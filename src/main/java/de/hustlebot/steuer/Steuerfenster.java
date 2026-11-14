package de.hustlebot.steuer;

import de.hustlebot.bot.BotEngine;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Stigmaplan;
import de.hustlebot.discord.DiscordClient;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Das grosse Fenster des Steuerprogramms, in Holz-Toenen, mit Reitern:
 *
 *   Steuerung     Start mit Modus und Anzahl, Pause/Weiter, Stopp, Not-Aus,
 *                 Warteschlange, Kreislauf, Bot neu starten, Bot beenden
 *   Meldungen     alles, was der Bot sagt (auch ohne Discord), mit Knoepfen bei Rueckfragen
 *   Anzeigen      Status, Statistik, Protokoll, Vorrat, Notizblock, Mini-Leiste ein/aus
 *   Einstellungen Aussehen (hell/dunkel) und alles aus dem Bauplan -- jeder Knopf schickt den
 *                 passenden !settings-Befehl
 *   Tastenkuerzel jedes Kuerzel aenderbar, einzeln und ganz an/aus
 *   Profile       Befehlslisten speichern und mit einem Klick anwenden
 *   Support       Discord spieler, E-Mail, Fehlerbericht mit einem Klick
 *
 * Aussehen (Auftrag 4, Autor 11.10.): alles, was zusammengehoert, steht auf einer Karte; die Knoepfe
 * tragen Symbole; oben rechts zeigt die Zustands-Pille, was der Bot tut; Meldungen sind Karten mit
 * Farbstreifen, Uhrzeit und Titel.
 *
 * Jeder Knopf baut nur einen Text (Befehlsbau) und gibt ihn an Steuerprogramm.senden
 * -- den einen Befehlsweg. Nichts hier greift am Bot vorbei.
 *
 * Das X schliesst nur das Fenster; der Bot laeuft weiter (wie das Bot-Fenster sagt:
 * zum Beenden gibt es dort "Beenden" oder hier den Knopf "Bot beenden").
 */
final class Steuerfenster extends JFrame implements Steuerprogramm.Abmeldbar {

    static final int REITER_STEUERUNG = 0, REITER_ANZEIGEN = 1, REITER_EINSTELLUNGEN = 2,
            REITER_KUERZEL = 3, REITER_PROFILE = 4, REITER_WERKZEUGE = 5, REITER_SUPPORT = 6;

    private final Steuerprogramm steuer;
    private final JLabel zustand = Holzstil.kopfzeile(" ");
    private final Holzstil.Pille pille = new Holzstil.Pille(true);
    private final JTabbedPane reiter = Holzstil.reiter();

    // Steuerung (Autor 14.10.: ein Start-Knopf, Bezahlen und Extra-Haekchen raus, Mitspieler zum Anklicken)
    private final JComboBox<Object> modus = Holzstil.auswahl(new Object[0]);
    private final JTextField anzahl = Holzstil.feld("10", 6);
    private final JComboBox<String> weg = Holzstil.auswahl(new String[]{Sprache.t("c – turbo", "c – Turbo"), Sprache.t("b – all waypoints", "b – alle Wegpunkte")});
    private final JComboBox<String> titan = Holzstil.auswahl(titanNamen());

    /**
     * Autor 23.10.: "Fuege jeden Namen zum Stigma-Bot-Programm hinzu, so dass man jeden Titan auswaehlen kann." Die Namen
     * kommen aus der Titan Collection (templates/gelernt/titanen/titanen.txt, in der Reihenfolge des Spiels); ohne die
     * Liste bleiben die bisherigen sechs.
     */
    static String[] titanNamen() {
        List<String> namen = new java.util.ArrayList<>();
        try {
            java.nio.file.Path liste = de.hustlebot.core.Ordner.bei("templates").resolve("gelernt").resolve("titanen").resolve("titanen.txt");
            for (String z : java.nio.file.Files.readAllLines(liste)) {
                String[] t = z.split("\t");
                if (t.length < 2 || t[1].isBlank()) continue;
                // Autor 23.10.: Klasse dazuschreiben -- Bull-Stigmas passen nur auf Archer.
                String klasse = t.length >= 3 ? t[2].trim().toLowerCase(java.util.Locale.ROOT) : "";
                String name = t[1].trim() + (klasse.equals("tank") ? " (Tank)" : klasse.equals("archer") ? " (Archer)" : "");
                if (!namen.contains(name)) namen.add(name);
            }
        } catch (Exception e) {
            // keine Liste: die bekannten Titanen
        }
        if (namen.isEmpty()) namen.addAll(List.of("Medusa", "Keeper of Stars", "Thundersaurus", "Torturer", "Pandemon"));
        // Der Last Guardian bleibt vorne -- er ist die Voreinstellung (titanWort "").
        String lg = namen.stream().filter(n -> n.startsWith("Last Guardian")).findFirst().orElse("Last Guardian");
        namen.remove(lg);
        namen.add(0, lg);
        return namen.toArray(String[]::new);
    }
    private final JTextField freund = Holzstil.feld("", 14);
    private final JTextField portalBis = Holzstil.feld("135", 6);
    // Autor 23.10. abends: "hoechstens 2 Level, je Level eingeben, wie viel Nahrung der Bot hoechstens benutzen darf."
    private final JTextField portalL1 = Holzstil.feld("", 4), portalN1 = Holzstil.feld("", 7);
    private final JTextField portalL2 = Holzstil.feld("", 4), portalN2 = Holzstil.feld("", 7);
    private final JCheckBox pcAus = Holzstil.kaestchen(Sprache.t("PC off afterwards", "PC danach aus"), false, Sprache.t("Like the word pcaus in the start command.", "Wie das Wort pcaus im Start-Befehl."));
    private final JLabel vorschau = Holzstil.hinweis(" ");
    private final JTextField frei = Holzstil.feld("", 30);

    // Tutorial mit Pip (15.10.): Ziele, die Pip markiert, und die leuchtende Markierung ueber dem Fenster.
    private final java.util.Map<String, JComponent> ziele = new java.util.LinkedHashMap<>();
    private final Markierung markierung = new Markierung();

    // Anzeigen
    private final List<JCheckBox> anzeigeKaestchen = new ArrayList<>();
    private final JCheckBox windowsMeldungen = Holzstil.kaestchen(Sprache.t("Windows messages at the bottom right", "Windows-Meldungen unten rechts"), true,
            Sprache.t("Errors and 'done' as a message in the notification area -- for people without Discord.",
                    "Fehler und 'fertig' als Meldung im Infobereich -- fuer Leute ohne Discord."));

    // Tastenkuerzel
    private final List<Runnable> kuerzelNachziehen = new ArrayList<>();
    private final Timer takt;

    // Einstellungen (Autor 24.10., Ideenliste F24-F27): Suche, Einfach/Alle, aktuelle Werte, Rueckgaengig, Standard
    /** Eine Karte der Einstellungen mit ihren Zeilen -- fuer das Suchfeld und die Ansicht "Einfach". */
    private static final class Bereich {
        final String titel;
        final JComponent karte;
        final List<JComponent> zeilen;
        boolean einfach;
        Component abstand;   // der Zwischenraum vor der Karte (verschwindet mit ihr)

        Bereich(String titel, JComponent karte, List<JComponent> zeilen) {
            this.titel = titel;
            this.karte = karte;
            this.zeilen = zeilen;
        }
    }

    /** Das Schildchen "jetzt: ..." hinter einer Zeile und der Wert, den es zeigt. */
    private record WertAnzeige(JLabel label, Einstellungswerte.Wert wert) {
    }

    /** Eine Aenderung, die sich zuruecknehmen laesst: Text und die Befehle, die den alten Stand wiederherstellen. */
    private record Rueckgang(String text, List<String> befehle) {
    }

    private final List<Bereich> bereiche = new ArrayList<>();
    private final List<WertAnzeige> wertAnzeigen = new ArrayList<>();
    private final java.util.Deque<Rueckgang> rueckgaenge = new java.util.ArrayDeque<>();
    private JTextField einstellungsSuche;
    private JComboBox<String> einstellungsAnsicht;
    private JLabel suchHinweis;
    private JButton rueckgangKnopf;
    private JLabel rueckgangStand;
    private static final String ANSICHT_EINFACH = "einstellungen.einfach";
    /** Die acht Karten der Ansicht "Einfach" -- nach ihrer Stelle in der Liste (siehe einstellungenReiter). */
    private static final java.util.Set<Integer> EINFACH_KARTEN = java.util.Set.of(0, 1, 2, 3, 6, 7, 8, 14);
    private static final String WERT = "einstellungswert";
    /** Autor 24.10. (F28): die Zeitleiste des Tages auf der Steuerung. */
    private final JTextArea zeitleiste = Holzstil.lesebereich();

    Steuerfenster(Steuerprogramm steuer) {
        this.steuer = steuer;

        setTitle(Sprache.t("Hustle Castle Bot – Control", "Hustle Castle Bot – Steuerung"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);   // nur das Fenster; der Bot laeuft weiter
            }
        });
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);

        JPanel kopf = Holzstil.kopfleiste(Sprache.t("Hustle Castle Bot – Control", "Hustle Castle Bot – Steuerung"), kopfRechts());
        kopf.add(zustand, BorderLayout.SOUTH);

        reiter.addTab(Sprache.t("Control", "Steuerung"), steuerungReiter());
        reiter.addTab(Sprache.t("Windows", "Anzeigen"), anzeigenReiter());
        reiter.addTab(Sprache.t("Settings", "Einstellungen"), einstellungenReiter());
        reiter.addTab(Sprache.t("Shortcuts", "Tastenkürzel"), kuerzelReiter());
        reiter.addTab(Sprache.t("Profiles", "Profile"), profileReiter());
        reiter.addTab(Sprache.t("Tools", "Werkzeuge"), werkzeugeReiter());
        reiter.addTab("Support", supportReiter());

        setLayout(new BorderLayout());
        add(kopf, BorderLayout.NORTH);
        add(reiter, BorderLayout.CENTER);
        setGlassPane(markierung);
        setMinimumSize(new Dimension(560, 420));
        // Autor, 11.10.: "Das Fenster ist zu gross, ueberdeckt das Spiel und soll immer hinter dem Spiel starten" --
        // seine Wahl ("so waere es perfekt"): links oben neben dem Spiel, 765 x 800. Verschoben merkt es sich die Lage.
        Holzstil.lageMerken(this, steuer.einst, "steuerfenster", new Rectangle(0, 0, 765, 800));

        // Autor 24.10. (F26): beim Oeffnen des Reiters Einstellungen stehen die aktuellen Werte sofort da
        reiter.addChangeListener(e -> {
            if (reiter.getSelectedIndex() == REITER_EINSTELLUNGEN) werteAktualisieren();
            if (reiter.getSelectedIndex() == REITER_STEUERUNG) zeitleisteAktualisieren();
        });

        takt = new Timer(1000, e -> aktualisieren());
        takt.start();
        aktualisieren();
    }

    @Override
    public void abmelden() {
        takt.stop();
    }

    // ------------------------------------------------------------------
    //  Autor 24.10. (Ideenliste F32): "Wo finde ich ...?"
    // ------------------------------------------------------------------

    private JTextField hilfeFeld;

    /** Rechts im Kopf: das Suchfeld "Wo finde ich ...?" und die Zustands-Pille. */
    private JComponent kopfRechts() {
        hilfeFeld = Hilfesuche.feld(Sprache.t("Where do I find …?", "Wo finde ich …?"), 14);
        hilfeFeld.setToolTipText(Sprache.t("Type a word (e.g. arena, food, clan, pictures) and press Enter: Pip shows you where it is.",
                "Tipp ein Wort (z. B. Arena, Nahrung, Clan, Bilder) und drück Enter: Pip zeigt dir, wo es steht."));
        hilfeFeld.addActionListener(e -> hilfeSuchen());
        hilfeFeld.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    hilfeFeld.setText("");
                    markieren(null);
                }
            }
        });
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        p.add(hilfeFeld);
        p.add(pille);
        return p;
    }

    /** Enter im Suchfeld: ein Treffer geht gleich hin, mehrere stehen zur Auswahl. */
    private void hilfeSuchen() {
        String eingabe = hilfeFeld.getText();
        if (Einstellungssuche.normal(eingabe).isEmpty()) return;
        List<Hilfesuche.Treffer> treffer = Hilfesuche.suche(eingabe);
        if (treffer.size() == 1) {
            hilfeZeigen(treffer.get(0));
            return;
        }
        JPopupMenu menue = new JPopupMenu();
        menue.setFocusable(false);
        if (treffer.isEmpty()) {
            JMenuItem nichts = new JMenuItem(Sprache.t("Nothing found – try another word (e.g. arena, food, start, pictures).",
                    "Nichts gefunden – versuch ein anderes Wort (z. B. Arena, Nahrung, Start, Bilder)."));
            nichts.setEnabled(false);
            menue.add(nichts);
        }
        for (Hilfesuche.Treffer t : treffer) {
            JMenuItem i = new JMenuItem(t.anzeige());
            i.addActionListener(e -> hilfeZeigen(t));
            menue.add(i);
        }
        menue.show(hilfeFeld, 0, hilfeFeld.getHeight());
    }

    /** Zum Reiter gehen und die Stelle mit Pips leuchtendem Rahmen markieren (nach 6 Sekunden geht er wieder aus). */
    void hilfeZeigen(Hilfesuche.Treffer t) {
        reiterWaehlen(t.reiter());
        JComponent z = ziel(t.ziel());
        if (z == null) return;
        // Eine Einstellungs-Karte, die die Ansicht "Einfach" oder eine Suche gerade versteckt: erst "Alle" zeigen.
        boolean verborgen = false;
        for (Component c = z; c != null && c != this; c = c.getParent()) if (!c.isVisible()) verborgen = true;
        if (verborgen && einstellungsAnsicht != null && einstellungsSuche != null) {
            einstellungsSuche.setText("");
            einstellungsAnsicht.setSelectedIndex(1);
        }
        SwingUtilities.invokeLater(() -> {
            markieren(z);
            Timer aus = new Timer(6000, e -> {
                if (markierung.ziel == z) markieren(null);
            });
            aus.setRepeats(false);
            aus.start();
        });
    }

    int gewaehlterReiter() {
        return reiter.getSelectedIndex();
    }

    void reiterWaehlen(int i) {
        if (i >= 0 && i < reiter.getTabCount()) reiter.setSelectedIndex(i);
    }

    /** Der Inhalt eines Reiters mit Luft rundherum -- gleich bei allen. */
    private static JPanel reiterInhalt(LayoutManager layout) {
        return Holzstil.grund(layout, Holzstil.LUFT);
    }

    /** Karten untereinander, oben buendig, nicht ueber die ganze Hoehe gezogen. */
    private static JPanel untereinander(JComponent... karten) {
        return untereinander(Holzstil.LUFT, karten);
    }

    private static JPanel untereinander(int luft, JComponent... karten) {
        JPanel p = Holzstil.flaeche(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (int i = 0; i < karten.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(luft));
            karten[i].setAlignmentX(LEFT_ALIGNMENT);
            p.add(karten[i]);
        }
        return p;
    }

    // ==================================================================
    //  Reiter Steuerung
    // ==================================================================

    private JComponent steuerungReiter() {
        JPanel p = reiterInhalt(new BorderLayout(0, Holzstil.LUFT));

        // Autor 14.10.: EIN Start-Knopf. Stigmas und Profile stehen mit in der Modus-Liste; Anhalten, Pause und
        // Beenden gehen nur per Taste (beim Spielen gehoert die Maus dem Bot). Kreislauf bleibt als eigener Knopf.

        // --- Starten ---------------------------------------------------
        JPanel start = Holzstil.karte(Sprache.t("Start", "Starten"), new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        modusListeNeu();
        modus.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { modusListeNeu(); }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        modus.setToolTipText(Sprache.t("What the bot should play -- also stigmas (with titan) and your profiles.",
                "Was der Bot spielen soll -- auch Stigmas (mit Titan) und deine Profile."));
        titan.setToolTipText(Sprache.t("Only for stigmas: at which titan.", "Nur fuer Stigmas: bei welchem Titan."));
        zeile(start, c, 0, Sprache.t("Mode", "Modus"), modus, Sprache.t("Number", "Anzahl"), anzahl);
        zeile(start, c, 1, Sprache.t("Route (cathedral)", "Weg (Kathedrale)"), weg, Sprache.t("Titan (stigmas)", "Titan (Stigmas)"), titan);
        c.gridy = 2; c.gridx = 0; c.gridwidth = 1;
        start.add(Holzstil.beschriftung(Sprache.t("Friend (dungeon)", "Mitspieler (Verlies)")), c);
        c.gridx = 1; c.gridwidth = 3;
        freund.setToolTipText(Sprache.t("Click in: list of your teammates (several possible). Empty = random players.",
                "Reinklicken: Liste deiner Mitspieler (mehrere moeglich). Leer = zufaellige Spieler."));
        freund.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (freund.isEnabled()) mitspielerListe();
            }
        });
        start.add(freund, c);
        // Autor 23.10.: "Im Bot-Fenster einstellen, bis zu welchem Ziel-Level er erst spielt, und dann die Farm-Liste."
        portalBis.setToolTipText(Sprache.t("Portal: play level by level up to this level (empty = no climbing).",
                "Portal: Level fuer Level bis zu diesem Level spielen (leer = kein Aufstieg)."));
        String levelTipp = Sprache.t("Level to farm in auto battle (empty = none).", "Level zum Farmen im Autokampf (leer = keins).");
        String nahrungTipp = Sprache.t("At most this much food for the level, e.g. 2 mio, 500k, 1.5 mio.",
                "Hoechstens so viel Nahrung fuer das Level, z. B. 2 Mio, 500k, 1,5 Mio.");
        portalL1.setToolTipText(levelTipp);
        portalL2.setToolTipText(levelTipp);
        portalN1.setToolTipText(nahrungTipp);
        portalN2.setToolTipText(nahrungTipp);
        // Zwei Zeilen untereinander -- nebeneinander war das zweite Level im Fenster abgeschnitten (23.10., Bild).
        JPanel farmFelder = new JPanel(new java.awt.GridLayout(2, 4, 6, 3));
        farmFelder.setOpaque(false);
        farmFelder.add(Holzstil.beschriftung(Sprache.t("1st level", "1. Level")));
        farmFelder.add(portalL1);
        farmFelder.add(Holzstil.beschriftung(Sprache.t("max. food", "max. Nahrung")));
        farmFelder.add(portalN1);
        farmFelder.add(Holzstil.beschriftung(Sprache.t("2nd level", "2. Level")));
        farmFelder.add(portalL2);
        farmFelder.add(Holzstil.beschriftung(Sprache.t("max. food", "max. Nahrung")));
        farmFelder.add(portalN2);
        zeile(start, c, 3, Sprache.t("Portal: up to level", "Portal: bis Level"), portalBis,
                Sprache.t("then farm", "danach farmen"), farmFelder);
        c.gridy = 4; c.gridx = 0; c.gridwidth = 4;
        // Freunde-Bot (Code-Pruefung 16.10.): "PC danach aus" (Wort pcaus) kennt die Freunde-DiscordBridge nicht --
        // beim Verlies hielt sie es fuer einen Freundesnamen und startete gar nichts. Darum ohne diesen Haken.
        pcAus.setVisible(false);

        JButton los = Holzstil.knopf("Start", Sprache.t("Starts what is chosen above.", "Startet, was oben gewaehlt ist."), Holzstil.GUT, Symbol.Art.START);
        los.addActionListener(e -> starten());
        JButton danach = Holzstil.knopf(Sprache.t("Add after", "Danach anhängen"),
                Sprache.t("Puts the mode after the running one (like !after).", "Haengt den Modus hinter den laufenden (wie !after)."), Symbol.Art.LISTE);
        danach.addActionListener(e -> {
            if (modus.getSelectedItem() instanceof Befehlsbau.Modus) {
                mitspielerMerken();
                steuer.senden(startBefehl(true), Sprache.t("After button", "Knopf Danach"));
                danachGemerkt.add(startBefehl(true));   // F35: fuer "Als Profil speichern"
            }
        });
        // Autor 24.10. (F35): Modus, Anzahl und das, was man mit "Danach anhaengen" geplant hat, als Profil speichern --
        // ohne eine Befehlszeile zu schreiben.
        JButton alsProfil = Holzstil.knopf(Sprache.t("Save as profile", "Als Profil speichern"),
                Sprache.t("Saves what is chosen above (and what you added with \"Add after\") as a profile with a name of your choice. "
                        + "It then appears in the mode list.",
                        "Speichert, was oben gewählt ist (und was du mit „Danach anhängen“ geplant hast), als Profil mit einem Namen deiner Wahl. "
                        + "Es steht danach in der Modus-Liste."), Symbol.Art.HAKEN);
        alsProfil.addActionListener(e -> alsProfilSpeichern());
        JButton ablauf = Holzstil.knopf(Sprache.t("Loop", "Kreislauf"),
                Sprache.t("Event loop: cathedral and event, lighthouse, colosseum and clan war on the side.",
                        "Event-Ablauf: Kathedrale und Event, nebenbei Leuchtturm, Kolosseum und Clan-Krieg."), Symbol.Art.KREISLAUF);
        ablauf.addActionListener(e -> steuer.senden(Befehlsbau.ABLAUF, Sprache.t("Loop button", "Knopf Kreislauf")));
        // Ben 17.10.: "einfach einen Loop-Knopf, nur Kolosseum, Leuchtturm, Kathedrale, Clan-Krieg und Titanen-Turm,
        // aber kein Event" -- den Ablauf gab es schon (!ablauf ohne), nur keinen Knopf.
        JButton ablaufOhne = Holzstil.knopf(Sprache.t("Loop (no event)", "Kreislauf ohne Event"),
                Sprache.t("Cathedral without end, NO event. On the side: lighthouse, colosseum, clan war and titan tower (as switched on in the settings).",
                        "Kathedrale ohne Ende, OHNE Event. Nebenbei Leuchtturm, Kolosseum, Clan-Krieg und Titanen-Turm (wie in den Einstellungen eingeschaltet)."),
                Symbol.Art.KREISLAUF);
        ablaufOhne.addActionListener(e -> steuer.senden(Befehlsbau.ABLAUF_OHNE, Sprache.t("Loop (no event) button", "Knopf Kreislauf ohne Event")));
        // Autor 24.10.: zwei Reihen -- mit dem Knopf "Als Profil speichern" waere eine Reihe breiter als das Fenster
        // (die Karte wuerde dann abgeschnitten, sobald ein Rollbalken dazukommt).
        JPanel startReihe1 = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        JPanel startReihe2 = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        JPanel startKnoepfe = Holzstil.flaeche(new GridLayout(0, 1, 0, Holzstil.LUFT_KLEIN));
        startKnoepfe.add(startReihe1);
        startKnoepfe.add(startReihe2);
        startReihe1.add(los);
        startReihe1.add(danach);
        startReihe1.add(alsProfil);
        startReihe2.add(ablauf);
        startReihe2.add(ablaufOhne);
        // Autor 14.10.: "Danach anhaengen" und "PC danach aus" verstaendlicher -- mit Anleitung.
        JButton hilfe = Holzstil.kleinerKnopf(Sprache.t("Guide", "Anleitung"),
                Sprache.t("How Start, Add after and Loop work.", "So funktionieren Start, Danach anhängen und Kreislauf."), Symbol.Art.LUPE);
        hilfe.addActionListener(e -> JOptionPane.showMessageDialog(this, Sprache.t("<html><div style='width:430px'>"
                + "<b>Start</b> – plays what is set under <b>Mode</b> above (how often: <b>Number</b>). Stigmas and profiles "
                + "are in this list too.<br><br>"
                + "<b>Add after</b> – plans a <b>second</b> mode that starts by itself when the first one is done. "
                + "How it works:<br>"
                + "1. Choose a mode (e.g. Cathedral, 50) and press <b>Start</b>.<br>"
                + "2. Choose the next mode (e.g. Dungeon, 10) and press <b>Add after</b>.<br>"
                + "3. Done – first 50 cathedrals, then 10 dungeons. As many in a row as you like.<br><br>"
                + "<b>Loop</b> – your fixed routine without end: lighthouse, colosseum, cathedral, event, clan war …<br><br>"
                + "<b>Loop (no event)</b> – the same, but WITHOUT the event: cathedral without end, lighthouse, colosseum, clan war "
                + "and titan tower on the side.<br><br>"
                + "<b>Stop:</b> emergency key (F9) · <b>Pause / Resume:</b> F10 · If you move the mouse while the bot plays, "
                + "it stops by itself (switch it off in Settings → Mouse).</div></html>",
                "<html><div style='width:430px'>"
                + "<b>Start</b> – spielt, was oben bei <b>Modus</b> steht (wie oft: <b>Anzahl</b>). Auch Stigmas und Profile "
                + "stehen in dieser Liste.<br><br>"
                + "<b>Danach anhängen</b> – plant einen <b>zweiten</b> Modus, der von selbst startet, wenn der erste fertig ist. "
                + "So geht's:<br>"
                + "1. Modus wählen (z. B. Kathedrale, 50) und <b>Start</b> drücken.<br>"
                + "2. Den nächsten Modus wählen (z. B. Verlies, 10) und <b>Danach anhängen</b> drücken.<br>"
                + "3. Fertig – erst 50 Kathedralen, dann 10 Verliese. Beliebig viele hintereinander.<br><br>"
                + "<b>Kreislauf</b> – dein fester Ablauf ohne Ende: Leuchtturm, Kolosseum, Kathedrale, Event, Clan-Krieg …<br><br>"
                + "<b>Kreislauf ohne Event</b> – dasselbe, aber OHNE Event: Kathedrale ohne Ende, nebenbei Leuchtturm, Kolosseum, "
                + "Clan-Krieg und Titanen-Turm.<br><br>"
                + "<b>Anhalten:</b> Not-Aus-Taste (F9) · <b>Pause / Weiter:</b> F10 · Bewegst du die Maus, während der Bot spielt, "
                + "stoppt er von selbst (abschaltbar in Einstellungen → Maus).</div></html>"),
                Sprache.t("Guide: Start", "Anleitung: Starten"), JOptionPane.INFORMATION_MESSAGE));
        startReihe2.add(hilfe);
        startReihe2.add(Box.createHorizontalStrut(Holzstil.LUFT_KLEIN));
        startReihe2.add(vorschau);
        c.gridy = 5;
        c.insets = new Insets(6, 4, 3, 10);
        start.add(startKnoepfe, c);
        c.gridy = 6;
        c.insets = new Insets(3, 4, 3, 10);
        JLabel tasten = Holzstil.hinweis(Sprache.t("<html><b>Stop:</b> emergency key (default F9) · <b>Pause / Resume:</b> F10 · "
                + "<b>Quit bot:</b> " + kuerzelText(Tastenkuerzel.Aktion.BEENDEN) + " — change it in the tab Shortcuts.</html>",
                "<html><b>Anhalten:</b> Not-Aus-Taste (Standard F9) · <b>Pause / Weiter:</b> F10 · "
                + "<b>Bot beenden:</b> " + kuerzelText(Tastenkuerzel.Aktion.BEENDEN) + " — ändern im Reiter Tastenkürzel.</html>"));
        start.add(tasten, c);
        Holzstil.fueller(start, c, 4, 0);

        Runnable vorschauNeu = () -> {
            Object w = modus.getSelectedItem();
            boolean istModus = w instanceof Befehlsbau.Modus;
            weg.setEnabled(w == Befehlsbau.Modus.KATHEDRALE);
            freund.setEnabled(w == Befehlsbau.Modus.VERLIES);
            portalBis.setEnabled(w == Befehlsbau.Modus.PORTAL);
            for (JTextField f : new JTextField[]{portalL1, portalN1, portalL2, portalN2}) f.setEnabled(w == Befehlsbau.Modus.PORTAL);
            titan.setEnabled(w instanceof Befehlsbau.Stigma s && s != Befehlsbau.Stigma.LOESCHEN);
            anzahl.setEnabled(istModus && ((Befehlsbau.Modus) w).mitAnzahl);
            danach.setEnabled(istModus);
            pcAus.setEnabled(istModus);
            vorschau.setText(w instanceof ProfilWahl pw ? Sprache.t("runs the profile “" + pw.name() + "”", "führt das Profil „" + pw.name() + "“ aus")
                    : w instanceof Befehlsbau.Stigma s ? s.befehl(titanWort(), false) : startBefehl(false));
        };
        modus.addActionListener(e -> vorschauNeu.run());
        weg.addActionListener(e -> vorschauNeu.run());
        titan.addActionListener(e -> vorschauNeu.run());
        pcAus.addActionListener(e -> vorschauNeu.run());
        anzahl.getDocument().addDocumentListener(dokument(vorschauNeu));
        portalBis.getDocument().addDocumentListener(dokument(vorschauNeu));
        for (JTextField f : new JTextField[]{portalL1, portalN1, portalL2, portalN2}) f.getDocument().addDocumentListener(dokument(vorschauNeu));
        freund.getDocument().addDocumentListener(dokument(vorschauNeu));
        vorschauNeu.run();

        // --- Schnell nachsehen und freier Befehl -----------------------
        JPanel unten = Holzstil.karte(Sprache.t("Look up", "Nachsehen"), new BorderLayout(0, Holzstil.LUFT_KLEIN));
        JPanel schnell = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        for (String[] k : new String[][]{{"Status", Befehlsbau.STATUS}, {Sprache.t("Statistics", "Statistik"), Befehlsbau.STATS},
                {Sprache.t("To the castle", "Zur Burg"), "!burg"},
                {Sprache.t("Screenshot", "Bildschirmfoto"), "!screenshot"}, {Sprache.t("Help", "Hilfe"), Befehlsbau.HILFE}}) {
            JButton b = Holzstil.kleinerKnopf(k[0], Sprache.t("Like " + k[1], "Wie " + k[1]));
            b.addActionListener(e -> steuer.senden(k[1], Sprache.t("Button " + k[0], "Knopf " + k[0])));
            schnell.add(b);
        }
        JButton tutorial = Holzstil.kleinerKnopf("Tutorial (Pip)", Sprache.t("Pip explains the program step by step.", "Pip erklärt das Programm Schritt für Schritt."), Symbol.Art.STERN);
        tutorial.addActionListener(e -> Tutorial.starten(this, steuer));
        schnell.add(tutorial);
        JPanel freiZeile = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        freiZeile.add(Holzstil.beschriftung(Sprache.t("Command like in Discord", "Befehl wie in Discord")));
        frei.setToolTipText(Sprache.t("For example !start cathedral 1000 c or !settings all. Enter sends it.",
                "Zum Beispiel !start cathedral 1000 c oder !settings all. Eingabetaste schickt."));
        JButton senden = Holzstil.kleinerKnopf(Sprache.t("Send", "Senden"), Sprache.t("Sends the command.", "Schickt den Befehl."), Symbol.Art.START);
        Runnable freiSenden = () -> {
            String t = frei.getText().trim();
            if (t.isEmpty()) return;
            if (Befehlsbau.gefaehrlich(t)) steuer.sendenMitNachfrage(t, Sprache.t("Input field", "Eingabefeld"), t);
            else steuer.senden(t, Sprache.t("Input field", "Eingabefeld"));
            frei.setText("");
        };
        senden.addActionListener(e -> freiSenden.run());
        frei.addActionListener(e -> freiSenden.run());
        freiZeile.add(frei);
        freiZeile.add(senden);
        unten.add(schnell, BorderLayout.NORTH);
        unten.add(freiZeile, BorderLayout.SOUTH);

        // Autor, 11.10.: Hinweis, was vor dem Start stimmen muss.
        JLabel vorher = Holzstil.hinweis(Sprache.t("<html><b>Before you start:</b> game in <b>English</b>, <b>HD Graphics Off</b>, "
                + "<b>V Sync Off</b>, scan your team (Settings → Your team → Scan now).<br>"
                + "The bot checks this every 3 days by itself and fixes it.</html>",
                "<html><b>Vor dem Start:</b> Spiel auf <b>English</b>, <b>HD Graphics Off</b>, "
                + "<b>V Sync Off</b>, Team einscannen (Einstellungen → Eigenes Team → Jetzt ansehen).<br>"
                + "Der Bot prüft das alle 3 Tage selbst und stellt es richtig.</html>"));
        vorher.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        JPanel rollInhalt = new Holzstil.Rollflaeche(new BorderLayout());
        // Autor 24.10. (F28): "Als Naechstes" -- Zeitleiste mit den Terminen von Leuchtturm, Kolosseum, Clan-Krieg und Titanen-Turm
        JPanel zeitKarte = Holzstil.karte(Sprache.t("Coming up", "Als Nächstes"), new BorderLayout());
        zeitleiste.setOpaque(false);
        zeitleiste.setMargin(new Insets(0, 0, 0, 0));
        zeitKarte.add(zeitleiste, BorderLayout.CENTER);
        zeitleisteAktualisieren();
        ziele.put("zeitleiste", zeitKarte);
        // Autor 24.10. (F33): Selbstcheck -- Spielfenster, Fenstergroesse, Windows-Skalierung, Virenschutz, Energiesparplan, Lizenz, Bilder
        Ampelkarte ampel = new Ampelkarte(() -> batStarten(de.hustlebot.core.Ordner.bei("Capture-Images.exe").toAbsolutePath(),
                Sprache.t("Capture program", "Aufnahme-Programm")));
        ziele.put("gesundheit", ampel);
        rollInhalt.add(untereinander(Holzstil.LUFT_KLEIN, vorher, zeitKarte, ampel, start, unten), BorderLayout.NORTH);
        for (Object[] z : new Object[][]{{"modus", modus}, {"anzahl", anzahl}, {"weg", weg}, {"titan", titan}, {"freund", freund},
                {"pcaus", pcAus}, {"start", los}, {"danach", danach}, {"kreislauf", ablauf}, {"tasten", tasten}, {"nachsehen", unten},
                {"portal", farmFelder}, {"kreislaufOhne", ablaufOhne}, {"befehl", freiZeile}}) {   // Tutorial 24.10.
            ziele.put((String) z[0], (JComponent) z[1]);
        }
        p.add(Holzstil.rollenOhneRahmen(rollInhalt), BorderLayout.CENTER);
        return p;
    }

    /** Ein Profil in der Modus-Liste (Autor 14.10.: ein Start-Knopf fuer alles). */
    record ProfilWahl(String name) {
        @Override public String toString() { return Sprache.t("Profile: " + name, "Profil: " + name); }
    }

    /** Modi, die drei Stigma-Auftraege (Autor 14.10.: loeschen, Stats filtern, leveln) und die Profile. */
    private void modusListeNeu() {
        Object vorher = modus.getSelectedItem();
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
        for (Befehlsbau.Modus mo : Befehlsbau.Modus.values()) {
            m.addElement(mo);
        }
        m.addElement(Befehlsbau.Stigma.LOESCHEN);
        m.addElement(Befehlsbau.Stigma.LEVELN);
        m.addElement(Befehlsbau.Stigma.HUNDERT);
        for (String n : steuer.profile().namen()) m.addElement(new ProfilWahl(n));
        modus.setModel(m);
        if (vorher != null) modus.setSelectedItem(vorher);
        if (modus.getSelectedIndex() < 0) modus.setSelectedIndex(0);
    }

    /** Der eine Start-Knopf: Modus, Stigma-Auftrag oder Profil. */
    private void starten() {
        Object w = modus.getSelectedItem();
        danachGemerkt.clear();   // ein neuer Start beginnt eine neue Kette
        if (w instanceof Befehlsbau.Modus) {
            mitspielerMerken();
            steuer.senden(startBefehl(false), Sprache.t("Start button", "Knopf Start"));
        } else if (w instanceof Befehlsbau.Stigma art) {
            String t = String.valueOf(titan.getSelectedItem());
            String befehl = art.befehl(titanWort(), false);
            Runnable schicken = () -> steuer.senden(befehl, Sprache.t("Start button", "Knopf Start"));
            // Code-Pruefung 16.10.: alle Sets angehakt = nichts behalten -- dann verlangt die Bruecke ausdruecklich "alles".
            if (art == Befehlsbau.Stigma.LOESCHEN) Stigmafenster.loeschen(this, () -> steuer.senden(
                    de.hustlebot.core.Stigmaplan.behalten().isEmpty() ? befehl + " alles" : befehl, "Knopf Start"));
            // Code-Pruefung 16.10.: die alte Freunde-Fassung hatte "Probelauf" als Standard -- ohne ihn verbraucht ein
            // Klick sofort Strahlen und Stones of Creation. Darum einmal nachfragen.
            else if ((art == Befehlsbau.Stigma.HUNDERT || art == Befehlsbau.Stigma.LEVELN) && !steuer.fragen(
                    Sprache.t("This really levels stigmas and uses up rays and Stones of Creation.\n\nStart now?",
                            "Das levelt Stigmas wirklich und verbraucht Strahlen und Stones of Creation.\n\nJetzt starten?"),
                    art.anzeige)) {
                return;
            }
            else schicken.run();
        } else if (w instanceof ProfilWahl pw) {
            steuer.profilAnwenden(pw.name(), Sprache.t("Start button", "Knopf Start"));
        }
    }

    /** Autor 24.10. (F35): die "Danach anhaengen"-Befehle seit dem letzten Start -- die Kette, die das Programm selbst geplant hat. */
    private final List<String> danachGemerkt = new ArrayList<>();
    private DefaultListModel<String> profilModell;

    /**
     * Autor 24.10. (F35): speichert die jetzige Auswahl (Start-Befehl) samt den "Danach"-Befehlen als Profil. Das Fenster zeigt die
     * Befehle zum Nachsehen (und Aendern); der Name ist frei. Ein Profil gleichen Namens wird erst nach Rueckfrage ersetzt.
     */
    private void alsProfilSpeichern() {
        List<String> zeilen = new ArrayList<>();
        Object w = modus.getSelectedItem();
        if (w instanceof Befehlsbau.Modus) {
            zeilen.add(startBefehl(false));
        } else if (w instanceof Befehlsbau.Stigma art) {
            zeilen.add(art.befehl(titanWort(), false));
        } else if (w instanceof ProfilWahl) {
            JOptionPane.showMessageDialog(this, Sprache.t("This is already a profile. Choose a mode (and a number) first.",
                    "Das ist schon ein Profil. Wähle erst einen Modus (und eine Anzahl)."), Sprache.t("Save as profile", "Als Profil speichern"),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        zeilen.addAll(danachGemerkt);
        JTextField name = Holzstil.feld("", 20);
        JTextArea befehle = Holzstil.lesebereich();
        befehle.setEditable(true);
        befehle.setFont(Holzstil.FEST);
        befehle.setLineWrap(false);
        befehle.setText(String.join("\n", zeilen));
        JPanel p = Holzstil.grund(new BorderLayout(0, Holzstil.LUFT_KLEIN), Holzstil.LUFT);
        JPanel oben = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        oben.add(Holzstil.beschriftung(Sprache.t("Name of the profile", "Name des Profils")));
        oben.add(name);
        p.add(oben, BorderLayout.NORTH);
        JScrollPane rollen = Holzstil.rollen(befehle);
        rollen.setPreferredSize(new Dimension(420, 120));
        p.add(rollen, BorderLayout.CENTER);
        p.add(Holzstil.hinweis(Sprache.t("<html>One command per line -- first the start, then what follows. You can change the lines.</html>",
                "<html>Eine Zeile je Befehl -- zuerst der Start, danach das, was folgt. Du kannst die Zeilen ändern.</html>")), BorderLayout.SOUTH);
        int ok = JOptionPane.showConfirmDialog(this, p, Sprache.t("Save as profile", "Als Profil speichern"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String n = name.getText().trim();
        List<String> neu = new ArrayList<>();
        for (String z : befehle.getText().split("\n")) if (!z.isBlank()) neu.add(z.strip());
        if (n.isEmpty() || neu.isEmpty()) {
            JOptionPane.showMessageDialog(this, Sprache.t("Please enter a name and at least one command.", "Bitte einen Namen und mindestens einen Befehl eintragen."),
                    Sprache.t("Save as profile", "Als Profil speichern"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (steuer.profile.namen().contains(n) && !steuer.fragen(Sprache.t("A profile called \"" + n + "\" exists. Replace it?",
                "Ein Profil „" + n + "“ gibt es schon. Ersetzen?"), Sprache.t("Save as profile", "Als Profil speichern"))) return;
        if (!steuer.profile.setzen(n, neu)) return;
        modusListeNeu();
        if (profilModell != null && !profilModell.contains(n)) profilModell.addElement(n);
        vorschau.setText(Sprache.t("Profile saved: " + n + " -- now in the mode list.", "Profil gespeichert: " + n + " -- steht jetzt in der Modus-Liste."));
    }

    /** Das Titan-Wort fuer den Befehl ("" = Last Guardian). */
    private String titanWort() {
        String t = String.valueOf(titan.getSelectedItem()).replaceAll("\\s*\\((Tank|Archer)\\)$", "");
        return t.startsWith("Last") ? "" : t.startsWith("Keeper") ? "keeper" : t.startsWith("Thunder") ? "thunder"
                : t.toLowerCase(java.util.Locale.ROOT);
    }

    private static final String MITSPIELER = "mitspieler.namen";

    /** Alle Mitspieler, die schon einmal eingetragen wurden (Autor 14.10.: "beim Reinklicken alle Namen"). */
    private List<String> bekannteMitspieler() {
        List<String> l = new ArrayList<>();
        for (String n : steuer.einst.text(MITSPIELER, "").split(",")) {
            String t = n.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty() && !l.contains(t)) l.add(t);
        }
        return l;
    }

    private static java.util.LinkedHashSet<String> namenAus(String feld) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        for (String n : (feld == null ? "" : feld).split("[,\\s]+")) {
            String t = n.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    /** Neue Namen aus dem Feld in die Liste aufnehmen. */
    private void mitspielerMerken() {
        if (modus.getSelectedItem() != Befehlsbau.Modus.VERLIES) return;
        List<String> l = bekannteMitspieler();
        boolean neu = false;
        for (String n : namenAus(freund.getText())) {
            if (!l.contains(n)) {
                l.add(n);
                neu = true;
            }
        }
        if (neu) steuer.einst.setzen(MITSPIELER, String.join(",", l));
    }

    /** Beim Reinklicken: alle bekannten Mitspieler zum Anhaken, dazu "zufaellig". */
    private void mitspielerListe() {
        JPopupMenu menue = new JPopupMenu();
        menue.setFocusable(false);   // Code-Review 15.10.: sonst nahm die Liste den Fokus, der erste getippte Name ging verloren
        JMenuItem zufall = new JMenuItem(Sprache.t("Random players (field empty)", "Zufällige Spieler (Feld leer)"));
        zufall.addActionListener(e -> freund.setText(""));
        menue.add(zufall);
        List<String> bekannt = bekannteMitspieler();
        menue.addSeparator();
        if (bekannt.isEmpty()) {
            JMenuItem leer = new JMenuItem(Sprache.t("No names yet – type them in once and start, then they show up here.",
                    "Noch keine Namen – einmal eintippen und starten, dann stehen sie hier."));
            leer.setEnabled(false);
            menue.add(leer);
        }
        for (String n : bekannt) {
            JCheckBoxMenuItem i = new JCheckBoxMenuItem(n, namenAus(freund.getText()).contains(n));
            i.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true);   // mehrere nacheinander anhaken
            i.addActionListener(e -> {
                java.util.LinkedHashSet<String> s = namenAus(freund.getText());
                if (i.isSelected()) s.add(n); else s.remove(n);
                freund.setText(String.join(",", s));
            });
            menue.add(i);
        }
        menue.show(freund, 0, freund.getHeight());
    }

    private String startBefehl(boolean danach) {
        if (!(modus.getSelectedItem() instanceof Befehlsbau.Modus m)) return "";
        int n = Befehlsbau.zahl(anzahl.getText(), 1);
        String w = weg.getSelectedIndex() == 1 ? "b" : "c";
        if (danach) return Befehlsbau.danach(m, n, w, null, freund.getText());
        List<String> extras = new ArrayList<>();
        if (m == Befehlsbau.Modus.PORTAL) extras.addAll(Befehlsbau.portalPlan(portalBis.getText(),
                Befehlsbau.portalFarm(portalL1.getText(), portalN1.getText(), portalL2.getText(), portalN2.getText())));
        return Befehlsbau.start(m, n, w, null, freund.getText(), extras);
    }

    private static void zeile(JPanel p, GridBagConstraints c, int y, String t1, JComponent f1, String t2, JComponent f2) {
        c.gridy = y; c.gridwidth = 1;
        c.gridx = 0; p.add(Holzstil.beschriftung(t1), c);
        c.gridx = 1; p.add(f1, c);
        c.gridx = 2; p.add(Holzstil.beschriftung(t2), c);
        c.gridx = 3; p.add(f2, c);
    }

    private static javax.swing.event.DocumentListener dokument(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        };
    }

    /** Eine Meldung als Karte: Farbstreifen links, Titel, Uhrzeit rechts, Text, Knoepfe bei Rueckfragen. */
    static final class Meldungskarte extends JPanel {
        private final Color streifen;

        Meldungskarte(Color streifen) {
            super(new BorderLayout(0, 4));
            this.streifen = streifen;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Holzstil.weich(g);
            int w = getWidth(), h = getHeight();
            g2.setColor(Holzstil.HELL);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g2.setColor(Holzstil.LINIE);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g2.setColor(streifen);
            g2.fillRoundRect(0, 0, 5, h - 1, 5, 5);
            g2.fillRect(3, 1, 2, h - 3);
            g2.dispose();
        }
    }

    /**
     * Baut eine Karte (seit 14.10. fuer das Antworten-Fenster -- der Reiter Meldungen ist weg, Autor).
     * knopf bekommt den Befehl eines Knopfs in der Meldung.
     */
    static JComponent karteBauen(Meldung m, Consumer<String> knopf) {
        Color farbe = Holzstil.kartenfarbe(m.farbe());
        Meldungskarte k = new Meldungskarte(farbe);
        JPanel kopf = Holzstil.flaeche(new BorderLayout(Holzstil.LUFT, 0));
        JLabel titel = new JLabel(m.titel().isEmpty() ? Sprache.t("Message", "Meldung") : m.titelLesbar());
        titel.setFont(Holzstil.KNOPF);
        titel.setForeground(farbe);
        JLabel uhr = Holzstil.hinweis(m.uhrzeit());
        kopf.add(titel, BorderLayout.CENTER);
        kopf.add(uhr, BorderLayout.EAST);
        k.add(kopf, BorderLayout.NORTH);
        if (!m.text().isEmpty()) {
            JTextArea t = Holzstil.lesebereich();
            t.setText(m.textLesbar());
            t.setMargin(new Insets(0, 0, 0, 0));
            t.setOpaque(false);
            k.add(t, BorderLayout.CENTER);
        }
        // Autor 24.10. (F36): Warnungen und Fehler, zu denen es einen Alltags-Hinweis gibt, bekommen "Was heisst das?"
        Warnhilfe.Hilfe hilfe = m.farbe() == DiscordClient.FARBE_WARNUNG || m.farbe() == DiscordClient.FARBE_FEHLER
                ? Warnhilfe.finde(m.titelLesbar() + " " + m.textLesbar()) : null;
        if (hilfe != null && !m.mitKnoepfen()) {
            JPanel kn = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
            kn.add(wasHeisstDas(k, m, hilfe));
            k.add(kn, BorderLayout.SOUTH);
        }
        if (m.mitKnoepfen()) {
            JPanel kn = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
            if (hilfe != null) kn.add(wasHeisstDas(k, m, hilfe));
            for (String[] paar : m.knoepfe()) {
                // Reine Discord-Formular-Knoepfe (setformular:..., startformular) gehen hier nicht (Review 15.10.).
                if (paar[1] == null || !paar[1].startsWith("!")) continue;
                JButton b = Holzstil.kleinerKnopf(paar[0], paar[1]);
                b.addActionListener(e -> {
                    knopf.accept(paar[1]);
                    for (Component c : kn.getComponents()) c.setEnabled(false);
                });
                kn.add(b);
            }
            k.add(kn, BorderLayout.SOUTH);
        }
        k.setMaximumSize(new Dimension(Integer.MAX_VALUE, k.getPreferredSize().height + 400));
        k.setAlignmentX(LEFT_ALIGNMENT);
        return k;
    }

    /** Der kleine Knopf "Was heisst das?" an einer Warn- oder Fehlerkarte. */
    private static JButton wasHeisstDas(Component eltern, Meldung m, Warnhilfe.Hilfe h) {
        JButton b = Holzstil.kleinerKnopf(Sprache.t("What does this mean?", "Was heißt das?"),
                Sprache.t("Explains this message in plain words.", "Erklärt diese Meldung in einfachen Worten."), Symbol.Art.LUPE);
        b.addActionListener(e -> JOptionPane.showMessageDialog(eltern, Warnhilfe.alsHtml(m.titelLesbar() + ": " + m.textLesbar(), h),
                Sprache.t("What does this mean?", "Was heißt das?"), JOptionPane.INFORMATION_MESSAGE));
        return b;
    }

    // ==================================================================
    //  Reiter Anzeigen
    // ==================================================================

    private JComponent anzeigenReiter() {
        JPanel p = reiterInhalt(new BorderLayout());
        JPanel liste = Holzstil.karte(Sprache.t("Show and hide windows (also by shortcut or voice)", "Fenster ein- und ausblenden (auch per Tastenkürzel oder Sprache)"), new GridLayout(0, 2, Holzstil.LUFT, Holzstil.LUFT_KLEIN));
        for (Tastenkuerzel.Aktion a : new Tastenkuerzel.Aktion[]{Tastenkuerzel.Aktion.STATUS, Tastenkuerzel.Aktion.STATISTIK,
                Tastenkuerzel.Aktion.PROTOKOLL, Tastenkuerzel.Aktion.VORRAT, Tastenkuerzel.Aktion.NOTIZBLOCK,
                Tastenkuerzel.Aktion.MINILEISTE}) {
            JCheckBox k = Holzstil.kaestchen(a.anzeige.replace(" auf/zu", "").replace(" show/hide", ""), steuer.offen(a), Sprache.t("Shortcut: " + kuerzelText(a), "Kuerzel: " + kuerzelText(a)));
            k.addActionListener(e -> {
                if (k.isSelected() != steuer.offen(a)) steuer.umschalten(a);
            });
            k.putClientProperty("aktion", a);
            anzeigeKaestchen.add(k);
            liste.add(k);
        }
        JPanel sonst = Holzstil.karte(Sprache.t("Display", "Darstellung"), new GridLayout(0, 1, 0, Holzstil.LUFT_KLEIN));
        JCheckBox obenauf = Holzstil.kaestchen(Sprache.t("Display windows always on top", "Anzeige-Fenster immer obenauf"), steuer.einst.an(Steuereinstellungen.FENSTER_OBENAUF, false),
                Sprache.t("Status, statistics and resources stay in front of the game.", "Status, Statistik und Vorrat bleiben vor dem Spiel."));
        obenauf.addActionListener(e -> steuer.obenaufSetzen(obenauf.isSelected()));
        windowsMeldungen.setSelected(steuer.windowsMeldungenAn());
        windowsMeldungen.addActionListener(e -> steuer.windowsMeldungenSetzen(windowsMeldungen.isSelected()));
        JCheckBox beimStart = Holzstil.kaestchen(Sprache.t("Show the control window when the bot starts", "Steuerprogramm beim Start des Bots zeigen"),
                steuer.einst.an(Steuereinstellungen.STEUERFENSTER_ZEIGEN, true), Sprache.t("Otherwise only via shortcut, notification area or bot window.", "Sonst nur ueber Kuerzel, Infobereich oder Bot-Fenster."));
        beimStart.addActionListener(e -> steuer.einst.setzen(Steuereinstellungen.STEUERFENSTER_ZEIGEN, beimStart.isSelected()));
        sonst.add(obenauf);
        sonst.add(windowsMeldungen);
        sonst.add(beimStart);
        JCheckBox leiste = Holzstil.kaestchen(Sprache.t("Show the log bar at the bottom", "Protokoll-Leiste unten zeigen"), steuer.einst.an(Steuereinstellungen.PROTOKOLLLEISTE, false),
                Sprache.t("The last lines of the log in the free strip below the game.", "Die letzten Zeilen aus dem Protokoll im freien Streifen unter dem Spiel."));
        leiste.addActionListener(e -> steuer.protokollleisteSetzen(leiste.isSelected()));
        sonst.add(leiste);
        sonst.add(aussehenZeile());
        p.add(untereinander(liste, sonst), BorderLayout.NORTH);
        ziele.put("anzeigen", p);   // Tutorial 24.10.
        return p;
    }

    /** Hell / Dunkel (Auftrag 4): die Fenster werden gleich neu gebaut, nichts geht verloren. */
    private JComponent aussehenZeile() {
        JPanel z = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        z.add(Holzstil.beschriftung(Sprache.t("Look", "Aussehen")));
        JComboBox<String> wahl = Holzstil.auswahl(new String[]{Sprache.t("Light", "Hell"), Sprache.t("Dark", "Dunkel")});
        wahl.setSelectedIndex(Holzstil.istDunkel() ? 1 : 0);
        wahl.setToolTipText(Sprache.t("Light is the default. Dark for evenings. Applies to all windows of the control program.",
                "Hell ist die Vorgabe. Dunkel fuer abends. Gilt fuer alle Fenster des Steuerprogramms."));
        wahl.addActionListener(e -> {
            boolean dunkel = wahl.getSelectedIndex() == 1;
            if (dunkel != Holzstil.istDunkel()) steuer.aussehenSetzen(dunkel);
        });
        z.add(wahl);
        z.add(Holzstil.hinweis(Sprache.t("Light is the default, dark for evenings.", "Hell ist die Vorgabe, Dunkel fuer abends.")));
        return z;
    }

    /** Autor 24.10. (F34): Schriftgroesse -- klein / mittel / gross / sehr gross; gilt beim naechsten Start des Bot-Programms. */
    private JComponent schriftZeile() {
        JPanel z = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        z.add(Holzstil.beschriftung(Sprache.t("Text size", "Schriftgröße")));
        JComboBox<String> wahl = Holzstil.auswahl(new String[]{Sprache.t("Small", "Klein"), Sprache.t("Medium", "Mittel"),
                Sprache.t("Large", "Groß"), Sprache.t("Extra large", "Sehr groß")});
        wahl.setSelectedIndex(Holzstil.schriftStufe());
        wahl.setToolTipText(Sprache.t("Makes all texts and buttons of the control program smaller or bigger -- for small or very sharp screens.",
                "Macht alle Texte und Knöpfe des Steuerprogramms kleiner oder größer -- für kleine oder sehr scharfe Bildschirme."));
        JLabel hinweis = Holzstil.hinweis(" ");
        wahl.addActionListener(e -> {
            int i = wahl.getSelectedIndex();
            steuer.einst.setzen(Steuereinstellungen.SCHRIFT, Holzstil.SCHRIFT_STUFEN[i]);
            hinweis.setText(i == Holzstil.schriftStufe() ? " " : Sprache.t("Applies the next time the bot program starts. (With large text make the window wider.)",
                    "Gilt beim nächsten Start des Bot-Programms. (Bei großer Schrift das Fenster breiter ziehen.)"));
        });
        z.add(wahl);
        z.add(hinweis);
        return z;
    }

    private String kuerzelText(Tastenkuerzel.Aktion a) {
        Tastenkuerzel.Eintrag e = steuer.kuerzel.eintrag(a);
        return e == null || !e.wirksam() ? Sprache.t("none", "keins") : e.kombination().anzeige();
    }

    /** Haekchen im Reiter Anzeigen nachziehen, wenn ein Fenster per Kuerzel auf- oder zuging. */
    void anzeigenNachziehen() {
        for (JCheckBox k : anzeigeKaestchen) {
            Tastenkuerzel.Aktion a = (Tastenkuerzel.Aktion) k.getClientProperty("aktion");
            k.setSelected(steuer.offen(a));
            k.setToolTipText(Sprache.t("Shortcut: " + kuerzelText(a), "Kuerzel: " + kuerzelText(a)));
        }
        for (Runnable r : kuerzelNachziehen) r.run();
    }

    // ==================================================================
    //  Reiter Einstellungen
    // ==================================================================

    private JComponent einstellungenReiter() {
        JPanel liste = new Holzstil.Rollflaeche(null);
        liste.setLayout(new BoxLayout(liste, BoxLayout.Y_AXIS));
        liste.setBorder(BorderFactory.createEmptyBorder(Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));

        // Drei kurze Zeilen -- ein langer Satz wuerde am rechten Rand abgeschnitten.
        // Autor 11.10.: "so einfach wie moeglich, bei jeder Einstellung ein i daneben" -- oben nur eine Zeile.
        JLabel hinweis = Holzstil.hinweis(Sprache.t("<html>Click <b>i</b> to see what a setting does. After each button the line below "
                + "turns <b>green</b> (it worked) or <b>red</b> (it did not work).</html>",
                "<html>Klick auf <b>i</b> erklärt jede Einstellung. Nach jedem Knopf steht unter der Zeile "
                + "<b>grün „Geklappt“</b> oder <b>rot „Nicht geklappt“</b>.</html>"));
        hinweis.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        hinweis.setAlignmentX(LEFT_ALIGNMENT);
        liste.add(hinweis);
        liste.add(Box.createVerticalStrut(Holzstil.LUFT));
        // Autor 24.10. (F24, F27): Suchfeld, Ansicht Einfach/Alle, "Letzte Aenderung rueckgaengig"
        JComponent leiste = einstellungsLeiste();
        leiste.setAlignmentX(LEFT_ALIGNMENT);
        liste.add(leiste);
        liste.add(Box.createVerticalStrut(Holzstil.LUFT));

        JPanel aussehen = Holzstil.karte(Sprache.t("Look of the control window", "Aussehen des Steuerprogramms"), new GridLayout(0, 1, 0, 4));
        aussehen.add(aussehenZeile());
        aussehen.add(schriftZeile());   // Autor 24.10. (F34)
        liste.add(aussehen);
        Component abstandAussehen = Box.createVerticalStrut(Holzstil.LUFT);
        liste.add(abstandAussehen);

        // Autor 16.10.: Einstellungen mit Freunden teilen -- Code kopieren / einfuegen. Persoenliches (Lager,
        // Nahrungs-Limit, Pass, Lizenz, Discord, Zeitzone, Sprache) ist nie dabei (Einstellungsaustausch).
        JPanel teilen = Holzstil.karte(Sprache.t("Share settings with friends", "Einstellungen mit Freunden teilen"),
                new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        JButton teilenKopieren = Holzstil.kleinerKnopf(Sprache.t("Copy my settings", "Meine Einstellungen kopieren"),
                Sprache.t("Puts a code into the clipboard -- send it to a friend. Personal values (food storage, pass, licence, "
                        + "Discord, time zone) are never included.",
                        "Legt einen Code in die Zwischenablage -- schick ihn einem Freund. Persönliches (Lager, Pass, Lizenz, "
                        + "Discord, Zeitzone) ist nie dabei."), Symbol.Art.HAKEN);
        teilenKopieren.addActionListener(e -> Einstellungsaustausch.kopierenDialog(this, de.hustlebot.core.Ordner.basis()));
        JButton teilenEinfuegen = Holzstil.kleinerKnopf(Sprache.t("Paste settings", "Einstellungen einfügen"),
                Sprache.t("Paste the code from a friend, check what changes, then apply.",
                        "Code vom Freund einfügen, prüfen, was sich ändert, dann übernehmen."), Symbol.Art.ORDNER);
        teilenEinfuegen.addActionListener(e -> Einstellungsaustausch.einfuegenDialog(this, de.hustlebot.core.Ordner.basis()));
        teilen.add(teilenKopieren);
        teilen.add(teilenEinfuegen);
        liste.add(teilen);
        Component abstandTeilen = Box.createVerticalStrut(Holzstil.LUFT);
        liste.add(abstandTeilen);
        // Die beiden Karten oben gehoeren nur zur Ansicht "Alle" und sind mit der Suche zu finden.
        Bereich aussehenBereich = new Bereich(Sprache.t("Look of the control window", "Aussehen des Steuerprogramms"), aussehen,
                java.util.Arrays.stream(aussehen.getComponents()).map(c -> (JComponent) c).toList());
        aussehenBereich.abstand = abstandAussehen;
        Bereich teilenBereich = new Bereich(Sprache.t("Share settings with friends", "Einstellungen mit Freunden teilen"), teilen,
                java.util.Arrays.stream(teilen.getComponents()).map(c -> (JComponent) c).toList());
        teilenBereich.abstand = abstandTeilen;

        // Autor 14.10.: hoechstens 20 Einstellungen. Raus: Truppenaufstellung, Kiste, Lager, Kisten je Event-Gang,
        // Spiel-Neustart, Lernmodus (immer an), Stigmas, Arena-Reserve, Held, Messlatte (immer an), Team-Automatik
        // (immer an), PC aus/wach. Clan-Krieg steht bei "Nebenbei", die Not-Aus-Taste im Reiter Tastenkuerzel.
        kartenInListe(liste,
                bereich(Sprache.t("Food (applies to all modes)", "Nahrung (gilt für alle Modi)"),
                        befehlSchalter(Sprache.t("Use food", "Nahrung benutzen"), new String[][]{{Sprache.t("Yes", "Ja"), "!settings bezahlen alle alles"},
                                {Sprache.t("No", "Nein"), "!settings bezahlen alle tickets"},
                                // Freunde-Bot (16.10., Wunsch einer Freundin): auch "nur Nahrung" als Knopf.
                                {Sprache.t("Food only", "Nur Nahrung"), "!settings bezahlen alle food"},
                                // Mia 21.10.: "Nur Nahrung" oeffnet keine Kisten, "Ja" nimmt zuerst Tickets -- dies: Nahrung, dann Kisten, nie Tickets.
                                {Sprache.t("Food + chests", "Nahrung + Kisten"), "!settings bezahlen alle food+chests"},
                                {Sprache.t("Status", "Stand"), "!settings bezahlen"}}),
                        zeile(Sprache.t("Food limit (all modes)", "Nahrungs-Limit (alle Modi)"),
                                Sprache.t("e.g. 30m · 15m · off", "z. B. 30m · 15m · aus"), "nahrungslimit alle", "30m"),
                        // Autor 14.10.: jeder hat ein anderes Lager (hoechstens 2 Mio) -- bleibt einstellbar.
                        zeile(Sprache.t("Fill food up to (storage)", "Nahrung auffüllen bis (Lager)"),
                                Sprache.t("Your storage size, at most 2000000", "Deine Lagergröße, höchstens 2000000"), "lager", "2000000")),

                bereich(Sprache.t("On the side: lighthouse, colosseum, titan tower, clan war", "Nebenbei: Leuchtturm, Kolosseum, Titanen-Turm, Clan-Krieg"),
                        schalter(Sprache.t("Lighthouse", "Leuchtturm"), "leuchtturm"),
                        schalter(Sprache.t("Colosseum", "Kolosseum"), "kolosseum"),
                        // Autor 15.10.: wie die anderen nur An / Aus / Stand (Stufen weiter per !settings titanturm easy 20).
                        schalter(Sprache.t("Titan tower", "Titanen-Turm"), "titanturm"),
                        befehlSchalter(Sprache.t("Clan war: deploy by itself", "Clan-Krieg selbst aufstellen"), "!kriegsplan an", "!kriegsplan aus", "!kriegsplan")),

                // Autor 16.10.: Ben will seine eigene Truppe ("Previous squad" statt "Deploy the best", damit seine
                // historische Kraft nicht steigt), dazu der Spiel-Neustart alle 2 h -- beide wieder im Fenster.
                // Ben 16.10. (zweiter Wunsch): nicht nur Kathedrale -- in JEDEM Modus "Previous squad".
                bereich(Sprache.t("Squad and game restart", "Truppe und Spiel-Neustart"),
                        befehlSchalter(Sprache.t("Squad in every mode", "Truppe in jedem Modus"), new String[][]{
                                {"Deploy the best", "!settings squad best"},
                                {"Previous squad", "!settings squad previous"},
                                {Sprache.t("Status", "Stand"), "!settings squad"}}),
                        schalter(Sprache.t("Restart the game every 2 hours (faster)", "Spiel alle 2 Stunden neu starten (schneller)"), "spielneustart"),
                        // Autor 18.10. (Link): "Kreislauf ohne Event -- nicht im Clan-Krieg aufgestellt". Der automatische Krieg war
                        // nur per Discord (!clanwar on) einschaltbar, Standard aus -- jetzt als Schalter.
                        befehlSchalter(Sprache.t("Clan war: deploy my team automatically (4 times a day + check 15 min before the war)",
                                        "Clan-Krieg: Team automatisch aufstellen (4-mal am Tag + Kontrolle 15 min vor Kriegsbeginn)"),
                                "!clanwar on", "!clanwar off", "!clanwar"),
                        // Ben 17.10.: vor dem automatischen Clan-Krieg Helden-Set "War", danach "Normal" -- Name UND Platz.
                        schalter(Sprache.t("Hero set for clan war (automatic wars only)", "Helden-Set im Clan-Krieg (nur automatisch)"), "heldenset"),
                        freiZeile(Sprache.t("Set BEFORE clan war", "Set VOR dem Krieg"),
                                Sprache.t("Name and slot of the set, e.g. War 2 (slot 1 = top)", "Name und Platz des Sets, z. B. War 2 (Platz 1 = oben)"),
                                "!settings heldenset vorher", "War 2"),
                        freiZeile(Sprache.t("Set AFTER clan war", "Set NACH dem Krieg"),
                                Sprache.t("Name and slot of the set, e.g. Normal 1 (slot 1 = top)", "Name und Platz des Sets, z. B. Normal 1 (Platz 1 = oben)"),
                                "!settings heldenset nachher", "Normal 1")),

                // Autor, 24.10.: Hammer- und Titan-Event getrennt, jedes mit seinem Abstand (1 bis 500 Kathedralen).
                bereich(Sprache.t("Event automation (event loop)", "Event-Automatik (Event-Kreislauf)"),
                        zeile(Sprache.t("Event automation", "Event-Automatik"),
                                Sprache.t("on · off · titan · hammer · number (dungeon runs in an emergency)", "an · aus · titan · hammer · Zahl (Verlies-Runs im Notfall)"), "eventautomatik", "an")),

                bereich(Sprache.t("Hammer event", "Hammer-Event"),
                        // Im Event-Kreislauf alle N Kathedralen ins Hammer-Event, alle Haemmer, dann weiter.
                        freiZeile(Sprache.t("Hammer event every N cathedrals", "Hammer-Event alle N Kathedralen"),
                                Sprache.t("1 to 500, e.g. 100 or 250 (0 = only when keys and chests are gone)", "1 bis 500, z. B. 100 oder 250 (0 = nur wenn Schluessel und Kisten alle sind)"),
                                "!settings hammerevent", "100"),
                        freiZeile(Sprache.t("Hammer event: at most N stages per visit", "Hammer-Event: hoechstens N Stages je Gang"),
                                Sprache.t("Stages = fields up to the magic wand, e.g. 10 (0 = until the hammers are gone)",
                                        "Stages = Felder bis zum Zauberstab, z. B. 10 (0 = bis die Haemmer alle sind)"),
                                "!settings hammerstages", "0"),
                        zeile("Pass", Sprache.t("gold · silver · none", "gold · silber · kein"), "pass", "gold"),
                        zeile(Sprache.t("Magnifiers per field", "Lupen je Feld"), Sprache.t("e.g. 5", "z. B. 5"), "lupen", "5")),

                bereich(Sprache.t("Titan event", "Titan-Event"),
                        freiZeile(Sprache.t("Titan event every N cathedrals", "Titan-Event alle N Kathedralen"),
                                Sprache.t("1 to 500, e.g. 100 (0 = only when keys and chests are gone; last 12 h at most 20)",
                                        "1 bis 500, z. B. 100 (0 = nur wenn Schluessel und Kisten alle sind; letzte 12 h hoechstens 20)"),
                                "!settings titanevent", "100"),
                        // Autor 18.10. (Ben wusste nicht, wie man es umstellt): HC-Jagd und Drills als Knoepfe.
                        befehlSchalter(Sprache.t("Titan event: dig out all HC rewards (off = straight down)",
                                "Titan-Event: alle HC-Belohnungen ausgraben (aus = gerade runter)"), "!titan hc on", "!titan hc off", "!titan hc"),
                        befehlSchalter(Sprache.t("Titan event: also use the drills", "Titan-Event: auch die Drills benutzen"),
                                "!titan drill on", "!titan drill off", "!titan drill"),
                        // Autor 18.10.: eine alte Ziel-Tiefe liess den Bot sofort wieder aus dem Event gehen.
                        freiZeile(Sprache.t("Titan event: stop at depth (m)", "Titan-Event: aufhoeren bei Tiefe (m)"),
                                Sprache.t("off = no limit", "aus = keine Grenze"), "!titan " + Sprache.t("depth", "ziel"), Sprache.t("off", "aus"))),

                bereich(Sprache.t("Time zone (all schedules follow this clock)", "Zeitzone (alle Zeitpläne gehen nach dieser Uhr)"),
                        freiZeile(Sprache.t("Time zone", "Zeitzone"), Sprache.t("Leave Europe/Berlin -- the game runs on Berlin time, also in other countries. Empty = show the current value.",
                                "Europe/Berlin lassen -- das Spiel laeuft nach Berliner Zeit, auch in anderen Laendern. Leer = Stand zeigen."), "!zeitzone", "Europe/Berlin")),

                bereich(Sprache.t("Your team (barracks – the bot looks by itself every 3 days)", "Eigenes Team (Kaserne – der Bot schaut alle 3 Tage von selbst)"),
                        knoepfe(new String[][]{{Sprache.t("Show team", "Team zeigen"), "!team"}, {Sprache.t("Scan now", "Jetzt ansehen"), "!team scan"}}),
                        // Autor 18.10.: Namen der Opfer-Einheiten selbst angeben (mehrere, mit Komma).
                        freiZeile(Sprache.t("Sacrifice units (names)", "Opfer-Einheiten (Namen)"),
                                Sprache.t("Names under the units, comma-separated, e.g. Josie, Magier 3 (ersatz, opfer, sacrifice always count)",
                                        "Namen unter den Einheiten, mit Komma, z. B. Josie, Magier 3 (ersatz, opfer, sacrifice zaehlen immer)"), "!opfer", "Josie"),
                        freiZeile(Sprache.t("Your player name", "Dein Spielername"),
                                Sprache.t("As shown at the top left in the castle (empty: the bot reads it by itself)",
                                        "Wie er oben links in der Burg steht (leer: der Bot liest ihn selbst)"), "!team name", ""),
                        spielernameErkannt()),

                bereich(Sprache.t("Language of the messages", "Sprache der Meldungen"),
                        knoepfe(new String[][]{{"Deutsch", "!deutsch"}, {"English", "!english"}})),

                bereich(Sprache.t("Sound (Discord notifications)", "Ton (Discord-Benachrichtigungen)"),
                        knoepfe(new String[][]{{Sprache.t("Sound off (mute)", "Ton aus (stumm)"), "!mute"}, {Sprache.t("Sound on", "Ton an"), "!unmute"}})),

                bereich(Sprache.t("Autostart with Windows", "Autostart mit Windows"),
                        knoepfe(new String[][]{{Sprache.t("Autostart on", "Autostart an"), "!autostart an"}, {Sprache.t("Autostart off", "Autostart aus"), "!autostart aus"}})),

                bereich(Sprache.t("Discord (optional)", "Discord (freiwillig)"), discordZeilen()),

                bereich(Sprache.t("Mouse", "Maus"),
                        schalter(Sprache.t("Stop when the mouse moves", "Stopp bei Mausbewegung"), "mauswache")),
                // Autor 21.10.: Arena-Beobachter beim Selbstspielen
                bereich(Sprache.t("Arena", "Arena"),
                        schalter(Sprache.t("Watch my own arena fights (counts wins/losses, never clicks)",
                                "Eigene Arena-Kaempfe mitzaehlen (zaehlt Siege/Niederlagen, klickt nie)"), "arenawatch"),
                        // Autor 24.10.: die Gegner-Listen (Bilder der Namen) direkt oeffnen -- der Bot-Ordner zeigt sonst wenig.
                        gegnerOrdnerKnoepfe()),
                // Autor 21.10. (Mia): mehrere Spielkonten, am Spielernamen erkannt
                bereich(Sprache.t("Game accounts", "Spielkonten"),
                        schalter(Sprache.t("Several accounts (recognised by the player name)",
                                "Mehrere Konten (am Spielernamen erkannt)"), "accounts")),
                bereich(Sprache.t("More", "Mehr"),
                        knoepfe(new String[][]{{Sprache.t("Show all settings", "Alle Einstellungen zeigen"), "!settings all"}, {Sprache.t("What's new", "Was ist neu"), "!update"}})));

        // Autor 24.10. (F24): die beiden Karten oben kommen zur Liste dazu (am Anfang), dann gilt die Ansicht
        bereiche.add(0, teilenBereich);
        bereiche.add(0, aussehenBereich);
        ansichtAnwenden();
        werteAktualisieren();
        return Holzstil.rollenOhneRahmen(liste);
    }

    /** Zaehlt die Karten, die {@link #bereich} baut -- ihre Stelle entscheidet ueber die Ansicht "Einfach". */
    private int bereichZaehler = 0;

    private void kartenInListe(JPanel liste, JComponent... karten) {
        for (int i = 0; i < karten.length; i++) {
            Component abstand = null;
            if (i > 0) {
                abstand = Box.createVerticalStrut(Holzstil.LUFT);
                liste.add(abstand);
            }
            karten[i].setAlignmentX(LEFT_ALIGNMENT);
            liste.add(karten[i]);
            for (Bereich b : bereiche) if (b.karte == karten[i]) b.abstand = abstand;
        }
    }

    private JPanel bereich(String titel, JComponent... zeilen) {
        // 15.10.: GridBag statt GridLayout -- jede Zeile so hoch, wie sie ist (die Rueckmeldung unter einer Zeile
        // macht nur diese Zeile hoeher, nicht alle).
        JPanel p = Holzstil.karte(titel, new GridBagLayout());
        ziele.putIfAbsent(titel, p);   // Tutorial: Pip markiert den ganzen Bereich
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(1, 0, 1, 0);
        List<JComponent> alleZeilen = new ArrayList<>();
        List<Object[]> mitStandard = new ArrayList<>();   // {Wert, Zeilentitel} -- fuer "Auf Standard zurueck"
        for (JComponent z : zeilen) {
            // Knopf-Reihen ohne eigene Beschriftung bekommen die Erklaerung des Bereichs.
            JComponent innen = z.getClientProperty(ZEILE) instanceof JComponent j ? j : z;
            if (innen.getClientProperty(INFO_TITEL) == null) innen.putClientProperty(INFO_TITEL, titel);
            if (z.getClientProperty(INFO_TITEL) == null) z.putClientProperty(INFO_TITEL, titel);
            p.add(z, c);
            alleZeilen.add(z);
            if (innen.getClientProperty(WERT) instanceof Einstellungswerte.Wert w && w.standard() != null) {
                mitStandard.add(new Object[]{w, String.valueOf(innen.getClientProperty(INFO_TITEL))});
            }
        }
        // Autor 24.10. (F27): "Auf Standard zurueck" je Karte -- nur wo der Standard bekannt ist
        if (!mitStandard.isEmpty()) {
            JButton standard = Holzstil.kleinerKnopf(Sprache.t("Reset to default", "Auf Standard zurück"),
                    Sprache.t("Puts the settings of this card that have a fixed default back to it (asks first, can be undone).",
                            "Setzt die Einstellungen dieser Karte, die einen festen Standard haben, darauf zurück (fragt vorher, lässt sich rückgängig machen)."),
                    Symbol.Art.NEUSTART);
            standard.addActionListener(e -> standardZuruecksetzen(titel, mitStandard));
            JPanel zeile = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 2));
            zeile.add(standard);
            p.add(zeile, c);
            alleZeilen.add(zeile);
        }
        Bereich b = new Bereich(titel, p, alleZeilen);
        b.einfach = EINFACH_KARTEN.contains(bereichZaehler++);
        bereiche.add(b);
        return p;
    }

    // ------------------------------------------------------------------
    //  Autor 24.10. (Ideenliste F24, F26, F27): Suche, Ansicht, aktuelle Werte, Rueckgaengig, Standard
    // ------------------------------------------------------------------

    /** Das Suchfeld, die Wahl Einfach/Alle und der Knopf "Letzte Aenderung rueckgaengig" -- ganz oben in den Einstellungen. */
    private JComponent einstellungsLeiste() {
        JPanel karte = Holzstil.karte(Sprache.t("Find a setting", "Einstellung finden"), new GridLayout(0, 1, 0, 2));

        einstellungsSuche = Holzstil.feld("", 16);
        einstellungsSuche.setToolTipText(Sprache.t("Type a word, e.g. food, clan or lighthouse – only the matching settings stay. Empty = the chosen view.",
                "Tipp ein Wort, z. B. Nahrung, Clan oder Leuchtturm – nur die passenden Einstellungen bleiben. Leer = die gewählte Ansicht."));
        einstellungsSuche.getDocument().addDocumentListener(dokument(this::ansichtAnwenden));
        einstellungsSuche.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) einstellungsSuche.setText("");
            }
        });
        // Neue Installation: erst "Einfach" (acht Karten); wer schon dabei ist, behaelt "Alle". Wird gleich gemerkt,
        // damit sich die Vorgabe nach dem Tutorial nicht umdreht.
        boolean vorgabeEinfach = !steuer.einst.an(Tutorial.GESEHEN, false);
        boolean einfach = steuer.einst.an(ANSICHT_EINFACH, vorgabeEinfach);
        steuer.einst.setzen(ANSICHT_EINFACH, einfach);
        einstellungsAnsicht = Holzstil.auswahl(new String[]{Sprache.t("Simple (the 8 most important)", "Einfach (die 8 wichtigsten)"),
                Sprache.t("All settings", "Alle Einstellungen")});
        einstellungsAnsicht.setSelectedIndex(einfach ? 0 : 1);
        einstellungsAnsicht.setToolTipText(Sprache.t("Simple shows only what most people need. All shows every setting.",
                "Einfach zeigt nur, was die meisten brauchen. Alle zeigt jede Einstellung."));
        einstellungsAnsicht.addActionListener(e -> {
            steuer.einst.setzen(ANSICHT_EINFACH, einstellungsAnsicht.getSelectedIndex() == 0);
            ansichtAnwenden();
        });

        rueckgangKnopf = Holzstil.kleinerKnopf(Sprache.t("Undo last change", "Letzte Änderung rückgängig"),
                Sprache.t("Nothing to undo yet.", "Noch nichts zum Zurücknehmen."), Symbol.Art.NEUSTART);
        rueckgangKnopf.setEnabled(false);
        rueckgangKnopf.addActionListener(e -> rueckgangAusfuehren());
        rueckgangStand = Holzstil.hinweis(" ");
        suchHinweis = Holzstil.hinweis(" ");

        // Zwei Reihen mit FlowLayout (nicht GridBag): bei grosser Schrift bleibt das Suchfeld so breit, wie es ist
        JPanel reihe1 = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        reihe1.add(Holzstil.beschriftung(Sprache.t("Search", "Suche")));
        reihe1.add(einstellungsSuche);
        reihe1.add(einstellungsAnsicht);
        reihe1.add(rueckgangKnopf);
        JPanel reihe2 = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        reihe2.add(suchHinweis);
        reihe2.add(rueckgangStand);
        karte.add(reihe1);
        karte.add(reihe2);
        ziele.put("einstellungsleiste", karte);
        return karte;
    }

    /** Je Karte und Zeile: zeigen oder verstecken, nach Suche und Ansicht. */
    private void ansichtAnwenden() {
        if (einstellungsSuche == null || einstellungsAnsicht == null) return;
        String suche = einstellungsSuche.getText();
        boolean sucht = !Einstellungssuche.normal(suche).isEmpty();
        boolean einfach = einstellungsAnsicht.getSelectedIndex() == 0;
        int gezeigt = 0;
        for (Bereich b : bereiche) {
            boolean sichtbar;
            if (!sucht) {
                for (JComponent z : b.zeilen) z.setVisible(true);
                sichtbar = !einfach || b.einfach;
            } else {
                boolean titelPasst = Einstellungssuche.passt(suche, b.titel);
                int treffer = 0;
                for (JComponent z : b.zeilen) {
                    boolean zeileSichtbar = titelPasst || Einstellungssuche.passt(suche, Einstellungssuche.textVon(z, ERKLAERUNG::get));
                    z.setVisible(zeileSichtbar);
                    if (zeileSichtbar) treffer++;
                }
                sichtbar = titelPasst || treffer > 0;
            }
            b.karte.setVisible(sichtbar);
            if (b.abstand != null) b.abstand.setVisible(sichtbar);
            if (sichtbar) gezeigt++;
        }
        if (suchHinweis != null) {
            suchHinweis.setText(sucht
                    ? (gezeigt == 0 ? Sprache.t("Nothing found – try another word.", "Nichts gefunden – versuch ein anderes Wort.")
                    : Sprache.t(gezeigt + " area(s) found.", gezeigt + " Bereich(e) gefunden."))
                    : einfach ? Sprache.t("Showing the 8 most important areas. Choose “All settings” for the rest.",
                            "Gezeigt werden die 8 wichtigsten Bereiche. Für den Rest „Alle Einstellungen“ wählen.")
                    : " ");
        }
        // Der erste sichtbare Bereich hat keinen Abstand davor.
        for (Bereich b : bereiche) {
            if (b.karte.isVisible()) {
                if (b.abstand != null) b.abstand.setVisible(false);
                break;
            }
        }
        JComponent inhalt = (JComponent) (bereiche.isEmpty() ? null : bereiche.get(0).karte.getParent());
        if (inhalt != null) {
            inhalt.revalidate();
            inhalt.repaint();
        }
    }

    /** Baut das Schildchen "jetzt: ..." fuer eine Zeile; es wird mit den anderen im Takt aufgefrischt. */
    private JLabel wertSchild(Einstellungswerte.Wert w) {
        JLabel l = Holzstil.hinweis(" ");
        l.setFont(Holzstil.KLEIN.deriveFont(Font.BOLD));
        wertAnzeigen.add(new WertAnzeige(l, w));
        String a = w.aktuellAnzeige();
        if (a != null) l.setText(schildText(a));
        return l;
    }

    /** Kurz genug fuer die Zeile; was mehrere Werte enthaelt oder zu lang ist, steht ganz im Tooltip. */
    private static String schildText(String anzeige) {
        String t = anzeige.length() <= 28 ? anzeige
                : anzeige.contains(" · ") ? Sprache.t("several values (hover)", "mehrere Werte (Maus darauf)")
                : anzeige.substring(0, 27) + "…";
        return Sprache.t("now: ", "jetzt: ") + t;
    }

    /** Alle Schildchen neu lesen (nur im Swing-Faden; die Werte sind schnell gelesen). */
    private void werteAktualisieren() {
        for (WertAnzeige a : wertAnzeigen) {
            String anzeige = a.wert().aktuellAnzeige();
            String neu = anzeige == null ? " " : schildText(anzeige);
            if (!neu.equals(a.label().getText())) {
                a.label().setText(neu);
                a.label().setToolTipText(anzeige);
                if (a.label().getParent() != null) a.label().getParent().revalidate();
            }
        }
    }

    /** Nach einer Aenderung: den alten Wert merken, wenn sich wirklich etwas geaendert hat (nur bei "Geklappt"). */
    private void nachAenderung(Einstellungswerte.Wert wert, String vorher, String erklaerung, Rueckmeldung.Ergebnis e) {
        werteAktualisieren();
        if (e.art() != Rueckmeldung.Art.GEKLAPPT || vorher == null) return;
        String jetzt = wert.aktuellRoh();
        if (jetzt == null || jetzt.equals(vorher)) return;
        rueckgangMerken(erklaerung, List.of(wert.setzBefehl(vorher)));
    }

    private void rueckgangMerken(String text, List<String> befehle) {
        rueckgaenge.push(new Rueckgang(text, befehle));
        while (rueckgaenge.size() > 20) rueckgaenge.removeLast();
        rueckgangAnzeigen();
    }

    private void rueckgangAnzeigen() {
        if (rueckgangKnopf == null) return;
        Rueckgang r = rueckgaenge.peek();
        rueckgangKnopf.setEnabled(r != null);
        rueckgangKnopf.setToolTipText(r == null ? Sprache.t("Nothing to undo yet.", "Noch nichts zum Zurücknehmen.")
                : Sprache.t("Undoes: ", "Nimmt zurück: ") + r.text() + "  (" + String.join(", ", r.befehle()) + ")");
    }

    /** "Letzte Aenderung rueckgaengig": schickt die Befehle, die den alten Stand wiederherstellen. */
    private void rueckgangAusfuehren() {
        Rueckgang r = rueckgaenge.poll();
        rueckgangAnzeigen();
        if (r == null) return;
        for (String b : r.befehle()) {
            steuer.senden(b, Sprache.t("Settings: undo ", "Einstellungen: rückgängig ") + r.text(), false, ergebnis -> werteAktualisieren());
        }
        if (rueckgangStand != null) {
            rueckgangStand.setText(Sprache.t("Undone: ", "Zurückgenommen: ") + r.text());
            rueckgangStand.setToolTipText(String.join("\n", r.befehle()));
        }
    }

    /** "Auf Standard zurueck" fuer eine Karte: zeigt, was sich aendert, fragt, schickt, merkt den Rueckweg. */
    private void standardZuruecksetzen(String titel, List<Object[]> werte) {
        List<String> befehle = new ArrayList<>(), zurueck = new ArrayList<>();
        StringBuilder liste = new StringBuilder();
        for (Object[] o : werte) {
            Einstellungswerte.Wert w = (Einstellungswerte.Wert) o[0];
            String roh = w.aktuellRoh();
            if (roh == null || w.standard() == null || roh.equalsIgnoreCase(w.standard())) continue;
            befehle.add(w.setzBefehl(w.standard()));
            zurueck.add(w.setzBefehl(roh));
            liste.append("• ").append(o[1]).append(":  ").append(roh).append("  →  ").append(w.standard()).append("<br>");
        }
        if (befehle.isEmpty()) {
            JOptionPane.showMessageDialog(this, Sprache.t("Everything in this card that has a fixed default is already at the default.",
                    "Alles in dieser Karte, was einen festen Standard hat, steht schon auf dem Standard."), titel, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!steuer.fragen("<html><div style='width:380px'>" + Sprache.t("These settings go back to the default:", "Diese Einstellungen gehen auf den Standard zurück:")
                + "<br><br>" + liste + "<br>" + Sprache.t("You can undo this with “Undo last change”.", "Mit „Letzte Änderung rückgängig“ geht es wieder zurück.")
                + "</div></html>", Sprache.t("Reset to default: ", "Auf Standard zurück: ") + titel)) return;
        for (String b : befehle) {
            steuer.senden(b, Sprache.t("Settings: default ", "Einstellungen: Standard ") + titel, false, ergebnis -> werteAktualisieren());
        }
        rueckgangMerken(Sprache.t("Reset to default: ", "Auf Standard zurück: ") + titel, zurueck);
    }

    private static final String ZEILE = "zeile";

    /** Zeile + darunter die Rueckmeldung (erst sichtbar, wenn etwas gedrueckt wurde). */
    private static JComponent mitRueckmeldung(JPanel zeile, JLabel rueck) {
        JPanel w = Holzstil.flaeche(new BorderLayout());
        w.add(zeile, BorderLayout.CENTER);
        rueck.setBorder(BorderFactory.createEmptyBorder(0, 36, 3, 0));   // unter der Beschriftung, nicht unter dem i
        rueck.setVisible(false);
        w.add(rueck, BorderLayout.SOUTH);
        w.putClientProperty(ZEILE, zeile);
        Object info = zeile.getClientProperty(INFO_TITEL);
        if (info != null) w.putClientProperty(INFO_TITEL, info);
        return w;
    }

    private static final String INFO_TITEL = "infoTitel";

    /**
     * Autor, 11.10.: "Bei jeder Einstellung ein i daneben -- wenn man das anklickt, kommt eine kleine Erklaerung, wie das
     * gemeint ist, wo es zu finden ist usw." Gesucht wird nach der Beschriftung, bei Knopf-Reihen nach dem Bereich
     * (der Anfang reicht, "Eigenes Team (Kaserne ...)" findet "Eigenes Team").
     */
    private static final Map<String, String> ERKLAERUNG = Map.ofEntries(
            Map.entry(Sprache.t("Use food", "Nahrung benutzen"), Sprache.t("<b>Yes</b> = keys/tickets first, then food, then food chests – in all modes (dungeon, cathedral, arena).<br><b>No</b> = only keys/tickets, never food.<br><b>Food only</b> = only food, never keys/tickets.",
                    "<b>Ja</b> = erst Schluessel/Tickets, dann Nahrung, dann Nahrungskisten – in allen Modi (Verlies, Kathedrale, Arena).<br><b>Nein</b> = nur Schluessel/Tickets, nie Nahrung.<br><b>Nur Nahrung</b> = nur Nahrung, nie Schluessel/Tickets.")),
            Map.entry(Sprache.t("Food limit (all modes)", "Nahrungs-Limit (alle Modi)"), Sprache.t("Below this amount of food the bot stops paying with food – applies to all modes.<br>e.g. <b>30m</b> = not below 30 million apples. <b>off</b> = no limit, <b>ohne bis 9:30</b> = no limit until 9:30.",
                    "Unter dieser Nahrungsmenge zahlt der Bot nicht mehr mit Nahrung – gilt fuer alle Modi.<br>z. B. <b>30m</b> = unter 30 Millionen Aepfeln nicht mehr. <b>aus</b> = keine Grenze, <b>ohne bis 9:30</b> = bis 9:30 ohne Grenze.")),
            Map.entry(Sprache.t("Emergency key", "Not-Aus-Taste"), Sprache.t("This key stops the bot at once, no matter which window is in front. Default <b>F9</b>.",
                    "Diese Taste haelt den Bot sofort an, egal welches Fenster vorn ist. Standard <b>F9</b>.")),
            Map.entry(Sprache.t("Payment per mode", "Bezahlart je Modus"), Sprache.t("What the bot pays the entry with.<br>food = food only (no chests) · food+chests = food, then food chests, never keys · alles = keys first, then food, then food chests · tickets = tickets only.<br>Per mode, e.g. <b>verlies food</b> or <b>kathedrale alles</b>.",
                    "Womit der Bot den Eintritt bezahlt.<br>food = nur Nahrung (keine Kisten) · food+chests = Nahrung, dann Nahrungskisten, nie Schluessel · alles = erst Schluessel, dann Nahrung, dann Nahrungskisten · tickets = nur Tickets.<br>Je Modus einzeln, z. B. <b>verlies food</b> oder <b>kathedrale alles</b>.")),
            Map.entry(Sprache.t("Food limit", "Nahrungs-Limit"), Sprache.t("Below this amount of food the bot stops paying with food.<br>e.g. <b>kathedrale 30m</b> = not below 30 million apples. <b>off</b> = no limit, <b>ohne bis 9:30</b> = no limit until 9:30.<br>Your food is shown at the top of the game next to the red apple.",
                    "Unter dieser Nahrungsmenge zahlt der Bot nicht mehr mit Nahrung.<br>z. B. <b>kathedrale 30m</b> = unter 30 Millionen Aepfeln nicht mehr. <b>aus</b> = keine Grenze, <b>ohne bis 9:30</b> = bis 9:30 ohne Grenze.<br>Die Nahrung steht im Spiel oben beim roten Apfel.")),
            Map.entry(Sprache.t("Fill food up to (storage)", "Nahrung auffüllen bis (Lager)"), Sprache.t("Up to this amount the bot opens food chests to fill up your storage (apple at the top of the game).",
                    "Bis zu dieser Menge oeffnet der Bot Nahrungskisten, um dein Lager aufzufuellen (Apfel oben im Spiel).")),
            Map.entry(Sprache.t("Food chests per event visit", "Nahrungskisten je Event-Gang"), Sprache.t("The most food chests the bot opens during one visit to the event.",
                    "So viele Nahrungskisten oeffnet der Bot hoechstens bei einem Gang ins Event.")),
            Map.entry(Sprache.t("Dungeon", "Verlies"), Sprache.t("Which button is pressed before the fight, in the window “Gather troops”:<br><b>best</b> = “Deploy the best” (strongest units) · <b>previous</b> = “Previous Squad” (your last setup).",
                    "Welcher Knopf vor dem Kampf gedrueckt wird, im Fenster „Gather troops“:<br><b>best</b> = „Deploy the best“ (die staerksten Einheiten) · <b>previous</b> = „Previous Squad“ (deine letzte Aufstellung).")),
            Map.entry(Sprache.t("Cathedral", "Kathedrale"), Sprache.t("Which button is pressed before the fight: <b>best</b> = “Deploy the best”, <b>previous</b> = “Previous Squad”.",
                    "Welcher Knopf vor dem Kampf gedrueckt wird: <b>best</b> = „Deploy the best“, <b>previous</b> = „Previous Squad“.")),
            Map.entry("Arena", Sprache.t("Which button is pressed before the fight: <b>best</b> = “Deploy the best”, <b>previous</b> = “Previous Squad”.",
                    "Welcher Knopf vor dem Kampf gedrueckt wird: <b>best</b> = „Deploy the best“, <b>previous</b> = „Previous Squad“.")),
            Map.entry(Sprache.t("Clan war tower", "Clan-Krieg Turm"), Sprache.t("Setup in the clan war at the tower: <b>best</b> = “Deploy the best”, <b>previous</b> = “Previous Squad”.",
                    "Aufstellung im Clan-Krieg am Turm: <b>best</b> = „Deploy the best“, <b>previous</b> = „Previous Squad“.")),
            Map.entry(Sprache.t("Outpost", "Außenposten"), Sprache.t("Setup in the clan war at the outpost: <b>best</b> = “Deploy the best”, <b>previous</b> = “Previous Squad”.",
                    "Aufstellung im Clan-Krieg am Aussenposten: <b>best</b> = „Deploy the best“, <b>previous</b> = „Previous Squad“.")),
            Map.entry(Sprache.t("Lighthouse", "Leuchtturm"), Sprache.t("On = the bot visits the lighthouse on the side whenever it is due, then goes back to your mode.",
                    "An = der Bot besucht nebenbei den Leuchtturm, sobald er wieder faellig ist, und kehrt dann zu deinem Modus zurueck.")),
            Map.entry(Sprache.t("Colosseum", "Kolosseum"), Sprache.t("On = the bot plays the colosseum on the side whenever it is due.",
                    "An = der Bot spielt nebenbei das Kolosseum, sobald es wieder faellig ist.")),
            Map.entry(Sprache.t("Titan tower", "Titanen-Turm"), Sprache.t(
                    "<b>On</b>: the bot plays the titan tower on the side (by itself at 02:00 and 12:00). "
                    + "<b>Off</b>: it doesn't. <b>Status</b> shows the current setting.",
                    "<b>An</b>: der Bot spielt den Titanen-Turm nebenbei (von selbst um 02:00 und 12:00). "
                    + "<b>Aus</b>: nicht. <b>Stand</b> zeigt, wie es gerade ist.")),
            Map.entry(Sprache.t("Clan war: deploy by itself", "Clan-Krieg selbst aufstellen"), Sprache.t("On = at the war times (" + de.hustlebot.core.Kriegsplan.zeitenText(", ") + ") the bot deploys your troops in the clan war by itself.",
                    "An = der Bot stellt zu den Kriegszeiten (" + de.hustlebot.core.Kriegsplan.zeitenText(", ") + ") selbst deine Truppen im Clan-Krieg auf.")),
            Map.entry(Sprache.t("Chest on/off", "Kiste an/aus"), Sprache.t("Which food chests the bot may open, e.g. <b>wood off</b> = wooden chests stay closed.",
                    "Welche Nahrungskisten der Bot oeffnen darf, z. B. <b>holz aus</b> = Holzkisten bleiben zu.")),
            Map.entry(Sprache.t("Event automation", "Event-Automatik"), Sprache.t("What the bot does in the event by itself: <b>on</b> · <b>off</b> · only <b>titan</b> · only <b>hammer</b>.<br>A number = that many dungeon runs in an emergency.",
                    "Was der Bot im Event selbst macht: <b>an</b> · <b>aus</b> · nur <b>titan</b> · nur <b>hammer</b>.<br>Eine Zahl = so viele Verlies-Runs im Notfall.")),
            Map.entry(Sprache.t("Hammer event every N cathedrals", "Hammer-Event alle N Kathedralen"), Sprache.t("In the event loop the bot goes into the hammer event after this many cathedral runs, uses all hammers and then carries on with the loop (lighthouse, colosseum, titan tower, clan war ...).<br><b>0</b> = only when keys and chests are gone.",
                    "Im Event-Kreislauf geht der Bot nach so vielen Kathedralen-Runs ins Hammer-Event, benutzt alle Haemmer und spielt dann den Kreislauf weiter (Leuchtturm, Kolosseum, Titanen-Turm, Clan-Krieg ...).<br><b>0</b> = nur wenn Schluessel und Kisten alle sind.")),
            Map.entry(Sprache.t("Titan event every N cathedrals", "Titan-Event alle N Kathedralen"), Sprache.t("In the event loop the bot goes into the titan event after this many cathedral runs (1 to 500), uses the boss tickets and all pickaxes and then carries on with the loop.<br>In the last 12 hours of the event at most every 20 runs. <b>0</b> = only when keys and chests are gone.",
                    "Im Event-Kreislauf geht der Bot nach so vielen Kathedralen-Runs (1 bis 500) ins Titan-Event, benutzt die Boss-Tickets und alle Spitzhacken und spielt dann den Kreislauf weiter.<br>In den letzten 12 Stunden des Events hoechstens alle 20 Runs. <b>0</b> = nur wenn Schluessel und Kisten alle sind.")),
            Map.entry(Sprache.t("Hammer event: at most N stages per visit", "Hammer-Event: hoechstens N Stages je Gang"), Sprache.t("A stage is one field up to the magic wand. After this many stages the bot leaves the hammer event and carries on with the loop; the hammers left over are used on the next visit.<br><b>0</b> = until the hammers are gone.",
                    "Eine Stage ist ein Feld bis zum Zauberstab. Nach so vielen Stages verlaesst der Bot das Hammer-Event und spielt den Kreislauf weiter; die uebrigen Haemmer kommen beim naechsten Gang dran.<br><b>0</b> = bis die Haemmer alle sind.")),
            Map.entry("Pass", Sprache.t("Which event pass you have: <b>gold</b>, <b>silver</b> or <b>none</b>. The bot plays the event accordingly.",
                    "Welchen Event-Pass du hast: <b>gold</b>, <b>silber</b> oder <b>kein</b>. Der Bot richtet sich im Event danach.")),
            Map.entry(Sprache.t("Magnifiers per field", "Lupen je Feld"), Sprache.t("The most magnifiers the bot uses on one field in the event.",
                    "So viele Lupen setzt der Bot im Event hoechstens auf ein Feld.")),
            Map.entry(Sprache.t("Game restart", "Spiel-Neustart"), Sprache.t("The game gets slower over time, so the bot restarts it regularly.<br><b>on</b> · <b>off</b> · hours (e.g. <b>1.5</b>) · <b>now</b>.",
                    "Das Spiel wird mit der Zeit langsamer. Der Bot startet es darum regelmaessig neu.<br><b>an</b> · <b>aus</b> · Stunden (z. B. <b>1.5</b>) · <b>jetzt</b>.")),
            Map.entry(Sprache.t("Learning mode (report when stuck)", "Lernmodus (bei Hänger melden)"), Sprache.t("If the bot gets stuck, it reports the case with a photo so it can learn.",
                    "Bleibt der Bot haengen, meldet er den Fall mit Foto, damit er dazulernt.")),
            Map.entry(Sprache.t("Stigmas and ranking", "Stigmas und Rangliste"), Sprache.t("“Stigma settings” opens a form: which stigmas the bot keeps or takes apart.<br>“Ranking” shows the best ones, “Back to default” resets everything.",
                    "„Stigmas einstellen“ oeffnet ein Formular: welche Stigmas der Bot behaelt oder zerlegt.<br>„Rangliste“ zeigt die besten, „Zurueck auf Standard“ setzt alles zurueck.")),
            Map.entry(Sprache.t("Ticket reserve", "Ticket-Reserve"), Sprache.t("The bot stops the arena when only this many tickets are left. <b>0</b> = off.",
                    "Der Bot hoert mit der Arena auf, wenn nur noch so viele Tickets da sind. <b>0</b> = aus.")),
            Map.entry(Sprache.t("Food reserve arena", "Nahrungs-Reserve Arena"), Sprache.t("This much food always stays in the castle when the arena is paid with food. <b>off</b> = no reserve.",
                    "So viel Nahrung bleibt immer in der Burg, wenn die Arena mit Nahrung bezahlt wird. <b>aus</b> = keine Reserve.")),
            Map.entry(Sprache.t("Max price arena", "Hoechstpreis Arena"), Sprache.t("If a tournament costs more food than this, the bot does not play it. <b>off</b> = any price.",
                    "Kostet ein Turnier mehr Nahrung als das, spielt der Bot es nicht. <b>aus</b> = jeder Preis.")),
            Map.entry(Sprache.t("Food limit arena", "Nahrungs-Limit Arena"), Sprache.t("Below this amount of food the arena stops paying with food, e.g. <b>500k</b>.",
                    "Unter dieser Nahrungsmenge zahlt die Arena nicht mehr mit Nahrung, z. B. <b>500k</b>.")),
            Map.entry(Sprache.t("Short line after each tournament", "Kurze Zeile nach jedem Turnier"), Sprache.t("After each arena tournament the bot sends a short line with place and points.",
                    "Nach jedem Arena-Turnier schickt der Bot eine kurze Zeile mit Platz und Punkten.")),
            Map.entry(Sprache.t("Time zone", "Zeitzone"), Sprache.t("All schedules follow this clock: clan war, Titan tower, PC off. <b>Leave Europe/Berlin</b> -- Hustle Castle runs on Berlin time (clan war, daily reset), also if you live in England or elsewhere. Another zone makes the bot an hour (or more) too early or too late.<br>Leave it empty and “Show” shows the current clock.",
                    "Nach dieser Uhr gehen alle Zeitplaene: Clan-Krieg, Titanen-Turm, PC aus. <b>Europe/Berlin lassen</b> -- Hustle Castle laeuft nach Berliner Zeit (Clan-Krieg, Tages-Reset), auch wenn du in England oder woanders wohnst. Eine andere Zone macht den Bot eine Stunde (oder mehr) zu frueh oder zu spaet.<br>Leer lassen und „Anzeigen“ zeigt die jetzige Uhr.")),
            Map.entry(Sprache.t("Your team", "Eigenes Team"), Sprache.t("From the castle the bot looks at your barracks: team power, alliance sign and every unit with armor and stones – once, then every 3 days.<br>From that: your team's points and the pictures of your units for the dungeon (most power = unit 1, the weakest one is sacrificed).",
                    "Der Bot schaut von der Burg aus deine Kaserne an: Teamkraft, Allianz-Zeichen und jede Einheit mit Ruestung und Steinen – einmal, dann alle 3 Tage.<br>Daraus: die Punkte deines Teams und die Bilder deiner Einheiten fuers Verlies (meiste Kraft = Einheit 1, die schwaechste wird geopfert).")),
            Map.entry(Sprache.t("Team as yardstick in the arena", "Team als Messlatte in der Arena"), Sprache.t("On = the arena compares every opponent with your own team to estimate the chance to win better.",
                    "An = die Arena vergleicht jeden Gegner mit deinem eigenen Team, um die Siegchance besser zu schaetzen.")),
            Map.entry(Sprache.t("Hero: points main/side", "Held: Punkte Haupt/Neben"), Sprache.t("How many points a good main or side skill of the hero counts, e.g. <b>12 4</b>.",
                    "Wie viele Punkte eine gute Haupt- bzw. Nebenfaehigkeit des Helden zaehlt, z. B. <b>12 4</b>.")),
            Map.entry(Sprache.t("Your player name", "Dein Spielername"), Sprache.t("Your name as shown at the top left in the castle. Leave it empty: the bot reads it by itself.",
                    "Dein Name, wie er oben links in der Burg steht. Leer lassen: der Bot liest ihn selbst.")),
            Map.entry(Sprache.t("Check game settings", "Spiel-Einstellungen prüfen"), Sprache.t("At the start and every 3 days the bot taps the <b>gear</b> at the bottom right in the game and checks:<br>language <b>English</b>, <b>HD Graphics Off</b>, <b>V Sync Off</b>, Scrolling sensitivity. Whatever is wrong, it fixes by itself.",
                    "Beim Start und alle 3 Tage tippt der Bot im Spiel unten rechts aufs <b>Zahnrad</b> und prueft:<br>Sprache <b>English</b>, <b>HD Graphics Off</b>, <b>V Sync Off</b>, Scrolling sensitivity. Was falsch steht, stellt er selbst richtig.")),
            Map.entry(Sprache.t("Language of the messages", "Sprache der Meldungen"), Sprache.t("The language the bot writes in – in Discord and in its windows.",
                    "In welcher Sprache der Bot schreibt – in Discord und in den Fenstern.")),
            Map.entry(Sprache.t("Sound (Discord notifications)", "Ton (Discord-Benachrichtigungen)"), Sprache.t("Mute = the bot's messages in Discord come without a sound.",
                    "Stumm = die Nachrichten des Bots in Discord kommen ohne Ton.")),
            // Autor 18.10.: "Die Beschreibung bitte auch beim i-Zeichen -- jetzt checke ich es."
            Map.entry(Sprache.t("Set BEFORE clan war", "Set VOR dem Krieg"), Sprache.t(
                    "Before the bot deploys your team in the clan war, it switches your hero to this hero set. "
                    + "\"War 2\" means: the set named \"War\" in the 2nd place of your set list (place 1 = top). "
                    + "Only when \"Hero set for clan war\" is on, and only for automatic wars. Empty = the hero stays as he is.",
                    "Bevor der Bot dein Team im Clan-Krieg aufstellt, stellt er deinen Helden auf dieses Helden-Set um. "
                    + "\"War 2\" heisst: das Set mit dem Namen \"War\" an 2. Stelle deiner Set-Liste (Platz 1 = oben). "
                    + "Nur wenn \"Helden-Set im Clan-Krieg\" an ist, und nur beim automatischen Krieg. Leer = der Held bleibt, wie er ist.")),
            Map.entry(Sprache.t("Set AFTER clan war", "Set NACH dem Krieg"), Sprache.t(
                    "When the bot is done with the clan war, it switches your hero back to this set -- e.g. \"Normal 1\" = the set "
                    + "\"Normal\" in place 1. So the hero fights the war with the war gear and the dungeon/cathedral with the normal gear.",
                    "Wenn der Bot mit dem Clan-Krieg fertig ist, stellt er deinen Helden auf dieses Set zurueck -- z. B. \"Normal 1\" = "
                    + "das Set \"Normal\" auf Platz 1. So kaempft der Held im Krieg mit Kriegs-Ausruestung und im Verlies/in der "
                    + "Kathedrale wieder mit der normalen.")),
            Map.entry(Sprache.t("Hero set for clan war (automatic wars only)", "Helden-Set im Clan-Krieg (nur automatisch)"), Sprache.t(
                    "On: before the automatic clan war the hero gets the set from \"Set BEFORE clan war\", afterwards the one from "
                    + "\"Set AFTER clan war\". Off: the bot does not touch the hero.",
                    "An: vor dem automatischen Clan-Krieg bekommt der Held das Set aus \"Set VOR dem Krieg\", danach das aus "
                    + "\"Set NACH dem Krieg\". Aus: der Bot laesst den Helden in Ruhe.")),
            Map.entry(Sprache.t("Clan war: deploy my team automatically (4 times a day + check 15 min before the war)",
                    "Clan-Krieg: Team automatisch aufstellen (4-mal am Tag + Kontrolle 15 min vor Kriegsbeginn)"), Sprache.t(
                    "On (default): the bot deploys your team by itself at " + de.hustlebot.core.Kriegsplan.zeitenText(", ") + " -- at the stronghold with your "
                    + "name, otherwise a free one. 10 minutes before the war it checks again and moves the team if needed. Off: no clan war.",
                    "An (Standard): der Bot stellt dein Team um " + de.hustlebot.core.Kriegsplan.zeitenText(", ") + " von selbst auf -- beim Stuetzpunkt mit deinem "
                    + "Namen, sonst bei einem freien. 10 Minuten vor Kriegsbeginn schaut er nach und setzt es bei Bedarf um. Aus: kein Clan-Krieg.")),
            Map.entry(Sprache.t("Autostart with Windows", "Autostart mit Windows"), Sprache.t("Starts the bot by itself when Windows starts up.",
                    "Startet den Bot von selbst, wenn Windows hochfaehrt.")),
            Map.entry(Sprache.t("PC off at", "PC aus um"), Sprache.t("Shuts the PC down at this time (after the running run). <b>now</b> = right away, <b>delete</b> = takes it back.",
                    "Faehrt den PC zu dieser Uhrzeit herunter (nach dem laufenden Durchgang). <b>jetzt</b> = sofort, <b>weg</b> = nimmt es zurueck.")),
            Map.entry(Sprache.t("Bot stops by itself at", "Bot hört von selbst auf um"), Sprache.t("The bot finishes the running run and then stops. The PC stays on.",
                    "Der Bot spielt den laufenden Durchgang fertig und hoert dann auf. Der PC bleibt an.")),
            Map.entry(Sprache.t("Keep awake / photo every", "Wach halten / Foto alle"), Sprache.t("Keeps the PC awake and sends a photo of the game every so many minutes. <b>off</b> = switch off.",
                    "Haelt den PC wach und schickt alle so viele Minuten ein Foto vom Spiel. <b>aus</b> = abschalten.")),
            Map.entry(Sprache.t("Key", "Taste"), Sprache.t("This key stops the bot at once, no matter which window is in front. Default <b>F9</b>.",
                    "Diese Taste stoppt den Bot sofort, egal welches Fenster vorn ist. Standard <b>F9</b>.")));

    private JButton infoKnopf(JComponent zeile, String titel) {
        JButton i = Holzstil.kleinerKnopf("i", Sprache.t("What is this? Short explanation", "Was ist das? Kurze Erklaerung"));
        i.addActionListener(e -> {
            String t = titel != null ? titel : (String) zeile.getClientProperty(INFO_TITEL);
            String text = t == null ? null : ERKLAERUNG.get(t);
            if (text == null && t != null) {
                for (Map.Entry<String, String> m : ERKLAERUNG.entrySet()) if (t.startsWith(m.getKey())) text = m.getValue();
            }
            JOptionPane.showMessageDialog(this, "<html><div style='width:360px'>"
                    + (text != null ? text : Sprache.t("Sends the command on the button – just like in Discord.",
                            "Schickt den Befehl auf dem Knopf \u2013 genau wie in Discord.")) + "</div></html>",
                    t == null ? Sprache.t("Explanation", "Erklaerung") : t, JOptionPane.INFORMATION_MESSAGE);
        });
        return i;
    }

    /** Beschriftung, Feld, Setzen, Anzeigen -> "!settings <bereich> <wert>" bzw. "!settings <bereich>". */
    private JComponent zeile(String titel, String hinweis, String bereich, String beispiel) {
        return freiZeile(titel, hinweis, "!settings " + bereich, beispiel);
    }

    /** Breite der Beschriftungen in den Einstellungen, damit alle Felder buendig stehen. */
    private static final int BESCHRIFTUNG = 230;

    /** Dasselbe mit einem beliebigen Befehl davor ("!aus", "!arenareserve"). */
    private JComponent freiZeile(String titel, String hinweis, String befehl, String beispiel) {
        // Autor 24.10. (F26, F27): kennt das Programm den Wert dieser Einstellung, steht er gleich daneben -- und das
        // Schildchen merkt sich der Kartenknopf "Auf Standard zurueck".
        Einstellungswerte.Wert wert = Einstellungswerte.finde(befehl);
        if (wert != null && !Einstellungswerte.normal(befehl).equals(wert.schluessel())) wert = null;
        // F25: reine Zahlenfelder als Drehfeld mit festem Bereich
        Eingabepruefung.Zahlenbereich zahlen = wert == null ? null : Eingabepruefung.zahlenbereich(befehl);
        if (zahlen != null) return zahlenZeile(titel, hinweis, befehl, beispiel, wert, zahlen);

        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        p.putClientProperty(INFO_TITEL, titel);
        if (wert != null) p.putClientProperty(WERT, wert);
        p.add(infoKnopf(p, titel));
        JLabel l = Holzstil.beschriftung(titel, BESCHRIFTUNG);
        l.setToolTipText(hinweis);
        // Autor, 12.10.: "Immer bei den Zeilen, wo ich etwas reinschreiben muss, aender das zu einer auswaehlbaren
        // Option." Liste mit passenden Werten; eintippen geht weiter (editierbar). Leer = nur anzeigen.
        JComboBox<String> f = Holzstil.auswahl(optionen(titel, hinweis, beispiel));
        f.setEditable(true);
        f.setSelectedIndex(-1);
        f.setPrototypeDisplayValue("kathedrale schluessel xx");
        f.setToolTipText(hinweis + Sprache.t("  (pick from the list or type it in; empty = only show)",
                "  (aus der Liste waehlen oder selbst eintippen; leer = nur anzeigen)"));
        JButton setzen = Holzstil.kleinerKnopf(Sprache.t("Set", "Setzen"), Sprache.t("Sends " + befehl + " <choice>", "Schickt " + befehl + " <Auswahl>"), Symbol.Art.HAKEN);
        JLabel rueck = Rueckmeldung.label();
        Runnable los = () -> {
            Object gewaehlt = f.getEditor().getItem();
            String w = gewaehlt == null ? "" : gewaehlt.toString().trim();
            String b = w.isEmpty() ? befehl : befehl + " " + w;
            schicken(b, Sprache.t("Settings " + titel, "Einstellungen " + titel), titel, w.isEmpty(), rueck);
        };
        setzen.addActionListener(e -> los.run());
        if (f.getEditor().getEditorComponent() instanceof JTextField feld) feld.addActionListener(e -> los.run());   // Enter
        JButton zeigen = Holzstil.kleinerKnopf(Sprache.t("Show", "Anzeigen"),
                Sprache.t("Shows the current setting (changes nothing).", "Zeigt, wie es gerade eingestellt ist (ändert nichts)."));
        zeigen.addActionListener(e -> schicken(anzeigeBefehl(befehl), Sprache.t("Settings " + titel, "Einstellungen " + titel), titel, true, rueck));
        p.add(l);
        p.add(f);
        p.add(setzen);
        p.add(zeigen);
        if (wert != null) p.add(wertSchild(wert));
        // Kurz in der Zeile, der ganze Hinweis beim Zeigen mit der Maus.
        // Mit "jetzt: ..." ist in der Zeile kein Platz mehr fuer das Beispiel -- es steht in der Liste und im Hinweis.
        String kurzText = wert != null || beispiel == null || beispiel.isEmpty() ? "" : Sprache.t("e.g. " + beispiel, "z. B. " + beispiel);
        JLabel kurz = Holzstil.hinweis(kurzText);
        kurz.setToolTipText(hinweis);
        p.add(kurz);
        // F25: eine falsche Eingabe wird rot und sagt, was erwartet wird -- noch bevor man "Setzen" drueckt (Setzen bleibt frei)
        if (f.getEditor().getEditorComponent() instanceof JTextField feld) {
            Color normal = feld.getForeground();
            boolean[] zeigtFehler = {false};
            feld.getDocument().addDocumentListener(dokument(() -> {
                java.util.Optional<String> fehler = Eingabepruefung.fehler(befehl, feld.getText());
                feld.setForeground(fehler.isPresent() ? Holzstil.FEHLER : normal);
                // Der Satz steht in der Zeile UNTER der Zeile (dort, wo sonst "Geklappt" steht) -- in der Zeile selbst ist kein Platz
                if (fehler.isPresent()) {
                    rueck.setIcon(null);
                    rueck.setText(fehler.get());
                    rueck.setForeground(Holzstil.FEHLER);
                    rueck.setToolTipText(null);
                    rueck.setVisible(true);
                    zeigtFehler[0] = true;
                } else if (zeigtFehler[0]) {
                    rueck.setText(" ");
                    rueck.setVisible(false);
                    zeigtFehler[0] = false;
                }
            }));
        }
        return mitRueckmeldung(p, rueck);
    }

    /**
     * Autor 24.10. (F25): Eine Zeile fuer eine ganze Zahl in festem Bereich -- als Drehfeld. Es startet mit dem aktuellen
     * Wert des Bots; "Setzen" schickt ihn. Falsch eintippen ist nicht moeglich.
     */
    private JComponent zahlenZeile(String titel, String hinweis, String befehl, String beispiel,
                                   Einstellungswerte.Wert wert, Eingabepruefung.Zahlenbereich z) {
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        p.putClientProperty(INFO_TITEL, titel);
        p.putClientProperty(WERT, wert);
        p.add(infoKnopf(p, titel));
        JLabel l = Holzstil.beschriftung(titel, BESCHRIFTUNG);
        l.setToolTipText(hinweis);
        int start = z.min();
        try {
            String roh = wert.aktuellRoh();
            if (roh != null) start = Integer.parseInt(roh.strip());
        } catch (NumberFormatException ignored) {
            // dann der kleinste Wert
        }
        SpinnerNumberModel modell = new SpinnerNumberModel(Math.max(z.min(), start), z.min(), Math.max(z.max(), start), z.schritt());
        JSpinner feld = Holzstil.zahlenfeld(modell, 90);
        feld.setToolTipText(hinweis + Sprache.t("  (" + z.min() + " to " + z.max() + ")", "  (" + z.min() + " bis " + z.max() + ")"));
        JButton setzen = Holzstil.kleinerKnopf(Sprache.t("Set", "Setzen"), Sprache.t("Sends " + befehl + " <number>", "Schickt " + befehl + " <Zahl>"), Symbol.Art.HAKEN);
        JLabel rueck = Rueckmeldung.label();
        setzen.addActionListener(e -> {
            try {
                feld.commitEdit();   // was getippt, aber noch nicht bestaetigt ist, zaehlt
            } catch (java.text.ParseException ignored) {
                // gilt der letzte gueltige Wert
            }
            schicken(befehl + " " + modell.getNumber().intValue(), Sprache.t("Settings " + titel, "Einstellungen " + titel), titel, false, rueck);
        });
        p.add(l);
        p.add(feld);
        p.add(setzen);
        p.add(wertSchild(wert));
        JLabel kurz = Holzstil.hinweis(Sprache.t(z.min() + " to " + z.max(), z.min() + " bis " + z.max()));
        kurz.setToolTipText(hinweis);
        p.add(kurz);
        return mitRueckmeldung(p, rueck);
    }

    /**
     * Schickt einen Einstellungs-Befehl und schreibt hinter die Knoepfe, ob es geklappt hat (Autor 15.10.).
     * @param abfrage true = nur nachsehen (Stand/Anzeigen)
     */
    private void schicken(String befehl, String quelle, String erklaerung, boolean abfrage, JLabel rueck) {
        Rueckmeldung.warten(rueck);
        // Autor 24.10. (F27): der Wert VOR einer Aenderung -- damit "Letzte Aenderung rueckgaengig" ihn wiederherstellen kann
        Einstellungswerte.Wert wert = abfrage ? null : Einstellungswerte.finde(befehl);
        String vorher = wert == null ? null : wert.aktuellRoh();
        Consumer<Rueckmeldung.Ergebnis> a = e -> {
            Rueckmeldung.zeigen(rueck, e);
            if (wert != null) nachAenderung(wert, vorher, erklaerung, e);
            else werteAktualisieren();
        };
        if (Befehlsbau.gefaehrlich(befehl)) steuer.sendenMitNachfrage(befehl, quelle, erklaerung, a);
        else steuer.senden(befehl, quelle, abfrage, a);
    }

    /** Was "Anzeigen" schickt: der Befehl ohne Wert -- ausser "!wach", das ohne Wert alle 10 Minuten einschaltet. */
    static String anzeigeBefehl(String befehl) {
        return befehl.equals("!wach") ? "!wach stand" : befehl;
    }

    /** Passende Werte je Einstellung (Autor 12.10.); sonst aus dem Hinweis "z. B. a · b · c" bzw. "a oder b" gelesen. */
    private static final Map<String, String[]> OPTIONEN = Map.ofEntries(
            Map.entry(Sprache.t("Titan event: stop at depth (m)", "Titan-Event: aufhoeren bei Tiefe (m)"), new String[]{Sprache.t("off", "aus"), "150000", "200000", "300000"}),
            Map.entry(Sprache.t("Payment per mode", "Bezahlart je Modus"), new String[]{"verlies tickets", "verlies food", "kathedrale alles", "kathedrale food",
                    "kathedrale schluessel", "arena tickets", "arena food", "verlies food+chests", "kathedrale food+chests", "arena food+chests"}),
            Map.entry(Sprache.t("Food limit", "Nahrungs-Limit"), new String[]{"verlies aus", "kathedrale 30m", "kathedrale 15m", "kathedrale aus",
                    "arena 500k", "arena aus"}),
            Map.entry(Sprache.t("Fill food up to (storage)", "Nahrung auffüllen bis (Lager)"), new String[]{"500000", "1000000", "1500000", "2000000"}),
            Map.entry(Sprache.t("Food chests per event visit", "Nahrungskisten je Event-Gang"), new String[]{"10", "25", "50", "999"}),
            Map.entry(Sprache.t("Titan tower", "Titanen-Turm"), new String[]{"an", "aus", "easy 20", "hard 10", "veryhard 10"}),
            Map.entry(Sprache.t("Chest on/off", "Kiste an/aus"), new String[]{"holz aus", "holz an", "eisen aus", "eisen an", "mana aus", "mana an"}),
            Map.entry(Sprache.t("Event automation", "Event-Automatik"), new String[]{"an", "aus", "titan", "hammer", "25"}),
            Map.entry(Sprache.t("Hammer event every N cathedrals", "Hammer-Event alle N Kathedralen"), new String[]{"50", "100", "250", "500", "0"}),
            Map.entry(Sprache.t("Titan event every N cathedrals", "Titan-Event alle N Kathedralen"), new String[]{"50", "100", "250", "500", "0"}),
            Map.entry(Sprache.t("Hammer event: at most N stages per visit", "Hammer-Event: hoechstens N Stages je Gang"), new String[]{"0", "5", "10", "25", "50"}),
            Map.entry("Pass", new String[]{"gold", "silber", "kein"}),
            Map.entry(Sprache.t("Magnifiers per field", "Lupen je Feld"), new String[]{"3", "5", "10"}),
            Map.entry(Sprache.t("Game restart", "Spiel-Neustart"), new String[]{"an", "aus", "0.75", "1.5", "2", "jetzt"}),
            Map.entry(Sprache.t("Ticket reserve", "Ticket-Reserve"), new String[]{"0", "5", "10", "20"}),
            Map.entry(Sprache.t("Food reserve arena", "Nahrungs-Reserve Arena"), new String[]{"aus", "500k", "1m", "5m"}),
            Map.entry(Sprache.t("Max price arena", "Hoechstpreis Arena"), new String[]{"aus", "100k", "150k", "200k"}),
            Map.entry(Sprache.t("Food limit arena", "Nahrungs-Limit Arena"), new String[]{"aus", "500k", "1m"}),
            // Autor 14.10.: "die groessten 30 Zonen der Welt; selber reinschreiben soll auch gehen" -- Eintippen geht
            // (Stadt, "Europe/Berlin" oder "+2"); der Bot erkennt jede existierende Zone, sonst sagt er es.
            Map.entry(Sprache.t("Time zone", "Zeitzone"), new String[]{"Europe/Berlin", "Europe/London", "Europe/Paris", "Europe/Madrid", "Europe/Rome",
                    "Europe/Warsaw", "Europe/Kyiv", "Europe/Istanbul", "Europe/Moscow", "Asia/Dubai", "Asia/Tehran",
                    "Asia/Karachi", "Asia/Kolkata", "Asia/Dhaka", "Asia/Bangkok", "Asia/Jakarta", "Asia/Shanghai", "Asia/Manila",
                    "Asia/Tokyo", "Asia/Seoul", "Australia/Sydney", "Africa/Cairo", "Africa/Lagos", "Africa/Johannesburg",
                    "America/Sao_Paulo", "America/New_York", "America/Chicago", "America/Mexico_City", "America/Bogota",
                    "America/Los_Angeles"}),
            Map.entry(Sprache.t("Food limit (all modes)", "Nahrungs-Limit (alle Modi)"), new String[]{"aus", "15m", "30m", "50m"}),
            Map.entry(Sprache.t("Emergency key", "Not-Aus-Taste"), new String[]{"F9", "F8", "F12", "aus"}),
            Map.entry(Sprache.t("Hero: points main/side", "Held: Punkte Haupt/Neben"), new String[]{"12 4", "10 3", "15 5"}),
            Map.entry(Sprache.t("Your player name", "Dein Spielername"), new String[0]),
            Map.entry(Sprache.t("PC off at", "PC aus um"), new String[]{"06:00", "07:00", "08:00", "jetzt", "weg"}),
            Map.entry(Sprache.t("Bot stops by itself at", "Bot hört von selbst auf um"), new String[]{"06:30", "08:00", "aus"}),
            Map.entry(Sprache.t("Keep awake / photo every", "Wach halten / Foto alle"), new String[]{"5", "10", "30", "aus"}),
            Map.entry(Sprache.t("Key", "Taste"), new String[]{"F9", "F8", "aus"}));

    static String[] optionen(String titel, String hinweis, String beispiel) {
        String[] fest = OPTIONEN.get(titel);
        if (fest != null) return fest;
        java.util.LinkedHashSet<String> l = new java.util.LinkedHashSet<>();
        String h = hinweis == null ? "" : hinweis.replace("z. B.", "").replace("z.B.", "").replace("e.g.", "");
        for (String teil : h.split("·|\\boder\\b|\\.")) {
            String t = teil.strip();
            if (!t.isEmpty() && t.length() <= 24 && !t.contains("(") && !t.contains(")")) l.add(t);
        }
        if (beispiel != null && !beispiel.isBlank()) l.add(beispiel.strip());
        return l.toArray(new String[0]);
    }

    /** An / Aus / Stand fuer "!settings <bereich> an|aus". */
    private JComponent schalter(String titel, String bereich) {
        return befehlSchalter(titel, "!settings " + bereich + " an", "!settings " + bereich + " aus", "!settings " + bereich);
    }

    private JComponent befehlSchalter(String titel, String an, String aus, String stand) {
        return befehlSchalter(titel, new String[][]{{Sprache.t("On", "An"), an}, {Sprache.t("Off", "Aus"), aus}, {Sprache.t("Status", "Stand"), stand}});
    }

    /** Beschriftung und beliebige Knoepfe {Text, Befehl} -- z. B. "Nahrung benutzen: Ja / Nein / Stand". */
    private JComponent befehlSchalter(String titel, String[][] knoepfe) {
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        p.putClientProperty(INFO_TITEL, titel);
        p.add(infoKnopf(p, titel));
        p.add(Holzstil.beschriftung(titel, BESCHRIFTUNG));
        JLabel rueck = Rueckmeldung.label();
        Einstellungswerte.Wert wert = null;
        for (String[] k : knoepfe) {
            boolean abfrage = istAbfrage(k[0]);
            JButton b = Holzstil.kleinerKnopf(k[0], abfrage
                    ? Sprache.t("Shows the current setting (changes nothing).", "Zeigt, wie es gerade eingestellt ist (ändert nichts).")
                    : Sprache.t("Sends " + k[1], "Schickt " + k[1]));
            b.addActionListener(e -> schicken(k[1], Sprache.t("Settings " + titel, "Einstellungen " + titel), titel + ": " + k[0], abfrage, rueck));
            p.add(b);
            if (wert == null) wert = Einstellungswerte.finde(k[1]);
        }
        // Autor 24.10. (F26): der aktuelle Stand steht gleich hinter den Knoepfen
        if (wert != null) {
            p.putClientProperty(WERT, wert);
            p.add(wertSchild(wert));
        }
        return mitRueckmeldung(p, rueck);
    }

    /** "Stand", "Anzeigen", "... zeigen": nur nachsehen, nichts aendern. */
    private static boolean istAbfrage(String knopf) {
        String k = knopf.toLowerCase(java.util.Locale.ROOT);
        return k.equals("stand") || k.equals("anzeigen") || k.contains("zeigen") || k.equals("was ist neu")
                || k.equals("status") || k.startsWith("show") || k.equals("what's new");
    }

    /** Knopf-Reihe {Text, Befehl}; {@code extra} kommt hinten mit in die Reihe. */
    /**
     * Autor 24.10.: Knoepfe fuer die Arena-Gegnerlisten (templates\arena\gegner\...): Sicher, Machbar, Ungern, Niemals,
     * Clans. Jeder oeffnet den Ordner im Explorer -- dort Bilder hinein- oder herausziehen.
     */
    private JComponent gegnerOrdnerKnoepfe() {
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        p.add(Holzstil.beschriftung(Sprache.t("Opponent lists:", "Gegner-Listen:"), BESCHRIFTUNG));
        // Autor 24.10.: die Listen liegen sichtbar in "My pictures\Arena lists" (Eigenbilder).
        String[][] ordner = {
                {"My pictures", "*"},
                {Sprache.t("All lists", "Alle Listen"), ""},
                {Sprache.t("Safe", "Sicher"), "ar_sicher_"},
                {Sprache.t("Doable", "Machbar"), "ar_machbar_"},
                {Sprache.t("Avoid", "Ungern"), "ar_ungern_"},
                {Sprache.t("Never (block)", "Niemals (blockieren)"), "ar_block_"},
                {"Clans", "ar_clan_"}};
        for (String[] o : ordner) {
            JButton b = Holzstil.kleinerKnopf(o[0], "*".equals(o[1])
                    ? Sprache.t("Opens your own pictures: arena lists and everything you captured with Capture-Images.",
                            "Öffnet deine eigenen Bilder: Arena-Listen und alles, was du mit Capture-Images aufgenommen hast.")
                    : Sprache.t("Opens the folder with these opponents' name pictures.",
                            "Öffnet den Ordner mit den Namensbildern dieser Gegner."));
            b.addActionListener(e -> {
                java.nio.file.Path basis = de.hustlebot.core.Ordner.basis();
                java.nio.file.Path d = "*".equals(o[1]) ? de.hustlebot.core.Eigenbilder.wurzel(basis)
                        : de.hustlebot.core.Eigenbilder.listenOrdner(basis, o[1]);
                try {
                    java.nio.file.Files.createDirectories(d);
                    Desktop.getDesktop().open(d.toAbsolutePath().toFile());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, Sprache.t("Could not open the folder: ", "Ordner ließ sich nicht öffnen: ")
                            + d.toAbsolutePath());
                }
            });
            p.add(b);
        }
        return p;
    }

    private JComponent knoepfe(String[][] paare, JComponent... extra) {
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        p.add(infoKnopf(p, null));
        JLabel rueck = Rueckmeldung.label();
        for (String[] k : paare) {
            boolean abfrage = istAbfrage(k[0]);
            JButton b = Holzstil.kleinerKnopf(k[0], Sprache.t("Sends " + k[1], "Schickt " + k[1]));
            b.addActionListener(e -> schicken(k[1], Sprache.t("Settings " + k[0], "Einstellungen " + k[0]), k[0], abfrage, rueck));
            p.add(b);
        }
        for (JComponent x : extra) p.add(x);
        return mitRueckmeldung(p, rueck);
    }

    /** Was der Bot beim Team-Rundgang als deinen Namen gelesen hat -- mit seinem eigenen Bild davon (Autor 14.10.). */
    private JComponent spielernameErkannt() {
        JPanel p = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 1));
        String n = de.hustlebot.bot.MeinTeam.spielername();
        p.add(Holzstil.beschriftung(Sprache.t("Detected by the bot", "Vom Bot erkannt"), BESCHRIFTUNG + 28));
        p.add(Holzstil.hinweis(n == null || n.isBlank() ? Sprache.t("nothing yet – “Scan now” reads it", "noch nichts – „Jetzt ansehen“ liest ihn")
                : Sprache.t("“" + n + "”", "„" + n + "“")));
        java.nio.file.Path foto = de.hustlebot.bot.TeamScan.nameFoto();
        if (foto != null) {
            try {
                p.add(new JLabel(new ImageIcon(javax.imageio.ImageIO.read(foto.toFile()))));
            } catch (Exception ignored) {
                // ohne Bild geht es auch
            }
        }
        return p;
    }

    /** Oeffnet doku\DISCORD.md im Editor (Autor 14.10.: "ohne es selber im Ordner suchen zu muessen"). */
    private void discordAnleitungOeffnen() {
        // Freunde-Bot: die Discord-Schritte stehen in Guide 1 im Bot-Ordner (es gibt kein doku-Verzeichnis).
        java.nio.file.Path d = java.nio.file.Path.of("GUIDE-1-SETUP-EN.txt").toAbsolutePath();
        if (!java.nio.file.Files.exists(d)) {
            JOptionPane.showMessageDialog(this, Sprache.t("The guide is missing: " + d, "Die Anleitung fehlt: " + d),
                    Sprache.t("Discord guide", "Discord-Anleitung"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            new ProcessBuilder("notepad.exe", d.toString()).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Sprache.t("Could not open the editor: " + ex.getMessage() + "\nThe file: " + d,
                    "Konnte den Editor nicht oeffnen: " + ex.getMessage() + "\nDie Datei: " + d),
                    Sprache.t("Discord guide", "Discord-Anleitung"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private JComponent discordZeilen() {
        JPanel p = Holzstil.flaeche(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        JCheckBox an = Holzstil.kaestchen(Sprache.t("Use Discord", "Discord benutzen"), steuer.discordAktiv(), "discord.enabled in config.properties");
        JPasswordField token = Holzstil.geheimfeld(28);
        token.setToolTipText(Sprache.t("The bot token. It is saved in config.properties -- never send that file to anyone.",
                "Der Bot-Token. Steht in config.properties -- diese Datei nie weitergeben."));
        JTextField kanal = Holzstil.feld("", 22);
        kanal.setToolTipText(Sprache.t("The channel number (discord.channelId). Leave empty = keep it.",
                "Die Nummer des Kanals (discord.channelId). Leer lassen = so lassen."));
        JLabel antwort = Holzstil.hinweis(steuer.discordAktiv() ? Sprache.t("Discord is running.", "Discord läuft gerade.")
                : Sprache.t("Discord is off right now.", "Discord ist gerade aus."));
        JButton speichern = Holzstil.kleinerKnopf(Sprache.t("Save", "Speichern"),
                Sprache.t("Writes the values. They count after the next restart of the bot.", "Schreibt die Werte. Gilt nach dem naechsten Neustart des Bots."), Symbol.Art.HAKEN);
        speichern.addActionListener(e -> antwort.setText(steuer.discordEinstellen(an.isSelected(),
                new String(token.getPassword()), kanal.getText())));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; p.add(an, c);
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 1; p.add(Holzstil.beschriftung(Sprache.t("Bot token", "Bot-Token"), 120), c);
        c.gridx = 1; p.add(token, c);
        c.gridx = 0; c.gridy = 2; p.add(Holzstil.beschriftung(Sprache.t("Channel number", "Kanal-Nummer"), 120), c);
        c.gridx = 1; p.add(kanal, c);
        c.gridx = 0; c.gridy = 3; p.add(speichern, c);
        c.gridx = 1; p.add(antwort, c);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 1;
        JButton anleitung = Holzstil.kleinerKnopf(Sprache.t("Open guide", "Anleitung öffnen"),
                Sprache.t("Opens GUIDE-1-SETUP-EN.txt: how to get token and channel (steps 3-5).", "Oeffnet GUIDE-1-SETUP-EN.txt: wie man Token und Kanal bekommt (Schritte 3-5)."), Symbol.Art.LUPE);
        anleitung.addActionListener(e -> discordAnleitungOeffnen());
        p.add(anleitung, c);
        c.gridx = 1;
        p.add(Holzstil.hinweis(Sprache.t("How to get token and channel. Without Discord everything here keeps working.",
                "Wie man Token und Kanal bekommt. Ohne Discord geht alles hier weiter.")), c);
        Holzstil.fueller(p, c, 2, 0);
        return p;
    }

    // ==================================================================
    //  Reiter Tastenkuerzel
    // ==================================================================

    private JComponent kuerzelReiter() {
        JPanel p = reiterInhalt(new BorderLayout(0, Holzstil.LUFT));
        JPanel oben = Holzstil.karte(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT, 0));
        JCheckBox alle = Holzstil.kaestchen(Sprache.t("All shortcuts on", "Alle Tastenkürzel an"), steuer.kuerzel.alleAn(),
                Sprache.t("One switch for all of them.", "Ein Schalter fuer alle zusammen."));
        alle.addActionListener(e -> steuer.kuerzel.setAlleAn(alle.isSelected()));
        JButton vorgaben = Holzstil.kleinerKnopf(Sprache.t("Defaults", "Vorgaben"),
                Sprache.t("All shortcuts back to default (Ctrl+Alt+A = log ...).", "Alle Kuerzel zurueck auf die Vorgabe (Strg+Alt+A = Protokoll ...)."), Symbol.Art.NEUSTART);
        oben.add(alle);
        oben.add(vorgaben);
        oben.add(Holzstil.hinweis(Sprache.t("How to write: join keys with +, e.g. Ctrl+Alt+A, F7. Empty = off.",
                "Schreibweise: Tasten mit + verbinden, z. B. Strg+Alt+A, F7. Leer = aus.")));

        JPanel liste = Holzstil.karte(Sprache.t("Shortcut (the bot keeps running – no pause, no stop)", "Kürzel (der Bot läuft dabei weiter – kein Pause, kein Stopp)"), new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        int y = 0;
        for (Tastenkuerzel.Aktion a : Tastenkuerzel.Aktion.values()) {
            Tastenkuerzel.Eintrag e = steuer.kuerzel.eintrag(a);
            JLabel l = Holzstil.beschriftung(a.anzeige, 220);
            JTextField f = Holzstil.feld(e == null ? "" : e.kombination().anzeige(), 12);
            JCheckBox an = Holzstil.kaestchen(Sprache.t("on", "an"), e == null || e.an(),
                    Sprache.t("Turn this shortcut on or off.", "Dieses Kuerzel einzeln an oder aus."));
            JLabel stand = Holzstil.hinweis(" ");
            JButton speichern = Holzstil.kleinerKnopf(Sprache.t("Save", "Speichern"), Sprache.t("Take over the combination.", "Kombination uebernehmen."), Symbol.Art.HAKEN);
            Runnable los = () -> {
                if (steuer.kuerzel.setzen(a, f.getText())) {
                    Tastenkuerzel.Kombination k = Tastenkuerzel.lesen(f.getText());
                    f.setText(k == null ? "" : k.anzeige());
                    stand.setText(k == null ? Sprache.t("off", "aus") : Tastenkuerzel.imAlltagMoeglich(k)
                            ? Sprache.t("saved – careful: also works while typing in other programs",
                                    "gespeichert – Achtung: wirkt auch beim Tippen in anderen Programmen")
                            : Sprache.t("saved", "gespeichert"));
                    stand.setForeground(k != null && Tastenkuerzel.imAlltagMoeglich(k) ? Holzstil.WARNUNG : Holzstil.LEISE);
                } else {
                    stand.setText(Sprache.t("not possible: unknown key or already taken", "nicht möglich: unbekannte Taste oder schon vergeben"));
                    stand.setForeground(Holzstil.FEHLER);
                }
                anzeigenNachziehen();
            };
            speichern.addActionListener(ev -> los.run());
            f.addActionListener(ev -> los.run());
            an.addActionListener(ev -> steuer.kuerzel.setAn(a, an.isSelected()));
            kuerzelNachziehen.add(() -> {
                Tastenkuerzel.Eintrag jetzt = steuer.kuerzel.eintrag(a);
                if (jetzt != null && !f.hasFocus()) {
                    f.setText(jetzt.kombination().anzeige());
                    an.setSelected(jetzt.an());
                }
            });
            c.gridy = y++;
            c.gridx = 0; liste.add(l, c);
            c.gridx = 1; liste.add(f, c);
            c.gridx = 2; liste.add(an, c);
            c.gridx = 3; liste.add(speichern, c);
            c.gridx = 4; liste.add(stand, c);
        }
        Holzstil.fueller(liste, c, 5, 0);
        vorgaben.addActionListener(e -> {
            steuer.kuerzel.vorgaben();
            alle.setSelected(true);
            anzeigenNachziehen();
        });
        // Autor 14.10.: Anhalten, Pause und Beenden NUR per Taste -- die Not-Aus-Taste steht deshalb hier.
        JPanel unten = untereinander(Holzstil.LUFT_KLEIN,
                bereich(Sprache.t("Stop and pause (always work, even when the game is in front)",
                                "Anhalten und Pause (wirken immer, auch wenn das Spiel vorn ist)"),
                        zeile(Sprache.t("Emergency key", "Not-Aus-Taste"), Sprache.t("e.g. F9 · F8 · off", "z. B. F9 · F8 · aus"), "notaus", "F9")),
                Holzstil.hinweis(Sprache.t("<html>Emergency key: stops the bot at once. <b>F10</b>: pause – press F10 again (or F11): resume. "
                        + "Quit bot: shortcut above (asks first).</html>",
                        "<html>Not-Aus-Taste: hält den Bot sofort an. <b>F10</b>: Pause – noch einmal F10 (oder F11): weiter. "
                        + "Bot beenden: Kürzel oben (fragt erst).</html>")));
        p.add(oben, BorderLayout.NORTH);
        p.add(Holzstil.rollenOhneRahmen(liste), BorderLayout.CENTER);
        p.add(unten, BorderLayout.SOUTH);
        return p;
    }

    // ==================================================================
    //  Reiter Profile
    // ==================================================================

    private JComponent profileReiter() {
        JPanel p = reiterInhalt(new BorderLayout(Holzstil.LUFT, 0));
        DefaultListModel<String> modell = new DefaultListModel<>();
        profilModell = modell;   // F35: "Als Profil speichern" auf der Steuerung traegt hier den neuen Namen ein
        for (String n : steuer.profile.namen()) modell.addElement(n);
        JList<String> liste = Holzstil.liste(modell);
        JPanel links = Holzstil.karte(Sprache.t("Profiles", "Profile"), new BorderLayout());
        JScrollPane linksRollen = Holzstil.rollen(liste);
        linksRollen.setBorder(BorderFactory.createEmptyBorder());
        links.add(linksRollen, BorderLayout.CENTER);
        links.setPreferredSize(new Dimension(220, 100));

        JTextField name = Holzstil.feld("", 16);
        JTextArea befehle = Holzstil.lesebereich();
        befehle.setEditable(true);
        befehle.setFont(Holzstil.FEST);
        befehle.setLineWrap(false);
        JLabel stand = Holzstil.hinweis(Sprache.t("Pick a profile on the left or enter a new one on the right.", "Links ein Profil wählen oder rechts ein neues eintragen."));

        liste.addListSelectionListener(e -> {
            String n = liste.getSelectedValue();
            if (n == null) return;
            name.setText(n);
            befehle.setText(steuer.profile.alsText(n));
            int nr = modell.indexOf(n) + 1;
            stand.setText(nr <= 3 ? Sprache.t("Profile " + nr + " – shortcut: " + kuerzelText(Tastenkuerzel.Aktion.valueOf("PROFIL_" + nr)),
                    "Profil " + nr + " – Kürzel: " + kuerzelText(Tastenkuerzel.Aktion.valueOf("PROFIL_" + nr))) : " ");
        });
        JButton speichern = Holzstil.kleinerKnopf(Sprache.t("Save", "Speichern"), Sprache.t("Creates the profile or replaces it.", "Legt das Profil an oder ersetzt es."), Symbol.Art.HAKEN);
        speichern.addActionListener(e -> {
            String n = name.getText().trim();
            if (!steuer.profile.setzen(n, List.of(befehle.getText().split("\n")))) {
                stand.setText(Sprache.t("Please enter a name.", "Bitte einen Namen eintragen."));
                return;
            }
            if (!modell.contains(n)) modell.addElement(n);
            liste.setSelectedValue(n, true);
            stand.setText(Sprache.t("Saved: " + n, "Gespeichert: " + n));
        });
        JButton loeschen = Holzstil.kleinerKnopf(Sprache.t("Delete", "Löschen"), Sprache.t("Removes the selected profile.", "Entfernt das gewaehlte Profil."), Symbol.Art.KREUZ);
        loeschen.addActionListener(e -> {
            String n = liste.getSelectedValue();
            if (n == null) return;
            if (steuer.profile.loeschen(n)) {
                modell.removeElement(n);
                name.setText("");
                befehle.setText("");
                stand.setText(Sprache.t("Deleted: " + n, "Gelöscht: " + n));
            }
        });
        JButton anwenden = Holzstil.knopf(Sprache.t("Apply", "Anwenden"),
                Sprache.t("Sends all commands of the profile one after another.", "Schickt alle Befehle des Profils der Reihe nach."), Holzstil.GUT, Symbol.Art.START);
        anwenden.addActionListener(e -> {
            String n = liste.getSelectedValue();
            if (n != null) steuer.profilAnwenden(n, Sprache.t("Button", "Knopf"));
        });

        JPanel rechts = Holzstil.karte(Sprache.t("Commands of the profile", "Befehle des Profils"), new BorderLayout(0, Holzstil.LUFT_KLEIN));
        JPanel kopf = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        kopf.add(Holzstil.beschriftung("Name"));
        kopf.add(name);
        kopf.add(speichern);
        kopf.add(loeschen);
        kopf.add(anwenden);
        rechts.add(kopf, BorderLayout.NORTH);
        rechts.add(Holzstil.rollen(befehle), BorderLayout.CENTER);
        JPanel fuss = Holzstil.flaeche(new GridLayout(0, 1));
        fuss.add(stand);
        fuss.add(Holzstil.hinweis(Sprache.t("One line per command, like in Discord: !settings meldungen 10, !mute … The first three profiles have shortcuts (tab Shortcuts).",
                "Eine Zeile je Befehl, wie in Discord: !settings meldungen 10, !mute … Die ersten drei Profile haben Kürzel (Reiter Tastenkürzel).")));
        rechts.add(fuss, BorderLayout.SOUTH);

        p.add(links, BorderLayout.WEST);
        p.add(rechts, BorderLayout.CENTER);
        if (!modell.isEmpty()) liste.setSelectedIndex(0);
        ziele.put("profile", p);   // Tutorial 24.10.
        return p;
    }

    // ==================================================================
    //  Reiter Support
    // ==================================================================

    // ==================================================================
    //  Reiter Werkzeuge (Autor 13.10., Punkt 13: Aufnahme-Programm, PC-Check, PC-Reiniger im Programm)
    // ==================================================================

    private JComponent werkzeugeReiter() {
        JPanel p = reiterInhalt(new BorderLayout(0, Holzstil.LUFT));
        JPanel karte = Holzstil.karte(Sprache.t("Tools (open in their own window)", "Werkzeuge (öffnen sich in einem eigenen Fenster)"), new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 10);
        c.anchor = GridBagConstraints.WEST;
        int y = 0;
        y = werkzeug(karte, c, y, Sprache.t("Capture program", "Aufnahme-Programm"), Sprache.t("Take pictures from the game (buttons, opponents, teammates) – same as the desktop icon \"Capture images\".",
                        "Bilder aus dem Spiel aufnehmen (Knöpfe, Gegner, Mitspieler) – wie das Desktop-Symbol \"Capture images\"."),
                // Freunde-Bot: das Aufnahme-Programm ist Capture-Images.exe im Bot-Ordner (capture.bat gibt es nur im Hauptbot).
                () -> batStarten(de.hustlebot.core.Ordner.bei("Capture-Images.exe").toAbsolutePath(), Sprache.t("Capture program", "Aufnahme-Programm")));
        y = werkzeug(karte, c, y, "PC-Check", Sprache.t("Checks whether the PC suits the bot (Java, screen, game). Changes nothing.",
                        "Prüft, ob der Rechner zum Bot passt (Java, Bildschirm, Spiel). Ändert nichts."),
                this::pcCheck);
        // Autor 14.10., Punkt 18: der PC-Reiniger vom 07.10. ins Programm (fragt vor jedem Schritt, legt erst einen
        // Wiederherstellungspunkt an, fasst persoenliche Dateien und den Bot nie an).
        y = werkzeug(karte, c, y, Sprache.t("PC cleaner", "PC-Reiniger"), Sprache.t("Cleans up the PC (junk files, autostart, slowdowns) – asks before each step, "
                + "creates a restore point first.", "Räumt den PC auf (Datenmüll, Autostart, Bremsen) – fragt vor jedem Schritt, "
                + "legt vorher einen Wiederherstellungspunkt an."), this::pcReiniger);
        java.nio.file.Path aufraeumen = java.nio.file.Path.of("pc-aufraeumen.bat").toAbsolutePath();
        if (java.nio.file.Files.exists(aufraeumen)) {
            werkzeug(karte, c, y, Sprache.t("Clean up PC", "PC aufräumen"), Sprache.t("Uninstalls the programs from pc-aufraeumen.bat – each one asks by itself.",
                    "Deinstalliert die Programme aus pc-aufraeumen.bat – jedes fragt selbst nach."), () -> {
                if (steuer.fragen(Sprache.t("Start cleaning up the PC?\n\nEach program asks again by itself when uninstalling.",
                        "PC aufräumen starten?\n\nJedes Programm fragt beim Deinstallieren selbst noch einmal nach."),
                        Sprache.t("Clean up PC", "PC aufräumen"))) batStarten(aufraeumen, Sprache.t("Clean up PC", "PC aufräumen"));
            });
        }
        Holzstil.fueller(karte, c, 3, 0);
        ziele.put("werkzeuge", karte);
        p.add(untereinander(karte), BorderLayout.NORTH);
        return p;
    }

    private int werkzeug(JPanel karte, GridBagConstraints c, int y, String titel, String text, Runnable los) {
        JButton b = Holzstil.knopf(titel, text, Symbol.Art.START);
        b.addActionListener(e -> los.run());
        c.gridx = 0; c.gridy = y; c.gridwidth = 1;
        karte.add(b, c);
        c.gridx = 1;
        karte.add(Holzstil.hinweis(text), c);
        return y + 1;
    }

    /** Eine .bat-Datei in einem eigenen Konsolenfenster starten (der Bot laeuft weiter). */
    private void batStarten(java.nio.file.Path bat, String titel) {
        if (!java.nio.file.Files.exists(bat)) {
            JOptionPane.showMessageDialog(this, Sprache.t("Not found: " + bat, "Nicht gefunden: " + bat), titel, JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "\"" + titel + "\"", "/d", bat.getParent().toString(), bat.toString()).start();
            Log.info("Steuerprogramm", "Werkzeug gestartet: " + titel);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Sprache.t("Could not start " + titel + ": " + ex.getMessage(), "Konnte " + titel + " nicht starten: " + ex.getMessage()), titel, JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Der PC-Check steckt im Programm (weniger einzelne Dateien) -- er wird in einen eigenen Ordner gelegt und gestartet. */
    /** Freunde-Bot: PC-Check.bat liegt im Bot-Ordner (Paket), nicht im Programm wie beim Hauptbot. */
    private void pcCheck() {
        batStarten(de.hustlebot.core.Ordner.bei("PC-Check.bat").toAbsolutePath(), "PC-Check");
    }

    /** Der PC-Reiniger steckt im Programm: Dateien nach logs\pc-reiniger legen und die .bat starten (fragt nach Admin-Rechten). */
    private void pcReiniger() {
        if (!steuer.fragen(Sprache.t("Start the PC cleaner?\n\nWindows will ask for admin rights. Before each step the "
                + "cleaner asks again by itself; first it creates a restore point.",
                "PC-Reiniger starten?\n\nWindows fragt gleich nach Admin-Rechten. Vor jedem Schritt fragt der "
                + "Reiniger selbst noch einmal, vorher legt er einen Wiederherstellungspunkt an."), Sprache.t("PC cleaner", "PC-Reiniger"))) return;
        // Freunde-Bot: der Reiniger liegt als PC-Cleaner\PC-Cleaner.bat im Bot-Ordner (ReinigerNachschub).
        batStarten(de.hustlebot.core.Ordner.bei("PC-Cleaner").resolve("PC-Cleaner.bat").toAbsolutePath(),
                Sprache.t("PC cleaner", "PC-Reiniger"));
    }

    /** Ziel fuers Tutorial: genauer Schluessel oder der erste Bereich, dessen Titel so anfaengt. */
    JComponent ziel(String schluessel) {
        JComponent z = ziele.get(schluessel);
        if (z != null) return z;
        for (java.util.Map.Entry<String, JComponent> e : ziele.entrySet()) if (e.getKey().startsWith(schluessel)) return e.getValue();
        // Englische Fenster: die Bereichs-Titel sind uebersetzt, das Tutorial sucht mit den deutschen Anfaengen.
        String en = ZIEL_EN.get(schluessel);
        if (en != null) for (java.util.Map.Entry<String, JComponent> e : ziele.entrySet()) if (e.getKey().startsWith(en)) return e.getValue();
        return null;
    }

    /** Deutsche Tutorial-Schluessel -> Anfang des englischen Bereichs-Titels. */
    private static final Map<String, String> ZIEL_EN = Map.ofEntries(Map.entry("Nahrung", "Food"), Map.entry("Nebenbei", "On the side"),
            Map.entry("Truppe", "Squad"), Map.entry("Event-Automatik", "Event automation"), Map.entry("Hammer-Event", "Hammer event"),
            Map.entry("Titan-Event", "Titan event"), Map.entry("Zeitzone", "Time zone"), Map.entry("Eigenes Team", "Your team"),
            Map.entry("Sprache", "Language"), Map.entry("Ton", "Sound"),
            Map.entry("Autostart", "Autostart"), Map.entry("Discord", "Discord"), Map.entry("Maus", "Mouse"), Map.entry("Arena", "Arena"),
            Map.entry("Spielkonten", "Game accounts"), Map.entry("Mehr", "More"), Map.entry("Anhalten", "Stop and pause"));

    /** Pip zeigt auf etwas: leuchtender Rahmen ueber dem Ziel (null = keine Markierung). */
    void markieren(JComponent z) {
        markierung.setZiel(z);
        if (z != null) SwingUtilities.invokeLater(() -> z.scrollRectToVisible(new Rectangle(0, 0, z.getWidth(), z.getHeight())));
    }

    /** Durchsichtige Ebene ueber dem Fenster; malt einen pulsierenden goldenen Rahmen um das Ziel. Klicks gehen durch. */
    private static final class Markierung extends JComponent {
        private JComponent ziel;
        private final Timer puls = new Timer(50, e -> repaint());

        void setZiel(JComponent z) {
            ziel = z;
            setVisible(z != null);
            if (z != null) puls.start(); else puls.stop();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (ziel == null || !ziel.isShowing()) return;
            Rectangle r = SwingUtilities.convertRectangle(ziel.getParent(), ziel.getBounds(), this);
            Graphics2D g = Holzstil.weich(g0);
            int a = (int) (150 + 100 * Math.sin(System.currentTimeMillis() / 180.0));
            g.setStroke(new BasicStroke(4));
            g.setColor(new Color(255, 196, 30, Math.max(40, Math.min(255, a))));
            g.drawRoundRect(r.x - 5, r.y - 5, r.width + 10, r.height + 10, 14, 14);
            g.dispose();
        }
    }

    private JComponent supportReiter() {
        JPanel p = reiterInhalt(new BorderLayout(0, Holzstil.LUFT));
        JPanel kontakt = Holzstil.karte("Support", new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 16);
        c.anchor = GridBagConstraints.WEST;
        // Offene Fassung: Fragen und Fehler ueber die Issues im GitHub-Projekt.
        c.gridx = 0; c.gridy = 0; kontakt.add(Holzstil.beschriftung("GitHub"), c);
        c.gridx = 1; kontakt.add(gross("github.com/Hustle-Bot/hustle-castle-bot  (Issues)"), c);
        Holzstil.fueller(kontakt, c, 2, 0);

        JPanel hilfe = Holzstil.karte(Sprache.t("If something goes wrong", "Bei einem Fehler"), new BorderLayout(0, Holzstil.LUFT_KLEIN));
        JTextArea t = Holzstil.lesebereich();
        t.setOpaque(false);
        t.setMargin(new Insets(0, 0, 0, 0));
        t.setText(Sprache.t("Press “Create error report” below. It makes a folder with a screenshot, the log and the last "
                + "messages and opens it – just send the folder along.\n\n"
                + "Guides are in the bot folder: GUIDE-1-SETUP-EN.txt to GUIDE-7-HELP-EN.txt.",
                "Unten „Fehlerbericht anlegen“ drücken. Das legt einen Ordner mit Bildschirmfoto, Protokoll und den letzten "
                + "Meldungen an und öffnet ihn – den Ordner einfach mitschicken.\n\n"
                + "Anleitungen liegen im Bot-Ordner: ANLEITUNG.txt (Englisch: GUIDE-1 bis GUIDE-7)."));
        hilfe.add(t, BorderLayout.CENTER);
        JLabel antwort = Holzstil.hinweis(" ");
        JButton bericht = Holzstil.knopf(Sprache.t("Create error report", "Fehlerbericht anlegen"),
                Sprache.t("Screenshot + log + messages into one folder.", "Bildschirmfoto + Protokoll + Meldungen in einen Ordner."), Symbol.Art.ORDNER);
        bericht.addActionListener(e -> {
            String a = steuer.fehlerberichtAnlegen();
            antwort.setText(a.length() > 110 ? a.substring(0, 108) + "…" : a);
            antwort.setToolTipText(a);
        });
        JButton logs = Holzstil.kleinerKnopf(Sprache.t("Open log folder", "Protokoll-Ordner öffnen"), Sprache.t("Opens the folder logs.", "Oeffnet den Ordner logs."), Symbol.Art.ORDNER);
        logs.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(java.nio.file.Path.of("logs").toAbsolutePath().toFile());
            } catch (Exception ex) {
                antwort.setText(Sprache.t("Could not open the folder: " + ex.getMessage(), "Ordner ließ sich nicht öffnen: " + ex.getMessage()));
            }
        });
        // 16.10.: Bericht als ZIP -- ein Klick, ohne Discord. Wird nur lokal gespeichert (logserichte).
        JButton zipBericht = Holzstil.knopf(Sprache.t("Save error report (ZIP)", "Fehlerbericht speichern (ZIP)"),
                Sprache.t("Log, a picture of the game and your settings (never the token) as a ZIP in logs\\berichte -- nothing is sent.",
                          "Protokoll, ein Bild vom Spiel und deine Einstellungen (nie der Token) als ZIP in logs\\berichte -- nichts wird verschickt."),
                Symbol.Art.HAKEN);
        zipBericht.addActionListener(e -> steuer.berichtDialog(""));
        JPanel unten = Holzstil.flaeche(new BorderLayout(0, Holzstil.LUFT_KLEIN));
        JPanel kn = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        kn.add(zipBericht);
        kn.add(bericht);
        kn.add(logs);
        unten.add(kn, BorderLayout.NORTH);
        unten.add(antwort, BorderLayout.SOUTH);
        hilfe.add(unten, BorderLayout.SOUTH);

        p.add(untereinander(kontakt, hilfe), BorderLayout.NORTH);
        ziele.put("support", p);   // Tutorial 24.10.
        return p;
    }

    private static JLabel gross(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Holzstil.GROSS);
        l.setForeground(Holzstil.TEXT);
        return l;
    }

    // ==================================================================
    //  Jede Sekunde
    // ==================================================================

    private int takte = 0;

    /** Autor 24.10. (F28): "Jetzt: Kathedrale 12/100 -> 08:00 Leuchtturm -> 09:20 Clan-Krieg ..." neu schreiben. */
    private void zeitleisteAktualisieren() {
        try {
            java.time.LocalDateTime jetzt = de.hustlebot.core.Zeitzone.jetzt();
            BotEngine.State z = steuer.zustand();
            String laeuft = z == BotEngine.State.LAEUFT || z == BotEngine.State.PAUSIERT ? steuer.statusText() : "";
            String text = Zeitleiste.text(jetzt, laeuft, Zeitleiste.termine(jetzt));
            if (!text.equals(zeitleiste.getText())) zeitleiste.setText(text);
        } catch (RuntimeException e) {
            Log.debug("Steuerprogramm", "Zeitleiste: " + e.getMessage());
        }
    }

    private void aktualisieren() {
        try {
            zustand.setText(steuer.zustandszeile());
            BotEngine.State z = steuer.zustand();
            pille.setzen(z);
            // Autor 24.10. (F26): die Werte in den Einstellungen alle 2 Sekunden auffrischen -- nur, solange man sie sieht
            // (eine Aenderung per Discord oder Tastenkuerzel erscheint so auch von selbst).
            if (++takte % 2 == 0 && isShowing() && reiter.getSelectedIndex() == REITER_EINSTELLUNGEN) werteAktualisieren();
            // Autor 24.10. (F28): die Zeitleiste alle 5 Sekunden, solange man die Steuerung sieht
            if (takte % 5 == 0 && isShowing() && reiter.getSelectedIndex() == REITER_STEUERUNG) zeitleisteAktualisieren();
        } catch (Exception e) {
            Log.debug("Steuerprogramm", "Anzeige: " + e.getMessage());
        }
    }

    /** Auf dem Server (ServerSparsam): das Fenster bleibt unsichtbar, der Sekundentakt faellt weg. */
    void ruhe() {
        takt.stop();
    }
}
