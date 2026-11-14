package de.hustlebot.bot;

import de.hustlebot.core.Ladenplan;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.List;

/**
 * Food chests im Clan-Laden kaufen, als eigener Botengang (Autor, 27.09.):
 * um 02:00 und 12:00 nach dem Titanen-Turm. Der eigentliche Weg steht in
 * ClanLaden; hier wird er nur gestartet und abgeschlossen.
 */
public final class ClanLadenMode implements GameMode {

    /** Wann zuletzt nach Werbe-/Belohnungsfenstern gesehen wurde (hoechstens jede Sekunde). */
    private long stoerGeprueftUm = 0;

    private final ClanLaden laden = new ClanLaden();
    private boolean gestartet;

    @Override public String id() { return "clanladen"; }

    @Override public String displayName() { return Sprache.t("Clan store", "Clan-Laden"); }

    @Override public List<String> requiredTemplates() { return ClanLaden.vorlagen(); }

    @Override
    public void reset() {
        gestartet = false;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        // AFK-Rauswurf ("are you still here?"): Update, das Spiel laedt neu.
        if (Stoerfenster.afkUpdate(screen, ctx, "Clan-Laden")) return Outcome.ACTED;
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, "Clan-Laden")) return Outcome.ACTED;
        // Werbefenster "Running out of apples?", Tagesbelohnung (Autor, 29.09.: der Bot
        // drueckte 8x "Weltkarte", waehrend das Apfel-Fenster offen war -- der Map-Knopf
        // "passt" auch hinter dem Fenster). Hoechstens jede Sekunde (~0,2 s Suche).
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Clan-Laden")) return Outcome.ACTED;
        }
        if (!gestartet) {
            gestartet = true;
            laden.starten();
        }
        if (laden.schritt(screen, ctx)) return Outcome.ACTED;
        if (!laden.sauberBeendet()) return Outcome.FATAL;
        Ladenplan.erledigt();
        runs.completeRun();
        return Outcome.FINISHED;
    }
}
