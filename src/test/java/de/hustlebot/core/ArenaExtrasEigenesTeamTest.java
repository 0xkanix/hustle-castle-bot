package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Der Schalter "eigenesteam aus" in arena-extras.txt (Auftrag 6, 11.10.): nur die Zeilen-Regel,
 * ohne die echte Datei im Projektordner anzufassen.
 */
class ArenaExtrasEigenesTeamTest {

    @Test
    void zeileErkennen() {
        assertTrue(ArenaExtras.istEigenesTeamAus("eigenesteam aus"));
        assertTrue(ArenaExtras.istEigenesTeamAus("  Eigenes Team: aus "));
        assertTrue(ArenaExtras.istEigenesTeamAus("eigenesteam=aus"));
        assertFalse(ArenaExtras.istEigenesTeamAus("# eigenesteam aus"));
        assertFalse(ArenaExtras.istEigenesTeamAus("eigenesteam an"));
        assertFalse(ArenaExtras.istEigenesTeamAus("alles"));
        assertFalse(ArenaExtras.istEigenesTeamAus(null));
    }
}
