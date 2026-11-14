package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "!arena Rina safe" (Autor 21.10.): Name und Listen-Wort zerlegen, Zeile "Name | sicher" in arena-aufnehmen.txt
 * schreiben (bestehende Zeile ersetzen, nicht doppelt anhaengen).
 */
class ArenaAufnehmenTest {

    @TempDir
    Path ordner;

    @Test
    void zerlegtNameUndWort() {
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.SICHER), ArenaAufnehmen.zerlegen("Rina sicher"));
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.SICHER), ArenaAufnehmen.zerlegen("Rina safe"));
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.MACHBAR), ArenaAufnehmen.zerlegen("Rina doable"));
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.MEIDEN), ArenaAufnehmen.zerlegen("Rina AVOID"));
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.NIEMALS), ArenaAufnehmen.zerlegen("Rina never"));
        assertEquals(new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.NIEMALS), ArenaAufnehmen.zerlegen("Rina niemals"));
    }

    @Test
    void nameMitLeerzeichenUndAnfuehrungszeichen() {
        assertEquals(new ArenaAufnehmen.Zeile("Walking Death", ArenaAufnehmen.Art.MEIDEN),
                ArenaAufnehmen.zerlegen("Walking Death avoid"));
        assertEquals(new ArenaAufnehmen.Zeile("Dr. Emil", ArenaAufnehmen.Art.SICHER),
                ArenaAufnehmen.zerlegen("\"Dr. Emil\" sicher"));
        assertEquals(new ArenaAufnehmen.Zeile("Dr. Emil", ArenaAufnehmen.Art.SICHER),
                ArenaAufnehmen.zerlegen("  Dr.   Emil   safe  "));
    }

    @Test
    void trennstrichImNamenWirdLeerzeichen() {
        // "|" trennt in der Datei Name und Liste -- im Namen darf er nicht vorkommen.
        assertEquals(new ArenaAufnehmen.Zeile("A B", ArenaAufnehmen.Art.NIEMALS), ArenaAufnehmen.zerlegen("A|B never"));
    }

    @Test
    void ohneNameOderOhneWortIstNichts() {
        assertNull(ArenaAufnehmen.zerlegen(null));
        assertNull(ArenaAufnehmen.zerlegen(""));
        assertNull(ArenaAufnehmen.zerlegen("Rina"));          // nur ein Name, keine Liste
        assertNull(ArenaAufnehmen.zerlegen("safe"));          // nur ein Wort, kein Name
        assertNull(ArenaAufnehmen.zerlegen("Rina vielleicht"));   // unbekanntes Listen-Wort
        assertNull(ArenaAufnehmen.zerlegen("\"\" safe"));     // leerer Name
    }

    @Test
    void eintragenLegtDieDateiAnUndErsetztDieZeile() throws Exception {
        Path datei = ordner.resolve("arena-aufnehmen.txt");
        assertEquals(List.of(), ArenaAufnehmen.offene(datei));

        assertNull(ArenaAufnehmen.eintragen(datei, new ArenaAufnehmen.Zeile("Rina", ArenaAufnehmen.Art.MACHBAR)));
        assertNull(ArenaAufnehmen.eintragen(datei, new ArenaAufnehmen.Zeile("Xenos", ArenaAufnehmen.Art.SICHER)));
        assertEquals(List.of("Rina | machbar", "Xenos | sicher"), ArenaAufnehmen.offene(datei));

        // Steht der Name schon drin (Gross-/Kleinschreibung egal), gilt die neue Liste -- keine zweite Zeile.
        assertEquals("machbar", ArenaAufnehmen.eintragen(datei, new ArenaAufnehmen.Zeile("RINA", ArenaAufnehmen.Art.NIEMALS)));
        List<String> offen = ArenaAufnehmen.offene(datei);
        assertEquals(2, offen.size());
        assertTrue(offen.contains("Xenos | sicher"));
        assertTrue(offen.contains("RINA | niemals"));

        // Die Kopfzeile bleibt, die Datei ist UTF-8 und zeilenweise lesbar (wie ArenaMode sie liest).
        List<String> alle = Files.readAllLines(datei, StandardCharsets.UTF_8);
        assertTrue(alle.get(0).startsWith("#"));
    }

    @Test
    void fremdeZeilenBleibenUnberuehrt() throws Exception {
        Path datei = ordner.resolve("arena-aufnehmen.txt");
        Files.write(datei, List.of("# eigene Kopfzeile", "Flimsy | machbar", "", "Mueller | meiden"), StandardCharsets.UTF_8);
        ArenaAufnehmen.eintragen(datei, new ArenaAufnehmen.Zeile("Flimsy", ArenaAufnehmen.Art.SICHER));
        List<String> alle = Files.readAllLines(datei, StandardCharsets.UTF_8);
        assertEquals(List.of("# eigene Kopfzeile", "", "Mueller | meiden", "Flimsy | sicher"), alle);
    }
}
