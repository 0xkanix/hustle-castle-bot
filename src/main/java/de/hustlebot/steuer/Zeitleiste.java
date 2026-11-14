package de.hustlebot.steuer;

import de.hustlebot.core.Energieplan;
import de.hustlebot.core.Kriegsplan;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Turmplan;
import de.hustlebot.core.Zeitzone;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Autor 24.10. (Ideenliste F28): Die Zeitleiste des Tages auf der Steuerung -- ein schmaler Streifen:
 * "Jetzt: Kathedrale 12/100  ->  08:00 Leuchtturm  ->  09:20 Clan-Krieg  ->  12:00 Titanen-Turm".
 * Bisher stand nur die Zustands-Pille da; wann der Bot als Naechstes etwas anderes tut, stand im Protokoll
 * ("Next visit at ...").
 *
 * Die Termine werden nur GELESEN (Energieplan, Kriegsplan, Turmplan). Die Warteschlange ("Danach anhaengen") gehoert der
 * Discord-Bruecke und ist von aussen nicht einsehbar -- sie steht darum nicht in der Leiste. Alle Zeiten gelten nach der
 * eingestellten Zeitzone (dieselbe Uhr wie alle Zeitplaene des Bots).
 */
final class Zeitleiste {

    private Zeitleiste() {
    }

    /** Ein Termin: wann, was -- und ob er schon dran ist (dann steht "jetzt" statt einer Uhrzeit). */
    record Termin(LocalDateTime zeit, String name, boolean faellig) {
    }

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");
    /** Weiter als das schaut die Leiste nicht voraus. */
    private static final Duration VORAUS = Duration.ofHours(24);
    static final int MAX_TERMINE = 5;

    // ------------------------------------------------------------------------------------------------------------
    //  Die Termine aus dem Bot
    // ------------------------------------------------------------------------------------------------------------

    /** Alle bekannten Termine der naechsten 24 Stunden, nach Zeit sortiert. Nur lesen; Fehler einzelner Quellen stoeren nicht. */
    static List<Termin> termine(LocalDateTime jetzt) {
        List<Termin> l = new ArrayList<>();
        try {
            plan(l, jetzt, Energieplan.LEUCHTTURM, Sprache.t("Lighthouse", "Leuchtturm"));
        } catch (RuntimeException ignored) {
            // diese Quelle fehlt -- die anderen zaehlen
        }
        try {
            plan(l, jetzt, Energieplan.KOLOSSEUM, Sprache.t("Colosseum", "Kolosseum"));
        } catch (RuntimeException ignored) {
            // s. o.
        }
        try {
            if (kriegsplanAn()) {
                LocalDateTime krieg = Kriegsplan.naechsteKriegszeitHier(jetzt);
                if (krieg != null) l.add(new Termin(krieg, Sprache.t("Clan war", "Clan-Krieg"), false));
            }
        } catch (RuntimeException ignored) {
            // s. o.
        }
        try {
            if (Turmplan.istAn()) {
                // Der Turm hat zwei Fenster am Tag (02:00 und 12:00); ist eines offen und noch nicht erledigt, ist er "jetzt" dran.
                boolean faellig = Turmplan.faellig().isPresent();
                LocalDateTime t = naechste(jetzt, LocalTime.of(2, 0), LocalTime.of(12, 0));
                l.add(faellig ? new Termin(jetzt, Sprache.t("Titan tower", "Titanen-Turm"), true)
                        : new Termin(t, Sprache.t("Titan tower", "Titanen-Turm"), false));
            }
        } catch (RuntimeException ignored) {
            // s. o.
        }
        l.removeIf(t -> t.zeit().isAfter(jetzt.plus(VORAUS)));
        l.sort(Comparator.comparing(Termin::zeit));
        return l;
    }

    private static void plan(List<Termin> l, LocalDateTime jetzt, Energieplan plan, String name) {
        if (!plan.istAn()) return;
        String wann = plan.naechsterBesuch();   // "HH:mm" (Uhr des PCs) oder "sofort"
        if ("sofort".equals(wann)) {
            l.add(new Termin(jetzt, name, true));
            return;
        }
        LocalTime zeit = LocalTime.parse(wann, UHR);
        // Der Plan rechnet nach der Uhr des PCs, die Leiste nach der Zeitzone des Bots.
        LocalDateTime lokal = LocalDate.now().atTime(zeit);
        if (lokal.isBefore(LocalDateTime.now().minusMinutes(1))) lokal = lokal.plusDays(1);
        LocalDateTime inZone = lokal.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(Zeitzone.zone()).toLocalDateTime();
        l.add(new Termin(inZone, name, false));
    }

    private static boolean kriegsplanAn() {
        return new Schalterdatei(Ordner.bei("kriegsplan-an.txt"), "Zeitleiste", true).lesen();
    }

    /** Die naechste dieser Uhrzeiten nach "jetzt" (heute oder morgen). */
    static LocalDateTime naechste(LocalDateTime jetzt, LocalTime... zeiten) {
        LocalDateTime beste = null;
        for (int tag = 0; tag <= 1; tag++) {
            for (LocalTime z : zeiten) {
                LocalDateTime t = jetzt.toLocalDate().plusDays(tag).atTime(z);
                if (t.isAfter(jetzt) && (beste == null || t.isBefore(beste))) beste = t;
            }
        }
        return beste;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Der Text
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Die Leiste als Text.
     *
     * @param laeuft was der Bot gerade tut ("Kathedrale 12/100"), leer = nichts
     */
    static String text(LocalDateTime jetzt, String laeuft, List<Termin> termine) {
        StringBuilder b = new StringBuilder();
        b.append(Sprache.t("Now: ", "Jetzt: "))
                .append(laeuft == null || laeuft.isBlank() ? Sprache.t("nothing running", "nichts läuft") : laeuft.strip());
        int n = 0;
        for (Termin t : termine) {
            if (n++ >= MAX_TERMINE) break;
            b.append("   →   ");
            if (t.faellig()) {
                b.append(Sprache.t("due: ", "dran: "));
            } else {
                b.append(t.zeit().format(UHR));
                if (!t.zeit().toLocalDate().equals(jetzt.toLocalDate())) b.append(Sprache.t(" (tomorrow)", " (morgen)"));
                b.append(' ');
            }
            b.append(t.name());
        }
        if (termine.isEmpty()) {
            b.append("   ·   ").append(Sprache.t("nothing planned (lighthouse, colosseum, clan war and titan tower are off)",
                    "nichts geplant (Leuchtturm, Kolosseum, Clan-Krieg und Titanen-Turm sind aus)"));
        }
        return b.toString();
    }
}
