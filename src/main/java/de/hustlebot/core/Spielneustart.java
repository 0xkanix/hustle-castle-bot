package de.hustlebot.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Hustle Castle alle 2 Stunden neu starten (Autor 16.10. fuer die Freunde, "das soll auch ausschaltbar sein").
 *
 * Im Hauptbot gemessen (27.09., je 5 Kathedralen-Runs): Spiel seit 16 h offen 89,8 s je Run, frisch gestartet
 * 68,4 s -- die Ersparnis steckt in den Ladezeiten, das Spiel wird mit der Zeit langsamer.
 *
 * Anders als der Hauptbot startet sich der Freunde-Bot dabei nicht selbst neu (dort laeuft das ueber die geplante
 * Aufgabe): der Motor beendet zwischen zwei Kathedralen-Runs das Spiel, oeffnet es ueber den Launcher wieder und
 * spielt weiter (BotEngine.spielNeustartZwischenRuns). Hier stehen nur Schalter, Alter und das Beenden.
 */
public final class Spielneustart {

    private Spielneustart() {}

    /** Nach so vielen Minuten Spiel-Laufzeit wird neu gestartet. */
    public static final long MINUTEN = 120;

    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("spielneustart.txt"), "Spiel-Neustart", true);

    /** Einmal beim naechsten Run-Ende neu starten (!settings spielneustart jetzt) -- zum Testen, auch wenn aus. */
    private static volatile boolean angefordert;

    public static boolean istAn() {
        return SCHALTER.lesen();
    }

    public static void setzen(boolean an) {
        SCHALTER.setzen(an, Sprache.t("Restart Hustle Castle every 2 hours: on/off (Settings -> Cathedral)",
                "Hustle Castle alle 2 Stunden neu starten: an/aus (Einstellungen -> Kathedrale)"));
    }

    public static void jetztAnfordern() {
        angefordert = true;
    }

    /** Das laufende Spiel (HC.exe), falls es eins gibt. */
    private static Optional<ProcessHandle> spiel() {
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> {
                    String k = c.toLowerCase();
                    return k.endsWith("\\hc.exe") || k.endsWith("/hc.exe");
                }).orElse(false))
                .findFirst();
    }

    /** Wie lange laeuft das Spiel schon? Leer, wenn es nicht laeuft oder die Zeit unbekannt ist. */
    public static Optional<Duration> spielAlter() {
        return spiel().flatMap(p -> p.info().startInstant()).map(s -> Duration.between(s, Instant.now()));
    }

    /** Ist es wieder so weit? */
    public static boolean faellig() {
        if (angefordert) return true;
        if (!istAn()) return false;
        return spielAlter().map(d -> d.toMinutes() >= MINUTEN).orElse(false);
    }

    /**
     * Das Spiel beenden. "Fenster schliessen" genuegt nicht -- das Spiel fragt dann im Bild nach. Der Spielstand
     * liegt auf dem Server, beim harten Beenden geht nichts verloren (Hauptbot seit 27.09.).
     */
    public static boolean spielBeenden() {
        angefordert = false;
        Optional<ProcessHandle> p = spiel();
        if (p.isEmpty()) return true;
        p.get().destroyForcibly();
        try {
            p.get().onExit().get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.warn("Spiel-Neustart", Sprache.t("The game did not close within 15 s: " + e.getMessage(),
                    "Das Spiel hat sich nicht in 15 s beendet: " + e.getMessage()));
            return false;
        }
        Log.info("Spiel-Neustart", Sprache.t("Hustle Castle closed.", "Hustle Castle beendet."));
        return true;
    }

    /** Eine Zeile fuer die Einstellungen. */
    public static String beschreibung() {
        String alter = spielAlter().map(d -> d.toHours() + " h " + (d.toMinutes() % 60) + " min").orElse("?");
        return Sprache.t((istAn() ? "**On**" : "**Off**") + " -- every 2 hours, between two cathedral runs (never right "
                        + "before a clan war). The game has been running for " + alter + ".",
                (istAn() ? "**An**" : "**Aus**") + " -- alle 2 Stunden, zwischen zwei Kathedralen-Runs (nie kurz vor "
                        + "einem Clan-Krieg). Das Spiel laeuft seit " + alter + ".");
    }
}
