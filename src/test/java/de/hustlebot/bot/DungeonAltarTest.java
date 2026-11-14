package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ben 30.10.: Opfer-Einheit am Altar: Scan-Bild zaehlt, Leiste mehrmals wischen, zuletzt die letzte lebende Einheit. */
class DungeonAltarTest {

    /** Altar-Bild 1159 x 770 mit dunklen Namen an den angegebenen Stellen. */
    private static BufferedImage bild(int... xVon) {
        BufferedImage b = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1159, 770);
        g.setColor(Color.BLACK);
        for (int x : xVon) g.fillRect(x, 530, 60, 12);
        g.dispose();
        return b;
    }

    @Test
    void scanBildZaehltAlsOpferEinheit() {
        assertTrue(DungeonMode.istOpferBild("unit_auto_opfer"));
        assertTrue(DungeonMode.istOpferBild("name_auto_opfer"));
        assertTrue(DungeonMode.istOpferBild("unit_sacrifice"));
        assertFalse(DungeonMode.istOpferBild("unit_6"), "hintere Einheit erst nach der Namenssuche");
    }

    @Test
    void namenWerdenAlsBloeckeGefunden() {
        List<int[]> b = DungeonMode.schriftBloecke(bild(150, 270, 390, 980));
        assertEquals(4, b.size());
        assertEquals(150, b.get(0)[0]);
        assertEquals(1039, b.get(3)[1]);
    }

    @Test
    void letzteLebendeEinheit() {
        BufferedImage b = bild(150, 270, 980);
        int[] s = DungeonMode.letzteLebende(DungeonMode.schriftBloecke(b), b.getWidth(), List.of());
        assertNotNull(s);
        assertEquals(1009, s[0]);
        assertEquals(470, s[1], "das Bild ueber dem Namen");
        assertNull(DungeonMode.letzteLebende(List.of(), b.getWidth(), List.of()), "keine Einheit zu sehen");
    }
}
