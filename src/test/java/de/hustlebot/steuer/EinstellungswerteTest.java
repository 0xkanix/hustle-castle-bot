package de.hustlebot.steuer;

import de.hustlebot.core.Eventautomatik;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F24-F27): Werte lesen, Eingaben pruefen, Suchen.
 */
class EinstellungswerteTest {

    @Test
    void befehlMitWertFindetDenWertOhneWert() {
        assertEquals("!settings leuchtturm", Einstellungswerte.finde("!settings leuchtturm aus").schluessel());
        assertEquals("!settings leuchtturm", Einstellungswerte.finde("  !Settings   Leuchtturm  ").schluessel());
        assertEquals("!settings nahrungslimit alle", Einstellungswerte.finde("!settings nahrungslimit alle 30m").schluessel());
        assertEquals("!settings bezahlen", Einstellungswerte.finde("!settings bezahlen alle alles").schluessel());
        assertEquals("!kriegsplan", Einstellungswerte.finde("!kriegsplan an").schluessel());
        assertEquals("!clanwar", Einstellungswerte.finde("!clanwar off").schluessel());
        // der laengere Schluessel gewinnt
        assertEquals("!settings heldenset vorher", Einstellungswerte.finde("!settings heldenset vorher War 2").schluessel());
        assertEquals("!settings heldenset", Einstellungswerte.finde("!settings heldenset an").schluessel());
        // nichts, was nicht dazugehoert
        assertNull(Einstellungswerte.finde("!team scan"));
        assertNull(Einstellungswerte.finde("!settings leuchtturmx"));
        assertNull(Einstellungswerte.finde(""));
        assertNull(Einstellungswerte.finde(null));
    }

    @Test
    void jederWertIstLesbarUndDerSetzBefehlFindetWiederDenselben() {
        for (Einstellungswerte.Wert w : Einstellungswerte.alle()) {
            assertNotNull(w.aktuellAnzeige(), w.schluessel() + ": Anzeige");
            String roh = w.aktuellRoh();
            if (roh != null) {
                assertSame(w, Einstellungswerte.finde(w.setzBefehl(roh)), w.schluessel() + ": Rundlauf");
            }
            if (w.standard() != null) {
                assertSame(w, Einstellungswerte.finde(w.setzBefehl(w.standard())), w.schluessel() + ": Standard");
            }
        }
    }

    @Test
    void passWorteSindFuerDenBotVerstaendlich() {
        for (var p : Eventautomatik.Pass.values()) {
            assertEquals(p, Eventautomatik.passAusWort(p.name().toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals("gold", Einstellungswerte.finde("!settings pass").standard());
    }

    @Test
    void standardwerteBestehenDiePruefung() {
        for (Einstellungswerte.Wert w : Einstellungswerte.alle()) {
            if (w.standard() == null) continue;
            assertTrue(Eingabepruefung.fehler(w.schluessel(), w.standard()).isEmpty(), w.schluessel() + " = " + w.standard());
        }
    }

    // ------------------------------------------------------------------ Eingabepruefung (F25)

    @Test
    void zahlenfelderHabenEinenBereich() {
        var z = Eingabepruefung.zahlenbereich("!settings hammerevent");
        assertNotNull(z);
        assertEquals(0, z.min());
        assertEquals(500, z.max());
        assertNull(Eingabepruefung.zahlenbereich("!settings nahrungslimit alle"));
        assertNull(Eingabepruefung.zahlenbereich("!zeitzone"));
        assertEquals(10, Eingabepruefung.zahlenbereich("!settings lupen").max());
    }

    @Test
    void falscheEingabenWerdenGemeldet() {
        assertTrue(Eingabepruefung.fehler("!settings hammerevent", "").isEmpty(), "leer = nur anzeigen");
        assertTrue(Eingabepruefung.fehler("!settings hammerevent", "100").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings hammerevent", "501").isPresent());
        assertTrue(Eingabepruefung.fehler("!settings hammerevent", "abc").isPresent());
        assertTrue(Eingabepruefung.fehler("!settings lupen", "11").isPresent());
        assertTrue(Eingabepruefung.fehler("!settings lupen", "5").isEmpty());

        assertTrue(Eingabepruefung.fehler("!settings nahrungslimit alle", "30m").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings nahrungslimit alle", "1,5 Mio").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings nahrungslimit alle", "500k").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings nahrungslimit alle", "aus").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings nahrungslimit alle", "viel").isPresent());

        assertTrue(Eingabepruefung.fehler("!settings lager", "2m").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings lager", "50000").isPresent());
        assertTrue(Eingabepruefung.fehler("!settings lager", "9m").isPresent());

        assertTrue(Eingabepruefung.fehler("!settings pass", "gold").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings pass", "silber").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings pass", "platin").isPresent());

        assertTrue(Eingabepruefung.fehler("!settings eventautomatik", "titan").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings eventautomatik", "25").isEmpty());
        assertTrue(Eingabepruefung.fehler("!settings eventautomatik", "vielleicht").isPresent());

        assertTrue(Eingabepruefung.fehler("!zeitzone", "Europe/Berlin").isEmpty());
        assertTrue(Eingabepruefung.fehler("!zeitzone", "Narnia/Nirgendwo").isPresent());
        assertTrue(Eingabepruefung.fehler("!settings gibtsnicht", "irgendwas").isEmpty(), "ohne Regel nie ein Fehler");
    }

    @Test
    void mengeWieInDerBruecke() {
        assertEquals(-1, Eingabepruefung.menge("aus"));
        assertEquals(30_000_000L, Eingabepruefung.menge("30m"));
        assertEquals(1_500_000L, Eingabepruefung.menge("1,5 Mio"));
        assertEquals(500_000L, Eingabepruefung.menge("500k"));
        assertEquals(15_000_000L, Eingabepruefung.menge("15000000"));
        assertEquals(-2, Eingabepruefung.menge("viel"));
    }

    // ------------------------------------------------------------------ Suche (F24)

    @Test
    void sucheIgnoriertGrossKleinUmlauteUndHtml() {
        assertTrue(Einstellungssuche.passt("nahrung", "<html>Use <b>Nahrung</b> benutzen</html>"));
        assertTrue(Einstellungssuche.passt("NAHRUNG BENUTZEN", "Nahrung benutzen"));
        assertTrue(Einstellungssuche.passt("benutzen nahrung", "Nahrung benutzen"), "Reihenfolge egal");
        assertTrue(Einstellungssuche.passt("zuruck", "Auf Standard zurück"));
        assertTrue(Einstellungssuche.passt("gross", "Größe"));
        assertTrue(Einstellungssuche.passt("", "irgendwas"));
        assertFalse(Einstellungssuche.passt("clan", "Nahrung benutzen"));
        assertFalse(Einstellungssuche.passt("nahrung clan", "Nahrung benutzen"));
        assertEquals("grosse ubung", Einstellungssuche.normal("<b>Große</b>  Übung"));
        assertTrue(Einstellungssuche.passt("zurueck", "Auf Standard zurück"));
        assertTrue(Einstellungssuche.passt("zurück", "Auf Standard zurueck"));
        assertTrue(Einstellungssuche.passt("neu", "Bot neuer Start"), "ue/ae darf andere Woerter nicht zerstoeren");
    }
}
