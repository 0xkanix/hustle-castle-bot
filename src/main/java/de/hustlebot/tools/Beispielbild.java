package de.hustlebot.tools;

import de.hustlebot.core.Sprache;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Autor 24.10. (Ideenliste F14): Ein kleines Beispielbild je Aufnahme-Schritt -- "so soll der Ausschnitt aussehen": der
 * Bildschirm als einfache Zeichnung, der gesuchte Teil mit gruenem Rahmen.
 *
 * Es ist bewusst eine ZEICHNUNG (Schema) und kein Foto: Mitgeliefert werden nur die Knoepfe, nicht die eigenen Kaempfer
 * und Namen -- und ein echtes Foto zeigte jemand anderes' Konto. Die Zeichnung sagt nur, WO der Rahmen hingehoert und
 * wie eng er sein soll. Darum steht unten "Schema".
 */
public final class Beispielbild extends JComponent {

    /** Was eingerahmt wird. */
    public enum Motiv { GESICHT, NAME, NAMENSSCHILD, FREUND, ARENA_NAME, KNOPF, TEXT, EVENT }

    /** Gezeichnet wird auf 260 x 130 und auf die echte Groesse umgerechnet. */
    static final int B = 260, H = 130;

    private static final Color FOND = new Color(44, 34, 28);
    private static final Color HOLZ = new Color(110, 76, 44);
    private static final Color WEISS = new Color(235, 235, 235);
    private static final Color GRAU = new Color(150, 150, 150);
    static final Color RAHMEN = new Color(46, 204, 64);

    private Motiv motiv = Motiv.GESICHT;

    public Beispielbild() {
        setPreferredSize(new Dimension(B, H));
        setMinimumSize(new Dimension(B, H));
        setToolTipText(Sprache.t("Schematic drawing: the green frame shows the part you should frame. Your game looks a little different.",
                "Schema-Zeichnung: der grüne Rahmen zeigt, was du einrahmen sollst. Dein Spiel sieht etwas anders aus."));
    }

    public void setMotiv(Motiv m) {
        if (m != null && m != motiv) {
            motiv = m;
            repaint();
        }
    }

    public Motiv motiv() {
        return motiv;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double s = Math.min(getWidth() / (double) B, getHeight() / (double) H);
            g.scale(s, s);
            zeichne(g, motiv);
        } finally {
            g.dispose();
        }
    }

    /** Zeichnet das Motiv auf die Flaeche 260 x 130 (auch ohne Fenster nutzbar, z. B. im Test). */
    static void zeichne(Graphics2D g, Motiv m) {
        g.setColor(FOND);
        g.fillRect(0, 0, B, H);
        switch (m) {
            case GESICHT, NAME -> {
                schrift(g, 50, 6, 160, 5, new Color(40, 150, 150));
                Color[] haut = {new Color(224, 172, 130), new Color(200, 150, 110), new Color(236, 190, 150),
                        new Color(180, 130, 100), new Color(214, 166, 120), new Color(196, 160, 130)};
                for (int i = 0; i < 6; i++) {
                    int x = 12 + i * 40;
                    g.setColor(HOLZ);
                    g.fillRoundRect(x - 2, 22, 36, 36, 8, 8);
                    g.setColor(haut[i]);
                    g.fillRoundRect(x, 24, 32, 32, 8, 8);
                    g.setColor(new Color(70, 50, 40));
                    g.fillRect(x, 24, 32, 8);                       // Haare
                    g.setColor(Color.BLACK);
                    g.fillOval(x + 9, 38, 4, 4);
                    g.fillOval(x + 19, 38, 4, 4);
                    schrift(g, x + 3, 62, 26, 5, WEISS);            // Name unter dem Gesicht
                    g.setColor(new Color(60, 180, 60));
                    g.fillRect(x, 72, 32, 4);                       // Lebensbalken
                }
                if (m == Motiv.GESICHT) rahmen(g, 9, 21, 38, 38);
                else rahmen(g, 12, 59, 32, 11);
                schrift(g, 12, 95, 100, 4, GRAU);
                schrift(g, 12, 105, 70, 4, GRAU);
            }
            case NAMENSSCHILD -> {
                g.setColor(new Color(70, 96, 52));
                g.fillRect(0, 0, B, H);
                g.setColor(new Color(176, 150, 96));
                g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(40, 20, 120, 50);
                g.drawLine(120, 50, 200, 30);
                g.drawLine(200, 30, 230, 70);
                g.setStroke(new BasicStroke(1f));
                for (int i = 0; i < 2; i++) {
                    int x = 16 + i * 124;
                    g.setColor(HOLZ);
                    g.fillRoundRect(x, 88, 104, 28, 10, 10);
                    g.setColor(new Color(60, 40, 24));
                    g.fillRoundRect(x + 4, 92, 96, 20, 8, 8);
                    schrift(g, x + 14, 98, 74, 8, WEISS);
                }
                rahmen(g, 26, 94, 84, 17);
            }
            case FREUND -> {
                for (int i = 0; i < 3; i++) {
                    int y = 14 + i * 38;
                    g.setColor(i == 1 ? new Color(70, 54, 42) : new Color(56, 44, 36));
                    g.fillRoundRect(8, y, 244, 32, 8, 8);
                    schrift(g, 16, y + 11, 12, 8, new Color(230, 190, 80));   // Nummer
                    schrift(g, 46, y + 7, 84, 9, WEISS);                      // Name
                    schrift(g, 46, y + 21, 56, 5, GRAU);                      // Clan darunter
                    g.setColor(new Color(240, 140, 30));
                    g.fillOval(140, y + 8, 16, 16);                           // oranges "i"
                    g.setColor(WEISS);
                    g.fillRect(147, y + 12, 2, 8);
                    g.setColor(new Color(60, 170, 60));
                    g.fillRoundRect(190, y + 6, 52, 20, 8, 8);
                    g.setColor(WEISS);
                    g.fillRect(202, y + 14, 28, 4);
                }
                rahmen(g, 43, 49, 90, 16);
            }
            case ARENA_NAME -> {
                Path2D.Double schild = new Path2D.Double();
                schild.moveTo(70, 8);
                schild.lineTo(190, 8);
                schild.lineTo(190, 70);
                schild.lineTo(130, 118);
                schild.lineTo(70, 70);
                schild.closePath();
                g.setColor(new Color(200, 160, 60));
                g.fill(schild);
                g.setColor(new Color(40, 50, 90));
                g.fillRect(76, 14, 108, 52);
                schrift(g, 92, 36, 76, 9, WEISS);                             // Name
                schrift(g, 104, 54, 52, 5, GRAU);                             // Clan
                g.setColor(new Color(200, 160, 60));
                g.fillOval(118, 76, 24, 24);                                  // Wappen
                rahmen(g, 88, 32, 84, 17);
            }
            case KNOPF -> {
                g.setPaint(new GradientPaint(0, 45, new Color(110, 210, 70), 0, 85, new Color(40, 130, 40)));
                g.fillRoundRect(70, 45, 120, 40, 16, 16);
                g.setColor(new Color(190, 240, 150));
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(70, 45, 120, 40, 16, 16);
                g.setStroke(new BasicStroke(1f));
                schrift(g, 96, 60, 68, 10, WEISS);
                rahmen(g, 66, 41, 128, 48);
            }
            case TEXT -> {
                for (int i = 0; i < 4; i++) {
                    g.setColor(new Color(200, 160, 60));
                    g.fillRoundRect(40 + i * 46, 12, 34, 34, 8, 8);
                }
                g.setColor(new Color(20, 14, 10, 220));
                g.fillRect(14, 62, 232, 34);
                schrift(g, 42, 74, 176, 9, WEISS);
                rahmen(g, 38, 70, 184, 17);
            }
            case EVENT -> {
                int[] xs = {26, 106, 186};
                for (int i = 0; i < 3; i++) {
                    g.setColor(new Color(150, 200, 230, 90));
                    g.fillRoundRect(xs[i], 18, 50, 84, 22, 22);
                    g.setColor(new Color(230, 245, 255, 190));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(xs[i], 18, 50, 84, 22, 22);
                    g.setStroke(new BasicStroke(1f));
                }
                g.setColor(new Color(250, 200, 40));
                g.fillOval(118, 50, 26, 26);                                  // das Teil in der Vase
                g.setColor(new Color(220, 120, 20));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(118, 50, 26, 26);
                g.setStroke(new BasicStroke(1f));
                rahmen(g, 114, 46, 34, 34);
            }
        }
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
        g.setColor(new Color(255, 255, 255, 140));
        g.drawString(Sprache.t("Schematic example", "Schema-Beispiel"), 6, H - 4);
    }

    /** Wie Schrift: eine Reihe kurzer Balken mit Luecken. */
    private static void schrift(Graphics2D g, int x, int y, int breite, int hoehe, Color c) {
        g.setColor(c);
        int[] teile = {5, 3, 6, 4, 3, 5, 2, 6};
        int px = x, i = 0;
        while (px < x + breite) {
            int w = Math.min(teile[i++ % teile.length], x + breite - px);
            g.fillRect(px, y, w, hoehe);
            px += w + 2;
        }
    }

    /** Der gruene Rahmen um den gesuchten Teil. */
    private static void rahmen(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(46, 204, 64, 70));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(0, 0, 0, 170));   // dunkler Saum, damit der Rahmen auch auf gruenen Knoepfen zu sehen ist
        g.setStroke(new BasicStroke(5f));
        g.drawRect(x, y, w, h);
        g.setColor(RAHMEN);
        g.setStroke(new BasicStroke(3f));
        g.drawRect(x, y, w, h);
        g.setStroke(new BasicStroke(1f));
    }
}
