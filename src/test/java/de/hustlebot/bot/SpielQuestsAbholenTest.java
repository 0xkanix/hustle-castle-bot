package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 21.11.: Abholen per Farbe, rotes X per Farbe, Werbung am PC fuer den Tag aus. */
class SpielQuestsAbholenTest {

    private static BufferedImage fenster() {
        BufferedImage b = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(20, 30, 70));
        g.fillRect(0, 0, 1159, 770);
        g.dispose();
        return b;
    }

    @Test
    void gruenerKnopfWirdGefunden() {
        BufferedImage b = fenster();
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(100, 200, 20));
        g.fillRoundRect(780, 300, 200, 70, 20, 20);
        g.setColor(Color.WHITE);
        g.fillRect(830, 325, 90, 15);
        // ein kleines gruenes "+" in der Kopfzeile und ein Haken darf nicht zaehlen
        g.setColor(new Color(100, 200, 20));
        g.fillRoundRect(690, 14, 36, 36, 8, 8);
        g.fillRect(100, 500, 30, 30);
        g.dispose();
        List<int[]> k = SpielQuestsMode.gruenerKnoepfe(b);
        assertEquals(1, k.size());
        assertTrue(Math.abs(k.get(0)[0] - 880) <= 4 && Math.abs(k.get(0)[1] - 335) <= 4, java.util.Arrays.toString(k.get(0)));
    }

    @Test
    void rotesXObenRechtsWirdGefunden() {
        BufferedImage b = fenster();
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(255, 94, 66));
        g.fillOval(1095, 10, 50, 50);
        g.setColor(Color.WHITE);
        g.fillRect(1112, 33, 16, 5);
        // roter Streifen links darf nicht zaehlen
        g.setColor(new Color(255, 94, 66));
        g.fillRect(300, 10, 80, 12);
        g.dispose();
        int[] x = SpielQuestsMode.roterKreisObenRechts(b);
        assertNotNull(x);
        assertTrue(Math.abs(x[0] - 1120) <= 3 && Math.abs(x[1] - 35) <= 3, x[0] + "," + x[1]);
        assertNull(SpielQuestsMode.roterKreisObenRechts(fenster()));
    }

    @Test
    void claimTextUnterscheidetKnoepfe() {
        assertTrue(SpielQuestsMode.istClaimText("Claim reward"));
        assertTrue(SpielQuestsMode.istClaimText("Claim"));
        assertTrue(SpielQuestsMode.istClaimText(""));
        assertFalse(SpielQuestsMode.istClaimText("Show"));
        assertFalse(SpielQuestsMode.istClaimText("Go"));
        assertFalse(SpielQuestsMode.istClaimText("Watch"));
    }

    @Test
    void werbungAusBisZumNaechstenReset() {
        SpielQuests.werbungAusLoeschen();
        LocalDateTime nachts = LocalDateTime.of(2026, 11, 21, 3, 30);
        assertFalse(SpielQuests.werbungAus(nachts, true));
        SpielQuests.werbungAus(nachts);
        assertTrue(SpielQuests.werbungAus(nachts.plusHours(5), true));
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 5), SpielQuests.werbungAusBerechnen(nachts));
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 5), SpielQuests.werbungAusBerechnen(nachts.withHour(12)));
        assertFalse(SpielQuests.werbungAus(LocalDateTime.of(2026, 11, 21, 10, 6), true));
        SpielQuests.werbungAusLoeschen();
    }
}
