package de.hustlebot.bot;

import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.List;

/**
 * Probe fuer das Helden-Set (Autor 17.10.: "Kannst du jetzt schon testen, ob der Bot es hinbekommt, mein Preset vom
 * Held zu aendern?"): wechselt auf das Set VOR dem Krieg und gleich wieder auf das Set NACH dem Krieg -- ohne Krieg.
 * Start: "!settings heldenset test" (oder "!start heldenset").
 */
public final class HeldenSetMode implements GameMode {

    private HeldenSetWechsel erst, dann;
    private long stoerGeprueftUm = 0;

    @Override public String id() { return "heldenset"; }

    @Override public String displayName() { return Sprache.t("Hero set (test)", "Helden-Set (Probe)"); }

    @Override public List<String> requiredTemplates() { return List.of(); }

    @Override
    public void reset() {
        erst = new HeldenSetWechsel(HeldenSet.vorher(), HeldenSet.vorherPlatz());
        dann = new HeldenSetWechsel(HeldenSet.nachher(), HeldenSet.nachherPlatz());
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        if (Stoerfenster.afkUpdate(screen, ctx, "Helden-Set")) return Outcome.ACTED;
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Helden-Set")) return Outcome.ACTED;
        }
        if (!erst.fertig()) {
            runs.progress("Helden-Set");
            return erst.schritt(screen, ctx);
        }
        if (!dann.fertig()) {
            runs.progress("Helden-Set");
            return dann.schritt(screen, ctx);
        }
        runs.completeRun();
        return Outcome.FINISHED;
    }

    @Override
    public String endeHinweis() {
        return Sprache.t("Hero set test: ", "Helden-Set-Probe: ")
                + zeile(HeldenSet.vorher(), erst) + " · " + zeile(HeldenSet.nachher(), dann);
    }

    private static String zeile(String name, HeldenSetWechsel w) {
        if (w == null) return name + " ?";
        return name + (w.erfolg() ? " ✅" : " ❌ (" + w.grund() + ")");
    }
}
