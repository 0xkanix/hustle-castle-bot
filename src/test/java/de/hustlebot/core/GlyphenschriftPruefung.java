package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Pruefprogramm fuer die Glyphenschrift (Autor 24.10.): zerlegt alle Namensbilder, die der Bot gespeichert hat,
 * in eine frische Bibliothek und berichtet, ob Bilder mit gleichem Namen denselben Fingerabdruck bekommen.
 * Liest die Bilder nur -- geschrieben wird ausschliesslich in den angegebenen Bibliotheksordner.
 *
 * Der Name eines Bildes steht im Dateinamen (ar_machbar_Сволочь_1024-0358.png = Windows-Lesung "Сволочь");
 * Namensbilder "ar_name_Bild-1002-012347.png" bekommen ihren Namen aus arena-namensbilder.txt.
 * Durchnummerierte Vorlagen (ar_machbar_1.png) sind keine Namen und zaehlen nicht.
 *
 * Aufruf von Hand: java ... GlyphenschriftPruefung [Bibliotheksordner]
 */
public final class GlyphenschriftPruefung {

    /** Wo der Bot Namensbilder abgelegt hat (nur lesen). */
    static final List<Path> WURZELN = List.of(
            Path.of("My pictures/Arena lists"),
            Path.of("gelernt/arena/gegner/namensbilder"),
            Path.of("templates/arena/gegner"),
            Path.of("templates/gelernt/arena/gegner"));
    static final List<Path> NAMENSLISTEN = List.of(Path.of("arena-namensbilder.txt"));

    private static final Pattern DATEI = Pattern.compile("ar_(machbar|sicher|ungern|block|clan|name)_(.+?)(_\\d{4}-\\d{4})?\\.png");

    private GlyphenschriftPruefung() {
    }

    /** Ein Namensbild mit seinem Namen (Windows-Lesung) und einem Inhalts-Kennwert (gleiche Datei = gleich). */
    record Bild(Path pfad, String name, String inhalt, BufferedImage bild) {
    }

    /** Ergebnis in Zahlen, damit der Test darauf pruefen kann. */
    record Bericht(int bilder, int namen, int glyphen, int paareMehrfach, int paareGleich, int paareDerselbe,
                   int paareVerwandt, int fremdeTreffer, int fremdePaare, double msJeName, int gelesenRichtig,
                   int gelesenGesamt, String text) {
    }

    public static void main(String[] args) throws Exception {
        Path bib = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("glyphen-pruefung");
        System.out.println(pruefen(bib).text());
    }

    private static String klein(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    static int editierabstand(String a, String b) {
        int[] vor = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vor[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++)
                jetzt[j] = Math.min(Math.min(vor[j] + 1, jetzt[j - 1] + 1), vor[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = vor; vor = jetzt; jetzt = t;
        }
        return vor[b.length()];
    }

    /** Alle vorhandenen Namensbilder laden (fehlende Ordner werden uebersprungen). */
    static List<Bild> bilderLaden() throws Exception {
        Map<String, String> lesbar = new TreeMap<>();
        for (Path p : NAMENSLISTEN) {
            if (!Files.exists(p)) continue;
            for (String z : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int i = z.indexOf('=');
                if (z.startsWith("#") || i < 0) continue;
                lesbar.put(z.substring(0, i).strip(), z.substring(i + 1).strip());
            }
        }
        List<Bild> aus = new ArrayList<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Path w : WURZELN) {
            if (!Files.isDirectory(w)) continue;
            List<Path> dateien;
            try (Stream<Path> s = Files.walk(w)) {
                dateien = s.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path p : dateien) {
                Matcher m = DATEI.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                String name = Normalizer.normalize(m.group(2), Normalizer.Form.NFC);
                if (name.matches("\\d+")) continue;                      // durchnummerierte Vorlage, kein Name
                if (name.startsWith("Bild-")) name = lesbar.getOrDefault(name, name);
                byte[] roh = Files.readAllBytes(p);
                BufferedImage b = ImageIO.read(p.toFile());
                if (b == null) continue;
                aus.add(new Bild(p, name, HexFormat.of().formatHex(md.digest(roh)), b));
            }
        }
        return aus;
    }

    /** Zerlegt alle Bilder in eine frische Bibliothek in "bibliothek" und schreibt den Bericht. */
    static Bericht pruefen(Path bibliothek) throws Exception {
        List<Bild> bilder = bilderLaden();
        Glyphenschrift gs = new Glyphenschrift(bibliothek);
        gs.setNamensbilderSpeichern(false);

        // 1. Durchgang: Bibliothek aufbauen
        Map<Bild, String> fp = new LinkedHashMap<>();
        for (Bild b : bilder) fp.put(b, gs.erkennen(b.bild()).fingerabdruck());

        // 2. Durchgang: Zeit messen (Bibliothek fertig, wie im Betrieb)
        long t0 = System.nanoTime();
        int runden = 3;
        for (int r = 0; r < runden; r++) for (Bild b : bilder) gs.erkennen(b.bild());
        double ms = bilder.isEmpty() ? 0 : (System.nanoTime() - t0) / 1e6 / (runden * bilder.size());

        // Gleiche Namen mit verschiedenen Bilddateien
        Map<String, List<Bild>> jeName = new TreeMap<>();
        for (Bild b : bilder) jeName.computeIfAbsent(b.name(), k -> new ArrayList<>()).add(b);
        StringBuilder sb = new StringBuilder();
        int mehrfach = 0, gleich = 0, derselbe = 0, verwandt = 0, gleicheDatei = 0;
        sb.append("Gleiche Namen, verschiedene Bilder:\n");
        for (var e : jeName.entrySet()) {
            List<Bild> l = e.getValue();
            for (int i = 0; i < l.size(); i++)
                for (int j = i + 1; j < l.size(); j++) {
                    if (l.get(i).inhalt().equals(l.get(j).inhalt())) { gleicheDatei++; continue; }
                    String a = fp.get(l.get(i)), b = fp.get(l.get(j));
                    mehrfach++;
                    boolean g = a.equals(b), d = Glyphenschrift.derselbeName(a, b), v = gs.derselbeNameVerwandt(a, b);
                    if (g) gleich++;
                    if (d) derselbe++;
                    if (v) verwandt++;
                    sb.append(String.format("  %-16s %s | %s  %s%n", e.getKey(), a, b,
                            g ? "GLEICH" : d ? "derselbe Name" : v ? "derselbe (verwandt)" : "VERSCHIEDEN"));
                }
        }

        // Klar verschiedene Namen (Lesungen unterscheiden sich in mind. 3 Zeichen), die faelschlich als derselbe
        // gelten. Fast gleiche Lesungen ("Karen"/"karen", "JkNXED"/"JtNXED") sind meist derselbe Spieler, den
        // Windows verschieden gelesen hat -- die werden nur aufgelistet.
        List<String> namen = new ArrayList<>(jeName.keySet());
        int fremd = 0, paare = 0;
        StringBuilder fremdeListe = new StringBuilder(), aehnlicheListe = new StringBuilder();
        for (int i = 0; i < namen.size(); i++)
            for (int j = i + 1; j < namen.size(); j++) {
                String a = fp.get(jeName.get(namen.get(i)).get(0)), b = fp.get(jeName.get(namen.get(j)).get(0));
                if (a.isEmpty() || b.isEmpty()) continue;
                boolean klar = editierabstand(klein(namen.get(i)), klein(namen.get(j))) >= 3;
                if (klar) paare++;
                if (!gs.derselbeNameVerwandt(a, b)) continue;
                String zeile = "  " + namen.get(i) + " ~ " + namen.get(j) + "  (" + a + " | " + b + ")\n";
                if (klar) {
                    fremd++;
                    if (fremd <= 15) fremdeListe.append(zeile);
                } else aehnlicheListe.append(zeile);
            }

        // Lernen: jede Windows-Lesung einmal lernen, dann nachlesen
        for (Bild b : bilder) gs.lernen(b.bild(), b.name());
        int richtig = 0, gesamt = 0;
        for (Bild b : bilder) {
            String soll = Glyphenschrift.sauber(b.name());
            if (soll == null) continue;
            gesamt++;
            if (gs.lesen(b.bild()).replace(" ", "").equals(soll)) richtig++;
        }
        gs.speichern();

        String kopf = String.format("Glyphenschrift-Pruefung: %d Bilder, %d Namen, %d Glyphen in der Bibliothek, %.2f ms je Name%n"
                        + "Namen mit mehreren verschiedenen Bildern: %d Paare -- gleicher Fingerabdruck %d, derselbeName %d, "
                        + "derselbeNameVerwandt %d (dazu %d Paare mit identischer Datei)%n"
                        + "Klar verschiedene Namen faelschlich gleich (verwandt): %d von %d Paaren%n"
                        + "Nach dem Lernen exakt gelesen: %d von %d Bildern mit sauberer Windows-Lesung%n",
                bilder.size(), jeName.size(), gs.anzahl(), ms, mehrfach, gleich, derselbe, verwandt, gleicheDatei,
                fremd, paare, richtig, gesamt);
        String text = kopf + sb + (fremd > 0 ? "Faelschlich gleich (bis 15):\n" + fremdeListe : "")
                + (aehnlicheListe.length() > 0 ? "Fast gleiche Lesung, als derselbe erkannt (meist derselbe Spieler):\n" + aehnlicheListe : "");
        return new Bericht(bilder.size(), jeName.size(), gs.anzahl(), mehrfach, gleich, derselbe, verwandt, fremd,
                paare, ms, richtig, gesamt, text);
    }
}
