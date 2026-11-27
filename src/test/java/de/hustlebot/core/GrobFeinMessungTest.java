package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * NUR MESSUNG (21.11., Auftrag Punkt 3, noch nicht eingebaut): Wuerde eine Suche "erst grob im halb so grossen Bild,
 * dann nur am Fundort fein in voller Groesse" dasselbe finden wie die Suche im ganzen Bild? Gemessen an echten Bildern
 * (Spielbilder + Fotoserien aus Freunde-Berichten, Etagenbilder) mit Autors Vorlagen, in Graustufen wie im Bot.
 * Kriterium (Autor): jede Fundstelle ab 0,70 im ganzen Bild muss grob/fein auf 2 Punkte genau und mit weniger als 0,01
 * Unterschied im Wert herauskommen. Aendert nichts am Bot.
 *
 * Aufruf: mvn-reihe.sh -Dtest=GrobFeinMessungTest -Dgrobfein.messen=true [-Dgrobfein.jedes=40]
 */
class GrobFeinMessungTest {

    private static final Path VORLAGEN = Path.of("C:/HustleBot/templates");
    private static final int JEDES = Integer.getInteger("grobfein.jedes", 40);
    /** So viele beste Stellen des groben Bildes werden fein nachgerechnet. */
    private static final int KANDIDATEN = 3;
    /** Luft um die grobe Stelle beim Nachrechnen (volle Groesse). */
    private static final int LUFT = 4;

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static double[] bester(Mat bild, Mat vorlage) {
        Mat r = new Mat();
        try {
            Imgproc.matchTemplate(bild, vorlage, r, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(r);
            return new double[]{mm.maxVal, mm.maxLoc.x, mm.maxLoc.y};
        } finally {
            r.release();
        }
    }

    /** Grob im halben Bild die besten Stellen, jede fein in voller Groesse nachgerechnet; der beste feine Wert. */
    private static double[] grobFein(Mat bild, Mat halbesBild, Mat vorlage, Mat halbeVorlage) {
        Mat r = new Mat();
        double[] beste = {-2, 0, 0};
        try {
            Imgproc.matchTemplate(halbesBild, halbeVorlage, r, Imgproc.TM_CCOEFF_NORMED);
            for (int k = 0; k < KANDIDATEN; k++) {
                Core.MinMaxLocResult mm = Core.minMaxLoc(r);
                int gx = (int) mm.maxLoc.x, gy = (int) mm.maxLoc.y;
                Imgproc.rectangle(r, new Point(gx - halbeVorlage.width() / 2.0, gy - halbeVorlage.height() / 2.0),
                        new Point(gx + halbeVorlage.width() / 2.0, gy + halbeVorlage.height() / 2.0), new Scalar(-1), -1);
                int x0 = Math.max(0, 2 * gx - LUFT), y0 = Math.max(0, 2 * gy - LUFT);
                int x1 = Math.min(bild.width(), 2 * gx + vorlage.width() + LUFT), y1 = Math.min(bild.height(), 2 * gy + vorlage.height() + LUFT);
                if (x1 - x0 < vorlage.width() || y1 - y0 < vorlage.height()) continue;
                Mat aus = bild.submat(new Rect(x0, y0, x1 - x0, y1 - y0));
                double[] f = bester(aus, vorlage);
                aus.release();
                if (f[0] > beste[0]) beste = new double[]{f[0], f[1] + x0, f[2] + y0};
            }
        } finally {
            r.release();
        }
        return beste;
    }

    @Test
    void grobFeinFindetDasselbeWieDasGanzeBild() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("grobfein.messen"), "nur auf Wunsch: -Dgrobfein.messen=true");
        Assumptions.assumeTrue(Files.isDirectory(VORLAGEN), "keine Vorlagen auf diesem Rechner");
        Assumptions.assumeFalse(Path.of("").toAbsolutePath().endsWith("speicher-pruefung"), "nur einmal messen");
        Core.setNumThreads(2);
        Map<String, Mat[]> vorlagen = new LinkedHashMap<>();
        int zuKlein = 0;
        try (Stream<Path> s = Files.walk(VORLAGEN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".png")).sorted().toList()) {
                String n = p.getFileName().toString().replace(".png", "");
                if (vorlagen.containsKey(n)) continue;
                Mat m = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_GRAYSCALE);
                if (m.empty()) continue;
                if (m.width() < 16 || m.height() < 16) {
                    zuKlein++;
                    continue;
                }
                Mat h = new Mat();
                Imgproc.resize(m, h, new Size(), 0.5, 0.5, Imgproc.INTER_AREA);
                vorlagen.put(n, new Mat[]{m, h});
            }
        }
        List<Path> bilder = new ArrayList<>();
        for (Path o : List.of(Path.of("C:/HustleBot/logs/etagen"), Path.of("C:/hustle-bot/fehlerberichte/freunde"))) {
            if (!Files.isDirectory(o)) continue;
            try (Stream<Path> s = Files.walk(o, 3)) {
                s.filter(p -> p.getFileName().toString().equals("spielbild.png") || p.toString().endsWith(".jpg")
                        || (p.getParent() != null && p.getParent().getFileName().toString().equals("etagen") && p.toString().endsWith(".png")))
                        .sorted().forEach(bilder::add);
            }
        }
        Assumptions.assumeFalse(bilder.isEmpty(), "keine Bilder auf diesem Rechner");
        long nsGanz = 0, nsGrobFein = 0, paare = 0, funde = 0, fehl = 0;
        Map<String, int[]> jeVorlage = new TreeMap<>();   // {Funde, Fehler}
        List<String> fehler = new ArrayList<>();
        int bildZahl = 0;
        for (int i = 0; i < bilder.size(); i += JEDES) {
            Mat bild = Imgcodecs.imread(bilder.get(i).toString(), Imgcodecs.IMREAD_GRAYSCALE);
            if (bild.empty()) continue;
            bildZahl++;
            long h0 = System.nanoTime();
            Mat halb = new Mat();
            Imgproc.resize(bild, halb, new Size(), 0.5, 0.5, Imgproc.INTER_AREA);
            nsGrobFein += System.nanoTime() - h0;   // einmal je Bild
            for (var e : vorlagen.entrySet()) {
                Mat v = e.getValue()[0], hv = e.getValue()[1];
                if (v.width() > bild.width() || v.height() > bild.height()) continue;
                long t0 = System.nanoTime();
                double[] ganz = bester(bild, v);
                long t1 = System.nanoTime();
                double[] gf = grobFein(bild, halb, v, hv);
                long t2 = System.nanoTime();
                nsGanz += t1 - t0;
                nsGrobFein += t2 - t1;
                paare++;
                if (ganz[0] < 0.70) continue;
                funde++;
                int[] z = jeVorlage.computeIfAbsent(e.getKey(), k -> new int[2]);
                z[0]++;
                boolean gleich = Math.abs(ganz[1] - gf[1]) <= 2 && Math.abs(ganz[2] - gf[2]) <= 2 && ganz[0] - gf[0] < 0.01;
                if (!gleich) {
                    fehl++;
                    z[1]++;
                    if (fehler.size() < 40) fehler.add(String.format("%s ganz %.3f bei %d,%d / grob-fein %.3f bei %d,%d in %s", e.getKey(),
                            ganz[0], (int) ganz[1], (int) ganz[2], gf[0], (int) gf[1], (int) gf[2], bilder.get(i).getParent().getFileName()));
                }
            }
            halb.release();
            bild.release();
        }
        int sicher = 0, unsicher = 0;
        StringBuilder unsichere = new StringBuilder();
        for (var e : jeVorlage.entrySet()) {
            if (e.getValue()[1] == 0) sicher++;
            else {
                unsicher++;
                unsichere.append(e.getKey()).append(' ').append(e.getValue()[1]).append('/').append(e.getValue()[0]).append("  ");
            }
        }
        System.out.printf("MESSUNG grob/fein: %d Bilder, %d Vorlagen (%d unter 16 px ausgelassen), %d Vergleiche; ganzes Bild %d ms,"
                        + " grob/fein %d ms (%d %%); Fundstellen ab 0,70: %d, davon abweichend %d; Vorlagen mit Funden: %d ohne, %d mit"
                        + " Abweichung%n", bildZahl, vorlagen.size(), zuKlein, paare, nsGanz / 1_000_000, nsGrobFein / 1_000_000,
                nsGanz == 0 ? 0 : 100 * nsGrobFein / nsGanz, funde, fehl, sicher, unsicher);
        System.out.println("  abweichend (Fehler/Funde): " + unsichere);
        fehler.forEach(f -> System.out.println("  " + f));
    }
}
