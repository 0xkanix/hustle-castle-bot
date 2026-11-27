package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * VM 21.11.: ein DXGI-Foto kam nie zurueck (virtuelle Grafikkarte abgestuerzt) und die Hauptschleife stand fuer immer.
 * Mit dem Fristfaden wartet der Aufrufer hoechstens die Frist, und ein haengender Aufruf wird nicht noch einmal angefasst.
 */
class FristfadenTest {

    @Test
    void normalerAufrufLiefertSeinErgebnis() {
        Fristfaden f = new Fristfaden("test-frist-normal", 2000);
        assertEquals("bild", f.rufe(() -> "bild"));
        assertFalse(f.haengt());
        assertFalse(f.haengerMelden());
    }

    @Test
    void haengenderAufrufHaeltDenAufruferNichtAuf() throws Exception {
        Fristfaden f = new Fristfaden("test-frist-haenger", 200);
        CountDownLatch nieFertig = new CountDownLatch(1);
        AtomicInteger ausgefuehrt = new AtomicInteger();

        long t0 = System.nanoTime();
        assertNull(f.rufe(() -> {
            ausgefuehrt.incrementAndGet();
            nieFertig.await();     // wie Map() bei toter Grafikkarte
            return "nie";
        }));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 150 && ms < 2000, "Frist 200 ms, gewartet " + ms + " ms");
        assertTrue(f.haengt());

        // solange er haengt: sofort null, der haengende Code wird nicht noch einmal angefasst
        t0 = System.nanoTime();
        assertNull(f.rufe(() -> {
            ausgefuehrt.incrementAndGet();
            return "zweiter";
        }));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 100, "zweiter Aufruf muss sofort zurueckkommen");
        assertEquals(1, ausgefuehrt.get());

        // genau eine Meldung je Haenger
        assertTrue(f.haengerMelden());
        assertFalse(f.haengerMelden());

        // kommt der Aufruf doch noch zurueck, geht es normal weiter
        nieFertig.countDown();
        long bis = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (f.haengt() && System.nanoTime() < bis) Thread.sleep(10);
        assertFalse(f.haengt());
        assertEquals("wieder da", f.rufe(() -> "wieder da"));
        assertTrue(f.freiMelden());
        assertFalse(f.freiMelden());
    }

    @Test
    void aufrufHinterEinemHaengendenWirdVerworfen() throws Exception {
        Fristfaden f = new Fristfaden("test-frist-schlange", 300);
        CountDownLatch los = new CountDownLatch(1);
        AtomicInteger spaeter = new AtomicInteger();
        // erster Aufruf haengt auf einem anderen Faden (wie der Arena-Beobachter neben der Hauptschleife)
        Thread anderer = new Thread(() -> f.rufe(() -> {
            los.await();
            return "a";
        }));
        anderer.start();
        Thread.sleep(50);
        // zweiter wartet hinter ihm, laeuft in seine Frist und darf danach nicht mehr ausgefuehrt werden
        assertNull(f.rufe(() -> spaeter.incrementAndGet()));
        los.countDown();
        anderer.join(2000);
        Thread.sleep(100);
        assertEquals(0, spaeter.get());
    }

    @Test
    void fehlerImAufrufKommtBeimAufruferAn() {
        Fristfaden f = new Fristfaden("test-frist-fehler", 2000);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> f.rufe(() -> {
            throw new IllegalArgumentException("kaputt");
        }));
        assertEquals("kaputt", e.getMessage());
        assertFalse(f.haengt());
    }

    @Test
    void dxgiFristLiegtWeitUnterDemHaengerWaechter() {
        // der Waechter in DiscordBridge meldet nach 3 Minuten; die DXGI-Frist muss deutlich frueher greifen,
        // aber lang genug fuer das erste Anlegen (Geraet + Duplikat) sein
        assertTrue(RobotSteuerung.DXGI_FRIST_MS >= 2000 && RobotSteuerung.DXGI_FRIST_MS <= 30_000);
    }
}
