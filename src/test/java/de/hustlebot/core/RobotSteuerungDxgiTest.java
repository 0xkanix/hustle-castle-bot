package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** DXGI-Fotos (foto-dxgi.txt): nach 3 Fehlern hintereinander eine Minute Pause, dann neuer Versuch. */
class RobotSteuerungDxgiTest {

    @Test
    void dreiFehlerHintereinanderGebenEineMinutePause() {
        RobotSteuerung.Aussetzer a = new RobotSteuerung.Aussetzer(3, 60_000);
        long t = 1_000_000;
        assertTrue(a.darf(t));
        assertFalse(a.fehler(t));
        assertFalse(a.fehler(t + 10));
        assertTrue(a.fehler(t + 20));          // der dritte startet die Pause
        assertFalse(a.darf(t + 20));
        assertFalse(a.darf(t + 20 + 59_999));
        assertTrue(a.darf(t + 20 + 60_000));   // danach neuer Versuch
    }

    @Test
    void erfolgSetztDieZaehlungZurueck() {
        RobotSteuerung.Aussetzer a = new RobotSteuerung.Aussetzer(3, 60_000);
        assertFalse(a.fehler(0));
        assertFalse(a.fehler(1));
        a.erfolg();
        assertFalse(a.fehler(2));
        assertFalse(a.fehler(3));
        assertTrue(a.darf(3));
        assertTrue(a.fehler(4));
        assertFalse(a.darf(5));
    }

    @Test
    void nachDerPauseZaehltEsNeu() {
        RobotSteuerung.Aussetzer a = new RobotSteuerung.Aussetzer(3, 60_000);
        a.fehler(0);
        a.fehler(0);
        assertTrue(a.fehler(0));
        assertTrue(a.darf(60_000));
        assertFalse(a.fehler(60_000));         // wieder von vorn: erst der dritte Fehler pausiert
        assertFalse(a.fehler(60_001));
        assertTrue(a.fehler(60_002));
        assertFalse(a.darf(60_003));
    }

    @Test
    void ganzAmAnfangDarfEs() {
        RobotSteuerung.Aussetzer a = new RobotSteuerung.Aussetzer(3, 60_000);
        assertTrue(a.darf(0));
        assertTrue(a.darf(Long.MIN_VALUE + 1));
    }
}
