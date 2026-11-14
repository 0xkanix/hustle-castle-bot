package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Portal-Plan aus dem Start-Befehl (Autor 23.10.): Ziel-Level und Farm-Liste. */
class PortalPlanTest {

    @Test
    void zielUndFarmListe() {
        String rest = PortalMode.planAusText("portal bis 135 farm 80:2mio,135:2mio,130:1,5mio");
        assertEquals("portal", rest);
        assertEquals(135, PortalMode.zielGesetzt());
        assertEquals(3, PortalMode.farmGesetzt().size());
        assertArrayEquals(new long[]{80, 2_000_000}, PortalMode.farmGesetzt().get(0));
        assertArrayEquals(new long[]{130, 1_500_000}, PortalMode.farmGesetzt().get(2));
    }

    @Test
    void mengenSchreibweisen() {
        assertEquals(2_000_000, PortalMode.mengeAus("2000000"));
        assertEquals(2_000_000, PortalMode.mengeAus("2.000.000"));
        assertEquals(1_500_000, PortalMode.mengeAus("1,5mio"));
        assertEquals(500_000, PortalMode.mengeAus("500k"));
    }

    @Test
    void ohnePlanWirdGeleert() {
        PortalMode.planAusText("portal bis 110");
        assertEquals("portal", PortalMode.planAusText("portal"));
        assertEquals(0, PortalMode.zielGesetzt());
        assertEquals(0, PortalMode.farmGesetzt().size());
    }

    @Test
    void semikolonAlsTrenner() {
        PortalMode.planAusText("portal farm 80:2000000;135:2000000");
        assertEquals(2, PortalMode.farmGesetzt().size());
        assertEquals(0, PortalMode.zielGesetzt());
    }
}
