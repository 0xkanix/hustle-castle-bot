package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Namen in jeder Schrift (Autor, 11.10.): lateinisch oder kyrillisch je Zeile, derselbe Spieler trotz
 * verschiedener Lesung. Die Faelle sind nach echten Lesefehlern der Texterkennung gebaut (Zwillingsbuchstaben,
 * fremde Zeichen, Kapitaelchen); die Namen selbst sind erfundene Beispiele und Woerterbuch-Woerter.
 */
class NamenSchriftTest {

    private static String wahl(String lateinisch, String kyrillisch) {
        return Texterkennung.waehleZeilen(List.of(lateinisch), List.of(kyrillisch)).get(0);
    }

    @Test
    void kyrillischeNamenWerdenKyrillisch() {
        // Buchstaben ohne lateinischen Zwilling (б, я, г, П ...) oder weniger fremde Zeichen: kyrillisch
        assertEquals("Ягуар", wahl("qryap", "Ягуар"));
        assertEquals("Патрик", wahl("naTpuK", "Патрик"));
        assertEquals("Мерлин", wahl("Mep.nuH", "Мерлин"));
        assertEquals("АрТёМ9", wahl("ApT>eM9)", "АрТёМ9"));
        assertEquals("Гора", wahl("ropa", "Гора"));
        // Kapitaelchen in der lateinischen Lesung: kyrillisch klein т/н/в/м/к sieht wie ein grosser Buchstabe aus
        assertEquals("том", wahl("TOM", "том"));
        assertEquals("Турист", wahl("TypncT", "Турист"));
        assertEquals("Время года;", wahl("BpeMA", "Время года;"));
    }

    @Test
    void lateinischeNamenBleibenLateinisch() {
        // Mischt die kyrillische Lesung die Schriften und die lateinische ist sauber: lateinisch
        assertEquals("King Bob", wahl("King Bob", "king ВоЬ"));
        // Ohne Zwillings-Verdacht (l ist kein Zwilling): lateinisch
        assertEquals("Hella", wahl("Hella", "Нена"));
        // Die kyrillische Lesung hat mehr fremde Zeichen: lateinisch
        assertEquals("cafe", wahl("cafe", "са(е"));
        assertEquals("neon1211", wahl("neon1211", "пеоп1211"));
        // Das russische Modell liest lateinische Namen schlechter -- ohne kyrillischen Buchstaben nie kyrillisch
        assertEquals("Dark Killer", wahl("Dark Killer", "Dark kitter"));
        assertEquals("d mann", wahl("d mann", "d тапп"));
        assertEquals("TKNo.1", wahl("TKNo.1", "ТКГЧо.1"));
        // Autor 11.10. (Bild vom Schild): reine Zwillings-Namen sind lateinisch -- "ocean", nicht "осеап"
        assertEquals("ocean", wahl("ocean", "осеап"));
        assertEquals("Human", wahl("Human", "Нитап"));
        assertTrue(Gegnerbuch.schoener("осеап", "ocean"));
        assertFalse(Gegnerbuch.schoener("Ягуар", "qryap"));
        // gleich viele Zeilen, aber die kyrillische Zeile ist Zeichensalat aus der Clan-Zeile
        assertEquals(List.of("pepe", "A por ellos!"),
                Texterkennung.waehleZeilen(List.of("pepe", "A por ellos!"), List.of("А рог eUos!", "A por ellos!")));
    }

    @Test
    void derselbeSpielerInBeidenSchriften() {
        // "саша" las das lateinische Modell als "Cawa" und als "CaL.Ua" -- alle drei haben das Skelett "cawa"
        assertTrue(NamensVergleich.gleicherName("Cawa", "саша"));
        assertTrue(NamensVergleich.gleicherName("CaL.Ua", "саша"));
        assertTrue(NamensVergleich.gleicherName("Human", "Нитап"));
        assertTrue(NamensVergleich.gleicherName("TOM", "Том"));
        // Clan: "ЛЫСЫЕ КОТЫ" las das lateinische Modell als "J1blCblE KOTbl" (Л -> J1, Ы -> bl)
        assertTrue(NamensVergleich.gleicherClan("J1blCblE KOTbl", "ЛЫСЫЕ КОТЫ"));
        assertFalse(NamensVergleich.gleicherName("Rina", "Rino"));
        assertFalse(NamensVergleich.gleicherName("Liw", "Lena"));
        assertFalse(NamensVergleich.gleicherName("Bonaparte", "Bartholdi"));
    }

    @Test
    void ostasiatischeZeichenOhneLuecken() {
        assertEquals("さくら", Texterkennung.ohneLueckenZwischenZeichen("さ く ら"));
        assertEquals("King Bob", Texterkennung.ohneLueckenZwischenZeichen("King Bob"));
    }
}
