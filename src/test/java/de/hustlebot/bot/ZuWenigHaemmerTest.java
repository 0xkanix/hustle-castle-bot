package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste B10/B11): Das Fenster "Not enough hammers?" wird an seiner Ueberschrift erkannt -- auf den
 * Event-Fotos mit diesem Fenster ja, auf allen anderen Event-Bildern nie.
 */
class ZuWenigHaemmerTest {

    private static final Path LOGS = Path.of("C:/HustleBot/logs");
    private static final String VORLAGE = "/nachschub/gelernt/event/ev_zu_wenig_haemmer.png";

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Ein Matcher, der nur die Ueberschrift kennt (aus dem Programm in einen Ordner gelegt). */
    private static Matcher matcher() throws Exception {
        Path ordner = Files.createTempDirectory("zuwenig");
        try (InputStream in = ZuWenigHaemmerTest.class.getResourceAsStream(VORLAGE)) {
            assertNotNull(in, "Vorlage fehlt im Programm: " + VORLAGE);
            Files.copy(in, ordner.resolve("ev_zu_wenig_haemmer.png"));
        }
        Matcher m = new Matcher(ordner);
        m.loadAll();
        assertTrue(m.has("ev_zu_wenig_haemmer"));
        return m;
    }

    @Test
    void vorlageLiegtImProgrammUndLaesstSichLaden() throws Exception {
        Matcher m = matcher();
        assertEquals(392, m.sizes().get("ev_zu_wenig_haemmer")[0]);
        assertEquals(48, m.sizes().get("ev_zu_wenig_haemmer")[1]);
    }

    @Test
    void leeresBildHatDasFensterNicht() throws Exception {
        Matcher m = matcher();
        Mat grau = new Mat(770, 1159, org.opencv.core.CvType.CV_8UC3, new org.opencv.core.Scalar(90, 60, 40));
        try {
            assertFalse(EventMode.zuWenigHaemmerFenster(m, grau));
        } finally {
            grau.release();
        }
    }

    @Test
    void echteFotos() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(LOGS));
        Matcher m = matcher();
        List<Path> fotos = new ArrayList<>();
        try (Stream<Path> s = Files.list(LOGS)) {
            s.filter(p -> p.getFileName().toString().matches("event-(ende|stab|fenster)-\\d+\\.png"))
                    .sorted(Comparator.comparing(Path::toString)).forEach(fotos::add);
        }
        Assumptions.assumeTrue(!fotos.isEmpty());

        StringBuilder out = new StringBuilder();
        int mit = 0, ohne = 0, n = 0;
        long ns = 0;
        for (Path f : fotos) {
            Mat bild = Imgcodecs.imread(f.toString(), Imgcodecs.IMREAD_COLOR);
            if (bild.empty() || bild.width() != 1159 || bild.height() != 770) { bild.release(); continue; }
            Rect bereich = new Rect((int) (1159 * 0.25), (int) (770 * 0.09), (int) (1159 * 0.50), (int) (770 * 0.16));
            double score = m.findImBereich(bild, "ev_zu_wenig_haemmer", bereich, -1).map(Matcher.Hit::score).orElse(-1.0);
            long t0 = System.nanoTime();
            boolean da = EventMode.zuWenigHaemmerFenster(m, bild);
            ns += System.nanoTime() - t0;
            n++;
            out.append(String.format("%s  %.3f  %s%n", f.getFileName(), score, da ? "FENSTER" : "-"));
            if (da) mit++; else ohne++;
            // Die drei Fotos von 14:26-14:27 zeigen es sicher; alle "ende"/"stab" davor nie.
            String name = f.getFileName().toString();
            if (name.matches("event-fenster-1426\\d\\d\\.png|event-fenster-1427\\d\\d\\.png")) {
                assertTrue(da, "Fenster nicht erkannt: " + name + " (" + score + ")");
            }
            if (name.startsWith("event-ende-") || name.startsWith("event-stab-")) {
                assertFalse(da, "Fehltreffer: " + name + " (" + score + ")");
            }
            bild.release();
        }
        out.append("mit Fenster ").append(mit).append(", ohne ").append(ohne).append('\n');
        out.append(String.format("Zeit je Pruefung: %.2f ms%n", ns / 1e6 / Math.max(1, n)));
        Files.writeString(Path.of("target", "zuwenig-scores.txt"), out.toString());
    }
}
