package de.hustlebot.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gibt Speicher frei, den gerade niemand braucht (Autor 20.11.: der Bot laeuft kuenftig in einer VM neben Minecraft --
 * gleiches Tempo, weniger Verbrauch).
 *
 * Ein ruhiger Hintergrund-Faden sieht einmal je Minute nach: Wurde die Texterkennung seit
 * {@link PaddleLeser#LEERLAUF_MS} nicht benutzt, werden ihre Modelle geschlossen (gemessen: bis 357 MB) und die offene
 * Windows-Texterkennung (eigenes PowerShell) beendet. Beim naechsten Lesen laedt beides von selbst neu.
 */
public final class Leerlauf {

    private Leerlauf() { }

    private static ScheduledExecutorService faden;

    /** Startet den Faden einmal (mehrfach rufen schadet nicht). */
    static synchronized void beobachten() {
        if (faden != null) return;
        faden = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Leerlauf-Aufraeumer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        faden.scheduleWithFixedDelay(Leerlauf::pruefen, 60, 60, TimeUnit.SECONDS);
    }

    static void pruefen() {
        long jetzt = System.currentTimeMillis();
        try {
            PaddleLeser.freigebenWennUnbenutzt(jetzt, PaddleLeser.LEERLAUF_MS);
        } catch (Throwable t) {
            Log.debug("Leerlauf", "Texterkennung nicht freigegeben: " + t);
        }
        try {
            DauerLeser.beendenWennUnbenutzt(jetzt, PaddleLeser.LEERLAUF_MS);
        } catch (Throwable t) {
            Log.debug("Leerlauf", "Offene Windows-Texterkennung nicht beendet: " + t);
        }
    }
}
