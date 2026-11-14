package de.hustlebot.schicht;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Echte Texte aus Autors Arena 30.10. */
class SeitenFensterTextTest {

    private static ArenaStand.Gegner gegner(String erklaerung, String listen) {
        return new ArenaStand.Gegner(100, 100, 8, "Miss Malindi", "", true, "sicher", false, false, 150, 96, erklaerung, listen, true);
    }

    @Test
    void ganzUnbekannterSpielerZeigtKeinePunkte() {
        String aus = SeitenFenster.erklaerungKurz("-40 points (team unknown, power unknown), 113 unter deinem Team (73), Gesamtbilanz 89 % aus 161 Kaempfen");
        assertFalse(aus.contains("points"), aus);
        assertFalse(aus.contains("unknown"), aus);
        assertFalse(aus.contains("deinem Team"), aus);
        assertTrue(aus.contains("Gesamtbilanz 89 %"), aus);
    }

    @Test
    void auchMitDeutschemTextUndOhneAbstand() {
        String aus = SeitenFenster.erklaerungKurz("70 Punkte (Team unbekannt, Kraft unbekannt), Erfahrung 66 % aus 24 Kaempfen");
        assertFalse(aus.contains("Punkte"), aus);
        assertTrue(aus.contains("Erfahrung 66 %"), aus);
    }

    @Test
    void bekannterSpielerBehaeltSeinWissenOhneUnbekannt() {
        assertEquals("55 points (team 70), 18 unter deinem Team (73)",
                SeitenFenster.erklaerungKurz("55 points (team 70, power unknown), 18 unter deinem Team (73)"));
        assertEquals("55 Punkte (Team 70)", SeitenFenster.erklaerungKurz("55 Punkte (Team 70, Kraft unbekannt)"));
        assertEquals("Notizblock 10/10, 31.719.948, 3 starke Artefakte",
                SeitenFenster.erklaerungKurz("Notizblock 10/10, 31.719.948, 3 starke Artefakte"));
    }

    @Test
    void beiListeStehenBeiUnbekanntenKeinePunkte() {
        List<String> p = SeitenFenster.posten(gegner("-40 points (team unknown, power unknown), 113 unter deinem Team (73)", "SICHERER SIEG (-40)"));
        assertTrue(p.contains("SICHERER SIEG"), p.toString());
        assertFalse(p.toString().contains("-40"), p.toString());
    }

    /** Autor 30.10.: "Wenn ich in dieser Stage schon angegriffen habe, kann er mir keinen anderen mehr vorschlagen." */
    @Test
    void nachDemAngriffGibtEsKeinBotZielMehr() {
        ArenaStand.Gegner ziel = gegner("Notizblock 10/10", "");
        var stand = new ArenaStand.Stand(1, 2, 1159, 770, List.of(ziel), List.of());
        assertEquals(ziel, SchichtSteuerung.ziel(stand));
        var ohne = stand.ohneZiel();
        assertEquals(null, SchichtSteuerung.ziel(ohne));
        assertEquals(96, ohne.gegner().get(0).siegchance(), "die Chance bleibt stehen");
        assertEquals("Miss Malindi", ohne.gegner().get(0).name());
        assertEquals(2, ohne.runde());
    }

    // ---- Englischer Text und Steckbrief (Autor 30.10.) -------------------------------------------------------

    private static ArenaStand.Gegner gegnerMitSteckbrief(String erklaerung, String steckbrief) {
        return new ArenaStand.Gegner(100, 100, 8, "Flimsy", "Clan", false, "", false, false, 150, 90, erklaerung, "", false, steckbrief);
    }

    @Test
    void ganzUnbekannterSpielerAufEnglischZeigtKeinePunkteUndKeinUnknown() {
        String aus = SeitenFenster.erklaerungKurz(
                "-40 points (team unknown, power unknown), 113 below your team (73), overall record 89 % from 161 fights");
        assertFalse(aus.contains("points"), aus);
        assertFalse(aus.contains("unknown"), aus);
        assertFalse(aus.contains("your team"), aus);
        assertTrue(aus.contains("overall record 89 %"), aus);
    }

    @Test
    void teileTrenntNichtInKlammern() {
        List<String> t = SeitenFenster.teile("55 points (team 40, power difference +1.2 M = +3), 18 below your team (73)");
        assertEquals(2, t.size(), t.toString());
        assertEquals("55 points (team 40, power difference +1.2 M = +3)", t.get(0));
        assertEquals("18 below your team (73)", t.get(1));
        assertEquals(List.of("a", "b (c, d)", "e"), SeitenFenster.teile("a, b (c, d), e"));
        assertEquals(List.of("31,719,948"), SeitenFenster.teile("31,719,948"), "Komma ohne Leerzeichen trennt nicht");
    }

    /** Autor 30.10.: der Steckbrief steht oben; was er schon sagt (Bilanz, nackte Kraft), steht im Rechenzettel nicht noch einmal. */
    @Test
    void mitSteckbriefStehenSeineZeilenDrinUndBilanzUndNackteKraftFallenWeg() {
        List<String> p = SeitenFenster.posten(gegnerMitSteckbrief("Notebook 10/10, 31,719,948, 3 strong artifacts",
                "Won 10 · lost 0 · last: S S S S S\nWin chance 90 % · lose chance 10 %"));
        assertEquals(List.of("Won 10 · lost 0 · last: S S S S S", "Win chance 90 % · lose chance 10 %", "3 strong artifacts"), p);
        assertFalse(p.contains("Notebook 10/10"), p.toString());
        assertFalse(p.contains("31,719,948"), p.toString());
        assertTrue(p.contains("3 strong artifacts"), p.toString());
    }

    @Test
    void ohneSteckbriefBleibtDerRechenzettelWieEr() {
        List<String> p = SeitenFenster.posten(gegnerMitSteckbrief("Notebook 10/10, 31,719,948, 3 strong artifacts", ""));
        assertEquals(List.of("Notebook 10/10", "31,719,948", "3 strong artifacts"), p);
        // Leerer Steckbrief aus lauter Leerzeilen zaehlt auch nicht
        assertEquals(p, SeitenFenster.posten(gegnerMitSteckbrief("Notebook 10/10, 31,719,948, 3 strong artifacts", " \n ")));
    }

    @Test
    void dieZwischenlistenHabenTasteTextUndFarbe() {
        assertEquals("fastsicher", SchichtSteuerung.stufeAusZiffer('5'));
        assertEquals("pause", SchichtSteuerung.stufeAusZiffer('6'));
        assertEquals("almost safe", SchichtSteuerung.stufeText("fastsicher"));
        assertEquals("pause", SchichtSteuerung.stufeText("pause"));
        assertEquals(SchichtFenster.FAST_SICHER, SeitenFenster.farbe("fastsicher"));
        assertEquals(SchichtFenster.PAUSE, SeitenFenster.farbe("pause"));
    }

    @Test
    void beiBekanntenBleibtDerListenWert() {
        List<String> p = SeitenFenster.posten(gegner("55 points (team 70, power unknown)", "SICHERER SIEG (-40)"));
        assertTrue(p.contains("SICHERER SIEG (-40)"), p.toString());
    }
}
