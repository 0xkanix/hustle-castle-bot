package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mia 21.10.: "food only" oeffnet absichtlich keine Nahrungskisten, "alles" nimmt zuerst Tickets.
 * Neue Bezahlart "food+chests": Nahrung, dann Nahrungskisten, nie Tickets. Hier die reinen
 * Entscheidungen (Wort, Eigenschaften) -- ohne Spielbild und ohne bezahlung.txt anzufassen.
 */
class BezahlungNahrungKistenTest {

    @Test
    void worte_der_neuen_art() {
        for (String w : new String[]{"food+chests", "food+kisten", "nahrung+kisten", "essen+kisten", "FOOD+Chests", " food+chests "}) {
            assertEquals(Bezahlung.Art.NAHRUNG_KISTEN, Bezahlung.ausWort(w), w);
        }
    }

    @Test
    void alte_worte_bedeuten_weiter_dasselbe() {
        assertEquals(Bezahlung.Art.TICKETS, Bezahlung.ausWort("tickets"));
        assertEquals(Bezahlung.Art.TICKETS, Bezahlung.ausWort("keys"));
        assertEquals(Bezahlung.Art.NAHRUNG, Bezahlung.ausWort("food"));
        assertEquals(Bezahlung.Art.NAHRUNG, Bezahlung.ausWort("essen"));
        assertEquals(Bezahlung.Art.NAHRUNG, Bezahlung.ausWort("nahrung"));
        assertEquals(Bezahlung.Art.ERST_TICKETS, Bezahlung.ausWort("tickets+food"));
        assertEquals(Bezahlung.Art.ALLES, Bezahlung.ausWort("alles"));
        assertEquals(Bezahlung.Art.ALLES, Bezahlung.ausWort("all"));
        assertEquals(Bezahlung.Art.ALLES, Bezahlung.ausWort("kisten"));
        assertNull(Bezahlung.ausWort("food+quatsch"));
        assertNull(Bezahlung.ausWort(null));
    }

    @Test
    void jede_art_findet_ueber_ihr_wort_zu_sich_zurueck() {
        for (Bezahlung.Art a : Bezahlung.Art.values()) {
            assertEquals(a, Bezahlung.ausWort(a.wort), a.name());
        }
    }

    @Test
    void nur_die_neue_art_nutzt_nie_tickets() {
        assertTrue(Bezahlung.Art.NAHRUNG_KISTEN.nieTickets());
        assertFalse(Bezahlung.Art.TICKETS.nieTickets());
        assertFalse(Bezahlung.Art.NAHRUNG.nieTickets());
        assertFalse(Bezahlung.Art.ERST_TICKETS.nieTickets());
        assertFalse(Bezahlung.Art.ALLES.nieTickets());
    }

    @Test
    void kisten_oeffnen_nur_alles_und_die_neue_art() {
        assertTrue(Bezahlung.Art.NAHRUNG_KISTEN.oeffnetKisten());
        assertTrue(Bezahlung.Art.ALLES.oeffnetKisten());
        assertFalse(Bezahlung.Art.NAHRUNG.oeffnetKisten());   // bleibt wie bisher: "nur Nahrung" = keine Kisten
        assertFalse(Bezahlung.Art.TICKETS.oeffnetKisten());
        assertFalse(Bezahlung.Art.ERST_TICKETS.oeffnetKisten());
    }

    @Test
    void mit_nahrung_von_anfang_an_nur_bei_nahrung_und_der_neuen_art() {
        assertTrue(Bezahlung.Art.NAHRUNG.startetMitNahrung());
        assertTrue(Bezahlung.Art.NAHRUNG_KISTEN.startetMitNahrung());
        assertFalse(Bezahlung.Art.TICKETS.startetMitNahrung());
        assertFalse(Bezahlung.Art.ERST_TICKETS.startetMitNahrung());
        assertFalse(Bezahlung.Art.ALLES.startetMitNahrung());
        for (Bezahlung.Art a : Bezahlung.Art.values()) {
            if (a != Bezahlung.Art.TICKETS) assertTrue(a.mitNahrung(), a.name());
        }
    }

    @Test
    void anzeige_ist_zweisprachig() {
        assertEquals("food, then food chests (no tickets)", Bezahlung.Art.NAHRUNG_KISTEN.englisch);
        assertEquals("erst Nahrung, dann Nahrungskisten (ohne Tickets)", Bezahlung.Art.NAHRUNG_KISTEN.anzeige);
        assertEquals("food+chests", Bezahlung.Art.NAHRUNG_KISTEN.wort);
    }
}
