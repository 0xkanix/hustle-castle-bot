package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

/** Autor 25.10. (TR5): Items nach seiner Bewertung -- Werte, Punkte, leere Plaetze. */
class ArenaItemsTest {

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void werteUndPunkte() {
        assertEquals(ArenaItems.Wert.OP, ArenaItems.wertAusText("op"));
        assertEquals(ArenaItems.Wert.OP, ArenaItems.wertAusText("Unglaublich OP"));
        assertEquals(ArenaItems.Wert.SCHLECHT, ArenaItems.wertAusText(" schlecht "));
        assertNull(ArenaItems.wertAusText("?"));
        assertEquals(3, ArenaItems.punkte(ArenaItems.Wert.GUT));
        assertEquals(3, ArenaItems.punkte(ArenaItems.Wert.OP));
        assertEquals(1, ArenaItems.punkte(ArenaItems.Wert.MITTEL));
        assertEquals(-1, ArenaItems.punkte(ArenaItems.Wert.NEUTRAL));
        assertEquals(-3, ArenaItems.punkte(ArenaItems.Wert.SCHLECHT));
        assertEquals(0, ArenaItems.punkte(null));
    }

    @Test
    void kennungWieAufDerSeite() {
        BufferedImage b = new BufferedImage(102, 102, BufferedImage.TYPE_INT_RGB);
        String id = ArenaItems.kennung("waffe", ArenaItems.fingerabdruck(b));
        assertTrue(id.matches("waffe-[0-9a-f]{10}"), id);
    }

    @Test
    void leererPlatzFaelltWeg() {
        // Ganzes Fenster tuerkis (leere Plaetze) -> nichts gelesen; dunkelrot -> alle fuenf Plaetze belegt.
        Mat leer = new Mat(770, 1159, CvType.CV_8UC3, new Scalar(200, 200, 60));   // BGR: tuerkis
        assertTrue(ArenaItems.lesen(leer).isEmpty());
        Mat voll = new Mat(770, 1159, CvType.CV_8UC3, new Scalar(30, 30, 120));
        var plaetze = ArenaItems.lesen(voll);
        assertEquals(5, plaetze.size());
        assertEquals(9, plaetze.get(0).size());   // Mitte + 8 verschobene
        assertEquals("waffe", plaetze.get(0).get(0).platz());
        leer.release();
        voll.release();
    }
}
