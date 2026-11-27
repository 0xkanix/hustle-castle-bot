package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sparsam 20.11.: feste Suchbereiche im Matcher. Kuenstliche Bilder (Rauschen) mit einer Vorlage "update_button"
 * (Bereich laut suchbereiche.txt: x 507, y 452-479) -- einmal im Bereich, einmal weit ausserhalb.
 */
class MatcherSuchbereicheTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @AfterEach
    void zurueck() {
        Matcher.BEREICHE_AN = true;
    }

    private static Mat rauschen(int w, int h, long saat) {
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        Core.setRNGSeed((int) saat);
        Core.randu(m, 0, 256);
        return m;
    }

    private static Mat bildMit(Mat vorlage, int x, int y, long saat) {
        Mat bild = rauschen(1159, 770, saat);
        vorlage.copyTo(bild.submat(new Rect(x, y, vorlage.width(), vorlage.height())));
        return bild;
    }

    private static Matcher matcher(Mat vorlage) throws Exception {
        Path ordner = Files.createTempDirectory("suchbereiche");
        Imgcodecs.imwrite(ordner.resolve("update_button.png").toString(), vorlage);
        Matcher m = new Matcher(ordner);
        m.loadAll();
        assertTrue(m.has("update_button"));
        return m;
    }

    @Test
    void bereichIstAusDerTabelleUndNurInGemessenerFenstergroesse() {
        Rect r = Suchbereiche.bereich("update_button", 1159, 770, 147, 73);
        assertNotNull(r);
        assertEquals(new Rect(447, 392, 507 + 147 + 60 - 447, 479 + 73 + 60 - 392), r);
        assertNotNull(Suchbereiche.bereich("update_button", 1157, 758, 147, 73), "Karlas Fenster (+-16) gilt noch");
        assertNull(Suchbereiche.bereich("update_button", 1600, 1000, 147, 73), "andere Fenstergroesse: ganzes Bild");
        assertNull(Suchbereiche.bereich("gibt_es_nicht", 1159, 770, 50, 50));
        Rect streifen = Suchbereiche.bereich("chat_close", 1159, 770, 32, 56);
        assertEquals(0, streifen.x);
        assertEquals(1159, streifen.width, "x frei: Streifen ueber die ganze Breite");
        assertEquals(323 - 60, streifen.y);
        assertFalse(Suchbereiche.hat("dc_node"), "Orte der Weltkarte wandern -- nie fest");
    }

    @Test
    void imBereichGefundenGleicherWertWieImGanzenBild() throws Exception {
        Mat vorlage = rauschen(147, 73, 7);
        Matcher m = matcher(vorlage);
        Mat bild = bildMit(vorlage, 507, 470, 11);
        Optional<Matcher.Hit> h = m.find(bild, "update_button");
        assertTrue(h.isPresent());
        assertEquals(507, h.get().x());
        assertEquals(470, h.get().y());
        assertTrue(h.get().score() > 0.99);
        assertTrue(m.vergleichsBericht().contains("1 im festen Suchbereich") || m.vergleichsBericht().contains("1 in the fixed search area"),
                m.vergleichsBericht());

        Matcher.BEREICHE_AN = false;
        Matcher alt = matcher(vorlage);
        Optional<Matcher.Hit> ganz = alt.find(bild, "update_button");
        assertEquals(ganz.get().score(), h.get().score(), 1e-6, "gleiche Rechnung, gleicher Wert");
    }

    @Test
    void ausserhalbSpaetestensBeiDerFuenfundzwanzigstenSucheGefundenUndDannImmer() throws Exception {
        Mat vorlage = rauschen(147, 73, 8);
        Matcher m = matcher(vorlage);
        int gefunden = -1;
        for (int i = 1; i <= 30 && gefunden < 0; i++) {
            m.neuesBild();
            Mat bild = bildMit(vorlage, 100, 100, 100 + i);   // jedes Mal ein neues Bild
            if (m.find(bild, "update_button").isPresent()) gefunden = i;
        }
        assertEquals(25, gefunden, "die 25. Suche geht ueber das ganze Bild");
        // danach gilt fuer diese Vorlage wieder das ganze Bild (und die gemerkte Stelle)
        for (int i = 0; i < 5; i++) {
            m.neuesBild();
            Optional<Matcher.Hit> h = m.find(bildMit(vorlage, 100, 100, 200 + i), "update_button");
            assertTrue(h.isPresent());
            assertEquals(100, h.get().x());
        }
    }

    @Test
    void gruendlichesBildSuchtGleichDasGanzeBild() throws Exception {
        Mat vorlage = rauschen(147, 73, 9);
        Matcher m = matcher(vorlage);
        m.naechstesBildGruendlich();
        m.neuesBild();
        assertTrue(m.find(bildMit(vorlage, 100, 100, 5), "update_button").isPresent(),
                "nach drei unerkannten Bildern sucht der Bot ueberall -- auch ausserhalb des Bereichs");
    }

    @Test
    void verliesSuchtToteEinheitenNurUnterDerZeile() throws Exception {
        Mat vorlage = rauschen(66, 12, 10);
        Path ordner = Files.createTempDirectory("tote");
        Imgcodecs.imwrite(ordner.resolve("unit_dead_bar.png").toString(), vorlage);
        Matcher m = new Matcher(ordner);
        m.loadAll();
        Mat bild = rauschen(1159, 770, 12);
        vorlage.copyTo(bild.submat(new Rect(200, 100, 66, 12)));   // oben: wird ohnehin verworfen
        vorlage.copyTo(bild.submat(new Rect(300, 600, 66, 12)));
        vorlage.copyTo(bild.submat(new Rect(700, 650, 66, 12)));
        var ganz = m.findAll(bild, "unit_dead_bar", 0.88, 12);
        var unten = m.findAllImBereich(bild, "unit_dead_bar", 0.88, 12, new Rect(0, 500, 1159, 270));
        assertEquals(3, ganz.size());
        assertEquals(2, unten.size());
        for (var h : unten) {
            assertTrue(ganz.stream().anyMatch(g -> g.x() == h.x() && g.y() == h.y() && Math.abs(g.score() - h.score()) < 1e-6),
                    "gleiche Stelle, gleicher Wert: " + h);
        }
    }
}
