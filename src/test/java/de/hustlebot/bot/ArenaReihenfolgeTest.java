package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Richtlinie 3 x 150 + 2 x 225 (Autor 21.10.: "nicht so streng, nur ein Tipp -- der Bot soll trotzdem nach Siegchance
 * und Sets arbeiten"). Ein schlagbarer 225er bekommt in Stage 2-3 x 0,8 und in Stage 4-5 x 1,1; sonst bleibt alles.
 */
class ArenaReihenfolgeTest {

    /** Idee A14 (22.10.): die Kraft-Tabelle faellt gleichmaessig, ohne Sprung bei 1,0. */
    @Test
    void kraftBasisOhneSpruenge() {
        assertEquals(0.99, ArenaMode.kraftBasis(0.5), 0.001);   // Idee 10: 264 von 264 gewonnen
        assertEquals(0.93, ArenaMode.kraftBasis(0.9), 0.001);
        assertEquals(0.875, ArenaMode.kraftBasis(0.95), 0.001);
        assertEquals(0.25, ArenaMode.kraftBasis(3.0), 0.001);
        double vorher = ArenaMode.kraftBasis(0.5);
        for (double r = 0.5; r <= 2.0; r += 0.01) {
            double c = ArenaMode.kraftBasis(r);
            assertTrue(c <= vorher + 1e-9, "steigt bei " + r);
            assertTrue(vorher - c < 0.02, "springt bei " + r);
            vorher = c;
        }
    }

    /** Wert wie in ArenaMode.wert(): Chance^2 x Punkte x Faktor. */
    private static double wert(int runde, long belohnung, double chance, boolean darf225) {
        double punkte = belohnung > 150 && !darf225 ? 150 : belohnung;
        double[] r = ArenaMode.reihenfolge(runde, belohnung, punkte, darf225, false);
        return chance * chance * r[0] * r[1];
    }

    @Test
    void ein150erBleibtUnveraendert() {
        for (int runde = 1; runde <= 5; runde++) {
            double[] r = ArenaMode.reihenfolge(runde, 150, 150, false, false);
            assertEquals(150, r[0], 0.001);
            assertEquals(1.0, r[1], 0.001);
        }
    }

    @Test
    void schlagbarer225erBekommtNurEinenSchubs() {
        assertArrayEquals(new double[]{225, ArenaMode.AUFHEBEN_225_FAKTOR}, ArenaMode.reihenfolge(2, 225, 225, true, false), 0.001);
        assertArrayEquals(new double[]{225, ArenaMode.AUFHEBEN_225_FAKTOR}, ArenaMode.reihenfolge(3, 225, 225, true, false), 0.001);
        assertArrayEquals(new double[]{225, ArenaMode.STAGE45_225_FAKTOR}, ArenaMode.reihenfolge(4, 225, 225, true, false), 0.001);
        assertArrayEquals(new double[]{225, ArenaMode.STAGE45_225_FAKTOR}, ArenaMode.reihenfolge(5, 225, 225, true, false), 0.001);
        // nicht schlagbar: zaehlt wie bisher als 150er, kein Schubs
        assertArrayEquals(new double[]{150, 1.0}, ArenaMode.reihenfolge(4, 225, 150, false, false), 0.001);
        // Idee 10: aufgehobener Spieler, du schon in den Top 3 -- in Stage 2 voll
        assertArrayEquals(new double[]{225, 1.0}, ArenaMode.reihenfolge(2, 225, 225, true, true), 0.001);
    }

    @Test
    void gleichSicherGehtInStage2Der150erVorUndInStage4Der225er() {
        assertTrue(wert(2, 150, 0.90, false) > wert(2, 225, 0.80, true));
        assertTrue(wert(4, 225, 0.80, true) > wert(4, 150, 0.90, false));
    }

    @Test
    void dieSiegchanceEntscheidetWeiter() {
        // Stage 2: ein sehr sicherer 225er schlaegt einen wackligen 150er -- trotz Richtlinie.
        assertTrue(wert(2, 225, 0.95, true) > wert(2, 150, 0.70, false));
        // Stage 4: ein wackliger 225er verliert gegen einen sicheren 150er -- trotz Richtlinie.
        assertTrue(wert(4, 150, 0.95, false) > wert(4, 225, 0.70, true));
    }
}
