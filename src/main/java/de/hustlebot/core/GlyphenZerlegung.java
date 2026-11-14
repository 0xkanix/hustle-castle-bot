package de.hustlebot.core;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Zerlegt ein Namensbild der Arena in einzelne Zeichen -- reine Bildarbeit, ohne Windows-Texterkennung
 * (Autor 24.10.: Namen in Schmuckschrift, mit Sonderzeichen oder Umlauten liest Windows jedes Mal anders,
 * z. B. "OMYth Gods" als "-UMPM-", "Gods", "Myth 5Qds" -- die Sperrliste erkannte ihn deshalb nie wieder).
 *
 * So geht es:
 *  1. Schrift freistellen: Die Namen sind weiss mit dunklem Rand auf grauem Grund (gemessen: Grund 206, Schrift
 *     255). Weiss heisst: alle drei Farben hell und fast gleich. Der Grund wird je Bild gemessen (haeufigste
 *     Helligkeit), die Schwelle liegt in der Mitte zwischen Grund und Weiss -- wie Texterkennung.weisseSchrift(),
 *     nur selbst geeicht.
 *  2. Zusammenhaengende weisse Flecken suchen. Weil jeder Buchstabe seinen dunklen Rand hat, beruehren sich
 *     zwei Buchstaben fast nie. Flecken genau uebereinander (i-Punkt, Akzent, Ausrufezeichen) gehoeren zusammen.
 *     Satzzeichen (- . ! * # ~ _) bleiben eigene Zeichen -- sie gehoeren zum Fingerabdruck.
 *  3. Was zu breit fuer ein Zeichen ist (zusammenhaengende Buchstaben), wird an der duennsten Spalte geteilt.
 *  4. Jedes Zeichen kommt in ein festes Raster, mit seinem Schwerpunkt genau in der Mitte (auf Bruchteile eines
 *     Bildpunkts). Die Hoehenlage zur Grundlinie wird extra gemerkt ("lage"), damit Bindestrich und Unterstrich
 *     verschieden bleiben.
 *  5. Das Raster gibt es in drei Groessen (93 %, 100 %, 107 %): Das Spiel zeichnet die Namen je nach Reihe der
 *     Pyramide verschieden gross (gemessen 24.10. an 49 Arena-Fotos: untere Reihe Grossbuchstaben 12 Punkte hoch,
 *     obere 13-14). Verglichen wird mit der passendsten Groesse.
 *
 * Gemessen 24.10. an 300 Namensausschnitten aus Arena-Fotos (Spieler, die mehrmals vorkamen): Derselbe Buchstabe
 * zweier Aufnahmen hat Abstand 0,07 (Mitte) bis 0,13 (95 von 100); von verschiedenen Kleinbuchstaben lagen nur
 * 0,2 % so nah.
 */
public final class GlyphenZerlegung {

    /** Rasterbreite und -hoehe in Bildpunkten (Spielgroesse); der Schwerpunkt liegt in der Mitte. */
    public static final int RB = 28, RH = 30;
    /** Groessen, in denen jedes Zeichen verglichen wird (Reihen der Arena-Pyramide sind verschieden gross). */
    static final double[] SKALEN = {0.93, 1.0, 1.07};

    /** Breiter als das ist kein einzelnes Zeichen mehr -- dann an der duennsten Spalte teilen. */
    static final int MAX_BREITE = 18;
    /** Kleinste Teilbreite beim Teilen. */
    static final int MIN_TEIL = 4;
    /** Ab dieser Luecke (Spalten ohne Schrift) steht ein Leerzeichen zwischen zwei Zeichen (gemessen: 0-4 sonst). */
    static final int WORT_LUECKE = 6;
    /** Kleinere Flecken sind Krumen, kein Zeichen. */
    static final int MIN_PUNKTE = 3;
    /** Ein Fleck am Bildrand, so weit weg vom Rest, ist kein Buchstabe (Rest vom Wappen). */
    static final int RAND_LUECKE = 8;

    private GlyphenZerlegung() {
    }

    /**
     * Ein Zeichen aus dem Bild. x0..x1 / y0..y1 = Lage im Bild; raster = Helligkeit 0..1 im festen Raster (RB x RH,
     * Groesse 100 %), tinte = Summe davon; lage = Hoehe des Schwerpunkts ueber (minus) bzw. unter der Grundlinie;
     * varianten/tinten = das Raster in den Groessen SKALEN; leerDavor = vor diesem Zeichen steht ein Leerzeichen.
     */
    public record Stueck(int x0, int x1, int y0, int y1, float[] raster, double tinte, boolean leerDavor, double lage,
                         float[][] varianten, double[] tinten) {
        public int breite() { return x1 - x0 + 1; }
        public int hoehe() { return y1 - y0 + 1; }
    }

    /** Ergebnis der Zerlegung: die Zeichen von links nach rechts, die Grundlinie und der Kasten um die Schrift. */
    public record Zerlegung(List<Stueck> stuecke, int grundlinie, Rectangle kasten) {
        public int anzahl() { return stuecke.size(); }
    }

    /** Zerlegt ein Namensbild. Nie null; ohne Schrift ist die Liste leer. */
    public static Zerlegung zerlegen(BufferedImage bild) {
        if (bild == null || bild.getWidth() < 2 || bild.getHeight() < 2) return new Zerlegung(List.of(), 0, null);
        int w = bild.getWidth(), h = bild.getHeight();
        float[][] e = ebenen(bild);
        float[] v = e[0], weiss = e[1];

        // 2. Flecken (8er-Nachbarschaft)
        int[] marke = new int[w * h];
        Arrays.fill(marke, -1);
        List<int[]> flecken = new ArrayList<>();   // {x0, x1, y0, y1, punkte}
        int[] stapel = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (v[start] < 0.5f || marke[start] >= 0) continue;
            int nr = flecken.size();
            int[] f = {w, -1, h, -1, 0};
            int sp = 0;
            stapel[sp++] = start;
            marke[start] = nr;
            while (sp > 0) {
                int p = stapel[--sp], px = p % w, py = p / w;
                f[0] = Math.min(f[0], px); f[1] = Math.max(f[1], px);
                f[2] = Math.min(f[2], py); f[3] = Math.max(f[3], py);
                f[4]++;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        if (nx < 0 || nx >= w || (dx == 0 && dy == 0)) continue;
                        int q = ny * w + nx;
                        if (v[q] >= 0.5f && marke[q] < 0) { marke[q] = nr; stapel[sp++] = q; }
                    }
                }
            }
            flecken.add(f);
        }

        // Krumen weg; Gruppen = Flecken, die waagerecht ueberwiegend uebereinander liegen
        List<Gruppe> gruppen = new ArrayList<>();
        for (int i = 0; i < flecken.size(); i++) {
            int[] f = flecken.get(i);
            if (f[4] < MIN_PUNKTE) continue;
            Gruppe g = new Gruppe(f[0], f[1], f[2], f[3]);
            g.marken.add(i);
            gruppen.add(g);
        }
        boolean geaendert = true;
        while (geaendert) {
            geaendert = false;
            gruppen.sort((a, b) -> Integer.compare(a.x0, b.x0));
            for (int i = 0; i + 1 < gruppen.size() && !geaendert; i++) {
                for (int j = i + 1; j < gruppen.size(); j++) {
                    Gruppe a = gruppen.get(i), b = gruppen.get(j);
                    if (b.x0 > a.x1) break;
                    int ueber = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0) + 1;
                    int schmal = Math.min(a.x1 - a.x0 + 1, b.x1 - b.x0 + 1);
                    if (ueber * 2 >= schmal) {
                        a.x0 = Math.min(a.x0, b.x0); a.x1 = Math.max(a.x1, b.x1);
                        a.y0 = Math.min(a.y0, b.y0); a.y1 = Math.max(a.y1, b.y1);
                        a.marken.addAll(b.marken);
                        gruppen.remove(j);
                        geaendert = true;
                        break;
                    }
                }
            }
        }

        // 3. zu breite Stuecke an der duennsten Spalte teilen
        List<Gruppe> fertig = new ArrayList<>();
        for (Gruppe g : gruppen) teilen(g, v, marke, w, fertig);
        fertig.sort((a, b) -> Integer.compare(a.x0, b.x0));
        // Reste vom Wappen am Bildrand (gemessen 24.10.: weisse Wappenteile links im Ausschnitt, weit weg vom Namen)
        if (fertig.size() > 1 && fertig.get(0).x0 == 0 && fertig.get(1).x0 - fertig.get(0).x1 - 1 >= RAND_LUECKE) fertig.remove(0);
        int n = fertig.size();
        if (n > 1 && fertig.get(n - 1).x1 == w - 1 && fertig.get(n - 1).x0 - fertig.get(n - 2).x1 - 1 >= RAND_LUECKE) fertig.remove(n - 1);
        if (fertig.isEmpty()) return new Zerlegung(List.of(), 0, null);

        // Grundlinie: die haeufigste Unterkante der richtigen Buchstaben (mind. 5 Punkte hoch)
        int[] unten = new int[h];
        for (Gruppe g : fertig) if (g.y1 - g.y0 + 1 >= 5) unten[g.y1] += 1;
        int grund = -1;
        for (int y = 0; y < h; y++) if (unten[y] > 0 && (grund < 0 || unten[y] >= unten[grund])) grund = y;
        if (grund < 0) for (Gruppe g : fertig) grund = Math.max(grund, g.y1);

        // 4./5. Raster je Zeichen
        List<Stueck> stuecke = new ArrayList<>();
        int kx0 = w, kx1 = 0, ky0 = h, ky1 = 0, vorher = Integer.MIN_VALUE;
        for (Gruppe g : fertig) {
            // Helligkeit des Zeichens: eigene Flecken plus weiche Kantenpunkte (ohne Marke) rundherum, fremde nie
            int bx0 = Math.max(0, g.x0 - 1), bx1 = Math.min(w - 1, g.x1 + 1), by0 = Math.max(0, g.y0 - 1), by1 = Math.min(h - 1, g.y1 + 1);
            int lw = bx1 - bx0 + 1, lh = by1 - by0 + 1;
            float[] lokal = new float[lw * lh];
            double sw = 0, sx = 0, sy = 0;
            for (int y = by0; y <= by1; y++)
                for (int x = bx0; x <= bx1; x++) {
                    int p = y * w + x;
                    boolean innen = x >= g.x0 && x <= g.x1;
                    if (marke[p] >= 0 && (!innen || !g.marken.contains(marke[p]))) continue;
                    float wv = weiss[p];
                    lokal[(y - by0) * lw + x - bx0] = wv;
                    sw += wv; sx += wv * x; sy += wv * y;
                }
            double cx = sw > 0 ? sx / sw : (g.x0 + g.x1) / 2.0, cy = sw > 0 ? sy / sw : (g.y0 + g.y1) / 2.0;
            float[][] varianten = new float[SKALEN.length][];
            double[] tinten = new double[SKALEN.length];
            float[] raster = null;
            double tinte = 0;
            for (int s = 0; s < SKALEN.length; s++) {
                float[] r = new float[RB * RH];
                for (int ry = 0; ry < RH; ry++)
                    for (int rx = 0; rx < RB; rx++)
                        r[ry * RB + rx] = bilinear(lokal, lw, lh, cx + (rx - RB / 2.0) / SKALEN[s] - bx0,
                                cy + (ry - RH / 2.0) / SKALEN[s] - by0);
                r = weichzeichnen(r);
                varianten[s] = r;
                for (float f : r) tinten[s] += f;
                if (SKALEN[s] == 1.0) { raster = r; tinte = tinten[s]; }
            }
            boolean leer = vorher != Integer.MIN_VALUE && g.x0 - vorher - 1 >= WORT_LUECKE;
            stuecke.add(new Stueck(g.x0, g.x1, g.y0, g.y1, raster, tinte, leer, cy - grund, varianten, tinten));
            vorher = g.x1;
            kx0 = Math.min(kx0, g.x0); kx1 = Math.max(kx1, g.x1);
            ky0 = Math.min(ky0, g.y0); ky1 = Math.max(ky1, g.y1);
        }
        return new Zerlegung(List.copyOf(stuecke), grund, new Rectangle(kx0, ky0, kx1 - kx0 + 1, ky1 - ky0 + 1));
    }

    /**
     * Abstand zweier Zeichen-Raster: 0 = gleich, 1 = nichts gemeinsam (Unterschied geteilt durch die gesamte
     * Tinte). Rechnet nur so weit, bis klar ist, dass er ueber "grenze" liegt -- dann kommt ein Wert >= grenze
     * zurueck. Das spart beim Durchsuchen der Bibliothek den groessten Teil der Rechnung.
     */
    public static double abstand(float[] a, float[] b, double tinteA, double tinteB, double grenze) {
        double summe = tinteA + tinteB;
        if (summe <= 0) return 0;
        double d = 0, schluss = grenze * summe;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            d += Math.abs(a[i] - b[i]);
            if ((i % RB) == RB - 1 && d >= schluss) return grenze;   // schlechter als die Grenze -- abbrechen
        }
        return d / summe;
    }

    /** Abstand ohne Grenze. */
    public static double abstand(float[] a, float[] b, double tinteA, double tinteB) {
        return abstand(a, b, tinteA, tinteB, Double.MAX_VALUE);
    }

    /** Ein Raster um seine Mitte (= Schwerpunkt) vergroessert/verkleinert -- fuer den Vergleich zweier Glyphen. */
    public static float[] skaliert(float[] raster, double skala) {
        if (skala == 1.0) return raster;
        float[] r = new float[RB * RH];
        for (int ry = 0; ry < RH; ry++)
            for (int rx = 0; rx < RB; rx++)
                r[ry * RB + rx] = bilinear(raster, RB, RH, RB / 2.0 + (rx - RB / 2.0) / skala, RH / 2.0 + (ry - RH / 2.0) / skala);
        return r;
    }

    /**
     * Ebenen des Bildes: [0] = Schrift ja/nein fuer das Zerlegen (weich um die Schwelle mitten zwischen Grund und
     * Weiss, nie unter 200), [1] = Helligkeit ueber dem Grund (0 = Grund oder dunkler, 1 = reines Weiss) fuer
     * das Raster -- so behalten Kantenpunkte ihren Anteil.
     */
    static float[][] ebenen(BufferedImage bild) {
        int w = bild.getWidth(), h = bild.getHeight();
        int[] px = bild.getRGB(0, 0, w, h, null, 0, w);
        int[] hist = new int[256];
        for (int p : px) {
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int mn = Math.min(r, Math.min(g, b));
            if (mn < 235) hist[mn]++;
        }
        int grund = 206;
        for (int i = 0, best = -1; i < 235; i++) if (hist[i] > best) { best = hist[i]; grund = i; }
        double schwelle = Math.max(200, grund + (255 - grund) * 0.5);
        double unten = schwelle - 15, oben = schwelle + 5, wu = Math.min(grund + 4, 240);
        float[] v = new float[w * h], weiss = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
            if (mx - mn > 60) continue;   // farbig -- keine weisse Schrift
            v[i] = (float) Math.max(0, Math.min(1, (mn - unten) / (oben - unten)));
            weiss[i] = (float) Math.max(0, Math.min(1, (mn - wu) / (255 - wu)));
        }
        // Weisse Linien quer durchs Bild (Rand der Namenszeile, gemessen 24.10. in "rew"/"NIKA": oberste Zeile ganz
        // weiss) wuerden alle Buchstaben zu einem Fleck verbinden -- solche Zeilen gehoeren nicht zur Schrift.
        for (int y = 0; y < h && w > 20; y++) {
            int anzahl = 0;
            for (int x = 0; x < w; x++) if (v[y * w + x] >= 0.5f) anzahl++;
            if (anzahl * 10 > w * 7) {
                Arrays.fill(v, y * w, (y + 1) * w, 0f);
                Arrays.fill(weiss, y * w, (y + 1) * w, 0f);
            }
        }
        return new float[][] {v, weiss};
    }

    /**
     * Leicht weichzeichnen (1-2-1 in beide Richtungen): Dasselbe Zeichen faellt von Bild zu Bild um einen
     * Bildpunkt dicker oder duenner aus; weich verglichen zaehlt das weniger (gemessen 24.10.: ohne Weichzeichnen
     * lagen von verschiedenen Buchstaben dreimal so viele unter der Schwelle).
     */
    static float[] weichzeichnen(float[] a) {
        float[] waag = new float[a.length], aus = new float[a.length];
        for (int y = 0; y < RH; y++)
            for (int x = 0; x < RB; x++) {
                float s = 2 * a[y * RB + x];
                if (x > 0) s += a[y * RB + x - 1];
                if (x + 1 < RB) s += a[y * RB + x + 1];
                waag[y * RB + x] = s / 4f;
            }
        for (int y = 0; y < RH; y++)
            for (int x = 0; x < RB; x++) {
                float s = 2 * waag[y * RB + x];
                if (y > 0) s += waag[(y - 1) * RB + x];
                if (y + 1 < RH) s += waag[(y + 1) * RB + x];
                aus[y * RB + x] = s / 4f;
            }
        return aus;
    }

    /** Wert zwischen den Bildpunkten (bilinear); ausserhalb 0. */
    private static float bilinear(float[] a, int w, int h, double x, double y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        return (float) ((1 - fx) * (1 - fy) * wert(a, w, h, x0, y0) + fx * (1 - fy) * wert(a, w, h, x0 + 1, y0)
                + (1 - fx) * fy * wert(a, w, h, x0, y0 + 1) + fx * fy * wert(a, w, h, x0 + 1, y0 + 1));
    }

    private static float wert(float[] a, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0f : a[y * w + x];
    }

    /** Teilt eine zu breite Gruppe an der duennsten Spalte (rekursiv) und legt die Teile in "aus". */
    private static void teilen(Gruppe g, float[] v, int[] marke, int w, List<Gruppe> aus) {
        int breite = g.x1 - g.x0 + 1;
        if (breite <= MAX_BREITE || breite < 2 * MIN_TEIL + 1) {
            aus.add(g.engZuschneiden(v, marke, w));
            return;
        }
        int bestX = -1;
        double bestTinte = Double.MAX_VALUE, mitte = (g.x0 + g.x1) / 2.0;
        for (int x = g.x0 + MIN_TEIL; x <= g.x1 - MIN_TEIL; x++) {
            double t = 0;
            for (int y = g.y0; y <= g.y1; y++) {
                int p = y * w + x;
                if (marke[p] >= 0 && g.marken.contains(marke[p])) t += v[p];
            }
            if (t < bestTinte - 1e-6 || (Math.abs(t - bestTinte) <= 1e-6 && Math.abs(x - mitte) < Math.abs(bestX - mitte))) {
                bestTinte = t;
                bestX = x;
            }
        }
        Gruppe links = new Gruppe(g.x0, bestX - 1, g.y0, g.y1), rechts = new Gruppe(bestX, g.x1, g.y0, g.y1);
        links.marken.addAll(g.marken);
        rechts.marken.addAll(g.marken);
        teilen(links, v, marke, w, aus);
        teilen(rechts, v, marke, w, aus);
    }

    /** Arbeitsstand eines Zeichens beim Zerlegen. */
    private static final class Gruppe {
        int x0, x1, y0, y1;
        final Set<Integer> marken = new HashSet<>();

        Gruppe(int x0, int x1, int y0, int y1) {
            this.x0 = x0; this.x1 = x1; this.y0 = y0; this.y1 = y1;
        }

        /** Kasten auf die tatsaechlich gesetzten Punkte verkleinern (nach dem Teilen). */
        Gruppe engZuschneiden(float[] v, int[] marke, int w) {
            int nx0 = Integer.MAX_VALUE, nx1 = -1, ny0 = Integer.MAX_VALUE, ny1 = -1;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    int p = y * w + x;
                    if (v[p] >= 0.5f && marke[p] >= 0 && marken.contains(marke[p])) {
                        nx0 = Math.min(nx0, x); nx1 = Math.max(nx1, x);
                        ny0 = Math.min(ny0, y); ny1 = Math.max(ny1, y);
                    }
                }
            if (nx1 >= 0) { x0 = nx0; x1 = nx1; y0 = ny0; y1 = ny1; }
            return this;
        }
    }
}
