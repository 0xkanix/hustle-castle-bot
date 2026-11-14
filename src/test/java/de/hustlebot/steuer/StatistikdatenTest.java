package de.hustlebot.steuer;

import de.hustlebot.core.Laufstatistik;
import de.hustlebot.discord.DiscordClient;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Die Zahlen hinter den Diagrammen und der Vorrat-Tabelle, ohne Bildschirm. */
class StatistikdatenTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    private static Laufstatistik.RunRecord lauf(String um) {
        return new Laufstatistik.RunRecord(1, 270, 0, 0, "", um);
    }

    @Test
    void laeufeJeStundeZaehltUndFuelltLuecken() {
        List<Diagramm.Wert> w = Statistikdaten.laeufeJeStunde(List.of(lauf("08:10:00"), lauf("08:40:00"), lauf("10:05:00")));
        assertEquals(3, w.size(), "8, 9 (leer) und 10 Uhr");
        assertEquals("8 Uhr", w.get(0).name());
        assertEquals(2, w.get(0).zahl());
        assertEquals(0, w.get(1).zahl(), "die leere Stunde dazwischen steht mit 0 da");
        assertEquals(1, w.get(2).zahl());
    }

    @Test
    void laeufeOhneLesbareZeitWerdenUebergangen() {
        assertTrue(Statistikdaten.laeufeJeStunde(List.of(lauf(""), lauf(null), lauf("xx"))).isEmpty());
        assertTrue(Statistikdaten.laeufeJeStunde(null).isEmpty());
        assertEquals(-1, Statistikdaten.stunde("25:00"));
        assertEquals(14, Statistikdaten.stunde("14:23"));
    }

    private static final String ARENA = "# Kopf\n# Zeit\tGegner\t...\n"
            + "2026-10-08 10:00:00\tA\tC\t1\t1\t\t\t\t80\tnein\tS\t17\t150\t1\n"
            + "2026-10-08 10:05:00\tB\tC\t1\t1\t\t\t\t80\tnein\tN\t17\t0\t1\n"
            + "2026-10-08 10:09:00\tB\tC\t1\t1\t\t\t\t80\tnein\tS\t17\t0\t1\n"
            + "2026-10-09 11:00:00\tA\tC\t1\t1\t\t\t\t80\tnein\tS\t17\t150\t1\n"
            + "kaputte Zeile\n"
            + "2026-10-10 11:00:00\tA\tC\t1\t1\t\t\t\t80\tnein\tN\t17\t150\t1\n";

    @Test
    void arenaQuoteJeTag() {
        List<Diagramm.Wert> w = Statistikdaten.arenaQuoteJeTag(ARENA, 7);
        assertEquals(3, w.size());
        assertEquals("08.10.", w.get(0).name());
        assertEquals(67, w.get(0).zahl(), "2 von 3 gewonnen");
        assertEquals(100, w.get(1).zahl());
        assertEquals(0, w.get(2).zahl());
    }

    @Test
    void arenaQuoteNurDieLetztenTage() {
        List<Diagramm.Wert> w = Statistikdaten.arenaQuoteJeTag(ARENA, 2);
        assertEquals(2, w.size());
        assertEquals("09.10.", w.get(0).name());
        assertTrue(Statistikdaten.arenaQuoteJeTag("", 7).isEmpty());
        assertTrue(Statistikdaten.arenaQuoteJeTag(null, 7).isEmpty());
    }

    @Test
    void top3QuoteJeTagZaehltTurniere() {
        // Autor 22.10.: Top 1-3 = Sieg, Top 4-10 = Niederlage
        String plaetze = "# Kommentar\n2026-10-21 21:40\t1\n2026-10-21 22:00\t5\n2026-10-21 22:20\t3\n"
                + "2026-10-22 03:15\t8\n2026-10-22 03:30\t2\n";
        List<Diagramm.Wert> w = Statistikdaten.top3QuoteJeTag(plaetze, 7);
        assertEquals(2, w.size());
        assertEquals("21.10.", w.get(0).name());
        assertEquals(67, w.get(0).zahl(), "2 von 3 Turnieren Top 3");
        assertEquals(50, w.get(1).zahl());
        assertTrue(Statistikdaten.top3QuoteJeTag("", 7).isEmpty());
    }

    @Test
    void arenaBilanzZaehltSiegeUndKaempfe() {
        int[] b = Statistikdaten.arenaBilanz(ARENA);
        assertEquals(3, b[0]);
        assertEquals(5, b[1]);
    }

    @Test
    void vorratZeilenAusEinerKarte() {
        Map<String, String> z = Statistikdaten.vorratZeilen("**Name:** Autor\n**Nahrung:** 1 184 300\n\n**Tasche:** 17\nNichts lesbar -- Burg offen?");
        assertEquals("Autor", z.get("Name"));
        assertEquals("1 184 300", z.get("Nahrung"));
        assertEquals("17", z.get("Tasche"));
        assertEquals("Nichts lesbar -- Burg offen?", z.get("Hinweis"));
        assertEquals(List.of("Name", "Nahrung", "Tasche", "Hinweis"), List.copyOf(z.keySet()), "Reihenfolge wie auf der Karte");
    }

    @Test
    void zahlenMitLeerzeichenUndPunkten() {
        assertEquals(1184300L, Statistikdaten.zahl("1 184 300"));
        assertEquals(1184300L, Statistikdaten.zahl("1.184.300"));
        assertEquals(17L, Statistikdaten.zahl("17"));
        assertNull(Statistikdaten.zahl("Autor"));
        assertNull(Statistikdaten.zahl(""));
        assertNull(Statistikdaten.zahl(null));
    }

    @Test
    void nahrungAusDenBurgStandKarten() {
        List<Meldung> m = List.of(
                new Meldung(LocalTime.of(10, 5, 0), "", "Burg-Stand", "**Nahrung:** 1 000 000\n**Holz:** 5", DiscordClient.FARBE_INFO, null),
                new Meldung(LocalTime.of(10, 6, 0), "", "Statistik", "Nahrung: 999", DiscordClient.FARBE_INFO, null),
                new Meldung(LocalTime.of(11, 0, 0), "", "Castle status", "**Food:** 1 200 000", DiscordClient.FARBE_INFO, null),
                new Meldung(LocalTime.of(11, 5, 0), "", "Burg-Stand", "Nichts lesbar", DiscordClient.FARBE_WARNUNG, null));
        List<Diagramm.Wert> w = Statistikdaten.nahrungAusMeldungen(m, 12);
        assertEquals(2, w.size(), "nur Burg-Stand-Karten mit Nahrung");
        assertEquals("10:05", w.get(0).name());
        assertEquals(1_000_000, w.get(0).zahl());
        assertEquals(1_200_000, w.get(1).zahl());
        assertEquals(1, Statistikdaten.nahrungAusMeldungen(m, 1).size(), "hoechstens so viele, die neuesten");
    }

    @Test
    void kurzeZahlen() {
        assertEquals("1,2 Mio", Statistikdaten.kurz(1_184_300));
        assertEquals("45 Tsd", Statistikdaten.kurz(45_000));
        assertEquals("320", Statistikdaten.kurz(320));
        assertEquals("08.10.", Statistikdaten.tagKurz("2026-10-08"));
    }
}
