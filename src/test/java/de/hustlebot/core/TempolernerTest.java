package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempolernerTest {

    @Test
    void jedeFehlerfreieRundeEinPromilleSchneller(@TempDir Path ordner) {
        Tempolerner t = new Tempolerner(ordner.resolve("event-tempo.txt"), "Test");
        assertEquals(1.0, t.faktor(), 1e-9);
        t.rundeFertig(true);
        t.rundeFertig(true);
        assertEquals(0.999 * 0.999, t.faktor(), 1e-9);
        assertFalse(t.fest());
        assertEquals(998, t.ms(1000));
    }

    @Test
    void beimFehlerEineStufeVorDemSchnellstenUndFest(@TempDir Path ordner) {
        Path d = ordner.resolve("event-tempo.txt");
        Tempolerner t = new Tempolerner(d, "Test");
        for (int i = 0; i < 10; i++) t.rundeFertig(true);
        double schnellsteFehlerfrei = Math.pow(0.999, 9);   // vor der Runde mit dem Fehler
        t.rundeFertig(false);
        assertTrue(t.fest());
        assertEquals(schnellsteFehlerfrei / 0.999, t.faktor(), 1e-9);
        t.rundeFertig(true);   // fest: bleibt
        assertEquals(schnellsteFehlerfrei / 0.999, t.faktor(), 1e-9);
        // gemerkt fuer den naechsten Start
        Tempolerner neu = new Tempolerner(d, "Test");
        assertTrue(neu.fest());
        assertEquals(t.faktor(), neu.faktor(), 1e-4);
    }

    @Test
    void nurKlickFehlerZaehlen() {
        assertTrue(Tempolerner.zaehltAlsFehler("The click on \"Map\" did not register -- the castle is still visible. Pressing it again"));
        assertTrue(Tempolerner.zaehltAlsFehler("Leuchtturm: Quick Attack verschluckt"));
        assertFalse(Tempolerner.zaehltAlsFehler("Nothing recognized for 24 frames. Last progress: Run completed 3 seconds ago."));
        assertFalse(Tempolerner.zaehltAlsFehler("No waypoint on the floor is clickable (1 question marks, 0 locks)."));
        assertFalse(Tempolerner.zaehltAlsFehler(null));
    }

    @Test
    void kaputteDateiGibtNormaltempo(@TempDir Path ordner) throws Exception {
        Path d = ordner.resolve("event-tempo.txt");
        Files.writeString(d, "quatsch\n");
        assertEquals(1.0, new Tempolerner(d, "Test").faktor(), 1e-9);
    }
}
