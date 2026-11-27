package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Namensbild des eigenen Spielers (Autor 21.11.): gleiches Bild = gleiches Konto, egal wie die Texterkennung liest.
 * Die offene Fassung hat kein echtes Burg-Bildschirmfoto (Spielername) -- die Masken hier sind selbst gezeichnet.
 */
class NamensbildTest {

    @TempDir
    Path ordner;

    /** Eine Schriftzeile als Maske: "Autor" hell auf dunkel gezeichnet, Schriftpunkte = true. */
    private static boolean[][] maske() {
        BufferedImage b = new BufferedImage(64, 18, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = b.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 15));
        g.drawString("Autor", 2, 15);
        g.dispose();
        boolean[][] m = new boolean[b.getHeight()][b.getWidth()];
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) m[y][x] = (b.getRGB(x, y) & 0xFF) > 128;
        }
        return m;
    }

    @Test
    void gleicheMaskeIstDasselbeBild() {
        assertTrue(Namensbild.gleich(maske(), maske()));
    }

    @Test
    void andererNameIstEinAnderesBild() throws Exception {
        boolean[][] m = maske();
        // gespiegelt = andere Buchstaben bei gleicher Groesse
        boolean[][] spiegel = new boolean[m.length][m[0].length];
        for (int y = 0; y < m.length; y++) for (int x = 0; x < m[0].length; x++) spiegel[y][x] = m[y][m[0].length - 1 - x];
        assertFalse(Namensbild.gleich(m, spiegel));
        // laenger (ein Buchstabe mehr) = anderer Name
        boolean[][] laenger = new boolean[m.length][m[0].length + 12];
        for (int y = 0; y < m.length; y++) System.arraycopy(m[y], 0, laenger[y], 0, m[0].length);
        for (int y = 2; y < m.length - 2; y++) for (int x = m[0].length + 3; x < m[0].length + 10; x++) laenger[y][x] = true;
        assertFalse(Namensbild.gleich(m, laenger));
    }

    @Test
    void speichernUndLaden() throws Exception {
        boolean[][] m = maske();
        Path d = ordner.resolve("konto-namensbild.png");
        Namensbild.speichern(m, d);
        boolean[][] zurueck = Namensbild.laden(d);
        assertNotNull(zurueck);
        assertTrue(Namensbild.gleich(m, zurueck));
        assertNull(Namensbild.laden(ordner.resolve("fehlt.png")));
    }

    @Test
    void ohneSchriftKeinBild() {
        BufferedImage leer = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        assertNull(Namensbild.ausBereich(leer, 0, 0, 300, 100));
        assertNull(Namensbild.ausBereich(null, 0, 0, 300, 100));
    }
}
