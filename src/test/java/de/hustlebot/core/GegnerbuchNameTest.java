package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Autor 19.10.: fast gleiche Namen (und gleiche Kraft) sind derselbe Spieler -- "JkNXED" = "JtNXED". */
class GegnerbuchNameTest {

    @Test
    void einLesefehlerIstDerselbe() {
        assertTrue(Gegnerbuch.derselbe("jknxed", "jtnxed", 0, 0));
        // Autor 22.10.: gleiche Kampfkraft auf die Einerstelle = derselbe Spieler, auch bei ganz anders gelesenem Namen.
        assertTrue(Gegnerbuch.derselbe("蛎茶多蛎走冰加蛎峯", "響蛎茶多", 30_497_325L, 30_497_325L));
        assertFalse(Gegnerbuch.derselbe("蛎茶多蛎走冰加蛎峯", "響蛎茶多", 30_497_325L, 30_497_326L));
        assertFalse(Gegnerbuch.derselbe("abcdefg", "xyzuvw", 500_000L, 500_000L));
        assertTrue(Gegnerbuch.derselbe("sapmanei", "sapmanei", 0, 0));
        assertTrue(Gegnerbuch.derselbe("moises", "moisess", 0, 0));
    }

    @Test
    void zweiLesefehlerNurMitGleicherKraft() {
        assertFalse(Gegnerbuch.derselbe("jknxeb", "jtnxed", 0, 0));
        assertTrue(Gegnerbuch.derselbe("jknxeb", "jtnxed", 34_737_932, 34_790_082));
        assertFalse(Gegnerbuch.derselbe("jknxeb", "jtnxed", 34_737_932, 20_000_000));
        assertTrue(Gegnerbuch.derselbe("tilt", "till", 1_000_000, 1_010_000));
    }

    @Test
    void andereNamenBleibenGetrennt() {
        assertFalse(Gegnerbuch.derselbe("tilt", "till", 0, 0));
        assertFalse(Gegnerbuch.derselbe("moises", "sapmanei", 1_000_000, 1_000_000));
    }

    @Test
    void clanMitZeichenDavorIstDerselbe() {
        assertTrue(Gegnerbuch.clanAehnlich("Июльский", "Щ Июльский Е"));
        assertTrue(Gegnerbuch.clanAehnlich("Night Legion", "V Night Legion"));
        assertFalse(Gegnerbuch.clanAehnlich("end", "The Legend"));
        assertFalse(Gegnerbuch.clanAehnlich("Gabriel", "Enigma"));
    }
}
