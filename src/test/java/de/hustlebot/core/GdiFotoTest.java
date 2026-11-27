package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** Sparsam 20.11.: Foto per GDI in einen BGR-Puffer (GdiFoto), auch bei ungerader Breite (DIB-Zeilen auf 4 Bytes). */
class GdiFotoTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Anteil gleicher Bildpunkte im gemeinsamen Bereich. */
    private static double gleich(BufferedImage a, BufferedImage b, int w, int h) {
        int n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((a.getRGB(x, y) & 0xFFFFFF) == (b.getRGB(x, y) & 0xFFFFFF)) n++;
        return n / (double) (w * h);
    }

    @Test
    void bgrBildInRichtigerGroesseUndZeilenRichtigUmgebrochen() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "kein Bildschirm");
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        try (GdiFoto gdi = new GdiFoto()) {
            double best = 0;
            for (int versuch = 0; versuch < 3 && best < 0.95; versuch++) {
                BufferedImage ungerade = gdi.foto(new Rectangle(0, 0, 63, 31), GdiFoto.Quelle.BILDSCHIRM, null);
                BufferedImage gerade = gdi.foto(new Rectangle(0, 0, 64, 31), GdiFoto.Quelle.BILDSCHIRM, null);
                assertNotNull(ungerade);
                assertNotNull(gerade);
                assertEquals(BufferedImage.TYPE_3BYTE_BGR, ungerade.getType());
                assertEquals(63, ungerade.getWidth());
                assertEquals(31, ungerade.getHeight());
                best = Math.max(best, gleich(ungerade, gerade, 63, 31));
            }
            assertTrue(best >= 0.95, "Zeilen verrutscht? gleich: " + best);
            // Der Bot uebernimmt das Bild ohne Umzeichnen in die Matrix -- gleiche Werte wie das Bild selbst
            BufferedImage b = gdi.foto(new Rectangle(0, 0, 40, 20), GdiFoto.Quelle.BILDSCHIRM, null);
            Mat m = ScreenCapture.toMat(b);
            double[] bgr = m.get(5, 7);
            int p = b.getRGB(7, 5);
            assertEquals(p & 255, (int) bgr[0]);
            assertEquals((p >> 8) & 255, (int) bgr[1]);
            assertEquals((p >> 16) & 255, (int) bgr[2]);
            m.release();
        }
    }
}
