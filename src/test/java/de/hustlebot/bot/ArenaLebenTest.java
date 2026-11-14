package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Autor 01.11. (Pruefung 3.137, Abweichung 8): "Leben von Einheit N nicht sicher gelesen" -- 11 von 31 Einheiten, obwohl "2 201 814/2 201 814" gross und klar im
 * Bild steht (weisse Schrift in einer roten Pille hinter dem Herz). Die Bilder sind Ausschnitte (280,100 + 400x110) der Fotoserie vom 01.11.; der Suchausschnitt
 * ist der des Bots hinter dem Herz (338,144 + 254x50 im ganzen Bild, hier um den Ausschnitt verschoben).
 */
class ArenaLebenTest {

    private static BufferedImage bild(String name) throws Exception {
        try (var in = ArenaLebenTest.class.getResourceAsStream("/arena-pruefung/leben/" + name)) {
            return ImageIO.read(in);
        }
    }

    /** Der Ausschnitt des Bots: hinter dem Herz, 22 % der Breite lang, Herzhoehe plus ein Drittel oben und unten (338,144 254x50, Ausschnitt ab 280,100). */
    private static List<Long> lesen(String name) throws Exception {
        BufferedImage b = bild(name);
        return ArenaLeser.lebenZahlen(b, 338 - 280, 144 - 100, 254, 50);
    }

    private static boolean ocrDa() {
        return de.hustlebot.core.Texterkennung.istDa();
    }

    @Test
    void zweiMillionenLebenWerdenGelesen() throws Exception {
        Assumptions.assumeTrue(ocrDa());
        assertEquals(List.of(2201814L, 2201814L), lesen("leben-2201814.png"));   // 14:59:00.654, vorher "nicht sicher gelesen"
    }

    @Test
    void auchKleineUndZerstueckelteZahlenKommenSauber() throws Exception {
        Assumptions.assumeTrue(ocrDa());
        assertEquals(List.of(74244L, 74244L), lesen("leben-74244.png"), "Bauarbeiter-Leben");
        assertEquals(List.of(459075L, 459075L), lesen("leben-459075.png"), "Вован 15:02:18, vorher nicht sicher gelesen");
        assertEquals(List.of(2675783L, 2675783L), lesen("leben-2675783.png"), "die zweite Zahl wurde sonst als 2675 / 78 zerstueckelt");
    }

    @Test
    void ohnePilleLiestEsWieBisher_undNichtsFalsches() throws Exception {
        Assumptions.assumeTrue(ocrDa());
        BufferedImage b = bild("leben-kein-fenster.png");
        // Kein Einheiten-Fenster (14:59:43, ein anderes Bild): keine zwei gleichen Zahlen erfinden.
        List<Long> z = ArenaLeser.lebenZahlen(b, 338 - 280, 144 - 100, 254, 50);
        assertTrue(z.size() < 2, "nichts Erfundenes: " + z);
    }

    @Test
    void roteAflaecheWirdAlsPilleGefunden() throws Exception {
        BufferedImage b = bild("leben-2201814.png");
        Rectangle p = ArenaLeser.rotePille(b, 338 - 280, 144 - 100, 254, 50);
        assertNotNull(p);
        assertTrue(p.width >= 150 && p.width <= 200, "Pille ist rund 180 Bildpunkte breit: " + p);
        assertTrue(p.height <= ArenaLeser.PILLE_MAX_HOEHE);
        // Ein Ausschnitt ohne Rot hat keine Pille.
        BufferedImage grau = new BufferedImage(300, 60, BufferedImage.TYPE_INT_RGB);
        assertNull(ArenaLeser.rotePille(grau, 0, 0, 300, 60));
    }
}
