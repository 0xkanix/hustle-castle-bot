package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Clan-Krieg, unvollstaendiger Trupp (Autor 22.10.): Zaehler "6/8" neben dem Helm. */
class TruppAuffuellenTest {

    @Test
    void zaehlerLesen() {
        assertArrayEquals(new int[]{6, 8}, TruppAuffuellen.zaehlerAusText("6/8"));
        assertArrayEquals(new int[]{7, 8}, TruppAuffuellen.zaehlerAusText(" 7 / 8 "));
        assertArrayEquals(new int[]{4, 4}, TruppAuffuellen.zaehlerAusText("4l4"));
        assertArrayEquals(new int[]{6, 6}, TruppAuffuellen.zaehlerAusText("x 6|6"));
        assertNull(TruppAuffuellen.zaehlerAusText("9/8"));
        assertNull(TruppAuffuellen.zaehlerAusText("41 899 732"));
        assertNull(TruppAuffuellen.zaehlerAusText(""));
    }

    /** Server 01.11.: die Windows-Texterkennung las "6/8" als "618" und "4/8" als "418" -- der Bot war blind. */
    @Test
    void schraegstrichAlsEinsGelesen() {
        assertArrayEquals(new int[]{6, 8}, TruppAuffuellen.zaehlerAusText("618"));
        assertArrayEquals(new int[]{4, 8}, TruppAuffuellen.zaehlerAusText(" 418 "));
        assertArrayEquals(new int[]{8, 8}, TruppAuffuellen.zaehlerAusText("818"));
        assertNull(TruppAuffuellen.zaehlerAusText("628"));
        assertNull(TruppAuffuellen.zaehlerAusText("6187"));
        assertNull(TruppAuffuellen.zaehlerAusText("819"));
    }

    @Test
    void namenVergleichbar() {
        assertEquals("magier1", TruppAuffuellen.zeichen("Magier 1"));
        assertEquals("ersatz", TruppAuffuellen.zeichen(" ersatz."));
        assertEquals("", TruppAuffuellen.zeichen(null));
    }

    /** Autor 01.11.: "am Anfang waren 6 Einheiten drin, jetzt nur noch 4" -- darf nicht unbemerkt bleiben. */
    @Test
    void zaehlerEntscheidetUeberDenZug() {
        assertFalse(TruppAuffuellen.zugMisslungen(6, 7, true), "Karte dazu");
        assertTrue(TruppAuffuellen.zugMisslungen(6, 6, true), "Karte hat eine andere verdraengt oder nichts getan");
        assertTrue(TruppAuffuellen.zugMisslungen(6, 5, true), "Karte hat eine Einheit herausgeworfen");
        assertFalse(TruppAuffuellen.zugMisslungen(6, 6, false), "Wischen aendert nichts");
        assertTrue(TruppAuffuellen.zugMisslungen(5, 4, false), "Wischen hat eine Einheit herausgezogen (08:23)");
    }

    /** Kartenmitten aus den Fotos 01.11.: 225-804 ganz, 997 rechts abgeschnitten ("Magie" 449). */
    @Test
    void abgeschnitteneKarteZaehltNicht() {
        assertTrue(TruppAuffuellen.ganzZuSehen(225, 1159));
        assertTrue(TruppAuffuellen.ganzZuSehen(804, 1159));
        assertTrue(TruppAuffuellen.ganzZuSehen(900, 1159), "ersatz am Ende der Reihe");
        assertFalse(TruppAuffuellen.ganzZuSehen(997, 1159), "Magier 3 rechts abgeschnitten");
        assertFalse(TruppAuffuellen.ganzZuSehen(180, 1159), "links abgeschnitten");
        assertTrue(TruppAuffuellen.ganzZuSehen(450, 2318) , "doppelte Fenstergroesse: Lage wird umgerechnet");
    }

    /** Foto 08:24: nach dem ersten Wischen lag bei x 904 die Karte "ersatz", die Reihe rollte nicht weiter. */
    @Test
    void wischenBeginntAufDerTrennlinie() {
        // Kraft-Symbole in der Anfangslage (Foto 02:23): Trennlinien 325, 518, 711, 904; das abgeschnittene waere 1097
        assertEquals(904, TruppAuffuellen.untenWischX(List.of(181, 374, 567, 760, 953), 1159));
        // nach dem Wischen (Foto 08:24): Trennlinie rechts neben "ersatz" bei 1000
        assertEquals(1000, TruppAuffuellen.untenWischX(List.of(275, 469, 662, 856), 1159));
        // keine Karte erkannt: wie frueher
        assertEquals(904, TruppAuffuellen.untenWischX(List.of(), 1159));
    }

    @Test
    void reiheErstAmEndeWennLetzterPlatzFrei() {
        assertTrue(TruppAuffuellen.reiheAmEnde(928, 1159));
        assertTrue(TruppAuffuellen.reiheAmEnde(915, 1159));
        assertFalse(TruppAuffuellen.reiheAmEnde(848, 1159), "Anfangslage mit 4/8");
        assertFalse(TruppAuffuellen.reiheAmEnde(800, 1159));
    }

    @Test
    void dieselbeKarteTrotzLesefehler() {
        Set<String> g = new HashSet<>(Set.of("4495507#1"));
        assertTrue(TruppAuffuellen.schonGesehen(g, "4495507", 1));
        assertTrue(TruppAuffuellen.schonGesehen(g, "4495501", 1), "eine Ziffer anders");
        assertTrue(TruppAuffuellen.schonGesehen(g, "449507", 1), "eine Ziffer fehlt");
        assertFalse(TruppAuffuellen.schonGesehen(g, "4549201", 1), "andere Einheit");
        assertFalse(TruppAuffuellen.schonGesehen(g, "4495507", 2), "zweite gleich starke Einheit");
        assertFalse(TruppAuffuellen.eineZifferAnders("449", "441"), "zu kurz");
    }
}
