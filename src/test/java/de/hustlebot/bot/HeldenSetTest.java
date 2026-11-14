package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Helden-Set fuer den Clan-Krieg (Ben 17.10.): Namen erkennen, Name und Platz eingeben. */
class HeldenSetTest {

    @Test
    void nameMussGanzPassen() {
        assertTrue(HeldenSetWechsel.passt(HeldenSetWechsel.normal("War"), HeldenSetWechsel.normal("war")));
        assertTrue(HeldenSetWechsel.passt(HeldenSetWechsel.normal(" War "), HeldenSetWechsel.normal("War")));
        // "War" darf nicht auf "Tank War" passen
        assertFalse(HeldenSetWechsel.passt(HeldenSetWechsel.normal("Tank War"), HeldenSetWechsel.normal("War")));
        // kurze Namen ohne Lesefehler-Toleranz
        assertFalse(HeldenSetWechsel.passt(HeldenSetWechsel.normal("Wan"), HeldenSetWechsel.normal("War")));
        // ab 5 Buchstaben ein falsch gelesener Buchstabe erlaubt
        assertTrue(HeldenSetWechsel.passt(HeldenSetWechsel.normal("Norrnal"), HeldenSetWechsel.normal("Normal")));
        assertFalse(HeldenSetWechsel.passt(HeldenSetWechsel.normal("Dailies"), HeldenSetWechsel.normal("Normal")));
        assertFalse(HeldenSetWechsel.passt("", "war"));
    }

    @Test
    void nameUndPlatzEingeben() {
        assertArrayEquals(new String[]{"War", "2"}, HeldenSet.nameUndPlatz("War 2", 1));
        assertArrayEquals(new String[]{"Tank War", "3"}, HeldenSet.nameUndPlatz("Tank War 3", 1));
        assertArrayEquals(new String[]{"Normal", "1"}, HeldenSet.nameUndPlatz("Normal", 1));
        assertArrayEquals(new String[]{"War", "2"}, HeldenSet.nameUndPlatz("  War   2 ", 5));
    }
}
