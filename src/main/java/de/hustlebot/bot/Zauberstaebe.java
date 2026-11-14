package de.hustlebot.bot;

import java.awt.image.BufferedImage;
import java.util.OptionalLong;

/**
 * Zauberstaebe im Hammer-Event ablesen (Autor, 29.09.): Reiter "Rankings", dort
 * steht unten die eigene Zeile, gelbgruen hinterlegt (alle anderen blau), rechts
 * die Zahl der Zauberstaebe neben dem Stab-Bild.
 *
 * Gemessen an Autors Bild vom 29.09.: eigene Zeile etwa RGB 164/197/75, die
 * Zeile von Platz 1 etwa 77/112/212. Die Zeile wird deshalb an der Farbe
 * gefunden, nicht an einer festen Stelle -- die Liste kann verschoben sein.
 */
final class Zauberstaebe {

    private Zauberstaebe() {}

    /** Die eigene Zahl Zauberstaebe, leer wenn keine gelbgruene Zeile oder keine Zahl da ist. */
    static OptionalLong ausRangliste(BufferedImage bild) {
        if (bild == null) return OptionalLong.empty();
        int w = bild.getWidth(), h = bild.getHeight();
        int x1 = (int) (w * 0.60), x2 = (int) (w * 0.95);

        // Laengster Lauf von Zeilen, die ueberwiegend gelbgruen sind. Oben (Reiter,
        // "Done!"-Balken) wird nicht gesucht.
        int bestOben = -1, bestUnten = -1, oben = -1;
        for (int y = (int) (h * 0.30); y < (int) (h * 0.97); y++) {
            int gruen = 0, alle = 0;
            for (int x = x1; x < x2; x += 3) {
                alle++;
                if (gelbgruen(bild.getRGB(x, y))) gruen++;
            }
            boolean zeile = gruen * 100 >= alle * 45;
            if (zeile && oben < 0) oben = y;
            if ((!zeile || y == (int) (h * 0.97) - 1) && oben >= 0) {
                if (y - oben > bestUnten - bestOben) {
                    bestOben = oben;
                    bestUnten = y;
                }
                oben = -1;
            }
        }
        if (bestOben < 0 || bestUnten - bestOben < h * 0.03) return OptionalLong.empty();

        // Die Zahl steht rechts, links vom Stab-Bild.
        int zx = (int) (w * 0.76), zb = (int) (w * 0.925) - zx;
        return Nahrungskisten.zahlVerstaerkt(bild, zx, bestOben, zb, bestUnten - bestOben, true);
    }

    /** Das Gelbgruen der eigenen Zeile (nicht das Blau der anderen, nicht Weiss). */
    static boolean gelbgruen(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return g > 150 && g > r + 10 && r > 90 && b < 120;
    }
}
