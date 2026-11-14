package de.hustlebot.schicht;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static de.hustlebot.schicht.SchichtSteuerung.VK_BACK;
import static de.hustlebot.schicht.SchichtSteuerung.VK_C;
import static de.hustlebot.schicht.SchichtSteuerung.VK_DOWN;
import static de.hustlebot.schicht.SchichtSteuerung.VK_ESCAPE;
import static de.hustlebot.schicht.SchichtSteuerung.VK_K;
import static de.hustlebot.schicht.SchichtSteuerung.VK_L;
import static de.hustlebot.schicht.SchichtSteuerung.VK_LEFT;
import static de.hustlebot.schicht.SchichtSteuerung.VK_RETURN;
import static de.hustlebot.schicht.SchichtSteuerung.VK_RIGHT;
import static de.hustlebot.schicht.SchichtSteuerung.VK_SPACE;
import static de.hustlebot.schicht.SchichtSteuerung.VK_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arena-Schicht: Bedienung nur mit der Tastatur (Autor 29.10. abends: ↑/↓ + Ziffern) -- reine Tastenlogik. */
class SchichtSteuerungTest {

    private static final long T = 1_000_000L;
    private static final ArenaStand.Befehl.Art LISTE = ArenaStand.Befehl.Art.LISTE;
    private static final ArenaStand.Befehl.Art CLAN = ArenaStand.Befehl.Art.CLAN;

    private final List<ArenaStand.Befehl> befehle = new ArrayList<>();
    private final List<SchichtSteuerung.Notiz> notizen = new ArrayList<>();
    private boolean kombi;
    private final SchichtSteuerung st = new SchichtSteuerung(new SchichtSteuerung.Tastatur() {
        @Override
        public String zeichen(int vk) {
            if (vk >= 'A' && vk <= 'Z') return String.valueOf((char) Character.toLowerCase(vk));
            if (vk >= '0' && vk <= '9' || vk == ' ') return String.valueOf((char) vk);
            return "";
        }

        @Override
        public boolean kombi() {
            return kombi;
        }
    }, befehle::add, notizen::add);

    @AfterEach
    void schlangeLeeren() {
        ArenaStand.befehleNehmen();
        ArenaStand.stageFertig(false);
        ArenaStand.setzen(null);   // kein Stand und kein Fertig-Merker fuer andere Tests
    }

    private static ArenaStand.Gegner gegner(int platz, int x, String name, String stufe, boolean clanListe, boolean ziel) {
        return new ArenaStand.Gegner(x, 400, platz, name, "Clan" + platz, false, stufe, clanListe, false, 150, 60,
                "", "", ziel);
    }

    /** Pyramide (absichtlich ungeordnet): Platz 1 oben, 2 und 3 darunter, 4 links unten. Der Bot zielt auf Platz 3. */
    private static ArenaStand.Stand botSpielt(int runde, long um) {
        return new ArenaStand.Stand(um, runde, 1159, 770,
                List.of(gegner(3, 700, "drei", "", false, true), gegner(2, 460, "zwei", "machbar", true, false),
                        gegner(4, 345, "vier", "niemals", false, false)),
                List.of(new ArenaStand.Platz(4, 345, 492, "niemals"), new ArenaStand.Platz(2, 460, 360, "machbar"),
                        new ArenaStand.Platz(1, 580, 220, ""), new ArenaStand.Platz(3, 700, 360, "")));
    }

    /** Autor spielt selbst: nur Plaetze, keine Rechnung. */
    private static ArenaStand.Stand autorSpielt(long um) {
        return new ArenaStand.Stand(um, 1, 1159, 770, List.of(),
                List.of(new ArenaStand.Platz(4, 345, 492, ""), new ArenaStand.Platz(2, 460, 360, "sicher")));
    }

    private boolean taste(int vk, ArenaStand.Stand s) {
        return st.taste(vk, s, T);
    }

    private SchichtSteuerung.Anzeige anzeige(ArenaStand.Stand s) {
        return st.anzeige(s, T);
    }

    // ---- Auswahl (Autor 29.10. abends: ↑/↓ in der Liste des Seitenfensters) ------------------------------------

    @Test
    void pfeileWaehlenInListenReihenfolgeAbDemBotZiel() {
        var s = botSpielt(1, T);
        assertEquals(List.of(1, 2, 3, 4), SchichtSteuerung.reihe(s));
        assertTrue(taste(VK_DOWN, s));
        assertEquals(3, anzeige(s).auswahl(), "erster Druck: beim Bot-Ziel anfangen");
        taste(VK_DOWN, s);
        assertEquals(4, anzeige(s).auswahl());
        taste(VK_DOWN, s);
        assertEquals(1, anzeige(s).auswahl(), "am Ende wieder oben");
        taste(VK_UP, s);
        assertEquals(4, anzeige(s).auswahl());
        assertTrue(befehle.isEmpty(), "Waehlen allein schickt nichts");
    }

    @Test
    void altePyramidenTastenTunNichtsMehr() {
        var s = botSpielt(1, T);
        for (int vk : new int[]{VK_C, VK_L, VK_RETURN}) {
            assertFalse(taste(vk, s), "Taste " + vk + " geht ans Spiel");
        }
        assertEquals(0, anzeige(s).auswahl());
        assertTrue(befehle.isEmpty());
    }

    /** Autor 30.10.: "Ich kann immer noch keinen Gegner auswaehlen mit WASD und den anderen Tasten." */
    @Test
    void wasdUndAllePfeileWaehlen() {
        var s = botSpielt(1, T);
        assertTrue(taste('S', s));
        assertEquals(3, anzeige(s).auswahl(), "erster Druck: beim Bot-Ziel anfangen");
        assertTrue(taste('D', s));
        assertEquals(4, anzeige(s).auswahl());
        assertTrue(taste(VK_RIGHT, s));
        assertEquals(1, anzeige(s).auswahl());
        assertTrue(taste('W', s));
        assertEquals(4, anzeige(s).auswahl());
        assertTrue(taste('A', s));
        assertEquals(3, anzeige(s).auswahl());
        assertTrue(taste(VK_LEFT, s));
        assertEquals(2, anzeige(s).auswahl());
        assertTrue(befehle.isEmpty(), "Waehlen allein schickt nichts");
    }

    /** Autor 30.10.: der Bot spielt: alles nur mit der Tastatur, die Maus bleibt liegen. */
    @Test
    void kommentarNurMitDerTastaturMitArtPerTab() {
        var s = botSpielt(1, T);
        taste('S', s);
        assertTrue(taste(VK_K, s));
        assertEquals("", anzeige(s).kommentarArt());
        assertTrue(taste(SchichtSteuerung.VK_TAB, s), "Tab geht nicht ans Spiel");
        assertEquals("warum_angegriffen", anzeige(s).kommentarArt());
        taste(SchichtSteuerung.VK_TAB, s);
        assertEquals("chance_zu_hoch", anzeige(s).kommentarArt());
        taste('W', s);   // beim Tippen ist W ein Buchstabe, kein Weiterwaehlen
        assertEquals("w", anzeige(s).eingabe());
        assertEquals(3, anzeige(s).auswahl());
        assertTrue(taste(VK_RETURN, s));
        assertFalse(st.modal());
        assertEquals("chance_zu_hoch", notizen.get(0).wert());
        assertEquals("w", notizen.get(0).text());
    }

    @Test
    void tabGehtNachDerLetztenArtWiederAufKeine() {
        assertEquals("warum_angegriffen", SchichtSteuerung.naechsteArt(""));
        assertEquals("", SchichtSteuerung.naechsteArt("chance_zu_niedrig"));
    }

    /** Autor 30.10.: er spielt selbst: im Seitenfenster einen Spieler anklicken, Knopf waehlen, tippen, abschicken. */
    @Test
    void klickImSeitenfensterStartetDenKommentarUndKnoepfeWirken() {
        var s = botSpielt(1, T);
        st.klick(4, s, T);
        assertTrue(st.modal());
        assertEquals(4, anzeige(s).bezugPlatz());
        st.klick(SchichtSteuerung.KLICK_ART - 2, s, T);
        assertEquals("chance_zu_niedrig", anzeige(s).kommentarArt());
        st.klick(SchichtSteuerung.KLICK_ART - 2, s, T);
        assertEquals("", anzeige(s).kommentarArt(), "derselbe Knopf nochmal: wieder aus");
        st.klick(SchichtSteuerung.KLICK_ART, s, T);
        taste('X', s);
        st.klick(2, s, T);
        assertEquals(4, anzeige(s).bezugPlatz(), "ein angefangener Kommentar geht durch einen Klick nicht verloren");
        st.klick(SchichtSteuerung.KLICK_SENDEN, s, T);
        assertFalse(st.modal());
        assertEquals("vier", notizen.get(0).name());
        assertEquals("warum_angegriffen", notizen.get(0).wert());
        assertEquals("x", notizen.get(0).text());
    }

    /** Autor 30.10.: "einen Knopf, dass ich dem Bot zustimmen kann." Das Ziel in botSpielt ist Platz 3. */
    @Test
    void zustimmenPerTasteUndKnopfKommtInDieNotiz() {
        var s = botSpielt(1, T);
        assertTrue(taste(SchichtSteuerung.VK_J, s), "J geht nicht ans Spiel");
        st.klick(SchichtSteuerung.KLICK_ZUSTIMMEN, s, T);
        assertEquals(2, notizen.size());
        assertEquals(ArenaStand.Befehl.Art.ZUSTIMMEN, notizen.get(0).art());
        assertEquals(3, notizen.get(0).platz());
        assertFalse(st.modal());
    }

    @Test
    void ablehnenFragtNachDemGrundPerTasteUndPerKnopf() {
        var s = botSpielt(1, T);
        assertTrue(taste(SchichtSteuerung.VK_N, s));
        assertTrue(st.modal());
        assertTrue(taste('2', s), "Grund per Taste");
        assertEquals("starker_clan", notizen.get(0).wert());
        assertFalse(st.modal());

        st.klick(SchichtSteuerung.KLICK_ABLEHNEN, s, T);
        assertTrue(st.modal());
        st.klick(SchichtSteuerung.KLICK_GRUND - 3, s, T);
        assertEquals("sonstiges", notizen.get(1).wert());
        assertFalse(st.modal());

        st.klick(SchichtSteuerung.KLICK_ABLEHNEN, s, T);
        st.klick(SchichtSteuerung.KLICK_ABBRECHEN, s, T);
        assertFalse(st.modal(), "ohne Grund geschlossen");
        assertEquals(3, notizen.size(), "die Ablehnung zaehlt trotzdem");
        assertEquals("", notizen.get(2).wert());
    }

    // ---- J/N/G ohne Ziel im Stand (Autor 30.10.: "steht ganz oft oben No Bot Target Now") ----------------------
    // ArenaStand ist statisch: jeder Test hat seine eigene Stage-Nummer (101, 102, ...), die sonst nirgends vorkommt.

    /** Eine Stage nur mit Gegnern, ohne Plaetze. */
    private static ArenaStand.Stand stage(int runde, ArenaStand.Gegner... g) {
        return new ArenaStand.Stand(T, runde, 1159, 770, List.of(g), List.of());
    }

    @Test
    void zustimmenOhneZielImStandGiltDemFruehherenVorschlagDerStage() {
        var mit = stage(101, gegner(3, 700, "drei", "", false, true), gegner(2, 460, "zwei", "machbar", true, false));
        ArenaStand.setzen(mit);   // der Bot hat Platz 3 vorgeschlagen ...
        ArenaStand.setzen(null);  // ... dann kam der Angriff, die Pyramide war kurz weg
        var danach = mit.ohneZiel();
        assertNull(SchichtSteuerung.ziel(danach), "im Stand selbst ist kein Ziel mehr");

        assertTrue(taste(SchichtSteuerung.VK_J, danach));
        assertEquals(1, befehle.size());
        assertEquals(ArenaStand.Befehl.Art.ZUSTIMMEN, befehle.get(0).art());
        assertEquals(3, befehle.get(0).platz());
        assertEquals(1, notizen.size());
        assertEquals(ArenaStand.Befehl.Art.ZUSTIMMEN, notizen.get(0).art());
        assertEquals(3, notizen.get(0).platz());
        assertEquals("drei", notizen.get(0).name());
        assertTrue(anzeige(danach).meldungGut());
    }

    @Test
    void ablehnenOhneZielImStandGiltDemFruehherenVorschlagUndFragtNachDemGrund() {
        var mit = stage(102, gegner(3, 700, "drei", "", false, true), gegner(2, 460, "zwei", "machbar", true, false));
        ArenaStand.setzen(mit);
        ArenaStand.setzen(null);
        var danach = mit.ohneZiel();

        assertTrue(taste(SchichtSteuerung.VK_N, danach));
        assertEquals(1, befehle.size());
        assertEquals(ArenaStand.Befehl.Art.ABLEHNEN, befehle.get(0).art());
        assertEquals(3, befehle.get(0).platz());
        assertEquals("", befehle.get(0).wert(), "sofort ohne Grund: dieses Ziel nicht");
        assertEquals(SchichtSteuerung.Modus.GRUND, anzeige(danach).modus());
        assertEquals(3, anzeige(danach).bezugPlatz());
        assertTrue(st.modal());
        assertTrue(notizen.isEmpty(), "die Notiz kommt erst mit dem Grund");

        assertTrue(taste('1', danach));
        assertFalse(st.modal());
        assertEquals(2, befehle.size());
        assertEquals("zu_stark", befehle.get(1).wert());
        assertEquals(1, notizen.size());
        assertEquals(ArenaStand.Befehl.Art.ABLEHNEN, notizen.get(0).art());
        assertEquals(3, notizen.get(0).platz());
        assertEquals("drei", notizen.get(0).name());
    }

    @Test
    void zustimmenOhneVorschlagUndOhneGeleseneGegnerSagtDassDerBotNochLiest() {
        var s = stage(103);   // Pyramide da, aber noch kein Gegner gelesen
        assertNull(ArenaStand.letztesZiel(103));
        assertTrue(taste(SchichtSteuerung.VK_J, s), "J gehoert der Schicht, nicht dem Spiel");
        assertTrue(befehle.isEmpty());
        assertTrue(notizen.isEmpty());
        String m = anzeige(s).meldung();
        assertNotNull(m);
        assertTrue(m.contains("still reading"), m);
        assertFalse(m.contains("No bot target"), m);
        assertFalse(anzeige(s).meldungGut());

        assertTrue(taste(SchichtSteuerung.VK_N, s));
        assertTrue(anzeige(s).meldung().contains("still reading"), "N genauso");
        assertFalse(st.modal(), "ohne Ziel keine Grund-Frage");
        assertTrue(befehle.isEmpty());
    }

    @Test
    void zustimmenMitGelesenenGegnernAberOhneVorschlagSagtKeinVorschlagInDieserStage() {
        var s = stage(104, gegner(2, 460, "zwei", "machbar", true, false));
        assertTrue(taste(SchichtSteuerung.VK_J, s));
        String m = anzeige(s).meldung();
        assertNotNull(m);
        assertTrue(m.contains("no pick in this stage"), m);
        assertFalse(m.contains("still reading"), m);
        assertTrue(befehle.isEmpty());
        assertTrue(notizen.isEmpty());
    }

    /** Autor 30.10.: "zu den einzelnen Spielern einen Kommentar schreiben koennen, aber auch zu der Stage." */
    @Test
    void gStartetEinenStageKommentarOhnePlatzUndTabAendertDieArtNicht() {
        var s = stage(105, gegner(3, 700, "drei", "", false, true), gegner(2, 460, "zwei", "machbar", true, false));
        assertTrue(taste(SchichtSteuerung.VK_G, s), "G gehoert der Schicht");
        var a = anzeige(s);
        assertEquals(SchichtSteuerung.Modus.KOMMENTAR, a.modus());
        assertEquals(0, a.bezugPlatz(), "kein Spieler: die ganze Stage");
        assertEquals(SchichtSteuerung.STAGE_ART, a.kommentarArt());
        assertTrue(st.modal());

        assertTrue(taste(SchichtSteuerung.VK_TAB, s), "Tab geht nicht ans Spiel");
        assertEquals(SchichtSteuerung.STAGE_ART, anzeige(s).kommentarArt(), "Tab aendert die Art der Stage nicht");
        taste('H', s);
        taste('I', s);
        assertEquals("hi", anzeige(s).eingabe());
        assertTrue(taste(VK_RETURN, s));
        assertFalse(st.modal());

        assertEquals(1, notizen.size());
        var n = notizen.get(0);
        assertEquals(ArenaStand.Befehl.Art.KOMMENTAR, n.art());
        assertEquals(0, n.platz());
        assertEquals(SchichtSteuerung.STAGE_ART, n.wert());
        assertEquals("hi", n.text());
        assertEquals("drei", n.botZiel(), "das Bot-Ziel steht dabei");
        assertEquals(1, befehle.size());
        assertEquals(ArenaStand.Befehl.Art.KOMMENTAR, befehle.get(0).art());
        assertEquals(0, befehle.get(0).platz());
        assertEquals("hi", befehle.get(0).text());
    }

    @Test
    void letztesZielGiltNurFuerDieselbeStageUndNachStageFertigUeberschreibtEinNeuesDenAltenNicht() {
        assertNull(ArenaStand.letztesZiel(106));
        ArenaStand.setzen(stage(106, gegner(3, 700, "drei", "", false, true)));
        assertEquals("drei", ArenaStand.letztesZiel(106).name());
        assertNull(ArenaStand.letztesZiel(105), "andere Stage");
        assertNull(ArenaStand.letztesZiel(107), "andere Stage");

        // Solange Autor nicht angegriffen hat, ersetzt ein neuer Vorschlag den alten ...
        ArenaStand.setzen(stage(106, gegner(2, 460, "zwei", "", false, true)));
        assertEquals("zwei", ArenaStand.letztesZiel(106).name());
        // ... ein Stand ohne Ziel aendert nichts.
        ArenaStand.setzen(stage(106, gegner(2, 460, "zwei", "", false, false)));
        assertEquals("zwei", ArenaStand.letztesZiel(106).name());

        // Nach dem Angriff ("Next stage in"): der alte Vorschlag bleibt, ein neuer ueberschreibt ihn nicht.
        ArenaStand.stageFertig(true);
        ArenaStand.setzen(stage(106, gegner(4, 345, "vier", "", false, true)));
        assertEquals("zwei", ArenaStand.letztesZiel(106).name());

        // Die naechste Stage faengt neu an.
        ArenaStand.setzen(stage(107, gegner(4, 345, "vier", "", false, true)));
        assertEquals("vier", ArenaStand.letztesZiel(107).name());
        assertNull(ArenaStand.letztesZiel(106));
    }

    @Test
    void klickAbbrechenSchicktNichts() {
        var s = botSpielt(1, T);
        st.klick(4, s, T);
        taste('X', s);
        st.klick(SchichtSteuerung.KLICK_ABBRECHEN, s, T);
        assertFalse(st.modal());
        assertTrue(notizen.isEmpty());
        st.klick(-1, s, T);   // daneben geklickt
        assertFalse(st.modal());
        st.klick(3, null, T);   // nichts zu sehen: kein Kommentar ins Leere
        assertFalse(st.modal());
    }

    @Test
    void ohneBotZielBeginntObenOderUnten() {
        var s = autorSpielt(T);
        taste(VK_UP, s);
        assertEquals(4, anzeige(s).auswahl());
        taste(VK_ESCAPE, s);
        taste(VK_DOWN, s);
        assertEquals(2, anzeige(s).auswahl());
    }

    // ---- Ziffern speichern sofort -----------------------------------------------------------------------------

    @Test
    void zifferSpeichertSofort() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);                        // Platz 3, keine Liste
        assertTrue(taste('2', s));
        assertEquals(1, befehle.size());
        var b = befehle.get(0);
        assertEquals(LISTE, b.art());
        assertEquals(3, b.platz());
        assertEquals("machbar", b.wert());
        assertEquals("drei", b.text());
        var a = anzeige(s);
        assertEquals("machbar", a.stufen().get(3), "sofort angezeigt, bevor der Bot nachzieht");
        assertTrue(a.meldungGut());

        assertTrue(taste(0x64, s));               // Ziffernblock 4 = niemals
        assertEquals("niemals", befehle.get(1).wert());
        assertTrue(taste('1', s));
        assertEquals("sicher", befehle.get(2).wert());
        assertTrue(taste('3', s));
        assertEquals("meiden", befehle.get(3).wert());
        assertTrue(taste('0', s));
        assertEquals("neutral", befehle.get(4).wert(), "0 = aus der Liste nehmen");
        assertFalse(taste('9', s), "andere Ziffern gehen ans Spiel");
    }

    @Test
    void zifferOhneAuswahlUndGleicheStufeSpeichernNichts() {
        var s = botSpielt(1, T);
        assertTrue(taste('1', s), "geschluckt, aber nur ein Hinweis");
        assertTrue(befehle.isEmpty());
        assertFalse(anzeige(s).meldungGut());
        taste(VK_DOWN, s);
        taste(VK_DOWN, s);                        // Platz 4 steht schon auf niemals
        taste('4', s);
        assertTrue(befehle.isEmpty(), "unveraendert");
    }

    @Test
    void stufeAusZiffer() {
        assertEquals("sicher", SchichtSteuerung.stufeAusZiffer('1'));
        assertEquals("niemals", SchichtSteuerung.stufeAusZiffer(0x64));
        assertEquals("neutral", SchichtSteuerung.stufeAusZiffer(0x60));
        assertEquals("fastsicher", SchichtSteuerung.stufeAusZiffer('5'));
        assertEquals("pause", SchichtSteuerung.stufeAusZiffer('6'));
        assertNull(SchichtSteuerung.stufeAusZiffer('7'));
        assertNull(SchichtSteuerung.stufeAusZiffer('K'));
    }

    // ---- Zurueck ----------------------------------------------------------------------------------------------

    @Test
    void ruecktasteNimmtDieLetzteAenderungZurueck() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);
        taste(VK_UP, s);                          // Platz 2: machbar
        taste('1', s);
        befehle.clear();

        assertTrue(taste(VK_BACK, s));
        assertEquals(1, befehle.size());
        var b = befehle.get(0);
        assertEquals(ArenaStand.Befehl.Art.ZURUECK, b.art());
        assertEquals(2, b.platz());
        assertEquals("zwei", b.text());
        assertEquals("machbar", b.wert());
        assertEquals("machbar", anzeige(s).stufen().get(2));

        befehle.clear();
        taste(VK_BACK, s);
        assertTrue(befehle.isEmpty(), "nichts mehr zum Zuruecknehmen");
    }

    @Test
    void zurueckNichtUeberEineNeueRundeHinweg() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);
        taste('2', s);
        befehle.clear();
        var neu = botSpielt(2, T + 1000);         // nach dem Kampf: andere Gegner auf den Plaetzen
        assertTrue(st.taste(VK_BACK, neu, T + 1000));
        assertTrue(befehle.isEmpty());
        var a = st.anzeige(neu, T + 1000);
        assertEquals(0, a.auswahl(), "neue Runde: Auswahl weg");
        assertTrue(a.stufen().isEmpty());
    }

    @Test
    void langeLueckeGiltAuchAlsNeueRunde() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);
        assertEquals(3, anzeige(s).auswahl());
        var spaeter = botSpielt(1, T + SchichtSteuerung.LUECKE_MS + 1);
        assertEquals(0, st.anzeige(spaeter, T + SchichtSteuerung.LUECKE_MS + 1).auswahl());
    }

    // ---- Kommentar --------------------------------------------------------------------------------------------

    @Test
    void kommentarzeileTippenLoeschenAbschicken() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);
        taste(VK_DOWN, s);                        // Platz 4
        assertTrue(taste(VK_K, s));
        assertTrue(st.modal());
        for (char c : "HALLOX".toCharArray()) assertTrue(taste(c, s), "Spiel bekommt die Buchstaben nicht");
        assertTrue(taste(VK_BACK, s));
        assertTrue(taste(VK_SPACE, s));
        assertTrue(taste('1', s));
        assertEquals("hallo 1", anzeige(s).eingabe());
        assertFalse(taste(0x78, s), "F9 (Not-Aus) geht immer durch");
        assertFalse(taste(0x10, s), "Umschalt geht durch -- sonst kennt Windows kein Grossschreiben");
        kombi = true;
        assertFalse(taste('T', s), "Alt+Tab & Co. gehoeren Windows");
        kombi = false;
        // Die Pyramide verschwindet (Bot greift an) -- der Kommentar laeuft weiter.
        assertTrue(st.taste('Y', null, T));
        assertEquals("hallo 1y", st.anzeige(null, T).eingabe());
        assertTrue(st.taste(VK_BACK, null, T));
        assertTrue(st.taste(VK_RETURN, null, T));
        assertFalse(st.modal());

        assertEquals(1, befehle.size());
        var b = befehle.get(0);
        assertEquals(ArenaStand.Befehl.Art.KOMMENTAR, b.art());
        assertEquals(4, b.platz());
        assertEquals("vier", b.wert());
        assertEquals("hallo 1", b.text());
        assertEquals(1, notizen.size());
        var n = notizen.get(0);
        assertEquals("vier", n.name());
        assertEquals("Clan4", n.clan());
        assertEquals("drei", n.botZiel());
        assertEquals("hallo 1", n.text());
    }

    @Test
    void kommentarMitEscAbbrechenUndLeerSchicktNichts() {
        var s = botSpielt(1, T);
        taste(VK_K, s);
        assertEquals(3, anzeige(s).bezugPlatz(), "ohne Auswahl: das Bot-Ziel");
        taste('X', s);
        assertTrue(taste(VK_ESCAPE, s));
        assertFalse(st.modal());
        taste(VK_K, s);
        taste(VK_RETURN, s);
        assertTrue(befehle.isEmpty());
        assertTrue(notizen.isEmpty());
    }

    @Test
    void leertasteZeigtDenZettelDerAuswahl() {
        var s = botSpielt(1, T);
        assertTrue(taste(VK_SPACE, s));
        assertTrue(anzeige(s).zettel());
        assertEquals(3, anzeige(s).auswahl());
        taste(VK_SPACE, s);
        assertFalse(anzeige(s).zettel());
    }

    @Test
    void escGehtAnsSpielWennNichtsOffenIst() {
        var s = botSpielt(1, T);
        assertFalse(taste(VK_ESCAPE, s));
        taste(VK_DOWN, s);
        assertTrue(taste(VK_ESCAPE, s), "erst die Auswahl schliessen");
        assertEquals(0, anzeige(s).auswahl());
        assertFalse(taste(VK_ESCAPE, s));
    }

    @Test
    void ohnePyramideWirdNichtsGeschluckt() {
        assertFalse(taste(VK_DOWN, null));
        assertFalse(taste(VK_K, null));
        assertFalse(taste('1', null));
        assertFalse(taste('X', botSpielt(1, T)));
    }

    @Test
    void funktionsTastenNieSchlucken() {
        for (int vk : new int[]{0x78, 0x79, 0x7A}) assertTrue(SchichtSteuerung.nieSchlucken(vk));
        for (int vk : new int[]{VK_DOWN, '1', VK_SPACE, 'K', VK_BACK}) assertFalse(SchichtSteuerung.nieSchlucken(vk));
    }

    // ---- Keine Maus mehr (Autor 29.10. abends) ----------------------------------------------------------------

    @Test
    void mausTastenTunNichts() {
        assertFalse(EingabeHaken.MAUS);
        var s = botSpielt(1, T);
        assertFalse(st.maus(EingabeHaken.Art.SEITE, true, 0, s, T));
        assertFalse(st.maus(EingabeHaken.Art.SEITE, false, 0, s, T));
        assertFalse(st.maus(EingabeHaken.Art.RECHTS, false, 4, autorSpielt(T), T));
        assertFalse(st.maus(EingabeHaken.Art.MITTE, false, 4, autorSpielt(T), T));
        assertTrue(befehle.isEmpty());
        assertFalse(st.modal());
    }

    @Test
    void platzAusSchirmpunkt() {
        var s = autorSpielt(T);
        Rectangle innen = new Rectangle(700, 50, 1159, 770);   // Spiel rechts angedockt
        // Platz 4: Punktezeile bei 345,492 -> Schild etwa x 240-452, y 437-563 im Spielbild.
        assertEquals(4, ArenaSchicht.platzBei(s, innen, new Point(700 + 345, 50 + 470)));
        assertEquals(2, ArenaSchicht.platzBei(s, innen, new Point(700 + 460, 50 + 350)));
        assertEquals(0, ArenaSchicht.platzBei(s, innen, new Point(700 + 1100, 50 + 700)), "daneben");
        assertEquals(0, ArenaSchicht.platzBei(s, null, new Point(1, 1)));
        // Doppelt so grosses Fenster: dieselbe Stelle im Bild.
        assertEquals(4, ArenaSchicht.platzBei(s, new Rectangle(0, 0, 2318, 1540), new Point(690, 940)));
    }

    // ---- Rahmen, Datei, Discord, Schlange ---------------------------------------------------------------------

    @Test
    void rahmenZeigenAuswahlUndGeradeGespeichertes() {
        var s = botSpielt(1, T);
        taste(VK_DOWN, s);                        // Platz 3, neutral
        var r = ArenaSchicht.rahmen(s, 1159, 770, anzeige(s));
        var drei = r.get(3);                      // Reihenfolge wie s.plaetze()
        assertTrue(drei.auswahl());
        assertEquals(SchichtFenster.AUSWAHL, drei.farbe(), "neutral gewaehlt: hell statt blassgrau");
        assertNull(drei.text());
        assertFalse(r.get(0).auswahl());
        taste('2', s);
        taste(VK_ESCAPE, s);
        r = ArenaSchicht.rahmen(s, 1159, 770, anzeige(s));
        assertEquals(SchichtFenster.MACHBAR, r.get(3).farbe(), "gespeichert gilt sofort");
        assertFalse(r.get(3).auswahl());
    }

    @Test
    void kommentarZeileBleibtEineZeile() {
        var n = new SchichtSteuerung.Notiz(T, ArenaStand.Befehl.Art.KOMMENTAR, 4, "li|no", "Clan", "Torres", "",
                "zu stark\r\nnaechstes Mal | nicht\tangreifen");
        String z = SchichtSteuerung.zeile(n);
        assertFalse(z.contains("\n") || z.contains("\r") || z.contains("\t"));
        String[] teile = z.split(" \\| ", -1);
        assertEquals(8, teile.length, z);
        assertEquals("KOMMENTAR", teile[1]);
        assertEquals("4", teile[2]);
        assertEquals("li¦no", teile[3]);
        assertEquals("Torres", teile[5]);
        assertEquals("zu stark / naechstes Mal ¦ nicht angreifen", teile[7]);
    }

    @Test
    void discordPingtNiemanden() {
        var n = new SchichtSteuerung.Notiz(T, ArenaStand.Befehl.Art.KOMMENTAR, 4, "lino", "", "", "", "@everyone schau");
        String d = SchichtSteuerung.discordText(n);
        assertFalse(d.contains("@everyone"));
        assertTrue(d.contains("lino"));
    }

    @Test
    void befehlsSchlangeReihenfolgeUndObergrenze() {
        ArenaStand.befehleNehmen();
        ArenaStand.befehl(new ArenaStand.Befehl(LISTE, 3, "sicher", "a", 1));
        ArenaStand.befehl(new ArenaStand.Befehl(CLAN, 3, "stark", "a", 2));
        ArenaStand.befehl(null);
        var l = ArenaStand.befehleNehmen();
        assertEquals(List.of(LISTE, CLAN), l.stream().map(ArenaStand.Befehl::art).toList());
        assertTrue(ArenaStand.befehleNehmen().isEmpty(), "einmal abgeholt ist weg");
        for (int i = 0; i < ArenaStand.BEFEHLE_HOECHSTENS + 5; i++) {
            ArenaStand.befehl(new ArenaStand.Befehl(ArenaStand.Befehl.Art.KOMMENTAR, 0, "", "x" + i, i));
        }
        l = ArenaStand.befehleNehmen();
        assertEquals(ArenaStand.BEFEHLE_HOECHSTENS, l.size());
        assertEquals("x5", l.get(0).text(), "die aeltesten fallen weg");
        assertEquals("", new ArenaStand.Befehl(ArenaStand.Befehl.Art.ZUSTIMMEN, 1, null, null, 0).wert());
    }
}
