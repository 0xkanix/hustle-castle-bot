package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 21.10.: "ein Name soll nicht 3 mal in Doable stehen" und alle Bilder, die der Bot aufnimmt, in denselben
 * Ordner wie die Bilder aus dem Capture-Programm.
 */
class ArenaListenAufraeumenTest {

    @TempDir
    Path bot;

    @Test
    void nameAusDemDateinamen() {
        assertEquals("Denis", ArenaVorlagenDoppelt.nameAusDatei("ar_sicher_Denis_1019-0249.png"));
        assertEquals("WalkingDeath", ArenaVorlagenDoppelt.nameAusDatei("ar_machbar_WalkingDeath.png"));
        assertTrue(ArenaVorlagenDoppelt.vomBot("ar_sicher_Denis_1019-0249.png"));
        assertFalse(ArenaVorlagenDoppelt.vomBot("ar_machbar_WalkingDeath.png"));
        assertFalse(ArenaVorlagenDoppelt.vomBot("ar_ungern_player1019_163424.png"));
    }

    @Test
    void derselbeSpieler() {
        assertTrue(ArenaVorlagenDoppelt.derselbeName("Loki", "loki"));
        assertTrue(ArenaVorlagenDoppelt.derselbeName("Andercastle", "Andercastl"));
        assertFalse(ArenaVorlagenDoppelt.derselbeName("Denis", "Dennis"));   // kurz: kein Lesefehler erlaubt
        assertFalse(ArenaVorlagenDoppelt.derselbeName("Alex", "Aleks"));
        assertFalse(ArenaVorlagenDoppelt.derselbeName("", "Loki"));
    }

    @Test
    void aufraeumenZiehtUmUndSortiertDoppelteAus() throws Exception {
        Path templates = bot.resolve("templates");
        Path gelernt = bot.resolve("gelernt").resolve("arena").resolve("gegner");
        // zweimal Denis in sicher (Bot), Machtjanix in machbar (alt) und sicher (neu), Loki/loki
        Path d1 = datei(gelernt.resolve("sicher"), "ar_sicher_Denis_1019-0249.png", 1000);
        Path d2 = datei(gelernt.resolve("sicher"), "ar_sicher_Denis_1019-0305.png", 2000);
        datei(gelernt.resolve("machbar"), "ar_machbar_Machtjanix_1019-2039.png", 1000);
        datei(gelernt.resolve("sicher"), "ar_sicher_Machtjanix_1019-2217.png", 2000);
        datei(gelernt.resolve("sicher"), "ar_sicher_loki_1019-0303.png", 1000);
        datei(gelernt.resolve("sicher"), "ar_sicher_Loki_1019-0049.png", 500);
        // eigene Aufnahme des Spielers: Loki in meiden -- geht vor
        datei(bot.resolve("My pictures").resolve("Arena lists").resolve("avoid"), "ar_ungern_Loki.png", 100);
        datei(gelernt.resolve("machbar"), "ar_machbar_Yoshi_1021-0139.png", 1000);

        int[] r = ArenaVorlagenDoppelt.aufraeumen(bot, templates);

        Path sicher = bot.resolve("My pictures").resolve("Arena lists").resolve("safe");
        Path machbar = bot.resolve("My pictures").resolve("Arena lists").resolve("doable");
        // alles aus gelernt\ ist umgezogen
        try (var s = Files.walk(bot.resolve("gelernt"))) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".png")).count());
        }
        // Denis: nur die neueste bleibt
        assertTrue(Files.exists(sicher.resolve(d2.getFileName())));
        assertFalse(Files.exists(sicher.resolve(d1.getFileName())));
        // Machtjanix: die neueste Liste (sicher) gilt
        assertTrue(Files.exists(sicher.resolve("ar_sicher_Machtjanix_1019-2217.png")));
        assertFalse(Files.exists(machbar.resolve("ar_machbar_Machtjanix_1019-2039.png")));
        // Loki: die eigene Aufnahme des Spielers gilt, beide Bot-Bilder sind aussortiert
        assertTrue(Files.exists(bot.resolve("My pictures/Arena lists/avoid/ar_ungern_Loki.png")));
        assertFalse(Files.exists(sicher.resolve("ar_sicher_loki_1019-0303.png")));
        assertFalse(Files.exists(sicher.resolve("ar_sicher_Loki_1019-0049.png")));
        assertTrue(Files.exists(machbar.resolve("ar_machbar_Yoshi_1021-0139.png")));
        // nichts geloescht: die Aussortierten liegen in templates-aussortiert
        try (var s = Files.walk(bot.resolve("templates-aussortiert"))) {
            assertEquals(4, s.filter(p -> p.toString().endsWith(".png")).count());
        }
        assertEquals(4, r[1]);
        // ein zweiter Lauf aendert nichts mehr
        assertArrayEquals(new int[]{0, 0}, ArenaVorlagenDoppelt.aufraeumen(bot, templates));
    }

    private static Path datei(Path ordner, String name, long zeit) throws Exception {
        Files.createDirectories(ordner);
        Path p = ordner.resolve(name);
        Files.write(p, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(p, FileTime.fromMillis(zeit * 1000));
        return p;
    }
}
