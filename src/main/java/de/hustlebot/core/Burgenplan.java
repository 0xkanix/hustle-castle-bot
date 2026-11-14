package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Burgen angreifen alle 30 Minuten (Autor 25.10., Zweitkonto TR5): "Jetzt sind wieder neue Enemies da. Ich glaube, es
 * hat circa 40 Minuten jetzt gedauert. Der Bot soll bitte alle 30 Minuten nachschauen, ob neue Gegner da sind."
 *
 * Eingeschoben wie Leuchtturm und Clan-Krieg (DiscordBridge.nebenbeiPruefen): nach dem laufenden Run, danach geht es
 * weiter. Gleich beim ersten Blick nach dem Start, dann alle 30 Minuten.
 *
 * Schalter burgen-plan.txt im Bot-Ordner, erste Zeile "an" oder "aus". Ohne Datei AUS -- beim Hauptkonto lohnen die
 * Burgen nicht, nur beim Zweitkonto-Bot steht die Datei auf "an".
 */
public final class Burgenplan {

    public static final long ABSTAND_MS = 30 * 60_000L;
    private static final String DATEI = "burgen-plan.txt";

    private static long angestossenUm;
    private static Boolean an;
    private static long gelesenUm;

    private Burgenplan() {
    }

    /** Schalter an? (Datei hoechstens alle 10 s neu gelesen.) */
    public static synchronized boolean istAn() {
        long jetzt = System.currentTimeMillis();
        if (an != null && jetzt - gelesenUm < 10_000) return an;
        gelesenUm = jetzt;
        an = lesen(Ordner.bei(DATEI));
        return an;
    }

    static boolean lesen(Path datei) {
        try {
            if (!Files.exists(datei)) return false;
            for (String z : Dateien.zeilen(datei)) {
                String w = z.trim().toLowerCase(java.util.Locale.ROOT);
                if (w.isEmpty() || w.startsWith("#")) continue;
                return w.startsWith("an") || w.startsWith("on");
            }
        } catch (Exception e) {
            Log.debug("Burgen", "burgen-plan.txt nicht lesbar: " + e.getMessage());
        }
        return false;
    }

    /** Wieder dran? (Schalter an und seit dem letzten Einschub 30 Minuten vergangen.) */
    public static synchronized boolean faellig() {
        return istAn() && (angestossenUm == 0 || System.currentTimeMillis() - angestossenUm >= ABSTAND_MS);
    }

    public static synchronized void angestossen() {
        angestossenUm = System.currentTimeMillis();
    }
}
