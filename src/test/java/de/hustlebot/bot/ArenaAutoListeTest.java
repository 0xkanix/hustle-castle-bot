package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Automatische Arena-Listen (Autor 21.10.): 2 Siege -> machbar, 3 Siege -> sicher. */
class ArenaAutoListeTest {

    @Test
    void zweiSiegeMachbarDreiSiegeSicher() {
        assertNull(ArenaMode.autoListe(0, 0));
        assertNull(ArenaMode.autoListe(1, 1));
        assertEquals(ArenaMode.AutoListe.MACHBAR, ArenaMode.autoListe(2, 2));
        assertEquals(ArenaMode.AutoListe.SICHER, ArenaMode.autoListe(3, 3));
        assertEquals(ArenaMode.AutoListe.SICHER, ArenaMode.autoListe(7, 7));
    }

    @Test
    void niederlagenBremsen() {
        // 2 Siege, 1 Niederlage: noch machbar
        assertEquals(ArenaMode.AutoListe.MACHBAR, ArenaMode.autoListe(2, 3));
        // 2 Siege, 2 Niederlagen: keine Liste
        assertNull(ArenaMode.autoListe(2, 4));
        // 3 Siege, 1 Niederlage: sicher; 3 Siege, 2 Niederlagen: nur machbar
        assertEquals(ArenaMode.AutoListe.SICHER, ArenaMode.autoListe(3, 4));
        assertEquals(ArenaMode.AutoListe.MACHBAR, ArenaMode.autoListe(3, 5));
        // 3 Siege, 5 Niederlagen: keine Liste
        assertNull(ArenaMode.autoListe(3, 8));
    }
}
