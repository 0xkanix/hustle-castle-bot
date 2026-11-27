package de.hustlebot.bot;

import de.hustlebot.bot.TitanElixier.Element;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 21.11.: Element des Titanen-Turms lesen und Turmwahl (Name -> sonst meister Ruhm). */
class ClanWarElementTest {

    @Test
    void elementAusDemSatz() {
        assertEquals(Element.ERDE, ClanWarMode.elementAus("You can only deploy earth titans"));
        assertEquals(Element.WASSER, ClanWarMode.elementAus("You can only deploy water titans"));
        assertEquals(Element.FEUER, ClanWarMode.elementAus("You can only deploy fire titans"));
        assertEquals(Element.LICHT, ClanWarMode.elementAus("You can only deploy light titans"));
        assertEquals(Element.DUNKEL, ClanWarMode.elementAus("You can only deploy dark titans"));
        // ganzer Kasten mit Ueberschrift und Ruhm-Zeile darunter
        assertEquals(Element.ERDE, ClanWarMode.elementAus(
                "Special requirements for deploying a squad at waypoint: You can only deploy earth titans Glory for winning: 11 500"));
        // deutsch
        assertEquals(Element.ERDE, ClanWarMode.elementAus("Du kannst nur Erd-Titanen einsetzen"));
        assertEquals(Element.ERDE, ClanWarMode.elementAus("nur Erde"));
        assertEquals(Element.WASSER, ClanWarMode.elementAus("nur Wassertitanen"));
    }

    @Test
    void elementTrotzLesefehlern() {
        assertEquals(Element.ERDE, ClanWarMode.elementAus("You can only deploy carth titans"));
        assertEquals(Element.ERDE, ClanWarMode.elementAus("You can only dep1oy eart titans"));
        assertEquals(Element.ERDE, ClanWarMode.elementAus("YOU CAN ONLY DEPLOY EARTH TITANS."));
        assertEquals(Element.WASSER, ClanWarMode.elementAus("only deploy watcr titans"));
        assertEquals(Element.FEUER, ClanWarMode.elementAus("only deploy flre titans"));
        assertEquals(Element.LICHT, ClanWarMode.elementAus("only deploy 1ight titans"));
        assertEquals(Element.LICHT, ClanWarMode.elementAus("only deploy iight titans"));
        assertEquals(Element.DUNKEL, ClanWarMode.elementAus("only deploy dark,titans"));
        assertEquals(Element.DUNKEL, ClanWarMode.elementAus("only deploy darrk titans"));
    }

    @Test
    void keinElement() {
        assertNull(ClanWarMode.elementAus(null));
        assertNull(ClanWarMode.elementAus(""));
        assertNull(ClanWarMode.elementAus("   "));
        assertNull(ClanWarMode.elementAus("Glory for winning: 11 500"));
        assertNull(ClanWarMode.elementAus("Special requirements for deploying a squad at waypoint"));
        assertNull(ClanWarMode.elementAus("Your throne room has to be at level 10"));
        assertNull(ClanWarMode.elementAus("You can only deploy titans"));
    }

    @Test
    void zweiElementeNurMitSatzstelle() {
        // das Wort direkt nach "deploy" zaehlt
        assertEquals(Element.FEUER, ClanWarMode.elementAus("You can only deploy fire titans. light"));
        // oder das direkt vor "titans"
        assertEquals(Element.WASSER, ClanWarMode.elementAus("dark ... water titans"));
        // ohne Satzstelle: lieber nichts
        assertNull(ClanWarMode.elementAus("earth water"));
    }

    @Test
    void verlangtNurMitSatz() {
        assertEquals(Element.ERDE, ClanWarMode.verlangtesElement("You can only deploy earth titans"));
        assertEquals(Element.ERDE, ClanWarMode.verlangtesElement("earth titans"));
        // ein Element-Wort ohne den Satz loest nichts aus
        assertNull(ClanWarMode.verlangtesElement("Dark Outpost Glory for winning 5 000"));
        assertNull(ClanWarMode.verlangtesElement(null));
        assertTrue(ClanWarMode.elementPflicht("you can only deploy earth titans"));
        assertFalse(ClanWarMode.elementPflicht("glory for winning: 5 000"));
    }

    @Test
    void abstandEins() {
        assertTrue(ClanWarMode.abstandHoechstensEins("earth", "earth"));
        assertTrue(ClanWarMode.abstandHoechstensEins("eart", "earth"));
        assertTrue(ClanWarMode.abstandHoechstensEins("earths", "earth"));
        assertTrue(ClanWarMode.abstandHoechstensEins("carth", "earth"));
        assertFalse(ClanWarMode.abstandHoechstensEins("ear", "earth"));
        assertFalse(ClanWarMode.abstandHoechstensEins("north", "earth"));
        assertFalse(ClanWarMode.abstandHoechstensEins("for", "fire"));
    }

    // ---- Turmwahl ------------------------------------------------------------------------------------------------

    private static ClanWarMode.TurmInfo turm(String titel, int ruhm) {
        return new ClanWarMode.TurmInfo(titel, "", ruhm, false, false);
    }

    @Test
    void meisterRuhmOhneTempel() {
        ClanWarMode.TurmInfo a = turm("Wrath Outpost", 5300);
        ClanWarMode.TurmInfo b = turm("Rage Outpost", 11500);
        ClanWarMode.TurmInfo tempel = turm("Temple of the Unstoppable Elements", 20000);
        ClanWarMode.TurmInfo ohne = turm("Fury Outpost", -1);
        List<ClanWarMode.TurmInfo> alle = List.of(a, b, tempel, ohne);
        // Umtausch nicht moeglich: Tempel (Feuer-Pflicht) erst nach allen anderen, ohne Ruhm faellt weg
        assertEquals(List.of(b, a, tempel), ClanWarMode.nachRuhm(alle, Function.identity(), true));
        assertEquals(b, ClanWarMode.meisterRuhm(alle, Function.identity(), true).orElseThrow());
        // nur Tempel: dann der Tempel
        assertEquals(tempel, ClanWarMode.meisterRuhm(List.of(tempel, ohne), Function.identity(), true).orElseThrow());
        // Umtausch moeglich: Autors Regel pur -- meister Ruhm, auch ein Tempel
        assertEquals(tempel, ClanWarMode.meisterRuhm(alle, Function.identity(), false).orElseThrow());
        assertTrue(ClanWarMode.meisterRuhm(List.of(ohne), Function.identity(), true).isEmpty());
    }

    @Test
    void nameVorRuhm() {
        // Autor 21.11.: erst der Turm mit "Autor" auf dem Zettel (auch an 7. Stelle in der Blase)
        assertTrue(ClanWarMode.enthaeltNamen("Ben ; Bruno ; Carl ; Paula ; Anna ; Sam ; Autor", "Autor"));
        assertFalse(ClanWarMode.enthaeltNamen("Ben ; Bruno", "Autor"));
    }
}
