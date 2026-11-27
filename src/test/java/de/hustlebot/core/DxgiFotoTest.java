package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

/** DXGI-Fotos (foto-dxgi.txt): alles, was ohne Bildschirm pruefbar ist. */
class DxgiFotoTest {

    @Test
    void bereichAufDemHauptmonitorWirdUmgerechnet() {
        Rectangle monitor = new Rectangle(0, 0, 2560, 1440);
        // Spiel wie bei Autor: Inhaltsbereich ab 763/31, 1159x770 (reicht bis x=1922 -- breiter als ein 1920er Monitor)
        assertEquals(new Rectangle(763, 31, 1159, 770), DxgiFoto.imMonitor(new Rectangle(763, 31, 1159, 770), monitor));
        assertEquals(new Rectangle(0, 0, 2560, 1440), DxgiFoto.imMonitor(new Rectangle(0, 0, 2560, 1440), monitor));
        // 2 Punkte ueber den rechten Rand (wie in der VM 21.11.): zugeschnitten statt null
        assertEquals(new Rectangle(763, 31, 1157, 770), DxgiFoto.imMonitor(new Rectangle(763, 31, 1159, 770), new Rectangle(0, 0, 1920, 1080)));
    }

    @Test
    void ueberstandBleibtSchwarz() {
        // Monitor 4x2 (BGR), Bereich ragt 1 Punkt rechts hinaus: Ziel 3 breit, letzte Spalte bleibt 0
        byte[] monitorBgr = new byte[4 * 2 * 3];
        for (int i = 0; i < monitorBgr.length; i++) monitorBgr[i] = (byte) (i + 1);
        Rectangle rel = DxgiFoto.imMonitor(new Rectangle(2, 0, 3, 2), new Rectangle(0, 0, 4, 2));
        assertEquals(new Rectangle(2, 0, 2, 2), rel);
        byte[] ziel = new byte[3 * 2 * 3];
        DxgiFoto.ausschneidenMitRand(monitorBgr, 4, rel, ziel, 3, 0, 0);
        assertEquals(monitorBgr[6], ziel[0]);          // Punkt (2,0)
        assertEquals(monitorBgr[9], ziel[3]);          // Punkt (3,0)
        assertEquals(0, ziel[6]);                      // ueber dem Rand: schwarz
        assertEquals(monitorBgr[18], ziel[9]);         // Punkt (2,1)
    }

    @Test
    void bereichAufEinemZweitenMonitorWirdUmgerechnet() {
        Rectangle links = DxgiFoto.rechteck(-1280, -200, 0, 824);   // links vom Hauptmonitor, hoeher gesetzt
        assertEquals(new Rectangle(-1280, -200, 1280, 1024), links);
        assertEquals(new Rectangle(280, 300, 300, 150), DxgiFoto.imMonitor(new Rectangle(-1000, 100, 300, 150), links));
        Rectangle rechts = DxgiFoto.rechteck(1920, 0, 3840, 1080);
        assertEquals(new Rectangle(10, 20, 30, 40), DxgiFoto.imMonitor(new Rectangle(1930, 20, 30, 40), rechts));
    }

    @Test
    void bereichNichtGanzAufDemMonitorGibtNull() {
        Rectangle monitor = new Rectangle(0, 0, 1920, 1080);
        assertNull(DxgiFoto.imMonitor(new Rectangle(1800, 31, 200, 100), monitor));   // rechts 80 Punkte ueber den Rand
        assertNull(DxgiFoto.imMonitor(new Rectangle(-20, 0, 100, 100), monitor));     // links 20 Punkte raus
        assertEquals(new Rectangle(0, 0, 95, 100), DxgiFoto.imMonitor(new Rectangle(-5, 0, 100, 100), monitor));   // 5 raus: zugeschnitten
        assertEquals(new Rectangle(0, 1000, 100, 80), DxgiFoto.imMonitor(new Rectangle(0, 1000, 100, 81), monitor)); // unten 1 zu viel
        assertNull(DxgiFoto.imMonitor(new Rectangle(0, 0, 0, 10), monitor));          // leer
        assertNull(DxgiFoto.imMonitor(null, monitor));
        assertNull(DxgiFoto.imMonitor(new Rectangle(0, 0, 10, 10), null));
        assertNull(DxgiFoto.imMonitor(new Rectangle(0, 0, 10, 10), new Rectangle(0, 0, 0, 0)));
    }

    @Test
    void bgraWirdZuBgr() {
        byte[] bgra = {1, 2, 3, (byte) 255, 4, 5, 6, (byte) 255, (byte) 200, (byte) 201, (byte) 202, 0};
        byte[] bgr = new byte[2 + 9];
        DxgiFoto.bgraZuBgr(bgra, 0, bgr, 2, 3);
        assertArrayEquals(new byte[]{0, 0, 1, 2, 3, 4, 5, 6, (byte) 200, (byte) 201, (byte) 202}, bgr);
        // mit Versatz in der Quelle: nur der zweite Bildpunkt
        byte[] einer = new byte[3];
        DxgiFoto.bgraZuBgr(bgra, 4, einer, 0, 1);
        assertArrayEquals(new byte[]{4, 5, 6}, einer);
    }

    @Test
    void ausschneidenNimmtDieRichtigenZeilen() {
        int breite = 4, hoehe = 3;
        byte[] monitor = new byte[breite * hoehe * 3];
        for (int i = 0; i < monitor.length; i++) monitor[i] = (byte) i;
        byte[] ziel = new byte[2 * 2 * 3];
        DxgiFoto.ausschneiden(monitor, breite, new Rectangle(1, 1, 2, 2), ziel);
        // Zeile 1 ab Punkt 1: Bytes 15..20, Zeile 2 ab Punkt 1: Bytes 27..32
        assertArrayEquals(new byte[]{15, 16, 17, 18, 19, 20, 27, 28, 29, 30, 31, 32}, ziel);
    }

    @Test
    void merkbereichUmfasstAlleVerlangtenBereiche() {
        DxgiFoto.Merkbereich m = new DxgiFoto.Merkbereich();
        Rectangle spiel = new Rectangle(763, 31, 1159, 770);
        assertEquals(spiel, m.merken(spiel, 1000));
        // ein Kasten im Spiel aendert nichts
        assertEquals(spiel, m.merken(new Rectangle(900, 300, 300, 150), 2000));
        // ein Bereich daneben vergroessert die Huelle
        assertEquals(new Rectangle(700, 31, 1222, 770), m.merken(new Rectangle(700, 100, 10, 10), 3000));
    }

    @Test
    void merkbereichVergisstAlteBereicheNachZweiAbschnitten() {
        DxgiFoto.Merkbereich m = new DxgiFoto.Merkbereich();
        Rectangle gross = new Rectangle(0, 0, 2560, 1440), spiel = new Rectangle(763, 31, 1159, 770);
        long t = 5000;
        m.merken(gross, t);                                   // einmal der ganze Bildschirm
        m.merken(spiel, t + 1000);
        // naechster Abschnitt: der grosse Bereich ist noch "vorher" dabei
        assertEquals(gross, m.merken(spiel, t + DxgiFoto.Merkbereich.ABSCHNITT_MS));
        // uebernaechster Abschnitt: nur noch das Spiel
        assertEquals(spiel, m.merken(spiel, t + 2 * DxgiFoto.Merkbereich.ABSCHNITT_MS));
        m.vergessen();
        assertNull(m.kasten());
        Rectangle kasten = new Rectangle(10, 10, 5, 5);
        assertEquals(kasten, m.merken(kasten, 0));
    }

    @Test
    void leererBereichIstEinFehlerOhneWindowsAufruf() {
        DxgiFoto d = new DxgiFoto();
        DxgiFoto.Aufnahme a = d.aufnehmen(new Rectangle(0, 0, 0, 0));
        assertNull(a.bild());
        assertTrue(a.fehler());
        assertNull(d.aufnehmen(null).bild());
        assertEquals(2, d.zaehler().fehler());
        assertEquals(2, d.zaehler().fotos());
        assertFalse(d.letzterFehler().isEmpty());
        d.close();   // nichts angelegt -- darf nicht stolpern
    }

    @Test
    void minutenZeileErstNachEinerMinute() {
        DxgiFoto d = new DxgiFoto();
        assertNull(d.minutenZeile(1_000));
        d.aufnehmen(null);
        assertNull(d.minutenZeile(30_000));
        String z = d.minutenZeile(61_000);
        assertTrue(z != null && z.startsWith("DXGI: ") && z.contains("1 Fehler"), z);
        assertNull(d.minutenZeile(62_000));
    }

    @Test
    void hresultAlsText() {
        assertEquals("0x887A0027", DxgiFoto.hex(DxgiFoto.DXGI_ERROR_WAIT_TIMEOUT));
        assertEquals("0x887A0026", DxgiFoto.hex(DxgiFoto.DXGI_ERROR_ACCESS_LOST));
    }
}
