package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15.11. / SMHD, 9XBW, YPBQ (ben, 3.123, langsamer PC): der Bot entschied bei 28-32 s Restzeit "spaet angreifen", las danach noch
 * Kampfprotokoll (~10 s), Tabelle und Pyramiden-Namen (21-50 s) und klickte nach Rundenende ("Round missed"). Die Restzeit im
 * Protokoll ("29 s left") war beim Klick veraltet.
 */
class ArenaLeseFristTest {

    private static final long T = 1_000_000_000L;

    @Test
    void namenLesenEndetZehnSekundenVorStageEnde() {
        // YPBQ Runde 3: Angriffsphase bei 21 s Rest -- nur noch 11 s lesen, nicht 21 s.
        assertEquals(T + 11_000, ArenaMode.leseFrist(T, 21, T));
        // SMHD Runde 1: 29 s Rest -> 19 s (gelesen wurde 50 s).
        assertEquals(T + 19_000, ArenaMode.leseFrist(T, 29, T));
        // Schon unter 10 s: gar nicht mehr lesen, sofort angreifen.
        assertEquals(T, ArenaMode.leseFrist(T, 6, T));
        assertEquals(T, ArenaMode.leseFrist(T, 0, T));
    }

    @Test
    void lesenNieLaengerAlsVierzigSekundenAbAngriffsphase() {
        // Falsch gelesene Uhr (z. B. 90 statt 30): trotzdem hoechstens 40 s ab Beginn der Angriffsphase.
        assertEquals(T + 40_000, ArenaMode.leseFrist(T, 90, T));
        // Angriffsphase begann schon vor 35 s (Protokoll, Banner): es bleiben 5 s.
        assertEquals(T + 5_000, ArenaMode.leseFrist(T, 90, T - 35_000));
    }

    @Test
    void ohneUhrZwanzigSekundenAbAngriffsphase() {
        assertEquals(T + 20_000, ArenaMode.leseFrist(T, -1, T));
        assertEquals(T + 8_000, ArenaMode.leseFrist(T, -1, T - 12_000));
        // Angriffsphase unbekannt (0) oder in der Zukunft: ab jetzt.
        assertEquals(T + 20_000, ArenaMode.leseFrist(T, -1, 0));
        assertEquals(T + 20_000, ArenaMode.leseFrist(T, -1, T + 5_000));
    }

    @Test
    void langsamesNamenLesenZiehtDieAngriffsschwelleVor() {
        // Startwert und schnelle PCs (5-6 s Lesen) wie bisher: 25 s.
        assertEquals(25, ArenaMode.spaetestensSekunden(8_000, List.of(), 0));
        assertEquals(25, ArenaMode.spaetestensSekunden(8_000, List.of(), 6_000));
        assertEquals(25, ArenaMode.spaetestensSekunden(8_000, List.of()), "alte Form unveraendert");
        // ben: 21 s Lesen -> Angriffsphase ab 31 s Rest (statt 25-27).
        assertEquals(31, ArenaMode.spaetestensSekunden(8_000, List.of(), 21_000));
        // 50 s Lesen (SMHD Runde 1): nie frueher als 50 s vor Ende.
        assertEquals(ArenaMode.SPAETESTENS_HOECHSTENS_S, ArenaMode.spaetestensSekunden(8_000, List.of(), 50_000));
        // Die Vorarbeit bleibt, wenn sie mehr verlangt.
        assertEquals(37, ArenaMode.spaetestensSekunden(8_000, List.of(27_000L), 6_000));
    }

    @Test
    void protokollVorDemAngriffNurWennDieNamenNochPlatzHaben() {
        // 9XBW Runde 2: 32 s Rest, Protokoll 10 s, Namen 21 s -> NICHT mehr lesen (vorher: 32 >= 15 + 10 -> gelesen).
        assertTrue(ArenaMode.protokollVorAngriff(2, 32, 10_000));
        assertFalse(ArenaMode.protokollVorAngriff(2, 32, 10_000, 21_000));
        assertTrue(ArenaMode.protokollVorAngriff(2, 41, 10_000, 21_000));
        // Schnelle PCs: wie bisher 15 s + Lesedauer (16 s mit 6 s Namen).
        assertTrue(ArenaMode.protokollVorAngriff(2, 23, 8_000, 0));
        assertTrue(ArenaMode.protokollVorAngriff(2, 24, 8_000, 6_000));
        assertFalse(ArenaMode.protokollVorAngriff(2, 23, 8_000, 6_000));
        // Runde 1 nie, Uhr unbekannt wie bisher.
        assertFalse(ArenaMode.protokollVorAngriff(1, 90, 8_000, 0));
        assertTrue(ArenaMode.protokollVorAngriff(3, -1, 8_000, 21_000));
    }

    @Test
    void lesenImWartenNurWennEsNichtInDieAngriffszeitReicht() {
        // YPBQ: 32 s Rest, Protokoll 10 s, Angriff ab 31 s -> nicht mehr lesen.
        assertFalse(ArenaMode.lesenReichtVorAngriff(32, 10_000, 31));
        assertTrue(ArenaMode.lesenReichtVorAngriff(41, 10_000, 31));
        // Durchschauen mit 21 s Namen-Lesen: erst ab 52 s.
        assertFalse(ArenaMode.lesenReichtVorAngriff(51, 21_000, 31));
        assertTrue(ArenaMode.lesenReichtVorAngriff(52, 21_000, 31));
        // Noch nicht gemessen: nur die Schwelle zaehlt; Uhr unbekannt: wie bisher ja.
        assertTrue(ArenaMode.lesenReichtVorAngriff(26, 0, 25));
        assertTrue(ArenaMode.lesenReichtVorAngriff(-1, 21_000, 31));
    }

    @Test
    void lesedauerSteigtSofortUndSinktLangsam() {
        assertEquals(21_000, ArenaMode.lesedauerMerken(0, 21_000), "erste Messung gilt");
        assertEquals(10_000, ArenaMode.lesedauerMerken(4_000, 10_000), "laenger: sofort (9XBW: Schnitt 4 s, gelesen 10 s)");
        assertEquals(35_000, ArenaMode.lesedauerMerken(50_000, 20_000), "kuerzer: zur Haelfte");
        assertEquals(8_000, ArenaMode.lesedauerMerken(8_000, 0), "nichts gemessen");
        assertEquals(8_000, ArenaMode.lesedauerMerken(8_000, 200_000), "unsinnig lang");
    }

    @Test
    void abgebrochenesLesenWirdHochgerechnet() {
        // 4 von 9 Schildern in 19 s -> ganz waeren es ~43 s.
        assertEquals(42_750, ArenaMode.volleLesedauer(19_000, 4, 9));
        assertEquals(19_000, ArenaMode.volleLesedauer(19_000, 9, 9), "vollstaendig");
        assertEquals(19_000, ArenaMode.volleLesedauer(19_000, 0, 9), "nichts ganz gelesen: nicht hochrechnen");
    }

    @Test
    void ablaufYpbqRunde3MitFrist() {
        // 17:31:10 32 s Rest: mit 21 s gemessenem Namen-Lesen beginnt die Angriffsphase bei 31 s, das Protokoll faellt weg.
        long spaetestens = ArenaMode.spaetestensSekunden(8_000, List.of(), 21_000);
        assertFalse(32 <= spaetestens, "bei 32 s wird noch gewartet");
        assertFalse(ArenaMode.lesenReichtVorAngriff(32, 10_000, spaetestens), "aber kein Protokoll mehr im Warten");
        assertFalse(ArenaMode.protokollVorAngriff(3, 31, 10_000, 21_000), "und keins in der Angriffsphase");
        // Angriffsphase bei 31 s: 21 s Lesen passen bis 10 s vor Ende.
        long frist = ArenaMode.leseFrist(T, 31, T);
        assertEquals(T + 21_000, frist);
    }
}
