package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Turnier-Balken der Arena oben in der Burg (Autor, 11.10.): "Wait for the next stage of the
 * tournament", "Time left to select an opponent". Hier nur die Texterkennung des gelesenen Textes --
 * mit den ueblichen Lesefehlern (Gross/Klein, Zeilenumbrueche, Zeichen dazwischen).
 */
class TurnierbalkenTest {

    @Test
    void dieBeidenBalkenTexteWerdenErkannt() {
        assertTrue(Turnierbalken.erkennt("Wait for the next stage of the tournament: 1m 13s"));
        assertTrue(Turnierbalken.erkennt("Time left to select an opponent: 27s"));
        assertTrue(Turnierbalken.erkennt("The tournament ends in 2m 05s"));
    }

    @Test
    void lesefehlerStoerenNicht() {
        assertTrue(Turnierbalken.erkennt("WAIT FOR THE NEXT STAGE"));
        assertTrue(Turnierbalken.erkennt("1 034 601  Time  left\nto select  an opponent 12s"));
        assertTrue(Turnierbalken.erkennt("next-stage of the  tournament"));
        assertTrue(Turnierbalken.erkennt("select  an\nopponent"));
    }

    @Test
    void burgLeisteOhneBalkenIstKeinBalken() {
        assertFalse(Turnierbalken.erkennt(null));
        assertFalse(Turnierbalken.erkennt(""));
        assertFalse(Turnierbalken.erkennt("1 034 601   250 000   1 200"));
        assertFalse(Turnierbalken.erkennt("Tournament"));
        assertFalse(Turnierbalken.erkennt("Clan war starts in 12m"));
    }
}
