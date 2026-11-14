package de.hustlebot.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Autor 24.10.: "Die Sperrliste soll einen Spieler auch dann sicher erkennen, wenn die Texterkennung seinen Namen falsch
 * liest." Beispiel Turnier 5: "~ЦИРИ~" aus "Myth Gods" (Kraft 36.548.453, 0 Siege, 4 Niederlagen) stand in der Pyramide
 * als "-UMPM-", "Gods", "-UVIPh-" und "Myth 5Qds" da -- angegriffen, verloren.
 *
 * Darum merkt sich die Sperrliste aus dem Vergleichsfenster (vor dem Angriff, beide Teams zu sehen) auch, wie der
 * Gegner AUSSIEHT: die Portraets seiner Einheiten (Skin samt Abzeichen) und das Clan-Banner links vor dem Clan-Namen.
 * Nur Ausschneiden und Rechnen, kein Klick -- es kostet im Vergleichsfenster wenige Millisekunden.
 *
 * Gemessen an den Fotos vom 23.10. (logs/fotos-arena23, 52 Vergleichsfenster mit 11 Gegnern, 1159 x 770): derselbe
 * Spieler in einer anderen Runde hatte 6 von 6 gleichen Portraets (jedes 0,93-1,00), ein anderer Spieler hoechstens 2
 * ueber 0,85 (das viertbeste Portraet lag dann bei 0,25). Verglichen wird wie in GemBayMode.aehnlich: Graustufen,
 * das Mittelstueck des einen Bildes wird ueber das andere geschoben (Korrelation, ein paar Pixel Spiel).
 *
 * Die Lage im Fenster (gemessen bei 770 px Hoehe, wie px() in GemBayMode) haengt an den Schwertern zwischen beiden
 * Kraftzahlen (ar_power_swords, linke obere Ecke). Die Portraets stehen nicht immer an derselben Stelle: mit 5 Einheiten
 * ist die Reihe anders verschoben als mit 6 ("Koroleva", 23.10.). Darum sucht der Bot die hellblauen Ringe um die
 * Portraets: linker und rechter Ring eines Portraets liegen 86-87 px auseinander, zwei Portraets 94 px.
 *
 * Autor 24.10. auch: "die Pals bitte auch merken" -- die Pals sind im Vergleichsfenster nicht als Bild zu sehen, nur
 * der goldene Pfoten-Knopf links vom Namen. Sie anzusehen hiesse klicken und warten; das kostet in der Runde zu viel
 * Zeit. Der Knopf wird nur benutzt, um das Banner zu finden (mit Knopf steht es 70 px weiter rechts).
 */
public final class ArenaSperrBilder {

    private ArenaSperrBilder() { }

    /** Messgroesse: Hoehe des Spielfensters. */
    static final double HOEHE = 770.0;
    /** Zeilen fuer die Ring-Suche und Mitte der Portraets, unter der linken oberen Ecke der Schwerter. */
    static final int[] RING_ZEILEN = {177, 167};
    static final int MITTE_DY = 172;
    /** Suchbereich der Ringe links/rechts der Schwerter (Held links ist golden, nicht hellblau). */
    static final int SUCHE_VON = -300, SUCHE_BIS = 440;
    /** Linker zu rechtem Ring eines Portraets (Mitte zu Mitte) und Abstand zweier Portraets. */
    static final double RINGE = 86.5, PLATZ = 94;
    static final double RING_SPIEL = 5, PLATZ_SPIEL = 6;
    /** Ausschnitt je Portraet (Kante, ohne den Ring) und Ablagegroesse. */
    static final int KANTE = 76, ABLAGE = 40;
    public static final int MAX_EINHEITEN = 6;
    /**
     * Clan-Banner (16 x 16, mit 5 px Rand): gemessen bei Myth Gods, Alkatras, YASTREBY, Mistitscheskaja sila (23.10.).
     * Links vom Namen steht manchmal der Pfoten-Knopf (Pals) -- dann sitzen Stufe, Name und Banner 70 px weiter rechts.
     */
    static final int BANNER_DX_PFOTE = -145, BANNER_DX_OHNE = -215, BANNER_DY = 87, BANNER_KANTE = 26;
    /** Wo der goldene Pfoten-Knopf waere {dx, dy, Breite, Hoehe}; ohne ihn steht dort die graublaue Stufen-Burg. */
    static final int[] PFOTE = {-242, 81, 24, 20};
    /** Vergleich: Portraets in Graustufen 32 x 32 mit 3 px Spiel; Banner in Farbe, Mitte 10 x 10 mit 4 px Spiel. */
    static final int GRAU = 32, VERSATZ = 3, BANNER_RAND = 8, BANNER_SPIEL = 4;
    /**
     * Ab hier zeigt ein Portraet dieselbe Einheit. Gemessen (23.10.): derselbe Spieler 0,85-1,00 (meist ueber 0,93,
     * die tiefsten in Bildern mitten im Ueberblenden), ein anderer Spieler hatte hoechstens 2 Portraets darueber.
     */
    public static final double PORTRAET_GLEICH_AB = 0.85;
    /** Bis zu dieser mittleren Farbabweichung ist es dasselbe Clan-Banner (gemessen: gleich bis 20,7, fremd ab 35,3). */
    public static final double BANNER_GLEICH_BIS = 28;
    /**
     * Grober Vorab-Vergleich (8 x 8, ohne Verschieben): darunter ist es sicher nicht dieselbe Einheit -- spart Rechenzeit.
     * Gemessen: gleiche Portraets mindestens 0,88; von 91.000 ungleichen Paaren lagen nur 8 % ueber 0,75.
     */
    static final double GROB_AB = 0.75;

    /**
     * Ein Einheiten-Portraet: die Werte fuer den Vergleich (Helligkeit 32 x 32 als Bytes, grob 8 x 8) und -- nur frisch
     * ausgeschnitten, zum Ablegen -- das Bild (40 x 40). Geladene behalten das Bild nicht: bei tausend Spielern spart
     * das viel Speicher.
     */
    public static final class Portraet {
        final BufferedImage bild;
        final byte[] grau;
        final float[] grob;

        Portraet(BufferedImage bild, boolean bildBehalten) {
            float[] g = verkleinern(bild, 0, 0, bild.getWidth(), bild.getHeight(), GRAU, GRAU, false);
            this.grau = new byte[g.length];
            for (int i = 0; i < g.length; i++) grau[i] = (byte) Math.min(255, Math.round(g[i]));
            this.grob = normiert(verkleinern(bild, 0, 0, bild.getWidth(), bild.getHeight(), 8, 8, false));
            this.bild = bildBehalten ? bild : null;
        }

        float[] grauWerte() {
            float[] f = new float[grau.length];
            for (int i = 0; i < grau.length; i++) f[i] = grau[i] & 255;
            return f;
        }
    }

    /** Was aus einem Vergleichsfenster gemerkt wird: die Portraets (links nach rechts) und das Banner (oder null). */
    public record Merkmale(List<Portraet> einheiten, BufferedImage banner) {
        public static final Merkmale LEER = new Merkmale(List.of(), null);

        public boolean leer() {
            return einheiten.isEmpty();
        }

        public int anzahl() {
            return einheiten.size();
        }
    }

    // ------------------------------------------------------------------ ausschneiden

    /**
     * Portraets und Banner des Gegners aus dem Vergleichsfenster. ankerX/ankerY = linke obere Ecke der Schwerter
     * (ar_power_swords). Findet der Bot keine Ringe, kommt {@link Merkmale#LEER} zurueck.
     */
    public static Merkmale ausVergleich(BufferedImage bild, int ankerX, int ankerY) {
        return bild == null ? Merkmale.LEER : ausVergleich(bild, ankerX, ankerY, bild.getHeight() / HOEHE);
    }

    /** Wie oben mit festem Massstab f (Fensterhoehe / 770) -- fuer Ausschnitte (Tests). */
    static Merkmale ausVergleich(BufferedImage bild, int ankerX, int ankerY, double f) {
        if (bild == null) return Merkmale.LEER;
        try {
            List<Double> mitten = portraetMitten(bild, ankerX, ankerY, f);
            List<Portraet> einheiten = new ArrayList<>();
            int kante = (int) Math.round(KANTE * f), cy = (int) Math.round(ankerY + MITTE_DY * f);
            for (double cx : mitten) {
                int x = (int) Math.round(cx) - kante / 2, y = cy - kante / 2;
                if (x < 0 || y < 0 || x + kante > bild.getWidth() || y + kante > bild.getHeight()) continue;
                einheiten.add(new Portraet(alsBild(verkleinern(bild, x, y, kante, kante, ABLAGE, ABLAGE, true), ABLAGE, ABLAGE), true));
            }
            if (einheiten.isEmpty()) return Merkmale.LEER;
            BufferedImage banner = null;
            int bx = (int) Math.round(ankerX + (mitPfote(bild, ankerX, ankerY, f) ? BANNER_DX_PFOTE : BANNER_DX_OHNE) * f);
            int by = (int) Math.round(ankerY + BANNER_DY * f), bk = (int) Math.round(BANNER_KANTE * f);
            if (bx >= 0 && by >= 0 && bx + bk <= bild.getWidth() && by + bk <= bild.getHeight()) {
                banner = alsBild(verkleinern(bild, bx, by, bk, bk, BANNER_KANTE, BANNER_KANTE, true), BANNER_KANTE, BANNER_KANTE);
            }
            return new Merkmale(einheiten, banner);
        } catch (RuntimeException e) {
            Log.debug("Arena", "Sperr-Bilder nicht ausgeschnitten: " + e.getMessage());
            return Merkmale.LEER;
        }
    }

    /**
     * Suchbereich fuer DEINE Portraet-Reihe (oben im Vergleichsfenster), Zeilen relativ zur linken oberen Ecke der
     * Schwerter. Gemessen 24.10. an logs/fotos-arena23 (1159 x 770): deine Reihe liegt 258-263 px ueber der des Gegners
     * (Ring-Zeilen y 220-252 gegen 481-514), ihre Mitte also rund 88 px ueber der Ecke.
     */
    static final int EIGEN_DY_VON = -130, EIGEN_DY_BIS = -46;

    /**
     * Autor 24.10.: die Portraets DEINER Einheiten aus dem Vergleichsfenster -- "fast genau das gleiche wie bei der
     * Sperrliste vom Gegner". Gesucht wird im Bereich ueber den Schwertern die Zeilenfolge mit den meisten hellblauen
     * Ring-Paaren; ihre Mitte ist die Mitte der Portraets. Ohne Banner (dein Clan aendert sich nicht). LEER = keine Reihe.
     */
    public static Merkmale eigeneReihe(BufferedImage bild, int ankerX, int ankerY) {
        if (bild == null) return Merkmale.LEER;
        double f = bild.getHeight() / HOEHE;
        try {
            int x0 = Math.max(0, (int) Math.round(ankerX + SUCHE_VON * f));
            int x1 = Math.min(bild.getWidth(), (int) Math.round(ankerX + SUCHE_BIS * f));
            int yVon = Math.max(0, (int) Math.round(ankerY + EIGEN_DY_VON * f));
            int yBis = Math.min(bild.getHeight() - 1, (int) Math.round(ankerY + EIGEN_DY_BIS * f));
            if (yBis <= yVon || x1 - x0 < 100) return Merkmale.LEER;
            List<List<Double>> jeZeile = new ArrayList<>();
            int max = 0;
            for (int y = yVon; y <= yBis; y++) {
                List<Double> m = reihe(paare(laeufe(bild, y, x0, x1), f), f);
                jeZeile.add(m);
                max = Math.max(max, m.size());
            }
            if (max == 0) return Merkmale.LEER;
            // Die laengste Folge von Zeilen mit der hoechsten Zahl (Luecken bis 2 Zeilen) -- einzelne Zeilen mit gleich vielen
            // Treffern ueber und unter der Reihe (andere Rahmen) zaehlen so nicht.
            int besterAnfang = -1, besterEnde = -1, anfang = -1, letzte = -10;
            for (int i = 0; i < jeZeile.size(); i++) {
                if (jeZeile.get(i).size() != max) continue;
                if (i - letzte > 3) anfang = i;
                letzte = i;
                if (besterAnfang < 0 || i - anfang > besterEnde - besterAnfang) {
                    besterAnfang = anfang;
                    besterEnde = i;
                }
            }
            int mitte = (besterAnfang + besterEnde) / 2;
            while (jeZeile.get(mitte).size() != max) mitte++;   // eine Zeile der Folge mit allen Treffern
            int cy = yVon + (besterAnfang + besterEnde) / 2, kante = (int) Math.round(KANTE * f);
            List<Portraet> einheiten = new ArrayList<>();
            for (double cx : jeZeile.get(mitte)) {
                int x = (int) Math.round(cx) - kante / 2, y = cy - kante / 2;
                if (x < 0 || y < 0 || x + kante > bild.getWidth() || y + kante > bild.getHeight()) continue;
                einheiten.add(new Portraet(alsBild(verkleinern(bild, x, y, kante, kante, ABLAGE, ABLAGE, true), ABLAGE, ABLAGE), true));
            }
            return einheiten.isEmpty() ? Merkmale.LEER : new Merkmale(einheiten, null);
        } catch (RuntimeException e) {
            Log.debug("Arena", "Eigene Reihe nicht ausgeschnitten: " + e.getMessage());
            return Merkmale.LEER;
        }
    }

    /** Die Mitten (x) der Portraets: Paare hellblauer Ringe in der Portraet-Reihe, im Abstand von 94 px. */
    static List<Double> portraetMitten(BufferedImage bild, int ankerX, int ankerY, double f) {
        List<Double> beste = List.of();
        for (int dy : RING_ZEILEN) {
            int y = (int) Math.round(ankerY + dy * f);
            if (y < 0 || y >= bild.getHeight()) continue;
            int x0 = Math.max(0, (int) Math.round(ankerX + SUCHE_VON * f));
            int x1 = Math.min(bild.getWidth(), (int) Math.round(ankerX + SUCHE_BIS * f));
            List<Double> m = reihe(paare(laeufe(bild, y, x0, x1), f), f);
            if (m.size() > beste.size()) beste = m;
        }
        return beste;
    }

    /** Steht links vom Gegner-Namen der goldene Pfoten-Knopf (255/203/0)? Mindestens ein Viertel goldene Punkte. */
    static boolean mitPfote(BufferedImage bild, int ankerX, int ankerY, double f) {
        int x0 = (int) Math.round(ankerX + PFOTE[0] * f), y0 = (int) Math.round(ankerY + PFOTE[1] * f);
        int w = (int) Math.round(PFOTE[2] * f), h = (int) Math.round(PFOTE[3] * f);
        if (x0 < 0 || y0 < 0 || x0 + w > bild.getWidth() || y0 + h > bild.getHeight() || w <= 0 || h <= 0) return false;
        int gold = 0;
        for (int y = y0; y < y0 + h; y++)
            for (int x = x0; x < x0 + w; x++) {
                int c = bild.getRGB(x, y), r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (r > 200 && g > 120 && b < 80) gold++;
            }
        return gold * 4 >= w * h;
    }

    /** Hellblau wie der Ring (99/235/247), nicht das dunklere Blau der Abzeichen. */
    static boolean ringfarbe(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return r < 160 && g > 200 && b > 220;
    }

    /** Zusammenhaengende Stuecke in Ringfarbe auf einer Zeile: {Anfang, Ende}. */
    static List<int[]> laeufe(BufferedImage bild, int y, int x0, int x1) {
        List<int[]> l = new ArrayList<>();
        int anfang = -1;
        for (int x = x0; x < x1; x++) {
            boolean ring = ringfarbe(bild.getRGB(x, y));
            if (ring && anfang < 0) anfang = x;
            if (!ring && anfang >= 0) { l.add(new int[]{anfang, x - 1}); anfang = -1; }
        }
        if (anfang >= 0) l.add(new int[]{anfang, x1 - 1});
        return l;
    }

    /** Linker und rechter Ring eines Portraets: 86-87 px auseinander. Liefert die Mitten. */
    static List<Double> paare(List<int[]> laeufe, double f) {
        List<Double> mitten = new ArrayList<>();
        int i = 0;
        while (i < laeufe.size()) {
            double links = (laeufe.get(i)[0] + laeufe.get(i)[1]) / 2.0;
            int passend = -1;
            double bester = Double.MAX_VALUE;
            for (int j = i + 1; j < laeufe.size(); j++) {
                double d = (laeufe.get(j)[0] + laeufe.get(j)[1]) / 2.0 - links;
                if (d > (RINGE + RING_SPIEL) * f) break;
                double abw = Math.abs(d - RINGE * f);
                if (abw <= RING_SPIEL * f && abw < bester) { bester = abw; passend = j; }
            }
            if (passend < 0) { i++; continue; }
            mitten.add((links + (laeufe.get(passend)[0] + laeufe.get(passend)[1]) / 2.0) / 2);
            i = passend + 1;
        }
        return mitten;
    }

    /** Nur Mitten, die im 94-px-Raster zueinander stehen (die groesste solche Gruppe), hoechstens 6. */
    static List<Double> reihe(List<Double> mitten, double f) {
        List<Double> beste = List.of();
        for (double start : mitten) {
            List<Double> g = new ArrayList<>();
            for (double m : mitten) {
                double schritte = (m - start) / (PLATZ * f);
                if (Math.abs(schritte - Math.round(schritte)) * PLATZ * f <= PLATZ_SPIEL * f) g.add(m);
            }
            if (g.size() > beste.size()) beste = g;
        }
        return beste.size() > MAX_EINHEITEN ? beste.subList(0, MAX_EINHEITEN) : beste;
    }

    // ------------------------------------------------------------------ vergleichen

    /**
     * Wie viele Portraets von b auch in a vorkommen (jedes hoechstens einmal, Reihenfolge egal -- der Spieler kann
     * seine Einheiten umstellen). Hoert auf, sobald {@code ziel} nicht mehr erreichbar ist (0 = alle zaehlen).
     */
    public static int gleicheEinheiten(Merkmale a, Merkmale b, int ziel) {
        if (a == null || b == null || a.leer() || b.leer()) return 0;
        boolean[] vergeben = new boolean[a.anzahl()];
        int gleich = 0;
        for (int i = 0; i < b.anzahl(); i++) {
            if (ziel > 0 && gleich + (b.anzahl() - i) < ziel) break;   // nicht mehr zu schaffen
            Portraet neu = b.einheiten().get(i);
            float[] neuGrau = null;
            int bester = -1;
            double besterWert = PORTRAET_GLEICH_AB;
            for (int j = 0; j < a.anzahl(); j++) {
                if (vergeben[j]) continue;
                Portraet alt = a.einheiten().get(j);
                if (punkt(alt.grob, neu.grob) < GROB_AB) continue;
                if (neuGrau == null) neuGrau = neu.grauWerte();
                double w = korrelation(alt.grauWerte(), neuGrau, GRAU, GRAU, 1, VERSATZ);
                if (w >= besterWert) { besterWert = w; bester = j; }
            }
            if (bester >= 0) { vergeben[bester] = true; gleich++; }
        }
        return gleich;
    }

    /** Gleiches Clan-Banner? null = nicht zu sagen (eins fehlt). */
    public static Boolean gleichesBanner(Merkmale a, Merkmale b) {
        if (a == null || b == null || a.banner() == null || b.banner() == null) return null;
        return bannerAbweichung(a.banner(), b.banner()) <= BANNER_GLEICH_BIS;
    }

    /**
     * Mittlere Farbabweichung (0..255) der Banner-Mitte (10 x 10, das Wappen ohne den goldenen Rand, den alle Banner
     * haben), bis 4 px verschoben -- die kleinste zaehlt. Korrelation trennte hier schlecht (verschiedene Clans 0,7-0,86,
     * weil Rand und Grund gleich sind), die Farbabweichung gut: gleich hoechstens 20,7, verschiedene Clans ab 35,3.
     */
    static double bannerAbweichung(BufferedImage a, BufferedImage b) {
        int k = BANNER_KANTE, r = BANNER_RAND, sp = BANNER_SPIEL, s = k - 2 * r;
        float[] x = verkleinern(a, 0, 0, a.getWidth(), a.getHeight(), k, k, true);
        float[] y = verkleinern(b, 0, 0, b.getWidth(), b.getHeight(), k, k, true);
        double best = Double.MAX_VALUE;
        for (int oy = r - sp; oy <= r + sp; oy++)
            for (int ox = r - sp; ox <= r + sp; ox++) {
                double summe = 0;
                for (int c = 0; c < 3; c++)
                    for (int yy = 0; yy < s; yy++)
                        for (int xx = 0; xx < s; xx++)
                            summe += Math.abs(x[(c * k + yy + r) * k + xx + r] - y[(c * k + yy + oy) * k + xx + ox]);
                best = Math.min(best, summe / (3.0 * s * s));
            }
        return best;
    }

    /**
     * Korrelation (-1..1) wie GemBayMode.aehnlich: das Mittelstueck von a (ringsum {@code versatz} kleiner) wird ueber b
     * geschoben, der beste Wert zaehlt. Beide Bilder gleich gross, {@code kanaele} Ebenen hintereinander.
     */
    static double korrelation(float[] a, float[] b, int breite, int hoehe, int kanaele, int versatz) {
        int sw = breite - 2 * versatz, sh = hoehe - 2 * versatz, n = sw * sh * kanaele;
        if (sw <= 0 || sh <= 0) return 0;
        float[] stueck = new float[n];
        double summe = 0;
        int k = 0;
        for (int c = 0; c < kanaele; c++)
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) summe += stueck[k++] = a[(c * hoehe + y + versatz) * breite + x + versatz];
        double mittel = summe / n, aa = 0;
        for (int i = 0; i < n; i++) {
            stueck[i] -= (float) mittel;
            aa += stueck[i] * stueck[i];
        }
        if (aa == 0) return 0;
        double best = -1;
        for (int oy = 0; oy <= 2 * versatz; oy++)
            for (int ox = 0; ox <= 2 * versatz; ox++) {
                double sb = 0, sbb = 0, sab = 0;
                k = 0;
                for (int c = 0; c < kanaele; c++)
                    for (int y = 0; y < sh; y++) {
                        int zeile = (c * hoehe + oy + y) * breite + ox;
                        for (int x = 0; x < sw; x++) {
                            double v = b[zeile + x];
                            sb += v;
                            sbb += v * v;
                            sab += stueck[k++] * v;
                        }
                    }
                double varianz = sbb - sb * sb / n;
                if (varianz > 0) best = Math.max(best, sab / Math.sqrt(aa * varianz));
            }
        return best;
    }

    private static double punkt(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** Ohne Mittelwert und auf Laenge 1 gebracht (fuer den groben Vorab-Vergleich). */
    private static float[] normiert(float[] v) {
        double m = 0;
        for (float x : v) m += x;
        m /= v.length;
        double q = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] -= (float) m;
            q += v[i] * v[i];
        }
        double l = Math.sqrt(q);
        if (l > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) l;
        return v;
    }

    /**
     * Ein Rechteck des Bildes auf zb x zh verkleinern (Mittelwert je Feld). farbig: drei Ebenen R, G, B hintereinander,
     * sonst eine Ebene Helligkeit.
     */
    static float[] verkleinern(BufferedImage bild, int x, int y, int w, int h, int zb, int zh, boolean farbig) {
        int[] px = bild.getRGB(x, y, w, h, null, 0, w);
        int kan = farbig ? 3 : 1;
        float[] aus = new float[zb * zh * kan];
        for (int ty = 0; ty < zh; ty++) {
            int y0 = ty * h / zh, y1 = Math.max(y0 + 1, (ty + 1) * h / zh);
            for (int tx = 0; tx < zb; tx++) {
                int x0 = tx * w / zb, x1 = Math.max(x0 + 1, (tx + 1) * w / zb);
                double r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++)
                    for (int sx = x0; sx < x1; sx++) {
                        int c = px[sy * w + sx];
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        n++;
                    }
                r /= n; g /= n; b /= n;
                if (farbig) {
                    aus[ty * zb + tx] = (float) r;
                    aus[(zh + ty) * zb + tx] = (float) g;
                    aus[(2 * zh + ty) * zb + tx] = (float) b;
                } else {
                    aus[ty * zb + tx] = (float) (0.299 * r + 0.587 * g + 0.114 * b);
                }
            }
        }
        return aus;
    }

    private static BufferedImage alsBild(float[] rgb, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int r = Math.round(rgb[y * w + x]), g = Math.round(rgb[(h + y) * w + x]), bl = Math.round(rgb[(2 * h + y) * w + x]);
                b.setRGB(x, y, (r << 16) | (g << 8) | bl);
            }
        return b;
    }

    // ------------------------------------------------------------------ ablegen / laden

    /**
     * Ein Bild je Spieler (ein Streifen, damit es nicht tausende kleine Dateien werden -- die Sicherung): links das
     * Banner in einem 40er-Feld (schwarz = keins), dann die Portraets je 40 x 40.
     */
    public static void speichern(Path datei, Merkmale m) {
        if (datei == null || m == null || m.leer()) return;
        try {
            BufferedImage s = new BufferedImage(ABLAGE * (m.anzahl() + 1), ABLAGE, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = s.createGraphics();
            if (m.banner() != null) g.drawImage(m.banner(), 0, 0, null);
            for (int i = 0; i < m.anzahl(); i++) {
                if (m.einheiten().get(i).bild != null) g.drawImage(m.einheiten().get(i).bild, ABLAGE * (i + 1), 0, null);
            }
            g.dispose();
            Files.createDirectories(datei.toAbsolutePath().getParent());
            // Ueber Java schreiben, nicht imwrite: OpenCV scheitert unter Windows an Pfaden mit Umlauten (wie ArenaAussehen).
            ImageIO.write(s, "png", datei.toFile());
        } catch (Exception e) {
            Log.debug("Arena", "Sperr-Bilder nicht abgelegt: " + e.getMessage());
        }
    }

    /** Das abgelegte Bild wieder zerlegen; null, wenn keins da ist. */
    public static Merkmale laden(Path datei) {
        try {
            if (datei == null || !Files.exists(datei)) return null;
            BufferedImage s = ImageIO.read(datei.toFile());
            if (s == null || s.getHeight() < ABLAGE || s.getWidth() < 2 * ABLAGE) return null;
            BufferedImage banner = kopie(s, 0, 0, BANNER_KANTE, BANNER_KANTE);
            boolean schwarz = true;
            for (int y = 0; y < BANNER_KANTE && schwarz; y++)
                for (int x = 0; x < BANNER_KANTE; x++) if ((banner.getRGB(x, y) & 0xFFFFFF) != 0) { schwarz = false; break; }
            List<Portraet> e = new ArrayList<>();
            for (int i = 1; i < s.getWidth() / ABLAGE; i++) e.add(new Portraet(kopie(s, ABLAGE * i, 0, ABLAGE, ABLAGE), false));
            return new Merkmale(e, schwarz ? null : banner);
        } catch (Exception e) {
            Log.debug("Arena", "Sperr-Bilder nicht lesbar: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage kopie(BufferedImage s, int x, int y, int w, int h) {
        BufferedImage k = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        k.setRGB(0, 0, w, h, s.getRGB(x, y, w, h, null, 0, w), 0, w);
        return k;
    }
}
