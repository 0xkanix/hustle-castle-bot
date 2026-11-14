package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 01.11.: Beweisfotos nur auf Wunsch -- ausgeschaltet entsteht weder Datei noch Ordner. */
class BelegTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @AfterEach
    void schalterZurueck() {
        Beleg.schalterFuerTest(null);
    }

    private Path schalter(String inhalt) throws Exception {
        Path s = tmp.resolve("beweisfotos.txt");
        if (inhalt != null) Files.writeString(s, inhalt);
        Beleg.schalterFuerTest(s);
        return s;
    }

    private static BufferedImage bild() {
        return new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void ohneSchalterdateiSchreibtNichts() throws Exception {
        schalter(null);
        assertFalse(Beleg.an());
        Path ziel = tmp.resolve("neu").resolve("a.png");
        assertFalse(Beleg.schreiben(bild(), "png", ziel.toFile()));
        assertFalse(Files.exists(ziel));
        assertFalse(Files.exists(ziel.getParent()), "es darf auch kein Ordner entstehen");
    }

    @Test
    void ausgeschaltetSchreibtNichts() throws Exception {
        schalter("# nur zur Fehlersuche\naus\n");
        Path ziel = tmp.resolve("neu").resolve("a.png");
        assertFalse(Beleg.schreiben(bild(), "png", ziel.toFile()));
        Mat m = Mat.zeros(8, 8, CvType.CV_8UC3);
        assertFalse(Beleg.schreiben(ziel.toString(), m));
        m.release();
        assertFalse(Files.exists(ziel.getParent()));
    }

    @Test
    void eingeschaltetSchreibtUndLegtDenOrdnerAn() throws Exception {
        schalter("an\n");
        assertTrue(Beleg.an());
        Path ziel = tmp.resolve("neu").resolve("a.png");
        assertTrue(Beleg.schreiben(bild(), "png", ziel.toFile()));
        assertTrue(Files.size(ziel) > 0);
        Path ziel2 = tmp.resolve("neu2").resolve("b.png");
        Mat m = Mat.zeros(8, 8, CvType.CV_8UC3);
        assertTrue(Beleg.schreiben(ziel2.toString(), m));
        m.release();
        assertTrue(Files.size(ziel2) > 0);
    }

    @Test
    void schalterGiltOhneNeustart() throws Exception {
        Path s = schalter("aus\n");
        assertFalse(Beleg.an());
        Files.writeString(s, "an\n");
        assertTrue(Beleg.an());
    }

    @Test
    void sammlungNurMitSchalterSolangeKeinAutorSchluesselDaIst() throws Exception {
        // In den Tests gibt es keinen geprueften Schluessel: die Sammlung haengt dann allein am Schalter.
        schalter("aus\n");
        assertFalse(Beleg.autorsSammlung());
        schalter("an\n");
        assertTrue(Beleg.autorsSammlung());
    }
}
