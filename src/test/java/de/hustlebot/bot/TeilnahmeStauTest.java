package de.hustlebot.bot;

import static de.hustlebot.bot.CathedralMode.TeilnahmeLage.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: Fehlerberichte W7E9/7NRP/FK7G/G3YH (ben, 27.10.): nach einem Captcha klickte der Turbo ueber drei Stunden
 * 8562 + 6131 + 6168 + 6169 Mal "Participate" (129 Schluessel), ohne dass eine Reise begann. Die Grenze galt nur bei NICHT
 * sichtbarem Knopf, jeder Klick meldete "Teilnahme bestaetigt" (Haenger-Uhr zurueck).
 */
class TeilnahmeStauTest {

    @Test
    void einigeAnlaeufeSindNormal() {
        for (int v = 0; v < 8; v++) assertEquals(WEITER, CathedralMode.teilnahmeLage(v, 129, 0), "Versuch " + v);
        assertEquals(WEITER, CathedralMode.teilnahmeLage(3, -1, 0));
    }

    @Test
    void vieleKlicksMitSchluesselnErholenDannEnde() {
        // genau der Fall aus dem Bericht: 129 Schluessel, Knopf bleibt sichtbar
        assertEquals(ERHOLEN, CathedralMode.teilnahmeLage(8, 129, 0));
        assertEquals(ERHOLEN, CathedralMode.teilnahmeLage(8, 129, 1));
        assertEquals(ENDE, CathedralMode.teilnahmeLage(8, 129, 2), "nach zwei Erholungen endet der Auftrag");
        assertEquals(ENDE, CathedralMode.teilnahmeLage(500, 129, 5));
    }

    @Test
    void ohneSchluesselOderUnbekanntWieBisher() {
        assertEquals(SCHLUESSEL_ALLE, CathedralMode.teilnahmeLage(8, 0, 0), "0 Schluessel: vermutlich alle");
        assertEquals(SCHLUESSEL_ALLE, CathedralMode.teilnahmeLage(8, -1, 0), "Stand unbekannt: wie bisher");
        assertEquals(SCHLUESSEL_ALLE, CathedralMode.teilnahmeLage(12, -1, 1));
    }
}
