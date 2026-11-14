package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Werkzeug-Formen (Fotoserie 21.10.): Dynamit 3 x 3, Super-Spitzhacke Plus -- die Mitte mit den meisten HC. */
class TitanFormTest {

    private static boolean[][] offen(String... z) {
        boolean[][] b = new boolean[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++) for (int c = 0; c < z[r].length(); c++) b[r][c] = z[r].charAt(c) == 'o';
        return b;
    }

    private static boolean[][] hc(String... z) {
        boolean[][] b = new boolean[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++) for (int c = 0; c < z[r].length(); c++) b[r][c] = z[r].charAt(c) == 'H';
        return b;
    }

    @Test
    void dynamitNichtInDieOffeneMitte() {
        // Wie auf dem Foto: die Mitte ist schon offen, HC unten links und unten Mitte.
        String[] mine = {
                "bbobbb",
                "boooob",
                "boooob",
                "boooob",
                "HbHbbb"};
        boolean[][] o = offen(mine);
        int[] b = TitanEventMode.besteFormMitte(o, TitanEventMode.zugang(o), hc(mine), TitanEventMode.FORM_DYNAMIT, Set.of());
        assertNotNull(b);
        assertEquals(4, b[0]);
        assertEquals(1, b[1]);    // unten zwischen den beiden HC: beide im 3x3
        assertEquals(2, b[2]);
    }

    @Test
    void superSpitzhackeImPlusMoeglichstVieleHc() {
        String[] mine = {
                "bbobbb",
                "bbobbb",
                "bHbHbb",
                "bbHbbb",
                "bbbbbb"};
        boolean[][] o = offen(mine);
        int[] b = TitanEventMode.besteFormMitte(o, TitanEventMode.zugang(o), hc(mine), TitanEventMode.FORM_SUPER, Set.of());
        assertNotNull(b);
        // Mitte 2:2 (grenzt an den Schacht): Plus trifft 2:1, 2:3 und 3:2 -- drei HC
        assertArrayEquals(new int[]{2, 2, 3, 4}, b);   // 1:2 ist der offene Schacht
    }

    @Test
    void ohneHcMoeglichstVieleBloecke() {
        String[] mine = {
                "ooobbb",
                "ooobbb",
                "bbbbbb",
                "bbbbbb",
                "bbbbbb"};
        boolean[][] o = offen(mine);
        int[] b = TitanEventMode.besteFormMitte(o, TitanEventMode.zugang(o), hc(mine), TitanEventMode.FORM_DYNAMIT, Set.of());
        assertNotNull(b);
        assertEquals(0, b[2]);
        assertEquals(7, b[3]);   // die Mitte muss am Schacht liegen: hoechstens 7 zue im 3x3
        assertEquals(2, b[0]);   // bei Gleichstand tiefer
    }
}
