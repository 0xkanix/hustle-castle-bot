package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Die deutschen Pakete bis 2.5 hatten keine sprache.txt (Deutsch war der Standard). Nach dem Update auf 3.3
 * sollen sie deutsch bleiben (16.10.); ein frischer Bot ohne Schluessel und jede vorhandene sprache.txt
 * gelten wie bisher.
 */
class SpracheAltesPaketTest {

    @TempDir
    Path ordner;

    @Test
    void eingerichteterBotOhneSprachdateiBleibtDeutsch() throws Exception {
        Files.writeString(ordner.resolve("config.properties"), "discord.botToken=\nlizenz.schluessel=HCB1.abc\n");
        assertEquals(Sprache.Lang.DE, Sprache.laden(ordner.resolve("sprache.txt")));
    }

    @Test
    void ohneSchluesselUndOhneDateiEnglisch() throws Exception {
        Files.writeString(ordner.resolve("config.properties"), "lizenz.schluessel=\n");
        assertEquals(Sprache.Lang.EN, Sprache.laden(ordner.resolve("sprache.txt")));
        Files.delete(ordner.resolve("config.properties"));
        assertEquals(Sprache.Lang.EN, Sprache.laden(ordner.resolve("sprache.txt")));
    }

    @Test
    void vorhandeneSprachdateiGewinnt() throws Exception {
        Files.writeString(ordner.resolve("config.properties"), "lizenz.schluessel=HCB1.abc\n");
        Files.writeString(ordner.resolve("sprache.txt"), "en");
        assertEquals(Sprache.Lang.EN, Sprache.laden(ordner.resolve("sprache.txt")));
    }
}
