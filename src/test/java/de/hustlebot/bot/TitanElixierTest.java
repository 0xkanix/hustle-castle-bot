package de.hustlebot.bot;

import de.hustlebot.bot.TitanElixier.Ablauf;
import de.hustlebot.bot.TitanElixier.Element;
import de.hustlebot.bot.TitanElixier.Konfig;
import de.hustlebot.bot.TitanElixier.Plan;
import de.hustlebot.bot.TitanElixier.Verteilung;
import de.hustlebot.bot.TitanElixier.Zustand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 21.11.: Elixier-Umtausch im Clankrieg -- Plan ohne Bildschirm. */
class TitanElixierTest {

    private static final List<String> WERTE = List.of("Health", "Magic damage", "Armor", "Crit chance", "Dodge");

    @Test
    void vorlageHatAutorsListen() {
        Konfig k = Konfig.aus(TitanElixier.VORLAGE);
        assertEquals(List.of("Medusa", "Chief", "Heavyweight", "Moving Castle", "Bridge Guardian"), k.beste(Element.ERDE));
        assertTrue(k.beste(Element.WASSER).isEmpty());
        assertTrue(k.beste(Element.FEUER).isEmpty());
        assertEquals(List.of("Thundersaurus", "Medusa", "Torturer", "Keeper of Stars", "Last Guardian"),
                k.normal().stream().map(TitanElixier.Ziel::titan).toList());
    }

    @Test
    void sollVerteilungDesNormalenTeams() {
        Konfig k = Konfig.aus(TitanElixier.VORLAGE);
        Verteilung thunder = k.normal().get(0).verteilung();
        Verteilung keeper = k.normal().get(3).verteilung();
        Verteilung guardian = k.normal().get(4).verteilung();
        // Thundersaurus, Medusa, Torturer: Health + (Magic) Damage MAX
        assertEquals(List.of("Health", "Magic damage"), TitanElixier.maxReihenfolge(thunder, WERTE));
        assertEquals(List.of("Health", "Magic damage"), TitanElixier.maxReihenfolge(k.normal().get(1).verteilung(), WERTE));
        // Keeper of Stars: alles MAX
        assertEquals(WERTE, TitanElixier.maxReihenfolge(keeper, WERTE));
        // Last Guardian: alles MAX ausser Crit/Dodge, Crit bekommt am Ende den Rest
        assertEquals(List.of("Health", "Magic damage", "Armor", "Crit chance"), TitanElixier.maxReihenfolge(guardian, WERTE));
        assertFalse(guardian.maxen("Dodge"));
        assertFalse(guardian.maxen("Crit chance"));
        assertTrue(guardian.bekommtRest("Crit chance"));
    }

    @Test
    void verteilungLesen() {
        assertNull(Verteilung.aus(""));
        assertNull(Verteilung.aus(null));
        assertTrue(Verteilung.aus("alles").alles());
        assertTrue(Verteilung.aus("all").alles());
        Verteilung v = Verteilung.aus("Health + Damage");
        assertTrue(v.maxen("Physical damage"));
        assertFalse(v.maxen("Armor"));
        assertFalse(v.maxen(""));
    }

    @Test
    void planErde() {
        Plan p = TitanElixier.plan(Konfig.aus(TitanElixier.VORLAGE), Element.ERDE);
        assertNotNull(p);
        assertEquals(Element.ERDE, p.element());
        // vorher: normales Team resetten, dann die Erd-Titanen bei allem MAX
        assertEquals(List.of("Thundersaurus", "Medusa", "Torturer", "Keeper of Stars", "Last Guardian"), p.hinReset());
        assertEquals(List.of("Medusa", "Chief", "Heavyweight", "Moving Castle", "Bridge Guardian"),
                p.hinMax().stream().map(TitanElixier.Ziel::titan).toList());
        assertTrue(p.hinMax().stream().allMatch(z -> z.verteilung().alles()));
        // danach: Erd-Titanen resetten, normales Team nach Soll-Verteilung
        assertEquals(p.hinMax().stream().map(TitanElixier.Ziel::titan).toList(), p.zurueckReset());
        assertEquals(List.of("Thundersaurus", "Medusa", "Torturer", "Keeper of Stars", "Last Guardian"),
                p.zurueckMax().stream().map(TitanElixier.Ziel::titan).toList());
        assertFalse(p.zurueckMax().get(0).verteilung().alles());
    }

    @Test
    void keinUmtauschOhneElementOderListe() {
        Konfig k = Konfig.aus(TitanElixier.VORLAGE);
        assertNull(TitanElixier.plan(k, null));                 // Turm verlangt kein Element: alles bleibt
        assertNull(TitanElixier.plan(k, Element.WASSER));       // keine Wasser-Liste eingetragen
        assertNull(TitanElixier.plan(null, Element.ERDE));
        assertNull(TitanElixier.plan(Konfig.aus("erde: Medusa"), Element.ERDE));   // kein normales Team
    }

    @Test
    void konfigTolerant() {
        Konfig k = Konfig.aus("""
                # Kommentar
                Earth: Chief, chief , Boulder   # doppelt + Kommentar
                WASSER: Sea King, Tide Warrior
                quatsch ohne doppelpunkt
                mond: Lightbringer
                normal: Keeper of Stars
                normal: Medusa = ;
                normal: Keeper of  Stars = alles
                """);
        assertEquals(List.of("Chief", "Boulder"), k.beste(Element.ERDE));
        assertEquals(List.of("Sea King", "Tide Warrior"), k.beste(Element.WASSER));
        // "Medusa = ;" ist unlesbar und faellt weg, Keeper steht nur einmal drin (die letzte Zeile gilt)
        assertEquals(1, k.normal().size());
        assertTrue(k.normal().get(0).verteilung().alles());
        assertTrue(Konfig.aus(null).normal().isEmpty());
    }

    @Test
    void schalterNurMitAn() {
        assertTrue(TitanElixier.schalterText("an"));
        assertTrue(TitanElixier.schalterText("AN\n"));
        assertTrue(TitanElixier.schalterText("# Elixier-Umtausch\nan  # Autor 21.11."));
        assertFalse(TitanElixier.schalterText("aus"));
        assertFalse(TitanElixier.schalterText("anders"));
        assertFalse(TitanElixier.schalterText(""));
        assertFalse(TitanElixier.schalterText(null));
        // Klickweg noch nicht gebaut: auch mit Schalter an kein Umtausch
        if (!TitanElixier.KLICKWEG_GEBAUT) assertFalse(TitanElixier.umtauschMoeglich());
    }

    @Test
    void ablaufHinSetzenZurueck() {
        Plan p = TitanElixier.plan(Konfig.aus(TitanElixier.VORLAGE), Element.ERDE);
        Ablauf a = new Ablauf();
        assertEquals(Zustand.RUHE, a.zustand());
        assertFalse(a.starten(null));                // kein Plan: nichts umtauschen
        assertEquals(Zustand.RUHE, a.zustand());
        assertTrue(a.starten(p));
        assertEquals(Zustand.HIN, a.zustand());
        assertTrue(a.zurueckOffen());
        assertFalse(a.starten(p));                   // nicht doppelt starten
        a.gesetzt();                                 // falsche Reihenfolge: bleibt HIN
        assertEquals(Zustand.HIN, a.zustand());
        a.hinFertig();
        assertEquals(Zustand.SETZEN, a.zustand());
        assertFalse(a.zurueckOffen());
        a.gesetzt();
        assertEquals(Zustand.ZURUECK, a.zustand());
        a.zurueckFertig();
        assertEquals(Zustand.RUHE, a.zustand());
        assertNull(a.element());
    }

    @Test
    void abbruchTauschtTrotzdemZurueck() {
        Ablauf a = new Ablauf();
        a.starten(TitanElixier.plan(Konfig.aus(TitanElixier.VORLAGE), Element.ERDE));
        a.hinAbgebrochen();
        assertEquals(Zustand.ZURUECK, a.zustand());
        assertEquals(Element.ERDE, a.element());
    }

    @Test
    void standUeberNeustart() {
        Ablauf a = new Ablauf();
        a.starten(TitanElixier.plan(Konfig.aus(TitanElixier.VORLAGE), Element.ERDE));
        a.hinFertig();
        assertEquals("SETZEN earth", a.alsText());
        Ablauf b = Ablauf.ausText(a.alsText());
        assertEquals(Zustand.SETZEN, b.zustand());
        assertEquals(Element.ERDE, b.element());
        // mitten im Umtausch neu gestartet: zuerst zuruecktauschen
        assertEquals(Zustand.ZURUECK, Ablauf.ausText("HIN earth").zustand());
        assertEquals(Zustand.RUHE, Ablauf.ausText("").zustand());
        assertEquals(Zustand.RUHE, Ablauf.ausText("quatsch").zustand());
        assertEquals(Zustand.RUHE, Ablauf.ausText(null).zustand());
        assertNull(Ablauf.ausText("RUHE earth").element());
    }

    @Test
    void elementSchluessel() {
        assertEquals(Element.ERDE, Element.ausSchluessel("earth"));
        assertEquals(Element.ERDE, Element.ausSchluessel(" Erde "));
        assertEquals(Element.ERDE, Element.ausSchluessel("erd"));
        assertEquals(Element.DUNKEL, Element.ausSchluessel("dark"));
        assertNull(Element.ausSchluessel("neutral"));
        assertNull(Element.ausSchluessel(null));
    }
}
