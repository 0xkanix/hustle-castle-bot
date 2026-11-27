package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hintergrund-Modus (Autor 20.11.): Umschalten zwischen echter Maus und Hintergrund -- nie mitten in einem Druck. */
class WahlSteuerungTest {

    static final class Merker implements Steuerung {
        final String name;
        final List<String> log;
        final boolean pc;

        Merker(String name, List<String> log, boolean pc) {
            this.name = name;
            this.log = log;
            this.pc = pc;
        }

        @Override public void bewegen(int x, int y) { log.add(name + " bewegen " + x + "," + y); }
        @Override public void druecken() { log.add(name + " druecken"); }
        @Override public void loslassen() { log.add(name + " loslassen"); }
        @Override public Point zeiger() { return new Point(); }
        @Override public BufferedImage foto(Rectangle r) { log.add(name + " foto"); return null; }
        @Override public boolean istPcMaus() { return pc; }
    }

    @Test
    void schalterAusIstWieBisher() {
        List<String> log = new ArrayList<>();
        WahlSteuerung w = new WahlSteuerung(new Merker("maus", log, true), new Merker("hinten", log, false), () -> false);
        w.bewegen(1, 2);
        w.druecken();
        w.loslassen();
        w.foto(new Rectangle(0, 0, 1, 1));
        assertEquals(List.of("maus bewegen 1,2", "maus druecken", "maus loslassen", "maus foto"), log);
        assertTrue(w.istPcMaus());
    }

    @Test
    void schalterAnGehtInDenHintergrund() {
        List<String> log = new ArrayList<>();
        WahlSteuerung w = new WahlSteuerung(new Merker("maus", log, true), new Merker("hinten", log, false), () -> true);
        w.druecken();
        w.loslassen();
        w.foto(new Rectangle(0, 0, 1, 1));
        assertEquals(List.of("hinten druecken", "hinten loslassen", "hinten foto"), log);
        assertFalse(w.istPcMaus(), "keine Mauswache im Hintergrund");
    }

    @Test
    void einBegonnenerDruckEndetMitDerselbenSteuerung() {
        List<String> log = new ArrayList<>();
        boolean[] an = {false};
        WahlSteuerung w = new WahlSteuerung(new Merker("maus", log, true), new Merker("hinten", log, false), () -> an[0]);
        w.druecken();
        an[0] = true;          // mitten im Zug umgeschaltet
        w.bewegen(5, 5);
        w.loslassen();
        w.druecken();          // erst der naechste Druck geht in den Hintergrund
        assertEquals(List.of("maus druecken", "maus bewegen 5,5", "maus loslassen", "hinten druecken"), log);
    }
}
