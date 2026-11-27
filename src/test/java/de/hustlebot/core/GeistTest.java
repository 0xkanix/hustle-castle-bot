package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Hintergrund-Modus (Autor 20.11.): die reinen Rechnungen des Geists und die Lage der Touch-Strukturen. */
class GeistTest {

    @Test
    void geistStilSetztUndNimmtDieRichtigenBits() {
        long normal = 0x00040100L;   // z. B. WS_EX_APPWINDOW | WS_EX_WINDOWEDGE
        long geist = Geist.geistStil(normal, true);
        assertEquals(normal | 0x80000L | 0x20L | 0x08000000L, geist);
        assertTrue(Geist.istGeistStil(geist));
        long klickbar = Geist.geistStil(geist, false);
        assertEquals(0, klickbar & 0x20L, "waehrend des Tipps ohne WS_EX_TRANSPARENT");
        assertTrue(Geist.istGeistStil(klickbar), "auch ohne TRANSPARENT noch als Geist erkannt");
        assertEquals(normal, Geist.normalStil(geist), "aufheben stellt den alten Stil her");
        assertFalse(Geist.istGeistStil(normal));
        assertFalse(Geist.istGeistStil(normal | 0x80000L), "nur LAYERED ist noch kein Geist (z. B. ein fremdes Fenster)");
    }

    @Test
    void ruheplatzLiegtLinksNebenAllenBildschirmen() {
        Dimension fenster = new Dimension(1175, 809);
        Point eins = Geist.ruheplatz(new Rectangle(0, 0, 1920, 1080), fenster);
        assertTrue(eins.x + fenster.width <= -100, "rechte Kante weit links vom Bildschirm: " + eins);
        assertEquals(0, eins.y);
        // zweiter Bildschirm links daneben: dann noch weiter links
        Point zwei = Geist.ruheplatz(new Rectangle(-1920, -200, 3840, 1280), fenster);
        assertTrue(zwei.x + fenster.width < -1920);
        assertEquals(-200, zwei.y);
    }

    @Test
    void touchStrukturenHabenDieWindowsLage() {
        Assumptions.assumeTrue(com.sun.jna.Native.POINTER_SIZE == 8, "nur 64 Bit");
        Geist.TouchInfo t = new Geist.TouchInfo();
        assertEquals(96, new Geist.ZeigerInfo().size(), "POINTER_INFO");
        assertEquals(144, t.size(), "POINTER_TOUCH_INFO");
        assertEquals(96, t.versatz("touchFlags"));
        assertEquals(104, t.versatz("rcContact"));
        assertEquals(140, t.versatz("pressure"));
    }

    @Test
    void touchFuellenWieInAutorsProbe() {
        Geist.TouchInfo t = new Geist.TouchInfo();
        t.fuellen(300, 200, Geist.RUNTER);
        assertEquals(2, t.pointerInfo.pointerType, "PT_TOUCH");
        assertEquals(0x10000 | 0x2 | 0x4, t.pointerInfo.pointerFlags, "DOWN | INRANGE | INCONTACT");
        assertEquals(300, t.pointerInfo.ptPixelLocation.x);
        assertEquals(200, t.pointerInfo.ptPixelLocation.y);
        assertEquals(0x7, t.touchMask);
        assertEquals(32000, t.pressure);
        assertEquals(298, t.rcContact.left);
        assertEquals(202, t.rcContact.bottom);
        assertEquals(0x40000, Geist.HOCH);
        assertEquals(0x20000 | 0x2 | 0x4, Geist.ZIEHEN);
        assertEquals(0x3, Geist.TOUCH_FEEDBACK_NONE, "Autor: keine Touch-Kreise");
    }
}
