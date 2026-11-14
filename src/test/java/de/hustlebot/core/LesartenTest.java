package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 28.10.: Woerterbuch "so gelesen -> so heisst er" (arena-lesarten.txt). */
class LesartenTest {

    @AfterEach
    void zurueck() {
        Lesarten.hoechstens = Lesarten.HOECHSTENS;
    }

    @Test
    void giltErstAbZweiBestaetigungen(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve(Lesarten.DATEINAME);
        Lesarten l = new Lesarten(datei);
        l.merken("JtWXED mUREN", "JtNXED", "Июльский ветер", 26_304_529L);
        assertTrue(l.kanonisch("JtWXED mUREN").isEmpty(), "einmal gesehen reicht nicht");
        l.merken("JtWXED mUREN", "JtNXED", "", -1);
        Optional<Lesarten.Lesart> k = l.kanonisch("JtWXED mUREN");
        assertTrue(k.isPresent());
        assertEquals("JtNXED", k.get().kanonisch());
        assertEquals("Июльский ветер", k.get().clan(), "leerer Clan ueberschreibt den bekannten nicht");
        assertEquals(26_304_529L, k.get().kraft());
        assertEquals(2, k.get().anzahl());
        List<String> zeilen = Files.readAllLines(datei, StandardCharsets.UTF_8);
        assertTrue(zeilen.contains("JtWXED mUREN | JtNXED | Июльский ветер | 26304529 | 2"), zeilen.toString());
    }

    @Test
    void exaktNachDerForm(@TempDir Path tmp) {
        Lesarten l = new Lesarten(tmp.resolve(Lesarten.DATEINAME));
        l.merken("KoponeBa Ll", "Королева", "Дружба", -1);
        l.merken("KoponeBa Ll", "Королева", "Дружба", -1);
        assertEquals("Королева", l.kanonisch("koponeba-ll").orElseThrow().kanonisch(), "Form: nur Buchstaben/Ziffern, klein");
        assertEquals("Королева", l.kanonisch(" KOPONEBA LL ").orElseThrow().kanonisch());
        assertTrue(l.kanonisch("KoponeBa L").isEmpty(), "aehnlich ist nicht gleich");
        assertTrue(l.kanonisch("").isEmpty());
        assertTrue(l.kanonisch(null).isEmpty());
        assertTrue(l.kanonisch("...").isEmpty());
    }

    @Test
    void zweiNamenMitDerselbenLesart(@TempDir Path tmp) {
        Lesarten l = new Lesarten(tmp.resolve(Lesarten.DATEINAME));
        for (int i = 0; i < 2; i++) {
            l.merken("Lord", "Lore", "", -1);
            l.merken("Lord", "Lord", "", -1);
        }
        assertTrue(l.kanonisch("Lord").isEmpty(), "gleich oft bestaetigt -> unklar");
        l.merken("Lord", "Lord", "", -1);
        assertEquals("Lord", l.kanonisch("Lord").orElseThrow().kanonisch());
    }

    @Test
    void neuLadenUndKaputteZeilen(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve(Lesarten.DATEINAME);
        Files.writeString(datei, String.join("\n",
                "# Kopf",
                "",
                "nur ein Feld",
                " | Leer | | | 5",
                "Xen0s | Xenos | 奶茶多奶走冰加奶霜 | 26,304,529 | 3",
                "Xen0s | Xenos |  | abc | x",           // Dopplung: zaehlt dazu, kaputte Zahlen = unbekannt / 1
                "IDKFA | IDKFA",                          // nur zwei Felder
                "kutaenaplet | kutaenaplet | | 1.234.567 | 2"), StandardCharsets.UTF_8);
        Lesarten l = new Lesarten(datei);
        Lesarten.Lesart x = l.kanonisch("xen0s").orElseThrow();
        assertEquals("Xenos", x.kanonisch());
        assertEquals("奶茶多奶走冰加奶霜", x.clan());
        assertEquals(26_304_529L, x.kraft());
        assertEquals(4, x.anzahl());
        assertTrue(l.kanonisch("IDKFA").isEmpty(), "anzahl fehlt -> 1");
        assertEquals(1_234_567L, l.kanonisch("kutaenaplet").orElseThrow().kraft());
        assertEquals(3, l.groesse());

        // von Hand geaendert (anderer Inhalt): wird neu gelesen
        Files.writeString(datei, "Neu | Neuer Name | | | 7\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(datei, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals("Neuer Name", l.kanonisch("neu").orElseThrow().kanonisch());
        assertTrue(l.kanonisch("xen0s").isEmpty());
    }

    @Test
    void trennerImNamenZerlegtDieZeileNicht(@TempDir Path tmp) {
        Path datei = tmp.resolve(Lesarten.DATEINAME);
        Lesarten l = new Lesarten(datei);
        l.merken("a|b\nc", "Na|me\tX", "Cl|an", 5);
        l.merken("a|b\nc", "Na|me\tX", "Cl|an", 5);
        Lesarten.Lesart x = new Lesarten(datei).kanonisch("abc").orElseThrow();
        assertEquals("Na/me X", x.kanonisch());
        assertEquals("Cl/an", x.clan());
        assertEquals(5, x.kraft());
    }

    @Test
    void schreibfehlerWirftNicht(@TempDir Path tmp) throws Exception {
        Path blockiert = tmp.resolve("ist-eine-datei");
        Files.writeString(blockiert, "x");
        Lesarten l = new Lesarten(blockiert.resolve("unter").resolve(Lesarten.DATEINAME));
        assertDoesNotThrow(() -> {
            l.merken("abc", "Abc", "", -1);
            l.merken("abc", "Abc", "", -1);
        });
        assertEquals("Abc", l.kanonisch("abc").orElseThrow().kanonisch(), "im Speicher bleibt es trotzdem");
        // leere Angaben werden nicht gemerkt
        l.merken("", "X", "", -1);
        l.merken("x", " ", "", -1);
        l.merken(null, null, null, -1);
        assertEquals(1, l.groesse());
    }

    @Test
    void zuVieleZeilenWerdenGekuerzt(@TempDir Path tmp) {
        Lesarten.hoechstens = 3;
        Lesarten l = new Lesarten(tmp.resolve(Lesarten.DATEINAME));
        l.merken("oft", "Oft", "", -1);
        l.merken("oft", "Oft", "", -1);
        for (int i = 0; i < 5; i++) l.merken("einmal" + i, "E" + i, "", -1);
        assertEquals(3, l.groesse());
        assertTrue(l.kanonisch("oft").isPresent(), "die bestaetigte Lesart bleibt");
    }

    @Test
    void vieleFaedenGleichzeitig(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve(Lesarten.DATEINAME);
        Lesarten l = new Lesarten(datei);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> f = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            f.add(pool.submit(() -> {
                for (int j = 0; j < 25; j++) {
                    l.merken("Gleich", "Gleich", "Clan", 100 + n);
                    l.merken("Faden" + n, "F" + n, "", -1);
                    l.kanonisch("Gleich");
                }
            }));
        }
        for (Future<?> x : f) x.get();
        pool.shutdown();
        assertEquals(200, l.kanonisch("gleich").orElseThrow().anzahl());
        assertEquals(200, new Lesarten(datei).kanonisch("gleich").orElseThrow().anzahl(), "Datei vollstaendig");
        assertEquals(9, new Lesarten(datei).groesse());
    }
}
