package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Steuerprogramm darf nur Texte bauen, die auch in Discord gehen -- hier wird
 * geprueft, dass genau diese Texte herauskommen (Auftrag 1, 11.10.).
 */
class BefehlsbauTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    @Test
    void start_kathedraleMitZahlUndWeg() {
        assertEquals("!start cathedral 1000 c",
                Befehlsbau.start(Befehlsbau.Modus.KATHEDRALE, 1000, "c", Befehlsbau.Bezahlart.STANDARD, "", List.of()));
        assertEquals("!start cathedral 3 b",
                Befehlsbau.start(Befehlsbau.Modus.KATHEDRALE, 3, "B", null, null, null));
    }

    @Test
    void start_wegNurBeiKathedrale() {
        assertEquals("!start dungeon 10",
                Befehlsbau.start(Befehlsbau.Modus.VERLIES, 10, "c", Befehlsbau.Bezahlart.STANDARD, "", List.of()));
    }

    @Test
    void start_edelsteinbuchtOhneZahl() {
        assertEquals("!start gembay",
                Befehlsbau.start(Befehlsbau.Modus.GEMBAY, 10, "c", Befehlsbau.Bezahlart.STANDARD, "", List.of()));
        assertEquals(Befehlsbau.Modus.GEMBAY, Befehlsbau.Modus.aus("Edelsteinbucht"));
        assertEquals(Befehlsbau.Modus.GEMBAY, Befehlsbau.Modus.aus("gem bay"));
    }

    @Test
    void start_portalMitPlan() {
        assertEquals("!start portal bis 135 farm 80:2mio,135:2mio,130:1,5mio",
                Befehlsbau.start(Befehlsbau.Modus.PORTAL, 1, "c", Befehlsbau.Bezahlart.STANDARD, "",
                        Befehlsbau.portalPlan("135", "80: 2 Mio; 135: 2 Mio; 130: 1,5 Mio")));
        assertEquals("!start portal", Befehlsbau.start(Befehlsbau.Modus.PORTAL, 1, "c", Befehlsbau.Bezahlart.STANDARD, "",
                Befehlsbau.portalPlan("", "")));
    }

    @Test
    void portalFarm_zweiLevelMitNahrung() {
        assertEquals("135:2 Mio,80:500k", Befehlsbau.portalFarm("135", "2 Mio", "80", "500k"));
        assertEquals("!start portal bis 135 farm 135:2mio,80:500k",
                Befehlsbau.start(Befehlsbau.Modus.PORTAL, 1, "c", Befehlsbau.Bezahlart.STANDARD, "",
                        Befehlsbau.portalPlan("135", Befehlsbau.portalFarm("135", "2 Mio", "80", "500k"))));
        // Level ohne Nahrung faellt weg, nur das zweite bleibt
        assertEquals("80:1,5 Mio", Befehlsbau.portalFarm("135", "", "80", "1,5 Mio"));
        assertEquals("", Befehlsbau.portalFarm("", "", "", ""));
    }

    @Test
    void start_verliesMitFreund() {
        assertEquals("!start dungeon 10 lisa",
                Befehlsbau.start(Befehlsbau.Modus.VERLIES, 10, "", null, "lisa", List.of()));
        // Mehrere Freunde mit Komma, wie in der Hilfe ("wetter,reaper,locke").
        assertEquals("!start dungeon 5 wetter,reaper",
                Befehlsbau.start(Befehlsbau.Modus.VERLIES, 5, "", null, "wetter reaper", List.of()));
    }

    @Test
    void start_bezahlartUndExtras() {
        assertEquals("!start arena 10 food",
                Befehlsbau.start(Befehlsbau.Modus.ARENA, 10, "", Befehlsbau.Bezahlart.FOOD, "", List.of()));
        assertEquals("!start cathedral 50 c alles pcaus ohnekrieg",
                Befehlsbau.start(Befehlsbau.Modus.KATHEDRALE, 50, "c", Befehlsbau.Bezahlart.ALLES, "",
                        List.of("pcaus", "", "OhneKrieg")));
    }

    @Test
    void start_ohneZahlBeiEinmalModi() {
        assertEquals("!start clanwar",
                Befehlsbau.start(Befehlsbau.Modus.CLANKRIEG, 5, "", null, "", List.of()));
        assertEquals("!start lighthouse",
                Befehlsbau.start(Befehlsbau.Modus.LEUCHTTURM, 1, "", null, "", List.of()));
        assertEquals("!start event hammer",
                Befehlsbau.start(Befehlsbau.Modus.HAMMER, 0, "", null, "", List.of()));
    }

    @Test
    void start_zahlWirdGedeckelt() {
        assertEquals("!start cathedral 100000 c",
                Befehlsbau.start(Befehlsbau.Modus.KATHEDRALE, 5_000_000, "c", null, "", List.of()));
    }

    @Test
    void danach_baut_after() {
        assertEquals("!after cathedral 50 c",
                Befehlsbau.danach(Befehlsbau.Modus.KATHEDRALE, 50, "c", null, ""));
        assertEquals("!after dungeon 20",
                Befehlsbau.danach(Befehlsbau.Modus.VERLIES, 20, "", Befehlsbau.Bezahlart.STANDARD, ""));
    }

    @Test
    void modus_ausWort_deutschUndEnglisch() {
        assertEquals(Befehlsbau.Modus.KATHEDRALE, Befehlsbau.Modus.aus("Kathedrale"));
        assertEquals(Befehlsbau.Modus.KATHEDRALE, Befehlsbau.Modus.aus("cath"));
        assertEquals(Befehlsbau.Modus.VERLIES, Befehlsbau.Modus.aus("dungeon"));
        assertEquals(Befehlsbau.Modus.GRABUNG, Befehlsbau.Modus.aus("Grabung"));
        assertEquals(Befehlsbau.Modus.TITANTURM, Befehlsbau.Modus.aus("Titanen-Turm"));
        assertEquals(Befehlsbau.Modus.CLANKRIEG, Befehlsbau.Modus.aus("clan war"));
        assertEquals(Befehlsbau.Modus.HAMMER, Befehlsbau.Modus.aus("hammer"));
        assertNull(Befehlsbau.Modus.aus("irgendwas"));
        assertNull(Befehlsbau.Modus.aus(null));
    }

    @Test
    void stigma_befehleMitTitanUndProbe() {
        assertEquals("!stigmas loeschen", Befehlsbau.Stigma.LOESCHEN.befehl("", false));
        assertEquals("!stigmas loeschen probe", Befehlsbau.Stigma.LOESCHEN.befehl(null, true));
        assertEquals("!stigmas leveln medusa", Befehlsbau.Stigma.LEVELN.befehl("Medusa", false));
        assertEquals("!stigmas hundert keeper probe", Befehlsbau.Stigma.HUNDERT.befehl("keeper", true));
        // Rangliste und Einstellen kennen weder Titan noch Probe.
        assertEquals("!stigmas rangliste", Befehlsbau.Stigma.RANGLISTE.befehl("medusa", true));
    }

    @Test
    void einstellung_baut_settings() {
        assertEquals("!settings bezahlen verlies food", Befehlsbau.einstellung("bezahlen", "verlies food"));
        assertEquals("!settings nahrungslimit", Befehlsbau.einstellung(" nahrungslimit ", ""));
        assertEquals("!settings", Befehlsbau.einstellung(null, null));
    }

    @Test
    void gefaehrlich_erkenntAlleSchreibweisen() {
        assertTrue(Befehlsbau.gefaehrlich("!notaus"));
        assertTrue(Befehlsbau.gefaehrlich("!kill"));
        assertTrue(Befehlsbau.gefaehrlich("!beenden"));
        assertTrue(Befehlsbau.gefaehrlich("!quit"));
        assertTrue(Befehlsbau.gefaehrlich("!aus jetzt"));
        assertTrue(Befehlsbau.gefaehrlich("!off now"));
        assertTrue(Befehlsbau.gefaehrlich("!pc off"));
        assertTrue(Befehlsbau.gefaehrlich("! neustart"));
        assertFalse(Befehlsbau.gefaehrlich("!stop"));
        assertFalse(Befehlsbau.gefaehrlich("!pause"));
        assertFalse(Befehlsbau.gefaehrlich("!start cathedral 10 c"));
        assertFalse(Befehlsbau.gefaehrlich(""));
        assertFalse(Befehlsbau.gefaehrlich(null));
    }

    @Test
    void verb_undZahl() {
        assertEquals("!start", Befehlsbau.verb("!Start cathedral 10"));
        assertEquals("!stop", Befehlsbau.verb("! stop"));
        assertEquals(1000, Befehlsbau.zahl("start cath 1000 c", 1));
        assertEquals(1, Befehlsbau.zahl("start cath c", 1));
        assertEquals(List.of("!start", "cathedral", "10"), Befehlsbau.woerter("  !start   cathedral 10 "));
    }

    @Test
    void ende_undPcAus() {
        assertEquals("!ende 06:30", Befehlsbau.ende("06:30"));
        assertEquals("!ende", Befehlsbau.ende(""));
        assertEquals("!aus jetzt", Befehlsbau.pcAus("jetzt"));
        assertEquals("!aus", Befehlsbau.pcAus(null));
    }
}
