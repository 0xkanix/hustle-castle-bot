package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import de.hustlebot.core.ScreenCapture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste A01): feste Suchplaetze fuer Banner und Fenster-Bilder der Arena.
 * Die Kaesten muessen dasselbe Ergebnis liefern wie die Suche im ganzen Bild -- an echten Arena-Fotos geprueft.
 */
class ArenaKaestenTest {

    private static final Path LOGS = Path.of("C:/HustleBot/logs");
    private static final String[][] VORLAGEN = {
            {"ar_defeat", "/nachschub/arena/anzeigen/ar_defeat.png"},
            {"ar_dwellers", "/nachschub/arena/einheiten-pruefung/ar_dwellers.png"},
            {"ar_item_fenster", "/nachschub/arena/einheiten-pruefung/ar_item_fenster.png"},
            {"ar_pal_titel", "/nachschub/gelernt/arena/pals/ar_pal_titel.png"},
            {"ar_schirm_titel", "/nachschub/gelernt/arena/schirmherrschaften/ar_schirm_titel.png"},
    };

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @BeforeEach
    void leeren() {
        ArenaKaesten.zuruecksetzen();
    }

    private static Matcher matcher() throws Exception {
        Path ordner = Files.createTempDirectory("kaesten");
        for (String[] v : VORLAGEN) {
            try (InputStream in = ArenaKaestenTest.class.getResourceAsStream(v[1])) {
                assertNotNull(in, "Vorlage fehlt im Programm: " + v[1]);
                Files.copy(in, ordner.resolve(v[0] + ".png"));
            }
        }
        Matcher m = new Matcher(ordner);
        m.loadAll();
        for (String[] v : VORLAGEN) assertTrue(m.has(v[0]), v[0]);
        return m;
    }

    @Test
    void kaestenWerdenMitDemFensterUmgerechnet() {
        Rect r = ArenaKaesten.kaesten("ar_defeat", 1159, 770).get(0);
        assertEquals(439, r.x);
        assertEquals(126, r.y);
        assertEquals(275, r.width);
        assertEquals(130, r.height);
        Rect halb = ArenaKaesten.kaesten("ar_defeat", 580, 385).get(0);
        assertEquals(219, halb.x);
        assertEquals(63, halb.y);
        assertTrue(halb.width >= 137 && halb.width <= 138);
        assertTrue(ArenaKaesten.kaesten("update_button", 1159, 770).isEmpty(), "update_button/other_device schuetzen Autor am Handy -- nie im Kasten");
        assertTrue(ArenaKaesten.kaesten("other_device", 1159, 770).isEmpty());
        assertTrue(ArenaKaesten.kaesten("ar_defeat", 0, 0).isEmpty());
    }

    /** Ein Bild aus Rauschen und eine Vorlage, die darin an (x, y) eingesetzt wird -- keine echten Spielbilder noetig. */
    private static Mat bildMitVorlage(Mat vorlage, int x, int y) {
        Mat bild = new Mat(770, 1159, CvType.CV_8UC3);
        Core.randu(bild, 0, 255);
        if (x >= 0) vorlage.copyTo(bild.submat(new Rect(x, y, vorlage.cols(), vorlage.rows())));
        return bild;
    }

    @Test
    void imKastenGefundenOhneGanzesBild() throws Exception {
        Matcher m = matcher();
        Mat vorlage = Imgcodecs.imdecode(new org.opencv.core.MatOfByte(
                ArenaKaestenTest.class.getResourceAsStream("/nachschub/arena/anzeigen/ar_defeat.png").readAllBytes()),
                Imgcodecs.IMREAD_COLOR);
        Mat bild = bildMitVorlage(vorlage, 519, 176);
        AtomicInteger ganz = new AtomicInteger();
        assertTrue(ArenaKaesten.sichtbar(m, bild, "ar_defeat", 0.84, () -> { ganz.incrementAndGet(); return false; }));
        assertEquals(0, ganz.get(), "ein Treffer im Kasten braucht keine Suche im ganzen Bild");
    }

    @Test
    void ausserhalbDesKastensNurDasSicherheitsnetz() throws Exception {
        Matcher m = matcher();
        Mat vorlage = Imgcodecs.imdecode(new org.opencv.core.MatOfByte(
                ArenaKaestenTest.class.getResourceAsStream("/nachschub/arena/anzeigen/ar_defeat.png").readAllBytes()),
                Imgcodecs.IMREAD_COLOR);
        AtomicInteger ganz = new AtomicInteger();
        // Das Bild sitzt weit ausserhalb des Kastens (links unten): kein Treffer im Kasten. Das erste Mal und danach
        // jedes 8. Mal wird im ganzen Bild gesucht -- und dort findet der Ersatz es.
        int gefunden = 0;
        for (int i = 0; i < 17; i++) {
            Mat bild = bildMitVorlage(vorlage, 40, 600);
            if (ArenaKaesten.sichtbar(m, bild, "ar_defeat", 0.84, () -> { ganz.incrementAndGet(); return true; })) gefunden++;
        }
        assertEquals(3, ganz.get(), "Bild 1, 9 und 17");
        assertEquals(3, gefunden);
    }

    @Test
    void imSelbenBildNurEinmalRechnen() throws Exception {
        Matcher m = matcher();
        Mat bild = bildMitVorlage(new Mat(10, 10, CvType.CV_8UC3), -1, 0);
        AtomicInteger ganz = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            ArenaKaesten.sichtbar(m, bild, "ar_pal_titel", 0.84, () -> { ganz.incrementAndGet(); return false; });
        }
        assertEquals(1, ganz.get());
    }

    @Test
    void ohneKastenGiltDieBisherigeSuche() throws Exception {
        Matcher m = matcher();
        Mat bild = bildMitVorlage(new Mat(10, 10, CvType.CV_8UC3), -1, 0);
        AtomicInteger ganz = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertTrue(ArenaKaesten.sichtbar(m, bild, "update_button", 0.84, () -> { ganz.incrementAndGet(); return true; }));
        }
        assertEquals(3, ganz.get(), "update_button: jedes Mal die bisherige Suche");
    }

    @Test
    void echteArenaFotosGleichesErgebnisWieImGanzenBild() throws Exception {
        Path dir = LOGS.resolve("fotos-arena22");
        Assumptions.assumeTrue(Files.isDirectory(dir));
        Matcher m = matcher();
        List<Path> fotos = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".png")).sorted(Comparator.comparing(Path::toString)).forEach(fotos::add);
        }
        // jedes dritte Foto reicht (Rechenzeit: jede Vorlage kostet im ganzen Bild 30 ms)
        List<Path> jedes3 = new ArrayList<>();
        for (int i = 0; i < fotos.size(); i += 3) jedes3.add(fotos.get(i));
        fotos = jedes3;
        Path links = LOGS.resolve("fotoserie/f_672_191143.png");   // Fenster der Schirmherrschaften auf der linken Seite
        if (Files.exists(links)) fotos.add(links);
        Assumptions.assumeTrue(!fotos.isEmpty());
        int geprueft = 0, treffer = 0;
        for (Path f : fotos) {
            Mat bild = ScreenCapture.toMat(ImageIO.read(f.toFile()));
            if (bild.cols() != 1159 || bild.rows() != 770) continue;
            geprueft++;
            for (String[] v : VORLAGEN) {
                boolean ganz = m.findImBereich(bild, v[0], new Rect(0, 0, bild.cols(), bild.rows()), 0.84).isPresent();
                ArenaKaesten.zuruecksetzen();   // nur der Kasten, ohne Ersatz (immer "nicht gefunden")
                boolean kasten = ArenaKaesten.sichtbar(m, bild, v[0], 0.84, () -> false);
                // Das erste Mal je Vorlage ruft das Sicherheitsnetz den Ersatz (false) -- es aendert nichts am Ergebnis.
                assertEquals(ganz, kasten, f.getFileName() + " " + v[0] + ": Kasten und ganzes Bild muessen dasselbe sagen");
                if (ganz) treffer++;
            }
            bild.release();
        }
        assertTrue(geprueft > 0);
        assertTrue(treffer > 0, "auf den Fotos muss mindestens ein Fenster zu sehen sein");
    }
}
