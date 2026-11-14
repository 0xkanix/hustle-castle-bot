package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zaehler fuer !stats, die einen Neustart des Bots ueberleben (Autor, 29.09.:
 * "einen Event-Zaehler, wie viele Stages schon abgeschlossen wurden").
 *
 * Der Bot startet sich mit dem Spiel alle paar Stunden selbst neu -- ohne Datei
 * fingen die Zahlen dann jedes Mal bei null an. Auf null gehen sie nur bei
 * einem frischen !start von Autor (nicht beim Weitermachen nach einem Neustart).
 */
public final class Laufzaehler {

    private Laufzaehler() {}

    private static final Path DATEI = Path.of("lauf-zaehler.txt");
    private static volatile int eventStages = laden();

    /** Eine Event-Stage ist geschafft (Hammer-Event, allein oder im Kreislauf). */
    public static synchronized void eventStage() {
        eventStages++;
        speichern();
    }

    public static int eventStages() {
        return eventStages;
    }

    /** Frischer !start: wieder bei null anfangen. */
    public static synchronized void zuruecksetzen() {
        eventStages = 0;
        speichern();
    }

    private static int laden() {
        try {
            if (!Files.exists(DATEI)) return 0;
            for (String z : Dateien.zeilen(DATEI)) {
                if (z.startsWith("event-stages=")) return Integer.parseInt(z.substring(13).trim());
            }
        } catch (Exception e) {
            Log.debug("Zaehler", "lauf-zaehler.txt nicht lesbar: " + e.getMessage());
        }
        return 0;
    }

    private static void speichern() {
        try {
            Files.writeString(DATEI, "# Zaehler fuer !stats seit dem letzten !start (ueberlebt Neustarts)\n"
                    + "event-stages=" + eventStages + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Zaehler", "lauf-zaehler.txt nicht geschrieben: " + e.getMessage());
        }
    }
}
