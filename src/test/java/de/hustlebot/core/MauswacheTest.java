package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MauswacheTest {

    @Test
    void kleineAbweichungIstKeinMensch() {
        assertFalse(Mauswache.menschlich(100, 100, 100, 100));
        assertFalse(Mauswache.menschlich(102, 99, 100, 100));          // Wackeln beim Dauerklick, Rundung
        assertFalse(Mauswache.menschlich(130, 120, 100, 100));         // 36 px -- noch unter der Schwelle
        assertFalse(Mauswache.menschlich(180, 160, 100, 100));         // 100 px -- seit 21.10. auch noch (Schwelle 120)
    }

    @Test
    void grosseAbweichungIstEinMensch() {
        assertTrue(Mauswache.menschlich(250, 100, 100, 100));
        assertTrue(Mauswache.menschlich(100, 230, 100, 100));
    }

    @Test
    void ohneBotStelleNieAlarm() {
        assertFalse(Mauswache.menschlich(500, 500, Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void eineEinzelneSichtungStopptNicht() {
        // Autor 21.10.: erst die zweite Sichtung innerhalb von 2 s zaehlt
        assertFalse(Mauswache.zweiteSichtung(0, 10_000));
        assertTrue(Mauswache.zweiteSichtung(9_000, 10_000));
        assertFalse(Mauswache.zweiteSichtung(7_000, 10_000));
    }
}
