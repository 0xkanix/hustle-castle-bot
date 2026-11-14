package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.Test;
import org.opencv.core.Rect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schirmherrschaften einordnen und punkten (Autor 11.10., Nachtrag zu Auftrag 6, Punkt 4) -- die reine Rechnung,
 * ohne Bild: Stellen zusammenfassen, Punkte wie beim Gegner, Texte, Bereich des eigenen Fensters.
 */
class SchirmLeserTest {

    private static Matcher.Hit hit(String name, int cx, int cy) {
        return new Matcher.Hit(name, cx - 20, cy - 20, 40, 40, 0.9);
    }

    @Test
    void dieselbenPunkteWieBeimGegner() {
        // Ohne arena-extras.txt zaehlt alles: +1 je Schirmherrschaft, +3 je guter, +2 je gutem Stigma.
        var e = new SchirmLeser.Ergebnis(3, 1, 1, List.of("ar_schirm_gut_2"), List.of("ar_stigma_gut_1"));
        assertEquals(de.hustlebot.core.Gegnerbuch.schirmPunkte(3, 1, 1), e.punkte());
        assertEquals(3 + 3 + 2, e.punkte());
        assertEquals("Schirmherrschaften 3 (1 gute, 1 Stigma, +8)", e.text());
        assertEquals("gute: Nr. 2; Stigma: Baer", e.einordnung());
    }

    @Test
    void unbekanntZaehltNullUndSagtEs() {
        var u = SchirmLeser.Ergebnis.unbekannt();
        assertFalse(u.bekannt());
        assertEquals(0, u.punkte());
        assertEquals("Schirmherrschaften ?", u.text());
        assertEquals("", u.einordnung());
    }

    @Test
    void ohneGuteBleibtDieEinordnungLeer() {
        var e = new SchirmLeser.Ergebnis(3, 0, 0, List.of(), List.of());
        assertEquals("", e.einordnung());
        assertEquals("Schirmherrschaften 3 (0 gute, 0 Stigma, +3)", e.text());
    }

    @Test
    void stigmaNamenAusDerSerie() {
        assertEquals(List.of("Baer", "Loewe", "Wolf", "Nr. 4", "alt"),
                SchirmLeser.kurznamen(List.of("ar_stigma_gut_1", "ar_stigma_gut_2", "ar_stigma_gut_3",
                        "ar_stigma_gut_4", "ar_stigma_gut_alt"), SchirmLeser.S_STIGMA_GUT, SchirmLeser.STIGMA_NAMEN));
        // Der Grundname ohne Nummer ist Motiv 1.
        assertEquals(List.of("Baer"), SchirmLeser.kurznamen(List.of("ar_stigma_gut"), SchirmLeser.S_STIGMA_GUT, SchirmLeser.STIGMA_NAMEN));
        assertEquals(List.of("Nr. 2"), SchirmLeser.kurznamen(List.of("ar_schirm_gut_2"), SchirmLeser.S_SCHIRM_GUT, null));
    }

    @Test
    void naheTrefferSindEineStelle() {
        // Zwei Vorlagen derselben Serie treffen dieselbe Karte (5 Punkte versetzt): eine Stelle.
        // Die dritte liegt 100 Punkte weiter rechts: zweite Stelle.
        List<Matcher.Hit> t = List.of(hit("ar_schirm_gut_1", 100, 200), hit("ar_schirm_gut_2", 105, 203), hit("ar_schirm_gut_2", 200, 200));
        List<Matcher.Hit> s = SchirmLeser.stellen(t, SchirmLeser.ABSTAND_BILD, SchirmLeser.ABSTAND_BILD);
        assertEquals(2, s.size());
        assertEquals("ar_schirm_gut_1", s.get(0).name(), "der erste Treffer einer Stelle bleibt");
    }

    @Test
    void lvNurWaagerechtZusammengefasst() {
        // "Lv." wird wie bisher nur ueber die x-Lage zusammengefasst (Arena: 40 Punkte).
        List<Matcher.Hit> t = List.of(hit("ar_schirm_lv", 100, 200), hit("ar_schirm_lv", 120, 500), hit("ar_schirm_lv", 300, 200));
        assertEquals(2, SchirmLeser.stellen(t, SchirmLeser.ABSTAND_LV, Integer.MAX_VALUE).size());
    }

    @Test
    void eigenerBereichIstDieLinkeSeite() {
        // Autors Bild 14 (1159x770): die drei aktiven Karten liegen bei x 60-520, y 150-640; die Liste rechts ab x ~580.
        Rect r = SchirmLeser.eigenerBereich(1159, 770);
        assertEquals(0, r.x);
        assertTrue(r.x + r.width >= 540 && r.x + r.width <= 600, "rechter Rand " + (r.x + r.width));
        assertTrue(r.y <= 150 && r.y + r.height >= 640, "senkrecht " + r.y + ".." + (r.y + r.height));
        // "Lv." der Liste rechts (x 663..1032 im Bild 14) liegt ausserhalb.
        assertTrue(663 > r.x + r.width);
    }
}
