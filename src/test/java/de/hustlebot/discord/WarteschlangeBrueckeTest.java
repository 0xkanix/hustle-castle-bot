package de.hustlebot.discord;

import de.hustlebot.bot.PortalMode;
import de.hustlebot.core.Bezahlung;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Die Warteschlange in der Bruecke (Autor 15.11., Fenster "Warteschlange"): "!queue start A | B | C" wird gelesen, ohne etwas zu
 * setzen; steht der Bot, ist die Liste die ganze Kette und Nummer 1 startet zuerst, die anderen folgen der Reihe nach; laeuft
 * er, kommen sie hinten an. Ein Portal-Plan reist im Eintrag mit und gilt erst, wenn der Eintrag startet.
 *
 * Den Motor gibt es im Test nicht (er braucht das Spielfenster) -- geprueft werden die Bausteine, die warteschlangeStarten,
 * naechstenStarten und zustandPruefen benutzen.
 */
class WarteschlangeBrueckeTest {

    @AfterEach
    void planLeeren() {
        PortalMode.planAusText("portal");
    }

    private static int zielGesetzt() throws Exception {
        Method m = PortalMode.class.getDeclaredMethod("zielGesetzt");
        m.setAccessible(true);
        return (int) m.invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static List<long[]> farmGesetzt() throws Exception {
        Method m = PortalMode.class.getDeclaredMethod("farmGesetzt");
        m.setAccessible(true);
        return (List<long[]>) m.invoke(null);
    }

    /** Wie eintragPruefen, nur ohne Bilder- und Lizenz-Pruefung (die braucht den laufenden Bot). */
    private static DiscordBridge.Auftrag auftrag(String text) {
        DiscordBridge.Eintragstext e = DiscordBridge.eintragLesen(text);
        assertNull(e.fehler(), text);
        return new DiscordBridge.Auftrag(e.modus(), e.runs(), e.weg(), e.freund().freund(), e.freund().warten(),
                true, e.plan(), e.bezahl(), e.kreislauf());
    }

    @Test
    void warteschlangeWirdAnDenStrichenGeteilt() {
        assertEquals(List.of("cathedral 50 c", "dungeon 10 bob", "portal bis 135 farm 80:2mio"),
                DiscordBridge.warteschlangeTeilen(" cathedral 50 c |dungeon 10 bob| | portal bis 135 farm 80:2mio "));
        assertEquals(List.of(), DiscordBridge.warteschlangeTeilen("  "));
    }

    @Test
    void portalPlanWirdAngenommenUndNichtSofortGesetzt() throws Exception {
        PortalMode.planAusText("portal");   // kein Plan
        DiscordBridge.Eintragstext e = DiscordBridge.eintragLesen("portal bis 135 farm 80:2mio,135:1,5mio");
        assertNull(e.fehler());
        assertEquals("portal", e.modus());
        assertEquals("bis 135 farm 80:2mio,135:1,5mio", e.plan());
        assertEquals(1, e.runs(), "135 ist das Ziel-Level, keine Rundenzahl");
        // Gelesen heisst nicht gesetzt -- sonst uebernaehme ein ganz anderer Portal-Start den Plan.
        assertEquals(0, zielGesetzt());
        // Auch "!after portal bis 120" geht jetzt (frueher: "nur mit !start").
        assertEquals("bis 120", DiscordBridge.eintragLesen("portal bis 120").plan());
    }

    @Test
    void einstellungenJeEintrag() {
        DiscordBridge.Eintragstext k = DiscordBridge.eintragLesen("cathedral 50 c food noevent");
        assertEquals("cathedral", k.modus());
        assertEquals(50, k.runs());
        assertEquals("c", k.weg());
        assertEquals(Bezahlung.Art.NAHRUNG, k.bezahl());
        assertEquals(Boolean.FALSE, k.kreislauf());

        DiscordBridge.Eintragstext v = DiscordBridge.eintragLesen("dungeon 10 bob,alice tickets");
        assertEquals("dungeon", v.modus());
        assertEquals(10, v.runs());
        assertEquals("bob,alice", v.freund().freund());
        assertEquals(Bezahlung.Art.TICKETS, v.bezahl());

        assertEquals(100_000, DiscordBridge.eintragLesen("event hammer").runs(), "Event ohne Zahl: bis leer");
        assertEquals(100_000, DiscordBridge.eintragLesen("grabung").runs());
        assertEquals(1, DiscordBridge.eintragLesen("cathedral noevent").runs(), "ohne Event und ohne Zahl: einmal");
        assertEquals(100_000, DiscordBridge.eintragLesen("cathedral eventloop").runs(), "mit Event-Kreislauf ohne Zahl: bis leer");
        assertEquals(Boolean.TRUE, DiscordBridge.eintragLesen("cathedral eventloop").kreislauf());
        assertEquals(Bezahlung.Art.NAHRUNG, DiscordBridge.eintragLesen("arena 5 aepfel").bezahl());

        DiscordBridge.Eintragstext s = DiscordBridge.eintragLesen("stigmas leveln medusa");
        assertEquals("stigmas", s.modus());
        assertEquals("leveln medusa", s.plan());
        assertNotNull(DiscordBridge.eintragLesen("stigmas").fehler());
        assertNotNull(DiscordBridge.eintragLesen("bananen 5").fehler());
    }

    @Test
    void stehenderBotStartetNummerEinsDanachDieAnderenDerReiheNach() throws Exception {
        List<DiscordBridge.Auftrag> kette = new ArrayList<>();
        kette.add(new DiscordBridge.Auftrag("dungeon", 3, "a", "", false));   // alter Rest von frueher
        List<DiscordBridge.Auftrag> neu = new ArrayList<>();
        for (String t : DiscordBridge.warteschlangeTeilen("portal bis 135 farm 80:2mio | cathedral 50 c | dungeon 10")) neu.add(auftrag(t));

        assertTrue(DiscordBridge.einreihen(kette, neu, false), "Bot steht: Nummer 1 startet gleich");
        assertEquals(List.of("portal", "cathedral", "dungeon"), kette.stream().map(DiscordBridge.Auftrag::modus).toList());

        // naechstenStarten: Nummer 1 ist dran, sein Plan gilt jetzt.
        DiscordBridge.Auftrag eins = kette.get(0);
        DiscordBridge.portalPlanAnwenden(eins);
        assertEquals(135, zielGesetzt());
        assertEquals(1, farmGesetzt().size());
        assertArrayEquals(new long[]{80, 2_000_000}, farmGesetzt().get(0));

        // zustandPruefen bei FERTIG: der fertige faellt weg, der naechste ist dran -- der Reihe nach.
        kette.remove(0);
        assertEquals("cathedral", kette.get(0).modus());
        assertEquals(50, kette.get(0).runs());
        kette.remove(0);
        assertEquals("dungeon", kette.get(0).modus());
        kette.remove(0);
        assertTrue(kette.isEmpty());
    }

    @Test
    void laufenderBotBekommtDieListeHintenAn() {
        List<DiscordBridge.Auftrag> kette = new ArrayList<>(List.of(new DiscordBridge.Auftrag("arena", 5, "a", "", false)));
        List<DiscordBridge.Auftrag> neu = List.of(auftrag("cathedral 20 b"), auftrag("portal bis 120"));
        assertFalse(DiscordBridge.einreihen(kette, neu, true), "Bot laeuft: nichts startet sofort");
        assertEquals(List.of("arena", "cathedral", "portal"), kette.stream().map(DiscordBridge.Auftrag::modus).toList());
        assertEquals("bis 120", kette.get(2).plan());
    }

    @Test
    void portalOhnePlanLeertDenAltenPlan() throws Exception {
        PortalMode.planAusText("portal bis 110");
        DiscordBridge.portalPlanAnwenden(auftrag("portal"));
        assertEquals(0, zielGesetzt(), "wie !start portal: ohne Plan-Woerter kein Aufstieg");
        // Fortsetzung nach dem Clan-Krieg: dort bleibt der Rest des Plans (PortalMode.fortsetzungErwarten), nichts setzen.
        PortalMode.planAusText("portal bis 110");
        DiscordBridge.portalPlanAnwenden(new DiscordBridge.Auftrag("portal", 1, "fortsetzung", "", false));
        assertEquals(110, zielGesetzt());
    }

    @Test
    void endzeitBeendetDieGanzeListe() {
        long jetzt = 1_000_000L;
        assertFalse(DiscordBridge.stoppzeitErreicht(0, jetzt), "keine Endzeit");
        assertFalse(DiscordBridge.stoppzeitErreicht(jetzt + 60_000, jetzt), "noch nicht");
        assertTrue(DiscordBridge.stoppzeitErreicht(jetzt, jetzt));
        assertTrue(DiscordBridge.stoppzeitErreicht(jetzt - 1, jetzt));
    }

    /**
     * Autor 18.11. (18:51): "!ende 18:50" muss fuer die ganze Warteschlange gelten -- auch fuer Folge-Auftraege (Spielquests,
     * Kathedrale -> Verlies). Eine Endzeit in der Zukunft haelt nichts an (und wird beim Start nicht geloescht: alteEndzeitLoeschen
     * loescht nur, was stoppzeitErreicht sagt).
     */
    @Test
    void endzeitGiltFuerDieGanzeWarteschlange() {
        long jetzt = 1_000_000L;
        assertFalse(DiscordBridge.endzeitBeendetKette(0, jetzt, false), "keine Endzeit, normales Ende: weiter");
        assertFalse(DiscordBridge.endzeitBeendetKette(jetzt + 60_000, jetzt, false), "Endzeit noch nicht da: weiter");
        assertTrue(DiscordBridge.endzeitBeendetKette(jetzt - 1, jetzt, false), "Endzeit vorbei: Schluss");
        assertTrue(DiscordBridge.endzeitBeendetKette(jetzt, jetzt, false), "genau die Endzeit: Schluss");
        // Der Motor hat wegen der Zeitgrenze aufgehoert, die Endzeit ist aber inzwischen weg oder verschoben: trotzdem Schluss.
        assertTrue(DiscordBridge.endzeitBeendetKette(0, jetzt, true));
        assertTrue(DiscordBridge.endzeitBeendetKette(jetzt + 60_000, jetzt, true));
        // Vor dem Start gesetzt und noch in der Zukunft: gilt nicht als "alt" und bleibt stehen.
        assertFalse(DiscordBridge.stoppzeitErreicht(jetzt + 40 * 60_000L, jetzt));
    }

    /**
     * Pruefung 15.11.: Eine Fenster-Zeile "Clan-Krieg" hinter einer langen Kathedrale sperrte bis zu ihr jeden Einschub -- kein
     * Krieg zur Kriegszeit, kein Leuchtturm, Kolosseum, Turm (nebenbeiPruefen/kriegPruefen sahen "Krieg schon eingereiht").
     */
    @Test
    void geplanterKriegWeiterHintenSperrtKeineEinschuebe() {
        DiscordBridge.Auftrag kathedrale = auftrag("cathedral 50 c");
        DiscordBridge.Auftrag kriegGeplant = auftrag("clanwar");
        DiscordBridge.Auftrag kriegVonSelbst = new DiscordBridge.Auftrag("clanwar", 1, "a", "", false);
        assertEquals("clanwar", kriegGeplant.modus());
        assertFalse(DiscordBridge.kriegEingereiht(List.of(kathedrale, auftrag("dungeon 10"), kriegGeplant)));
        // Laeuft der geplante Krieg gerade (vorn) oder ist einer von selbst eingeschoben: Ruhe wie bisher.
        assertTrue(DiscordBridge.kriegEingereiht(List.of(kriegGeplant, kathedrale)));
        assertTrue(DiscordBridge.kriegEingereiht(List.of(kathedrale, kriegVonSelbst, kathedrale)));
        assertTrue(DiscordBridge.kriegEingereiht(List.of(kriegVonSelbst)));
        assertFalse(DiscordBridge.kriegEingereiht(List.of()));
        assertFalse(DiscordBridge.kriegEingereiht(List.of(kathedrale)));
    }

    @Test
    void eintragFuerMenschen() {
        String t = DiscordBridge.eintragText(auftrag("portal bis 135 farm 80:2mio"));
        assertTrue(t.startsWith("Portal") && t.contains("bis 135 farm 80:2mio"), t);
        assertTrue(DiscordBridge.eintragText(auftrag("dungeon 10 bob")).contains("bob"));
    }

    @Test
    void auftragBehaeltSeineEinstellungenBeimKuerzen() {
        DiscordBridge.Auftrag a = auftrag("arena 10 food");
        DiscordBridge.Auftrag k = a.mitRuns(4);
        assertEquals(4, k.runs());
        assertTrue(k.eigen());
        assertEquals(Bezahlung.Art.NAHRUNG, k.bezahl());
        assertFalse(new DiscordBridge.Auftrag("clanwar", 1, "a", "", false).eigen(), "Einschuebe bringen nichts mit");
    }
}
