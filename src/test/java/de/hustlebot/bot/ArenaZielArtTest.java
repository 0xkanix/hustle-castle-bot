package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zielart-Korrektur (Auswertung 30.10., 614 Kaempfe aus arena-lernen.txt): bei gleicher Schaetzung gewann der Bot gegen
 * 150er viel oefter als gegen 225er ab Stage 3 mit mindestens 0,9x deiner Kraft. Die Wahl rechnet darum mit
 * verschobenen Chancen -- die Reihenfolge innerhalb einer Zielart bleibt.
 */
class ArenaZielArtTest {

    private static double k(double chance, int runde, long belohnung, double verhaeltnis) {
        return ArenaMode.zielArtKorrektur(chance, runde, belohnung, verhaeltnis, false);
    }

    @Test
    void ein150erSteigtUndBehaeltDieReihenfolge() {
        assertEquals(0.916, k(0.80, 4, 150, 0.95), 0.002);
        assertEquals(0.981, k(0.95, 5, 150, 0.95), 0.002);
        double vorher = 0;
        for (double c = 0.05; c <= 0.99; c += 0.01) {
            double neu = k(c, 3, 150, 1.0);
            assertTrue(neu >= c, "faellt bei " + c);
            assertTrue(neu >= vorher, "Reihenfolge kippt bei " + c);
            assertTrue(neu <= ArenaMode.ZIELART_HOECHSTENS + 1e-9, "ueber der Obergrenze bei " + c);
            vorher = neu;
        }
    }

    @Test
    void ein225erInStage2ZaehltWieEin150er() {
        // 43 von 43 gewonnen bei 90 % Schaetzung
        assertEquals(k(0.85, 2, 150, 0.95), k(0.85, 2, 225, 0.95), 1e-9);
        assertTrue(k(0.85, 2, 225, 0.95) > 0.85);
    }

    @Test
    void ein225erInStage2MitGleicherKraftBekommtKeinenZuschlag() {
        // 30.10., 01:25: Kraft 1,0x, 85 % -> 94 %, verloren
        assertEquals(0.85, k(0.85, 2, 225, 1.0), 1e-9);
        assertEquals(0.85, k(0.85, 2, 225, -1), 1e-9, "Kraft unbekannt: bleibt, wie er ist");
    }

    @Test
    void ein225erAbStage3MitAehnlicherKraftFaellt() {
        // 45 von 79 gewonnen (57 %), geschaetzt 69 %
        assertEquals(0.817, k(0.90, 3, 225, 0.95), 0.002);
        assertEquals(0.537, k(0.70, 5, 225, 1.2), 0.002);
        double vorher = 0;
        for (double c = 0.05; c <= 0.99; c += 0.01) {
            double neu = k(c, 4, 225, 0.9);
            assertTrue(neu < c, "steigt bei " + c);
            assertTrue(neu >= vorher, "Reihenfolge kippt bei " + c);
            vorher = neu;
        }
    }

    @Test
    void ein225erUnter09xOderMitUnbekannterKraftBleibt() {
        // unter 0,9x stimmte die Schaetzung (85 % zu 89 %, unter 0,75x 18 von 18) -- unbekannte Kraft: keine Daten
        assertEquals(0.90, k(0.90, 4, 225, 0.85), 1e-9);
        assertEquals(0.97, k(0.97, 5, 225, 0.6), 1e-9);
        assertEquals(0.60, k(0.60, 3, 225, -1), 1e-9);
    }

    @Test
    void botUndListenGegnerAls225erBleiben() {
        // "Not in clan" 171 von 171, "sicher"/"machbar" als 225er 21 von 22 -- nicht ueberschaetzt
        assertEquals(0.95, ArenaMode.zielArtKorrektur(0.95, 4, 225, 0.95, true), 1e-9);
        assertEquals(0.80, ArenaMode.zielArtKorrektur(0.80, 5, 225, 1.1, true), 1e-9);
        // als 150er steigen sie wie alle anderen
        assertEquals(k(0.80, 4, 150, 0.95), ArenaMode.zielArtKorrektur(0.80, 4, 150, 0.95, true), 1e-9);
    }

    @Test
    void beiGleicherSchaetzungGehtDer150erNachDerKorrekturVor() {
        // Stage 4, beide "80 %": vorher Chance^2 x Punkte -> 225er (144 zu 96), jetzt rechnet die Wahl mit 92 % zu 66 %
        double c150 = k(0.80, 4, 150, 0.95);
        double c225 = k(0.80, 4, 225, 0.95);
        assertTrue(c150 > 0.9 && c225 < 0.7, c150 + " / " + c225);
    }

    /** Gemessen 30.10.: 150er 14 s -> 145, 20 s -> 139; 225er 14 s -> 218, 15 s -> 216, 20 s -> 208. */
    @Test
    void langeKaempfeKostenJeNachLohn() {
        assertEquals(0, ArenaMode.dauerAbzug(225, 10), 1e-9);
        assertEquals(0, ArenaMode.dauerAbzug(150, -1), 1e-9);
        assertEquals(145, 150 - ArenaMode.dauerAbzug(150, 14), 0.6);
        assertEquals(139, 150 - ArenaMode.dauerAbzug(150, 20), 0.6);
        assertEquals(218, 225 - ArenaMode.dauerAbzug(225, 14), 0.6);
        assertEquals(216, 225 - ArenaMode.dauerAbzug(225, 15), 0.6);
        assertEquals(208, 225 - ArenaMode.dauerAbzug(225, 20), 0.6);
        assertEquals(45, ArenaMode.dauerAbzug(225, 60), 1e-9);   // hoechstens ein Fuenftel
    }

    @Test
    void randwerteBleiben() {
        assertEquals(0.0, k(0.0, 4, 150, 0.95), 1e-9);
        assertEquals(1.0, k(1.0, 4, 225, 0.95), 1e-9);
    }
}
