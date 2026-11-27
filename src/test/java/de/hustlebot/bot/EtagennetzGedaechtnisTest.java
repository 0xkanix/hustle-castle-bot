package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bauplan L1/L2 (09.10.) auf der festen Karte "Gitter": Ein verdeckter naechster Stuetzpunkt wird nur
 * MIT Schalter aus dem Gedaechtnis angeklickt (ohne: warten wie bisher), das Gedaechtnis ist mit dem
 * Klick verbraucht, und die Vorausplanung rechnet den naechsten Schritt, ohne den Zustand zu aendern.
 */
class EtagennetzGedaechtnisTest {

    /** Die Stuetzpunkte des Gitters (Sockel-Mitten bei 1159x770), in der Reihenfolge der festen Karte. */
    private static final int[][] GITTER = {
        {599, 154}, {404, 257}, {792, 260}, {270, 257}, {271, 392}, {410, 529}, {790, 521},
        {915, 258}, {147, 387}, {1045, 388}, {915, 389}, {272, 521}, {912, 520}, {598, 646}
    };
    private static final int WAECHTER = 0, OBEN_LINKS = 1, MITTE_LINKS = 5, MITTE_RECHTS = 6,
            UNTEN_LINKS = 11, ANFANG = 13;

    private Mat bild;

    @BeforeAll
    static void openCvLaden() {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            Assumptions.abort("OpenCV nicht ladbar: " + t.getMessage());
        }
    }

    @BeforeEach
    void bildAnlegen() {
        bild = new Mat(770, 1159, CvType.CV_8UC3);
    }

    @AfterEach
    void bildFreigeben() {
        bild.release();
    }

    private static Matcher.Hit bei(String name, int x, int y) {
        return new Matcher.Hit(name, x - 10, y - 10, 20, 20, 0.9);
    }

    private static Matcher.Hit sockel(int i) {
        return bei("sockel", GITTER[i][0], GITTER[i][1]);
    }

    /** Motiv und Schloss sitzen 40 Bildpunkte ueber dem Sockel. */
    private static Matcher.Hit motiv(int i) {
        return bei(i == OBEN_LINKS ? "dc_camp_alt" : "dc_cube", GITTER[i][0], GITTER[i][1] - 40);
    }

    private static Matcher.Hit schloss(int i) {
        return bei("lock_icon", GITTER[i][0], GITTER[i][1] - 40);
    }

    private static Optional<Matcher.Hit> waechter() {
        return Optional.of(bei("dc_guardian_lair", GITTER[WAECHTER][0], GITTER[WAECHTER][1] - 40));
    }

    /**
     * Erstes Bild: Anfang fertig, die zwei Stuetzpunkte darueber offen, alle anderen mit Motiv UND Schloss.
     * Danach ist der Stuetzpunkt "Mitte links" gespielt, aber noch nicht abgeschlossen.
     */
    private Etagennetz ersteEtagenhaelfte() {
        Etagennetz netz = new Etagennetz();
        List<Matcher.Hit> sockel = new ArrayList<>(), gesperrt = new ArrayList<>(), schloesser = new ArrayList<>();
        for (int i = 0; i < GITTER.length; i++) sockel.add(sockel(i));
        for (int i = 0; i < GITTER.length; i++) {
            if (i == WAECHTER || i == ANFANG || i == MITTE_LINKS || i == MITTE_RECHTS) continue;
            gesperrt.add(motiv(i));
            schloesser.add(schloss(i));
        }
        netz.beobachten(bild, sockel, List.of(sockel(ANFANG)), List.of(), List.of(), waechter(),
                List.of(motiv(MITTE_LINKS), motiv(MITTE_RECHTS)), gesperrt, schloesser, true);
        assertTrue(netz.festeKarte(), "das Gitter muss als feste Karte erkannt werden");
        Etagennetz.Plan erster = netz.planen(true);
        assertNotNull(erster);
        assertEquals(Etagennetz.Art.SCHRITT, erster.art());
        assertEquals(motiv(MITTE_LINKS), erster.klick());
        netz.gespielt(erster.klick());
        return netz;
    }

    /** Zweites Bild nach dem Segen: "oben links" ist offen, liegt aber unter dem Schriftzug (kein Motiv, kein Schloss). */
    private static void zweitesBild(Etagennetz netz, Mat bild) {
        List<Matcher.Hit> sockel = new ArrayList<>(), gesperrt = new ArrayList<>(), schloesser = new ArrayList<>();
        for (int i = 0; i < GITTER.length; i++) if (i != OBEN_LINKS) sockel.add(sockel(i));
        for (int i = 0; i < GITTER.length; i++) {
            if (i == WAECHTER || i == ANFANG || i == MITTE_LINKS || i == MITTE_RECHTS
                    || i == OBEN_LINKS || i == UNTEN_LINKS) continue;
            gesperrt.add(motiv(i));
            schloesser.add(schloss(i));
        }
        netz.beobachten(bild, sockel, List.of(sockel(ANFANG), sockel(MITTE_LINKS)), List.of(), List.of(), waechter(),
                List.of(motiv(MITTE_RECHTS), motiv(UNTEN_LINKS)), gesperrt, schloesser, false);
    }

    @Test
    void ohneSchalterWirdGewartetMitSchalterAusDemGedaechtnisGeklickt() {
        Etagennetz netz = ersteEtagenhaelfte();
        netz.letzterAbgeschlossen();
        zweitesBild(netz, bild);

        netz.gedaechtnisKlickErlauben(false);
        Etagennetz.Plan alt = netz.planen(false);
        assertNotNull(alt);
        assertEquals(Etagennetz.Art.WARTEN, alt.art(), "Schalter aus: wie bisher warten");

        netz.gedaechtnisKlickErlauben(true);
        Etagennetz.Plan neu = netz.planen(false);
        assertNotNull(neu);
        assertEquals(Etagennetz.Art.SCHRITT, neu.art());
        assertEquals(motiv(OBEN_LINKS), neu.klick(), "die gemerkte Stelle (Motiv mit Schloss vom ersten Bild)");
        assertTrue(neu.grund().contains("dc_camp"), neu.grund());
        assertTrue(netz.planAusGedaechtnisVerbrauchen(), "Plan aus dem Gedaechtnis");
        assertFalse(netz.planAusGedaechtnisVerbrauchen(), "Merker ist verbraucht");

        // Mit dem Klick ist das Gedaechtnis verbraucht: kein zweiter Blindklick auf denselben Stuetzpunkt.
        netz.gespielt(neu.klick());
        Etagennetz.Plan danach = netz.planen(false);
        assertNotNull(danach);
        assertEquals(Etagennetz.Art.WARTEN, danach.art());
    }

    @Test
    void vorausplanungRechnetDenNaechstenSchrittOhneDenZustandZuAendern() {
        Etagennetz netz = ersteEtagenhaelfte();
        int planVorher = netz.planAmAnfang();

        Etagennetz.Plan voraus = netz.vorausPlanen();
        assertNotNull(voraus);
        assertEquals(Etagennetz.Art.SCHRITT, voraus.art());
        assertEquals(motiv(OBEN_LINKS), voraus.klick());

        // Der echte Zustand ist unberuehrt.
        assertFalse(netz.letzterFertig());
        assertEquals(planVorher, netz.planAmAnfang());
        netz.letzterAbgeschlossen();
        assertTrue(netz.letzterFertig());
    }

    @Test
    void keineVorausplanungNebenEinemFragezeichen() {
        Etagennetz netz = new Etagennetz();
        List<Matcher.Hit> sockel = new ArrayList<>(), gesperrt = new ArrayList<>(), schloesser = new ArrayList<>();
        for (int i = 0; i < GITTER.length; i++) sockel.add(sockel(i));
        for (int i = 0; i < GITTER.length; i++) {
            if (i == WAECHTER || i == ANFANG || i == MITTE_LINKS || i == MITTE_RECHTS || i == UNTEN_LINKS) continue;
            gesperrt.add(motiv(i));
            schloesser.add(schloss(i));
        }
        // "unten links" (Nachbar von "Mitte links") ist ein Fragezeichen: dahinter kann ein Boss stecken.
        Matcher.Hit frage = bei("dc_question", GITTER[UNTEN_LINKS][0], GITTER[UNTEN_LINKS][1] - 40);
        netz.beobachten(bild, sockel, List.of(sockel(ANFANG)), List.of(frage), List.of(), waechter(),
                List.of(motiv(MITTE_LINKS), motiv(MITTE_RECHTS)), gesperrt, schloesser, true);
        assertTrue(netz.festeKarte());
        netz.gespielt(motiv(MITTE_LINKS));
        assertNull(netz.vorausPlanen());
    }
}
