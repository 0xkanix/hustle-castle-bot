package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

/** Clan-Krieg: Funde des Pruefers vom 29.10. (langsamer Server, "Editing mode", fremde Tuerme). */
class ClanWarPrueferTest {

    // ---- A: zwei Confirm-Klicks sind noch keine zwei Trupps ----

    @Test
    void fertigZaehltNurImTurmGesehenes() {
        var z = new ClanWarMode.Aufstellungen();
        z.bestaetigt(false);
        z.gesehen();                       // Kontrolle im Turm: normaler Trupp steht
        z.bestaetigt(true);                // zweites Confirm -- naechstes Bild zeigt noch das Truppenfenster
        assertEquals(2, z.anzahl(), "zwei Klicks");
        assertEquals(1, z.gesehenAnzahl(), "aber erst ein Trupp gesehen -- noch NICHT fertig");
        z.gesehen();                       // Turmfenster kam wieder, Titan steht drin
        assertEquals(2, z.gesehenAnzahl());
    }

    @Test
    void zuruecknehmenLoeschtAuchDasGesehene() {
        var z = new ClanWarMode.Aufstellungen();
        z.bestaetigt(true);
        z.gesehen();
        z.zuruecknehmen();                 // Rauswurf direkt danach
        assertEquals(0, z.gesehenAnzahl());
        z.bestaetigt(true);
        assertEquals(0, z.gesehenAnzahl(), "neu bestaetigt, aber noch nicht wieder gesehen");
        z.gesehen();
        assertEquals(1, z.gesehenAnzahl());
        z.leeren();
        assertEquals(0, z.gesehenAnzahl());
        z.gesehen();                       // nichts bestaetigt -- nichts zu sehen
        assertEquals(0, z.gesehenAnzahl());
    }

    // ---- E: Sperrzone "Editing mode" (586,204 bei 1159x770) ----

    @Test
    void berechneterKlickNieAufEditingMode() {
        assertTrue(ClanWarMode.inSperrzone(586, 204, 1159, 770, null, true));
        assertTrue(ClanWarMode.inSperrzone(500, 190, 1159, 770, null, true), "Rand des Knopfs");
        assertFalse(ClanWarMode.inSperrzone(581, 300, 1159, 770, null, true), "Turm Dragon's breath (TR5 14:55)");
        assertFalse(ClanWarMode.inSperrzone(460, 398, 1159, 770, null, true));
        // doppelt so grosses Fenster: dieselbe Stelle, nur skaliert
        assertTrue(ClanWarMode.inSperrzone(1172, 408, 2318, 1540, null, true));
        assertFalse(ClanWarMode.inSperrzone(586, 204, 2318, 1540, null, true));
    }

    @Test
    void vorlagenKlickNurGesperrtWennEditingModeZuSehen() {
        // "Deploy squad" liegt im Turmfenster genau dort -- als Vorlage erlaubt, solange "Editing mode" nicht im Bild ist
        assertFalse(ClanWarMode.inSperrzone(586, 204, 1159, 770, null, false));
        Rectangle edit = new Rectangle(496, 182, 180, 44);
        assertTrue(ClanWarMode.inSperrzone(586, 204, 1159, 770, edit, false));
        assertTrue(ClanWarMode.inSperrzone(490, 180, 1159, 770, edit, false), "kleiner Rand um das Bild");
        assertFalse(ClanWarMode.inSperrzone(78, 691, 1159, 770, edit, false), "Back unten links bleibt erlaubt");
    }

    // ---- B: Tempel sind Titanen-Tuerme ----

    @Test
    void tempelIstTitanenTurm() {
        assertTrue(ClanWarMode.titanenName("temple of the unstoppable elements"));
        assertTrue(ClanWarMode.titanenName("Temple of the Rampaging Elements"));
        assertTrue(ClanWarMode.titanenName("ple of the Unstoppable Eleme"), "Server 30.10.: abgeschnitten gelesen");
        for (String normal : new String[]{"Blood Furnace", "Field of glory", "Treachery of Kings", "Lair of horror", "Dragon's breath",
                "Struggle road", "Knight's Halt"}) {
            assertFalse(ClanWarMode.titanenName(normal), normal);
        }
        assertTrue(ClanWarMode.titanenName("Wrath Outpost"));
        assertTrue(ClanWarMode.titanenName("wrath out post"), "Texterkennung mit Luecke");
        assertTrue(ClanWarMode.titanenName("Aussenposten des Zorns"));
        assertTrue(ClanWarMode.titanenName("Tempel der Elemente"));
        assertFalse(ClanWarMode.titanenName("Struggle road"));
        assertFalse(ClanWarMode.titanenName("Dragon's breath"));
        assertFalse(ClanWarMode.titanenName("Knight's Halt"));
        assertFalse(ClanWarMode.titanenName(null));
    }

    // ---- C: beim Ausweichen gelesene Tuerme ohne fremden Namen zuerst ----

    @Test
    void ausweichenNimmtGeleseneVorUngelesenen() {
        var leerUngelesen = new ClanWarMode.Turm(100, 300, false, true, 0, 3);
        var halbGelesen = new ClanWarMode.Turm(300, 300, false, false, 1, 3);
        var leerGelesen = new ClanWarMode.Turm(500, 300, false, true, 0, 3);
        java.util.Set<ClanWarMode.Turm> gelesen = new java.util.HashSet<>(java.util.List.of(halbGelesen));

        assertEquals(halbGelesen, ClanWarMode.ausweichWahl(java.util.List.of(leerUngelesen, halbGelesen), gelesen::contains).orElseThrow(),
                "ein ungelesener (vielleicht reservierter) leerer Turm verliert gegen einen gelesenen");
        gelesen.add(leerGelesen);
        assertEquals(leerGelesen, ClanWarMode.ausweichWahl(java.util.List.of(leerUngelesen, halbGelesen, leerGelesen), gelesen::contains).orElseThrow(),
                "unter den gelesenen wie bisher der leere");
        assertEquals(leerUngelesen, ClanWarMode.ausweichWahl(java.util.List.of(leerUngelesen), t -> false).orElseThrow(),
                "nur ungelesene: dann doch einer davon");
        assertTrue(ClanWarMode.ausweichWahl(java.util.List.of(), t -> true).isEmpty());
    }

    @Test
    void leseFehlerZaehltNichtAlsGelesen() {
        assertFalse(ClanWarMode.gelesen(null));
        assertFalse(ClanWarMode.gelesen(new ClanWarMode.TurmInfo("", "", -1, false, false)));
        assertTrue(ClanWarMode.gelesen(new ClanWarMode.TurmInfo("Dragon's breath", "", -1, false, false)));
        assertTrue(ClanWarMode.gelesen(new ClanWarMode.TurmInfo("", "Anna ; Mia", -1, false, false)));
    }

    // ---- F: Warten auf einen freien Turm -- Zeit statt Bilder, "voll" nur fuer die gesuchte Sorte ----

    @Test
    void geduldIstDreiMinuten() {
        assertFalse(ClanWarMode.ohneZielGeduldUm(0));
        assertFalse(ClanWarMode.ohneZielGeduldUm(179_999));
        assertTrue(ClanWarMode.ohneZielGeduldUm(180_000));
    }

    @Test
    void vollZaehltNurDieGesuchteSorte() {
        var outpostVoll = new ClanWarMode.Turm(800, 300, true, false, 6, 6);
        var templeVoll = new ClanWarMode.Turm(900, 400, true, false, 6, 6);
        var normalUngelesen = new ClanWarMode.Turm(200, 300, false, false, -1, -1);
        var normalFrei = new ClanWarMode.Turm(300, 300, false, false, 1, 3);
        var titan = java.util.List.of(ClanWarMode.Sorte.AUSSENPOSTEN);

        assertTrue(ClanWarMode.alleGesuchtenVoll(java.util.List.of(outpostVoll, templeVoll, normalUngelesen, normalFrei), titan),
                "ein ungelesener NORMALER Turm (Server: 8 von 13 gelesen) haelt das Titan-Warten nicht mehr auf");
        assertFalse(ClanWarMode.alleGesuchtenVoll(java.util.List.of(outpostVoll, new ClanWarMode.Turm(900, 400, true, false, -1, 6)), titan),
                "ein ungelesener Aussenposten ist kein sicheres 'voll'");
        assertFalse(ClanWarMode.alleGesuchtenVoll(java.util.List.of(normalFrei), titan), "kein Aussenposten erkannt = nicht voll");
        assertFalse(ClanWarMode.alleGesuchtenVoll(java.util.List.of(outpostVoll, normalFrei),
                java.util.List.of(ClanWarMode.Sorte.AUSSENPOSTEN, ClanWarMode.Sorte.NORMAL)));
        assertFalse(ClanWarMode.alleGesuchtenVoll(java.util.List.of(outpostVoll), java.util.List.of()));
    }

    @Test
    void ohneFenstergroesseNurDasGeseheneBild() {
        assertFalse(ClanWarMode.inSperrzone(586, 204, 0, 0, null, true));
        assertTrue(ClanWarMode.inSperrzone(586, 204, 0, 0, new Rectangle(496, 182, 180, 44), true));
    }
}
