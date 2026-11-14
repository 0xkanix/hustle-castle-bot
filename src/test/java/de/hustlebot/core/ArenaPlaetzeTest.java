package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Statistik Top 1 / 2 / 3 insgesamt und je Tag (Autor 21.10.). */
class ArenaPlaetzeTest {

    @TempDir
    Path ordner;

    @Test
    void ausDemProtokollMitTagwechsel() {
        String s = ArenaPlaetze.ausProtokoll("bot_2026-10-16_23-50-00.log", List.of(
                "23:55:01.000  [INFO] Arena: Tournament finished in place 3. 1 tournaments so far, 1 of them in the top three.",
                "00:09:36.135  [INFO] Arena: Tournament finished in place 8. 2 tournaments so far, 1 of them in the top three.",
                "00:21:37.348  [INFO] Arena: Turnier beendet auf Platz 1. Bisher 3 Turniere, davon 2 in den ersten drei."));
        assertEquals("2026-10-16 23:55\t3\n2026-10-17 00:09\t8\n2026-10-17 00:21\t1\n", s);
    }

    @Test
    void jeTagUndGesamt() {
        var t = ArenaPlaetze.jeTag("# Kopf\n2026-10-16 23:55\t3\n2026-10-17 00:09\t8\n2026-10-17 00:21\t1\n2026-10-17 01:00\t1\n");
        assertArrayEquals(new int[]{1, 0, 0, 1}, t.get("2026-10-16"));
        assertArrayEquals(new int[]{3, 2, 0, 0}, t.get("2026-10-17"));
        assertArrayEquals(new int[]{4, 2, 0, 1}, ArenaPlaetze.gesamt(t));
        String text = ArenaPlaetze.text("2026-10-16 23:55\t3\n2026-10-17 00:21\t1\n");
        assertTrue(text.startsWith(Sprache.t("Total: 2", "Gesamt: 2")), text);
        assertTrue(text.indexOf("17.10.") < text.indexOf("16.10."), "neuester Tag oben: " + text);
    }

    @Test
    void nachholenSchreibtAlleProtokolle() throws Exception {
        Path logs = Files.createDirectories(ordner.resolve("logs"));
        Files.writeString(logs.resolve("bot_2026-10-18_10-00-00.log"), "10:20:00.000  [INFO] Arena: Tournament finished in place 2. x\n");
        Files.writeString(logs.resolve("bot_2026-10-19_10-00-00.log"), "11:00:00.000  [INFO] Arena: Tournament finished in place 5. x\n");
        Files.writeString(logs.resolve("anderes.log"), "11:00:00.000  [INFO] Arena: Tournament finished in place 1. x\n");
        Path ziel = ordner.resolve("arena-plaetze.txt");
        ArenaPlaetze.nachholen(logs, ziel);
        var t = ArenaPlaetze.jeTag(Files.readString(ziel));
        assertEquals(2, t.size());
        assertArrayEquals(new int[]{2, 0, 1, 0}, ArenaPlaetze.gesamt(t));
    }
}
