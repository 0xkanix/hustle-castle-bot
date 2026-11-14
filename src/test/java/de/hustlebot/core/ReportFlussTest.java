package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFlussTest {
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static Log.Entry klick(int vorSekunden, String text) {
        return new Log.Entry(LocalTime.now().minusSeconds(vorSekunden).format(ZEIT), Log.Level.ACTION, "Klick", text);
    }

    @Test
    void leuchtturmKaempfeSindKeinHaenger() {
        List<Log.Entry> e = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            e.add(klick(18 - 5 * k, "Leuchtturm: To battle (Kampf " + k + ")  [680,284]"));
            e.add(klick(17 - 5 * k, "Leuchtturm: Quick Attack  [lh_quick_attack bei 666,604, Uebereinstimmung 1,000]"));
            e.add(klick(16 - 5 * k, "Leuchtturm: Kampfergebnis bestaetigen  [home_button bei 580,710]"));
        }
        assertTrue(Report.arbeitetSichtbar(e, 20));
    }

    @Test
    void immerDerselbeKlickBleibtEinHaenger() {
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 8; i++) e.add(klick(19 - 2 * i, "Next opponent (scouting)  [ar_next_arrow at 900,400]"));
        e.add(klick(2, "Open opponent Urano"));
        e.add(klick(1, "Open opponent Rina"));
        assertFalse(Report.arbeitetSichtbar(e, 20));
    }

    @Test
    void alteKlicksZaehlenNicht() {
        List<Log.Entry> e = new ArrayList<>();
        e.add(klick(60, "A"));
        e.add(klick(55, "B"));
        e.add(klick(50, "C"));
        e.add(klick(45, "D"));
        assertFalse(Report.arbeitetSichtbar(e, 20));
    }

    @Test
    void sechzigGleicheKlicksInEinerMinuteSindEineSchleife() {
        // Bericht W7E9: "Turbo: Participate in the cathedral (dc_participate)" ~3 Mal je Sekunde
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 120; i++) e.add(klick(59 - i / 3, "Participate in the cathedral (dc_participate)"));
        assertTrue(Report.klickSchleife(e, 60, 40));
    }

    @Test
    void echteArbeitMitAnderenKlicksIstKeineSchleife() {
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            e.add(klick(59 - i, "Claim reward (right away)  [ev_claim at 579,668]"));
            if (i % 2 == 0) e.add(klick(59 - i, "Magnifier " + i + " of 20  [ev_see_through at 1012,382]"));
            if (i % 3 == 0) e.add(klick(59 - i, "Confirm the spot -- uncover three vases  [ev_ok at 909,313]"));
        }
        assertFalse(Report.klickSchleife(e, 60, 40), "Hammer-Event: viele Belohnungs-Klicks, aber auch Lupe und Form");
    }

    @Test
    void zuWenigeKlicksOderAlteSindKeineSchleife() {
        List<Log.Entry> e = new ArrayList<>();
        for (int i = 0; i < 30; i++) e.add(klick(50, "Participate in the cathedral (dc_participate)"));
        assertFalse(Report.klickSchleife(e, 60, 40), "nur 30 Klicks");
        List<Log.Entry> alt = new ArrayList<>();
        for (int i = 0; i < 100; i++) alt.add(klick(120, "Participate in the cathedral (dc_participate)"));
        assertFalse(Report.klickSchleife(alt, 60, 40), "zwei Minuten alt");
    }
}
