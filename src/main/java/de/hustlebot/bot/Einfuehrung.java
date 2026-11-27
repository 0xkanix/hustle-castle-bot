package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfInt4;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pflicht-Einfuehrungen (Tutorials) von Events selbst durchspielen.
 *
 * Autor 21.11.: "Die Einfuehrung -- das kannst du in den Bot einbauen, dass er die Einfuehrung auch spielen
 * kann." Auf dem Zweitkonto blockierte so eine Einfuehrung ALLES: das Spiel nimmt waehrenddessen nur Klicks auf das
 * gezeigte Ziel an. Der alte Bot tippte nur den Text weg und gab nach Minuten auf ("Nothing recognized ... Templates are
 * probably missing").
 *
 * Was eine Einfuehrung zeigt (Event-Einfuehrungen, Bilder vom 21.11.):
 * <ul>
 *   <li>eine Sprechblase: tuerkiser Kasten unten mit Namensband (Sage, Abyss Lord, Frogus ...) -- erkannt an der linken
 *       Verzierung des Namensbands (Vorlage {@value #T_NAMENSBAND});</li>
 *   <li>einen grossen rot-orangen Pfeil (flache Farbe, weisser Rand), der auf das Ziel zeigt -- erkannt an Farbe und Form,
 *       ohne Vorlage, damit jede Richtung und jedes Event passt;</li>
 *   <li>beim Zusammenlegen eine Hand, die einen Gegenstand auf den anderen zieht.</li>
 * </ul>
 * Pfeil sichtbar: das Ziel vor der Spitze antippen (lang halten -- Autor 21.11.: kurze Klicks wirkten in der Einfuehrung
 * nicht, ~200 ms gehalten schon). Nur Sprechblase: den Text antippen. Hand: die gezeigte Bewegung langsam nachziehen.
 * Nie etwas kaufen: zeigt der Pfeil auf einen gruenen Knopf mit Preis ("Buy"), wird NICHT getippt.
 */
public final class Einfuehrung {

    /** Linke Verzierung des Namensbands der Sprechblase (aus Autors Bild vom 21.11., Abyss Lord in der Burg). */
    static final String T_NAMENSBAND = "einf_namensband";
    /** Schwelle fuer das Namensband: echte Bilder 0,9-1,0, normale Spielbilder hoechstens 0,55 (gemessen 21.11.). */
    static final double NAMENSBAND_SCHWELLE = 0.75;
    /** So lange wird ein Knopf in der Einfuehrung gedrueckt gehalten (Autor 21.11.: kurze Klicks wirkten nicht). */
    static final int HALTEN_MS = 200;
    /** Wie weit hinter der Pfeilspitze das Ziel liegt, als Anteil der Pfeillaenge (Burg: Koch, Event: Reiter). */
    static final double ZIEL_ABSTAND = 0.45;
    /** Wirkt der Tipp nicht (Pfeil bleibt), werden andere Abstaende probiert. */
    private static final double[] ABSTAENDE = {ZIEL_ABSTAND, ZIEL_ABSTAND, 0.8, 0.25, 0.6, 0.15};
    /** Zeitgrenze fuer eine ganze Einfuehrung -- nie endlos. */
    static final long HOECHSTENS_MS = 6 * 60_000L;
    /** Nach dem Aufgeben so lange nicht mehr eingreifen (weiter wie bisher). */
    static final long PAUSE_NACH_AUFGABE_MS = 10 * 60_000L;
    /** Ohne Pfeil/Blase so lange nach der letzten Handlung gilt die Einfuehrung als fertig. */
    static final long FERTIG_NACH_MS = 5000;

    /**
     * NICHTS = nichts zu tun (weiter wie bisher, das Bild gilt noch), GEHANDELT = getippt, BILD_VERALTET = nichts getan,
     * aber neue Bilder aufgenommen (das alte Bild ist freigegeben -- der Motor muss neu aufnehmen), FERTIG = Einfuehrung
     * vorbei, Event-Fenster geschlossen -- jetzt zur Burg.
     */
    public enum Ergebnis { NICHTS, GEHANDELT, BILD_VERALTET, FERTIG }

    // ================================================================================================================
    // Erkennen: der Pfeil
    // ================================================================================================================

    /**
     * Ein Zeige-Pfeil der Einfuehrung.
     *
     * @param spitzeX  Spitze (Bildpunkt am Ende in Zeigerichtung)
     * @param richtungX Zeigerichtung (Einheitsvektor)
     * @param laenge   Laenge des Pfeils entlang der Zeigerichtung
     */
    public record Pfeil(int spitzeX, int spitzeY, double richtungX, double richtungY, int laenge, int flaeche) {
        /** Punkt vor der Spitze, auf den der Pfeil zeigt. */
        public Point ziel(double abstand) {
            return new Point(spitzeX + richtungX * laenge * abstand, spitzeY + richtungY * laenge * abstand);
        }

        public Point ziel() {
            return ziel(ZIEL_ABSTAND);
        }

        /** Wie weit die Spitze in Zeigerichtung vorn liegt (zum Vergleich mehrerer Bilder: der Pfeil wippt). */
        double vorn(double rx, double ry) {
            return spitzeX * rx + spitzeY * ry;
        }
    }

    /**
     * Sucht die rot-orangen Zeige-Pfeile im Bild (BGR, Spielflaeche ~1160x770; andere Groessen werden mitskaliert).
     *
     * Gemessen 21.11.: der Pfeil hat flache Farbe BGR (11,62,247) = HSV (6,244,247), ist ~76x74 Bildpunkte gross
     * (Flaeche ~3400), Festigkeit (Flaeche / konvexe Huelle) 0,85-0,88, zwei tiefe Einbuchtungen (Schaft trifft Kopf,
     * je ~10 Bildpunkte), die dritte kaum (unter 4). Rote Dinge im Spiel (Halstuch, Aepfel, Kreuze, Zahlen-Marken,
     * Gemuese) fallen an Farbe, Seitenverhaeltnis oder Form durch (geprueft an 125 Testbildern).
     */
    public static List<Pfeil> pfeile(Mat bgr) {
        List<Pfeil> out = new ArrayList<>();
        if (bgr == null || bgr.empty() || bgr.channels() < 3) return out;
        double f = bgr.width() / 1160.0;
        Mat hsv = new Mat(), maske = new Mat(), labels = new Mat(), stats = new Mat(), mitten = new Mat();
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(1, 200, 200), new Scalar(11, 255, 255), maske);
            int n = Imgproc.connectedComponentsWithStats(maske, labels, stats, mitten, 8, org.opencv.core.CvType.CV_32S);
            for (int k = 1; k < n; k++) {
                int x = (int) stats.get(k, Imgproc.CC_STAT_LEFT)[0];
                int y = (int) stats.get(k, Imgproc.CC_STAT_TOP)[0];
                int w = (int) stats.get(k, Imgproc.CC_STAT_WIDTH)[0];
                int h = (int) stats.get(k, Imgproc.CC_STAT_HEIGHT)[0];
                int a = (int) stats.get(k, Imgproc.CC_STAT_AREA)[0];
                if (a < 1800 * f * f || a > 7000 * f * f) continue;
                double seiten = w / (double) h;
                if (seiten < 0.7 || seiten > 1.45) continue;
                if (x <= 0 || y <= 0 || x + w >= bgr.width() || y + h >= bgr.height()) continue;   // angeschnitten
                Pfeil p = pruefen(bgr, labels, k, new Rect(x, y, w, h), a);
                if (p != null) out.add(p);
            }
        } finally {
            hsv.release();
            maske.release();
            labels.release();
            stats.release();
            mitten.release();
        }
        out.sort(Comparator.comparingInt(Pfeil::flaeche).reversed());
        return out;
    }

    /** Form- und Farbpruefung eines Kandidaten; Spitze und Richtung bestimmen. null = kein Pfeil. */
    private static Pfeil pruefen(Mat bgr, Mat labels, int k, Rect r, int flaeche) {
        Mat teil = new Mat(), farbe = null;
        MatOfDouble mittel = new MatOfDouble(), streu = new MatOfDouble();
        List<MatOfPoint> konturen = new ArrayList<>();
        Mat hier = new Mat();
        try {
            Core.compare(labels.submat(r), new Scalar(k), teil, Core.CMP_EQ);

            // Flache Farbe: echte Pfeile streuen kaum (JPEG-Bilder etwas mehr).
            farbe = bgr.submat(r);
            Core.meanStdDev(farbe, mittel, streu, teil);
            double[] s = streu.toArray();
            if ((s[0] + s[1] + s[2]) / 3.0 > 22) return null;

            Imgproc.findContours(teil.clone(), konturen, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (konturen.isEmpty()) return null;
            MatOfPoint kontur = konturen.stream().max(Comparator.comparingDouble(Imgproc::contourArea)).get();
            double kFlaeche = Imgproc.contourArea(kontur);
            MatOfInt huelleIdx = new MatOfInt();
            Imgproc.convexHull(kontur, huelleIdx);
            Point[] kp = kontur.toArray();
            int[] idx = huelleIdx.toArray();
            Point[] hp = new Point[idx.length];
            for (int i = 0; i < idx.length; i++) hp[i] = kp[idx[i]];
            double hFlaeche = Imgproc.contourArea(new MatOfPoint(hp));
            double fest = kFlaeche / Math.max(1, hFlaeche);
            if (fest < 0.78 || fest > 0.94) return null;

            // Zwei tiefe Einbuchtungen (wo der Schaft auf den Kopf trifft), keine dritte.
            double wurzel = Math.sqrt(flaeche);
            List<Double> tiefen = new ArrayList<>();
            try {
                MatOfInt4 dellen = new MatOfInt4();
                Imgproc.convexityDefects(kontur, huelleIdx, dellen);
                int[] d = dellen.toArray();
                for (int i = 0; i + 3 < d.length; i += 4) tiefen.add(d[i + 3] / 256.0);
            } catch (CvException e) {
                return null;   // verdrehte Kontur -- kein sauberer Pfeil
            }
            tiefen.sort(Comparator.reverseOrder());
            if (tiefen.size() < 2) return null;
            if (tiefen.get(1) < 0.10 * wurzel) return null;
            if (tiefen.size() > 2 && tiefen.get(2) > 0.6 * tiefen.get(1)) return null;

            return spitzeBestimmen(teil, r, flaeche);
        } finally {
            teil.release();
            hier.release();
            mittel.release();
            streu.release();
            for (MatOfPoint m : konturen) m.release();
        }
    }

    /**
     * Hauptachse aus den Momenten; das schmalere Ende ist die Spitze (der Kopf laeuft spitz zu, der Schaft hat am Ende
     * die volle Schaftbreite -- gemessen 0,42-0,66 Breitenverhaeltnis).
     */
    private static Pfeil spitzeBestimmen(Mat teil, Rect r, int flaeche) {
        Moments m = Imgproc.moments(teil, true);
        if (m.m00 <= 0) return null;
        double cx = m.m10 / m.m00, cy = m.m01 / m.m00;
        double winkel = 0.5 * Math.atan2(2 * m.mu11, m.mu20 - m.mu02);
        double ax = Math.cos(winkel), ay = Math.sin(winkel);
        int w = teil.cols(), h = teil.rows();
        byte[] px = new byte[w * h];
        Mat dicht = teil.isContinuous() ? teil : teil.clone();
        dicht.get(0, 0, px);
        if (dicht != teil) dicht.release();
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                if (px[yy * w + xx] == 0) continue;
                double t = (xx - cx) * ax + (yy - cy) * ay;
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
            }
        }
        double laenge = tMax - tMin;
        if (laenge < 10) return null;
        double band = 0.1 * laenge;
        double[] vorn = {Double.MAX_VALUE, -Double.MAX_VALUE}, hinten = {Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                if (px[yy * w + xx] == 0) continue;
                double t = (xx - cx) * ax + (yy - cy) * ay;
                double s = -(xx - cx) * ay + (yy - cy) * ax;
                if (t <= tMin + band) {
                    vorn[0] = Math.min(vorn[0], s);
                    vorn[1] = Math.max(vorn[1], s);
                }
                if (t >= tMax - band) {
                    hinten[0] = Math.min(hinten[0], s);
                    hinten[1] = Math.max(hinten[1], s);
                }
            }
        }
        double bMin = vorn[1] - vorn[0], bMax = hinten[1] - hinten[0];
        double schmal = Math.min(bMin, bMax), breit = Math.max(bMin, bMax);
        if (breit <= 0 || schmal / breit > 0.8) return null;   // kein klar schmales Ende
        boolean spitzeBeiMin = bMin < bMax;
        double rx = spitzeBeiMin ? -ax : ax, ry = spitzeBeiMin ? -ay : ay;
        double tSpitze = spitzeBeiMin ? tMin : tMax;
        double sx = r.x + cx + ax * tSpitze, sy = r.y + cy + ay * tSpitze;
        return new Pfeil((int) Math.round(sx), (int) Math.round(sy), rx, ry, (int) Math.round(laenge), flaeche);
    }

    // ================================================================================================================
    // Erkennen: die Sprechblase
    // ================================================================================================================

    /** Namensband der Sprechblase (nur in der unteren Bildhaelfte gesucht). */
    public static Optional<Matcher.Hit> sprechblase(Mat screen, Matcher matcher) {
        if (screen == null || matcher == null || !matcher.has(T_NAMENSBAND)) return Optional.empty();
        int y0 = (int) (screen.height() * 0.45);
        return matcher.findImBereich(screen, T_NAMENSBAND, new Rect(0, y0, screen.width(), screen.height() - y0),
                NAMENSBAND_SCHWELLE);
    }

    /**
     * Wo der Text der Sprechblase steht: unter und rechts neben der Verzierung (Burg: Text 630-740, Verzierung bei 592;
     * Grim/Sage ebenso). Dort tippen schaltet den Satz weiter.
     */
    public static Point textStelle(Matcher.Hit band, int breite, int hoehe) {
        int x = Math.min(breite - 20, band.centerX() + 120);
        int y = Math.min(hoehe - 15, band.centerY() + 75);
        return new Point(x, y);
    }

    // ================================================================================================================
    // Erkennen: die Hand (Zusammenlegen)
    // ================================================================================================================

    /** Gezeigte Bewegung der Hand: von Start nach Ende ziehen. */
    public record Hand(int startX, int startY, int endeX, int endeY) {}

    /**
     * Die Hand aus mehreren kurz nacheinander aufgenommenen Bildern: hautfarbene Bildpunkte, die in einem anderen Bild der
     * Reihe anders aussehen (Gesichter der Figuren stehen still). Fingerspitze = oberste
     * Stelle der Hand (die Hand zeigt mit dem Zeigefinger nach oben links, Bild 21.11.). Aus der Spur der Fingerspitze:
     * die zwei entferntesten Punkte; Start ist das Ende, von dem die Hand gleichmaessig weggleitet (der Rueck-Sprung ist gross).
     *
     * @param obenBis nur oberhalb dieser Zeile suchen (Oberkante der Sprechblase; dort sind die Gesichter)
     */
    public static Optional<Hand> hand(List<Mat> bilder, int obenBis) {
        int n = bilder.size();
        if (n < 4) return Optional.empty();
        List<Mat> grau = new ArrayList<>(), haut = new ArrayList<>();
        List<Point> spur = new ArrayList<>();
        try {
            for (Mat b : bilder) {
                Mat klein = new Mat(), hsv = new Mat(), g = new Mat(), m1 = new Mat(), m2 = new Mat();
                Imgproc.resize(b, klein, new Size(b.width() / 2.0, b.height() / 2.0), 0, 0, Imgproc.INTER_AREA);
                Imgproc.cvtColor(klein, hsv, Imgproc.COLOR_BGR2HSV);
                Imgproc.cvtColor(klein, g, Imgproc.COLOR_BGR2GRAY);
                // Hand gemessen 21.11.: H 3-9 (bis 172), S 41-103, V 138-255; der gelbe Schein drumherum und das Brett H 10-36.
                Core.inRange(hsv, new Scalar(0, 35, 140), new Scalar(11, 135, 255), m1);
                Core.inRange(hsv, new Scalar(165, 35, 140), new Scalar(180, 135, 255), m2);
                Core.bitwise_or(m1, m2, m1);
                grau.add(g);
                haut.add(m1);
                klein.release();
                hsv.release();
                m2.release();
            }
            int grenze = Math.max(1, Math.min(grau.get(0).rows(), obenBis / 2));
            for (int i = 0; i < n; i++) {
                // Groesster Unterschied zu IRGENDEINEM anderen Bild der Reihe: so faellt eine Hand nicht heraus, deren
                // Bewegung zufaellig genau so lang dauert wie der Abstand zweier Bilder. Stillstehende Gesichter bleiben 0.
                Mat diff = Mat.zeros(grau.get(i).size(), grau.get(i).type()), d = new Mat(), beweg = new Mat();
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    Core.absdiff(grau.get(i), grau.get(j), d);
                    Core.max(diff, d, diff);
                }
                d.release();
                Imgproc.threshold(diff, beweg, 25, 255, Imgproc.THRESH_BINARY);
                Core.bitwise_and(beweg, haut.get(i), beweg);
                if (grenze < beweg.rows()) beweg.submat(new Rect(0, grenze, beweg.cols(), beweg.rows() - grenze)).setTo(new Scalar(0));
                Imgproc.morphologyEx(beweg, beweg, Imgproc.MORPH_OPEN,
                        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3)));
                Point spitze = fingerspitze(beweg);
                if (spitze != null) spur.add(new Point(spitze.x * 2, spitze.y * 2));
                diff.release();
                beweg.release();
            }
        } finally {
            for (Mat m : grau) m.release();
            for (Mat m : haut) m.release();
        }
        return bewegungAusSpur(spur);
    }

    /** Oberste Stelle des groessten Flecks (Mittel der obersten 4 Zeilen); null ohne ausreichend grossen Fleck. */
    private static Point fingerspitze(Mat maske) {
        Mat labels = new Mat(), stats = new Mat(), mitten = new Mat();
        try {
            int n = Imgproc.connectedComponentsWithStats(maske, labels, stats, mitten);
            int best = -1, bestA = 0;
            for (int k = 1; k < n; k++) {
                int a = (int) stats.get(k, Imgproc.CC_STAT_AREA)[0];
                if (a > bestA) {
                    bestA = a;
                    best = k;
                }
            }
            if (best < 0 || bestA < 120) return null;
            int x = (int) stats.get(best, Imgproc.CC_STAT_LEFT)[0], y = (int) stats.get(best, Imgproc.CC_STAT_TOP)[0];
            int w = (int) stats.get(best, Imgproc.CC_STAT_WIDTH)[0];
            int h = Math.min(4, (int) stats.get(best, Imgproc.CC_STAT_HEIGHT)[0]);
            int[] lab = new int[w * h];
            labels.submat(new Rect(x, y, w, h)).clone().get(0, 0, lab);
            double sx = 0;
            int z = 0;
            for (int i = 0; i < lab.length; i++) {
                if (lab[i] == best) {
                    sx += i % w;
                    z++;
                }
            }
            return z == 0 ? null : new Point(x + sx / z, y + 1);
        } finally {
            labels.release();
            stats.release();
            mitten.release();
        }
    }

    /** Aus der Spur der Fingerspitze Start und Ende der gezeigten Bewegung (mindestens 35 Bildpunkte Weg). */
    static Optional<Hand> bewegungAusSpur(List<Point> spur) {
        if (spur.size() < 4) return Optional.empty();
        Point p = null, q = null;
        double weit = 0;
        for (int i = 0; i < spur.size(); i++) {
            for (int j = i + 1; j < spur.size(); j++) {
                double d = Math.hypot(spur.get(i).x - spur.get(j).x, spur.get(i).y - spur.get(j).y);
                if (d > weit) {
                    weit = d;
                    p = spur.get(i);
                    q = spur.get(j);
                }
            }
        }
        if (p == null || weit < 35) return Optional.empty();
        double ux = (q.x - p.x) / weit, uy = (q.y - p.y) / weit;
        double summe = 0;
        for (int i = 0; i + 1 < spur.size(); i++) {
            double vx = spur.get(i + 1).x - spur.get(i).x, vy = spur.get(i + 1).y - spur.get(i).y;
            if (Math.hypot(vx, vy) < 0.6 * weit) summe += vx * ux + vy * uy;   // gleichmaessiges Gleiten, kein Rueck-Sprung
        }
        if (summe < 0) {
            Point t = p;
            p = q;
            q = t;
        }
        return Optional.of(new Hand((int) Math.round(p.x), (int) Math.round(p.y), (int) Math.round(q.x), (int) Math.round(q.y)));
    }

    // ================================================================================================================
    // Nie kaufen
    // ================================================================================================================

    /**
     * Liegt das Ziel auf einem gruenen Knopf? Dann koennte es "Buy" mit Diamanten sein (Autor 21.11.: NIE etwas mit
     * Diamanten/Kaufen antippen). Gruen = HSV 35-85, mehr als ein Viertel der Flaeche um das Ziel.
     */
    static boolean gruenerKnopf(Mat bgr, Point ziel) {
        Rect r = new Rect((int) ziel.x - 40, (int) ziel.y - 18, 80, 36);
        Rect bild = new Rect(0, 0, bgr.width(), bgr.height());
        int x0 = Math.max(r.x, 0), y0 = Math.max(r.y, 0);
        int x1 = Math.min(r.x + r.width, bild.width), y1 = Math.min(r.y + r.height, bild.height);
        if (x1 - x0 < 10 || y1 - y0 < 10) return false;
        Mat hsv = new Mat(), gruen = new Mat();
        try {
            Imgproc.cvtColor(bgr.submat(new Rect(x0, y0, x1 - x0, y1 - y0)), hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(35, 120, 110), new Scalar(85, 255, 255), gruen);
            return Core.countNonZero(gruen) > 0.25 * (x1 - x0) * (y1 - y0);
        } finally {
            hsv.release();
            gruen.release();
        }
    }

    /** Text auf dem Knopf: "buy"/"kauf"/Ziffern = Preis. Ohne Lesung gilt er als gefaehrlich. */
    static boolean kaufText(String text) {
        if (text == null || text.isBlank()) return true;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("buy") || t.contains("kauf") || t.contains("purchase") || t.contains("speed") || t.matches(".*\\d.*");
    }

    // ================================================================================================================
    // Handeln
    // ================================================================================================================

    private long sitzungSeit;
    private long letzteAktionUm;
    private long letztePruefungUm;
    private long pauseBis;
    private int aktionen;
    private boolean echteEinfuehrung;   // Pfeil oder Hand gesehen (nicht nur ein Erzaehltext)
    private Point letztesZiel;
    private int gleichesZiel;
    private Mat letzteBlase;
    private int gleicheBlase;
    private boolean gemeldet;

    /**
     * Ein Schritt: Pfeil -> Ziel tippen, nur Sprechblase -> Text tippen, Hand -> Bewegung nachziehen. Ausserhalb einer
     * Einfuehrung wird hoechstens alle 0,6 s nachgesehen (Farbsuche + Vorlage im unteren Bildteil, wenige ms).
     *
     * @return siehe {@link Ergebnis}
     */
    public Ergebnis schritt(Mat screen, BotContext ctx, String woher) {
        long jetzt = System.currentTimeMillis();
        if (screen == null || jetzt < pauseBis) return Ergebnis.NICHTS;
        if (sitzungSeit == 0 && jetzt - letztePruefungUm < 600) return Ergebnis.NICHTS;
        letztePruefungUm = jetzt;

        List<Pfeil> pfeile = pfeile(screen);
        Optional<Matcher.Hit> blase = sprechblase(screen, ctx.matcher());

        if (pfeile.isEmpty() && blase.isEmpty()) {
            if (sitzungSeit == 0) return Ergebnis.NICHTS;
            if (jetzt - letzteAktionUm < FERTIG_NACH_MS) return Ergebnis.NICHTS;
            return beenden(screen, ctx, woher);
        }

        if (sitzungSeit == 0) {
            sitzungSeit = jetzt;
            aktionen = 0;
            echteEinfuehrung = false;
            gemeldet = false;
            Log.info(woher, Sprache.t("Event introduction (tutorial) on screen -- the bot plays it through first.",
                    "Einfuehrung eines Events auf dem Bild -- der Bot spielt sie erst durch."));
        }
        if (jetzt - sitzungSeit > HOECHSTENS_MS) {
            return aufgeben(screen, ctx, woher, Sprache.t("time limit of 6 minutes reached", "Zeitgrenze 6 Minuten erreicht"));
        }

        if (!pfeile.isEmpty()) return pfeilTippen(screen, pfeile.get(0), blase, ctx, woher);
        return blaseTippen(screen, blase.get(), ctx, woher);
    }

    /** Der Pfeil wippt: zwei weitere Bilder ansehen und das mit der Spitze am weitesten vorn nehmen. */
    private Ergebnis pfeilTippen(Mat screen, Pfeil erster, Optional<Matcher.Hit> blase, BotContext ctx, String woher) {
        echteEinfuehrung = true;
        Pfeil bester = erster;
        int gesehen = 1;
        for (int i = 0; i < 2; i++) {
            ctx.sleep(110);
            Mat neu = ctx.refresh();
            if (neu == null) break;
            for (Pfeil p : pfeile(neu)) {
                if (Math.hypot(p.spitzeX() - erster.spitzeX(), p.spitzeY() - erster.spitzeY()) > 80) continue;
                gesehen++;
                if (p.vorn(erster.richtungX(), erster.richtungY()) > bester.vorn(erster.richtungX(), erster.richtungY())) bester = p;
            }
            screen = neu;
        }
        if (gesehen < 2) return Ergebnis.BILD_VERALTET;   // nur kurz aufgeblitzt -- kein Pfeil der Einfuehrung

        if (letztesZiel != null && Math.hypot(letztesZiel.x - bester.spitzeX(), letztesZiel.y - bester.spitzeY()) < 60) {
            gleichesZiel++;
        } else {
            gleichesZiel = 0;
        }
        letztesZiel = new Point(bester.spitzeX(), bester.spitzeY());
        if (gleichesZiel >= ABSTAENDE.length) {
            return aufgeben(screen, ctx, woher, Sprache.t("the arrow stays after " + gleichesZiel + " taps",
                    "der Pfeil bleibt nach " + gleichesZiel + " Tipps"));
        }
        // Grim 21.11.: Pfeil UND Sprechblase -- wirkt der erste Tipp aufs Ziel nicht, einmal die Blase weitertippen.
        if (gleichesZiel == 1 && blase.isPresent()) {
            Point t = textStelle(blase.get(), ctx.width(), ctx.height());
            Log.action("Klick", Sprache.t("Introduction: arrow stays -- tap the speech bubble once", "Einfuehrung: Pfeil bleibt -- einmal die Sprechblase antippen")
                    + "  [" + (int) t.x + "," + (int) t.y + "]");
            ctx.input().klickLang((int) t.x, (int) t.y, HALTEN_MS);
            gehandelt();
            ctx.sleepUntilChanged(1200);
            return Ergebnis.GEHANDELT;
        }
        Point ziel = bester.ziel(ABSTAENDE[gleichesZiel]);
        int zx = (int) Math.max(5, Math.min(screen.width() - 5, ziel.x));
        int zy = (int) Math.max(5, Math.min(screen.height() - 5, ziel.y));

        if (gruenerKnopf(screen, new Point(zx, zy)) && kaufKnopf(ctx, zx, zy)) {
            Log.warn(woher, Sprache.t("Introduction: the arrow points at a green button with a price -- the bot never buys. Not tapped.",
                    "Einfuehrung: der Pfeil zeigt auf einen gruenen Knopf mit Preis -- der Bot kauft nie. Nicht getippt."));
            return aufgeben(screen, ctx, woher, Sprache.t("arrow points at a purchase button", "Pfeil zeigt auf einen Kauf-Knopf"));
        }

        Log.action("Klick", Sprache.t("Introduction: tap what the arrow points at", "Einfuehrung: antippen, worauf der Pfeil zeigt")
                + "  [" + zx + "," + zy + Sprache.t(", tip ", ", Spitze ") + bester.spitzeX() + "," + bester.spitzeY()
                + (gleichesZiel > 0 ? Sprache.t(", try ", ", Versuch ") + (gleichesZiel + 1) : "") + "]");
        ctx.input().klickLang(zx, zy, HALTEN_MS);
        gehandelt();
        ctx.sleepUntilChanged(1500);
        return Ergebnis.GEHANDELT;
    }

    /** Nur Sprechblase: Text antippen. Bleibt dieselbe Blase stehen, zeigt vielleicht eine Hand die Aufgabe. */
    private Ergebnis blaseTippen(Mat screen, Matcher.Hit band, BotContext ctx, String woher) {
        Mat jetzt = blasenBild(screen, band);
        if (letzteBlase != null && !jetzt.empty() && gleichesBild(letzteBlase, jetzt)) {
            gleicheBlase++;
        } else {
            gleicheBlase = 0;
        }
        if (letzteBlase != null) letzteBlase.release();
        letzteBlase = jetzt;

        if (gleicheBlase >= 2) {
            Ergebnis h = handVersuchen(ctx, woher, band.y() - 5);
            if (h != null) return h;
            if (gleicheBlase >= 8) {
                return aufgeben(ctx.aktuellesBild(), ctx, woher, Sprache.t("the speech bubble does not go away", "die Sprechblase geht nicht weg"));
            }
        }
        Point t = textStelle(band, ctx.width(), ctx.height());   // ctx: die Hand-Suche kann neue Bilder geholt haben
        Log.action("Klick", Sprache.t("Introduction: tap the speech bubble", "Einfuehrung: Sprechblase antippen")
                + "  [" + (int) t.x + "," + (int) t.y + ", " + String.format("%.3f", band.score()) + "]");
        ctx.input().klickLang((int) t.x, (int) t.y, HALTEN_MS);
        gehandelt();
        ctx.sleepUntilChanged(1200);
        return Ergebnis.GEHANDELT;
    }

    /** Eine Hand zeigt eine Bewegung? 16 Bilder in ~2 s ansehen und nachziehen. null = keine Hand gefunden. */
    private Ergebnis handVersuchen(BotContext ctx, String woher, int obenBis) {
        List<Mat> bilder = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                Mat b = ctx.refresh();
                if (b == null) return null;
                bilder.add(b.clone());
                ctx.sleep(110);
            }
            Optional<Hand> hand = hand(bilder, obenBis);
            if (hand.isEmpty()) return null;
            Hand h = hand.get();
            echteEinfuehrung = true;
            Log.action("Klick", Sprache.t("Introduction: copy the hand's move", "Einfuehrung: Bewegung der Hand nachziehen")
                    + "  [" + h.startX() + "," + h.startY() + " -> " + h.endeX() + "," + h.endeY() + "]");
            ctx.input().dragLangsam(h.startX(), h.startY(), h.endeX(), h.endeY());
            gehandelt();
            ctx.sleepUntilChanged(1500);
            return Ergebnis.GEHANDELT;
        } finally {
            for (Mat m : bilder) m.release();
        }
    }

    private boolean kaufKnopf(BotContext ctx, int x, int y) {
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return true;
        int x0 = Math.max(0, x - 70), y0 = Math.max(0, y - 25);
        int w = Math.min(bild.getWidth() - x0, 140), h = Math.min(bild.getHeight() - y0, 50);
        if (w < 20 || h < 10) return true;
        try {
            return kaufText(String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x0, y0, w, h))));
        } catch (Exception e) {
            return true;
        }
    }

    private void gehandelt() {
        aktionen++;
        letzteAktionUm = System.currentTimeMillis();
    }

    /** Einfuehrung vorbei: Event-Fenster ueber das rote X schliessen, dann soll der Motor zur Burg gehen. */
    private Ergebnis beenden(Mat screen, BotContext ctx, String woher) {
        boolean echt = echteEinfuehrung;
        int n = aktionen;
        zuruecksetzen();
        if (!echt) return Ergebnis.NICHTS;   // nur Erzaehltexte weggetippt -- der Modus macht einfach weiter
        Log.info(woher, Sprache.t("Introduction finished (" + n + " steps). Closing the event window, back to the castle.",
                "Einfuehrung fertig (" + n + " Schritte). Event-Fenster schliessen, zurueck zur Burg."));
        ctx.clickIfVisibleIfKnown(screen, "close_x", Sprache.t("Introduction finished: close the event window",
                "Einfuehrung fertig: Event-Fenster schliessen"));
        ctx.sleepUntilChanged(1500);
        return Ergebnis.FERTIG;
    }

    /**
     * Klappt es nicht: Fehlerbericht mit Bild, 10 min nicht mehr eingreifen, weiter wie bisher. Liefert BILD_VERALTET:
     * meist wurden vorher neue Bilder geholt, ein erneutes Aufnehmen schadet nie.
     */
    private Ergebnis aufgeben(Mat screen, BotContext ctx, String woher, String grund) {
        String text = Sprache.t("Event introduction could not be played through (" + grund + "). The bot continues as before.",
                "Einfuehrung eines Events liess sich nicht durchspielen (" + grund + "). Der Bot macht weiter wie bisher.");
        Log.warn(woher, text);
        if (!gemeldet) {
            gemeldet = true;
            MatOfByte png = new MatOfByte();
            try {
                if (screen == null || screen.empty()) screen = ctx.aktuellesBild();
                Imgcodecs.imencode(".png", screen, png);
                BotEngine.berichtMelden(text, png.toArray());
            } catch (Exception e) {
                Log.debug(woher, "Einfuehrung-Bericht: " + e.getMessage());
            } finally {
                png.release();
            }
        }
        zuruecksetzen();
        pauseBis = System.currentTimeMillis() + PAUSE_NACH_AUFGABE_MS;
        return Ergebnis.BILD_VERALTET;
    }

    private void zuruecksetzen() {
        sitzungSeit = 0;
        aktionen = 0;
        echteEinfuehrung = false;
        letztesZiel = null;
        gleichesZiel = 0;
        gleicheBlase = 0;
        if (letzteBlase != null) letzteBlase.release();
        letzteBlase = null;
    }

    /** Ausschnitt des Blasentexts, verkleinert und grau -- zum Vergleich "steht noch derselbe Satz da?". */
    private static Mat blasenBild(Mat screen, Matcher.Hit band) {
        int x = Math.max(0, band.x() - 60), y = Math.min(screen.height() - 2, band.y() + band.height() + 10);
        int w = Math.min(screen.width() - x, 700), h = Math.min(screen.height() - y, 110);
        Mat out = new Mat();
        if (w < 20 || h < 10) return out;
        Mat g = new Mat();
        Imgproc.cvtColor(screen.submat(new Rect(x, y, w, h)), g, Imgproc.COLOR_BGR2GRAY);
        Imgproc.resize(g, out, new Size(140, 22), 0, 0, Imgproc.INTER_AREA);
        g.release();
        return out;
    }

    private static boolean gleichesBild(Mat a, Mat b) {
        if (a.empty() || b.empty() || a.size().width != b.size().width || a.size().height != b.size().height) return false;
        Mat d = new Mat();
        Core.absdiff(a, b, d);
        double m = Core.mean(d).val[0];
        d.release();
        return m < 6;
    }
}
