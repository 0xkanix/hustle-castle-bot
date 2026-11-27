package de.hustlebot.bot;

import de.hustlebot.core.Sprache;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.util.*;

/**
 * Spielt das Verlies vollstaendig durch.
 *
 * Der Ablauf ist bewusst nicht als starre Schrittfolge gebaut, sondern als
 * Liste von Regeln, die bei jedem Bild von oben nach unten geprueft werden.
 * Die erste passende Regel handelt. Dadurch faengt der Bot auch Bildschirme ab,
 * die unerwartet auftauchen, etwa eine Truhe oder ein Verbindungsabbruch.
 */
public final class DungeonMode implements GameMode {

    /**
     * Autor 18.10.: "erkennt das Moving here erst nach ein paar Sekunden" -- der Schriftzug ist oft halb vom
     * Fortschrittsbalken verdeckt. 0,72 statt 0,80, damit der Bot sofort folgt.
     */
    private static final double MOVING_HERE_AB = 0.72;

    /** Autor 18.10.: Stillstands-Bericht im Verlies erst nach einer Minute. */
    @Override
    public long haengerBerichtNachS() {
        return 60;
    }

    /**
     * Autor 18.10.: "Waehrend man auf einen Mitspieler wartet, soll keine Fehlermeldung kommen" -- Suche nach einem
     * Mitspieler, Warten auf sein "Ready" oder auf die Wahl des Leiters.
     */
    @Override
    public boolean wartetNurAufAndere() {
        return waitingForPartnerSince > 0 || leaderWaitSince > 0 || readyPressedAt > 0 || sucheAktiv;
    }

    /** Sucht der Bot gerade einen Mitspieler (Suchbildschirm oder Eingang)? */
    private volatile boolean sucheAktiv;

    // ---- Vorlagen, die immer auftauchen koennen -------------------------
    private static final String T_UPDATE          = "update_button";
    private static final String T_HOME            = "home_button";
    private static final String T_GO_TO_MAP       = "go_to_map";
    private static final String T_CLOSE_X         = "close_x";

    /**
     * Das rote runde X der Werbefenster ("Running out of apples?").
     *
     * Nicht zu verwechseln mit close_x -- das ist das graue X der
     * Spielfenster und sieht voellig anders aus.
     */
    private static final String T_AD_CLOSE        = "ad_close";
    private static final String T_MAP_BUTTON      = "map_button";

    // ---- Einstieg ins Verlies -------------------------------------------
    private static final String T_DUNGEON_NODE    = "dungeon_node";
    private static final String T_TAB_TORCHES     = "tab_torches";

    /**
     * Womit bezahlt wird (Teil A, 28.09.): Fackeln, Nahrung, erst Fackeln dann
     * Nahrung, oder zusaetzlich Nahrungskisten -- siehe core.Bezahlung.
     */
    private final Bezahlkasse kasse = new Bezahlkasse("dungeon", "du_for_food", "du_for_food_off",
            T_TAB_TORCHES, "du_apfel_knopf", T_DUNGEON_NODE, "dg_close_x");
    private static final String T_PARTICIPATE     = "participate";
    /** Fackel-Symbol oben im Teilnahme-Fenster, rechts daneben die Zahl (Teil A). */
    private static final String T_FACKEL_ICON     = "du_fackel_icon";
    /**
     * Kennzeichen des KATHEDRALEN-Teilnahmebildschirms -- hier also der
     * falsche. Wird mitten in der Kathedrale auf das Verlies umgeschaltet,
     * steht der Bot noch vor deren Fenster und kaeme ohne diesen Ausweg nicht
     * weiter.
     */
    private static final String T_DC_PARTICIPATE  = "dc_participate";
    private static final String T_DEPLOY_BEST     = "deploy_best";
    /** "Previous Squad" -- nur gebraucht, wenn Autor es fuers Verlies so eingestellt hat (!settings). */
    private static final String T_PREVIOUS_SQUAD  = "dc_previous_squad";
    /**
     * Zeile "Drag and drop dwellers to form your squad" auf dem Aufstell-
     * bildschirm. Freiwillig, aber empfohlen: solange sie sichtbar ist und noch
     * nicht aufgestellt wurde, drueckt der Bot NICHT "Ready".
     */
    private static final String T_DEPLOY_SCREEN   = "deploy_screen";
    private static final String T_CONFIRM         = "confirm";
    private static final String T_RANDOM          = "teammate_random";
    /** Roter Knopf "Cancel" auf dem Bildschirm "Waiting for random player". */
    private static final String T_CANCEL_SEARCH   = "cancel_search";
    /** Knopf "Friend" in der Mitspielerauswahl. */
    private static final String T_FRIEND          = "teammate_friend";
    /** Gruener Knopf "Invite" in der Freundesliste. */
    private static final String T_FRIEND_INVITE   = "friend_invite";
    /** "Invited" mit Haken in der Zeile des Freundes (Autors Bild vom 22.10.) -- erst dann ist die Einladung raus. */
    private static final String T_FRIEND_INVITED  = "invited_haken";   // nicht friend_...: das sind Freundesbilder

    /**
     * Autor 22.10.: "Der Bot hat ein bisschen zu niedrig geklickt und den Invite-Knopf nicht getroffen. Er soll nur auf
     * das rote X druecken, wenn dort auch die Bestaetigung steht: Invited mit dem Haken." Reihenfolge laut Autors vier
     * Bildern: Invite -> "Write a message" -> Send -> "Invited" mit Haken in der Zeile -> rotes X -> "Waiting for a friend".
     * Hier steht, wessen Bestaetigung noch aussteht.
     */
    private String einladungOffen;
    private long einladungGeklicktUm, einladungListeSeit;
    private int einladungVersuche;
    /** Nach "Invite": das Fenster "Write a message", gruener Knopf "Send". */
    private static final String T_INVITE_SEND     = "invite_send";

    // ---- Mit demselben Freund weiterspielen (Abschlussfenster) ----------
    //
    // Autors Bilder vom 11.09.: Nach einem geschafften Verlies steht unter
    // "Embark on the next journey together?" der gruene Knopf "Invite
    // partner". Danach "A request was sent to your partner. Wait for a
    // reply", und sobald der Freund zusagt "Both players ready for new
    // journey" mit "Start journey". Hat umgekehrt der Freund zuerst
    // eingeladen, steht dort "Your partner is waiting for you" -- mit
    // demselben Knopf "Start journey". Danach kommt der Teilnahmebildschirm,
    // nur steht dort statt "Participate" jetzt "Decline" und "Accept"
    // (1 Fackel), und zuletzt "Waiting for previous player".
    //
    // Alle freiwillig. Genutzt wird der Weg nur, wenn die drei Knoepfe
    // Invite partner, Start journey und Accept ALLE im Paket sind -- sonst
    // laedt der Bot wie bisher ueber die Freundesliste neu ein.

    /** Gruener Knopf "Invite partner" im Abschlussfenster. */
    private static final String T_INVITE_PARTNER  = "invite_partner";
    /** Der Hinweis "A request was sent to your partner. Wait for a reply". */
    private static final String T_INVITE_SENT     = "invite_sent";
    /** Gruener Knopf "Start journey" -- beide bereit, oder der Freund wartet schon. */
    private static final String T_START_JOURNEY   = "start_journey";
    /**
     * Gruener Knopf "Accept" (1 Fackel) auf dem Teilnahmebildschirm mit Einladung.
     * Nicht privat: FreundesChat erkennt daran, dass eine Einladung aus dem
     * Chat wirklich angenommen wurde.
     */
    static final String T_INVITE_ACCEPT           = "invite_accept";
    /** Roter Knopf "Decline" daneben. */
    private static final String T_INVITE_DECLINE  = "invite_decline";

    /**
     * So lange wartet der Bot nach "Invite partner" auf die Zusage. Danach
     * schliesst er das Fenster und schaut in der Freundesliste nach: kein
     * gruener Punkt, dann Zufallsspieler (siehe folgeReise()).
     *
     * Autor, 12.09.: "wenn ich dem freund nach einem verliess eine anfrage
     * schicke und ... er nimmt die anfrage nicht an dann soll der bot nach 3
     * minuten einfach aus diesem screen raus gehen und wieder normal mit
     * zufaelligen leuten weiterspielen."
     */
    private static final long FOLGEREISE_WARTEN_MS = 180_000;

    // ---- Wenn bei beiden ein Bot laeuft ---------------------------------
    //
    // Autor tippt "!start dungeon 10 reaper", Robin "!start dungeon 10
    // autor". Keiner der beiden Bots weiss, ob der andere schon eingeladen
    // hat. Laden beide im selben Augenblick ein, wartet jeder auf den
    // anderen. Dagegen hilft der Zufall: Vor jedem Einladen wartet ein Bot
    // eine zufaellige Weile und schaut dann erst noch einmal nach. Die zwei
    // wuerfeln verschieden -- einer ist frueher dran, der andere nimmt an.

    /** Der gruene Punkt neben einem Freund in der Liste: er ist gerade im Spiel. */
    private static final String T_ONLINE = "online_dot";

    /** Zufaellige Pause auf dem Teilnahmebildschirm, bevor der Bot selbst einlaedt. */
    private static final long VOR_EINLADEN_MIN_MS = 8_000;
    private static final long VOR_EINLADEN_MAX_MS = 40_000;

    /** Zufaellige Pause im Abschlussfenster, bevor "Invite partner" gedrueckt wird. */
    private static final long FOLGE_VOR_EINLADEN_MIN_MS = 2_000;
    private static final long FOLGE_VOR_EINLADEN_MAX_MS = 20_000;

    /**
     * So lange wartet der Bot nach einer Einladung aus der Freundesliste --
     * zufaellig zwischen anderthalb und drei Minuten. Haben sich beide
     * gleichzeitig eingeladen, gibt so einer frueher auf, findet dann die
     * Einladung des anderen im Chat und nimmt sie an.
     */
    private static final long FREUND_WARTEN_MIN_MS = 90_000;
    private static final long FREUND_WARTEN_MAX_MS = 180_000;

    /** Beim Warten auf den Freund: so oft schaut der Bot, ob der Freund ihn auch eingeladen hat. */
    private static final long KREUZUNG_MIN_MS = 20_000;
    private static final long KREUZUNG_MAX_MS = 40_000;

    // ---- Wegpunkt mit Effektauswahl (Invisible Influence) ---------------
    /** Die Ueberschrift "Effect selection". */
    private static final String T_EFFECT_SCREEN   = "effect_selection";
    /** Gruener Knopf "Select" im kleinen Bestaetigungsfenster. */
    private static final String T_EFFECT_CONFIRM  = "effect_select";
    /**
     * Der Schriftzug "Effect selected" in der Mitte des kleinen Fensters. Er
     * erscheint anstelle des Knopfes "Select", sobald der Effekt steht.
     *
     * Freiwillig, aber die sicherste Auskunft: solange er zu sehen ist, darf
     * kein weiterer Effekt angeklickt werden – dann muss "Ready" kommen.
     */
    private static final String T_EFFECT_DONE     = "effect_selected";

    /**
     * Negative Effekte, die nie gewaehlt werden sollen. Alle Vorlagen, deren
     * Name mit "effect_ban" beginnt (effect_ban_1, effect_ban_2, ... beliebig
     * viele), zaehlen. Alle freiwillig: fehlt eine, wird sie nicht beachtet.
     */
    private static final String T_EFFECT_BAN_PREFIX = "effect_ban";

    // ---- Belohnungsfenster ----------------------------------------------
    /** Gruener Knopf "Close" unter einer erhaltenen Belohnung. */
    private static final String T_CLOSE_REWARD    = "close_reward";

    // ---- Wegpunkt "Altar of Sacrifice" ----------------------------------
    /** Die Ueberschrift "Make a sacrifice". */
    private static final String T_SACRIFICE_SCREEN = "sacrifice_screen";
    /**
     * Die Einheit, die geopfert werden soll.
     *
     * FREUNDE-FASSUNG: Autors Bot kennt hier feste Namen seiner eigenen
     * Kaempfer (magier1, bogi1, ersatz). Die Freunde heissen ihre Kaempfer
     * anders und sollen im Spiel nichts umbenennen muessen. Deshalb gibt es
     * hier nur Plaetze: Kaempfer 1 bis 6 nach Wichtigkeit, dazu den
     * Opfer-Kaempfer. Welcher echte Kaempfer auf welchem Platz steht, legt
     * der Freund beim Aufnehmen mit Capture-Images.exe fest.
     */
    private static final String T_SACRIFICE_UNIT   = "unit_sacrifice";

    /**
     * Wen der Bot opfert -- in dieser Reihenfolge.
     *
     * Normalerweise den Opfer-Kaempfer; dafuer ist er da. Ist der aber selbst
     * gefallen, steht er nur noch ausgegraut in der Ablage und laesst sich
     * nicht mehr ziehen. Dann geht der Bot die Liste weiter nach unten.
     *
     * Ein toter Kaempfer wird dabei von selbst uebersprungen: Die Vorlagen
     * zeigen die Portraets in Farbe, ein gefallener steht ausgegraut da und
     * passt nicht mehr.
     *
     * Die Reihenfolge ist Absicht. Geopfert wird endgueltig, deshalb steht
     * hinten, was am meisten weh tut. Kaempfer 1 kommt nur dran, wenn sonst
     * gar nichts mehr lebt -- und dann waere der Durchlauf ohnehin verloren.
     */
    // "name_sacrifice" steht gleich hinter dem Portraet: Aendert sich der Skin
    // des Opfer-Kaempfers (andere Ruestung usw.), passt das Portraet
    // "unit_sacrifice" nicht mehr -- der NAME darunter aber schon. Der Bot
    // erkennt am Seitenverhaeltnis, dass ein Namensschild getroffen wurde, und
    // packt das Portraet knapp darueber. So klappt die Opferung auch nach einem
    // Skin-Wechsel, ohne ein Bild neu aufnehmen zu muessen.
    private static final java.util.List<String> SACRIFICE_ORDER =
            java.util.List.of("unit_sacrifice", "name_sacrifice", "unit_auto_opfer", "name_auto_opfer", "unit_6", "unit_5",
                              "unit_4", "unit_3", "unit_2", "unit_1");

    /** Bilder der eigentlichen Opfer-Einheit: von Hand aufgenommen oder vom Team-Scan (Ben 30.10.: das Scan-Bild wurde nie gesucht). */
    static boolean istOpferBild(String vorlage) {
        return vorlage.equals("unit_sacrifice") || vorlage.equals("name_sacrifice")
                || vorlage.equals("unit_auto_opfer") || vorlage.equals("name_auto_opfer");
    }

    /** So oft wird die Einheiten-Leiste am Altar weitergewischt: Sie ist laenger als der Bildschirm (Blades Altar-Foto 24.10.). */
    static final int LEISTE_WISCHE_MAX = 3;

    // ---- Innerhalb des Verlieses ----------------------------------------
    private static final String T_GO_HERE         = "go_here";
    /**
     * "Ready" sieht nicht ueberall gleich aus: im Zweikampf ist es ein kleinerer
     * gruener Knopf an anderer Stelle. Jede Vorlage, deren Name mit "ready"
     * beginnt (ready, ready_alt, ready_2 ...), zaehlt als dieser Knopf.
     */
    private static final String T_READY           = "ready";
    /** Bevorzugt, weil die Kampfanimation uebersprungen wird. */
    private static final String T_QUICK_ATTACK    = "quick_attack";
    /** Nur als Rueckfall, falls auf einem Bildschirm kein Quick Attack angeboten wird. */
    private static final String T_TO_BATTLE       = "to_battle";

    /**
     * Der gruene Haken hinter "Ready" auf gemeinsamen Bildschirmen.
     *
     * Daran erkennt der Bot, ob der Mitspieler schon so weit ist. Fehlt die
     * Vorlage, laeuft alles wie vorher -- deshalb steht sie bei den
     * freiwilligen und nicht bei den notwendigen.
     */
    private static final String T_PARTNER_READY   = "partner_ready";
    private static final String T_FINISH_SELECT   = "finish_selection";
    /**
     * Kennzeichen des Zweikampfs, etwa die Zeile "Defeat your partner's squad".
     * Freiwillig, macht die Unterscheidung aber eindeutig.
     */
    private static final String T_DUEL_MARKER     = "duel_marker";
    /**
     * Ein LEERES Aufstellungsfeld im Zweikampf: der runde Platzhalter oben, in
     * den eine Einheit gezogen werden muss. Freiwillig, aber sehr hilfreich:
     * ist die Vorlage da, findet der Bot die Luecken selbst, statt sich auf
     * geschaetzte Bildpunkte zu verlassen.
     */
    private static final String T_DUEL_SLOT       = "duel_slot";
    /**
     * "Resurrect" ist ein Zwei-Schritt-Knopf: erst "Resurrect 1", dann eine
     * leicht anders aussehende Bestaetigung. Jede Vorlage, deren Name mit
     * "resurrect" beginnt (resurrect, resurrect_confirm, resurrect_2 ...), zaehlt.
     */
    private static final String T_RESURRECT       = "resurrect";
    private static final String T_UNIT_DEAD       = "unit_dead";
    /** Grauer Porträt-Hintergrund einer toten Einheit in der Truppenablage. */
    private static final String T_UNIT_DEAD_BG    = "unit_dead_bg";
    /** Die rote (leere) Lebensleiste unter einer toten Einheit. Klickziel liegt darueber. */
    private static final String T_UNIT_DEAD_BAR   = "unit_dead_bar";
    /**
     * Der Name des Opfer-Kaempfers unter seinem Porträt. Ist diese Vorlage
     * vorhanden, wird er nie wiederbelebt (er ist nur zum Opfern da).
     */
    private static final String T_NAME_ERSATZ     = "name_sacrifice";
    /**
     * Die eigenen Kaempfer in Wunschreihenfolge -- vorne zuerst.
     *
     * Gilt fuer beides: wen der Bot im Zweikampf nach oben zieht, und wen er
     * zuerst wiederbelebt. Im Zweikampf gehen Kaempfer 1 und 2 nach oben;
     * ist einer davon gefallen, ruecken die naechsten nach.
     *
     * Alle freiwillig -- fehlt eine Vorlage, wird sie uebersprungen.
     */
    private static final String[] PRIORITY_UNITS = {
            "unit_1", "unit_2", "unit_3", "unit_4", "unit_5", "unit_6"
    };

    /**
     * Namensschilder der eigenen Kaempfer, in derselben Reihenfolge.
     *
     * Warum es beides braucht, Portraet UND Name: Ein Portraet passt nur,
     * solange die Einheit lebt -- eine gefallene steht ausgegraut in der
     * Ablage und sieht voellig anders aus. Der Name darunter bleibt lesbar.
     *
     * Fuer LEBENDE Einheiten (Zweikampf, Opferung) sind also die Portraets
     * richtig, fuer GEFALLENE (Wiederbeleben) die Namen. Fehlen die
     * Namensvorlagen noch, faellt der Bot auf die Portraets zurueck -- dann
     * erkennt er tote Einheiten eben nicht namentlich und behandelt sie alle
     * gleich.
     */
    private static final String[] PRIORITY_NAMES = {
            "name_1", "name_2", "name_3", "name_4", "name_5", "name_6"
    };
    private static final String T_PROGRESS_100    = "progress_100";
    private static final String T_JOURNEY_DONE    = "journey_complete";
    private static final String T_END_JOURNEY     = "end_journey";
    /** Derselbe Knopf, aber gruen eingefaerbt, sobald das Verlies fertig ist. */
    private static final String T_END_JOURNEY_GRN = "end_journey_green";
    private static final String T_END_CONFIRM     = "end_journey_yes";

    /**
     * Die Nachfrage "Are you sure you want to quit?".
     *
     * Kommt nur, wenn der Mitspieler waehrend des Laufs eine
     * Freundschaftsanfrage geschickt hat. Freiwillige Vorlage -- fehlt sie,
     * verhaelt sich der Bot wie bisher.
     */
    private static final String T_QUIT_WARNING    = "quit_warning";

    /** Der gruene "Yes" in genau dieser Nachfrage. */
    private static final String T_QUIT_YES        = "quit_yes";
    private static final String T_NAMEPLATE_ME    = "nameplate_me";
    private static final String T_LOCK            = "lock_icon";
    /**
     * Schriftzug "moving here" ueber dem Stuetzpunkt, den der Leiter angesteuert
     * hat. Nur der Mitspieler (Nicht-Leiter) braucht ihn: er zeigt, welchem
     * Stuetzpunkt die eigene Truppe folgen muss.
     *
     * Der Schriftzug wird haeufig teilweise vom grauen Fortschrittsbalken
     * verdeckt und sieht dann anders aus. Deshalb zaehlt jede Vorlage, deren
     * Name mit "moving_here" beginnt (moving_here_alt, moving_here_2 ...).
     */
    private static final String T_MOVING_HERE     = "moving_here";

    /**
     * Anklickbare Wegpunkte. Alles ohne Schloss ist erlaubt.
     *
     * Die noch verdeckten Stuetzpunkte mit dem Fragezeichen sind hier bewusst
     * NICHT aufgefuehrt: das Fragezeichen-Symbol schlug auf zu vielen Stellen
     * an und hat die Wegpunktwahl durcheinandergebracht.
     */
    private static final String[] WAYPOINTS = {
            "waypoint_skull", "waypoint_swords", "waypoint_mirror",
            "waypoint_boss", "waypoint_effect",
            "waypoint_sacrifice"
    };

    /**
     * Der rote Hinweis "Your ally left the Dungeon". Taucht er auf, ist das
     * Verlies nicht mehr sinnvoll spielbar und der Bot steigt aus.
     *
     * Der Hinweis erscheint in mehreren Groessen und Formen. Jede Vorlage,
     * deren Name mit "ally_left" beginnt (ally_left, ally_left_alt,
     * ally_left_2 ...), zaehlt – so lassen sich weitere ohne Codeaenderung
     * nachtragen.
     */
    private static final String T_ALLY_LEFT = "ally_left";

    /** Nach einem Klick auf diese Vorlage wird sie kurz nicht erneut geklickt. */
    private static final long CLICK_COOLDOWN_MS = 4500;

    /**
     * Wie weit unter dem "moving here"-Schriftzug der Stuetzpunkt sitzt, als
     * Anteil der Fensterhoehe. Wird nur genutzt, wenn direkt darunter kein
     * bekanntes Wegpunkt-Symbol erkannt wird.
     */
    private static final double MOVING_HERE_DROP = 0.05;

    /** Nach dem Folgen wird der Stuetzpunkt eine Weile nicht erneut geklickt. */
    private static final long FOLLOW_COOLDOWN_MS = 9000;

    /** So oft wird die Weltkarte hoechstens zur Seite gezogen, um das Verlies zu finden. */
    private static final int MAX_MAP_SWIPES = 8;

    private final Map<String, Long> cooldowns = new HashMap<>();
    private int participateAttempts = 0;
    private int unknownScreens = 0;
    private boolean endJourneyAllowed = false;

    /** True, sobald die Weltkarte offen ist und das Verlies noch nicht gefunden wurde. */
    private boolean searchingDungeon = false;
    private int mapSwipes = 0;

    /** Wie oft hintereinander die Burg statt der Weltkarte zu sehen war. */
    private int burgStattKarte = 0;
    private static final int MAX_BURG_STATT_KARTE = 8;

    /** Zeitpunkt des letzten Klicks, mit dem der Bot dem Leiter gefolgt ist. */
    private long lastFollowClickAt = 0;
    /** Wo die "moving here"-Markierung stand, der zuletzt gefolgt wurde (Ben 18.10.: Leiter waehlt um). */
    private int[] letzteMarkierung;

    /**
     * Nach so vielen Sekunden ohne jeden Fortschritt verlaesst der Bot das
     * Verlies und faengt einen neuen Durchlauf an.
     *
     * Der Notausgang fuer alles, was sonst nicht abgedeckt ist -- ein
     * unbekanntes Fenster, ein haengender Mitspieler, ein Bildschirm, fuer den
     * eine Vorlage fehlt. Eine Minute ist reichlich: jeder Klick, der etwas
     * bewirkt, setzt die Uhr zurueck. Steht sie eine Minute still, geht
     * wirklich nichts mehr.
     */
    private static final long IDLE_ABORT_SECONDS = 60;

    /** Seit wann als Mitspieler auf die Stuetzpunkt-Wahl des Leiters gewartet wird. 0 = nicht. */
    private long leaderWaitSince = 0;
    /**
     * Frist, nach der bei untaetigem Leiter das Verlies verlassen wird.
     *
     * Eine Minute. Frueher waren es drei -- das ist zu lang: waehlt der Leiter
     * in einer Minute keinen Stuetzpunkt, macht er in aller Regel gar nichts
     * mehr, und die Wartezeit ist verlorene Zeit. Ueber
     * bot.leaderPickTimeoutMinutes in config.properties laesst sie sich
     * anders einstellen.
     */
    private long leaderPickTimeoutMillis = 3 * 60 * 1000L;   // Autor 18.10.: 3 Minuten, nicht 1

    /** Wann zuletzt "Ready" gedrueckt wurde und seitdem nichts weiterging. 0 = nicht. */
    private long readyPressedAt = 0;
    /** Frist, nach der bei untaetigem Mitspieler nach "Ready" ausgestiegen wird. */
    private long partnerReadyTimeoutMillis = 5 * 60 * 1000L;

    /** True, sobald auf dem aktuellen Opferbildschirm eine Einheit gezogen wurde. */
    private boolean sacrificeDone = false;

    /**
     * Seit wann auf einem offenen Aufstellbildschirm kein "Deploy the best"
     * gefunden wird. 0 heisst: gerade kein solcher Fall.
     */
    private long deployMissingSince = 0;
    /**
     * True, sobald auf DIESEM Aufstellbildschirm einmal aufgestellt wurde.
     * Faellt, sobald der Bildschirm verschwindet -- ein Schalter, keine Uhr.
     */
    private boolean deployedThisScreen = false;

    /** Autor 21.11.: fehlen die Kaempferbilder dieses Kontos oder passen sie nicht, nimmt der Bot sie selbst auf. */
    private final Kaempferbilder kaempferbilder = new Kaempferbilder("Verlies");
    /** Wann auf diesem Bildschirm aufgestellt wurde. */
    private long deployGeklicktUm = 0;
    /** Seit wann "Ready" zu sehen ist, ohne dass aufgestellt wurde (0 = nicht). */
    private long readyOhneAufstellenSeit = 0;
    /** Wo ein Knopf im letzten Bild stand: {x, y, Zeit}. */
    private final java.util.Map<String, long[]> letzteStelle = new java.util.HashMap<>();

    /**
     * Steht der Knopf an derselben Stelle wie im Bild davor? Solange ein
     * Fenster noch hereinfaehrt, wandert er -- dann nicht klicken.
     */
    private boolean stehtStill(Mat screen, BotContext ctx, String name) {
        Optional<Matcher.Hit> h = ctx.find(screen, name);
        if (h.isEmpty()) return false;
        long jetzt = System.currentTimeMillis();
        long[] alt = letzteStelle.put(name, new long[]{h.get().centerX(), h.get().centerY(), jetzt});
        return alt != null && jetzt - alt[2] < 1500
                && Math.abs(alt[0] - h.get().centerX()) <= 3 && Math.abs(alt[1] - h.get().centerY()) <= 3;
    }
    /**
     * So lange wird gewartet, bevor trotzdem gekaempft wird.
     *
     * Der Fall: Ein Aufstellbildschirm ist offen, aber kein "Deploy the
     * best" wird erkannt -- weder der normale noch der ausgegraute. Dann ist
     * entweder schon aufgestellt (Knopf ausgegraut) oder eine Vorlage passt
     * nicht. In beiden Faellen hilft laengeres Warten nichts.
     *
     * Frueher standen hier 25 Sekunden. An einem Vormittag kam das 16 Mal
     * vor -- sieben Minuten reines Warten. Schlimmer noch: Manchmal verliert
     * der Mitspieler in der Zeit die Geduld und steigt aus, dann kostet die
     * Pause nicht 25 Sekunden, sondern den ganzen Versuch.
     *
     * Zehn Sekunden reichen. Wenn in zehn Sekunden kein Knopf auftaucht,
     * taucht auch in fuenfundzwanzig keiner auf.
     */
    private static final long DEPLOY_MISSING_GRACE_MS = 10_000;

    /**
     * True, sobald auf dem aktuellen Effektbildschirm ein Effekt steht.
     *
     * Frueher war das eine Zeitsperre von 4,5 Sekunden. Lief die ab, ohne dass
     * "Ready" geklickt werden konnte, waehlte der Bot denselben Effekt erneut –
     * und das immer wieder. Der Schalter faellt erst, wenn der Effektbildschirm
     * wirklich verschwunden ist.
     */
    private boolean effectDone = false;
    /** Versuche, das kleine Effektfenster wegzuklicken, damit "Ready" frei wird. */
    private int effectPopupClicks = 0;

    /**
     * True, solange der Zweikampf-Bildschirm offen ist.
     *
     * Noetig, weil die Zeile "Defeat your partner's squad" verschwindet, sobald
     * beide Einheiten drin sind: der gruene "Ready"-Knopf legt sich darueber.
     * Ohne diesen Merker hielt der Bot den Bildschirm ab da fuer eine normale
     * Aufstellung, fand dort kein "Deploy the best" und blieb stehen.
     */
    private boolean duelActive = false;
    /** Anlaeufe, die Einheiten in die Luecken zu ziehen. Bremst Endlosversuche. */
    private int duelDragAttempts = 0;

    /**
     * True zwischen "Participate" und der Rueckkehr in die Burg. Daran erkennt
     * der Bot, dass ein Verlies vorbei ist, auch wenn das Abschlussfenster
     * einmal nicht erkannt wurde.
     */
    private boolean insideDungeon = false;

    /** Grund fuer einen laufenden Ausstieg aus dem Verlies, oder null. */
    private String abortReason = null;

    /**
     * Seit wann der Bot auf den Mitspieler wartet. 0 heisst: wartet nicht.
     *
     * Gesetzt wird das, sobald der Bot seinen Teil erledigt hat -- also nach
     * "Ready" oder "To battle!". Danach kann er nichts mehr tun ausser warten,
     * und genau deshalb darf die Stillstandsuhr in dieser Zeit nicht laufen.
     *
     * Vorher fehlte das: Der Bot drueckte "To battle!", der Mitspieler war
     * nicht bereit, nichts passierte -- und nach einer Minute hielt die
     * Stillstandsuhr das fuer einen Hänger und warf ihn aus dem Verlies. Er
     * landete in der Inselansicht und stand dort, weil der Ausstieg dort gar
     * nicht zu Ende gebracht werden konnte.
     */
    private long waitingForPartnerSince = 0;

    /**
     * Ob der Bot auf diesem Bildschirm schon bestaetigt hat.
     *
     * Ein Schalter, keine Uhr -- aus demselben Grund wie beim Aufstellen: Eine
     * Zeitsperre laeuft irgendwann ab, und dann drueckt der Bot ein zweites
     * und drittes Mal auf einen Knopf, der laengst gedrueckt ist. Im Protokoll
     * stand dreimal "Auswahl abschliessen" auf demselben Knopf.
     *
     * Der Schalter faellt erst, wenn der Zweikampfbildschirm verschwindet --
     * also wenn es wirklich weitergegangen ist.
     */
    private boolean duelConfirmed = false;

    /**
     * Ob der gerade laufende Durchlauf schon gezaehlt wurde.
     *
     * Es gibt zwei Stellen, an denen ein Durchlauf als fertig gilt: das
     * Abschlussfenster und die Rueckkehr in die Burg. Beide konnten fuer
     * denselben Durchlauf feuern -- etwa wenn das Abschlussfenster nach dem
     * Klick noch einen Moment stehenblieb. Dann stand in der Statistik eine
     * Drei, wo zwei Durchlaeufe gelaufen waren, und der Ether-Schnitt war
     * entsprechend zu niedrig.
     *
     * Faellt zurueck auf false, sobald ein neues Verlies betreten wird.
     */
    private boolean runCounted = false;

    /** Wann der letzte gewuenschte Run bei "End journey" gezaehlt wurde; 0 = nicht. FERTIG erst draussen. */
    private long fertigGezaehltUm = 0;

    /** Wann zuletzt die Mitspielersuche oder der Teilnahmebildschirm zu sehen war (Stillstandsuhr erst danach). */
    private long sucheGesehenUm = 0;

    /** Seit wann "To battle" ohne Quick Attack zu sehen ist (0 = nicht); erst nach 2,5 s wird es gedrueckt. */
    private long nurToBattleSeit = 0;

    /**
     * Ob der laufende Durchlauf abgebrochen wurde.
     *
     * Reicht laenger als abortReason: Der Grund wird geloescht, sobald der
     * Ausstieg geschafft ist -- die Information "das war ein Abbruch" muss
     * aber bis zur Zaehlung ueberleben.
     */
    private boolean runAborted = false;

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller -- wenn das maximale Tempo erreicht ist, die Stufe 1 vor
     * dem maximalen Tempo behalten. Ohne Fehler am besten." Die kurzen festen Pausen (Hinsehen, bis ein Knopf stillsteht,
     * farbig ist oder die Frist um ist) laufen x Faktor (verlies-tempo.txt). Fotozyklen (Warten auf Bildwechsel), das
     * Warten auf Mitspieler und Einladung und der Kampf bleiben, wie sie sind. Als Fehler zaehlt jede Warnung zu den
     * eigenen Klicks und Bildschirmen -- nicht die zu Mitspielern (untaetig, kein Stuetzpunkt, keiner gefunden, Absturz)
     * und Einladungen: die liegen nicht am Tempo und wuerden das Lernen sonst gleich beim ersten Mal anhalten.
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("verlies-tempo.txt", "Verlies");
    private int runFehler;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn("Verlies", text);
    }

    /** Nach jedem geschafften Run (Autor 24.10.): fehlerfrei 0,1 % schneller, sonst eine Stufe zurueck und fest. */
    private void tempoRundeFertig(RunTracker runs) {
        tempo.rundeFertig(runFehler == 0);
        if (runs.completedRuns() % 10 == 0) {
            Log.info("Verlies", Sprache.t("Speed after " + runs.completedRuns() + " runs: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + runs.completedRuns() + " Runs: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    /**
     * Der zuletzt angesteuerte Stuetzpunkt (Name der Vorlage). 22.10. nachts: der Mitspieler verliess das Verlies
     * 0,3 s nach dem Boss-Kampf -- der Bot wertete das als Abbruch, zaehlte den Lauf nicht und bezahlte noch einmal
     * 150 000 Nahrung. Nach dem Boss wartet er jetzt kurz, ob das Spiel "fertig" zeigt (gruener Knopf / 100 %).
     */
    private String letzterStuetzpunkt = null;
    /** Seit wann "ally left" nach dem Boss-Kampf zu sehen ist (0 = nicht). */
    private long allyLeftNachBossSeit = 0;
    /** So lange darf das Spiel nach dem Boss brauchen, bis es "fertig" zeigt. */
    private static final long BOSS_FERTIG_FRIST_MS = 6_000;

    /** Wie oft beim Ausstieg schon das rote X gedrueckt wurde. */
    private int abortCloseClicks = 0;

    /** Anlaeufe beim Wiederbeleben in Folge, um Endlosschleifen zu erkennen. */
    private int resurrectRounds = 0;
    /** Klicks auf "Resurrect"-Knoepfe seit dem Anwaehlen der aktuellen toten Einheit. */
    private int resurrectClicks = 0;
    /** Zeitpunkt des letzten Resurrect-Klicks, um die offene Karte zu erkennen. */
    private long lastResurrectClickAt = 0;
    /** Zuletzt angeklickte tote Einheit, um eine erkannte ersatz-Einheit zu merken. */
    private Matcher.Hit lastDeadClick = null;
    /** Stellen, an denen eine ersatz-Einheit erkannt wurde – wird nicht mehr angeklickt. */
    private final List<int[]> ersatzSkip = new ArrayList<>();

    /**
     * Stellen, an denen eine Einheit NICHT tot war, sondern nur schwer verletzt.
     *
     * Bei einem einzigen Lebenspunkt sieht die Leiste einer leeren zum
     * Verwechseln aehnlich -- der Bot hielt die Einheit fuer tot, oeffnete ihre
     * Karte, fand keinen "Resurrect", schloss sie wieder und fing von vorne an.
     * Endlos. Wer einmal so entlarvt wurde, wird ab dann uebersprungen.
     */
    private final List<int[]> aliveSkip = new ArrayList<>();

    /** Zeitpunkt, seit dem der Wartebildschirm der Mitspielersuche angezeigt wird. */
    private long searchWaitingSince = 0;
    private int searchRestarts = 0;

    /**
     * Mit wem gespielt wird. CLAN (18.11., Event-Quest "Go to a Dungeon with a clan mate"): erst im
     * Clan-Chat fragen, dann im Verlies "Clan" -- siehe VerliesClan.
     */
    public enum Teammate { RANDOM, FRIEND, CLAN }

    // ---- Mit einem Clan-Mitglied (Autor 18.11.) ---------------------------
    /** Das "Thanks" im Verlies-Chat nach der letzten Runde. */
    private final VerliesClan clan = new VerliesClan();
    /** Die Frage im Clan-Chat ist erledigt (gesendet oder nicht moeglich) -- einmal je Auftrag. */
    private boolean clanChatErledigt = false;
    /**
     * Die Entscheidungen (Autor 19.11.): bis zu 10 min warten, dabei in den Clan-Chat schauen ("in 5 min" -> neu einladen,
     * "can't" -> aufgeben), "Thanks" beim vorletzten Stuetzpunkt im Verlies-Chat, solange der Mitspieler da ist -- ist er
     * schon weg, im Clan-Chat (21:25).
     */
    private ClanVerliesPlan clanPlan = new ClanVerliesPlan(VerliesClan.eigeneTexte(1));
    /** Die eigenen Texte dieses Auftrags (fuer einen frischen Plan in der naechsten Runde). */
    private List<String> clanTexte = VerliesClan.eigeneTexte(1);
    /** Wann zuletzt "Clan" gedrueckt wurde, und wie oft in dieser Runde. */
    private long clanEingeladenUm = 0;
    private int clanEinladungen = 0;
    /** Niemand online / niemand hat angenommen: Fenster zu und den Modus mit Hinweis beenden. */
    private boolean clanAufgegeben = false;
    private boolean clanFensterZu = false;
    /** Der Satz fuer endeHinweis(). */
    private String clanHinweis;
    /** So oft wird in einer Runde hoechstens "Clan" gedrueckt (falls die Einladung verpufft). */
    private static final int CLAN_EINLADUNGEN_MAX = 3;

    private ClanVerliesPlan neuerClanPlan() {
        return new ClanVerliesPlan(clanTexte);
    }

    private Teammate teammate = Teammate.RANDOM;
    /** Anzeigename fuer Meldungen -- bei mehreren "Wetter, Robin, Locke". */
    private String friendName = "";
    /** Die gewuenschten Freunde in der Reihenfolge des Befehls. Bei einem: eine Liste mit einem Namen. */
    private final java.util.List<String> freundeListe = new java.util.ArrayList<>();

    // ---- Eine Einladungsrunde in der Freundesliste (auch mit mehreren) ----
    /** Freunde, die in dieser Runde abgehandelt sind -- eingeladen ODER offline/nicht da. */
    private final java.util.Set<String> gesehenRunde = new java.util.HashSet<>();
    /** Freunde, denen in dieser Runde wirklich eine Einladung geschickt wurde (nur Online). */
    private final java.util.Set<String> eingeladeneRunde = new java.util.HashSet<>();
    /** Nach "Invite" in der Liste steht "Write a message" offen -- "Send" fehlt noch. */
    private boolean sendeAusstehend = false;

    private int friendScrolls = 0;

    /**
     * So oft wird die Freundesliste hoechstens weitergeblaettert.
     *
     * Hier stand 10 -- das reichte fuer etwa 45 Freunde. Autors Liste ist
     * laenger. Jetzt darf es weiter gehen, dafuer erkennt der Bot das Ende
     * der Liste selbst (siehe listeZuEnde) und blaettert nicht ins Leere.
     */
    private static final int MAX_FRIEND_SCROLLS = 40;

    /** Kleines Graubild der Freundesliste vom letzten Blaettern -- fuer listeZuEnde(). */
    private Mat letzteListe;

    /** Wann "Invite partner" gedrueckt wurde. 0 = noch nicht. */
    private long einladungGesendetUm = 0;
    /** Der Freund hat nicht geantwortet: fuer diese Reise nicht noch einmal. */
    private boolean folgeReiseAufgegeben = false;
    /** Autor 22.10.: der Freund hat 3 Minuten nicht angenommen -- in die Burg, dann mit Fremden weiter. Einmalig. */
    private boolean burgJetzt = false;

    @Override
    public boolean jetztInDieBurg() {
        boolean b = burgJetzt;
        burgJetzt = false;
        return b;
    }

    /** Nicht selbst einladen, sondern auf die Einladung des Freundes warten. */
    private boolean aufEinladungWarten = false;
    /** Seit wann auf die Einladung gewartet wird. 0 = gerade nicht. */
    private long einladungAbwartenSeit = 0;
    /** Wann zuletzt nachgesehen wurde -- im Chat, oder durch neues Oeffnen des Fensters. */
    private long letzterBlickNachEinladung = 0;

    /**
     * Wie oft der Bot beim Warten das Verlies-Fenster neu oeffnet. Nur noch,
     * wenn die Bilder fuer den Chat fehlen -- seit dem 12.09. ist klar, dass
     * das Spiel die Einladung nur im Chat zeigt.
     */
    private static final long EINLADUNG_NEU_SCHAUEN_MS = 90_000;
    /** Wie oft der Bot beim Warten in den Chat schaut. */
    private static final long EINLADUNG_CHAT_MS = 45_000;

    /** Seit wann der Freund als weg gilt -- offline, oder er hat nicht angenommen. 0 = er ist da. */
    private long freundWegSeit = 0;

    /**
     * Wo der Bot auf dem Teilnahmebildschirm steht, bevor er "Participate"
     * drueckt: 0 = noch nicht in den Chat geschaut, 1 = die zufaellige Pause
     * laeuft, 2 = fertig. Siehe vorDemTeilnehmen().
     */
    private int vorTeilnahme = 0;
    private long vorTeilnahmeBis = 0;

    /** Wann aus der Freundesliste eingeladen wurde. 0 = gerade nicht. */
    private long freundEingeladenUm = 0;
    /** Ob zu dieser Einladung der Wartebildschirm ("Cancel") kam -- nur fuer die Meldung. */
    private boolean freundWartebildGesehen = false;
    /**
     * So lange darf es nach "Invite" in der Freundesliste dauern, bis der
     * Wartebildschirm steht: "Send", Liste schliessen, das Spiel wechselt --
     * gut sechs Sekunden, hier mit reichlich Luft. Steht der Bot danach noch
     * immer (oder wieder) in der Mitspielerwahl, ist die Einladung verpufft.
     * Siehe offeneEinladung().
     */
    private static final long EINLADUNG_UNTERWEGS_MS = 15_000;
    /** Bis wann auf die Zusage gewartet wird -- zufaellig, siehe FREUND_WARTEN_MIN_MS. */
    private long freundWartenBis = 0;
    /** Wann wieder im Chat nachgesehen wird, ob sich die Einladungen gekreuzt haben. */
    private long naechsterKreuzungsBlick = 0;
    /** Nach einer zurueckgezogenen Einladung: vorher nicht wieder "Friend" druecken. */
    private long freundWaehlenAb = 0;

    /** Ab wann im Abschlussfenster "Invite partner" gedrueckt werden darf. 0 = noch nicht ausgewuerfelt. */
    private long folgeEinladenAb = 0;
    /** Wie lange hoechstens auf eine Einladung gewartet wird. */
    private static final long EINLADUNG_HOECHSTENS_MS = 30 * 60_000L;

    /**
     * Legt fest, ob mit einem zufaelligen Spieler oder einem Freund gespielt wird.
     * Der Name wird zum Namen einer Bildvorlage, etwa "friend_darkangel".
     */
    public void setTeammate(Teammate teammate, String friendName) {
        setTeammate(teammate, friendName, false);
    }

    /**
     * Wie oben. aufEinladungWarten = true: der Bot laedt NICHT selbst ein,
     * sondern wartet, bis der Freund einlaedt, und nimmt dann an.
     *
     * Gebraucht, wenn auch beim Freund ein Bot laeuft. Laden beide ein,
     * kreuzen sich die Einladungen -- einer muss warten.
     */
    public void setTeammate(Teammate teammate, String friendName, boolean aufEinladungWarten) {
        this.teammate = teammate == null ? Teammate.RANDOM : teammate;

        // Mehrere Freunde durch Komma getrennt: "wetter,reaper,locke" oder mit
        // Leerzeichen "Wetter, Robin, LOCKE". Gross/klein ist egal -- der Name
        // wird ueber freundVorlage() ohnehin klein und ohne Sonderzeichen zum
        // Bildnamen (Autor, 16.09.).
        freundeListe.clear();
        if (friendName != null) {
            for (String teil : friendName.split(",")) {
                String n = teil.trim();
                if (!n.isEmpty()) freundeListe.add(n);
            }
        }
        this.friendName = String.join(", ", freundeListe);
        // Auf die Einladung warten gibt es nur mit GENAU einem Freund -- bei
        // mehreren laedt der Bot selbst der Reihe nach ein.
        this.aufEinladungWarten = this.teammate == Teammate.FRIEND
                && aufEinladungWarten && freundeListe.size() == 1;
    }

    /** Die Bildvorlagen aller gewuenschten Freunde -- friend_wetter, friend_reaper ... */
    private java.util.List<String> freundeVorlagen() {
        java.util.List<String> v = new java.util.ArrayList<>();
        for (String n : freundeListe) v.add(freundVorlage(n));
        return v;
    }

    /**
     * Frist, nach der der Bot als Mitspieler das Verlies verlaesst, wenn der
     * Leiter keinen Stuetzpunkt waehlt. Danach beginnt die Mitspielersuche neu.
     */
    public void setLeaderPickTimeoutMinutes(int minutes) {
        this.leaderPickTimeoutMillis = Math.max(1, minutes) * 60_000L;
    }

    /**
     * Frist, nach der der Bot als Leiter aussteigt, wenn der Mitspieler nach
     * dem eigenen "Ready" nicht reagiert.
     */
    public void setPartnerReadyTimeoutMinutes(int minutes) {
        this.partnerReadyTimeoutMillis = Math.max(1, minutes) * 60_000L;
    }

    /**
     * Aus einem Freundesnamen den Namen seiner Bildvorlage machen:
     * "Sir Camelot" wird zu "friend_sircamelot".
     *
     * Oeffentlich, weil drei Stellen dieselbe Regel brauchen: der Bot hier,
     * Capture-Images.exe (es speichert unter genau diesem Namen) und Discord
     * (es prueft vor dem Start, ob das Bild schon da ist).
     */
    public static String freundVorlage(String name) {
        String sauber = name == null ? "" : name.toLowerCase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]", "");
        return "friend_" + sauber;
    }

    /** Mit welchem Freund gerade gespielt wird -- leer bei zufaelligem Mitspieler. */
    public String freund() {
        return teammate == Teammate.FRIEND ? friendName : "";
    }

    @Override
    public String id() {
        return "dungeon";
    }

    @Override
    public String displayName() {
        return Sprache.t("Dungeon", "Verlies");
    }

    @Override
    public List<String> requiredTemplates() {
        List<String> list = new ArrayList<>(List.of(
                T_UPDATE, T_HOME, T_GO_TO_MAP, T_CLOSE_X, T_MAP_BUTTON,
                T_DUNGEON_NODE, T_TAB_TORCHES, T_PARTICIPATE, T_DEPLOY_BEST,
                T_CONFIRM, T_RANDOM, T_CANCEL_SEARCH, T_GO_HERE, T_READY, T_QUICK_ATTACK,
                T_FINISH_SELECT, T_RESURRECT, T_PROGRESS_100, T_JOURNEY_DONE,
                T_END_JOURNEY, T_END_JOURNEY_GRN, T_END_CONFIRM, T_NAMEPLATE_ME, T_LOCK,
                T_MOVING_HERE,
                T_EFFECT_SCREEN, T_EFFECT_CONFIRM,
                T_SACRIFICE_SCREEN, T_SACRIFICE_UNIT
        ));
        list.addAll(Arrays.asList(WAYPOINTS));

        // Nur noetig, wenn wirklich mit einem Freund gespielt werden soll.
        // Wer auf die Einladung wartet, sucht niemanden in der Liste -- er
        // braucht nur den Knopf "Accept", um anzunehmen.
        if (teammate == Teammate.FRIEND && aufEinladungWarten) {
            list.add(T_INVITE_ACCEPT);
        } else if (teammate == Teammate.FRIEND) {
            list.add(T_FRIEND);
            list.add(T_FRIEND_INVITE);
            list.add(T_INVITE_SEND);
            // Jeder gewuenschte Freund braucht sein Namensbild.
            list.addAll(freundeVorlagen());
        }
        return list;
    }

    /** Nicht zwingend, wird aber genutzt, wenn vorhanden. */
    public static List<String> optionalTemplates() {
        List<String> list = new ArrayList<>(List.of(
                T_AD_CLOSE,
                T_TO_BATTLE, T_PARTNER_READY, T_CLOSE_REWARD, T_DUEL_MARKER, "defeat_banner",
                T_UNIT_DEAD,
                T_MAP_BUTTON + "_alt", T_DEPLOY_SCREEN, T_DEPLOY_BEST + "_alt",
                T_DEPLOY_BEST + "_2",
                T_RESURRECT + "_confirm", T_UNIT_DEAD_BG, T_UNIT_DEAD_BAR, T_NAME_ERSATZ,
                T_ALLY_LEFT, T_ALLY_LEFT + "_alt",
                T_GO_HERE + "_alt", T_FINISH_SELECT + "_alt", T_UNIT_CARD,
                T_READY + "_alt", T_MOVING_HERE + "_alt", T_DUEL_SLOT,
                T_READY + "_effect", T_EFFECT_DONE,
                T_INVITE_PARTNER, T_INVITE_SENT, T_START_JOURNEY,
                T_INVITE_ACCEPT, T_INVITE_DECLINE, T_ONLINE, T_PREVIOUS_SQUAD));
        list.addAll(FreundesChat.vorlagen());
        list.addAll(VerliesClan.vorlagen());
        list.addAll(Arrays.asList(EFFECT_CLASSES));
        list.addAll(Arrays.asList(PRIORITY_UNITS));
        list.addAll(Arrays.asList(PRIORITY_NAMES));
        return list;
    }

    @Override
    public void reset() {
        kasse.auftragBeginnt();
        fertigGezaehltUm = 0;
        cooldowns.clear();
        participateAttempts = 0;
        unknownScreens = 0;
        endJourneyAllowed = false;
        searchingDungeon = false;
        mapSwipes = 0;
        burgStattKarte = 0;
        lastFollowClickAt = 0;
        leaderWaitSince = 0;
        letzteMarkierung = null;
        leiterNamensErgebnis = null;
        leiterNameGelesenUm = 0;
        leiterNameGemeldet = false;
        readyPressedAt = 0;
        sacrificeDone = false;
        deployMissingSince = 0;
        deployedThisScreen = false;
        kaempferbilder.neuerStart();   // Autor 21.11.: Kaempferbilder einmal je Modus-Start pruefen
        effectDone = false;
        effectClicks = 0;
        effectPopupClicks = 0;
        duelActive = false;
        duelDragAttempts = 0;
        insideDungeon = false;
        lastResurrectClickAt = 0;
        waitingForPartnerSince = 0;
        duelConfirmed = false;
        runCounted = false;
        blockGeprueft = false;
        runAborted = false;
        letzterStuetzpunkt = null;
        allyLeftNachBossSeit = 0;
        abortReason = null;
        abortCloseClicks = 0;
        resurrectRounds = 0;
        resurrectClicks = 0;
        lastDeadClick = null;
        ersatzSkip.clear();
        searchWaitingSince = 0;
        searchRestarts = 0;
        friendScrolls = 0;
        listeVergessen();
        einladungGesendetUm = 0;
        folgeReiseAufgegeben = false;
        burgJetzt = false;
        einladungAbwartenSeit = 0;
        letzterBlickNachEinladung = 0;
        freundWegSeit = 0;
        vorTeilnahme = 0;
        vorTeilnahmeBis = 0;
        freundEingeladenUm = 0;
        freundWartebildGesehen = false;
        freundWartenBis = 0;
        naechsterKreuzungsBlick = 0;
        freundWaehlenAb = 0;
        folgeEinladenAb = 0;
        neueEinladungsrunde();
        clan.zuruecksetzen();
        clanTexte = VerliesClan.eigeneTexte(1);
        clanPlan = neuerClanPlan();
        clanChatErledigt = false;
        clanEingeladenUm = 0;
        clanEinladungen = 0;
        clanAufgegeben = false;
        clanFensterZu = false;
        clanHinweis = null;
        if (teammate == Teammate.CLAN) {
            Log.info("Verlies", Sprache.t("Mode reset. Partner: clan mate (ask in the clan chat, then \"Clan\")",
                    "Modus zurueckgesetzt. Mitspieler: Clan-Mitglied (erst im Clan-Chat fragen, dann \"Clan\")"));
            return;
        }
        Log.info("Verlies", Sprache.t(
                "Mode reset. Partner: "
                + (teammate != Teammate.FRIEND ? "random"
                   : "friend " + friendName
                     + (aufEinladungWarten ? " (the bot waits for their invite)" : "")),
                "Modus zurueckgesetzt. Mitspieler: "
                + (teammate != Teammate.FRIEND ? "zufaellig"
                   : "Freund " + friendName
                     + (aufEinladungWarten ? " (der Bot wartet auf seine Einladung)" : ""))));
    }

    /**
     * In die Burg zurueck (!burg) erst, wenn der Bot NICHT mitten im Verlies
     * steckt. So wird der laufende Durchlauf noch zu Ende gespielt -- oder bei
     * Stillstand ohnehin abgebrochen, dann ist insideDungeon wieder false.
     */
    @Override
    public boolean darfHeim() {
        return !insideDungeon;
    }

    /**
     * Draussen ist der Bot erst, wenn das Abschlussfenster zu ist oder die
     * Burg erkannt wurde -- beides setzt insideDungeon auf false. Der Klick
     * auf "End journey" allein reicht nicht.
     */
    @Override
    public boolean draussen() {
        return !insideDungeon;
    }

    /** Wann "Abschlussfenster wird geschlossen" zuletzt im Protokoll stand. */
    private long abschlussGemeldetUm;

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {

        // Bezahlen (Teil A, 28.09.): laeuft ein Gang zur Burg (Nahrung ablesen)
        // oder durch die Tasche (Nahrungskisten), gehoert ihm der Schritt.
        Outcome kassenGang = kasse.schritt(screen, ctx);
        if (kassenGang != null) return kassenGang;

        // --- 1. Verbindungsabbruch. Hat immer Vorrang. -----------------------
        if (Stoerfenster.updateDruecken(screen, ctx, "Verlies")) {
            Log.info("Verlies", Sprache.t("Game is reloading, waiting 20 seconds.", "Spiel wird neu geladen, warte 20 Sekunden."));
            ctx.sleep(20000);
            runs.progress(Sprache.t("Reloaded", "Neu geladen"));
            endJourneyAllowed = false;
            return Outcome.ACTED;
        }


        // --- 0. Von einem anderen Geraet angemeldet. Hat Vorrang vor allem. --
        //
        // Steht diese Meldung, ist das Spiel fuer den Bot tot: Er kommt an
        // gar nichts mehr heran. Deshalb ganz vorne.
        switch (Stoerfenster.anderesGeraet(screen, ctx, "Verlies")) {
            case ANHALTEN -> { return Outcome.PAUSE; }
            case GEKLICKT -> {
                runs.progress(Sprache.t("Logged in again after being kicked out", "Nach Rauswurf neu angemeldet"));
                return Outcome.ACTED;
            }
            case NICHTS -> { /* weiter im Ablauf */ }
        }

        // --- 0b. Der Chat ist offen, waehrend der Bot auf die Einladung des
        // Freundes wartet (Autor, 27.09.: "als ich auf den Dungeon-Tab im Chat
        // geklickt hab, hat er trotzdem nicht angenommen"). Der offene Chat
        // galt als unbekannter Bildschirm; nach 12 Bildern drueckte der Bot das
        // rote X. Jetzt: gleich nachsehen und annehmen.
        if (vorTeilnahme == 1 && ctx.visibleIfKnown(screen, FreundesChat.T_SCHLIESSEN)) {
            Log.info("Verlies", Sprache.t("The chat is open -- checking for the invite from " + friendName + " right away.",
                    "Der Chat ist offen -- gleich nach der Einladung von " + friendName + " sehen."));
            vorTeilnahme = 2;
            chatAnnehmen(ctx, runs);
            return Outcome.ACTED;
        }

        // --- 1a. Das taegliche Belohnungsfenster. ----------------------------
        //
        // Kommt nach dem naechtlichen Neustart des Spiels, sobald der Bot das
        // erste Mal wieder in der Burg steht. Es liegt ueber allem, und ohne
        // diesen Klick erkennt der Bot minutenlang nichts mehr.
        if (Stoerfenster.belohnungWegklicken(screen, ctx, "Verlies")) {
            runs.progress(Sprache.t("Reward window closed", "Belohnungsfenster geschlossen"));
            return Outcome.ACTED;
        }

        // --- 1b. Der Teilnahmebildschirm der KATHEDRALE steht im Weg. ---
        //
        // Passiert beim Umschalten mitten im Betrieb: "!start verlies",
        // waehrend noch das Kathedralen-Fenster offen ist. Erst raus, dann
        // kann das Verlies gesucht werden.
        if (onCathedralScreen(screen, ctx)) {
            if (click(screen, ctx, T_CLOSE_X, Sprache.t("Leave the Cathedral window", "Kathedralen-Fenster verlassen"))) {
                Log.info("Verlies", Sprache.t("The Cathedral's participation screen was open. "
                        + "It is being closed, then the bot heads for the Dungeon.",
                        "Der Teilnahmebildschirm der Kathedrale stand offen. "
                        + "Er wird geschlossen, danach geht es zum Verlies."));
                insideDungeon = false;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // --- 1c. "Are you sure you want to quit?" ---------------------------
        //
        // Kommt, wenn der Mitspieler waehrend des Laufs eine
        // Freundschaftsanfrage geschickt hat und man danach das Verlies
        // verlassen will:
        //
        //     "Warning
        //      Are you sure you want to quit? You can only continue playing
        //      with your current partner by adding them to your friend list."
        //
        // MUSS VOR DER ABSCHLUSSFENSTER-REGEL STEHEN. Hinter dem Dialog ist
        // naemlich "You've completed a journey to the Dungeon" weiter zu
        // sehen -- der Bot wuerde sonst immer wieder auf das rote X dahinter
        // zielen, das gar nicht anklickbar ist, und haengenbleiben.
        //
        // Geantwortet wird mit Ja: Der Durchlauf ist an dieser Stelle laengst
        // gezaehlt (beim Klick auf "End journey" bei 100 Prozent), und
        // weiterspielen mit demselben Mitspieler will der Bot nicht -- er
        // sucht sich fuer den naechsten Lauf ohnehin einen neuen.
        if (ctx.matcher().has(T_QUIT_WARNING) && ctx.visible(screen, T_QUIT_WARNING)) {
            if (click(screen, ctx, T_QUIT_YES, Sprache.t("Confirm leaving (warning)", "Verlassen bestaetigen (Warnung)"))
                    || click(screen, ctx, T_END_CONFIRM, Sprache.t("Confirm leaving (warning)", "Verlassen bestaetigen (Warnung)"))) {
                Log.info("Verlies", Sprache.t("Answered the prompt \"Are you sure you want to quit?\" "
                        + "with Yes.",
                        "Nachfrage \"Are you sure you want to quit?\" "
                        + "mit Ja beantwortet."));
                endJourneyAllowed = false;
                runs.progress(Sprache.t("Leaving confirmed (warning)", "Verlassen bestaetigt (Warnung)"));
                ctx.sleepUntilChanged(2500);
                return Outcome.ACTED;
            }
            warnung(Sprache.t("The prompt \"Are you sure you want to quit?\" is there, "
                    + "but the green \"Yes\" button was not recognized. Please capture it with "
                    + "Capture-Images.exe as quit_yes.",
                    "Die Nachfrage \"Are you sure you want to quit?\" ist da, "
                    + "aber der gruene Knopf \"Yes\" wurde nicht erkannt. Bitte ihn mit "
                    + "Capture-Images.exe als quit_yes aufnehmen."));
            return Outcome.WAITING;
        }

        // --- 1d. Mit einem Clan-Mitglied: aufgeben (Fenster zu, Ende mit Hinweis) oder nach der letzten Runde
        // "Thanks" im Verlies-Chat. Vor dem Abschlussfenster -- das Danke kommt, bevor es geschlossen wird.
        if (teammate == Teammate.CLAN) {
            Outcome c = clanVorab(screen, ctx, runs);
            if (c != null) return c;
        }

        // --- 2. Verlies abgeschlossen. Ueber das X hinaus und neu starten. ---
        if (ctx.visible(screen, T_JOURNEY_DONE)) {
            // Mit einem Freund geht es direkt aus diesem Fenster weiter,
            // ohne Umweg ueber Weltkarte und Freundesliste.
            Outcome weiter = folgeReise(screen, ctx, runs);
            if (weiter != null) return weiter;

            // Nur einmal je Fenster ins Protokoll (Test 29.09.: 39 gleiche Zeilen, bis das X kam).
            if (System.currentTimeMillis() - abschlussGemeldetUm > 10_000) {
                abschlussGemeldetUm = System.currentTimeMillis();
                Log.info("Verlies", Sprache.t("Dungeon completed. Closing the completion screen.", "Verlies abgeschlossen. Abschlussfenster wird geschlossen."));
            }
            // Autor 18.10.: Hat der Mitspieler "noch eine Reise zusammen" gewaehlt, kommt beim X eine Warnung mit "Yes" --
            // vorher drueckte der Bot 200 Mal das X und kam nicht heraus. Jetzt "Yes" bestaetigen.
            for (String ja : new String[]{"dc_yes", T_END_CONFIRM, "cw_yes"}) {
                if (ctx.matcher().has(ja) && click(screen, ctx, ja, Sprache.t("Leave anyway (Yes)", "Trotzdem gehen (Yes)"))) {
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
            }
            if (click(screen, ctx, T_CLOSE_X, Sprache.t("Close the completion screen", "Abschlussfenster schliessen"))) {
                insideDungeon = false;   // schon hier gezaehlt, Burg zaehlt nicht nochmal

                // Nach einem Ausstieg zaehlt der Versuch NICHT. Auch dann nicht,
                // wenn dabei ein Abschlussfenster erscheint -- das kommt naemlich
                // auch beim vorzeitigen Verlassen. Frueher wurde hier stur
                // mitgezaehlt, und ein Lauf, den der Bot wegen eines fehlenden
                // Mitspielers abbrechen musste, stand hinterher in der Statistik
                // wie ein geschaffter.
                abortReason = null;
                abortCloseClicks = 0;
                endRun(runs, Sprache.t("completion screen", "Abschlussfenster"));

                cooldowns.clear();
                endJourneyAllowed = false;
                participateAttempts = 0;
                ctx.sleepUntilChanged(2500);
                return runs.isDone() ? Outcome.FINISHED : Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // --- 2b. Sicherheitsabfrage "End journey?" – muss frueh geprueft werden.
        // Hinter dem Fenster ist der Knopf "End journey" weiter sichtbar. Stand
        // diese Pruefung weiter unten, griff vorher die Verlieskarten-Regel und
        // das gruene "Yes" wurde nie gedrueckt.
        if (endJourneyAllowed && click(screen, ctx, T_END_CONFIRM, Sprache.t("Confirm leaving", "Verlassen bestaetigen"))) {
            endJourneyAllowed = false;
            runs.progress(Sprache.t("Leaving confirmed", "Verlassen bestaetigt"));
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }

        // --- 3. Belohnungsfenster wegklicken. --------------------------------
        if (click(screen, ctx, T_CLOSE_REWARD, Sprache.t("Confirm reward", "Belohnung bestaetigen"))) {
            runs.progress(Sprache.t("Reward received", "Belohnung erhalten"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // --- 4. Sieg, Niederlage, Truhe oder Kosmetik: immer "Home". ---------
        //
        // Frueher wurde hier mitgezaehlt, ob der Kampf verloren ging. Das war
        // nie verlaesslich: Sieges- und Niederlagenbanner sehen sich zu
        // aehnlich, und der Bot zaehlte jeden gewonnenen Kampf als Niederlage.
        // Eine Zahl, der man nicht trauen kann, ist schlechter als keine --
        // deshalb ist die Zaehlung entfernt.
        if (ctx.visible(screen, T_HOME)) {
            click(screen, ctx, T_HOME, Sprache.t("Confirm battle result", "Kampfergebnis bestaetigen"));
            readyPressedAt = 0;   // Kampf lief, der Mitspieler hat reagiert
            runs.progress(Sprache.t("Battle finished", "Kampf beendet"));
            // Nach dem Ergebnisfenster geht es direkt auf die Verlieskarte.
            // Der Uebergang ist kurz, laenger warten bringt nichts.
            ctx.sleepUntilChanged(700);
            return Outcome.ACTED;
        }

        // --- 4. Leiterwechsel angekuendigt. ----------------------------------
        if (click(screen, ctx, T_GO_TO_MAP, Sprache.t("To the dungeon map", "Zur Verlieskarte"))) {
            runs.progress(Sprache.t("Leader role settled", "Leiterrolle geklaert"));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // --- 4b. Stillstand: eine Minute lang nichts geschafft. --------------
        //
        // Das ist der Notausgang fuer alles, was sonst nicht abgedeckt ist --
        // ein unbekanntes Fenster, ein haengender Mitspieler, ein Bildschirm,
        // fuer den eine Vorlage fehlt. Statt endlos zu stehen, verlaesst der
        // Bot das Verlies und faengt einen neuen Durchlauf an.
        //
        // Gemessen wird die Zeit seit dem letzten echten Fortschritt. Jeder
        // Klick, der etwas bewirkt, setzt sie zurueck -- ein laufender Kampf
        // oder eine Wiederbelebung loesen das hier also nicht aus.
        //
        // Der Versuch zaehlt nicht mit: das erledigt der Ausstiegsweg, weil
        // abortReason gesetzt ist.
        // ACHTUNG: Waehrend der Mitspielersuche und auf dem Teilnahmebildschirm
        // gilt das NICHT. Dort wartet der Bot mit Absicht, und ein Fortschritt
        // ist gar nicht moeglich -- fuer beides gibt es eigene Fristen.
        //
        // insideDungeon wird naemlich schon beim Klick auf "Participate"
        // gesetzt, also bevor ueberhaupt gesucht wird. Ohne diese Ausnahme hielt
        // der Waechter das Warten auf einen Mitspieler fuer Stillstand und
        // schloss den ganzen Verlies-Bildschirm ueber das X oben rechts.
        boolean searching = ctx.matcher().has(T_CANCEL_SEARCH)
                         && ctx.visible(screen, T_CANCEL_SEARCH);
        boolean onEntryScreen = teilnahmeFenster(screen, ctx);

        // Auch das Warten auf den Mitspieler ist kein Stillstand: Der Bot hat
        // seinen Teil erledigt und darf nichts weiter tun. Dafuer gibt es die
        // eigene, laengere Frist ein paar Zeilen weiter unten.
        // 16.10. (Autor: "immer wenn ein Spieler gefunden wird, geht er kurz raus und sofort wieder rein"):
        // Waehrend der Suche lief die Stillstandsuhr weiter. Im Moment, in dem ein Mitspieler gefunden war und
        // der Suchbildschirm verschwand, stand sie schon bei 135 s -- der Bot drueckte das X (00:02:24, "Exit
        // completed") und ging gleich wieder hinein. Jetzt zaehlt die Uhr erst ab dem Ende der Suche.
        if (searching || onEntryScreen) sucheGesehenUm = System.currentTimeMillis();
        sucheAktiv = searching || onEntryScreen;
        boolean sucheLangeVorbei = System.currentTimeMillis() - sucheGesehenUm >= IDLE_ABORT_SECONDS * 1000L;
        if (insideDungeon && !searching && !onEntryScreen && abortReason == null
                && waitingForPartnerSince == 0 && sucheLangeVorbei
                // Autor 18.10.: "Beim letzten Run nach einer Minute rausgegangen, nicht nach drei" -- waehrend der Bot als
                // Mitspieler auf die Wahl des Leiters wartet, gilt nur dessen eigene Frist (3 Minuten).
                && leaderWaitSince == 0
                && runs.idleSeconds() >= IDLE_ABORT_SECONDS) {
            beginAbort(Sprache.t("No progress for " + runs.idleSeconds() + " seconds",
                    "Seit " + runs.idleSeconds() + " Sekunden kein Fortschritt"));
            // Uhr zuruecksetzen, sonst loest sie waehrend des Ausstiegs sofort
            // wieder aus -- und der Bot kaeme aus dem Abbrechen nicht heraus.
            runs.resetIdleClock();
        }

        // --- 4c. Ausstieg noetig? Zwei Faelle, gleiche Behandlung. -----------
        // a) Der Mitspieler hat das Verlies verlassen ("Your ally left the Dungeon").
        if (abortReason == null) {
            String seen = allyLeftSeen(screen, ctx);
            boolean nachBoss = seen != null && letzterStuetzpunkt != null && letzterStuetzpunkt.startsWith("waypoint_boss");
            if (nachBoss && allyLeftNachBossSeit == 0) {
                allyLeftNachBossSeit = System.currentTimeMillis();
                Log.info("Verlies", Sprache.t("The partner left right after the boss fight -- waiting a moment whether the Dungeon counts as finished.",
                        "Der Mitspieler ging direkt nach dem Boss-Kampf -- kurz warten, ob das Verlies als fertig gilt."));
            }
            if (nachBoss && System.currentTimeMillis() - allyLeftNachBossSeit < BOSS_FERTIG_FRIST_MS) {
                if (isDungeonComplete(screen, ctx)) {
                    // Clan-Kamerad nach dem Boss gegangen (19.11. 21:25): das Thanks kommt in den Clan-Chat
                    if (teammate == Teammate.CLAN) {
                        Outcome c = clanAufKarte(screen, ctx, runs);
                        if (c != null) return c;
                    }
                    return handleDungeonMap(screen, ctx, runs);
                }
                return Outcome.WAITING;
            }
            if (seen != null) {
                beginAbort(Sprache.t("The partner has left the Dungeon (" + seen + ")",
                        "Der Mitspieler hat das Verlies verlassen (" + seen + ")"));
            }
        }
        // b) Als Leiter "Ready" gedrueckt, aber der Mitspieler reagiert nicht.
        if (abortReason == null && readyPressedAt > 0
                && System.currentTimeMillis() - readyPressedAt > partnerReadyTimeoutMillis) {
            beginAbort(Sprache.t("The partner has not reacted to \"Ready\" for "
                    + ((System.currentTimeMillis() - readyPressedAt) / 1000)
                    + " seconds",
                    "Der Mitspieler hat seit "
                    + ((System.currentTimeMillis() - readyPressedAt) / 1000)
                    + " Sekunden nicht auf \"Ready\" reagiert"));
        }
        // c) Der Mitspieler ist einfach nicht bereit geworden.
        if (abortReason == null && waitingForPartnerSince > 0
                && System.currentTimeMillis() - waitingForPartnerSince
                   > partnerReadyTimeoutMillis) {
            beginAbort(Sprache.t("The partner was not ready for " + (partnerReadyTimeoutMillis / 60000)
                    + " minutes",
                    "Der Mitspieler war " + (partnerReadyTimeoutMillis / 60000)
                    + " Minuten lang nicht bereit"));
            waitingForPartnerSince = 0;
        }

        // d) Der Mitspieler steht auf der Sperrliste.
        //
        // Es gibt Mitspieler, die nichts tun und jeden Durchlauf auf eine
        // halbe Stunde ziehen. Wer einmal als solcher aufgenommen wurde, wird
        // beim naechsten Zusammentreffen sofort erkannt -- und der Bot geht
        // raus, statt die Zeit abzuwarten.
        if (abortReason == null && insideDungeon && !blockGeprueft) {
            String gesperrt = gesperrterMitspieler(screen, ctx);
            if (gesperrt != null) {
                blockGeprueft = true;
                String wer = gesperrt.substring(T_BLOCK_PREFIX.length());
                Log.alarm("Verlies", Sprache.t("Partner is on the block list: " + wer
                        + ". The bot leaves right away and looks for a new one.",
                        "Mitspieler steht auf der Sperrliste: " + wer
                        + ". Der Bot geht sofort raus und sucht einen neuen."));
                beginAbort(Sprache.t("Partner on the block list (" + wer + ")",
                        "Mitspieler auf der Sperrliste (" + wer + ")"));
            }
        }

        if (abortReason != null) {
            // Clan-Kamerad hat das Verlies verlassen (19.11. 21:25): vor dem Ausstieg das Thanks in den Clan-Chat (nur ab
            // dem vorletzten Stuetzpunkt, siehe clanAufKarte)
            if (teammate == Teammate.CLAN && isDungeonMap(screen, ctx)) {
                Outcome c = clanAufKarte(screen, ctx, runs);
                if (c != null) return c;
            }
            return handleAbort(screen, ctx, runs);
        }

        // --- 5. Gefallene Einheiten wiederbeleben. ---------------------------
        if (!onCooldown("resurrect_giveup") && resurrectionActive(screen, ctx)) {
            return handleResurrection(screen, ctx, runs);
        }

        // --- 6b. Opferaltar. Ebenfalls vor dem Zweikampf. --------------------
        if (ctx.visible(screen, T_SACRIFICE_SCREEN)) {
            return handleSacrifice(screen, ctx, runs);
        }
        sacrificeDone = false;   // Opferbildschirm ist weg, naechstes Mal neu ziehen

        // --- 6. Effektauswahl. Vor dem Zweikampf, beide zeigen "Finish selection". ---
        if (ctx.visible(screen, T_EFFECT_SCREEN)) {
            return handleEffectSelection(screen, ctx, runs);
        }
        // Effektbildschirm ist weg: beim naechsten Mal wieder neu waehlen.
        effectDone = false;
        effectClicks = 0;
        effectPopupClicks = 0;

        // --- 7. Kampf gegen den Mitspieler: zwei Einheiten hineinziehen. -----
        if (isPartnerDuel(screen, ctx)) {
            return handlePartnerDuel(screen, ctx, runs);
        }
        // Zweikampf ist vorbei: beim naechsten wieder von vorne bestaetigen.
        duelConfirmed = false;

        // --- 8. Truppen aufstellen und bestaetigen. --------------------------
        // "Deploy the best" sieht je nach Bildschirm anders aus (allein beim
        // Verliesstart, gemeinsam im Stuetzpunkt ...). Jede Vorlage, deren Name
        // mit "deploy_best" beginnt, zaehlt als dieser Knopf.
        //
        // Einmal druecken genuegt. Frueher lief diese Schleife auch dann noch,
        // wenn schon aufgestellt war -- und weil mehrere Vorlagen desselben
        // Knopfes an derselben Stelle passen (die normale und die ausgegraute),
        // klickte der Bot abwechselnd auf beide und kam nie zu "Ready". Jede
        // Vorlage hat ihre eigene kurze Sperre, die andere war immer frei.
        // ACHTUNG, hier lag ein boeser Fehler: Ob schon aufgestellt wurde, hing
        // an einer Sperre von 25 Sekunden. Ein Stuetzpunkt ist aber oft in
        // weniger als 25 Sekunden durch -- beim naechsten lief die Sperre noch,
        // der Bot hielt die Truppen faelschlich fuer aufgestellt und ging
        // direkt auf "Ready". Ergebnis: ungefaehr jeder zweite Stuetzpunkt
        // wurde ohne aufgestellte Truppen gekaempft.
        //
        // Jetzt entscheidet ein Schalter, keine Uhr. Er faellt, sobald der
        // Aufstellbildschirm verschwindet -- also genau dann, wenn ein neuer
        // Stuetzpunkt beginnt.
        boolean deployVisible = false;

        // Autor 21.11. (Robin, anderes Konto): "Wenn er noch keine Bilder fuer diesen Account hat bezueglich den
        // Einheiten, soll er sie machen und dann erst den gewuenschten Modus spielen." Nur auf dem Aufstellbildschirm
        // (Zeile "Drag and drop dwellers ...") und bevor aufgestellt ist; einmal je Modus-Start.
        if (!deployedThisScreen && ctx.matcher().has(T_DEPLOY_SCREEN) && ctx.visible(screen, T_DEPLOY_SCREEN)) {
            Outcome bilder = kaempferbilder.pruefen(screen, ctx);
            if (bilder != null) return bilder;
        }

        // Welcher Knopf zuerst: Autors Einstellung (!settings verlies ...).
        // Bei "previous" der eine "Previous Squad", bei "best" jede Vorlage,
        // die mit "deploy_best" anfaengt. Der andere bleibt als Rueckfall.
        boolean previous = Aufstellung.istPrevious("dungeon");
        java.util.List<String> knoepfe = new java.util.ArrayList<>();
        // Live-Test 16.10. (Foto, Wunsch von Ben): im Verlies heisst der Knopf "Previous Squad" (grosses S), die
        // alte Vorlage zeigt "Previous squad" -- sie passte nicht, der Bot nahm "Deploy the best". Darum zaehlt jetzt
        // jede Vorlage ab "dc_previous_squad" (z. B. dc_previous_squad_2 aus templates\gelernt\dungeon).
        java.util.List<String> vorige = new java.util.ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (name.equals(T_PREVIOUS_SQUAD) || name.startsWith(T_PREVIOUS_SQUAD + "_")) vorige.add(name);
        }
        if (vorige.isEmpty()) vorige.add(T_PREVIOUS_SQUAD);
        if (previous) knoepfe.addAll(vorige);
        for (String name : ctx.matcher().names()) {
            if (name.equals(T_DEPLOY_BEST) || name.startsWith(T_DEPLOY_BEST + "_")) {
                knoepfe.add(name);
            }
        }
        if (!previous) knoepfe.addAll(vorige);

        for (String name : knoepfe) {
            if (!ctx.matcher().has(name)) continue;
            if (ctx.visible(screen, name)) deployVisible = true;

            if (deployedThisScreen) continue;   // steht schon, weiter zu "Ready"

            // Der Bildschirm faehrt noch herein: erst klicken, wenn der Knopf
            // stillsteht (sonst geht der Klick daneben).
            if (ctx.visible(screen, name) && !stehtStill(screen, ctx, name)) {
                ctx.sleep(tempo.ms(120));
                return Outcome.WAITING;
            }

            if (click(screen, ctx, name, Sprache.t("Deploy squad (" + name + ")", "Truppen aufstellen (" + name + ")"))) {
                deployedThisScreen = true;
                deployGeklicktUm = System.currentTimeMillis();
                runs.progress(Sprache.t("Squad deployed", "Truppen aufgestellt"));
                // Autor 31.10.: "nur ein bisschen schneller". Gemessen: in 30 von 40 Faellen aendert sich das Bild nach dem Aufstellen gar nicht, der Bot wartete
                // immer die ganzen 1,1 s, dann noch den naechsten Blick (Median 2,05 s bis "Ready"). "Ready" kommt ohnehin fruehestens nach 0,8 s (siehe unten).
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
        }
        if (click(screen, ctx, T_CONFIRM, Sprache.t("Confirm squad selection", "Truppenauswahl bestaetigen"))) {
            runs.progress(Sprache.t("Squad confirmed", "Truppen bestaetigt"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // Schutz gegen "Ready ohne Truppen": Solange ein Aufstellbildschirm offen
        // ist und gerade nicht aufgestellt wurde, wird NICHT "Ready" (und auch
        // nicht "Quick Attack"/"To battle") gedrueckt.
        boolean squadForming = ctx.matcher().has(T_DEPLOY_SCREEN) && ctx.visible(screen, T_DEPLOY_SCREEN);

        // Der Aufstellbildschirm ist weg: der naechste Stuetzpunkt faengt bei
        // null an und wird wieder aufgestellt.
        if (!squadForming && !deployVisible) {
            deployedThisScreen = false;
        }

        if ((deployVisible || squadForming) && !deployedThisScreen) {

            // Der Knopf fehlt im Bild. Zwei Erklaerungen sind moeglich: die
            // Truppen sind noch nicht aufgestellt (dann waere Losschlagen
            // schaedlich), oder sie stehen laengst und der Knopf ist nur
            // ausgegraut -- so sieht er aus, wenn nichts mehr aufzustellen ist.
            //
            // Unterscheiden laesst sich das von aussen nicht. Deshalb: erst
            // eine Weile warten und melden, und wenn sich gar nichts mehr tut,
            // lieber kaempfen als endlos stehenbleiben. Der Bot ist sonst bis
            // zum Ausstieg blockiert, und das kostet den ganzen Durchlauf.
            if (squadForming && !deployVisible) {
                if (deployMissingSince == 0) deployMissingSince = System.currentTimeMillis();
                long waited = System.currentTimeMillis() - deployMissingSince;

                if (waited > DEPLOY_MISSING_GRACE_MS) {
                    warnung(Sprache.t("\"Deploy the best\" has not been recognized for "
                            + (waited / 1000) + " seconds. Most likely the squad is already in place "
                            + "and the button is greyed out. The bot fights now instead of waiting "
                            + "any longer. If this happens often: take a picture on EXACTLY THIS "
                            + "screen and capture the button as deploy_best_3 -- deploy_best_2 "
                            + "already exists and apparently does not match here.",
                            "\"Deploy the best\" wurde seit "
                            + (waited / 1000) + " Sekunden nicht erkannt. Vermutlich stehen die "
                            + "Truppen schon und der Knopf ist ausgegraut. Der Bot kaempft jetzt, "
                            + "statt weiter zu warten. Wenn das oefter vorkommt: Auf GENAU DIESEM "
                            + "Bildschirm ein Bild machen und den Knopf als deploy_best_3 "
                            + "aufnehmen -- deploy_best_2 gibt es schon, der passt hier "
                            + "offenbar nicht."));
                    deployedThisScreen = true;   // aufgeben und kaempfen, der Knopf ist ausgegraut
                    deployMissingSince = 0;
                    // Nicht hier abbrechen: unten geht es mit Ready/Quick Attack weiter.
                } else {
                    if (!onCooldown("deploy_missing_warn")) {
                        warnung(Sprache.t("A deployment screen is open, but the button "
                                + "\"Deploy the best\" was not recognized. The bot keeps waiting "
                                + "and deliberately does NOT press \"Ready\", so you do not fight "
                                + "without units. Please capture the button on this screen again "
                                + "with Capture-Images.exe, e.g. as deploy_best_2.",
                                "Ein Aufstellbildschirm ist offen, aber der Knopf "
                                + "\"Deploy the best\" wurde nicht erkannt. Der Bot wartet noch "
                                + "und drueckt bewusst NICHT \"Ready\", damit du nicht ohne "
                                + "Einheiten kaempfst. Bitte den Knopf auf diesem Bildschirm mit "
                                + "Capture-Images.exe neu aufnehmen, z.B. als deploy_best_2."));
                        cooldowns.put("deploy_missing_warn", System.currentTimeMillis() + 30_000);
                    }
                    return Outcome.WAITING;
                }
            } else {
                Log.debug("Verlies", "\"Deploy the best\" noch offen – warte, bevor \"Ready\" kommt.");
                return Outcome.WAITING;
            }
        } else {
            deployMissingSince = 0;   // Bildschirm ist in Ordnung, Uhr zuruecksetzen
        }

        // NIE "READY" VOR "DEPLOY THE BEST" (Autor, 28.09., 19:24).
        //
        // Der Aufstellbildschirm faehrt beim Oeffnen von links herein. "Ready"
        // sitzt links von "Deploy the best" und war deshalb eher im Bild -- der
        // Bot drueckte mitten in der Bewegung "Ready", 0,4 s spaeter "Deploy",
        // aber nach "Ready" ist "Deploy" gesperrt. Er kaempfte nur mit Tom, der
        // Mitspieler wartete, ein Run dauerte 15 Minuten.
        // Jetzt: "Ready" erst, wenn auf diesem Bildschirm aufgestellt wurde --
        // oder 2,5 s lang gar kein Aufstell-Knopf kam (dann gibt es keinen) --,
        // fruehestens 0,8 s nach dem Aufstellen und nur, wenn "Ready" stillsteht.
        String readyName = null;
        for (String name : variantsOf(ctx, T_READY)) {
            if (ctx.visible(screen, name)) { readyName = name; break; }
        }
        if (readyName == null) {
            readyOhneAufstellenSeit = 0;
        } else {
            long jetzt = System.currentTimeMillis();
            if (!deployedThisScreen) {
                if (readyOhneAufstellenSeit == 0) readyOhneAufstellenSeit = jetzt;
                if (jetzt - readyOhneAufstellenSeit < 2500) {
                    Log.debug("Verlies", "\"Ready\" ist da, aber noch nicht aufgestellt -- warte auf \"Deploy the best\".");
                    ctx.sleep(tempo.ms(150));
                    return Outcome.WAITING;
                }
            } else if (jetzt - deployGeklicktUm < 800) {
                ctx.sleep(tempo.ms(150));
                return Outcome.WAITING;
            }
            if (!stehtStill(screen, ctx, readyName)) {
                ctx.sleep(tempo.ms(120));
                return Outcome.WAITING;
            }
        }

        if (clickAny(screen, ctx, T_READY, Sprache.t("Press Ready", "Bereit melden"))) {
            readyOhneAufstellenSeit = 0;
            // Ab hier laeuft die Frist: reagiert der Mitspieler nicht, steigt
            // der Bot aus. Sie wird bei jedem echten Fortschritt zurueckgesetzt.
            readyPressedAt = System.currentTimeMillis();
            nurToBattleSeit = 0;   // neuer Stuetzpunkt: die 2,5-s-Uhr fuer "To battle" faengt neu an
            Log.debug("Verlies", "Bereit gemeldet. Der Mitspieler hat jetzt "
                    + (partnerReadyTimeoutMillis / 60000) + " Minuten Zeit.");
            runs.progress(Sprache.t("Ready pressed", "Bereit gemeldet"));
            ctx.sleepUntilChanged(1000);
            return Outcome.ACTED;
        }
        // Erst angreifen, wenn der Mitspieler bereit ist.
        //
        // Vorher drueckte der Bot sofort nach dem eigenen "Ready" auf
        // "Quick Attack". War der andere noch nicht so weit, passierte gar
        // nichts -- und der Bot drueckte alle paar Sekunden erneut. Im
        // Protokoll stand dreimal derselbe Klick auf dieselbe Stelle, danach
        // warf ihn die Stillstandsuhr aus dem Verlies.
        if (!warteAufMitspieler(screen, ctx, Sprache.t("deployment", "Aufstellung"))) {
            return Outcome.WAITING;
        }
        // Aus dem Hauptbot (Verlies-Test 15.10.): Nach beiden "Ready" sind "Quick Attack" und "To battle" erst
        // GRAU (das Spiel synchronisiert). Die graue Vorlage passte trotzdem (0,967) -- der Bot drueckte das graue
        // Quick Attack, das nichts bewirkt, und nahm danach das farbige "To battle" (langsamer Kampf statt
        // Schnellkampf; Autor 15.10.: "die letzten 5 Wegpunkte alle mit To battle"). Jetzt: solange grau,
        // nur kurz hinsehen; farbig -> Quick Attack. Gemessen: grau Farbsaettigung 0,00, farbig 0,51-0,76.
        if (kampfKnoepfeGrau(screen, ctx)) {
            if (!onCooldown("kampf_grau_log")) {
                Log.debug("Verlies", "Kampf-Knoepfe noch grau (Spiel synchronisiert) -- warte, bis sie farbig sind.");
                cooldowns.put("kampf_grau_log", System.currentTimeMillis() + 5_000);
            }
            ctx.sleep(tempo.ms(120));
            return Outcome.WAITING;
        }

        if (click(screen, ctx, T_QUICK_ATTACK, Sprache.t("Start battle (Quick Attack)", "Kampf starten (Quick Attack)"))) {
            nurToBattleSeit = 0;
            readyPressedAt = 0;
            waitingForPartnerSince = 0;   // Hauptbot (Code-Review 15.10.): Warteuhr endet mit dem Kampf
            runs.progress(Sprache.t("Battle started", "Kampf gestartet"));
            // War 2500. Waehrend des Kampfes aendert sich das Bild ohnehin
            // staendig, sleepUntilChanged kommt also frueh zurueck -- die alte
            // Obergrenze wurde nur im Leerlauf voll ausgesessen.
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        // 16.10.: Als Mitspieler drueckte der Bot im Moment, in dem beide bereit waren, noch ein "To battle"
        // (974,652, Treffer 0,866), bevor Quick Attack ueberhaupt im Bild war -- 5 von 11 Kaempfen. Die
        // Bildserie von 00:06:24 zeigt warum: Unten steht "Waiting for the leader's confirmation" mit einem
        // gruenen "To battle!" -- Quick Attack kommt erst, wenn der Leiter bestaetigt. Darum "To battle" erst,
        // wenn so lange kein Quick Attack aufgetaucht ist: als Leiter 2,5 s, als Mitspieler 15 s.
        if (ctx.visible(screen, T_TO_BATTLE)) {
            long jetzt = System.currentTimeMillis();
            if (nurToBattleSeit == 0) nurToBattleSeit = jetzt;
            if (jetzt - nurToBattleSeit < (isLeader(screen, ctx) ? 2500 : 15_000)) {
                ctx.sleep(tempo.ms(120));
                return Outcome.WAITING;
            }
        }
        // Nur falls auf diesem Bildschirm kein Quick Attack angeboten wird.
        if (click(screen, ctx, T_TO_BATTLE, Sprache.t("Start battle (no Quick Attack available)", "Kampf starten (kein Quick Attack vorhanden)"))) {
            nurToBattleSeit = 0;
            readyPressedAt = 0;
            waitingForPartnerSince = 0;   // Hauptbot (Code-Review 15.10.): Warteuhr endet mit dem Kampf
            runs.progress(Sprache.t("Battle started", "Kampf gestartet"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // --- 9. Wegpunkt betreten. -------------------------------------------
        // Der Knopf ist je nach Wegpunkt gruen oder orange.
        if (clickAny(screen, ctx, T_GO_HERE, Sprache.t("Enter waypoint", "Wegpunkt betreten"))) {
            readyPressedAt = 0;
            runs.progress(Sprache.t("Waypoint entered", "Wegpunkt betreten"));
            // Beim Betreten wechselt das Bild sofort; die Obergrenze wurde nur
            // im Leerlauf voll ausgesessen. Wie beim Kampfstart auf 1200.
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // --- 10. Mitspielersuche laeuft: Geduld, aber nicht unbegrenzt. ------
        if (ctx.visible(screen, T_CANCEL_SEARCH)) {
            return handleTeammateSearch(screen, ctx, runs);
        }
        searchWaitingSince = 0;

        // --- 10b. Nach "Invite": das Fenster "Write a message". -------------
        //
        // Die Einladung geht erst mit "Send" hinaus (Autors Bild vom 12.09.),
        // einen Text braucht es nicht. Steht VOR der Freundesliste, weil die
        // hinter dem Fenster weiter zu sehen ist.
        //
        // Nur direkt nach "Invite" -- entweder gerade in der Liste einen
        // eingeladen (sendeAusstehend), oder eine Folge-Einladung laeuft
        // (freundEingeladenUm). Ein gruener "Send" anderswo ist nicht gemeint.
        if (teammate == Teammate.FRIEND && (sendeAusstehend || freundEingeladenUm > 0)
                && ctx.matcher().has(T_INVITE_SEND)
                && click(screen, ctx, T_INVITE_SEND, Sprache.t("Send the invite (Send)", "Einladung abschicken (Send)"))) {
            sendeAusstehend = false;
            runs.progress(Sprache.t("Invite sent", "Einladung abgeschickt"));
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }

        // --- 11. Freundesliste: den gewuenschten Freund einladen. ------------
        if (ctx.matcher().has(T_FRIEND_INVITE) && ctx.visible(screen, T_FRIEND_INVITE)) {
            // Die Liste wird geschlossen, statt jemanden einzuladen,
            //  - wenn der Freund schon eingeladen ist: In seiner Zeile steht
            //    dann "Invited" mit Haken, die Liste bleibt aber offen
            //    (Autors Bilder vom 12.09.). Erst wenn sie zu ist, kommt der
            //    Wartebildschirm;
            //  - wenn der Bot auf die Einladung wartet;
            //  - wenn der Freund gerade weg ist.
            boolean schonEingeladen = teammate == Teammate.FRIEND && freundEingeladenUm > 0;
            if (teammate == Teammate.FRIEND && (schonEingeladen || aufEinladungWarten || !freundAktiv())) {
                String grund = schonEingeladen ? Sprache.t(friendName + " is invited", friendName + " ist eingeladen")
                             : aufEinladungWarten ? Sprache.t("bot waits for the invite", "Bot wartet auf Einladung")
                             : Sprache.t(friendName + " is gone", friendName + " ist weg");
                return listeSchliessen(screen, ctx, Sprache.t("Close friend list (" + grund + ")", "Freundesliste schliessen (" + grund + ")"))
                        ? Outcome.ACTED : Outcome.WAITING;
            }
            return handleFriendList(screen, ctx, runs);
        }
        listeVergessen();   // Liste zu: beim naechsten Oeffnen faengt sie oben an

        // --- 11b. Eine Einladung steht an: "Decline" / "Accept". -------------
        //
        // Kommt nach "Start journey" und immer dann, wenn ein Freund den Bot
        // eingeladen hat. Statt "Participate" gibt es dann nur diese zwei
        // Knoepfe -- ohne diesen Schritt stuende der Bot davor still.
        if (ctx.matcher().has(T_INVITE_ACCEPT) && ctx.visible(screen, T_INVITE_ACCEPT)) {
            return handleInvitation(screen, ctx, runs);
        }

        // --- 11c. "Start journey" ausserhalb des Abschlussfensters. ----------
        //
        // Im Abschlussfenster kuemmert sich folgeReise() darum. Taucht der
        // Knopf woanders auf -- etwa nachdem der Freund eine Einladung aus
        // der Liste angenommen hat --, wird er im Freundesmodus ebenfalls
        // gedrueckt, statt davor stehenzubleiben.
        if (teammate == Teammate.FRIEND && freundAktiv()
                && click(screen, ctx, T_START_JOURNEY, Sprache.t("Start journey with " + friendName, "Reise mit " + friendName + " starten"))) {
            freundWiederDa();
            runs.progress(Sprache.t("Journey with " + friendName + " started", "Reise mit " + friendName + " gestartet"));
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }

        // --- 12. Mitspieler waehlen. -----------------------------------------
        // Wer auf die Einladung wartet, waehlt hier niemanden -- er kommt ueber
        // "Accept" hinein. (Hierher gelangt er normalerweise gar nicht, weil
        // er "Participate" nie drueckt.)
        //
        // Ist der Freund weg -- offline, oder er hat nicht angenommen --,
        // spielt der Bot mit einem zufaelligen Mitspieler (Autor, 12.09.).
        if (teammate == Teammate.FRIEND && (aufEinladungWarten || freundAktiv())) {
            if (!aufEinladungWarten && ctx.matcher().has(T_FRIEND) && ctx.visible(screen, T_FRIEND)) {
                // Schon eingeladen und trotzdem (wieder) hier? Siehe offeneEinladung().
                Outcome offen = offeneEinladung(ctx, runs);
                if (offen != null) return offen;

                // Gerade eine Einladung zurueckgezogen? Dann einen Moment warten,
                // sonst laden beide Bots im selben Augenblick wieder ein.
                //
                // Nur HIER warten, wo der Friend-Knopf zu sehen ist. Stand das
                // eine Ebene hoeher, blieb in der Pause JEDER Bildschirm liegen --
                // auch die Verlieskarte, falls der Freund doch noch angenommen
                // hatte, und der Notausgang fuer fremde Fenster.
                if (System.currentTimeMillis() < freundWaehlenAb) return Outcome.WAITING;
                if (click(screen, ctx, T_FRIEND, Sprache.t("Choose a friend as partner", "Freund als Mitspieler waehlen"))) {
                    friendScrolls = 0;
                    neueEinladungsrunde();   // frische Liste: alle Freunde neu abklappern
                    runs.progress(Sprache.t("Friend list opened", "Freundesliste geoeffnet"));
                    ctx.sleepUntilChanged(1800);
                    return Outcome.ACTED;
                }
            }
        } else if (teammate == Teammate.CLAN) {
            // Mitspielerwahl erkannt am Knopf "Random" daneben (oder am Clan-Bild): "Clan", Nachricht, "Send".
            Outcome c = clanEinladen(screen, ctx, runs);
            if (c != null) return c;
        } else {
            if (click(screen, ctx, T_RANDOM, teammate == Teammate.FRIEND
                    ? Sprache.t("Search for a random partner (" + friendName + " is gone)", "Zufaelligen Mitspieler suchen (" + friendName + " ist weg)")
                    : Sprache.t("Search for a random partner", "Zufaelligen Mitspieler suchen"))) {
                runs.progress(Sprache.t("Searching for a partner", "Mitspieler wird gesucht"));
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }
        }

        // Letzter Run gezaehlt und schon draussen (Teilnahmefenster oder Weltkarte), ohne dass Abschlussfenster
        // oder Burg erkannt wurden: fertig -- NICHT wieder hinein. Notausgang nach 60 s.
        if (fertigGezaehltUm > 0 && runs.isDone()
                && (teilnahmeFenster(screen, ctx) || ctx.visible(screen, T_DUNGEON_NODE)
                    || System.currentTimeMillis() - fertigGezaehltUm > 60_000)) {
            Log.info("Verlies", Sprache.t("Out of the Dungeon -- the run is finished.",
                    "Aus dem Verlies heraus -- der Run ist fertig."));
            fertigGezaehltUm = 0;
            return Outcome.FINISHED;
        }

        // --- 13. Teilnahmefenster: Fackeln waehlen und starten. --------------
        if (teilnahmeFenster(screen, ctx)) {
            return handleParticipation(screen, ctx, runs);
        }

        // --- 14. Weltkarte: das Verlies anklicken. ---------------------------
        if (click(screen, ctx, T_DUNGEON_NODE, Sprache.t("Open the Dungeon on the world map", "Verlies auf der Weltkarte oeffnen"))) {
            searchingDungeon = false;
            mapSwipes = 0;
            burgStattKarte = 0;
            runs.progress(Sprache.t("Dungeon opened", "Verlies geoeffnet"));
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }

        // --- 15. Verlies liegt ausserhalb des Ausschnitts: Karte schieben. ---
        //
        // Aber nur, wenn wir WIRKLICH auf der Weltkarte sind. Fehlerbericht
        // 8.1 vom 21.09.: Der Klick auf "Map" wurde von einem Werbefenster
        // geschluckt, der Bot hielt die Burg fuer die Karte und schob acht
        // Mal -- in der Burg zieht so ein Wischen Figuren zwischen Raeumen
        // hin und her ("The character can only be moved to another room").
        // Ist der Map-Knopf noch zu sehen, ist das die Burg: nicht schieben,
        // sondern Map noch einmal druecken (Schritt weiter unten).
        if (searchingDungeon && visibleAny(screen, ctx, T_MAP_BUTTON)) {
            searchingDungeon = false;
            burgStattKarte++;
            if (burgStattKarte >= MAX_BURG_STATT_KARTE) {
                Log.error("Verlies", Sprache.t("The \"Map\" button was pressed " + burgStattKarte
                        + " times, but the castle stays. There is probably a pop-up "
                        + "on top that the bot does not know. Please take a look at "
                        + "what is on the screen.",
                        "Der Knopf \"Map\" wurde " + burgStattKarte
                        + " Mal gedrueckt, aber die Burg bleibt stehen. Da liegt "
                        + "vermutlich eine Einblendung darueber, die der Bot nicht "
                        + "kennt. Bitte einmal nachsehen, was auf dem Bildschirm ist."));
                burgStattKarte = 0;
                return Outcome.FATAL;
            }
            if (!onCooldown("burg_statt_karte")) {
                cooldowns.put("burg_statt_karte", System.currentTimeMillis() + 10_000);
                warnung(Sprache.t("The click on \"Map\" did not take -- the "
                        + "castle is still visible. Pressing it again instead of "
                        + "swiping in the castle (attempt " + burgStattKarte + " of "
                        + MAX_BURG_STATT_KARTE + ").",
                        "Der Klick auf \"Map\" hat nicht gegriffen -- die "
                        + "Burg ist noch zu sehen. Es wird noch einmal gedrueckt, statt "
                        + "in der Burg zu wischen (Versuch " + burgStattKarte + " von "
                        + MAX_BURG_STATT_KARTE + ")."));
            }
            return Outcome.WAITING;
        }
        if (searchingDungeon) {
            return swipeWorldMap(ctx, runs);
        }

        // --- 15b. Werbefenster in der Burg wegklicken. -----------------------
        //
        // "Running out of apples? Watch an ad to get some food" und ihre
        // Geschwister tauchen beim Zurueckkehren in die Burg auf und legen
        // sich ueber alles.
        //
        // ACHTUNG, HIER LAG EIN BOESER FEHLER: Zuerst stand diese Pruefung
        // ganz oben in der Kette, ohne jede Bedingung. Das rote runde X sieht
        // aber bei JEDEM Fenster gleich aus -- auch beim Teilnahmefenster des
        // Verlieses. Der Bot betrat das Verlies und schloss es im selben
        // Atemzug wieder:
        //
        //     Verlies betreten -> Truppen aufstellen -> "Werbefenster"
        //     schliessen -> Fackeln -> Verlies betreten -> ...
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
        if (visibleAny(screen, ctx, T_MAP_BUTTON)
                && Stoerfenster.werbungSicher(screen, ctx)
                && clickAny(screen, ctx, T_AD_CLOSE, Sprache.t("Close the ad window in the castle", "Werbefenster in der Burg schliessen"))) {
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // --- 16. Burg: zur Weltkarte wechseln. -------------------------------
        // Der "Map"-Knopf unten links sieht nicht immer gleich aus.
        if (visibleAny(screen, ctx, T_MAP_BUTTON)) {

            // Wir stehen wieder in der Burg. War der Bot eben noch im Verlies,
            // ist es hiermit beendet - auch wenn das Abschlussfenster einmal
            // nicht erkannt wurde. So bleibt der Zaehler richtig.
            if (insideDungeon) {
                insideDungeon = false;
                cooldowns.clear();
                endJourneyAllowed = false;
                participateAttempts = 0;
                readyPressedAt = 0;
                leaderWaitSince = 0;

                abortReason = null;
                abortCloseClicks = 0;
                // Hauptbot (Code-Review 15.10.): Ist das Spiel im Verlies abgestuerzt, landet der Bot nach dem Neustart in
                // der Burg -- das zaehlte bisher als geschafft.
                if (BotEngine.spielAbsturzUm > betretenUm && betretenUm > 0) {
                    runAborted = true;
                    Log.warn("Verlies", Sprache.t("The game crashed during the Dungeon -- this run does not count.",
                            "Das Spiel ist waehrend des Verlieses abgestuerzt -- der Lauf zaehlt nicht."));
                }
                endRun(runs, Sprache.t("return to the castle", "Rueckkehr in die Burg"));
                if (!runAborted && runs.isDone()) return Outcome.FINISHED;
            }

            // Mit einem Clan-Mitglied: vor dem Weg zur Karte einmal im Clan-Chat fragen (Autor 18.11.).
            if (teammate == Teammate.CLAN && !clanChatErledigt) {
                return clanChatFragen(ctx, runs);
            }

            if (clickAny(screen, ctx, T_MAP_BUTTON, Sprache.t("Open the world map", "Weltkarte oeffnen"))) {
                searchingDungeon = true;
                mapSwipes = 0;
                runs.progress(Sprache.t("World map opened", "Weltkarte geoeffnet"));
                ctx.sleepUntilChanged(2200);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // --- 17. Verlieskarte: Wegpunkt waehlen oder auf den Leiter warten. ---
        if (isDungeonMap(screen, ctx)) {
            // Nach einer Einladung aus der Liste: Der Freund ist mitgekommen.
            if (freundEingeladenUm > 0) {
                freundEingeladenUm = 0;
                freundWiederDa();
            }
            // Mit einem Clan-Kameraden: "Thanks" im Verlies-Chat zur rechten Zeit (Autor 19.11.).
            if (teammate == Teammate.CLAN) {
                Outcome c = clanAufKarte(screen, ctx, runs);
                if (c != null) return c;
            }
            return handleDungeonMap(screen, ctx, runs);
        }

        // --- 18. Sicherheitsabfrage beim Verlassen. --------------------------
        if (endJourneyAllowed && click(screen, ctx, T_END_CONFIRM, Sprache.t("Confirm leaving", "Verlassen bestaetigen"))) {
            ctx.sleepUntilChanged(2500);
            endJourneyAllowed = false;
            return Outcome.ACTED;
        }

        // --- 19. Nichts erkannt. ---------------------------------------------
        //
        // Auf der Weltkarte (nach Leuchtturm/Kolosseum, Autor 26.09.), das
        // Verlies aber nicht im Bild (Schritt 14 haette es sonst angeklickt): suchen.
        // Sofort, nicht erst nach drei Bildern -- und nicht ueber die Burg: die
        // Karte ziehen, bis das Verlies zu sehen ist (Schritt 14 klickt es dann).
        if (!searchingDungeon && Weltkarte.erkannt(screen, ctx)) {
            searchingDungeon = true;
            unknownScreens = 0;
            return swipeWorldMap(ctx, runs);
        }
        unknownScreens++;
        if (Stoerfenster.warnungFaellig(unknownScreens, runs.idleSeconds())) {
            warnung(Sprache.t("Nothing recognized for " + unknownScreens + " frames. "
                    + "Last progress: " + runs.lastNote()
                    + " " + runs.idleSeconds() + " seconds ago.",
                    "Seit " + unknownScreens + " Bildern nichts erkannt. "
                    + "Letzter Fortschritt: " + runs.lastNote()
                    + " vor " + runs.idleSeconds() + " Sekunden."));
            reportNearMisses(screen, ctx);
        }

        // Notausgang: Liegt ein unbekanntes Fenster im Weg und hat es ein
        // rotes X, wird es gedrueckt. Rettet den Lauf auch bei Fenstern,
        // fuer die es noch gar keine Vorlage gibt.
        if (Stoerfenster.notausgang(screen, ctx, "Verlies", unknownScreens)) {
            // 15.11. / X3A2: nur als Fortschritt zaehlen, wenn der Klick das Bild wirklich geaendert hat.
            if (BotContext.notausgangZaehlt(ctx.letzteWartungGeaendert())) {
                runs.progress(Sprache.t("Unknown window closed", "Unbekanntes Fenster geschlossen"));
            }
            return Outcome.ACTED;
        }
        if (unknownScreens > 150) {
            Log.error("Verlies", Sprache.t("The screen is permanently not recognized. "
                    + "Probably pictures are missing or the game is stuck in an "
                    + "unknown window. The bot stops.",
                    "Der Bildschirm wird dauerhaft nicht erkannt. "
                    + "Vermutlich fehlen Vorlagen oder das Spiel haengt in einem "
                    + "unbekannten Fenster. Bot wird gestoppt."));
            return Outcome.FATAL;
        }
        return Outcome.UNKNOWN;
    }

    // ------------------------------------------------------------------
    //  Teilnahmefenster
    // ------------------------------------------------------------------
    /**
     * Steht der Teilnahmebildschirm der KATHEDRALE vor uns?
     *
     * Zwei Bedingungen, damit es sicher ist: Ihr Teilnahme-Knopf muss da sein
     * UND keines der eigenen Kennzeichen. Sonst koennte eine knappe
     * Aehnlichkeit der beiden Knoepfe dazu fuehren, dass der Bot den richtigen
     * Bildschirm verlaesst.
     */
    private int kathedraleBilder = 0;

    /**
     * Das Teilnahme-Fenster des Verlieses: mit dem Fackel-Knopf ODER mit gewaehltem Reiter "For food" (dann zeigt
     * "Participate" Apfel und Preis, der Fackel-Knopf fehlt). 22.10. nachts: ohne die Reiter erkannte der Bot das
     * Fenster mit Nahrung gar nicht und schloss es alle 8 s mit dem X.
     */
    private boolean teilnahmeFenster(Mat screen, BotContext ctx) {
        return ctx.visible(screen, T_PARTICIPATE) || nahrungsReiterVomVerlies(screen, ctx);
    }

    /**
     * Code-Pruefung 22.10. (C1): der Reiter "For food" sieht in Verlies und Kathedrale gleich aus (du_for_food ==
     * dc_for_food, Guete 1,00) -- das Verlies hielt das Kathedralen-Fenster fuer sein eigenes und haette mit Nahrung
     * in die Kathedrale gehen koennen. Nur die Kathedrale hat den Reiter "For keys": ist er da, zaehlt "For food" nicht.
     */
    private boolean nahrungsReiterVomVerlies(Mat screen, BotContext ctx) {
        if (ctx.visibleIfKnown(screen, "dc_for_keys_off")) return false;
        return ctx.visibleIfKnown(screen, "du_for_food") || ctx.visibleIfKnown(screen, "du_for_food_off");
    }

    private boolean onCathedralScreen(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_DC_PARTICIPATE)) return false;
        if (!ctx.visible(screen, T_DC_PARTICIPATE)) return false;

        boolean ownSigns = ctx.visible(screen, T_PARTICIPATE)
                || (ctx.matcher().has(T_TAB_TORCHES) && ctx.visible(screen, T_TAB_TORCHES))
                // Der Teilnahmebildschirm mit Einladung hat kein "Participate",
                // sondern "Accept" -- er ist trotzdem der eigene.
                || (ctx.matcher().has(T_INVITE_ACCEPT) && ctx.visible(screen, T_INVITE_ACCEPT))
                // 23.09., 23:48: Der Mitspieler-Dialog des Verlieses ("Random"
                // / "Friend") wurde fuer die Kathedrale gehalten -- der Bot
                // schloss ihn mit dem X und fing von vorn an, in einer
                // Schleife. Die Knoepfe dieses Dialogs sind eindeutig Verlies.
                || (ctx.matcher().has(T_RANDOM) && ctx.visible(screen, T_RANDOM))
                || (ctx.matcher().has(T_FRIEND) && ctx.visible(screen, T_FRIEND))
                || (ctx.matcher().has(T_CANCEL_SEARCH) && ctx.visible(screen, T_CANCEL_SEARCH))
                // 22.10. nachts (Verlies mit Nahrung): "Participate" mit Apfel und Preis sieht aus wie der
                // Kathedralen-Knopf (0,95) -- der Bot schloss das Verlies-Fenster in einer Schleife. Die Reiter
                // "For food" / "For torches" gibt es nur im Verlies (Mia: "Food only" nahm nie Nahrung).
                || nahrungsReiterVomVerlies(screen, ctx)
                || ctx.visible(screen, T_CONFIRM);
        if (!ownSigns) {
            ctx.find(screen, T_DC_PARTICIPATE).ifPresent(h -> {
                Log.debug("Verlies", "Kathedralen-Teilnahme erkannt bei " + h.centerX() + ","
                        + h.centerY() + " (Guete " + String.format("%.3f", h.score()) + ")");
                // Zum Nachmessen der Schleife vom 23.09. (hoechstens drei Bilder).
                if (kathedraleBilder < 3) {
                    kathedraleBilder++;
                    try {
                        de.hustlebot.core.Beleg.schreiben(
                                "logs/verlies-kathedrale-" + kathedraleBilder + ".png", screen);
                    } catch (Exception ignored) {
                        // nur zum Nachsehen
                    }
                }
            });
        }
        return !ownSigns;
    }

    private Outcome handleParticipation(Mat screen, BotContext ctx, RunTracker runs) {
        // Wer auf die Einladung des Freundes wartet, drueckt NICHT
        // "Participate" -- sonst sucht er selbst einen Mitspieler, und die
        // beiden Bots laden sich gegenseitig ein.
        if (teammate == Teammate.FRIEND && aufEinladungWarten) {
            return einladungAbwarten(screen, ctx, runs);
        }

        // Mit einem Freund: erst nachsehen, ob er schon eingeladen hat.
        if (teammate == Teammate.FRIEND) {
            // Eben aus der Liste eingeladen und schon wieder hier, ohne
            // Wartebildschirm? Dann ist die Einladung verpufft.
            Outcome offen = offeneEinladung(ctx, runs);
            if (offen != null) return offen;

            Outcome vorher = vorDemTeilnehmen(ctx, runs);
            if (vorher != null) return vorher;
        }

        // Bezahlen (Teil A, 28.09.): Fackeln oder Nahrung, je nach Einstellung.
        // Bei "erst Tickets, dann Nahrung" die Fackeln lesen; sind sie alle,
        // geht es mit Nahrung weiter. Die Kasse stellt den Reiter um und
        // prueft Preis, Limit und Vorrat -- reicht die Nahrung nicht sicher,
        // wird "Participate" nicht gedrueckt (nie Diamanten).
        Outcome fackeln = kasse.ticketsPruefen(screen, ctx, T_FACKEL_ICON);
        if (fackeln != null) return fackeln;
        Outcome bezahlt = kasse.vorTeilnahme(screen, ctx);
        if (bezahlt != null) return bezahlt;

        if (click(screen, ctx, T_PARTICIPATE, Sprache.t("Enter the Dungeon", "Verlies betreten"))
                || kasse.participateOberteil(screen, ctx, T_PARTICIPATE, Sprache.t("Enter the Dungeon", "Verlies betreten"))
                || kasse.apfelImKnopf(screen, ctx, Sprache.t("Enter the Dungeon", "Verlies betreten"))) {
            kasse.teilgenommen();
            neuerDurchlaufBeginnt();
            runs.progress(Sprache.t("Participation confirmed", "Teilnahme bestaetigt"));
            ctx.sleepUntilChanged(2200);

            // Der Bot haelt hier NICHT mehr an.
            //
            // Frueher stand hier ein Abbruch: "Sehr wahrscheinlich sind keine
            // Fackeln mehr vorhanden." Die Vermutung war oft falsch -- zuletzt
            // klickte der Bot durch einen Fehler von mir sein eigenes
            // Teilnahmefenster weg, und nach sechs Runden stand der Bot still,
            // obwohl Fackeln da waren.
            //
            // Ein stehender Bot ist das Schlimmste, was passieren kann: Er
            // laeuft naechtelang ohne Aufsicht, und niemand merkt, dass seit
            // drei Stunden nichts mehr geht.
            //
            // Jetzt wird gewartet und weiterprobiert. Fackeln wachsen mit der
            // Zeit nach -- wer wartet, kommt irgendwann durch. Und Autor
            // bekommt Bescheid, statt eine stille Leiche vorzufinden.
            if (participateAttempts > 6) {
                Log.alarm("Verlies", Sprache.t("The participation screen cannot be left ("
                        + participateAttempts + " attempts). Possible reasons: no "
                        + "torches left, or a picture does not match. The bot waits "
                        + "two minutes and then keeps trying -- it does not stop.",
                        "Das Teilnahmefenster laesst sich nicht verlassen ("
                        + participateAttempts + " Versuche). Moegliche Gruende: keine "
                        + "Fackeln mehr, oder eine Vorlage passt nicht. Der Bot wartet "
                        + "zwei Minuten und probiert es dann weiter -- er haelt nicht an."));
                participateAttempts = 0;
                ctx.sleep(120_000);
            }
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    /**
     * Ein neuer Durchlauf faengt an: Zaehlung und Geduld auf Null.
     *
     * Gilt fuer beide Wege hinein -- "Participate" wie bisher und "Accept",
     * wenn ein Freund eingeladen hat oder es mit ihm direkt weitergeht.
     */
    /** Wann das Verlies betreten wurde (Hauptbot 15.10.: ein Absturz danach zaehlt nicht als geschafft). */
    private long betretenUm = 0;
    private int opferVersuche = 0;
    /** Namen aus opfer-namen.txt (Einstellungen "Opfer-Einheiten", mit Komma oder je Zeile). */
    public static List<String> opferNamen() {
        List<String> n = new ArrayList<>();
        try {
            java.nio.file.Path d = de.hustlebot.core.Ordner.bei("opfer-namen.txt");
            if (!java.nio.file.Files.exists(d)) return n;
            for (String z : de.hustlebot.core.Dateien.zeilen(d))
                for (String s : z.split("[,;]")) if (!s.isBlank() && !s.strip().startsWith("#")) n.add(s.strip());
        } catch (Exception e) {
            // ohne Liste
        }
        return n;
    }

    /** So oft wurde die Einheiten-Leiste am Altar gewischt (Autor 18.10.). */
    private int leisteWische = 0;

    /** Einheit an der gefundenen Stelle in das eigene Altar-Feld ziehen. */
    private Outcome opferZiehen(Mat screen, BotContext ctx, RunTracker runs, int[] stelle) {
        boolean alsLeiter = isLeader(screen, ctx);
        int zielX = (int) (ctx.width() * (alsLeiter ? SACRIFICE_SLOT_LEFT : SACRIFICE_SLOT_RIGHT));
        int zielY = (int) (ctx.height() * SACRIFICE_SLOT_Y);
        ctx.input().drag(stelle[0], stelle[1], zielX, zielY);
        ctx.sleepUntilChanged(1400);
        sacrificeDone = true;
        runs.progress(Sprache.t("Unit sacrificed", "Einheit geopfert"));
        return Outcome.ACTED;
    }
    /** Am Altar schon am Namen gesucht -- erst danach die hinteren Einheiten. */
    private boolean namenGesucht = false;

    /**
     * Einheit in der Leiste am Namen finden (Autor 18.10.: Ruestung geaendert, Name gleich). Die Namen stehen in einer
     * Zeile unter den Bildern; jeder dunkle Schriftblock wird gelesen. Tote Einheiten (Spalte eines Tot-Zeichens) zaehlen
     * nicht. @return {x, y} der Einheit (Bild ueber dem Namen) oder null
     */
    private int[] einheitAmNamen(BotContext ctx, String gesucht, List<Matcher.Hit> tote) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return null;
        double s = b.getWidth() / 1159.0;
        int y0 = (int) (526 * s), y1 = Math.min(b.getHeight(), (int) (548 * s));
        for (int[] block : schriftBloecke(b)) {
            int a = block[0], e = block[1];
            int mitte = (a + e) / 2;
            if (tote.stream().anyMatch(d -> Math.abs(d.centerX() - mitte) < 45 * s)) continue;
            String gelesen = String.join(" ", ArenaLeser.liesNamenZeilen(b, Math.max(0, a - 4), Math.max(0, y0 - 3), e - a + 8, y1 - y0 + 6));
            if (de.hustlebot.core.NamensVergleich.gleicherName(gelesen, gesucht)
                    || de.hustlebot.core.NamensVergleich.aehnlichkeit(gelesen, gesucht) >= 0.75) {
                Log.debug("Verlies", "Name \"" + gelesen + "\" passt zu \"" + gesucht + "\" bei x=" + mitte);
                return new int[]{mitte, (int) (470 * s)};
            }
        }
        return null;
    }

    /** Die dunklen Schriftbloecke der Namenszeile unter den Bildern am Altar, von links nach rechts: je {von x, bis x}. */
    static List<int[]> schriftBloecke(java.awt.image.BufferedImage b) {
        List<int[]> bloecke = new ArrayList<>();
        double s = b.getWidth() / 1159.0;
        int y0 = (int) (526 * s), y1 = Math.min(b.getHeight(), (int) (548 * s));
        int x0 = (int) (90 * s), x1 = Math.min(b.getWidth(), (int) (1070 * s));
        boolean[] text = new boolean[x1];
        for (int x = x0; x < x1; x++) {
            int dunkel = 0;
            for (int y = y0; y < y1; y++) {
                int p = b.getRGB(x, y);
                if (((p >> 16) & 255) < 90 && ((p >> 8) & 255) < 90 && (p & 255) < 90) dunkel++;
            }
            text[x] = dunkel >= 2;
        }
        int x = x0;
        while (x < x1) {
            if (!text[x]) { x++; continue; }
            int a = x, luecke = 0, e = x;
            while (x < x1 && luecke <= (int) (9 * s)) {
                if (text[x]) { e = x; luecke = 0; } else luecke++;
                x++;
            }
            if (e - a >= (int) (18 * s)) bloecke.add(new int[]{a, e});
        }
        return bloecke;
    }

    /** Die letzte lebende Einheit der Leiste: {x, y} des Bildes ueber ihrem Namen, oder null. */
    static int[] letzteLebende(List<int[]> bloecke, int bildBreite, List<Matcher.Hit> tote) {
        double s = bildBreite / 1159.0;
        for (int i = bloecke.size() - 1; i >= 0; i--) {
            int mitte = (bloecke.get(i)[0] + bloecke.get(i)[1]) / 2;
            if (tote.stream().anyMatch(d -> Math.abs(d.centerX() - mitte) < 45 * s)) continue;
            return new int[]{mitte, (int) (470 * s)};
        }
        return null;
    }

    private void neuerDurchlaufBeginnt() {
        einladungOffen = null;
        einladungVersuche = 0;
        participateAttempts++;
        insideDungeon = true;
        betretenUm = System.currentTimeMillis();
        // Clan (18.11.): neue Runde -- die Warte-Uhr und die Zahl der Einladungen fangen neu an (nur nach einer gespielten
        // Runde; ein "in 5 min" der laufenden Einladung bleibt stehen).
        if (clanPlan.zustand() == ClanVerliesPlan.Zustand.ANGENOMMEN) {
            clanPlan = neuerClanPlan();
            clan.zuruecksetzen();
        }
        clanEingeladenUm = 0;
        clanEinladungen = 0;
        runCounted = false;
        blockGeprueft = false;
        runAborted = false;
        letzterStuetzpunkt = null;
        allyLeftNachBossSeit = 0;
        waitingForPartnerSince = 0;
        duelConfirmed = false;
        einladungGesendetUm = 0;
        folgeReiseAufgegeben = false;
        einladungAbwartenSeit = 0;
        vorTeilnahme = 0;
        freundEingeladenUm = 0;
        folgeEinladenAb = 0;
        neueEinladungsrunde();
    }

    /**
     * Auf dem Teilnahmebildschirm: auf die Einladung des Freundes warten.
     *
     * Die Einladung kommt im Chat an. Der Bot schaut alle 45 Sekunden hinein
     * und nimmt sie an; danach steht statt "Participate" der Knopf "Accept"
     * da -- das erledigt handleInvitation(). Fehlen die Bilder fuer den
     * Chat, oeffnet er wie frueher alle anderthalb Minuten das Verlies-
     * Fenster neu. Nach einer halben Stunde ohne Einladung gibt er auf und
     * sagt Bescheid -- ewig zu warten hilft niemandem.
     */
    private Outcome einladungAbwarten(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        boolean chat = FreundesChat.bereit(ctx.matcher());
        if (einladungAbwartenSeit == 0) {
            einladungAbwartenSeit = jetzt;
            // Im Chat gleich nachsehen, das Fenster dagegen erst spaeter neu oeffnen.
            letzterBlickNachEinladung = chat ? 0 : jetzt;
            Log.info("Verlies", Sprache.t("The bot is waiting for the invite from " + friendName
                    + " (at most " + (EINLADUNG_HOECHSTENS_MS / 60_000) + " minutes).",
                    "Der Bot wartet auf die Einladung von " + friendName
                    + " (hoechstens " + (EINLADUNG_HOECHSTENS_MS / 60_000) + " Minuten)."));
            runs.progress(Sprache.t("Waiting for the invite from " + friendName, "Warte auf Einladung von " + friendName));
        }

        if (jetzt - einladungAbwartenSeit > EINLADUNG_HOECHSTENS_MS) {
            Log.error("Verlies", Sprache.t("No invite from " + friendName + " for "
                    + (EINLADUNG_HOECHSTENS_MS / 60_000) + " minutes. The bot stops. Is the bot "
                    + "running at " + friendName + "'s, and did it get \"!start dungeon 10 <your name>\"?",
                    "Seit " + (EINLADUNG_HOECHSTENS_MS / 60_000) + " Minuten keine "
                    + "Einladung von " + friendName + ". Der Bot hoert auf. Laeuft bei "
                    + friendName + " der Bot, und hat er \"!start dungeon 10 <dein Name>\" "
                    + "bekommen?"));
            einladungAbwartenSeit = 0;
            return Outcome.FATAL;
        }

        if (jetzt - letzterBlickNachEinladung > (chat ? EINLADUNG_CHAT_MS : EINLADUNG_NEU_SCHAUEN_MS)) {
            letzterBlickNachEinladung = jetzt;
            // Das Warten ist Absicht, kein Stillstand -- der Waechter soll
            // deshalb nicht eingreifen.
            runs.progress(Sprache.t("Waiting for the invite from " + friendName, "Warte auf Einladung von " + friendName));
            if (chat) {
                if (chatAnnehmen(ctx, runs)) einladungAbwartenSeit = 0;
                return Outcome.ACTED;
            }
            if (click(screen, ctx, T_CLOSE_X, Sprache.t("Reopen the Dungeon window (invite there?)", "Verlies-Fenster neu oeffnen (Einladung da?)"))) {
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }
        return Outcome.WAITING;
    }

    // ------------------------------------------------------------------
    //  Mit einem Freund spielen: Einladung annehmen, gemeinsam weiter
    // ------------------------------------------------------------------

    /**
     * Teilnahmebildschirm mit Einladung: "Decline" oder "Accept" (1 Fackel).
     *
     * Im Freundesmodus wird angenommen -- entweder hat der Freund eingeladen,
     * oder es geht nach "Start journey" gemeinsam weiter.
     *
     * Soll mit einem zufaelligen Mitspieler gespielt werden, lehnt der Bot ab,
     * damit danach wieder "Participate" erscheint. Fehlt dafuer die Vorlage
     * "invite_decline", nimmt er lieber an, als davor stehenzubleiben -- ein
     * Lauf mit einem Freund ist allemal besser als keiner.
     */
    private Outcome handleInvitation(Mat screen, BotContext ctx, RunTracker runs) {
        boolean ablehnen = teammate == Teammate.RANDOM && ctx.matcher().has(T_INVITE_DECLINE);
        if (ablehnen) {
            if (click(screen, ctx, T_INVITE_DECLINE, Sprache.t("Decline the invite (random partner wanted)", "Einladung ablehnen (zufaelliger Mitspieler gewuenscht)"))) {
                runs.progress(Sprache.t("Invite declined", "Einladung abgelehnt"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // Bezahlen -- wie beim normalen Teilnehmen (Teil A). ABER: Hat das
        // Einladungsfenster keinen Food-Reiter, wird die Einladung ohne ihn
        // angenommen -- sonst wuerde der Food-Modus den Auftrag beenden.
        boolean mitReiter = !kasse.mitNahrung()
                || ctx.visibleIfKnown(screen, "du_for_food") || ctx.visibleIfKnown(screen, "du_for_food_off");
        if (mitReiter) {
            Outcome bezahlt = kasse.vorTeilnahme(screen, ctx);
            if (bezahlt != null) return bezahlt;
        }

        if (click(screen, ctx, T_INVITE_ACCEPT, Sprache.t("Accept the invite (1 torch)", "Einladung annehmen (1 Fackel)"))) {
            if (mitReiter) kasse.teilgenommen();
            else kasse.teilgenommenOhneNahrung();
            if (teammate == Teammate.RANDOM) {
                Log.info("Verlies", Sprache.t("An invite was accepted although a random "
                        + "partner was wanted -- the picture invite_decline is missing.",
                        "Eine Einladung wurde angenommen, obwohl ein zufaelliger "
                        + "Mitspieler gewuenscht war -- die Vorlage invite_decline fehlt."));
            }
            neuerDurchlaufBeginnt();
            runs.progress(Sprache.t("Invite accepted", "Einladung angenommen"));
            ctx.sleepUntilChanged(2200);
            return Outcome.ACTED;
        }
        return Outcome.WAITING;
    }

    /**
     * Sind die Knoepfe fuer das direkte Weiterspielen vorhanden? Wer auf die
     * Einladung wartet, braucht "Invite partner" nicht.
     */
    private boolean folgeReiseMoeglich(BotContext ctx) {
        return (aufEinladungWarten || ctx.matcher().has(T_INVITE_PARTNER))
            && ctx.matcher().has(T_START_JOURNEY)
            && ctx.matcher().has(T_INVITE_ACCEPT);
    }

    /**
     * Abschlussfenster im Freundesmodus: mit demselben Freund gleich weiter.
     *
     * Liefert null, wenn es auf dem gewohnten Weg weitergehen soll -- Fenster
     * zu, dann ueber die Freundesliste, oder mit einem zufaelligen
     * Mitspieler, wenn der Freund weg ist. Das gilt, wenn gar nicht mit einem
     * Freund gespielt wird, die Knoepfe fehlen, der Lauf abgebrochen war,
     * alle Durchlaeufe geschafft sind oder der Freund nicht antwortet.
     */
    private Outcome folgeReise(Mat screen, BotContext ctx, RunTracker runs) {
        // Ist der Freund weg, spielt der Bot mit Fremden -- mit denen geht
        // es nach dem Verlies nicht weiter.
        if (teammate != Teammate.FRIEND || !freundAktiv() || !folgeReiseMoeglich(ctx)) return null;
        if (runAborted || abortReason != null || folgeReiseAufgegeben) return null;

        // Geschafft ist geschafft: jetzt zaehlen, nicht erst beim Schliessen.
        // Meist ist es beim Klick auf "End journey" schon passiert -- dann
        // tut das hier nichts.
        endRun(runs, Sprache.t("completion screen", "Abschlussfenster"));
        if (runs.isDone()) return null;   // Ziel erreicht: Fenster zu, fertig

        // 1. Beide bereit -- oder der Freund hat zuerst eingeladen und wartet.
        if (click(screen, ctx, T_START_JOURNEY, Sprache.t("Start the next journey with " + friendName, "Naechste Reise mit " + friendName + " starten"))) {
            // Wie beim Schliessen ueber das X: Der alte Lauf ist vorbei. Der
            // neue beginnt erst mit "Accept" auf dem Teilnahmebildschirm.
            insideDungeon = false;
            abortCloseClicks = 0;
            cooldowns.clear();
            endJourneyAllowed = false;
            participateAttempts = 0;
            einladungGesendetUm = 0;
            folgeEinladenAb = 0;
            // WICHTIG (Autor, 18.09.): den Zaehl-Merker zuruecksetzen. Sonst
            // gilt der schon gezaehlte alte Lauf weiter, der neue Folgelauf
            // wird NIE gezaehlt -- der Zaehler blieb bei "1 von 2" stehen, der
            // Bot spielte endlos weiter, statt nach den bestellten Laeufen zu
            // stoppen. Der Folgelauf mit Freund lief bisher an "Accept" vorbei,
            // wo sonst zurueckgesetzt wird.
            runCounted = false;
            runAborted = false;
            freundWiederDa();
            runs.progress(Sprache.t("Next journey with " + friendName, "Naechste Reise mit " + friendName));
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }

        // Nach einem Neustart des Bots weiss er nicht mehr, dass er schon
        // eingeladen hat. Der Hinweis im Fenster sagt es ihm.
        if (einladungGesendetUm == 0 && ctx.matcher().has(T_INVITE_SENT)
                && ctx.visible(screen, T_INVITE_SENT)) {
            einladungGesendetUm = System.currentTimeMillis();
        }

        // 2. Noch nicht eingeladen: einladen -- ausser der Bot soll warten.
        if (einladungGesendetUm == 0) {
            if (aufEinladungWarten) {
                // Nicht selbst einladen. Laedt der Freund ein, erscheint hier
                // "Start journey" ("Your partner is waiting for you"). Die Uhr
                // laeuft trotzdem: Kommt nichts, geht es auf dem gewohnten Weg
                // weiter und der Bot wartet auf dem Teilnahmebildschirm.
                einladungGesendetUm = System.currentTimeMillis();
                Log.info("Verlies", Sprache.t("The bot is waiting for " + friendName
                        + " to invite to the next journey.",
                        "Der Bot wartet, dass " + friendName
                        + " zur naechsten Reise einlaedt."));
                return Outcome.WAITING;
            }
            // Nicht sofort einladen. Laeuft beim Freund auch ein Bot, druecken
            // sonst beide im selben Augenblick "Invite partner". Laedt der
            // Freund in der Pause zuerst ein, erscheint "Start journey" --
            // Schritt 1 oben nimmt dann an.
            long jetzt = System.currentTimeMillis();
            if (folgeEinladenAb == 0) {
                folgeEinladenAb = jetzt + zufall(FOLGE_VOR_EINLADEN_MIN_MS, FOLGE_VOR_EINLADEN_MAX_MS);
                Log.info("Verlies", Sprache.t("The bot waits " + ((folgeEinladenAb - jetzt) / 1000)
                        + " seconds to see whether " + friendName + " invites first.",
                        "Der Bot wartet " + ((folgeEinladenAb - jetzt) / 1000)
                        + " Sekunden, ob " + friendName + " zuerst einlaedt."));
            }
            if (jetzt < folgeEinladenAb) return Outcome.WAITING;

            if (click(screen, ctx, T_INVITE_PARTNER, Sprache.t("Invite " + friendName + " to the next journey", friendName + " zur naechsten Reise einladen"))) {
                einladungGesendetUm = System.currentTimeMillis();
                Log.info("Verlies", Sprache.t(friendName + " is invited to the next journey. The bot "
                        + "waits up to " + (FOLGEREISE_WARTEN_MS / 60_000) + " minutes for "
                        + "the acceptance.",
                        friendName + " ist zur naechsten Reise eingeladen. Der Bot "
                        + "wartet bis zu " + (FOLGEREISE_WARTEN_MS / 60_000) + " Minuten auf "
                        + "die Zusage."));
                runs.progress(Sprache.t(friendName + " invited", friendName + " eingeladen"));
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }
            // Kein Knopf zu sehen -- etwa weil der Freund das Fenster schon
            // geschlossen hat. Dann auf dem gewohnten Weg.
            return null;
        }

        // 3. Eingeladen, die Antwort steht noch aus.
        if (System.currentTimeMillis() - einladungGesendetUm < FOLGEREISE_WARTEN_MS) {
            return Outcome.WAITING;
        }
        einladungGesendetUm = 0;
        folgeReiseAufgegeben = true;
        // Autor 22.10.: "Wenn mir Ben nach dem Dungeon eine Anfrage schickt, soll der Bot sie annehmen -- immer (Schritt 1
        // oben). Schickt er keine, soll der Bot eine schicken, bis er annimmt. Wenn er nach 3 Minuten nicht annimmt, geh
        // bitte in die Burg." Gleich darauf: "Man soll in die Burg gehen und dann halt weiterspielen mit Fremden."
        // Also: Fenster zu (wie gewohnt, return null), in die Burg, dann mit Zufallsspielern weiter. Laedt der Freund
        // spaeter ein, nimmt der Bot an (Blick in den Chat vor jedem Verlies).
        if (!aufEinladungWarten) {
            freundWeg(Sprache.t(friendName + " did not accept the next journey within " + (FOLGEREISE_WARTEN_MS / 60_000)
                            + " minutes -- off to the castle first.",
                    friendName + " hat die naechste Reise nach " + (FOLGEREISE_WARTEN_MS / 60_000)
                            + " Minuten nicht angenommen -- erst in die Burg."));
            burgJetzt = true;
            return null;
        }
        if (aufEinladungWarten) {
            Log.info("Verlies", Sprache.t(friendName + " did not invite to the next journey. The "
                    + "window is being closed, the bot keeps waiting for the invite.",
                    friendName + " hat nicht zur naechsten Reise eingeladen. Das "
                    + "Fenster wird geschlossen, der Bot wartet weiter auf die Einladung."));
        } else {
            // NOCH NICHT als weg abhaken. Ob er weg ist, zeigt der gruene
            // Punkt in der Freundesliste -- und genau dorthin fuehrt der
            // gewohnte Weg jetzt: Fenster zu, Blick in den Chat, Liste.
            // Offline: gleich Zufallsspieler (Autors Regel). Online: eine
            // Einladung ueber die Liste, mit allem, was gegen Kreuzungen hilft.
            //
            // Frueher galt er hier sofort als weg. Hatten beide Bots im selben
            // Augenblick "Invite partner" gedrueckt, warteten beide drei
            // Minuten, gaben sich gleichzeitig auf -- und weil danach keiner
            // mehr einlaedt, spielten beide bis zum Morgen mit Fremden.
            Log.info("Verlies", Sprache.t(friendName + " did not accept the invite to the next journey within "
                    + (FOLGEREISE_WARTEN_MS / 60_000) + " minutes. The window is being closed; "
                    + "the friend list will show in a moment whether " + friendName
                    + " is online -- if not, the bot continues with random players.",
                    friendName + " hat die Einladung zur naechsten Reise nach "
                    + (FOLGEREISE_WARTEN_MS / 60_000) + " Minuten nicht angenommen. Das Fenster "
                    + "wird geschlossen; die Freundesliste zeigt gleich, ob " + friendName
                    + " online ist -- wenn nicht, geht es mit Zufallsspielern weiter."));
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Wenn bei beiden ein Bot laeuft: wer laedt ein, wer nimmt an?
    // ------------------------------------------------------------------

    /**
     * Teilnahmebildschirm mit einem Freund, BEVOR "Participate" gedrueckt wird.
     *
     *   1. In den Chat schauen. Hat der Freund eingeladen: annehmen.
     *   2. Eine zufaellige Weile warten, 8 bis 40 Sekunden.
     *   3. Noch einmal in den Chat schauen -- hat der Freund inzwischen
     *      eingeladen, wird angenommen. Sonst laedt der Bot selbst ein.
     *
     * Laufen bei beiden Bots, wuerfeln sie verschiedene Pausen. Der eine laedt
     * ein, der andere findet die Einladung beim zweiten Blick.
     *
     * Ist der Freund weg, entfaellt die Pause: ein Blick in den Chat --
     * vielleicht ist er wieder da --, dann ein zufaelliger Mitspieler.
     *
     * Liefert null, wenn es mit "Participate" weitergehen soll.
     */
    private Outcome vorDemTeilnehmen(BotContext ctx, RunTracker runs) {
        // Ohne die Bilder fuer den Chat laesst sich nicht nachsehen -- dann
        // laedt der Bot ein wie bisher.
        if (!FreundesChat.bereit(ctx.matcher())) return null;

        long jetzt = System.currentTimeMillis();
        if (vorTeilnahme == 0) {
            vorTeilnahme = 1;
            if (chatAnnehmen(ctx, runs)) return Outcome.ACTED;
            if (!freundAktiv()) {
                vorTeilnahme = 2;
                return Outcome.ACTED;
            }
            vorTeilnahmeBis = jetzt + zufall(VOR_EINLADEN_MIN_MS, VOR_EINLADEN_MAX_MS);
            Log.info("Verlies", Sprache.t("No invite from " + friendName + " yet. The bot waits "
                    + ((vorTeilnahmeBis - jetzt) / 1000) + " seconds, then checks the chat once "
                    + "more and otherwise invites on its own.",
                    "Noch keine Einladung von " + friendName + ". Der Bot wartet "
                    + ((vorTeilnahmeBis - jetzt) / 1000) + " Sekunden, schaut dann noch einmal "
                    + "in den Chat und laedt sonst selbst ein."));
            runs.progress(Sprache.t("Waiting briefly to see whether " + friendName + " invites", "Kurz warten, ob " + friendName + " einlaedt"));
            return Outcome.ACTED;
        }
        if (vorTeilnahme == 1) {
            if (jetzt < vorTeilnahmeBis) return Outcome.WAITING;
            vorTeilnahme = 2;
            chatAnnehmen(ctx, runs);
            return Outcome.ACTED;
        }
        return null;
    }

    /** Schaut in den Chat und nimmt die Einladung EINES der Freunde an, wenn eine da ist. */
    private boolean chatAnnehmen(BotContext ctx, RunTracker runs) {
        if (FreundesChat.nachsehen(ctx, freundeVorlagen(), freundeListe, true)
                != FreundesChat.Ergebnis.ANGENOMMEN) {
            return false;
        }
        freundWiederDa();
        runs.progress(Sprache.t("Invite accepted in the chat", "Einladung im Chat angenommen"));
        return true;
    }

    /**
     * Soll der Bot den Freund einladen? Nein, sobald er als weg gilt -- dann
     * spielt der Bot bis zum Ende des Auftrags zufaellig. Einen neuen
     * Versuch gibt es nicht. Autor, 12.09.: "lass das weg das der bot es
     * alle 15min wieder mit meinem freund versucht". Nur wenn der Freund
     * selbst einlaedt, nimmt der Bot wieder an -- siehe vorDemTeilnehmen().
     */
    private boolean freundAktiv() {
        return freundWegSeit == 0;
    }

    /** Der Freund ist offline oder hat nicht angenommen: zufaellig weiter. */
    private void freundWeg(String grund) {
        boolean zumErstenMal = freundWegSeit == 0;
        freundWegSeit = System.currentTimeMillis();
        String text = Sprache.t(grund + " The bot continues with random partners. If "
                + friendName + " invites it, it accepts -- it checks the chat before every Dungeon.",
                grund + " Der Bot spielt mit zufaelligen Mitspielern weiter. Laedt "
                + friendName + " ihn ein, nimmt er an -- er schaut vor jedem Verlies in den Chat.");
        // Nur beim ersten Mal aufs Handy.
        if (zumErstenMal) Log.alarm("Verlies", text);
        else Log.info("Verlies", text);
    }

    private void freundWiederDa() {
        if (freundWegSeit != 0) Log.info("Verlies", Sprache.t(friendName + " is back in.", friendName + " ist wieder dabei."));
        freundWegSeit = 0;
    }

    /**
     * Ist der Freund gerade im Spiel? Das zeigt der gruene Punkt in seiner
     * Zeile der Freundesliste, zwischen Name und Kampfkraft (Autors Bild vom
     * 12.09.). Fehlt das Bild online_dot, gilt er als online -- dann laedt
     * der Bot ein wie bisher.
     *
     * Die Lage stammt aus EINEM Bild und ist an keiner echten Liste
     * nachgemessen. Deshalb zwei Stufen: erst dort, wo der Punkt sitzen
     * sollte; dann irgendwo sonst in derselben Zeile -- links vom
     * Invite-Knopf und diesem Knopf naeher als jedem anderen. Auch die
     * zweite Stufe zaehlt und schreibt ins Protokoll, wo der Punkt wirklich
     * sitzt. Lieber einmal zu Unrecht einladen (kostet hoechstens drei
     * Minuten) als den Freund die ganze Nacht fuer offline halten.
     */
    private boolean istOnline(Mat screen, BotContext ctx, Matcher.Hit name, Matcher.Hit einladen,
                              List<Matcher.Hit> alleEinladen) {
        if (!ctx.matcher().has(T_ONLINE)) return true;
        List<Matcher.Hit> punkte = ctx.matcher().findAll(screen, T_ONLINE, 0.80, 20);
        for (Matcher.Hit punkt : punkte) {
            if (Math.abs(punkt.centerY() - name.centerY()) <= name.height()
                    && punkt.centerX() > name.x() && punkt.centerX() < einladen.x()) {
                return true;
            }
        }
        for (Matcher.Hit punkt : punkte) {
            if (punkt.centerX() >= einladen.x()) continue;
            int abstand = Math.abs(punkt.centerY() - einladen.centerY());
            if (abstand > einladen.height() * 2) continue;
            boolean andereZeile = false;
            for (Matcher.Hit anderer : alleEinladen) {
                if (!anderer.equals(einladen)
                        && Math.abs(punkt.centerY() - anderer.centerY()) < abstand) {
                    andereZeile = true;
                    break;
                }
            }
            if (andereZeile) continue;
            Log.warn("Verlies", Sprache.t("The green dot next to " + friendName + " sits elsewhere than "
                    + "expected (" + (punkt.centerX() - name.x()) + " / "
                    + (punkt.centerY() - name.centerY()) + " pixels from the name). It still "
                    + "counts -- " + friendName + " is treated as online.",
                    "Der gruene Punkt bei " + friendName + " sitzt anders als "
                    + "erwartet (" + (punkt.centerX() - name.x()) + " / "
                    + (punkt.centerY() - name.centerY()) + " Bildpunkte vom Namen). Er zaehlt "
                    + "trotzdem -- " + friendName + " gilt als online."));
            return true;
        }
        Log.info("Verlies", Sprache.t("No green dot in the row of " + friendName + " ("
                + punkte.size() + " green dots in the whole picture).",
                "Kein gruener Punkt in der Zeile von " + friendName + " ("
                + punkte.size() + " gruene Punkte im ganzen Bild)."));
        return false;
    }

    /**
     * Wartebildschirm nach einer Einladung aus der Freundesliste.
     *
     * Zwei Dinge koennen schiefgehen:
     *
     *  - Der Freund hat den Bot im selben Augenblick eingeladen. Dann warten
     *    beide aufeinander. Alle 20 bis 40 Sekunden schaut der Bot deshalb in
     *    den Chat -- falls es ihn auf diesem Bildschirm gibt. Liegt dort die
     *    Einladung des Freundes, wird gewuerfelt: Bei Kopf zieht der Bot
     *    seine Einladung zurueck und nimmt an, bei Zahl wartet er weiter.
     *    Wuerfeln beide dasselbe, geht es beim naechsten Blick von vorn.
     *
     *  - Er nimmt nicht an. Nach anderthalb bis drei Minuten -- zufaellig,
     *    damit bei einer Kreuzung einer frueher aufgibt -- zieht der Bot
     *    zurueck und schaut noch einmal in den Chat. Liegt da nichts, gilt
     *    der Freund als weg.
     */
    private Outcome aufFreundWarten(BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();

        if (jetzt >= naechsterKreuzungsBlick) {
            naechsterKreuzungsBlick = jetzt + zufall(KREUZUNG_MIN_MS, KREUZUNG_MAX_MS);
            FreundesChat.Ergebnis blick = FreundesChat.nachsehen(ctx, freundeVorlagen(), freundeListe, false);
            if (blick == FreundesChat.Ergebnis.GEFUNDEN) {
                if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
                    Log.info("Verlies", Sprache.t("The bot and " + friendName + " invited each other at the "
                            + "same time. The bot withdraws its invite and accepts the one from "
                            + friendName + ".",
                            "Der Bot und " + friendName + " haben sich gleichzeitig "
                            + "eingeladen. Der Bot zieht seine Einladung zurueck und nimmt die von "
                            + friendName + " an."));
                    return eigeneEinladungZurueck(ctx, runs, true);
                }
                Log.info("Verlies", Sprache.t("The bot and " + friendName + " invited each other at the "
                        + "same time. The bot keeps its own -- " + friendName + " should accept.",
                        "Der Bot und " + friendName + " haben sich gleichzeitig "
                        + "eingeladen. Der Bot bleibt bei seiner -- " + friendName + " soll annehmen."));
            }
            if (blick != FreundesChat.Ergebnis.NICHT_MOEGLICH) return Outcome.ACTED;
        }

        if (jetzt < freundWartenBis) return Outcome.WAITING;

        Log.info("Verlies", Sprache.t(friendName + " did not accept the invite within "
                + ((jetzt - freundEingeladenUm) / 1000) + " seconds.",
                friendName + " hat die Einladung nach "
                + ((jetzt - freundEingeladenUm) / 1000) + " Sekunden nicht angenommen."));
        return eigeneEinladungZurueck(ctx, runs, false);
    }

    /**
     * Zieht die eigene Einladung zurueck ("Cancel") und schaut in den Chat.
     * Liegt dort die Einladung des Freundes, wird sie angenommen.
     */
    private Outcome eigeneEinladungZurueck(BotContext ctx, RunTracker runs, boolean gekreuzt) {
        Mat screen = ctx.refresh();
        if (screen == null || !ctx.clickIfVisible(screen, T_CANCEL_SEARCH,
                Sprache.t("Withdraw the invite to " + friendName, "Einladung an " + friendName + " zurueckziehen"))) {
            return Outcome.WAITING;
        }
        freundEingeladenUm = 0;
        searchWaitingSince = 0;
        runs.progress(Sprache.t("Invite to " + friendName + " withdrawn", "Einladung an " + friendName + " zurueckgezogen"));
        ctx.sleepUntilChanged(2000);
        ctx.sleep(500);

        if (chatAnnehmen(ctx, runs)) return Outcome.ACTED;

        if (gekreuzt) {
            // Die Einladung des Freundes ist inzwischen weg -- er hat wohl
            // ebenfalls zurueckgezogen. Also neu, nach einer zufaelligen Pause.
            long pause = zufall(VOR_EINLADEN_MIN_MS, VOR_EINLADEN_MAX_MS);
            freundWaehlenAb = System.currentTimeMillis() + pause;
            Log.info("Verlies", Sprache.t("The invite from " + friendName + " is gone. The "
                    + "bot invites again in " + (pause / 1000) + " seconds.",
                    "Die Einladung von " + friendName + " ist nicht mehr da. Der "
                    + "Bot laedt in " + (pause / 1000) + " Sekunden neu ein."));
        } else {
            freundWeg(Sprache.t(friendName + " did not accept the invite.", friendName + " hat die Einladung nicht angenommen."));
        }
        return Outcome.ACTED;
    }

    /**
     * Eine Einladung aus der Freundesliste steht aus, aber der Bot ist nicht
     * im Wartebildschirm, sondern (wieder) in der Mitspielerwahl oder auf dem
     * Teilnahmebildschirm. Liefert null, wenn keine Einladung aussteht.
     *
     * Kurz nach "Invite" ist das normal: "Send", Liste zu, dann erst kommt
     * der Wartebildschirm. So lange wird gewartet. Danach ist die Einladung
     * verpufft -- der Freund hat abgelehnt, sie ist verfallen, oder "Send"
     * hat nicht gegriffen und sie ging nie hinaus.
     *
     * FRUEHER blieb der Merker freundEingeladenUm in diesem Fall einfach
     * stehen. Der Bot oeffnete die Liste, sah "schon eingeladen", schloss sie,
     * oeffnete sie wieder ... Jeder Klick zaehlte als Fortschritt, also griff
     * keine Stillstandsuhr -- die ganze Nacht lang.
     */
    private Outcome offeneEinladung(BotContext ctx, RunTracker runs) {
        if (freundEingeladenUm == 0) return null;
        if (System.currentTimeMillis() - freundEingeladenUm < EINLADUNG_UNTERWEGS_MS) {
            return Outcome.WAITING;
        }
        boolean gesehen = freundWartebildGesehen;
        freundEingeladenUm = 0;
        freundWartebildGesehen = false;

        // Hat er stattdessen selbst eingeladen? Dann annehmen.
        if (chatAnnehmen(ctx, runs)) return Outcome.ACTED;

        freundWeg(gesehen
                ? Sprache.t(friendName + " did not accept the invite.", friendName + " hat die Einladung nicht angenommen.")
                : Sprache.t("The invite to " + friendName + " apparently never went out -- "
                  + "the waiting screen did not appear. Does the picture invite_send still match?",
                  "Die Einladung an " + friendName + " ist offenbar nicht hinausgegangen -- "
                  + "der Wartebildschirm kam nicht. Passt das Bild invite_send noch?"));
        return Outcome.ACTED;
    }

    /**
     * Schliesst die Freundesliste ueber IHR rotes X.
     *
     * Hinter der Liste steht das Verlies-Fenster mit einem eigenen roten X
     * oben rechts in der Ecke (Autors Bilder vom 12.09.). Beide sehen gleich
     * aus, das der Liste sitzt tiefer. Wer das falsche erwischt, macht das
     * ganze Verlies-Fenster zu.
     */
    private boolean listeSchliessen(Mat screen, BotContext ctx, String grund) {
        if (onCooldown(T_CLOSE_X)) return false;
        Matcher.Hit tiefstes = null;
        for (Matcher.Hit kreuz : ctx.matcher().findAll(screen, T_CLOSE_X)) {
            if (tiefstes == null || kreuz.y() > tiefstes.y()) tiefstes = kreuz;
        }
        if (tiefstes == null) return false;
        Log.action("Klick", grund + Sprache.t("  [X at ", "  [X bei ") + tiefstes.centerX() + "/" + tiefstes.centerY() + "]");
        ctx.input().click(tiefstes);
        markClicked(T_CLOSE_X);
        unknownScreens = 0;
        ctx.sleepUntilChanged(1200);
        return true;
    }

    private static long zufall(long min, long max) {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /**
     * Hat sich die Freundesliste seit dem letzten Blaettern nicht bewegt? Dann
     * ist ihr Ende erreicht, und weiteres Blaettern waere Zeitverschwendung.
     *
     * Verglichen wird ein kleines Graubild der Liste. Ein Freund, der gerade
     * online geht, aendert dabei nur einen winzigen Punkt -- das bleibt
     * unter der Schwelle.
     */
    private boolean listeZuEnde(Mat screen) {
        int x = (int) (screen.cols() * 0.12), y = (int) (screen.rows() * 0.25);
        int w = (int) (screen.cols() * 0.76), h = (int) (screen.rows() * 0.62);
        if (w <= 0 || h <= 0) return false;

        Mat ausschnitt = new Mat(screen, new org.opencv.core.Rect(x, y, w, h));
        Mat grau = new Mat();
        Mat klein = new Mat();
        try {
            org.opencv.imgproc.Imgproc.cvtColor(ausschnitt, grau,
                    org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
            org.opencv.imgproc.Imgproc.resize(grau, klein, new org.opencv.core.Size(96, 64));

            boolean gleich = false;
            if (letzteListe != null) {
                Mat unterschied = new Mat();
                org.opencv.core.Core.absdiff(letzteListe, klein, unterschied);
                gleich = org.opencv.core.Core.mean(unterschied).val[0] < 2.0;
                unterschied.release();
                letzteListe.release();
            }
            letzteListe = klein;
            klein = null;   // gehoert jetzt letzteListe
            return gleich;
        } finally {
            ausschnitt.release();
            grau.release();
            if (klein != null) klein.release();
        }
    }

    /** Das gemerkte Listenbild wegwerfen -- beim naechsten Oeffnen faengt sie oben an. */
    private void listeVergessen() {
        if (letzteListe != null) {
            letzteListe.release();
            letzteListe = null;
        }
    }

    // ------------------------------------------------------------------
    //  Opferaltar ("Altar of Sacrifice")
    // ------------------------------------------------------------------
    /** Waagerechte Mitte der beiden Opferfelder als Anteil der Fensterbreite. */
    private static final double SACRIFICE_SLOT_LEFT  = 0.286;
    private static final double SACRIFICE_SLOT_RIGHT = 0.709;
    /** Hoehe der Opferfelder als Anteil der Fensterhoehe. */
    private static final double SACRIFICE_SLOT_Y     = 0.338;

    /**
     * Zieht die festgelegte Einheit in das eigene Opferfeld.
     *
     * Welches der beiden Felder das eigene ist, haengt davon ab, ob man Leiter
     * ist. Die Zuordnung entspricht der Anordnung der Namensschilder unten:
     * der Leiter steht links.
     */
    private Outcome handleSacrifice(Mat screen, BotContext ctx, RunTracker runs) {

        // Bereits gezogen: nur noch abschliessen.
        //
        // Das merkt sich ein Schalter und keine Zeitsperre: die lief nach 4,5
        // Sekunden ab, und danach hat der Bot erneut eine Einheit gezogen,
        // statt "Finish selection" zu druecken. Der Schalter faellt erst, wenn
        // der Opferbildschirm wieder verschwunden ist.
        if (sacrificeDone) {
            if (clickAny(screen, ctx, T_READY, Sprache.t("Press Ready (sacrifice)", "Bereit melden (Opferung)"))) {
                ctx.sleepUntilChanged(1100);
                return Outcome.ACTED;
            }
            if (clickAny(screen, ctx, T_FINISH_SELECT, Sprache.t("Finish the sacrifice", "Opferung abschliessen"))) {
                runs.progress(Sprache.t("Sacrifice finished", "Opferung abgeschlossen"));
                ctx.sleepUntilChanged(2200);
                return Outcome.ACTED;
            }
            if (!onCooldown("sacrifice_finish_warn")
                    && !visibleAny(screen, ctx, T_FINISH_SELECT)) {
                warnung(Sprache.t("The unit is sacrificed, but the button "
                        + "\"Finish selection\" is not recognized. On this screen it has "
                        + "two lines and looks different than elsewhere. Please capture it "
                        + "with Capture-Images.exe as finish_selection_alt.",
                        "Die Einheit ist geopfert, aber der Knopf "
                        + "\"Finish selection\" wird nicht erkannt. Auf diesem Bildschirm "
                        + "steht er zweizeilig und sieht anders aus als sonst. Bitte mit "
                        + "Capture-Images.exe als finish_selection_alt aufnehmen."));
                cooldowns.put("sacrifice_finish_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        // Die Ueberschrift "Make a sacrifice" steht ueber ZWEI Bildschirmen:
        // zuerst der Ankuendigung mit dem Knopf "Go here", danach erst der
        // eigentlichen Opferung mit der Truppenablage. Auf der Ankuendigung gibt
        // es gar keine Einheit zu ziehen – dort muss "Go here" gedrueckt werden.
        // Ohne diesen Schritt blieb der Bot mit der Fehlermeldung unten stehen.
        if (clickAny(screen, ctx, T_GO_HERE, Sprache.t("Enter the sacrifice", "Opferung betreten"))) {
            leisteWische = 0;
            namenGesucht = false;
            runs.progress(Sprache.t("Sacrifice entered", "Opferung betreten"));
            ctx.sleepUntilChanged(1800);
            return Outcome.ACTED;
        }


        // Wen der Bot opfert, in dieser Reihenfolge -- siehe SACRIFICE_ORDER.
        //
        // Vorher wurde nur nach der Ersatzeinheit gesucht. War die selbst
        // gefallen, stand sie ausgegraut in der Ablage, die Vorlage passte
        // nicht mehr, und der Bot blieb vor dem Altar stehen, bis ihn die
        // Stillstandsuhr aus dem Verlies warf.
        Optional<Matcher.Hit> unit = Optional.empty();
        String unitName = "";
        // Autor 18.10.: "Der Bot versucht eine TOTE Ersatzeinheit in den Altar zu ziehen -- beide Ersatzeinheiten sind tot.
        // Stattdessen eine andere Einheit." -- tote Einheiten (gleiche Spalte wie ein Tot-Zeichen) auslassen.
        List<Matcher.Hit> totePlaetze = rawDeadUnits(screen, ctx);
        for (String kandidat : SACRIFICE_ORDER) {
            if (!ctx.matcher().has(kandidat)) continue;
            // Autor 18.10.: hintere Einheiten (unit_6 ... unit_1) erst, wenn weder Bild noch Name eine Opfer-Einheit fand.
            if (!istOpferBild(kandidat) && !namenGesucht) continue;
            Optional<Matcher.Hit> treffer = ctx.find(screen, kandidat);
            if (treffer.isPresent() && totePlaetze.stream().anyMatch(d -> Math.abs(d.centerX() - treffer.get().centerX()) < 45)) {
                Log.debug("Verlies", "Opfer-Kandidat " + kandidat + " ist tot -- naechster.");
                continue;
            }
            if (treffer.isPresent()) {
                unit = treffer;
                unitName = kandidat;
                break;
            }
        }

        // Autor 18.10. (drei Bilder): keine lebende Opfer-Einheit zu sehen -> die Einheiten-Leiste nach links wischen (dahinter
        // stand eine zweite Ersatz-Einheit). Dann am NAMEN suchen: "ersatz", sonst die Opfer-Einheit aus dem Team-Scan ("Magier 3"
        // -- Ruestung geaendert, Name gleich).
        // Ben 30.10. (29.10. 20:26 "No unit found"): Die Leiste ist laenger als der Bildschirm, einmal Wischen reichte nicht, und ohne
        // Treffer blieb der Bot vor dem Altar stehen. Jetzt: in jeder Stellung suchen, bis zu dreimal wischen, dann wie frueher die hinteren
        // Einheiten und zuletzt die letzte lebende Einheit der Leiste.
        if (unit.isEmpty()) {
            // Autor 18.10.: Einheiten, die "ersatz", "opfer" oder englisch "sacrifice" heissen, sind immer Opfer-Einheiten.
            List<String> namen = new ArrayList<>(List.of("ersatz", "opfer", "sacrifice"));
            namen.addAll(opferNamen());
            String opfer = MeinTeam.opfereinheit();
            if (opfer != null && !opfer.isBlank()) namen.add(opfer);
            for (String n : namen) {
                int[] stelle = einheitAmNamen(ctx, n, totePlaetze);
                if (stelle == null) continue;
                Log.action("Ziehen", Sprache.t("Unit \"" + n + "\" recognized by its name -- dragging it to the altar",
                        "Einheit \"" + n + "\" am Namen erkannt -- zum Altar ziehen"));
                return opferZiehen(screen, ctx, runs, stelle);
            }
            if (leisteWische < LEISTE_WISCHE_MAX) {
                leisteWische++;
                int y = (int) (ctx.height() * 0.61);
                Log.action("Ziehen", Sprache.t("No live sacrifice unit visible -- swiping the unit list to the left (" + leisteWische + ")",
                        "Keine lebende Opfer-Einheit zu sehen -- Einheiten-Leiste nach links wischen (" + leisteWische + ")"));
                ctx.input().drag((int) (ctx.width() * 0.80), y, (int) (ctx.width() * 0.25), y);
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            MeinTeam.neuScannenAnfordern(Sprache.t("sacrifice unit recognized neither by picture nor by name",
                    "Opfer-Einheit weder am Bild noch am Namen erkannt"));
            if (!namenGesucht) {
                namenGesucht = true;   // naechster Schritt: wie frueher eine hintere Einheit
                return Outcome.ACTED;
            }
            // Ben 30.10.: Lieber die letzte lebende Einheit der Leiste als gar keine -- ohne Opfer endet die ganze Reise.
            java.awt.image.BufferedImage bild = ctx.frame();
            if (bild != null) {
                int[] letzte = letzteLebende(schriftBloecke(bild), bild.getWidth(), totePlaetze);
                if (letzte != null) {
                    Log.warn("Verlies", Sprache.t("Sacrifice unit not found -- sacrificing the last live unit in the list instead.",
                            "Opfer-Einheit nicht gefunden -- geopfert wird stattdessen die letzte lebende Einheit der Leiste."));
                    return opferZiehen(screen, ctx, runs, letzte);
                }
            }
        }
        if (unit.isEmpty()) {
            if (!onCooldown("sacrifice_unit_warn")) {
                Log.error("Verlies", Sprache.t("No unit found for the sacrifice. Searched "
                        + "for " + String.join(", ", SACRIFICE_ORDER) + ". Please check whether "
                        + "these units are in the squad tray at all and whether the pictures "
                        + "still match.",
                        "Keine Einheit fuer die Opferung gefunden. Gesucht wurde "
                        + "nach " + String.join(", ", SACRIFICE_ORDER) + ". Bitte pruefen, ob "
                        + "diese Einheiten ueberhaupt in der Ablage stehen und ob die Vorlagen "
                        + "noch passen."));
                // Weder das Portraet (unit_sacrifice) noch der Name
                // (name_sacrifice) haben getroffen -- vermutlich hat sich der
                // Skin ODER der Name der Einheit geaendert. Ein Mensch muss die
                // Vorlagen neu aufnehmen; nur alle 10 Minuten melden.
                if (!onCooldown("sacrifice_unit_discord")) {
                    ctx.melde(de.hustlebot.core.Sprache.t("Sacrifice fighter not found", "Opfer-Einheit nicht gefunden"),
                            de.hustlebot.core.Sprache.t(
                            "Please capture the name and skin of the sacrifice fighter in the dungeon again "
                            + "(Capture-Images: \"Sacrifice fighter: face\" and \"Sacrifice fighter: name\"). "
                            + "Until then the bot sacrifices one of the rear fighters if possible.",
                            "Name und Skin der Opfer-Einheit im Verlies bitte neu aufnehmen "
                            + "(Capture-Images: „Opfer-Kaempfer: Gesicht" + "“ und "
                            + "„Opfer-Kaempfer: Name" + "“). Bis dahin opfert der Bot, "
                            + "wenn moeglich, einen der hinteren Kaempfer."));
                    cooldowns.put("sacrifice_unit_discord", System.currentTimeMillis() + 600_000);
                }
                cooldowns.put("sacrifice_unit_warn", System.currentTimeMillis() + 15_000);
            }
            return Outcome.UNKNOWN;
        }

        // "unit_sacrifice" (Portraet) und "name_sacrifice" (Name darunter)
        // meinen beide den Opfer-Kaempfer -- nur bei allem anderen wird
        // gemeldet, dass ersatzweise ein anderer geopfert wird.
        boolean istOpferKaempfer = istOpferBild(unitName);
        if (!istOpferKaempfer) {
            Log.info("Verlies", Sprache.t("The sacrifice fighter is not available (probably fallen). "
                    + "Sacrificing " + unitName + " instead.",
                    "Der Opfer-Kaempfer ist nicht verfuegbar (vermutlich gefallen). "
                    + "Geopfert wird stattdessen " + unitName + "."));
        }

        boolean leader = isLeader(screen, ctx);
        double slotX = leader ? SACRIFICE_SLOT_LEFT : SACRIFICE_SLOT_RIGHT;

        int targetX = (int) (ctx.width() * slotX);
        int targetY = (int) (ctx.height() * SACRIFICE_SLOT_Y);

        Log.info("Verlies", Sprache.t("Altar of Sacrifice. As " + (leader ? "leader" : "partner")
                + " the unit is dragged into the " + (leader ? "left" : "right")
                + " slot.",
                "Opferaltar. Als " + (leader ? "Leiter" : "Mitspieler")
                + " wird die Einheit in das " + (leader ? "linke" : "rechte")
                + " Feld gezogen."));
        // Gepackt wird am Portraet, nicht am Namensschild darunter.
        //
        // Welche der beiden Vorlagen angeschlagen hat, laesst sich an ihrer
        // Form ablesen: ein Namensschild ist breit und flach, ein Portraet
        // ungefaehr quadratisch. So funktioniert beides, ohne dass jemand
        // wissen muss, wie eine Vorlage seinerzeit aufgenommen wurde.
        Matcher.Hit treffer = unit.get();
        boolean namensschild = treffer.width() > treffer.height() * 1.6;
        int sourceX = treffer.centerX();
        int sourceY = treffer.centerY()
                    - (namensschild ? (int) (ctx.height() * 0.055) : 0);

        Log.action("Ziehen", Sprache.t("Unit " + unitName + " from " + sourceX + "," + sourceY
                + " to " + targetX + "," + targetY
                + (namensschild ? "  (recognized by the name plate)" : ""),
                "Einheit " + unitName + " von " + sourceX + "," + sourceY
                + " nach " + targetX + "," + targetY
                + (namensschild ? "  (am Namensschild erkannt)" : "")));

        ctx.input().drag(sourceX, sourceY, targetX, targetY);
        ctx.sleepUntilChanged(1400);
        // Aus dem Hauptbot (Code-Review 15.10.): Vorher galt das Opfer nach dem Ziehen blind als erledigt. Rutschte das
        // Ziehen ab, stand der Bot ewig vor dem Altar. Steht die Einheit noch an ihrem Platz: bis zu dreimal neu.
        Mat danach = ctx.refresh();
        Optional<Matcher.Hit> nochDa = ctx.find(danach, treffer.name());
        // Autor 18.10.: Nach dem Ziehen stand an fast derselben Stelle die ZWEITE Ersatz-Einheit ("ersatz") -- der Bot
        // hielt das Ziehen fuer gescheitert und zog noch zweimal. Erscheint "Ready", ist die Einheit im Altar.
        boolean bereitDa = danach != null && variantsOf(ctx, T_READY).stream()
                .anyMatch(n -> ctx.matcher().find(danach, n, 0.84).isPresent());
        boolean amPlatz = !bereitDa && nochDa.isPresent()
                && Math.abs(nochDa.get().centerX() - treffer.centerX()) < 20
                && Math.abs(nochDa.get().centerY() - treffer.centerY()) < 20;
        if (amPlatz && ++opferVersuche < 3) {
            warnung(Sprache.t("Unit still in its place after dragging -- trying again (" + opferVersuche + "/3).",
                    "Einheit steht nach dem Ziehen noch an ihrem Platz -- neuer Versuch (" + opferVersuche + "/3)."));
            return Outcome.ACTED;
        }
        opferVersuche = 0;
        sacrificeDone = true;
        runs.progress(Sprache.t("Unit sacrificed", "Einheit geopfert"));
        return Outcome.ACTED;
    }

    // ------------------------------------------------------------------
    //  Effektauswahl (Wegpunkt "Invisible Influence")
    // ------------------------------------------------------------------
    /**
     * Auf diesem Wegpunkt waehlt der Leiter einen positiven Effekt, der
     * Mitspieler einen negativen. Der Bot nimmt bevorzugt das mittlere Feld.
     * Liegt dort ein verbotener Effekt, weicht er nach links und dann nach
     * rechts aus.
     */
    private Outcome handleEffectSelection(Mat screen, BotContext ctx, RunTracker runs) {

        // Als Leiter muss zuerst der Wegpunkt betreten werden. Vorher steht im
        // Fenster "The waypoint is inactive; an effect cannot be selected" und
        // jeder Klick auf einen Effekt geht ins Leere. Also erst "Go here".
        if (clickAny(screen, ctx, T_GO_HERE, Sprache.t("Enter waypoint (effect selection)", "Wegpunkt betreten (Effektauswahl)"))) {
            runs.progress(Sprache.t("Waypoint entered", "Wegpunkt betreten"));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // Steht der Schriftzug "Effect selected" da, ist der Effekt gesetzt –
        // egal, was der Bot vorher gemacht hat. Das ist die sicherste Auskunft.
        Optional<Matcher.Hit> selected = ctx.matcher().has(T_EFFECT_DONE)
                ? ctx.find(screen, T_EFFECT_DONE)
                : Optional.empty();
        if (selected.isPresent() && !effectDone) {
            Log.info("Verlies", Sprache.t("\"Effect selected\" recognized: the effect is already set.", "\"Effect selected\" erkannt: der Effekt steht bereits."));
            effectDone = true;
        }

        // Bestaetigungsfenster ist offen: nur noch zusagen.
        if (!effectDone && click(screen, ctx, T_EFFECT_CONFIRM, Sprache.t("Confirm effect", "Effekt bestaetigen"))) {
            effectDone = true;
            runs.progress(Sprache.t("Effect chosen", "Effekt gewaehlt"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }

        // Effekt steht: bereit melden und abschliessen. Genau diese Reihenfolge –
        // erst "Ready", dann "Finish selection".
        if (effectDone) {
            if (clickAny(screen, ctx, T_READY, Sprache.t("Press Ready (effect selection)", "Bereit melden (Effektauswahl)"))) {
                effectPopupClicks = 0;
                ctx.sleepUntilChanged(1100);
                return Outcome.ACTED;
            }
            if (clickAny(screen, ctx, T_FINISH_SELECT, Sprache.t("Finish the effect selection", "Effektauswahl abschliessen"))) {
                runs.progress(Sprache.t("Effect selection finished", "Effektauswahl abgeschlossen"));
                effectPopupClicks = 0;
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }

            // Beides nicht erreichbar: meist liegt noch das kleine Fenster mit
            // "Effect selected" darueber und verdeckt den "Ready"-Knopf. Einmal
            // darauf klicken schliesst es.
            if (selected.isPresent() && effectPopupClicks < 3
                    && !onCooldown("effect_popup_close")) {
                effectPopupClicks++;
                Log.action("Klick", Sprache.t("Close the effect pop-up (\"Effect selected\" at "
                        + selected.get().centerX() + "," + selected.get().centerY() + ")",
                        "Effektfenster schliessen (\"Effect selected\" bei "
                        + selected.get().centerX() + "," + selected.get().centerY() + ")"));
                ctx.input().click(selected.get().centerX(), selected.get().centerY(), 6);
                cooldowns.put("effect_popup_close", System.currentTimeMillis() + 1500);
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }

            if (!onCooldown("effect_ready_warn")) {
                warnung(Sprache.t("The effect is set, but neither \"Ready\" nor "
                        + "\"Finish selection\" is recognized. Please capture the green \"Ready\" button "
                        + "on this screen with Capture-Images.exe as ready_effect -- "
                        + "and, if not done yet, the words \"Effect selected\" "
                        + "as effect_selected.",
                        "Der Effekt steht, aber weder \"Ready\" noch "
                        + "\"Finish selection\" wird erkannt. Bitte den gruenen \"Ready\"-Knopf "
                        + "auf diesem Bildschirm mit Capture-Images.exe als ready_effect aufnehmen – "
                        + "und, falls noch nicht geschehen, den Schriftzug \"Effect selected\" "
                        + "als effect_selected."));
                cooldowns.put("effect_ready_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        // Welche Dreiergruppe gehoert mir? Am Ready-Knopf abgelesen, nicht
        // an der Rolle geraten -- der Knopf steht unter der eigenen Gruppe.
        boolean meineLinks = meineSeiteIstLinks(screen, ctx, isLeader(screen, ctx));
        double[] positionen = meineLinks ? EFFECT_X_POSITIVE : EFFECT_X_NEGATIVE;
        // Autor 18.10. (Foto 22:18:22): rechts stand bei IHM "Select a positive effect" mit gruenen Karten -- der Bot
        // nahm "rechts = negativ" an und waehlte nach der Negativ-Reihenfolge den Krieger. Ob die eigenen Karten positiv
        // sind, entscheidet jetzt ihre Farbe (gruen = positiv, rot = negativ); nur ohne klares Ergebnis die Seite.
        double gruen = gruenAnteil(screen, ctx, positionen);
        boolean meinePositiven = Math.abs(gruen) > 8 ? gruen > 0 : meineLinks;

        Log.info("Verlies", Sprache.t("Effect selection: my cards are on the " + (meineLinks ? "left" : "right") + " and "
                + (gruen > 8 ? "green" : gruen < -8 ? "red" : "unclear") + " -- choosing a "
                + (meinePositiven ? "positive" : "negative") + " effect.",
                "Effektauswahl: meine Karten sind " + (meineLinks ? "links" : "rechts") + " und "
                + (gruen > 8 ? "gruen" : gruen < -8 ? "rot" : "unklar") + " -- es wird ein "
                + (meinePositiven ? "positiver" : "negativer") + " Effekt gewaehlt."));

        int slot = pickEffectSlot(screen, ctx, meinePositiven, positionen);
        if (slot < 0) {
            Log.error("Verlies", Sprache.t("All three effect slots are marked as banned. "
                    + "Please remove one of the pictures effect_ban_*.",
                    "Alle drei Effektfelder sind als verboten hinterlegt. "
                    + "Bitte eine der Vorlagen effect_ban_* entfernen."));
            return Outcome.FATAL;
        }

        int x = effectSlotX(ctx.width(), positionen, slot);
        int y = (int) (ctx.height() * EFFECT_ROW_Y);

        Log.action("Klick", Sprache.t("Effect slot " + slotName(slot) + " at " + x + "," + y,
                "Effektfeld " + slotName(slot) + " bei " + x + "," + y));
        ctx.input().click(x, y, 8);
        markClicked("effect_pick");
        ctx.sleepUntilChanged(1200);

        // MITZAEHLEN, WIE OFT HIER SCHON GEKLICKT WURDE.
        //
        // HIER LAG DER ZWEITE FEHLER VOM 02.09., UND ER IST DER GEFAEHRLICHERE:
        // Der Klick meldete jedes Mal "Fortschritt". Damit wurde die
        // Untaetigkeitsuhr bei jedem Durchgang zurueckgesetzt -- der Bot
        // konnte sich endlos im Kreis drehen, ohne dass der Waechter je
        // anschlug. Im Protokoll standen 223 gleiche Durchgaenge, und
        // aufgehoert hat er nur, weil Autor !stop getippt hat. Sonst haette
        // er das bis zum Morgen gemacht.
        //
        // Fortschritt gilt jetzt nur noch als solcher, wenn der Bildschirm
        // hinterher weiterspringt -- also beim naechsten Schritt. Bleibt er
        // stehen, laeuft der Zaehler hoch und der Bot geht raus.
        effectClicks++;
        if (effectClicks >= EFFECT_MAX_KLICKS) {
            Log.alarm("Verlies", Sprache.t("The effect selection is stuck: clicked " + effectClicks
                    + " times without anything happening. The leader probably has not "
                    + "chosen a waypoint. The bot leaves the Dungeon and "
                    + "looks for a new partner.",
                    "Die Effektauswahl haengt: " + effectClicks
                    + " Mal geklickt, ohne dass sich etwas tut. Vermutlich hat der Leiter "
                    + "keinen Stuetzpunkt gewaehlt. Der Bot verlaesst das Verlies und "
                    + "sucht einen neuen Mitspieler."));
            effectClicks = 0;
            beginAbort(Sprache.t("Effect selection stuck after " + EFFECT_MAX_KLICKS + " attempts",
                    "Effektauswahl haengt nach " + EFFECT_MAX_KLICKS + " Versuchen"));
            return Outcome.ACTED;
        }

        return Outcome.ACTED;
    }

    /**
     * Wie oft auf ein Effektfeld geklickt werden darf, bevor der Bot aufgibt.
     *
     * Acht ist Autors Wahl. Normal genuegt ein Klick; zwei oder drei kommen
     * vor, wenn das Fenster gerade noch aufgeht. Acht heisst: hier tut sich
     * nichts mehr.
     */
    private static final int EFFECT_MAX_KLICKS = 8;

    /** Klicks auf ein Effektfeld, seit sich zuletzt etwas getan hat. */
    private int effectClicks = 0;

    /**
     * Waehlt ein Effektfeld aus -- nach Klasse, wenn erkennbar.
     *
     * Autors Reihenfolge:
     *
     *   positive Effekte (als Leiter):     Magier, Bogenschuetze, Krieger
     *   negative Effekte (als Mitspieler): Krieger, Bogenschuetze, Magier
     *
     * Der Gedanke dahinter ist derselbe von beiden Seiten aus: Die eigenen
     * Magier sollen staerker werden, die gegnerischen Krieger schwaecher.
     *
     * Verbotene Effekte (effect_ban_*) haben Vorrang vor allem. Ein
     * gesperrtes Feld wird nie genommen, auch wenn es das einzige mit der
     * gewuenschten Klasse waere -- dann geht es eben zur naechsten Klasse.
     *
     * Wird auf keinem Feld ein Klassenzeichen erkannt, faellt der Bot auf das
     * alte Verhalten zurueck: Mitte, links, rechts. Er bleibt also nie stehen,
     * nur weil die Vorlagen fehlen.
     */
    private int pickEffectSlot(Mat screen, BotContext ctx, boolean leader, double[] positionen) {
        List<Matcher.Hit> banned = new ArrayList<>();
        for (String template : ctx.matcher().names()) {
            if (!template.startsWith(T_EFFECT_BAN_PREFIX)) continue;
            banned.addAll(ctx.matcher().findAll(screen, template, 0.82, 6));
        }
        if (!banned.isEmpty()) {
            Log.debug("Verlies", banned.size() + " verbotene Effekte auf dem Bildschirm gefunden.");
        }

        int y = (int) (ctx.height() * EFFECT_ROW_Y);
        int tolerance = Math.max(30, ctx.width() / 24);

        // Welche Klasse steht auf welchem Feld? -1 heisst: unbekannt.
        int[] klasse = new int[3];
        boolean etwasErkannt = false;
        for (int slot = 0; slot < 3; slot++) {
            klasse[slot] = klasseAmFeld(screen, ctx, effectSlotX(ctx.width(), positionen, slot),
                                        y, tolerance);
            if (klasse[slot] >= 0) etwasErkannt = true;
        }

        // Wunschreihenfolge der Klassen. Die Zahlen stehen fuer die Vorlagen
        // in EFFECT_CLASSES: 0 = Magier, 1 = Bogenschuetze, 2 = Krieger.
        int[] wunsch = leader ? new int[]{0, 1, 2}    // positiv: Magier zuerst
                              : new int[]{2, 1, 0};   // negativ: Krieger zuerst

        // Reihenfolge der Felder aufbauen: erst die beiden Wunschklassen, dann Felder ohne erkannte Klasse
        // (Mitte, links, rechts), ganz zuletzt die unerwuenschte Klasse. 15.10.: Vorher kamen unerkannte
        // Felder ganz hinten -- war nur der Krieger erkannt, nahm der Bot als Leiter den Tank-Effekt, obwohl
        // auf den anderen Feldern Magier oder Bogen stehen konnten (Autor: "immer Magier positive Effekte
        // zuerst, dann Archer und dann erst Tank").
        List<Integer> reihenfolge = new ArrayList<>();
        if (etwasErkannt) {
            for (int i = 0; i < wunsch.length - 1; i++) {
                for (int slot = 0; slot < 3; slot++) {
                    if (klasse[slot] == wunsch[i] && !reihenfolge.contains(slot)) reihenfolge.add(slot);
                }
            }
        }
        for (int slot : new int[]{1, 0, 2}) {
            if (klasse[slot] < 0 && !reihenfolge.contains(slot)) reihenfolge.add(slot);
        }
        for (int slot : new int[]{1, 0, 2}) {
            if (!reihenfolge.contains(slot)) reihenfolge.add(slot);
        }

        for (int slot : reihenfolge) {
            int x = effectSlotX(ctx.width(), positionen, slot);
            boolean blocked = false;

            for (Matcher.Hit hit : banned) {
                if (Math.abs(hit.centerX() - x) < tolerance
                        && Math.abs(hit.centerY() - y) < tolerance) {
                    Log.info("Verlies", Sprache.t("Slot " + slotName(slot) + " is banned ("
                            + hit.name() + "), skipping it.",
                            "Feld " + slotName(slot) + " ist verboten ("
                            + hit.name() + "), wird uebersprungen."));
                    blocked = true;
                    break;
                }
            }
            if (blocked) continue;

            if (klasse[slot] >= 0) {
                Log.info("Verlies", Sprache.t("Slot " + slotName(slot) + " is a "
                        + EFFECT_CLASS_NAMES_EN[klasse[slot]] + " effect -- fits the "
                        + (leader ? "positive" : "negative") + " order.",
                        "Feld " + slotName(slot) + " ist ein "
                        + EFFECT_CLASS_NAMES[klasse[slot]] + "-Effekt -- passt zur "
                        + (leader ? "positiven" : "negativen") + " Reihenfolge."));
            }
            return slot;
        }
        return -1;
    }

    /** Vorlagen der drei Klassenzeichen. Reihenfolge: Magier, Bogen, Krieger. */
    private static final String[] EFFECT_CLASSES = {
            "effect_magier", "effect_bogen", "effect_krieger"
    };

    /** Nur fuer lesbare Meldungen. */
    private static final String[] EFFECT_CLASS_NAMES = {
            "Magier", "Bogenschuetzen", "Krieger"
    };
    /** Dasselbe auf Englisch (Autor 11.10.: alles zweisprachig). */
    private static final String[] EFFECT_CLASS_NAMES_EN = {
            "mage", "archer", "warrior"
    };

    /**
     * Welche Klasse steht auf dem Feld an dieser Stelle? -1 heisst: unbekannt.
     *
     * Gesucht wird in einem Umkreis um die Feldmitte. Die Zeichen sitzen nicht
     * exakt mittig auf der Karte, deshalb wird der Suchbereich grosszuegig
     * gewaehlt -- aber nicht so gross, dass er ins Nachbarfeld reicht.
     */
    private int klasseAmFeld(Mat screen, BotContext ctx, int x, int y, int tolerance) {
        int umkreis = tolerance * 2;

        for (int k = 0; k < EFFECT_CLASSES.length; k++) {
            String name = EFFECT_CLASSES[k];
            if (!ctx.matcher().has(name)) continue;

            for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, 0.82, 6)) {
                if (Math.abs(hit.centerX() - x) < umkreis
                        && Math.abs(hit.centerY() - y) < umkreis * 2) {
                    return k;
                }
            }
        }
        return -1;
    }

    /** Hoehe der Effektreihe als Anteil der Fensterhoehe. */
    private static final double EFFECT_ROW_Y = 0.385;

    /** Waagerechte Mitte eines Effektfeldes als Anteil der Fensterbreite. */
    private static final double[] EFFECT_X_POSITIVE = {0.164, 0.286, 0.408};
    private static final double[] EFFECT_X_NEGATIVE = {0.590, 0.711, 0.833};

    private static int effectSlotX(int width, double[] positions, int slot) {
        return (int) (width * positions[slot]);
    }

    // ------------------------------------------------------------------
    //  Welche Dreiergruppe ist positiv, welche negativ?
    // ------------------------------------------------------------------
    /**
     * Auf dem Effektbildschirm liegen SECHS Karten: drei positive und drei
     * negative. Der Leiter waehlt immer aus den positiven, der Mitspieler
     * immer aus den negativen -- so hat Autor es bestaetigt.
     *
     * Bisher stand fest verdrahtet im Code, dass die positiven links liegen
     * und die negativen rechts. Wenn das einmal nicht stimmt, greift der Bot
     * in die falsche Gruppe -- und genau das hat Autor beobachtet: als
     * Mitspieler nahm der Bot einen positiven Krieger-Effekt.
     *
     * Jetzt wird es GEMESSEN statt angenommen. Positive Karten haben einen
     * gruenen Hintergrund, negative einen roten. Der Bot sieht sich beide
     * Seiten an und entscheidet danach.
     *
     * Ist die Messung nicht eindeutig -- etwa weil ein Fenster darueber
     * liegt --, bleibt es bei der alten Annahme. Lieber wie bisher als gar
     * nicht.
     */
    private double[] feldPositionen(Mat screen, BotContext ctx, boolean positivGesucht) {

        // DIE SEITEN STEHEN FEST -- von Autor an einem Bildschirmfoto
        // bestaetigt: links "Select a positive effect" mit drei GRUENEN
        // Karten, rechts "Your partner is choosing a debuff" mit drei ROTEN.
        // Immer so herum.
        //
        // HIER STAND EINMAL EINE FARBMESSUNG, DIE DAS UMDREHEN KONNTE, UND
        // SIE HAT DEN BOT ZUM STEHEN GEBRACHT: Am 02.09. meldete sie 223 Mal
        // "die positiven liegen RECHTS" und schickte den Bot als Mitspieler
        // auf die LINKE Seite -- die Karten des Leiters, die er gar nicht
        // anklicken kann. Nichts passierte, und weil jeder Klick als
        // Fortschritt zaehlte, lief das endlos weiter, bis Autor !stop
        // tippte.
        //
        // Die Messung sollte ein Problem loesen, das es nie gab. Sie ist
        // raus.
        //
        // Was WIRKLICH wackelte, ist die Rolle: Wer bin ich hier, Leiter
        // oder Mitspieler? Dafuer gibt es jetzt ein verlaessliches Zeichen --
        // siehe meineSeiteIstLinks().
        return positivGesucht ? EFFECT_X_POSITIVE : EFFECT_X_NEGATIVE;
    }

    /**
     * Auf welcher Seite stehen die Karten, die ICH auswaehlen darf?
     *
     * Der gruene "Ready"-Knopf sitzt immer unter der eigenen Dreiergruppe.
     * Das ist ein viel besseres Zeichen als isLeader(): Das erkennt man am
     * eigenen Namensschild, und genau das hat in einer einzigen Sitzung
     * schon 842 Mal gefehlt. Liegt isLeader() daneben, greift der Bot in die
     * Gruppe des anderen -- und genau das hatte Autor gemeldet: "als
     * Mitspieler nimmt er positive Krieger-Effekte".
     *
     * Ist der Knopf nicht zu sehen, bleibt es bei isLeader().
     *
     * @return true, wenn meine Karten links liegen (also die positiven)
     */
    private boolean meineSeiteIstLinks(Mat screen, BotContext ctx, boolean leader) {
        for (String name : variantsOf(ctx, T_READY)) {
            var treffer = ctx.matcher().find(screen, name, 0.84);
            if (treffer.isEmpty()) continue;

            boolean links = treffer.get().centerX() < ctx.width() / 2;
            Log.info("Verlies", Sprache.t("The Ready button is on the " + (links ? "left" : "right")
                    + " -- so my effects are the " + (links ? "positive" : "negative")
                    + " ones.",
                    "Der Ready-Knopf steht " + (links ? "links" : "rechts")
                    + " -- meine Effekte sind also die " + (links ? "positiven" : "negativen")
                    + "."));
            return links;
        }

        Log.debug("Verlies", "Kein Ready-Knopf zu sehen. Die Seite wird aus der Rolle "
                + "abgeleitet (" + (leader ? "Leiter" : "Mitspieler") + ").");
        return leader;
    }

    /**
     * Wie viel gruener als rot ist die Gruppe an diesen Stellen?
     *
     * Positiver Wert heisst gruen, negativer rot. Gemessen wird der
     * Mittelwert ueber kleine Felder an allen drei Stellen -- so faellt ein
     * einzelnes Symbol auf der Karte nicht ins Gewicht.
     */
    private double gruenAnteil(Mat screen, BotContext ctx, double[] positionen) {
        int y = (int) (ctx.height() * EFFECT_ROW_Y);
        int halb = Math.max(6, ctx.width() / 90);

        double summe = 0;
        int gezaehlt = 0;

        for (double anteil : positionen) {
            int x = (int) (ctx.width() * anteil);
            for (int dy = -halb; dy <= halb; dy += 3) {
                for (int dx = -halb; dx <= halb; dx += 3) {
                    int px = x + dx, py = y + dy;
                    if (px < 0 || py < 0 || px >= screen.cols() || py >= screen.rows()) continue;
                    double[] bgr = screen.get(py, px);
                    if (bgr == null || bgr.length < 3) continue;
                    // OpenCV liefert Blau, Gruen, Rot -- in dieser Reihenfolge.
                    summe += bgr[1] - bgr[2];
                    gezaehlt++;
                }
            }
        }
        return gezaehlt == 0 ? 0 : summe / gezaehlt;
    }

    /** Ab welchem Abstand zwischen Gruen und Rot die Farbe als eindeutig gilt. */
    private static final double FARB_SCHWELLE = 12.0;

    private static String farbwort(double wert) {
        if (wert >  FARB_SCHWELLE) return "gruen";
        if (wert < -FARB_SCHWELLE) return "rot";
        return "unklar";
    }

    private static String slotName(int slot) {
        return switch (slot) {
            case 0 -> Sprache.t("left", "links");
            case 1 -> Sprache.t("middle", "Mitte");
            default -> Sprache.t("right", "rechts");
        };
    }

    // ------------------------------------------------------------------
    //  Freundesliste
    // ------------------------------------------------------------------
    /**
     * Freundesliste: JEDEN gewuenschten Freund der Reihe nach einladen, der
     * online ist (gruener Punkt). Pro Aufruf eine Handlung -- einen einladen,
     * oder blaettern, oder die Liste schliessen.
     *
     * Autor, 16.09.: "er sieht ob der erste spieler online ist ... drueckt
     * dann auf invite und dann auf send, dann geht er weiter zu dem naechsten
     * spieler und macht da das gleiche." Die Liste bleibt beim Einladen offen
     * (Autors Bilder vom 12.09.), so lassen sich mehrere einladen. Erst wenn
     * alle durch sind, wird sie geschlossen -- und wer zuerst annimmt, spielt
     * mit. Ist keiner online, geht es mit Zufallsspielern weiter.
     */
    /**
     * Steht "Invited" mit Haken in der Zeile des eben eingeladenen Freundes? Dann zaehlt er als eingeladen (null =
     * weiter wie gewohnt). Sonst bis 2,5 s warten, danach noch einmal einladen (hoechstens 3 Versuche).
     */
    private Outcome einladungPruefen(Mat screen, BotContext ctx) {
        String name = einladungOffen;
        long jetzt = System.currentTimeMillis();
        if (einladungListeSeit == 0) einladungListeSeit = jetzt;   // Liste wieder da (nach "Send")
        String vorlage = freundVorlage(name);
        Optional<Matcher.Hit> f = ctx.matcher().has(vorlage) ? ctx.find(screen, vorlage) : Optional.empty();
        boolean bestaetigt = f.isPresent() && ctx.matcher().findAll(screen, T_FRIEND_INVITED, 0.85, 6).stream()
                .anyMatch(h -> Math.abs(h.centerY() - f.get().centerY()) <= f.get().height() * 2 && h.centerX() > f.get().centerX());
        if (bestaetigt) {
            Log.info("Verlies", Sprache.t(name + ": \"Invited\" with the check mark is there -- the invite arrived.",
                    name + ": \"Invited\" mit Haken steht da -- die Einladung ist angekommen."));
            eingeladeneRunde.add(name);
            einladungOffen = null;
            einladungVersuche = 0;
            return null;
        }
        if (jetzt - einladungListeSeit < 2500) {
            ctx.sleep(200);
            return Outcome.WAITING;
        }
        einladungOffen = null;
        if (++einladungVersuche < 3) {
            Log.warn("Verlies", Sprache.t("No \"Invited\" with the check mark next to " + name + " -- the tap missed. Inviting again ("
                            + (einladungVersuche + 1) + ". try).",
                    "Kein \"Invited\" mit Haken bei " + name + " -- der Tipp ging daneben. Noch einmal einladen ("
                            + (einladungVersuche + 1) + ". Versuch)."));
            gesehenRunde.remove(name);
            return Outcome.ACTED;
        }
        Log.warn("Verlies", Sprache.t("After 3 tries still no \"Invited\" next to " + name + " -- this friend is left out this time.",
                "Nach 3 Versuchen noch kein \"Invited\" bei " + name + " -- dieser Freund wird diesmal ausgelassen."));
        einladungVersuche = 0;
        return null;
    }

    private Outcome handleFriendList(Mat screen, BotContext ctx, RunTracker runs) {
        if (freundeListe.isEmpty()) {
            Log.error("Verlies", Sprache.t("No friend's name is set. Please enter a name in the "
                    + "control panel or switch to a random partner.",
                    "Es ist kein Freundesname eingetragen. Bitte im Bedienfeld "
                    + "einen Namen angeben oder auf zufaelligen Mitspieler umstellen."));
            return Outcome.FATAL;
        }

        List<Matcher.Hit> invites = ctx.matcher().findAll(screen, T_FRIEND_INVITE, 0.82, 15);

        // Eine Einladung wartet auf "Invited" mit Haken (Autor 22.10.) -- erst dann geht es weiter (und die Liste zu).
        if (einladungOffen != null) {
            Outcome warten = einladungPruefen(screen, ctx);
            if (warten != null) return warten;
        }

        // Den naechsten noch nicht abgehandelten Freund suchen, der in dieser
        // Ansicht steht, und ihn einladen -- wenn er online ist.
        for (String name : freundeListe) {
            if (gesehenRunde.contains(name)) continue;

            String vorlage = freundVorlage(name);
            if (!ctx.matcher().has(vorlage)) {
                Log.warn("Verlies", Sprache.t("The picture " + vorlage + ".png for " + name
                        + " is missing -- this friend is skipped. Capture it with Capture-Images.exe.",
                        "Fuer " + name + " fehlt das Bild " + vorlage
                        + ".png -- dieser Freund wird uebersprungen. Mit Capture-Images.exe aufnehmen."));
                gesehenRunde.add(name);
                continue;
            }

            Optional<Matcher.Hit> gefunden = ctx.find(screen, vorlage);
            if (gefunden.isEmpty()) continue;   // steht (noch) nicht im Bild -- evtl. weiter unten
            Matcher.Hit f = gefunden.get();

            // Der Einladen-Knopf in derselben Zeile.
            Matcher.Hit best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Matcher.Hit invite : invites) {
                int distance = Math.abs(invite.centerY() - f.centerY());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = invite;
                }
            }
            if (best == null || bestDistance > f.height() * 2) {
                Log.warn("Verlies", Sprache.t(name + " found, but no Invite button in the row. "
                        + "Skipping.",
                        name + " gefunden, aber kein Einladen-Knopf in der Zeile. "
                        + "Wird uebersprungen."));
                gesehenRunde.add(name);
                continue;
            }

            friendScrolls = 0;
            listeVergessen();

            // Offline? Dann nicht einladen -- den naechsten pruefen.
            if (!istOnline(screen, ctx, f, best, invites)) {
                Log.info("Verlies", Sprache.t(name + " is not online right now (no green dot) -- "
                        + "not inviting.",
                        name + " ist gerade nicht online (kein gruener Punkt) -- "
                        + "wird nicht eingeladen."));
                gesehenRunde.add(name);
                continue;
            }

            // Online: einladen. "Send" drueckt gleich Schritt 10b.
            Log.action("Klick", Sprache.t("Invite " + name + "  [row at y=" + best.centerY() + "]",
                    name + " einladen  [Zeile bei y=" + best.centerY() + "]"));
            ctx.input().click(best);
            gesehenRunde.add(name);
            if (ctx.matcher().has(T_FRIEND_INVITED)) {
                // Erst als eingeladen zaehlen, wenn "Invited" mit Haken in seiner Zeile steht (einladungPruefen).
                einladungOffen = name;
                einladungGeklicktUm = System.currentTimeMillis();
                einladungListeSeit = 0;
            } else {
                eingeladeneRunde.add(name);
            }
            sendeAusstehend = true;
            runs.progress(Sprache.t(name + " invited", name + " eingeladen"));
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }

        // In dieser Ansicht ist niemand mehr einzuladen. Stehen noch Freunde
        // aus, die weiter unten sein koennten? Dann blaettern.
        boolean alleGesehen = true;
        for (String name : freundeListe) {
            if (!gesehenRunde.contains(name)) { alleGesehen = false; break; }
        }
        if (!alleGesehen) {
            boolean amEnde = friendScrolls > 0 && listeZuEnde(screen);
            if (friendScrolls == 0) listeZuEnde(screen);   // Ausgangsbild merken
            if (!amEnde && friendScrolls < MAX_FRIEND_SCROLLS) {
                int x = ctx.width() / 2;
                friendScrolls++;
                Log.action("Liste", Sprache.t("Scrolling the friend list further (swipe "
                        + friendScrolls + " of " + MAX_FRIEND_SCROLLS + ").",
                        "Freundesliste wird weitergeblaettert (Zug "
                        + friendScrolls + " von " + MAX_FRIEND_SCROLLS + ")."));
                ctx.input().drag(x, (int) (ctx.height() * 0.72), x, (int) (ctx.height() * 0.38));
                ctx.sleepUntilChanged(1100);
                // Autor 22.10.: der Klick auf "Invite" lag zu tief -- die Liste rollte nach dem Wischen noch nach.
                // Erst warten, bis sie steht, dann im naechsten Bild die Zeile suchen.
                ctx.sleep(700);
                return Outcome.ACTED;
            }
            // Ende der Liste: Wer bis hier nicht auftauchte, steht nicht drin
            // (nicht online oder kein Freund).
            for (String name : freundeListe) {
                if (!gesehenRunde.contains(name)) {
                    Log.info("Verlies", Sprache.t(name + " is not in the friend list (not online "
                            + "or not a friend).",
                            name + " steht nicht in der Freundesliste (nicht online "
                            + "oder kein Freund)."));
                    gesehenRunde.add(name);
                }
            }
        }

        friendScrolls = 0;
        listeVergessen();

        // Runde durch. Wurde jemand eingeladen? Dann Liste zu -> Wartebildschirm.
        if (!eingeladeneRunde.isEmpty()) {
            long jetzt = System.currentTimeMillis();
            freundEingeladenUm = jetzt;
            freundWartebildGesehen = false;
            freundWartenBis = jetzt + zufall(FREUND_WARTEN_MIN_MS, FREUND_WARTEN_MAX_MS);
            naechsterKreuzungsBlick = jetzt + zufall(KREUZUNG_MIN_MS, KREUZUNG_MAX_MS);
            Log.info("Verlies", Sprache.t(eingeladeneRunde.size() + " friend(s) invited: "
                    + String.join(", ", eingeladeneRunde) + ". Whoever accepts first plays along.",
                    eingeladeneRunde.size() + " Freund(e) eingeladen: "
                    + String.join(", ", eingeladeneRunde) + ". Wer zuerst annimmt, spielt mit."));
            return listeSchliessen(screen, ctx, Sprache.t("Close friend list (invited: "
                    + String.join(", ", eingeladeneRunde) + ")",
                    "Freundesliste schliessen (eingeladen: "
                    + String.join(", ", eingeladeneRunde) + ")"))
                    ? Outcome.ACTED : Outcome.WAITING;
        }

        // Keiner aus der Liste online -> Zufallsspieler fuer den Rest.
        freundWeg(Sprache.t("Nobody from your list (" + friendName + ") is online right now.",
                "Keiner aus deiner Liste (" + friendName + ") ist gerade online."));
        listeSchliessen(screen, ctx, Sprache.t("Close friend list (nobody online)", "Freundesliste schliessen (keiner online)"));
        return Outcome.ACTED;
    }

    /** Eine neue Einladungsrunde in der Freundesliste beginnt: alle Merker leeren. */
    private void neueEinladungsrunde() {
        gesehenRunde.clear();
        eingeladeneRunde.clear();
        sendeAusstehend = false;
    }

    // ------------------------------------------------------------------
    //  Mit einem Clan-Mitglied (18.11., siehe VerliesClan)
    // ------------------------------------------------------------------

    /**
     * Aus dem Mitspieler-Wort des Starts den Weg machen: "friend"/"friendwait" = Freund, "clan" = Clan-Mitglied,
     * alles andere (auch leer) = zufaellig. Wie bisher in BotEngine.start, jetzt mit "clan".
     */
    public static Teammate teammateAus(String wort) {
        if (wort == null) return Teammate.RANDOM;
        String w = wort.trim().toLowerCase(java.util.Locale.ROOT);
        if (w.startsWith("friend")) return Teammate.FRIEND;
        if (w.equals("clan")) return Teammate.CLAN;
        return Teammate.RANDOM;
    }

    @Override
    public String endeHinweis() {
        String h = clanHinweis;
        clanHinweis = null;
        return h;
    }

    /** Wie viele Runden noch fehlen -- fuer die Texte ("1 round" / "3 rounds"). */
    private static int clanRestRunden(RunTracker runs) {
        return Math.max(1, runs.targetRuns() - runs.completedRuns());
    }

    /** Aufgeben: Hinweis merken, Fenster zu, dann FINISHED (siehe clanVorab). */
    private void clanAufgeben(String hinweisEn, String hinweisDe) {
        clanAufgegeben = true;
        clanHinweis = Sprache.t(hinweisEn, hinweisDe);
        Log.info("Verlies", clanHinweis);
    }

    /**
     * Schritt 1d: aufgegeben -> Verlies-Fenster schliessen und den Modus beenden. Null = normal weiter.
     */
    private Outcome clanVorab(Mat screen, BotContext ctx, RunTracker runs) {
        if (clanAufgegeben) {
            if (!clanFensterZu && !visibleAny(screen, ctx, T_MAP_BUTTON)) {
                clanFensterZu = true;
                if (click(screen, ctx, T_CLOSE_X, Sprache.t("Close the Dungeon window (no clan mate)",
                        "Verlies-Fenster schliessen (kein Clan-Mitglied)"))) {
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
            }
            insideDungeon = false;
            Log.info("Verlies", Sprache.t("Clan mate quest: stopping for now.",
                    "Clan-Quest: fuers Erste Schluss."));
            return Outcome.FINISHED;
        }
        // Das "Thanks" kommt jetzt auf der Verlieskarte beim vorletzten Stuetzpunkt (clanAufKarte, Autor 19.11.).
        return null;
    }

    /** In der Burg: einmal im Clan-Chat fragen. Niemand online -> aufhoeren (nicht warten). */
    private Outcome clanChatFragen(BotContext ctx, RunTracker runs) {
        clanChatErledigt = true;
        clanTexte = VerliesClan.eigeneTexte(clanRestRunden(runs));
        clanPlan = neuerClanPlan();
        VerliesClan.ChatErgebnis e = VerliesClan.clanChatSchreiben(ctx, clanRestRunden(runs));
        Log.info("Verlies", "Clan-Chat: " + e);
        clanPlan.gefragt();
        if (e == VerliesClan.ChatErgebnis.NIEMAND_ONLINE) {
            clanPlan.onlineGelesen(0);
            clanAufgeben("No clan mate online (0 online in the clan chat) -- the quest \"Go to a Dungeon with a clan mate\" "
                    + "is tried again later.",
                    "Kein Clan-Mitglied online (0 online im Clan-Chat) -- die Quest \"Go to a Dungeon with a clan mate\" "
                    + "spaeter nochmal.");
            clanFensterZu = true;   // wir stehen in der Burg, es gibt nichts zu schliessen
            return Outcome.FINISHED;
        }
        runs.progress(Sprache.t("Clan chat: asked for a partner", "Clan-Chat: nach Mitspieler gefragt"));
        return Outcome.ACTED;
    }

    /** Mitspielerwahl im Clan-Weg: "Clan", Nachricht, "Send". Null = hier ist nicht die Mitspielerwahl. */
    private Outcome clanEinladen(Mat screen, BotContext ctx, RunTracker runs) {
        boolean wahl = (ctx.matcher().has(VerliesClan.T_CLAN) && ctx.visible(screen, VerliesClan.T_CLAN))
                || ctx.visible(screen, T_RANDOM);
        if (!wahl) return null;
        long jetzt = System.currentTimeMillis();
        if (clanPlan.aufgegeben()) {
            clanAufgeben("Clan mate quest given up (" + clanPlan.grund() + ") -- tried again later.",
                    "Clan-Quest aufgegeben (" + clanPlan.grund() + ") -- spaeter nochmal.");
            return Outcome.ACTED;
        }
        if (clanEingeladenUm > 0 && jetzt - clanEingeladenUm < 20_000) return Outcome.WAITING;   // Fenster wechselt noch
        if (clanEinladungen >= CLAN_EINLADUNGEN_MAX) {
            clanAufgeben("The clan invite did not go out " + CLAN_EINLADUNGEN_MAX + " times -- the quest "
                    + "\"Go to a Dungeon with a clan mate\" is tried again later.",
                    "Die Clan-Einladung ging " + CLAN_EINLADUNGEN_MAX + " Mal nicht hinaus -- die Quest "
                    + "\"Go to a Dungeon with a clan mate\" spaeter nochmal.");
            return Outcome.ACTED;
        }
        clanEinladungen++;
        clanEingeladenUm = jetzt;
        if (VerliesClan.imVerliesEinladen(screen, ctx, clanRestRunden(runs), T_INVITE_SEND)) {
            clanPlan.eingeladen(System.currentTimeMillis());
            runs.progress(Sprache.t("Clan mate invited", "Clan-Mitglied eingeladen"));
        }
        return Outcome.ACTED;
    }

    /**
     * "Waiting for a clan mate" (Autor 19.11.): bis zu 10 Minuten im Wartefenster bleiben, ab und zu (sofort bei rotem
     * Abzeichen am Chat-Pfeil) den Clan-Chat lesen. "in 5 min" -> nach 5 Minuten neu einladen; "can't", niemand online
     * oder Zeit um -> Cancel und aufgeben (spaeter noch einmal).
     */
    private Outcome aufClanWarten(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        ClanVerliesPlan.Schritt s = clanPlan.imWartefenster(jetzt, VerliesClan.abzeichenAmChatPfeil(ctx.frame()));
        switch (s) {
            case WARTEN -> {
                if (!onCooldown("clan_warten_log")) {
                    long rest = Math.max(0, clanPlan.wartenBis() - jetzt) / 1000;
                    Log.info("Verlies", Sprache.t("Waiting for a clan mate, " + rest + " s left.",
                            "Warte auf ein Clan-Mitglied, noch " + rest + " s."));
                    cooldowns.put("clan_warten_log", jetzt + 30_000);
                }
                return Outcome.WAITING;
            }
            case CHAT_ANSEHEN -> {
                VerliesClan.ChatLesung l = VerliesClan.clanChatLesen(ctx);
                ClanVerliesPlan.Antwort a = clanPlan.chatGelesen(l.offen() ? l.zeilen() : null, l.online(),
                        System.currentTimeMillis());
                String zeile = clanPlan.letzteAntwortZeile();
                Log.info("Verlies", Sprache.t("Clan chat checked while waiting: " + a, "Clan-Chat beim Warten angesehen: " + a)
                        + (l.online() != null ? " (" + l.online() + " online)" : "")
                        + (a != ClanVerliesPlan.Antwort.SONST ? " -- \"" + zeile + "\"" : ""));
                if (a != ClanVerliesPlan.Antwort.SONST) {
                    String folge = switch (a) {
                        case ZUSAGE -> "Zusage -- der Bot wartet weiter.";
                        case SPAETER -> "spaeter -- in 5 Minuten neu einladen.";
                        default -> "Absage -- Einladung zurueck, spaeter nochmal.";
                    };
                    ctx.melde("Clan-Verlies: Antwort im Clan-Chat", "\"" + zeile + "\" -> " + folge);
                }
                runs.progress(Sprache.t("Clan chat checked", "Clan-Chat angesehen"));
                return Outcome.ACTED;
            }
            case NEU_EINLADEN -> {
                Log.info("Verlies", Sprache.t("The clan mate wanted to come later -- inviting again.",
                        "Das Clan-Mitglied wollte spaeter -- neu einladen."));
                VerliesClan.wartenAbbrechen(screen, ctx, T_CANCEL_SEARCH);
                clanEingeladenUm = 0;   // gleich wieder "Clan" in der Mitspielerwahl
                runs.progress(Sprache.t("Clan invite renewed", "Clan-Einladung erneuert"));
                return Outcome.ACTED;
            }
            default -> {
                VerliesClan.wartenAbbrechen(screen, ctx, T_CANCEL_SEARCH);
                clanAufgeben("Clan mate quest given up (" + clanPlan.grund() + ") -- the quest \"Go to a Dungeon with a "
                        + "clan mate\" is tried again later.",
                        "Clan-Quest aufgegeben (" + clanPlan.grund() + ") -- die Quest \"Go to a Dungeon with a clan mate\" "
                        + "spaeter nochmal.");
                runs.progress(Sprache.t("Clan mate search cancelled", "Clan-Suche abgebrochen"));
                return Outcome.ACTED;
            }
        }
    }

    /**
     * Auf der Verlieskarte mit einem Clan-Kameraden (Autor 19.11.): beim vorletzten Stuetzpunkt (Balken ab 80 %) einmal
     * "Thanks" im Verlies-Chat, solange der Mitspieler noch da ist; bei 100 % einmal nachsehen, ob er geantwortet hat.
     * Null = nichts zu tun, normal weiterspielen.
     */
    private Outcome clanAufKarte(Mat screen, BotContext ctx, RunTracker runs) {
        clanPlan.angenommen();
        if (!insideDungeon) {
            // Angenommen, waehrend der Bot nicht als "im Verlies" galt (Autor 19.11.): jetzt ist er wieder im Verlies
            insideDungeon = true;
            if (betretenUm == 0) betretenUm = System.currentTimeMillis();
        }
        boolean weg = allyLeftSeen(screen, ctx) != null;
        // Beim Ausstieg nur noch, wenn der Mitspieler gegangen ist (dann Thanks im Clan-Chat, 19.11. 21:25)
        if ((runAborted || abortReason != null) && !weg && !clanPlan.istMitspielerWeg()) return null;
        // nur in der letzten Runde -- sonst passt der Abschied nicht
        boolean letzteRunde = runCounted ? runs.isDone() : runs.completedRuns() + 1 >= runs.targetRuns();
        if (!letzteRunde) return null;
        int prozent = VerliesClan.fortschrittProzent(ctx.frame());
        if (prozent < 0 && ctx.visible(screen, T_PROGRESS_100)) prozent = 100;
        switch (clanPlan.dankJetzt(prozent, weg)) {
            case VERLIES_CHAT -> {
                Log.info("Verlies", Sprache.t("Progress " + prozent + " % -- saying \"Thanks\" in the dungeon chat.",
                        "Fortschritt " + prozent + " % -- \"Thanks\" im Verlies-Chat."));
                switch (clan.dankSagen(ctx)) {
                    case GESENDET -> {
                        clanPlan.gedankt();
                        runs.progress(Sprache.t("Dungeon chat: Thanks sent", "Verlies-Chat: Thanks gesendet"));
                    }
                    // gleich danach (naechstes Bild) kommt das Thanks in den Clan-Chat
                    case MITSPIELER_WEG -> clanPlan.mitspielerWeg();
                    default -> clanPlan.dankAusgelassen();
                }
                return Outcome.ACTED;
            }
            case CLAN_CHAT -> {
                Log.info("Verlies", Sprache.t("Progress " + prozent + " %, the clan mate already left -- \"Thanks\" in the "
                        + "clan chat.", "Fortschritt " + prozent + " %, der Clan-Kamerad ist schon weg -- \"Thanks\" im Clan-Chat."));
                if (clan.dankImClanChat(ctx) == VerliesClan.DankErgebnis.GESENDET) {
                    clanPlan.gedankt();
                    runs.progress(Sprache.t("Clan chat: Thanks sent", "Clan-Chat: Thanks gesendet"));
                } else {
                    clanPlan.dankAusgelassen();
                }
                return Outcome.ACTED;
            }
            default -> { }
        }
        if (clanPlan.antwortPruefenJetzt(prozent, weg)) {
            clanPlan.antwortGeprueft();
            if (clan.antwortPruefen(ctx)) clanPlan.mitspielerWeg();
            return Outcome.ACTED;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Mitspielersuche: Wartebildschirm ueberwachen
    // ------------------------------------------------------------------
    /**
     * Der Bildschirm "Waiting for random player" kann sehr lange stehen bleiben,
     * wenn gerade niemand sucht. Nach Ablauf der eingestellten Frist bricht der
     * Bot ueber den roten Knopf ab. Danach steht er wieder in der
     * Mitspielerauswahl und startet eine neue Suche.
     */
    private Outcome handleTeammateSearch(Mat screen, BotContext ctx, RunTracker runs) {
        // Mit einem Clan-Mitglied: hoechstens 5 Minuten, dann aufgeben (nicht neu suchen).
        if (teammate == Teammate.CLAN) {
            return aufClanWarten(screen, ctx, runs);
        }
        // Aus der Freundesliste eingeladen: eigene Regeln, siehe aufFreundWarten().
        if (teammate == Teammate.FRIEND && freundEingeladenUm > 0) {
            freundWartebildGesehen = true;
            return aufFreundWarten(ctx, runs);
        }
        long now = System.currentTimeMillis();

        if (searchWaitingSince == 0) {
            searchWaitingSince = now;
            Log.info("Verlies", Sprache.t("Searching for a random partner.", "Suche nach einem zufaelligen Mitspieler laeuft."));
            return Outcome.WAITING;
        }

        long waitedMillis = now - searchWaitingSince;
        long limitMillis = runs.idleTimeoutMillis();

        if (waitedMillis < limitMillis) {
            long remaining = (limitMillis - waitedMillis) / 1000;
            Log.debug("Verlies", "Warte auf Mitspieler, noch " + remaining
                    + " Sekunden bis zum Abbruch.");
            return Outcome.WAITING;
        }

        searchRestarts++;
        Log.warn("Verlies", Sprache.t("No partner found for " + (waitedMillis / 1000) + " seconds. "
                + "The search is cancelled and restarted "
                + "(restart " + searchRestarts + ").",
                "Seit " + (waitedMillis / 1000) + " Sekunden kein Mitspieler "
                + "gefunden. Die Suche wird abgebrochen und neu gestartet "
                + "(Neustart " + searchRestarts + ")."));

        if (ctx.clickIfVisible(screen, T_CANCEL_SEARCH, Sprache.t("Cancel the partner search", "Mitspielersuche abbrechen"))) {
            searchWaitingSince = 0;
            markClicked(T_RANDOM);   // kurz sperren, damit das Fenster erst aufgeht
            runs.progress(Sprache.t("Search restarted", "Suche neu gestartet"));
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }

        Log.error("Verlies", Sprache.t("The Cancel button was not hit. "
                + "Please check the picture cancel_search.",
                "Der Abbrechen-Knopf wurde nicht getroffen. "
                + "Bitte die Vorlage cancel_search pruefen."));
        searchWaitingSince = 0;
        return Outcome.UNKNOWN;
    }

    // ------------------------------------------------------------------
    //  Weltkarte seitlich schieben, bis das Verlies im Bild ist
    // ------------------------------------------------------------------
    /**
     * Das Verlies liegt weiter rechts als der Ausschnitt, den die Karte beim
     * Oeffnen zeigt. Der Bot zieht die Karte deshalb zur Seite und prueft nach
     * jedem Zug erneut, ob das Schild sichtbar geworden ist.
     *
     * Zuerst wird nach links gezogen, der Blick wandert also nach rechts.
     * Bringt das nichts, wird die Gegenrichtung versucht.
     */
    private Outcome swipeWorldMap(BotContext ctx, RunTracker runs) {
        if (mapSwipes >= MAX_MAP_SWIPES) {
            Log.error("Verlies", Sprache.t("After " + MAX_MAP_SWIPES + " swipes the Dungeon has not "
                    + "shown up on the world map. Possible causes: the picture "
                    + "dungeon_node no longer matches, or the map cannot be "
                    + "dragged in the expected direction.",
                    "Nach " + MAX_MAP_SWIPES + " Zuegen ist das Verlies auf der "
                    + "Weltkarte nicht aufgetaucht. Moegliche Ursachen: die Vorlage "
                    + "dungeon_node passt nicht mehr, oder die Karte laesst sich nicht "
                    + "in die erwartete Richtung schieben."));
            searchingDungeon = false;
            mapSwipes = 0;
            return Outcome.FATAL;
        }

        int width = ctx.width();
        int height = ctx.height();

        // Auf halber Hoehe ziehen, dort liegen keine Bedienelemente.
        int y = (int) (height * 0.52);
        int near = (int) (width * 0.24);
        int far  = (int) (width * 0.78);

        // Erste Haelfte der Versuche nach links, danach zur Sicherheit nach rechts.
        boolean pullLeft = mapSwipes < MAX_MAP_SWIPES / 2;
        int fromX = pullLeft ? far : near;
        int toX   = pullLeft ? near : far;

        Log.action("Karte", Sprache.t("Dragging the world map to the " + (pullLeft ? "left" : "right")
                + " to find the Dungeon (swipe "
                + (mapSwipes + 1) + " of " + MAX_MAP_SWIPES + ").",
                "Weltkarte wird nach " + (pullLeft ? "links" : "rechts")
                + " geschoben, um das Verlies zu finden (Zug "
                + (mapSwipes + 1) + " von " + MAX_MAP_SWIPES + ")."));

        ctx.input().drag(fromX, y, toX, y);
        mapSwipes++;
        runs.progress(Sprache.t("World map dragged", "Weltkarte geschoben"));
        ctx.sleepUntilChanged(1300);
        return Outcome.ACTED;
    }

    // ------------------------------------------------------------------
    //  Zweikampf gegen den Mitspieler: zwei Einheiten hineinziehen
    // ------------------------------------------------------------------
    /**
     * Unterscheidet den Zweikampf von einem gewoehnlichen Stuetzpunkt.
     *
     * Der Knopf "Finish selection" allein reicht dafuer nicht: er sieht dem
     * gruenen "To battle!" sehr aehnlich, sodass eine grosszuegig
     * zugeschnittene Vorlage auch dort anschlaegt. Deshalb gilt ein Bildschirm
     * nur dann als Zweikampf, wenn zusaetzlich weder "Deploy the best" noch
     * "Quick Attack" zu sehen sind – beide gibt es im Zweikampf nicht.
     */
    private boolean isPartnerDuel(Mat screen, BotContext ctx) {

        // Ist ueberhaupt ein Truppenfenster offen? Sonst ist der Zweikampf vorbei.
        boolean squadScreen = visibleAny(screen, ctx, T_FINISH_SELECT)
                || (ctx.matcher().has(T_DEPLOY_SCREEN) && ctx.visible(screen, T_DEPLOY_SCREEN));
        if (!squadScreen) {
            endDuel();
            return false;
        }

        // "Deploy the best" oder "Quick Attack" gibt es im Zweikampf nicht.
        // Sind sie da, ist das ein gewoehnlicher Stuetzpunkt.
        if (visibleAny(screen, ctx, T_DEPLOY_BEST)) {
            if (duelActive) Log.debug("Verlies", "\"Deploy the best\" ist zu sehen – "
                    + "doch kein Zweikampf.");
            endDuel();
            return false;
        }
        if (ctx.visible(screen, T_QUICK_ATTACK)) {
            if (duelActive) {
                // 15.10.: Doch -- nach beiden "Ready" zeigt auch der Zweikampf "Quick Attack" und "To battle".
                // Der Bot hielt das fuer einen gewoehnlichen Stuetzpunkt ohne "Deploy the best" und wartete
                // 10 s (DEPLOY_MISSING_GRACE_MS), bevor er Quick Attack drueckte (Autor: "sehr lange
                // ueberlegt"). Die Einheiten stehen ja schon: gleich weiter zum Kampf.
                Log.debug("Verlies", "Zweikampf: beide bereit, \"Quick Attack\" ist da -- Kampf starten.");
                deployedThisScreen = true;
                deployMissingSince = 0;
            }
            endDuel();
            return false;
        }

        // Eindeutiges Kennzeichen vorhanden: darauf verlassen.
        if (ctx.matcher().has(T_DUEL_MARKER)) {
            if (ctx.visible(screen, T_DUEL_MARKER)) {
                duelActive = true;
                return true;
            }
            // Kennzeichen weg, Truppenfenster aber noch offen: Sobald beide
            // Einheiten drin sind, verdeckt der gruene "Ready"-Knopf die Zeile.
            // Der Bildschirm bleibt deshalb ein Zweikampf.
            return duelActive;
        }

        // Kein Kennzeichen aufgenommen: "Finish selection" muss reichen.
        if (visibleAny(screen, ctx, T_FINISH_SELECT)) {
            duelActive = true;
            return true;
        }
        return duelActive;
    }

    /** Zweikampf ist vorbei: Merker und Zaehler zuruecksetzen. */
    private void endDuel() {
        duelActive = false;
        duelDragAttempts = 0;
    }

    private Outcome handlePartnerDuel(Mat screen, BotContext ctx, RunTracker runs) {

        // Sind noch Luecken offen? Ist die Vorlage duel_slot vorhanden, ist das
        // eine echte Auskunft. Fehlt sie, muss die Zeitsperre herhalten.
        boolean slotsKnown = ctx.matcher().has(T_DUEL_SLOT);
        List<Matcher.Hit> slots = slotsKnown ? emptyDuelSlots(screen, ctx) : List.of();
        boolean squadFull = slotsKnown ? slots.isEmpty() : onCooldown("duel_drag");

        if (squadFull) {
            Outcome done = finishDuel(screen, ctx, runs);
            if (done != null) return done;
            if (!onCooldown("duel_ready_warn")) {
                warnung(Sprache.t("In the duel the units are in place, but neither \"Ready\" "
                        + "nor \"Finish selection\" is recognized. The green \"Ready\" button "
                        + "looks different here than elsewhere. Please capture it with Capture-Images.exe as "
                        + "ready_alt.",
                        "Im Zweikampf stehen die Einheiten, aber weder \"Ready\" "
                        + "noch \"Finish selection\" wird erkannt. Der gruene \"Ready\"-Knopf "
                        + "sieht hier anders aus als sonst. Bitte mit Capture-Images.exe als "
                        + "ready_alt aufnehmen."));
                cooldowns.put("duel_ready_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        if (onCooldown("duel_drag")) {
            // Gerade gezogen: dem Spiel Zeit zum Nachziehen der Anzeige geben.
            return Outcome.WAITING;
        }

        if (++duelDragAttempts > 6) {
            if (!onCooldown("duel_drag_warn")) {
                warnung(Sprache.t("The units cannot be dragged into the empty "
                        + "slots in the duel. The most reliable fix is to capture the picture duel_slot "
                        + "(an EMPTY round deployment slot at the top) with Capture-Images.exe.",
                        "Die Einheiten lassen sich im Zweikampf nicht in die "
                        + "Luecken ziehen. Am sichersten wird das, wenn die Vorlage duel_slot "
                        + "(ein LEERES rundes Aufstellungsfeld oben) mit Capture-Images.exe "
                        + "aufgenommen wird."));
                cooldowns.put("duel_drag_warn", System.currentTimeMillis() + 30_000);
            }
            // Trotzdem weiterkommen: vielleicht sitzen die Einheiten ja doch.
            Outcome done = finishDuel(screen, ctx, runs);
            return done != null ? done : Outcome.WAITING;
        }

        // Bei jedem weiteren Anlauf eine Einheit weiter hinten anfangen. Sonst
        // wuerde der Bot immer wieder dieselbe Einheit ziehen, obwohl die
        // vielleicht schon oben steht und sich gar nicht mehr bewegen laesst.
        List<int[]> sources = duelDragSources(screen, ctx, duelDragAttempts - 1);
        List<int[]> targets = duelDropTargets(ctx, slots);
        if (sources.isEmpty() || targets.isEmpty()) {
            warnung(Sprache.t("Duel: no suitable unit or no "
                    + "empty slot found. Attempt " + duelDragAttempts + " of 6.",
                    "Zweikampf: es wurde keine passende Einheit oder keine "
                    + "freie Luecke gefunden. Anlauf " + duelDragAttempts + " von 6."));
            markClicked("duel_drag");
            return Outcome.WAITING;
        }

        Log.info("Verlies", Sprache.t("Duel against the partner. Deploying "
                + Math.min(sources.size(), targets.size()) + " units.",
                "Zweikampf gegen den Mitspieler. Es werden "
                + Math.min(sources.size(), targets.size()) + " Einheiten aufgestellt."));

        int count = Math.min(sources.size(), targets.size());
        for (int i = 0; i < count; i++) {
            int[] from = sources.get(i);
            int[] to = targets.get(i);
            Log.action("Ziehen", Sprache.t("Duel: unit from " + from[0] + "," + from[1]
                    + " to " + to[0] + "," + to[1],
                    "Zweikampf: Einheit von " + from[0] + "," + from[1]
                    + " nach " + to[0] + "," + to[1]));
            ctx.input().drag(from[0], from[1], to[0], to[1]);
            ctx.sleepUntilChanged(600);
        }

        markClicked("duel_drag");
        runs.progress(Sprache.t("Duel units placed", "Zweikampf-Einheiten gesetzt"));
        return Outcome.ACTED;
    }

    /**
     * Bereit melden und den Zweikampf starten. Liefert null, wenn keiner der
     * beiden Knoepfe zu sehen war.
     *
     * "Ready" sieht hier anders aus als auf dem normalen Aufstellbildschirm,
     * deshalb zaehlt jede Vorlage ab "ready" (ready, ready_alt, ready_2 ...).
     */
    private static String fmtScore(double score) {
        return String.format(java.util.Locale.GERMANY, "%.3f", score);
    }

    /**
     * Sagt, wie knapp die wichtigsten Vorlagen daneben lagen.
     *
     * "Nichts erkannt" allein hilft niemandem weiter. Diese Zeile zeigt, welche
     * Vorlage am naechsten dran war -- daran laesst sich ablesen, ob eine
     * Vorlage nur leicht nachjustiert werden muss (Wert knapp unter 0,84) oder
     * ob der Bildschirm wirklich ein voellig anderer ist (alles unter 0,5).
     */
    private void reportNearMisses(Mat screen, BotContext ctx) {
        String[] wichtig = {
                T_END_JOURNEY, T_END_JOURNEY_GRN, T_MAP_BUTTON, T_HOME,
                T_PARTICIPATE, T_DEPLOY_SCREEN, T_MOVING_HERE, T_NAMEPLATE_ME
        };

        StringBuilder text = new StringBuilder(Sprache.t("Best matches: ", "Beste Uebereinstimmungen: "));
        for (String name : wichtig) {
            if (!ctx.matcher().has(name)) continue;
            // Mit Schwelle 0 suchen: so kommt immer ein Wert zurueck.
            double best = ctx.matcher().find(screen, name, 0.0)
                    .map(Matcher.Hit::score).orElse(0.0);
            text.append(name).append(' ').append(fmtScore(best)).append("  ");
        }
        Log.warn("Verlies", text.toString().trim());
    }

    private Outcome finishDuel(Mat screen, BotContext ctx, RunTracker runs) {

        // "Ready" darf immer: damit meldet der Bot nur sich selbst bereit.
        if (clickAny(screen, ctx, T_READY, Sprache.t("Press Ready (duel)", "Bereit melden (Zweikampf)"))) {
            duelDragAttempts = 0;
            ctx.sleepUntilChanged(1000);
            return Outcome.ACTED;
        }

        // Schon bestaetigt: nicht noch einmal draufdruecken.
        //
        // Ein Schalter, keine Zeitsperre. Die lief ab, und danach drueckte der
        // Bot ein zweites und drittes Mal auf denselben Knopf. Im Protokoll
        // stand dreimal "Auswahl abschliessen" auf Punkt 960,651 -- das ist
        // der gruene "To battle!"-Knopf.
        if (duelConfirmed) return Outcome.WAITING;

        // Und losschlagen erst, wenn der andere auch bereit ist.
        if (!warteAufMitspieler(screen, ctx, Sprache.t("duel", "Zweikampf"))) {
            return Outcome.WAITING;
        }

        if (clickAny(screen, ctx, T_FINISH_SELECT, Sprache.t("Finish selection", "Auswahl abschliessen"))) {
            runs.progress(Sprache.t("Duel started", "Zweikampf gestartet"));
            duelDragAttempts = 0;
            duelConfirmed = true;
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }
        return null;
    }

    /**
     * Alle noch leeren Aufstellungsfelder, von links nach rechts.
     *
     * Ein leeres Feld ist ein fast einfarbiger Kreis – so eine Vorlage schlaegt
     * leicht auch anderswo an. Deshalb zaehlen nur Fundstellen OBERHALB der
     * Zeile "Drag and drop dwellers ...": die Felder liegen immer darueber,
     * die Truppenablage immer darunter.
     */
    private List<Matcher.Hit> emptyDuelSlots(Mat screen, BotContext ctx) {
        int lineY = deployLineY(screen, ctx);
        int limit = lineY >= 0 ? lineY : (int) (ctx.height() * 0.55);

        List<Matcher.Hit> slots = new ArrayList<>();
        for (Matcher.Hit hit : ctx.matcher().findAll(screen, T_DUEL_SLOT, 0.88, 6)) {
            if (hit.centerY() < limit) slots.add(hit);
        }
        slots.sort(Comparator.comparingInt(Matcher.Hit::centerX));
        return slots;
    }

    /**
     * Wohin gezogen wird. Sind die leeren Felder im Bild gefunden, werden genau
     * die genommen. Sonst bleiben die geschaetzten Bildpunkte als Rueckfall.
     */
    private List<int[]> duelDropTargets(BotContext ctx, List<Matcher.Hit> slots) {
        List<int[]> targets = new ArrayList<>();
        if (!slots.isEmpty()) {
            for (Matcher.Hit slot : slots) {
                targets.add(new int[]{slot.centerX(), slot.centerY()});
            }
            return targets;
        }
        Layout layout = Layout.forSize(ctx.width(), ctx.height());
        targets.add(new int[]{layout.slotX(0), layout.slotY()});
        targets.add(new int[]{layout.slotX(1), layout.slotY()});
        return targets;
    }

    /**
     * Woher gezogen wird: die eigenen Kaempfer in der Ablage unten.
     *
     * Sie werden an ihrem Namensschild erkannt (unit_1, unit_2 ...);
     * das Portraet sitzt ein Stueck darueber. Gefallene Einheiten werden
     * ausgelassen – die bringen im Zweikampf nichts. Findet sich gar nichts,
     * greifen die geschaetzten Bildpunkte von frueher.
     */
    private List<int[]> duelDragSources(Mat screen, BotContext ctx, int skip) {
        List<int[]> sources = new ArrayList<>();

        int lineY = deployLineY(screen, ctx);
        int lift = (int) (ctx.height() * 0.055);

        List<Matcher.Hit> dead = lineY < 0 ? List.of() : rawDeadUnits(screen, ctx);
        int skipped = 0;

        for (String name : PRIORITY_UNITS) {
            if (sources.size() >= 2) break;
            if (!ctx.matcher().has(name)) continue;

            // 15.10.: 0,70 statt 0,82. Gemessen am Duell-Foto mit den Team-Scan-Bildern: Bogen 2 (staerkste
            // Einheit) 0,772, Bogen 1 0,812, Magier 0,82-0,89; falsche Stellen hoechstens 0,45. Mit 0,82 fiel
            // Bogen 2 durch, und der Bot zog Magier statt der staerksten Einheiten (Autor: "immer die
            // staerksten Einheiten anhand der Power").
            Optional<Matcher.Hit> hit = ctx.matcher().find(screen, name, 0.70);
            if (hit.isEmpty()) continue;
            if (lineY >= 0 && hit.get().centerY() <= lineY) continue;   // nicht in der Ablage

            int x = hit.get().centerX();
            // Gepackt wird am Portraet. Ob die Vorlage schon das Portraet
            // zeigt oder nur das Namensschild darunter, verraet ihre Form:
            // ein Schild ist breit und flach, ein Portraet quadratisch.
            //
            // Hier stand frueher fest, dass angehoben wird. Autors Vorlagen
            // sind aber alle Portraets (rund 68 mal 56), und 5,5 Prozent der
            // Bildhoehe sind 42 Punkte -- der Bot griff also oberhalb der
            // Einheit ins Leere.
            boolean namensschild = hit.get().width() > hit.get().height() * 1.6;
            int y = hit.get().centerY() - (namensschild ? lift : 0);
            if (isNearDead(x, y, dead, ctx)) {
                Log.debug("Verlies", "Zweikampf: " + name + " ist gefallen und wird ausgelassen.");
                continue;
            }
            if (skipped < skip) {
                skipped++;
                continue;
            }
            sources.add(new int[]{x, y});
        }

        if (sources.size() >= 2) return sources;

        // 15.10.: Passen die Namensbilder nicht (Test-Installation: Bilder vom 18.09., Einheiten seitdem
        // anders), griff der Bot bei den geschaetzten Plaetzen unten (y = 88 % der Hoehe) ins Leere --
        // die Ablage liegt im Zweikampf-Fenster aber viel hoeher (Portraets bei y ~ 500 von 770).
        // Darum erst die gruenen Lebensbalken unter den Portraets suchen.
        List<int[]> balken = duelBalkenQuellen(screen, ctx, lineY, skip);
        if (balken.size() >= 2) {
            Log.info("Verlies", Sprache.t("Duel: unit pictures did not match -- the units were found by their green health bars.",
                    "Zweikampf: Einheitenbilder passten nicht -- die Einheiten wurden an ihren gruenen Lebensbalken gefunden."));
            return balken;
        }

        Log.debug("Verlies", "Zweikampf: nur " + sources.size() + " Einheiten am Namen erkannt, "
                + "es werden die geschaetzten Plaetze in der Ablage genommen.");
        Layout layout = Layout.forSize(ctx.width(), ctx.height());
        List<int[]> fallback = new ArrayList<>();
        fallback.add(new int[]{layout.unitX(0), layout.unitY()});
        fallback.add(new int[]{layout.unitX(1), layout.unitY()});
        return fallback;
    }

    /**
     * Die eigenen Einheiten in der Ablage an ihren Lebensbalken: kraeftig gruene Streifen von 3,5 bis 9 %
     * der Bildbreite, unter der Zeile "Drag and drop dwellers" (19 bis 26 % der Hoehe darunter; ohne diese
     * Zeile 71 bis 80 % der Hoehe). Gefallene Einheiten haben einen roten Balken und fallen so von selbst
     * heraus. Gegriffen wird am Portraet, 10,4 % der Hoehe ueber dem Balken. Gemessen am 15.10. in
     * Autors Spiel (1159 x 770): Zeile y 416, Balken y 581, Portraets y 500, Abstand 111.
     */
    private List<int[]> duelBalkenQuellen(Mat screen, BotContext ctx, int lineY, int skip) {
        List<int[]> quellen = new ArrayList<>();
        int h = Math.min(ctx.height(), screen.rows()), w = Math.min(ctx.width(), screen.cols());
        int kanaele = screen.channels();
        if (kanaele < 3 || h < 100 || w < 100) return quellen;
        int von = lineY >= 0 ? lineY + (int) (h * 0.19) : (int) (h * 0.71);
        int bis = Math.min(h - 1, lineY >= 0 ? lineY + (int) (h * 0.26) : (int) (h * 0.80));
        int minLang = (int) (w * 0.035), maxLang = (int) (w * 0.09);
        byte[] zeile = new byte[screen.cols() * kanaele];
        for (int y = Math.max(0, von); y <= bis; y += 2) {
            screen.get(y, 0, zeile);
            List<int[]> laeufe = new ArrayList<>();
            int start = -1, luecke = 0;
            for (int x = 0; x <= w; x++) {
                boolean gruen = false;
                if (x < w) {
                    int b = zeile[x * kanaele] & 0xff, g = zeile[x * kanaele + 1] & 0xff, r = zeile[x * kanaele + 2] & 0xff;
                    gruen = g > 140 && g > r + 50 && g > b + 50;
                }
                if (gruen) {
                    if (start < 0) start = x;
                    luecke = 0;
                } else if (start >= 0 && (++luecke > 3 || x == w)) {
                    int ende = x - luecke;
                    int lang = ende - start + 1;
                    if (lang >= minLang && lang <= maxLang) laeufe.add(new int[]{start, ende});
                    start = -1;
                    luecke = 0;
                }
            }
            if (laeufe.size() < 2) continue;
            int portraitY = y - (int) (h * 0.104);
            int uebersprungen = 0;
            for (int[] l : laeufe) {
                if (uebersprungen < skip) {
                    uebersprungen++;
                    continue;
                }
                quellen.add(new int[]{(l[0] + l[1]) / 2, portraitY});
                if (quellen.size() >= 2) break;
            }
            return quellen;
        }
        return quellen;
    }

    /** True, wenn an dieser Stelle eine gefallene Einheit steht. */
    private boolean isNearDead(int x, int y, List<Matcher.Hit> dead, BotContext ctx) {
        int tolerance = Math.max(20, (int) (ctx.height() * 0.045));
        for (Matcher.Hit d : dead) {
            if (Math.abs(d.centerX() - x) < tolerance && Math.abs(d.centerY() - y) < tolerance * 2) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Verlieskarte
    // ------------------------------------------------------------------
    /**
     * Stehen wir auf der Verlieskarte?
     *
     * Erstes Kennzeichen ist der Knopf "End journey" unten rechts. Der allein
     * hat sich aber als zu wenig erwiesen: wird er einmal nicht erkannt -- weil
     * er halb verdeckt ist, am Bildrand klebt oder ein Fenster darueber liegt --
     * gilt der ganze Bildschirm als unbekannt, obwohl Wegpunkte und der
     * Schriftzug "Moving here" gut sichtbar sind. Der Bot bleibt dann stehen.
     *
     * Deshalb zweiter Weg: sind wir nachweislich im Verlies und ist entweder
     * ein Wegpunkt oder "Moving here" zu sehen, ist das ebenfalls die Karte.
     * Beides gibt es nur dort, Fehlalarm ist also nicht zu erwarten.
     */
    private boolean isDungeonMap(Mat screen, BotContext ctx) {
        if (ctx.visible(screen, T_END_JOURNEY) || ctx.visible(screen, T_END_JOURNEY_GRN)) {
            return true;
        }
        if (!insideDungeon) return false;

        if (visibleAny(screen, ctx, T_MOVING_HERE)) {
            Log.debug("Verlies", "\"End journey\" nicht erkannt, aber \"Moving here\" ist da – "
                    + "das ist die Verlieskarte.");
            return true;
        }
        for (String waypoint : WAYPOINTS) {
            if (ctx.matcher().has(waypoint) && ctx.visible(screen, waypoint)) {
                Log.debug("Verlies", "\"End journey\" nicht erkannt, aber der Wegpunkt "
                        + waypoint + " ist da – das ist die Verlieskarte.");
                return true;
            }
        }
        return false;
    }

    private Outcome handleDungeonMap(Mat screen, BotContext ctx, RunTracker runs) {

        // Verlies fertig: verlassen ist jetzt erlaubt.
        if (isDungeonComplete(screen, ctx)) {
            Log.info("Verlies", Sprache.t("Progress at 100 percent. Leaving the Dungeon.", "Fortschritt bei 100 Prozent. Verlies wird verlassen."));
            endJourneyAllowed = true;
            if (click(screen, ctx, T_END_JOURNEY_GRN, Sprache.t("Leave the Dungeon", "Verlies verlassen"))
                    || click(screen, ctx, T_END_JOURNEY, Sprache.t("Leave the Dungeon", "Verlies verlassen"))) {

                // HIER wird gezaehlt, und nur hier.
                //
                // Der Klick auf "End journey" bei 100 Prozent ist der einzige
                // Punkt im ganzen Ablauf, an dem zweifelsfrei feststeht: Dieser
                // Durchlauf ist geschafft. Vorher haing die Zaehlung an zwei
                // spaeteren Stellen -- dem Abschlussfenster und der Rueckkehr
                // in die Burg -- und beide waren stoeranfaellig: Ein Abbruch,
                // der irgendwann vorher passiert war, konnte einen fertig
                // gespielten Lauf noch verschlucken.
                //
                // Ob der Knopf rot oder gruen ist, spielt keine Rolle. Gruen
                // heisst, dass alles abgeschlossen ist, rot kommt vor -- an
                // dieser Stelle ist der Fortschritt aber ohnehin bei 100
                // Prozent, sonst waeren wir gar nicht hier.
                // Autor 18.10.: "Der Run soll erst gezaehlt werden, wenn der Bot wieder in der Burg ist." -- hier NICHT mehr
                // zaehlen; das macht endRun() am Abschlussfenster bzw. bei der Rueckkehr in Burg/Weltkarte.
                Log.debug("Verlies", "End journey bei 100 Prozent gedrueckt -- gezaehlt wird erst draussen.");

                ctx.sleepUntilChanged(1500);
                // 16.10. (Autor): "Ein Verlies-Run soll erst als fertig gezaehlt werden, wenn er wirklich wieder
                // aus dem Verlies draussen ist." Vorher kam hier beim letzten Run sofort FINISHED -- der naechste
                // Modus (Leuchtturm) fing an, waehrend noch "End journey?" und das Abschlussfenster offen waren.
                // Gezaehlt bleibt hier; FERTIG meldet erst das Abschlussfenster, die Burg oder die Weltkarte.
                if (runs.isDone()) fertigGezaehltUm = System.currentTimeMillis();
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // Mitspieler untaetig: nach Ablauf der Frist abbrechen.
        if (runs.isIdleTooLong()) {
            Log.warn("Verlies", Sprache.t("No progress for " + runs.idleSeconds() + " seconds. "
                    + "The partner is probably idle. Leaving the Dungeon.",
                    "Seit " + runs.idleSeconds() + " Sekunden kein Fortschritt. "
                    + "Der Mitspieler ist vermutlich untaetig. Verlies wird verlassen."));
            endJourneyAllowed = true;
            if (click(screen, ctx, T_END_JOURNEY, Sprache.t("Leave the Dungeon because of inactivity", "Verlies wegen Untaetigkeit verlassen"))) {
                runs.resetIdleClock();
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        if (!isLeader(screen, ctx)) {
            return followTeammate(screen, ctx, runs);
        }

        leaderWaitSince = 0;   // wir sind selbst Leiter, keine Wartefrist noetig
        Optional<Matcher.Hit> waypoint = pickWaypoint(screen, ctx);
        if (waypoint.isPresent()) {
            Matcher.Hit w = waypoint.get();
            Log.action("Klick", Sprache.t("Choose waypoint  [" + w.name() + " at "
                    + w.centerX() + "," + w.centerY() + "]",
                    "Wegpunkt waehlen  [" + w.name() + " bei "
                    + w.centerX() + "," + w.centerY() + "]"));
            ctx.input().click(w);
            letzterStuetzpunkt = w.name();
            runs.progress(Sprache.t("Waypoint chosen", "Wegpunkt gewaehlt"));
            ctx.sleepUntilChanged(900);
            return Outcome.ACTED;
        }

        Log.debug("Verlies", "Als Leiter kein offener Wegpunkt gefunden. Warte.");
        return Outcome.WAITING;
    }

    /**
     * Als Mitspieler (nicht Leiter) sucht der Bot den Stuetzpunkt nicht selbst
     * aus. Sobald der Leiter einen angesteuert hat, erscheint darueber der
     * Schriftzug "moving here". Der Bot klickt den Stuetzpunkt unter genau
     * dieser Markierung an, damit die eigene Truppe folgt. Alles Weitere
     * ("Go here", "Deploy the best", "Ready") uebernehmen anschliessend die
     * normalen Regeln.
     */
    private Outcome followTeammate(Mat screen, BotContext ctx, RunTracker runs) {
        long now = System.currentTimeMillis();

        // Frist: waehlt der Leiter zu lange keinen Stuetzpunkt, Verlies verlassen
        // und neuen Mitspieler suchen.
        if (leaderWaitSince == 0) {
            leaderWaitSince = now;
            Log.info("Verlies", Sprache.t("Partner: waiting for the leader to choose a waypoint (up to "
                    + (leaderPickTimeoutMillis / 60000) + " minutes).",
                    "Mitspieler: warte auf die Stuetzpunkt-Wahl des Leiters (bis zu "
                    + (leaderPickTimeoutMillis / 60000) + " Minuten)."));
        }
        if (now - leaderWaitSince > leaderPickTimeoutMillis && !onCooldown("follow_teammate")) {
            searchRestarts++;
            Log.warn("Verlies", Sprache.t("The leader has not chosen a waypoint for " + ((now - leaderWaitSince) / 1000)
                    + " seconds. Leaving the Dungeon, then a new "
                    + "partner search (restart " + searchRestarts + ").",
                    "Der Leiter hat " + ((now - leaderWaitSince) / 1000) + " Sekunden "
                    + "keinen Stuetzpunkt gewaehlt. Verlies wird verlassen, danach neue "
                    + "Mitspielersuche (Neustart " + searchRestarts + ")."));
            endJourneyAllowed = true;
            leaderWaitSince = 0;
            participateAttempts = 0;
            runAborted = true;   // Hauptbot (Code-Review 15.10.): dieser Ausstieg zaehlte als geschaffter Lauf
            if (click(screen, ctx, T_END_JOURNEY, Sprache.t("Leave the Dungeon (leader idle)", "Verlies verlassen (Leiter untaetig)"))) {
                runs.progress(Sprache.t("Dungeon left -- leader idle", "Verlies verlassen – Leiter untaetig"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        List<String> movingVariants = variantsOf(ctx, T_MOVING_HERE);
        if (onCooldown("follow_teammate")) {
            // Ben 18.10.: "ging zum falschen Wegpunkt und wartete, obwohl der Leiter einen anderen gewaehlt hatte" --
            // steht die Markierung jetzt deutlich woanders, gleich dorthin folgen.
            boolean umgewaehlt = false;
            if (letzteMarkierung != null) {
                for (String variant : movingVariants) {
                    for (Matcher.Hit m : ctx.matcher().findAll(screen, variant, MOVING_HERE_AB, 6)) {
                        if (Math.abs(m.centerX() - letzteMarkierung[0]) > 40 || Math.abs(m.centerY() - letzteMarkierung[1]) > 40) {
                            umgewaehlt = true;
                        }
                    }
                }
            }
            if (!umgewaehlt) {
                Log.debug("Verlies", "Mitspieler: Stuetzpunkt schon angesteuert, warte auf den Leiter.");
                return Outcome.WAITING;
            }
            Log.info("Verlies", Sprache.t("The leader chose another waypoint -- following again.",
                    "Der Leiter hat einen anderen Stuetzpunkt gewaehlt -- folge neu."));
            cooldowns.remove("follow_teammate");
        }

        if (movingVariants.isEmpty()) {
            if (!onCooldown("moving_here_warn")) {
                warnung(Sprache.t("Not the leader, but the picture " + T_MOVING_HERE + " is missing. "
                        + "Without it the bot cannot follow the leader. Please capture the words "
                        + "\"moving here\" above a waypoint with Capture-Images.exe.",
                        "Kein Leiter, aber die Vorlage " + T_MOVING_HERE + " fehlt. "
                        + "Ohne sie kann der Bot dem Leiter nicht folgen. Bitte den Schriftzug "
                        + "\"moving here\" ueber einem Stuetzpunkt mit Capture-Images.exe aufnehmen."));
                cooldowns.put("moving_here_warn", System.currentTimeMillis() + 30_000);
            }
            return Outcome.WAITING;
        }

        // Der Schriftzug wird oft halb vom Fortschrittsbalken verdeckt und sieht
        // dann anders aus. Deshalb zaehlt jede Vorlage ab "moving_here"
        // (moving_here, moving_here_alt, moving_here_2 ...).
        List<Matcher.Hit> markers = new ArrayList<>();
        for (String variant : movingVariants) {
            markers.addAll(ctx.matcher().findAll(screen, variant, MOVING_HERE_AB, 6));
        }
        if (markers.isEmpty()) {
            Log.debug("Verlies", "Mitspieler: noch keine \"moving here\"-Markierung sichtbar. Warte.");
            return Outcome.WAITING;
        }

        markers.sort(Comparator.comparingDouble(Matcher.Hit::score).reversed());
        if (markers.size() > 1) {
            Log.debug("Verlies", markers.size() + " \"moving here\"-Markierungen gefunden, "
                    + "die sicherste wird genommen.");
        }
        Matcher.Hit marker = markers.get(0);
        letzteMarkierung = new int[]{marker.centerX(), marker.centerY()};

        int targetX = marker.centerX();
        int targetY = marker.centerY() + (int) (ctx.height() * MOVING_HERE_DROP);

        Matcher.Hit node = waypointBelow(screen, ctx, marker);
        if (node != null) {
            targetX = node.centerX();
            targetY = node.centerY();
            letzterStuetzpunkt = node.name();
            Log.info("Verlies", Sprache.t("Partner follows the leader to the waypoint (" + node.name() + ").",
                    "Mitspieler folgt dem Leiter zum Stuetzpunkt (" + node.name() + ")."));
        } else {
            Log.info("Verlies", Sprache.t("Partner follows the leader. No known waypoint symbol was "
                    + "recognized right below the marker, clicking just below it.",
                    "Mitspieler folgt dem Leiter. Direkt unter der Markierung wurde "
                    + "kein bekanntes Wegpunkt-Symbol erkannt, es wird knapp darunter geklickt."));
        }

        Log.action("Klick", Sprache.t("The leader's waypoint  [" + targetX + "," + targetY
                + ", marker " + String.format("%.3f", marker.score()) + "]",
                "Stuetzpunkt des Leiters  [" + targetX + "," + targetY
                + ", Markierung " + String.format("%.3f", marker.score()) + "]"));
        ctx.input().click(targetX, targetY, 6);

        boolean freshAttempt = System.currentTimeMillis() - lastFollowClickAt > 20_000;
        lastFollowClickAt = System.currentTimeMillis();
        cooldowns.put("follow_teammate", System.currentTimeMillis() + FOLLOW_COOLDOWN_MS);
        leaderWaitSince = 0;   // der Leiter hat gewaehlt, Frist neu ab dem naechsten Stuetzpunkt
        unknownScreens = 0;
        // Nur beim ersten Versuch die Untaetigkeitsuhr zuruecksetzen. Klickt der
        // Bot wiederholt ins Leere, laeuft die Uhr weiter und der Bot verlaesst
        // das Verlies ueber die normale Frist.
        if (freshAttempt) runs.progress(Sprache.t("Followed the leader", "Dem Leiter gefolgt"));
        ctx.sleepUntilChanged(1600);
        return Outcome.ACTED;
    }

    /**
     * Sucht das Wegpunkt-Symbol, das am dichtesten unterhalb der Markierung
     * liegt. Liefert null, wenn keines nah genug passt.
     */
    private Matcher.Hit waypointBelow(Mat screen, BotContext ctx, Matcher.Hit marker) {
        Matcher.Hit best = null;
        int bestDy = Integer.MAX_VALUE;
        int maxDx = Math.max(60, ctx.width() / 12);
        int maxDy = ctx.height() / 4;

        for (String template : WAYPOINTS) {
            if (!ctx.matcher().has(template)) continue;
            for (Matcher.Hit hit : ctx.matcher().findAll(screen, template, 0.80, 12)) {
                int dx = Math.abs(hit.centerX() - marker.centerX());
                int dy = hit.centerY() - marker.centerY();
                if (dx <= maxDx && dy >= 0 && dy <= maxDy && dy < bestDy) {
                    bestDy = dy;
                    best = hit;
                }
            }
        }
        return best;
    }

    /**
     * Ist das Verlies wirklich fertig?
     *
     * Frueher reichte ein Treffer der Vorlage progress_100 mit der ueblichen
     * Schwelle. Der Fortschrittsbalken sieht bei 92 Prozent aber fast genauso
     * aus wie bei 100 – dadurch ist der Bot zu frueh ausgestiegen.
     *
     * Deshalb jetzt zwei strenge Wege, von denen einer reichen muss:
     *  1. Der Knopf "End journey" ist gruen. Das faerbt das Spiel selbst erst
     *     bei 100 Prozent um, also das verlaesslichste Zeichen.
     *  2. progress_100 mit sehr hoher Uebereinstimmung – als Rueckfall, falls
     *     die gruene Vorlage einmal nicht sitzt.
     */
    private boolean isDungeonComplete(Mat screen, BotContext ctx) {

        // Beide Schwellen wurden nachgeschaerft: der Bot stieg bei 93 Prozent
        // aus. Und es wird protokolliert, WELCHER der beiden Wege entschieden
        // hat -- ohne das laesst sich beim naechsten Fehlausstieg nicht sagen,
        // welche Vorlage danebenlag.
        Optional<Matcher.Hit> green = ctx.matcher().has(T_END_JOURNEY_GRN)
                ? ctx.matcher().find(screen, T_END_JOURNEY_GRN, END_JOURNEY_GREEN_THRESHOLD)
                : Optional.empty();
        if (green.isPresent()) {
            Log.info("Verlies", Sprache.t("Dungeon counts as finished: the \"End journey\" button is green "
                    + "(match " + String.format("%.3f", green.get().score()) + ").",
                    "Verlies gilt als fertig: der Knopf \"End journey\" ist gruen "
                    + "(Guete " + String.format("%.3f", green.get().score()) + ")."));
            return true;
        }

        Optional<Matcher.Hit> full =
                ctx.matcher().find(screen, T_PROGRESS_100, PROGRESS_100_THRESHOLD);
        if (full.isPresent()) {
            Log.info("Verlies", Sprache.t("Dungeon counts as finished: progress is at 100 percent "
                    + "(match " + String.format("%.3f", full.get().score()) + ").",
                    "Verlies gilt als fertig: der Fortschritt steht auf 100 Prozent "
                    + "(Guete " + String.format("%.3f", full.get().score()) + ")."));
            return true;
        }
        // Knapp daneben: einmal melden, damit im Protokoll steht warum gewartet wird.
        Optional<Matcher.Hit> near = ctx.matcher().find(screen, T_PROGRESS_100, 0.84);
        if (near.isPresent() && !onCooldown("progress_hint")) {
            Log.debug("Verlies", "Fortschrittsbalken aehnelt 100 Prozent (nur "
                    + String.format("%.2f", near.get().score()) + "), das reicht nicht. "
                    + "Es wird weitergespielt.");
            cooldowns.put("progress_hint", System.currentTimeMillis() + 20_000);
        }
        return false;
    }

    /**
     * Sehr hohe Schwelle fuer den Fortschrittsbalken. Bei 92 Prozent kam die
     * Vorlage auf 0.85 – knapp ueber der ueblichen Schwelle von 0.84.
     */
    private static final double PROGRESS_100_THRESHOLD = 0.985;

    /**
     * Ebenso streng fuer den gruenen "End journey". Bei 93 Prozent stieg der
     * Bot noch aus -- eine der beiden Vorlagen sass also zu locker.
     */
    private static final double END_JOURNEY_GREEN_THRESHOLD = 0.96;

    /**
     * Der Leiter steht immer auf dem linken Namensschild unten. Steht der eigene
     * Name links, ist man Leiter.
     */
    /** Zuletzt gemeldete Rolle (Autor 18.10.: Test "bis ich mindestens einmal Leiter bin" -- im Protokoll zaehlbar). */
    private Boolean letzteRolle;

    private boolean isLeader(Mat screen, BotContext ctx) {
        boolean l = isLeaderRoh(screen, ctx);
        if (letzteRolle == null || letzteRolle != l) {
            letzteRolle = l;
            Log.info("Verlies", Sprache.t("Role: " + (l ? "leader" : "partner"), "Rolle: " + (l ? "Leiter" : "Mitspieler")));
        }
        return l;
    }

    private boolean isLeaderRoh(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> me = ctx.find(screen, T_NAMEPLATE_ME);
        if (me.isEmpty()) {
            // Ben 18.10.: Passt das Bild vom eigenen Namensschild nicht (oder fehlt es), galt der Bot immer als
            // Mitspieler -- als Leiter wartete er 60 s auf sich selbst und verliess das Verlies. Jetzt liest er die
            // Namen auf beiden Schildern unten und vergleicht sie mit dem Spielernamen aus der Burg.
            Boolean nachName = leiterNachName(ctx);
            if (nachName != null) return nachName;
            Log.debug("Verlies", "Eigenes Namensschild nicht gefunden (weder Bild noch Name), gelte als kein Leiter.");
            return false;
        }
        boolean leader = me.get().centerX() < ctx.width() / 2;
        Log.debug("Verlies", "Namensschild bei x=" + me.get().centerX()
                + " von " + ctx.width() + " – Leiter: " + leader);
        return leader;
    }

    /** Ergebnis der letzten Namens-Lesung (Texterkennung kostet Zeit -- hoechstens alle 3 s neu). */
    private Boolean leiterNamensErgebnis;
    private long leiterNameGelesenUm;
    private boolean leiterNameGemeldet;

    /**
     * Leiter am Namen erkennen: der Leiter steht immer links unten, der Mitspieler rechts. Liest beide Schilder und
     * vergleicht mit dem eigenen Spielernamen (MeinTeam, aus der Burg gelesen). TRUE = links, FALSE = rechts,
     * null = nicht sicher (kein Name bekannt, nichts lesbar oder auf beiden Seiten aehnlich).
     */
    private Boolean leiterNachName(BotContext ctx) {
        String ich = MeinTeam.spielername();
        if (ich == null || ich.isBlank()) return null;
        long jetzt = System.currentTimeMillis();
        if (jetzt - leiterNameGelesenUm < 3000) return leiterNamensErgebnis;
        leiterNameGelesenUm = jetzt;
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return leiterNamensErgebnis = null;
        int w = b.getWidth(), h = b.getHeight();
        // Namensschilder unten: links um x 0,36 (Leiter), rechts um x 0,66 (Mitspieler), y um 0,90 (gemessen am
        // Bild nameplate_me: 383,688 bzw. 727,688 bei 1159x770, 70x21).
        List<String> links = ArenaLeser.liesNamenZeilen(b, (int) (w * 0.24), (int) (h * 0.875), (int) (w * 0.25), (int) (h * 0.06));
        List<String> rechts = ArenaLeser.liesNamenZeilen(b, (int) (w * 0.54), (int) (h * 0.875), (int) (w * 0.25), (int) (h * 0.06));
        boolean l = enthaeltNamen(links, ich), r = enthaeltNamen(rechts, ich);
        leiterNamensErgebnis = l && !r ? Boolean.TRUE : r && !l ? Boolean.FALSE : null;
        if (leiterNamensErgebnis != null && !leiterNameGemeldet) {
            leiterNameGemeldet = true;
            Log.info("Verlies", Sprache.t("Own name \"" + ich + "\" found on the " + (l ? "left (leader)" : "right (partner)") + " name plate.",
                    "Eigener Name \"" + ich + "\" auf dem " + (l ? "linken Schild (Leiter)" : "rechten Schild (Mitspieler)") + " gefunden."));
        }
        Log.debug("Verlies", "Namensschilder gelesen: links " + links + ", rechts " + rechts + " -- ich \"" + ich + "\": "
                + (leiterNamensErgebnis == null ? "unklar" : leiterNamensErgebnis ? "Leiter" : "Mitspieler"));
        return leiterNamensErgebnis;
    }

    private static boolean enthaeltNamen(List<String> zeilen, String ich) {
        for (String z : zeilen) {
            if (de.hustlebot.core.NamensVergleich.gleicherName(z, ich)
                    || de.hustlebot.core.NamensVergleich.aehnlichkeit(z, ich) >= 0.75) return true;
        }
        return false;
    }

    /** Sucht alle offenen Wegpunkte und blendet die aus, die ein Schloss tragen. */
    private Optional<Matcher.Hit> pickWaypoint(Mat screen, BotContext ctx) {
        List<Matcher.Hit> locks = ctx.matcher().findAll(screen, T_LOCK, 0.80, 40);

        List<Matcher.Hit> open = new ArrayList<>();
        for (String base : WAYPOINTS) {
            // Wie bei den Knoepfen zaehlt jede Vorlage ab dem Grundnamen als
            // derselbe Wegpunkt: waypoint_swords, waypoint_swords_alt,
            // waypoint_swords_2 ... Ein neues Motiv braucht damit keine
            // Codeaenderung mehr, nur eine neue Aufnahme.
            for (String template : variantsOf(ctx, base)) {
                for (Matcher.Hit hit : ctx.matcher().findAll(screen, template, 0.82, 12)) {
                    if (!isLocked(hit, locks)) open.add(hit);
                }
            }
        }

        if (open.isEmpty()) return Optional.empty();

        Log.debug("Verlies", open.size() + " offene Wegpunkte gefunden, "
                + locks.size() + " verschlossene ignoriert.");

        // Der sicherste Treffer zuerst.
        open.sort(Comparator.comparingDouble(Matcher.Hit::score).reversed());
        return Optional.of(open.get(0));
    }

    private boolean isLocked(Matcher.Hit waypoint, List<Matcher.Hit> locks) {
        for (Matcher.Hit lock : locks) {
            int dx = Math.abs(lock.centerX() - waypoint.centerX());
            int dy = Math.abs(lock.centerY() - waypoint.centerY());
            if (dx < waypoint.width() && dy < waypoint.height()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Verlies vorzeitig verlassen
    // ------------------------------------------------------------------
    /**
     * Prueft alle Vorlagen, die mit "ally_left" beginnen. Liefert den Namen der
     * Vorlage, die angeschlagen hat – oder null.
     */
    private String allyLeftSeen(Mat screen, BotContext ctx) {
        for (String name : variantsOf(ctx, T_ALLY_LEFT)) {
            if (ctx.visible(screen, name)) return name;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Sperrliste: Mitspieler, mit denen es sich nicht lohnt
    // ------------------------------------------------------------------
    /**
     * Vorlagen der gesperrten Spielernamen. Alles, was mit "block_" anfaengt,
     * zaehlt dazu -- also block_braunschweiger.png, block_slowpoke.png und so
     * weiter. Aufgenommen werden sie mit Capture-Images.exe.
     *
     * Es muessen keine da sein. Ohne Vorlagen passiert hier gar nichts.
     */
    private static final String T_BLOCK_PREFIX = "block_";

    /**
     * Ab welcher Hoehe die Namensschilder stehen, als Anteil der Bildhoehe.
     *
     * Die beiden Schilder sitzen ganz unten am Rand, links der eigene, rechts
     * der des Mitspielers. Welche Seite wem gehoert, wechselt -- deshalb wird
     * gar nicht erst nach Seiten unterschieden: Der eigene Name steht ja nicht
     * auf der Sperrliste.
     *
     * Der obere Bildbereich bleibt aussen vor. Sonst koennte ein Name in einer
     * Nachricht oder auf einer Karte den Ausstieg ausloesen.
     */
    private static final double NAMEPLATE_ROW_Y = 0.78;

    /**
     * Wie genau der Name getroffen sein muss.
     *
     * Hoeher als der uebliche Wert von 0.84. Ein falscher Treffer waere hier
     * teuer: Der Bot wuerde einen tadellosen Mitspieler stehenlassen und den
     * Durchlauf wegwerfen. Lieber einmal zu wenig erkennen.
     */
    private static final double BLOCK_THRESHOLD = 0.88;

    /** Wie oft hoechstens nachgesehen wird. */
    private static final long BLOCK_INTERVAL_MS = 4000;

    private long lastBlockCheck = 0;

    /** Fuer diesen Durchlauf schon entschieden? */
    private boolean blockGeprueft = false;

    /**
     * Steht unten am Rand ein gesperrter Mitspieler? Liefert den Namen der
     * Vorlage oder null.
     */
    private String gesperrterMitspieler(Mat screen, BotContext ctx) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - lastBlockCheck < BLOCK_INTERVAL_MS) return null;
        lastBlockCheck = jetzt;

        int untereGrenze = (int) (ctx.height() * NAMEPLATE_ROW_Y);

        for (String name : ctx.matcher().names()) {
            if (!name.startsWith(T_BLOCK_PREFIX)) continue;

            for (Matcher.Hit hit : ctx.matcher().findAll(screen, name, BLOCK_THRESHOLD, 4)) {
                if (hit.centerY() < untereGrenze) continue;
                Log.debug("Verlies", "Sperrlisten-Treffer: " + name
                        + " bei " + hit.centerX() + "/" + hit.centerY()
                        + ", Guete " + String.format(java.util.Locale.US, "%.2f", hit.score()));
                return name;
            }
        }
        return null;
    }
    /**
     * Sind BEIDE bereit -- ich und der Mitspieler?
     *
     * Auf gemeinsamen Bildschirmen steht der gruene Haken zweimal: einmal
     * links, einmal rechts. Welche Seite einem selbst gehoert, wechselt von
     * Durchlauf zu Durchlauf -- als Leiter steht man links, als Mitspieler
     * rechts.
     *
     * ACHTUNG, HIER LAG EIN BOESER FEHLER: Frueher wurde nur EIN Haken
     * gesucht, naemlich auf der Seite des Mitspielers. Welche Seite das ist,
     * kam von isLeader() -- und das erkennt man am eigenen Namensschild.
     * Findet der Bot das nicht, nimmt er an, er sei nicht der Leiter. In
     * einer einzigen Sitzung stand 842 Mal im Protokoll:
     *
     *     "Eigenes Namensschild nicht gefunden, gelte als kein Leiter."
     *
     * Dann suchte er den Haken links -- also auf der EIGENEN Seite, wenn er
     * in Wahrheit doch Leiter war. Sobald Autor bereit war, hielt der Bot das
     * fuer den Mitspieler und griff sofort an.
     *
     * Die Loesung braucht isLeader() gar nicht mehr: Es muessen BEIDE Haken
     * da sein, einer links und einer rechts. Wer wo steht, ist dann egal.
     *
     * Zwei Faelle geben bewusst true zurueck, obwohl nichts geprueft wurde:
     *
     *  - Die Vorlage fehlt. Dann laeuft alles wie vor dem Einbau weiter,
     *    statt dass der Bot ueberhaupt nicht mehr angreift.
     *  - Es ist gar kein Haken zu sehen. Das ist dann kein gemeinsamer
     *    Bildschirm, sondern ein Kampf allein -- da gibt es niemanden, auf
     *    den zu warten waere. Ohne diese Ausnahme haette der Bot vor jedem
     *    gewoehnlichen Stuetzpunkt gewartet.
     */
    private boolean partnerIsReady(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_PARTNER_READY)) return true;

        List<Matcher.Hit> haken = ctx.matcher().findAll(screen, T_PARTNER_READY, 0.85, 6);
        if (haken.isEmpty()) return true;

        int mitte = ctx.width() / 2;
        boolean links = false;
        boolean rechts = false;
        for (Matcher.Hit hit : haken) {
            if (hit.centerX() < mitte) links = true;
            else                       rechts = true;
        }

        if (links && rechts) return true;

        Log.debug("Verlies", "Erst ein Haken (" + (links ? "links" : "rechts")
                + "), der andere fehlt noch. Es wird gewartet.");
        return false;
    }

    /**
     * Wartet auf den Mitspieler und meldet, ob weitergemacht werden darf.
     *
     * Setzt beim ersten Mal die Uhr. Solange sie laeuft, ruehrt die
     * Stillstandsuhr den Bot nicht an -- er wartet hier ja mit Absicht.
     */
    private boolean warteAufMitspieler(Mat screen, BotContext ctx, String wo) {
        if (partnerIsReady(screen, ctx)) {
            waitingForPartnerSince = 0;
            return true;
        }
        if (waitingForPartnerSince == 0) {
            waitingForPartnerSince = System.currentTimeMillis();
            Log.info("Verlies", Sprache.t("The partner is not ready yet (" + wo + "). The bot "
                    + "waits at most " + (partnerReadyTimeoutMillis / 60000) + " minutes "
                    + "and does not touch any button until then.",
                    "Der Mitspieler ist noch nicht bereit (" + wo + "). Der Bot "
                    + "wartet hoechstens " + (partnerReadyTimeoutMillis / 60000) + " Minuten "
                    + "und ruehrt bis dahin keinen Knopf an."));
        }
        return false;
    }

    /**
     * Zaehlt den Durchlauf -- hoechstens einmal, egal wie oft gerufen.
     *
     * Im Unterschied zu endRun() wird hier NICHT geprueft, ob zwischendurch
     * einmal abgebrochen wurde. Das ist Absicht: Diese Methode wird nur an
     * einer einzigen Stelle gerufen, naemlich beim Klick auf "End journey" bei
     * 100 Prozent Fortschritt. Wer dort ankommt, hat das Verlies geschafft --
     * was vorher war, spielt keine Rolle mehr.
     *
     * Genau daran ist die alte Zaehlung gescheitert: Ein Abbruch aus einem
     * frueheren Anlauf hat einen vollstaendig gespielten Durchlauf noch
     * verschluckt. In der Statistik stand null, obwohl Ether hereinkam.
     */
    private void countRunNow(RunTracker runs, String woher) {
        if (runCounted) {
            Log.debug("Verlies", "Schon gezaehlt, kein zweites Mal (" + woher + ").");
            return;
        }
        runCounted = true;
        runAborted = false;
        runs.completeRun();
        tempoRundeFertig(runs);
        Log.info("Verlies", Sprache.t("Run " + runs.completedRuns() + " of "
                + runs.targetRuns() + " completed (" + woher + ").",
                "Durchlauf " + runs.completedRuns() + " von "
                + runs.targetRuns() + " geschafft (" + woher + ")."));
    }

    /**
     * Beendet den laufenden Durchlauf und zaehlt ihn -- hoechstens einmal, und
     * nur wenn er nicht abgebrochen wurde.
     *
     * Es gibt zwei Wege aus einem Verlies heraus: ueber das Abschlussfenster
     * und ueber die Rueckkehr in die Burg. Beide konnten fuer denselben
     * Durchlauf feuern, etwa wenn das Abschlussfenster nach dem Klick noch
     * einen Moment stehenblieb. In der Statistik stand dann eine Drei, wo
     * zwei Durchlaeufe gelaufen waren.
     *
     * Warum nicht einfach abortReason pruefen: Der Grund wird geloescht,
     * sobald der Ausstieg geschafft ist. Die Information "das war ein
     * Abbruch" muss aber bis hierher ueberleben, und dafuer ist runAborted da.
     */
    private void endRun(RunTracker runs, String woher) {
        if (runCounted) {
            Log.debug("Verlies", "Der Durchlauf war schon gezaehlt (" + woher + ").");
            return;
        }
        runCounted = true;

        if (runAborted) {
            Log.info("Verlies", Sprache.t("The attempt does not count (" + woher + ").", "Der Versuch zaehlt nicht mit (" + woher + ")."));
            return;
        }
        Log.info("Verlies", Sprache.t("Dungeon completed (" + woher + ").", "Verlies abgeschlossen (" + woher + ")."));
        runs.completeRun();
        tempoRundeFertig(runs);
    }

    /** Merkt den Grund und schreibt ihn einmal ins Protokoll. */
    private void beginAbort(String reason) {
        abortReason = reason;
        runAborted = true;   // ueberlebt das Loeschen von abortReason
        abortCloseClicks = 0;
        Log.warn("Verlies", Sprache.t(reason + ". The bot leaves the Dungeon and then "
                + "looks for a new partner.",
                reason + ". Der Bot verlaesst das Verlies und sucht "
                + "anschliessend einen neuen Mitspieler."));
    }

    /**
     * Steigt aus dem laufenden Verlies aus: erst einen offenen Stuetzpunkt oder
     * ein Fenster ueber das rote X schliessen, dann den roten Knopf
     * "End journey" druecken. Die Sicherheitsabfrage "Yes" uebernimmt danach
     * Schritt 2b ganz oben.
     */
    private Outcome handleAbort(Mat screen, BotContext ctx, RunTracker runs) {

        // Fertig: wir sind zurueck im Teilnahmefenster oder in der Burg.
        // Auch die Weltkarte zaehlt als draussen.
        //
        // Genau hier hing der Bot fest: Nach dem Ausstieg landete er in der
        // Inselansicht. Die stand nicht in dieser Liste, also galt der Ausstieg
        // als unfertig -- und der Bot suchte weiter nach einem X und einem
        // "End journey", die es dort gar nicht gibt. Er stand einfach herum.
        //
        // Aber nur, wenn nicht gleichzeitig "End journey" zu sehen ist. Den
        // Knopf gibt es ausschliesslich INNERHALB eines Verlieses. Ohne diese
        // Bedingung hielt sich der Bot schon zwei Sekunden nach dem Abbruch
        // fuer draussen und spielte in Wahrheit weiter.
        boolean drinnen = visibleAny(screen, ctx, T_END_JOURNEY)
                       || visibleAny(screen, ctx, T_END_JOURNEY_GRN);

        if (teilnahmeFenster(screen, ctx) || visibleAny(screen, ctx, T_MAP_BUTTON)
                || (ctx.visible(screen, T_DUNGEON_NODE) && !drinnen)) {
            Log.info("Verlies", Sprache.t("Exit completed. The attempt does not count.", "Ausstieg abgeschlossen. Der Versuch zaehlt nicht mit."));
            abortReason = null;

            // Der abgebrochene Durchlauf ist hiermit erledigt -- und der
            // naechste faengt sauber an.
            //
            // Genau das fehlte: runAborted blieb stehen und hat den NAECHSTEN
            // Lauf mit vergiftet. Im Protokoll sah das so aus, dass der Bot um
            // 05:56 abbrach und der danach vollstaendig gespielte Lauf um
            // 06:02 trotzdem als "zaehlt nicht mit" durchging. Der Zaehler
            // stand deshalb bei null, obwohl Ether hereinkam.
            runAborted = false;
            runCounted = false;
            blockGeprueft = false;
            waitingForPartnerSince = 0;
            duelConfirmed = false;
            abortCloseClicks = 0;
            readyPressedAt = 0;
            leaderWaitSince = 0;
            participateAttempts = 0;
            endJourneyAllowed = false;
            // Wichtig: sonst wuerde die Burg-Regel den Versuch gleich darauf
            // doch noch als abgeschlossenen Durchlauf zaehlen.
            insideDungeon = false;
            return Outcome.ACTED;
        }

        // Der rote Knopf ist da: das ist der direkte Weg hinaus.
        if (ctx.visible(screen, T_END_JOURNEY) || ctx.visible(screen, T_END_JOURNEY_GRN)) {
            endJourneyAllowed = true;
            if (click(screen, ctx, T_END_JOURNEY, Sprache.t("Leave the Dungeon (" + abortReason + ")", "Verlies verlassen (" + abortReason + ")"))
                    || click(screen, ctx, T_END_JOURNEY_GRN, Sprache.t("Leave the Dungeon (" + abortReason + ")", "Verlies verlassen (" + abortReason + ")"))) {
                runs.progress(Sprache.t("Leaving the Dungeon", "Verlies wird verlassen"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            return Outcome.WAITING;
        }

        // Aus dem Hauptbot (Code-Review 15.10.): "abort_stuck" wurde gesetzt, aber nie gelesen -- der Bot klickte danach
        // sofort wieder viermal aufs X, endlos. Jetzt ruht das X 30 s, und der Bildschirm gilt als unbekannt.
        if (onCooldown("abort_stuck")) return Outcome.UNKNOWN;

        // Ein Fenster liegt darueber (z.B. der offene Stuetzpunkt): mit X zu.
        if (abortCloseClicks < 4 && click(screen, ctx, T_CLOSE_X, Sprache.t("Close window (exit)", "Fenster schliessen (Ausstieg)"))) {
            abortCloseClicks++;
            runs.progress(Sprache.t("Window closed", "Fenster geschlossen"));
            ctx.sleepUntilChanged(1400);
            return Outcome.ACTED;
        }

        if (abortCloseClicks >= 4) {
            Log.error("Verlies", Sprache.t("The exit does not work: neither the \"End journey\" button "
                    + "nor the red X can be reached. Please check the pictures end_journey and "
                    + "close_x. The bot waits.",
                    "Der Ausstieg klappt nicht: weder der Knopf \"End journey\" "
                    + "noch das rote X sind erreichbar. Bitte die Vorlagen end_journey und "
                    + "close_x pruefen. Der Bot wartet."));
            abortCloseClicks = 0;
            cooldowns.put("abort_stuck", System.currentTimeMillis() + 30_000);
        }
        return Outcome.WAITING;
    }

    // ------------------------------------------------------------------
    //  Wiederbeleben
    // ------------------------------------------------------------------
    /** True, wenn ein Wiederbeleben-Knopf oder eine wirklich zu belebende Einheit sichtbar ist. */
    private boolean resurrectionActive(Mat screen, BotContext ctx) {
        // Eine Einheitenkarte ist offen: wiederbeleben oder (bei ersatz) schliessen.
        if (!resurrectButtons(screen, ctx).isEmpty()) return true;

        // Karte noch offen, aber kein Resurrect-Knopf mehr: sie muss zugeklickt
        // werden, sonst kommt der Bot hier nicht mehr heraus.
        if (unitCardOpen(screen, ctx)) return true;

        // WICHTIG: exakt dieselbe Liste wie handleResurrection. Weichen die beiden
        // voneinander ab, haelt der Bot das Wiederbeleben fuer noetig, findet dann
        // aber nichts zu tun – und bleibt fuer immer im Wartezustand haengen,
        // ohne je zu "Deploy the best" und "Ready" zu kommen.
        // Aus dem Hauptbot (Code-Review 15.10.): Am Altar siebte handleResurrection aus, hier nicht -- endloses Warten,
        // Opfer nie, Abbruch nach 60 s. Jetzt dieselbe Auswahl an beiden Stellen.
        List<Matcher.Hit> tot = new ArrayList<>(deadUnitsToRevive(screen, ctx));
        tot.removeIf(d -> amAltarLiegenLassen(d, screen, ctx));
        return !tot.isEmpty();
    }

    /** Am Opferaltar bleibt liegen, wer keine Rolle wert ist (nur die ALTAR_KEEPERS werden wiederbelebt). */
    private boolean amAltarLiegenLassen(Matcher.Hit d, Mat screen, BotContext ctx) {
        return ctx.visible(screen, T_SACRIFICE_SCREEN) && kannEinheitenUnterscheiden(ctx)
                && !ALTAR_KEEPERS.contains(altarSchluessel(unitKeyOf(d, screen, ctx)));
    }

    /**
     * Aus dem Hauptbot: "auto_N" (vom Team-Rundgang aufgenommene Bilder) -> der echte Name aus einheiten-auto.txt
     * ("1 | Bogen 2 | ..." -> "bogi2"). Code-Review 15.10.: Seit es name_auto_N gibt, passte keine gefallene Einheit mehr.
     */
    static String altarSchluessel(String key) {
        if (key == null || !key.startsWith("auto_")) return key == null ? "" : key;
        String nr = key.substring("auto_".length());
        try {
            for (String z : de.hustlebot.core.Dateien.zeilen(java.nio.file.Path.of("einheiten-auto.txt"))) {
                String[] teile = z.split("[|]");
                if (teile.length >= 2 && teile[0].trim().equals(nr)) return teile[1].trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            }
        } catch (Exception e) {
            // ohne Liste: wie bisher
        }
        return key;
    }

    /**
     * Die Einheiten, die tatsaechlich wiederbelebt werden sollen: tote Einheiten
     * aus der Truppenablage, ohne die als "ersatz" erkannten.
     */
    private List<Matcher.Hit> deadUnitsToRevive(Mat screen, BotContext ctx) {
        // Tote Einheiten werden NUR auf dem Truppen-Aufstellbildschirm gesucht,
        // erkennbar an der Zeile "Drag and drop dwellers to form your squad".
        // Ohne diese Zeile (z.B. auf dem "Gather troops"-Startbildschirm) wird
        // gar nicht gesucht – sonst zu viele Fehltreffer.
        if (deployLineY(screen, ctx) < 0) {
            ersatzSkip.clear();
            aliveSkip.clear();
            return new ArrayList<>();
        }

        List<Matcher.Hit> dead = rawDeadUnits(screen, ctx);
        if (dead.isEmpty()) {
            ersatzSkip.clear();   // Ablage leer -> Merklisten sind veraltet
            aliveSkip.clear();
            return dead;
        }

        dead.removeIf(this::isKnownErsatz);

        // Einheiten, unter denen "ersatz" steht, sind nur zum Opfern da.
        if (ctx.matcher().has(T_NAME_ERSATZ)) {
            List<Matcher.Hit> ersatzNames = ctx.matcher().findAll(screen, T_NAME_ERSATZ, 0.80, 8);
            dead.removeIf(d -> nameBelow(d, ersatzNames, ctx) != null);
        }
        return dead;
    }

    /** Y-Position der Zeile "Drag and drop dwellers ...", oder -1 wenn nicht sichtbar. */
    private int deployLineY(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_DEPLOY_SCREEN)) return -1;
        Optional<Matcher.Hit> line = ctx.find(screen, T_DEPLOY_SCREEN);
        return line.map(Matcher.Hit::centerY).orElse(-1);
    }

    /**
     * Wiederbeleben-Ablauf: entweder einen der zwei "Resurrect"-Knoepfe druecken
     * (falls eine Einheitenkarte offen ist), oder die naechste tote Einheit
     * anwaehlen. Einheiten mit dem Namen "ersatz" werden dabei ausgelassen.
     */
    private Outcome handleResurrection(Mat screen, BotContext ctx, RunTracker runs) {

        // Sicherung gegen Endlosschleifen: kommt der Ablauf ueber viele Bilder
        // nicht voran, wird das Wiederbeleben eine Minute lang uebersprungen.
        if (++resurrectRounds > 20) {
            warnung(Sprache.t("The resurrection is not getting anywhere. It is skipped for "
                    + "one minute. Probably a picture does not match -- please check resurrect_confirm "
                    + "and unit_dead_bg.",
                    "Das Wiederbeleben kommt nicht voran. Es wird eine Minute lang "
                    + "uebersprungen. Vermutlich passt eine Vorlage nicht – bitte resurrect_confirm "
                    + "und unit_dead_bg pruefen."));
            if (!onCooldown("resurrect_discord")) {
                ctx.melde(de.hustlebot.core.Sprache.t("Resurrection stuck", "Wiederbeleben klemmt"),
                        de.hustlebot.core.Sprache.t(
                        "The bot cannot get the resurrection going and skips it. Usually a picture is "
                        + "outdated – please capture the names of the fallen fighters again (Capture-Images).",
                        "Der Bot bringt das Wiederbeleben nicht voran und ueberspringt es. "
                        + "Meist ist eine Vorlage veraltet – bitte die Namen der gefallenen "
                        + "Einheiten (Capture-Images) neu aufnehmen."));
                cooldowns.put("resurrect_discord", System.currentTimeMillis() + 600_000);
            }
            cooldowns.put("resurrect_giveup", System.currentTimeMillis() + 60_000);
            resurrectRounds = 0;
            return Outcome.WAITING;
        }

        // 1. Karte offen: ein "Resurrect"-Knopf ist sichtbar.
        List<Matcher.Hit> buttons = resurrectButtons(screen, ctx);
        if (!buttons.isEmpty()) {

            // Karte einer ersatz-Einheit: nicht wiederbeleben, sondern schliessen.
            // Nur der Name im oberen Kartenbereich zaehlt, nicht die Truppenablage unten.
            if (ersatzNameInCardHeader(screen, ctx)) {
                if (lastDeadClick != null) {
                    ersatzSkip.add(new int[]{lastDeadClick.centerX(), lastDeadClick.centerY()});
                    Log.info("Verlies", Sprache.t("Sacrifice unit recognized -- from now on it is skipped "
                            + "when resurrecting.",
                            "ersatz-Einheit erkannt – wird ab jetzt beim Wiederbeleben "
                            + "uebersprungen."));
                    lastDeadClick = null;
                }
                if (click(screen, ctx, T_CLOSE_X, Sprache.t("Close the sacrifice unit's card", "Karte der ersatz-Einheit schliessen"))) {
                    ctx.sleepUntilChanged(900);
                    return Outcome.ACTED;
                }
                return Outcome.WAITING;
            }

            // Der erste und der zweite "Resurrect"-Knopf sehen fast gleich aus,
            // deshalb wird nicht nach Namen unterschieden, sondern einfach der
            // beste Treffer geklickt (Zwei-Schritt-Ablauf: zweimal pro Einheit).
            if (resurrectClicks >= 4) {
                warnung(Sprache.t("A unit cannot be resurrected after several clicks. "
                        + "Resurrection is skipped for one minute. Please check "
                        + "resurrect / resurrect_confirm and unit_dead_bar/unit_dead_bg.",
                        "Eine Einheit laesst sich nach mehreren Klicks nicht "
                        + "wiederbeleben. Wiederbeleben wird eine Minute uebersprungen. Bitte "
                        + "resurrect / resurrect_confirm und unit_dead_bar/unit_dead_bg pruefen."));
                cooldowns.put("resurrect_giveup", System.currentTimeMillis() + 60_000);
                resurrectRounds = 0;
                resurrectClicks = 0;
                return Outcome.WAITING;
            }
            if (onCooldown("resurrect_click")) return Outcome.WAITING;

            Matcher.Hit b = buttons.get(0);
            Log.action("Klick", Sprache.t("Resurrect (" + b.name() + ", "
                    + String.format("%.3f", b.score()) + ")",
                    "Wiederbeleben (" + b.name() + ", "
                    + String.format("%.3f", b.score()) + ")"));
            ctx.input().click(b);
            cooldowns.put("resurrect_click", System.currentTimeMillis() + 2200);
            if (resurrectClicks == 0) ctx.laufstatistik().countResurrection();
            resurrectClicks++;
            lastResurrectClickAt = System.currentTimeMillis();
            resurrectRounds = 0;   // es geht voran
            runs.progress(Sprache.t("Resurrection", "Wiederbelebung"));
            ctx.sleepUntilChanged(1300);
            return Outcome.ACTED;
        }

        // Karte der Einheit ist noch offen, obwohl kein "Resurrect" mehr da ist:
        // Die Einheit lebt. Die Karte geht nur zu, wenn man NEBEN sie klickt –
        // ohne das bliebe der Bot hier stehen.
        if (unitCardOpen(screen, ctx)) {

            // Und sie wird gemerkt. Sonst geht es sofort von vorne los: eine
            // Einheit mit einem einzigen Lebenspunkt sieht auf der Leiste aus
            // wie tot, also klickt der Bot sie gleich wieder an. Genau diese
            // Schleife -- anklicken, schliessen, anklicken -- lief endlos.
            if (lastDeadClick != null) {
                aliveSkip.add(new int[]{lastDeadClick.centerX(), lastDeadClick.centerY()});
                Log.info("Verlies", Sprache.t("The unit is still alive (probably just badly hurt). "
                        + "From now on it is skipped when resurrecting.",
                        "Die Einheit lebt noch (vermutlich nur schwer verletzt). "
                        + "Sie wird beim Wiederbeleben ab jetzt uebersprungen."));
                lastDeadClick = null;
            }
            return closeUnitCard(ctx, runs);
        }

        // 2. Keine Karte: die naechste tote Einheit anwaehlen.
        List<Matcher.Hit> dead = deadUnitsToRevive(screen, ctx);

        // Am Opferaltar wird sparsam wiederbelebt.
        //
        // Dort sucht der Bot eine Einheit zum Opfern. Eine Schriftrolle fuer
        // Kaempfer 5, Kaempfer 6 oder den Opfer-Kaempfer auszugeben waere Unsinn --
        // die sollen ja gerade in den Altar. Die echten Kaempfer werden
        // weiterhin gerettet.
        //
        // Nur wenn der Bot die Gefallenen ueberhaupt auseinanderhalten kann.
        // Ohne Namensvorlagen weiss er nicht, wen er vor sich hat, und wuerde
        // beim Aussieben genauso gut den falschen liegen lassen -- dann lieber
        // alle wiederbeleben wie bisher.
        if (ctx.visible(screen, T_SACRIFICE_SCREEN) && kannEinheitenUnterscheiden(ctx)) {
            int vorher = dead.size();
            dead.removeIf(d -> !ALTAR_KEEPERS.contains(altarSchluessel(unitKeyOf(d, screen, ctx))));
            if (dead.size() < vorher) {
                Log.debug("Verlies", "Am Opferaltar bleiben " + (vorher - dead.size())
                        + " gefallene Einheiten liegen: Opfermaterial ist keine Rolle wert.");
            }
        }

        if (dead.isEmpty()) {
            resurrectRounds = 0;
            resurrectClicks = 0;
            lastDeadClick = null;
            Log.debug("Verlies", "Keine wiederzubelebenden Einheiten mehr.");
            return Outcome.WAITING;
        }

        // Reihenfolge: Kaempfer 1 zuerst ... dann unbekannte, dann von links nach rechts.
        Map<Matcher.Hit, Integer> rank = new HashMap<>();
        for (Matcher.Hit d : dead) rank.put(d, unitRank(d, screen, ctx));
        dead.sort(Comparator.<Matcher.Hit>comparingInt(rank::get)
                .thenComparingInt(Matcher.Hit::centerX));

        Matcher.Hit target = dead.get(0);
        lastDeadClick = target;
        resurrectClicks = 0;   // neue Karte, neuer Zwei-Schritt-Ablauf
        resurrectRounds = 0;   // es geht voran
        Log.action("Klick", Sprache.t("Select dead unit  [" + target.centerX() + "," + target.centerY() + "]",
                "Tote Einheit anwaehlen  [" + target.centerX() + "," + target.centerY() + "]"));
        ctx.input().click(target);
        runs.progress(Sprache.t("Dead unit selected", "Tote Einheit gewaehlt"));
        ctx.sleepUntilChanged(1100);
        return Outcome.ACTED;
    }

    /**
     * True, wenn der Name "ersatz" im oberen Bereich des Bildschirms steht, also
     * im Kopf der geoeffneten Einheitenkarte – nicht in der Truppenablage unten.
     */
    private boolean ersatzNameInCardHeader(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_NAME_ERSATZ)) return false;
        int limit = (int) (ctx.height() * 0.45);
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_NAME_ERSATZ, 0.80, 6)) {
            if (h.centerY() < limit) return true;
        }
        return false;
    }

    /**
     * Die grosse Einheitenkarte ist offen, erkennbar an der Zeile
     * "Switch dwellers" ganz unten. Freiwillige Vorlage: fehlt sie, wird die
     * Karte nach dem Wiederbeleben ueber die Sicherung in closeUnitCard()
     * trotzdem geschlossen.
     */
    private static final String T_UNIT_CARD = "switch_dwellers";

    private boolean unitCardOpen(Mat screen, BotContext ctx) {
        if (ctx.matcher().has(T_UNIT_CARD)) {
            return ctx.visible(screen, T_UNIT_CARD);
        }
        // Ohne Vorlage: kurz nach einem Resurrect-Klick ist die Karte offen.
        // Die Zeitgrenze verhindert, dass spaeter auf einem ganz anderen
        // Bildschirm ins Leere geklickt wird.
        return resurrectClicks > 0
                && System.currentTimeMillis() - lastResurrectClickAt < 8000;
    }

    /**
     * Waagerechte und senkrechte Lage des Punktes, auf den geklickt wird, um
     * die Einheitenkarte zu schliessen – als Anteil der Fensterbreite/-hoehe.
     *
     * Die Karte sitzt in der Mitte. Rechts daneben liegt eine freie Flaeche im
     * Truppenfenster, auf der kein Portraet und kein Knopf liegt. Trifft der
     * Klick doch einmal daneben, sind das die beiden Zahlen zum Nachstellen.
     */
    private static final double UNIT_CARD_DISMISS_X = 0.82;
    private static final double UNIT_CARD_DISMISS_Y = 0.55;

    /** Klickt neben die Einheitenkarte, damit sie zugeht. */
    private Outcome closeUnitCard(BotContext ctx, RunTracker runs) {
        if (onCooldown("close_unit_card")) return Outcome.WAITING;

        int x = (int) (ctx.width() * UNIT_CARD_DISMISS_X);
        int y = (int) (ctx.height() * UNIT_CARD_DISMISS_Y);

        Log.action("Klick", Sprache.t("Close unit card  [next to the card at " + x + "," + y + "]",
                "Einheitenkarte schliessen  [neben die Karte bei " + x + "," + y + "]"));
        ctx.input().click(x, y, 6);
        cooldowns.put("close_unit_card", System.currentTimeMillis() + 2000);
        resurrectClicks = 0;
        lastDeadClick = null;
        runs.progress(Sprache.t("Unit card closed", "Einheitenkarte geschlossen"));
        ctx.sleepUntilChanged(1200);
        return Outcome.ACTED;
    }

    /** Alle sichtbaren "Resurrect"-Knoepfe (jede Vorlage, die mit "resurrect" beginnt). */
    private List<Matcher.Hit> resurrectButtons(Mat screen, BotContext ctx) {
        List<Matcher.Hit> all = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (!name.equals(T_RESURRECT) && !name.startsWith(T_RESURRECT + "_")) continue;
            all.addAll(ctx.matcher().findAll(screen, name, 0.83, 3));
        }
        all.sort(Comparator.comparingDouble(Matcher.Hit::score).reversed());
        return all;
    }

    /**
     * Tote Einheiten in der Truppenablage, als Klickziele (Portraet-Mitte),
     * noch ohne ersatz-Filter. Es wird ausschliesslich UNTERHALB der Zeile
     * "Drag and drop dwellers ..." gesucht, damit rote/graue Flaechen weiter
     * oben im Bild keine Fehltreffer ausloesen.
     */
    private List<Matcher.Hit> rawDeadUnits(Mat screen, BotContext ctx) {
        List<Matcher.Hit> dead = new ArrayList<>();

        int minY = deployLineY(screen, ctx);
        if (minY < 0) return dead;

        // Rote Lebensleiste: das zuverlaessigste Zeichen. Sie liegt UNTER dem
        // Portraet, deshalb wird das Klickziel nach oben aufs Portraet verschoben
        // und portraetgross beschrieben, damit die Namenspruefung passt.
        //
        // Ist diese Vorlage vorhanden, wird ihr allein vertraut. Frueher wurde
        // bei "keine Leiste gefunden" zusaetzlich unit_dead_bg geprueft – und
        // diese einfarbige Vorlage schlaegt leicht auf lebenden Einheiten an.
        if (ctx.matcher().has(T_UNIT_DEAD_BAR)) {
            int portrait = (int) (ctx.height() * 0.09);
            int lift = (int) (ctx.height() * 0.055);
            // Sparsam 20.11.: nur unterhalb der Zeile suchen -- Funde darueber wurden ohnehin verworfen (gemessen:
            // unit_dead_bar kostete im Verlies 10,4 s je Run im ganzen Bild). Gleiche Werte je Stelle, siehe findAllImBereich.
            int oben = Math.max(0, minY - 40);
            org.opencv.core.Rect unten = new org.opencv.core.Rect(0, oben, screen.width(), screen.height() - oben);
            for (Matcher.Hit bar : ctx.matcher().findAllImBereich(screen, T_UNIT_DEAD_BAR, 0.88, 12, unten)) {
                if (bar.centerY() <= minY) continue;
                int cx = bar.centerX();
                int cy = bar.centerY() - lift;
                dead.add(new Matcher.Hit(bar.name(), cx - portrait / 2, cy - portrait / 2,
                        portrait, portrait, bar.score()));
            }
            return dead;
        }

        // Nur wenn die Leisten-Vorlage gar nicht aufgenommen wurde.
        if (ctx.matcher().has(T_UNIT_DEAD_BG)) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, T_UNIT_DEAD_BG, 0.94, 12)) {
                if (h.centerY() > minY) dead.add(h);
            }
        }
        return dead;
    }

    /** True, wenn an dieser Stelle schon einmal eine ersatz-Einheit erkannt wurde. */
    private boolean isKnownErsatz(Matcher.Hit unit) {
        return inList(ersatzSkip, unit) || inList(aliveSkip, unit);
    }

    private static boolean inList(List<int[]> list, Matcher.Hit unit) {
        for (int[] p : list) {
            if (Math.abs(p[0] - unit.centerX()) < Math.max(unit.width(), 30)
                    && Math.abs(p[1] - unit.centerY()) < Math.max(unit.height(), 30)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Liefert den Namenstreffer, der zu dieser Einheit gehoert – oder null.
     * Die Toleranz richtet sich nach der Fenstergroesse und nicht nach der
     * Vorlagengroesse: die Vorlage kann ein schmaler Streifen sein, das Portraet
     * darueber ist aber deutlich breiter.
     */
    private Matcher.Hit nameBelow(Matcher.Hit unit, List<Matcher.Hit> names, BotContext ctx) {
        int maxDx = (int) (ctx.width() * 0.035);    // etwa halbe Portraetbreite
        int maxDy = (int) (ctx.height() * 0.13);    // Name sitzt unter dem Portraet
        for (Matcher.Hit n : names) {
            int dx = Math.abs(n.centerX() - unit.centerX());
            int dy = n.centerY() - unit.centerY();
            if (dx <= maxDx && dy > -maxDy / 2 && dy <= maxDy) {
                return n;
            }
        }
        return null;
    }


    /**
     * Wer am Opferaltar noch eine Schriftrolle wert ist.
     *
     * Kaempfer 5, Kaempfer 6 und der Opfer-Kaempfer stehen bewusst nicht
     * drin: Die sind das Opfermaterial. Eine Rolle auszugeben, um die Einheit
     * gleich danach zu opfern, waere zweimal verloren.
     *
     * Nur am Altar. Im uebrigen Verlies belebt der Bot alles wieder, was
     * gefallen ist -- dort geht es ja darum, weiterkaempfen zu koennen.
     *
     * Die Eintraege sind die Platznummern, so wie unitKeyOf() sie liefert.
     *
     * Kennt der Bot einen Gefallenen nicht (Leerstring, weil der Freund nur
     * einen Teil der Namen aufgenommen hat), bleibt der hier ebenfalls liegen.
     * Das ist gewollt und kostet nichts: Am naechsten Stuetzpunkt belebt der
     * Bot ohnehin alles wieder -- und so wird keine Rolle fuer einen
     * unbekannten Opfer-Kaempfer verschwendet.
     */
    private static final java.util.Set<String> ALTAR_KEEPERS =
            java.util.Set.of("1", "2", "3", "4");

    // ---- FREUNDE-FASSUNG: die eigenen Bilder, fuer Capture-Images.exe ----
    //
    // Das Aufnahmewerkzeug, die Kathedrale und die Selbstpruefung beim
    // Paketbau holen die Namen von hier, statt sie abzuschreiben. Genau so
    // ein Abschreibfehler war der Grund fuer den Umbau: Anleitung und
    // Werkzeug boten Namen an, nach denen der Bot nie gesucht hat.

    /** Das eigene Namensschild auf der Verlieskarte. */
    public static String bildNamensschild()            { return T_NAMEPLATE_ME; }
    /** Portraet des Opfer-Kaempfers in der Truppenablage. */
    public static String bildOpferPortraet()           { return T_SACRIFICE_UNIT; }
    /** Name unter dem Opfer-Kaempfer. */
    public static String bildOpferName()               { return T_NAME_ERSATZ; }
    /** Portraets der Kaempfer 1 bis 6, wichtigster zuerst. */
    public static List<String> bilderKaempfer()        { return List.of(PRIORITY_UNITS); }
    /** Namen der Kaempfer 1 bis 6, in derselben Reihenfolge wie die Portraets. */
    public static List<String> bilderKaempferNamen()   { return List.of(PRIORITY_NAMES); }
    /** Der gruene "Confirm"-Knopf beim Betreten von Verlies/Kathedrale (Zusatzbild). */
    public static String bildConfirm()                 { return T_CONFIRM; }
    /** Die Plaetze, die am Opferaltar noch eine Rolle wert sind. */
    public static java.util.Set<String> altarPlaetze() { return ALTAR_KEEPERS; }

    /** Der Platz zu einer Vorlage: "unit_3" und "name_3" ergeben beide "3". */
    public static String platzVon(String vorlage) {
        return vorlage.substring(vorlage.indexOf('_') + 1);
    }

    /**
     * Welcher Kaempfer das ist: "1" bis "6" -- oder "", wenn unbekannt.
     *
     * Gesucht wird zuerst nach dem Namensschild, denn nur das bleibt lesbar,
     * wenn die Einheit gefallen ist. Erst danach nach dem Portraet, damit es
     * auch ohne Namensvorlagen irgendwie geht.
     *
     * Zurueck kommt der blanke Platz ohne Vorsilbe, damit es egal ist, welche
     * der beiden Vorlagen angeschlagen hat.
     */
    private String unitKeyOf(Matcher.Hit unit, Mat screen, BotContext ctx) {
        for (String t : PRIORITY_NAMES) {
            if (!ctx.matcher().has(t)) continue;
            if (nameBelow(unit, ctx.matcher().findAll(screen, t, 0.82, 4), ctx) != null) {
                return platzVon(t);
            }
        }
        for (String t : PRIORITY_UNITS) {
            if (!ctx.matcher().has(t)) continue;
            if (nameBelow(unit, ctx.matcher().findAll(screen, t, 0.82, 4), ctx) != null) {
                return platzVon(t);
            }
        }
        return "";
    }

    /**
     * Ob gefallene Einheiten ueberhaupt namentlich unterschieden werden
     * koennen. Ist keine einzige Vorlage dafuer geladen, waere jedes Aussieben
     * blindes Raten -- dann wird lieber gar nicht ausgesiebt.
     */
    private boolean kannEinheitenUnterscheiden(BotContext ctx) {
        for (String t : PRIORITY_NAMES) if (ctx.matcher().has(t)) return true;
        return false;
    }
    /** Rang einer toten Einheit nach den Prioritaets-Namensvorlagen. Kleiner = zuerst. */
    private int unitRank(Matcher.Hit unit, Mat screen, BotContext ctx) {
        // Erst die Namensschilder: Nur die bleiben lesbar, wenn die Einheit
        // gefallen ist. Die Portraets sind dann ausgegraut und passen nicht
        // mehr -- frueher stand deshalb jede tote Einheit auf Rang 50, und
        // die ganze Reihenfolge war wirkungslos.
        for (int i = 0; i < PRIORITY_NAMES.length; i++) {
            String t = PRIORITY_NAMES[i];
            if (!ctx.matcher().has(t)) continue;
            if (nameBelow(unit, ctx.matcher().findAll(screen, t, 0.82, 4), ctx) != null) return i;
        }
        for (int i = 0; i < PRIORITY_UNITS.length; i++) {
            String t = PRIORITY_UNITS[i];
            if (!ctx.matcher().has(t)) continue;
            if (nameBelow(unit, ctx.matcher().findAll(screen, t, 0.82, 4), ctx) != null) return i;
        }
        return 50;
    }

    // ------------------------------------------------------------------
    //  Knoepfe mit mehreren Aussehen
    // ------------------------------------------------------------------
    /**
     * Viele Knoepfe sehen je nach Bildschirm anders aus: "Go here" ist mal
     * gruen, mal orange, "Finish selection" mal einzeilig, mal zweizeilig.
     * Deshalb gilt: jede geladene Vorlage, deren Name mit dem Grundnamen
     * beginnt, zaehlt als derselbe Knopf ("go_here", "go_here_alt", "go_here_2").
     * So laesst sich ein neues Aussehen ohne Codeaenderung nachtragen.
     */
    private List<String> variantsOf(BotContext ctx, String base) {
        List<String> names = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (name.equals(base) || name.startsWith(base + "_")) names.add(name);
        }
        return names;
    }

    /** Klickt das erste sichtbare Aussehen dieses Knopfes. */
    private boolean clickAny(Mat screen, BotContext ctx, String base, String reason) {
        for (String name : variantsOf(ctx, base)) {
            if (click(screen, ctx, name, reason + " (" + name + ")")) return true;
        }
        return false;
    }

    /** True, wenn irgendein Aussehen dieses Knopfes zu sehen ist. */
    private boolean visibleAny(Mat screen, BotContext ctx, String base) {
        for (String name : variantsOf(ctx, base)) {
            if (ctx.visible(screen, name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Hilfsmittel
    // ------------------------------------------------------------------
    private boolean click(Mat screen, BotContext ctx, String template, String reason) {
        if (!ctx.matcher().has(template)) return false;
        if (onCooldown(template)) return false;
        if (!ctx.clickIfVisible(screen, template, reason)) return false;

        markClicked(template);
        unknownScreens = 0;
        return true;
    }

    private boolean onCooldown(String key) {
        Long until = cooldowns.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Stehen "Quick Attack"/"To battle" da, aber nur grau (Spiel synchronisiert noch)? Nur wenn ALLE gefundenen
     * Kampf-Knoepfe grau sind -- ist einer farbig, wird wie bisher geklickt. Ohne Bild: false (wie bisher).
     */
    private static boolean kampfKnoepfeGrau(Mat screen, BotContext ctx) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return false;
        boolean gefunden = false;
        for (String t : new String[]{T_QUICK_ATTACK, T_TO_BATTLE}) {
            Optional<Matcher.Hit> h = ctx.find(screen, t);
            if (h.isEmpty()) continue;
            gefunden = true;
            if (farbsaettigung(b, h.get()) > 0.30) return false;
        }
        return gefunden;
    }

    /** Mittlere Farbsaettigung (0 = grau, 1 = kraeftig) im Bereich eines Treffers, jeder dritte Bildpunkt. */
    private static double farbsaettigung(java.awt.image.BufferedImage b, Matcher.Hit h) {
        int x0 = Math.max(0, h.x()), y0 = Math.max(0, h.y());
        int x1 = Math.min(b.getWidth(), h.x() + h.width()), y1 = Math.min(b.getHeight(), h.y() + h.height());
        double summe = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 3) {
            for (int x = x0; x < x1; x += 3) {
                int p = b.getRGB(x, y);
                int r = p >> 16 & 255, g = p >> 8 & 255, bl = p & 255;
                int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
                if (max > 0) summe += (max - min) / (double) max;
                n++;
            }
        }
        return n == 0 ? 1.0 : summe / n;
    }

    private void markClicked(String key) {
        cooldowns.put(key, System.currentTimeMillis() + CLICK_COOLDOWN_MS);
    }

    /**
     * Feste Bildpunkte fuer das Ziehen der Einheiten im Zweikampf.
     * Die Werte sind Anteile der Fensterbreite und passen sich damit an,
     * falls das Fenster doch einmal anders gross ist.
     */
    private record Layout(int width, int height) {

        static Layout forSize(int width, int height) {
            return new Layout(width, height);
        }

        /** Waagerechte Position der Einheiten in der unteren Ablage. */
        int unitX(int index) {
            return (int) (width * (0.245 + index * 0.0565));
        }

        /** Hoehe der unteren Ablage. */
        int unitY() {
            return (int) (height * 0.885);
        }

        /** Waagerechte Position der beiden leeren Aufstellungsfelder. */
        int slotX(int index) {
            return (int) (width * (0.401 + index * 0.0585));
        }

        /** Hoehe der Aufstellungsfelder. */
        int slotY() {
            return (int) (height * 0.745);
        }
    }
}
