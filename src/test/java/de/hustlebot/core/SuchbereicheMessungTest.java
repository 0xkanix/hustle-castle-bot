package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sparsam 20.11.: Findet die Suche im festen Bereich (Suchbereiche) dasselbe wie die Suche im ganzen Bild? Gemessen an
 * echten Spielbildern aus den Freunde-Berichten (C:/hustle-bot/fehlerberichte/freunde/.../spielbild.png) mit den
 * Vorlagen aus Autors Bot (C:/HustleBot/templates). Laeuft nur, wenn beides da ist. Ein Faden, damit der Rechner
 * neben dem laufenden Bot ruhig bleibt.
 *
 * Aufruf: mvn-reihe.sh -Dtest=SuchbereicheMessungTest -Dsuchbereiche.messen=true [-Dsuchbereiche.jedes=8 -Dsuchbereiche.ab=3
 * -Dsuchbereiche.grau=true]. Ergebnisse 20.11.: 1. Durchgang (62 Bilder, Farbe) Bereich 4 % der Zeit, 24 Vorlagen ausserhalb;
 * 2. Durchgang (193 andere Bilder, grau) 8 %, 18 weitere ausserhalb -- alle 42 aus suchbereiche.txt genommen.
 *
 * Kriterium: jede Fundstelle ab 0,70 im ganzen Bild muss im Bereich liegen -- dann ist der Bereichswert exakt derselbe.
 */
class SuchbereicheMessungTest {

    private static final Path BERICHTE = Path.of("C:/hustle-bot/fehlerberichte/freunde");
    private static final Path VORLAGEN = Path.of("C:/HustleBot/templates");
    /** Jedes wievielte Spielbild (1545 vorhanden). */
    private static final int JEDES = Integer.getInteger("suchbereiche.jedes", 25);
    /** Ab welchem Spielbild (fuer einen zweiten Durchgang mit anderen Bildern). */
    private static final int AB = Integer.getInteger("suchbereiche.ab", 0);
    /** In Graustufen (dreimal schneller -- fuer grosse Pruefungen; echte Funde liegen grau wie farbig hoch). */
    private static final boolean GRAU = Boolean.getBoolean("suchbereiche.grau");

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

    @Test
    void bereichFindetDasselbeWieDasGanzeBild() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("suchbereiche.messen"), "nur auf Wunsch: -Dsuchbereiche.messen=true (dauert 8-15 min)");
        Assumptions.assumeTrue(Files.isDirectory(BERICHTE) && Files.isDirectory(VORLAGEN), "keine Messdaten auf diesem Rechner");
        // Die zweite Surefire-Ausfuehrung (speicher-pruefung, pom.xml) wuerde die lange Messung bei -Dtest=... wiederholen.
        Assumptions.assumeFalse(Path.of("").toAbsolutePath().endsWith("speicher-pruefung"), "nur einmal messen");
        Core.setNumThreads(1);
        Map<String, Mat> vorlagen = new HashMap<>();
        try (Stream<Path> s = Files.walk(VORLAGEN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".png")).sorted().toList()) {
                String n = p.getFileName().toString().replace(".png", "");
                if (!Suchbereiche.hat(n) || vorlagen.containsKey(n)) continue;
                Mat m = Imgcodecs.imread(p.toString(), GRAU ? Imgcodecs.IMREAD_GRAYSCALE : Imgcodecs.IMREAD_COLOR);
                if (!m.empty()) vorlagen.put(n, m);
            }
        }
        List<Path> bilder;
        try (Stream<Path> s = Files.walk(BERICHTE, 2)) {
            bilder = s.filter(x -> x.getFileName().toString().equals("spielbild.png")).sorted().toList();
        }
        long nsGanz = 0, nsBereich = 0, paare = 0, funde = 0;
        List<String> ausserhalb = new ArrayList<>();
        Map<String, long[]> jeVorlage = new HashMap<>();
        for (int i = AB; i < bilder.size(); i += JEDES) {
            Mat bild = Imgcodecs.imread(bilder.get(i).toString(), GRAU ? Imgcodecs.IMREAD_GRAYSCALE : Imgcodecs.IMREAD_COLOR);
            if (bild.empty()) continue;
            for (var e : vorlagen.entrySet()) {
                Mat v = e.getValue();
                Rect r = Suchbereiche.bereich(e.getKey(), bild.width(), bild.height(), v.width(), v.height());
                if (r == null) continue;
                long t0 = System.nanoTime();
                double[] ganz = bester(bild, v);
                long t1 = System.nanoTime();
                Mat aus = bild.submat(r);
                double[] imBereich = bester(aus, v);
                long t2 = System.nanoTime();
                aus.release();
                nsGanz += t1 - t0;
                nsBereich += t2 - t1;
                paare++;
                jeVorlage.computeIfAbsent(e.getKey(), k -> new long[2])[0] += t1 - t0;
                jeVorlage.get(e.getKey())[1] += t2 - t1;
                if (ganz[0] < 0.70) continue;
                funde++;
                boolean drin = ganz[1] >= r.x && ganz[2] >= r.y
                        && ganz[1] + v.width() <= r.x + r.width && ganz[2] + v.height() <= r.y + r.height;
                if (!drin) {
                    ausserhalb.add(e.getKey() + " " + String.format("%.3f", ganz[0]) + " bei " + (int) ganz[1] + "," + (int) ganz[2]
                            + " (Bereich " + r + ", dort " + String.format("%.3f", imBereich[0]) + ") in " + bilder.get(i).getParent().getFileName());
                } else {
                    assertEquals(ganz[0], imBereich[0], 1e-4, "gleicher Wert im Bereich: " + e.getKey());
                    assertEquals(ganz[1], imBereich[1] + r.x, 0.5, e.getKey());
                    assertEquals(ganz[2], imBereich[2] + r.y, 0.5, e.getKey());
                }
            }
            bild.release();
        }
        System.out.println("MESSUNG Suchbereiche: " + vorlagen.size() + " Vorlagen, " + paare + " Vergleiche; ganzes Bild "
                + nsGanz / 1_000_000 + " ms, im Bereich " + nsBereich / 1_000_000 + " ms (" + (nsGanz == 0 ? 0 : 100 * nsBereich / nsGanz)
                + " %); Fundstellen ab 0,70: " + funde + ", davon ausserhalb des Bereichs: " + ausserhalb.size());
        jeVorlage.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(12)
                .forEach(e -> System.out.println("MESSUNG   " + e.getKey() + ": ganz " + e.getValue()[0] / 1_000_000 + " ms, Bereich "
                        + e.getValue()[1] / 1_000_000 + " ms"));
        ausserhalb.forEach(a -> System.out.println("MESSUNG AUSSERHALB " + a));
    }
}
