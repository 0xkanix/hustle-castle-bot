package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wann das Titan-Event endet -- abgelesen im Event ("Event ends in: 9d 22h 37m") und in titan-ende.txt gemerkt,
 * damit die Kathedrale den Endspurt planen kann (Autor, 09.10.: "kurz vor Eventende soll der Bot alles
 * verbrauchen ... wenn er noch in der Kathedrale ist, soll er rausgehen. Endspurt = die letzten 12 Stunden").
 */
public final class Titanende {

    private static final Path DATEI = Path.of("titan-ende.txt");
    private static volatile long endeMs = laden();

    private Titanende() {
    }

    /** Restzeit in Minuten wurde im Event abgelesen. */
    public static void setzen(long restMinuten) {
        if (restMinuten < 0) return;
        long neu = System.currentTimeMillis() + restMinuten * 60_000L;
        // Nur nennenswerte Aenderungen schreiben (Lesungen schwanken um eine Minute).
        if (Math.abs(neu - endeMs) < 3 * 60_000L) return;
        endeMs = neu;
        try {
            Files.writeString(DATEI, "# Ende des Titan-Events (Millisekunden seit 1970), abgelesen im Event\n" + neu + "\n");
        } catch (Exception e) {
            Log.debug("Titan-Event", DATEI + " nicht geschrieben: " + e.getMessage());
        }
    }

    /** Minuten bis zum Ende; -1, wenn unbekannt oder schon vorbei. */
    public static long restMinuten() {
        if (endeMs <= 0) return -1;
        long rest = (endeMs - System.currentTimeMillis()) / 60_000L;
        return rest < 0 ? -1 : rest;
    }

    /** Das abgelesene Event-Ende liegt schon hinter uns (false, wenn unbekannt). Freunde, 10.10. */
    public static boolean vorbei() {
        return endeMs > 0 && System.currentTimeMillis() > endeMs;
    }

    private static long laden() {
        try {
            if (!Files.exists(DATEI)) return 0;
            for (String z : Dateien.zeilen(DATEI)) {
                String t = z.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return Long.parseLong(t);
            }
        } catch (Exception e) {
            Log.debug("Titan-Event", DATEI + " nicht lesbar: " + e.getMessage());
        }
        return 0;
    }
}
