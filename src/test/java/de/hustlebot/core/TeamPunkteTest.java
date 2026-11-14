package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Autor 22.10.: je Einheit, die ein Gegner weniger hat als du, +12 Punkte (Bauarbeiter zaehlen als Einheit). */
class TeamPunkteTest {

    @Test
    void jeFehlendeEinheitZwoelf() {
        int vorher = Gegnerbuch.meineEinheiten();
        try {
            Gegnerbuch.setMeineEinheiten(6);
            assertEquals(30, Gegnerbuch.teamPunkte(30, 0, 0, 6));
            assertEquals(30 + 12, Gegnerbuch.teamPunkte(30, 0, 0, 5));
            assertEquals(30 + 24 + 3 + 5, Gegnerbuch.teamPunkte(30, 1, 1, 4));
            assertEquals(30, Gegnerbuch.teamPunkte(30, 0, 0, 7));   // mehr Einheiten als du: kein Abzug
            Gegnerbuch.setMeineEinheiten(7);
            assertEquals(30 + 12, Gegnerbuch.teamPunkte(30, 0, 0, 6));
            assertEquals(30, Gegnerbuch.teamPunkte(30, 0, 0, -1));   // nicht gezaehlt: nichts dazu
        } finally {
            Gegnerbuch.setMeineEinheiten(vorher);
        }
    }
}
