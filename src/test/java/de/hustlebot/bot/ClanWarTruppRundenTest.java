package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static de.hustlebot.bot.ClanWarMode.TruppRunden.Was.*;
import static org.junit.jupiter.api.Assertions.*;

/** Clan-Krieg: "Confirm" ohne Wirkung wird nicht endlos wiederholt (Server 30.10., 02:29: 10 Minuten nur Confirm). */
class ClanWarTruppRundenTest {

    private static final long GEDULD = 10_000;   // 5 s x Rechnertempo 2 wie auf dem Server

    @Test
    void tuermeMitSpielernZuerst() {
        var a = new ClanWarMode.Turm(100, 0, false, true, 0, 9);
        var b = new ClanWarMode.Turm(200, 0, false, false, 4, 9);
        var c = new ClanWarMode.Turm(300, 0, false, false, 4, 9);
        var d = new ClanWarMode.Turm(50, 0, false, false, -1, 9);
        var liste = new java.util.ArrayList<>(java.util.List.of(a, b, c, d));
        liste.sort(ClanWarMode.ansehReihenfolge());
        assertEquals(java.util.List.of(b, c, d, a), liste);
    }

    @Test
    void ohneConfirmGehtEsNormalWeiter() {
        var r = new ClanWarMode.TruppRunden();
        assertEquals(WEITER, r.naechstes(1000, GEDULD));
        assertFalse(r.lieberDieStaerksten());
    }

    @Test
    void derAblaufVomServer() {
        // Previous Squad ohne Wirkung, Confirm bleibt stehen: alle 12 s kam ein neues Bild.
        var r = new ClanWarMode.TruppRunden();
        r.confirmGedrueckt(1000);
        assertEquals(WARTEN, r.naechstes(5000, GEDULD), "der Klick kann spaet wirken");
        assertEquals(NOCHMAL, r.naechstes(13_000, GEDULD), "erst dann neu waehlen");
        assertTrue(r.lieberDieStaerksten(), "kein gespeicherter Trupp: gleich die Staerksten, Previous Squad bringt nichts mehr");
        assertEquals(WEITER, r.naechstes(13_000, GEDULD), "jetzt wird gewaehlt, nicht gezaehlt");

        r.confirmGedrueckt(20_000);
        assertEquals(NOCHMAL, r.naechstes(32_000, GEDULD));
        assertTrue(r.lieberDieStaerksten());

        r.confirmGedrueckt(40_000);
        assertEquals(AUFGEBEN, r.naechstes(52_000, GEDULD), "nie mehr als drei Runden");
    }

    @Test
    void warnfensterZaehltNichtAlsFehlschlag() {
        // Confirm -> "unvollstaendiger Trupp" -> No: Das Confirm hat gewirkt, das Auffuellen darf nicht warten.
        var r = new ClanWarMode.TruppRunden();
        r.confirmGedrueckt(1000);
        r.vergessen();
        assertEquals(WEITER, r.naechstes(1500, GEDULD));
        assertFalse(r.lieberDieStaerksten());
    }

    @Test
    void neuerTurmFaengtVonVorneAn() {
        var r = new ClanWarMode.TruppRunden();
        r.confirmGedrueckt(1000);
        r.naechstes(20_000, GEDULD);
        r.confirmGedrueckt(21_000);
        r.naechstes(40_000, GEDULD);
        assertTrue(r.lieberDieStaerksten());
        r.neuerTurm();
        assertFalse(r.lieberDieStaerksten());
        assertEquals(WEITER, r.naechstes(41_000, GEDULD));
    }
}
