package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10.: der Notizblock merkt sich je Gegner den Fingerabdruck seines Namensbildes (Glyphenschrift) -- derselbe
 * Spieler unter einem anders gelesenen Namen landet in derselben Notiz, und der Name steht im Notizblock mit den
 * exakten Zeichen, sobald alle bekannt sind.
 */
class GegnerbuchFingerabdruckTest {

    @TempDir
    Path ordner;

    private Gegnerbuch buch(Glyphenschrift gs) {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"), gs);
    }

    private static Glyphenschrift.Ergebnis erg(String fp, String text) {
        return new Glyphenschrift.Ergebnis(fp, Glyphenschrift.nummern(fp), text, 0);
    }

    @Test
    void andererNameGleichesNamensbildGleicheNotiz() {
        Glyphenschrift gs = new Glyphenschrift(ordner.resolve("glyphen"));
        Gegnerbuch b = buch(gs);
        b.ergebnis("ЦИРИ", "Myth Gods", false, 36_548_453, 30, 0, erg("1.2.3.4.5.6.7.8.9", "??????????"));
        // Name und Clan verlesen, Kraft 3,7 % hoeher, ein Zeichen anders zugeordnet
        b.ergebnis("UMPM", "O'Myth Go s", false, 37_912_345, 25, 0, erg("1.2.3.4.5.6.7.8.10", "??????????"));
        assertEquals(1, b.anzahl());
        assertEquals(2, b.nachschlagen("ЦИРИ", "Myth Gods").orElseThrow().kaempfe());
        // Nachschlagen mit Fingerabdruck -- auch ohne lesbaren Namen
        assertEquals("ЦИРИ", b.nachschlagen("-UMPM-", "", "1.2.3.4.5.6.7.8.9").orElseThrow().name());
        assertEquals("ЦИРИ", b.nachschlagen("", "", "1.2.3.4.5.6.7.8.10").orElseThrow().name());
        assertTrue(b.nachschlagen("-UMPM-", "").isEmpty(), "ohne Fingerabdruck wie bisher");
        // Kampf ohne lesbaren Namen kommt auch dorthin
        b.ergebnis("", "", true, -1, 20, 50, erg("1.2.3.4.5.6.7.8.9", "??????????"));
        assertEquals(3, b.nachschlagen("ЦИРИ", "Myth Gods").orElseThrow().kaempfe());
        // Bilanz ueber das Namensbild, auch unter ganz anderem gelesenen Namen
        assertArrayEquals(new int[]{1, 3}, b.bilanzNachName("Myth 5Qds", -1, "1.2.3.4.5.6.7.8.9"));
        assertArrayEquals(new int[]{0, 0}, b.bilanzNachName("Myth 5Qds", -1));
        // wiedererkennen liefert den Namen der Notiz
        String[] nc = b.wiedererkennen("V Myth Gods", "", 37_000_123, erg("1.2.3.4.5.6.7.8.11", "??????????"));
        assertEquals("ЦИРИ", nc[0]);
        assertEquals("Myth Gods", nc[1]);
        assertTrue(b.nachschlagen(nc[0], nc[1]).orElseThrow().fingerabdruecke().startsWith("1.2.3.4.5.6.7.8.11|"));
    }

    @Test
    void andererClanUndAndereKraftBleibtEinAndererSpieler() {
        Gegnerbuch b = buch(new Glyphenschrift(ordner.resolve("glyphen")));
        b.ergebnis("Alex", "Nordwacht", false, 30_100_123, 30, 0, erg("5.6.7.8", "????"));
        // gleich heissender Spieler aus einem anderen Clan, Kraft weit weg -> eigene Notiz
        b.ergebnis("Alex", "Suedwind", true, 52_000_777, 30, 40, erg("5.6.7.8", "????"));
        assertEquals(2, b.anzahl());
        // anderer Clan gelesen, aber Kraft passt (hoechstens 10 %) -> derselbe
        b.ergebnis("A1ex", "Nord wacht X", false, 31_500_456, 30, 0, erg("5.6.7.8", "????"));
        assertEquals(2, b.anzahl());
        assertEquals(2, b.nachschlagen("Alex", "Nordwacht").orElseThrow().kaempfe());
    }

    @Test
    void fingerabdruckUeberlebtNeuladenAlteDateienLadenWeiter() throws Exception {
        Path daten = ordner.resolve("arena-daten.txt");
        // Zeile im alten Format (17 Spalten, ohne Namensbild)
        Files.writeString(daten, "Rina\tRUSSIA corp\tNS\t25914235\t-1\t2026-10-20 10:00\t-1\t\t\t30 25\t-1\t-1\t\t\t\t\t\n",
                StandardCharsets.UTF_8);
        Glyphenschrift gs = new Glyphenschrift(ordner.resolve("glyphen"));
        Gegnerbuch b = buch(gs);
        assertEquals("NS", b.nachschlagen("Rina", "RUSSIA corp").orElseThrow().verlauf());
        b.merkeStaerke("Rina", "RUSSIA corp", 25_914_235, erg("9.10.11.12", "Ri?a"));
        b.jetztSpeichern();
        Gegnerbuch neu = buch(gs);
        var n = neu.nachschlagen("R1na", "", "9.10.11.12").orElseThrow();
        assertEquals("Rina", n.name());
        assertEquals("9.10.11.12", n.fingerabdruecke());
        assertEquals("Ri?a", n.schrift());
    }

    @Test
    void notizblockZeigtExakteZeichenSobaldAlleBekannt() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner.resolve("glyphen"));
        Glyphenschrift.Ergebnis e = gs.erkennen(ArenaSperreFingerabdruckTest.bild("dars2-1"));
        assertEquals("?????", e.text());
        Gegnerbuch b = buch(gs);
        b.ergebnis("Dar52", "Nordwacht", true, 30_100_123, 30, 50, e);
        b.merkePruefung("Dar52", "Nordwacht", 30, "Sets", 0, 6, 0);
        String block = Files.readString(ordner.resolve("arena-notizblock.txt"), StandardCharsets.UTF_8);
        assertTrue(block.contains("-> Dar52 ("), block);
        assertTrue(block.contains("Namensbild: "), "solange Zeichen fehlen: das Namensbild\n" + block);
        // Zeichen gelernt (oder per !glyph beschriftet): ohne neuen Kampf gleich mit exakten Zeichen
        assertTrue(gs.lernen(e, "Dars2"));
        b.neuSchreiben();
        block = Files.readString(ordner.resolve("arena-notizblock.txt"), StandardCharsets.UTF_8);
        assertTrue(block.contains("-> Dars2 ("), block);
        assertFalse(block.contains("Namensbild: "), block);
        // Schmuckzeichen von Hand
        assertTrue(gs.beschriften(e.nummern()[0], "𝔇"));
        b.neuSchreiben();
        block = Files.readString(ordner.resolve("arena-notizblock.txt"), StandardCharsets.UTF_8);
        assertTrue(block.contains("-> 𝔇ars2 ("), block);
    }

    @Test
    void schriftMitLueckenAuffuellen() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner.resolve("glyphen"));
        Glyphenschrift.Ergebnis e = gs.erkennen(ArenaSperreFingerabdruckTest.bild("irishcesca-1"));
        assertTrue(e.text().contains(" ") && e.text().contains("?"), e.text());
        assertEquals(e.text(), Gegnerbuch.exakteSchrift(gs, e.fingerabdruck(), e.text()), "noch nichts bekannt");
        assertTrue(gs.lernen(e, "IrishCesca"));
        assertEquals("Irish Cesca", Gegnerbuch.exakteSchrift(gs, e.fingerabdruck(), e.text()));
        assertNull(Gegnerbuch.exakteSchrift(gs, e.fingerabdruck(), "Xyz"), "passt nicht zusammen");
    }
}
