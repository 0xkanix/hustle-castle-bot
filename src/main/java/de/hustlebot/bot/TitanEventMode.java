package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import de.hustlebot.core.Sprache;

/**
 * Titan-Event "Titanic Excavation" -- schlanke Fassung vom 09.10.2026.
 *
 * Autors Regeln (09.10.):
 *  - "der bot muss sich nur runtergraben, grade runter" -- kein Wegplanen, keine Funde, keine Werkzeuge.
 *    Drill, Super-Spitzhacke und Dynamit erst, wenn das Event vorbei ist (hier: nie).
 *  - Erst den Boss: "boss angreift mit x10 wenn es geht, wenn nicht soll er mit x1 angreifen", so lange,
 *    bis die Zahl am Knopf rot ist. Dann alle Spitzhacken verbrauchen.
 *  - Graben "wie ein Autoclicker so schnell wie moeglich" auf IMMER DIESELBE Stelle (sein rotes X). Dieselbe
 *    Stelle trifft auch den "Claim"-Knopf der Belohnungsbilder ("You have obtained", "Special reward").
 *    Die Echtgeld-Angebote haben ihren Kaufknopf woanders -- dort zu tippen kauft nichts.
 *  - Spitzhacken: am Anfang eng ablesen, dann mitzaehlen und nachsehen. Ein Tipp kostet hoechstens eine
 *    Spitzhacke, also tippt der Bot nie oefter, als laut Anzeige noch Hacken da sind.
 *
 * Alle Stellen sind fest (Fenster 1159 x 770), gemessen an Autors Bildern und der Aufnahme vom 09.10.
 * NIE: Store, Activate, Watch (Werbung), Kaufknoepfe, Diamanten.
 *
 * FREUNDE-FASSUNG (uebernommen 10.10. aus dem Hauptbot, Stand Commit e6531dd): ohne Belohnungs-Zaehler
 * (TitanBeute, !beute -- Autor 09.10.: "kein Belohnungs-Zaehler im Freunde-Bot") und ohne Spiel-Neustart
 * (den gibt es hier nicht). Alle 1000 Spitzhacken nur eine kurze Zeile mit den Zeiten im Discord-Kanal.
 */
public final class TitanEventMode implements GameMode {

    private static final String WER = "Titan-Event";

    static final String T_KALENDER         = "ev_kalender";               // Kalender-Symbol in der Burg (geteilt)
    static final String T_KALENDER_EINTRAG = "ev_titan_kalender_eintrag"; // Zeile "Titanic Excavation" im Kalender
    static final String T_VIEW             = "ev_titan_view";             // gruenes "View" im Event-Fenster des Kalenders
    static final String T_TITEL            = "ev_titan_titel";            // Schriftzug "Titanic Excavation" = im Event
    static final String T_BOSS_ANGRIFF     = "ev_titan_boss_angriff";     // "Attack the Boss!" = Reiter Boss offen
    static final String T_QUICK            = "ev_titan_quick";            // "Quick attack! X10" = Truppenfenster offen
    static final String T_VORBEI           = "ev_titan_vorbei";           // "The attack is over!"
    static final String T_CLAIM            = "ev_titan_claim";            // "Claim" unter "You have obtained" (liegt auf der Grabstelle)

    static final List<String> PFLICHT = List.of(T_KALENDER, T_KALENDER_EINTRAG, T_VIEW, T_TITEL);
    static final List<String> WICHTIG = List.of(T_BOSS_ANGRIFF, T_QUICK, T_VORBEI, T_CLAIM);

    // ------------------------------------------------ feste Stellen (Fenster 1159 x 770)
    private static final int BREITE = 1159, HOEHE = 770;
    /** Autors rotes X: der Block gerade unter dem Schacht -- und zugleich der "Claim"-Knopf. */
    private static final int[] GRABEN_HIER = {540, 642};
    private static final int[] REITER_MINE = {272, 172};
    private static final int[] REITER_BOSS = {462, 172};
    private static final int[] REITER_RANGLISTE = {846, 172};
    private static final int[] BOSS_ANGRIFF = {1009, 643};
    private static final int[] X10 = {555, 640};
    private static final int[] X1 = {745, 640};
    private static final int[] TRUPPE_ZU = {1072, 127};
    private static final int[] HOME = {580, 712};
    /** Fester Fleck fuer die Not-Bremse: Drill-Symbol links (x, y, Breite, Hoehe). */
    private static final int[] BREMS_FLECK = {45, 315, 60, 60};
    /** Rotes X im Fenster "Active events" (Kalender), gemessen in Autors Aufnahme vom 09.10. */
    private static final int[] KALENDER_X = {1089, 114};
    /** Rotes X des Events oben rechts (1123,33) -- nie als "Angebot zu" druecken. */
    private static final int[] EVENT_X = {1123, 33};
    /** Enge Kaesten fuer die Zahlen (x, y, Breite, Hoehe). */
    private static final int[] HACKEN_ZAHL = {82, 212, 108, 36};
    private static final int[] TICKET_ZAHL = {948, 553, 115, 36};
    private static final int[] X10_ZAHL = {572, 646, 66, 32};
    private static final int[] X1_ZAHL = {762, 646, 50, 32};

    /** Nur X10 (500 Tickets) -- x1 nie (Autor, 09.10.). Ausnahme: Schalter titan-x1.txt (siehe x1An). */
    private static final int TICKETS_X10 = 500;
    /** So viel kostet ein X1-Angriff (ein Zehntel von X10). */
    private static final int TICKETS_X1 = 50;

    /**
     * Autor 21.10. (letzte Event-Nacht): "Du kannst auch alle Boss-Tickets benutzen, um moeglichst viele Spitzhacken
     * rauszubekommen." Steht in titan-x1.txt "an", greift der Bot mit den restlichen Tickets (unter 500) mit X1 an,
     * solange die Zahl am X1-Knopf nicht rot ist. Ohne Datei: aus (wie bisher, nur X10).
     */
    /**
     * Autor 21.10.: "Ist die Zeit oben links ROT, sind es die letzten zwei Tage nach dem Event -- die Meter zaehlen nicht
     * mehr fuers Ranking. Dann soll der Bot alle Boss-Tickets benutzen. Sonst immer nur X10." Gemessen am Bild von 23:00:
     * rot ueber 1000 Bildpunkte, kein Weiss; in der normalen Event-Zeit weiss-gelb (Weiss ~860).
     */
    private boolean endphase;

    private void endphasePruefen(BotContext ctx) {
        if (endphase || ctx.frame() == null) return;
        if (rot(ctx, ctx.frame(), RESTZEIT)) {
            endphase = true;
            Log.info(WER, Sprache.t("The time at the top left is red -- the two days after the event: all boss tickets (X1 too) and the drills are used.",
                    "Die Zeit oben links ist rot -- die zwei Tage nach dem Event: alle Boss-Tickets (auch X1) und die Drills werden benutzt."));
        }
    }

    /** Restliche Tickets mit X1: in den zwei Tagen nach dem Event (rote Zeit) oder per Schalter titan-x1.txt. */
    private boolean alleTickets() {
        return endphase || x1An();
    }

    static boolean x1An() {
        try {
            java.nio.file.Path d = de.hustlebot.core.Ordner.bei("titan-x1.txt");
            return java.nio.file.Files.exists(d) && de.hustlebot.core.Schalterdatei.istAn(
                    de.hustlebot.core.Dateien.text(d).strip().split("\\s+")[0]);
        } catch (Exception e) {
            return false;
        }
    }
    private static final int MAX_BOSS_KLICKS = 40;
    private static final int MAX_FREMDE_BILDER = 5;

    /** Dauerklick: Tipps je Schub hoechstens, Druckdauer und Abstand (titan-klick.txt ueberschreibt). */
    private int schubMax = 1200, haltenMs = 8, pauseMs = 8;

    private enum Phase { BOSS, GRABEN, WERKZEUG, ENDE }

    /**
     * Werkzeuge links (Autor, 09.10., von Hand ausprobiert): antippen -> "Select an area" mit der Form ->
     * Form auf den aktiven Block im Schacht ziehen (Drill nicht: er nimmt gleich die ganze Spalte) ->
     * gruener Haken -> "Special reward" mit Claim (liegt auf der Grabstelle). Kosten keine Spitzhacken.
     * Bei 0 steht statt der Zahl ein gelber "Watch"-Knopf (Werbung) -- dann NIE antippen.
     */
    private enum Werkzeug {
        // Autor 18.10.: Reihenfolge nach den Spitzhacken -- erst alles Dynamit, dann alle Super-Spitzhacken, dann Bohrer.
        DYNAMIT("Dynamit", new int[]{70, 505}, new int[]{125, 500, 110, 32}, true, new int[]{742, 469}),
        SUPER("Super-Spitzhacke", new int[]{75, 425}, new int[]{125, 417, 110, 32}, true, new int[]{742, 469}),
        DRILL("Drill", new int[]{75, 345}, new int[]{125, 335, 110, 32}, false, new int[]{649, 285});

        final String name;
        final int[] symbol, zahl, haken;
        final boolean ziehen;

        Werkzeug(String name, int[] symbol, int[] zahl, boolean ziehen, int[] haken) {
            this.name = name;
            this.symbol = symbol;
            this.zahl = zahl;
            this.ziehen = ziehen;
            this.haken = haken;
        }
    }

    /** Formen der Werkzeuge (Fotoserie 21.10.): Dynamit sprengt 3 x 3, die Super-Spitzhacke ein Plus (Mitte + 4 Nachbarn). */
    static final int[][] FORM_DYNAMIT = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 0}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    static final int[][] FORM_SUPER = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /**
     * Die beste Mitte fuer eine Werkzeug-Form: unter ihr moeglichst viele HC, dann moeglichst viele zue Bloecke (bei
     * Gleichstand tiefer). Die Mitte selbst ist ein grabbarer Block am Schacht -- so nimmt das Spiel die Form sicher an.
     * @return {Zeile, Spalte, HC darunter, zue Bloecke darunter} oder null
     */
    static int[] besteFormMitte(boolean[][] offen, boolean[][] zugang, boolean[][] hc, int[][] form, java.util.Set<String> gesperrt) {
        int ze = offen.length, sp = offen[0].length;
        int[] best = null;
        long bestW = Long.MIN_VALUE;
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                if (offen[r][c] || !randFeld(zugang, r, c) || gesperrt.contains(r + ":" + c)) continue;
                int hcs = 0, zu = 0;
                for (int[] o : form) {
                    int rr = r + o[0], cc = c + o[1];
                    if (rr < 0 || rr >= ze || cc < 0 || cc >= sp || offen[rr][cc]) continue;
                    zu++;
                    if (hc[rr][cc]) hcs++;
                }
                long w = hcs * 1000L + zu * 10L + r;
                if (w > bestW) {
                    bestW = w;
                    best = new int[]{r, c, hcs, zu};
                }
            }
        return best;
    }

    /** Ziel fuer Dynamit/Super-Spitzhacke als {x, y, 1 = HC darunter / 0}, oder leer. */
    private Optional<int[]> formZiel(Mat screen, BotContext ctx, Werkzeug w) {
        boolean[][][] m = mineLesen(screen, ctx);
        if (m == null) return Optional.empty();
        int[][] form = w == Werkzeug.DYNAMIT ? FORM_DYNAMIT : FORM_SUPER;
        int[] b = besteFormMitte(m[0], zugang(m[0]), m[1], form, hcGesperrt.keySet());
        if (b == null) return Optional.empty();
        Log.info(WER, Sprache.t(w.name + ": shape onto " + b[0] + ":" + b[1] + " (" + b[2] + " HC, " + b[3] + " blocks under it).",
                w.name + ": Form auf " + b[0] + ":" + b[1] + " (" + b[2] + " HC, " + b[3] + " Bloecke darunter)."));
        return Optional.of(new int[]{px(ctx, MINE_X[b[1]]), py(ctx, MINE_Y[b[0]]), b[2] > 0 ? 1 : 0});
    }

    /** Goldenes Schloss am Werkzeug = nicht freigeschaltet (kein Pass), aus dem Foto eines Freundes (21.10.). */
    static final String T_SCHLOSS = "ev_titan_schloss";
    /** Ab dieser Uebereinstimmung ist es ein Schloss (Foto: 0,81-0,96; Autors Bilder ohne Schloss hoechstens 0,55). */
    static final double SCHLOSS_SCHWELLE = 0.65;

    /** Steht oben rechts am Symbol dieses Werkzeugs ein Schloss? (gesucht nur dort: x +5..+60, y -50..+5 vom Symbol) */
    private static boolean gesperrt(BotContext ctx, Mat screen, Werkzeug w) {
        if (screen == null || !ctx.matcher().has(T_SCHLOSS)) return false;
        int x0 = Math.max(0, px(ctx, w.symbol[0] + 5)), y0 = Math.max(0, py(ctx, w.symbol[1] - 50));
        int x1 = Math.min(screen.cols(), px(ctx, w.symbol[0] + 60)), y1 = Math.min(screen.rows(), py(ctx, w.symbol[1] + 5));
        if (x1 - x0 < 10 || y1 - y0 < 10) return false;
        Mat teil = new Mat(screen, new org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0));
        try {
            return !ctx.matcher().findAll(teil, T_SCHLOSS, SCHLOSS_SCHWELLE, 1).isEmpty();
        } finally {
            teil.release();
        }
    }

    /** Form erscheint mittig (532,438), der aktive Block im Schacht liegt bei (532,622); Haken vorher (742,285). */
    private static final int[] FORM_VON = {532, 438}, FORM_NACH = {532, 622};
    /** "Event ends in: 9d 22h 37m" oben links (x, y, Breite, Hoehe). */
    private static final int[] RESTZEIT = {5, 4, 220, 44};
    static final String T_HAKEN_OK = "ev_titan_haken_ok";   // gruener Haken im Auswahlfeld der Werkzeuge
    private int werkzeugVersuche;
    private final Map<Werkzeug, Integer> werkzeugFehl = new java.util.EnumMap<>(Werkzeug.class);
    private final java.util.EnumSet<Werkzeug> werkzeugFertig = java.util.EnumSet.noneOf(Werkzeug.class);
    /** Werkzeuge, deren Zahl in diesem Besuch schon einmal gelesen wurde (0 = ohne Pass, Autor 18.10.). */
    private final java.util.EnumSet<Werkzeug> werkzeugGezaehlt = java.util.EnumSet.noneOf(Werkzeug.class);
    private final Map<Werkzeug, Integer> werkzeugBenutzt = new java.util.EnumMap<>(Werkzeug.class);

    private Phase phase = Phase.BOSS;
    private boolean fertig;
    private String endeGrund;
    private boolean imEventZuletzt;
    private long letztesEventBildUm;
    private long wechselGefragtUm;
    private boolean draussenFuerWechsel;

    // Weg ins Event
    private long eintragGeklicktUm;
    private int kalenderVersuche, eintritte, fremdeBilder, zuege;

    // Boss
    private int bossKlicks, angriffeX10, angriffeX1, angriffOhneErgebnis;
    private long ticketStand = -1;
    private long angriffGetipptUm;
    private int ticketUnlesbar;

    // Graben
    private boolean mineGeklickt;
    private long hacken = -1, hackenAmAnfang = -1;
    /** Ziel-Tiefe (titan-ziel.txt, Autor 16.10.: "bis maximal 150.000 Meter graben, nicht weiter"), sonst -1. */
    private long tiefe = -1, tiefeBeiHacken = -1, ziel = -1;
    /** Tiefe rechts unten ("139064/139090m") -- wie im Hauptbot. */
    static final int[] TIEFE = {925, 562, 160, 32};
    private int nullLesungen, ohneWirkung, unlesbar;
    /** Wann zuletzt eine Spitzhacke verbraucht wurde (Haenger-Uhr), wie viele insgesamt, Notausgang-Stufe. */
    private long letzteHackeUm, verbraucht, tausenderMarke, tausenderUm;
    /** Wann zuletzt ein Werkzeug-Haken gedrueckt wurde (Animation laeuft danach noch ~1-2 s). */
    private long letzterHakenUm;
    private int notausgangStufe;
    private long notausgangUm;
    /** Tipps seit der letzten Lesung (fuer die Plausibilitaet). */
    private long tippsSeitLesung;
    /** Wie lange der letzte Dauerklick-Schub lief (ms) -- kurze Schuebe beweisen kein "wirkt nicht". */
    private long letzterSchubMs;
    /** Event ist zu, jetzt noch das Fenster "Active events" (Kalender) mit seinem X schliessen. */
    private boolean kalenderZu;
    private int kalenderZuVersuche;
    private long tipps, tippsSeitMarke, grabenSeit, grabenMs;
    private long markeHacken = -1, markeUm;

    private volatile java.util.function.Consumer<byte[]> ranglisteAn = null;

    @Override public String id()          { return "grabung"; }

    /**
     * Nacht 21./22.10.: 11 Berichte "kein Fortschritt seit 20 s" (Bohrer, Super-Spitzhacke, Belohnungen, Rutschen) bei
     * Autor und Mia -- die Animationen dauern so lange, gegraben wurde trotzdem. Bericht erst nach 60 s.
     */
    @Override
    public long haengerBerichtNachS() {
        return 60;
    }
    @Override public String displayName() { return Sprache.t("Titan excavation", "Titan-Grabung"); }

    @Override
    public List<String> requiredTemplates() {
        return PFLICHT;
    }

    /** Pflicht-Bilder, die noch fehlen (fuer die Karte in Discord). */
    public static List<String> fehlendePflicht(Matcher m) {
        List<String> aus = new ArrayList<>();
        for (String n : PFLICHT) if (!m.liegtBereit(n)) aus.add(n);
        return aus;
    }

    /** Empfohlene Bilder, die noch fehlen. */
    public static List<String> fehlendeWichtige(Matcher m) {
        List<String> aus = new ArrayList<>();
        for (String n : WICHTIG) if (!m.liegtBereit(n)) aus.add(n);
        return aus;
    }

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Hier laufen nur die kurzen festen Pausen nach Tipps und beim erneuten
     * Hinsehen x Faktor (grabung-tempo.txt). Der Dauerklick (Schub, Halten, Pause), alle Wartezeiten auf Animationen und
     * Anzeigen (Schlag, Mine rutscht, Boss-Angriff, Rangliste, Kalender) und die Abfrage-Schleifen bleiben, wie sie sind.
     * Runde = 1000 verbrauchte Spitzhacken (wie die Tausender-Meldung); Fehler = jede Warnung der Grabung (Notausgang,
     * Hilfe-Klick, Aufhoeren, Werkzeug-Fehlversuche ...). Die Warnungen zu titan-ziel.txt zaehlen nicht (Datei, nicht Tempo).
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("grabung-tempo.txt", WER);
    private int runFehler;
    /** Abgeschlossene Runden seit dem Start (fuer die Tempo-Zeile alle 10 Runden; nicht in reset()). */
    private int tempoRunden;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn(WER, text);
    }

    private void tempoRundeFertig() {
        tempoRunden++;
        tempo.rundeFertig(runFehler == 0);
        if (tempoRunden % 10 == 0) {
            Log.info(WER, Sprache.t("Speed after " + tempoRunden + " x 1000 pickaxes: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + tempoRunden + " x 1000 Spitzhacken: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    @Override
    public void reset() {
        phase = Phase.BOSS;
        fertig = false;
        endeGrund = null;
        imEventZuletzt = false;
        letztesEventBildUm = wechselGefragtUm = eintragGeklicktUm = 0;
        draussenFuerWechsel = false;
        kalenderVersuche = eintritte = fremdeBilder = zuege = 0;
        bossKlicks = angriffeX10 = angriffeX1 = angriffOhneErgebnis = 0;
        endphase = false;   // je Besuch neu erkennen (rote Zeit oben links)
        ticketStand = -1;
        angriffGetipptUm = 0;
        ticketUnlesbar = 0;
        mineGeklickt = false;
        hacken = hackenAmAnfang = markeHacken = -1;
        tiefe = tiefeBeiHacken = ziel = -1;
        nullLesungen = ohneWirkung = unlesbar = 0;
        tipps = tippsSeitMarke = grabenSeit = grabenMs = markeUm = 0;
        werkzeugFertig.clear();
        werkzeugGezaehlt.clear();
        werkzeugBenutzt.clear();
        grenze = naechsteGrenze;
        naechsteGrenze = 0;
        if (grenze > 0) Log.info(WER, Sprache.t("This time at most " + grenze + " pickaxes (!titan " + grenze + ").",
                "Diesmal hoechstens " + grenze + " Spitzhacken (!titan " + grenze + ")."));
        werkzeugVersuche = 0;
        hcGeholt = wegeAufgegeben = 0;   // Zaehlung HC-Graben (21.10.)
        hackenFuerHc = 0;
        hackenBeiZielStart = -1;
        wegZiel = null;
        wegZellen = null;
        werkzeugFehl.clear();
        letzterSchubMs = 0;
        hcFest = null;
        hcFestMuster = null;
        offenGemerkt = null;
        offenGesehen = null;
        hcGemerkt = null;
        hcLeer = 0;
        hcRuhigFehl = 0;
        letzteHackeUm = verbraucht = tausenderMarke = tippsSeitLesung = 0;
        tausenderUm = System.currentTimeMillis();
        notausgangStufe = 0;
        notausgangUm = 0;
        kalenderZu = false;
        kalenderZuVersuche = 0;
        klickWerteLesen();
    }

    /** titan-klick.txt (optional): "schub=400", "halten=8", "pause=8" -- zum Feinstellen ohne Neubau. */
    private void klickWerteLesen() {
        java.nio.file.Path p = java.nio.file.Path.of("titan-klick.txt");
        if (!java.nio.file.Files.exists(p)) return;
        try {
            for (String z : de.hustlebot.core.Dateien.zeilen(p)) {
                String[] t = z.trim().split("\\s*=\\s*");
                if (t.length != 2 || !t[1].matches("\\d{1,4}")) continue;
                int w = Integer.parseInt(t[1]);
                switch (t[0].toLowerCase()) {
                    case "schub" -> schubMax = Math.max(1, w);
                    case "halten" -> haltenMs = w;
                    case "pause" -> pauseMs = w;
                    default -> { }
                }
            }
            Log.info(WER, Sprache.t("Click values from titan-klick.txt: burst " + schubMax + ", hold " + haltenMs + " ms, pause " + pauseMs + " ms",
                    "Klickwerte aus titan-klick.txt: Schub " + schubMax + ", halten " + haltenMs
                    + " ms, Pause " + pauseMs + " ms"));
        } catch (Exception e) {
            Log.debug(WER, "titan-klick.txt nicht lesbar: " + e.getMessage());
        }
    }

    @Override public String endeHinweis() { return endeGrund; }

    /** "!titan 5000" (Autor, 09.10.): beim naechsten Start hoechstens so viele Spitzhacken verbrauchen (0 = alle). */
    private static volatile long naechsteGrenze = 0;
    private long grenze = 0;

    public static void grenzeFuerNaechstenStart(long hacken) {
        naechsteGrenze = Math.max(0, hacken);
    }

    /** Abgebrochen (nicht "alles verbraucht")? Dann soll der Kreislauf bald wiederkommen (Titan-Idee 13). */
    public boolean abgebrochen() {
        return endeGrund != null && endeGrund.contains("abgebrochen");
    }

    /** Ein anderer Modus darf erst anfangen, wenn das Event zu ist. */
    @Override public boolean draussen() { return !imEventZuletzt; }

    /** Der Motor fragt das, solange ein Wechsel (Clan-Krieg o. ae.) wartet: step() macht das Event dann zu. */
    @Override
    public boolean wechselJetzt() {
        wechselGefragtUm = System.currentTimeMillis();
        return draussenFuerWechsel || !imEventZuletzt;
    }

    /** "!titan rangliste": an der naechsten ruhigen Stelle Reiter Rankings oeffnen und ein Bild schicken. */
    public void ranglisteSchicken(java.util.function.Consumer<byte[]> ziel) {
        ranglisteAn = ziel;
    }

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Sprache.t("Boss attacks X10", "Boss-Angriffe X10"), String.valueOf(angriffeX10));
        if (ticketStand >= 0) m.put(Sprache.t("Tickets (last read)", "Tickets (zuletzt gelesen)"), String.valueOf(ticketStand));
        if (hackenAmAnfang >= 0) m.put(Sprache.t("Pickaxes at the start", "Spitzhacken am Anfang"), String.valueOf(hackenAmAnfang));
        if (hacken >= 0) m.put(Sprache.t("Pickaxes (last read)", "Spitzhacken (zuletzt gelesen)"), String.valueOf(hacken));
        m.put(Sprache.t("Taps on the field", "Tipps aufs Feld"), String.valueOf(tipps));
        m.put(Sprache.t("Super pickaxe / dynamite / drill", "Super-Spitzhacke / Dynamit / Drill"), werkzeugBenutzt.getOrDefault(Werkzeug.SUPER, 0) + " / "
                + werkzeugBenutzt.getOrDefault(Werkzeug.DYNAMIT, 0) + " / " + werkzeugBenutzt.getOrDefault(Werkzeug.DRILL, 0));
        long verbraucht = hackenAmAnfang >= 0 && hacken >= 0 ? hackenAmAnfang - hacken : -1;
        if (verbraucht > 0 && grabenMs > 0) {
            m.put("Tempo", String.format(java.util.Locale.GERMANY, Sprache.t("%.1f pickaxes/s", "%.1f Spitzhacken/s"), verbraucht * 1000.0 / grabenMs));
        }
        return m;
    }

    // =================================================================== step
    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        // In jedem Bild: sonst haelt Stoerfenster ein gruenes "View" fuer das Fenster "Active events".
        Stoerfenster.eventUnterwegs();
        if (fertig) {
            Log.info(WER, "Fertig. " + bericht());
            // Freunde: kein Spiel-Neustart nach dem Besuch (gibt es in dieser Fassung nicht).
            return Outcome.FINISHED;
        }
        long jetzt = System.currentTimeMillis();
        // Autor 18.10.: "am besten soll der Bot gar nicht stecken bleiben" -- 30 s ohne Fortschritt: selbst helfen
        // (rotes X eines Auswahlfensters, sonst Event schliessen -- danach oeffnet der Ablauf es neu).
        if (runs != null && runs.idleSeconds() >= 30 && jetzt - selbsthilfeUm > 30_000) {
            selbsthilfeUm = jetzt;
            warnung(Sprache.t("No progress for " + runs.idleSeconds() + " s -- helping myself (red X / close the event).",
                    "Seit " + runs.idleSeconds() + " s kein Fortschritt -- hilft sich selbst (rotes X / Event schliessen)."));
            if (ctx.matcher().has(T_FORM_X) && ctx.clickIfVisibleIfKnown(screen, T_FORM_X, Sprache.t("Self-help: red X", "Selbsthilfe: rotes X"))) {
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
            if (ctx.clickIfVisibleIfKnown(screen, "close_x", Sprache.t("Self-help: close the event", "Selbsthilfe: Event schliessen"))) {
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
        }
        boolean titel = sicht(screen, ctx, T_TITEL);
        // ERST das Ergebnisbild: 09.10., 13:03 passte das abgedunkelte Truppenfenster dahinter noch auf T_QUICK,
        // und der Bot tippte 110 Mal ins Leere auf X10 (Vorlagenvergleich ist helligkeitsunabhaengig).
        boolean vorbei = !titel && sicht(screen, ctx, T_VORBEI);
        boolean truppe = !titel && !vorbei && sicht(screen, ctx, T_QUICK);
        // "You have obtained" (Funde, Special reward): Claim sofort -- 09.10. hing der Bot hier 2 Minuten, weil
        // dieses Bild nach 6 s nicht mehr als "im Event" galt.
        Optional<Matcher.Hit> claim = !titel && !truppe && !vorbei && ctx.matcher().has(T_CLAIM)
                ? ctx.find(screen, T_CLAIM) : Optional.empty();
        boolean imEvent = titel || truppe || vorbei || claim.isPresent();
        imEventZuletzt = imEvent;
        if (imEvent) {
            letztesEventBildUm = jetzt;
            fremdeBilder = 0;
        }

        // Moduswechsel gewuenscht: Event an der naechsten Stelle zumachen.
        if (jetzt - wechselGefragtUm < 3000) {
            if (!imEvent) {
                draussenFuerWechsel = true;
                return Outcome.WAITING;
            }
            return eventZumachen(screen, ctx, truppe, vorbei, "Moduswechsel");
        }
        draussenFuerWechsel = false;

        if (claim.isPresent()) {
            runs.progress("Belohnung");
            Log.action("Klick", Sprache.t(WER + ": accept the reward (Claim)", WER + ": Belohnung annehmen (Claim)"));
            ctx.input().tippeSofort(claim.get().centerX(), claim.get().centerY());
            ctx.sleep(tempo.ms(150));
            return Outcome.ACTED;
        }
        if (vorbei) {
            runs.progress("Boss-Ergebnis");
            angriffOhneErgebnis = 0;
            angriffGetipptUm = 0;
            tippe(ctx, HOME, Sprache.t("Boss: attack over -- Home", "Boss: Angriff vorbei -- Home"));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (truppe) return angreifen(ctx, runs);
        if (kalenderZu && !titel) return kalenderSchliessen(screen, ctx);
        if (!titel) return draussen(screen, ctx, runs);

        return switch (phase) {
            case BOSS -> bossSeite(screen, ctx, runs);
            case GRABEN -> graben(screen, ctx, runs);
            case WERKZEUG -> werkzeugSchritt(screen, ctx, runs);
            // Autor, 10.10.: "ganz am Ende ... ein Bild von Top 25" -- ein angefordertes Ranking-Bild auch nach den
            // Werkzeugen noch machen, bevor das Event zugeht (bisher nur in der Grabphase, danach ging es verloren).
            case ENDE -> {
                // Rangliste um 0 und 12 Uhr (Autor, 10.10.): am Ende des Besuchs, nach Spitzhacken, Super-Spitzhacken
                // und Dynamit -- dann stimmen die Meter. Vorher vermerken, damit es nicht in einer Schleife laeuft.
                if (ranglisteAn == null && tagesRanglisteFaellig()) {
                    tagesRanglisteErledigt();
                    Log.info(WER, Sprache.t("Rankings (0/12 o'clock, end of the visit): image with rank 25 for Discord.",
                            "Rangliste (0/12 Uhr, Ende des Besuchs): Bild mit Platz 25 fuer Discord."));
                    ranglisteAn = tagesZiel;
                }
                yield ranglisteAn != null ? ranglisteZeigen(ctx) : eventZumachen(screen, ctx, false, false, Sprache.t("all done", "alles erledigt"));
            }
        };
    }

    // =================================================================== Boss
    /** Reiter Boss: Tickets lesen; reichen sie fuer einen Angriff, "Attack the Boss!". */
    private Outcome bossSeite(Mat screen, BotContext ctx, RunTracker runs) {
        if (++bossKlicks > MAX_BOSS_KLICKS) {
            warnung(Sprache.t("Boss: no end after " + MAX_BOSS_KLICKS + " steps -- digging now.",
                    "Boss: nach " + MAX_BOSS_KLICKS + " Schritten kein Ende -- jetzt graben."));
            return zumGraben(ctx);
        }
        if (!sicht(screen, ctx, T_BOSS_ANGRIFF)) {
            tippe(ctx, REITER_BOSS, Sprache.t("Boss tab", "Reiter Boss"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        OptionalLong t = zahlLesen(ctx.frame(), px(ctx, TICKET_ZAHL[0]), py(ctx, TICKET_ZAHL[1]),
                px(ctx, TICKET_ZAHL[2]), py(ctx, TICKET_ZAHL[3]), ticketStand);
        if (t.isPresent()) {
            ticketStand = t.getAsLong();
            endphasePruefen(ctx);
            if (ticketStand < TICKETS_X10 && !(alleTickets() && ticketStand >= TICKETS_X1)) {
                Log.info(WER, Sprache.t("Boss: " + ticketStand + " tickets -- not enough for X10 (" + TICKETS_X10 + "). Digging now, X10 on the next visit.",
                        "Boss: " + ticketStand + " Tickets -- reicht nicht fuer X10 (" + TICKETS_X10
                        + "). Jetzt graben, X10 beim naechsten Besuch."));
                return zumGraben(ctx);
            }
        } else if (++ticketUnlesbar >= 2) {
            // Autor, 09.10. (Platz 7): ohne sichere Ticketzahl kein Angriff -- lieber graben.
            warnung(Sprache.t("Boss: ticket count not readable twice -- no attack, digging now.",
                    "Boss: Ticketzahl zweimal nicht lesbar -- kein Angriff, jetzt graben."));
            foto(ctx, "tickets-unlesbar");
            return zumGraben(ctx);
        } else {
            ctx.sleep(tempo.ms(200));
            return Outcome.WAITING;
        }
        if (angriffOhneErgebnis >= 3) {
            warnung(Sprache.t("Boss: 3 attacks without a result screen -- digging now.",
                    "Boss: 3 Angriffe ohne Ergebnisbild -- jetzt graben."));
            foto(ctx, "boss-ohne-ergebnis");
            return zumGraben(ctx);
        }
        tippe(ctx, BOSS_ANGRIFF, "Attack the Boss! (" + (ticketStand >= 0 ? ticketStand + " Tickets" : Sprache.t("tickets unreadable", "Tickets unlesbar")) + ")");
        runs.progress("Boss");
        ctx.sleepUntilChanged(1500);
        return Outcome.ACTED;
    }

    /**
     * Truppenfenster: NUR X10 (Autor, 09.10.: "x1 soll er bitte nie andruecken" -- reichen die Tickets nicht,
     * graben und beim naechsten Besuch X10). Angegriffen wird nur, wenn die Zahl am Knopf nicht rot ist UND laut
     * Anzeige mindestens 500 Tickets da sind. Nach einem Tipp wird auf "The attack is over!" gewartet, nie
     * nachgetippt.
     */
    private Outcome angreifen(BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        if (angriffGetipptUm > 0 && jetzt - angriffGetipptUm < 6000) {
            ctx.sleep(150);   // Angriff laeuft -- auf das Ergebnisbild warten
            return Outcome.WAITING;
        }
        if (angriffGetipptUm > 0) {
            angriffGetipptUm = 0;
            if (++angriffOhneErgebnis >= 2) {
                warnung(Sprache.t("Boss: no result screen after X10 twice -- closing the squad window, digging now.",
                        "Boss: zweimal kein Ergebnisbild nach X10 -- Truppenfenster zu, jetzt graben."));
                foto(ctx, "boss-ohne-ergebnis");
                tippe(ctx, TRUPPE_ZU, Sprache.t("Close the squad window", "Truppenfenster zu"));
                zumGraben(ctx);
                return Outcome.ACTED;
            }
        }
        boolean x10rot = rot(ctx, ctx.frame(), X10_ZAHL);
        // Autor 21.10.: Reichen die Tickets nicht fuer X10, mit X1 weiter (Schalter titan-x1.txt), solange X1 nicht rot ist.
        if (phase == Phase.BOSS && (x10rot || ticketStand < TICKETS_X10) && alleTickets() && ticketStand >= TICKETS_X1
                && !rot(ctx, ctx.frame(), X1_ZAHL)) {
            angriffeX1++;
            ticketStand -= TICKETS_X1;
            angriffGetipptUm = jetzt;
            tippe(ctx, X1, "Boss: Quick attack X1 (" + angriffeX1 + ".)");
            runs.progress("Boss-Angriff");
            return Outcome.ACTED;
        }
        if (phase != Phase.BOSS || x10rot || ticketStand < TICKETS_X10) {
            Log.info(WER, Sprache.t("Boss: " + (phase != Phase.BOSS ? "done" : x10rot ? "X10 red" : ticketStand + " tickets") + " -- closing the squad window, digging now (" + angriffeX10 + "x X10).",
                    "Boss: " + (phase != Phase.BOSS ? "erledigt" : x10rot ? "X10 rot" : ticketStand + " Tickets")
                    + " -- Truppenfenster zu, jetzt graben (" + angriffeX10 + "x X10)."));
            tippe(ctx, TRUPPE_ZU, Sprache.t("Close the squad window", "Truppenfenster zu"));
            zumGraben(ctx);
            ctx.sleepUntilChanged(800);
            return Outcome.ACTED;
        }
        angriffeX10++;
        ticketStand -= TICKETS_X10;
        angriffGetipptUm = jetzt;
        tippe(ctx, X10, "Boss: Quick attack X10 (" + angriffeX10 + ".)");
        runs.progress("Boss-Angriff");
        return Outcome.ACTED;
    }

    private Outcome zumGraben(BotContext ctx) {
        phase = Phase.GRABEN;
        mineGeklickt = false;
        return Outcome.ACTED;
    }

    // ================================================================= Graben
    private Outcome graben(Mat screen, BotContext ctx, RunTracker runs) {
        if (!mineGeklickt || sicht(screen, ctx, T_BOSS_ANGRIFF)) {
            mineGeklickt = true;
            tippe(ctx, REITER_MINE, Sprache.t("Mine tab", "Reiter Mine"));
            ctx.sleepUntilChanged(1000);
            return Outcome.ACTED;
        }
        if (ranglisteAn != null) return ranglisteZeigen(ctx);

        OptionalLong z = zahlLesen(ctx.frame(), px(ctx, HACKEN_ZAHL[0]), py(ctx, HACKEN_ZAHL[1]),
                px(ctx, HACKEN_ZAHL[2]), py(ctx, HACKEN_ZAHL[3]), hacken);
        long jetztMs = System.currentTimeMillis();
        if (z.isPresent() && !plausibel(z.getAsLong())) {
            // Titan-Idee 9: "124" statt "1234" nicht glauben -- so schnell kann die Zahl nicht sinken.
            Log.debug(WER, "Spitzhacken-Zahl " + z.getAsLong() + " unglaubwuerdig (vorher " + hacken + ") -- neu lesen.");
            z = OptionalLong.empty();
        }
        if (z.isPresent() && z.getAsLong() <= 99_999) {
            long neu = z.getAsLong();
            if (letzteHackeUm == 0) letzteHackeUm = jetztMs;
            if (hackenAmAnfang < 0) {
                restLesen(ctx);   // Event-Ende merken (Endspurt der Kathedrale)
                hackenAmAnfang = neu;
                markeHacken = neu;
                markeUm = System.currentTimeMillis();
                Log.info(WER, Sprache.t("Digging: " + neu + " pickaxes -- continuous click straight down.",
                        "Graben: " + neu + " Spitzhacken -- Dauerklick gerade runter."));
                Werkzeug nur = nurWerkzeug();
                if (nur != null) {
                    // Autor 18.10.: Testrunden "nur Dynamit / nur Super-Spitzhacke / nur Bohrer" (titan-nur.txt).
                    Log.info(WER, "Nur " + nur.name + " (titan-nur.txt) -- keine Spitzhacken.");
                    phase = Phase.WERKZEUG;
                    return Outcome.ACTED;
                }
                ziel = zielLesen();
                if (ziel > 0) {
                    tiefe = tiefeSicher(ctx);
                    tiefeBeiHacken = neu;
                    Log.info(WER, Sprache.t("Target depth " + ziel + " m -- now " + (tiefe < 0 ? "?" : tiefe) + " m.",
                            "Ziel-Tiefe " + ziel + " m -- jetzt " + (tiefe < 0 ? "?" : tiefe) + " m."));
                }
            }
            // 09.10.: bei 5 Hacken liefen 5 Tipps in 0,15 s -- schneller als EINE Schlag-Animation (0,8 s), die
            // Zahl blieb stehen und der Bot hoerte auf. Als "wirkt nicht" zaehlt nur ein Schub von >= 1,5 s.
            if (hacken >= 0 && neu < hacken) {
                ohneWirkung = 0;
                notausgangStufe = 0;
                letzteHackeUm = jetztMs;
                verbraucht += hacken - neu;
                runs.progress("graben");
            } else if (hacken >= 0 && neu > hacken) {
                ohneWirkung = 0;   // Zahl gestiegen (Fund/Belohnung) -- kein Haenger (Titan-Idee 5)
            } else if (hacken >= 0 && letzterSchubMs >= 1500) {
                ohneWirkung++;
            }
            hacken = neu;
            unlesbar = 0;
            tippsSeitLesung = 0;
            // Freunde: keine Tiefe alle 100 Hacken -- sie war nur fuer die Beute-Auswertung (!beute) da.
            tempoMessen();
            tausenderMelden();
        } else if (++unlesbar >= 20) {
            foto(ctx, "hacken-unlesbar");
            return aufhoeren(Sprache.t("pickaxe count not readable 20 times", "Spitzhacken-Zahl 20 Mal nicht lesbar"));
        } else if (unlesbar >= 6) {
            return notausgang(screen, ctx, Sprache.t("pickaxe count not readable " + unlesbar + " times",
                    "Spitzhacken-Zahl " + unlesbar + " Mal nicht lesbar"));
        }
        // Haenger-Uhr (Titan-Idee 5): 90 s ohne verbrauchte Hacke -> Notausgang-Leiter, 5 min -> aufhoeren.
        // Freunde: ohne Spiel-Neustart -- der Besuch endet "abgebrochen", der Kreislauf kommt bald wieder.
        if (letzteHackeUm > 0 && jetztMs - letzteHackeUm > 300_000) {
            foto(ctx, "graben-5-min");
            warnung(Sprache.t("No pickaxe used for 5 minutes -- the excavation stops here.",
                    "5 Minuten keine Spitzhacke verbraucht -- die Grabung hoert hier auf."));
            return aufhoeren(Sprache.t("5 minutes without progress", "5 Minuten ohne Fortschritt"));
        }
        if (hacken != 0 && letzteHackeUm > 0 && jetztMs - letzteHackeUm > 90_000) {
            return notausgang(screen, ctx, Sprache.t("no pickaxe used for 90 s", "90 s keine Spitzhacke verbraucht"));
        }

        // Ziel-Tiefe: weit weg alle ~100 Hacken lesen, ab 1500 m vor dem Ziel nach jedem Schub. Erreicht = Schluss,
        // auch bei spaeteren Besuchen (der Kreislauf kommt wieder, liest die Tiefe und hoert gleich auf).
        if (ziel > 0) {
            boolean lesen = tiefe < 0 || ziel - tiefe <= 1500 || (tiefeBeiHacken >= 0 && hacken >= 0 && tiefeBeiHacken - hacken >= 100);
            if (lesen) {
                long w = tiefeLesenWert(ctx);
                if (w > 0) {
                    tiefe = w;
                    tiefeBeiHacken = hacken;
                }
            }
            if (tiefe >= ziel) {
                Log.info(WER, Sprache.t("Target depth reached: " + tiefe + " m (target " + ziel + " m) -- no more digging. " + bericht(),
                        "Ziel-Tiefe erreicht: " + tiefe + " m (Ziel " + ziel + " m) -- kein Graben mehr. " + bericht()));
                runs.completeRun();
                endeGrund = Sprache.t("Titan excavation: target depth " + ziel + " m reached (" + tiefe + " m).",
                        "Titan-Grabung: Ziel-Tiefe " + ziel + " m erreicht (" + tiefe + " m).");
                phase = Phase.ENDE;
                return Outcome.ACTED;
            }
        }
        if (grenze > 0 && verbraucht >= grenze) {
            Log.info(WER, Sprache.t("Limit reached: " + verbraucht + " of " + grenze + " pickaxes used. " + bericht(),
                    "Grenze erreicht: " + verbraucht + " von " + grenze + " Spitzhacken verbraucht. " + bericht()));
            runs.completeRun();
            endeGrund = Sprache.t("Titan excavation: " + verbraucht + " pickaxes used (limit " + grenze + ").", "Titan-Grabung: " + verbraucht + " Spitzhacken verbraucht (Grenze " + grenze + ").");
            phase = Phase.ENDE;
            return Outcome.ACTED;
        }
        if (hacken == 0) {
            if (++nullLesungen >= 2) {
                Log.info(WER, Sprache.t("Out of pickaxes. " + bericht(), "Spitzhacken alle. " + bericht()));
                runs.completeRun();
                // Autor, 09.10.: danach jedes Mal Super-Spitzhacken und Dynamit verbrauchen; den Drill nur
                // auf seine Ansage kurz vor Event-Ende (titan-drill.txt "an") -- er dauert zu lange.
                phase = Phase.WERKZEUG;
                return Outcome.ACTED;
            }
            ctx.sleep(250);   // Anzeige zieht vielleicht noch nach
            return Outcome.WAITING;
        }
        nullLesungen = 0;
        if (ohneWirkung >= 2) {
            // Gleich nach zwei Schueben ohne Wirkung nachsehen (Titan-Idee 5), nicht erst nach ~1,5 min.
            foto(ctx, "graben-ohne-wirkung");
            if (ohneWirkung >= 8) return aufhoeren(Sprache.t("pickaxe count does not change despite tapping", "Spitzhacken-Zahl bleibt trotz Tippen stehen"));
            tippe(ctx, REITER_MINE, Sprache.t("Digging has no effect -- Mine tab once more", "Graben wirkt nicht -- Reiter Mine noch einmal"));
            ctx.sleepUntilChanged(1000);
            return Outcome.ACTED;
        }

        // Volles Tempo bis zum Schluss (Autor, 09.10.: "schneller, schneller" -- blind weitertippen ist sicher,
        // das Angebot bei 0 Hacken hat seinen Kaufknopf woanders und geht mit dem roten X zu). Am Ende kuerzere
        // Schuebe, damit die 0 schnell auffaellt.
        // Lange Schuebe = seltener anhalten zum Ablesen (~0,15 s je Lesung): je Hacke ~26 Tipps (32 Tipps/s bei
        // 1,22 Hacken/s), so viele, dass vor dem Ende sicher noch 30 Hacken uebrig sind; hoechstens schubMax.
        int schub = hacken < 0 ? 40 : hacken <= 50 ? 60 : (int) Math.min(schubMax, Math.max(60, (hacken - 30) * 26));
        if (grenze > 0) schub = (int) Math.max(30, Math.min(schub, (grenze - verbraucht) * 26L));   // nicht weit ueber die Grenze
        boolean fein = ziel > 0 && tiefe >= 0 && ziel - tiefe <= 150;
        if (fein) schub = 8;                                                    // ~eine Spitzhacke, dann Meter lesen
        else if (ziel > 0 && tiefe >= 0 && ziel - tiefe <= 1500) schub = Math.min(schub, 26 * 20);   // ~20 Hacken je Schub
        long vorher = System.currentTimeMillis();
        if (grabenSeit == 0) grabenSeit = vorher;
        // Not-Bremse (Titan-Idee 6): alle 40 Tipps die Helligkeit eines festen Flecks (Drill-Symbol links)
        // pruefen -- legt sich ein Fenster (Angebot, Burg-Angriff) darueber, wird es dunkler: sofort
        // aufhoeren statt bis zu 37 s weiterzutippen. Kostet ~2 ms je Pruefung.
        int[] f = {px(ctx, BREMS_FLECK[0]), py(ctx, BREMS_FLECK[1]), px(ctx, BREMS_FLECK[2]), py(ctx, BREMS_FLECK[3])};
        double hell0 = ctx.input().helligkeit(f[0], f[1], f[2], f[3]);
        java.util.function.BooleanSupplier weiter = () -> !BotEngine.pausiert() && !BotEngine.stoppAngefordert()
                && (hell0 <= 0 || ctx.input().helligkeit(f[0], f[1], f[2], f[3]) >= hell0 * 0.70);
        // Hauptbot 17.10. (Autor: "alle HC-Belohnungen mitnehmen, nicht nur grade nach unten bauen"): ist ein HC-Feld zu
        // sehen und erreichbar, ein kurzer Schub (~1 Schlag) auf das naechste Feld des kuerzesten Wegs dorthin; sonst auf
        // das tiefste erreichbare Feld -- und danach wieder schauen. Schalter: "!titan hc on|off" (titan-hc.txt).
        int zielX = px(ctx, GRABEN_HIER[0]), zielY = py(ctx, GRABEN_HIER[1]);
        java.util.function.BooleanSupplier weiterHier = weiter;
        int pruefAlle = 40;
        if (hcAn()) {
            Optional<int[]> hz = hcZiel(screen, ctx, true);
            if (hz.isPresent() && hcFestMuster != null && hcRuhigFehl < 3) {
                // Autor 18.10.: "angeknackste Bloecke erst gar nicht anknacken, wenn sie nichts bringen" -- rutscht die
                // Mine gerade, stimmt die Stelle nicht mehr. Frisches Bild: anders als beim Ausrechnen = neu schauen.
                Mat frisch = ctx.refresh();
                boolean[][][] mineJetzt = frisch == null ? null : mineLesen(frisch, ctx);
                // Runde 2 (18.10.): 58x in 3 min, meist weil gerade ein Nachbar aufging. Nur echtes Rutschen zaehlt: ein
                // offenes Feld ist ploetzlich zu, oder das Ziel selbst ist schon offen.
                boolean bewegt = mineJetzt == null || hcFest == null;
                boolean zielOffen = false;
                if (!bewegt) {
                    zielOffen = mineJetzt[0][hcFest[0]][hcFest[1]];
                    bewegt = zielOffen;
                    for (int r = 0; r < hcFestMuster.length && !bewegt; r++)
                        for (int c = 0; c < hcFestMuster[r].length; c++)
                            if (hcFestMuster[r][c] && !mineJetzt[0][r][c]) { bewegt = true; break; }
                }
                if (bewegt) {
                    hcRuhigFehl++;
                    letzterSchubMs = 0;   // kein Fehlversuch fuer "Feld geht nicht auf"
                    Log.debug(WER, "HC-Graben: Mine bewegt sich -- erst neu schauen.");
                    // 21.10. (Test, 22:28): 0,1 s nach dem Rutschen geplant -- zweimal "Cannot be used on inaccessible
                    // blocks". Das Bild stammte noch aus dem Rutschen. Erst warten, bis die Mine stillsteht, und die
                    // gemerkten offenen Felder vergessen (sie stehen jetzt woanders). Nur beim echten Rutschen -- ist bloss
                    // das Ziel aufgegangen, gleich weiter.
                    if (!zielOffen) {
                        offenGemerkt = null;
                        ctx.sleep(600);
                    }
                    return Outcome.ACTED;
                }
            }
            hcRuhigFehl = 0;
            if (hz.isPresent()) {
                zielX = hz.get()[0];
                zielY = hz.get()[1];
                // Autor 17.10., 19:20: "schneller". Bis 120 Tipps (1,9 s) auf dasselbe Feld -- ein Stein braucht mehrere
                // Schlaege, dann ohne Pause weiter --, aber sofort Schluss, sobald sich das Feld sichtbar aendert (offen).
                // Das Spiel nimmt nur ~alle 0,82 s einen Schlag an: das naechste Ziel wird in dieser Zeit gesucht.
                if (!fein) schub = 120;
                int b = px(ctx, 44), h = py(ctx, 44);
                int fx = zielX - b / 2, fy = zielY - h / 2;
                double feld0 = ctx.input().helligkeit(fx, fy, b, h);
                // Autor 18.10.: rutscht die Mine weiter, aendert sich auch das offene Nachbarfeld -- dann sofort neu
                // schauen statt bis 1,9 s auf einen jetzt unerreichbaren Block zu tippen.
                int nx = hz.get().length > 4 ? hz.get()[3] - b / 2 : -1, ny = hz.get().length > 4 ? hz.get()[4] - h / 2 : -1;
                double nach0 = nx >= 0 ? ctx.input().helligkeit(nx, ny, b, h) : -1;
                weiterHier = () -> weiter.getAsBoolean()
                        && (feld0 <= 0 || Math.abs(ctx.input().helligkeit(fx, fy, b, h) - feld0) <= feld0 * 0.18)
                        && (nach0 <= 0 || Math.abs(ctx.input().helligkeit(nx, ny, b, h) - nach0) <= Math.max(14, nach0 * 0.25));
                pruefAlle = 6;
            } else {
                // Autor 18.10., 20:07: ohne Ziel tippte der Bot auf die alte feste Stelle unten in Spalte 3 -- dort lag ein
                // unerreichbarer Block (12 s ohne Wirkung). Autor 21.10.: "Bloecke, die du nicht anfangen musst, gar nicht
                // erst anfangen" -- ohne Ziel wird NIE mehr blind getippt, nur neu geschaut.
                if (++hcLeer % 40 == 0) Log.info(WER, Sprache.t("HC digging: no target for a while -- looking again.",
                        "HC-Graben: seit einer Weile kein Ziel -- schaue neu."));
                ctx.sleep(tempo.ms(hcLeer < 10 ? 150 : 400));
                return Outcome.ACTED;
            }
            if (hz.isPresent()) hcLeer = 0;
        }
        int n = ctx.input().dauerklick(zielX, zielY, schub, haltenMs, pauseMs, weiterHier, pruefAlle);
        if (fein) ctx.sleep(900);   // Schlag und Meter-Anzeige abwarten, bevor gelesen wird
        letzterSchubMs = System.currentTimeMillis() - vorher;
        grabenMs += letzterSchubMs;
        tipps += n;
        tippsSeitMarke += n;
        tippsSeitLesung += n;
        // KEIN runs.progress hier: "Fortschritt" nur, wenn die Spitzhacken-Zahl wirklich sinkt (oben) -- sonst
        // greift weder der Haenger-Alarm (2 min) noch der Totmann-Schalter (Titan-Idee 2, 09.10.).
        return Outcome.ACTED;
    }

    /** Ziel-Tiefe aus titan-ziel.txt im Bot-Ordner (eine Zahl, z. B. 150000), sonst -1. */
    public static long zielLesen() {
        java.nio.file.Path p = de.hustlebot.core.Ordner.bei("titan-ziel.txt");
        try {
            if (!java.nio.file.Files.exists(p)) return -1;
            // Code-Pruefung 16.10.: readAllLines (UTF-8) warf bei ANSI-Umlauten -- dann grub der Bot still ohne Ziel.
            String text = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1)
                    .replace("\u00ef\u00bb\u00bf", "").replace("\ufeff", "");
            for (String z : text.split("\\R")) {
                String s = z.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.matches("[0-9][0-9 .,]{0,12}")) {
                    String ziffern = s.replaceAll("[^0-9]", "");
                    if (ziffern.length() <= 9) return Long.parseLong(ziffern);
                }
                break;
            }
        } catch (Exception e) {
            Log.warn(WER, Sprache.t("titan-ziel.txt not readable: " + e.getMessage(), "titan-ziel.txt nicht lesbar: " + e.getMessage()));
        }
        Log.warn(WER, Sprache.t("titan-ziel.txt has no valid target depth -- digging WITHOUT a target.",
                "titan-ziel.txt hat keine gueltige Ziel-Tiefe -- es wird OHNE Ziel gegraben."));
        return -1;
    }

    /** Tiefe rechts unten lesen (erste Zahl vor dem "/"). -1, wenn unlesbar oder unglaubwuerdig. */
    private long tiefeLesenWert(BotContext ctx) {
        ctx.refresh();
        String text = ArenaLeser.liesTextGross(ctx.frame(), px(ctx, TIEFE[0]), py(ctx, TIEFE[1]), px(ctx, TIEFE[2]), py(ctx, TIEFE[3]));
        long w = tiefeAusText(text);
        if (w < 0) {
            Log.debug(WER, "Tiefe nicht lesbar: " + text);
            return -1;
        }
        try {
            // Rueckwaerts oder ein Riesensprung = Lesefehler (die Erkennung las schon "13030Sm").
            if (tiefe > 0 && (w < tiefe || w > tiefe + 3000)) {
                Log.debug(WER, "Tiefe " + w + " unglaubwuerdig (vorher " + tiefe + ").");
                return -1;
            }
            Log.debug(WER, "Tiefe: " + w + " m (gelesen: " + (text == null ? "" : text.strip()) + ")");
            return w;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Erste Zahl vor dem "/" (die Tiefe), -1 wenn keine. Ist sie laenger und groesser als die Zahl dahinter, hat die
     * Erkennung vorn ein Zeichen zu viel gelesen (Autor 18.10.: 1151632 statt 151632 -- der Bot hielt das Ziel 150000
     * fuer erreicht) -- dann wird vorn gekuerzt.
     */
    static long tiefeAusText(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d[\\d .,]{0,8})\\s*/\\s*(\\d[\\d .,]{0,8})?")
                .matcher(text == null ? "" : text);
        if (!m.find()) return -1;
        String a = m.group(1).replaceAll("[^0-9]", "");
        String b = m.group(2) == null ? "" : m.group(2).replaceAll("[^0-9]", "");
        if (a.isEmpty() || a.length() > 9) return -1;
        if (!b.isEmpty() && b.length() <= 9) {
            long grenze = Long.parseLong(b);
            // Nur genau ein Zeichen zu viel, und nur wenn es danach passt -- fehlt hinten eine Ziffer ("151632/15165m"),
            // bleibt die Tiefe, wie sie ist.
            if (a.length() == b.length() + 1 && Long.parseLong(a) > grenze && Long.parseLong(a.substring(1)) <= grenze) {
                a = a.substring(1);
            }
        }
        return Long.parseLong(a);
    }

    /** Fuer den Start: erst gueltig, wenn zweimal dieselbe Tiefe gelesen wurde. */
    private long tiefeSicher(BotContext ctx) {
        java.util.Map<Long, Integer> gesehen = new java.util.HashMap<>();
        for (int i = 0; i < 6; i++) {
            if (i > 0) ctx.sleep(300);
            long w = tiefeLesenWert(ctx);
            if (w > 0 && gesehen.merge(w, 1, Integer::sum) >= 2) return w;
        }
        return -1;
    }

    // ============================================================= HC-Graben (Hauptbot 17.10.)
    /** Mitten der 6 Spalten und 5 Zeilen der Mine (gemessen an Autors Fotoserie 17.10., 1159x770). */
    private static final int[] MINE_X = {346, 439, 532, 626, 719, 812};
    private static final int[] MINE_Y = {261, 351, 441, 531, 610};
    private static final String T_HC_BUNT = "ev_titan_hc_bunt", T_HC_BRONZE = "ev_titan_hc_bronze", T_FORM_X = "ev_form_x";
    /** Felder, die trotz Tippen nicht aufgingen ("Cannot be used on inaccessible blocks") -- kurz meiden. */
    private final Map<String, Long> hcGesperrt = new HashMap<>();
    private String hcLetztesZiel = "", hcGemeldet = "";
    private long hcHackenVorher = -1;
    private int hcGleichesZiel;

    /** Schalter titan-hc.txt: Standard an (Hauptbot 17.10.), "aus" = wie frueher nur gerade runter. */
    private static boolean hcAn() {
        try {
            java.nio.file.Path d = java.nio.file.Path.of("titan-hc.txt");
            return !java.nio.file.Files.exists(d) || !de.hustlebot.core.Dateien.text(d).trim().toLowerCase().startsWith("aus");
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Die 6 x 5 Felder lesen: {offen, hc}, oder null (kein Bild / kein offenes Feld = gerade nicht die Mine). Offen =
     * gegraben (dunkel, fast farblos) oder eingesammelt (gruener Haken unten rechts). Gemessen an Autors Fotos: offen
     * Helligkeit 70 / Spanne 19, Erde Spanne ~100, Stein Spanne 12 bei Helligkeit 112-162, Haken 69-125 gruene Punkte.
     */
    private boolean[][][] mineLesen(Mat screen, BotContext ctx) {
        BufferedImage b = ctx.frame();
        if (b == null || screen == null) return null;
        int sp = MINE_X.length, ze = MINE_Y.length;
        boolean[][] offen = new boolean[ze][sp];
        boolean[][] hc = new boolean[ze][sp];
        int[][] kosten = new int[ze][sp];
        int[][][] farbe = new int[ze][sp][];
        // Autor 18.10.: Farbe am RAND des Felds (Ring um das Symbol) -- HC- und Belohnungssymbole in der Mitte machten
        // aus einem braunen Block einen "unbekannten" (teuren), und ein offenes Feld mit frischer Belohnung sah "zu" aus.
        int ring = px(ctx, 36);
        boolean irgendOffen = false;
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                int cx = px(ctx, MINE_X[c]), cy = py(ctx, MINE_Y[r]);
                long sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -ring; k <= ring; k += 3) {
                    int[][] punkte = {{cx + k, cy - ring}, {cx + k, cy + ring}, {cx - ring, cy + k}, {cx + ring, cy + k}};
                    for (int[] q : punkte) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= b.getWidth() || q[1] >= b.getHeight()) continue;
                        int p = b.getRGB(q[0], q[1]);
                        sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; n++;
                    }
                }
                if (n == 0) continue;
                long mr = sr / n, mg = sg / n, mb = sb / n;
                long hell = (mr + mg + mb) / 3, spanne = Math.max(mr, Math.max(mg, mb)) - Math.min(mr, Math.min(mg, mb));
                int gruen = 0;
                for (int y = cy + py(ctx, 5); y < Math.min(b.getHeight(), cy + py(ctx, 42)); y += 2)
                    for (int x = cx + px(ctx, 5); x < Math.min(b.getWidth(), cx + px(ctx, 42)); x += 2) {
                        int p = b.getRGB(x, y), rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
                        if (gg > 150 && gg > rr + 50 && gg > bb + 70) gruen++;
                    }
                offen[r][c] = (hell < 100 && spanne < 40) || gruen >= 15;
                kosten[r][c] = offen[r][c] ? 0 : blockKosten(mr, mg, mb);
                farbe[r][c] = new int[]{(int) mr, (int) mg, (int) mb};
                irgendOffen |= offen[r][c];
            }
        if (!irgendOffen) return null;
        // Weitergerutscht? (18.10.: ein senkrechter Schacht sieht eine Reihe versetzt fast gleich aus -- darum die Farben
        // aller Felder vergleichen: passen sie eine Reihe versetzt besser als an derselben Stelle, ist die Mine gerutscht.)
        boolean gerutscht = offenGemerkt == null || offenGesehen == null || farbeVorher == null || offenGemerkt.length != ze
                || offenGemerkt[0].length != sp;
        if (!gerutscht) {
            gerutscht = verschobenUmEineReihe(farbe, farbeVorher);
            int zu = 0;
            for (int r = 0; r < ze; r++)
                for (int c = 0; c < sp; c++)
                    if (offenGemerkt[r][c] && !offen[r][c]) zu++;
            gerutscht |= zu > 2;
        }
        farbeVorher = farbe;
        if (gerutscht) {
            hcGerutscht = true;
            offenGemerkt = new boolean[ze][sp];
            offenGesehen = new long[ze][sp];
            hcGemerkt = new long[ze][sp];
            hcFest = null;
        }
        // Spitzhacke und Schlag-Blitz verdecken beim Graben Felder -- ein offenes Feld gilt noch 1,5 s als offen (20:07:
        // "fuer immer bis zum Rutschen" liess nach einem nicht erkannten Rutschen falsche offene Felder stehen).
        long jetztOffen = System.currentTimeMillis();
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                if (offen[r][c]) {
                    offenGemerkt[r][c] = true;
                    offenGesehen[r][c] = jetztOffen;
                } else if (offenGemerkt[r][c] && jetztOffen - offenGesehen[r][c] < 1500) {
                    offen[r][c] = true;
                    kosten[r][c] = 0;
                }
            }
        mineKosten = kosten;
        // Nur im Minenbereich suchen (17.10.: schneller als das ganze Bild).
        int mx0 = Math.max(0, px(ctx, MINE_X[0] - 60)), my0 = Math.max(0, py(ctx, MINE_Y[0] - 60));
        int mx1 = Math.min(screen.cols(), px(ctx, MINE_X[MINE_X.length - 1] + 60));
        int my1 = Math.min(screen.rows(), py(ctx, MINE_Y[MINE_Y.length - 1] + 60));
        Mat mine = new Mat(screen, new org.opencv.core.Rect(mx0, my0, mx1 - mx0, my1 - my0));
        try {
            for (String v : new String[]{T_HC_BUNT, T_HC_BRONZE}) {
                if (!ctx.matcher().has(v)) continue;
                for (Matcher.Hit h : ctx.matcher().findAll(mine, v, 0.72, 12)) {
                    int c = naechste(MINE_X, mx0 + h.centerX(), ctx, true), r = naechste(MINE_Y, my0 + h.centerY(), ctx, false);
                    if (c >= 0 && r >= 0 && !offen[r][c]) hc[r][c] = true;
                }
            }
        } finally {
            mine.release();
        }
        // Ein HC-Symbol bleibt 6 s gemerkt (es verschwand fuer ein Bild unter der Spitzhacke -- der Bot grub woanders
        // weiter und kam zurueck). Geht das Feld auf, ist es eingesammelt.
        long jetztMs = System.currentTimeMillis();
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                if (offen[r][c]) hcGemerkt[r][c] = 0;
                else if (hc[r][c]) hcGemerkt[r][c] = jetztMs;
                else if (hcGemerkt[r][c] > 0 && jetztMs - hcGemerkt[r][c] < 2500) hc[r][c] = true;
            }
        return new boolean[][][]{offen, hc};
    }

    /** Farben je Feld vom letzten mineLesen -- zum Erkennen, ob die Mine eine Reihe weitergerutscht ist. */
    private int[][][] farbeVorher;

    /**
     * Ist das Bild gegenueber dem vorigen um eine Reihe nach oben gerutscht? Mittlerer Farbabstand je Feld an derselben
     * Stelle gegen eine Reihe versetzt -- versetzt deutlich besser = gerutscht.
     */
    static boolean verschobenUmEineReihe(int[][][] jetzt, int[][][] vorher) {
        if (jetzt == null || vorher == null || jetzt.length != vorher.length || jetzt[0].length != vorher[0].length) return true;
        int ze = jetzt.length, sp = jetzt[0].length;
        double gleich = 0, versetzt = 0;
        int ng = 0, nv = 0;
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                if (jetzt[r][c] == null || vorher[r][c] == null) continue;
                gleich += farbAbstand(jetzt[r][c], vorher[r][c]);
                ng++;
                if (r + 1 < ze && vorher[r + 1][c] != null) {
                    versetzt += farbAbstand(jetzt[r][c], vorher[r + 1][c]);
                    nv++;
                }
            }
        if (ng == 0 || nv == 0) return true;
        gleich /= ng;
        versetzt /= nv;
        return gleich > 25 && versetzt < gleich * 0.6;
    }

    private static int farbAbstand(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    /**
     * Grab-Plan (Autor 18.10., drei Bilder mit eingezeichneten Wegen: "diesen Block haette er nicht zerstoeren muessen,
     * er hat sich spaeter sowieso nach unten gegraben"): Der Weg nach unten (unter das Seil) wird ohnehin gegraben --
     * ein HC wird ueber einen kurzen Abzweig davon geholt statt ueber einen eigenen Umweg. Zwei Reihenfolgen (erst nach
     * unten / erst die HC), die guenstigere gilt. Bei gleichem Preis lieber tiefer (naeher am kuenftigen Schacht).
     *
     * @return geplante (noch zue) Felder
     */
    static boolean[][] grabPlan(boolean[][] offen, boolean[][] hc, int[][] kost) {
        // Autor 18.10.: "Er kann einfach die Schaechte benutzen, die er sowieso schon freigegraben hat. Nur wenn wirklich
        // gar keine HC-Belohnung da ist, gerade runtergraben." -- mit HC im Bild nur die HC, ohne HC nur nach unten.
        boolean hcDa = false;
        for (int r = 0; r < offen.length; r++)
            for (int c = 0; c < offen[r].length; c++)
                if (hc[r][c] && !offen[r][c]) hcDa = true;
        return grabPlan(offen, hc, kost, !hcDa);
    }

    private static boolean[][] grabPlan(boolean[][] offen, boolean[][] hc, int[][] kost, boolean nurBoden) {
        int ze = offen.length, sp = offen[0].length;
        boolean[][] baum = new boolean[ze][], plan = new boolean[ze][sp];
        for (int r = 0; r < ze; r++) baum[r] = offen[r].clone();
        if (nurBoden) {
            zumBoden(baum, plan, kost);
            return plan;
        }
        while (true) {
            long[][] dist = new long[ze][sp];
            int[][] vor = new int[ze][sp];
            planWege(baum, kost, dist, vor);
            int br = -1, bc = -1;
            for (int r = 0; r < ze; r++)
                for (int c = 0; c < sp; c++)
                    if (hc[r][c] && !baum[r][c] && dist[r][c] < Long.MAX_VALUE
                            && (br < 0 || dist[r][c] < dist[br][bc])) { br = r; bc = c; }
            if (br < 0) break;
            einzeichnen(br, bc, baum, plan, vor);
        }
        return plan;
    }

    /** Weg vom Baum bis in die unterste Reihe (unter das Seil) -- es sei denn, dort ist schon etwas offen. */
    private static void zumBoden(boolean[][] baum, boolean[][] plan, int[][] kost) {
        int ze = baum.length, sp = baum[0].length;
        for (int c = 0; c < sp; c++) if (baum[ze - 1][c]) return;
        long[][] dist = new long[ze][sp];
        int[][] vor = new int[ze][sp];
        planWege(baum, kost, dist, vor);
        int bc = -1;
        for (int c = 0; c < sp; c++)
            if (dist[ze - 1][c] < Long.MAX_VALUE && (bc < 0 || dist[ze - 1][c] < dist[ze - 1][bc]
                    || (dist[ze - 1][c] == dist[ze - 1][bc] && Math.abs(c - 2) < Math.abs(bc - 2)))) bc = c;
        if (bc >= 0) einzeichnen(ze - 1, bc, baum, plan, vor);
    }

    /**
     * Dijkstra vom Baum (offen + schon geplant, Kosten 0) durch die zuen Felder. Je Feld Schlaege x 1000 plus ein kleiner
     * Zuschlag, je weiter oben es liegt -- so gewinnt bei gleich vielen Schlaegen der tiefere Weg.
     */
    private static void planWege(boolean[][] baum, int[][] kost, long[][] dist, int[][] vor) {
        int ze = baum.length, sp = baum[0].length;
        boolean[][] fertig = new boolean[ze][sp];
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                dist[r][c] = baum[r][c] ? 0 : Long.MAX_VALUE;
                vor[r][c] = -1;
            }
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (true) {
            int br = -1, bc = -1;
            for (int r = 0; r < ze; r++)
                for (int c = 0; c < sp; c++)
                    if (!fertig[r][c] && dist[r][c] < Long.MAX_VALUE && (br < 0 || dist[r][c] < dist[br][bc])) { br = r; bc = c; }
            if (br < 0) return;
            fertig[br][bc] = true;
            for (int[] d : nb) {
                int rr = br + d[0], cc = bc + d[1];
                if (rr < 0 || rr >= ze || cc < 0 || cc >= sp || baum[rr][cc] || fertig[rr][cc]) continue;
                long nd = dist[br][bc] + Math.max(1, kost[rr][cc]) * 1000L + (ze - 1 - rr);
                if (nd < dist[rr][cc]) {
                    dist[rr][cc] = nd;
                    vor[rr][cc] = br * sp + bc;
                }
            }
        }
    }

    private static void einzeichnen(int r, int c, boolean[][] baum, boolean[][] plan, int[][] vor) {
        int sp = baum[0].length;
        while (!baum[r][c]) {
            plan[r][c] = true;
            baum[r][c] = true;
            int v = vor[r][c];
            if (v < 0) break;
            r = v / sp;
            c = v % sp;
        }
    }

    private static long planWert(boolean[][] plan, int[][] kost) {
        long w = 0;
        for (int r = 0; r < plan.length; r++)
            for (int c = 0; c < plan[r].length; c++)
                if (plan[r][c]) w += Math.max(1, kost[r][c]) * 1000L + (plan.length - 1 - r);
        return w;
    }

    /**
     * Welches geplante Feld als naechstes: nur grabbare (neben einem offenen); erst alles ueber dem Seil (die unterste
     * Reihe laesst die Mine weiterrutschen -- dann waeren HC weiter oben weg), oben vor unten, HC-Felder zuerst.
     *
     * @return {Zeile, Spalte} oder null
     */
    static int[] naechstesFeld(boolean[][] offen, boolean[][] hc, boolean[][] plan, int[][] kost, java.util.Set<String> gesperrt) {
        return naechstesFeld(offen, offen, hc, plan, kost, gesperrt);
    }

    /** Wie oben; grabbar ist nur, was an ein ERREICHBARES offenes Feld grenzt (zugang, Mia 21.10.). */
    static int[] naechstesFeld(boolean[][] offen, boolean[][] zugang, boolean[][] hc, boolean[][] plan, int[][] kost,
                               java.util.Set<String> gesperrt) {
        int ze = offen.length, sp = offen[0].length;
        int[] best = null;
        long bestW = Long.MAX_VALUE;
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++) {
                if (!plan[r][c] || offen[r][c] || !randFeld(zugang, r, c) || gesperrt.contains(r + ":" + c)) continue;
                long w = (r == ze - 1 ? 1_000_000L : 0) + r * 10_000L + (hc[r][c] ? 0 : 1000) + kost[r][c] * 10L + Math.abs(c - 2);
                if (w < bestW) {
                    bestW = w;
                    best = new int[]{r, c};
                }
            }
        return best;
    }

    /** Kosten der Felder aus dem letzten mineLesen (0 = offen, sonst blockKosten). */
    private int[][] mineKosten;
    /** Seit dem letzten Weiterrutschen gesehene offene Felder und wann zuletzt ein HC je Feld zu sehen war (18.10.). */
    private boolean[][] offenGemerkt;
    private long[][] offenGesehen;
    private long[][] hcGemerkt;
    /** Messung (18.10.): Treffer auf das aktuelle Ziel, seit wann es Ziel ist, Spitzhacken dabei, Rutschen seitdem. */
    private int hcTrefferAmZiel;
    private long hcZielSeit, hcHackenBeiZiel = -1;
    private boolean hcLeerGemeldet, hcGerutscht;
    private long hcMeldungUm;
    /** Wann zuletzt die Selbsthilfe (30 s ohne Fortschritt) eingriff. */
    private long selbsthilfeUm;

    /**
     * Steht "Cannot be used on inaccessible blocks" im Bild? Weisse Schriftzeile ueber der Mine (gemessen an Autors
     * Fotos 18.10.: y 192-250, je Zeile ~360 weisse Punkte; sonst hoechstens ~190).
     */
    static boolean unerreichbarMeldung(BufferedImage b) {
        if (b == null) return false;
        double s = b.getWidth() / 1159.0;
        int x0 = (int) (300 * s), x1 = Math.min(b.getWidth(), (int) (860 * s));
        int y0 = (int) (190 * s), y1 = Math.min(b.getHeight(), (int) (252 * s)), grenze = (int) (300 * s);
        for (int y = y0; y < y1; y += 2) {
            int n = 0;
            for (int x = x0; x < x1; x++) {
                int p = b.getRGB(x, y);
                if (((p >> 16) & 255) > 235 && ((p >> 8) & 255) > 235 && (p & 255) > 235 && ++n >= grenze) return true;
            }
        }
        return false;
    }

    /** Wie oft hintereinander kein Ziel gefunden wurde (dann erst nach 5x die alte feste Grabstelle). */
    private int hcLeer;
    /** Angefangenes Feld {Zeile, Spalte, Art} und das Offen-Muster der Mine dazu (Autor 18.10.). */
    private int[] hcFest;
    private boolean[][] hcFestMuster;
    /** Wie oft hintereinander die Mine vor dem Schub noch in Bewegung war (hoechstens 3x warten). */
    private int hcRuhigFehl;

    /**
     * Spitzhacken-Schlaege je Blocksorte (Autor 18.10.: "hell oder dunkelbraun 1, grau 2"; Farben gemessen: hell
     * 206/149/92, braun 153/96/52, dunkelgrau 118/112/106, hellgrau 176/172/167). Unbekannt = 2.
     */
    static int blockKosten(long r, long g, long b) {
        if (nahe(r, g, b, 206, 149, 92) || nahe(r, g, b, 153, 96, 52)) return 1;
        return 2;
    }

    private static boolean nahe(long r, long g, long b, int rr, int gg, int bb) {
        return Math.abs(r - rr) <= 18 && Math.abs(g - gg) <= 18 && Math.abs(b - bb) <= 18;
    }

    /** Grenzt das Feld an ein offenes (nur dann laesst es sich graben)? */
    // ---- Ein Weg zu einem Ziel (Autor 21.10.) --------------------------------------------------------------------
    /** Das Feld, auf das gerade gegraben wird ({Zeile, Spalte}): ein HC, oder ohne HC ein Feld der untersten Reihe. */
    private int[] wegZiel;
    /** Der Weg dorthin, der Reihe nach vom Schacht aus; jedes Feld {Zeile, Spalte}. */
    private java.util.List<int[]> wegZellen;
    private boolean wegZumBoden;
    /**
     * Zaehlung fuer Autor (21.10.: "wastet locker 50 % der Spitzhacken"): geholte HC, Spitzhacken bis dahin, und Schlaege
     * auf Felder, die danach nicht fertig gegraben wurden (verschwendet). Steht im Bericht am Ende.
     */
    private int hcGeholt, wegeAufgegeben;
    private long hackenBeiZielStart = -1, hackenFuerHc;

    /** Wann zuletzt ein HC im Bild war -- nach unten wird erst gegraben, wenn HC_PAUSE_MS lang keins zu sehen war. */
    private long hcZuletztGesehen;
    static final long HC_PAUSE_MS = 1_500;

    /** Ist das als naechstes angeschlagene Feld (hcFest, schon getroffen) ein noch zues Feld dieses Wegs? */
    private boolean angefangenAufWeg(boolean[][] offen) {
        if (hcFest == null || hcTrefferAmZiel <= 0 || wegZellen == null) return false;
        if (hcFest[0] >= offen.length || offen[hcFest[0]][hcFest[1]]) return false;
        for (int[] z : wegZellen) if (z[0] == hcFest[0] && z[1] == hcFest[1]) return true;
        return false;
    }

    /** Die offenen Felder beim letzten Blick -- daran wird ein Rutschen der Mine erkannt. */
    private boolean[][] wegOffenVorher;

    /**
     * Um wie viele Reihen ist die Mine hochgerutscht (0, 1 oder 2)? Verglichen wird das Muster der offenen Felder: passt es
     * um s Reihen versetzt deutlich besser (mindestens 90 % und 8 Punkte besser als unversetzt), dann s.
     */
    static int rutschReihen(boolean[][] vorher, boolean[][] jetzt) {
        int ze = jetzt.length, sp = jetzt[0].length;
        double[] q = new double[3];
        for (int sh = 0; sh <= 2 && sh < ze; sh++) {
            int gleich = 0, n = 0;
            for (int r = 0; r + sh < ze; r++)
                for (int c = 0; c < sp; c++) {
                    n++;
                    if (vorher[r + sh][c] == jetzt[r][c]) gleich++;
                }
            q[sh] = n == 0 ? 0 : gleich / (double) n;
        }
        int best = q[1] >= q[2] ? 1 : 2;
        return q[best] >= 0.9 && q[best] > q[0] + 0.08 ? best : 0;
    }

    /** Ein Feld der untersten Reihe auf dem Weg zu einem HC weiter oben kostet so viel mehr (es laesst die Mine rutschen). */
    static final int BODEN_STRAFE = 50;

    /**
     * Autor 21.10.: Genau EIN Ziel und genau EIN Weg dorthin -- der wird Block fuer Block zu Ende gegraben, erst dann kommt
     * das naechste HC. Vorher plante der Bot Wege zu allen sichtbaren HC zugleich und grub abwechselnd an mehreren, dazu
     * sprang der Weg zum selben HC zwischen zwei gleich teuren hin und her ("verschwendet locker 50 % der Spitzhacken").
     *
     * Ziel: das guenstigste HC ueber der untersten Reihe (die unterste laesst die Mine rutschen, HC weiter oben waeren dann
     * weg), sonst eins in der untersten Reihe; ohne HC der guenstigste Weg nach unten. Rutscht die Mine, wird das Ziel eine
     * Reihe hoeher wiedergefunden (Weg mit). Neu geplant wird nur, wenn der Weg nicht mehr stimmt.
     *
     * @param plan bekommt die noch zuen Felder des Wegs (fuer Anzeige und "angeschlagen fertig graben")
     * @return das naechste Feld {Zeile, Spalte}, oder null
     */
    int[] einWeg(boolean[][] offen, boolean[][] zugang, boolean[][] hc, int[][] kost, java.util.Set<String> gesperrt,
                 boolean[][] plan) {
        return einWeg(offen, zugang, hc, kost, gesperrt, plan, true);
    }

    /** @param graben true = Spitzhacke (vor dem Weg nach unten 1,5 s auf ein HC warten); false = Werkzeug (sofort ein Ziel) */
    int[] einWeg(boolean[][] offen, boolean[][] zugang, boolean[][] hc, int[][] kost, java.util.Set<String> gesperrt,
                 boolean[][] plan, boolean graben) {
        int ze = offen.length, sp = offen[0].length;
        boolean bodenOffen = false;
        for (int c = 0; c < sp; c++) bodenOffen |= offen[ze - 1][c];
        boolean hcDa = false;
        for (int r = 0; r < ze; r++) for (int c = 0; c < sp; c++) hcDa |= hc[r][c] && !offen[r][c];
        long jetztMs = System.currentTimeMillis();
        if (hcDa) hcZuletztGesehen = jetztMs;
        // 1. Gilt das Ziel noch?
        // Fotoserie 21.10.: Beim Schlagen verdeckt die Spitzhacke das HC-Zeichen -- der Bot hielt das Ziel fuer weg, nahm ein
        // anderes und liess den angeschlagenen Block liegen ("4:2 angeschlagen (4x) und verlassen"). Darum gilt das Ziel,
        // solange das Feld zu ist. Ist die Mine gerutscht, zeigt das Muster der offenen Felder um wie viele Reihen -- Ziel und
        // Weg wandern mit.
        int rutsch = wegOffenVorher == null || wegOffenVorher.length != ze ? 0 : rutschReihen(wegOffenVorher, offen);
        wegOffenVorher = new boolean[ze][];
        for (int r = 0; r < ze; r++) wegOffenVorher[r] = offen[r].clone();
        if (wegZiel != null && rutsch > 0) {
            wegZiel = new int[]{wegZiel[0] - rutsch, wegZiel[1]};
            if (wegZellen != null) {
                java.util.List<int[]> neu = new java.util.ArrayList<>();
                for (int[] z : wegZellen) if (z[0] - rutsch >= 0) neu.add(new int[]{z[0] - rutsch, z[1]});
                wegZellen = neu;
            }
        }
        if (wegZiel != null) {
            int zr = wegZiel[0], zc = wegZiel[1];
            if (zr < 0 || zr >= ze) {
                wegeAufgegeben++;
                Log.info(WER, Sprache.t("HC digging: target out of view -- " + wegeAufgegeben + " so far.",
                        "HC-Graben: Ziel aus dem Bild -- bisher " + wegeAufgegeben + "."));
                wegZiel = null;
            } else if (offen[zr][zc]) {
                if (!wegZumBoden) {
                    hcGeholt++;
                    long verbraucht = hackenBeiZielStart >= 0 && hacken >= 0 ? hackenBeiZielStart - hacken : -1;
                    if (verbraucht >= 0) hackenFuerHc += verbraucht;
                    Log.info(WER, Sprache.t("HC digging: HC " + zr + ":" + zc + " collected" + (verbraucht >= 0 ? " with ~" + verbraucht + " pickaxes" : "")
                                    + " (" + hcGeholt + " HC so far).",
                            "HC-Graben: HC " + zr + ":" + zc + " geholt" + (verbraucht >= 0 ? " mit ~" + verbraucht + " Spitzhacken" : "")
                                    + " (bisher " + hcGeholt + " HC)."));
                }
                wegZiel = null;
            } else if (wegZumBoden && bodenOffen) {
                wegZiel = null;   // unten ist schon etwas offen -- die Mine rutscht ohnehin
            } else if (wegZumBoden && hcDa && !angefangenAufWeg(offen)) {
                // Autor 21.10.: nach unten nur ohne HC -- taucht eins auf, sofort dorthin (ein schon angeschlagener Block
                // auf dem Weg nach unten wird noch fertig gegraben, sonst waeren die Schlaege verloren).
                wegZiel = null;
            }
            // sonst: bleibt (auch wenn das HC-Zeichen gerade verdeckt ist, und auch ein angefangener Weg nach unten)
        }
        // 2. Stimmt der Weg noch? (kein Feld gesperrt, das erste zue Feld grenzt an den Schacht)
        if (wegZiel != null && wegZellen != null) {
            int[] erstes = null;
            boolean gut = true;
            for (int[] z : wegZellen) {
                if (z[0] < 0 || z[0] >= ze || z[1] < 0 || z[1] >= sp || gesperrt.contains(z[0] + ":" + z[1])) { gut = false; break; }
                if (erstes == null && !offen[z[0]][z[1]]) erstes = z;
            }
            if (!gut || erstes == null || !randFeld(zugang, erstes[0], erstes[1])) wegZellen = null;
        }
        // 3. Neues Ziel / neuer Weg
        int[][] k = new int[ze][sp];
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++)
                k[r][c] = gesperrt.contains(r + ":" + c) ? 10_000 : kost[r][c];
        boolean[][] baum = new boolean[ze][];
        for (int r = 0; r < ze; r++) baum[r] = zugang[r].clone();
        if (wegZiel == null || wegZellen == null) {
            // Felder der untersten Reihe sind teuer, solange das Ziel nicht selbst dort liegt.
            int[][] kOben = new int[ze][sp];
            for (int r = 0; r < ze; r++)
                for (int c = 0; c < sp; c++)
                    kOben[r][c] = k[r][c] + (r == ze - 1 && !offen[r][c] ? BODEN_STRAFE : 0);
            long[][] dist = new long[ze][sp];
            int[][] vor = new int[ze][sp];
            if (wegZiel == null) {
                planWege(baum, kOben, dist, vor);
                int[] best = null;
                long bestW = Long.MAX_VALUE;
                for (int r = 0; r < ze; r++)
                    for (int c = 0; c < sp; c++) {
                        if (!hc[r][c] || offen[r][c] || dist[r][c] == Long.MAX_VALUE || gesperrt.contains(r + ":" + c)) continue;
                        long w = (r == ze - 1 ? 1_000_000_000L : 0) + dist[r][c] * 10 + Math.abs(c - 2);
                        if (w < bestW) { bestW = w; best = new int[]{r, c}; }
                    }
                wegZumBoden = false;
                // Autor 21.10.: nur nach unten, wenn wirklich kein HC da ist -- mindestens 1,5 s keins gesehen (beim Rutschen
                // oder unter der Spitzhacke ist es kurz nicht zu sehen; um 22:55 fing der Bot so einen unnoetigen Block an).
                if (graben && best == null && !bodenOffen && jetztMs - hcZuletztGesehen < HC_PAUSE_MS) return null;
                if (best == null && !bodenOffen) {
                    // kein HC: der guenstigste Weg in die unterste Reihe
                    planWege(baum, k, dist, vor);
                    for (int c = 0; c < sp; c++) {
                        int r = ze - 1;
                        if (offen[r][c] || dist[r][c] == Long.MAX_VALUE || gesperrt.contains(r + ":" + c)) continue;
                        long w = dist[r][c] * 10 + Math.abs(c - 2);
                        if (w < bestW) { bestW = w; best = new int[]{r, c}; }
                    }
                    wegZumBoden = best != null;
                }
                wegZiel = best;
                if (best != null) {
                    hackenBeiZielStart = hacken;
                    Log.debug(WER, "HC-Graben: neues Ziel " + best[0] + ":" + best[1] + (wegZumBoden ? " (nach unten)" : " (HC)") + ".");
                }
            }
            if (wegZiel != null) {
                if (wegZumBoden || wegZiel[0] == ze - 1) planWege(baum, k, dist, vor);
                else planWege(baum, kOben, dist, vor);
                wegZellen = null;
                if (dist[wegZiel[0]][wegZiel[1]] < Long.MAX_VALUE) {
                    java.util.LinkedList<int[]> w = new java.util.LinkedList<>();
                    int r = wegZiel[0], c = wegZiel[1];
                    while (!baum[r][c]) {
                        w.addFirst(new int[]{r, c});
                        int v = vor[r][c];
                        if (v < 0) break;
                        r = v / sp;
                        c = v % sp;
                    }
                    wegZellen = new java.util.ArrayList<>(w);
                }
                if (wegZellen == null || wegZellen.isEmpty()) wegZiel = null;
            }
        }
        if (wegZiel == null || wegZellen == null) return null;
        // 4. Das naechste Feld: das erste noch zue des Wegs
        int[] naechstes = null;
        for (int[] z : wegZellen) {
            if (offen[z[0]][z[1]]) continue;
            plan[z[0]][z[1]] = true;
            if (naechstes == null) naechstes = z;
        }
        if (naechstes == null || !randFeld(zugang, naechstes[0], naechstes[1])) {
            wegZellen = null;
            return null;
        }
        return new int[]{naechstes[0], naechstes[1]};
    }

    /**
     * Mia 21.10. ("der Bot drueckt auf Bloecke, die noch gesperrt sind"): Offen ist nicht gleich erreichbar. Ein offener
     * Hohlraum, der nicht mit dem eigenen Schacht verbunden ist, macht seine Nachbarn NICHT grabbar ("Cannot be used on
     * inaccessible blocks"). Erreichbar sind nur die offenen Felder, die ueber offene Felder mit der obersten Reihe
     * zusammenhaengen -- dort kommt der Schacht herein. Ist oben nichts offen, gilt wie bisher jedes offene Feld.
     */
    static boolean[][] zugang(boolean[][] offen) {
        int ze = offen.length, sp = offen[0].length;
        boolean[][] z = new boolean[ze][sp];
        java.util.ArrayDeque<int[]> offenListe = new java.util.ArrayDeque<>();
        for (int c = 0; c < sp; c++) {
            if (offen[0][c]) {
                z[0][c] = true;
                offenListe.add(new int[]{0, c});
            }
        }
        if (offenListe.isEmpty()) {
            for (int r = 0; r < ze; r++) z[r] = offen[r].clone();
            return z;
        }
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!offenListe.isEmpty()) {
            int[] q = offenListe.poll();
            for (int[] d : nb) {
                int rr = q[0] + d[0], cc = q[1] + d[1];
                if (rr < 0 || rr >= ze || cc < 0 || cc >= sp || z[rr][cc] || !offen[rr][cc]) continue;
                z[rr][cc] = true;
                offenListe.add(new int[]{rr, cc});
            }
        }
        return z;
    }

    static boolean randFeld(boolean[][] offen, int r, int c) {
        int ze = offen.length, sp = offen[0].length;
        return (r > 0 && offen[r - 1][c]) || (r < ze - 1 && offen[r + 1][c]) || (c > 0 && offen[r][c - 1]) || (c < sp - 1 && offen[r][c + 1]);
    }

    private static int unterschiede(boolean[][] a, boolean[][] b) {
        if (b == null || b.length != a.length) return 99;
        int n = 0;
        for (int r = 0; r < a.length; r++)
            for (int c = 0; c < a[r].length; c++)
                if (a[r][c] != b[r][c]) n++;
        return n;
    }

    /**
     * Ziel fuer den naechsten Schlag als {x, y, 1 = Weg zu einem HC-Feld / 0 = tiefstes erreichbares Feld}. Gegraben
     * werden kann nur, was an ein offenes Feld grenzt (sonst "Cannot be used on inaccessible blocks").
     *
     * @param sperreZaehlen beim Graben: wirkt das Tippen auf ein Feld dreimal nicht (Spitzhacken-Zahl bleibt), wird es
     *                      30 s gemieden. Bei den Werkzeugen nicht (dort bleibt die Zahl ohnehin stehen).
     */
    /** Feld und Zeit der letzten Meldung "inaccessible" (erst beim zweiten Mal meiden, 21.10.). */
    private String unerreichbarFeld = "";
    private long unerreichbarUm;

    private Optional<int[]> hcZiel(Mat screen, BotContext ctx, boolean sperreZaehlen) {
        boolean[][][] m = mineLesen(screen, ctx);
        if (m == null) return Optional.empty();
        boolean[][] offen = m[0], hc = m[1];
        int sp = MINE_X.length, ze = MINE_Y.length;
        long jetzt = System.currentTimeMillis();
        hcGesperrt.values().removeIf(bis -> bis < jetzt);
        // Autor 18.10.: "klickt 5-10 s auf einen Block, den man gar nicht anklicken kann" -- steht "Cannot be used on
        // inaccessible blocks" im Bild, das letzte Ziel sofort 8 s meiden und das Gedaechtnis vergessen.
        if (sperreZaehlen && !hcLetztesZiel.isEmpty() && jetzt - hcMeldungUm > 2500 && unerreichbarMeldung(ctx.frame())) {
            hcMeldungUm = jetzt;
            // Fotoserie 21.10.: die Meldung kam oft, waehrend nach einem aufgegangenen Block das Belohnungsfenster hochkam --
            // sie gehoerte nicht zum naechsten Ziel. Erst beim zweiten Mal fuer dasselbe Feld (innerhalb 20 s) meiden.
            boolean zweitesMal = hcLetztesZiel.equals(unerreichbarFeld) && jetzt - unerreichbarUm < 20_000;
            unerreichbarFeld = hcLetztesZiel;
            unerreichbarUm = jetzt;
            if (zweitesMal) {
                hcGesperrt.put(hcLetztesZiel, jetzt + 8000);
                wegZellen = null;   // neuer Weg, wenn es einen gibt
            }
            offenGemerkt = null;
            Log.info(WER, "HC-Messung: Feld " + hcLetztesZiel + (zweitesMal ? " unerreichbar (Meldung zweimal) -- 8 s gemieden."
                    : ": Meldung 'inaccessible' (einmal) -- noch nicht gemieden."));
            m = mineLesen(screen, ctx);
            if (m == null) return Optional.empty();
            offen = m[0];
            hc = m[1];
        }
        int[][] kost = new int[ze][sp];
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++)
                kost[r][c] = offen[r][c] ? 0 : mineKosten != null ? Math.max(1, mineKosten[r][c]) : 1;
        // Das zuletzt angeschlagene Feld braucht einen Schlag weniger -- fertig gegraben wird es nur, wenn es im Plan bleibt.
        if (sperreZaehlen && hcFest != null && !offen[hcFest[0]][hcFest[1]]) {
            kost[hcFest[0]][hcFest[1]] = Math.max(1, kost[hcFest[0]][hcFest[1]] - 1);
        }
        // Mia 21.10.: Wege nur vom eigenen Schacht aus planen (nicht von abgeschlossenen Hohlraeumen).
        boolean[][] zugang = zugang(offen);
        // Autor 21.10. ("wastet locker 50 % der Spitzhacken -- graebt Wege, die er gar nicht braucht", "nur EINEN Weg
        // anfangen, nicht mehrere Wege zu einer HC"): genau ein Ziel, genau ein Weg -- der wird zu Ende gegraben.
        boolean[][] plan = new boolean[ze][sp];
        int[] f = einWeg(offen, zugang, hc, kost, hcGesperrt.keySet(), plan, sperreZaehlen);
        // Mia 21.10. ("schlaegt manche Bloecke an und macht dann bei einem anderen weiter -- eine Spitzhacke verschwendet"):
        // ein schon angeschlagener Block wird fertig gegraben, solange er erreichbar ist und gebraucht wird (im Plan, oder
        // es gibt noch HC im Bild). Sonst sind die Schlaege darauf verloren.
        if (sperreZaehlen && hcFest != null && hcTrefferAmZiel > 0 && !hcGerutscht) {
            int fr = hcFest[0], fc = hcFest[1];
            String fest = fr + ":" + fc;
            boolean hcImBild = false;
            for (int r = 0; r < ze && !hcImBild; r++)
                for (int c = 0; c < sp; c++)
                    if (hc[r][c] && !offen[r][c]) { hcImBild = true; break; }
            if (fr < ze && fc < sp && fest.equals(hcLetztesZiel) && !offen[fr][fc] && randFeld(zugang, fr, fc)
                    && !hcGesperrt.containsKey(fest) && (plan[fr][fc] || hcImBild)
                    && (f == null || f[0] != fr || f[1] != fc)) {
                Log.debug(WER, "HC-Graben: Feld " + fest + " ist angeschlagen (" + hcTrefferAmZiel + "x) -- erst fertig graben"
                        + (f == null ? "." : " (Plan wollte " + f[0] + ":" + f[1] + ")."));
                f = new int[]{fr, fc};
            }
        }
        if (f == null) return Optional.empty();
        int sr = f[0], sc = f[1], hcs = 0, felder = 0, schlaege = 0;
        for (int r = 0; r < ze; r++)
            for (int c = 0; c < sp; c++)
                if (plan[r][c]) {
                    felder++;
                    schlaege += kost[r][c];
                    if (hc[r][c]) hcs++;
                }
        int art = hcs > 0 ? 1 : 0;
        if (sperreZaehlen) {
            hcFest = new int[]{sr, sc, art};
            hcFestMuster = new boolean[ze][];
            for (int r = 0; r < ze; r++) hcFestMuster[r] = offen[r].clone();
        }
        String key = sr + ":" + sc;
        if (sperreZaehlen) {
            // Messung (Autor 18.10.): Feld angeschlagen und verlassen / laenger als 3 s ohne Wirkung getippt.
            if (!key.equals(hcLetztesZiel)) {
                if (!hcLetztesZiel.isEmpty() && hcTrefferAmZiel > 0 && !hcGerutscht) {
                    String[] a = hcLetztesZiel.split(":");
                    int lr = Integer.parseInt(a[0]), lc = Integer.parseInt(a[1]);
                    if (lr < ze && lc < sp && !offen[lr][lc]) {
                        Log.info(WER, "HC-Messung: Feld " + hcLetztesZiel + " angeschlagen (" + hcTrefferAmZiel + "x) und verlassen -> " + key);
                    }
                }
                hcTrefferAmZiel = 0;
                hcZielSeit = jetzt;
                hcHackenBeiZiel = hacken;
                hcLeerGemeldet = false;
                hcGerutscht = false;
            } else if (hacken >= 0 && hcHackenVorher >= 0 && hacken < hcHackenVorher) {
                hcTrefferAmZiel += (int) (hcHackenVorher - hacken);
            } else if (!hcLeerGemeldet && hacken >= 0 && hacken >= hcHackenBeiZiel && jetzt - hcZielSeit > 3000) {
                hcLeerGemeldet = true;
                Log.info(WER, "HC-Messung: seit " + (jetzt - hcZielSeit) / 1000 + " s ohne Wirkung auf Feld " + key);
            }
            if (key.equals(hcLetztesZiel) && hacken >= 0 && hacken >= hcHackenVorher && letzterSchubMs >= 800) {
                // Wirkte nicht: vielleicht ist die Mine unbemerkt gerutscht -- Gedaechtnis vergessen, neu sehen.
                offenGemerkt = null;
                if (++hcGleichesZiel >= 3) {
                    hcGesperrt.put(key, jetzt + 30_000);
                    hcGleichesZiel = 0;
                    hcLetztesZiel = "";
                    Log.info(WER, de.hustlebot.core.Sprache.t("HC digging: block " + key + " does not open -- avoiding it for 30 s.",
                            "HC-Graben: Feld " + key + " geht nicht auf -- 30 s gemieden."));
                    foto(ctx, "hc-feld-zu");
                    return Optional.empty();
                }
            } else {
                hcGleichesZiel = 0;
            }
            hcLetztesZiel = key;
            hcHackenVorher = hacken;
        }
        if (!key.equals(hcGemeldet)) {
            hcGemeldet = key;
            Log.debug(WER, de.hustlebot.core.Sprache.t(
                    "HC digging: plan " + felder + " blocks / " + schlaege + " hits, " + hcs + " HC -- digging " + key,
                    "HC-Graben: Plan " + felder + " Felder / " + schlaege + " Schlaege, " + hcs + " HC -- grabe " + key));
        }
        // Ein offenes Nachbarfeld (lieber unten/links/rechts -- oben rechts steht beim Schlag die Spitzhacke): aendert es
        // sich waehrend des Schubs, ist die Mine weitergerutscht.
        int nr = -1, nc = -1;
        for (int[] d : new int[][]{{1, 0}, {0, -1}, {0, 1}, {-1, 0}}) {
            int rr = sr + d[0], cc = sc + d[1];
            if (rr >= 0 && rr < ze && cc >= 0 && cc < sp && offen[rr][cc]) { nr = rr; nc = cc; break; }
        }
        return Optional.of(new int[]{px(ctx, MINE_X[sc]), py(ctx, MINE_Y[sr]), art,
                nr >= 0 ? px(ctx, MINE_X[nc]) : -1, nr >= 0 ? py(ctx, MINE_Y[nr]) : -1});
    }

    /**
     * Spalte fuer den Drill (0-5) oder -1 = wie bisher dort lassen, wo die Form erscheint (Spalte 3). Autor 17.10.: "der
     * Bohrer funktioniert nur, wenn ... mindestens 1 Slot im Nebenschacht offen" ist. Unter diesen Spalten die mit den
     * meisten HC-Feldern, sonst die mit den meisten festen Feldern, bei Gleichstand nah an Spalte 3.
     */
    private int drillSpalte(Mat screen, BotContext ctx) {
        boolean[][][] m = mineLesen(screen, ctx);
        if (m == null) return -1;
        boolean[][] offen = m[0], hc = m[1];
        int ze = MINE_Y.length, spn = MINE_X.length, beste = -1;
        long besterWert = Long.MIN_VALUE;
        for (int c = 0; c < spn; c++) {
            int zu = 0, hcs = 0;
            boolean nachbar = false;
            for (int r = 0; r < ze; r++) {
                if (!offen[r][c]) { zu++; if (hc[r][c]) hcs++; }
                if ((c > 0 && offen[r][c - 1]) || (c < spn - 1 && offen[r][c + 1])) nachbar = true;
            }
            // Autor 18.10.: "die Belohnungen rechts mit dem Bohrer nicht mitgenommen" -- ohne HC nahm er die Spalte mit den
            // meisten Bloecken (links, ohne HC). Jetzt nur Spalten mit HC; ohne HC gerade runter (unten).
            if (hcs == 0 || zu == 0 || !nachbar) continue;
            long wert = hcs * 1000L + zu * 10L - Math.abs(c - 2);
            if (wert > besterWert) { besterWert = wert; beste = c; }
        }
        if (beste < 0) {
            // Kein HC erreichbar: die Spalte mit dem tiefsten offenen Feld (der Schacht) weiter nach unten.
            int tiefste = -1;
            for (int c = 0; c < spn; c++)
                for (int r = ze - 1; r >= 0; r--)
                    if (offen[r][c]) {
                        if (r > tiefste || (r == tiefste && Math.abs(c - 2) < Math.abs(beste - 2))) { tiefste = r; beste = c; }
                        break;
                    }
        }
        if (beste >= 0) Log.debug(WER, Sprache.t("HC digging: drill column " + (beste + 1), "HC-Graben: Drill-Spalte " + (beste + 1)));
        return beste;
    }

    /**
     * Den gruenen Haken einer verschobenen Form finden: er steht immer gerade ueber ihrem roten X (Fotoserie 17.10.:
     * Haken 462,285 / X 463,358). Nur so ein Paar zaehlt -- die Haekchen auf eingesammelten Funden haben kein X darunter.
     */
    private static Optional<Matcher.Hit> hakenMitX(BotContext ctx, long bisMs) {
        if (!ctx.matcher().has(T_HAKEN_OK)) return Optional.empty();
        long start = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            ctx.sleep(100);
            ctx.matcher().naechstesBildGruendlich();
            Mat frisch = ctx.refresh();
            if (frisch != null) {
                List<Matcher.Hit> haken = ctx.matcher().findAll(frisch, T_HAKEN_OK, 0.8, 6);
                List<Matcher.Hit> xe = ctx.matcher().has(T_FORM_X) ? ctx.matcher().findAll(frisch, T_FORM_X, 0.8, 6) : List.of();
                for (Matcher.Hit h : haken) {
                    if (xe.isEmpty() && haken.size() == 1) return Optional.of(h);
                    for (Matcher.Hit x : xe) {
                        int dx = x.centerX() - h.centerX(), dy = x.centerY() - h.centerY();
                        if (Math.abs(dx) <= px(ctx, 20) && dy >= py(ctx, 50) && dy <= py(ctx, 100)) return Optional.of(h);
                    }
                }
            }
            if (System.currentTimeMillis() - start >= bisMs) return Optional.empty();
        }
        return Optional.empty();
    }

    /** Index der naechsten Mitte (Spalte/Zeile), -1 wenn mehr als eine halbe Zelle daneben. */
    private static int naechste(int[] mitten, int wert, BotContext ctx, boolean x) {
        int beste = -1, bestA = Integer.MAX_VALUE;
        for (int i = 0; i < mitten.length; i++) {
            int a = Math.abs((x ? px(ctx, mitten[i]) : py(ctx, mitten[i])) - wert);
            if (a < bestA) { bestA = a; beste = i; }
        }
        return bestA <= (x ? px(ctx, 47) : py(ctx, 45)) ? beste : -1;
    }

    /** titan-nur.txt: "dynamit", "super" oder "drill" = nur dieses Werkzeug, keine Spitzhacken (Testrunden 18.10.). */
    private static Werkzeug nurWerkzeug() {
        try {
            java.nio.file.Path d = java.nio.file.Path.of("titan-nur.txt");
            if (!java.nio.file.Files.exists(d)) return null;
            String s = de.hustlebot.core.Dateien.text(d).trim().toLowerCase(java.util.Locale.ROOT);
            return s.startsWith("dyn") ? Werkzeug.DYNAMIT : s.startsWith("super") ? Werkzeug.SUPER
                    : s.startsWith("drill") || s.startsWith("bohr") ? Werkzeug.DRILL : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================= Werkzeuge
    /** Spitzhacken alle: Super-Spitzhacken und Dynamit verbrauchen, den Drill nur mit titan-drill.txt "an". */
    private Outcome werkzeugSchritt(Mat screen, BotContext ctx, RunTracker runs) {
        if (ziel > 0 && werkzeugFertig.size() < Werkzeug.values().length) {
            // Super-Spitzhacken und Dynamit graben viele Felder auf einmal -- mit Ziel-Tiefe nie (sonst drueber).
            Log.info(WER, Sprache.t("Target depth set -- no tools (they dig too much at once).",
                    "Ziel-Tiefe gesetzt -- keine Werkzeuge (graben zu viel auf einmal)."));
            for (Werkzeug k : Werkzeug.values()) werkzeugFertig.add(k);
        }
        Werkzeug nur = nurWerkzeug();
        if (nur != null) {
            for (Werkzeug k : Werkzeug.values()) if (k != nur) werkzeugFertig.add(k);
        } else if (!werkzeugFertig.contains(Werkzeug.DRILL) && !drillErlaubt(ctx)) werkzeugFertig.add(Werkzeug.DRILL);
        Werkzeug w = null;
        for (Werkzeug k : Werkzeug.values()) {
            if (!werkzeugFertig.contains(k)) {
                w = k;
                break;
            }
        }
        if (w == null) {
            Log.info(WER, Sprache.t("Tools used up. " + bericht(), "Werkzeuge verbraucht. " + bericht()));
            phase = Phase.ENDE;
            return Outcome.ACTED;
        }
        // Watch-Knopf am FRISCHEN Bild pruefen (Titan-Idee 8) -- springt die Zahl gerade auf 0, steht dort Werbung.
        Mat frischWatch = ctx.refresh();
        BufferedImage b = ctx.frame();
        int[] k = w.zahl;
        if (frischWatch == null || watchKnopf(ctx, b, k)) {
            Log.info(WER, Sprache.t(w.name + ": none left (yellow Watch button = ad, never tap).",
                    w.name + ": keine mehr (gelber Watch-Knopf = Werbung, nie antippen)."));
            werkzeugFertig.add(w);
            return Outcome.ACTED;
        }
        // Autor 21.10. (Foto eines Freundes ohne Pass): gesperrte Werkzeuge tragen ein goldenes Schloss oben rechts am Symbol
        // -- die Zahl steht trotzdem da (50, 30, 9). Nie antippen, gleich auslassen.
        if (gesperrt(ctx, frischWatch, w)) {
            Log.info(WER, Sprache.t(w.name + ": locked (padlock, no pass) -- skipped.", w.name + ": gesperrt (Schloss, kein Pass) -- ausgelassen."));
            werkzeugFertig.add(w);
            return Outcome.ACTED;
        }
        // Autor 18.10.: Ohne Pass (Ben) gibt es kein Dynamit, keine Super-Spitzhacken und keine Drills -- dann steht
        // dort 0. Einmal je Besuch die Zahl lesen und bei 0 gar nicht erst antippen (sonst Fehlversuche/Pass-Fenster).
        if (werkzeugGezaehlt.add(w)) {
            OptionalLong anzahl = zahlLesen(b, px(ctx, k[0]), py(ctx, k[1]), px(ctx, k[2]), py(ctx, k[3]), -1);
            if (anzahl.isPresent() && anzahl.getAsLong() == 0) {
                Log.info(WER, Sprache.t(w.name + ": 0 -- skipped (no pass?).", w.name + ": 0 -- ausgelassen (kein Pass?)."));
                werkzeugFertig.add(w);
                return Outcome.ACTED;
            }
        }
        // Keine Zahl mehr ablesen (spart ~0,5 s je Werkzeug, Autor 09.10.: "Wartezeiten kuerzen"): benutzt wird,
        // bis statt der Zahl der gelbe Watch-Knopf steht.
        // Zaehlt nur Versuche OHNE Erfolg hintereinander (beim Haken wieder 0): 09.10. 22:22 brach der Bot nach 56 von
        // 175 Super-Spitzhacken ab, das Dynamit (81) blieb ganz liegen -- die Grenze zaehlte alle Einsaetze.
        if (++werkzeugVersuche > 60) {
            warnung(Sprache.t("60 tool attempts in a row without success -- stopping (guard against an endless loop).",
                    "60 Werkzeug-Versuche ohne Erfolg hintereinander -- hoere auf (Schutz gegen Endlosschleife)."));
            phase = Phase.ENDE;
            return Outcome.ACTED;
        }
        // Erst ein freies Minenbild: 09.10. lag zweimal noch "You have obtained" vom letzten Schlag darueber, und
        // der Tipp aufs Werkzeug ging ins Leere. Die Belohnung erledigt der naechste Schritt (Claim).
        Mat frei = ctx.refresh();
        // Autor 18.10. (Bericht #MD6S): kam nach dem Antippen eine Belohnung dazwischen, blieb "Select an area" offen und
        // der Bot wartete stumm. Am Anfang eines Werkzeug-Schritts ist jedes offene Auswahlfenster alt -- rotes X.
        if (frei != null && ctx.matcher().has(T_FORM_X) && ctx.find(frei, T_FORM_X).isPresent() && !sicht(frei, ctx, T_TITEL)) {
            ctx.clickIfVisibleIfKnown(frei, T_FORM_X, Sprache.t("Old selection box still open -- red X", "Altes Auswahlfenster noch offen -- rotes X"));
            ctx.sleepUntilChanged(800);
            return Outcome.ACTED;
        }
        if (frei == null || !sicht(frei, ctx, T_TITEL) || (ctx.matcher().has(T_CLAIM) && ctx.find(frei, T_CLAIM).isPresent())) {
            ctx.sleep(tempo.ms(200));
            return Outcome.ACTED;
        }
        // Antippen und nachsehen, ob "Select an area" mit dem gruenen Haken kam (Bild ev_titan_haken_ok --
        // eine Farbpruefung taugt nicht: die eingesammelten Funde tragen auch gruene Haekchen).
        // 17.10., 20:51: HC-Ziel und Drill-Spalte JETZT ausrechnen, solange "frei" das aktuelle Minenbild ist --
        // nach dem Antippen gibt hakenAbwarten() es mit refresh() frei (CvException), und das Bild zeigt das Auswahlfenster.
        // Fotoserie 21.10.: das Dynamit zielte auf das naechste Wegfeld -- die 3x3-Sprengung traf zur Haelfte schon offene
        // Felder. Jetzt die Mitte, unter der die meisten HC (dann die meisten zuen Bloecke) liegen.
        Optional<int[]> hcVorher = w.ziehen && hcAn() ? formZiel(frei, ctx, w) : Optional.empty();
        int drillVorher = w == Werkzeug.DRILL && hcAn() ? drillSpalte(frei, ctx) : -1;
        tippe(ctx, w.symbol, w.name + " antippen");
        // Form-Werkzeuge: Haken irgendwo rechts am Schacht (die Form steht schon auf dem aktiven Block, dessen
        // Zeile wechselt); Drill: festes Fenster.
        Optional<Matcher.Hit> haken = hakenAbwarten(ctx, w.ziehen ? null : w.haken, 1200);
        if (haken.isEmpty() && belohnungDa(ctx)) {
            // Das Belohnungsbild kam erst nach dem Tipp -- kein Fehlversuch, der naechste Schritt nimmt Claim.
            return Outcome.ACTED;
        }
        if (haken.isEmpty() && System.currentTimeMillis() - letzterHakenUm < 2500) {
            // Das vorige Werkzeug laeuft noch (Animation) -- kein Fehlversuch, gleich noch einmal.
            ctx.sleep(150);
            return Outcome.ACTED;
        }
        if (haken.isEmpty()) {
            int fehl = werkzeugFehl.merge(w, 1, Integer::sum);
            warnung(Sprache.t(w.name + ": no selection box with a green check mark (" + fehl + ". time)" + (fehl >= 3 ? " -- skipping it." : " -- trying again shortly."),
                    w.name + ": kein Auswahlfeld mit gruenem Haken (" + fehl + ". Mal)"
                    + (fehl >= 3 ? " -- lasse es aus." : " -- gleich noch einmal.")));
            foto(ctx, "werkzeug-ohne-haken");
            if (fehl >= 3) werkzeugFertig.add(w);
            return Outcome.ACTED;
        }
        if (w.ziehen) {
            // Hauptbot 17.10.: auch die Werkzeuge auf die HC-Belohnungen -- liegt eine erreichbar im Bild, die Form dorthin.
            int[] nach = {px(ctx, FORM_NACH[0]), py(ctx, FORM_NACH[1])};
            Optional<int[]> hz = hcVorher;
            boolean zumHc = hz.isPresent() && hz.get()[2] == 1;
            // Autor 18.10. (Karla): ohne HC im Bild landete die Form fest in Spalte 3 -- dort war kein aktiver Block,
            // der Bot hing. Jetzt auch ohne HC auf das tiefste erreichbare Feld neben dem offenen Schacht.
            boolean verschoben = hz.isPresent();
            if (verschoben) nach = new int[]{hz.get()[0], hz.get()[1]};
            Log.action("Klick", zumHc
                    ? Sprache.t(WER + ": drag the " + w.name + " shape onto the block towards the HC", WER + ": " + w.name + "-Form auf das Feld zum HC ziehen")
                    : verschoben
                    ? Sprache.t(WER + ": drag the " + w.name + " shape onto the deepest open block", WER + ": " + w.name + "-Form auf das tiefste offene Feld ziehen")
                    : Sprache.t(WER + ": drag the " + w.name + " shape onto the shaft block", WER + ": " + w.name + "-Form auf den Schacht-Block ziehen"));
            ctx.input().ziehenSofort(px(ctx, FORM_VON[0]), py(ctx, FORM_VON[1]), nach[0], nach[1]);
            // Nach dem Ziehen sitzt die Form auf dem aktiven Block -- wo auch immer der gerade ist (14.10.). Auf einem
            // anderen Feld steht der Haken woanders: dann den Haken mit dem roten X darunter suchen (Hauptbot 17.10.).
            haken = verschoben ? hakenMitX(ctx, 1200) : hakenAbwarten(ctx, null, 1000);
            if (haken.isEmpty()) {
                int fehl = werkzeugFehl.merge(w, 1, Integer::sum);
                warnung(Sprache.t(w.name + ": no green check mark on the shaft after dragging (" + fehl + ". time) -- " + "abort (red X)" + (fehl >= 3 ? ", skipping it." : ", trying again shortly."),
                        w.name + ": nach dem Ziehen kein gruener Haken am Schacht (" + fehl + ". Mal) -- "
                        + "abbrechen (rotes X)" + (fehl >= 3 ? ", lasse es aus." : ", gleich noch einmal.")));
                foto(ctx, "werkzeug-ziehen");
                Mat jetzt = ctx.refresh();
                if (jetzt == null || !ctx.clickIfVisibleIfKnown(jetzt, "ev_form_x", w.name + Sprache.t(" cancel (red X)", " abbrechen (rotes X)"))) {
                    tippe(ctx, new int[]{w.haken[0], w.haken[1] + 71}, w.name + Sprache.t(" cancel", " abbrechen"));
                }
                if (fehl >= 3) werkzeugFertig.add(w);
                ctx.sleep(tempo.ms(400));
                return Outcome.ACTED;
            }
        } else if (w == Werkzeug.DRILL && hcAn()) {
            // Hauptbot 17.10. (Autor): Drill auf die Spalte mit den meisten HC -- er geht nur, wenn im Nebenschacht
            // mindestens ein Feld offen ist. Die Form erscheint in Spalte 3 (Haken 649,285); sonst seitwaerts ziehen.
            int sp = drillVorher;
            if (sp >= 0 && sp != 2) {
                Log.action("Klick", Sprache.t(WER + ": drag the drill onto column " + (sp + 1), WER + ": Drill auf Spalte " + (sp + 1) + " ziehen"));
                ctx.input().ziehenSofort(px(ctx, MINE_X[2]), py(ctx, MINE_Y[2]), px(ctx, MINE_X[sp]), py(ctx, MINE_Y[2]));
                haken = hakenMitX(ctx, 1200);
                // Autor 18.10. (Bild): "auf Spalte 6 ziehen" landete eine Spalte links im offenen Schacht. Der gruene Haken
                // steht ~117 Punkte rechts der Bohrer-Spalte (gemessen: Spalte 3 -> Haken 649) -- daran pruefen, nachziehen.
                for (int nach = 0; nach < 2 && haken.isPresent(); nach++) {
                    int ist = haken.get().centerX() - px(ctx, 117), soll = px(ctx, MINE_X[sp]);
                    if (Math.abs(ist - soll) <= px(ctx, 40)) break;
                    Log.action("Klick", Sprache.t(WER + ": drill is at x " + ist + " instead of " + soll + " -- drag again",
                            WER + ": Drill steht bei x " + ist + " statt " + soll + " -- nachziehen"));
                    ctx.input().ziehenSofort(ist, py(ctx, MINE_Y[2]), soll, py(ctx, MINE_Y[2]));
                    haken = hakenMitX(ctx, 1200);
                }
                if (haken.isEmpty()) {
                    int fehl = werkzeugFehl.merge(w, 1, Integer::sum);
                    warnung(Sprache.t("Drill: no green check mark after dragging (" + fehl + ". time) -- abort (red X)"
                                    + (fehl >= 3 ? ", skipping it." : ", trying again shortly."),
                            "Drill: nach dem Ziehen kein gruener Haken (" + fehl + ". Mal) -- abbrechen (rotes X)"
                                    + (fehl >= 3 ? ", lasse es aus." : ", gleich noch einmal.")));
                    foto(ctx, "drill-ziehen");
                    Mat jetzt = ctx.refresh();
                    if (jetzt == null || !ctx.clickIfVisibleIfKnown(jetzt, T_FORM_X, Sprache.t("Drill: cancel (red X)", "Drill abbrechen (rotes X)"))) {
                        tippe(ctx, new int[]{w.haken[0], w.haken[1] + 73}, Sprache.t("Drill: cancel", "Drill abbrechen"));
                    }
                    if (fehl >= 3) werkzeugFertig.add(w);
                    ctx.sleep(tempo.ms(400));
                    return Outcome.ACTED;
                }
            }
        }
        Log.action("Klick", Sprache.t(WER + ": " + w.name + ": green check mark", WER + ": " + w.name + ": gruener Haken"));
        ctx.input().tippeSofort(haken.get().centerX(), haken.get().centerY());
        letzterHakenUm = System.currentTimeMillis();
        werkzeugVersuche = 0;
        werkzeugFehl.remove(w);   // Titan-Idee 13: Fehler zaehlen je Einsatz, nicht fuer den ganzen Besuch
        int benutzt = werkzeugBenutzt.merge(w, 1, Integer::sum);
        if (w == Werkzeug.DRILL && drillGrenze() > 0 && benutzt >= drillGrenze()) {
            Log.info(WER, Sprache.t("Drill: limit of " + drillGrenze() + " reached (titan-drill.txt).",
                    "Drill: Grenze von " + drillGrenze() + " erreicht (titan-drill.txt)."));
            werkzeugFertig.add(Werkzeug.DRILL);
        }
        runs.progress(w.name);
        // Autor, 09.10. 22:22: "nach dem gruenen Haken laeuft nur die Animation aus, da kann er gleich das naechste
        // auswaehlen". Vorher: mind. 0,9 s auf "You have obtained" + bis 2 s auf eine Bildaenderung (Protokoll: meist
        // die vollen 2 s) -- 2,1-4 s je Werkzeug. Jetzt gleich weiter: Claim nimmt der naechste Schritt (er prueft
        // zuerst, ob das Minenbild frei ist), und ein Haken, der wegen der laufenden Animation nicht kommt, zaehlt
        // nicht als Fehlversuch (siehe oben).
        ctx.sleep(tempo.ms(150));
        return Outcome.ACTED;
    }

    /**
     * Drill nur kurz vor Event-Ende (Autor, 09.10.: "wenn oben nur noch 12 Stunden steht ... Drills benutzen,
     * wenn keine Spitzhacken mehr da sind" -- er dauert zu lange). Restzeit oben links ("Event ends in:
     * 9d 22h 37m"), oder von Hand mit titan-drill.txt "an".
     */
    private boolean drillErlaubt(BotContext ctx) {
        restLesen(ctx);   // merkt das Event-Ende fuer den Endspurt der Kathedrale
        // Autor, 09.10. 22:50: Drills NUR auf seine Ansage ("!titan drill an"), sonst nie -- gemessen 3,5 s je Meter,
        // Kathedrale + Spitzhacken 2,4 s; nach den 10 Tagen gibt es noch 2 Tage, in denen sie verbraucht werden koennen.
        // Autor 21.10.: in den zwei Tagen nach dem Event (rote Zeit oben links) zaehlen die Meter nicht mehr -- dann alles verbrauchen.
        endphasePruefen(ctx);
        return drillVonHand() || endphase;
    }

    /**
     * Restzeit oben links lesen -- zwei Lesungen (normal und dreifach vergroessert): 09.10. las die normale "22h 27m"
     * statt "9d 22h 27m", das "9d" fehlte. Die laengere gilt; "einig" nur, wenn BEIDE etwas lesen. Das Ende wird fuer
     * den Endspurt der Kathedrale gemerkt (Titanende).
     *
     * @return {Minuten oder -1, 1 = beide Lesungen da}
     */
    private long[] restLesen(BotContext ctx) {
        int x = px(ctx, RESTZEIT[0]), y = py(ctx, RESTZEIT[1]), w = px(ctx, RESTZEIT[2]), h = py(ctx, RESTZEIT[3]);
        String a = ArenaLeser.liesText(ctx.frame(), x, y, w, h), b = ArenaLeser.liesTextGross(ctx.frame(), x, y, w, h);
        long ra = restMinuten(a), rb = restMinuten(b);
        long rest = ra < 0 ? rb : rb < 0 ? ra : Math.max(ra, rb);
        boolean einig = ra >= 0 && rb >= 0;
        if (rest >= 0) {
            Log.info(WER, Sprache.t("Event ends in " + (rest / 60) + " h " + (rest % 60) + " min (read: \"" + (a == null ? "" : a.replace('\n', ' ')) + "\" / \"" + (b == null ? "" : b.replace('\n', ' ')) + "\").",
                    "Event endet in " + (rest / 60) + " h " + (rest % 60) + " min (gelesen: \""
                    + (a == null ? "" : a.replace('\n', ' ')) + "\" / \"" + (b == null ? "" : b.replace('\n', ' ')) + "\")."));
            if (einig) de.hustlebot.core.Titanende.setzen(rest);
        }
        return new long[]{rest, einig ? 1 : 0};
    }

    /** Restzeit oben links: "9d 22h 37m" -> Minuten; -1, wenn nichts Lesbares da steht. */
    static long restMinuten(String text) {
        if (text == null) return -1;
        String t = text.toLowerCase().replace('o', '0');
        java.util.regex.Matcher d = java.util.regex.Pattern.compile("(\\d+)\\s*d").matcher(t);
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("(\\d+)\\s*h").matcher(t);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*m").matcher(t);
        boolean dd = d.find(), hh = h.find(), mm = m.find();
        if (!dd && !hh && !mm) return -1;
        return (dd ? Long.parseLong(d.group(1)) * 1440 : 0) + (hh ? Long.parseLong(h.group(1)) * 60 : 0)
                + (mm ? Long.parseLong(m.group(1)) : 0);
    }

    /** Alle 0,1 s nach dem gruenen Haken sehen statt fest zu warten -- hoechstens bisMs lang. */
    private static Optional<Matcher.Hit> hakenAbwarten(BotContext ctx, int[] erwartet, long bisMs) {
        long start = System.currentTimeMillis();
        while (true) {
            ctx.sleep(100);
            Optional<Matcher.Hit> h = hakenSuchen(ctx, erwartet);
            if (h.isPresent() || System.currentTimeMillis() - start >= bisMs || Thread.currentThread().isInterrupted()) return h;
        }
    }

    private static boolean belohnungDa(BotContext ctx) {
        if (!ctx.matcher().has(T_CLAIM)) return false;
        Mat f = ctx.refresh();
        return f != null && ctx.find(f, T_CLAIM).isPresent();
    }

    /**
     * Hoechstens so viele Drills je Besuch: Zahl hinter "an" in titan-drill.txt ("an 500"), sonst unbegrenzt (-1).
     * Autor 21.10.: "von den 3700 Bohrern nur 500 benutzen -- es soll nur gut funktionieren."
     */
    static int drillGrenze() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("titan-drill.txt");
            if (!java.nio.file.Files.exists(p)) return -1;
            for (String z : de.hustlebot.core.Dateien.zeilen(p)) {
                String t = z.strip().toLowerCase();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] w = t.split("\\s+");
                return w.length > 1 && w[1].matches("\\d{1,6}") ? Integer.parseInt(w[1]) : -1;
            }
        } catch (Exception e) {
            Log.debug(WER, "titan-drill.txt: " + e.getMessage());
        }
        return -1;
    }

    private static boolean drillVonHand() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("titan-drill.txt");
            // Autor 18.10.: im Freunde-Bot die Bohrer standardmaessig mitbenutzen (aus nur per Schalter).
            if (!java.nio.file.Files.exists(p)) return true;
            for (String z : de.hustlebot.core.Dateien.zeilen(p)) {
                String t = z.strip().toLowerCase();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return t.startsWith("an") || t.startsWith("on");
            }
        } catch (Exception e) {
            Log.debug(WER, "titan-drill.txt: " + e.getMessage());
        }
        return false;
    }

    /** Gelber "Watch"-Knopf statt der Zahl (0 Stueck): viele kraeftig gelbe Punkte im Kasten. */
    private static boolean watchKnopf(BotContext ctx, BufferedImage b, int[] k) {
        if (b == null) return false;
        int x0 = px(ctx, k[0]), y0 = py(ctx, k[1]), x1 = Math.min(b.getWidth(), x0 + px(ctx, k[2]));
        int y1 = Math.min(b.getHeight(), y0 + py(ctx, k[3])), gelb = 0;
        for (int y = Math.max(0, y0); y < y1; y++) {
            for (int x = Math.max(0, x0); x < x1; x++) {
                int p = b.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                if (r > 200 && g > 150 && bl < 90) gelb++;
            }
        }
        return gelb > 400;
    }

    /**
     * Gruener Haken des Auswahlfelds: frisches Bild, Vorlage ev_titan_haken_ok, und der Treffer muss nahe der
     * erwarteten Stelle liegen (hoechstens 60 Bildpunkte daneben) -- sonst leer.
     */
    /**
     * Wo der gruene Haken der Form ueberhaupt sitzen kann (x, y, Breite, Hoehe): rechts neben dem Schacht,
     * von der obersten bis zur untersten Block-Zeile. Autor, 13.10. (Punkt 10): "Super-Spitzhacke hakt
     * manchmal -- wird leicht verschoben, dann kommt er nicht weiter." Fotos 13.10. 01:06 und 14.10. 05:46:
     * Die Form springt im Spiel VON SELBST auf den aktiven Block ("You can only aim at an active block"),
     * und dessen Zeile wandert mit der Grabtiefe (y 430, 530 oder 622). Der Haken sitzt immer rechts oben an
     * der Form -- also mal bei y 284, mal 374, mal 469. Der Bot erwartete ihn fest bei 469, fand ihn nicht,
     * drueckte das rote X und liess das Werkzeug fuer den ganzen Besuch aus.
     */
    private static final int[] HAKEN_BEREICH = {690, 230, 110, 300};

    /**
     * @param erwartet feste Stelle (Drill: eigenes Fenster) -- oder null: irgendwo im HAKEN_BEREICH
     */
    private static Optional<Matcher.Hit> hakenSuchen(BotContext ctx, int[] erwartet) {
        if (!ctx.matcher().has(T_HAKEN_OK)) return Optional.empty();
        // Ganzes Bild absuchen: die Kastensuche merkt sich die Stelle VOR dem Ziehen (742,285) und haette den
        // Haken danach 180 Bildpunkte tiefer nie gefunden (09.10., 12:32: Form richtig, Haken nicht gedrueckt).
        ctx.matcher().naechstesBildGruendlich();
        Mat frisch = ctx.refresh();
        if (frisch == null) return Optional.empty();
        if (erwartet == null) {
            // Review 14.10.: NUR im Bereich suchen -- der beste Treffer im ganzen Bild kann ein Haeckchen auf einem
            // eingesammelten Fund sein, dann wurde der echte Haken neben dem Schacht nie gefunden.
            int rand = 40;
            int x = Math.max(0, px(ctx, HAKEN_BEREICH[0]) - rand), y = Math.max(0, py(ctx, HAKEN_BEREICH[1]) - rand);
            int w = Math.min(frisch.cols() - x, px(ctx, HAKEN_BEREICH[2]) + 2 * rand);
            int hh = Math.min(frisch.rows() - y, py(ctx, HAKEN_BEREICH[3]) + 2 * rand);
            if (w <= 0 || hh <= 0) return Optional.empty();
            return ctx.matcher().findImBereich(frisch, T_HAKEN_OK, new org.opencv.core.Rect(x, y, w, hh),
                    de.hustlebot.core.Matcher.DEFAULT_THRESHOLD);
        }
        Optional<Matcher.Hit> h = ctx.find(frisch, T_HAKEN_OK);
        if (h.isEmpty()) return h;
        int dx = h.get().centerX() - px(ctx, erwartet[0]), dy = h.get().centerY() - py(ctx, erwartet[1]);
        return dx * dx + dy * dy <= 60 * 60 ? h : Optional.empty();
    }

    /** Kann die gelesene Zahl stimmen? Steigen darf sie um hoechstens 2000 (Boss), sinken nur so schnell wie getippt. */
    private boolean plausibel(long neu) {
        if (hacken < 0) return true;
        if (neu > hacken + 2000) return false;
        long hoechstens = tippsSeitLesung / 10 + 30;   // gemessen ~26 Tipps je Hacke; grosszuegig
        return neu >= hacken - hoechstens;
    }

    /**
     * Notausgang-Leiter (Titan-Idee 4): Stufe fuer Stufe aus einem unbekannten Zustand -- Claim/Ergebnisbild, rotes
     * X (nicht das des Events), Reiter Mine, dann Event zu und ueber den Kalender neu hinein. Hoechstens alle 3 s.
     */
    private Outcome notausgang(Mat screen, BotContext ctx, String grund) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - notausgangUm < 3000) {
            ctx.sleep(150);
            return Outcome.WAITING;
        }
        notausgangUm = jetzt;
        int stufe = notausgangStufe++ % 4;
        foto(ctx, "notausgang");
        warnung(Sprache.t("Emergency exit level " + (stufe + 1) + " (" + grund + ").",
                "Notausgang Stufe " + (stufe + 1) + " (" + grund + ")."));
        switch (stufe) {
            case 0 -> {
                if (!klickSofort(screen, ctx, T_CLAIM, "Notausgang: Claim")
                        && !klickSofort(screen, ctx, T_VORBEI, "Notausgang: Ergebnisbild")) {
                    tippe(ctx, GRABEN_HIER, Sprache.t("Emergency exit: onto the dig site (= Claim)", "Notausgang: auf die Grabstelle (= Claim)"));
                }
            }
            case 1 -> {
                Optional<Matcher.Hit> x = ctx.matcher().has("close_x") ? ctx.find(screen, "close_x") : Optional.empty();
                if (x.isPresent() && !nahe(ctx, x.get(), EVENT_X, 12)) {
                    Log.action("Klick", WER + ": Notausgang: rotes X  [" + x.get().centerX() + "," + x.get().centerY() + "]");
                    ctx.input().tippeSofort(x.get().centerX(), x.get().centerY());
                }
            }
            case 2 -> tippe(ctx, REITER_MINE, Sprache.t("Emergency exit: Mine tab", "Notausgang: Reiter Mine"));
            default -> {
                tippe(ctx, EVENT_X, Sprache.t("Emergency exit: close the event, back in via the calendar", "Notausgang: Event zu, ueber den Kalender neu hinein"));
                mineGeklickt = false;
                letzteHackeUm = jetzt;   // neue Frist fuer den naechsten Anlauf
            }
        }
        ctx.sleepUntilChanged(800);
        return Outcome.ACTED;
    }

    /**
     * Alle 1000 verbrauchten Spitzhacken eine Zeile nach Discord (Autor, 09.10.: "nicht nach jeder Grabung, das
     * waeren 150.000 Meldungen") und nach logs/titan-1000.txt. Freunde (Autor, 09.10.): NUR die Zeiten, kein
     * Belohnungs-Zaehler, kein !beute.
     */
    private void tausenderMelden() {
        if (verbraucht - tausenderMarke < 1000) return;
        long jetzt = System.currentTimeMillis();
        long weg = verbraucht - tausenderMarke;
        double s = (jetzt - tausenderUm) / 1000.0;
        String zeile = String.format(java.util.Locale.GERMANY, de.hustlebot.core.Sprache.t(
                "%d pickaxes in %.0f s -- %.2f s per pickaxe (%.2f per s). Total %d, left %d.",
                "%d Spitzhacken in %.0f s -- %.2f s je Spitzhacke (%.2f je s). Insgesamt %d, uebrig %d."),
                weg, s, s / Math.max(1, weg), weg / Math.max(0.1, s), verbraucht, Math.max(0, hacken));
        Log.hinweis(de.hustlebot.core.Sprache.t("Titan excavation", "Titan-Grabung"), zeile);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "titan-1000.txt"),
                    java.time.LocalDateTime.now().withNano(0) + "  " + zeile + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug(WER, "titan-1000.txt: " + e.getMessage());
        }
        tausenderMarke = verbraucht;
        tausenderUm = jetzt;
        tempoRundeFertig();
    }

    /** Alle 100 verbrauchten Spitzhacken: Zeit und Tipps ins Protokoll (Freunde: ohne Spielalter und Neustart). */
    private void tempoMessen() {
        if (markeHacken < 0 || markeHacken - hacken < 100) return;
        long jetzt = System.currentTimeMillis();
        long weg = markeHacken - hacken;
        double s = (jetzt - markeUm) / 1000.0;
        double sJe = s / Math.max(1, weg);
        String zeile = String.format(java.util.Locale.GERMANY,
                Sprache.t("%d pickaxes in %.1f s (%d taps, %.2f pickaxes/s, %.3f s per pickaxe, hold %d ms, pause %d ms)", "%d Spitzhacken in %.1f s (%d Tipps, %.2f Hacken/s, %.3f s je Hacke, halten %d ms, Pause %d ms)"),
                weg, s, tippsSeitMarke, weg / Math.max(0.1, s), sJe, haltenMs, pauseMs);
        Log.info(WER, Sprache.t("Speed: " + zeile, "Tempo: " + zeile));
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "titan-tempo.txt"),
                    java.time.LocalDateTime.now().withNano(0) + "  " + zeile + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug(WER, "titan-tempo.txt: " + e.getMessage());
        }
        markeHacken = hacken;
        markeUm = jetzt;
        tippsSeitMarke = 0;
    }

    /** Letzte Auswertung der Rangliste (Platz 25, eigene Meter, Faktor) -- fuer die Bildunterschrift in Discord. */
    private static volatile String ranglisteText = "";

    public String ranglisteText() {
        return ranglisteText;
    }

    /** Fuer die Tages-Rangliste: die Auswertung des letzten Bildes (egal welche Grabung es machte). */
    public static String letzterRanglisteText() {
        return ranglisteText;
    }

    // ------------------------------------------------------------------ Tages-Rangliste
    // Autor, 10.10.: "jeden Tag puenktlich um 0 Uhr, falls der Bot da im Event ist, sonst das naechste Mal, wenn er
    // nach 0 Uhr ins Event geht, einen Screenshot vom Top-25-Placement und ausrechnen, wie viel mehr Meter ich habe".
    // Gilt fuer die Grabung allein (!graben) und den Titan-Besuch in der Kathedrale -- darum statisch.
    private static volatile java.util.function.Consumer<byte[]> tagesZiel;
    private static final java.nio.file.Path TAGES_DATEI = java.nio.file.Path.of("titan-rangliste-tag.txt");
    private static volatile String tagesErledigt = tagesLaden();

    /** Von Discord beim Start: wohin das Tagesbild geht. */
    public static void tagesRanglisteZiel(java.util.function.Consumer<byte[]> ziel) {
        tagesZiel = ziel;
    }

    private static String tagesLaden() {
        try {
            if (java.nio.file.Files.exists(TAGES_DATEI)) return de.hustlebot.core.Dateien.text(TAGES_DATEI).trim();
        } catch (Exception e) {
            Log.debug(WER, TAGES_DATEI + ": " + e.getMessage());
        }
        return "";
    }

    /** Zeitfenster der Rangliste: "2026-10-11T00" (ab 0 Uhr) oder "2026-10-11T12" (ab 12 Uhr) -- Autor, 10.10. */
    private static String ranglistenFenster() {
        java.time.LocalDateTime j = java.time.LocalDateTime.now();
        return j.toLocalDate() + (j.getHour() >= 12 ? "T12" : "T00");
    }

    private static boolean tagesRanglisteFaellig() {
        return tagesZiel != null && !ranglistenFenster().equals(tagesErledigt);
    }

    private static void tagesRanglisteErledigt() {
        tagesErledigt = ranglistenFenster();
        try {
            java.nio.file.Files.writeString(TAGES_DATEI, tagesErledigt);
        } catch (Exception e) {
            Log.debug(WER, TAGES_DATEI + ": " + e.getMessage());
        }
    }

    private Outcome ranglisteZeigen(BotContext ctx) {
        var ziel = ranglisteAn;
        ranglisteAn = null;
        // 10.10. 19:22: "Special reward" (1 Spitzhacke + 10 Traenke) kam erst nach dem letzten Dynamit und lag ueber der
        // Rangliste -- das Bild zeigte nur das Belohnungsfenster. Darum vorher Claim nehmen, und kam es waehrend des
        // Ziehens, das Ganze einmal wiederholen.
        for (int versuch = 0; versuch < 2; versuch++) {
            claimWeg(ctx);
            tippe(ctx, REITER_RANGLISTE, Sprache.t("Rankings tab (image for Discord)", "Reiter Rankings (Bild fuer Discord)"));
            ctx.sleep(1500);
            // Autor, 10.10.: "Ziel ist eher in Top 25 zu bleiben ... ein Screenshot von Top 25 reicht, da unten steht ja
            // sowieso meine Platzierung." Gemessen 13:45: 5 Zuege nach oben zeigen Platz 21-26. Gezogen wird auf der freien
            // Flaeche rechts (x 880): ein Zug ueber Namen/Wappen (x 580) oeffnete das Clan-Fenster mit "Leave clan".
            for (int i = 0; i < 5; i++) {
                ctx.input().drag(px(ctx, 880), py(ctx, 540), px(ctx, 880), py(ctx, 270));
                ctx.sleep(900);
            }
            ctx.sleep(300);
            if (!claimWeg(ctx)) break;
        }
        BufferedImage bild = ctx.frischesFoto();
        ranglisteText = "";
        try {
            if (bild != null) ranglisteText = ranglisteAuswerten(ctx, bild);
            if (bild != null && ziel != null) {
                byte[] png = de.hustlebot.core.ScreenCapture.toPreviewPng(bild, 1200);
                ziel.accept(png);
                // Kopie fuer Claude (10.10.: Autor will das Bild auch im Chat, Discord kann Claude nicht lesen)
                if (de.hustlebot.core.Beleg.an()) java.nio.file.Files.write(java.nio.file.Path.of("logs", "titan-rangliste.png"), png);
            }
        } catch (Exception e) {
            Log.debug(WER, "Ranglisten-Bild: " + e.getMessage());
        }
        mineGeklickt = false;
        return Outcome.ACTED;
    }

    /**
     * Platz 25 und die eigene Zeile aus dem Ranking-Bild lesen (Autor, 10.10.: "wie viel mehr, in Mal gerechnet, habe
     * ich als Platz 25 -- dass wir es mitverfolgen koennen"). Die Rang-Kaestchen (x 70) sind hell, dazwischen dunkle
     * Fugen; je volle Zeile (>= 40 px) Platz (x 40-102) und Meter (x 900-1070) lesen. Die eigene Zeile haengt unten
     * fest (y 645-687). Ergebnis auch in logs\titan-rangliste.txt.
     */
    private String ranglisteAuswerten(BotContext ctx, BufferedImage b) {
        java.util.Map<Integer, Long> meter = new java.util.TreeMap<>();
        int x = px(ctx, 70), yEnde = Math.min(b.getHeight() - 1, py(ctx, 635));
        int start = -1;
        for (int y = py(ctx, 255); y <= yEnde; y++) {
            int p = b.getRGB(x, y);
            boolean zeile = ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255) > 330;
            if (zeile && start < 0) start = y;
            if ((!zeile || y == yEnde) && start >= 0) {
                if (y - start >= py(ctx, 40)) {
                    long platz = nurZiffern(ArenaLeser.liesTextGross(b, px(ctx, 40), start, px(ctx, 62), y - start));
                    long m = nurZiffern(ArenaLeser.liesTextGross(b, px(ctx, 900), start, px(ctx, 170), y - start));
                    if (platz > 0 && platz < 1000 && m > 0) meter.put((int) platz, m);
                }
                start = -1;
            }
        }
        long meinPlatz = nurZiffern(ArenaLeser.liesTextGross(b, px(ctx, 40), py(ctx, 645), px(ctx, 62), py(ctx, 42)));
        long mein = nurZiffern(ArenaLeser.liesTextGross(b, px(ctx, 900), py(ctx, 645), px(ctx, 170), py(ctx, 42)));
        Integer vergleich = meter.containsKey(25) ? Integer.valueOf(25)
                : meter.keySet().stream().min(java.util.Comparator.comparingInt(k -> Math.abs(k - 25))).orElse(null);
        java.util.Locale de = java.util.Locale.GERMANY;
        if (vergleich == null || mein <= 0) {
            Log.info(WER, Sprache.t("Rankings: ranks not readable (" + meter + ", own meters " + mein + ").",
                    "Rangliste: Plaetze nicht lesbar (" + meter + ", eigene Meter " + mein + ")."));
            return "";
        }
        long m25 = meter.get(vergleich);
        double faktor = mein / (double) Math.max(1, m25);
        String text = String.format(de, Sprache.t("Rank %d: %,d m -- you: rank %d, %,d m -- %.1f times as much", "Platz %d: %,d m -- du: Platz %d, %,d m -- %.1f-mal so viel"), vergleich, m25,
                meinPlatz, mein, faktor);
        Log.info(WER, Sprache.t("Rankings: " + text, "Rangliste: " + text));
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "titan-rangliste.txt"),
                    java.time.LocalDateTime.now().withNano(0) + ";platz=" + meinPlatz + ";meter=" + mein + ";platz" + vergleich
                            + "=" + m25 + ";faktor=" + String.format(java.util.Locale.ROOT, "%.2f", faktor) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug(WER, "titan-rangliste.txt: " + e.getMessage());
        }
        return text;
    }

    private static long nurZiffern(String s) {
        if (s == null) return -1;
        String z = s.replaceAll("[^0-9]", "");
        if (z.isEmpty() || z.length() > 9) return -1;
        return Long.parseLong(z);
    }

    // ============================================================ ausserhalb
    /** Titel nicht zu sehen: Belohnungsbild, Angebot -- oder wir sind gar nicht im Event. */
    private Outcome draussen(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        boolean inDerBurg = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
        if (!inDerBurg && jetzt - letztesEventBildUm < 6000) {
            // Eben noch im Event: ein Angebot hat ein rotes X, die Belohnungsbilder ("You have obtained",
            // "Special reward") haben ihr "Claim" genau auf der Grabstelle.
            // 09.10., 13:10: Nach "Truppenfenster zu" hielt der Bot den Uebergang fuer ein Angebot, drueckte das X
            // des EVENTS und tippte danach in der Burg auf X-aehnliche Stellen. Darum: erst nach 1,2 s ohne
            // Eventbild gilt etwas als Angebot, und das X des Events / des Kalenders zaehlt nie als Angebot.
            if (jetzt - letztesEventBildUm >= 1200) {
                Optional<Matcher.Hit> x = ctx.matcher().has("close_x") ? ctx.find(screen, "close_x") : Optional.empty();
                if (x.isPresent() && !nahe(ctx, x.get(), EVENT_X, 12) && !nahe(ctx, x.get(), KALENDER_X, 12)) {
                    Log.action("Klick", Sprache.t(WER + ": close the offer (never buy)  [close_x at " + x.get().centerX() + "," + x.get().centerY() + "]",
                            WER + ": Angebot zu (nie kaufen)  [close_x bei " + x.get().centerX() + "," + x.get().centerY() + "]"));
                    ctx.input().tippeSofort(x.get().centerX(), x.get().centerY());
                    ctx.sleepUntilChanged(800);
                    return Outcome.ACTED;
                }
            }
            if (phase == Phase.GRABEN) {
                // Nur beim Graben blind auf die Grabstelle (= Claim); beim Boss liegt X10 nur 15 px daneben.
                ctx.input().dauerklick(px(ctx, GRABEN_HIER[0]), py(ctx, GRABEN_HIER[1]), 2, haltenMs, 60);
            }
            ctx.sleep(tempo.ms(120));
            return Outcome.WAITING;
        }
        if (Stoerfenster.belohnungWegklicken(screen, ctx, WER)) return Outcome.ACTED;
        // Update / Spielfehler / anderes Geraet (Titan-Idee 3) -- der Motor prueft das in der Kathedrale nicht
        // selbst, und dort laeuft das Titan-Event als Einschub.
        if (Stoerfenster.afkUpdate(screen, ctx, WER)) return Outcome.ACTED;
        switch (Stoerfenster.anderesGeraet(screen, ctx, WER)) {   // Spieler selbst im Spiel: anhalten (Hauptbot 62b1968)
            case ANHALTEN -> { return Outcome.PAUSE; }
            case GEKLICKT -> { return Outcome.ACTED; }
            default -> { }
        }   // Freunde: afkUpdate wartet selbst 20 s
        switch (Stoerfenster.anderesGeraet(screen, ctx, WER)) {
            case ANHALTEN -> { return Outcome.PAUSE; }
            case GEKLICKT -> { return Outcome.ACTED; }
            default -> { }
        }
        return zumEventGehen(screen, ctx, runs);
    }

    /** Event mit dem roten X oben rechts schliessen (Truppen-/Ergebnisfenster vorher zu). */
    private Outcome eventZumachen(Mat screen, BotContext ctx, boolean truppe, boolean vorbei, String grund) {
        if (vorbei) {
            tippe(ctx, HOME, Sprache.t("Close the boss result (" + grund + ")", "Boss-Ergebnis zu (" + grund + ")"));
        } else if (truppe) {
            tippe(ctx, TRUPPE_ZU, Sprache.t("Close the squad window (" + grund + ")", "Truppenfenster zu (" + grund + ")"));
        } else if (!klickSofort(screen, ctx, "close_x", Sprache.t("Close the event (" + grund + ")", "Event zu (" + grund + ")"))) {
            warnung(Sprache.t("Red X not found -- the event stays open.", "Rotes X nicht gefunden -- Event bleibt offen."));
            fertig = phase == Phase.ENDE;
            return Outcome.WAITING;
        } else if (phase == Phase.ENDE) {
            kalenderZu = true;   // danach kommt "Active events" -- erst das schliessen, dann fertig
            if (endeGrund == null) endeGrund = "Titan-Grabung: " + bericht();
        }
        ctx.sleepUntilChanged(1200);
        return Outcome.ACTED;
    }

    /**
     * Nach dem Event kommt das Fenster "Active events" (Kalender) -- Autor, 09.10.: "einfach mit dem X oben in
     * der Ecke wegdruecken, das X ist im Kalender immer an der gleichen Stelle" (1089,114 in seiner Aufnahme).
     * Kastensuche: das rote X muss dort liegen (hoechstens 30 Bildpunkte daneben).
     */
    private Outcome kalenderSchliessen(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> x = ctx.matcher().has("close_x") ? ctx.find(screen, "close_x") : Optional.empty();
        if (x.isPresent()) {
            int dx = x.get().centerX() - px(ctx, KALENDER_X[0]), dy = x.get().centerY() - py(ctx, KALENDER_X[1]);
            if (dx * dx + dy * dy <= 30 * 30) {
                tippe(ctx, KALENDER_X, Sprache.t("Close the \"Active events\" window", "Fenster \"Active events\" zu"));
                fertig = true;
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
        }
        if (++kalenderZuVersuche >= 4) {
            fertig = true;   // kein Kalender zu sehen -- schon zu oder gar nicht gekommen
            return Outcome.ACTED;
        }
        ctx.sleep(300);
        return Outcome.WAITING;
    }

    private Outcome aufhoeren(String grund) {
        warnung(Sprache.t("Stopping: " + grund + ". " + bericht(), "Hoere auf: " + grund + ". " + bericht()));
        endeGrund = Sprache.t("Titan excavation aborted: " + grund, "Titan-Grabung abgebrochen: " + grund);
        phase = Phase.ENDE;
        return Outcome.ACTED;
    }

    private String bericht() {
        String s = "Boss " + angriffeX10 + "x X10" + (angriffeX1 > 0 ? ", " + angriffeX1 + "x X1" : "");
        if (hackenAmAnfang >= 0) s += Sprache.t(", pickaxes ", ", Spitzhacken ") + hackenAmAnfang + " -> " + Math.max(hacken, 0);
        s += ", " + tipps + " Tipps";
        if (grabenMs > 0) s += String.format(java.util.Locale.GERMANY, " in %.0f s", grabenMs / 1000.0);
        if (hcGeholt > 0) {
            s += Sprache.t(", HC collected " + hcGeholt, ", HC geholt " + hcGeholt);
            if (hackenFuerHc > 0) s += String.format(java.util.Locale.GERMANY, Sprache.t(" (%.1f pickaxes each)", " (%.1f Spitzhacken je HC)"),
                    hackenFuerHc / (double) hcGeholt);
            if (wegeAufgegeben > 0) s += Sprache.t(", targets lost " + wegeAufgegeben, ", Ziele verloren " + wegeAufgegeben);
        }
        return s;
    }

    // ============================================================ Weg ins Event
    /** Burg -> Kalender -> Zeile "Titanic Excavation" -> "View". */
    private Outcome zumEventGehen(Mat screen, BotContext ctx, RunTracker runs) {
        if (System.currentTimeMillis() - eintragGeklicktUm < 15_000) {
            Optional<Matcher.Hit> view = ctx.find(screen, T_VIEW);
            if (view.isPresent()) {
                Log.action("Klick", WER + ": Event oeffnen (View)");
                ctx.input().tippeSofort(view.get().centerX(), view.get().centerY());
                eintragGeklicktUm = 0;
                kalenderVersuche = 0;
                if (++eintritte > 10) return aufgeben(Sprache.t("entered the event 10 times without arriving", "10 Mal ins Event, ohne anzukommen"));
                runs.progress(Sprache.t("Event opened", "Event geoeffnet"));
                ctx.sleepUntilChanged(2500);
                return Outcome.ACTED;
            }
        }
        if (klickSofort(screen, ctx, T_KALENDER_EINTRAG, Sprache.t("choose \"Titanic Excavation\" in the calendar", "im Kalender \"Titanic Excavation\" waehlen"))) {
            eintragGeklicktUm = System.currentTimeMillis();
            fremdeBilder = 0;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (klickSofort(screen, ctx, T_KALENDER, Sprache.t("Open the event calendar", "Event-Kalender oeffnen"))) {
            if (++kalenderVersuche > 6) return aufgeben(Sprache.t("Calendar opened 6 times, row or View not found", "Kalender 6 Mal offen, Zeile oder View nicht gefunden"));
            fremdeBilder = 0;
            zuege = 0;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        boolean inDerBurg = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
        if (!inDerBurg && (Weltkarte.erkannt(screen, ctx) || ctx.visibleIfKnown(screen, Weltkarte.T_HOME)) && zuege < 16) {
            zuege++;
            Weltkarte.heim(screen, ctx, WER);
            return Outcome.ACTED;
        }
        if (!inDerBurg) {
            // Erst nach ein paar fremden Bildern das rote X -- eine Animation dauert ein paar Bilder.
            if (++fremdeBilder >= MAX_FREMDE_BILDER
                    && ctx.clickIfVisibleIfKnown(screen, "close_x", WER + Sprache.t(": close the foreign window", ": fremdes Fenster schliessen"))) {
                fremdeBilder = 0;
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            ctx.sleep(300);
            return Outcome.UNKNOWN;
        }
        fremdeBilder = 0;
        if (zuege >= 16) return aufgeben(Sprache.t("Calendar icon not found after 16 moves", "Kalender-Symbol nach 16 Zuegen nicht gefunden"));
        int w = ctx.width(), h = ctx.height(), mx = w / 2, my = h / 2, dx = (int) (w * 0.30), dy = (int) (h * 0.30);
        int[][] richtungen = {{mx - dx, my, mx + dx, my}, {mx + dx, my, mx - dx, my},
                              {mx, my + dy, mx, my - dy}, {mx, my - dy, mx, my + dy}};
        int[] z = richtungen[(zuege / 4) % richtungen.length];
        zuege++;
        Log.action(WER, Sprache.t("Moving the castle to find the calendar icon (" + zuege + " of 16)",
                "Burg verschieben, um das Kalender-Symbol zu finden (" + zuege + " von 16)"));
        ctx.input().drag(z[0], z[1], z[2], z[3]);
        ctx.sleepUntilChanged(1200);
        return Outcome.ACTED;
    }

    private Outcome aufgeben(String grund) {
        warnung(Sprache.t("Giving up: " + grund + ". " + bericht(), "Gebe auf: " + grund + ". " + bericht()));
        endeGrund = Sprache.t("Titan excavation aborted: " + grund, "Titan-Grabung abgebrochen: " + grund);
        fertig = true;
        return Outcome.ACTED;
    }

    // ================================================================= Helfer
    private static boolean sicht(Mat screen, BotContext ctx, String vorlage) {
        return ctx.visibleIfKnown(screen, vorlage);
    }

    private static boolean nahe(BotContext ctx, Matcher.Hit h, int[] stelle, int abstand) {
        int dx = h.centerX() - px(ctx, stelle[0]), dy = h.centerY() - py(ctx, stelle[1]);
        return dx * dx + dy * dy <= abstand * abstand;
    }

    /** Vorlage suchen und sofort draufklicken (ohne Hingleiten). */
    /** Belohnungsfenster ("You have obtained") wegdruecken, bis zu dreimal. true, wenn eins da war. */
    private static boolean claimWeg(BotContext ctx) {
        boolean da = false;
        for (int i = 0; i < 3; i++) {
            if (!klickSofort(ctx.refresh(), ctx, T_CLAIM, Sprache.t("Accept the reward (Claim) before the rankings", "Belohnung annehmen (Claim) vor der Rangliste"))) break;
            da = true;
            ctx.sleep(1200);
        }
        return da;
    }

    private static boolean klickSofort(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.matcher().has(vorlage)) return false;
        Optional<Matcher.Hit> h = ctx.find(screen, vorlage);
        if (h.isEmpty()) return false;
        Log.action("Klick", Sprache.t(WER + ": " + grund + "  [" + vorlage + " at " + h.get().centerX() + "," + h.get().centerY() + "]",
                WER + ": " + grund + "  [" + vorlage + " bei " + h.get().centerX() + "," + h.get().centerY() + "]"));
        ctx.input().tippeSofort(h.get().centerX(), h.get().centerY());
        return true;
    }

    private static int px(BotContext ctx, int x) {
        int w = ctx.width();
        return w > 0 ? (int) Math.round(x * (double) w / BREITE) : x;
    }

    private static int py(BotContext ctx, int y) {
        int h = ctx.height();
        return h > 0 ? (int) Math.round(y * (double) h / HOEHE) : y;
    }

    private static void tippe(BotContext ctx, int[] stelle, String grund) {
        int x = px(ctx, stelle[0]), y = py(ctx, stelle[1]);
        Log.action("Klick", WER + ": " + grund + "  [" + x + "," + y + "]");
        ctx.input().tippeSofort(x, y);   // Autor, 09.10.: im Event sofort draufklicken, nicht hingleiten
    }

    /**
     * Ist die Zahl im Kasten rot (zu wenig Tickets)? Entscheidend sind die WEISSEN Ziffern: Nachgemessen
     * 09.10. -- normaler Knopf 314-464 weisse Punkte im Kasten (aber auch ~330 "rote", der orange Knopf hat
     * dunkelrote Raender), Autors rotes "500" 0 weisse und 751 rote. Rot heisst also: keine weissen Ziffern
     * mehr, dafuer deutlich Rot.
     */
    private static boolean rot(BotContext ctx, BufferedImage b, int[] k) {
        return zahlRot(b, px(ctx, k[0]), py(ctx, k[1]), px(ctx, k[2]), py(ctx, k[3]));
    }

    static boolean zahlRot(BufferedImage b, int x0, int y0, int w, int h) {
        if (b == null) return false;
        int x1 = Math.min(b.getWidth(), x0 + w), y1 = Math.min(b.getHeight(), y0 + h);
        int rotN = 0, weissN = 0;
        for (int y = Math.max(0, y0); y < y1; y++) {
            for (int x = Math.max(0, x0); x < x1; x++) {
                int p = b.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                if (r > 170 && g < 110 && bl < 110) rotN++;
                else if (r > 215 && g > 215 && bl > 215) weissN++;
            }
        }
        return weissN < 40 && rotN > 150;
    }

    /** Foto fuer die Auswertung nach logs\titan -- hoechstens alle 20 s, nur die neuesten 40. */
    private static long fotoUm = 0;

    private static void foto(BotContext ctx, String art) {
        if (!de.hustlebot.core.Beleg.an()) return;   // 01.11.: nur noch zur gezielten Fehlersuche, kein Ordner, keine Meldung
        long jetzt = System.currentTimeMillis();
        if (jetzt - fotoUm < 20_000) return;
        fotoUm = jetzt;
        BufferedImage b = ctx.frame();
        if (b == null) return;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "titan");
            java.nio.file.Files.createDirectories(ordner);
            String name = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss"))
                    + "-" + art + ".png";
            de.hustlebot.core.Beleg.schreiben(b, "png", ordner.resolve(name).toFile());
            try (var s = java.nio.file.Files.list(ordner)) {
                List<java.nio.file.Path> alle = s.filter(p -> p.toString().endsWith(".png")).sorted().toList();
                for (int i = 0; i < alle.size() - 40; i++) java.nio.file.Files.deleteIfExists(alle.get(i));
            }
            Log.info(WER, Sprache.t("Photo for the analysis: logs\\titan\\" + name,
                    "Foto fuer die Auswertung: logs\\titan\\" + name));
        } catch (Exception e) {
            Log.debug(WER, "Foto nicht gespeichert: " + e.getMessage());
        }
    }

    /**
     * Zahl im Kasten lesen (drei Vorsatzwoerter, die haeufigste Zahl gilt). Passt die erste Lesung zur
     * Erwartung (hoechstens 3 daneben) oder liegt sie darunter, gilt sie sofort -- spart Texterkennungen.
     */
    static OptionalLong zahlLesen(BufferedImage bild, int x, int y, int w, int h, long erwartet) {
        if (bild == null) return OptionalLong.empty();
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, bild.getWidth() - x);
        h = Math.min(h, bild.getHeight() - y);
        if (w <= 4 || h <= 4) return OptionalLong.empty();
        Map<Long, Integer> stimmen = new HashMap<>();
        for (String wort : new String[]{"Nummer", "Wert", "No"}) {
            OptionalLong z = zahlAusText(ArenaLeser.textHinterWort(bild, x, y, w, h, wort));
            if (z.isPresent()) {
                if (stimmen.isEmpty() && erwartet >= 0 && z.getAsLong() <= erwartet + 3
                        && z.getAsLong() >= erwartet - 2000) return z;
                stimmen.merge(z.getAsLong(), 1, Integer::sum);
            }
        }
        return stimmen.entrySet().stream()
                .max(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> OptionalLong.of(e.getKey())).orElse(OptionalLong.empty());
    }

    static OptionalLong zahlAusText(String text) {
        if (text == null) return OptionalLong.empty();
        String t = text.replaceAll("[Oo]", "0").replaceAll("(?<=\\d)[Il|!]|[Il|!](?=\\d)", "1");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,3}(?:[ .,]\\d{3})+|\\d+").matcher(t);
        if (!m.find()) return OptionalLong.empty();
        String z = m.group().replaceAll("[ .,]", "");
        if (z.length() > 9) return OptionalLong.empty();
        return OptionalLong.of(Long.parseLong(z));
    }
}
