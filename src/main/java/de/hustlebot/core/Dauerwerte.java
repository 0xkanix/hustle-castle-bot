package de.hustlebot.core;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Einstellungen, die vorher nur im Speicher lagen und nach einem Neustart weg waren
 * (Autor, 12.10.: "jede Einstellung muss nach Schliessen und Neu-Oeffnen von Programm und Bot
 * noch genauso gelten"): Takt von !stats, Abschaltzeit von !ende und !off (PC aus um).
 * Datei dauerwerte.properties im Bot-Ordner; jede
 * Aenderung wird sofort geschrieben, gelesen wird beim Start.
 */
public final class Dauerwerte {

    public static final String STATS = "stats.minuten";
    /** Autor 21.10. (aus dem Hauptbot, fuer !watch): alle so viele Minuten ein Bild vom Spiel; 0 = aus. */
    public static final String WACH = "wach.minuten";
    public static final String ENDE_UHRZEIT = "ende.uhrzeit";
    public static final String ENDE_UM = "ende.um";
    public static final String AUS_UHRZEIT = "aus.uhrzeit";
    public static final String AUS_UM = "aus.um";

    private static Path datei() {
        return Ordner.bei("dauerwerte.properties");
    }

    private Dauerwerte() {}

    public static synchronized String lesen(String schluessel, String vorgabe) {
        return laden().getProperty(schluessel, vorgabe);
    }

    public static long zahl(String schluessel, long vorgabe) {
        try {
            return Long.parseLong(lesen(schluessel, "").trim());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public static int zahl(String schluessel, int vorgabe) {
        return (int) zahl(schluessel, (long) vorgabe);
    }

    /** Leer oder null loescht den Wert (dann gilt beim naechsten Start die Vorgabe). */
    public static synchronized void schreiben(String schluessel, Object wert) {
        Properties p = laden();
        String w = wert == null ? "" : String.valueOf(wert).trim();
        if (w.isEmpty()) p.remove(schluessel);
        else p.setProperty(schluessel, w);
        try (Writer out = Files.newBufferedWriter(datei(), StandardCharsets.UTF_8)) {
            p.store(out, "Einstellungen, die einen Neustart ueberleben (!stats, !ende, !off) -- schreibt der Bot selbst");
        } catch (Exception e) {
            Log.warn("Einstellungen", Sprache.t("dauerwerte.properties not written: " + e.getMessage(),
                    "dauerwerte.properties nicht geschrieben: " + e.getMessage()));
        }
    }

    private static Properties laden() {
        Properties p = new Properties();
        if (!Files.exists(datei())) return p;
        try (Reader in = Files.newBufferedReader(datei(), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (Exception e) {
            Log.warn("Einstellungen", Sprache.t("dauerwerte.properties not readable: " + e.getMessage(),
                    "dauerwerte.properties nicht lesbar: " + e.getMessage()));
        }
        return p;
    }
}
