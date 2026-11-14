package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Merkt, ob der Bot beim letzten Mal sauber beendet wurde -- oder ob ihm
 * jemand den Stecker gezogen hat.
 *
 * Wie das geht: Der Bot legt beim Start eine kleine Datei an und traegt dort
 * alle halbe Minute die aktuelle Uhrzeit ein. Beim ordentlichen Beenden loescht
 * er sie wieder.
 *
 * Ist die Datei beim naechsten Start noch da, war das Ende nicht ordentlich --
 * Stromausfall, ausgeschaltete Steckdose, Absturz. Die zuletzt eingetragene
 * Uhrzeit sagt dann auch, WANN es passiert ist.
 *
 * Wozu das gut ist: Der Bot laeuft naechtelang, ohne dass jemand hinsieht. Wenn
 * er morgens weniger geschafft hat als erwartet, will man wissen, ob er
 * gearbeitet hat und nur langsam war -- oder ob er um halb drei einfach
 * ausgegangen ist.
 */
public final class CrashDetector {

    /** Die Datei liegt im Protokollordner und faellt dort nicht weiter auf. */
    private static final Path MARKER = Path.of("logs", "laeuft.marker");

    /** So oft wird die Uhrzeit aufgefrischt. */
    private static final long HEARTBEAT_MS = 30_000;

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    /**
     * Woran das letzte Ende lag, soweit sich das nachtraeglich sagen laesst.
     *
     * SAUBER    Der Bot hat sich ordentlich verabschiedet.
     * PROGRAMM  Nur das Programm war weg, Windows lief durch. Also ein
     *           Neustart des Bots, ein Abschuss ueber den Task-Manager oder
     *           ein Absturz der Java-Anwendung.
     * RECHNER   Der ganze Rechner war aus. Stromausfall, ausgeschaltete
     *           Steckdose, harter Reset.
     */
    public enum Ursache { SAUBER, PROGRAMM, RECHNER }

    /** Das Ergebnis der Pruefung beim Start. */
    public record LastRun(boolean crashed, String when, long minutesAgo, Ursache ursache) {}

    /**
     * Seit wann Windows laeuft.
     *
     * Das ist der Schluessel zur Unterscheidung: Liegt das letzte
     * Lebenszeichen NACH dem Windows-Start, dann lief der Rechner die ganze
     * Zeit durch -- es kann also kein Stromausfall gewesen sein, sondern nur
     * das Programm war weg. Liegt es davor, war der Rechner tatsaechlich aus.
     *
     * GetTickCount64 gibt die Millisekunden seit dem Hochfahren zurueck. Der
     * Aufruf kostet nichts und braucht keine Rechte.
     */
    private static LocalDateTime windowsStart() {
        try {
            long seitStart = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64();
            return LocalDateTime.now().minusNanos(seitStart * 1_000_000L);
        } catch (Throwable t) {
            // Kein Windows oder JNA fehlt: dann eben ohne diese Auskunft.
            return null;
        }
    }

    private static volatile LastRun lastRun = new LastRun(false, "", 0, Ursache.SAUBER);
    private static volatile boolean running = false;

    private CrashDetector() {
    }

    /**
     * Prueft beim Start, wie der letzte Lauf geendet hat, und startet danach
     * die eigene Uhr.
     */
    public static LastRun start() {
        lastRun = check();

        if (lastRun.crashed() && lastRun.ursache() == Ursache.RECHNER) {
            Log.warn("Rechner", Sprache.t("The computer was off in between. Last heartbeat: " + lastRun.when() + " (" + humanAge(lastRun.minutesAgo()) + " ago). " + "Windows was restarted after that -- possible reasons: " + "power failure, switched-off outlet or a hard reset.",
                    "Der Rechner war zwischendurch aus. Letztes Lebenszeichen: "
                    + lastRun.when() + " (vor " + humanAge(lastRun.minutesAgo()) + "). "
                    + "Windows wurde danach neu gestartet -- moegliche Gruende: "
                    + "Stromausfall, ausgeschaltete Steckdose oder ein harter Reset."));

        } else if (lastRun.crashed()) {
            // Windows lief durch, nur das Programm war weg. Meist ein Neustart
            // fuer eine neue Fassung. Das ist keine Warnung wert.
            Log.info("Rechner", Sprache.t("The last run ended hard, but Windows kept running -- " + "so only the program was gone (" + humanAge(lastRun.minutesAgo()) + " ago). Usually a restart.",
                    "Der letzte Lauf endete hart, aber Windows lief durch -- "
                    + "es war also nur das Programm weg (vor "
                    + humanAge(lastRun.minutesAgo()) + "). Ueblicherweise ein Neustart."));

        } else {
            Log.info("Rechner", Sprache.t("The last run ended properly.", "Der letzte Lauf wurde ordentlich beendet."));
        }

        running = true;
        Thread beat = new Thread(CrashDetector::heartbeat, "crash-detector");
        beat.setDaemon(true);
        beat.start();

        return lastRun;
    }

    /** Das Ergebnis der letzten Pruefung, fuer Discord und Bedienfeld. */
    public static LastRun lastRun() {
        return lastRun;
    }

    /**
     * Ordentliches Ende: die Datei verschwindet, beim naechsten Start gilt der
     * Lauf als sauber beendet.
     */
    public static void markCleanExit() {
        running = false;
        try {
            Files.deleteIfExists(MARKER);
            Log.debug("Rechner", "Sauberes Ende vermerkt.");
        } catch (Exception e) {
            Log.debug("Rechner", "Marker liess sich nicht loeschen: " + e.getMessage());
        }
    }

    /**
     * Ein Herunterfahren ist angefordert und gewollt.
     *
     * Solange das gilt, wird kein Lebenszeichen mehr geschrieben -- der Lauf
     * ist damit als sauber beendet vermerkt, egal was danach noch passiert.
     */
    private static volatile boolean shutdownPlanned = false;

    /**
     * Meldet, dass der Rechner absichtlich heruntergefahren wird.
     *
     * Warum das noetig ist: Beim ordentlichen Beenden loescht der Bot seine
     * Merkdatei im Abschalt-Haken. Faehrt aber WINDOWS herunter, laesst es den
     * Programmen dafuer nur ein paar Augenblicke -- und ein Konsolenprogramm
     * wird dabei oft einfach abgeschossen, bevor der Haken durch ist. Die
     * Datei blieb dann liegen, und beim naechsten Start meldete der Bot einen
     * Absturz, obwohl alles seine Ordnung hatte.
     *
     * Deshalb wird hier nicht auf das Ende gewartet, sondern sofort vermerkt.
     * Wird das Herunterfahren doch noch abgeblasen, holt
     * cancelExpectedShutdown() das zurueck.
     */
    public static void expectShutdown() {
        shutdownPlanned = true;
        try {
            Files.deleteIfExists(MARKER);
            Log.info("Rechner", Sprache.t("Planned shutdown noted. The next start " + "therefore reports no crash.",
                    "Geplantes Herunterfahren vermerkt. Der naechste Start "
                    + "meldet deshalb keinen Absturz."));
        } catch (Exception e) {
            Log.debug("Rechner", "Marker liess sich nicht loeschen: " + e.getMessage());
        }
    }

    /**
     * Nimmt das zurueck, falls das Herunterfahren doch nicht kommt.
     *
     * Der Bot laeuft dann ja weiter, und ab dem naechsten Lebenszeichen wuerde
     * ein echter Absturz auch wieder erkannt.
     */
    public static void cancelExpectedShutdown() {
        if (!shutdownPlanned) return;
        shutdownPlanned = false;
        Log.info("Rechner", Sprache.t("The shutdown was called off. The bot writes " + "heartbeats again.",
                "Das Herunterfahren wurde abgeblasen. Der Bot schreibt wieder "
                + "Lebenszeichen."));
    }

    /** Liest den Marker des letzten Laufs, falls einer da ist. */
    private static LastRun check() {
        try {
            if (!Files.isRegularFile(MARKER)) {
                return new LastRun(false, "", 0, Ursache.SAUBER);
            }

            String content = Files.readString(MARKER, StandardCharsets.UTF_8).trim();
            LocalDateTime at = LocalDateTime.parse(content, FORMAT);
            long minutes = Duration.between(at, LocalDateTime.now()).toMinutes();

            // War der Rechner aus oder nur das Programm weg?
            //
            // Liegt das letzte Lebenszeichen NACH dem Windows-Start, dann lief
            // der Rechner die ganze Zeit durch. Ein Stromausfall kann es dann
            // nicht gewesen sein -- es war nur das Programm.
            LocalDateTime hochgefahren = windowsStart();
            Ursache ursache = (hochgefahren != null && at.isAfter(hochgefahren))
                    ? Ursache.PROGRAMM
                    : Ursache.RECHNER;

            return new LastRun(true, content, Math.max(0, minutes), ursache);

        } catch (Exception e) {
            // Datei da, aber unlesbar -- dann war es trotzdem kein sauberes Ende.
            return new LastRun(true, "unbekannt", 0, Ursache.RECHNER);
        }
    }

    /** Traegt alle halbe Minute die aktuelle Uhrzeit ein. */
    private static void heartbeat() {
        while (running) {
            // Nach einem angekuendigten Herunterfahren wird nichts mehr
            // geschrieben. Sonst legte der naechste Herzschlag die gerade
            // geloeschte Datei eine halbe Minute spaeter wieder an.
            if (!shutdownPlanned) {
                try {
                    Files.createDirectories(MARKER.getParent());
                    Files.writeString(MARKER, LocalDateTime.now().format(FORMAT),
                            StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // Klappt das Schreiben nicht, ist das kein Grund aufzuhoeren.
                }
            }
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Aus 195 Minuten wird "3 Stunden 15 Minuten" oder "3 hours 15 minutes".
     *
     * Die Sprache muss hier mit hinein: Der Text landet mitten in einem Satz,
     * und ein deutsches "weniger als einer Minute" in einer englischen Meldung
     * liest sich schauderhaft.
     */
    public static String humanAge(long minutes) {
        boolean en = Sprache.istEnglisch();

        if (minutes < 1) {
            return en ? "less than a minute" : "weniger als einer Minute";
        }
        if (minutes < 60) {
            return minutes + (en ? " minutes" : " Minuten");
        }

        long hours = minutes / 60;
        long rest = minutes % 60;

        if (hours < 24) {
            if (en) {
                return hours + (hours == 1 ? " hour " : " hours ") + rest + " minutes";
            }
            return hours + (hours == 1 ? " Stunde " : " Stunden ") + rest + " Minuten";
        }

        long days = hours / 24;
        if (en) {
            return days + (days == 1 ? " day " : " days ") + (hours % 24) + " hours";
        }
        return days + (days == 1 ? " Tag " : " Tagen ") + (hours % 24) + " Stunden";
    }
}
