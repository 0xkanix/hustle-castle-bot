package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Autor 03.11.: in Stage 5 zeigte das offene Kampfprotokoll oben Stage 1/2 -- die oberste Ueberschrift wird gelesen. */
class ArenaProtokollStufeTest {

    @Test
    void obersteStageUeberschrift() {
        assertEquals(2, ArenaMode.protokollStufeOben("Stage 2"));
        assertEquals(5, ArenaMode.protokollStufeOben("A ! Stage 5"));
        assertEquals(3, ArenaMode.protokollStufeOben("Stage: 3"));
        assertEquals(4, ArenaMode.protokollStufeOben("st@ge 4"));
        assertEquals(-1, ArenaMode.protokollStufeOben("+0 DEFEAT"));
        assertEquals(-1, ArenaMode.protokollStufeOben(""));
        assertEquals(-1, ArenaMode.protokollStufeOben(null));
    }
}
