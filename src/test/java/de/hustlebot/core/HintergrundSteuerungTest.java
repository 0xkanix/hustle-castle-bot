package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hintergrund-Modus (Autor 20.11.): der Ablauf der HintergrundSteuerung mit einem nachgebauten Windows. Geprueft wird
 * vor allem die Sicherheit -- nie ein Touch, solange die Maus belegt ist oder nicht das Spiel unter der Stelle liegt;
 * ein Tipp ist EIN Moment (runter + hoch an derselben Stelle); hoch immer am letzten Punkt; nie ein Finger liegen lassen.
 */
class HintergrundSteuerungTest {

    /** Nachgebautes Windows: schreibt jeden Schritt mit, die Uhr laeuft nur beim Warten. */
    static final class Nachbau implements HintergrundSteuerung.Fenster {
        long uhr = 1_000;
        final List<String> log = new ArrayList<>();
        Point zeiger = new Point(500, 400);
        int belegtNochMal = 0;          // so oft meldet mausBelegt() noch "belegt"
        final java.util.ArrayDeque<Boolean> belegtFolge = new java.util.ArrayDeque<>();   // Antworten der Reihe nach
        boolean frei = true;
        int freiNein = 0;               // so oft meldet frei() noch "nein"
        boolean da = true;
        long bewegungen = 0;
        Point echteStelle = new Point(0, 0);
        Point zeigerNachAusrichten;     // so steht der Zeiger nach dem naechsten Ausrichten (Autor bewegt)
        Point randLinks;                // dieser Punkt liegt nicht auf dem Schirm
        int[] bild;
        int bildAufrufe = 0;
        Runnable beimBeruehren;

        @Override public boolean da() { return da; }
        @Override public Dimension groesse() { return new Dimension(4, 3); }
        @Override public int[] bild(int b, int h) { bildAufrufe++; return bild; }
        @Override public Point zeiger() { return new Point(zeiger); }
        @Override public void zeigerSetzen(Point p) { log.add("zeiger " + p.x + "," + p.y); zeiger = new Point(p); }
        @Override public boolean mausBelegt() {
            Boolean f = belegtFolge.poll();
            if (f != null) {
                if (f) log.add("belegt");
                return f;
            }
            if (belegtNochMal > 0) { belegtNochMal--; log.add("belegt"); return true; }
            return false;
        }
        @Override public boolean aufSchirm(Point p) { return !p.equals(randLinks); }
        @Override public void ausrichten(int fx, int fy, Point m) {
            log.add("ausrichten " + fx + "," + fy + "@" + m.x + "," + m.y);
            if (zeigerNachAusrichten != null) { zeiger = zeigerNachAusrichten; zeigerNachAusrichten = null; }
        }
        @Override public void takt() { uhr += 20; log.add("takt"); }
        @Override public boolean frei(Point m) {
            if (freiNein > 0) { freiNein--; return false; }
            return frei;
        }
        @Override public void ruhe() { log.add("ruhe"); }
        @Override public boolean beruehren(Point m, int flags) {
            String art = flags == Geist.RUNTER ? "RUNTER" : flags == Geist.HOCH ? "HOCH" : flags == Geist.ZIEHEN ? "ZIEHEN" : "?" + flags;
            log.add(art + " " + m.x + "," + m.y);
            if (beimBeruehren != null) beimBeruehren.run();
            return true;
        }
        @Override public long echteBewegungen() { return bewegungen; }
        @Override public Point echteStelle() { return new Point(echteStelle); }
        @Override public long ruhtSeit() { return 100; }
        @Override public boolean warten(long ms) { uhr += ms; return true; }

        List<String> touches() {
            List<String> t = new ArrayList<>();
            for (String s : log) if (s.startsWith("RUNTER") || s.startsWith("HOCH") || s.startsWith("ZIEHEN")) t.add(s);
            return t;
        }
    }

    private static HintergrundSteuerung steuerung(Nachbau n) {
        return new HintergrundSteuerung(n, () -> n.uhr, false);
    }

    @Test
    void kurzerTippIstEinMomentUnterDemZeiger() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.bewegen(100, 200);
        s.druecken();
        assertTrue(n.log.isEmpty(), "druecken() allein tut noch nichts");
        n.uhr += 25;   // Bot haelt 25 ms
        s.loslassen();
        // Tippstelle = Zeiger +/- 1, Fenster so geschoben, dass Fensterpunkt 100,200 dort liegt
        assertEquals(List.of("ausrichten 100,200@499,400", "takt", "RUNTER 499,400", "HOCH 499,400", "zeiger 500,400", "ruhe"), n.log);
    }

    @Test
    void seiteWechseltJedenTipp() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        for (int i = 0; i < 2; i++) {
            s.druecken();
            s.loslassen();
        }
        assertEquals(List.of("RUNTER 499,400", "HOCH 499,400", "RUNTER 501,400", "HOCH 501,400"), n.touches());
    }

    @Test
    void amBildschirmrandDieAndereSeite() {
        Nachbau n = new Nachbau();
        n.zeiger = new Point(0, 50);
        n.randLinks = new Point(-1, 50);
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertEquals(List.of("RUNTER 1,50", "HOCH 1,50"), n.touches());
    }

    @Test
    void nieTippenSolangeDieMausBelegtIst() {
        Nachbau n = new Nachbau();
        n.belegtNochMal = 500;   // 10 s lang gedrueckt -- keine Zeitgrenze, es wird gewartet
        List<String> beimTouch = new ArrayList<>();
        n.beimBeruehren = () -> beimTouch.add("belegt noch " + n.belegtNochMal);
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertEquals(List.of("RUNTER 499,400", "HOCH 499,400"), n.touches());
        assertEquals(List.of("belegt noch 0", "belegt noch 0"), beimTouch);
        assertTrue(n.uhr >= 1_000 + 500 * HintergrundSteuerung.BELEGT_TAKT_MS);
    }

    @Test
    void mausDirektVorDemTouchBelegtDannNeuerVersuch() {
        Nachbau n = new Nachbau();
        // 1. Blick (Ruhe abwarten): frei, 2. Blick (direkt vor dem Touch): belegt -> kein Touch, neuer Versuch
        n.belegtFolge.add(false);
        n.belegtFolge.add(true);
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        int belegt = n.log.indexOf("belegt");
        int runter = n.log.indexOf("RUNTER 501,400");
        assertTrue(belegt >= 0 && runter > belegt, n.log.toString());
        assertEquals(List.of("RUNTER 501,400", "HOCH 501,400"), n.touches(), "erst der zweite Versuch tippt");
        assertTrue(n.log.subList(belegt, runter).contains("ruhe"), "dazwischen wieder in Ruhe");
    }

    @Test
    void nichtDasSpielUnterDemZeigerHeisstKeinTouch() {
        Nachbau n = new Nachbau();
        n.frei = false;
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertTrue(n.touches().isEmpty(), "nie in Autors Fenster tippen");
        assertEquals("ruhe", n.log.get(n.log.size() - 1));
        long ausgerichtet = n.log.stream().filter(e -> e.startsWith("ausrichten")).count();
        assertEquals(HintergrundSteuerung.VERSUCHE, ausgerichtet);
    }

    @Test
    void kurzVerdecktDannKlapptEsBeimNaechstenVersuch() {
        Nachbau n = new Nachbau();
        n.freiNein = 1;
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertEquals(1, n.touches().stream().filter(e -> e.startsWith("RUNTER")).count());
    }

    @Test
    void zeigerBewegtNachDemAusrichtenDannNeuAusrichten() {
        Nachbau n = new Nachbau();
        n.zeigerNachAusrichten = new Point(520, 400);   // Autor schiebt die Maus 20 Punkte weiter
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        List<String> ausrichten = n.log.stream().filter(e -> e.startsWith("ausrichten")).toList();
        assertEquals(List.of("ausrichten 0,0@499,400", "ausrichten 0,0@521,400"), ausrichten);
        assertEquals(List.of("RUNTER 521,400", "HOCH 521,400"), n.touches());
    }

    @Test
    void zeigerFolgtAutorsEchterBewegung() {
        Nachbau n = new Nachbau();
        n.beimBeruehren = () -> {   // Autor bewegt die Maus genau waehrend des Tipps
            n.bewegungen++;
            n.echteStelle = new Point(640, 410);
        };
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertTrue(n.log.contains("zeiger 640,410"));
        assertFalse(n.log.contains("zeiger 500,400"));
    }

    @Test
    void langerKlickWirdWirklichGehalten() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.bewegen(30, 40);
        s.druecken();
        n.uhr += 120;   // klickLang 120 ms
        long vor = n.uhr;
        s.loslassen();
        assertEquals(List.of("RUNTER 499,400", "HOCH 499,400"), n.touches());
        assertTrue(n.uhr - vor >= 120, "so lange gehalten wie der Bot");
    }

    @Test
    void zugRunterSchritteUndHochAmLetztenPunkt() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.bewegen(10, 10);
        s.druecken();
        n.uhr += 100;
        s.bewegen(20, 10);
        n.zeiger = new Point(600, 300);   // Autor bewegt die Maus mitten im Zug
        s.bewegen(30, 10);
        s.loslassen();
        assertEquals(List.of("RUNTER 499,400", "ZIEHEN 501,400", "ZIEHEN 599,300", "HOCH 599,300"), n.touches());
        assertTrue(n.log.contains("ausrichten 20,10@501,400"));
        assertTrue(n.log.contains("ausrichten 30,10@599,300"));
    }

    @Test
    void zugBrichtAbWennDasSpielNichtMehrDaLiegt() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.bewegen(10, 10);
        s.druecken();
        s.bewegen(20, 10);    // runter + erster Schritt
        n.frei = false;
        s.bewegen(30, 10);    // Fehler: sofort hoch am letzten Punkt
        s.bewegen(40, 10);    // nichts mehr
        s.loslassen();        // nichts mehr
        assertEquals(List.of("RUNTER 499,400", "ZIEHEN 501,400", "HOCH 501,400"), n.touches());
    }

    @Test
    void ohneSpielfensterKeinTouch() {
        Nachbau n = new Nachbau();
        n.da = false;
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.loslassen();
        assertTrue(n.touches().isEmpty());
    }

    @Test
    void loslassenOhneDrueckenTutNichts() {
        Nachbau n = new Nachbau();
        steuerung(n).loslassen();
        assertTrue(n.log.isEmpty());
    }

    @Test
    void pflegeFrischtAufUndHebtNachAchtSekunden() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.bewegen(10, 10);
        s.druecken();
        s.bewegen(20, 10);    // Finger liegt
        n.log.clear();
        s.pflegen();          // zu frueh: nichts
        assertTrue(n.touches().isEmpty());
        n.uhr += HintergrundSteuerung.AUFFRISCHEN_MS;
        s.pflegen();
        assertEquals(List.of("ZIEHEN 501,400"), n.touches(), "Lebenszeichen an derselben Stelle");
        n.uhr += HintergrundSteuerung.NOTLOS_MS;
        s.pflegen();
        assertEquals(List.of("ZIEHEN 501,400", "HOCH 501,400"), n.touches());
        s.loslassen();
        assertEquals(2, n.touches().size(), "danach nichts mehr");
    }

    @Test
    void auffrischenNieBeiBelegterMaus() {
        Nachbau n = new Nachbau();
        HintergrundSteuerung s = steuerung(n);
        s.druecken();
        s.bewegen(5, 5);
        n.log.clear();
        n.uhr += HintergrundSteuerung.AUFFRISCHEN_MS;
        n.belegtNochMal = 1;
        s.pflegen();
        assertTrue(n.touches().isEmpty());
    }

    @Test
    void fotoSchneidetAusDemFensterbildAus() {
        Nachbau n = new Nachbau();
        n.bild = new int[] {
            1, 2, 3, 4,
            5, 6, 7, 8,
            9, 10, 11, 12};
        HintergrundSteuerung s = steuerung(n);
        BufferedImage b = s.foto(new Rectangle(1, 1, 2, 2));
        assertEquals(6, b.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(7, b.getRGB(1, 0) & 0xFFFFFF);
        assertEquals(10, b.getRGB(0, 1) & 0xFFFFFF);
        assertEquals(11, b.getRGB(1, 1) & 0xFFFFFF);
        s.foto(new Rectangle(0, 0, 1, 1));
        assertEquals(1, n.bildAufrufe, "derselbe Moment: ein Foto");
        n.uhr += HintergrundSteuerung.FOTO_GUELTIG_MS + 1;
        s.foto(new Rectangle(0, 0, 1, 1));
        assertEquals(2, n.bildAufrufe);
        s.druecken();
        s.loslassen();
        s.foto(new Rectangle(0, 0, 1, 1));
        assertEquals(3, n.bildAufrufe, "nach einem Tipp nie das alte Bild");
    }

    @Test
    void ausschneidenLaesstAussenSchwarz() {
        int[] q = {1, 2, 3, 4};   // 2 x 2
        int[] z = new int[9];      // 3 x 3 ab (-1,-1)
        HintergrundSteuerung.ausschneiden(q, 2, 2, -1, -1, z, 3, 3);
        assertArrayEquals(new int[] {0, 0, 0, 0, 1, 2, 0, 3, 4}, z);
        int[] weit = new int[4];
        HintergrundSteuerung.ausschneiden(q, 2, 2, 5, 5, weit, 2, 2);
        assertArrayEquals(new int[4], weit);
    }

    @Test
    void keinPcZeiger() {
        assertFalse(steuerung(new Nachbau()).istPcMaus(), "keine Mauswache im Hintergrund-Modus");
    }
}
