package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Hintergrund-Modus (Autor 20.11.): die Entscheidungen der Wache und der feste Schalter. */
class HintergrundTest {

    @AfterEach
    void zuruecksetzen() {
        Hintergrund.festlegen(null);
    }

    @Test
    void autorOeffnetDasSpielUeberDieTaskleiste() {
        // es wird das aktive Fenster, war es vorher nicht
        assertEquals(Hintergrund.Schritt.AUTOR_OEFFNET, Hintergrund.entscheiden(true, false, false));
        // es war vorne (z. B. beim Unsichtbarmachen) und wird per Taskleiste minimiert
        assertEquals(Hintergrund.Schritt.AUTOR_OEFFNET, Hintergrund.entscheiden(false, true, true));
    }

    @Test
    void sonstMinimiertWirdEsStillWiederhergestellt() {
        // z. B. Win+D: nie minimiert lassen -- minimiert gibt es kein Bild
        assertEquals(Hintergrund.Schritt.WIEDERHERSTELLEN, Hintergrund.entscheiden(false, false, true));
    }

    @Test
    void bleibtEsVorneOderHintenPassiertNichts() {
        assertEquals(Hintergrund.Schritt.NICHTS, Hintergrund.entscheiden(true, true, false));
        assertEquals(Hintergrund.Schritt.NICHTS, Hintergrund.entscheiden(false, false, false));
    }

    @Test
    void festlegenUeberstimmtDieDatei() {
        Hintergrund.festlegen(true);
        assertTrue(Hintergrund.an());
        Hintergrund.festlegen(false);
        assertFalse(Hintergrund.an());
    }

    @Test
    void ohneSpielfensterTutStoppNichts() {
        Hintergrund.nachStopp();   // kein Spielfenster gemerkt: kein Windows-Aufruf, kein Fehler
        Hintergrund.botLaeuft();
        assertFalse(Hintergrund.nutzerHatSpiel());
    }
}
