package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Burgen angreifen (Autor 25.10., Zweitkonto "Zweitkonto A", Thronsaal 5): auf der Weltkarte steht bei manchen Inseln
 * "Enemies: N" mit einem kleinen Burg-Symbol (Orc Woods, Wretched Land, Scorching Ground, Age-Old Woods). Das bringt
 * Haemmer und Lupen fuers Hammer-Event, wo es noch keine Kathedrale gibt.
 *
 * Ablauf je Insel: Symbol antippen -> auf der Inselkarte die Burg (rote Burg mit Fahne, darunter Stufe und Name) ->
 * im Fenster die Kraft lesen -> mehr als 200 000 ueber deiner? bis zu zweimal "Change opponent", danach trotzdem ->
 * "Scout" -> "Quick Attack!" -> bei zu wenig Einheiten "Yes" -> nach dem Kampf "Home" -> naechste Burg; keine mehr:
 * "Map" -> naechste Insel. Ohne Burg-Symbol auf der Karte ist der Durchgang fertig. Keine Invasions (Autor).
 * Nahrung ist Autor egal (Wechsel 2 500, Angriff 5 000).
 */
public final class BurgenMode implements GameMode {

    static final String T_SYMBOL = "bu_feind_symbol";
    static final String T_BURG = "bu_ziel_burg";
    static final String T_WECHSELN = "bu_wechseln";
    static final String T_ANSEHEN = "bu_ansehen";
    static final String T_SCHNELL = "bu_schnell";
    static final String T_JA = "bu_ja";
    static final String T_HEIM = "bu_heim";
    static final String T_KARTE = "bu_karte";
    private static final String WER = "Burgen";

    /** Autor 25.10.: "Wenn der Spieler mehr Power hat als ich, sagen wir 200k mehr, soll er auf Change Opponent druecken." */
    static final long ZU_STARK_UEBER = 200_000;
    /** "Wenn der Gegner immer noch zu stark ist, soll er ihn trotzdem angreifen. Oder zwei." */
    static final int WECHSEL_MAX = 2;

    enum Schritt { ZUR_KARTE, INSELN, INSEL_OEFFNEN, BURG_SUCHEN, FENSTER, ANGRIFF, KAMPF, HEIM }

    private Schritt schritt;
    private long seit;
    private int wechsel, angriffe, unbekannt;
    /** Code-Review 26.10.: Angriffe auf DIESER Insel -- eine verlorene Burg bleibt stehen und wurde sonst endlos angegriffen. */
    private int angriffeHier;
    static final int ANGRIFFE_JE_INSEL_MAX = 6;
    /** Code-Review 26.10.: nach einem Abbruch erst noch heim in die Burg (einmal), sonst stand der naechste Modus auf der Insel. */
    private boolean aufgegeben;
    /**
     * Autor 25.10.: "Burgen auch in den Wartezeiten zwischen den Arena-Stages angreifen und rechtzeitig zur naechsten
     * Stage zurueck sein." Als Ausflug aus der Arena: bis zu dieser Zeit (ms) muss der Bot wieder in der Burg sein,
     * 0 = ohne Frist (eigener Modus). Im Ausflug zaehlt kein Run -- der RunTracker gehoert der Arena.
     */
    private long frist;
    /** Alle Inseln abgearbeitet (keine Gegner mehr) -- erst dann wartet der Plan wieder 30 Minuten. */
    private boolean alleErledigt;
    /** HEIM: "Home" auf der Weltkarte schon getippt (der Map-Knopf der Insel sieht aus wie der der Burg). */
    private long heimGeklicktUm;
    /** Nicht auf eine neue Insel, wenn bis zur Frist weniger bleibt (Insel, Burg, Kampf, Rueckweg). */
    static final long INSEL_BRAUCHT_MS = 50_000;
    /** Keine neue Burg antippen, wenn weniger bleibt (Fenster, Scout, Quick Attack, Home, Rueckweg). */
    static final long BURG_BRAUCHT_MS = 40_000;
    private long stoerGeprueftUm;
    private final List<int[]> fertigeInseln = new ArrayList<>();
    private int[] insel;

    @Override public String id() { return "burgen"; }

    @Override public String displayName() { return Sprache.t("Castle raids", "Burgen angreifen"); }

    @Override public List<String> requiredTemplates() { return List.of(T_SYMBOL, T_BURG, T_ANSEHEN, T_SCHNELL, T_HEIM); }

    /** Ein Kampf mit "Quick Attack" dauert ein paar Sekunden; Laden der Inselkarte auch. */
    @Override public long haengerBerichtNachS() { return 90; }

    @Override
    public void reset() {
        schritt = Schritt.ZUR_KARTE;
        seit = System.currentTimeMillis();
        wechsel = 0;
        angriffe = 0;
        angriffeHier = 0;
        aufgegeben = false;
        unbekannt = 0;
        fertigeInseln.clear();
        insel = null;
        frist = 0;
        alleErledigt = false;
        heimGeklicktUm = 0;
    }

    /** Ausflug aus der Arena: bis {@code bisMs} wieder in der Burg (danach geht die Arena von dort zum Turnier). */
    void fristSetzen(long bisMs) {
        frist = bisMs;
    }

    /** Waren am Ende keine Gegner mehr auf der Karte? */
    boolean alleErledigt() {
        return alleErledigt;
    }

    /** Wie viel Zeit bis zur Frist bleibt (ohne Frist: unbegrenzt). */
    private long restMs() {
        return frist <= 0 ? Long.MAX_VALUE : frist - System.currentTimeMillis();
    }

    /** Fertig -- im Ausflug ohne Run (der zaehlt Turniere der Arena). */
    private Outcome fertig(RunTracker runs) {
        if (frist <= 0) runs.completeRun();
        return Outcome.FINISHED;
    }

    /**
     * Code-Review 26.10.: "Yes" wurde in JEDEM Schritt blind gedrueckt -- jede Ja-Frage mit gleichem Knopf (auch eine
     * Kauffrage) waere bestaetigt worden. Jetzt nur, wenn der Text darueber von zu wenig Einheiten spricht und nichts
     * von Kaufen/Diamanten (Autor: "der Bot darf nichts kaufen").
     */
    private static boolean zuWenigEinheitenJa(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_JA)) return false;
        var ja = ctx.matcher().find(screen, T_JA, 0.88);
        if (ja.isEmpty()) return false;
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return false;
        double s = bild.getWidth() / 1159.0;   // Code-Review 26.10.: Masse mit der Fenstergroesse
        int x = Math.max(0, ja.get().centerX() - (int) (300 * s)), y = Math.max(0, ja.get().centerY() - (int) (260 * s));
        int w = Math.min(bild.getWidth() - x, (int) (700 * s)), h = Math.min(bild.getHeight() - y, (int) (240 * s));
        if (w < 50 || h < 50) return false;
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, h)))
                .toLowerCase(java.util.Locale.ROOT);
        // Code-Review 26.10.: "enough" allein passte auch auf "Not enough energy -- refill?" -> nur "units"/"start battle".
        boolean einheiten = text.contains("units") || text.contains("start battle");
        boolean kauf = false;
        for (String wort : List.of("buy", "gem", "diamond", "purchase", "speed up", "skip", "refill", "spend", "cost", "price",
                "coin", "gold", "energy")) {
            if (text.contains(wort)) kauf = true;
        }
        if (!einheiten || kauf) {
            Log.debug(WER, "Yes nicht gedrueckt, Text: " + text);
            return false;
        }
        ctx.klick(ja.get(), WER + Sprache.t(": too few units -- start anyway (Yes)", ": zu wenig Einheiten -- trotzdem kaempfen (Yes)"));
        return true;
    }

    private void weiter(Schritt s) {
        schritt = s;
        seit = System.currentTimeMillis();
        unbekannt = 0;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        long lang = System.currentTimeMillis() - seit;
        if (Stoerfenster.afkUpdate(screen, ctx, WER)) return Outcome.ACTED;
        // "Your squad does not have enough units ... Start battle now?" -> Yes (Autor 25.10.)
        if (zuWenigEinheitenJa(screen, ctx)) {
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, WER)) return Outcome.ACTED;
        }
        switch (schritt) {
            case ZUR_KARTE -> {
                if (Weltkarte.erkannt(screen, ctx) || ctx.visibleIfKnown(screen, T_SYMBOL)) {
                    ctx.sleep(500);   // die Karte gleitet noch
                    weiter(Schritt.INSELN);
                    return Outcome.ACTED;
                }
                // Von der Inselkarte ("Map" unten links) oder aus der Burg ("Map" unten links) auf die Weltkarte.
                if (ctx.clickIfVisibleIfKnown(screen, T_KARTE, WER + Sprache.t(": Map -- to the world map", ": Map -- zur Weltkarte"))
                        || ctx.clickIfVisibleIfKnown(screen, "map_button", WER + Sprache.t(": Map -- to the world map", ": Map -- zur Weltkarte"))
                        || ctx.clickIfVisibleIfKnown(screen, "map_button_alt", WER + Sprache.t(": Map -- to the world map", ": Map -- zur Weltkarte"))) {
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
                if (Stoerfenster.notausgang(screen, ctx, WER, ++unbekannt)) return Outcome.ACTED;
                if (lang > 40_000) return aufgeben(runs, "Weltkarte nicht erreicht");
                ctx.sleep(300);
                return Outcome.WAITING;
            }
            case INSELN -> {
                List<Matcher.Hit> symbole = new ArrayList<>(ctx.matcher().findAll(screen, T_SYMBOL, 0.82, 8));
                symbole.removeIf(h -> fertigeInseln.stream().anyMatch(p -> Math.abs(p[0] - h.centerX()) < 45 && Math.abs(p[1] - h.centerY()) < 45));
                if (symbole.isEmpty()) {
                    if (lang < 1500) { ctx.sleep(300); return Outcome.WAITING; }   // Karte noch nicht ruhig
                    Log.info(WER, Sprache.t("No (more) enemies on the world map -- " + angriffe + " castle(s) attacked. Back to the castle.",
                            "Keine (weiteren) Gegner auf der Weltkarte -- " + angriffe + " Burg(en) angegriffen. Zurueck zur Burg."));
                    // Seit 25.10. alle 30 Minuten eingeschoben: danach geht Arena/Event weiter -- die starten in der Burg.
                    alleErledigt = true;
                    weiter(Schritt.HEIM);
                    return Outcome.ACTED;
                }
                if (restMs() < INSEL_BRAUCHT_MS) {
                    Log.info(WER, Sprache.t("Not enough time for another island -- back to the arena.",
                            "Fuer eine weitere Insel reicht die Zeit nicht -- zurueck zur Arena."));
                    weiter(Schritt.HEIM);
                    return Outcome.ACTED;
                }
                Matcher.Hit s = symbole.get(0);
                insel = new int[]{s.centerX(), s.centerY()};
                angriffeHier = 0;
                ctx.klick(s, WER + Sprache.t(": island with enemies", ": Insel mit Gegnern"));
                ctx.sleepUntilChanged(2500);
                weiter(Schritt.INSEL_OEFFNEN);
                return Outcome.ACTED;
            }
            case INSEL_OEFFNEN -> {
                if (burgFinden(screen, ctx).isPresent() || (ctx.visibleIfKnown(screen, T_KARTE) && !Weltkarte.erkannt(screen, ctx))) {
                    runs.progress(Sprache.t("Island opened", "Insel geoeffnet"));
                    weiter(Schritt.BURG_SUCHEN);
                    return Outcome.ACTED;
                }
                if (lang > 8000) {
                    Log.info(WER, Sprache.t("The island did not open -- next one.", "Die Insel ging nicht auf -- die naechste."));
                    fertigeInseln.add(insel);
                    weiter(Schritt.ZUR_KARTE);
                    return Outcome.ACTED;
                }
                ctx.sleep(300);
                return Outcome.WAITING;
            }
            case BURG_SUCHEN -> {
                Optional<Matcher.Hit> burg = burgFinden(screen, ctx);
                if (burg.isPresent() && restMs() < BURG_BRAUCHT_MS) {
                    Log.info(WER, Sprache.t("Not enough time for another castle -- back to the arena.",
                            "Fuer eine weitere Burg reicht die Zeit nicht -- zurueck zur Arena."));
                    weiter(Schritt.HEIM);
                    return Outcome.ACTED;
                }
                if (burg.isPresent() && angriffeHier >= ANGRIFFE_JE_INSEL_MAX) {
                    Log.info(WER, Sprache.t(angriffeHier + " attacks on this island and still a castle -- probably lost; next island.",
                            angriffeHier + " Angriffe auf dieser Insel und noch immer eine Burg -- wohl verloren; naechste Insel."));
                    if (insel != null) fertigeInseln.add(insel);
                    weiter(Schritt.ZUR_KARTE);
                    return Outcome.ACTED;
                }
                if (burg.isPresent()) {
                    wechsel = 0;
                    ctx.klick(burg.get(), WER + Sprache.t(": enemy castle", ": Burg des Gegners"));
                    ctx.sleepUntilChanged(1500);
                    weiter(Schritt.FENSTER);
                    return Outcome.ACTED;
                }
                if (lang < 2500) { ctx.sleep(300); return Outcome.WAITING; }
                // Keine Burg (mehr) auf dieser Insel -> zurueck auf die Weltkarte, naechste Insel.
                Log.info(WER, Sprache.t("No (more) castle on this island -- back to the world map.",
                        "Keine (weitere) Burg auf dieser Insel -- zurueck zur Weltkarte."));
                if (insel != null) fertigeInseln.add(insel);
                weiter(Schritt.ZUR_KARTE);
                return Outcome.ACTED;
            }
            case FENSTER -> {
                Optional<Matcher.Hit> ansehen = ctx.find(screen, T_ANSEHEN);
                if (ansehen.isEmpty()) {
                    if (lang > 6000) { weiter(Schritt.BURG_SUCHEN); return Outcome.ACTED; }
                    ctx.sleep(250);
                    return Outcome.WAITING;
                }
                long gegner = kraftLesen(ctx, ansehen.get());
                long ich = MeinTeam.squadPower();
                boolean zuStark = gegner > 0 && ich > 0 && gegner > ich + ZU_STARK_UEBER;
                Log.info(WER, Sprache.t("Enemy power " + zahl(gegner) + ", yours " + zahl(ich) + (zuStark ? " -- too strong" : "") + ".",
                        "Kraft des Gegners " + zahl(gegner) + ", deine " + zahl(ich) + (zuStark ? " -- zu stark" : "") + "."));
                if (zuStark && wechsel < WECHSEL_MAX && ctx.clickIfVisibleIfKnown(screen, T_WECHSELN,
                        WER + Sprache.t(": Change opponent (" + (wechsel + 1) + " of " + WECHSEL_MAX + ")", ": Change opponent (" + (wechsel + 1) + " von " + WECHSEL_MAX + ")"))) {
                    wechsel++;
                    ctx.sleepUntilChanged(2000);
                    seit = System.currentTimeMillis();
                    return Outcome.ACTED;
                }
                ctx.klick(ansehen.get(), WER + Sprache.t(": Scout", ": Scout (ansehen)"));
                ctx.sleepUntilChanged(2000);
                weiter(Schritt.ANGRIFF);
                return Outcome.ACTED;
            }
            case ANGRIFF -> {
                if (ctx.clickIfVisibleIfKnown(screen, T_SCHNELL, WER + Sprache.t(": Quick Attack!", ": Quick Attack!"))) {
                    ctx.sleepUntilChanged(2000);
                    weiter(Schritt.KAMPF);
                    return Outcome.ACTED;
                }
                if (lang > 10_000) return aufgeben(runs, "kein Quick-Attack-Knopf");
                ctx.sleep(250);
                return Outcome.WAITING;
            }
            case KAMPF -> {
                if (ctx.clickIfVisibleIfKnown(screen, T_HEIM, WER + Sprache.t(": Home (fight over)", ": Home (Kampf vorbei)"))) {
                    angriffe++;
                    angriffeHier++;
                    runs.progress(Sprache.t("Castle " + angriffe + " attacked", "Burg " + angriffe + " angegriffen"));
                    ctx.sleepUntilChanged(2500);
                    weiter(Schritt.BURG_SUCHEN);
                    return Outcome.ACTED;
                }
                if (lang > 120_000) return aufgeben(runs, "Kampf endet nicht");
                ctx.sleep(400);
                return Outcome.WAITING;
            }
            case HEIM -> {
                // Code-Review 26.10.: nach einem Abbruch in ANGRIFF/KAMPF steht oft noch ein Fenster offen -- erst das X.
                if (aufgegeben && lang < 6000 && !Weltkarte.erkannt(screen, ctx) && Stoerfenster.notausgang(screen, ctx, WER, 99)) {
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
                // Der "Map"-Knopf der Insel ist derselbe wie der der Burg (Fotos 25.10.: map_button_alt 1,00 auf beiden) --
                // darum merkt sich der Modus, ob "Home" auf der Weltkarte schon getippt ist: vorher = Insel, danach = Burg.
                if (Weltkarte.erkannt(screen, ctx)) {
                    if (lang > 20_000) return aufgeben(runs, "Burg nicht erreicht");
                    if (System.currentTimeMillis() - heimGeklicktUm < 3000) { ctx.sleep(300); return Outcome.WAITING; }
                    Weltkarte.heim(screen, ctx, WER);
                    heimGeklicktUm = System.currentTimeMillis();
                    return Outcome.ACTED;
                }
                if (heimGeklicktUm == 0) {
                    if (ctx.clickIfVisibleIfKnown(screen, T_KARTE, WER + Sprache.t(": Map -- off the island", ": Map -- weg von der Insel"))) {
                        ctx.sleepUntilChanged(2000);
                        return Outcome.ACTED;
                    }
                    if (lang > 20_000) return aufgeben(runs, "Weltkarte nicht erreicht");
                    ctx.sleep(300);
                    return Outcome.WAITING;
                }
                if (System.currentTimeMillis() - heimGeklicktUm < 1500) { ctx.sleep(300); return Outcome.WAITING; }   // Burg laedt
                return fertig(runs);
            }
            default -> {
                return Outcome.WAITING;
            }
        }
    }

    /**
     * Die Burg auf der Inselkarte, beste Vorlage der Serie bu_ziel_burg(_2, _3 ...). Fotoserie 25.10.: Die erste Vorlage
     * (mit gruenem Gras drumherum) kam auf Herbst-, Dunkel- und Lava-Inseln nur auf 0,67-0,80 -- unter der Schwelle.
     * Jetzt nur der Burgkoerper ohne Hintergrund, je Inseltyp eine: jede Burg 0,87 oder mehr, sonst hoechstens 0,28.
     */
    private static Optional<Matcher.Hit> burgFinden(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> beste = Optional.empty();
        for (String name : ctx.matcher().serie(T_BURG)) {
            Optional<Matcher.Hit> h = ctx.find(screen, name);
            if (h.isPresent() && (beste.isEmpty() || h.get().score() > beste.get().score())) beste = h;
        }
        return beste;
    }

    /** Die Kraftzahl im Fenster der Burg: 250 Punkte ueber "Scout", etwas rechts davon (Bild 25.10.: "140 412"). */
    private static long kraftLesen(BotContext ctx, Matcher.Hit ansehen) {
        BufferedImage b = ctx.frame();
        if (b == null) return -1;
        double f = ctx.width() / 1159.0;
        int x = Math.max(0, ansehen.centerX() + (int) (-20 * f)), y = Math.max(0, ansehen.centerY() - (int) (275 * f));
        int w = (int) (140 * f), h = (int) (48 * f);
        String t = ArenaLeser.liesTextGross(b, x, y, Math.min(w, b.getWidth() - x), Math.min(h, b.getHeight() - y));
        String z = t == null ? "" : t.replaceAll("[^0-9]", "");
        return z.length() >= 4 && z.length() <= 10 ? Long.parseLong(z) : -1;
    }

    private static String zahl(long k) {
        return k > 0 ? String.format(java.util.Locale.GERMANY, "%,d", k) : Sprache.t("unknown", "unbekannt");
    }

    private Outcome aufgeben(RunTracker runs, String grund) {
        Log.warn(WER, Sprache.t("Stopped (" + grund + ") after " + angriffe + " attack(s).", "Abgebrochen (" + grund + ") nach " + angriffe + " Angriff(en)."));
        if (schritt != Schritt.HEIM && !aufgegeben) {
            aufgegeben = true;
            weiter(Schritt.HEIM);
            return Outcome.ACTED;
        }
        return fertig(runs);
    }
}
