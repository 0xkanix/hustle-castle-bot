package de.hustlebot.steuer;

import de.hustlebot.discord.DiscordClient;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 15.10.: in den Einstellungen immer sehen, ob es geklappt hat -- mit Ja oder Nein. */
class RueckmeldungTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    private static Meldung karte(String titel, String text, int farbe) {
        return new Meldung(LocalTime.NOON, "", titel, text, farbe, null);
    }

    @Test
    void antwortDesBotsHeisstGeklappt() {
        var e = Rueckmeldung.auswerten(List.of(karte("Leuchtturm", "**an** -- der Bot schaut nebenbei vorbei.", DiscordClient.FARBE_INFO)),
                null, false);
        assertEquals(Rueckmeldung.Art.GEKLAPPT, e.art());
        assertTrue(e.kurz().startsWith("Geklappt: Leuchtturm: an"), e.kurz());
    }

    @Test
    void nichtVerstandenHeisstNichtGeklappt() {
        var e = Rueckmeldung.auswerten(List.of(karte("Bezahlen", "Nicht verstanden. Verlies: tickets", DiscordClient.FARBE_INFO)),
                null, false);
        assertEquals(Rueckmeldung.Art.NICHT_GEKLAPPT, e.art());
    }

    @Test
    void fehlerFarbeHeisstNichtGeklappt() {
        var e = Rueckmeldung.auswerten(List.of(karte("Fehler", "Datei gesperrt", DiscordClient.FARBE_FEHLER)), null, false);
        assertEquals(Rueckmeldung.Art.NICHT_GEKLAPPT, e.art());
    }

    @Test
    void keineAntwortHeisstNichtGeklappt() {
        assertEquals(Rueckmeldung.Art.NICHT_GEKLAPPT, Rueckmeldung.auswerten(List.of(), null, false).art());
        assertEquals(Rueckmeldung.Art.NICHT_GEKLAPPT,
                Rueckmeldung.auswerten(List.of(), new IllegalStateException("weg"), false).art());
    }

    @Test
    void nachsehenHeisstStand() {
        var e = Rueckmeldung.auswerten(List.of(karte("Kolosseum", "aus", DiscordClient.FARBE_INFO)), null, true);
        assertEquals(Rueckmeldung.Art.STAND, e.art());
        assertEquals("Stand: Kolosseum: aus", e.kurz());
    }

    @Test
    void langeAntwortWirdGekuerzt() {
        var e = Rueckmeldung.auswerten(List.of(karte("", "x".repeat(200), DiscordClient.FARBE_INFO)), null, false);
        assertTrue(e.kurz().length() < 80, e.kurz());
        assertEquals(200, e.lang().length());
    }
}
