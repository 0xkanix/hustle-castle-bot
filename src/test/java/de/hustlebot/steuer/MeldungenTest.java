package de.hustlebot.steuer;

import de.hustlebot.discord.DiscordClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ohne Discord-Zugang muss trotzdem jede Meldung im Steuerprogramm ankommen
 * (Auftrag 1, 11.10.: "Ohne Discord-Token muss alles laufen").
 */
class MeldungenTest {

    @Test
    void ohneDiscord_kommenKartenTrotzdemAn() {
        DiscordClient client = new DiscordClient();
        client.configure(false, "", "", "");   // kein Token, kein Webhook
        assertFalse(client.isEnabled());

        Meldungen m = new Meldungen();
        client.setMithoerer(m);
        List<Meldung> gehoert = new ArrayList<>();
        m.zuhoeren(gehoert::add);

        client.sendCard("Bot bereit", "Die **Konsole** ist da.", DiscordClient.FARBE_INFO);
        client.send("Unbekannter Befehl.");
        client.sendCardMitKnoepfen("123", "Bot beenden?", "Wirklich?", DiscordClient.FARBE_WARNUNG,
                List.of(new String[]{"Ja", "!beenden"}, new String[]{"Nein", "!status"}));

        assertEquals(3, m.anzahl());
        assertEquals(3, gehoert.size());

        Meldung erste = gehoert.get(0);
        assertEquals("Bot bereit", erste.titel());
        assertEquals("Die Konsole ist da.", erste.textLesbar());
        assertFalse(erste.mitKnoepfen());

        Meldung dritte = gehoert.get(2);
        assertTrue(dritte.mitKnoepfen());
        assertEquals(2, dritte.knoepfe().size());
        assertEquals("!beenden", dritte.knoepfe().get(0)[1]);
        assertEquals("123", dritte.kanal());
    }

    @Test
    void letzteMit_findetDieNeuesteKarteZumTitel() {
        Meldungen m = new Meldungen();
        m.eigene("Burg-Stand", "Gold 1");
        m.eigene("Zustand: Bereit", "x");
        m.eigene("Castle status", "Gold 2");

        assertEquals("Gold 2", m.letzteMit("Burg-Stand", "Castle status").text());
        assertEquals("Zustand: Bereit", m.letzteMit("zustand").titel());
        assertNull(m.letzteMit("gibt es nicht"));
    }

    @Test
    void speicherBleibtBegrenzt() {
        Meldungen m = new Meldungen();
        for (int i = 0; i < 1000; i++) m.eigene("T" + i, "");
        assertEquals(400, m.anzahl());
        assertEquals("T999", m.alle().get(m.anzahl() - 1).titel());
    }

    @Test
    void einKlemmenderZuhoererStoertDieAnderenNicht() {
        Meldungen m = new Meldungen();
        m.zuhoeren(x -> { throw new IllegalStateException("klemmt"); });
        List<Meldung> ok = new ArrayList<>();
        m.zuhoeren(ok::add);
        m.eigene("Test", "");
        assertEquals(1, ok.size());
    }
}
