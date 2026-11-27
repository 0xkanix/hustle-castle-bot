package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hustlebot.bot.SpielQuests.Quest;
import de.hustlebot.bot.SpielQuests.Ziel;
import de.hustlebot.bot.SpielQuests.Zuordnung;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Taegliche Spiel-Quests (Autor 18.11.): Zuordnung Text -> Modus, "Progress x/y", Laeufe, Folge-Auftraege. */
class SpielQuestsTest {

    private static Ziel ziel(String titel, String aufgabe) {
        return SpielQuests.zuordnen(titel, aufgabe).ziel();
    }

    private static Ziel ziel(String text) {
        return SpielQuests.zuordnen(text).ziel();
    }

    // ------------------------------------------------------------------ Zuordnung

    @Test
    void bekannteTitelVomKlickProtokoll() {
        assertEquals(Ziel.WERBUNG, ziel("Ad-related quest", null));
        assertEquals(Ziel.WERBUNG, ziel("Movie Theater", ""));
        assertEquals(Ziel.GEMBAY, ziel("Boarding Crew", ""));
        assertEquals(Ziel.UNBEKANNT, ziel("Jack of All Trades", ""));
        // Titel unbekannt, aber die Aufgabe rechts sagt es
        assertEquals(Ziel.GEMBAY, ziel("Boarding Crew", "Win 5 battles in the Gem Bay"));
        assertEquals(Ziel.WERBUNG, ziel("Movie Theater", "Watch 3 video ads"));
    }

    @Test
    void ortNachStichwort() {
        String[][] faelle = {
                {"Win 5 battles in the Gem Bay", "GEMBAY"},
                {"Clear 20 waves in the Gem Bay without losses", "GEMBAY"},
                {"Clear waves without losing a unit", "GEMBAY"},
                {"Complete a journey to the GemBay", "GEMBAY"},
                {"Win 3 battles in the Arena", "ARENA"},
                {"Take part in an Arena tournament", "ARENA"},
                {"Earn Badges of Courage in the Arena", "ARENA"},
                {"Defeat 6 bosses in the Dungeon", "VERLIES"},
                {"Complete the Dungeon", "VERLIES"},
                {"Defeat the floor guardians of the Dark Cathedral", "KATHEDRALE"},
                {"Explore the Dark Cathedral", "KATHEDRALE"},
                {"Win battles at the Lighthouse", "LEUCHTTURM"},
                {"Win 3 battles in the Colosseum", "KOLOSSEUM"},
                {"Clear 30 floors of the Titan Tower", "TURM"},
                {"Climb the Tower", "TURM"},
                {"Watch an ad", "WERBUNG"},
                {"Watch 3 video ads", "WERBUNG"},
                {"Watch advertisements", "WERBUNG"},
        };
        List<String> fehler = new ArrayList<>();
        for (String[] f : faelle) {
            Ziel z = ziel(f[0]);
            if (!z.name().equals(f[1])) fehler.add(f[0] + " -> " + z + " (erwartet " + f[1] + ")");
        }
        assertEquals(List.of(), fehler);
    }

    @Test
    void lesefehlerDerTexterkennung() {
        String[][] faelle = {
                {"Win 5 battles in the Gem 8ay", "GEMBAY"},       // "gem"+"8ay" ist nah genug an "gembay"
                {"Win 5 battIes in the GemBay", "GEMBAY"},
                {"Win battles at the L1ghthouse", "LEUCHTTURM"},
                {"Win battles at the Light house", "LEUCHTTURM"},
                {"Win battles at the Lighthuose", "LEUCHTTURM"},
                {"Defeat bosses ir Dungeon", "VERLIES"},
                {"Defeat B0sses", "VERLIES"},
                {"Defeat 6 bosses in the Dungeom", "VERLIES"},
                {"Explore the Dark Cathedrai", "KATHEDRALE"},
                {"Defeat the floor guardlans", "KATHEDRALE"},
                {"Win 3 battles in the Colloseum", "KOLOSSEUM"},
                {"Ad-reIated quest", "WERBUNG"},
                {"Movle Theater", "WERBUNG"},
                {"Movie Theatre", "WERBUNG"},
                {"Boardinq Crew", "GEMBAY"},
                {"Clear 30 Tower noors", "TURM"},
                {"Win PVP battles", "PVP"},
        };
        List<String> fehler = new ArrayList<>();
        for (String[] f : faelle) {
            Ziel z = ziel(f[0]);
            String erwartet = f[1];
            if (!z.name().equals(erwartet)) fehler.add(f[0] + " -> " + z + " (erwartet " + erwartet + ")");
        }
        assertEquals(List.of(), fehler);
    }

    @Test
    void pvpUndClanWerdenNieGespielt() {
        assertEquals(Ziel.PVP, ziel("Win PvP battles by attacking other players' castles"));
        assertEquals(Ziel.PVP, ziel("Attack 3 castles"));
        assertEquals(Ziel.CLAN, ziel("Go to a Dungeon with a clan mate"));
        assertEquals(Ziel.CLAN, ziel("Win a battle in the Clan War"));
        assertEquals(Ziel.CLAN, ziel("Donate to your clan"));
        assertFalse(Ziel.PVP.botKann());
        assertFalse(Ziel.CLAN.botKann());
        assertEquals(0, SpielQuests.laeufe(SpielQuests.zuordnen("Attack 3 castles"), 0, 3));
    }

    @Test
    void keineFalschenTreffer() {
        // "power" ist kein "tower", "areas" keine "arena", "already"/"trade" enthalten kein Werbe-"ad"
        assertEquals(Ziel.UNBEKANNT, ziel("Increase your castle power"));
        assertEquals(Ziel.UNBEKANNT, ziel("Explore new areas"));
        assertEquals(Ziel.UNBEKANNT, ziel("Jack of All Trades", "Upgrade items already crafted"));
        assertEquals(Ziel.UNBEKANNT, ziel("Collect resources in your castle"));
        assertEquals(Ziel.UNBEKANNT, ziel(""));
        assertEquals(Ziel.UNBEKANNT, ziel(null, null));
        assertEquals(Ziel.UNBEKANNT, ziel("Train 10 dwellers"));
    }

    @Test
    void reihenfolgePvpVorOrt() {
        // PvP zuerst, auch wenn "battles" darin steht; Clan vor Verlies
        assertEquals(Ziel.PVP, ziel("Win battles by attacking players' castles"));
        assertEquals(Ziel.CLAN, ziel("Defeat bosses in a Dungeon with a clan mate"));
        // Ort vor Werbung: der Watch-Knopf koennte im Text mitgelesen sein
        assertEquals(Ziel.ARENA, ziel("Win 3 battles in the Arena Watch"));
    }

    @Test
    void arenaAbzeichenZaehlenAnders() {
        Zuordnung abzeichen = SpielQuests.zuordnen("Earn 1800 Badges of Courage in the Arena");
        Zuordnung siege = SpielQuests.zuordnen("Win 6 battles in the Arena");
        assertEquals(Ziel.ARENA, abzeichen.ziel());
        assertEquals(150, abzeichen.einheitenJeLauf());
        assertEquals(3, siege.einheitenJeLauf());
    }

    // ------------------------------------------------------------------ Progress

    @Test
    void progressParsen() {
        assertArrayEquals(new long[]{1, 3}, SpielQuests.fortschritt("Progress 1/3"));
        assertArrayEquals(new long[]{1, 3}, SpielQuests.fortschritt("Progress: 1/3"));
        assertArrayEquals(new long[]{0, 5}, SpielQuests.fortschritt("Progress 0 / 5"));
        assertArrayEquals(new long[]{1, 5}, SpielQuests.fortschritt("Progress1/5"));
        assertArrayEquals(new long[]{1, 3}, SpielQuests.fortschritt("Pr0gress l/3"));
        assertArrayEquals(new long[]{0, 1}, SpielQuests.fortschritt("Progress O/1"));
        assertArrayEquals(new long[]{0, 1}, SpielQuests.fortschritt("Progres 0/1"));
        assertArrayEquals(new long[]{2, 5}, SpielQuests.fortschritt("progess 2/5"));
        assertArrayEquals(new long[]{508, 1500}, SpielQuests.fortschritt("Progress 508/1 500"));
        assertArrayEquals(new long[]{0, 1}, SpielQuests.fortschritt("Progress 10/1"));   // Kante am Balken
        assertArrayEquals(new long[]{3, 3}, SpielQuests.fortschritt("Progress 3/3"));
        // Zahl hinter dem Bruch gehoert nicht dazu (Belohnung)
        assertArrayEquals(new long[]{1, 3}, SpielQuests.fortschritt("Progress 1/3 x10"));
    }

    @Test
    void progressUeberMehrereZeilen() {
        assertArrayEquals(new long[]{2, 5}, SpielQuests.fortschritt(List.of("Win 5 battles in the Gem Bay", "Progress", "2/5")));
        assertArrayEquals(new long[]{1, 3}, SpielQuests.fortschritt(List.of("Watch 3 video ads", "Progress 1/3", "Reward", "x5")));
        // ohne "Progress": eine Zeile, die nur aus dem Bruch besteht
        assertArrayEquals(new long[]{0, 1}, SpielQuests.fortschritt(List.of("Watch an ad", "0/1")));
        assertNull(SpielQuests.fortschritt(List.of("Watch an ad", "Reward x5")));
        assertNull(SpielQuests.fortschritt((String) null));
        assertNull(SpielQuests.fortschritt("Progress"));
        assertNull(SpielQuests.fortschritt("Progress 0/0"));
    }

    @Test
    void bruchLaesstWoerterInRuhe() {
        assertNull(SpielQuests.bruch("Gold Ore"));
        assertNull(SpielQuests.bruch("Collect gold"));
        assertArrayEquals(new long[]{5, 10}, SpielQuests.bruch("5/10"));
    }

    // ------------------------------------------------------------------ Laeufe

    @Test
    void laeufeBerechnen() {
        Zuordnung verlies = SpielQuests.zuordnen("Defeat 6 bosses in the Dungeon");
        assertEquals(4, SpielQuests.laeufe(verlies, 2, 6));
        assertEquals(0, SpielQuests.laeufe(verlies, 6, 6));
        assertEquals(1, SpielQuests.laeufe(verlies, -1, -1));    // nicht gelesen: einmal hin
        assertEquals(10, SpielQuests.laeufe(verlies, 0, 40));    // Grenze

        Zuordnung arena = SpielQuests.zuordnen("Win 6 battles in the Arena");
        assertEquals(2, SpielQuests.laeufe(arena, 0, 6));        // 3 Siege je Lauf
        assertEquals(1, SpielQuests.laeufe(arena, 4, 6));
        Zuordnung abzeichen = SpielQuests.zuordnen("Earn 1800 Badges of Courage in the Arena");
        assertEquals(5, SpielQuests.laeufe(abzeichen, 0, 1800)); // 12 noetig, Grenze 5

        Zuordnung gembay = SpielQuests.zuordnen("Boarding Crew", "Win 5 battles in the Gem Bay");
        assertEquals(1, SpielQuests.laeufe(gembay, 0, 5));
        assertEquals(1, SpielQuests.laeufe(gembay, 0, 100));     // ein Gem-Bay-Lauf spielt bis zum Diamant-Preis

        Zuordnung turm = SpielQuests.zuordnen("Clear 150 floors of the Titan Tower");
        assertEquals(1, SpielQuests.laeufe(turm, 0, 150));       // Turm laeuft genau einmal
        assertEquals(1, SpielQuests.laeufe(SpielQuests.zuordnen("Win 10 battles at the Lighthouse"), 0, 10));

        Zuordnung werbung = SpielQuests.zuordnen("Movie Theater", "");
        assertEquals(3, SpielQuests.laeufe(werbung, 0, 3));      // 3 Videos
        assertEquals(2, SpielQuests.laeufe(werbung, 1, 3));
        assertEquals(0, SpielQuests.laeufe(SpielQuests.zuordnen("Jack of All Trades"), 0, 3));
        assertEquals(0, SpielQuests.laeufe(null, 0, 3));
    }

    @Test
    void questOffenUndErledigt() {
        Quest q = new Quest("Boarding Crew", "Win 5 battles in the Gem Bay", 2, 5, false);
        assertEquals(3, q.offen());
        assertFalse(q.erledigt());
        assertEquals(1, q.laeufe());
        Quest fertig = new Quest("Movie Theater", "", 3, 3, false);
        assertTrue(fertig.erledigt());
        assertEquals(0, fertig.laeufe());
        Quest abgeholt = new Quest("Boarding Crew", "", 0, 5, false).mitAbgeholt();
        assertTrue(abgeholt.erledigt());
        assertEquals(0, abgeholt.offen());
        assertEquals(1, new Quest("Ad-related quest", "", -1, -1, false).offen());
    }

    // ------------------------------------------------------------------ Folge-Auftraege

    @Test
    void folgenJeModusEinmalInFesterReihenfolge() {
        List<Quest> qs = List.of(
                new Quest("Ad-related quest", "Watch an ad", 0, 1, false),
                new Quest("Movie Theater", "Watch 3 video ads", 0, 3, false),
                new Quest("Arena Fan", "Win 6 battles in the Arena", 0, 6, false),
                new Quest("Badges", "Earn 300 Badges of Courage in the Arena", 0, 300, false),
                new Quest("Boarding Crew", "Win 5 battles in the Gem Bay", 0, 5, false),
                new Quest("Jack of All Trades", "Complete 5 daily quests", 0, 5, false),
                new Quest("Bosses", "Defeat 3 bosses in the Dungeon", 3, 3, false),
                new Quest("Raider", "Attack 3 castles", 0, 3, false),
                new Quest("Tower", "Clear 30 floors of the Titan Tower", 0, 30, false));
        List<GameMode.Folge> f = SpielQuests.folgen(qs);
        assertEquals(List.of(new GameMode.Folge("gembay", 1), new GameMode.Folge("arena", 2),
                new GameMode.Folge("titantower", 1)), f);
        de.hustlebot.core.Sprache.Lang vorher = de.hustlebot.core.Sprache.get();
        try {
            de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE);
            String text = SpielQuests.zusammenfassung(qs, f, 1, 0);
            assertTrue(text.startsWith("9 Quests gelesen, 6 offen, 2 uebersprungen"), text);
            assertTrue(text.contains("gembay x1, arena x2, titantower x1"), text);
            // Autor 22.11.: bei Englisch alles englisch
            de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN);
            String en = SpielQuests.zusammenfassung(qs, f, 1, 0);
            assertTrue(en.startsWith("9 quests read, 6 open, 2 skipped, 1 ad(s) watched. Then: gembay x1"), en);
        } finally {
            de.hustlebot.core.Sprache.set(vorher);
        }
    }

    @Test
    void keineFolgenOhneOffeneQuests() {
        assertEquals(List.of(), SpielQuests.folgen(List.of()));
        assertEquals(List.of(), SpielQuests.folgen(null));
        assertEquals(List.of(), SpielQuests.folgen(List.of(new Quest("Bosses", "Defeat 3 bosses in the Dungeon", 3, 3, false),
                new Quest("Ad-related quest", "", 0, 1, false))));
    }

    // ------------------------------------------------------------------ Liste und Feld rechts

    @Test
    void questNamenInDerListe() {
        assertTrue(SpielQuests.istQuestName("Ad-related quest"));
        assertTrue(SpielQuests.istQuestName("Movie Theater"));
        assertTrue(SpielQuests.istQuestName("Jack of All Trades"));
        assertTrue(SpielQuests.istQuestName("Boarding Crew"));
        assertFalse(SpielQuests.istQuestName("Daily quests"));
        assertFalse(SpielQuests.istQuestName("Progress 1/3"));
        assertFalse(SpielQuests.istQuestName("0/1"));
        assertFalse(SpielQuests.istQuestName("508/1500"));
        assertFalse(SpielQuests.istQuestName("5h 20m"));
        assertFalse(SpielQuests.istQuestName("Ends in 47s"));
        assertFalse(SpielQuests.istQuestName("x10"));
        assertFalse(SpielQuests.istQuestName("Watch"));
        assertFalse(SpielQuests.istQuestName("Show"));
        assertFalse(SpielQuests.istQuestName("Claim"));
        assertFalse(SpielQuests.istQuestName("abc"));
        assertFalse(SpielQuests.istQuestName(null));
    }

    @Test
    void feldRechtsLesen() {
        SpielQuests.Detail d = SpielQuests.detail("Boarding Crew",
                List.of("Boarding Crew", "Win 5 battles in the Gem Bay", "Progress 2/5", "Reward", "x10", "Show"));
        assertEquals("Boarding Crew", d.titel());
        assertEquals("Win 5 battles in the Gem Bay", d.aufgabe());
        assertEquals(2, d.stand());
        assertEquals(5, d.ziel());
        assertEquals(Ziel.GEMBAY, d.alsQuest().zielArt());

        SpielQuests.Detail w = SpielQuests.detail("Movie Theater", List.of("Watch 3 video ads", "Progress O/3", "Watch"));
        assertEquals("Watch 3 video ads", w.aufgabe());
        assertEquals(0, w.stand());
        assertEquals(3, w.ziel());
        assertEquals(3, w.alsQuest().laeufe());

        SpielQuests.Detail ohne = SpielQuests.detail(null, List.of("Jack of All Trades", "Complete 5 quests"));
        assertEquals("Jack of All Trades", ohne.titel());
        assertEquals("Complete 5 quests", ohne.aufgabe());
        assertEquals(-1, ohne.ziel());
        assertEquals("", SpielQuests.detail("X", null).aufgabe());
    }

    @Test
    void schluesselVertraegtLeerzeichenUndZeichen() {
        assertEquals(SpielQuests.schluessel("Ad-related quest"), SpielQuests.schluessel("Ad related  quest."));
        assertEquals("", SpielQuests.schluessel(null));
    }

    @Test
    void woerterMitZiffernFehlern() {
        // "1" wird zu "l" ("L1ghthouse" -> "llghthouse"), das passt mit einem Buchstaben Abstand zu "lighthouse"
        assertEquals(List.of("win", "battles", "at", "the", "llghthouse"), SpielQuests.woerter("Win battles at the L1ghthouse"));
        assertTrue(SpielQuests.passt("llghthouse", "lighthouse"));
        assertEquals(List.of("5h", "20m", "x10"), SpielQuests.woerter("5h 20m x10"));
        assertTrue(SpielQuests.passt("bosses", "boss"));
        assertFalse(SpielQuests.passt("power", "tower"));
        assertTrue(SpielQuests.passt("dungeom", "dungeon"));
        assertEquals(1, SpielQuests.abstand("kitten", "sitten"));
        assertEquals(3, SpielQuests.abstand("kitten", "sitting"));
    }

    // ------------------------------------------------------------------ Anmeldung

    @Test
    void modusIstUeberallAngemeldet() throws Exception {
        assertEquals("spielquests", new SpielQuestsMode().id());
        assertEquals("spielquests", de.hustlebot.core.Vorlagenordner.fuer("sq_buch"));
        assertEquals("spielquests", de.hustlebot.core.Vorlagenordner.fuer("sq_reiter_daily.png"));
        assertEquals(de.hustlebot.steuer.Befehlsbau.Modus.SPIELQUESTS,
                de.hustlebot.steuer.Befehlsbau.Modus.aus("spielquests"));
        Method m = de.hustlebot.discord.DiscordBridge.class.getDeclaredMethod("modusAus", String.class);
        m.setAccessible(true);
        assertEquals("spielquests", m.invoke(null, "spielquests"));
        assertEquals("spielquests", m.invoke(null, "spielquests 1"));
        assertEquals("spielquests", m.invoke(null, "daily quests"));
        assertEquals("event", m.invoke(null, "event"));
        assertEquals("gembay", m.invoke(null, "gembay"));
    }

    @Test
    void werbungIstEmpfindlich() {
        SpielQuestsMode m = new SpielQuestsMode();
        m.reset();
        assertFalse(m.inEmpfindlicherPhase());
        assertEquals(List.of(), m.folgeAuftraege());
        assertTrue(m.requiredTemplates().isEmpty());
    }
}
