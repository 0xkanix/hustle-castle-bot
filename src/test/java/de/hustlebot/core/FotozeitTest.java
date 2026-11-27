package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FotozeitTest {

    private static final long MS = 1_000_000;

    @Test
    void ersteMinuteOhneZeile() {
        Fotozeit f = new Fotozeit();
        assertNull(f.merke(30 * MS, 1_000));
        assertNull(f.merke(40 * MS, 30_000));
        assertNull(f.merke(50 * MS, 60_999));
    }

    @Test
    void nachEinerMinuteMedianUndLangsamstes() {
        Fotozeit f = new Fotozeit();
        f.merke(30 * MS, 0);
        f.merke(90 * MS, 10_000);
        f.merke(20 * MS, 20_000);
        f.merke(31 * MS, 30_000);
        assertEquals("Fotozeit: 5 Fotos in 60 s, Median 31 ms, langsamstes 90 ms", f.merke(40 * MS, 60_000));
    }

    @Test
    void danachBeginntNeuerAbschnitt() {
        Fotozeit f = new Fotozeit();
        f.merke(500 * MS, 0);
        f.merke(10 * MS, 60_000);
        assertNull(f.merke(12 * MS, 61_000));
        assertEquals("Fotozeit: 2 Fotos in 60 s, Median 14 ms, langsamstes 14 ms", f.merke(14 * MS, 120_000));
    }

    @Test
    void ueberlaufZaehltWeiterMedianAusDenErsten() {
        Fotozeit f = new Fotozeit();
        for (int i = 0; i < Fotozeit.PLATZ + 100; i++) f.merke(5 * MS, i);
        String z = f.merke(7 * MS, 60_000);
        assertEquals("Fotozeit: " + (Fotozeit.PLATZ + 101) + " Fotos in 60 s, Median 5 ms, langsamstes 7 ms", z);
    }

    @Test
    void negativeDauerZaehltAlsNull() {
        Fotozeit f = new Fotozeit();
        f.merke(-5 * MS, 0);
        assertEquals("Fotozeit: 2 Fotos in 60 s, Median 0 ms, langsamstes 0 ms", f.merke(0, 60_000));
    }
}
