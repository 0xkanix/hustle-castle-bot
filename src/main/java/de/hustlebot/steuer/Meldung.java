package de.hustlebot.steuer;

import de.hustlebot.core.Zeitzone;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Eine Meldung des Bots, so wie sie in Discord als Karte erschienen waere:
 * Titel, Text, Farbe der Randleiste und -- bei Rueckfragen -- die Knoepfe
 * darunter (Aufschrift, Befehl).
 *
 * Das Steuerprogramm hoert alle Karten mit (DiscordClient.Mithoerer) und zeigt
 * sie im Fenster; ohne Discord-Token sind sie sonst nirgends zu sehen.
 */
public record Meldung(LocalTime zeit, String kanal, String titel, String text, int farbe,
                      List<String[]> knoepfe) {

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm:ss");

    public Meldung {
        titel = titel == null ? "" : titel;
        text = text == null ? "" : text;
        knoepfe = knoepfe == null ? List.of() : List.copyOf(knoepfe);
        kanal = kanal == null ? "" : kanal;
        zeit = zeit == null ? Zeitzone.uhrzeit() : zeit;
    }

    /** Eine Meldung von jetzt. */
    public static Meldung jetzt(String kanal, String titel, String text, int farbe, List<String[]> knoepfe) {
        return new Meldung(Zeitzone.uhrzeit(), kanal, titel, text, farbe, knoepfe);
    }

    public String uhrzeit() {
        return zeit.format(UHR);
    }

    /** Titel und Text ohne Discord-Markdown, fuer das Fenster und die Windows-Meldung. */
    public String titelLesbar() {
        return Holzstil.ohneMarkdown(titel);
    }

    public String textLesbar() {
        return Holzstil.ohneMarkdown(text);
    }

    /** Hat die Meldung Knoepfe (eine Rueckfrage)? */
    public boolean mitKnoepfen() {
        return !knoepfe.isEmpty();
    }

    /** Passt ein Wort auf den Titel (Gross/klein egal)? Fuer die Anzeige-Fenster, die sich eine Karte heraussuchen. */
    public boolean titelEnthaelt(String... woerter) {
        String t = titel.toLowerCase(java.util.Locale.ROOT);
        for (String w : woerter) {
            if (w != null && t.contains(w.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }
}
