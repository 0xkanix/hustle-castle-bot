package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stein-Regel (Autor 22.10.): legendaer (orange) -0,1, episch (violett) -0,05 je Stein. */
class ArenaSteineTest {

    @Test
    void orangeUndViolettWerdenGetrenntGezaehlt() {
        ArenaSteine.Zaehlung z = new ArenaSteine.Zaehlung(1, 0, 4, 1, 2, List.of(
                List.of(ArenaSteine.Art.ORANGE, ArenaSteine.Art.ORANGE, ArenaSteine.Art.GELB),
                List.of(ArenaSteine.Art.VIOLETT, ArenaSteine.Art.ANDERE, ArenaSteine.Art.LEER)));
        assertEquals(2, z.orange());
        assertEquals(1, z.violett());
    }

    @Test
    void abzugJeStein() {
        assertEquals(0.0, ArenaSteine.abzug(0, 0), 1e-9);
        assertEquals(-1.2, ArenaSteine.abzug(12, 0), 1e-9);
        assertEquals(-0.4, ArenaSteine.abzug(0, 8), 1e-9);
        assertEquals(-2.5, ArenaSteine.abzug(20, 10), 1e-9);
    }
}
