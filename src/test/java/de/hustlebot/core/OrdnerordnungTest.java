package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrdnerordnungTest {

    private static boolean versteckt(Path p) throws Exception {
        return (Boolean) Files.getAttribute(p, "dos:hidden", LinkOption.NOFOLLOW_LINKS);
    }

    @Test
    void nurDreiDateienBleibenSichtbar(@TempDir Path ordner) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Files.createFile(ordner.resolve("HustleBot.exe"));
        Files.createFile(ordner.resolve("Capture-Images.exe"));
        Files.createFile(ordner.resolve("0-START-HERE.txt"));
        Files.createFile(ordner.resolve("config.properties"));
        Files.createDirectory(ordner.resolve("app"));

        Ordnerordnung.anwenden(ordner);

        assertEquals(false, versteckt(ordner.resolve("HustleBot.exe")));
        assertEquals(false, versteckt(ordner.resolve("Capture-Images.exe")));
        assertEquals(false, versteckt(ordner.resolve("0-START-HERE.txt")));
        assertEquals(true, versteckt(ordner.resolve("config.properties")));
        assertEquals(true, versteckt(ordner.resolve("app")));
    }

    @Test
    void eigeneBilderBleibenSichtbarTemplatesNicht(@TempDir Path ordner) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Files.createDirectories(ordner.resolve("templates"));
        Eigenbilder.einrichten(ordner);
        Ordnerordnung.anwenden(ordner);
        assertEquals(false, versteckt(Eigenbilder.wurzel(ordner)));
        assertEquals(true, versteckt(ordner.resolve("templates")));
    }

    @Test
    void mitOrdnerOffenBleibtAllesSichtbar(@TempDir Path ordner) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Files.createFile(ordner.resolve("config.properties"));
        Ordnerordnung.anwenden(ordner);
        assertEquals(true, versteckt(ordner.resolve("config.properties")));

        Files.createFile(ordner.resolve(Ordnerordnung.OFFEN));
        Ordnerordnung.anwenden(ordner);
        assertEquals(false, versteckt(ordner.resolve("config.properties")));
        assertEquals(false, versteckt(ordner.resolve(Ordnerordnung.OFFEN)));
    }
}
