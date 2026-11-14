package de.hustlebot.bot;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

/** Autors Bewertungen aus den Boegen: derselbe Vergleich wie bogen.py, ohne neue Vorlagen (Autor 30.10.). */
class ArenaBewertungTest {

    @BeforeAll
    static void cv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static String zeile(String art, String wahl, float wert) {
        StringJoiner j = new StringJoiner(",");
        for (int i = 0; i < 192; i++) j.add(String.valueOf(wert));
        return art + "\t" + wahl + "\tabc\t" + j;
    }

    private static float[] alle(float wert) {
        float[] m = new float[192];
        java.util.Arrays.fill(m, wert);
        return m;
    }

    @Test
    void nimmtDenAehnlichsten() {
        List<String> z = List.of(zeile("totems", "gut", 100), zeile("totems", "schlecht", 130), zeile("artefakte", "mittel", 100));
        assertEquals("gut", ArenaBewertung.wertAusZeilen(z, "totems", alle(105)));
        assertEquals("schlecht", ArenaBewertung.wertAusZeilen(z, "totems", alle(125)));
        assertNull(ArenaBewertung.wertAusZeilen(z, "totems", alle(200)), "zu weit weg: unbekannt, zaehlt nicht");
    }

    @Test
    void schwelleWieAufDenBoegen() {
        List<String> z = List.of(zeile("talente", "gut", 100), zeile("artefakte", "gut", 100));
        assertEquals("gut", ArenaBewertung.wertAusZeilen(z, "artefakte", alle(115)), "Artefakte bis 18");
        assertNull(ArenaBewertung.wertAusZeilen(z, "talente", alle(115)), "die anderen bis 9");
        assertNull(ArenaBewertung.wertAusZeilen(z, "totems", alle(100)), "andere Art");
    }

    @Test
    void kaputteZeilenStoerenNicht() {
        List<String> z = List.of("", "totems\tgut", "totems\tgut\tk\t1,2,3", zeile("totems", "gut", 50));
        assertEquals("gut", ArenaBewertung.wertAusZeilen(z, "totems", alle(50)));
    }

    @Test
    void merkmalWieInBogenPy() {
        // OpenCV speichert BGR, die Boegen rechnen in RGB: Rot muss vorn stehen.
        Mat m = new Mat(46, 84, CvType.CV_8UC3, new Scalar(10, 20, 30));
        try {
            float[] v = ArenaBewertung.merkmal(m, 0, 0, 84, 46);
            assertEquals(192, v.length);
            assertEquals(30f, v[0]);
            assertEquals(20f, v[1]);
            assertEquals(10f, v[2]);
            assertNull(ArenaBewertung.merkmal(m, 10, 0, 84, 46), "ragt hinaus");
        } finally {
            m.release();
        }
        BufferedImage b = new BufferedImage(84, 46, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 46; y++) for (int x = 0; x < 84; x++) b.setRGB(x, y, (30 << 16) | (20 << 8) | 10);
        assertArrayEquals(new float[]{30f, 20f, 10f}, java.util.Arrays.copyOf(ArenaBewertung.merkmal(b, 0, 0, 84, 46), 3));
    }

    @Test
    void einFeldKostetKaumZeit() {
        List<String> z = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) z.add(zeile("totems", "schlecht", i % 200));
        Mat m = new Mat(103, 388, CvType.CV_8UC3, new Scalar(10, 20, 30));
        try {
            float[] v = ArenaBewertung.merkmal(m, 8, 5, 84, 46);
            ArenaBewertung.wertAusZeilen(z, "totems", v);   // warm laufen lassen
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) ArenaBewertung.merkmal(m, 8, 5, 84, 46);
            long jeFeldMs = (System.nanoTime() - start) / 100 / 1_000_000;
            assertTrue(jeFeldMs < 5, "Merkmal je Feld: " + jeFeldMs + " ms");
        } finally {
            m.release();
        }
    }
}
