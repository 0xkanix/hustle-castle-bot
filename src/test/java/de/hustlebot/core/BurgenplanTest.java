package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Schalter burgen-plan.txt (Autor 25.10.): ohne Datei aus, "an" an, Kommentare egal. */
class BurgenplanTest {

    @Test
    void schalterLesen(@TempDir Path d) throws Exception {
        Path f = d.resolve("burgen-plan.txt");
        assertFalse(Burgenplan.lesen(f));
        Files.writeString(f, "# Burgen alle 30 Minuten\nan\n");
        assertTrue(Burgenplan.lesen(f));
        Files.writeString(f, "aus\n");
        assertFalse(Burgenplan.lesen(f));
        Files.writeString(f, "on");
        assertTrue(Burgenplan.lesen(f));
    }
}
