package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 03.11.: der Titanen-Trupp wurde um 02:20 und 08:20 nicht gesetzt -- die drei Ursachen, mit den echten Texten. */
class ClanWarStuetzpunktTest {

    private static final Matcher.Hit STRICH = new Matcher.Hit("cw_slash", 100, 50, 8, 16, 0.9);

    private static int[] ziffer(int d, int x) {
        return new int[]{d, x, 50, 9, 16, 900};
    }

    @Test
    void zweistelligePlatzzahlWirdGanzGelesen() {
        // "4/12" (Rage Outpost 08:23) galt als "4/1" und damit als voll.
        List<int[]> z = new ArrayList<>(List.of(ziffer(4, 90), ziffer(1, 109), ziffer(2, 119)));
        assertEquals(4, ClanWarMode.zahlNeben(STRICH, z, true));
        assertEquals(12, ClanWarMode.zahlNeben(STRICH, z, false));
        // "10/12": links zwei Stellen
        List<int[]> z2 = List.of(ziffer(1, 80), ziffer(0, 90), ziffer(1, 109), ziffer(2, 119));
        assertEquals(10, ClanWarMode.zahlNeben(STRICH, z2, true));
        // "2/4" bleibt einstellig
        List<int[]> z3 = List.of(ziffer(2, 90), ziffer(4, 109));
        assertEquals(2, ClanWarMode.zahlNeben(STRICH, z3, true));
        assertEquals(4, ClanWarMode.zahlNeben(STRICH, z3, false));
        assertEquals(-1, ClanWarMode.zahlNeben(STRICH, List.of(), false));
    }

    @Test
    void vierVonZwoelfIstNichtVoll() {
        assertFalse(new ClanWarMode.Turm(0, 0, true, false, 4, 12).voll());
        assertFalse(new ClanWarMode.Turm(0, 0, false, false, 1, 10).voll());
        assertTrue(new ClanWarMode.Turm(0, 0, true, false, 4, 4).voll());
        // "4/1" und "5/4" sind unmoeglich -> Platzzahl unbekannt, nie voll
        assertEquals(-1, ClanWarMode.platzPlausibel(4, 1));
        assertEquals(-1, ClanWarMode.platzPlausibel(5, 4));
        assertFalse(new ClanWarMode.Turm(0, 0, true, false, 4, ClanWarMode.platzPlausibel(4, 1)).voll());
        assertEquals(12, ClanWarMode.platzPlausibel(4, 12));
        assertEquals(4, ClanWarMode.platzPlausibel(4, 4));
        assertEquals(4, ClanWarMode.platzPlausibel(-1, 4));
    }

    @Test
    void stufenNotizenDerClanVerwaltung() {
        assertArrayEquals(new int[]{11, 11}, ClanWarMode.stufenNotiz("2 Til's FILL"));          // gelesen fuer "2 T11's FILL"
        assertArrayEquals(new int[]{11, 11}, ClanWarMode.stufenNotiz("2 T11’s FILL"));
        assertArrayEquals(new int[]{11, 11}, ClanWarMode.stufenNotiz("LEAVE SPACE ; FOR 8 TRII"));
        assertArrayEquals(new int[]{11, 11}, ClanWarMode.stufenNotiz("LEAVE SPACE ; FOR 8 TR11 ; TITANS - LOOK ; BEFORE FILLING"));
        assertArrayEquals(new int[]{1, 7}, ClanWarMode.stufenNotiz("T7 and BELOW"));
        assertArrayEquals(new int[]{1, 9}, ClanWarMode.stufenNotiz("T9 & Below"));
        assertArrayEquals(new int[]{10, 99}, ClanWarMode.stufenNotiz("T10+"));
        // Autor 03.11.: "1 T11, 2T11, 2TR11 -- alle moeglichen Schreibweisen"
        for (String n : new String[]{"1 T11", "2T11", "2TR11", "TR 11", "2 TR11s", "tr11 only", "2 T1l FILL", "T11"}) {
            assertTrue(ClanWarMode.stufePasst(n, 11), n);
        }
        assertFalse(ClanWarMode.stufePasst("T7 and BELOW", 11));
        assertFalse(ClanWarMode.stufePasst("2 T11's FILL", -1));   // Stufe unbekannt: kein Vorrang, aber auch nicht gesperrt
        assertFalse(ClanWarMode.stufePasst("MEINCLAN ; Titans", 11));
        // echte Spielernamen bleiben Namen
        for (String n : new String[]{"Anna", "Carl/Karl", "Karla/Joe/ Hagen", "sam/paula", "Bruno ; Autor ; ; Emil",
                "Otto ; Karl ; ; David", "Til", "Tim ; Til", "Tom", "Anna ; Paula ; ; Tom", ""}) {
            assertNull(ClanWarMode.stufenNotiz(n), n);
        }
    }

    @Test
    void ruhmAuchMitServerErkennung() {
        // So las die Server-Erkennung (en-US) die Ausschnitte am 03.11.
        assertEquals(8000, ClanWarMode.ruhmZahl("8 ooo"));
        assertEquals(6000, ClanWarMode.ruhmZahl("6 ooo"));
        assertEquals(3400, ClanWarMode.ruhmZahl("3 400"));
        assertEquals(1400, ClanWarMode.ruhmZahl("1 400"));
        assertEquals(-1, ClanWarMode.ruhmZahl("aoo"));
        assertEquals(-1, ClanWarMode.ruhmZahl("+35%"));
        // der alte Weg mit dem ganzen Ausschnitt bleibt
        assertEquals(8000, ClanWarMode.ruhmAus("Glory for winning: 0 / 8 ooo / Outpostlbonus: +2 800"));
        assertEquals(5000, ClanWarMode.ruhmAus("5 000 Glory for winning:"));
        assertEquals(-1, ClanWarMode.ruhmAus("lory or"));
    }
}
