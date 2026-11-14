package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Noch nie angegriffen: Punkte schaetzen wie bei Gegnern mit aehnlicher Siegchance (Autor 30.10.). */
class ArenaVergleichTest {

    private static String zeile(int chance, String ergebnis, int dauer, int punkte) {
        return "2026-10-30 05:27:43\tX\t\t34874371\t41702115\t-1\t73\t-74\t" + chance + "\tja\t" + ergebnis + "\t" + dauer + "\t" + punkte + "\t4";
    }

    @Test
    void nurGleicheStufeUndAehnlicheChance() {
        List<String> z = new ArrayList<>(List.of("# Kopfzeile"));
        for (int i = 0; i < 8; i++) z.add(zeile(90, "S", 13, 219));
        z.add(zeile(90, "S", 7, 150));     // 150er zaehlt nicht fuer einen 225er
        z.add(zeile(70, "S", 30, 190));    // Chance zu weit weg
        z.add(zeile(90, "N", 25, 0));      // Niederlage
        z.add(zeile(91, "S", 99, 219));    // Lesefehler
        List<Integer> d = ArenaVergleich.dauern(z, 225, 88);
        assertEquals(8, d.size());
        assertTrue(d.stream().allMatch(x -> x == 13));
    }

    @Test
    void zuWenigVergleicheGibtNichts() {
        List<String> z = new ArrayList<>();
        for (int i = 0; i < ArenaVergleich.MINDESTENS - 1; i++) z.add(zeile(90, "S", 13, 219));
        assertTrue(ArenaVergleich.dauern(z, 225, 90).isEmpty(), "dann gilt 220 / 149");
        assertEquals(220.0, ArenaVergleich.ohneVergleich(225));
        assertEquals(149.0, ArenaVergleich.ohneVergleich(150));
    }

    @Test
    void dieNeuestenZaehlen() {
        List<String> z = new ArrayList<>();
        for (int i = 0; i < ArenaVergleich.HOECHSTENS; i++) z.add(zeile(90, "S", 20, 208));
        z.add(zeile(90, "S", 11, 223));
        List<Integer> d = ArenaVergleich.dauern(z, 225, 90);
        assertEquals(ArenaVergleich.HOECHSTENS, d.size());
        assertEquals(11, d.get(d.size() - 1));
    }
}
