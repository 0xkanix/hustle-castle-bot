package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Autor 01.11., Lauf 13:31 und 13:52: Das Vergleichsfenster las die EIGENE Kraft als 4.055.264 statt 42.055.264 (Ziffer verloren). Folge: 49 + 59 Mal
 * "Kraft ... passt nicht zur eigenen -- verworfen", 15/16 Mal "power not readable", und 屈機黑雞 (Cthulhu's Realm, 74 Mio) bekam "94 %".
 * Die Zeilen und Zahlen unten sind echte Protokollzeilen.
 */
class ArenaKraftKetteTest {

    private static final long ECHT = 42_055_264L, VERLESEN = 4_055_264L;
    /** Gegnerkraefte aus dem Protokoll 13:35 und 14:01, die alle verworfen wurden. */
    private static final long[] GEGNER = {24_584_661L, 50_252_935L, 32_216_084L, 45_958_641L, 37_278_493L, 74_573_627L, 38_741_723L, 49_416_269L};

    @Test
    void verleseneEigeneKraftWirdNichtUebernommen() {
        assertEquals(-1, ArenaMode.eigeneKraftPruefen(VERLESEN, ECHT), "4.055.264 gegen 42.055.264: Ziffer verloren");
        assertEquals(42_100_000L, ArenaMode.eigeneKraftPruefen(42_100_000L, ECHT), "kleine Aenderung durch neue Ausruestung gilt");
        assertEquals(-1, ArenaMode.eigeneKraftPruefen(0, ECHT));
        assertEquals(VERLESEN, ArenaMode.eigeneKraftPruefen(VERLESEN, -1), "nichts bekannt: wie bisher");
        assertEquals(-1, ArenaMode.eigeneKraftPruefen(ECHT * 3, ECHT), "mehr als das Doppelte ist ein Lesefehler");
    }

    @Test
    void mitDerFalschenEigenenKraftWurdenAlleEchtenGegnerkraefteVerworfen() {
        for (long g : GEGNER) {
            assertFalse(ArenaMode.kraftPlausibel(g, VERLESEN), "mit 4,05 Mio verworfen (Ursache von 'power not readable'): " + g);
            assertTrue(ArenaMode.kraftPlausibel(g, ECHT), "mit der echten Kraft glaubhaft: " + g);
        }
    }

    @Test
    void spaeterAngriffHaeltAbstandAuchBeiDerSchlechtestenVorarbeit() {
        // Lauf 13:52: Vorarbeit 23, 21, 27, 19, 24 s, Schnitt 13 s: bisher "Angriff ab 28 s Rest" -> Klick bei 5 s Rest.
        List<Long> letzte = List.of(23_000L, 21_000L, 27_000L, 19_000L, 24_000L);
        long alt = Math.min(45, 25 + Math.max(0, 13_000 / 1000 - 10));
        long neu = ArenaMode.spaetestensSekunden(13_000, letzte);
        assertEquals(28, alt, "die alte Rechnung");
        assertEquals(37, neu, "schlechteste Vorarbeit 27 s + 10 s Abstand");
        assertTrue(neu - 27 >= ArenaMode.ABSTAND_ENDE_S, "auch die schlechteste Vorarbeit klickt noch 10 s vor Ende");
        assertTrue(neu < 60, "spaet angreifen bleibt: Grenze deutlich unter der halben Stage");
    }

    @Test
    void ohneMessungGiltDieAlteRegelUndEineGrenzeBleibt() {
        assertEquals(25, ArenaMode.spaetestensSekunden(8_000, List.of()), "Startwert wie bisher");
        assertEquals(ArenaMode.SPAETESTENS_HOECHSTENS_S, ArenaMode.spaetestensSekunden(8_000, List.of(90_000L)), "nie frueher als 50 s vor Ende");
        assertEquals(45, ArenaMode.spaetestensSekunden(60_000, List.of(5_000L)), "die alte Regel bleibt Untergrenze (hoechstens 45)");
    }

    /** 屈機黑雞: Team UND Kraft unbekannt, aber Punkte 50 und Erfahrung 95 % ergaben 94 % -- jetzt hoechstens wie unbekannt. */
    @Test
    void ohneTeamUndKraftGiltHoechstensWieUnbekannt() {
        assertEquals(0.50, ArenaMode.UNBEKANNT_HOECHSTENS, 1e-9);
        assertTrue(ArenaMode.wissenLos(false, false, false, false), "屈機黑雞: nichts bekannt");
        assertFalse(ArenaMode.wissenLos(false, false, true, false), "Kraft bekannt: normale Rechnung");
        assertFalse(ArenaMode.wissenLos(false, false, false, true), "Team bekannt: normale Rechnung");
        assertFalse(ArenaMode.wissenLos(true, false, false, false), "Not in clan hat seine eigene Regel");
        assertFalse(ArenaMode.wissenLos(false, true, false, false), "Autors Liste (sicher/machbar) gilt");
    }

    @Test
    void derselbeSpielerUnterZweiLesartenWirdNurEinmalGesehen() {
        Map<String, String> alias = Map.of("屈機黑雞", "cthulhusre");   // Schild -> Fenster
        Set<String> gesehen = new HashSet<>(Set.of("cthulhusre"));
        assertTrue(ArenaMode.dieselbeLesart(gesehen::contains, alias, "屈機黑雞"), "Schild-Name findet die Pruefung unter dem Fenster-Namen");
        Set<String> gesehenSchild = new HashSet<>(Set.of("屈機黑雞"));
        assertTrue(ArenaMode.dieselbeLesart(gesehenSchild::contains, alias, "cthulhusre"), "und umgekehrt");
        assertFalse(ArenaMode.dieselbeLesart(gesehen::contains, alias, "ava"), "ein anderer Spieler nicht");
        assertTrue(ArenaMode.dieselbeLesart(gesehen::contains, Map.of(), "cthulhusre"), "ohne Alias wie bisher");
    }

    @Test
    void fensterNameDerNurEinClanStueckIstGiltNichtAlsName() {
        // Protokoll 14:01: Fenster liest "*Cthulh" / "Cthulhu's Re" (Clan "Realm"), das Schild heisst 屈機黑雞.
        assertTrue(ArenaMode.fensterNameGehoertNichtDazu("*Cthulh", "Realm", "屈機黑雞", ""));
        assertTrue(ArenaMode.fensterNameGehoertNichtDazu("Cthulhu's Re", "Realm", "屈機黑雞", "Cthulhu's Realm"));
        // Clan-Anfang auch ohne fremde Schrift (Schild-Name lateinisch, anderes Wort)
        assertTrue(ArenaMode.fensterNameGehoertNichtDazu("Cthulhu's Re", "", "Bob", "Cthulhu's Realm"));
        // dieselben Namen, kyrillisch, kurz oder ganz gleich: nichts ersetzen
        assertFalse(ArenaMode.fensterNameGehoertNichtDazu("Andrey", "", "Андрей", ""), "kyrillisch ist keine fremde Schrift");
        assertFalse(ArenaMode.fensterNameGehoertNichtDazu("KinEsch", "Валидол", "KinEsch", ""));
        assertFalse(ArenaMode.fensterNameGehoertNichtDazu("Ab", "", "屈機黑雞", ""), "unter 3 lateinischen Buchstaben kein Urteil");
        assertFalse(ArenaMode.fensterNameGehoertNichtDazu("Ava", "", "Ava", "Kein Clan"));
        assertFalse(ArenaMode.fensterNameGehoertNichtDazu("", "", "屈機黑雞", ""));
    }

    /** Stage 4: Top-3-Rechnung 92 %, Klick-Zeile 47 % (Myth Gods) -- aufgehoben hebt nur an, wenn die rohe Chance nicht dagegen spricht. */
    @Test
    void aufgehobenHebtNichtGegenEineSchlechteRoheChanceAn() {
        assertEquals(0.47, ArenaMode.aufgehobenAnheben(0.47, 0.47, 4, true), 1e-9, "roh 47 %: kein Boden von 92 %");
        assertEquals(0.92, ArenaMode.aufgehobenAnheben(0.80, 0.85, 4, true), 1e-9, "roh 85 %: Autors Regel gilt weiter");
        assertEquals(0.70, ArenaMode.aufgehobenAnheben(0.70, 0.85, 3, true), 1e-9, "erst ab Stage 4");
        assertEquals(0.70, ArenaMode.aufgehobenAnheben(0.70, 0.95, 5, false), 1e-9, "nur fuer aufgehobene Spieler");
    }
}
