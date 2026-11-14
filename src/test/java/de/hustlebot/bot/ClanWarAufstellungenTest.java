package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Clan-Krieg: bestaetigte Aufstellungen zaehlen je Sorte, nicht je Klick (Server 29.10., 20:32). */
class ClanWarAufstellungenTest {

    @Test
    void doppeltesDeployZaehltEinmal() {
        // Genau der Ablauf vom Server: Deploy, Deploy (gleicher Trupp), Warnung -> No, auffuellen, Deploy.
        var z = new ClanWarMode.Aufstellungen();
        assertTrue(z.bestaetigt(false));
        assertFalse(z.bestaetigt(false));
        assertEquals(1, z.anzahl());
        z.zuruecknehmen();
        assertEquals(0, z.anzahl());
        assertTrue(z.bestaetigt(false));
        assertEquals(1, z.anzahl(), "nur der normale Trupp steht -- der Titan fehlt noch");
    }

    @Test
    void normalUndTitanSindZwei() {
        var z = new ClanWarMode.Aufstellungen();
        z.bestaetigt(false);
        z.bestaetigt(true);
        assertEquals(2, z.anzahl());
        z.zuruecknehmen();   // Rauswurf direkt nach dem Titan
        assertEquals(1, z.anzahl());
        assertTrue(z.bestaetigt(true));
        assertEquals(2, z.anzahl());
    }

    @Test
    void unbekannteSorteNimmtDieFreie() {
        var z = new ClanWarMode.Aufstellungen();
        assertTrue(z.bestaetigt(null));
        assertTrue(z.bestaetigt(null));
        assertEquals(2, z.anzahl());
        assertFalse(z.bestaetigt(null));
        z.leeren();
        assertEquals(0, z.anzahl());
        z.zuruecknehmen();   // nichts zurueckzunehmen
        assertEquals(0, z.anzahl());
    }
}
