package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: Team-Scan 17:12 und 21:16 "Ueberschrift Pals nicht zu sehen -- Pals nicht gezaehlt", obwohl "Pals" im
 * Bild stand (Foto logs\team\1029-211641-pals-nicht-gefunden.png). Die Windows-Texterkennung las den Ausschnitt als
 * "Pats Pat Squad: 11 Dungeon". Die Texte unten sind die echten Lesungen der 14 Fotos in logs\team (ausgeschnitten wie im Bot).
 */
class TeamScanPalsTest {

    @Test
    void alleEchtenLesungenDerUeberschriftWerdenErkannt() {
        String[] echt = {
                "Pals Pal Squad: Dungeon", "Pals Dungeon", "Pats Pat Squad: 11 Dungeon", "Pals Dungeon",
                "Pats Pal Squad: Dungeon", "Lv. 9 Pats Pal Squad: Dungeon", "Lv.9 Pats Dungeon", "pats Dungeon",
                "Pals Dungeon", "Pats Dungeon", "Pats Pal Squad: 11 Dungeon",
                "Titans Patronage: You haven't activated the Patrons' abilities yet Pals Pat Squad: 4",
                // zweite Lesart (offene Erkennung aus):
                "Pals", "Pale", "Pala", "als", "PalSquad:", "PalSauad:", "Pals Pal Sauad:", "aPals PalSquad:"};
        for (String t : echt) assertTrue(TeamScan.palsUeberschrift(t), "Ueberschrift in: " + t);
    }

    @Test
    void andereBildschirmeZaehlenNicht() {
        String[] nein = {"", "Titans Patronage: You haven't activated the Patrons' abilities yet", "Dungeon Journeys to the dungeon: 626",
                "Rewards Castle Battles", "Hero Tom", "Lv. 5 Lv. 1 Lv. 5", "Combat attributes", "MeinClan Squad Leaders 2 277",
                "Titans Patronage:"};
        for (String t : nein) assertFalse(TeamScan.palsUeberschrift(t), "keine Pals-Ueberschrift in: " + t);
        assertFalse(TeamScan.palsUeberschrift(null));
    }

    @Test
    void abstandZaehltEinfuegenLoeschenErsetzen() {
        assertEquals(0, TeamScan.abstand("pals", "pals"));
        assertEquals(1, TeamScan.abstand("pats", "pals"));
        assertEquals(1, TeamScan.abstand("pal", "pals"));
        assertEquals(2, TeamScan.abstand("pat", "pals"));
        assertEquals(1, TeamScan.abstand("sauad", "squad"));
    }
}
