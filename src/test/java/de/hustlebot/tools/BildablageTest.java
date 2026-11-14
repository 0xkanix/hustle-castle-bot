package de.hustlebot.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F19, F21): Rueckweg fuer Speichern, Ueberschreiben, Loeschen und Textdateien im
 * Aufnahme-Programm.
 */
class BildablageTest {

    private static BufferedImage farbe(Color c) {
        BufferedImage b = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) b.setRGB(x, y, c.getRGB());
        return b;
    }

    private static int pixel(Path p) throws Exception {
        return ImageIO.read(p.toFile()).getRGB(0, 0) & 0xFFFFFF;
    }

    @Test
    void neuesBildUndRueckgaengigLoeschtEs(@TempDir Path d) throws Exception {
        Path ziel = d.resolve("My pictures/Dungeon/kaempfer_1.png");
        Runnable zurueck = Bildablage.schreibeMitSicherung(ziel, farbe(Color.RED));
        assertTrue(Files.exists(ziel));
        assertFalse(Files.exists(Bildablage.altDatei(ziel)));
        zurueck.run();
        assertFalse(Files.exists(ziel));
    }

    @Test
    void ueberschreibenBehaeltDasAlteAlsAlt(@TempDir Path d) throws Exception {
        Path ziel = d.resolve("a/b.png");
        Bildablage.schreibeMitSicherung(ziel, farbe(Color.RED));
        Runnable zurueck = Bildablage.schreibeMitSicherung(ziel, farbe(Color.BLUE));
        assertEquals(0x0000FF, pixel(ziel));
        assertTrue(Files.exists(d.resolve("a/b.png.alt")), "die alte Datei bleibt als .alt liegen");
        assertEquals(0xFF0000, pixel(d.resolve("a/b.png.alt")));

        zurueck.run();
        assertEquals(0xFF0000, pixel(ziel), "Rueckgaengig stellt das alte Bild wieder her");
        assertFalse(Files.exists(d.resolve("a/b.png.alt")));
    }

    @Test
    void loeschenBenenntNurUmUndLaesstSichZuruecknehmen(@TempDir Path d) throws Exception {
        Path bild = d.resolve("x.png");
        Bildablage.schreibeMitSicherung(bild, farbe(Color.GREEN));
        Runnable zurueck = Bildablage.nachAlt(bild);
        assertFalse(Files.exists(bild));
        assertTrue(Files.exists(Bildablage.altDatei(bild)));
        zurueck.run();
        assertTrue(Files.exists(bild));
        assertFalse(Files.exists(Bildablage.altDatei(bild)));
    }

    @Test
    void textdateiStandWirdWiederhergestellt(@TempDir Path d) throws Exception {
        Path t = d.resolve("arena-aufnehmen.txt");
        Runnable keine = Bildablage.dateiStand(t);   // gab es noch nicht
        Files.writeString(t, "Loki | niemals\n");
        keine.run();
        assertFalse(Files.exists(t), "die neu angelegte Datei verschwindet wieder");

        Files.writeString(t, "alt\n");
        Runnable alt = Bildablage.dateiStand(t);
        Files.writeString(t, "alt\nneu\n");
        alt.run();
        assertEquals("alt\n", Files.readString(t));
    }

    @Test
    void verschiebenUndZurueck(@TempDir Path d) throws Exception {
        Path von = d.resolve("a/x.png");
        Files.createDirectories(von.getParent());
        Files.writeString(von, "x");
        Path nach = d.resolve("templates-aussortiert/a/x.png");
        Runnable zurueck = Bildablage.verschiebe(von, nach);
        assertFalse(Files.exists(von));
        assertTrue(Files.exists(nach));
        zurueck.run();
        assertTrue(Files.exists(von));
        assertFalse(Files.exists(nach));
    }

    @Test
    void journalNimmtDieLetzteAktionZurueckInUmgekehrterReihenfolge(@TempDir Path d) throws Exception {
        Bildablage.Journal j = new Bildablage.Journal();
        assertTrue(j.leer());
        assertNull(j.zuruecknehmen());

        Path a = d.resolve("a.png"), b = d.resolve("b.png");
        j.neu("a", 3, Bildablage.schreibeMitSicherung(a, farbe(Color.RED)));
        j.neu("b", 4, Bildablage.schreibeMitSicherung(b, farbe(Color.RED)));
        StringBuilder reihenfolge = new StringBuilder();
        j.ergaenze(() -> reihenfolge.append("zweiter"));
        assertEquals("b", j.oberster());

        var e = j.zuruecknehmen();
        assertEquals("b", e.text());
        assertEquals(4, e.schritt());
        assertEquals("zweiter", reihenfolge.toString());
        assertFalse(Files.exists(b));
        assertTrue(Files.exists(a), "die vorletzte Aktion bleibt");
        assertEquals("a", j.oberster());
        j.zuruecknehmen();
        assertFalse(Files.exists(a));
        assertTrue(j.leer());
    }
}
