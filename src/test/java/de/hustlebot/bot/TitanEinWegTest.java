package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 21.10.: "nur EINEN Weg anfangen, nicht mehrere Wege zu einer HC" -- ein Ziel, ein Weg, zu Ende graben.
 * 'o' offen, 'b' brauner Block (1 Schlag), 's' grauer Stein (2 Schlaege), 'H' HC auf braunem Block.
 */
class TitanEinWegTest {

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

    private static int[][] kost(String... z) {
        int[][] k = new int[z.length][z[0].length()];
        for (int r = 0; r < z.length; r++)
            for (int c = 0; c < z[r].length(); c++) k[r][c] = switch (z[r].charAt(c)) { case 'o' -> 0; case 's' -> 2; default -> 1; };
        return k;
    }

    private static int[] schritt(TitanEventMode m, String... mine) {
        boolean[][] o = offen(mine);
        return m.einWeg(o, TitanEventMode.zugang(o), hc(mine), kost(mine), Set.of(), new boolean[mine.length][mine[0].length()]);
    }

    @Test
    void einZielEinWegBisZumEnde() {
        TitanEventMode m = new TitanEventMode();
        // Zwei HC: links nah, rechts weiter. Erst links, und zwar Feld fuer Feld auf demselben Weg.
        String[] mine = {
                "bbobbb",
                "bbobbb",
                "Hbbbbb",
                "bbbbbH",
                "bbbbbb"};
        int[] f1 = schritt(m, mine);
        assertNotNull(f1);
        assertTrue(f1[1] <= 2, "erster Schlag Richtung linkes HC: " + f1[0] + ":" + f1[1]);
        // f1 ist offen -- der naechste Schlag bleibt auf diesem Weg (grenzt an f1, geht nach links)
        StringBuilder z = new StringBuilder(mine[f1[0]]);
        z.setCharAt(f1[1], 'o');
        String[] mine2 = mine.clone();
        mine2[f1[0]] = z.toString();
        int[] f2 = schritt(m, mine2);
        assertNotNull(f2);
        assertEquals(1, Math.abs(f2[0] - f1[0]) + Math.abs(f2[1] - f1[1]), "naechstes Feld grenzt an das letzte");
        assertTrue(f2[1] <= f1[1], "weiter nach links, nicht zum rechten HC");
    }

    @Test
    void gleichTeureWegeWechselnNicht() {
        TitanEventMode m = new TitanEventMode();
        // HC unten links-mittig: zwei gleich teure Wege (ueber 1:2 oder ueber 1:3?). Einmal gewaehlt, bleibt es.
        String[] mine = {
                "bboobb",
                "bbbbbb",
                "bbHbbb",
                "bbbbbb",
                "bbbbbb"};
        int[] f1 = schritt(m, mine);
        assertNotNull(f1);
        // Kosten aendern sich (Lesefehler): der Weg bleibt trotzdem derselbe
        String[] mine2 = {
                "bboobb",
                "bbsbbb".equals("") ? "" : (f1[1] == 2 ? "bbbsbb" : "bbsbbb"),
                "bbHbbb",
                "bbbbbb",
                "bbbbbb"};
        int[] f2 = schritt(m, mine2);
        assertArrayEquals(f1, f2);
    }

    @Test
    void erstDieHcObenDannUnten() {
        TitanEventMode m = new TitanEventMode();
        // HC in der untersten Reihe ist naeher, das obere zuerst (die unterste laesst die Mine rutschen).
        int[] f = schritt(m,
                "bbobbb",
                "bbobbb",
                "bbobbb",
                "Hbobbb",
                "bbHbbb");
        assertNotNull(f);
        assertEquals(3, f[0]);
        assertEquals(1, f[1]);
    }

    @Test
    void ohneHcGeradeNachUnten() {
        TitanEventMode m = new TitanEventMode();
        int[] f = schritt(m,
                "bbobbb",
                "bbobbb",
                "bbbbbb",
                "bbbbbb",
                "bbbbbb");
        assertArrayEquals(new int[]{2, 2}, f);
    }

    @Test
    void zielBleibtWennDieSpitzhackeDasHcVerdeckt() {
        TitanEventMode m = new TitanEventMode();
        String[] mine = {
                "bbobbb",
                "bbobbb",
                "bbbbbb",
                "bbHbbH",
                "bbbbbb"};
        int[] f1 = schritt(m, mine);
        assertArrayEquals(new int[]{2, 2}, f1);   // Richtung HC 3:2
        // 2:2 ist offen, das HC-Zeichen auf 3:2 gerade verdeckt (Spitzhacke) -- trotzdem weiter auf 3:2, nicht zu 3:5
        int[] f2 = schritt(m,
                "bbobbb",
                "bbobbb",
                "bbobbb",
                "bbbbbH",
                "bbbbbb");
        assertArrayEquals(new int[]{3, 2}, f2);
    }

    @Test
    void rutschenUmEineReiheWirdErkannt() {
        boolean[][] vorher = offen(
                "bbobbb",
                "bbobbb",
                "bboobb",
                "bbbobb",
                "bbbbbb");
        boolean[][] jetzt = offen(
                "bbobbb",
                "bboobb",
                "bbbobb",
                "bbbbbb",
                "bbbbbb");
        assertEquals(1, TitanEventMode.rutschReihen(vorher, jetzt));
        assertEquals(0, TitanEventMode.rutschReihen(vorher, vorher));
    }

    @Test
    void zielWandertBeimRutschenMit() {
        TitanEventMode m = new TitanEventMode();
        int[] f1 = schritt(m,
                "bbobbb",
                "bbobbb",
                "bboobb",
                "bbbobb",
                "bbbHbb");
        assertArrayEquals(new int[]{4, 3}, f1);
        // eine Reihe hochgerutscht, HC jetzt auf 3:3 (verdeckt), Weg geht weiter dorthin
        int[] f2 = schritt(m,
                "bbobbb",
                "bboobb",
                "bbbobb",
                "bbbbbb",
                "bbbbbb");
        assertArrayEquals(new int[]{3, 3}, f2);
    }

    @Test
    void nachUntenErstWennLaengerKeinHcZuSehenIst() {
        TitanEventMode m = new TitanEventMode();
        // HC gesehen und geholt ...
        assertNotNull(schritt(m,
                "bbobbb",
                "bbobbb",
                "bbHbbb",
                "bbbbbb",
                "bbbbbb"));
        // ... gleich danach kein HC im Bild: noch NICHT nach unten anfangen (es koennte nur verdeckt sein)
        assertNull(schritt(m,
                "bbobbb",
                "bbobbb",
                "bbobbb",
                "bbbbbb",
                "bbbbbb"));
    }

    @Test
    void wegNachUntenEndetSobaldEinHcAuftaucht() {
        TitanEventMode m = new TitanEventMode();
        assertArrayEquals(new int[]{2, 2}, schritt(m,
                "bbobbb",
                "bbobbb",
                "bbbbbb",
                "bbbbbb",
                "bbbbbb"));
        // HC taucht rechts oben auf -- der Weg nach unten (2:2 noch nicht angeschlagen) wird nicht weiter verfolgt
        int[] f = schritt(m,
                "bbobbb",
                "bbobHb",
                "bbbbbb",
                "bbbbbb",
                "bbbbbb");
        assertNotNull(f);
        assertEquals(1, f[0]);
        assertEquals(3, f[1]);
    }
}
