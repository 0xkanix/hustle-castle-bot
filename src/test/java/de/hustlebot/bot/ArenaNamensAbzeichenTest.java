package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefer 29.10. (3.109-3.112): im Vergleichsfenster las PaddleOCR das Stufen-Abzeichen links vom Namen mit ("G4nawa",
 * "49cowboyup", "53HELIOS", "i66Bernd"). Die Masse unten stammen aus 20 Fotos vom 15.10. (1159 x 770, Namenszeile
 * 389-414): Abzeichen 282-315 (oder 277-311), Name ab 325 (321); mit Pal-Pfote 286-324, Abzeichen 352-385, Name ab 395.
 */
class ArenaNamensAbzeichenTest {

    private static final int W = 1159, H = 770, UNTER_SCHWERTERN = 386;

    private static BufferedImage fenster(int[]... bloecke) {
        BufferedImage b = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(255, 237, 231));   // rosa Grund der unteren Fensterhaelfte
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(150, 220, 225));   // tuerkise Linie quer darueber (darf nicht stoeren)
        g.fillRect(0, 384, W, 4);
        g.setColor(new Color(40, 40, 40));
        for (int[] k : bloecke) g.fillRect(k[0], 392, k[1] - k[0] + 1, 18);
        g.dispose();
        return b;
    }

    @Test
    void ohnePfote() {
        assertEquals(318, ArenaMode.namensAnfangNachAbzeichen(fenster(new int[]{282, 315}, new int[]{325, 413}), UNTER_SCHWERTERN));
        // Fenster 5 Bildpunkte weiter links (kam auf den Fotos vor)
        assertEquals(314, ArenaMode.namensAnfangNachAbzeichen(fenster(new int[]{278, 311}, new int[]{321, 410}), UNTER_SCHWERTERN));
    }

    @Test
    void mitPalPfoteDavor() {
        // Die Pfote ist fast so breit wie das Abzeichen -- entscheidend ist die lange Luecke dahinter (27 statt 9).
        assertEquals(388, ArenaMode.namensAnfangNachAbzeichen(
                fenster(new int[]{286, 324}, new int[]{352, 385}, new int[]{395, 462}), UNTER_SCHWERTERN));
    }

    @Test
    void ohneAbzeichenBleibtEsBeimAltenAusschnitt() {
        assertEquals(-1, ArenaMode.namensAnfangNachAbzeichen(fenster(new int[]{325, 413}), UNTER_SCHWERTERN));
        assertEquals(-1, ArenaMode.namensAnfangNachAbzeichen(fenster(), UNTER_SCHWERTERN));
        assertEquals(-1, ArenaMode.namensAnfangNachAbzeichen(null, UNTER_SCHWERTERN));
    }

    @Test
    void kleineresFensterSkaliertMit() {
        // Halbe Groesse: alles halb so breit.
        BufferedImage gross = fenster(new int[]{282, 315}, new int[]{325, 413});
        BufferedImage klein = new BufferedImage(W / 2, H / 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = klein.createGraphics();
        g.drawImage(gross, 0, 0, W / 2, H / 2, null);
        g.dispose();
        int x = ArenaMode.namensAnfangNachAbzeichen(klein, UNTER_SCHWERTERN / 2);
        assertTrue(x >= 158 && x <= 162, "Anfang " + x);   // Abzeichen endet bei ~157, Name ab ~162
    }
}
