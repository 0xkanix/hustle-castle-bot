package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Listen-Netz (Autor 28.10.): ein Spieler mit Listenbild wird auch am Namen des Bildes erkannt, wenn das Bild selbst nicht
 * trifft (Xenos: Bild traf in 14 von 21 Sichtungen, zweimal angegriffen und verloren).
 */
class ArenaListenNetzTest {

    @AfterEach
    void schalterZurueck() {
        ArenaRegelnNeu.testWert(null);
    }

    private static final String NIEMALS = ArenaListenNetz.NIEMALS;

    @Test
    void namenAusDenVorlagenNamenJeListe() {
        Map<String, List<String>> l = ArenaListenNetz.listenNamen(List.of(
                "ar_block_Xenos", "ar_block_JkNXED_1017-0002", "ar_sicher_Denis_auto_1028-1430",
                "ar_machbar_Анатолий_1024-0349", "ar_ungern_Lutor_1022-1758",
                "ar_block_1", "ar_block_abc", "ar_name_foo", "ar_clan_bar", "ar_reward"));
        assertEquals(List.of("Xenos", "JkNXED"), l.get(ArenaListenNetz.NIEMALS));
        assertEquals(List.of("Denis"), l.get(ArenaListenNetz.SICHER));
        assertEquals(List.of("Анатолий"), l.get(ArenaListenNetz.MACHBAR));
        assertEquals(List.of("Lutor"), l.get(ArenaListenNetz.MEIDEN));
    }

    @Test
    void zuKurzeNamenUndZahlenZaehlenNicht() {
        Map<String, List<String>> l = ArenaListenNetz.listenNamen(List.of("ar_block_1", "ar_block_i", "ar_block_abc", "ar_block_2024", "ar_sicher_Ab"));
        for (List<String> namen : l.values()) assertTrue(namen.isEmpty(), namen.toString());
        assertTrue(ArenaListenNetz.listenNamen(null).get(NIEMALS).isEmpty());
    }

    @Test
    void gleicherNameTrifft() {
        assertEquals("Xenos", ArenaListenNetz.treffer(List.of("Xenos"), "Xenos", 4));
        assertEquals("Xenos", ArenaListenNetz.treffer(List.of("Xenos"), "  xenos ", 4));
        assertEquals("Xenos", ArenaListenNetz.treffer(List.of("Xenos"), "XE-NOS", 4));
        assertNull(ArenaListenNetz.treffer(List.of("Xenos"), "Xeno", 4));
        assertNull(ArenaListenNetz.treffer(List.of("Xenos"), "Zeus", 4));
        assertNull(ArenaListenNetz.treffer(List.of("Xenos"), "", 4));
        assertNull(ArenaListenNetz.treffer(List.of("Xenos"), null, 4));
        assertNull(ArenaListenNetz.treffer(List.of(), "Xenos", 4));
    }

    @Test
    void vierZeichenNurBeiNiemalsUndMeiden() {
        // "Alex" ist ein gewoehnlicher Name: auf sicher/machbar waere ein falscher Treffer ein Fremder als "sicherer Sieg"
        Map<String, List<String>> sicher = Map.of(ArenaListenNetz.SICHER, List.of("Alex"));
        assertTrue(ArenaListenNetz.treffer(sicher, "Alex").isEmpty());
        Map<String, List<String>> machbar = Map.of(ArenaListenNetz.MACHBAR, List.of("Alex"));
        assertTrue(ArenaListenNetz.treffer(machbar, "Alex").isEmpty());
        Map<String, List<String>> niemals = Map.of(NIEMALS, List.of("Alex"));
        assertEquals(1, ArenaListenNetz.treffer(niemals, "Alex").size());
        Map<String, List<String>> meiden = Map.of(ArenaListenNetz.MEIDEN, List.of("Alex"));
        assertEquals(1, ArenaListenNetz.treffer(meiden, "Alex").size());
        // fuenf Zeichen genuegen auch bei sicher
        assertEquals(1, ArenaListenNetz.treffer(Map.of(ArenaListenNetz.SICHER, List.of("Denis")), "Denis").size());
    }

    @Test
    void kyrillischUndLateinischGleichesSkelett() {
        // "Хакер" (kyrillisch) und "Xakep" (lateinisch) sehen gleich aus -- die Texterkennung liest mal so, mal so
        String kyr = "Хакер";
        assertNotEquals(NamensVergleich.form(kyr), NamensVergleich.form("Xakep"));
        assertEquals(kyr, ArenaListenNetz.treffer(List.of(kyr), "Xakep", 5));
        assertEquals("Xakep", ArenaListenNetz.treffer(List.of("Xakep"), kyr, 5));
    }

    @Test
    void einLesefehlerErstAbSechsZeichen() {
        assertEquals("Beelzebub", ArenaListenNetz.treffer(List.of("Beelzebub"), "Beelzebud", 5));   // ein Zeichen anders
        assertEquals("Beelzebub", ArenaListenNetz.treffer(List.of("Beelzebub"), "Beelzebu", 5));    // eines fehlt
        assertEquals("Beelzebub", ArenaListenNetz.treffer(List.of("Beelzebub"), "Beelzebubb", 5));  // eines zu viel
        assertNull(ArenaListenNetz.treffer(List.of("Beelzebub"), "Beelzxbud", 5));                  // zwei Fehler
        assertNull(ArenaListenNetz.treffer(List.of("Xenos"), "Xanos", 4));                          // 5 Zeichen: zu kurz fuer einen Fehler
        assertNull(ArenaListenNetz.treffer(List.of("Denis"), "Denix", 5));
    }

    @Test
    void abstandHoechstensEins() {
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdef", "abcdef"));
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdef", "abcdxf"));
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdef", "abcdf"));
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdf", "abcdef"));
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdef", "abcdefg"));
        assertTrue(ArenaListenNetz.abstandHoechstens1("abcdef", "bcdef"));
        assertFalse(ArenaListenNetz.abstandHoechstens1("abcdef", "abxxef"));
        assertFalse(ArenaListenNetz.abstandHoechstens1("abcdef", "abcd"));
        assertFalse(ArenaListenNetz.abstandHoechstens1("abcdef", "fedcba"));
    }

    @Test
    void beiZweiListenGiltDieStrengere() {
        // niemals > meiden > machbar > sicher, egal wie die Map sortiert ist
        Map<String, List<String>> l = Map.of(
                ArenaListenNetz.SICHER, List.of("Beelzebub"),
                ArenaListenNetz.MACHBAR, List.of("Beelzebub"),
                ArenaListenNetz.MEIDEN, List.of("Beelzebub"));
        List<ArenaListenNetz.Treffer> t = ArenaListenNetz.treffer(l, "Beelzebub");
        assertEquals(List.of(ArenaListenNetz.MEIDEN, ArenaListenNetz.MACHBAR, ArenaListenNetz.SICHER),
                t.stream().map(ArenaListenNetz.Treffer::liste).toList());
        Map<String, List<String>> mit = Map.of(ArenaListenNetz.SICHER, List.of("Beelzebub"), NIEMALS, List.of("Beelzebub"));
        assertEquals(NIEMALS, ArenaListenNetz.treffer(mit, "Beelzebub").get(0).liste());
        assertEquals("Beelzebub", ArenaListenNetz.treffer(mit, "Beelzebub").get(0).name());
    }

    @Test
    void keinTrefferBeiFremdemNamen() {
        Map<String, List<String>> l = ArenaListenNetz.listenNamen(List.of("ar_block_Xenos", "ar_sicher_Denis"));
        assertTrue(ArenaListenNetz.treffer(l, "Bernd").isEmpty());
        assertTrue(ArenaListenNetz.treffer(l, "").isEmpty());
        assertTrue(ArenaListenNetz.treffer(Map.of(), "Xenos").isEmpty());
        assertTrue(ArenaListenNetz.treffer(null, "Xenos").isEmpty());
    }

    @Test
    void kraftNurEinProzentFuenfAbweichung() {
        assertTrue(ArenaListenNetz.kraftPasst(35_501_945L, 35_501_945L));
        assertTrue(ArenaListenNetz.kraftPasst(35_501_945L, 35_000_000L));    // 1,4 %
        assertFalse(ArenaListenNetz.kraftPasst(35_501_945L, 34_900_000L));   // 1,7 %
        assertFalse(ArenaListenNetz.kraftPasst(0, 35_000_000L));
        assertFalse(ArenaListenNetz.kraftPasst(35_000_000L, -1));
    }

    @Test
    void kraftVertraeglichNurWennBeideBekanntSind() {
        // unbekannt hier oder im Notizblock: der Name allein gilt
        assertTrue(ArenaListenNetz.kraftVertraeglich(-1, List.of(35_000_000L)));
        assertTrue(ArenaListenNetz.kraftVertraeglich(0, List.of(35_000_000L)));
        assertTrue(ArenaListenNetz.kraftVertraeglich(35_000_000L, List.of()));
        assertTrue(ArenaListenNetz.kraftVertraeglich(35_000_000L, null));
        assertTrue(ArenaListenNetz.kraftVertraeglich(35_000_000L, List.of(0L, -1L)));
        // beide bekannt: eine muss passen -- sonst ist es ein anderer Spieler mit gleichem Namen
        assertTrue(ArenaListenNetz.kraftVertraeglich(35_000_000L, List.of(30_000_000L, 35_300_000L)));
        assertFalse(ArenaListenNetz.kraftVertraeglich(35_000_000L, List.of(30_000_000L)));
        assertFalse(ArenaListenNetz.kraftVertraeglich(35_000_000L, List.of(36_000_000L, 28_000_000L)));
    }

    @Test
    void schalterStandardAusUndFuerTestSetzbar() {
        ArenaRegelnNeu.testWert(null);
        assertFalse(ArenaRegelnNeu.an(), "ohne arena-regeln-neu.txt muss der Schalter aus sein");
        ArenaRegelnNeu.testWert(true);
        assertTrue(ArenaRegelnNeu.an());
        ArenaRegelnNeu.testWert(false);
        assertFalse(ArenaRegelnNeu.an());
    }
}
