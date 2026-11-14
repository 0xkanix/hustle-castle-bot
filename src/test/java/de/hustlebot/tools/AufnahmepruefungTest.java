package de.hustlebot.tools;

import de.hustlebot.tools.Aufnahmepruefung.Mangel;
import de.hustlebot.tools.Aufnahmepruefung.Stufe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F13, F17, F22): die Pruefung eines Ausschnitts vor dem Speichern im Aufnahme-Programm.
 */
class AufnahmepruefungTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Ein Foto voller Zufallspunkte: jeder Ausschnitt ist einmalig und scharf. */
    private static BufferedImage rauschen(int b, int h, long samen) {
        BufferedImage f = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(samen);
        for (int y = 0; y < h; y++) for (int x = 0; x < b; x++) f.setRGB(x, y, r.nextInt(0xFFFFFF));
        return f;
    }

    @Test
    void einmaligerAusschnittIstGruen() {
        BufferedImage foto = rauschen(400, 300, 1);
        var b = Aufnahmepruefung.bewerte(foto, new Rectangle(100, 80, 60, 30));
        assertNotNull(b);
        assertEquals(Stufe.GRUEN, b.stufe());
        assertTrue(b.maengel().isEmpty(), b.maengel().toString());
        assertTrue(b.suche().beste() >= 0.99);
        assertTrue(b.suche().zweite() < 0.80);
        assertFalse(b.text().isBlank());
    }

    @Test
    void zweimalVorhandenerAusschnittIstRot() {
        // dieselbe 30x30-Kachel mehrfach nebeneinander (zwei gleiche Knoepfe im Bild)
        BufferedImage kachel = rauschen(30, 30, 7);
        BufferedImage foto = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = foto.createGraphics();
        for (int y = 0; y < 200; y += 30) for (int x = 0; x < 300; x += 30) g.drawImage(kachel, x, y, null);
        g.dispose();
        var b = Aufnahmepruefung.bewerte(foto, new Rectangle(60, 60, 30, 30));
        assertNotNull(b);
        assertEquals(Stufe.ROT, b.stufe());
        assertTrue(b.maengel().contains(Mangel.NICHT_EINDEUTIG));
        assertTrue(b.suche().zweite() >= 0.90);
    }

    @Test
    void einfarbigerAusschnittIstRot() {
        BufferedImage foto = rauschen(300, 200, 3);
        Graphics2D g = foto.createGraphics();
        g.setColor(new Color(20, 40, 60));
        g.fillRect(50, 50, 80, 60);
        g.dispose();
        var b = Aufnahmepruefung.bewerte(foto, new Rectangle(60, 60, 40, 30));
        assertNotNull(b);
        assertEquals(Stufe.ROT, b.stufe());
        assertTrue(b.maengel().contains(Mangel.EINFARBIG));
        assertFalse(b.maengel().contains(Mangel.NICHT_GEFUNDEN), "einfarbig sagt schon alles");
    }

    @Test
    void kleinerUndGrosserAusschnittWarnen() {
        BufferedImage foto = rauschen(400, 300, 5);
        assertTrue(Aufnahmepruefung.qualitaet(foto, new Rectangle(10, 10, 8, 20)).contains(Mangel.ZU_KLEIN));
        assertFalse(Aufnahmepruefung.qualitaet(foto, new Rectangle(10, 10, 12, 12)).contains(Mangel.ZU_KLEIN));
        // 300 x 150 = 45000 von 120000 = 37,5 % -> noch nicht; 300 x 170 = 42,5 % -> zu gross
        assertFalse(Aufnahmepruefung.qualitaet(foto, new Rectangle(0, 0, 300, 150)).contains(Mangel.ZU_GROSS));
        assertTrue(Aufnahmepruefung.qualitaet(foto, new Rectangle(0, 0, 300, 170)).contains(Mangel.ZU_GROSS));
        var klein = Aufnahmepruefung.bewerte(foto, new Rectangle(10, 10, 8, 20));
        assertNotNull(klein);
        assertEquals(Stufe.GELB, klein.stufe(), "klein allein ist nur eine Warnung");
    }

    @Test
    void weicherVerlaufWirktUnscharf() {
        // ein sanfter Verlauf hat Streuung, aber keine Kanten
        BufferedImage foto = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) {
            int v = (int) (40 + 160.0 * x / 300);
            foto.setRGB(x, y, new Color(v, v, v).getRGB());
        }
        assertTrue(Aufnahmepruefung.qualitaet(foto, new Rectangle(20, 20, 200, 100)).contains(Mangel.UNSCHARF));
    }

    @Test
    void scharfeKantenSindNichtUnscharf() {
        BufferedImage foto = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = foto.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 300, 200);
        g.setColor(Color.WHITE);
        for (int i = 0; i < 6; i++) g.fillRect(20 + i * 20, 30, 8, 40);   // Balken mit harten Kanten
        g.dispose();
        assertFalse(Aufnahmepruefung.qualitaet(foto, new Rectangle(10, 20, 130, 60)).contains(Mangel.UNSCHARF));
    }

    @Test
    void vorschauIstDreifachUndBegrenzt() {
        BufferedImage foto = rauschen(400, 300, 9);
        BufferedImage v = Aufnahmepruefung.vorschau(foto, new Rectangle(10, 10, 40, 20), 400, 200);
        assertNotNull(v);
        assertEquals(120, v.getWidth());
        assertEquals(60, v.getHeight());
        BufferedImage gross = Aufnahmepruefung.vorschau(foto, new Rectangle(0, 0, 300, 100), 300, 90);
        assertTrue(gross.getWidth() <= 300 && gross.getHeight() <= 90);
        assertNull(Aufnahmepruefung.vorschau(foto, new Rectangle(500, 500, 10, 10), 100, 100));
    }

    @Test
    void groessenHinweisNurBeiAbweichung() {
        assertEquals("", Aufnahmepruefung.groessenHinweis(1159, 770));
        assertEquals("", Aufnahmepruefung.groessenHinweis(1157, 771), "bis 2 Punkte Abweichung gelten als passend");
        assertFalse(Aufnahmepruefung.groessenHinweis(1175, 809).isEmpty());
        String h = Aufnahmepruefung.groessenHinweis(1100, 770);
        assertTrue(h.contains("1100") && h.contains("1159"));
    }
}
