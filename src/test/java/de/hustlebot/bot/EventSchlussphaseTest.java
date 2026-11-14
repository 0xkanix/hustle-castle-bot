package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hammer-Event, Nacht 29.10. (Protokoll bot_2026-10-29_22-25-55.log): Die Schlussphasen-Messung (Zeit oben links rot)
 * kippte im Wechsel 2830 / 0 / 2797 / 0 rote Bildpunkte -- die offene Lupen-Form verdeckte die Zeit. Und die blauen
 * Haemmer galten nach zwei leeren Proben fuer immer als leer, obwohl sie sich nachfuellen.
 */
class EventSchlussphaseTest {

    /** Wie step(): bei jeder gueltigen Messung Zaehler nachfuehren und den neuen Stand ausrechnen. */
    private static boolean[] verlauf(boolean start, int... rotePunkte) {
        boolean[] stand = new boolean[rotePunkte.length];
        boolean schlussphase = start;
        int nichtRotFolge = 0;
        for (int i = 0; i < rotePunkte.length; i++) {
            boolean rot = rotePunkte[i] >= 300;   // SCHLUSS_ROT_PIXEL
            nichtRotFolge = rot ? 0 : nichtRotFolge + 1;
            schlussphase = EventMode.schlussphaseGilt(schlussphase, rot, nichtRotFolge);
            stand[i] = schlussphase;
        }
        return stand;
    }

    @Test
    void rotGiltSofort() {
        assertTrue(EventMode.schlussphaseGilt(false, true, 0));
        assertTrue(EventMode.schlussphaseGilt(true, true, 0));
    }

    @Test
    void eineEinzelneNullBeendetDieSchlussphaseNicht() {
        assertTrue(EventMode.schlussphaseGilt(true, false, 1), "erste Null: Schlussphase bleibt");
        assertFalse(EventMode.schlussphaseGilt(true, false, EventMode.SCHLUSS_NICHT_ROT_FOLGE), "zweite Null hintereinander: vorbei");
    }

    @Test
    void ohneSchlussphaseBleibtEsBeiNichtRot() {
        assertFalse(EventMode.schlussphaseGilt(false, false, 1));
        assertFalse(EventMode.schlussphaseGilt(false, false, 5));
    }

    @Test
    void wechselndeMessungenAusDemProtokollKippenNicht() {
        // 22:26:10 bis 22:34:52: 2830, 2838, 2868, 0, 2797, 0, 2829, 2845, 2865, 0, 2870, 2837, 0, 2765, 2833, 0, 2864, 0
        boolean[] stand = verlauf(false, 2830, 2838, 2868, 0, 2797, 0, 2829, 2845, 2865, 0, 2870, 2837, 0, 2765, 2833, 0, 2864, 0);
        for (int i = 0; i < stand.length; i++) assertTrue(stand[i], "Messung " + i + ": Schlussphase muss bleiben");
    }

    @Test
    void zweiNullenHintereinanderBeendenSie() {
        boolean[] stand = verlauf(false, 2800, 0, 0, 0);
        assertArrayEquals(new boolean[] {true, true, false, false}, stand);
    }

    @Test
    void eineRoteMessungSetztDenZaehlerZurueck() {
        // Null, rot, Null, Null: erst die letzte Null (zweite hintereinander) beendet
        boolean[] stand = verlauf(false, 2800, 0, 2800, 0, 0);
        assertArrayEquals(new boolean[] {true, true, true, true, false}, stand);
    }

    @Test
    void blaueHaemmerWerdenVorDemEndstoppEinmalFrischGeprueft() {
        assertTrue(EventMode.frischPruefen(true, false), "alte Probe sagt leer, noch nicht neu geprueft: pruefen");
        assertFalse(EventMode.frischPruefen(true, true), "in diesem Feld schon neu geprueft: jetzt anhalten");
        assertFalse(EventMode.frischPruefen(false, false), "nicht leer: nichts zu pruefen");
        assertFalse(EventMode.frischPruefen(false, true));
    }
}
