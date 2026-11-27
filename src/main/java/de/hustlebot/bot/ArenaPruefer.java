package de.hustlebot.bot;

import de.hustlebot.core.ArenaExtras;
import de.hustlebot.core.FesteStellen;
import de.hustlebot.core.Gegnerbuch;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sieht sich die Einheiten EINES Arena-Gegners an: Sets und Artefakte.
 *
 * Autor, 24.09.: "der bot soll sich durch jeden kaempfer jedes einzelnen
 * spielers durchclicken. schauen welche sets der spieler benutzt, hierzu soll
 * er bei jedem kaempfer einmal auf die Brustplatte druecken dort wird dann
 * oben der Set name angezeigt ... unten in der ecke das artifakt".
 *
 * ABLAUF (ein Schritt je Bild, damit der Motor nie blockiert):
 *
 *   1. Im Vergleichsfenster EINE gegnerische Einheit anklicken. Welche, ist
 *      egal -- im Einheitenfenster blaettert der blaue Pfeil (ar_dweller_next)
 *      durch alle. Gefunden wird sie an ihrem runden Rahmen; klappt das nicht,
 *      werden die zwei Stellen probiert, an denen bei jeder Anzahl eine
 *      Einheit steht (rechts neben der Mitte). Den Helden (links, gross,
 *      golden) faesst der Bot nie an.
 *   2. Einheitenfenster (ar_dwellers): Artefakt-Ecke pruefen (ar_stelle_
 *      artefakt, verglichen mit ar_art_*), dann auf die Brustplatte klicken
 *      (ar_stelle_brustplatte).
 *   3. Gegenstandsfenster: den Titel lesen (ar_stelle_setname), das Set
 *      daraus bestimmen (ArenaSets), Fenster schliessen (ar_item_zu -- ohne
 *      diese Aufnahme liest der Bot nur Artefakte, keine Sets).
 *   4. Pfeil zur naechsten Einheit. Ist das Bild eines, das schon da war,
 *      ist man einmal herum. Hoechstens 8.
 *   5. Einheitenfenster schliessen (ar_dweller_close).
 *
 * Jede Wartestelle hat eine Zeitgrenze. Geht etwas schief, wird die Einheit
 * uebersprungen oder die Pruefung beendet -- nie haengen bleiben.
 */
final class ArenaPruefer {
    /**
     * Autor 24.10.: das Arena-Tempo (arena-tempo.txt, ArenaMode) -- gilt fuer die reinen Tempo-Pausen hier
     * (das doppelte Gegenlesen). Pausen, die auf das Zeichnen im Spiel warten, bleiben fest. null = normal.
     */
    private de.hustlebot.core.Tempolerner tempo;

    void tempoSetzen(de.hustlebot.core.Tempolerner t) {
        tempo = t;
    }

    private long ms(long ms) {
        return tempo == null ? ms : tempo.ms(ms);
    }


    private static final String T_DWELLERS      = "ar_dwellers";
    private static final String T_DWELLER_NEXT  = "ar_dweller_next";
    private static final String T_DWELLER_CLOSE = "ar_dweller_close";
    private static final String T_ITEM_ZU       = "ar_item_zu";
    private static final String T_ITEM_FENSTER  = "ar_item_fenster";
    private static final String S_BRUST   = "ar_stelle_brustplatte";
    private static final String S_SETNAME = "ar_stelle_setname";
    /** Kopfzeile "Equipped by character:" (gemessen 11.10.: Fenster 0,92-1,00, sonst hoechstens 0,54). */
    static final String T_ITEM_EQUIPPED = "ar_item_equipped";
    private static final String S_ARTE    = "ar_stelle_artefakt";
    /** Autor 22.10.: das Totem-Feld (erstes der vier kleinen) -- verglichen mit ar_totem_*. */
    private static final String S_TOTEM   = "ar_stelle_totem";
    /** Punkte je gutem Totem (wie ein gutes Artefakt). */
    static final int GUTES_TOTEM = 3;
    /** Punkte je schlechtem Totem (ar_totemschlecht_..., Autor 22.10.: -3). */
    static final int SCHLECHTES_TOTEM = -3;
    private int guteTotems, schlechteTotems;
    /** Autor 22.10.: gute Guardians (+2 je Einheit) und Einheiten ohne Guardian (Schloss, -2 je Einheit). */
    private int guteGuardians, ohneGuardian;
    /** Autor 22.10.: Einheiten mit Talentstufe unter 50 (-2 je Einheit, Bauarbeiter nicht). */
    private int talentUnter50;
    /** Das rote Herz oben im Einheitenfenster; rechts daneben stehen die Lebenspunkte. */
    private static final String T_HERZ    = "ar_leben_herz";
    /** Ersatz ohne Herz-Vorlage: Rahmen um die Lebenspunkte. */
    private static final String S_LEBEN   = "ar_stelle_leben";
    /** Bis zu so vielen Lebenspunkten gilt eine Einheit als Bauarbeiter. */
    static final long BAUARBEITER_BIS = 250_000;   // 27.09.: Autor "bis einschliesslich 250k" (vorher 100k)
    /** Weniger Leben hat keine Einheit -- so eine Zahl ist verlesen. */
    private static final long LEBEN_MIN = 1_000;

    /** So lange wird hoechstens auf ein Fenster gewartet. */
    private static final long GEDULD_MS = 2500;
    private static final int MAX_EINHEITEN = 6;   // eine Turnier-Truppe hat hoechstens 6 (01.10.: "tig" mit 7 gezaehlt)
    /** Wie oft "Naechste Einheit" ohne Wirkung blieb (das Bild zeigte noch dieselbe). */
    private int weiterOhneWirkung;

    enum Phase { AUS, SCHIRM, SCHIRM_FENSTER, PAL, PAL_FENSTER, ZU, HELD, HELD_FENSTER, HELD_ZU, OEFFNEN, EINHEIT, ITEM, WAFFE, WAFFE_ITEM, RING, RING_ITEM, WEITER, SCHLIESSEN }

    // --- Schirmherrschaften und Pals (Autor, 01.10.) ---------------------------------
    /** Titel "Patronage" des Fensters, das ein Klick auf das Abzeichen neben "Place" oeffnet. */
    private static final String T_SCHIRM_TITEL = SchirmLeser.T_TITEL;
    // Das Zaehlen im Fenster (Lv., gute, Stigmas) steckt seit 11.10. in SchirmLeser -- dieselbe Stelle
    // liest auch das eigene Team (Nachtrag zu Auftrag 6, Punkt 4).
    /** Die Pfote mit "i" links neben dem Gegnernamen; Bots haben sie nicht. */
    private static final String T_PAL_KNOPF = "ar_pal_knopf";
    /** Titel "Pals" des Fensters, das die Pfote oeffnet. */
    private static final String T_PAL_TITEL = "ar_pal_titel";
    private static final String S_PAL_GUT = "ar_pal_gut";
    /** -1 = nicht angesehen. */
    private int schirmAnzahl = -1, guteSchirme, guteStigmas, gutePals = -1;
    /** Nur Schirmherrschaften/Pals nachholen, keine Einheiten (die sind frisch geprueft). */
    private boolean nurExtras, schirmAnsehen, palsAnsehen;
    /** Ein kleines Fenster schliessen: woran man es erkennt, wohin getippt wird, was danach kommt. */
    private String zuTitel;
    private int zuX, zuY, zuVersuche;
    private boolean zuNachPals;
    /** Wohin getippt wird, um das Fenster der Schirmherrschaften / der Pals zu schliessen. */
    private int schirmZuX, schirmZuY, palZuX, palZuY;

    private Phase phase = Phase.AUS;
    private long phaseSeit;
    /** Das X des Einheitenfensters ist schon geklickt -- nicht noch einmal (01.10.). */
    private boolean schliessenGeklickt;
    private int oeffnenVersuch;
    private int einheiten;
    /** Bilder aller schon gesehenen Einheiten -- zum Erkennen, wann man einmal herum ist. */
    private final List<Mat> gesehen = new ArrayList<>();
    /**
     * Die Ausruestungsfelder jeder gesehenen Einheit (gleiche Reihenfolge wie {@link #gesehen}, leer = nicht bekannt).
     * Helfer B 22.10.: bei JinXED wurden 24 von 24 Mal nur 2 von 6 Einheiten gelesen, bei MasterSyncro 5 von 7 --
     * gleich aussehende Einheiten (gleicher Typ, gleiche Figur) galten als "einmal herum". Erst wenn auch die
     * Ausruestung gleich ist, ist es wirklich dieselbe Einheit.
     */
    private final List<List<Mat>> gesehenFelder = new ArrayList<>();
    /** Talent, Guardian, Kraft- und Lebenszahl jeder gesehenen Einheit (Autor 22.10.: zwei "ersatz" ohne Ausruestung). */
    private final List<List<Mat>> gesehenMerkmale = new ArrayList<>();
    /** Ab dieser Aehnlichkeit der Ausruestung ist es dieselbe Einheit (gleich 0,99-1,00, andere 0,27-0,65). */
    private static final double DIESELBE_AUSRUESTUNG = 0.90;
    /** Beim letzten {@link #gesehenAn}: gleiche Figur, aber andere Ausruestung. */
    private boolean zwillingJetzt;
    private final List<String> sets = new ArrayList<>();
    // --- Misch-Sets (Autor 15.10.): steht im Set-Fenster "Collected: 1-5/6", auch die Waffe ansehen ---
    /** Die Waffe im Einheitenfenster (oben rechts im 2x2-Feld), gemessen an Autors Bild vom 15.10. (1159x770). */
    private static final double[] WAFFE = {570 / 1159.0, 289 / 770.0};
    /** Der Ring: zweites Feld der unteren Reihe (Autors Bilder vom 22.10., Gegner und eigenes Team gleich). */
    private static final double[] RING = {384 / 1159.0, 556 / 770.0};
    /** Die Zeile "Set bonus  Collected: 6/6" im Gegenstandsfenster. */
    private static final double[] SAMMEL = {520 / 1159.0, 226 / 770.0, 190 / 1159.0, 32 / 770.0};
    private static final java.util.regex.Pattern TEILE = java.util.regex.Pattern.compile("([1-6])\\s*[/|]\\s*6");
    private boolean waffeNoetig, waffeModus;

    // --- Held des Gegners (Autor, 15.10.) ------------------------------------------------
    // Im Vergleichsfenster links neben der ersten Einheit antippen. Es kommt dasselbe Heldenfenster wie in der
    // Kaserne: Hauptfaehigkeit ueber "Active", vier Nebenfaehigkeiten drumherum (Stellen wie TeamScan.HELD_*).
    // Gut = wie bei Autor (Herz, Dolch, Zwangsjacke; Vorlagen ar_held_haupt_gut_* / ar_held_neben_gut_*
    // aus Autors eigenem Heldenfenster). Punkte wie bei Autor: Haupt 8, jede gute Neben 2.
    // Alle Faehigkeiten landen als Ausschnitt in logs/arena-helden -- zum Bewerten fuer Autor.
    static final String S_AR_HELD_HAUPT_GUT = "ar_held_haupt_gut";
    static final String S_AR_HELD_NEBEN_GUT = "ar_held_neben_gut";
    /** Dieselben Faehigkeiten aus dem Team-Scan der Kaserne (Autors Bilder, templates/gelernt/team). */
    static final String S_TEAM_HELD_HAUPT_GUT = "held_haupt_gut", S_TEAM_HELD_NEBEN_GUT = "held_neben_gut";
    private static final String T_HELD_ACTIVE = "ts_held_active";
    private static final double HELD_GUT_AB = 0.78;
    private boolean heldGelesen;
    private Boolean heldHauptGut;
    private int heldGuteNeben, heldZuVersuche;
    /** Schlechte Faehigkeiten (Autor 22.10.): Bilder ar_held_haupt_schlecht_* / ar_held_neben_schlecht_*. */
    static final String S_AR_HELD_HAUPT_SCHLECHT = "ar_held_haupt_schlecht", S_AR_HELD_NEBEN_SCHLECHT = "ar_held_neben_schlecht";
    private boolean heldHauptSchlecht;
    private int heldSchlechteNeben;
    private String heldText = "";
    /** Autor 22.10.: den Helden mit merken -- die fuenf Faehigkeiten (Haupt, Neben 1-4) als Bilder, fuer ArenaAussehen. */
    private final List<BufferedImage> heldBilder = new ArrayList<>();
    /** Testschalter (!team scan waffe): die Waffe immer ansehen, auch bei 6/6. */
    static volatile boolean waffeImmerAnsehen;
    /** Zaehlt ganze Sets (6/6) -- jedes 8. bekommt trotzdem die Waffe zur Kontrolle (Autor 22.10.). */
    private static int sechsVonSechs;
    private ArenaSets.Treffer brustSet;
    private int brustTeile;
    /**
     * Autor 22.10.: "Bot first checks the chestplate. If it says 6/6 set, he doesn't check the weapon or ring anymore. If it
     * says 4/6 he checks the weapon first. If that says 2/6 it skips the ring. Chestplate 2/6, weapon 2/6 and ring 2/6:
     * those points get calculated by how many set pieces are equipped." -- je Einheit die gefundenen Sets mit Teilen.
     */
    private final java.util.LinkedHashMap<String, Integer> setTeile = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ArenaSets.Treffer> setTreffer = new java.util.HashMap<>();
    /** Welcher Gegenstand gerade gelesen wird: 0 Brust, 1 Waffe, 2 Ring. */
    private int stueck;
    private boolean ringNoetig;
    /** Seit 16.10. mit Kommastellen (anteilige Sets), gerundet erst im Ergebnis. */
    private double setPunkte;
    private int guteArtefakte;
    /** Schlechte Artefakte (ar_artschlecht_..., Autor 22.10.) -- stecken in setPunkte. */
    private int schlechteArtefakte;
    /** Punkte je schlechtem Artefakt (Autor 22.10.: -3 je Fund; ein gutes bleibt +3). */
    static final int SCHLECHTES_ARTEFAKT = -3;
    /** Einheiten mit hoechstens 250 000 Leben -- "Bauarbeiter" (Autor, 25.09.; 27.09. von 100 000 hoch). */
    private int bauarbeiter;
    /** Einheiten ueber 8 Mio Kraft (+5) und ueber 6 Mio (+1) -- Autor, 11.10. abends; stecken in setPunkte. */
    private int starke, mittelstarke;
    /** Summe der Kraft-Punkte aller Einheiten (Autor 22.10.) -- steckt in setPunkte. */
    private int kraftPunkte;
    /** Alle vier Allianz-Talente im Gegnerteam (+8, Autor 11.10.) -- und ob schon nachgesehen. */
    private boolean allianzVoll, allianzGeprueft;
    /** Einheiten mit gutem Emblem (+3, Autor 11.10.) -- stecken in setPunkte. */
    private int guteEmbleme, emblemPunkte;
    /** Steine auf den Gegenstaenden aller Einheiten (Autor, 30.09.): gelb +0,5, rot +0,1. */
    private int gelbeSteine, roteSteine;
    /** Legendaere (orange) und epische (violette) Steine (Autor 22.10.: -0,1 / -0,05 je Stein) -- Abzug auf setPunkte. */
    private int legendaereSteine, epischeSteine;
    private String name = "", clan = "";
    /** Autor 25.10. (TR5): Items nach seiner Bewertung (ArenaItems) -- OP-Items des Gegners und je Wert gezaehlt. */
    private int opItems, itemPunkte;
    /** Bei der gerade angesehenen Einheit hat mindestens ein Gegenstand eine Bewertung von Autor. */
    private boolean einheitItemsGewertet;
    private final java.util.Map<ArenaItems.Wert, Integer> itemZaehler = new java.util.EnumMap<>(ArenaItems.Wert.class);
    /** Dein Team: die belegten Plaetze je Einheit -- landen am Ende in arena-items\eigen.tsv. */
    private final List<java.util.Map.Entry<String, List<List<ArenaItems.Gesehen>>>> eigeneItems = new ArrayList<>();

    /** Wie viele "unglaublich OP"-Items die letzte Pruefung beim Gegner fand. */
    int opItems() {
        return opItems;
    }

    /** Was die Pruefung ergab. {@code eigenes} = Autors eigenes Team (08.10.), nicht in den Notizblock. */
    record Ergebnis(String name, String clan, int einheiten, int setPunkte,
                    String sets, int guteArtefakte, int bauarbeiter, int gelbeSteine, int roteSteine,
                    int schirmAnzahl, int guteSchirme, int guteStigmas, int gutePals, boolean nurExtras,
                    boolean eigenes) { }

    private Ergebnis ergebnis;

    // --- Schnell-Blick (Autor 22.10.): sehen die Einheiten noch so aus wie beim letzten Mal? (ArenaAussehen) ------
    /** Bei einer vollstaendigen Pruefung: das Aussehen jeder Einheit (holt ArenaMode ab und legt es ab). */
    private final List<ArenaAussehen.Einheit> aussehen = new ArrayList<>();
    /** Nur durchklicken und vergleichen -- keine Sets, keine Artefakte, kein Notizblock. */
    private boolean nurAussehen;
    /** Das abgelegte Aussehen, mit dem verglichen wird (gehoert dem Pruefer, wird am Ende freigegeben). */
    private List<ArenaAussehen.Einheit> vergleich = new ArrayList<>();
    private boolean abweichung, nachgeladen;
    private int passendeOhneBau;
    /** Schnell-Blick: Nummern der wiedererkannten Einheiten (ohne Bauarbeiter) in der Reihenfolge des Durchklickens. */
    private final List<Integer> folge = new ArrayList<>();
    /** Welche abgelegte Einheit als naechste kommen muesste (-1 = noch keine erkannt). */
    private int erwartet = -1;
    /** Ergebnis des Schnell-Blicks: TRUE = anders als beim letzten Mal, FALSE = gleich, null = keins. */
    private Boolean aussehenErgebnis;

    /**
     * Autors eigenes Team (08.10.): "Wenn ich auf einen Gegner druecke, ist unten das Gegnerteam
     * und oben mein Team." Dieselbe Pruefung, nur mit den OBEREN Schwertern als Anker -- die
     * bleiben fuer die ganze Pruefung fest, egal, was der Aufrufer je Bild findet.
     */
    private boolean eigenesTeam;
    private Matcher.Hit festeSchwerter;
    /** Die Schwerter des Gegners (unten) -- die Schliess-Tipps der kleinen Fenster gehen dorthin. */
    private Matcher.Hit unterAnker;

    void startenEigenesTeam(Matcher.Hit meineSchwerter, Matcher.Hit gegnerSchwerter, boolean pfote) {
        starten("dein Team", "", false, true, pfote);
        eigenesTeam = true;
        festeSchwerter = meineSchwerter;
        unterAnker = gegnerSchwerter;
    }

    // --- Eigenes Team in der Kaserne (Auftrag 6, Autor 11.10.) ---------------------------------
    /** "Switch dwellers" unten im Einheitenfenster der Kaserne -- Ersatz, falls ar_dwellers dort nicht passt. */
    static final String T_KASERNE_FENSTER = "ts_switch_dwellers";
    /** Der rechte Pfeil "Switch dwellers" der Kaserne -- Ersatz fuer ar_dweller_next. */
    static final String T_KASERNE_WEITER = "ts_pfeil_rechts";
    /** Die Kaserne: das Einheitenfenster ist schon offen, geschlossen wird es vom Aufrufer (TeamScan). */
    private boolean kaserne;
    /** Je Einheit einmal gerufen (Bild, gelesene Kraft oder -1) -- TeamScan liest dort den Namen. */
    private java.util.function.BiConsumer<BufferedImage, Long> jeEinheit;
    /** Die zuletzt gelesene Kraft (-1 = nicht sicher gelesen). */
    private long letzteKraft = -1;

    /**
     * Autors eigenes Team in der KASERNE (Auftrag 6): das Einheitenfenster (Bild 07 der Team-Scan-Bilder)
     * ist schon offen -- dieselbe Lese-Logik wie beim Gegner (Kraft, Emblem, Set, Artefakte, Steine), mit
     * den Pfeilen "Switch dwellers" alle durch. Keine Schirmherrschaften, keine Pals, kein Schliessen --
     * das macht TeamScan selbst.
     */
    void startenKaserne(java.util.function.BiConsumer<BufferedImage, Long> jeEinheit) {
        starten("dein Team", "", false, false, false);
        eigenesTeam = true;
        kaserne = true;
        this.jeEinheit = jeEinheit;
        wechsel(Phase.EINHEIT);
    }

    /** Ist das Einheitenfenster da? In der Kaserne zaehlt auch "Switch dwellers". */
    private boolean fensterOffen(Mat screen, BotContext ctx) {
        return da(screen, ctx, T_DWELLERS) || (kaserne && da(screen, ctx, T_KASERNE_FENSTER));
    }

    /** Welche Vorlagen die Kaserne mindestens braucht (sonst nur Kraft und Name, keine Sets). */
    static List<String> kaserneFehlt(BotContext ctx) {
        List<String> f = new ArrayList<>();
        if (!ctx.matcher().has(T_DWELLERS) && !ctx.matcher().has(T_KASERNE_FENSTER)) f.add(T_KASERNE_FENSTER);
        if (!ctx.matcher().has(T_DWELLER_NEXT) && !ctx.matcher().has(T_KASERNE_WEITER)) f.add(T_KASERNE_WEITER);
        return f;
    }

    // ------------------------------------------------------------------

    /** Geht die Pruefung ueberhaupt? Braucht Einheitenfenster + Blaetterpfeil + X. */
    static boolean moeglich(BotContext ctx) {
        return ctx.matcher().has(T_DWELLERS) && ctx.matcher().has(T_DWELLER_NEXT)
                && ctx.matcher().has(T_DWELLER_CLOSE)
                && (FesteStellen.holen(S_BRUST).isPresent() || FesteStellen.holen(S_ARTE).isPresent());
    }

    /** Was fuer eine vollstaendige Pruefung noch aufgenommen werden muss. */
    static List<String> fehlt(BotContext ctx) {
        List<String> f = new ArrayList<>();
        for (String t : List.of(T_DWELLERS, T_DWELLER_NEXT, T_DWELLER_CLOSE)) {
            if (!ctx.matcher().has(t)) f.add(t);
        }
        for (String s : List.of(S_BRUST, S_SETNAME, S_ARTE)) {
            if (FesteStellen.holen(s).isEmpty()) f.add(s);
        }
        return f;
    }

    boolean laeuft() { return phase != Phase.AUS; }

    /** Das Ergebnis der letzten Pruefung, einmal abholbar. */
    Optional<Ergebnis> abholen() {
        Ergebnis e = ergebnis;
        ergebnis = null;
        return Optional.ofNullable(e);
    }

    void starten(String name, String clan) {
        starten(name, clan, true);
    }

    /**
     * @param pfoteDa die Pfote ist im Fenster zu sehen. Autor, 02.10.: "Wenn er die Pfeile benutzt,
     *                um zum naechsten Gegner zu gelangen, taucht der Pal-Knopf nicht auf" -- dann
     *                werden die Pals hier nicht angesehen, sondern spaeter einzeln.
     */
    void starten(String name, String clan, boolean pfoteDa) {
        starten(name, clan, false, ArenaExtras.schirm() || ArenaExtras.stigma(), ArenaExtras.pals() && pfoteDa);
    }

    /**
     * Steht noch ein Pal- oder Schirmherrschaften-Fenster offen, obwohl keine Pruefung laeuft
     * (abgebrochen, oder in Phase ZU nach drei Versuchen weitergemacht)? Dann ins Leere tippen.
     * Review 02.10.: sonst verdeckt das Pal-Fenster "Your squad:", das Vergleichsfenster wird nicht
     * mehr erkannt und die Stage verstreicht ohne Angriff.
     * @return true, wenn geklickt wurde
     */
    static boolean kleinesFensterZu(Mat screen, BotContext ctx) {
        if (da(screen, ctx, T_PAL_TITEL)) {
            // Das Pal-Fenster liegt links: auf "Place" der eigenen Truppe rechts oben tippen.
            int x = (int) (ctx.width() * 0.759), y = (int) (ctx.height() * 0.238);
            Log.action("Klick", "Uebrig gebliebenes Pal-Fenster schliessen  [" + x + "," + y + "]");
            ctx.input().click(x, y, 2);
            ctx.sleepUntilChanged(20, 700);
            return true;
        }
        if (da(screen, ctx, T_SCHIRM_TITEL)) {
            // Das Fenster der Schirmherrschaften liegt rechts: auf "Your squad:" tippen.
            int x = (int) (ctx.width() * 0.350), y = (int) (ctx.height() * 0.235);
            Log.action("Klick", "Uebrig gebliebenes Fenster der Schirmherrschaften schliessen  [" + x + "," + y + "]");
            ctx.input().click(x, y, 2);
            ctx.sleepUntilChanged(20, 700);
            return true;
        }
        return false;
    }

    /** Ist die Pfote (Pals) im Vergleichsfenster zu sehen? Nur, wenn der Gegner direkt angeklickt wurde. */
    static boolean pfoteDa(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        return schwerter != null && palKnopf(screen, ctx, schwerter) != null;
    }

    /** Nur Schirmherrschaften und/oder Pals nachholen -- die Einheiten sind frisch geprueft. */
    void startenNurExtras(String name, String clan, boolean schirm, boolean pals) {
        starten(name, clan, true, schirm, pals);
    }

    /**
     * Schnell-Blick (Autor 22.10.): beim schon bekannten Gegner nur die Einheiten durchklicken und mit dem abgelegten
     * Aussehen vergleichen. Danach {@link #aussehenErgebnis()} abholen.
     */
    void startenAussehen(String name, String clan, List<ArenaAussehen.Einheit> bekannt) {
        starten(name, clan, false, false, false);
        nurAussehen = true;
        vergleich = bekannt == null ? new ArrayList<>() : bekannt;
        wechsel(Phase.OEFFNEN);
        Log.info("Arena", Sprache.t("Quick look at " + this.name + ": do the " + vergleich.size() + " units still look like last time?",
                "Schnell-Blick bei " + this.name + ": sehen die " + vergleich.size() + " Einheiten noch aus wie beim letzten Mal?"));
    }

    /**
     * Autor 24.10.: Schnell-Blick auf DEIN Team -- "einmal kurz durchswipen, aber nicht jedes Mal das ganze Team neu
     * einscannen". Wie beim Gegner (Ausruestung, Name, Talent, Guardian, Skin, Kraft und Leben je Einheit), nur mit den
     * oberen Schwertern als Anker. Danach {@link #aussehenErgebnis()} abholen.
     */
    void startenEigenesAussehen(Matcher.Hit meineSchwerter, Matcher.Hit gegnerSchwerter, List<ArenaAussehen.Einheit> bekannt) {
        startenAussehen(Sprache.t("your team", "dein Team"), "", bekannt);
        eigenesTeam = true;
        festeSchwerter = meineSchwerter;
        unterAnker = gegnerSchwerter;
    }

    /** Galt die zuletzt gestartete Pruefung (oder der Schnell-Blick) deinem eigenen Team? */
    boolean warEigenesTeam() {
        return eigenesTeam && !kaserne;
    }

    /** Ergebnis des letzten Schnell-Blicks, einmal abholbar: TRUE = anders, FALSE = gleich, null = keins. */
    Boolean aussehenErgebnis() {
        Boolean e = aussehenErgebnis;
        aussehenErgebnis = null;
        return e;
    }

    /** War die letzte (fertige) Pruefung ein Schnell-Blick? */
    boolean warSchnellblick() {
        return nurAussehen;
    }

    /** Das Aussehen der zuletzt vollstaendig geprueften Einheiten -- gehoert danach dem Aufrufer. */
    /** Die Held-Bilder der eben fertigen Pruefung (leer, wenn der Held diesmal nicht angesehen wurde). */
    List<BufferedImage> heldBilderAbholen() {
        List<BufferedImage> l = new ArrayList<>(heldBilder);
        heldBilder.clear();
        return l;
    }

    /** "Held: Haupt gut, 2 gute Neben (+12)" der eben fertigen Pruefung, leer ohne Held. */
    String heldTextJetzt() {
        return heldText;
    }

    List<ArenaAussehen.Einheit> aussehenAbholen() {
        List<ArenaAussehen.Einheit> a = new ArrayList<>(aussehen);
        aussehen.clear();
        return a;
    }

    private void starten(String name, String clan, boolean nurExtras, boolean schirm, boolean pals) {
        nurAussehen = false;
        unvollstaendig = false;
        ArenaAussehen.freigebenAlle(aussehen);
        aussehen.clear();
        ArenaAussehen.freigebenAlle(vergleich);
        vergleich = new ArrayList<>();
        abweichung = false;
        nachgeladen = false;
        passendeOhneBau = 0;
        folge.clear();
        erwartet = -1;
        aussehenErgebnis = null;
        eigenesTeam = false;
        kaserne = false;
        jeEinheit = null;
        letzteKraft = -1;
        festeSchwerter = null;
        unterAnker = null;
        this.nurExtras = nurExtras;
        gestartetUm = System.currentTimeMillis();
        schirmAnsehen = schirm;
        palsAnsehen = pals;
        schirmAnzahl = -1;
        guteSchirme = 0;
        guteStigmas = 0;
        gutePals = -1;
        this.name = name == null ? "" : name;
        this.clan = clan == null ? "" : clan;
        sets.clear();
        waffeNoetig = false;
        waffeModus = false;
        brustSet = null;
        ringNoetig = false;
        stueck = 0;
        setTeile.clear();
        setTreffer.clear();
        setPunkte = 0;
        guteArtefakte = 0;
        schlechteArtefakte = 0;
        opItems = 0;
        itemPunkte = 0;
        itemZaehler.clear();
        eigeneItems.clear();
        guteTotems = 0;
        schlechteTotems = 0;
        guteGuardians = 0;
        ohneGuardian = 0;
        talentUnter50 = 0;
        bauarbeiter = 0;
        starke = 0;
        mittelstarke = 0;
        kraftPunkte = 0;
        allianzVoll = false;
        allianzGeprueft = false;
        guteEmbleme = 0;
        emblemPunkte = 0;
        gelbeSteine = 0;
        einheitenNamen.clear();
        roteSteine = 0;
        legendaereSteine = 0;
        epischeSteine = 0;
        einheiten = 0;
        oeffnenVersuch = 0;
        schliessenGeklickt = false;
        weiterOhneWirkung = 0;
        wechselGrob = 0;
        wechselFigur = 0;
        wechselGrenze = 0;
        wechselMs = 0;
        gesehenLeeren();
        ergebnis = null;
        heldGelesen = false;
        heldHauptGut = null;
        heldGuteNeben = 0;
        heldHauptSchlecht = false;
        heldSchlechteNeben = 0;
        heldZuVersuche = 0;
        heldText = "";
        heldBilder.clear();
        wechsel(schirm ? Phase.SCHIRM : pals ? Phase.PAL : heldDran() ? Phase.HELD : Phase.OEFFNEN);
        if (nurExtras) {
            Log.info("Arena", "Hole bei " + this.name + " nach: " + (schirm ? "Schirmherrschaften" : "")
                    + (schirm && pals ? " und " : "") + (pals ? "Pals" : "") + " (Einheiten sind frisch geprueft).");
        } else {
            Log.info("Arena", "Sehe mir die Einheiten von " + this.name + " an (Sets, Artefakte"
                    + (schirm ? ", Schirmherrschaften" : "") + (pals ? ", Pals" : "") + ").");
        }
    }

    /**
     * Sofort beenden (Runde vorbei, Zeit knapp). Das halbe Ergebnis wird VERWORFEN
     * (30.09.): Frueher galt z. B. "2 von 6 Einheiten" als fertige Pruefung -- die
     * fehlenden zaehlten als "Einheit unter 6" (+5 je Stueck), und 14 Tage lang
     * wurde nicht neu geprueft. Jetzt prueft der Bot in der naechsten Stage neu.
     *
     * @return der Name des Gegners, dessen Pruefung abgebrochen wurde, sonst null
     */
    String abbrechen(String grund) {
        if (!laeuft()) return null;
        Log.info("Arena", "Einheiten-Pruefung abgebrochen: " + grund + " -- wird spaeter wiederholt.");
        String wer = name;
        fertig();
        ergebnis = null;
        aussehenErgebnis = null;   // abgebrochener Schnell-Blick sagt nichts
        ArenaAussehen.freigebenAlle(aussehen);
        aussehen.clear();
        return wer;
    }

    /**
     * Ein Schritt. Null heisst: die Pruefung ist fertig (Ergebnis abholen),
     * der Aufrufer macht normal weiter.
     */
    /**
     * Wie viele Schritte in diesem Takt schon hintereinander liefen. Seit 01.10. haengt
     * die Pruefung den naechsten Schritt gleich an (Fenster zu -> naechste Einheit ->
     * ansehen), statt je Schritt einen Takt des Bots zu warten: 14 s je Gegner wurden
     * so rund 9 s (Autor: "Foto-Zyklen und Wartezeiten kuerzen").
     */
    private int kette;
    private static final int KETTE_MAX = 4;
    /** Autor, 15.10.: hoechstens 25 s je Gegner. */
    static final long GEGNER_FRIST_MS = 25_000;
    private long gestartetUm;

    GameMode.Outcome schritt(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        if (eigenesTeam && festeSchwerter != null) schwerter = festeSchwerter;   // eigenes Team: oberer Anker
        kette = 0;
        return schrittInnen(screen, ctx, schwerter);
    }

    /**
     * Autor, 15.10.: hoechstens 25 s je Gegner. Geprueft wird in ArenaMode (dort, wo auch "Runde vorbei" abbricht),
     * damit das Ergebnis im selben Takt verbucht wird. Arena-Review 15.10.: Vorher stand die Frist hier in
     * schritt() und gab ACTED zurueck -- ArenaMode verbucht aber nur bei null, das Ergebnis ging verloren.
     */
    boolean fristUm() {
        return laeuft() && !eigenesTeam && !kaserne && gestartetUm > 0
                && System.currentTimeMillis() - gestartetUm > GEGNER_FRIST_MS;
    }

    /**
     * Alle sechs Einheiten fertig gelesen? Arena-Review 15.10.: "einheiten >= 6" allein galt schon beim Betreten
     * der sechsten Einheit -- deren Set fehlte dann. Erst in SCHLIESSEN ist wirklich alles gelesen.
     */
    boolean einheitenFertig() {
        return einheiten > 0 && phase == Phase.SCHLIESSEN && !unvollstaendig;   // Review 15.10.: auch Teams unter 6 Einheiten
    }

    /** Pruefer 29.10.: das Gegenstandsfenster ging nicht zu, die Pruefung hoerte vor der letzten Einheit auf. */
    private boolean unvollstaendig;

    /** Frist um, aber alle Einheiten gelesen: das Ergebnis behalten (ArenaMode holt es ab). */
    void fristFertig() {
        Log.info("Arena", "25 s um bei " + name + " -- alle Einheiten sind gelesen, das Ergebnis bleibt.");
        fertig();
    }

    /** Nur die sichere Kopfzeile "Battle" (>= 0,85, sonst hoechstens 0,58) -- fuer das Aufraeumen in ArenaMode. */
    static boolean heldFensterSicher(Mat screen, BotContext ctx) {
        return ctx.matcher().has(T_HELD_BATTLE) && !ctx.matcher().findAll(screen, T_HELD_BATTLE, 0.85, 1).isEmpty();
    }

    /** Steht das Heldenfenster (noch) offen? Fuer ArenaMode, das es nach einem Abbruch schliessen muss. */
    static boolean heldFensterOffen(Mat screen, BotContext ctx) {
        return heldFensterDa(screen, ctx);
    }

    /** Das rote X des Heldenfensters antippen (1120,35 bei 1159 breit). */
    static void heldFensterSchliessen(BotContext ctx) {
        int x = (int) Math.round(1120 * ctx.width() / 1159.0), y = (int) Math.round(35 * ctx.height() / 770.0);
        Log.action("Klick", "Heldenfenster schliessen (nach Abbruch)  [" + x + "," + y + "]");
        ctx.input().click(x, y, 2);
        ctx.sleepUntilChanged(20, 800);
    }

    /** Den naechsten Schritt sofort auf einem frischen Bild ausfuehren. */
    private GameMode.Outcome sofortWeiter(BotContext ctx, Matcher.Hit schwerter, GameMode.Outcome sonst) {
        if (kette >= KETTE_MAX) return sonst;
        Mat neu = ctx.refresh();
        if (neu == null) return sonst;
        kette++;
        return schrittInnen(neu, ctx, schwerter);
    }

    private GameMode.Outcome schrittInnen(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        long seit = System.currentTimeMillis() - phaseSeit;
        // Gleich zu Beginn steht das Vergleichsfenster mit beiden Teams da: Allianz nachsehen (11.10.).
        if (!allianzGeprueft && !eigenesTeam && !nurExtras) {
            allianzGeprueft = true;
            allianzPruefen(ctx);
        }
        switch (phase) {
            case SCHIRM -> {
                // Das Abzeichen rechts am Kraftbalken des Gegners: rot mit Zahl = Schirmherrschaften,
                // grau = keine. Gemessen an Autors Bildern: 331 Punkte rechts der Schwerter.
                if (schwerter == null || !ctx.matcher().has(T_SCHIRM_TITEL)) {
                    weiterNachSchirm();
                    return phase == Phase.AUS ? null : sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                double f = ctx.width() / 1159.0;
                int bx = schwerter.centerX() + (int) Math.round(331 * f);
                int by = schwerter.centerY() - (int) Math.round(3 * f);
                // Autor 22.10.: dein Abzeichen sitzt LINKS an den (einzigen) Schwertern und ist lila, nicht rot (Foto
                // Arena 15.10., Stage 5: lila "3" bei 237,350, Schwerter bei 575,350) -- vorher hiess es immer "grau".
                boolean meins = eigenesTeam && unterAnker != null;
                if (meins) {
                    bx = unterAnker.centerX() - (int) Math.round(338 * f);
                    by = unterAnker.centerY() - (int) Math.round(3 * f);
                }
                beweisFoto(ctx, "abzeichen");   // das Bild, an dem das Abzeichen (rot/lila/grau) beurteilt wird
                if (meins ? !abzeichenLila(ctx.frame(), bx, by, f) : !abzeichenRot(ctx.frame(), bx, by, f)) {
                    // Pruefung 29.10.: grau heisst oft nur "noch nicht da" -- in Runde 1 ist das Abzeichen bei allen grau,
                    // dieselben Spieler zeigen ab Runde 2-4 ein rotes "2"/"3" (29x im selben Turnier erst "keine", dann 2-3;
                    // 54 von 136 "grauen" Spielern hatten anderswo welche). Mit 0 wirkten starke Gegner bis 9 Punkte
                    // schwaecher, und die 0 ging in den Notizblock. Jetzt: unbekannt (-1) -- gerechnet wird wie bei jedem
                    // unbekannten Wert, nichts wird gemerkt, und beim naechsten Blick wird wieder nachgesehen.
                    schirmAnzahl = -1;
                    Log.info("Arena", name + ": Abzeichen grau -- Schirmherrschaften noch unbekannt (grau heisst oft nur \"noch nicht da\").");
                    weiterNachSchirm();
                    return phase == Phase.AUS ? null : sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                // Zu: auf "Your squad:" tippen -- das Fenster liegt rechts, dort passiert nichts.
                Matcher.Hit zuAnker = eigenesTeam && unterAnker != null ? unterAnker : schwerter;   // eigenes Team: Schliess-Tipp wie beim Gegner
                schirmZuX = zuAnker.centerX() - (int) Math.round(172 * f);
                schirmZuY = zuAnker.centerY() - (int) Math.round(204 * f);
                if (meins) {
                    // Dein Fenster geht links auf -- zu mit einem Tipp rechts auf "Place" deiner Reihe (wie bei den Pals).
                    schirmZuX = unterAnker.centerX() + (int) Math.round(300 * f);
                    schirmZuY = unterAnker.centerY() - (int) Math.round(202 * f);
                }
                Log.action("Klick", "Schirmherrschaften ansehen  [" + bx + "," + by + "]");
                ctx.input().click(bx, by, 2);
                wechsel(Phase.SCHIRM_FENSTER);
                ctx.sleepUntilChanged(20, 900);
                return GameMode.Outcome.ACTED;
            }
            case SCHIRM_FENSTER -> {
                if (!da(screen, ctx, T_SCHIRM_TITEL)) {
                    if (seit > 2000) {
                        Log.info("Arena", "Das Fenster der Schirmherrschaften ging nicht auf -- ohne weiter.");
                        weiterNachSchirm();
                        return phase == Phase.AUS ? null : GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                ctx.sleep(200);   // Bilder im Fenster fertig zeichnen lassen
                Mat frisch = ctx.refresh();
                beweisFoto(ctx, "schirm-stigma");   // das Bild, das gelesen wird (Schirmherren, Titanen, Stigma-Bonus-Sets)
                schirmeZaehlen(frisch != null ? frisch : screen, ctx);
                bogenFoto(ctx, "schirm");
                zuMachen(ctx, T_SCHIRM_TITEL, schirmZuX, schirmZuY, false);
                return GameMode.Outcome.ACTED;
            }
            case PAL -> {
                // Die Pfote links neben dem Gegnernamen (Autor, 01.10.). Ohne Pfote: keine Pals.
                Matcher.Hit knopf = schwerter == null ? null : palKnopf(screen, ctx, schwerter);
                if (knopf == null || !ctx.matcher().has(T_PAL_TITEL)) {
                    // Keine Pfote heisst NICHT "keine Pals": beim Blaettern mit den Pfeilen fehlt sie
                    // (Autor, 02.10.). Dann bleibt es offen -- der Pal-Rundgang klickt ihn einzeln an.
                    if (knopf == null) Log.debug("Arena", "Bei " + name + " keine Pfote zu sehen -- Pals bleiben offen.");
                    weiterNachPals();
                    return phase == Phase.AUS ? null : sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                // Zu: auf "Place" der eigenen Truppe tippen -- das Pal-Fenster deckt "Your squad:" zu.
                double f = ctx.width() / 1159.0;
                Matcher.Hit palAnker = eigenesTeam && unterAnker != null ? unterAnker : schwerter;
                palZuX = palAnker.centerX() + (int) Math.round(300 * f);
                palZuY = palAnker.centerY() - (int) Math.round(202 * f);
                Log.action("Klick", "Pals ansehen  [" + knopf.centerX() + "," + knopf.centerY() + "]");
                ctx.input().click(knopf.centerX(), knopf.centerY(), 2);
                wechsel(Phase.PAL_FENSTER);
                ctx.sleepUntilChanged(20, 900);
                return GameMode.Outcome.ACTED;
            }
            case PAL_FENSTER -> {
                if (!da(screen, ctx, T_PAL_TITEL)) {
                    if (seit > 2000) {
                        Log.info("Arena", "Das Pal-Fenster ging nicht auf -- ohne weiter.");
                        weiterNachPals();
                        return phase == Phase.AUS ? null : GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                ctx.sleep(200);
                Mat frisch = ctx.refresh();
                beweisFoto(ctx, "pals");   // das Bild, das gelesen wird
                gutePals = zaehleSerie(frisch != null ? frisch : screen, ctx, S_PAL_GUT, 0.84);
                bogenFoto(ctx, "pals");
                Log.info("Arena", "Pals von " + name + ": " + gutePals + " gute (+" + Gegnerbuch.palPunkte(gutePals) + ").");
                zuMachen(ctx, T_PAL_TITEL, palZuX, palZuY, true);
                return GameMode.Outcome.ACTED;
            }
            case ZU -> {
                if (!da(screen, ctx, zuTitel)) {
                    if (zuNachPals) weiterNachPals(); else weiterNachSchirm();
                    return phase == Phase.AUS ? null : sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                if (seit < 700) return GameMode.Outcome.WAITING;
                if (zuVersuche >= 3) {
                    Log.info("Arena", "Das kleine Fenster ging nicht zu -- es geht trotzdem weiter.");
                    if (zuNachPals) weiterNachPals(); else weiterNachSchirm();
                    return phase == Phase.AUS ? null : GameMode.Outcome.ACTED;
                }
                zuVersuche++;
                Log.action("Klick", "Kleines Fenster schliessen (" + zuVersuche + ". Versuch)  [" + zuX + "," + zuY + "]");
                ctx.input().click(zuX, zuY, 2);
                phaseSeit = System.currentTimeMillis();
                ctx.sleepUntilChanged(20, 700);
                return GameMode.Outcome.ACTED;
            }
            case HELD -> {
                // Freunde-Bot (Code-Pruefung 16.10.): ohne die Bewertungsbilder ar_held_* bringt das Heldenfenster nur
                // Wartezeit (immer 0 Punkte) -- dann gar nicht erst oeffnen.
                if (schwerter == null || !ctx.matcher().has(T_HELD_ACTIVE) || ctx.matcher().names().stream()
                        .noneMatch(v -> v.startsWith(S_AR_HELD_HAUPT_GUT) || v.startsWith(S_AR_HELD_NEBEN_GUT)
                                || v.startsWith(S_TEAM_HELD_HAUPT_GUT) || v.startsWith(S_TEAM_HELD_NEBEN_GUT)
                                || v.startsWith(S_AR_HELD_HAUPT_SCHLECHT) || v.startsWith(S_AR_HELD_NEBEN_SCHLECHT))) {
                    heldGelesen = true;
                    wechsel(Phase.OEFFNEN);
                    return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                double f = ctx.width() / 1159.0;
                int hx = schwerter.centerX() - (int) Math.round(288 * f);
                int hy = schwerter.centerY() + (int) Math.round(147 * f);
                Log.action("Klick", "Held von " + name + " ansehen  [" + hx + "," + hy + "]");
                ctx.input().click(hx, hy, 2);
                wechsel(Phase.HELD_FENSTER);
                ctx.sleepUntilChanged(20, 900);
                return GameMode.Outcome.ACTED;
            }
            case HELD_FENSTER -> {
                if (!heldFensterDa(screen, ctx)) {
                    if (seit > 2500) {
                        heldGelesen = true;
                        if (++heldFehlInFolge >= 3) {
                            heldMoeglich = false;
                            Log.warn("Arena", "Das Heldenfenster ging dreimal in Folge nicht auf -- Helden werden bis zum Neustart ausgelassen.");
                        } else {
                            Log.info("Arena", "Das Heldenfenster von " + name + " ging nicht auf -- ohne Held weiter.");
                        }
                        // Sicherheitshalber das X antippen: Steht das Fenster doch (nur nicht erkannt), haengt der Bot
                        // sonst davor (Test 3). Im Vergleichsfenster liegt dort nur die Rundenanzeige.
                        int x = (int) Math.round(1120 * ctx.width() / 1159.0), y = (int) Math.round(35 * ctx.height() / 770.0);
                        ctx.input().click(x, y, 2);
                        ctx.sleepUntilChanged(20, 600);
                        wechsel(Phase.OEFFNEN);
                        return GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                heldFehlInFolge = 0;
                ctx.sleep(250);   // Bilder im Fenster fertig zeichnen lassen
                Mat frisch = ctx.refresh();
                heldLesen(frisch != null ? frisch : screen, ctx);
                heldGelesen = true;
                heldZu(ctx);
                return GameMode.Outcome.ACTED;
            }
            case HELD_ZU -> {
                if (!heldFensterDa(screen, ctx)) {
                    wechsel(Phase.OEFFNEN);
                    return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                if (seit > 1500) {
                    if (heldZuVersuche >= 3) {
                        abbrechen("das Heldenfenster ging nicht zu");
                        return GameMode.Outcome.ACTED;
                    }
                    heldZu(ctx);
                    return GameMode.Outcome.ACTED;
                }
                return GameMode.Outcome.WAITING;
            }
            case OEFFNEN -> {
                if (da(screen, ctx, T_DWELLERS)) {
                    wechsel(Phase.EINHEIT);
                    return GameMode.Outcome.ACTED;
                }
                if (oeffnenVersuch >= 5) {
                    Log.info("Arena", "Keine gegnerische Einheit liess sich oeffnen -- ohne Sets weiter.");
                    fertig();
                    return null;
                }
                if (oeffnenVersuch > 0 && seit < 900) return GameMode.Outcome.WAITING;
                int[] p = einheitFinden(screen, ctx, schwerter, oeffnenVersuch);
                oeffnenVersuch++;
                if (p == null) return GameMode.Outcome.WAITING;
                Log.action("Klick", "Gegnerische Einheit oeffnen  [" + p[0] + "," + p[1] + "]");
                ctx.input().click(p[0], p[1], 3);
                phaseSeit = System.currentTimeMillis();
                ctx.sleepUntilChanged(20, 600);
                return GameMode.Outcome.ACTED;
            }
            case EINHEIT -> {
                if (!fensterOffen(screen, ctx)) {
                    if (seit > GEDULD_MS) { fertig(); return null; }
                    return GameMode.Outcome.WAITING;
                }
                gesehen.add(mitte(screen));
                gesehenFelder.add(kaserne ? List.of() : ArenaAussehen.ausschneiden(screen));
                // Konrad 19.11.: Merkmale (Kraft, Leben, Talent, Guardian) auch in der Kaserne merken. Ohne sie galt dort
                // nur die Figur: seine "Mage 1"/"Mage 2" und "Archer 1"/"Archer 2" sehen gleich aus -- der Team-Scan hielt
                // die zweite fuer "Klick kam nicht an" bzw. "einmal herum" und sah 2-3 statt 5 Einheiten (Messlatte 43 statt
                // ~0). Die Ausruestung bleibt in der Kaserne aussen vor (dort laden die Gegenstaende noch, 16.10.).
                gesehenMerkmale.add(ArenaAussehen.merkmale(screen));
                einheiten++;
                if (nurAussehen) return aussehenVergleichen(screen, ctx, schwerter);
                // Autor 22.10.: das Aussehen jeder Einheit merken (Schnell-Blick in der naechsten Runde). Vor
                // steineZaehlen() ausschneiden -- das holt ein neues Bild und gibt dieses frei.
                // Autor 24.10.: auch bei DEINEM Team (fuer den Schnell-Blick in der naechsten Arena) -- nur nicht in der Kaserne.
                List<Mat> felder = kaserne ? List.of() : ArenaAussehen.ausschneiden(screen);
                // Autor 22.10.: dazu Name, Talent, Guardian, Kraftzahl und Skin jeder Einheit (ArenaAussehen.MERKMALE).
                List<Mat> merkmale = kaserne ? List.of() : ArenaAussehen.merkmale(screen);
                int artVorher = guteArtefakte + schlechteArtefakte, totemVorher = guteTotems + schlechteTotems;
                artefaktPruefen(screen, ctx);
                // Autor 25.10. (TR5): Waffe, Ruestung, Totem, Ring, Amulett nach seiner Bewertung -- nur unter Thronsaal 11
                // (die Bewertungen gibt es nur im Ordner des Zweitkonto-Bots). Dein Team: die Items merken.
                // Autor 31.10.: "Flimsy hat ziemlich gute Gegenstaende, nur keine Set-Gegenstaende." Auf seinem PC auch mit Sets (bewertet sind nur Items ohne Set),
                // sobald er Items bewertet hat. Nur das Bild im offenen Fenster, nichts wird angeklickt.
                boolean itemsLesen = de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.SETS)
                        ? eigenesTeam || ArenaItems.aktiv()
                        : de.hustlebot.core.ArenaRegelnNeu.an() && ArenaItems.aktiv();
                List<List<ArenaItems.Gesehen>> items = itemsLesen ? ArenaItems.lesen(screen) : List.of();
                bewertungArtefakt(felder, artVorher);
                if (!de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.TOTEMS)) {   // Totems erst ab Thronsaal 6
                    totemPruefen(screen, ctx);
                    bewertungTotem(felder, totemVorher);
                }
                int bauarbeiterVorher = bauarbeiter;
                lebenPruefen(screen, ctx);
                boolean istBau = bauarbeiter > bauarbeiterVorher;
                letzteKraft = -1;
                kraftPruefen(ctx);
                long lebenHier = letzteLeben;
                // Code-Pruefung 22.10.: VOR dem frischen Bild -- ctx.refresh() gibt screen frei, danach lasen Emblem und
                // Steine ein leeres 0x0-Bild (Embleme nie gefunden, Steine am Ersatz-Anker gezaehlt).
                emblemPruefen(screen, ctx);
                if (!de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.STEINE)) steineZaehlen(screen, ctx);   // Steine erst ab Thronsaal 11
                // Idee B21 (Arena 4, 22.10.): das erste Bild kommt, waehrend die Einheit noch hereingleitet -- Тюлень wurde
                // so in der naechsten Runde wegen "Skin anders" neu angesehen. Jetzt (nach Leben und Kraft, ~0,5 s spaeter)
                // aus einem frischen, ruhigen Bild ablegen, wenn darin noch dasselbe Einheitenfenster steht.
                Mat ruhig = kaserne ? null : ctx.refresh();
                if (ruhig != null && fensterOffen(ruhig, ctx)) {
                    List<Mat> f2 = ArenaAussehen.ausschneiden(ruhig);
                    List<Mat> m2 = ArenaAussehen.merkmale(ruhig);
                    if (!f2.isEmpty() && ArenaAussehen.aehnlichkeit(felder, f2) >= ArenaAussehen.GLEICH_AB - 0.15) {
                        ArenaAussehen.freigeben(felder);
                        ArenaAussehen.freigeben(merkmale);
                        felder = f2;
                        merkmale = m2;
                        if (itemsLesen) items = ArenaItems.lesen(ruhig);   // ruhiges Bild: die Items sitzen fest
                    } else {
                        ArenaAussehen.freigeben(f2);
                        ArenaAussehen.freigeben(m2);
                    }
                }
                // Bauarbeiter zaehlen nicht (Autor 22.10.: die Regel gilt nicht fuer Bauarbeiter). Dein Team wie bisher ohne
                // Guardian/Talent (die Merkmale gibt es seit 24.10. nur fuer den Schnell-Blick).
                if (!nurAussehen && !istBau && !eigenesTeam && !de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.GUARDIANS)) {
                    int gp = ArenaAussehen.guardianPunkte(merkmale, ctx.matcher().templateRoot());
                    if (gp > 0) guteGuardians++;
                    if (gp < 0) ohneGuardian++;
                    setPunkte += gp;
                    int tp = ArenaAussehen.talentPunkte(merkmale, ctx.matcher().templateRoot());
                    if (tp < 0) talentUnter50++;
                    setPunkte += tp;
                    bewertungTalent(merkmale);
                }
                if (itemsLesen) itemsWerten(items);
                if (!felder.isEmpty()) aussehen.add(new ArenaAussehen.Einheit(felder, istBau, merkmale, letzteKraft, lebenHier));
                else ArenaAussehen.freigeben(merkmale);
                if (jeEinheit != null && ctx.frame() != null) {
                    try {
                        jeEinheit.accept(ctx.frame(), letzteKraft);   // Kaserne: Name der Einheit lesen (TeamScan)
                    } catch (Exception e) {
                        Log.debug("Team", "Einheit " + einheiten + " nicht gelesen: " + e.getMessage());
                    }
                }
                if (bauarbeiter > bauarbeiterVorher) {
                    // Autor, 30.09.: bei Bauarbeitern (bis 250k Leben) die Ruestung nicht
                    // ansehen -- das kostete je Einheit 4 s und brachte "?".
                    sets.add("Bauarbeiter");
                    wechsel(Phase.WEITER);
                    // NICHT mit "screen" weiter: steineZaehlen() hat ein neues Bild geholt und
                    // das alte dabei freigegeben (Review 01.10.).
                    return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                // Autor 25.10.: Sets gibt es erst ab Thronsaal 11 -- darunter die Ruestung gar nicht erst aufmachen.
                if (de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.SETS)) {
                    wechsel(Phase.WEITER);
                    return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
                }
                Optional<FesteStellen.Stelle> brust = FesteStellen.holen(S_BRUST);
                // Ohne ar_item_zu keine Sets: das Teile-Fenster liesse sich nicht
                // sicher schliessen (ein Klick "daneben" koennte etwas treffen).
                if (brust.isEmpty() || FesteStellen.holen(S_SETNAME).isEmpty()
                        || !ctx.matcher().has(T_ITEM_ZU)) {
                    wechsel(Phase.WEITER);           // nur Artefakte
                    return GameMode.Outcome.ACTED;
                }
                Rectangle r = brust.get().in(ctx.width(), ctx.height());
                Log.action("Klick", "Brustplatte ansehen (Einheit " + einheiten + ")  ["
                        + (int) r.getCenterX() + "," + (int) r.getCenterY() + "]");
                ctx.input().click((int) r.getCenterX(), (int) r.getCenterY(), 2);
                wechsel(Phase.ITEM);
                ctx.sleepUntilChanged(20, 700);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case ITEM -> {
                // Auch ohne Set-Kopfzeile ist das Fenster offen, wenn sein X da ist (30.09.:
                // bei Einheiten ohne Set wartete der Bot sonst 2,5 s vergebens, je Einheit 4 s).
                // 11.10.: "Equipped by character:" steht in JEDEM Gegenstand-Fenster -- auch ohne Set-Leiste (Divine,
                // Mythical, Legendary) und in Autors Burg ohne X. Vorher wartete der Bot dort 3,7 s und zaehlte "unbekannt".
                boolean offen = da(screen, ctx, T_ITEM_ZU) || da(screen, ctx, T_ITEM_EQUIPPED)
                        || (ctx.matcher().has(T_ITEM_FENSTER)
                            ? da(screen, ctx, T_ITEM_FENSTER)
                            : !da(screen, ctx, T_DWELLERS) || seit > 500);
                if (!offen) {
                    if (seit > GEDULD_MS) {
                        sets.add("?");
                        setPunkte += ArenaSets.UNBEKANNT;
                        wechsel(Phase.WEITER);
                        return GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                setLesen(ctx);
                basisItemPruefen(ctx, "ruestung");
                itemSchliessen(screen, ctx);
                wechsel(waffeNoetig ? Phase.WAFFE : Phase.WEITER);
                ctx.sleepUntilChanged(20, 500);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case WAFFE -> {
                // Erst muss das Einheitenfenster wieder da sein, dann die Waffe (oben rechts) antippen.
                if (!fensterOffen(screen, ctx)) {
                    if (seit > 800 && seit < GEDULD_MS) itemSchliessen(screen, ctx);
                    if (seit > GEDULD_MS) {
                        waffeAufgeben("Einheitenfenster kam nicht zurueck");
                        wechsel(Phase.WEITER);
                    }
                    return GameMode.Outcome.WAITING;
                }
                waffeNoetig = false;
                waffeModus = true;
                stueck = 1;
                int wx = (int) Math.round(WAFFE[0] * ctx.width()), wy = (int) Math.round(WAFFE[1] * ctx.height());
                Log.action("Klick", "Waffe ansehen (Einheit " + einheiten + ")  [" + wx + "," + wy + "]");
                ctx.input().click(wx, wy, 2);
                wechsel(Phase.WAFFE_ITEM);
                ctx.sleepUntilChanged(20, 700);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case WAFFE_ITEM -> {
                boolean offen = da(screen, ctx, T_ITEM_ZU) || da(screen, ctx, T_ITEM_EQUIPPED)
                        || (ctx.matcher().has(T_ITEM_FENSTER) ? da(screen, ctx, T_ITEM_FENSTER) : !da(screen, ctx, T_DWELLERS) || seit > 500);
                if (!offen) {
                    if (seit > GEDULD_MS) {
                        waffeAufgeben("Waffenfenster ging nicht auf");
                        wechsel(Phase.WEITER);
                        return GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                setLesen(ctx);
                basisItemPruefen(ctx, "waffe");
                if (brustSet != null && !ringNoetig) waffeAufgeben("nicht verbucht");   // Sicherheitsnetz: nie ein Set verlieren
                itemSchliessen(screen, ctx);
                wechsel(ringNoetig ? Phase.RING : Phase.WEITER);
                ctx.sleepUntilChanged(20, 500);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case RING -> {
                // Autor 22.10.: Brust und Waffe ergeben noch keine 6 Teile -- auch den Ring ansehen.
                if (!fensterOffen(screen, ctx)) {
                    if (seit > 800 && seit < GEDULD_MS) itemSchliessen(screen, ctx);
                    if (seit > GEDULD_MS) {
                        waffeAufgeben("Einheitenfenster kam nach der Waffe nicht zurueck");
                        wechsel(Phase.WEITER);
                    }
                    return GameMode.Outcome.WAITING;
                }
                ringNoetig = false;
                stueck = 2;
                waffeModus = true;
                int rx = (int) Math.round(RING[0] * ctx.width()), ry = (int) Math.round(RING[1] * ctx.height());
                Log.action("Klick", "Ring ansehen (Einheit " + einheiten + ")  [" + rx + "," + ry + "]");
                ctx.input().click(rx, ry, 2);
                wechsel(Phase.RING_ITEM);
                ctx.sleepUntilChanged(20, 700);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case RING_ITEM -> {
                boolean offen = da(screen, ctx, T_ITEM_ZU) || da(screen, ctx, T_ITEM_EQUIPPED)
                        || (ctx.matcher().has(T_ITEM_FENSTER) ? da(screen, ctx, T_ITEM_FENSTER) : !da(screen, ctx, T_DWELLERS) || seit > 500);
                if (!offen) {
                    if (seit > GEDULD_MS) {
                        waffeAufgeben("Ringfenster ging nicht auf");
                        wechsel(Phase.WEITER);
                        return GameMode.Outcome.ACTED;
                    }
                    return GameMode.Outcome.WAITING;
                }
                setLesen(ctx);
                basisItemPruefen(ctx, "ring");
                if (brustSet != null) waffeAufgeben("nicht verbucht");
                itemSchliessen(screen, ctx);
                wechsel(Phase.WEITER);
                ctx.sleepUntilChanged(20, 500);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);
            }
            case WEITER -> {
                if (!fensterOffen(screen, ctx)) {
                    // Das Gegenstandsfenster ist noch offen -- noch einmal schliessen.
                    if (seit > 800 && seit < GEDULD_MS) itemSchliessen(screen, ctx);
                    if (seit > GEDULD_MS) {
                        // Pruefer 29.10.: G4nawa -- 4 von 6 Einheiten gelesen, gespeichert als "Einheiten 4". Das ist ein
                        // Abbruch, keine fertige Pruefung.
                        unvollstaendig = true;
                        Log.info("Arena", Sprache.t(name + ": the item window does not close -- stopped after " + einheiten
                                        + " units, the check counts as incomplete.",
                                name + ": das Gegenstandsfenster geht nicht zu -- nach " + einheiten
                                        + " Einheiten aufgehoert, die Pruefung gilt als unvollstaendig."));
                        wechsel(Phase.SCHLIESSEN);
                    }
                    return GameMode.Outcome.WAITING;
                }
                if (einheiten >= MAX_EINHEITEN) {
                    wechsel(Phase.SCHLIESSEN);
                    return GameMode.Outcome.ACTED;
                }
                boolean weiter = ctx.matcher().has(T_DWELLER_NEXT)
                        && ctx.clickIfVisible(screen, T_DWELLER_NEXT, kaserne ? "Naechste eigene Einheit" : "Naechste gegnerische Einheit");
                if (!weiter && kaserne) {
                    weiter = ctx.clickIfVisibleIfKnown(screen, T_KASERNE_WEITER, "Naechste eigene Einheit (Switch dwellers)");
                }
                if (!weiter) {
                    wechsel(Phase.SCHLIESSEN);       // keine weitere Einheit
                    return GameMode.Outcome.ACTED;
                }
                // Autor 24.10.: Wartezeit nach "Naechste Einheit" mit Blick auf die Figur statt blind bis 700 ms
                // (siehe naechsteEinheitAbwarten). Enthaelt die 200 ms Ruhe: die Einheit gleitet herein -- erst
                // das ruhige Bild vergleichen.
                naechsteEinheitAbwarten(ctx);
                Mat neu = ctx.refresh();
                int gleich = neu == null ? -1 : gesehenAn(neu);
                if (gleich < 0 && zwillingJetzt) {
                    // Gleiche Figur, andere Ausruestung: die Gegenstaende koennten noch laden (Platzhalter-Kreise,
                    // Kasernen-Bild 16.10.). Kurz warten und noch einmal vergleichen, sonst zaehlt eine Einheit doppelt.
                    ctx.sleep(400);
                    Mat spaeter = ctx.refresh();
                    if (spaeter != null) {
                        neu = spaeter;
                        gleich = gesehenAn(neu);
                    }
                    if (gleich < 0) {
                        Log.info("Arena", Sprache.t(name + ": unit " + (einheiten + 1) + " looks like an earlier one, but has other equipment -- "
                                        + "it is another unit, the bot keeps going.",
                                name + ": Einheit " + (einheiten + 1) + " sieht aus wie eine fruehere, hat aber andere Ausruestung -- "
                                        + "eine weitere Einheit, der Bot klickt weiter."));
                    }
                }
                if (gleich >= 0 && gleich == gesehen.size() - 1 && gesehen.size() > 0 && weiterOhneWirkung < 2) {
                    // Noch DIESELBE Einheit: der Klick kam nicht an (01.10.: bei JUXED nach 1 Einheit
                    // "einmal herum" und zu). Noch einmal "weiter".
                    weiterOhneWirkung++;
                    return GameMode.Outcome.ACTED;
                }
                weiterOhneWirkung = 0;
                if (gleich >= 0) {
                    wechsel(Phase.SCHLIESSEN);       // einmal herum
                    return GameMode.Outcome.ACTED;
                }
                wechsel(Phase.EINHEIT);
                if (neu != null && kette < KETTE_MAX) { kette++; return schrittInnen(neu, ctx, schwerter); }
                return GameMode.Outcome.ACTED;
            }
            case SCHLIESSEN -> {
                if (kaserne) { fertig(); return null; }   // das Fenster schliesst TeamScan (X / Burg-Knopf)
                if (da(screen, ctx, T_DWELLERS)) {
                    // Nur einmal klicken. Das Fenster geht mit Animation zu, das naechste Bild
                    // zeigt es noch -- der zweite Klick traf das X des Vergleichsfensters
                    // dahinter (dieselbe Stelle) und warf den Bot in die Pyramide (01.10.).
                    if (!schliessenGeklickt
                            && ctx.clickIfVisible(screen, T_DWELLER_CLOSE, "Einheitenfenster schliessen")) {
                        schliessenGeklickt = true;
                        ctx.sleepUntilChanged(20, 900);
                        return GameMode.Outcome.ACTED;
                    }
                    if (seit > GEDULD_MS) { fertig(); return null; }
                    return GameMode.Outcome.WAITING;
                }
                fertig();
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Ist das Fenster da? IMMER im ganzen Bild gesucht (findAll). Die
     * Sparregel des Matchers ("nie gesehene Vorlagen nur jedes sechste Mal")
     * darf hier nicht greifen: sonst hielte der Bot ein offenes Fenster fuer
     * zu und klickte ein zweites Mal hinein.
     *
     * Autor 24.10. (Ideenliste A01): Fenster mit festem Platz (ar_dwellers, ar_item_fenster, ar_pal_titel,
     * ar_schirm_titel) werden zuerst nur in ihrem Kasten gesucht ({@link ArenaKaesten}, ~1 statt 25-40 ms) -- derselbe
     * Vergleich mit derselben Schwelle, nur auf dem kleineren Ausschnitt. Steht das Bild nicht im Kasten, wird das erste
     * Mal und danach jedes 8. Mal wie bisher im ganzen Bild gesucht (Sicherheitsnetz). Die Sparregel des Matchers
     * greift weiterhin nicht.
     */
    private static boolean da(Mat screen, BotContext ctx, String vorlage) {
        if (!ctx.matcher().has(vorlage)) return false;
        return ArenaKaesten.sichtbar(ctx.matcher(), screen, vorlage, DA_SCHWELLE,
                () -> !ctx.matcher().findAll(screen, vorlage, DA_SCHWELLE, 1).isEmpty());
    }
    private static final double DA_SCHWELLE = 0.84;

    /**
     * Heldenfenster offen? "Active" unter der Hauptfaehigkeit. Test 2 (15.10.): bei rotem Leuchten nur 0,83,
     * bei blauem 0,95 -- die uebliche Schwelle 0,84 war zu streng, alles andere im Bild liegt unter 0,40.
     */
    private static boolean heldFensterDa(Mat screen, BotContext ctx) {
        // Test 3 (15.10.): Ein Held ganz ohne Faehigkeiten hat kein "Active" -- das Fenster galt als zu, und der Bot
        // stand 3 min davor. Die Kopfzeile "Battle" rechts oben steht immer da (0,95; sonst hoechstens 0,58).
        if (ctx.matcher().has(T_HELD_BATTLE) && !ctx.matcher().findAll(screen, T_HELD_BATTLE, 0.85, 1).isEmpty()) return true;
        return ctx.matcher().has(T_HELD_ACTIVE) && !ctx.matcher().findAll(screen, T_HELD_ACTIVE, 0.72, 1).isEmpty();
    }
    private static final String T_HELD_BATTLE = "ts_held_battle";

    /** Rotes X oben rechts im Heldenfenster (1120,35 bei 1159 breit). */
    private void heldZu(BotContext ctx) {
        heldZuVersuche++;
        int x = (int) Math.round(1120 * ctx.width() / 1159.0), y = (int) Math.round(35 * ctx.height() / 770.0);
        Log.action("Klick", "Heldenfenster schliessen  [" + x + "," + y + "]");
        ctx.input().click(x, y, 2);
        wechsel(Phase.HELD_ZU);
        ctx.sleepUntilChanged(20, 800);
    }

    /** Haupt- und Nebenfaehigkeiten bewerten, alle fuenf ausschneiden (logs/arena-helden). */
    private void heldLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        double[][] stellen = new double[5][];
        stellen[0] = TeamScan.HELD_HAUPT;
        for (int i = 0; i < 4; i++) stellen[i + 1] = TeamScan.HELD_NEBEN[i];
        StringBuilder werte = new StringBuilder();
        int gute = 0, schlechteNeben = 0;
        boolean hauptGut = false, hauptSchlecht = false;
        for (int i = 0; i < 5; i++) {
            int x = (int) (stellen[i][0] * ctx.width()), y = (int) (stellen[i][1] * ctx.height());
            int w = (int) (stellen[i][2] * ctx.width()), h = (int) (stellen[i][3] * ctx.height());
            Rect r = new Rect(x, y, Math.min(w, screen.cols() - x), Math.min(h, screen.rows() - y));
            double best = 0;
            // Autor 22.10.: "Die guten Heldenfaehigkeiten, die ich hochgeladen habe, sollen auch fuer die Arena zaehlen" --
            // die Arena-Bilder (ar_held_*) und die aus dem Team-Scan (held_*).
            List<String> vorlagen = new ArrayList<>(ctx.matcher().serie(i == 0 ? S_AR_HELD_HAUPT_GUT : S_AR_HELD_NEBEN_GUT));
            vorlagen.addAll(ctx.matcher().serie(i == 0 ? S_TEAM_HELD_HAUPT_GUT : S_TEAM_HELD_NEBEN_GUT));
            for (String t : vorlagen) {
                Optional<Matcher.Hit> hit = ctx.matcher().findImBereich(screen, t, r, 0.0);   // 0.0: den echten Wert ins Protokoll
                if (hit.isPresent()) best = Math.max(best, hit.get().score());
            }
            boolean gut = best >= HELD_GUT_AB;
            boolean motivBekannt = gut;
            if (i == 0) hauptGut = gut; else if (gut) gute++;
            werte.append(i == 0 ? "Haupt " : " | Neben " + i + " ").append(String.format(java.util.Locale.ROOT, "%.2f", best));
            // Autor 22.10.: schlechte Heldenfaehigkeiten zaehlen genauso dagegen (ar_held_haupt_schlecht_* / ar_held_neben_schlecht_*).
            if (!gut) {
                double schlecht = 0;
                for (String t : ctx.matcher().serie(i == 0 ? S_AR_HELD_HAUPT_SCHLECHT : S_AR_HELD_NEBEN_SCHLECHT)) {
                    Optional<Matcher.Hit> hit = ctx.matcher().findImBereich(screen, t, r, 0.0);
                    if (hit.isPresent()) schlecht = Math.max(schlecht, hit.get().score());
                }
                if (schlecht >= HELD_GUT_AB) {
                    motivBekannt = true;
                    if (i == 0) hauptSchlecht = true; else schlechteNeben++;
                    werte.append(" schlecht");
                } else {
                    // Autor 30.10.: keine Vorlage getroffen -- seine Bewertung aus den Boegen (ArenaBewertung).
                    String b = ArenaBewertung.wert(i == 0 ? "held-haupt" : "held-neben", ArenaBewertung.merkmal(bild, x, y, w, h));
                    motivBekannt = b != null;
                    if (ArenaBewertung.GUT.equals(b)) {
                        if (i == 0) hauptGut = true; else gute++;
                        werte.append(" gut (Bogen)");
                    } else if (ArenaBewertung.SCHLECHT.equals(b)) {
                        if (i == 0) hauptSchlecht = true; else schlechteNeben++;
                        werte.append(" schlecht (Bogen)");
                    }
                }
            }
            if (bild != null) heldAusschnittSichern(bild, x, y, w, h, i == 0 ? "haupt" : "neben" + i, !motivBekannt);
        }
        heldHauptGut = hauptGut;
        heldGuteNeben = gute;
        heldHauptSchlecht = hauptSchlecht;
        heldSchlechteNeben = schlechteNeben;
        int punkte = heldPunkte();
        String haupt = hauptGut ? "gut" : hauptSchlecht ? "schlecht" : "normal";
        heldText = "Held: Haupt " + haupt + ", " + gute + " gute Neben" + (schlechteNeben > 0 ? ", " + schlechteNeben + " schlechte Neben" : "")
                + " (" + (punkte >= 0 ? "+" : "") + punkte + ")";
        Log.info("Arena", "Held von " + name + ": Hauptfaehigkeit " + haupt + ", gute Nebenfaehigkeiten " + gute
                + ", schlechte " + schlechteNeben + " (" + (punkte >= 0 ? "+" : "") + punkte + ") -- Werte [" + werte + "].");
    }

    /** Autor 22.10.: wie gut, nur dagegen -- Haupt schlecht -8, jede schlechte Neben -2. */
    private int heldPunkte() {
        return (Boolean.TRUE.equals(heldHauptGut) ? 8 : 0) + 2 * heldGuteNeben
                - (heldHauptSchlecht ? 8 : 0) - 2 * heldSchlechteNeben;
    }

    /**
     * Autor 01.11. (Pruefung 3.137, fuer die naechste Pruefung): EIN Foto je Lesung, im Moment des Lesens -- Set-Popups (nur unter 0,5 s offen), Abzeichen, Schirmherren/Titanen
     * samt Stigma-Bonus-Sets (dasselbe Fenster) und Pals. Nur mit beweisfotos.txt "an" ({@link de.hustlebot.core.Beleg}): sonst wird nichts gespeichert und
     * kein Ordner angelegt (Autors Vorgabe: nichts waechst). Auch mit Schalter hoechstens {@link #BEWEIS_MAX_JE_ART} Fotos je Art und Tag.
     */
    private void beweisFoto(BotContext ctx, String art) {
        if (!de.hustlebot.core.Beleg.an()) return;
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        beweisFotoSchreiben(bild, art, eigenesTeam ? "ich" : name, einheiten);
    }

    static final int BEWEIS_MAX_JE_ART = 250;
    private static final java.util.Map<String, Integer> BEWEIS_ZAEHLER = new java.util.HashMap<>();
    private static java.time.LocalDate beweisTag;

    /** @return true, wenn das Foto wirklich auf der Platte liegt (false: Schalter aus, Grenze erreicht oder Fehler). */
    static synchronized boolean beweisFotoSchreiben(BufferedImage bild, String art, String wer, int einheit) {
        if (bild == null || !de.hustlebot.core.Beleg.an()) return false;
        java.time.LocalDate heute = java.time.LocalDate.now();
        if (!heute.equals(beweisTag)) {
            beweisTag = heute;
            BEWEIS_ZAEHLER.clear();
        }
        int n = BEWEIS_ZAEHLER.merge(art, 1, Integer::sum);
        if (n > BEWEIS_MAX_JE_ART) return false;
        try {
            return de.hustlebot.core.Beleg.schreiben(bild, "png", java.nio.file.Path.of("logs", "beweis", beweisDateiname(art, wer, einheit, java.time.LocalTime.now())).toFile());
        } catch (Exception e) {
            Log.debug("Arena", "Beweisfoto (" + art + ") nicht gespeichert: " + e.getMessage());
            return false;
        }
    }

    /** "set/083015-123-Denis-e3.png": Art als Ordner, dann Uhrzeit mit Tausendstel, Spieler und Einheit. */
    static String beweisDateiname(String art, String wer, int einheit, java.time.LocalTime zeit) {
        String w = wer == null ? "" : wer.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        return art + "/" + zeit.format(java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS")) + "-" + w + "-e" + einheit + ".png";
    }

    /**
     * Autor 22.10.: "Gib mir wie bei den Guardians einen Bogen fuer Schirmherrschaften und Pals -- ich kreise ein, was gut
     * und was schlecht ist." Das offene Fenster einmal je Gegner nach logs\arena-bogen\<art>\ (hoechstens 300 Bilder).
     */
    private void bogenFoto(BotContext ctx, String art) {
        if (eigenesTeam || nurAussehen) return;
        if (!de.hustlebot.core.Beleg.autorsSammlung()) return;   // 01.11.: die Boegen macht nur Autor, bei den Freunden las das niemand
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "arena-bogen", art);
            java.nio.file.Files.createDirectories(ordner);
            try (var l = java.nio.file.Files.list(ordner)) {
                if (l.count() >= 300) return;   // Autor 22.10. abends: mehr Motive sammeln (vorher 120)
            }
            String wer = name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
            javax.imageio.ImageIO.write(kartenstreifen(bild, art), "png", ordner.resolve(wer + ".png").toFile());
        } catch (Exception e) {
            Log.debug("Arena", "Bogen-Foto (" + art + ") nicht gespeichert: " + e.getMessage());
        }
    }

    /**
     * Autor 01.11.: nie mehr das ganze Fenster als PNG. Bogen braucht nur den Kartenstreifen, scharf, damit die Motive gleich
     * bleiben (gemessen bei 1159 x 770, dieselben Zahlen wie in werkzeuge\sitzung-22-10\bogen.py).
     */
    static BufferedImage kartenstreifen(BufferedImage bild, String art) {
        int[] s = "schirm".equals(art) ? new int[]{600, 1148, 82, 262} : new int[]{58, 608, 148, 300};
        double fx = bild.getWidth() / 1159.0, fy = bild.getHeight() / 770.0;
        int x = (int) (s[0] * fx), y = (int) (s[2] * fy);
        return bild.getSubimage(x, y, Math.min((int) ((s[1] - s[0]) * fx), bild.getWidth() - x), Math.min((int) ((s[3] - s[2]) * fy), bild.getHeight() - y));
    }

    private void heldAusschnittSichern(BufferedImage bild, int x, int y, int w, int h, String teil, boolean alsDatei) {
        try {
            int x0 = Math.max(0, x), y0 = Math.max(0, y);
            BufferedImage teilBild = bild.getSubimage(x0, y0, Math.min(w, bild.getWidth() - x0), Math.min(h, bild.getHeight() - y0));
            // 01.11.: Motive, die Autor schon bewertet hat, brauchen kein Bild mehr; bei den Freunden las sie nie jemand.
            if (alsDatei && de.hustlebot.core.Beleg.autorsSammlung()) {
                java.nio.file.Path ordner = java.nio.file.Path.of("logs", "arena-helden");
                java.nio.file.Files.createDirectories(ordner);
                String wer = name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
                javax.imageio.ImageIO.write(teilBild, "png", ordner.resolve(wer + "-" + teil + ".png").toFile());
            }
            BufferedImage kopie = new BufferedImage(teilBild.getWidth(), teilBild.getHeight(), BufferedImage.TYPE_INT_RGB);
            kopie.getGraphics().drawImage(teilBild, 0, 0, null);
            heldBilder.add(kopie);
        } catch (Exception e) {
            Log.debug("Arena", "Held-Ausschnitt nicht gespeichert: " + e.getMessage());
        }
    }

    private void wechsel(Phase p) {
        phase = p;
        phaseSeit = System.currentTimeMillis();
    }

    /** Schirmherrschaften fertig: weiter zu den Pals, zu den Einheiten -- oder ganz fertig. */
    private void weiterNachSchirm() {
        if (palsAnsehen) wechsel(Phase.PAL);
        else weiterNachPals();
    }

    /** Pals fertig: weiter zu den Einheiten -- oder fertig, wenn nur nachgeholt wurde. */
    private void weiterNachPals() {
        if (nurExtras) extrasFertig();
        else wechsel(heldDran() ? Phase.HELD : Phase.OEFFNEN);
    }

    /** Den Gegner-Helden ansehen? Nur bei Gegnern, einmal je Pruefung, solange das Fenster aufgeht. */
    private boolean heldDran() {
        // Autor 22.10.: auch deinen Helden ansehen -- dein Team wird genauso gerechnet wie jeder Gegner.
        return !kaserne && !nurExtras && !heldGelesen && heldMoeglich;
    }
    /** Geht das Heldenfenster dreimal in Folge nicht auf, wird der Held bis zum Neustart ausgelassen. */
    private static volatile boolean heldMoeglich = true;
    private static int heldFehlInFolge;

    /** Ins Leere tippen, dann (Phase ZU) warten, bis das Fenster weg ist. */
    private void zuMachen(BotContext ctx, String titel, int x, int y, boolean nachPals) {
        zuTitel = titel;
        zuX = x;
        zuY = y;
        zuVersuche = 1;
        zuNachPals = nachPals;
        Log.action("Klick", "Kleines Fenster schliessen  [" + x + "," + y + "]");
        ctx.input().click(x, y, 2);
        wechsel(Phase.ZU);
        ctx.sleepUntilChanged(20, 700);
    }

    private void extrasFertig() {
        ergebnis = new Ergebnis(name, clan, 0, 0, "", 0, 0, 0, 0,
                schirmAnzahl, guteSchirme, guteStigmas, gutePals, true, eigenesTeam);
        Log.info("Arena", "Nachgeholt bei " + name + ": " + extrasText() + ".");
        phase = Phase.AUS;
    }

    /** "Schirmherrschaften 2 (1 gute, 1 Stigma, +6), gute Pals 1 (+5)". */
    private String extrasText() {
        StringBuilder b = new StringBuilder();
        if (schirmAnzahl >= 0) {
            b.append("Schirmherrschaften ").append(schirmAnzahl).append(" (").append(guteSchirme).append(" gute, ")
             .append(guteStigmas).append(" Stigma, +").append(Gegnerbuch.schirmPunkte(schirmAnzahl, guteSchirme, guteStigmas)).append(")");
        }
        if (gutePals >= 0) {
            b.append(b.length() > 0 ? ", " : "").append("gute Pals ").append(gutePals)
             .append(" (+").append(Gegnerbuch.palPunkte(gutePals)).append(")");
        }
        return b.length() == 0 ? "nichts" : b.toString();
    }

    /**
     * Schnell-Blick, eine Einheit: gleich aussehende Einheit unter den abgelegten? Dann weiter. Sonst Leben lesen --
     * ein Bauarbeiter (bis 250k) zaehlt nicht (Autor 22.10.), jede andere Abweichung beendet den Blick: neu ansehen.
     */
    private GameMode.Outcome aussehenVergleichen(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        Mat bild = screen;
        List<Mat> felder = ArenaAussehen.ausschneiden(bild);
        try {
            int i = ArenaAussehen.passend(vergleich, felder, erwartet);
            if (i < 0 && !nachgeladen) {
                // Kasernen-Bild 16.10.: kurz nach dem Oeffnen standen statt der Gegenstaende noch Platzhalter-Kreise.
                nachgeladen = true;
                ctx.sleep(400);
                Mat neu = ctx.refresh();
                if (neu != null) {
                    bild = neu;
                    ArenaAussehen.freigeben(felder);
                    felder = ArenaAussehen.ausschneiden(bild);
                    i = ArenaAussehen.passend(vergleich, felder, erwartet);
                }
            }
            nachgeladen = false;
            if (i >= 0) {
                erwartet = (i + 1) % vergleich.size();
                if (!vergleich.get(i).bauarbeiter()) {
                    String was = merkmalAnders(bild, ctx, vergleich.get(i));
                    if (was != null) {
                        abweichung = true;
                        Log.info("Arena", Sprache.t(name + ": unit " + einheiten + " has the same equipment, but " + was
                                        + " is different from last time -- the team is looked at again in full.",
                                name + ": Einheit " + einheiten + " hat dieselbe Ausruestung, aber " + was
                                        + " ist anders als beim letzten Mal -- das Team wird neu angesehen."));
                        wechsel(Phase.SCHLIESSEN);
                        return GameMode.Outcome.ACTED;
                    }
                    passendeOhneBau++;
                    folge.add(i);
                    // Schon zweimal "zurueck" in der Folge: umgestellt, egal wie der Rest aussieht (Autor 22.10.).
                    if (ArenaAussehen.abstiege(folge, false) >= 2) {
                        abweichung = true;
                        Log.info("Arena", Sprache.t(name + ": the units stand on other positions than last time (order " + folgeText()
                                        + ") -- the team is looked at again in full.",
                                name + ": die Einheiten stehen auf anderen Positionen als beim letzten Mal (Reihenfolge " + folgeText()
                                        + ") -- das Team wird neu angesehen."));
                        wechsel(Phase.SCHLIESSEN);
                        return GameMode.Outcome.ACTED;
                    }
                }
                wechsel(Phase.WEITER);
                return sofortWeiter(ctx, schwerter, GameMode.Outcome.ACTED);   // ganz schnell durchklicken
            }
            int vorher = bauarbeiter;
            lebenPruefen(bild, ctx);
            if (bauarbeiter > vorher) {
                Log.info("Arena", Sprache.t(name + ": unit " + einheiten + " looks different, but it is a builder -- does not count.",
                        name + ": Einheit " + einheiten + " sieht anders aus, ist aber ein Bauarbeiter -- zaehlt nicht."));
                if (erwartet >= 0 && !vergleich.isEmpty()) erwartet = (erwartet + 1) % vergleich.size();
                wechsel(Phase.WEITER);
                return GameMode.Outcome.ACTED;
            }
            abweichung = true;
            Log.info("Arena", Sprache.t(name + ": unit " + einheiten + " looks different from last time -- the team is looked at again in full.",
                    name + ": Einheit " + einheiten + " sieht anders aus als beim letzten Mal -- das Team wird neu angesehen."));
            wechsel(Phase.SCHLIESSEN);
            return GameMode.Outcome.ACTED;
        } finally {
            ArenaAussehen.freigeben(felder);
        }
    }

    /**
     * Autor 22.10.: Name, Talent, Guardian, Skin (5 %) und Kraft (3 %) einer wiedererkannten Einheit -- was ist anders?
     * null = alles gleich, oder alte Ablage ohne Merkmale. Die Kraft wird nur gelesen, wenn das Bild der Zahl anders
     * aussieht (Lesen kostet Zeit; der Schnell-Blick soll hoechstens 5 s dauern).
     */
    private String merkmalAnders(Mat bild, BotContext ctx, ArenaAussehen.Einheit alt) {
        if (alt.merkmale().isEmpty()) return null;
        List<Mat> neu = ArenaAussehen.merkmale(bild);
        try {
            String was = ArenaAussehen.abweichung(alt, neu);
            if (was != null) {
                // Idee B21 (Arena 4, 22.10.): die Figur bewegt sich, Effekte flackern -- vor dem Alarm 0,3 s spaeter
                // ein zweites Bild. Nur wenn es auch dort anders ist, zaehlt es.
                ctx.sleep(300);
                Mat spaeter = ctx.refresh();
                if (spaeter == null) return was;
                ArenaAussehen.freigeben(neu);
                neu = ArenaAussehen.merkmale(spaeter);
                bild = spaeter;
                was = ArenaAussehen.abweichung(alt, neu);
                if (was != null) return was;
            }
            if (!ArenaAussehen.zahlBildGleich(alt, neu, ArenaAussehen.M_KRAFT)) {
                long k = kraftLesen(ctx);
                if (k > 0 && ArenaAussehen.kraftAnders(alt.kraft(), k)) return Sprache.t("the power (" + alt.kraft() + " -> " + k + ")",
                        "die Kraft (" + alt.kraft() + " -> " + k + ")");
            }
            if (!ArenaAussehen.zahlBildGleich(alt, neu, ArenaAussehen.M_LEBEN)) {
                int vorher = bauarbeiter;
                lebenPruefen(bild, ctx);
                bauarbeiter = vorher;   // im Schnell-Blick nichts buchen
                if (letzteLeben > 0 && ArenaAussehen.kraftAnders(alt.leben(), letzteLeben)) return Sprache.t(
                        "the life (" + alt.leben() + " -> " + letzteLeben + ")", "die Leben (" + alt.leben() + " -> " + letzteLeben + ")");
            }
            return null;   // weniger als 3 % oder nicht lesbar: zaehlt nicht
        } finally {
            ArenaAussehen.freigeben(neu);
        }
    }

    /** Die Folge fuers Protokoll, wie Autor zaehlt: 1, 2, 3 ... */
    private String folgeText() {
        StringBuilder b = new StringBuilder();
        for (int i : folge) b.append(b.length() > 0 ? "-" : "").append(i + 1);
        return b.toString();
    }

    /** Code-Review 26.10.: lief der letzte Scan bis zum Ende (SCHLIESSEN)? Abgebrochene sagen ueber Items nichts. */
    private boolean letzteVollstaendig;

    boolean letzteVollstaendig() {
        return letzteVollstaendig;
    }

    /** Bis zum Ende gelaufen (SCHLIESSEN) und nicht wegen eines haengenden Fensters abgebrochen (Pruefer 29.10.)? */
    static boolean vollstaendig(boolean schliessen, boolean unvollstaendig) {
        return schliessen && !unvollstaendig;
    }

    private void fertig() {
        letzteVollstaendig = vollstaendig(phase == Phase.SCHLIESSEN, unvollstaendig);
        wechselBericht();   // Autor 24.10.: wie die Wechsel zur naechsten Einheit erkannt wurden
        if (nurAussehen) {
            // Schnell-Blick: kein Ergebnis fuer den Notizblock, nur "gleich" oder "anders".
            int erwartet = ArenaAussehen.ohneBauarbeiter(vergleich);
            if (einheiten <= 0) aussehenErgebnis = null;
            else if (abweichung) aussehenErgebnis = Boolean.TRUE;
            else if (passendeOhneBau < erwartet) {
                // Eine bekannte Einheit fehlt (z. B. durch einen Bauarbeiter ersetzt) -- auch das ist ein anderes Team.
                Log.info("Arena", Sprache.t(name + ": only " + passendeOhneBau + " of " + erwartet + " known units found -- looked at again.",
                        name + ": nur " + passendeOhneBau + " von " + erwartet + " bekannten Einheiten gefunden -- wird neu angesehen."));
                aussehenErgebnis = Boolean.TRUE;
            } else if (!ArenaAussehen.reihenfolgeGleich(folge)) {
                Log.info("Arena", Sprache.t(name + ": same units, but on other positions (order " + folgeText()
                                + ") -- looked at again.",
                        name + ": dieselben Einheiten, aber auf anderen Positionen (Reihenfolge " + folgeText()
                                + ") -- wird neu angesehen."));
                aussehenErgebnis = Boolean.TRUE;
            } else {
                Log.info("Arena", Sprache.t(name + ": all " + einheiten + " units look like last time -- the known values stay.",
                        name + ": alle " + einheiten + " Einheiten sehen aus wie beim letzten Mal -- die bekannten Werte bleiben."));
                aussehenErgebnis = Boolean.FALSE;
            }
            ArenaAussehen.freigebenAlle(vergleich);
            vergleich = new ArrayList<>();
            phase = Phase.AUS;
            gesehenLeeren();
            return;
        }
        double steinAbzug = ArenaSteine.abzug(legendaereSteine, epischeSteine);
        setPunkte += steinAbzug;
        String kurz = String.join(", ", sets)
                + (starke > 0 ? (sets.isEmpty() ? "" : ", ") + starke + "x ab 7 Mio Kraft (+" + kraftPunkte + ")" : "")
                + (legendaereSteine + epischeSteine > 0 ? ", Steine " + legendaereSteine + " legendaer/" + epischeSteine
                        + " episch (" + zahl(steinAbzug) + ")" : "")
                + (allianzVoll ? ", starke Allianz (+8)" : "")
                + (guteTotems > 0 ? ", " + guteTotems + "x gutes Totem (+" + (GUTES_TOTEM * guteTotems) + ")" : "")
                + (guteGuardians > 0 ? ", " + guteGuardians + "x guter Guardian (+" + (ArenaAussehen.GUTER_GUARDIAN * guteGuardians) + ")" : "")
                + (ohneGuardian > 0 ? ", " + ohneGuardian + "x kein Guardian (" + (ArenaAussehen.KEIN_GUARDIAN * ohneGuardian) + ")" : "")
                + (talentUnter50 > 0 ? ", " + talentUnter50 + "x Talent unter 50 (" + (ArenaAussehen.TALENT_UNTER_50 * talentUnter50) + ")" : "")
                + (schlechteTotems > 0 ? ", " + schlechteTotems + "x schlechtes Totem (" + (SCHLECHTES_TOTEM * schlechteTotems) + ")" : "")
                + (schlechteArtefakte > 0 ? ", " + schlechteArtefakte + "x schlechtes Artefakt (" + (SCHLECHTES_ARTEFAKT * schlechteArtefakte) + ")" : "")
                + (guteEmbleme > 0 ? ", " + guteEmbleme + "x gutes Emblem (+" + emblemPunkte + ")" : "")
                + (heldText.isEmpty() ? "" : ", " + heldText);
        setPunkte += heldPunkte();   // Held (Autor 15.10.): Haupt 8, gute Neben je 2 -- zaehlt wie die Sets
        // Autor 25.10. (TR5): deine Items merken -- nur, wenn mindestens zwei Einheiten angesehen wurden (sonst bliebe
        // von einer abgebrochenen Pruefung eine halbe Liste).
        if (eigenesTeam && letzteVollstaendig && einheiten >= 2 && !eigeneItems.isEmpty()) ArenaItems.eigeneSpeichern(eigeneItems);
        if (!itemZaehler.isEmpty()) {
            Log.info("Arena", Sprache.t("Items of " + name + ": " + ArenaItems.zusammenfassung(itemZaehler) + " (" + (itemPunkte >= 0 ? "+" : "") + itemPunkte + ")",
                    "Items von " + name + ": " + ArenaItems.zusammenfassung(itemZaehler) + " (" + (itemPunkte >= 0 ? "+" : "") + itemPunkte + ")"));
        }
        ergebnis = new Ergebnis(name, clan, einheiten, (int) Math.round(setPunkte), kurz, guteArtefakte, bauarbeiter,
                gelbeSteine, roteSteine, schirmAnzahl, guteSchirme, guteStigmas, gutePals, false, eigenesTeam);
        Log.info("Arena", "Einheiten von " + name + ": " + einheiten + " angesehen, Sets "
                + (sets.isEmpty() ? "nicht gelesen" : "[" + kurz + "] = " + zahl(setPunkte) + " Punkte")
                + ", gute Artefakte " + guteArtefakte + " (+" + (3 * guteArtefakte) + ")"
                + ", Bauarbeiter " + bauarbeiter + " (+" + (5 * bauarbeiter) + ")"
                + ", Steine " + ArenaSteine.text(gelbeSteine, roteSteine)
                + (schirmAnzahl >= 0 || gutePals >= 0 ? ", " + extrasText() : "") + ".");
        phase = Phase.AUS;
        gesehenLeeren();
    }

    /**
     * Wo eine gegnerische Einheit sitzt. Versuch 0: an den runden Rahmen
     * erkennen. Versuch 1 und 2: die Stellen, an denen bei JEDER Anzahl eine
     * Einheit steht -- rechts neben der Mitte (bei ungerader Anzahl ein halber
     * Abstand, bei gerader ein ganzer). Der Held steht immer ganz links.
     */
    private int[] einheitFinden(Mat screen, BotContext ctx, Matcher.Hit schwerter, int versuch) {
        if (schwerter == null) return null;
        int mitteX = schwerter.centerX();
        // Gemessen an Autors Bild (1919 breit): Reihe 225 Pixel unter den
        // Schwertern, Abstand der Einheiten 140 Pixel.
        int y = schwerter.centerY() + (int) Math.round(ctx.height() * 0.218);
        // Ob das Spiel seine Oberflaeche nach Hoehe oder Breite skaliert, ist
        // noch nicht gemessen -- beide Abstaende werden probiert.
        int abstandH = (int) Math.round(ctx.height() * 0.136);
        int abstandB = (int) Math.round(ctx.width() * 0.073);
        if (versuch == 0) {
            int[] kreis = rechtesterKreis(screen, mitteX, y, abstandH);
            if (kreis != null) return kreis;
            versuch = 1;
        }
        return switch (versuch) {
            case 1 -> new int[]{mitteX + abstandH / 2, y};
            case 2 -> new int[]{mitteX + abstandH, y};
            case 3 -> new int[]{mitteX + abstandB / 2, y};
            default -> new int[]{mitteX + abstandB, y};
        };
    }

    /** Sucht runde Rahmen in der Gegnerreihe und liefert den rechtesten. */
    private int[] rechtesterKreis(Mat screen, int mitteX, int y, int abstand) {
        int halb = (int) (abstand * 4.5);
        int x0 = Math.max(0, mitteX - halb), x1 = Math.min(screen.cols(), mitteX + halb);
        int y0 = Math.max(0, y - abstand), y1 = Math.min(screen.rows(), y + abstand);
        if (x1 - x0 < abstand || y1 - y0 < abstand) return null;
        Mat grau = new Mat(), kreise = new Mat();
        Mat streifen = new Mat(screen, new Rect(x0, y0, x1 - x0, y1 - y0));
        try {
            Imgproc.cvtColor(streifen, grau, Imgproc.COLOR_BGR2GRAY);
            Imgproc.medianBlur(grau, grau, 5);
            int r = (int) (abstand * 0.42);
            Imgproc.HoughCircles(grau, kreise, Imgproc.HOUGH_GRADIENT, 1.2, abstand * 0.7,
                    100, 30, (int) (r * 0.75), (int) (r * 1.15));
            int[] beste = null;
            for (int i = 0; i < kreise.cols(); i++) {
                double[] k = kreise.get(0, i);
                if (k == null) continue;
                int kx = x0 + (int) k[0], ky = y0 + (int) k[1];
                if (Math.abs(ky - y) > abstand * 0.5) continue;
                if (beste == null || kx > beste[0]) beste = new int[]{kx, ky};
            }
            Log.debug("Arena", "Einheiten-Rahmen gefunden: " + kreise.cols()
                    + (beste == null ? "" : ", rechtester bei " + beste[0] + "," + beste[1]));
            return beste;
        } catch (Exception e) {
            Log.debug("Arena", "Kreissuche ging nicht: " + e.getMessage());
            return null;
        } finally {
            grau.release();
            kreise.release();
            streifen.release();
        }
    }

    /** Gutes Totem im ersten kleinen Feld? (Autor 22.10., Vorlagen ar_totem_* -- zaehlt wie die Sets, +3.) */
    private void totemPruefen(Mat screen, BotContext ctx) {
        Optional<FesteStellen.Stelle> s = FesteStellen.holen(S_TOTEM);
        if (s.isEmpty()) return;
        boolean vorlagen = false;
        for (String n : ctx.matcher().names()) {
            if (n.startsWith("ar_totem_") || n.startsWith("ar_totemschlecht_")) {
                vorlagen = true;
                break;
            }
        }
        if (!vorlagen) return;
        Rectangle r = s.get().in(ctx.width(), ctx.height());
        int luft = Math.max(6, r.width / 5);
        int x0 = Math.max(0, r.x - luft), y0 = Math.max(0, r.y - luft);
        int x1 = Math.min(screen.cols(), r.x + r.width + luft);
        int y1 = Math.min(screen.rows(), r.y + r.height + luft);
        Mat ecke = new Mat(screen, new Rect(x0, y0, x1 - x0, y1 - y0));
        try {
            for (String n : ctx.matcher().names()) {
                if (!n.startsWith("ar_totem_")) continue;
                if (!ctx.matcher().findAll(ecke, n, 0.82, 1).isEmpty()) {
                    guteTotems++;
                    setPunkte += GUTES_TOTEM;
                    Log.info("Arena", "Gutes Totem bei Einheit " + einheiten + ": " + n + " (+" + GUTES_TOTEM + ")");
                    return;
                }
            }
            for (String n : ctx.matcher().names()) {
                if (!n.startsWith("ar_totemschlecht_")) continue;
                if (!ctx.matcher().findAll(ecke, n, 0.82, 1).isEmpty()) {
                    schlechteTotems++;
                    setPunkte += SCHLECHTES_TOTEM;
                    Log.info("Arena", "Schlechtes Totem bei Einheit " + einheiten + ": " + n + " (" + SCHLECHTES_TOTEM + ")");
                    return;
                }
            }
        } finally {
            ecke.release();
        }
    }

    /** Lage des Artefakts und des Totems im Ausruestungsfeld (388 x 103): nur das Bild oben, ohne Stufe und Balken, wie auf den Boegen. */
    private static final int BOGEN_ARTEFAKT_X = 299, BOGEN_TOTEM_X = 8, BOGEN_Y = 5, BOGEN_B = 84, BOGEN_H = 46;

    /** Autor 30.10.: Hat keine Vorlage getroffen, zaehlt seine Bewertung aus den Boegen. Gut +3, mittel +1, schlecht -3 wie bei den Items. */
    private void bewertungArtefakt(List<Mat> felder, int vorher) {
        if (felder.size() < 3 || guteArtefakte + schlechteArtefakte != vorher) return;
        float[] merkmal = ArenaBewertung.merkmal(felder.get(2), BOGEN_ARTEFAKT_X, BOGEN_Y, BOGEN_B, BOGEN_H);
        String w = ArenaBewertung.wert("artefakte", merkmal);
        boolean goettlich = ArenaBewertung.goettlich("artefakte", merkmal);   // Autor 31.10.: gut und mittel doppelt
        if (ArenaBewertung.GUT.equals(w)) {
            guteArtefakte++;
            if (goettlich) setPunkte += 3;   // die ersten +3 je gutem Artefakt rechnet Gegnerbuch.teamPunkte
        } else if (ArenaBewertung.SCHLECHT.equals(w)) {
            schlechteArtefakte++;
            setPunkte += SCHLECHTES_ARTEFAKT;
        } else if (ArenaBewertung.MITTEL.equals(w)) {
            setPunkte += goettlich ? 2 : 1;
        }
    }

    private void bewertungTotem(List<Mat> felder, int vorher) {
        if (felder.size() < 3 || guteTotems + schlechteTotems != vorher) return;
        float[] merkmal = ArenaBewertung.merkmal(felder.get(2), BOGEN_TOTEM_X, BOGEN_Y, BOGEN_B, BOGEN_H);
        String w = ArenaBewertung.wert("totems", merkmal);
        boolean goettlich = ArenaBewertung.goettlich("totems", merkmal);   // Autor 31.10.: gut und mittel doppelt
        if (ArenaBewertung.GUT.equals(w)) {
            guteTotems++;
            setPunkte += goettlich ? 2 * GUTES_TOTEM : GUTES_TOTEM;
        } else if (ArenaBewertung.SCHLECHT.equals(w)) {
            schlechteTotems++;
            setPunkte += SCHLECHTES_TOTEM;
        } else if (ArenaBewertung.MITTEL.equals(w)) {
            setPunkte += goettlich ? 2 : 1;
        }
    }

    /** Rote Talente: gut +2, mittel +1, schlecht -2 wie die Guardians. Merkmal 1 ist das Talent. */
    private void bewertungTalent(List<Mat> merkmale) {
        if (merkmale.size() < 2) return;
        Mat t = merkmale.get(1);
        String w = ArenaBewertung.wert("talente", ArenaBewertung.merkmal(t, 0, 0, t.cols(), t.rows()));
        if (ArenaBewertung.GUT.equals(w)) setPunkte += 2;
        else if (ArenaBewertung.SCHLECHT.equals(w)) setPunkte -= 2;
        else if (ArenaBewertung.MITTEL.equals(w)) setPunkte += 1;
    }

    /**
     * Autor 25.10. (TR5): die Items einer Einheit nach seiner Bewertung. Gegner: gut +3, mittel +1, schlecht -3, OP wie
     * gut und gezaehlt (Sperre/Abzug in ArenaMode). Dein Team: nur merken -- am Ende nach arena-items\eigen.tsv.
     */
    private void itemsWerten(List<List<ArenaItems.Gesehen>> items) {
        einheitItemsGewertet = false;
        if (eigenesTeam) {
            if (items.isEmpty()) return;
            String wer = einheitenNamen.size() == einheiten ? einheitenNamen.get(einheiten - 1) : "Einheit " + einheiten;
            eigeneItems.add(java.util.Map.entry(wer, items));
            return;
        }
        java.util.Map<ArenaItems.Wert, Integer> hier = new java.util.EnumMap<>(ArenaItems.Wert.class);
        int punkte = 0, unbekannt = 0;
        for (List<ArenaItems.Gesehen> varianten : items) {
            ArenaItems.Wert w = ArenaItems.wert(varianten);
            if (w == null) {
                unbekannt++;
                continue;
            }
            hier.merge(w, 1, Integer::sum);
            itemZaehler.merge(w, 1, Integer::sum);
            punkte += ArenaItems.punkteFuer(varianten);
            if (w == ArenaItems.Wert.OP) opItems++;
        }
        // Autor 03.11.: auch die nicht erkannten melden -- ihre Bilder liegen in arena-aussehen und kommen auf die Bewertungsseite.
        if (unbekannt > 0 && ArenaItems.aktiv()) {
            Log.info("Arena", "Einheit " + einheiten + ": " + unbekannt + " Item(s) ohne Set nicht erkannt (Bild in arena-aussehen, kommt auf die Bewertungsseite).");
        }
        if (hier.isEmpty()) return;
        einheitItemsGewertet = true;
        itemPunkte += punkte;
        setPunkte += punkte;
        Log.info("Arena", Sprache.t("Items of unit " + einheiten + ": " + ArenaItems.zusammenfassung(hier) + " (" + (punkte >= 0 ? "+" : "") + punkte + ")",
                "Items bei Einheit " + einheiten + ": " + ArenaItems.zusammenfassung(hier) + " (" + (punkte >= 0 ? "+" : "") + punkte + ")"));
    }

    private void artefaktPruefen(Mat screen, BotContext ctx) {
        Optional<FesteStellen.Stelle> s = FesteStellen.holen(S_ARTE);
        if (s.isEmpty()) return;
        Rectangle r = s.get().in(ctx.width(), ctx.height());
        // Etwas Luft um den Rahmen, das Artefakt sitzt nicht pixelgenau.
        int luft = Math.max(6, r.width / 5);
        int x0 = Math.max(0, r.x - luft), y0 = Math.max(0, r.y - luft);
        int x1 = Math.min(screen.cols(), r.x + r.width + luft);
        int y1 = Math.min(screen.rows(), r.y + r.height + luft);
        Mat ecke = new Mat(screen, new Rect(x0, y0, x1 - x0, y1 - y0));
        try {
            for (String n : ctx.matcher().names()) {
                if (!n.startsWith("ar_art_")) continue;
                // findAll statt find: find wuerde bei nie gesehenen Vorlagen nur
                // jede sechste Abfrage ganz rechnen (Sparregel im Matcher).
                if (!ctx.matcher().findAll(ecke, n, 0.82, 1).isEmpty()) {
                    guteArtefakte++;
                    Log.info("Arena", "Gutes Artefakt bei Einheit " + einheiten + ": " + n);
                    return;
                }
            }
            // Autor 22.10.: "zu den guten Artefakten noch schlechte Artefakte aufnehmen" (ar_artschlecht_...). Jeder Fund
            // zieht SCHLECHTES_ARTEFAKT ab -- es zaehlt wie die Sets, dann bleibt der Notizblock, wie er ist.
            for (String n : ctx.matcher().names()) {
                if (!n.startsWith("ar_artschlecht_")) continue;
                if (!ctx.matcher().findAll(ecke, n, 0.82, 1).isEmpty()) {
                    schlechteArtefakte++;
                    setPunkte += SCHLECHTES_ARTEFAKT;
                    Log.info("Arena", "Schlechtes Artefakt bei Einheit " + einheiten + ": " + n + " (" + SCHLECHTES_ARTEFAKT + ")");
                    return;
                }
            }
        } finally {
            ecke.release();
        }
    }

    /**
     * Lebenspunkte der Einheit -- die erste Zahl rechts neben dem roten Herz
     * ("2 777 300/2 777 300"). Bis 250 000 ist es ein Bauarbeiter (Autor,
     * 25.09.: "diese gegner sind meistens auch staerker", +5 Punkte je Stueck).
     * Ohne Herz-Vorlage geht es ueber die Stelle ar_stelle_leben; ohne beide
     * wird nichts gezaehlt.
     */
    /**
     * Steine auf den acht Gegenstaenden der Einheit (Autor, 30.09.). Anker ist die Mitte der
     * Lebens-Zeile: rechts neben dem Herzen, sonst die feste Stelle ar_stelle_leben.
     */
    /** Namen der Einheiten des Gegners, in der Reihenfolge des Durchblaetterns (Autor 22.10.: Merkmal fuer die Sperre). */
    private final java.util.List<String> einheitenNamen = new java.util.ArrayList<>();

    /** "Name1|Name2|..." der zuletzt angesehenen Einheiten (leer, wenn keiner lesbar war). */
    String einheitenNamenText() {
        return String.join("|", einheitenNamen);
    }

    /**
     * Autor 22.10. abends: "Die Einheitennamen siehst du, wenn du auf eine Einheit drueckst und durchskippst." Der Name
     * steht gross oben im Einheitenfenster, rund 31 Punkte ueber der Mitte der Lebens-Zeile (Foto Arena 22: "Закись"
     * ueber "13 700/13 700", Fenster 1159 x 770). Gelesen wird ein Streifen 300 x 34 darum.
     */
    private void einheitNameLesen(BufferedImage bild, int ax, int ay) {
        if (bild == null || einheitenNamen.size() >= 8) return;
        double fx = bild.getWidth() / 1159.0, fy = bild.getHeight() / 770.0;
        int w = (int) Math.round(300 * fx), h = (int) Math.round(34 * fy);
        int x = Math.max(0, ax - w / 2), y = Math.max(0, ay - (int) Math.round(48 * fy));
        if (x + w > bild.getWidth() || y + h > bild.getHeight()) return;
        try {
            // Autor 01.11. (Pruefung 3.137): erst die Schrift umschneiden und mehrfach lesen (ArenaLeser.einheitNameLesen) -- der ganze Streifen in Originalgroesse verlor
            // Zeichen ("Bogen 1" -> "c Bogen", "Magier 3" -> "Magier", "КАЛАНТЭ" -> "АЛАНТЭ"). Nur wenn das nichts ergibt, die alte Lesung.
            String t = ArenaLeser.einheitNameLesen(bild, x, y, w, h);
            if (t.isBlank()) t = String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, h))).strip();
            if (!t.isEmpty()) einheitenNamen.add(t.replace('|', '/'));
        } catch (Exception e) {
            Log.debug("Arena", "Einheiten-Name nicht gelesen: " + e.getMessage());
        }
    }

    private void steineZaehlen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int ax = -1, ay = -1;
        if (ctx.matcher().has(T_HERZ) && screen != null) {
            var h = ctx.matcher().findAll(screen, T_HERZ, 0.84, 1);
            if (!h.isEmpty()) {
                Matcher.Hit herz = h.get(0);
                // Die Lebens-Zeile beginnt hinter dem Herzen und ist etwa 16 % der Breite lang.
                ax = herz.x() + herz.width() + (int) Math.round(ctx.width() * 0.08);
                ay = herz.y() + herz.height() / 2;
            }
        }
        if (ax < 0) {
            Optional<FesteStellen.Stelle> s = FesteStellen.holen(S_LEBEN);
            if (s.isEmpty()) return;
            Rectangle r = s.get().in(ctx.width(), ctx.height());
            ax = (int) r.getCenterX();
            ay = (int) r.getCenterY();
        }
        // Autor 24.10.: auch die Namen DEINER Einheiten merken (nur nicht in der Kaserne -- dort liest TeamScan selbst).
        if (!kaserne) einheitNameLesen(bild, ax, ay);
        ArenaSteine.Zaehlung z = ArenaSteine.zaehlen(bild, ax, ay);
        // Der Glanz auf den Gegenstaenden wandert: ein zweites Bild kurz danach, je Platz die
        // dunklere Lesung (rot vor orange vor gelb) -- siehe ArenaSteine.
        ctx.sleep(250);
        if (ctx.refresh() != null) {
            BufferedImage zweites = ctx.frame();
            if (zweites != null) z = ArenaSteine.vereinen(z, ArenaSteine.zaehlen(zweites, ax, ay));
        }
        gelbeSteine += z.gelb();
        roteSteine += z.rot();
        legendaereSteine += z.orange();
        epischeSteine += z.violett();
        ArenaSteine.protokoll(einheiten, z);
        // Sichtpruefung (Autor, 08.10.: "visuell gegenchecken, ob der Steinezaehler funktioniert"):
        // die ersten 4 Einheiten je Programmlauf als Foto mit dem Zaehlergebnis im Dateinamen.
        if (steinFotos < 4 && ctx.frame() != null) {
            steinFotos++;
            try {
                java.nio.file.Path ordner = java.nio.file.Path.of("logs", "auge");
                String datei = "steine-" + de.hustlebot.core.Zeitzone.uhrzeit().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"))
                        + "-gelb" + z.gelb() + "-rot" + z.rot() + ".png";
                de.hustlebot.core.Beleg.schreiben(ctx.frame(), "png", ordner.resolve(datei).toFile());
            } catch (Exception e) {
                Log.debug("Arena", "Steine-Foto nicht gespeichert: " + e.getMessage());
            }
        }
    }

    private static int steinFotos;
    private static boolean tieferFotografiert;
    /** Pruefer 29.10.: einmal je Programmlauf ein Foto, wenn erst das dritte (tiefste) Fenster den Set-Namen fand. */
    private static boolean nochTieferFotografiert;

    /** Rot (Schirmherrschaften da) oder grau (keine)? Zaehlt kraeftig rote Punkte rund ums Abzeichen. */
    static boolean abzeichenRot(BufferedImage bild, int cx, int cy, double f) {
        if (bild == null) return false;
        int r0 = (int) Math.round(18 * f), rot = 0, alle = 0;
        for (int y = cy - r0; y <= cy + r0; y++) {
            for (int x = cx - r0; x <= cx + r0; x++) {
                if (x < 0 || y < 0 || x >= bild.getWidth() || y >= bild.getHeight()) continue;
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                alle++;
                // Kraeftiges Rot; der lachsfarbene Kraftbalken drumherum (g ~170) zaehlt nicht,
                // das graue Abzeichen ist braun (r < 170).
                if (r > 170 && g < 110 && b < 110 && r - g > 90) rot++;
            }
        }
        return alle > 0 && rot * 6 >= alle;   // mindestens ein Sechstel rot
    }

    /** Dein eigenes Abzeichen (Autor 22.10.): lila mit Zahl = Schirmherrschaften, grau/braun = keine. */
    static boolean abzeichenLila(BufferedImage bild, int cx, int cy, double f) {
        if (bild == null) return false;
        int r0 = (int) Math.round(18 * f), lila = 0, alle = 0;
        for (int y = cy - r0; y <= cy + r0; y++) {
            for (int x = cx - r0; x <= cx + r0; x++) {
                if (x < 0 || y < 0 || x >= bild.getWidth() || y >= bild.getHeight()) continue;
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                alle++;
                if (b > 150 && r > 90 && g < 130 && b - g > 70) lila++;
            }
        }
        return alle > 0 && lila * 6 >= alle;
    }

    /** Im Fenster "Patronage": Schirmherrschaften zaehlen, gute und gute Stigmas erkennen (SchirmLeser, 11.10.). */
    private void schirmeZaehlen(Mat screen, BotContext ctx) {
        SchirmLeser.Ergebnis e = SchirmLeser.lesen(screen, ctx);
        schirmAnzahl = e.anzahl();
        guteSchirme = e.gute();
        guteStigmas = e.stigmas();
        SchirmLeser.protokoll("von " + name, e);
    }

    /** Wie viele verschiedene Stellen eine Bilderserie trifft (hoechstens 3) -- fuer die Pals. */
    private static int zaehleSerie(Mat screen, BotContext ctx, String serie, double schwelle) {
        return SchirmLeser.zaehleSerie(screen, ctx.matcher(), serie, schwelle);
    }

    /** Die Pfote links neben dem Gegnernamen: 270 Punkte links, 56 unter den Schwertern (Autors Bilder). */
    private static Matcher.Hit palKnopf(Mat screen, BotContext ctx, Matcher.Hit schwerter) {
        if (!ctx.matcher().has(T_PAL_KNOPF)) return null;
        double f = ctx.width() / 1159.0;
        int ex = schwerter.centerX() - (int) Math.round(270 * f);
        int ey = schwerter.centerY() + (int) Math.round(56 * f);
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_PAL_KNOPF, 0.84, 3)) {
            if (Math.abs(h.centerX() - ex) < 30 * f && Math.abs(h.centerY() - ey) < 30 * f) return h;
        }
        return null;
    }

    /** Kraft-Zahl links oben im Einheiten-Fenster (ueber "Emblem"), gemessen 11.10. an logs/auge/steine-*.png. */
    private static final double[] KRAFT_STELLE = {25 / 1159.0, 140 / 770.0, 195 / 1159.0, 60 / 770.0};
    static final long STARK_AB = 8_000_000L, MITTEL_AB = 6_000_000L;

    /** Punkte fuer die Kraft EINER Einheit (Autor 22.10.): ab 7 Mio +2, ab 8 Mio +3, ab 9 Mio +4, ab 10 Mio +5, je weitere Mio +1. */
    static int kraftBonus(long kraft) {
        long mio = kraft / 1_000_000L;
        return mio >= 7 ? (int) (mio - 5) : 0;
    }

    // ------------------------------------------------------------------
    //  Allianz (Autor, 11.10.)
    // ------------------------------------------------------------------
    // "Wenn der Bot bei einem Gegnerteam in diesen sechs Einheiten alle vier von diesen Skills sieht (Sonne, Faust,
    // Blitz, Pfeile), soll er +8 Punkte berechnen -- das ist eine sehr starke Allianz. Die Talente koennen bei
    // unterschiedlichen Einheiten sein, es zaehlt nur, dass alle vier im Team sind."
    // Die Talente stehen im Vergleichsfenster als Kreis oben rechts an jeder Einheit. Vorlagen (nur das Symbol, 26x26)
    // aus Autors eigener Reihe geschnitten (templates/gelernt/arena/allianz). Gemessen an 7 Vergleichsfenstern:
    // vorhanden 0,75-1,00, nicht vorhanden hoechstens 0,38 -- Graustufen, weil dasselbe Talent rot oder orange sein kann.
    private static final String[] ALLIANZ = {"sonne", "blitz", "faust", "pfeile"};
    private static final double ALLIANZ_SCHWELLE = 0.70;
    /** Eine Vorlage eines Talents: talent = Index in ALLIANZ, mat = Graustufen-Symbol. */
    private record AllianzVorlage(int talent, Mat mat) { }

    private static java.util.List<AllianzVorlage> allianzVorlagen;

    /**
     * Autor 01.11. (Pruefung 3.137): dasselbe Talent hat je nach Einheit ein anderes Aussehen -- die Faust ist rot, lila oder orange, in Graustufen mit ganz
     * anderen Helligkeiten (rote Vorlage gegen die lila Faust: 0,38, gegen die orange: 0,34; "nicht da" hoechstens 0,42). Darum darf jedes Talent mehrere Vorlagen
     * haben: {@code ar_allianz_<talent>.png} und {@code ar_allianz_<talent>_<farbe>.png}; es zaehlt, wenn EINE passt.
     */
    private static synchronized java.util.List<AllianzVorlage> allianzVorlagen() {
        if (allianzVorlagen == null) {
            java.util.List<AllianzVorlage> v = new java.util.ArrayList<>();
            java.nio.file.Path ordner = Matcher.gelernt("arena", "allianz");
            for (int i = 0; i < ALLIANZ.length; i++) {
                String n = ALLIANZ[i];
                java.util.List<java.nio.file.Path> dateien = new java.util.ArrayList<>();
                try (java.util.stream.Stream<java.nio.file.Path> ls = java.nio.file.Files.list(ordner)) {
                    ls.filter(f -> {
                        String dn = f.getFileName().toString();
                        return dn.equals("ar_allianz_" + n + ".png") || (dn.startsWith("ar_allianz_" + n + "_") && dn.endsWith(".png"));
                    }).sorted().forEach(dateien::add);
                } catch (Exception e) {
                    Log.debug("Arena", "Allianz-Vorlagen " + n + " nicht gelistet: " + e.getMessage());
                }
                for (java.nio.file.Path f : dateien) {
                    try {
                        BufferedImage b = javax.imageio.ImageIO.read(f.toFile());
                        if (b != null) v.add(new AllianzVorlage(i, grau(b)));
                    } catch (Exception e) {
                        Log.debug("Arena", "Allianz-Vorlage " + f.getFileName() + " fehlt: " + e.getMessage());
                    }
                }
            }
            allianzVorlagen = v;
        }
        return allianzVorlagen;
    }

    private static Mat grau(BufferedImage b) {
        BufferedImage rgb = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(b, 0, 0, null);
        Mat farbe = de.hustlebot.core.ScreenCapture.toMat(rgb), g = new Mat();
        org.opencv.imgproc.Imgproc.cvtColor(farbe, g, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
        farbe.release();
        return g;
    }

    /** Im Vergleichsfenster: hat die Gegnerreihe (unten) alle vier Allianz-Talente? Dann +8. */
    private void allianzPruefen(BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        // Gegnerreihe: gemessen 400-510 von 770 Hoehe, 300-960 von 1159 Breite (Bild 181938, Stage 1).
        int y0 = (int) (bild.getHeight() * 400 / 770.0), y1 = (int) (bild.getHeight() * 510 / 770.0);
        int x0 = (int) (bild.getWidth() * 300 / 1159.0), x1 = (int) (bild.getWidth() * 960 / 1159.0);
        int maske = allianzMaske(bild, x0, y0, x1, y1);
        int treffer = Integer.bitCount(maske);
        if (treffer == ALLIANZ.length) {
            allianzVoll = true;
            setPunkte += 8;
            Log.info("Arena", name + " hat alle vier Allianz-Talente (Sonne, Blitz, Faust, Pfeile) -- starke Allianz (+8).");
        } else if (treffer > 0) {
            Log.debug("Arena", name + ": Allianz-Talente " + allianzText(maske) + " (" + treffer + " von 4, kein Zuschlag).");
        }
    }

    /** Ausrufezeichen am Talent: das Talent ist da, wirkt aber nicht (Autor 11.10.). */
    private static Mat ausrufVorlage;
    private static boolean ausrufGeladen;

    /**
     * Welche der vier Allianz-Talente (Sonne, Blitz, Faust, Pfeile) in diesem Bildausschnitt WIRKSAM zu sehen sind --
     * als Bitmaske (Bit i = ALLIANZ[i]). Fuer die Gegnerreihe im Vergleichsfenster und die eigene Reihe im
     * Kasernen-Fenster (TeamScan, Auftrag 6). Autor, 11.10.: "Steht neben dem Talent ein Ausrufezeichen, zaehlt es
     * nicht." Darum je Talent alle Fundstellen ansehen und nur eine ohne "!" oben rechts zaehlen. 0, wenn Vorlagen fehlen.
     */
    static int allianzMaske(BufferedImage bild, int x0, int y0, int x1, int y1) {
        return allianzMaske(bild, x0, y0, x1, y1, 1.0);
    }

    /** skala: wie gross die Talente hier sind, gemessen an der Arena (eigene Kaserne 11.10.: 1,1). */
    static int allianzMaske(BufferedImage bild, int x0, int y0, int x1, int y1, double skala) {
        java.util.List<AllianzVorlage> vorlagen = allianzVorlagen();
        if (bild == null) return 0;
        for (int t = 0; t < ALLIANZ.length; t++) {
            final int talent = t;
            if (vorlagen.stream().noneMatch(x -> x.talent() == talent)) return 0;   // eine Vorlage je Talent muss es geben
        }
        synchronized (ArenaPruefer.class) {
            if (!ausrufGeladen) {
                ausrufGeladen = true;
                try {
                    BufferedImage a = javax.imageio.ImageIO.read(Matcher.gelernt("arena", "allianz")
                            .resolve("ar_allianz_aus.png").toFile());
                    if (a != null) ausrufVorlage = grau(a);
                } catch (Exception e) {
                    Log.debug("Arena", "Ausrufezeichen-Vorlage fehlt: " + e.getMessage());
                }
            }
        }
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(bild.getWidth(), x1); y1 = Math.min(bild.getHeight(), y1);
        if (x1 - x0 < 30 || y1 - y0 < 30) return 0;
        Mat reihe = grau(bild.getSubimage(x0, y0, x1 - x0, y1 - y0));
        if (skala > 0 && Math.abs(skala - 1.0) > 0.01) {
            Mat klein = new Mat();
            org.opencv.imgproc.Imgproc.resize(reihe, klein, new org.opencv.core.Size(Math.round(reihe.cols() / skala),
                    Math.round(reihe.rows() / skala)), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);
            reihe.release();
            reihe = klein;
        }
        int maske = 0;
        try {
            for (AllianzVorlage av : vorlagen) {
                int i = av.talent();
                if ((maske & (1 << i)) != 0) continue;   // ein Talent zaehlt einmal
                Mat v = av.mat();
                if (v.cols() > reihe.cols() || v.rows() > reihe.rows()) continue;
                Mat erg = new Mat();
                org.opencv.imgproc.Imgproc.matchTemplate(reihe, v, erg, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
                boolean wirksam = false;
                for (int n = 0; n < 6 && !wirksam; n++) {   // bis zu sechs Einheiten mit demselben Talent
                    org.opencv.core.Core.MinMaxLocResult m = org.opencv.core.Core.minMaxLoc(erg);
                    if (m.maxVal < ALLIANZ_SCHWELLE) break;
                    int px = (int) m.maxLoc.x, py = (int) m.maxLoc.y;
                    wirksam = !ausrufDaneben(reihe, px + v.cols() / 2, py + v.rows() / 2);
                    // diese Fundstelle loeschen, dann die naechste suchen
                    org.opencv.imgproc.Imgproc.rectangle(erg, new org.opencv.core.Point(px - 20, py - 20),
                            new org.opencv.core.Point(px + 20, py + 20), new org.opencv.core.Scalar(-1), -1);
                }
                erg.release();
                if (wirksam) maske |= 1 << i;
            }
        } finally {
            reihe.release();
        }
        return maske;
    }

    /** Alle vier Talente in der Maske? */
    static boolean allianzVoll(int maske) {
        return maske == (1 << ALLIANZ.length) - 1;
    }

    /** "sonne, blitz" fuer eine Maske. */
    static String allianzText(int maske) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ALLIANZ.length; i++) {
            if ((maske & (1 << i)) != 0) b.append(b.length() == 0 ? "" : ", ").append(ALLIANZ[i]);
        }
        return b.toString();
    }

    /** Steht oben rechts am Talent (Mitte gx/gy in der Reihe) ein rotes Ausrufezeichen? Gemessen 11.10.: +7..+35 / -30..+5. */
    private static boolean ausrufDaneben(Mat reihe, int gx, int gy) {
        Mat a = ausrufVorlage;
        if (a == null) return false;
        int x = Math.max(0, gx + 5), y = Math.max(0, gy - 32);
        int w = Math.min(reihe.cols() - x, 40), h = Math.min(reihe.rows() - y, 42);
        if (w < a.cols() || h < a.rows()) return false;
        Mat ausschnitt = reihe.submat(y, y + h, x, x + w), erg = new Mat();
        try {
            org.opencv.imgproc.Imgproc.matchTemplate(ausschnitt, a, erg, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            return org.opencv.core.Core.minMaxLoc(erg).maxVal >= 0.75;
        } finally {
            erg.release();
            ausschnitt.release();
        }
    }

    /**
     * Autor, 11.10.: "Wenn eine Truppe mehr als 8 Mio Power hat in einem Gegner-Team, bedeutet das +3 Punkte."
     * Gelesen wird die Zahl links oben im Einheiten-Fenster ("9 271 872"). Unsichere Lesungen zaehlen nicht.
     */
    /** Die Kraftzahl der offenen Einheit, -1 = nicht sicher gelesen. Bucht nichts. */
    private long kraftLesen(BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return -1;
        int x = (int) Math.round(KRAFT_STELLE[0] * bild.getWidth()), y = (int) Math.round(KRAFT_STELLE[1] * bild.getHeight());
        int w = (int) Math.round(KRAFT_STELLE[2] * bild.getWidth()), h = (int) Math.round(KRAFT_STELLE[3] * bild.getHeight());
        List<Long> zahlen = ArenaLeser.zahlenIn(bild, x, y, w, h);
        long kraft = zahlen.size() == 1 ? zahlen.get(0) : -1;
        long min = kraftMin();
        if (kraft < min || kraft > 500_000_000L) kraft = kraftAusText(ArenaLeser.liesTextEnglisch(bild, x, y, w, h));
        return kraft < min || kraft > 500_000_000L ? -1 : kraft;
    }

    /**
     * Kleinste glaubhafte Einheiten-Kraft. Autor 25.10.: auf Thronsaal 5 haben die Einheiten 53 059 bis 108 971 -- die
     * alte Grenze 100 000 warf sie weg. Unter Thronsaal 11 darum ab 1 000 (dort ganze Dreiergruppen wie "53 059").
     */
    private static long kraftMin() {
        return de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.SETS) ? 1_000 : 100_000;
    }

    private void kraftPruefen(BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int x = (int) Math.round(KRAFT_STELLE[0] * bild.getWidth()), y = (int) Math.round(KRAFT_STELLE[1] * bild.getHeight());
        int w = (int) Math.round(KRAFT_STELLE[2] * bild.getWidth()), h = (int) Math.round(KRAFT_STELLE[3] * bild.getHeight());
        List<Long> zahlen = ArenaLeser.zahlenIn(bild, x, y, w, h);
        long kraft = zahlen.size() == 1 ? zahlen.get(0) : -1;
        // 11.10. (Test 18:18): 8 von 29 Lesungen kamen zerrissen ("4589, 3, 7" fuer "4 589 397"). Gemessen an
        // 12 Fotos: normal 11 richtig, vergroessert las genau den einen Fehlfall richtig -- darum dann nachlesen,
        // und nur ein sauberes Zahlenbild ("4 589 397", "12 345 678") gilt.
        long min = kraftMin();
        if (kraft < min || kraft > 500_000_000L) kraft = kraftAusText(ArenaLeser.liesTextEnglisch(bild, x, y, w, h));
        if (kraft < 0) kraft = kraftAusText(ArenaLeser.liesTextGross(bild, x, y, w, h));
        if (kraft < min || kraft > 500_000_000L) {
            Log.debug("Arena", "Kraft von Einheit " + einheiten + " nicht sicher gelesen: " + zahlen);
            return;
        }
        letzteKraft = kraft;
        // Autor 22.10. (ersetzt 11.10.: "ueber 6 Mio +1, ueber 8 Mio +5"): "7 Millionen +2, 8 Millionen +3, 9 Millionen +4,
        // 10 Millionen +5 -- alles darunter keine extra Punkte". Darueber je weitere Million +1.
        int bonus = kraftBonus(kraft);
        if (bonus > 0) {
            starke++;
            kraftPunkte += bonus;
            setPunkte += bonus;
            Log.info("Arena", "Einheit " + einheiten + " hat " + kraft + " Kraft (+" + bonus + ").");
        } else {
            Log.debug("Arena", "Einheit " + einheiten + ": Kraft " + kraft + ".");
        }
    }

    /** Das Emblem im Einheiten-Fenster: unter dem Wort "Emblem", gemessen 11.10. an steine-181824 (Kreis um 125/285). */
    private static final double[] EMBLEM_STELLE = {70 / 1159.0, 230 / 770.0, 110 / 1159.0, 110 / 770.0};

    /**
     * Autor, 11.10.: "Bei jeder Einheit gibt es ein Emblem, darauf soll er bitte auch achten -- fuer ein gutes +3 Punkte."
     * Gute Embleme nimmt Autor mit capture.bat auf (Serie ar_emblem_gut_1, _2, ...). Ohne solche Bilder passiert nichts.
     * Autor, 11.10.: "Es gibt ein super gutes, das soll +5 geben" -- Serie ar_emblem_super_*, wird zuerst gesucht.
     */
    private void emblemPruefen(Mat screen, BotContext ctx) {
        if (screen == null) return;
        int x = (int) (EMBLEM_STELLE[0] * ctx.width()), y = (int) (EMBLEM_STELLE[1] * ctx.height());
        int w = (int) (EMBLEM_STELLE[2] * ctx.width()), h = (int) (EMBLEM_STELLE[3] * ctx.height());
        for (String art : new String[] {"ar_emblem_super_", "ar_emblem_gut_"}) {
            int punkte = art.equals("ar_emblem_super_") ? 5 : 3;
            for (String t : ctx.matcher().names()) {
                if (!t.startsWith(art)) continue;
                for (Matcher.Hit hit : ctx.matcher().findAll(screen, t, 0.85, 2)) {
                    if (hit.centerX() >= x && hit.centerX() <= x + w && hit.centerY() >= y && hit.centerY() <= y + h) {
                        guteEmbleme++;
                        emblemPunkte += punkte;
                        setPunkte += punkte;
                        Log.info("Arena", "Einheit " + einheiten + " hat ein " + (punkte == 5 ? "super gutes" : "gutes")
                                + " Emblem (" + t + ", +" + punkte + ").");
                        return;
                    }
                }
            }
        }
    }

    /** "4 589 397" -> 4589397; alles, was nicht genau so aussieht, -> -1. */
    static long kraftAusText(String text) {
        String t = text == null ? "" : text.strip().replaceAll("[.,]", " ").replaceAll("\\s+", " ");
        return t.matches("\\d{1,3}( \\d{3}){1,2}") ? Long.parseLong(t.replace(" ", "")) : -1;
    }

    /** Die vollen Leben der zuletzt gelesenen Einheit (-1 = nicht gelesen) -- fuers Aussehen (Autor 22.10.). */
    private long letzteLeben = -1;

    private void lebenPruefen(Mat screen, BotContext ctx) {
        letzteLeben = -1;
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        // Autor 22.10.: "Die Leben jeder Einheit sind immer an der gleichen Stelle -- der Bot muss nicht nach dem Herz
        // suchen." Zuerst die feste Stelle (Zaehler-Test: 115 von 115 Bildern sauber), das Herz nur, wenn dort nichts steht.
        Optional<FesteStellen.Stelle> fest = FesteStellen.holen(S_LEBEN);
        if (fest.isPresent()) {
            Rectangle f = fest.get().in(ctx.width(), ctx.height());
            int fx = Math.max(0, f.x), fy = Math.max(0, f.y);
            int fw = Math.min(f.width, bild.getWidth() - fx), fh = Math.min(f.height, bild.getHeight() - fy);
            List<Long> z = fw > 5 && fh > 5 ? ArenaLeser.lebenZahlen(bild, fx, fy, fw, fh) : List.of();
            if (z.size() >= 2) {
                lebenAuswerten(z.get(0), z.get(1));
                return;
            }
        }
        Rectangle r = null;
        if (ctx.matcher().has(T_HERZ) && screen != null) {
            var h = ctx.matcher().findAll(screen, T_HERZ, 0.84, 1);
            if (!h.isEmpty()) {
                Matcher.Hit herz = h.get(0);
                int rand = herz.height() / 3;
                r = new Rectangle(herz.x() + herz.width(), Math.max(0, herz.y() - rand),
                        (int) (ctx.width() * 0.22), herz.height() + 2 * rand);
            }
        }
        if (r == null) return;
        // Nicht ueber den Bildrand hinaus lesen.
        int x = Math.max(0, r.x), y = Math.max(0, r.y);
        int w = Math.min(r.width, bild.getWidth() - x), hoehe = Math.min(r.height, bild.getHeight() - y);
        if (w < 5 || hoehe < 5) return;
        List<Long> zahlen = ArenaLeser.lebenZahlen(bild, x, y, w, hoehe);
        // Nur zaehlen, wenn BEIDE Zahlen ("jetzt/voll") gelesen wurden und
        // passen. Eine einzelne Zahl kann ein Streuzeichen oder ein halb
        // gelesenes "2 777" aus "2 777 300" sein -- dann lieber keinen
        // Bauarbeiter als einen falschen (+5 wuerde den Gegner meiden).
        if (zahlen.size() < 2) {
            // 22.10.: auch "gar nichts gelesen" ins Protokoll -- sonst blieb ein Lesefehler unsichtbar.
            Log.debug("Arena", "Leben von Einheit " + einheiten + " nicht sicher gelesen: " + zahlen);
            return;
        }
        lebenAuswerten(zahlen.get(0), zahlen.get(1));
    }

    /** Leben "jetzt/voll" gelesen: merken, und unter der Grenze ist es ein Bauarbeiter. */
    private void lebenAuswerten(long leben, long voll) {
        Log.debug("Arena", "Leben von Einheit " + einheiten + ": " + leben + "/" + voll + ".");
        if (leben > 0 && leben <= voll) letzteLeben = voll;
        // Autor 25.10.: "In den niedrigeren Thronsaelen haben die Einheiten meistens nicht so viele Leben -- meine haben
        // 30 000, sind aber keine Bauarbeiter." Unter Thronsaal 11 gibt es darum keine Bauarbeiter-Regel.
        if (de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.SETS)) return;
        if (leben >= LEBEN_MIN && leben <= voll && voll <= BAUARBEITER_BIS) {
            bauarbeiter++;
            Log.info("Arena", "Einheit " + einheiten + " hat nur " + leben + "/" + voll
                    + " Leben -- Bauarbeiter (+5).");
        }
    }

    /** Autor 01.11.: das Item unter "Basic item" im offenen Set-Gegenstand: gut +2, mittel +1, schlecht -2 (ArenaItems). */
    private void basisItemPruefen(BotContext ctx, String platz) {
        if (!ArenaItems.aktiv() || eigenesTeam) return;   // wie itemsWerten: dein eigenes Team bekommt keine Item-Punkte
        BufferedImage bild = ctx.frame();
        // Pruefung 3.138 (165938-891): zeigt das Feld nur den gelben Platzhalter (Bild noch nicht geladen), 150 ms warten, neu aufnehmen, noch einmal vergleichen
        ArenaItems.Wert w = ArenaItems.basisItemWert(bild, platz, ArenaItems.bewertungen(), () -> {
            ctx.sleep(150);
            return ctx.refresh() == null ? null : ctx.frame();
        });
        if (w == null) {
            // Autor 03.11.: "Wieso hat der Bot 89 nicht zuordnen koennen, und wieso wird das nicht protokolliert oder als Bild gespeichert?"
            // -- bisher brach die Stelle hier stumm ab. Probe an 550 Beweisfotos: 162 standen nicht in seiner Liste, 21 waren zu aehnlich,
            // 16 noch nicht geladen. "Alle Items, die der Bot nicht zuordnen kann, soll er aufnehmen -- nur bei meinem Bot" (aktiv() oben).
            BufferedImage jetzt = ctx.frame() != null ? ctx.frame() : bild;
            String grund = ArenaItems.basisGrund(jetzt, platz, ArenaItems.bewertungen());
            boolean neu = ArenaItems.basisMerken(jetzt, platz);
            Log.info("Arena", "Einheit " + einheiten + ": Basic item (" + platz + ") nicht erkannt -- " + grund
                    + (neu ? ", Bild fuer die Bewertung gemerkt (arena-items\\neu)." : "."));
            return;
        }
        int punkte = ArenaItems.basisPunkte(w);
        itemPunkte += punkte;
        setPunkte += punkte;
        Log.info("Arena", "Einheit " + einheiten + ": Basic item (" + platz + ") bewertet " + w + " -> " + (punkte >= 0 ? "+" : "") + punkte + " Punkte.");
    }

    private void setLesen(BotContext ctx) {
        Optional<FesteStellen.Stelle> s = FesteStellen.holen(S_SETNAME);
        BufferedImage bild = ctx.frame();
        beweisFoto(ctx, "set");   // das Popup ist nur kurz offen (unter 0,5 s): das Bild im Moment des Lesens
        // Autor, 11.10.: "Bei der Divine Armor hat er ziemlich lange ueberlegt" -- der Name stand in keiner Liste, darum
        // las er ihn bis zu sechsmal neu (3,7 s). Zuerst das Banner: Divine/Mythical/Legendary = kein Set, fertig.
        if (bild != null && ArenaSets.banner(bannerLesen(bild)) == ArenaSets.Banner.KEIN_SET) {
            Log.info("Arena", "Einheit " + einheiten + ": Banner Divine/Mythical/Legendary/Rare/Epic/Common -> kein Set (" + ArenaSets.KEIN_SET + " Punkt).");
            verbuchen(new ArenaSets.Treffer("kein Set", '-', ArenaSets.KEIN_SET), "", bild);
            return;
        }
        String titel = "";
        if (s.isPresent() && bild != null) {
            Rectangle r = s.get().in(ctx.width(), ctx.height());
            // Etwas breiter als die gespeicherte Stelle: lange Namen stehen mittig und ragen
            // darueber hinaus -- der erste Buchstabe fehlte (01.10.: ":orest Guardian", ";nake").
            int mehr = r.width / 6;
            titel = ArenaLeser.liesText(bild, Math.max(0, r.x - mehr), r.y, r.width + 2 * mehr, r.height);
            // Leer gelesen? Das Fenster baut sich evtl. noch auf (02.10., Lauf 11: mit schnelleren
            // Fotos 3 von 23 Set-Namen leer). Bis zu dreimal kurz warten und neu lesen.
            for (int versuch = 0; titel.isBlank() && versuch < 2; versuch++) {
                ctx.sleep(ms(120));   // 08.10.: 2 x 120 ms statt 3 x 200 ms (Autor: "das Doppelchecken soll schneller gehen"); 24.10. mit Arena-Tempo
                if (ctx.refresh() == null) break;
                bild = ctx.frame();
                if (bild == null) break;
                titel = ArenaLeser.liesText(bild, Math.max(0, r.x - mehr), r.y, r.width + 2 * mehr, r.height);
            }
        }
        ArenaSets.Treffer t = ArenaSets.erkennen(titel);
        // Autor 11.10.: "kannst du diese starke Verzerrung der Texterkennung verbessern?" -- Nicht sauber gelesen
        // (fremde Zeichen, kleiner Anfangsbuchstabe) oder nicht erkannt: noch einmal ENGLISCH und doppelt so gross.
        if (s.isPresent() && bild != null && (t.stufe() == '?' || !sauberGelesen(titel))) {
            Rectangle r = s.get().in(ctx.width(), ctx.height());
            int mehr = r.width / 6;
            String en = ArenaLeser.liesTextEnglisch(bild, Math.max(0, r.x - mehr), r.y, r.width + 2 * mehr, r.height);
            ArenaSets.Treffer te = en.isBlank() ? null : ArenaSets.erkennen(en);
            if (te != null && te.stufe() != '?' && (t.stufe() == '?' || sauberGelesen(en))) {
                Log.debug("Arena", "Set-Name englisch neu gelesen: \"" + titel.trim() + "\" -> \"" + en + "\"");
                titel = en;
                t = te;
            }
            setFoto(bild, Math.max(0, r.x - mehr), r.y, r.width + 2 * mehr, r.height, titel);
        }
        // Nicht erkannt? Lange Namen stehen auf ZWEI Zeilen ("Herald of Cold" / "Plate Mail"), oder die
        // Stelle sass eine Zeile zu tief ("Equipped by character:"). Autor, 08.10.: "Vielleicht musst
        // du die Stelle ein bisschen vergroessern." Dann noch einmal mit doppelter Hoehe nach oben.
        if (t.stufe() == '?' && s.isPresent() && bild != null) {
            Rectangle r = s.get().in(ctx.width(), ctx.height());
            int mehr = r.width / 6;
            // Zwei Fenster: (a) eine Zeile hoeher -- lange Namen auf zwei Zeilen; (b) Autor, 08.10.: bei NICHT
            // verzauberter, nicht goettlicher Ausruestung steht der Set-Name "ein bisschen weiter unten"
            // (Yilmaz, Krieger mit Defender of Nature: an der gewohnten Stelle stand gar nichts).
            int[][] fenster = {
                {Math.max(0, r.y - r.height), r.y + r.height},
                // Gemessen 08.10. (Foto logs/auge/yilmaz-jetzt.png): gewohnte Stelle y 75-116, bei nicht
                // verzauberter Ausruestung steht dort "Equipped by character:" (108-125), der Name darunter (128-170).
                {r.y + (int) Math.round(r.height * 0.8), Math.min(bild.getHeight(), r.y + (int) Math.round(r.height * 2.4))},
                // Pruefer 29.10. (29 von 254 Teilen unlesbar, gehaeuft je Einheit): die Fotos (logs/sets/223941-316-.png,
                // 225635-031-.png) zeigen an der gewohnten Stelle nur die Oberkante des Fensters, etwa 0,66 Hoehen tiefer als
                // sonst. Gemessen an logs/auge/set-tiefer-223702.png steht der Name 0,8-1,35 Hoehen unter der Oberkante,
                // zweizeilig bis ~2 -- darum ein drittes Fenster bis 3,5 Hoehen.
                {r.y + (int) Math.round(r.height * 1.2), Math.min(bild.getHeight(), r.y + (int) Math.round(r.height * 3.5))},
            };
            for (int[] f : fenster) {
                if (f[1] - f[0] < 8) continue;
                String gross = ArenaLeser.liesText(bild, Math.max(0, r.x - mehr), f[0], r.width + 2 * mehr, f[1] - f[0]);
                gross = gross.replaceAll("(?i)equipped by character:?", " ").replaceAll("\s+", " ").strip();
                ArenaSets.Treffer t2 = ArenaSets.erkennen(gross);
                if (t2.stufe() != '?') {
                    Log.debug("Arena", "Set-Name an anderer Stelle gelesen (" + (f == fenster[0] ? "zweizeilig" : f == fenster[1] ? "tiefer" : "noch tiefer")
                            + "): \"" + titel.trim() + "\" -> \"" + gross + "\"");
                    boolean dritte = f == fenster[2];
                    if (f != fenster[0] && !(dritte ? nochTieferFotografiert : tieferFotografiert)) {   // einmal je Programmlauf: Lage nachmessen
                        if (dritte) nochTieferFotografiert = true;
                        else tieferFotografiert = true;
                        try {
                            de.hustlebot.core.Beleg.schreiben(bild, "png", java.nio.file.Path.of("logs", "auge", (dritte ? "set-noch-tiefer-" : "set-tiefer-")
                                    + de.hustlebot.core.Zeitzone.uhrzeit().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png").toFile());
                        } catch (Exception e) {
                            Log.debug("Arena", "Foto set-tiefer nicht gespeichert: " + e.getMessage());
                        }
                    }
                    titel = gross;
                    t = t2;
                    break;
                }
            }
        }
        // Autor, 11.10.: das farbige Banner unter dem Namen entscheidet, ob es ein Set ist (Immortal/Supreme = Set,
        // Divine/Mythical/Legendary = kein Set). Es steht je nach Namenslaenge auf Hoehe 125-214 (gemessen an 5 Bildern).
        if (bild != null) {
            String bannerText = bannerLesen(bild);
            ArenaSets.Banner b = ArenaSets.banner(bannerText);
            if (b == ArenaSets.Banner.KEIN_SET && t.stufe() != '-') {
                Log.debug("Arena", "Banner sagt kein Set (" + bannerText.replace('\n', ' ').strip() + ") -- statt " + t.set());
                t = new ArenaSets.Treffer("kein Set", '-', ArenaSets.KEIN_SET);
            } else if (b == ArenaSets.Banner.SET && t.stufe() == '-') {
                Log.debug("Arena", "Banner sagt Set (Immortal/Supreme), der Name steht aber als kein Set -- zaehlt als unbekanntes Set.");
                t = new ArenaSets.Treffer("Set (unbekannt)", 'C', ArenaSets.UNBEKANNT);
            }
        }
        verbuchen(t, titel, bild);
    }

    /**
     * Ist das ein Set, das nur in der Liste fehlt? Pruefung 3.138 (01.11., Kira Einheit 6): "Thicket Lord's Gear" galt als unbekanntes Set,
     * "Thicket Lord's Weapon" und "Thicket Lord's Ring" (Banner Immortal, "Collected: 2/6") als "nicht lesbar" -- das Ruestungswort fehlt, der Name war
     * also nicht nurName. Jetzt zaehlt auch ein nicht erkannter Name, wenn der Balken 1-6 Teile zeigt UND das Banner Ancient/Immortal/Supreme sagt
     * (nur diese tragen Sets; das Banner wird nur in diesem seltenen Fall gelesen).
     */
    static boolean unbekanntesSet(ArenaSets.Treffer t, int teile, BufferedImage bild) {
        if (teile < 1 || teile > 6) return false;
        if (t.nurName()) return true;
        return t.stufe() == '?' && bild != null && ArenaSets.setStufe(bannerLesen(bild));
    }

    /**
     * Ein gelesenes Set verbuchen. Autor 15.10.: Steht im Set-Fenster "Collected: 1-5/6", ist es ein Misch-Set oder noch
     * nicht fertig -- dann wird auch die WAFFE angesehen (Phase WAFFE) und beide Sets zaehlen anteilig nach Teilen
     * (z. B. 4/6 Herald + 2/6 Necromancer). Bei 6/6 bleibt es wie bisher (keine Waffe, Zeit sparen).
     */
    private void verbuchen(ArenaSets.Treffer t, String titel, BufferedImage bild) {
        int teile = teileLesen(bild);
        // Pruefung 29.10.: Ein sauber gelesener Name, der in keiner Liste steht, galt als "kein Set" (-2/-4) -- auch bei
        // Sets, die nur in der Liste fehlen (Thicket Lord, Terror of the Seas, Frost Monk, Field Druid). Zeigt der
        // Set-Balken Teile (1-6/6), ist es ein Set: neutral als unbekanntes Set zaehlen und fuer Autor sammeln.
        if (unbekanntesSet(t, teile, bild)) {
            Log.debug("Arena", "\"" + titel.trim() + "\" steht in keiner Liste, der Set-Balken zeigt aber " + teile
                    + "/6 -- zaehlt als unbekanntes Set statt \"kein Set\"/\"nicht lesbar\".");
            ArenaSets.unbekanntMerken(titel);
            t = new ArenaSets.Treffer("Set (unbekannt)", 'C', ArenaSets.UNBEKANNT);
        }
        boolean echtesSet = t.stufe() != '?' && t.stufe() != '-';
        if (!waffeModus) {
            // Neue Einheit: die Brust.
            stueck = 0;
            setTeile.clear();
            setTreffer.clear();
            brustSet = t;
            brustTeile = echtesSet ? teile : -1;
            // Pruefer 29.10.: das Foto liegt in logs/sets (setFoto), nicht in logs/ereignisse -- und nur, wenn es eins gab.
            if (t.stufe() == '?') Log.warn("Arena", "Set-Name bei Einheit " + einheiten + " von " + name + " nicht lesbar"
                    + (setFotoGespeichert ? " (Foto in logs/sets)." : " (ohne Foto)."));
        }
        setFotoGespeichert = false;   // gilt nur fuer dieses eine Teil
        // Pruefer 29.10.: erst NACH dem Zuruecksetzen oben -- vorher stand bei einer neuen Brustplatte "Ring" im Protokoll.
        String was = stueck == 0 ? "Ruestung" : stueck == 1 ? "Waffe" : "Ring";
        waffeModus = false;
        if (echtesSet) {
            setTeile.merge(t.set(), teile >= 1 && teile <= 6 ? teile : -1, Math::max);
            setTreffer.putIfAbsent(t.set(), t);
        }
        int summe = 0;
        for (int n : setTeile.values()) summe += Math.max(0, n);
        boolean fertig = (stueck == 0 && echtesSet && teile == 6 && !waffeImmerAnsehen)   // ganzes Set: keine Waffe, kein Ring
                || summe >= 6 || stueck >= 2;
        Log.info("Arena", "Einheit " + einheiten + ": " + was + " \"" + titel.trim() + "\" -> "
                + (echtesSet ? t.set() + " " + (teile > 0 ? teile + "/6" : "?/6") : t.stufe() == '-' ? "kein Set" : "nicht lesbar")
                + (fertig ? "" : " -- noch " + (6 - summe) + " Teile offen, " + (stueck == 0 ? "die Waffe" : "der Ring") + " wird angesehen."));
        if (fertig) {
            setsBuchen("");
            return;
        }
        if (stueck == 0) waffeNoetig = true;
        else ringNoetig = true;
    }

    /**
     * Die gefundenen Sets der Einheit anteilig buchen (Autor 16.10.: jedes Set nach SEINEN Teilen, fehlende Teile zaehlen
     * nichts; Teilezahl nicht lesbar und nur ein Set -> wie 6/6). Ohne jedes Set zaehlt die Brust (kein Set -2 /
     * unlesbar neutral), anteilig fuer die Teile, die kein Set abdeckt.
     */
    /**
     * Autor 31.10.: "Das soll nicht doppelt zaehlen." Schlechte Gegenstaende sitzen fast immer auf Einheiten ohne Set (Rueckrechnung: 75 %), dort kam zu ihrem Abzug
     * noch der fuer "kein Set" dazu. Hat Autor Gegenstaende dieser Einheit bewertet, sagen die mehr: "kein Set" zaehlt dann 0.
     */
    static ArenaSets.Treffer ohneDoppelwertung(ArenaSets.Treffer b, boolean itemsGewertet) {
        return itemsGewertet && b.stufe() == '-' ? new ArenaSets.Treffer(b.set(), '-', 0) : b;
    }

    private void setsBuchen(String zusatz) {
        ArenaSets.Treffer b = brustSet;
        brustSet = null;
        waffeNoetig = false;
        ringNoetig = false;
        waffeModus = false;
        if (setTeile.isEmpty()) {
            if (b != null) einzelVerbuchen(ohneDoppelwertung(b, einheitItemsGewertet), zusatz);
            return;
        }
        double p = 0;
        int summe = 0;
        StringBuilder text = new StringBuilder();
        for (var e : setTeile.entrySet()) {
            int n = e.getValue();
            if (n < 1) n = setTeile.size() == 1 ? 6 : 1;
            n = Math.min(n, 6 - Math.min(5, summe));
            ArenaSets.Treffer tr = setTreffer.get(e.getKey());
            p += tr.punkte() * n / 6.0;
            summe += n;
            if (text.length() > 0) text.append(" + ");
            text.append(e.getKey()).append(' ').append(n).append("/6");
        }
        if (summe < 6 && b != null && (b.stufe() == '-' || b.stufe() == '?')) {
            int rest = 6 - summe;
            p += b.punkte() * rest / 6.0;
            text.append(" + ").append(b.stufe() == '-' ? "kein Set" : "?").append(' ').append(rest).append("/6");
        }
        sets.add(text.toString());
        setPunkte += p;
        Log.info("Arena", "Einheit " + einheiten + ": " + text + " -> " + zahl(p) + " Punkte (anteilig nach Teilen)"
                + (zusatz.isEmpty() ? "." : " " + zusatz));
        setTeile.clear();
        setTreffer.clear();
    }

    /** Ein einzelnes Ergebnis (nur die Ruestung) verbuchen -- wie vor der Waffe. */
    private void einzelVerbuchen(ArenaSets.Treffer t, String zusatz) {
        sets.add(t.stufe() == '?' ? "?" : t.stufe() == '-' ? "kein Set" : t.set() + " " + t.stufe());
        setPunkte += t.punkte();
        Log.info("Arena", "Einheit " + einheiten + ": "
                + (t.stufe() == '?' ? "nicht lesbar (" + ArenaSets.UNBEKANNT + " Punkte, neutral)"
                 : t.stufe() == '-' ? "kein Set, nur Ausruestung (" + ArenaSets.KEIN_SET + " Punkte)"
                                    : t.set() + " (" + t.stufe() + ", " + t.punkte() + " Punkte)") + " " + zusatz);
    }

    /** "Collected: 4/6" -> 4; nicht lesbar -> -1. */
    static int teileLesen(BufferedImage bild) {
        if (bild == null) return -1;
        int bw = bild.getWidth(), bh = bild.getHeight();
        // Test 15.10.: in Originalgroesse war die kleine Zeile nicht lesbar -- doppelt gross wie das Bedienfeld.
        String z = TeamScan.liesDoppelt(bild, (int) (SAMMEL[0] * bw), (int) (SAMMEL[1] * bh), (int) (SAMMEL[2] * bw), (int) (SAMMEL[3] * bh));
        java.util.regex.Matcher m = TEILE.matcher(z == null ? "" : z.replace('l', '1').replace('I', '1'));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Waffe oder Ring nicht anzusehen: was bis dahin gefunden ist, anteilig buchen. */
    private void waffeAufgeben(String grund) {
        if (brustSet == null && setTeile.isEmpty()) {
            waffeNoetig = false;
            ringNoetig = false;
            waffeModus = false;
            return;
        }
        setsBuchen("(" + grund + ")");
    }

    /** Punkte mit hoechstens zwei Kommastellen fuers Protokoll ("4,67", "8"). */
    private static String zahl(double p) {
        return Math.abs(p - Math.round(p)) < 0.005 ? String.valueOf(Math.round(p))
                : String.format(java.util.Locale.GERMANY, "%.2f", p);
    }

    /** Das farbige Banner unter dem Ruestungsnamen (Hoehe 115-225, gemessen an 5 Bildern vom 11.10.). */
    static String bannerLesen(BufferedImage bild) {
        String t = ArenaLeser.liesTextEnglisch(bild, (int) (480 / 1159.0 * bild.getWidth()), (int) (115 / 770.0 * bild.getHeight()),
                (int) (330 / 1159.0 * bild.getWidth()), (int) (110 / 770.0 * bild.getHeight()));
        if (ArenaSets.bannerGelesen(t)) return t;
        // Pruefung 3.138 (01.11.): Das Fenster eines Arbeiters ("Watchmaker suit", "Rare outfit") ist kurz und sitzt in der Mitte -- der Name steht bei
        // Hoehe 271, das Banner bei 318 statt 130. Nicht gefunden: einmal weiter unten suchen (Hoehe 225-445), nur dann.
        String tief = ArenaLeser.liesTextEnglisch(bild, (int) (480 / 1159.0 * bild.getWidth()), (int) (225 / 770.0 * bild.getHeight()),
                (int) (330 / 1159.0 * bild.getWidth()), (int) (220 / 770.0 * bild.getHeight()));
        return t + " " + tief;
    }

    /** Sauber gelesener Set-Name: Grossbuchstabe vorn, nur Buchstaben, Apostroph, Bindestrich, Leerzeichen. */
    static boolean sauberGelesen(String titel) {
        return titel != null && titel.strip().matches("[A-Z][A-Za-z' -]+");
    }

    private static int setFotos;
    /** Pruefer 29.10.: die Grenze gilt je Tag (der Bot laeuft tagelang, nach 40 Fotos kam keins mehr). */
    private static java.time.LocalDate setFotoTag;
    /** Hat der letzte setFoto()-Aufruf wirklich ein Foto gespeichert? Fuer die Warnung in verbuchen(). */
    private static boolean setFotoGespeichert;

    /** Verzerrt gelesene Set-Namen als kleines Bild in logs/sets (hoechstens 40 je Tag) -- zum Nachmessen. */
    private static void setFoto(BufferedImage bild, int x, int y, int w, int h, String gelesen) {
        setFotoGespeichert = false;
        java.time.LocalDate heute = java.time.LocalDate.now();
        if (!heute.equals(setFotoTag)) {
            setFotoTag = heute;
            setFotos = 0;
        }
        if (setFotos >= 40) return;
        try {
            int x2 = Math.min(bild.getWidth(), x + w), y2 = Math.min(bild.getHeight(), y + h);
            if (x2 - x <= 4 || y2 - y <= 4) return;
            setFotos++;
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "sets");
            String name = de.hustlebot.core.Zeitzone.uhrzeit().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS"))
                    + "-" + gelesen.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".png";
            setFotoGespeichert = de.hustlebot.core.Beleg.schreiben(bild.getSubimage(x, y, x2 - x, y2 - y), "png", ordner.resolve(name).toFile());
        } catch (Exception e) {
            Log.debug("Arena", "Set-Foto nicht gespeichert: " + e.getMessage());
        }
    }

    /** Schliesst das Teile-Fenster -- nur ueber ar_item_zu (ohne die gibt es keine Sets). */
    private void itemSchliessen(Mat screen, BotContext ctx) {
        // 16.10. (Freunde-Probelauf, "Servant of the Night", 5 s Haenger): setLesen() holt bei schwer lesbarem Namen
        // neue Bilder -- das mitgegebene Bild ist danach freigegeben (0x0). Das X wurde im leeren Bild gesucht, der Bot
        // tippte noch einmal auf die Brustplatte und wartete vergebens. Dann das aktuelle Bild nehmen.
        if (screen == null || screen.empty()) screen = ctx.aktuellesBild();
        if (screen == null || screen.empty()) return;
        if (ctx.clickIfVisibleIfKnown(screen, T_ITEM_ZU, "Gegenstand schliessen")) {
            ctx.sleepUntilChanged(20, 500);
            return;
        }
        // 11.10. (Autor: "bei Loki schaut er sehr lange die Ruestung an, nur von einer Einheit"): das X des
        // Gegenstand-Fensters wurde nicht erkannt, das Fenster blieb offen, die Einheit war verdeckt -- nach 2,5 s
        // brach die Pruefung bei 2 von 6 Einheiten ab, dazu 10 s zum Wiederfinden. Jetzt: ein Foto fuer Claude,
        // dann das allgemeine rote X, sonst noch einmal auf die Brustplatte (das Fenster klappt dann zu).
        if (itemFotos < 3 && ctx.frame() != null) {
            itemFotos++;
            try {
                de.hustlebot.core.Beleg.schreiben(ctx.frame(), "png", java.nio.file.Path.of("logs", "auge", "item-offen-"
                        + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png").toFile());
            } catch (Exception e) {
                Log.debug("Arena", "Foto item-offen nicht gespeichert: " + e.getMessage());
            }
        }
        if (ctx.clickIfVisibleIfKnown(screen, "ar_close_x", "Gegenstand schliessen (rotes X)")) {
            ctx.sleepUntilChanged(20, 500);
            return;
        }
        // Autor, 11.10. (eigene Kaserne, Plattenruestung blieb offen): "Da ist kein X -- er muss einfach neben die
        // Ruestung klicken." Abwechselnd: links neben das Gegenstand-Fenster auf die Kraftzahl des Einheiten-Fensters
        // (dort passiert nichts, selbst wenn der Tipp durchgeht), beim naechsten Mal noch einmal auf die Brustplatte.
        nebenGetippt = !nebenGetippt;
        if (nebenGetippt) {
            Log.action("Arena", "Gegenstand schliessen: neben die Ruestung tippen");
            ctx.input().click((int) (ctx.width() * NEBEN_RUESTUNG[0]), (int) (ctx.height() * NEBEN_RUESTUNG[1]), 3);
            ctx.sleepUntilChanged(20, 500);
            return;
        }
        Optional<FesteStellen.Stelle> brust = FesteStellen.holen(S_BRUST);
        if (brust.isPresent()) {
            Rectangle r = brust.get().in(ctx.width(), ctx.height());
            Log.action("Arena", "Gegenstand schliessen: X nicht gefunden -- noch einmal auf die Brustplatte");
            ctx.input().click((int) r.getCenterX(), (int) r.getCenterY(), 3);
            ctx.sleepUntilChanged(20, 500);
        }
    }

    private static int itemFotos;
    private boolean nebenGetippt;
    /** Links neben dem Gegenstand-Fenster: die Kraftzahl oben links im Einheiten-Fenster (Bild 11.10.: 100/175). */
    private static final double[] NEBEN_RUESTUNG = {100 / 1159.0, 175 / 770.0};

    // ------------------------------------------------------------------
    //  Wartezeit nach "Naechste gegnerische Einheit" (Autor 24.10.)
    // ------------------------------------------------------------------
    //
    // GEMESSEN (Protokolle 22.10. abends und 24.10. nachts, ~1400 Klicks auf "Naechste Einheit"): von dem Klick bis
    // zum ersten Auslesen der neuen Einheit vergingen entweder 0,35-0,5 s (die grobe Bildabweichung schlug nach
    // ~105 ms an) oder 0,9-1,2 s (sie schlug in 700 ms NIE an -- die neue Einheit sieht dem ganzen Bild nach der
    // alten zu aehnlich, Schwelle 8 auf einem 64x48-Graubild). Der zweite Fall war 40-50 % aller Wechsel; im
    // Schnell-Blick (6 Einheiten) waren das ~2 von 4,7 s. In Wahrheit war die neue Einheit schon nach ~100 ms da.
    //
    // Messung an 83 echten Wechseln (logs\auge\steine-*.png, je zwei Einheiten nacheinander): die Mitte (wo die Figur
    // steht, wie in gesehenAn) stimmt bei zwei verschiedenen Einheiten nur zu 0,24-0,76 ueberein; dieselbe Einheit
    // (Autors Kasernen-Bilder) zu 0,99. Das grobe Gesamtbild lag bei 21 von 83 Wechseln unter der Schwelle 8.
    private static final long WECHSEL_GROB_MS = 100, WECHSEL_HOECHSTENS_MS = 700, WECHSEL_RUHE_MS = 300;
    /** Unter dieser Uebereinstimmung der Bildmitte steht eine andere Figur da (dieselbe: 0,99; andere: hoechstens 0,76). */
    static final double FIGUR_ANDERS_UNTER = 0.80;
    /** Fuer das Protokoll: Wechsel gesamt grob erkannt / an der Figur erkannt / nach 700 ms ohne Treffer, und die Wartezeit. */
    private int wechselGrob, wechselFigur, wechselGrenze;
    private long wechselMs;

    /**
     * Wartet nach dem Klick auf "Naechste Einheit", bis die neue Einheit da ist und zur Ruhe kommt -- danach darf
     * gelesen werden. Frueher: {@code sleepUntilChanged(20, 700)} + 200 ms.
     *
     * Jetzt: (1) wie bisher ein Blick auf die grobe Bildabweichung, aber nur ~100 ms lang (schlaegt sie an, geht es
     * genau wie vorher weiter: +200 ms). (2) Sonst alle ~60 ms ein frisches Bild und die Bildmitte mit der letzten
     * gesehenen Einheit vergleichen; sobald dort eine andere Figur steht, ist der Wechsel da. Gelesen wird dann
     * fruehestens 300 ms nach dem Klick (so frueh wie im bisherigen schnellen Fall) und mindestens 150 ms nach dem
     * Erkennen. (3) Sieht die Mitte auch nach 700 ms gleich aus (gleich aussehende Einheit, oder der Klick kam nicht
     * an), bleibt alles wie bisher: 700 ms + 200 ms, danach prueft WEITER wie vorher "dieselbe Einheit?".
     * Kasernen-Rundgang (TeamScan) und ein leeres Gedaechtnis: unveraendert.
     */
    private void naechsteEinheitAbwarten(BotContext ctx) {
        long t0 = System.currentTimeMillis();
        if (kaserne || gesehen.isEmpty()) {
            ctx.sleepUntilChanged(20, WECHSEL_HOECHSTENS_MS);
            ctx.sleep(200);
            return;
        }
        if (ctx.bildGeaendert(20, WECHSEL_GROB_MS)) {
            ctx.sleep(200);
            wechselGrob++;
            wechselMs += System.currentTimeMillis() - t0;
            return;
        }
        Mat vorige = gesehen.get(gesehen.size() - 1);
        boolean anders = false;
        while (System.currentTimeMillis() - t0 < WECHSEL_HOECHSTENS_MS) {
            Mat bild = ctx.refresh();
            if (bild == null) break;
            if (figurAnders(bild, vorige)) {
                anders = true;
                break;
            }
            ctx.sleep(30);
        }
        if (anders) {
            ctx.sleep(Math.max(150, WECHSEL_RUHE_MS - (System.currentTimeMillis() - t0)));
            wechselFigur++;
        } else {
            ctx.sleep(200);
            wechselGrenze++;
        }
        wechselMs += System.currentTimeMillis() - t0;
    }

    /**
     * Steht in der Bildmitte eine andere Figur als in {@code vorigeMitte} (dem {@link #mitte} der letzten Einheit)?
     * Bei jedem Zweifel (leeres Bild, andere Groesse, Rechenfehler) false -- dann wird weitergewartet wie bisher.
     */
    static boolean figurAnders(Mat bild, Mat vorigeMitte) {
        if (bild == null || bild.empty() || vorigeMitte == null || vorigeMitte.empty()) return false;
        Mat jetzt = null, ergebnis = null;
        try {
            jetzt = mitte(bild);
            if (jetzt.cols() != vorigeMitte.cols() || jetzt.rows() != vorigeMitte.rows()) return false;
            ergebnis = new Mat();
            Imgproc.matchTemplate(jetzt, vorigeMitte, ergebnis, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(ergebnis).maxVal < FIGUR_ANDERS_UNTER;
        } catch (Exception e) {
            return false;
        } finally {
            if (jetzt != null) jetzt.release();
            if (ergebnis != null) ergebnis.release();
        }
    }

    /** Eine Zeile je Pruefung: wie die Wechsel zur naechsten Einheit erkannt wurden (zum Nachpruefen im naechsten Lauf). */
    private void wechselBericht() {
        int alle = wechselGrob + wechselFigur + wechselGrenze;
        if (alle > 0) {
            Log.debug("Arena", name + ": " + alle + " Wechsel zur naechsten Einheit -- grob erkannt " + wechselGrob
                    + ", an der Figur erkannt " + wechselFigur + ", ohne Treffer nach " + WECHSEL_HOECHSTENS_MS + " ms "
                    + wechselGrenze + ", Wartezeit zusammen " + wechselMs + " ms.");
        }
        wechselGrob = 0;
        wechselFigur = 0;
        wechselGrenze = 0;
        wechselMs = 0;
    }

    /** Ein Ausschnitt aus der Bildmitte -- dort steht die Einheit selbst. */
    static Mat mitte(Mat screen) {
        int w = screen.cols() / 6, h = screen.rows() / 5;
        Rect r = new Rect(screen.cols() / 2 - w / 2, screen.rows() / 2 - h / 2, w, h);
        Mat ausschnitt = new Mat(screen, r);   // Review 15.10.: den Ausschnitt-Kopf freigeben, sonst haelt er das ganze Bild
        try {
            return ausschnitt.clone();
        } finally {
            ausschnitt.release();
        }
    }

    /**
     * Steht eine Einheit da, die schon angesehen wurde? Verglichen wird mit
     * ALLEN bisherigen, nicht nur mit der ersten -- wird die erste einmal nicht
     * wiedererkannt, zaehlten sonst Sets doppelt (bis zu acht Einheiten).
     */
    private boolean schonGesehen(Mat screen) {
        return gesehenAn(screen) >= 0;
    }

    /** An welcher Stelle der schon gesehenen Einheiten steht diese, oder -1. */
    private int gesehenAn(Mat screen) {
        Treffer t = gesehenUnter(screen, gesehen, gesehenFelder, gesehenMerkmale);
        zwillingJetzt = t.zwilling();
        return t.stelle();
    }

    /** Ergebnis von {@link #gesehenUnter}: Stelle der schon gesehenen Einheit (-1 = neu), und ob ein Zwilling dabei war. */
    record Treffer(int stelle, boolean zwilling) {}

    /**
     * Die reine Regel hinter {@link #gesehenAn} (testbar): gleiche Figur und -- soweit gemerkt -- gleiche Ausruestung und
     * gleiche Merkmale = dieselbe Einheit. Gleiche Figur, aber andere Ausruestung oder Merkmale = Zwilling (eine andere).
     */
    static Treffer gesehenUnter(Mat screen, List<Mat> gesehen, List<List<Mat>> gesehenFelder, List<List<Mat>> gesehenMerkmale) {
        boolean zwilling = false;
        Mat jetzt = mitte(screen);
        List<Mat> felder = null, merkmale = null;
        try {
            for (int i = 0; i < gesehen.size(); i++) {
                Mat ergebnis = new Mat();
                try {
                    Imgproc.matchTemplate(jetzt, gesehen.get(i), ergebnis, Imgproc.TM_CCOEFF_NORMED);
                    if (Core.minMaxLoc(ergebnis).maxVal <= 0.90) continue;
                } finally {
                    ergebnis.release();
                }
                // Die Figur ist gleich. Dieselbe Einheit nur, wenn auch die Ausruestung gleich ist (B16, 22.10.).
                List<Mat> alt = i < gesehenFelder.size() ? gesehenFelder.get(i) : List.of();
                if (!alt.isEmpty()) {
                    if (felder == null) felder = ArenaAussehen.ausschneiden(screen);
                    if (!felder.isEmpty() && ArenaAussehen.aehnlichkeit(alt, felder) < DIESELBE_AUSRUESTUNG) {
                        zwilling = true;
                        continue;
                    }
                }
                // Autor 22.10.: auch bei gleicher Figur und gleicher (leerer) Ausruestung -- andere Kraft, andere Leben,
                // anderes Talent oder anderer Guardian = eine andere Einheit (seine zwei "ersatz").
                List<Mat> mAlt = i < gesehenMerkmale.size() ? gesehenMerkmale.get(i) : List.of();
                if (!mAlt.isEmpty()) {
                    if (merkmale == null) merkmale = ArenaAussehen.merkmale(screen);
                    if (!ArenaAussehen.gleicheMerkmale(mAlt, merkmale)) {
                        zwilling = true;
                        continue;
                    }
                }
                return new Treffer(i, zwilling);
            }
            return new Treffer(-1, zwilling);
        } catch (Exception e) {
            return new Treffer(-1, zwilling);
        } finally {
            jetzt.release();
            if (felder != null) ArenaAussehen.freigeben(felder);
            if (merkmale != null) ArenaAussehen.freigeben(merkmale);
        }
    }

    private void gesehenLeeren() {
        for (Mat m : gesehen) m.release();
        gesehen.clear();
        for (List<Mat> f : gesehenFelder) ArenaAussehen.freigeben(f);
        gesehenFelder.clear();
        for (List<Mat> m : gesehenMerkmale) ArenaAussehen.freigeben(m);
        gesehenMerkmale.clear();
    }
}
