package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event-Quest "Go to a Dungeon with a clan mate" (18.11.): Texte mit Rundenzahl, "x/34 online" lesen,
 * der Mitspieler-Weg "clan" -- und random/friend bleiben wie sie waren.
 */
class VerliesClanTest {

    @Test
    void clanChatTextMitEinerRunde() {
        // Autor 19.11.: mit Quest-Bezug ("event quest"), und dass gleich ueber "Clan" eingeladen wird
        assertEquals("Anyone up for 1 round of Dungeon with me for the event quest (play with a clan mate)? "
                + "Inviting now via Clan, thanks!", VerliesClan.clanChatText(1));
    }

    @Test
    void clanChatTextMitMehrerenRunden() {
        String t = VerliesClan.clanChatText(3);
        assertTrue(t.contains("Anyone up for 3 rounds of Dungeon"), t);
        assertTrue(t.contains("event quest"), t);
        assertFalse(t.contains("1 round"), t);
        // 0 oder weniger gibt es nicht -- mindestens 1 Runde
        assertTrue(VerliesClan.clanChatText(0).contains("1 round of"));
    }

    @Test
    void verliesNachrichtMitRundenzahl() {
        assertEquals("Clan mate quest - 1 round, thanks!", VerliesClan.verliesText(1));
        assertEquals("Clan mate quest - 2 rounds, thanks!", VerliesClan.verliesText(2));
    }

    @Test
    void texteSindReinesAscii() {
        for (String t : List.of(VerliesClan.clanChatText(1), VerliesClan.clanChatText(5), VerliesClan.verliesText(4),
                VerliesClan.DANKE, VerliesClan.ABSCHIED)) {
            assertTrue(t.chars().allMatch(c -> c >= 32 && c < 127), t);
        }
        assertEquals("Haha thanks again! See you around :)", VerliesClan.ABSCHIED);
    }

    @Test
    void onlineZeileWirdGelesen() {
        assertEquals(8, VerliesClan.onlineAus(List.of("MeinClan", "8/34 online")));
        assertEquals(0, VerliesClan.onlineAus(List.of("0/34 online")));
        assertEquals(12, VerliesClan.onlineAus(List.of("12 / 34   online")));
        assertEquals(0, VerliesClan.onlineAus(List.of("O/34 online")), "Texterkennung liest 0 als O");
        assertEquals(5, VerliesClan.onlineAus(List.of("5/34")));
    }

    @Test
    void unlesbareOnlineZeileGibtNull() {
        assertNull(VerliesClan.onlineAus(null));
        assertNull(VerliesClan.onlineAus(List.of()));
        assertNull(VerliesClan.onlineAus(List.of("MeinClan", "online")));
        assertNull(VerliesClan.onlineAus(List.of("40/34 online")), "mehr online als Mitglieder = falsch gelesen");
        assertNull(VerliesClan.onlineAus(List.of("3/0")));
    }

    @Test
    void wegClanWirdErkannt() {
        assertEquals(DungeonMode.Teammate.CLAN, DungeonMode.teammateAus("clan"));
        assertEquals(DungeonMode.Teammate.CLAN, DungeonMode.teammateAus(" CLAN "));
    }

    @Test
    void randomUndFriendUnveraendert() {
        assertEquals(DungeonMode.Teammate.RANDOM, DungeonMode.teammateAus("random"));
        assertEquals(DungeonMode.Teammate.RANDOM, DungeonMode.teammateAus(null));
        assertEquals(DungeonMode.Teammate.RANDOM, DungeonMode.teammateAus(""));
        assertEquals(DungeonMode.Teammate.RANDOM, DungeonMode.teammateAus("c"));
        assertEquals(DungeonMode.Teammate.FRIEND, DungeonMode.teammateAus("friend"));
        assertEquals(DungeonMode.Teammate.FRIEND, DungeonMode.teammateAus("friendwait"));
        assertEquals(DungeonMode.Teammate.FRIEND, DungeonMode.teammateAus("Friend"));
    }

    @Test
    void pflichtbilderMitClanWieZufaellig() {
        DungeonMode d = new DungeonMode();
        d.setTeammate(DungeonMode.Teammate.RANDOM, "", false);
        List<String> zufall = d.requiredTemplates();
        d.setTeammate(DungeonMode.Teammate.CLAN, "", false);
        assertEquals(zufall, d.requiredTemplates(), "Clan braucht keine Pflichtbilder mehr (feste Stellen als Ersatz)");
        assertEquals("", d.freund());
        d.setTeammate(DungeonMode.Teammate.FRIEND, "Lisa", false);
        assertTrue(d.requiredTemplates().contains("teammate_friend"));
        assertTrue(d.requiredTemplates().contains("friend_lisa"));
        assertEquals("Lisa", d.freund());
        assertTrue(DungeonMode.optionalTemplates().contains(VerliesClan.T_CLAN));
    }

    @Test
    void antwortImChatWirdAnDerAenderungErkannt() {
        BufferedImage a = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        BufferedImage b = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(50, 50, 200, 60);   // neue Nachricht
        g.dispose();
        assertFalse(VerliesClan.bereichGeaendert(a, a, new int[]{0, 0, 400, 300}));
        assertTrue(VerliesClan.bereichGeaendert(a, b, new int[]{0, 0, 300, 150}));
        assertFalse(VerliesClan.bereichGeaendert(a, b, new int[]{0, 200, 400, 100}), "Aenderung ausserhalb zaehlt nicht");
    }
}
