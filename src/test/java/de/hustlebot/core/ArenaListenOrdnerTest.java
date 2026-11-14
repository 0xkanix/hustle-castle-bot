package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 28.10.: die Arena-Listen wie normale Ordner -- der Ordner entscheidet, und ein vom Bot eingetragenes Bild,
 * das Autor loescht, merkt sich der Bot in arena-entfernt.txt.
 */
class ArenaListenOrdnerTest {

    @TempDir
    Path bot;

    // Nur mit den Arena-Neuerungen (arena-schicht.txt "an") -- in diesen Tests an, sonst aus.
    @org.junit.jupiter.api.BeforeEach
    void schalterAn() {
        ArenaSchalter.fuerTest(true);
    }

    @org.junit.jupiter.api.AfterEach
    void schalterZurueck() {
        ArenaSchalter.fuerTest(null);
    }

    @Test
    void ohneSchalterBleibtAllesWieBisher() throws Exception {
        ArenaSchalter.fuerTest(false);
        Path bild = liste("never").resolve("ar_sicher_Rina.png");
        Files.createDirectories(bild.getParent());
        Files.write(bild, new byte[]{1});
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());
        assertTrue(Files.exists(bild), "ohne Schalter wird nichts umbenannt (Freunde)");
        assertFalse(Files.exists(entfernt()));
    }

    private Path templates() {
        return bot.resolve("templates");
    }

    private Path liste(String ordner) {
        return bot.resolve("My pictures").resolve("Arena lists").resolve(ordner);
    }

    private Path entfernt() {
        return bot.resolve(ArenaListenOrdner.ENTFERNT);
    }

    @Test
    void ordnerEntscheidet() throws Exception {
        datei(liste("never"), "ar_sicher_Denis.png", 1000, 1);
        datei(liste("safe"), "ar_sicher_Loki.png", 1000, 2);
        datei(liste("clans"), "ar_sicher_Clanbild.png", 1000, 3);           // clans bleibt, wie es ist
        datei(liste("doable"), "ar_ungern_Yoshi_auto_1028-1430.png", 1000, 4);

        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        assertTrue(Files.exists(liste("never").resolve("ar_block_Denis.png")));
        assertFalse(Files.exists(liste("never").resolve("ar_sicher_Denis.png")));
        assertTrue(Files.exists(liste("safe").resolve("ar_sicher_Loki.png")));
        assertTrue(Files.exists(liste("clans").resolve("ar_sicher_Clanbild.png")));
        // verschobenes Bot-Bild: ab jetzt Autors Entscheidung, "_auto" faellt weg (Review 28.10.)
        assertTrue(Files.exists(liste("doable").resolve("ar_machbar_Yoshi.png")));
        assertFalse(Files.exists(bot.resolve("templates-aussortiert")));
        // ein zweiter Lauf aendert nichts mehr
        assertArrayEquals(new int[]{0, 0}, ArenaVorlagenDoppelt.aufraeumen(bot, templates()));
        assertTrue(Files.exists(liste("never").resolve("ar_block_Denis.png")));
    }

    @Test
    void ordnerEntscheidetGibtEsSchonKommtNachAussortiert() throws Exception {
        datei(liste("never"), "ar_sicher_Denis.png", 1000, 1);
        datei(liste("never"), "ar_block_Denis.png", 500, 2);

        int[] r = ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        assertArrayEquals(new byte[]{2}, Files.readAllBytes(liste("never").resolve("ar_block_Denis.png")));
        Path weg = bot.resolve("templates-aussortiert/My pictures/Arena lists/never/ar_sicher_Denis.png");
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(weg));            // nichts geloescht
        assertFalse(Files.exists(liste("never").resolve("ar_sicher_Denis.png")));
        assertEquals(1, r[1]);
    }

    @Test
    void verschiebenGehtVorEigeneAufnahme() throws Exception {
        datei(liste("safe"), "ar_sicher_Denis.png", 5000, 1);                  // eigene Aufnahme, neuer
        datei(liste("never"), "ar_machbar_Denis_auto_1028-1430.png", 1000, 2); // Bot-Bild, von Autor nach never gezogen

        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        assertTrue(Files.exists(liste("never").resolve("ar_block_Denis.png")));
        assertFalse(Files.exists(liste("safe").resolve("ar_sicher_Denis.png")));
        assertTrue(Files.exists(bot.resolve("templates-aussortiert/My pictures/Arena lists/safe/ar_sicher_Denis.png")));
    }

    @Test
    void geloeschtesAutoBildWirdGemerkt() throws Exception {
        Path denis = datei(liste("safe"), "ar_sicher_Denis_auto_1028-1430.png", 1000, 1);
        datei(liste("doable"), "ar_machbar_Loki_auto_1028-1431.png", 1000, 2);
        datei(liste("safe"), "ar_sicher_Eigen.png", 1000, 3);                  // eigene Aufnahme: nicht gemerkt

        ArenaVorlagenDoppelt.aufraeumen(bot, templates());
        String merker = Files.readString(bot.resolve(ArenaListenOrdner.MERKER), StandardCharsets.UTF_8);
        assertTrue(merker.contains("ar_sicher_Denis_auto_1028-1430.png"));
        assertTrue(merker.contains("ar_machbar_Loki_auto_1028-1431.png"));
        assertFalse(merker.contains("Eigen"));
        assertFalse(Files.exists(entfernt()));

        Files.delete(denis);                                                   // Autor loescht Denis
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        assertEquals(1, eintraege().size());
        assertTrue(eintraege().get(0).startsWith("Denis | safe | "));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "denis"));
        assertFalse(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Loki"));
        assertFalse(ArenaListenOrdner.vonAutorEntfernt(entfernt(), ""));

        // naechster Start: nicht noch einmal eingetragen
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());
        assertEquals(1, eintraege().size());
    }

    @Test
    void umbenanntZaehltNichtAlsGeloescht() throws Exception {
        Path denis = datei(liste("safe"), "ar_sicher_Denis_auto_1028-1430.png", 1000, 1);
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        // Autor zieht das Bild von safe nach never
        Files.createDirectories(liste("never"));
        Files.move(denis, liste("never").resolve(denis.getFileName()));
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        // verschoben = Autors Entscheidung: "_auto" faellt weg (Review 28.10.), und es zaehlt nicht als geloescht
        assertTrue(Files.exists(liste("never").resolve("ar_block_Denis.png")));
        assertFalse(Files.exists(entfernt()));
        assertFalse(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Denis"));
        String merker = Files.readString(bot.resolve(ArenaListenOrdner.MERKER), StandardCharsets.UTF_8);
        assertFalse(merker.contains("Denis"));   // kein Bot-Bild mehr

        // auch wenn Autor es danach selbst umbenennt (Vorsilbe passend zum Ordner): nicht geloescht
        Path block = liste("never").resolve("ar_block_Denis.png");
        Files.createDirectories(liste("avoid"));
        Files.move(block, liste("avoid").resolve("ar_ungern_Denis.png"));
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());
        assertFalse(Files.exists(entfernt()));
    }

    @Test
    void vomBotAussortiertZaehltNichtAlsGeloescht() throws Exception {
        Files.createDirectories(liste("safe"));
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());                     // Start ohne _auto-Bilder
        Path merker = bot.resolve(ArenaListenOrdner.MERKER);

        // waehrend des Laufs legt der Bot zwei Bilder ab
        Path denis = datei(liste("safe"), ArenaListenOrdner.autoDateiname("safe", "Denis", java.time.LocalDateTime.of(2026, 10, 28, 14, 30)), 1000, 1);
        Path loki = datei(liste("safe"), "ar_sicher_Loki_auto_1028-1431.png", 1000, 2);
        ArenaListenOrdner.autoBildGemerkt(merker, denis);
        ArenaListenOrdner.autoBildGemerkt(merker, loki);
        ArenaListenOrdner.autoBildGemerkt(merker, loki);                        // doppelt -> einmal
        ArenaListenOrdner.autoBildGemerkt(merker, liste("safe").resolve("ar_sicher_Eigen.png"));   // kein _auto
        List<String> zeilen = Files.readAllLines(merker, StandardCharsets.UTF_8);
        assertEquals(List.of("ar_sicher_Denis_auto_1028-1430.png", "ar_sicher_Loki_auto_1028-1431.png"), zeilen);

        // Bot sortiert Loki selbst aus (wie ArenaMode), Autor loescht Denis
        Path weg = ArenaVorlagenDoppelt.aussortiertZiel(bot, loki);
        Files.createDirectories(weg.getParent());
        Files.move(loki, weg);
        Files.delete(denis);
        ArenaVorlagenDoppelt.aufraeumen(bot, templates());

        assertEquals(1, eintraege().size());
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Denis"));
        assertFalse(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Loki"));
    }

    @Test
    void autorsZeileLoeschenErlaubtWieder() throws Exception {
        Files.writeString(entfernt(), "# Erklaerung\r\nDenis | safe | 2026-10-28 14:30\r\nLoki | never | 2026-10-28 14:31\r\n");
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Denis"));
        Files.writeString(entfernt(), "# Erklaerung\r\nLoki | never | 2026-10-28 14:31\r\n");
        assertFalse(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "Denis"));
        assertTrue(ArenaListenOrdner.vonAutorEntfernt(entfernt(), "loki"));
    }

    /** Die Eintraege in arena-entfernt.txt (ohne Erklaerungszeilen). */
    private List<String> eintraege() throws Exception {
        if (!Files.exists(entfernt())) return List.of();
        return Files.readAllLines(entfernt(), StandardCharsets.UTF_8).stream()
                .filter(z -> !z.isBlank() && !z.startsWith("#")).toList();
    }

    private static Path datei(Path ordner, String name, long zeit, int inhalt) throws Exception {
        Files.createDirectories(ordner);
        Path p = ordner.resolve(name);
        Files.write(p, new byte[]{(byte) inhalt});
        Files.setLastModifiedTime(p, FileTime.fromMillis(zeit * 1000));
        return p;
    }
}
