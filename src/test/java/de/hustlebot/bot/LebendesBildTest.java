package de.hustlebot.bot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bildfehler-Suche 30.10.: Nach Nahrungskisten.nahrungLesen (holt selbst frische Bilder) suchten Gem Bay, Tasche und
 * Fenster-X im selben Schritt mit dem alten, schon freigegebenen Bild weiter -- 664 Mal "screen 0x0" im Oktober, jeder
 * Blick fand nichts. Jetzt gilt dann das neueste Bild.
 */
class LebendesBildTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void freigegebenesBildWirdDurchDasNeuesteErsetzt() {
        Mat alt = new Mat(770, 1159, CvType.CV_8UC3, new Scalar(10, 20, 30));
        Mat neu = new Mat(770, 1159, CvType.CV_8UC3, new Scalar(40, 50, 60));
        // so sieht der Modus sein Bild nach refresh(): freigegeben = 0x0
        alt.release();
        assertTrue(alt.empty(), "ein freigegebenes Bild ist leer (0x0) -- genau das stand in der Warnung");
        assertSame(neu, BotContext.lebendesBild(alt, neu));
        neu.release();
    }

    @Test
    void gueltigesBildBleibtUndNullBleibtNull() {
        Mat bild = new Mat(10, 10, CvType.CV_8UC3, new Scalar(1, 2, 3));
        Mat neu = new Mat(10, 10, CvType.CV_8UC3, new Scalar(4, 5, 6));
        assertSame(bild, BotContext.lebendesBild(bild, neu), "ein lebendes Bild wird nie ersetzt");
        assertNull(BotContext.lebendesBild(null, neu), "null bleibt null (kein Bild = wie bisher)");
        Mat leer = new Mat();
        assertSame(leer, BotContext.lebendesBild(leer, null), "ohne neueres Bild bleibt es beim alten");
        Mat auchLeer = new Mat();
        assertSame(leer, BotContext.lebendesBild(leer, auchLeer), "ein leeres neuestes Bild hilft nicht");
        bild.release();
        neu.release();
    }
}
