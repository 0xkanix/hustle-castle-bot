package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprachumschaltung (Fassung 3.1, Autor 11.10.): Englisch ist Standard, die Wahl wird in
 * sprache.txt gemerkt und gilt nach einem Neustart weiter.
 */
class SpracheTest {

    @AfterEach
    void zurueckAufEnglisch() {
        Sprache.set(Sprache.Lang.EN);
    }

    @Test
    void ohneDateiEnglisch() {
        assertEquals(Sprache.Lang.EN, Sprache.ausText(null));
        assertEquals(Sprache.Lang.EN, Sprache.ausText(""));
        assertEquals(Sprache.Lang.EN, Sprache.ausText("   "));
        assertEquals(Sprache.Lang.EN, Sprache.ausText("en"));
        assertEquals(Sprache.Lang.EN, Sprache.ausText("irgendwas"));
        assertEquals(Sprache.Lang.EN, Sprache.laden(Path.of("gibt-es-nicht", "sprache.txt")));
    }

    @Test
    void deutschNurWennDeDrinSteht() {
        assertEquals(Sprache.Lang.DE, Sprache.ausText("de"));
        assertEquals(Sprache.Lang.DE, Sprache.ausText("DE\r\n"));
        assertEquals(Sprache.Lang.DE, Sprache.ausText("  deutsch "));
    }

    @Test
    void wahlWirdGespeichertUndWiedergelesen(@TempDir Path ordner) {
        Path datei = ordner.resolve("sprache.txt");
        assertTrue(Sprache.speichern(datei, Sprache.Lang.DE));
        assertEquals(Sprache.Lang.DE, Sprache.laden(datei));
        assertTrue(Sprache.speichern(datei, Sprache.Lang.EN));
        assertEquals(Sprache.Lang.EN, Sprache.laden(datei));
        assertFalse(Sprache.speichern(datei, null));
    }

    @Test
    void umschaltenWirktAufTexteZahlenUndQuellen() {
        Sprache.set(Sprache.Lang.EN);
        assertTrue(Sprache.istEnglisch());
        assertEquals("Runs", Sprache.t("Runs", "Durchlaeufe"));
        assertEquals("1,234,567", Sprache.zahl(1_234_567));
        assertEquals("Dungeon", Sprache.quelle("Verlies"));
        assertEquals("Arena", Sprache.quelle("Arena"));   // unbekannt oder gleich: bleibt
        assertEquals("Cathedral (Hammer-Event)", Sprache.quelle("Kathedrale (Hammer-Event)"));
        assertEquals("Accounts", Sprache.quelle("Konten"));   // 22.11.: Quellen der Modi, die Autor nutzt
        assertEquals("Daily quests", Sprache.quelle("Spiel-Quests"));
        assertEquals("Fremd (x)", Sprache.quelle("Fremd (x)"));

        Sprache.set(Sprache.Lang.DE);
        assertFalse(Sprache.istEnglisch());
        assertEquals("Durchlaeufe", Sprache.t("Runs", "Durchlaeufe"));
        assertEquals("1.234.567", Sprache.zahl(1_234_567));
        assertEquals("Verlies", Sprache.quelle("Verlies"));
    }

    @Test
    void nullAendertNichts() {
        Sprache.set(Sprache.Lang.DE);
        Sprache.set(null);
        assertEquals(Sprache.Lang.DE, Sprache.get());
    }
}
