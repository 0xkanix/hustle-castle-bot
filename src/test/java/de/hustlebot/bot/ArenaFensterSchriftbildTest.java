package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import de.hustlebot.core.Schriftbild;

/** Autor 21.11.: der Name im Vergleichsfenster als Schriftbild (schwarze Schrift) -- zwei Aufnahmen desselben Spielers. */
class ArenaFensterSchriftbildTest {

    private static BufferedImage bild(String name) throws Exception {
        try (InputStream in = ArenaFensterSchriftbildTest.class.getResourceAsStream("/schriftbild/" + name)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    @Test
    void zweiFensterDesselbenSpielers() throws Exception {
        BufferedImage a = bild("fenster-1.png"), b = bild("fenster-2.png");
        Schriftbild fa = ArenaMode.fensterSchriftbildLesen(a, 0, 0, a.getWidth(), a.getHeight() / 2);
        Schriftbild fb = ArenaMode.fensterSchriftbildLesen(b, 0, 0, b.getWidth(), b.getHeight() / 2);
        assertNotNull(fa);
        assertEquals(Schriftbild.FENSTER, fa.art());
        assertEquals(Schriftbild.Urteil.SICHER, fa.urteil(fb));
        // Ausschnitt ganz ausserhalb oder zu klein: kein Bild, kein Fehler
        assertNull(ArenaMode.fensterSchriftbildLesen(a, a.getWidth() + 5, 0, 50, 20));
        assertNull(ArenaMode.fensterSchriftbildLesen(null, 0, 0, 50, 20));
    }
}
