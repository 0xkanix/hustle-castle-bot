package de.hustlebot.steuer;

import de.hustlebot.core.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import de.hustlebot.core.Sprache;

/**
 * Die wenigen Einstellungen, die NUR das Steuerprogramm betreffen: Sprachsteuerung
 * an/aus und Sprache, Windows-Meldungen, Mini-Leiste, welche Fenster beim Start
 * offen sind.
 *
 * Eigene Datei steuerprogramm.properties, damit config.properties unangetastet
 * bleibt (CLAUDE.md). Alles, was den Bot selbst betrifft (Bezahlen, Not-Aus-Taste,
 * Autostart ...), geht weiter ueber die Discord-Befehle und deren Dateien.
 */
public final class Steuereinstellungen {

    public static final String WINDOWS_MELDUNGEN = "windowsmeldungen";
    public static final String MINILEISTE = "minileiste.an";
    public static final String MINILEISTE_X = "minileiste.x";
    public static final String MINILEISTE_Y = "minileiste.y";
    public static final String FENSTER_OBENAUF = "fenster.obenauf";
    public static final String STEUERFENSTER_ZEIGEN = "steuerfenster.beimstart";
    /** Protokoll-Leiste im freien Streifen unter dem Spiel (Autor 11.10.), Vorgabe an. */
    public static final String PROTOKOLLLEISTE = "protokollleiste.an";
    /** "hell" (Vorgabe) oder "dunkel" -- Auftrag 4, Autor 11.10. */
    public static final String AUSSEHEN = "aussehen";
    /** Autor 24.10. (F34): Schriftgroesse klein / mittel / gross / riesig -- gilt beim naechsten Start (siehe Holzstil). */
    public static final String SCHRIFT = "schrift";

    private final Path datei;
    private final Properties p = new Properties();

    public Steuereinstellungen(Path datei) {
        this.datei = datei;
        if (Files.exists(datei)) {
            try (InputStream in = Files.newInputStream(datei)) {
                p.load(in);
            } catch (Exception e) {
                Log.warn("Steuerprogramm", Sprache.t(datei + " could not be read: " + e.getMessage(),
                        datei + " liess sich nicht lesen: " + e.getMessage()));
            }
        }
    }

    public synchronized String text(String schluessel, String vorgabe) {
        String w = p.getProperty(schluessel);
        return w == null || w.isBlank() ? vorgabe : w.trim();
    }

    public synchronized boolean an(String schluessel, boolean vorgabe) {
        String w = p.getProperty(schluessel);
        if (w == null || w.isBlank()) return vorgabe;
        String t = w.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("an") || t.equals("true") || t.equals("ja") || t.equals("1") || t.equals("on");
    }

    public synchronized int zahl(String schluessel, int vorgabe) {
        try {
            return Integer.parseInt(text(schluessel, String.valueOf(vorgabe)));
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public synchronized double kommazahl(String schluessel, double vorgabe) {
        try {
            return Double.parseDouble(text(schluessel, String.valueOf(vorgabe)).replace(',', '.'));
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public synchronized void setzen(String schluessel, String wert) {
        p.setProperty(schluessel, wert == null ? "" : wert);
        speichern();
    }

    public synchronized void setzen(String schluessel, boolean an) {
        setzen(schluessel, an ? "an" : "aus");
    }

    public synchronized void setzen(String schluessel, int zahl) {
        setzen(schluessel, String.valueOf(zahl));
    }

    private void speichern() {
        try (OutputStream out = Files.newOutputStream(datei)) {
            p.store(out, "Einstellungen des Steuerprogramms (Fenster, Windows-Meldungen, Aussehen). "
                    + "Wird vom Programm geschrieben.");
        } catch (Exception e) {
            Log.warn("Steuerprogramm", Sprache.t(datei + " could not be saved: " + e.getMessage(),
                    datei + " liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
