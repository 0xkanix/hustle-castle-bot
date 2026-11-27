package de.hustlebot.bot;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Spieler-ID im Profilfenster (Autor 21.11.: "Die echte Spieler-ID steht im Spielerprofil: in der Burg oben links auf
 * das Level-Abzeichen tippen -> links der Name, darunter 'id: ...' mit Kopier-Symbol"). Die Zahl "Your ID" auf dem
 * Ladebildschirm ist eine andere (MY.GAMES) und wird NICHT benutzt.
 *
 * WIE. Im linken Teil des Profilfensters (dunkler Grund) die weissen Flecken suchen; die ID ist die Reihe von 6-12 gleich
 * hohen, eng stehenden Zeichen. "id:" davor und das Kopier-Symbol dahinter stehen weiter weg (Luecke > halbe Zeichenhoehe)
 * und fallen so ab. Gelesen wird nur diese Ziffernreihe (Texterkennung), und die Zahl der gelesenen Ziffern muss zur Zahl
 * der Zeichen passen -- sonst lieber keine ID als eine falsche. Nichts davon ist fest eingetragen; die ID wird je Konto
 * gespeichert (core.Konten).
 */
final class SpielerProfil {

    private SpielerProfil() { }

    /** Suchbereich (Spielflaeche 1159 x 770): links im Profilfenster, um die ID-Zeile herum. */
    static final int SX = 30, SY = 135, SB = 300, SH = 65;
    /** Weiss = alle drei Farben ueber diesem Wert; dunkel = alle drei unter DUNKEL. */
    static final int WEISS = 200, DUNKEL = 70;

    /** Die Ziffernreihe der ID: {x, y, breite, hoehe, zeichen} im Bild; null = kein Profilfenster/keine ID zu sehen. */
    static int[] ziffernreihe(BufferedImage bild) {
        if (bild == null || bild.getWidth() < 200 || bild.getHeight() < 200) return null;
        double fx = bild.getWidth() / 1159.0, fy = bild.getHeight() / 770.0;
        int x0 = (int) Math.round(SX * fx), y0 = (int) Math.round(SY * fy);
        int w = Math.min(bild.getWidth() - x0, (int) Math.round(SB * fx)), h = Math.min(bild.getHeight() - y0, (int) Math.round(SH * fy));
        boolean[][] m = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bild.getRGB(x0 + x, y0 + y);
                m[y][x] = Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff)) > WEISS;
            }
        }
        List<int[]> flecken = flecken(m);
        int minHoehe = Math.max(6, (int) Math.round(8 * fy));
        List<Integer> hoehen = new ArrayList<>();
        for (int[] f : flecken) if (f[3] - f[2] + 1 >= minHoehe) hoehen.add(f[3] - f[2] + 1);
        if (hoehen.size() < 6) return null;
        hoehen.sort(null);
        int hoehe = hoehen.get(hoehen.size() / 2);
        List<int[]> zeichen = new ArrayList<>();
        for (int[] f : flecken) {
            int fh = f[3] - f[2] + 1;
            if (fh >= hoehe * 0.8 && fh <= hoehe * 1.25) zeichen.add(f);
        }
        zeichen.sort(java.util.Comparator.comparingInt(f -> f[0]));
        // Reihen: eng stehende Zeichen auf einer Hoehe
        int[] beste = null;
        int i = 0;
        while (i < zeichen.size()) {
            int j = i + 1;
            while (j < zeichen.size() && zeichen.get(j)[0] - zeichen.get(j - 1)[1] <= hoehe / 2
                    && Math.abs((zeichen.get(j)[2] + zeichen.get(j)[3]) - (zeichen.get(i)[2] + zeichen.get(i)[3])) <= hoehe) j++;
            int[] r = reihe(zeichen.subList(i, j), hoehe);
            if (r != null && (beste == null || r[4] > beste[4])) beste = r;
            i = j;
        }
        if (beste == null) return null;
        int[] r = {x0 + beste[0], y0 + beste[1], beste[2], beste[3], beste[4]};
        return dunklerGrund(bild, r) ? r : null;
    }

    /** Eine Reihe von 6-12 ziffernartigen Zeichen (schmaler als hoch, nicht winzig): {x, y, breite, hoehe, anzahl}. */
    private static int[] reihe(List<int[]> r, int hoehe) {
        if (r.size() < 6 || r.size() > 12) return null;
        int x1 = Integer.MAX_VALUE, x2 = 0, y1 = Integer.MAX_VALUE, y2 = 0;
        for (int[] f : r) {
            int fw = f[1] - f[0] + 1;
            if (fw > hoehe || fw < Math.max(2, hoehe / 5)) return null;
            x1 = Math.min(x1, f[0]); x2 = Math.max(x2, f[1]); y1 = Math.min(y1, f[2]); y2 = Math.max(y2, f[3]);
        }
        return new int[]{x1, y1, x2 - x1 + 1, y2 - y1 + 1, r.size()};
    }

    /** Das Profilfenster hat um die ID dunklen Grund (Burg-Grafik nicht) -- mindestens 40 % dunkle Punkte. */
    private static boolean dunklerGrund(BufferedImage bild, int[] r) {
        int x1 = Math.max(0, r[0] - r[3]), y1 = Math.max(0, r[1] - r[3] / 2);
        int x2 = Math.min(bild.getWidth(), r[0] + r[2] + r[3]), y2 = Math.min(bild.getHeight(), r[1] + r[3] + r[3] / 2);
        int dunkel = 0, alle = 0;
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int p = bild.getRGB(x, y);
                alle++;
                if (Math.max((p >> 16) & 0xff, Math.max((p >> 8) & 0xff, p & 0xff)) < DUNKEL) dunkel++;
            }
        }
        return alle > 0 && dunkel * 10 >= alle * 4;
    }

    /** Zusammenhaengende weisse Flecken: {x1, x2, y1, y2}. */
    static List<int[]> flecken(boolean[][] m) {
        int h = m.length, w = h == 0 ? 0 : m[0].length;
        List<int[]> alle = new ArrayList<>();
        boolean[][] gesehen = new boolean[h][w];
        int[] stapel = new int[Math.max(1, h * w)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x] || gesehen[y][x]) continue;
                int sp = 0, punkte = 0, x1 = x, x2 = x, y1 = y, y2 = y;
                gesehen[y][x] = true;
                stapel[sp++] = y * w + x;
                while (sp > 0) {
                    int p = stapel[--sp], py = p / w, px = p % w;
                    punkte++;
                    x1 = Math.min(x1, px); x2 = Math.max(x2, px); y1 = Math.min(y1, py); y2 = Math.max(y2, py);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int qy = py + dy, qx = px + dx;
                            if (qy < 0 || qy >= h || qx < 0 || qx >= w || !m[qy][qx] || gesehen[qy][qx]) continue;
                            gesehen[qy][qx] = true;
                            stapel[sp++] = qy * w + qx;
                        }
                    }
                }
                if (punkte >= 6) alle.add(new int[]{x1, x2, y1, y2});
            }
        }
        return alle;
    }

    /** Aus dem gelesenen Text nur die ID: genau so viele Ziffern wie Zeichen in der Reihe, sonst "". */
    static String idAusText(String text, int zeichen) {
        if (text == null) return "";
        StringBuilder z = new StringBuilder();
        for (char c : text.strip().toCharArray()) {
            char d = switch (c) {
                case 'O', 'o', 'D', 'Q' -> '0';
                case 'I', 'l', '|', 'i', '!' -> '1';
                case 'Z', 'z' -> '2';
                case 'S', 's' -> '5';
                case 'B' -> '8';
                case 'g' -> '9';
                default -> c;
            };
            if (Character.isDigit(d)) z.append(d);
            else if (!Character.isWhitespace(c) && c != '.' && c != ',') return "";   // fremdes Zeichen: lieber keine ID
        }
        return z.length() == zeichen ? z.toString() : "";
    }

    /** Die Spieler-ID aus dem offenen Profilfenster; "" = keine Ziffernreihe oder nicht sicher lesbar. */
    static String id(BufferedImage bild) {
        int[] r = ziffernreihe(bild);
        if (r == null) return "";
        int rand = Math.max(3, r[3] / 3);
        String text = String.join(" ", ArenaLeser.lies(weissAufSchwarzZuSchwarzAufWeiss(bild, r[0] - rand, r[1] - rand,
                r[2] + 2 * rand, r[3] + 2 * rand), 0, 0, Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4));
        return idAusText(text, r[4]);
    }

    /** Der grosse Name im Profil (ueber der ID) -- eine zweite Lesung fuer die Konto-Entscheidung; "" = keine. */
    static String name(BufferedImage bild) {
        int[] r = ziffernreihe(bild);
        if (r == null) return "";
        double fx = bild.getWidth() / 1159.0;
        int y = r[1] - (int) Math.round(r[3] * 3.6), hoehe = (int) Math.round(r[3] * 2.2);
        int x = (int) Math.round(20 * fx), breite = (int) Math.round(225 * fx);
        return ArenaLeser.liesName(bild, x, y, breite, hoehe);
    }

    /**
     * Ausschnitt dreifach vergroessert, weisse Schrift -> schwarz auf Weiss (wie ArenaLeser.weisseZahl, eigene Kopie: die
     * Vorstufe dort ist privat). Mit Rand, damit die Texterkennung die erste und letzte Ziffer nicht abschneidet.
     */
    private static BufferedImage weissAufSchwarzZuSchwarzAufWeiss(BufferedImage bild, int x, int y, int breite, int hoehe) {
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        int f = 3, rand = 12, w = (x2 - x1) * f, h = (y2 - y1) * f;
        BufferedImage gross = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1)), 0, 0, w, h, null);
        g.dispose();
        BufferedImage aus = new BufferedImage(w + 2 * rand, h + 2 * rand, BufferedImage.TYPE_INT_RGB);
        for (int yy = 0; yy < aus.getHeight(); yy++) {
            for (int xx = 0; xx < aus.getWidth(); xx++) {
                boolean schrift = false;
                if (xx >= rand && yy >= rand && xx < rand + w && yy < rand + h) {
                    int p = gross.getRGB(xx - rand, yy - rand);
                    schrift = Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff)) > WEISS;
                }
                aus.setRGB(xx, yy, schrift ? 0x000000 : 0xFFFFFF);
            }
        }
        return aus;
    }
}
