package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wie oft Discord "Run X fertig" meldet (Autor, 29.09.): jeden Run, alle 5 oder
 * alle 10 -- fuer alle Modi. Der erste Run wird immer gemeldet ("damit ich weiss,
 * dass alles klappt"). Datei meldungen.txt, Standard 10, !settings meldungen 1|5|10.
 */
public final class Meldetakt {

    private Meldetakt() {}

    private static final Path DATEI = Path.of("meldungen.txt");
    private static volatile int takt = laden();

    public static int takt() {
        return takt;
    }

    public static void setTakt(int neu) {
        takt = neu <= 1 ? 1 : neu <= 5 ? 5 : 10;
        try {
            Files.writeString(DATEI, "# Discord meldet jeden Run (1), alle 5 oder alle 10 -- gesetzt mit !settings meldungen\n"
                    + takt + "\n");
        } catch (Exception e) {
            Log.warn("Meldungen", Sprache.t("meldungen.txt not written: " + e.getMessage(),
                    "meldungen.txt nicht geschrieben: " + e.getMessage()));
        }
        Log.info("Meldungen", "Discord meldet " + beschreibung() + ".");
    }

    public static String beschreibung() {
        return Sprache.t(takt == 1 ? "every run" : "run 1, then every " + takt, takt == 1 ? "jeden Run" : "Run 1, dann alle " + takt);
    }

    private static int laden() {
        try {
            if (!Files.exists(DATEI)) return 10;
            for (String z : Dateien.zeilen(DATEI)) {
                String t = z.trim();
                if (t.matches("\\d{1,2}")) {
                    int n = Integer.parseInt(t);
                    return n <= 1 ? 1 : n <= 5 ? 5 : 10;
                }
            }
        } catch (Exception e) {
            // Standard
        }
        return 10;
    }
}
