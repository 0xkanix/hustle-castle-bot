package de.hustlebot.bot;

import de.hustlebot.core.Sprache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wohin die Zeit eines Kathedralen-Runs geht.
 *
 * Autor, 26.09.: "ja bau so einen Zeitbericht pro Run ein, das soll alles im
 * !log zu sehen sein." Grundlage fuer den Lernmodus: Erst wenn feststeht,
 * wie viel ein Run fuer Kaempfe, Segen, Wegwahl und Laden braucht -- und wie
 * viel davon der Bot selbst wartet --, laesst sich sagen, wo Kuerzen lohnt.
 *
 * Funktionsweise: Der Run ist immer genau in EINEM Abschnitt. Bei jedem
 * Wechsel (phase) wird die Zeit seit dem letzten Wechsel dem alten
 * Abschnitt gutgeschrieben. Am Ende steht alles in einer Zeile.
 */
final class Zeitbericht {

    /** Reihenfolge der Abschnitte im Bericht. */
    static final String LADEN = "Laden", ANLAUF = "Anlauf", KAMPF = "Kampf", SEGEN = "Segen",
            WEGWAHL = "Wegwahl", AUSWAHL = "Fenster", ETAGE = "Etagenwechsel", ABSCHLUSS = "Abschluss";

    private final Map<String, Long> ms = new LinkedHashMap<>();
    private String phase = null;
    private long seit = 0;
    private long botWartetBeiStart = 0;
    private int kaempfe = 0;

    /** Der Run beginnt ("Participate"). */
    void start(long botWartetMs) {
        ms.clear();
        for (String p : new String[]{LADEN, ANLAUF, KAMPF, SEGEN, WEGWAHL, AUSWAHL, ETAGE, ABSCHLUSS}) {
            ms.put(p, 0L);
        }
        phase = LADEN;
        seit = System.currentTimeMillis();
        botWartetBeiStart = botWartetMs;
        kaempfe = 0;
    }

    /** Ab jetzt zaehlt die Zeit fuer diesen Abschnitt. Ohne start() still. */
    void phase(String neu) {
        if (phase == null || neu.equals(phase)) return;
        long jetzt = System.currentTimeMillis();
        ms.merge(phase, jetzt - seit, Long::sum);
        phase = neu;
        seit = jetzt;
        if (KAMPF.equals(neu)) kaempfe++;
    }

    /**
     * Schliesst den Run ab und liefert die Zeile fuer das Protokoll, oder
     * null, wenn der Run nicht mit start() begonnen hat.
     */
    String abschluss(long botWartetMs) {
        if (phase == null) return null;
        phase(ABSCHLUSS);   // die letzte Strecke gehoert zum Abschluss
        ms.merge(ABSCHLUSS, System.currentTimeMillis() - seit, Long::sum);
        long gesamt = 0;
        for (long v : ms.values()) gesamt += v;
        StringBuilder b = new StringBuilder();
        b.append(String.format(Sprache.t("%.0f s total | ", "%.0f s gesamt | "), gesamt / 1000.0));
        for (var e : ms.entrySet()) {
            if (e.getValue() < 50) continue;
            b.append(bezeichnung(e.getKey())).append(' ').append(String.format("%.1f", e.getValue() / 1000.0))
             .append(" s");
            if (KAMPF.equals(e.getKey()) && kaempfe > 0) {
                b.append(String.format(" (%d x %.2f)", kaempfe, e.getValue() / 1000.0 / kaempfe));
            }
            b.append(" | ");
        }
        b.append(String.format(Sprache.t("of which the bot waits %.1f s", "davon Bot wartet %.1f s"),
                (botWartetMs - botWartetBeiStart) / 1000.0));
        phase = null;
        // Autor 17.10.: Kathedralen-Zeitmesser -- jede Run-Zeit merken, Meldung ins Steuerfenster, "!times".
        de.hustlebot.core.Kathedralzeiten.merken(gesamt, b.toString());
        return b.toString();
    }

    /**
     * Der Name eines Abschnitts, wie er im Bericht steht (11.10.: zweisprachig).
     * Die Schluessel selbst bleiben deutsch -- sie werden von der Kathedrale uebergeben.
     */
    private static String bezeichnung(String abschnitt) {
        return switch (abschnitt) {
            case LADEN -> Sprache.t("Loading", LADEN);
            case ANLAUF -> Sprache.t("Start-up", ANLAUF);
            case KAMPF -> Sprache.t("Fights", KAMPF);
            case SEGEN -> Sprache.t("Blessings", SEGEN);
            case WEGWAHL -> Sprache.t("Path choice", WEGWAHL);
            case AUSWAHL -> Sprache.t("Windows", AUSWAHL);
            case ETAGE -> Sprache.t("Floor change", ETAGE);
            case ABSCHLUSS -> Sprache.t("Completion", ABSCHLUSS);
            default -> abschnitt;
        };
    }
}
