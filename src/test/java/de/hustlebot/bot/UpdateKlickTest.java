package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bericht #ETD4 (28.10. 05:02): Nach "Update" laedt das Spiel ~20 s neu. Die Kathedrale drueckt den Knopf ueber ihren
 * eigenen Klick -- die Zeit wurde nicht gemerkt, der Motor meldete einen Haenger. Jetzt merkt jeder Klick auf
 * "update_button" die Zeit (BotEngine.haengerPruefen schweigt dann 45 s), jeder andere Klick nicht.
 */
class UpdateKlickTest {

    @Test
    void nurUpdateKnopfMerktSichDieZeit() throws Exception {
        long vorher = Stoerfenster.updateGedruecktUm();
        Thread.sleep(5);
        Stoerfenster.klickGemacht("close_x");
        Stoerfenster.klickGemacht("home_button");
        Stoerfenster.klickGemacht(null);
        assertEquals(vorher, Stoerfenster.updateGedruecktUm(), "andere Knoepfe aendern nichts");

        long jetzt = System.currentTimeMillis();
        Stoerfenster.klickGemacht("update_button");
        long danach = Stoerfenster.updateGedruecktUm();
        assertTrue(danach >= jetzt && danach <= System.currentTimeMillis(), "Update-Klick gemerkt");
        assertTrue(System.currentTimeMillis() - danach < 45_000, "Haenger-Pruefung schweigt jetzt");
    }
}
