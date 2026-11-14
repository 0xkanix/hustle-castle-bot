package de.hustlebot.bot;

import de.hustlebot.core.Energieplan;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Leuchtturm und Kolosseum: ein Besuch = bis zu fuenf Kaempfe, dann zurueck
 * in die Burg. Beide laufen gleich (Autor, 26.09.: das Kolosseum ist "genau
 * gleich wie der Leuchtturm, nur fuer Titanen"), nur Weg und Bilder
 * unterscheiden sich -- siehe Ort.
 *
 * Ablauf:
 *
 *   1. Burg -> "Map" -> Karte wischen, bis der Ort zu sehen ist -> antippen.
 *      Leuchtturm: erst nach links ziehen (er liegt rechts). Kolosseum: nach
 *      rechts ziehen (der "Tower" liegt ganz links), dann im Fenster "Select
 *      an activity" auf "Colosseum".
 *   2. Oben steht die Energie ("1/5" bis "5/5", abgelesen). So viele Kaempfe
 *      werden gemacht; keine Energie: gleich wieder raus, in 21 min nachsehen.
 *   3. Kaempfen, bis die Energie alle ist: "To battle!" -> "Quick Attack!"
 *      -> Ergebnis -> "Home". Immer der OBERSTE Gegner, der noch "To battle!"
 *      hat -- so zaehlen Niederlagen sauber gegen denselben Gegner.
 *   4. Zwei Niederlagen -> oben links "Refresh" (nur kostenlos, hoechstens alle 15 min).
 *      Sind alle fuenf besiegt, mischt das Spiel selbst neu.
 *   5. Hinaus: Leuchtturm per rotem X, Kolosseum per "Home" unten links (es
 *      hat kein X) -> Weltkarte -> "Home" -> Burg. Naechster Besuch in 101 min.
 *
 * NIE DIAMANTEN. Zwei Sicherungen:
 *   - "To battle!" wird nie oefter gedrueckt, als Energie da ist (5 bei Max).
 *     Ohne Energie koennte sonst ein Kauf-Fenster aufgehen.
 *   - Liegt ueber der Gegnerliste ploetzlich ein unbekanntes Fenster (das
 *     Bild wird dunkler), wird darin nichts angeklickt: Besuch beenden und
 *     hinaus. Ein X oder "Home" schliesst, es kauft nicht.
 *
 * Eingeschoben wird der Besuch zwischen zwei Runs von der Discord-Bruecke
 * (wie der Clan-Krieg), an/aus mit "!settings leuchtturm|kolosseum an|aus".
 */
public final class LighthouseMode implements GameMode {

    /** Wann zuletzt nach Werbe-/Belohnungsfenstern gesehen wurde (hoechstens jede Sekunde). */
    private long stoerGeprueftUm = 0;
    /** Wann zuletzt nach dem AFK-Update-Knopf gesehen wurde (hoechstens jede Sekunde). */
    private long afkGeprueftUm = 0;

    /**
     * Was einen Ort ausmacht.
     *
     * @param auswahl       Knopf im Zwischenfenster nach dem Kartenklick, oder null
     * @param ersteRichtungRechts true: die Karte zuerst nach rechts ziehen
     *                      (Inhalt wandert nach rechts, die linke Seite kommt ins Bild)
     * @param mitX          true: hinaus per rotem X; sonst per "Home" unten links
     */
    public record Ort(String id, String de, String en, Energieplan plan,
                      String node, String auswahl, String titel, String max, String energie,
                      String toBattle, String refresh, String quick,
                      boolean ersteRichtungRechts, boolean mitX) {}

    public static final Ort LEUCHTTURM = new Ort("lighthouse", "Leuchtturm", "Lighthouse",
            Energieplan.LEUCHTTURM, "lh_node", null, "lh_titel", "lh_max", "lh_energie_",
            "lh_to_battle", "lh_refresh", "lh_quick_attack", false, true);

    /** Autor, 26.09., mit Screenshots: Karte nach links wischen bis "Tower", dann "Colosseum". */
    public static final Ort KOLOSSEUM = new Ort("colosseum", "Kolosseum", "Colosseum",
            Energieplan.KOLOSSEUM, "ko_tower", "ko_auswahl", "ko_titel", "ko_max", "ko_energie_",
            "ko_to_battle", "ko_refresh", "ko_quick_attack", true, false);

    // --- Gemeinsame Vorlagen --------------------------------------------
    /** Gemessen an Autors Screenshots (26.09.): passt auch im Leuchtturm, 0,986. */
    static final String T_HOME = "home_button";
    /** Ebenso: das "Victory!"-Banner der Arena passt, 0,982. */
    static final String T_SIEG = "ar_victory";
    private static final String T_X = "close_x";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";
    /** Wettbewerb vorbei (Autors Bild vom 22.10.): gruener Knopf "Claim reward" und der orange "Close" rechts unten. */
    static final String T_CLAIM = "lh_claim_reward";
    static final String T_WETTBEWERB_ZU = "lh_contest_close";
    private long wettbewerbKlickUm;
    private int wettbewerbKlicks;

    private static final int MAX_KAEMPFE = 5;
    private static final int NIEDERLAGEN_BIS_REFRESH = 2;
    /** Hoechstens so oft Refresh (Autor, 26.09.: "maximal 1 mal alle 15 min"). */
    private static final long REFRESH_ABSTAND_MS = 15 * 60_000L;
    /** Letzter Refresh -- gilt ueber Besuche hinweg (nicht in reset()). */
    private long letzterRefreshUm = 0;
    private static final int MAX_WISCHER = 12;
    private static final int MAX_BLIND = 40;
    private static final int MAX_SCHRITTE = 600;
    /** So lange steht die Liste ruhig da, bevor die Energie abgelesen wird. */
    private static final long TITEL_RUHE_MS = 800;
    /**
     * Autor 24.10. (Ideenliste A18): Gemessen dauert es vom Klick auf den Ort bis "Energy 5/5" im Leuchtturm 1,3-2,4 s, im
     * Kolosseum 2,9-6 s. Davon sind rund 1 s eigenes Warten (TITEL_RUHE_MS plus Schrittlaenge ~0,35 s): der Rest ist die
     * Ladezeit des Spiels (die Kolosseum-Liste braucht rund 2,4 s laenger als die Leuchtturm-Liste). Darum gilt "5/5" schon
     * nach zwei guten Lesungen im Abstand von ENERGIE_FRUEH_PAUSE_MS, wenn das Bild dazwischen gleich hell blieb.
     */
    private static final long ENERGIE_FRUEH_PAUSE_MS = 120;
    /** So gut muss die Vorlage "5" passen, damit die Lesung vor TITEL_RUHE_MS gilt (die Vorlage selbst: 1,00). */
    private static final double ENERGIE_FRUEH_SCHWELLE = 0.95;
    /** So wenig (Anteil) darf sich die Helligkeit zwischen den zwei Lesungen aendern, sonst blendet die Liste noch ein. */
    private static final double RUHE_HELLIGKEIT = 0.03;
    /**
     * Autor 24.10. (Ideenliste A15/A16): So lange hoechstens wird per Kastenblick (alle paar ms, ohne Schlaf) auf das naechste
     * Bild gewartet; danach geht es wie bisher mit dem normalen Schritt weiter. Das Ergebnis-Home -> Liste dauert im Spiel
     * rund 0,15 s, "To battle!" -> Aufstellung ebenso.
     */
    private static final long LISTE_NACH_ERGEBNIS_MS = 900;
    private static final long QUICK_NACH_KLICK_MS = 1200;
    private static final long KAMPF_BLICK_MS = 400;
    /** Gegner ansehen (A15): so lange hoechstens auf das Fenster, den Reiterwechsel und das Schliessen warten. */
    private static final long SPAEH_FENSTER_MS = 1500;
    /** Wie tief die Ketten ineinander greifen duerfen (Ergebnis -> Liste -> Aufstellung bzw. Liste -> Gegner ansehen -> Liste ...). */
    private static final int MAX_KETTE = 10;
    private int kettenTiefe;
    /** Autor 24.10. (Ideenliste A17): wie viele Zuege auf der Weltkarte es beim letzten Besuch je Ort brauchte. */
    private static final java.util.Map<String, Integer> GEMERKTE_ZUEGE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Dunkler als so viel der ruhigen Liste: da liegt ein Fenster darueber. */
    private static final double DUNKEL_GRENZE = 0.8;

    private final Ort ort;

    // --- Kolosseum: Staerke ablesen (Autor, 27.09.) ---------------------
    //
    // "im coloseum hat er nur 1 mal gewonnen von 5 kaempfen" -- der Bot nahm
    // immer den obersten Gegner. Jetzt schaut er sich jeden noch offenen
    // Gegner einmal an ("To battle!" -> Fenster -> Reiter "Your squad" /
    // "Enemy squad" -> Power ablesen -> rotes X), merkt sich die Staerken
    // und kaempft zuerst gegen die schwaecheren. Ist jeder uebrige Gegner
    // mehr als die Grenze (Standard 500 000) staerker als Autor: kostenloser
    // Refresh, und geht der nicht, trotzdem der schwaechste (Autors Wahl).
    // NIE Diamanten -- der Refresh-Knopf mit Diamant wird nie gedrueckt.
    static final String T_POWER = "ko_power";            // gekreuzte Schwerter vor der Power
    static final String T_TAB_GEGNER = "ko_tab_gegner";  // Reiter "Enemy squad", NICHT gewaehlt
    static final String T_TAB_ICH = "ko_tab_ich";        // Reiter "Your squad", NICHT gewaehlt
    private static final java.nio.file.Path GRENZE_DATEI = java.nio.file.Path.of("kolosseum-grenze.txt");
    private static final long GRENZE_STANDARD = 500_000;
    /** Autors Power, einmal je Besuch abgelesen (-1 = noch nicht). */
    private long meinePower = -1;
    private int meineLeseVersuche;
    /** Gegner-Power je Zeile der Liste (y-Mitte des Knopfs), -1 = nicht lesbar. */
    private final java.util.Map<Integer, Long> gegnerPower = new java.util.HashMap<>();
    /** Die Zeile, deren Gegner gerade angesehen wird (null = es wird gekaempft). */
    private Integer spaehZeile;
    private int spaehSchritte;
    /** Kampfkraft 0 schon einmal gelesen (muss ein zweites Mal kommen). */
    private boolean nullSchonGesehen;
    /** Wie viele "To battle!" beim letzten Blick in der Liste standen -- mehr = neue Gegner. */
    private int letzteKnopfZahl;
    /** Zeile des laufenden Kampfes, und Zeilen, gegen die schon verloren wurde ("nicht 2 mal verlieren"). */
    private int kampfZeile = -1;
    private final java.util.Set<Integer> verlorenGegen = new java.util.HashSet<>();

    /** Ab so viel MEHR Power als Autor gilt ein Gegner als zu stark (!settings kolosseum grenze). */
    public static long kolosseumGrenze() {
        try {
            if (java.nio.file.Files.exists(GRENZE_DATEI)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(GRENZE_DATEI)) {
                    z = z.trim();
                    if (!z.isEmpty() && !z.startsWith("#")) return Math.max(0, Long.parseLong(z.replaceAll("[^0-9]", "")));
                }
            }
        } catch (Exception ignored) {
            // Standard
        }
        return GRENZE_STANDARD;
    }

    public static void setKolosseumGrenze(long wert) {
        try {
            java.nio.file.Files.writeString(GRENZE_DATEI, "# Kolosseum: ab so viel mehr Power als Autor ist ein "
                    + "Gegner zu stark (gesetzt mit !settings kolosseum grenze)\n" + Math.max(0, wert) + "\n");
        } catch (Exception e) {
            Log.warn("Kolosseum", Sprache.t("Limit could not be saved: " + e.getMessage(),
                    "Grenze liess sich nicht speichern: " + e.getMessage()));
        }
    }

    // --- Zustand eines Besuchs -----------------------------------------
    private boolean energieGeprueft, fertig, kampfLaeuft;
    /** So viele Kaempfe erlaubt die abgelesene Energie (0 = keine bestaetigt). */
    private int erlaubt;
    /** Wie oft die Energie schon abgelesen wurde (bei 0 einmal auf einem neuen Bild wiederholen). */
    private int energieLeseVersuche;
    private int kaempfe, siege, niederlagen, niederlagenSeitRefresh, refreshs;
    private int blind, schritte, wischer, ohneKnopf, dunkelFolge;
    private long titelSeit, karteGeklicktUm, quickGeklicktUm;
    /** Wann der Ort (Leuchtturm/Kolosseum) auf der Karte angeklickt wurde -- danach nie wischen. */
    private long ortGeklicktUm;
    /** Im Zwischenfenster "Colosseum" schon gewaehlt? */
    private boolean auswahlGeklickt;
    /** Energie im Auswahlfenster schon gelesen (einmal je Besuch). */
    private boolean auswahlEnergieGelesen;

    /** Die Energie unter "Colosseum" im Auswahlfenster ("0/5"), oder null. */
    private int[] auswahlEnergie(Mat screen, BotContext ctx) {
        var schrift = ctx.find(screen, ort.auswahl());
        java.awt.image.BufferedImage bild = ctx.frame();
        if (schrift.isEmpty() || bild == null) return null;
        double f = ctx.height() / 770.0;
        int sx = schrift.get().centerX(), sy = schrift.get().centerY();
        int x = sx - (int) (105 * f), y = sy + (int) (47 * f), w = (int) (68 * f), h = (int) (38 * f);
        int[] bruch = Nahrungskisten.bruchVerstaerkt(bild, x, y, w, h, true);
        if (bruch == null) {
            // Zum Nachsehen: welcher Ausschnitt wurde gelesen? (Test 28.09.: "0/5" nicht erkannt)
            try {
                int x1 = Math.max(0, x - 20), y1 = Math.max(0, y - 20);
                int x2 = Math.min(bild.getWidth(), x + w + 20), y2 = Math.min(bild.getHeight(), y + h + 20);
                de.hustlebot.core.Beleg.schreiben(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), "png",
                        java.nio.file.Path.of("logs", "ko-energie-ausschnitt.png").toFile());
            } catch (Exception e) {
                Log.debug(ort.de(), "Ausschnitt nicht gespeichert: " + e.getMessage());
            }
        }
        return bruch;
    }
    /** So lange hoechstens auf "Home" nach einem Kampf warten. */
    private static final long KAMPF_MAX_MS = 45_000;
    private double helligkeitRuhig = -1;

    public LighthouseMode() {
        this(LEUCHTTURM);
    }

    public LighthouseMode(Ort ort) {
        this.ort = ort;
        this.tempo = de.hustlebot.core.Tempolerner.fuer(
                ort == KOLOSSEUM ? "kolosseum-tempo.txt" : "leuchtturm-tempo.txt", ort.de());
    }

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Alle festen Pausen x Faktor, je Ort eine eigene Datei
     * (leuchtturm-tempo.txt / kolosseum-tempo.txt). Runde = ein abgeschlossener Besuch; als Fehler zaehlt jede Warnung,
     * ein verschluckter "Quick Attack" und ein abgebrochener Besuch (zaehlt beim naechsten fertigen Besuch mit).
     * Das Warten auf Bildwechsel (sleepUntilChanged) und auf den laufenden Kampf bleibt, wie es ist.
     */
    private final de.hustlebot.core.Tempolerner tempo;
    private int runFehler;
    /** Wie oft hintereinander "Quick Attack" verschluckt wurde (das Fenster blieb stehen); 0, sobald ein Kampf ein Ergebnis hat. */
    private int verschlucktInFolge;
    /** Ab so vielen verschluckten Klicks in Folge wird geprueft, ob die Truppe leer ist (3), und auf jeden Fall aufgehoert (10). */
    private static final int VERSCHLUCKT_PRUEFEN = 3, VERSCHLUCKT_MAX = 10;
    /** Fertige Besuche seit dem Start (fuer die Tempo-Zeile alle 10 Besuche; nicht in reset()). */
    private int runden;

    private void warnung(String wo, String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn(wo, text);
    }

    /** Besuch fertig: zaehlen und das Tempo lernen (Autor 24.10.). */
    private void besuchFertig(RunTracker runs) {
        runs.completeRun();
        runden++;
        tempo.rundeFertig(runFehler == 0);
        if (runden % 10 == 0) {
            Log.info(ort.de(), Sprache.t("Speed after " + runden + " visits: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + runden + " Besuchen: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    public Ort ort() {
        return ort;
    }

    @Override
    public String id() {
        return ort.id();
    }

    @Override
    public String displayName() {
        return Sprache.t(ort.en(), ort.de());
    }

    @Override
    public List<String> requiredTemplates() {
        List<String> n = new ArrayList<>(List.of(ort.node(), ort.titel(), ort.toBattle(),
                ort.refresh(), ort.quick(), T_HOME, T_SIEG));
        if (ort.auswahl() != null) n.add(ort.auswahl());
        return n;
    }

    @Override
    public void reset() {
        energieGeprueft = fertig = kampfLaeuft = false;
        verschlucktInFolge = 0;
        erlaubt = 0;
        energieLeseVersuche = 0;
        kaempfe = siege = niederlagen = niederlagenSeitRefresh = refreshs = 0;
        blind = schritte = wischer = ohneKnopf = dunkelFolge = 0;
        titelSeit = karteGeklicktUm = quickGeklicktUm = ortGeklicktUm = 0;
        auswahlGeklickt = false;
        auswahlEnergieGelesen = false;
        wettbewerbKlickUm = 0;
        wettbewerbKlicks = 0;
        helligkeitRuhig = -1;
        meinePower = -1;          // Autor: "jedes mal, wenn er in das Kolosseum geht, einmal ablesen"
        meineLeseVersuche = 0;
        gegnerPower.clear();
        spaehZeile = null;
        spaehSchritte = 0;
        nullSchonGesehen = false;
        letzteKnopfZahl = 0;
        kampfZeile = -1;
        verlorenGegen.clear();
        kettenTiefe = 0;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        if (++schritte > MAX_SCHRITTE) {
            return abbrechen(Sprache.t("too many steps, the visit hangs", "zu viele Schritte, der Besuch haengt"));
        }
        long jetzt = System.currentTimeMillis();
        String wo = ort.de();

        // AFK-Rauswurf ("are you still here?"): Update, das Spiel laedt neu.
        // Autor 24.10. (Ideenliste A16): hoechstens jede Sekunde statt in jedem Schritt -- der Suchlauf nach dem Update-Knopf
        // kostet ~33 ms je Schritt (70 Suchen in einem Kolosseum-Besuch), und die Huelle (BotEngine) sieht ohnehin alle 2 s nach.
        if (jetzt - afkGeprueftUm >= 1000) {
            afkGeprueftUm = jetzt;
            if (Stoerfenster.afkUpdate(screen, ctx, wo)) return Outcome.ACTED;
        }
        // "You have received a reward" nach einem anderen Modus -- wegklicken (Autor, 28.09.).
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, wo)) return Outcome.ACTED;
        // Werbefenster "Running out of apples?", Tagesbelohnung (Autor, 29.09.: der Bot
        // drueckte 8x "Weltkarte", waehrend das Apfel-Fenster offen war -- der Map-Knopf
        // "passt" auch hinter dem Fenster). Hoechstens jede Sekunde (~0,2 s Suche).
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, wo)) return Outcome.ACTED;
        }

        // Autor 24.10. (Ideenliste A16): laeuft ein Kampf, nicht alle 0,5 s das ganze Bild durchsuchen (ein Schritt kostet hier
        // ~0,23 s: Home, Quick Attack, Titel, Auswahl -- alles Fehlsuchen), sondern per Kastenblick auf "Home" warten. Zeigt
        // der die Aufstellung noch (Nachfrage "Truppe nicht voll"), uebernimmt der normale Ablauf.
        // Ein Kampf dauert 1-3 s; nach 12 s ohne "Home" sucht wieder der normale Ablauf (ganzes Bild), falls der Knopf
        // doch woanders sitzt als die gemerkte Stelle.
        if (kampfLaeuft && spaehZeile == null && jetzt - quickGeklicktUm < 12_000) {
            Outcome kampf = kampfAbwarten(ctx, runs);
            if (kampf != null) return kampf;
        }

        // --- 1. Kampfergebnis: "Home" -----------------------------------
        if (ctx.visibleIfKnown(screen, T_HOME) && !ctx.visibleIfKnown(screen, ort.quick())) {
            return kampfErgebnis(screen, ctx, runs);
        }

        // --- 2. Aufstellungsfenster: "Quick Attack!" --------------------
        if (ctx.visibleIfKnown(screen, ort.quick())) {
            return aufstellung(screen, ctx, runs);
        }

        // --- 3. Die Gegnerliste -----------------------------------------
        // Autor, 29.09., 02:40: nachts ist der Himmel hinter der Ueberschrift dunkelrot statt
        // blau -- die Tag-Vorlage passte nur 0,79. Zweite Vorlage "<titel>_abend" (gelernt\).
        if (ctx.visibleIfKnown(screen, ort.titel()) || ctx.visibleIfKnown(screen, ort.titel() + "_abend")) {
            return gegnerliste(screen, ctx, runs);
        }

        // --- 3b. Zwischenfenster ("Select an activity" -> "Colosseum") ---
        if (ort.auswahl() != null && ctx.visibleIfKnown(screen, ort.auswahl())) {
            blind = 0;
            // ENERGIE SCHON IN DER AUSWAHL (Autor, 28.09., Bild): Unter "Colosseum"
            // steht die Energie ("0/5"). Bei 0 gar nicht erst hinein -- gleich
            // weiter im Ablauf. Nicht lesbar: wie bisher hinein und drinnen lesen.
            if (!fertig && ort == KOLOSSEUM && !auswahlEnergieGelesen) {
                auswahlEnergieGelesen = true;
                // Der Turm steht im selben Fenster: ist sein Balken voll (90/90),
                // braucht der Titanen-Turm danach gar nicht mehr hin (Autor, 28.09.).
                int[] turm = TitanTurmMode.balkenLesen(screen, ctx);
                if (turm != null && turm[1] > 0 && turm[0] >= turm[1]) {
                    TitanTurmMode.vollGesehen();
                    Log.info(wo, Sprache.t("Seen on the side: tower " + turm[0] + "/" + turm[1] + " -- all towers done.",
                            "Nebenbei gesehen: Turm " + turm[0] + "/" + turm[1] + " -- alle Tuerme fertig."));
                }
                int[] e = auswahlEnergie(screen, ctx);
                if (e != null && e[0] == 0 && e[1] > 0) {
                    fertig = true;
                    ort.plan().naechsterBesuchIn(Energieplan.NACH_KAEMPFEN_MIN,
                            Sprache.t("Energy 0/" + e[1] + " already in the selection -- not entered.", "Energie 0/" + e[1] + " schon in der Auswahl -- nicht hineingegangen."));
                } else if (e != null) {
                    Log.info(wo, Sprache.t("Energy according to the selection: " + e[0] + "/" + e[1] + ".",
                            "Energie laut Auswahl: " + e[0] + "/" + e[1] + "."));
                }
            }
            if (fertig) {
                if (ctx.clickIfVisibleIfKnown(screen, T_X, Sprache.t("Close the selection window", "Auswahlfenster schliessen"))) {
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
            } else if (ctx.clickIfVisibleIfKnown(screen, ort.auswahl(), wo + " waehlen")) {
                auswahlGeklickt = true;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- 3c. Ein Kampf laeuft: auf "Home" warten, NICHTS sonst ---------
        //
        // Autor, 26.09., mit Video (Sek. 26-27): Nach jedem "Quick Attack" im
        // Kolosseum wischte der Bot ueber den Bildschirm. Der Kampf ist kein
        // bekanntes Bild, und "bis 20 s nach dem Map-Klick gilt Unbekanntes als
        // Weltkarte" griff -- also "Karte nach links ziehen". Jetzt: laeuft
        // ein Kampf, wird nur gewartet, bis "Home" kommt (Schritt 1).
        if (kampfLaeuft) {
            blind = 0;
            if (jetzt - quickGeklicktUm < KAMPF_MAX_MS) {
                ctx.sleep(150);
                return Outcome.WAITING;
            }
            warnung(wo, Sprache.t("No \"Home\" after " + KAMPF_MAX_MS / 1000 + " s -- the fight is " + "not counted, carrying on as usual.",
                    "Nach " + KAMPF_MAX_MS / 1000 + " s kam kein \"Home\" -- der Kampf wird "
                    + "nicht mitgezaehlt, es geht normal weiter."));
            kampfLaeuft = false;
        }
        // Schon drin gewesen (Liste gesehen oder Auswahl geklickt)? Dann ist
        // ein unbekanntes Bild kein Grund mehr, an der Karte zu ziehen.
        boolean drinGewesen = titelSeit > 0 || auswahlGeklickt;

        // --- 4. Weltkarte mit dem Ort -------------------------------------
        boolean ortZuSehen = ctx.visibleIfKnown(screen, ort.node());
        if (!fertig && ortZuSehen
                && ctx.clickIfVisibleIfKnown(screen, ort.node(), wo + " oeffnen")) {
            blind = 0;
            ortGeklicktUm = jetzt;
            if (karteGeklicktUm > 0) GEMERKTE_ZUEGE.put(ort.de(), wischer);   // Autor 24.10. (Ideenliste A17)
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // --- 5. Die Burg --------------------------------------------------
        if (ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT)) {
            blind = 0;
            if (fertig) {
                besuchFertig(runs);
                return Outcome.FINISHED;
            }
            if (jetzt - karteGeklicktUm < 2500) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, de.hustlebot.core.Sprache.t("Open the world map (", "Weltkarte oeffnen (") + wo + ")")
                    || ctx.clickIfVisibleIfKnown(screen, T_MAP, de.hustlebot.core.Sprache.t("Open the world map (", "Weltkarte oeffnen (") + wo + ")")) {
                karteGeklicktUm = jetzt;
                wischer = 0;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- 6. Weltkarte ohne (erkannten) Ort ----------------------------
        boolean weltkarte = ortZuSehen
                || Weltkarte.erkannt(screen, ctx)
                || (!drinGewesen && karteGeklicktUm > 0 && jetzt - karteGeklicktUm < 20_000
                    // Autor, 29.09.: nach dem Klick auf den Ort ist ein unbekanntes Bild NICHT
                    // die Weltkarte -- sonst wischte der Bot im Leuchtturm hin und her.
                    && jetzt - ortGeklicktUm > 20_000);
        if (weltkarte) {
            blind = 0;
            if (fertig) {
                // Autor, 26.09.: NICHT in die Burg zurueck. Nach Leuchtturm/
                // Kolosseum steht der Bot auf der Weltkarte -- von dort macht der
                // naechste Modus gleich weiter (er klickt sein Ziel an oder geht
                // selbst mit "Home" in die Burg, der Clan-Krieg immer).
                Log.info(wo, de.hustlebot.core.Sprache.t("Done, staying on the world map -- the next mode starts from here.", "Fertig, bleibt auf der Weltkarte -- es geht von hier aus weiter."));
                besuchFertig(runs);
                return Outcome.FINISHED;
            }
            if (drinGewesen || jetzt - ortGeklicktUm < 20_000) {   // war schon drin / Ort eben geklickt: nicht suchen
                if (titelSeit == 0 && ortGeklicktUm > 0 && jetzt - ortGeklicktUm < 20_000) {
                    aufListeWarten(ctx, 300);   // Autor 24.10. (Ideenliste A18): die Liste laedt -- per Kastenblick auf den Titel
                } else {
                    ctx.sleep(tempo.ms(200));
                }
                return Outcome.WAITING;
            }
            if (jetzt - karteGeklicktUm < 1500) {   // Karte erst laden lassen (wie Kathedrale, 25.09.)
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            // Autor 24.10. (Ideenliste A17): beim letzten Besuch lag der Ort nach N Zuegen im Bild -- diese N Zuege jetzt
            // gleich hintereinander (je ~0,25 s statt Zug, Bild, Suche). Passt es nicht mehr, sucht der Bot wie bisher weiter.
            Integer gemerkt = GEMERKTE_ZUEGE.get(ort.de());
            if (wischer == 0 && gemerkt != null && gemerkt > 1 && gemerkt <= 3) {
                int y = ctx.height() / 2;
                int links = (int) (ctx.width() * 0.25), rechts = (int) (ctx.width() * 0.75);
                for (int i = 0; i < gemerkt; i++) {
                    wischer++;
                    if (ort.ersteRichtungRechts()) ctx.input().drag(links, y, rechts, y);
                    else ctx.input().drag(rechts, y, links, y);
                    if (i < gemerkt - 1) ctx.sleep(tempo.ms(250));
                }
                Log.action("Karte", Sprache.t("Dragging the world map " + gemerkt + " times in a row like last time (to " + wo + ").",
                        "Weltkarte " + gemerkt + " Mal hintereinander gezogen wie beim letzten Mal (zu " + wo + ")."));
                ctx.sleepUntilChanged(400);
                return Outcome.ACTED;
            }
            if (wischer < MAX_WISCHER) {
                wischer++;
                // Erst dreimal in die erste Richtung, dann in die Gegenrichtung.
                boolean nachRechts = (wischer <= 3) == ort.ersteRichtungRechts();
                int y = ctx.height() / 2;
                int links = (int) (ctx.width() * 0.25), rechts = (int) (ctx.width() * 0.75);
                Log.action("Karte", de.hustlebot.core.Sprache.t("Dragging the world map ", "Weltkarte nach ") + (nachRechts ? de.hustlebot.core.Sprache.t("right", "rechts") : de.hustlebot.core.Sprache.t("left", "links"))
                        + de.hustlebot.core.Sprache.t(" to find ", " ziehen, um ") + wo + de.hustlebot.core.Sprache.t(" (swipe ", " zu finden (Zug ") + wischer + de.hustlebot.core.Sprache.t(" of ", " von ")
                        + MAX_WISCHER + ").");
                if (nachRechts) ctx.input().drag(links, y, rechts, y);
                else ctx.input().drag(rechts, y, links, y);
                ctx.sleepUntilChanged(400);
                return Outcome.ACTED;
            }
            runFehler++;   // Autor 24.10.: zaehlt wie eine Warnung fuers Tempo
            Log.error(wo, Sprache.t(wo + " was not found on the world map. " + "Does the template " + ort.node() + " still match?",
                    wo + " wurde auf der Weltkarte nicht gefunden. "
                    + "Passt die Vorlage " + ort.node() + " noch?"));
            fertig = true;
            ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN, Sprache.t(wo + " not found.", wo + " nicht gefunden."));
            return heimVonDerKarte(screen, ctx);
        }

        // --- 7a. Nach dem eigenen "End journey" (Schritt 7b): "Yes", Warnung, Abschluss ---
        // Vor dem roten X -- sonst ginge die Nachfrage nur wieder zu, und hinter der
        // Quit-Warnung liegt ein rotes X, das nicht anklickbar ist.
        if (Stoerfenster.verliesRestAbraeumen(screen, ctx, wo)) {
            blind = 0;
            return Outcome.ACTED;
        }

        // --- 7. Irgendein Fenster davor: rotes X --------------------------
        if (ctx.clickIfVisibleIfKnown(screen, T_X, Sprache.t("Close the window (way to " + wo + ")", "Fenster schliessen (Weg zu " + wo + ")"))) {
            ctx.sleepUntilChanged(1000);
            return Outcome.ACTED;
        }

        // --- 7b. Eine fertige Verlies-Reise liegt noch offen (29.09., 20:38) ---
        // 100 %, gruener "End journey": beenden, 7a raeumt Nachfrage, Warnung und
        // Abschlussfenster ab; ein Fenster mit rotem X schliesst Schritt 7.
        if (Stoerfenster.fertigeVerliesReiseBeenden(screen, ctx, Sprache.t("Way to ", "Weg zu ") + wo)) {
            return Outcome.ACTED;
        }

        // --- 8. Nichts erkannt --------------------------------------------
        if (++blind > MAX_BLIND) {
            return abbrechen(Sprache.t("Screen not recognized", "Bildschirm nicht erkannt"));
        }
        if (titelSeit == 0 && (auswahlGeklickt || (ortGeklicktUm > 0 && jetzt - ortGeklicktUm < 20_000))) {
            aufListeWarten(ctx, 400);   // Autor 24.10. (Ideenliste A18): die Liste laedt noch -- per Kastenblick auf den Titel
        } else {
            ctx.sleep(tempo.ms(250));
        }
        return Outcome.UNKNOWN;
    }

    /**
     * Schritt 1: das Kampfergebnis ("Home") ist zu sehen. Gewonnen oder verloren zaehlen, "Home" druecken.
     * Autor 24.10.: bisher mitten in step(); herausgeloest, damit die schnellen Ketten (Ideenliste A15/A16/A18) dieselbe
     * Stelle mit einem frischen Bild aufrufen koennen.
     */
    private Outcome kampfErgebnis(Mat screen, BotContext ctx, RunTracker runs) {
        String wo = ort.de();
        if (kampfLaeuft) {
            kampfLaeuft = false;
            verschlucktInFolge = 0;   // ein Kampf hatte ein Ergebnis -- die Klicks wirken
            boolean sieg = ctx.visibleIfKnown(screen, T_SIEG);
            if (sieg) {
                siege++;
            } else {
                niederlagen++;
                niederlagenSeitRefresh++;
                if (kampfZeile >= 0) verlorenGegen.add(kampfZeile);
            }
            ort.plan().kampfGezaehlt(sieg);   // fuer !stats
            Log.info(wo, de.hustlebot.core.Sprache.t("Fight ", "Kampf ") + kaempfe + de.hustlebot.core.Sprache.t(" of ", " von ") + erlaubt + ": "
                    + (sieg ? de.hustlebot.core.Sprache.t("Win", "Sieg") : de.hustlebot.core.Sprache.t("Loss (", "Niederlage (") + niederlagenSeitRefresh + de.hustlebot.core.Sprache.t(" since the last refresh)", " seit dem letzten Refresh)")));
            // 22.10. nachts: ohne Fortschritts-Meldung schickte der Haenger-Melder nach 20 s einen Bericht (#DHUZ),
            // obwohl 4 Kaempfe in 21 s liefen.
            runs.progress(de.hustlebot.core.Sprache.t("Fight " + kaempfe + " done", "Kampf " + kaempfe + " fertig"));
        }
        blind = 0;
        boolean geklickt = ctx.clickIfVisibleIfKnown(screen, T_HOME, wo + ": Kampfergebnis bestaetigen");
        // Autor 24.10. (Ideenliste A16): Pause nach dem Bestaetigen war 0,4-0,5 s (Bildwechsel abwarten, ganzer Schritt mit
        // allen Fehlsuchen). Jetzt: per Kastenblick warten, bis die Liste wieder steht (Titel da, Bild nicht dunkel), und
        // gleich auf "To battle!" -- mit demselben Code und denselben Sicherungen (roter Blitz, Energie, dunkles Fenster) wie
        // im normalen Schritt. Wird die Liste nicht gesehen, geht es wie bisher weiter.
        if (geklickt && kettenTiefe < MAX_KETTE) {
            Mat liste = listeAbwarten(ctx, LISTE_NACH_ERGEBNIS_MS);
            if (liste != null) {
                kettenTiefe++;
                try {
                    return gegnerliste(liste, ctx, runs);
                } finally {
                    kettenTiefe--;
                }
            }
        }
        ctx.sleepUntilChanged(700);
        return Outcome.ACTED;
    }

    /**
     * Autor 24.10. (Ideenliste A16): Wartet per Kastenblick, bis die Gegnerliste wieder dasteht (Titel zu sehen und das Bild
     * nicht dunkler als die ruhige Liste -- ein Ergebnisbild oder Fenster darueber dunkelt ab, der Titel passt dann trotzdem).
     * Liefert dann ein frisches ganzes Bild, sonst null (keine Stelle bekannt, Spiel verdeckt, Zeit um): der Aufrufer macht
     * wie bisher weiter.
     */
    private Mat listeAbwarten(BotContext ctx, long maxMs) {
        long bis = System.currentTimeMillis() + maxMs;
        String abend = ort.titel() + "_abend";
        while (System.currentTimeMillis() < bis) {
            if (ctx.warteAufKnopf(Math.max(30, bis - System.currentTimeMillis()), Matcher.DEFAULT_THRESHOLD, ort.titel(), abend).isEmpty()) {
                return null;
            }
            // Das Kampfergebnis (Knopf "Home") muss weg sein, erst dann ein Bild nehmen: die Liste scheint durch das
            // abgedunkelte Ergebnisbild hindurch, der Titel passt darum schon, solange es noch davor liegt.
            if (ctx.kastenBekannt(T_HOME) && ctx.kastenBlick(Matcher.DEFAULT_THRESHOLD, T_HOME).isPresent()) {
                ctx.sleep(tempo.ms(40));
                continue;
            }
            Mat f = ctx.refresh();
            if (f == null) return null;
            if (helligkeitRuhig <= 0 || helligkeit(f) >= helligkeitRuhig * DUNKEL_GRENZE) return f;
            ctx.sleep(tempo.ms(60));   // das Ergebnisbild blendet noch aus
        }
        return null;
    }

    /**
     * Autor 24.10. (Ideenliste A16): Waehrend ein Kampf laeuft: ~0,4 s lang per Kastenblick auf "Home" (Ergebnis) schauen, ohne
     * das ganze Bild zu durchsuchen. Liefert das Ergebnis von kampfErgebnis(), wenn "Home" auf einem frischen ganzen Bild
     * bestaetigt ist (derselbe Test wie in step()); WAITING, wenn nichts zu sehen ist (der Kampf laeuft); null, wenn der
     * normale Ablauf ran soll (Aufstellungsfenster steht noch, oder es ist keine Stelle von "Home" bekannt).
     */
    private Outcome kampfAbwarten(BotContext ctx, RunTracker runs) {
        if (!ctx.kastenBekannt(T_HOME) || !ctx.kastenBekannt(ort.quick())) return null;
        Optional<Matcher.Hit> h = ctx.warteAufKnopf(KAMPF_BLICK_MS, Matcher.DEFAULT_THRESHOLD, T_HOME, ort.quick());
        if (h.isEmpty()) return Outcome.WAITING;
        if (!T_HOME.equals(h.get().name())) return null;
        Mat f = ctx.refresh();
        if (f == null) return Outcome.WAITING;
        if (ctx.visibleIfKnown(f, T_HOME) && !ctx.visibleIfKnown(f, ort.quick())) return kampfErgebnis(f, ctx, runs);
        return Outcome.WAITING;
    }

    /**
     * Schritt 2: das Aufstellungsfenster ("Quick Attack!") ist zu sehen. Autor 24.10.: herausgeloest wie kampfErgebnis().
     */
    private Outcome aufstellung(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        String wo = ort.de();
        blind = 0;
        // 22.10. nachts: "Your squad does not have enough units ... Start battle now?" (Yes/No) -- ohne Antwort tippte
        // der Bot 2 Minuten lang "Quick Attack" dahinter. Autor: Stoerfenster wegdruecken und weitermachen -> "Yes".
        // NIE DIAMANTEN: nur direkt nach dem eigenen "Quick Attack" und nur, wenn im Fenster "units"/"squad" steht.
        if (kampfLaeuft && jetzt - quickGeklicktUm < 8000 && truppeNichtVoll(ctx)) {
            for (String ja : new String[]{"dc_yes", "cw_yes", "end_journey_yes"}) {
                if (ctx.matcher().has(ja) && ctx.clickIfVisibleIfKnown(screen, ja,
                        wo + Sprache.t(": squad not full -- start the battle anyway (Yes)", ": Truppe nicht voll -- trotzdem kaempfen (Yes)"))) {
                    quickGeklicktUm = jetzt;
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
            }
        }
        // Nur angesehen, nicht gekaempft: Staerke ablesen, dann wieder zu.
        if (spaehZeile != null) return spaehen(screen, ctx);
        // Klick verschluckt? Steht das Fenster 1,5 s nach "Quick Attack"
        // immer noch da, hat der Kampf nie angefangen: nicht mitzaehlen,
        // noch einmal druecken (sonst schloss der Bot hier das Fenster und
        // blieb danach stehen -- Code-Pruefung 26.09.).
        if (kampfLaeuft && jetzt - quickGeklicktUm > 1500) {
            kampfLaeuft = false;
            kaempfe--;
            runFehler++;   // Autor 24.10.: verschluckter Klick = Fehler fuers Tempo
            verschlucktInFolge++;
            // Bugjagd 29.10. (Bericht UZ4S, ben 28.10.): das Spiel zeigte "Move your troops to the Barracks!" -- die Truppe war leer
            // (Einheiten woanders, z. B. im Clan-Krieg) -- und der Bot drueckte endlos "Quick Attack" (8 Klicks in 20 s, bis zum
            // 15-Minuten-Abbruch). Nach 3 verschluckten Klicks nachsehen; nach 10 auf jeden Fall aufhoeren.
            boolean leer = verschlucktInFolge >= VERSCHLUCKT_PRUEFEN && LeereTruppe.sichtbar(ctx);
            if (!fertig && (leer || verschlucktInFolge >= VERSCHLUCKT_MAX)) {
                fertig = true;
                warnung(wo, Sprache.t(
                        (leer ? "The game says \"Move your troops to the Barracks!\" -- the squad is empty (troops are away, e.g. in the clan war). "
                              : "\"Quick Attack\" was pressed " + verschlucktInFolge + " times without a fight starting. ")
                        + "No more fights on this visit.",
                        (leer ? "Das Spiel sagt \"Move your troops to the Barracks!\" -- die Truppe ist leer (Einheiten sind woanders, z. B. im Clan-Krieg). "
                              : "\"Quick Attack\" wurde " + verschlucktInFolge + " Mal gedrueckt, ohne dass ein Kampf begann. ")
                        + "Auf diesem Besuch wird nicht mehr gekaempft."));
                ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN, Sprache.t(
                        leer ? "Squad empty." : "Quick Attack without effect.", leer ? "Truppe leer." : "Quick Attack ohne Wirkung."));
            }
        }
        if (kampfLaeuft) {
            ctx.sleep(tempo.ms(150));
            return Outcome.WAITING;
        }
        if (!fertig && kaempfe < erlaubt) {
            if (ctx.clickIfVisibleIfKnown(screen, ort.quick(), wo + ": Quick Attack")) {
                kampfLaeuft = true;
                kaempfe++;
                quickGeklicktUm = jetzt;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }
        // Hier nicht kaempfen (keine Energie mehr, Besuch vorbei): Fenster zu.
        if (ctx.clickIfVisibleIfKnown(screen, T_X, wo + Sprache.t(": close the squad window", ": Aufstellung schliessen"))) {
            ctx.sleepUntilChanged(800);
            return Outcome.ACTED;
        }
        ctx.sleep(tempo.ms(200));
        return Outcome.WAITING;
    }

    /**
     * Schritt 3: die Gegnerliste ist zu sehen (Titel erkannt). Autor 24.10.: herausgeloest wie kampfErgebnis().
     */
    private Outcome gegnerliste(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        String wo = ort.de();
        blind = 0;
        if (titelSeit == 0) titelSeit = jetzt;

        // Autor 24.10. (Ideenliste A16): Die zwei Suchen nach dem Wettbewerbs-Fenster (Claim reward / Close) laufen ueber das
        // ganze Bild und kosten zusammen ~70 ms je Schritt -- bei 27 Schritten im Kolosseum-Besuch fast 2 s, meist mitten in
        // der Kette Home -> To battle. Das Fenster erscheint beim Betreten der Liste, also VOR der Energie, und dunkelt die
        // Liste ab. Darum nur noch: solange die Energie nicht gelesen ist, sobald das Bild dunkler als die ruhige Liste ist,
        // und wenn schon einmal ein Wettbewerbsfenster geklickt wurde.
        boolean dunkelFuerWettbewerb = helligkeitRuhig > 0 && helligkeit(screen) < helligkeitRuhig * DUNKEL_GRENZE;
        if (!energieGeprueft || dunkelFuerWettbewerb || wettbewerbKlicks > 0) {
            Outcome wettbewerb = wettbewerbVorbei(screen, ctx, jetzt);
            if (wettbewerb != null) return wettbewerb;
        }

        if (!energieGeprueft) {
            int n;
            if (jetzt - titelSeit < TITEL_RUHE_MS) {
                // Autor 24.10. (Ideenliste A18): nicht stur 0,8 s warten -- steht "5/5" zweimal kurz hintereinander
                // sicher da und das Bild hat sich nicht veraendert, ist die Liste ruhig. Alles andere (1-4, nichts
                // lesbar, Bild noch in Bewegung) wartet wie bisher.
                Mat ruhig = energieFrueh(screen, ctx);
                if (ruhig == null) {
                    ctx.sleep(tempo.ms(150));
                    return Outcome.WAITING;
                }
                Log.debug(wo, "Energie 5/5 schon " + (System.currentTimeMillis() - titelSeit) + " ms nach dem Titel sicher (zwei gleiche Lesungen).");
                screen = ruhig;   // das erste Bild ist mit refresh() ungueltig geworden
                n = MAX_KAEMPFE;
            } else {
                // Autor, 26.09.: die ganze vorhandene Energie verbrauchen, nicht
                // nur bei 5/5. Die Zahl wird abgelesen (lh_energie_1..5 bzw.
                // ko_energie_1..5). Fehlen diese Bilder noch, gilt die alte
                // Regel: nur bei "Max" fuenf Kaempfe. "Max" zaehlt auch dann,
                // wenn die Zahlen da sind, aber keine passt (Code-Pruefung 26.09.).
                n = energieLesen(screen, ctx);
                if (n <= 0 && ctx.visibleIfKnown(screen, ort.max())) n = MAX_KAEMPFE;
            }
            // Nichts lesbar? Die Liste blendet vielleicht noch ein -- einmal
            // 0,4 s spaeter auf einem neuen Bild wiederholen.
            if (n == 0 && energieLeseVersuche++ < 1) {
                ctx.sleep(tempo.ms(400));
                return Outcome.WAITING;
            }
            energieGeprueft = true;
            helligkeitRuhig = helligkeit(screen);
            erlaubt = Math.min(Math.max(n, 0), MAX_KAEMPFE);
            if (erlaubt > 0) {
                Log.info(wo, de.hustlebot.core.Sprache.t("Energy " + erlaubt + "/5 -- " + erlaubt + " fights.", "Energie " + erlaubt + "/5 -- " + erlaubt + " Kaempfe."));
                ort.plan().kaempfeMoeglich(erlaubt);   // fuer !stats: "3 von 6" statt "3 von 10"
                runs.progress(de.hustlebot.core.Sprache.t("Energy " + erlaubt + "/5", "Energie " + erlaubt + "/5"));
            } else if (n < 0) {
                // Keine Vorlagen und kein "Max": alte Regel.
                fertig = true;
                ort.plan().naechsterBesuchIn(Energieplan.NACH_KAEMPFEN_MIN,   // Autor, 26.09.: "wenn nicht, dann erst wieder in 100 min"
                        Sprache.t("Energy not full (no \"Max\"), no fight.", "Energie nicht voll (kein \"Max\"), kein Kampf."));
            } else {
                // 0 abgelesen heisst nicht sicher 0 Energie -- vielleicht passt
                // nur keine Zahl. Der Blitz auf "To battle!" entscheidet: rot =
                // wirklich leer (100 min); nicht rot = nur nicht lesbar, dann
                // in 21 min noch einmal, und Autor erfaehrt es.
                fertig = true;
                Boolean rot = blitzObenRot(screen, ctx);
                if (rot == null || rot) {
                    ort.plan().naechsterBesuchIn(Energieplan.NACH_KAEMPFEN_MIN,   // Autor, 26.09.: "wenn nicht, dann erst wieder in 100 min"
                            Sprache.t("No energy, no fight.", "Keine Energie, kein Kampf."));
                } else {
                    warnung(wo, Sprache.t("The energy number matches no template (" + ort.energie() + "1..5), but the flash on \"To battle!\" is not red -- energy " + "is probably there. Please check the number templates. No fight.",
                            "Die Energie-Zahl passt zu keiner Vorlage (" + ort.energie()
                            + "1..5), aber der Blitz auf \"To battle!\" ist nicht rot -- Energie "
                            + "ist wohl da. Bitte die Zahlen-Vorlagen pruefen. Kein Kampf."));
                    ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN,
                            Sprache.t("Energy not readable, no fight.", "Energie nicht lesbar, kein Kampf."));
                }
            }
        }

        if (!fertig && kaempfe >= erlaubt && !kampfLaeuft) {
            fertig = true;
            ort.plan().naechsterBesuchIn(Energieplan.NACH_KAEMPFEN_MIN,
                    de.hustlebot.core.Sprache.t("Energy used up: " + kaempfe + " fights, " + siege + " wins, ", "Energie verbraucht: " + kaempfe + " Kaempfe, " + siege + " Siege, ")
                    + niederlagen + de.hustlebot.core.Sprache.t(" losses, ", " Niederlagen, ") + refreshs + "x Refresh.");
        }

        // Liegt ein unbekanntes Fenster darueber? Dann darin NICHTS anklicken.
        // Ein einzelnes dunkles Bild ist meist nur der Uebergang vom
        // Kampfergebnis zurueck -- erst warten, dann glauben.
        boolean dunkel = helligkeitRuhig > 0 && helligkeit(screen) < helligkeitRuhig * DUNKEL_GRENZE;
        if (!fertig && dunkel) {
            if (++dunkelFolge < 6) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            warnung(wo, Sprache.t("An unknown window lies over the enemy list. " + "Nothing gets clicked in it -- the visit ends.",
                    "Ueber der Gegnerliste liegt ein unbekanntes Fenster. "
                    + "Darin wird nichts angeklickt -- der Besuch wird beendet."));
            fertig = true;
            ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN, Sprache.t("Visit aborted.", "Besuch abgebrochen."));
        }
        if (!dunkel) dunkelFolge = 0;

        if (fertig) {
            // Leuchtturm: rotes X. Kolosseum: kein X, "Home" unten links
            // (Autor, 26.09.: landet dann auf der Weltkarte).
            if (ort.mitX() && ctx.clickIfVisibleIfKnown(screen, T_X, wo + " verlassen")) {
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            return heimVonDerKarte(screen, ctx);
        }

        // Zwei Niederlagen: neue Gegner (Autor, 26.09.: "im Kolosseum ziemlich
        // oft"). NUR der kostenlose Refresh, hoechstens alle 15 Minuten.
        // Gemessen: die Vorlage (kostenlos) passt auf den Knopf mit Diamant-
        // preis nur zu 0,33-0,38 -- und zur Sicherheit wird zusaetzlich nach
        // dem blauen Diamanten im Knopf gesehen. Ist er da: NIE druecken.
        if (niederlagenSeitRefresh >= NIEDERLAGEN_BIS_REFRESH
                && kostenlosRefresh(screen, ctx, jetzt, niederlagenSeitRefresh + Sprache.t("x lost", "x verloren"))) {
            return Outcome.ACTED;
        }

        // Den obersten Gegner mit "To battle!" nehmen.
        List<Matcher.Hit> knoepfe = ctx.matcher().findAll(screen, ort.toBattle(),
                Matcher.DEFAULT_THRESHOLD, 6);
        if (!knoepfe.isEmpty() && !kampfLaeuft) {
            ohneKnopf = 0;
            // Mehr Knoepfe als beim letzten Blick: das Spiel hat neue Gegner
            // gemischt (alle fuenf besiegt) -- die gemerkten Staerken gelten nicht mehr.
            if (letzteKnopfZahl > 0 && knoepfe.size() > letzteKnopfZahl) {
                gegnerPower.clear();
                verlorenGegen.clear();
            }
            letzteKnopfZahl = knoepfe.size();
            Matcher.Hit oben = knoepfe.stream()
                    .min(Comparator.comparingInt(Matcher.Hit::y)).orElseThrow();
            // ZWEITE SICHERUNG: roter Blitz = keine Energie mehr, egal was
            // abgelesen wurde. Dann NICHT druecken -- dahinter koennte ein
            // Kauf-Fenster aufgehen.
            if (blitzRot(screen, oben)) {
                Log.info(wo, Sprache.t("The flash on \"To battle!\" is red -- no energy left.",
                        "Der Blitz auf \"To battle!\" ist rot -- keine Energie mehr."));
                fertig = true;
                ort.plan().naechsterBesuchIn(Energieplan.NACH_KAEMPFEN_MIN,
                        de.hustlebot.core.Sprache.t("Energy used up: " + kaempfe + " fights, " + siege + " wins, ", "Energie verbraucht: " + kaempfe + " Kaempfe, " + siege + " Siege, ")
                        + niederlagen + de.hustlebot.core.Sprache.t(" losses.", " Niederlagen."));
                ctx.sleep(tempo.ms(150));
                return Outcome.ACTED;
            }
            Matcher.Hit ziel = oben;
            if (kannSpaehen(ctx)) {
                // Erst jeden noch unbekannten Gegner ansehen (von oben nach unten).
                for (Matcher.Hit k : knoepfe.stream().sorted(Comparator.comparingInt(Matcher.Hit::y)).toList()) {
                    if (powerBei(k.centerY()) == null) {
                        spaehZeile = k.centerY();
                        spaehSchritte = 0;
                        nullSchonGesehen = false;
                        Log.action("Klick", Sprache.t(wo + ": look at the enemy, read the power  [" + k.centerX() + "," + k.centerY() + "]",
                                wo + ": Gegner ansehen, Staerke ablesen  ["
                                + k.centerX() + "," + k.centerY() + "]"));
                        ctx.input().click(k);
                        return spaehKette(ctx, runs);   // Autor 24.10. (Ideenliste A15)
                    }
                }
                Matcher.Hit gewaehlt = gegnerWaehlen(knoepfe, screen, ctx, jetzt);
                if (gewaehlt == null) return Outcome.ACTED;   // Refresh gedrueckt
                ziel = gewaehlt;
            }
            Log.action("Klick", wo + de.hustlebot.core.Sprache.t(": To battle (fight ", ": To battle (Kampf ") + (kaempfe + 1) + ")  ["
                    + ziel.centerX() + "," + ziel.centerY() + "]");
            kampfZeile = ziel.centerY();
            spaehZeile = null;   // hier wird gekaempft, nichts mehr angesehen
            ctx.input().click(ziel);
            // Autor 24.10. (Ideenliste A16): gleich auf die Aufstellung warten (Kastenblick) und "Quick Attack!" druecken,
            // statt erst den Bildwechsel abzuwarten und dann einen ganzen Schritt zu machen. Kommt sie nicht, geht es wie
            // bisher weiter; gedrueckt wird nur, was auf einem frischen ganzen Bild wirklich zu sehen ist.
            if (kettenTiefe < MAX_KETTE && ctx.kastenBekannt(ort.quick())) {
                if (ctx.warteAufKnopf(QUICK_NACH_KLICK_MS, Matcher.DEFAULT_THRESHOLD, ort.quick()).isPresent()) {
                    Mat f = ctx.refresh();
                    if (f != null && ctx.visibleIfKnown(f, ort.quick())) {
                        kettenTiefe++;
                        try {
                            return aufstellung(f, ctx, runs);
                        } finally {
                            kettenTiefe--;
                        }
                    }
                }
                return Outcome.ACTED;
            }
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        // Kein Knopf: die Gegner werden gerade neu gemischt. Nicht ewig warten.
        if (++ohneKnopf > 30) {
            fertig = true;
            ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN,
                    Sprache.t("No \"To battle!\" in sight any more.", "Kein \"To battle!\" mehr zu sehen."));
        }
        ctx.sleep(tempo.ms(200));
        return Outcome.WAITING;
    }

    /**
     * "Home" unten links -- auf der Weltkarte wie in der Kolosseum-Liste an
     * derselben Stelle (Vorlage worldmap_home, sonst feste Stelle wie im
     * Kisten-Gang). Aus dem Kolosseum fuehrt es auf die Weltkarte, von dort
     * in die Burg.
     */
    /**
     * Autor 22.10. (Bild): "Manchmal gibt es im Leuchtturm so Contests -- wenn du diesen Screen siehst ('The contest is
     * over. Rewards for ranking places have been distributed.'), klick einfach auf 'Claim reward' und mach dann alle 5
     * Kaempfe, oder so viele, wie Energie da ist." Danach das Wettbewerbsfenster mit "Close" zu und die Energie neu
     * ablesen. NIE "Watch" (Werbung). null = kein Wettbewerb, weiter wie gewohnt.
     */
    private Outcome wettbewerbVorbei(Mat screen, BotContext ctx, long jetzt) {
        if (!ctx.matcher().has(T_CLAIM) && !ctx.matcher().has(T_WETTBEWERB_ZU)) return null;
        boolean claim = ctx.visibleIfKnown(screen, T_CLAIM);
        boolean zu = !claim && ctx.visibleIfKnown(screen, T_WETTBEWERB_ZU)
                && ctx.matcher().findAll(screen, ort.toBattle(), Matcher.DEFAULT_THRESHOLD, 6).isEmpty();
        if (!claim && !zu) return null;
        if (jetzt - wettbewerbKlickUm < 1500) {   // das Fenster reagiert noch
            ctx.sleep(tempo.ms(200));
            return Outcome.WAITING;
        }
        if (++wettbewerbKlicks > 6) {
            if (wettbewerbKlicks == 7) warnung(ort.de(), Sprache.t("The contest window does not go away -- leaving the visit.",
                    "Das Wettbewerbsfenster geht nicht weg -- der Besuch wird beendet."));
            fertig = true;
            ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN, Sprache.t("Contest window stuck.", "Wettbewerbsfenster haengt."));
            return null;
        }
        String was = claim ? Sprache.t(": contest over -- claim the reward", ": Wettbewerb vorbei -- Belohnung abholen (Claim reward)")
                           : Sprache.t(": close the contest window", ": Wettbewerbsfenster schliessen (Close)");
        if (!ctx.clickIfVisibleIfKnown(screen, claim ? T_CLAIM : T_WETTBEWERB_ZU, ort.de() + was)) return null;
        if (claim) Log.info(ort.de(), Sprache.t("The contest is over -- reward claimed. Then the usual fights.",
                "Der Wettbewerb ist vorbei -- Belohnung abgeholt. Danach die gewohnten Kaempfe."));
        wettbewerbKlickUm = jetzt;
        // Die Energie erst ablesen, wenn die Gegnerliste ruhig dasteht (und die Helligkeit dann neu messen).
        energieGeprueft = false;
        titelSeit = 0;
        energieLeseVersuche = 0;
        helligkeitRuhig = -1;
        dunkelFolge = 0;
        kaempfe = 0;
        ctx.sleepUntilChanged(1500);
        return Outcome.ACTED;
    }

    private Outcome heimVonDerKarte(Mat screen, BotContext ctx) {
        Weltkarte.heim(screen, ctx, ort.de());
        karteGeklicktUm = 0;   // ab jetzt gilt "Karte" nicht mehr als sicher
        return Outcome.ACTED;
    }

    // ------------------------------------------------------------------
    //  Kolosseum: Staerken ablesen und den Gegner waehlen
    // ------------------------------------------------------------------

    /** Nur im Kolosseum, und nur wenn Autor die drei Bilder aufgenommen hat. */
    private boolean kannSpaehen(BotContext ctx) {
        return ort == KOLOSSEUM && ctx.matcher().has(T_POWER)
                && ctx.matcher().has(T_TAB_GEGNER) && ctx.matcher().has(T_TAB_ICH);
    }

    /** Gemerkte Power fuer die Zeile auf Hoehe y (bis 15 Pixel daneben), null = noch nicht angesehen. */
    private Long powerBei(int y) {
        for (var e : gegnerPower.entrySet()) {
            if (Math.abs(e.getKey() - y) <= 15) return e.getValue();
        }
        return null;
    }

    /**
     * Im offenen Gegner-Fenster: Reiter wechseln, Power ablesen, wieder zu.
     * "ko_tab_gegner" zu sehen heisst: der Reiter "Your squad" ist offen
     * (Autors Power); "ko_tab_ich" zu sehen heisst: "Enemy squad" ist offen.
     */
    private Outcome spaehen(Mat screen, BotContext ctx) {
        String wo = ort.de();
        if (++spaehSchritte > 14) {
            if (powerBei(spaehZeile) == null) gegnerPower.put(spaehZeile, -1L);
            warnung(wo, de.hustlebot.core.Sprache.t("Could not read the power -- this opponent counts as unknown.", "Die Staerke liess sich nicht ablesen -- dieser Gegner zaehlt als unbekannt."));
            return spaehSchliessen(screen, ctx);
        }
        boolean meinReiter = ctx.visibleIfKnown(screen, T_TAB_GEGNER);
        boolean gegnerReiter = ctx.visibleIfKnown(screen, T_TAB_ICH);
        if (meinReiter && !gegnerReiter) {
            if (meinePower < 0 && meineLeseVersuche < 3) {
                meineLeseVersuche++;
                var p = powerLesen(screen, ctx);
                if (p.isPresent()) {
                    meinePower = p.getAsLong();
                    Log.info(wo, "Meine Power: " + String.format("%,d", meinePower).replace(',', ' ') + ".");
                }
            }
            if (powerBei(spaehZeile) == null) {
                ctx.clickIfVisibleIfKnown(screen, T_TAB_GEGNER, wo + Sprache.t(": tab \"Enemy squad\"", ": Reiter \"Enemy squad\""));
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
            return spaehSchliessen(screen, ctx);
        }
        if (gegnerReiter && !meinReiter) {
            if (powerBei(spaehZeile) == null) {
                var p = powerLesen(screen, ctx);
                if (p.isEmpty()) {
                    ctx.sleep(tempo.ms(200));   // Zahl blendet vielleicht noch ein -- auf dem naechsten Bild noch einmal
                    return Outcome.WAITING;
                }
                // Eine 0 erst glauben, wenn sie auf zwei Bildern hintereinander steht. Autor 24.10.: manche Teams im
                // Kolosseum haben wirklich 0 Kraft -- dann ist es ein leichter Sieg (Ideenliste B06 darum nicht umgesetzt).
                if (p.getAsLong() == 0 && !nullSchonGesehen) {
                    nullSchonGesehen = true;
                    ctx.sleep(tempo.ms(200));
                    return Outcome.WAITING;
                }
                nullSchonGesehen = false;
                gegnerPower.put(spaehZeile, p.getAsLong());
                Log.info(wo, Sprache.t("Enemy in row " + spaehZeile + ": power " + String.format("%,d", p.getAsLong()).replace(',', ' ') + ".",
                        "Gegner in Zeile " + spaehZeile + ": Power "
                        + String.format("%,d", p.getAsLong()).replace(',', ' ') + "."));
            }
            if (meinePower < 0 && meineLeseVersuche < 3) {
                ctx.clickIfVisibleIfKnown(screen, T_TAB_ICH, wo + Sprache.t(": tab \"Your squad\"", ": Reiter \"Your squad\""));
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
            return spaehSchliessen(screen, ctx);
        }
        ctx.sleep(tempo.ms(150));   // Reiter noch nicht erkannt
        return Outcome.WAITING;
    }

    /**
     * Autor 24.10. (Ideenliste A15): Gegner ansehen in EINER Kette. Gemessen waren es 0,9-1,1 s je Gegner (ansehen -> Reiter
     * "Enemy squad" -> lesen -> schliessen), davon rund 0,55 s eigene Schritte (je ~0,15-0,25 s Bild holen, Fehlsuchen,
     * Pause), der Rest ist das Spiel (Fenster auf 0,15 s, Reiter 0,15 s, zu 0,12 s). Hier wartet der Kastenblick auf das
     * Fenster, auf den Reiterwechsel und auf das Schliessen und liest nur ein frisches Bild fuer die Zahl; danach gleich der
     * naechste Gegner ueber gegnerliste() (die Liste ist zurueck, wenn der Titel da und das Bild hell ist).
     *
     * Sicher, weil jeder Schritt gepruft wird und bei jeder Abweichung (Fenster nicht gesehen, Reiter nicht gewechselt,
     * Zahl nicht lesbar, Fenster bleibt offen, meine Power fehlt noch) einfach der normale Ablauf (spaehen()) uebernimmt --
     * spaehZeile bleibt dann gesetzt, und spaehen() schliesst das Fenster. Eine Zahl, die genau meine eigene Power ist,
     * gilt als "Reiter hat noch nicht umgeschaltet" und wird nicht uebernommen.
     */
    private Outcome spaehKette(BotContext ctx, RunTracker runs) {
        String wo = ort.de();
        Integer zeile = spaehZeile;
        if (zeile == null || kettenTiefe >= MAX_KETTE
                || !ctx.kastenBekannt(T_TAB_GEGNER) || !ctx.kastenBekannt(T_TAB_ICH)) {
            ctx.sleepUntilChanged(1200);   // wie bisher
            return Outcome.ACTED;
        }
        Optional<Matcher.Hit> reiter = ctx.warteAufKnopf(SPAEH_FENSTER_MS, Matcher.DEFAULT_THRESHOLD, T_TAB_GEGNER, T_TAB_ICH);
        if (reiter.isEmpty()) return Outcome.ACTED;
        if (T_TAB_GEGNER.equals(reiter.get().name())) {
            // "Your squad" ist offen: erst (einmal je Besuch) meine Power lesen, dann auf "Enemy squad" umschalten.
            if (ctx.kastenBlick(Matcher.DEFAULT_THRESHOLD, T_TAB_ICH).isPresent()) return Outcome.ACTED;   // nicht eindeutig
            if (meinePower < 0 && meineLeseVersuche < 3) {
                ctx.sleep(tempo.ms(80));   // das Fenster ist gerade erst aufgegangen
                Mat f = ctx.refresh();
                if (f == null) return Outcome.ACTED;
                meineLeseVersuche++;
                var p = powerLesen(f, ctx);
                if (p.isPresent()) {
                    meinePower = p.getAsLong();
                    Log.info(wo, "Meine Power: " + String.format("%,d", meinePower).replace(',', ' ') + ".");
                }
            }
            // Ohne meine Power waere "Zahl gleich meiner Power" (Reiter noch nicht umgeschaltet) nicht zu erkennen: dann
            // macht spaehen() es wie bisher (liest erst meine Power, dann den Gegner).
            if (meinePower < 0) return Outcome.ACTED;
            ctx.klick(reiter.get(), wo + Sprache.t(": tab \"Enemy squad\"", ": Reiter \"Enemy squad\""));
            if (ctx.warteAufKnopf(SPAEH_FENSTER_MS, Matcher.DEFAULT_THRESHOLD, T_TAB_ICH).isEmpty()) return Outcome.ACTED;
            ctx.sleep(tempo.ms(80));   // die Zahl blendet vielleicht noch ein
        }
        // "Enemy squad" ist offen: die Zahl lesen (bis zu 6 Bilder, eine 0 erst beim zweiten Mal -- wie in spaehen()).
        Mat f = null;
        long wert = -1;
        for (int i = 0; i < 6 && wert < 0; i++) {
            f = ctx.refresh();
            if (f == null) return Outcome.ACTED;
            var p = powerLesen(f, ctx);
            if (p.isEmpty() || (meinePower > 0 && p.getAsLong() == meinePower)) {
                ctx.sleep(tempo.ms(120));
                continue;
            }
            if (p.getAsLong() == 0 && !nullSchonGesehen) {
                nullSchonGesehen = true;
                ctx.sleep(tempo.ms(120));
                continue;
            }
            nullSchonGesehen = false;
            wert = p.getAsLong();
        }
        if (wert < 0) return Outcome.ACTED;   // spaehen() liest weiter
        gegnerPower.put(zeile, wert);
        Log.info(wo, Sprache.t("Enemy in row " + zeile + ": power " + String.format("%,d", wert).replace(',', ' ') + ".",
                "Gegner in Zeile " + zeile + ": Power " + String.format("%,d", wert).replace(',', ' ') + "."));
        // Meine Power fehlt noch? Dann liest spaehen() sie ueber den Reiter "Your squad".
        if (meinePower < 0 && meineLeseVersuche < 3) return Outcome.ACTED;
        if (!ctx.clickIfVisibleIfKnown(f, T_X, wo + Sprache.t(": close the enemy window (only looked at)",
                ": Gegner-Fenster schliessen (nur angesehen)"))) {
            return Outcome.ACTED;
        }
        if (!ctx.warteBisWeg(SPAEH_FENSTER_MS, 0.80, T_TAB_ICH)) return Outcome.ACTED;   // spaehen() schliesst
        spaehZeile = null;
        Mat liste = listeAbwarten(ctx, LISTE_NACH_ERGEBNIS_MS);
        if (liste == null) return Outcome.ACTED;
        kettenTiefe++;
        try {
            return gegnerliste(liste, ctx, runs);
        } finally {
            kettenTiefe--;
        }
    }

    private Outcome spaehSchliessen(Mat screen, BotContext ctx) {
        if (ctx.clickIfVisibleIfKnown(screen, T_X, ort.de() + Sprache.t(": close the enemy window (only looked at)", ": Gegner-Fenster schliessen (nur angesehen)"))) {
            spaehZeile = null;
            ctx.sleepUntilChanged(800);
            return Outcome.ACTED;
        }
        ctx.sleep(tempo.ms(200));
        return Outcome.WAITING;
    }

    /**
     * Steht rechts vom Power-Symbol genau EIN dunkles Zeichen in Ziffergroesse?
     * Gezaehlt werden Spalten mit dunklen Bildpunkten; eine Ziffer ist ein
     * einziger zusammenhaengender Streifen, etwa 0,2- bis 0,8-mal so breit wie
     * das Symbol hoch ist. Leer (Zahl noch nicht da) oder mehrere Zeichen: nein.
     */
    private static boolean einZeichen(java.awt.image.BufferedImage bild, Matcher.Hit h) {
        int x0 = Math.min(bild.getWidth(), h.x() + h.width() + 4);
        int x1 = Math.min(bild.getWidth(), h.x() + h.width() * 5);
        int y0 = Math.max(0, h.y()), y1 = Math.min(bild.getHeight(), h.y() + h.height());
        int streifen = 0, breite = 0, breitester = 0;
        boolean imStreifen = false;
        for (int x = x0; x < x1; x++) {
            boolean dunkel = false;
            for (int y = y0; y < y1 && !dunkel; y++) {
                int rgb = bild.getRGB(x, y);
                if (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255) < 3 * 90) dunkel = true;
            }
            if (dunkel) {
                if (!imStreifen) {
                    streifen++;
                    imStreifen = true;
                    breite = 0;
                }
                breitester = Math.max(breitester, ++breite);
            } else {
                imStreifen = false;
            }
        }
        int hoehe = y1 - y0;
        return streifen == 1 && breitester >= hoehe * 0.2 && breitester <= hoehe * 0.8;
    }

    /** Die Power rechts neben den gekreuzten Schwertern. */
    private static java.util.OptionalLong powerLesen(Mat screen, BotContext ctx) {
        var icon = ctx.matcher().find(screen, T_POWER);
        java.awt.image.BufferedImage bild = ctx.frame();
        if (icon.isEmpty() || bild == null) return java.util.OptionalLong.empty();
        Matcher.Hit h = icon.get();
        var n = ArenaLeser.zahlRechtsVon(bild, h, h.width() * 5);
        boolean ersterNull = n.isPresent() && n.getAsLong() == 0;
        if (n.isEmpty() || n.getAsLong() < 1000) {
            n = Nahrungskisten.zahlVerstaerkt(bild, h.x() + h.width(), h.y() - 4, h.width() * 5, h.height() + 8, false);
        }
        // KAMPFKRAFT 0 (Autor, 28.09.): Das ist kein Lesefehler -- manche Gegner
        // haben wirklich 0 ("Enemy power: 0"), ein leichter Sieg. Gilt nur, wenn
        // BEIDE Leser genau 0 sehen; andere kleine Zahlen bleiben verdaechtig.
        if (ersterNull && n.isPresent() && n.getAsLong() == 0) return java.util.OptionalLong.of(0);
        // Test 28.09., 23:03: Die Texterkennung findet eine EINZELNE Ziffer gar
        // nicht ("0 Zeilen erkannt"). Steht rechts vom Symbol genau ein Zeichen,
        // ist die Kampfkraft einstellig -- praktisch 0, ein leichter Sieg.
        if ((n.isEmpty() || n.getAsLong() < 10) && einZeichen(bild, h)) return java.util.OptionalLong.of(0);
        if (n.isPresent() && (n.getAsLong() < 1000 || n.getAsLong() > 2_000_000_000L)) return java.util.OptionalLong.empty();
        return n;
    }

    /**
     * Wen angreifen? Den SCHWAECHSTEN, der hoechstens "Grenze" mehr Power hat
     * als Autor. Gibt es keinen: kostenloser Refresh (liefert dann null), sonst
     * trotzdem der schwaechste. Unlesbare zaehlen erst, wenn kein bekannter da ist.
     */
    private Matcher.Hit gegnerWaehlen(List<Matcher.Hit> knoepfe, Mat screen, BotContext ctx, long jetzt) {
        String wo = ort.de();
        List<Matcher.Hit> alleBekannt = knoepfe.stream()
                .filter(k -> powerBei(k.centerY()) != null && powerBei(k.centerY()) >= 0)   // 0 = leichter Sieg
                .sorted(Comparator.comparingLong(k -> powerBei(k.centerY())))
                .toList();
        // Gegen wen schon verloren wurde, der kommt erst dran, wenn sonst keiner da ist.
        List<Matcher.Hit> bekannt = alleBekannt.stream()
                .filter(k -> verlorenGegen.stream().noneMatch(v -> Math.abs(v - k.centerY()) <= 15))
                .toList();
        if (bekannt.isEmpty()) bekannt = alleBekannt;
        Matcher.Hit oben = knoepfe.stream().min(Comparator.comparingInt(Matcher.Hit::y)).orElseThrow();
        if (bekannt.isEmpty()) return oben;
        Matcher.Hit schwaechster = bekannt.get(0);
        if (meinePower <= 0) {
            Log.info(wo, Sprache.t("My power is not readable -- going against the weakest enemy.",
                    "Meine Power ist nicht lesbar -- es geht gegen den schwaechsten Gegner."));
            return schwaechster;
        }
        long grenze = kolosseumGrenze();
        String staerken = bekannt.stream().map(k -> String.format("%,d", powerBei(k.centerY())).replace(',', ' '))
                .collect(java.util.stream.Collectors.joining(", "));
        if (powerBei(schwaechster.centerY()) <= meinePower + grenze) {
            Log.info(wo, de.hustlebot.core.Sprache.t("Opponents (weak -> strong): ", "Gegner (schwach -> stark): ") + staerken + de.hustlebot.core.Sprache.t(" | me: ", " | ich: ")
                    + String.format("%,d", meinePower).replace(',', ' ') + de.hustlebot.core.Sprache.t(" -- weakest first.", " -- der schwaechste zuerst."));
            return schwaechster;
        }
        Log.info(wo, Sprache.t("All remaining enemies have more than " + String.format("%,d", grenze).replace(',', ' ') + " power more than me (" + staerken + ").",
                "Alle uebrigen Gegner haben mehr als " + String.format("%,d", grenze).replace(',', ' ')
                + " Power mehr als ich (" + staerken + ")."));
        if (kostenlosRefresh(screen, ctx, jetzt, Sprache.t("all enemies too strong", "alle Gegner zu stark"))) return null;
        Log.info(wo, Sprache.t("No free refresh possible -- going against the weakest anyway (owner's choice).",
                "Kein kostenloser Refresh moeglich -- trotzdem gegen den schwaechsten (Autors Wahl)."));
        return schwaechster;
    }

    /**
     * Refresh -- NUR der kostenlose, hoechstens alle 15 Minuten. Steht ein
     * Diamant im Knopf: nie. Liefert true, wenn gedrueckt wurde.
     */
    private boolean kostenlosRefresh(Mat screen, BotContext ctx, long jetzt, String grund) {
        if (jetzt - letzterRefreshUm < REFRESH_ABSTAND_MS) return false;
        var knopf = ctx.matcher().find(screen, ort.refresh(), Matcher.DEFAULT_THRESHOLD);
        if (knopf.isEmpty() || diamantImKnopf(screen, knopf.get())) return false;
        Matcher.Hit k = knopf.get();
        Log.action("Klick", ort.de() + ": " + grund + " -- Refresh (kostenlos)  [" + k.centerX() + ","
                + k.centerY() + String.format(", Uebereinstimmung %.3f]", k.score()));
        ctx.input().click(k);
        refreshs++;
        letzterRefreshUm = jetzt;
        niederlagenSeitRefresh = 0;
        gegnerPower.clear();   // neue Gegner: die gemerkten Staerken gelten nicht mehr
        verlorenGegen.clear();
        letzteKnopfZahl = 0;
        ctx.sleepUntilChanged(1500);
        return true;
    }

    /** Besuch abbrechen: in 21 Minuten neu versuchen, die Kette laeuft weiter. */
    private Outcome abbrechen(String grund) {
        // Autor 24.10.: ein abgebrochener Besuch ist ein Fehler fuers Tempo (reset() laesst runFehler stehen,
        // also zaehlt er beim naechsten fertigen Besuch mit).
        runFehler++;
        ort.plan().naechsterBesuchIn(Energieplan.NICHT_VOLL_MIN, Sprache.t("Visit aborted (" + grund + ").", "Besuch abgebrochen (" + grund + ")."));
        Log.error(ort.de(), Sprache.t("Visit aborted: " + grund + ".", "Besuch abgebrochen: " + grund + "."));
        return Outcome.FATAL;
    }

    /**
     * Liest die Energie oben in der Gegnerliste ab ("1/5" bis "5/5").
     *
     * Je Zahl eine Vorlage (lh_energie_1..5 bzw. ko_energie_1..5); genommen
     * wird die am besten passende, und nur, wenn sie wirklich gut passt.
     *
     * @return 1..5, 0 wenn keine passt, -1 wenn es noch keine dieser Vorlagen gibt
     */
    private int energieLesen(Mat screen, BotContext ctx) {
        int beste = 0;
        double besterWert = 0;
        boolean irgendeine = false;
        for (int n = 1; n <= MAX_KAEMPFE; n++) {
            String name = ort.energie() + n;
            if (!ctx.matcher().has(name)) continue;
            irgendeine = true;
            var hit = ctx.matcher().find(screen, name, ENERGIE_SCHWELLE);
            if (hit.isPresent() && hit.get().score() > besterWert) {
                besterWert = hit.get().score();
                beste = n;
            }
        }
        if (!irgendeine) return -1;
        Log.debug(ort.de(), "Energie abgelesen: " + beste + "/5"
                + (beste > 0 ? String.format(" (Uebereinstimmung %.2f)", besterWert) : " (keine Zahl passt)"));
        return beste;
    }

    /**
     * Steht im Refresh-Knopf ein blauer Diamant (Preis)? Dann ist er NICHT
     * kostenlos. Der kostenlose Knopf ist rein orange/gelb; schon 1 % kraeftig
     * blaue Flaeche gilt als Diamant.
     */
    static boolean diamantImKnopf(Mat screen, Matcher.Hit knopf) {
        int x = Math.max(0, knopf.x()), y = Math.max(0, knopf.y());
        int w = Math.min(knopf.width(), screen.width() - x), h = Math.min(knopf.height(), screen.height() - y);
        if (w <= 0 || h <= 0) return true;   // im Zweifel: nicht druecken
        Mat hsv = new Mat(), blau = new Mat();
        try {
            org.opencv.imgproc.Imgproc.cvtColor(new Mat(screen, new org.opencv.core.Rect(x, y, w, h)), hsv,
                    org.opencv.imgproc.Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new org.opencv.core.Scalar(95, 120, 120),
                    new org.opencv.core.Scalar(130, 255, 255), blau);
            return Core.countNonZero(blau) / (double) (w * h) > 0.01;
        } finally {
            hsv.release();
            blau.release();
        }
    }

    /** So gut muss eine Energie-Zahl passen. Hoeher als sonst: 1/5 und 4/5 sehen sich aehnlich. */
    private static final double ENERGIE_SCHWELLE = 0.88;

    /** Ist der Blitz auf dem obersten "To battle!" rot? null, wenn kein Knopf zu sehen ist. */
    private Boolean blitzObenRot(Mat screen, BotContext ctx) {
        List<Matcher.Hit> knoepfe = ctx.matcher().findAll(screen, ort.toBattle(), Matcher.DEFAULT_THRESHOLD, 6);
        if (knoepfe.isEmpty()) return null;
        Matcher.Hit oben = knoepfe.stream().min(Comparator.comparingInt(Matcher.Hit::y)).orElseThrow();
        return blitzRot(screen, oben);
    }

    /**
     * Ist der Blitz auf "To battle!" rot? Dann ist keine Energie mehr da.
     *
     * Gemessen an Autors Screenshots (26.09.): Leuchtturm mit Energie 5-14
     * rote Punkte je Knopf (0,1-0,2 %), bei 0/5 87-88 (1,3 %). Im Kolosseum
     * ist der Blitz mit Energie lila, 0 rote Punkte. Grenze: 0,6 % der
     * Knopfflaeche kraeftig rot.
     */
    static boolean blitzRot(Mat screen, Matcher.Hit knopf) {
        int x = Math.max(0, knopf.x()), y = Math.max(0, knopf.y());
        int w = Math.min(knopf.width(), screen.width() - x), h = Math.min(knopf.height(), screen.height() - y);
        if (w <= 0 || h <= 0) return false;
        Mat hsv = new Mat(), rot1 = new Mat(), rot2 = new Mat();
        try {
            org.opencv.imgproc.Imgproc.cvtColor(new Mat(screen, new org.opencv.core.Rect(x, y, w, h)), hsv,
                    org.opencv.imgproc.Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new org.opencv.core.Scalar(0, 120, 120),
                    new org.opencv.core.Scalar(10, 255, 255), rot1);
            Core.inRange(hsv, new org.opencv.core.Scalar(170, 120, 120),
                    new org.opencv.core.Scalar(180, 255, 255), rot2);
            double anteil = (Core.countNonZero(rot1) + Core.countNonZero(rot2)) / (double) (w * h);
            return anteil > 0.006;
        } finally {
            hsv.release();
            rot1.release();
            rot2.release();
        }
    }

    /**
     * Autor 24.10. (Ideenliste A18): Ist "5/5" jetzt schon sicher? Gut gelesen (Vorlage "5" mit mindestens 0,95), dann
     * ENERGIE_FRUEH_PAUSE_MS spaeter auf einem frischen Bild noch einmal -- Titel noch da, wieder "5", Helligkeit gleich,
     * kein Wettbewerbsfenster. Liefert das frische Bild (das erste ist dann ungueltig), sonst null: dann wartet der Aufrufer
     * wie bisher bis TITEL_RUHE_MS. Zu viel gelesen schadet nicht (der rote Blitz stoppt vor jedem "To battle!"); zu wenig
     * wird nie gelesen, denn nur "5" zaehlt hier.
     */
    private Mat energieFrueh(Mat screen, BotContext ctx) {
        String fuenf = ort.energie() + MAX_KAEMPFE;
        if (!ctx.matcher().has(fuenf) || !istFuenf(screen, ctx, fuenf)) return null;
        double h1 = helligkeit(screen);
        ctx.sleep(tempo.ms(ENERGIE_FRUEH_PAUSE_MS));
        Mat f = ctx.refresh();
        if (f == null) return null;
        if (!(ctx.visibleIfKnown(f, ort.titel()) || ctx.visibleIfKnown(f, ort.titel() + "_abend"))) return null;
        if (!istFuenf(f, ctx, fuenf) || !helligkeitRuhig(h1, helligkeit(f))) return null;
        if (ctx.visibleIfKnown(f, T_CLAIM) || ctx.visibleIfKnown(f, T_WETTBEWERB_ZU)) return null;
        return f;
    }

    private static boolean istFuenf(Mat screen, BotContext ctx, String name) {
        var h = ctx.matcher().find(screen, name, ENERGIE_FRUEH_SCHWELLE);
        return h.isPresent() && h.get().score() >= ENERGIE_FRUEH_SCHWELLE;
    }

    /** Zwei Helligkeiten (0-255) gelten als "gleich", wenn sie weniger als RUHE_HELLIGKEIT (Anteil) auseinanderliegen. */
    static boolean helligkeitRuhig(double vorher, double nachher) {
        return vorher > 0 && Math.abs(nachher - vorher) <= vorher * RUHE_HELLIGKEIT;
    }

    /**
     * Autor 24.10. (Ideenliste A18): Auf die Gegnerliste warten, nachdem der Ort (oder im Kolosseum "Colosseum" im
     * Auswahlfenster) angeklickt wurde. Statt zu schlafen und dann das ganze Bild zu durchsuchen (ein Schritt kostet rund
     * 0,35 s, die Liste war oft schon laenger da) schaut der Kastenblick alle paar Millisekunden auf die bekannte Stelle des
     * Titels. Ist keine Stelle bekannt (erster Besuch) oder das Spiel nicht vorn, wird wie bisher geschlafen.
     */
    private void aufListeWarten(BotContext ctx, long maxMs) {
        long von = System.currentTimeMillis();
        boolean da = ctx.warteAufKnopf(maxMs, Matcher.DEFAULT_THRESHOLD, ort.titel(), ort.titel() + "_abend").isPresent();
        if (!da && System.currentTimeMillis() - von < maxMs / 2) ctx.sleep(tempo.ms(250));
    }

    /** Mittlere Helligkeit des Bildes (0-255), um ein darueberliegendes Fenster zu merken. */
    private static double helligkeit(Mat screen) {
        var m = Core.mean(screen).val;
        return (m[0] + m[1] + m[2]) / 3.0;
    }

    /** Steht mitten im Bild die Warnung "Your squad does not have enough units ..."? (Texterkennung, Spiel auf Englisch) */
    private static boolean truppeNichtVoll(BotContext ctx) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return false;
        int w = b.getWidth(), h = b.getHeight();
        String t = ArenaLeser.liesText(b, (int) (w * 0.28), (int) (h * 0.36), (int) (w * 0.44), (int) (h * 0.24)).toLowerCase(java.util.Locale.ROOT);
        return t.contains("units") || t.contains("squad");
    }
}
