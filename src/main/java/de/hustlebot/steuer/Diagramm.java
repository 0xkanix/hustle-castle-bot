package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Ein kleines Diagramm fuer die Statistik (Auftrag 4, Autor 11.10.): Balken oder Linie, mit Ueberschrift,
 * Beschriftung unter jedem Wert und der Zahl darueber. Nur Swing, selbst gemalt, in den Holz-Toenen.
 *
 * Ohne Werte steht ein ruhiger Hinweis da ("noch nichts gemessen") statt einer leeren Flaeche.
 */
public final class Diagramm extends JComponent {

    public enum Art { BALKEN, LINIE }

    /** Ein Wert mit Beschriftung (etwa "14 Uhr" -> 12). */
    public record Wert(String name, double zahl) {}

    private final Art art;
    private final String titel;
    private final List<Wert> werte = new ArrayList<>();
    private Color farbe;
    private String leer = Sprache.t("nothing measured yet", "noch nichts gemessen");
    private String einheit = "";
    private LongFunction<String> format = Holzstil::zahl;
    private double hoechstens = -1;

    public Diagramm(String titel, Art art, Color farbe) {
        this.titel = titel;
        this.art = art;
        this.farbe = farbe;
        setOpaque(false);
        setPreferredSize(new Dimension(300, 170));
        setMinimumSize(new Dimension(200, 130));
    }

    /** Text, wenn es keine Werte gibt. */
    public Diagramm leerText(String text) {
        this.leer = text;
        return this;
    }

    /** Einheit hinter den Zahlen ("%", " je Std."). */
    public Diagramm einheit(String einheit) {
        this.einheit = einheit == null ? "" : einheit;
        return this;
    }

    /** Eigene Zahlendarstellung (etwa "1,2 Mio"). */
    public Diagramm format(LongFunction<String> f) {
        if (f != null) this.format = f;
        return this;
    }

    /** Feste Obergrenze der Achse (etwa 100 bei Prozent), sonst der groesste Wert. */
    public Diagramm hoechstens(double h) {
        this.hoechstens = h;
        return this;
    }

    public Diagramm farbe(Color c) {
        if (c != null) this.farbe = c;
        return this;
    }

    /** Werte setzen (ersetzt die alten) und neu malen. */
    public void setzen(List<Wert> neue) {
        werte.clear();
        if (neue != null) werte.addAll(neue);
        repaint();
    }

    public List<Wert> werte() {
        return List.copyOf(werte);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Holzstil.weich(g);
        int w = getWidth(), h = getHeight();
        // Karte
        g2.setColor(Holzstil.HELL);
        g2.fillRoundRect(0, 0, w - 1, h - 1, Holzstil.RUNDUNG, Holzstil.RUNDUNG);
        g2.setColor(Holzstil.LINIE);
        g2.drawRoundRect(0, 0, w - 1, h - 1, Holzstil.RUNDUNG, Holzstil.RUNDUNG);
        // Titel
        g2.setFont(Holzstil.KNOPF);
        g2.setColor(Holzstil.LEISE);
        FontMetrics fmT = g2.getFontMetrics();
        g2.drawString(titel, 14, 8 + fmT.getAscent());

        int links = 14, rechts = w - 14, oben = 8 + fmT.getHeight() + 10;
        g2.setFont(Holzstil.KLEIN);
        FontMetrics fm = g2.getFontMetrics();
        int unten = h - 12 - fm.getHeight() - 4;   // Platz fuer die Beschriftung

        if (werte.isEmpty()) {
            g2.setColor(Holzstil.LEISE);
            int tb = fm.stringWidth(leer);
            g2.drawString(leer, (w - tb) / 2, (oben + unten) / 2 + fm.getAscent() / 2);
            g2.dispose();
            return;
        }

        double max = hoechstens > 0 ? hoechstens : 0;
        if (hoechstens <= 0) {
            for (Wert v : werte) max = Math.max(max, v.zahl());
            if (max <= 0) max = 1;
            max *= 1.15;   // Luft fuer die Zahl ueber dem Balken
        }
        // Grundlinie
        g2.setColor(Holzstil.LINIE);
        g2.drawLine(links, unten, rechts, unten);

        int n = werte.size();
        double breite = (double) (rechts - links) / n;
        int hoehe = unten - oben;
        boolean alleBeschriften = breite >= fm.stringWidth("88:88") + 6;
        int schritt = alleBeschriften ? 1 : Math.max(1, (int) Math.ceil((fm.stringWidth("88:88") + 6) / breite));

        if (art == Art.BALKEN) {
            int luft = Math.max(2, (int) (breite * 0.2));
            for (int i = 0; i < n; i++) {
                Wert v = werte.get(i);
                int x = (int) Math.round(links + i * breite) + luft / 2;
                int bw = Math.max(2, (int) Math.round(breite) - luft);
                int bh = (int) Math.round(hoehe * Math.max(0, v.zahl()) / max);
                g2.setColor(farbe);
                g2.fillRoundRect(x, unten - bh, bw, bh, 5, 5);
                if (bh > 0 && bw >= 18) {
                    String z = zahlText(v.zahl());
                    g2.setColor(Holzstil.TEXT);
                    g2.drawString(z, x + (bw - fm.stringWidth(z)) / 2, unten - bh - 3);
                }
                if (i % schritt == 0) {
                    g2.setColor(Holzstil.LEISE);
                    String name = v.name();
                    g2.drawString(name, x + (bw - fm.stringWidth(name)) / 2, unten + 4 + fm.getAscent());
                }
            }
        } else {
            Path2D linie = new Path2D.Double();
            Path2D flaeche = new Path2D.Double();
            int[] xs = new int[n], ys = new int[n];
            for (int i = 0; i < n; i++) {
                double mitte = n == 1 ? (links + rechts) / 2.0 : links + i * (rechts - links) / (double) (n - 1);
                xs[i] = (int) Math.round(mitte);
                ys[i] = unten - (int) Math.round(hoehe * Math.max(0, werte.get(i).zahl()) / max);
                if (i == 0) {
                    linie.moveTo(xs[i], ys[i]);
                    flaeche.moveTo(xs[i], unten);
                    flaeche.lineTo(xs[i], ys[i]);
                } else {
                    linie.lineTo(xs[i], ys[i]);
                    flaeche.lineTo(xs[i], ys[i]);
                }
            }
            flaeche.lineTo(xs[n - 1], unten);
            flaeche.closePath();
            g2.setColor(Holzstil.durchsichtig(farbe, 45));
            g2.fill(flaeche);
            g2.setColor(farbe);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(linie);
            for (int i = 0; i < n; i++) {
                g2.setColor(Holzstil.HELL);
                g2.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
                g2.setColor(farbe);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(xs[i] - 4, ys[i] - 4, 8, 8);
                boolean beschriften = i % schritt == 0 || i == n - 1;
                if (beschriften) {
                    g2.setColor(Holzstil.LEISE);
                    String name = werte.get(i).name();
                    int tx = Math.max(links, Math.min(rechts - fm.stringWidth(name), xs[i] - fm.stringWidth(name) / 2));
                    g2.drawString(name, tx, unten + 4 + fm.getAscent());
                }
                if (i == 0 || i == n - 1 || n <= 6) {
                    String z = zahlText(werte.get(i).zahl());
                    g2.setColor(Holzstil.TEXT);
                    int tx = Math.max(links, Math.min(rechts - fm.stringWidth(z), xs[i] - fm.stringWidth(z) / 2));
                    g2.drawString(z, tx, ys[i] - 7);
                }
            }
        }
        g2.dispose();
    }

    private String zahlText(double zahl) {
        String z = zahl == Math.rint(zahl) ? format.apply((long) zahl) : String.format(java.util.Locale.GERMANY, "%.1f", zahl);
        return z + einheit;
    }
}
