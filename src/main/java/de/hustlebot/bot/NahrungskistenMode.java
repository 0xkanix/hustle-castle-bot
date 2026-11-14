package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.List;

/**
 * Nahrungskisten aus der Tasche oeffnen, bis das Lager fast voll ist (Autor, 17.10., Portal-Aufstieg: "Falls die
 * Nahrung nicht reicht, soll der Bot aus dem Portal rausgehen, in die Burg, in die Tasche, Nahrungskisten oeffnen,
 * bis mein Lager voll ist. Aktuell 2 Millionen."). Nur die Tasche, kein Clan-Laden, keine Werbevideos, nie Diamanten.
 * Start: !nahrungskisten (= !start nahrungskisten 1). Steht der Bot nicht in der Burg, geht er erst ueber das rote X
 * zurueck.
 */
public final class NahrungskistenMode implements GameMode {

    private static final String WO = "Nahrungskisten";
    /** So oft hoechstens das rote X, um in die Burg zu kommen. */
    private static final int MAX_X = 6;

    private final Nahrungskisten kisten = new Nahrungskisten();
    /**
     * Autor 23.10. (Portal): "Jedes Mal eine Million Nahrungskisten oeffnen; ist das Lager kleiner (850 000), so nah wie
     * moeglich daran." 0 = wie bisher bis fast ans Lager.
     */
    private final long zielMenge;
    private long ziel;
    private boolean gestartet;
    /** Nahrung vor und nach dem Oeffnen (-1 = nicht gelesen) -- Portal 23.10.: daran sieht man, ob die Tasche leer war. */
    private long vorher = -1, nachher = -1;

    /** Wie viel Nahrung die Kisten gebracht haben (-1 = unbekannt). */
    public long gebracht() {
        return vorher >= 0 && nachher >= 0 ? nachher - vorher : -1;
    }

    /** Nahrung nach dem Oeffnen (-1 = nicht gelesen). */
    public long nachher() {
        return nachher;
    }

    public NahrungskistenMode() {
        this(0);
    }

    public NahrungskistenMode(long zielMenge) {
        this.zielMenge = Math.max(0, zielMenge);
    }
    private int xKlicks, versuche;
    private long ohneBurgSeit;
    private long stoerGeprueftUm;

    @Override public String id() { return "nahrungskisten"; }
    @Override public String displayName() { return Sprache.t("Food chests", "Nahrungskisten"); }
    @Override public List<String> requiredTemplates() { return List.of(); }

    @Override
    public void reset() {
        gestartet = false;
        xKlicks = versuche = 0; ohneBurgSeit = 0;
        vorher = nachher = -1;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        if (Stoerfenster.afkUpdate(screen, ctx, WO)) return Outcome.ACTED;
        if (!gestartet) {
            if (!kisten.einsatzbereit(ctx)) {
                Log.warn(WO, "Fuer die Nahrungskisten fehlen Vorlagen -- nichts geoeffnet.");
                runs.completeRun();
                return Outcome.FINISHED;
            }
            boolean burg = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
            if (!burg) {
                // Erst zurueck in die Burg: Fenster (Portal, Kampf-Fenster) ueber das rote X schliessen.
                // Live 17.10., 17:49: aus dem Portal fuehrte das zweite X auf die Weltkarte -- dort gibt es kein X, nur
                // "Home" unten links. Der Bot wartete stumm.
                if (xKlicks < MAX_X && (ctx.clickIfVisibleIfKnown(screen, "close_x", WO + ": Fenster schliessen (zur Burg)")
                        || ctx.clickIfVisibleIfKnown(screen, "ev_angebot_x", WO + ": Fenster schliessen (zur Burg)")
                        || Weltkarte.homeUntenLinksKlicken(screen, ctx, WO + ": Weltkarte -- Home (zur Burg)")
                        || ctx.clickIfVisibleIfKnown(screen, "home_button", WO + ": Home (zur Burg)")
                        || ctx.clickIfVisibleIfKnown(screen, "ts_burg_knopf", WO + ": Weltkarte -- Burg-Knopf"))) {
                    xKlicks++;
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
                // Live 17.10., 17:57: das Weltkarten-"Home" passte nur mit 0,73 (Hintergrund anders) -- hier reicht 0,65,
                // es steht allein unten links.
                java.util.Optional<de.hustlebot.core.Matcher.Hit> heim = ctx.matcher().has("worldmap_home")
                        ? ctx.matcher().find(screen, "worldmap_home", 0.65) : java.util.Optional.empty();
                if (xKlicks < MAX_X && heim.isPresent() && heim.get().centerX() < ctx.width() / 4
                        && heim.get().centerY() > ctx.height() * 3 / 4) {
                    Log.action("Klick", WO + ": Weltkarte -- Home (zur Burg)");
                    ctx.input().tippeSofort(heim.get().centerX(), heim.get().centerY());
                    xKlicks++;
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
                if (ohneBurgSeit == 0) ohneBurgSeit = System.currentTimeMillis();
                if (System.currentTimeMillis() - ohneBurgSeit > 60_000) {
                    Log.warn(WO, "Seit einer Minute weder Burg noch rotes X noch Home zu sehen -- nichts geoeffnet.");
                    runs.completeRun();
                    return Outcome.FINISHED;
                }
                if (xKlicks >= MAX_X) {
                    Log.warn(WO, "Die Burg ist nach " + MAX_X + " Mal rotem X nicht zu sehen -- nichts geoeffnet.");
                    runs.completeRun();
                    return Outcome.FINISHED;
                }
                ctx.sleep(300);
                return Outcome.WAITING;
            }
            gestartet = true;
            long lager = de.hustlebot.core.Bezahlung.lager();
            vorher = Nahrungskisten.nahrungLesen(ctx).orElse(-1);
            if (zielMenge > 0) {
                long da = Math.max(0, vorher);
                ziel = Math.min(lager, da + zielMenge);
                Log.info(WO, "Nahrung " + da + " -- Kisten fuer bis zu " + zielMenge + " oeffnen (Ziel " + ziel + ", Lager " + lager + ").");
                kisten.startenAuffuellen(ziel, ziel - 135_000, false, false);
            } else {
                ziel = lager;
                // Bis nah ans Lager auffuellen; ist schon 3/4 davon da, wird nichts geoeffnet.
                kisten.startenAuffuellen(lager, lager * 3 / 4, false, false);
            }
            // Autor 17.10.: "ca. 2000 Kisten mit jeweils 135 000 Nahrung -- kannst du diese Kisten benutzen?"
            // Freunde-Bot 22.10.: bevorzugeInhalt(135_000, 15) fehlt hier noch (kommt mit den Nahrungskisten des Hauptbots)   // Autor 17.10.: jedes Mal 15 (= 2 025 000)
            // Autor 17.10.: "Die Kistenpositionen haben sich nicht geaendert -- er muss nicht jede Kiste neu anschauen."
            // Freunde-Bot 22.10.: gedaechtnisNutzen(true) fehlt hier noch (kommt mit den Nahrungskisten des Hauptbots)
            Log.info(WO, "Nahrungskisten aus der Tasche oeffnen, bis das Lager (" + lager + ") fast voll ist.");
            runs.progress("Nahrungskisten: Tasche");
        }
        if (kisten.aktiv()) {
            runs.progress("Nahrungskisten oeffnen");
            if (kisten.schritt(screen, ctx)) return Outcome.ACTED;
            if (kisten.aktiv()) return Outcome.WAITING;
        }
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
        }
        // Live-Test 17.10.: "Running out of ..." lag im Weg, die Tasche ging nicht auf (0 Kisten) -- das Fenster wurde
        // erst danach geschlossen. Darum: noch zu wenig Nahrung -> hoechstens zweimal von vorn.
        long lager = de.hustlebot.core.Bezahlung.lager();
        long jetzt = Nahrungskisten.nahrungLesen(ctx).orElse(-1);
        long mindestens = zielMenge > 0 ? ziel - 135_000 : lager * 3 / 4;
        if (jetzt >= 0 && jetzt < mindestens && versuche < 2) {
            versuche++;
            Log.info(WO, "Nahrung erst " + jetzt + " -- noch ein Versuch (" + versuche + " von 2).");
            kisten.startenAuffuellen(zielMenge > 0 ? ziel : lager, mindestens, false, false);
            // Freunde-Bot 22.10.: bevorzugeInhalt(135_000, 15) fehlt hier noch (kommt mit den Nahrungskisten des Hauptbots)   // Autor 17.10.: jedes Mal 15 (= 2 025 000)
            // Freunde-Bot 22.10.: gedaechtnisNutzen(true) fehlt hier noch (kommt mit den Nahrungskisten des Hauptbots)
            return Outcome.ACTED;
        }
        nachher = jetzt;
        Log.info(WO, "Fertig -- Nahrung jetzt: " + jetzt + ".");
        runs.completeRun();
        return Outcome.FINISHED;
    }
}
