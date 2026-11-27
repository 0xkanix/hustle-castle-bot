package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

/** Fotos direkt vom Spielfenster (foto-fenster.txt): Umrechnung in den Inhaltsbereich und Schwarz-Erkennung. */
class RobotSteuerungFensterTest {

    @Test
    void bereichImSpielfensterWirdUmgerechnet() {
        // Spiel wie bei Autor: Inhaltsbereich ab 763/31, 1159x770
        Rectangle rel = RobotSteuerung.imFenster(new Rectangle(763, 31, 1159, 770), 763, 31, 1159, 770);
        assertEquals(new Rectangle(0, 0, 1159, 770), rel);
        assertEquals(new Rectangle(100, 200, 300, 150),
                RobotSteuerung.imFenster(new Rectangle(863, 231, 300, 150), 763, 31, 1159, 770));
    }

    @Test
    void bereichAusserhalbGibtNull() {
        assertNull(RobotSteuerung.imFenster(new Rectangle(700, 31, 300, 150), 763, 31, 1159, 770));   // links raus
        assertNull(RobotSteuerung.imFenster(new Rectangle(763, 31, 1160, 770), 763, 31, 1159, 770));  // zu breit
        assertNull(RobotSteuerung.imFenster(new Rectangle(763, 700, 100, 120), 763, 31, 1159, 770));  // unten raus
        assertNull(RobotSteuerung.imFenster(new Rectangle(763, 31, 0, 10), 763, 31, 1159, 770));
        assertNull(RobotSteuerung.imFenster(new Rectangle(763, 31, 10, 10), 763, 31, 0, 0));          // minimiert
        assertNull(RobotSteuerung.imFenster(null, 0, 0, 100, 100));
    }

    @Test
    void schwarzesBildWirdErkannt() {
        assertTrue(RobotSteuerung.schwarz(new byte[3000]));
        assertTrue(RobotSteuerung.schwarz(null));
        byte[] spiel = new byte[3000];
        spiel[1500] = 0;
        for (int i = 0; i < spiel.length; i += 7) spiel[i] = (byte) 90;
        assertFalse(RobotSteuerung.schwarz(spiel));
    }
}
