package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 22.10.: die Ausschnitte fuer die Arena-Boegen sind Sammlung und bleiben beim Aufraeumen liegen. */
class FotobremseBehaltenTest {

    @Test
    void boegenSammlungBleibt() {
        assertTrue(Fotobremse.behalten(Path.of("arena-helden", "Shinryu-haupt.png")));
        assertTrue(Fotobremse.behalten(Path.of("arena-bogen", "pals", "Kira.png")));
        assertTrue(Fotobremse.behalten(Path.of("stigmas", "gute", "a.png")));
    }

    @Test
    void beweisfotosDuerfenWeg() {
        assertFalse(Fotobremse.behalten(Path.of("fotoserie", "f-001.png")));
        assertFalse(Fotobremse.behalten(Path.of("arena-helden.png")));
        assertFalse(Fotobremse.behalten(Path.of("event-ende-014337.png")));
    }
}
