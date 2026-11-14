package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nachbearbeitung der Spielernamen-Lesungen (Messung 29.10. am Testsatz). */
class PaddleLeserNamenTest {

    @Test
    void gemischteWoerterWerdenEinheitlich() {
        assertEquals("Ромаріо", PaddleLeser.wortweiseEinheitlich("Poмаpio"));
        assertEquals("Остап", PaddleLeser.wortweiseEinheitlich("Остаn"));
        assertEquals("Іван_гроза", PaddleLeser.wortweiseEinheitlich("lван_гроза"));
        assertEquals("Max007", PaddleLeser.wortweiseEinheitlich("Маx007"));
        // Woerter aus nur einer Schrift bleiben
        assertEquals("King Вова", PaddleLeser.wortweiseEinheitlich("King Вова"));
        assertEquals("ВАРВАР", PaddleLeser.wortweiseEinheitlich("ВАРВАР"));
        assertEquals("Ørjan", PaddleLeser.wortweiseEinheitlich("Ørjan"));
    }

    @Test
    void lateinischeKuerzelNurMitTrennzeichen() {
        assertTrue(PaddleLeser.lateinAbgetrennt("[GEA]Лев Щука"));
        assertTrue(PaddleLeser.lateinAbgetrennt("злой&Blue"));
        assertTrue(PaddleLeser.lateinAbgetrennt("ведьма950+Shadow"));
        assertTrue(PaddleLeser.lateinAbgetrennt("Беркут 228™™"));
        assertFalse(PaddleLeser.lateinAbgetrennt("cYМихаилowb"));
        assertFalse(PaddleLeser.lateinAbgetrennt("Охотник ll"));
    }

    @Test
    void zierzeichenPaareUmLateinischeNamen() {
        assertTrue(PaddleLeser.zierpaar("兰WILD兰"));
        assertTrue(PaddleLeser.zierpaar("쓰PIRATE브"));
        assertFalse(PaddleLeser.zierpaar("Robin宝宝"));
        assertFalse(PaddleLeser.zierpaar("시우122"));
        assertFalse(PaddleLeser.zierpaar("王小明王"));
    }

    @Test
    void katakanaDoppelgaenger() {
        assertEquals("タナカ290", PaddleLeser.kanaEinheitlich("タナ力290"));
        assertEquals("タイガー光", PaddleLeser.kanaEinheitlich("タイガ一光"));
        assertEquals("天下第一", PaddleLeser.kanaEinheitlich("天下第一"));
        assertEquals("力士", PaddleLeser.kanaEinheitlich("力士"));
    }

    @Test
    void roemischeZahlenImAnhangNurBeiNamen() {
        assertEquals("Охотник II", PaddleLeser.kyrillischMitAnhang("Охотник ll", true));
        assertEquals("Охотник ll", PaddleLeser.kyrillischMitAnhang("Охотник ll", false));
        assertEquals("король v2", PaddleLeser.kyrillischMitAnhang("король v2", true));
        assertEquals("Королева II", PaddleLeser.kyrillischMitAnhang("Королева I|", true));
    }

    /** Weisse Balken (Textzeilen) auf schwarzem Grund: Hoehe 60, Breite 200, Balken A Zeilen 10-20, Balken B beginnt nach der Luecke. */
    private static java.util.List<int[]> baenderMitLuecke(int luecke) {
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(200, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int beginnB = 21 + luecke;
        for (int y = 0; y < 60; y++) {
            boolean weiss = (y >= 10 && y <= 20) || (y >= beginnB && y <= beginnB + 10);
            for (int x = 20; x < 180; x++) b.setRGB(x, y, weiss ? 0xFFFFFF : 0x000000);
        }
        return PaddleLeser.baender(b);
    }

    @Test
    void schmaleLueckeInnerhalbDerZeileTeiltNicht() {
        // Messung 30.10.: Hangul-Silben und duenne Zierbuchstaben haben 1-4 Pixel Luecke -- das war EINE Zeile (ganzes Bild)
        for (int luecke : new int[]{1, 3, 4}) {
            java.util.List<int[]> b = baenderMitLuecke(luecke);
            assertEquals(1, b.size(), "Luecke " + luecke);
            assertEquals(0, b.get(0)[0]);
            assertEquals(60, b.get(0)[1]);
        }
    }

    @Test
    void breiteLueckeTrenntZweiZeilen() {
        for (int luecke : new int[]{5, 12}) {
            java.util.List<int[]> b = baenderMitLuecke(luecke);
            assertEquals(2, b.size(), "Luecke " + luecke);
            assertEquals(7, b.get(0)[0]);              // Zeile 10 minus 3 Rand
            assertEquals(24, b.get(0)[1]);             // erste Zeile ohne Tinte (21) plus 3 Rand
            assertEquals(21 + luecke - 3, b.get(1)[0]);
        }
    }
}
