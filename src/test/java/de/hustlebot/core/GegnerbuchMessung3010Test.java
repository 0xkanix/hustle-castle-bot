package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Messung 30.10.: "Not in clan"-Reste als Clan, Clan als Spielername, derselbe Spieler unter mehreren Schreibweisen. */
class GegnerbuchMessung3010Test {

    @TempDir
    Path ordner;

    @AfterEach
    void zuruecksetzen() {
        ArenaRegelnNeu.testWert(null);
    }

    private Gegnerbuch buch() {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
    }

    @Test
    void restVonNotInClanIstKeinClan() {
        for (String rest : new String[]{"IVNot", "ot in clan", "i VNot", "j V Not in clan", "CONot in clan", "I V Not in Clan", "Not in clan", "N0t"}) {
            assertTrue(Gegnerbuch.keinClan(rest), rest);
        }
        for (String clan : new String[]{"Myth Gods", "Xenos", "Boehse Onkel", "Carpe Diem", "Cannot"}) {
            assertFalse(Gegnerbuch.keinClan(clan), clan);
        }
    }

    @Test
    void clanBleibtClanAuchMitEinemFalschenNamensEintrag() {
        ArenaRegelnNeu.testWert(true);
        Gegnerbuch b = buch();
        b.ergebnis("Myth Gods", "", true, 30_000_000, 20, 0, null);   // falsch gelesen
        assertFalse(b.nurAlsClanBekannt("Myth Gods"), "nur als Name bekannt");
        b.ergebnis("zevs", "Myth Gods", true, 31_000_000, 20, 0, null);
        assertFalse(b.nurAlsClanBekannt("Myth Gods"), "einer als Clan reicht nicht");
        b.ergebnis("Rina", "Myth Gods", true, 32_000_000, 20, 0, null);
        assertTrue(b.nurAlsClanBekannt("Myth Gods"), "zwei echte Mitglieder gegen einen Fehleintrag");
        ArenaRegelnNeu.testWert(false);
        assertFalse(b.nurAlsClanBekannt("Myth Gods"), "Schalter aus: wie bisher");
    }

    @Test
    void gleicheKraftAufAlleStellen() {
        Gegnerbuch.Notiz a = notiz("QUIJOTE", 34_874_371);
        assertTrue(Gegnerbuch.gleicheKraftGenau(a, notiz("Ouijote", 34_874_371)));
        assertFalse(Gegnerbuch.gleicheKraftGenau(a, notiz("QUIJOTE", 34_874_372)));
        assertFalse(Gegnerbuch.gleicheKraftGenau(notiz("a", 0), notiz("b", 0)), "unbekannte Kraft zaehlt nicht");
        assertFalse(Gegnerbuch.setsWidersprechen(a, notiz("b", 34_874_371)), "ohne gelesene Sets kein Widerspruch");
    }

    @Test
    void letzteDauernOhneLesefehler() {
        Gegnerbuch.Notiz n = new Gegnerbuch.Notiz("FLIMSY", "", "", 31_719_948, 0, "", 0, "", "", "13 19 179 20 22", 0, 0, "", "", "", "", "", "", "");
        assertEquals(java.util.List.of(13, 19, 20, 22), n.letzteDauern(10), "179 s ist ein Lesefehler");
        assertEquals(java.util.List.of(20, 22), n.letzteDauern(2), "die letzten zwei");
        assertTrue(notiz("a", 1).letzteDauern(10).isEmpty());
    }

    private static Gegnerbuch.Notiz notiz(String name, long kraft) {
        return new Gegnerbuch.Notiz(name, "", "", kraft, 0, "", 0, "", "", "", 0, 0, "", "", "", "", "", "", "");
    }
}
