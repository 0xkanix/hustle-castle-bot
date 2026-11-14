package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Team-Scan (Auftrag 6, 11.10.): die Ablaufregeln ohne Bildschirm -- Reihenfolge der Bloecke,
 * erster Schritt je Block, Scroll-Muster, Punkte wie bei Gegnern.
 */
class TeamScanTest {

    @Test
    void bloecke_inDerReihenfolgeDesAuftrags() {
        assertEquals(TeamScan.Block.EINSTELLUNGEN, TeamScan.naechsterBlock(TeamScan.Block.START));
        // Autor 25.10.: das Profil (Thronsaal) vor den Einheiten.
        assertEquals(TeamScan.Block.PROFIL, TeamScan.naechsterBlock(TeamScan.Block.EINSTELLUNGEN));
        assertEquals(TeamScan.Block.KASERNE_INFO, TeamScan.naechsterBlock(TeamScan.Block.PROFIL));
        assertEquals(TeamScan.Block.KASERNE_EINHEITEN, TeamScan.naechsterBlock(TeamScan.Block.KASERNE_INFO));
        // Thronsaal/Rift aus (Autor 11.10.); seit 15.10. Held, Schirme und Pals ueber das Profil (Burg oben links).
        assertEquals(TeamScan.HELD_UND_SCHIRME ? TeamScan.Block.HELD : TeamScan.Block.FERTIG,
                TeamScan.naechsterBlock(TeamScan.Block.KASERNE_EINHEITEN));
        assertEquals(TeamScan.Schritt.PROFIL_OEFFNEN, TeamScan.ersterSchritt(TeamScan.Block.PROFIL));
        assertEquals(TeamScan.Block.SCHIRMHERREN, TeamScan.naechsterBlock(TeamScan.Block.HELD));
        assertEquals(TeamScan.Block.FERTIG, TeamScan.naechsterBlock(TeamScan.Block.SCHIRMHERREN));
        assertEquals(TeamScan.Block.ENDE, TeamScan.naechsterBlock(TeamScan.Block.FERTIG));
        assertEquals(TeamScan.Block.ENDE, TeamScan.naechsterBlock(TeamScan.Block.ENDE));
    }

    @Test
    void jederBlockHatEinenErstenSchritt() {
        assertEquals(TeamScan.Schritt.HEIM, TeamScan.ersterSchritt(TeamScan.Block.START));
        assertEquals(TeamScan.Schritt.KASERNE_SUCHEN, TeamScan.ersterSchritt(TeamScan.Block.KASERNE_INFO));
        assertEquals(TeamScan.Schritt.SQUAD_OEFFNEN, TeamScan.ersterSchritt(TeamScan.Block.KASERNE_EINHEITEN));   // Autor 25.10.: ueber Squad Challenge
        assertEquals(TeamScan.Schritt.THRON_SUCHEN, TeamScan.ersterSchritt(TeamScan.Block.HELD));
        assertEquals(TeamScan.Schritt.RIFT_SUCHEN, TeamScan.ersterSchritt(TeamScan.Block.SCHIRMHERREN));
        assertEquals(TeamScan.Schritt.AUFRAEUMEN, TeamScan.ersterSchritt(TeamScan.Block.FERTIG));
        assertEquals(TeamScan.Schritt.AUS, TeamScan.ersterSchritt(TeamScan.Block.ENDE));
    }

    @Test
    void scrollen_erstHochDannRunterDannWiederHoch() {
        // 3x nach oben, 6x nach unten, dann wieder nach oben -- alles einmal gesehen, ohne Zickzack.
        for (int i = 0; i < 3; i++) assertEquals(-1, TeamScan.scrollRichtung(i), "Versuch " + i);
        for (int i = 3; i < 9; i++) assertEquals(1, TeamScan.scrollRichtung(i), "Versuch " + i);
        assertEquals(-1, TeamScan.scrollRichtung(9));
    }

    @Test
    void punkte_wieBeiGegnern() {
        // 6 Einheiten, Sets/Kraft/Embleme 31, 2 gute Artefakte, 0 Bauarbeiter, 4 gelbe / 6 rote Steine
        ArenaPruefer.Ergebnis e = new ArenaPruefer.Ergebnis("dein Team", "", 6, 31, "x", 2, 0, 4, 6, -1, 0, 0, -1, false, true);
        MeinTeam.Held held = new MeinTeam.Held("Tom", 4_351_600, true, 1);
        TeamScan.Punkte p = TeamScan.punkte(e, 0b1111, 3, 1, 0, held, -1);
        assertEquals(de.hustlebot.core.Gegnerbuch.teamPunkte(31, 2, 0, 6), p.einheiten());
        assertEquals(de.hustlebot.core.Gegnerbuch.steinPunkte(4, 6), p.steine());
        assertEquals(8, p.allianz(), "alle vier Allianz-Talente: +8");
        assertEquals(de.hustlebot.core.Gegnerbuch.schirmPunkte(3, 1, 0), p.schirme());
        assertEquals(MeinTeam.heldPunkte(held), p.held());
        assertEquals(p.einheiten() + p.steine() + p.allianz() + p.schirme() + p.held(), p.gesamt());
        assertTrue(p.text().contains("Allianz"));
        assertTrue(p.text().contains("Tom"));
    }

    @Test
    void punkte_ohneAllesIstNull() {
        TeamScan.Punkte p = TeamScan.punkte(null, 0, -1, 0, 0, null, -1);
        assertEquals(0, p.gesamt());
        assertTrue(p.text().contains("nicht angesehen"));
        // Drei von vier Talenten: kein Zuschlag.
        assertEquals(0, TeamScan.punkte(null, 0b0111, -1, 0, 0, null, -1).allianz());
    }

    @Test
    void squadPower_ohneDasOrangeI() {
        // 11.10., Autors Kaserne: das orange "i" neben der Zahl wird als 1 oder 0 mitgelesen
        assertEquals(42_930_130L, TeamScan.squadAusText("42 930 1301"));
        assertEquals(42_930_130L, TeamScan.squadAusText("42 930 1300"));
        assertEquals(42_930_130L, TeamScan.squadAusText("42 930 130"));
        assertEquals(-1L, TeamScan.squadAusText(""));
        assertEquals(-1L, TeamScan.squadAusText(null));
    }

    @Test
    void allianzMaske_textUndVoll() {
        assertTrue(ArenaPruefer.allianzVoll(0b1111));
        assertFalse(ArenaPruefer.allianzVoll(0b1110));
        assertEquals("sonne, blitz", ArenaPruefer.allianzText(0b0011));
        assertEquals("", ArenaPruefer.allianzText(0));
    }

    @Test
    void pflichtvorlagen_sindDieKleineMenge() {
        assertEquals(3, TeamScan.vorlagen().size(), "nur Globus, Info und X sind Pflicht -- alles andere ist je Block freiwillig");
        assertTrue(TeamScan.alleVorlagen().containsAll(TeamScan.vorlagen()));
    }
}
