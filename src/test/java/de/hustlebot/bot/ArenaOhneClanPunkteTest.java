package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Autor 30.10.: bei "Not in clan"-Bots, die der Bot nicht kennt, ordnen die Turnierpunkte die Siegchance fein: je mehr Punkte, desto
 * niedriger, je weniger, desto hoeher -- nur ganz kleine Unterschiede, ab Stage 2. Dazu: wann der Bot einen Spieler von der Liste selbst ansieht.
 */
class ArenaOhneClanPunkteTest {

    private static double p(double chance, int runde, long punkte) {
        return ArenaMode.ohneClanNachPunkten(chance, runde, punkte);
    }

    @Test
    void stage1UndUnbekanntePunkteAendernNichts() {
        assertEquals(0.95, p(0.95, 1, 0), 1e-12);
        assertEquals(0.95, p(0.95, 1, 225), 1e-12);
        for (int runde = 2; runde <= 5; runde++) {
            assertEquals(0.95, p(0.95, runde, -1), 1e-12, "Punkte nicht gelesen: Stage " + runde);
        }
        assertEquals(0.0, p(0.0, 3, 100), 0.0, "ohne Chance bleibt es dabei");
        assertEquals(1.0, p(1.0, 3, 100), 0.0);
    }

    @Test
    void abStage2MehrPunkteSindNiedrigerWenigerSindHoeher() {
        for (int runde = 2; runde <= 5; runde++) {
            long voll = 225L * (runde - 1);
            double wenig = p(0.95, runde, 0), mittel = p(0.95, runde, voll / 2), viel = p(0.95, runde, voll);
            assertTrue(wenig > 0.95, "alles verloren: hoeher, Stage " + runde + ": " + wenig);
            assertTrue(viel < 0.95, "jedes Mal 225 geholt: niedriger, Stage " + runde + ": " + viel);
            assertTrue(wenig > mittel && mittel > viel, "Reihenfolge Stage " + runde + ": " + wenig + " " + mittel + " " + viel);
        }
    }

    @Test
    void jeMehrPunkteDestoNiedrigerOhneAusreisser() {
        for (int runde = 2; runde <= 5; runde++) {
            double davor = 1;
            for (long punkte = 0; punkte <= 225L * (runde - 1); punkte += 15) {
                double jetzt = p(0.95, runde, punkte);
                assertTrue(jetzt <= davor, "steigt bei " + punkte + " Punkten in Stage " + runde);
                davor = jetzt;
            }
        }
        // mehr als jedes Mal 225 gibt es nicht: gedeckelt
        assertEquals(p(0.95, 3, 450), p(0.95, 3, 5000), 1e-12);
    }

    @Test
    void derUnterschiedIstKleinBeiNeunzigFuenfProzentBleibtAllesZwischen91Und98() {
        for (int runde = 2; runde <= 5; runde++) {
            for (long punkte = 0; punkte <= 225L * (runde - 1) + 100; punkte += 5) {
                double c = p(0.95, runde, punkte);
                assertTrue(c >= 0.91 && c <= 0.98, "Stage " + runde + ", " + punkte + " Punkte: " + c);
            }
        }
    }

    @Test
    void nieUeberDerObergrenzeDerZielart() {
        for (int runde = 2; runde <= 5; runde++) {
            for (double chance = 0.50; chance < 1.0; chance += 0.005) {
                for (long punkte : new long[]{0, 100, 225L * (runde - 1)}) {
                    double c = p(chance, runde, punkte);
                    assertTrue(c <= ArenaMode.ZIELART_HOECHSTENS + 1e-12,
                            "ueber der Obergrenze: Chance " + chance + ", Stage " + runde + ", " + punkte + " Punkte: " + c);
                }
            }
        }
        assertEquals(ArenaMode.ZIELART_HOECHSTENS, p(0.999, 2, 0), 1e-12);
    }

    // ---- listeAufnehmen ---------------------------------------------------------------------------------------

    /** Autor 30.10.: Ein Spieler von der Liste, dessen Team der Bot nicht kennt, wird in Stage 3 bis 5 selbst angesehen -- nur mit den neuen Regeln. */
    @Test
    void listeAufnehmenNurMitNeuenRegelnAbStage3UndNurBeiUnbekanntemTeam() {
        assertTrue(ArenaMode.listeAufnehmen(true, 3, false));
        assertTrue(ArenaMode.listeAufnehmen(true, 4, false));
        assertTrue(ArenaMode.listeAufnehmen(true, 5, false));
        assertFalse(ArenaMode.listeAufnehmen(false, 3, false), "ohne neue Regeln nie");
        assertFalse(ArenaMode.listeAufnehmen(false, 5, false));
        assertFalse(ArenaMode.listeAufnehmen(true, 1, false), "Stage 1: zu frueh");
        assertFalse(ArenaMode.listeAufnehmen(true, 2, false), "Stage 2: zu frueh");
        assertFalse(ArenaMode.listeAufnehmen(true, 3, true), "Team schon bekannt: nichts nachzuholen");
        assertFalse(ArenaMode.listeAufnehmen(true, 5, true));
    }
}
