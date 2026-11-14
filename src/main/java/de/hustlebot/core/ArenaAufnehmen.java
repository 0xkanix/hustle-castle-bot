package de.hustlebot.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arena-Gegner per Discord auf eine Liste setzen (Autor 21.10., aus dem Hauptbot: "!arena Rina sicher").
 *
 * Im Hauptbot gibt es dafuer eine eigene Namensliste (ArenaNamensliste). Der Freunde-Bot nimmt den Weg, den es hier
 * schon gibt: eine Zeile "Name | sicher" in arena-aufnehmen.txt im Bot-Ordner -- genau wie das Capture-Programm
 * ("Arena: type names"). Sobald der Bot den Namen auf einem Schild in der Arena-Pyramide liest, schneidet er das
 * Namensbild selbst aus, legt es in die Liste und streicht die Zeile (ArenaMode.aufZurufAufnehmen).
 *
 * Alles hier ist ohne Bildschirm und ohne Discord pruefbar.
 */
public final class ArenaAufnehmen {

    private ArenaAufnehmen() {
    }

    /** Die vier Listen. {@code wort} ist das Wort in der Datei (wie im Capture-Programm). */
    public enum Art {
        SICHER("sicher", "Safe win", "Sicherer Sieg"),
        MACHBAR("machbar", "Doable", "Machbar"),
        MEIDEN("meiden", "Avoid", "Meiden"),
        NIEMALS("niemals", "Never attack", "Niemals angreifen");

        public final String wort;
        public final String en;
        public final String de;

        Art(String wort, String en, String de) {
            this.wort = wort;
            this.en = en;
            this.de = de;
        }

        /** Anzeige in der eingestellten Sprache. */
        public String anzeige() {
            return Sprache.t(en, de);
        }
    }

    /** Ein Gegner mit seiner Liste. */
    public record Zeile(String name, Art art) {
    }

    /** Die Datei im Bot-Ordner. */
    public static Path datei() {
        return Ordner.bei("arena-aufnehmen.txt");
    }

    /** Das Listen-Wort (deutsch oder englisch, mit ein paar Nebenschreibweisen), oder null. */
    public static Art artAus(String wort) {
        if (wort == null) return null;
        String w = wort.toLowerCase(Locale.ROOT).strip();
        return switch (w) {
            case "sicher", "safe", "safewin", "sichersieg" -> Art.SICHER;
            case "machbar", "doable" -> Art.MACHBAR;
            case "meiden", "avoid", "ungern" -> Art.MEIDEN;
            case "niemals", "never", "nie", "block", "blockieren" -> Art.NIEMALS;
            default -> null;
        };
    }

    /**
     * "Rina sicher", "Walking Death avoid", "\"Dr. Emil\" never": das letzte Wort ist die Liste, davor steht der Name
     * (auch mit Leerzeichen, auch in Anfuehrungszeichen). Null, wenn kein Name oder kein gueltiges Listen-Wort dasteht.
     */
    public static Zeile zerlegen(String rest) {
        if (rest == null) return null;
        String t = rest.strip();
        int ende = t.lastIndexOf(' ');
        if (ende <= 0) return null;   // nur ein Wort: entweder nur der Name oder nur die Liste
        Art art = artAus(t.substring(ende + 1));
        if (art == null) return null;
        String name = sauber(t.substring(0, ende));
        return name.isEmpty() ? null : new Zeile(name, art);
    }

    /** Anfuehrungszeichen weg, kein "|" (das trennt in der Datei), Leerraum zusammen, nicht endlos lang. */
    static String sauber(String roh) {
        String s = roh.strip();
        while (s.length() >= 2 && "\"'“”„‘’".indexOf(s.charAt(0)) >= 0
                && "\"'“”„‘’".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(1, s.length() - 1).strip();
        }
        s = s.replace('|', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return s.length() > 60 ? s.substring(0, 60).strip() : s;
    }

    /**
     * Schreibt "Name | wort" in die Datei. Steht der Name schon drin (Gross-/Kleinschreibung egal), wird die Zeile
     * ersetzt statt doppelt angehaengt -- wie im Capture-Programm.
     *
     * @return das Wort der ersetzten Zeile (z. B. "machbar"), oder null, wenn der Name neu war
     */
    public static synchronized String eintragen(Path datei, Zeile z) throws IOException {
        List<String> zeilen = Files.exists(datei)
                ? new ArrayList<>(Dateien.zeilen(datei))
                : new ArrayList<>(List.of("# Arena: Name | Liste (machbar, sicher, niemals, meiden)"));
        String alt = null;
        List<String> neu = new ArrayList<>();
        for (String s : zeilen) {
            if (s.contains("|") && !s.strip().startsWith("#")) {
                String[] t = s.split("\\|", 2);
                if (t[0].strip().equalsIgnoreCase(z.name())) {
                    alt = t[1].strip();
                    continue;
                }
            }
            neu.add(s);
        }
        neu.add(z.name() + " | " + z.art().wort);
        Files.write(datei, neu, StandardCharsets.UTF_8);
        return alt;
    }

    /** Was noch auf sein Schild wartet: die Zeilen "Name | wort" (ohne Kopfzeile und Leerzeilen). */
    public static synchronized List<String> offene(Path datei) {
        List<String> l = new ArrayList<>();
        try {
            if (!Files.exists(datei)) return l;
            for (String s : Dateien.zeilen(datei)) {
                if (s.isBlank() || s.strip().startsWith("#") || !s.contains("|")) continue;
                l.add(s.strip());
            }
        } catch (IOException e) {
            Log.debug("Arena", "arena-aufnehmen.txt nicht lesbar: " + e.getMessage());
        }
        return l;
    }
}
