package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Freundeplan: wann "Freunden senden" dran ist (Autor, 05.10.; seit 08.10. zwei Fenster am
 * Tag, 02:00 und 12:00). Nur die reine Regel -- ohne Uhr, ohne Schalter- und Vermerk-Datei.
 */
class FreundeplanTest {

    private static final LocalDate HEUTE = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime F2 = HEUTE.atTime(2, 0);
    private static final LocalDateTime F12 = HEUTE.atTime(12, 0);
    private static final LocalDateTime GESTERN12 = HEUTE.minusDays(1).atTime(12, 0);
    private static final LocalDateTime MITTAG = HEUTE.atTime(12, 5);

    @Test
    void fenster_zweiAmTag() {
        assertEquals(GESTERN12, Freundeplan.fenster(HEUTE.atTime(1, 59)));
        assertEquals(F2, Freundeplan.fenster(HEUTE.atTime(2, 0)));
        assertEquals(F2, Freundeplan.fenster(HEUTE.atTime(11, 59)));
        assertEquals(F12, Freundeplan.fenster(HEUTE.atTime(12, 0)));
        assertEquals(F12, Freundeplan.fenster(HEUTE.atTime(23, 59)));
    }

    @Test
    void pruefen_ausgeschaltetNieFaellig() {
        assertEquals(Optional.empty(), Freundeplan.pruefen(false, MITTAG, null, null, Long.MAX_VALUE, 0));
    }

    @Test
    void pruefen_fensterErledigtNichtFaellig() {
        assertEquals(Optional.empty(), Freundeplan.pruefen(true, MITTAG, F12, null, Long.MAX_VALUE, 0));
    }

    @Test
    void pruefen_um2ErledigtIstUm12WiederFaellig() {
        assertEquals(Optional.of(F12), Freundeplan.pruefen(true, MITTAG, F2, null, Long.MAX_VALUE, 0));
    }

    @Test
    void pruefen_um2UhrGleichFaellig() {
        assertEquals(Optional.of(F2), Freundeplan.pruefen(true, HEUTE.atTime(2, 0), GESTERN12, null, 0, 0));
    }

    @Test
    void pruefen_vormittagsNachholenImZweiUhrFenster() {
        assertEquals(Optional.of(F2), Freundeplan.pruefen(true, HEUTE.atTime(9, 30), GESTERN12, null, Long.MAX_VALUE, 0));
    }

    @Test
    void pruefen_eben_angestossenWartet20Minuten() {
        long fuenfMin = 5 * 60_000L, fuenfundzwanzigMin = 25 * 60_000L;
        assertEquals(Optional.empty(), Freundeplan.pruefen(true, MITTAG, F2, F12, fuenfMin, 1));
        assertEquals(Optional.of(F12), Freundeplan.pruefen(true, MITTAG, F2, F12, fuenfundzwanzigMin, 1));
    }

    @Test
    void pruefen_nachDreiVersuchenFuerDiesesFensterSchluss() {
        assertEquals(Optional.empty(),
                Freundeplan.pruefen(true, MITTAG, F2, F12, Long.MAX_VALUE, Freundeplan.MAX_VERSUCHE));
    }

    @Test
    void pruefen_versucheDesVorigenFenstersZaehlenNicht() {
        assertEquals(Optional.of(F12),
                Freundeplan.pruefen(true, MITTAG, null, F2, 0, Freundeplan.MAX_VERSUCHE));
    }

    @Test
    void kurzNachReset_nurVon2Bis2Uhr10() {
        assertTrue(Freundeplan.kurzNachReset(HEUTE.atTime(2, 0)));
        assertTrue(Freundeplan.kurzNachReset(HEUTE.atTime(2, 5)));
        assertFalse(Freundeplan.kurzNachReset(HEUTE.atTime(2, 10)));
        assertFalse(Freundeplan.kurzNachReset(HEUTE.atTime(1, 59)));
        assertFalse(Freundeplan.kurzNachReset(MITTAG));
    }
}
