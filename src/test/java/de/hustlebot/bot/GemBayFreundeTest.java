package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import de.hustlebot.bot.GemBayGrenzen.PreisArt;
import org.junit.jupiter.api.Test;

/** Gem Bay fuer Freunde (Autor, 20.11.): vorsichtiger Standard, gembay-mehr.txt oeffnet. */
class GemBayFreundeTest {

    @Test
    void freundNurKostenlos() {
        assertTrue(GemBayGrenzen.reiseErlaubt(false, false, PreisArt.FREI));
        assertFalse(GemBayGrenzen.reiseErlaubt(false, false, PreisArt.NAHRUNG));
        assertFalse(GemBayGrenzen.reiseErlaubt(false, false, PreisArt.ANDERER_PREIS));
        assertFalse(GemBayGrenzen.reiseErlaubt(false, false, PreisArt.UNKLAR));
        assertFalse(GemBayGrenzen.kistenErlaubt(false, false));
    }

    @Test
    void mehrSchalterUndAutorSpielenWieBisher() {
        for (boolean[] w : new boolean[][] {{true, false}, {false, true}, {true, true}}) {
            assertTrue(GemBayGrenzen.reiseErlaubt(w[0], w[1], PreisArt.FREI));
            assertTrue(GemBayGrenzen.reiseErlaubt(w[0], w[1], PreisArt.NAHRUNG));
            assertFalse(GemBayGrenzen.reiseErlaubt(w[0], w[1], PreisArt.ANDERER_PREIS));
            assertTrue(GemBayGrenzen.kistenErlaubt(w[0], w[1]));
        }
    }
}
