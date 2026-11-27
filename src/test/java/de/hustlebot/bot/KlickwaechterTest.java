package de.hustlebot.bot;

import de.hustlebot.bot.Klickwaechter.Folge;
import de.hustlebot.bot.Klickwaechter.Schritt;
import de.hustlebot.core.Log;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selbsthilfe gegen wirkungslose gleiche Klicks (Bericht UK3S, Ben 19.11.). */
class KlickwaechterTest {

    private static final String HOME = "Confirm battle result  [home_button at 580,710, match 0.999]";
    private static final long STUNDE = 3_600_000L;

    private static Log.Entry klick(String zeit, String text) {
        return new Log.Entry(zeit, Log.Level.ACTION, "Klick", text);
    }

    private static Log.Entry notiz(String zeit, String text) {
        return new Log.Entry(zeit, Log.Level.DEBUG, "Fortschritt", text);
    }

    /** Die echten Klickzeiten aus UK3S (19:18:05 bis 19:19:15, alle auf "Home" bei 580,710). */
    private static final String[] UK3S = {
            "19:18:05.634", "19:18:07.705", "19:18:09.915", "19:18:12.195", "19:18:14.438", "19:18:16.568",
            "19:18:18.806", "19:18:20.972", "19:18:23.193", "19:18:25.519", "19:18:27.759", "19:18:30.113",
            "19:18:32.235", "19:18:34.594", "19:18:36.803", "19:18:39.067", "19:18:41.252", "19:18:43.384",
            "19:18:45.952", "19:18:48.137", "19:18:50.227", "19:18:52.357", "19:18:54.518", "19:18:56.858",
            "19:18:59.039", "19:19:01.230", "19:19:03.364", "19:19:06.282", "19:19:08.629", "19:19:10.867",
            "19:19:12.980", "19:19:15.153"};

    /** Das UK3S-Protokoll bis einschliesslich zur Uhrzeit {@code bis}. */
    private static List<Log.Entry> uk3sBis(String bis) {
        List<Log.Entry> e = new ArrayList<>();
        e.add(klick("19:17:56.320", "Start battle (dc_quick_attack, box look)"));
        e.add(notiz("19:17:56.397", "Battle started"));
        for (String z : UK3S) {
            if (z.compareTo(bis) > 0) break;
            e.add(klick(z, HOME));
            e.add(notiz(z, "Battle finished"));
        }
        return e;
    }

    private static Folge folge(List<Log.Entry> e, String jetzt) {
        return Klickwaechter.folge(e, LocalTime.parse(jetzt), STUNDE);
    }

    @Test
    void uk3sFuenfterKlickHoltDasFensterNachVorn() {
        Folge f = folge(uk3sBis("19:18:14.438"), "19:18:14.900");
        assertEquals(5, f.anzahl());
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(f, false, false, true, false, false, 0));
        // vier Klicks sind noch nichts
        Folge vier = folge(uk3sBis("19:18:12.195"), "19:18:12.500");
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(vier, false, false, true, false, false, 0));
    }

    @Test
    void uk3sNeustartNachEinerMinuteVorDemHaengerBericht() {
        // 15 Klicks sind um 19:18:36 erreicht, die Minute erst mit dem Klick um 19:19:06 -- vorher kein Neustart.
        Folge f15 = folge(uk3sBis("19:18:36.803"), "19:18:37.000");
        assertEquals(15, f15.anzahl());
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(f15, false, true, true, false, false, 0));

        Folge f = folge(uk3sBis("19:19:06.282"), "19:19:06.500");
        assertEquals(28, f.anzahl());
        assertTrue(f.dauerMs() >= 60_000, "Dauer " + f.dauerMs());
        assertEquals(Schritt.NEUSTART, Klickwaechter.entscheiden(f, false, true, true, false, false, 0));
        // der Haenger-Bericht kam erst um 19:19:17
        assertTrue("19:19:06.500".compareTo("19:19:17.183") < 0);
    }

    @Test
    void spielVorne_stufeEinsNurBeiStillemBild() {
        // Arena-Durchschauen: 5x "Next opponent" ueber 10 s, aber jedes Mal ein anderer Gegner -- Arbeit, kein Haenger.
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 6; i++) e.add(klick(String.format("10:00:%02d.000", i * 2), "Next opponent (scouting)  [ar_next_arrow at 713,697, match 0.998]"));
        Folge f = folge(e, "10:00:10.500");
        assertEquals(6, f.anzahl());
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(f, true, false, false, false, false, 0), "Bild bewegt sich");
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(f, true, false, true, false, false, 0), "Bild steht 10 s still");
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(f, true, false, false, true, false, 0), "Spiel antwortet nicht");
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(f, false, false, false, false, false, 0), "Spiel liegt nicht vorne");
        // stilles Bild, aber erst 6 s lang: noch warten
        Folge kurz = folge(e.subList(0, 4), "10:00:06.500");
        Folge f5 = folge(e.subList(0, 5), "10:00:08.500");
        assertEquals(5, f5.anzahl());
        assertEquals(8_000, f5.dauerMs());
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(f5, true, false, true, false, false, 0));
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(kurz, true, false, true, false, false, 0), "nur 4 Klicks");
    }

    @Test
    void ohneStufeEinsKeinNeustart() {
        Folge f = folge(uk3sBis("19:19:15.153"), "19:19:15.500");
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(f, false, false, true, false, false, 0));
    }

    @Test
    void zeitangabenUndTrefferguete_zaehlenNicht_dieStelleSchon() {
        Log.Entry a = klick("10:00:00.000", "Confirm battle result  [home_button at 580,710, match 0.999, box look after 1574 ms]");
        Log.Entry b = klick("10:00:02.000", "Confirm battle result  [home_button at 580,710, match 0.981, box look after 12 ms]");
        Log.Entry c = klick("10:00:04.000", "Confirm battle result  [home_button at 581,710, match 0.999, box look after 12 ms]");
        assertEquals(Klickwaechter.schluessel(a), Klickwaechter.schluessel(b));
        assertNotEquals(Klickwaechter.schluessel(a), Klickwaechter.schluessel(c), "andere Stelle = anderer Klick");
        // deutsch: "Uebereinstimmung 0.999" und "1.000" sind derselbe Klick
        assertEquals(Klickwaechter.schluessel(klick("1", "Leuchtturm: Quick Attack  [lh_quick_attack bei 666,604, Uebereinstimmung 0.999]")),
                Klickwaechter.schluessel(klick("2", "Leuchtturm: Quick Attack  [lh_quick_attack bei 666,604, Uebereinstimmung 1.000]")));
        // andere Quelle (Turbo statt Klick) = anderer Klick
        assertNotEquals(Klickwaechter.schluessel(a),
                Klickwaechter.schluessel(new Log.Entry("10:00:00.000", Log.Level.ACTION, "Turbo", a.message())));
    }

    @Test
    void andereAktionDazwischenBeendetDieFolge_meldungenNicht() {
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 6; i++) e.add(klick(String.format("10:00:%02d.000", i * 2), HOME));
        e.add(klick("10:00:13.000", "Pick the middle blessing at 579,338"));
        for (int i = 0; i < 3; i++) {
            e.add(new Log.Entry(String.format("10:00:%02d.500", 14 + i * 2), Log.Level.WARN, "Fenster", "verdeckt"));
            e.add(klick(String.format("10:00:%02d.000", 15 + i * 2), HOME));
        }
        Folge f = folge(e, "10:00:20.000");
        assertEquals(3, f.anzahl(), "nur die drei nach dem Segen; die Warnungen dazwischen stoeren nicht");
        assertEquals("Confirm battle result  [home_button at 580,710, match #]", f.text(), "fuers Protokoll: ohne Trefferguete");
    }

    @Test
    void alteKlicksZaehlenNicht_etwaVorDemLetztenNeustart() {
        List<Log.Entry> e = uk3sBis("19:19:15.153");
        Folge f = Klickwaechter.folge(e, LocalTime.parse("19:19:15.500"), 10_000);
        assertEquals(5, f.anzahl(), "nur die Klicks der letzten 10 s");
    }

    @Test
    void abgerisseneFolge_istVorbei() {
        // letzter gleicher Klick vor 25 s: der Modus wartet oder tut etwas anderes
        Folge f = folge(uk3sBis("19:19:15.153"), "19:19:40.200");
        assertTrue(f.letzterVorMs() > Klickwaechter.FOLGE_AKTUELL_MS);
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(f, false, true, true, false, false, 0));
    }

    @Test
    void ueberMitternacht() {
        List<Log.Entry> e = new ArrayList<>();
        e.add(klick("23:59:58.000", HOME));
        e.add(klick("00:00:01.000", HOME));
        Folge f = folge(e, "00:00:02.000");
        assertEquals(2, f.anzahl());
        assertEquals(3_000, f.dauerMs());
        assertEquals(1_000, f.letzterVorMs());
        assertEquals(Long.MAX_VALUE, Klickwaechter.alterMs("kaputt", LocalTime.NOON));
    }

    @Test
    void leeresProtokoll() {
        assertEquals(0, folge(List.of(), "12:00:00.000").anzahl());
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(folge(List.of(), "12:00:00.000"), false, false, true, false, false, 0));
    }

    @Test
    void neustartNurMitStillemBild_ohneKampf_undHoechstensZweimalJeStunde() {
        Folge reif = folge(uk3sBis("19:19:06.282"), "19:19:06.500");
        assertEquals(Schritt.NEUSTART, Klickwaechter.entscheiden(reif, false, true, true, false, false, 1));
        assertEquals(Schritt.BILD_BEWEGT_SICH, Klickwaechter.entscheiden(reif, false, true, false, false, false, 0));
        assertEquals(Schritt.KAMPF_ABWARTEN, Klickwaechter.entscheiden(reif, false, true, true, false, true, 0));
        assertEquals(Schritt.AUSGESCHOEPFT, Klickwaechter.entscheiden(reif, false, true, true, false, false, 2));
        assertEquals(Schritt.AUSGESCHOEPFT, Klickwaechter.entscheiden(reif, false, true, true, true, false, 2),
                "auch ein haengendes Spiel nicht oefter als zweimal je Stunde");
    }

    @Test
    void spielReagiertNicht_neustartAuchBeiKampfOderBewegtemBild() {
        Folge reif = folge(uk3sBis("19:19:06.282"), "19:19:06.500");
        assertEquals(Schritt.NEUSTART, Klickwaechter.entscheiden(reif, false, true, false, true, true, 0));
        // aber nie ohne Stufe 1 und nie vor der Minute
        assertEquals(Schritt.NACH_VORN, Klickwaechter.entscheiden(reif, false, false, false, true, true, 0));
        Folge frueh = folge(uk3sBis("19:18:36.803"), "19:18:37.000");
        assertEquals(Schritt.NICHTS, Klickwaechter.entscheiden(frueh, false, true, false, true, true, 0));
    }

    @Test
    void ergebnisfensterIstKeinLaufenderKampf() {
        assertFalse(Klickwaechter.kampfZuSehen(Set.of("home_button", "unit_dead_bar")::contains),
                "Sieg-Fenster ueber dem Schlachtfeld: Kampf vorbei");
        assertTrue(Klickwaechter.kampfZuSehen(Set.of("unit_dead_bar")::contains));
        assertTrue(Klickwaechter.kampfZuSehen(Set.of("pt_kampf_laeuft")::contains));
        assertTrue(Klickwaechter.kampfZuSehen(Set.of("gb_battle_begins")::contains), "Gem Bay: Kampfbeginn zaehlt als Kampf");
        assertFalse(Klickwaechter.kampfZuSehen(Set.<String>of()::contains));
    }

    @Test
    void bildProbe() {
        byte[] a = new byte[576], b = new byte[576];
        assertEquals(0.0, Klickwaechter.abweichung(a, b));
        for (int i = 0; i < b.length; i++) b[i] = (byte) 200;   // ganz anderes Bild (Fenster zu)
        assertEquals(200.0, Klickwaechter.abweichung(a, b));
        assertFalse(Klickwaechter.still(Klickwaechter.abweichung(a, b)));
        b = new byte[576];
        for (int i = 0; i < 40; i++) b[i] = (byte) 100;          // kleine Bewegung in einer Ecke
        assertTrue(Klickwaechter.still(Klickwaechter.abweichung(a, b)));
        assertEquals(Double.POSITIVE_INFINITY, Klickwaechter.abweichung(a, null));
        assertEquals(Double.POSITIVE_INFINITY, Klickwaechter.abweichung(a, new byte[10]));
        assertFalse(Klickwaechter.still(Double.POSITIVE_INFINITY));
    }
}
