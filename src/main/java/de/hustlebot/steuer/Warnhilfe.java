package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Autor 24.10. (Ideenliste F36): Warnungen in Alltagssprache. "Template larger than the screen" oder "Nothing recognized for
 * 12 frames" sagen Freunden nichts -- neben der Meldung steht jetzt "Was heisst das?" mit einem Satz zur Bedeutung und einem
 * zur Loesung ("Vorlage fehlt: nimm das Bild mit dem Aufnahme-Programm auf").
 *
 * Erkannt wird am Text der Meldung, in beiden Sprachen (das Protokoll steht in der Sprache, die beim Schreiben eingestellt
 * war). Nur gut bekannte Faelle sind drin; bei allem anderen sagt das Programm ehrlich, dass es dazu keinen Hinweis hat --
 * und verweist auf den Fehlerbericht. Benutzt vom Protokoll-Fenster (Knopf, Doppelklick auf eine Zeile) und von den
 * Meldungs-Karten.
 */
public final class Warnhilfe {

    private Warnhilfe() {
    }

    /** Was die Meldung bedeutet und was man tun kann -- schon in der Sprache des Programms. */
    public record Hilfe(String bedeutung, String loesung) {
    }

    private record Regel(Pattern muster, String bedeutungEn, String bedeutungDe, String loesungEn, String loesungDe) {
    }

    private static Regel r(String muster, String bedeutungEn, String bedeutungDe, String loesungEn, String loesungDe) {
        return new Regel(Pattern.compile("(?is).*(?:" + muster + ").*"), bedeutungEn, bedeutungDe, loesungEn, loesungDe);
    }

    private static final List<Regel> REGELN = List.of(
            r("Template larger than the screen|Vorlage gr(?:o|ö|oe)(?:ss|ß)er als der Bildschirm",
                    "A picture the bot looks for is bigger than the picture of the game it got.",
                    "Ein Bild, das der Bot sucht, ist größer als das Spielbild, das er bekommen hat.",
                    "Usually the game window has the wrong size: set Windows scaling to 100 % (Settings > System > Display), restart the game. "
                            + "If it is one of your own pictures: capture it again with the capture program (Tools tab) -- \"Check all my pictures\" shows which ones do not fit.",
                    "Meistens hat das Spielfenster die falsche Größe: Windows-Skalierung auf 100 % stellen (Einstellungen > System > Anzeige) und das Spiel neu starten. "
                            + "Ist es eines deiner eigenen Bilder: mit dem Aufnahme-Programm (Reiter Werkzeuge) noch einmal aufnehmen -- „Alle meine Bilder prüfen“ zeigt, welche nicht passen."),
            r("Nothing recognized for \\d+ frames|Seit \\d+ Bildern nichts erkannt",
                    "The bot sees a screen it does not know -- often a window or pop-up it has no picture for.",
                    "Der Bot sieht einen Bildschirm, den er nicht kennt -- oft ein Fenster oder Pop-up, für das er kein Bild hat.",
                    "Look at the game and close the window by hand. Check: game language English, HD Graphics Off, V Sync Off. "
                            + "If it keeps happening: Support tab > \"Save error report (ZIP)\".",
                    "Schau ins Spiel und schließ das Fenster von Hand. Prüf: Spielsprache English, HD Graphics Off, V Sync Off. "
                            + "Wenn es immer wieder passiert: Reiter Support > „Fehlerbericht speichern (ZIP)“."),
            r("No progress for|kein Fortschritt",
                    "The bot did not get any further for a while -- it is stuck.",
                    "Der Bot ist eine Weile nicht weitergekommen -- er hängt.",
                    "Usually a window is in the way. The bot tries to get out by itself; if it does not, close the window in the game by hand. "
                            + "If it repeats: save an error report (Support tab).",
                    "Meist steht ein Fenster im Weg. Der Bot versucht von selbst herauszukommen; klappt das nicht, schließ das Fenster im Spiel von Hand. "
                            + "Wiederholt es sich: Fehlerbericht speichern (Reiter Support)."),
            r("cannot be brought to the front|nicht nach vorne holen",
                    "Another window lies over the game, so the bot does not see the game.",
                    "Ein anderes Fenster liegt über dem Spiel, darum sieht der Bot das Spiel nicht.",
                    "Close or minimise other windows (browser, chat, recording tools) and do not click in them while the bot plays.",
                    "Andere Fenster schließen oder verkleinern (Browser, Chat, Aufnahme-Programme) und nicht hineinklicken, solange der Bot spielt."),
            r("No window area set|Kein Fensterbereich gesetzt",
                    "The bot does not know where the game window is.",
                    "Der Bot weiß nicht, wo das Spielfenster ist.",
                    "Start Hustle Castle first, then the bot -- and do not minimise the game.",
                    "Erst Hustle Castle starten, dann den Bot -- und das Spiel nicht minimieren."),
            r("no images in the templates folder|keine Bilder\\. Ohne sie",
                    "The pictures the bot needs are missing, so it sees nothing.",
                    "Die Bilder, die der Bot braucht, fehlen -- er sieht darum nichts.",
                    "Run the update again (or unpack the ZIP again) and do not move or rename the folder \"templates\".",
                    "Das Update noch einmal ausführen (oder das ZIP neu entpacken) und den Ordner „templates“ nicht verschieben oder umbenennen."),
            r("game crashed|Spiel ist w(?:ae|ä)hrend|abgest(?:ue|ü)rzt",
                    "The game closed by itself (crash).",
                    "Das Spiel hat sich von selbst beendet (Absturz).",
                    "The bot restarts it. If it happens often: restart the PC, close other programs, update the graphics driver.",
                    "Der Bot startet es neu. Passiert es oft: den PC neu starten, andere Programme schließen, den Grafiktreiber aktualisieren."),
            r("No partner found|kein Mitspieler gefunden",
                    "No dungeon partner appeared in time.",
                    "Es ist kein Mitspieler fürs Verlies gekommen.",
                    "The bot cancels and searches again by itself. If you play with a friend, enter the name in the Start card (field \"Friend\").",
                    "Der Bot bricht ab und sucht von selbst neu. Spielst du mit einem Freund, trag den Namen in der Karte Starten ein (Feld „Mitspieler“)."),
            r("Discord is off|Discord ist aus",
                    "Discord is not set up. That is fine -- everything works in this program too.",
                    "Discord ist nicht eingerichtet. Das ist in Ordnung -- alles geht auch in diesem Programm.",
                    "Only needed if you want to control the bot from your phone: Settings tab > Discord > \"Open guide\".",
                    "Nur nötig, wenn du den Bot vom Handy aus steuern willst: Reiter Einstellungen > Discord > „Anleitung öffnen“."),
            r("config\\.properties not found",
                    "The file with your Discord data is missing; the defaults apply.",
                    "Die Datei mit deinen Discord-Daten fehlt; es gelten die Standardwerte.",
                    "Only Discord is affected. Copy config.properties back into the bot folder (from your backup or the package).",
                    "Betroffen ist nur Discord. Kopier config.properties wieder in den Bot-Ordner (aus deiner Sicherung oder dem Paket)."),
            r("computer was off in between|Rechner war zwischendurch aus",
                    "The PC was off or restarted in between (power cut, Windows update or sleep mode).",
                    "Der PC war zwischendurch aus oder wurde neu gestartet (Stromausfall, Windows-Update oder Ruhezustand).",
                    "Switch off sleep mode and automatic restarts for updates while the bot plays; Settings tab > Autostart on starts the bot again by itself.",
                    "Ruhezustand und automatische Neustarts für Updates abschalten, solange der Bot spielt; Reiter Einstellungen > Autostart an startet den Bot von selbst wieder."),
            r("Name used twice|Name doppelt vergeben",
                    "Two pictures have the same name; the bot uses the newer one.",
                    "Zwei Bilder haben denselben Namen; der Bot nimmt das neuere.",
                    "Normal after capturing a picture again. If it was not meant: remove the old one in \"My pictures\" (capture program > Advanced > My pictures).",
                    "Normal, wenn du ein Bild neu aufgenommen hast. War es nicht gewollt: das alte unter „My pictures“ entfernen (Aufnahme-Programm > Erweitert > Meine Bilder)."),
            r("Unknown template requested|Unbekannte Vorlage angefragt",
                    "The bot looked for a picture that is not in your folder.",
                    "Der Bot hat ein Bild gesucht, das es in deinem Ordner nicht gibt.",
                    "Run the update again so all pictures are there. If it is a picture of your own: capture it with the capture program.",
                    "Das Update noch einmal ausführen, damit alle Bilder da sind. Ist es ein eigenes Bild: mit dem Aufnahme-Programm aufnehmen."),
            r("Button positions not readable|Knopfstellen nicht lesbar",
                    "The bot's memory of where the buttons are was damaged.",
                    "Das Gedächtnis des Bots für die Knopf-Stellen war beschädigt.",
                    "Nothing to do -- the bot learns the positions again by itself.",
                    "Nichts zu tun -- der Bot lernt die Stellen von selbst neu."),
            r("File could not be read|Datei konnte nicht gelesen werden",
                    "A picture file is damaged or not a picture.",
                    "Eine Bilddatei ist beschädigt oder gar kein Bild.",
                    "Capture the picture again, or remove the file in \"My pictures\".",
                    "Das Bild noch einmal aufnehmen oder die Datei unter „My pictures“ entfernen."),
            r("Game window inside .* instead of|Spielfenster innen .* statt",
                    "The game window has another size than the bot expects (usually because of Windows scaling).",
                    "Das Spielfenster hat eine andere Größe, als der Bot erwartet (meist wegen der Windows-Skalierung).",
                    "Set Windows scaling to 100 % (Settings > System > Display > Scale) and restart the game. The PC check (Tools tab) tells you.",
                    "Windows-Skalierung auf 100 % stellen (Einstellungen > System > Anzeige > Skalierung) und das Spiel neu starten. Der PC-Check (Reiter Werkzeuge) sagt es dir."),
            r("Not in the castle|Nicht in der Burg",
                    "The bot expected the castle screen but saw something else.",
                    "Der Bot hat die Burg erwartet und etwas anderes gesehen.",
                    "Go back to the castle in the game and start again.",
                    "Geh im Spiel zurück in die Burg und starte noch einmal."),
            r("No reply from the bot|Keine Antwort vom Bot",
                    "The bot did not answer this button in time -- it is probably busy.",
                    "Der Bot hat auf diesen Knopf nicht rechtzeitig geantwortet -- er ist wohl gerade beschäftigt.",
                    "Wait a moment and press again.",
                    "Einen Moment warten und noch einmal drücken."),
            r("Text recognition|text recognition|Texterkennung|texterkennung\\.ps1",
                    "Reading text from the game (names, numbers) did not work this time.",
                    "Das Lesen von Text im Spiel (Namen, Zahlen) hat diesmal nicht geklappt.",
                    "Usually harmless -- the bot tries again. If it fails every time: update the bot, and let your virus protection allow PowerShell scripts in the bot folder.",
                    "Meist harmlos -- der Bot versucht es erneut. Klappt es nie: den Bot aktualisieren und den Virenschutz PowerShell-Skripte im Bot-Ordner erlauben lassen."),
            r("could not be (?:saved|written)|not (?:saved|written)|konnte nicht gespeichert|nicht gespeichert|nicht geschrieben|lie(?:ss|ß) sich nicht (?:speichern|schreiben)",
                    "The bot could not write a file.",
                    "Der Bot konnte eine Datei nicht schreiben.",
                    "Make sure the bot folder is not write-protected (do not run it from the ZIP or from \"Program Files\") and that virus protection does not block it.",
                    "Prüf, dass der Bot-Ordner nicht schreibgeschützt ist (nicht aus dem ZIP oder aus „Programme“ starten) und der Virenschutz ihn nicht sperrt."),
            r("Battle lost|Kampf verloren",
                    "A fight was lost; the bot carries on.",
                    "Ein Kampf wurde verloren; der Bot macht weiter.",
                    "Single losses are normal. If there are many: check the squad (Settings > Squad: \"Deploy the best\").",
                    "Einzelne Niederlagen sind normal. Sind es viele: die Truppe prüfen (Einstellungen > Truppe: „Deploy the best“)."));

    /** Der Hinweis zu einer Meldung oder Protokollzeile, oder null, wenn dazu nichts Bekanntes drinsteht. */
    public static Hilfe finde(String text) {
        if (text == null || text.isBlank()) return null;
        for (Regel r : REGELN) {
            if (r.muster().matcher(text).matches()) {
                return new Hilfe(Sprache.t(r.bedeutungEn(), r.bedeutungDe()), Sprache.t(r.loesungEn(), r.loesungDe()));
            }
        }
        return null;
    }

    /** Der Text fuer das Fenster "Was heisst das?" (html, ohne Rahmen). */
    public static String alsHtml(String meldung, Hilfe h) {
        String m = meldung == null ? "" : meldung.strip();
        if (m.length() > 240) m = m.substring(0, 238) + "…";
        m = m.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (h == null) {
            return "<html><div style='width:400px'><i>" + m + "</i><br><br>"
                    + Sprache.t("I have no everyday explanation for this message. Most of the time it is harmless; the bot carries on. "
                            + "If something does not work: Support tab > \"Save error report (ZIP)\".",
                    "Zu dieser Meldung habe ich keine Erklärung in Alltagssprache. Meist ist sie harmlos; der Bot macht weiter. "
                            + "Geht etwas nicht: Reiter Support > „Fehlerbericht speichern (ZIP)“.")
                    + "</div></html>";
        }
        return "<html><div style='width:400px'><i>" + m + "</i><br><br><b>" + Sprache.t("What does this mean?", "Was heißt das?") + "</b><br>"
                + h.bedeutung() + "<br><br><b>" + Sprache.t("What can I do?", "Was kann ich tun?") + "</b><br>" + h.loesung() + "</div></html>";
    }
}
