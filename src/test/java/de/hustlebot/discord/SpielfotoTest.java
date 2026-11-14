package de.hustlebot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** Fotos nur vom Spielfenster (16.10.): die Umrechnung der Fensterbytes und der Ersatz bei fehlendem Fenster. */
class SpielfotoTest {

    @Test
    void bgraWirdZuRgb() {
        // 2x2 Punkte: rot, gruen / blau, weiss -- als B,G,R,A
        byte[] bgra = {
                0, 0, (byte) 255, (byte) 255,     (byte) 0, (byte) 255, 0, (byte) 255,
                (byte) 255, 0, 0, (byte) 255,     (byte) 255, (byte) 255, (byte) 255, 0 };
        BufferedImage b = Spielfoto.bgraZuBild(bgra, 2, 2);
        assertEquals(BufferedImage.TYPE_INT_RGB, b.getType());
        assertEquals(0xFF0000, b.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x00FF00, b.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(0x0000FF, b.getRGB(0, 1) & 0xFFFFFF);
        assertEquals(0xFFFFFF, b.getRGB(1, 1) & 0xFFFFFF);
    }

    @Test
    void ohneFensterKommtNull() {
        try (Spielfoto s = new Spielfoto()) {
            assertNull(s.aufnehmen(null));
        }
    }
}
