package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Schriftbild (Autor 21.11.): der Name als kleines Bild. Echte Ausschnitte aus dem Bot-Ordner (src/test/resources/
 * schriftbild): "~ЦИРИ~" (Myth Gods) aus Liste und Schild, Revan/Raven und Том/Тан (aehnliche Namen, verschiedene
 * Spieler), Karen, ein Vergleichsfenster (schwarze Schrift) und ein Schild mit weisser Linie quer durchs Bild.
 */
class SchriftbildTest {

    private static BufferedImage bild(String name) throws Exception {
        try (InputStream in = SchriftbildTest.class.getResourceAsStream("/schriftbild/" + name)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /** Wie im Bot: ein Namensausschnitt vom Schild. */
    private static Schriftbild schild(String name) throws Exception {
        Schriftbild s = Schriftbild.aus(bild(name), Schriftbild.SCHILD);
        assertNotNull(s, name);
        return s;
    }

    /** Ganzes Schild aus logs\schmuckschrift (162 x 80): nur der Bereich rechts vom Wappen, obere Haelfte. */
    private static Schriftbild grossesSchild(String name) throws Exception {
        BufferedImage b = bild(name);
        int w = b.getWidth(), h = b.getHeight();
        Schriftbild s = Schriftbild.aus(b.getSubimage((int) (w * 0.36), 0, w - (int) (w * 0.36), (int) (h * 0.42)), Schriftbild.SCHILD);
        assertNotNull(s, name);
        return s;
    }

    @Test
    void textHinUndZurueckUndKlein() throws Exception {
        for (String n : new String[]{"ziri-liste.png", "ziri-schild.png", "revan.png", "tom.png", "tan.png", "karen.png", "nikneim.png"}) {
            Schriftbild s = schild(n);
            String t = s.text();
            assertEquals(s, Schriftbild.lesen(t), n);
            assertTrue(t.length() < 300, n + ": " + t.length() + " Zeichen");
            assertTrue(t.matches("S[A-Za-z0-9_-]+"), n + ": nur Base64-URL-Zeichen, kein | oder Tab");
            assertEquals(Schriftbild.HOEHE, s.hoehe());
        }
        assertNull(Schriftbild.lesen("Skaputt!"));
        assertNull(Schriftbild.lesen(""));
        assertNull(Schriftbild.lesen(null));
    }

    @Test
    void ziriAusListeUndSchildIstDerselbe() throws Exception {
        // ar_name_Bild-1120-230452.png (Autors Liste) und glyphen\namen\201757a4.png (Schild vom 29.10.)
        assertNotEquals(Schriftbild.Urteil.NEIN, schild("ziri-liste.png").urteil(schild("ziri-schild.png")));
        // zwei ganze Schilde aus verschiedenen Turnieren
        assertNotEquals(Schriftbild.Urteil.NEIN, grossesSchild("ziri-schild-gross-1.png").urteil(grossesSchild("ziri-schild-gross-2.png")));
    }

    @Test
    void groesserGezeichnetBleibtDerselbe() throws Exception {
        // Die Pyramidenreihen zeichnen Namen 93-107 % gross: dasselbe Bild um 7 % vergroessert
        BufferedImage b = bild("ziri-liste.png");
        BufferedImage gross = new BufferedImage((int) Math.round(b.getWidth() * 1.07), (int) Math.round(b.getHeight() * 1.07), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gross.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(b, 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        Schriftbild a = schild("ziri-liste.png"), c = Schriftbild.aus(gross, Schriftbild.SCHILD);
        assertNotEquals(Schriftbild.Urteil.NEIN, a.urteil(c));
    }

    @Test
    void aehnlicheNamenSindNieSicher() throws Exception {
        // Revan (Autors Liste) und Raven (Schild): zwei verschiedene Spieler, gleiche Laenge, fast gleiche Buchstaben
        assertNotEquals(Schriftbild.Urteil.SICHER, schild("revan.png").urteil(grossesSchild("raven-schild-gross.png")));
        assertNotEquals(Schriftbild.Urteil.SICHER, schild("tom.png").urteil(schild("tan.png")));
        assertEquals(Schriftbild.Urteil.NEIN, schild("karen.png").urteil(schild("revan.png")));
        assertEquals(Schriftbild.Urteil.NEIN, schild("ziri-liste.png").urteil(schild("revan.png")));
    }

    @Test
    void fensterMitSchwarzerSchrift() throws Exception {
        BufferedImage a = bild("fenster-1.png"), b = bild("fenster-2.png");
        Schriftbild fa = Schriftbild.aus(a.getSubimage(0, 0, a.getWidth(), a.getHeight() / 2), Schriftbild.FENSTER);
        Schriftbild fb = Schriftbild.aus(b.getSubimage(0, 0, b.getWidth(), b.getHeight() / 2), Schriftbild.FENSTER);
        assertNotNull(fa);
        assertNotNull(fb);
        assertEquals(Schriftbild.Urteil.SICHER, fa.urteil(fb));
        assertTrue(fa.text().startsWith("F"));
        // andere Bildschirm-Art wird nie verglichen
        assertEquals(Schriftbild.Urteil.NEIN, fa.urteil(schild("ziri-liste.png")));
    }

    @Test
    void weisseLinieQuerDurchsBildStoertNicht() throws Exception {
        // glyphen\namen\2a8cb8d0.png: oben eine weisse Linie quer durchs Bild -- vorher wurde daraus ein einziger Fleck
        assertNotEquals(Schriftbild.Urteil.NEIN, schild("nikneim.png").urteil(schild("nikneim-linie.png")));
    }

    @Test
    void ohneSchriftKeinBild() {
        BufferedImage leer = new BufferedImage(80, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = leer.createGraphics();
        g.setColor(new java.awt.Color(206, 206, 206));
        g.fillRect(0, 0, 80, 20);
        g.dispose();
        assertNull(Schriftbild.aus(leer, Schriftbild.SCHILD));
        assertNull(Schriftbild.aus(null, Schriftbild.SCHILD));
    }
}
