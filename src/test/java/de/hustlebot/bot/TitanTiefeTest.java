package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tiefe rechts unten lesen (Autor 18.10.: "1151632" statt 151632 -- der Bot ging sofort wieder aus dem Event). */
class TitanTiefeTest {

    @Test
    void normaleTiefe() {
        assertEquals(151632, TitanEventMode.tiefeAusText("151632/151655m"));
        assertEquals(1801, TitanEventMode.tiefeAusText("1801/1820m"));
    }

    @Test
    void eineZifferZuVielVorn() {
        assertEquals(151632, TitanEventMode.tiefeAusText("1151632/151655m"));
        assertEquals(151632, TitanEventMode.tiefeAusText("| 1151632 / 151655 m"));
    }

    @Test
    void fehltHintenEineZifferWirdVornNichtGekuerzt() {
        assertEquals(151632, TitanEventMode.tiefeAusText("151632/15165m"));
    }

    @Test
    void ohneZweiteZahlBleibtEsBeiDerErsten() {
        assertEquals(10812, TitanEventMode.tiefeAusText("10812/"));
    }

    @Test
    void unlesbar() {
        assertEquals(-1, TitanEventMode.tiefeAusText("Event ends"));
        assertEquals(-1, TitanEventMode.tiefeAusText(null));
    }
}
