package de.hustlebot.bot;

import de.hustlebot.core.Gegnerbuch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefer 29.10.: G4nawa -- das Gegenstandsfenster ging bei Einheit 4 nicht zu, nach 2,5 s ging es auf SCHLIESSEN, und
 * die Pruefung galt als fertig: gespeichert "Einheiten 4", teamPunkte() rechnete +12 je "fehlender" Einheit, und Einheit 5
 * war bei jedem Schnell-Blick "anders" (4x je 15-20 s neu angesehen).
 */
class ArenaPruefungAbbruchTest {

    @Test
    void abbruchIstNichtVollstaendig() {
        assertTrue(ArenaPruefer.vollstaendig(true, false));
        assertFalse(ArenaPruefer.vollstaendig(true, true));    // SCHLIESSEN, aber wegen des haengenden Fensters
        assertFalse(ArenaPruefer.vollstaendig(false, false));
    }

    @Test
    void abgebrochenePruefungKenntDieEinheitenzahlNicht() {
        ArenaPruefer.Ergebnis e = new ArenaPruefer.Ergebnis("G4nawa", "", 4, 30, "Paladin 4/6, ?", 1, 0, 13, 0,
                3, 0, 0, -1, false, false);
        ArenaPruefer.Ergebnis o = ArenaMode.ohneEinheitenzahl(e);
        assertEquals(-1, o.einheiten());
        assertEquals(e.setPunkte(), o.setPunkte());   // die gelesenen Sets gelten
        assertEquals(e.sets(), o.sets());
        assertEquals(e.gelbeSteine(), o.gelbeSteine());
        // Mit "4" kam die Strafe fuer zwei fehlende Einheiten dazu, mit -1 nicht.
        Gegnerbuch.setMeineEinheiten(6);
        assertEquals(30 + 3 + 2 * Gegnerbuch.JE_FEHLENDE_EINHEIT, Gegnerbuch.teamPunkte(30, 1, 0, e.einheiten()));
        assertEquals(30 + 3, Gegnerbuch.teamPunkte(30, 1, 0, o.einheiten()));
    }

    @Test
    void ohneGeseheneEinheitOderNurExtrasBleibtAlles() {
        ArenaPruefer.Ergebnis leer = new ArenaPruefer.Ergebnis("x", "", 0, 0, "", 0, 0, 0, 0, -1, 0, 0, -1, false, false);
        assertSame(leer, ArenaMode.ohneEinheitenzahl(leer));
        ArenaPruefer.Ergebnis extras = new ArenaPruefer.Ergebnis("x", "", 0, 0, "", 0, 0, 0, 0, 3, 1, 0, 2, true, false);
        assertSame(extras, ArenaMode.ohneEinheitenzahl(extras));
    }
}
