package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** HC-Graben (Autor 18.10., Fotoserie): Blocksorten nach Farbe und grabbare Randfelder. */
class TitanWegTest {

    @Test
    void blockSortenNachFarbe() {
        assertEquals(1, TitanEventMode.blockKosten(206, 149, 92));    // hell (Sand)
        assertEquals(1, TitanEventMode.blockKosten(153, 96, 52));     // braun
        assertEquals(2, TitanEventMode.blockKosten(118, 112, 106));   // dunkelgrau
        assertEquals(2, TitanEventMode.blockKosten(176, 172, 167));   // hellgrau
        assertEquals(2, TitanEventMode.blockKosten(40, 200, 40));     // unbekannt
    }

    @Test
    void nurFelderNebenOffenenSindGrabbar() {
        boolean[][] offen = {
                {true, false, false},
                {false, false, false}};
        assertTrue(TitanEventMode.randFeld(offen, 0, 1));
        assertTrue(TitanEventMode.randFeld(offen, 1, 0));
        assertFalse(TitanEventMode.randFeld(offen, 1, 2));
    }
}
