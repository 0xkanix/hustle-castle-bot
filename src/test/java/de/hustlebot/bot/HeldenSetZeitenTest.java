package de.hustlebot.bot;

import de.hustlebot.core.Rechnertempo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** Helden-Set: die festen Grenzen wachsen auf dem langsamen Server mit (Pruefer 29.10., Rechnertempo bis 5). */
class HeldenSetZeitenTest {

    private static void faktor(double f) throws Exception {
        Field feld = Rechnertempo.class.getDeclaredField("faktor");
        feld.setAccessible(true);
        feld.setDouble(null, f);
    }

    @Test
    void grenzenMitRechnertempo() throws Exception {
        double vorher = Rechnertempo.faktor();
        try {
            faktor(1.0);
            assertEquals(25_000, HeldenSetWechsel.grenze(HeldenSetWechsel.SCHRITT_GRENZE_MS));
            assertEquals(4_000, HeldenSetWechsel.grenze(HeldenSetWechsel.APPLY_WARTEN_MS));
            faktor(5.0);
            assertEquals(125_000, HeldenSetWechsel.grenze(HeldenSetWechsel.SCHRITT_GRENZE_MS), "Schritt-Grenze x5");
            assertEquals(7_500, HeldenSetWechsel.grenze(HeldenSetWechsel.TIPP_ABSTAND_MS), "nicht schon nach 1,5 s neu tippen");
            assertEquals(6_000, HeldenSetWechsel.grenze(HeldenSetWechsel.ZU_TIPP_ABSTAND_MS));
            assertEquals(20_000, HeldenSetWechsel.grenze(HeldenSetWechsel.APPLY_WARTEN_MS), "auf Apply 20 s warten");
        } finally {
            faktor(vorher);
        }
    }
}
