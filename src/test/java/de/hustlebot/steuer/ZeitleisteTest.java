package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F28): die Zeitleiste des Tages.
 */
class ZeitleisteTest {

    private static final LocalDateTime JETZT = LocalDateTime.of(2026, 10, 24, 7, 30);

    @Test
    void textZeigtJetztUndDieNaechstenTermineInReihenfolge() {
        List<Zeitleiste.Termin> t = List.of(
                new Zeitleiste.Termin(LocalDateTime.of(2026, 10, 24, 8, 0), "Leuchtturm", false),
                new Zeitleiste.Termin(LocalDateTime.of(2026, 10, 24, 9, 20), "Clan-Krieg", false),
                new Zeitleiste.Termin(LocalDateTime.of(2026, 10, 25, 2, 0), "Titanen-Turm", false));
        String s = Zeitleiste.text(JETZT, "Kathedrale 41/100", t);
        assertTrue(s.contains("Kathedrale 41/100"));
        assertTrue(s.indexOf("08:00 Leuchtturm") < s.indexOf("09:20 Clan-Krieg"));
        assertTrue(s.indexOf("09:20 Clan-Krieg") < s.indexOf("02:00"));
        assertTrue(s.contains("(morgen)") || s.contains("(tomorrow)"), "ein Termin am naechsten Tag sagt es");
    }

    @Test
    void faelligerTerminHatKeineUhrzeit() {
        String s = Zeitleiste.text(JETZT, "", List.of(new Zeitleiste.Termin(JETZT, "Kolosseum", true)));
        assertTrue(s.contains("Kolosseum"));
        assertFalse(s.contains("07:30"));
        assertTrue(s.contains("dran") || s.contains("due"));
    }

    @Test
    void ohneTermineSagtDieLeisteWarum() {
        String s = Zeitleiste.text(JETZT, null, List.of());
        assertTrue(s.contains("nichts") || s.contains("nothing"));
    }

    @Test
    void hoechstensFuenfTermine() {
        List<Zeitleiste.Termin> t = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) t.add(new Zeitleiste.Termin(JETZT.plusMinutes(10 * (i + 1)), "T" + i, false));
        String s = Zeitleiste.text(JETZT, "x", t);
        assertTrue(s.contains("T4"));
        assertFalse(s.contains("T5"));
    }

    @Test
    void naechsteUhrzeitHeuteOderMorgen() {
        LocalTime z2 = LocalTime.of(2, 0), z12 = LocalTime.of(12, 0);
        assertEquals(LocalDateTime.of(2026, 10, 24, 12, 0), Zeitleiste.naechste(JETZT, z2, z12));
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 0), Zeitleiste.naechste(LocalDateTime.of(2026, 10, 24, 13, 0), z2, z12));
    }

    @Test
    void termineAusDemBotWerfenNie() {
        List<Zeitleiste.Termin> l = Zeitleiste.termine(LocalDateTime.now());
        assertNotNull(l);
        for (int i = 1; i < l.size(); i++) assertFalse(l.get(i).zeit().isBefore(l.get(i - 1).zeit()), "sortiert");
    }
}
