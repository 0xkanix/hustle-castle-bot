package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Fasst das Protokoll zu einem kurzen Bericht zusammen.
 *
 * Das vollstaendige Protokoll hat schnell mehrere tausend Zeilen, von denen die
 * allermeisten nichts zur Fehlersuche beitragen. Der Bericht enthaelt nur
 * Warnungen und Fehler samt ein paar Zeilen Zusammenhang davor und danach,
 * dazu einen Kopf mit den wichtigsten Eckdaten.
 */
public final class Report {

    /** So viele Zeilen vor und nach einer Auffaelligkeit kommen mit. */
    private static final int CONTEXT_LINES = 5;

    private Report() {}

    public static String build(Map<String, String> facts) {
        List<Log.Entry> entries = Log.snapshot();
        StringBuilder out = new StringBuilder();

        out.append(Sprache.t("Hustle Castle Bot - Error report\n", "Hustle Castle Bot - Fehlerbericht\n"));
        out.append(Sprache.t("created on ", "erstellt am "))
           .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
           .append("\n");
        out.append("=".repeat(62)).append("\n\n");

        facts.forEach((key, value) -> out.append(pad(key)).append(value).append("\n"));
        out.append("\n");

        zeitberichte(out);

        // Autor, 24.09.: Der Bericht soll "genauer anzeigen, wo er
        // festgesteckt ist ... praeziser anzeigen, was genau passiert ist".
        // Deshalb zuerst die Lage in Klartext, dann der Verlauf der letzten
        // Minuten -- und erst danach die Statistik.
        woStehtEr(entries, out);
        verlauf(entries, out);

        // Zaehlung nach Schweregrad.
        Map<Log.Level, Integer> counts = new EnumMap<>(Log.Level.class);
        for (Log.Entry entry : entries) {
            counts.merge(entry.level(), 1, Integer::sum);
        }
        out.append(pad(Sprache.t("Log lines", "Protokollzeilen"))).append(entries.size()).append("\n");
        out.append(pad(Sprache.t("of which warnings", "davon Warnungen"))).append(counts.getOrDefault(Log.Level.WARN, 0)).append("\n");
        out.append(pad(Sprache.t("of which errors", "davon Fehler"))).append(counts.getOrDefault(Log.Level.ERROR, 0)).append("\n\n");

        // Haeufigste Meldungen, damit Wiederholungen sichtbar werden.
        out.append(Sprache.t("Most frequent messages\n", "Haeufigste Meldungen\n")).append("-".repeat(62)).append("\n");
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (Log.Entry entry : entries) {
            if (entry.level() == Log.Level.DEBUG) continue;
            frequency.merge(normalise(entry.message()), 1, Integer::sum);
        }
        frequency.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> out.append(String.format("%5dx  %s%n", e.getValue(), e.getKey())));
        out.append("\n");

        // Auffaelligkeiten mit Zusammenhang.
        Set<Integer> keep = new TreeSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Log.Level level = entries.get(i).level();
            if (level == Log.Level.WARN || level == Log.Level.ERROR) {
                for (int j = i - CONTEXT_LINES; j <= i + CONTEXT_LINES; j++) {
                    if (j >= 0 && j < entries.size()) keep.add(j);
                }
            }
        }

        // (Die letzten Zeilen stehen seit dem 24.09. oben im "Verlauf".)

        out.append(Sprache.t("Warnings and errors in context\n", "Warnungen und Fehler im Zusammenhang\n")).append("-".repeat(62)).append("\n");
        if (keep.isEmpty()) {
            out.append(Sprache.t("Nothing unusual in the log.\n", "Keine Auffaelligkeiten im Protokoll.\n"));
        } else {
            int previous = -2;
            for (int index : keep) {
                if (index > previous + 1) out.append("   ...\n");
                Log.Entry entry = entries.get(index);
                if (entry.level() == Log.Level.DEBUG) {
                    previous = index;
                    continue;
                }
                out.append(String.format("%s  %-6s %-12s %s%n",
                        entry.time(), entry.level(), entry.source(), entry.message()));
                previous = index;
            }
        }

        out.append("\n").append("=".repeat(62)).append(Sprache.t("\nEnd of report\n", "\nEnde des Berichts\n"));
        return out.toString();
    }

    // ------------------------------------------------------------------
    //  Zeitberichte je Run (Kathedrale) und Lernwerte
    // ------------------------------------------------------------------
    //
    // Autor, 26.09.: "bau so einen Zeitbericht pro Run ein, das soll alles im
    // !log zu sehen sein." Eigene Liste statt Suche im Protokoll: Das
    // Protokoll im Speicher ist begrenzt, nach einer langen Nacht waeren die
    // ersten Runs sonst schon herausgefallen.

    private static final int ZEITBERICHTE_MAX = 60;
    private static final java.util.ArrayDeque<String> ZEITBERICHTE = new java.util.ArrayDeque<>();

    /** Eine Zeile fuer den Abschnitt "Zeitbericht je Run" merken. */
    public static void merkeZeitbericht(String zeile) {
        String mitZeit = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "  " + zeile;
        synchronized (ZEITBERICHTE) {
            ZEITBERICHTE.addLast(mitZeit);
            while (ZEITBERICHTE.size() > ZEITBERICHTE_MAX) ZEITBERICHTE.removeFirst();
        }
    }

    private static void zeitberichte(StringBuilder out) {
        synchronized (ZEITBERICHTE) {
            if (ZEITBERICHTE.isEmpty()) return;
            out.append(Sprache.t("Time report per run (Cathedral) and learned values\n",
                                 "Zeitbericht je Run (Kathedrale) und Lernwerte\n"))
               .append("-".repeat(62)).append("\n");
            for (String z : ZEITBERICHTE) out.append(z).append("\n");
            out.append("\n");
        }
    }

    // ------------------------------------------------------------------
    //  "Wo steht der Bot?" und der Verlauf
    // ------------------------------------------------------------------

    /** So weit reicht der Verlauf zurueck. */
    private static final int VERLAUF_SEKUNDEN = 180;
    /** Hoechstens so viele Zeilen Verlauf. */
    private static final int VERLAUF_ZEILEN = 90;

    private static void woStehtEr(List<Log.Entry> entries, StringBuilder out) {
        out.append(Sprache.t("Where is the bot?\n", "Wo steht der Bot?\n")).append("-".repeat(62)).append("\n");

        int iSchritt = letzter(entries, Report::istSchritt);
        int iKlick = letzter(entries, e -> e.level() == Log.Level.ACTION);
        int iFehler = letzter(entries, e -> e.level() == Log.Level.ERROR);

        long seitSchritt = -1;
        if (iSchritt >= 0) {
            Log.Entry s = entries.get(iSchritt);
            seitSchritt = sekundenSeit(s.time());
            out.append(pad(Sprache.t("Last step", "Letzter Schritt"))).append(s.message())
               .append("  (").append(s.time(), 0, 8).append(Sprache.t(", ", ", vor ")).append(dauer(seitSchritt))
               .append(Sprache.t(" ago)\n", ")\n"));
        } else {
            out.append(pad(Sprache.t("Last step", "Letzter Schritt"))).append(Sprache.t("none in the log\n", "keiner im Protokoll\n"));
        }
        if (iKlick >= 0) {
            Log.Entry k = entries.get(iKlick);
            out.append(pad(Sprache.t("Last click", "Letzter Klick"))).append(kurz(k.message(), 110))
               .append("  (").append(k.time(), 0, 8).append(Sprache.t(", ", ", vor "))
               .append(dauer(sekundenSeit(k.time()))).append(Sprache.t(" ago)\n", ")\n"));
        }

        // Was kam NACH dem letzten Schritt?
        int ab = Math.max(0, iSchritt);
        int unerkannt = 0;
        String aehnlich = null;
        Map<String, Integer> klicks = new LinkedHashMap<>();
        for (int i = ab; i < entries.size(); i++) {
            Log.Entry e = entries.get(i);
            // Autor 11.10.: Die Modi melden seit 3.1 zweisprachig -- hier beide Fassungen erkennen,
            // sonst bleibt der Bericht auf Englisch blind fuer diese Zeilen.
            java.util.regex.Matcher m = UNERKANNT.matcher(e.message());
            if (m.find()) unerkannt = Math.max(unerkannt, Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
            if (e.message().startsWith("Beste Uebereinstimmungen") || e.message().startsWith("Best matches")) aehnlich = e.message();
            if (e.level() == Log.Level.ACTION && i > iSchritt) {
                klicks.merge(normalise(e.message()), 1, Integer::sum);
            }
        }
        if (unerkannt > 0) {
            out.append(pad(Sprache.t("Then unrecognized", "Danach unerkannt"))).append(unerkannt)
               .append(Sprache.t(" frames in a row\n", " Bilder hintereinander\n"));
        }
        if (aehnlich != null) {
            out.append(pad(Sprache.t("Closest match", "Am aehnlichsten"))).append(kurz(aehnlich
                    .replace("Beste Uebereinstimmungen: ", "").replace("Best matches: ", ""), 150)).append("\n");
        }
        String dauernd = null;
        int dauerndMal = 0;
        for (var e : klicks.entrySet()) {
            if (e.getValue() > dauerndMal) {
                dauernd = e.getKey();
                dauerndMal = e.getValue();
            }
        }
        if (iFehler >= 0) {
            Log.Entry f = entries.get(iFehler);
            out.append(pad(Sprache.t("Last error", "Letzter Fehler"))).append(kurz(f.message(), 130))
               .append("  (").append(f.time(), 0, 8).append(")\n");
        }

        // Einschaetzung in einem Satz.
        String satz;
        if (seitSchritt < 0) {
            satz = Sprache.t("Nothing has run yet -- no step in the log.",
                             "Noch nichts gelaufen -- kein Schritt im Protokoll.");
        } else if (seitSchritt < 45) {
            satz = Sprache.t("All flowing: the last step was only " + dauer(seitSchritt) + " ago.",
                             "Alles im Fluss: der letzte Schritt ist erst " + dauer(seitSchritt) + " her.");
        } else if (dauerndMal >= 4) {
            satz = Sprache.t("The bot keeps clicking the same thing (" + dauerndMal + " times since the last "
                    + "step): \"" + dauernd + "\". The click apparently has no effect -- something is "
                    + "in front of it, or the button is somewhere else. See the screenshot.",
                    "Der Bot klickt immer wieder dasselbe (" + dauerndMal + " Mal seit dem letzten "
                    + "Schritt): \"" + dauernd + "\". Der Klick wirkt offenbar nicht -- etwas liegt "
                    + "davor, oder der Knopf sitzt woanders. Siehe Bildschirmfoto.");
        } else if (unerkannt > 0) {
            satz = Sprache.t("The bot has not recognized the screen for " + dauer(seitSchritt) + " ("
                    + unerkannt + " frames). Probably an unknown window or a button "
                    + "with no template image. See the screenshot.",
                    "Der Bot erkennt den Bildschirm seit " + dauer(seitSchritt) + " nicht ("
                    + unerkannt + " Bilder). Vermutlich ein unbekanntes Fenster oder ein Knopf, "
                    + "fuer den ein Bild fehlt. Siehe Bildschirmfoto.");
        } else {
            satz = Sprache.t("No progress for " + dauer(seitSchritt) + ", but no warning either -- "
                    + "the bot is waiting for something (fight, other players, loading screen?).",
                    "Seit " + dauer(seitSchritt) + " kein Fortschritt, aber auch keine Warnung -- "
                    + "der Bot wartet auf etwas (Kampf, Mitspieler, Ladebild?).");
        }
        out.append(pad(Sprache.t("Assessment", "Einschaetzung"))).append(satz).append("\n\n");
    }

    /** Die letzten Minuten: jeder Klick, jede Meldung, jeder geschaffte Schritt. */
    private static void verlauf(List<Log.Entry> entries, StringBuilder out) {
        out.append(Sprache.t("History of the last ", "Verlauf der letzten ")).append(VERLAUF_SEKUNDEN / 60)
           .append(Sprache.t(" minutes\n", " Minuten\n"))
           .append("-".repeat(62)).append("\n");
        List<Log.Entry> zeilen = new ArrayList<>();
        for (Log.Entry e : entries) {
            boolean wichtig = e.level() != Log.Level.DEBUG || istSchritt(e);
            if (!wichtig || istVorlagen(e)) continue;
            if (sekundenSeit(e.time()) <= VERLAUF_SEKUNDEN) zeilen.add(e);
        }
        if (zeilen.size() > VERLAUF_ZEILEN) {
            out.append("   ... (").append(zeilen.size() - VERLAUF_ZEILEN)
               .append(Sprache.t(" older lines omitted)\n", " aeltere Zeilen weggelassen)\n"));
            zeilen = zeilen.subList(zeilen.size() - VERLAUF_ZEILEN, zeilen.size());
        }
        if (zeilen.isEmpty()) out.append(Sprache.t("Nothing in the last minutes.\n", "Nichts in den letzten Minuten.\n"));
        for (Log.Entry e : zeilen) {
            String art = istSchritt(e) ? Sprache.t("STEP", "SCHRITT") : e.level().name();
            out.append(String.format("%s  %-7s %-12s %s%n", e.time(), art, e.source(),
                    kurz(e.message(), 170)));
        }
        out.append("\n");
    }

    /**
     * "Seit 12 Bildern nichts erkannt" bzw. "Nothing recognized for 12 frames" -- die Zahl
     * steht je nach Sprache in Gruppe 1 oder 2.
     */
    private static final java.util.regex.Pattern UNERKANNT = java.util.regex.Pattern
            .compile("Seit (\\d+) Bildern nichts erkannt|Nothing recogni[sz]ed for (\\d+) frames");

    /**
     * Die Quelle einer Protokollzeile steht seit 3.1 in der Sprache des Spielers
     * (Sprache.quelle) -- deshalb hier beide Schreibweisen zulassen.
     */
    private static boolean istSchritt(Log.Entry e) {
        return "Fortschritt".equals(e.source()) || "Progress".equals(e.source());
    }

    private static boolean istVorlagen(Log.Entry e) {
        return "Vorlagen".equals(e.source()) || "Templates".equals(e.source());
    }

    private static int letzter(List<Log.Entry> entries,
                               java.util.function.Predicate<Log.Entry> wer) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (wer.test(entries.get(i))) return i;
        }
        return -1;
    }

    /** Sekunden seit einer Protokoll-Uhrzeit ("HH:mm:ss.SSS"), ueber Mitternacht hinweg. */
    private static long sekundenSeit(String zeit) {
        try {
            java.time.LocalTime t = java.time.LocalTime.parse(zeit.length() > 12 ? zeit.substring(0, 12) : zeit);
            long s = java.time.Duration.between(t, java.time.LocalTime.now()).getSeconds();
            return s < 0 ? s + 24 * 3600 : s;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String dauer(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private static String kurz(String t, int max) {
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** Schreibt den Bericht in eine Datei und liefert deren Pfad. */
    public static Path writeToFile(Map<String, String> facts) throws Exception {
        Path directory = Path.of("logs");
        Files.createDirectories(directory);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path file = directory.resolve("bericht_" + stamp + ".txt");

        Files.writeString(file, build(facts), StandardCharsets.UTF_8);
        Log.info("Bericht", Sprache.t("Error report saved: " + file.toAbsolutePath(),
                                      "Fehlerbericht gespeichert: " + file.toAbsolutePath()));
        return file;
    }

    /** Ersetzt wechselnde Zahlen, damit gleichartige Meldungen zusammenfallen. */
    /**
     * Autor 24.10. (1247 Haenger-Berichte, die meisten mit "Alles im Fluss"): Arbeitet der Bot gerade sichtbar? In den
     * letzten {@code sekunden} mindestens 4 Klicks auf mindestens 3 verschiedene Ziele, keins davon mehr als die
     * Haelfte -- dann ist es kein Stillstand (Leuchtturm- und Kolosseum-Kaempfe, Geschenke an Freunde ...). Ein Klick,
     * der immer wieder kommt, bleibt ein Haenger.
     */
    public static boolean arbeitetSichtbar(int sekunden) {
        return arbeitetSichtbar(Log.snapshot(), sekunden);
    }

    static boolean arbeitetSichtbar(List<Log.Entry> entries, int sekunden) {
        Map<String, Integer> klicks = new HashMap<>();
        int alle = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Log.Entry e = entries.get(i);
            if (sekundenSeit(e.time()) > sekunden) break;
            if (e.level() != Log.Level.ACTION) continue;
            klicks.merge(normalise(e.message()), 1, Integer::sum);
            alle++;
        }
        if (alle < 4 || klicks.size() < 3) return false;
        return Collections.max(klicks.values()) * 2 <= alle;
    }

    /**
     * Klickt der Bot seit {@code sekunden} fast nur denselben Klick (mindestens {@code mind} Klicks, davon mindestens 90 %
     * derselbe)? Das ist ein Klick ohne Wirkung -- kein Fortschritt, auch wenn jeder Klick "Fortschritt" meldet.
     *
     * WARUM (Bugjagd 29.10., Berichte W7E9/7NRP/FK7G/G3YH): "Teilnahme bestaetigt" setzte bei jedem der ~27 000 vergeblichen
     * "Participate"-Klicks die Haenger-Uhr zurueck -- drei Stunden lang kam weder ein Bericht noch ein Abbruch. Echte Arbeit
     * mischt immer andere Klicks darunter (Hammer-Event: hoechstens 20 gleiche Belohnungs-Klicks in Folge, dann Form und Lupe).
     */
    public static boolean klickSchleife(int sekunden, int mind) {
        return klickSchleife(Log.snapshot(), sekunden, mind);
    }

    static boolean klickSchleife(List<Log.Entry> entries, int sekunden, int mind) {
        Map<String, Integer> klicks = new HashMap<>();
        int alle = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Log.Entry e = entries.get(i);
            if (sekundenSeit(e.time()) > sekunden) break;
            if (e.level() != Log.Level.ACTION) continue;
            klicks.merge(normalise(e.message()), 1, Integer::sum);
            alle++;
        }
        if (alle < mind) return false;
        return Collections.max(klicks.values()) * 10 >= alle * 9;
    }

    private static String normalise(String message) {
        String result = message.replaceAll("\\d+", "#");
        return result.length() > 90 ? result.substring(0, 90) + "…" : result;
    }

    private static String pad(String label) {
        return String.format("%-22s: ", label);
    }
}
