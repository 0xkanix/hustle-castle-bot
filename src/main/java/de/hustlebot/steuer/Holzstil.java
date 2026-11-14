package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Aussehen des Steuerprogramms an einer Stelle: Holz-Toene, Schriften, Knoepfe, Karten, Tabellen.
 *
 * Autors Wahl (doku/STEUERPROGRAMM.md, 11.10.): Holz-Toene, grosse Knoepfe, klare Reiter.
 * Autor, 11.10. abends (Bild): "Die Farbtoene sind nicht angenehm anzuschauen, gestalte es um." Darum
 * ruhiger: heller Creme-Grund, dunkles Nussbaum nur fuer die Kopfleiste, flache Knoepfe mit runden Ecken.
 * Autor, 11.10. spaet (Auftrag 4): "Mach es noch schoener, mehr anschaubar, soll angenehm sein fuer die
 * Augen." Darum jetzt:
 *   - Alles, was zusammengehoert, steht auf einer hellen Karte mit runden Ecken und leiser Ueberschrift.
 *   - Mehr Luft (LUFT), einheitliche Hoehen (Knoepfe 38, kleine Knoepfe 30, Felder 30).
 *   - Knoepfe koennen ein kleines Symbol tragen (Symbol), damit man sie auf einen Blick erkennt.
 *   - Ein Zustand hat immer dieselbe Farbe: gruen laeuft, bernstein wartet/warnt, rot Fehler, grau still.
 *   - Hell und Dunkel (thema): beide mit Kontrast mindestens 4,5:1 fuer Schrift (WCAG AA, HolzstilTest
 *     rechnet es nach). Standard hell.
 *
 * Die Farben sind absichtlich keine Konstanten mehr: thema() tauscht sie aus, bevor die Fenster gebaut
 * werden. Alles hier ist ohne Bildschirm nutzbar (nur Farben und Fabrikmethoden).
 */
public final class Holzstil {

    // ------------------------------------------------------------------
    //  Farben -- werden von thema() gesetzt
    // ------------------------------------------------------------------

    /** Grundflaeche hinter allem: helles, warmes Creme (dunkel: dunkles Holz). */
    public static Color GRUND;
    /** Kopfleiste: dunkles Nussbaum (auch Akzent, etwa der Strich unter dem gewaehlten Reiter). */
    public static Color KOPF;
    /** Schrift. */
    public static Color TEXT;
    /** Helle Flaechen: Karten, Eingabefelder, Lesebereiche; Schrift auf dunklem Holz. */
    public static Color HELL;
    /** Helles Holz: normale Knoepfe, Tabellenkoepfe. */
    public static Color FELD;
    /** Feine Linien: Rahmen von Feldern, Karten, Listen. */
    public static Color LINIE;
    /** Leise Schrift: Hinweise, Ueberschriften von Karten, Uhrzeiten. */
    public static Color LEISE;
    /** Jede zweite Zeile einer Tabelle. */
    public static Color ZEBRA;
    /** Gruen fuer "laeuft", Start und Erfolg -- gedeckt, damit es zum Holz passt. */
    public static Color GUT;
    /** Bernstein fuer Warnungen und "wartet". */
    public static Color WARNUNG;
    /** Rot fuer Fehler und den Not-Aus -- gedeckt, nicht grell. */
    public static Color FEHLER;
    /** Blau-Grau fuer Klicks im Protokoll und neutrale Diagramme. */
    public static Color KLICK;
    /** Schrift auf einem Knopf in Signalfarbe (hell: weiss; dunkel: dunkles Holz, weil die Signalfarben hell sind). */
    public static Color AUF_SIGNAL;

    private static boolean dunkel;

    static {
        thema(false);
    }

    /**
     * Hell oder dunkel. Vor dem Bauen der Fenster rufen; schon gebaute Fenster malen sich nicht um
     * (Steuerprogramm baut sie dann neu).
     */
    public static void thema(boolean dunkelesThema) {
        dunkel = dunkelesThema;
        if (dunkel) {
            GRUND      = new Color(0x2A, 0x21, 0x19);
            KOPF       = new Color(0x1C, 0x15, 0x10);
            TEXT       = new Color(0xF2, 0xE8, 0xDA);
            HELL       = new Color(0x36, 0x2B, 0x22);
            FELD       = new Color(0x4A, 0x3B, 0x2F);
            LINIE      = new Color(0x5E, 0x4D, 0x3E);
            LEISE      = new Color(0xC9, 0xB5, 0x9E);
            ZEBRA      = new Color(0x3D, 0x31, 0x27);
            GUT        = new Color(0x6F, 0xAF, 0x68);
            WARNUNG    = new Color(0xD9, 0xA0, 0x50);
            FEHLER     = new Color(0xE5, 0x7A, 0x6E);
            KLICK      = new Color(0x8F, 0xB5, 0xC9);
            AUF_SIGNAL = new Color(0x1C, 0x15, 0x10);
        } else {
            GRUND      = new Color(0xF4, 0xED, 0xE2);
            KOPF       = new Color(0x4A, 0x35, 0x27);
            TEXT       = new Color(0x2F, 0x22, 0x18);
            HELL       = new Color(0xFF, 0xFC, 0xF7);
            FELD       = new Color(0xE9, 0xDC, 0xC7);
            LINIE      = new Color(0xD5, 0xC3, 0xA6);
            LEISE      = new Color(0x6B, 0x53, 0x40);
            ZEBRA      = new Color(0xF7, 0xF1, 0xE8);
            GUT        = new Color(0x3D, 0x73, 0x39);
            WARNUNG    = new Color(0x9C, 0x5A, 0x12);
            FEHLER     = new Color(0xA8, 0x34, 0x2A);
            KLICK      = new Color(0x3E, 0x66, 0x7A);
            AUF_SIGNAL = new Color(0xFF, 0xFC, 0xF7);
        }
    }

    public static boolean istDunkel() {
        return dunkel;
    }

    // ------------------------------------------------------------------
    //  Schriften und Masse
    // ------------------------------------------------------------------

    /**
     * Autor 24.10. (Ideenliste F34): Schriftgroesse klein / mittel / gross / sehr gross. Auf hochaufloesenden Bildschirmen war das Programm
     * zu klein, auf kleinen zu gross. Alle Schriften, Hoehen und Abstaende hier rechnen mit EINEM Faktor; er wird beim Start aus
     * steuerprogramm.properties (Schluessel "schrift") gelesen -- eine Aenderung gilt darum beim naechsten Start des Bot-Programms
     * (Fenster live umzubauen waere fehleranfaellig; die Schriften sind Konstanten).
     */
    static final String[] SCHRIFT_STUFEN = {"klein", "mittel", "gross", "riesig"};
    private static final double[] SCHRIFT_FAKTOREN = {0.9, 1.0, 1.25, 1.5};

    /** Die Stufe (Stelle in SCHRIFT_STUFEN) zu einem Wort; unbekannt oder leer = "mittel". */
    static int stufeAus(String wort) {
        String w = wort == null ? "" : wort.strip().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < SCHRIFT_STUFEN.length; i++) if (SCHRIFT_STUFEN[i].equals(w)) return i;
        return 1;
    }

    private static final int STUFE = stufeLesen();
    /** Der Faktor, mit dem alle Schriften und Masse hier gerechnet sind (1,0 = wie bisher). */
    static final double SKALA = SCHRIFT_FAKTOREN[STUFE];

    private static int stufeLesen() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("steuerprogramm.properties");
            if (java.nio.file.Files.isRegularFile(p)) {
                java.util.Properties pr = new java.util.Properties();
                try (var in = java.nio.file.Files.newBufferedReader(p, java.nio.charset.StandardCharsets.UTF_8)) {
                    pr.load(in);
                }
                return stufeAus(pr.getProperty(Steuereinstellungen.SCHRIFT));
            }
        } catch (Exception ignored) {
            // ohne lesbare Datei: mittel
        }
        return 1;
    }

    /** Welche Stufe gerade gilt (0 klein ... 3 sehr gross). */
    static int schriftStufe() {
        return STUFE;
    }

    /** Ein Mass (Punkte) mit der Schrift-Skala. */
    static int px(int wert) {
        return (int) Math.round(wert * SKALA);
    }

    private static final String SCHRIFT = schrift();
    public static final Font GROSS   = new Font(SCHRIFT, Font.BOLD, px(17));
    public static final Font NORMAL  = new Font(SCHRIFT, Font.PLAIN, px(13));
    public static final Font KLEIN   = new Font(SCHRIFT, Font.PLAIN, px(12));
    public static final Font KNOPF   = new Font(SCHRIFT, Font.BOLD, px(13));
    public static final Font FEST    = new Font(Font.MONOSPACED, Font.PLAIN, px(13));

    /** Abstand zum Bildschirmrand. */
    public static final int RAND = 20;
    /** Luft zwischen Karten und um Reiter-Inhalte. */
    public static final int LUFT = px(12);
    /** Luft zwischen Dingen auf einer Karte. */
    public static final int LUFT_KLEIN = px(6);
    /** Hoehe grosser Knoepfe und Felder, damit Zeilen gleich hoch sind. */
    public static final int HOEHE_GROSS = px(38);
    /** Hoehe kleiner Knoepfe, Felder und Auswahlen. */
    public static final int HOEHE_KLEIN = px(30);
    /** Rundung der Ecken. */
    public static final int RUNDUNG = 12;

    private Holzstil() {
    }

    /** Segoe UI, wenn Windows sie hat (die Windows-Schrift), sonst die Java-Standardschrift. */
    private static String schrift() {
        try {
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                if (f.equals("Segoe UI")) return f;
            }
        } catch (Exception ignored) {
            // ohne Bildschirm (Tests): Standardschrift
        }
        return Font.SANS_SERIF;
    }

    /** Zwei Farben mischen: anteil 0 = a, 1 = b. */
    public static Color mischen(Color a, Color b, double anteil) {
        double p = Math.max(0, Math.min(1, anteil));
        return new Color((int) Math.round(a.getRed() * (1 - p) + b.getRed() * p),
                (int) Math.round(a.getGreen() * (1 - p) + b.getGreen() * p),
                (int) Math.round(a.getBlue() * (1 - p) + b.getBlue() * p));
    }

    /** Dieselbe Farbe durchsichtig. */
    public static Color durchsichtig(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /** Etwas dunkler (hell) bzw. heller (dunkel) -- fuer "Maus darueber". */
    static Color betont(Color c, double anteil) {
        return mischen(c, dunkel ? Color.WHITE : Color.BLACK, anteil);
    }

    /**
     * Kontrast nach WCAG (1 bis 21). Schrift braucht mindestens 4,5, grosse Schrift 3.
     * Steht hier, damit der Test dieselbe Rechnung benutzt wie die Doku verspricht.
     */
    public static double kontrast(Color a, Color b) {
        double la = leuchtdichte(a), lb = leuchtdichte(b);
        double hell = Math.max(la, lb), dunkelW = Math.min(la, lb);
        return (hell + 0.05) / (dunkelW + 0.05);
    }

    private static double leuchtdichte(Color c) {
        return 0.2126 * kanal(c.getRed()) + 0.7152 * kanal(c.getGreen()) + 0.0722 * kanal(c.getBlue());
    }

    private static double kanal(int wert) {
        double v = wert / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static Graphics2D weich(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    // ------------------------------------------------------------------
    //  Knoepfe
    // ------------------------------------------------------------------

    /**
     * Ein flacher Knopf mit runden Ecken und wahlweise einem Symbol links vom Text. Gemalt wird selbst:
     * so sieht er unter jedem Windows-Aussehen gleich aus, wird beim Darueberfahren etwas dunkler, beim
     * Druecken noch etwas, und ausgegraut blass. Fuellfarbe ist getBackground(), Schrift getForeground().
     */
    public static final class Knopf extends JButton {
        private boolean ueber;
        private int mindestHoehe = HOEHE_GROSS;

        Knopf(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setIconTextGap(8);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { ueber = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { ueber = false; repaint(); }
            });
        }

        void mindestHoehe(int h) {
            mindestHoehe = h;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, mindestHoehe));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = weich(g);
            Color fuellung = getBackground(), schrift = getForeground();
            if (!isEnabled()) {
                fuellung = mischen(fuellung, GRUND, 0.65);
                schrift = mischen(schrift, fuellung, 0.55);
            } else if (getModel().isPressed()) {
                fuellung = betont(fuellung, 0.18);
            } else if (ueber || getModel().isRollover()) {
                fuellung = betont(fuellung, 0.08);
            }
            int w = getWidth(), h = getHeight();
            g2.setColor(fuellung);
            g2.fillRoundRect(0, 0, w - 1, h - 1, RUNDUNG, RUNDUNG);
            g2.setColor(mischen(fuellung, dunkel ? Color.WHITE : Color.BLACK, isEnabled() ? 0.14 : 0.06));
            g2.drawRoundRect(0, 0, w - 1, h - 1, RUNDUNG, RUNDUNG);
            if (hasFocus() && isFocusPainted()) {
                g2.setColor(durchsichtig(schrift, 90));
                g2.drawRoundRect(2, 2, w - 5, h - 5, RUNDUNG - 3, RUNDUNG - 3);
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            String text = getText() == null ? "" : getText();
            Icon icon = getIcon();
            int textB = fm.stringWidth(text);
            int iconB = icon == null ? 0 : icon.getIconWidth() + (text.isEmpty() ? 0 : getIconTextGap());
            int x = (w - textB - iconB) / 2;
            if (icon != null) {
                int iy = (h - icon.getIconHeight()) / 2;
                if (icon instanceof Symbol s) s.malen(g2, x, iy, schrift);   // in der Schriftfarbe
                else icon.paintIcon(this, g2, x, iy);
                x += iconB;
            }
            g2.setColor(schrift);
            g2.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Ein grosser Knopf in hellem Holz mit dunkler Schrift. */
    public static JButton knopf(String text, String hinweis) {
        Knopf k = new Knopf(text);
        k.setFont(KNOPF);
        k.setBackground(FELD);
        k.setForeground(TEXT);
        k.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        if (hinweis != null && !hinweis.isBlank()) k.setToolTipText(hinweis);
        return k;
    }

    /** Ein grosser Knopf mit Symbol. */
    public static JButton knopf(String text, String hinweis, Symbol.Art symbol) {
        JButton k = knopf(text, hinweis);
        k.setIcon(new Symbol(symbol, 15));
        return k;
    }

    /** Ein Knopf in einer Signalfarbe (Not-Aus rot, Start gruen). */
    public static JButton knopf(String text, String hinweis, Color farbe) {
        JButton k = knopf(text, hinweis);
        k.setBackground(farbe);
        k.setForeground(AUF_SIGNAL);
        return k;
    }

    /** Ein Knopf in einer Signalfarbe mit Symbol. */
    public static JButton knopf(String text, String hinweis, Color farbe, Symbol.Art symbol) {
        JButton k = knopf(text, hinweis, farbe);
        k.setIcon(new Symbol(symbol, 15));
        return k;
    }

    /** Ein kleiner Knopf, etwa neben einem Eingabefeld. */
    public static JButton kleinerKnopf(String text, String hinweis) {
        Knopf k = (Knopf) knopf(text, hinweis);
        k.setFont(KLEIN);
        k.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        k.mindestHoehe(HOEHE_KLEIN);
        return k;
    }

    /** Ein kleiner Knopf mit Symbol. */
    public static JButton kleinerKnopf(String text, String hinweis, Symbol.Art symbol) {
        JButton k = kleinerKnopf(text, hinweis);
        k.setIcon(new Symbol(symbol, 12));
        return k;
    }

    /** Ein kleiner Knopf, der nur ein Symbol zeigt (etwa das Kreuz zum Schliessen). */
    public static JButton symbolKnopf(Symbol.Art symbol, String hinweis) {
        Knopf k = (Knopf) kleinerKnopf("", hinweis);
        k.setIcon(new Symbol(symbol, 12));
        k.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return k;
    }

    // ------------------------------------------------------------------
    //  Flaechen, Karten, Kopfleiste
    // ------------------------------------------------------------------

    /**
     * Eine durchsichtige Flaeche: nimmt die Farbe dessen an, worauf sie liegt (Karte oder Grund).
     * So muss nicht jede Zeile wissen, ob sie auf einer hellen Karte steht.
     */
    public static JPanel flaeche(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    /** Eine durchsichtige Flaeche mit Luft rundherum. */
    public static JPanel flaeche(LayoutManager layout, int luft) {
        JPanel p = flaeche(layout);
        p.setBorder(BorderFactory.createEmptyBorder(luft, luft, luft, luft));
        return p;
    }

    /** Eine Flaeche im Grundton (fuer den Inhalt eines Reiters oder Fensters). */
    public static JPanel grund(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(GRUND);
        return p;
    }

    /** Eine Flaeche im Grundton mit Luft rundherum. */
    public static JPanel grund(LayoutManager layout, int luft) {
        JPanel p = grund(layout);
        p.setBorder(BorderFactory.createEmptyBorder(luft, luft, luft, luft));
        return p;
    }

    /**
     * Eine Karte: helle Flaeche mit runden Ecken, feiner Linie und leiser Ueberschrift oben links.
     * Alles, was zusammengehoert (ein Bereich der Einstellungen, die Start-Felder), steht auf einer Karte.
     */
    public static JPanel karte(String titel, LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        p.setBorder(bereich(titel));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Eine Karte ohne Ueberschrift. */
    public static JPanel karte(LayoutManager layout) {
        return karte(null, layout);
    }

    /**
     * Der Rahmen einer Karte, auch einzeln nutzbar (setBorder). Fuellt die Flaeche hell, zieht die
     * runde Linie und schreibt den Titel -- ein Rahmen malt vor den Kindern, deshalb geht das.
     */
    public static Border bereich(String titel) {
        return new Kartenrahmen(titel);
    }

    static final class Kartenrahmen extends AbstractBorder {
        private final String titel;

        Kartenrahmen(String titel) {
            this.titel = titel == null || titel.isBlank() ? null : titel;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = weich(g);
            g2.setColor(HELL);
            g2.fillRoundRect(x, y, w - 1, h - 1, RUNDUNG, RUNDUNG);
            g2.setColor(LINIE);
            g2.drawRoundRect(x, y, w - 1, h - 1, RUNDUNG, RUNDUNG);
            if (titel != null) {
                g2.setFont(KNOPF);
                g2.setColor(LEISE);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(titel, x + 14, y + 8 + fm.getAscent());
            }
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return getBorderInsets(c, new Insets(0, 0, 0, 0));
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            int oben = titel == null ? 12 : 34;
            i.set(oben, 14, 12, 14);
            return i;
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /**
     * Die dunkle Kopfleiste mit heller Ueberschrift links. Rechts laesst sich etwas dazustellen
     * (etwa die Zustands-Pille), unten eine Zeile (BorderLayout.SOUTH).
     */
    public static JPanel kopfleiste(String titel) {
        return kopfleiste(titel, null);
    }

    public static JPanel kopfleiste(String titel, JComponent rechts) {
        JPanel p = new JPanel(new BorderLayout(LUFT, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Ein Hauch heller oben, wie ein Brett im Licht -- kein Verlauf, der ins Auge springt.
                g2.setPaint(new GradientPaint(0, 0, betont(KOPF, 0.07), 0, getHeight(), KOPF));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(betont(KOPF, 0.18));
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        p.setOpaque(true);
        p.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        JLabel l = new JLabel(titel);
        l.setFont(GROSS);
        l.setForeground(aufKopf());
        p.add(l, BorderLayout.WEST);
        if (rechts != null) p.add(rechts, BorderLayout.EAST);
        return p;
    }

    /** Schrift auf der Kopfleiste. */
    public static Color aufKopf() {
        return dunkel ? TEXT : HELL;
    }

    /** Leise Schrift auf der Kopfleiste (die Zeile darunter). */
    public static Color leiseAufKopf() {
        return mischen(aufKopf(), KOPF, 0.3);
    }

    /** Eine Zeile unter der Ueberschrift der Kopfleiste. */
    public static JLabel kopfzeile(String text) {
        JLabel l = new JLabel(text);
        l.setFont(NORMAL);
        l.setForeground(leiseAufKopf());
        return l;
    }

    /** Eine waagerechte feine Linie mit Luft darueber und darunter. */
    public static JComponent trennlinie() {
        JComponent t = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(LINIE);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        t.setPreferredSize(new Dimension(10, LUFT));
        t.setMaximumSize(new Dimension(Integer.MAX_VALUE, LUFT));
        return t;
    }

    // ------------------------------------------------------------------
    //  Beschriftungen
    // ------------------------------------------------------------------

    /** Eine Beschriftung in Textfarbe. */
    public static JLabel beschriftung(String text) {
        JLabel l = new JLabel(text);
        l.setFont(NORMAL);
        l.setForeground(TEXT);
        return l;
    }

    /** Eine Beschriftung mit fester Breite, damit Felder darunter buendig stehen. */
    public static JLabel beschriftung(String text, int breite) {
        JLabel l = beschriftung(text);
        l.setPreferredSize(new Dimension(breite, HOEHE_KLEIN));
        return l;
    }

    /** Eine kleine, leise Beschriftung (Erklaerungen unter einem Feld). */
    public static JLabel hinweis(String text) {
        JLabel l = new JLabel(text);
        l.setFont(KLEIN);
        l.setForeground(LEISE);
        return l;
    }

    /** Eine Ueberschrift innerhalb eines Reiters. */
    public static JLabel ueberschrift(String text) {
        JLabel l = new JLabel(text);
        l.setFont(GROSS);
        l.setForeground(TEXT);
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        return l;
    }

    // ------------------------------------------------------------------
    //  Eingaben
    // ------------------------------------------------------------------

    /** Rahmen fuer Felder: feine runde Linie, innen Luft. */
    private static Border feldrahmen() {
        return BorderFactory.createCompoundBorder(
                new Rundrahmen(LINIE), BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    /**
     * Eine runde Linie als Rahmen (Felder, Auswahlen). Das Feld selbst ist eckig und undurchsichtig; der
     * Rahmen deckt die vier Ecken mit der Farbe der Unterlage ab, dann sieht es rund aus. (Fuellen darf
     * der Rahmen nicht -- er malt NACH dem Inhalt und wuerde die Schrift zudecken.)
     */
    static final class Rundrahmen extends AbstractBorder {
        private final Color farbe;

        Rundrahmen(Color farbe) {
            this.farbe = farbe;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = weich(g);
            java.awt.geom.Area ecken = new java.awt.geom.Area(new Rectangle(x, y, w, h));
            ecken.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(x, y, w - 1, h - 1, 8, 8)));
            g2.setColor(unterlage(c));
            g2.fill(ecken);
            g2.setColor(c.hasFocus() ? KOPF : farbe);
            g2.drawRoundRect(x, y, w - 1, h - 1, 8, 8);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c, Insets i) {
            i.set(2, 2, 2, 2);
            return i;
        }
    }

    /** Die Farbe dessen, worauf ein Bauteil liegt: der naechste undurchsichtige Vorfahr oder eine Karte. */
    static Color unterlage(Component c) {
        Container p = c.getParent();
        while (p != null) {
            if (p instanceof JComponent jc && jc.getBorder() instanceof Kartenrahmen) return HELL;
            if (p instanceof JComponent jc && jc.isOpaque() && jc.getBackground() != null) return jc.getBackground();
            if (!(p instanceof JComponent) && p.getBackground() != null) return p.getBackground();
            p = p.getParent();
        }
        return GRUND;
    }

    /** Ein Eingabefeld in heller Farbe mit feinem Rahmen. */
    public static JTextField feld(String inhalt, int spalten) {
        JTextField f = new JTextField(inhalt, spalten);
        feldStil(f);
        return f;
    }

    /** Ein Feld fuer ein Passwort oder einen Token. */
    public static JPasswordField geheimfeld(int spalten) {
        JPasswordField f = new JPasswordField(spalten);
        feldStil(f);
        return f;
    }

    /** Macht aus jedem Textfeld eines im Holzstil. */
    public static void feldStil(javax.swing.text.JTextComponent f) {
        f.setFont(NORMAL);
        f.setBackground(HELL);
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setSelectionColor(mischen(FELD, KOPF, 0.15));
        f.setSelectedTextColor(TEXT);
        f.setOpaque(true);
        f.setBorder(feldrahmen());
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { f.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { f.repaint(); }
        });
    }

    /** Ein Kaestchen zum Ankreuzen, selbst gemalt (rundes Kaestchen, Haken in Nussbaum). */
    public static JCheckBox kaestchen(String text, boolean an, String hinweis) {
        JCheckBox k = new JCheckBox(text, an);
        k.setFont(NORMAL);
        k.setForeground(TEXT);
        k.setOpaque(false);
        k.setFocusPainted(false);
        k.setIcon(new Kaestchenbild());
        k.setIconTextGap(8);
        k.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (hinweis != null && !hinweis.isBlank()) k.setToolTipText(hinweis);
        return k;
    }

    static final class Kaestchenbild implements Icon {
        private static final int G = 16;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = weich(g);
            boolean an = c instanceof AbstractButton b && b.isSelected();
            boolean aktiv = c.isEnabled();
            Color fuellung = an ? (dunkel ? LEISE : KOPF) : HELL;
            if (!aktiv) fuellung = mischen(fuellung, GRUND, 0.5);
            g2.setColor(fuellung);
            g2.fillRoundRect(x, y, G - 1, G - 1, 5, 5);
            g2.setColor(an ? fuellung : LINIE);
            g2.drawRoundRect(x, y, G - 1, G - 1, 5, 5);
            if (an) {
                g2.setColor(dunkel ? KOPF : HELL);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 4, y + 8, x + 7, y + 11);
                g2.drawLine(x + 7, y + 11, x + 12, y + 5);
            }
            g2.dispose();
        }

        @Override public int getIconWidth() { return G; }
        @Override public int getIconHeight() { return G; }
    }

    /** Eine Auswahlliste im Holzstil (heller Kasten, kleiner Pfeil, Liste in denselben Farben). */
    public static <T> JComboBox<T> auswahl(T[] werte) {
        JComboBox<T> a = new JComboBox<>(werte);
        a.setFont(NORMAL);
        a.setBackground(HELL);
        a.setForeground(TEXT);
        a.setOpaque(true);
        a.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton b = new JButton(new Symbol(Symbol.Art.PFEIL_UNTEN, 10));
                b.setContentAreaFilled(false);
                b.setBorderPainted(false);
                b.setFocusPainted(false);
                b.setForeground(LEISE);
                b.setOpaque(false);
                b.setName("ComboBox.arrowButton");
                return b;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean focus) {
                // Die Vorgabe nimmt die Farbe des Windows-Aussehens -- hier die eigene (dunkel waere sonst ein heller Kasten).
                g.setColor(comboBox.isEnabled() ? comboBox.getBackground() : mischen(comboBox.getBackground(), GRUND, 0.5));
                g.fillRect(r.x, r.y, r.width, r.height);
            }

            @Override
            protected javax.swing.plaf.basic.ComboPopup createPopup() {
                javax.swing.plaf.basic.BasicComboPopup p = (javax.swing.plaf.basic.BasicComboPopup) super.createPopup();
                p.setBorder(BorderFactory.createLineBorder(LINIE, 1));
                p.getList().setBackground(HELL);
                p.getList().setForeground(TEXT);
                p.getList().setSelectionBackground(FELD);
                p.getList().setSelectionForeground(TEXT);
                p.getList().setFont(NORMAL);
                return p;
            }
        });
        a.setBorder(new Rundrahmen(LINIE));
        a.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean gew, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, gew, f);
                c.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                c.setBackground(gew ? FELD : HELL);
                c.setForeground(TEXT);
                c.setOpaque(true);
                return c;
            }
        });
        a.setPreferredSize(new Dimension(a.getPreferredSize().width + 8, HOEHE_KLEIN));
        return a;
    }

    /** Ein Zahlenfeld mit Pfeilen (Spinner) im Holzstil. */
    public static JSpinner zahlenfeld(SpinnerNumberModel modell, int breite) {
        JSpinner s = new JSpinner(modell);
        s.setFont(NORMAL);
        s.setOpaque(true);
        s.setBorder(new Rundrahmen(LINIE));
        s.setBackground(HELL);
        if (s.getEditor() instanceof JSpinner.DefaultEditor e) {
            e.getTextField().setFont(NORMAL);
            e.getTextField().setBackground(HELL);
            e.getTextField().setForeground(TEXT);
            e.getTextField().setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            e.setOpaque(true);
            e.setBackground(HELL);
        }
        s.setPreferredSize(new Dimension(breite, HOEHE_KLEIN));
        return s;
    }

    /** Eine Liste (etwa die Profile) im Holzstil. */
    public static <T> JList<T> liste(ListModel<T> modell) {
        JList<T> l = new JList<>(modell);
        l.setFont(NORMAL);
        l.setBackground(HELL);
        l.setForeground(TEXT);
        l.setSelectionBackground(FELD);
        l.setSelectionForeground(TEXT);
        l.setFixedCellHeight(HOEHE_KLEIN);
        l.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> li, Object v, int i, boolean gew, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(li, v, i, gew, f);
                c.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return c;
            }
        });
        return l;
    }

    /** Ein mehrzeiliger Textbereich zum Lesen (Meldungen, Protokoll). */
    public static JTextArea lesebereich() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setFont(NORMAL);
        t.setBackground(HELL);
        t.setForeground(TEXT);
        t.setCaretColor(TEXT);
        t.setSelectionColor(mischen(FELD, KOPF, 0.15));
        t.setSelectedTextColor(TEXT);
        t.setMargin(new Insets(10, 12, 10, 12));
        return t;
    }

    // ------------------------------------------------------------------
    //  Rollen, Reiter, Tabellen
    // ------------------------------------------------------------------

    /** Ein Rollbalken-Rahmen mit feiner Linie und schmalen Holz-Rollbalken. */
    public static JScrollPane rollen(Component inhalt) {
        JScrollPane s = new JScrollPane(inhalt);
        s.setBorder(new Rundrahmen(LINIE));
        s.setOpaque(true);
        s.setBackground(HELL);
        s.getViewport().setBackground(HELL);
        s.getViewport().setOpaque(true);
        s.getVerticalScrollBar().setUnitIncrement(16);
        rollbalken(s.getVerticalScrollBar());
        rollbalken(s.getHorizontalScrollBar());
        return s;
    }

    /** Ein Rollbalken-Rahmen ohne Linie, mit dem Grund als Hintergrund (Reiter-Inhalte). */
    public static JScrollPane rollenOhneRahmen(Component inhalt) {
        JScrollPane s = rollen(inhalt);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getViewport().setBackground(GRUND);
        s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return s;
    }

    /** Macht aus einem Rollbalken einen schmalen, runden in Holz-Toenen. */
    public static void rollbalken(JScrollBar b) {
        b.setOpaque(false);
        b.setPreferredSize(b.getOrientation() == Adjustable.VERTICAL ? new Dimension(10, 0) : new Dimension(0, 10));
        b.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = LINIE;
                trackColor = GRUND;
            }

            @Override
            protected JButton createDecreaseButton(int o) { return leer(); }

            @Override
            protected JButton createIncreaseButton(int o) { return leer(); }

            private JButton leer() {
                JButton b2 = new JButton();
                b2.setPreferredSize(new Dimension(0, 0));
                b2.setMinimumSize(new Dimension(0, 0));
                b2.setMaximumSize(new Dimension(0, 0));
                return b2;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                g.setColor(scrollbar.getParent() instanceof JScrollPane sp && sp.getViewport().getBackground() != null
                        ? sp.getViewport().getBackground() : HELL);
                g.fillRect(r.x, r.y, r.width, r.height);
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = weich(g);
                g2.setColor(isThumbRollover() ? mischen(LINIE, LEISE, 0.5) : LINIE);
                g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
                g2.dispose();
            }
        });
    }

    /**
     * Eine Flaeche fuer einen Rollbalken, die sich in der Breite nach dem Fenster richtet:
     * nur senkrecht rollen, Zeilen brechen um statt nach rechts hinauszulaufen.
     */
    public static final class Rollflaeche extends JPanel implements Scrollable {
        public Rollflaeche(LayoutManager layout) {
            super(layout);
            setBackground(GRUND);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Ein unsichtbarer Fueller, der in einem GridBagLayout den Rest der Breite nimmt (sonst steht alles mittig). */
    public static void fueller(JPanel p, GridBagConstraints c, int gridx, int gridy) {
        GridBagConstraints f = (GridBagConstraints) c.clone();
        f.gridx = gridx;
        f.gridy = gridy;
        f.gridwidth = 1;
        f.weightx = 1.0;
        f.fill = GridBagConstraints.HORIZONTAL;
        JLabel leer = new JLabel(" ");
        p.add(leer, f);
    }

    /**
     * Reiter: flach, der gewaehlte hell mit einem Nussbaum-Strich darunter, die anderen leise auf dem Grund.
     * Selbst gemalt (BasicTabbedPaneUI) -- das Windows-Aussehen haette die Farben uebergangen.
     */
    public static JTabbedPane reiter() {
        JTabbedPane r = new JTabbedPane();
        r.setFont(KNOPF);
        r.setBackground(GRUND);
        r.setForeground(TEXT);
        r.setOpaque(true);
        r.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                tabInsets = new Insets(8, 10, 8, 10);   // 11.10.: alle acht Reiter in einer Zeile bei 765 breit
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                tabAreaInsets = new Insets(8, LUFT, 0, LUFT);
                contentBorderInsets = new Insets(1, 0, 0, 0);
                lightHighlight = LINIE;
                shadow = LINIE;
                darkShadow = LINIE;
                focus = GRUND;
            }

            @Override
            protected void paintTabBackground(Graphics g, int lage, int i, int x, int y, int w, int h, boolean gewaehlt) {
                Graphics2D g2 = weich(g);
                g2.setColor(gewaehlt ? HELL : (getRolloverTab() == i ? mischen(GRUND, HELL, 0.5) : GRUND));
                g2.fillRoundRect(x, y, w, h + 8, 10, 10);
                g2.dispose();
            }

            @Override
            protected void paintTabBorder(Graphics g, int lage, int i, int x, int y, int w, int h, boolean gewaehlt) {
                if (gewaehlt) {
                    Graphics2D g2 = weich(g);
                    g2.setColor(dunkel ? LEISE : KOPF);
                    g2.fillRoundRect(x + 10, y + h - 3, w - 20, 3, 3, 3);
                    g2.dispose();
                }
            }

            @Override
            protected void paintText(Graphics g, int lage, Font schrift, FontMetrics fm, int i, String titel,
                                     Rectangle r, boolean gewaehlt) {
                Graphics2D g2 = weich(g);
                g2.setFont(schrift);
                g2.setColor(gewaehlt ? TEXT : LEISE);
                g2.drawString(titel, r.x, r.y + fm.getAscent());
                g2.dispose();
            }

            @Override
            protected void paintFocusIndicator(Graphics g, int lage, Rectangle[] r2, int i, Rectangle icon,
                                               Rectangle text, boolean gewaehlt) {
                // kein gestrichelter Rahmen
            }

            @Override
            protected void paintContentBorder(Graphics g, int lage, int gewaehlt) {
                int y = calculateTabAreaHeight(lage, runCount, maxTabHeight);
                g.setColor(LINIE);
                g.fillRect(0, y - 1, tabPane.getWidth(), 1);
            }
        });
        return r;
    }

    /**
     * Eine Tabelle im Holzstil: Kopfzeile in hellem Holz, jede zweite Zeile leicht getoent, keine
     * Gitterlinien, Zahlen rechtsbuendig, sortierbar durch Klick auf die Kopfzeile.
     */
    public static JTable tabelle(javax.swing.table.TableModel modell) {
        JTable t = new JTable(modell);
        t.setFont(NORMAL);
        t.setForeground(TEXT);
        t.setBackground(HELL);
        t.setRowHeight(HOEHE_KLEIN - 2);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setSelectionBackground(FELD);
        t.setSelectionForeground(TEXT);
        t.setAutoCreateRowSorter(true);
        t.setFocusable(false);
        t.setDefaultRenderer(Object.class, new Zebra(SwingConstants.LEFT));
        t.setDefaultRenderer(Number.class, new Zebra(SwingConstants.RIGHT));
        t.setDefaultRenderer(Integer.class, new Zebra(SwingConstants.RIGHT));
        t.setDefaultRenderer(Long.class, new Zebra(SwingConstants.RIGHT));
        t.setDefaultRenderer(Double.class, new Zebra(SwingConstants.RIGHT));
        JTableHeader kopf = t.getTableHeader();
        kopf.setFont(KNOPF);
        kopf.setForeground(LEISE);
        kopf.setBackground(FELD);
        kopf.setReorderingAllowed(false);
        kopf.setPreferredSize(new Dimension(10, HOEHE_KLEIN));
        kopf.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setFont(KNOPF);
                l.setForeground(LEISE);
                l.setBackground(FELD);
                l.setOpaque(true);
                l.setHorizontalAlignment(SwingConstants.LEFT);
                l.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, LINIE),
                        BorderFactory.createEmptyBorder(0, 10, 0, 10)));
                return l;
            }
        });
        return t;
    }

    /** Zeilen abwechselnd getoent, ohne Fokusrahmen. */
    private static final class Zebra extends DefaultTableCellRenderer {
        private final int ausrichtung;

        Zebra(int ausrichtung) {
            this.ausrichtung = ausrichtung;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean gew, boolean f, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, gew, false, r, c);
            l.setHorizontalAlignment(ausrichtung);
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            l.setBackground(gew ? FELD : (r % 2 == 1 ? ZEBRA : HELL));
            l.setForeground(TEXT);
            if (v instanceof Number n && !(v instanceof Double)) l.setText(String.format("%,d", n.longValue()).replace(',', '.'));
            return l;
        }
    }

    // ------------------------------------------------------------------
    //  Zustand
    // ------------------------------------------------------------------

    /** Die Farbe eines Bot-Zustands: gruen spielt, bernstein pausiert, rot abgebrochen, leise sonst. */
    public static Color zustandsfarbe(de.hustlebot.bot.BotEngine.State z) {
        if (z == null) return LEISE;
        return switch (z) {
            case LAEUFT -> GUT;
            case PAUSIERT -> WARNUNG;
            case FEHLER -> FEHLER;
            case FERTIG -> GUT;
            case BEREIT, GESTOPPT -> LEISE;
        };
    }

    /** Kurzes Wort zu einem Zustand ("Spielt", "Pausiert" ...). */
    public static String zustandswort(de.hustlebot.bot.BotEngine.State z) {
        if (z == null) return Sprache.t("Ready", "Bereit");
        return switch (z) {
            case LAEUFT -> Sprache.t("Playing", "Spielt");
            case PAUSIERT -> Sprache.t("Paused", "Pausiert");
            case BEREIT -> Sprache.t("Ready", "Bereit");
            case FERTIG -> Sprache.t("Done", "Fertig");
            case GESTOPPT -> Sprache.t("Stopped", "Gestoppt");
            case FEHLER -> Sprache.t("Aborted", "Abgebrochen");
        };
    }

    /**
     * Die Zustands-Pille: ein farbiger Punkt und ein Wort in einer runden Kapsel. Sitzt in der Kopfleiste
     * (dunkel) oder auf einer Karte (hell) -- aufDunkel sagt, welche Schriftfarbe passt.
     */
    public static final class Pille extends JComponent {
        private final boolean aufDunkel;
        private Color punkt = LEISE;
        private String text = Sprache.t("Ready", "Bereit");

        public Pille(boolean aufDunkel) {
            this.aufDunkel = aufDunkel;
            setFont(KNOPF);
        }

        public void setzen(Color punktfarbe, String wort) {
            if (punktfarbe != null) punkt = punktfarbe;
            text = wort == null ? "" : wort;
            revalidate();
            repaint();
        }

        public void setzen(de.hustlebot.bot.BotEngine.State z) {
            setzen(zustandsfarbe(z), zustandswort(z));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 34, 26);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = weich(g);
            int w = getWidth(), h = getHeight();
            Color kapsel = aufDunkel ? durchsichtig(aufKopf(), 28) : FELD;
            g2.setColor(kapsel);
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            int d = 10, py = (h - d) / 2;
            g2.setColor(punkt);
            g2.fillOval(10, py, d, d);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(aufDunkel ? aufKopf() : TEXT);
            g2.drawString(text, 26, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    //  Fenster stellen
    // ------------------------------------------------------------------

    /**
     * Stellt ein Fenster unten rechts ueber die Taskleiste, wie das Bot-Fenster.
     *
     * @param hoeheVonUnten wie viele Bildpunkte unterhalb schon belegt sind (etwa vom Bot-Fenster)
     */
    public static void untenRechts(Window w, int hoeheVonUnten) {
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = schirm.x + schirm.width - w.getWidth() - RAND;
        int y = schirm.y + schirm.height - w.getHeight() - RAND - hoeheVonUnten;
        w.setLocation(Math.max(schirm.x, x), Math.max(schirm.y, y));
    }

    /** Stellt ein Fenster oben rechts. */
    public static void obenRechts(Window w, int abstandRechts) {
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = schirm.x + schirm.width - w.getWidth() - RAND - abstandRechts;
        w.setLocation(Math.max(schirm.x, x), schirm.y + RAND);
    }

    /**
     * Lage und Groesse eines Fensters aus den Einstellungen (sonst die Vorgabe), auf den Bildschirm geklemmt; verschiebt
     * oder zieht Autor das Fenster, wird es gemerkt (Schluessel name.x, .y, .breite, .hoehe). Autor 11.10.: Fenster
     * duerfen das Spiel nicht ueberdecken -- der Bot klickt mit der echten Maus, ein Fenster darueber faengt die Klicks ab.
     */
    /** Wo das Spiel steht (Autors Bildschirm 1920 x 1080: rechts oben, 755-1930 x 0-809). */
    static final Rectangle SPIEL = new Rectangle(758, 0, 1200, 810);

    public static void lageMerken(Window w, Steuereinstellungen e, String name, Rectangle vorgabe) {
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension min = w.getMinimumSize();
        int b = Math.max(min.width, Math.min(schirm.width, e.zahl(name + ".breite", vorgabe.width)));
        int h = Math.max(min.height, Math.min(schirm.height, e.zahl(name + ".hoehe", vorgabe.height)));
        int x = Math.max(schirm.x, Math.min(e.zahl(name + ".x", vorgabe.x), schirm.x + schirm.width - b));
        int y = Math.max(schirm.y, Math.min(e.zahl(name + ".y", vorgabe.y), schirm.y + schirm.height - h));
        // Autor, 11.10.: "Diese Extras sollen nicht ueber meinem Hustle Castle starten." Liegt die gemerkte Stelle
        // ueber dem Spiel (rechts oben), gilt wieder die Vorgabe.
        if (new Rectangle(x, y, b, h).intersects(SPIEL)) {
            x = vorgabe.x;
            y = vorgabe.y;
            b = Math.min(b, vorgabe.width);
            h = Math.min(h, vorgabe.height);
        }
        w.setBounds(x, y, b, h);
        Timer merken = new Timer(600, ev -> {
            if (!w.isShowing() || (w instanceof Frame f && f.getExtendedState() != Frame.NORMAL)) return;
            Rectangle r = w.getBounds();
            if (r.x == e.zahl(name + ".x", Integer.MIN_VALUE) && r.y == e.zahl(name + ".y", Integer.MIN_VALUE)
                    && r.width == e.zahl(name + ".breite", -1) && r.height == e.zahl(name + ".hoehe", -1)) return;
            e.setzen(name + ".x", r.x);
            e.setzen(name + ".y", r.y);
            e.setzen(name + ".breite", r.width);
            e.setzen(name + ".hoehe", r.height);
        });
        merken.setRepeats(false);
        w.addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent ev) { merken.restart(); }
            @Override public void componentResized(ComponentEvent ev) { merken.restart(); }
        });
    }

    /** Stellt ein Fenster oben in die Mitte (Mini-Leiste). */
    public static void obenMitte(Window w) {
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setLocation(schirm.x + (schirm.width - w.getWidth()) / 2, schirm.y);
    }

    // ------------------------------------------------------------------
    //  Discord-Text
    // ------------------------------------------------------------------

    /** Macht aus Discord-Markdown lesbaren Fenstertext: **fett** und `Code` ohne Zeichen. */
    public static String ohneMarkdown(String text) {
        if (text == null) return "";
        return text.replace("**", "").replace("`", "").replace("__", "");
    }

    /** Farbe fuer eine Discord-Kartenfarbe (Randleiste): Warnung bernstein, Fehler rot, Erfolg gruen. */
    public static Color kartenfarbe(int discordFarbe) {
        return switch (discordFarbe) {
            case de.hustlebot.discord.DiscordClient.FARBE_WARNUNG -> WARNUNG;
            case de.hustlebot.discord.DiscordClient.FARBE_FEHLER -> FEHLER;
            case de.hustlebot.discord.DiscordClient.FARBE_ERFOLG -> GUT;
            case de.hustlebot.discord.DiscordClient.FARBE_START -> KLICK;
            default -> LEISE;
        };
    }

    /** Tausender mit Punkt: 1234567 -> "1.234.567". */
    public static String zahl(long wert) {
        return String.format("%,d", wert).replace(',', '.');
    }
}
