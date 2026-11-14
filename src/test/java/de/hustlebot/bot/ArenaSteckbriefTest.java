package de.hustlebot.bot;

import de.hustlebot.core.Sprache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steckbrief fuers Seitenfenster (Autor 30.10.): Bilanz, Kampfdauer, Punkte, Kraft + Team-Punkte; was unbekannt ist, steht nicht da.
 */
class ArenaSteckbriefTest {

    @BeforeAll
    static void aufEnglisch() {
        Sprache.set(Sprache.Lang.EN);
    }

    /** Ein Spieler, ueber den der Bot alles weiss. */
    private static ArenaSteckbrief.Werte bekannt() {
        return new ArenaSteckbrief.Werte("SSSSSSSSSS", 10, 10, 90, List.of(60, 80, 100), "2026-10-30 23:31", List.of(150L, 225L),
                190, 225, 450, 31_000_000L, 32_500_000L, 55, "sets 40, artifacts 15");
    }

    /** Ein Spieler, den der Bot nie gesehen hat. */
    private static ArenaSteckbrief.Werte unbekannt() {
        return new ArenaSteckbrief.Werte("", 0, 0, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
    }

    private static ArenaSteckbrief.Werte mitKraft(long kraft, long meine) {
        return new ArenaSteckbrief.Werte("", 0, 0, -1, List.of(), "", List.of(), -1, 0, -1, kraft, meine,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
    }

    @Test
    void bekannterSpielerZeigtAlleZeilen() {
        assertEquals(List.of(
                "Won 10 · lost 0 · last: S S S S S",
                "Win chance 90 % · lose chance 10 %",
                "Fight: average 80 s · shortest 60 s · longest 100 s",
                "Last fights: 60 s, 80 s, 100 s · last fight 30.10. 23:31",
                "Average points per fight: 190 · last: 150, 225",
                "Reward now: +225 · Arena points now: 450",
                "Power 31,000,000 · 1.5 M below you",
                "Points 55 (sets 40, artifacts 15)"),
                List.of(ArenaSteckbrief.text(bekannt()).split("\n")));
    }

    @Test
    void dieLetztenFuenfKaempfeStehenEinzelnDerAelteresteFaelltWeg() {
        var w = new ArenaSteckbrief.Werte("NNSSNSN", 3, 7, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
        assertEquals("Won 3 · lost 4 · last: S S N S N", ArenaSteckbrief.text(w));
        // kurzer Verlauf: alles, was da ist
        var kurz = new ArenaSteckbrief.Werte("SSN", 2, 3, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
        assertEquals("Won 2 · lost 1 · last: S S N", ArenaSteckbrief.text(kurz));
        // Kaempfe gezaehlt, aber kein Verlauf: nur die Bilanz
        var ohneVerlauf = new ArenaSteckbrief.Werte("", 2, 3, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
        assertEquals("Won 2 · lost 1", ArenaSteckbrief.text(ohneVerlauf));
    }

    @Test
    void kampfdauerRechnetSchnittKuerzestenUndLaengstenUndZeigtNurDieLetztenFuenf() {
        var w = new ArenaSteckbrief.Werte("", 0, 0, -1, List.of(10, 20, 30, 40, 50, 60, 70), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "");
        List<String> z = List.of(ArenaSteckbrief.text(w).split("\n"));
        assertTrue(z.contains("Fight: average 40 s · shortest 10 s · longest 70 s"), z.toString());
        String letzte = z.stream().filter(s -> s.startsWith("Last fights:")).findFirst().orElseThrow();
        assertEquals("Last fights: 30 s, 40 s, 50 s, 60 s, 70 s", letzte, "ohne Zeit kein 'last fight'");
    }

    @Test
    void kraftZeigtDenAbstandUeberOderUnterDir() {
        assertTrue(ArenaSteckbrief.text(mitKraft(31_000_000L, 32_500_000L)).endsWith("\nPower 31,000,000 · 1.5 M below you"));
        assertTrue(ArenaSteckbrief.text(mitKraft(34_000_000L, 32_500_000L)).endsWith("\nPower 34,000,000 · 1.5 M above you"));
        assertTrue(ArenaSteckbrief.text(mitKraft(34_000_000L, 0)).endsWith("\nPower 34,000,000"), "eigene Kraft unbekannt: kein Abstand");
    }

    @Test
    void teamPunkteMitBestandteilenUndNurMitBestandteilen() {
        var nurTeam = new ArenaSteckbrief.Werte("", 0, 0, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0,
                ArenaSteckbrief.PUNKTE_UNBEKANNT, "sets 40");
        assertTrue(ArenaSteckbrief.text(nurTeam).endsWith("Team (sets 40)"), ArenaSteckbrief.text(nurTeam));
        var nurPunkte = new ArenaSteckbrief.Werte("", 0, 0, -1, List.of(), "", List.of(), -1, 0, -1, 0, 0, -40, "");
        assertTrue(ArenaSteckbrief.text(nurPunkte).endsWith("Points -40"), ArenaSteckbrief.text(nurPunkte));
    }

    @Test
    void unbekannterSpielerZeigtNurKeinKampf() {
        String t = ArenaSteckbrief.text(unbekannt());
        assertEquals("No fights yet", t);
        assertFalse(t.contains("\n"), "keine weitere Zeile");
        for (String zeile : new String[]{"Fight", "Power", "Points", "Reward", "chance"}) {
            assertFalse(t.contains(zeile), zeile + " darf nicht dastehen: " + t);
        }
    }

    @Test
    void zeitWirdTagMonatUhrzeit() {
        assertEquals("30.10. 23:31", ArenaSteckbrief.zeit("2026-10-30 23:31"));
        assertEquals("30.10. 23:31", ArenaSteckbrief.zeit("2026-10-30 23:31:45"), "Sekunden fallen weg");
        assertEquals("", ArenaSteckbrief.zeit(""));
        assertEquals("", ArenaSteckbrief.zeit(null));
        assertEquals("", ArenaSteckbrief.zeit("Muell"));
        assertEquals("", ArenaSteckbrief.zeit("heute abend um halb zwoelf"), "lang genug, aber kein Datum");
    }

    @Test
    void teamTeileLaesstNullenWeg() {
        assertEquals("sets 40, builders 5", ArenaSteckbrief.teamTeile(40, 0, 5, 0, 0, 0, 0));
        assertEquals("artifacts 9, pals 2", ArenaSteckbrief.teamTeile(0, 9, 0, 0, 0, 0, 2));
        assertEquals("", ArenaSteckbrief.teamTeile(0, 0, 0, 0, 0, 0, 0));
        assertEquals("sets 1, artifacts 2, builders 3, missing units 4, stones 5, patronages 6, pals 7",
                ArenaSteckbrief.teamTeile(1, 2, 3, 4, 5, 6, 7));
    }
}
