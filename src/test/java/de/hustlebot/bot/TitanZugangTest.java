package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mia 21.10.: "der Bot drueckt auf Bloecke, die noch gesperrt sind" -- ein offener Hohlraum, der nicht mit dem Schacht
 * verbunden ist, macht seine Nachbarn nicht grabbar.
 */
class TitanZugangTest {

    /** 'o' = offen, '#' = Block, 'H' = Block mit HC. */
    private static boolean[][] offen(String... z) {
        boolean[][] b = new boolean[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++)
            for (int c = 0; c < z[r].length(); c++) b[r][c] = z[r].charAt(c) == 'o';
        return b;
    }

    private static boolean[][] hc(String... z) {
        boolean[][] b = new boolean[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++)
            for (int c = 0; c < z[r].length(); c++) b[r][c] = z[r].charAt(c) == 'H';
        return b;
    }

    private static int[][] kost(String... z) {
        int[][] k = new int[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++)
            for (int c = 0; c < z[r].length(); c++) k[r][c] = z[r].charAt(c) == 'o' ? 0 : 1;
        return k;
    }

    @Test
    void nurDerHohlraumAmSchachtIstErreichbar() {
        String[] mine = {
                "##o###",
                "##o###",
                "######",
                "####o#",   // abgeschlossener Hohlraum
                "######"};
        boolean[][] z = TitanEventMode.zugang(offen(mine));
        assertTrue(z[0][2]);
        assertTrue(z[1][2]);
        assertFalse(z[3][4]);
        // der Block neben dem Hohlraum ist NICHT grabbar, der unter dem Schacht schon
        assertFalse(TitanEventMode.randFeld(z, 3, 3));
        assertTrue(TitanEventMode.randFeld(z, 2, 2));
    }

    @Test
    void ohneOffenesFeldObenGiltJedesOffeneFeld() {
        String[] mine = {
                "######",
                "##o###",
                "######"};
        boolean[][] z = TitanEventMode.zugang(offen(mine));
        assertTrue(z[1][2]);
    }

    @Test
    void derNaechsteSchlagGehtNieNebenDenHohlraum() {
        // HC direkt neben dem abgeschlossenen Hohlraum: der Weg muss vom Schacht kommen, nicht vom Hohlraum.
        String[] mine = {
                "o#####",
                "o#####",
                "######",
                "###oH#",
                "######"};
        boolean[][] o = offen(mine), z = TitanEventMode.zugang(o), h = hc(mine);
        int[][] k = kost(mine);
        int[] f = TitanEventMode.naechstesFeld(o, z, h, TitanEventMode.grabPlan(z, h, k), k, Set.of());
        assertNotNull(f);
        assertTrue(TitanEventMode.randFeld(z, f[0], f[1]), "Ziel " + f[0] + ":" + f[1] + " muss am Schacht liegen");
        // vorher waere 3:5 (rechts neben dem HC, am Hohlraum) oder 2:4 gewaehlt worden -- unerreichbar
        assertFalse(f[0] == 3 && f[1] == 5);
    }
}
