package de.hustlebot.core;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;

/**
 * Erkennt das Captcha "Suspicious magical activity" (Goblin mit Fliegerbrille links im Fenster).
 *
 * 15.11. / X3A2 (Ben, 31.10.): Seit dem Ausbau am 29.10. (bf688d60) wusste der Bot nichts mehr vom Captcha, drueckte
 * zwoelf Mal das hintere rote X (ohne Wirkung) und stoppte nach 2 Minuten. Diese Erkennung ist bewusst klein: sie sagt
 * nur "da ist eins". Der Bot haelt dann an, ruft den Menschen und loest NIE selbst.
 *
 * Vorlage: der Goblin-Kopf aus dem echten Spielbild von X3A2 (resources/captcha/goblin.png, 255 x 165 bei 1159 x 770
 * Fensterbild). Das Fenster kann anders gross sein, darum wird in mehreren Massstaeben gesucht (auf halber Aufloesung).
 */
public final class Captcha {

    /** Ab diesem Wert (Korrelation 0..1) gilt der Goblin als gefunden. Echtes Bild: ~1,0; normale Spielbilder: < 0,4. */
    public static final double SCHWELLE = 0.70;
    private static final double[] MASSSTAEBE = massstaebe();

    /** 0,6 bis 1,8 in 0,05er Schritten (grober waere bei Fenstern zwischen den Stufen zu ungenau). */
    private static double[] massstaebe() {
        double[] m = new double[25];
        for (int i = 0; i < m.length; i++) m[i] = 0.6 + 0.05 * i;
        return m;
    }
    private static final double KLEIN = 0.5;

    private static volatile Mat vorlage;

    private Captcha() {
    }

    private static synchronized Mat vorlage() {
        if (vorlage == null) {
            try (InputStream in = Captcha.class.getResourceAsStream("/captcha/goblin.png")) {
                if (in == null) return null;
                Mat bild = Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_GRAYSCALE);
                if (bild.empty()) return null;
                Mat klein = new Mat();
                Imgproc.resize(bild, klein, new Size(), KLEIN, KLEIN, Imgproc.INTER_AREA);
                bild.release();
                vorlage = klein;
            } catch (Exception e) {
                return null;
            }
        }
        return vorlage;
    }

    /** Bester Trefferwert des Goblins im Bild (0..1); -1, wenn die Vorlage fehlt oder das Bild leer ist. */
    public static double wert(Mat screen) {
        Mat v = vorlage();
        if (v == null || screen == null || screen.empty()) return -1;
        Mat grau = new Mat(), klein = new Mat();
        try {
            if (screen.channels() == 1) screen.copyTo(grau);
            else Imgproc.cvtColor(screen, grau, screen.channels() == 4 ? Imgproc.COLOR_BGRA2GRAY : Imgproc.COLOR_BGR2GRAY);
            Imgproc.resize(grau, klein, new Size(), KLEIN, KLEIN, Imgproc.INTER_AREA);
            double best = 0;
            for (double s : MASSSTAEBE) {
                Mat t = new Mat(), erg = new Mat();
                try {
                    Imgproc.resize(v, t, new Size(), s, s, s < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
                    if (t.cols() >= klein.cols() || t.rows() >= klein.rows()) continue;
                    Imgproc.matchTemplate(klein, t, erg, Imgproc.TM_CCOEFF_NORMED);
                    double m = Core.minMaxLoc(erg).maxVal;
                    if (m > best) best = m;
                } finally {
                    t.release();
                    erg.release();
                }
            }
            return best;
        } finally {
            grau.release();
            klein.release();
        }
    }

    public static boolean erkannt(Mat screen) {
        return wert(screen) >= SCHWELLE;
    }
}
