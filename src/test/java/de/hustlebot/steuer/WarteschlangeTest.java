package de.hustlebot.steuer;

import de.hustlebot.discord.DiscordBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Das Fenster "Warteschlange" ohne Bildschirm (Autor 15.11.): aus den Zeilen werden die Befehle, in der richtigen Reihenfolge;
 * ein Portal-Plan steht im Eintrag; ein Profil laesst sich speichern und wieder laden; und der Bot versteht jeden Eintrag.
 */
class WarteschlangeTest {

    private static Warteschlange.Eintrag portal() {
        return new Warteschlange.Eintrag(Befehlsbau.Modus.PORTAL, 0, "c", null, null, "", "135", "80", "2 Mio", "135", "1,5mio", "");
    }

    private static Warteschlange.Eintrag kathedrale() {
        return new Warteschlange.Eintrag(Befehlsbau.Modus.KATHEDRALE, 50, "b", Befehlsbau.Bezahlart.FOOD,
                Warteschlange.Ablauf.OHNE_EVENT, "", "", "", "", "", "", "");
    }

    private static Warteschlange.Eintrag verlies() {
        return new Warteschlange.Eintrag(Befehlsbau.Modus.VERLIES, 10, "c", Befehlsbau.Bezahlart.TICKETS, null,
                "Bob, alice", "", "", "", "", "", "");
    }

    private static Warteschlange.Eintrag stigmas() {
        return new Warteschlange.Eintrag(Befehlsbau.Stigma.LEVELN, 1, "c", null, null, "", "", "", "", "", "", "medusa");
    }

    private static Warteschlange.Eintrag hammer() {
        return Warteschlange.Eintrag.von(Befehlsbau.Modus.HAMMER, 0);
    }

    @Test
    void warteschlangeWirdZuBefehlenInDerRichtigenReihenfolge() {
        Map<Warteschlange.Schalter, Boolean> schalter = new EnumMap<>(Warteschlange.Schalter.class);
        schalter.put(Warteschlange.Schalter.CLANKRIEG, false);
        schalter.put(Warteschlange.Schalter.LEUCHTTURM, true);
        List<String> b = Warteschlange.befehle(List.of(portal(), kathedrale(), verlies(), hammer(), stigmas()), schalter, "6:30", false);
        assertEquals(List.of(
                "!settings leuchtturm an",
                "!kriegsplan aus",
                "!ende 06:30",
                "!queue start portal bis 135 farm 80:2mio,135:1,5mio | cathedral 50 b food noevent | dungeon 10 bob,alice tickets"
                        + " | event hammer | stigmas leveln medusa"), b);
    }

    @Test
    void leereZeilenFallenWegUndOhneModusKeinStart() {
        List<Warteschlange.Eintrag> l = new ArrayList<>();
        l.add(Warteschlange.Eintrag.von(null, 10));
        l.add(kathedrale());
        l.add(Warteschlange.Eintrag.von(null, 10));
        assertEquals("!queue start cathedral 50 b food noevent", Warteschlange.startBefehl(l, false));
        assertEquals("", Warteschlange.startBefehl(List.of(Warteschlange.Eintrag.von(null, 5)), false));
        assertEquals(List.of(), Warteschlange.befehle(List.of(), Map.of(), "", false));
    }

    @Test
    void portalPlanStehtImEintrag() {
        assertEquals("portal bis 135 farm 80:2mio,135:1,5mio", portal().text(false));
        // Nur das Ziel, kein Farmen
        assertEquals("portal bis 120", new Warteschlange.Eintrag(Befehlsbau.Modus.PORTAL, 0, "c", null, null, "",
                "120", "", "", "", "", "").text(false));
        // Ein Level ohne Nahrung faellt weg (wie im Steuerfenster)
        assertEquals("portal farm 80:500k", new Warteschlange.Eintrag(Befehlsbau.Modus.PORTAL, 0, "c", null, null, "",
                "", "80", "500k", "130", "", "").text(false));
    }

    @Test
    void stigmasLoeschenOhneBehalteneSetsSagtAlles() {
        Warteschlange.Eintrag e = Warteschlange.Eintrag.von(Befehlsbau.Stigma.LOESCHEN, 1);
        assertEquals("stigmas loeschen", e.text(false));
        assertEquals("stigmas loeschen alles", e.text(true));
        assertEquals("stigmas leveln", new Warteschlange.Eintrag(Befehlsbau.Stigma.LEVELN, 1, "c", null, null, "", "", "", "", "", "", "").text(false));
        assertEquals("stigmas hundert keeper", new Warteschlange.Eintrag(Befehlsbau.Stigma.HUNDERT, 1, "c", null, null, "", "", "", "", "", "",
                Warteschlange.titanWort("Keeper of Stars (Tank)")).text(false));
    }

    @Test
    void hochUndRunterTauschenZeilen() {
        List<String> l = new ArrayList<>(List.of("A", "B", "C", "D", "E"));
        assertEquals(1, Warteschlange.verschieben(l, 2, -1));
        assertEquals(List.of("A", "C", "B", "D", "E"), l);
        assertEquals(4, Warteschlange.verschieben(l, 3, +1));
        assertEquals(List.of("A", "C", "B", "E", "D"), l);
        assertEquals(0, Warteschlange.verschieben(l, 0, -1), "ganz oben bleibt oben");
        assertEquals(4, Warteschlange.verschieben(l, 4, +1), "ganz unten bleibt unten");
        assertEquals(List.of("A", "C", "B", "E", "D"), l);
    }

    @Test
    void uhrzeitLesen() {
        assertEquals("06:30", Warteschlange.uhrzeit("6:30"));
        assertEquals("06:30", Warteschlange.uhrzeit("06.30"));
        assertEquals("22:00", Warteschlange.uhrzeit("22 uhr"));
        assertEquals("", Warteschlange.uhrzeit("  "));
        assertNull(Warteschlange.uhrzeit("25:00"));
        assertNull(Warteschlange.uhrzeit("morgen"));
    }

    @Test
    void profilSpeichernUndLaden(@TempDir Path ordner) {
        Map<Warteschlange.Schalter, Boolean> schalter = new EnumMap<>(Warteschlange.Schalter.class);
        for (Warteschlange.Schalter s : Warteschlange.Schalter.values()) schalter.put(s, s != Warteschlange.Schalter.KOLOSSEUM);
        List<Warteschlange.Eintrag> l = List.of(stigmas(), portal(), kathedrale(), verlies(), hammer());
        List<String> befehle = Warteschlange.befehle(l, schalter, "05:45", false);

        Path datei = ordner.resolve("profile.txt");
        assertTrue(new Profile(datei).setzen("Nacht-Kette", befehle));
        Profile neu = new Profile(datei);   // frisch von der Platte
        assertTrue(neu.namen().contains("Nacht-Kette"));

        Warteschlange.Geladen g = Warteschlange.lesen(neu.befehle("Nacht-Kette"));
        assertEquals(l.stream().map(e -> e.text(false)).toList(), g.eintraege().stream().map(e -> e.text(false)).toList(),
                "dieselben Eintraege in derselben Reihenfolge");
        assertEquals(schalter, g.schalter());
        assertEquals("05:45", g.endeUm());

        Warteschlange.Eintrag p = g.eintraege().get(1);
        assertEquals(Befehlsbau.Modus.PORTAL, p.art());
        assertEquals("135", p.portalBis());
        assertEquals("80", p.level1());
        assertEquals("2mio", p.nahrung1());
        assertEquals("135", p.level2());
        assertEquals("1,5mio", p.nahrung2());
        Warteschlange.Eintrag k = g.eintraege().get(2);
        assertEquals("b", k.weg());
        assertEquals(Befehlsbau.Bezahlart.FOOD, k.bezahl());
        assertEquals(Warteschlange.Ablauf.OHNE_EVENT, k.ablauf());
        assertEquals("bob,alice", g.eintraege().get(3).freund());
        assertEquals("medusa", g.eintraege().get(0).titan());
        assertEquals(0, g.eintraege().get(4).anzahl(), "Event bis leer");
    }

    @Test
    void alteProfileMitStartUndAfterWerdenZurWarteschlange() {
        Warteschlange.Geladen g = Warteschlange.lesen(List.of("!mute", "!start cathedral 50 c", "!after dungeon 10 bob",
                "!after portal bis 120", "!clanwar off", "!stigmas loeschen"));
        assertEquals(List.of("cathedral 50 c", "dungeon 10 bob", "portal bis 120", "stigmas loeschen"),
                g.eintraege().stream().map(e -> e.text(false)).toList());
        assertEquals(Map.of(Warteschlange.Schalter.CLANKRIEG, false), g.schalter());
        assertEquals("", g.endeUm());
    }

    @Test
    void hoechstensFuenfEintraege() {
        List<String> z = new ArrayList<>();
        for (int i = 1; i <= 7; i++) z.add("!after dungeon " + i);
        assertEquals(5, Warteschlange.lesen(z).eintraege().size());
    }

    /** Der Bot versteht JEDEN Eintrag, den das Fenster bauen kann -- als den richtigen Modus, mit Plan. */
    @Test
    void derBotVerstehtJedenEintrag() throws Exception {
        Method lesen = DiscordBridge.class.getDeclaredMethod("eintragLesen", String.class);
        lesen.setAccessible(true);
        List<String> fehler = new ArrayList<>();
        List<Warteschlange.Eintrag> alle = new ArrayList<>();
        for (Befehlsbau.Modus m : Befehlsbau.Modus.values()) {
            for (Befehlsbau.Bezahlart bz : Befehlsbau.Bezahlart.values()) {
                for (Warteschlange.Ablauf ab : Warteschlange.Ablauf.values()) {
                    alle.add(new Warteschlange.Eintrag(m, Warteschlange.bisLeer(m) ? 0 : 7, "b", bz, ab, "max", "130", "80", "2mio", "", "", ""));
                }
            }
        }
        for (Befehlsbau.Stigma s : new Befehlsbau.Stigma[]{Befehlsbau.Stigma.LOESCHEN, Befehlsbau.Stigma.LEVELN, Befehlsbau.Stigma.HUNDERT}) {
            for (String titan : Steuerfenster.titanNamen()) {
                alle.add(new Warteschlange.Eintrag(s, 1, "c", null, null, "", "", "", "", "", "", Warteschlange.titanWort(titan)));
            }
        }
        for (Warteschlange.Eintrag e : alle) {
            String text = e.text(false);
            Object g = lesen.invoke(null, text);
            String modus = (String) wert(g, "modus");
            String erwartet = e.art() instanceof Befehlsbau.Stigma ? "stigmas" : switch ((Befehlsbau.Modus) e.art()) {
                case KATHEDRALE -> "cathedral";
                case VERLIES -> "dungeon";
                case HAMMER -> "event";
                default -> ((Befehlsbau.Modus) e.art()).befehlswort;
            };
            if (wert(g, "fehler") != null || !erwartet.equals(modus)) fehler.add(text + "  ->  " + modus + " / " + wert(g, "fehler"));
            if (e.art() == Befehlsbau.Modus.PORTAL && !"bis 130 farm 80:2mio".equals(wert(g, "plan"))) fehler.add(text + "  ->  Plan " + wert(g, "plan"));
            if (e.art() instanceof Befehlsbau.Modus m && m.mitAnzahl && !Warteschlange.bisLeer(m) && !Integer.valueOf(7).equals(wert(g, "runs"))) {
                fehler.add(text + "  ->  Runs " + wert(g, "runs"));
            }
            // Und zurueck ins Fenster (Profil laden) ergibt denselben Eintrag.
            Warteschlange.Eintrag zurueck = Warteschlange.eintragAus(text);
            if (zurueck == null || !text.equals(zurueck.text(false))) fehler.add(text + "  ->  zurueck: " + (zurueck == null ? null : zurueck.text(false)));
        }
        assertEquals(List.of(), fehler);
    }

    private static Object wert(Object rekord, String name) throws Exception {
        Method m = rekord.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(rekord);
    }
}
