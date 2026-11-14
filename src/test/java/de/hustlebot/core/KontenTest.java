package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Konto-Profile (Autor 21.10., Mia mit mehreren Spielkonten). */
class KontenTest {

    @TempDir
    Path bot;

    private Path templates() throws Exception {
        return Files.createDirectories(bot.resolve("templates"));
    }

    private void datei(Path p, String inhalt) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
    }

    @Test
    void ohneSchalterPassiertNichts() {
        assertEquals(Optional.empty(), Konten.erkennen("Mia", bot, false));
        assertFalse(Files.exists(bot.resolve("konto-aktiv.txt")));
    }

    @Test
    void erstesKontoWirdNurVermerktZweitesBrauchtZweiLesungen() {
        assertEquals(Optional.empty(), Konten.erkennen("Mia", bot, true));
        assertEquals("Mia", Konten.aktiv(bot));
        // Lesefehler am selben Namen: kein Wechsel
        assertEquals(Optional.empty(), Konten.erkennen("mia", bot, true));
        // neuer Name: erst beim zweiten gleichen Lesen
        assertEquals(Optional.empty(), Konten.erkennen("MiaZwei", bot, true));
        assertEquals(Optional.of("MiaZwei"), Konten.erkennen("MiaZwei", bot, true));
    }

    @Test
    void wechselnTauschtDateienUndListen() throws Exception {
        Path t = templates();
        Konten.erkennen("Mia", bot, true);
        datei(bot.resolve("arena-ich.txt"), "punkte=40");
        datei(bot.resolve("bezahlung.txt"), "arena=food+chests\nlager=900000");
        datei(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png"), "x");
        datei(bot.resolve("config.properties"), "bleibt");

        assertTrue(Konten.wechseln("Zweitkonto", bot, t));
        assertEquals("Zweitkonto", Konten.aktiv(bot));
        // das alte Konto ruht in konten\mia
        assertEquals("punkte=40", Files.readString(bot.resolve("konten/mia/arena-ich.txt")));
        assertTrue(Files.exists(bot.resolve("konten/mia/gegner/sicher/ar_sicher_Loki_1019-0049.png")));
        // das neue Konto faengt ohne Team-Scan und ohne Listen an, die Bezahlart kommt mit
        assertFalse(Files.exists(bot.resolve("arena-ich.txt")));
        assertFalse(Files.exists(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png")));
        assertTrue(Files.readString(bot.resolve("bezahlung.txt")).contains("food+chests"));
        // config.properties gehoert keinem Konto
        assertEquals("bleibt", Files.readString(bot.resolve("config.properties")));

        datei(bot.resolve("arena-ich.txt"), "punkte=90");
        assertTrue(Konten.wechseln("Mia", bot, t));
        assertEquals("punkte=40", Files.readString(bot.resolve("arena-ich.txt")));
        assertTrue(Files.exists(bot.resolve("My pictures/Arena lists/safe/ar_sicher_Loki_1019-0049.png")));
        assertEquals("punkte=90", Files.readString(bot.resolve("konten/zweitkonto/arena-ich.txt")));
        // bekannte Konten: beide, das aktive zuerst
        assertEquals(java.util.List.of("Mia", "Zweitkonto"), Konten.bekannt(bot));
        // zurueck zum bekannten Zweitkonto: sofort, ohne zweite Lesung
        assertEquals(Optional.of("Zweitkonto"), Konten.erkennen("Zweitkonto", bot, true));
    }
}
