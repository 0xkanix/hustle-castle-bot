package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 22.10. abends: drei Niederlagen ohne Sieg = gesperrt, erkannt an Kraft, Name, Clan und Sets. */
class ArenaSperreTest {

    @TempDir
    Path ordner;

    @Test
    void gleicheKraftTrotzImmerAndererNamen() {
        ArenaSperre.fuerTest(ordner.resolve("a.txt"));
        ArenaSperre.ergebnis("響蛎茶多", "", 30_497_325, "", false);
        ArenaSperre.ergebnis("회한奶茶多바", "", 30_497_325, "", false);
        assertNull(ArenaSperre.sperrgrund("蛎茶多妨走冰加妨峯", "", 30_497_325, ""));
        ArenaSperre.ergebnis("蛎茶多蛎走冰加蛎峯", "", 30_497_325, "", false);
        assertNotNull(ArenaSperre.sperrgrund("蛎茶多妨走冰加妨峯", "", 30_497_325, ""));
        // nach dem Neuladen noch da
        ArenaSperre.fuerTest(ordner.resolve("a.txt"));
        assertNotNull(ArenaSperre.sperrgrund("irgendwas", "", 30_497_325, ""));
    }

    @Test
    void einSiegHebtDieSperreAuf() {
        ArenaSperre.fuerTest(ordner.resolve("b.txt"));
        ArenaSperre.ergebnis("JtNXED", "Iyulskiy", 35_501_945, "", false);
        ArenaSperre.ergebnis("JtNXED", "Iyulskiy", 35_501_945, "", true);
        ArenaSperre.ergebnis("JtNXED", "Iyulskiy", 35_501_945, "", false);
        ArenaSperre.ergebnis("JtNXED", "Iyulskiy", 35_501_945, "", false);
        assertNull(ArenaSperre.sperrgrund("JtNXED", "Iyulskiy", 35_501_945, ""));
    }

    @Test
    void nameOhneKraftUndClanMitSets() {
        ArenaSperre.fuerTest(ordner.resolve("c.txt"));
        String sets = "Armor Breaker 6/6, Crusader 2/6 + Necromancer 2/6, Bauarbeiter";
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Winterfell", "Nordwacht", -1, sets, false);
        assertNotNull(ArenaSperre.sperrgrund("Winterfel", "Nordwacht", -1, ""));        // fast derselbe Name
        assertNotNull(ArenaSperre.sperrgrund("W1nt#rf3ll??", "Nordwacht", -1, sets));  // Name unlesbar, gleiche Sets
        assertNotNull(ArenaSperre.sperrgrund("Winterfell", "Nordwacht", 52_000_123, ""));  // seine Kraft war nie bekannt -> der Name zaehlt
        assertNull(ArenaSperre.sperrgrund("Sommerwind", "Suedwacht", -1, ""));
    }

    @Test
    void vierGleicheEinheitenNamen() {
        ArenaSperre.fuerTest(ordner.resolve("e.txt"));
        String team = "Закись|T.N.T.|Портос|Арамис|Атос|Д'Артаньян";
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Сволочь", "", -1, "", team, false);
        // Name ganz anders gelesen, 4 von 6 Namen gleich (zwei verlesen)
        assertNotNull(ArenaSperre.sperrgrund("Cbonoqb", "", -1, "", "Закись|T.N.T|Портос|Арамис|Ат0с|ДАртаньян"));
        assertNull(ArenaSperre.sperrgrund("Cbonoqb", "", -1, "", "Закись|T.N.T|Портос|Xyz|Abc|Def"));
        assertEquals(4, ArenaSperre.gleicheEinheiten("a1|b2|c3|d4", "d4|c3|b2|a1"));
    }

    /**
     * Autor 24.10.: "OMYth Gods" (Kraft 36.548.453, 4 Niederlagen) wurde als "-UMPM-" gelesen und angegriffen. Jetzt
     * erkennt ihn die Sperre an den Einheiten-Bildern -- auch mit verlesenem Clan und neuer Kraft.
     */
    @Test
    void amAussehenTrotzVerlesenemNamenUndNeuerKraft() throws Exception {
        ArenaSperre.fuerTest(ordner.resolve("f.txt"));
        var vorher = ArenaSperrBilderTest.merkmale("myth-gods-ohne-pfote");
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("~ЦИРИ~", "Myth Gods", 36_548_453, "", "", vorher, false);
        var jetzt = ArenaSperrBilderTest.merkmale("myth-gods-pfote");
        // Name und Clan verlesen, andere Kraft (neues Ausruestungsstueck): 6 von 6 Einheiten-Bildern -> gesperrt
        assertNotNull(ArenaSperre.sperrgrund("-UMPM-", "O'Myth Go s", 37_912_345, "", "", jetzt));
        // die neue Kraft ist gemerkt -- die Pyramide (dort gibt es keine Bilder) erkennt ihn jetzt an der Kraft
        assertNotNull(ArenaSperre.sperrgrund("Myth 5Qds", "", 37_912_345, ""));
        // ein anderer Spieler aus DEMSELBEN Clan (andere Einheiten) ist nicht gesperrt
        assertNull(ArenaSperre.sperrgrund("Svolotsch", "Myth Gods", 41_000_123, "", "", ArenaSperrBilderTest.merkmale("alkatras")));
        // nach dem Neuladen (Bilder aus arena-sperre-bilder/) noch da, auch ohne Kraft und Clan
        ArenaSperre.fuerTest(ordner.resolve("f.txt"));
        assertNotNull(ArenaSperre.sperrgrund("Gods", "", -1, "", "", jetzt));
    }

    /** Autor 24.10.: Clan-Tag passt + 4 von 6 Einheiten -> derselbe; ohne Clan muessen es 5 sein. Kraft schliesst nicht aus. */
    @Test
    void vierGleicheEinheitenNurMitGleichemClan() {
        ArenaSperre.fuerTest(ordner.resolve("g.txt"));
        String team = "Bogen 2|Magier 1|Bogen 1|Magier 2|ersatz|Paisley";
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Litos", "Nordwacht", 40_100_123, "", team, false);
        String vier = "Bogen 2|Magier 1|Bogen 1|Magier 2|Xaver|Yvonne";
        assertNotNull(ArenaSperre.sperrgrund("???", "Nordwacht", 44_900_777, "", vier));   // Clan passt, Kraft anders
        assertNull(ArenaSperre.sperrgrund("???", "", -1, "", vier));                       // ohne Clan reichen 4 nicht
        assertNull(ArenaSperre.sperrgrund("???", "Suedwacht", -1, "", vier));              // anderer Clan auch nicht
        assertNotNull(ArenaSperre.sperrgrund("???", "", -1, "", "Bogen 2|Magier 1|Bogen 1|Magier 2|ersatz|Yvonne"));
    }

    /**
     * Autor 24.10.: die Merkmale decken sich gegenseitig ab -- Clan falsch: Kraft + Einheiten; Kraft falsch: Name +
     * Einheiten; Clan und Einheiten-Namen falsch: Name + Kraft. Clan + aehnliche Kraft allein reicht nicht.
     */
    @Test
    void zweiPassendeMerkmaleReichen() {
        ArenaSperre.fuerTest(ordner.resolve("h.txt"));
        String team = "Bogen 2|Magier 1|Bogen 1|Magier 2|ersatz|Paisley";
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Litos", "Nordwacht", 40_100_123, "", team, false);
        String vier = "Bogen 2|Magier 1|Bogen 1|Magier 2|Xaver|Yvonne";
        // Name und Clan verlesen, Kraft 2 % hoeher (neues Teil), 4 Einheiten-Namen
        assertNotNull(ArenaSperre.sperrgrund("L1t0s?", "N0rdw", 40_912_345, "", vier));
        // Kraft nicht lesbar, Clan verlesen: Name + 4 Einheiten-Namen
        assertNotNull(ArenaSperre.sperrgrund("Litos", "Xyz", -1, "", vier));
        // Clan und Einheiten-Namen verlesen: Name + Kraft
        assertNotNull(ArenaSperre.sperrgrund("Litos", "Xyz", 40_312_345, "", "a|b|c|d|e|f"));
        // nur ein Merkmal (Einheiten) -- nicht gesperrt
        assertNull(ArenaSperre.sperrgrund("Kora", "Xyz", -1, "", vier));
        // anderer Spieler aus demselben Clan mit aehnlicher Kraft -- nicht gesperrt
        assertNull(ArenaSperre.sperrgrund("Kora", "Nordwacht", 40_312_345, "", "a|b|c|d|e|f"));
    }

    @Test
    void andereKraftIstAndererSpieler() {
        ArenaSperre.fuerTest(ordner.resolve("d.txt"));
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Myth Gods", "", 34_100_123, "", false);
        assertNull(ArenaSperre.sperrgrund("Myth Gods", "", 52_400_777, ""));
        assertNotNull(ArenaSperre.sperrgrund("Myth Gods", "", -1, ""));
    }
}
