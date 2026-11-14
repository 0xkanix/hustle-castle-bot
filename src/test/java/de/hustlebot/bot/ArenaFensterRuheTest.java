package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Autor 01.11. (Pruefung 3.137, Abweichung 10): "4.055.264" statt 42.055.264 um 14:49:35 -- die eigene Kraft wurde gelesen, waehrend das Vergleichsfenster noch hereinglitt
 * (Ziffer verloren). Gelesen wird jetzt erst, wenn die gekreuzten Schwerter im selben Punkt liegen wie im Bild davor. Lagen aus der Fotoserie vom 01.11.: die Schwerter standen
 * bei (578, 355) bzw. (578, 354) -- siehe ArenaReiheTest.
 */
class ArenaFensterRuheTest {

    @Test
    void einFensterInRuheIstFertig() {
        assertTrue(ArenaMode.fensterStill(578, 355, 578, 355, 300));
        assertTrue(ArenaMode.fensterStill(578, 354, 578, 355, 300), "ein Punkt Unterschied (beide Lagen kommen vor) zaehlt noch als Ruhe");
    }

    @Test
    void einHereingleitendesFensterIstNochNichtFertig() {
        assertFalse(ArenaMode.fensterStill(578, 330, 578, 290, 120), "gleitet: 40 Punkte weiter als im Bild davor");
        assertFalse(ArenaMode.fensterStill(578, 350, 578, 330, 120));
        assertFalse(ArenaMode.fensterStill(578, 355, 578, 353, 120), "zwei Punkte: noch in Bewegung");
    }

    @Test
    void dasErsteBildEinesFenstersGiltNieAlsFertig() {
        assertFalse(ArenaMode.fensterStill(578, 355, -1000, -1000, 5_000_000), "noch nie ein Fenster gesehen");
        assertFalse(ArenaMode.fensterStill(578, 355, 578, 355, ArenaMode.FENSTER_RUHE_MS + 1), "das vorige Bild war vor einem anderen Fenster");
        assertFalse(ArenaMode.fensterStill(578, 355, 578, 355, -5), "Uhr zurueck");
        assertEquals(1, ArenaMode.FENSTER_RUHE_PUNKTE);
    }
}
