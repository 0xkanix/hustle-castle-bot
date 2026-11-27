package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static de.hustlebot.bot.CathedralMode.Infokasten.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 15.11. / F7Y7 (14.11., 10:45 und 11:16): Nach einem Wegpunkt-Klick kam das Fenster "Altar of Blood" erst nach
 * ~0,8 s. Der Bot klickte in der Zwischenzeit den naechsten Wegpunkt -- mitten ins Altar-Fenster auf das Allianz-Symbol
 * eines Kaempfers. Darueber standen die Infokaesten "Talent Shaman" und "Alliance Brethren", "Move on" war verdeckt,
 * 40 s lang wurde nichts erkannt. Das Spielbild des Berichts liegt (als JPG) unter kathedrale/, die Vorlagen als Kopie
 * aus templates/ daneben (Autors Bilder bleiben unberuehrt).
 */
class KathedraleInfokastenTest {

    private static final Path ORDNER = ordner();

    private static Path ordner() {
        try {
            return Path.of(KathedraleInfokastenTest.class.getResource("/kathedrale/dc_close_x.png").toURI()).getParent();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Matcher matcher(Path tmp) throws Exception {
        for (String n : new String[]{"dc_close_x.png", "dc_finish_selection.png", "dc_move_on.png",
                "dc_claim_reward.png"}) {
            Files.copy(ORDNER.resolve(n), tmp.resolve(n));
        }
        Matcher m = new Matcher(tmp);
        m.loadAll();
        assertTrue(m.has("dc_close_x") && m.has("dc_finish_selection") && m.has("dc_move_on")
                && m.has("dc_claim_reward"));
        return m;
    }

    private static Mat bild(Path pfad) {
        Mat m = Imgcodecs.imread(pfad.toString(), Imgcodecs.IMREAD_COLOR);
        assertFalse(m.empty(), pfad.toString());
        return m;
    }

    /** Das Haengerbild: Altar-Fenster offen, Wahl verdeckt -- erkannt, angetippt wird eine freie Stelle. */
    @Test
    void altarMitInfokaestenWirdErkannt(@TempDir Path tmp) throws Exception {
        Matcher m = matcher(tmp);
        Mat b = bild(ORDNER.resolve("altar-infokaesten-f7y7.jpg"));
        try {
            Optional<Matcher.Hit> kreuz = m.find(b, CathedralMode.T_DC_CLOSE_X);
            boolean finishDa = m.find(b, "dc_finish_selection").isPresent();
            boolean wahlDa = m.find(b, "dc_move_on").isPresent() || m.find(b, "dc_claim_reward").isPresent();
            assertTrue(kreuz.isPresent(), "rotes X des Fensters nicht gefunden");
            assertTrue(kreuz.get().centerX() > 1000 && kreuz.get().centerY() < 200, "X oben rechts: " + kreuz.get());
            assertTrue(finishDa, "Finish selection nicht gefunden");
            assertFalse(wahlDa, "Move on ist auf dem Bild verdeckt -- genau der Haenger");

            assertEquals(ANTIPPEN, CathedralMode.verdecktesWahlfenster(finishDa, true, wahlDa, 1000, 0));

            // Die freie Stelle: Fenstermitte unter dem Titelband -- ausserhalb beider Infokaesten und der Kaempferreihe.
            int[] p = CathedralMode.infokastenTippStelle(kreuz.get(), b.cols());
            assertEquals(579, p[0], 3);
            assertTrue(p[1] > 115 && p[1] < 150, "unter dem Titelband, ueber \"Choose an action:\": " + p[1]);
            assertFalse(p[0] > 375 && p[0] < 785 && p[1] > 250 && p[1] < 520, "im Kasten \"Talent Shaman\"");
            assertFalse(p[0] > 800 && p[0] < 1105 && p[1] > 255 && p[1] < 515, "im Kasten \"Alliance Brethren\"");
        } finally {
            b.release();
        }
    }

    @Test
    void entscheidungImVerdecktenFenster() {
        // Wahl zu sehen (normales Fenster) oder kein Fenster: nichts tun.
        assertEquals(NICHTS, CathedralMode.verdecktesWahlfenster(true, true, true, 5000, 0));
        assertEquals(NICHTS, CathedralMode.verdecktesWahlfenster(false, true, false, 5000, 0));
        assertEquals(NICHTS, CathedralMode.verdecktesWahlfenster(true, false, false, 5000, 0));
        // Auf- und Zublenden: erst wenn es ruhig steht.
        assertEquals(WARTEN, CathedralMode.verdecktesWahlfenster(true, true, false, 0, 0));
        assertEquals(WARTEN, CathedralMode.verdecktesWahlfenster(true, true, false,
                CathedralMode.INFOKASTEN_RUHE_MS - 1, 0));
        // Dann antippen, nach INFOKASTEN_TIPPS_MAX Versuchen mit dem roten X schliessen (nie opfern).
        for (int t = 0; t < CathedralMode.INFOKASTEN_TIPPS_MAX; t++) {
            assertEquals(ANTIPPEN, CathedralMode.verdecktesWahlfenster(true, true, false,
                    CathedralMode.INFOKASTEN_RUHE_MS, t), "Tipp " + t);
        }
        assertEquals(SCHLIESSEN, CathedralMode.verdecktesWahlfenster(true, true, false, 5000,
                CathedralMode.INFOKASTEN_TIPPS_MAX));
    }

    /** Nach dem Wegpunkt-Klick: solange nur die Karte zu sehen ist, bis zu WEGPUNKT_FENSTER_MS warten. */
    @Test
    void nachWegpunktKlickAufFensterWarten() {
        long g = CathedralMode.WEGPUNKT_FENSTER_MS;
        // Der Haenger: 773 ms bzw. 1036 ms nach dem ersten Klick stand der zweite -- jetzt wird da noch gewartet.
        assertTrue(CathedralMode.aufWegpunktFensterWarten(true, 773, g));
        assertTrue(CathedralMode.aufWegpunktFensterWarten(true, 1036, g));
        assertTrue(CathedralMode.aufWegpunktFensterWarten(true, 0, g));
        // Danach wie bisher weiter (der Klick ging ins Leere).
        assertFalse(CathedralMode.aufWegpunktFensterWarten(true, g, g));
        assertFalse(CathedralMode.aufWegpunktFensterWarten(true, g + 5000, g));
        // Dazwischen war etwas anderes zu sehen (Fenster, Kampf): die Karte ist neu, gleich weiter.
        assertFalse(CathedralMode.aufWegpunktFensterWarten(false, 100, g));
        // Uhr verstellt: nicht haengen bleiben.
        assertFalse(CathedralMode.aufWegpunktFensterWarten(true, -50, g));
    }
}
