package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F34): Schriftgroesse klein / mittel / gross / sehr gross -- ein Faktor fuer Schriften und Masse.
 */
class SchriftgroesseTest {

    @Test
    void woerterWerdenZuStufen() {
        assertEquals(0, Holzstil.stufeAus("klein"));
        assertEquals(1, Holzstil.stufeAus("mittel"));
        assertEquals(2, Holzstil.stufeAus(" Gross "));
        assertEquals(3, Holzstil.stufeAus("RIESIG"));
        assertEquals(1, Holzstil.stufeAus(""), "ohne Angabe: mittel");
        assertEquals(1, Holzstil.stufeAus(null));
        assertEquals(1, Holzstil.stufeAus("winzig"), "unbekannt: mittel");
    }

    @Test
    void alleStufenHabenEinenWort() {
        for (int i = 0; i < Holzstil.SCHRIFT_STUFEN.length; i++) {
            assertEquals(i, Holzstil.stufeAus(Holzstil.SCHRIFT_STUFEN[i]));
        }
    }

    @Test
    void massWirdMitDemFaktorGerechnet() {
        assertTrue(Holzstil.SKALA >= 0.9 && Holzstil.SKALA <= 1.5);
        assertEquals((int) Math.round(38 * Holzstil.SKALA), Holzstil.px(38));
        assertEquals(Holzstil.px(38), Holzstil.HOEHE_GROSS);
        assertEquals(Holzstil.px(30), Holzstil.HOEHE_KLEIN);
        assertEquals(Holzstil.px(13), Holzstil.NORMAL.getSize());
        assertEquals(0, Holzstil.px(0));
    }
}
