package de.hustlebot.schicht;

import de.hustlebot.core.Sprache;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Das Seitenfenster neben dem Spiel (Arena-Schicht, Alpha vom 28.10.): die Gegner der Pyramide mit ihrer
 * Einstufung und Siegchance, das Ziel des Bots und darunter der "Rechenzettel" -- woher die Siegchance kommt.
 *
 * Stufe 2 (Autor 28.10.): bedient wird es per Tasten (SchichtSteuerung) -- die Auswahl ist hervorgehoben, darunter
 * stehen Vorgemerktes, die Kommentar-Eingabe, die Grund-Frage nach dem Ablehnen, die Gegnerliste und eine kleine
 * Tasten-Hilfe. Es nimmt nie die Tastatur (nicht fokussierbar), liegt NEBEN dem Spiel und nie darueber. Ein Klick auf
 * eine Zeile waehlt sie und zeigt ihren Rechenzettel; ein Klick auf den Zettel springt zurueck zum Ziel (Achtung:
 * Mausbewegung haelt den laufenden Bot an).
 */
final class SeitenFenster {

    static final int BREITE = 340;
    private static final int ABSTAND = 10;
    private static final int RAND = 12;
    private static final int ZEILE = 24;

    private static final Color GRUND = new Color(30, 32, 38);
    private static final Color ZIEL_GRUND = new Color(52, 58, 72);
    private static final Color AUSWAHL_GRUND = new Color(40, 52, 78);
    private static final Color AUSWAHL_RAND = new Color(120, 170, 255);
    private static final Color KASTEN_GRUND = new Color(42, 46, 58);
    private static final Color SCHRIFT = new Color(236, 236, 240);
    private static final Color LEISE = new Color(150, 154, 166);
    private static final Color GRAU = new Color(110, 114, 124);
    private static final Color SCHLECHT = new Color(255, 170, 90);

    // Review 28.10.: Schriften und Linien einmal anlegen statt bei jedem Zeichnen.
    private static final Font FETT = SchichtFenster.schrift(Font.BOLD, 14);
    private static final Font NORMAL = SchichtFenster.schrift(Font.PLAIN, 13);
    private static final Font KLEIN = SchichtFenster.schrift(Font.PLAIN, 11);
    private static final Font KLEIN_FETT = SchichtFenster.schrift(Font.BOLD, 11);
    private static final Font GROSS = SchichtFenster.schrift(Font.BOLD, 22);
    // 29.10. nachts (Autor: "Seitenfenster ueberarbeiten"): Kopfleiste, Ziel-Karte, Balken, Tasten als Kaestchen.
    private static final int KOPF = 34;
    private static final int MINI_BALKEN = 34;
    private static final Color KOPF_GRUND = new Color(22, 24, 29);
    private static final Color BALKEN_GRUND = new Color(62, 66, 78);
    private static final Color TASTE_GRUND = new Color(58, 62, 74);
    private static final Stroke AUSWAHL_LINIE = new BasicStroke(2f);

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Klick auf eine Zeile: deren Platz; auf den Zettel: 0 (zurueck zum Ziel). */
    private final IntConsumer klick;

    private JWindow fenster;
    private Inhalt inhalt;
    private volatile ArenaStand.Stand stand;
    private volatile SchichtSteuerung.Anzeige anzeige = SchichtSteuerung.Anzeige.LEER;

    /** Fehler hoechstens alle 30 s ins Protokoll. */
    private long fehlerGemeldetUm;

    SeitenFenster(IntConsumer klick) {
        this.klick = klick;
    }

    /** Swing-Arbeit sicher auf dem EDT: ein Fehler versteckt das Fenster nur, statt bei jedem Takt zu werfen. */
    private void aufEdt(Runnable arbeit) {
        SwingUtilities.invokeLater(() -> {
            try {
                arbeit.run();
            } catch (Throwable t) {
                if (System.currentTimeMillis() - fehlerGemeldetUm > 30_000) {
                    fehlerGemeldetUm = System.currentTimeMillis();
                    de.hustlebot.core.Log.warn("Schicht", Sprache.t("Side window error: " + t,
                            "Fehler im Seitenfenster: " + t));
                }
                try {
                    if (fenster != null) fenster.dispose();
                } catch (Throwable ignoriert) {
                    // nichts mehr zu retten
                }
                fenster = null;
                inhalt = null;
            }
        });
    }

    /**
     * Zeigt das Fenster neben dem Spiel (links, sonst rechts) mit diesem Stand. Ist neben dem Spiel kein Platz, bleibt
     * es weg -- es darf nie ueber dem Spiel liegen (die Klicks des Bots landeten sonst darauf). Jeder Faden darf rufen;
     * neu gezeichnet wird nur, wenn die Schicht etwas Neues hat (ArenaSchicht vergleicht vorher).
     */
    void zeigen(Rectangle spiel, ArenaStand.Stand neu, SchichtSteuerung.Anzeige a) {
        stand = neu;
        anzeige = a == null ? SchichtSteuerung.Anzeige.LEER : a;
        aufEdt(() -> {
            Rectangle soll = lage(spiel, monitorBei(spiel));
            if (soll == null) {
                if (fenster != null && fenster.isVisible()) fenster.setVisible(false);
                return;
            }
            if (fenster == null) bauen();
            if (!fenster.getBounds().equals(soll)) fenster.setBounds(soll);
            if (!fenster.isVisible()) {
                if (!fenster.isDisplayable()) {
                    fenster.pack();
                    fenster.setBounds(soll);
                }
                SchichtFenster.vorAufnahmeVerstecken(fenster);   // zusaetzlich -- es liegt ohnehin neben dem Spiel
                fenster.setVisible(true);
            }
            inhalt.repaint();
        });
    }

    void verstecken() {
        aufEdt(() -> {
            if (fenster != null && fenster.isVisible()) fenster.setVisible(false);
        });
    }

    void schliessen() {
        aufEdt(() -> {
            if (fenster != null) fenster.dispose();
            fenster = null;
            inhalt = null;
        });
    }

    /** Der Arbeitsbereich des Bildschirms, auf dem die Mitte des Spiels liegt (auch bei zwei Bildschirmen). */
    private static Rectangle monitorBei(Rectangle spiel) {
        java.awt.Point mitte = new java.awt.Point(spiel.x + spiel.width / 2, spiel.y + spiel.height / 2);
        for (java.awt.GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            java.awt.GraphicsConfiguration k = d.getDefaultConfiguration();
            Rectangle b = k.getBounds();
            if (b.contains(mitte)) {
                java.awt.Insets r = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(k);
                return new Rectangle(b.x + r.left, b.y + r.top, b.width - r.left - r.right, b.height - r.top - r.bottom);
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    /**
     * Wo das Fenster hinkommt: links neben das Spiel (der Bot dockt es rechts an), passt das nicht, rechts daneben.
     * Passt es auf keiner Seite, null -- dann wird es nicht gezeigt (nie ueber dem Spiel).
     */
    static Rectangle lage(Rectangle spiel, Rectangle schirm) {
        int hoehe = Math.max(300, Math.min(spiel.height, schirm.height));
        int y = Math.max(schirm.y, Math.min(spiel.y, schirm.y + schirm.height - hoehe));
        int links = spiel.x - BREITE - ABSTAND;
        if (links >= schirm.x) return new Rectangle(links, y, BREITE, hoehe);
        int rechts = spiel.x + spiel.width + ABSTAND;
        if (rechts + BREITE <= schirm.x + schirm.width) return new Rectangle(rechts, y, BREITE, hoehe);
        return null;
    }

    private void bauen() {
        fenster = new JWindow();
        fenster.setAlwaysOnTop(true);
        fenster.setFocusableWindowState(false);   // nie die Tastatur nehmen -- das Spiel bleibt vorn
        fenster.setAutoRequestFocus(false);
        fenster.setType(java.awt.Window.Type.UTILITY);
        inhalt = new Inhalt();
        inhalt.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (klick == null) return;
                int knopf = inhalt.knopfBei(e.getX(), e.getY());
                klick.accept(knopf != 0 ? knopf : inhalt.platzBei(e.getY()));
            }
        });
        fenster.setContentPane(inhalt);
    }

    /** Gegner nach Platz, dann von links nach rechts. */
    static List<ArenaStand.Gegner> sortiert(ArenaStand.Stand s) {
        List<ArenaStand.Gegner> l = new ArrayList<>(s.gegner());
        l.sort(Comparator.comparingInt(ArenaStand.Gegner::platz).thenComparingInt(ArenaStand.Gegner::x));
        return l;
    }

    /**
     * Eine Zeile der Liste.
     *
     * @param gegner die Rechnung des Bots dazu, null = keine (besiegt, oder Autor spielt selbst)
     */
    record Zeile(int platz, String name, String clan, String stufe, int siegchance, boolean ziel,
                 ArenaStand.Gegner gegner, boolean besiegt) { }

    /**
     * Die Zeilen: mit Rechnung die angreifbaren Gegner; fuer die Gegnerliste (alle = true) oder ohne Rechnung alle
     * Plaetze der Pyramide (auch die besiegten), mit Namen, soweit der Bot sie kennt.
     */
    static List<Zeile> zeilen(ArenaStand.Stand s, boolean alle) {
        List<Zeile> l = new ArrayList<>();
        if ((!alle && !s.gegner().isEmpty()) || s.plaetze().isEmpty()) {
            for (ArenaStand.Gegner g : sortiert(s)) {
                l.add(new Zeile(g.platz(), g.anzeige(), g.clan(), g.stufe(), g.siegchance(), g.ziel(), g, false));
            }
            return l;
        }
        List<ArenaStand.Platz> p = new ArrayList<>(s.plaetze());
        p.sort(Comparator.comparingInt(ArenaStand.Platz::platz).thenComparingInt(ArenaStand.Platz::x));
        for (ArenaStand.Platz x : p) {
            ArenaStand.Gegner g = SchichtSteuerung.gegnerAuf(s, x.platz());
            l.add(new Zeile(x.platz(), g == null ? "" : g.anzeige(), g == null ? "" : g.clan(), x.stufe(),
                    g == null ? -1 : g.siegchance(), g != null && g.ziel(), g, x.besiegt()));
        }
        return l;
    }

    /** Der Beste nach dem Ziel (hoechste Siegchance unter den anderen), oder null. */
    static ArenaStand.Gegner zweitbester(ArenaStand.Stand s) {
        ArenaStand.Gegner best = null;
        for (ArenaStand.Gegner g : s.gegner()) {
            if (g.ziel() || g.siegchance() < 0 || "niemals".equals(g.stufe())) continue;
            if (best == null || g.siegchance() > best.siegchance()) best = g;
        }
        return best;
    }

    /** Die Posten des Rechenzettels: Listen, Herkunft der Siegchance (an ", " getrennt), Besonderheiten. */
    /** Punkte ohne jedes Wissen ueber den Spieler: "-40 points (team unknown, power unknown), 113 below your team (73)". */
    private static final java.util.regex.Pattern GANZ_UNBEKANNT = java.util.regex.Pattern.compile(
            "(?:\\?|-?\\d+) (?:points|Punkte) \\((?:team unknown|Team unbekannt), (?:power unknown|Kraft unbekannt)\\)"
                    + "(?:, \\d+ (?:ueber|unter|above|below)(?: deinem Team \\(\\d+\\)| dem Besten, den du je geschlagen hast \\(\\d+\\)"
                    + "| your team \\(\\d+\\)| the best you ever beat \\(\\d+\\)))?");

    /** Was der Steckbrief schon sagt und im Rechenzettel nicht noch einmal stehen muss: die Bilanz und die nackte Kraft. */
    private static final java.util.regex.Pattern IM_STECKBRIEF = java.util.regex.Pattern.compile(
            "(?:Notebook|Notizblock)(?: \\(.*\\))? \\d+/\\d+|(?:own fights|eigene Kaempfe) \\d+/\\d+|[\\d.,]+");

    /** An ", " trennen, aber nicht in Klammern ("55 points (team 40, power difference +1.2 M = +3)" bleibt ganz). */
    static List<String> teile(String t) {
        List<String> l = new ArrayList<>();
        int tiefe = 0, von = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') tiefe++;
            else if (c == ')') tiefe = Math.max(0, tiefe - 1);
            else if (tiefe == 0 && c == ',' && i + 1 < t.length() && t.charAt(i + 1) == ' ') {
                l.add(t.substring(von, i));
                von = i + 2;
            }
        }
        l.add(t.substring(von));
        return l;
    }

    /**
     * Autor 30.10.: "Bei jedem Spieler, den der Bot schon kennt, soll nicht dabeistehen, dass er das Team und die Power nicht kennt. Und wenn der
     * Bot einen Spieler wirklich gar nicht kennt, sollen die Punkte auch nicht dastehen." Nur die Anzeige, das Protokoll bleibt ausfuehrlich.
     */
    static String erklaerungKurz(String e) {
        if (e == null) return "";
        String t = GANZ_UNBEKANNT.matcher(e).replaceAll(Sprache.t("Never looked at: the chance is an estimate", "Noch nie angesehen: die Chance ist eine Schaetzung"));
        return t.replaceAll(", (?:power unknown|Kraft unbekannt)\\)", ")").replaceAll("\\((?:team unknown|Team unbekannt), ", "(");
    }

    static List<String> posten(ArenaStand.Gegner g) {
        List<String> p = new ArrayList<>();
        boolean unbekannt = g.erklaerung() != null && GANZ_UNBEKANNT.matcher(g.erklaerung()).find();
        boolean steckbrief = g.steckbrief() != null && !g.steckbrief().isBlank();
        if (g.listen() != null && !g.listen().isBlank()) {
            for (String t : g.listen().split("\\s{2,}")) {
                // Kennt er ihn gar nicht, stehen auch bei der Liste keine Punkte ("SAFE WIN (-40)").
                String z = unbekannt ? t.replaceAll("\\s*\\([+-]\\d+\\)", "") : t;
                if (!z.isBlank()) p.add(z.strip());
            }
        }
        if (g.autoListe()) p.add(Sprache.t("list set by the bot itself (notebook)", "Liste vom Bot selbst (Notizblock)"));
        if (g.ohneClan()) p.add("Not in clan");   // steht sonst noch einmal in der Herkunft der Chance (Autor 01.11.: nicht doppelt)
        // Autor 30.10.: "Wieso steht im Seitenfenster so wenig ueber Flimsy?" Erst, was der Bot ueber ihn weiss, danach die Herkunft der Chance.
        if (steckbrief) for (String t : g.steckbrief().split("\n")) if (!t.isBlank()) p.add(t.strip());
        if (g.erklaerung() != null && !g.erklaerung().isBlank()) {
            for (String t : teile(erklaerungKurz(g.erklaerung()))) {
                if (t.isBlank() || (steckbrief && IM_STECKBRIEF.matcher(t.strip()).matches())) continue;
                if (schonDa(p, t.strip())) continue;
                p.add(t.strip());
            }
        }
        if (p.isEmpty()) p.add(Sprache.t("nothing known yet", "noch nichts bekannt"));
        return p;
    }

    /** Steht dieser Posten (ohne auf Gross-/Kleinschreibung zu achten) schon in der Liste? */
    static boolean schonDa(List<String> posten, String t) {
        for (String x : posten) if (x.equalsIgnoreCase(t)) return true;
        return false;
    }

    static Color farbe(String stufe) {
        return switch (stufe == null ? "" : stufe) {
            case "sicher" -> SchichtFenster.SICHER;
            case "machbar" -> SchichtFenster.MACHBAR;
            case "fastsicher" -> SchichtFenster.FAST_SICHER;
            case "pause" -> SchichtFenster.PAUSE;
            case "meiden" -> SchichtFenster.MEIDEN;
            case "niemals" -> SchichtFenster.NIEMALS;
            default -> null;
        };
    }

    /** Farbe der Siegchance: ab 70 % gruen, ab 50 % gold, darunter grau. */
    static Color chanceFarbe(int siegchance) {
        return siegchance >= 70 ? SchichtFenster.SICHER : siegchance >= 50 ? SchichtFenster.MACHBAR : LEISE;
    }

    /** Vorschaubild (SchichtVorschau, Tests): das Fenster mit diesem Stand, ohne es auf den Schirm zu bringen. */
    static java.awt.image.BufferedImage vorschau(ArenaStand.Stand s, SchichtSteuerung.Anzeige a, int hoehe) {
        SeitenFenster f = new SeitenFenster(null);
        f.stand = s;
        f.anzeige = a == null ? SchichtSteuerung.Anzeige.LEER : a;
        Inhalt i = f.new Inhalt();
        i.setSize(BREITE, hoehe);
        java.awt.image.BufferedImage bild = new java.awt.image.BufferedImage(BREITE, hoehe,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bild.createGraphics();
        try {
            i.paintComponent(g);
        } finally {
            g.dispose();
        }
        return bild;
    }

    /** Wie im Arena-Modus auf 8 Punkte gerundet -- die Erkennung springt um 1-2 Punkte (Review 28.10.). */
    static String schluessel(ArenaStand.Gegner g) {
        return (g.x() / 8) + ":" + (g.y() / 8) + ":" + g.name();
    }

    /** Bricht einen Text auf die Breite um (an Leerzeichen; ein zu langes Wort wird hart geteilt). */
    static List<String> umbrechen(String text, FontMetrics fm, int breite) {
        List<String> zeilen = new ArrayList<>();
        StringBuilder z = new StringBuilder();
        for (String wort : text.split(" ")) {
            String probe = z.length() == 0 ? wort : z + " " + wort;
            if (fm.stringWidth(probe) <= breite) {
                z.setLength(0);
                z.append(probe);
                continue;
            }
            if (z.length() > 0) zeilen.add(z.toString());
            z.setLength(0);
            while (fm.stringWidth(wort) > breite && wort.length() > 1) {
                int n = wort.length();
                while (n > 1 && fm.stringWidth(wort.substring(0, n)) > breite) n--;
                zeilen.add(wort.substring(0, n));
                wort = wort.substring(n);
            }
            z.append(wort);
        }
        if (z.length() > 0) zeilen.add(z.toString());
        return zeilen;
    }

    private final class Inhalt extends JComponent {

        /** Wo jede Zeile der Liste liegt (y-Bereich -> Platz), fuer den Klick. */
        private final List<int[]> zeilenY = new ArrayList<>();
        private int zettelOben = Integer.MAX_VALUE;

        Inhalt() {
            setOpaque(true);
        }

        /** Platz der Zeile unter y; 0 = Klick auf den Zettel (zurueck zum Ziel); -1 = nichts. */
        /** Die Knoepfe im Kommentar-Kasten beim letzten Malen: {x0, y0, x1, y1, KLICK_-Wert}. */
        private final List<int[]> knoepfe = new ArrayList<>();

        /** Der KLICK_-Wert des Knopfs an dieser Stelle, 0 = kein Knopf. */
        int knopfBei(int x, int y) {
            for (int[] k : knoepfe) if (x >= k[0] && x < k[2] && y >= k[1] && y < k[3]) return k[4];
            return 0;
        }

        /** Ein Knopf im Kommentar-Kasten; gibt das x hinter ihm zurueck. */
        private int knopfMalen(Graphics2D g, String text, boolean an, int x, int y, int code) {
            g.setFont(KLEIN_FETT);
            int w = g.getFontMetrics().stringWidth(text) + 12;
            g.setColor(an ? SchichtFenster.MACHBAR : TASTE_GRUND);
            g.fillRoundRect(x, y, w, 18, 6, 6);
            g.setColor(an ? KASTEN_GRUND : SCHRIFT);
            g.drawString(text, x + 6, y + 13);
            knoepfe.add(new int[]{x, y, x + w, y + 18, code});
            return x + w + 5;
        }

        int platzBei(int y) {
            if (y >= zettelOben) return 0;
            for (int[] z : zeilenY) if (y >= z[0] && y < z[1]) return z[2];
            return -1;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(GRUND);
                g.fillRect(0, 0, getWidth(), getHeight());
                zeilenY.clear();
                zettelOben = Integer.MAX_VALUE;
                ArenaStand.Stand s = stand;
                if (s == null) return;
                malen(g, s, anzeige);
            } finally {
                g.dispose();
            }
        }

        private void malen(Graphics2D g, ArenaStand.Stand s, SchichtSteuerung.Anzeige a) {
            int b = getWidth() - 2 * RAND;
            knoepfe.clear();

            // Kopfleiste (29.10. nachts): "Arena", fuenf Punkte fuer die Stufen, rechts die Uhrzeit des Standes.
            g.setColor(KOPF_GRUND);
            g.fillRect(0, 0, getWidth(), KOPF);
            g.setFont(FETT);
            g.setColor(SCHRIFT);
            g.drawString("Arena", RAND, 23);
            int px = RAND + g.getFontMetrics().stringWidth("Arena") + 12;
            int stufeJetzt = ArenaStand.anzeigeRunde(s.runde());   // 01.11.: die am Bild gelesene Stage, nicht die des (alten) Standes
            for (int i = 1; i <= 5; i++) {
                if (i <= stufeJetzt) {
                    g.setColor(i == stufeJetzt ? SCHRIFT : LEISE);
                    g.fillOval(px, 13, 9, 9);
                } else {
                    g.setColor(GRAU);
                    g.drawOval(px, 13, 8, 8);
                }
                px += 14;
            }
            g.setFont(KLEIN);
            g.setColor(LEISE);
            g.drawString(Sprache.t("stage ", "Stufe ") + stufeJetzt + "/5", px + 4, 22);
            // Autor 30.10.: "Da ist kein Knopf zum Wegdruecken. Dieses Minus, wie bei jedem Spiel, und mit der Taste ^." Mit ^ kommt es wieder.
            g.setFont(KLEIN_FETT);
            int mx = getWidth() - RAND - g.getFontMetrics().stringWidth("–") - 12;
            knopfMalen(g, "–", false, mx, 8, SchichtSteuerung.KLICK_WEG);
            g.setFont(KLEIN);
            g.setColor(LEISE);
            String uhr = UHR.format(Instant.ofEpochMilli(s.um()));
            g.drawString(uhr, mx - 6 - g.getFontMetrics().stringWidth(uhr), 22);
            int y = KOPF;

            // Unten die Tasten-Hilfe; alles andere hoert darueber auf.
            int unten = hilfeMalen(g, b, !s.gegner().isEmpty());

            if (a.modus() != SchichtSteuerung.Modus.NORMAL) y = eingabeMalen(g, a, b, y);
            if (a.meldung() != null) {
                g.setFont(NORMAL);
                g.setColor(a.meldungGut() ? SchichtFenster.SICHER : SCHLECHT);
                for (String z : umbrechen(a.meldung(), g.getFontMetrics(), b)) {
                    y += 17;
                    g.drawString(z, RAND, y);
                }
                y += 4;
            }

            // Autor 29.10. abends: im Seitenfenster stehen ALLE Gegner, dort wird mit ↑/↓ gewaehlt.
            List<Zeile> zeilen = zeilen(s, true);
            if (a.liste()) {
                g.setFont(FETT);
                g.setColor(SCHRIFT);
                y += 20;
                g.drawString(Sprache.t("Opponents", "Gegnerliste"), RAND, y);
                y = zeilenMalen(g, s, a, zeilen, b, y + 2, unten);
                g.setFont(KLEIN);
                g.setColor(LEISE);
                y += 16;
                if (y < unten) {
                    g.drawString(Sprache.t("↑↓ choose · K comment · L / Esc close",
                            "↑↓ waehlen · K kommentieren · L / Esc schliessen"), RAND, y);
                }
                return;
            }
            y = zielMalen(g, s, b, y, unten);
            y = zeilenMalen(g, s, a, zeilen, b, y + 4, unten);
            y = auswahlMalen(g, s, a, b, y, unten);
            zettelMalen(g, s, a, b, y, unten);
        }

        /**
         * Karte "Bot greift an" ganz oben (29.10. nachts): Name, Clan, Siegchance gross mit Balken, darunter der
         * Zweitbeste. Ohne Ziel eine Zeile, warum keins da ist. Gibt das neue y zurueck.
         */
        private int zielMalen(Graphics2D g, ArenaStand.Stand s, int b, int y, int unten) {
            ArenaStand.Gegner z = SchichtSteuerung.ziel(s);
            // Autor 30.10.: "die ganzen 5 Stages ueber": nach seinem Angriff bleibt der letzte Vorschlag stehen, damit J und N weiter etwas meinen.
            boolean nachAngriff = z == null && (z = ArenaStand.letztesZiel(s.runde())) != null;
            y += 10;
            if (z == null) {
                g.setFont(NORMAL);
                g.setColor(LEISE);
                String t = ArenaStand.stageFertig() ? Sprache.t("Already attacked in this stage.", "Diese Stage schon angegriffen.")
                        : s.gegner().isEmpty() ? Sprache.t("Reading the players ...",
                        "Spieler werden gelesen ...") : Sprache.t("No target chosen yet.", "Noch kein Ziel gewaehlt.");
                g.drawString(kuerzen(t, g.getFontMetrics(), b), RAND, y + 14);
                return kommentarKnoepfe(g, y + 22, unten);
            }
            ArenaStand.Gegner zweiter = nachAngriff ? null : zweitbester(s);
            int hoehe = zweiter != null ? 74 : 58;
            if (y + hoehe > unten) return y;
            Color akzent = chanceFarbe(z.siegchance());
            g.setColor(KASTEN_GRUND);
            g.fillRoundRect(RAND - 4, y, b + 8, hoehe, 10, 10);
            g.setColor(akzent);
            g.fillRoundRect(RAND - 4, y, 4, hoehe, 4, 4);
            g.setFont(KLEIN);
            g.setColor(LEISE);
            g.drawString((nachAngriff ? Sprache.t("Bot wanted · place ", "Bot wollte · Platz ") : Sprache.t("Bot attacks · place ", "Bot greift an · Platz ")) + z.platz(), RAND + 6, y + 16);
            String pz = z.siegchance() >= 0 ? z.siegchance() + " %" : "?";
            g.setFont(GROSS);
            int pzB = g.getFontMetrics().stringWidth(pz);
            g.setColor(akzent);
            g.drawString(pz, RAND + b - pzB, y + 30);
            g.setFont(FETT);
            g.setColor(SCHRIFT);
            String name = z.anzeige();
            int nameB = b - pzB - 16;
            g.drawString(kuerzen(name, g.getFontMetrics(), nameB), RAND + 6, y + 36);
            int ny = y + 36;
            int w = Math.min(g.getFontMetrics().stringWidth(name), nameB);
            if (z.clan() != null && !z.clan().isBlank() && nameB - w > 30) {
                g.setFont(NORMAL);
                g.setColor(LEISE);
                g.drawString(kuerzen("  " + z.clan(), g.getFontMetrics(), nameB - w), RAND + 6 + w, ny);
            }
            // Balken der Siegchance
            int by = y + 44;
            g.setColor(BALKEN_GRUND);
            g.fillRoundRect(RAND + 6, by, b - 10, 5, 5, 5);
            if (z.siegchance() > 0) {
                g.setColor(akzent);
                g.fillRoundRect(RAND + 6, by, (b - 10) * Math.min(100, z.siegchance()) / 100, 5, 5, 5);
            }
            if (zweiter != null) {
                g.setFont(KLEIN);
                g.setColor(LEISE);
                g.drawString(kuerzen(Sprache.t("next best: place ", "danach: Platz ") + zweiter.platz() + "  "
                        + zweiter.anzeige() + "  " + zweiter.siegchance() + " %", g.getFontMetrics(), b - 8),RAND + 6, y + 66);
            }
            // Autor 30.10.: "einen Knopf, dass ich dem Bot zustimmen kann: Ja, das passt." Mit der Maus oder J und N.
            int ky = y + hoehe + 3;
            if (ky + 18 > unten) return y + hoehe + 2;
            int kx = knopfMalen(g, Sprache.t("Agree (J)", "Passt (J)"), false, RAND, ky, SchichtSteuerung.KLICK_ZUSTIMMEN);
            knopfMalen(g, Sprache.t("Disagree (N)", "Passt nicht (N)"), false, kx, ky, SchichtSteuerung.KLICK_ABLEHNEN);
            return kommentarKnoepfe(g, ky + 21, unten);
        }

        /** Autor 30.10.: "zu den einzelnen Spielern einen Kommentar schreiben koennen, aber auch zu der Stage." Gibt das neue y zurueck. */
        private int kommentarKnoepfe(Graphics2D g, int y, int unten) {
            if (y + 18 > unten) return y;
            int kx = knopfMalen(g, Sprache.t("Comment player (K)", "Kommentar Spieler (K)"), false, RAND, y, SchichtSteuerung.KLICK_KOMMENTAR);
            knopfMalen(g, Sprache.t("Comment stage (G)", "Kommentar Stage (G)"), false, kx, y, SchichtSteuerung.KLICK_STAGE_KOMMENTAR);
            return y + 20;
        }

        /** Die Zeilen; die Auswahl blau umrandet, das Ziel hinterlegt. Gibt das neue y zurueck. */
        private int zeilenMalen(Graphics2D g, ArenaStand.Stand s, SchichtSteuerung.Anzeige a, List<Zeile> zeilen,
                                int b, int y, int unten) {
            for (Zeile x : zeilen) {
                if (y + ZEILE > unten) break;
                int oben = y;
                y += ZEILE;
                boolean gewaehlt = a.auswahl() == x.platz();
                if (x.ziel() || gewaehlt) {
                    g.setColor(gewaehlt ? AUSWAHL_GRUND : ZIEL_GRUND);
                    g.fillRoundRect(RAND - 4, oben + 2, b + 8, ZEILE, 8, 8);
                }
                if (gewaehlt) {
                    Stroke alt = g.getStroke();
                    g.setStroke(AUSWAHL_LINIE);
                    g.setColor(AUSWAHL_RAND);
                    g.drawRoundRect(RAND - 4, oben + 2, b + 8, ZEILE, 8, 8);
                    g.setStroke(alt);
                }
                String stufe = SchichtSteuerung.stufeVon(s, a.stufen(), x.platz());
                Color f = farbe(stufe);
                if (f != null) {
                    g.setColor(f);
                    g.fillOval(RAND, oben + 9, 10, 10);
                } else {
                    g.setColor(GRAU);
                    g.drawOval(RAND, oben + 9, 9, 9);
                }
                g.setFont(NORMAL);
                g.setColor(LEISE);
                g.drawString(String.valueOf(x.platz()), RAND + 16, oben + 19);
                boolean mitChance = x.siegchance() >= 0;
                String rechts = mitChance ? x.siegchance() + " %"
                        : x.gegner() == null ? SchichtSteuerung.stufeText(stufe) : "?";
                int rB = g.getFontMetrics().stringWidth(rechts);
                // 29.10. nachts: kleiner Balken vor der Zahl -- die Reihe ist auf einen Blick vergleichbar.
                int balkenX = RAND + b - rB - 6 - MINI_BALKEN;
                if (mitChance) {
                    g.setColor(BALKEN_GRUND);
                    g.fillRoundRect(balkenX, oben + 12, MINI_BALKEN, 4, 4, 4);
                    g.setColor(chanceFarbe(x.siegchance()));
                    g.fillRoundRect(balkenX, oben + 12, MINI_BALKEN * Math.min(100, x.siegchance()) / 100, 4, 4, 4);
                }
                int nameEnde = (mitChance ? balkenX : RAND + b - rB) - 8;
                g.setFont(x.ziel() ? FETT : NORMAL);
                g.setColor(SCHRIFT);
                // Autor 30.10.: "bei den anderen steht nur ein Strich": Plaetze ohne "Reward:" sind die, gegen die er schon gekaempft hat.
                // Autor 01.11.: "already fought" nur, wo das Bild wirklich "Victory!" zeigt; sonst wird der Platz noch gelesen.
                String name = x.gegner() == null ? (x.besiegt() ? Sprache.t("already fought", "schon gekaempft") : Sprache.t("being read ...", "wird gelesen ..."))
                        : x.name() == null || x.name().isBlank() ? "—" : x.name();
                name = kuerzen(name, g.getFontMetrics(), nameEnde - RAND - 34);
                g.drawString(name, RAND + 34, oben + 19);
                int frei = nameEnde - RAND - 34 - g.getFontMetrics().stringWidth(name);
                if (x.clan() != null && !x.clan().isBlank() && frei > 30) {
                    int w = g.getFontMetrics().stringWidth(name);
                    g.setFont(KLEIN);
                    g.setColor(LEISE);
                    g.drawString(kuerzen("  " + x.clan(), g.getFontMetrics(), frei), RAND + 34 + w, oben + 19);
                }
                g.setFont(NORMAL);
                g.setColor(mitChance ? chanceFarbe(x.siegchance()) : LEISE);
                g.drawString(rechts, RAND + b - rB, oben + 19);
                zeilenY.add(new int[]{oben + 2, oben + 2 + ZEILE, x.platz()});
            }
            return y;
        }

        /** Der Kasten zur Auswahl: Stufe und Clan, Vorgemerktes mit "Enter speichert". */
        private int auswahlMalen(Graphics2D g, ArenaStand.Stand s, SchichtSteuerung.Anzeige a, int b, int y, int unten) {
            if (a.auswahl() == 0 || y + 60 > unten) return y;
            ArenaStand.Gegner gg = SchichtSteuerung.gegnerAuf(s, a.auswahl());
            String stufe = SchichtSteuerung.stufeVon(s, a.stufen(), a.auswahl());
            String clan = SchichtSteuerung.clanVon(s, a.clans(), a.auswahl());
            List<String> z = new ArrayList<>();
            z.add(Sprache.t("List: ", "Liste: ") + SchichtSteuerung.stufeText(stufe));
            if (!"nichts".equals(clan)) z.add(Sprache.t("Clan: ", "Clan: ") + SchichtSteuerung.clanText(clan));
            z.add(Sprache.t("1 safe · 2 doable · 3 avoid · 4 never · 0 none", "1 sicher · 2 machbar · 3 meiden · 4 niemals · 0 keine"));

            int hoehe = 22 + 17 * z.size() + 6;
            y += 8;
            g.setColor(KASTEN_GRUND);
            g.fillRoundRect(RAND - 4, y, b + 8, hoehe, 10, 10);
            g.setColor(AUSWAHL_RAND);
            g.drawRoundRect(RAND - 4, y, b + 8, hoehe, 10, 10);
            g.setFont(FETT);
            g.setColor(SCHRIFT);
            g.drawString(kuerzen(Sprache.t("Selected: ", "Auswahl: ")
                    + SchichtSteuerung.wer(a.auswahl(), gg == null ? "" : gg.anzeige()), g.getFontMetrics(), b), RAND, y + 18);
            g.setFont(NORMAL);
            int zy = y + 18;
            for (int i = 0; i < z.size(); i++) {
                zy += 17;
                boolean hinweis = i == z.size() - 1;   // die Ziffern-Zeile
                boolean geaendert = i == 0;
                g.setColor(hinweis ? SchichtFenster.MACHBAR : geaendert ? SCHRIFT : LEISE);
                g.drawString(kuerzen(z.get(i), g.getFontMetrics(), b), RAND, zy);
            }
            return y + hoehe + 4;
        }

        /** Rechenzettel: mit Leertaste der der Auswahl, sonst der des Bot-Ziels. */
        private void zettelMalen(Graphics2D g, ArenaStand.Stand s, SchichtSteuerung.Anzeige a, int b, int y, int unten) {
            // Autor 30.10.: Unter dem gewaehlten FLIMSY stand die Begruendung des Bot-Ziels. Wer gewaehlt ist, dessen Begruendung steht hier.
            ArenaStand.Gegner zettel = a.auswahl() != 0 ? SchichtSteuerung.gegnerAuf(s, a.auswahl()) : SchichtSteuerung.zielFuerMeinung(s);
            y += 14;
            if (y + 20 > unten) return;
            zettelOben = y - 10;
            g.setColor(GRAU);
            g.drawLine(RAND, y - 8, RAND + b, y - 8);
            if (zettel == null) {
                if (a.auswahl() == 0) return;   // ohne Ziel sagt es die Karte oben schon
                g.setFont(NORMAL);
                g.setColor(LEISE);
                boolean unsicher = false;
                for (ArenaStand.Platz pl : s.plaetze()) if (pl.platz() == a.auswahl() && !pl.besiegt()) unsicher = true;
                String t = unsicher ? Sprache.t("No calculation yet: this one is still being read.", "Noch keine Rechnung dazu: wird noch gelesen.")
                        : Sprache.t("No calculation for this one: already fought in this arena.",
                        "Keine Rechnung dazu: in dieser Arena schon gekaempft.");
                for (String z : umbrechen(t, g.getFontMetrics(), b)) {
                    y += 17;
                    g.drawString(z, RAND, y);
                }
                return;
            }
            g.setFont(FETT);
            g.setColor(SCHRIFT);
            String kopf = (zettel.ziel() ? Sprache.t("Why the bot picks ", "Warum der Bot nimmt: ") : Sprache.t("About ", "Zu ")) + zettel.anzeige()
                    + (zettel.siegchance() >= 0 ? " — " + zettel.siegchance() + " %" : "");
            for (String z : umbrechen(kopf, g.getFontMetrics(), b)) {
                y += 18;
                g.drawString(z, RAND, y);
            }
            // Autor 30.10.: "so viel wie moeglich, du hast genuegend Platz, wenn du in dieser Kleinschrift weiterschreibst."
            g.setFont(KLEIN);
            FontMetrics fm = g.getFontMetrics();
            y += 4;
            for (String p : posten(zettel)) {
                List<String> teile = umbrechen(p, fm, b - 14);
                for (int i = 0; i < teile.size(); i++) {
                    y += 15;
                    if (y > unten) return;   // kein Platz mehr
                    g.setColor(i == 0 ? SCHRIFT : LEISE);
                    if (i == 0) g.drawString("•", RAND, y);
                    g.drawString(teile.get(i), RAND + 14, y);
                }
            }
        }

        /** Kommentar-Eingabe oder Grund-Frage, oben im Fenster, gut sichtbar. */
        private int eingabeMalen(Graphics2D g, SchichtSteuerung.Anzeige a, int b, int y) {
            List<String> kopf = new ArrayList<>();
            List<String> rumpf = new ArrayList<>();
            String fuss;
            String wer = SchichtSteuerung.wer(a.bezugPlatz(), a.bezugName());
            g.setFont(NORMAL);
            FontMetrics fm = g.getFontMetrics();
            if (a.modus() == SchichtSteuerung.Modus.GRUND) {
                kopf.add(Sprache.t("Rejected: ", "Abgelehnt: ") + wer + Sprache.t(". Why?", ". Warum?"));
                fuss = Sprache.t("Keys 1-4 · K = write a reason · Esc = no reason", "Tasten 1-4 · K = Grund schreiben · Esc = ohne Grund");
            } else {
                kopf.add(a.eingabeGrund() ? Sprache.t("Why reject ", "Warum abgelehnt: ") + wer + "?"
                        : a.bezugPlatz() > 0 || !a.bezugName().isBlank() ? Sprache.t("Comment on ", "Kommentar zu ") + wer + ":"
                        : Sprache.t("Comment:", "Kommentar:"));
                rumpf.addAll(umbrechen(a.eingabe() + "▏", fm, b - 8));
                fuss = Sprache.t("Tab = kind · Enter = send · Esc = cancel", "Tab = Art · Enter = abschicken · Esc = abbrechen");
            }
            // Autor 30.10.: Knoepfe + freier Text -- mit der Maus (er spielt selbst) oder Tab und Enter (der Bot spielt).
            boolean mitKnoepfen = a.modus() == SchichtSteuerung.Modus.KOMMENTAR && !a.eingabeGrund();
            boolean grundKnoepfe = a.modus() == SchichtSteuerung.Modus.GRUND;
            int hoehe = 10 + 18 * kopf.size() + 17 * rumpf.size() + 18 + (mitKnoepfen || grundKnoepfe ? 2 * 23 : 0);
            y += 10;
            g.setColor(KASTEN_GRUND);
            g.fillRoundRect(RAND - 4, y, b + 8, hoehe, 10, 10);
            g.setColor(SchichtFenster.MACHBAR);
            g.drawRoundRect(RAND - 4, y, b + 8, hoehe, 10, 10);
            int zy = y + 4;
            g.setFont(FETT);
            g.setColor(SCHRIFT);
            for (String z : kopf) {
                zy += 18;
                g.drawString(kuerzen(z, g.getFontMetrics(), b), RAND, zy);
            }
            if (grundKnoepfe) {
                for (int i = 0; i < SchichtSteuerung.GRUENDE.size(); i += 2) {
                    zy += 5;
                    int kx = knopfMalen(g, (i + 1) + " " + SchichtSteuerung.grundText(SchichtSteuerung.GRUENDE.get(i)), false, RAND, zy, SchichtSteuerung.KLICK_GRUND - i);
                    knopfMalen(g, (i + 2) + " " + SchichtSteuerung.grundText(SchichtSteuerung.GRUENDE.get(i + 1)), false, kx, zy, SchichtSteuerung.KLICK_GRUND - i - 1);
                    zy += 18;
                }
            }
            if (mitKnoepfen) {
                zy += 5;
                int kx = RAND;
                for (int i = 0; i < SchichtSteuerung.KOMMENTAR_ARTEN.size() && !SchichtSteuerung.STAGE_ART.equals(a.kommentarArt()); i++) {
                    String art = SchichtSteuerung.KOMMENTAR_ARTEN.get(i);
                    kx = knopfMalen(g, SchichtSteuerung.artText(art), art.equals(a.kommentarArt()), kx, zy, SchichtSteuerung.KLICK_ART - i);
                }
                zy += 23;
            }
            g.setFont(NORMAL);
            g.setColor(SCHRIFT);
            for (String z : rumpf) {
                zy += 17;
                g.drawString(z, RAND + 4, zy);
            }
            if (mitKnoepfen) {
                zy += 5;
                int kx = knopfMalen(g, Sprache.t("Send", "Abschicken"), false, RAND, zy, SchichtSteuerung.KLICK_SENDEN);
                knopfMalen(g, Sprache.t("Cancel", "Abbrechen"), false, kx, zy, SchichtSteuerung.KLICK_ABBRECHEN);
                zy += 18;
            }
            g.setFont(KLEIN);
            g.setColor(LEISE);
            zy += 16;
            g.drawString(kuerzen(fuss, g.getFontMetrics(), b), RAND, zy);
            return y + hoehe + 4;
        }

        /**
         * Tasten-Hilfe ganz unten, die Tasten als kleine Kaestchen (29.10. nachts, vorher ein langer Satz); gibt die
         * Oberkante zurueck (bis dahin darf der Rest malen).
         */
        private int hilfeMalen(Graphics2D g, int b, boolean botSpielt) {
            String[][] tasten = tasten();
            // erst ausmessen (Zeilen umbrechen), dann von unten her malen
            g.setFont(KLEIN);
            FontMetrics fm = g.getFontMetrics();
            g.setFont(KLEIN_FETT);
            FontMetrics fmT = g.getFontMetrics();
            List<List<String[]>> reihen = new ArrayList<>();
            List<String[]> reihe = new ArrayList<>();
            int x = 0;
            for (String[] t : tasten) {
                int w = fmT.stringWidth(t[0]) + 10 + 4 + fm.stringWidth(t[1]) + 12;
                if (x + w > b && !reihe.isEmpty()) {
                    reihen.add(reihe);
                    reihe = new ArrayList<>();
                    x = 0;
                }
                reihe.add(t);
                x += w;
            }
            if (!reihe.isEmpty()) reihen.add(reihe);
            int zeile = 20;
            int oben = getHeight() - RAND - reihen.size() * zeile + 4;
            g.setColor(GRAU);
            g.drawLine(RAND, oben - 6, RAND + b, oben - 6);
            int y = oben;
            for (List<String[]> r : reihen) {
                x = RAND;
                for (String[] t : r) {
                    int kw = fmT.stringWidth(t[0]) + 10;
                    g.setColor(TASTE_GRUND);
                    g.fillRoundRect(x, y + 1, kw, 16, 6, 6);
                    g.setFont(KLEIN_FETT);
                    g.setColor(SCHRIFT);
                    g.drawString(t[0], x + 5, y + 13);
                    g.setFont(KLEIN);
                    g.setColor(LEISE);
                    g.drawString(t[1], x + kw + 4, y + 13);
                    x += kw + 4 + fm.stringWidth(t[1]) + 12;
                }
                y += zeile;
            }
            return oben - 12;
        }

        /** Taste und was sie tut -- dieselben Tasten wie SchichtSteuerung.hilfe(). */
        private String[][] tasten() {
            return new String[][]{
                    {"WASD", Sprache.t("choose", "waehlen")},
                    {"1", Sprache.t("safe", "sicher")},
                    {"2", Sprache.t("doable", "machbar")},
                    {"3", Sprache.t("avoid", "meiden")},
                    {"4", Sprache.t("never", "niemals")},
                    {"5", Sprache.t("almost safe", "fast sicher")},
                    {"6", Sprache.t("pause", "Pause")},
                    {"0", Sprache.t("none", "keine")},
                    {"←", Sprache.t("undo", "zurueck")},
                    {"K", Sprache.t("comment", "Kommentar")},
                    {"G", Sprache.t("stage comment", "Stage-Kommentar")},
                    {"^", Sprache.t("hide window", "Fenster weg")},
                    {"J", Sprache.t("agree", "passt")},
                    {"N", Sprache.t("disagree", "passt nicht")},
                    {Sprache.t("Space", "Leertaste"), Sprache.t("info", "Info")},
            };
        }

        private String kuerzen(String text, FontMetrics fm, int breite) {
            if (fm.stringWidth(text) <= breite) return text;
            String t = text;
            while (t.length() > 1 && fm.stringWidth(t + "…") > breite) t = t.substring(0, t.length() - 1);
            return t + "…";
        }
    }
}
