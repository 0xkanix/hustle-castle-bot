package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KommentarPaketeTest {

    @Test
    void arenaOhneKommentarErgibtKeinPaket(@TempDir Path ordner) {
        KommentarPakete.abschliessen(ordner, 1_000);
        assertEquals(0, KommentarPakete.pakete(ordner));
    }

    @Test
    void kommentareEinerArenaWerdenEinPaket(@TempDir Path ordner) throws Exception {
        long jetzt = System.currentTimeMillis();
        KommentarPakete.anhaengen(ordner, "eins", jetzt);
        KommentarPakete.anhaengen(ordner, "zwei", jetzt);
        assertEquals(0, KommentarPakete.pakete(ordner), "die Arena laeuft noch");

        KommentarPakete.abschliessen(ordner, jetzt);
        assertEquals(1, KommentarPakete.pakete(ordner));
        assertFalse(Files.exists(ordner.resolve(KommentarPakete.OFFEN)));
        try (var s = Files.list(ordner)) {
            String inhalt = Files.readString(s.findFirst().orElseThrow());
            assertTrue(inhalt.contains("eins") && inhalt.contains("zwei"));
        }
    }

    @Test
    void langeStilleHeisstNeueArena(@TempDir Path ordner) throws Exception {
        long jetzt = System.currentTimeMillis();
        KommentarPakete.anhaengen(ordner, "alt", jetzt);
        Files.setLastModifiedTime(ordner.resolve(KommentarPakete.OFFEN),
                FileTime.fromMillis(jetzt - KommentarPakete.LUECKE_MS - 1_000));
        KommentarPakete.anhaengen(ordner, "neu", jetzt);

        assertEquals(1, KommentarPakete.pakete(ordner));
        assertEquals("neu", Files.readString(ordner.resolve(KommentarPakete.OFFEN)).strip());
    }

    @Test
    void zweiPaketeInDerselbenSekundeBleibenGetrennt(@TempDir Path ordner) {
        long jetzt = System.currentTimeMillis();
        KommentarPakete.anhaengen(ordner, "a", jetzt);
        KommentarPakete.abschliessen(ordner, jetzt);
        KommentarPakete.anhaengen(ordner, "b", jetzt);
        KommentarPakete.abschliessen(ordner, jetzt);
        assertEquals(2, KommentarPakete.pakete(ordner));
    }
}
