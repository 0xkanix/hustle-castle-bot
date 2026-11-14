package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 28.10.: Namensausschnitte + richtiger Name fuer ein spaeteres PaddleOCR-Nachtraining. */
class OcrTrainingsdatenTest {

    /** Ein kleines Bild, das sich je Nummer unterscheidet. */
    static BufferedImage bild(int n) {
        BufferedImage b = new BufferedImage(60, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 60; x++) b.setRGB(x, y, 0xff000000 | ((x * 4 + n * 7) << 8) | (y * 9 + n));
        return b;
    }

    @Test
    void bildUndZeileImPaddleFormat(@TempDir Path tmp) throws Exception {
        Path ordner = tmp.resolve("logs").resolve("ocr-training");
        OcrTrainingsdaten d = new OcrTrainingsdaten(ordner);
        assertTrue(d.merken(bild(1), "  Королева\t"));
        List<String> zeilen = Files.readAllLines(ordner.resolve(OcrTrainingsdaten.LABELS), StandardCharsets.UTF_8);
        assertEquals(1, zeilen.size());
        String[] t = zeilen.get(0).split("\t", -1);
        assertEquals(2, t.length, "bild.png TAB Name");
        assertTrue(t[0].matches("000001_[0-9a-f]{8}\\.png"), t[0]);
        assertEquals("Королева", t[1]);
        BufferedImage gelesen = ImageIO.read(ordner.resolve(t[0]).toFile());
        assertEquals(60, gelesen.getWidth());
        assertEquals(20, gelesen.getHeight());
        try (Stream<Path> s = Files.list(ordner)) {
            assertEquals(2, s.count(), "keine Reste (.neu)");
        }
    }

    @Test
    void hoechstensDreiJeSpielerUndKeinBildDoppelt(@TempDir Path tmp) {
        OcrTrainingsdaten d = new OcrTrainingsdaten(tmp);
        assertTrue(d.merken(bild(1), "Xenos"));
        assertFalse(d.merken(bild(1), "Xenos"), "dasselbe Bild nicht zweimal");
        assertTrue(d.merken(bild(2), "xenos"));
        assertTrue(d.merken(bild(3), "XENOS"));
        assertFalse(d.merken(bild(4), "Xenos"), "vierter Ausschnitt desselben Spielers");
        assertEquals(3, d.anzahl("Xenos"));
        assertTrue(d.merken(bild(4), "IDKFA"));
        assertEquals(4, d.anzahl());
    }

    @Test
    void gesamtGrenze(@TempDir Path tmp) {
        OcrTrainingsdaten d = new OcrTrainingsdaten(tmp, 3, 5);
        for (int i = 0; i < 5; i++) assertTrue(d.merken(bild(i), "Spieler" + i));
        assertFalse(d.merken(bild(9), "Spieler9"));
        assertEquals(5, d.anzahl());
    }

    @Test
    void unbrauchbareNamenUndBilder(@TempDir Path tmp) {
        OcrTrainingsdaten d = new OcrTrainingsdaten(tmp);
        assertFalse(d.merken(bild(1), ""));
        assertFalse(d.merken(bild(1), null));
        assertFalse(d.merken(bild(1), "  \t "));
        assertFalse(d.merken(bild(1), "x".repeat(26)), "PaddleOCR nimmt nur bis 25 Zeichen");
        assertTrue(d.merken(bild(1), "x".repeat(25)));
        assertFalse(d.merken(null, "Name"));
        assertFalse(d.merken(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "Winzig"));
    }

    @Test
    void nachNeustartGeltenDieGrenzenWeiter(@TempDir Path tmp) throws Exception {
        OcrTrainingsdaten a = new OcrTrainingsdaten(tmp);
        assertTrue(a.merken(bild(1), "Xenos"));
        assertTrue(a.merken(bild(2), "Xenos"));
        OcrTrainingsdaten b = new OcrTrainingsdaten(tmp);
        assertEquals(2, b.anzahl("Xenos"));
        assertFalse(b.merken(bild(2), "Xenos"), "Pruefsumme steht im Dateinamen");
        assertTrue(b.merken(bild(3), "Xenos"));
        assertFalse(b.merken(bild(4), "Xenos"));
        List<String> zeilen = Files.readAllLines(tmp.resolve(OcrTrainingsdaten.LABELS), StandardCharsets.UTF_8);
        assertEquals(3, zeilen.size());
        assertTrue(zeilen.get(2).startsWith("000003_"), "Nummer laeuft weiter: " + zeilen.get(2));
    }

    @Test
    void schreibfehlerWirftNicht(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ist-eine-datei");
        Files.writeString(datei, "x");
        OcrTrainingsdaten d = new OcrTrainingsdaten(datei.resolve("ocr-training"));
        assertFalse(assertDoesNotThrow(() -> d.merken(bild(1), "Xenos")));
        assertEquals(0, d.anzahl());
    }

    @Test
    void vieleFaedenGleichzeitig(@TempDir Path tmp) throws Exception {
        OcrTrainingsdaten d = new OcrTrainingsdaten(tmp);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> f = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            f.add(pool.submit(() -> {
                for (int j = 0; j < 5; j++) d.merken(bild(n * 10 + j), "Spieler" + n);
            }));
        }
        for (Future<?> x : f) x.get();
        pool.shutdown();
        assertEquals(18, d.anzahl(), "6 Spieler x hoechstens 3");
        List<String> zeilen = Files.readAllLines(tmp.resolve(OcrTrainingsdaten.LABELS), StandardCharsets.UTF_8);
        assertEquals(18, zeilen.size());
        for (String z : zeilen) assertTrue(Files.exists(tmp.resolve(z.substring(0, z.indexOf('\t')))), z);
    }
}
