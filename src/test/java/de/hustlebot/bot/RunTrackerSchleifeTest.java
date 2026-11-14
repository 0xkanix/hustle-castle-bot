package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Bugjagd 29.10.: ein Klick, der "Fortschritt" meldet, aber immer dasselbe tut, darf die Haenger-Uhr nicht zuruecksetzen. */
class RunTrackerSchleifeTest {

    @Test
    void gleicherKlickOhneWirkungSetztDieUhrNichtZurueck() throws Exception {
        RunTracker t = new RunTracker(5);
        t.progress("Participation confirmed", false);
        long erster = t.letzterFortschrittUm();
        Thread.sleep(8);
        t.progress("Participation confirmed", true);
        assertEquals(erster, t.letzterFortschrittUm(), "Schleife: Uhr laeuft weiter");
        Thread.sleep(8);
        t.progress("Participation confirmed", false);
        assertTrue(t.letzterFortschrittUm() > erster, "normaler Fortschritt setzt die Uhr zurueck");
    }

    @Test
    void neueMeldungenZaehlenImmer() throws Exception {
        RunTracker t = new RunTracker(5);
        t.progress("A", false);
        long a = t.letzterFortschrittUm();
        Thread.sleep(8);
        t.progress("B");   // andere Meldung als eben: nie eine Schleife
        assertTrue(t.letzterFortschrittUm() > a);
    }

    @Test
    void langsameSchleifeEinKlickAlleZweiSekundenWirdErkannt() {
        // Bericht UZ4S (ben): "Leuchtturm: Quick Attack" ohne Wirkung -- 30 Klicks in einer Minute (hier alle im selben Augenblick)
        de.hustlebot.core.Log.clear();
        for (int i = 0; i < 30; i++) {
            de.hustlebot.core.Log.action("Klick", "Leuchtturm: Quick Attack  [lh_quick_attack at 666,604, match 0,999]");
        }
        assertTrue(de.hustlebot.core.Report.klickSchleife(RunTracker.SCHLEIFE_SEKUNDEN, RunTracker.SCHLEIFE_KLICKS));
        de.hustlebot.core.Log.clear();
        for (int i = 0; i < 30; i++) {
            de.hustlebot.core.Log.action("Klick", i % 3 == 0 ? "Leuchtturm: To battle (fight " + i + ")  [680,284]"
                    : i % 3 == 1 ? "Leuchtturm: Quick Attack  [lh_quick_attack at 666,604, match 0,999]"
                    : "Leuchtturm: Kampfergebnis bestaetigen  [home_button at 580,710]");
        }
        assertFalse(de.hustlebot.core.Report.klickSchleife(RunTracker.SCHLEIFE_SEKUNDEN, RunTracker.SCHLEIFE_KLICKS),
                "gesunde Leuchtturm-Kaempfe: drei verschiedene Klicks im Wechsel");
        de.hustlebot.core.Log.clear();
    }
}
