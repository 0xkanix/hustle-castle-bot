package de.hustlebot.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Namensbild des eigenen Spielers (Autor 21.11.: "Erkennungsmerkmale nur Spieler-ID und Name"; Schmuckschrift wird von der
 * Texterkennung mal so, mal so gelesen -- bei Autor entstand so aus "Autor" ein zweites Konto "Autori").
 *
 * WIE. Oben links in der Burg steht der Name in weisser Schrift mit dunklem Rand, mitten auf der Burg-Grafik. Als Schrift
 * zaehlt nur, was weiss ist UND einen dunklen Rand in der Naehe hat (so faellt helle Burg-Grafik weg). Davon die erste
 * Textzeile, eng ausgeschnitten, als Schwarz-Weiss-Maske. Zwei Masken gelten als gleich, wenn sie fast gleich gross sind und
 * sich (bis 2 Bildpunkte verschoben) zu mindestens {@link #GLEICH_AB} decken. Reines Java, kein OpenCV -- billig und testbar.
 */
public final class Namensbild {

    private Namensbild() { }

    /** Weiss = alle drei Farben ueber diesem Wert; dunkel = alle drei darunter. */
    static final int WEISS_AB = 215, DUNKEL_BIS = 75;
    /** So weit (Bildpunkte) darf der dunkle Rand von einem Schriftpunkt entfernt sein. */
    static final int RAND = 3;
    /** Deckung (Schnitt durch Vereinigung) ab der zwei Namensbilder gleich sind. */
    static final double GLEICH_AB = 0.80;

    /**
     * Die erste Textzeile (der Name) im Bereich als Maske [y][x], eng ausgeschnitten; null = keine Schrift erkannt.
     */
    public static boolean[][] ausBereich(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return null;
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        int w = x2 - x1, h = y2 - y1;
        if (w < 10 || h < 8) return null;
        boolean[][] weiss = new boolean[h][w], dunkel = new boolean[h][w];
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int p = bild.getRGB(x1 + xx, y1 + yy);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                weiss[yy][xx] = Math.min(r, Math.min(g, b)) > WEISS_AB;
                dunkel[yy][xx] = Math.max(r, Math.max(g, b)) < DUNKEL_BIS;
            }
        }
        boolean[][] schrift = new boolean[h][w];
        int[] zeilen = new int[h];
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                if (!weiss[yy][xx] || !randNah(dunkel, xx, yy)) continue;
                schrift[yy][xx] = true;
                zeilen[yy]++;
            }
        }
        // die erste Zeile: von der ersten Zeile mit Schrift bis zur ersten Luecke von 2 leeren Zeilen
        int oben = -1;
        for (int yy = 0; yy < h && oben < 0; yy++) if (zeilen[yy] >= 2) oben = yy;
        if (oben < 0) return null;
        int unten = oben, leer = 0;
        for (int yy = oben + 1; yy < h; yy++) {
            if (zeilen[yy] == 0) {
                if (++leer >= 2) break;
            } else {
                leer = 0;
                unten = yy;
            }
        }
        int zh = unten - oben + 1;
        if (zh < 6) return null;
        // Spalten: ab der ersten Schriftspalte, Luecken bis zur Zeilenhoehe (Wortabstand) gehoeren dazu
        int[] spalten = new int[w];
        for (int yy = oben; yy <= unten; yy++) for (int xx = 0; xx < w; xx++) if (schrift[yy][xx]) spalten[xx]++;
        int links = -1;
        for (int xx = 0; xx < w && links < 0; xx++) if (spalten[xx] > 0) links = xx;
        if (links < 0) return null;
        int rechts = links, luecke = 0;
        for (int xx = links + 1; xx < w; xx++) {
            if (spalten[xx] == 0) {
                if (++luecke > Math.max(4, zh)) break;
            } else {
                luecke = 0;
                rechts = xx;
            }
        }
        int zw = rechts - links + 1;
        if (zw < 6) return null;
        boolean[][] m = new boolean[zh][zw];
        for (int yy = 0; yy < zh; yy++) System.arraycopy(schrift[oben + yy], links, m[yy], 0, zw);
        return m;
    }

    private static boolean randNah(boolean[][] dunkel, int x, int y) {
        int h = dunkel.length, w = dunkel[0].length;
        for (int dy = -RAND; dy <= RAND; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) continue;
            for (int dx = -RAND; dx <= RAND; dx++) {
                int xx = x + dx;
                if (xx >= 0 && xx < w && dunkel[yy][xx]) return true;
            }
        }
        return false;
    }

    /** Dasselbe Namensbild? Fast gleich gross und, bis 2 Bildpunkte verschoben, zu mindestens 80 % deckungsgleich. */
    public static boolean gleich(boolean[][] a, boolean[][] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        int ha = a.length, wa = a[0].length, hb = b.length, wb = b[0].length;
        if (Math.abs(ha - hb) > 2) return false;
        if (Math.abs(wa - wb) > Math.max(3, Math.max(wa, wb) / 12)) return false;
        double beste = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int schnitt = 0, vereinigt = 0;
                int hoch = Math.max(ha, hb + dy) + 2, breit = Math.max(wa, wb + dx) + 2;
                for (int y = -2; y < hoch; y++) {
                    for (int x = -2; x < breit; x++) {
                        boolean pa = punkt(a, x, y), pb = punkt(b, x - dx, y - dy);
                        if (pa && pb) schnitt++;
                        if (pa || pb) vereinigt++;
                    }
                }
                if (vereinigt > 0) beste = Math.max(beste, (double) schnitt / vereinigt);
            }
        }
        return beste >= GLEICH_AB;
    }

    private static boolean punkt(boolean[][] m, int x, int y) {
        return y >= 0 && y < m.length && x >= 0 && x < m[0].length && m[y][x];
    }

    /**
     * Autor 22.11.: das Namensbild fuer die Texterkennung -- schwarze Schrift auf Weiss, {@code faktor}-fach vergroessert,
     * mit Rand. Die Erkennung bekommt so NUR die Namenszeile: auf dem ganzen Ausschnitt las sie "MeinClan" (den Clan
     * darunter) statt "Autor" -- und dann haette jedes Konto im selben Clan als "Autor" gegolten. null = kein Bild.
     */
    public static BufferedImage zumLesen(boolean[][] m, int faktor) {
        if (m == null || m.length == 0 || m[0].length == 0 || faktor < 1) return null;
        int rand = 12, h = m.length, w = m[0].length;
        BufferedImage b = new BufferedImage(w * faktor + 2 * rand, h * faktor + 2 * rand, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) b.setRGB(x, y, 0xFFFFFF);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x]) continue;
                for (int dy = 0; dy < faktor; dy++) for (int dx = 0; dx < faktor; dx++) b.setRGB(rand + x * faktor + dx, rand + y * faktor + dy, 0);
            }
        }
        return b;
    }

    /** Als PNG speichern (weiss = Schrift). */
    public static void speichern(boolean[][] m, Path datei) throws java.io.IOException {
        BufferedImage b = new BufferedImage(m[0].length, m.length, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < m.length; y++) for (int x = 0; x < m[0].length; x++) b.setRGB(x, y, m[y][x] ? 0xFFFFFF : 0);
        if (datei.getParent() != null) Files.createDirectories(datei.getParent());
        ImageIO.write(b, "png", datei.toFile());
    }

    /** Ein gespeichertes Namensbild; null = fehlt oder unlesbar. */
    public static boolean[][] laden(Path datei) {
        try {
            if (!Files.exists(datei)) return null;
            BufferedImage b = ImageIO.read(datei.toFile());
            if (b == null) return null;
            boolean[][] m = new boolean[b.getHeight()][b.getWidth()];
            for (int y = 0; y < m.length; y++) for (int x = 0; x < m[0].length; x++) m[y][x] = (b.getRGB(x, y) & 0xff) > 127;
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
