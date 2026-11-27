package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Konrad 19.11.: Zeitzone Europe/Berlin eingestellt, bei ihm war es 13:04, der Bot zeigte 22:03 und setzte im Clan-Krieg
 * nicht. Ursache (Berichte seit 29.10.): Windows-Zeitzone Pazifik, Uhr von Hand auf deutsche Zeit gestellt -- der innere
 * Augenblick von Windows liegt 9 h (bis 01.11.: 8 h) vorn. Die Weltuhr stellt das mit dem "Date"-Kopf aus dem Netz richtig.
 */
class WeltuhrTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId PAZIFIK = ZoneId.of("America/Los_Angeles");

    @TempDir
    Path tmp;

    private Clock alteUhr;
    private Path alteDatei;
    private Path alteZoneDatei;

    @BeforeEach
    void umleiten() {
        alteUhr = Weltuhr.rechner;
        alteDatei = Weltuhr.datei;
        alteZoneDatei = Zeitzone.datei;
        Weltuhr.datei = tmp.resolve("weltuhr.txt");
        Weltuhr.zuruecksetzen();
        Zeitzone.datei = tmp.resolve("zeitzone.txt");
        Zeitzone.neuLesen();
    }

    @AfterEach
    void zurueck() {
        Weltuhr.rechner = alteUhr;
        Weltuhr.datei = alteDatei;
        Weltuhr.zuruecksetzen();
        Zeitzone.datei = alteZoneDatei;
        Zeitzone.neuLesen();
    }

    /** Windows zeigt 'zeigt' in der Windows-Zone 'windowsZone' (so wie der Nutzer die Uhr gestellt hat). */
    private static void windows(String zeigt, ZoneId windowsZone) {
        Instant innen = LocalDateTime.parse(zeigt).atZone(windowsZone).toInstant();
        Weltuhr.rechner = Clock.fixed(innen, windowsZone);
    }

    /** Ein "Date"-Kopf mit der wahren Zeit 'wahr' (Berliner Ortszeit). */
    private static String kopf(String wahrBerlin) {
        Instant i = LocalDateTime.parse(wahrBerlin).atZone(BERLIN).toInstant();
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(i.atZone(ZoneOffset.UTC));
    }

    /** Zwei Antworten aus dem Netz (erst die zweite, passende zaehlt). */
    private static void zweiAntworten(String wahrBerlin) {
        Weltuhr.datumKopf(kopf(wahrBerlin), 0);
        Weltuhr.datumKopf(kopf(wahrBerlin), 0);
    }

    private static LocalDateTime t(String s) {
        return LocalDateTime.parse(s);
    }

    @Test
    void konradBerlinEingestelltWindowsAufPazifikVonHandGestellt() {
        assertTrue(Zeitzone.setzen("Europe/Berlin"));
        windows("2026-11-19T13:04", PAZIFIK);

        // So sah der Fehler aus: 13:04 bei Konrad, der Bot zeigte 22:04.
        assertEquals(LocalTime.of(22, 4), Zeitzone.uhrzeit());

        zweiAntworten("2026-11-19T13:04");

        assertEquals(-9 * 3_600_000L, Weltuhr.versatzMs());
        assertEquals(LocalTime.of(13, 4), Zeitzone.uhrzeit());
        assertEquals(t("2026-11-19T13:04"), Zeitzone.jetzt());
        assertEquals(java.time.LocalDate.of(2026, 11, 19), Zeitzone.heute());
        assertTrue(Zeitzone.uhrText().contains("13:04"), Zeitzone.uhrText());
        assertTrue(Zeitzone.beschreibung().contains("9:00 h"), Zeitzone.beschreibung());
        assertTrue(Weltuhr.beschreibung().contains("America/Los_Angeles"), Weltuhr.beschreibung());
        assertTrue(Weltuhr.beschreibung().contains("fast by 9:00 h"), Weltuhr.beschreibung());

        // Kriegsfenster: um 13:04 ist keines offen, der naechste Krieg wird um 14:20 aufgestellt (Winter).
        assertEquals(t("2026-11-19T13:04"), Kriegsplan.spielzeit());
        assertNull(Kriegsplan.offenesFenster(Kriegsplan.spielzeit()));
        assertEquals(t("2026-11-19T14:20"), Kriegsplan.naechsteKriegszeit(Kriegsplan.spielzeit()));
        assertEquals(t("2026-11-19T14:20"), Kriegsplan.naechsteKriegszeitHier(Zeitzone.jetzt()));

        // 14:25 bei Konrad: jetzt ist das Fenster offen (vorher dachte der Bot 23:25 -- kein Fenster, nichts gesetzt).
        windows("2026-11-19T14:25", PAZIFIK);
        assertEquals(t("2026-11-19T14:20"), Kriegsplan.offenesFenster(Kriegsplan.spielzeit()));
        assertEquals("14:20", Kriegsplan.zeitText(Kriegsplan.offenesFenster(Kriegsplan.spielzeit()), BERLIN));
        // 15:50: Nachpruefen vor Kriegsbeginn (15:45-16:00)
        windows("2026-11-19T15:50", PAZIFIK);
        assertTrue(Kriegsplan.istKontrolle(Kriegsplan.spielzeit()));
        // 20:30: Abendkrieg
        windows("2026-11-19T20:30", PAZIFIK);
        assertEquals(t("2026-11-19T20:20"), Kriegsplan.offenesFenster(Kriegsplan.spielzeit()));
    }

    /** Konrad 20.11. (Geschwister): "Fertig etwa" in Discord kommt aus der Rechneruhr -- auch das stellt die Weltuhr richtig. */
    @Test
    void zeitpunktDerRechneruhrWirdRichtiggestellt() {
        assertTrue(Zeitzone.setzen("Europe/Berlin"));
        windows("2026-11-20T06:41", PAZIFIK);
        zweiAntworten("2026-11-20T06:41");
        long rechnerJetzt = Weltuhr.rechner.millis();
        // In 50 Minuten fertig: 07:31 bei Konrad (vorher stand 16:31 da).
        assertEquals(t("2026-11-20T07:31"), Zeitzone.ausRechnerMs(rechnerJetzt + 50 * 60_000L));

        // Ein Freund in London: seine eigene Zone, nicht Berliner Zeit.
        assertTrue(Zeitzone.setzen("Europe/London"));
        assertEquals(t("2026-11-20T06:31"), Zeitzone.ausRechnerMs(rechnerJetzt + 50 * 60_000L));
    }

    @Test
    void ohneAntwortAusDemNetzBleibtEsWieBisher() {
        Zeitzone.setzen("Europe/Berlin");
        windows("2026-11-19T13:04", PAZIFIK);
        Weltuhr.datumKopf(kopf("2026-11-19T13:04"), 0);   // nur EINE Antwort -- noch nicht bestaetigt
        assertEquals(0, Weltuhr.versatzMs());
        assertEquals(LocalTime.of(22, 4), Zeitzone.uhrzeit());
        assertTrue(Weltuhr.beschreibung().contains("not checked"), Weltuhr.beschreibung());
    }

    @Test
    void versatzGiltNachNeustartSofortAberNichtNachNeuerWindowsZone() throws Exception {
        Zeitzone.setzen("Europe/Berlin");
        windows("2026-11-19T13:04", PAZIFIK);
        zweiAntworten("2026-11-19T13:04");
        assertTrue(Files.exists(Weltuhr.datei));

        // Neustart, noch keine Antwort aus dem Netz: der gemerkte Versatz gilt sofort.
        Weltuhr.zuruecksetzen();
        windows("2026-11-19T18:00", PAZIFIK);
        assertEquals(LocalTime.of(18, 0), Zeitzone.uhrzeit());

        // Konrad stellt Windows richtig (Zeitzone Berlin, Uhr automatisch): der alte Versatz gilt nicht mehr.
        Weltuhr.zuruecksetzen();
        windows("2026-11-19T18:00", BERLIN);
        assertEquals(0, Weltuhr.versatzMs());
        assertEquals(LocalTime.of(18, 0), Zeitzone.uhrzeit());
    }

    @Test
    void uhrWirdGerichtetDannGiltWiederWindows() {
        Zeitzone.setzen("Europe/Berlin");
        windows("2026-11-19T13:04", PAZIFIK);
        zweiAntworten("2026-11-19T13:04");
        assertEquals(-9 * 3_600_000L, Weltuhr.versatzMs());

        windows("2026-11-19T14:00", BERLIN);   // Windows richtig gestellt, laufender Bot
        zweiAntworten("2026-11-19T14:00");
        assertEquals(0, Weltuhr.versatzMs());
        assertFalse(Files.exists(Weltuhr.datei));
        assertEquals(LocalTime.of(14, 0), Zeitzone.uhrzeit());
        assertEquals("", Weltuhr.hinweis());
    }

    @Test
    void sommerzeitUndDieWocheZwischenDenZeitumstellungen() throws Exception {
        Zeitzone.setzen("Europe/Berlin");
        // Sommer: Berlin UTC+2, Pazifik UTC-7 -> 9 h. Krieg im Sommer um 21:20 Berliner Zeit.
        windows("2026-07-15T21:25", PAZIFIK);
        zweiAntworten("2026-07-15T21:25");
        assertEquals(LocalTime.of(21, 25), Zeitzone.uhrzeit());
        LocalDateTime fenster = Kriegsplan.offenesFenster(Kriegsplan.spielzeit());
        assertEquals("21:20", Kriegsplan.zeitText(fenster, BERLIN));

        // 29.10. (Europa schon Winterzeit, USA noch Sommerzeit) -> 8 h, wie in Konrad' Berichten vom 29.10. (20:36 -> 04:36)
        Files.deleteIfExists(Weltuhr.datei);
        Weltuhr.zuruecksetzen();
        windows("2026-10-29T20:36", PAZIFIK);
        assertEquals(LocalTime.of(4, 36), Zeitzone.uhrzeit());   // so stand es in Bericht UWEZ
        zweiAntworten("2026-10-29T20:36");
        assertEquals(-8 * 3_600_000L, Weltuhr.versatzMs());
        assertEquals(LocalTime.of(20, 36), Zeitzone.uhrzeit());
        assertEquals(t("2026-10-29T20:20"), Kriegsplan.offenesFenster(Kriegsplan.spielzeit()));
    }

    @Test
    void rechnerInAndererZoneMitRichtigerUhr() {
        Zeitzone.setzen("Europe/Berlin");
        for (String z : List.of("Asia/Tokyo", "Europe/London", "America/New_York", "UTC", "Europe/Berlin")) {
            Weltuhr.zuruecksetzen();
            // Uhr stimmt: Windows zeigt die Ortszeit seiner Zone zum wahren Augenblick 13:04 Berlin.
            Instant wahr = t("2026-11-19T13:04").atZone(BERLIN).toInstant();
            Weltuhr.rechner = Clock.fixed(wahr, ZoneId.of(z));
            assertEquals(LocalTime.of(13, 4), Zeitzone.uhrzeit(), z);
            zweiAntworten("2026-11-19T13:04");
            assertEquals(0, Weltuhr.versatzMs(), z);
            assertEquals(LocalTime.of(13, 4), Zeitzone.uhrzeit(), z);
            assertNull(Kriegsplan.offenesFenster(Kriegsplan.spielzeit()), z);
        }
        // Spieler in London stellt London ein: Anzeige 12:04, Krieg trotzdem zum selben Augenblick (13:20 London)
        Zeitzone.setzen("london");
        assertEquals(LocalTime.of(12, 4), Zeitzone.uhrzeit());
        assertEquals(t("2026-11-19T13:20"), Kriegsplan.naechsteKriegszeitHier(Zeitzone.jetzt()));
    }

    @Test
    void eineFalscheAntwortVerstelltNichts() {
        windows("2026-11-19T13:04", BERLIN);
        Weltuhr.datumKopf(kopf("2026-11-21T13:04"), 0);   // Unsinn (zwei Tage vor)
        Weltuhr.datumKopf(kopf("2026-11-19T13:04"), 0);   // passt nicht zur ersten -> nichts
        assertEquals(0, Weltuhr.versatzMs());
        Weltuhr.datumKopf(kopf("2026-11-21T13:04"), 0);
        assertEquals(0, Weltuhr.versatzMs());
        Weltuhr.datumKopf("kein Datum", 0);
        Weltuhr.datumKopf(null, 0);
        Weltuhr.datumKopf("Mon, 01 Jan 2001 00:00:00 GMT", 0);   // vor 2024: geglaubt wird das nicht
        assertEquals(0, Weltuhr.versatzMs());
    }

    @Test
    void kleineAbweichungIstMessrauschen() {
        Zeitzone.setzen("Europe/Berlin");
        Instant wahr = t("2026-11-19T13:04").atZone(BERLIN).toInstant();
        Weltuhr.rechner = Clock.fixed(wahr.plusSeconds(40), BERLIN);   // Windows 40 s vor
        zweiAntworten("2026-11-19T13:04");
        assertEquals(0, Weltuhr.versatzMs());
        assertFalse(Files.exists(Weltuhr.datei));
        assertTrue(Weltuhr.beschreibung().contains("matches"), Weltuhr.beschreibung());
    }

    @Test
    void zwischengespeicherteAntwortZaehltMitIhremAlter() {
        windows("2026-11-19T13:04", PAZIFIK);
        // Date 12:03 Berlin, aber schon 61 min im Zwischenspeicher (Age 3660) -> in Wahrheit 13:04 Berlin
        String alt = kopf("2026-11-19T12:03");
        HttpHeaders h = HttpHeaders.of(Map.of("Date", List.of(alt), "Age", List.of("3660")), (a, b) -> true);
        Weltuhr.datumKopf(h);
        Weltuhr.datumKopf(h);
        assertEquals(-9 * 3_600_000L, Weltuhr.versatzMs());
    }

    @Test
    void stoppUhrzeitWirdAufDieRechneruhrUmgerechnet() {
        windows("2026-11-19T13:04", PAZIFIK);
        zweiAntworten("2026-11-19T13:04");
        Instant stoppUmSechs = t("2026-11-19T18:00").atZone(BERLIN).toInstant();
        // Die Rechneruhr zeigt dann 18:00 "Pazifik" -- genau dann soll der Bot anhalten.
        assertEquals(t("2026-11-19T18:00").atZone(PAZIFIK).toInstant().toEpochMilli(), Weltuhr.rechnerMs(stoppUmSechs));
    }

    @Test
    void kriegsvermerkAusDerZukunftGiltNicht() {
        // Bei vorgehender Uhr am 19.11. um 17:45 (Bot dachte 20.11. 02:45) vermerkt; nach dem Richtigstellen ist es 20:25.
        LocalDateTime jetzt = t("2026-11-19T20:25");
        LocalDateTime fenster = t("2026-11-19T20:20");
        assertFalse(Kriegsplan.erledigtBei(t("2026-11-20T02:45"), fenster, jetzt));
        // normaler Fall: Vermerk fuer genau dieses Fenster oder die Kontrolle danach
        assertTrue(Kriegsplan.erledigtBei(t("2026-11-19T20:20"), fenster, jetzt));
        assertTrue(Kriegsplan.erledigtBei(t("2026-11-19T21:45"), fenster, t("2026-11-19T21:50")));
        assertFalse(Kriegsplan.erledigtBei(t("2026-11-19T14:20"), fenster, jetzt));
        assertFalse(Kriegsplan.erledigtBei(null, fenster, jetzt));
    }

    @Test
    void schreibweisenFuerBerlin() {
        Instant winter = Instant.parse("2026-11-19T12:00:00Z"), sommer = Instant.parse("2026-07-15T12:00:00Z");
        for (String e : List.of("Europe/Berlin", "europe/berlin", " Europe/Berlin ", "Europe / Berlin", "EUROPE/BERLIN",
                "Berlin", "berlin", "Deutschland", "germany", "MEZ", "MESZ", "CEST", "CET", "Europe/Berlin (UTC+01:00)",
                "Europe/Berlin, jetzt 13:04 (UTC+01:00)", "Europa/Berlin", "Berlin, Germany")) {
            ZoneId z = Zeitzone.verstehen(e).orElse(null);
            assertTrue(z != null, e);
            assertEquals(BERLIN.getRules().getOffset(winter), z.getRules().getOffset(winter), e);
            assertEquals(BERLIN.getRules().getOffset(sommer), z.getRules().getOffset(sommer), e);
        }
        assertTrue(Zeitzone.verstehen("Mondzeit").isEmpty());
        assertTrue(Zeitzone.verstehen("Europe/Mondzeit").isEmpty());
        assertEquals(ZoneId.of("America/New_York"), Zeitzone.verstehen("New York").orElseThrow());
        assertEquals(ZoneId.of("America/Los_Angeles"), Zeitzone.verstehen("Los Angeles").orElseThrow());
    }
}
