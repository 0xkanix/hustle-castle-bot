package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Der Name eines Arena-Gegners als winziges Bild -- Autor 21.11.: "den Namen als Bildausschnitt speichern und immer
 * mit dem alten Bild vergleichen -- Schrift egal".
 *
 * Anlass: "~ЦИРИ~" (Myth Gods) las die Texterkennung als "-UMPM-", "-ЦИРИ-", "V Myth Gods", "RA~ЦИРИ~" -- jedes Mal
 * ein anderer Eintrag im Notizblock. Auch der Glyphen-Fingerabdruck (Glyphenschrift) kippt, sobald ein Zeichen anders
 * zerlegt oder ein Stueck Nachbartext mitgeschnitten wird. Das Schriftbild vergleicht dagegen das GANZE Namensbild.
 *
 * So geht es:
 *  1. Nur die Schrift bleibt, der Hintergrund faellt weg. Schild und Kampfprotokoll: weisse Schrift (wie
 *     GlyphenZerlegung.ebenen); Vergleichsfenster: schwarze Schrift mit weissem Rand auf Rosa.
 *  2. Nur die Namenszeile zaehlt: die Zeile mit der meisten Schrift, die nicht am oberen/unteren Rand abgeschnitten ist
 *     (Reste einer Nachbarzeile); Flecken weit abseits (Wappen am Rand) fliegen raus.
 *  3. Der Kasten um die Schrift wird auf feste HOEHE gebracht (Breite im selben Verhaeltnis) -- so passen Schilde
 *     verschiedener Pyramidenreihen (93-107 % Groesse) aufeinander.
 *  4. Gespeichert als 1-Bit-Raster, gepackt (Deflate) und als Text (Base64) -- steht direkt in arena-daten.txt.
 *     Gemessen 21.11. an 1002 echten Namensbildern: im Mittel 114 Zeichen (gut 80 Byte), hoechstens 287.
 *  5. vergleichen(): Anteil der Schriftpunkte ohne Partner im anderen Bild (Breite angeglichen, um einen Punkt
 *     verschiebbar) -- im Ganzen und im schlechtesten Fenster von einer Zeilenhoehe. urteil() macht daraus SICHER,
 *     AEHNLICH (nur mit Gegenprobe glauben) oder NEIN. Rund 20 Mikrosekunden je Vergleich.
 *
 * Je Bildschirm-Art (Schild, Fenster, Kampfprotokoll) eigenes Bild: die Art steht als erstes Zeichen davor.
 */
public final class Schriftbild {

    /** Hoehe des normierten Bildes in Punkten. */
    public static final int HOEHE = 12;

    /** Bildschirm-Arten: Schild der Pyramide, Vergleichsfenster, Kampfprotokoll. */
    public static final char SCHILD = 'S', FENSTER = 'F', PROTOKOLL = 'P';

    /** Breiter wird nicht gespeichert (ein Byte); so lange Namen gibt es nicht. */
    static final int MAX_BREITE = 255;

    /** Weniger Schriftpunkte: kein brauchbarer Name (ein Strich, ein Krumen). */
    static final int MIN_TINTE = 25;

    /** Kleinere Flecken sind Krumen. */
    private static final int MIN_FLECK = 3;

    /** Weicht die Breite (bei gleicher Hoehe) um mehr als diesen Faktor ab, ist es nie derselbe Name. */
    static final double MAX_BREITEN_VERHAELTNIS = 1.22;

    /** So weit ueber/unter der Zeile (Anteil der Zeilenhoehe, +1 Punkt) zaehlen kleine Flecken noch dazu: Punkte, Haekchen, Akzente. */
    static final double RAND_OBEN = 0.45, RAND_UNTEN = 0.25;

    /** Vergleichsarten (Messung): 0 = genau, 1 = Kreuz-Nachbarn, 2 = 3x3, 3 = gewichtet (Nachbar halb). */
    static final int GENAU = 0, KREUZ = 1, NEUN = 2, GEWICHTET = 3;
    static int vergleich = GEWICHTET;

    /**
     * Schwellen, gemessen 21.11. (SchriftbildMessung) an 995 echten Namensbildern: Listenbilder, glyphen\namen und
     * logs\schmuckschrift, 399 Paare desselben Namens, 72.774 Paare klar verschiedener Namen. Verschiedene Namen kamen
     * nie unter SICHER (die drei Treffer dort waren alle derselbe Spieler unter zwei Lesungen: "UMPM"/"ЦИРИ",
     * "VIPFR"/"Raphy 2.0", "gegner"/"Сволочь"). Am naechsten: "Revan"/"Raven" 0,082/0,161 und "Том"/"Тан" 0,111/0,163 --
     * gleich nah wie zwei Aufnahmen von "~ЦИРИ~" (0,093/0,170). Darum zaehlt AEHNLICH nur mit Gegenprobe (Kraft, Clan).
     */
    static final double SICHER_GESAMT = 0.06, SICHER_OERTLICH = 0.12;
    static final double AEHNLICH_GESAMT = 0.12, AEHNLICH_OERTLICH = 0.25;

    /** Wie sicher zwei Schriftbilder derselbe Name sind. */
    public enum Urteil { SICHER, AEHNLICH, NEIN }

    /** SICHER = derselbe Name; AEHNLICH = vielleicht (nur mit Gegenprobe glauben); NEIN. Andere Bildschirm-Art: NEIN. */
    public Urteil urteil(Schriftbild o) {
        if (o == null || o.art != art) return Urteil.NEIN;
        return urteil(vergleichen(o));
    }

    /** Das Urteil zu einem Ergebnis von vergleichen() ({gesamt, oertlich}). */
    static Urteil urteil(double[] v) {
        if (v[0] <= SICHER_GESAMT && v[1] <= SICHER_OERTLICH) return Urteil.SICHER;
        if (v[0] <= AEHNLICH_GESAMT && v[1] <= AEHNLICH_OERTLICH) return Urteil.AEHNLICH;
        return Urteil.NEIN;
    }

    private final char art;
    private final int breite, hoehe;
    private final boolean[] punkt;   // hoehe x breite, zeilenweise
    private final int tinte;

    private Schriftbild(char art, int breite, int hoehe, boolean[] punkt) {
        this.art = art;
        this.breite = breite;
        this.hoehe = hoehe;
        this.punkt = punkt;
        int t = 0;
        for (boolean b : punkt) if (b) t++;
        this.tinte = t;
    }

    public char art() { return art; }
    public int breite() { return breite; }
    public int hoehe() { return hoehe; }
    public int tinte() { return tinte; }

    // ------------------------------------------------------------------------------------------------------------
    //  Aus einem Bildausschnitt
    // ------------------------------------------------------------------------------------------------------------

    /** Das Schriftbild eines Namensausschnitts, oder null, wenn darin kein Name zu sehen ist. */
    public static Schriftbild aus(BufferedImage bild, char art) {
        return aus(bild, art, HOEHE);
    }

    static Schriftbild aus(BufferedImage bild, char art, int zielHoehe) {
        if (bild == null || bild.getWidth() < 4 || bild.getHeight() < 6) return null;
        int w = bild.getWidth(), h = bild.getHeight();
        float[] v = art == FENSTER ? dunkleSchrift(bild) : helleSchrift(bild);
        // Linien quer durchs Bild (Rand der Namenszeile) verbinden sonst alle Buchstaben zu einem Fleck: ein
        // ununterbrochener Strich ueber 40 % der Breite ist kein Buchstabe -- nur der Strich faellt weg, nicht die Zeile
        // (sonst verschwindet z. B. das Haekchen auf dem "й").
        int strich = (int) Math.max(20, w * 0.4);
        for (int y = 0; y < h; y++) {
            int lauf = 0;
            for (int x = 0; x <= w; x++) {
                if (x < w && v[y * w + x] >= 0.5f) { lauf++; continue; }
                if (lauf >= strich) Arrays.fill(v, y * w + x - lauf, y * w + x, 0f);
                lauf = 0;
            }
        }

        // Flecken (8er-Nachbarschaft): {x0, x1, y0, y1, punkte}
        int[] marke = new int[w * h];
        Arrays.fill(marke, -1);
        List<int[]> flecken = new ArrayList<>();
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
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (v[q] >= 0.5f && marke[q] < 0) { marke[q] = nr; stapel[sp++] = q; }
                    }
                }
            }
            flecken.add(f);
        }
        int[] band = new int[2];
        boolean[] behalten = zeileWaehlen(flecken, h, band);
        if (behalten == null) return null;

        // Kasten um die behaltenen Flecken (Zeile samt Punkten und Haekchen). Gemessen 21.11.: ein fester Rand ueber der
        // Zeile statt des Kastens machte die Buchstaben bei Hoehe 12 nur 6-7 Punkte hoch -- zu grob ("~ЦИРИ~" aus Liste
        // und Schild war dann "verschieden"). Darum Kasten, aber Haekchen grosszuegig dazunehmen (siehe zeileWaehlen).
        int x0 = w, x1 = -1, y0 = h, y1 = -1;
        for (int i = 0; i < flecken.size(); i++) {
            if (!behalten[i]) continue;
            int[] f = flecken.get(i);
            x0 = Math.min(x0, f[0]); x1 = Math.max(x1, f[1]);
            y0 = Math.min(y0, f[2]); y1 = Math.max(y1, f[3]);
        }
        if (x1 < x0 || band[1] - band[0] + 1 < 5) return null;
        int kw = x1 - x0 + 1, kh = y1 - y0 + 1;

        // Auf zielHoehe bringen: Flaechenmittel der Schrift (nur behaltene Flecken), dann Schwelle 0,5
        double s = (double) zielHoehe / kh;
        int nb = (int) Math.max(1, Math.min(MAX_BREITE, Math.round(kw * s)));
        double sx = (double) kw / nb, sy = (double) kh / zielHoehe;
        boolean[] aus = new boolean[zielHoehe * nb];
        for (int ty = 0; ty < zielHoehe; ty++) {
            double qy0 = y0 + ty * sy, qy1 = qy0 + sy;
            for (int tx = 0; tx < nb; tx++) {
                double qx0 = x0 + tx * sx, qx1 = qx0 + sx;
                double summe = 0, flaeche = 0;
                for (int yy = (int) Math.floor(qy0); yy < Math.ceil(qy1); yy++) {
                    double fy = Math.min(qy1, yy + 1) - Math.max(qy0, yy);
                    if (fy <= 0) continue;
                    for (int xx = (int) Math.floor(qx0); xx < Math.ceil(qx1) && xx < w; xx++) {
                        double fx = Math.min(qx1, xx + 1) - Math.max(qx0, xx);
                        if (fx <= 0) continue;
                        int p = yy * w + xx;
                        double wert = yy >= 0 && yy < h && marke[p] >= 0 && behalten[marke[p]] ? v[p] : 0;   // ausserhalb: leer
                        summe += wert * fx * fy;
                        flaeche += fx * fy;
                    }
                }
                aus[ty * nb + tx] = flaeche > 0 && summe / flaeche >= 0.5;
            }
        }
        Schriftbild sb = new Schriftbild(art, nb, zielHoehe, aus);
        return sb.tinte < MIN_TINTE ? null : sb;
    }

    /**
     * Schild und Kampfprotokoll: weisse Schrift mit dunklem Rand. Wie GlyphenZerlegung.ebenen, aber der Grund kommt vom
     * Bildrand (dort ist fast nur Hintergrund): bei eng geschnittenen Namen auf hellem Grund war sonst der dunkle Rand
     * der Buchstaben "der haeufigste Wert", die Schwelle fiel auf 200 und der hellgraue Grund (200-225) wurde Schrift
     * (gemessen 21.11. an glyphen\namen\2a8cb8d0.png, "никнейм").
     */
    static float[] helleSchrift(BufferedImage bild) {
        int w = bild.getWidth(), h = bild.getHeight();
        int[] px = bild.getRGB(0, 0, w, h, null, 0, w);
        int[] hist = new int[256], randHist = new int[256];
        int rand = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int mn = Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255));
            if (mn < 235) hist[mn]++;
            int x = i % w, y = i / w;
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { randHist[mn]++; rand++; }
        }
        int grund = 206;
        for (int i = 0, best = -1; i < 235; i++) if (hist[i] > best) { best = hist[i]; grund = i; }
        int median = 0;
        for (int i = 0, n = 0; i < 256; i++) { n += randHist[i]; if (n * 2 >= rand) { median = i; break; } }
        if (median > grund && median < 240) grund = median;   // heller Grund am Rand: der gilt
        double schwelle = Math.max(200, grund + (255 - grund) * 0.5);
        double unten = schwelle - 15, oben = schwelle + 5;
        float[] v = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
            if (mx - mn > 60) continue;   // farbig -- keine weisse Schrift
            v[i] = (float) Math.max(0, Math.min(1, (mn - unten) / (oben - unten)));
        }
        return v;
    }

    /** Vergleichsfenster: schwarze Schrift (weiss umrandet) auf Rosa -- Anteil "dunkel und grau" je Punkt. */
    static float[] dunkleSchrift(BufferedImage bild) {
        int w = bild.getWidth(), h = bild.getHeight();
        int[] px = bild.getRGB(0, 0, w, h, null, 0, w);
        float[] v = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
            if (mx - mn > 50) continue;   // farbig (Wappen, Abzeichen)
            v[i] = (float) Math.max(0, Math.min(1, (120 - mx) / 40.0));
        }
        return v;
    }

    /**
     * Welche Flecken gehoeren zur Namenszeile? Grosse Flecken (mindestens 45 % so hoch wie der hoechste) bilden Zeilen;
     * es gilt die Zeile mit der meisten Schrift -- eine Zeile, die oben oder unten am Bildrand klebt (abgeschnittener
     * Nachbartext), nur wenn es keine andere mit wenigstens einem Fuenftel so viel Schrift gibt. Dazu kommen kleine
     * Flecken direkt darueber/darunter (i-Punkt, Akzent). Was waagerecht weit abseits liegt (mehr als eine Zeilenhoehe
     * Luecke), faellt weg -- Rest vom Wappen am Rand. null = keine Zeile.
     */
    private static boolean[] zeileWaehlen(List<int[]> flecken, int bildHoehe, int[] band) {
        int maxH = 0;
        for (int[] f : flecken) if (f[4] >= MIN_FLECK) maxH = Math.max(maxH, f[3] - f[2] + 1);
        if (maxH < 4) return null;
        // Zeilen aus grossen Flecken: senkrechte Bereiche zusammenlegen, die sich ueberlappen
        List<int[]> zeilen = new ArrayList<>();   // {y0, y1, tinte, amRand}
        List<int[]> gross = new ArrayList<>();
        for (int[] f : flecken) if (f[4] >= MIN_FLECK && (f[3] - f[2] + 1) * 100 >= maxH * 45) gross.add(f);
        gross.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int[] f : gross) {
            int[] letzte = zeilen.isEmpty() ? null : zeilen.get(zeilen.size() - 1);
            int ueber = letzte == null ? -1 : Math.min(letzte[1], f[3]) - Math.max(letzte[0], f[2]) + 1;
            if (letzte != null && ueber * 2 >= Math.min(f[3] - f[2] + 1, letzte[1] - letzte[0] + 1)) {
                letzte[0] = Math.min(letzte[0], f[2]);
                letzte[1] = Math.max(letzte[1], f[3]);
                letzte[2] += f[4];
            } else {
                zeilen.add(new int[]{f[2], f[3], f[4], 0});
            }
        }
        for (int[] z : zeilen) z[3] = z[0] == 0 || z[1] >= bildHoehe - 1 ? 1 : 0;
        int[] zeile = null;
        for (int[] z : zeilen) if (z[3] == 0 && (zeile == null || z[2] > zeile[2])) zeile = z;
        for (int[] z : zeilen) {
            if (z[3] == 1 && (zeile == null || zeile[2] * 5 < z[2])) zeile = z;
        }
        if (zeile == null) return null;
        band[0] = zeile[0];
        band[1] = zeile[1];
        int lh = zeile[1] - zeile[0] + 1, oben = (int) Math.round(lh * RAND_OBEN) + 1, unten = (int) Math.round(lh * RAND_UNTEN) + 1;
        boolean[] drin = new boolean[flecken.size()];
        for (int i = 0; i < flecken.size(); i++) {
            int[] f = flecken.get(i);
            if (f[4] < MIN_FLECK) continue;
            int fh = f[3] - f[2] + 1;
            int ueber = Math.min(zeile[1], f[3]) - Math.max(zeile[0], f[2]) + 1;
            double mitte = (f[2] + f[3]) / 2.0;
            boolean inZeile = ueber * 2 >= fh;
            boolean zeichenDazu = fh * 2 <= maxH && mitte >= zeile[0] - oben && mitte <= zeile[1] + unten;   // Punkt, Akzent
            drin[i] = inZeile || zeichenDazu;
        }
        // Waagerecht: Gruppen mit grosser Luecke trennen, die Gruppe mit der meisten Schrift bleibt
        int luecke = Math.max(8, zeile[1] - zeile[0] + 1);
        List<Integer> ordnung = new ArrayList<>();
        for (int i = 0; i < drin.length; i++) if (drin[i]) ordnung.add(i);
        if (ordnung.isEmpty()) return null;
        ordnung.sort((a, b) -> Integer.compare(flecken.get(a)[0], flecken.get(b)[0]));
        List<List<Integer>> gruppen = new ArrayList<>();
        int rechts = Integer.MIN_VALUE;
        for (int i : ordnung) {
            int[] f = flecken.get(i);
            if (gruppen.isEmpty() || f[0] - rechts > luecke) gruppen.add(new ArrayList<>());
            gruppen.get(gruppen.size() - 1).add(i);
            rechts = Math.max(rechts, f[1]);
        }
        List<Integer> beste = null;
        int besteTinte = -1;
        for (List<Integer> g : gruppen) {
            int t = 0;
            for (int i : g) t += flecken.get(i)[4];
            if (t > besteTinte) { besteTinte = t; beste = g; }
        }
        Arrays.fill(drin, false);
        for (int i : beste) drin[i] = true;
        return drin;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Vergleich
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Abstand zweier Schriftbilder: Anteil der Schriftpunkte beider Bilder ohne Partner im anderen (Breite auf das erste
     * Bild angeglichen, um einen Punkt verschiebbar). 0 = gleich, 1 = verschieden (auch bei anderer Hoehe, stark anderer
     * Breite oder Schriftmenge).
     */
    public double abstand(Schriftbild o) {
        return vergleichen(o)[0];
    }

    /**
     * {gesamt, ortlich}: gesamt wie abstand(); oertlich = der groesste Abstand in einem Fenster von einer Zeilenhoehe
     * Breite (etwa ein bis zwei Buchstaben). Ein einzelner anderer Buchstabe ("Revan"/"Raven") faellt im Ganzen kaum
     * auf, im Fenster deutlich; gleichmaessiges Rauschen zweier Aufnahmen desselben Namens bleibt in jedem Fenster klein.
     */
    double[] vergleichen(Schriftbild o) {
        if (o == null || o.hoehe != hoehe) return new double[]{1, 1};
        double r = (double) o.breite / breite;
        if (r > MAX_BREITEN_VERHAELTNIS || r < 1 / MAX_BREITEN_VERHAELTNIS) return new double[]{1, 1};
        double t = (double) o.tinte / tinte;
        if (t > 1.6 || t < 1 / 1.6) return new double[]{1, 1};
        // Schnell-Abbruch: ohne Verschiebung schon fast die Haelfte daneben -- das holt ein Punkt Verschiebung nie auf die
        // Schwellen (0,06/0,12) herunter (dasselbe Bild um einen Punkt versetzt liegt bei 0,2-0,3). Spart 8 von 9 Rechnungen.
        double ohne = (fehlend(this, o, r, 0, 0, null, null) + fehlend(o, this, 1 / r, 0, 0, null, null)) / (tinte + o.tinte);
        if (ohne > 0.45) return new double[]{Math.min(1, ohne), 1};
        double best = 2;
        int bdx = 0, bdy = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                double fehlA = fehlend(this, o, r, dx, dy, null, null);
                double fehlB = fehlend(o, this, 1 / r, -dx * r, -dy, null, null);
                double d = (fehlA + fehlB) / (tinte + o.tinte);
                if (d < best) { best = d; bdx = dx; bdy = dy; }
            }
        }
        // Spaltenweise (in Koordinaten dieses Bildes): Fehlpunkte und Schriftpunkte beider Bilder
        double[] fehl = new double[breite], tin = new double[breite];
        fehlend(this, o, r, bdx, bdy, fehl, tin);
        double[] fehlO = new double[o.breite], tinO = new double[o.breite];
        fehlend(o, this, 1 / r, -bdx * r, -bdy, fehlO, tinO);
        for (int x = 0; x < o.breite; x++) {
            int xa = (int) Math.round(x / r - bdx);
            if (xa < 0 || xa >= breite) xa = Math.max(0, Math.min(breite - 1, xa));
            fehl[xa] += fehlO[x];
            tin[xa] += tinO[x];
        }
        double lokal = 0;
        int fenster = hoehe;
        for (int x0 = 0; x0 + fenster <= Math.max(fenster, breite); x0 += 2) {
            double f = 0, ti = 0;
            for (int x = x0; x < Math.min(breite, x0 + fenster); x++) { f += fehl[x]; ti += tin[x]; }
            if (ti >= hoehe * 2) lokal = Math.max(lokal, f / ti);
        }
        return new double[]{Math.min(1, best), Math.min(1, lokal)};
    }

    /**
     * Schriftpunkte von a ohne Partner in b (a-Koordinate x -> b-Koordinate (x + dx) * r, y + dy). Ein Partner direkt
     * daneben (Kreuz) zaehlt halb -- dieselbe Schrift faellt von Aufnahme zu Aufnahme einen Punkt anders aus.
     * jeSpalte/tinteJeSpalte (oder null): die Fehlpunkte und Schriftpunkte je Spalte von a.
     */
    private static double fehlend(Schriftbild a, Schriftbild b, double r, double dx, int dy, double[] jeSpalte, double[] tinteJeSpalte) {
        double fehl = 0;
        int h = a.hoehe;
        for (int y = 0; y < h; y++) {
            int by = y + dy;
            for (int x = 0; x < a.breite; x++) {
                if (!a.punkt[y * a.breite + x]) continue;
                if (tinteJeSpalte != null) tinteJeSpalte[x]++;
                int bx = (int) Math.round((x + dx) * r);
                if (b.an(bx, by)) continue;
                boolean kreuz = b.an(bx - 1, by) || b.an(bx + 1, by) || b.an(bx, by - 1) || b.an(bx, by + 1);
                boolean eck = b.an(bx - 1, by - 1) || b.an(bx + 1, by - 1) || b.an(bx - 1, by + 1) || b.an(bx + 1, by + 1);
                double f = switch (vergleich) {
                    case GENAU -> 1;
                    case KREUZ -> kreuz ? 0 : 1;
                    case NEUN -> kreuz || eck ? 0 : 1;
                    default -> kreuz ? 0.5 : 1;
                };
                fehl += f;
                if (jeSpalte != null) jeSpalte[x] += f;
            }
        }
        return fehl;
    }

    private boolean an(int x, int y) {
        return x >= 0 && x < breite && y >= 0 && y < hoehe && punkt[y * breite + x];
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Als Text speichern
    // ------------------------------------------------------------------------------------------------------------

    /** Als kurzer Text: Art-Zeichen + Base64 (URL-Zeichen, ohne "|", Tab oder Leerzeichen) der gepackten Punkte. */
    public String text() {
        byte[] roh = new byte[2 + (punkt.length + 7) / 8];
        roh[0] = (byte) breite;
        roh[1] = (byte) hoehe;
        for (int i = 0; i < punkt.length; i++) if (punkt[i]) roh[2 + i / 8] |= (byte) (0x80 >>> (i % 8));
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(roh);
        d.finish();
        byte[] puffer = new byte[roh.length + 64];
        int n = d.deflate(puffer);
        d.end();
        return art + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(puffer, n));
    }

    /** Aus text() zurueck, oder null bei kaputtem Text. */
    public static Schriftbild lesen(String text) {
        if (text == null || text.strip().length() < 3) return null;
        text = text.strip();
        try {
            byte[] gepackt = Base64.getUrlDecoder().decode(text.substring(1));
            Inflater inf = new Inflater(true);
            inf.setInput(gepackt);
            ByteArrayOutputStream raus = new ByteArrayOutputStream();
            byte[] puffer = new byte[512];
            int grenze = 2 + (MAX_BREITE * 64 + 7) / 8;
            while (!inf.finished() && raus.size() <= grenze) {
                int n = inf.inflate(puffer);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                raus.write(puffer, 0, n);
            }
            inf.end();
            byte[] roh = raus.toByteArray();
            if (roh.length < 2) return null;
            int breite = roh[0] & 0xFF, hoehe = roh[1] & 0xFF;
            if (breite < 1 || hoehe < 4 || hoehe > 64 || roh.length < 2 + (breite * hoehe + 7) / 8) return null;
            boolean[] punkt = new boolean[breite * hoehe];
            for (int i = 0; i < punkt.length; i++) punkt[i] = (roh[2 + i / 8] & (0x80 >>> (i % 8))) != 0;
            return new Schriftbild(text.charAt(0), breite, hoehe, punkt);
        } catch (IllegalArgumentException | DataFormatException e) {
            return null;
        }
    }

    /** Als kleines Schwarz-Weiss-Bild (fuer Anzeige und Pruefung): Schrift weiss auf schwarz. */
    public BufferedImage bild() {
        BufferedImage b = new BufferedImage(breite, hoehe, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < hoehe; y++) {
            for (int x = 0; x < breite; x++) if (punkt[y * breite + x]) b.setRGB(x, y, 0xFFFFFF);
        }
        return b;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schriftbild s && s.art == art && s.breite == breite && s.hoehe == hoehe
                && Arrays.equals(s.punkt, punkt);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * art + breite) + hoehe) + Arrays.hashCode(punkt);
    }

    @Override
    public String toString() {
        return "Schriftbild[" + art + ", " + breite + "x" + hoehe + ", Tinte " + tinte + "]";
    }
}
