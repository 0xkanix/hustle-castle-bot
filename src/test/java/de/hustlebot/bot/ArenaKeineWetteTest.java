package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keine Wette auf 47 %, solange Platz 3 mit dem sicheren Gegner erreichbar bleibt (30.10., Turnier bis 05:10, Platz 4). */
class ArenaKeineWetteTest {

    /** Runde 3 des Turniers: die anderen, wie die Rechnung sie geschaetzt hat. */
    private static final double[] ANDERE = {813, 750, 600, 600, 825, 825, 600, 449, 300};

    @Test
    void derFallVon0510() {
        // 300 + 150 = 450, der geschaetzte Dritte 813: mit zwei vollen 225ern 900, zwei Runden sind noch da.
        assertTrue(ArenaTabelle.keineWette(0.47, 0.99, 2, 450, ANDERE));
    }

    @Test
    void derFallVon1400AmErstenNovember() {
        // Runde 4, 445 Punkte: Mit dem 150er 593, mit einem 225er danach hoechstens 818 -- der Dritte stand schon bei 823. Die 60-%-Wette bleibt erlaubt.
        double[] andere = {823, 671, 887, 871, 598, 445, 291, 297};
        assertFalse(ArenaTabelle.keineWette(0.60, 0.93, 1, 593, andere));
    }

    @Test
    void flimsyReichtNurSelten() {
        // 05:01, Runde 5: 450 Punkte, der Dritte endet bei 662. Die letzten 10 Kaempfe gegen FLIMSY, auf 225 umgerechnet.
        double[] andere = {662, 805, 646, 743, 590, 598, 361, 280, 0};
        boolean[] pokale = new boolean[andere.length];
        double[] flimsy = new double[10];
        int[] dauern = {13, 19, 20, 22, 22, 19, 16, 22, 20, 23};
        for (int i = 0; i < 10; i++) flimsy[i] = 225 - ArenaMode.dauerAbzug(225, dauern[i]);
        double mitVerlauf = ArenaTabelle.wahlWertVoraus(0.90, 450, flimsy, andere, andere, pokale, 0, false);
        // Nur 13 s und 16 s reichen fuer mehr als 662: 0,9 x (0,2 x Platz 3 + 0,8 x Platz 4) + 0,1 x Niederlage.
        assertTrue(Math.abs(mitVerlauf - (0.9 * (0.2 * 3 - 0.8 * 10) - 1.0)) < 1e-9);
        // Mit dem einen Schnittwert 209 waere jeder Sieg Platz 4, mit vollen 225 jeder Sieg Platz 3 -- beides stimmt nicht.
        double voll = ArenaTabelle.wahlWertVoraus(0.90, 450, 225, andere, andere, pokale, 0, false);
        assertTrue(mitVerlauf < voll);
        // Ein 225er mit 70 % und kurzem Kampf ist damit besser als FLIMSY mit 90 %.
        double kurz = ArenaTabelle.wahlWertVoraus(0.70, 450, 225, andere, andere, pokale, 0, false);
        assertTrue(kurz > mitVerlauf);
    }

    @Test
    void inDerLetztenRundeZaehltDieGenaueRechnung() {
        assertFalse(ArenaTabelle.keineWette(0.47, 0.99, 0, 450, ANDERE));
    }

    @Test
    void wennPlatz3NichtMehrErreichbarIstDarfEsDieWetteSein() {
        // 363 Punkte hinter dem Dritten bei einer Runde Rest: mit dem sicheren Gegner ist nichts mehr zu holen.
        assertFalse(ArenaTabelle.keineWette(0.47, 0.99, 1, 450, ANDERE));
    }

    @Test
    void ohneSicherenGegnerOderOhneWetteAendertSichNichts() {
        assertFalse(ArenaTabelle.keineWette(0.47, 0.85, 2, 450, ANDERE), "kein sicherer Gegner im Bild");
        assertFalse(ArenaTabelle.keineWette(0.75, 0.99, 2, 450, ANDERE), "75 % ist keine Wette");
    }
}
