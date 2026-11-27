package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.hustlebot.bot.ClanVerliesPlan.Antwort.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Event-Quest "Verlies mit einem Clan-Kameraden" (Autor 18./19.11.): Warten bis 10 min, Clan-Chat lesen ("in 5 min" ->
 * neu einladen, "can't" -> aufgeben), "Thanks" beim vorletzten Stuetzpunkt (Verlies-Chat, ist der Mitspieler weg: Clan-Chat).
 */
class ClanVerliesPlanTest {

    private static final long T0 = 1_000_000L;
    private static final long MIN = 60_000L;

    private static ClanVerliesPlan eingeladen() {
        ClanVerliesPlan p = new ClanVerliesPlan(VerliesClan.eigeneTexte(1));
        p.gefragt();
        p.eingeladen(T0);
        return p;
    }

    /** So sieht der Clan-Chat ungefaehr aus, wie ihn die Texterkennung liest (Bild 19.11. 20:36:30). */
    private static List<String> chat(String... danach) {
        List<String> l = new java.util.ArrayList<>(List.of(
                "Message of the Day", "PLEASE ATTACK IMMORTAL BOSS EVERYDAY!!! :: BOSS: TR8 - Pumba",
                "Officer", "no", "raiders to hunters",
                "you", "3min ago",
                "Anyone up for 1 round of Dungeon with me for the event",
                "quest (play with a clan mate)? Inviting now via Clan, thanks!"));
        l.addAll(List.of(danach));
        return l;
    }

    @Test
    void ablaufFragenEinladenWarten() {
        ClanVerliesPlan p = new ClanVerliesPlan(VerliesClan.eigeneTexte(1));
        assertEquals(ClanVerliesPlan.Zustand.FRAGEN, p.zustand());
        p.gefragt();
        assertEquals(ClanVerliesPlan.Zustand.EINGELADEN, p.zustand());
        p.eingeladen(T0);
        assertEquals(ClanVerliesPlan.Schritt.WARTEN, p.imWartefenster(T0 + 1000, false));
        assertEquals(ClanVerliesPlan.Zustand.WARTEN, p.zustand());
        assertEquals(T0 + 10 * MIN, p.wartenBis(), "Autor 19.11.: bis zu 10 min warten");
        p.angenommen();
        assertEquals(ClanVerliesPlan.Zustand.ANGENOMMEN, p.zustand());
        p.onlineGelesen(0);
        assertEquals(ClanVerliesPlan.Zustand.ANGENOMMEN, p.zustand(), "einmal angenommen, gibt nichts mehr auf");
    }

    @Test
    void niemandOnlineGibtAuf() {
        ClanVerliesPlan p = new ClanVerliesPlan(VerliesClan.eigeneTexte(1));
        p.onlineGelesen(null);
        assertEquals(ClanVerliesPlan.Zustand.FRAGEN, p.zustand(), "unlesbar = trotzdem fragen");
        p.onlineGelesen(3);
        assertFalse(p.aufgegeben());
        p.onlineGelesen(0);
        assertTrue(p.aufgegeben());
        assertTrue(p.grund().contains("niemand online"), p.grund());
        assertEquals(ClanVerliesPlan.Schritt.ABBRECHEN, p.imWartefenster(T0, false));
    }

    @Test
    void chatBlickAlle90SekundenSofortBeiAbzeichen() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(ClanVerliesPlan.Schritt.WARTEN, p.imWartefenster(T0 + 89_000, false));
        assertEquals(ClanVerliesPlan.Schritt.CHAT_ANSEHEN, p.imWartefenster(T0 + 90_000, false));
        p.chatGelesen(chat(), 6, T0 + 90_000);
        assertEquals(ClanVerliesPlan.Schritt.WARTEN, p.imWartefenster(T0 + 100_000, false));
        assertEquals(ClanVerliesPlan.Schritt.WARTEN, p.imWartefenster(T0 + 109_000, true), "Abzeichen: erst nach 20 s wieder");
        assertEquals(ClanVerliesPlan.Schritt.CHAT_ANSEHEN, p.imWartefenster(T0 + 110_000, true));
    }

    @Test
    void nachZehnMinutenAbbrechen() {
        ClanVerliesPlan p = eingeladen();
        assertNotEquals(ClanVerliesPlan.Schritt.ABBRECHEN, p.imWartefenster(T0 + 10 * MIN - 1, false));
        assertEquals(ClanVerliesPlan.Schritt.ABBRECHEN, p.imWartefenster(T0 + 10 * MIN, false));
        assertTrue(p.aufgegeben());
        assertTrue(p.grund().contains("10 Minuten"), p.grund());
    }

    @Test
    void inFuenfMinutenHeisstSpaeterNeuEinladen() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(SPAETER, p.chatGelesen(chat("little Johnee", "in 5 min"), 8, T0 + 2 * MIN));
        assertEquals(ClanVerliesPlan.Zustand.SPAETER, p.zustand());
        assertEquals("in 5 min", p.letzteAntwortZeile());
        assertEquals(ClanVerliesPlan.Schritt.CHAT_ANSEHEN, p.imWartefenster(T0 + 6 * MIN, false), "noch nicht neu einladen");
        assertEquals(ClanVerliesPlan.Schritt.NEU_EINLADEN, p.imWartefenster(T0 + 7 * MIN, false));
        p.eingeladen(T0 + 7 * MIN + 5000);
        assertEquals(ClanVerliesPlan.Zustand.EINGELADEN, p.zustand());
        assertTrue(p.wartenBis() >= T0 + 12 * MIN, "nach dem neuen Einladen noch 5 min");
        assertEquals(ClanVerliesPlan.Schritt.WARTEN, p.imWartefenster(T0 + 8 * MIN, false));
    }

    @Test
    void nieLaengerAls20Minuten() {
        ClanVerliesPlan p = eingeladen();
        p.chatGelesen(chat("A", "in 5 min"), 8, T0 + 9 * MIN);
        p.chatGelesen(chat("A", "in 5 min", "B", "give me 5 mins pls"), 8, T0 + 16 * MIN);
        assertTrue(p.wartenBis() <= T0 + 20 * MIN, "hoechstens 20 min ab der ersten Einladung");
        assertEquals(ClanVerliesPlan.Schritt.ABBRECHEN, p.imWartefenster(T0 + 20 * MIN, false));
    }

    @Test
    void absageGibtAuf() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(ABSAGE, p.chatGelesen(chat("little Johnee", "sorry can't right now"), 8, T0 + MIN));
        assertTrue(p.aufgegeben());
        assertTrue(p.grund().contains("can't"), p.grund());
        assertEquals(ClanVerliesPlan.Schritt.ABBRECHEN, p.imWartefenster(T0 + 2 * MIN, false));
    }

    @Test
    void zusageGehtVorAbsageUndVerlaengert() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(ZUSAGE, p.chatGelesen(chat("A", "can't, at work", "B", "yes coming"), 8, T0 + 9 * MIN));
        assertFalse(p.aufgegeben());
        assertTrue(p.wartenBis() >= T0 + 12 * MIN, "nach einer Zusage noch mindestens 3 min");
    }

    @Test
    void nurNeueZeilenNachDerEigenenFrage() {
        ClanVerliesPlan p = eingeladen();
        // "no" steht VOR der Frage, die eigene Einladung und Zeitangaben zaehlen nie
        assertEquals(SONST, p.chatGelesen(chat("Autor", "is inviting you on a journey", "Cancel",
                "Clan mate quest - 1 round, thanks!", "2min ago"), 6, T0 + MIN));
        assertFalse(p.aufgegeben());
        assertEquals(ABSAGE, p.chatGelesen(chat("Autor", "Clan mate quest - 1 round, thanks!", "little Johnee", "nope"), 6,
                T0 + 3 * MIN));
    }

    @Test
    void gleicheZeileZaehltNurEinmal() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(ZUSAGE, p.chatGelesen(chat("A", "sure"), 6, T0 + MIN));
        assertEquals(SONST, p.chatGelesen(chat("A", "sure"), 6, T0 + 3 * MIN));
    }

    @Test
    void ohneEigeneFrageBeimErstenBlickNurMerken() {
        ClanVerliesPlan p = eingeladen();
        // Frage weggescrollt: altes "sorry can't" ist keine Antwort auf uns
        assertEquals(SONST, p.chatGelesen(List.of("Officer", "sorry can't, at work"), 6, T0 + MIN));
        assertFalse(p.aufgegeben());
        assertEquals(SPAETER, p.chatGelesen(List.of("Officer", "sorry can't, at work", "Robin", "brb 5 min"), 6, T0 + 3 * MIN));
    }

    @Test
    void zeilenEinordnen() {
        for (String z : List.of("in 5 min", "In 10 minutes", "give me 5", "later", "brb", "5 mins", "sorry, in 5 min",
                "wait a sec", "soon")) {
            assertEquals(SPAETER, ClanVerliesPlan.einordnen(z), z);
        }
        for (String z : List.of("can't", "Cant right now", "I cannot", "sorry", "busy", "not now", "no", "No thanks", "nope",
                "not today", "nah")) {
            assertEquals(ABSAGE, ClanVerliesPlan.einordnen(z), z);
        }
        for (String z : List.of("yes", "Yep!", "sure", "ok", "I'm in", "coming", "joining", "accepted", "on my way",
                "let's go", "i can", "no problem", "np")) {
            assertEquals(ZUSAGE, ClanVerliesPlan.einordnen(z), z);
        }
        for (String z : List.of("we just started planning our trip for Italy for next year", "raiders to hunters", "3min ago",
                "Officer", "you", "6/34 online", "", "Squad Leaders")) {
            assertEquals(SONST, ClanVerliesPlan.einordnen(z), z);
        }
        assertEquals(SONST, ClanVerliesPlan.einordnen(null));
    }

    // ---- "Thanks" im Verlies-Chat --------------------------------------------

    @Test
    void dankBeimVorletztenStuetzpunkt() {
        ClanVerliesPlan p = eingeladen();
        p.angenommen();
        // 19.11.: 7 Stuetzpunkte = 0/15/29/43/58/72/86/100 %; bei 72 % sind noch zwei offen, bei 86 % nur noch einer
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(-1, false), "unbekannter Fortschritt");
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(72, false));
        assertEquals(ClanVerliesPlan.Dank.VERLIES_CHAT, p.dankJetzt(86, false));
        p.gedankt();
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(86, false), "nur einmal");
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(100, false));
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(100, true), "schon gedankt: auch nicht mehr im Clan-Chat");
    }

    @Test
    void dankBeiFuenfStuetzpunktenAb80UndNotfalls100() {
        ClanVerliesPlan p = eingeladen();
        assertEquals(ClanVerliesPlan.Dank.VERLIES_CHAT, p.dankJetzt(80, false));
        ClanVerliesPlan q = eingeladen();
        assertEquals(ClanVerliesPlan.Dank.VERLIES_CHAT, q.dankJetzt(100, false),
                "verpasst (z. B. 4 Stuetzpunkte): bei 100 %, wenn er noch da ist");
    }

    @Test
    void mitspielerWegDannDankImClanChat() {
        // Autor 19.11. 21:25: "Your ally left the Dungeon" auf der Karte -> Thanks in den Clan-Chat, nicht weglassen
        ClanVerliesPlan p = eingeladen();
        assertEquals(ClanVerliesPlan.Dank.CLAN_CHAT, p.dankJetzt(86, true));
        assertTrue(p.istMitspielerWeg());
        p.gedankt();
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(100, false), "nur einmal");
        assertFalse(p.antwortPruefenJetzt(100, false), "er ist weg: keine Antwort abwarten");

        // "The other player left the Dungeon" erst im Verlies-Chat gesehen -> beim naechsten Blick Clan-Chat
        ClanVerliesPlan q = eingeladen();
        assertEquals(ClanVerliesPlan.Dank.VERLIES_CHAT, q.dankJetzt(90, false));
        q.mitspielerWeg();
        assertEquals(ClanVerliesPlan.Dank.CLAN_CHAT, q.dankJetzt(90, false));
        assertEquals(ClanVerliesPlan.Dank.CLAN_CHAT, q.dankJetzt(100, false), "weg bleibt weg");

        // Ging frueh (vor dem vorletzten Stuetzpunkt): noch nicht, erst ab 80 %
        ClanVerliesPlan r = eingeladen();
        assertEquals(ClanVerliesPlan.Dank.KEINER, r.dankJetzt(43, true));
        assertEquals(ClanVerliesPlan.Dank.CLAN_CHAT, r.dankJetzt(100, false), "nach dem Boss gegangen (19.11.)");
    }

    @Test
    void dankAusgelassenWirdNichtWiederholt() {
        ClanVerliesPlan p = eingeladen();
        p.dankAusgelassen();
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(90, false), "spielen hat Vorrang -- nicht bei jedem Blick neu versuchen");
        assertEquals(ClanVerliesPlan.Dank.KEINER, p.dankJetzt(90, true));
        assertFalse(p.antwortPruefenJetzt(100, false));
    }

    @Test
    void antwortNurEinmalAmEndeNachDemDank() {
        ClanVerliesPlan p = eingeladen();
        assertFalse(p.antwortPruefenJetzt(100, false), "ohne Thanks keine Antwort");
        p.gedankt();
        assertFalse(p.antwortPruefenJetzt(86, false));
        assertTrue(p.antwortPruefenJetzt(100, false));
        p.antwortGeprueft();
        assertFalse(p.antwortPruefenJetzt(100, false));
        ClanVerliesPlan q = eingeladen();
        q.gedankt();
        assertFalse(q.antwortPruefenJetzt(100, true), "Mitspieler weg: nichts mehr");
    }

    @Test
    void mitspielerWegImChatText() {
        assertTrue(ClanVerliesPlan.mitspielerWegImText(List.of("Welcome to the joint journey chat.",
                "The other player left the Dungeon. You can end the journey and", "start a new one.")));
        assertTrue(ClanVerliesPlan.mitspielerWegImText(List.of("Your ally left the Dungeon")));
        assertFalse(ClanVerliesPlan.mitspielerWegImText(List.of("you", "Thanks", "aid", "np!")));
        assertFalse(ClanVerliesPlan.mitspielerWegImText(null));
    }

    @Test
    void antwortDesMitspielersUnterDemDank() {
        assertEquals(List.of("aid", "np, good luck with the event!"), ClanVerliesPlan.neuNach(List.of(
                "Welcome to the joint journey chat.", "Please be polite to and respectful of other players. Offensive",
                "behavior is prohibited.", "you", "Thanks", "aid", "np, good luck with the event!"), VerliesClan.DANKE));
        assertTrue(ClanVerliesPlan.neuNach(List.of("Welcome to the joint journey chat.", "you", "Thanks"),
                VerliesClan.DANKE).isEmpty());
        assertTrue(ClanVerliesPlan.neuNach(List.of("aid", "hi"), VerliesClan.DANKE).isEmpty(), "ohne eigenes Thanks nichts");
        assertTrue(ClanVerliesPlan.neuNach(null, VerliesClan.DANKE).isEmpty());
    }
}
