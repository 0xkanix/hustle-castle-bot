package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wer laut Kampfprotokoll gegen Autor verloren hat -- FUER IMMER gemerkt, getrennt vom Notizblock
 * (Autor, 13.10., Punkt 14). Der Notizblock wird bewertet und umgebaut; diese Liste ist nur ein
 * Gedaechtnis: Name, wie oft, zuletzt wann, und ob er Autor angegriffen hat oder von Autor
 * angegriffen wurde.
 *
 * Datei arena-verlierer.txt (Tab-getrennt): Name | Anzahl | zuletzt | Art
 *   Art: "angriff" = er hat Autor angegriffen und verloren, "verteidigung" = Autor hat ihn angegriffen
 *   und gewonnen, "beides" = schon beides vorgekommen.
 */
public final class ArenaVerlierer {

    private static final Path DATEI = Path.of("arena-verlierer.txt");
    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String KOPF = "# Wer laut Kampfprotokoll gegen Autor verloren hat (fuer immer, Befehl !arena verloren).\n"
            + "# Name\tAnzahl\tzuletzt\tArt (angriff = hat dich angegriffen und verloren, verteidigung = von dir besiegt)";

    /** Ein Eintrag; Name in Vergleichsform als Schluessel, Anzeige-Name wie zuletzt gelesen. */
    public record Eintrag(String name, int anzahl, String zuletzt, String art) {
        public String text() {
            return Sprache.t(name + " – " + anzahl + "x (last " + zuletzt + ", " + art + ")", name + " – " + anzahl + "x (zuletzt " + zuletzt + ", " + art + ")");
        }
    }

    private static Map<String, Eintrag> eintraege;
    /** Code-Pruefung 16.10.: Lesen der Datei fehlgeschlagen? Dann NICHT ueberschreiben (sonst waere die Liste weg). */
    private static boolean ladeFehler;

    private ArenaVerlierer() { }

    private static synchronized Map<String, Eintrag> laden() {
        if (eintraege != null) return eintraege;
        Map<String, Eintrag> m = new LinkedHashMap<>();
        ladeFehler = false;
        if (Files.exists(DATEI)) {
            try {
                // new String(bytes, UTF_8) ersetzt kaputte Bytes durch ein Ersatzzeichen, statt wie
                // readAllLines(UTF_8) eine MalformedInputException zu werfen (Datei als ANSI gespeichert oder
                // mitten im Zeichen abgeschnitten). So geht hoechstens ein Zeichen verloren, nicht die ganze Liste.
                String inhalt = new String(Files.readAllBytes(DATEI), StandardCharsets.UTF_8);
                for (String z : inhalt.split("\\R")) {
                    if (z.isBlank() || z.startsWith("#")) continue;
                    String[] t = z.split("\t");
                    if (t.length < 4) continue;
                    int n;
                    try { n = Integer.parseInt(t[1].trim()); } catch (NumberFormatException e) { n = 1; }
                    m.put(schluessel(t[0]), new Eintrag(t[0].trim(), n, t[2].trim(), t[3].trim()));
                }
            } catch (Exception e) {
                // Echter Lesefehler (nicht nur ein kaputtes Zeichen): Liste NICHT als leer uebernehmen und
                // NICHT zurueckschreiben -- sonst wuerde der naechste Eintrag die ganze Datei ueberschreiben.
                ladeFehler = true;
                Log.warn("Arena", Sprache.t("arena-verlierer.txt not readable -- list left untouched: " + e.getMessage(),
                        "arena-verlierer.txt nicht lesbar -- Liste bleibt unangetastet: " + e.getMessage()));
            }
        }
        if (!ladeFehler) eintraege = m;
        return m;
    }

    private static String schluessel(String name) {
        return NamensVergleich.form(name == null ? "" : name).toLowerCase(Locale.ROOT);
    }

    /**
     * Einen Verlierer vermerken. angriff = er hat Autor angegriffen und verloren; sonst hat Autor ihn
     * angegriffen und gewonnen. @return true, wenn der Name neu in der Liste ist
     */
    public static synchronized boolean vermerken(String name, boolean angriff) {
        if (name == null || name.isBlank()) return false;
        Map<String, Eintrag> m = laden();
        String k = schluessel(name);
        if (k.isEmpty()) return false;
        String artNeu = angriff ? "angriff" : "verteidigung";
        Eintrag alt = m.get(k);
        String art = alt == null || alt.art().equals(artNeu) ? artNeu : "beides";
        Eintrag neu = new Eintrag(name.trim(), alt == null ? 1 : alt.anzahl() + 1, LocalDateTime.now().format(STEMPEL), art);
        m.put(k, neu);
        speichern(m);
        return alt == null;
    }

    public static synchronized boolean hatVerloren(String name) {
        return laden().containsKey(schluessel(name));
    }

    public static synchronized int anzahl() {
        return laden().size();
    }

    /** Die Liste fuer Discord, neueste zuerst, hoechstens 40 Zeilen. */
    public static synchronized String text() {
        List<Eintrag> l = new ArrayList<>(laden().values());
        l.sort((a, b) -> b.zuletzt().compareTo(a.zuletzt()));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Eintrag e : l) {
            if (++n > 40) { sb.append(Sprache.t("… and ", "… und ")).append(l.size() - 40).append(Sprache.t(" more (arena-verlierer.txt)\n", " weitere (arena-verlierer.txt)\n")); break; }
            sb.append(e.text()).append('\n');
        }
        return sb.toString().strip();
    }

    private static void speichern(Map<String, Eintrag> m) {
        if (ladeFehler) {
            Log.warn("Arena", Sprache.t("arena-verlierer.txt was not saved: it was not readable before, " + "the existing file stays (otherwise the list would be gone).",
                    "arena-verlierer.txt wurde nicht gespeichert: sie war vorher nicht lesbar, "
                    + "die vorhandene Datei bleibt stehen (sonst waere die Liste weg)."));
            return;
        }
        List<String> z = new ArrayList<>();
        z.add(KOPF);
        for (Eintrag e : m.values()) {
            z.add(e.name() + "\t" + e.anzahl() + "\t" + e.zuletzt() + "\t" + e.art());
        }
        try {
            // Atomar schreiben: erst in eine Nebendatei, dann umbenennen. Ein Abbruch mitten im Schreiben
            // (Neustart, Stromausfall) laesst sonst eine halbe, kaputte Datei zurueck (Code-Pruefung 16.10.).
            Path tmp = DATEI.resolveSibling("arena-verlierer.txt.neu");
            Files.write(tmp, z, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, DATEI, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomarNichtMoeglich) {
                Files.move(tmp, DATEI, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("arena-verlierer.txt not saved: " + e.getMessage(),
                    "arena-verlierer.txt nicht gespeichert: " + e.getMessage()));
        }
    }
}
