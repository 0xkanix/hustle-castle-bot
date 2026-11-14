package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Bugjagd 29.10.: 1078 von 1611 Warnungen in drei Tagen waren dieselben acht Doppelnamen aus Autors eigenen Ordnern
 * (unit_1..6, dc_claim_reward), bei jedem Laden neu. Eine Warnung soll es nur geben, wenn der Spieler selbst ein Bild
 * mit schon vorhandenem Namen aufgenommen hat ("My pictures").
 */
class VorlagenDoppelNamenTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @AfterEach
    void aufraeumen() {
        Matcher.EIGENE_BILDER = null;
    }

    private static void bild(Path datei, int grau) throws Exception {
        Files.createDirectories(datei.getParent());
        Mat m = new Mat(12, 12, CvType.CV_8UC3, new Scalar(grau, grau, grau));
        assertTrue(Imgcodecs.imwrite(datei.toString(), m));
        m.release();
    }

    private static long warnungenDoppelt(int ab) {
        List<Log.Entry> l = Log.snapshot();
        return l.subList(Math.min(ab, l.size()), l.size()).stream()
                .filter(e -> e.level() == Log.Level.WARN && e.message().contains("Name used twice")
                        || e.level() == Log.Level.WARN && e.message().contains("Name doppelt vergeben"))
                .count();
    }

    @Test
    void doppelteNamenInEigenenOrdnernWarnenNicht(@TempDir Path wurzel) throws Exception {
        bild(wurzel.resolve("cathedral/dc_x.png"), 40);
        bild(wurzel.resolve("dungeon/dc_x.png"), 90);
        int ab = Log.size();
        Matcher m = new Matcher(wurzel);
        assertEquals(1, m.loadAll());
        assertEquals(0, warnungenDoppelt(ab), "ein stehender Doppelname ist keine Warnung");
        boolean zusammenfassung = Log.snapshot().stream().skip(ab)
                .anyMatch(e -> e.level() == Log.Level.INFO && e.message().contains("dc_x")
                        && (e.message().contains("two of the bot") || e.message().contains("zwei eigenen")));
        assertTrue(zusammenfassung, "eine einzige Info-Zeile nennt den Namen");
    }

    @Test
    void eigeneAufnahmeMitSchonVorhandenemNamenWarntWeiter(@TempDir Path wurzel, @TempDir Path eigene) throws Exception {
        bild(wurzel.resolve("cathedral/dc_y.png"), 40);
        bild(eigene.resolve("Cathedral/dc_y.png"), 200);
        Matcher.EIGENE_BILDER = eigene;
        int ab = Log.size();
        Matcher m = new Matcher(wurzel);
        m.loadAll();
        assertTrue(warnungenDoppelt(ab) >= 1, "hat der Spieler selbst aufgenommen, soll die Warnung bleiben");
    }
}
