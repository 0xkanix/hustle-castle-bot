package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: Nach dem Kisten-Oeffnen zaehlt die Nahrungsanzeige hoch. Zwischenwerte ueber dem alten Stand wurden nicht
 * nachgelesen ("reads 1 780 210, after the chests at least 1 980 210 -- misread"). Die Zahlen unten sind aus dem Protokoll.
 */
class NahrungNachlesenTest {

    @Test
    void zwischenwertBeimHochzaehlenWirdNachgelesen() {
        // 28.10. 00:43:42: vorher 35 210 + 1 945 000 -> mindestens 1 980 210; gelesen 1 780 210
        assertTrue(Nahrungskisten.nachlesenNoetig(1_780_210, 35_210, 1_980_210));
        // 28.10. 02:00:02 / 02:15:54 / 02:29:50
        assertTrue(Nahrungskisten.nachlesenNoetig(1_621_012, 95_543, 1_985_543));
        assertTrue(Nahrungskisten.nachlesenNoetig(1_540_321, 95_543, 1_985_543));
        assertTrue(Nahrungskisten.nachlesenNoetig(655_653, 95_543, 1_985_543));
    }

    @Test
    void alterWertWirdNachgelesenWieBisher() {
        // 27.10. 22:27:48: gelesen 84 914 = der Stand vor den Kisten
        assertTrue(Nahrungskisten.nachlesenNoetig(84_914, 84_914, 1_984_914));
        assertTrue(Nahrungskisten.nachlesenNoetig(0, 0, 2_000_000));
    }

    @Test
    void fertigeAnzeigeWirdNichtNochmalGelesen() {
        assertFalse(Nahrungskisten.nachlesenNoetig(1_984_914, 84_914, 1_984_914), "genau der Mindestwert");
        assertFalse(Nahrungskisten.nachlesenNoetig(2_000_000, 84_914, 1_984_914), "mehr als der Mindestwert (Produktion)");
        assertFalse(Nahrungskisten.nachlesenNoetig(1_530_896, -1, -1), "ohne Kisten und ohne Vorstand nichts zu vergleichen");
    }
}
