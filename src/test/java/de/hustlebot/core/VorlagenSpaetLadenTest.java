package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Speicher 20.11. (Autor spielt nebenbei Minecraft): Vorlagen werden erst beim ersten Gebrauch entpackt. Vorher entpackte
 * jeder Modus-Start alle ~1430 Bilder (68 MB, ~1 s). Gefunden wird dasselbe wie vorher.
 */
class VorlagenSpaetLadenTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat muster(int breite, int hoehe) {
        Mat m = new Mat(hoehe, breite, CvType.CV_8UC3);
        Core.randu(m, 0, 256);
        return m;
    }

    private static void speichern(Path datei, Mat m) throws Exception {
        Files.createDirectories(datei.getParent());
        assertTrue(Imgcodecs.imwrite(datei.toString(), m));
    }

    /** Ein dunkles Spielbild mit dem Muster bei (50, 40). */
    private static Mat schirmMit(Mat muster) {
        Mat s = new Mat(150, 220, CvType.CV_8UC3, new Scalar(20, 20, 20));
        muster.copyTo(s.submat(new Rect(50, 40, muster.width(), muster.height())));
        return s;
    }

    @Test
    void ladenEntpacktNichtsErstDerGebrauch(@TempDir Path wurzel) throws Exception {
        Mat a = muster(24, 18), b = muster(30, 12);
        speichern(wurzel.resolve("dungeon/dc_a.png"), a);
        speichern(wurzel.resolve("dungeon/dc_b.png"), b);
        Matcher m = new Matcher(wurzel);
        assertEquals(2, m.loadAll());
        assertEquals(0, m.entpackt(), "beim Laden wird nichts entpackt");
        assertTrue(m.has("dc_a") && m.has("dc_b"));
        assertArrayEquals(new int[]{24, 18}, m.sizes().get("dc_a"), "Groesse aus dem PNG-Kopf");
        assertArrayEquals(new int[]{30, 12}, m.sizes().get("dc_b"));

        var treffer = m.find(schirmMit(a), "dc_a");
        assertTrue(treffer.isPresent());
        assertEquals(50, treffer.get().x());
        assertEquals(40, treffer.get().y());
        assertEquals(1, m.entpackt(), "nur die gebrauchte Vorlage liegt im Speicher");

        assertEquals(2, m.loadAll());
        assertEquals(0, m.entpackt(), "Neuladen (naechster Modus) gibt den Speicher wieder frei");
        assertTrue(m.find(schirmMit(b), "dc_b").isPresent());
    }

    @Test
    void neuereDoppelteGiltAuchBeimSpaetenLesen(@TempDir Path wurzel) throws Exception {
        Mat alt = muster(26, 20), neu = muster(26, 20);
        Path altDatei = wurzel.resolve("dungeon/dc_x.png"), neuDatei = wurzel.resolve("cathedral/dc_x.png");
        speichern(altDatei, alt);
        speichern(neuDatei, neu);
        Files.setLastModifiedTime(altDatei, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));
        Matcher m = new Matcher(wurzel);
        assertEquals(1, m.loadAll());
        var h = m.find(schirmMit(neu), "dc_x");
        assertTrue(h.isPresent() && h.get().score() > 0.99, "die neuere Aufnahme (cathedral) gilt");
        assertTrue(m.find(schirmMit(alt), "dc_x", 0.9).isEmpty(), "die aeltere ist nicht die Vorlage");
    }

    @Test
    void kaputteDateiFaelltBeimErstenGebrauchWeg(@TempDir Path wurzel) throws Exception {
        MatOfByte png = new MatOfByte();
        Imgcodecs.imencode(".png", muster(20, 20), png);
        byte[] voll = png.toArray();
        Path kaputt = wurzel.resolve("event/ev_kaputt.png");
        Files.createDirectories(kaputt.getParent());
        Files.write(kaputt, Arrays.copyOf(voll, 40));   // Kopf heil, Bild abgeschnitten
        Matcher m = new Matcher(wurzel);
        assertEquals(1, m.loadAll());
        int ab = Log.size();
        assertTrue(m.find(schirmMit(muster(20, 20)), "ev_kaputt").isEmpty());
        assertFalse(m.has("ev_kaputt"), "nach dem misslungenen Lesen ist der Name weg, wie frueher beim Laden");
        assertTrue(Log.snapshot().stream().skip(ab).anyMatch(e -> e.level() == Log.Level.WARN
                && e.message().contains("ev_kaputt")), "eine Warnung nennt die Datei");
    }

    @Test
    void dateiOhnePngKopfWieBisherSofortGeprueft(@TempDir Path wurzel) throws Exception {
        Path muell = wurzel.resolve("event/ev_muell.png");
        Files.createDirectories(muell.getParent());
        Files.writeString(muell, "kein Bild");
        Matcher m = new Matcher(wurzel);
        assertEquals(0, m.loadAll(), "unlesbar ohne PNG-Kopf: gar nicht erst geladen");
        assertFalse(m.has("ev_muell"));
        assertNull(Matcher.pngMasse(muell));
    }

    @Test
    void eigeneAufnahmeBehaeltDasOriginalAlsRueckfall(@TempDir Path wurzel) throws Exception {
        Mat original = muster(22, 16), eigen = muster(22, 16);
        speichern(wurzel.resolve("dungeon/ok_knopf.png"), original);
        speichern(wurzel.resolve("gelernt/eigen/ok_knopf.png"), eigen);
        Matcher m = new Matcher(wurzel);
        assertEquals(1, m.loadAll());
        assertEquals(1, m.eigeneAnzahl());
        assertTrue(m.find(schirmMit(eigen), "ok_knopf", 0.99).isPresent(), "die eigene Aufnahme gilt");
        assertEquals(1, m.eigeneZuruecknehmen("Test"));
        m.neuesBild();
        assertTrue(m.find(schirmMit(original), "ok_knopf", 0.99).isPresent(), "nach dem Rueckfall wieder das Original");
    }
}
