package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 21.11. (Ziel 65 s je Kathedralen-Run): Anlauf-Kastenblick nach dem Wegpunkt und Etagenwechsel im Kastenblick.
 * Der Weg selbst braucht das Spiel; hier stehen die Regeln, auf die er sich verlaesst.
 */
class KathedraleAnlaufTest {

    @Test
    void anlaufKastenblickIstOhneDateiAn() {
        // Eigener Schalter, weil fotozyklus_kampf in schon angelegten lernwerte.txt auf "aus" steht -- fehlt der neue
        // Wert in der Datei, gilt der Standard.
        Lernwerte w = new Lernwerte();
        assertTrue(w.an("anlauf_kastenblick"));
        assertFalse(w.an("fotozyklus_kampf"), "der alte Fotozyklus bleibt aus");
        assertTrue(w.an("kastenblick"));
    }

    @Test
    void kastenFristEndetVorDemFensterSchutz() {
        // Kommt in der Frist nichts, uebernimmt die Schrittkette -- mit dem F7Y7-Schutz (kein zweiter Wegpunkt bis
        // WEGPUNKT_FENSTER_MS nach dem Klick). Die Frist muss also deutlich davor enden.
        assertTrue(CathedralMode.ANLAUF_KASTEN_MS >= 450, "mindestens so lange wie der alte Kastenblick");
        assertTrue(CathedralMode.ANLAUF_KASTEN_MS * 2 <= CathedralMode.WEGPUNKT_FENSTER_MS);
        assertTrue(CathedralMode.aufWegpunktFensterWarten(true, CathedralMode.ANLAUF_KASTEN_MS,
                CathedralMode.WEGPUNKT_FENSTER_MS), "nach der Frist wird auf der Karte weiter gewartet");
    }

    @Test
    void aufstellenSparenNurWennAllesPasst() {
        assertFalse(new Lernwerte().an("aufstellen_sparen"), "Standard aus");
        assertTrue(CathedralMode.aufstellenSparbar(true, false, true, 0, true));
        assertFalse(CathedralMode.aufstellenSparbar(false, false, true, 0, true), "Schalter aus");
        assertFalse(CathedralMode.aufstellenSparbar(true, true, true, 0, true), "nach einer Warnung gesperrt");
        assertFalse(CathedralMode.aufstellenSparbar(true, false, false, 0, true), "Truppe steht nicht (Fenster, Etage, Niederlage)");
        assertFalse(CathedralMode.aufstellenSparbar(true, false, true, -1, true), "nicht nach Gefallenen gesucht");
        assertFalse(CathedralMode.aufstellenSparbar(true, false, true, 1, true), "Gefallene gesehen");
        assertFalse(CathedralMode.aufstellenSparbar(true, false, true, 0, false), "Boss oder Waechter");
    }

    @Test
    void einfacherKampfOhneBossUndWaechter() {
        assertTrue(CathedralMode.einfacherKampf("dc_skirmish", "stepping stone (fixed map, 11 to go)"));
        assertTrue(CathedralMode.einfacherKampf("dc_skirmish", "stepping stone, reveals a question mark (fixed map, 8 to go)"));
        assertFalse(CathedralMode.einfacherKampf("dc_minion", "stepping stone (fixed map, 3 to go)"));
        assertFalse(CathedralMode.einfacherKampf("dc_skirmish", "Boss (fixed map, 7 to go)"));
        assertFalse(CathedralMode.einfacherKampf("dc_guardian_lair", "Floor guardian (all bosses done)"));
        assertFalse(CathedralMode.einfacherKampf("netz", "Etagenwaechter (alle Bosse erledigt)"));
        assertFalse(CathedralMode.einfacherKampf(null, "Boss zuerst (Kampf-Wegpunkt)"));
    }

    @Test
    void teilnahmeWartetNurKurzAufLesbareSchluessel() {
        long g = CathedralMode.TEILNAHME_LESE_GEDULD_MS;
        assertTrue(CathedralMode.teilnahmeNochWarten(false, true, 0, g), "unlesbar, schon mal gelesen: warten");
        assertTrue(CathedralMode.teilnahmeNochWarten(false, true, g - 1, g));
        assertFalse(CathedralMode.teilnahmeNochWarten(false, true, g, g), "Geduld um: wie bisher klicken");
        assertFalse(CathedralMode.teilnahmeNochWarten(true, true, 100, g), "lesbar: sofort klicken");
        assertFalse(CathedralMode.teilnahmeNochWarten(false, false, 100, g), "auf diesem PC nie gelesen: nicht warten");
        assertFalse(CathedralMode.teilnahmeNochWarten(false, true, -1, g));
    }

    @Test
    void jaNachNaechsterEtageNurKurz() {
        assertTrue(CathedralMode.NAECHSTE_ETAGE_JA_MS > 0 && CathedralMode.NAECHSTE_ETAGE_JA_MS <= 1000);
    }
}
