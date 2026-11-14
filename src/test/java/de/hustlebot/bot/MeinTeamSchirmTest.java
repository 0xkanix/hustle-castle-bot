package de.hustlebot.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Eigene Schirmherrschaften in arena-ich.txt (Autor 11.10., Nachtrag zu Auftrag 6, Punkt 4) -- ueber @TempDir. */
class MeinTeamSchirmTest {

    @TempDir
    Path tmp;

    @Test
    void schreibenUndWiederLesen() {
        Path datei = tmp.resolve("arena-ich.txt");
        var e = new SchirmLeser.Ergebnis(3, 1, 1, List.of("ar_schirm_gut_2"), List.of("ar_stigma_gut_1"));
        LocalDateTime jetzt = LocalDateTime.of(2026, 10, 11, 20, 5);
        MeinTeam.Schirme s = MeinTeam.schirmeSpeichern(datei, e, jetzt);
        assertNotNull(s);
        assertEquals(e.punkte(), s.punkte(), "dieselben Punkte wie beim Gegner");

        MeinTeam.Schirme g = MeinTeam.schirmeLesen(datei);
        assertNotNull(g);
        assertEquals(3, g.anzahl());
        assertEquals(1, g.gute());
        assertEquals(1, g.stigmas());
        assertEquals(jetzt, g.stand());
        assertEquals("Schirmherrschaften 3 (1 gute, 1 Stigma, +8) -- gute: Nr. 2; Stigma: Baer", g.text());
    }

    @Test
    void scoreZeilenBleibenErhalten() throws Exception {
        Path datei = tmp.resolve("arena-ich.txt");
        Files.write(datei, List.of("# alt", "stand=2026-10-08 15:10", "punkte=58", "team=Sets 31, Bonus +15"));
        MeinTeam.schirmeSpeichern(datei, new SchirmLeser.Ergebnis(2, 0, 0, List.of(), List.of()),
                LocalDateTime.of(2026, 10, 11, 20, 5));
        List<String> zeilen = Files.readAllLines(datei);
        assertTrue(zeilen.contains("stand=2026-10-08 15:10"), zeilen.toString());
        assertTrue(zeilen.contains("punkte=58"), zeilen.toString());
        assertTrue(zeilen.contains("team=Sets 31, Bonus +15"), zeilen.toString());
        assertTrue(zeilen.contains("schirm=2/0/0"), zeilen.toString());
        assertTrue(zeilen.contains("schirmtext=Schirmherrschaften 2 (0 gute, 0 Stigma, +2)"), zeilen.toString());
        // Noch einmal schreiben ersetzt, haengt nicht an.
        MeinTeam.schirmeSpeichern(datei, new SchirmLeser.Ergebnis(3, 0, 0, List.of(), List.of()),
                LocalDateTime.of(2026, 10, 12, 8, 0));
        zeilen = Files.readAllLines(datei);
        assertEquals(1, zeilen.stream().filter(z -> z.startsWith("schirm=")).count(), zeilen.toString());
        assertTrue(zeilen.contains("schirm=3/0/0"), zeilen.toString());
        assertTrue(zeilen.contains("punkte=58"), zeilen.toString());
    }

    @Test
    void ohneSchirmZeilenNull() throws Exception {
        Path datei = tmp.resolve("arena-ich.txt");
        assertNull(MeinTeam.schirmeLesen(datei), "keine Datei");
        Files.write(datei, List.of("stand=2026-10-08 15:10", "punkte=58"));
        assertNull(MeinTeam.schirmeLesen(datei), "nur der Score");
        Files.write(datei, List.of("schirm=kaputt", "schirmstand=2026-10-11 20:05"));
        assertNull(MeinTeam.schirmeLesen(datei), "unlesbar gilt als nicht vorhanden");
    }
}
