package de.hustlebot.bot;

import de.hustlebot.core.Sprache;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dunkle Kathedrale.
 *
 * Der Ablauf einer Reise:
 *
 *   Burg -> Weltkarte -> "Dark Cathedral" -> Participate
 *   -> "Gather troops": Previous squad, Confirm, Attack!
 *   -> Etagenkarte: Wegpunkt waehlen -> Truppen -> Quick Attack -> Victory
 *   -> "Choose a blessing!": mittlere Karte -> Confirm selection
 *   -> ... bis der Etagenwaechter faellt -> "Next floor"
 *   -> nach drei Etagen: "You completed the journey" -> Home
 *
 * Anders als im Verlies spielt man allein: es gibt keinen Mitspieler, kein
 * "Ready" und keine Leiterrolle.
 *
 * Der Weg ueber eine Etage wird NICHT aus festen Mustern abgeleitet. Der
 * Waechter liegt je nach Etage woanders, und manchmal ist er gar nicht dort,
 * wo man ihn vermutet. Deshalb sucht der Bot "Guardian's lair" im Bild und
 * geht jeweils zu dem offenen Wegpunkt, der dem Waechter am naechsten liegt.
 * Das findet den kurzen Weg von selbst und geht nicht kaputt, wenn die Etage
 * anders aussieht als erwartet.
 */
public final class CathedralMode implements GameMode {

    // ---- Einstieg --------------------------------------------------------
    private static final String T_UPDATE       = "update_button";

    /** Das rote runde X der Werbefenster ("Running out of apples?"). */
    private static final String T_AD_CLOSE     = "ad_close";
    private static final String T_MAP_BUTTON   = "map_button";
    /**
     * Der Map-Knopf im neueren Aussehen (bei Autor seit 25.09.). clickAny()
     * fand ihn ueber die Varianten, visibleAny() aber nicht: Die Sicherung
     * "Klick auf Map hat nicht gegriffen, die Burg ist noch da" griff deshalb
     * nie, und der Bot zog zwoelfmal an der Burg statt an der Karte (Test
     * 25.09., 22:23 -- der Klan-Chat hatte den ersten Klick verschluckt).
     */
    private static final String T_MAP_BUTTON_ALT = "map_button_alt";
    private static final String T_NODE         = "dc_node";
    private static final String T_PARTICIPATE  = "dc_participate";

    /**
     * Kennzeichen des VERLIES-Teilnahmebildschirms -- hier also der falsche.
     *
     * Wird mitten im Verlies auf die Kathedrale umgeschaltet, steht der Bot
     * noch vor dem Verlies-Fenster. Das sieht dem eigenen aehnlich genug, dass
     * er sonst darauf herumklickt oder ratlos wartet. Ueber das rote X oben
     * rechts geht es hinaus, danach sucht er die Kathedrale auf der Weltkarte.
     */
    private static final String T_DUNGEON_PARTICIPATE = "participate";
    private static final String T_DUNGEON_TAB         = "tab_torches";
    /** Das rote X oben rechts, mit dem sich ein Fenster schliessen laesst. */
    private static final String T_CLOSE_X             = "close_x";

    // ---- Truppen sammeln -------------------------------------------------
    private static final String T_PREV_SQUAD   = "dc_previous_squad";
    private static final String T_CONFIRM      = "dc_confirm";
    // Ersatz-Confirm: Autor (18.09.) -- der "Confirm" der Kathedrale sieht
    // genauso aus wie der im Verlies. Findet der Bot dc_confirm nicht (die
    // Vorlage passte beim Event nicht), nimmt er den Verlies-"confirm". Der
    // liegt ohnehin im Paket, es muss also niemand etwas neu aufnehmen.
    private static final String T_CONFIRM_ALT  = "confirm";
    /**
     * Der Belohnungs-Bildschirm des Events ("You have received a reward").
     *
     * Autor, 19.09.: Kommt waehrend des Events nach JEDER Kathedrale. Er geht
     * weg, sobald man einmal unter den Text klickt -- ein eigener Knopf ist da
     * nicht. Ohne das bleibt der Bot davor stehen.
     */
    private static final String T_REWARD_TEXT  = "dc_reward_text";
    private static final String T_ATTACK       = "dc_attack";

    // ---- Etagenkarte -----------------------------------------------------
    private static final String T_FLOOR_HINT   = "dc_floor_hint";
    private static final String T_END_JOURNEY  = "end_journey";
    /**
     * "End journey" unten rechts, wie er in der Kathedrale aussieht, wenn die
     * Reise fertig ist.
     *
     * ACHTUNG, das war schon einmal falsch herum: Solange die Reise laeuft, ist
     * der Knopf ROT. Ist alles abgeschlossen, wird er GRUEN. Gebraucht wird
     * hier der gruene -- deshalb kam die rote Vorlage im Protokoll auch nur auf
     * 0,46 und reichte nicht.
     *
     * Eigener Name, weil ein Sammelname auch end_journey_yes erwischen wuerde
     * -- das ist aber die Sicherheitsabfrage danach, nicht der Knopf selbst.
     */
    private static final String T_DC_END_JOURNEY = "dc_end_journey";
    /** Der gruene "End journey" aus dem Verlies. Passt oft auch hier. */
    private static final String T_END_JOURNEY_GREEN = "end_journey_green";
    /** Die Sicherheitsabfrage nach "End journey": das gruene "Yes". */
    private static final String T_END_JOURNEY_YES = "end_journey_yes";
    private static final String T_GUARDIAN     = "dc_guardian";
    /**
     * Der Etagenwaechter der Kathedrale -- eigener Name, weil es
     * "dc_guardian" auch im Verlies-Ordner gibt und der Bot Vorlagen nur
     * beim Dateinamen kennt. Enthaelt Sockel und die Schrift "Guardian's
     * lair": Schrift ist das Zuverlaessigste, was auf einem Bildschirm
     * steht -- harte Kanten, hoher Kontrast, immer gleich.
     */
    private static final String T_GUARDIAN_LAIR = "dc_guardian_lair";

    /** Nur die Schrift "Guardian's lair" -- die sieht ueberall gleich aus. */
    private static final String T_GUARDIAN_TEXT = "dc_guardian_text";
    /** Eigene, niedrigere Schwelle: Treffer ab 0,741, Fehltreffer bis 0,400. */
    private static final double WAECHTER_SCHRIFT_SCHWELLE = 0.60;
    /** So weit ueber der Schrift darf der Sockel hoechstens liegen. */
    private static final int WAECHTER_SOCKEL_MAX_HOCH = 130;
    /** So weit seitlich darf der Sockel von der Schrift abweichen. */
    private static final int WAECHTER_SOCKEL_MAX_SEITLICH = 90;
    /** Ohne erkannten Sockel: so weit ueber der Schrift wird geklickt. */
    private static final int WAECHTER_OHNE_SOCKEL_HOCH = 55;
    private static final String T_QUESTION     = "dc_question";
    private static final String T_NEXT_FLOOR   = "dc_next_floor";
    /**
     * Die Etagenzahl in der Mitte der Karte: auf dem leuchtenden Sockel steht
     * eine 1, 2 oder 3.
     *
     * Das ist die verlaesslichste Auskunft darueber, wie weit die Reise ist --
     * viel besser als Mitzaehlen. Gezaehlt wird ab dem Start des Bots; wird er
     * mitten in einer laufenden Reise angeworfen, steht der Zaehler auf null,
     * obwohl schon zwei Waechter gefallen sind. Die Zahl auf dem Sockel stimmt
     * immer.
     *
     * Reihenfolge = Etage 1, 2, 3.
     */
    private static final String[] STAGE_NUMBERS = {
            "dc_stage_1", "dc_stage_2", "dc_stage_3"
    };
    private static final String T_LOCK         = "lock_icon";

    /**
     * Wegpunkte mit einem Ereignis. Alle freiwillig: fehlt eine Vorlage, wird
     * der Wegpunkt eben nicht angesteuert. Fuer Variante a (schnellster Weg)
     * braucht es keinen davon.
     */
    private static final String[] EVENT_WAYPOINTS = {
            "dc_cube", "dc_skirmish", "dc_minion", "dc_altar",
            "dc_camp", "dc_danger", "dc_ally", "dc_arena"
    };

    /**
     * Wegpunkte, an denen gekaempft werden muss: der normale Boss und das
     * Scharmuetzel. Auf dem schnellen Weg (Variante a) werden sie nur genommen,
     * wenn sie wirklich auf dem Weg zum Waechter liegen.
     */
    //
    // Autor, 19.09. (mit Bild): "nur die minions sind bosse. der etagen
    // waechter ist ja auch eine art boss, undzwar der endboss der einzelnen
    // etagen." Hier standen frueher auch dc_skirmish und dc_arena -- dadurch
    // hielt der Bot "Arena of Torment" fuer einen Boss und lief Umwege, um
    // einen Wuerfel-Stuetzpunkt mitzunehmen, der gar keine Belohnung bringt.
    // Der Waechter steckt NICHT in dieser Liste: er wird getrennt behandelt
    // und kommt immer zuletzt.
    private static final List<String> BATTLE_WAYPOINTS = List.of("dc_minion");

    /**
     * Wegpunkte, an denen überhaupt GEKAEMPFT wird -- nicht nur die Bosse.
     *
     * Wichtig, dass das getrennt bleibt: BATTLE_WAYPOINTS sagt "das ist ein
     * Boss, den will ich haben", diese Liste sagt "da kostet es einen Kampf".
     * Beim Weg zum Waechter soll ein harmloser Trittstein einem Kampf
     * vorgezogen werden -- wuerde man dafuer die Boss-Liste nehmen, hielte der
     * Bot Scharmuetzel und Arena faelschlich fuer harmlos und liefe genau in
     * die Kämpfe hinein, die er bisher umgangen hat.
     */
    private static final List<String> KAMPF_WAYPOINTS =
            List.of("dc_minion", "dc_skirmish", "dc_arena");

    /**
     * So viele Punkte Umweg ist es wert, einen Kampf zu umgehen. Liegt ein
     * harmloser Wegpunkt hoechstens so viel weiter vom Waechter entfernt als
     * der naechstgelegene Kampf, wird der harmlose genommen.
     */
    private static final double BATTLE_DETOUR_PX = 70;

    // ---- Kampf und Truppen ----------------------------------------------
    private static final String T_DEPLOY_SCREEN = "dc_deploy_screen";
    /**
     * "Deploy the best" sieht bei "Gather troops" anders aus als an einem
     * Wegpunkt: dort steht er neben "Previous squad", hier allein in der Mitte.
     * Jede Vorlage ab "dc_deploy_best" zaehlt (dc_deploy_best_alt, _2 ...).
     */
    private static final String T_DEPLOY_BEST   = "dc_deploy_best";
    /**
     * Der Knopf zum Losschlagen. Die Kathedrale hat einen eigenen -- der aus
     * dem Verlies passt nicht -- deshalb werden beide durchprobiert:
     * erst dc_quick_attack, dann quick_attack.
     */
    private static final String T_QUICK_ATTACK    = "dc_quick_attack";
    private static final String T_QUICK_ATTACK_ALT = "quick_attack";
    private static final String T_TO_BATTLE       = "dc_to_battle";
    private static final String T_TO_BATTLE_ALT   = "to_battle";
    private static final String T_HOME          = "home_button";
    /**
     * Karlas Bericht #UTXQ (16.10.): nach einem vollen Kampf ("To battle") steht auf dem Siegbild unten ein
     * gruener "Back" statt "Home" -- den kannte der Bot nicht und stand. Ausgeschnitten aus ihrem Spielbild.
     */
    private static final String T_KAMPF_BACK    = "dc_battle_back";

    // ---- Segen -----------------------------------------------------------
    private static final String T_BLESSING      = "dc_blessing";
    private static final String T_BLESSING_OK   = "dc_blessing_ok";

    // ---- Entscheidungsfelder --------------------------------------------
    private static final String T_MOVE_ON       = "dc_move_on";
    private static final String T_CLAIM_REWARD  = "dc_claim_reward";
    private static final String T_FINISH_SELECT = "dc_finish_selection";
    /**
     * Der gruene "Finish selection" im Warnfenster "Are you sure you want to
     * finish the selection?". Ein eigener Knopf: er sitzt woanders, ist
     * zweizeilig, und der grosse dahinter bleibt zwar sichtbar, laesst sich
     * aber nicht mehr druecken.
     */
    private static final String T_FINISH_YES    = "dc_finish_yes";
    /**
     * Freiwillige, genauere Vorlage fuer denselben Knopf: der gruene
     * "Finish selection" INNERHALB des Warnfensters, eng um die Beschriftung
     * zugeschnitten. Gibt es sie, wird sie bevorzugt. Gibt es sie nicht,
     * findet der Bot den Knopf ueber die Beschriftung des grossen (siehe
     * warningFinishButton).
     */
    private static final String T_FINISH_CONFIRM = "dc_finish_confirm";

    /**
     * Groessen, in denen eine Beschriftung gesucht wird, wenn derselbe Knopf
     * im Bild zweimal in verschiedener Breite vorkommt.
     */
    private static final double[] LABEL_SCALES = {1.00, 0.94, 0.88, 0.82, 0.76};

    /**
     * Der gruene Knopf "Put in the squad" im Fenster "Ally in Reflection".
     *
     * Dort wird eine mystische Kopie einer eigenen Einheit angeboten, die den
     * Rest der Reise mitkaempft. Die wird immer angenommen -- eine Einheit mehr
     * ist immer besser, und es gibt nichts dagegen einzutauschen.
     */
    private static final String T_JOIN_SQUAD    = "dc_join_squad";
    /**
     * Die Ueberschrift "Ally in Reflection" desselben Fensters.
     *
     * Freiwillig, aber nuetzlich als zweites Merkmal: solange sie zu sehen ist,
     * steht das Fenster, und die Etagenkarte dahinter wird nicht angefasst --
     * auch dann nicht, wenn der Knopf gerade einmal nicht erkannt wird.
     */
    private static final String T_ALLY_SCREEN   = "dc_ally_screen";

    // ---- Zwischenfenster -------------------------------------------------
    private static final String T_WAYPOINT_DONE = "dc_waypoint_done";
    private static final String T_CLOSE         = "dc_close";
    private static final String T_YES           = "dc_yes";
    private static final String T_JOURNEY_DONE  = "dc_journey_done";
    /**
     * Der gruene "Home" auf dem Abschlussbildschirm der Kathedrale.
     * Sieht anders aus als der nach einem Kampf, deshalb ein eigener Name.
     */
    private static final String T_HOME_DONE     = "dc_home_done";

    /**
     * Die freie Flaeche unten rechts auf dem Abschlussbildschirm.
     *
     * Ein Klick dorthin ueberspringt die Einblendung der Belohnungen -- das
     * spart bei jedem Durchlauf Zeit. Ist diese Vorlage aufgenommen, klickt
     * der Bot genau dahin, wo sie gefunden wird. Fehlt sie, nimmt er die feste
     * Stelle weiter unten.
     */
    private static final String T_DONE_EMPTY    = "dc_done_empty";

    /**
     * Dieselbe Ecke als Anteil des Fensters -- der Rueckfall, solange
     * dc_done_empty nicht aufgenommen ist. Dort ist nichts, ein Klick geht
     * also ins Leere und nicht auf einen Knopf.
     */
    private static final double DONE_CORNER_X = 0.90;
    private static final double DONE_CORNER_Y = 0.90;

    // ---- Wiederbeleben ---------------------------------------------------
    private static final String T_RESURRECT     = "dc_resurrect";
    private static final String T_NAME_ERSATZ   = DungeonMode.bildOpferName();   // Freunde: unit_1..6 / sacrifice
    private static final String T_UNIT_DEAD_BAR = "unit_dead_bar";
    /** Die Zeile "Switch dwellers" unten auf der offenen Einheitenkarte. */
    private static final String T_UNIT_CARD     = "switch_dwellers";
    /**
     * Die Lebensanzeige auf der Einheitenkarte, wenn sie auf NULL steht.
     * Freiwillig, aber eindeutig: steht dort eine 0, ist die Einheit tot.
     */
    private static final String T_HEALTH_ZERO   = "dc_health_zero";

    /**
     * Freie Stelle rechts neben der Einheitenkarte. Ein Klick dorthin schliesst
     * sie wieder, ohne etwas anderes auszuloesen.
     */
    private static final double CARD_DISMISS_X = 0.83;
    private static final double CARD_DISMISS_Y = 0.50;

    /**
     * So oft darf der Bot je Durchlauf vergeblich eine Wiederbelebung
     * versuchen, bevor er die Einheiten liegen laesst.
     *
     * Vier Einheiten stehen in der Wunschreihenfolge -- ein paar Versuche
     * mehr als das, damit ein einzelner danebengegangener Klick nicht
     * gleich alles abschaltet. Wird die Zahl erreicht, stimmt etwas
     * Grundsaetzliches nicht, und Weiterklicken hilft dann auch nicht.
     */
    private static final int MAX_REVIVE_VERSUCHE = 8;

    /**
     * Reihenfolge beim Wiederbeleben, so wie Autor sie festgelegt hat.
     * Einheiten mit dem Namen "ersatz" werden nie wiederbelebt.
     */
    private static final String[] REVIVE_ORDER =
            DungeonMode.bilderKaempfer().subList(0, 4).toArray(String[]::new);

    /** Fuer die Selbstpruefung beim Paketbau. */
    public static List<String> bilderWiederbeleben() { return List.of(REVIVE_ORDER); }

    /**
     * FREUNDE-FASSUNG (hier wirkungslos, damit die Datei 1:1 uebernommen
     * werden kann): Wann zuletzt aufgestellt wurde. Ohne eigene
     * Kaempferbilder kann squadDeployed() nie "steht schon" sagen -- dann
     * gilt "einmal aufgestellt" fuer AUFGESTELLT_GILT_MS als aufgestellt.
     * Autor hat seine Kaempferbilder, bei ihm greift das nie.
     */
    private long aufgestelltUm = 0;
    private static final long AUFGESTELLT_GILT_MS = 20_000;

    // Hier stand einmal eine feste Klickstelle fuer den "Finish selection" im
    // Warnfenster, fuer den Fall dass die Vorlage dc_finish_yes fehlt.
    //
    // NICHT WIEDER EINBAUEN. Die Stelle lag bei 41 % Breite und 65 % Hoehe --
    // und genau dort sitzt im Altar-Fenster die Reihe der Einheiten. Kam kein
    // Warnfenster, klickte der Bot dort auf eine Einheit, deren Karte ging auf
    // und legte sich ueber alles. Danach erkannte er gar nichts mehr.
    //
    // Ein Blindklick auf eine feste Stelle ist immer eine Wette. Fehlt eine
    // Vorlage, wird gewartet statt geraten.

    /** Die drei Segenskarten liegen nebeneinander; die Mitte wird genommen. */
    private static final double BLESSING_MIDDLE_X = 0.50;
    private static final double BLESSING_MIDDLE_Y = 0.44;

    /** Nach einem Klick auf dieselbe Vorlage wird kurz gewartet. */
    private static final long CLICK_COOLDOWN_MS = 1400;
    /** So lange wird die Etagenkarte nach einem Kampf nicht angefasst. */
    private long nachKampfMs = 200;   // lernwerte.txt
    /** So lange gilt ein angesteuerter Wegpunkt als "schon versucht". */
    private static final long WAYPOINT_COOLDOWN_MS = 6000;
    /** So lange wird der Waechter hoechstens zurueckgestellt, siehe waechterWartetSeit. */
    private static final long WAECHTER_MAX_WARTE_MS = 120_000;
    /**
     * So viele Bilder lang darf die Etagenkarte nach einem Kampf durchscheinen,
     * ohne dass ein Ergebnisfenster kommt. Danach wird sie wieder freigegeben.
     * Rund eine halbe Minute -- lang genug fuer jeden Kampf, kurz genug, dass
     * der Bot nicht ewig steht.
     */
    private static final int FLOOR_MAP_BLOCK_LIMIT = 40;
    /**
     * Bei so vielen Schluesseln geht der Bot Kisten oeffnen.
     *
     * Stand auf 10 -- Autors Ansage vom 6. September: "der bot soll wenn er 0
     * schluessel erkennt in die burg zurueckgehen -> in die tasche -> die
     * cathedral kisten oeffnen wenn welche da sind, die geben wieder
     * schluessel." Also null.
     *
     * Die 10 war ohnehin gefaehrlich: Sie machte aus einem verlesenen Stand
     * sofort ein Ende. Bei null muss sich der Leser schon sehr irren.
     */
    private static final long KEY_FLOOR = 0;
    /**
     * Nach so vielen Etagen ist Schluss. Autors Regel: immer drei.
     *
     * Faellt der Waechter der dritten Etage, wird nicht weitergezogen, sondern
     * unten rechts "End journey" gedrueckt.
     */
    private static final int MAX_FLOORS = 3;
    /**
     * So gut muss der gruene "End journey" passen, damit die Reise als
     * abgeschlossen gilt. Etwas hoeher als sonst, weil eine Verwechslung mit
     * dem roten die Reise mittendrin abbrechen wuerde.
     */
    private static final double END_JOURNEY_MIN_SCORE = 0.88;
    /**
     * Warten, bis der Schluesselzaehler im Teilnahmefenster abgelesen ist.
     *
     * Frueher wurde nach dem Anfordern stur 2000 ms gewartet. Das war viel zu
     * lang: gelesen wird gar nicht waehrend dieser Pause, sondern erst im
     * naechsten Motor-Takt (readEther liest das frische Bild, bevor step wieder
     * dran ist). Die Pause muss also nur reichen, damit das Bild steht -- ein
     * paar hundert Millisekunden. Steht die Zahl dann noch nicht, wird ein paar
     * Takte lang erneut angefordert, statt einmal lange zu warten. Meist steht
     * sie nach dem ersten Versuch, also nach gut 300 statt 2000 ms.
     */
    private static final long COUNTER_READ_SETTLE_MS = 120;
    /** So oft wird der Schluesselstand hoechstens neu angefordert, bevor der Bot ohne ihn weiterspielt. */
    private static final int  COUNTER_READ_MAX_TRIES = 6;
    /** Turbo: So oft wird "Participate" versucht, bevor "Schluessel wohl alle" gilt. */
    private static final int TEILNAHME_MAX_VERSUCHE = 8;
    /** So oft wird die Weltkarte hoechstens verschoben, um die Kathedrale zu finden. */
    private static final int MAX_MAP_SWIPES = 12;

    /** Wie der Bot ueber eine Etage geht. */
    public enum Route {
        // a (kuerzester Weg, normales Tempo) ist am 26.09. entfallen. Autor:
        // "kann man weg a nicht eigentlich loeschen? hat ja keinen Sinn mehr
        // den zu spielen." Ein "a" in Befehlen wird zu c (BotEngine).

        /** b: Erst alle offenen Wegpunkte abraeumen, dann zum Waechter. */
        FULL,
        /**
         * c, der Standard: kuerzester Weg zum Waechter, so schnell wie moeglich. Autor,
         * 17.09., nur fuer seinen Bot: feste Blindklicks auf die immer gleichen
         * Knoepfe (Deploy the best, Confirm, Quick Attack, Segen) ohne aufs
         * Erkennen zu warten, kuerzere Blind-Wartezeit, und Particle of Light
         * wird nicht mehr abgelesen. In der Kathedrale poppt nichts dazwischen,
         * darum ist das gefahrlos -- Autor: "sobald der bot in der cathedrale
         * ist gibt es keine werbefenster mehr die aufpoppen".
         */
        TURBO
    }

    private Route route = Route.TURBO;

    /**
     * Im Turbo: kuerzere Blind-Wartezeit vor dem Hinsehen. Sonst sind es 80
     * Millisekunden (siehe BotContext), rund 200-mal je Durchlauf. 35 reichen
     * -- beendet wird das Warten ohnehin erst, wenn sich das Bild wirklich
     * geaendert hat; frueher hinsehen kann nichts kaputt machen.
     */
    private long turboMinMs = 35;   // lernwerte.txt

    /**
     * Im Turbo zuletzt gesehene Stelle je Knopf ({x, y}). Beim ersten Mal wird
     * der Knopf noch normal gesucht und die Stelle gemerkt; ab dann wird blind
     * daraufgedrueckt, ohne ihn erst zu erkennen. Wird bei jedem reset()
     * geleert, damit jeder frische Lauf neu misst.
     */
    private final Map<String, int[]> turboStelle = new HashMap<>();

    private final Map<String, Long> cooldowns = new HashMap<>();
    /** Stellen, die schon angesteuert wurden, mit Zeitstempel. */
    private final List<long[]> visited = new ArrayList<>();

    private boolean insideCathedral = false;
    private boolean squadPrepared = false;
    /** Von !kisten gesetzt: den Kisten-Gang beim naechsten Schritt sofort starten (Test). */
    private volatile boolean kistenTestGewuenscht = false;
    /** True, sobald auf dem aktuellen Segensfenster eine Karte gewaehlt wurde. */
    private boolean blessingPicked = false;
    /** True, solange das Warnfenster nach "Finish selection" noch zu bestaetigen ist. */
    private boolean finishConfirmPending = false;
    /**
     * True, sobald im Entscheidungsfenster die Wahl angeklickt wurde
     * ("Move on" oder "Claim reward") -- erst danach darf "Finish selection".
     * Faellt erst, wenn das Fenster wirklich weg ist, nicht ueber eine Uhr.
     */
    private boolean choiceMade = false;

    // ------------------------------------------------------------------
    //  Hat die Wahl gegriffen? (27.09., Run 10 und Run 45)
    // ------------------------------------------------------------------
    //
    // Autor, 27.09. mittags: "er will auf finish selection druecken, checkt
    // aber nicht, dass er davor auf den move on knopf druecken muss." Zweimal
    // hing der Bot so, einmal 12 und einmal 22 Minuten. Die Wahl war
    // angeklickt, hatte im Spiel aber nicht gegriffen (einmal direkt nach dem
    // Aufgehen des Fensters, einmal auf "Claim reward" neben der Karte).
    // Das Spiel antwortete auf "Finish selection" nur mit dem Hinweis "You
    // need to select an action to complete a waypoint." -- und der Bot, der
    // die Wahl fuer erledigt hielt, drueckte "Finish selection" 938 Mal.
    //
    // Jetzt: Bleibt "Finish selection" nach dem Druck stehen, ohne dass ein
    // Warnfenster oder "Wegpunkt abgeschlossen" kommt, hat es nicht gegriffen.
    // Beim ersten Mal wird noch einmal abgeschlossen (vielleicht war nur der
    // Druck zu frueh), danach wird die Wahl neu getroffen -- abwechselnd die
    // andere, falls beide zu sehen sind, und mit niedrigerer Schwelle, weil
    // der Hinweis ueber der Karte "Move on" liegt (gemessen 0,77 statt 0,98).
    // Hilft auch das nicht, wird das Fenster mit dem roten X geschlossen.

    /** So viele Nahrungskisten holt der Kreislauf je Gang aus dem Event, dann Kathedrale (Autor, 29.09.). */
    private static final int NAHRUNGSKISTEN_JE_EVENT = 25;
    /** So oft wurde in diesem Auftrag ein fremdes Fenster statt der Weltkarte geschlossen. */
    private int fremdeFensterZu;
    /** Wie oft das Event hintereinander ohne Fass und ohne "Haemmer alle" abbrach. */
    private int eventAbbrueche;
    /** Seit wann der Abschlussbildschirm ohne gruenen "Home" zu sehen ist (0 = nicht). */
    private long abschlussOhneHomeSeit = 0;
    /** Wann in diesem Fenster zuletzt "Finish selection" gedrueckt wurde (0 = noch nicht). */
    private long abschlussGeklicktUm = 0;
    /** So oft hat "Finish selection" in diesem Fenster nicht gegriffen. */
    private int abschlussOhneWirkung = 0;
    /** Welche Wahl zuletzt angeklickt wurde (T_CLAIM_REWARD oder T_MOVE_ON). */
    private String letzteWahl = null;
    /** Wann die Wahl zuletzt angeklickt wurde. */
    private long wahlGeklicktUm = 0;
    /** So lange nach "Finish selection" muss sich etwas tun (gemessen: 100-150 ms). */
    private static final long ABSCHLUSS_PRUEF_MS = 500;
    /** Nach einer neu getroffenen Wahl so lange warten, bevor abgeschlossen wird. */
    private static final long WAHL_WIEDER_PAUSE_MS = 250;
    /**
     * TRAEGHEIT DES SPIELS (Karla 16.10.: "steht ewig lange bei diesem Screen", Altar of Blood): Bei Autor zeigt das
     * Spiel "Quick Attack" ~0,15 s nach dem Aufstellen, bei Karla nach 1,0-1,15 s. Genauso spaet nimmt ihr Spiel die
     * Wahl im Fenster an -- der Bot drueckte "Finish selection" schon nach 0,06 s, wirkungslos, dann Nochmal-Druecken,
     * Neu-Waehlen (das die Wahl wieder wegklickte) und am Ende Fenster schliessen. Gemessen wird darum, wie lange
     * "Quick Attack" auf sich warten laesst (gleitender Mittelwert); um so viel wartet der Bot nach der Wahl, bevor er
     * abschliesst, und so viel laenger gibt er "Finish selection" Zeit. Bei Autor bleibt der Wert nahe 0.
     */
    private long spielTraegheitMs = 0;
    private boolean traegheitGemeldet = false;

    private void traegheitMessen(long ms) {
        long wert = Math.max(0, Math.min(2500, ms));
        spielTraegheitMs = (spielTraegheitMs * 3 + wert) / 4;
        if (spielTraegheitMs >= 400 && !traegheitGemeldet) {
            traegheitGemeldet = true;
            Log.info("Kathedrale", Sprache.t("The game reacts slowly here (buttons come after about " + spielTraegheitMs
                            + " ms) -- the bot waits that much longer in the choice windows.",
                    "Das Spiel reagiert hier langsam (Knoepfe kommen nach etwa " + spielTraegheitMs
                            + " ms) -- der Bot wartet in den Auswahlfenstern entsprechend laenger."));
        }
    }
    /** Ab so vielen Fehlschlaegen wird die Wahl neu getroffen (der erste darf ein zu frueher Druck sein). */
    private static final int WAHL_NEU_AB = 2;
    /** Ab so vielen Fehlschlaegen wird das Fenster mit dem roten X geschlossen. */
    private static final int FENSTER_AUFGEBEN_AB = 6;
    /** Schwelle fuer die Wahl, wenn sie neu getroffen wird (der Hinweis liegt darueber). */
    private static final double WAHL_SCHWACH = 0.70;

    /** Neues Fenster (neuer Wegpunkt): alles zur Wahl vergessen. */
    private void wahlVergessen() {
        abschlussGeklicktUm = 0;
        abschlussOhneWirkung = 0;
        letzteWahl = null;
        wahlGeklicktUm = 0;
    }

    /** In welcher Reihenfolge die Wahl gesucht wird. Nach Fehlschlaegen zuerst die andere. */
    private String[] wahlReihenfolge() {
        if (abschlussOhneWirkung >= WAHL_NEU_AB && T_CLAIM_REWARD.equals(letzteWahl)) {
            return new String[]{T_MOVE_ON, T_CLAIM_REWARD};
        }
        return new String[]{T_CLAIM_REWARD, T_MOVE_ON};
    }

    private static String wahlGrund(String base, boolean nochmal) {
        return (T_CLAIM_REWARD.equals(base)
                        ? Sprache.t("Claim the reward and move on", "Belohnung mitnehmen und weiter")
                        : Sprache.t("Move on without risk", "Weitergehen ohne Risiko"))
                + (nochmal ? Sprache.t(" (once more -- the choice had not registered)",
                                       " (noch einmal -- die Wahl hatte nicht gegriffen)") : "");
    }

    /**
     * "Finish selection" wurde gedrueckt, steht aber nach ABSCHLUSS_PRUEF_MS
     * noch da, und es kam kein Warnfenster: ein Fehlschlag. Zaehlt ihn und
     * sagt, was jetzt zu tun ist.
     *
     * @return 0 = noch warten, 1 = noch einmal abschliessen, 2 = die Wahl neu
     *         treffen, 3 = aufgeben (Fenster schliessen); -1 = kein Fall
     */
    private int abschlussPruefen(long jetzt) {
        if (abschlussGeklicktUm == 0) return -1;
        // Nach jedem Fehlschlag laenger warten (Nachtlauf 29.09., 05:03): Das traege
        // Spiel nahm die Wahl erst nach gut einer halben Sekunde an -- der Bot hatte
        // da schon neu gewaehlt und die Wahl damit wieder weggeklickt. So ging es bis
        // zu 70 s im Kreis. Jetzt: 0,5 s, dann 1 s, 1,5 s ...
        if (jetzt - abschlussGeklicktUm < ABSCHLUSS_PRUEF_MS + spielTraegheitMs + abschlussOhneWirkung * 500L) return 0;
        abschlussGeklicktUm = 0;
        finishConfirmPending = false;
        abschlussOhneWirkung++;
        if (abschlussOhneWirkung >= FENSTER_AUFGEBEN_AB) {
            warnung(Sprache.t(
                    "\"Finish selection\" did not register " + abschlussOhneWirkung + " times. "
                    + "The window is closed and the waypoint is approached again.",
                    "\"Finish selection\" hat " + abschlussOhneWirkung + " Mal nicht "
                    + "gegriffen. Das Fenster wird geschlossen, der Wegpunkt wird neu angesteuert."));
            return 3;
        }
        if (abschlussOhneWirkung >= WAHL_NEU_AB) {
            choiceMade = false;
            warnung(Sprache.t(
                    "\"Finish selection\" did not register (attempt " + abschlussOhneWirkung
                    + ") -- the choice had not reached the game. It is made again.",
                    "\"Finish selection\" hat nicht gegriffen (" + abschlussOhneWirkung
                    + ". Mal) -- die Wahl war im Spiel nicht angekommen. Sie wird neu getroffen."));
            return 2;
        }
        Log.debug("Kathedrale", "\"Finish selection\" hat nicht gegriffen -- noch einmal druecken.");
        return 1;
    }

    /** Letzter Ausweg: das Entscheidungsfenster mit dem roten X schliessen. */
    private boolean wahlFensterSchliessen(BotContext ctx) {
        Optional<Matcher.Hit> x = ctx.kastenBlick(0.84, "dc_close_x");
        if (x.isEmpty()) {
            Mat bild = ctx.refresh();
            if (bild != null && ctx.matcher().has("dc_close_x")) x = ctx.matcher().find(bild, "dc_close_x", 0.84);
        }
        wahlVergessen();
        choiceMade = false;
        finishConfirmPending = false;
        if (x.isEmpty()) return false;
        fensterKlick(ctx, x.get(), Sprache.t("Close the choice window (choice does not register)",
                                             "Entscheidungsfenster schliessen (Wahl greift nicht)"));
        return true;
    }

    /**
     * True vom Losschlagen bis zum bestaetigten Kampfergebnis.
     *
     * Solange wird die Etagenkarte nicht angefasst. Waehrend das Siegesbanner
     * ein- und ausblendet, scheint die Karte dahinter durch: die Wegpunkte
     * werden erkannt, sind aber noch nicht bedienbar. Ohne diesen Schalter
     * klickte der Bot dort auf den naechsten Stuetzpunkt und erst danach auf
     * "Home" -- also in der falschen Reihenfolge.
     */
    private boolean battlePending = false;
    /** Wann der letzte Kampf gestartet wurde -- fuer imKampf(). */
    private long kampfGestartetUm = 0;
    /**
     * So lange nach "Kampf starten" gilt "nichts erkannt" als normal. Ein
     * Kampf samt Siegesbanner dauerte am 25.09. zwischen 1,2 und 2,1 s.
     */
    private static final long KAMPF_RUHE_MS = 8000;
    /**
     * Turbo: Pause zwischen "mittlere Karte" und "Confirm selection". Die
     * Markierung der Karte aendert das Bild kaum, darum wurde bisher immer
     * die volle Zeit gewartet (100 ms). Autor, 25.09.: beim Segen Run fuer
     * Run etwas kuerzen. Kommt Bestaetigen zu frueh, drueckt der Bot im
     * naechsten Bild einfach noch einmal (blessingPicked bleibt gesetzt).
     */
    private long segenWahlMs = 30;   // lernwerte.txt
    /** Fotozyklus: so lange nach "Home" nur nach dem Segen sehen. */
    private static final long SEGEN_KETTE_MS = 1000;
    /** Steht das Segen-Fenster so lange nach "Bestaetigen" noch, wird noch einmal gewaehlt. */
    private static final long SEGEN_NOCHMAL_MS = 450;
    /** Fotozyklus nach Home: so oft nach dem Segen sehen. */
    private long segenTaktMs = 60;   // lernwerte.txt

    // --- Lernmodus (Autor, 26.09.) -------------------------------------
    //
    // Schalter und Zeiten aus lernwerte.txt, bei jedem Run-Start neu
    // gelesen. Alles, was neu und noch nicht erprobt ist, steht dort auf
    // "aus" und wird blockweise eingeschaltet.

    private final Lernwerte lernwerte = new Lernwerte();
    private final Zeitbericht zeit = new Zeitbericht();
    /** Nach dem Segen gleich auf die Etagenkarte sehen. */
    private boolean fotozyklusEtage = false;
    /** Nach dem Wegpunkt gleich nach dem Kampfknopf sehen. */
    private boolean fotozyklusKampf = false;
    /** Die Sieg-Animation antippen, damit "Home" frueher kommt. */
    private boolean siegAntippen = false;
    private long siegTippAbMs = 450;
    /** Tipps im laufenden Kampf (nur der erste kommt ins Protokoll). */
    private int siegTipps = 0;

    /**
     * Liest lernwerte.txt neu (nur wenn sie sich geaendert hat) und
     * uebernimmt die Werte. Steht einmal je Run im Protokoll, wenn sich
     * etwas geaendert hat -- dann ist im Zeitbericht nachvollziehbar, mit
     * welchen Werten welcher Run lief.
     */
    private void lernwerteAnwenden(BotContext ctx) {
        if (!lernwerte.neuLesen()) return;
        ctx.setWarteTakt(lernwerte.zahl("takt_ms"), lernwerte.zahl("blind_ms"));
        turboMinMs = lernwerte.zahl("turbo_min_ms");
        ketteMs = Math.max(20, lernwerte.zahl("kette_ms"));
        segenWahlMs = lernwerte.zahl("segen_wahl_ms");
        segenTaktMs = Math.max(20, lernwerte.zahl("segen_takt_ms"));
        nachKampfMs = lernwerte.zahl("nach_kampf_ms");
        fotozyklusEtage = lernwerte.an("fotozyklus_etage");
        fotozyklusKampf = lernwerte.an("fotozyklus_kampf");
        siegAntippen = lernwerte.an("sieg_antippen");
        siegTippAbMs = lernwerte.zahl("sieg_tipp_ab_ms");
        zweiterBlickMs = lernwerte.zahl("waechter_blick_ms");
        wegplanung = lernwerte.an("wegplanung");
        kastenblick = lernwerte.an("kastenblick");
        aufstellenMs = lernwerte.zahl("aufstellen_ms");
        Log.info("Lernwerte", lernwerte.zeile());
        de.hustlebot.core.Report.merkeZeitbericht("Lernwerte: " + lernwerte.zeile());
    }
    /**
     * Notausgang: Bilder, in denen die Etagenkarte klar zu sehen war, ohne
     * dass ein Kampfergebnis kam. Verhindert, dass ein haengengebliebener
     * Schalter den Bot dauerhaft stehen laesst.
     */
    private int floorMapBlocked = 0;
    /** Stellen, an denen eine Einheit nur schwer verletzt war, nicht tot. */
    private final List<int[]> aliveUnits = new ArrayList<>();
    /** Wo zuletzt eine Einheit angeklickt wurde, deren Karte gerade offen ist. */
    private int[] lastCardAt = null;
    /**
     * Wie oft hintereinander eine "gefallene" Einheit angeklickt wurde, ohne
     * dass je eine Wiederbelebung dabei herauskam.
     *
     * Der Rueckhalt fuer den Fall, dass die Karte gar nicht aufgeht. Die
     * Lebendliste kann dann nichts lernen, weil es nichts zu lesen gibt --
     * und der Bot wuerde ohne diese Zahl bis zum Rundenende weiterklicken.
     * Genau das ist am 4. September passiert, elfmal auf unit_magier2.
     */
    private int reviveVersucheOhneErfolg = 0;
    private int unknownScreens = 0;

    // ------------------------------------------------------------------
    //  Seltene Notfaelle nicht bei jedem Bild pruefen
    // ------------------------------------------------------------------
    /**
     * Ganz oben in step() stehen die Fenster, die alles blockieren koennen:
     * Verbindungsabbruch, Handy-Anmeldung, taegliche Belohnung, Werbung,
     * das Teilnahmefenster des Verlieses. Zusammen sind das gut zehn
     * Vorlagenvergleiche.
     *
     * EIN VERGLEICH KOSTET 8,9 MILLISEKUNDEN. Gemessen an allen 269
     * Vorlagen auf einem Bild in Spielgroesse. Zehn davon sind also
     * neunzig Millisekunden -- bei 209 Schritten je Durchlauf rund
     * neunzehn Sekunden, nur fuer Faelle, die pro Nacht vielleicht einmal
     * eintreten.
     *
     * Deshalb laufen sie jetzt nur noch jedes fuenfte Bild. Das kostet im
     * schlimmsten Fall vier Bilder Verzoegerung, also etwa drei Sekunden --
     * bei einem Fenster, das den Bot sonst minutenlang aufhaelt, faellt das
     * nicht ins Gewicht.
     *
     * UND DER WICHTIGE TEIL: Wird gerade gar nichts erkannt, laufen sie
     * sofort. Genau dann liegt naemlich vermutlich so ein Fenster im Weg.
     * Die Ersparnis faellt also nur an, solange alles glattgeht.
     */
    private static final int SELTEN_TAKT = 5;
    private int schrittZaehler = 0;

    private boolean seltenDran() {
        return unknownScreens > 0 || schrittZaehler % SELTEN_TAKT == 0;
    }

    // ------------------------------------------------------------------
    //  Das leere Ladebild nach Run-Ende und nach "Truppen bestaetigt"
    // ------------------------------------------------------------------
    /**
     * Autor 24.10. (Ideenliste A14, B03, B04): Nach "Home" auf dem Abschlussfenster (Median 3,0 s, Mittel 5,0 s, laengstens 22 s)
     * und nach "Truppen bestaetigt" (rund 4 s) laedt das Spiel -- das Bild ist dabei EINFARBIG LEER (in 46 von 53 Proben des
     * 6-Stunden-Protokolls standen alle Uebereinstimmungen auf 0,000). Der Bot lief in der Zeit die ganze Schrittkette durch
     * (rund 0,3 s je Bild, jedes zehnte Bild gruendlich ueber das ganze Bild, dazu dc_node alle drei Bilder) und meldete alle
     * 12 Bilder "nichts erkannt" -- 53 Fehlalarme in 6 Stunden. Ein leeres Bild kann kein Fenster zeigen: Es wird ein paar
     * Sekunden lang nur still gewartet (billig, ein Bild nach dem anderen), das naechste Bild mit Inhalt geht gleich durch die
     * volle Schrittkette. Nach LADE_ERWARTET_MS gilt wieder alles wie sonst (Warnungen, gruendliche Suche, Notausgang).
     */
    private long ladeErwartetBis = 0;
    private int ladeLeerBilder = 0;
    private static final long LADE_ERWARTET_MS = 15_000;
    /** Ab dieser Streuung der Graustufen (0..255) hat ein Bild Inhalt. Ein leeres Ladebild liegt bei 0, ein Spielbild weit ueber 20. */
    static final double LEER_STREUUNG = 6.0;

    private void ladebildErwarten() {
        ladeErwartetBis = System.currentTimeMillis() + LADE_ERWARTET_MS;
    }

    /** Ist das Bild einfarbig (dunkel oder hell) leer -- ein Ladebild? Kleine Kopie, ein paar Millisekunden. */
    static boolean bildLeer(Mat screen) {
        if (screen == null || screen.empty()) return false;
        Mat klein = new Mat(), grau = new Mat();
        org.opencv.core.MatOfDouble mittel = new org.opencv.core.MatOfDouble(), streuung = new org.opencv.core.MatOfDouble();
        try {
            org.opencv.imgproc.Imgproc.resize(screen, klein, new org.opencv.core.Size(), 0.1, 0.1, org.opencv.imgproc.Imgproc.INTER_AREA);
            if (klein.channels() == 1) {
                grau = klein;
            } else {
                org.opencv.imgproc.Imgproc.cvtColor(klein, grau, klein.channels() == 4
                        ? org.opencv.imgproc.Imgproc.COLOR_BGRA2GRAY : org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
            }
            org.opencv.core.Core.meanStdDev(grau, mittel, streuung);
            return streuung.get(0, 0)[0] < LEER_STREUUNG;
        } catch (Exception e) {
            return false;   // im Zweifel: kein Ladebild, es gilt der normale Ablauf
        } finally {
            klein.release();
            grau.release();
            mittel.release();
            streuung.release();
        }
    }
    private int mapSwipes = 0;
    private boolean searchingCathedral = false;
    /** Wann "Map" gedrueckt wurde -- vorher nicht an der Karte ziehen. */
    private long karteGeoeffnetUm = 0;
    private static final long KARTE_LADEN_MS = 1500;

    /** Wie oft "Map" gedrueckt wurde, ohne dass die Burg verschwunden ist. */
    private int burgStattKarte = 0;
    /** Danach ist Schluss -- dann liegt etwas darueber, das der Bot nicht kennt. */
    private static final int MAX_BURG_STATT_KARTE = 8;
    /**
     * Seit wann der Waechter zurueckgestellt wird, weil noch Fragezeichen offen
     * sind. 0 heisst: wird nicht zurueckgestellt.
     *
     * Ohne diese Bremse kann eine Etage endlos laufen: Ist ein Fragezeichen
     * gar nicht erreichbar, bleibt "noch aufzudecken" fuer immer wahr, der
     * Waechter kommt nie dran -- und weil jeder Schritt runs.progress() ruft,
     * greift auch die Stillstandsuhr nicht. Nach WAECHTER_MAX_WARTE_MS wird
     * der Waechter deshalb trotzdem genommen und die Etage beendet.
     */
    private long waechterWartetSeit = 0;
    // --- Der Schriftzug "You have got a blessing" (27.09.) -----------------
    //
    // Gemessen am Video vom 27.09., 01:20 (20 Bilder je Sekunde): Die
    // Etagenkarte steht 60 ms nach "Segen bestaetigen" vollstaendig da. Aber
    // ueber ihr steigt der Schriftzug "You have got a blessing" langsam nach
    // oben und verdeckt, was gerade unter ihm liegt -- die Schrift
    // "Guardian's lair" oben in der Mitte von 0,7 bis 1,4 s (Guete 0,78 ->
    // 0,38). Der Bot sah genau in dieser Zeit hin (die alte Pruefung "1,2 s
    // spaeter noch einmal"), fand keinen Waechter und nahm irgendeinen
    // Stuetzpunkt: ein Dangerous Game zu viel auf Etage 1 und 2 (Waechter
    // oben). Auf Etage 3 sitzt der Waechter unten, dort passierte nichts.
    //
    // Deshalb: (1) den Waechter je Etage merken, sobald er einmal gut zu sehen
    // war; (2) Endgueltiges -- zum Waechter gehen, Notloesung -- erst nach
    // ZWEI Blicken zu verschiedenen Zeiten entscheiden. Der Schriftzug wandert
    // dazwischen weiter; was beim einen Blick verdeckt war, ist beim anderen
    // frei. Alles Gesehene wird gemerkt.

    /** Seit wann die Etagenkarte wieder zu sehen ist (0 = seit dem letzten Wegpunkt/Segen noch nicht). */
    private long karteDaSeit = 0;
    /** Wann das Bild der laufenden Auswertung geholt wurde. */
    private long karteBildUm = 0;
    /**
     * Der zweite Blick: fruehestens so lange nach dem Erscheinen der Karte
     * (lernwerte.txt: waechter_blick_ms). Autor, 26.09.: "1,2 Sekunden
     * maximal" -- 0,6 s reichen, weil der erste Blick jetzt mitzaehlt.
     */
    private long zweiterBlickMs = 600;
    /** Fehlt etwas schon Gesehenes (Schriftzug davor), hoechstens so lange darauf warten. */
    private static final long VERDECKT_MAX_MS = 2500;
    /** Der Waechter dieser Etage, sobald er einmal gut zu sehen war. Die Karte steht still, er bleibt dort. */
    private Matcher.Hit waechterDieserEtage = null;
    /** Nur ein deutlicher Treffer wird gemerkt (Schrift: Treffer ab 0,74, Fehltreffer bis 0,46). */
    private static final double WAECHTER_MERKEN_AB = 0.70;
    /** Bosse und Fragezeichen, die seit dem Erscheinen der Karte auf irgendeinem Bild zu sehen waren. */
    private final List<Matcher.Hit> gesehenBosse = new ArrayList<>();
    private final List<Matcher.Hit> gesehenFragen = new ArrayList<>();

    private int floorsDone = 0;

    /** Alle geschafften Etagen seit dem Start, ueber alle Durchlaeufe (fuer !stats). */
    private int etagenGesamt = 0;

    // ---- Bosse und Etagenwaechter fuer !stats (Autor, 24.09.) ----------
    //
    // "Daran wuerd ich dann erkennen ob der bot auch wirklich alle bosse
    // besiegt." Gezaehlt wird ueber die Etagenkarte: Jede Boss-Stelle, die
    // der Bot auf einer Etage je gesehen hat, zaehlt als "gesehen". Ist ihr
    // Symbol beim letzten Blick auf die Karte weg, gilt der Boss als besiegt.
    // Ein Etagenwaechter je erreichter Etage; besiegt ist er, wenn es danach
    // auf die naechste Etage geht bzw. die Reise mit allen Etagen endet.
    private int bosseGesehen = 0;
    private int bosseBesiegt = 0;
    private int waechterGesehen = 0;
    private int waechterBesiegt = 0;
    /** Boss-Stellen der laufenden Etage (verschiedene Stellen). */
    private final List<Matcher.Hit> etageBosse = new ArrayList<>();
    /** Wie viele Boss-Symbole beim letzten Blick auf die Karte noch da waren. */
    private int bosseNochDa = 0;
    /** Wurde die laufende Etage schon auf der Karte gesehen? */
    private boolean etageBeobachtet = false;

    /** Auf jeder Etagenkarte: Boss-Stellen merken, neue Etage zaehlen. */
    private void etageBeobachten(Mat screen, BotContext ctx) {
        if (!etageBeobachtet) {
            etageBeobachtet = true;
            waechterGesehen++;
        }
        List<Matcher.Hit> jetzt = alleBosse(screen, ctx);
        for (Matcher.Hit b : jetzt) merkeKnoten(etageBosse, b);
        bosseNochDa = jetzt.size();
    }

    /** Die Etage ist vorbei: Bosse und Waechter verbuchen. */
    private void etageAbschliessen(boolean waechterGeschafft) {
        if (!etageBeobachtet) return;
        bosseGesehen += etageBosse.size();
        bosseBesiegt += Math.max(0, etageBosse.size() - bosseNochDa);
        if (waechterGeschafft) waechterBesiegt++;
        if (bosseNochDa > 0) {
            Log.info("Kathedrale", Sprache.t(
                    "Floor left, " + bosseNochDa + " of " + etageBosse.size() + " boss(es) still on the map.",
                    "Etage verlassen, " + bosseNochDa + " von "
                    + etageBosse.size() + " Boss(en) noch auf der Karte."));
        }
        Log.info("Kathedrale", Sprache.t(
                "Floor done: bosses " + (etageBosse.size() - bosseNochDa)
                + " of " + etageBosse.size() + " defeated, guardian "
                + (waechterGeschafft ? "defeated" : "not defeated")
                + " -- total bosses " + bosseBesiegt + "/" + bosseGesehen
                + ", guardians " + waechterBesiegt + "/" + waechterGesehen + ".",
                "Etage fertig: Bosse " + (etageBosse.size() - bosseNochDa)
                + " von " + etageBosse.size() + " besiegt, Waechter "
                + (waechterGeschafft ? "besiegt" : "nicht besiegt")
                + " -- gesamt Bosse " + bosseBesiegt + "/" + bosseGesehen
                + ", Waechter " + waechterBesiegt + "/" + waechterGesehen + "."));
        etageBosse.clear();
        bosseNochDa = 0;
        etageBeobachtet = false;
    }
    /**
     * Die zuletzt auf dem Sockel abgelesene Etage: 1, 2 oder 3.
     * 0 heisst "noch nicht gelesen" -- dann zaehlt ersatzweise floorsDone.
     */
    private int currentStage = 0;
    /**
     * True, sobald auf dem Abschlussbildschirm einmal in die freie Ecke
     * geklickt wurde. Faellt erst, wenn das Fenster wirklich weg ist.
     */
    private boolean cornerTapped = false;
    /**
     * True, sobald auf dem Teilnahmebildschirm einmal fuer das Ablesen des
     * Schluesselstandes gewartet wurde. Faellt, wenn der Bildschirm weg ist --
     * ein Schalter, keine Uhr.
     */
    private boolean participationRead = false;
    /** Wie oft der Schluesselstand auf diesem Teilnahmebildschirm schon neu angefordert wurde. */
    private int ableseVersuche = 0;
    /** Turbo: Anlaeufe auf "Participate", die zu keiner Reise gefuehrt haben. */
    private int teilnahmeVersuche = 0;
    /** Wie oft schon "Teilnahme haengt" erholt wurde (Fenster geschlossen), ohne dass eine Reise begann. */
    private int teilnahmeErholungen = 0;
    /** Nach so vielen ergebnislosen Erholungen endet der Kathedralen-Auftrag (der Bot spielt dann den naechsten Modus). */
    private static final int TEILNAHME_ERHOLUNGEN = 2;

    /** Was tun, wenn "Participate" schon oft gedrueckt wurde? */
    enum TeilnahmeLage { WEITER, SCHLUESSEL_ALLE, ERHOLEN, ENDE }

    /**
     * Entscheidet nach {@code versuche} Klicks auf "Participate" ohne Reise.
     *
     * WARUM (Bugjagd 29.10., Fehlerberichte W7E9/7NRP/FK7G/G3YH von ben, 27.10.): nach einem Captcha drueckte der Turbo
     * 8562 Mal "Participate" (03:35-04:23), dann noch drei Mal je ~6150 Mal -- ueber drei Stunden, bei 129 Schluesseln. Die
     * Grenze (8 Anlaeufe) wurde nur geprueft, wenn der Knopf NICHT zu sehen war; stand er da, wurde bei jedem Klick "Teilnahme
     * bestaetigt" gemeldet (das setzt die Haenger-Uhr zurueck) und es ging ewig weiter. Jetzt wird vor jedem Klick geprueft:
     * sind die Schluessel nicht alle (bekannt > 0), liegt etwas im Weg -- zweimal erholen (Fenster mit dem roten X schliessen),
     * dann den Auftrag beenden statt ins Leere zu klicken. Ohne bekannten Schluesselstand oder bei 0: wie bisher "vermutlich alle".
     */
    static TeilnahmeLage teilnahmeLage(int versuche, long schluessel, int erholungen) {
        if (versuche < TEILNAHME_MAX_VERSUCHE) return TeilnahmeLage.WEITER;
        if (schluessel > KEY_FLOOR) return erholungen < TEILNAHME_ERHOLUNGEN ? TeilnahmeLage.ERHOLEN : TeilnahmeLage.ENDE;
        return TeilnahmeLage.SCHLUESSEL_ALLE;
    }

    /** null = weiter wie gewohnt; sonst das Ergebnis des Schritts (Schluessel-Kisten, Erholen oder Ende). */
    private Outcome teilnahmeStau(Mat screen, BotContext ctx, RunTracker runs) {
        long keys = ctx.laufstatistik().keysNow();
        switch (teilnahmeLage(teilnahmeVersuche, keys, teilnahmeErholungen)) {
            case SCHLUESSEL_ALLE -> {
                // Rueckfall, falls das Ablesen nichts geliefert hat: Kommt nach mehreren Anlaeufen keine Reise zustande,
                // sind vermutlich die Schluessel alle (Autor, 23.09., Punkt 11).
                teilnahmeVersuche = 0;
                Log.info("Kathedrale", Sprache.t(
                        "No journey after " + TEILNAHME_MAX_VERSUCHE + " attempts -- probably out of keys.",
                        "Keine Reise nach " + TEILNAHME_MAX_VERSUCHE
                        + " Anlaeufen -- vermutlich Schluessel alle."));
                return schluesselAlle(ctx, runs, keys >= 0 ? keys : -1);
            }
            case ERHOLEN -> {
                teilnahmeVersuche = 0;
                // Bericht UZ4S (Bugjagd 29.10.): leere Truppe -- das Spiel zeigt "Move your troops to the Barracks!" und startet nichts.
                if (LeereTruppe.sichtbar(ctx)) {
                    teilnahmeErholungen = 0;
                    endeHinweis = Sprache.t(
                            "The squad is empty (\"Move your troops to the Barracks!\") -- the cathedral job stopped.",
                            "Die Truppe ist leer (\"Move your troops to the Barracks!\") -- der Kathedralen-Auftrag wurde beendet.");
                    warnung(Sprache.t(
                            "The game says \"Move your troops to the Barracks!\" -- the squad is empty (troops are away, e.g. in the clan war). "
                            + "The cathedral job ends here instead of clicking into the void.",
                            "Das Spiel sagt \"Move your troops to the Barracks!\" -- die Truppe ist leer (Einheiten sind woanders, z. B. im Clan-Krieg). "
                            + "Der Kathedralen-Auftrag endet hier, statt ins Leere zu klicken."));
                    return Outcome.FINISHED;
                }
                teilnahmeErholungen++;
                warnung(Sprache.t(
                        "\"Participate\" was pressed " + TEILNAHME_MAX_VERSUCHE + " times, but no journey began although " + keys
                        + " keys are left (recovery " + teilnahmeErholungen + " of " + TEILNAHME_ERHOLUNGEN + "). Closing what is in the way.",
                        "\"Participate\" wurde " + TEILNAHME_MAX_VERSUCHE + " Mal gedrueckt, aber es begann keine Reise, obwohl noch " + keys
                        + " Schluessel da sind (Erholung " + teilnahmeErholungen + " von " + TEILNAHME_ERHOLUNGEN + "). Was im Weg liegt, wird geschlossen."));
                if (!Stoerfenster.notausgang(screen, ctx, "Kathedrale", Stoerfenster.NOTAUSGANG_AB)) ctx.sleep(1500);
                return Outcome.ACTED;
            }
            case ENDE -> {
                teilnahmeVersuche = 0;
                teilnahmeErholungen = 0;
                endeHinweis = Sprache.t(
                        "\"Participate\" did not start a journey although keys are left -- the cathedral job stopped.",
                        "\"Participate\" startete keine Reise, obwohl Schluessel da sind -- der Kathedralen-Auftrag wurde beendet.");
                warnung(Sprache.t(
                        "No journey began after repeated \"Participate\" presses and recoveries (" + keys + " keys left). "
                        + "The cathedral job ends here instead of clicking into the void.",
                        "Trotz wiederholtem \"Participate\" und Erholen begann keine Reise (" + keys + " Schluessel uebrig). "
                        + "Der Kathedralen-Auftrag endet hier, statt ins Leere zu klicken."));
                return Outcome.FINISHED;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Womit bezahlt wird (Teil A, 28.09.): Schluessel, Nahrung, erst Schluessel
     * dann Nahrung, oder zusaetzlich Nahrungskisten -- siehe core.Bezahlung.
     */
    private final Bezahlkasse kasse = new Bezahlkasse("cathedral", "dc_for_food", "dc_for_food_off",
            "dc_for_keys_off", "dc_apfel_knopf", T_NODE, "dc_close_x");

    /**
     * Event-Automatik (Autor, 28.09.): keine Schluessel und keine Kisten mehr
     * -> ins Hammer-Event, alle Haemmer benutzen, die Schluessel-Kisten daraus
     * oeffnen und weiter Kathedrale. Siehe core.Eventautomatik.
     */
    private final EventMode hammerEvent = new EventMode();
    /** Seit 10.10. (Hauptbot 09.10.): statt des Hammer-Events die Titan-Grabung (Eventautomatik.titan()). */
    private final TitanEventMode titanEvent = new TitanEventMode();
    private boolean titanJetzt;
    /** Eigener Zaehler fuers Event -- dessen "Feld geschafft" ist kein Kathedralen-Run. */
    private RunTracker eventZaehler = new RunTracker(9999);
    /** Event-Stages, als der Motor zum ersten Mal nach einem Wechsel fragte (-1 = keine Frage offen). */
    private int stagesBeiWechselfrage = -1;
    /** Fertige Event-Stages in diesem Auftrag (zaehlt nur hoch) -- sichere Stellen fuer den Spiel-Neustart. */
    private int stagesGesamt, stagesZuletzt;

    @Override
    public int sicherePunkte() {
        return stagesGesamt;
    }

    /**
     * Im Kreislauf-Event nach der naechsten fertigen Stage wechseln (Clan-Krieg,
     * Leuchtturm ...) statt 15 Minuten zu warten (Nachtlauf 29.09.). Der Wechsel
     * mitten aus dem Event klappt: Die anderen Modi schliessen das Event-Fenster
     * mit dem roten X (getestet 29.09., 08:25, Leuchtturm).
     */
    @Override
    public boolean wechselJetzt() {
        if (!eventLaeuft) {
            stagesBeiWechselfrage = -1;
            return false;
        }
        // Hauptbot 10.10., 18:01: beim Graben gibt es keine "Stage" -- die Grabung macht das Event selbst sauber zu.
        if (titanJetzt) return titanEvent.wechselJetzt();
        int jetzt = eventZaehler.completedRuns();
        if (stagesBeiWechselfrage < 0) {
            stagesBeiWechselfrage = jetzt;
            return false;
        }
        if (jetzt > stagesBeiWechselfrage) {
            stagesBeiWechselfrage = -1;
            Log.info("Kathedrale", Sprache.t(
                    "Event stage done -- switching now (the interlude does not wait 15 minutes).",
                    "Event-Stage fertig -- jetzt wird gewechselt (Einschub wartet nicht 15 Minuten)."));
            return true;
        }
        return false;
    }
    /** Auf dem Weg in die Burg, um von dort ins Event zu gehen. */
    private boolean eventUnterwegs;
    private boolean eventLaeuft;
    /** Das Event hat nichts mehr gebracht (keine Haemmer) -- nicht noch einmal hin. */
    private boolean eventLeer;
    private int eventWeg, eventGaenge;
    /** Kreislauf durch: danach die Verlies-Runs (an die Warteschlange, ueber den Motor). */
    private Folge folge;

    @Override
    public Folge folgeAuftrag() {
        return folge;
    }
    /** True, sobald zu wenige Schluessel da sind. Die laufende Reise wird noch beendet. */
    private boolean keysExhausted = false;
    /** Wie viele Runs gespielt waren, als das Event zuletzt leer/unerreichbar war (-1 = nie). */
    private int runsBeiEventLeer = -1;

    /** Holt Nachschub aus der Tasche, wenn die Schluessel ausgehen. */
    private final Truhenoeffner truhen = new Truhenoeffner();

    /**
     * Wie oft der Bot schon Kisten geoeffnet hat, weil die Schluessel alle
     * waren.
     *
     * Begrenzt, damit aus "Kisten leer, Schluessel leer" keine Endlosschleife
     * wird: Kisten oeffnen -> immer noch keine Schluessel -> Kisten oeffnen.
     */
    private int truhenGaenge = 0;

    /** Hoechstens so viele Kisten-Gaenge je Sitzung. */
    private static final int MAX_TRUHEN_GAENGE = 50;   // 07.10.: viele Schluesselkisten -- Bremse bleibt "Tasche leer"

    /**
     * Hat der letzte Kisten-Gang eine leere Tasche vorgefunden (nichts
     * geoeffnet, ordentlich mit dem X geschlossen)? Dann bringt ein weiterer
     * Gang nichts -- bei 0 Schluesseln ist Schluss.
     */
    private boolean letzterGangLeer = false;

    /** Grund fuer Discord, wenn der Modus von sich aus aufhoert (endeHinweis). */
    private String endeHinweis = null;

    @Override
    public String endeHinweis() {
        return endeHinweis;
    }

    /**
     * Die Schluessel sind alle -- in allen drei Wegen dieselbe Antwort.
     *
     * Erst die Kathedralen-Kisten in der Tasche oeffnen, die liefern
     * Schluessel nach. Ist die Tasche schon leer (letzter Gang hat nichts
     * gefunden) oder sind die Gaenge aufgebraucht: keine neue Reise mehr,
     * und Discord bekommt Bescheid. Laeuft danach noch etwas aus der
     * Warteschlange, macht der Motor damit weiter (Autor, 25.09.).
     *
     * @param keys der abgelesene Stand, -1 wenn nur vermutet
     */
    private Outcome schluesselAlle(BotContext ctx, RunTracker runs, long keys) {
        // Teil A (28.09.): bei "erst Schluessel, dann Nahrung" und "alles" geht
        // es mit Nahrung weiter -- statt die Schluessel-Kisten zu oeffnen.
        if (kasse.ticketsAlle()) {
            leerGesehen = 0;
            turboNullGesehen = 0;
            teilnahmeVersuche = 0;
            runs.progress(Sprache.t("Out of keys -- continuing with food", "Schluessel alle -- weiter mit Nahrung"));
            return Outcome.ACTED;
        }
        // Im Kreislauf bringt jeder Kathedralen-Run neue Kisten -- die Grenze von
        // drei Gaengen je Auftrag gilt dort nicht (endet sonst zu frueh im Event).
        int maxGaenge = de.hustlebot.core.Eventautomatik.istAn() ? 50 : MAX_TRUHEN_GAENGE;
        if (!letzterGangLeer && truhenGaenge < maxGaenge && truhen.einsatzbereit(ctx)) {
            truhenGaenge++;
            Log.info("Kathedrale", Sprache.t(
                    (keys == 0 ? "0 keys read." : "Probably out of keys.") + " Opening the chests in the bag first.",
                    (keys == 0 ? "0 Schluessel abgelesen." : "Vermutlich keine "
                    + "Schluessel mehr.") + " Erst die Kisten in der Tasche oeffnen."));
            // Im Kreislauf mit Haemmern: hoechstens 9 Kisten bleiben liegen, erst ins Event.
            // Freunde, 10.10.: die Zauberstab-Grenze gilt nur fuers Hammer-Event (auch hier und unten).
            truhen.starten(ctx, de.hustlebot.core.Eventautomatik.istAn() && !eventLeer
                    && (de.hustlebot.core.Eventautomatik.titan()
                        || !de.hustlebot.core.Zauberstabziel.erreicht()));
            runs.progress(Sprache.t("Out of keys -- opening the chests in the bag", "Schluessel alle -- Kisten in der Tasche oeffnen"));
            return Outcome.ACTED;
        }
        // Event-Automatik: keine Kisten mehr -> ins Hammer-Event (Autor, 28.09.).
        if (de.hustlebot.core.Eventautomatik.istAn() && !eventLeer
                && (de.hustlebot.core.Eventautomatik.titan()
                    || !de.hustlebot.core.Zauberstabziel.erreicht())) {
            eventGaenge++;
            eventUnterwegs = true;
            eventWeg = 0;
            // Kommt jetzt ein Einschub (Leuchtturm, Kolosseum, Clan-Krieg), geht es danach hierher zurueck.
            de.hustlebot.core.Eventautomatik.eventZuerstMerken();
            Log.info("Kathedrale", Sprache.t(
                    "No keys and no cathedral chests left -- off to the "
                    + (de.hustlebot.core.Eventautomatik.titan() ? "Titan Event" : "Hammer Event")
                    + " (event automation, trip " + eventGaenge + ").",
                    "Keine Schluessel und keine Kathedralen-Kisten mehr -- ab ins "
                    + (de.hustlebot.core.Eventautomatik.titan() ? "Titan-Event" : "Hammer-Event")
                    + " (Event-Automatik, Gang " + eventGaenge + ")."));
            runs.progress(de.hustlebot.core.Eventautomatik.titan()
                    ? Sprache.t("Out of keys and chests -- off to the Titan Event", "Schluessel und Kisten alle -- ins Titan-Event")
                    : Sprache.t("Out of keys and chests -- off to the Hammer Event", "Schluessel und Kisten alle -- ins Hammer-Event"));
            return Outcome.ACTED;
        }
        keysExhausted = true;
        if (de.hustlebot.core.Eventautomatik.istAn()) {
            // KEINE Verlies-Runs mehr (Autor, 30.09.). War das Event leer, wurden seither aber
            // Runs gespielt, noch einmal ins Event sehen -- die Haemmer kommen nach.
            if (eventLeer && runs.completedRuns() > runsBeiEventLeer
                    && (de.hustlebot.core.Eventautomatik.titan()
                        || !de.hustlebot.core.Zauberstabziel.erreicht())) {
                eventLeer = false;
                keysExhausted = false;
                Log.info("Kathedrale", Sprache.t(
                        "Everything used up again -- taking another look at the "
                        + (de.hustlebot.core.Eventautomatik.titan() ? "Titan Event" : "Hammer Event")
                        + " (no dungeon runs).",
                        "Wieder alles aufgebraucht -- noch einmal ins "
                        + (de.hustlebot.core.Eventautomatik.titan() ? "Titan-Event" : "Hammer-Event")
                        + " sehen (keine Verlies-Runs)."));
                return Outcome.ACTED;
            }
            // Freunde: Sind Verlies-Runs am Ende eingestellt (Start-Formular, !settings eventautomatik 25),
            // kommen sie jetzt -- danach geht der Kreislauf wie bisher weiter (Event, Kathedrale).
            int verliesRuns = de.hustlebot.core.Eventautomatik.verliesRuns();
            if (verliesRuns > 0) {
                folge = new Folge("dungeon", verliesRuns);
                endeHinweis = de.hustlebot.core.Sprache.t(
                        "No keys, no food, no chests and no hammers left. Now " + verliesRuns
                        + " dungeon runs (event automation), then the loop continues.",
                        "Keine Schlüssel, keine Nahrung, keine Kisten und keine Hämmer mehr. Jetzt " + verliesRuns
                        + " Verlies-Runs (Event-Automatik), danach geht der Kreislauf weiter.");
                Log.info("Kathedrale", endeHinweis);
                return Outcome.FINISHED;
            }
            // Seit dem letzten leeren Event kein einziger Run: es ist wirklich nichts mehr da.
            endeHinweis = de.hustlebot.core.Sprache.t(
                    "No keys, no food, no chests and no hammers left -- the loop has ended. No dungeon runs are played.",
                    "Keine Schlüssel, keine Nahrung, keine Kisten und keine Hämmer mehr -- der Kreislauf "
                    + "ist zu Ende. Es werden keine Verlies-Runs gespielt.");
            Log.info("Kathedrale", endeHinweis);
            return Outcome.FINISHED;
        }
        endeHinweis = letzterGangLeer
                ? Sprache.t("No keys left, and there are no cathedral chests left in the bag either. "
                            + "No new cathedral is started.",
                            "Keine Schluessel mehr, und in der Tasche sind auch keine "
                            + "Kathedralen-Kisten mehr. Es wird keine neue Kathedrale begonnen.")
                : Sprache.t("No keys left -- the chests in the bag did not yield any more either. "
                            + "No new cathedral is started.",
                            "Keine Schluessel mehr -- auch die Kisten in der Tasche haben keine mehr "
                            + "gebracht. Es wird keine neue Kathedrale begonnen.");
        warnung(endeHinweis);
        return Outcome.FINISHED;
    }

    /**
     * Wie oft hintereinander ein zu niedriger Schluesselstand gesehen wurde.
     *
     * WOZU DAS ZAEHLEN. Am 6. September las der Bot 140 Schluessel als 10,
     * hielt sich fuer leer und fuhr den Rechner herunter. Der Zahlenleser ist
     * seither vorsichtiger, aber ganz sicher ist kein Ablesen. Deshalb wird
     * ein leerer Stand nicht mehr beim ersten Mal geglaubt: Erst wenn er auf
     * zwei verschiedenen Teilnahmebildschirmen hintereinander dasteht, wird
     * gehandelt. Zweimal falsch hintereinander ist um Groessenordnungen
     * unwahrscheinlicher als einmal.
     */
    private int leerGesehen = 0;

    public void setRoute(Route route) {
        this.route = route == null ? Route.TURBO : route;
    }

    public Route route() {
        return route;
    }

    /**
     * Loest den Kisten-Gang beim naechsten Schritt aus, auch wenn noch
     * Schluessel da sind (Test ueber !kisten). Wirkt nur, wenn die Kathedrale
     * gerade der laufende Modus ist.
     */
    public void kistenGangAusloesen() {
        kistenTestGewuenscht = true;
    }

    /** Nur den Kisten-Gang laufen lassen, ohne Reise (vor dem Start setzen). */
    private volatile boolean nurKisten = false;
    private boolean nurKistenGestartet = false;
    /** Gilt nur fuer den naechsten Start; reset() macht daraus nurKisten. */
    private volatile boolean nurKistenAngefordert = false;

    public void nurKistenGang() {
        nurKistenAngefordert = true;
    }

    /** Nimmt einen nicht gestarteten Nur-Kisten-Wunsch zurueck. */
    public void nurKistenVergessen() {
        nurKistenAngefordert = false;
        nurKisten = false;
    }

    @Override
    public String id() {
        return "cathedral";
    }

    @Override
    public String displayName() {
        return Sprache.t("Dark Cathedral", "Dunkle Kathedrale");
    }

    /** Der Belohnungs-Text des Events -- fuer das Aufnahme-Werkzeug. */
    public static String bildBelohnungText() { return T_REWARD_TEXT; }

    @Override
    public List<String> requiredTemplates() {
        return List.of(
                T_MAP_BUTTON, T_NODE, T_PARTICIPATE,
                T_PREV_SQUAD, T_CONFIRM, T_ATTACK, T_YES,
                T_DEPLOY_SCREEN, T_FLOOR_HINT, T_GUARDIAN, T_NEXT_FLOOR,
                T_BLESSING, T_BLESSING_OK,
                T_WAYPOINT_DONE, T_CLOSE, T_JOURNEY_DONE,
                T_HOME);
    }

    /** Nicht zwingend, wird aber genutzt, wenn vorhanden. */
    public static List<String> optionalTemplates() {
        List<String> list = new ArrayList<>(List.of(
                T_AD_CLOSE,
                T_MOVE_ON, T_CLAIM_REWARD, T_FINISH_SELECT, T_FINISH_YES,
                T_FINISH_CONFIRM, T_JOIN_SQUAD, T_ALLY_SCREEN, T_RESURRECT,
                T_DC_END_JOURNEY, T_END_JOURNEY_GREEN, T_END_JOURNEY_YES,
                T_HOME_DONE, T_DONE_EMPTY,
                T_QUESTION, T_DEPLOY_BEST, T_DEPLOY_BEST + "_alt", T_LOCK,
                T_UNIT_CARD, T_HEALTH_ZERO,
                T_QUICK_ATTACK, T_QUICK_ATTACK_ALT, T_TO_BATTLE, T_TO_BATTLE_ALT,
                "dc_key_icon", "dc_light_icon", "dc_light_icon_alt", T_RESURRECT + "_alt"));
        list.addAll(Arrays.asList(EVENT_WAYPOINTS));
        list.addAll(Arrays.asList(STAGE_NUMBERS));
        list.addAll(Arrays.asList(REVIVE_ORDER));
        for (int i = 0; i <= 9; i++) list.add("key_" + i);
        return list;
    }

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> extra = new LinkedHashMap<>();

        // Autor, 23.09. (Screenshot 21.0): "Floors cleared" und "Current
        // floor" sagten dasselbe -- beide zaehlten nur den laufenden
        // Durchlauf. Jetzt: EINE Zeile fuer die Etage, auf der der Bot gerade
        // ist, und daneben die Summe ueber alle Durchlaeufe.
        int etage = currentStage > 0 ? currentStage : Math.min(MAX_FLOORS, floorsDone + 1);
        extra.put(Sprache.t("Floor right now", "Etage gerade"),
                insideCathedral
                        ? etage + Sprache.t(" of ", " von ") + MAX_FLOORS
                        : Sprache.t("not in the cathedral", "nicht in der Kathedrale"));

        // Autor, 24.09.: statt "Etagen geschafft" lieber Bosse und
        // Etagenwaechter -- "besiegt von gesehen", damit man sieht, ob einer
        // ausgelassen wurde. Die laufende Etage zaehlt schon mit.
        int bG = bosseGesehen + etageBosse.size();
        int bB = bosseBesiegt + Math.max(0, etageBosse.size() - bosseNochDa);
        String besiegt = Sprache.t(" defeated", " besiegt");
        extra.put(Sprache.t("Bosses (all runs)", "Bosse (alle Runs)"),
                bB + Sprache.t(" of ", " von ") + bG + besiegt);
        extra.put(Sprache.t("Floor guardians (all runs)", "Etagenwaechter (alle Runs)"),
                waechterBesiegt + Sprache.t(" of ", " von ") + waechterGesehen + besiegt);

        extra.put(Sprache.t("Route", "Weg"), routeAnzeige());
        return extra;
    }

    /** Der eingestellte Weg in Worten -- fuer Meldungen und die Statuskarte. */
    private String routeAnzeige() {
        return switch (route) {
            case FULL  -> Sprache.t("b – take every waypoint", "b – alle Wegpunkte mitnehmen");
            case TURBO -> Sprache.t("c – as fast as possible (turbo)",
                                    "c – so schnell wie moeglich (Turbo)");
        };
    }

    /**
     * Autor 24.10.: "nach jeder abgeschlossenen Kathedrale um 0,1 % schneller -- und wenn du das maximale Tempo erreicht
     * hast, behaeltst du die Stufe 1 vor dem maximalen Tempo. Ohne Fehler am besten." Alle festen Pausen x Faktor
     * (kathedrale-tempo.txt); als Fehler zaehlt jede Warnung der Kathedrale waehrend eines Runs.
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("kathedrale-tempo.txt", "Kathedrale");
    private int runFehler;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn("Kathedrale", text);
    }

    @Override
    public void reset() {
        // Zaehler bis zum naechsten Titan-Besuch frisch aus der Datei (Hauptbot 09.10.).
        runsSeitEvent = seitEventLaden();
        ladeErwartetBis = 0;
        titanJetzt = false;
        kasse.auftragBeginnt();
        eventUnterwegs = eventLaeuft = eventLeer = false;
        runsBeiEventLeer = -1;
        eventWeg = eventGaenge = 0;
        fremdeFensterZu = 0;
        eventAbbrueche = 0;
        folge = null;
        // Kreislauf ohne Ende (Autor, 29.09.): nach den Verlies-Runs zuerst ins Event.
        if (de.hustlebot.core.Eventautomatik.istAn() && de.hustlebot.core.Eventautomatik.eventZuerstAbholen()) {
            eventUnterwegs = true;
            eventGaenge = 1;
            de.hustlebot.core.Eventautomatik.eventZuerstMerken();   // bleibt, bis das Event wirklich fertig ist
            Log.info("Kathedrale", de.hustlebot.core.Eventautomatik.titan()
                    ? Sprache.t("Loop: Titan Event first (use up the pickaxes), then the cathedral.",
                                "Kreislauf: erst ins Titan-Event (Spitzhacken aufbrauchen), danach Kathedrale.")
                    : Sprache.t("Loop: Hammer Event first (use up the hammers), then the cathedral.",
                                "Kreislauf: erst ins Hammer-Event (Haemmer aufbrauchen), danach Kathedrale."));
        }
        // Im Kreislauf endet der Auftrag nicht, wenn die Nahrung vorbei ist --
        // es geht mit Schluesseln weiter (Bezahlart unter !settings -> Bezahlen).
        if (de.hustlebot.core.Eventautomatik.istAn()) kasse.imKreislauf();
        cooldowns.clear();
        etageVergessen();
        insideCathedral = false;
        squadPrepared = false;
        blessingPicked = false;
        finishConfirmPending = false;
        choiceMade = false;
        battlePending = false;
        floorMapBlocked = 0;
        aliveUnits.clear();
        aufstellVersuche = 0;
        aufgestelltUm = 0;
        lastCardAt = null;
        reviveVersucheOhneErfolg = 0;
        unknownScreens = 0;
        mapSwipes = 0;
        searchingCathedral = false;
        burgStattKarte = 0;
        floorsDone = 0;
        currentStage = 0;
        cornerTapped = false;
        participationRead = false;
        keysExhausted = false;
        endeHinweis = null;
        letzterGangLeer = false;
        lernwerte.vergessen();
        turboStelle.clear();
        // Nach !stop mitten im Nur-Kisten-Gang darf der Wunsch nicht in die
        // naechste normale Kathedrale mitgenommen werden.
        nurKisten = nurKistenAngefordert;
        nurKistenAngefordert = false;
        nurKistenGestartet = false;
        // Ebenso ein nach !stop halb gelaufener Kisten-Gang: nicht fortsetzen.
        truhen.abbrechen();
        Log.info("Kathedrale", Sprache.t("Mode reset. Route: ", "Modus zurueckgesetzt. Weg: ") + routeAnzeige());
    }

    /** Kurz: laeuft gerade der Turbo (Weg c)? */
    /** Ein Turbo-Wert, der nicht zum bisherigen Stand passte (beim zweiten Mal gilt er). */
    private long turboSchluesselVerdacht = -1;
    /** Wie oft hintereinander im Turbo 0 Schluessel gelesen wurden. */
    private int turboNullGesehen = 0;

    /**
     * Schluessel im Turbo ablesen, ohne zu warten.
     *
     * Das Bild ist beim ersten Blick auf "Participate" evtl. noch nicht ganz
     * ruhig, und dann verliest sich die Ziffernsuche. Deshalb wird nur
     * uebernommen, was zum bisherigen Stand passt: gleich oder bis zu drei
     * weniger (ein Run kostet Schluessel). Mehr als vorher (Kisten, Handy)
     * gilt erst, wenn dieselbe Zahl zweimal hintereinander dasteht.
     *
     * @return der auf DIESEM Bild gelesene und geglaubte Stand, sonst -1
     */
    private long turboSchluesselLesen(Mat screen, BotContext ctx) {
        long start = System.nanoTime();
        if (!de.hustlebot.core.NumberReader.digitsAvailable(ctx.matcher(),
                de.hustlebot.core.NumberReader.KEY_PREFIX)) return -1;
        for (String icon : ctx.matcher().names()) {
            if (!icon.equals("dc_key_icon") && !icon.startsWith("dc_key_icon_")) continue;
            var wert = de.hustlebot.core.NumberReader.readRightOf(screen, ctx.matcher(), icon,
                    0.09, de.hustlebot.core.NumberReader.KEY_PREFIX);
            if (wert.isEmpty()) continue;
            long v = wert.getAsLong(), bisher = ctx.laufstatistik().keysNow();
            boolean passt = bisher < 0 || (v <= bisher && v >= bisher - 3) || v == turboSchluesselVerdacht;
            if (passt) {
                ctx.laufstatistik().recordKeys(v);
                turboSchluesselVerdacht = -1;
            } else {
                turboSchluesselVerdacht = v;
            }
            Log.debug("Kathedrale", "Schluessel im Turbo: " + v + (passt ? "" : " (noch nicht geglaubt)")
                    + " -- " + (System.nanoTime() - start) / 1_000_000 + " ms");
            return passt ? v : -1;
        }
        return -1;
    }

    private boolean istTurbo() {
        return route == Route.TURBO;
    }

    /**
     * Warten nach einem Klick. Im Turbo mit kuerzerer Blind-Wartezeit -- der
     * einzige feste Zeitanteil, den es zu druecken gibt. Die Obergrenze bleibt
     * gleich: haengt das Spiel, wird weiter voll gewartet.
     */
    private void warte(BotContext ctx, long maxMs) {
        if (istTurbo()) {
            ctx.sleepUntilChanged(Math.min(turboMinMs, maxMs), maxMs);
        } else {
            ctx.sleepUntilChanged(maxMs);
        }
    }

    /** Im Turbo: kurze Pause zwischen zwei verketteten Klicks im selben Schritt. */
    private long ketteMs = 200;   // lernwerte.txt

    /**
     * Turbo-Klick: sucht den Knopf im aktuellen Bild und drueckt ihn, wenn er
     * da ist. Anders als der normale click() OHNE Abkuehlzeit-Sperre und ohne
     * die uebrige Schrittkette -- er ist der Baustein fuer das Verketten
     * mehrerer Klicks in EINEM Schritt (siehe turboKette*).
     *
     * WICHTIG, und der Unterschied zum urspruenglichen Plan "blind druecken":
     * Es wird NUR geklickt, was auch gesehen wird. Ein Blindklick auf einen
     * Knopf, der gerade gar nicht da ist (etwa "Quick Attack" an einem
     * Wegpunkt, der nur "To Battle" zeigt), traefe ins Leere, der Bot hielte
     * den Kampf trotzdem fuer gestartet und bliebe haengen. Die Zeit spart
     * ohnehin nicht das Blinddruecken (rund 9 Tausendstelsekunden je Knopf),
     * sondern das Zusammenfassen der Bildschirmfotos.
     *
     * @return true, wenn geklickt wurde.
     */
    private boolean turboKlick(Mat screen, BotContext ctx, String base, String grund) {
        for (String name : variantsOf(ctx, base)) {
            Optional<Matcher.Hit> hit = ctx.find(screen, name);
            if (hit.isPresent()) {
                Matcher.Hit h = hit.get();
                turboStelle.put(base, new int[]{h.centerX(), h.centerY()});
                Log.action("Turbo", grund + " (" + name + ")");
                ctx.input().click(h.centerX(), h.centerY(), 4);
                unknownScreens = 0;
                return true;
            }
        }
        return false;
    }

    /**
     * Ein frisches Bild aufnehmen, ohne aus dem Schritt herauszugehen. So
     * lassen sich zwei Klicks (aufstellen, dann losschlagen) in EINEM Schritt
     * verketten, statt jedes Mal die ganze Schrittkette neu zu durchlaufen.
     * Gibt das alte Bild zurueck, wenn die Aufnahme fehlschlaegt.
     */
    private Mat turboFrisch(Mat alt, BotContext ctx) {
        ctx.sleep(tempo.ms(ketteMs));
        Mat frisch = ctx.refresh();
        return frisch != null ? frisch : alt;
    }

    /**
     * Wie turboFrisch, aber nur so lange, bis sich das Bild geaendert hat
     * (hoechstens ketteMs). Fuer den Segen (24.09.): Greift der Klick
     * auf "OK" zu frueh, bleibt das Segen-Fenster offen und wird im naechsten
     * Schritt normal bestaetigt -- kaputt gehen kann nichts. Beim Aufstellen
     * bleibt die feste Pause: Ein zu frueher Angriff liefe mit halber Truppe.
     */
    private Mat turboFrischSchnell(Mat alt, BotContext ctx) {
        return turboFrischSchnell(alt, ctx, 100);   // 24.09. abends: 200 -> 100 (wie beim Dangerous Game)
    }

    private Mat turboFrischSchnell(Mat alt, BotContext ctx, long maxMs) {
        ctx.sleepUntilChanged(20, maxMs);
        Mat frisch = ctx.refresh();
        return frisch != null ? frisch : alt;
    }

    // ==================================================================
    //  Der Ablauf
    // ==================================================================

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {

        schrittZaehler++;

        // Bezahlen (Teil A, 28.09.): laeuft ein Gang zur Burg (Nahrung ablesen)
        // oder durch die Tasche (Nahrungskisten), gehoert ihm der Schritt.
        Outcome kassenGang = kasse.schritt(screen, ctx);
        if (kassenGang != null) return kassenGang;

        // Event-Automatik (28.09.): der Gang ins Hammer-Event gehoert sich selbst.
        if (eventUnterwegs || eventLaeuft) return hammerEventSchritt(screen, ctx, runs);

        // Autor 24.10. (Ideenliste A14/B03/B04): leeres Ladebild kurz nach Run-Ende / Truppen bestaetigt -- nur still warten.
        if (ladeErwartetBis != 0) {
            if (System.currentTimeMillis() > ladeErwartetBis) {
                ladeErwartetBis = 0;
            } else if (bildLeer(screen)) {
                if (ladeLeerBilder++ == 0) {
                    Log.debug("Kathedrale", "Ladebild (leeres Bild) -- es wird gewartet, kein Fehlalarm.");
                }
                return Outcome.WAITING;
            }
        }
        ladeLeerBilder = 0;

        // --- 1. Verbindungsabbruch / AFK-Rauswurf hat immer Vorrang. ---
        //
        // Nach ~15 Minuten ohne Eingabe wirft das Spiel einen mit "My lord,
        // are you still here?" heraus; zurueck geht es nur ueber den gruenen
        // "Update"-Knopf, der das Spiel neu laedt. Deshalb JEDEN Schritt danach
        // sehen (nicht nur seltenDran) -- wie im Verlies. Sonst startet der
        // Modus nach einem Befehl ins Leere, weil der Bot noch auf dem
        // Rauswurf-Bildschirm steht.
        // Hauptbot 62b1968: steht dahinter "von einem anderen Geraet angemeldet", NICHT druecken -- anhalten,
        // bis der Spieler Weiter sagt (dann drueckt anderesGeraet selbst).
        if (ctx.visibleIfKnown(screen, T_UPDATE) && Stoerfenster.autorIstDrin(screen, ctx)) {
            switch (Stoerfenster.anderesGeraet(screen, ctx, "Kathedrale")) {
                case ANHALTEN -> { return Outcome.PAUSE; }
                case GEKLICKT -> { return Outcome.ACTED; }
                case NICHTS -> { }
            }
        } else if (click(screen, ctx, T_UPDATE, Sprache.t("Reconnect (Update)", "Verbindung neu aufbauen (Update)"))) {
            Log.info("Kathedrale", Sprache.t(
                    "AFK kick detected -- the game is reloaded via Update, waiting 20 seconds. "
                    + "After that it continues from the castle.",
                    "AFK-Rauswurf erkannt -- Spiel wird ueber Update neu "
                    + "geladen, warte 20 Sekunden. Danach geht es von der Burg aus weiter."));
            ctx.sleep(20000);
            return Outcome.ACTED;
        }

        // --- 0. Von einem anderen Geraet angemeldet. Vorrang vor allem. -
        if (seltenDran()) {
            switch (Stoerfenster.anderesGeraet(screen, ctx, "Kathedrale")) {
                case ANHALTEN -> { return Outcome.PAUSE; }
                case GEKLICKT -> {
                    runs.progress(Sprache.t("Logged in again after the kick", "Nach Rauswurf neu angemeldet"));
                    return Outcome.ACTED;
                }
                case NICHTS -> { /* weiter im Ablauf */ }
            }

            // --- 1a. Das taegliche Belohnungsfenster. -------------------
            //
            // Kommt nach dem naechtlichen Neustart des Spiels, sobald der
            // Bot das erste Mal wieder in der Burg steht.
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Kathedrale")) {
                runs.progress(Sprache.t("Reward window closed", "Belohnungsfenster geschlossen"));
                return Outcome.ACTED;
            }
        }

        // --- 1a1. Kisten-Gang auf Kommando (!kisten) -------------------------
        //
        // Zum Testen: loest den Gang sofort aus, auch wenn noch Schluessel da
        // sind. So laesst sich der Weg Kathedrale -> Burg -> Tasche -> Kisten
        // -> zurueck pruefen, ohne auf leere Schluessel zu warten. Danach macht
        // der Kathedralen-Modus von selbst weiter.
        // Erst am Teilnahme-Bildschirm -- genau dort, wo der Gang auch im Ernstfall
        // (Schluessel alle) beginnt. Mitten in der Reise kennt er keinen Weg
        // in die Burg (Test 23.09., 22:59: "Weg nicht gefunden").
        //
        // NUR KISTEN (Autor, 25.09.: "den kisten run testen ohne einen
        // cathedral run davor"): gleich loslegen, egal welcher Bildschirm --
        // der Gang findet aus der Burg und aus dem Kathedralen-Fenster heraus
        // in die Tasche. Danach ist Schluss, es wird keine Reise gespielt.
        boolean nurKistenJetzt = nurKisten && !nurKistenGestartet;
        if (!truhen.aktiv() && (nurKistenJetzt
                || (kistenTestGewuenscht && ctx.visible(screen, T_PARTICIPATE)))) {
            kistenTestGewuenscht = false;
            if (truhen.einsatzbereit(ctx)) {
                if (nurKisten) nurKistenGestartet = true;
                truhen.starten(ctx);
                runs.progress(Sprache.t("Chest test: trip started", "Kisten-Test: Gang gestartet"));
                return Outcome.ACTED;
            }
            warnung(Sprache.t(
                    "Chest test requested, but templates are missing "
                    + "(bag_button / bag_title / bag_chest_1). The trip is not started.",
                    "Kisten-Test angefordert, aber es fehlen Vorlagen "
                    + "(bag_button / bag_title / bag_chest_1). Der Gang wird nicht gestartet."));
            if (nurKisten) {
                nurKisten = false;
                return Outcome.FINISHED;
            }
        }

        // --- 1a2. Der Kisten-Gang laeuft. -------------------------------
        //
        // Steht weit vorn, weil er durch fremde Bildschirme fuehrt (Tasche,
        // Kistenfenster, Oeffnen-Animation). Wuerde die uebliche Kette
        // darunter zuerst greifen, wuerde sie diese Bildschirme fuer
        // Stoerfenster halten und wegklicken.
        if (truhen.aktiv()) {
            boolean gehandelt = truhen.schritt(screen, ctx);
            if (truhen.aktiv()) {
                runs.progress(Sprache.t("Opening the chests in the bag", "Kisten in der Tasche oeffnen"));
                return Outcome.ACTED;
            }
            // Der Gang ist vorbei -- AUCH wenn sein letzter Schritt noch etwas
            // gedrueckt hat (das X der Tasche). Frueher wurde das Ende nur
            // bemerkt, wenn schritt() false lieferte; endete der Gang sauber
            // mit dem X, lief der Nur-Kisten-Gang in eine Reise weiter (Test
            // 25.09., 21:56) und der Schluesselstand blieb der alte.
            //
            // Der Schluesselstand muss neu abgelesen
            // werden, sonst gilt weiter der alte Stand von vor den Kisten.
            letzterGangLeer = (truhen.geoeffnet() == 0 && truhen.tascheLeer()) || truhen.wenigeKisten();
            keysExhausted = false;
            participationRead = false;
            ctx.laufstatistik().vergissSchluessel();
            if (nurKisten) {
                nurKisten = false;
                Log.info("Kathedrale", Sprache.t(
                        "Chests-only trip done: " + truhen.geoeffnet() + " chests opened. No journey is played.",
                        "Nur-Kisten-Gang fertig: " + truhen.geoeffnet()
                        + " Kisten geoeffnet. Es wird keine Reise gespielt."));
                return Outcome.FINISHED;
            }
            if (gehandelt) return Outcome.ACTED;
        }

        // --- 1c. Waehrend eines Kampfs: zuerst nach "Home" sehen. -------
        //
        // Autor, 25.09.: "da wartet er immer eine Weile". Gemessen mit
        // Fotos (22:09:48): "Victory" um 48,75, der Home-Knopf erscheint um
        // 49,83 -- gedrueckt hat der Bot erst um 50,34. Er sah jedes Bild erst
        // alle anderen Fenster durch, bevor er in Schritt 7 nach Home schaute.
        // Dieselbe Pruefung wie Schritt 7, nur vorn.
        if (imKampf() && ergebnisKnopfDa(screen, ctx) && !ctx.visible(screen, T_JOURNEY_DONE)) {
            return kampfergebnisBestaetigen(screen, ctx, runs);
        }

        // --- 1b. Der Teilnahmebildschirm des VERLIESES steht im Weg. ----
        //
        // Passiert beim Umschalten mitten im Betrieb: "!start kathedrale",
        // waehrend noch das Verlies-Fenster offen ist. Erst raus, dann kann
        // die Kathedrale gesucht werden.
        if (seltenDran() && onDungeonScreen(screen, ctx)) {
            if (click(screen, ctx, T_CLOSE_X, Sprache.t("Leave the dungeon window", "Verlies-Fenster verlassen"))) {
                Log.info("Kathedrale", Sprache.t(
                        "The dungeon's participation screen was open. "
                        + "It is closed, then it is off to the cathedral.",
                        "Der Teilnahmebildschirm des Verlieses stand offen. "
                        + "Er wird geschlossen, danach geht es zur Kathedrale."));
                insideCathedral = false;
                searchingCathedral = false;
                ctx.sleepUntilChanged(600);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // --- 2. Reise fertig: Abschlussfenster wegklicken. --------------
        // Autor 24.10. (Ideenliste A13): das Abschlussfenster kommt nur nach "End journey" -- dann (und auf der letzten Etage)
        // wird jedes Bild geprueft, sonst alle SELTEN_TAKT Bilder und bei jedem unerkannten Bild. Gemessen 32 Suchen ueber das
        // ganze Bild je Run (0,75 s). Den Eckklick-Merker nur zuruecksetzen, wenn wirklich hingesehen wurde.
        if (journeyDoneZuPruefen()) {
            if (ctx.visible(screen, T_JOURNEY_DONE)) {
                reiseEndeBis = System.currentTimeMillis() + REISE_ENDE_MS;
                return handleJourneyDone(screen, ctx, runs);
            }
            // Abschlussfenster ist weg: der Eckklick gilt wieder als offen.
            cornerTapped = false;
        }

        // --- 3. Warnungen. Immer "Yes". ---------------------------------
        // Es gibt mehrere: zu wenige Einheiten aufgestellt, und die Nachfrage
        // beim vorzeitigen Beenden. Beide sollen bejaht werden.
        // Nicht ueber der Tasche: dort ist es die "Lager voll"-Nachfrage der Nahrungskisten.
        // Autor 24.10. (Ideenliste A13): die Nachfrage ueber der Tasche hat immer ein "Yes" -- ohne "Yes" im Bild ist die Frage
        // beantwortet, bevor bag_title (24 Suchen ueber das ganze Bild je Run, 0,6 s) gesucht wird. Gleiches Ergebnis wie vorher.
        if (ctx.visibleIfKnown(screen, T_YES) && Stoerfenster.lagerVollFrage(screen, ctx, "Kathedrale")) {
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        if (click(screen, ctx, T_YES, Sprache.t("Confirm warning", "Warnung bestaetigen"))) {
            // Nach der Warnung kommt oft noch einmal der Truppenbildschirm:
            // die Wartezeit fuers Antippen der Sieg-Animation neu beginnen.
            if (battlePending) {
                kampfGestartetUm = System.currentTimeMillis();
                // 31.10. (Bericht NXWE, Ben): "Yes" nach Quick Attack heisst, der Kampf laeuft schon. Bisher sah der
                // Bot noch den ausblendenden Truppenbildschirm, suchte 10 s "Quick Attack" und warnte faelschlich
                // "No attack button" (Kampf 15,8 s statt 1,8 s). Darum gleich auf "Home" warten; kommt es nicht,
                // geht es wie bisher im normalen Ablauf weiter.
                if (istTurbo() && kastenblick) return kampfAbwarten(ctx, runs);
            }
            ctx.sleepUntilChanged(700);
            return Outcome.ACTED;
        }

        // --- 3b. Die naechste Etage laedt. ------------------------------
        if (etageLaedt) {
            Outcome neu = aufNeueEtageWarten(ctx, runs);
            if (neu != null) return neu;
        }

        // --- 4. "Waypoint completed!" wegklicken. -----------------------
        if (ctx.visible(screen, T_WAYPOINT_DONE)) {
            battlePending = false;
            floorMapBlocked = 0;
            if (click(screen, ctx, T_CLOSE, Sprache.t("Waypoint completed", "Wegpunkt abgeschlossen"))) {
                etagennetz.letzterAbgeschlossen();
                runs.progress(Sprache.t("Waypoint completed", "Wegpunkt abgeschlossen"));
                zeit.phase(Zeitbericht.WEGWAHL);
                karteKommtNeu();
                ctx.sleepUntilChanged(700);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // --- 4b. "Ally in Reflection": Verbuendeten immer aufnehmen. ----
        // Eine Kopie einer eigenen Einheit, die den Rest der Reise mitkaempft.
        // Steht vor der Etagenkarte, weil das Fenster darueber liegt.
        if (clickAny(screen, ctx, T_JOIN_SQUAD, Sprache.t("Put the ally in the squad", "Verbuendeten in die Truppe aufnehmen"))) {
            runs.progress(Sprache.t("Ally taken in", "Verbuendeten aufgenommen"));
            ctx.sleepUntilChanged(400);
            return Outcome.ACTED;
        }
        // Ueberschrift zu sehen, Knopf aber nicht: dann wird gewartet statt
        // geraten. Ohne die Ueberschrift faellt dieser Fall einfach weg.
        // Autor 24.10. (Ideenliste A13): die Ueberschrift ist nur der Rueckfall fuer "Knopf nicht erkannt" -- den Normalfall (Knopf
        // sichtbar) erledigt die Zeile darueber. Darum nur alle SELTEN_TAKT Bilder und bei jedem unerkannten Bild (25 Suchen/Run).
        if (seltenDran() && ctx.matcher().has(T_ALLY_SCREEN) && ctx.visible(screen, T_ALLY_SCREEN)) {
            if (!ctx.matcher().has(T_JOIN_SQUAD) && !onCooldown("ally_warn")) {
                warnung(Sprache.t(
                        "The window \"Ally in Reflection\" is open, "
                        + "but the button \"Put in the squad\" is missing as a template. "
                        + "Please capture dc_join_squad with Capture-Images.exe.",
                        "Das Fenster \"Ally in Reflection\" steht offen, "
                        + "aber der Knopf \"Put in the squad\" fehlt als Vorlage. "
                        + "Bitte dc_join_squad mit Capture-Images.exe aufnehmen."));
                cooldowns.put("ally_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        // --- 5. Segen waehlen: immer die mittlere Karte. ----------------
        // Der Segen kommt nach dem Kampf: spaetestens hier ist er vorbei.
        if (ctx.visible(screen, T_BLESSING)) {
            battlePending = false;
            floorMapBlocked = 0;
            return handleBlessing(screen, ctx, runs);
        }
        // Segensfenster ist weg: beim naechsten Mal wieder neu waehlen.
        blessingPicked = false;

        // --- 6. Entscheidungsfelder: immer die sichere Wahl. ------------
        //
        // Das Fenster gilt auch dann noch als offen, wenn die Wahl schon
        // angeklickt ist und nur noch "Finish selection" wartet: nach dem
        // Anklicken kann "Move on" sein Aussehen aendern und aus der
        // Erkennung fallen. Ohne diesen Zusatz bliebe der Bot dann mit
        // gesetztem Haken stehen und drueckte nie ab.
        // Karlas Bericht #B7XK (16.10.): das Warnfenster "Are you sure you want to finish the selection?" stand offen
        // und verdeckte die Wahl-Karten. Die Merker waren schon zurueckgesetzt -- choiceScreen war falsch, handleChoice
        // kam nie dran, und der Bot drueckte 40 s lang das rote X hinter dem Fenster. Der kleine gruene Knopf
        // dc_finish_confirm steht nur in diesem Fenster (Mitte; der grosse "Finish selection" liegt rechts unten):
        // sehen = bestaetigen, egal in welchem Zustand.
        if (ctx.matcher().has(T_FINISH_CONFIRM)) {
            Optional<Matcher.Hit> warnung = ctx.matcher().find(screen, T_FINISH_CONFIRM);
            if (warnung.isPresent() && warnung.get().centerX() < ctx.width() * 0.6) {
                Matcher.Hit hit = warnung.get();
                Log.action("Klick", Sprache.t("Confirm the selection (warning window)", "Auswahl bestaetigen (Warnfenster)")
                        + "  [" + hit.name() + " at " + hit.centerX() + "," + hit.centerY()
                        + String.format(", match %.3f]", hit.score()));
                ctx.input().click(hit.centerX(), hit.centerY(), 6);
                finishConfirmPending = false;
                choiceMade = false;
                wahlVergessen();
                unknownScreens = 0;
                runs.progress(Sprache.t("Decision confirmed", "Entscheidung bestaetigt"));
                ctx.sleepUntilChanged(500);
                return Outcome.ACTED;
            }
        }

        boolean choiceScreen = visibleAny(screen, ctx, T_MOVE_ON, T_CLAIM_REWARD)
                || (choiceMade && ctx.matcher().has(T_FINISH_SELECT)
                                && ctx.visible(screen, T_FINISH_SELECT));

        // Entscheidungsfenster ist weg: beide Schalter fallen.
        if (!choiceScreen) {
            finishConfirmPending = false;
            choiceMade = false;
        }

        if (choiceScreen) {
            // Autor 24.10. (Ideenliste A33): "Belohnung -> Auswahl beenden -> Warnung bestaetigen -> Wegpunkt erledigt" als
            // Kastenblick-Kette (60 ms Takt, nur was gesehen wird) statt vier Mal die ganze Schrittkette: gemessen 0,81 s je
            // Fenster (0,39 + 0,20 + 0,21), ~4 Fenster je Run. Sind die Stellen der Knoepfe noch nicht bekannt, oder bleibt die
            // Kette zweimal ohne einen Klick, gilt wie bisher der Einzelschritt (handleChoice).
            if (fensterKetteErlaubt(ctx)) {
                return fensterKetteGeprueft(ctx, runs);
            }
            return handleChoice(screen, ctx, runs);
        }

        // --- 7. Kampfergebnis, Sieg wie Niederlage: "Home". -------------
        if (ergebnisKnopfDa(screen, ctx) && !ctx.visible(screen, T_JOURNEY_DONE)) {
            return kampfergebnisBestaetigen(screen, ctx, runs);
        }

        // --- 7b. Eine Einheitenkarte liegt offen. -----------------------
        //
        // DAS MUSS VOR SCHRITT 8 STEHEN, und darin lag der Fehler.
        //
        // Die Karte legt sich ueber den ganzen Bildschirm. Damit wird
        // dc_deploy_screen nicht mehr erkannt, Schritt 8 greift nicht, und
        // handleSquad() laeuft nicht -- obwohl genau darin die Stelle sass,
        // die merkt "die Einheit lebt ja noch". Der Bot fiel stattdessen bis
        // Schritt 14b durch, der die Karte stumm wegklickte und den Merker
        // loeschte. Beim naechsten Wegpunkt fing alles von vorn an.
        //
        // Im Protokoll vom 4. September steht es Zeile fuer Zeile: elfmal
        // "Gefallene Einheit anwaehlen (unit_magier2)", elfmal
        // "Einheitenkarte lag im Weg, wird geschlossen" -- und kein einziges
        // Mal "Einheit lebt noch, Karte schliessen". Autor: "Bot versucht
        // eine Einheit 10 mal wiederzubeleben, da er nicht checkt das die
        // Einheit gar nicht tot ist. Das Problem hatten wir schonmal."
        //
        // Es hier oben zu pruefen kostet nichts: liegt keine Karte offen,
        // kommt sofort null zurueck.
        // Autor 24.10. (Ideenliste A13): eine Karte liegt nur offen, wenn der Bot gerade eine Einheit angetippt hat (lastCardAt)
        // oder sich ein Klick verirrt hat -- sonst reicht alle SELTEN_TAKT Bilder und jedes unerkannte Bild (switch_dwellers).
        if (lastCardAt != null || seltenDran()) {
            Outcome einheitenkarte = handleUnitCard(screen, ctx, runs);
            if (einheitenkarte != null) return einheitenkarte;
        }

        // --- 8. Truppenbildschirm. --------------------------------------
        if (ctx.matcher().has(T_DEPLOY_SCREEN) && ctx.visible(screen, T_DEPLOY_SCREEN)) {
            return handleSquad(screen, ctx, runs);
        }

        // --- 9. "Gather troops" ganz am Anfang der Reise. ---------------
        if (ctx.visible(screen, T_PREV_SQUAD)) {
            return handleGatherTroops(screen, ctx, runs);
        }
        if (click(screen, ctx, T_ATTACK, Sprache.t("Set off (Attack!)", "Losziehen (Attack!)"))) {
            insideCathedral = true;
            runs.progress(Sprache.t("Journey started", "Reise begonnen"));
            ctx.sleepUntilChanged(700);
            return Outcome.ACTED;
        }

        // --- 10. Naechste Etage -- oder Schluss nach der dritten. -------
        //
        // "Next floor" erscheint, sobald der Etagenwaechter gefallen ist. War
        // das die dritte Etage, wird nicht weitergezogen: dann geht es unten
        // rechts ueber "End journey" hinaus.
        if (ctx.matcher().has(T_NEXT_FLOOR) && ctx.visible(screen, T_NEXT_FLOOR)) {
            // "Next floor" steht da -- also GIBT es noch eine Etage. Das weiss
            // das Spiel besser als jeder Zaehler von uns.
            //
            // Autor, 19.09.: Der Bot stand auf Etage ZWEI, hat die Zahl auf
            // dem Sockel aber als 3 gelesen. Damit hielt er die Reise fuer
            // fertig, suchte 2,5 Minuten lang einen gruenen "End journey" --
            // der war rot, weil die Reise eben NICHT fertig war -- und wurde
            // dann von der Notbremse gestoppt. journeyFinished() haette es
            // richtig gewusst, wurde vom Zaehler aber ueberstimmt.
            // Deshalb jetzt: beendet wird nur, wenn der End-journey-Knopf
            // wirklich GRUEN ist.
            if (onLastStage() && journeyFinished(screen, ctx)) {
                etagenGesamt += Math.max(0, MAX_FLOORS - floorsDone);
                etageAbschliessen(true);
                floorsDone = MAX_FLOORS;
                Log.info("Kathedrale", Sprache.t(
                        "Floor " + MAX_FLOORS + " is cleared and the last guardian has fallen. The journey is ended.",
                        "Etage " + MAX_FLOORS + " ist geschafft und der "
                        + "letzte Waechter gefallen. Die Reise wird beendet."));
                runs.progress(Sprache.t("Last floor cleared", "Letzte Etage geschafft"));
                battlePending = false;
                floorMapBlocked = 0;
                Outcome leaving = endJourney(screen, ctx, runs);
                if (leaving != null) return leaving;
            } else if (click(screen, ctx, T_NEXT_FLOOR, Sprache.t("Next floor", "Naechste Etage"))) {
                if (onLastStage()) {
                    // Nur melden, NICHT den Zaehler zuruecksetzen. floorsDone
                    // zaehlt die tatsaechlich geklickten "Next floor" und ist
                    // damit verlaesslich -- daneben lag nur die abgelesene
                    // Zahl auf dem Sockel. Ein Reset waere sogar gefaehrlich:
                    // Passt die gruene Vorlage nicht (bei Autor 0,69 statt
                    // 0,88), ist floorsDone >= MAX_FLOORS der EINZIGE Weg, die
                    // Reise zu beenden. Wuerde er staendig zurueckgesetzt,
                    // endete die Reise nie.
                    warnung(Sprache.t(
                            "The number on the pedestal says last floor, but "
                            + "\"Next floor\" is there -- the number was probably misread. "
                            + "Continuing; floors are counted by the floor changes.",
                            "Die Zahl auf dem Sockel sagt letzte Etage, aber "
                            + "\"Next floor\" ist da -- die Zahl wurde wohl falsch gelesen. "
                            + "Es geht weiter; gezaehlt wird nach den Etagenwechseln."));
                }
                etageAbschliessen(true);
                floorsDone++;
                etagenGesamt++;
                currentStage = 0;   // wird auf der naechsten Karte neu gelesen
                etageVergessen();
                battlePending = false;
                floorMapBlocked = 0;
                etageLaedt = true;
                Log.info("Kathedrale", Sprache.t("Floor cleared, on to the next one.", "Etage geschafft, weiter zur naechsten."));
                runs.progress(Sprache.t("Floor cleared", "Etage geschafft"));
                zeit.phase(Zeitbericht.ETAGE);
                ctx.sleepUntilChanged(600);
                return Outcome.ACTED;
            }
        }

        // --- 10b. Reise abgeschlossen: nur noch hinaus. -----------------
        //
        // Zweiter, unabhaengiger Weg: der Knopf unten rechts verraet den
        // Zustand selbst -- rot solange die Reise laeuft, gruen wenn alles
        // erledigt ist. Greift auch dann, wenn "Next floor" gar nicht erst
        // erscheint.
        // Autor 24.10. (Ideenliste A13): der Knopf wird erst nach dem letzten Waechter gruen. Auf Etage 1 und 2 reicht darum
        // alle SELTEN_TAKT Bilder (end_journey_green kostete 51 ms je Suche, 14 Suchen/Run); auf der letzten Etage, bei nicht
        // abgelesener Etage und bei jedem unerkannten Bild wird wie vorher jedes Bild geprueft.
        if (floorsDone >= MAX_FLOORS || (endeMoeglich() && journeyFinished(screen, ctx))) {
            Outcome leaving = endJourney(screen, ctx, runs);
            if (leaving != null) return leaving;
        }

        // --- 11. Etagenkarte. -------------------------------------------
        if (isFloorMap(screen, ctx)) {
            karteGefunden();
            return handleFloorMap(screen, ctx, runs);
        }

        // --- 12. Teilnahmefenster der Kathedrale. -----------------------
        if (ctx.visible(screen, T_PARTICIPATE)) {
            karteGefunden();
            return handleParticipation(screen, ctx, runs);
        }
        // Fenster ist weg: beim naechsten Mal wieder auf die Zahlen warten.
        participationRead = false;

        // --- 13. Weltkarte: die Kathedrale suchen und anklicken. --------
        // 24.09.: dc_node wird ueberall im ganzen Bild gesucht (wandert). Ausser
        // waehrend der Suche auf der Weltkarte reicht jedes dritte Bild.
        if (searchingCathedral || (schrittZaehler % 3 == 0 && ctx.visible(screen, T_NODE))) {
            return handleWorldMap(screen, ctx, runs);
        }

        // --- 13b. Werbefenster in der Burg wegklicken. -----------------------
        //
        // "Running out of apples? Watch an ad to get some food" und ihre
        // Geschwister tauchen beim Zurueckkehren in die Burg auf und legen
        // sich ueber alles.
        //
        // ACHTUNG, HIER LAG EIN BOESER FEHLER: Zuerst stand diese Pruefung
        // ganz oben in der Kette, ohne jede Bedingung. Das rote runde X sieht
        // aber bei JEDEM Fenster gleich aus -- auch beim Teilnahmefenster des
        // Verlieses. Der Bot betrat das Kathedrale und schloss es im selben
        // Atemzug wieder:
        //
        //     Kathedrale betreten -> Truppen aufstellen -> "Werbefenster"
        //     schliessen -> Fackeln -> Kathedrale betreten -> ...
        //
        // Nach sechs Runden gab er auf und meldete, es seien keine Fackeln
        // mehr da. Es waren welche da.
        //
        // Deshalb die Bedingung: NUR wenn gleichzeitig die Burg im Hintergrund
        // zu sehen ist. Erkannt wird sie am "Map"-Knopf unten links -- der
        // bleibt sichtbar, weil das Werbefenster nur die Mitte verdeckt. Beim
        // Teilnahmefenster ist er es nicht, das deckt den ganzen Bildschirm.
        //
        // Und die Pruefung steht VOR der Burg-Regel darunter: Sonst wuerde der
        // Bot "Map" druecken wollen, waehrend das Werbefenster im Weg ist.
        //
        // NICHT OHNE DIESE BEDINGUNG WIEDER EINBAUEN.
        if (visibleAny(screen, ctx, T_MAP_BUTTON, T_MAP_BUTTON_ALT)
                && Stoerfenster.werbungSicher(screen, ctx)
                && clickAny(screen, ctx, T_AD_CLOSE, Sprache.t("Close the ad window in the castle", "Werbefenster in der Burg schliessen"))) {
            ctx.sleepUntilChanged(400);
            return Outcome.ACTED;
        }

        // --- 14. Burg: zur Weltkarte. -----------------------------------
        if (clickAny(screen, ctx, T_MAP_BUTTON, Sprache.t("Open the world map", "Weltkarte oeffnen"))) {
            searchingCathedral = true;
            mapSwipes = 0;
            karteGeoeffnetUm = System.currentTimeMillis();
            insideCathedral = false;
            runs.progress(Sprache.t("World map opened", "Weltkarte geoeffnet"));
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }

        // --- 14b. Eine Einheitenkarte liegt im Weg. ---------------------
        //
        // Ausserhalb des Truppenbildschirms hat sie hier nichts zu suchen.
        // Ging ein Klick daneben und traf eine Einheit, legt sich ihre Karte
        // ueber den ganzen Bildschirm -- danach erkennt der Bot nichts mehr
        // und bleibt stehen. Ein Klick auf die freie Flaeche rechts daneben
        // schliesst sie, ohne sonst etwas auszuloesen.
        //
        // Steht hier bewusst weit unten: erst wenn alle richtigen Bildschirme
        // durchgeprueft sind, ist die Karte wirklich ein Hindernis.
        if (ctx.matcher().has(T_UNIT_CARD) && ctx.visible(screen, T_UNIT_CARD)) {
            // HIER WURDE DAS GEDAECHTNIS WEGGEWORFEN.
            //
            // Vorher stand an dieser Stelle nur "lastCardAt = null". Hat der
            // Bot diese Karte selbst geoeffnet, weil er die Einheit fuer
            // gefallen hielt, dann ist sie offenbar gar nicht tot -- sonst
            // haette handleUnitCard() weiter oben den Resurrect-Knopf
            // gefunden und gedrueckt. Genau das gehoert gemerkt, statt
            // stillschweigend vergessen zu werden.
            merkeLebt(Sprache.t("Unit card without a Resurrect button", "Einheitenkarte ohne Resurrect-Knopf"));

            int x = (int) (ctx.width() * CARD_DISMISS_X);
            int y = (int) (ctx.height() * CARD_DISMISS_Y);
            Log.action("Klick", Sprache.t(
                    "A unit card was in the way, closing it at " + x + "," + y,
                    "Einheitenkarte lag im Weg, wird geschlossen "
                    + "bei " + x + "," + y));
            ctx.input().click(x, y, 6);
            lastCardAt = null;
            ctx.sleepUntilChanged(1500);   // gemessen: 700 war 6 von 13 Mal zu kurz (max 758 ms)
            return Outcome.ACTED;
        }

        // --- 15. Nichts erkannt. ----------------------------------------
        //
        // Waehrend eines Kampfs ist das normal (Kampf, Siegesbanner). Dann
        // NICHT als "unbekannt" zaehlen: Sonst laufen ab dem ersten solchen
        // Bild alle seltenen Pruefungen jedes Mal mit (seltenDran), und nach
        // drei Bildern sucht der Motor ein ganzes Bild gruendlich ab -- das
        // dauert, und erscheint "Home" gerade dann, sieht der Bot es ein Bild
        // zu spaet (Autor, 25.09.: "da wartet er immer eine Weile").
        if (imKampf()) {
            // Versuch (lernwerte.txt, sieg_antippen): Die Sieg-Animation
            // antippen, wie die Kisten-Animation -- vielleicht kommt "Home"
            // dann frueher (bisher 1,1 s je Kampf reine Animation). Hier
            // unten ist alles Bekannte schon ausgeschlossen: keine Warnung,
            // kein Truppenbildschirm, kein Home. Getippt wird oben Mitte,
            // zwischen Banner und Belohnung, weit weg von "Home".
            if (siegAntippen && System.currentTimeMillis() - kampfGestartetUm >= siegTippAbMs) {
                int x = (int) (ctx.width() * 0.50), y = (int) (ctx.height() * 0.30);
                if (siegTipps++ == 0) {
                    Log.debug("Kathedrale", "Sieg-Animation antippen bei " + x + "," + y);
                }
                ctx.input().click(x, y, 6);
            }
            return Outcome.WAITING;
        }

        // Auf der Weltkarte (nach Leuchtturm/Kolosseum, Autor 26.09.), die
        // Kathedrale aber nicht im Bild (Schritt 13 haette sie sonst schon
        // angeklickt): mit "Home" in die Burg, von dort der gewohnte Weg.
        // Autor, 26.09.: "sobald er in der Karte ist, soll er automatisch zur
        // Kathedrale gehen" -- vorher wartete der Bot drei Bilder und ging dann
        // ueber die Burg (10 s). Jetzt sofort: Kathedrale anklicken, oder die
        // Karte ziehen, bis sie zu sehen ist (vom Kolosseum aus liegt sie
        // rechts -- die erste Zugrichtung in handleWorldMap).
        if (!insideCathedral && Weltkarte.erkannt(screen, ctx)) {
            unknownScreens = 0;
            // Gerade erst auf die Kathedrale geklickt: Das Teilnahme-Fenster
            // laedt noch, die Karte ist nur noch kurz zu sehen -- nicht ziehen.
            if (System.currentTimeMillis() - knotenGeklicktUm < 3000) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            searchingCathedral = true;
            return handleWorldMap(screen, ctx, runs);
        }
        // Bericht #A6Y3 (Autor, 28.10.): eine uebrig gebliebene Kisten-Animation oder offene Tasche wegklicken,
        // statt nach 20 s stillzustehen.
        if (!truhen.aktiv() && Truhenoeffner.kistenBildschirmWegklicken(screen, ctx)) {
            unknownScreens = 0;
            runs.progress(Sprache.t("Leftover chest screen closed", "Uebrigen Kisten-Bildschirm geschlossen"));
            return Outcome.ACTED;
        }
        unknownScreens++;
        if (Stoerfenster.warnungFaellig(unknownScreens, runs.idleSeconds())) {
            warnung(Sprache.t(
                    "Nothing recognized for " + unknownScreens + " frames. "
                    + "Last progress: " + runs.lastNote()
                    + " " + runs.idleSeconds() + " seconds ago.",
                    "Seit " + unknownScreens + " Bildern nichts erkannt. "
                    + "Letzter Fortschritt: " + runs.lastNote()
                    + " vor " + runs.idleSeconds() + " Sekunden."));
            reportNearMisses(screen, ctx);
        }

        // Notausgang: unbekanntes Fenster mit rotem X wegklicken.
        if (Stoerfenster.notausgang(screen, ctx, "Kathedrale", unknownScreens)) {
            runs.progress(Sprache.t("Unknown window closed", "Unbekanntes Fenster geschlossen"));
            return Outcome.ACTED;
        }
        if (unknownScreens > 150) {
            Log.error("Kathedrale", Sprache.t(
                    "The screen is persistently not recognized. "
                    + "Templates are probably missing. The bot is stopped.",
                    "Der Bildschirm wird dauerhaft nicht erkannt. "
                    + "Vermutlich fehlen Vorlagen. Bot wird gestoppt."));
            return Outcome.FATAL;
        }
        return Outcome.UNKNOWN;
    }

    // ==================================================================
    //  Einzelne Bildschirme
    // ==================================================================

    /**
     * Teilnahmefenster. Bezahlart und Schwierigkeit sind im Spiel schon
     * gewaehlt und bleiben unberuehrt -- nur "Participate" wird gedrueckt.
     *
     * Vorher wird der Schluesselstand geprueft. Sind es zu wenige, wird keine
     * neue Reise mehr begonnen; die laufende war zu diesem Zeitpunkt bereits
     * abgeschlossen, sonst waeren wir gar nicht hier.
     */
    private Outcome handleParticipation(Mat screen, BotContext ctx, RunTracker runs) {

        // BEZAHLEN (Teil A, 28.09.): Reiter "For keys" oder "For food", dazu
        // Preis, Nahrungs-Limit und Vorrat. Reicht die Nahrung nicht sicher,
        // wird "Participate" nicht gedrueckt (nie Diamanten).
        Outcome bezahlt = kasse.vorTeilnahme(screen, ctx);
        if (bezahlt != null) return bezahlt;
        // Mit Nahrung zaehlen die Schluessel nicht -- gleich teilnehmen.
        if (kasse.mitNahrung()) return mitNahrungTeilnehmen(screen, ctx, runs);

        // TURBO (Weg c): kein Schluesselzaehler.
        //
        // Autor, 19.09.: "nimm den Schluessel und particle of light zaehler
        // raus. Bei der c Variante geht das dann nicht. Muss dann selber drauf
        // achten ob noch genuegend Schluessel uebrig sind." Das Ablesen kostet
        // je Reise mehrere Takte plus Wartezeit -- bei einer Zielzeit unter
        // 1:30 ist das zu viel.
        //
        // Damit der Bot nicht ewig ins Leere klickt, wenn die Schluessel doch
        // alle sind, zaehlt teilnahmeVersuche mit: Kommt er nach ein paar
        // Anlaeufen nicht in die Reise, hoert er von selbst auf.
        if (istTurbo()) {
            // Schluessel auch im Turbo (Autor, 25.09.: "alle 3 varianten
            // sollen die schluessel lesen ... nur wenig zeit pro run"). Kein
            // Warten wie in a/b: gelesen wird aus DEMSELBEN Bild, auf dem
            // gleich "Participate" gedrueckt wird -- nur ein paar Ziffern-
            // vergleiche in einem schmalen Feld. Klappt es nicht, eben beim
            // naechsten Run.
            long frisch = ctx.visible(screen, T_PARTICIPATE) ? turboSchluesselLesen(screen, ctx) : -1;

            // 0 SCHLUESSEL: gleich zu den Kisten (Autor, 25.09.). Bisher
            // klickte der Turbo trotzdem "Participate" und merkte es erst nach
            // acht vergeblichen Anlaeufen. Wie in a/b nicht beim ersten Mal
            // glauben (140 als 10 gelesen, 06.09.): erst wenn auch das
            // naechste Bild 0 zeigt. Kostet nur bei 0 ein Bild.
            if (frisch == 0) {
                if (++turboNullGesehen < 2) {
                    ctx.sleep(tempo.ms(150));
                    return Outcome.WAITING;
                }
                turboNullGesehen = 0;
                return schluesselAlle(ctx, runs, 0);
            }
            if (frisch > 0) turboNullGesehen = 0;

            Outcome stauVorher = teilnahmeStau(screen, ctx, runs);
            if (stauVorher != null) return stauVorher;

            if (turboKlick(screen, ctx, T_PARTICIPATE, Sprache.t("Participate in the cathedral", "An der Kathedrale teilnehmen"))) {
                karteGefunden();
                runs.reiseBeginnt();   // ab hier zaehlt die Run-Zeit
                lernwerteAnwenden(ctx);
                zeit.start(ctx.gewartetMs());
                teilnahmeVersuche++;
                squadPrepared = false;
                floorsDone = 0;
                currentStage = 0;
                etageVergessen();
                runs.progress(Sprache.t("Participation confirmed", "Teilnahme bestaetigt"));
                warte(ctx, 600);
                return Outcome.ACTED;
            }
            // (Die Grenze fuer ergebnislose Anlaeufe prueft teilnahmeStau oben -- vor dem Klick, auch bei sichtbarem Knopf.)
            return Outcome.WAITING;
        }

        // Hier steht der Schluesselzaehler -- und nur hier. Der Bildschirm ist
        // aber bloss ein, zwei Sekunden offen, bevor "Participate" gedrueckt
        // wird. Deshalb: kurz warten, damit das Bild sicher steht, und ein
        // sofortiges Ablesen anfordern. Ohne das wurde der Stand in einem Lauf
        // ueber 17 Minuten genau einmal gelesen.
        if (!participationRead) {
            participationRead = true;
            ableseVersuche = 0;
            ctx.laufstatistik().vergissSchluessel();   // alten Stand vergessen, frisch lesen
            ctx.requestCounterRead();
            ctx.sleep(tempo.ms(COUNTER_READ_SETTLE_MS));
            return Outcome.ACTED;
        }

        long keys = ctx.laufstatistik().keysNow();

        // Steht die Zahl noch nicht (das Bild war beim ersten Blick noch nicht
        // ruhig), ein paar Takte lang erneut anfordern -- statt einmal lange zu
        // warten. Gelesen wird ohnehin erst im naechsten Motor-Takt. Nach
        // COUNTER_READ_MAX_TRIES gibt der Bot auf und spielt ohne den Stand
        // weiter (siehe unten: keys < 0).
        if (keys < 0 && ableseVersuche < COUNTER_READ_MAX_TRIES) {
            ableseVersuche++;
            ctx.requestCounterRead();
            ctx.sleep(tempo.ms(COUNTER_READ_SETTLE_MS));
            return Outcome.ACTED;
        }

        if (keys >= 0 && keys <= KEY_FLOOR) {

            // Nicht beim ersten Mal glauben -- siehe leerGesehen.
            if (++leerGesehen < 2) {
                Log.info("Kathedrale", Sprache.t(
                        "Key count " + keys + " read. This is checked once more on the "
                        + "next participation screen before anything happens.",
                        "Schluesselstand " + keys + " abgelesen. Das wird "
                        + "beim naechsten Teilnahmebildschirm noch einmal geprueft, bevor "
                        + "etwas passiert."));
                ableseVersuche = 0;
                ctx.laufstatistik().vergissSchluessel();
                ctx.requestCounterRead();
                ctx.sleep(tempo.ms(COUNTER_READ_SETTLE_MS));
                return Outcome.ACTED;
            }
            leerGesehen = 0;

            // Bestaetigt: die Schluessel sind wirklich alle. In der Tasche
            // liegen Kisten, die genau die nachliefern.
            return schluesselAlle(ctx, runs, keys);
        }

        // Ein brauchbarer Stand: Der Zaehler faengt wieder bei null an.
        if (keys > KEY_FLOOR) leerGesehen = 0;

        if (keys < 0 && !onCooldown("key_warn")) {
            Log.info("Kathedrale", Sprache.t(
                    "The key count could not be read. "
                    + "dc_key_icon and key_0 to key_9 are missing for that. The bot keeps "
                    + "playing anyway, but will not stop on its own.",
                    "Der Schluesselstand konnte nicht abgelesen werden. "
                    + "Dafuer fehlen dc_key_icon und key_0 bis key_9. Der Bot spielt "
                    + "trotzdem weiter, hoert aber nicht von selbst auf."));
            cooldowns.put("key_warn", System.currentTimeMillis() + 120_000);
        }

        Outcome stauVorher = teilnahmeStau(screen, ctx, runs);
        if (stauVorher != null) return stauVorher;

        if (click(screen, ctx, T_PARTICIPATE, Sprache.t("Participate in the cathedral", "An der Kathedrale teilnehmen"))) {
            teilnahmeVersuche++;
            runs.reiseBeginnt();   // ab hier zaehlt die Run-Zeit
            lernwerteAnwenden(ctx);
            zeit.start(ctx.gewartetMs());
            squadPrepared = false;
            floorsDone = 0;
            currentStage = 0;
            etageVergessen();
            runs.progress(Sprache.t("Participation confirmed", "Teilnahme bestaetigt"));
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    /**
     * Teilnehmen mit Nahrung (Teil A): kein Schluesselstand, die Kasse hat
     * Preis, Limit und Vorrat schon geprueft. Kommt nach mehreren Anlaeufen
     * keine Reise zustande, endet der Auftrag -- nie ins Leere klicken.
     */
    private Outcome mitNahrungTeilnehmen(Mat screen, BotContext ctx, RunTracker runs) {
        if (teilnahmeVersuche >= TEILNAHME_MAX_VERSUCHE) {
            teilnahmeVersuche = 0;
            warnung(Sprache.t(
                    "With food, no journey came about after " + TEILNAHME_MAX_VERSUCHE
                    + " attempts. The job ends here.",
                    "Mit Nahrung kam nach " + TEILNAHME_MAX_VERSUCHE
                    + " Anlaeufen keine Reise zustande. Der Auftrag endet hier."));
            return Outcome.FINISHED;
        }
        String grund = Sprache.t("Participate in the cathedral (with food)", "An der Kathedrale teilnehmen (mit Nahrung)");
        boolean geklickt = istTurbo() ? turboKlick(screen, ctx, T_PARTICIPATE, grund)
                                      : click(screen, ctx, T_PARTICIPATE, grund);
        if (!geklickt) geklickt = kasse.participateOberteil(screen, ctx, T_PARTICIPATE, grund);
        if (!geklickt) return Outcome.WAITING;
        kasse.teilgenommen();
        teilnahmeVersuche++;
        runs.reiseBeginnt();   // ab hier zaehlt die Run-Zeit
        lernwerteAnwenden(ctx);
        zeit.start(ctx.gewartetMs());
        squadPrepared = false;
        floorsDone = 0;
        currentStage = 0;
        etageVergessen();
        runs.progress(Sprache.t("Participation confirmed", "Teilnahme bestaetigt"));
        ctx.sleepUntilChanged(600);
        return Outcome.ACTED;
    }

    /**
     * Der Gang ins Hammer-Event (Event-Automatik): erst in die Burg, dann
     * spielt der Event-Modus, bis die Haemmer alle sind. Danach gleich die
     * Kisten aus dem Event in der Tasche oeffnen -- die Burg ist ja schon da.
     */
    private Outcome hammerEventSchritt(Mat screen, BotContext ctx, RunTracker runs) {
        if (eventUnterwegs) {
            if (Stoerfenster.afkUpdate(screen, ctx, "Kathedrale (zum Hammer-Event)")) return Outcome.ACTED;
            switch (Stoerfenster.anderesGeraet(screen, ctx, "Kathedrale (zum Hammer-Event)")) {
                case ANHALTEN -> { return Outcome.PAUSE; }
                case GEKLICKT -> { return Outcome.ACTED; }
                case NICHTS -> { }
            }
            // Freunde, 10.10.: die Zauberstab-Grenze gilt nur fuers Hammer-Event (Zauberstaebe gibt es nur dort).
            if (visibleAny(screen, ctx, T_MAP_BUTTON, T_MAP_BUTTON_ALT)
                    && !de.hustlebot.core.Eventautomatik.titan()
                    && de.hustlebot.core.Zauberstabziel.erreicht()) {
                // Zauberstab-Grenze erreicht (Autor, 29.09.): nicht mehr ins Event, weiter
                // mit der Kathedrale -- bis Autor die Grenze aendert oder ausschaltet.
                eventUnterwegs = false;
                eventLeer = true;
                runsBeiEventLeer = runs.completedRuns();
                de.hustlebot.core.Eventautomatik.eventFertig();
                Log.info("Kathedrale", de.hustlebot.core.Zauberstabziel.beschreibung()
                        + Sprache.t(" The event is skipped.", " Das Event wird ausgelassen."));
                runs.progress(Sprache.t("Wand limit reached -- no event", "Zauberstab-Grenze erreicht -- kein Event"));
                return Outcome.ACTED;
            }
            if (visibleAny(screen, ctx, T_MAP_BUTTON, T_MAP_BUTTON_ALT)) {
                eventUnterwegs = false;
                eventLaeuft = true;
                titanJetzt = de.hustlebot.core.Eventautomatik.titan();
                if (titanJetzt) {
                    titanEvent.reset();
                } else {
                    hammerEvent.reset();
                    // Autor, 29.09.: nach 25 im Event erhaltenen Nahrungskisten raus und Kathedrale
                    // spielen -- der Zaehler faengt bei jedem Gang ins Event wieder bei 0 an.
                    hammerEvent.nahrungsGrenze(de.hustlebot.core.Eventautomatik.nahrungskistenJeEvent());
                }
                // Autor, 24.10.: hoechstens N Stages je Gang (Einstellung) -- danach raus und weiter im Kreislauf.
                int grenze = titanJetzt ? 0 : de.hustlebot.core.Eventautomatik.stagesJeGang();
                eventZaehler = new RunTracker(grenze > 0 ? grenze : 9999);
                if (grenze > 0) Log.info("Kathedrale", Sprache.t("Hammer Event: at most " + grenze + " stages this visit.",
                        "Hammer-Event: hoechstens " + grenze + " Stages bei diesem Gang."));
                stagesZuletzt = 0;
                runs.progress(titanJetzt ? Sprache.t("In the castle -- off to the Titan Event", "In der Burg -- ins Titan-Event")
                                         : Sprache.t("In the castle -- off to the Hammer Event", "In der Burg -- ins Hammer-Event"));
                return Outcome.ACTED;
            }
            if (++eventWeg > 80) {
                eventUnterwegs = false;
                eventLeer = true;
                runsBeiEventLeer = runs.completedRuns();
                de.hustlebot.core.Eventautomatik.eventFertig();
                // Hauptbot (Pruefung 10.10.): beim Titan-Event nach 5 Runs erneut versuchen, nicht nach jedem Run.
                // Hammer (Autor, 24.10.): auch dort nach 5 Runs erneut, wenn der feste Abstand gilt.
                if (de.hustlebot.core.Eventautomatik.titan()) seitEventSetzen(Math.max(0, titanAlleRuns() - 5));
                else if (hammerAlleRuns() > 0) seitEventSetzen(Math.max(0, hammerAlleRuns() - 5));
                warnung(Sprache.t(
                        "The way to the castle (for the Hammer Event) was not found. The event is skipped.",
                        "Der Weg in die Burg (zum Hammer-Event) wurde nicht gefunden. "
                        + "Das Event wird ausgelassen."));
                return Outcome.ACTED;
            }
            // Rest der Nahrungskisten (gelber "Close", 30.09., 03:06) -- vor allem anderen.
            if (ctx.clickIfVisibleIfKnown(screen, "bag_close", Sprache.t("Close the chest screen (for the Hammer Event)", "Kisten-Bildschirm schliessen (zum Hammer-Event)"))) {
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            if (ctx.clickIfVisibleIfKnown(screen, "dc_close_x", Sprache.t("Close the cathedral window (for the Hammer Event)", "Kathedralen-Fenster schliessen (zum Hammer-Event)"))) {
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            // Weltkarte auch dann erkennen, wenn Kathedrale und "Home" nicht im Bild sind
            // (30.09., 02:12: nach dem Kolosseum stand die Karte verschoben, worldmap_home
            // passt dort nur 0,72 -- 18 s nichts erkannt, "Event wird ausgelassen").
            if (ctx.visibleIfKnown(screen, T_NODE) || ctx.visibleIfKnown(screen, "worldmap_home")
                    || Weltkarte.erkannt(screen, ctx)) {
                Weltkarte.heim(screen, ctx, Sprache.t("Cathedral (Hammer Event)", "Kathedrale (Hammer-Event)"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_CLOSE_X, Sprache.t("Close the window (for the Hammer Event)", "Fenster schliessen (zum Hammer-Event)"))) {
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            ctx.sleep(tempo.ms(300));
            return Outcome.WAITING;
        }

        if (titanJetzt) return titanEventSchritt(screen, ctx, runs);
        Outcome e = hammerEvent.step(screen, ctx, eventZaehler);
        int stagesJetzt = eventZaehler.completedRuns();
        if (stagesJetzt > stagesZuletzt) stagesGesamt += stagesJetzt - stagesZuletzt;
        stagesZuletzt = stagesJetzt;
        if (e != Outcome.FINISHED && e != Outcome.FATAL) {
            // Code-Pruefung 22.10. (wie beim Titan-Event seit 10.10.): nur echten Fortschritt weiterreichen (Vase,
            // Kiste, Lupe, Hammer ...) -- vorher zaehlte jeder Schritt, und der 15-Minuten-Haenger-Alarm griff im
            // Hammer-Event nie, auch wenn es irgendwo festhing.
            if (eventZaehler.idleSeconds() < 30) runs.progress(Sprache.t("Hammer Event", "Hammer-Event"));
            return e;
        }
        eventLaeuft = false;
        de.hustlebot.core.Eventautomatik.eventFertig();
        int faesser = hammerEvent.vasenZerschlagen();
        if (faesser == 0 && !hammerEvent.haemmerAlle() && eventAbbrueche < 2) {
            // Kein Fass zerschlagen, aber auch nicht "Haemmer alle" -- das Event ist aus
            // einem anderen Grund abgebrochen (Fenster, Material-Truhe). Noch einmal hin,
            // statt den Kreislauf mit Verlies-Runs zu beenden (29.09., 20:13).
            eventAbbrueche++;
            eventUnterwegs = true;
            eventWeg = 0;
            de.hustlebot.core.Eventautomatik.eventZuerstMerken();
            warnung(Sprache.t(
                    "The Hammer Event broke off without the hammers being used up -- "
                    + "going in again (attempt " + eventAbbrueche + ").",
                    "Das Hammer-Event brach ab, ohne dass die Haemmer alle waren -- "
                    + "noch einmal hinein (" + eventAbbrueche + ". Mal)."));
            return Outcome.ACTED;
        }
        if (faesser == 0) {
            // Autor, 30.09.: ohne Haemmer zurueck in die Kathedrale -- Schluessel, dann weitere
            // 15 Mio Nahrung, Kisten, und spaeter wieder ins Event. KEINE Verlies-Runs.
            eventLeer = true;
            runsBeiEventLeer = runs.completedRuns();
            seitEventSetzen(0);   // Autor, 24.10.: der feste Abstand zaehlt ab hier neu
            de.hustlebot.core.Bezahlung.rundeNeu("cathedral");
            letzterGangLeer = false;
            truhenGaenge = 0;
            keysExhausted = false;
            kasse.nahrungWiederErlauben();
            leerGesehen = 0;
            turboNullGesehen = 0;
            teilnahmeVersuche = 0;
            Log.info("Kathedrale", Sprache.t(
                    "Nothing left to do in the Hammer Event (no hammers). Back to the "
                    + "cathedral with a fresh food budget.",
                    "Im Hammer-Event ging nichts mehr (keine Haemmer). Zurueck in die "
                    + "Kathedrale mit neuem Nahrungs-Budget."));
            return Outcome.ACTED;
        }
        eventAbbrueche = 0;
        seitEventSetzen(0);   // Autor, 24.10.: der feste Abstand (alle N Kathedralen) zaehlt ab hier neu
        // Im Event gibt es keine Schluessel-Kisten, nur Schluessel (Autor,
        // 28.09.) -- also gleich weiter Kathedrale, nicht erst in die Tasche.
        // Die naechsten Kathedralen-Runs bringen wieder Kisten: die Tasche
        // darf danach wieder durchsucht werden.
        Log.info("Kathedrale", Sprache.t(
                "Hammer Event done (" + faesser + " barrels smashed). Continuing with the "
                + "cathedral using the keys from it.",
                "Hammer-Event fertig (" + faesser + " Faesser zerschlagen). Weiter "
                + "Kathedrale mit den Schluesseln daraus."));
        letzterGangLeer = false;
        truhenGaenge = 0;
        keysExhausted = false;
        de.hustlebot.core.Bezahlung.rundeNeu("cathedral");   // Autor, 30.09.: je Runde wieder 15 Mio
        kasse.nahrungWiederErlauben();   // Autor: nach dem Event Food erneut, dann wieder Schluessel
        leerGesehen = 0;
        turboNullGesehen = 0;
        teilnahmeVersuche = 0;
        runs.progress(Sprache.t("Hammer Event done", "Hammer-Event fertig"));
        return Outcome.ACTED;
    }

    /**
     * Titan-Event statt Hammer-Event (Hauptbot 09.10., Freunde 10.10.): Boss mit den Tickets, dann alle
     * Spitzhacken gerade runter. Es bringt keine Schluessel -- danach gilt das Event als "leer" wie ein
     * Hammer-Event ohne Haemmer: weiter Kathedrale, und erst nach neuen Runs wieder hinein.
     */
    private Outcome titanEventSchritt(Mat screen, BotContext ctx, RunTracker runs) {
        Outcome e = titanEvent.step(screen, ctx, eventZaehler);
        if (e != Outcome.FINISHED && e != Outcome.FATAL) {
            // Nur echten Fortschritt weiterreichen (Spitzhacken gesunken, Boss, Werkzeug ...) -- sonst greift der
            // Haenger-Alarm im Event nie. 30 s deckt einen Dauerklick-Schub ab (Hauptbot, 10.10.).
            if (eventZaehler.idleSeconds() < 30) runs.progress(Sprache.t("Titan Event", "Titan-Event"));
            return e;
        }
        eventLaeuft = false;
        titanJetzt = false;
        de.hustlebot.core.Eventautomatik.eventFertig();
        eventAbbrueche = 0;
        eventLeer = true;
        runsBeiEventLeer = runs.completedRuns();
        // Abgebrochen (Haenger, Lesefehler)? Dann nach 5 Runs wieder hin, nicht erst nach dem ganzen Abstand.
        seitEventSetzen(titanEvent.abgebrochen() ? Math.max(0, titanAlleRuns() - 5) : 0);
        letzterGangLeer = false;
        truhenGaenge = 0;
        keysExhausted = false;
        leerGesehen = 0;
        turboNullGesehen = 0;
        teilnahmeVersuche = 0;
        String wie = titanEvent.endeHinweis();
        Log.info("Kathedrale", Sprache.t(
                "Titan Event done" + (wie != null ? " (" + wie + ")" : "") + ". Continuing with the cathedral.",
                "Titan-Event fertig" + (wie != null ? " (" + wie + ")" : "") + ". Weiter Kathedrale."));
        runs.progress(Sprache.t("Titan Event done", "Titan-Event fertig"));
        return Outcome.ACTED;
    }

    // --- Titan-Event alle N Kathedralen-Runs (Hauptbot 02./09.10., Freunde 10.10.) -------------------------------
    // Die Spitzhacken kommen aus den Kathedralen-Runs. Ohne festen Abstand ginge die Freunde-Fassung erst ins Event,
    // wenn Schluessel und Kisten alle sind -- bei vielen Schluesseln vielleicht erst nach dem Event-Ende.
    // Abstand selbst: Eventautomatik.titanAlleRuns() / hammerAlleRuns() (Einstellung, Autor 24.10.).
    /** Runs seit dem letzten Event-Besuch. Ueberlebt Neustarts des Bots. */
    private static final java.nio.file.Path SEIT_EVENT_DATEI = java.nio.file.Path.of("kathedrale-seit-event.txt");
    private int runsSeitEvent = seitEventLaden();

    /**
     * Wie viele Kathedralen-Runs bis zum naechsten Titan-Besuch: Einstellung "titanevent" (event-titan-alle.txt, sonst
     * "alle=" aus event-plan.txt, sonst 100; 0 = nur wenn alles leer). Endspurt wie
     * im Hauptbot (Autor, 09.10.): in den letzten 12 h alle 20 Runs, in der letzten Dreiviertelstunde nach jedem Run --
     * am Ende darf nichts uebrig sein. Das Event-Ende liest die Grabung oben links ab (Titanende).
     */
    private int titanAlleRuns() {
        // Seit 24.10. aus der Einstellung (event-titan-alle.txt, sonst "alle=" aus event-plan.txt, sonst 100; 0 = aus).
        int alle = de.hustlebot.core.Eventautomatik.titanAlleRuns();
        long rest = de.hustlebot.core.Titanende.restMinuten();
        if (rest >= 0 && rest <= 45) return 1;
        if (rest >= 0 && rest <= 12 * 60) return alle > 0 ? Math.min(alle, 20) : 20;
        return alle;
    }

    /** Hammer-Event alle N Kathedralen-Runs (Autor, 24.10.; Einstellung, 0 = aus). */
    private int hammerAlleRuns() {
        return de.hustlebot.core.Eventautomatik.hammerAlleRuns();
    }

    private static int seitEventLaden() {
        try {
            return Integer.parseInt(de.hustlebot.core.Dateien.text(SEIT_EVENT_DATEI).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void seitEventSetzen(int n) {
        runsSeitEvent = n;
        try {
            java.nio.file.Files.writeString(SEIT_EVENT_DATEI, n + "\n");
        } catch (Exception e) {
            Log.debug("Kathedrale", "kathedrale-seit-event.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /** Wann zuletzt die Kathedrale auf der Weltkarte angeklickt wurde. */
    private long knotenGeklicktUm = 0;

    /**
     * Die Kathedrale ist offen (Teilnahme-Fenster, Etagenkarte): Die Suche auf
     * der Weltkarte ist vorbei.
     *
     * WARUM (Langlauf 28.09., 18:36): Nach dem Klick auf die Kathedrale zeigte
     * das naechste Bild noch kurz die Weltkarte -- der Bot fing an zu suchen
     * (Zug 1 von 12), obwohl er schon drin war. Die Suche wurde danach nie
     * beendet: Nach dem naechsten Run zog er weiter (Zug 6 bis 12) und brach
     * mit "Kathedrale nicht gefunden" ab, obwohl sie offen vor ihm lag.
     */
    private void karteGefunden() {
        searchingCathedral = false;
        mapSwipes = 0;
    }

    /** Weltkarte: die Kathedrale finden, notfalls die Karte verschieben. */
    private Outcome handleWorldMap(Mat screen, BotContext ctx, RunTracker runs) {
        if (click(screen, ctx, T_NODE, Sprache.t("Open the Dark Cathedral", "Dunkle Kathedrale oeffnen"))) {
            searchingCathedral = false;
            mapSwipes = 0;
            burgStattKarte = 0;
            knotenGeklicktUm = System.currentTimeMillis();
            runs.progress(Sprache.t("Cathedral opened", "Kathedrale geoeffnet"));
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }

        // SIND WIR UEBERHAUPT AUF DER WELTKARTE?
        //
        // Ist der "Map"-Knopf der Burg noch zu sehen, dann nicht -- der
        // Klick darauf hat nicht gegriffen, etwa weil eine Einblendung im
        // Weg lag. Bisher hat der Bot das gar nicht geprueft: er hat
        // searchingCathedral gesetzt, sobald er auf "Map" geklickt hatte,
        // und ist von da an in diesen Zweig gelaufen, egal was auf dem
        // Bildschirm stand.
        //
        // In der Nacht zum 21.09. hat genau das nach 76 Durchlaeufen den
        // Lauf beendet: der Bot hat sich die BURG achtmal zur Seite
        // gezogen, dort natuerlich keine Kathedrale gefunden und
        // abgebrochen. Autor hat es auf dem Bildschirm gesehen -- "er denkt
        // er hat auf die karte gedrueckt, ist aber noch in der burg".
        //
        // Jetzt geht es zurueck in den Burg-Zweig, wo "Map" erneut gedrueckt
        // wird -- und wo auch die Stoerfenster weggeklickt werden, die den
        // ersten Klick verschluckt haben koennten.
        if (visibleAny(screen, ctx, T_MAP_BUTTON, T_MAP_BUTTON_ALT)) {
            searchingCathedral = false;
            burgStattKarte++;
            if (burgStattKarte >= MAX_BURG_STATT_KARTE) {
                Log.error("Kathedrale", Sprache.t(
                        "The \"Map\" button was pressed " + burgStattKarte
                        + " times, but the castle stays. There is probably a pop-up "
                        + "over it that the bot does not know. Please take a look at "
                        + "what is on the screen.",
                        "Der Knopf \"Map\" wurde " + burgStattKarte
                        + " Mal gedrueckt, aber die Burg bleibt stehen. Da liegt "
                        + "vermutlich eine Einblendung darueber, die der Bot nicht "
                        + "kennt. Bitte einmal nachsehen, was auf dem Bildschirm ist."));
                return Outcome.FATAL;
            }
            if (!onCooldown("burg_statt_karte")) {
                cooldowns.put("burg_statt_karte", System.currentTimeMillis() + 10_000);
                warnung(Sprache.t(
                        "The click on \"Map\" did not register -- the castle is still "
                        + "visible. Pressing it again "
                        + "(attempt " + burgStattKarte + " of " + MAX_BURG_STATT_KARTE + ").",
                        "Der Klick auf \"Map\" hat nicht gegriffen -- die "
                        + "Burg ist noch zu sehen. Es wird noch einmal gedrueckt "
                        + "(Versuch " + burgStattKarte + " von " + MAX_BURG_STATT_KARTE + ")."));
            }
            return Outcome.WAITING;
        }

        // ERST LADEN LASSEN (Test 25.09., 21:47, nach dem Kisten-Gang): 0,2 s
        // nach dem Klick auf "Map" zog der Bot schon an der Karte, die noch
        // gar nicht fertig da war -- und schob die Kathedrale damit aus dem
        // Bild. 12 Zuege spaeter: "nicht gefunden", Abbruch. Aus der Burg
        // steht die Kathedrale sonst gleich im Bild (615,679 oder 1004,679).
        if (mapSwipes == 0 && System.currentTimeMillis() - karteGeoeffnetUm < KARTE_LADEN_MS) {
            ctx.sleep(tempo.ms(200));
            return Outcome.WAITING;
        }

        if (mapSwipes >= MAX_MAP_SWIPES) {
            Log.error("Kathedrale", Sprache.t(
                    "The Dark Cathedral was not found on the world map. "
                    + "Please check whether the template dc_node still matches.",
                    "Die Dunkle Kathedrale wurde auf der Weltkarte nicht "
                    + "gefunden. Bitte pruefen, ob die Vorlage dc_node noch passt."));
            return Outcome.FATAL;
        }

        // Liegt statt der Weltkarte ein fremdes Fenster offen (29.09., 19:36: der
        // Diamanten-Shop nach einem Fehlklick), hilft kein Ziehen -- erst das rote X
        // oben rechts. Hoechstens dreimal je Auftrag, damit es nie im Kreis laeuft.
        if (fremdeFensterZu < 3 && !Weltkarte.erkannt(screen, ctx)
                && ctx.clickIfVisibleIfKnown(screen, "close_x", Sprache.t("Close a foreign window (instead of the world map)",
                                                                          "Fremdes Fenster schliessen (statt der Weltkarte)"))) {
            fremdeFensterZu++;
            mapSwipes = 0;
            warnung(Sprache.t(
                    "Another window was open instead of the world map -- closed with the red X.",
                    "Statt der Weltkarte lag ein anderes Fenster offen -- mit dem roten X geschlossen."));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        mapSwipes++;
        int y = ctx.height() / 2;

        // WEGWEISER VERLIES. Die Kathedrale liegt auf der Weltkarte immer
        // links vom Verlies (23.09.: Verlies bei x=580, Kathedrale bei x=190,
        // halb hinter dem Home-Knopf). Ist das Verlies zu sehen, wird die
        // Karte gezielt nach rechts gezogen -- dann rutscht die Kathedrale
        // ins Bild. Vorher zog der Bot blind immer nach links und schob sie
        // dabei ganz hinaus: "Die Dunkle Kathedrale wurde auf der Weltkarte
        // nicht gefunden", Abbruch.
        Optional<Matcher.Hit> verlies = ctx.matcher().has("dungeon_node")
                ? ctx.matcher().find(screen, "dungeon_node", 0.85)
                : Optional.empty();
        boolean nachRechts;
        String grund;
        if (verlies.isPresent()) {
            nachRechts = true;
            grund = Sprache.t("the dungeon is visible, the cathedral lies to its left",
                              "das Verlies ist zu sehen, die Kathedrale liegt links davon");
        } else {
            // Ohne Wegweiser: erst dreimal in die eine, dann in die andere
            // Richtung -- wie im Verlies.
            nachRechts = mapSwipes > 3;
            grund = Sprache.t("no signpost, " + (nachRechts ? "opposite direction" : "first direction"),
                              "ohne Wegweiser, " + (nachRechts ? "Gegenrichtung" : "erste Richtung"));
        }
        Log.action("Karte", Sprache.t(
                "Dragging the world map to the " + (nachRechts ? "right" : "left")
                + " to find the cathedral (" + grund + ", swipe " + mapSwipes
                + " of " + MAX_MAP_SWIPES + ").",
                "Weltkarte wird nach " + (nachRechts ? "rechts" : "links")
                + " gezogen, um die Kathedrale zu finden (" + grund + ", Zug " + mapSwipes
                + " von " + MAX_MAP_SWIPES + ")."));
        int links = (int) (ctx.width() * 0.25);
        int rechts = (int) (ctx.width() * 0.75);
        if (nachRechts) ctx.input().drag(links, y, rechts, y);
        else ctx.input().drag(rechts, y, links, y);
        ctx.sleepUntilChanged(400);
        return Outcome.ACTED;
    }

    /**
     * "Gather troops" zu Beginn einer Reise.
     *
     * Autor will hier ausdruecklich "Previous squad" statt "Deploy the best":
     * die zuletzt genutzte Aufstellung ist die eingespielte.
     */
    private Outcome handleGatherTroops(Mat screen, BotContext ctx, RunTracker runs) {
        // Welcher Knopf: Autors Einstellung (!settings kathedrale ...).
        //
        // "previous": Die Aufstellung vom letzten Mal steht ohnehin schon,
        // also gleich bestaetigen -- ein Klick auf "Previous Squad" kostet nur
        // Zeit. So war es bisher, und so bleibt es als Standard.
        // "best": erst die staerkste Truppe stellen, dann bestaetigen.
        // Turbo (Weg c): Aufstellen und Bestaetigen in EINEM Schritt.
        if (istTurbo()) {
            if (!Aufstellung.istPrevious("cathedral")
                    && turboKlick(screen, ctx, T_DEPLOY_BEST, Sprache.t("Best troops (Gather troops)", "Beste Truppen (Gather troops)"))) {
                runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));
                screen = turboFrisch(screen, ctx);
            }
            if (turboKlick(screen, ctx, T_CONFIRM, Sprache.t("Confirm the squad", "Aufstellung bestaetigen"))
                    || turboKlick(screen, ctx, T_CONFIRM_ALT,
                                  Sprache.t("Confirm the squad (dungeon Confirm)", "Aufstellung bestaetigen (Verlies-Confirm)"))) {
                runs.progress(Sprache.t("Troops confirmed", "Truppen bestaetigt"));
                ladebildErwarten();   // Autor 24.10. (B04): die Etage laedt, das Bild ist kurz leer
                warte(ctx, 500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // AUFSTELLEN UND BESTAETIGEN GEHOEREN IN EINEN SCHRITT.
        //
        // Vorher stand hier: "Deploy the best" klicken, Schritt zu Ende.
        // Beim naechsten Bild war der Knopf aber IMMER NOCH DA -- er
        // verschwindet durch das Aufstellen nicht. Also wurde wieder er
        // geklickt, und der Bestaetigen-Knopf darunter kam NIE an die Reihe.
        //
        // Bei Autors Freund lief das am 19.09. einundfuenfzig Mal
        // hintereinander: immer dieselbe Stelle 458,371, Uebereinstimmung
        // 0,999 -- bis er den Not-Aus gedrueckt hat.
        //
        // Es lag also weder an einer zu hohen Schwelle noch an ueberlappenden
        // Fenstern: der Knopf wurde einwandfrei erkannt. Der Bot hat den
        // Bestaetigen-Knopf schlicht nie gesucht.
        //
        // Bei Autor faellt das nicht auf, weil dort "previous" eingestellt
        // ist -- dann wird dieser Zweig uebersprungen und es geht sofort zum
        // Bestaetigen. Derselbe Bot, andere Einstellung, anderes Verhalten.
        // Der Weg c hatte das oben schon richtig; jetzt gilt es ueberall.
        if (!Aufstellung.istPrevious("cathedral")
                && aufstellVersuche < AUFSTELL_MAX_VERSUCHE
                && clickAny(screen, ctx, T_DEPLOY_BEST, Sprache.t("Deploy the best troops (Gather troops)", "Beste Truppen aufstellen (Gather troops)"))) {
            aufstellVersuche++;
            runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));
            ctx.sleepUntilChanged(400);
            Mat frisch = ctx.refresh();
            if (frisch != null) screen = frisch;
        }

        if (click(screen, ctx, T_CONFIRM, Sprache.t("Confirm the squad", "Aufstellung bestaetigen"))
                || click(screen, ctx, T_CONFIRM_ALT, Sprache.t("Confirm the squad (dungeon Confirm)", "Aufstellung bestaetigen (Verlies-Confirm)"))) {
            aufstellVersuche = 0;
            runs.progress(Sprache.t("Troops confirmed", "Truppen bestaetigt"));
            ladebildErwarten();   // Autor 24.10. (B04): die Etage laedt, das Bild ist kurz leer
            ctx.sleepUntilChanged(500);
            return Outcome.ACTED;
        }

        // Notbremse. Wird der Bestaetigen-Knopf auch nach mehreren Anlaeufen
        // nicht gefunden, hoert das Klicken auf "Deploy the best" auf.
        // Einundfuenfzig Mal denselben Knopf zu druecken hilft niemandem --
        // und im Protokoll sieht es aus, als laege es am Knopf.
        if (aufstellVersuche >= AUFSTELL_MAX_VERSUCHE && !onCooldown("aufstell_warn")) {
            cooldowns.put("aufstell_warn", System.currentTimeMillis() + 30_000);
            warnung(Sprache.t(
                    "The troops are deployed, but the Confirm button "
                    + "is not found. The bot stops pressing "
                    + "\"Deploy the best\". Please capture the Confirm button below the "
                    + "squad screen again with Capture-Images.exe as dc_confirm.",
                    "Die Truppen sind gestellt, aber der Bestaetigen-Knopf "
                    + "wird nicht gefunden. Der Bot drueckt jetzt nicht weiter auf "
                    + "\"Deploy the best\". Bitte den Bestaetigen-Knopf unter dem "
                    + "Truppenbildschirm mit Capture-Images.exe als dc_confirm neu aufnehmen."));
        }
        return Outcome.WAITING;
    }

    /** So oft wird "Deploy the best" hoechstens gedrueckt, bevor Schluss ist. */
    private static final int AUFSTELL_MAX_VERSUCHE = 3;

    /** Wie oft "Deploy the best" auf diesem Bildschirm schon gedrueckt wurde. */
    private int aufstellVersuche;

    /**
     * Truppenbildschirm an einem Wegpunkt.
     *
     * Meistens stehen die Einheiten schon oben, weil sie den letzten Kampf
     * ueberlebt haben. Dann geht es sofort in den Kampf, ohne erst aufzustellen
     * -- das spart bei jedem Wegpunkt ein paar Sekunden.
     *
     * Gefallene Einheiten werden vorher wiederbelebt, in Autors Reihenfolge.
     */
    private Outcome handleSquad(Mat screen, BotContext ctx, RunTracker runs) {

        // Wiederbeleben hat Vorrang: mit einer Einheit weniger kaempft es sich
        // schlechter, und der Seelenstein ist reichlich vorhanden.
        Outcome revived = tryRevive(screen, ctx, runs);
        if (revived != null) return revived;

        // Turbo (Weg c): Aufstellen und Losschlagen in EINEM Schritt, mit einem
        // frischen Bild dazwischen. Das spart je Wegpunkt einen ganzen
        // Durchlauf der Schrittkette. Faellt auf den normalen Weg zurueck, wenn
        // ein Knopf gerade nicht zu sehen ist.
        if (istTurbo()) {
            // Autor, 26.09., mit Video: "bei jedem Stuetzpunkt, bevor er auf Quick
            // Attack drueckt, kurz auf Deploy the best druecken" -- sonst kam
            // nach einer gefallenen Einheit die Warnung "incomplete squad".
            // squadDeployed() hielt den Trupp da noch fuer vollstaendig. Bei
            // "best" also IMMER aufstellen; bei "previous" wie bisher nur, wenn
            // sichtbar etwas fehlt (Previous Squad fuellt Luecken nicht auf).
            boolean immerAufstellen = !Aufstellung.istPrevious("cathedral");
            if (immerAufstellen || !squadDeployed(screen, ctx)) {
                boolean previous = Aufstellung.istPrevious("cathedral");
                String erster = previous ? T_PREV_SQUAD : T_DEPLOY_BEST;
                String zweiter = previous ? T_DEPLOY_BEST : T_PREV_SQUAD;
                String grund = previous ? Sprache.t("Previous squad (Previous Squad)", "Letzte Aufstellung (Previous Squad)")
                                        : Sprache.t("Best troops (Deploy the best)", "Beste Truppen (Deploy the best)");
                // Live-Test 16.10. (Foto): im Kampf-Aufstellungsfenster der Kathedrale gibt es nur "Deploy the best" --
                // "Previous squad" gibt es nur am Reisebeginn ("Gather troops"). Dann nimmt "Deploy the best" die Besten
                // aus der Reise-Truppe; so steht es jetzt auch im Protokoll (vorher hiess es irrefuehrend "Previous squad").
                String grundZweiter = previous ? Sprache.t("Deploy the best (from your journey squad -- there is no Previous squad in the fight)",
                                                          "Deploy the best (aus der Reise-Truppe -- im Kampf gibt es kein Previous squad)")
                                               : grund;
                boolean aufgestellt = turboKlick(screen, ctx, erster, grund)
                        || turboKlick(screen, ctx, zweiter, grundZweiter);
                // AUFSTELLEN NICHT GEFUNDEN (Test 27.09., 04:43): In 5 von 74
                // Kaempfen war auf dem ersten Bild des Truppenbildschirms kein
                // Knopf zum Aufstellen zu finden -- das Fenster blendete noch
                // ein. Der Bot schlug sofort los, OHNE aufzustellen, einmal
                // sogar ueber "To battle!" (Kampf in voller Laenge, 30 s).
                // Jetzt wartet er bis zu AUFSTELLEN_GEDULD_MS auf den Knopf.
                long wartetSeit = System.currentTimeMillis();
                while (!aufgestellt && System.currentTimeMillis() - wartetSeit < AUFSTELLEN_GEDULD_MS) {
                    screen = turboFrischSchnell(screen, ctx, 40);
                    aufgestellt = turboKlick(screen, ctx, erster, grund)
                            || turboKlick(screen, ctx, zweiter, grund);
                    if (aufgestellt) {
                        Log.debug("Kathedrale", "Aufstellen erst nach "
                                + (System.currentTimeMillis() - wartetSeit) + " ms gefunden.");
                    }
                }
                if (aufgestellt) {
                    aufgestelltUm = System.currentTimeMillis();
                    runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));

                    // KASTENBLICK (27.09.): Stand die Truppe schon (der
                    // Normalfall), aendert sich nach dem Aufstellen nichts am
                    // Bild -- der Bot wartete trotzdem jedes Mal die vollen
                    // 200 ms auf eine Aenderung, 22 Mal je Run. Jetzt: kurze
                    // feste Pause, dann auf "Quick Attack" sehen und druecken.
                    // Seit dem Test 04:43 nicht mehr nur EIN Blick: Ist der
                    // Knopf noch nicht da, wird bis zu ANGRIFF_GEDULD_MS
                    // gewartet, statt auf "To battle!" auszuweichen.
                    if (kastenblick) {
                        ctx.sleep(tempo.ms(aufstellenMs));
                        long angriffSeit = System.currentTimeMillis();
                        Optional<Matcher.Hit> angriff =
                                ctx.kastenBlick(0.84, T_QUICK_ATTACK, T_QUICK_ATTACK_ALT);
                        if (angriff.isEmpty()) {
                            angriff = ctx.warteAufKnopf(ANGRIFF_GEDULD_MS, 0.84,
                                    T_QUICK_ATTACK, T_QUICK_ATTACK_ALT);
                        }
                        traegheitMessen(System.currentTimeMillis() - angriffSeit);
                        if (angriff.isPresent()) {
                            Log.action("Turbo", Sprache.t("Start battle (", "Kampf starten (") + angriff.get().name()
                                    + Sprache.t(", box look)", ", Kastenblick)"));
                            ctx.input().click(angriff.get().centerX(), angriff.get().centerY(), 4);
                            kampfGestartet(runs);
                            return kampfAbwarten(ctx, runs);
                        }
                    }
                    screen = turboFrischSchnell(screen, ctx, ketteMs);   // 24.09.: erst wenn die Truppen sichtbar stehen (Bildwechsel), hoechstens 200 ms
                }
            }
            if (startBattle(screen, ctx, runs)) {
                return kastenblick ? kampfAbwarten(ctx, runs) : Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        if (squadDeployed(screen, ctx)) {
            if (startBattle(screen, ctx, runs)) return Outcome.ACTED;
            return Outcome.WAITING;
        }

        // FREUNDE-FASSUNG: Ohne eigene Kaempferbilder (Fighter 1 bis 4) kann
        // squadDeployed() nie "steht schon" sagen. Dann drueckte der Bot hier
        // "Deploy the best", gleich danach "Previous squad" -- das die beste
        // Aufstellung wieder ueberschreibt. Ohne diese Bilder gilt deshalb:
        // einmal aufgestellt heisst aufgestellt.
        if (!kenntEigeneKaempfer(ctx)
                && System.currentTimeMillis() - aufgestelltUm < AUFGESTELLT_GILT_MS) {
            if (startBattle(screen, ctx, runs)) return Outcome.ACTED;
            return Outcome.WAITING;
        }

        // Truppen stehen noch nicht: erst aufstellen. Welcher Knopf zuerst --
        // Autors Einstellung (!settings kathedrale ...). Der andere bleibt als
        // Rueckfall, falls der gewaehlte gerade nicht zu sehen ist.
        boolean previous = Aufstellung.istPrevious("cathedral");
        String ersterKnopf = previous ? T_PREV_SQUAD : T_DEPLOY_BEST;
        String ersterGrund = previous ? Sprache.t("Use the previous squad (Previous Squad)", "Letzte Aufstellung uebernehmen (Previous Squad)")
                                       : Sprache.t("Deploy the best troops (Deploy the best)", "Beste Truppen aufstellen (Deploy the best)");
        String zweiterKnopf = previous ? T_DEPLOY_BEST : T_PREV_SQUAD;
        String zweiterGrund = previous ? Sprache.t("Deploy the best troops (Deploy the best)", "Beste Truppen aufstellen (Deploy the best)")
                                       : Sprache.t("Use the previous squad (Previous Squad)", "Letzte Aufstellung uebernehmen (Previous Squad)");
        if (clickAny(screen, ctx, ersterKnopf, ersterGrund)) {
            aufgestelltUm = System.currentTimeMillis();
            runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));
            ctx.sleepUntilChanged(400);
            return Outcome.ACTED;
        }
        if (clickAny(screen, ctx, zweiterKnopf, zweiterGrund)) {
            aufgestelltUm = System.currentTimeMillis();
            runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));
            ctx.sleepUntilChanged(400);
            return Outcome.ACTED;
        }

        // Autor 17.10. (Punkt 7): "Deploy the best" robust nachsehen bzw. an seiner bekannten Stelle tippen.
        Optional<int[]> aufstellenErsatz = knopfErsatz(screen, ctx, T_DEPLOY_BEST);
        if (aufstellenErsatz.isPresent()) {
            ersatzTippen(ctx, aufstellenErsatz.get(), T_DEPLOY_BEST, Sprache.t("Deploy the best", "Beste Truppen aufstellen"));
            aufgestelltUm = System.currentTimeMillis();
            runs.progress(Sprache.t("Troops deployed", "Truppen aufgestellt"));
            return Outcome.ACTED;
        }

        // Nichts zum Aufstellen gefunden. Lieber kaempfen als stehenbleiben --
        // die Warnung wegen zu weniger Einheiten wird oben mit "Yes" bejaht.
        if (!onCooldown("deploy_warn")) {
            warnung(Sprache.t(
                    "The units do not seem to be deployed, but "
                    + "no deploy button was found. Fighting anyway. "
                    + "Please capture dc_deploy_best with Capture-Images.exe.",
                    "Die Einheiten scheinen nicht aufgestellt zu sein, aber "
                    + "es wurde kein Knopf zum Aufstellen gefunden. Es wird trotzdem "
                    + "gekaempft. Bitte dc_deploy_best mit Capture-Images.exe aufnehmen."));
            cooldowns.put("deploy_warn", System.currentTimeMillis() + 30_000);
        }
        if (startBattle(screen, ctx, runs)) return Outcome.ACTED;
        return Outcome.WAITING;
    }

    /** Der Kampf laeuft: die Etagenkarte ist gesperrt, bis "Home" bestaetigt ist. */
    private void kampfGestartet(RunTracker runs) {
        // Ab jetzt ist die Etagenkarte gesperrt, bis das Kampfergebnis
        // mit "Home" bestaetigt ist. Sonst klickt der Bot waehrend des
        // Siegesbanners auf den naechsten Stuetzpunkt dahinter.
        battlePending = true;
        kampfGestartetUm = System.currentTimeMillis();
        siegTipps = 0;
        floorMapBlocked = 0;
        aufgestelltUm = 0;
        runs.progress(Sprache.t("Battle started", "Kampf gestartet"));
        zeit.phase(Zeitbericht.KAMPF);
    }

    /**
     * KASTENBLICK-KETTE (27.09.): Kampf -> "Home" -> Segen -> Bestaetigen ->
     * Etagenkarte, ohne zwischendurch die ganze Schrittkette zu durchlaufen.
     * Gesehen wird an jeder Stelle, nur eben allein der erwartete Knopf. Kommt
     * er nicht (Niederlage ohne Segen, Warnfenster), endet die Kette,
     * und der normale Ablauf sieht sich das ganze Bild an.
     */
    private Outcome kampfAbwarten(BotContext ctx, RunTracker runs) {
        // Karla 17.10.: nach einem vollen Kampf steht dort "Back" -- bisher wartete die Kette darauf 3,5 s umsonst.
        Optional<Matcher.Hit> home = ctx.matcher().has(T_KAMPF_BACK)
                ? ctx.warteAufKnopf(KASTEN_HOME_MS, 0.84, T_HOME, T_KAMPF_BACK)
                : ctx.warteAufKnopf(KASTEN_HOME_MS, 0.84, T_HOME);
        if (home.isEmpty()) return Outcome.ACTED;

        Log.action("Klick", Sprache.t("Confirm battle result  [", "Kampfergebnis bestaetigen  [") + home.get().name()
                + Sprache.t(" at ", " bei ")
                + home.get().centerX() + "," + home.get().centerY() + String.format(
                Sprache.t(", match %.3f, box look after %d ms]", ", Uebereinstimmung %.3f, Kastenblick nach %d ms]"),
                home.get().score(), System.currentTimeMillis() - kampfGestartetUm));
        ctx.input().click(home.get().centerX(), home.get().centerY(), 4);
        battlePending = false;
        floorMapBlocked = 0;
        cooldowns.put("after_battle", System.currentTimeMillis() + nachKampfMs);
        runs.progress(Sprache.t("Battle finished", "Kampf beendet"));
        zeit.phase(Zeitbericht.SEGEN);

        long los = System.currentTimeMillis();
        Optional<Matcher.Hit> segen = ctx.warteAufKnopf(SEGEN_KETTE_MS, 0.84, T_BLESSING);
        if (segen.isEmpty()) return Outcome.ACTED;
        Log.debug("Kathedrale", "Kastenblick Segen nach " + (System.currentTimeMillis() - los) + " ms");

        int x = (int) (ctx.width() * BLESSING_MIDDLE_X);
        int y = (int) (ctx.height() * BLESSING_MIDDLE_Y);
        Log.action("Klick", Sprache.t("Pick the middle blessing at ", "Mittleren Segen waehlen bei ") + x + "," + y);
        ctx.input().click(x, y, 8);
        blessingPicked = true;
        ctx.sleep(tempo.ms(segenWahlMs));

        // "Bestaetigen" sitzt immer an derselben Stelle: ein Blick, druecken.
        Optional<Matcher.Hit> ok = ctx.warteAufKnopf(KASTEN_OK_MS, 0.84, T_BLESSING_OK);
        if (ok.isEmpty()) {
            // Karla 17.10.: hier blieb ihr Bot haengen -- "Bestaetigen" nie erkannt, 92 Mal auf die Karte getippt.
            Mat jetzt = ctx.refresh();
            Optional<int[]> ersatz = jetzt == null ? Optional.empty() : knopfErsatz(jetzt, ctx, T_BLESSING_OK, true, 1);
            if (ersatz.isEmpty()) return Outcome.ACTED;      // der normale Ablauf bestaetigt
            ersatzTippen(ctx, ersatz.get(), T_BLESSING_OK, Sprache.t("Confirm blessing", "Segen bestaetigen"));
            segenKartenKlicks = 0;
        } else {
            Log.action("Turbo", Sprache.t("Confirm blessing (", "Segen bestaetigen (") + ok.get().name()
                    + Sprache.t(", box look)", ", Kastenblick)"));
            ctx.input().click(ok.get().centerX(), ok.get().centerY(), 4);
        }
        etagennetz.letzterAbgeschlossen();   // einen Segen gibt es nur nach dem Sieg
        runs.progress(Sprache.t("Blessing picked", "Segen gewaehlt"));
        zeit.phase(Zeitbericht.WEGWAHL);
        karteKommtNeu();   // ab jetzt steigt der Schriftzug ueber die Karte

        // Erst muss das Segen-Fenster weg sein, dann ist die Etagenkarte da,
        // sobald ihr Hinweis unten rechts zu sehen ist (oder "Next floor" nach
        // dem Waechter). Dann EIN ganzes Bild fuer die Wegwahl.
        //
        // SEGEN NICHT ANGENOMMEN (Test 27.09., 04:24 und 04:29): Zweimal in 94
        // Segen blieb das Fenster nach "Bestaetigen" stehen -- die Karte hatte
        // das Antippen nicht angenommen. Der Bot wartete 1,5 s und brauchte im
        // normalen Ablauf noch einmal 5 s (zusammen 6 bis 7 s). Sonst ist das
        // Fenster nach rund 0,1 s weg. Steht es nach SEGEN_NOCHMAL_MS noch,
        // wird deshalb gleich noch einmal gewaehlt und bestaetigt.
        los = System.currentTimeMillis();
        boolean segenWeg = ctx.warteBisWeg(SEGEN_NOCHMAL_MS, 0.80, T_BLESSING);
        for (int versuch = 0; !segenWeg && versuch < 2; versuch++) {
            Log.action("Klick", Sprache.t(
                    "Pick the middle blessing again at " + x + "," + y + " (the window is still there)",
                    "Mittleren Segen noch einmal waehlen bei " + x + "," + y
                    + " (das Fenster steht noch)"));
            ctx.input().click(x, y, 8);
            ctx.sleep(Math.max(segenWahlMs, 80));
            Optional<Matcher.Hit> nochmal = ctx.warteAufKnopf(KASTEN_OK_MS, 0.84, T_BLESSING_OK);
            if (nochmal.isEmpty()) return Outcome.ACTED;
            Log.action("Turbo", Sprache.t("Confirm blessing (", "Segen bestaetigen (") + nochmal.get().name()
                    + Sprache.t(", box look, once more)", ", Kastenblick, noch einmal)"));
            ctx.input().click(nochmal.get().centerX(), nochmal.get().centerY(), 4);
            karteKommtNeu();
            los = System.currentTimeMillis();
            segenWeg = ctx.warteBisWeg(SEGEN_NOCHMAL_MS, 0.80, T_BLESSING);
        }
        if (!segenWeg) return Outcome.ACTED;
        Optional<Matcher.Hit> karte = ctx.warteAufKnopf(FOTOZYKLUS_MAX_MS, 0.84,
                T_NEXT_FLOOR, T_FLOOR_HINT, T_END_JOURNEY);
        if (karte.isEmpty()) return Outcome.ACTED;
        Mat frisch = ctx.refresh();
        if (frisch == null || ctx.visible(frisch, T_BLESSING)) return Outcome.ACTED;
        blessingPicked = false;
        if (T_NEXT_FLOOR.equals(karte.get().name())
                || (ctx.matcher().has(T_NEXT_FLOOR) && ctx.visible(frisch, T_NEXT_FLOOR))) {
            return Outcome.ACTED;                    // Schritt 10 klickt "Next floor"
        }
        if (isFloorMap(frisch, ctx)) {
            Log.debug("Kathedrale", "Kastenblick Etage nach " + (System.currentTimeMillis() - los) + " ms");
            return handleFloorMap(frisch, ctx, runs);
        }
        return Outcome.ACTED;
    }

    /**
     * Alle Knoepfe, die in einem Fenster nach dem Wegpunkt vorkommen, samt
     * ihren Spielarten (dc_move_on, dc_move_on_alt ...) -- dazu die
     * uebergebenen weiteren.
     */
    private String[] fensterKnoepfe(BotContext ctx, String... weitere) {
        List<String> namen = new ArrayList<>(List.of(weitere));
        for (String base : new String[]{T_CLAIM_REWARD, T_MOVE_ON, T_FINISH_SELECT,
                                        T_FINISH_CONFIRM, T_JOIN_SQUAD, T_CLOSE}) {
            for (String name : variantsOf(ctx, base)) {
                if (!name.endsWith("_screen") && !namen.contains(name)) namen.add(name);
            }
        }
        if (ctx.matcher().has(T_FINISH_YES)) namen.add(T_FINISH_YES);
        if (ctx.matcher().has(T_WAYPOINT_DONE)) namen.add(T_WAYPOINT_DONE);
        return namen.toArray(new String[0]);
    }

    /** Der erste Treffer, dessen Name mit einem dieser Grundnamen beginnt. */
    private static Matcher.Hit treffer(java.util.Map<String, Matcher.Hit> da, String... grundnamen) {
        for (String g : grundnamen) {
            for (var e : da.entrySet()) {
                if (e.getKey().equals(g) || e.getKey().startsWith(g + "_")) return e.getValue();
            }
        }
        return null;
    }

    /**
     * KASTENBLICK-KETTE FUER DIE FENSTER (27.09.): Wuerfel, Dangerous Game,
     * Camp, Altar, Verbuendeter. Autor: "bei denen hakt es noch". Gemessen:
     * Zwischen den Klicks lag jedes Mal die ganze Schrittkette (0,15 bis
     * 0,6 s), dazu feste Wartezeiten.
     *
     * Die Reihenfolge und die Merker sind dieselben wie in der Schrittkette
     * (handleChoice): erst die sichere Wahl ("Weitergehen ohne Risiko" bzw.
     * "Belohnung mitnehmen"), dann "Auswahl abschliessen", dann das
     * Warnfenster, dann "Wegpunkt abgeschlossen". Gedrueckt wird nur, was
     * gesehen wurde. Erscheint etwas anderes, endet die Kette, und der
     * normale Ablauf sieht sich das ganze Bild an.
     */
    private Outcome fensterKette(BotContext ctx, RunTracker runs) {
        ketteKlicks = 0;
        zeit.phase(Zeitbericht.AUSWAHL);
        String[] knoepfe = fensterKnoepfe(ctx, T_DEPLOY_SCREEN, T_FLOOR_HINT, T_END_JOURNEY,
                T_BLESSING, T_NEXT_FLOOR);
        long los = System.currentTimeMillis();
        long letzterKlick = los;
        long karteSeit = 0;
        int klicks = 0;
        // Nach "Wegpunkt abgeschlossen" kommt kein Fenster mehr nach -- dann
        // braucht es die Wartezeit fuer Nachzuegler nicht.
        boolean abgeschlossen = false;
        // Der enge Kasten hat nichts zu tun gefunden: bis zum naechsten Klick
        // das ganze Bild ansehen.
        boolean weit = false;
        boolean schwachGemeldet = false;

        while (System.currentTimeMillis() - los < FENSTER_KETTE_MS) {
            // WUERFEL-HAENGER (27.09.): "Auswahl abschliessen" sitzt im
            // Wuerfel-Fenster 28 px tiefer als bei Dangerous Game -- der enge
            // Kasten sah ihn nie, die Kette wartete die vollen 4 s (20 von 42
            // Wuerfel-Fenstern, je 4,1 s). Sonst kommt der naechste Knopf
            // 100 bis 190 ms nach dem Klick. Kommt so lange nichts, wird das
            // ganze Bild angesehen; die Suche merkt sich dabei die neue
            // Stelle. Und bleibt es lange still, uebernimmt der normale Ablauf.
            long still = System.currentTimeMillis() - letzterKlick;
            if (still >= FENSTER_GEDULD_MS) {
                Log.debug("Kathedrale", "Kastenblick Fenster: " + still + " ms nichts Bekanntes, "
                        + "der normale Ablauf sieht nach.");
                return Outcome.ACTED;
            }
            java.util.Map<String, Matcher.Hit> da = weit ? ctx.weitBlicke(0.84, knoepfe)
                                                         : ctx.kastenBlicke(0.84, knoepfe);
            if (da == null) return Outcome.ACTED;
            long jetzt = System.currentTimeMillis();
            Matcher.Hit h;

            // Ein Kampf oder ein Segen: das gehoert den anderen Ketten.
            if (da.containsKey(T_DEPLOY_SCREEN) || da.containsKey(T_BLESSING)) return Outcome.ACTED;

            // Nach einem Klick dem Spiel einen Augenblick lassen, sonst
            // trifft der naechste Klick denselben Knopf noch einmal.
            if (klicks > 0 && jetzt - letzterKlick < FENSTER_TAKT_MS) continue;

            // Hat "Finish selection" gegriffen? (siehe abschlussPruefen)
            boolean finishDa = treffer(da, T_FINISH_SELECT) != null;
            boolean fensterFolgt = da.containsKey(T_WAYPOINT_DONE)
                    || treffer(da, T_FINISH_CONFIRM, T_FINISH_YES, T_JOIN_SQUAD) != null;
            if (choiceMade && finishDa && !fensterFolgt && abschlussGeklicktUm != 0) {
                int was = abschlussPruefen(jetzt);
                if (was == 0) continue;                       // dem Spiel noch Zeit lassen
                if (was == 3) {
                    wahlFensterSchliessen(ctx);
                    return Outcome.ACTED;
                }
                letzterKlick = jetzt;                        // die Geduld beginnt neu
            }

            if (da.containsKey(T_WAYPOINT_DONE) && (h = treffer(da, T_CLOSE)) != null) {
                fensterKlick(ctx, h, Sprache.t("Waypoint completed", "Wegpunkt abgeschlossen"));
                battlePending = false;
                floorMapBlocked = 0;
                wahlVergessen();
                etagennetz.letzterAbgeschlossen();
                runs.progress(Sprache.t("Waypoint completed", "Wegpunkt abgeschlossen"));
                zeit.phase(Zeitbericht.WEGWAHL);
                karteKommtNeu();
                abgeschlossen = true;
            } else if ((h = treffer(da, T_JOIN_SQUAD)) != null) {
                fensterKlick(ctx, h, Sprache.t("Put the ally in the squad", "Verbuendeten in die Truppe aufnehmen"));
                runs.progress(Sprache.t("Ally taken in", "Verbuendeten aufgenommen"));
            } else if (finishConfirmPending
                    && (h = treffer(da, T_FINISH_CONFIRM, T_FINISH_YES)) != null) {
                fensterKlick(ctx, h, Sprache.t("Confirm the selection (warning window)", "Auswahl bestaetigen (Warnfenster)"));
                finishConfirmPending = false;
                choiceMade = false;
                wahlVergessen();
                runs.progress(Sprache.t("Decision confirmed", "Entscheidung bestaetigt"));
            } else if (!choiceMade && (h = wahlImFenster(ctx, da, finishDa)) != null) {
                boolean nochmal = abschlussOhneWirkung >= WAHL_NEU_AB;
                String base = h.name().startsWith(T_CLAIM_REWARD) ? T_CLAIM_REWARD : T_MOVE_ON;
                fensterKlick(ctx, h, wahlGrund(base, nochmal));
                choiceMade = true;
                letzteWahl = base;
                wahlGeklicktUm = System.currentTimeMillis();
            } else if (choiceMade && finishDa && abschlussGeklicktUm == 0) {
                // Nach einer neu getroffenen Wahl etwas laenger warten.
                if (abschlussOhneWirkung >= WAHL_NEU_AB && jetzt - wahlGeklicktUm < WAHL_WIEDER_PAUSE_MS) continue;
                if (jetzt - wahlGeklicktUm < spielTraegheitMs) continue;   // traeges Spiel: die Wahl erst ankommen lassen
                h = treffer(da, T_FINISH_SELECT);
                fensterKlick(ctx, h, Sprache.t("Finish selection", "Auswahl abschliessen"));
                finishConfirmPending = true;
                abschlussGeklicktUm = System.currentTimeMillis();
                runs.progress(Sprache.t("Decision made", "Entscheidung getroffen"));
                karteKommtNeu();
            } else if (choiceMade && finishDa) {
                continue;                                    // "Finish selection" gedrueckt, warten
            } else {
                // Kein Knopf eines Fensters mehr: Ist die Etagenkarte zurueck?
                // Erst glauben, wenn sie zwei Blicke lang ohne Fenster steht --
                // nach "Auswahl abschliessen" kommt oft noch ein Fenster nach.
                boolean karte = da.containsKey(T_FLOOR_HINT) || da.containsKey(T_END_JOURNEY)
                        || da.containsKey(T_NEXT_FLOOR);
                boolean fensterZu = treffer(da, T_CLAIM_REWARD, T_MOVE_ON, T_FINISH_SELECT,
                        T_FINISH_CONFIRM, T_FINISH_YES, T_JOIN_SQUAD, T_CLOSE) == null
                        && !da.containsKey(T_WAYPOINT_DONE);
                if (klicks > 0 && karte && fensterZu) {
                    if (karteSeit == 0) karteSeit = jetzt;
                    if (jetzt - karteSeit >= (abgeschlossen ? 0 : FENSTER_RUHE_MS)
                            && jetzt - letzterKlick >= (abgeschlossen ? FENSTER_TAKT_MS
                                                                      : FENSTER_NACHLAUF_MS)) {
                        Mat frisch = ctx.refresh();
                        if (frisch == null) return Outcome.ACTED;
                        finishConfirmPending = false;
                        choiceMade = false;
                        if (isFloorMap(frisch, ctx)) {
                            Log.debug("Kathedrale", "Kastenblick Fenster fertig nach "
                                    + (jetzt - los) + " ms, " + klicks + " Klicks");
                            return handleFloorMap(frisch, ctx, runs);
                        }
                        return Outcome.ACTED;
                    }
                } else {
                    karteSeit = 0;
                    // ZUM NACHMESSEN (Autor, 27.09.: der Schriftzug "You have
                    // got a blessing" zieht auch ueber die Fenster): Ist das
                    // Fenster da, die Wahl aber nicht zu erkennen, einmal je
                    // Fenster festhalten, wie gut die Knoepfe gerade passen.
                    if (!choiceMade && !schwachGemeldet && treffer(da, T_FINISH_SELECT) != null) {
                        schwachGemeldet = true;
                        StringBuilder werte = new StringBuilder();
                        for (var e : ctx.kastenBlicke(0.20, fensterKnoepfe(ctx)).entrySet()) {
                            if (!e.getKey().startsWith(T_MOVE_ON) && !e.getKey().startsWith(T_CLAIM_REWARD)) continue;
                            werte.append(String.format(" %s %.2f bei %d,%d", e.getKey(), e.getValue().score(),
                                    e.getValue().centerX(), e.getValue().centerY()));
                        }
                        Log.debug("Kathedrale", "Kastenblick Fenster: Wahl nach " + (jetzt - los)
                                + " ms nicht zu erkennen (verdeckt?). Beste Werte:" + werte);
                    }
                    // Nach "Wegpunkt abgeschlossen" kommt nur noch die Karte,
                    // dafuer reicht der enge Kasten.
                    if (!weit && !abgeschlossen && jetzt - letzterKlick >= FENSTER_WEITBLICK_MS) {
                        weit = true;
                        Log.debug("Kathedrale", "Kastenblick Fenster: nach "
                                + (jetzt - letzterKlick) + " ms nichts zu tun, ganzes Bild.");
                    }
                }
                continue;
            }
            klicks++;
            ketteKlicks = klicks;
            karteSeit = 0;
            weit = false;
            letzterKlick = System.currentTimeMillis();
            unknownScreens = 0;
        }
        return Outcome.ACTED;
    }

    /** Klicks der letzten Fenster-Kette (Autor 24.10., A33: bleibt sie zweimal ohne Klick, ruht sie 2 Minuten). */
    private int ketteKlicks = 0;
    private int ketteLeerLaeufe = 0;
    private long ketteAusBis = 0;
    private static final long KETTE_RUHE_MS = 120_000;

    /**
     * Darf die Kastenblick-Kette das Auswahl-Fenster uebernehmen? Nur im Turbo mit Kastenblick, und nur wenn die Stellen
     * aller Knoepfe der Reihe nach schon bekannt sind (Wahl, Auswahl abschliessen, Warnung, Wegpunkt abgeschlossen, Schliessen):
     * sonst sieht der Einzelschritt nach und merkt sich die Stelle.
     */
    private boolean fensterKetteErlaubt(BotContext ctx) {
        if (!kastenblick || !istTurbo() || System.currentTimeMillis() < ketteAusBis) return false;
        List<String> wahl = new ArrayList<>(), bestaetigen = new ArrayList<>();
        for (String base : new String[]{T_CLAIM_REWARD, T_MOVE_ON}) {
            for (String name : variantsOf(ctx, base)) if (!name.endsWith("_screen")) wahl.add(name);
        }
        for (String name : variantsOf(ctx, T_FINISH_CONFIRM)) if (!name.endsWith("_screen")) bestaetigen.add(name);
        if (ctx.matcher().has(T_FINISH_YES)) bestaetigen.add(T_FINISH_YES);
        if (wahl.isEmpty() || bestaetigen.isEmpty() || !ctx.matcher().has(T_FINISH_SELECT)
                || !ctx.matcher().has(T_WAYPOINT_DONE) || !ctx.matcher().has(T_CLOSE)) return false;
        return ctx.kastenBekannt(wahl.toArray(new String[0])) && ctx.kastenBekannt(T_FINISH_SELECT)
                && ctx.kastenBekannt(bestaetigen.toArray(new String[0])) && ctx.kastenBekannt(T_WAYPOINT_DONE)
                && ctx.kastenBekannt(T_CLOSE);
    }

    /** Die Kette mit Selbstkontrolle: zwei Laeufe hintereinander ohne einen einzigen Klick -> 2 Minuten lang wieder der Einzelschritt. */
    private Outcome fensterKetteGeprueft(BotContext ctx, RunTracker runs) {
        Outcome ergebnis = fensterKette(ctx, runs);
        if (ketteKlicks > 0) {
            ketteLeerLaeufe = 0;
        } else if (++ketteLeerLaeufe >= 2) {
            ketteLeerLaeufe = 0;
            ketteAusBis = System.currentTimeMillis() + KETTE_RUHE_MS;
            Log.info("Kathedrale", Sprache.t(
                    "The window chain found nothing to press twice in a row -- the single steps take over for two minutes.",
                    "Die Fenster-Kette fand zweimal hintereinander nichts zu druecken -- zwei Minuten lang gelten die Einzelschritte."));
        }
        return ergebnis;
    }

    /**
     * Die Wahl im Fenster ("Claim reward" oder "Move on"), in der Reihenfolge
     * von wahlReihenfolge(). Wird sie neu getroffen und ist "Finish selection"
     * zu sehen (also sicher ein Entscheidungsfenster), gilt die niedrigere
     * Schwelle: Der Hinweis "You need to select an action" liegt ueber "Move on".
     */
    private Matcher.Hit wahlImFenster(BotContext ctx, java.util.Map<String, Matcher.Hit> da, boolean finishDa) {
        for (String base : wahlReihenfolge()) {
            Matcher.Hit h = treffer(da, base);
            if (h != null) return h;
        }
        if (abschlussOhneWirkung < WAHL_NEU_AB || !finishDa) return null;
        for (String base : wahlReihenfolge()) {
            List<String> namen = new ArrayList<>();
            for (String n : variantsOf(ctx, base)) if (!n.endsWith("_screen")) namen.add(n);
            Optional<Matcher.Hit> h = ctx.kastenBlick(WAHL_SCHWACH, namen.toArray(new String[0]));
            if (h.isPresent()) return h.get();
        }
        return null;
    }

    private void fensterKlick(BotContext ctx, Matcher.Hit h, String grund) {
        Log.action("Klick", grund + "  [" + h.name() + Sprache.t(" at ", " bei ") + h.centerX() + "," + h.centerY()
                + String.format(Sprache.t(", match %.3f, box look]", ", Uebereinstimmung %.3f, Kastenblick]"), h.score()));
        ctx.input().click(h.centerX(), h.centerY(), 5);
    }

    /** Fenster-Kette: laengstens so lange. */
    private static final long FENSTER_KETTE_MS = 4000;
    /** Fenster-Kette: so lange nach dem letzten Klick nichts gesehen -> das ganze Bild ansehen. */
    private static final long FENSTER_WEITBLICK_MS = 150;
    /** Fenster-Kette: so lange nach dem letzten Klick nichts zu tun -> normaler Ablauf. */
    private static final long FENSTER_GEDULD_MS = 1500;
    /** Fenster-Kette: nach einem Klick so lange nicht wieder klicken. */
    private static final long FENSTER_TAKT_MS = 60;
    /** Fenster-Kette: so lange muss die Etagenkarte ohne Fenster stehen. */
    private static final long FENSTER_RUHE_MS = 90;
    /** Fenster-Kette: so lange nach dem letzten Klick kann noch ein Fenster nachkommen. */
    private static final long FENSTER_NACHLAUF_MS = 260;

    /** Kastenblick: so lange hoechstens auf den Truppenbildschirm oder ein Fenster warten. */
    private static final long KASTEN_KAMPF_MS = 450;
    /** Truppenbildschirm: so lange hoechstens auf den Knopf zum Aufstellen warten. */
    private static final long AUFSTELLEN_GEDULD_MS = 450;
    /** Truppenbildschirm: so lange hoechstens auf "Quick Attack" warten, bevor "To battle!" gilt. */
    // Karlas Bericht #UTXQ (16.10.): auf ihrem PC kam "Quick Attack" bis 0,43 s spaet (gemessen), bei 0,4 s Geduld
    // nahm der Bot 5 von 23 Mal "To battle" -- ein voller Kampf zum Zuschauen. Gefunden wird sofort weitergemacht,
    // die Geduld kostet nur dann Zeit, wenn es wirklich keinen Quick Attack gibt.
    // Karlas Bericht #B7XK (Fassung mit 1,5 s): "Quick Attack" kam nach 1042 und 1147 ms, zweimal gar nicht
    // innerhalb 1,5 s -> wieder "To battle" (voller Kampf, 10-40 s zuschauen). Ihr Spiel zeichnet den Knopf
    // gut 7-mal langsamer als Autors (0,15 s). 4 s Geduld kosten nur Zeit, wenn es wirklich keinen gibt.
    private static final long ANGRIFF_GEDULD_MS = 4000;
    /** Kastenblick: so lange hoechstens, wenn nur "Auswahl abschliessen" zu sehen ist (Wahl verdeckt). */
    private static final long KASTEN_FENSTER_MS = 1300;
    /** Kastenblick: so lange hoechstens auf "Home" nach dem Kampf warten. */
    private static final long KASTEN_HOME_MS = 3500;
    /** Kastenblick: so lange hoechstens auf "Bestaetigen" nach der Segen-Karte warten. */
    private static final long KASTEN_OK_MS = 400;
    /** Kastenblick an/aus (lernwerte.txt: kastenblick). Aus = die bisherigen Fotozyklen. */
    private boolean kastenblick = true;
    /** Pause zwischen "Deploy the best" und "Quick Attack" (lernwerte.txt: aufstellen_ms). */
    private long aufstellenMs = 70;

    /**
     * Losschlagen. Zuerst der Kathedralen-Knopf, dann der aus dem Verlies --
     * je nachdem, welche Vorlage aufgenommen wurde. "Quick Attack" ist immer
     * die erste Wahl, weil es die Kampfanimation ueberspringt.
     */
    private boolean startBattle(Mat screen, BotContext ctx, RunTracker runs) {
        String[] buttons = {
                T_QUICK_ATTACK, T_QUICK_ATTACK_ALT, T_TO_BATTLE, T_TO_BATTLE_ALT
        };
        // "TO BATTLE!" NUR, WENN ES "QUICK ATTACK" WIRKLICH NICHT GIBT (Test
        // 27.09., 04:47): Auf einem Bild mitten im Einblenden fehlte "Quick
        // Attack", "To battle!" passte -- ein Kampf in voller Laenge, 30 s.
        // Im schnellen Weg wird deshalb erst noch kurz auf "Quick Attack"
        // gewartet.
        if (istTurbo() && ctx.matcher().has(T_QUICK_ATTACK)
                && !ctx.visible(screen, T_QUICK_ATTACK)
                && !(ctx.matcher().has(T_QUICK_ATTACK_ALT) && ctx.visible(screen, T_QUICK_ATTACK_ALT))) {
            long seit = System.currentTimeMillis();
            while (System.currentTimeMillis() - seit < ANGRIFF_GEDULD_MS) {
                screen = turboFrischSchnell(screen, ctx, 40);
                if (ctx.visible(screen, T_QUICK_ATTACK)
                        || (ctx.matcher().has(T_QUICK_ATTACK_ALT) && ctx.visible(screen, T_QUICK_ATTACK_ALT))) {
                    Log.debug("Kathedrale", "Quick Attack erst nach "
                            + (System.currentTimeMillis() - seit) + " ms gefunden.");
                    break;
                }
            }
            traegheitMessen(System.currentTimeMillis() - seit);
        } else if (istTurbo()) {
            traegheitMessen(0);   // sofort da
        }
        for (String button : buttons) {
            // Karla 17.10.: 7 von 28 Kaempfen liefen ueber "To battle!" (voller Kampf, ~30 s statt ~10 s), bei Autor
            // 0 von 1496 -- "Quick Attack" war da, wurde auf ihrem Bildschirm nur nicht erkannt. Darum vor dem
            // Rueckfall auf "To battle!" noch mit dem gelernten Bild bzw. in anderer Groesse suchen (ohne Blind-Tipp).
            if (button.equals(T_TO_BATTLE) && ctx.matcher().has(T_QUICK_ATTACK)) {
                Optional<int[]> schnell = knopfErsatz(screen, ctx, T_QUICK_ATTACK, false);
                if (schnell.isPresent() && ersatzTippen(ctx, schnell.get(), T_QUICK_ATTACK,
                        Sprache.t("Start battle (Quick Attack)", "Kampf starten (Quick Attack)"))) {
                    kampfGestartet(runs);
                    if (!(istTurbo() && kastenblick)) warte(ctx, 700);
                    return true;
                }
            }
            boolean geklickt = istTurbo() ? turboKlick(screen, ctx, button, Sprache.t("Start battle", "Kampf starten"))
                                          : clickAny(screen, ctx, button, Sprache.t("Start battle", "Kampf starten"));
            if (geklickt) {
                kampfGestartet(runs);
                // Mit Kastenblick wartet der Aufrufer gleich auf "Home".
                if (!(istTurbo() && kastenblick)) warte(ctx, 700);
                return true;
            }
        }
        // Autor 17.10. (Punkt 7): "Quick Attack" robust nachsehen bzw. an seiner bekannten Stelle tippen.
        Optional<int[]> angriffErsatz = knopfErsatz(screen, ctx, T_QUICK_ATTACK);
        if (angriffErsatz.isPresent() && ersatzTippen(ctx, angriffErsatz.get(), T_QUICK_ATTACK,
                Sprache.t("Start battle", "Kampf starten"))) {
            kampfGestartet(runs);
            if (!(istTurbo() && kastenblick)) warte(ctx, 700);
            return true;
        }
        if (!onCooldown("battle_warn")) {
            warnung(Sprache.t(
                    "No attack button recognized. Please capture the orange "
                    + "\"Quick Attack!\" of the cathedral with Capture-Images.exe as dc_quick_attack.",
                    "Kein Knopf zum Losschlagen erkannt. Bitte den orangen "
                    + "\"Quick Attack!\" der Kathedrale mit Capture-Images.exe als dc_quick_attack "
                    + "aufnehmen."));
            cooldowns.put("battle_warn", System.currentTimeMillis() + 30_000);
        }
        return false;
    }

    /**
     * Stehen die Einheiten schon im Feld?
     *
     * Erkennbar an den Namensschildern: die Zeile "Drag and drop units ..."
     * trennt oben (aufgestellt) von unten (Ablage). Findet sich ein bekannter
     * Name oberhalb dieser Zeile, ist die Truppe aufgestellt.
     */
    /** Hat der Spieler wenigstens eins seiner Kaempferbilder 1 bis 4 aufgenommen? */
    private boolean kenntEigeneKaempfer(BotContext ctx) {
        for (String name : REVIVE_ORDER) {
            if (ctx.matcher().has(name)) return true;
        }
        return false;
    }

    private boolean squadDeployed(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> line = ctx.find(screen, T_DEPLOY_SCREEN);
        if (line.isEmpty()) return false;
        int divider = line.get().centerY();

        for (String name : REVIVE_ORDER) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> hit = ctx.matcher().find(screen, name, 0.82);
            if (hit.isPresent() && hit.get().centerY() < divider) return true;
        }
        return false;
    }

    /**
     * Belebt eine gefallene Einheit wieder, in der Reihenfolge
     * Magier 1, Bogen 1, Bogen 2, Magier 2. Einheiten mit dem Namen "ersatz"
     * bleiben liegen -- die sind nur Auffuellung.
     *
     * Liefert null, wenn es nichts wiederzubeleben gibt.
     */
    /** Seit wann eine Einheit gefallen aussieht (0 = kein Verdacht). */
    private long totVerdachtSeit = 0;
    /** So lange nach dem ersten Verdacht noch einmal hinsehen (Portraets laden noch). */
    private static final long TOT_BESTAETIGEN_MS = 300;
    private Outcome tryRevive(Mat screen, BotContext ctx, RunTracker runs) {
        // Der Knopf hat zwei Aussehen. Wie ueberall gilt: jede Vorlage, deren
        // Name mit "dc_resurrect" beginnt, zaehlt als derselbe Knopf.
        if (variantsOf(ctx, T_RESURRECT).isEmpty()) return null;

        // Die offene Einheitenkarte wird jetzt schon oben in step()
        // behandelt -- sie verdeckt den Truppenbildschirm, also darf die
        // Entscheidung nicht davon abhaengen, dass man ihn noch sieht.
        Outcome karte = handleUnitCard(screen, ctx, runs);
        if (karte != null) return karte;

        if (onCooldown("revive_click")) return null;
        if (!ctx.matcher().has(T_UNIT_DEAD_BAR)) return null;

        // Der Rueckhalt, falls die Karte gar nicht erst aufgeht.
        if (reviveVersucheOhneErfolg >= MAX_REVIVE_VERSUCHE) {
            if (!onCooldown("revive_aufgeben")) {
                warnung(Sprache.t(
                        "After " + reviveVersucheOhneErfolg + " attempts not a single "
                        + "resurrection came about. The bot now leaves the "
                        + "units be and keeps playing. The template dc_unit_dead_bar "
                        + "probably mistakes an almost empty health bar for an empty one.",
                        "Nach " + reviveVersucheOhneErfolg + " Versuchen kam "
                        + "keine einzige Wiederbelebung zustande. Der Bot laesst die "
                        + "Einheiten jetzt liegen und spielt weiter. Wahrscheinlich haelt "
                        + "die Vorlage dc_unit_dead_bar eine fast leere Lebensleiste "
                        + "faelschlich fuer eine leere."));
                cooldowns.put("revive_aufgeben", System.currentTimeMillis() + 120_000);
            }
            return null;
        }

        List<Matcher.Hit> dead = ctx.matcher().findAll(screen, T_UNIT_DEAD_BAR, 0.88, 12);
        if (dead.isEmpty()) {
            totVerdachtSeit = 0;
            return null;
        }

        // Nur Einheiten aus der Wunschreihenfolge anfassen, "ersatz" nie.
        for (String name : REVIVE_ORDER) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> label = ctx.matcher().find(screen, name, 0.82);
            if (label.isEmpty()) continue;

            for (Matcher.Hit bar : dead) {
                if (Math.abs(bar.centerX() - label.get().centerX()) > 40) continue;
                if (knownAlive(bar)) continue;   // schon geprueft, die lebt noch

                // ERST AUF EINEM ZWEITEN BILD GLAUBEN (Autor, 26.09., mit Video):
                // Der Fotozyklus sah den Truppenbildschirm 75 ms nach dem
                // Wegpunkt -- die Portraets luden noch und sahen wie gefallen
                // aus. Der Bot klickte "gefallene" Einheiten an und oeffnete
                // dabei den Helden (Tom). Jetzt: Verdacht merken, 0,3 s spaeter
                // auf einem neuen Bild noch einmal pruefen, erst dann klicken.
                long jetzt = System.currentTimeMillis();
                if (totVerdachtSeit == 0 || jetzt - totVerdachtSeit > 3000) {
                    totVerdachtSeit = jetzt;
                    ctx.sleep(tempo.ms(TOT_BESTAETIGEN_MS));
                    return Outcome.WAITING;
                }
                if (jetzt - totVerdachtSeit < TOT_BESTAETIGEN_MS) {
                    ctx.sleep(tempo.ms(60));
                    return Outcome.WAITING;
                }
                totVerdachtSeit = 0;

                int lift = (int) (ctx.height() * 0.055);
                reviveVersucheOhneErfolg++;
                Log.action("Klick", Sprache.t(
                        "Select fallen unit (" + name + ")"
                        + (reviveVersucheOhneErfolg > 1
                           ? "  [attempt " + reviveVersucheOhneErfolg + " of "
                             + MAX_REVIVE_VERSUCHE + "]" : ""),
                        "Gefallene Einheit anwaehlen (" + name + ")"
                        + (reviveVersucheOhneErfolg > 1
                           ? "  [Versuch " + reviveVersucheOhneErfolg + " von "
                             + MAX_REVIVE_VERSUCHE + "]" : "")));
                ctx.input().click(bar.centerX(), bar.centerY() - lift, 5);
                lastCardAt = new int[]{bar.centerX(), bar.centerY()};
                cooldowns.put("revive_click", System.currentTimeMillis() + 2500);
                runs.progress(Sprache.t("Dead unit selected", "Tote Einheit gewaehlt"));
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
        }
        return null;
    }

    /**
     * Eine Einheitenkarte liegt offen -- was steht darauf?
     *
     * ------------------------------------------------------------------
     *  Warum es diesen Umweg ueberhaupt gibt
     * ------------------------------------------------------------------
     *
     * Eine fast leere Lebensleiste sieht einer ganz leeren zum Verwechseln
     * aehnlich. Der Bot hat deshalb schon Einheiten mit einem einzigen
     * Lebenspunkt fuer tot gehalten. Von aussen ist das nicht zu
     * entscheiden -- auf der Karte schon: gibt es einen "Resurrect"-Knopf
     * oder steht die Lebensanzeige auf 0, ist die Einheit tot. Sonst lebt
     * sie, und die Stelle wird gemerkt, damit der Bot nicht gleich wieder
     * darauf hereinfaellt.
     *
     * ------------------------------------------------------------------
     *  Warum das eine eigene Methode ist
     * ------------------------------------------------------------------
     *
     * Weil sie an zwei Stellen gebraucht wird. Frueher stand sie nur in
     * tryRevive(), und tryRevive() laeuft nur, wenn der Truppenbildschirm
     * erkannt wird -- den die Karte aber gerade verdeckt. Die Entscheidung
     * kam damit nie zustande, und das Merken erst recht nicht.
     *
     * @return null, wenn ueberhaupt keine Karte offen ist
     */
    private Outcome handleUnitCard(Mat screen, BotContext ctx, RunTracker runs) {
        if (!ctx.matcher().has(T_UNIT_CARD) || !ctx.visible(screen, T_UNIT_CARD)) {
            return null;
        }

        if (clickAny(screen, ctx, T_RESURRECT, Sprache.t("Resurrect unit", "Einheit wiederbeleben"))) {
            ctx.laufstatistik().countResurrection();
            runs.progress(Sprache.t("Resurrection", "Wiederbelebung"));
            // Es hat geklappt -- der Zaehler faengt wieder bei null an.
            reviveVersucheOhneErfolg = 0;
            lastCardAt = null;
            ctx.sleepUntilChanged(400);
            return Outcome.ACTED;
        }

        if (ctx.matcher().has(T_HEALTH_ZERO) && ctx.visible(screen, T_HEALTH_ZERO)) {
            // Tot, aber der Knopf wird nicht erkannt. Nicht raten.
            if (!onCooldown("revive_warn")) {
                warnung(Sprache.t(
                        "The unit is dead, but the resurrect button "
                        + "is not recognized. Please capture the green "
                        + "\"Resurrect\" with Capture-Images.exe as dc_resurrect_alt.",
                        "Die Einheit ist tot, aber der Knopf zum "
                        + "Wiederbeleben wird nicht erkannt. Bitte den gruenen "
                        + "\"Resurrect\" mit Capture-Images.exe als dc_resurrect_alt aufnehmen."));
                cooldowns.put("revive_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        // Kein Resurrect-Knopf und keine Null: die Einheit lebt noch.
        merkeLebt(Sprache.t("Unit is still alive", "Einheit lebt noch"));
        int x = (int) (ctx.width() * CARD_DISMISS_X);
        int y = (int) (ctx.height() * CARD_DISMISS_Y);
        Log.action("Klick", Sprache.t("Unit is still alive, closing the card at ",
                                      "Einheit lebt noch, Karte schliessen bei ") + x + "," + y);
        ctx.input().click(x, y, 6);
        ctx.sleepUntilChanged(700);
        return Outcome.ACTED;
    }

    /**
     * Haelt fest, dass an der zuletzt angeklickten Stelle eine lebende
     * Einheit steht -- die wird fuer diesen Durchlauf nicht mehr angefasst.
     */
    private void merkeLebt(String warum) {
        if (lastCardAt == null) return;
        aliveUnits.add(lastCardAt);
        Log.info("Kathedrale", Sprache.t(
                warum + ": the spot " + lastCardAt[0] + ","
                + lastCardAt[1] + " is not clicked again this run ("
                + aliveUnits.size() + " spots remembered).",
                warum + ": die Stelle " + lastCardAt[0] + ","
                + lastCardAt[1] + " wird diesen Durchlauf nicht mehr angeklickt ("
                + aliveUnits.size() + " Stellen gemerkt)."));
        lastCardAt = null;
    }

    /** Steht ein Knopf des Kampfergebnisses da -- "Home" (Quick Attack) oder "Back" (voller Kampf)? */
    private boolean ergebnisKnopfDa(Mat screen, BotContext ctx) {
        return ctx.visible(screen, T_HOME) || (ctx.matcher().has(T_KAMPF_BACK) && ctx.visible(screen, T_KAMPF_BACK));
    }

    /** Schritt 7: "Home" (oder "Back" nach einem vollen Kampf) auf dem Kampfergebnis, Sieg wie Niederlage. */
    private Outcome kampfergebnisBestaetigen(Mat screen, BotContext ctx, RunTracker runs) {
        if (!click(screen, ctx, T_HOME, Sprache.t("Confirm battle result", "Kampfergebnis bestaetigen"))
                && ctx.matcher().has(T_KAMPF_BACK)) {
            click(screen, ctx, T_KAMPF_BACK, Sprache.t("Confirm battle result (Back)", "Kampfergebnis bestaetigen (Back)"));
        }
        // Der Kampf ist abgehakt: die Etagenkarte darf wieder angefasst
        // werden. Genau diese Reihenfolge -- erst "Home", dann der
        // naechste Stuetzpunkt -- war vorher nicht sichergestellt.
        battlePending = false;
        floorMapBlocked = 0;
        // Sperre fuer die Etagenkarte: waehrend das Banner verblasst,
        // werden die Wegpunkte dahinter zwar erkannt, sind aber noch nicht
        // anklickbar. Ohne diese Sperre klickt der Bot dort ins Leere.
        //
        // 600 Millisekunden reichen -- mit 1200 war es sichtbar zu
        // vorsichtig. Klickt der Bot hier wieder ins Leere, ist das die
        // Zahl zum Hochsetzen.
        cooldowns.put("after_battle", System.currentTimeMillis() + nachKampfMs);
        runs.progress(Sprache.t("Battle finished", "Kampf beendet"));
        zeit.phase(Zeitbericht.SEGEN);

        // FOTOZYKLUS (Turbo, Autor 25.09.): Nach "Home" kommt der Segen fast
        // sofort (gemessen ~0,2 s). Statt im naechsten Schritt wieder alle
        // Fenster durchzupruefen, gleich neue Bilder holen und NUR nach dem
        // Segen sehen -- dann waehlen und bestaetigen in einem Zug. Blind
        // geklickt wird nicht: kommt kein Segen (etwa nach einer Niederlage),
        // geht es nach SEGEN_KETTE_MS ganz normal weiter.
        if (istTurbo()) {
            long los = System.currentTimeMillis();
            while (System.currentTimeMillis() - los < SEGEN_KETTE_MS) {
                Mat frisch = turboFrischSchnell(screen, ctx, segenTaktMs);
                if (ctx.visible(frisch, T_BLESSING)) {
                    Log.debug("Kathedrale", "Fotozyklus: Segen nach "
                            + (System.currentTimeMillis() - los) + " ms");
                    battlePending = false;
                    floorMapBlocked = 0;
                    return handleBlessing(frisch, ctx, runs);
                }
                screen = frisch;
            }
            return Outcome.ACTED;
        }
        ctx.sleepUntilChanged(700);
        return Outcome.ACTED;
    }

    /** Fotozyklen: so lange hoechstens nur nach dem erwarteten Bild sehen. */
    private static final long FOTOZYKLUS_MAX_MS = 1500;

    /**
     * Fotozyklus nach dem Segen (lernwerte.txt: fotozyklus_etage). Gleich
     * neue Bilder holen und nur fragen "Segen weg, Etagenkarte da?" -- dann
     * sofort den naechsten Wegpunkt waehlen, ohne die uebrige Kette. Kommt
     * etwas anderes, geht es nach FOTOZYKLUS_MAX_MS ganz normal weiter.
     * Gemessen vorher (26.09.): Segen bestaetigt -> Wegpunkt 0,4 s.
     */
    private Outcome nachSegenAufEtage(Mat screen, BotContext ctx, RunTracker runs) {
        long los = System.currentTimeMillis();
        while (System.currentTimeMillis() - los < FOTOZYKLUS_MAX_MS) {
            screen = turboFrischSchnell(screen, ctx, segenTaktMs);
            if (ctx.visible(screen, T_BLESSING)) continue;
            blessingPicked = false;   // Segensfenster ist weg (sonst erst in Schritt 5)
            // Nach dem Waechter steht "Next floor" da: das ist Schritt 10,
            // nicht die Wegwahl -- zurueck in den normalen Ablauf.
            if (ctx.matcher().has(T_NEXT_FLOOR) && ctx.visible(screen, T_NEXT_FLOOR)) {
                return Outcome.ACTED;
            }
            if (isFloorMap(screen, ctx)) {
                Log.debug("Kathedrale", "Fotozyklus Etage nach "
                        + (System.currentTimeMillis() - los) + " ms");
                return handleFloorMap(screen, ctx, runs);
            }
        }
        return Outcome.ACTED;
    }

    /**
     * Fotozyklus nach dem Wegpunkt (lernwerte.txt: fotozyklus_kampf). Nur
     * fragen "Truppenbildschirm da?" -- dann gleich aufstellen und losschlagen.
     * Liegt stattdessen ein anderes Fenster oben (Auswahl, Warnung, Lager),
     * sofort zurueck in den normalen Ablauf, der kennt sie alle.
     * Gemessen vorher (26.09.): Wegpunkt -> Kampf starten 0,45 s.
     */
    private Outcome nachWegpunktZumKampf(Mat screen, BotContext ctx, RunTracker runs) {
        long los = System.currentTimeMillis();

        // KASTENBLICK (27.09.): Nur auf die Stellen sehen, an denen nach einem
        // Wegpunkt etwas erscheinen kann -- Truppenbildschirm ODER eines der
        // Fenster (Auswahl, Belohnung, Verbuendeter). Rund 8 ms je Blick statt
        // 60 bis 75. Erscheint nichts davon, geht es wie bisher am ganzen
        // Bild weiter.
        if (kastenblick) {
            String[] erwartet = fensterKnoepfe(ctx, T_DEPLOY_SCREEN, T_QUICK_ATTACK, T_QUICK_ATTACK_ALT);
            long grenze = KASTEN_KAMPF_MS;
            while (System.currentTimeMillis() - los < grenze) {
                java.util.Map<String, Matcher.Hit> da = ctx.kastenBlicke(0.84, erwartet);
                if (da.isEmpty()) continue;
                long nach = System.currentTimeMillis() - los;
                if (da.containsKey(T_DEPLOY_SCREEN)) {
                    Mat frisch = ctx.refresh();
                    if (frisch == null) break;
                    Log.debug("Kathedrale", "Kastenblick Kampf nach " + nach + " ms");
                    return handleSquad(frisch, ctx, runs);
                }
                // "AUSWAHL ABSCHLIESSEN" ALLEIN IST KEIN FENSTER (Test 27.09.,
                // 04:22): Der gruene Knopf "To battle!" des Truppenbildschirms
                // sitzt fast an der Stelle, an der im Wuerfel-Fenster "Auswahl
                // abschliessen" steht, und sieht ihm aehnlich. 92 von 94
                // Kaempfen liefen deshalb erst in die Fenster-Kette (0,3 s je
                // Kampf). Ein Fenster ist erst da, wenn einer seiner EIGENEN
                // Knoepfe zu sehen ist. Dasselbe gilt fuer "Schliessen" allein.
                if (treffer(da, T_CLAIM_REWARD, T_MOVE_ON, T_JOIN_SQUAD, T_FINISH_CONFIRM,
                        T_FINISH_YES) == null && !da.containsKey(T_WAYPOINT_DONE)) {
                    // Ist es doch ein Fenster, liegt gerade der Schriftzug
                    // "You have got a blessing" ueber seiner Wahl (Autor,
                    // 27.09.) -- der ist nach gut einer Sekunde durch. Der
                    // Truppenbildschirm zeigt sich vorher von selbst; steht
                    // sein "Quick Attack" schon da, bleibt es bei der Frist.
                    // (Im selben Foto nachgesehen -- ein eigener Blick dafuer
                    // hat im Test 04:43 den Truppenbildschirm verspaetet.)
                    if (treffer(da, T_FINISH_SELECT) != null && !da.containsKey(T_QUICK_ATTACK)
                            && !da.containsKey(T_QUICK_ATTACK_ALT)) {
                        grenze = KASTEN_FENSTER_MS;
                    }
                    continue;
                }
                Log.debug("Kathedrale", "Kastenblick Fenster nach " + nach + " ms");
                return fensterKette(ctx, runs);
            }
        }
        while (System.currentTimeMillis() - los < FOTOZYKLUS_MAX_MS) {
            screen = turboFrischSchnell(screen, ctx, segenTaktMs);
            if (screen == null) continue;   // Bild liess sich (noch) nicht holen
            if (ctx.matcher().has(T_DEPLOY_SCREEN) && ctx.visible(screen, T_DEPLOY_SCREEN)) {
                Log.debug("Kathedrale", "Fotozyklus Kampf nach "
                        + (System.currentTimeMillis() - los) + " ms");
                return handleSquad(screen, ctx, runs);
            }
            if (overlayOpen(screen, ctx)) return Outcome.ACTED;
        }
        return Outcome.ACTED;
    }

    /**
     * Laeuft gerade ein Kampf (Knopf gedrueckt, "Home" noch nicht)? Dann ist
     * es normal, dass der Bot nichts erkennt: Kampf und Siegesbanner.
     * Hoechstens KAMPF_RUHE_MS lang -- danach gilt wieder alles wie sonst.
     */
    /** Autor 17.10.: in der Kathedrale schon nach 20 s ein Bericht -- nur waehrend eines Kampfes (bis ~30 s) 60 s. */
    @Override
    public long haengerBerichtNachS() {
        // 23.10. (Berichte #BV9N u. a., Dirk): nach jedem Durchlauf laedt das Spiel neu, auf langsamen PCs
        // steht das Ladebild ~28 s -- in der ersten Minute nach dem Abschluss erst nach 60 s melden.
        if (System.currentTimeMillis() - durchlaufFertigUm < 60_000) return 60;
        return imKampf() ? 60 : 20;
    }

    /** Wann der letzte Durchlauf abgeschlossen wurde -- fuer haengerBerichtNachS(). */
    private long durchlaufFertigUm = 0;

    private boolean imKampf() {
        return battlePending && System.currentTimeMillis() - kampfGestartetUm < KAMPF_RUHE_MS;
    }

    /**
     * Segen: immer die mittlere der drei Karten, danach bestaetigen.
     *
     * Die Reihenfolge ist entscheidend. "Confirm selection" ist von Anfang an
     * sichtbar, und frueher stand er hier zuerst -- der Bot druckte also auf
     * Bestaetigen, ohne je eine Karte gewaehlt zu haben. Das tut nichts, und
     * der Bildschirm blieb stehen. Erst waehlen, dann bestaetigen.
     */
    private Outcome handleBlessing(Mat screen, BotContext ctx, RunTracker runs) {

        if (!blessingPicked) {
            int x = (int) (ctx.width() * BLESSING_MIDDLE_X);
            int y = (int) (ctx.height() * BLESSING_MIDDLE_Y);
            Log.action("Klick", Sprache.t("Pick the middle blessing at ", "Mittleren Segen waehlen bei ") + x + "," + y);
            // Karla 17.10.: 92 Mal die Karte gewaehlt, nichts geschah. Ab dem dritten Mal ohne Bestaetigen
            // kontrolliert tippen (und notfalls anders).
            if (++segenKartenKlicks >= 2) {
                tippeKontrolliert(ctx, x, y, Sprache.t("Blessing card", "Segen-Karte"));
            } else {
                ctx.input().click(x, y, 8);
            }
            blessingPicked = true;

            // Turbo (Weg c): Waehlen und Bestaetigen in EINEM Schritt.
            if (istTurbo()) {
                screen = turboFrischSchnell(screen, ctx, segenWahlMs);
                if (turboKlick(screen, ctx, T_BLESSING_OK, Sprache.t("Confirm blessing", "Segen bestaetigen"))) {
                    segenKartenKlicks = 0;
                    etagennetz.letzterAbgeschlossen();
                    runs.progress(Sprache.t("Blessing picked", "Segen gewaehlt"));
                    zeit.phase(Zeitbericht.WEGWAHL);
                    karteKommtNeu();   // ab jetzt steigt der Schriftzug ueber die Karte
                    if (fotozyklusEtage) return nachSegenAufEtage(screen, ctx, runs);
                    warte(ctx, 500);
                    return Outcome.ACTED;
                }
                // Karla 17.10.: 298 Karten, nur 23 Mal bestaetigt -- je Segen ~15 s. "Bestaetigen" sitzt immer an
                // derselben Stelle, und ohne gewaehlte Karte tut der Knopf nichts: gleich dort tippen (Bild, andere
                // Groesse oder die bekannte Stelle), statt die Karte nach 1,2 s noch einmal zu waehlen.
                Optional<int[]> sofort = knopfErsatz(screen, ctx, T_BLESSING_OK, true, 1);
                if (sofort.isPresent() && ersatzTippen(ctx, sofort.get(), T_BLESSING_OK,
                        Sprache.t("Confirm blessing", "Segen bestaetigen"))) {
                    segenKartenKlicks = 0;
                    etagennetz.letzterAbgeschlossen();
                    runs.progress(Sprache.t("Blessing picked", "Segen gewaehlt"));
                    zeit.phase(Zeitbericht.WEGWAHL);
                    karteKommtNeu();
                    warte(ctx, 500);
                }
                return Outcome.ACTED;
            }

            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }

        if (click(screen, ctx, T_BLESSING_OK, Sprache.t("Confirm blessing", "Segen bestaetigen"))) {
            segenKartenKlicks = 0;
            etagennetz.letzterAbgeschlossen();
            runs.progress(Sprache.t("Blessing picked", "Segen gewaehlt"));
            zeit.phase(Zeitbericht.WEGWAHL);
            karteKommtNeu();   // ab jetzt steigt der Schriftzug ueber die Karte
            warte(ctx, 500);
            return Outcome.ACTED;
        }
        // Karla 17.10.: "Bestaetigen" nicht erkannt -- robust nachsehen bzw. die bekannte Stelle antippen.
        Optional<int[]> ersatz = knopfErsatz(screen, ctx, T_BLESSING_OK, true, 1);
        if (ersatz.isPresent()) {
            ersatzTippen(ctx, ersatz.get(), T_BLESSING_OK, Sprache.t("Confirm blessing", "Segen bestaetigen"));
            segenKartenKlicks = 0;
            karteKommtNeu();
            warte(ctx, 500);
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    // ------------------------------------------------------------------
    //  Robuste Knoepfe (Karla 17.10., Laptop + Samsung-Monitor, andere Windows-Skalierung)
    // ------------------------------------------------------------------

    /**
     * Bekannte Mitte (Anteil von Breite und Hoehe bei 1159 x 770): dc_blessing_ok oben links 487,623 (189 x 69),
     * dc_deploy_best_alt 476,344 (209 x 33), dc_quick_attack 666,607 (167 x 62) -- gemessen im Hauptbot und in
     * Karlas gemerkten Stellen.
     */
    private static final java.util.Map<String, double[]> FESTE_MITTE = java.util.Map.of(
            T_BLESSING_OK, new double[]{581.5 / 1159.0, 657.5 / 770.0},
            T_DEPLOY_BEST, new double[]{580.5 / 1159.0, 360.5 / 770.0},
            T_QUICK_ATTACK, new double[]{749.5 / 1159.0, 638.0 / 770.0});
    /** Wie oft die Vorlage hintereinander auch robust nicht zu finden war (je Vorlage). */
    private final java.util.Map<String, Integer> ersatzFehl = new java.util.HashMap<>();
    /** Segen-Karte gewaehlt, ohne dass "Bestaetigen" kam (Karla: 92 Mal). */
    private int segenKartenKlicks;

    /**
     * Wenn die normale Suche einen Knopf nicht findet:
     *   0. ein auf diesem PC selbst gelerntes Bild (Name_lokal, siehe ersatzTippen) an der bekannten Stelle,
     *   1. das normale Bild an der bekannten Stelle (+-60 px) in den Groessen 90 bis 110 % mit Schwelle 0,65,
     *   2. beim dritten Mal hintereinander ohne Fund die bekannte Stelle einfach antippen.
     * Liefert {x, y, Ausschnitt x, y, Breite, Hoehe, Stufe} oder leer. Nur fuer Knoepfe, die immer an derselben
     * Stelle sitzen (FESTE_MITTE) -- sonst nie blind.
     */
    private Optional<int[]> knopfErsatz(Mat screen, BotContext ctx, String vorlage) {
        return knopfErsatz(screen, ctx, vorlage, true, 3);
    }

    private Optional<int[]> knopfErsatz(Mat screen, BotContext ctx, String vorlage, boolean blind) {
        return knopfErsatz(screen, ctx, vorlage, blind, 3);
    }

    /**
     * @param blind   false = nie die bekannte Stelle blind antippen (nur gelerntes Bild / andere Groesse)
     * @param blindAb ab dem wievielten Fehlschlag hintereinander blind getippt wird (Segen-Bestaetigen: 1)
     */
    private Optional<int[]> knopfErsatz(Mat screen, BotContext ctx, String vorlage, boolean blind, int blindAb) {
        double[] m = FESTE_MITTE.get(vorlage);
        if (m == null || !ctx.matcher().has(vorlage)) return Optional.empty();
        int fx = (int) Math.round(ctx.width() * m[0]), fy = (int) Math.round(ctx.height() * m[1]);
        String lokal = vorlage + "_lokal";
        if (ctx.matcher().has(lokal)) {
            Optional<Matcher.Hit> h = ctx.matcher().find(screen, lokal, 0.84);
            if (h.isPresent() && Math.abs(h.get().centerX() - fx) <= 60 && Math.abs(h.get().centerY() - fy) <= 60) {
                ersatzFehl.remove(vorlage);
                Log.action("Klick", Sprache.t("Button found with the picture learned on this PC (", "Knopf mit dem auf diesem PC gelernten Bild gefunden (")
                        + lokal + String.format(" %.2f)", h.get().score()));
                return Optional.of(new int[]{h.get().centerX(), h.get().centerY(), h.get().x(), h.get().y(),
                        h.get().width(), h.get().height(), 0});
            }
        }
        for (Matcher.Hit h : ctx.matcher().findCore(screen, vorlage, 1.0, 1.0,
                new double[]{0.90, 0.95, 1.0, 1.05, 1.10}, 0.65, 3)) {
            if (Math.abs(h.centerX() - fx) <= 60 && Math.abs(h.centerY() - fy) <= 60) {
                ersatzFehl.remove(vorlage);
                Log.action("Klick", Sprache.t("Button found with a different size/blur (", "Knopf mit anderer Groesse/unscharf gefunden (")
                        + vorlage + String.format(" %.2f) ", h.score()) + Sprache.t("at ", "bei ") + h.centerX() + "," + h.centerY());
                return Optional.of(new int[]{h.centerX(), h.centerY(), h.x(), h.y(), h.width(), h.height(), 1});
            }
        }
        if (!blind) return Optional.empty();
        int fehl = ersatzFehl.merge(vorlage, 1, Integer::sum);
        if (fehl < blindAb) return Optional.empty();
        ersatzFehl.remove(vorlage);
        warnung(Sprache.t("\"" + vorlage + "\" not recognized three times -- tapping its known place " + fx + "," + fy,
                "\"" + vorlage + "\" dreimal nicht erkannt -- tippe die bekannte Stelle " + fx + "," + fy));
        int[] groesse = ctx.matcher().sizes().getOrDefault(vorlage, new int[]{120, 40});
        return Optional.of(new int[]{fx, fy, fx - groesse[0] / 2, fy - groesse[1] / 2, groesse[0], groesse[1], 2});
    }

    /**
     * Einen Ersatz-Fund (knopfErsatz) kontrolliert antippen. Hat das gewirkt (das Bild aendert sich) und kam der Fund
     * nicht schon aus dem gelernten Bild, schneidet der Bot den Knopf aus dem Bild VOR dem Tipp aus und merkt ihn sich
     * als Name_lokal (Autor 17.10., Punkt 6: "unbedingt einbauen, das ist wichtig") -- ab dann findet er ihn auf
     * diesem PC gleich mit dem eigenen Bild.
     */
    private boolean ersatzTippen(BotContext ctx, int[] e, String vorlage, String was) {
        java.awt.image.BufferedImage vorher = ctx.frame();
        boolean gewirkt = tippeKontrolliert(ctx, e[0], e[1], was);
        if (gewirkt && e[6] > 0 && vorher != null) lernen(ctx, vorher, e, vorlage + "_lokal");
        return gewirkt;
    }

    private static void lernen(BotContext ctx, java.awt.image.BufferedImage bild, int[] e, String name) {
        try {
            int x = Math.max(0, e[2]), y = Math.max(0, e[3]);
            int w = Math.min(e[4], bild.getWidth() - x), h = Math.min(e[5], bild.getHeight() - y);
            if (w < 20 || h < 12) return;
            java.nio.file.Path ordner = de.hustlebot.core.Ordner.bei("templates").resolve("gelernt").resolve("lokal");
            java.nio.file.Files.createDirectories(ordner);
            java.nio.file.Path datei = ordner.resolve(name + ".png");
            javax.imageio.ImageIO.write(bild.getSubimage(x, y, w, h), "png", datei.toFile());
            ctx.matcher().vorlageNachladen(datei);
            Log.info("Kathedrale", Sprache.t("Learned the button \"" + name + "\" on this PC (" + w + " x " + h + ") -- found right away from now on.",
                    "Knopf \"" + name + "\" auf diesem PC gelernt (" + w + " x " + h + ") -- ab jetzt sofort gefunden."));
        } catch (Exception ex) {
            Log.debug("Kathedrale", "Lernen: " + ex.getMessage());
        }
    }

    /**
     * Klick-Kontrolle (Autor 17.10., Punkt 4; Karla: "I can see the bot clicking but nothing happens"): tippen und
     * bis 0,7 s auf eine Bildaenderung warten. Aendert sich nichts, nacheinander anders tippen: laenger druecken
     * (80 ms), die Maus vorher 150 ms ruhen lassen, dazu 6 Pixel daneben. Was hilft, gilt ab dann fuer alle Klicks
     * dieser Sitzung; hilft nichts, bleibt die alte Klickart. True, wenn sich das Bild geaendert hat.
     */
    private boolean tippeKontrolliert(BotContext ctx, int x, int y, String was) {
        if (ctx.refresh() == null) {
            ctx.input().click(x, y, 4);
            return false;
        }
        ctx.input().click(x, y, 4);
        if (ctx.bildGeaendert(30, 700)) return true;
        int alterDruck = de.hustlebot.core.Input.druckMs(), alteRuhe = de.hustlebot.core.Input.ruheVorherMs();
        int[][] arten = {{80, 0, 0}, {80, 150, 0}, {120, 150, 6}};
        for (int[] a : arten) {
            warnung(Sprache.t(was + ": nothing happened after the tap -- tapping differently (press " + a[0]
                            + " ms, mouse still " + a[1] + " ms, " + a[2] + " px aside)",
                    was + ": nach dem Tipp passierte nichts -- tippe anders (druecken " + a[0] + " ms, Maus vorher "
                            + a[1] + " ms still, " + a[2] + " px daneben)"));
            de.hustlebot.core.Input.klickArtSetzen(a[0], a[1]);
            if (ctx.refresh() == null) break;
            ctx.input().click(x + a[2], y + a[2], 0);
            if (ctx.bildGeaendert(30, 900)) {
                Log.info("Kathedrale", Sprache.t("This kind of tap works -- used for all taps from now on.",
                        "Diese Klickart wirkt -- gilt ab jetzt fuer alle Klicks."));
                return true;
            }
        }
        de.hustlebot.core.Input.klickArtSetzen(alterDruck, alteRuhe);
        warnung(Sprache.t(was + ": no kind of tap changed anything.", was + ": keine Klickart hat etwas bewirkt."));
        return false;
    }

    /**
     * Altar of Blood, Dangerous Game und Camp. Ueberall die sichere Wahl:
     * weitergehen statt Kaempfer opfern oder Risiko eingehen, und im Camp die
     * Belohnung mitnehmen.
     */
    private Outcome handleChoice(Mat screen, BotContext ctx, RunTracker runs) {
        zeit.phase(Zeitbericht.AUSWAHL);

        // Die Reihenfolge ist im Spiel fest vorgegeben und war hier einmal
        // vertauscht -- daher kam das wilde Herumdruecken:
        //
        //   1. die Wahl anklicken   ("Move on" bzw. "Claim reward")
        //   2. "Finish selection"   -- vorher tut der Knopf nichts
        //   3. das Warnfenster bestaetigen, falls eins kommt
        //
        // Zuerst gedrueckt wurde frueher Schritt 2. Der Knopf blieb wirkungslos,
        // der Bot hielt die Sache trotzdem fuer erledigt und klickte blind
        // weiter -- mitten in die Einheitenreihe.

        // --- 3. Warnfenster: "Are you sure you want to finish the selection?"
        //
        // Darin steht ein ZWEITER gruener Knopf mit derselben Beschriftung,
        // und nur der zaehlt. Der grosse unten rechts bleibt sichtbar, laesst
        // sich aber nicht mehr druecken. Genau daran hing der Bot fest: er sah
        // den grossen, drueckte ihn wieder und wieder, und nichts geschah.
        if (finishConfirmPending) {
            // Die genaue Vorlage, falls aufgenommen.
            if (clickAny(screen, ctx, T_FINISH_CONFIRM, Sprache.t("Confirm the selection (warning window)", "Auswahl bestaetigen (Warnfenster)"))) {
                finishConfirmPending = false;
                choiceMade = false;
                wahlVergessen();
                runs.progress(Sprache.t("Decision confirmed", "Entscheidung bestaetigt"));
                ctx.sleepUntilChanged(500);
                return Outcome.ACTED;
            }
            // Sonst ueber die Beschriftung -- siehe warningFinishButton.
            Optional<Matcher.Hit> button = warningFinishButton(screen, ctx);
            if (button.isPresent()) {
                Matcher.Hit hit = button.get();
                Log.action("Klick", Sprache.t("Confirm the selection (button in the warning window)",
                                              "Auswahl bestaetigen (Knopf im Warnfenster)")
                        + "  [" + hit.centerX() + "," + hit.centerY()
                        + Sprache.t(", match ", ", Uebereinstimmung ") + String.format("%.3f", hit.score()) + "]");
                ctx.input().click(hit.centerX(), hit.centerY(), 6);
                finishConfirmPending = false;
                choiceMade = false;
                wahlVergessen();
                unknownScreens = 0;
                runs.progress(Sprache.t("Decision confirmed", "Entscheidung bestaetigt"));
                ctx.sleepUntilChanged(500);
                return Outcome.ACTED;
            }
            // Manche Warnfenster haben statt "Finish selection" ein "Yes".
            if (click(screen, ctx, T_FINISH_YES, Sprache.t("Confirm the selection (warning window)", "Auswahl bestaetigen (Warnfenster)"))) {
                finishConfirmPending = false;
                choiceMade = false;
                wahlVergessen();
                runs.progress(Sprache.t("Decision confirmed", "Entscheidung bestaetigt"));
                ctx.sleepUntilChanged(500);
                return Outcome.ACTED;
            }
            // Kein Warnfenster zu sehen: es kam wohl gar keines. Schalter faellt,
            // damit unten der grosse Knopf noch einmal gedrueckt werden kann.
            finishConfirmPending = false;
        }

        // --- 2b. Hat "Finish selection" gegriffen? ---------------------
        // (27.09.: 938 Mal gedrueckt, weil die Wahl nie angekommen war --
        // siehe abschlussPruefen.)
        if (choiceMade && abschlussGeklicktUm != 0 && ctx.visible(screen, T_FINISH_SELECT)) {
            int was = abschlussPruefen(System.currentTimeMillis());
            if (was == 0) {
                finishConfirmPending = true;   // das Warnfenster kann noch kommen
                ctx.sleep(tempo.ms(60));
                return Outcome.WAITING;
            }
            if (was == 3) {
                wahlFensterSchliessen(ctx);
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
        }

        // --- 1. Erst die Wahl treffen. ---------------------------------
        if (!choiceMade) {
            boolean nochmal = abschlussOhneWirkung >= WAHL_NEU_AB;
            for (String base : wahlReihenfolge()) {
                if (clickAny(screen, ctx, base, wahlGrund(base, nochmal))) {
                    choiceMade = true;
                    letzteWahl = base;
                    wahlGeklicktUm = System.currentTimeMillis();
                    // 24.10. (Ideenliste A06): 100 -> 40 ms -- 745 Mal lief der Bot in die Grenze (Mittel 131 ms), das Bild
                    // aendert sich nach der Wahl kaum; der naechste Schritt prueft ohnehin selbst, ob "Finish" schon da ist.
                    ctx.sleepUntilChanged(20, nochmal ? WAHL_WIEDER_PAUSE_MS : 40);   // "Dangerous Game", 24.09.: 1500 -> 100 (Autor: "noch weiter runter"). Das Bild aendert sich nach der Wahl kaum (nur die Markierung), der Bot sass fast immer die Obergrenze ab (107 Mal gemessen: 1,27 s). Wo die Aenderung erkannt wurde, kam sie nach ~60 ms.
                    return Outcome.ACTED;
                }
            }
            // Neu zu treffen und "Finish selection" steht da: mit niedrigerer
            // Schwelle, der Hinweis des Spiels liegt ueber der Karte.
            if (nochmal && ctx.visible(screen, T_FINISH_SELECT)) {
                for (String base : wahlReihenfolge()) {
                    for (String name : variantsOf(ctx, base)) {
                        if (name.endsWith("_screen")) continue;
                        Optional<Matcher.Hit> h = ctx.matcher().find(screen, name, WAHL_SCHWACH);
                        if (h.isEmpty()) continue;
                        Log.action("Klick", wahlGrund(base, true) + "  [" + name + Sprache.t(" at ", " bei ")
                                + h.get().centerX() + "," + h.get().centerY()
                                + String.format(Sprache.t(", match %.3f]", ", Uebereinstimmung %.3f]"), h.get().score()));
                        ctx.input().click(h.get().centerX(), h.get().centerY(), 5);
                        choiceMade = true;
                        letzteWahl = base;
                        wahlGeklicktUm = System.currentTimeMillis();
                        ctx.sleepUntilChanged(20, WAHL_WIEDER_PAUSE_MS);
                        return Outcome.ACTED;
                    }
                }
            }
            // Wahl noch nicht getroffen und kein Knopf gefunden: warten.
            // Auf keinen Fall schon abschliessen.
            return Outcome.WAITING;
        }

        // --- 2. Dann abschliessen. -------------------------------------
        // Traeges Spiel (Karla 16.10.): erst abschliessen, wenn die Wahl Zeit hatte anzukommen.
        if (wahlGeklicktUm > 0 && System.currentTimeMillis() - wahlGeklicktUm < spielTraegheitMs) {
            ctx.sleep(tempo.ms(50));
            return Outcome.WAITING;
        }
        if (click(screen, ctx, T_FINISH_SELECT, Sprache.t("Finish selection", "Auswahl abschliessen"))) {
            finishConfirmPending = true;
            abschlussGeklicktUm = System.currentTimeMillis();
            runs.progress(Sprache.t("Decision made", "Entscheidung getroffen"));
            karteKommtNeu();
            ctx.sleepUntilChanged(500);
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    /**
     * Der Abschlussbildschirm "You completed the journey to the Dark Cathedral!"
     *
     * Zwei Schritte, in dieser Reihenfolge:
     *
     *   1. ein Klick in die freie Ecke unten rechts. Dort ist nichts -- der
     *      Klick raeumt nur die Einblendung ab, die sonst ueber dem Knopf
     *      liegt. Ohne ihn reagiert "Home" nicht zuverlaessig.
     *   2. dann "Home".
     *
     * Der Eckklick ist ausdruecklich KEIN Blindklick auf gut Glueck: er
     * passiert nur, wenn die Ueberschrift des Abschlussfensters erkannt wurde,
     * und er zielt auf eine bewusst leere Stelle. Ein Schalter sorgt dafuer,
     * dass er genau einmal je Abschluss ausgeloest wird -- keine ablaufende
     * Uhr, sondern ein Schalter, der erst faellt, wenn das Fenster weg ist.
     */
    private Outcome handleJourneyDone(Mat screen, BotContext ctx, RunTracker runs) {
        zeit.phase(Zeitbericht.ABSCHLUSS);

        if (!cornerTapped) {
            // Die aufgenommene Stelle hat Vorrang, die feste Ecke ist der
            // Rueckfall. Hohe Schwelle, weil eine fast leere Flaeche sonst
            // leicht anderswo anschlaegt.
            Optional<Matcher.Hit> spot = ctx.matcher().has(T_DONE_EMPTY)
                    ? ctx.matcher().find(screen, T_DONE_EMPTY, 0.92)
                    : Optional.empty();

            int x = spot.map(Matcher.Hit::centerX)
                        .orElseGet(() -> (int) (ctx.width() * DONE_CORNER_X));
            int y = spot.map(Matcher.Hit::centerY)
                        .orElseGet(() -> (int) (ctx.height() * DONE_CORNER_Y));

            Log.info("Kathedrale", Sprache.t(
                    "Journey completed. " + floorsDone + " floors cleared.",
                    "Reise abgeschlossen. " + floorsDone + " Etagen geschafft."));
            Log.action("Klick", Sprache.t(
                    "Completion: tap the empty area to skip the "
                    + "animation  [" + x + "," + y + ", "
                    + (spot.isPresent() ? "template dc_done_empty" : "fixed spot") + "]",
                    "Abschluss: freie Flaeche antippen, um die Einblendung "
                    + "zu ueberspringen  [" + x + "," + y + ", "
                    + (spot.isPresent() ? "Vorlage dc_done_empty" : "feste Stelle") + "]"));
            ctx.input().click(x, y, 6);
            cornerTapped = true;
            unknownScreens = 0;
            ctx.sleepUntilChanged(700);
            return Outcome.ACTED;
        }

        // Erst die eigene Vorlage des Abschlussbildschirms, dann der "Home"
        // vom Kampfergebnis als Rueckfall.
        for (String name : new String[]{T_HOME_DONE, T_HOME}) {
            if (click(screen, ctx, name, Sprache.t("Confirm completion", "Abschluss bestaetigen"))) {
                etageAbschliessen(floorsDone >= MAX_FLOORS);
                String bericht = zeit.abschluss(ctx.gewartetMs());
                if (bericht != null) {
                    String zeile = "Run " + (runs.completedRuns() + 1) + ": " + bericht;
                    Log.info("Zeitbericht", zeile);
                    de.hustlebot.core.Report.merkeZeitbericht(zeile);
                }
                runs.completeRun();
                tempo.rundeFertig(runFehler == 0);
                if (runs.completedRuns() % 10 == 0) {
                    Log.info("Kathedrale", Sprache.t("Speed after " + runs.completedRuns() + " runs: pauses x "
                            + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                            "Tempo nach " + runs.completedRuns() + " Runs: Pausen x "
                            + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
                }
                runFehler = 0;
                durchlaufFertigUm = System.currentTimeMillis();
                ladebildErwarten();   // Autor 24.10. (A14/B03): das Spiel laedt, bis das Teilnahmefenster wieder da ist
                seitEventSetzen(runsSeitEvent + 1);
                // Titan-Event alle N Runs (Hauptbot 02./09.10.): nur im Kreislauf. Seit 24.10. (Autor) auch das
                // Hammer-Event alle N Runs (Einstellung, 0 = erst wenn Schluessel und Kisten alle sind).
                // Der Zaehler geht erst am ENDE des Besuchs auf 0 (titanEventSchritt / hammerEventSchritt).
                boolean titanZiel = de.hustlebot.core.Eventautomatik.titan();
                int alle = titanZiel ? titanAlleRuns() : hammerAlleRuns();
                if (!runs.isDone() && de.hustlebot.core.Eventautomatik.istAn()
                        && alle > 0 && runsSeitEvent >= alle && !eventUnterwegs && !eventLaeuft
                        && (titanZiel || !de.hustlebot.core.Zauberstabziel.erreicht())) {
                    String welches = titanZiel ? "Titan" : "Hammer";
                    Log.info("Kathedrale", Sprache.t(
                            runsSeitEvent + " cathedral runs since the last " + welches + " Event -- off to the "
                            + welches + " Event (every " + alle + " runs).",
                            runsSeitEvent + " Kathedralen-Runs seit dem letzten " + welches + "-Event -- ab ins "
                            + welches + "-Event (alle " + alle + " Runs)."));
                    eventLeer = false;   // ein frueher leeres Event zaehlt nach N Runs nicht mehr
                    eventGaenge++;
                    eventUnterwegs = true;
                    eventWeg = 0;
                    de.hustlebot.core.Eventautomatik.eventZuerstMerken();
                }
                insideCathedral = false;
                squadPrepared = false;
                floorsDone = 0;
                currentStage = 0;
                cornerTapped = false;
                etageVergessen();
                abschlussOhneHomeSeit = 0;
                ctx.sleepUntilChanged(700);
                return runs.isDone() ? Outcome.FINISHED : Outcome.ACTED;
            }
        }

        // Erst nach 2 s ohne Knopf warnen (Nachtlauf 29.09., 05:51: Warnung "neu aufnehmen",
        // 0,6 s spaeter kam der Knopf mit 1,00 -- das traege Spiel war nur langsam).
        long jetztMs = System.currentTimeMillis();
        if (abschlussOhneHomeSeit == 0) abschlussOhneHomeSeit = jetztMs;
        if (jetztMs - abschlussOhneHomeSeit > 2000 && !onCooldown("home_done_warn")) {
            warnung(Sprache.t(
                    "The completion screen is recognized, but the green "
                    + "\"Home\" on it is not. Please capture it with Capture-Images.exe as "
                    + "dc_home_done.",
                    "Der Abschlussbildschirm ist erkannt, aber der gruene "
                    + "\"Home\" darauf nicht. Bitte ihn mit Capture-Images.exe als "
                    + "dc_home_done aufnehmen."));
            cooldowns.put("home_done_warn", System.currentTimeMillis() + 30_000);
        }
        return Outcome.WAITING;
    }

    /**
     * Liest die Etagenzahl vom Sockel in der Mitte der Karte ab.
     *
     * Auf jeder Etage steht dort eine 1, 2 oder 3. Wird eine erkannt, gilt sie
     * bis zur naechsten Ablesung. Wird keine erkannt -- etwa weil die Vorlagen
     * fehlen oder gerade ein Fenster darueber liegt -- bleibt die letzte
     * stehen, statt auf null zurueckzufallen.
     */
    private void readStage(Mat screen, BotContext ctx) {
        for (int stage = 1; stage <= STAGE_NUMBERS.length; stage++) {
            String name = STAGE_NUMBERS[stage - 1];
            if (!ctx.matcher().has(name)) continue;
            if (!ctx.visible(screen, name)) continue;

            if (currentStage != stage) {
                Log.info("Kathedrale", Sprache.t(
                        "Floor " + stage + " of " + MAX_FLOORS + " (read from the pedestal in the middle).",
                        "Etage " + stage + " von " + MAX_FLOORS
                        + " (abgelesen vom Sockel in der Mitte)."));
            }
            currentStage = stage;
            return;
        }
    }

    /**
     * Laeuft gerade die letzte Etage?
     *
     * Die abgelesene Zahl hat Vorrang -- sie stimmt immer. Nur wenn keine
     * gelesen werden konnte, wird ersatzweise mitgezaehlt. Das Mitzaehlen geht
     * schief, sobald der Bot mitten in einer laufenden Reise gestartet wird:
     * dann steht der Zaehler auf null, obwohl schon zwei Waechter gefallen
     * sind. Genau daran hing der Bot vor dem fertigen Bildschirm fest.
     */
    private boolean onLastStage() {
        if (currentStage > 0) return currentStage >= MAX_FLOORS;
        return floorsDone + 1 >= MAX_FLOORS;
    }

    /**
     * Ist die Reise abgeschlossen?
     *
     * Der Knopf "End journey" unten rechts verraet es selbst: solange die Reise
     * laeuft ist er ROT, sind alle Etagenwaechter gefallen wird er GRUEN.
     *
     * Das ist ein weit verlaesslicheres Zeichen als das Mitzaehlen der Etagen.
     * Gezaehlt wird ab dem Start des Bots -- wird er mitten in einer laufenden
     * Reise angeworfen, steht der Zaehler auf null, obwohl schon alles erledigt
     * ist. Dann blieb der Bot vor dem fertigen Bildschirm stehen.
     *
     * Damit der rote nicht versehentlich fuer den gruenen gehalten wird, reicht
     * ein Treffer allein nicht: der gruene muss auch BESSER passen als der
     * rote. Solange die Reise laeuft, ist es genau umgekehrt.
     */
    private boolean journeyFinished(Mat screen, BotContext ctx) {
        // Gemeinsame Regel mit Verlies und Resten vom Verlies (29.09.).
        return Stoerfenster.gruenerEndJourney(screen, ctx, END_JOURNEY_MIN_SCORE,
                T_DC_END_JOURNEY, T_END_JOURNEY_GREEN).isPresent();
    }

    /**
     * Beendet die laufende Reise.
     *
     * Erst der rote "End journey" unten rechts, dann die Sicherheitsabfrage.
     * Das Abschlussfenster danach raeumt Schritt 2 ab -- dort wird auch der
     * Durchlauf gezaehlt und floorsDone wieder auf null gesetzt.
     *
     * Liefert null, wenn hier gerade nichts zu tun ist. Dann laeuft der
     * normale Ablauf weiter, statt dass der Bot stehenbleibt.
     */
    private Outcome endJourney(Mat screen, BotContext ctx, RunTracker runs) {
        reiseEndeBis = System.currentTimeMillis() + REISE_ENDE_MS;   // A13: jetzt wird das Abschlussfenster erwartet

        // Die Abfrage zuerst: steht sie offen, ist der Knopf dahinter tot.
        if (click(screen, ctx, T_END_JOURNEY_YES, Sprache.t("Confirm ending", "Beenden bestaetigen"))) {
            ctx.sleepUntilChanged(500);
            return Outcome.ACTED;
        }

        // Reihenfolge: erst die eigene Vorlage der Kathedrale, dann der gruene
        // aus dem Verlies -- gruen ist er, wenn alles abgeschlossen ist. Der
        // rote ganz zuletzt, nur fuer den Fall.
        for (String name : new String[]{
                T_DC_END_JOURNEY, T_END_JOURNEY_GREEN, T_END_JOURNEY}) {
            if (click(screen, ctx, name, Sprache.t("End journey (" + MAX_FLOORS + " floors cleared)",
                                                   "Reise beenden (" + MAX_FLOORS + " Etagen geschafft)"))) {
                // Die letzte Etage fuer !stats verbuchen: gruener Knopf = der
                // letzte Waechter ist gefallen (25.09.: bei Weg b stand hier
                // sonst "nicht besiegt", weil floorsDone nur Etagenwechsel zaehlt).
                etageAbschliessen(!T_END_JOURNEY.equals(name));
                runs.progress(Sprache.t("Ending the journey", "Reise wird beendet"));
                ctx.sleepUntilChanged(500);
                return Outcome.ACTED;
            }
        }

        // Letzter Ausweg: Der Knopf IST da, die Vorlage passt nur nicht genau
        // genug -- beim Event kam er auf 0,69 statt der noetigen Schwelle.
        // Hierher kommt der Ablauf nur, wenn alle Etagen geschafft sind, ein
        // Klick auf die beste Fundstelle ist dann richtig. Ohne das lief der
        // Bot in Autors Protokoll 151 mal dieselbe Zeile, bis die Notbremse
        // griff (19.09.).
        // Zuerst die billige Pruefung: Steht "Next floor" da, ist die Reise gar
        // nicht fertig -- dann hier gar nichts suchen und erst recht nicht
        // klicken. Sonst traefe es den ROTEN Knopf und wuerfe die angefangene
        // Reise samt Schluessel weg.
        if (ctx.matcher().has(T_NEXT_FLOOR) && ctx.visible(screen, T_NEXT_FLOOR)) {
            return null;
        }

        // Gesucht wird NUR nach den gruenen Vorlagen. Die rote (T_END_JOURNEY)
        // gehoert hier nicht hin: sie steht auch mitten in der Reise da, und
        // ein Klick darauf waere ein Abbruch statt eines Abschlusses.
        Optional<Matcher.Hit> ungenau = Optional.empty();
        double beste = 0;
        for (String name : new String[]{T_DC_END_JOURNEY, T_END_JOURNEY_GREEN}) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> treffer = ctx.matcher().find(screen, name, 0.60);
            if (treffer.isPresent() && treffer.get().score() > beste) {
                beste = treffer.get().score();
                ungenau = treffer;
            }
        }
        // Die Warnung "nicht erkannt" erst, wenn auch dieser Rueckfallweg nichts findet
        // (Nachtlauf 29.09., 05:09: erst "nicht erkannt -- neu aufnehmen", gleich danach
        // "ungenau erkannt (1,00)" -- der Knopf kam nur einen Moment spaeter).
        if (ungenau.isEmpty() && !onCooldown("end_journey_warn")) {
            warnung(Sprache.t(
                    "Three floors are cleared, but the button "
                    + "\"End journey\" was not recognized. Please capture the GREEN button "
                    + "with Capture-Images.exe as dc_end_journey -- it turns green "
                    + "when everything is completed, during the journey it is red.",
                    "Drei Etagen sind geschafft, aber der Knopf "
                    + "\"End journey\" wurde nicht erkannt. Bitte den GRUENEN Knopf "
                    + "mit Capture-Images.exe als dc_end_journey aufnehmen -- gruen wird er, "
                    + "wenn alles abgeschlossen ist, waehrend der Reise ist er rot."));
            cooldowns.put("end_journey_warn", System.currentTimeMillis() + 30_000);
        }
        if (ungenau.isPresent() && !onCooldown("end_journey_notfall")) {
            Matcher.Hit h = ungenau.get();
            String text = Sprache.t(
                    "\"End journey\" " + (h.score() >= 0.9 ? "recognized" : "only roughly recognized") + " ("
                    + String.format("%.2f", h.score()) + "). Clicking there because all floors are cleared.",
                    "\"End journey\" " + (h.score() >= 0.9 ? "erkannt" : "nur ungenau erkannt") + " ("
                    + String.format("%.2f", h.score()) + "). Es wird dort geklickt, weil alle Etagen geschafft sind.");
            if (h.score() >= 0.9) Log.info("Kathedrale", text);
            else warnung(text);
            ctx.input().click(h.centerX(), h.centerY(), 6);
            cooldowns.put("end_journey_notfall", System.currentTimeMillis() + 15_000);
            unknownScreens = 0;
            runs.progress(Sprache.t("Ending the journey (rough match)", "Reise wird beendet (ungenauer Treffer)"));
            ctx.sleepUntilChanged(500);
            return Outcome.ACTED;
        }

        return null;
    }

    /**
     * Sucht den gruenen "Finish selection" IM Warnfenster.
     *
     * Das Fenster zeigt denselben Knopf noch einmal, aber schmaler: der grosse
     * unten rechts ist gut 200 Punkte breit, der im Fenster nur gut 150. Eine
     * Vorlage vom ganzen Knopf passt deshalb immer nur auf einen von beiden --
     * und der Bot fand jedes Mal den grossen, den man gerade nicht druecken
     * kann.
     *
     * Der Ausweg: nicht den Knopf suchen, sondern nur seine Beschriftung. Die
     * ist in beiden gleich. Damit es auch dann klappt, wenn die Schrift im
     * Fenster ein wenig kleiner ausfaellt, wird sie in mehreren Groessen
     * gesucht. Von den Fundstellen wird die genommen, die NICHT der grosse
     * Knopf ist.
     *
     * Das ist kein Raten auf gut Glueck: geklickt wird eine Stelle, an der die
     * Beschriftung tatsaechlich gefunden wurde.
     */
    private Optional<Matcher.Hit> warningFinishButton(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_FINISH_SELECT)) return Optional.empty();

        // Wo steht der grosse Knopf? Genau der ist es nicht.
        Optional<Matcher.Hit> big = ctx.matcher().find(screen, T_FINISH_SELECT, 0.90);

        List<Matcher.Hit> labels = ctx.matcher().findCore(
                screen, T_FINISH_SELECT, 0.55, 0.62, LABEL_SCALES, 0.82, 4);

        for (Matcher.Hit label : labels) {
            if (big.isPresent()
                    && Math.abs(label.centerX() - big.get().centerX()) < 90
                    && Math.abs(label.centerY() - big.get().centerY()) < 45) {
                continue;
            }
            return Optional.of(label);
        }
        return Optional.empty();
    }

    // ==================================================================
    //  Etagenkarte und Wegfindung
    // ==================================================================

    /**
     * Stehen wir auf der Etagenkarte?
     *
     * Erstes Kennzeichen ist der gruene Hinweistext rechts. Als zweiter Weg
     * zaehlt "End journey" zusammen mit dem Wissen, dass wir in der Kathedrale
     * sind -- so wie beim Verlies, wo ein einziges Kennzeichen sich als zu
     * wenig erwiesen hat.
     */
    private boolean isFloorMap(Mat screen, BotContext ctx) {

        // Liegt ein Fenster darueber, ist die Karte zwar zu sehen, aber tot.
        // Beim Siegesbanner etwa scheint die Etage abgedunkelt durch, und die
        // Wegpunkte werden weiterhin erkannt -- ein Klick darauf geht ins Leere
        // und kostet nur Zeit. Erst das Fenster wegklicken, dann weiter.
        if (overlayOpen(screen, ctx)) return false;

        // Kurz nach dem Kampf ist der Uebergang noch im Gange: das Banner
        // verblasst schon, die Karte ist aber noch nicht wieder bedienbar.
        // Solange wird nicht auf Wegpunkte geklickt.
        if (onCooldown("after_battle")) return false;

        boolean looksLikeMap =
                (ctx.matcher().has(T_FLOOR_HINT) && ctx.visible(screen, T_FLOOR_HINT))
                || (insideCathedral && ctx.visible(screen, T_END_JOURNEY));
        if (!looksLikeMap) return false;

        // Zwischen Losschlagen und bestaetigtem Kampfergebnis bleibt die Karte
        // tabu. Waehrend das Banner ein- und ausblendet, ist "Home" oft noch
        // nicht zu erkennen, die Wegpunkte dahinter aber schon -- und dann
        // klickte der Bot den naechsten Stuetzpunkt an, bevor er das Ergebnis
        // bestaetigt hatte.
        if (battlePending) {
            floorMapBlocked++;
            if (floorMapBlocked <= FLOOR_MAP_BLOCK_LIMIT) return false;

            // Notausgang: kam wider Erwarten nie ein Ergebnisfenster, wird die
            // Karte wieder freigegeben, statt den Bot stehen zu lassen.
            warnung(Sprache.t(
                    "No result window came after the battle. The floor map is released again.",
                    "Nach dem Kampf kam kein Ergebnisfenster. "
                    + "Die Etagenkarte wird wieder freigegeben."));
            battlePending = false;
            floorMapBlocked = 0;
        }
        return true;
    }

    /**
     * Steht der Teilnahmebildschirm des VERLIESES vor uns?
     *
     * Zwei Bedingungen, damit es sicher ist: Ein Kennzeichen des Verlieses muss
     * da sein UND der eigene Teilnahme-Knopf darf es nicht sein. Sonst koennte
     * eine knappe Aehnlichkeit der beiden Knoepfe dazu fuehren, dass der Bot
     * den richtigen Bildschirm verlaesst.
     */
    private boolean onDungeonScreen(Mat screen, BotContext ctx) {
        boolean dungeonSigns = visibleAny(screen, ctx, T_DUNGEON_PARTICIPATE, T_DUNGEON_TAB);
        if (!dungeonSigns) return false;

        return !ctx.visible(screen, T_PARTICIPATE);
    }

    /** Liegt gerade ein Fenster ueber der Etagenkarte? */
    private boolean overlayOpen(Mat screen, BotContext ctx) {
        if (visibleAny(screen, ctx,
                T_HOME, T_WAYPOINT_DONE, T_BLESSING,
                T_DEPLOY_SCREEN, T_YES, T_MOVE_ON, T_CLAIM_REWARD,
                T_JOIN_SQUAD)) {
            return true;
        }
        // Autor 24.10. (Ideenliste A13): die Titel des Abschluss- und des Verbuendeten-Fensters sind Sicherheitsbilder (ganzes
        // Bild, 25 ms je Suche): Abschluss wird nach "End journey" und auf der letzten Etage jedes Mal gesucht (sonst alle
        // SELTEN_TAKT Bilder), der Verbuendete erscheint mit seinem eigenen Knopf (T_JOIN_SQUAD oben).
        return (journeyDoneZuPruefen() && visibleAny(screen, ctx, T_JOURNEY_DONE))
                || (seltenDran() && visibleAny(screen, ctx, T_ALLY_SCREEN));
    }

    /** Bis wann das Abschlussfenster der Reise erwartet wird (nach "End journey"), Autor 24.10. (A13). */
    private long reiseEndeBis = 0;
    private static final long REISE_ENDE_MS = 20_000;

    /** Wird jetzt nach dem Abschlussfenster (dc_journey_done) gesehen? Immer, wenn es kommen kann; sonst alle SELTEN_TAKT Bilder. */
    private boolean journeyDoneZuPruefen() {
        return seltenDran() || cornerTapped || onLastStage() || System.currentTimeMillis() < reiseEndeBis;
    }

    /** Kann der Knopf "End journey" jetzt schon gruen sein? Nach dem letzten Waechter -- auf Etage 1 und 2 nie. */
    private boolean endeMoeglich() {
        return seltenDran() || currentStage == 0 || onLastStage() || System.currentTimeMillis() < reiseEndeBis;
    }

    /**
     * Waehlt den naechsten Stuetzpunkt.
     *
     * SO FUNKTIONIERT EINE ETAGE -- das ist der Kern des ganzen Modus:
     *
     * Ziel ist der Etagenwaechter. Er steht immer an einer Stelle, an der
     * anfangs ein FRAGEZEICHEN liegt. Ein Fragezeichen ist ein unentdeckter
     * Stuetzpunkt: anklicken laesst es sich nicht, ein Klick darauf geht ins
     * Leere. Aufgedeckt wird es, indem der Stuetzpunkt DANEBEN abgeschlossen
     * wird -- danach steht dort ein richtiges Symbol, und mit etwas Glueck ist
     * es der Waechter.
     *
     * Daraus ergibt sich der kuerzeste Weg von selbst:
     *
     *   1. Waechter aufgedeckt und offen  -> direkt hin.
     *   2. Waechter aufgedeckt, aber noch gesperrt -> zu dem offenen
     *      Stuetzpunkt, der ihm am naechsten liegt.
     *   3. Waechter noch nicht aufgedeckt -> zu dem offenen Stuetzpunkt, der
     *      dem naechstgelegenen Fragezeichen am naechsten liegt. Genau der
     *      deckt es auf.
     *
     * Fragezeichen werden dabei NIE angeklickt. Genau das hat der Bot vorher
     * getan, und es passierte jedes Mal nichts.
     *
     * Variante b unterscheidet sich nur darin, dass unterwegs jeder offene
     * Ereignis-Stuetzpunkt mitgenommen wird.
     */
    private Outcome handleFloorMap(Mat screen, BotContext ctx, RunTracker runs) {
        // Wann ist die Karte (wieder) erschienen? karteKommtNeu() setzt 0 --
        // nach jedem Wegpunkt, Segen und geschlossenen Fenster.
        long jetzt = System.currentTimeMillis();
        if (karteDaSeit == 0) karteDaSeit = jetzt;
        karteBildUm = jetzt;
        return etagenkarte(screen, ctx, runs);
    }

    /**
     * Die Etagenkarte war weg (Kampf, Segen, Fenster) und kommt gleich neu:
     * Die Zeit fuer den zweiten Blick beginnt von vorn, und was vorher zu
     * sehen war, gilt nicht mehr (ein Boss ist jetzt besiegt, ein
     * Fragezeichen aufgedeckt).
     */
    private void karteKommtNeu() {
        karteDaSeit = 0;
        gesehenBosse.clear();
        gesehenFragen.clear();
    }

    /** Neue Etage oder neue Reise: besuchte Stuetzpunkte und der gemerkte Waechter gelten nicht mehr. */
    private void etageVergessen() {
        visited.clear();
        waechterDieserEtage = null;
        karteKommtNeu();
        if (etagennetz.gespielteStuetzpunkte() > 0) {
            // "Bestmoeglich" ist am Ende gerechnet, wenn Anfang, Bosse und
            // Waechter bekannt sind -- der Plan am Anfang kennt noch nicht
            // alle Fragezeichen und liegt deshalb oft darunter.
            int bestmoeglich = etagennetz.bestmoeglich();
            Log.info("Kathedrale", Sprache.t(
                    "Route planning: " + etagennetz.gespielteStuetzpunkte()
                    + " waypoints played on this floor"
                    + (etagennetz.planAmAnfang() > 0
                            ? ", the plan at the start foresaw at least " + etagennetz.planAmAnfang()
                            : " (no plan, old route search)")
                    + (bestmoeglich > 0 ? ", the best possible was " + bestmoeglich + "." : "."),
                    "Wegplanung: auf dieser Etage " + etagennetz.gespielteStuetzpunkte()
                    + " Stuetzpunkte gespielt"
                    + (etagennetz.planAmAnfang() > 0
                            ? ", der Plan am Anfang sah mindestens " + etagennetz.planAmAnfang() + " vor"
                            : " (ohne Plan, alte Wegsuche)")
                    + (bestmoeglich > 0 ? ", bestmoeglich waren " + bestmoeglich + "." : ".")));
        }
        etagennetz.neueEtage();
    }

    /**
     * Die Sockel fuer das Wegenetz: OHNE den pauschalen Randfilter, dafuer
     * ohne die Ecken, in denen Knoepfe des Spiels in denselben Farben sitzen.
     *
     * Test 27.09., 03:12: Auf einer Karte liegt der Waechter ganz links
     * (x=149), der Randfilter schneidet bei x=150. Der Stuetzpunkt hatte damit
     * keinen Sockel, also keine Linie -- die Planung hat geraten, an welchem
     * Nachbarn er haengt, und fuenf Stuetzpunkte umsonst gespielt (14 statt 8).
     */
    private static List<Matcher.Hit> sockelFuerNetz(Mat screen, boolean nurTuerkis) {
        List<Matcher.Hit> alle = sockelKnoten(screen, false, nurTuerkis);
        int w = screen.cols(), h = screen.rows();
        List<Matcher.Hit> aus = new ArrayList<>();
        for (Matcher.Hit s : alle) {
            double x = s.centerX() / (double) w, y = s.centerY() / (double) h;
            if (x < 0.085) continue;                       // Leiste links: Hilfe, Laden, Tasche
            if (x < 0.135 && y > 0.76) continue;           // "Home" unten links
            if (y < 0.075) continue;                       // obere Leiste
            if (x > 0.78 && y < 0.13) continue;            // Diamanten, Particle of Light
            if (x > 0.80 && y > 0.72) continue;            // Hinweis und "End journey" unten rechts
            if (y > 0.93 || x > 0.965) continue;           // aeusserster Rand
            aus.add(s);
        }
        return aus;
    }

    /** "Naechste Etage" ist gedrueckt, die neue Karte war noch nicht da. */
    private boolean etageLaedt = false;
    /** Laengstens so lange auf die neue Etage warten (Ladebild des Spiels). */
    private static final long ETAGE_LADEN_MAX_MS = 6000;
    /** Das Ladebild steht mindestens so lange (im Video 1,2 s); kuerzer war es nur ein Flackern. */
    private static final long ETAGE_LADEN_MIN_MS = 400;
    /** Steht der Hinweis der alten Karte so lange weiter da, laedt nichts. */
    private static final long ETAGE_LADEN_BEGINN_MS = 1500;

    /**
     * ETAGENWECHSEL (27.09.): Nach "Naechste Etage" zeigt das Spiel ein
     * Ladebild mit Balken (im Video 1,2 s, spaet in der Nacht ueber 2 s).
     * Bisher hat der Bot in der Zeit Bild um Bild ganz abgesucht (12 Bilder
     * "nichts erkannt", je rund 0,2 s) und die neue Karte erst mit dem
     * naechsten ganzen Bild bemerkt. Jetzt sieht er nur auf die Stelle des
     * Hinweises unten rechts: erst muss er weg sein (Ladebild), dann wieder da
     * (neue Karte). Das kostet kaum Rechenzeit, und die Karte wird gleich
     * ausgewertet.
     *
     * Null heisst: nichts zu warten, der normale Ablauf macht weiter.
     */
    private Outcome aufNeueEtageWarten(BotContext ctx, RunTracker runs) {
        if (!istTurbo() || !kastenblick || !ctx.kastenBekannt(T_FLOOR_HINT)) {
            etageLaedt = false;
            return null;
        }
        String[] blick = ctx.matcher().has(T_YES) ? new String[]{T_FLOOR_HINT, T_YES}
                                                  : new String[]{T_FLOOR_HINT};
        long los = System.currentTimeMillis();
        long wegSeit = 0;
        while (System.currentTimeMillis() - los < ETAGE_LADEN_MAX_MS) {
            if (Thread.currentThread().isInterrupted()) break;
            java.util.Map<String, Matcher.Hit> da = ctx.kastenBlicke(0.84, blick);
            long jetzt = System.currentTimeMillis();
            // Die Nachfrage "Are you sure ...?" steht da: Die bestaetigt der
            // normale Ablauf auf einem frischen Bild, danach geht es hier weiter.
            if (da.containsKey(T_YES)) return Outcome.ACTED;
            if (!da.containsKey(T_FLOOR_HINT)) {
                if (wegSeit == 0) wegSeit = jetzt;
            } else if (wegSeit > 0 && jetzt - wegSeit >= ETAGE_LADEN_MIN_MS) {
                etageLaedt = false;
                Log.debug("Kathedrale", "Kastenblick neue Etage nach " + (jetzt - los)
                        + " ms (Ladebild " + (jetzt - wegSeit) + " ms)");
                Mat frisch = ctx.refresh();
                if (frisch != null && isFloorMap(frisch, ctx)) return handleFloorMap(frisch, ctx, runs);
                return Outcome.ACTED;
            } else {
                // Der Hinweis der ALTEN Karte (kurz abgedunkelt hinter der
                // Nachfrage zaehlt nicht als Ladebild).
                wegSeit = 0;
                if (jetzt - los > ETAGE_LADEN_BEGINN_MS) break;
            }
            ctx.sleep(12);
        }
        etageLaedt = false;
        return Outcome.ACTED;
    }

    /** Gezieltes Nachsehen an einem Stuetzpunkt der festen Karte: ab dieser Uebereinstimmung gilt das Motiv. */
    private static final double MOTIV_SCHWACH = 0.70;

    /**
     * Sucht ein Stuetzpunkt-Motiv genau ueber dem Sockel bei (sx, sy) -- mit
     * niedrigerer Schwelle als sonst (Test 27.09., 05:07: "Random skirmish"
     * passte an einer Stelle der Raute nur 0,83 bis 0,85).
     *
     * Das ist kein Klick ins Blaue: Gesucht wird nur, wo auf der festen Karte
     * ein OFFENER Stuetzpunkt stehen muss (sein Nachbar ist fertig), und
     * gedrueckt wird nur, wenn dort ein Motiv zu sehen ist. Ein Schloss an der
     * Stelle schliesst es aus. Null, wenn nichts passt.
     */
    private Matcher.Hit motivSuchen(Mat screen, BotContext ctx, int sx, int sy, List<Matcher.Hit> locks) {
        int x0 = Math.max(0, sx - 75), x1 = Math.min(screen.cols(), sx + 75);
        int y0 = Math.max(0, sy - 135), y1 = Math.min(screen.rows(), sy + 30);
        if (x1 - x0 < 40 || y1 - y0 < 40) return null;
        Mat ausschnitt = new Mat(screen, new org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0));
        Matcher.Hit beste = null;
        try {
            for (String base : EVENT_WAYPOINTS) {
                for (String name : variantsOf(ctx, base)) {
                    if (name.endsWith("_screen")) continue;
                    Optional<Matcher.Hit> h = ctx.matcher().imAusschnitt(ausschnitt, x0, y0, name);
                    if (h.isEmpty() || h.get().score() < MOTIV_SCHWACH) continue;
                    if (beste == null || h.get().score() > beste.score()) beste = h.get();
                }
            }
        } finally {
            ausschnitt.release();
        }
        if (beste == null || isLocked(beste, locks) || recentlyVisited(beste)) return null;
        Log.debug("Kathedrale", String.format("Wegplanung: Motiv am Stuetzpunkt [%d,%d] gezielt gefunden "
                + "(%s, Uebereinstimmung %.3f).", sx, sy, beste.name(), beste.score()));
        return beste;
    }

    /** Seit wann die Wegplanung "warten" sagt (0 = sie wartet nicht). */
    private long planWartetSeit = 0;
    private static final long PLAN_WARTEN_MAX_MS = 2000;
    /** Autor 24.10. (B02): So lange nach dem Erscheinen einer neuen Etage ist "kein Stuetzpunkt anklickbar" noch keine Warnung wert. */
    private static final long ETAGE_AUFBAU_MS = 4000;

    /** Letztes Foto einer neuen Karte (Anfang/Ende), damit je Etage nur eins je Art entsteht. */
    private String letztesEtagenFoto = "";
    private int etagenFotos = 0;
    /** Hoechstens so viele Fotos neuer Karten je Programmstart. */
    private static final int MAX_ETAGEN_FOTOS = 24;

    /** Foto einer noch unbekannten Etagenkarte nach logs\etagen -- zum Nachmessen des gelernten Netzes. */
    private void etagenFoto(Mat screen, String art) {
        String marke = etagenGesamt + "-" + floorsDone + "-" + art;
        if (marke.equals(letztesEtagenFoto) || etagenFotos >= MAX_ETAGEN_FOTOS) return;
        letztesEtagenFoto = marke;
        etagenFotos++;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "etagen");
            String name = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss")) + "_" + art + ".jpg";
            if (de.hustlebot.core.Beleg.schreiben(ordner.resolve(name).toString(), screen)) Log.debug("Kathedrale", "Neue Karte fotografiert: logs\\etagen\\" + name);
        } catch (Exception e) {
            // nur zum Nachmessen -- darf nie stoeren
        }
    }

    /** Das Wegenetz der Etage und der ausgerechnete kuerzeste Weg (27.09.). */
    private final Etagennetz etagennetz = new Etagennetz();
    /** Wegplanung an/aus (lernwerte.txt: wegplanung). Aus = die bisherige Wegsuche. */
    private boolean wegplanung = true;

    /**
     * Darf jetzt etwas Endgueltiges entschieden werden (zum Waechter gehen,
     * Notloesung "irgendein Stuetzpunkt")? Erst auf dem zweiten Blick -- und
     * nicht, solange etwas schon Gesehenes gerade fehlt: dann liegt der
     * Schriftzug davor und zieht gleich weiter.
     */
    private boolean entscheidungReif(boolean verdeckt) {
        long seit = karteBildUm - karteDaSeit;
        if (seit < zweiterBlickMs) return false;
        return !verdeckt || seit >= VERDECKT_MAX_MS;
    }

    /** Bis zum zweiten Blick: kurz warten, dann kommt ein neues Bild. */
    private Outcome aufZweitenBlickWarten(BotContext ctx) {
        long rest = karteDaSeit + zweiterBlickMs - System.currentTimeMillis();
        ctx.sleep(rest > 0 ? Math.min(rest, 150) : 60);
        return Outcome.WAITING;
    }

    /** Ist etwas aus "gesehen" auf dem jetzigen Bild nicht zu finden (und nicht gerade besucht)? */
    private boolean fehlt(List<Matcher.Hit> gesehen, List<Matcher.Hit> jetzt) {
        for (Matcher.Hit h : gesehen) {
            if (!recentlyVisited(h) && knotenIndex(jetzt, h, 30) < 0) return true;
        }
        return false;
    }

    private Outcome etagenkarte(Mat screen, BotContext ctx, RunTracker runs) {
        insideCathedral = true;
        // Der Truppenbildschirm ist vorbei -- die Notbremse fuer
        // "Deploy the best" faengt beim naechsten Mal wieder bei null an.
        aufstellVersuche = 0;
        // Die Etagenkarte ist da -- die Reise laeuft also wirklich. Damit ist
        // der Turbo-Zaehler fuer misslungene Teilnahmen hinfaellig. Ohne das
        // wuerde er auch bei ERFOLGREICHEN Teilnahmen hochzaehlen und der Bot
        // haette nach acht gelungenen Reisen faelschlich aufgehoert.
        teilnahmeVersuche = 0;
        teilnahmeErholungen = 0;
        forgetOldVisits();

        // Hier steht die Zahl auf dem Sockel offen sichtbar -- der beste Ort,
        // um die aktuelle Etage abzulesen.
        long zeit0 = System.nanoTime();
        readStage(screen, ctx);
        etageBeobachten(screen, ctx);   // Bosse/Waechter fuer !stats

        List<Matcher.Hit> locks = ctx.matcher().has(T_LOCK)
                ? ctx.matcher().findAll(screen, T_LOCK, 0.84, 20)
                : List.of();
        long zeit1 = System.nanoTime();

        // Wo liegt der Waechter?
        //
        // WICHTIG: auch ein noch gesperrter zaehlt. Er ist das Ziel der Etage
        // und zeigt die Richtung an -- und gesperrt ist er fast immer, solange
        // der Weg dorthin nicht frei ist. Frueher wurde ein gesperrter Waechter
        // wie "nicht vorhanden" behandelt. Der Bot hatte damit kein Ziel mehr
        // und nahm irgendeinen Wegpunkt. Im Protokoll stand dann 41-mal
        // hintereinander "auf der Suche nach dem Waechter", obwohl er die ganze
        // Zeit gut sichtbar auf der Karte lag.
        // ZUERST DIE KATHEDRALEN-EIGENE VORLAGE.
        //
        // "dc_guardian" gab es zweimal: einmal im Verlies-Ordner (39x83) und
        // einmal in der Kathedrale. Der Bot kennt Vorlagen nur beim
        // Dateinamen, und beim Laden gewinnt die alphabetisch letzte -- also
        // die aus dem Verlies. Die Kathedrale hat deshalb die ganze Zeit
        // gegen das falsche Bild verglichen: beste Uebereinstimmung 0,285,
        // zwanzig Mal "Waechter NICHT erkannt" in einem einzigen Bericht.
        //
        // Daraus folgte beides, worueber Autor sich beschwert hat: der Bot
        // lief nicht zum Waechter (er sah ihn nicht) und machte deshalb alle
        // Stuetzpunkte -- und er suchte die Vorlage bei jedem Schritt im
        // ganzen Bild, weil nie etwas gefunden wurde, das er sich haette
        // merken koennen.
        //
        // dc_guardian_lair ist der eindeutige Name. dc_guardian bleibt als
        // Rueckfall, damit nichts kaputtgeht, solange das Bild fehlt.
        Optional<Matcher.Hit> gefunden = waechterFinden(screen, ctx);
        // Einmal gut gesehen = gemerkt. Liegt spaeter der Schriftzug "You have
        // got a blessing" ueber seiner Schrift, gilt die gemerkte Stelle.
        if (gefunden.isPresent() && gefunden.get().score() >= WAECHTER_MERKEN_AB) {
            waechterDieserEtage = gefunden.get();
        }
        boolean waechterGemerkt = gefunden.isEmpty() && waechterDieserEtage != null;
        Optional<Matcher.Hit> guardian = gefunden.isPresent()
                ? gefunden : Optional.ofNullable(waechterDieserEtage);

        // Was auf irgendeinem Bild seit dem Erscheinen der Karte zu sehen war,
        // wird gemerkt. Fehlt davon gerade etwas, liegt der Schriftzug davor.
        List<Matcher.Hit> bosseJetzt = route != Route.FULL ? alleBosse(screen, ctx) : List.of();
        List<Matcher.Hit> fragenJetzt = questionMarks(screen, ctx);
        for (Matcher.Hit h : bosseJetzt) merkeKnoten(gesehenBosse, h);
        for (Matcher.Hit h : fragenJetzt) merkeKnoten(gesehenFragen, h);
        boolean verdeckt = fehlt(gesehenBosse, bosseJetzt) || fehlt(gesehenFragen, fragenJetzt);

        // --- Variante b: erst alles abraeumen, dann zum Waechter. --------
        //
        // WICHTIG: Das steht VOR dem Waechter. Frueher stand es dahinter --
        // und damit lief Variante b nie: Sobald der Waechter offen war, ging
        // der Bot sofort zu ihm und beendete die Etage, ohne einen einzigen
        // Stuetzpunkt mitzunehmen.
        //
        // Der Waechter kommt jetzt erst dran, wenn wirklich kein offener
        // Stuetzpunkt mehr uebrig ist. Genommen wird ueber openWaypoints, damit
        // auch neue Motive zaehlen (dc_arena, dc_skirmish_alt ...) -- die
        // fruehere Schleife kannte nur die Grundnamen.
        if (route == Route.FULL) {
            List<Matcher.Hit> open = openWaypoints(screen, ctx, locks);
            open.removeIf(this::recentlyVisited);

            if (!open.isEmpty()) {
                // Der naechstgelegene zuerst -- so entsteht ein zusammenhaengender
                // Weg statt eines Zickzacks quer ueber die Etage. Ohne Waechter
                // als Bezug zaehlt die Reihenfolge der Funde.
                Matcher.Hit next = guardian.isPresent()
                        ? chooseTowards(open, List.of(guardian.get()))
                        : open.get(0);
                return goTo(ctx, runs, next,
                        Sprache.t("Route b: take the waypoint (" + open.size() + " open)",
                                  "Variante b: Stuetzpunkt mitnehmen (" + open.size() + " offen)"));
            }
        }

        // --- a/c: DER AUSGERECHNETE WEG (27.09.). -------------------------
        //
        // Autor, 27.09.: "Er soll sich wirklich nur auf alle Boss- und
        // Etagenwaechter-Stuetzpunkte fokussieren und pro Etage den schlausten
        // Weg finden." Das Etagennetz kennt die Linien (gesehen und gelernt)
        // und rechnet die kleinste Menge Stuetzpunkte aus. Gibt es nichts her,
        // gilt darunter die bisherige Wegsuche.
        boolean planSagtWaechter = false;
        if (route != Route.FULL && wegplanung) {
            long zeit2 = System.nanoTime();
            List<Matcher.Hit> offeneJetzt = openWaypoints(screen, ctx, locks);
            offeneJetzt.removeIf(this::recentlyVisited);
            long zeit3 = System.nanoTime();
            boolean reif = entscheidungReif(verdeckt);
            etagennetz.beobachten(screen, sockelFuerNetz(screen, false), sockelFuerNetz(screen, true),
                    fragenJetzt, bosseJetzt, guardian, offeneJetzt, locks, reif);
            // Feste Karte: Wo ein offener Stuetzpunkt stehen MUSS, dort aber
            // nichts erkannt ist, gezielt und mit niedrigerer Schwelle nachsehen.
            List<Matcher.Hit> nachgesehen = new ArrayList<>();
            for (int[] ort : etagennetz.offenOhneMotiv()) {
                Matcher.Hit motiv = motivSuchen(screen, ctx, ort[0], ort[1], locks);
                if (motiv != null) nachgesehen.add(motiv);
            }
            if (!nachgesehen.isEmpty()) etagennetz.nachtragen(nachgesehen, BATTLE_WAYPOINTS);
            long zeit4 = System.nanoTime();
            Etagennetz.Plan plan = etagennetz.planen(reif);
            long zeit5 = System.nanoTime();
            // Wo bleibt die Zeit der Wegwahl? (Autor, 27.09.: "wartet so lange
            // nach jedem Stuetzpunkt".) Eine Zeile je Auswertung, zum Nachmessen.
            Log.debug("Kathedrale", String.format("Wegwahl-Zeiten: Etage/Schloesser %d ms, Waechter/Bosse/"
                    + "Fragezeichen %d ms, offene Stuetzpunkte %d ms, Netz %d ms, Plan %d ms",
                    (zeit1 - zeit0) / 1_000_000, (zeit2 - zeit1) / 1_000_000,
                    (zeit3 - zeit2) / 1_000_000, (zeit4 - zeit3) / 1_000_000,
                    (zeit5 - zeit4) / 1_000_000));

            // NIE ENDLOS WARTEN (Test 27.09., 02:36: der Bot stand zwei Minuten,
            // weil der Plan auf ein Fragezeichen wartete, das nie aufging).
            // Nach zwei Sekunden gilt fuer diesen Schritt die alte Wegsuche.
            long jetztPlan = System.currentTimeMillis();
            if (plan != null && plan.art() == Etagennetz.Art.WARTEN) {
                if (planWartetSeit == 0) planWartetSeit = jetztPlan;
                if (jetztPlan - planWartetSeit > PLAN_WARTEN_MAX_MS) {
                    if (!onCooldown("plan_wartet")) {
                        cooldowns.put("plan_wartet", jetztPlan + 10_000);
                        warnung(Sprache.t(
                                "Route planning has been waiting " + PLAN_WARTEN_MAX_MS / 1000
                                + " s for a question mark that does not open up. For this step "
                                + "the old route search applies. State: " + etagennetz.knotenText(),
                                "Wegplanung wartet seit " + PLAN_WARTEN_MAX_MS / 1000
                                + " s auf ein Fragezeichen, das nicht aufgeht. Fuer diesen Schritt gilt "
                                + "die alte Wegsuche. Stand: " + etagennetz.knotenText()));
                    }
                    plan = null;
                }
            } else {
                planWartetSeit = 0;
            }

            if (!onCooldown("plan_stand")) {
                cooldowns.put("plan_stand", jetztPlan + 300);
                Log.debug("Kathedrale", "Wegplanung: " + etagennetz.stand() + ", "
                        + offeneJetzt.size() + " offen -> "
                        + (plan == null ? "kein Plan" : plan.art() + " " + plan.grund()));
            }
            if (plan != null) {
                // Eine neue Karte: je ein Foto vom Anfang und vom Ende der
                // Etage, damit sich das gelernte Netz nachmessen laesst.
                if (etagennetz.karteNeu() && reif
                        && (etagennetz.gespielteStuetzpunkte() == 0
                            || plan.art() == Etagennetz.Art.WAECHTER)) {
                    etagenFoto(screen, plan.art() == Etagennetz.Art.WAECHTER ? "ende" : "anfang");
                }
                // Zum Nachsehen (Test 27.09., 04:43): ein offener Stuetzpunkt,
                // an dem der Bot nichts erkennt. Ein Foto je Etage.
                String unerkannt = reif ? etagennetz.unerkannt() : null;
                if (unerkannt != null) {
                    String vorher = letztesEtagenFoto;
                    etagenFoto(screen, "unerkannt");
                    if (!letztesEtagenFoto.equals(vorher)) {
                        Log.info("Kathedrale", Sprache.t(
                                "Route planning: nothing recognizable at waypoint " + unerkannt
                                + " (no motif, no lock).",
                                "Wegplanung: Am Stuetzpunkt " + unerkannt + " ist nichts zu "
                                + "erkennen (kein Motiv, kein Schloss)."));
                    }
                }
                switch (plan.art()) {
                    case SCHRITT -> {
                        // Der erste Schritt einer Etage erst nach dem zweiten
                        // Blick: Ueber der neuen Karte steht noch der Schriftzug
                        // vom Segen des Waechters und verdeckt, was oben liegt.
                        //
                        // AUSNAHME (27.09., Etagenwechsel): Eine Etage faengt
                        // mit genau EINEM offenen Stuetzpunkt an. Ist er zu
                        // sehen, gibt es nichts abzuwaegen -- das Warten auf
                        // den zweiten Blick hat je Etage 0,6 bis 0,9 s gekostet.
                        // Liegt er unter dem Schriftzug, ist keiner zu sehen,
                        // und es wird wie bisher gewartet.
                        boolean nurEiner = offeneJetzt.size() == 1
                                && knotenIndex(offeneJetzt, plan.klick(), 30) >= 0;
                        if (etagennetz.gespielteStuetzpunkte() == 0 && !reif && !nurEiner) {
                            return aufZweitenBlickWarten(ctx);
                        }
                        return goTo(ctx, runs, plan.klick(), plan.grund());
                    }
                    case WARTEN -> {
                        return aufZweitenBlickWarten(ctx);
                    }
                    case WAECHTER -> planSagtWaechter = true;
                }
            }
        }

        // --- a/c: BOSSE ZUERST. ------------------------------------------
        //
        // Autor, 18.09.: In den beiden schnellen Wegen (a und c) sollen ALLE
        // Bosse (Kampf-Wegpunkte) einer Etage gemacht werden, BEVOR es zum
        // Waechter geht. Die normalen Wegpunkte sind unwichtig -- die nimmt
        // der Bot nur, wenn sie auf dem Weg liegen (siehe unten, chooseTowards
        // zum Waechter). Frueher war es umgekehrt: der schnelle Weg mied
        // Kaempfe und rannte zum Waechter.
        //
        // Steht VOR dem Waechter: ein offener Boss geht immer vor. Erst wenn
        // kein offener Boss mehr da ist, kommt der Waechter dran.
        if (route != Route.FULL && !planSagtWaechter) {
            // Ziele: alle Bosse (Minions) und alle Fragezeichen -- auch die noch
            // gesperrten, denn die gehen beim Vorruecken von selbst auf, und
            // hinter einem Fragezeichen kann ein Boss stecken.
            List<Matcher.Hit> ziele = new ArrayList<>();
            for (Matcher.Hit boss : alleBosse(screen, ctx)) {
                if (!recentlyVisited(boss)) merkeKnoten(ziele, boss);
            }
            for (Matcher.Hit frage : questionMarks(screen, ctx)) {
                if (!recentlyVisited(frage)) merkeKnoten(ziele, frage);
            }

            List<Matcher.Hit> offene = openWaypoints(screen, ctx, locks);
            offene.removeIf(this::recentlyVisited);

            // Ueber die gepunkteten Linien: der Schritt, von dem aus es am
            // WENIGSTEN Stuetzpunkte bis zum naechsten Ziel sind. Genau so
            // zaehlt Autor auch (19.09.: vier Stuetzpunkte statt einem).
            Matcher.Hit schritt = kuerzesterSchritt(screen, ctx, offene, ziele, guardian);
            if (schritt != null) {
                // Hier wird Symbol mit Symbol verglichen, nicht Symbol mit
                // Sockel -- deshalb die enge Toleranz von 30 Pixeln und
                // nicht die 70, mit denen ein Symbol seinem Sockel
                // zugeordnet wird. Sonst staende im Protokoll "Boss", wo in
                // Wahrheit nur ein Trittstein daneben lag.
                return goTo(ctx, runs, schritt, knotenIndex(ziele, schritt, 30) >= 0
                        ? Sprache.t("Boss / question mark (shortest route)", "Boss / Fragezeichen (kuerzester Weg)")
                        : Sprache.t("Stepping stone on the shortest route to the boss", "Trittstein auf dem kuerzesten Weg zum Boss"));
            }

            // Kein brauchbares Wegenetz -> wie bisher ueber den Pixelabstand.
            List<Matcher.Hit> bosse = new ArrayList<>(offene);
            bosse.removeIf(h -> !isBattleWaypoint(h));
            if (!bosse.isEmpty()) {
                if (guardian.isPresent()) {
                    Matcher.Hit ziel = guardian.get();
                    bosse.sort(Comparator.comparingDouble(h -> pixelDistance(h, ziel)));
                }
                return goTo(ctx, runs, bosse.get(0), Sprache.t("Boss first (battle waypoint)", "Boss zuerst (Kampf-Wegpunkt)"));
            }
        }

        // Der Waechter kommt ZULETZT -- erst wenn alle Bosse gefallen sind.
        //
        // Autor, 18.09.: In Mode a (und c) muessen ALLE Bosse einer Etage
        // fallen, sonst gibt es nicht die volle Event-Belohnung. Ein Boss kann
        // hinter einem noch nicht aufgedeckten Fragezeichen stecken. Deshalb:
        // Solange es noch Fragezeichen gibt UND einen offenen Wegpunkt, um sie
        // aufzudecken, erst aufdecken -- der Waechter wartet. Sonst faellt er,
        // "Next floor" erscheint sofort, und die restlichen Bosse waeren
        // uebersprungen. Kann nichts mehr aufgedeckt werden (kein Fragezeichen
        // mehr oder kein Weg dorthin), kommt der Waechter dran -- so entsteht
        // keine Endlosschleife.
        if (guardian.isPresent() && !isLocked(guardian.get(), locks)
                && !recentlyVisited(guardian.get())) {
            // WICHTIG: hier NICHT die gerade besuchten herausfiltern.
            // "Gerade besucht" gilt nur 6 Sekunden (WAYPOINT_COOLDOWN_MS) und
            // sagt nichts darueber, ob es noch einen Weg gibt. Wuerde man sie
            // abziehen, saehe die Liste kurz nach dem Besuch leer aus -- der
            // Bot ginge zum Waechter und die Etage waere vorbei, obwohl noch
            // Fragezeichen (und dahinter Bosse) offen sind. Lieber ein paar
            // Sekunden warten, bis der Wegpunkt wieder frei ist.
            List<Matcher.Hit> nochFragen = questionMarks(screen, ctx);
            List<Matcher.Hit> trittsteine = openWaypoints(screen, ctx, locks);
            boolean nochAufzudecken = !nochFragen.isEmpty() && !trittsteine.isEmpty();

            // Bremse gegen die Endlos-Etage: laesst sich ein Fragezeichen gar
            // nicht aufdecken, wird der Waechter nach zwei Minuten trotzdem
            // genommen. Sonst liefe der Bot ewig im Kreis.
            if (nochAufzudecken) {
                if (waechterWartetSeit == 0) waechterWartetSeit = System.currentTimeMillis();
                if (System.currentTimeMillis() - waechterWartetSeit > WAECHTER_MAX_WARTE_MS) {
                    warnung(Sprache.t(
                            "For two minutes no question mark could be "
                            + "uncovered (" + nochFragen.size() + " open). The guardian is "
                            + "taken on anyway so the floor does not get stuck.",
                            "Seit zwei Minuten laesst sich kein Fragezeichen mehr "
                            + "aufdecken (" + nochFragen.size() + " offen). Der Waechter wird "
                            + "jetzt trotzdem gemacht, damit die Etage nicht haengenbleibt."));
                    nochAufzudecken = false;
                }
            } else {
                waechterWartetSeit = 0;
            }

            if (!nochAufzudecken) {
                // ERST NACH DEM ZWEITEN BLICK (Autor, 26.09., mit Video): Nach
                // dem letzten Fragezeichen kam der Segen, und 135 ms danach sah
                // der Bot weder den frisch aufgedeckten "Minion" noch ein
                // Fragezeichen, ging zum Waechter und beendete die Reise mit
                // einem Boss zu wenig. Seit 27.09. ist klar, warum: Der
                // Schriftzug "You have got a blessing" lag davor. Deshalb zwei
                // Blicke zu verschiedenen Zeiten -- taucht auf einem ein Boss
                // auf, nimmt ihn der Boss-Zweig oben.
                if (!entscheidungReif(verdeckt)) return aufZweitenBlickWarten(ctx);
                waechterWartetSeit = 0;
                return goTo(ctx, runs, guardian.get(), route == Route.FULL
                        ? Sprache.t("Floor guardian (all waypoints cleared)", "Etagenwaechter (alle Stuetzpunkte abgeraeumt)")
                        : Sprache.t("Floor guardian (all bosses done)", "Etagenwaechter (alle Bosse gemacht)"));
            }
            // sonst: erst die Fragezeichen aufdecken -- faellt unten durch zur
            // Fragezeichen-Logik.
        }

        // Die unentdeckten Stuetzpunkte. Hinter einem steckt der Waechter.
        List<Matcher.Hit> questions = questionMarks(screen, ctx);

        // Anklickbar sind nur die aufgedeckten Stuetzpunkte ohne Schloss.
        List<Matcher.Hit> candidates = openWaypoints(screen, ctx, locks);
        candidates.removeIf(this::recentlyVisited);

        // Alle 15 Sekunden einmal festhalten, was auf der Etage zu sehen ist.
        // Damit steht im naechsten Fehlerbericht schwarz auf weiss, ob der
        // Waechter ueberhaupt erkannt wird -- die Frage, an der die ganze
        // Wegfindung haengt. Kostet nichts und erspart ein Bildschirmfoto.
        if (!onCooldown("floor_report")) {
            cooldowns.put("floor_report", System.currentTimeMillis() + 15_000);

            // Auf der Etagenkarte steht oben rechts der Zaehler fuer Particle
            // of Light -- der einzige Ort, an dem er zu sehen ist. Er steigt
            // erst nach einer abgeschlossenen Reise, wird hier also beim
            // naechsten Durchgang mitgenommen. Im Turbo (Weg c) wird er auf
            // Autors Wunsch NICHT abgelesen -- das spart die Zahlenerkennung.
            if (!istTurbo()) ctx.requestCounterRead();
            String guardianInfo = guardian.isPresent()
                    ? Sprache.t("at ", "bei ") + guardian.get().centerX() + "," + guardian.get().centerY()
                      + (waechterGemerkt ? Sprache.t(" [remembered, currently hidden]", " [gemerkt, gerade verdeckt]") : "")
                      + Sprache.t(" (quality ", " (Guete ") + String.format("%.2f", guardian.get().score()) + ", "
                      + (isLocked(guardian.get(), locks) ? Sprache.t("still locked", "noch gesperrt") : Sprache.t("open", "offen")) + ")"
                    : Sprache.t("NOT recognized", "NICHT erkannt");
            if (guardian.isEmpty()) waechterBildMerken(screen, ctx);
            Log.info("Kathedrale", Sprache.t(
                    "Floor map: floor "
                    + (currentStage > 0 ? currentStage + " of " + MAX_FLOORS
                                        : "not read")
                    + " | guardian " + guardianInfo
                    + " | open waypoints: " + candidates.size()
                    + " | question marks: " + questions.size()
                    + " | locks: " + locks.size(),
                    "Etagenkarte: Etage "
                    + (currentStage > 0 ? currentStage + " von " + MAX_FLOORS
                                        : "nicht abgelesen")
                    + " | Waechter " + guardianInfo
                    + " | offene Stuetzpunkte: " + candidates.size()
                    + " | Fragezeichen: " + questions.size()
                    + " | Schloesser: " + locks.size()));
        }

        if (candidates.isEmpty()) {
            // Hier stand einmal eine Notloesung, die ersatzweise ein
            // Fragezeichen angeklickt hat. NICHT WIEDER EINBAUEN: ein
            // Fragezeichen ist ein unentdeckter Stuetzpunkt und reagiert auf
            // keinen Klick. Im Protokoll standen dadurch neunmal hintereinander
            // Klicks auf dieselbe Stelle, ohne dass etwas geschah.
            //
            // Autor 24.10. (Ideenliste B02): AM ANFANG EINER ETAGE ist das normal. 35 von 37 solcher Warnungen in 6 Stunden
            // kamen im allerersten Blick auf eine Etage mit erkannter fester Karte (Etagenwechsel zur 2./3. Etage): die Karte
            // baut sich noch auf, das erste Motiv zeigt sich 0,5 bis 1,7 s spaeter (Mittel 1,07 s) und wird dann gespielt.
            // Die Warnung schickte Autor und die Freunde dabei faelschlich zum Bilderaufnehmen. Hier also nur warten und
            // leise sagen, warum -- die echte Warnung gilt erst, wenn es nach ETAGE_AUFBAU_MS immer noch nichts zu klicken gibt.
            if (etagennetz.festeKarte() && etagennetz.gespielteStuetzpunkte() == 0
                    && karteDaSeit != 0 && System.currentTimeMillis() - karteDaSeit < ETAGE_AUFBAU_MS) {
                Log.debug("Kathedrale", "Die Etage baut sich noch auf (feste Karte erkannt, noch kein Motiv zu sehen) -- es wird gewartet.");
                return Outcome.WAITING;
            }

            // Ist kein Stuetzpunkt anklickbar, fehlt in aller Regel eine
            // Vorlage fuer ein Wegpunkt-Motiv. Dann wird gewartet und gesagt,
            // was zu tun ist -- das ist ehrlicher als sinnlose Klicks.
            if (!onCooldown("no_waypoint_warn")) {
                warnung(Sprache.t(
                        "No waypoint on the floor is clickable "
                        + "(" + questions.size() + " question marks, " + locks.size()
                        + " locks). Question marks are deliberately not clicked -- "
                        + "they are undiscovered and do not react. The template for a "
                        + "waypoint motif is probably missing: capture the picture with Capture-Images.exe, "
                        + "e.g. as dc_arena or dc_skirmish_alt.",
                        "Auf der Etage ist kein Stuetzpunkt anklickbar "
                        + "(" + questions.size() + " Fragezeichen, " + locks.size()
                        + " Schloesser). Fragezeichen werden bewusst nicht angeklickt -- "
                        + "sie sind unentdeckt und reagieren nicht. Vermutlich fehlt die "
                        + "Vorlage fuer ein Wegpunkt-Motiv: das Bild mit Capture-Images.exe "
                        + "aufnehmen, etwa als dc_arena oder dc_skirmish_alt."));
                cooldowns.put("no_waypoint_warn", System.currentTimeMillis() + 20_000);
            }
            return Outcome.WAITING;
        }

        // Worauf wird zugelaufen? Der aufgedeckte Waechter ist das eigentliche
        // Ziel. Ist er noch nicht aufgedeckt, sind es die Fragezeichen -- eines
        // davon IST er.
        Matcher.Hit target;
        String reason;

        // Auch hier zuerst ueber die gepunkteten Linien. Klappt das nicht
        // (Punkte nicht lesbar), gilt wie frueher der Pixelabstand.
        List<Matcher.Hit> netzZiele = guardian.map(List::of).orElse(questions);
        Matcher.Hit ueberNetz = kuerzesterSchritt(screen, ctx, candidates, netzZiele, guardian);
        if (ueberNetz != null) {
            return goTo(ctx, runs, ueberNetz, guardian.isPresent()
                    ? Sprache.t("towards the guardian (shortest route)", "zum Waechter (kuerzester Weg)")
                    : Sprache.t("uncovers a question mark (shortest route)", "deckt ein Fragezeichen auf (kuerzester Weg)"));
        }

        if (guardian.isPresent()) {
            target = chooseTowards(candidates, List.of(guardian.get()));
            reason = Sprache.t("on the way to the guardian", "auf dem Weg zum Waechter");
        } else if (!questions.isEmpty()) {
            target = chooseTowards(candidates, questions);
            reason = Sprache.t("uncovers the nearest question mark", "deckt das naechstgelegene Fragezeichen auf");
        } else {
            // Kein Waechter erkannt, kein Fragezeichen mehr -- aber es liegen
            // noch normale Stuetzpunkte da. Die werden mitgenommen, um zu den
            // restlichen Bossen / zum Waechter zu kommen (Autor, 18.09.:
            // "normale Stuetzpunkte sind Nebensache, muessen aber oft besiegt
            // werden, um zu den Bossen zu gelangen"). NICHT vorzeitig beenden --
            // sonst gehen auf den fruehen Etagen die naechsten Etagen verloren.
            // Die Bosse selbst hat der Boss-Schritt oben ohnehin schon zuerst
            // abgearbeitet.
            //
            // ABER ERST NACH DEM ZWEITEN BLICK (Test 27.09., 01:20): Der
            // Waechter fehlte nur, weil der Schriftzug "You have got a
            // blessing" ueber seiner Schrift lag -- der Bot nahm ein Dangerous
            // Game, das gar nicht noetig war.
            if (!entscheidungReif(verdeckt)) return aufZweitenBlickWarten(ctx);
            target = candidates.get(0);
            reason = Sprache.t("on the way to the remaining bosses / the guardian", "auf dem Weg zu den restlichen Bossen / zum Waechter");
        }

        return goTo(ctx, runs, target, reason);
    }

    /**
     * Der guenstigste naechste Schritt in Richtung eines Ziels.
     *
     * Ziele sind entweder der aufgedeckte Waechter oder -- solange der noch
     * nicht zu sehen ist -- die Fragezeichen, hinter denen er stecken kann.
     * Genommen wird der anklickbare Stuetzpunkt, der dem naechstgelegenen Ziel
     * am naechsten liegt. Damit entsteht der kurze Weg von selbst, ohne dass
     * ein Muster hinterlegt sein muss.
     *
     * Eine Ausnahme: Ist der guenstigste ein Kampf und liegt ein harmloser
     * Stuetzpunkt praktisch genauso guenstig, wird der harmlose genommen.
     * Kaempfe kosten Zeit und Leben -- sie sollen nur sein, wenn sie wirklich
     * auf dem Weg liegen, nicht weil sie zufaellig ein paar Punkte naeher
     * waren.
     */
    private Matcher.Hit chooseTowards(List<Matcher.Hit> candidates, List<Matcher.Hit> goals) {
        List<Matcher.Hit> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(hit -> distanceToNearest(hit, goals)));

        Matcher.Hit nearest = sorted.get(0);
        if (!istKampf(nearest)) return nearest;

        double limit = distanceToNearest(nearest, goals) + BATTLE_DETOUR_PX;
        for (Matcher.Hit hit : sorted) {
            if (!istKampf(hit) && distanceToNearest(hit, goals) <= limit) {
                return hit;
            }
        }
        return nearest;
    }

    /** Entfernung zum naechstgelegenen der Ziele. */
    private static double distanceToNearest(Matcher.Hit from, List<Matcher.Hit> goals) {
        double best = Double.MAX_VALUE;
        for (Matcher.Hit goal : goals) {
            best = Math.min(best, pixelDistance(from, goal));
        }
        return best;
    }

    /** Muss an diesem Wegpunkt gekaempft werden? */
    private boolean isBattleWaypoint(Matcher.Hit hit) {
        for (String name : BATTLE_WAYPOINTS) {
            if (hit.name().startsWith(name)) return true;
        }
        return false;
    }

    /** Kostet dieser Wegpunkt einen Kampf? Siehe KAMPF_WAYPOINTS. */
    private boolean istKampf(Matcher.Hit hit) {
        for (String name : KAMPF_WAYPOINTS) {
            if (hit.name().startsWith(name)) return true;
        }
        return false;
    }

    /** Klickt einen Wegpunkt an und merkt sich die Stelle. */
    private Outcome goTo(BotContext ctx, RunTracker runs, Matcher.Hit target, String what) {
        karteKommtNeu();   // nach dem Wegpunkt zaehlt die Karte als neu erschienen, sobald sie wieder da ist
        wahlVergessen();   // neuer Wegpunkt, neues Fenster
        etagennetz.gespielt(target);
        planWartetSeit = 0;
        // WO GENAU HIN?
        //
        // Sonst immer in die Mitte. Beim Waechter nicht: dessen Vorlage
        // enthaelt unten die Schrift "Guardian's lair", und die Mitte laege
        // damit zwischen Sockel und Text. Angeklickt wird der Sockel, also
        // das obere Drittel. Die Schrift gehoert zum Bild, damit es sicher
        // GEFUNDEN wird -- angefasst wird sie nicht.
        int zielX = target.centerX();
        int zielY = T_GUARDIAN_LAIR.equals(target.name())
                ? target.y() + target.height() / 3
                : target.centerY();

        Log.action("Klick", Sprache.t("Waypoint: ", "Wegpunkt: ") + what + "  [" + zielX + "," + zielY + "]");
        ctx.input().click(zielX, zielY, 6);
        visited.add(new long[]{zielX, zielY, System.currentTimeMillis()});
        unknownScreens = 0;
        runs.progress(Sprache.t("Heading for a waypoint", "Wegpunkt angesteuert"));
        zeit.phase(Zeitbericht.ANLAUF);
        if (istTurbo() && fotozyklusKampf) return nachWegpunktZumKampf(null, ctx, runs);
        ctx.sleepUntilChanged(500);
        return Outcome.ACTED;
    }

    /**
     * Die anklickbaren Stuetzpunkte: aufgedeckte Ereignisse ohne Schloss.
     *
     * Fragezeichen gehoeren ausdruecklich NICHT dazu. Sie sind unentdeckte
     * Stuetzpunkte und lassen sich nicht anklicken -- ein Klick darauf geht
     * ins Leere. Sie dienen nur als Wegweiser, siehe questionMarks().
     */
    private List<Matcher.Hit> openWaypoints(Mat screen, BotContext ctx, List<Matcher.Hit> locks) {
        List<Matcher.Hit> open = new ArrayList<>();

        for (String base : EVENT_WAYPOINTS) {
            // Wie bei den Knoepfen zaehlt jede Vorlage ab dem Grundnamen als
            // derselbe Wegpunkt: dc_skirmish, dc_skirmish_alt, dc_skirmish_2 ...
            // Ein neues Motiv braucht damit keine Codeaenderung, nur eine
            // neue Aufnahme.
            for (String name : variantsOf(ctx, base)) {
                // Ausnahme: dc_ally_screen ist die Ueberschrift eines Fensters
                // und kein Wegpunkt -- die faellt sonst unter "dc_ally".
                if (name.endsWith("_screen")) continue;

                for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, 0.84, 6)) {
                    if (!isLocked(hit, locks)) open.add(hit);
                }
            }
        }
        return open;
    }

    /**
     * Die Fragezeichen auf der Etage: noch unentdeckte Stuetzpunkte.
     *
     * Hinter einem davon steckt der Etagenwaechter. Aufgedeckt wird ein
     * Fragezeichen, indem der Stuetzpunkt daneben abgeschlossen wird -- danach
     * steht dort ein richtiges Symbol. Deshalb sind sie das Ziel, auf das der
     * Bot zulaeuft, aber nie die Stelle, auf die er klickt.
     */
    private List<Matcher.Hit> questionMarks(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_QUESTION)) return List.of();
        return ctx.matcher().findAll(screen, T_QUESTION, 0.84, 8);
    }

    /** Liegt ein Vorhaengeschloss auf diesem Wegpunkt? Dann ist er gesperrt. */
    private boolean isLocked(Matcher.Hit waypoint, List<Matcher.Hit> locks) {
        for (Matcher.Hit lock : locks) {
            if (Math.abs(lock.centerX() - waypoint.centerX()) < 34
                    && Math.abs(lock.centerY() - waypoint.centerY()) < 34) {
                return true;
            }
        }
        return false;
    }

    /** Entfernung zweier Stellen in Bildpunkten. */
    // ==================================================================
    //  Wegenetz -- welche Stuetzpunkte sind wirklich verbunden?
    // ==================================================================
    //
    // Autor, 19.09. (mit Bildern): Zwischen den Stuetzpunkten liegen gepunktete
    // Linien. NUR die zeigen, wohin man gehen kann -- "das erkennt man daran
    // das da keine punkte sind die den stuetzpunkt mit dem anderen verbinden".
    // Zwei Stuetzpunkte koennen dicht beieinander liegen und trotzdem keinen
    // Weg haben. Nach dem reinen Pixelabstand lief der Bot deshalb Umwege:
    // vier Stuetzpunkte statt einem.
    //
    // ERSTER VERSUCH UND WARUM ER SCHEITERTE: Zuerst habe ich einfach helle
    // Stellen entlang der Verbindung gezaehlt. An Autors Bildern nachgemessen
    // galten damit 57 % ALLER Bildstellen als Punkt -- der Steinboden ist zu
    // unruhig. Jede beliebige Linie waere "verbunden" gewesen.
    //
    // JETZT: Entscheidend ist nicht die Helligkeit, sondern die REGELMAESSIGKEIT.
    // Eine gepunktete Linie hat gleichmaessig verteilte Gipfel im
    // Helligkeitsverlauf. Steinboden hat unregelmaessige. Geprueft wird also,
    // ob genug Gipfel da sind UND ob ihre Abstaende zusammenpassen.
    // Das funktioniert auch bei AUSGEGRAUTEN Punkten: verglichen wird gegen den
    // Hintergrund der Linie selbst, nicht gegen einen festen Helligkeitswert.

    /** Weiter auseinander koennen zwei benachbarte Stuetzpunkte nicht liegen. */
    private static final double MAX_KANTE_PX = 240;
    /** So viel heller als der Hintergrund muss ein Punkt sein. */
    private static final int PUNKT_KONTRAST = 22;
    /** Ungefaehrer Abstand zweier Punkte der Linie. */
    private static final double PUNKT_ABSTAND_PX = 14;
    /** So weit wird rund um das Symbol nicht abgetastet -- da ist kein Punkt. */
    private static final int SYMBOL_RAND_PX = 26;

    /**
     * Versaetze quer zur Linie, in Pixeln -- die MITTE zuerst.
     *
     * Hier stand vorher eine Schleife von -14 bis +14 in Vierer-Schritten.
     * Die trifft die Null nie (-14, -10, -6, -2, 2, ...) -- ausgerechnet die
     * Mittellinie, auf der die Punktreihe am haeufigsten liegt, wurde also
     * gar nicht geprueft. Gemessen am 19.09.: mit der Null dabei kommen aus
     * denselben Sockeln 14 statt 12 Verbindungen heraus (Schnitt 1,7 -> 2,0).
     *
     * Die Reihenfolge ist Absicht: der erste Treffer beendet die Suche,
     * also gehoert der wahrscheinlichste Versatz nach vorn.
     */
    private static final int[] LINIEN_VERSATZ =
            {0, -2, 2, -5, 5, -8, 8, -11, 11, -14, 14};

    /** So weit darf ein Symbol von seinem Sockel entfernt sein (Pixel). */
    private static final double ZUORDNUNG_PX = 70;

    /** Groesse eines Sockelflecks in Pixeln -- gemessen: 230 bis 1000. */
    private static final int SOCKEL_MIN_FLAECHE = 200;
    private static final int SOCKEL_MAX_FLAECHE = 1100;

    /** Ab so vielen erkannten Sockeln wird der Farbe geglaubt. */
    private static final int SOCKEL_MIN_ANZAHL = 6;

    /**
     * Das Bild einmal in Graustufen, als einfaches Feld.
     *
     * Einzeln ueber OpenCV auf Pixel zuzugreifen ist langsam (jedes Mal ein
     * Sprung in die C-Bibliothek). Einmal am Stueck holen und danach im
     * Java-Feld nachsehen ist um ein Vielfaches schneller -- und abgetastet
     * wird hier viel.
     */
    private static final class Graustufen {
        private final byte[] px;
        private final int w;
        private final int h;

        private Graustufen(byte[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }

        static Graustufen von(Mat screen) {
            Mat grau = new Mat();
            try {
                org.opencv.imgproc.Imgproc.cvtColor(
                        screen, grau, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
                byte[] daten = new byte[(int) grau.total()];
                grau.get(0, 0, daten);
                return new Graustufen(daten, grau.cols(), grau.rows());
            } catch (Exception e) {
                return null;
            } finally {
                grau.release();
            }
        }

        int wert(int x, int y) {
            if (x < 0 || y < 0 || x >= w || y >= h) return 0;
            return px[y * w + x] & 0xFF;
        }
    }

    /**
     * Sind die beiden Stuetzpunkte durch eine gepunktete Linie verbunden?
     *
     * Gemessen wird der Helligkeitsverlauf entlang der Verbindung. Eine
     * gepunktete Linie zeigt sich als Folge regelmaessiger Gipfel.
     */
    private static boolean verbunden(Graustufen g, Matcher.Hit a, Matcher.Hit b) {
        double dist = pixelDistance(a, b);
        if (dist > MAX_KANTE_PX || dist < 1) return false;

        double ax = a.centerX();
        double ay = a.centerY();
        double dx = (b.centerX() - ax) / dist;
        double dy = (b.centerY() - ay) / dist;

        double von = SYMBOL_RAND_PX;
        double bis = dist - SYMBOL_RAND_PX;
        // Zu kurz, um eine Regelmaessigkeit zu erkennen: dann lieber nein
        // sagen. Ein falsches Ja waere teurer als eine fehlende Kante.
        if (bis - von < 3 * PUNKT_ABSTAND_PX) return false;

        double laenge = bis - von;
        int erwartet = (int) Math.round(laenge / PUNKT_ABSTAND_PX);
        // Zu wenige Punkte erwartet -- da laesst sich keine Regelmaessigkeit
        // erkennen. Haengt nicht vom Versatz ab, deshalb steht es davor.
        if (erwartet < 3) return false;

        // Die Punktreihe laeuft NICHT exakt von Mittelpunkt zu Mittelpunkt,
        // deshalb werden mehrere parallel versetzte Linien probiert.
        int n = (int) ((bis - von) / 2) + 1;
        int[] profil = new int[n];

        for (int versatz : LINIEN_VERSATZ) {
            for (int i = 0; i < n; i++) {
                double s = von + i * 2;
                double x = ax + dx * s - dy * versatz;
                double y = ay + dy * s + dx * versatz;
                int hell = 0;
                for (int q = -3; q <= 3; q++) {
                    hell = Math.max(hell, g.wert((int) Math.round(x - dy * q),
                                                 (int) Math.round(y + dx * q)));
                }
                profil[i] = hell;
            }

            // Hintergrund der Linie selbst -- so ist egal, ob die Punkte hell
            // oder ausgegraut sind und ob der Boden hell oder dunkel ist.
            int[] sortiert = profil.clone();
            Arrays.sort(sortiert);
            int hintergrund = sortiert[sortiert.length / 2];

            // Gipfel: deutlich ueber dem Hintergrund und ein lokales Maximum.
            List<Integer> gipfel = new ArrayList<>();
            for (int i = 1; i < n - 1; i++) {
                if (profil[i] < hintergrund + PUNKT_KONTRAST) continue;
                if (profil[i] < profil[i - 1] || profil[i] < profil[i + 1]) continue;
                // Zwei Messpunkte auf demselben Punkt zaehlen nur einmal.
                if (!gipfel.isEmpty() && i - gipfel.get(gipfel.size() - 1) < 3) continue;
                gipfel.add(i);
            }

            // Zu wenige Gipfel: keine Linie. Zu viele: unruhiger Untergrund.
            if (gipfel.size() < erwartet * 0.55) continue;
            if (gipfel.size() > erwartet * 1.8) continue;
            // Mit zwei Gipfeln ist jeder Abstand "regelmaessig" -- das waere
            // geschenkt. Erst ab drei sagt die Regelmaessigkeit etwas aus.
            if (gipfel.size() < 3) continue;

            // Und die Abstaende muessen zusammenpassen. Bei echtem Rauschen
            // schwanken sie stark, bei einer Punktreihe kaum.
            double schnitt = 0;
            for (int i = 1; i < gipfel.size(); i++) schnitt += gipfel.get(i) - gipfel.get(i - 1);
            schnitt /= gipfel.size() - 1;
            double abweichung = 0;
            for (int i = 1; i < gipfel.size(); i++) {
                abweichung += Math.abs((gipfel.get(i) - gipfel.get(i - 1)) - schnitt);
            }
            abweichung /= gipfel.size() - 1;
            if (abweichung <= schnitt * 0.6) return true;
        }
        return false;
    }

    /** Nimmt den Knoten auf, wenn an der Stelle nicht schon einer liegt. */
    private static void merkeKnoten(List<Matcher.Hit> knoten, Matcher.Hit neu) {
        for (Matcher.Hit da : knoten) {
            if (Math.abs(da.centerX() - neu.centerX()) < 30
                    && Math.abs(da.centerY() - neu.centerY()) < 30) {
                return;
            }
        }
        knoten.add(neu);
    }

    /** Welcher Knoten liegt an dieser Stelle? -1, wenn keiner. */
    private static int knotenIndex(List<Matcher.Hit> knoten, Matcher.Hit gesucht) {
        return knotenIndex(knoten, gesucht, ZUORDNUNG_PX);
    }

    /**
     * Der NAECHSTGELEGENE Knoten innerhalb der Toleranz. -1, wenn keiner.
     *
     * Wichtig ist "naechstgelegen", nicht "der erste, der passt": ein Symbol
     * sitzt ueber seinem Sockel, nicht darauf. Bei engen Karten kaeme sonst
     * der Nachbarsockel heraus und der Bot liefe in die falsche Richtung.
     */
    private static int knotenIndex(List<Matcher.Hit> knoten, Matcher.Hit gesucht,
                                   double toleranz) {
        int beste = -1;
        double besteDist = toleranz;
        for (int i = 0; i < knoten.size(); i++) {
            double d = pixelDistance(knoten.get(i), gesucht);
            if (d <= besteDist) {
                besteDist = d;
                beste = i;
            }
        }
        return beste;
    }

    /**
     * Den Etagenwaechter finden -- ueber die SCHRIFT "Guardian's lair".
     *
     * Warum nicht ueber das Bild des Waechters: er steht auf jeder Etage
     * woanders (Autor, 20.09.: "die stuetzpunkte und der waechter stehen
     * immer woanders ja"). Eine Vorlage mit Hintergrund passt deshalb nur an
     * genau der Stelle, an der sie aufgenommen wurde. Gemessen an drei
     * echten Etagenbildern: das ganze Bild kam auf 0,456 / 0,512 / 0,512 --
     * nie ueber die Schwelle. Genau derselbe Grund, aus dem schon die
     * Dolch-Vorlage gescheitert ist.
     *
     * Die Schrift dagegen sieht ueberall gleich aus. Gemessen mit demselben
     * Ausschnitt auf denselben drei Bildern:
     *
     *     schlechtester Treffer   0,741
     *     hoechster Fehltreffer   0,400
     *     Abstand                 0,341
     *
     * Deshalb eine eigene, niedrigere Schwelle: 0,60 liegt mit reichlich
     * Luft zwischen beiden.
     *
     * Angeklickt wird nicht die Schrift, sondern der SOCKEL darueber. Den
     * liefert die Farberkennung ohnehin schon -- sie findet alle roten und
     * tuerkisen Sockel der Etage. Gesucht wird der naechste Sockel oberhalb
     * der Schrift. So stimmt die Klickstelle, egal wo der Waechter steht.
     */
    /** Wie viele Etagenkarten ohne Waechter schon gespeichert wurden. */
    private static int waechterBilder = 0;
    // Abgeschaltet (24.09.): Die Messung ist erledigt -- auf den gespeicherten
    // Karten war der Waechter noch unter einem Fragezeichen verborgen, "nicht
    // erkannt" war also richtig. Zum erneuten Nachmessen hier wieder 40 setzen.
    private static final int MAX_WAECHTER_BILDER = 0;

    /**
     * Speichert eine Etagenkarte, auf der der Waechter NICHT gefunden wurde,
     * nach logs\waechter-fehlt\ -- hoechstens 40 Stueck je Start.
     *
     * Am 22.09. stand bei 1.040 Etagenkarten 795 Mal "Waechter NICHT
     * erkannt". Ohne die Bilder laesst sich nicht sagen, warum: steht die
     * Schrift woanders, ist sie verdeckt, oder ist der Waechter auf dieser
     * Karte schon besiegt? Mit den Bildern laesst sich die Erkennung daran
     * nachmessen. Dazu die beste Schrift-Guete ins Protokoll.
     */
    private void waechterBildMerken(Mat screen, BotContext ctx) {
        if (waechterBilder >= MAX_WAECHTER_BILDER) return;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "waechter-fehlt");
            String name = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss")) + ".png";
            boolean foto = de.hustlebot.core.Beleg.schreiben(ordner.resolve(name).toString(), screen);
            waechterBilder++;
            double beste = ctx.matcher().has(T_GUARDIAN_TEXT)
                    ? ctx.matcher().find(screen, T_GUARDIAN_TEXT, 0.0)
                          .map(Matcher.Hit::score).orElse(-1.0)
                    : -1.0;
            Log.debug("Kathedrale", "Etagenkarte ohne Waechter" + (foto ? " gespeichert: " + name : "")
                    + " (beste Schrift-Guete " + String.format("%.3f", beste) + ")");
        } catch (Exception e) {
            // nur zum Nachmessen -- darf nie stoeren
        }
    }

    private Optional<Matcher.Hit> waechterFinden(Mat screen, BotContext ctx) {
        // Erst die alten Wege: eine Vorlage, die trifft, ist immer besser.
        for (String name : new String[]{T_GUARDIAN_LAIR, T_GUARDIAN}) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> direkt = ctx.matcher().find(screen, name, 0.84);
            if (direkt.isPresent()) return direkt;
        }

        if (!ctx.matcher().has(T_GUARDIAN_TEXT)) return Optional.empty();
        Optional<Matcher.Hit> schrift =
                ctx.matcher().find(screen, T_GUARDIAN_TEXT, WAECHTER_SCHRIFT_SCHWELLE);
        if (schrift.isEmpty()) return Optional.empty();

        Matcher.Hit s = schrift.get();
        // Der Sockel sitzt ueber der Schrift: senkrecht nah, waagerecht nah.
        Matcher.Hit besterSockel = null;
        double besteEntfernung = Double.MAX_VALUE;
        for (Matcher.Hit sockel : sockelKnoten(screen, false)) {
            int dy = s.y() - sockel.centerY();          // positiv = Sockel liegt hoeher
            int dx = Math.abs(sockel.centerX() - s.centerX());
            if (dy < 0 || dy > WAECHTER_SOCKEL_MAX_HOCH) continue;
            if (dx > WAECHTER_SOCKEL_MAX_SEITLICH) continue;
            double entfernung = dy + dx;
            if (entfernung < besteEntfernung) {
                besteEntfernung = entfernung;
                besterSockel = sockel;
            }
        }

        if (besterSockel != null) {
            return Optional.of(new Matcher.Hit(T_GUARDIAN_LAIR,
                    besterSockel.x(), besterSockel.y(),
                    besterSockel.width(), besterSockel.height(), s.score()));
        }

        // Kein Sockel gefunden (verdeckt, Einblendung): dann eben die Stelle
        // ueber der Schrift. Besser ungefaehr richtig als gar nicht.
        return Optional.of(new Matcher.Hit(T_GUARDIAN_LAIR,
                s.centerX() - 20, s.y() - WAECHTER_OHNE_SOCKEL_HOCH, 40, 40, s.score()));
    }

    /**
     * Alle Stuetzpunkt-Sockel der Etage, erkannt an ihrer FARBE.
     *
     * Die Sockel leuchten tuerkis (offen oder schon erledigt) oder rot (Boss).
     * Der Steinboden dazwischen ist grau. Gemessen am 19.09. an einem
     * Bildschirmfoto der Kathedrale: Sockel Farbstaerke 200 bis 255,
     * Steinboden 56 bis 71 -- dazwischen liegen Welten, das trennt sauber.
     *
     * Ueber die Farbe zu gehen loest genau das Problem, an dem die Vorlagen
     * gescheitert sind: der Hintergrund hinter dem Dolch wechselt staendig,
     * deshalb konnte Autor nur die Klinge aufnehmen (9x54 Pixel) und die traf
     * nur 3 von 8 Sockeln. Die Farbe des Sockels bleibt immer dieselbe.
     *
     * Gemessen an einem Etagenbild vom 19.09.: 14 Sockel gefunden, 13 davon
     * echt, 12 Verbindungen, Schnitt 1,7 Nachbarn. Der eine fehlende steckte
     * unter der Einblendung "You have got a blessing!". Kosten: 4 ms je
     * Schritt -- weniger als ein einziger Vorlagenvergleich.
     */
    private static List<Matcher.Hit> sockelKnoten(Mat screen) {
        return sockelKnoten(screen, true);
    }

    /**
     * Wie oben, aber der Rand laesst sich abschalten.
     *
     * Der Randfilter haelt Knoepfe und Leisten heraus, die in denselben
     * Farben leuchten. Beim Waechter stoert er: der steht oft ganz aussen
     * (gemessen an Autors Bildern bei x=1047 von 1159) und faellt dann
     * genau durch diesen Filter. Dort wird ohnehin nur in einem kleinen
     * Bereich ueber der Schrift gesucht -- ein fremder Fleck kaeme da nicht
     * hinein.
     */
    private static List<Matcher.Hit> sockelKnoten(Mat screen, boolean mitRand) {
        return sockelKnoten(screen, mitRand, false);
    }

    /**
     * @param nurTuerkis nur die tuerkisen Sockel -- das sind die abgeschlossenen
     *                   Stuetzpunkte (ein Schwert auf tuerkisem Sockel); alle
     *                   anderen stehen auf rotem
     */
    private static List<Matcher.Hit> sockelKnoten(Mat screen, boolean mitRand, boolean nurTuerkis) {
        List<Matcher.Hit> gefunden = new ArrayList<>();
        Mat hsv = new Mat();
        Mat tuerkis = new Mat();
        Mat rotUnten = new Mat();
        Mat rotOben = new Mat();
        Mat maske = new Mat();
        Mat marken = new Mat();
        Mat werte = new Mat();
        Mat mitten = new Mat();
        try {
            org.opencv.imgproc.Imgproc.cvtColor(
                    screen, hsv, org.opencv.imgproc.Imgproc.COLOR_BGR2HSV);
            org.opencv.core.Core.inRange(hsv, new org.opencv.core.Scalar(85, 190, 70),
                    new org.opencv.core.Scalar(100, 255, 255), tuerkis);
            org.opencv.core.Core.inRange(hsv, new org.opencv.core.Scalar(0, 190, 70),
                    new org.opencv.core.Scalar(6, 255, 255), rotUnten);
            org.opencv.core.Core.inRange(hsv, new org.opencv.core.Scalar(172, 190, 70),
                    new org.opencv.core.Scalar(179, 255, 255), rotOben);
            if (nurTuerkis) {
                tuerkis.copyTo(maske);
            } else {
                org.opencv.core.Core.bitwise_or(tuerkis, rotUnten, maske);
                org.opencv.core.Core.bitwise_or(maske, rotOben, maske);
            }

            int anzahl = org.opencv.imgproc.Imgproc.connectedComponentsWithStats(
                    maske, marken, werte, mitten);
            int w = screen.cols();
            int h = screen.rows();
            // Auch die Groesse eines Sockelflecks haengt an der Fenstergroesse.
            // Gemessen an 1159x770; bei einem groesseren Fenster sind die
            // Flecken entsprechend groesser.
            double massstab = (w * (double) h) / (1159.0 * 770.0);
            int minFlaeche = (int) Math.round(SOCKEL_MIN_FLAECHE * massstab);
            int maxFlaeche = (int) Math.round(SOCKEL_MAX_FLAECHE * massstab);

            for (int i = 1; i < anzahl; i++) {
                int flaeche = (int) werte.get(i, org.opencv.imgproc.Imgproc.CC_STAT_AREA)[0];
                if (flaeche < minFlaeche || flaeche > maxFlaeche) continue;
                int mx = (int) Math.round(mitten.get(i, 0)[0]);
                int my = (int) Math.round(mitten.get(i, 1)[0]);
                // Den Rand auslassen: dort sitzen Knoepfe und Leisten in
                // denselben Farben, die sind keine Stuetzpunkte.
                //
                // Als Anteil der Fenstergroesse, nicht in festen Pixeln.
                // Gemessen wurde an 1159x770 -- dort kommen genau die alten
                // Werte 150 / 140 / 95 / 70 heraus. Bei einem anders grossen
                // Fenster wandert der Rand mit, statt mitten in die Karte zu
                // schneiden.
                if (mitRand && (mx < w * 0.129 || mx > w - w * 0.121
                        || my < h * 0.123 || my > h - h * 0.091)) continue;
                int bw = (int) werte.get(i, org.opencv.imgproc.Imgproc.CC_STAT_WIDTH)[0];
                int bh = (int) werte.get(i, org.opencv.imgproc.Imgproc.CC_STAT_HEIGHT)[0];
                gefunden.add(new Matcher.Hit("sockel", mx - bw / 2, my - bh / 2, bw, bh, 1.0));
            }
        } catch (Exception e) {
            return List.of();
        } finally {
            for (Mat m : new Mat[]{hsv, tuerkis, rotUnten, rotOben,
                                   maske, marken, werte, mitten}) {
                m.release();
            }
        }
        return gefunden;
    }

    /** ALLE Stuetzpunkte der Etage -- auch gesperrte, Fragezeichen, Waechter. */
    private List<Matcher.Hit> alleKnoten(Mat screen, BotContext ctx,
                                         Optional<Matcher.Hit> guardian) {
        // Erste Wahl ist die Farbe: sie findet auch die Sockel, fuer die es
        // gar keine Vorlage gibt, und braucht keinen einzigen Bildvergleich.
        List<Matcher.Hit> sockel = sockelKnoten(screen);
        if (sockel.size() >= SOCKEL_MIN_ANZAHL) {
            // Der Waechter ist das Ziel der ganzen Etage. Steht er auf einem
            // Sockel, den die Farbe nicht hergegeben hat (verdeckt, anders
            // eingefaerbt), kaeme er im Netz gar nicht vor -- und die Wegsuche
            // dorthin koennte nie gelingen. Dann kommt er als eigener Knoten
            // dazu.
            if (guardian.isPresent()
                    && knotenIndex(sockel, guardian.get()) < 0) {
                sockel.add(guardian.get());
            }
            // Dasselbe fuer Fragezeichen und Bosse (24.09.). Autor: "der Bot
            // schliesst manchmal Stuetzpunkte ab, die er nicht abschliessen
            // muesste." Im Protokoll: 38 Mal "deckt das naechstgelegene
            // Fragezeichen auf" (Luftlinie), kein einziges Mal ueber das
            // Wegenetz. Die Fragezeichen standen gar nicht im Netz -- die
            // Wegsuche fand kein Ziel und fiel auf die Luftlinie zurueck, die
            // gern Umwege ueber fremde Stuetzpunkte nimmt.
            for (Matcher.Hit frage : questionMarks(screen, ctx)) {
                if (knotenIndex(sockel, frage) < 0) sockel.add(frage);
            }
            for (Matcher.Hit boss : alleBosse(screen, ctx)) {
                if (knotenIndex(sockel, boss) < 0) sockel.add(boss);
            }
            return sockel;
        }

        List<Matcher.Hit> knoten = new ArrayList<>();
        for (String base : EVENT_WAYPOINTS) {
            for (String name : variantsOf(ctx, base)) {
                if (name.endsWith("_screen")) continue;
                for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, 0.84, 6)) {
                    merkeKnoten(knoten, hit);
                }
            }
        }
        // Die abgeschlossenen Stuetzpunkte (Dolch auf tuerkisem Sockel) zaehlen
        // als Knoten mit: ueber sie fuehrt der Weg, auch wenn dort nichts mehr
        // zu holen ist. Ohne sie sah der Bot nur 6 von rund 14 Stuetzpunkten
        // und es kam gar kein Netz zustande.
        for (String name : variantsOf(ctx, "dc_visited")) {
            for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, 0.84, 20)) {
                merkeKnoten(knoten, hit);
            }
        }
        for (Matcher.Hit frage : questionMarks(screen, ctx)) merkeKnoten(knoten, frage);
        guardian.ifPresent(h -> merkeKnoten(knoten, h));
        return knoten;
    }

    /** Alle Bosse (Minions) der Etage -- auch die noch gesperrten. */
    private List<Matcher.Hit> alleBosse(Mat screen, BotContext ctx) {
        List<Matcher.Hit> bosse = new ArrayList<>();
        for (String base : BATTLE_WAYPOINTS) {
            for (String name : variantsOf(ctx, base)) {
                for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, 0.84, 6)) {
                    merkeKnoten(bosse, hit);
                }
            }
        }
        return bosse;
    }

    // Das Wegenetz wird hoechstens EINMAL je Schritt gebaut und dann
    // wiederverwendet. Es neu aufzubauen kostet rund ein Dutzend
    // Vorlagenvergleiche -- das jedes Mal zu tun haette die gerade gesparten
    // Wartezeiten gleich wieder aufgefressen.
    private int netzTakt = -1;
    private List<Matcher.Hit> netzKnoten = List.of();
    private List<List<Integer>> netzKanten = List.of();

    /**
     * Baut Knoten und Kanten der Etagenkarte auf, falls das in diesem Schritt
     * noch nicht geschehen ist. False heisst: die Punkte waren nicht lesbar --
     * dann faellt der Aufrufer auf den alten Weg (Pixelabstand) zurueck.
     */
    private boolean netzAufbauen(Mat screen, BotContext ctx, Optional<Matcher.Hit> guardian) {
        if (netzTakt == schrittZaehler) return netzKnoten.size() >= 2;

        netzTakt = schrittZaehler;
        netzKnoten = List.of();
        netzKanten = List.of();

        Graustufen g = Graustufen.von(screen);
        if (g == null) return false;

        List<Matcher.Hit> knoten = alleKnoten(screen, ctx, guardian);
        if (knoten.size() < 2) return false;

        List<List<Integer>> netz = wegenetz(g, knoten);
        int kanten = 0;
        for (List<Integer> n : netz) kanten += n.size();

        if (!onCooldown("wegenetz_report")) {
            cooldowns.put("wegenetz_report", System.currentTimeMillis() + 15_000);
            Log.info("Kathedrale", Sprache.t(
                    "Path network: " + knoten.size() + " waypoints, " + (kanten / 2) + " connections recognized.",
                    "Wegenetz: " + knoten.size() + " Stuetzpunkte, "
                    + (kanten / 2) + " Verbindungen erkannt."));
        }
        // Plausibilitaetspruefung. Auf einer Etagenkarte hat ein Stuetzpunkt
        // typischerweise zwei bis vier Nachbarn. Liegt der Schnitt weit
        // darunter, waren die Punkte nicht lesbar; liegt er weit darueber,
        // hat der Untergrund getaeuscht. In beiden Faellen lieber den alten
        // Weg ueber den Pixelabstand nehmen als falsch zu routen.
        double schnittGrad = (double) kanten / Math.max(1, knoten.size());
        // Gemessen am 19.09. an einem echten Etagenbild: 14 Sockel, 11
        // Verbindungen, Schnitt 1,6 Nachbarn. Die Schwelle liegt bei 1,0 --
        // darunter haengen die meisten Sockel einzeln in der Luft und eine
        // Wegsuche darauf waere geraten, nicht gerechnet.
        if (knoten.size() < 6 || schnittGrad < 1.0 || schnittGrad > 4.5) {
            if (!onCooldown("wegenetz_unbrauchbar")) {
                cooldowns.put("wegenetz_unbrauchbar", System.currentTimeMillis() + 30_000);
                Log.info("Kathedrale", Sprache.t(
                        "Path network unusable (" + knoten.size()
                        + " waypoints, " + (kanten / 2) + " connections, average "
                        + String.format("%.1f", schnittGrad) + " neighbors). Falling back to "
                        + "the pixel distance.",
                        "Wegenetz unbrauchbar (" + knoten.size()
                        + " Stuetzpunkte, " + (kanten / 2) + " Verbindungen, Schnitt "
                        + String.format("%.1f", schnittGrad) + " Nachbarn). Es gilt wieder "
                        + "der Pixelabstand."));
            }
            return false;
        }

        netzKnoten = knoten;
        netzKanten = netz;
        return true;
    }

    /** Nachbarschaft nach den gepunkteten Linien. */
    private static List<List<Integer>> wegenetz(Graustufen g, List<Matcher.Hit> knoten) {
        List<List<Integer>> netz = new ArrayList<>();
        for (int i = 0; i < knoten.size(); i++) netz.add(new ArrayList<>());
        for (int i = 0; i < knoten.size(); i++) {
            for (int j = i + 1; j < knoten.size(); j++) {
                if (verbunden(g, knoten.get(i), knoten.get(j))) {
                    netz.get(i).add(j);
                    netz.get(j).add(i);
                }
            }
        }
        return netz;
    }

    /**
     * Schritte bis zum naechstgelegenen Ziel, fuer jeden Knoten. -1 heisst
     * "von dort kommt man an kein Ziel". Eine Breitensuche von allen Zielen
     * aus gleichzeitig -- das ist genau die Zahl, die Autor zaehlt: wie viele
     * Stuetzpunkte muessen abgeschlossen werden.
     */
    private static int[] schritteBisZiel(List<List<Integer>> netz, List<Integer> ziele) {
        int[] dist = new int[netz.size()];
        Arrays.fill(dist, -1);
        java.util.ArrayDeque<Integer> schlange = new java.util.ArrayDeque<>();
        for (int z : ziele) {
            if (z >= 0 && z < dist.length && dist[z] < 0) {
                dist[z] = 0;
                schlange.add(z);
            }
        }
        while (!schlange.isEmpty()) {
            int k = schlange.poll();
            for (int n : netz.get(k)) {
                if (dist[n] < 0) {
                    dist[n] = dist[k] + 1;
                    schlange.add(n);
                }
            }
        }
        return dist;
    }

    /**
     * Der beste naechste Schritt: der offene Stuetzpunkt, von dem aus es ueber
     * die gepunkteten Linien am kuerzesten zu einem der Ziele geht.
     *
     * Null heisst "das Wegenetz gibt nichts her" -- dann gilt wieder der alte
     * Weg ueber den Pixelabstand. Das ist Absicht: liegt ein Banner ueber der
     * Karte oder sehen die Punkte einmal anders aus, soll der Bot weiterspielen
     * wie bisher und nicht stehenbleiben.
     */
    private Matcher.Hit kuerzesterSchritt(Mat screen, BotContext ctx,
                                          List<Matcher.Hit> offene,
                                          List<Matcher.Hit> ziele,
                                          Optional<Matcher.Hit> guardian) {
        if (offene.isEmpty() || ziele.isEmpty()) return null;
        if (!netzAufbauen(screen, ctx, guardian)) return null;

        List<Matcher.Hit> knoten = netzKnoten;
        List<List<Integer>> netz = netzKanten;

        List<Integer> zielIdx = new ArrayList<>();
        for (Matcher.Hit z : ziele) {
            int i = knotenIndex(knoten, z);
            if (i >= 0) zielIdx.add(i);
        }
        if (zielIdx.isEmpty()) {
            Log.debug("Kathedrale", "Wegsuche: kein Ziel im Netz (" + ziele.size() + " Ziele).");
            return null;
        }

        // Ein Ziel ohne erkannte Verbindung (meist ein Fragezeichen: zu
        // unentdeckten Stuetzpunkten sind die Punkte blass) hing bisher in
        // der Luft -- kein Weg, also Luftlinie mit Umwegen (24.09.). Jetzt
        // gilt der naechstgelegene Stuetzpunkt als sein Nachbar. Die Zahl
        // der Schritte bis dorthin kommt weiter aus den echten Linien.
        // Seit Block C5 fuer JEDEN Punkt ohne Linie, nicht nur fuer die Ziele:
        // Auch offene Stuetzpunkte hingen oft in der Luft (C4: 15 Mal kein Weg
        // bei 4-6 offenen Stuetzpunkten -> Luftlinie).
        List<List<Integer>> mitNachbarn = netz;
        for (int z = 0; z < knoten.size(); z++) {
            if (!netz.get(z).isEmpty()) continue;
            int naechster = -1;
            double naechsteEntfernung = Double.MAX_VALUE;
            for (int k = 0; k < knoten.size(); k++) {
                if (k == z || netz.get(k).isEmpty()) continue;
                double d = pixelDistance(knoten.get(z), knoten.get(k));
                if (d < naechsteEntfernung) {
                    naechsteEntfernung = d;
                    naechster = k;
                }
            }
            if (naechster < 0 || naechsteEntfernung > ISOLIERT_MAX_PX) continue;
            if (mitNachbarn == netz) {
                mitNachbarn = new ArrayList<>();
                for (List<Integer> n : netz) mitNachbarn.add(new ArrayList<>(n));
            }
            mitNachbarn.get(z).add(naechster);
            mitNachbarn.get(naechster).add(z);
        }

        int[] dist = schritteBisZiel(mitNachbarn, zielIdx);

        Matcher.Hit beste = null;
        int besteSchritte = Integer.MAX_VALUE;
        double besteNaehe = Double.MAX_VALUE;
        for (Matcher.Hit kandidat : offene) {
            int i = knotenIndex(knoten, kandidat);
            if (i < 0 || dist[i] < 0) continue;

            double naehe = Double.MAX_VALUE;
            for (int z : zielIdx) {
                naehe = Math.min(naehe, pixelDistance(kandidat, knoten.get(z)));
            }
            if (dist[i] < besteSchritte
                    || (dist[i] == besteSchritte && naehe < besteNaehe)) {
                besteSchritte = dist[i];
                besteNaehe = naehe;
                beste = kandidat;
            }
        }
        if (beste == null) {
            Log.debug("Kathedrale", "Wegsuche: von keinem der " + offene.size()
                    + " offenen Stuetzpunkte fuehrt eine Linie zu einem Ziel.");
        }
        return beste;
    }

    /** Wie weit ein Ziel ohne Verbindung von seinem Ersatz-Nachbarn weg sein darf. */
    private static final double ISOLIERT_MAX_PX = 230;


    private static double pixelDistance(Matcher.Hit a, Matcher.Hit b) {
        double dx = a.centerX() - b.centerX();
        double dy = a.centerY() - b.centerY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Steht an dieser Stelle eine Einheit, die nachweislich noch lebt? */
    private boolean knownAlive(Matcher.Hit bar) {
        for (int[] p : aliveUnits) {
            if (Math.abs(p[0] - bar.centerX()) < 30 && Math.abs(p[1] - bar.centerY()) < 30) {
                return true;
            }
        }
        return false;
    }

    /** Wurde diese Stelle gerade erst angeklickt? Dann nicht schon wieder. */
    private boolean recentlyVisited(Matcher.Hit hit) {
        for (long[] entry : visited) {
            if (Math.abs(entry[0] - hit.centerX()) < 30
                    && Math.abs(entry[1] - hit.centerY()) < 30) {
                return true;
            }
        }
        return false;
    }

    private void forgetOldVisits() {
        long cutoff = System.currentTimeMillis() - WAYPOINT_COOLDOWN_MS;
        visited.removeIf(entry -> entry[2] < cutoff);
    }

    // ==================================================================
    //  Hilfsmittel
    // ==================================================================

    private boolean click(Mat screen, BotContext ctx, String template, String reason) {
        if (!ctx.matcher().has(template)) return false;
        if (onCooldown(template)) return false;
        if (!ctx.clickIfVisible(screen, template, reason)) return false;

        cooldowns.put(template, System.currentTimeMillis() + CLICK_COOLDOWN_MS);
        unknownScreens = 0;
        return true;
    }

    /**
     * Alle geladenen Aussehen eines Knopfes. Wie im Verlies gilt: jede Vorlage,
     * deren Name mit dem Grundnamen beginnt, zaehlt als derselbe Knopf
     * (dc_resurrect, dc_resurrect_alt, dc_resurrect_2 ...). Neue Aussehen
     * brauchen also keine Codeaenderung, nur eine neue Aufnahme.
     */
    private List<String> variantsOf(BotContext ctx, String base) {
        List<String> names = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (name.equals(base) || name.startsWith(base + "_")) names.add(name);
        }
        return names;
    }

    /** Klickt das erste sichtbare Aussehen eines Knopfes. */
    private boolean clickAny(Mat screen, BotContext ctx, String base, String reason) {
        for (String name : variantsOf(ctx, base)) {
            if (click(screen, ctx, name, reason + " (" + name + ")")) return true;
        }
        return false;
    }

    private boolean visibleAny(Mat screen, BotContext ctx, String... names) {
        for (String name : names) {
            if (ctx.matcher().has(name) && ctx.visible(screen, name)) return true;
        }
        return false;
    }

    private boolean onCooldown(String key) {
        Long until = cooldowns.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Wie knapp die wichtigsten Vorlagen daneben lagen -- fuer die Fehlersuche. */
    private void reportNearMisses(Mat screen, BotContext ctx) {
        String[] wichtig = {
                T_FLOOR_HINT, T_END_JOURNEY, T_END_JOURNEY_GREEN, T_DC_END_JOURNEY,
                T_PARTICIPATE, T_NODE,
                T_DEPLOY_SCREEN, T_PREV_SQUAD, T_HOME, T_GUARDIAN
        };
        StringBuilder text = new StringBuilder(Sprache.t("Best matches: ", "Beste Uebereinstimmungen: "));
        for (String name : wichtig) {
            if (!ctx.matcher().has(name)) continue;
            double best = ctx.matcher().find(screen, name, 0.0)
                    .map(Matcher.Hit::score).orElse(0.0);
            text.append(name).append(' ')
                .append(String.format(java.util.Locale.GERMANY, "%.3f", best)).append("  ");
        }
        warnung(text.toString().trim());
    }
}
