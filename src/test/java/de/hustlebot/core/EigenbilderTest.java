package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EigenbilderTest {

    @Test
    void ordnerFuerSortiertEigeneAufnahmenEin(@TempDir Path bot) {
        Path w = bot.resolve("My pictures");
        assertEquals(w.resolve("Arena lists").resolve("safe"), Eigenbilder.ordnerFuer(bot, "ar_sicher_Loki"));
        assertEquals(w.resolve("Arena lists").resolve("never"), Eigenbilder.ordnerFuer(bot, "ar_block_3"));
        assertEquals(w.resolve("Event rewards"), Eigenbilder.ordnerFuer(bot, "ev_kiste_4"));
        assertEquals(w.resolve("Clan war"), Eigenbilder.ordnerFuer(bot, "cw_angriff"));
        assertEquals(w.resolve("Cathedral"), Eigenbilder.ordnerFuer(bot, "key_7"));
        assertEquals(w.resolve("Dungeon"), Eigenbilder.ordnerFuer(bot, "friend_Rina"));
        assertEquals(w.resolve("Arena lists").resolve("avoid"), Eigenbilder.listenOrdner(bot, "ar_ungern_"));
    }

    @Test
    void einrichtenZiehtAlteListenUm(@TempDir Path bot) throws Exception {
        Path alt = bot.resolve("templates/arena/gegner/sicher/ar_sicher_Loki.png");
        Files.createDirectories(alt.getParent());
        Files.writeString(alt, "x");

        assertEquals(1, Eigenbilder.einrichten(bot));

        assertFalse(Files.exists(alt));
        assertTrue(Files.exists(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki.png")));
        assertTrue(Files.exists(bot.resolve("My pictures/README.txt")));
        // ein zweiter Start aendert nichts mehr
        assertEquals(0, Eigenbilder.einrichten(bot));
    }
}
