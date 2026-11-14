package de.hustlebot.bot;

import de.hustlebot.core.Bezahlung;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mia 21.10.: Wie die Kasse je Bezahlart startet -- ohne Spielbild. Die Art kommt wie bei !start
 * aus dem Auftrags-Wort (Bezahlung.auftragBeginnt), bezahlung.txt bleibt unberuehrt.
 */
class BezahlkasseNahrungKistenTest {

    @AfterEach
    void aufraeumen() {
        Bezahlung.auftragBeginnt(null);
    }

    private static Bezahlkasse kasse(Bezahlung.Art art) {
        Bezahlung.auftragBeginnt(art);
        Bezahlkasse k = new Bezahlkasse("cathedral", "t_food", "t_food_off", "t_tickets_off", "t_apfel", "t_knoten", "t_x");
        k.auftragBeginnt();
        return k;
    }

    @Test
    void food_chests_zahlt_von_anfang_an_mit_nahrung_und_wechselt_nie_auf_tickets() {
        Bezahlkasse k = kasse(Bezahlung.Art.NAHRUNG_KISTEN);
        assertTrue(k.mitNahrung());
        assertFalse(k.ticketsAlle());   // es gibt keinen Wechsel von Tickets auf Nahrung
        assertTrue(k.mitNahrung());
    }

    @Test
    void bisherige_arten_starten_wie_vorher() {
        assertTrue(kasse(Bezahlung.Art.NAHRUNG).mitNahrung());
        assertFalse(kasse(Bezahlung.Art.TICKETS).mitNahrung());
        assertFalse(kasse(Bezahlung.Art.ERST_TICKETS).mitNahrung());
        assertFalse(kasse(Bezahlung.Art.ALLES).mitNahrung());
    }

    @Test
    void tickets_alle_schaltet_nur_bei_erst_tickets_und_alles_auf_nahrung() {
        assertFalse(kasse(Bezahlung.Art.TICKETS).ticketsAlle());
        assertFalse(kasse(Bezahlung.Art.NAHRUNG).ticketsAlle());
        Bezahlkasse erst = kasse(Bezahlung.Art.ERST_TICKETS);
        assertTrue(erst.ticketsAlle());
        assertTrue(erst.mitNahrung());
        Bezahlkasse alles = kasse(Bezahlung.Art.ALLES);
        assertTrue(alles.ticketsAlle());
        assertTrue(alles.mitNahrung());
    }
}
