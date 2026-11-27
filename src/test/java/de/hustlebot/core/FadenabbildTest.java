package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

/** VM 21.11.: der Haenger-Waechter schreibt selbst ins Protokoll, wo die Hauptschleife steckt (kein jstack in der Laufzeit). */
class FadenabbildTest {

    private static void steckeHierFest(CountDownLatch los) throws InterruptedException {
        los.await();
    }

    @Test
    void zeigtDieZeileInDerEinFadenSteckt() throws Exception {
        CountDownLatch los = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                steckeHierFest(los);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-abbild-faden");
        t.start();
        try {
            String text = "";
            for (int i = 0; i < 100 && !text.contains("steckeHierFest"); i++) {
                Thread.sleep(10);
                text = Fadenabbild.von("test-abbild-faden", 40);
            }
            assertTrue(text.startsWith("Faden \"test-abbild-faden\" (WAITING)"), text);
            assertTrue(text.contains("steckeHierFest"), text);
        } finally {
            los.countDown();
            t.join(2000);
        }
    }

    @Test
    void unbekannterFadenGibtLeerenText() {
        assertEquals("", Fadenabbild.von("gibt-es-nicht-xyz", 10));
    }

    @Test
    void kuerztLangeStapel() {
        StackTraceElement[] stapel = new StackTraceElement[5];
        for (int i = 0; i < 5; i++) stapel[i] = new StackTraceElement("K", "m" + i, "K.java", i + 1);
        String text = Fadenabbild.text("bot-worker", Thread.State.RUNNABLE, stapel, 2);
        assertEquals("Faden \"bot-worker\" (RUNNABLE):\n    at K.m0(K.java:1)\n    at K.m1(K.java:2)\n    ... (+3)\n", text);
    }
}
