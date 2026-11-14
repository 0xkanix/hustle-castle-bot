package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 1 (Autor 01.11.): lieber knapp unter 150 Punkte als der einfachste Gegner. */
class ArenaStage1KnappTest {

    @Test
    void nurGegnerMitMittlererKraft() {
        assertFalse(ArenaMode.stage1Knapp(0.50), "zu leicht: Kampf nach 6-7 s, volle 150");
        assertFalse(ArenaMode.stage1Knapp(0.69));
        assertTrue(ArenaMode.stage1Knapp(0.70));
        assertTrue(ArenaMode.stage1Knapp(ArenaMode.STAGE1_KNAPP_ZIEL));
        assertTrue(ArenaMode.stage1Knapp(0.89));
        assertFalse(ArenaMode.stage1Knapp(0.90), "ab 0,9 nur noch 85 % gewonnen");
        assertFalse(ArenaMode.stage1Knapp(1.76), "der Spieler mit 74 Mio");
    }

    /** Arena 17:18: Enigma (sicher) in Stage 2 fuer 144 verbraucht, spaeter fehlte der 225er. */
    @Test
    void listenSpielerBleibenFuerSpaeter() {
        assertTrue(ArenaMode.fuerSpaeterAufheben(true, false, false));
        assertTrue(ArenaMode.fuerSpaeterAufheben(false, true, false));
        assertTrue(ArenaMode.fuerSpaeterAufheben(false, false, true));
        assertFalse(ArenaMode.fuerSpaeterAufheben(false, false, false));
    }

    /** Arena 15:14: 150, 150, 150, 150, 149 = 749 gegen 750, in Stage 5 kein 225er. */
    @Test
    void stageZweiUndDreiNurSolangeAllesVollIst() {
        assertTrue(ArenaMode.nochNichtUnter150(2, 150, false));
        assertTrue(ArenaMode.nochNichtUnter150(3, 300, false));
        assertFalse(ArenaMode.nochNichtUnter150(2, 149, false), "schon unter 150: fertig");
        assertFalse(ArenaMode.nochNichtUnter150(3, 299, false));
        assertFalse(ArenaMode.nochNichtUnter150(3, 375, false), "schon ein 225er dabei");
        assertFalse(ArenaMode.nochNichtUnter150(4, 450, false), "ab Stage 4 gilt die Top-3-Rechnung");
        assertFalse(ArenaMode.nochNichtUnter150(1, 0, false), "Stage 1 hat die eigene Wahl");
        assertFalse(ArenaMode.nochNichtUnter150(3, 300, true), "Punkte nicht sicher bekannt (Niederlage)");
    }
}
