package de.hustlebot.bot;

import de.hustlebot.core.*;
import org.opencv.core.Mat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faehrt den eigentlichen Bot. Laeuft in einem eigenen Thread, damit die
 * Weboberflaeche jederzeit bedienbar bleibt.
 */
public final class BotEngine {

    public enum State { BEREIT, LAEUFT, PAUSIERT, GESTOPPT, FEHLER, FERTIG }

    private final BotContext ctx;
    private final Map<String, GameMode> modes = new LinkedHashMap<>();

    private volatile State state = State.BEREIT;
    private volatile String statusText = de.hustlebot.core.Sprache.t("Ready.", "Bereit.");
    private volatile GameMode activeMode;
    private volatile RunTracker tracker;
    private volatile Thread worker;
    private volatile boolean stopRequested = false;
    private volatile boolean paused = false;
    /**
     * Autor 24.10.: Seit wann ein MODUS den Bot angehalten hat (Outcome.PAUSE -- Portal: Tasche leer, zweimal verloren ...;
     * nicht Autors !pause und nicht die Maus). 0 = keine solche Pause.
     */
    private volatile long modusPauseUm = 0;
    /** Die Modus-Pause soll enden, als waere der Modus fertig (Clan-Krieg, danach der naechste Auftrag). */
    private volatile boolean modusPauseBeenden = false;

    public long modusPauseUm() { return modusPauseUm; }

    /**
     * Autor 24.10.: "Clan-Krieg setzen und danach mit Arena, Event-Loop oder Verlies weitermachen" -- auch wenn sich der
     * laufende Modus selbst angehalten hat. Beendet die Pause wie ein fertiger Auftrag (FERTIG); die Warteschlange geht
     * weiter. Nur fuer eine Pause, die der Modus ausgeloest hat. true = wird beendet.
     */
    public synchronized boolean modusPauseBeenden() {
        if (state != State.PAUSIERT || modusPauseUm == 0) return false;
        modusPauseBeenden = true;
        return true;
    }

    /**
     * Wann die Hauptschleife zuletzt eine Runde gedreht hat -- unabhaengig
     * davon, ob dabei Fortschritt gemeldet wurde (siehe {@link RunTracker}).
     *
     * WOZU. Der bisherige Totmann-Schalter (guardSystem) fragt sich selbst
     * aus der Hauptschleife heraus, ob 15 Minuten nichts vorangeht. Bleibt
     * die Schleife aber mitten in einem Schritt haengen -- ein Modus, der
     * sich verschluckt, oder ein blockierender Aufruf in der Bilderkennung
     * -- kommt sie nie mehr bis zu dieser Selbstpruefung. Diese Uhr wird
     * dagegen von einem UNABHAENGIGEN Wach-Faden gelesen (siehe
     * DiscordBridge), der nichts mit der Hauptschleife zu tun hat und daher
     * merkt, wenn sie wirklich steht -- nicht nur, wenn nichts vorangeht.
     */
    private volatile long lastLoopHeartbeat = System.currentTimeMillis();

    /** Der laufende Motor -- damit lange Schritte eines Modus den Stopp selbst abfragen koennen. */
    private static volatile BotEngine aktiv;

    /**
     * Stopp/Not-Aus angefordert? Fuer lange Schritte (Hauptbot 04.10./11.10., Stigmas: "nach Stopp kein
     * Klick mehr" -- der Motor prueft sonst nur zwischen zwei Schritten).
     */
    public static boolean stoppAngefordert() {
        BotEngine e = aktiv;
        return e != null && e.stopRequested;
    }

    /** Pausiert (F10, !pause, Mauswache)? */
    public static boolean pausiert() {
        BotEngine e = aktiv;
        return e != null && e.paused;
    }

    /**
     * Pause angefordert (!pause oder der Bot wartet auf einen Menschen)? Freunde, Pruefung 11.10.: der Motor prueft die
     * Pause nur zwischen zwei Schritten -- ein Level-100-Schritt der Stigmas klickte sonst noch Minuten weiter.
     */
    public static boolean pauseAngefordert() {
        BotEngine e = aktiv;
        return e != null && e.paused;
    }

    /** Wie viele Bilder hintereinander kein Modus etwas erkannt hat. */
    private int unbekanntFolge = 0;

    private int idleTimeoutMinutes = 5;
    private int leaderPickTimeoutMinutes = 3;
    private int partnerReadyTimeoutMinutes = 5;
    private boolean requireAllTemplates = true;

    /** Zusaetzliche Abbruchbedingungen. 0 heisst ausgeschaltet. */
    private volatile int stopAfterMinutes = 0;
    private volatile String stopAtClock = "";
    /** Fester Zeitpunkt der Abschaltung in Millisekunden. 0 heisst ausgeschaltet. */
    private volatile long stopAtMillis = 0;

    // !ende / !off ueberleben seit 12.10. einen Neustart (Autor: "jede Einstellung"); schon vorbei = verworfen.
    {
        long um = de.hustlebot.core.Dauerwerte.zahl(de.hustlebot.core.Dauerwerte.ENDE_UM, 0L);
        String uhr = de.hustlebot.core.Dauerwerte.lesen(de.hustlebot.core.Dauerwerte.ENDE_UHRZEIT, "");
        if (um > System.currentTimeMillis() && uhr.length() == 5 && uhr.charAt(2) == ':') {
            stopAtClock = uhr;
            stopAtMillis = um;
        } else if (um != 0) {
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.ENDE_UM, null);
            de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.ENDE_UHRZEIT, null);
        }
    }

    private void stoppzeitMerken() {
        de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.ENDE_UHRZEIT, stopAtClock);
        de.hustlebot.core.Dauerwerte.schreiben(de.hustlebot.core.Dauerwerte.ENDE_UM, stopAtMillis > 0 ? stopAtMillis : null);
    }
    private volatile long sessionStartedAt = 0;

    // Die zuletzt verwendeten Startangaben, damit der Neustart sie uebernimmt.
    private volatile String lastModeId = "dungeon";
    private volatile int lastRuns = 10;
    private volatile String lastTeammate = "random";
    private volatile String lastFriendName = "";

    /**
     * Seit wann darauf gewartet wird, dass der laufende Durchlauf endet.
     * 0 heisst: es wird nicht gewartet.
     *
     * Gesetzt, sobald eine Zeitgrenze erreicht ist -- bei "!ende 06:30"
     * genauso wie bei "!aus 06:00". Der Bot spielt dann noch zu Ende, statt
     * mitten im Verlies stehenzubleiben und die Fackel zu verschenken.
     */
    private volatile long finishingSince = 0;

    /** Wie viele Durchlaeufe fertig waren, als die Zeit ablief. */
    private volatile int runsWhenFinishing = 0;

    /**
     * Wurde das Ziel wirklich erreicht -- also alle geplanten Durchlaeufe
     * gespielt? Nur dann darf eine Kette (!after) mit dem naechsten Modus
     * weitermachen.
     *
     * Der Zustand FERTIG allein reicht dafuer nicht: Auch eine Zeitgrenze
     * (!ende 06:30) endet mit FERTIG. Dann soll aber Schluss sein und nicht
     * noch die Kathedrale hinterherlaufen.
     */
    private volatile boolean zielErreicht = false;

    /**
     * Hat der letzte Lauf wegen der Zeitgrenze (!ende / !off) aufgehoert? Dann startet die Warteschlange nichts mehr --
     * auch wenn die Endzeit inzwischen geloescht oder verschoben wurde (Autor 18.11.: "Stoppzeit muss fuer die ganze
     * Warteschlange gelten").
     */
    private volatile boolean durchZeitgrenzeBeendet = false;

    /**
     * Ein Moduswechsel wurde gewuenscht, waehrend schon etwas laeuft.
     *
     * "!start kathedrale 50" mitten im Verlies: Der angefangene Durchlauf
     * wird noch zu Ende gespielt, sonst wirft man eine halb gelaufene Fackel
     * weg. Erkannt wird das am Zaehler, genau wie bei der Zeitgrenze.
     */
    private volatile boolean wechselAngefordert = false;
    private volatile int runsBeimWechsel = 0;
    private volatile long wechselSeit = 0;

    /**
     * Wann der Bot zuletzt das Spiel neu gestartet hat.
     *
     * Ein abgestuerztes Hustle Castle soll er selbst wieder hochbringen --
     * aber nicht im Kreis. Steckt etwas anderes dahinter, waere ein Neustart
     * alle paar Sekunden nur Laerm im Protokoll.
     */
    private volatile long letzterSpielNeustart = 0;
    /** Wann das Spiel zuletzt abgestuerzt war und neu gestartet wurde (Verlies: Lauf zaehlt dann nicht). */
    public static volatile long spielAbsturzUm = 0;
    private static final long SPIEL_NEUSTART_PAUSE_MS = 10 * 60_000;

    /**
     * Wie lange hoechstens ueberzogen wird.
     *
     * Ein Verlies dauert meist drei bis zehn Minuten. Nach acht Minuten haengt
     * der Durchlauf vermutlich ohnehin -- und ein Rechner, der wegen eines
     * haengenden Durchlaufs die ganze Nacht weiterlaeuft, waere schlimmer als
     * ein verlorener Versuch.
     */
    private static final long FINISH_GRACE_MS = 8 * 60_000;

    /**
     * Frist fuer den MODUSWECHSEL (Clan-Krieg, Leuchtturm, Kolosseum): so lange
     * darf der laufende Durchlauf noch dauern. Laenger als die acht Minuten der
     * Zeitgrenze, weil ein Verlies-Run bis zu zwoelf Minuten braucht (gemessen
     * 27.09.). Um 09:17 lief die Frist mitten im Run ab; der Leuchtturm fing im
     * Verlies an und gab auf ("Bildschirm nicht erkannt"). Das Fenster des
     * Clan-Kriegs ist zwei Stunden offen -- die Zeit ist da.
     */
    private static final long WECHSEL_FRIST_MS = 15 * 60_000;
    /** Nach dem gezaehlten Durchlauf: so lange hat der Modus, um wirklich hinauszugehen. */
    private static final long WECHSEL_DRAUSSEN_MS = 90_000;
    /** Seit wann der Durchlauf fuer den Wechsel gezaehlt ist (0 = noch nicht). */
    private volatile long wechselFertigSeit = 0;
    /**
     * Autor, 11.10. 15:00: Laeuft ein Arena-Turnier, wartet jeder Einschub, bis es zu Ende ist --
     * so lange wird der Durchlauf nicht abgebrochen (ein Turnier samt Wartezeit dauert bis ~15 min).
     */
    private static final long TURNIER_FRIST_MS = 30 * 60_000;
    /** Nachspiel (11.10.): hoechstens so lange die Arena zu Ende spielen, bevor der eigentliche Modus dran ist. */
    private static final long NACHSPIEL_FRIST_MS = 20 * 60_000;
    /** Nachspiel: so lange ohne Fortschritt, dann aufgeben und mit dem eigentlichen Modus weitermachen. */
    private static final long NACHSPIEL_STILL_MS = 4 * 60_000;
    /** Noch kein Schritt des neuen Modus gemacht -- einmal nach dem Turnier-Balken sehen. */
    private boolean ersterSchritt;
    /** Laeuft gerade das Arena-Nachspiel (sonst null). */
    private volatile ArenaMode nachspiel;
    private RunTracker nachspielTracker;
    private long nachspielSeit;
    private boolean nachspielVorlagenGemeldet;
    /**
     * Pruefung 11.10.: Ging ein Nachspiel zu Ende, OHNE dass ein Turnier gespielt wurde (die Arena
     * fand gleich wieder "Participate", kam nicht weiter, oder die Texterkennung hat den Balken
     * nur falsch gelesen), faengt der Motor so lange kein neues an. Sonst liefe es im Kreis:
     * Nahrung lesen -> "Balken" -> Arena -> nichts -> Nahrung lesen -> ...
     */
    private static final long NACHSPIEL_SPERRE_MS = 10 * 60_000;
    /** Bis wann kein neues Nachspiel anfaengt (0 = keine Sperre). */
    private long nachspielSperreBis;

    /** Wann die naechste Sperre endet: nur nach einem Nachspiel ohne gespieltes Turnier (sonst 0). */
    /**
     * Darf ein Arena-Nachspiel (Turnier-Balken) noch anfangen? Nicht mehr, sobald die Zeitgrenze erreicht ist
     * ({@code finishingSince} gesetzt) -- Autor 18.11.: nach "!ende 18:50" wollte der Bot nach dem Verlies in die Arena.
     */
    static boolean nachspielErlaubt(long finishingSince) {
        return finishingSince == 0;
    }

    /** Wie weit eine Warnung zurueckliegen darf, um als Grund fuer ein vorzeitiges Ende zu gelten. */
    private static final long ENDE_GRUND_MS = 3 * 60_000L;

    /**
     * Ab so vielen geplanten Durchlaeufen heisst es "bis leer" (DiscordBridge.BIS_LEER = 100 000) -- dort ist ein
     * Ende vor der Zahl normal und kein Abbruch.
     */
    private static final int BIS_LEER_AB = 100_000;

    /** Hat der Modus vor der geplanten Zahl aufgehoert (nicht bei "bis leer")? */
    static boolean vorzeitig(int fertig, int geplant) {
        return fertig < geplant && geplant < BIS_LEER_AB;
    }

    /**
     * Die Schlusszeile, wenn ein Modus mit FINISHED aufhoert (Autor 18.11. 21:51: "All 10 runs done" nach einem Abbruch).
     * Alle geschafft: "Alle 10 Durchlaeufe erledigt." Vorher aufgehoert: "3 von 10 Durchlaeufen, dann abgebrochen: Grund"
     * (ohne Grund: "... dann aufgehoert."). "Bis leer": "12 Durchlaeufe erledigt (bis leer)."
     */
    static String endeMeldung(int fertig, int geplant, String grund) {
        boolean mitGrund = grund != null && !grund.isBlank();
        if (geplant >= BIS_LEER_AB) {
            return de.hustlebot.core.Sprache.t(fertig + " runs done (until empty)", fertig + " Durchlaeufe erledigt (bis leer)")
                    + (mitGrund ? ": " + grund : ".");
        }
        if (!vorzeitig(fertig, geplant)) {
            return de.hustlebot.core.Sprache.t("All " + geplant + " runs done.", "Alle " + geplant + " Durchlaeufe erledigt.")
                    + (mitGrund ? " " + grund : "");
        }
        return mitGrund
                ? de.hustlebot.core.Sprache.t(fertig + " of " + geplant + " runs, then stopped: ",
                        fertig + " von " + geplant + " Durchlaeufen, dann abgebrochen: ") + grund
                : de.hustlebot.core.Sprache.t(fertig + " of " + geplant + " runs, then the mode stopped (see the log).",
                        fertig + " von " + geplant + " Durchlaeufen, dann hat der Modus aufgehoert (siehe Protokoll).");
    }

    static long nachspielSperreBis(boolean turnierGespielt, long jetzt) {
        return turnierGespielt ? 0 : jetzt + NACHSPIEL_SPERRE_MS;
    }
    private long tickDelayMillis = 700;

    /**
     * Obergrenze fuer die Pause NACH einer ausgefuehrten Aktion.
     *
     * Der Modus hat davor schon adaptiv gewartet; mehr als ein kurzes
     * Durchatmen bringt hier nichts. Siehe die Stelle im Ablauf (case ACTED).
     */
    private static final long AKTION_PAUSE_MS = 25;
    /**
     * Pause, wenn der Bot nur schaut und nichts klickt. Bestimmt, wie oft ein
     * frisches Bild aufgenommen wird – und damit auch, wie fluessig die Vorschau
     * in der Weboberflaeche laeuft. Klickpausen bleiben davon unberuehrt.
     */
    /**
     * Pause, wenn der Bot nur schaut und nichts klickt.
     *
     * GEMESSEN am 19.09. (Autor: "die sind oft viel viel laenger als man sie
     * eigentlich braeuchte"): Nach einem Klick wechselt der Bildschirm gerade,
     * der Modus erkennt nichts und meldet WAITING -- und dann wurde 450 ms
     * geschlafen, bevor ueberhaupt wieder hingesehen wurde. Zwei bis drei
     * solcher Runden je Klick ergaben die gemessenen 1,5 bis 2,5 Sekunden
     * Leerlauf nach jedem Klick. Das war der groesste Zeitfresser ueberhaupt:
     * die eigentlichen Wartezeiten lagen bei nur rund 150 ms.
     *
     * 120 ms reichen. Frueher hinsehen kostet nur Rechenzeit, und bei hoher
     * Auslastung greift weiterhin die Bremse (isThrottled, mal vier).
     */
    private long scanDelayMillis = 120;

    public BotEngine(BotContext ctx) {
        // Texterkennung gleich beim Start einrichten (Sprachliste von Windows, ~6 s): sonst passierte
        // das beim ersten Lesen -- in der Arena mitten in Runde 1 (Hauptbot 08.10.).
        Thread ocrWarm = new Thread(() -> {
            try {
                de.hustlebot.core.Texterkennung.verfuegbareSprachen();
            } catch (Exception e) {
                Log.debug("Text", "Vorab-Einrichtung der Texterkennung: " + e.getMessage());
            }
        }, "ocr-vorab");
        ocrWarm.setDaemon(true);
        ocrWarm.start();
        this.ctx = ctx;
        aktiv = this;
        register(new DungeonMode());
        register(new CathedralMode());
        register(new LighthouseMode(LighthouseMode.LEUCHTTURM));
        register(new LighthouseMode(LighthouseMode.KOLOSSEUM));
        register(new TitanTurmMode());
        register(new ClanLadenMode());
        register(new ArenaMode());        // Arena/Turnier (Hauptbot 11.10., Freunde 3.0)
        register(new StigmaMode());       // "!stigmas loeschen" (Hauptbot 10.10., Freunde-Update)
        register(new EventMode());
        register(new SpielQuestsMode());   // Taegliche Spiel-Quests (Buch in der Burg -> Daily quests, Autor 18.11.)
        register(new TitanEventMode());   // Titan-Grabung "Titanic Excavation" (Hauptbot 09./10.10., Freunde-Update)
        register(new ClanWarMode());
        register(new HeldenSetMode());   // Probe Helden-Set (Ben 17.10.)
        register(new BurgenMode());   // Burgen angreifen (Zweitkonten, Autor 25.10.)
        register(new FreundeSendenMode());   // Freunden Ressourcen senden (Hauptbot 05.10., Freunde-Bot 22.10.)
        register(new PortalMode());   // Portal Level 80 im Autokampf (Hauptbot 09.10., Freunde-Bot 22.10.)
        register(new NahrungskistenMode());   // Kisten aus der Tasche, bis das Lager voll ist (Hauptbot 17.10.)
        register(new GemBayMode());   // Gem Bay (Hauptbot 29.09., Freunde-Bot 22.10.)
        register(new TeamScanMode());   // eigenes Team ansehen (Auftrag 6, 11.10.): einmal, dann alle 3 Tage
    }

    private void register(GameMode mode) {
        modes.put(mode.id(), mode);
    }

    public synchronized boolean start(String modeId, int runs) {
        return start(modeId, runs, "random", "");
    }

    public synchronized boolean start(String modeId, int runs, String teammate, String friendName) {
        if (state == State.LAEUFT) {
            Log.warn("Motor", de.hustlebot.core.Sprache.t("The bot is already running.", "Der Bot laeuft bereits."));
            return false;
        }
        // Code-Pruefung 15.10. (Hauptbot): Bei PAUSIERT lebt der alte Arbeitsfaden noch (er wartet nur auf paused=false).
        // Ein Start haette einen ZWEITEN Faden gestartet -- beide klickten gleichzeitig (z. B. Stigmas zerlegen).
        if (state == State.PAUSIERT) {
            Log.warn("Motor", de.hustlebot.core.Sprache.t("The bot is paused -- first continue (F10 / !resume) or stop (!stop), then start again.",
                    "Der Bot ist pausiert -- erst weiter (F10 / !weiter) oder stoppen (!stop), dann neu starten."));
            return false;
        }
        // Autor 25.10.: je nach Thronsaal des Kontos gibt es Modi noch nicht (Kathedrale ab 6, Titanenturm ab 7,
        // Edelsteinbucht ab 8, Verlies ab 9) -- dann gleich "fertig", damit eine Kette mit dem naechsten Modus weitermacht.
        // Vor dem "gesperrt"-Ende unten loeschen: sonst haette ein alter Zeitgrenzen-Vermerk die neue Kette angehalten.
        durchZeitgrenzeBeendet = false;
        String konto = de.hustlebot.core.Konten.aktiv();
        if (konto.isBlank()) konto = MeinTeam.spielername();
        de.hustlebot.core.Thronsaal.aktuellSetzen(de.hustlebot.core.Thronsaal.stufe(konto));
        String gesperrt = de.hustlebot.core.Thronsaal.gesperrtGrund(modeId, konto);
        if (gesperrt != null) {
            Log.info("Motor", gesperrt);
            setState(State.FERTIG, gesperrt);
            return true;
        }

        // Beim Start eines Modus sicherstellen, dass der Hardware-Monitor
        // laeuft. Sonst bleiben Temperatur und Luefter leer -- und die
        // Notbremse gegen Ueberhitzung haette nichts zum Hinsehen.
        // Freunde-Fassung: KEIN SystemMonitor.ensureRunning() -- die geplante
        // Aufgabe fuer den Hardware-Monitor gibt es nur auf Autors Rechner.

        // Ein neuer Lauf faengt sauber an -- sonst haengt die Frist vom
        // vorigen Ende noch drin.
        finishingSince = 0;
        runsWhenFinishing = 0;
        haengerAbbruch = false;
        zielErreicht = false;
        folgen = java.util.List.of();
        wechselAngefordert = false;
        wechselFertigSeit = 0;
        ersterSchritt = true;     // 11.10.: einmal nach dem Turnier-Balken der Arena sehen
        nachspiel = null;
        Turnierbalken.vergessen();

        GameMode mode = modes.get(modeId);
        if (mode == null) {
            String message = de.hustlebot.core.Sprache.t("Unknown mode: " + modeId, "Unbekannter Modus: " + modeId);
            Log.error("Motor", message);
            setState(State.FEHLER, message);
            return false;
        }

        // Mitspielerwahl setzen, bevor die Pflichtvorlagen geprueft werden:
        // im Freundesmodus werden zusaetzliche Vorlagen gebraucht.
        //
        // "friendwait" heisst: mit dem Freund spielen, aber nicht selbst
        // einladen -- auf SEINE Einladung warten. Fuer den Fall, dass auch
        // bei ihm ein Bot laeuft; sonst laden sich beide gegenseitig ein.
        boolean withFriend = teammate != null && teammate.toLowerCase().startsWith("friend");
        boolean aufEinladungWarten = "friendwait".equalsIgnoreCase(teammate);
        if (mode instanceof DungeonMode dungeon) {
            // 18.11.: "clan" = mit einem Clan-Mitglied (Quest "Go to a Dungeon with a clan mate") -- siehe DungeonMode.teammateAus.
            dungeon.setTeammate(DungeonMode.teammateAus(teammate), friendName, aufEinladungWarten);
            dungeon.setLeaderPickTimeoutMinutes(leaderPickTimeoutMinutes);
            dungeon.setPartnerReadyTimeoutMinutes(partnerReadyTimeoutMinutes);
        }
        if (withFriend && (friendName == null || friendName.isBlank())) {
            String message = de.hustlebot.core.Sprache.t("Playing with a friend needs a friend's name.",
                    "Fuer das Spiel mit einem Freund muss ein Name eingetragen sein.");
            Log.error("Motor", message);
            setState(State.FEHLER, message);
            return false;
        }

        // Vorlagen frisch laden, damit Aenderungen ohne Neustart wirken.
        ctx.matcher().loadAll();

        // Fehlende Bilder sind in der Freunde-Fassung KEIN Grund, nicht zu starten
        // (Autor, 01.10.: "die Vorlagen sind nicht Pflicht, nur empfohlen"). Vorher
        // brach der Start mit "Es fehlen 7 Vorlagen" ab -- bei einem Freund, dessen
        // Update die Event-Bilder gar nicht mitgebracht hatte. Das Paket legt fehlende
        // Bilder seit 2.2 beim Start selbst in den Ordner templates; was dann noch
        // fehlt, wird gemeldet, und der Bot laeuft trotzdem los. Die Einstellung
        // bot.requireAllTemplates in config.properties spielt hier keine Rolle mehr.
        List<String> missing = ctx.matcher().missing(mode.requiredTemplates());
        if (!missing.isEmpty()) {
            String liste = de.hustlebot.core.Sprache.t(
                    missing.size() + " images are missing: " + String.join(", ", missing) + ".",
                    "Es fehlen " + missing.size() + " Vorlagen: " + String.join(", ", missing) + ".");
            Log.warn("Motor", liste);
            Log.warn("Motor", de.hustlebot.core.Sprache.t(
                    "The bot starts anyway -- the images are recommended, not required. Screens "
                    + "without an image may not be recognised; if the bot gets stuck there, record "
                    + "the image with Capture-Images.exe (it is saved in the folder templates).",
                    "Der Bot startet trotzdem -- die Bilder sind empfohlen, nicht Pflicht. Bildschirme "
                    + "ohne Bild werden dort vielleicht nicht erkannt; bleibt der Bot haengen, das Bild "
                    + "mit Capture-Images.exe aufnehmen (es landet im Ordner templates)."));
        }

        // OHNE SPIEL KEIN START -- und kein Alarm (13.09.).
        //
        // Hier stand FEHLER, und Discord schickte jedes Mal eine rote Karte
        // "Abbruch mit Fehler". In der Nacht zum 13.09. kamen sie reihenweise:
        // Jedes Mal hatte die Taste ^ den Bot gestartet, waehrend Hustle
        // Castle zu war. Autor: "der bot soll nur an sein wenn er irgendwas hustle
        // castle bezogenes sieht". Ohne Spiel ist nichts kaputt -- der Bot
        // bleibt aus und sagt es ruhig (meldungOhneSpiel).
        //
        // Im Hauptbot kommt !start so gar nicht erst hierher: Es oeffnet
        // Hustle Castle vorher selbst ueber den Launcher (commandStart).
        if (!ctx.window().locate()) {
            String message = de.hustlebot.core.Sprache.t("Hustle Castle is not open -- the bot does not start.",
                    "Hustle Castle ist nicht offen -- der Bot startet nicht.");
            Log.info("Motor", message);
            setState(State.BEREIT, message);
            meldungOhneSpiel.run();
            return false;
        }

        lastModeId = modeId;
        lastRuns = runs;
        lastTeammate = teammate == null ? "random" : teammate;
        lastFriendName = friendName;

        activeMode = mode;
        // Graustufen in Kathedrale (23.09. geprueft) und Verlies (24.09.: in
        // Farbe 695 s reine Suche je 14-Minuten-Run -- der Rechner lief heiss).
        // Krieg und Arena suchen weiter in Farbe.
        // Arena wieder in Farbe (25.09.): im Test fand der Bot in Graustufen das
        // Schwerter-Zeichen zwischen den Kampfkraeften nicht mehr (Kraft bei allen
        // Gegnern "nicht lesbar") und "Quick Attack" nur noch unzuverlaessig.
        ctx.matcher().setGrauErlaubt("cathedral".equals(modeId) || "dungeon".equals(modeId)
                );
        // Schnelles Hinsehen nur in der Kathedrale (dort gemessen); alle
        // anderen Modi mit den alten Wartezeiten -- siehe BotContext.setSchnell.
        // 10.10. (wie Hauptbot 09.10.): auch die Titan-Grabung ("Wartezeiten so kurz wie moeglich im Titan-Event").
        boolean schnell = "cathedral".equals(modeId) || "grabung".equals(modeId);
        ctx.setSchnell(schnell);
        scanDelayMillis = schnell ? 40 : 120;
        mode.reset();
        kontoPruefung.starten(de.hustlebot.core.Konten.an());
        teamVorspiel = null;

        tracker = new RunTracker(runs);
        tracker.setIdleTimeoutMinutes(idleTimeoutMinutes);
        // Nach jedem Durchlauf sagen, wie oft verglichen wurde und wie viel
        // davon im kleinen Kasten lag. Danach bei null anfangen, damit die
        // Zahl immer fuer genau einen Durchlauf gilt.
        // Sparsam-Messung 20.11.: was VOR diesem Start lief (z. B. stundenlanges Arena-Zuschauen), landete bisher im
        // ersten Bericht des neuen Modus -- etwa 30.763 Chat-Suchen "im Gem Bay". Jetzt eigene Zeile, dann bei null.
        String erkennungVorher = ctx.matcher().vergleichsBericht();
        ctx.matcher().vergleicheZuruecksetzen();
        String textVorher = de.hustlebot.core.Texterkennung.bericht();
        if (!erkennungVorher.isBlank() && !erkennungVorher.equals(de.hustlebot.core.Sprache.t("no comparisons", "keine Vergleiche"))) {
            Log.debug("Bilderkennung", de.hustlebot.core.Sprache.t("before this start: ", "vor diesem Start: ") + erkennungVorher);
        }
        if (!textVorher.isBlank()) Log.debug("Text", de.hustlebot.core.Sprache.t("before this start: ", "vor diesem Start: ") + textVorher);
        tracker.setErkennungsBericht(() -> {
            String bericht = ctx.matcher().vergleichsBericht();
            ctx.matcher().vergleicheZuruecksetzen();
            // Sparsam 20.11.: Texterkennung je Durchlauf mitzaehlen (Zeilen, ms, aus dem Gedaechtnis, Modelle im Speicher)
            String text = de.hustlebot.core.Texterkennung.bericht();
            if (!text.isBlank()) Log.info("Text", text);
            return bericht;
        });

        stopRequested = false;
        paused = false;
        sessionStartedAt = System.currentTimeMillis();
        ctx.laufstatistik().startSession();
        setState(State.LAEUFT, de.hustlebot.core.Sprache.t("Running: ", "Laeuft: ") + mode.displayName() + ", " + runs
                + de.hustlebot.core.Sprache.t(" runs planned.", " Durchlaeufe geplant."));

        Log.info("Motor", "=================================================");
        Log.info("Motor", de.hustlebot.core.Sprache.t("Start: " + mode.displayName() + " – " + runs + " runs",
                "Start: " + mode.displayName() + " – " + runs + " Durchlaeufe"));
        Log.info("Motor", de.hustlebot.core.Sprache.t("Teammate: " + (withFriend ? "friend " + friendName : "random"),
                "Mitspieler: " + (withFriend ? "Freund " + friendName : "zufaellig")));
        Log.info("Motor", de.hustlebot.core.Sprache.t("Stop: !stop in Discord.", "Anhalten: !stop in Discord."));
        // Autor 18.11. (18:10): eine VOR dem Start gesetzte Endzeit stand nur weiter oben im Protokoll -- es sah aus, als
        // waere sie beim Start verworfen worden. Jetzt steht sie bei jedem Start mit da (sie gilt fuer die ganze Warteschlange).
        if (stopAtMillis > System.currentTimeMillis()) {
            Log.info("Motor", de.hustlebot.core.Sprache.t(
                    "Stop time: " + stopAtClock + " (in " + minutesUntilStop() + " minutes) -- after that nothing else from the queue starts.",
                    "Endzeit: " + stopAtClock + " (in " + minutesUntilStop() + " Minuten) -- danach startet nichts mehr aus der Warteschlange."));
        }
        Log.info("Motor", "=================================================");

        // Den alten Arbeitsfaden zu Ende kommen lassen (Code-Pruefung 26.09.):
        // Seit die Bruecke beim Modus-Ende sofort weiterschaltet, kann er hier
        // noch in seinem finally stecken und die gelernten Knopfstellen
        // speichern -- der neue Faden griffe gleichzeitig in dieselbe Liste.
        Thread vorher = worker;
        if (vorher != null && vorher != Thread.currentThread() && vorher.isAlive()) {
            try {
                vorher.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (vorher.isAlive()) {
                Log.warn("Motor", de.hustlebot.core.Sprache.t(
                        "The previous worker has not finished after 3 s -- the new one starts anyway.",
                        "Der vorige Arbeitsgang ist nach 3 s noch nicht zu Ende -- "
                        + "der neue startet trotzdem."));
            }
        }

        worker = new Thread(this::loop, "bot-worker");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * Welche Modi erst in die Burg muessen, wenn das Spiel woanders steht. 15.11. (Berichte XPVY/U7BK, Robin, 3.142, 13.11.
     * 12:15 und 12:35): Hing die Kathedrale 15 Minuten (Moduswechsel "Durchlauf hing"), startete der Titanen-Turm mitten auf
     * der Verlies-Etage, kannte den Bildschirm nicht und brach nach 75 s ab; "Freunden senden" danach ebenso ("Burg nicht
     * erkannt"). Beide kommen von der Etage nicht selbst weg -- darum wie Leuchtturm zuerst in die Burg.
     */
    static boolean vorBesuchNoetig(GameMode modus, boolean stigmaImTitanBildschirm) {
        return modus instanceof LighthouseMode
                || modus instanceof TitanTurmMode
                || modus instanceof FreundeSendenMode
                || (modus instanceof StigmaMode && !stigmaImTitanBildschirm);
    }

    private void loop() {
        int consecutiveFailures = 0;
        int lastCountedRun = 0;

        try {
            // Leuchtturm/Kolosseum erst aus der Burg (29.09.): Der Besuch fing sonst
            // dort an, wo der letzte Modus aufgehoert hatte -- 20:38 in einem fertigen
            // Verlies, 27.09. 09:17 mitten im Verlies ("Bildschirm nicht erkannt").
            // Stigmas ebenso (15.10.): nach dem Titan-Event kannte die Stigma-Suche den Bildschirm nicht.
            if (vorBesuchNoetig(activeMode, activeMode instanceof StigmaMode && StigmaMode.titanBildschirm(ctx))) {
                vorBesuchInDieBurg();
            }

            while (!stopRequested) {
                lastLoopHeartbeat = System.currentTimeMillis();

                if (paused && modusPauseBeenden) {
                    modusPauseBeenden = false;
                    paused = false;
                    modusPauseUm = 0;
                    zielErreicht = true;   // gewolltes Ende: die Warteschlange schaltet weiter statt abzubrechen
                    Log.info("Motor", de.hustlebot.core.Sprache.t("Pause ended for the clan war -- the queue carries on.",
                            "Pause beendet fuer den Clan-Krieg -- die Warteschlange geht weiter."));
                    setState(State.FERTIG, de.hustlebot.core.Sprache.t("Pause ended for the clan war.", "Pause beendet fuer den Clan-Krieg."));
                    return;
                }
                if (paused) {
                    setStatusText(de.hustlebot.core.Sprache.t("Paused at run ", "Pausiert bei Durchlauf ") + tracker.completedRuns()
                            + de.hustlebot.core.Sprache.t(" of ", " von ") + tracker.targetRuns() + ".");
                    Thread.sleep(400);
                    continue;
                }

                // Zurueck in die Burg gewuenscht (!burg)? Erst wenn der Modus an
                // einer sicheren Stelle ist -- nicht mitten im Verlies. Steckt
                // er fest, bricht der Modus den Durchlauf ohnehin selbst ab,
                // und dann ist die Stelle sicher.
                if (heimGewuenscht && activeMode.darfHeim()) {
                    heimGehenUndMelden();
                    return;
                }

                // Zeitgrenze erreicht? Dann wird NICHT sofort abgebrochen.
                //
                // Frueher hielt der Bot mitten im Durchlauf an -- die Fackel
                // war ausgegeben, der Mitspieler stand da, und der halbe
                // Fortschritt war weg. Jetzt wird der laufende Durchlauf noch
                // zu Ende gespielt.
                //
                // Erkannt wird das am Zaehler: Sobald eine Runde mehr
                // abgeschlossen ist als in dem Moment, in dem die Zeit
                // ablief, ist der Durchlauf durch. Das funktioniert in jedem
                // Modus gleich, ohne dass der Motor wissen muss, wie ein
                // Verlies oder eine Kathedrale innen aussieht.
                //
                // Die Frist von acht Minuten ist der Notausgang: Haengt der
                // Durchlauf, soll der Rechner trotzdem irgendwann aus.
                // Moduswechsel mitten im Betrieb.
                //
                // "!start kathedrale 50", waehrend das Verlies laeuft: Der
                // angefangene Durchlauf soll noch zu Ende gespielt werden --
                // sonst wirft man eine halb gelaufene Fackel weg.
                //
                // Erkannt wird das am Zaehler, genau wie bei der Zeitgrenze:
                // Steht ein Durchlauf mehr da als beim Wunsch, ist er durch.
                if (wechselAngefordert) {
                    boolean durchlaufFertig = tracker.completedRuns() > runsBeimWechsel;
                    long gewartet = System.currentTimeMillis() - wechselSeit;

                    // NICHT MITTEN IM VERLASSEN WECHSELN (27.09., 09:08).
                    //
                    // Das Verlies zaehlt den Durchlauf schon beim Klick auf
                    // "End journey" -- das Bestaetigen und das Abschlussfenster
                    // kommen erst danach. Der Wechsel kam 0,2 s nach dem Klick:
                    // Der Clan-Krieg fing im Verlies an, fand die Burg nicht
                    // und meldete "es laeuft kein Krieg". Der Krieg von 09:01
                    // waere verpasst gewesen. 19 von 20 Verlies-Runs enden so.
                    //
                    // Gewechselt wird deshalb erst, wenn der Modus selbst sagt,
                    // dass er draussen ist -- dafuer bekommt er nach dem
                    // gezaehlten Durchlauf bis zu WECHSEL_DRAUSSEN_MS Zeit.
                    // (Erster Versuch mit darfHeim(): griff um 09:38 nicht,
                    // das sagt im Verlies auch beim Warten auf den Mitspieler ja.)
                    if (durchlaufFertig && wechselFertigSeit == 0) {
                        wechselFertigSeit = System.currentTimeMillis();
                    }
                    boolean draussen = activeMode == null || activeMode.draussen()
                            || (wechselFertigSeit != 0
                                && System.currentTimeMillis() - wechselFertigSeit > WECHSEL_DRAUSSEN_MS);
                    // Autor, 11.10. 15:00: Laeuft ein Arena-Turnier (auch die Wartezeit zwischen den
                    // Stufen), wird NICHT abgebrochen -- der Einschub wartet, bis es zu Ende ist,
                    // und kommt dann sofort. Notausgang erst nach TURNIER_FRIST_MS.
                    boolean turnier = turnierLaeuft();
                    boolean fristUm = gewartet > (turnier ? Math.max(WECHSEL_FRIST_MS, TURNIER_FRIST_MS)
                                                          : WECHSEL_FRIST_MS);

                    if ((durchlaufFertig && draussen)
                            || (activeMode != null && activeMode.wechselJetzt())
                            || (!durchlaufFertig && fristUm)) {
                        wechselAngefordert = false;
                        // Damit die Warteschlange weiterschaltet statt
                        // abzubrechen: Der Wechsel ist ein gewolltes Ende,
                        // kein Fehler und keine Zeitgrenze.
                        zielErreicht = true;
                        // 29.10.: sagt der Modus selbst "jetzt" (wechselJetzt), stand hier "Durchlauf hing, nach 0 Minuten abgebrochen",
                        // obwohl nichts hing. Unter einer Minute ist es nie die Frist (15 Min.), sondern der Modus.
                        String wie = durchlaufFertig
                                ? de.hustlebot.core.Sprache.t("run finished first", "Durchlauf noch beendet")
                                : gewartet < 60_000
                                ? de.hustlebot.core.Sprache.t("the mode is at a safe spot, switching now",
                                        "der Modus ist an einer sicheren Stelle, Wechsel jetzt")
                                : de.hustlebot.core.Sprache.t("run was stuck, stopped after ", "Durchlauf hing, nach ") + (gewartet / 60000)
                                  + de.hustlebot.core.Sprache.t(" minutes", " Minuten abgebrochen");
                        Log.info("Motor", de.hustlebot.core.Sprache.t("Mode switch: " + wie + ".", "Moduswechsel: " + wie + "."));
                        setState(State.FERTIG, de.hustlebot.core.Sprache.t("Switching mode (", "Modus wird gewechselt (") + wie
                                + de.hustlebot.core.Sprache.t("). Done: ", "). Erledigt: ") + tracker.completedRuns() + de.hustlebot.core.Sprache.t(" runs.", " Durchlaeufe."));
                        return;
                    }
                    setStatusText(turnier
                            ? de.hustlebot.core.Sprache.t("Mode switch requested – the arena tournament is finished first (",
                                "Moduswechsel gewuenscht – erst wird das Arena-Turnier zu Ende gespielt (")
                              + (gewartet / 1000) + " s)."
                            : de.hustlebot.core.Sprache.t("Mode switch requested – the current run is finished first (",
                                "Moduswechsel gewuenscht – der laufende Durchlauf wird noch zu Ende gespielt (")
                              + (gewartet / 1000) + " s).");
                }

                String reason = timeLimitReached();
                if (reason != null) {
                    if (finishingSince == 0) {
                        finishingSince = System.currentTimeMillis();
                        runsWhenFinishing = tracker.completedRuns();
                        Log.info("Motor", de.hustlebot.core.Sprache.t(
                                "Time limit reached: " + reason
                                + ". The current run is finished first, "
                                + "at most " + (FINISH_GRACE_MS / 60000) + " minutes.",
                                "Zeitgrenze erreicht: " + reason
                                + ". Der laufende Durchlauf wird noch zu Ende gespielt, "
                                + "hoechstens " + (FINISH_GRACE_MS / 60000) + " Minuten."));
                    }

                    boolean durchlaufFertig = tracker.completedRuns() > runsWhenFinishing;
                    long gewartet = System.currentTimeMillis() - finishingSince;

                    if (durchlaufFertig || gewartet > FINISH_GRACE_MS) {
                        String wie = durchlaufFertig
                                ? de.hustlebot.core.Sprache.t("run finished first", "Durchlauf noch beendet")
                                : de.hustlebot.core.Sprache.t("run was stuck, stopped after ", "Durchlauf hing, nach ") + (gewartet / 60000)
                                  + de.hustlebot.core.Sprache.t(" minutes", " Minuten abgebrochen");
                        Log.info("Motor", de.hustlebot.core.Sprache.t("Time limit: " + wie + ".", "Zeitgrenze: " + wie + "."));
                        durchZeitgrenzeBeendet = true;   // fuer die Warteschlange: danach startet nichts mehr (18.11.)
                        setState(State.FERTIG, de.hustlebot.core.Sprache.t("Finished – ", "Beendet – ") + reason + " (" + wie
                                + de.hustlebot.core.Sprache.t("). Done: ", "). Erledigt: ") + tracker.completedRuns() + de.hustlebot.core.Sprache.t(" runs.", " Durchlaeufe."));
                        return;
                    }

                    setStatusText(de.hustlebot.core.Sprache.t("Time is up – the run is finished first (",
                            "Zeit ist um – der Durchlauf wird noch zu Ende gespielt (")
                            + (gewartet / 1000) + " s).");
                }

                Mat screen = ctx.refresh();
                if (screen == null) {
                    consecutiveFailures++;
                    Log.warn("Motor", de.hustlebot.core.Sprache.t("No picture from the game window (" + consecutiveFailures + ").",
                            "Kein Bild vom Spielfenster (" + consecutiveFailures + ")."));
                    if (consecutiveFailures > 20) {

                        // Bevor aufgegeben wird: Vielleicht ist Hustle Castle
                        // abgestuerzt. Dann hilft ein Neustart des Spiels --
                        // und der Bot spielt weiter, statt bis zum naechsten
                        // Morgen zu stehen.
                        //
                        // Hoechstens einmal alle zehn Minuten. Sonst startet
                        // er bei einem echten Problem im Kreis das Spiel neu.
                        long her = System.currentTimeMillis() - letzterSpielNeustart;
                        if (her > SPIEL_NEUSTART_PAUSE_MS) {
                            letzterSpielNeustart = System.currentTimeMillis();
                            spielAbsturzUm = letzterSpielNeustart;
                            Log.alarm("Motor", de.hustlebot.core.Sprache.t(
                                    "The game window is gone. Hustle Castle has probably crashed. The bot restarts it.",
                                    "Das Spielfenster ist weg. Vermutlich ist Hustle "
                                    + "Castle abgestuerzt. Der Bot startet es neu."));

                            // Eine Launcher-Sperre von vorhin darf hier nicht
                            // im Weg stehen: Es laeuft gerade ein Modus, Autor
                            // WILL also spielen. Ohne das waere die
                            // Selbstheilung nachts um drei tot, nur weil er
                            // abends den Launcher zugemacht hat.
                            GameLauncher.wiederErlauben();

                            GameLauncher.Ergebnis versuch =
                                    GameLauncher.sicherstellen(ctx.window());
                            Log.info("Motor", versuch.meldung());

                            if (versuch.ok()) {
                                consecutiveFailures = 0;
                                Thread.sleep(5000);
                                continue;
                            }
                        }

                        Log.error("Motor", de.hustlebot.core.Sprache.t("The game window is permanently unreachable. Stopping.",
                                "Das Spielfenster ist dauerhaft nicht erreichbar. Stopp."));
                        setState(State.FEHLER, de.hustlebot.core.Sprache.t("Game window not reachable.", "Spielfenster nicht erreichbar."));
                        return;
                    }
                    Thread.sleep(1500);
                    continue;
                }
                consecutiveFailures = 0;

                // 15.11. / X3A2: Captcha ("Suspicious magical activity") VOR allem anderen. Solange es offen ist, klickt
                // kein Modus; der Bot loest es NIE selbst, sondern ruft den Menschen.
                if (captchaPruefen(screen)) {
                    if (tracker != null) tracker.resetIdleClock();
                    schlaf(300);
                    continue;
                }

                // Autor 21.11.: Pflicht-Einfuehrung eines Events (Pfeil, Sprechblase, Hand) erst durchspielen -- das Spiel
                // nimmt waehrenddessen nur Klicks auf das gezeigte Ziel an (Zweitkonto: eine Einfuehrung blockierte alles).
                if (einfuehrungSchritt(screen)) continue;

                // Autor 18.10.: Erzaehltexte ("My lord, ..." mit kleinem Pfeil unten -- neues Event, Burg-Stufe, ...)
                // einfach wegdruecken, in jedem Modus.
                if (dialogWegdruecken(screen)) continue;
                readEther(screen);

                // Aufpassen, dass der Rechner heil bleibt. Prueft nur alle
                // paar Sekunden, kostet also praktisch nichts.
                haengerPruefen(tracker);
                String stopReason = guardSystem(tracker);
                if (stopReason != null) {
                    Log.error("Motor", stopReason);
                    setState(State.FEHLER, stopReason);
                    return;
                }

                // Autor 21.10. (Mia, mehrere Spielkonten): bei jedem Modus-Start den Spielernamen in der Burg lesen --
                // ein anderes Konto? Dann erst dessen Dateien holen, bevor der Modus etwas tut.
                // Konrad 20.11. (LT6E, Y9JU, VLD8, BUX9): Kontoname und Turnier-Balken liest der MOTOR per Texterkennung
                // (beim ersten Mal samt Laden der Erkennung bis 11 s). Das ist kein Stillstand des Modus -- die Zeit wird
                // der Haenger-Uhr gutgeschrieben, sonst kam "20 s kein Fortschritt, zuletzt: Start" kurz nach dem Start.
                // Autor 21.11.: in JEDEM Modus -- erst in die Burg, Name lesen, wenn noetig die Spieler-ID im Profil;
                // solange das laeuft, wartet der Modus (KontoPruefung). Neues Konto ohne Team-Scan: der Scan zuerst.
                if (!kontoPruefung.fertig()) {
                    long vorKonto = System.currentTimeMillis();
                    boolean wartet;
                    try {
                        wartet = kontoPruefung.schritt(screen, ctx);
                    } catch (Exception e) {
                        Log.warn("Konten", Sprache.t("Account check failed: ", "Konto-Pruefung fehlgeschlagen: ") + e.getMessage());
                        kontoPruefung.starten(false);
                        wartet = false;
                    }
                    if (tracker != null) tracker.gutschreiben(System.currentTimeMillis() - vorKonto);
                    if (wartet) continue;
                }
                if (kontoPruefung.gewechseltAbholen()) teamVorspielStarten();

                // Bericht UK3S (Ben 19.11.): derselbe Klick wirkt immer wieder nicht -- erst das Fenster mit Nachdruck
                // nach vorne holen, hilft das nicht, das Spiel neu starten (Klickwaechter). Danach ist das Bild veraltet.
                if (klickSelbsthilfe(screen)) continue;

                GameMode.Outcome outcome;
                try {
                    GameMode.Outcome vorab = allgemeineStoerung(screen);
                    // Turnier-Balken oben (Autor, 11.10.): erst die Arena zu Ende spielen (Nachspiel).
                    // Autor 18.11. (18:50): nach der Endzeit (!ende) KEIN neues Nachspiel mehr -- der Bot wollte nach dem
                    // Verlies noch in die Arena, statt aufzuhoeren. Ein schon laufendes Nachspiel spielt weiter wie bisher.
                    if (vorab == null && nachspiel == null && teamVorspiel == null && nachspielErlaubt(finishingSince)) {
                        long vorBalken = System.currentTimeMillis();
                        nachspielPruefen();
                        if (tracker != null) tracker.gutschreiben(System.currentTimeMillis() - vorBalken);
                    }
                    outcome = vorab != null ? vorab
                            : teamVorspiel != null ? teamVorspielSchritt(screen)
                            : nachspiel != null ? nachspielSchritt(screen)
                            : activeMode.step(screen, ctx, tracker);
                    if (activeMode.jetztInDieBurg()) {
                        heimGehen();
                        tracker.resetIdleClock();
                    }
                } catch (Exception e) {
                    Log.error("Motor", de.hustlebot.core.Sprache.t("Error in mode " + activeMode.displayName(),
                            "Fehler im Modus " + activeMode.displayName()), e);
                    Thread.sleep(2000);
                    continue;
                }

                // Fertigen Durchlauf in die Statistik eintragen -- VOR der
                // Fallunterscheidung.
                //
                // HIER FEHLTE DER LETZTE DURCHLAUF JEDES MODUS. Frueher stand
                // das im Zweig ACTED. Der letzte Durchlauf meldet aber
                // FINISHED, und der kehrt sofort zurueck -- eingetragen wurde
                // er also nie.
                //
                // Im Bericht sah das so aus:
                //
                //     1. ✔ 5 von 5 Kathedrale
                //     Mittlere Dauer je Kathedrale-Durchlauf: 4:44  (4)
                //     2. ✔ 1 von 1 Verlies
                //     (keine Verlies-Zeit)
                //
                // Fuenf gespielt, vier gemessen. Und bei einem Modus mit
                // genau einem Durchlauf fehlte die Zeile ganz.
                boolean durchlaufNeu = tracker.completedRuns() > lastCountedRun;
                if (durchlaufNeu) {
                    lastCountedRun = tracker.completedRuns();
                    ctx.matcher().stellenSpeichern(Matcher.STELLEN_DATEI);
                    // Leuchtturm, Kolosseum und Clan-Krieg sind Botengaenge,
                    // keine Durchlaeufe (Autor, 27.09.): Sie stehen nicht in
                    // der Statistik und verfaelschen keine Durchschnittszeit.
                    if (!istBotengang(lastModeId)) {
                        ctx.laufstatistik().addRun(tracker.completedRuns(),
                                tracker.lastRunSeconds(), tracker.failedBattles(),
                                lastTeammate.toLowerCase().startsWith("friend")
                                        ? lastFriendName : "zufaellig");

                        // Zusaetzlich getrennt nach Modus mitzaehlen. Das
                        // ueberlebt den Wechsel innerhalb einer Kette -- sonst
                        // waere die Verlies-Zeit weg, sobald die Kathedrale
                        // anfaengt.
                        ctx.laufstatistik().addRunForMode(lastModeId, tracker.lastRunSeconds());
                    }
                }

                switch (outcome) {
                    case FINISHED -> {
                        folgen = activeMode.folgeAuftraege();
                        String hinweis = activeMode.endeHinweis();
                        if (hinweis != null) {
                            try {
                                endeHinweis.accept(hinweis);
                            } catch (Exception ignored) {
                                // Eine misslungene Discord-Meldung darf das Ende nicht stoeren.
                            }
                        }
                        // Bericht K58J (19.10.): "All 100 runs done" stand da nach 1 Run, weil die Arena die Nahrung nicht lesen
                        // konnte. Mit Grund steht jetzt beides da, auch im Bot-Fenster und im Bericht an Autor.
                        // Autor 18.11. (21:51): "!start burgen 10" hoerte nach 3 Angriffen auf ("Weltkarte nicht erreicht"),
                        // trotzdem stand "All 10 runs done" da. Jetzt nur noch, wenn wirklich alle geschafft sind; sonst
                        // "1 von 10 Durchlaeufen, dann abgebrochen: <Grund>" (Grund: endeHinweis, sonst die letzte Warnung).
                        // Konrad 20.11. (UJQ8): die Vorlagen-Warnung beim Laden ("Name doppelt vergeben: confirm") ist kein
                        // Grund fuers Aufhoeren -- sie stand sonst als "dann abgebrochen: ..." hinter dem Clan-Krieg.
                        String grund = hinweis != null && !hinweis.isBlank() ? hinweis
                                : Log.letzteWarnung(ENDE_GRUND_MS, "Motor", "Vorlagen");
                        Log.info("Motor", endeMeldung(tracker.completedRuns(), tracker.targetRuns(), grund));
                        zielErreicht = true;
                        setState(State.FERTIG, de.hustlebot.core.Sprache.t("Done: ", "Fertig: ") + tracker.completedRuns()
                                + (vorzeitig(tracker.completedRuns(), tracker.targetRuns())
                                        ? de.hustlebot.core.Sprache.t(" of ", " von ") + tracker.targetRuns() : "")
                                + de.hustlebot.core.Sprache.t(" runs.", " Durchlaeufe.")
                                + (hinweis != null && !hinweis.isBlank() ? " " + hinweis : ""));
                        return;
                    }
                    case FATAL -> {
                        haengerAbbruch = true;   // fuer den Lernmodus (!fix)
                        // Autor 23.10.: "Kannst du Meldungen wie 'Hammerhand ist ein Tank' dort auch anzeigen?" -- statt "Bitte das
                        // Protokoll pruefen" den Grund, den der Modus eben als Fehler geschrieben hat.
                        String grund = Log.letzterFehler(30_000);
                        setState(State.FEHLER, de.hustlebot.core.Sprache.t("Stopped after ", "Abbruch nach ") + tracker.completedRuns()
                                + (grund != null ? de.hustlebot.core.Sprache.t(" runs: ", " Durchlaeufen: ") + grund
                                        : de.hustlebot.core.Sprache.t(" runs. Please check the log.", " Durchlaeufen. Bitte das Protokoll pruefen.")));
                        return;
                    }
                    case PAUSE -> {
                        // Anhalten, aber nicht aufgeben. Der Modus hat etwas
                        // gesehen, das ein Mensch aufloesen muss -- etwa die
                        // Meldung, dass sich jemand mit dem Handy angemeldet
                        // hat. Zaehler, Statistik und Warteschlange bleiben
                        // stehen, mit !weiter geht es genau hier weiter.
                        if (!paused) {
                            paused = true;
                            modusPauseUm = System.currentTimeMillis();
                            setState(State.PAUSIERT, de.hustlebot.core.Sprache.t("Paused – something is waiting for you. `!resume` carries on.",
                                    "Angehalten – es wartet etwas auf dich. Mit !weiter geht es weiter."));
                        }
                        Thread.sleep(scanDelayMillis);
                    }
                    case ACTED -> {
                        unbekanntFolge = 0;
                        // Ein Durchlauf ist fertig geworden: erst einmal Pause.
                        // Eingetragen ist er oben schon.
                        if (durchlaufNeu) {
                            takeBreak(tracker.completedRuns());
                            spielNeustartZwischenRuns();
                        }
                        setStatusText(de.hustlebot.core.Sprache.t("Run ", "Durchlauf ") + (tracker.completedRuns() + 1) + de.hustlebot.core.Sprache.t(" of ", " von ")
                                + tracker.targetRuns()
                                + (de.hustlebot.core.Sprache.istEnglisch() ? "" : " – " + tracker.lastNote()));

                        // NACH EINER AKTION IST DIE LANGE PAUSE UMSONST.
                        //
                        // Der Modus hat direkt davor schon adaptiv gewartet
                        // (sleepUntilChanged) -- also genau so lange, bis sich
                        // der Bildschirm wirklich geaendert hat. Die feste
                        // Pause von 150 ms kam da nur obendrauf: bei rund 150
                        // Aktionen je Kathedralen-Reise sind das ueber zwanzig
                        // Sekunden, in denen nichts passiert. Autor, 18.09.:
                        // "finde sinnlose Wartezeiten, verringer sie oder
                        // entfern sie ganz."
                        //
                        // Nur die Pause NACH einer Aktion wird gekuerzt. Das
                        // Warten im Leerlauf (WAITING/UNKNOWN, scanDelayMillis)
                        // bleibt, sonst wuerde der Bot im Stillstand heisslaufen
                        // -- und bei hoher Auslastung greift weiterhin die
                        // lange Bremse (isThrottled).
                        Thread.sleep(isThrottled() ? AKTION_PAUSE_MS * 10
                                : Math.min(AKTION_PAUSE_MS,
                                           ctx.input().humanizer().vary(tickDelayMillis)));
                    }
                    case WAITING -> {
                        unbekanntFolge = 0;
                        setStatusText(de.hustlebot.core.Sprache.t(
                                "Waiting – " + tracker.lastNote() + " (for " + tracker.idleSeconds() + " s)",
                                "Warte – " + tracker.lastNote()
                                + " (seit " + tracker.idleSeconds() + " s)"));
                        Thread.sleep(isThrottled() ? scanDelayMillis * 4 : scanDelayMillis);
                    }
                    case UNKNOWN -> {
                        // Dreimal hintereinander nichts erkannt: Vielleicht steht ein
                        // bekannter Knopf an einer NEUEN Stelle. Das naechste Bild
                        // deshalb ueberall absuchen. Nicht schon beim ersten Mal --
                        // kurze Uebergaenge zwischen zwei Bildschirmen sind haeufig,
                        // und jedes gruendliche Bild kostet ein Vielfaches (23.09.
                        // gemessen: Anteil der schnellen Suche fiel von 39 auf 21 %).
                        // 24.09. abends: danach nur noch jedes zehnte Mal. Beim
                        // Etagenwechsel (4-5 s Ladebild) liefen sonst sieben
                        // gruendliche Bilder hintereinander -- und tauchte die
                        // Etage waehrend eines davon auf, sah der Bot sie bis
                        // zu einer Sekunde zu spaet.
                        unbekanntFolge++;
                        if (unbekanntFolge == 3
                                || (unbekanntFolge > 3 && (unbekanntFolge - 3) % 10 == 0)) {
                            ctx.matcher().naechstesBildGruendlich();
                        }
                        Thread.sleep(isThrottled() ? scanDelayMillis * 4 : scanDelayMillis);
                    }
                }
            }

            setState(State.GESTOPPT, de.hustlebot.core.Sprache.t("Stopped after ", "Gestoppt nach ") + tracker.completedRuns()
                    + de.hustlebot.core.Sprache.t(" runs.", " Durchlaeufen."));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setState(State.GESTOPPT, de.hustlebot.core.Sprache.t("Interrupted.", "Unterbrochen."));
        } catch (Exception e) {
            Log.error("Motor", de.hustlebot.core.Sprache.t("Unexpected error in the main loop", "Unerwarteter Fehler in der Hauptschleife"), e);
            setState(State.FEHLER, de.hustlebot.core.Sprache.t("Unexpected error: ", "Unerwarteter Fehler: ") + e.getMessage());
        } catch (OutOfMemoryError e) {
            // 21.11. (Nacht, -Xmx192m): ein OutOfMemoryError ist kein Exception -- der Motorfaden starb still, der Zustand blieb
            // LAEUFT, kein Log, kein Hinweis, der Clankrieg 02:20 wurde verpasst. Jetzt: melden und auf FEHLER stellen.
            Log.error("Motor", de.hustlebot.core.Sprache.t("Out of memory (OutOfMemoryError) -- the bot stops here. Restart the bot; "
                    + "if it happens again, raise -Xmx in HustleBot.cfg.", "Speicher voll (OutOfMemoryError) -- der Bot haelt hier an. "
                    + "Bot neu starten; kommt es wieder vor, -Xmx in HustleBot.cfg erhoehen."));
            setState(State.FEHLER, de.hustlebot.core.Sprache.t("Out of memory -- please restart the bot.", "Speicher voll -- bitte den Bot neu starten."));
        } finally {
            // Gelernte Knopfstellen fuer den naechsten Start aufheben.
            ctx.matcher().stellenSpeichern(Matcher.STELLEN_DATEI);
        }
    }

    /** Name der Vorlage, an der der Ether-Zaehler oben erkannt wird. */
    private static final String T_ETHER_ICON = "ether_icon";
    /** Symbol, an dem "Particle of Light" in der Kathedrale erkannt wird. */
    private static final String T_LIGHT_ICON = "dc_light_icon";
    /** Symbol, an dem der Schluesselzaehler der Kathedrale erkannt wird. */
    private static final String T_KEY_ICON = "dc_key_icon";
    /**
     * Das Multiplikator-Schild auf dem Teilnahmebildschirm. Zwei eigene
     * Vorlagen, weil es in beiden Modi anders aussieht. Jede Vorlage ab dem
     * Grundnamen zaehlt, ein zweites Aussehen braucht also keine
     * Codeaenderung.
     */
    private static final String T_DC_MULTIPLIER = "dc_multiplier";
    private static final String T_DG_MULTIPLIER = "multiplier";
    /**
     * Wie weit rechts vom Symbol nach Ziffern gesucht wird, als Anteil der
     * Breite.
     *
     * Hier standen einmal 0.13. Das war zu wenig: Bei Autors Fenster sind
     * das 150 Pixel, eine Ziffer misst rund 19 -- sieben Ziffern mit ihren
     * Tausenderleerzeichen passten nicht mehr hinein. "Particle of Light"
     * stand deshalb im Discord mit 60.227 statt mit 6 022 715: Die Zahl war
     * hinten abgeschnitten.
     *
     * 0.22 sind gut 250 Pixel und damit Platz fuer zwoelf Ziffern. Dass der
     * breitere Ausschnitt in die Ziffern des NACHBARZAEHLERS hineinreicht,
     * faengt NumberReader ab -- es liest nur bis zur ersten grossen Luecke.
     */
    private static final double ETHER_FIELD_WIDTH = 0.22;
    /** Der Schluesselzaehler ist kurz, ein schmales Feld genuegt. */
    private static final double KEY_FIELD_WIDTH = 0.09;
    /**
     * Abstand zwischen zwei Ablesungen im Leerlauf.
     *
     * Frueher standen hier 20 Sekunden. Das war zu traege: die Zaehler stehen
     * jeweils nur ein, zwei Sekunden im Bild, und der Takt traf sie praktisch
     * nie. Fuenf Sekunden treffen deutlich haeufiger; den Rest erledigen die
     * gezielten Anforderungen der Spielmodi (siehe requestCounterRead).
     */
    private static final long ETHER_INTERVAL_MS = 5_000;

    private long lastEtherAttempt = 0;
    private boolean etherHintShown = false;

    // ==================================================================
    //  Schutz fuer den Rechner
    // ==================================================================

    /** So oft wird nachgesehen, wie es dem Rechner geht. */
    private static final long GUARD_INTERVAL_MS = 15_000;
    /** Ab dieser Auslastung macht der Bot von selbst langsamer. */
    private static final double CPU_BRAKE_PERCENT = 85;
    /** Ab dieser Auslastung wird es ernst. */
    private static final double CPU_STOP_PERCENT = 95;
    /** So lange darf es am Anschlag bleiben, bevor der Bot aufhoert. */
    private static final long CPU_STOP_GRACE_MS = 10 * 60_000;
    /** Laeuft der Bot auf einem Server ohne Grafikkarte (Datei server.txt im Bot-Ordner)? Dann keine Last-Bremse. */
    private static final boolean AUF_SERVER = java.nio.file.Files.exists(de.hustlebot.core.Ordner.bei("server.txt"));
    /** Unter so vielen Gigabyte freiem Platz wird gestoppt. */
    private static final long DISK_MIN_GB = 5;

    /**
     * Ab hier meldet sich der Bot, ab dort haelt er an. Beides in Grad.
     *
     * Die Zahlen orientieren sich am Prozessor selbst: Ein Ryzen drosselt
     * ab 95 Grad von sich aus. Bei 90 ist also noch Luft, aber es wird
     * ernst -- da haelt der Bot lieber an.
     *
     * Vorher standen hier 75 und 85. Das war zu vorsichtig: schon ein
     * Uebersetzungslauf erreicht kurz 75 Grad, und eine Warnung, die bei
     * voelliger Normallage kommt, sieht man nach der dritten nicht mehr an.
     */
    private static final double TEMP_WARN_CELSIUS = 85;
    private static final double TEMP_STOP_CELSIUS = 90;

    /**
     * Ab dieser Temperatur wird der RECHNER heruntergefahren, nicht nur der
     * Bot angehalten.
     *
     * Autors Wunsch, als er ueber Nacht wegfuhr: "ab einer bestimmten
     * Temperatur abschalten bzw. Herunterfahren, nur das du's weisst." Er
     * hat 85 Grad gewaehlt.
     *
     * WARUM ES NICHT BEIM ANHALTEN BLEIBT. Ein angehaltener Bot laesst den
     * Rechner zwar abkuehlen -- aber nur, wenn die Hitze vom Bot kam. Steckt
     * ein defekter Luefter dahinter oder ein Netzteil, das zu warm wird,
     * hilft das nichts, und niemand ist da, der nachsieht. Dann ist ein
     * heruntergefahrener Rechner das kleinere Uebel.
     */
    private static final double TEMP_AUS_CELSIUS = 90;   // Autor 29.10. abends: erst ab 90, 85 ist ihm zu frueh (mitten im Lauf)
    /**
     * Autor 31.10.: "Normalerweise bleibt es auf 90 Grad, aber wenn du an irgendwelchen Sachen baust, ist 95 auch okay." Sein Ryzen 5 7600X ist laut AMD bis 95 Grad
     * ausgelegt und bremst dort selbst. Nur mit hitze-bau-95.txt im Bot-Ordner: die Rechner der Freunde bleiben bei 90.
     */
    private static final double TEMP_AUS_BAU_CELSIUS = 95;
    private static final String BAU_95_DATEI = "hitze-bau-95.txt";
    /** Woran ein Bau zu erkennen ist: Maven und die Paket-Werkzeuge. Der Bot selbst laeuft als javaw. */
    private static final java.util.Set<String> BAU_PROGRAMME = java.util.Set.of("java", "java.exe", "javac", "javac.exe", "jpackage", "jpackage.exe", "jlink", "jlink.exe");
    /** So viel Rechenzeit (Prozent eines Kerns) muss ein Bau-Programm haben, damit es als Bau zaehlt. */
    private static final int BAU_AB_PROZENT = 50;

    static double abschaltGrenze(boolean baufreigabe, java.util.List<Rechenlast.Programm> top) {
        if (baufreigabe) {
            for (Rechenlast.Programm p : top) {
                if (p.prozent() >= BAU_AB_PROZENT && BAU_PROGRAMME.contains(p.name().toLowerCase(java.util.Locale.ROOT))) return TEMP_AUS_BAU_CELSIUS;
            }
        }
        return TEMP_AUS_CELSIUS;
    }

    /**
     * Wie lange die Hitze anhalten muss, bevor heruntergefahren wird.
     *
     * Eine einzelne Messung reicht ausdruecklich NICHT. Ein Uebersetzungslauf
     * oder ein Virenscan treibt die Temperatur sekundenweise hoch, ohne dass
     * irgendetwas kaputt waere -- und einen Rechner deswegen abzuschalten,
     * waere schlimmer als die Hitze.
     *
     * Zwei Minuten durchgehend ueber 85 Grad sind dagegen kein Ausreisser
     * mehr, sondern ein Zustand.
     */
    private static final long TEMP_AUS_MINUTEN = 2;

    /** Seit wann es ununterbrochen zu heiss ist, oder 0. */
    private long zuHeissSeit = 0;
    /** Nach so langer Zeit ohne jeden Fortschritt hoert der Bot ganz auf. */
    private static final long DEAD_MAN_MINUTES = 15;

    private long lastGuardAt = 0;
    private long cpuHighSince = 0;
    private long throttleUntil = 0;
    private boolean brakeReported = false;

    /** Damit die Hitzewarnung nicht bei jedem Durchgang neu verschickt wird. */
    private boolean tempWarned = false;

    /** Hitze-Fruehwarnung in Stufen (Autor 29.10.) und wer gerade rechnet (die Ursache). */
    private final de.hustlebot.core.HitzeWaechter hitze = de.hustlebot.core.HitzeWaechter.imBotOrdner();
    private final de.hustlebot.core.Rechenlast rechenlast = new de.hustlebot.core.Rechenlast();
    private volatile java.util.List<de.hustlebot.core.Rechenlast.Programm> rechenlastZuletzt = java.util.List.of();

    /** Damit die Luefterwarnung nicht bei jedem Durchgang neu verschickt wird. */
    private boolean fanWarned = false;

    /**
     * Sieht nach, wie es dem Rechner geht, und greift ein, wenn noetig.
     *
     * Der Bot laeuft oft nachts, ohne dass jemand danebensitzt. Dann soll er
     * selbst merken, wenn etwas aus dem Ruder laeuft -- und lieber einen
     * Durchlauf abbrechen als den Rechner ueber Stunden am Anschlag fahren.
     *
     * Drei Stufen:
     *   1. Ueber 85 Prozent Auslastung: laengere Pausen zwischen den Bildern.
     *      Der Bot wird langsamer, laeuft aber weiter.
     *   2. Ueber 95 Prozent, und das zehn Minuten am Stueck: Schluss.
     *   3. Platte fast voll oder eine Viertelstunde gar kein Fortschritt:
     *      ebenfalls Schluss.
     *
     * @return der Grund zum Aufhoeren, oder null wenn alles in Ordnung ist
     */
    /**
     * Autor 17.10.: Steht der Bot still, wird das gemeldet (Protokoll, optional ein Melder) -- ausserhalb der
     * Kathedrale nach 3 Minuten, in der Kathedrale nach 20 Sekunden (GameMode.haengerBerichtNachS). Einmal je Haenger.
     * (Der fruehere automatische Bericht an den Besitzer ist entfernt.)
     * Nach 2 Minuten nimmt der Bot ausserdem die eigenen Vorlagen zurueck (koennten schuld sein).
     */
    private static final long EIGENE_ZURUECK_S = 120;
    /** Fortschritts-Notizen, bei denen langes Warten normal ist (Deutsch und Englisch, klein geschrieben). */
    private static final String[] MITSPIELER_SUCHE = {"mitspieler wird gesucht", "searching for a partner", "suche neu gestartet",
            "search restarted", "kurz warten, ob", "waiting briefly to see whether"};
    private static final String[] NORMALES_WARTEN = {"mitspieler wird gesucht", "searching for a partner", "warte auf einladung",
            "waiting for the invite", "kurz warten, ob", "waiting briefly to see whether", "suche neu gestartet", "search restarted",
            "warteschlange", "queue ("};
    private static volatile java.util.function.Consumer<String> haengerMelder;
    private volatile boolean haengerGemeldet, eigeneZurueck;

    /** Vom Steuerprogramm: wer bei einem Haenger benachrichtigt wird (null = niemand). */
    public static void haengerMelderSetzen(java.util.function.Consumer<String> melder) {
        haengerMelder = melder;
    }

    /**
     * Ein Modus meldet etwas, das der Spieler sehen sollte (21.11.: unbekanntes Etagenmuster der Kathedrale). Die offene
     * Fassung schickt nichts weg -- es steht nur im eigenen Protokoll (das Bild wird nicht gesendet).
     */
    public static void berichtMelden(String text, byte[] png) {
        if (text == null || text.isBlank()) return;
        Log.warn("Motor", text);
    }

    // ---- Konto-Profile (Autor 21.10., Mia mit mehreren Spielkonten; seit 21.11. in jedem Modus, siehe KontoPruefung) ----
    private final KontoPruefung kontoPruefung = new KontoPruefung();

    // ---- Team-Scan vor dem Modus, wenn ein Konto noch keinen hat (Autor 21.11.) ---------------------------------------
    // "Wenn der Bot merkt, dass er nicht auf dem Autor-Account ist, soll er beim anderen Account automatisch das Team
    // scannen" -- und erst danach den gewuenschten Modus spielen. Gebaut wie das Arena-Nachspiel: der Modus bleibt aktiv,
    // der Motor ruft solange den Team-Scan. Einmal je Konto und Botlauf, nie in Schleife; klappt er nicht: Log + Modus.
    private volatile TeamScanMode teamVorspiel;
    private RunTracker teamVorspielTracker;
    private long teamVorspielSeit;
    private static final long TEAM_VORSPIEL_FRIST_MS = 12 * 60_000, TEAM_VORSPIEL_STILL_MS = 3 * 60_000;
    private static final java.util.Set<String> TEAM_VORSPIEL_KONTEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Kommt vor diesem Modus der Team-Scan? Nicht vor Botengaengen (Clankrieg, Leuchtturm, Team-Scan selbst ...). */
    static boolean teamVorspielNoetig(String modus, boolean ohneScan) {
        return modus != null && ohneScan && !istBotengang(modus);
    }

    private void teamVorspielStarten() {
        if (teamVorspiel != null || activeMode == null) return;
        if (!teamVorspielNoetig(activeMode.id(), MeinTeam.ohneScan())) return;
        String konto = Konten.aktiv();
        if (!TEAM_VORSPIEL_KONTEN.add(konto.toLowerCase(java.util.Locale.ROOT))) return;
        if (!(modes.get("teamscan") instanceof TeamScanMode scan)) {
            Log.info("Konten", Sprache.t("Account \"" + konto + "\" has no team scan yet -- this version has no team scan.",
                    "Konto \"" + konto + "\" hat noch keinen Team-Scan -- diese Fassung hat keinen Team-Scan."));
            return;
        }
        List<String> fehlt = ctx.matcher().missing(scan.requiredTemplates());
        if (!fehlt.isEmpty()) {
            Log.warn("Konten", Sprache.t("Account \"" + konto + "\" has no team scan yet, but the scan is missing " + fehlt.size()
                            + " images -- " + activeMode.displayName() + " starts without it.",
                    "Konto \"" + konto + "\" hat noch keinen Team-Scan, dem Scan fehlen aber " + fehlt.size() + " Vorlagen -- "
                            + activeMode.displayName() + " startet ohne ihn."));
            return;
        }
        scan.reset();
        teamVorspielTracker = new RunTracker(1);
        teamVorspielTracker.setIdleTimeoutMinutes(idleTimeoutMinutes);
        teamVorspielSeit = System.currentTimeMillis();
        teamVorspiel = scan;
        if (tracker != null) tracker.progress(Sprache.t("Team scan for the account", "Team-Scan fuer das Konto"));
        Log.hinweis("Konten", Sprache.t("Account \"" + konto + "\" has no team scan yet -- the bot looks at its team first, then "
                        + activeMode.displayName() + " starts.",
                "Das Konto \"" + konto + "\" hat noch keinen Team-Scan -- der Bot sieht sich zuerst sein Team an, danach startet "
                        + activeMode.displayName() + "."));
        setStatusText(Sprache.t("Team scan for the new account, then ", "Team-Scan fuer das neue Konto, danach ") + activeMode.displayName() + ".");
    }

    /** Ein Schritt des Team-Scans vor dem Modus. FINISHED/FATAL/PAUSE beenden nur den Scan, nie den Modus. */
    private GameMode.Outcome teamVorspielSchritt(Mat screen) {
        TeamScanMode scan = teamVorspiel;
        if (scan == null) return GameMode.Outcome.WAITING;
        GameMode.Outcome o;
        try {
            o = scan.step(screen, ctx, teamVorspielTracker);
        } catch (Exception e) {
            Log.error("Konten", Sprache.t("Error in the team scan before the mode", "Fehler im Team-Scan vor dem Modus"), e);
            teamVorspielBeenden(Sprache.t("error", "Fehler"));
            return GameMode.Outcome.WAITING;
        }
        if ((o == GameMode.Outcome.ACTED || teamVorspielTracker.idleSeconds() < 2) && tracker != null) {
            tracker.progress(Sprache.t("Team scan for the account", "Team-Scan fuer das Konto"));
        }
        long dauer = System.currentTimeMillis() - teamVorspielSeit;
        if (o == GameMode.Outcome.FINISHED) {
            teamVorspielBeenden(Sprache.t("done", "fertig"));
        } else if (o == GameMode.Outcome.FATAL || o == GameMode.Outcome.PAUSE) {
            teamVorspielBeenden(Sprache.t("the scan got stuck (" + o + ")", "der Scan kam nicht weiter (" + o + ")"));
        } else if (dauer > TEAM_VORSPIEL_FRIST_MS) {
            teamVorspielBeenden(Sprache.t("not finished after " + dauer / 60000 + " minutes", "nach " + dauer / 60000 + " Minuten nicht fertig"));
        } else if (teamVorspielTracker.idleSeconds() * 1000L > TEAM_VORSPIEL_STILL_MS) {
            teamVorspielBeenden(Sprache.t(teamVorspielTracker.idleSeconds() + " s without progress",
                    teamVorspielTracker.idleSeconds() + " s ohne Fortschritt"));
        }
        return o == GameMode.Outcome.ACTED ? GameMode.Outcome.ACTED : GameMode.Outcome.WAITING;
    }

    private void teamVorspielBeenden(String grund) {
        teamVorspiel = null;
        MeinTeam.kontoGewechselt();   // das Ergebnis frisch lesen (gilt fuer das Konto, steht in seiner arena-ich.txt)
        String weiter = activeMode == null ? Sprache.t("the mode", "dem Modus") : activeMode.displayName();
        Log.info("Konten", Sprache.t("Team scan before the mode finished (" + grund + ") -- continuing with " + weiter + ".",
                "Team-Scan vor dem Modus beendet (" + grund + ") -- jetzt weiter mit " + weiter + "."));
        if (tracker != null) {
            tracker.resetIdleClock();
            tracker.progress(Sprache.t("continuing with " + weiter, "weiter mit " + weiter));
        }
        setStatusText(Sprache.t("Continuing with ", "Weiter mit ") + weiter + ".");
    }

    private void haengerPruefen(RunTracker tracker) {
        if (tracker == null || pausiert()) return;
        // 22.10. nachts: nach "Update" (Spiel laedt ~20 s neu) kam jedes Mal ein Haenger-Bericht -- das ist kein Stillstand.
        if (System.currentTimeMillis() - Stoerfenster.updateGedruecktUm() < 45_000) return;
        long still = tracker.idleSeconds();
        // Bericht WTK9 (Server 30.10., vorher HFQX, BMGQ): auf dem langsamen Server dauerte das Lesen der Kriegskarte 59 s -- kein Haenger.
        long frist = de.hustlebot.core.Rechnertempo.mal(activeMode != null ? activeMode.haengerBerichtNachS() : 20);
        // Normales Warten ist kein Stillstand (18.10.): Verlies sucht Mitspieler / wartet auf Einladung, Arena-Warteschlange
        // -- dort erst nach 3 Minuten melden, sonst kaeme bei jedem Verlies ein Bericht.
        String notiz = String.valueOf(tracker.lastNote()).toLowerCase(java.util.Locale.ROOT);
        for (String w : NORMALES_WARTEN) {
            if (notiz.contains(w)) {
                frist = Math.max(frist, 180);
                break;
            }
        }
        if (activeMode != null && activeMode.wartetNurAufAndere()) frist = Long.MAX_VALUE;
        // Autor 18.10.: Stillstands-Bericht im Verlies nach 20 s -- "nur halt nicht da, wo er einen random Mitspieler
        // sucht". Waehrend der Mitspieler-Suche gar keiner.
        for (String w : MITSPIELER_SUCHE) {
            if (notiz.contains(w)) {
                frist = Long.MAX_VALUE;
                break;
            }
        }
        if (still < Math.min(frist, EIGENE_ZURUECK_S)) {
            haengerGemeldet = false;
            eigeneZurueck = false;
            return;
        }
        // Autor 24.10.: klickt der Bot gerade abwechselnd verschiedene Knoepfe (Kaempfe, Geschenke ...), ist das kein
        // Stillstand -- noch nicht melden und die eigenen Bilder behalten. Nach 3 Minuten ohne Schritt zaehlt es doch.
        if (still < 180 && de.hustlebot.core.Report.arbeitetSichtbar(20)) return;
        // Nacht 26.10. (Titanen-Turm, 4 min Autokampf): "181 own pictures taken back" -- ein Modus mit laengerer Frist
        // (Titanen-Turm, Portal, Arena) kaempft dann noch, das ist kein Stillstand. Erst ab seiner Frist zuruecknehmen.
        if (still >= Math.max(EIGENE_ZURUECK_S, frist) && !eigeneZurueck) {
            eigeneZurueck = true;
            // Haengt der Bot, koennte eine eigene Aufnahme schuld sein -- zurueck auf Autors Bilder.
            ctx.matcher().eigeneZuruecknehmen(de.hustlebot.core.Sprache.t("2 minutes without progress", "2 Minuten ohne Fortschritt"));
        }
        if (still < frist || haengerGemeldet) return;
        haengerGemeldet = true;
        String grund = de.hustlebot.core.Sprache.t(
                "No progress for " + still + " s (mode " + (activeMode != null ? activeMode.id() : "?")
                        + ", last: " + tracker.lastNote() + ")",
                "Seit " + still + " s kein Fortschritt (Modus " + (activeMode != null ? activeMode.id() : "?")
                        + ", zuletzt: " + tracker.lastNote() + ")");
        Log.warn("Motor", grund);
        var m = haengerMelder;
        if (m != null) {
            try {
                m.accept(grund);
            } catch (Exception e) {
                Log.debug("Motor", "Haenger-Bericht: " + e.getMessage());
            }
        }
    }

    private String guardSystem(RunTracker tracker) {
        long now = System.currentTimeMillis();
        if (now - lastGuardAt < GUARD_INTERVAL_MS) return null;
        lastGuardAt = now;

        // --- Totmann-Schalter -----------------------------------------
        // Tut sich eine Viertelstunde gar nichts, laeuft der Bot ins Leere.
        // Die Modi haben eigene, kuerzere Fristen; das hier ist der letzte
        // Halt, falls die alle nicht greifen.
        if (tracker != null && tracker.idleSeconds() > DEAD_MAN_MINUTES * 60) {
            haengerAbbruch = true;   // fuer den Lernmodus (!fix)
            return de.hustlebot.core.Sprache.t("No progress for " + (tracker.idleSeconds() / 60) + " minutes. The bot stops instead of running on for nothing.",
                    "Seit " + (tracker.idleSeconds() / 60) + " Minuten kein Fortschritt mehr. "
                    + "Der Bot haelt an, statt weiter ins Leere zu laufen.");
        }

        // --- Festplatte -----------------------------------------------
        SystemMonitor.Reading r = SystemMonitor.read();
        if (r.diskFreeGb() >= 0 && r.diskFreeGb() < DISK_MIN_GB) {
            return de.hustlebot.core.Sprache.t("Only " + r.diskFreeGb() + " GB left on the disk. The bot stops before nothing works any more.",
                    "Nur noch " + r.diskFreeGb() + " GB frei auf der Festplatte. "
                    + "Der Bot haelt an, bevor gar nichts mehr geht.");
        }

        // --- Temperatur -----------------------------------------------
        // Erst ab hier gibt es diesen Wert ueberhaupt: Windows selbst gibt
        // die Sensoren nicht heraus, die Zahl kommt von LibreHardwareMonitor.
        // Laeuft der nicht, steht hier -1 und der Block wird uebersprungen --
        // die Auslastung weiter unten ueberwacht dann allein.
        double temp = r.sensors().cpuTemp();
        if (temp > 0) {

            // --- Hitze-Fruehwarnung (Autor 29.10.): Stufen 75/80/83 mit Verlauf und Ursache, nicht erst bei 85 ---
            // Bei jeder Pruefung (alle 15 s) messen, welche Programme rechnen -- dann steht die Ursache schon fest, wenn
            // 75 erreicht wird. Nicht erst ab 70: ein Bau sprang am 29.10. in 15 s von unter 70 auf 78 Grad.
            rechenlastZuletzt = rechenlast.top(4);
            var hm = hitze.messen(now, temp, () -> de.hustlebot.core.Rechenlast.text(rechenlastZuletzt));
            if (hm.isPresent()) {
                if (hm.get().wecken()) Log.alarm("Rechner", hm.get().text());
                else if (hm.get().melden()) Log.hinweis("Rechner", hm.get().text());
                else Log.info("Rechner", hm.get().text());   // kurze Spitze: nur ins Protokoll (Autor 01.11.)
            }
            // Ab Stufe 2 (80 Grad) macht der Bot selbst langsamer (dieselbe Bremse wie bei hoher Auslastung).
            if (hitze.stufe() >= 2) throttleUntil = Math.max(throttleUntil, now + 60_000);

            // --- Zu heiss, und zwar anhaltend: Rechner herunterfahren ---
            double grenze = abschaltGrenze(java.nio.file.Files.exists(Ordner.bei(BAU_95_DATEI)), rechenlastZuletzt);
            if (temp >= grenze) {
                long jetzt = System.currentTimeMillis();
                if (zuHeissSeit == 0) {
                    zuHeissSeit = jetzt;
                    Log.alarm("Rechner", de.hustlebot.core.Sprache.t(
                            "The processor is " + Math.round(temp)
                            + " degrees hot. If that lasts " + TEMP_AUS_MINUTEN + " minutes, "
                            + "the PC shuts itself down.",
                            "Der Prozessor ist " + Math.round(temp)
                            + " Grad heiss. Haelt das " + TEMP_AUS_MINUTEN + " Minuten an, "
                            + "faehrt der Rechner von selbst herunter."));
                } else if (jetzt - zuHeissSeit >= TEMP_AUS_MINUTEN * 60_000L) {
                    zuHeissSeit = 0;
                    Log.alarm("Rechner", de.hustlebot.core.Sprache.t(
                            "Above " + (int) grenze + " degrees for " + TEMP_AUS_MINUTEN
                            + " minutes (" + Math.round(temp)
                            + "). The PC is shutting down -- better off than broken.",
                            "Seit " + TEMP_AUS_MINUTEN + " Minuten ueber "
                            + (int) grenze + " Grad (" + Math.round(temp)
                            + "). Der Rechner wird heruntergefahren -- lieber aus als kaputt."));
                    CrashDetector.expectShutdown();
                    PowerControl.shutdown(60, de.hustlebot.core.Sprache.t(
                            "Processor permanently above " + (int) grenze + " degrees",
                            "Prozessor dauerhaft ueber "
                            + (int) grenze + " Grad"));
                    return de.hustlebot.core.Sprache.t("The processor was above " + (int) grenze + " degrees for " + TEMP_AUS_MINUTEN
                            + " minutes. The PC shuts down in one minute. \"!pc cancel\" still takes it back.",
                            "Der Prozessor war " + TEMP_AUS_MINUTEN + " Minuten lang ueber "
                            + (int) grenze + " Grad. Der Rechner faehrt in einer "
                            + "Minute herunter. Mit \"!pc abbruch\" laesst sich das noch "
                            + "zuruecknehmen.");
                }
            } else if (zuHeissSeit != 0) {
                // Autor 26.10.: Entwarnung MUSS nach Discord (Log.hinweis), nicht nur ins Logbuch -- bleibt sie aus,
                // weiss Autor, dass der Rechner wirklich heruntergefahren wurde.
                Log.hinweis("Rechner", de.hustlebot.core.Sprache.t(
                        "The temperature is back below " + (int) grenze + " degrees. No shutdown.",
                        "Die Temperatur ist wieder unter "
                        + (int) grenze + " Grad. Es wird NICHT heruntergefahren."));
                zuHeissSeit = 0;
            }

            if (temp >= TEMP_STOP_CELSIUS) {
                return de.hustlebot.core.Sprache.t("The processor is " + Math.round(temp) + " degrees hot. The bot stops so the PC can cool down. "
                        + "A stopped run is better than an overheated PC.",
                        "Der Prozessor ist " + Math.round(temp) + " Grad heiss. Der Bot haelt "
                        + "an, damit der Rechner abkuehlen kann. Ein abgebrochener Durchlauf ist "
                        + "weniger schlimm als ein ueberhitzter Rechner.");
            }

            if (temp >= TEMP_WARN_CELSIUS && !tempWarned) {
                tempWarned = true;
                Log.alarm("Rechner", de.hustlebot.core.Sprache.t(
                        "The processor is " + Math.round(temp) + " degrees hot. "
                        + "From " + (int) TEMP_STOP_CELSIUS + " degrees the bot stops by itself. "
                        + "Nothing has happened yet -- this is just a note.",
                        "Der Prozessor ist " + Math.round(temp) + " Grad heiss. "
                        + "Ab " + (int) TEMP_STOP_CELSIUS + " Grad haelt der Bot von selbst an. "
                        + "Noch ist nichts passiert -- das hier ist nur ein Hinweis."));
            }

            // --- Luefter ------------------------------------------------
            // Dreht sich kein einziger Luefter mehr, waehrend es warm ist,
            // ist das kein Sparmodus, sondern ein Defekt. Bei niedriger
            // Temperatur stehen Luefter voellig zu Recht still -- deshalb
            // gilt das erst ab 60 Grad, sonst haette man jede Nacht Alarm.
            if (r.sensors().fanCount() == 0 && temp >= 60) {
                if (!fanWarned) {
                    fanWarned = true;
                    Log.alarm("Rechner", de.hustlebot.core.Sprache.t(
                            "Not a single fan is spinning, yet the processor is "
                            + Math.round(temp) + " degrees warm. That looks like a defect. "
                            + "Please check before the PC shuts itself down.",
                            "Es dreht sich kein einziger Luefter, dabei ist der "
                            + "Prozessor " + Math.round(temp) + " Grad warm. Das sieht nach "
                            + "einem Defekt aus. Bitte nachsehen, bevor der Rechner sich "
                            + "selbst abschaltet."));
                }
            } else if (r.sensors().fanCount() > 0) {
                fanWarned = false;
            }

            // Erst deutlich unterhalb der Schwelle Entwarnung geben. Sonst
            // pendelt die Temperatur um die 75 Grad und es hagelt Meldungen.
            if (tempWarned && temp < TEMP_WARN_CELSIUS - 5) {
                tempWarned = false;
                // Autor 26.10.: auch diese Entwarnung nach Discord (Log.hinweis), damit Autor die Abkuehlung sieht.
                Log.hinweis("Rechner", de.hustlebot.core.Sprache.t(
                        "The processor has cooled down again (" + Math.round(temp) + " degrees).",
                        "Der Prozessor ist wieder abgekuehlt ("
                        + Math.round(temp) + " Grad)."));
            }
        }

        // --- Auslastung -----------------------------------------------
        double cpu = r.cpuPercent();
        if (cpu < 0) return null;   // nicht messbar, dann eben ohne
        // Server-Test 23.10.: Ohne Grafikkarte zeichnet das Spiel mit dem Prozessor und haelt ihn dauernd bei 95-99 %.
        // Das ist dort kein Hitzeproblem -- die Bremse machte den Bot aber laufend langsamer, und nach 10 Minuten
        // haette er ganz angehalten. Steht server.txt im Bot-Ordner, zaehlt die Auslastung deshalb nicht.
        if (AUF_SERVER) return null;

        if (cpu >= CPU_STOP_PERCENT) {
            if (cpuHighSince == 0) cpuHighSince = now;
            long hot = now - cpuHighSince;

            if (hot > CPU_STOP_GRACE_MS) {
                return de.hustlebot.core.Sprache.t("The PC has been at full load for " + (hot / 60000) + " minutes (" + r.cpuPercent()
                        + " percent). The bot stops so nothing overheats. A stopped run is better than an overheated PC.",
                        "Der Rechner laeuft seit " + (hot / 60000) + " Minuten am Anschlag ("
                        + r.cpuPercent() + " Prozent). Der Bot haelt an, damit nichts heiss "
                        + "laeuft. Ein abgebrochener Durchlauf ist weniger schlimm als ein "
                        + "ueberhitzter Rechner.");
            }
        } else {
            cpuHighSince = 0;
        }

        // --- Bremse ---------------------------------------------------
        if (cpu >= CPU_BRAKE_PERCENT) {
            throttleUntil = now + 60_000;
            if (!brakeReported) {
                brakeReported = true;
                Log.warn("Rechner", de.hustlebot.core.Sprache.t(
                        "Load at " + r.cpuPercent() + " percent. The bot "
                        + "slows down by itself until things calm down again.",
                        "Auslastung bei " + r.cpuPercent() + " Prozent. Der Bot "
                        + "macht von selbst langsamer, bis es wieder ruhiger wird."));
            }
        } else if (brakeReported && now > throttleUntil) {
            brakeReported = false;
            Log.info("Rechner", de.hustlebot.core.Sprache.t(
                    "Load back in the green (" + r.cpuPercent() + " percent), normal speed.",
                    "Auslastung wieder im gruenen Bereich ("
                    + r.cpuPercent() + " Prozent), normales Tempo."));
        }

        return null;
    }

    /** True, solange der Bot wegen hoher Auslastung langsamer machen soll. */
    public boolean isThrottled() {
        return System.currentTimeMillis() < throttleUntil;
    }

    /**
     * Liest ab und zu die Zaehler oben im Spielfenster ab: Ether, "Particle of
     * Light" und die Schluessel der Kathedrale.
     *
     * WICHTIG: Die drei haengen NICHT voneinander ab. Frueher brach die ganze
     * Methode ab, sobald der Ether nicht gelesen werden konnte -- und damit
     * wurden auch Licht und Schluessel nie mitgeschrieben, obwohl deren
     * Vorlagen laengst da waren. Jeder Zaehler wird jetzt fuer sich versucht.
     */
    private void readEther(Mat screen) {
        // Hat ein Spielmodus gerade gemeldet, dass die passende Anzeige offen
        // ist, wird sofort gelesen -- ohne auf den Takt zu warten. Genau daran
        // hing es: die Zaehler stehen nur kurz im Bild, und der Takt traf sie
        // fast nie.
        boolean forced = ctx.consumeCounterRead();

        // Autor 30.10. (Server): Im Clan-Krieg stehen diese Zaehler nie im Bild. Das Suchen kostete dort je Blick rund 0,25 s pro Vorlage.
        if (!forced && activeMode != null && "clanwar".equals(activeMode.id())) return;

        long now = System.currentTimeMillis();
        if (!forced && now - lastEtherAttempt < ETHER_INTERVAL_MS) return;
        lastEtherAttempt = now;

        boolean digits = NumberReader.digitsAvailable(ctx.matcher());

        // --- Ether -----------------------------------------------------
        if (digits && ctx.matcher().has(T_ETHER_ICON)) {
            NumberReader.readRightOf(screen, ctx.matcher(), T_ETHER_ICON, ETHER_FIELD_WIDTH)
                    .ifPresent(value -> ctx.laufstatistik().recordEther(value));
        }

        // --- "Particle of Light" der Kathedrale ------------------------
        // Nutzt dieselben Ziffern wie der Ether -- so hat Autor es bestaetigt.
        // Nur das Symbol davor ist anders, und davon gibt es zwei Aussehen.
        // Jede Vorlage ab "dc_light_icon" zaehlt, das erste passende gewinnt.
        if (digits) {
            for (String icon : iconVariants(ctx, T_LIGHT_ICON)) {
                var value = NumberReader.readRightOf(screen, ctx.matcher(), icon,
                        ETHER_FIELD_WIDTH);
                if (value.isPresent()) {
                    ctx.laufstatistik().recordLight(value.getAsLong());
                    break;
                }
            }
        }

        // --- Schluessel der Kathedrale ---------------------------------
        // Eigene Schrift, deshalb eigene Ziffern key_0 bis key_9. Sie stehen
        // nur auf dem Teilnahmebildschirm.
        if (NumberReader.digitsAvailable(ctx.matcher(), NumberReader.KEY_PREFIX)) {
            for (String icon : iconVariants(ctx, T_KEY_ICON)) {
                var value = NumberReader.readRightOf(screen, ctx.matcher(), icon,
                        KEY_FIELD_WIDTH, NumberReader.KEY_PREFIX);
                if (value.isPresent()) {
                    ctx.laufstatistik().recordKeys(value.getAsLong());
                    break;
                }
            }
        }

        // --- Multiplikator-Reisen, beide Modi -------------------------
        // Die Zahl steht nicht immer rechts vom Symbol: im Verlies hinter
        // "Number of journeys:", in der Kathedrale als kleines Schild an der
        // Ecke. Deshalb wird ein Kasten rundherum durchsucht.
        //
        // Die vier Werte sagen, wie weit der Kasten ueber das Symbol
        // hinausreicht (links, rechts, oben, unten). Liest der Bot die falsche
        // Zahl, sind das die Zahlen zum Nachstellen -- der gelesene Wert steht
        // im Protokoll.
        if (digits) {
            for (String icon : iconVariants(ctx, T_DC_MULTIPLIER)) {
                var value = NumberReader.readAround(screen, ctx.matcher(), icon,
                        1.2, 0.4, 0.8, 0.2, NumberReader.DIGIT_PREFIX);
                if (value.isPresent()) {
                    ctx.laufstatistik().recordCathedralMultiplier(value.getAsLong());
                    break;
                }
            }
            for (String icon : iconVariants(ctx, T_DG_MULTIPLIER)) {
                var value = NumberReader.readAround(screen, ctx.matcher(), icon,
                        0.2, 1.6, 0.3, 0.3, NumberReader.DIGIT_PREFIX);
                if (value.isPresent()) {
                    ctx.laufstatistik().recordDungeonMultiplier(value.getAsLong());
                    break;
                }
            }
        }

        // Einmal je Sitzung sagen, was fuer die Zaehler noch fehlt.
        if (!etherHintShown) {
            etherHintShown = true;
            reportCounterGaps();
        }
    }

    /**
     * Sagt einmal je Sitzung, welche Vorlagen fuer die Zaehler noch fehlen --
     * getrennt nach Ether, Licht und Schluesseln, damit klar ist, was wofuer
     * gebraucht wird.
     */
    private void reportCounterGaps() {
        List<String> missingDigits = NumberReader.missingDigits(ctx.matcher());

        if (!missingDigits.isEmpty()) {
            Log.info("Zaehler", de.hustlebot.core.Sprache.t(
                    "Ether and Particle of Light are not being recorded yet. "
                    + "Missing digits: " + String.join(", ", missingDigits),
                    "Ether und Particle of Light werden noch nicht "
                    + "mitgeschrieben. Es fehlen die Ziffern: "
                    + String.join(", ", missingDigits)));
        } else if (!ctx.matcher().has(T_ETHER_ICON)) {
            Log.info("Zaehler", de.hustlebot.core.Sprache.t(
                    "Ether is not being recorded, " + T_ETHER_ICON + " is missing.",
                    "Der Ether wird nicht mitgeschrieben, es fehlt "
                    + T_ETHER_ICON + "."));
        }

        List<String> missingKeys = new java.util.ArrayList<>();
        for (int i = 0; i <= 9; i++) {
            if (!ctx.matcher().has(NumberReader.KEY_PREFIX + i)) {
                missingKeys.add(NumberReader.KEY_PREFIX + i);
            }
        }
        if (!missingKeys.isEmpty()) {
            Log.info("Zaehler", de.hustlebot.core.Sprache.t(
                    "The Cathedral keys are not being counted yet. "
                    + "Missing: " + String.join(", ", missingKeys)
                    + ". The digits are in the key counter at the top of the "
                    + "participation screen and use their own font.",
                    "Die Schluessel der Kathedrale werden noch nicht gezaehlt. "
                    + "Es fehlen: " + String.join(", ", missingKeys)
                    + ". Die Ziffern stehen im Schluesselzaehler oben auf dem "
                    + "Teilnahmebildschirm und haben eine eigene Schrift."));
        }
    }

    /** Alle geladenen Aussehen eines Symbols, etwa dc_light_icon und _alt. */
    private List<String> iconVariants(BotContext ctx, String base) {
        List<String> names = new java.util.ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (name.equals(base) || name.startsWith(base + "_")) names.add(name);
        }
        return names;
    }

    /**
     * SPIEL-NEUSTART ALLE 2 STUNDEN (Autor 16.10. fuer die Freunde, abschaltbar: Einstellungen -> Cathedral).
     * Nur in der Kathedrale und nur direkt nach einem fertigen Run (dann ist die Reise abgeschlossen), nicht bei
     * einem angeforderten Moduswechsel (Leuchtturm, Clan-Krieg ...), nicht 15 min vor einem Clan-Krieg, nicht wenn
     * der Titanen-Turm faellig ist und nicht vor dem letzten Run. Der Motor beendet das Spiel, oeffnet es ueber den
     * Launcher, wartet auf die Burg und spielt weiter -- ohne sich selbst neu zu starten.
     */
    private void spielNeustartZwischenRuns() throws InterruptedException {
        if (activeMode == null || !"cathedral".equals(activeMode.id()) || stopRequested) return;
        if (!Spielneustart.faellig()) return;
        if (wechselAngefordert || tracker.targetRuns() - tracker.completedRuns() <= 1) return;
        // 26.10.: in Minuten auf der inneren Uhr des Kriegsplans -- vorher Berliner Kriegszeit gegen die Ortszeit (Ben:
        // eine Stunde daneben), und in der Nacht der Zeitumstellung ist eine Ortszeit doppelt.
        if (Kriegsplan.minutenBisKrieg() < 15) return;
        if (Turmplan.faellig().isPresent()) return;

        String alter = Spielneustart.spielAlter().map(d -> d.toHours() + " h " + (d.toMinutes() % 60) + " min").orElse("?");
        Log.info("Spiel-Neustart", Sprache.t("Hustle Castle has been running for " + alter + " and gets slower over time -- "
                        + "restart for more speed (every 2 hours; switch: Settings -> Cathedral).",
                "Hustle Castle laeuft seit " + alter + " und wird mit der Zeit langsamer -- Neustart fuer mehr Tempo "
                        + "(alle 2 Stunden; Schalter: Einstellungen -> Kathedrale)."));
        setStatusText(Sprache.t("Restarting Hustle Castle (every 2 hours) ...", "Hustle Castle wird neu gestartet (alle 2 Stunden) ..."));
        spielNeuStartenBisBurg();
    }

    /**
     * Hustle Castle beenden, ueber den Launcher neu oeffnen, bis in die Burg laden lassen (hoechstens 2 min) und den
     * Modus zuruecksetzen -- danach spielt er weiter wie nach jedem Neustart. Gemeinsamer Weg fuer den Neustart alle
     * 2 Stunden und die Selbsthilfe gegen wirkungslose Klicks (Bericht UK3S, {@link #klickSelbsthilfe}).
     *
     * @return false, wenn das Spiel nicht wieder startete
     */
    private boolean spielNeuStartenBisBurg() throws InterruptedException {
        long begonnen = System.currentTimeMillis();
        Mauswache.ruheFuer(240_000);   // der Launcher klickt mit eigener Maus
        try {
            Spielneustart.spielBeenden();
            Thread.sleep(2_000);
            GameLauncher.Ergebnis e = GameLauncher.sicherstellen(ctx.window());
            if (!e.ok()) {
                Log.warn("Spiel-Neustart", Sprache.t("The game did not start again: ", "Das Spiel startete nicht wieder: ") + e.meldung());
                return false;
            }
            // Bis in die Burg laden lassen (hoechstens 2 min): sobald der Karten-Knopf zu sehen ist, geht es weiter.
            long bis = System.currentTimeMillis() + 120_000;
            boolean burg = false;
            while (!stopRequested && System.currentTimeMillis() < bis) {
                lastLoopHeartbeat = System.currentTimeMillis();   // die Hauptschleife lebt (kein "Bot antwortet nicht")
                Mat bild = ctx.refresh();
                if (bild != null && (ctx.visibleIfKnown(bild, "map_button") || ctx.visibleIfKnown(bild, "map_button_alt"))) {
                    burg = true;
                    break;
                }
                Thread.sleep(1_000);
            }
            Log.info("Spiel-Neustart", burg
                    ? Sprache.t("Hustle Castle is back in the castle after " + (System.currentTimeMillis() - begonnen) / 1000 + " s -- carrying on.",
                            "Hustle Castle ist nach " + (System.currentTimeMillis() - begonnen) / 1000 + " s wieder in der Burg -- weiter geht's.")
                    : Sprache.t("The castle was not seen within 2 minutes -- the mode tries to find its way by itself.",
                            "Die Burg war nach 2 Minuten nicht zu sehen -- der Modus sucht sich den Weg selbst."));
            activeMode.reset();
            // Bericht YLG4 (Mia, 19.10.): Der Neustart dauert gut 30 s, danach schlug die Stillstands-Uhr (20 s) an,
            // obwohl alles lief. Der Neustart ist Fortschritt.
            if (tracker != null) tracker.progress(Sprache.t("Game restarted", "Spiel neu gestartet"));
            return true;
        } finally {
            Mauswache.ruheFuer(3_000);
        }
    }

    // ------------------------------------------------------------------
    //  Selbsthilfe: derselbe Klick wirkt immer wieder nicht (Bericht UK3S)
    // ------------------------------------------------------------------

    private long waechterGeprueftUm = 0;
    /** Die Folge (Klick + Uhrzeit des ersten), fuer die die Stufen unten gelten; eine neue Folge faengt von vorn an. */
    private String waechterFolge = "";
    private boolean waechterNachVorn = false;
    /** Grobes Bild zu Beginn der Folge (nach Stufe 1: direkt danach) und die groesste Abweichung davon seither. */
    private byte[] waechterBild = null;
    private double waechterAbweichung = 0;
    private final java.util.EnumSet<Klickwaechter.Schritt> waechterGemeldet = java.util.EnumSet.noneOf(Klickwaechter.Schritt.class);
    /** Klicks vor diesem Zeitpunkt zaehlen nicht (letzter Selbsthilfe-Neustart). */
    private long waechterAb = 0;
    private final java.util.ArrayDeque<Long> selbsthilfeNeustarts = new java.util.ArrayDeque<>();

    /**
     * BERICHT UK3S (Ben 19.11.): Das Spielfenster verlor den Vordergrund, danach klickte der Bot 32-mal in 69 s auf
     * "Home" -- ohne Wirkung -- und kam nie heraus. Stufe 1: Fenster mit Nachdruck nach vorne, Stufe 2: Spiel neu
     * starten. Entschieden wird in {@link Klickwaechter} (reine Rechnung, getestet), hier wird nur gehandelt.
     *
     * @return true = Fenster geholt oder Spiel neu gestartet: das Bild ist veraltet, die Schleife faengt neu an
     */
    private boolean klickSelbsthilfe(Mat screen) throws InterruptedException {
        if (tracker == null || activeMode == null || paused || pausiert() || stopRequested) return false;
        if (activeMode.inEmpfindlicherPhase()) return false;   // Werbung laeuft: der Modus steuert selbst
        long jetzt = System.currentTimeMillis();
        if (jetzt - waechterGeprueftUm < 1000) return false;   // Log.snapshot kopiert bis zu 2000 Zeilen
        waechterGeprueftUm = jetzt;

        Klickwaechter.Folge f = Klickwaechter.folge(Log.snapshot(), java.time.LocalTime.now(),
                Math.min(10 * 60_000L, jetzt - waechterAb));
        String id = f.klick() + "@" + f.erstesUm();
        if (!id.equals(waechterFolge)) {
            waechterFolge = id;
            waechterNachVorn = false;
            waechterBild = null;
            waechterAbweichung = 0;
            waechterGemeldet.clear();
        }
        // Bild-Probe: ab dem zweiten gleichen Klick ein grobes Bild als Ausgangspunkt, danach je Sekunde die groesste
        // Abweichung davon mitfuehren (nach Stufe 1 neuer Ausgangspunkt).
        if (f.anzahl() < 2) return false;
        if (waechterBild == null && !waechterNachVorn) {
            waechterBild = Klickwaechter.fingerabdruck(screen);
        } else {
            waechterAbweichung = Math.max(waechterAbweichung,
                    Klickwaechter.abweichung(waechterBild, Klickwaechter.fingerabdruck(screen)));
        }
        if (f.anzahl() < Klickwaechter.NACH_VORN_AB) return false;
        while (!selbsthilfeNeustarts.isEmpty() && jetzt - selbsthilfeNeustarts.peekFirst() > 3_600_000L) {
            selbsthilfeNeustarts.pollFirst();
        }
        GameWindow fenster = ctx.window();
        boolean haengt = fenster.reagiertNicht();
        // Die Kampf-Probe kostet ein paar Vollbildsuchen -- nur, wenn sonst neu gestartet wuerde.
        boolean kampf = waechterNachVorn && Klickwaechter.neustartReif(f) && !haengt
                && Klickwaechter.kampfZuSehen(v -> ctx.visibleIfKnown(screen, v));
        boolean still = waechterBild != null && Klickwaechter.still(waechterAbweichung);
        Klickwaechter.Schritt s = Klickwaechter.entscheiden(f, fenster.isForeground(), waechterNachVorn, still, haengt,
                kampf, selbsthilfeNeustarts.size());
        String klick = "\"" + f.text() + "\"";
        switch (s) {
            case NACH_VORN -> {
                Log.warn("Motor", Sprache.t("Self-help: the same click has had no effect " + f.anzahl() + " times in a row ("
                                + klick + "). The game window is brought to the front with force and photographed again.",
                        "Selbsthilfe: derselbe Klick wirkt " + f.anzahl() + "-mal in Folge nicht (" + klick
                                + "). Das Spielfenster wird mit Nachdruck nach vorne geholt und neu fotografiert."));
                boolean vorn = !haengt && fenster.nachVorneZwingen();
                Thread.sleep(500);
                waechterBild = Klickwaechter.fingerabdruck(ctx.refresh());
                waechterAbweichung = 0;
                waechterNachVorn = true;
                Log.info("Motor", Sprache.t("Self-help: game window in front: " + (vorn ? "yes" : "no")
                                + ", game responds: " + (haengt ? "NO (Windows: not responding)" : "yes")
                                + ", in front lies: " + GameWindow.vordergrund() + ".",
                        "Selbsthilfe: Spielfenster vorne: " + (vorn ? "ja" : "nein")
                                + ", Spiel antwortet: " + (haengt ? "NEIN (Windows: keine Rueckmeldung)" : "ja")
                                + ", vorne liegt: " + GameWindow.vordergrund() + "."));
                return true;
            }
            case NEUSTART -> {
                selbsthilfeNeustarts.addLast(jetzt);
                Log.warn("Motor", Sprache.t("Self-help: the click still has no effect (" + f.anzahl() + " times in "
                                + f.dauerMs() / 1000 + " s, " + klick + ")"
                                + (haengt ? ", and Windows reports the game as not responding" : ", the picture stands still")
                                + ". Hustle Castle is restarted (like the restart every 2 hours), then the mode carries on.",
                        "Selbsthilfe: der Klick wirkt weiter nicht (" + f.anzahl() + "-mal in " + f.dauerMs() / 1000
                                + " s, " + klick + ")"
                                + (haengt ? ", und Windows meldet das Spiel als \"keine Rueckmeldung\"" : ", das Bild steht still")
                                + ". Hustle Castle wird neu gestartet (wie der Neustart alle 2 Stunden), danach macht der Modus weiter."));
                GameLauncher.wiederErlauben();   // wie beim Absturz: es laeuft ein Modus, das Spiel soll laufen
                spielAbsturzUm = System.currentTimeMillis();   // Verlies: dieser Lauf zaehlt nicht
                setStatusText(Sprache.t("Self-help: restarting Hustle Castle ...", "Selbsthilfe: Hustle Castle wird neu gestartet ..."));
                boolean ok = spielNeuStartenBisBurg();
                waechterAb = System.currentTimeMillis();
                Log.warn("Motor", ok
                        ? Sprache.t("Self-help: Hustle Castle restarted, the mode carries on.",
                                "Selbsthilfe: Hustle Castle neu gestartet, der Modus macht weiter.")
                        : Sprache.t("Self-help: the restart did not work -- the bot carries on as before (stuck report, stop after 15 minutes).",
                                "Selbsthilfe: der Neustart klappte nicht -- weiter wie bisher (Haenger-Bericht, Abbruch nach 15 Minuten)."));
                return true;
            }
            case KAMPF_ABWARTEN, BILD_BEWEGT_SICH, AUSGESCHOEPFT -> {
                if (waechterGemeldet.add(s)) {
                    Log.warn("Motor", switch (s) {
                        case KAMPF_ABWARTEN -> Sprache.t("Self-help: the click still has no effect, but a battle is running -- no restart in the middle of a battle.",
                                "Selbsthilfe: der Klick wirkt weiter nicht, aber ein Kampf laeuft -- kein Neustart mitten im Kampf.");
                        case BILD_BEWEGT_SICH -> Sprache.t("Self-help: the same click repeats, but the picture keeps changing -- the clicks may do something after all. No restart.",
                                "Selbsthilfe: derselbe Klick wiederholt sich, aber das Bild veraendert sich -- die Klicks bewirken vielleicht doch etwas. Kein Neustart.");
                        default -> Sprache.t("Self-help: the game was already restarted " + Klickwaechter.NEUSTARTS_JE_STUNDE + " times in the last hour -- no further restart.",
                                "Selbsthilfe: das Spiel wurde in der letzten Stunde schon " + Klickwaechter.NEUSTARTS_JE_STUNDE + "-mal neu gestartet -- kein weiterer Neustart.");
                    });
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Legt nach einem abgeschlossenen Durchlauf eine Pause ein. Die Pause wird
     * in kleinen Schritten abgewartet, damit ein Stopp sofort greift.
     */
    private void takeBreak(int completedRuns) throws InterruptedException {
        long millis = ctx.input().humanizer().breakAfterRun(completedRuns);
        if (millis <= 0) return;

        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until && !stopRequested) {
            lastLoopHeartbeat = System.currentTimeMillis();
            long remaining = (until - System.currentTimeMillis()) / 1000;
            setStatusText(de.hustlebot.core.Sprache.t(
                    "Break, continuing in " + remaining + " seconds. Done: "
                    + completedRuns + " of " + tracker.targetRuns() + ".",
                    "Pause, weiter in " + remaining + " Sekunden. Erledigt: "
                    + completedRuns + " von " + tracker.targetRuns() + "."));
            Thread.sleep(500);
        }
        if (tracker != null) tracker.resetIdleClock();
    }

    /**
     * Start mit den zuletzt gewaehlten Angaben. Laeuft der Bot bereits,
     * passiert nichts.
     *
     * Setzt die Anfangsstaende von Ether, Particle of Light und Schluesseln
     * zurueck -- hier faengt eine neue Sitzung an.
     */
    public boolean startWithLastSettings() {
        return startWithLastSettings(true);
    }

    /**
     * Wie oben, aber mit der Wahl, ob die Anfangsstaende neu gesetzt werden.
     *
     * HIER FEHLTE ETWAS NACH DEM UMBAU: Das Zuruecksetzen war aus
     * startSession() herausgenommen worden, damit eine Kette die Zahlen
     * behaelt -- gerufen wurde es danach aber nur noch aus commandStart.
     * Wer den Bot mit der Taste ^, ueber das Bedienfeld im Browser oder mit
     * !neustart startete, bekam die Anfangsstaende der VORIGEN Sitzung und
     * damit ein falsches "Dazugekommen".
     *
     * Jetzt entscheidet es der Motor: Standard ist zuruecksetzen, und nur
     * die Warteschlange und der Moduswechsel sagen ausdruecklich, dass die
     * Zahlen stehenbleiben sollen.
     */
    public boolean startWithLastSettings(boolean zaehlerZuruecksetzen) {
        if (state == State.LAEUFT || state == State.PAUSIERT) {
            Log.info("Motor", de.hustlebot.core.Sprache.t("The bot is already running -- no second start.",
                    "Der Bot laeuft bereits -- kein zweiter Start."));
            return false;
        }
        if (zaehlerZuruecksetzen) {
            ctx.laufstatistik().resetZaehlerstaende();
        }
        Log.info("Motor", de.hustlebot.core.Sprache.t(
                "Start with the last settings: " + lastModeId + ", " + lastRuns + " runs.",
                "Start mit den letzten Angaben: " + lastModeId
                + ", " + lastRuns + " Durchlaeufe."));
        return start(lastModeId, lastRuns, lastTeammate, lastFriendName);
    }

    /**
     * Haelt den Bot an und startet ihn mit denselben Angaben neu. Der Zaehler
     * der Durchlaeufe beginnt wieder bei null, die Vorlagen werden frisch
     * eingelesen. Gedacht fuer den Fall, dass der Bot irgendwo haengen bleibt.
     */
    public boolean restart() {
        Log.info("Motor", de.hustlebot.core.Sprache.t("Restart requested.", "Neustart angefordert."));

        if (state == State.LAEUFT || state == State.PAUSIERT) {
            stop(de.hustlebot.core.Sprache.t("Restart", "Neustart"));

            // Auf das Ende des laufenden Arbeitsschritts warten.
            Thread previous = worker;
            if (previous != null) {
                try {
                    previous.join(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (previous.isAlive()) {
                    Log.error("Motor", de.hustlebot.core.Sprache.t(
                            "The old worker cannot be stopped. "
                            + "No restart, otherwise two bots would run at the same time. "
                            + "Please close the program window and start run.bat again.",
                            "Der alte Arbeitsgang laesst sich nicht beenden. "
                            + "Es wird nicht neu gestartet, sonst liefen zwei Bots gleichzeitig. "
                            + "Bitte das Programmfenster schliessen und run.bat erneut starten."));
                    return false;
                }
            }
        }

        // Maus an eine harmlose Stelle, damit kein Fenster offen bleibt.
        try {
            ctx.input().parkMouse();
        } catch (Exception ignored) {
            // Nicht schlimm, wenn das gerade nicht geht.
        }

        Log.info("Motor", de.hustlebot.core.Sprache.t(
                "Restart with the last settings: " + lastModeId + ", " + lastRuns + " runs.",
                "Neustart mit den letzten Angaben: " + lastModeId
                + ", " + lastRuns + " Durchlaeufe."));

        // Ein Neustart ist eine neue Sitzung: Ether, Particle of Light und
        // Schluessel fangen wieder bei ihrem jetzigen Stand an.
        ctx.laufstatistik().resetZaehlerstaende();
        return start(lastModeId, lastRuns, lastTeammate, lastFriendName);
    }

    /** Prueft die zusaetzlichen Abbruchbedingungen. Null heisst: weitermachen. */
    private String timeLimitReached() {
        if (stopAfterMinutes > 0 && sessionStartedAt > 0) {
            long minutes = (System.currentTimeMillis() - sessionStartedAt) / 60000;
            if (minutes >= stopAfterMinutes) {
                return de.hustlebot.core.Sprache.t("after " + minutes + " minutes of running", "nach " + minutes + " Minuten Laufzeit");
            }
        }
        if (stopAtMillis > 0 && System.currentTimeMillis() >= stopAtMillis) {
            return de.hustlebot.core.Sprache.t("stop time " + stopAtClock + " reached", "Uhrzeit " + stopAtClock + " erreicht");
        }
        return null;
    }

    /** Setzt den Spielmodus fuer den naechsten Start, etwa "dungeon" oder "cathedral". */
    public void setLastMode(String modeId) {
        if (modeId != null && modes.containsKey(modeId)) {
            this.lastModeId = modeId;
        }
    }

    /**
     * Mit wem das Verlies beim naechsten Start gespielt wird.
     *
     * Leer oder null heisst: zufaelliger Mitspieler. Sonst der Name des
     * Freundes, so wie er in "!start dungeon 10 friend Lisa" steht.
     *
     * Bisher konnte das nur das Bedienfeld setzen. Discord startet ueber
     * startWithLastSettings() -- und das nahm stets den zuletzt gesetzten
     * Mitspieler, also immer "zufaellig".
     */
    public void setMitspieler(String freund) {
        setMitspieler(freund, false);
    }

    /**
     * Wie oben. warten = true: nicht selbst einladen, sondern auf die
     * Einladung des Freundes warten ("!start dungeon 10 friend Lisa wait").
     */
    public void setMitspieler(String freund, boolean warten) {
        boolean mitFreund = freund != null && !freund.isBlank();
        this.lastTeammate = !mitFreund ? "random" : (warten ? "friendwait" : "friend");
        this.lastFriendName = mitFreund ? freund.trim() : "";
    }

    /** Der Freund fuer den naechsten Start -- leer bei zufaelligem Mitspieler. */
    public String mitspielerFreund() {
        return lastTeammate.toLowerCase().startsWith("friend") ? lastFriendName : "";
    }

    /** Wartet der Bot beim naechsten Start auf die Einladung des Freundes? */
    public boolean mitspielerWartet() {
        return "friendwait".equalsIgnoreCase(lastTeammate);
    }

    /** Wie viele Durchlaeufe beim naechsten Start geplant sind. */
    public int lastRuns() { return lastRuns; }

    public String lastModeId() {
        return lastModeId;
    }

    /**
     * Weg ueber eine Etage der Kathedrale.
     *
     * @param full true fuer Variante b (alle Wegpunkte mitnehmen),
     *             false fuer Variante a (schnellster Weg zum Waechter)
     */
    public void setCathedralRoute(boolean full) {
        setCathedralRoute(full ? "b" : "c");
    }

    /**
     * Weg ueber eine Etage der Kathedrale, als Buchstabe.
     *
     * @param weg "b" (alle Wegpunkte) oder "c"/"fast" (Turbo -- so schnell wie
     *            moeglich). Alles andere gilt als "c" -- auch "a": Autor,
     *            26.09.: "Weg a loeschen, hat keinen Sinn mehr den zu spielen."
     *            Alte Befehle und Warteschlangen mit "a" laufen so als c weiter.
     */
    public void setCathedralRoute(String weg) {
        GameMode mode = modes.get("cathedral");
        if (mode instanceof CathedralMode cathedral) {
            String w = weg == null ? "c" : weg.trim().toLowerCase();
            CathedralMode.Route route = switch (w) {
                case "b" -> CathedralMode.Route.FULL;
                case "c", "fast", "turbo" -> CathedralMode.Route.TURBO;
                default -> CathedralMode.Route.TURBO;   // auch "a": Weg a gibt es seit 26.09. nicht mehr
            };
            cathedral.setRoute(route);
        }
    }

    /**
     * !kisten (Test): loest im laufenden Kathedralen-Modus sofort den
     * Kisten-Gang aus (Kathedrale -> Burg -> Tasche -> Kisten -> zurueck),
     * auch wenn noch Schluessel da sind. Gibt false zurueck, wenn gerade keine
     * Kathedrale laeuft.
     */
    public boolean kathedraleKistenGang() {
        if (state == State.LAEUFT && activeMode instanceof CathedralMode cathedral) {
            cathedral.kistenGangAusloesen();
            return true;
        }
        return false;
    }

    /**
     * Nur den Kisten-Gang, ohne Kathedralen-Reise (Autor, 25.09.): geht aus
     * der Burg oder dem Kathedralen-Fenster in die Tasche, oeffnet die
     * Kathedralen-Kisten und hoert auf. Nur wenn gerade nichts laeuft.
     */
    public boolean nurKistenGang() {
        if (state == State.LAEUFT || state == State.PAUSIERT) return false;
        if (!(modes.get("cathedral") instanceof CathedralMode cathedral)) return false;
        cathedral.nurKistenGang();
        boolean ok = start("cathedral", 1);
        if (!ok) cathedral.nurKistenVergessen();
        return ok;
    }

    /** Welcher Weg gerade eingestellt ist, als Buchstabe "a"/"b"/"c". */
    public String cathedralRouteCode() {
        GameMode mode = modes.get("cathedral");
        if (mode instanceof CathedralMode cathedral) {
            return switch (cathedral.route()) {
                case FULL -> "b";
                case TURBO -> "c";
            };
        }
        return "c";
    }

    /**
     * Welcher Weg gerade eingestellt ist -- true heisst Variante b.
     *
     * Gebraucht, wenn ein Lauf unterbrochen und spaeter fortgesetzt wird.
     * Beim Clan-Krieg um 09:01 fehlte genau das: Der Rest der Kathedrale
     * kam danach mit "false" zurueck in die Warteschlange, also auf
     * Variante a. Wer abends "!start cathedral 200 b" tippt und morgens
     * nachsieht, haette den Weg gewechselt vorgefunden, ohne es je
     * angeordnet zu haben.
     */
    public boolean cathedralRouteIstB() {
        GameMode mode = modes.get("cathedral");
        if (mode instanceof CathedralMode cathedral) {
            return cathedral.route() == CathedralMode.Route.FULL;
        }
        return false;
    }

    /** Setzt die Anzahl fuer den naechsten Start, etwa ueber "!start 25". */
    public void setLastRuns(int runs) {
        this.lastRuns = Math.max(1, Math.min(1_000_000, runs));   // 29.09.: 500er-Grenze raus (Autor)
    }

    public void setStopAfterMinutes(int minutes) {
        this.stopAfterMinutes = Math.max(0, minutes);
    }


    /**
     * Legt die Uhrzeit fest, zu der der Bot von selbst aufhoert, etwa "06:30".
     *
     * Gerechnet wird mit einem festen Zeitpunkt, nicht mit einem Vergleich von
     * Uhrzeiten: gemeint ist immer das NAECHSTE Mal, dass diese Uhrzeit
     * erreicht wird. Sagt Autor um 23 Uhr "!ende 06:30", ist damit der naechste
     * Morgen gemeint; sagt er es um 5 Uhr, ist es der gleiche Morgen.
     *
     * Ein leerer Text schaltet die Abschaltzeit wieder ab.
     */
    public void setStopAtClock(String clock) {
        // 01.11. (16:44, mitten im Turnier beendet): eine schon erreichte alte Stoppzeit liess finishingSince stehen. Beim Erreichen
        // der neuen galt der Durchlauf sofort als "beendet", weil der alte Zaehlerstand verglichen wurde.
        finishingSince = 0;
        stoppzeitSetzen(clock);
        stoppzeitMerken();
    }

    private void stoppzeitSetzen(String clock) {
        String value = clock == null ? "" : clock.trim();

        if (!value.matches("\\d{1,2}:\\d{2}")) {
            this.stopAtClock = "";
            this.stopAtMillis = 0;
            return;
        }

        String[] parts = value.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour > 23 || minute > 59) {
            this.stopAtClock = "";
            this.stopAtMillis = 0;
            return;
        }

        // Konrad 19.11.: richtige Weltzeit (Windows-Uhr kann falsch gehen); verglichen wird spaeter mit der Rechneruhr.
        // 20.11. (Geschwister): in der eingestellten Zone des Spielers (ohne Einstellung Europe/Berlin) -- bisher fest Berlin,
        // ein Freund in London ("!ende 06:30") haette eine Stunde zu frueh aufgehoert. Zeitleiste rechnet genauso.
        java.time.ZonedDateTime now = java.time.ZonedDateTime.ofInstant(de.hustlebot.core.Weltuhr.jetzt(),
                de.hustlebot.core.Zeitzone.zone());
        java.time.ZonedDateTime target = now
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!target.isAfter(now)) target = target.plusDays(1);

        this.stopAtClock = String.format("%02d:%02d", hour, minute);
        this.stopAtMillis = de.hustlebot.core.Weltuhr.rechnerMs(target.toInstant());

        Log.info("Motor", de.hustlebot.core.Sprache.t(
                "Stop time set: " + this.stopAtClock + " on "
                + target.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))
                + " (in " + minutesUntilStop() + " minutes).",
                "Abschaltzeit gesetzt: " + this.stopAtClock + " am "
                + target.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))
                + " (in " + minutesUntilStop() + " Minuten)."));
    }

    /** Minuten bis zur Abschaltzeit, oder -1 wenn keine gesetzt ist. */
    public long minutesUntilStop() {
        if (stopAtMillis <= 0) return -1;
        long remaining = stopAtMillis - System.currentTimeMillis();
        return remaining <= 0 ? 0 : remaining / 60_000;
    }

    /** Der Zeitpunkt der Abschaltung als Millisekunden, oder 0. */
    public long stopAtMillis() { return stopAtMillis; }

    /** True, wenn der letzte Lauf alle geplanten Durchlaeufe geschafft hat. */
    public boolean zielErreicht() { return zielErreicht; }

    /** True, wenn der letzte Lauf wegen der Zeitgrenze (!ende / !off) aufgehoert hat (18.11.). */
    public boolean durchZeitgrenzeBeendet() { return durchZeitgrenzeBeendet; }

    /** Was der eben fertig gewordene Modus noch angehaengt haben will, der Reihe nach (einmal abholbar, nie null). */
    private volatile java.util.List<GameMode.Folge> folgen = java.util.List.of();

    public java.util.List<GameMode.Folge> folgenAbholen() {
        java.util.List<GameMode.Folge> f = folgen;
        folgen = java.util.List.of();
        return f == null ? java.util.List.of() : f;
    }

    /**
     * Bittet den Motor, nach dem laufenden Durchlauf aufzuhoeren, damit ein
     * anderer Modus drankommen kann.
     *
     * Gedacht fuer "!start kathedrale 50" mitten im Verlies. Der angefangene
     * Durchlauf wird noch zu Ende gespielt -- ihn abzubrechen hiesse, eine
     * halb gelaufene Fackel wegzuwerfen.
     *
     * @return false, wenn gerade gar nichts laeuft
     */
    // ------------------------------------------------------------------
    //  Captcha: erkennen, anhalten, Mensch rufen -- nie selbst loesen (15.11. / X3A2)
    // ------------------------------------------------------------------

    private final CaptchaWache captchaWache = new CaptchaWache(
            de.hustlebot.core.Captcha::wert,
            w -> w >= de.hustlebot.core.Captcha.SCHWELLE,
            new CaptchaWache.Folgen() {
                @Override
                public void neu(Mat screen, double wert) {
                    Log.alarm("Captcha", String.format(java.util.Locale.ROOT, de.hustlebot.core.Sprache.t(
                            "Captcha detected (match %.2f). The bot clicks NOTHING more -- please solve it in the game yourself. "
                            + "It carries on by itself once the captcha is gone.",
                            "Captcha erkannt (Treffer %.2f). Der Bot klickt NICHTS mehr -- bitte im Spiel selbst loesen. "
                            + "Er macht von allein weiter, sobald das Captcha weg ist."), wert));
                    captchaFotoSpeichern(screen);
                    setStatusText(de.hustlebot.core.Sprache.t(
                            "Captcha open – solve it in the game yourself. The bot waits.",
                            "Captcha offen – bitte im Spiel selbst loesen. Der Bot wartet."));
                    captchaMelden(String.format(java.util.Locale.ROOT, "Captcha (match %.2f)", wert));
                }

                @Override
                public void erinnerung(long minutenOffen) {
                    Log.alarm("Captcha", de.hustlebot.core.Sprache.t(
                            "The captcha has been open for " + minutenOffen + " minutes. The bot is still waiting for you.",
                            "Das Captcha ist seit " + minutenOffen + " Minuten offen. Der Bot wartet weiter auf dich."));
                    captchaMelden("Captcha open for " + minutenOffen + " min");
                }

                @Override
                public void weg(long sekundenOffen) {
                    Log.info("Captcha", de.hustlebot.core.Sprache.t(
                            "The captcha is gone (after " + sekundenOffen + " s). The bot carries on.",
                            "Das Captcha ist weg (nach " + sekundenOffen + " s). Der Bot macht weiter."));
                    setStatusText(de.hustlebot.core.Sprache.t("Captcha gone – carrying on.", "Captcha weg – es geht weiter."));
                }
            },
            // Standard AUS: nur "an" in captcha-wache.txt schaltet die Wache ein (Autor 29.10.: Captcha ausgebaut)
            new de.hustlebot.core.Schalterdatei(java.nio.file.Path.of("captcha-wache.txt"), "Captcha", false)::lesen);

    /** true, solange ein Captcha offen ist: niemand sonst darf klicken, der Aufrufer beginnt von vorn. */
    private boolean captchaPruefen(Mat screen) {
        try {
            return captchaWache.pruefen(screen, System.currentTimeMillis());
        } catch (Exception e) {
            Log.debug("Captcha", "Pruefung: " + e.getMessage());
            return captchaWache.offen();
        }
    }

    /** Bericht an den Menschen (Discord-Bericht/Haenger-Weg des Steuerprogramms). */
    private void captchaMelden(String text) {
        var bericht = haengerMelder;
        if (bericht == null) return;
        try {
            bericht.accept(text);
        } catch (Exception e) {
            Log.debug("Captcha", "Bericht: " + e.getMessage());
        }
    }

    /** Foto des Captchas in logs\ -- zum Nachstellen der Erkennung. */
    private void captchaFotoSpeichern(Mat screen) {
        try {
            String name = "logs/captcha_" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".png";
            org.opencv.imgcodecs.Imgcodecs.imwrite(name, screen);
            Log.info("Captcha", de.hustlebot.core.Sprache.t("Picture saved: " + name, "Foto gespeichert: " + name));
        } catch (Exception e) {
            Log.debug("Captcha", "Foto liess sich nicht speichern: " + e.getMessage());
        }
    }

    private final Einfuehrung einfuehrung = new Einfuehrung();

    /**
     * Autor 21.11.: Einfuehrung (Tutorial) eines Events selbst durchspielen, in JEDEM Modus. true = gehandelt oder neue
     * Bilder geholt -- das Bild ist veraltet, die Schleife nimmt neu auf. Nach dem Ende geht es zur Burg.
     */
    private boolean einfuehrungSchritt(Mat screen) {
        if (activeMode == null || activeMode.inEmpfindlicherPhase()) return false;   // Werbung: nie hineintippen
        Einfuehrung.Ergebnis e;
        try {
            e = einfuehrung.schritt(screen, ctx, activeMode.displayName());
        } catch (Exception ex) {
            Log.debug("Motor", "Einfuehrung: " + ex.getMessage());
            return false;
        }
        switch (e) {
            case GEHANDELT -> {
                if (tracker != null) tracker.progress(Sprache.t("Event introduction: step", "Event-Einfuehrung: Schritt"));
                return true;
            }
            case BILD_VERALTET -> {
                return true;
            }
            case FERTIG -> {
                try {
                    heimGehen();
                } catch (Exception ex) {
                    Log.debug("Motor", "Einfuehrung, Heimweg: " + ex.getMessage());
                }
                if (tracker != null) tracker.resetIdleClock();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private long dialogGeprueftUm;

    /** Erzaehltext mit Weiter-Pfeil (Vorlage dialog_weiter) wegtippen; hoechstens alle 1,5 s nachsehen. */
    private boolean dialogWegdruecken(Mat screen) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - dialogGeprueftUm < 1500 || !ctx.matcher().has("dialog_weiter")) return false;
        dialogGeprueftUm = jetzt;
        if (!ctx.clickIfVisibleIfKnown(screen, "dialog_weiter",
                de.hustlebot.core.Sprache.t("Story text -- tap it away", "Erzaehltext -- wegdruecken"))) return false;
        dialogGeprueftUm = 0;   // gleich wieder nachsehen: oft kommen mehrere Saetze hintereinander
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Laeuft gerade ein Arena-Turnier (Autor, 11.10. 15:00: begonnen, nicht beendet -- auch in der
     * Wartezeit zwischen den Stufen)? Dann warten Einschuebe und Spiel-Neustart. Gilt auch fuer das
     * Nachspiel (Turnier-Balken oben, obwohl ein anderer Modus dran war).
     */
    public boolean turnierLaeuft() {
        if (nachspiel != null) return true;
        return activeMode instanceof ArenaMode a && a.turnierLaeuft();
    }

    /**
     * Turnier-Balken oben (Autor, 11.10. 15:05: "Wait for the next stage of the tournament",
     * "Time left to select an opponent")? Beim ersten Schritt eines neuen Modus wird einmal
     * hingesehen; die Nahrungs-Leser melden ihn ausserdem (Turnierbalken). Steht er da, spielt
     * die Arena erst das Turnier zu Ende (Nachspiel), dann kommt der eigentliche Modus dran.
     * Fehlen der Arena Vorlagen, passiert nichts -- einmal ein Hinweis im Protokoll.
     */
    private void nachspielPruefen() {
        GameMode aktiv = activeMode;
        if (aktiv == null || aktiv instanceof ArenaMode
                || System.currentTimeMillis() < nachspielSperreBis) {
            ersterSchritt = false;
            Turnierbalken.vergessen();
            return;
        }
        boolean da;
        if (ersterSchritt) {
            ersterSchritt = false;
            da = Turnierbalken.sichtbar(ctx);
        } else {
            da = Turnierbalken.abholen(15_000);
        }
        if (!da) return;
        if (!(modes.get("arena") instanceof ArenaMode arena)) return;
        List<String> fehlt = ctx.matcher().missing(arena.requiredTemplates());
        if (!fehlt.isEmpty()) {
            if (!nachspielVorlagenGemeldet) {
                nachspielVorlagenGemeldet = true;
                Log.warn("Motor", de.hustlebot.core.Sprache.t(
                        "The arena tournament bar is showing at the top, but the arena is missing "
                        + fehlt.size() + " images -- the tournament cannot be finished.",
                        "Oben steht der Turnier-Balken der Arena, aber der Arena fehlen "
                        + fehlt.size() + " Vorlagen -- das Turnier kann nicht zu Ende gespielt werden."));
            }
            return;
        }
        arena.reset();
        arena.nurZuEndeSpielen(true);
        nachspielTracker = new RunTracker(1);
        nachspielTracker.setIdleTimeoutMinutes(idleTimeoutMinutes);
        nachspielSeit = System.currentTimeMillis();
        nachspiel = arena;
        if (tracker != null) tracker.progress(de.hustlebot.core.Sprache.t("Finishing the arena tournament", "Arena-Turnier wird zu Ende gespielt"));
        Log.hinweis("Arena", de.hustlebot.core.Sprache.t(
                "The tournament bar is still showing at the top -- the last arena tournament is still running. "
                + "It is finished first, then the bot continues with " + aktiv.displayName() + ".",
                "Oben steht noch der Turnier-Balken -- das letzte Arena-Turnier laeuft noch. "
                + "Erst wird es zu Ende gespielt, dann geht es mit " + aktiv.displayName() + " weiter."));
        setStatusText(de.hustlebot.core.Sprache.t("Finishing the arena tournament, then ", "Arena-Turnier wird zu Ende gespielt, danach ")
                + aktiv.displayName() + ".");
    }

    /** Ein Schritt des Nachspiels. FINISHED/FATAL/PAUSE der Arena beenden nur das Nachspiel, nie den Modus. */
    private GameMode.Outcome nachspielSchritt(Mat screen) {
        ArenaMode arena = nachspiel;
        if (arena == null) return GameMode.Outcome.WAITING;
        GameMode.Outcome o;
        try {
            o = arena.step(screen, ctx, nachspielTracker);
        } catch (Exception e) {
            Log.error("Motor", de.hustlebot.core.Sprache.t("Error in the arena follow-up", "Fehler im Arena-Nachspiel"), e);
            nachspielBeenden(de.hustlebot.core.Sprache.t("error in the arena", "Fehler in der Arena"), nachspielTracker.completedRuns() > 0);
            return GameMode.Outcome.WAITING;
        }
        if (o == GameMode.Outcome.ACTED || nachspielTracker.idleSeconds() < 2) {
            if (tracker != null) tracker.progress(de.hustlebot.core.Sprache.t("Finishing the arena tournament", "Arena-Turnier wird zu Ende gespielt"));
        }
        long dauer = System.currentTimeMillis() - nachspielSeit;
        boolean gespielt = nachspielTracker.completedRuns() > 0;
        if (o == GameMode.Outcome.FINISHED) {
            nachspielBeenden(gespielt ? de.hustlebot.core.Sprache.t("the tournament is over", "das Turnier ist zu Ende")
                                      : de.hustlebot.core.Sprache.t("no tournament open any more", "kein Turnier mehr offen"), gespielt);
        } else if (o == GameMode.Outcome.FATAL || o == GameMode.Outcome.PAUSE) {
            nachspielBeenden(de.hustlebot.core.Sprache.t("the arena got stuck (" + o + ")", "die Arena kam nicht weiter (" + o + ")"), gespielt);
        } else if (dauer > NACHSPIEL_FRIST_MS) {
            nachspielBeenden(de.hustlebot.core.Sprache.t("not finished after " + (dauer / 60000) + " minutes",
                    "nach " + (dauer / 60000) + " Minuten noch nicht zu Ende"), gespielt);
        } else if (nachspielTracker.idleSeconds() * 1000L > NACHSPIEL_STILL_MS) {
            nachspielBeenden(de.hustlebot.core.Sprache.t(nachspielTracker.idleSeconds() + " s without progress",
                    nachspielTracker.idleSeconds() + " s ohne Fortschritt"), gespielt);
        }
        return o == GameMode.Outcome.ACTED ? GameMode.Outcome.ACTED : GameMode.Outcome.WAITING;
    }

    private void nachspielBeenden(String grund, boolean turnierGespielt) {
        ArenaMode arena = nachspiel;
        nachspiel = null;
        if (arena != null) arena.nurZuEndeSpielen(false);
        Turnierbalken.vergessen();
        // Pruefung 11.10.: ohne gespieltes Turnier eine Weile kein neues Nachspiel (sonst Kreislauf).
        nachspielSperreBis = nachspielSperreBis(turnierGespielt, System.currentTimeMillis());
        if (!turnierGespielt) {
            Log.info("Motor", de.hustlebot.core.Sprache.t(
                    "For the next " + (NACHSPIEL_SPERRE_MS / 60000) + " minutes the engine starts no new arena follow-up.",
                    "Die naechsten " + (NACHSPIEL_SPERRE_MS / 60000)
                    + " Minuten faengt der Motor kein Arena-Nachspiel mehr an."));
        }
        String weiter = activeMode == null ? de.hustlebot.core.Sprache.t("the mode", "dem Modus") : activeMode.displayName();
        Log.info("Motor", de.hustlebot.core.Sprache.t(
                "Arena follow-up finished (" + grund + ") -- continuing with " + weiter + ".",
                "Arena-Nachspiel beendet (" + grund + ") -- jetzt weiter mit " + weiter + "."));
        if (tracker != null) tracker.progress(de.hustlebot.core.Sprache.t("continuing with " + weiter, "weiter mit " + weiter));
        setStatusText(de.hustlebot.core.Sprache.t("Continuing with ", "Weiter mit ") + weiter + ".");
    }

    /** Steht schon ein Moduswechsel an (Krieg, !start, Leuchtturm)? */
    public boolean wechselAngefordert() {
        return wechselAngefordert;
    }

    /** Der eingetragene Modus mit dieser Kennung, sonst null (Bruecke: vorab pruefen, ob seine Bilder da sind). */
    public GameMode modus(String id) {
        return modes.get(id);
    }

    /** Einen gewuenschten Wechsel zuruecknehmen (Hauptbot 02.10.: Arena-Pause, das Turnier begann doch). */
    public synchronized void wechselZuruecknehmen() {
        if (!wechselAngefordert) return;
        wechselAngefordert = false;
        if (activeMode instanceof ArenaMode a) a.wechselErbeten(false);
        Log.info("Motor", de.hustlebot.core.Sprache.t("Mode switch withdrawn -- the current mode carries on.",
                "Moduswechsel zurueckgenommen -- der laufende Modus macht weiter."));
    }

    public synchronized boolean wechselNachDurchlauf() {
        if (state != State.LAEUFT || tracker == null) return false;
        wechselAngefordert = true;
        runsBeimWechsel = tracker.completedRuns();
        wechselSeit = System.currentTimeMillis();
        wechselFertigSeit = 0;
        // Autor, 11.10. 15:00: Die Arena spielt das laufende Turnier zu Ende und faengt kein neues an.
        if (activeMode instanceof ArenaMode a) a.wechselErbeten(true);
        Log.info("Motor", de.hustlebot.core.Sprache.t(
                "Mode switch requested. The current run is finished first, "
                + "at most " + (WECHSEL_FRIST_MS / 60000) + " minutes.",
                "Moduswechsel gewuenscht. Der laufende Durchlauf wird noch "
                + "zu Ende gespielt, hoechstens " + (WECHSEL_FRIST_MS / 60000) + " Minuten."));
        return true;
    }

    public int stopAfterMinutes()  { return stopAfterMinutes; }
    public String stopAtClock()    { return stopAtClock; }
    public long sessionStartedAt() { return sessionStartedAt; }

    /**
     * Wird gerufen, wenn ein Start abgelehnt wird, weil Hustle Castle nicht
     * offen ist. Discord meldet es damit ruhig -- statt einer roten
     * Fehlerkarte, siehe start().
     */
    private volatile Runnable meldungOhneSpiel = () -> { };

    public void setMeldungOhneSpiel(Runnable melder) {
        this.meldungOhneSpiel = melder == null ? () -> { } : melder;
    }

    // ------------------------------------------------------------------
    //  Zurueck in die Burg -- !burg / !castle
    // ------------------------------------------------------------------
    /** Ist eine Rueckkehr in die Burg gewuenscht? */
    private volatile boolean heimGewuenscht = false;
    /** Wird gerufen, wenn der Bot die Burg erreicht hat (true) oder nicht (false). */
    private volatile java.util.function.Consumer<Boolean> heimFertig = ok -> { };
    /** So lange versucht der Bot hoechstens, in die Burg zu kommen. */
    private static final long HEIM_FRIST_MS = 2 * 60_000L;

    /** Ist der Weg in die Burg ("!burg") gewuenscht oder unterwegs? (Code-Pruefung 22.10.: dann kein Auto-Start.) */
    public boolean heimGewuenscht() {
        return heimGewuenscht;
    }

    public void setHeimFertig(java.util.function.Consumer<Boolean> cb) {
        this.heimFertig = cb == null ? ok -> { } : cb;
    }

    /** Bekommt GameMode.endeHinweis(), wenn ein Modus mit Grund aufhoert. */
    private volatile java.util.function.Consumer<String> endeHinweis = t -> { };

    public void setEndeHinweis(java.util.function.Consumer<String> cb) {
        this.endeHinweis = cb == null ? t -> { } : cb;
    }

    /**
     * Zurueck in die Burg. Autor, 16.09.: den laufenden Durchlauf noch zu Ende
     * spielen (oder bei Stillstand abbrechen), dann in die Burg und dort auf
     * den naechsten Befehl warten.
     *
     * Laeuft ein Modus, setzt das nur den Wunsch -- die Hauptschleife geht in
     * die Burg, sobald der Modus an einer sicheren Stelle ist (nicht mitten im
     * Verlies, siehe GameMode.darfHeim). Laeuft keiner, geht der Bot gleich in
     * einem eigenen Faden.
     */
    public synchronized void geheHeim() {
        heimGewuenscht = true;
        if (state == State.LAEUFT || state == State.PAUSIERT) {
            paused = false;   // damit die Schleife bis zur sicheren Stelle weiterlaeuft
            Log.info("Motor", de.hustlebot.core.Sprache.t(
                    "Return to the castle requested. First finish the run, then go to the castle.",
                    "Rueckkehr in die Burg gewuenscht. Erst den Durchlauf zu Ende, "
                    + "dann in die Burg."));
            return;
        }
        Thread t = new Thread(this::heimGehenUndMelden, "heim-gang");
        t.setDaemon(true);
        t.start();
    }

    /** Geht in die Burg und meldet danach, ob es geklappt hat. Setzt den Bot auf "wartet". */
    private void heimGehenUndMelden() {
        boolean ok;
        try {
            ok = heimGehen();
        } catch (Exception e) {
            Log.error("Motor", de.hustlebot.core.Sprache.t("Something went wrong on the way back to the castle.",
                    "Beim Rueckweg in die Burg ist etwas schiefgegangen."), e);
            ok = false;
        }
        heimGewuenscht = false;

        // Kam waehrend des Rueckwegs ein !stop, wird NICHT "in der Burg" oder
        // "konnte die Burg nicht erreichen" gemeldet -- der Stopp setzt den
        // Zustand selbst. Sonst ueberschriebe diese Meldung den Stopp.
        if (stopRequested) return;

        // BEREIT loest in Discord KEINE eigene Meldung aus -- die kommt gleich
        // ueber heimFertig, mit dem Wortlaut, den Autor wollte.
        setState(State.BEREIT, ok ? de.hustlebot.core.Sprache.t("In the castle. Waiting for the next command.", "In der Burg. Wartet auf den naechsten Befehl.")
                                  : de.hustlebot.core.Sprache.t("Could not reach the castle safely.", "Konnte die Burg nicht sicher erreichen."));
        try {
            heimFertig.accept(ok);
        } catch (Exception ignored) {
            // Eine misslungene Discord-Meldung darf hier nichts weiter kaputtmachen.
        }
    }

    /**
     * Klickt sich zur Burg zurueck: Home, Belohnung schliessen, Verlassen
     * bestaetigen, Fenster/rotes X. In der Burg erkennt der Bot den
     * "Map"-Knopf unten links -- dann ist er da.
     */
    /**
     * Vor Leuchtturm/Kolosseum: erst in die Burg -- ausser das Spiel steht schon in
     * der Burg oder auf der Weltkarte, dort findet der Besuch seinen Weg selbst.
     * Klappt der Heimweg nicht, versucht es der Besuch trotzdem (wie bisher).
     */
    private void vorBesuchInDieBurg() {
        Mat screen = ctx.refresh();
        if (screen != null && (sichtbar(screen, "map_button") || sichtbar(screen, "map_button_alt")
                || Weltkarte.erkannt(screen, ctx))) {
            return;
        }
        Log.info("Motor", de.hustlebot.core.Sprache.t(
                activeMode.displayName() + ": The game is not in the castle -- going there first.",
                activeMode.displayName()
                + ": Das Spiel steht nicht in der Burg -- erst dorthin."));
        // 31.10. (Bericht UK6L, Ben): der Heimweg vor dem Leuchtturm zaehlte nicht als Fortschritt -- nach 14 s Heimweg
        // kam der Haenger-Alarm "last: Start", obwohl der Bot 1 s vorher die Karte oeffnete. Innerhalb des Heimwegs
        // bleibt die Uhr stehen, ein echter Haenger dort wird also weiter gemeldet.
        tracker.progress("Going to the castle first");
        boolean daheim = heimGehen();
        if (daheim) tracker.progress("Castle reached");
        if (!daheim && !stopRequested) {
            Log.warn("Motor", de.hustlebot.core.Sprache.t(
                    "The castle could not be reached safely -- " + activeMode.displayName() + " tries anyway.",
                    "Die Burg war nicht sicher zu erreichen -- "
                    + activeMode.displayName() + " versucht es trotzdem."));
        }
    }

    private boolean heimGehen() {
        Log.info("Motor", de.hustlebot.core.Sprache.t("The bot is going back to the castle.", "Der Bot geht zurueck in die Burg."));
        long ende = System.currentTimeMillis() + HEIM_FRIST_MS;
        int unbekannt = 0;

        // !stop bricht auch einen schon laufenden Rueckweg sofort ab.
        while (System.currentTimeMillis() < ende && !stopRequested) {
            // Laeuft der Heimweg im Arbeitsfaden (vor Leuchtturm/Kolosseum), darf die
            // Wache in Discord nicht "Bot antwortet nicht" melden.
            lastLoopHeartbeat = System.currentTimeMillis();
            Mat screen = ctx.refresh();
            if (screen == null) { schlaf(800); continue; }

            // 15.11. / X3A2: Captcha? Dann hier nichts klicken (das rote X waere sofort eine Kampfsperre). Die Zeit, die
            // es offen ist, zaehlt nicht gegen die Frist.
            long vorCaptcha = System.currentTimeMillis();
            if (captchaPruefen(screen)) {
                ende += System.currentTimeMillis() - vorCaptcha + 300;
                continue;
            }

            // In der Burg? Der "Map"-Knopf unten links ist ueberall in der Burg da.
            if (sichtbar(screen, "map_button") || sichtbar(screen, "map_button_alt")) {
                Log.info("Motor", de.hustlebot.core.Sprache.t("The castle is reached.", "Die Burg ist erreicht."));
                return true;
            }

            // Gebietskarte (Schwert-Stuetzpunkte, "Invasion!"; 20.11. nach der Arena): der "Map"-Knopf sieht dort aus wie in der
            // Burg, zaehlt aber nicht als Burg (BotContext.visible). Von dort fuehrt der Knopf weiter zur Weltkarte, dann "Home".
            if (ctx.gebietskarte(screen)
                    && (klickWennDa(screen, "map_button", Sprache.t("Territory map: Map button (to the castle)", "Gebietskarte: Map-Knopf (zur Burg)"))
                    || klickWennDa(screen, "map_button_alt", Sprache.t("Territory map: Map button (to the castle)", "Gebietskarte: Map-Knopf (zur Burg)")))) {
                unbekannt = 0;
                ctx.sleepUntilChanged(1500);
                continue;
            }

            // Auf der Weltkarte? Dort fuehrt "Home" unten links in die Burg.
            // (27.09., 09:20: Der Bot stand auf der Weltkarte, fand keinen
            // der Knoepfe unten und gab nach zwei Minuten auf.)
            if (Weltkarte.erkannt(screen, ctx)) {
                Weltkarte.heim(screen, ctx, de.hustlebot.core.Sprache.t("Home", "Heim"));
                unbekannt = 0;
                continue;
            }

            // Sonst irgendetwas druecken, das Richtung Burg fuehrt.
            //
            // cancel_search steht ganz vorn: Wartet der Bot gerade auf einen
            // Gegner, muss erst diese Suche beendet werden, sonst fuehrt kein
            // Home-Knopf hinaus (Autor, 17.09.). Danach liegt der Home-Knopf
            // unten links wieder frei.
            //
            // Eine FERTIGE Verlies-Reise (gruener "End journey") wird beendet statt mit
            // "Home" offen gelassen; Nachfrage, Warnung und Abschlussfenster danach raeumt
            // Stoerfenster ab (29.09., gleiche Regeln wie Leuchtturm und Notausgang). Die
            // Klicks auf end_journey_yes/quit_yes weiter unten bleiben fuer !burg nach
            // einem "End journey" des Verlieses selbst.
            // "Are you still here?" mit "Update" (30.09., 00:20: nach Autors F9 stand das
            // Spiel lange, der Heimweg vor dem Leuchtturm kannte das Fenster nicht).
            // Spieler selbst im Spiel (Hauptbot 62b1968): Heimweg abbrechen, nichts druecken.
            if (Stoerfenster.autorIstDrin(screen, ctx)) {
                Log.info("Motor", Sprache.t("Trip home aborted: logged in from another device -- the bot waits for Resume.",
                        "Heimweg abgebrochen: von einem anderen Geraet angemeldet -- der Bot wartet auf Weiter."));
                return false;
            }
            if (Stoerfenster.afkUpdate(screen, ctx, de.hustlebot.core.Sprache.t("Home", "Heim"))) {
                unbekannt = 0;
                continue;
            }
            // Unfertiges Verlies (roter "End journey"; 30.09., 02:18: Autor hatte den Bot beim
            // Aufstellen gestoppt, der Run lief ohne ihn an): nach ~12 s ohne Fortschritt
            // abbrechen -- der Run ist ohnehin verloren, festhaengen waere schlimmer.
            if (unbekannt >= 15 && sichtbar(screen, "end_journey") && !sichtbar(screen, "end_journey_green")
                    && klickWennDa(screen, "end_journey", de.hustlebot.core.Sprache.t("Abort unfinished dungeon (End journey)", "Unfertiges Verlies abbrechen (End journey)"))) {
                Log.warn("Motor", de.hustlebot.core.Sprache.t(
                        "The game was in an unfinished dungeon -- aborted to get to the castle.",
                        "Das Spiel stand in einem unfertigen Verlies -- abgebrochen, um in die Burg zu kommen."));
                unbekannt = 0;
                ctx.sleepUntilChanged(1500);
                continue;
            }
            boolean geklickt =
                    klickWennDa(screen, "cancel_search", de.hustlebot.core.Sprache.t("Cancel opponent search (to the castle)", "Gegnersuche abbrechen (zur Burg)"))
                 || Stoerfenster.verliesRestAbraeumen(screen, ctx, de.hustlebot.core.Sprache.t("Home", "Heim"))
                 // Nicht durch ein Fenster hindurch: der Knopf passt auch abgedunkelt.
                 || (!sichtbar(screen, "close_reward") && !sichtbar(screen, "close_x")
                     && Stoerfenster.fertigeVerliesReiseBeenden(screen, ctx, de.hustlebot.core.Sprache.t("Home", "Heim")))
                 || klickWennDa(screen, "home_button", de.hustlebot.core.Sprache.t("Go home (Home)", "Nach Hause (Home)"))
                 // Ergebnis-Bildschirme mit eigenem "Home" (30.09., 02:37: Turm "Auto battle finished").
                 || klickWennDa(screen, "tt_home", de.hustlebot.core.Sprache.t("Confirm tower result (Home)", "Turm-Ergebnis bestaetigen (Home)"))
                 || klickWennDa(screen, "ar_home_result", de.hustlebot.core.Sprache.t("Confirm arena result (Home)", "Arena-Ergebnis bestaetigen (Home)"))
                 || klickWennDa(screen, "dc_home_done", de.hustlebot.core.Sprache.t("Confirm cathedral completion (Home)", "Kathedralen-Abschluss bestaetigen (Home)"))
                 || klickWennDa(screen, "close_reward", de.hustlebot.core.Sprache.t("Close reward", "Belohnung schliessen"))
                 || klickWennDa(screen, "bag_close", de.hustlebot.core.Sprache.t("Close chest screen (Close)", "Kisten-Bildschirm schliessen (Close)"))
                 || klickWennDa(screen, "end_journey_yes", de.hustlebot.core.Sprache.t("Confirm leaving", "Verlassen bestaetigen"))
                 || klickWennDa(screen, "quit_yes", de.hustlebot.core.Sprache.t("Confirm leaving", "Verlassen bestaetigen"))
                 || klickWennDa(screen, "home", de.hustlebot.core.Sprache.t("Confirm battle result", "Kampfergebnis bestaetigen"))
                 || klickWennDa(screen, "close_x", de.hustlebot.core.Sprache.t("Close window", "Fenster schliessen"));
            if (geklickt) {
                unbekannt = 0;
                ctx.sleepUntilChanged(1500);
                continue;
            }

            // Etagenkarte der Kathedrale (30.09., 18:20: Neustart mit einem Fix, die naechste
            // Reise hatte schon begonnen -- kein Knopf passte, zwei Minuten Stillstand, danach
            // brach der Leuchtturm ab und der ganze Ablauf stand). "Home" unten links fuehrt in
            // die Burg; die Reise bleibt offen und wird von der Kathedrale weitergespielt.
            if (sichtbar(screen, "dc_floor_hint")) {
                Weltkarte.heim(screen, ctx, de.hustlebot.core.Sprache.t("Home (from the cathedral)", "Heim (aus der Kathedrale)"));
                unbekannt = 0;
                continue;
            }

            // Unbekannter Bildschirm: den Notausgang (rotes X) versuchen.
            if (Stoerfenster.notausgang(screen, ctx, de.hustlebot.core.Sprache.t("Home", "Heim"), ++unbekannt)) {
                ctx.sleepUntilChanged(1200);
                continue;
            }
            schlaf(800);
        }
        return false;
    }

    /** Sichtbar, aber still, wenn es die Vorlage gar nicht gibt. */
    private boolean sichtbar(Mat screen, String name) {
        return ctx.matcher().has(name) && ctx.visible(screen, name);
    }

    /** Klickt, aber nur wenn die Vorlage geladen und zu sehen ist. */
    private boolean klickWennDa(Mat screen, String name, String grund) {
        return ctx.matcher().has(name) && ctx.clickIfVisible(screen, name, grund);
    }

    private void schlaf(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void stop(String reason) {
        if (state != State.LAEUFT && state != State.PAUSIERT) return;
        Log.info("Motor", de.hustlebot.core.Sprache.t("Stop requested: " + reason, "Stopp angefordert: " + reason));
        stopRequested = true;
        // Ein noch offener !burg-Wunsch wird mitgestoppt. Sonst ging der Bot
        // nach dem !stop trotzdem noch in die Burg (Autor, 17.09.: "Er hat den
        // Dungeon Run gestoppt, aber danach immernoch versucht in die Burg zu
        // gehen"). heimGehen() prueft stopRequested jetzt zusaetzlich selbst,
        // damit auch ein schon laufender Rueckweg sofort abbricht.
        heimGewuenscht = false;
        paused = false;
        if (worker != null) worker.interrupt();
    }

    /** Pause durch die Mauswache: nur wenn der Bot laeuft (kein Umschalter). true = jetzt pausiert. */
    public synchronized boolean pausierenWennLaeuft() {
        if (state != State.LAEUFT || paused) return false;
        paused = true;
        setState(State.PAUSIERT, de.hustlebot.core.Sprache.t("Paused (mouse moved).", "Pausiert (Maus bewegt)."));
        Log.info("Motor", de.hustlebot.core.Sprache.t("Paused (mouse moved).", "Pausiert (Maus bewegt)."));
        return true;
    }

    /** Hintergrund-Modus (Autor 20.11.): Autor hat das Spiel geoeffnet -- pausieren. true = jetzt pausiert. */
    public synchronized boolean pausierenFuerAutor() {
        if (state != State.LAEUFT || paused) return false;
        paused = true;
        modusPauseUm = 0;
        String t = de.hustlebot.core.Sprache.t("Paused (you opened the game).", "Pausiert (du hast das Spiel geoeffnet).");
        setState(State.PAUSIERT, t);
        Log.info("Motor", t);
        return true;
    }

    /** Weiter durch die Mauswache nach 5 min Ruhe. true = jetzt fortgesetzt. */
    public synchronized boolean fortsetzenOhneAutor() {
        if (state != State.PAUSIERT) return false;
        paused = false;
        modusPauseUm = 0;
        setState(State.LAEUFT, de.hustlebot.core.Sprache.t("Resumed (5 min without mouse movement).",
                "Fortgesetzt (5 min keine Mausbewegung)."));
        Log.info("Motor", de.hustlebot.core.Sprache.t("Resumed (mouse watch).", "Fortgesetzt (Mauswache)."));
        if (tracker != null) tracker.resetIdleClock();
        return true;
    }

    public synchronized void togglePause() {
        if (state == State.LAEUFT && !paused) {
            paused = true;
            modusPauseUm = 0;   // Autors eigene Pause
            setState(State.PAUSIERT, de.hustlebot.core.Sprache.t("Paused.", "Pausiert."));
            Log.info("Motor", de.hustlebot.core.Sprache.t("Paused.", "Pausiert."));
        } else if (state == State.PAUSIERT) {
            paused = false;
            modusPauseUm = 0;
            setState(State.LAEUFT, de.hustlebot.core.Sprache.t("Resumed.", "Fortgesetzt."));
            Log.info("Motor", de.hustlebot.core.Sprache.t("Resumed.", "Fortgesetzt."));
            if (tracker != null) tracker.resetIdleClock();
        }
    }

    private void setState(State newState, String text) {
        State alt = this.state;
        this.state = newState;
        this.statusText = text;

        // Hintergrund-Modus (Autor 20.11.): !stop -> Spiel wieder sichtbar; laeuft der Bot wieder -> wieder unsichtbar.
        if (newState != alt) {
            try {
                if (newState == State.GESTOPPT) de.hustlebot.core.Hintergrund.nachStopp();
                else if (newState == State.LAEUFT) de.hustlebot.core.Hintergrund.botLaeuft();
            } catch (Throwable ignored) {
                // der Hintergrund-Modus darf den Motor nie stoeren
            }
        }

        // Pausenzeit aus der Laufzeit herausrechnen -- egal, ob per !pause
        // oder weil der Bot selbst auf einen Menschen wartet.
        RunTracker t = tracker;
        if (t != null) {
            if (newState == State.PAUSIERT && alt != State.PAUSIERT) t.pauseBeginnt();
            else if (alt == State.PAUSIERT && newState != State.PAUSIERT) t.pauseEndet();
        }

        // Ein Modus ist zu Ende: die Warteschlange sofort weiterschalten lassen,
        // nicht erst beim naechsten Zwei-Sekunden-Takt (Autor, 26.09.: nach dem
        // Kolosseum stand der Bot ~2 s auf der Karte, bevor es weiterging).
        if (newState != alt && (newState == State.FERTIG || newState == State.FEHLER)) {
            try {
                nachEnde.run();
            } catch (Exception ignored) {
                // Die Bruecke darf den Motor nicht stoeren.
            }
        }
    }

    /**
     * Ein Botengang zwischen den Runs -- Leuchtturm, Kolosseum, Clan-Krieg.
     * Er zaehlt nicht als Durchlauf und steht in keiner Statistik.
     */
    /**
     * Lernmodus (Autor, 27.09.): War der letzte Abbruch ein Haenger -- der
     * Modus kam nicht weiter (FATAL) oder es tat sich eine Viertelstunde
     * nichts? Nicht bei Hitze, voller Platte oder fehlendem Spielfenster:
     * dafuer gibt es nichts zu lernen. Liefert den Wert nur EINMAL.
     */
    private volatile boolean haengerAbbruch;

    public boolean haengerAbholen() {
        boolean war = haengerAbbruch;
        haengerAbbruch = false;
        return war;
    }

    public static boolean istBotengang(String modus) {
        return "lighthouse".equals(modus) || "colosseum".equals(modus) || "clanwar".equals(modus)
                || "titantower".equals(modus) || "clanladen".equals(modus)
                || "freundesenden".equals(modus) || "teamscan".equals(modus);
    }

    /** Wird gerufen, sobald ein Modus fertig ist (die Bruecke schaltet dann weiter). */
    private volatile Runnable nachEnde = () -> { };

    public void setNachEnde(Runnable r) {
        this.nachEnde = r == null ? () -> { } : r;
    }

    private void setStatusText(String text) {
        this.statusText = text;
    }

    public void setIdleTimeoutMinutes(int minutes) {
        this.idleTimeoutMinutes = minutes;
    }

    /** Frist, nach der der Bot als Mitspieler ein Verlies verlaesst, wenn der Leiter nicht waehlt. */
    public void setLeaderPickTimeoutMinutes(int minutes) {
        this.leaderPickTimeoutMinutes = Math.max(1, minutes);
    }

    /** Frist, nach der der Bot aussteigt, wenn der Mitspieler nach "Ready" nicht reagiert. */
    public void setPartnerReadyTimeoutMinutes(int minutes) {
        this.partnerReadyTimeoutMinutes = Math.max(1, minutes);
    }

    /**
     * Wenn false, startet der Bot auch mit fehlenden Vorlagen. Nur zum
     * Ausprobieren, im Alltag sollte das eingeschaltet bleiben.
     */
    public void setRequireAllTemplates(boolean required) {
        this.requireAllTemplates = required;
    }

    public void setTickDelayMillis(long millis) {
        this.tickDelayMillis = millis;
    }

    /** Wie schnell neu geschaut wird, wenn gerade nichts geklickt wird. */
    public void setScanDelayMillis(long millis) {
        // Untergrenze 24.09. von 120 auf 40: Waehrend eines Kampfs schaut der
        // Bot so oefter nach dem Ergebnis. Laeuft der Rechner heiss, bremst
        // isThrottled() weiterhin auf das Vierfache.
        this.scanDelayMillis = Math.max(40, Math.min(3000, millis));
    }

    public State state()            { return state; }
    /**
     * Update ("Verbindung neu aufbauen"), Spielfehler ("Try again"), "anderes Geraet" und die Warnung "Burg angreifbar"
     * in JEDEM Modus (Hauptbot 442b1da/4536b1b): sonst hing z. B. die Kathedrale 15 min vor dem Update-Fenster.
     * Alle 2 s; die Kathedrale prueft "anderes Geraet" weiter selbst.
     */
    private GameMode.Outcome allgemeineStoerung(org.opencv.core.Mat screen) {
        if (activeMode == null) return null;
        // Bericht #K4AL (Ben 26.10.): Server-Wartung -- warten statt "Vorlagen fehlen" und Stopp. Jedes Bild, solange sie steht.
        if (Stoerfenster.serverWartung(ctx, activeMode.displayName(), tracker == null ? 0 : tracker.idleSeconds())) {
            if (tracker != null && Stoerfenster.wartungSeitMs() < 3 * 60 * 60_000L) {
                tracker.progress(Sprache.t("Server maintenance: waiting", "Server-Wartung: warte"));
            }
            ctx.sleep(2000);
            return GameMode.Outcome.WAITING;
        }
        // Code-Pruefung 16.10.: In einer empfindlichen Phase (z. B. Nahrung per Werbevideo) NICHTS allgemein klicken.
        // Diese Huelle drueckt sonst "Update" oder "Try again" in der Bildmitte und koennte in die Werbung treffen --
        // der Modus selbst (z. B. Clan-Krieg) laesst waehrend der Werbung bewusst alles liegen.
        if (activeMode.inEmpfindlicherPhase()) return null;
        long jetzt = System.currentTimeMillis();
        if (jetzt - stoerAllgemeinUm < 2000) return null;
        stoerAllgemeinUm = jetzt;
        if (ctx.clickIfVisibleIfKnown(screen, "st_got_it", "Warning (castle vulnerable): Got it")) {
            ctx.sleepUntilChanged(1200);
            return GameMode.Outcome.ACTED;
        }
        String wo = activeMode.displayName();
        // Autor 21.11.: "Congratulations! ... See your mailbox" -- Go to mailbox, Mailbox gleich wieder zu, weiter.
        if (Stoerfenster.glueckwunschMailbox(screen, ctx, wo)) return GameMode.Outcome.ACTED;
        if (Stoerfenster.afkUpdate(screen, ctx, wo)) return GameMode.Outcome.ACTED;
        if ("cathedral".equals(activeMode.id())) return null;
        switch (Stoerfenster.anderesGeraet(screen, ctx, wo)) {
            case ANHALTEN -> { return GameMode.Outcome.PAUSE; }
            case GEKLICKT -> {
                if (tracker != null) tracker.progress(Sprache.t("Logged in again after the kick", "Nach Rauswurf neu angemeldet"));
                return GameMode.Outcome.ACTED;
            }
            default -> { return null; }
        }
    }

    private long stoerAllgemeinUm = 0;

    public String statusText()      { return statusText; }
    public BotContext context()     { return ctx; }
    public RunTracker tracker()     { return tracker; }
    public GameMode activeMode()    { return activeMode; }

    /**
     * Seit wie vielen Millisekunden die Hauptschleife keine Runde mehr
     * gedreht hat. Nur aussagekraeftig, solange der Motor LAEUFT oder
     * PAUSIERT ist -- siehe {@link #lastLoopHeartbeat}.
     */
    public long heartbeatAlterMs() {
        return System.currentTimeMillis() - lastLoopHeartbeat;
    }

    public Map<String, String> availableModes() {
        Map<String, String> result = new LinkedHashMap<>();
        modes.forEach((id, mode) -> result.put(id, mode.displayName()));
        return result;
    }
}
