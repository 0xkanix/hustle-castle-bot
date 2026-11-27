package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Konto-Pruefung bei jedem Modus-Start (Autor 21.11.): Burg zuerst, Team-Scan vor dem Modus fuer ein neues Konto. */
class KontoPruefungTest {

    @Test
    void ohneSchalterSofortFertig() {
        KontoPruefung p = new KontoPruefung();
        p.starten(false);
        assertTrue(p.fertig());
        assertFalse(p.gewechseltAbholen());
    }

    @Test
    void mitSchalterErstInDieBurg() {
        KontoPruefung p = new KontoPruefung();
        p.starten(true);
        assertFalse(p.fertig());
        assertEquals(KontoPruefung.Phase.WEG, p.phase());
        // ohne Bild wartet der Modus nicht
        assertFalse(p.schritt(null, null));
        p.starten(false);
        assertTrue(p.fertig());
    }

    @Test
    void nichtWegklickenBeiSucheUndAufstellung() {
        for (String v : new String[]{"cancel_search", "ar_searching", "ready", "partner_ready", "cw_deploy_squad", "end_journey"}) {
            assertTrue(KontoPruefung.NICHT_WEGKLICKEN.contains(v), v);
        }
        assertTrue(KontoPruefung.BURG_FRIST_MS <= 25_000);
    }

    @Test
    void ohneBildKeinName() {
        assertEquals("", KontoPruefung.nameAusBild(null));
    }

    @Test
    void lesbarerName() {
        assertTrue(KontoPruefung.lesbar("Zweitkonto A"));
        assertTrue(KontoPruefung.lesbar("ID 50017349"));
        assertFalse(KontoPruefung.lesbar(""));
        assertFalse(KontoPruefung.lesbar(" ~ "));
        assertFalse(KontoPruefung.lesbar(null));
    }

    @Test
    void teamScanVorDemModusNurFuerNormaleModi() {
        assertTrue(BotEngine.teamVorspielNoetig("cathedral", true));
        assertTrue(BotEngine.teamVorspielNoetig("dungeon", true));
        assertFalse(BotEngine.teamVorspielNoetig("cathedral", false), "Konto hat schon einen Scan");
        assertFalse(BotEngine.teamVorspielNoetig("clanwar", true), "Clankrieg nicht aufhalten");
        assertFalse(BotEngine.teamVorspielNoetig("teamscan", true));
        assertFalse(BotEngine.teamVorspielNoetig(null, true));
    }
}
