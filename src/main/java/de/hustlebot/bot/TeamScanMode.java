package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.List;

/**
 * Das eigene Team ansehen, als eigener Botengang (Autor 11.10., Auftrag 6): beim allerersten
 * Start und danach alle 3 Echtzeit-Tage (MeinTeam, arena-ich.txt), eingeschoben zwischen zwei
 * Runs wie Freunden senden; von Hand mit "!team scan" oder "!start teamscan". Der eigentliche Weg
 * steht in TeamScan; hier wird er nur gestartet und abgeschlossen.
 *
 * Gebaut wie FreundeSendenMode: ein Abbruch mit dem Bot wieder in der Burg ist kein Fehler --
 * was gelesen wurde, ist gespeichert, der Rest kommt in 3 Tagen noch einmal dran. Nur wenn der
 * Bot NICHT in die Burg zurueckfand, ist es ein Haenger (FATAL).
 */
public final class TeamScanMode implements GameMode {

    private long stoerGeprueftUm = 0;
    private final TeamScan gang = new TeamScan();
    private boolean gestartet;

    @Override public String id() { return "teamscan"; }

    @Override public String displayName() { return Sprache.t("Own team", "Eigenes Team"); }

    @Override public List<String> requiredTemplates() { return TeamScan.vorlagen(); }

    /** Alle Vorlagen, die der Scan kennt -- fuer das Aufnahme-Werkzeug und die Fehlt-Liste. */
    public static List<String> optionalTemplates() { return TeamScan.alleVorlagen(); }

    @Override
    public void reset() {
        gestartet = false;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        if (Stoerfenster.afkUpdate(screen, ctx, TeamScan.WER)) return Outcome.ACTED;
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, TeamScan.WER)) return Outcome.ACTED;
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, TeamScan.WER)) return Outcome.ACTED;
        }
        if (!gestartet) {
            gestartet = true;
            gang.starten();
        }
        if (gang.schritt(screen, ctx)) {
            runs.progress("Team-Scan");   // kein Haenger-Alarm, solange der Scan Schritte macht
            return Outcome.ACTED;
        }
        if (!gang.zurueckInBurg()) return Outcome.FATAL;
        if (gang.abgebrochen()) {
            Log.warn(TeamScan.WER, "Team-Scan abgebrochen (" + String.join("; ", gang.fehlgeschlagen()) + ") -- zurueck in der Burg, "
                    + "es geht normal weiter. In 3 Tagen wird es noch einmal versucht.");
        }
        runs.completeRun();
        return Outcome.FINISHED;
    }

    @Override
    public String endeHinweis() {
        if (gang.abgebrochen()) {
            return Sprache.t("Could not look at your team completely (", "Dein Team konnte nicht ganz angesehen werden (")
                    + String.join("; ", gang.fehlgeschlagen()) + Sprache.t("). Try again in 3 days.", "). In 3 Tagen noch einmal.");
        }
        return Sprache.t("Your team has been looked at:\n", "Dein Team ist angesehen:\n") + MeinTeam.karte();
    }
}
