package de.hustlebot.bot;

import de.hustlebot.core.Freundeplan;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.time.LocalDate;
import java.util.List;

/**
 * Freunden Ressourcen senden, als eigener Botengang (Autor, 05.10.): einmal je
 * Spieltag gleich nach dem Titanen-Turm um 02:00. Der eigentliche Weg steht in
 * FreundeSenden; hier wird er nur gestartet und abgeschlossen.
 *
 * Die Id heisst bewusst nicht "freunde": "friend"/"freunde" gehoert im Verlies
 * zum Mitspieler.
 */
public final class FreundeSendenMode implements GameMode {

    /** Wann zuletzt nach Werbe-/Belohnungsfenstern gesehen wurde (hoechstens jede Sekunde). */
    private long stoerGeprueftUm = 0;

    private final FreundeSenden gang = new FreundeSenden();
    private boolean gestartet;
    /** Bericht #FHF4 (22.10.): zuletzt dem Haenger-Waechter gemeldete Zahl Geschenke. */
    private int gemeldet;
    /** Berichte #ZQFM/#8GD9 (24.10.): zuletzt gemeldeter Klick-Zeitpunkt des Gangs. */
    private long geklicktGemeldet;
    /** Fuer diesen Spieltag wird gesendet (beim Start festgehalten, falls der Gang ueber 02:00 laeuft). */
    private java.time.LocalDateTime tag;

    @Override public String id() { return "freundesenden"; }

    @Override public String displayName() { return Sprache.t("Gifts to friends", "Freunden senden"); }

    @Override public List<String> requiredTemplates() { return FreundeSenden.vorlagen(); }

    @Override
    public void reset() {
        gestartet = false;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        // AFK-Rauswurf ("are you still here?"): Update, das Spiel laedt neu.
        if (Stoerfenster.afkUpdate(screen, ctx, "Freunden senden")) return Outcome.ACTED;
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, "Freunden senden")) return Outcome.ACTED;
        // Werbe-/Belohnungsfenster wie ueberall wegdruecken -- hoechstens jede Sekunde (~0,2 s Suche).
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Freunden senden")) return Outcome.ACTED;
        }
        if (!gestartet) {
            gestartet = true;
            tag = Freundeplan.fenster();
            gang.starten();
            gemeldet = 0;
            geklicktGemeldet = gang.zuletztGeklickt();
            runs.progress(Sprache.t("Gifts to friends started", "Freunden senden begonnen"));
        }
        if (gang.schritt(screen, ctx)) {
            // Bericht #FHF4 (22.10.): ohne Meldung kam nach 20 s "No progress", obwohl 18 Geschenke rausgingen.
            if (gang.gesendet() != gemeldet) {
                gemeldet = gang.gesendet();
                runs.progress(Sprache.t("Sent " + gemeldet, gemeldet + " gesendet"));
            } else if (gang.zuletztGeklickt() != geklicktGemeldet) {
                // Berichte #ZQFM/#8GD9 (24.10.): der Weg hin (Fenster schliessen, Pokal, Reiter)
                // dauerte 17 s ohne Meldung -- "No progress" nach 20 s, obwohl alles lief.
                geklicktGemeldet = gang.zuletztGeklickt();
                runs.progress(Sprache.t("On the way to the friends", "Auf dem Weg zu den Freunden"));
            }
            return Outcome.ACTED;
        }
        // Gegenlese 05.10.: Nur wenn der Bot NICHT zurueck in die Burg kam, ist es ein Haenger
        // (FATAL -- im Lernmodus haelt der Bot dann an). Ein Abbruch mit dem Bot wieder in der
        // Burg ist keiner: der Tag wird nicht vermerkt, Freundeplan versucht es in 20 Minuten
        // noch einmal, und die Warteschlange (Kathedrale, Event ...) laeuft weiter.
        if (!gang.zurueckInBurg()) return Outcome.FATAL;
        if (!gang.sauberBeendet()) {
            Log.warn("Freunden senden", de.hustlebot.core.Sprache.t("Not done (" + gang.ergebnis() + ") -- back in the castle, "
                    + "carrying on as normal. Another try in 20 minutes.",
                    "Nicht geschafft (" + gang.ergebnis() + ") -- zurueck in der Burg, "
                    + "es geht normal weiter. In 20 Minuten wird noch einmal versucht."));
        } else if (gang.tagFertig()) {
            // Oben 0 oder kein Send-Knopf mehr in der ganzen Liste: der Spieltag ist erledigt
            // (auch wenn schon beim Oeffnen 0 dastand).
            Freundeplan.erledigt(tag, gang.gesendet());
        }
        runs.completeRun();
        return Outcome.FINISHED;
    }
}
