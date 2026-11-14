package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Autor 24.10. (Ideenliste A04): Suche der geschlossenen Faesser im Hammer-Event.
 *
 * Gemessen (Bericht 06:15): neun Fass-Bilder je ~580 Bilder x 11 ms = 57 s von 88 s Vergleichszeit -- jedes Bild wurde
 * ueber das GANZE Fenster (1159 x 770) gesucht, obwohl die Faesser nur im Spielfeld (5 x 4) stehen. Steht das Raster,
 * wird nur noch ein Ausschnitt um die zwanzig Felder durchsucht (rund ein Fuenftel der Bildpunkte). Gerechnet wird
 * genau dasselbe wie vorher (Graustufen, gleiche Schwelle, gleiche Fundstellen); nur Treffer ausserhalb des Feldes
 * fallen weg -- die zaehlten bisher mit, gehoerten aber zu keinem der zwanzig Felder.
 */
final class Vasensuche {

    private Vasensuche() {
    }

    /**
     * Der Ausschnitt um die zwanzig Felder: jede Vorlage (bis ~64 Bildpunkte) liegt mit 20 Punkten Luft ringsum ganz
     * darin. null = Raster noch unbekannt, dann gilt das ganze Bild.
     */
    static Rect feldBereich(EventMode.Raster r, int breite, int hoehe) {
        if (r == null) return null;
        int luft = 52;   // halbe Vorlage (32) + 20 Punkte Wackelraum
        int x = Math.max(0, r.x0() - luft);
        int y = Math.max(0, r.y0() - luft);
        int x2 = Math.min(breite, r.mitteX(EventMode.SPALTEN - 1) + luft);
        int y2 = Math.min(hoehe, r.mitteY(EventMode.REIHEN - 1) + luft);
        if (x2 - x < 100 || y2 - y < 100) return null;
        return new Rect(x, y, x2 - x, y2 - y);
    }

    /**
     * Alle Stellen, an denen eines der Fass-Bilder passt (Graustufen), doppelte Stellen (unter 25 Punkten Abstand)
     * nur einmal. Die Treffer stehen in Fensterkoordinaten, auch wenn nur im Ausschnitt gesucht wurde.
     */
    static List<Matcher.Hit> suchen(Matcher matcher, Mat screen, List<String> motive, double schwelle, int maxJeMotiv,
                                    Rect bereich) {
        List<Matcher.Hit> alle = new ArrayList<>();
        Mat ausschnitt = bereich == null ? null : new Mat(screen, bereich);
        try {
            Mat bild = ausschnitt != null ? ausschnitt : screen;
            int ox = bereich == null ? 0 : bereich.x, oy = bereich == null ? 0 : bereich.y;
            for (String motiv : motive) {
                for (Matcher.Hit t : matcher.findAllGrau(bild, motiv, schwelle, maxJeMotiv)) {
                    Matcher.Hit h = (ox == 0 && oy == 0) ? t
                            : new Matcher.Hit(t.name(), t.x() + ox, t.y() + oy, t.width(), t.height(), t.score());
                    boolean doppelt = false;
                    for (Matcher.Hit a : alle) {
                        doppelt |= Math.abs(a.centerX() - h.centerX()) < 25 && Math.abs(a.centerY() - h.centerY()) < 25;
                    }
                    if (!doppelt) alle.add(h);
                }
            }
        } finally {
            if (ausschnitt != null) ausschnitt.release();
        }
        return alle;
    }
}
