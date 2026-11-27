package de.hustlebot.discord;

import de.hustlebot.bot.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 18.11.: "!start dungeon 1 clan" bzw. der Folge-Auftrag new GameMode.Folge("dungeon", 1, "clan")
 * kommen beim Motor als Mitspieler-Wort "clan" an. random/friend bleiben wie bisher.
 */
class VerliesClanWegTest {

    private static DiscordBridge.Auftrag auftrag(String text) {
        DiscordBridge.Eintragstext e = DiscordBridge.eintragLesen(text);
        assertNull(e.fehler(), text);
        return new DiscordBridge.Auftrag(e.modus(), e.runs(), e.weg(), e.freund().freund(), e.freund().warten(),
                true, e.plan(), e.bezahl(), e.kreislauf());
    }

    @Test
    void startMitClanWirdClanWeg() {
        DiscordBridge.Auftrag a = auftrag("!start dungeon 1 clan");
        assertEquals("dungeon", a.modus());
        assertEquals(1, a.runs());
        assertEquals("clan", a.weg());
        assertEquals("", a.freund(), "clan ist kein Freundesname");
        assertEquals("clan", DiscordBridge.mitspielerWort(a));
        assertEquals("clan", DiscordBridge.mitspielerWort(auftrag("dungeon 3 clanmate")));
        assertEquals(3, auftrag("dungeon 3 clanmate").runs());
    }

    @Test
    void folgeAuftragMitClanKommtAlsClanAn() {
        GameMode.Folge f = new GameMode.Folge("dungeon", 1, "clan");
        // so legt die Bruecke Folge-Auftraege an (zustandPruefen)
        DiscordBridge.Auftrag a = new DiscordBridge.Auftrag(f.modus(), f.runs(), f.wegOderC(), "", false);
        assertEquals("clan", DiscordBridge.mitspielerWort(a));
    }

    @Test
    void zufaelligUndFreundUnveraendert() {
        assertEquals("random", DiscordBridge.mitspielerWort(auftrag("dungeon 10")));
        assertEquals("random", DiscordBridge.mitspielerWort(auftrag("dungeon 10 random")));
        assertEquals("friend", DiscordBridge.mitspielerWort(auftrag("dungeon 10 friend Lisa")));
        assertEquals("friendwait", DiscordBridge.mitspielerWort(auftrag("dungeon 10 friend Lisa wait")));
        DiscordBridge.Auftrag ohneWort = auftrag("dungeon 10 lisa");
        assertEquals("lisa", ohneWort.freund());
        assertEquals("friend", DiscordBridge.mitspielerWort(ohneWort));
        // Folge-Auftrag ohne Weg (Kathedrale -> Verlies): wie immer "c", zufaellig
        GameMode.Folge f = new GameMode.Folge("dungeon", 5);
        assertEquals("random", DiscordBridge.mitspielerWort(new DiscordBridge.Auftrag(f.modus(), f.runs(), f.wegOderC(), "", false)));
    }

    @Test
    void clanGiltNurImVerlies() {
        DiscordBridge.Auftrag k = new DiscordBridge.Auftrag("cathedral", 3, "clan", "", false);
        assertEquals("random", DiscordBridge.mitspielerWort(k));
        assertEquals("c", DiscordBridge.weg("cathedral 3 c"));
        assertEquals("b", DiscordBridge.weg("cathedral 3 b"));
        assertEquals("a", DiscordBridge.weg("dungeon 3"));
        assertEquals("clanwar", auftrag("clanwar").modus());
    }
}
