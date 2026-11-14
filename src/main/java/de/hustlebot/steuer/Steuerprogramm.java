package de.hustlebot.steuer;

import de.hustlebot.bot.BotEngine;
import de.hustlebot.bot.SpielWaechter;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.discord.DiscordBridge;
import de.hustlebot.discord.DiscordClient;
import de.hustlebot.tools.LogFenster;
import de.hustlebot.tools.NotizFenster;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Das Steuerprogramm: Bot ohne Discord bedienen (Auftrag 1, 11.10.; Bauplan in
 * doku/STEUERPROGRAMM.md).
 *
 * Die Mitte von allem. Knoepfe, Tastenkuerzel und Sprache rufen hier {@link #senden}
 * -- und das schickt den Text ueber DiscordBridge.befehlVonBedienfeld, denselben
 * Weg wie ein Discord-Befehl und wie /api/befehl im Bedienfeld. Antworten kommen
 * ueber den Mithoerer des DiscordClients zurueck (Meldungen) und landen im Fenster,
 * in den Anzeige-Fenstern und -- wenn eingeschaltet -- als Windows-Meldung.
 *
 * Mit Discord-Token aendert sich nichts: Discord bekommt alles wie bisher, das
 * Steuerprogramm hoert nur mit.
 */
public final class Steuerprogramm {

    private final BotEngine engine;
    private final DiscordBridge bridge;
    private final Path configDatei;
    private final boolean discordAktiv;

    final Meldungen meldungen = new Meldungen();
    /** Autor 24.10. (F29): die Tageswerte, dauerhaft (statistik-tage.txt) -- nicht nur "in dieser Sitzung". */
    final Tagesstatistik tage;
    final Steuereinstellungen einst;
    final Tastenkuerzel kuerzel;
    final Profile profile;
    private final Windowsmeldung windows;

    /** Befehle laufen nacheinander in einem eigenen Faden -- nie im Fenster-Faden (ein !stand liest den Bildschirm). */
    private final ExecutorService befehle = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "steuerprogramm-befehle");
        t.setDaemon(true);
        return t;
    });

    private SpielWaechter waechter;
    private Steuerfenster steuerfenster;
    private Antwortfenster antwortfenster;
    /** Wann zuletzt ein Knopf des Programms etwas geschickt hat, worauf eine Antwort im Antworten-Fenster gehoert. */
    private volatile long antwortErwartetBis = 0;
    /** Sammelt im Befehls-Faden die Antworten auf den gerade laufenden Befehl (fuer die Rueckmeldung je Knopf). */
    private final ThreadLocal<List<Meldung>> sammler = new ThreadLocal<>();
    private Minileiste minileiste;
    private JFrame statusFenster, statistikFenster, vorratFenster;
    private Protokollleiste protokollleiste;
    private Tastenwache tastenwache;

    private Steuerprogramm(BotEngine engine, DiscordBridge bridge, DiscordClient client, Properties config,
                           Path configDatei, Path ordner) {
        this.engine = engine;
        this.bridge = bridge;
        this.configDatei = configDatei;
        this.discordAktiv = client != null && client.isEnabled();
        this.einst = new Steuereinstellungen(ordner.resolve("steuerprogramm.properties"));
        this.tage = new Tagesstatistik(ordner.resolve("statistik-tage.txt"));
        this.kuerzel = new Tastenkuerzel(ordner.resolve("tastenkuerzel.properties"));
        this.profile = new Profile(ordner.resolve("profile.txt"));
        this.windows = new Windowsmeldung(this::steuerfensterZeigen, this::beendenGewuenscht);
        // Hell oder dunkel (Auftrag 4) -- vor dem Bauen der Fenster, die Farben sind dann fest.
        Holzstil.thema(dunkel());

        // Ab jetzt kommt jede Meldung des Bots hier an -- VOR bridge.start(), damit
        // auch "Bot bereit" nicht verloren geht.
        if (client != null) client.setMithoerer(meldungen);
        meldungen.zuhoeren(this::windowsMeldung);
        // Autor 24.10. (F29): jede Nahrungs-Ablesung (Burg-Stand-Karte) kommt in die Tageswerte
        meldungen.zuhoeren(m -> {
            try {
                if (m.titelEnthaelt("Burg-Stand", "Castle status")) {
                    Long n = Statistikdaten.nahrung(m.textLesbar());
                    if (n != null) tage.nahrung(de.hustlebot.core.Zeitzone.heute(), n);
                }
            } catch (RuntimeException e) {
                Log.debug("Steuerprogramm", "Nahrung nicht gemerkt: " + e.getMessage());
            }
        });
        // Autor 14.10.: kein Reiter Meldungen mehr -- Antworten auf Knoepfe gehen im Antworten-Fenster auf.
        // Autor 15.10.: Rueckmeldung je Knopf -- Meldungen, die der Bot WAEHREND eines Befehls im selben Faden ausgibt,
        // gehoeren zu genau diesem Befehl (Meldungen ruft im Faden des Senders auf).
        meldungen.zuhoeren(m -> {
            List<Meldung> l = sammler.get();
            if (l != null) l.add(m);
        });
        meldungen.zuhoeren(m -> {
            if (System.currentTimeMillis() > antwortErwartetBis) return;
            SwingUtilities.invokeLater(() -> {
                if (antwortfenster != null) antwortfenster.zeigen(m);
            });
        });
    }

    /**
     * Fuer die Vorschaubilder und zum Ansehen ohne Bot (SteuerVorschauProbe): kein Motor, keine Bruecke,
     * kein Discord. Befehle landen nur als Meldung "Vorschau", der Zustand ist gestellt.
     */
    static Steuerprogramm vorschau(Path ordner) {
        return new Steuerprogramm(null, null, null, new Properties(), ordner.resolve("config.properties"), ordner);
    }

    /** Nur fuer die Vorschau: so tun, als liefe gerade etwas. */
    void vorschauZustand(BotEngine.State zustand, String statusText) {
        this.vorschauZustand = zustand;
        this.vorschauText = statusText;
    }

    private BotEngine.State vorschauZustand = BotEngine.State.BEREIT;
    private String vorschauText = "";

    /** Der Zustand des Bots -- aus dem Motor, in der Vorschau gestellt. */
    BotEngine.State zustand() {
        return engine == null ? vorschauZustand : engine.state();
    }

    /** Was der Bot gerade tut ("Kathedrale 12/100") -- aus dem Motor, in der Vorschau gestellt. */
    String statusText() {
        return engine == null ? vorschauText : engine.statusText();
    }

    /** Die Zaehler des Motors fuer die Statistik-Diagramme; null ohne Motor. */
    de.hustlebot.core.Laufstatistik laufstatistik() {
        try {
            return engine == null ? null : engine.context().laufstatistik();
        } catch (Exception e) {
            return null;
        }
    }

    /** Hell oder dunkel laut Einstellung (Vorgabe hell). */
    boolean dunkel() {
        return einst.text(Steuereinstellungen.AUSSEHEN, "hell").trim().equalsIgnoreCase("dunkel");
    }

    /**
     * Von Main, vor bridge.start(): haengt sich an den DiscordClient und laedt die
     * Einstellungen. Fenster kommen spaeter mit {@link #zeigen}.
     */
    public static Steuerprogramm vorbereiten(BotEngine engine, DiscordBridge bridge, DiscordClient client,
                                             Properties config, Path configDatei) {
        return new Steuerprogramm(engine, bridge, client, config, configDatei, Path.of(""));
    }

    /** Von Main, nach dem Bot-Fenster: Fenster, Infobereich, Tastenkuerzel. (Sprachsteuerung seit 14.10. raus, Autor Punkt 16.) */
    public void zeigen(SpielWaechter waechter) {
        this.waechter = waechter;
        tage.starten(this::laufstatistik);   // Autor 24.10. (F29): Durchlaeufe und Zeit je Tag mitschreiben (nur lesen)
        LogFenster.befehlSender = b -> senden(b, Sprache.t("Log: report error", "Protokoll: Fehler melden"));
        // 16.10.: "Fehler melden" im Protokoll oeffnet den Fehlerbericht (wird lokal gespeichert).
        LogFenster.berichtDialog = () -> berichtDialog("");
        // Nach "Einstellungen einfuegen" neu starten: erst den Motor anhalten, dann beenden (Code-Pruefung 16.10.).
        Einstellungsaustausch.beenden = () -> {
            if (engine != null) engine.stop(Sprache.t("Restart for new settings", "Neustart fuer neue Einstellungen"));
            new Thread(() -> System.exit(0), "neustart-beenden").start();
        };
        SwingUtilities.invokeLater(() -> {
            try {
                fensterBauen();
            } catch (Exception e) {
                Log.error("Steuerprogramm", Sprache.t("The control window could not be built.",
                        "Das Steuerprogramm liess sich nicht aufbauen."), e);
                return;
            }
            // Robin 24.10.: das Symbol im Infobereich (Doppelklick = Bot-Programm) IMMER, auch mit Discord -- sonst fand
            // ein Discord-Freund das Bot-Programm nicht. Die Windows-Meldungen selbst bleiben an den Schalter gebunden.
            windows.einrichten();
            // Robin 24.10.: nach einem Update (Install.bat legt fenster-nach-vorn.txt an) und beim allerersten Start
            // (Tutorial noch nicht gesehen) geht das Bot-Programm einmal VORN auf -- sonst lag es hinter dem Vollbild-Spiel.
            java.nio.file.Path vorn = de.hustlebot.core.Ordner.bei("fenster-nach-vorn.txt");
            boolean nachVorn = java.nio.file.Files.exists(vorn) || !einst.an(Tutorial.GESEHEN, false);
            boolean sparsam = de.hustlebot.core.ServerSparsam.an();
            if (sparsam) steuerfenster.ruhe();   // Autor 30.10.: auf dem Server sieht niemand hin -- kein Fenster, kein Sekundentakt
            if (!sparsam && (einst.an(Steuereinstellungen.STEUERFENSTER_ZEIGEN, true) || nachVorn)) {
                steuerfenster.setVisible(true);
                if (nachVorn) {
                    steuerfenster.toFront();
                    try { java.nio.file.Files.deleteIfExists(vorn); } catch (Exception ignored) { }
                } else {
                    steuerfenster.toBack();   // Autor 11.10.: sonst immer hinter dem Spiel starten
                }
                // Pip-Tutorial beim allerersten Start (Autor 15.10.); spaeter ueber den Knopf "Tutorial".
                if (!einst.an(Tutorial.GESEHEN, false) && zustand() != BotEngine.State.LAEUFT && zustand() != BotEngine.State.PAUSIERT) SwingUtilities.invokeLater(() -> Tutorial.erststart(steuerfenster, this));   // Autor 24.10. (F31): erst die Kurzfassung
            }
            // Autor 11.10.: "Beim Start bitte Log und Statistik nicht oeffnen, nur das Bot-Programm."
            if (einst.an(Steuereinstellungen.PROTOKOLLLEISTE, false)) protokollleiste.setVisible(true);
            if (einst.an(Steuereinstellungen.MINILEISTE, false)) minileiste.setVisible(true);
            if (!sparsam) tastenwache = Tastenwache.starten(kuerzel, this::umschalten);
            Log.info("Steuerprogramm", Sprache.t("Control window ready" + (discordAktiv ? " (Discord running alongside)." : " (without Discord)."),
                    "Steuerprogramm bereit" + (discordAktiv ? " (Discord laeuft mit)." : " (ohne Discord).")));
            befehlDateiBeobachten();
        });
    }

    /**
     * Autor 18.10.: "starte den Bot selbst und spiel 3 Minuten" -- Befehle aus der Datei befehl.txt im Bot-Ordner
     * (je Zeile einer, z. B. "!start grabung 10"), genau wie getippt. Die Datei wird gelesen und geloescht.
     */
    private void befehlDateiBeobachten() {
        Thread t = new Thread(() -> {
            java.nio.file.Path datei = de.hustlebot.core.Ordner.bei("befehl.txt");
            while (true) {
                try {
                    Thread.sleep(2000);
                    if (!java.nio.file.Files.exists(datei)) continue;
                    java.util.List<String> zeilen = java.nio.file.Files.readAllLines(datei);
                    java.nio.file.Files.deleteIfExists(datei);
                    for (String z : zeilen) {
                        String b = z.strip().replace("﻿", "");
                        if (b.startsWith("!")) senden(b, "befehl.txt");
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.debug("Steuerprogramm", "befehl.txt: " + e.getMessage());
                }
            }
        }, "befehl-datei");
        t.setDaemon(true);
        t.start();
    }

    void fensterBauen() {
        boolean obenauf = einst.an(Steuereinstellungen.FENSTER_OBENAUF, false);
        steuerfenster = new Steuerfenster(this);
        antwortfenster = new Antwortfenster(this);
        minileiste = new Minileiste(this, einst);
        statusFenster = new Kartenfenster("Status", Befehlsbau.STATUS,
                new String[]{"Zustand", "State"}, meldungen, this::zustandszeile, this, obenauf, 440, 320);
        statistikFenster = new Statistikfenster(meldungen, this, obenauf);
        vorratFenster = new Vorratsfenster(meldungen, this, obenauf);
        // Autor 11.10.: kein Fenster ueber dem Spiel (rechts) -- die Anzeigen links in die Spalte des Steuerfensters,
        // die Protokoll-Leiste in den freien Streifen unten. Verschoben merken sie sich ihre Lage.
        Holzstil.lageMerken(statusFenster, einst, "status", new java.awt.Rectangle(0, 0, 440, 320));
        Holzstil.lageMerken(statistikFenster, einst, "statistik", new java.awt.Rectangle(0, 330, 600, 470));
        Holzstil.lageMerken(vorratFenster, einst, "vorrat", new java.awt.Rectangle(0, 330, 420, 400));
        protokollleiste = new Protokollleiste(einst, obenauf);
        Holzstil.lageMerken(protokollleiste, einst, "protokollleiste", new java.awt.Rectangle(0, 812, 1410, 218));
    }

    /**
     * Hell/Dunkel umgeschaltet (Auftrag 4): die Fenster malen sich nicht um, also werden sie neu gebaut --
     * an derselben Stelle, mit demselben Reiter, offen was offen war. Die Meldungen bleiben, sie liegen
     * im Steuerprogramm, nicht im Fenster.
     */
    void aussehenSetzen(boolean dunkel) {
        einst.setzen(Steuereinstellungen.AUSSEHEN, dunkel ? "dunkel" : "hell");
        Holzstil.thema(dunkel);
        SwingUtilities.invokeLater(this::fensterNeuBauen);
    }

    private void fensterNeuBauen() {
        if (steuerfenster == null) return;
        boolean steuerAuf = steuerfenster.isVisible();
        java.awt.Point stelle = steuerfenster.getLocation();
        java.awt.Dimension groesse = steuerfenster.getSize();
        int reiter = steuerfenster.gewaehlterReiter();
        boolean miniAuf = minileiste.isVisible(), statusAuf = statusFenster.isVisible(),
                statistikAuf = statistikFenster.isVisible(), vorratAuf = vorratFenster.isVisible(),
                leisteAuf = protokollleiste.isVisible();
        java.awt.Point statusStelle = statusFenster.getLocation(), statistikStelle = statistikFenster.getLocation(),
                vorratStelle = vorratFenster.getLocation();
        for (JFrame f : new JFrame[]{steuerfenster, antwortfenster, statusFenster, statistikFenster, vorratFenster, protokollleiste}) {
            if (f instanceof Abmeldbar a) a.abmelden();
            f.dispose();
        }
        minileiste.setVisible(false);
        minileiste.dispose();
        fensterBauen();
        steuerfenster.setLocation(stelle);
        steuerfenster.setSize(groesse);
        steuerfenster.reiterWaehlen(reiter);
        statusFenster.setLocation(statusStelle);
        statistikFenster.setLocation(statistikStelle);
        vorratFenster.setLocation(vorratStelle);
        if (statusAuf) statusFenster.setVisible(true);
        if (statistikAuf) statistikFenster.setVisible(true);
        if (vorratAuf) vorratFenster.setVisible(true);
        if (miniAuf) minileiste.setVisible(true);
        if (leisteAuf) protokollleiste.setVisible(true);
        if (steuerAuf) steuerfensterZeigen();
        // Protokoll und Notizblock malen sich auch nicht um: zu und wieder auf.
        if (LogFenster.istOffen()) {
            LogFenster.zumachen();
            LogFenster.aufmachen(Path.of("logs"));
        }
        if (NotizFenster.istOffen()) {
            NotizFenster.zumachen();
            NotizFenster.aufmachen();
        }
        meldungen.eigene(Sprache.t("Look", "Aussehen"), Holzstil.istDunkel() ? Sprache.t("Dark.", "Dunkel.") : Sprache.t("Light.", "Hell."));
    }

    /** Ein Fenster, das sich bei den Meldungen abmelden kann, bevor es weggeworfen wird. */
    interface Abmeldbar {
        void abmelden();
    }

    // ------------------------------------------------------------------
    //  Der eine Befehlsweg
    // ------------------------------------------------------------------

    /** Schickt einen Befehl -- genau wie in Discord getippt. Liefert sofort zurueck. */
    public void senden(String befehl, String quelle) {
        senden(befehl, quelle, false, null);
    }

    /**
     * Wie senden, aber mit Rueckmeldung (Autor 15.10.): {@code antwort} bekommt im Swing-Faden, ob es geklappt hat --
     * ausgewertet aus den Meldungen, die der Bot auf genau diesen Befehl ausgibt. Dann kein Antworten-Fenster,
     * die Rueckmeldung steht direkt hinter dem Knopf.
     * @param abfrage true = nur nachsehen (Stand/Anzeigen)
     */
    public void senden(String befehl, String quelle, boolean abfrage, Consumer<Rueckmeldung.Ergebnis> antwort) {
        if (befehl == null) return;
        String b = befehl.trim();
        if (b.isEmpty()) return;
        if (!b.startsWith("!")) b = "!" + b;
        final String text = b;
        Log.info("Steuerprogramm", quelle + ": " + text);
        // Antworten 15 s lang im Antworten-Fenster zeigen -- nicht bei Starts und Profilen (dann spielt der Bot).
        String verb = Befehlsbau.verb(text);
        boolean start = verb.matches("!(start|starte|starten|ablauf|routine|after|danach|dann|stigmas|graben|event"
                + "|startet|startt|strat|statr|sart|stard|stadt|begin|beginne|los)")   // Code-Review 15.10.: wie !start in Discord
                || quelle.startsWith("Profil");
        if (!start && antwort == null) antwortErwartetBis = System.currentTimeMillis() + 15_000;
        if (bridge == null) {   // Vorschau ohne Bot
            meldungen.eigene(Sprache.t("Preview", "Vorschau"), Sprache.t("The command would be: ", "Der Befehl waere: ") + text + "  (" + quelle + ")");
            if (antwort != null) {
                Rueckmeldung.Ergebnis e = new Rueckmeldung.Ergebnis(Rueckmeldung.Art.HINWEIS, Sprache.t("Preview without bot: " + text, "Vorschau ohne Bot: " + text), "");
                SwingUtilities.invokeLater(() -> antwort.accept(e));
            }
            return;
        }
        befehle.submit(() -> {
            List<Meldung> antworten = new ArrayList<>();
            Exception fehler = null;
            sammler.set(antworten);
            try {
                bridge.befehlVonBedienfeld(text);
            } catch (Exception e) {
                fehler = e;
            } finally {
                sammler.remove();
            }
            if (fehler != null) {
                Log.error("Steuerprogramm", Sprache.t("Command " + text + " failed.", "Befehl " + text + " ging nicht."), fehler);
                meldungen.eigene(Sprache.t("Command failed", "Befehl ging nicht"), text + "\n" + fehler.getMessage(), DiscordClient.FARBE_FEHLER);
            }
            if (antwort != null) {
                Rueckmeldung.Ergebnis e = Rueckmeldung.auswerten(List.copyOf(antworten), fehler, abfrage);
                SwingUtilities.invokeLater(() -> antwort.accept(e));
            }
        });
    }

    /**
     * Wie senden, aber bei gefaehrlichen Befehlen (Not-Aus, Beenden, PC aus, Neustart) erst
     * eine Rueckfrage -- fuer Sprache und Tastenkuerzel (Autor 11.10.).
     */
    public void sendenMitNachfrage(String befehl, String quelle, String erklaerung) {
        sendenMitNachfrage(befehl, quelle, erklaerung, null);
    }

    /** Wie sendenMitNachfrage, mit Rueckmeldung je Knopf (siehe senden mit Rueckruf). */
    public void sendenMitNachfrage(String befehl, String quelle, String erklaerung, Consumer<Rueckmeldung.Ergebnis> antwort) {
        if (!Befehlsbau.gefaehrlich(befehl)) {
            senden(befehl, quelle, false, antwort);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (fragen(Sprache.t("Really: ", "Wirklich: ") + erklaerung + Sprache.t("?\n\nCommand: ", "?\n\nBefehl: ") + befehl + "\n(" + quelle + ")", erklaerung)) {
                senden(befehl, quelle + Sprache.t(", confirmed", ", bestaetigt"), false, antwort);
            } else {
                meldungen.eigene(Sprache.t("Cancelled", "Abgebrochen"), erklaerung + Sprache.t(" -- not done.", " -- nicht ausgefuehrt."));
                Log.info("Steuerprogramm", Sprache.t(quelle + ": " + befehl + " -- refused.",
                        quelle + ": " + befehl + " -- abgelehnt."));
                if (antwort != null) antwort.accept(new Rueckmeldung.Ergebnis(Rueckmeldung.Art.NICHT_GEKLAPPT,
                        Sprache.t("Cancelled – nothing changed.", "Abgebrochen – nichts geändert."), ""));
            }
        });
    }

    /** Eine Ja/Nein-Frage im Fenster-Faden. */
    boolean fragen(String text, String titel) {
        java.awt.Component owner = steuerfenster != null && steuerfenster.isVisible() ? steuerfenster : null;
        JOptionPane pane = new JOptionPane(text, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog d = pane.createDialog(owner, titel);
        d.setAlwaysOnTop(true);   // sonst liegt die Frage hinter dem Spiel, das der Motor nach vorn holt
        d.setVisible(true);
        d.dispose();
        Object w = pane.getValue();
        return w instanceof Integer i && i == JOptionPane.YES_OPTION;
    }

    /** "Bot beenden": fragt, dann ganz beenden (die Freunde-Fassung hat kein !beenden). */
    public void botBeenden() {
        SwingUtilities.invokeLater(() -> {
            String laeuft = zustand() == BotEngine.State.LAEUFT
                    ? Sprache.t("\n\nA mode is running right now -- it will be stopped.", "\n\nGerade laeuft ein Modus -- der wird abgebrochen.") : "";
            if (!fragen(Sprache.t("This closes the whole program, not only the mode.", "Das schliesst das ganze Programm, nicht nur den Modus.") + laeuft
                    + Sprache.t("\n\nStart the bot again with run.bat or the desktop icon.\n\nQuit the bot now?",
                                "\n\nWieder starten mit run.bat oder dem Desktop-Symbol.\n\nBot jetzt beenden?"),
                    Sprache.t("Quit bot?", "Bot beenden?"))) return;
            if (bridge == null) return;   // Vorschau
            if (engine != null) engine.stop(Sprache.t("Quit in the control window", "Beenden im Steuerprogramm"));
            Log.info("Ende", Sprache.t("Quit via the control window.", "Beenden ueber das Steuerprogramm."));
            new Thread(() -> System.exit(0), "steuer-beenden").start();
        });
    }

    /** "Bot neu starten": fragt, dann !neustart. */
    public void botNeuStarten() {
        SwingUtilities.invokeLater(() -> {
            if (fragen(Sprache.t("The bot will be stopped and restarted with the same settings.\nThe counter starts at zero.\n\nRestart now?",
                            "Der Bot wird angehalten und mit denselben Einstellungen neu gestartet.\nDer Zaehler beginnt bei null.\n\nJetzt neu starten?"),
                    Sprache.t("Restart bot?", "Bot neu starten?"))) {
                senden(Befehlsbau.NEUSTART, Sprache.t("Button", "Knopf"));
            }
        });
    }

    /** Beenden aus dem Infobereich: wie im Bot-Fenster, ohne Discord-Umweg. */
    private void beendenGewuenscht() {
        SwingUtilities.invokeLater(() -> {
            if (!fragen(Sprache.t("Quit the Hustle Castle Bot completely?", "Den Hustle Castle Bot ganz beenden?"), Sprache.t("Quit bot?", "Bot beenden?"))) return;
            if (engine != null) engine.stop(Sprache.t("Quit in the control window", "Beenden im Steuerprogramm"));
            Log.info("Ende", Sprache.t("Quit via the control window.", "Beenden ueber das Steuerprogramm."));
            new Thread(() -> System.exit(0), "steuer-beenden").start();
        });
    }

    // ------------------------------------------------------------------
    //  Fenster auf und zu
    // ------------------------------------------------------------------

    /** Ein Kuerzel, ein Sprachbefehl oder ein Knopf: Fenster auf/zu, Sprache an/aus, Profil anwenden. */
    public void umschalten(Tastenkuerzel.Aktion a) {
        if (a == null) return;
        SwingUtilities.invokeLater(() -> {
            switch (a) {
                case PROTOKOLL -> {
                    if (LogFenster.istOffen()) LogFenster.zumachen();
                    else LogFenster.aufmachen(Path.of("logs"));
                }
                case NOTIZBLOCK -> {
                    if (NotizFenster.istOffen()) NotizFenster.zumachen();
                    else NotizFenster.aufmachen();
                }
                case VORRAT -> {
                    boolean auf = !vorratFenster.isVisible();
                    vorratFenster.setVisible(auf);
                    if (auf && meldungen.letzteMit("Burg-Stand", "Castle status") == null) senden(Befehlsbau.STAND, Sprache.t("Supplies window", "Vorrat-Fenster"));
                }
                case STATISTIK -> {
                    boolean auf = !statistikFenster.isVisible();
                    statistikFenster.setVisible(auf);
                    if (auf) senden(Befehlsbau.STATS, Sprache.t("Statistics window", "Statistik-Fenster"));
                }
                case STATUS -> {
                    boolean auf = !statusFenster.isVisible();
                    statusFenster.setVisible(auf);
                    if (auf) senden(Befehlsbau.STATUS, Sprache.t("Status window", "Status-Fenster"));
                }
                case MINILEISTE -> {
                    boolean auf = !minileiste.isVisible();
                    minileiste.setVisible(auf);
                    einst.setzen(Steuereinstellungen.MINILEISTE, auf);
                }
                // Autor 14.10.: kein Reiter Meldungen mehr -- das Kuerzel klappt das Antworten-Fenster auf und zu.
                case MELDUNGEN -> antwortfenster.setVisible(!antwortfenster.isVisible());
                case BEENDEN -> botBeenden();
                case STEUERFENSTER -> {
                    if (steuerfenster.isVisible()) steuerfenster.setVisible(false);
                    else steuerfensterZeigen();
                }
                case PROFIL_1 -> profilAnwenden(profile.name(1), Sprache.t("Shortcut", "Kuerzel"));
                case PROFIL_2 -> profilAnwenden(profile.name(2), Sprache.t("Shortcut", "Kuerzel"));
                case PROFIL_3 -> profilAnwenden(profile.name(3), Sprache.t("Shortcut", "Kuerzel"));
            }
            if (steuerfenster != null) steuerfenster.anzeigenNachziehen();
        });
    }

    /**
     * Auftrag 6 (11.10., "alles auch in Discord"): ein Fenster gezielt auf- oder zumachen -- "!steuer
     * fenster an". umschalten() wechselt nur; hier wird erst nachgesehen, ob es schon so steht.
     */
    public void anzeigeSetzen(Tastenkuerzel.Aktion a, boolean an) {
        if (a == null || offen(a) == an) return;
        umschalten(a);
    }

    public Tastenkuerzel tastenkuerzel() { return kuerzel; }

    public Profile profile() { return profile; }

    public boolean obenauf() { return einst.an(Steuereinstellungen.FENSTER_OBENAUF, false); }

    /** Der Stand aller Schalter des Steuerprogramms in Zeilen -- fuer "!steuer" in Discord. */
    public String uebersicht() {
        StringBuilder b = new StringBuilder();
        String an = Sprache.t("on", "an"), aus = Sprache.t("off", "aus");
        b.append(Sprache.t("Control window: ", "Steuerfenster: ")).append(offen(Tastenkuerzel.Aktion.STEUERFENSTER) ? Sprache.t("open", "offen") : Sprache.t("closed", "zu")).append('\n');
        b.append(Sprache.t("Mini bar: ", "Mini-Leiste: ")).append(offen(Tastenkuerzel.Aktion.MINILEISTE) ? an : aus).append('\n');
        b.append(Sprache.t("Windows messages: ", "Windows-Meldungen: ")).append(windowsMeldungenAn() ? an : aus).append('\n');
        b.append(Sprache.t("Display windows always on top: ", "Anzeige-Fenster immer obenauf: ")).append(obenauf() ? an : aus).append('\n');
        b.append(Sprache.t("Open windows: ", "Fenster offen: "));
        java.util.List<String> offen = new java.util.ArrayList<>();
        for (Tastenkuerzel.Aktion a : new Tastenkuerzel.Aktion[]{Tastenkuerzel.Aktion.PROTOKOLL, Tastenkuerzel.Aktion.NOTIZBLOCK,
                Tastenkuerzel.Aktion.VORRAT, Tastenkuerzel.Aktion.STATISTIK, Tastenkuerzel.Aktion.STATUS}) {
            if (offen(a)) offen.add(a.anzeige.replace(" auf/zu", "").replace(" show/hide", ""));
        }
        b.append(offen.isEmpty() ? Sprache.t("none", "keins") : String.join(", ", offen)).append('\n');
        b.append(Sprache.t("Shortcuts: ", "Tastenkuerzel: ")).append(kuerzel.alleAn() ? an : Sprache.t("all off", "alle aus")).append('\n');
        b.append(Sprache.t("Profiles: ", "Profile: ")).append(profile.namen().isEmpty() ? Sprache.t("none", "keine") : String.join(", ", profile.namen())).append('\n');
        b.append(Sprache.t("Look: ", "Aussehen: ")).append(dunkel() ? Sprache.t("dark", "dunkel") : Sprache.t("light", "hell"));
        return b.toString();
    }

    /** Ist das Fenster zu dieser Aktion gerade offen? Fuer die Haekchen im Reiter "Anzeigen". */
    public boolean offen(Tastenkuerzel.Aktion a) {
        return switch (a) {
            case PROTOKOLL -> LogFenster.istOffen();
            case NOTIZBLOCK -> NotizFenster.istOffen();
            case VORRAT -> vorratFenster != null && vorratFenster.isVisible();
            case STATISTIK -> statistikFenster != null && statistikFenster.isVisible();
            case STATUS -> statusFenster != null && statusFenster.isVisible();
            case MINILEISTE -> minileiste != null && minileiste.isVisible();
            case STEUERFENSTER, MELDUNGEN -> steuerfenster != null && steuerfenster.isVisible();
            default -> false;
        };
    }

    public void steuerfensterZeigen() {
        SwingUtilities.invokeLater(() -> {
            if (steuerfenster == null) return;
            steuerfenster.setVisible(true);
            steuerfenster.setExtendedState(java.awt.Frame.NORMAL);
            steuerfenster.toFront();
        });
    }

    /** "Immer obenauf" fuer die kleinen Anzeige-Fenster. */
    public void obenaufSetzen(boolean an) {
        einst.setzen(Steuereinstellungen.FENSTER_OBENAUF, an);
        for (JFrame f : new JFrame[]{statusFenster, statistikFenster, vorratFenster, protokollleiste}) {
            if (f != null) f.setAlwaysOnTop(an);
        }
    }

    /** Protokoll-Leiste unten an/aus (Einstellungen -> Darstellung), gemerkt fuer den naechsten Start. */
    void protokollleisteSetzen(boolean an) {
        einst.setzen(Steuereinstellungen.PROTOKOLLLEISTE, an);
        if (protokollleiste != null) protokollleiste.setVisible(an);
    }

    // ------------------------------------------------------------------
    //  Profile, Discord, Fehlerbericht
    // ------------------------------------------------------------------

    public void profilAnwenden(String name, String quelle) {
        if (name == null || name.isBlank()) {
            meldungen.eigene(Sprache.t("Profile", "Profil"), Sprache.t("No profile at this place. Create one in the tab Profiles.", "Kein Profil an dieser Stelle. Im Reiter Profile anlegen."), DiscordClient.FARBE_WARNUNG);
            return;
        }
        int n = profile.anwenden(name, b -> senden(b, Sprache.t("Profile ", "Profil ") + name + " (" + quelle + ")"));
        meldungen.eigene(Sprache.t("Profile \"", "Profil \"") + name + "\"", n == 0 ? Sprache.t("Is empty -- nothing sent.", "Ist leer -- nichts geschickt.")
                : Sprache.t(n + " commands sent.", n + " Befehle geschickt."));
    }

    /**
     * Discord-Zugang eintragen: Token, Kanal und Schalter in
     * config.properties ueber denselben Weg wie "!settings notaus". Gilt nach einem Neustart.
     */
    public String discordEinstellen(boolean an, String token, String kanal) {
        try {
            DiscordBridge.configWertSchreiben(configDatei, "discord.enabled", an ? "true" : "false");
            if (kanal != null && !kanal.isBlank()) DiscordBridge.configWertSchreiben(configDatei, "discord.channelId", kanal.trim());
            // Freunde-Bot: das Token steht in config.properties (dort liest Main es), kein geheim.properties.
            if (token != null && !token.isBlank()) DiscordBridge.configWertSchreiben(configDatei, "discord.botToken", token.trim());
            String text = Sprache.t("Saved. After the next restart of the bot Discord is " + (an ? "on" : "off") + ".",
                    "Gespeichert. Discord ist nach dem naechsten Neustart des Bots " + (an ? "an" : "aus") + ".");
            meldungen.eigene("Discord", text);
            return text;
        } catch (Exception e) {
            Log.error("Steuerprogramm", Sprache.t("Discord access not saved.", "Discord-Zugang nicht gespeichert."), e);
            return Sprache.t("Not saved: ", "Nicht gespeichert: ") + e.getMessage();
        }
    }

    /**
     * Fehler melden mit einem Klick (Idee aus dem Bauplan): legt einen Ordner fehlerberichte\<Zeit>
     * mit Bildschirmfoto und Protokoll an und oeffnet ihn.
     */
    // ==================================================================
    //  Fehlerbericht (Autor 16.10.: "einen Fehlerbericht-Knopf"). Wird nur lokal als ZIP gespeichert,
    //  nichts wird verschickt. Mit Bericht-Nummer.
    // ==================================================================

    private static volatile long letzterBericht = 0;

    /** Fragt nach einer kurzen Beschreibung und speichert den Bericht im Hintergrund. */
    public void berichtDialog(String vorschlag) {
        SwingUtilities.invokeLater(() -> {
            java.awt.Component owner = steuerfenster != null && steuerfenster.isVisible() ? steuerfenster : null;
            JTextArea feld = new JTextArea(vorschlag == null ? "" : vorschlag, 5, 40);
            feld.setLineWrap(true);
            feld.setWrapStyleWord(true);
            JCheckBox bild = new JCheckBox(Sprache.t("Include a picture of the game window", "Bild vom Spielfenster dazulegen"), true);
            JPanel p = new JPanel(new java.awt.BorderLayout(0, 6));
            p.add(new JLabel("<html><div style='width:380px'>" + Sprache.t(
                    "What happened? (optional)<br>The log, a picture of the game and your settings are saved as a ZIP "
                    + "in logs\\berichte on this PC -- nothing is sent. Never your Discord token.",
                    "Was ist passiert? (freiwillig)<br>Protokoll, ein Bild vom Spiel und deine Einstellungen werden als ZIP "
                    + "in logs\\berichte auf diesem PC gespeichert -- nichts wird verschickt. Nie dein Discord-Token.") + "</div></html>"), java.awt.BorderLayout.NORTH);
            p.add(new JScrollPane(feld), java.awt.BorderLayout.CENTER);
            p.add(bild, java.awt.BorderLayout.SOUTH);
            Object[] knoepfe = {Sprache.t("Save report", "Bericht speichern"), Sprache.t("Cancel", "Abbrechen")};
            JOptionPane pane = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.YES_NO_OPTION, null, knoepfe, knoepfe[0]);
            JDialog d = pane.createDialog(owner, Sprache.t("Save error report", "Fehlerbericht speichern"));
            d.setAlwaysOnTop(true);
            d.setVisible(true);
            d.dispose();
            if (pane.getValue() != knoepfe[0]) return;
            String text = feld.getText().trim();
            boolean mitBild = bild.isSelected();
            Thread t = new Thread(() -> {
                String ergebnis = berichtSpeichern(text, mitBild);
                SwingUtilities.invokeLater(() -> berichtHinweis(ergebnis));
            }, "bericht-speichern");
            t.setDaemon(true);
            t.start();
        });
    }

    private void berichtHinweis(String text) {
        java.awt.Component owner = steuerfenster != null && steuerfenster.isVisible() ? steuerfenster : null;
        JOptionPane pane = new JOptionPane(text, JOptionPane.INFORMATION_MESSAGE);
        JDialog d = pane.createDialog(owner, Sprache.t("Error report", "Fehlerbericht"));
        d.setAlwaysOnTop(true);
        d.setVisible(true);
        d.dispose();
    }

    /** Baut den Bericht und legt ihn lokal als ZIP unter logs\berichte ab (nichts wird verschickt). Liefert den Text fuer den Nutzer. */
    public String berichtSpeichern(String beschreibung, boolean mitBild) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzterBericht < 60_000L) {
            return Sprache.t("A report was saved less than a minute ago -- please wait a moment.",
                    "Vor weniger als einer Minute wurde schon ein Bericht gespeichert -- bitte kurz warten.");
        }
        letzterBericht = jetzt;
        String id = berichtNummer();
        String b = beschreibung == null ? "" : beschreibung.strip();
        byte[] zip;
        try {
            zip = berichtZip(id, b);
        } catch (Exception e) {
            Log.warn("Fehlerbericht", String.valueOf(e.getMessage()));
            return Sprache.t("The report could not be built: ", "Der Bericht liess sich nicht bauen: ") + e.getMessage();
        }
        try {
            Path ordner = de.hustlebot.core.Ordner.bei("logs").resolve("berichte");
            Files.createDirectories(ordner);
            Path z = ordner.resolve("bericht-" + id + ".zip");
            Files.write(z, zip);
            if (mitBild && bridge != null) {
                byte[] png = bridge.spielbildPng();
                if (png != null) Files.write(ordner.resolve("bericht-" + id + "-spielbild.png"), png);
            }
            Log.info("Fehlerbericht", "Report #" + id + " -> " + z.toAbsolutePath());
            meldungen.eigene(Sprache.t("Error report", "Fehlerbericht"),
                    Sprache.t("Report #" + id + " saved: " + z.toAbsolutePath(), "Bericht #" + id + " gespeichert: " + z.toAbsolutePath()));
            return Sprache.t("✔ Report #" + id + " is saved here:\n" + z.toAbsolutePath(),
                    "✔ Bericht #" + id + " liegt hier:\n" + z.toAbsolutePath());
        } catch (Exception e) {
            return Sprache.t("The report could not be saved: ", "Der Bericht liess sich nicht speichern: ") + e.getMessage();
        }
    }

    private static String berichtNummer() {
        String z = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(z.charAt(r.nextInt(z.length())));
        return b.toString();
    }

    /** ZIP mit info.txt, fehlerbericht.txt, den letzten Protokollen und den Einstellungen (Geheimnisse entfernt). */
    private byte[] berichtZip(String id, String beschreibung) throws java.io.IOException {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        java.io.ByteArrayOutputStream raus = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raus, utf8)) {
            StringBuilder info = new StringBuilder();
            info.append("Report #").append(id).append('\n');
            info.append("Time: ").append(de.hustlebot.core.Zeitzone.beschreibung()).append('\n');
            info.append("What happened: ").append(beschreibung.isEmpty() ? "-" : beschreibung).append('\n');
            info.append("Version: ").append(de.hustlebot.core.UpdateNachschub.fassungInDerJar()).append('\n');
            info.append("State: ").append(zustand()).append('\n');
            info.append("Status: ").append(statusText()).append('\n');
            info.append("Mode: ").append(engine == null ? "-" : engine.lastModeId()).append('\n');
            info.append("Language: ").append(Sprache.get()).append('\n');
            info.append("Stop when mouse moves: ").append(de.hustlebot.core.Mauswache.an()).append('\n');
            info.append("Java: ").append(System.getProperty("java.version")).append(" | OS: ")
                    .append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
            try {
                java.awt.Dimension sch = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                info.append("Screen: ").append(sch.width).append('x').append(sch.height).append('\n');
            } catch (Exception ignored) {
                // ohne Bildschirmgroesse
            }
            try {
                info.append("Game window: ").append(engine.context().width()).append('x').append(engine.context().height()).append('\n');
            } catch (Exception ignored) {
                // ohne Spielfenster
            }
            // 17.10. (Karla, Laptop + Samsung-Monitor): jeder Bildschirm mit Groesse, Lage und Windows-Skalierung.
            try {
                for (var d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    var c = d.getDefaultConfiguration();
                    var b = c.getBounds();
                    info.append("Monitor: ").append(d.getIDstring()).append(' ').append(b.width).append('x').append(b.height)
                            .append(" at ").append(b.x).append(',').append(b.y)
                            .append(String.format(" scaling %.0f%%", c.getDefaultTransform().getScaleX() * 100)).append('\n');
                }
            } catch (Exception ignored) {
                // ohne Bildschirm-Angaben
            }
            try {
                info.append("Own pictures (learned on this PC): ").append(engine.context().matcher().eigeneAnzahl()).append('\n');
            } catch (Exception ignored) {
                // ohne Zahl
            }
            // Karla 17.10.: wie schnell dieser PC ist -- Bild vom Spielfenster und Bilderkennung.
            try {
                info.append(String.format("Picture of the game window: %.0f ms on average%n",
                        de.hustlebot.bot.BotContext.bildMsSchnitt()));
                info.append("Recognition: ").append(engine.context().matcher().vergleichsBericht()).append('\n');
            } catch (Exception ignored) {
                // ohne Messwerte
            }
            // Autor 17.10.: Kathedralen-Zeitmesser -- die letzten Run-Zeiten gleich im Bericht.
            info.append("\nCathedral times:\n").append(de.hustlebot.core.Kathedralzeiten.uebersicht()).append('\n');
            info.append("\nLast messages:\n");
            var alle = meldungen.alle();
            for (int i = Math.max(0, alle.size() - 40); i < alle.size(); i++) {
                Meldung m = alle.get(i);
                info.append(m.uhrzeit()).append("  ").append(m.titelLesbar()).append(" -- ").append(m.textLesbar().replace('\n', ' ')).append('\n');
            }
            berichtEintrag(zip, "info.txt", info.toString().getBytes(utf8));
            // Autor 17.10.: die auf diesem PC aufgenommenen Knoepfe dazulegen (hoechstens 2 MB).
            try {
                java.nio.file.Path eigen = de.hustlebot.core.Matcher.EIGEN_ORDNER;
                if (eigen != null && java.nio.file.Files.isDirectory(eigen)) {
                    long summe = 0;
                    try (var liste = java.nio.file.Files.list(eigen)) {
                        for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) liste.sorted()::iterator) {
                            if (!f.getFileName().toString().endsWith(".png")) continue;
                            byte[] b = java.nio.file.Files.readAllBytes(f);
                            if (summe + b.length > 2_000_000) break;
                            summe += b.length;
                            berichtEintrag(zip, "eigene-vorlagen/" + f.getFileName(), b);
                        }
                    }
                }
            } catch (Exception ignored) {
                // ohne eigene Vorlagen
            }
            try {
                java.util.Map<String, String> fakten = new java.util.LinkedHashMap<>();
                fakten.put("Report", "#" + id);
                fakten.put("Zustand", String.valueOf(zustand()));
                fakten.put("Status", statusText());
                berichtEintrag(zip, "fehlerbericht.txt", de.hustlebot.core.Report.build(fakten).getBytes(utf8));
            } catch (Exception ignored) {
                // ohne Systembericht
            }
            Path logs = de.hustlebot.core.Ordner.bei("logs");
            Path aktuell = Log.currentLogFile();
            if (aktuell != null && Files.exists(aktuell)) berichtEintrag(zip, "protokoll.log", berichtEnde(aktuell, 3_000_000));
            if (Files.isDirectory(logs)) {
                try (var st = Files.list(logs)) {
                    var vorher = st.filter(x -> x.getFileName().toString().matches("bot_.*\\.log") && (aktuell == null || !x.equals(aktuell)))
                            .max(java.util.Comparator.comparingLong(x -> x.toFile().lastModified()));
                    if (vorher.isPresent()) berichtEintrag(zip, "protokoll-vorher.log", berichtEnde(vorher.get(), 1_000_000));
                }
            }
            Path basis = de.hustlebot.core.Ordner.basis();
            if (basis != null && Files.isDirectory(basis)) {
                try (var st = Files.list(basis)) {
                    for (Path f : st.toList()) {
                        String n = f.getFileName().toString();
                        if (!Files.isRegularFile(f) || !n.matches("(?i).+\\.(txt|properties)") || Files.size(f) > 256_000) continue;
                        if (n.matches("(?i)(0-START-HERE|GUIDE-|ANLEITUNG|EVENT-ABLAUF|QUICK-START|KURZANLEITUNG|VORLAGEN-AUFNEHMEN|CAPTURE-IMAGES|LIESMICH|README|NEUERUNGEN).*")) continue;
                        // Nie mitschicken: geheime Zugaenge.
                        if (n.matches("(?i)(geheim|lizenz).*")) continue;
                        String inhalt = new String(Files.readAllBytes(f), utf8);
                        // Geheimnisse raus: Schluessel, Token, Webhooks, Passwoerter -- nie mitschicken.
                        // Code-Pruefung 16.10.: auch "key: wert" und "key wert" -- und die Stichwoerter thema/topic/key.
                        inhalt = inhalt.replaceAll("(?im)^(\\s*[^\\r\\n:=]*(schluessel|schlüssel|token|webhook|secret|passwort|password|lizenz|licence|license|thema|topic|apikey|api_key)[^\\r\\n:=]*)([:=]|\\s).*$", "$1$3 <removed>");
                        berichtEintrag(zip, "einstellungen/" + n, inhalt.getBytes(utf8));
                    }
                }
            }
        }
        return raus.toByteArray();
    }

    private static byte[] berichtEnde(Path p, int max) throws java.io.IOException {
        long gr = Files.size(p);
        try (var in = Files.newInputStream(p)) {
            if (gr > max) in.skipNBytes(gr - max);
            return in.readAllBytes();
        }
    }

    private static void berichtEintrag(java.util.zip.ZipOutputStream zip, String name, byte[] daten) throws java.io.IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(daten);
        zip.closeEntry();
    }

    public String fehlerberichtAnlegen() {
        try {
            String zeit = de.hustlebot.core.Zeitzone.jetzt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path ordner = Path.of("fehlerberichte", zeit);
            Files.createDirectories(ordner);
            try {
                java.awt.Rectangle schirm = new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                java.awt.image.BufferedImage foto = new java.awt.Robot().createScreenCapture(schirm);
                javax.imageio.ImageIO.write(foto, "png", ordner.resolve("bildschirm.png").toFile());
            } catch (Exception e) {
                Files.writeString(ordner.resolve("kein-foto.txt"), Sprache.t("Screenshot failed: ", "Bildschirmfoto ging nicht: ") + e.getMessage());
            }
            Path log = Log.currentLogFile();
            if (log != null && Files.exists(log)) Files.copy(log, ordner.resolve("protokoll.log"));
            StringBuilder info = new StringBuilder();
            info.append(Sprache.t("State: ", "Zustand: ")).append(zustand()).append('\n');
            info.append("Status: ").append(statusText()).append('\n');
            info.append(Sprache.t("Mode: ", "Modus: ")).append(engine == null ? "-" : engine.lastModeId()).append('\n');
            info.append(Sprache.t("Last messages:\n", "Letzte Meldungen:\n"));
            var alle = meldungen.alle();
            for (int i = Math.max(0, alle.size() - 30); i < alle.size(); i++) {
                Meldung m = alle.get(i);
                info.append(m.uhrzeit()).append("  ").append(m.titelLesbar()).append(" -- ").append(m.textLesbar().replace('\n', ' ')).append('\n');
            }
            Files.writeString(ordner.resolve("info.txt"), info.toString(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.awt.Desktop.getDesktop().open(ordner.toFile());
            } catch (Exception ignored) {
                // kein Explorer -- der Pfad steht in der Antwort
            }
            String text = Sprache.t("Error report is in " + ordner.toAbsolutePath() + ".",
                    "Fehlerbericht liegt in " + ordner.toAbsolutePath() + ".");
            meldungen.eigene(Sprache.t("Error report", "Fehlerbericht"), text);
            return text;
        } catch (Exception e) {
            return Sprache.t("Error report failed: ", "Fehlerbericht ging nicht: ") + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    //  Windows-Meldungen
    // ------------------------------------------------------------------

    private void windowsMeldung(Meldung m) {
        if (!windows.bereit()) return;
        if (!einst.an(Steuereinstellungen.WINDOWS_MELDUNGEN, !discordAktiv)) return;
        boolean wichtig = m.farbe() == DiscordClient.FARBE_WARNUNG || m.farbe() == DiscordClient.FARBE_FEHLER
                || m.farbe() == DiscordClient.FARBE_ERFOLG || m.farbe() == DiscordClient.FARBE_START
                || m.titelEnthaelt("fertig", "done", "finished", "abgeschlossen");
        if (!wichtig) return;
        windows.zeigen(m.titelLesbar(), m.textLesbar(), m.farbe());
    }

    public void windowsMeldungenSetzen(boolean an) {
        einst.setzen(Steuereinstellungen.WINDOWS_MELDUNGEN, an);
        if (an && !windows.bereit()) windows.einrichten();
        // Aus = keine Meldungen mehr (windowsMeldung prueft den Schalter); das Symbol bleibt, um das Bot-Programm zu oeffnen.
    }

    public boolean windowsMeldungenAn() {
        return einst.an(Steuereinstellungen.WINDOWS_MELDUNGEN, !discordAktiv);
    }

    // ------------------------------------------------------------------
    //  Fuer die Fenster
    // ------------------------------------------------------------------

    boolean discordAktiv() {
        return discordAktiv;
    }

    /** "Der Bot spielt – Kathedrale 12/100 · Hustle Castle ist offen" fuer Kopfzeilen. */
    String zustandszeile() {
        BotEngine.State z = zustand();
        String text = switch (z) {
            case LAEUFT -> Sprache.t("The bot is playing", "Der Bot spielt");
            case PAUSIERT -> Sprache.t("Paused", "Pausiert");
            case BEREIT -> Sprache.t("Ready – waiting for a command", "Bereit – wartet auf einen Befehl");
            case FERTIG -> Sprache.t("Done", "Fertig");
            case GESTOPPT -> Sprache.t("Stopped", "Gestoppt");
            case FEHLER -> Sprache.t("Aborted", "Abgebrochen");
        };
        String details = statusText();
        // "Bereit – wartet auf einen Befehl – Bereit." waere doppelt gemoppelt.
        if (details != null && !details.isBlank() && !details.trim().equalsIgnoreCase(Sprache.t("Ready.", "Bereit."))
                && !details.trim().equalsIgnoreCase(Sprache.t("Ready", "Bereit"))) {
            text += " – " + details;
        }
        if (waechter != null) {
            long rest = waechter.beendetInMs();
            if (rest >= 0) {
                long s = rest / 1000;
                text += String.format(Sprache.t(" · Hustle Castle is closed, the bot quits in %d:%02d",
                        " · Hustle Castle ist zu, der Bot beendet sich in %d:%02d"), s / 60, s % 60);
            } else {
                text += waechter.spielOffen() ? Sprache.t(" · Hustle Castle is open", " · Hustle Castle ist offen")
                        : Sprache.t(" · Hustle Castle is starting …", " · Hustle Castle wird gestartet …");
            }
        }
        return text;
    }
}
