package de.hustlebot.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F19): die Liste "Meine Bilder" zeigt nur *.png (die .alt-Sicherungen nicht) und baut Vorschauen.
 */
class MeineBilderTest {

    @Test
    void listeKenntNurPngUndBegrenzt(@TempDir Path w) throws Exception {
        Files.createDirectories(w.resolve("Dungeon"));
        Files.createDirectories(w.resolve("Arena lists/never"));
        BufferedImage b = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(b, "png", w.resolve("Dungeon/kaempfer_1.png").toFile());
        ImageIO.write(b, "png", w.resolve("Dungeon/kaempfer_2.png").toFile());
        ImageIO.write(b, "png", w.resolve("Arena lists/never/ar_block_1.png").toFile());
        Files.writeString(w.resolve("Dungeon/kaempfer_3.png.alt"), "x");
        Files.writeString(w.resolve("README.txt"), "x");

        List<Path> alle = MeineBilder.bilder(w, 100);
        assertEquals(3, alle.size());
        assertTrue(alle.stream().noneMatch(p -> p.toString().endsWith(".alt") || p.toString().endsWith(".txt")));
        assertEquals(2, MeineBilder.bilder(w, 2).size());
        assertTrue(MeineBilder.bilder(w.resolve("gibt-es-nicht"), 10).isEmpty());
        assertTrue(MeineBilder.bilder(null, 10).isEmpty());
    }

    @Test
    void vorschauBleibtKleinUndMeldetDieGroesse(@TempDir Path w) throws Exception {
        Path p = w.resolve("gross.png");
        ImageIO.write(new BufferedImage(600, 120, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        var v = MeineBilder.vorschau(p);
        assertEquals("600 x 120", v.groesse());
        assertTrue(v.icon().getIconWidth() <= 150 && v.icon().getIconHeight() <= 40);

        Path kaputt = w.resolve("kaputt.png");
        Files.writeString(kaputt, "kein bild");
        assertNull(MeineBilder.vorschau(kaputt).icon());
    }
}
