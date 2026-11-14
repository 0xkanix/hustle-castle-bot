package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F36): Warnungen in Alltagssprache -- erkannt in beiden Sprachen, nie etwas Falsches behauptet.
 */
class WarnhilfeTest {

    @Test
    void bekannteWarnungenWerdenInBeidenSprachenErkannt() {
        String[] zeilen = {
                "Template larger than the screen: ar_me (template 300x40, screen 200x100)",
                "Vorlage groesser als der Bildschirm: ar_me (Vorlage 300x40, Bild 200x100)",
                "Nothing recognized for 12 frames. Last progress: x 40 seconds ago.",
                "Seit 24 Bildern nichts erkannt. Letzter Fortschritt: x vor 40 Sekunden.",
                "No progress for 120 s (mode cathedral, last: x)",
                "Seit 120 s kein Fortschritt (Modus cathedral, zuletzt: x)",
                "The game window cannot be brought to the front -- another window lies over it.",
                "Das Spielfenster laesst sich nicht nach vorne holen -- ein anderes Fenster liegt darueber.",
                "There are no images in the templates folder. Without them the bot sees nothing -- please unpack the ZIP again.",
                "Im Ordner templates liegen keine Bilder. Ohne sie sieht der Bot nichts.",
                "The game crashed during the Dungeon -- this run does not count.",
                "Das Spiel ist waehrend des Verlieses abgestuerzt -- der Lauf zaehlt nicht.",
                "No partner found for 90 seconds. The search is cancelled and restarted (restart 2).",
                "Seit 90 Sekunden kein Mitspieler gefunden. Die Suche wird abgebrochen und neu gestartet (Neustart 2).",
                "Discord is off -- bot token or channel number are missing in config.properties.",
                "The computer was off in between. Last heartbeat: 03:00",
                "Der Rechner war zwischendurch aus. Letztes Lebenszeichen: 03:00",
                "Name used twice: confirm. Keeping the newer capture",
                "Unknown template requested: abc",
                "Game window inside 1157x770 instead of 1159x770 (Windows scaling or design?)",
                "Spielfenster innen 1157x770 statt 1159x770 (Windows-Skalierung oder Design?)",
                "Text recognition failed: boom",
                "The notebook could not be written: access denied",
                "Battle lost. Battles lost in total: 3"};
        for (String z : zeilen) {
            Warnhilfe.Hilfe h = Warnhilfe.finde(z);
            assertNotNull(h, z);
            assertFalse(h.bedeutung().isBlank() || h.loesung().isBlank(), z);
        }
    }

    @Test
    void unbekanntesBleibtUnbekanntUndSagtEsEhrlich() {
        assertNull(Warnhilfe.finde("Kathedrale: Etage 3 fertig"));
        assertNull(Warnhilfe.finde(""));
        assertNull(Warnhilfe.finde(null));
        String html = Warnhilfe.alsHtml("Etwas Seltsames", null);
        assertTrue(html.contains("Etwas Seltsames"));
        assertTrue(html.contains("Support"), "verweist auf den Fehlerbericht");
    }

    @Test
    void htmlEnthaeltMeldungBedeutungUndLoesung() {
        var h = Warnhilfe.finde("Template larger than the screen: <x>");
        String html = Warnhilfe.alsHtml("Template larger than the screen: <x>", h);
        assertTrue(html.contains("&lt;x&gt;"), "html-Zeichen werden entschaerft");
        assertTrue(html.contains(h.bedeutung()));
        assertTrue(html.contains(h.loesung()));
        assertTrue(html.length() < 3000);
    }
}
