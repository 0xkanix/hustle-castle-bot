package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Autor 31.10.: goettlich x2, nur bei gut und mittel; legendaer und mythisch x1, schlecht bleibt, wie es ist. */
class ArenaGoettlichTest {

    private static String zahlen(float wert) {
        float[] m = new float[192];
        Arrays.fill(m, wert);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < m.length; i++) b.append(i == 0 ? "" : ",").append(m[i]);
        return b.toString();
    }

    private static float[] merkmal(float wert) {
        float[] m = new float[192];
        Arrays.fill(m, wert);
        return m;
    }

    @Test
    void gegenstaendeZaehlenNurBeiPluspunktenDoppelt() {
        assertEquals(6, ArenaItems.punkte(ArenaItems.Wert.GUT, true));
        assertEquals(2, ArenaItems.punkte(ArenaItems.Wert.MITTEL, true));
        assertEquals(-3, ArenaItems.punkte(ArenaItems.Wert.SCHLECHT, true));
        assertEquals(-1, ArenaItems.punkte(ArenaItems.Wert.NEUTRAL, true));
        assertEquals(3, ArenaItems.punkte(ArenaItems.Wert.GUT, false));
        assertEquals(1, ArenaItems.punkte(ArenaItems.Wert.MITTEL, false));
    }

    @Test
    void dieStufeKommtAusDemFuenftenFeld() {
        List<String> z = List.of("artefakte\tgut\ta\t" + zahlen(10) + "\tg", "artefakte\tgut\tb\t" + zahlen(200));
        assertTrue(ArenaBewertung.goettlichAusZeilen(z, "artefakte", merkmal(11)));
        assertFalse(ArenaBewertung.goettlichAusZeilen(z, "artefakte", merkmal(199)));
    }

    @Test
    void ohneAehnlichesBildIstNichtsGoettlich() {
        List<String> z = List.of("totems\tgut\ta\t" + zahlen(10) + "\tg");
        assertFalse(ArenaBewertung.goettlichAusZeilen(z, "totems", merkmal(200)), "zu weit weg");
        assertFalse(ArenaBewertung.goettlichAusZeilen(z, "artefakte", merkmal(10)), "andere Art");
        assertFalse(ArenaBewertung.goettlichAusZeilen(z, "totems", null));
    }

    @Test
    void alteZeilenOhneStufeGeltenWeiter() {
        List<String> z = List.of("artefakte\tgut\ta\t" + zahlen(10));
        assertEquals("gut", ArenaBewertung.wertAusZeilen(z, "artefakte", merkmal(10)));
        assertFalse(ArenaBewertung.goettlichAusZeilen(z, "artefakte", merkmal(10)));
    }
}
