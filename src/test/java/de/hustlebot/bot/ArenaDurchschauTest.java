package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 03.11.: unbekannte Teams vom schwaechsten zum staerksten ansehen; lange Laeufe beginnen mit einem vollen Eigen-Scan. */
class ArenaDurchschauTest {

    @Test
    void schwaechsterOffenerZuerst() {
        Map<String, Long> kraft = Map.of("a", 30_000_000L, "b", 25_000_000L, "c", 41_000_000L, "d", 28_000_000L);
        Set<String> offen = Set.of("a", "b", "c");   // d ist schon bekannt
        // c (41 M) wartet auf b (25 M), den schwaechsten offenen -- d ist schon durchgeschaut
        assertEquals("b", ArenaMode.schwaechererOffen("c", 41_000_000L, kraft, offen::contains));
        assertEquals("b", ArenaMode.schwaechererOffen("a", 30_000_000L, kraft, offen::contains));
        // b ist der schwaechste offene: sofort ansehen
        assertNull(ArenaMode.schwaechererOffen("b", 25_000_000L, kraft, offen::contains));
        // nichts mehr offen ausser c: c ist dran
        assertNull(ArenaMode.schwaechererOffen("c", 41_000_000L, kraft, Set.of("c")::contains));
    }

    @Test
    void langerLaufAb26ArenenOderDreiStunden() {
        long jetzt = 1_000_000_000L;
        assertFalse(ArenaMode.langerLauf(15, 0, jetzt));
        assertFalse(ArenaMode.langerLauf(25, 0, jetzt));
        assertTrue(ArenaMode.langerLauf(26, 0, jetzt));
        assertTrue(ArenaMode.langerLauf(100, 0, jetzt));
        assertTrue(ArenaMode.langerLauf(15, jetzt + 3 * 3_600_000L, jetzt));
        assertFalse(ArenaMode.langerLauf(15, jetzt + 2 * 3_600_000L, jetzt));
        assertFalse(ArenaMode.langerLauf(15, jetzt - 1, jetzt));   // Stoppzeit schon vorbei
    }
}
