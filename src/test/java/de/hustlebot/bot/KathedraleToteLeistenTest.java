package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hustlebot.core.Matcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Sparsam 21.11.: Die gefallenen Einheiten (unit_dead_bar) auf dem Truppenbildschirm der Kathedrale nur ab knapp ueber
 * der Zeile "Drag and drop units ..." suchen. Geprueft an ECHTEN Truppenbildschirmen (Fotoserien und Spielbilder aus
 * Freunde-Berichten, nur auf Autors Rechner): im Bereich muessen genau dieselben Leisten herauskommen wie im ganzen Bild.
 * Zusaetzlich gezaehlt: was ausserhalb des Bereichs schon ab 0,80 aussieht wie eine Leiste (Sicherheitsabstand).
 *
 * Nur auf Wunsch: mvn-reihe.sh -Dtest=KathedraleToteLeistenTest -Dkaesten.messen=true
 */
class KathedraleToteLeistenTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    void bereichBeginntVierzigPunkteUeberDerZeile() {
        Matcher.Hit zeile = new Matcher.Hit("dc_deploy_screen", 300, 395, 500, 20, 0.99);
        Rect r = CathedralMode.toteBereich(zeile, 1159, 770);
        assertEquals(0, r.x);
        assertEquals(365, r.y);
        assertEquals(1159, r.width);
        assertEquals(770 - 365, r.height);
        Rect g = CathedralMode.toteBereich(new Matcher.Hit("dc_deploy_screen", 600, 790, 1000, 40, 0.99), 2318, 1540);
        assertEquals(810 - 80, g.y);
    }

    @Test
    void echteTruppenbildschirmeGleicheLeistenUnterDerZeile() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("kaesten.messen"), "nur auf Wunsch: -Dkaesten.messen=true");
        Assumptions.assumeFalse(Path.of("").toAbsolutePath().endsWith("speicher-pruefung"), "nur einmal messen");
        org.opencv.core.Core.setNumThreads(2);
        List<Path> bilder = KathedraleKartenKaestenTest.berichtBilder(false);
        Assumptions.assumeFalse(bilder.isEmpty(), "keine Fotoserien/Spielbilder auf diesem Rechner");
        Path vorlagen = java.nio.file.Files.isDirectory(Path.of("C:/HustleBot/templates")) ? Path.of("C:/HustleBot/templates") : Path.of("templates");
        Matcher m = new Matcher(vorlagen);
        m.loadAll();
        Assumptions.assumeTrue(m.has("unit_dead_bar") && m.has("dc_deploy_screen"));
        int schirme = 0, leisten = 0, abweichungen = 0, knappAusserhalb = 0;
        long ganzNs = 0, bereichNs = 0;
        double besteAusserhalb = 0;
        StringBuilder fehler = new StringBuilder();
        List<String> zeilenY = new ArrayList<>();
        for (Path p : bilder) {
            Mat bild = Imgcodecs.imread(p.toString());
            if (bild.empty()) continue;
            Optional<Matcher.Hit> zeile = m.find(bild, "dc_deploy_screen", 0.84);
            if (zeile.isEmpty()) {
                bild.release();
                continue;
            }
            schirme++;
            if (zeilenY.size() < 400) zeilenY.add(bild.cols() + "x" + bild.rows() + ":" + zeile.get().centerY());
            Rect bereich = CathedralMode.toteBereich(zeile.get(), bild.cols(), bild.rows());
            long t0 = System.nanoTime();
            List<Matcher.Hit> a = m.findAll(bild, "unit_dead_bar", 0.88, 12);
            long t1 = System.nanoTime();
            List<Matcher.Hit> b = m.findAllImBereich(bild, "unit_dead_bar", 0.88, 12, bereich);
            long t2 = System.nanoTime();
            ganzNs += t1 - t0;
            bereichNs += t2 - t1;
            leisten += a.size();
            abweichungen += KathedraleKartenKaestenTest.vergleichen(a, b, p.toString(), fehler);
            // Sicherheitsabstand: was liegt AUSSERHALB des Bereichs und sieht schon ab 0,80 wie eine Leiste aus?
            for (Matcher.Hit h : m.findAll(bild, "unit_dead_bar", 0.80, 12)) {
                if (h.y() >= bereich.y) continue;
                knappAusserhalb++;
                besteAusserhalb = Math.max(besteAusserhalb, h.score());
                if (fehler.length() < 4000) fehler.append("  ausserhalb ab 0,80: ").append(p).append(' ').append(h).append('\n');
            }
            bild.release();
        }
        System.out.printf("Tote Leisten: %d Bilder, %d Truppenbildschirme, %d Leisten, %d Abweichungen, ausserhalb des Bereichs ab 0,80:"
                        + " %d (bester %.3f); je Suche ganz %.1f ms, ab der Zeile %.1f ms%n", bilder.size(), schirme, leisten, abweichungen,
                knappAusserhalb, besteAusserhalb, schirme == 0 ? 0 : ganzNs / 1e6 / schirme, schirme == 0 ? 0 : bereichNs / 1e6 / schirme);
        System.out.println("  Zeile (Bildgroesse:y): " + String.join(" ", zeilenY.stream().distinct().toList()));
        if (fehler.length() > 0) System.out.println(fehler);
        assertEquals(0, abweichungen, fehler.toString());
    }
}
