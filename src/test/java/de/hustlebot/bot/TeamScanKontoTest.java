package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29.10.: der Team-Rundgang im Zweitkonto ueberschrieb Autors Team (27.10. 16:25). */
class TeamScanKontoTest {

    @Test
    void fremdesKontoWirdNichtGespeichert() {
        assertFalse(TeamScan.kontoPasst("Autor", "Zweitkonto A'"));
        assertFalse(TeamScan.kontoPasst("Autor", "Drittkonto"));
    }

    @Test
    void gleichesKontoUndOhnePruefungWieBisher() {
        assertTrue(TeamScan.kontoPasst("Autor", "Autor"));
        assertTrue(TeamScan.kontoPasst("Autor", "AUTOR"));
        assertTrue(TeamScan.kontoPasst("", "Zweitkonto A'"), "ohne aktives Konto (Freunde mit einem Konto) wie bisher");
        assertTrue(TeamScan.kontoPasst("Autor", ""), "Name nicht lesbar: wie bisher");
    }
}
