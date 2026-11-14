package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10.: die Sperrliste erkennt Spieler auch am Fingerabdruck ihres Namensbildes (Glyphenschrift) -- "OMYth Gods"
 * las Windows als "-UMPM-", "Gods" und "Myth 5Qds", das Namensbild war jedes Mal (fast) dasselbe.
 */
class ArenaSperreFingerabdruckTest {

    @TempDir
    Path ordner;

    private Glyphenschrift leereBibliothek() {
        return new Glyphenschrift(ordner.resolve("glyphen"));
    }

    @Test
    void gleichesNamensbildTrotzAnderemNamenUndAndererKraft() {
        ArenaSperre.fuerTest(ordner.resolve("a.txt"), leereBibliothek());
        for (int i = 0; i < 3; i++) {
            ArenaSperre.ergebnis("OMYth Gods", "Myth Gods", 36_548_453, "", "", null, "1.2.3.4.5.6.7.8.9", false);
        }
        // Name, Clan und Kraft anders gelesen, ein Zeichen des Namensbildes anders zugeordnet -> derselbe Spieler
        String grund = ArenaSperre.sperrgrund("-UMPM-", "O'Myth Go s", 37_912_345, "", "", null, "1.2.3.4.5.6.7.8.10");
        assertNotNull(grund);
        assertTrue(grund.contains("Namensbild") || grund.contains("name picture"), grund);
        // die neue Kraft ist gemerkt -- jetzt reicht in der Pyramide auch die Kraft allein
        assertNotNull(ArenaSperre.sperrgrund("Myth 5Qds", "", 37_912_345, ""));
        // ein ganz anderes Namensbild (und ein anderer Name) ist nicht gesperrt
        assertNull(ArenaSperre.sperrgrund("Rina", "", 41_000_123, "", "", null, "20.21.22.23.24"));
        // ohne Fingerabdruck wie bisher
        assertNull(ArenaSperre.sperrgrund("Rina", "", 41_000_123, ""));
    }

    @Test
    void jedeNeueVarianteWirdGemerktUndUeberlebtDasNeuladen() throws Exception {
        Path datei = ordner.resolve("b.txt");
        ArenaSperre.fuerTest(datei, leereBibliothek());
        ArenaSperre.ergebnis("Gods", "", -1, "", "", null, "1.2.3.4.5.6.7.8", false);
        ArenaSperre.ergebnis("V Myth Gods", "", -1, "", "", null, "1.2.3.4.5.6.7.9", false);   // dieselbe Person
        ArenaSperre.ergebnis("Myth 5Qds", "", -1, "", "", null, "1.2.3.4.5.6.7.9", false);     // schon bekannt
        String text = Files.readString(datei, StandardCharsets.UTF_8);
        assertTrue(text.contains("\t1.2.3.4.5.6.7.9|1.2.3.4.5.6.7.8"), text);
        assertEquals(1, text.lines().filter(z -> !z.startsWith("#")).count(), "ein Spieler, ein Profil");
        // nach dem Neuladen am zweiten Fingerabdruck erkannt (der erste waere zwei Zeichen weg)
        ArenaSperre.fuerTest(datei, leereBibliothek());
        assertNotNull(ArenaSperre.sperrgrund("~~~", "", -1, "", "", null, "1.2.3.4.5.6.30.9"));
    }

    @Test
    void alteZeilenOhneSpalteLadenWeiter() throws Exception {
        Path datei = ordner.resolve("c.txt");
        Files.writeString(datei, "# alt\n30497325\t0\t3\t響蛎茶多\t\t\t\t\n", StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(datei, leereBibliothek());
        assertNotNull(ArenaSperre.sperrgrund("irgendwas", "", 30_497_325, "", "", null, "7.8.9.10"));
        // erkannt an der Kraft: der Fingerabdruck wird gleich dazugemerkt (gesperrte Spieler kaempfen ja nie wieder)
        assertTrue(Files.readString(datei, StandardCharsets.UTF_8).contains("\t7.8.9.10"));
        ArenaSperre.fuerTest(datei, leereBibliothek());
        assertNotNull(ArenaSperre.sperrgrund("nochmal anders", "", 31_000_123, "", "", null, "7.8.9.10"));
    }

    @Test
    void kurzeNamensbilderErkennenNiemanden() {
        ArenaSperre.fuerTest(ordner.resolve("d.txt"), leereBibliothek());
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Bo", "", -1, "", "", null, "4.5", false);
        assertNull(ArenaSperre.sperrgrund("Xy", "", -1, "", "", null, "4.5"));
        assertEquals("4.5", ArenaSperre.dazu("", "4.5"));
        assertEquals("", ArenaSperre.dazu("", "kaputt"), "nur Ziffern und Punkte");
    }

    @Test
    void echteNamensbilderZweierAufnahmen() throws Exception {
        Glyphenschrift gs = leereBibliothek();
        ArenaSperre.fuerTest(ordner.resolve("e.txt"), gs);
        String fp1 = gs.erkennen(bild("aragon-1")).fingerabdruck(), fp2 = gs.erkennen(bild("aragon-2")).fingerabdruck();
        for (int i = 0; i < 3; i++) ArenaSperre.ergebnis("Aragon", "", -1, "", "", null, fp1, false);
        assertNotNull(ArenaSperre.sperrgrund("Arag0n??", "", -1, "", "", null, fp2));
        assertNull(ArenaSperre.sperrgrund("Lady", "", -1, "", "", null, gs.erkennen(bild("lady-1")).fingerabdruck()));
    }

    static BufferedImage bild(String name) throws Exception {
        try (InputStream in = ArenaSperreFingerabdruckTest.class.getResourceAsStream("/glyphen/" + name + ".png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }
}
