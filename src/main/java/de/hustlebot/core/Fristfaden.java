package de.hustlebot.core;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ein Aufruf mit Frist auf einem eigenen Faden (VM 21.11.).
 *
 * Anlass: In der HustleVM stand die Hauptschleife ab 17:38 fuer immer still, ohne eine einzige Protokollzeile. VirtualBox'
 * eigene Bildschirmaufnahme hatte auf dem PC den Faden der virtuellen Grafikkarte abstuerzen lassen (VBox.log:
 * Zugriffsverletzung in "VMSVGA FIFO"); ein Grafik-Aufruf, der auf die Grafikkarte wartet (DXGI-Foto: Kopie + Map), kam
 * danach nie mehr zurueck. Ein Aufruf in fremden Code (Treiber, DLL) laesst sich nicht abbrechen -- aber der Aufrufer muss
 * nicht mit ihm stehen bleiben.
 *
 * Darum: Der Aufruf laeuft auf einem eigenen (Daemon-)Faden, der Aufrufer wartet hoechstens die Frist. Haengt ein Aufruf
 * laenger als die Frist, kommen alle weiteren sofort mit null zurueck, ohne den haengenden Code noch einmal anzufassen
 * (er haelt dort womoeglich eine Sperre). Kommt der Aufruf doch noch zurueck, ist der Faden wieder frei und es geht normal
 * weiter. Alle Aufrufe laufen nacheinander auf demselben Faden -- wie vorher unter einer gemeinsamen Sperre.
 */
public final class Fristfaden {

    private static final long FREI = Long.MIN_VALUE;

    private final long fristMs;
    private final ExecutorService faden;
    /** System.nanoTime() beim Beginn des laufenden Aufrufs, FREI wenn keiner laeuft. */
    private volatile long laeuftSeit = FREI;
    private final AtomicBoolean haengerGemeldet = new AtomicBoolean();

    public Fristfaden(String name, long fristMs) {
        this.fristMs = fristMs;
        this.faden = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);   // ein haengender Aufruf darf das Beenden des Programms nicht aufhalten
            return t;
        });
    }

    public long fristMs() {
        return fristMs;
    }

    /** laeuftSeit des Aufrufs, auf den schon ein Aufrufer bis zum Ende seiner Frist gewartet hat. */
    private volatile long abgelaufen = FREI;

    /** Haengt gerade ein Aufruf -- laeuft er laenger als die Frist, oder hat schon jemand vergeblich auf ihn gewartet? */
    public boolean haengt() {
        long seit = laeuftSeit;
        return seit != FREI && (seit == abgelaufen || System.nanoTime() - seit > fristMs * 1_000_000L);
    }

    /**
     * Fuehrt den Aufruf auf dem eigenen Faden aus und wartet hoechstens die Frist.
     *
     * @param aufruf darf nicht null liefern (null heisst hier "kein Ergebnis")
     * @return das Ergebnis, oder null: Frist abgelaufen, ein frueherer Aufruf haengt noch, oder der Aufrufer wurde
     *         unterbrochen (dann bleibt die Unterbrechung gesetzt)
     */
    public <T> T rufe(Callable<T> aufruf) {
        if (haengt()) return null;
        Future<T> f = faden.submit(() -> {
            laeuftSeit = System.nanoTime();
            try {
                return aufruf.call();
            } finally {
                laeuftSeit = FREI;
            }
        });
        try {
            return f.get(fristMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);   // noch nicht begonnen (wartete hinter einem haengenden)? Dann gar nicht mehr ausfuehren.
            long seit = laeuftSeit;
            if (seit != FREI) abgelaufen = seit;   // der gerade laufende Aufruf gilt ab jetzt als haengend
            return null;
        } catch (InterruptedException e) {
            f.cancel(false);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable grund = e.getCause();
            if (grund instanceof RuntimeException r) throw r;
            if (grund instanceof Error err) throw err;
            throw new IllegalStateException(grund);
        }
    }

    /** True genau einmal je Haenger (fuer eine einzige Protokollzeile), und nur solange er wirklich haengt. */
    public boolean haengerMelden() {
        return haengt() && haengerGemeldet.compareAndSet(false, true);
    }

    /** True genau einmal, wenn nach einem gemeldeten Haenger wieder ein Aufruf durchkam. */
    public boolean freiMelden() {
        return haengerGemeldet.compareAndSet(true, false);
    }
}
