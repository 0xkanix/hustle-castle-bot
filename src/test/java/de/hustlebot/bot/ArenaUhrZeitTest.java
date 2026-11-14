package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefer 29.10. (3.112): Runde 1 in 4 von 4 Turnieren verpasst. Der Bot meldete "Round 1, 16 s left", die Runde war
 * 0,5-6 s spaeter vorbei. Die Uhr wurde von einem Bild gelesen, das schon 16 s alt war (Schilder lesen davor), galt
 * aber ab "jetzt". Dazu kosteten Schicht und Kampfprotokoll kurz vor dem Angriff noch 4-16 s.
 */
class ArenaUhrZeitTest {

    @Test
    void uhrGiltAbDerAufnahmeDesBildes() {
        long bild = 1_000_000L, jetzt = bild + 16_000;   // 16 s fuer die Schilder zwischen Aufnahme und Ablesen
        long gilt = ArenaMode.uhrGiltAb(bild, jetzt);
        assertEquals(bild, gilt);
        // 32 s auf der Uhr im alten Bild -> jetzt sind es nur noch 16, nicht 32.
        long rest = 32 - (jetzt - gilt) / 1000;
        assertEquals(16, rest);
    }

    @Test
    void ohneAufnahmezeitOderUnsinnGiltJetzt() {
        long jetzt = 5_000_000L;
        assertEquals(jetzt, ArenaMode.uhrGiltAb(0, jetzt));               // noch nie ein Bild
        assertEquals(jetzt, ArenaMode.uhrGiltAb(jetzt + 500, jetzt));     // Bild "aus der Zukunft"
        assertEquals(jetzt, ArenaMode.uhrGiltAb(jetzt - 4 * 60_000L, jetzt));   // uralt: nicht trauen
    }

    @Test
    void schichtNichtKurzVorDemEigenenAngriff() {
        long spaetestens = 25;
        assertTrue(ArenaMode.schichtLesenErlaubt(false, 90, 30_000, spaetestens));
        assertFalse(ArenaMode.schichtLesenErlaubt(false, 39, 80_000, spaetestens));   // unter 40 s Rest
        assertFalse(ArenaMode.schichtLesenErlaubt(false, 16, 100_000, spaetestens));
        // Langsamer PC (Angriff ab 45 s): Grenze 55 s.
        assertFalse(ArenaMode.schichtLesenErlaubt(false, 50, 60_000, 45));
        // Eigener Angriff schon durch: keine Eile mehr.
        assertTrue(ArenaMode.schichtLesenErlaubt(true, 10, 110_000, spaetestens));
        // Uhr unbekannt: nach der eigenen Zeit in der Runde.
        assertTrue(ArenaMode.schichtLesenErlaubt(false, -1, 20_000, spaetestens));
        assertFalse(ArenaMode.schichtLesenErlaubt(false, -1, 60_000, spaetestens));
    }

    @Test
    void kampfprotokollVorDemAngriffNurMitGenugZeit() {
        // Runde 1: nie (106 von 106 Lesungen: "nobody attacked you and failed").
        assertFalse(ArenaMode.protokollVorAngriff(1, 40, 8_000));
        // Sonst 15 s + gemessene Lesedauer.
        assertTrue(ArenaMode.protokollVorAngriff(2, 23, 8_000));
        assertFalse(ArenaMode.protokollVorAngriff(2, 22, 8_000));
        assertFalse(ArenaMode.protokollVorAngriff(3, 20, 11_000));   // 11 s Lesen -> erst ab 26 s
        assertTrue(ArenaMode.protokollVorAngriff(3, 26, 11_000));
        // Uhr unbekannt: wie bisher lesen.
        assertTrue(ArenaMode.protokollVorAngriff(4, -1, 8_000));
    }
}
