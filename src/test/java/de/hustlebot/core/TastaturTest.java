package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tastenfolgen fuers Tippen (18.11., Clan-Chat) -- ohne Windows pruefbar, VkKeyScanEx-Werte von Hand. */
class TastaturTest {

    @Test
    void kleinerBuchstabeEineTaste() {
        // 'a' auf jeder Belegung: Taste A (0x41) ohne Umschalt
        List<Tastatur.Schritt> s = Tastatur.schritte('a', (short) 0x0041, Set.of());
        assertEquals(List.of(new Tastatur.Schritt(0x41, false, false), new Tastatur.Schritt(0x41, true, false)), s);
    }

    @Test
    void fragezeichenAufDeutscherTastaturMitUmschalt() {
        // Deutsch: '?' = Umschalt + ss-Taste (VK_OEM_4 = 0xDB) -> VkKeyScanEx 0x01DB
        List<Tastatur.Schritt> s = Tastatur.schritte('?', (short) 0x01DB, Set.of());
        assertEquals(List.of(
                new Tastatur.Schritt(0x10, false, false),
                new Tastatur.Schritt(0xDB, false, false),
                new Tastatur.Schritt(0xDB, true, false),
                new Tastatur.Schritt(0x10, true, false)), s);
    }

    @Test
    void altGrDrueckenStrgUndAlt() {
        // '@' deutsch = AltGr + Q -> 0x0651
        List<Tastatur.Schritt> s = Tastatur.schritte('@', (short) 0x0651, Set.of());
        assertEquals(6, s.size());
        assertEquals(0x11, s.get(0).taste());
        assertEquals(0x12, s.get(1).taste());
        assertEquals(0x51, s.get(2).taste());
        assertEquals(0x12, s.get(4).taste());
        assertEquals(0x11, s.get(5).taste());
    }

    @Test
    void unbekanntesZeichenAlsUnicode() {
        List<Tastatur.Schritt> s = Tastatur.schritte((char) 0x263A, (short) -1, Set.of());
        assertEquals(List.of(new Tastatur.Schritt(0x263A, false, true), new Tastatur.Schritt(0x263A, true, true)), s);
    }

    @Test
    void notAusTasteWirdNieGedrueckt() {
        // Not-Aus auf "g": das g geht als Unicode-Zeichen hinaus, nicht ueber die Taste G
        List<Tastatur.Schritt> s = Tastatur.schritte('g', (short) 0x0047, Set.of(0x47));
        assertTrue(s.stream().allMatch(Tastatur.Schritt::unicode));
    }
}
