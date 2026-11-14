package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** Autor 25.10.: Seit der Zeitumstellung um 20:20 setzen, um 21:45 nachsehen -- im Sommer eine Stunde spaeter. */
class KriegsplanZeitumstellungTest {

    private static LocalDateTime t(String s) {
        return LocalDateTime.parse(s);
    }

    @Test
    void winterAbZwanzigUhr() {
        assertNull(Kriegsplan.offenesFenster(t("2026-10-25T20:00")));
        assertNull(Kriegsplan.offenesFenster(t("2026-10-25T20:10")));
        assertEquals(t("2026-10-25T20:20"), Kriegsplan.offenesFenster(t("2026-10-25T20:20")));
        assertEquals(t("2026-10-25T20:20"), Kriegsplan.offenesFenster(t("2026-10-25T21:44")));
        assertEquals(t("2026-10-25T21:45"), Kriegsplan.offenesFenster(t("2026-10-25T21:55")));
        assertNull(Kriegsplan.offenesFenster(t("2026-10-25T22:00")));
        assertEquals(t("2026-10-26T02:20"), Kriegsplan.offenesFenster(t("2026-10-26T02:30")));
        assertEquals(t("2026-10-26T03:45"), Kriegsplan.offenesFenster(t("2026-10-26T03:52")));
        assertEquals(t("2026-10-26T08:20"), Kriegsplan.naechsteKriegszeit(t("2026-10-26T04:10")));
        assertEquals(t("2026-10-26T14:20"), Kriegsplan.naechsteKriegszeit(t("2026-10-26T12:00")));
        // Nachpruefen 15 min vor Kriegsbeginn (Autor 25.10.: "um 3.45 Uhr rechecken")
        org.junit.jupiter.api.Assertions.assertTrue(Kriegsplan.istKontrolle(t("2026-10-26T03:50")));
        org.junit.jupiter.api.Assertions.assertFalse(Kriegsplan.istKontrolle(t("2026-10-26T03:40")));
        org.junit.jupiter.api.Assertions.assertFalse(Kriegsplan.istKontrolle(t("2026-10-26T04:00")));
    }

    @Test
    void sommerEineStundeSpaeterInDerAnzeige() {
        // innere Uhr = Weltzeit+1, auch im Sommer: 19:20 Weltzeit = 20:20 innen = 21:20 in Berlin (Sommerzeit)
        assertNull(Kriegsplan.offenesFenster(t("2026-10-24T19:30")));
        assertEquals(t("2026-10-24T20:20"), Kriegsplan.offenesFenster(t("2026-10-24T20:20")));
        assertEquals(t("2026-10-24T21:45"), Kriegsplan.offenesFenster(t("2026-10-24T21:55")));
        java.time.ZoneId berlin = java.time.ZoneId.of("Europe/Berlin"), london = java.time.ZoneId.of("Europe/London");
        assertEquals("21:20", Kriegsplan.zeitText(t("2026-10-24T20:20"), berlin));
        assertEquals("20:20", Kriegsplan.zeitText(t("2026-10-24T20:20"), london));
        // Winter: Berlin 02:20, London 01:20 (Ben: "der Clankrieg hat bei mir um 1 angefangen")
        assertEquals("02:20", Kriegsplan.zeitText(t("2026-10-26T02:20"), berlin));
        assertEquals("01:20", Kriegsplan.zeitText(t("2026-10-26T02:20"), london));
        // Fruehling: ab dem 29.03.2027 wieder Sommerzeit -> in Berlin 21:20
        assertEquals("21:20", Kriegsplan.zeitText(Kriegsplan.naechsteKriegszeit(t("2027-03-29T19:00")), berlin));
    }

    @Test
    void naechsteKriegszeitInDerOrtszeit() {
        java.time.ZoneId london = java.time.ZoneId.of("Europe/London"), berlin = java.time.ZoneId.of("Europe/Berlin");
        // 13:20 Weltzeit: London 13:20, Berlin 14:20, New York (bis 01.11. Sommerzeit, -4) 09:20
        java.time.Instant mittag = java.time.Instant.parse("2026-10-26T12:00:00Z");
        assertEquals(t("2026-10-26T13:20"), Kriegsplan.naechsteKriegszeitIn(mittag, london).toLocalDateTime());
        assertEquals(t("2026-10-26T14:20"), Kriegsplan.naechsteKriegszeitIn(mittag, berlin).toLocalDateTime());
        assertEquals(t("2026-10-26T09:20"), Kriegsplan.naechsteKriegszeitIn(mittag, java.time.ZoneId.of("America/New_York")).toLocalDateTime());
    }

    @Test
    void nachtDerZeitumstellungNichtZuFrueh() {
        // 25.10.2026: 02:00-03:00 gab es in Berlin zweimal. 00:20 Weltzeit (erstes 02:20) = 01:20 innen -> noch zu
        // (Fenster ab 01:20 Weltzeit = 02:20 innen); 01:20 Weltzeit (zweites 02:20) -> offen.
        assertNull(Kriegsplan.offenesFenster(t("2026-10-25T01:20")));
        assertEquals(t("2026-10-25T02:20"), Kriegsplan.offenesFenster(t("2026-10-25T02:20")));
    }
}
