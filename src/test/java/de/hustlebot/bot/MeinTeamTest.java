package de.hustlebot.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MeinTeam (Auftrag 6, 11.10.): 3-Tage-Merker ueber Neustarts, Datei-Format alt und neu,
 * Opfereinheit (kleinste Kraft), Held-Punkte, Spielername.
 */
class MeinTeamTest {

    @TempDir
    Path tmp;

    private Path alteDatei, altePunkte;

    @BeforeEach
    void umleiten() {
        alteDatei = MeinTeam.DATEI;
        altePunkte = MeinTeam.PUNKTE_DATEI;
        MeinTeam.DATEI = tmp.resolve("arena-ich.txt");
        MeinTeam.PUNKTE_DATEI = tmp.resolve("eigenesteam.txt");
        MeinTeam.neuLesen();
    }

    @AfterEach
    void zurueck() {
        MeinTeam.DATEI = alteDatei;
        MeinTeam.PUNKTE_DATEI = altePunkte;
        MeinTeam.neuLesen();
    }

    private static final LocalDateTime JETZT = LocalDateTime.of(2026, 10, 11, 20, 0);

    @Test
    void ohneDatei_keinePunkte_scanFaellig() {
        assertEquals(-1, MeinTeam.punkte());
        assertTrue(MeinTeam.faellig());
        assertTrue(MeinTeam.scanFaellig(true, MeinTeam.Stand.leer(), JETZT));
        assertFalse(MeinTeam.scanFaellig(false, MeinTeam.Stand.leer(), JETZT), "Automatik aus: nie faellig");
    }

    @Test
    void alteDatei_bleibtLesbar() throws Exception {
        Files.writeString(MeinTeam.DATEI, "# alt\nstand=2026-10-10 15:10\npunkte=58\nteam=Sets 31\n", StandardCharsets.UTF_8);
        MeinTeam.neuLesen();
        MeinTeam.Stand s = MeinTeam.alles();
        assertEquals(58, s.punkte());
        assertEquals("Sets 31", s.beschreibung());
        assertEquals("", s.name());
        assertTrue(s.einheiten().isEmpty());
    }

    @Test
    void dreiTageRegel() {
        MeinTeam.Stand frisch = new MeinTeam.Stand(JETZT.minusDays(2), 70, "", "kaserne", "Autor", 1, List.of(), "", null, null, List.of(), null);
        MeinTeam.Stand alt = new MeinTeam.Stand(JETZT.minusDays(3).minusMinutes(1), 70, "", "kaserne", "Autor", 1, List.of(), "", null, null, List.of(), null);
        assertFalse(MeinTeam.scanFaellig(true, frisch, JETZT));
        assertTrue(MeinTeam.scanFaellig(true, alt, JETZT));
    }

    @Test
    void nachFehlversuch_sechsStundenPause() {
        MeinTeam.Stand versucht = new MeinTeam.Stand(null, -1, "", "", "", -1, List.of(), "", null, null, List.of(), JETZT.minusDays(2));
        assertFalse(MeinTeam.scanFaellig(true, versucht, JETZT));
        MeinTeam.Stand laengerHer = new MeinTeam.Stand(null, -1, "", "", "", -1, List.of(), "", null, null, List.of(), JETZT.minusDays(3).minusHours(1));
        assertTrue(MeinTeam.scanFaellig(true, laengerHer, JETZT));
    }

    @Test
    void speichernScan_schreibtUndLiestAlles() {
        List<MeinTeam.Einheit> e = List.of(new MeinTeam.Einheit("Bogen 1", 6_362_024), new MeinTeam.Einheit("Magier 3", 4_100_000),
                new MeinTeam.Einheit("Tank", 5_000_000), new MeinTeam.Einheit("Unlesbar", -1));
        MeinTeam.Held held = new MeinTeam.Held("Tom", 4_351_600, null, 1);
        var schirm = new SchirmLeser.Ergebnis(3, 1, 0, List.of("ar_schirm_gut_2"), List.of());
        MeinTeam.speichernScan(58, "Sets 31, Steine +4", "Autor", 42_464_130L, e, held, schirm, List.of(3, 9, 5));
        MeinTeam.neuLesen();
        MeinTeam.Stand s = MeinTeam.alles();
        assertEquals(58 + MeinTeam.BONUS - MeinTeam.ABZUG, MeinTeam.punkte());
        assertEquals("Autor", s.name());
        assertEquals("Autor", MeinTeam.spielername());
        assertEquals(42_464_130L, s.squadPower());
        assertEquals(4, s.einheiten().size());
        assertEquals("Magier 3", MeinTeam.opfereinheit(), "kleinste gelesene Kraft");
        assertEquals("Tom", s.held().name());
        assertNull(s.held().hauptGut());
        assertEquals(1, s.held().guteNeben());
        assertEquals(List.of(3, 9, 5), s.schirmLevel());
        assertNotNull(s.schirme());
        assertEquals(3, s.schirme().anzahl());
        assertEquals(1, s.schirme().gute());
        assertEquals(schirm.punkte(), MeinTeam.schirmPunkte(), "Schirm-Punkte wie beim Gegner, auch getrennt abholbar");
        assertTrue(MeinTeam.schirmText().contains("Nr. 2"));
        assertEquals("kaserne", s.quelle());
        assertFalse(MeinTeam.faellig());
        assertFalse(MeinTeam.scanFaellig(true, s, s.stand().plusHours(1)));
        assertTrue(MeinTeam.karte().contains("Magier 3"));
    }

    @Test
    void speichernAusArena_laesstScanDatenStehen() {
        MeinTeam.speichernScan(58, "Scan", "Autor", 40_000_000L, List.of(new MeinTeam.Einheit("Magier 3", 4_000_000)), null, null, List.of());
        MeinTeam.speichern(60, "Arena");
        MeinTeam.neuLesen();
        MeinTeam.Stand s = MeinTeam.alles();
        assertEquals(60 + MeinTeam.BONUS - MeinTeam.ABZUG, s.punkte());
        assertEquals("arena", s.quelle());
        assertEquals("Autor", s.name());
        assertEquals("Magier 3", s.opfer());
        assertEquals(1, s.einheiten().size());
    }

    @Test
    void opferAus_kleinsteKraft_ohneKraftKeinOpfer() {
        assertEquals("", MeinTeam.opferAus(List.of()));
        assertEquals("", MeinTeam.opferAus(List.of(new MeinTeam.Einheit("A", -1))));
        assertEquals("B", MeinTeam.opferAus(List.of(new MeinTeam.Einheit("A", 9), new MeinTeam.Einheit("B", 3), new MeinTeam.Einheit("C", 5))));
    }

    @Test
    void heldPunkte_standardUndEinstellbar() {
        MeinTeam.Held gut = new MeinTeam.Held("Tom", 1, true, 2);
        assertEquals(12 + 2 * 4, MeinTeam.heldPunkte(gut));
        assertEquals(0, MeinTeam.heldPunkte(new MeinTeam.Held("Tom", 1, null, 0)));
        assertEquals(0, MeinTeam.heldPunkte(null));
        MeinTeam.setHeldPunkte(20, 5);
        assertEquals(20, MeinTeam.heldHaupt());
        assertEquals(5, MeinTeam.heldNeben());
        assertEquals(20 + 2 * 5, MeinTeam.heldPunkte(gut));
    }

    @Test
    void versuchVermerken_bleibtOhneStand() {
        MeinTeam.versuchVermerken();
        MeinTeam.neuLesen();
        MeinTeam.Stand s = MeinTeam.alles();
        assertNull(s.stand());
        assertNotNull(s.versuch());
        assertEquals(-1, MeinTeam.punkte());
    }

    @Test
    void spielername_setzen() {
        MeinTeam.setSpielername("  Autor ");
        MeinTeam.neuLesen();
        assertEquals("Autor", MeinTeam.spielername());
        MeinTeam.setSpielername("");
        assertEquals("Autor", MeinTeam.spielername());
    }
}
