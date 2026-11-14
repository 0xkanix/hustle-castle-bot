package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ArenaLeser.zahlAus: die erste Zahl aus dem, was die Texterkennung liefert.
 *
 * Mia 21.10.: Die Nahrung kam als "-90-ooo" oder "90-000" an und wurde 90 statt 90 000
 * ("Not enough food left (90 ...)", Auftrag beendet). Ein "-" zwischen Ziffern gliedert jetzt,
 * aber nur vor genau drei Ziffern; ein o/O in einer begonnenen Zahl ist eine 0.
 */
class ArenaLeserZahlTest {

    private static long zahl(String text) {
        OptionalLong n = ArenaLeser.zahlAus(text);
        assertTrue(n.isPresent(), "keine Zahl in: " + text);
        return n.getAsLong();
    }

    @Test
    void striche_als_tausendergliederung_mia() {
        assertEquals(90_000L, zahl("-90-ooo"));
        assertEquals(90_000L, zahl("90-000"));
        assertEquals(90_000L, zahl("-90-000"));
        assertEquals(90_000L, zahl("90-OOO"));
        assertEquals(1_034_601L, zahl("1-034-601"));
        assertEquals(90_000L, zahl("Food: -90-ooo apples"));
    }

    @Test
    void strich_gliedert_nur_vor_genau_drei_ziffern() {
        assertEquals(1L, zahl("1-2"));
        assertEquals(1L, zahl("1-23"));
        assertEquals(12L, zahl("12-3456"));
        assertEquals(12L, zahl("12-34"));
        assertEquals(5L, zahl("5-"));
    }

    @Test
    void fuehrender_strich_ist_kein_minus() {
        assertEquals(5L, zahl("-5"));
        assertEquals(90L, zahl("-90"));
    }

    @Test
    void bisherige_faelle_bleiben() {
        assertEquals(14_398L, zahl("14 398"));
        assertEquals(37_472_535L, zahl("37 472 535"));
        assertEquals(1_034_601L, zahl("1 034 601"));
        assertEquals(1_234L, zahl("1.234"));
        assertEquals(1_234L, zahl("1 234"));
        assertEquals(90L, zahl("90"));
        assertEquals(0L, zahl("0"));
        assertEquals(12L, zahl("12:30"));
        assertEquals(7L, zahl("Dauer 7s"));
        assertEquals(42L, zahl("abc 42 def 17"));
        assertTrue(ArenaLeser.zahlAus("").isEmpty());
        assertTrue(ArenaLeser.zahlAus("   ").isEmpty());
        assertTrue(ArenaLeser.zahlAus(null).isEmpty());
        assertTrue(ArenaLeser.zahlAus("keine Zahl").isEmpty());
    }

    @Test
    void o_in_begonnener_zahl_ist_null() {
        assertEquals(90_000L, zahl("90ooo"));
        assertEquals(900L, zahl("9oo"));
        assertEquals(10L, zahl("1o"));
        assertEquals(90_000L, zahl("90 ooo"));
        assertEquals(1_000L, zahl("1.OOO"));
    }

    @Test
    void o_ohne_begonnene_zahl_oder_in_woertern_bleibt_ein_buchstabe() {
        assertTrue(ArenaLeser.zahlAus("ooo").isEmpty());
        assertEquals(90L, zahl("o90"));
        assertEquals(90L, zahl("90 oder"));
        assertEquals(5L, zahl("5 o"));
        assertEquals(5L, zahl("5oz"));
        assertEquals(12L, zahl("12 ox"));
    }
    @Test
    void kraft_mit_i_symbol_nicht_mal_zehn_pruefung_29_10() {
        // Das orange "i" rechts neben der Kraft kam als "1" an: 284.380.601 statt 28.438.060.
        assertEquals(28_438_060L, ArenaLeser.dreierGruppen("28 438 060 1").getAsLong());
        assertEquals(28_438_060L, ArenaLeser.dreierGruppen("28 438 060 i").getAsLong());
        assertEquals(34_624_389L, ArenaLeser.dreierGruppen("34.624.389").getAsLong());
        assertEquals(1_034_601L, ArenaLeser.dreierGruppen("1 034 601").getAsLong());
        // Ungegliedert oder nur eine Gruppe: zahlAus entscheidet wie bisher.
        assertTrue(ArenaLeser.dreierGruppen("150").isEmpty());
        assertTrue(ArenaLeser.dreierGruppen("28438060").isEmpty());
    }

    @Test
    void kraft_plausibel_nur_zwischen_0_2_und_3_mal_der_eigenen() {
        assertTrue(ArenaMode.kraftPlausibel(28_438_060L, 34_624_389L));
        assertFalse(ArenaMode.kraftPlausibel(284_380_601L, 34_624_389L));   // x10
        assertFalse(ArenaMode.kraftPlausibel(784_346L, 34_624_389L));       // Fenster glitt noch
        assertFalse(ArenaMode.kraftPlausibel(3_900_000L, 34_624_389L));
        assertTrue(ArenaMode.kraftPlausibel(40_788_154L, -1));               // eigene unbekannt: nur 7-9 Stellen
        assertFalse(ArenaMode.kraftPlausibel(1_234_567_890L, -1));
    }
    @Test
    void zusammengerutschte_dreiergruppen_pruefung_29_10() {
        assertEquals(java.util.List.of(7_940_373L), ArenaLeser.alleZahlen("7 940373"));
        assertEquals(java.util.List.of(24_956_401L), ArenaLeser.alleZahlen("24 956 401"));
        assertEquals(java.util.List.of(150L, 70L), ArenaLeser.alleZahlen("150 70"));
    }
}
