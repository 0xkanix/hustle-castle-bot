package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zeitzone (Auftrag 6, 11.10.): Standard Europe/Berlin, Datei zeitzone.txt, Eingaben wie Autor
 * oder ein Freund sie tippen ("london", "kiew", "+2", "Europe/Berlin").
 */
class ZeitzoneTest {

    @TempDir
    Path tmp;

    private Path alteDatei;

    @BeforeEach
    void umleiten() {
        alteDatei = Zeitzone.datei;
        Zeitzone.datei = tmp.resolve("zeitzone.txt");
        Zeitzone.neuLesen();
    }

    @AfterEach
    void zurueck() {
        Zeitzone.datei = alteDatei;
        Zeitzone.neuLesen();
    }

    @Test
    void ohneDatei_giltBerlin() {
        assertEquals(ZoneId.of("Europe/Berlin"), Zeitzone.zone());
        assertEquals("Europe/Berlin", Zeitzone.name());
        assertEquals("", Zeitzone.fehler());
    }

    @Test
    void verstehen_javaNamenUndStaedte() {
        assertEquals(ZoneId.of("Europe/Berlin"), Zeitzone.verstehen("Europe/Berlin").orElseThrow());
        assertEquals(ZoneId.of("Europe/Berlin"), Zeitzone.verstehen("berlin").orElseThrow());
        assertEquals(ZoneId.of("Europe/Berlin"), Zeitzone.verstehen("Deutschland").orElseThrow());
        assertEquals(ZoneId.of("Europe/London"), Zeitzone.verstehen("london").orElseThrow());
        assertEquals(ZoneId.of("Europe/Kyiv"), Zeitzone.verstehen("Kiew").orElseThrow());
        assertEquals(ZoneId.of("Europe/Vienna"), Zeitzone.verstehen("wien").orElseThrow());
        assertEquals(ZoneId.of("America/New_York"), Zeitzone.verstehen("new york").orElseThrow());
        assertEquals(ZoneId.of("UTC"), Zeitzone.verstehen("utc").orElseThrow());
        // Gross/klein egal, auch bei Java-Namen
        assertEquals(ZoneId.of("Europe/Paris"), Zeitzone.verstehen("europe/paris").orElseThrow());
        // Nur die Stadt eines Java-Namens
        assertEquals(ZoneId.of("Asia/Tokyo"), Zeitzone.verstehen("Tokyo").orElseThrow());
    }

    @Test
    void verstehen_versatz() {
        assertEquals("+02:00", Zeitzone.verstehen("+2").orElseThrow().getRules().getOffset(java.time.Instant.EPOCH).getId());
        assertEquals("-05:00", Zeitzone.verstehen("utc-5").orElseThrow().getRules().getOffset(java.time.Instant.EPOCH).getId());
        assertEquals("+05:30", Zeitzone.verstehen("+5:30").orElseThrow().getRules().getOffset(java.time.Instant.EPOCH).getId());
    }

    @Test
    void verstehen_unsinnBleibtLeer() {
        assertTrue(Zeitzone.verstehen("").isEmpty());
        assertTrue(Zeitzone.verstehen(null).isEmpty());
        assertTrue(Zeitzone.verstehen("Kaese").isEmpty());
        assertTrue(Zeitzone.verstehen("Atlantis/Nirgendwo").isEmpty());
    }

    @Test
    void setzen_schreibtDateiUndGiltSofort() {
        assertTrue(Zeitzone.setzen("london"));
        assertEquals(ZoneId.of("Europe/London"), Zeitzone.zone());
        assertTrue(Files.exists(Zeitzone.datei));
        // Nach dem Neulesen steht sie weiter da (ueberlebt einen Neustart).
        Zeitzone.neuLesen();
        assertEquals(ZoneId.of("Europe/London"), Zeitzone.zone());
        assertEquals("", Zeitzone.fehler());
    }

    @Test
    void setzen_unbekanntLaesstAllesWieEsIst() {
        assertTrue(Zeitzone.setzen("Europe/Vienna"));
        assertFalse(Zeitzone.setzen("Mond"));
        assertEquals(ZoneId.of("Europe/Vienna"), Zeitzone.zone());
    }

    @Test
    void kaputteDatei_giltBerlinMitHinweis() throws Exception {
        Files.writeString(Zeitzone.datei, "# Kommentar\nMondzeit\n", StandardCharsets.UTF_8);
        Zeitzone.neuLesen();
        assertEquals(ZoneId.of("Europe/Berlin"), Zeitzone.zone());
        assertTrue(Zeitzone.fehler().contains("Mondzeit"));
        assertTrue(Zeitzone.beschreibung().contains("Europe/Berlin"));
    }

    @Test
    void jetzt_rechnetInDerZone() {
        assertTrue(Zeitzone.setzen("UTC"));
        ZonedDateTime utc = ZonedDateTime.now(ZoneId.of("UTC"));
        // Die Stunde stimmt mit der echten UTC-Stunde ueberein (hoechstens eine Minute Abstand im Test).
        long diff = Math.abs(java.time.Duration.between(utc.toLocalDateTime(), Zeitzone.jetzt()).toMinutes());
        assertTrue(diff <= 1, "Abstand " + diff + " Minuten");
        assertEquals(utc.toLocalDate(), Zeitzone.heute());
    }
}
