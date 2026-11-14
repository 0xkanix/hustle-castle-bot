package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Eigene Schrifterkennung fuer Spielernamen (Autor 24.10.). Die Bilder in src/test/resources/glyphen sind echte
 * Namensbilder des Bots: je zwei Aufnahmen desselben Spielers von verschiedenen Tagen (aragon, dars2, lady, mpno1,
 * irishcesca), derselbe Spieler in zwei Reihen der Arena-Pyramide (shaban-reihe1/-reihe3, dort ist die Schrift
 * verschieden gross), Schmuckschrift (schmuck), Chinesisch (chinesisch), Akzent + Leerzeichen + Punkt
 * (hagar-fenix = "Hagar Fénix.") und ein Bild mit weisser Linie am oberen Rand (weisse-linie).
 */
class GlyphenschriftTest {

    @TempDir
    Path ordner;

    private static BufferedImage bild(String name) throws Exception {
        try (InputStream in = GlyphenschriftTest.class.getResourceAsStream("/glyphen/" + name + ".png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    private static final List<String> ALLE = List.of("aragon-1", "aragon-2", "dars2-1", "dars2-2", "lady-1", "lady-2",
            "mpno1-1", "mpno1-2", "irishcesca-1", "irishcesca-2", "shaban-reihe1", "shaban-reihe3", "hagar-fenix",
            "schmuck", "chinesisch", "weisse-linie");

    @Test
    void zerlegtNamenMitAkzentLeerzeichenUndPunkt() throws Exception {
        GlyphenZerlegung.Zerlegung z = GlyphenZerlegung.zerlegen(bild("hagar-fenix"));
        // H a g a r | F é n i x .  -- das é ist EIN Zeichen (Akzent ueber dem e), der Punkt ein eigenes
        assertEquals(11, z.anzahl());
        for (int i = 0; i < z.anzahl(); i++) assertEquals(i == 5, z.stuecke().get(i).leerDavor(), "Leerzeichen vor Zeichen " + i);
        // der Punkt sitzt unten, das é reicht hoeher als das n daneben
        GlyphenZerlegung.Stueck punkt = z.stuecke().get(10), e = z.stuecke().get(6), n = z.stuecke().get(7);
        assertTrue(punkt.lage() > n.lage(), "Punkt liegt tiefer als n");
        assertTrue(e.y0() < n.y0(), "Akzent gehoert zum e");
    }

    @Test
    void weisseLinieAmRandVerbindetNichtAlleBuchstaben() throws Exception {
        assertEquals(4, GlyphenZerlegung.zerlegen(bild("weisse-linie")).anzahl());
    }

    @Test
    void leeresOderFehlendesBildGibtLeerenFingerabdruck() {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        assertTrue(gs.erkennen(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)).leer());
        assertEquals("", gs.erkennen(null).fingerabdruck());
    }

    @Test
    void derselbeSpielerWirdWiedererkanntAndereNicht() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        Map<String, String> fp = new TreeMap<>();
        for (String n : ALLE) fp.put(n, gs.fingerabdruck(bild(n)));
        for (String n : List.of("aragon", "dars2", "lady", "mpno1", "irishcesca")) {
            String a = fp.get(n + "-1"), b = fp.get(n + "-2");
            assertTrue(Glyphenschrift.derselbeName(a, b), n + ": " + a + " | " + b);
            assertTrue(gs.derselbeNameVerwandt(a, b), n);
        }
        // Reihe 1 und Reihe 3 der Pyramide: Schrift verschieden gross -- trotzdem derselbe
        assertTrue(gs.derselbeNameVerwandt(fp.get("shaban-reihe1"), fp.get("shaban-reihe3")));
        // verschiedene Spieler duerfen nie als derselbe gelten
        for (String a : ALLE)
            for (String b : ALLE) {
                if (a.compareTo(b) >= 0 || a.replaceAll("-.*", "").equals(b.replaceAll("-.*", ""))) continue;
                assertFalse(gs.derselbeNameVerwandt(fp.get(a), fp.get(b)), a + " ~ " + b);
            }
    }

    @Test
    void lerntBuchstabenAusSaubererLesung() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        assertEquals("?????", gs.lesen(bild("dars2-1")));
        assertFalse(gs.lernen(bild("dars2-1"), "Dars2?"), "Fragezeichen = unsicher, nicht lernen");
        assertFalse(gs.lernen(bild("dars2-1"), "Dars"), "falsche Zeichenzahl, nicht lernen");
        assertTrue(gs.lernen(bild("dars2-1"), "Dars2"));
        assertEquals("Dars2", gs.lesen(bild("dars2-1")));
        assertEquals("Dars2", gs.lesen(bild("dars2-2")), "andere Aufnahme (3 Wochen frueher), dieselben Glyphen");
        // Mehrheit: eine einzelne Fehllesung kippt nichts
        gs.lernen(gs.erkennen(bild("dars2-2")), "Dars2");
        gs.lernen(bild("dars2-1"), "Dar52");
        assertEquals("Dars2", gs.lesen(bild("dars2-1")));
        // Windows liest "IrishCesca" ohne Leerzeichen -- das Leerzeichen kommt aus der Luecke im Bild
        assertTrue(gs.lernen(bild("irishcesca-1"), "IrishCesca"));
        assertEquals("Irish Cesca", gs.lesen(bild("irishcesca-2")));
        String hagar = gs.lesen(bild("hagar-fenix"));
        assertEquals(12, hagar.length(), hagar);
        assertEquals(' ', hagar.charAt(5), hagar);
    }

    @Test
    void vonHandBeschriftenMitUnicodeUndNachLadenNochDa() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        Glyphenschrift.Ergebnis e = gs.erkennen(bild("chinesisch"));
        assertEquals(3, e.nummern().length);
        assertTrue(gs.beschriften(e.nummern()[0], "元"));
        assertTrue(gs.beschriften(e.nummern()[1], "素"));
        assertTrue(gs.beschriften(e.nummern()[2], "神"));
        assertEquals("元素神", gs.lesen(bild("chinesisch")));

        // Schmuckschrift: mathematische Buchstaben ausserhalb der Grundebene (zwei char je Zeichen)
        Glyphenschrift.Ergebnis s = gs.erkennen(bild("schmuck"));
        assertTrue(gs.beschriften(s.nummern()[0], "𝔍"));
        assertTrue(gs.beschriften(s.nummern()[1], ""), "leer = Teil des Zeichens davor");
        assertTrue(gs.lesen(bild("schmuck")).startsWith("𝔍"), gs.lesen(bild("schmuck")));
        assertFalse(gs.beschriften(999_999, "x"), "unbekannte Nummer");
        assertFalse(gs.beschriften(e.nummern()[0], "a\tb"), "Tabulator wuerde die Datei zerstoeren");

        // neu laden (wie nach einem Neustart des Bots): Zuordnung ist noch da, Datei in UTF-8
        Glyphenschrift neu = new Glyphenschrift(ordner);
        assertEquals(s.fingerabdruck(), neu.fingerabdruck(bild("schmuck")), "nach dem Neuladen dieselben Nummern");
        assertEquals("元素神", neu.lesen(bild("chinesisch")));
        assertTrue(neu.lesen(bild("schmuck")).startsWith("𝔍"));
        assertEquals(Glyphenschrift.TEIL, neu.zeichen(s.nummern()[1]));
        String datei = Files.readString(ordner.resolve("glyphen.txt"), StandardCharsets.UTF_8);
        assertTrue(datei.contains("𝔍") && datei.contains("元") && datei.contains("[teil]"), datei);
        assertTrue(Files.exists(neu.glyphenBild(e.nummern()[0])));
        // Zuordnung von Hand wieder wegnehmen
        assertTrue(neu.beschriften(e.nummern()[0], null));
        assertNull(neu.zeichen(e.nummern()[0]));
    }

    @Test
    void legtJeNameEinNamensbildAb() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        String a = gs.fingerabdruck(bild("lady-1"));
        Path p = gs.namensbild(a);
        assertNotNull(p);
        assertTrue(Files.exists(p));
        assertEquals(Glyphenschrift.kurz(a) + ".png", p.getFileName().toString());
        // zweite Aufnahme desselben Spielers: kein zweites Bild, es gilt das erste
        String b = gs.fingerabdruck(bild("lady-2"));
        assertEquals(p, gs.namensbild(b));
        gs.fingerabdruck(bild("dars2-1"));
        try (var l = Files.list(ordner.resolve("namen"))) {
            assertEquals(2, l.filter(f -> f.toString().endsWith(".png")).count());
        }
        // nach dem Neuladen findet es das Bild ueber namen.txt
        assertEquals(p, new Glyphenschrift(ordner).namensbild(b));
        assertNull(gs.namensbild("777.778.779.780.781"));
    }

    @Test
    void vergleichVonFingerabdruecken() {
        assertEquals(1.0, Glyphenschrift.aehnlichkeit("1.2.3.4.5", "1.2.3.4.5"), 1e-9);
        assertEquals(0.8, Glyphenschrift.aehnlichkeit("1.2.3.4.5", "1.2.9.4.5"), 1e-9);
        assertTrue(Glyphenschrift.derselbeName("1.2.3.4", "1.2.9.4"), "ein falsches Zeichen von vier");
        assertTrue(Glyphenschrift.derselbeName("1.2.3.4.5", "1.2.4.5"), "ein Zeichen fehlt");
        assertFalse(Glyphenschrift.derselbeName("1.2.3.4", "1.7.9.4"), "zwei falsche von vier");
        assertFalse(Glyphenschrift.derselbeName("1.2.3", "1.2.4"), "kurze Namen muessen gleich sein");
        assertTrue(Glyphenschrift.derselbeName("1.2.3", "1.2.3"));
        assertFalse(Glyphenschrift.derselbeName("", ""));
        assertEquals("4.17.9", Glyphenschrift.fingerabdruck(Glyphenschrift.nummern(" 4.17.x.9 ")));
        assertEquals(8, Glyphenschrift.kurz("4.17.9").length());
    }

    @Test
    void schnellGenug() throws Exception {
        Glyphenschrift gs = new Glyphenschrift(ordner);
        gs.setNamensbilderSpeichern(false);
        List<BufferedImage> bilder = new java.util.ArrayList<>();
        for (String n : ALLE) bilder.add(bild(n));
        for (BufferedImage b : bilder) gs.erkennen(b);           // Bibliothek aufbauen, warm laufen
        long t0 = System.nanoTime();
        int runden = 20;
        for (int r = 0; r < runden; r++) for (BufferedImage b : bilder) gs.erkennen(b);
        double ms = (System.nanoTime() - t0) / 1e6 / (runden * bilder.size());
        System.out.printf("Glyphenschrift: %.2f ms je Name%n", ms);
        assertTrue(ms < 20, ms + " ms je Name");
    }

    /** Pruefprogramm mit allen Namensbildern des Bots -- nur dort, wo es eigene Namensbilder in den Ordnern gibt. */
    @Test
    void pruefprogrammMitEchtenNamensbildern() throws Exception {
        Assumptions.assumeTrue(GlyphenschriftPruefung.WURZELN.stream().anyMatch(Files::isDirectory), "keine Namensbilder da");
        GlyphenschriftPruefung.Bericht b = GlyphenschriftPruefung.pruefen(ordner);
        System.out.println(b.text());
        Assumptions.assumeTrue(b.bilder() > 0, "keine Namensbilder da");
        assertTrue(b.paareVerwandt() * 5 >= b.paareMehrfach() * 4, "mind. 80 % der Mehrfach-Namen wiedererkannt");
        assertTrue(b.fremdeTreffer() * 200 <= Math.max(1, b.fremdePaare()), "hoechstens 0,5 % Verwechslungen");
    }
}
