package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import de.hustlebot.core.ScreenCapture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.Image;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clan-Verlies (Autor 19.11. 20:34-20:54, Bilder in src/test/resources/verlies-clan): Fortschrittsbalken der
 * Verlieskarte, rotes Abzeichen am Chat-Pfeil, aktiver Verlies-Reiter im Chat und die rote Zeile "The other player left
 * the Dungeon". Die Testbilder sind Ausschnitte, die wieder an ihre Stelle in ein leeres 1159x770-Spielbild gesetzt werden.
 */
class VerliesClanBildTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Ein Testbild aus src/test/resources/verlies-clan als RGB-Bild. */
    private static BufferedImage bild(String name) throws Exception {
        try (var in = VerliesClanBildTest.class.getResourceAsStream("/verlies-clan/" + name + ".png")) {
            assertNotNull(in, name);
            BufferedImage b = javax.imageio.ImageIO.read(in);
            BufferedImage rgb = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(b, 0, 0, null);
            return rgb;
        }
    }

    /** Der echte Vorlagen-Ordner templates\ des Projekts (oberhalb von target\test-classes). */
    private static Matcher matcher() throws Exception {
        java.nio.file.Path ziel = java.nio.file.Path.of(VerliesClanBildTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        return new Matcher(ziel.getParent().getParent().resolve("templates"));
    }

    private static BufferedImage spielbild(String name, int x, int y) throws Exception {
        BufferedImage teil = bild(name);
        BufferedImage b = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        b.getGraphics().drawImage(teil, x, y, null);
        return b;
    }

    private static BufferedImage skaliert(BufferedImage b, int w, int h) {
        BufferedImage s = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        s.getGraphics().drawImage(b.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        return s;
    }

    @Test
    void fortschrittAusDemBalken() throws Exception {
        assertEquals(0, VerliesClan.fortschrittProzent(spielbild("verlies-balken-0", 400, 40)));
        int p43 = VerliesClan.fortschrittProzent(spielbild("verlies-balken-43", 400, 40));
        assertTrue(Math.abs(p43 - 43) <= 2, "43 % gelesen als " + p43);
        BufferedImage b86 = spielbild("verlies-balken-86", 400, 40);
        int p86 = VerliesClan.fortschrittProzent(b86);
        assertTrue(Math.abs(p86 - 86) <= 2, "86 % gelesen als " + p86);
        assertTrue(p86 >= ClanVerliesPlan.DANK_AB_PROZENT, "bei 86 % ist nur noch ein Stuetzpunkt offen -> Thanks");
        assertTrue(p43 < ClanVerliesPlan.DANK_AB_PROZENT);
        int groesser = VerliesClan.fortschrittProzent(skaliert(b86, 1280, 850));
        assertTrue(Math.abs(groesser - 86) <= 3, "anderes Fenstermass: " + groesser);
    }

    @Test
    void fortschrittUnlesbarBeiOffenemChat() throws Exception {
        assertEquals(-1, VerliesClan.fortschrittProzent(spielbild("verlies-balken-chat", 400, 40)));
        BufferedImage weiss = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        weiss.getGraphics().fillRect(0, 0, 1159, 770);
        assertEquals(-1, VerliesClan.fortschrittProzent(weiss));
        assertEquals(-1, VerliesClan.fortschrittProzent(null));
    }

    @Test
    void abzeichenAmChatPfeil() throws Exception {
        assertTrue(VerliesClan.abzeichenAmChatPfeil(spielbild("verlies-abzeichen-mit", 0, 295)), "rote 2 am Pfeil (20:34:41)");
        assertFalse(VerliesClan.abzeichenAmChatPfeil(spielbild("verlies-abzeichen-ohne", 0, 295)), "Wartefenster 20:36:42");
        assertFalse(VerliesClan.abzeichenAmChatPfeil(new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void nurDerAktiveVerliesReiterGilt() throws Exception {
        assertTrue(VerliesClan.reiseReiterAktiv(spielbild("verlies-reiter-reise", 380, 0)), "Verlies-Reiter aktiv (20:52:40)");
        assertFalse(VerliesClan.reiseReiterAktiv(spielbild("verlies-reiter-clan", 380, 0)), "Clan-Reiter aktiv im Verlies");
        assertFalse(VerliesClan.reiseReiterAktiv(spielbild("verlies-reiter-burg", 380, 0)), "Clan-Chat im Wartefenster");
    }

    @Test
    void vorlagenFuerDenVerliesChat() throws Exception {
        Matcher m = matcher();
        m.loadAll();
        assertTrue(m.has(VerliesClan.T_REISECHAT_WEG));
        assertTrue(m.has(VerliesClan.T_REISECHAT_REITER));
        for (String n : new String[]{"verlies-reisechat-weg-unten", "verlies-reisechat-weg-ganz-unten"}) {
            Mat b = ScreenCapture.toMat(spielbild(n, 40, 540));
            assertTrue(m.findImBereich(b, VerliesClan.T_REISECHAT_WEG, VerliesClan.CHAT_BEREICH, Matcher.DEFAULT_THRESHOLD)
                    .isPresent(), n);
        }
        Mat ohne = ScreenCapture.toMat(spielbild("verlies-reisechat-ohne-weg", 40, 540));
        assertTrue(m.findImBereich(ohne, VerliesClan.T_REISECHAT_WEG, VerliesClan.CHAT_BEREICH, Matcher.DEFAULT_THRESHOLD)
                .isEmpty(), "Clan-Chat ohne rote Zeile");
        Mat reise = ScreenCapture.toMat(spielbild("verlies-reiter-reise", 380, 0));
        assertTrue(m.findImBereich(reise, VerliesClan.T_REISECHAT_REITER, VerliesClan.REITER_BEREICH, Matcher.DEFAULT_THRESHOLD)
                .isPresent());
    }

    /**
     * Clan-Thanks, wenn der Mitspieler schon weg ist (Autor 19.11. 21:25): im Chat auf der Verlieskarte muss der Reiter
     * "Clan" (Wappen mit Drache, 218,32) sicher aktiv sein -- Bilder 20:51:36 (Clan aktiv) und 20:52:40 (Verlies aktiv).
     */
    @Test
    void clanReiterAufDerVerlieskarte() throws Exception {
        BufferedImage clan = spielbild("verlies-reiter-clan-aktiv", 150, 0);
        assertTrue(VerliesClan.clanReiterImVerliesAktiv(clan), "Clan-Reiter aktiv (20:51:36)");
        assertFalse(VerliesClan.reiseReiterAktiv(clan));
        BufferedImage reise = spielbild("verlies-reiter-reise-aktiv", 150, 0);
        assertFalse(VerliesClan.clanReiterImVerliesAktiv(reise), "Verlies-Reiter aktiv (20:52:40)");
        assertTrue(VerliesClan.reiseReiterAktiv(reise));
        assertFalse(VerliesClan.clanReiterImVerliesAktiv(spielbild("verlies-reiter-burg", 380, 0)));
        assertFalse(VerliesClan.clanReiterImVerliesAktiv(new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB)));
        assertFalse(VerliesClan.clanReiterImVerliesAktiv(null));
        assertTrue(VerliesClan.clanReiterImVerliesAktiv(skaliert(clan, 1280, 850)), "anderes Fenstermass");
    }

    /** "Waiting for a clan mate" (18.11. 21:15:05) muss als Mitspielersuche gelten -- nur dann laeuft das Warten. */
    @Test
    void wartefensterWirdAlsSucheErkannt() throws Exception {
        Matcher m = matcher();
        m.loadAll();
        BufferedImage b;
        try (var in = VerliesClanBildTest.class.getResourceAsStream("/verlies-clan/wartefenster-211505.jpg")) {
            assertNotNull(in);
            BufferedImage roh = javax.imageio.ImageIO.read(in);
            b = new BufferedImage(roh.getWidth(), roh.getHeight(), BufferedImage.TYPE_INT_RGB);
            b.getGraphics().drawImage(roh, 0, 0, null);
        }
        assertTrue(m.find(ScreenCapture.toMat(b), "cancel_search").isPresent(), "Cancel im Wartefenster nicht erkannt");
        assertFalse(VerliesClan.reiseReiterAktiv(b), "im Wartefenster gibt es keinen Verlies-Reiter");
        assertFalse(VerliesClan.clanReiterImVerliesAktiv(b));
    }
}
