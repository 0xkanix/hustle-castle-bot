package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Autor 30.10.: der Bot verfolgt seine Klicks im Vergleichsfenster und merkt sich den Gegner. Lagen aus dem Foto f_048 (1159x770). */
class ArenaBeobachterKlickTest {

    private static double fx(int x) {
        return x / 1159.0;
    }

    private static double fy(int y) {
        return y / 770.0;
    }

    @Test
    void beideAngriffsKnoepfeZaehlen() {
        assertTrue(ArenaMode.istAngriffsKnopf(fx(670), fy(617)), "Quick Attack");
        assertTrue(ArenaMode.istAngriffsKnopf(fx(860), fy(617)), "To battle");
        assertFalse(ArenaMode.istAngriffsKnopf(fx(300), fy(617)), "Reward-Anzeige links");
        assertFalse(ArenaMode.istAngriffsKnopf(fx(670), fy(500)), "Reihe des Gegners");
        assertFalse(ArenaMode.istAngriffsKnopf(fx(950), fy(130)), "rotes X");
    }

    @Test
    void pfeileUnterDemFensterSindAndererGegner() {
        assertTrue(ArenaMode.istVergleichsPfeil(fx(449), fy(696)), "links");
        assertTrue(ArenaMode.istVergleichsPfeil(fx(709), fy(696)), "rechts");
        assertFalse(ArenaMode.istVergleichsPfeil(fx(580), fy(696)), "Text dazwischen");
        assertFalse(ArenaMode.istVergleichsPfeil(fx(670), fy(617)), "Quick Attack ist kein Pfeil");
    }

    /** Echte Lesungen aus Autors Runde 30.10. */
    @Test
    void nameVomAngeklicktenSchildGiltWennErGrobPasst() {
        assertEquals("Khun", ArenaMode.nameMitKlick(new String[]{"Khuni", ""}, "Khun", "")[0]);
        assertEquals("Duje", ArenaMode.nameMitKlick(new String[]{"Dujei", ""}, "Duje", "")[0]);
        assertEquals("Agust", ArenaMode.nameMitKlick(new String[]{"Agnstin clan", ""}, "Agust", "")[0]);
        assertEquals("Clan", ArenaMode.nameMitKlick(new String[]{"Khuni", ""}, "Khun", "Clan")[1], "fehlender Clan kommt vom Schild");
        assertEquals("Hier", ArenaMode.nameMitKlick(new String[]{"Khuni", "Hier"}, "Khun", "Clan")[1]);
    }

    @Test
    void ganzAndererNameGiltAusDemFenster() {
        assertEquals("LOBO", ArenaMode.nameMitKlick(new String[]{"LOBO", ""}, "zevs", "")[0], "die Pyramide hat sich umgeordnet");
    }

    @Test
    void ohneKlickOderOhneLesungBleibtWasDaIst() {
        assertEquals("LOBO", ArenaMode.nameMitKlick(new String[]{"LOBO", ""}, "", "")[0]);
        assertEquals("zevs", ArenaMode.nameMitKlick(new String[]{"", ""}, "zevs", "")[0]);
        assertEquals("", ArenaMode.nameMitKlick(new String[]{"", ""}, "", "")[0]);
    }

    @Test
    void spielerNameWirdSichererDateiname() {
        assertEquals("Jahamość", ArenaMode.spielerDatei("Jahamość"));
        assertEquals("JINXEDMURENA", ArenaMode.spielerDatei("JINXED MURENA"));
        assertEquals("ЦИРИ", ArenaMode.spielerDatei("~ЦИРИ~"));
        assertEquals("evil", ArenaMode.spielerDatei("..\\..\\evil"), "kein Weg aus dem Ordner heraus");
        assertTrue(ArenaMode.spielerDatei("~~~").startsWith("gegner"));
    }

    @Test
    void fotoIstHalbSoGrossUndKlein(@TempDir Path ordner) throws Exception {
        BufferedImage voll = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        BufferedImage foto = ArenaMode.vergleichsFoto(voll);
        assertEquals(370, foto.getWidth(), 2);
        assertEquals(94, foto.getHeight(), 2);
        Path ziel = ordner.resolve("foto.jpg");
        ArenaMode.alsJpeg(foto, ziel, 0.6f);
        assertTrue(Files.size(ziel) > 0);
    }
}
