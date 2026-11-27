package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Zettel der Clan-Verwaltung links neben dem Turm (Autor 21.11.: den ganzen Zettel lesen, bis 20 Zeilen; Autor 22.11.:
 * "mein Server-Bot setzt mein normales Team immer in den falschen WP" -- die alte Blasen-Suche las nur die Ueberschrift).
 */
class ClanWarBlaseTest {

    /** Spielbild 1159x770: dunkler Hintergrund, weisse Blase ab y 125 bis {@code blaseBis}, Textzeilen, evtl. gelber Knopf. */
    private static BufferedImage bild(int blaseBis, int zeilen, boolean knopf) {
        BufferedImage b = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(60, 50, 40));
        g.fillRect(0, 0, 1159, 770);
        g.setColor(Color.WHITE);
        g.fillRect(12, 125, 175, blaseBis - 125 + (knopf ? 60 : 0));   // Blase reicht bis unter den Knopf (wie im Spiel)
        g.setColor(Color.BLACK);
        for (int i = 0; i < zeilen; i++) {
            int y = 135 + i * 20;
            for (int x = 25; x < 170; x += 9) g.fillRect(x, y, 7, 12);   // dichte, fette Schrift (nur ~25 % weiss)
        }
        if (knopf) {
            g.setColor(new Color(250, 200, 40));
            g.fillRect(30, blaseBis + 4, 135, 40);                    // gelber Stift-Knopf
        }
        g.dispose();
        return b;
    }

    @Test
    void kurzerZettelEndetAmStiftKnopf() {
        double[] z = ClanWarMode.zettelBereich(bild(282, 8, true));
        assertNotNull(z);
        assertEquals(125 / 770.0, z[0], 2 / 770.0);
        assertEquals(286 / 770.0, z[1], 2 / 770.0);
    }

    @Test
    void zwanzigZeilenWerdenGanzErfasst() {
        int bis = 135 + 20 * 20 + 8;
        double[] z = ClanWarMode.zettelBereich(bild(bis, 20, true));
        assertNotNull(z);
        assertEquals((bis + 4) / 770.0, z[1], 2 / 770.0);
    }

    @Test
    void ohneStiftKnopfEndetAmBlasenende() {
        // Mitspieler ohne Clan-Verwaltung sehen keinen Stift-Knopf: dann endet der Bereich mit der Blase.
        double[] z = ClanWarMode.zettelBereich(bild(240, 5, false));
        assertNotNull(z);
        assertEquals(240 / 770.0, z[1], 2 / 770.0);
    }

    @Test
    void ohneBlaseKeinZettel() {
        assertNull(ClanWarMode.zettelBereich(new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB)));
    }

    // --- Echte Server-Fotos (Fotoserien 21./22.11., halbe Groesse): linke Spalte des Spielbilds, 110x385 = 0..220 x 0..770 ---

    /** Spielbild 1159x770 aus dem Ausschnitt; {@code scharf}: helle Punkte auf reines Weiss wie im echten (unverkleinerten) Bild. */
    private static BufferedImage server(String name, boolean scharf) throws Exception {
        BufferedImage teil;
        try (InputStream in = ClanWarBlaseTest.class.getResourceAsStream("/clankrieg/" + name)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            assertNotNull(in, name);
            teil = ImageIO.read(in);
        }
        BufferedImage b = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(30, 25, 15));
        g.fillRect(0, 0, 1159, 770);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(teil, 0, 0, 220, 770, null);
        g.dispose();
        if (scharf) {
            for (int y = 0; y < 770; y++) {
                for (int x = 0; x < 220; x++) {
                    int v = b.getRGB(x, y), r = (v >> 16) & 255, gr = (v >> 8) & 255, bl = v & 255;
                    b.setRGB(x, y, ((r > 200 ? 255 : r) << 16) | ((gr > 200 ? 255 : gr) << 8) | (bl > 200 ? 255 : bl));
                }
            }
        }
        return b;
    }

    /**
     * Der Lesebereich beginnt unter der Ueberschrift "Message from the Clan Administration" (endet bei y ~100) und vor der
     * ersten Namenszeile (ab y ~139), er reicht ueber die letzte Namenszeile hinaus und hoert vor dem Stift-Knopf auf.
     */
    private static void bereichPasst(String name, int letzteTextZeile, int knopf) throws Exception {
        for (boolean scharf : new boolean[] {false, true}) {
            double[] z = ClanWarMode.zettelBereich(server(name, scharf));
            String wo = name + (scharf ? " (scharf)" : "");
            assertNotNull(z, wo + ": Zettel nicht gefunden");
            int oben = (int) Math.round(z[0] * 770), unten = (int) Math.round(z[1] * 770);
            assertTrue(oben >= 105 && oben <= 136, wo + ": oben " + oben);
            assertTrue(unten >= letzteTextZeile + 2 && unten <= knopf + 4, wo + ": unten " + unten);
        }
    }

    @Test
    void einzeiligerZettelMitAutor() throws Exception {
        // 21.11. 20:20 "Ancient Bones": "Autor" -- der Bot las names "" und setzte den Trupp in "Treachery of Kings".
        bereichPasst("zettel-autor-21-11-2020.jpg", 151, 170);
    }

    @Test
    void zweizeiligerZettel() throws Exception {
        // 21.11. 20:20 "Storm Outpost": "Autor ; Paula ; Gus / ; Karla".
        bereichPasst("zettel-2-zeilen-21-11-2020.jpg", 170, 190);
    }

    @Test
    void dreizeiligerZettel() throws Exception {
        // 22.11. 14:20 "Echo of the war": "Autor ; Robin ; / Jonas P ; Kabelfritz ; / Tom" -- der Bot nahm "Blood Furnace".
        bereichPasst("zettel-3-zeilen-22-11-1420.jpg", 189, 210);
    }

    @Test
    void langerTempelZettel() throws Exception {
        // 21.11. 14:20 Tempel: 8 Zeilen, Autor erst an 7. Stelle (Grund fuer das Lesen bis 20 Zeilen).
        bereichPasst("zettel-tempel-21-11-1420.jpg", 290, 309);
    }

    @Test
    void ohneZettelWerdenKeineKartenNamenGelesen() throws Exception {
        // 22.11. 02:20/08:20: ohne Zettel las der Bot die Turmnamen der Karte ("of the immort ; Treachery of Kings") und
        // hielt freie Tuerme fuer "fuer andere reserviert".
        for (String name : new String[] {"ohne-zettel-22-11-0220.jpg", "ohne-zettel-22-11-0820.jpg"}) {
            assertNull(ClanWarMode.zettelBereich(server(name, false)), name);
            assertNull(ClanWarMode.zettelBereich(server(name, true)), name + " (scharf)");
        }
    }
}
