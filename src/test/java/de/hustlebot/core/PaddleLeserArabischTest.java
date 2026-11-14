package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Messung 29.10.: das arabische Modell liefert die Zeichen in Bildreihenfolge (links nach rechts); PaddleOCR dreht sie
 * selbst um (pred_reverse). Lateinische Stuecke und Ziffern bleiben dabei als Ganzes stehen.
 */
class PaddleLeserArabischTest {

    @Test
    void arabischWirdUmgedreht() {
        assertEquals("محمد", PaddleLeser.arabischUmdrehen("دمحم"));
    }

    @Test
    void lateinischeStueckeBleibenGanz() {
        // Bild: "12" links, dann die arabischen Zeichen -> umgedreht steht "12" am Ende, aber nicht "21".
        assertEquals("محمد12", PaddleLeser.arabischUmdrehen("12دمحم"));
        // Leerzeichen zwischen Zahl/Latein und arabischem Wort bleibt dazwischen (PaddleOCR haengt es an das lateinische
        // Stueck, dann geht es beim Abschneiden verloren: "نور608" statt "نور 608")
        assertEquals("علي ABC", PaddleLeser.arabischUmdrehen("ABC يلع"));
        assertEquals("نور 608", PaddleLeser.arabischUmdrehen("608 رون"));
        assertEquals("سارة_508", PaddleLeser.arabischUmdrehen("508_ةراس"));
    }

    @Test
    void leerzeichenZwischenLateinischenWoertern() {
        // "Hello World" bleibt ein Stueck (nicht "World Hello")
        assertEquals("نور Hello World", PaddleLeser.arabischUmdrehen("Hello World رون"));
        assertEquals("نور 12 34", PaddleLeser.arabischUmdrehen("12 34 رون"));
    }

    @Test
    void leerzeichenAmRandUndZwischenArabischenWoertern() {
        assertEquals("ملك عمر", PaddleLeser.arabischUmdrehen("رمع كلم"));
        assertEquals(" محمد12", PaddleLeser.arabischUmdrehen("12دمحم "));
        assertEquals("", PaddleLeser.arabischUmdrehen(""));
    }
}
