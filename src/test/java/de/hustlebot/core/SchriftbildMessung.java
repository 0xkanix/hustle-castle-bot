package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Messung fuer das Schriftbild (Autor 21.11.) an den echten Namensbildern im Bot-Ordner -- nur lesen. Laeuft nicht mit
 * den normalen Tests (Name endet nicht auf Test); von Hand: mvn test -Dtest=SchriftbildMessung -Dmessung.aus=DATEI.
 *
 * Quellen: Listenbilder (Name im Dateinamen), glyphen\namen\<kurz>.png (Eintrag in arena-daten.txt ueber die
 * Fingerabdruecke), logs\schmuckschrift (Schild, Kampfprotokoll, Fenster; Name = Lesung in liste.tsv).
 * Gleich = gleiche Bezeichnung; verschieden = Bezeichnungen mit Editierabstand >= 2, keine steckt in der anderen, und
 * (wenn beide bekannt) Kraft mehr als 5 % auseinander.
 */
public class SchriftbildMessung {

    record Quelle(char art, String datei, String spieler, long kraft, BufferedImage bild) {
    }

    record Probe(Quelle q, Schriftbild sb) {
    }

    @Test
    void messen() throws Exception {
        Path bot = Path.of("C:/HustleBot");
        Assumptions.assumeTrue(Files.isDirectory(bot.resolve("glyphen/namen")), "kein Bot-Ordner");
        List<Quelle> quellen = new ArrayList<>();
        Set<String> gesehen = new HashSet<>();
        for (GlyphenschriftPruefung.Bild b : GlyphenschriftPruefung.bilderLaden()) {
            if (gesehen.add(b.inhalt())) quellen.add(new Quelle('S', b.pfad().getFileName().toString(), klein(b.name()), -1, b.bild()));
        }
        for (String z : Files.readAllLines(bot.resolve("arena-daten.txt"), StandardCharsets.UTF_8)) {
            if (z.startsWith("#")) continue;
            String[] t = z.split("\t", -1);
            if (t.length < 18 || t[17].isBlank()) continue;
            long kraft;
            try { kraft = Long.parseLong(t[3].strip()); } catch (NumberFormatException e) { kraft = -1; }
            for (String fp : t[17].split("[|]")) {
                Path p = bot.resolve("glyphen/namen/" + Glyphenschrift.kurz(fp.strip()) + ".png");
                if (!Files.exists(p)) continue;
                quellen.add(new Quelle('S', p.getFileName().toString(), "#" + t[0] + "/" + t[1], kraft, ImageIO.read(p.toFile())));
            }
        }
        Path schmuck = bot.resolve("logs/schmuckschrift");
        if (Files.exists(schmuck.resolve("liste.tsv"))) {
            for (String z : Files.readAllLines(schmuck.resolve("liste.tsv"), StandardCharsets.UTF_8)) {
                String[] t = z.split("\t");
                if (t.length < 2 || !Files.exists(schmuck.resolve(t[0])) || !gesehen.add(t[0])) continue;
                String kern = klein(t[1]);
                if (kern.length() < 3) continue;
                BufferedImage b = ImageIO.read(schmuck.resolve(t[0]).toFile());
                int w = b.getWidth(), h = b.getHeight();
                if (w == 162 && h == 80) {
                    quellen.add(new Quelle('S', "schmuck/" + t[0], "~" + kern, -1, b.getSubimage((int) (w * 0.36), 0, w - (int) (w * 0.36), (int) (h * 0.42))));
                } else if (h >= 30 && h <= 40 && w >= 170) {
                    quellen.add(new Quelle('P', "schmuck/" + t[0], "~" + kern, -1, b));
                } else if (w == 214 && h == 53) {
                    quellen.add(new Quelle('F', "schmuck/" + t[0], "~" + kern, -1, b.getSubimage(0, 0, w, h / 2)));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Quellen: ").append(quellen.size()).append('\n');
        String ziel = System.getProperty("messung.aus", "");
        for (int hoehe : new int[]{12}) {
            for (int vergleich : new int[]{Schriftbild.GEWICHTET}) {
                Schriftbild.vergleich = vergleich;
                boolean genau = hoehe == Integer.getInteger("messung.hoehe", 12) && vergleich == Integer.getInteger("messung.vergleich", 3);
                auswerten(quellen, hoehe, vergleich, genau, out, ziel);
            }
        }
        Schriftbild.vergleich = Schriftbild.GEWICHTET;
        if (!ziel.isBlank()) Files.writeString(Path.of(ziel), out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    private static void auswerten(List<Quelle> quellen, int hoehe, int vergleich, boolean genau, StringBuilder out, String ziel) throws Exception {
        List<Probe> proben = new ArrayList<>();
        int leer = 0;
        long laenge = 0, maxLaenge = 0;
        for (Quelle q : quellen) {
            Schriftbild sb = Schriftbild.aus(q.bild(), q.art(), hoehe);
            if (sb == null) { leer++; continue; }
            proben.add(new Probe(q, sb));
            int l = sb.text().length();
            laenge += l;
            maxLaenge = Math.max(maxLaenge, l);
            if (!sb.equals(Schriftbild.lesen(sb.text()))) out.append("FEHLER Hin-und-zurueck: ").append(q.datei()).append('\n');
        }
        out.append(String.format(Locale.ROOT, "%n== Hoehe %d, Vergleich %d: %d Bilder (%d ohne Schrift), Text Mittel %.0f / max %d Zeichen%n",
                hoehe, vergleich, proben.size(), leer, (double) laenge / Math.max(1, proben.size()), maxLaenge));
        List<double[]> gleich = new ArrayList<>(), fremd = new ArrayList<>();
        List<String> gleichText = new ArrayList<>(), fremdText = new ArrayList<>(), schmuckText = new ArrayList<>();
        long t0 = System.nanoTime(), vergleiche = 0;
        for (int i = 0; i < proben.size(); i++) {
            for (int j = i + 1; j < proben.size(); j++) {
                Probe a = proben.get(i), b = proben.get(j);
                if (a.q().art() != b.q().art()) continue;
                String la = a.q().spieler(), lb = b.q().spieler();
                boolean selber = la.equals(lb);
                boolean listeGegenDaten = la.charAt(0) == '#' ^ lb.charAt(0) == '#';
                boolean schmuck = la.charAt(0) == '~' || lb.charAt(0) == '~';
                boolean kraftAnders = a.q().kraft() > 0 && b.q().kraft() > 0
                        && Math.abs(a.q().kraft() - b.q().kraft()) > Math.max(a.q().kraft(), b.q().kraft()) * 0.05;
                boolean kraftUnbekannt = a.q().kraft() <= 0 || b.q().kraft() <= 0;
                String ka = la.replaceAll("^[#~]", "").replaceAll("/.*", ""), kb = lb.replaceAll("^[#~]", "").replaceAll("/.*", "");
                boolean klarAnders = !selber && GlyphenschriftPruefung.editierabstand(ka, kb) >= 2 && !ka.contains(kb) && !kb.contains(ka)
                        && (kraftAnders || kraftUnbekannt) && !listeGegenDaten;
                if (!selber && !klarAnders) continue;
                double[] v = a.sb().vergleichen(b.sb());
                vergleiche++;
                String zeile = String.format(Locale.ROOT, "%.3f %.3f %c %s (%s) <> %s (%s)", v[0], v[1], a.q().art(), la, a.q().datei(), lb, b.q().datei());
                if (selber) { gleich.add(v); gleichText.add(zeile); }
                else if (!schmuck) { fremd.add(v); if (v[0] < 0.25) fremdText.add(zeile); }
                else if (v[0] < 0.12 && v[1] < 0.4) schmuckText.add(zeile);
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        out.append(String.format(Locale.ROOT, "Vergleiche %d (%.2f us je Vergleich); gleiche Paare %d, verschiedene (ohne Schmuck-Lesungen) %d%n",
                vergleiche, ms * 1000 / Math.max(1, vergleiche), gleich.size(), fremd.size()));
        for (double g : new double[]{0.06, 0.08, 0.10, 0.12, 0.15}) {
            StringBuilder z = new StringBuilder(String.format(Locale.ROOT, "  gesamt bis %.2f:", g));
            for (double l : new double[]{0.12, 0.15, 0.20, 0.25, 0.30, 1.0}) {
                long gl = gleich.stream().filter(x -> x[0] <= g && x[1] <= l).count();
                long fr = fremd.stream().filter(x -> x[0] <= g && x[1] <= l).count();
                z.append(String.format(Locale.ROOT, " | oertl %.2f: %.0f %% / %d", l, 100.0 * gl / Math.max(1, gleich.size()), fr));
            }
            out.append(z).append('\n');
        }
        if (!genau) return;
        gleichText.sort((x, y) -> -x.substring(6, 11).compareTo(y.substring(6, 11)));
        fremdText.sort(String::compareTo);
        out.append("\nGleiche Paare, groesster oertlicher Abstand:\n");
        gleichText.stream().filter(s -> !s.startsWith("1.000")).limit(40).forEach(s -> out.append(s).append('\n'));
        out.append("\nGleiche Paare ganz verschieden: ").append(gleichText.stream().filter(s -> s.startsWith("1.000")).count()).append('\n');
        gleichText.stream().filter(s -> s.startsWith("1.000")).limit(15).forEach(s -> out.append(s).append('\n'));
        out.append("\nNaechste fremde Paare (Listen/Daten):\n");
        fremdText.stream().limit(40).forEach(s -> out.append(s).append('\n'));
        out.append("\nSchmuck-Paare mit anderer Lesung, nah (zum Ansehen):\n");
        schmuckText.sort(String::compareTo);
        int[] jeStufe = new int[20];
        for (String s : schmuckText) {
            int stufe = (int) (Double.parseDouble(s.substring(0, 5)) * 100);
            if (stufe < 20 && jeStufe[stufe]++ < 4) out.append(s).append('\n');
        }
        if (!ziel.isBlank()) {
            Path ordner = Path.of(ziel + "-bilder");
            Files.createDirectories(ordner);
            for (Probe p : proben) {
                String n = p.q().datei().replace('/', '_');
                ImageIO.write(p.sb().bild(), "png", ordner.resolve(n).toFile());
            }
        }
    }

    private static String klein(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
