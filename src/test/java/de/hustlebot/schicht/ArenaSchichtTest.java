package de.hustlebot.schicht;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena-Schicht (Autor 28.10., Alpha): Rahmen, Lage des Seitenfensters, Rechenzettel. */
class ArenaSchichtTest {

    @AfterEach
    void standWeg() {
        ArenaStand.setzen(null);   // kein Stand fuer andere Tests liegen lassen
    }

    private static ArenaStand.Gegner gegner(int x, int y, int platz, String name, String stufe, int chance, boolean ziel) {
        return new ArenaStand.Gegner(x, y, platz, name, "Clan", false, stufe, false, false, 150, chance,
                "Notizblock 3/4, 24.606.287", stufe.equals("sicher") ? "SICHERER SIEG (-40)" : "", ziel);
    }

    private static ArenaStand.Stand stand(List<ArenaStand.Platz> plaetze, ArenaStand.Gegner... g) {
        return new ArenaStand.Stand(System.currentTimeMillis(), 1, 1159, 770, List.of(g), plaetze);
    }

    private static ArenaStand.Stand stand(ArenaStand.Gegner... g) {
        return stand(List.of(), g);
    }

    @Test
    void jederGegnerBekommtEinenRahmenUnbekannteGrau() {
        var s = stand(List.of(new ArenaStand.Platz(7, 228, 626, "sicher"), new ArenaStand.Platz(8, 463, 626, ""),
                new ArenaStand.Platz(5, 580, 492, "niemals")));
        var r = ArenaSchicht.rahmen(s, 1159, 770);
        assertEquals(3, r.size());
        assertEquals(SchichtFenster.SICHER, r.get(0).farbe());
        assertEquals(SchichtFenster.UNBEKANNT, r.get(1).farbe());
        assertTrue(SchichtFenster.UNBEKANNT.getAlpha() < SchichtFenster.RAHMEN_ALPHA, "grau dezenter als die Ampel");
        assertEquals(SchichtFenster.NIEMALS, r.get(2).farbe());
    }

    @Test
    void rahmenUmDasSchildUndMitDemFensterSkaliert() {
        // Goldener Rahmen, an 6 echten Pyramiden-Fotos gemessen (Platz 1, Punktezeile 580,225: x 469-689, y 165-301); hier Platz 4.
        Rectangle r = ArenaSchicht.schildRahmen(345, 492, 1159);
        assertEquals(235, r.x, 1);
        assertEquals(455, r.x + r.width, 1);
        assertEquals(432, r.y, 1);
        assertEquals(568, r.y + r.height, 1);
        // Doppelt so grosses Fenster -> doppelt so grosser Rahmen an doppelter Stelle.
        var gross = ArenaSchicht.rahmen(stand(List.of(new ArenaStand.Platz(4, 345, 492, "machbar"))), 2318, 1540)
                .get(0).bereich();
        assertEquals(r.x * 2, gross.x, 1);
        assertEquals(r.width * 2, gross.width, 1);
        assertEquals(r.y * 2, gross.y, 1);
    }

    @Test
    void seitenfensterNebenDemSpielNieDarueber() {
        Rectangle schirm = new Rectangle(0, 0, 1920, 1040);
        Rectangle rechtsAngedockt = new Rectangle(1920 - 1165, 30, 1159, 770);
        Rectangle l = SeitenFenster.lage(rechtsAngedockt, schirm);
        assertNotNull(l);
        assertTrue(l.x + l.width <= rechtsAngedockt.x, "links neben dem Spiel, nicht darueber");
        Rectangle linksAngedockt = new Rectangle(5, 30, 1159, 770);
        Rectangle r = SeitenFenster.lage(linksAngedockt, schirm);
        assertNotNull(r);
        assertTrue(r.x >= linksAngedockt.x + linksAngedockt.width, "rechts daneben, wenn links kein Platz ist");
        assertTrue(r.x + r.width <= schirm.width);
        // Review 28.10.: maximiertes Spiel oder schmaler Bildschirm -> gar nicht zeigen.
        assertNull(SeitenFenster.lage(new Rectangle(0, 23, 1920, 1009), schirm));
        assertNull(SeitenFenster.lage(new Rectangle(1366 - 1165, 30, 1159, 770), new Rectangle(0, 0, 1366, 728)));
    }

    @Test
    void zweitbesterOhneZielUndOhneNiemals() {
        var s = stand(gegner(1, 1, 7, "Ziel", "", 95, true),
                gegner(2, 2, 8, "Gesperrt", "niemals", 99, false),
                gegner(3, 3, 5, "Zweiter", "", 88, false),
                gegner(4, 4, 4, "Dritter", "", 61, false));
        assertEquals("Zweiter", SeitenFenster.zweitbester(s).name());
        assertNull(SeitenFenster.zweitbester(stand(gegner(1, 1, 7, "Ziel", "", 95, true))));
    }

    @Test
    void sortiertNachPlatzDannVonLinks() {
        var s = stand(gegner(900, 1, 9, "c", "", 1, false), gegner(100, 1, 2, "a", "", 1, false),
                gegner(500, 1, 9, "b", "", 1, false));
        assertEquals(List.of("a", "b", "c"), SeitenFenster.sortiert(s).stream().map(ArenaStand.Gegner::name).toList());
    }

    @Test
    void auswahlSchluesselVertraegtKleineSpruenge() {
        assertEquals(SeitenFenster.schluessel(gegner(400, 600, 7, "a", "", 1, false)),
                SeitenFenster.schluessel(gegner(401, 601, 7, "a", "", 1, false)));
    }

    @Test
    void rechenzettelPosten() {
        var p = SeitenFenster.posten(gegner(1, 1, 7, "Torres", "sicher", 95, true));
        assertEquals(List.of("SICHERER SIEG (-40)", "Notizblock 3/4", "24.606.287"), p);
        var leer = new ArenaStand.Gegner(1, 1, 7, "", "", false, "", false, false, 150, -1, "", "", false);
        assertEquals(1, SeitenFenster.posten(leer).size());
        assertEquals("(name not readable)", leer.anzeige());
    }

    @Test
    void umbrechenHaeltDieBreite() {
        Graphics2D g = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        FontMetrics fm = g.getFontMetrics();
        String text = "Punkte 37 (Team unbekannt, Kraft unbekannt), 33 unter deinem Team (70), Erfahrung 70 % aus 4 Kaempfen";
        List<String> z = SeitenFenster.umbrechen(text, fm, 150);
        assertTrue(z.size() > 1);
        for (String s : z) assertTrue(fm.stringWidth(s) <= 150, s);
        assertEquals(text.replace(" ", ""), String.join("", z).replace(" ", ""));
        g.dispose();
    }

    @Test
    void standVerfaelltNachEinigenSekunden() {
        ArenaStand.setzen(new ArenaStand.Stand(System.currentTimeMillis() - ArenaStand.HALTBAR_MS - 1, 1, 1159, 770,
                List.of(), List.of()));
        assertNull(ArenaStand.aktuell());
        ArenaStand.setzen(stand());
        assertNotNull(ArenaStand.aktuell());
        ArenaStand.setzen(null);
        assertNull(ArenaStand.aktuell());
    }

    /** Autor 29.10. nachts: das Ziel des Bots steht auch im Spiel -- ganzer Rahmen und Schild "Bot ▶ 81 %". */
    @Test
    void zielDesBotsHatGanzenRahmenUndSchild() {
        ArenaStand.Stand s = stand(List.of(new ArenaStand.Platz(4, 345, 512, "sicher"), new ArenaStand.Platz(5, 579, 512, "")),
                gegner(345, 512, 4, "Falke", "sicher", 81, true), gegner(579, 512, 5, "Baer", "", 40, false));
        List<SchichtFenster.Rahmen> r = ArenaSchicht.rahmen(s, 1159, 770, null);
        assertEquals(2, r.size());
        assertTrue(r.get(0).ziel());
        assertEquals("Bot ▶ 81 %", r.get(0).text());
        assertEquals(false, r.get(1).ziel());
        assertNull(r.get(1).text());
    }

    /**
     * Autor 30.10.: "genau so umrahmen wie dieser goldene Rahmen": Oberkante gerade, Seiten gerade, unten die Spitze in der Mitte.
     * Innen bleibt alles durchsichtig, damit der Rahmen das Schild nicht verdeckt.
     */
    @Test
    void rahmenFolgtDemGoldenenSchild() {
        Rectangle b = new Rectangle(100, 100, 200, 120);
        BufferedImage bild = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bild.createGraphics();
        SchichtFenster.vorschauZeichnen(g, List.of(new SchichtFenster.Rahmen(b, SchichtFenster.SICHER)), 400);
        g.dispose();
        assertTrue((bild.getRGB(200, 101) >>> 24) > 0, "Oberkante durchgehend gemalt");
        assertTrue((bild.getRGB(101, 150) >>> 24) > 0, "linke Seite gemalt");
        assertEquals(0, bild.getRGB(200, 160) >>> 24, "Mitte bleibt durchsichtig");
        assertEquals(0, bild.getRGB(110, 218) >>> 24, "unten links ist das Schild schon zu Ende");

        var form = SchichtFenster.Flaeche.schildUmriss(b, 1f);
        assertTrue(form.contains(200, 215), "die Spitze reicht unten in der Mitte fast bis zum Rand");
        assertFalse(form.contains(106, 215), "an der Seite ist unten vorher Schluss");
    }

    /** Die Pyramide in einer Zeile (Kommentare zur Stage, Ablehnungen): "Platz Name +Belohnung Chance %", mit "; " getrennt. */
    @Test
    void lageTextNenntJedenGegnerMitBelohnungUndChance() {
        var s = stand(gegner(1, 1, 7, "Torres", "sicher", 95, true), gegner(2, 2, 8, "Baer", "", 61, false),
                new ArenaStand.Gegner(3, 3, 4, "Falke", "Clan", false, "", false, false, 225, 81, "", "", false));
        assertEquals("7 Torres +150 95 % (bot); 8 Baer +150 61 %; 4 Falke +225 81 %", ArenaSchicht.lageText(s));
    }

    @Test
    void lageTextOhneChanceKeinProzentUndOhneNamenNoName() {
        var s = stand(gegner(1, 1, 7, "Torres", "", -1, false),
                new ArenaStand.Gegner(2, 2, 5, "", "", false, "", false, false, 225, -1, "", "", true));
        String t = ArenaSchicht.lageText(s);
        assertEquals("7 Torres +150; 5 (name not readable) +225 (bot)", t);
        assertFalse(t.contains("%"), t);
        assertEquals("", ArenaSchicht.lageText(stand()), "keine Gegner, keine Zeile");
    }

    /** Vorschaubild 29.10.: chinesische/koreanische Namen und Zeichen wie ▶ kamen als leere Kaestchen. */
    @Test
    void schriftKannFremdeZeichen() {
        Font f = SchichtFenster.schrift(Font.PLAIN, 13);
        assertEquals(-1, f.canDisplayUpTo("Bäldr паша 龙之刃 별빛 صقر"), "Schrift mit Ersatzschriften");
        BufferedImage bild = SeitenFenster.vorschau(stand(gegner(345, 512, 4, "龙之刃", "sicher", 81, true)), null, 600);
        assertEquals(SeitenFenster.BREITE, bild.getWidth());
    }
}
