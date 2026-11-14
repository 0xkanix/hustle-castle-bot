package de.hustlebot.bot;

import de.hustlebot.core.ArenaRegelnNeu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Punkte und Turniere nicht doppelt zaehlen, Vorausrechnung ohne Sprung (Auswertung 30.10.). */
class ArenaZaehlungTest {

    @AfterEach
    void zuruecksetzen() {
        ArenaRegelnNeu.testWert(null);
    }

    @Test
    void dasselbeSiegfensterZaehltEinmal() {
        // 01:26:56 xMeowth 150 / 7 s, 01:27:05 ohne Gegner 150 / 7 s
        assertTrue(ArenaMode.doppeltesErgebnis(true, 150, 7, 150, 7, 9_000));
        assertFalse(ArenaMode.doppeltesErgebnis(false, 150, 7, 150, 7, 9_000), "mit Gegner ist es ein neuer Kampf");
        assertFalse(ArenaMode.doppeltesErgebnis(true, 216, 15, 150, 7, 9_000), "andere Zahlen");
        assertFalse(ArenaMode.doppeltesErgebnis(true, 150, 7, 150, 7, 120_000), "naechste Runde");
    }

    @Test
    void notausstiegIstKeinNeuesTurnier() {
        assertTrue(ArenaMode.turnierDoppelt(0, 5 * 60_000L, true), "04:12 und 04:17");
        assertFalse(ArenaMode.turnierDoppelt(5, 5 * 60_000L, true), "mit Kaempfen ist es ein Turnier");
        assertFalse(ArenaMode.turnierDoppelt(0, 7 * 60_000L, true), "alles verpasst, aber ein eigenes Turnier");
        assertFalse(ArenaMode.turnierDoppelt(0, 1000, false), "das erste Ende seit dem Start zaehlt");
    }

    @Test
    void mehrPunkteHeuteHeissenNieWenigerAmEnde() {
        ArenaRegelnNeu.testWert(true);
        double[] andere = {600, 594, 450};
        boolean[] pokale = new boolean[3];
        double vorher = -1;
        for (int g = 0; g <= 300; g += 3) {
            double ende = 450 + g + ArenaTabelle.zukunft(450 + g, andere, pokale, 2);
            assertTrue(ende >= vorher, "bei " + g + " Punkten");
            vorher = ende;
        }
        assertEquals(2 * ArenaTabelle.JE_RUNDE_ICH + ArenaTabelle.OHNE_SPITZE_BONUS, ArenaTabelle.zukunft(400, andere, pokale, 2));
        assertEquals(2 * ArenaTabelle.JE_RUNDE_ICH + 30, ArenaTabelle.zukunft(570, andere, pokale, 2));
        assertEquals(2 * ArenaTabelle.JE_RUNDE_ICH, ArenaTabelle.zukunft(700, andere, pokale, 2));
    }

    @Test
    void schalterAusWieBisher() {
        ArenaRegelnNeu.testWert(false);
        double[] andere = {600};
        boolean[] pokale = {true};
        assertEquals(ArenaTabelle.JE_RUNDE_ICH + ArenaTabelle.OHNE_SPITZE_BONUS, ArenaTabelle.zukunft(599, andere, pokale, 1));
        assertEquals(ArenaTabelle.JE_RUNDE_ICH, ArenaTabelle.zukunft(601, andere, pokale, 1));
    }
}
