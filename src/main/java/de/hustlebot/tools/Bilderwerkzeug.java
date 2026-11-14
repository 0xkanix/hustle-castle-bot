package de.hustlebot.tools;

import de.hustlebot.bot.DungeonMode;
import de.hustlebot.core.Eigenbilder;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;
import de.hustlebot.core.ScreenCapture;
import de.hustlebot.core.Sprache;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Das Werkzeug, mit dem jeder Freund seine eigenen Bilder aufnimmt.
 *
 * ASSISTENT STATT WERKSTATT (Autor, 12.09.: "mach das capture bat einsteiger /
 * beginner freundlicher"). Frueher war das hier ein Fenster mit einer Liste,
 * einem Aufklappmenue und drei Knoepfen -- man musste selbst wissen, in welcher
 * Reihenfolge man was drueckt. Jetzt fuehrt das Fenster durch:
 *
 *     Bild 3 von 15:  Kaempfer 1: Gesicht
 *     [ Was zu tun ist, in einfachen Saetzen ]
 *     [ Bild machen ]  [ Speichern und weiter ]  [ Ueberspringen ]
 *     ############------------  5 von 15 gespeichert
 *
 * Immer nur EIN Bild ist dran. Der Balken zeigt, wie weit man ist, und nach
 * dem Speichern geht es von selbst zum naechsten. Wer schon fertig ist,
 * bekommt das gesagt, statt raten zu muessen.
 *
 * ALLES AUF DEUTSCH (Autors Entscheidung vom 12.09.), passend zu den
 * deutschen Anleitungen -- auch wenn das Spiel selbst englisch ist. Die paar
 * englischen Woerter aus dem Spiel ("Friend", "Drag and drop dwellers")
 * bleiben stehen, damit man sie im Spiel wiederfindet.
 *
 * ANGEBOTEN WIRD NUR, WAS JEDER SELBST AUFNEHMEN MUSS (siehe EIGENE): die
 * eigenen Kaempfer, der eigene Spielername, der Opfer-Kaempfer und die Namen
 * der Freunde. Alles andere -- Knoepfe, Symbole, Ziffern -- liegt fertig im
 * Paket und taucht hier gar nicht auf. Muss doch einmal ein Paketbild ersetzt
 * werden, gibt es dafuer den Knopf "Anderes Bild".
 */
public final class Bilderwerkzeug extends JFrame {

    /** Der eigene Name fuer die Arena. Auch bei einem Schluessel ohne Arena-Teil lesbar (Schutz-Plan 31.10.). */
    private static String arenaName() {
        return de.hustlebot.bot.ArenaMode.bildEigenerName();
    }
    // Windows-Skalierung (16.10.): bei 125/150 % rechnete Java in umgerechneten Punkten, die Fensterposition
    // kommt von Windows aber in echten Pixeln -- Fotos und Klicks lagen daneben, der Bot erkannte nichts (Freundin
    // mit grossem Bildschirm: "es steht, dass er laeuft, aber es passiert nichts"). Darum immer echte Pixel.
    // Muss vor dem ersten Grafik-Zugriff laufen -- deshalb als erstes in der Klasse.
    static {
        System.setProperty("sun.java2d.uiScale", "1");
    }


    private final Path templateDir;
    private final GameWindow window;
    private final ScreenCapture capture;

    private BufferedImage frame;
    private Rectangle selection;
    private Point dragStart;

    // --- Autor 24.10. (Ideenliste F13-F23) -----------------------------------------------------------------------
    private final Beispielbild beispiel = new Beispielbild();                    // F14: Beispielbild je Schritt
    private final JLabel vorschauBild = new JLabel();                            // F17: Ausschnitt in dreifacher Groesse
    private final JLabel pruefung = new JLabel();                                // F13/F17: Ergebnis der Test-Suche
    private final JLabel fotoHinweis = new JLabel();                             // F15/F22: falscher Bildschirm / Fenstergroesse
    private final Bildablage.Journal journal = new Bildablage.Journal();         // F19/F21: Rueckwege
    private final JButton rueckgaengigKnopf = new JButton(Sprache.t("Undo last picture", "Letztes Bild zurücknehmen"));
    private final JCheckBox anpassenBox = new JCheckBox(Sprache.t("Fit picture", "Bild anpassen"));   // F18
    private final JScrollPane bildRolle;
    private JPanel erweitertFeld;                                                // F20: Einfach / Erweitert
    private JButton erweitertKnopf;
    private Ort fotoOrt;                                                         // F16: zu welchem Bildschirm das Foto gehoert
    private boolean ankerFehlt;                                                  // F15: im Foto fehlt das Kennzeichen des Bildschirms
    private volatile int pruefLauf;                                              // F13: verwirft veraltete Ergebnisse
    private boolean pruefungLaeuft;                                              // F23
    private boolean kleinerBildschirm;                                           // F18
    private String anleitungText = "";

    // --- Die Anzeige ---------------------------------------------------
    private final ImagePanel imagePanel = new ImagePanel();
    private final JLabel titel = new JLabel();
    private final JLabel anleitung = new JLabel();
    private final JLabel info = new JLabel();
    private final JProgressBar balken = new JProgressBar();
    private final JButton speichernKnopf = new JButton(Sprache.t("Save and next", "Speichern und weiter"));
    private final JButton zurueckKnopf = new JButton(Sprache.t("< Back", "< Zurück"));
    private final JButton weiterKnopf = new JButton(Sprache.t("Skip >", "Überspringen >"));

    /** Die Bilder in der Reihenfolge, in der der Assistent sie abfragt. */
    private final List<String> reihenfolge = reihenfolgeBauen();

    /** Erst die eigenen Pflichtbilder, danach die freiwilligen Zusatzbilder. */
    private static List<String> reihenfolgeBauen() {
        List<String> r = new ArrayList<>(EIGENE.keySet());
        r.addAll(ZUSATZ.keySet());
        return r;
    }

    /** Bei welchem Bild der Assistent gerade steht. */
    private int schritt = 0;

    public Bilderwerkzeug(Path templateDir, String windowTitle, int w, int h) throws AWTException {
        super(Sprache.t("Capture pictures – Hustle Castle Bot", "Bilder aufnehmen – Hustle Castle Bot"));
        this.templateDir = templateDir;
        this.window = new GameWindow(windowTitle, w, h);
        this.capture = new ScreenCapture();

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        add(baueKopf(), BorderLayout.NORTH);
        bildRolle = new JScrollPane(imagePanel);
        add(bildRolle, BorderLayout.CENTER);
        add(baueFuss(), BorderLayout.SOUTH);

        // Autor 24.10. (F18): Fenstergroesse = Bildschirm minus Taskleiste (hoechstens 1240 x 900). Zuerst, damit die
        // Erklaerung gleich in der richtigen Breite umbricht.
        fensterGroesseSetzen();

        // Beim Start dort anfangen, wo noch etwas fehlt.
        schritt = ersterOffener();
        zeigeSchritt();
        rueckgaengigAktualisieren();

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                anleitungAnzeigen();
            }
        });
        bildRolle.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                imagePanel.revalidate();   // F18: das angepasste Foto folgt der Fenstergroesse
                imagePanel.repaint();
            }
        });
    }

    /**
     * Autor 24.10. (F18): Statt fest 1240 x 900 -- auf einem Laptop mit 1366 x 768 lagen die Knoepfe unten ausserhalb des
     * Bildschirms. Jetzt: nicht groesser als der nutzbare Bildschirm (ohne Taskleiste). Wird das Fenster dadurch kleiner,
     * ist "Bild anpassen" vorgewaehlt (das Foto wird verkleinert gezeigt); auf grossen Bildschirmen bleibt alles wie bisher.
     */
    private void fensterGroesseSetzen() {
        Rectangle nutz = null;
        try {
            nutz = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (Exception ignored) {
            // ohne Messung bleibt es bei der alten Groesse
        }
        if (nutz == null || nutz.width < 600 || nutz.height < 400) {
            setSize(1240, 900);
            setLocationRelativeTo(null);
            return;
        }
        int w = Math.min(1240, nutz.width), h = Math.min(900, nutz.height);
        setSize(w, h);
        setLocation(nutz.x + (nutz.width - w) / 2, nutz.y + (nutz.height - h) / 2);
        kleinerBildschirm = w < 1240 || h < 900;
        if (kleinerBildschirm) {
            beispiel.setPreferredSize(new Dimension(200, 100));
            anpassenBox.setSelected(true);
            anpassenUmstellen();
        }
    }

    // ==================================================================
    //  Aufbau des Fensters
    // ==================================================================

    private JPanel baueKopf() {
        JPanel kopf = new JPanel(new BorderLayout(0, 6));
        kopf.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        titel.setFont(titel.getFont().deriveFont(Font.BOLD, 20f));
        titel.setForeground(new Color(20, 60, 130));

        anleitung.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        anleitung.setVerticalAlignment(SwingConstants.TOP);

        // Autor 24.10. (F14): das Beispielbild rechts neben der Erklaerung
        JPanel beispielFeld = new JPanel(new BorderLayout());
        beispielFeld.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 0));
        beispielFeld.add(beispiel, BorderLayout.CENTER);

        kopf.add(titel, BorderLayout.NORTH);
        kopf.add(anleitung, BorderLayout.CENTER);
        kopf.add(beispielFeld, BorderLayout.EAST);
        return kopf;
    }

    private JPanel baueFuss() {
        JPanel fuss = new JPanel(new BorderLayout(0, 4));
        fuss.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 12));

        JButton bildKnopf = new JButton(Sprache.t("Take picture", "Bild machen"));
        bildKnopf.setToolTipText(Sprache.t("Takes a picture of the Hustle Castle window as it looks right now.", "Fotografiert das Hustle-Castle-Fenster so, wie es gerade aussieht."));
        bildKnopf.addActionListener(e -> fotografiere());

        JButton dateiKnopf = new JButton(Sprache.t("Picture from file…", "Bild aus Datei…"));
        dateiKnopf.setToolTipText(Sprache.t("Uses a screenshot you saved earlier instead of the running game.",
                "Nimmt einen Screenshot, den du vorher gespeichert hast, statt des laufenden Spiels."));
        dateiKnopf.addActionListener(e -> ladeBild());

        JButton anderesKnopf = new JButton(Sprache.t("Other picture…", "Anderes Bild…"));
        anderesKnopf.setToolTipText(Sprache.t("For advanced users: save a picture under a name you type in.",
                "Für Fortgeschrittene: ein Bild unter einem selbst eingetippten Namen speichern."));
        anderesKnopf.addActionListener(e -> anderesBild());

        // Autor 19.10.: Arena-Gegner selbst in die Listen eintragen -- per Rahmen im Bild oder per Eintippen.
        JButton arenaRahmenKnopf = new JButton(Sprache.t("Arena: save name…", "Arena: Name speichern…"));
        arenaRahmenKnopf.setToolTipText(Sprache.t("Drag a frame around a player's name in the arena pyramid, then choose the list "
                + "(doable, safe, never, avoid). Repeat for more names.",
                "Rahmen um den Namen eines Spielers in der Arena-Pyramide ziehen, dann die Liste waehlen "
                + "(machbar, sicher, niemals, meiden). Fuer weitere Namen wiederholen."));
        arenaRahmenKnopf.addActionListener(e -> arenaNameSpeichern());

        JButton arenaTippenKnopf = new JButton(Sprache.t("Arena: type names…", "Arena: Namen eintippen…"));
        arenaTippenKnopf.setToolTipText(Sprache.t("Type one or more player names (comma-separated) and choose the list. The bot cuts "
                + "the names out by itself as soon as it sees them in the arena.",
                "Einen oder mehrere Spielernamen (mit Komma) eintippen und die Liste waehlen. Der Bot schneidet "
                + "die Namen selbst aus, sobald er sie in der Arena sieht."));
        arenaTippenKnopf.addActionListener(e -> arenaNamenEintippen());

        // Autor 22.10.: gute Totems, gute und schlechte Artefakte selbst aufnehmen.
        JButton arenaGegenstandKnopf = new JButton(Sprache.t("Arena: save item…", "Arena: Gegenstand speichern…"));
        arenaGegenstandKnopf.setToolTipText(Sprache.t("In the window of a fighter: drag a tight frame around the totem or artifact "
                + "symbol (only the upper part of the symbol -- without the '40 Max' bar, number and stones), then choose good/bad totem or "
                + "good/bad artifact. With and without rune (glitter) are best saved as two pictures.",
                "Im Fenster eines Kaempfers: engen Rahmen um das Totem- oder Artefakt-Symbol ziehen (nur der obere Teil des Symbols -- ohne "
                + "den Balken '40 Max', Zahl und Steine), dann gutes/schlechtes Totem oder gutes/schlechtes Artefakt waehlen. Mit und ohne "
                + "Rune (Glitzern) am besten als zwei Bilder speichern."));
        arenaGegenstandKnopf.addActionListener(e -> arenaGegenstandSpeichern());

        speichernKnopf.addActionListener(e -> speichern());
        zurueckKnopf.addActionListener(e -> springe(-1));
        weiterKnopf.addActionListener(e -> springe(+1));

        // Autor 24.10. (F19): Rueckgaengig, Liste "Meine Bilder", Selbstpruefung (F23)
        rueckgaengigKnopf.addActionListener(e -> rueckgaengig());
        JButton meineKnopf = new JButton(Sprache.t("My pictures…", "Meine Bilder…"));
        meineKnopf.setToolTipText(Sprache.t("Shows everything you captured, with a preview. You can remove pictures there (nothing is deleted for good).",
                "Zeigt alles, was du aufgenommen hast, mit Vorschau. Dort kannst du Bilder entfernen (nichts wird endgültig gelöscht)."));
        meineKnopf.addActionListener(e -> meineBilder());
        JButton pruefKnopf = new JButton(Sprache.t("Check all my pictures…", "Alle meine Bilder prüfen…"));
        pruefKnopf.setToolTipText(Sprache.t("Takes a fresh picture of the game and checks which of your pictures can be found in it. "
                + "Open the screen of the pictures you want to check first.",
                "Macht ein frisches Foto vom Spiel und prüft, welche deiner Bilder darin gefunden werden. "
                + "Öffne vorher den Bildschirm der Bilder, die du prüfen willst."));
        pruefKnopf.addActionListener(e -> allePruefen());

        // F18: Foto an das Fenster anpassen
        anpassenBox.setToolTipText(Sprache.t("Shows the picture smaller so that it fits the window (for small screens).",
                "Zeigt das Foto verkleinert, damit es ins Fenster passt (für kleine Bildschirme)."));
        anpassenBox.addActionListener(e -> anpassenUmstellen());

        // F20: Hauptreihe nur mit dem Noetigen -- alles andere hinter "Erweitert"
        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.add(zurueckKnopf);
        knoepfe.add(bildKnopf);
        knoepfe.add(speichernKnopf);
        knoepfe.add(weiterKnopf);
        knoepfe.add(rueckgaengigKnopf);
        knoepfe.add(anpassenBox);
        erweitertKnopf = new JButton(Sprache.t("Advanced ▾", "Erweitert ▾"));
        erweitertKnopf.setToolTipText(Sprache.t("More buttons: picture from a file, other picture, arena lists, my pictures, check.",
                "Weitere Knöpfe: Bild aus Datei, anderes Bild, Arena-Listen, meine Bilder, Prüfung."));
        erweitertKnopf.addActionListener(e -> erweitertUmschalten());
        knoepfe.add(erweitertKnopf);

        JPanel dateiReihe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        dateiReihe.add(dateiKnopf);
        dateiReihe.add(anderesKnopf);
        dateiReihe.add(meineKnopf);
        dateiReihe.add(pruefKnopf);
        JPanel arenaReihe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        arenaReihe.add(new JLabel(Sprache.t("Arena lists (doable / safe / never / avoid):", "Arena-Listen (machbar / sicher / niemals / meiden):")));
        arenaReihe.add(arenaRahmenKnopf);
        arenaReihe.add(arenaTippenKnopf);
        arenaReihe.add(arenaGegenstandKnopf);
        erweitertFeld = new JPanel(new BorderLayout(0, 0));
        erweitertFeld.add(dateiReihe, BorderLayout.NORTH);
        erweitertFeld.add(arenaReihe, BorderLayout.SOUTH);
        erweitertFeld.setVisible(false);
        JPanel knopfZeilen = new JPanel(new BorderLayout(0, 0));
        knopfZeilen.add(knoepfe, BorderLayout.NORTH);
        knopfZeilen.add(erweitertFeld, BorderLayout.CENTER);

        balken.setStringPainted(true);
        balken.setPreferredSize(new Dimension(100, 22));

        info.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
        info.setForeground(new Color(60, 60, 60));

        // Autor 24.10. (F13/F17/F15/F22): Vorschau in dreifacher Groesse und das Ergebnis der Test-Suche. Der Platz ist
        // immer da (feste Hoehe), damit das Foto nicht springt, wenn ein Rahmen gezogen wird.
        vorschauBild.setHorizontalAlignment(SwingConstants.CENTER);
        vorschauBild.setOpaque(true);
        vorschauBild.setBackground(new Color(30, 30, 34));
        vorschauBild.setBorder(BorderFactory.createLineBorder(new Color(90, 90, 96)));
        vorschauBild.setPreferredSize(new Dimension(336, 72));
        vorschauBild.setToolTipText(Sprache.t("Your frame in 3x size (smaller if it is big).", "Dein Rahmen in dreifacher Größe (kleiner, wenn er groß ist)."));
        pruefung.setVerticalAlignment(SwingConstants.TOP);
        fotoHinweis.setVerticalAlignment(SwingConstants.TOP);
        JPanel meldungen = new JPanel(new BorderLayout(0, 2));
        meldungen.add(fotoHinweis, BorderLayout.NORTH);
        meldungen.add(pruefung, BorderLayout.CENTER);
        JPanel pruefFeld = new JPanel(new BorderLayout(8, 0));
        pruefFeld.add(vorschauBild, BorderLayout.WEST);
        pruefFeld.add(meldungen, BorderLayout.CENTER);
        pruefFeld.setPreferredSize(new Dimension(100, 76));

        JPanel mitte = new JPanel(new BorderLayout(0, 4));
        mitte.add(pruefFeld, BorderLayout.NORTH);
        mitte.add(info, BorderLayout.CENTER);
        JPanel unten = new JPanel(new BorderLayout(0, 4));
        unten.add(balken, BorderLayout.NORTH);
        unten.add(mitte, BorderLayout.CENTER);

        fuss.add(knopfZeilen, BorderLayout.NORTH);
        fuss.add(unten, BorderLayout.CENTER);
        return fuss;
    }

    /** F20: die zweite und dritte Knopfreihe ein- und ausklappen. */
    private void erweitertUmschalten() {
        boolean an = !erweitertFeld.isVisible();
        erweitertFeld.setVisible(an);
        erweitertKnopf.setText(an ? Sprache.t("Advanced ▴", "Erweitert ▴") : Sprache.t("Advanced ▾", "Erweitert ▾"));
        revalidate();
        repaint();
    }

    // ==================================================================
    //  Der Assistent: immer genau ein Bild ist dran
    // ==================================================================

    /** Das Bild, bei dem der Assistent gerade steht. */
    private String jetzigesBild() {
        return reihenfolge.get(schritt);
    }

    /** Der erste Eintrag, der noch fehlt. Sind alle da, der erste ueberhaupt. */
    private int ersterOffener() {
        for (int i = 0; i < reihenfolge.size(); i++) {
            String name = reihenfolge.get(i);
            if (!name.equals(FREUND_PLATZHALTER) && !schonGespeichert(name)) return i;
        }
        return 0;
    }

    /** Blaettert vor oder zurueck, ohne ueber den Rand hinaus. */
    private void springe(int richtung) {
        int neu = schritt + richtung;
        if (neu < 0 || neu >= reihenfolge.size()) return;
        schritt = neu;

        // Liegt das naechste Bild auf einem anderen Spielbildschirm, ist das
        // alte Foto wertlos -- darin wuerde man etwas Falsches einrahmen und
        // unter dem neuen Namen speichern. Liegt es auf demselben (F16), bleibt es.
        if (zeigeSchritt()) info.setText(gleicherBildschirmHinweis());
    }

    /** Autor 24.10. (F16): Hinweis, wenn das Foto zum naechsten Schritt behalten wurde. */
    private static String gleicherBildschirmHinweis() {
        return Sprache.t("Same screen: the picture stays – just drag the next frame. (Click \"Take picture\" for a new one.)",
                "Derselbe Bildschirm: das Foto bleibt – zieh einfach den nächsten Rahmen. (Für ein neues Foto auf \"Bild machen\" klicken.)");
    }

    /**
     * Baut die Anzeige fuer das gerade faellige Bild neu auf: Ueberschrift,
     * Erklaerung, Knopfbeschriftung, Balken.
     */
    private boolean zeigeSchritt() {
        String name = jetzigesBild();
        Eintrag e = eintrag(name);
        boolean freund = name.equals(FREUND_PLATZHALTER);
        boolean fertig = !freund && schonGespeichert(name);

        titel.setText(Sprache.t("Picture ", "Bild ") + (schritt + 1) + Sprache.t(" of ", " von ") + reihenfolge.size()
                + ":   " + e.kurz() + (fertig ? Sprache.t("   ✓ already saved", "   ✓ schon gespeichert") : ""));

        // Die Breite steht in CSS-Punkten, nicht in Bildpunkten: Swing rechnet
        // sie um ein Drittel groesser. 860 ergibt gut 1140 Bildpunkte und passt
        // so in das 1240 breite Fenster.
        // Autor 24.10.: die Breite richtet sich nach dem Fenster (anleitungAnzeigen) -- das Beispielbild nimmt rechts Platz.
        anleitungText = e.lang()
                + Sprache.t("<br><br><b>How:</b> 1. In the game open ", "<br><br><b>So geht's:</b> 1. Öffne im Spiel ") + e.ort().text
                + Sprache.t(".&nbsp; 2. Click <b>Take picture</b>.&nbsp; 3. Drag a frame around the spot.&nbsp; 4. Click <b>", ".&nbsp; 2. Klick auf <b>Bild machen</b>.&nbsp; 3. Zieh mit der Maus einen Rahmen um die Stelle.&nbsp; 4. Klick auf <b>")
                + ""
                + (freund ? Sprache.t("Save", "Speichern") : Sprache.t("Save and next", "Speichern und weiter")) + "</b>.";
        anleitungAnzeigen();
        beispiel.setMotiv(motivFuer(name));

        speichernKnopf.setText(freund ? Sprache.t("Save", "Speichern")
                : fertig ? Sprache.t("Capture again and next", "Neu aufnehmen und weiter") : Sprache.t("Save and next", "Speichern und weiter"));
        zurueckKnopf.setEnabled(schritt > 0);
        weiterKnopf.setEnabled(schritt < reihenfolge.size() - 1);

        // Ein Foto vom vorigen Bildschirm fuehrt in die Irre: weg damit. Autor 24.10. (F16): Liegt der naechste Schritt auf
        // DEMSELBEN Bildschirm (Gesicht und Name desselben Kaempfers ...), bleibt das Foto -- bei 15 Bildern waren das
        // vorher 15 x "Bild machen".
        boolean behalten = frame != null && fotoOrt != null && fotoOrt == e.ort();
        selection = null;
        if (!behalten) {
            frame = null;
            fotoOrt = null;
            ankerFehlt = false;
            fotoHinweis.setText("");
            imagePanel.leeren();
        } else {
            imagePanel.repaint();
        }
        auswahlLeeren();

        aktualisiereBalken();
        return behalten;
    }

    /** Setzt die Erklaerung in einer Breite, die neben das Beispielbild passt (CSS-Punkte sind 3/4 der Bildpunkte). */
    private void anleitungAnzeigen() {
        int frei = getWidth() - 24 - beispiel.getPreferredSize().width - 16;
        int css = Math.max(260, (int) (frei * 0.72));
        anleitung.setText("<html><body style='width:" + css + "px'>" + anleitungText + "</body></html>");
    }

    /** Autor 24.10. (F14): welches Beispielbild zu welchem Schritt gehoert. */
    private static Beispielbild.Motiv motivFuer(String name) {
        if (name.equals(DungeonMode.bildNamensschild())) return Beispielbild.Motiv.NAMENSSCHILD;
        if (name.equals(DungeonMode.bildOpferPortraet()) || DungeonMode.bilderKaempfer().contains(name)) return Beispielbild.Motiv.GESICHT;
        if (name.equals(DungeonMode.bildOpferName()) || DungeonMode.bilderKaempferNamen().contains(name)) return Beispielbild.Motiv.NAME;
        if (name.equals(FREUND_PLATZHALTER)) return Beispielbild.Motiv.FREUND;
        if (name.equals(arenaName())) return Beispielbild.Motiv.ARENA_NAME;
        if (name.equals(DungeonMode.bildConfirm())) return Beispielbild.Motiv.KNOPF;
        if (name.equals(de.hustlebot.bot.CathedralMode.bildBelohnungText())) return Beispielbild.Motiv.TEXT;
        if (name.startsWith("ev_kiste_")) return Beispielbild.Motiv.EVENT;
        return Beispielbild.Motiv.GESICHT;
    }

    /** Zeigt, wie viele der eigenen Bilder schon da sind. */
    private void aktualisiereBalken() {
        int soll = 0;
        int ist = 0;
        for (String name : reihenfolge) {
            if (name.equals(FREUND_PLATZHALTER)) continue;   // beliebig viele
            soll++;
            if (schonGespeichert(name)) ist++;
        }
        balken.setMaximum(Math.max(1, soll));
        balken.setValue(ist);

        int freunde = freundesbilder();
        balken.setString(ist + Sprache.t(" of ", " von ") + soll + Sprache.t(" saved", " gespeichert")
                + (freunde > 0 ? "   ·   " + freunde + Sprache.t(" friend pictures", " Freundesbilder") : "")
                + (ist == soll ? Sprache.t("   ·   done!", "   ·   fertig!") : ""));
    }

    // ==================================================================
    //  Foto holen
    // ==================================================================

    private void fotografiere() {
        if (!window.locate()) {
            info.setText(Sprache.t("The game window cannot be found. Is Hustle Castle running?", "Das Spielfenster ist nicht zu finden. Läuft Hustle Castle?"));
            return;
        }
        window.prepare();
        Rectangle bereich = window.clientArea();
        if (bereich == null) {
            info.setText(Sprache.t("The game window could not be measured. Please try again.", "Das Spielfenster ließ sich nicht ausmessen. Bitte noch einmal versuchen."));
            return;
        }
        frame = capture.grab(bereich);
        selection = null;
        imagePanel.setImage(frame);
        auswahlLeeren();
        fotoOrt = eintrag(jetzigesBild()).ort();
        fotoPruefen(true);
        info.setText(Sprache.t("Picture taken. Now drag a frame around the spot with the mouse.", "Bild gemacht. Zieh jetzt mit der Maus einen Rahmen um die Stelle."));
        toFront();
    }

    /**
     * Autor 24.10.: Was dem Foto anzusehen ist, bevor man einrahmt.
     *  F22 (nur beim Live-Foto): Fenstergroesse gegen die Bot-Groesse (1159 x 770 innen) -- bei anderer Windows-Skalierung
     *      findet der Bot die Ausschnitte nie ("es passiert nichts", Karla 17.10.: 1157 / 1159 / 1175).
     *  F15: das Kennzeichen des Bildschirms (z. B. die Ueberschrift im Aufstellungsbildschirm) muss im Foto sein.
     * Es sind nur Hinweise; das Speichern bleibt moeglich (mit Rueckfrage).
     */
    private void fotoPruefen(boolean live) {
        List<String> hinweise = new ArrayList<>();
        ankerFehlt = false;
        if (live) {
            String g = Aufnahmepruefung.groessenHinweis(frame.getWidth(), frame.getHeight());
            if (!g.isEmpty()) hinweise.add(g);
        }
        Ort ort = fotoOrt;
        if (ort != null && Boolean.TRUE.equals(kennzeichenFehlt(frame, ort))) {
            ankerFehlt = true;
            hinweise.add(Sprache.t("This does not look like " + ort.text + ". Open that screen in the game, then click \"Take picture\" again.",
                    "Das sieht nicht nach " + ort.text + " aus. Öffne diesen Bildschirm im Spiel und klick noch einmal auf \"Bild machen\"."));
        }
        fotoHinweis.setText(hinweise.isEmpty() ? "" : farbig("#b35a00", "⚠ " + String.join("<br>⚠ ", hinweise)));
    }

    /** F15: Kennzeichen-Bild je Bildschirm (nur wo es eines gibt, das der Bot selbst benutzt). */
    private static String kennzeichenName(Ort ort) {
        return switch (ort) {
            case AUFSTELLUNG -> "deploy_screen";          // "Drag and drop dwellers to form your squad"
            case ARENA -> "ar_select_opponent";           // "Select the next opponent"
            default -> null;
        };
    }

    private final Map<String, java.util.Optional<Path>> kennzeichenDateien = new java.util.HashMap<>();

    /** Wahr = Kennzeichen fehlt im Foto, falsch = gefunden, null = keine Pruefung moeglich (kein Bild da, Fehler). */
    private Boolean kennzeichenFehlt(BufferedImage foto, Ort ort) {
        String name = kennzeichenName(ort);
        if (name == null) return null;
        try {
            Path datei = kennzeichenDateien.computeIfAbsent(name, n -> {
                try (var alle = Files.walk(vorlagenWurzel())) {
                    return alle.filter(p -> p.getFileName().toString().equals(n + ".png")).findFirst();
                } catch (Exception e) {
                    return java.util.Optional.empty();
                }
            }).orElse(null);
            if (datei == null) return null;
            org.opencv.core.Mat f = ScreenCapture.toMatAusschnitt(foto, 0, 0, foto.getWidth(), foto.getHeight());
            try {
                double w = Bilderpruefung.wert(f, datei);
                return w < 0 ? null : w < 0.80;
            } finally {
                f.release();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String farbig(String farbe, String text) {
        return "<html><body style='width:640px'><font color='" + farbe + "'>" + text + "</font></body></html>";
    }

    /**
     * Nimmt ein gespeichertes Bildschirmfoto statt des laufenden Spiels.
     *
     * Wichtig ist der MASSSTAB, nicht die Bildgroesse: Die Vorlage wird spaeter
     * irgendwo im Spielbild gesucht, ein Rand oben stoert also nicht. Ist das
     * geladene Bild aber breiter oder schmaler als das Spielfenster, sind die
     * Gesichter darin groesser oder kleiner -- und dann findet der Bot sie nie
     * wieder. Genau davor wird gewarnt.
     */
    private void ladeBild() {
        JFileChooser wahl = new JFileChooser();
        wahl.setDialogTitle(Sprache.t("Choose a screenshot", "Screenshot auswählen"));
        wahl.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                Sprache.t("Pictures (png, jpg, bmp)", "Bilder (png, jpg, bmp)"), "png", "jpg", "jpeg", "bmp"));
        if (wahl.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File datei = wahl.getSelectedFile();
        BufferedImage geladen;
        try {
            geladen = ImageIO.read(datei);
        } catch (Exception e) {
            info.setText(Sprache.t("The picture could not be read: ", "Das Bild ließ sich nicht lesen: ") + e.getMessage());
            return;
        }
        if (geladen == null) {
            info.setText(Sprache.t("That is not a picture I can read: ", "Das ist kein Bild, das ich lesen kann: ") + datei.getName());
            return;
        }

        frame = geladen;
        selection = null;
        imagePanel.setImage(frame);
        auswahlLeeren();
        fotoOrt = eintrag(jetzigesBild()).ort();
        fotoPruefen(false);

        String warnung = "";
        if (window.locate()) {
            Rectangle bereich = window.clientArea();
            if (bereich != null && bereich.width > 0) {
                int unterschied = Math.abs(frame.getWidth() - bereich.width);
                if (unterschied > 4) {
                    warnung = Sprache.t("  CAREFUL: The game window is " + bereich.width
                            + " pixels wide, this picture " + frame.getWidth()
                            + ". Parts of it have the wrong size and will not be found later.",
                            "  ACHTUNG: Das Spielfenster ist " + bereich.width
                            + " Punkte breit, dieses Bild " + frame.getWidth()
                            + ". Ausschnitte daraus haben die falsche Größe und werden "
                            + "später nicht gefunden.");
                }
            }
        }
        info.setText(Sprache.t("Picture loaded: ", "Bild geladen: ") + datei.getName()
                + " (" + frame.getWidth() + "x" + frame.getHeight() + ")."
                + (warnung.isEmpty() ? Sprache.t("  Now drag a frame.", "  Zieh jetzt einen Rahmen.") : warnung));
        toFront();
    }

    // ==================================================================
    //  Speichern
    // ==================================================================

    private void speichern() {
        String name = jetzigesBild();

        // Beim Freundesbild haengt der Dateiname vom Freund ab. Gefragt wird
        // nach derselben Regel, nach der der Bot spaeter sucht.
        if (name.equals(FREUND_PLATZHALTER)) {
            String freund = JOptionPane.showInputDialog(this,
                    Sprache.t("What is the friend's name? As you will type it later in Discord:\n!start dungeon 10 <name>   (10 is the number of runs)", "Wie heißt der Freund? So, wie du ihn später in Discord eintippst:\n"
                    + "!start dungeon 10 <Name>   (die 10 ist die Zahl der Durchläufe)"),
                    Sprache.t("Friend's name", "Name des Freundes"), JOptionPane.QUESTION_MESSAGE);
            if (freund == null || freund.isBlank()) {
                info.setText(Sprache.t("Not saved – the friend's name is missing.", "Nicht gespeichert – es fehlt der Name des Freundes."));
                return;
            }
            name = DungeonMode.freundVorlage(freund);
            if (name.equals(FREUND_PLATZHALTER)) {
                info.setText(Sprache.t("Not saved – the name has no letter and no digit.", "Nicht gespeichert – in dem Namen steht kein Buchstabe und keine Ziffer."));
                return;
            }
            // friend_invite ist schon vergeben: der gruene Knopf "Invite".
            if (name.equals("friend_invite")) {
                info.setText(Sprache.t("Not saved – \"Invite\" cannot be a friend's name, the invite button picture already has that name.",
                        "Nicht gespeichert – \"Invite\" geht als Freundesname nicht, "
                        + "so heißt schon das Bild des Einladen-Knopfes."));
                return;
            }
        }
        speichereUnter(name, !name.startsWith(FREUND_PLATZHALTER), true);
    }

    /** Fuer Fortgeschrittene: ein Bild unter einem frei eingetippten Namen. */
    private void anderesBild() {
        String name = JOptionPane.showInputDialog(this,
                Sprache.t("Name of the picture, exactly as in the package – for example  participate .\n"
                          + "Only needed if a supplied picture does not fit on your PC.",
                          "Name des Bildes, genau wie im Paket – zum Beispiel  participate .\n"
                          + "Das braucht man nur, wenn ein mitgeliefertes Bild auf deinem\n"
                          + "Rechner nicht passt."),
                Sprache.t("Save other picture", "Anderes Bild speichern"), JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) return;
        speichereUnter(name.trim(), false);
    }

    // --- Arena-Listen (Autor 19.10.) ---------------------------------------------------------
    /** Die vier Listen: Anzeige (Englisch/Deutsch), Bild-Vorsilbe, Wort fuer arena-aufnehmen.txt. */
    // Autor 24.10. (F21): zusaetzlich die Wirkung in Klartext (Englisch, Deutsch) -- sie steht unter der Auswahl.
    private static final String[][] ARENA_LISTEN = {
            {"Doable", "Machbar", "ar_machbar_", "machbar",
                    "Doable: this player can be beaten. The bot may attack him when it fits.",
                    "Machbar: dieser Spieler ist zu schlagen. Der Bot greift ihn an, wenn es passt."},
            {"Safe win", "Sicherer Sieg", "ar_sicher_", "sicher",
                    "Safe win: a sure win. The bot prefers to attack this player.",
                    "Sicherer Sieg: ein sicherer Sieg. Der Bot greift diesen Spieler bevorzugt an."},
            {"Never attack", "Niemals angreifen", "ar_block_", "niemals",
                    "NEVER: the bot will never attack this player again - for ever, there is no expiry.",
                    "NIEMALS: Der Bot greift diesen Spieler nie wieder an - für immer, es gibt keinen Ablauf."},
            {"Avoid", "Meiden", "ar_ungern_", "meiden",
                    "Avoid: the bot would rather not attack this player.",
                    "Meiden: der Bot greift diesen Spieler lieber nicht an."}};
    /** Der Eintrag "niemals" in ARENA_LISTEN -- gilt fuer immer, darum mit Rueckfrage. */
    private static final int NIEMALS = 2;

    /** Autor 24.10. (F21): nichts vorwaehlen -- vorher merkte sich das Fenster die zuletzt benutzte Liste, ein Fehlklick bei "niemals" kostete lange. */
    private static JComboBox<String> arenaListenAuswahl() {
        String[] namen = new String[ARENA_LISTEN.length];
        for (int i = 0; i < namen.length; i++) namen[i] = Sprache.t(ARENA_LISTEN[i][0], ARENA_LISTEN[i][1]);
        JComboBox<String> auswahl = new JComboBox<>(namen);
        auswahl.setSelectedIndex(-1);
        return auswahl;
    }

    /** Zeigt unter der Auswahl in Klartext, was die gewaehlte Liste bewirkt (aendert sich mit der Auswahl). */
    private static JLabel wirkungsLabel(JComboBox<String> auswahl) {
        JLabel l = new JLabel(wirkungHtml(-1));
        auswahl.addActionListener(e -> l.setText(wirkungHtml(auswahl.getSelectedIndex())));
        return l;
    }

    private static String wirkungHtml(int liste) {
        if (liste < 0) {
            return "<html><body style='width:340px'><i>" + Sprache.t("Please choose a list.", "Bitte eine Liste wählen.") + "</i></body></html>";
        }
        String text = Sprache.t(ARENA_LISTEN[liste][4], ARENA_LISTEN[liste][5]);
        return "<html><body style='width:340px'>" + (liste == NIEMALS ? "<b><font color='#b00000'>" + text + "</font></b>" : text) + "</body></html>";
    }

    /** Beim Eintrag in "niemals" noch einmal ausdruecklich fragen (Standard: Abbrechen). Alle anderen Listen: gleich ja. */
    private boolean arenaListeBestaetigt(int liste, String wer) {
        if (liste != NIEMALS) return true;
        String[] knoepfe = {Sprache.t("Yes, never attack", "Ja, niemals angreifen"), Sprache.t("Cancel", "Abbrechen")};
        int a = JOptionPane.showOptionDialog(this,
                "<html><body style='width:420px'><b>" + Sprache.t("List NEVER: ", "Liste NIEMALS: ") + "</b>"
                        + Sprache.t("the bot will never attack " + wer + " again - for ever, with no expiry.<br><br>"
                                + "Clicked wrong? Right after saving, \"Undo last picture\" takes it back; later you can remove the picture under \"My pictures\".",
                        "der Bot greift " + wer + " nie wieder an - für immer, ohne Ablauf.<br><br>"
                                + "Falsch geklickt? Gleich nach dem Speichern nimmt \"Letztes Bild zurücknehmen\" es zurück; später kannst du das Bild unter \"Meine Bilder\" entfernen.")
                        + "</body></html>",
                Sprache.t("Never attack?", "Niemals angreifen?"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, knoepfe, knoepfe[1]);
        if (a != 0) {
            info.setText(Sprache.t("Not saved.", "Nicht gespeichert."));
            return false;
        }
        return true;
    }

    /** Rahmen um einen Namen in der Pyramide -> Liste waehlen -> als Listen-Bild speichern. Beliebig oft. */
    /** Autor 22.10.: Totem/Artefakt-Bilder -- Anzeige (Englisch/Deutsch), Bild-Vorsilbe. Gespeichert unter der naechsten freien Nummer. */
    private static final String[][] GEGENSTAENDE = {
            {"Good totem (+3)", "Gutes Totem (+3)", "ar_totem_"},
            {"Bad totem (-3)", "Schlechtes Totem (-3)", "ar_totemschlecht_"},
            {"Good artifact (+3)", "Gutes Artefakt (+3)", "ar_art_"},
            {"Bad artifact (-3)", "Schlechtes Artefakt (-3)", "ar_artschlecht_"}};
    private static int gegenstandZuletzt = 0;

    private void arenaGegenstandSpeichern() {
        if (frame == null || selection == null || selection.width < 6 || selection.height < 6) {
            info.setText(Sprache.t("Arena: first take a picture of an opponent's fighter and drag a tight frame around the totem or "
                    + "artifact symbol (only the symbol, without number and stones).",
                    "Arena: erst ein Bild eines gegnerischen Kaempfers machen und einen engen Rahmen um das Totem- oder "
                    + "Artefakt-Symbol ziehen (nur das Symbol, ohne Zahl und Steine)."));
            return;
        }
        String[] namen = new String[GEGENSTAENDE.length];
        for (int i = 0; i < namen.length; i++) namen[i] = Sprache.t(GEGENSTAENDE[i][0], GEGENSTAENDE[i][1]);
        JComboBox<String> auswahl = new JComboBox<>(namen);
        auswahl.setSelectedIndex(gegenstandZuletzt);
        int ok = JOptionPane.showConfirmDialog(this, auswahl, Sprache.t("Save arena item", "Arena-Gegenstand speichern"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        gegenstandZuletzt = auswahl.getSelectedIndex();
        String vorsilbe = GEGENSTAENDE[gegenstandZuletzt][2];
        speichereUnter(vorsilbe + naechsteNummer(vorsilbe), false);
    }

    /** Die naechste freie Nummer fuer diese Vorsilbe -- gezaehlt im GANZEN Ordner templates (auch gelernt\). */
    private int naechsteNummer(String vorsilbe) {
        java.util.Set<String> da = new java.util.HashSet<>();
        for (Path w : List.of(vorlagenWurzel(), de.hustlebot.core.Eigenbilder.wurzel(botOrdner()))) {
            if (!Files.isDirectory(w)) continue;
            try (var alle = Files.walk(w)) {
                alle.forEach(p -> da.add(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
            } catch (Exception ignored) {
                // ohne Liste wird ab 1 gezaehlt -- das Speichern meldet Doppelte selbst
            }
        }
        int n = 1;
        while (da.contains((vorsilbe + n + ".png").toLowerCase(java.util.Locale.ROOT))) n++;
        return n;
    }

    private void arenaNameSpeichern() {
        if (frame == null || selection == null || selection.width < 6 || selection.height < 6) {
            info.setText(Sprache.t("Arena: first take a picture of the pyramid and drag a tight frame around ONE player name "
                    + "(only the white name, not the clan).",
                    "Arena: erst ein Bild der Pyramide machen und einen engen Rahmen um EINEN Spielernamen ziehen "
                    + "(nur der weisse Name, nicht der Clan)."));
            return;
        }
        JComboBox<String> auswahl = arenaListenAuswahl();
        JTextField name = new JTextField(16);
        JPanel feld = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        feld.add(new JLabel(Sprache.t("List:", "Liste:")));
        feld.add(auswahl);
        feld.add(wirkungsLabel(auswahl));
        feld.add(new JLabel(Sprache.t("Player name (as in the game, for the file name):", "Spielername (wie im Spiel, fuer den Dateinamen):")));
        feld.add(name);
        int ok = JOptionPane.showConfirmDialog(this, feld, Sprache.t("Arena: save player name", "Arena: Spielernamen speichern"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        int liste = auswahl.getSelectedIndex();
        if (liste < 0) {
            info.setText(Sprache.t("Not saved - please choose a list.", "Nicht gespeichert - bitte eine Liste wählen."));
            return;
        }
        String eingetippt = name.getText().strip();
        if (!arenaListeBestaetigt(liste, eingetippt.isEmpty() ? Sprache.t("this player", "diesen Spieler") : eingetippt)) return;
        String kurz = name.getText().replaceAll("[^\\p{L}\\p{N}]", "");
        if (kurz.isEmpty()) {
            kurz = "player" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd_HHmmss"));
        }
        speichereUnter(ARENA_LISTEN[liste][2] + kurz, false);
    }

    /** Namen eintippen (mit Komma) -> arena-aufnehmen.txt; der Bot schneidet sie im Turnier selbst aus. */
    private void arenaNamenEintippen() {
        JComboBox<String> auswahl = arenaListenAuswahl();
        JTextField namen = new JTextField(28);
        JPanel feld = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        feld.add(new JLabel(Sprache.t("Player names, separated by commas (e.g.  Xenos, Walking Death):",
                "Spielernamen, mit Komma getrennt (z. B.  Xenos, Walking Death):")));
        feld.add(namen);
        feld.add(new JLabel(Sprache.t("List:", "Liste:")));
        feld.add(auswahl);
        feld.add(wirkungsLabel(auswahl));
        int ok = JOptionPane.showConfirmDialog(this, feld, Sprache.t("Arena: type player names", "Arena: Spielernamen eintippen"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        int liste = auswahl.getSelectedIndex();
        if (liste < 0) {
            info.setText(Sprache.t("Not saved - please choose a list.", "Nicht gespeichert - bitte eine Liste wählen."));
            return;
        }
        String wort = ARENA_LISTEN[liste][3];
        java.util.List<String> neu = new java.util.ArrayList<>();
        for (String n : namen.getText().split(",")) {
            String s = n.replace('|', ' ').strip();
            if (!s.isEmpty()) neu.add(s);
        }
        if (neu.isEmpty()) {
            info.setText(Sprache.t("No name typed in.", "Kein Name eingetippt."));
            return;
        }
        if (!arenaListeBestaetigt(liste, neu.size() == 1 ? neu.get(0) : String.join(", ", neu))) return;
        Path datei = botOrdner().resolve("arena-aufnehmen.txt");
        try {
            Runnable rueckweg = Bildablage.dateiStand(datei);   // Autor 24.10. (F21): Rueckgaengig stellt die Datei wieder her
            java.util.List<String> zeilen = Files.exists(datei)
                    ? new java.util.ArrayList<>(Files.readAllLines(datei, java.nio.charset.StandardCharsets.UTF_8))
                    : new java.util.ArrayList<>(java.util.List.of("# Arena: Name | Liste (machbar, sicher, niemals, meiden)"));
            // Steht der Name schon drin, gilt die neue Liste.
            zeilen.removeIf(z -> z.contains("|") && neu.stream().anyMatch(n -> n.equalsIgnoreCase(z.split("\\|", 2)[0].strip())));
            for (String n : neu) zeilen.add(n + " | " + wort);
            Files.write(datei, zeilen, java.nio.charset.StandardCharsets.UTF_8);
            journal.neu(Sprache.t("Names typed: ", "Namen eingetippt: ") + String.join(", ", neu) + " -> " + ARENA_LISTEN[liste][Sprache.istEnglisch() ? 0 : 1],
                    -1, rueckweg);
            rueckgaengigAktualisieren();
            info.setText(Sprache.t(neu.size() + " name(s) noted for \"" + ARENA_LISTEN[liste][0]
                    + "\". The bot cuts them out by itself as soon as it sees them in the arena pyramid. Wrong? Click \"Undo last picture\".",
                    neu.size() + " Name(n) fuer \"" + ARENA_LISTEN[liste][1]
                    + "\" vorgemerkt. Der Bot schneidet sie selbst aus, sobald er sie in der Arena-Pyramide sieht. Falsch? Klick auf \"Letztes Bild zurücknehmen\"."));
            Log.info("Vorlagen", "arena-aufnehmen.txt: " + String.join(", ", neu) + " -> " + wort);
        } catch (Exception e) {
            info.setText(Sprache.t("Could not be saved: ", "Konnte nicht gespeichert werden: ") + e.getMessage());
        }
    }

    /**
     * Schneidet den Rahmen aus und legt ihn als PNG ab.
     *
     * weiterGehen: danach zum naechsten Schritt springen. Beim Freundesbild und
     * beim Sonderfall bleibt der Assistent stehen -- dort will man meist gleich
     * noch eins aufnehmen.
     */
    private void speichereUnter(String name, boolean weiterGehen) {
        speichereUnter(name, weiterGehen, false);
    }

    /** ausAssistent: das Bild gehoert zum Schritt des Assistenten (Rueckgaengig springt dorthin zurueck; F15 prueft den Bildschirm). */
    private void speichereUnter(String name, boolean weiterGehen, boolean ausAssistent) {
        if (frame == null || selection == null || selection.width < 6 || selection.height < 6) {
            info.setText(Sprache.t("First take a picture and drag a frame that is big enough.", "Erst ein Bild machen und einen Rahmen ziehen, der groß genug ist."));
            return;
        }
        Rectangle sicher = selection.intersection(
                new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
        if (sicher.isEmpty()) {
            info.setText(Sprache.t("The frame is outside the picture.", "Der Rahmen liegt außerhalb des Bildes."));
            return;
        }
        // Autor 24.10. (F13/F15/F17): Test-Suche und Bildschirm-Kennzeichen -- bei Bedenken erst fragen.
        if (!bedenkenGeklaert(sicher, ausAssistent, name)) return;

        List<Runnable> rueck = new ArrayList<>();
        try {
            BufferedImage ausschnitt = frame.getSubimage(sicher.x, sicher.y, sicher.width, sicher.height);
            Path ordner = ordnerFuer(name);
            Path ziel = ordner.resolve(name + ".png");
            // Autor, 11.10.: "Ein Name soll nicht zweimal in der gleichen Liste stehen -- das soll schon beim
            // Aufnehmen auffallen." Gleiche Liste: nicht speichern. Andere Liste: fragen, ob er umziehen soll
            // (die alte Aufnahme kommt nach templates-aussortiert, nichts wird geloescht).
            String liste = de.hustlebot.core.ArenaVorlagenDoppelt.liste(name);
            if (liste != null && !doppeltErlaubt(ausschnitt, name, liste, ziel, rueck)) return;
            // Autor 24.10. (F19): ein vorhandenes Bild bleibt als .alt liegen, jede Aufnahme hat einen Rueckweg.
            rueck.add(Bildablage.schreibeMitSicherung(ziel, ausschnitt));
            journal.neu(Sprache.t("Saved: ", "Gespeichert: ") + name, ausAssistent ? schritt : -1, rueck.toArray(new Runnable[0]));
            rueckgaengigAktualisieren();

            selection = null;
            auswahlLeeren();
            imagePanel.repaint();
            info.setText(Sprache.t("Saved: ", "Gespeichert: ") + name + "  (" + sicher.width + "x" + sicher.height
                    + Sprache.t(" pixels).", " Punkte).")
                    + (liste != null ? Sprache.t("  Wrong? Click \"Undo last picture\".", "  Falsch? Klick auf \"Letztes Bild zurücknehmen\".") : ""));
            Log.info("Vorlagen", Sprache.t("Saved: ", "Gespeichert: ") + name);

            if (weiterGehen && schritt < reihenfolge.size() - 1) {
                schritt++;
                boolean behalten = zeigeSchritt();
                info.setText(Sprache.t("Saved. Next: ", "Gespeichert. Weiter mit: ") + eintrag(jetzigesBild()).kurz().trim()
                        + Sprache.t(" – found on ", " – zu finden auf ") + eintrag(jetzigesBild()).ort().text + "."
                        + (behalten ? " " + gleicherBildschirmHinweis() : ""));
            } else {
                aktualisiereBalken();
            }
            // Autor, 11.10.: "beim Aufnehmen hinschreiben, wie viele Punkte ich gegen diesen Spieler erzielt habe".
            // Erst nach "Gespeichert: ..." fragen, sonst ueberschreibt diese Zeile die Rueckmeldung zu den Punkten.
            if ("ar_sicher_".equals(liste) || "ar_machbar_".equals(liste)) punkteErfragen(ausschnitt, name);
        } catch (Exception e) {
            for (int i = rueck.size() - 1; i >= 0; i--) rueck.get(i).run();   // nichts halb gemacht liegen lassen
            info.setText(Sprache.t("Could not be saved: ", "Konnte nicht gespeichert werden: ") + e.getMessage());
        }
    }

    /**
     * Autor 24.10. (F13/F15/F17): Vor dem Speichern: die Test-Suche zeigt ROT (nicht eindeutig / einfarbig / nicht wiedergefunden)
     * oder das Foto zeigt nicht den erwarteten Bildschirm? Dann fragt das Fenster nach -- der Freund kann trotzdem speichern.
     */
    private boolean bedenkenGeklaert(Rectangle sicher, boolean ausAssistent, String name) {
        List<String> bedenken = new ArrayList<>();
        Aufnahmepruefung.Bericht b = Aufnahmepruefung.bewerte(frame, sicher);
        // Arena-Bilder (Totems, Artefakte ...) duerfen mehrfach im Foto vorkommen -- der Bot sucht dort keine einzelne Stelle.
        boolean nurMehrfach = b != null && b.maengel().contains(Aufnahmepruefung.Mangel.NICHT_EINDEUTIG)
                && !b.maengel().contains(Aufnahmepruefung.Mangel.NICHT_GEFUNDEN) && !b.maengel().contains(Aufnahmepruefung.Mangel.EINFARBIG);
        if (b != null && b.stufe() == Aufnahmepruefung.Stufe.ROT && !(name.startsWith("ar_") && nurMehrfach)) bedenken.add(b.text());
        if (ausAssistent && ankerFehlt && fotoOrt != null) {
            bedenken.add(Sprache.t("The picture does not look like " + fotoOrt.text + " - it may be the wrong screen.",
                    "Das Foto sieht nicht nach " + fotoOrt.text + " aus - vielleicht ist es der falsche Bildschirm."));
        }
        if (bedenken.isEmpty()) return true;
        String[] knoepfe = {Sprache.t("Save anyway", "Trotzdem speichern"), Sprache.t("Frame again", "Neu einrahmen")};
        int a = JOptionPane.showOptionDialog(this,
                "<html><body style='width:480px'>" + String.join("<br><br>", bedenken) + "</body></html>",
                Sprache.t("Please check", "Bitte prüfen"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, knoepfe, knoepfe[1]);
        if (a == 0) return true;
        info.setText(Sprache.t("Not saved - frame again.", "Nicht gespeichert - rahme noch einmal ein."));
        return false;
    }

    /** Der Bot-Ordner: der Ordner, in dem "templates" liegt (dort liegen auch gelernt\ und die Listen-Dateien). */
    private Path botOrdner() {
        Path wurzel = vorlagenWurzel().toAbsolutePath().normalize();
        return wurzel.getParent() != null ? wurzel.getParent() : wurzel;
    }

    /** Der Weg einer Vorlage relativ zum Bot-Ordner (fuer Meldungen), sonst nur der Dateiname. */
    private Path relativZurWurzel(Path datei) {
        try {
            Path r = botOrdner().relativize(datei.toAbsolutePath().normalize());
            return r.toString().startsWith("..") ? datei.getFileName() : r;
        } catch (IllegalArgumentException e) {
            return datei.getFileName();
        }
    }

    /**
     * Autor, 11.10.: kein Name zweimal in einer Arena-Liste (sicher, machbar, meiden, niemals). Steht der Name
     * schon in DERSELBEN Liste, wird nichts gespeichert; steht er in einer anderen, fragt das Fenster, ob er
     * umziehen soll -- die alte Aufnahme kommt dann nach templates-aussortiert, geloescht wird nichts.
     * Verglichen wird das Bild (ArenaVorlagenDoppelt), nicht der Dateiname. Gibt zurueck, ob gespeichert werden darf.
     */
    private boolean doppeltErlaubt(BufferedImage ausschnitt, String name, String liste, Path ziel, List<Runnable> rueck) {
        var schon = de.hustlebot.core.ArenaVorlagenDoppelt.finde(ausschnitt, vorlagenWurzel(), ziel);
        // Autor 21.10.: auch nach dem eingetippten NAMEN -- das Bild allein liess Doppelte durch.
        if (schon.isEmpty()) {
            Path zielNorm = ziel.toAbsolutePath().normalize();
            schon = de.hustlebot.core.ArenaVorlagenDoppelt.findeNachName(
                            de.hustlebot.core.ArenaVorlagenDoppelt.nameAusDatei(name), vorlagenWurzel()).stream()
                    .filter(t -> !t.datei().toAbsolutePath().normalize().equals(zielNorm)).findFirst();
        }
        if (schon.isEmpty()) return true;
        Path alt = schon.get().datei();
        // Review 28.10.: ein "_auto"-Bild hat der Bot nur zum Ansehen in die Liste gelegt -- es blockiert nie Autors eigene
        // Aufnahme. Es kommt nach templates-aussortiert (zuruecknehmbar), Autors Bild wird gespeichert.
        if (de.hustlebot.core.ArenaListenOrdner.istAuto(alt.getFileName().toString())) {
            try {
                rueck.add(Bildablage.verschiebe(alt, de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(botOrdner(), alt)));
                return true;
            } catch (Exception e) {
                info.setText(Sprache.t("Could not move the old picture: ", "Die alte Aufnahme ließ sich nicht verschieben: ")
                        + e.getMessage());
                return false;
            }
        }
        Path relativ = relativZurWurzel(alt);
        if (schon.get().liste().equals(liste)) {
            info.setText(Sprache.t("Not saved: this name is already in the same list (",
                    "Nicht gespeichert: dieser Name steht schon in derselben Liste (") + relativ + ").");
            return false;
        }
        int antwort = JOptionPane.showConfirmDialog(this,
                Sprache.t("This name is already in another list:\n", "Dieser Name steht schon in einer anderen Liste:\n")
                + relativ
                + Sprache.t("\n\nMove it to the new list (", "\n\nIn die neue Liste (") + name
                + Sprache.t(")?\nYes = the old picture goes to templates-aussortiert, the new one is saved.",
                            ") verschieben?\nJa = die alte Aufnahme kommt nach templates-aussortiert, die neue wird gespeichert."),
                Sprache.t("Name already there", "Name schon vorhanden"),
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (antwort != JOptionPane.YES_OPTION) {
            info.setText(Sprache.t("Not saved – ", "Nicht gespeichert – ") + alt.getFileName()
                    + Sprache.t(" stays as it is.", " bleibt, wie es ist."));
            return false;
        }
        try {
            Path weg = de.hustlebot.core.ArenaVorlagenDoppelt.aussortiertZiel(botOrdner(), alt);
            rueck.add(Bildablage.verschiebe(alt, weg));   // Autor 24.10. (F21): auch das Umziehen laesst sich zuruecknehmen
        } catch (Exception e) {
            info.setText(Sprache.t("Could not move the old picture: ", "Die alte Aufnahme ließ sich nicht verschieben: ")
                    + e.getMessage());
            return false;
        }
        return true;
    }

    /** Die Datei im Bot-Ordner (neben "templates") -- dort liest auch der Bot sie. */
    private Path imBotOrdner(String datei) {
        return botOrdner().resolve(datei);
    }

    /**
     * Autor, 11.10.: "wenn ich einen Spieler als sicher abstempel, dass ich dann noch hinzuschreiben kann, wie viele
     * Punkte ich gegen ihn bekommen habe, wie lange es gedauert hat und ob er auf die Aufheben-Liste soll."
     * Ein Fenster nach dem Speichern eines sicher/machbar-Bildes: Name (per Texterkennung vorgeschlagen), Punkte,
     * Kampfdauer und die Aufheben-Liste. Punkte landen in arena-punkte-erzielt.txt ("Name | Punkte | Datei | Dauer"),
     * die Aufheben-Liste in arena-aufheben.txt (ein Name je Zeile) -- beide im Bot-Ordner, der Bot liest sie dort.
     * Alles leer lassen = nichts eintragen.
     */
    private void punkteErfragen(BufferedImage crop, String datei) {
        String gelesen = "";
        try {
            BufferedImage gross = new BufferedImage(crop.getWidth() * 2, crop.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = gross.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(crop, 0, 0, gross.getWidth(), gross.getHeight(), null);
            g.dispose();
            gelesen = String.join(" ", de.hustlebot.core.Texterkennung.liesAuchKyrillisch(gross)).strip();
        } catch (Exception e) {
            Log.debug("Vorlagen", "Name nicht gelesen: " + e.getMessage());
        }
        JTextField feldName = new JTextField(gelesen, 18);
        JTextField feldPunkte = new JTextField(5);
        JTextField feldDauer = new JTextField(5);
        JCheckBox aufheben = new JCheckBox(Sprache.t("Save for later (attack only in stage 4-5)",
                "Auf die Aufheben-Liste (erst in Stage 4-5 angreifen)"));
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel(Sprache.t("Player (please check):", "Spieler (bitte prüfen):")));
        p.add(feldName);
        p.add(new JLabel(Sprache.t("Points you scored against them (e.g. 215):", "Punkte gegen ihn (z. B. 215):")));
        p.add(feldPunkte);
        p.add(new JLabel(Sprache.t("Fight duration in seconds:", "Kampfdauer in Sekunden:")));
        p.add(feldDauer);
        p.add(aufheben);
        p.add(new JLabel(Sprache.t("(leave empty = don't know)", "(leer lassen = weiß nicht)")));
        int ok = JOptionPane.showConfirmDialog(this, p, Sprache.t("Against this player", "Gegen diesen Spieler"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        String name = feldName.getText().strip();
        if (ok != JOptionPane.OK_OPTION || name.isEmpty()) return;
        String punkte = feldPunkte.getText().strip(), dauer = feldDauer.getText().strip();
        StringBuilder gemerkt = new StringBuilder();
        try {
            if (punkte.matches("\\d{1,3}")) {
                Path ziel = imBotOrdner("arena-punkte-erzielt.txt");
                journal.ergaenze(Bildablage.dateiStand(ziel));   // Autor 24.10. (F19/F21): "Letztes Bild zuruecknehmen" nimmt auch das zurueck
                if (!Files.exists(ziel)) {
                    Files.writeString(ziel, "# Your points against a player, entered when capturing / "
                            + "Deine Punkte gegen einen Spieler, beim Aufnehmen eingetragen: Name | Punkte | Datei | Dauer\n",
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                Files.writeString(ziel, name + " | " + punkte + " | " + datei + " | " + (dauer.matches("\\d{1,3}") ? dauer : "") + "\n",
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
                gemerkt.append(punkte).append(Sprache.t(" points", " Punkte"));
                if (dauer.matches("\\d{1,3}")) gemerkt.append(Sprache.t(" in ", " in ")).append(dauer).append(" s");
            }
            if (aufheben.isSelected()) {
                Path liste = imBotOrdner("arena-aufheben.txt");
                journal.ergaenze(Bildablage.dateiStand(liste));
                List<String> drin = Files.exists(liste) ? Files.readAllLines(liste, java.nio.charset.StandardCharsets.UTF_8) : List.of();
                if (drin.stream().noneMatch(z -> de.hustlebot.core.NamensVergleich.gleicherName(z.strip(), name))) {
                    if (!Files.exists(liste)) {
                        Files.writeString(liste, "# Good players you beat easily -- attack only in stage 4-5 / "
                                + "Gute Spieler, gegen die du leicht gewinnst -- erst in Stage 4-5 angreifen. Ein Name je Zeile.\n",
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                    Files.writeString(liste, name + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
                }
                gemerkt.append(gemerkt.length() > 0 ? ", " : "").append(Sprache.t("save-for-later list", "Aufheben-Liste"));
            }
            if (gemerkt.length() > 0) {
                info.setText(info.getText() + Sprache.t("  – noted for ", "  – gemerkt für ") + name + ": " + gemerkt + ".");
            }
        } catch (Exception e) {
            info.setText(info.getText() + Sprache.t("  – not saved: ", "  – nicht gespeichert: ") + e.getMessage());
        }
    }

    // ==================================================================
    //  Wo die Bilder liegen
    // ==================================================================

    /**
     * In welchen Unterordner eine Vorlage gehoert.
     *
     * Die Namen der Dunklen Kathedrale beginnen mit "dc_", ihre Ziffern mit
     * "key_". Alles andere gehoert zum Verlies. Fuer den Bot macht das keinen
     * Unterschied -- er liest alle Unterordner gemeinsam ein und schaut nur auf
     * den Dateinamen. Es haelt den Ordner aber uebersichtlich.
     */
    private Path ordnerFuer(String name) {
        String unter;
        if (name.startsWith("cw_")) {
            unter = "clanwar";
        } else if (name.startsWith("dc_") || name.startsWith("key_")) {
            unter = "cathedral";
        } else if (name.startsWith("ev_kiste_")) {
            unter = "event-belohnungen";   // eigene Event-Motive (03.10.)
        } else if (name.startsWith("ar_")) {
            // Arena (3.0): wie im Hauptbot einsortiert -- ar_me nach arena\anzeigen, eigene
            // Gegner-Listen (ar_block_1 ...) nach arena\gegner\niemals usw.
            unter = de.hustlebot.core.Vorlagenordner.fuer(name);
        } else {
            unter = "dungeon";
        }
        // Autor 24.10.: eigene Aufnahmen sichtbar und getrennt von Autors Bildern -- in "My pictures" neben templates.
        Path botOrdner = vorlagenWurzel().toAbsolutePath().normalize().getParent();
        if (botOrdner != null) return de.hustlebot.core.Eigenbilder.ordnerFuer(botOrdner, name);
        return vorlagenWurzel().resolve(unter);
    }

    /**
     * Der Ordner "templates" selbst.
     *
     * Frueher bekam das Werkzeug direkt "templates\dungeon" uebergeben. Damit
     * beide Aufrufe funktionieren, wird ein mitgegebener Unterordner hier
     * wieder abgestreift.
     */
    private Path vorlagenWurzel() {
        Path name = templateDir.getFileName();
        if (name != null && ("dungeon".equals(name.toString())
                          || "cathedral".equals(name.toString()))) {
            Path eltern = templateDir.getParent();
            if (eltern != null) return eltern;
        }
        return templateDir;
    }

    /** Die Freundesbilder (friend_...png), die schon aufgenommen sind. */
    private List<Path> freundesDateien() {
        List<Path> liste = new ArrayList<>();
        // Autor 24.10.: neue Aufnahmen liegen in "My pictures\Dungeon", aeltere in templates\dungeon.
        for (Path ordner : java.util.stream.Stream.of(vorlagenWurzel().resolve("dungeon"), ordnerFuer(FREUND_PLATZHALTER + "x")).distinct().toList()) {
            if (!Files.isDirectory(ordner)) continue;
            try (var dateien = Files.list(ordner)) {
                // friend_invite.png ist der Knopf "Invite", kein Freund.
                dateien.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(FREUND_PLATZHALTER) && n.endsWith(".png")
                            && n.length() > FREUND_PLATZHALTER.length() + 4
                            && !n.equals("friend_invite.png");
                }).forEach(liste::add);
            } catch (Exception e) {
                // dieser Ordner zaehlt nicht mit
            }
        }
        return liste;
    }

    /** Wie viele Freundesbilder (friend_...png) schon aufgenommen sind. */
    private int freundesbilder() {
        return freundesDateien().size();
    }

    private boolean schonGespeichert(String name) {
        if (Files.exists(ordnerFuer(name).resolve(name + ".png"))) return true;
        // Aeltere Aufnahmen koennen noch im falschen Ordner liegen. Die zaehlen mit.
        Path wurzel = vorlagenWurzel();
        return Files.exists(wurzel.resolve("dungeon").resolve(name + ".png"))
            || Files.exists(wurzel.resolve("cathedral").resolve(name + ".png"))
            || Files.exists(wurzel.resolve("clanwar").resolve(name + ".png"))
            || Files.exists(wurzel.resolve(name + ".png"));
    }

    // ==================================================================
    //  Welche Bilder jeder selbst aufnehmen muss
    // ==================================================================

    /** Auf welchem Bildschirm im Spiel ein eigenes Bild zu finden ist. */
    private enum Ort {
        KARTE(Sprache.t("the dungeon map", "die Verlieskarte")),
        AUFSTELLUNG(Sprache.t("the squad screen in the dungeon", "den Aufstellungsbildschirm im Verlies")),
        FREUNDESLISTE(Sprache.t("the friends list (click \"Friend\" in the dungeon)",
                                "die Freundesliste (im Verlies auf \"Friend\" klicken)")),
        BESTAETIGEN(Sprache.t("the screen where you enter a dungeon or the cathedral "
                              + "(green \"Confirm\" button at the bottom)",
                              "den Bildschirm, auf dem du ein Verlies oder die Kathedrale betrittst "
                              + "(unten der grüne Knopf \"Confirm\")")),
        BELOHNUNG(Sprache.t("the reward screen after a cathedral journey "
                            + "(the text \"You have received a reward\")",
                            "den Belohnungs-Bildschirm nach einer Kathedralen-Reise "
                            + "(der Text \"You have received a reward\")")),
        EVENT(Sprache.t("the hammer event field: an item in a glass vase (uncovered by a magnifier)",
                        "das Feld im Hammer-Event: ein Teil in einer Glasvase (mit der Lupe aufgedeckt)")),
        ARENA(Sprache.t("the arena pyramid (\"Select the next opponent\")",
                        "die Pyramide in der Arena (\"Select the next opponent\")"));

        final String text;
        Ort(String text) { this.text = text; }
    }

    /** Ein eigenes Bild: kurz als Ueberschrift, ausfuehrlich als Erklaerung. */
    private record Eintrag(String kurz, String lang, Ort ort) {}

    /**
     * Die Bilder, die jeder Freund selbst aufnehmen muss -- in dieser
     * Reihenfolge fragt der Assistent sie ab.
     *
     * Alles andere liefert das Paket fertig mit. Knoepfe und Fenster sehen bei
     * jedem gleich aus; die eigenen Kaempfer und der eigene Spielername nicht.
     *
     * Die Kaempfer stehen hier nicht mit Namen, sondern als Plaetze 1 bis 6
     * nach Wichtigkeit. So muss niemand seine Kaempfer im Spiel umbenennen:
     * er fotografiert den Namen, wie er ist, und der Platz sagt dem Bot, wie
     * wichtig der Kaempfer ist.
     *
     * Die Dateinamen kommen aus DungeonMode, nicht von hier. Frueher standen
     * sie an zwei Stellen, und genau daraus entstand der Fehler, dass Werkzeug
     * und Bot verschiedene Namen meinten.
     */
    private static final Map<String, Eintrag> EIGENE = new LinkedHashMap<>();

    /**
     * Eintrag fuer das Namensbild eines Freundes. Kein fester Dateiname: Beim
     * Speichern wird nach dem Freund gefragt. Entspricht
     * DungeonMode.freundVorlage(""). Muss VOR dem static-Block stehen, der es
     * benutzt.
     */
    private static final String FREUND_PLATZHALTER = DungeonMode.freundVorlage("");

    static {
        // Aufgenommen wird alles im Verlies. Die Kathedrale zeigt zwar auch
        // eine Kaempferreihe, doch ob die Namensschilder dort genauso aussehen,
        // hat nie jemand geprueft -- und gebraucht werden die Namen nur im
        // Verlies.
        // Sprache zuerst -- dieser Block laeuft beim Laden der Klasse, also noch
        // bevor main() etwas einstellen kann (sprache.txt, seit 2.1).
        Sprache.set(de.hustlebot.Main.spracheAusDatei());
        boolean en = Sprache.istEnglisch();

        String aufstellung = en
                ? "Where: in the dungeon, the squad screen before a battle (at the top it says "
                  + "\"Drag and drop dwellers to form your squad\"). Your fighters stand in a row "
                  + "at the bottom. "
                : "Wo: im Verlies, der Aufstellungsbildschirm vor einem Kampf "
                  + "(oben steht \"Drag and drop dwellers to form your squad\"). Deine Kämpfer "
                  + "stehen unten in einer Reihe. ";
        String nurGesicht = en
                ? "Frame only the face – not the name below and not the health bar. "
                : "Rahme nur das Gesicht ein – nicht den Namen darunter und nicht den Lebensbalken. ";
        String nurName = en
                ? "Frame only the letters of the name under the face, nothing else. "
                : "Rahme nur die Buchstaben des Namens unter dem Gesicht ein, sonst nichts. ";

        EIGENE.put(DungeonMode.bildNamensschild(), new Eintrag(
                en ? "Your player name  (REQUIRED)" : "Dein Spielername  (PFLICHT)",
                en ? "Where: on the dungeon map. At the bottom there are two name tags – yours and "
                     + "your partner's. Frame only the letters of YOUR name. That is how the bot "
                     + "knows whether it is the leader. Without this picture the dungeon does not start."
                   : "Wo: auf der Verlieskarte. Unten stehen zwei Namensschilder – deins und das "
                     + "deines Mitspielers. Rahme nur die Buchstaben DEINES Namens ein. Daran "
                     + "erkennt der Bot, ob er der Anführer ist. Ohne dieses Bild startet das "
                     + "Verlies nicht.", Ort.KARTE));

        EIGENE.put(DungeonMode.bildOpferPortraet(), new Eintrag(
                en ? "Sacrifice fighter: face  (REQUIRED)" : "Opfer-Kämpfer: Gesicht  (PFLICHT)",
                aufstellung + (en ? "Take the fighter you would give up first at the sacrifice altar. "
                                  : "Nimm den Kämpfer, den du am Opferaltar am ehesten hergibst. ")
                + nurGesicht + (en ? "Without this picture the dungeon does not start."
                                   : "Ohne dieses Bild startet das Verlies nicht."), Ort.AUFSTELLUNG));

        EIGENE.put(DungeonMode.bildOpferName(), new Eintrag(
                en ? "Sacrifice fighter: name" : "Opfer-Kämpfer: Name",
                (en ? "Same screen, same fighter. " : "Derselbe Bildschirm, derselbe Kämpfer. ") + nurName
                + (en ? "So the bot never wastes a resurrection on it."
                      : "Damit verschwendet der Bot nie eine Wiederbelebung an ihn."), Ort.AUFSTELLUNG));

        List<String> gesichter = DungeonMode.bilderKaempfer();
        List<String> namen = DungeonMode.bilderKaempferNamen();
        String[] rang = en
                ? new String[]{"your MOST IMPORTANT fighter", "your second most important fighter",
                               "your third most important fighter", "your fourth most important fighter",
                               "your fifth most important fighter", "your sixth most important fighter"}
                : new String[]{"dein WICHTIGSTER Kämpfer", "dein zweitwichtigster Kämpfer",
                               "dein drittwichtigster Kämpfer", "dein viertwichtigster Kämpfer",
                               "dein fünftwichtigster Kämpfer", "dein sechstwichtigster Kämpfer"};
        String kaempfer = en ? "Fighter " : "Kämpfer ";
        for (int i = 1; i <= gesichter.size(); i++) {
            String wer = i <= rang.length ? rang[i - 1] : kaempfer + i;
            String wirkung = switch (i) {
                case 1 -> en ? "Fighter 1 is revived first, sacrificed last and goes up first in the duel. "
                             : "Kämpfer 1 wird zuerst wiederbelebt, zuletzt geopfert und geht im "
                               + "Zweikampf als Erster hoch. ";
                case 2 -> en ? "Fighter 2 goes up in the duel together with fighter 1. "
                             : "Kämpfer 2 geht im Zweikampf zusammen mit Kämpfer 1 hoch. ";
                case 5, 6 -> en ? "Fighters 5 and 6 may be sacrificed once the sacrifice fighter has fallen. "
                                : "Kämpfer 5 und 6 dürfen geopfert werden, wenn der Opfer-Kämpfer "
                                  + "schon gefallen ist. ";
                default -> "";
            };
            EIGENE.put(gesichter.get(i - 1), new Eintrag(kaempfer + i + (en ? ": face" : ": Gesicht"),
                    aufstellung + (en ? "The face of " : "Das Gesicht von ") + wer + ". " + nurGesicht + wirkung
                    + (en ? "Fewer than " + gesichter.size() + " fighters? Leave the rest out – click \"Skip\"."
                          : "Du hast keine " + gesichter.size() + " Kämpfer? Dann lass den Rest "
                            + "einfach weg – klick auf \"Überspringen\"."), Ort.AUFSTELLUNG));
            EIGENE.put(namen.get(i - 1), new Eintrag(kaempfer + i + (en ? ": name" : ": Name"),
                    (en ? "Same screen, same fighter as in \"Fighter " + i + ": face\". "
                        : "Derselbe Bildschirm, derselbe Kämpfer wie bei \"Kämpfer " + i + ": Gesicht\". ")
                    + nurName
                    + (en ? "A fallen fighter turns grey, then the face no longer matches – the name "
                            + "still does. That is how the bot knows whom to revive."
                          : "Ein gefallener Kämpfer wird grau, dann passt "
                            + "das Gesicht nicht mehr – der Name schon. So weiß der Bot, wen er "
                            + "wiederbeleben soll."), Ort.AUFSTELLUNG));
        }

        EIGENE.put(FREUND_PLATZHALTER, new Eintrag(
                en ? "Name of a friend  (optional)" : "Name eines Freundes  (freiwillig)",
                en ? "Only needed if you want to play the dungeon with a particular friend: "
                     + "!start dungeon 10 lisa (10 is the number of runs). Where: click \"Friend\" "
                     + "in the dungeon – that is your friends list. Frame only the letters of the "
                     + "name: not the orange \"i\" behind it, not the clan below and not the number "
                     + "in front. When saving, the tool asks for the friend's name. As many friends "
                     + "as you like – just save again."
                   : "Nur nötig, wenn du mit einem bestimmten Freund ins Verlies willst: "
                     + "!start dungeon 10 lisa (die 10 ist die Zahl der Durchläufe). Wo: im "
                     + "Verlies auf \"Friend\" klicken – das ist deine Freundesliste. Rahme nur die "
                     + "Buchstaben des Namens ein: nicht das orange \"i\" dahinter, nicht den Clan "
                     + "darunter und nicht die Nummer davor. Beim Speichern fragt das Werkzeug, wie "
                     + "der Freund heißt. So viele Freunde, wie du willst – einfach noch einmal "
                     + "speichern.", Ort.FREUNDESLISTE));

        // Arena (3.0): der eigene Name im eigenen Schild der Pyramide (im Hauptbot "ar_me").
        EIGENE.put(arenaName(), new Eintrag(
                en ? "Arena: your name  (REQUIRED for the arena)" : "Arena: dein Name  (PFLICHT für die Arena)",
                en ? "Only needed for the arena (!start arena). Where: in a tournament, the pyramid with the ten "
                     + "shields (top right \"Select the next opponent\"). Find YOUR own shield and frame only the "
                     + "letters of your name – without the clan below and without the frame. That is how the bot "
                     + "knows which shield is yours. Without this picture the arena does not start."
                   : "Nur für die Arena nötig (!start arena). Wo: im Turnier die Pyramide mit den zehn Schildern "
                     + "(oben rechts \"Select the next opponent\"). Such DEIN eigenes Schild und rahme nur die "
                     + "Buchstaben deines Namens ein – ohne den Clan darunter und ohne Rahmen. Daran erkennt der "
                     + "Bot, welches Schild deins ist. Ohne dieses Bild startet die Arena nicht.", Ort.ARENA));
    }

    /**
     * Zusatzbilder: Das Paket bringt sie fertig mit, aber bei manchen sehen sie
     * anders aus. Der Freund DARF sie neu aufnehmen, muss aber nicht.
     *
     * Wichtig: Diese Namen zaehlen NICHT als eigene Bilder (eigeneBilder gibt
     * nur EIGENE zurueck). Sonst wuerde die Selbstpruefung sie als "liegt schon
     * im Paket" bemaengeln -- denn genau das tun sie ja mit Absicht.
     */
    private static final Map<String, Eintrag> ZUSATZ = new LinkedHashMap<>();

    static {
        boolean en = Sprache.istEnglisch();
        ZUSATZ.put(DungeonMode.bildConfirm(), new Eintrag(
                en ? "\"Confirm\" button  (optional)" : "„Confirm\"-Knopf  (freiwillig)",
                en ? "Only needed if the bot does NOT click \"Confirm\" when entering a dungeon or the "
                     + "cathedral. The package already brings this picture – only capture it again if it "
                     + "gets stuck for you. <b>Important:</b> HD graphics in the game must be OFF "
                     + "(Settings → Graphics). Frame only the green \"Confirm\" button, nothing else."
                   : "Nur nötig, wenn der Bot beim Betreten eines Verlieses oder der Kathedrale "
                     + "NICHT auf „Confirm\" klickt. Das Paket bringt dieses Bild schon mit – nimm "
                     + "es nur neu auf, wenn es bei dir hakt. <b>Wichtig:</b> Die HD-Grafik im Spiel "
                     + "muss AUS sein (Einstellungen → Grafik). Rahme nur den grünen Knopf „Confirm\" "
                     + "ein, sonst nichts.", Ort.BESTAETIGEN));

        ZUSATZ.put(de.hustlebot.bot.CathedralMode.bildBelohnungText(), new Eintrag(
                en ? "Event reward text  (optional)" : "Belohnungs-Text des Events  (freiwillig)",
                en ? "Only needed while the event is running. After every cathedral journey the text "
                     + "<b>\"You have received a reward\"</b> covers the picture. The bot clicks it away, "
                     + "but it has to know it. Frame only the letters of the text – not the reward "
                     + "pictures above it."
                   : "Nur nötig, solange das Event läuft. Nach jeder Kathedralen-Reise legt sich "
                     + "der Text <b>\"You have received a reward\"</b> über das Bild. Der Bot klickt "
                     + "ihn weg, muss ihn dafür aber kennen. Rahme nur die Buchstaben des Textes "
                     + "ein – nicht die Belohnungsbilder darüber.", Ort.BELOHNUNG));
    }

    static {
        // EIGENE EVENT-MOTIVE (Autor, 02.10.): "dass man bei Sachen, die der Bot nehmen soll, mit
        // Capture ein Bild aufnimmt. Das nimmt der Bot dann im Event." Ist die Aufnahme nicht ganz
        // sauber, schneidet der Bot das Motiv im Event selbst nach (EventMode, ..._auto).
        boolean en = Sprache.istEnglisch();
        for (int i = 1; i <= 10; i++) {
            ZUSATZ.put("ev_kiste_eigen_" + i, new Eintrag(
                    en ? "Own event item " + i + "  (optional)" : "Eigenes Event-Motiv " + i + "  (freiwillig)",
                    en ? "Something the bot should ALWAYS take in the hammer event (e.g. arena tickets). "
                         + "Uncover it with a magnifier, then frame <b>only the item inside the glass vase</b> "
                         + "– not the vase rim. If the picture is not perfect, the bot cuts it again by itself "
                         + "during the event. Skip if you do not need it."
                       : "Etwas, das der Bot im Hammer-Event IMMER mitnehmen soll (z. B. Arena-Tickets). "
                         + "Mit der Lupe aufdecken, dann <b>nur das Teil in der Glasvase</b> einrahmen – nicht "
                         + "den Glasrand. Ist das Bild nicht ganz sauber, schneidet der Bot es im Event selbst "
                         + "nach. Überspringen, wenn du es nicht brauchst.",
                    Ort.EVENT));
        }
    }

    /** Der Eintrag zu einem Schrittnamen -- eigenes Bild oder Zusatzbild. */
    private static Eintrag eintrag(String name) {
        Eintrag e = EIGENE.get(name);
        return e != null ? e : ZUSATZ.get(name);
    }

    /** Fuer die Selbstpruefung beim Paketbau: welche Namen das Werkzeug anbietet. */
    public static Set<String> eigeneBilder() {
        return java.util.Collections.unmodifiableSet(EIGENE.keySet());
    }

    // ==================================================================
    //  Autor 24.10. (Ideenliste F13-F23): Rahmen pruefen, Rueckgaengig, Meine Bilder, alles pruefen
    // ==================================================================

    private void auswahlLeeren() {
        pruefLauf++;   // ein noch laufendes Ergebnis ist damit veraltet
        vorschauBild.setIcon(null);
        pruefung.setText("");
    }

    private void auswahlBeginnt() {
        auswahlLeeren();
    }

    private static String farbeFuer(Aufnahmepruefung.Stufe stufe) {
        return switch (stufe) {
            case GRUEN -> "#1b7a1b";
            case GELB -> "#b35a00";
            case ROT -> "#b00000";
        };
    }

    private static String symbolFuer(Aufnahmepruefung.Stufe stufe) {
        return switch (stufe) {
            case GRUEN -> "✓";
            case GELB -> "⚠";
            case ROT -> "✗";
        };
    }

    /** F13 + F17: Rahmen fertig gezogen -> Vorschau in dreifacher Groesse, Qualitaet und Test-Suche (im Hintergrund). */
    private void auswahlGeaendert() {
        BufferedImage f = frame;
        Rectangle s = selection == null ? null : new Rectangle(selection);
        if (f == null || s == null || s.width < 6 || s.height < 6) {
            auswahlLeeren();
            return;
        }
        BufferedImage v = Aufnahmepruefung.vorschau(f, s, 330, 66);
        vorschauBild.setIcon(v == null ? null : new ImageIcon(v));
        int lauf = ++pruefLauf;
        pruefung.setText(farbig("#606060", Sprache.t("Checking the frame …", "Prüfe den Rahmen …")));
        Thread t = new Thread(() -> {
            Aufnahmepruefung.Bericht b = Aufnahmepruefung.bewerte(f, s);
            SwingUtilities.invokeLater(() -> {
                if (lauf != pruefLauf) return;   // inzwischen ein anderer Rahmen
                pruefung.setText(b == null ? "" : farbig(farbeFuer(b.stufe()), symbolFuer(b.stufe()) + " " + b.text()));
            });
        }, "Aufnahme-Pruefung");
        t.setDaemon(true);
        t.start();
    }

    /** F18: "Bild anpassen" ein- oder ausschalten. Angepasst passt das Foto immer in die Breite, es rollt nur noch senkrecht. */
    private void anpassenUmstellen() {
        boolean an = anpassenBox.isSelected();
        bildRolle.setHorizontalScrollBarPolicy(an ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        imagePanel.revalidate();
        imagePanel.repaint();
    }

    /** F19: die letzte Aktion zuruecknehmen (Bild speichern, Namen eintippen, Bilder entfernen). */
    private void rueckgaengig() {
        Bildablage.Journal.Eintrag e = journal.zuruecknehmen();
        if (e == null) {
            info.setText(Sprache.t("Nothing to take back.", "Es gibt nichts zurückzunehmen."));
            rueckgaengigAktualisieren();
            return;
        }
        if (e.schritt() >= 0 && e.schritt() < reihenfolge.size()) schritt = e.schritt();
        zeigeSchritt();
        info.setText(Sprache.t("Taken back: ", "Zurückgenommen: ") + e.text());
        rueckgaengigAktualisieren();
    }

    private void rueckgaengigAktualisieren() {
        rueckgaengigKnopf.setEnabled(!journal.leer());
        String letztes = journal.oberster();
        rueckgaengigKnopf.setToolTipText(letztes == null
                ? Sprache.t("Nothing to take back yet.", "Noch nichts zum Zurücknehmen.")
                : Sprache.t("Takes back: ", "Nimmt zurück: ") + letztes);
    }

    /** F19: die Liste aller eigenen Bilder. */
    private void meineBilder() {
        MeineBilder.zeigen(this, Eigenbilder.wurzel(botOrdner()), journal, () -> {
            zeigeSchritt();
            rueckgaengigAktualisieren();
        });
    }

    /** Der Name eines Bildschirms fuer Listen: ohne Klammern, erster Buchstabe gross. */
    private static String ortKurz(Ort ort) {
        String t = ort.text.replaceAll(" \\(.*$", "");
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Die Datei eines eigenen Bildes (My pictures, bei den Pflichtbildern auch die aelteren Orte); null, wenn es keine gibt. */
    private Path eigeneDatei(String name) {
        Path p = ordnerFuer(name).resolve(name + ".png");
        if (Files.exists(p)) return p;
        if (!EIGENE.containsKey(name)) return null;   // Zusatzbilder liegen fertig im Paket -- zaehlen nur aus "My pictures"
        Path wurzel = vorlagenWurzel();
        for (Path q : List.of(wurzel.resolve("dungeon").resolve(name + ".png"), wurzel.resolve("cathedral").resolve(name + ".png"),
                wurzel.resolve("clanwar").resolve(name + ".png"), wurzel.resolve(name + ".png"))) {
            if (Files.exists(q)) return q;
        }
        return null;
    }

    private List<Bilderpruefung.Kandidat> eigeneKandidaten() {
        List<Bilderpruefung.Kandidat> l = new ArrayList<>();
        for (String name : reihenfolge) {
            if (name.equals(FREUND_PLATZHALTER)) continue;
            Path d = eigeneDatei(name);
            if (d == null) continue;
            Eintrag e = eintrag(name);
            l.add(new Bilderpruefung.Kandidat(e.kurz().replaceAll("\\s*\\(.*\\)\\s*$", "").strip(), ortKurz(e.ort()), d));
        }
        for (Path d : freundesDateien()) {
            String n = d.getFileName().toString();
            l.add(new Bilderpruefung.Kandidat(Sprache.t("Friend: ", "Freund: ") + n.substring(FREUND_PLATZHALTER.length(), n.length() - 4),
                    ortKurz(Ort.FREUNDESLISTE), d));
        }
        return l;
    }

    /**
     * F23: Macht ein frisches Foto vom Spiel (laeuft es nicht: das geladene Bild) und prueft, welche eigenen Bilder darin
     * gefunden werden -- die Selbstpruefung nach dem Aufnehmen. Gerechnet wird im Hintergrund.
     */
    private void allePruefen() {
        if (pruefungLaeuft) return;
        List<Bilderpruefung.Kandidat> kandidaten = eigeneKandidaten();
        if (kandidaten.isEmpty()) {
            info.setText(Sprache.t("You have no pictures of your own yet - capture some first.", "Du hast noch keine eigenen Bilder - nimm zuerst welche auf."));
            return;
        }
        BufferedImage foto = null;
        if (window.locate()) {
            window.prepare();
            Rectangle bereich = window.clientArea();
            if (bereich != null) foto = capture.grab(bereich);
        }
        if (foto == null) foto = frame;
        if (foto == null) {
            info.setText(Sprache.t("The game window cannot be found and no picture is loaded. Start Hustle Castle or take a picture first.",
                    "Das Spielfenster ist nicht zu finden und es ist kein Bild geladen. Starte Hustle Castle oder mach zuerst ein Bild."));
            return;
        }
        final BufferedImage fotoEnd = foto;
        final String erwartet = ortKurz(eintrag(jetzigesBild()).ort());
        pruefungLaeuft = true;
        info.setText(Sprache.t("Checking your pictures …", "Prüfe deine Bilder …"));
        Thread t = new Thread(() -> {
            List<Bilderpruefung.Zeile> z = null;
            try {
                z = Bilderpruefung.pruefe(fotoEnd, kandidaten, erwartet,
                        n -> SwingUtilities.invokeLater(() -> info.setText(Sprache.t("Checking picture ", "Prüfe Bild ") + n
                                + Sprache.t(" of ", " von ") + kandidaten.size() + " …")));
            } catch (Throwable ex) {
                Log.debug("Vorlagen", "Pruefung fehlgeschlagen: " + ex);
            }
            List<Bilderpruefung.Zeile> ergebnis = z;
            SwingUtilities.invokeLater(() -> {
                pruefungLaeuft = false;
                toFront();
                if (ergebnis == null) {
                    info.setText(Sprache.t("The check did not work.", "Die Prüfung hat nicht geklappt."));
                    return;
                }
                long gefunden = ergebnis.stream().filter(x -> x.status() == Bilderpruefung.Status.GEFUNDEN).count();
                long fehlt = ergebnis.stream().filter(x -> x.status() == Bilderpruefung.Status.FEHLT_HIER).count();
                info.setText(Sprache.t("Check done: " + gefunden + " of " + ergebnis.size() + " found" + (fehlt > 0 ? ", " + fehlt + " missing on this screen." : "."),
                        "Prüfung fertig: " + gefunden + " von " + ergebnis.size() + " gefunden" + (fehlt > 0 ? ", " + fehlt + " fehlen auf diesem Bildschirm." : ".")));
                Bilderpruefung.zeigeErgebnis(this, ergebnis);
            });
        }, "Bilder-Pruefung");
        t.setDaemon(true);
        t.start();
    }

    // ==================================================================
    //  Die Bildflaeche mit dem Auswahlrahmen
    // ==================================================================

    private final class ImagePanel extends JPanel {
        private BufferedImage image;

        ImagePanel() {
            setBackground(new Color(30, 30, 34));
            MouseAdapter adapter = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    // Ohne Foto gibt es nichts einzurahmen.
                    if (image == null) {
                        dragStart = null;
                        return;
                    }
                    dragStart = zuBild(e.getPoint());
                    selection = new Rectangle(dragStart);
                    auswahlBeginnt();
                    repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    Point p = zuBild(e.getPoint());
                    int x = Math.min(dragStart.x, p.x);
                    int y = Math.min(dragStart.y, p.y);
                    selection = new Rectangle(x, y,
                            Math.abs(p.x - dragStart.x),
                            Math.abs(p.y - dragStart.y));
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (selection != null) {
                        info.setText(Sprache.t("Frame: ", "Rahmen: ") + selection.width + "x" + selection.height
                                + Sprache.t(" pixels – now click \"", " Punkte – jetzt auf \"") + speichernKnopf.getText()
                                + Sprache.t("\". Missed it? Just draw a new frame.",
                                            "\" klicken. Nicht getroffen? Einfach einen neuen Rahmen ziehen."));
                        auswahlGeaendert();   // Autor 24.10. (F13/F17): Vorschau und Test-Suche
                    }
                }
            };
            addMouseListener(adapter);
            addMouseMotionListener(adapter);
        }

        /**
         * Autor 24.10. (F18): Mit "Bild anpassen" wird das Foto verkleinert gezeigt, damit es ins Fenster passt (nie
         * vergroessert) -- aber nie kleiner als 75 %, sonst laesst sich nicht mehr genau einrahmen: Was dann in der Hoehe nicht
         * mehr passt, rollt senkrecht. In der Breite passt es immer. Rahmen und Ausschnitt rechnen weiter in Punkten des
         * ORIGINALFOTOS -- gespeichert wird nichts Verkleinertes.
         */
        double faktor() {
            if (image == null || !anpassenBox.isSelected() || !(getParent() instanceof JViewport vp)) return 1.0;
            if (vp.getWidth() <= 0 || vp.getHeight() <= 0) return 1.0;
            double breite = (vp.getWidth() - 18) / (double) image.getWidth();   // 18 = Platz fuer den Rollbalken rechts
            double hoehe = vp.getHeight() / (double) image.getHeight();
            return Math.max(0.1, Math.min(1.0, Math.min(breite, Math.max(hoehe, 0.75))));
        }

        /** Mauspunkt auf dem Bildschirm -> Punkt im Originalfoto. */
        private Point zuBild(Point p) {
            double f = faktor();
            return new Point((int) Math.round(p.x / f), (int) Math.round(p.y / f));
        }

        @Override public Dimension getPreferredSize() {
            if (image == null) return super.getPreferredSize();
            double f = faktor();
            return new Dimension((int) (image.getWidth() * f), (int) (image.getHeight() * f));
        }

        void setImage(BufferedImage img) {
            this.image = img;
            revalidate();
            repaint();
        }

        /** Nimmt das Foto weg -- die Flaeche ist wieder leer wie beim Start. */
        void leeren() {
            this.image = null;
            revalidate();
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) {
                double f = faktor();
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    if (f < 1.0) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.scale(f, f);
                    g2.drawImage(image, 0, 0, null);
                    if (selection != null) {
                        g2.setColor(new Color(255, 122, 26));
                        g2.setStroke(new BasicStroke((float) (2 / f)));
                        g2.drawRect(selection.x, selection.y, selection.width, selection.height);
                        g2.setColor(new Color(255, 122, 26, 45));
                        g2.fillRect(selection.x, selection.y, selection.width, selection.height);
                    }
                } finally {
                    g2.dispose();
                }
            } else {
                g.setColor(new Color(200, 200, 205));
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 15f));
                g.drawString(Sprache.t("No picture yet. Open the right screen in the game and click \"Take picture\" at the top.",
                        "Noch kein Bild. Öffne im Spiel den richtigen Bildschirm "
                        + "und klick oben auf \"Bild machen\"."), 24, 40);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Erst den eigenen Ordner bestimmen. Sonst legt das Werkzeug beim
        // Doppelklick auf die .exe seine Bilder irgendwo ab -- nur nicht dort,
        // wo der Bot sie spaeter sucht.
        Path heim = de.hustlebot.core.Ordner.basis();

        nu.pattern.OpenCV.loadLocally();
        Log.init(heim.resolve("logs"));

        Path ordner = args.length > 0 ? Path.of(args[0])
                                      : heim.resolve("templates").resolve("dungeon");
        Files.createDirectories(ordner);

        SwingUtilities.invokeLater(() -> {
            try {
                new Bilderwerkzeug(ordner, "Hustle Castle", 1175, 809).setVisible(true);
            } catch (AWTException e) {
                JOptionPane.showMessageDialog(null, Sprache.t("Could not start: ", "Konnte nicht starten: ") + e.getMessage());
            }
        });
    }
}
