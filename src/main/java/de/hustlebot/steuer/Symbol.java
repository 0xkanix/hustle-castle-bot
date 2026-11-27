package de.hustlebot.steuer;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

/**
 * Kleine Symbole fuer Knoepfe und Leisten, selbst gezeichnet (keine Bilddateien, keine Schrift-Symbole,
 * die auf einem anderen Windows fehlen koennten): Start-Dreieck, Pause-Balken, Stopp-Quadrat, Not-Aus-Achteck,
 * Liste, Kreislauf, Neustart, Ausschalter, Kreuz, Lupe, Mikrofon, Pfeil, Haken.
 *
 * Gemalt wird in der Schriftfarbe des Knopfes (Holzstil.Knopf ruft malen), sonst in getForeground().
 */
public final class Symbol implements Icon {

    public enum Art {
        START, PAUSE, WEITER, STOPP, NOTAUS, LISTE, KREISLAUF, NEUSTART, AUS, KREUZ, LUPE, MIKRO,
        PFEIL_UNTEN, PFEIL_OBEN, HAKEN, NEU, ORDNER, STERN, FENSTER, PUNKT
    }

    private final Art art;
    private final int groesse;

    public Symbol(Art art, int groesse) {
        this.art = art;
        this.groesse = groesse;
    }

    public Art art() {
        return art;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        malen(g, x, y, c == null ? Holzstil.TEXT : c.getForeground());
    }

    /** Malt das Symbol an die Stelle, in der Farbe. */
    public void malen(Graphics g, int x, int y, Color farbe) {
        Graphics2D g2 = Holzstil.weich(g);
        g2.setColor(farbe);
        float s = groesse;
        float strich = Math.max(1.6f, s / 8f);
        g2.setStroke(new BasicStroke(strich, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.translate(x, y);
        switch (art) {
            case START, WEITER -> {
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.2, s * 0.08);
                p.lineTo(s * 0.92, s * 0.5);
                p.lineTo(s * 0.2, s * 0.92);
                p.closePath();
                g2.fill(p);
            }
            case PAUSE -> {
                g2.fillRoundRect(r(s * 0.18), r(s * 0.1), r(s * 0.24), r(s * 0.8), 2, 2);
                g2.fillRoundRect(r(s * 0.58), r(s * 0.1), r(s * 0.24), r(s * 0.8), 2, 2);
            }
            case STOPP -> g2.fillRoundRect(r(s * 0.15), r(s * 0.15), r(s * 0.7), r(s * 0.7), 3, 3);
            case NOTAUS -> {
                // Achteck wie ein Stoppschild, innen ein Balken
                Path2D p = new Path2D.Float();
                double k = 0.29;
                p.moveTo(s * k, 0); p.lineTo(s * (1 - k), 0); p.lineTo(s, s * k); p.lineTo(s, s * (1 - k));
                p.lineTo(s * (1 - k), s); p.lineTo(s * k, s); p.lineTo(0, s * (1 - k)); p.lineTo(0, s * k);
                p.closePath();
                g2.fill(p);
                g2.setColor(Holzstil.durchsichtig(Holzstil.istDunkel() ? Holzstil.KOPF : Holzstil.HELL, 230));
                g2.fillRoundRect(r(s * 0.22), r(s * 0.42), r(s * 0.56), r(s * 0.16), 2, 2);
            }
            case LISTE -> {
                for (int i = 0; i < 3; i++) {
                    float yy = s * (0.2f + i * 0.3f);
                    g2.drawLine(r(s * 0.12), r(yy), r(s * 0.88), r(yy));
                }
            }
            case KREISLAUF, NEUSTART, NEU -> {
                // offener Kreis mit Pfeilspitze
                g2.draw(new Arc2D.Float(s * 0.12f, s * 0.12f, s * 0.76f, s * 0.76f, 30, 290, Arc2D.OPEN));
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.98, s * 0.12);
                p.lineTo(s * 0.98, s * 0.5);
                p.lineTo(s * 0.62, s * 0.5);
                p.closePath();
                g2.fill(p);
            }
            case AUS -> {
                // Kreis mit Luecke oben, Strich hinein -- der Ausschalter
                g2.draw(new Arc2D.Float(s * 0.12f, s * 0.14f, s * 0.76f, s * 0.76f, 120, 300, Arc2D.OPEN));
                g2.drawLine(r(s * 0.5), r(s * 0.04), r(s * 0.5), r(s * 0.48));
            }
            case KREUZ -> {
                g2.drawLine(r(s * 0.2), r(s * 0.2), r(s * 0.8), r(s * 0.8));
                g2.drawLine(r(s * 0.8), r(s * 0.2), r(s * 0.2), r(s * 0.8));
            }
            case LUPE -> {
                g2.drawOval(r(s * 0.1), r(s * 0.1), r(s * 0.55), r(s * 0.55));
                g2.drawLine(r(s * 0.62), r(s * 0.62), r(s * 0.92), r(s * 0.92));
            }
            case MIKRO -> {
                g2.fillRoundRect(r(s * 0.35), r(s * 0.05), r(s * 0.3), r(s * 0.5), r(s * 0.3), r(s * 0.3));
                g2.draw(new Arc2D.Float(s * 0.2f, s * 0.25f, s * 0.6f, s * 0.5f, 180, 180, Arc2D.OPEN));
                g2.drawLine(r(s * 0.5), r(s * 0.75), r(s * 0.5), r(s * 0.95));
                g2.drawLine(r(s * 0.32), r(s * 0.95), r(s * 0.68), r(s * 0.95));
            }
            case PFEIL_UNTEN -> {
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.15, s * 0.3);
                p.lineTo(s * 0.85, s * 0.3);
                p.lineTo(s * 0.5, s * 0.78);
                p.closePath();
                g2.fill(p);
            }
            case PFEIL_OBEN -> {   // Warteschlange 15.11.: Zeile nach oben
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.15, s * 0.7);
                p.lineTo(s * 0.85, s * 0.7);
                p.lineTo(s * 0.5, s * 0.22);
                p.closePath();
                g2.fill(p);
            }
            case HAKEN -> {
                g2.drawLine(r(s * 0.15), r(s * 0.55), r(s * 0.4), r(s * 0.8));
                g2.drawLine(r(s * 0.4), r(s * 0.8), r(s * 0.88), r(s * 0.22));
            }
            case ORDNER -> {
                g2.drawRoundRect(r(s * 0.08), r(s * 0.25), r(s * 0.84), r(s * 0.6), 3, 3);
                g2.drawLine(r(s * 0.08), r(s * 0.25), r(s * 0.38), r(s * 0.25));
                g2.drawLine(r(s * 0.38), r(s * 0.25), r(s * 0.48), r(s * 0.12));
                g2.drawLine(r(s * 0.48), r(s * 0.12), r(s * 0.7), r(s * 0.12));
            }
            case STERN -> {
                Path2D p = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double w = Math.PI / 2 + i * Math.PI / 5;
                    double rad = (i % 2 == 0 ? 0.48 : 0.2) * s;
                    double px = s * 0.5 + Math.cos(w) * rad, py = s * 0.5 - Math.sin(w) * rad;
                    if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                p.closePath();
                g2.fill(p);
            }
            case FENSTER -> {
                g2.drawRoundRect(r(s * 0.1), r(s * 0.15), r(s * 0.8), r(s * 0.7), 3, 3);
                g2.drawLine(r(s * 0.1), r(s * 0.35), r(s * 0.9), r(s * 0.35));
            }
            case PUNKT -> g2.fillOval(r(s * 0.25), r(s * 0.25), r(s * 0.5), r(s * 0.5));
        }
        g2.dispose();
    }

    private static int r(double v) {
        return (int) Math.round(v);
    }

    @Override
    public int getIconWidth() {
        return groesse;
    }

    @Override
    public int getIconHeight() {
        return groesse;
    }
}
