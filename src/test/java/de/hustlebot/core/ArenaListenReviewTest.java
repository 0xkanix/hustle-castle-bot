package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Funde aus dem Code-Review vom 28.10. (Listen-Ordner, "_auto"-Bilder). */
class ArenaListenReviewTest {

    @TempDir
    Path bot;

    @BeforeEach
    void schalterAn() {
        ArenaSchalter.fuerTest(true);
    }

    @AfterEach
    void schalterZurueck() {
        ArenaSchalter.fuerTest(null);
    }

    private Path liste(String ordner) {
        return bot.resolve("My pictures").resolve("Arena lists").resolve(ordner);
    }

    @Test
    void spielerNamensAutoIstKeinAnzeigeBild() {
        // K1: das normale Bot-Bild eines Spielers "auto" darf nicht als reines Anzeige-Bild gelten (Freunde!).
        assertFalse(ArenaListenOrdner.istAuto("ar_block_auto_1028-1430.png"));
        assertEquals("auto", ArenaVorlagenDoppelt.nameAusDatei("ar_block_auto_1028-1430.png"));
        assertTrue(ArenaListenOrdner.istAuto("ar_block_auto_auto_1028-1430.png"));   // Spieler "auto", vom Bot eingetragen
        assertTrue(ArenaListenOrdner.istAuto("ar_sicher_Denis_auto_1028-1430.png"));
        assertFalse(ArenaListenOrdner.istAuto("ar_sicher_Denis_1028-1430.png"));
    }

    @Test
    void verschobenesBildVerliertDasBotKennzeichen() throws Exception {
        // K3: Autors Verschieben ist seine Entscheidung -- kein "_MMdd-HHmm" mehr, sonst ersetzt der Bot es spaeter.
        Files.createDirectories(liste("avoid"));
        Files.write(liste("avoid").resolve("ar_sicher_X_1028-1430.png"), new byte[]{1});
        ArenaVorlagenDoppelt.aufraeumen(bot, bot.resolve("templates"));
        assertTrue(Files.exists(liste("avoid").resolve("ar_ungern_X.png")));
        assertFalse(ArenaVorlagenDoppelt.vomBot("ar_ungern_X.png"));
    }

    @Test
    void geloeschtWaehrendDesLaufsWirdErkannt() throws Exception {
        // K4: Autor loescht ein _auto-Bild, waehrend der Bot laeuft -> nicht wieder anlegen.
        Files.createDirectories(liste("safe"));
        Path bild = liste("safe").resolve("ar_sicher_Denis_auto_1028-1430.png");
        Files.write(bild, new byte[]{1});
        ArenaListenOrdner.autoBildGemerkt(bot.resolve(ArenaListenOrdner.MERKER), bild);
        assertFalse(ArenaListenOrdner.geloeschtWaehrendLauf(bot, "Denis"));   // liegt noch da
        Files.delete(bild);
        assertTrue(ArenaListenOrdner.geloeschtWaehrendLauf(bot, "Denis"));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(bot.resolve(ArenaListenOrdner.ENTFERNT), "Denis"));
        assertFalse(ArenaListenOrdner.geloeschtWaehrendLauf(bot, "Loki"));
    }

    @Test
    void anhaengenOhneZeilenumbruchAmEnde() throws Exception {
        // K6: Autor hat die letzte Zeile samt Umbruch geloescht -> der naechste Eintrag kommt trotzdem in eine eigene Zeile.
        Files.createDirectories(liste("safe"));
        Path entfernt = bot.resolve(ArenaListenOrdner.ENTFERNT);
        Files.writeString(entfernt, "# Kopf\r\nLoki | safe | 2026-10-28 14:30", StandardCharsets.UTF_8);
        Path bild = liste("safe").resolve("ar_sicher_Denis_auto_1028-1430.png");
        Files.write(bild, new byte[]{1});
        ArenaListenOrdner.autoBildGemerkt(bot.resolve(ArenaListenOrdner.MERKER), bild);
        Files.delete(bild);
        assertTrue(ArenaListenOrdner.geloeschtWaehrendLauf(bot, "Denis"));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt, "Loki"));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt, "Denis"));
    }

    @Test
    void ansiGespeicherteDateiWirdGelesen() throws Exception {
        // K9: im Editor als ANSI gespeichert -> "Jörg" bleibt "Jörg".
        Path entfernt = bot.resolve("arena-entfernt-ansi.txt");
        Files.write(entfernt, "Jörg | safe | 2026-10-28 14:30\r\n".getBytes(Charset.forName("windows-1252")));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt, "Jörg"));
    }
}
