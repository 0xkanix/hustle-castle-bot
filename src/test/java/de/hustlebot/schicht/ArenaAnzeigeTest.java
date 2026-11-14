package de.hustlebot.schicht;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Autor 01.11., Fotos vom Seitenfenster: Bruchstueck-Namen, "already fought" bei unsicheren Plaetzen, "Not in clan" doppelt. */
class ArenaAnzeigeTest {

    private static ArenaStand.Gegner gegner(String name, boolean ohneClan, String erklaerung) {
        return new ArenaStand.Gegner(1, 1, 7, name, "", ohneClan, "", false, false, 150, 95, erklaerung, "", false);
    }

    @Test
    void einZeichenNameIstKeinNameSondernNichtLesbar() {
        for (String bruch : new String[]{"d", "y", "д", "", " ", "-", null}) {
            assertFalse(ArenaStand.lesbar(bruch), "Bruchstueck: " + bruch);
            assertEquals("(name not readable)", gegner(bruch, false, "").anzeige());
        }
        for (String echt : new String[]{"Ava", "bak", "屈機黑雞", "Ab", "M7"}) {
            assertTrue(ArenaStand.lesbar(echt), echt);
            assertEquals(echt, gegner(echt, false, "").anzeige());
        }
    }

    @Test
    void notInClanStehtNurEinmalImRechenzettel() {
        var p = SeitenFenster.posten(gegner("dnnf", true, "82 points (team 17), experience 87 % from 20 fights, Not in clan"));
        long wie = p.stream().filter(t -> t.equalsIgnoreCase("Not in clan")).count();
        assertEquals(1, wie, p.toString());
        assertTrue(p.stream().anyMatch(t -> t.contains("experience 87 %")), p.toString());
    }

    @Test
    void schonDaIgnoriertGrossUndKleinschreibung() {
        assertTrue(SeitenFenster.schonDa(List.of("Not in clan"), "NOT IN CLAN"));
        assertFalse(SeitenFenster.schonDa(List.of("Not in clan"), "Not in cl"));
    }

    @Test
    void platzOhneBesiegtStehtAlsWirdGelesenNichtAlsSchonGekaempft() {
        var plaetze = List.of(new ArenaStand.Platz(1, 580, 220, "", true), new ArenaStand.Platz(2, 400, 300, "", false));
        var s = new ArenaStand.Stand(System.currentTimeMillis(), 5, 1159, 770, List.of(), plaetze);
        var z = SeitenFenster.zeilen(s, true);
        assertEquals(2, z.size());
        assertTrue(z.get(0).besiegt(), "Platz 1: Victory am Bild");
        assertFalse(z.get(1).besiegt(), "Platz 2: unsicher, wird gelesen");
    }

    @Test
    void platzMitVierArgumentenBleibtBesiegtWieBisher() {
        assertTrue(new ArenaStand.Platz(4, 1, 2, "sicher").besiegt());
    }

    /** Protokoll 14:49:30: "Namensbild Bild-1024-032130 erkannt (gelesen: 元素神)" -- die Kennung darf nie angezeigt werden. */
    @Test
    void bildKennungWirdNieAlsNameGezeigt() {
        assertFalse(ArenaStand.lesbar("Bild-1024-032130"));
        assertEquals("(name not readable)", gegner("Bild-1024-032130", false, "").anzeige());
        assertEquals("元素神", gegner("元素神", false, "").anzeige());
    }
}
