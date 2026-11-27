package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Robin 22.11. (Bericht 7HE3, Zweitkonto "Saphir", 3.164): das Antippen der Kaserne traf eine herumlaufende Figur, es ging
 * deren Einheitenfenster auf statt des Raum-Menues. Der Team-Scan tippte danach noch einmal an dieselbe Stelle -- mitten ins
 * offene Fenster auf einen Gegenstand. Folge: Einheit 1 "Equipped by", "support" doppelt, Oid (5,2 Mio.) fehlte.
 * Regel: ohne Raum-Menue, aber mit offenem Einheitenfenster wird gleich gelesen (FIGUR_TIPPEN tippt bei offenem Fenster nicht).
 */
class TeamScanEinheitStattMenueTest {

    @Test
    void offenesEinheitenfenster_wirdGleichGelesen_keinTippHinein() {
        assertEquals(TeamScan.Schritt.FIGUR_TIPPEN, TeamScan.ohneMenue(true));
    }

    @Test
    void ohneFenster_bleibtEsBeimAntippenDerKaserne() {
        assertNull(TeamScan.ohneMenue(false));
    }
}
