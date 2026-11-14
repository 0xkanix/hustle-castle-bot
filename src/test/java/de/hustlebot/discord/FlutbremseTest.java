package de.hustlebot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Flut-Bremse (16.10.): Foto-/Berichts-Befehle hoechstens einmal je 5 s je Nutzer. */
class FlutbremseTest {

    private long jetzt = 1_000_000;

    @Test
    void zweiterBefehlInnerhalbVonFuenfSekundenWirdGebremst() {
        Flutbremse b = new Flutbremse(() -> jetzt);
        assertTrue(b.erlaubt("42"));
        jetzt += 1000;
        assertFalse(b.erlaubt("42"));
        assertEquals(4, b.restSekunden("42"));
        jetzt += 3999;
        assertFalse(b.erlaubt("42"));
        jetzt += 1;                    // genau 5 s nach dem ersten
        assertTrue(b.erlaubt("42"));
        assertEquals(0, b.restSekunden("99"));
    }

    @Test
    void jederNutzerHatSeineEigeneBremse() {
        Flutbremse b = new Flutbremse(() -> jetzt);
        assertTrue(b.erlaubt("42"));
        assertTrue(b.erlaubt("43"));
        assertFalse(b.erlaubt("42"));
        assertFalse(b.erlaubt("43"));
    }

    @Test
    void eigenerRechnerWirdNieGebremst() {
        Flutbremse b = new Flutbremse(() -> jetzt);
        assertTrue(b.erlaubt(Rechte.EIGENER_RECHNER));
        assertTrue(b.erlaubt(Rechte.EIGENER_RECHNER));
        assertTrue(b.erlaubt(""));
        assertTrue(b.erlaubt(null));
    }

    @Test
    void nurFotoUndBerichtsBefehle() {
        for (String v : new String[]{"!screenshot", "!bild", "!live", "!livebild", "!log", "!bericht", "!LOG"}) {
            assertTrue(Flutbremse.gebremst(v), v);
        }
        for (String v : new String[]{"!help", "!status", "!pc", "!off", "!start", "", null}) {
            assertFalse(Flutbremse.gebremst(v), String.valueOf(v));
        }
    }

    @Test
    void wartetextNenntDieSekunden() {
        assertTrue(Flutbremse.wartetext(3).contains("3 s"));
        assertTrue(Flutbremse.wartetext(0).contains("moment"));
    }
}
