package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Autor 22.10.: Punkte fuer die Kraft einer Einheit -- 7 Mio +2, 8 Mio +3, 9 Mio +4, 10 Mio +5, darunter nichts. */
class ArenaKraftTest {

    @Test
    void stufen() {
        assertEquals(0, ArenaPruefer.kraftBonus(6_999_999));
        assertEquals(2, ArenaPruefer.kraftBonus(7_559_028));   // Autors Bogen 2
        assertEquals(3, ArenaPruefer.kraftBonus(8_000_000));
        assertEquals(4, ArenaPruefer.kraftBonus(9_400_000));
        assertEquals(5, ArenaPruefer.kraftBonus(10_000_000));
        assertEquals(6, ArenaPruefer.kraftBonus(11_200_000));
    }
}
