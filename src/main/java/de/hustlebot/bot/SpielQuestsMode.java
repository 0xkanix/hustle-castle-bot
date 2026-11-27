package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Texterkennung;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Taegliche Spiel-Quests (Autor zeigte 18.11.): "!start spielquests".
 *
 * Ablauf, ein Schritt je Aufruf:
 * <pre>
 *   Burg -> Buch-Symbol links ueber der Truhe (sq_buch) -> Fenster "Storyline quests"
 *        -> lila Reiter mit Sanduhr (sq_reiter_daily) -> "Daily quests"
 *   Liste links lesen (Texterkennung, Zeile fuer Zeile) -> jede Quest antippen -> rechts Aufgabe + "Progress x/y" lesen
 *        -> Werbung: Knopf "Watch" (sq_watch), Werbung laufen lassen (NICHTS klicken), danach Fenster zu
 *        -> fertige Quest mit gruenem "Claim" (sq_claim): abholen
 *   nach unten wischen, bis nichts Neues mehr kommt -> Fenster mit rotem X zu
 *   -> Folge-Auftraege (Gem Bay, Verlies, Arena ...) vorn in die Warteschlange, danach noch einmal "spielquests" zum
 *      Abholen (diese zweite Runde haengt nichts mehr an) -> FINISHED.
 * </pre>
 * Zuordnung, Progress und Laeufe stehen in SpielQuests (ohne Spiel getestet). Ohne die Pflicht-Vorlagen hoert der
 * Modus mit einer Meldung auf -- er klickt nie blind auf eine Stelle. Nie Diamanten: es wird nur "Watch", gruenes
 * "Claim", der Reiter, die Listeneintraege und das rote X gedrueckt; "Show" nicht (die Modi finden selbst hin).
 *
 * LIVE PRUEFEN: alle Bildausschnitte (LISTE_*, RECHTS_*), Wischweg und Schriftfarbe sind geschaetzt -- es gibt noch kein
 * Bild dieses Fensters.
 */
public final class SpielQuestsMode implements GameMode {

    private static final String WO = "Spiel-Quests";

    // ---- Vorlagen (Ordner templates\spielquests, siehe Vorlagenordner "sq_") ----
    /**
     * Buch-Symbol in der Burg, links ueber der Truhe. Pflicht. Pruefung 19.11.: es liegt bei (42,497) -- nicht bei
     * (73,523) wie im Protokoll notiert; Vorlage aus Bild 18:51:43 ohne die wechselnde Zahl-Plakette (1,0 in allen 9
     * Burgbildern, 0,98 in Autors Bildern 165-169, sonst hoechstens 0,81).
     */
    static final String T_BUCH = "sq_buch";
    /** Lila Reiter mit Sanduhr links im Fenster "Storyline quests". Pflicht. */
    static final String T_REITER = "sq_reiter_daily";
    /** Knopf "Watch" rechts bei einer Werbe-Quest. Freiwillig: ohne ihn keine Werbung. */
    static final String T_WATCH = "sq_watch";
    /** Gruener Knopf "Claim" rechts bei einer fertigen Quest. Freiwillig: ohne ihn wird nichts abgeholt. */
    static final String T_CLAIM = "sq_claim";
    /** Allgemeine Vorlagen, die es schon gibt. */
    private static final String T_ROTES_X = "close_x", T_ANGEBOT_X = "ev_angebot_x", T_WERBUNG_TEXT = "cw_werbung";

    // ---- Bildausschnitte auf der Spielflaeche 1159 x 770 -- GESCHAETZT, LIVE PRUEFEN ----
    /** Liste links (Quest-Namen), rechts neben den Reitern. */
    static final int LISTE_X = 150, LISTE_Y = 150, LISTE_B = 400, LISTE_H = 590;
    /** Feld rechts (Aufgabe, "Progress x/y", Belohnung, Knopf). */
    static final int RECHTS_X = 570, RECHTS_Y = 120, RECHTS_B = 560, RECHTS_H = 620;
    /** Liste nach unten blaettern: von unten nach oben wischen. */
    static final int WISCH_X = LISTE_X + LISTE_B / 2, WISCH_VON_Y = 640, WISCH_BIS_Y = 300;
    /** Drueckdauer beim Tippen (kurze Klicks nimmt das Spiel oft nicht an). */
    static final int LANG_MS = 110;

    // ---- Grenzen ----
    static final int MAX_WISCHEN = 6;
    static final int MAX_QUESTS = 25;
    /** Mehr als so viele Abholungen je Lauf sind ein Zeichen fuer eine Schleife (ein Knopf, der nicht verschwindet). */
    static final int MAX_ABHOLEN = 30;
    /** Derselbe Knopf (Lage auf 20 px gerundet) wird nicht oefter als so oft angetippt. */
    static final int MAX_GLEICHER_KNOPF = 2;
    static final int MAX_SCHLIESSEN = 4;
    static final int MAX_BUCH_KLICKS = 3;
    static final int MAX_HEIMWEG = 6;
    static final int MAX_SCHRITTE = 500;
    /** Werbung: vorher nicht nachsehen (laedt sie langsam, steht kurz noch das Fenster da), danach Alarm ohne Klick. */
    static final long WERBUNG_MIN_MS = 35_000, WERBUNG_MAX_MS = 180_000;
    static final int MAX_WERBUNGEN = 8;
    /** Abschluss-Runde (Abholen) gilt nur, wenn sie innerhalb dieser Zeit nach der ersten Runde kommt. */
    static final long ABSCHLUSS_GILT_MS = 8 * 60 * 60_000L;

    private enum Phase { START, OEFFNEN, LISTE, DETAIL, NACH_CLAIM, WERBUNG, NACH_WERBUNG, SCHLIESSEN, FERTIG }

    /** Eine gelesene Zeile mit ihrer Mitte auf der Spielflaeche (zum Antippen). */
    record Zeile(String text, int x, int y) { }

    // ---- Zustand eines Laufs ----
    private Phase phase = Phase.START;
    private int schritte, buchKlicks, heimweg, wischen, ohneNeues, leereLesungen, schliessKlicks, nachClaim, nachWerbung;
    private long heimwegSeit, werbungSeit;
    private boolean reiterGeklickt, abschluss;
    private final Set<String> angetippt = new HashSet<>();
    private final Map<String, SpielQuests.Quest> quests = new LinkedHashMap<>();
    private final Map<String, Integer> werbungJeQuest = new HashMap<>();
    /** Stand der Werbe-Quest vor dem ersten "Watch" -- ohne Zuwachs danach geht die Werbung am PC nicht. */
    private final Map<String, Long> werbungStand = new HashMap<>();
    private final Set<String> gemeldet = new HashSet<>();
    private final Map<String, Integer> claimJeLage = new HashMap<>();
    private Zeile aktuell;
    /**
     * Die zuletzt gelesene Liste. Nach dem Antippen einer Quest bleibt die Liste, wie sie ist -- sie wird erst nach
     * Wischen, Abholen, Werbung oder neuem Oeffnen wieder gelesen (jede Zeile kostet die Texterkennung ~1 s).
     */
    private List<Zeile> letzteListe;
    private String werbungName;
    private int werbungen, abgeholt;
    private List<GameMode.Folge> folgen = List.of();
    private String ende;

    // ---- ueber das Ende eines Laufs hinaus: kommt die Abschluss-Runde (Abholen) aus der Warteschlange zurueck ----
    private long abschlussBis;

    @Override public String id() { return "spielquests"; }
    @Override public String displayName() { return Sprache.t("Daily game quests", "Tages-Spielquests"); }
    @Override public List<String> requiredTemplates() { return List.of(); }

    @Override
    public void reset() {
        phase = Phase.START;
        schritte = buchKlicks = heimweg = wischen = ohneNeues = leereLesungen = schliessKlicks = nachClaim = nachWerbung = 0;
        heimwegSeit = werbungSeit = 0;
        reiterGeklickt = false;
        angetippt.clear();
        quests.clear();
        werbungJeQuest.clear();
        werbungStand.clear();
        gemeldet.clear();
        claimJeLage.clear();
        aktuell = null;
        letzteListe = null;
        werbungName = null;
        werbungen = abgeholt = 0;
        folgen = List.of();
        ende = null;
        long jetzt = System.currentTimeMillis();
        abschluss = abschlussBis > jetzt;
        abschlussBis = 0;
        if (abschluss) {
            Log.info(WO, Sprache.t("Final round: claim finished quests and watch ads -- nothing more is queued.", "Abschluss-Runde: fertige Quests abholen und Werbung ansehen -- es wird nichts mehr angehaengt."));
        }
    }

    @Override
    public List<GameMode.Folge> folgeAuftraege() {
        return folgen;
    }

    @Override
    public String endeHinweis() {
        return ende;
    }

    /** Waehrend die Werbung laeuft, darf der Motor NICHTS allgemein klicken (Klick in die Werbung = fremde Seite). */
    @Override
    public boolean inEmpfindlicherPhase() {
        return phase == Phase.WERBUNG;
    }

    /** Werbung abwarten ist kein Haenger. */
    @Override
    public boolean wartetNurAufAndere() {
        return phase == Phase.WERBUNG;
    }

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Sprache.t("Quests read", "Quests gelesen"), String.valueOf(quests.size()));
        m.put(Sprache.t("Ads watched", "Werbungen angesehen"), String.valueOf(werbungen));
        m.put(Sprache.t("Rewards claimed", "Belohnungen abgeholt"), String.valueOf(abgeholt));
        return m;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        // Waehrend der Werbung auch kein "Update"/"Try again" -- jeder Klick koennte in die Werbung gehen.
        if (phase != Phase.WERBUNG && Stoerfenster.afkUpdate(screen, ctx, WO)) return Outcome.ACTED;
        // Die Werbung zaehlt nicht mit (bis zu 3 Minuten je Video, ein Schritt je Sekunde)
        if (phase != Phase.WERBUNG && ++schritte > MAX_SCHRITTE) {
            foto(ctx, "zu-viele-schritte");
            return beenden(runs, Sprache.t("Not done after ", "Nach ") + MAX_SCHRITTE + Sprache.t(" steps -- stopping.", " Schritten nicht fertig -- Ende."), true);
        }
        return switch (phase) {
            case START -> start(runs);
            case OEFFNEN -> oeffnen(screen, ctx, runs);
            case LISTE -> liste(ctx, runs);
            case DETAIL -> detail(screen, ctx, runs);
            case NACH_CLAIM -> nachClaim(screen, ctx);
            case WERBUNG -> werbung(screen, ctx, runs);
            case NACH_WERBUNG -> nachWerbung(screen, ctx);
            case SCHLIESSEN -> schliessen(screen, ctx);
            case FERTIG -> fertig(runs);
        };
    }

    // ------------------------------------------------------------------ Start und Fenster oeffnen

    private Outcome start(RunTracker runs) {
        runs.progress(Sprache.t("Daily quests: start", "Spiel-Quests: Start"));
        if (!Texterkennung.istDa()) {
            return beenden(runs, Sprache.t("Text recognition is missing (texterkennung.ps1) -- without it the bot cannot ", "Die Texterkennung fehlt (texterkennung.ps1) -- ohne sie kann der Bot die Quests nicht ")
                    + Sprache.t("read the quests. Nothing done.", "lesen. Nichts gemacht."), true);
        }
        // Die Pruefung auf die Vorlagen macht oeffnen() -- erst dort steht fest, ob das Fenster vielleicht schon offen ist.
        phase = Phase.OEFFNEN;
        return Outcome.ACTED;
    }

    private Outcome oeffnen(Mat screen, BotContext ctx, RunTracker runs) {
        for (String t : List.of(T_BUCH, T_REITER)) {
            if (!ctx.matcher().has(t)) {
                return beenden(runs, Sprache.t("The template ", "Die Vorlage ") + t + Sprache.t(" is missing (", " fehlt (") + vorlagenText(t) + Sprache.t(", folder templates\\spielquests) ", ", Ordner templates\\spielquests) ")
                        + Sprache.t("-- without it the bot does not click. Please capture it.", "-- ohne sie klickt der Bot nicht. Bitte aufnehmen."), true);
            }
        }
        runs.progress(Sprache.t("Daily quests: opening the window", "Spiel-Quests: Fenster oeffnen"));
        // 1. Fenster offen: lila Reiter "Daily quests" antippen (einmal; danach gilt er als gewaehlt)
        Optional<Matcher.Hit> reiter = ctx.find(screen, T_REITER);
        if (reiter.isPresent()) {
            if (!reiterGeklickt) {
                ctx.klick(reiter.get(), wo() + Sprache.t(": purple tab with hourglass (Daily quests)", ": lila Reiter mit Sanduhr (Daily quests)"));
                reiterGeklickt = true;
                ctx.sleepUntilChanged(1500);
                ctx.sleep(400);
                return Outcome.ACTED;
            }
            phase = Phase.LISTE;
            ohneNeues = leereLesungen = 0;
            letzteListe = null;
            return Outcome.ACTED;
        }
        reiterGeklickt = false;
        // 2. In der Burg: Buch-Symbol
        boolean burg = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
        Optional<Matcher.Hit> buch = ctx.find(screen, T_BUCH);
        if (buch.isPresent() && buch.get().centerX() < ctx.width() / 2) {
            if (++buchKlicks > MAX_BUCH_KLICKS) {
                foto(ctx, "fenster-geht-nicht-auf");
                return beenden(runs, Sprache.t("The book was tapped ", "Das Buch wurde ") + MAX_BUCH_KLICKS + Sprache.t(" times, but the purple tab (", " Mal angetippt, aber der lila Reiter (")
                        + T_REITER + Sprache.t(") never came -- stopping.", ") kam nie -- Ende."), true);
            }
            ctx.klick(buch.get(), wo() + Sprache.t(": book symbol (Storyline quests)", ": Buch-Symbol (Storyline quests)"));
            ctx.sleepUntilChanged(2000);
            ctx.sleep(500);
            return Outcome.ACTED;
        }
        if (burg) {
            // Burg zu sehen, aber kein Buch: das Buch ist woanders oder verdeckt -- nicht blind tippen.
            if (gemeldet.add("kein-buch")) {
                Log.warn(WO, Sprache.t("In the castle, but the book symbol (", "In der Burg, aber das Buch-Symbol (") + T_BUCH + Sprache.t(") is not visible -- waiting briefly.", ") ist nicht zu sehen -- es wird kurz gewartet."));
            }
        }
        // 3. Nicht in der Burg (oder Buch verdeckt): Fenster ueber das rote X schliessen, wie die Nahrungskisten
        if (heimwegSeit == 0) heimwegSeit = System.currentTimeMillis();
        if (heimweg < MAX_HEIMWEG && (ctx.clickIfVisibleIfKnown(screen, T_ROTES_X, wo() + Sprache.t(": close the window (to the castle)", ": Fenster schliessen (zur Burg)"))
                || ctx.clickIfVisibleIfKnown(screen, T_ANGEBOT_X, wo() + Sprache.t(": close the window (to the castle)", ": Fenster schliessen (zur Burg)"))
                || !burg && Weltkarte.homeUntenLinksKlicken(screen, ctx, wo() + Sprache.t(": world map -- Home (to the castle)", ": Weltkarte -- Home (zur Burg)"))
                || !burg && ctx.clickIfVisibleIfKnown(screen, "home_button", wo() + Sprache.t(": Home (to the castle)", ": Home (zur Burg)")))) {
            heimweg++;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (System.currentTimeMillis() - heimwegSeit > 60_000 || heimweg >= MAX_HEIMWEG) {
            foto(ctx, "kein-buch");
            return beenden(runs, burg ? Sprache.t("In the castle the book symbol (", "In der Burg ist das Buch-Symbol (") + T_BUCH + Sprache.t(") has not been visible for a minute -- stopping.", ") seit einer Minute nicht zu sehen -- Ende.")
                    : Sprache.t("The castle cannot be reached -- stopping.", "Die Burg ist nicht zu erreichen -- Ende."), true);
        }
        ctx.sleep(500);
        return Outcome.WAITING;
    }

    private static String vorlagenText(String t) {
        return switch (t) {
            case T_BUCH -> Sprache.t("book symbol on the left above the chest in the castle", "Buch-Symbol links ueber der Truhe in der Burg");
            case T_REITER -> Sprache.t("purple tab with hourglass in the \"Storyline quests\" window", "lila Reiter mit Sanduhr im Fenster \"Storyline quests\"");
            case T_WATCH -> Sprache.t("\"Watch\" button on the right of an ad quest", "Knopf \"Watch\" rechts bei einer Werbe-Quest");
            case T_CLAIM -> Sprache.t("green \"Claim\" button on the right of a finished quest", "gruener Knopf \"Claim\" rechts bei einer fertigen Quest");
            default -> t;
        };
    }

    // ------------------------------------------------------------------ Liste links

    private Outcome liste(BotContext ctx, RunTracker runs) {
        if (quests.size() >= MAX_QUESTS) {
            phase = Phase.SCHLIESSEN;
            return Outcome.ACTED;
        }
        // 21.11. (03:08): die Liste war nicht lesbar, fertige Quests blieben liegen. Darum zuerst alles Abholbare im Fenster
        // abholen -- unabhaengig davon, ob die Zeilen gelesen werden (gruenes "Claim reward", wo auch immer es sitzt).
        if (abgeholt < MAX_ABHOLEN && claimKlick(null, ctx, foto(ctx), wo() + Sprache.t(": claim a finished quest (Claim reward)", ": fertige Quest abholen (Claim reward)"))) {
            abgeholt++;
            nachClaim = 0;
            letzteListe = null;
            ohneNeues = 0;
            phase = Phase.NACH_CLAIM;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        List<Zeile> zeilen = letzteListe;
        if (zeilen == null) {
            zeilen = listeLesen(foto(ctx));
            Log.debug(WO, "Liste: " + zeilen);
            letzteListe = zeilen.isEmpty() ? null : zeilen;
        }
        runs.progress(Sprache.t("Daily quests: reading the list (", "Spiel-Quests: Liste lesen (") + quests.size() + Sprache.t(" read)", " gelesen)"));
        if (zeilen.isEmpty()) {
            if (++leereLesungen >= 3) {
                if (quests.isEmpty() && abgeholt == 0) {
                    foto(ctx, "liste-unlesbar");
                    return beenden(runs, Sprache.t("The quest list was not readable (no line recognised) -- stopping. Check LISTE_*.", "Die Quest-Liste war nicht lesbar (keine Zeile erkannt) -- Ende. LISTE_* pruefen."), true);
                }
                phase = Phase.SCHLIESSEN;
                return Outcome.ACTED;
            }
            ctx.sleep(800);
            return Outcome.WAITING;
        }
        leereLesungen = 0;
        for (Zeile z : zeilen) {
            String k = SpielQuests.schluessel(z.text());
            if (k.isEmpty() || !SpielQuests.istQuestName(z.text()) || !angetippt.add(k)) continue;
            aktuell = z;
            ohneNeues = 0;
            Log.action("Klick", wo() + Sprache.t(": tap quest \"", ": Quest \"") + z.text() + Sprache.t("\" (", "\" antippen (") + z.x() + "," + z.y() + ")");
            ctx.input().tippWeich(z.x(), z.y(), LANG_MS);
            ctx.sleepUntilChanged(1200);
            ctx.sleep(300);
            phase = Phase.DETAIL;
            return Outcome.ACTED;
        }
        // nichts Neues zu sehen: weiter nach unten
        if (++ohneNeues > 2 || wischen >= MAX_WISCHEN) {
            phase = Phase.SCHLIESSEN;
            return Outcome.ACTED;
        }
        wischen++;
        letzteListe = null;
        Log.action("Klick", wo() + Sprache.t(": scroll the list down (", ": Liste nach unten blaettern (") + wischen + ")");
        ctx.input().dragLangsam(WISCH_X, WISCH_VON_Y, WISCH_X, WISCH_BIS_Y);
        ctx.sleepUntilChanged(1200);
        ctx.sleep(500);   // die Liste laeuft nach
        return Outcome.ACTED;
    }

    // ------------------------------------------------------------------ Feld rechts

    private Outcome detail(Mat screen, BotContext ctx, RunTracker runs) {
        phase = Phase.LISTE;
        BufferedImage bild = foto(ctx);
        List<String> zeilen = rechtsLesen(bild);
        SpielQuests.Detail d = SpielQuests.detail(aktuell == null ? null : aktuell.text(), zeilen);
        // Schluessel nach der Aufgabe rechts: ein Name auf zwei Zeilen links ("Boarding" / "Crew") ist so nur EINE Quest
        String k = SpielQuests.schluessel(d.aufgabe().isBlank() ? d.titel() : d.aufgabe());
        SpielQuests.Quest q = d.alsQuest();
        SpielQuests.Quest alt = quests.get(k);
        if (alt != null && alt.titel().length() > q.titel().length()) {
            q = new SpielQuests.Quest(alt.titel(), q.aufgabe(), q.stand(), q.ziel(), false);
        }
        if (alt != null && alt.abgeholt()) q = q.mitAbgeholt();
        quests.put(k, q);
        Log.info(WO, "Quest " + q.zeile() + Sprache.t("   [read on the right: ", "   [rechts gelesen: ") + zeilen + "]");
        runs.progress(Sprache.t("Daily quests: ", "Spiel-Quests: ") + d.titel());

        // Fertig: gruenes "Claim" rechts abholen (nur per Vorlage, nie geraten)
        if (claimKlick(screen, ctx, bild, wo() + Sprache.t(": claim the reward (Claim) -- \"", ": Belohnung abholen (Claim) -- \"") + d.titel() + "\"")) {
            quests.put(k, q.mitAbgeholt());
            abgeholt++;
            nachClaim = 0;
            letzteListe = null;
            phase = Phase.NACH_CLAIM;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        if (q.zielArt() == SpielQuests.Ziel.WERBUNG && !q.erledigt()) {
            // Schon einmal "Watch" gedrueckt, aber kein Fortschritt und nichts abzuholen: die Werbung laeuft am PC nicht
            // (Spiel: "To use this feature, you must restart the game ...") -- fuer heute in ALLEN Modi auslassen.
            Long vorher = werbungStand.get(k);
            if (vorher != null && werbungJeQuest.getOrDefault(k, 0) > 0 && q.stand() <= vorher) {
                SpielQuests.werbungAus(java.time.LocalDateTime.now());
                Log.warn(WO, Sprache.t("Ad \"", "Werbung \"") + q.titel() + Sprache.t("\": no progress after \"Watch\" (", "\": nach \"Watch\" kein Fortschritt (") + q.stand() + Sprache.t(") -- ads do not ", ") -- die Werbung ")
                        + Sprache.t("work on the PC, skipped for today.", "geht am PC nicht, fuer heute ausgelassen."));
                return Outcome.ACTED;
            }
            return werbungStarten(screen, ctx, k, q);
        }
        if (!q.zielArt().botKann() && !q.erledigt()) {
            Log.info(WO, Sprache.t("Skipped: ", "Uebersprungen: ") + q.zeile());
        }
        return Outcome.ACTED;
    }

    /** Werbe-Quest: "Watch" druecken, wenn die Vorlage da und der Knopf zu sehen ist. */
    private Outcome werbungStarten(Mat screen, BotContext ctx, String k, SpielQuests.Quest q) {
        if (SpielQuests.werbungAus(java.time.LocalDateTime.now(), true)) {
            if (gemeldet.add("werbung-aus")) Log.info(WO, Sprache.t("Ad \"", "Werbung \"") + q.titel() + Sprache.t("\": not possible on the PC today -- skipped.", "\": heute am PC nicht moeglich -- ausgelassen."));
            return Outcome.ACTED;
        }
        if (!ctx.matcher().has(T_WATCH)) {
            if (gemeldet.add("watch-fehlt")) {
                Log.warn(WO, Sprache.t("The template ", "Die Vorlage ") + T_WATCH + Sprache.t(" is missing (", " fehlt (") + vorlagenText(T_WATCH) + Sprache.t(") -- ad quests are ", ") -- Werbe-Quests werden ")
                        + Sprache.t("skipped. Please capture it.", "uebersprungen. Bitte aufnehmen."));
            }
            return Outcome.ACTED;
        }
        int schon = werbungJeQuest.getOrDefault(k, 0);
        int grenze = (int) Math.min(SpielQuests.Ziel.WERBUNG.hoechstens, Math.max(1, q.ziel() > 0 ? q.ziel() : 1));
        if (werbungen >= MAX_WERBUNGEN || schon >= grenze) {
            Log.info(WO, Sprache.t("Ad \"", "Werbung \"") + q.titel() + Sprache.t("\": already ", "\": schon ") + schon + Sprache.t(" watched -- enough for this run.", " angesehen -- genug fuer diesen Lauf."));
            return Outcome.ACTED;
        }
        Optional<Matcher.Hit> watch = rechtsFinden(screen, ctx, T_WATCH);
        if (watch.isEmpty()) {
            Log.info(WO, Sprache.t("Ad \"", "Werbung \"") + q.titel() + Sprache.t("\": no \"Watch\" button visible (waiting time?) -- skipped.", "\": kein \"Watch\"-Knopf zu sehen (Wartezeit?) -- uebersprungen."));
            return Outcome.ACTED;
        }
        ctx.klick(watch.get(), wo() + Sprache.t(": start the ad (Watch) -- \"", ": Werbung starten (Watch) -- \"") + q.titel() + Sprache.t("\". Now NOTHING is pressed.", "\". Jetzt wird NICHTS gedrueckt."));
        werbungJeQuest.put(k, schon + 1);
        werbungStand.putIfAbsent(k, Math.max(0L, q.stand()));
        werbungName = aktuell == null ? null : SpielQuests.schluessel(aktuell.text());
        werbungSeit = System.currentTimeMillis();
        letzteListe = null;
        phase = Phase.WERBUNG;
        ctx.sleep(2000);
        return Outcome.ACTED;
    }

    private Outcome nachClaim(Mat screen, BotContext ctx) {
        // Nach "Claim" kommt meist ein Belohnungsfenster. Zurueck in der Liste: weiter lesen.
        if (ctx.visible(screen, T_REITER)) {
            phase = Phase.LISTE;
            return Outcome.ACTED;
        }
        if (++nachClaim > 6) {
            // Fenster ist weg (oder etwas anderes liegt davor): von vorn oeffnen, Gelesenes bleibt
            phase = Phase.OEFFNEN;
            reiterGeklickt = false;
            return Outcome.ACTED;
        }
        if (Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
        if (rechtsFinden(screen, ctx, T_CLAIM).isPresent()
                && ctx.clickIfVisibleIfKnown(screen, T_CLAIM, wo() + Sprache.t(": reward window -- Claim", ": Belohnungsfenster -- Claim"))) {
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (nachClaim >= 3 && xObenRechts(screen, ctx, wo() + Sprache.t(": close the reward window (red X)", ": Belohnungsfenster schliessen (rotes X)"))) return Outcome.ACTED;
        ctx.sleep(700);
        return Outcome.WAITING;
    }

    // ------------------------------------------------------------------ Werbung

    /** NICHTS DRUECKEN, solange die Werbung laeuft. Erst nach WERBUNG_MIN_MS hinsehen, ob sie vorbei ist. */
    private Outcome werbung(Mat screen, BotContext ctx, RunTracker runs) {
        long dauer = System.currentTimeMillis() - werbungSeit;
        runs.progress(Sprache.t("Daily quests: ad running (", "Spiel-Quests: Werbung laeuft (") + dauer / 1000 + Sprache.t(" s) -- pressing nothing", " s) -- nichts druecken"));
        if (dauer > WERBUNG_MAX_MS) {
            // Auch jetzt NICHT klicken (wie Nahrungsvideo): ein Klick in eine Werbung kann eine fremde Seite oeffnen.
            Log.alarm(WO, Sprache.t("The ad was not over after ", "Die Werbung war nach ") + dauer / 1000 + Sprache.t(" s. The bot pressed NOTHING -- ", " s noch nicht vorbei. Der Bot hat NICHTS gedrueckt -- ")
                    + Sprache.t("please check at the PC.", "bitte am Rechner nachsehen."));
            phase = Phase.FERTIG;
            return beenden(runs, Sprache.t("Ad not over after ", "Werbung nach ") + dauer / 1000 + Sprache.t(" s -- stopping without a click.", " s nicht vorbei -- Ende ohne Klick."), true);
        }
        if (dauer < WERBUNG_MIN_MS || ctx.visibleIfKnown(screen, T_WERBUNG_TEXT)) {
            ctx.sleep(1000);
            return Outcome.WAITING;
        }
        // Vorbei, wenn wieder etwas aus dem Spiel zu sehen ist: das Quest-Fenster, ein Claim- oder ein rotes X
        boolean fenster = ctx.visible(screen, T_REITER);
        boolean claim = ctx.visibleIfKnown(screen, T_CLAIM);
        boolean x = ctx.visibleIfKnown(screen, T_ROTES_X);
        if (!fenster && !claim && !x) {
            ctx.sleep(1000);
            return Outcome.WAITING;
        }
        werbungen++;
        Log.info(WO, Sprache.t("Ad ", "Werbung ") + werbungen + Sprache.t(" finished (", " zu Ende (") + dauer / 1000 + " s).");
        phase = Phase.NACH_WERBUNG;
        nachWerbung = 0;
        return Outcome.ACTED;
    }

    /** Nach der Werbung: Belohnungsfenster (Claim / rotes X) zu, dann dieselbe Quest noch einmal ansehen. */
    private Outcome nachWerbung(Mat screen, BotContext ctx) {
        if (++nachWerbung > 8) {
            phase = Phase.OEFFNEN;
            reiterGeklickt = false;
            nochmalAnsehen();
            return Outcome.ACTED;
        }
        if (ctx.clickIfVisibleIfKnown(screen, T_CLAIM, wo() + Sprache.t(": after the ad -- Claim", ": nach der Werbung -- Claim"))) {
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        boolean fenster = ctx.visible(screen, T_REITER);
        if (!fenster) {
            if (xObenRechts(screen, ctx, wo() + Sprache.t(": after the ad -- close the window (red X)", ": nach der Werbung -- Fenster schliessen (rotes X)"))) return Outcome.ACTED;
            if (Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
            ctx.sleep(700);
            return Outcome.WAITING;
        }
        // Zurueck im Quest-Fenster. Liegt noch ein Belohnungsfenster darueber, steht der Reiter zwar da, ist aber
        // verdeckt -- das zeigt erst live, ob das vorkommt (LIVE PRUEFEN). Dieselbe Quest noch einmal lesen:
        nochmalAnsehen();
        phase = Phase.LISTE;
        return Outcome.ACTED;
    }

    /** Die Werbe-Quest beim naechsten Lesen der Liste noch einmal antippen (Movie Theater = 3 Videos). */
    private void nochmalAnsehen() {
        if (werbungName != null) angetippt.remove(werbungName);
        werbungName = null;
    }

    // ------------------------------------------------------------------ Ende

    private Outcome schliessen(Mat screen, BotContext ctx) {
        if (schliessKlicks < MAX_SCHLIESSEN && ctx.visible(screen, T_REITER)) {
            if (xObenRechts(screen, ctx, wo() + Sprache.t(": close the \"Storyline quests\" window (red X)", ": Fenster \"Storyline quests\" schliessen (rotes X)"))) {
                schliessKlicks++;
                return Outcome.ACTED;
            }
            // 21.11. (03:08): die Vorlage traf das X dieses Fensters nicht -- das rote Rund oben rechts per Farbe suchen,
            // zuletzt dort tippen, wo es in allen Fenstern sitzt (wie TeamScan: 1120/1159, 33/770).
            int[] x = roterKreisObenRechts(foto(ctx));
            if (x == null) x = new int[]{(int) Math.round(ctx.width() * 1120 / 1159.0), (int) Math.round(ctx.height() * 33 / 770.0)};
            if (gemeldet.add("kein-x")) Log.warn(WO, Sprache.t("The red X of the quest window is not visible by template -- searching ", "Das rote X des Quest-Fensters ist per Vorlage nicht zu sehen -- es wird ")
                    + Sprache.t("by colour (", "per Farbe gesucht (") + x[0] + "," + x[1] + ").");
            Log.action("Klick", wo() + Sprache.t(": close the \"Storyline quests\" window (red X by colour, ", ": Fenster \"Storyline quests\" schliessen (rotes X per Farbe, ") + x[0] + "," + x[1] + ")");
            ctx.input().tippWeich(x[0], x[1], LANG_MS);
            schliessKlicks++;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        phase = Phase.FERTIG;
        return Outcome.ACTED;
    }

    private Outcome fertig(RunTracker runs) {
        List<SpielQuests.Quest> alle = new ArrayList<>(quests.values());
        for (SpielQuests.Quest q : alle) {
            if (!q.erledigt() && !q.zielArt().botKann()) Log.info(WO, Sprache.t("Skipped: ", "Uebersprungen: ") + q.zeile());
        }
        List<GameMode.Folge> plan = abschluss ? List.of() : SpielQuests.folgen(alle);
        if (!plan.isEmpty()) {
            List<GameMode.Folge> mitAbschluss = new ArrayList<>(plan);
            mitAbschluss.add(new GameMode.Folge(id(), 1));   // danach noch einmal: abholen
            folgen = List.copyOf(mitAbschluss);
            abschlussBis = System.currentTimeMillis() + ABSCHLUSS_GILT_MS;
        } else {
            folgen = List.of();
        }
        offeneMerken(alle);
        String text = SpielQuests.zusammenfassung(alle, plan, werbungen, abgeholt);
        Log.info(WO, text + (abschluss ? Sprache.t(" (final round)", " (Abschluss-Runde)") : ""));
        ende = Sprache.t("Daily quests: ", "Spiel-Quests: ") + text;
        runs.completeRun();
        return Outcome.FINISHED;
    }

    /** Offene Burg-Tagesquests (Name, Fortschritt) fuer den Ablauf und fuer Autor: spielquests-offen.txt. */
    private static void offeneMerken(List<SpielQuests.Quest> alle) {
        try {
            List<String> z = new ArrayList<>();
            z.add("# Offene Burg-Tagesquests, gelesen " + java.time.LocalDateTime.now().withNano(0).withSecond(0));
            for (SpielQuests.Quest q : alle) if (!q.erledigt()) z.add(q.zeile());
            Files.write(de.hustlebot.core.Ordner.bei("spielquests-offen.txt"), z);
        } catch (Exception e) {
            Log.debug(WO, "spielquests-offen.txt nicht gespeichert: " + e.getMessage());
        }
    }

    /** Name des Modus in Protokoll-Texten (die Quelle WO uebersetzt Sprache.quelle). */
    private static String wo() {
        return Sprache.t("Daily quests", WO);
    }

    private Outcome beenden(RunTracker runs, String grund, boolean fehler) {
        if (fehler) Log.error(WO, grund); else Log.info(WO, grund);
        ende = Sprache.t("Daily quests: ", "Spiel-Quests: ") + grund;
        folgen = List.of();
        runs.completeRun();
        return Outcome.FINISHED;
    }

    // ------------------------------------------------------------------ Bild

    private static BufferedImage foto(BotContext ctx) {
        BufferedImage b = ctx.frischesFoto();
        return b != null ? b : ctx.frame();
    }

    /** Ein Treffer nur im Feld rechts -- ein "Claim"/"Watch" anderswo ist nicht gemeint. */
    private static Optional<Matcher.Hit> rechtsFinden(Mat screen, BotContext ctx, String vorlage) {
        if (!ctx.matcher().has(vorlage)) return Optional.empty();
        Optional<Matcher.Hit> h = ctx.find(screen, vorlage);
        if (h.isEmpty()) return h;
        int x = h.get().centerX(), y = h.get().centerY();
        boolean drin = x >= RECHTS_X && x <= RECHTS_X + RECHTS_B && y >= RECHTS_Y && y <= RECHTS_Y + RECHTS_H;
        return drin ? h : Optional.empty();
    }

    /** Rotes X nur oben rechts (Fenster-X), nie mitten im Bild. */
    private static boolean xObenRechts(Mat screen, BotContext ctx, String grund) {
        for (String t : List.of(T_ROTES_X, T_ANGEBOT_X)) {
            if (!ctx.matcher().has(t)) continue;
            Optional<Matcher.Hit> x = ctx.find(screen, t);
            if (x.isPresent() && x.get().centerX() > ctx.width() * 0.60 && x.get().centerY() < ctx.height() * 0.30) {
                ctx.klick(x.get(), grund);
                ctx.sleepUntilChanged(1500);
                return true;
            }
        }
        return false;
    }

    /**
     * Klickt ein fertiges "Claim reward": erst per Vorlage (sq_claim, sonst die vom Event ev_claim), dann am gruenen
     * Knopf (Farbe + Texterkennung "Claim"). Nur unterhalb der Kopfzeile; derselbe Knopf nur MAX_GLEICHER_KNOPF Mal.
     */
    private boolean claimKlick(Mat screen, BotContext ctx, BufferedImage bild, String grund) {
        if (screen == null) screen = ctx.aktuellesBild();
        for (String t : List.of(T_CLAIM, "ev_claim")) {
            if (screen == null || !ctx.matcher().has(t)) continue;
            Optional<Matcher.Hit> h = ctx.find(screen, t);
            if (h.isEmpty() || h.get().centerY() < KOPF_H) continue;
            if (!claimErlaubt(h.get().centerX(), h.get().centerY())) continue;
            ctx.klick(h.get(), grund);
            return true;
        }
        for (int[] c : gruenerKnoepfe(bild)) {
            if (!istClaimText(knopfText(bild, c))) continue;
            if (!claimErlaubt(c[0], c[1])) continue;
            Log.action("Klick", grund + Sprache.t("  [green button at ", "  [gruener Knopf bei ") + c[0] + "," + c[1] + "]");
            ctx.input().tippWeich(c[0], c[1], LANG_MS);
            return true;
        }
        return false;
    }

    private boolean claimErlaubt(int x, int y) {
        String key = x / 20 + "/" + y / 20;
        int n = claimJeLage.merge(key, 1, Integer::sum);
        if (n > MAX_GLEICHER_KNOPF) {
            if (gemeldet.add("claim-" + key)) Log.warn(WO, Sprache.t("The claim button at ", "Der Abhol-Knopf bei ") + x + "," + y + Sprache.t(" stays after ", " bleibt nach ") + MAX_GLEICHER_KNOPF
                    + Sprache.t(" clicks -- not tapped any more.", " Klicks stehen -- wird nicht mehr angetippt."));
            return false;
        }
        return true;
    }

    /** Die Kopfzeile (Waehrung, rotes X) hat keine Abhol-Knoepfe. */
    static final int KOPF_H = 100;

    /** Gruen: wie der Knopf "Claim reward" (100,200,20), Rand (0,160,0). */
    static boolean gruen(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return g >= 150 && r <= 150 && b <= 70 && g - r >= 40;
    }

    /** Rot: wie das Rund des Fenster-X (255,94,66). */
    static boolean rot(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r >= 200 && g <= 125 && b <= 105;
    }

    /**
     * Zusammenhaengende Farbflecken, die wie ein Knopf aussehen: {Mitte x, Mitte y, Breite, Hoehe}, von oben nach unten.
     * Raster 2 px, 8er-Nachbarschaft; gesucht ab (xAb, yAb) bis yBis.
     */
    static List<int[]> fleckenSuchen(BufferedImage bild, IntPredicate farbe, int minB, int maxB, int minH, int maxH,
                                     double minFuell, int xAb, int yAb, int yBis) {
        List<int[]> out = new ArrayList<>();
        if (bild == null) return out;
        int w = bild.getWidth(), h = Math.min(bild.getHeight(), yBis);
        int gw = w / 2, gh = h / 2;
        boolean[] an = new boolean[gw * gh], besucht = new boolean[gw * gh];
        for (int gy = yAb / 2; gy < gh; gy++) {
            for (int gx = xAb / 2; gx < gw; gx++) an[gy * gw + gx] = farbe.test(bild.getRGB(gx * 2, gy * 2));
        }
        int[] stapel = new int[gw * gh];
        for (int start = 0; start < an.length; start++) {
            if (!an[start] || besucht[start]) continue;
            int sp = 0, n = 0, x0 = gw, x1 = -1, y0 = gh, y1 = -1;
            stapel[sp++] = start;
            besucht[start] = true;
            while (sp > 0) {
                int i = stapel[--sp];
                int x = i % gw, y = i / gw;
                n++;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                        int j = ny * gw + nx;
                        if (an[j] && !besucht[j]) { besucht[j] = true; stapel[sp++] = j; }
                    }
                }
            }
            int bw = (x1 - x0 + 1) * 2, bh = (y1 - y0 + 1) * 2;
            if (bw < minB || bw > maxB || bh < minH || bh > maxH) continue;
            if (n * 4.0 / ((double) bw * bh) < minFuell) continue;
            out.add(new int[]{x0 + x1 + 1, y0 + y1 + 1, bw, bh});
        }
        out.sort((a, b) -> Integer.compare(a[1], b[1]));
        return out;
    }

    /** Gruene Knoepfe (Mitte x, Mitte y, Breite, Hoehe) unterhalb der Kopfzeile. */
    static List<int[]> gruenerKnoepfe(BufferedImage bild) {
        return fleckenSuchen(bild, SpielQuestsMode::gruen, 70, 320, 28, 110, 0.55, 0, KOPF_H, Integer.MAX_VALUE);
    }

    /** Das rote Rund des Fenster-X oben rechts (Mitte x, y) oder null. */
    static int[] roterKreisObenRechts(BufferedImage bild) {
        if (bild == null) return null;
        int w = bild.getWidth(), h = bild.getHeight();
        List<int[]> f = fleckenSuchen(bild, SpielQuestsMode::rot, 28, 90, 28, 90, 0.45, (int) (w * 0.55), 0, (int) (h * 0.22));
        int[] best = null;
        for (int[] c : f) {
            if (Math.abs(c[2] - c[3]) > 14) continue;   // rund, nicht ein Streifen
            if (best == null || c[0] > best[0]) best = c;
        }
        return best == null ? null : new int[]{best[0], best[1]};
    }

    /** Die Schrift auf einem Knopf (weiss auf gruen) lesen; leer, wenn die Texterkennung nichts liefert. */
    private static String knopfText(BufferedImage bild, int[] c) {
        try {
            int x0 = Math.max(0, c[0] - c[2] / 2), y0 = Math.max(0, c[1] - c[3] / 2);
            int w = Math.min(c[2], bild.getWidth() - x0), h = Math.min(c[3], bild.getHeight() - y0);
            int rand = 8, zoom = 2;
            BufferedImage z = new BufferedImage((w + 2 * rand) * zoom, (h + 2 * rand) * zoom, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = z.createGraphics();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, z.getWidth(), z.getHeight());
            g.setColor(java.awt.Color.WHITE);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = bild.getRGB(x0 + x, y0 + y);
                    int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                    if (Math.min(r, Math.min(gg, b)) > 200) g.fillRect((x + rand) * zoom, (y + rand) * zoom, zoom, zoom);
                }
            }
            g.dispose();
            return String.join(" ", Texterkennung.lies(z)).strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Ist das ein Abhol-Knopf? "Claim"/"Reward" ja; ein Knopf mit anderem Wort (Show, Go, Open, Buy, Start, Join, Watch)
     * nein. Kommt gar kein Text (Schrift zu klein/Texterkennung fehlt): ja -- gruene Knoepfe dieser Groesse sind in diesem
     * Fenster sonst nicht zu erwarten.
     */
    static boolean istClaimText(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("claim") || t.contains("rewar") || t.contains("abhol")) return true;
        for (String nein : List.of("show", "go", "open", "buy", "start", "join", "watch", "zeig")) {
            if (t.matches(".*\\b" + nein + "\\b.*")) return false;
        }
        return t.isBlank();
    }

    /** Die Liste links als Zeilen mit Lage. Weisse Schrift; kommt nichts, helle Schrift (LIVE PRUEFEN: Farbe). */
    static List<Zeile> listeLesen(BufferedImage bild) {
        List<Zeile> z = zeilenMitLage(bild, LISTE_X, LISTE_Y, LISTE_B, LISTE_H, SpielQuestsMode::weiss);
        return z.isEmpty() ? zeilenMitLage(bild, LISTE_X, LISTE_Y, LISTE_B, LISTE_H, SpielQuestsMode::hell) : z;
    }

    /** Das Feld rechts als Textzeilen (weiss, sonst hell, sonst dunkle Schrift auf hellem Grund). */
    static List<String> rechtsLesen(BufferedImage bild) {
        List<String> erste = null;
        for (IntPredicate tinte : List.<IntPredicate>of(SpielQuestsMode::weiss, SpielQuestsMode::hell,
                SpielQuestsMode::dunkel)) {
            List<String> z = zeilenMitLage(bild, RECHTS_X, RECHTS_Y, RECHTS_B, RECHTS_H, tinte).stream()
                    .map(Zeile::text).toList();
            if (SpielQuests.fortschritt(z) != null) return z;   // "Progress x/y" gefunden: diese Farbe stimmt
            if (erste == null && !z.isEmpty()) erste = z;
        }
        return erste == null ? List.of() : erste;
    }

    /** Weisse Schrift: hell und fast farblos -- wie Texterkennung.weisseSchrift. */
    static boolean weiss(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
        return min > 190 && max - min < 45;
    }

    /** Helle, fast farblose oder leicht gelbliche Schrift (etwas weiter als weiss). */
    static boolean hell(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
        return min > 160 && max - min < 70;
    }

    /** Dunkle Schrift (braun/schwarz) auf hellem Pergament. */
    static boolean dunkel(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return Math.max(r, Math.max(g, b)) < 80;
    }

    /**
     * Jede Schriftzeile einzeln lesen (die Texterkennung liest nur eine Zeile je Bild verlaesslich), mit der Mitte
     * jeder Zeile auf der Spielflaeche, damit sie angetippt werden kann.
     */
    static List<Zeile> zeilenMitLage(BufferedImage bild, int ax, int ay, int ab, int ah, IntPredicate tinte) {
        List<Zeile> zeilen = new ArrayList<>();
        if (bild == null || bild.getWidth() < ax + 50 || bild.getHeight() < ay + 50) return zeilen;
        int w = Math.min(ab, bild.getWidth() - ax), h = Math.min(ah, bild.getHeight() - ay);
        int[] jeZeile = new int[h];
        long tintenPunkte = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) if (tinte.test(bild.getRGB(ax + x, ay + y))) jeZeile[y]++;
            tintenPunkte += jeZeile[y];
        }
        // Mehr als 40 % "Schrift" ist keine Schrift, sondern der Hintergrund (dunkle Tinte auf dunklem Fenster):
        // dann gar nicht erst lesen -- jede Zeile kostet die Texterkennung etwa eine Sekunde.
        if (tintenPunkte > (long) w * h * 2 / 5) return zeilen;
        for (int[] band : baender(jeZeile)) {
            int[] jeSpalte = new int[w];
            for (int y = band[0]; y < band[1]; y++) {
                for (int x = 0; x < w; x++) if (tinte.test(bild.getRGB(ax + x, ay + y))) jeSpalte[x]++;
            }
            int[] bereich = schriftBreite(jeSpalte);
            if (bereich == null) continue;
            int x0 = bereich[0], x1 = bereich[1];
            int rand = 8, zoom = 2, bb = x1 - x0 + 1, hh = band[1] - band[0];
            BufferedImage z = new BufferedImage((bb + 2 * rand) * zoom, (hh + 2 * rand) * zoom, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = z.createGraphics();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, z.getWidth(), z.getHeight());
            g.setColor(java.awt.Color.WHITE);
            for (int y = band[0]; y < band[1]; y++) {
                for (int x = x0; x <= x1; x++) {
                    if (tinte.test(bild.getRGB(ax + x, ay + y))) g.fillRect((x - x0 + rand) * zoom, (y - band[0] + rand) * zoom, zoom, zoom);
                }
            }
            g.dispose();
            try {
                String t = String.join(" ", Texterkennung.lies(z)).replaceAll("\\s+", " ").strip();
                if (!t.isEmpty()) zeilen.add(new Zeile(t, ax + (x0 + x1) / 2, ay + (band[0] + band[1]) / 2));
            } catch (RuntimeException e) {
                // diese Zeile nicht lesbar: weiter mit der naechsten
            }
        }
        return zeilen;
    }

    /**
     * Wo in einer Schriftzeile die Schrift steht: Spaltengruppen (Luecken ab 20 Spalten trennen), schmale Gruppen unter
     * 6 Spalten fallen weg -- das ist die helle Kante links am Fortschrittsbalken, kein Zeichen ("0/1" las sich sonst als
     * "10/1"). {von, bis (einschliesslich)} oder null.
     */
    static int[] schriftBreite(int[] jeSpalte) {
        int von = -1, bis = -1, start = -1, letzte = -100;
        for (int x = 0; x <= jeSpalte.length; x++) {
            boolean an = x < jeSpalte.length && jeSpalte[x] > 0;
            if (an) {
                if (start < 0) start = x;
                letzte = x;
            }
            if (start >= 0 && (x == jeSpalte.length || !an && x - letzte >= 20)) {
                if (letzte - start + 1 >= 6) {
                    if (von < 0) von = start;
                    bis = letzte;
                }
                start = -1;
            }
        }
        return von < 0 ? null : new int[]{von, bis};
    }

    /**
     * Schriftzeilen aus der Zahl der Schriftpunkte je Bildzeile: zusammenhaengende Bereiche mit mindestens 2 Punkten,
     * Luecken bis 2 Bildzeilen ueberbrueckt (i-Punkte), mindestens 8 hoch. {von, bis (ausschliesslich)}.
     */
    static List<int[]> baender(int[] jeZeile) {
        List<int[]> aus = new ArrayList<>();
        int start = -1, letzte = -10;
        for (int y = 0; y < jeZeile.length; y++) {
            if (jeZeile[y] >= 2) {
                if (start < 0) start = y;
                letzte = y;
            } else if (start >= 0 && y - letzte > 2) {
                if (letzte + 1 - start >= 8) aus.add(new int[]{start, letzte + 1});
                start = -1;
            }
        }
        if (start >= 0 && letzte + 1 - start >= 8) aus.add(new int[]{start, letzte + 1});
        return aus;
    }

    /** Foto fuer die Fehlersuche nach logs\spielquests, die neuesten 20 bleiben. */
    private static void foto(BotContext ctx, String art) {
        BufferedImage b = ctx.frame();
        if (b == null) return;
        try {
            Path ordner = Path.of("logs", "spielquests");
            Files.createDirectories(ordner);
            String name = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss"))
                    + "-" + art + ".png";
            javax.imageio.ImageIO.write(b, "png", ordner.resolve(name).toFile());
            try (var s = Files.list(ordner)) {
                List<Path> alle = s.filter(p -> p.toString().endsWith(".png")).sorted().toList();
                for (int i = 0; i < alle.size() - 20; i++) Files.deleteIfExists(alle.get(i));
            }
            Log.info(WO, Sprache.t("Photo: logs\\spielquests\\", "Foto: logs\\spielquests\\") + name);
        } catch (Exception e) {
            Log.debug(WO, "Foto nicht gespeichert: " + e.getMessage());
        }
    }
}
