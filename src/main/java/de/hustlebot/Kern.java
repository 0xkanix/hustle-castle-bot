package de.hustlebot;

import de.hustlebot.bot.BotContext;
import de.hustlebot.bot.BotEngine;
import de.hustlebot.bot.SpielWaechter;
import de.hustlebot.core.*;
import de.hustlebot.discord.DiscordBridge;
import de.hustlebot.discord.DiscordClient;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Hustle Castle Bot -- Freunde-Fassung.
 *
 * Kann Verlies, Kathedrale und Clan-Krieg. Kein Bedienfeld im Browser, keine
 * Steckdose, keine Hardware-Ueberwachung, kein Uebersetzer -- das steckt alles
 * nur in Autors eigener Fassung.
 *
 * Der Ablauf beim Start:
 *   1. Konfiguration lesen
 *   2. OpenCV laden
 *   3. Fenster, Vorlagen, Motor
 *   4. Discord (freiwillig, eigener Bot-Token aus config.properties)
 */
public final class Kern {
    // Windows-Skalierung (16.10.): bei 125/150 % rechnete Java in umgerechneten Punkten, die Fensterposition
    // kommt von Windows aber in echten Pixeln -- Fotos und Klicks lagen daneben, der Bot erkannte nichts (Freundin
    // mit grossem Bildschirm: "es steht, dass er laeuft, aber es passiert nichts"). Darum immer echte Pixel.
    // Muss vor dem ersten Grafik-Zugriff laufen -- deshalb als erstes in der Klasse.
    static {
        System.setProperty("sun.java2d.uiScale", "1");
    }


    public static void main(String[] args) {
        // ZUERST herausfinden, wo wir liegen. Alles danach -- Protokoll,
        // Einstellungen, Vorlagen -- haengt daran.
        Path heim = Ordner.basis();

        Log.init(heim.resolve("logs"));
        // Protokollfenster NICHT sofort minimieren (Code-Pruefung 16.10.): ein OpenCV-Fehler haengen sonst unsichtbar im minimierten Fenster und warten auf einen Tastendruck,
        // den der Freund nie sieht. Erst ganz unten, wenn der Start geklappt hat, geht es in den Hintergrund.
        // Alte Beweisfotos in logs klein halten (Hauptbot: 1,4 GB nach Wochen; mit Spiel-Neustarts waechst es schneller).
        de.hustlebot.core.Fotobremse.imHintergrund();
        Sprache.set(Main.spracheAusDatei());

        Log.info("Start", Sprache.t("Hustle Castle Bot " + Main.FASSUNG + " (build " + Main.bau() + ") is starting.",
                                    "Hustle Castle Bot " + Main.FASSUNG + " (Bau " + Main.bau() + ") startet."));
        Log.info("Start", Sprache.t("Folder: " + heim, "Ordner: " + heim));
        // Autor 24.10.: im Bot-Ordner nur HustleBot.exe, Capture-Images.exe und 0-START-HERE.txt zeigen, den Rest verstecken.
        de.hustlebot.core.Ordnerordnung.aufraeumen(heim);

        // Je Modus: "Deploy the best" oder "Previous Squad" beim Aufstellen.
        // Umstellbar in Discord mit !settings. Liegt neben dem Bot.
        de.hustlebot.bot.Aufstellung.konfigurieren(Ordner.bei("aufstellung.properties"));

        Properties config = ladeConfig();

        String fensterTitel = config.getProperty("window.title", "Hustle Castle");
        int fensterBreite   = zahl(config, "window.width", 1175);
        int fensterHoehe    = zahl(config, "window.height", 809);
        int leerlaufMinuten = zahl(config, "bot.idleTimeoutMinutes", 5);
        long taktMillis     = Math.min(150, zahl(config, "bot.tickDelayMillis", 150));
        boolean debug       = Boolean.parseBoolean(config.getProperty("log.debug", "true"));
        Path vorlagenOrdner = Ordner.bei(config.getProperty("templates.dir", "templates"));

        Log.setDebugEnabled(debug);

        Path configDatei = Ordner.bei("config.properties");

        try {
            Log.info("Start", Sprache.t("Loading OpenCV.", "OpenCV wird geladen."));
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            Log.error("Start", Sprache.t("OpenCV could not be loaded. The bot cannot work "
                    + "without it.",
                    "OpenCV liess sich nicht laden. Der Bot kann so nicht "
                    + "arbeiten."), t);
            warteAufTaste();
            return;
        }

        // Handy-Umbau 19.11.: liegt handy.txt im Bot-Ordner, spielt der Bot ueber ADB am Handy (ohne Datei: wie bisher).
        de.hustlebot.core.Handyprofil handy = de.hustlebot.core.Handyprofil.laden(Ordner.basis());
        de.hustlebot.core.AdbSteuerung handySteuerung = null;
        if (handy != null) {
            handySteuerung = new de.hustlebot.core.AdbSteuerung(handy.adb(), handy.geraet());
            java.awt.image.BufferedImage erstes = handySteuerung.ganzesFoto();
            if (erstes == null) {
                Log.error("Start", "Handy (handy.txt): kein Bildschirmfoto ueber ADB -- ist das Handy verbunden "
                        + "(adb connect, siehe doku\\handy\\ANLEITUNG-ADB.md)?");
                warteAufTaste();
                return;
            }
            GameWindow.probeFensterSetzen(new java.awt.Rectangle(0, 0, erstes.getWidth(), erstes.getHeight()));
            vorlagenOrdner = Ordner.bei(handy.vorlagen());
            Log.info("Start", "Handy-Betrieb: " + (handy.geraet() == null ? "einziges Geraet" : handy.geraet()) + ", Bildschirm "
                    + erstes.getWidth() + "x" + erstes.getHeight() + ", Vorlagen " + vorlagenOrdner + ".");
        } else if (GraphicsEnvironment.isHeadless()) {
            Log.error("Start", Sprache.t("No screen available.", "Kein Bildschirm vorhanden."));
            return;
        }

        try {
            GameWindow fenster = new GameWindow(fensterTitel, fensterBreite, fensterHoehe);
            // Hintergrund-Modus (EXPERIMENTELL, Autor 20.11.): mit hintergrund.txt "an" spielt der Bot unsichtbar --
            // Fotos per PrintWindow, Tipps per Touch unter dem Mauszeiger. Ohne Datei: genau wie bisher (echte Maus,
            // Bildschirmfoto). Umschalten geht im Lauf (die Datei wird alle 2 s gelesen).
            de.hustlebot.core.HintergrundSteuerung hinten = handySteuerung != null ? null
                    : new de.hustlebot.core.HintergrundSteuerung(new de.hustlebot.core.Geist.Zugang(fenster::handle));
            ScreenCapture kamera;
            if (handySteuerung != null) {
                kamera = new ScreenCapture(handySteuerung);
            } else {
                Robot fotoRoboter = new Robot();
                fotoRoboter.setAutoWaitForIdle(false);
                kamera = new ScreenCapture(new de.hustlebot.core.WahlSteuerung(
                        new de.hustlebot.core.RobotSteuerung(fotoRoboter), hinten, de.hustlebot.core.Hintergrund::an));
            }
            // Autor 17.10.: sicher erkannte Knoepfe vom Bildschirm des Freundes aufnehmen (Matcher, eigene Vorlagen).
            Matcher.EIGEN_ORDNER = vorlagenOrdner.resolve("gelernt").resolve("eigen");
            // Autor 24.10.: eigene Aufnahmen + Arena-Listen sichtbar in "My pictures" (vorher templates\arena\gegner).
            de.hustlebot.core.Eigenbilder.einrichten(Ordner.basis());
            Matcher.EIGENE_BILDER = de.hustlebot.core.Eigenbilder.wurzel(Ordner.basis());
            // Autor 21.10.: Arena-Listen in EINEN Ordner (wie Capture-Programm), jeder Spieler nur einmal.
            de.hustlebot.core.ArenaVorlagenDoppelt.aufraeumen(Ordner.basis(), vorlagenOrdner);
            Matcher matcher = new Matcher(vorlagenOrdner);

            Humanizer mensch = new Humanizer();
            mensch.setEnabled(false);
            mensch.setIntensity(0.0);

            Input eingabe = handySteuerung != null ? new Input(handySteuerung, mensch)
                    : new Input(new de.hustlebot.core.WahlSteuerung(new de.hustlebot.core.RobotSteuerung(new Robot()),
                            hinten, de.hustlebot.core.Hintergrund::an), mensch);

            de.hustlebot.core.VorlagenNachschub.liefern(vorlagenOrdner);
            de.hustlebot.core.ReinigerNachschub.liefern();   // PC-Cleaner in den Bot-Ordner (07.10.)
            String inDerJar = de.hustlebot.core.UpdateNachschub.fassungInDerJar();
            if (inDerJar != null && !inDerJar.equals(Main.FASSUNG)) {
                Log.warn("Start", Sprache.t("fassung.txt in the jar says " + inDerJar + ", the program " + Main.FASSUNG
                        + " -- something went wrong when building (paket-bauen.ps1).",
                        "fassung.txt in der Jar sagt " + inDerJar + ", das Programm " + Main.FASSUNG
                        + " -- beim Bau ist etwas schiefgegangen (paket-bauen.ps1)."));
            }
            int geladen = matcher.loadAll();
            Matcher.STELLEN_DATEI = Ordner.bei("vorlagen-stellen.txt");
            matcher.stellenLaden(Matcher.STELLEN_DATEI);
            Log.info("Start", Sprache.t(geladen + " templates loaded.", geladen + " Vorlagen geladen."));
            if (geladen == 0) {
                Log.warn("Start", Sprache.t("There are no images in the templates folder. Without them "
                        + "the bot sees nothing -- please unpack the ZIP again.",
                        "Im Ordner templates liegen keine Bilder. Ohne sie sieht "
                        + "der Bot nichts -- pack die ZIP bitte noch einmal aus."));
            }

            BotContext ctx = new BotContext(fenster, kamera, matcher, eingabe,
                    new Laufstatistik());
            BotEngine motor = new BotEngine(ctx);
            motor.setIdleTimeoutMinutes(leerlaufMinuten);
            motor.setTickDelayMillis(taktMillis);
            motor.setRequireAllTemplates(
                    Boolean.parseBoolean(config.getProperty("bot.requireAllTemplates", "true")));
            // Start-Probleme 16.10.: solange ein Modus laeuft oder pausiert, schlaeft der PC nicht ein.
            de.hustlebot.core.Wachhalten.starten(() -> motor.state() == BotEngine.State.LAEUFT
                    || motor.state() == BotEngine.State.PAUSIERT);

            // Keine Tastenkuerzel mehr (13.09.): ^ und Q wirkten im ganzen
            // Rechner -- ein "q" beim Tippen hielt den Bot an, ein "^" startete
            // ihn, auch ohne Spiel. Angehalten wird er mit !stop in Discord.
            DiscordClient discord = new DiscordClient();
            discord.configure(
                    Boolean.parseBoolean(config.getProperty("discord.enabled", "true")),
                    "",
                    config.getProperty("discord.botToken", ""),
                    config.getProperty("discord.channelId", ""));

            DiscordBridge bruecke = new DiscordBridge(motor, discord,
                    config.getProperty("discord.botToken", ""),
                    config.getProperty("discord.channelId", ""),
                    config.getProperty("discord.statsChannelId", ""),
                    Main.FASSUNG);
            // Das Steuerprogramm (3.2): den Bot auch ohne Discord bedienen. Haengt sich VOR
            // bruecke.start() an den DiscordClient, damit auch "Bot bereit" im Fenster ankommt.
            de.hustlebot.steuer.Steuerprogramm steuer =
                    de.hustlebot.steuer.Steuerprogramm.vorbereiten(motor, bruecke, discord, config, configDatei);
            bruecke.setSteuerprogramm(steuer);
            bruecke.start();

            // Hinweise aus den Spielmodi nach Discord (z.B. "Vorlage neu
            // aufnehmen", wenn die Opfer-Einheit nicht mehr gefunden wird).
            ctx.setDiscordHinweis((titel, text) ->
                    discord.sendCard(titel, text, DiscordClient.FARBE_WARNUNG));

            // Not-Aus: eine frei waehlbare Taste haelt den Bot ueberall sofort
            // an. Voreinstellung F9. Umstellbar mit !settings notaus <Taste>.
            bruecke.notAusStarten(config.getProperty("notaus.taste", "F9"), configDatei);
            bruecke.notPauseStarten(config.getProperty("notpause.taste", "F10"), config.getProperty("weiter.taste", "F11"));
            if (!de.hustlebot.core.ServerSparsam.an()) bruecke.mauswacheStarten();
            if (hinten != null) {
                // Hintergrund-Wache: Autor oeffnet das Spiel -> Bot pausiert; Schalter aus -> Spiel wieder sichtbar.
                de.hustlebot.core.Hintergrund.starten(() -> {
                    if (motor.pausierenFuerAutor()) {
                        discord.sendCard(Sprache.t("Paused (game opened)", "Pausiert (Spiel geoeffnet)"),
                                Sprache.t("You opened the game -- background mode pauses. Type !resume when the bot should carry on.",
                                        "Du hast das Spiel geoeffnet -- der Hintergrund-Modus pausiert. Tipp !resume, wenn der Bot weitermachen soll."),
                                DiscordClient.FARBE_INFO);
                    }
                });
                if (de.hustlebot.core.Hintergrund.an()) {
                    Log.warn("Start", Sprache.t("Background mode is ON (hintergrund.txt) -- EXPERIMENTAL. Do not use it next to games with many clicks.",
                            "Hintergrund-Modus ist AN (hintergrund.txt) -- EXPERIMENTELL. Nicht neben Spielen mit vielen Klicks benutzen."));
                }
            }
            de.hustlebot.bot.ArenaBeobachter.starten(motor);   // Autor 21.10.: eigene Arena-Kaempfe mitzaehlen
            // Autor 28.10.: nur mit arena-schicht.txt "an"; Kommentare/Zustimmen/Ablehnen gehen auch nach Discord.
            de.hustlebot.schicht.ArenaSchicht.starten(fensterTitel, discord::send);

            if (discord.isEnabled()) {
                Log.info("Start", Sprache.t("Discord is running. Type !help in your channel.",
                                            "Discord laeuft. Tipp !help in deinen Kanal."));
            } else {
                Log.warn("Start", Sprache.t("Discord is off -- bot token or channel number are missing "
                        + "in config.properties. See GUIDE-1-SETUP-EN.txt.",
                        "Discord ist aus -- Bot-Token oder Kanal-Nummer fehlen "
                        + "in config.properties. Siehe GUIDE-1-SETUP-EN.txt."));
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.info("Ende", Sprache.t("The bot is shutting down.", "Der Bot wird beendet."));
                // Hintergrund-Modus: nie einen Finger liegen und nie das Spiel unsichtbar zuruecklassen.
                if (hinten != null) {
                    hinten.aufraeumen();
                    de.hustlebot.core.Hintergrund.beimEnde();
                }
                bruecke.stop();
            }));

            Log.info("Start", Sprache.t("Ready. The bot is controlled via Discord -- type !help.",
                                        "Bereit. Gesteuert wird der Bot ueber Discord -- tipp !help."));

            // Jetzt hat der Start geklappt -- das schwarze Protokollfenster darf in den Hintergrund, damit es das
            // Spiel nicht verdeckt (Autor 15.10.). Vorher sichtbar, damit Startfehler lesbar sind (Code-Pruefung 16.10.).
            de.hustlebot.core.KonsoleHinten.minimieren();

            // SICHTBAR STATT HEIMLICH, UND NUR MIT DEM SPIEL (14.09.).
            //
            // Wie in Autors eigener Fassung: ein kleines Fenster "Hustle Castle
            // Bot" (Stoppen, Beenden), damit man den Bot sieht und schliessen
            // kann, statt ihn nur im Task-Manager als javaw zu finden. Und
            // ein Waechter, der den Bot beendet, wenn Hustle Castle fuenf
            // Minuten zu ist -- er soll nur laufen, solange gespielt wird.
            SpielWaechter waechter = new SpielWaechter(fenster, motor, text ->
                    discord.sendCard(Sprache.t("The bot is closing", "Der Bot beendet sich"), text, DiscordClient.FARBE_INFO));
            Thread waechterFaden = new Thread(waechter, "spiel-waechter");
            waechterFaden.setDaemon(true);
            waechterFaden.start();
            if (!de.hustlebot.core.ServerSparsam.an()) de.hustlebot.tools.BotFenster.zeigen(motor, waechter);
            steuer.zeigen(waechter);

            // Der Motor laeuft in eigenen Faeden. Hier nur warten.
            Thread.currentThread().join();

        } catch (Exception e) {
            Log.error("Start", Sprache.t("The bot could not start.", "Der Bot konnte nicht starten."), e);
            warteAufTaste();
        }
    }

    // ------------------------------------------------------------------

    private static Properties ladeConfig() {
        Properties p = new Properties();
        Path datei = Ordner.bei("config.properties");
        try {
            if (Files.exists(datei)) {
                try (InputStream in = Files.newInputStream(datei)) {
                    p.load(in);
                }
                Log.info("Start", Sprache.t("config.properties read.", "config.properties gelesen."));
            } else {
                Log.warn("Start", Sprache.t("config.properties not found -- the defaults apply. "
                        + "Discord is off then.",
                        "config.properties nicht gefunden -- es gelten die "
                        + "Standardwerte. Discord ist dann aus."));
            }
        } catch (Exception e) {
            Log.error("Start", Sprache.t("config.properties could not be read.",
                                         "config.properties liess sich nicht lesen."), e);
        }
        return p;
    }

    private static int zahl(Properties p, String schluessel, int standard) {
        try {
            return Integer.parseInt(p.getProperty(schluessel, String.valueOf(standard)).trim());
        } catch (Exception e) {
            return standard;
        }
    }

    /** Damit das schwarze Fenster nicht zuklappt, bevor man den Fehler lesen kann. */
    private static void warteAufTaste() {
        try {
            System.out.println();
            System.out.println(Sprache.t("Press Enter to close this window.",
                                         "Zum Schliessen dieses Fensters Enter druecken."));
            System.in.read();
        } catch (Exception ignored) {
        }
    }
}
