package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.List;

/**
 * Kleiner gemeinsamer Heimweg (z. B. fuer die Konto-Pruefung):
 * Burg erkennen und -- falls nicht in der Burg -- einen Schritt Richtung Burg tun. Es wird NUR geschlossen
 * (rotes X) oder "Home" gedrueckt, nie ein anderer Knopf.
 */
final class Burgweg {

    private Burgweg() { }

    /** Rote X, die der Bot kennt (wie Stoerfenster/FreundeSenden). Angebote ("ev_angebot_x") nur ueber ihr X. */
    static final List<String> X_VORLAGEN = List.of("close_x", "dg_close_x", "ev_angebot_x", "ar_close_x", "cw_close_x",
            "dc_close_x");

    /** Rotes X oben rechts auf der Spielflaeche 1159 x 770. */
    static final int X_FEST_X = 1120, X_FEST_Y = 32;
    /** Lang druecken: kurze Klicks nimmt das Spiel oft nicht an. */
    static final int DRUECK_MS = 110;

    /** Steht der Bot in der Burg? (Karten-Knopf unten links zu sehen.) */
    static boolean inDerBurg(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
    }

    /**
     * Einen Schritt Richtung Burg: rotes X (Vorlage), Weltkarte -> Home, Home-Knopf. Mit festesX darf -- falls keine
     * Vorlage passt -- das rote X an seiner festen Stelle (1120,32) getippt werden; nur dort benutzen, wo der Bildschirm
     * bekannt ist (Laden, Edelstein-Menue). false = nichts getan.
     */
    static boolean schritt(Mat screen, BotContext ctx, String wo, boolean festesX) {
        for (String x : X_VORLAGEN) {
            if (ctx.clickIfVisibleIfKnown(screen, x, wo + Sprache.t(": close the window with the red X (to the castle)", ": Fenster ueber das rote X schliessen (zur Burg)"))) {
                ctx.sleepUntilChanged(1500);
                return true;
            }
        }
        // Gebietskarte (Schwert-Stuetzpunkte): der runde Knopf unten links fuehrt weiter Richtung Weltkarte/Burg.
        if (ctx.gebietskarte(screen)
                && (ctx.clickIfVisibleIfKnown(screen, "map_button", wo + Sprache.t(": area map -- Map (to the castle)", ": Gebietskarte -- Map (zur Burg)"))
                || ctx.clickIfVisibleIfKnown(screen, "map_button_alt", wo + Sprache.t(": area map -- Map (to the castle)", ": Gebietskarte -- Map (zur Burg)")))) {
            ctx.sleepUntilChanged(1500);
            return true;
        }
        if (Weltkarte.homeUntenLinksKlicken(screen, ctx, wo + ": Weltkarte -- Home (zur Burg)")) {
            ctx.sleepUntilChanged(1500);
            return true;
        }
        if (ctx.clickIfVisibleIfKnown(screen, "home_button", wo + Sprache.t(": Home (to the castle)", ": Home (zur Burg)"))) {
            ctx.sleepUntilChanged(1500);
            return true;
        }
        if (Weltkarte.erkannt(screen, ctx)) {
            Weltkarte.heim(screen, ctx, wo);
            return true;
        }
        if (festesX) {
            Log.action("Klick", wo + Sprache.t(": red X top right (fixed spot ", ": rotes X oben rechts (feste Stelle ") + X_FEST_X + "," + X_FEST_Y + ")");
            ctx.input().tippWeich(X_FEST_X, X_FEST_Y, DRUECK_MS);
            ctx.sleepUntilChanged(1500);
            return true;
        }
        return false;
    }
}
