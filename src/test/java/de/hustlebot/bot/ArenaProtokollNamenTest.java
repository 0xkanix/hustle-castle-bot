package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Autor 01.11. (Pruefung 3.137, Abweichung 6 und 7): Kampfprotokoll. Echte Lesungen aus dem Protokoll vom 15:00-15:06: "kall" kam als "4kall", "Jakall",
 * "nankall", "kalt"; und die Zeile "Kreyn -> Denis +0 DEFEAT" fehlte, weil "Denis -> Kreyn +150 VICTORY" denselben Sieger und Verlierer hat.
 */
class ArenaProtokollNamenTest {

    private static String schluessel(Map<String, String> karte, String name) {
        return ArenaMode.schluesselIn(karte, name, g -> { });
    }

    @Test
    void kallUndSeineLesevariantenSindEinSpieler() {
        Map<String, String> karte = new HashMap<>();
        String kall = schluessel(karte, "kall");
        for (String variante : new String[]{"4kall", "Jakall", "nankall", "kalt", "kall"}) {
            assertEquals(kall, schluessel(karte, variante), variante + " ist kall");
        }
        assertEquals(1, new java.util.HashSet<>(karte.values()).size(), "alle Varianten haben denselben Schluessel");
    }

    @Test
    void auchWennEineVarianteZuerstKommt() {
        Map<String, String> karte = new HashMap<>();
        String zuerst = schluessel(karte, "Jakall");
        assertEquals(zuerst, schluessel(karte, "kall"));
        assertEquals(zuerst, schluessel(karte, "4kall"));
        assertEquals(zuerst, schluessel(karte, "kalt"));
    }

    @Test
    void andereSpielerDesTurniersBleibenGetrennt() {
        // Die zehn Spieler aus Arena 2 (15:00-15:06): nur kall faellt zusammen.
        Map<String, String> karte = new HashMap<>();
        String[] namen = {"Xenos", "old man", "The Dark Prince", "Kreyn", "Denis", "vovan4ik24", "Еру Ілуватар", "~ЦИРИ~", "kall"};
        java.util.Set<String> schluessel = new java.util.HashSet<>();
        for (String n : namen) schluessel.add(schluessel(karte, n));
        assertEquals(namen.length, schluessel.size(), "neun verschiedene Spieler, neun Schluessel");
        assertNotEquals(schluessel(karte, "kall"), schluessel(karte, "Kreyn"));
    }

    @Test
    void kurzeNamenNurMitJunkDavorOderEinemZeichenAnders() {
        assertTrue(ArenaMode.kurzerNameGleich("kall", "4kall"));
        assertTrue(ArenaMode.kurzerNameGleich("kall", "jakall"));
        assertTrue(ArenaMode.kurzerNameGleich("nankall", "kall"));
        assertTrue(ArenaMode.kurzerNameGleich("kall", "kalt"));
        assertFalse(ArenaMode.kurzerNameGleich("kall", "kreyn"));
        assertFalse(ArenaMode.kurzerNameGleich("kall", "denis"));
        assertFalse(ArenaMode.kurzerNameGleich("kall", "kallisto"), "zu lang");
        assertFalse(ArenaMode.kurzerNameGleich("kall", "kallx"), "nur Zeichen VOR dem Namen sind Wappen-Reste");
        assertFalse(ArenaMode.kurzerNameGleich("tom1", "tom2"), "Ziffern gehoeren zu echten Namen");
        assertFalse(ArenaMode.kurzerNameGleich("abc", "abcd"), "unter vier Zeichen nie");
        assertFalse(ArenaMode.kurzerNameGleich(null, "kall"));
    }

    @Test
    void zweiKaempfeMitDemselbenSiegerUndVerliererSindZweiKaempfe() {
        // 15:00:52, Stage 2: "Denis -> Kreyn +150 VICTORY" und "Kreyn -> Denis +0 DEFEAT"
        String denisSchlugKreyn = ArenaMode.fremderKampfSchluessel("denis", "kreyn", true);
        String kreynVerlorGegenDenis = ArenaMode.fremderKampfSchluessel("kreyn", "denis", false);
        assertNotEquals(denisSchlugKreyn, kreynVerlorGegenDenis, "vorher beide \"kreyn>denis\": die zweite Zeile fehlte");
        // Dieselbe Zeile in der naechsten Lesung bleibt derselbe Kampf (kein Doppelzaehlen).
        assertEquals(denisSchlugKreyn, ArenaMode.fremderKampfSchluessel("denis", "kreyn", true));
        // Gewinn und Niederlage derselben Paarung desselben Angreifers sind verschiedene Ergebnisse.
        assertNotEquals(ArenaMode.fremderKampfSchluessel("kreyn", "denis", true), ArenaMode.fremderKampfSchluessel("kreyn", "denis", false));
    }
}
