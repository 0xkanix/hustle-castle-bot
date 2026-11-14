package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Grab-Plan (Autor 18.10.: drei Bilder mit rot = Weg des Bots, gruen = richtiger Weg). "o" offen, "H" HC, Ziffer =
 * Schlaege (1 braun, 2 grau). Zeile 5 = unter dem Seil.
 */
class TitanPlanTest {

    private static boolean[][] offen(String... z) {
        boolean[][] o = new boolean[z.length][];
        for (int r = 0; r < z.length; r++) {
            o[r] = new boolean[z[r].length()];
            for (int c = 0; c < z[r].length(); c++) o[r][c] = z[r].charAt(c) == 'o';
        }
        return o;
    }

    private static boolean[][] hc(String... z) {
        boolean[][] o = new boolean[z.length][];
        for (int r = 0; r < z.length; r++) {
            o[r] = new boolean[z[r].length()];
            for (int c = 0; c < z[r].length(); c++) o[r][c] = z[r].charAt(c) == 'H';
        }
        return o;
    }

    private static int[][] kost(String... z) {
        int[][] k = new int[z.length][];
        for (int r = 0; r < z.length; r++) {
            k[r] = new int[z[r].length()];
            for (int c = 0; c < z[r].length(); c++) {
                char ch = z[r].charAt(c);
                k[r][c] = ch == 'o' ? 0 : ch == '2' ? 2 : 1;
            }
        }
        return k;
    }

    private static int[] naechstes(String... z) {
        boolean[][] o = offen(z), h = hc(z);
        int[][] k = kost(z);
        return TitanEventMode.naechstesFeld(o, h, TitanEventMode.grabPlan(o, h, k), k, Set.of());
    }

    /** Bild 3: Schacht in Spalte 2, HC links in Zeile 3 und 4 -- nicht den Block links in Zeile 2 zerschlagen. */
    @Test
    void hcUeberAbzweigVomSchacht() {
        String[] mine = {
                "1oooo1",
                "1o12o1",
                "H11111",
                "H11111",
                "111111"};
        boolean[][] plan = TitanEventMode.grabPlan(offen(mine), hc(mine), kost(mine));
        assertFalse(plan[1][0], "Block links in Zeile 2 ist unnoetig");
        assertArrayEquals(new int[]{2, 1}, naechstes(mine));
    }

    /** Bild 2: obere Reihe offen, HC unten in Spalte 4 -- gerade runter, nicht in Spalte 3 anfangen. */
    @Test
    void geradeRunterZumHc() {
        String[] mine = {
                "1oooo1",
                "112111",
                "111111",
                "111111",
                "111H11"};
        assertArrayEquals(new int[]{1, 3}, naechstes(mine));
    }

    /** Ohne HC: vom Schacht aus nach unten, nicht zur Seite. */
    @Test
    void ohneHcNachUnten() {
        String[] mine = {
                "11o111",
                "11o111",
                "121111",
                "111111",
                "111111"};
        assertArrayEquals(new int[]{2, 2}, naechstes(mine));
    }

    /** Erst die HC ueber dem Seil, dann die unterste Reihe (die laesst die Mine rutschen). */
    @Test
    void obenVorUnten() {
        String[] mine = {
                "111111",
                "111111",
                "11oH11",
                "11o111",
                "11H111"};
        assertArrayEquals(new int[]{2, 3}, naechstes(mine));
    }

    @Test
    void rutschenErkennen() {
        int[][][] vorher = new int[5][6][], gleich = new int[5][6][], versetzt = new int[5][6][];
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 6; c++) {
                int v = (r * 7 + c * 3) % 5 * 40 + 50;
                vorher[r][c] = new int[]{v, v / 2, v / 3};
                gleich[r][c] = vorher[r][c].clone();
            }
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 6; c++) versetzt[r][c] = r + 1 < 5 ? vorher[r + 1][c].clone() : new int[]{200, 150, 90};
        assertFalse(TitanEventMode.verschobenUmEineReihe(gleich, vorher));
        assertTrue(TitanEventMode.verschobenUmEineReihe(versetzt, vorher));
    }
}
