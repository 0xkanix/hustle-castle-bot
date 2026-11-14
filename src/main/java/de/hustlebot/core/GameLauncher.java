package de.hustlebot.core;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;

/**
 * Startet Hustle Castle von selbst.
 *
 * Gedacht fuer den Fall, dass Autor nicht zu Hause ist: Strom ueber die
 * Shelly-Steckdose an, der Rechner faehrt hoch, der Bot startet mit -- und
 * dann fehlt nur noch das Spiel. Genau das macht diese Klasse.
 *
 * WARUM NICHT EINFACH HC.EXE STARTEN
 *
 * Das war der erste Versuch, und er sah sogar aus, als wuerde er
 * funktionieren: Das Fenster ging auf, der Bot meldete Erfolg. Im Spiel
 * stand aber:
 *
 *     "Authentication error -- Account authentication failed.
 *      Please restart the game using MyGames GameCenter"
 *
 * Der Launcher gibt dem Spiel beim Start ein Anmeldezeichen mit
 * (--sz_token= und --sz_pers_id=, nachzulesen in der Datei last.xml des
 * Spiels). Ohne das kommt das Spiel nicht an Autors Burg. Ein Fenster ist
 * also noch kein laufendes Spiel -- diese Klasse geht deshalb IMMER ueber
 * den Launcher.
 *
 * Passwoerter braucht es dafuer keine: Der Launcher ist dauerhaft
 * angemeldet und reicht die Anmeldung von selbst weiter.
 *
 * WIE DER KNOPF GEFUNDEN WIRD
 *
 * Nicht an einer festgelegten Stelle -- das Launcher-Fenster ist mal 1266,
 * mal 1320 Bildpunkte breit, und der Kopfbereich ist immer gleich hoch
 * statt mitzuwachsen. Ein fester Anteil der Fensterhoehe lag beim Nachmessen
 * zwanzig Bildpunkte daneben und haette knapp ueber dem Knopf gelandet.
 *
 * Stattdessen wird nach der FARBE gesucht. Der Knopf "Spielen" ist genau
 * RGB 0/90/255, ein Blau, das sonst nur noch das MY.GAMES-Logo hat -- und
 * das sitzt links. Gesucht wird deshalb nur in der rechten Haelfte.
 *
 * WICHTIG: Diese Klasse startet nur. Sie klickt nichts im Spiel an und
 * entscheidet auch nicht, ob ein Modus laufen soll -- das bleibt bei den
 * Befehlen aus Discord.
 */
public final class GameLauncher {

    /** Der Launcher. */
    private static final String LAUNCHER_EXE =
            "C:/MY.GAMES/MY.GAMES Launcher/MGL.exe";

    /**
     * Adresse, mit der der Launcher gleich auf der Hustle-Castle-Seite
     * aufgeht. Die Nummer 0.2002058 ist die Kennung des Spiels bei MY.GAMES.
     *
     * Sie startet das Spiel NICHT von selbst -- ausprobiert am 31.08.: Der
     * Launcher ging auf, das Spiel kam nicht. Sie sorgt nur dafuer, dass die
     * richtige Seite offen ist und der Knopf ueberhaupt da ist.
     */
    private static final String SPIEL_URL = "mygames://show/0.2002058/";

    /** Fenstertitel des Launchers. */
    private static final String LAUNCHER_TITEL = "MY.GAMES Launcher";

    /** Die Farbe des Knopfes "Spielen": ein kraeftiges Blau. */
    private static final int KNOPF_R = 0;
    private static final int KNOPF_G = 90;
    private static final int KNOPF_B = 255;

    /** Wie weit die Farbe abweichen darf. */
    private static final int FARB_TOLERANZ = 14;

    /**
     * So viele Punkte muessen es mindestens sein, damit es der Knopf ist.
     * Er ist rund 210 mal 40 Punkte gross, also gut achttausend. Bei unter
     * eintausend ist es eher ein blauer Fleck im Bild der Spielwerbung.
     */
    private static final int MIN_PUNKTE = 1000;

    /** Wie lange auf das Spielfenster gewartet wird. */
    private static final long WARTEN_SPIEL_MS = 120_000;

    /**
     * Wie lange auf den Knopf "Spielen" gewartet wird.
     *
     * Fuenf Minuten, von Autor so gewaehlt. Der Grund ist das Update des
     * Launchers: Waehrend es laeuft, gibt es den Knopf nicht.
     */
    private static final long KNOPF_GEDULD_MS = 5 * 60_000;

    /**
     * Nach dieser Zeit ohne Knopf geht eine Meldung nach Discord.
     *
     * Ein Bot, der schweigend wartet, sieht von aussen aus wie ein kaputter
     * Bot. Eine halbe Minute ist frueh genug, um Bescheid zu wissen, und
     * spaet genug, dass es bei einem normalen Start nie dazu kommt.
     */
    private static final long MELDEN_NACH_MS = 30_000;

    /** So lange ohne Knopf, dann auf die Hustle-Castle-Seite wechseln (29.09.). */
    private static final long SEITE_ABSTAND_MS = 12_000;

    /**
     * Wie lange nach einem Klick auf das Spielfenster gewartet wird, bevor
     * nachgesehen wird, ob der Klick ueberhaupt gewirkt hat.
     *
     * Kurz gehalten: Ein verpuffter Klick soll schnell auffallen. Hat er
     * gewirkt, wird danach ohnehin in Ruhe weitergewartet.
     */
    private static final long NACH_KLICK_MS = 20_000;

    /**
     * Abstand zwischen zwei Aufrufen des Launchers.
     *
     * Solange sein Fenster noch NIE dagewesen ist, faehrt er vermutlich
     * gerade erst hoch -- nach einem Neustart des Rechners dauert das, er
     * startet mit Windows mit und prueft dabei auf Updates. Dann wird
     * gewartet statt aufgegeben, der Aufruf aber nur alle zwanzig Sekunden
     * wiederholt.
     *
     * Sobald das Fenster einmal da war und dann verschwindet, gilt etwas
     * anderes: Dann hat es jemand mit Absicht zugemacht, und der Bot laesst
     * die Finger davon. Diese Unterscheidung ist der ganze Punkt -- eine
     * feste Obergrenze an Versuchen kann beides nicht auseinanderhalten und
     * war deshalb einmal zu streng (Abbruch nach dem Hochfahren) und einmal
     * zu grob (Launcher alle fuenf Sekunden wieder vor der Nase).
     */
    private static final long AUFRUF_ABSTAND_MS = 20_000;

    /** Wie oft der Launcher hoechstens gestartet wird. Danach nur noch warten. */
    private static final int MAX_AUFRUFE = 3;

    private GameLauncher() {
    }

    /** Ergebnis eines Startversuchs, mit Erklaerung in Alltagssprache. */
    public record Ergebnis(boolean ok, String meldung) {}

    /**
     * Autor hat den Launcher selbst zugemacht.
     *
     * Dann bleibt er zu -- auch bei spaeteren Startversuchen, etwa nach
     * einem Absturz des Spiels. Wer sein Fenster schliesst, will es nicht
     * zehn Sekunden spaeter wieder vor der Nase haben.
     *
     * Aufgehoben wird das nur durch "!spiel": Wenn Autor den Spielstart
     * ausdruecklich anstoesst, darf der Bot es wieder versuchen.
     */
    private static volatile boolean vomBenutzerGeschlossen = false;

    /** Hebt die Sperre auf. Wird von "!spiel" gerufen. */
    public static void wiederErlauben() {
        vomBenutzerGeschlossen = false;
    }

    /** Wie viele Spielstarts gerade laufen. Siehe startLaeuft(). */
    private static final java.util.concurrent.atomic.AtomicInteger laufendeStarts =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Startet der Bot das Spiel gerade? Dann ist Hustle Castle vielleicht noch
     * nicht zu sehen -- und der SpielWaechter soll den Bot deswegen nicht
     * beenden.
     */
    public static boolean startLaeuft() {
        return laufendeStarts.get() > 0;
    }

    /**
     * Sorgt dafuer, dass Hustle Castle laeuft.
     *
     * Laeuft es schon, passiert nichts und es kostet keine Zeit.
     *
     * @param fenster das Spielfenster, so wie der Bot es sucht
     */
    public static Ergebnis sicherstellen(GameWindow fenster) {
        laufendeStarts.incrementAndGet();
        try {
            return sicherstellenIntern(fenster);
        } finally {
            laufendeStarts.decrementAndGet();
        }
    }

    private static Ergebnis sicherstellenIntern(GameWindow fenster) {
        if (fenster.locate()) {
            return new Ergebnis(true, Sprache.t(
                    "Hustle Castle is already running.",
                    "Hustle Castle laeuft bereits."));
        }

        if (vomBenutzerGeschlossen) {
            Log.info("Start", Sprache.t("The launcher was closed. The bot leaves it closed. " + "With !game it may try again.",
                    "Der Launcher wurde zugemacht. Der Bot laesst ihn zu. "
                    + "Mit !spiel darf er es wieder versuchen."));
            return new Ergebnis(false, Sprache.t(
                    "You closed the launcher, so the bot leaves it alone. "
                    + "Type `!game` when it should try again.",
                    "Du hast den Launcher zugemacht, deshalb laesst der Bot ihn in "
                    + "Ruhe. Mit `!spiel` darf er es wieder versuchen."));
        }

        Log.info("Start", Sprache.t("Hustle Castle is not running. The bot starts it " + "via the MY.GAMES Launcher.",
                "Hustle Castle laeuft nicht. Der Bot startet es "
                + "ueber den MY.GAMES Launcher."));

        GameWindow launcher = new GameWindow(LAUNCHER_TITEL, 0, 0);

        // Erst nachsehen, ob der Launcher ueberhaupt schon ein Fenster hat.
        //
        // Die Adresse mygames:// holt einen LAUFENDEN Launcher nach vorne --
        // laeuft keiner, tut sie nichts. Deshalb wird das Programm selbst
        // gestartet, wenn kein Fenster da ist. Ist eines da, genuegt die
        // Adresse, und sie hat den Vorteil, gleich die richtige Spielseite
        // zu oeffnen.
        if (launcher.locateVisible()) {
            starteUrl(SPIEL_URL);
        } else {
            Log.info("Start", Sprache.t("The launcher has no window yet. Starting it.",
                    "Der Launcher hat noch kein Fenster. Er wird gestartet."));
            starte(LAUNCHER_EXE);
            schlafe(6000);
            starteUrl(SPIEL_URL);
        }

        long bis = System.currentTimeMillis() + 60_000;
        boolean da = false;
        while (System.currentTimeMillis() < bis) {
            if (launcher.locateVisible()) { da = true; break; }
            schlafe(2000);
        }
        if (!da) {
            return new Ergebnis(false, Sprache.t(
                    "The launcher window did not show up.",
                    "Das Fenster des Launchers ist nicht aufgegangen."));
        }

        // Der Launcher legt sich nach einem Spielstart in die Leiste. Fuer
        // Windows gilt er dann immer noch als "sichtbar" -- wer das nicht
        // beachtet, klickt auf den Schreibtisch.
        launcher.bringToFront();

        // Die Seite braucht einen Moment, bis sie steht.
        schlafe(5000);

        // ---------------------------------------------------------------
        //  Geduldig auf den Knopf warten
        //
        //  Warum das noetig ist: Der MY.GAMES Launcher startet mit Windows
        //  mit (Eintrag "mglauncherlaunchpad_", MGL.exe -autostart) und
        //  prueft dabei auf Updates. Waehrend ein Update laeuft, gibt es
        //  den blauen Knopf gar nicht -- an seiner Stelle steht ein
        //  Fortschrittsbalken.
        //
        //  Frueher gab der Bot nach zwoelf Sekunden auf. Am Rechner waere
        //  das egal, aber genau dafuer ist die Kette ja nicht gedacht:
        //  Autor macht von unterwegs den Strom an und merkt erst in
        //  Discord, dass nichts passiert ist.
        //
        //  Deshalb jetzt fuenf Minuten. Und nach einer halben Minute eine
        //  Meldung in den Wichtig-Kanal, damit er weiss, WORAN es liegt --
        //  ein stiller Bot, der wartet, sieht von aussen aus wie ein
        //  kaputter Bot.
        // ---------------------------------------------------------------
        long ende = System.currentTimeMillis() + KNOPF_GEDULD_MS;
        boolean gemeldet = false;
        long begonnen = System.currentTimeMillis();
        long seiteGesuchtUm = System.currentTimeMillis();   // erst nach SEITE_ABSTAND_MS ohne Knopf
        int seiteVersuche = 0;
        int klicks = 0;

        /** Hatten wir das Launcher-Fenster schon einmal vor uns? */
        boolean schonGesehen = false;

        /** Wie oft der Launcher aufgerufen wurde. */
        int oeffnungen = 0;

        /** Wann der Launcher zuletzt aufgerufen wurde. */
        long letzterAufruf = System.currentTimeMillis();

        while (System.currentTimeMillis() < ende) {

            // Ist das Spiel inzwischen da? Dann sind wir fertig -- egal, wer
            // es gestartet hat. Ohne diese Pruefung laeuft die Schleife
            // weiter, obwohl es nichts mehr zu tun gibt.
            if (fenster.locate()) {
                return new Ergebnis(true, Sprache.t(
                        "Hustle Castle is running.",
                        "Hustle Castle laeuft."));
            }

            // Fenster verschwunden?
            //
            // HIER STAND EIN AERGERLICHER FEHLER: Frueher wurde der Launcher
            // dann einfach neu aufgerufen -- alle fuenf Sekunden, fuenf
            // Minuten lang. Wer ihn zumachen wollte, weil er am Rechner
            // arbeiten will, bekam ihn immer wieder vor die Nase gesetzt.
            //
            // Ein geschlossenes Fenster ist eine ANSAGE. Hatte der Bot es
            // schon einmal gesehen, hat es jemand mit Absicht zugemacht --
            // dann ist Schluss. Nur beim allerersten Versuch wird nachgeholt,
            // und auch das hoechstens zweimal.
            if (!launcher.locateVisible()) {

                if (schonGesehen) {
                    vomBenutzerGeschlossen = true;
                    Log.info("Start", Sprache.t("The MY.GAMES Launcher was closed. The bot " + "does NOT reopen it and aborts the game start. " + "With !game you can try again later.",
                            "Der MY.GAMES Launcher wurde geschlossen. Der Bot "
                            + "macht ihn NICHT wieder auf und bricht den Spielstart ab. "
                            + "Mit !spiel kannst du es spaeter erneut versuchen."));
                    return new Ergebnis(false, Sprache.t(
                            "The launcher was closed, so the start was cancelled. "
                            + "Type `!game` to try again.",
                            "Der Launcher wurde geschlossen, der Start wurde deshalb "
                            + "abgebrochen. Mit `!spiel` kannst du es erneut versuchen."));
                }

                // Noch nie dagewesen: Der Launcher faehrt vermutlich gerade
                // erst hoch. Nach einem frischen Neustart des Rechners dauert
                // das seine Zeit -- er startet mit Windows mit und prueft
                // dabei auf Updates.
                //
                // Deshalb wird hier NICHT nach ein paar Versuchen aufgegeben,
                // sondern die uebliche Geduld abgewartet. Der Aufruf selbst
                // wird nur alle zwanzig Sekunden wiederholt: Ihn alle fuenf
                // Sekunden abzufeuern hilft nicht und macht nur Unruhe.
                // Hoechstens dreimal starten. Danach wird nur noch gewartet.
                //
                // Warten und Starten sind zweierlei: Geduld darf lang sein,
                // aber ein Programm immer wieder neu aufzurufen hilft nach dem
                // dritten Mal nicht mehr -- es macht nur Unruhe auf dem
                // Bildschirm.
                if (oeffnungen >= MAX_AUFRUFE) {
                    schlafe(5000);
                    continue;
                }

                if (System.currentTimeMillis() - letzterAufruf > AUFRUF_ABSTAND_MS) {
                    letzterAufruf = System.currentTimeMillis();
                    oeffnungen++;
                    Log.debug("Start", "Launcher-Fenster noch nicht da, Aufruf "
                            + oeffnungen + ".");

                    // HIER FEHLTE ETWAS WICHTIGES: Die Adresse mygames://
                    // holt einen LAUFENDEN Launcher nach vorne. Laeuft aber
                    // gar keiner -- oder haengt nur noch ein Rest von ihm im
                    // Speicher -- passiert damit gar nichts.
                    //
                    // Genau das war am 31.08. zu sehen: elf Aufrufe
                    // hintereinander, MGL.exe im Speicher, aber nie ein
                    // Fenster. Der Bot rief immer wieder eine Adresse auf,
                    // die niemand entgegennahm.
                    //
                    // Deshalb wird ab dem zweiten Versuch das Programm
                    // SELBST gestartet. Das legt einen frischen Launcher an,
                    // auch wenn der alte nicht mehr ansprechbar ist.
                    if (oeffnungen == 1) {
                        starteUrl(SPIEL_URL);
                    } else {
                        Log.debug("Start", "Die Adresse allein hilft nicht. Der Launcher "
                                + "wird jetzt direkt gestartet.");
                        starte(LAUNCHER_EXE);
                        schlafe(4000);
                        starteUrl(SPIEL_URL);
                    }
                }
                schlafe(5000);
                launcher.bringToFront();
                continue;
            }
            schonGesehen = true;

            Rectangle bereich = launcher.windowRect();
            if (bereich != null && bereich.width >= 400) {

                java.awt.Point knopf = findeSpielenKnopf(bereich);
                if (knopf != null) {
                    klicks++;
                    long gewartet = (System.currentTimeMillis() - begonnen) / 1000;
                    Log.action("Start", Sprache.t("\"Play\" button found at " + knopf.x + "/" + knopf.y + " (after " + gewartet + " s), click " + klicks + ".",
                            "Knopf \"Spielen\" gefunden bei " + knopf.x + "/"
                            + knopf.y + " (nach " + gewartet + " s), Klick " + klicks + "."));

                    klicke(knopf.x, knopf.y);

                    // HIER STECKTE DER FEHLER, DER DEN GANZEN SELBSTSTART
                    // WERTLOS MACHTE:
                    //
                    // Windows laesst ein Hintergrundprogramm kein fremdes
                    // Fenster nach vorne holen -- SetForegroundWindow schlaegt
                    // still fehl. Das Launcher-Fenster ist dann zwar sichtbar,
                    // aber nicht aktiv. Und der erste Klick auf ein inaktives
                    // Fenster wird von Windows nur zum AKTIVIEREN verbraucht;
                    // beim Knopf kommt er gar nicht an.
                    //
                    // Im Protokoll sah das aus wie ein Erfolg: Knopf gefunden,
                    // geklickt -- und dann kam nie ein Spiel.
                    //
                    // Deshalb wird nach dem Klick nachgesehen, was daraus
                    // geworden ist, statt blind zu warten.
                    if (warteAufFenster(fenster, NACH_KLICK_MS)) {
                        return new Ergebnis(true, Sprache.t(
                                "Hustle Castle was started through the launcher and is "
                                + "logged in.",
                                "Hustle Castle wurde ueber den Launcher gestartet und "
                                + "ist angemeldet."));
                    }

                    // Kein Spiel. Ist der Knopf noch da?
                    //
                    //   Knopf weg  -> der Klick hat gewirkt, das Spiel laedt.
                    //                 Dann in Ruhe warten, NICHT nachklicken.
                    //   Knopf da   -> der Klick ist verpufft. Die Schleife
                    //                 versucht es gleich noch einmal, und
                    //                 diesmal ist das Fenster aktiv.
                    Rectangle jetzt = launcher.windowRect();
                    boolean knopfNochDa = jetzt != null && jetzt.width >= 400
                            && findeSpielenKnopf(jetzt) != null;

                    if (!knopfNochDa) {
                        // Knopf weg heisst: Der Klick hat gewirkt. Zwei Faelle,
                        // und von aussen sehen sie gleich aus:
                        //
                        //   a) Das Spiel laedt. Gleich ist das Fenster da.
                        //   b) Es stand "Update" auf dem Knopf, nicht
                        //      "Spielen". Dann laeuft jetzt ein Update, an
                        //      seiner Stelle steht ein Fortschrittsbalken --
                        //      und hinterher wird der Knopf wieder blau,
                        //      diesmal als "Spielen".
                        //
                        // Beide Faelle brauchen dasselbe: warten und danach
                        // NOCH EINMAL hinsehen. Frueher gab der Bot hier auf,
                        // und ein Update bedeutete: kein Spiel bis Autor
                        // heimkommt.
                        Log.info("Start", Sprache.t("The button is gone -- either the game is loading " + "or an update ran. Waiting.",
                                "Der Knopf ist verschwunden -- entweder laedt "
                                + "das Spiel, oder es lief ein Update. Es wird gewartet."));
                        if (warteAufFenster(fenster, WARTEN_SPIEL_MS)) {
                            return new Ergebnis(true, Sprache.t(
                                    "Hustle Castle was started through the launcher and "
                                    + "is logged in.",
                                    "Hustle Castle wurde ueber den Launcher gestartet "
                                    + "und ist angemeldet."));
                        }
                        Log.info("Start", Sprache.t("Still no game. It was probably the " + "\"Update\" button. The bot keeps watching for the " + "blue button and presses it again as soon as it is there.",
                                "Immer noch kein Spiel. Vermutlich war es der "
                                + "Knopf \"Update\". Der Bot sieht weiter nach dem "
                                + "blauen Knopf und drueckt ihn wieder, sobald er da ist."));
                    } else {
                        Log.warn("Start", Sprache.t("The button is still there -- the click " + "fizzled because the launcher window was not active. " + "Clicking once more.",
                                "Der Knopf ist noch da -- der Klick ist "
                                + "verpufft, weil das Launcher-Fenster nicht aktiv war. "
                                + "Es wird noch einmal geklickt."));
                    }
                    continue;
                }
            }

            // Kein Knopf -- vielleicht steht der Launcher auf "Alle Spiele" oder
            // "Markt" (Autor, 29.09.). Dann erst auf die Hustle-Castle-Seite: im
            // Wechsel die Adresse, die dorthin fuehrt, und ein Klick auf den
            // Eintrag "Hustle Castle" links (dritter Eintrag unter dem Logo).
            // Laeuft gerade ein Update, schadet beides nicht -- man bleibt auf der Seite.
            if (bereich != null && bereich.width >= 400
                    && System.currentTimeMillis() - seiteGesuchtUm > SEITE_ABSTAND_MS) {
                seiteGesuchtUm = System.currentTimeMillis();
                if (seiteVersuche++ % 2 == 0) {
                    Log.info("Start", Sprache.t("No \"Play\" button -- the launcher is probably on another " + "page. Opening the Hustle Castle page.",
                            "Kein Knopf \"Spielen\" -- der Launcher steht wohl auf einer anderen "
                            + "Seite. Die Hustle-Castle-Seite wird aufgerufen."));
                    starteUrl(SPIEL_URL);
                } else {
                    int x = bereich.x + Math.round(bereich.width * 0.08f);
                    int y = bereich.y + Math.round(bereich.height * 0.37f);
                    Log.action("Start", Sprache.t("Clicking \"Hustle Castle\" on the left (" + x + "/" + y + ").",
                            "Links auf \"Hustle Castle\" klicken (" + x + "/" + y + ")."));
                    klicke(x, y);
                }
                schlafe(3000);
                continue;
            }

            // Kein Knopf. Nach einer halben Minute einmal Bescheid geben.
            if (!gemeldet && System.currentTimeMillis() - begonnen > MELDEN_NACH_MS) {
                gemeldet = true;
                Log.alarm("Start", Sprache.t("The \"Play\" button is not visible in the launcher. " + "It is probably loading an update -- then a " + "progress bar sits there instead of the button. The bot waits up to " + (KNOPF_GEDULD_MS / 60000) + " more minutes and then starts the game.",
                        "Der Knopf \"Spielen\" ist im Launcher nicht zu sehen. "
                        + "Vermutlich laedt er gerade ein Update -- dann steht dort ein "
                        + "Fortschrittsbalken statt des Knopfes. Der Bot wartet noch bis zu "
                        + (KNOPF_GEDULD_MS / 60000) + " Minuten und startet das Spiel dann."));
            }

            schlafe(5000);
            launcher.bringToFront();
        }

        long gewartet = (System.currentTimeMillis() - begonnen) / 60000;
        return new Ergebnis(false, Sprache.t(
                "The button \"Spielen\" did not appear within " + gewartet
                + " minutes. The launcher is probably still updating.",
                "Der Knopf \"Spielen\" ist in " + gewartet + " Minuten nicht "
                + "aufgetaucht. Der Launcher laedt vermutlich immer noch."));
    }

    /**
     * Sucht den blauen Knopf im rechten oberen Teil des Launcher-Fensters.
     *
     * Liefert den Punkt, auf den geklickt werden soll, in
     * Bildschirmkoordinaten -- oder null.
     *
     * Geklickt wird nicht in die Mitte der blauen Flaeche: Rechts sitzt am
     * selben Knopf ein kleiner Pfeil fuer weitere Auswahlmoeglichkeiten, und
     * der gehoert zum selben Blau. Der Klick geht deshalb auf das erste
     * Drittel -- da steht der Schriftzug.
     */
    private static java.awt.Point findeSpielenKnopf(Rectangle fenster) {
        BufferedImage bild;
        try {
            bild = new Robot().createScreenCapture(fenster);
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("Screenshot of the launcher failed: " + e.getMessage(),
                    "Bildschirmfoto des Launchers fehlgeschlagen: "
                    + e.getMessage()));
            return null;
        }

        // Nur rechte Haelfte, nur oberes Drittel. Links sitzt das
        // MY.GAMES-Logo im selben Blau, unten die bunte Spielwerbung.
        int vonX = bild.getWidth() / 2;
        int bisY = Math.max(60, bild.getHeight() / 3);

        int minX = Integer.MAX_VALUE, maxX = -1;
        int minY = Integer.MAX_VALUE, maxY = -1;
        int punkte = 0;

        for (int y = 0; y < bisY; y++) {
            for (int x = vonX; x < bild.getWidth(); x++) {
                if (!istKnopfblau(bild.getRGB(x, y))) continue;
                punkte++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        if (punkte < MIN_PUNKTE) {
            Log.debug("Start", "Nur " + punkte + " blaue Punkte gefunden, das ist "
                    + "zu wenig fuer den Knopf.");
            return null;
        }

        int klickX = minX + (int) ((maxX - minX) * 0.35);
        int klickY = (minY + maxY) / 2;

        Log.debug("Start", "Blauer Knopf: x " + minX + ".." + maxX + ", y " + minY
                + ".." + maxY + " (" + punkte + " Punkte).");

        return new java.awt.Point(fenster.x + klickX, fenster.y + klickY);
    }

    private static boolean istKnopfblau(int rgb) {
        Color c = new Color(rgb);
        return Math.abs(c.getRed()   - KNOPF_R) <= FARB_TOLERANZ
            && Math.abs(c.getGreen() - KNOPF_G) <= FARB_TOLERANZ
            && Math.abs(c.getBlue()  - KNOPF_B) <= FARB_TOLERANZ;
    }

    /**
     * Wartet, bis das Spielfenster da ist.
     *
     * Das Spiel braucht beim ersten Start nach dem Hochfahren gut eine halbe
     * Minute, dazu kommt manchmal ein Update -- deshalb die lange Frist.
     */
    private static boolean warteAufFenster(GameWindow fenster, long hoechstensMs) {
        long bis = System.currentTimeMillis() + hoechstensMs;
        while (System.currentTimeMillis() < bis) {
            if (fenster.locate()) {
                // Noch einen Moment: Das Fenster ist da, aber das Spiel laedt
                // noch. Wer sofort klickt, klickt ins Leere.
                schlafe(8000);
                return true;
            }
            schlafe(2000);
        }
        return false;
    }

    private static boolean starte(String programm) {
        try {
            new ProcessBuilder(programm).start();
            return true;
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("Could not start " + programm + ": " + e.getMessage(),
                    "Konnte " + programm + " nicht starten: " + e.getMessage()));
            return false;
        }
    }

    /**
     * Oeffnet eine Adresse so, wie Windows es tun wuerde -- damit greift der
     * eingetragene Umgang mit mygames-Adressen.
     */
    private static boolean starteUrl(String url) {
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
            return true;
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("Could not open " + url + ": " + e.getMessage(),
                    "Konnte " + url + " nicht oeffnen: " + e.getMessage()));
            return false;
        }
    }

    private static boolean klicke(int x, int y) {
        try {
            Robot robot = new Robot();
            Mauswache.botBewegt(x, y);
            robot.mouseMove(x, y);
            Mauswache.botBewegt(x, y);
            schlafe(300);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            schlafe(80);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            return true;
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("Click failed: " + e.getMessage(), "Klick fehlgeschlagen: " + e.getMessage()));
            return false;
        }
    }

    private static void schlafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
