package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Wann die Edelsteinbucht (Gem Bay) dran ist (Autor, 29.09.).
 *
 * "Der Bot soll um 2 Uhr nachts und um 12 Uhr einmal schauen, ob noch
 * Edelsteinbucht frei ist" -- erst der Titanen-Turm, dann Gem Bay, dann weiter
 * mit dem eingestellten Modus. Gem Bay setzt sich wie der Turm jeden Tag um
 * 02:00 zurueck. Ob sie heute schon gespielt ist, sieht der Bot am Preis unter
 * "Start journey": Diamanten statt Nahrung heisst "schon erledigt".
 *
 * Dieselbe Bauart wie Turmplan: zwei Fenster von 55 Minuten, ein Vermerk in
 * einer Datei, damit ein Neustart nicht noch einmal hinlaeuft.
 */
public final class GemBayplan {

    private GemBayplan() {}

    private static final List<LocalTime> ZEITEN = List.of(LocalTime.of(2, 0), LocalTime.of(12, 0));
    private static final Duration FENSTER = Duration.ofMinutes(55);
    /** Kommt ein angestossener Besuch nicht dran (etwa nach !stop), nach 20 Minuten noch einmal. */
    private static final long WIEDER_MS = 20 * 60_000L;

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("gembay.txt"), "Gem Bay", false);   // Autor, 29.09.: erst nach allen Bildern einschalten
    private static volatile boolean an = SCHALTER.lesen();
    private static final Path VERMERK = Path.of("gembay-erledigt.txt");

    private static volatile LocalDateTime angestossenFuer;
    private static volatile long angestossenUm;

    public static boolean istAn() { return an; }

    public static void setAn(boolean neu) {
        an = neu;
        SCHALTER.setzen(neu, "Gem Bay um 02:00 und 12:00 (an/aus), gesetzt mit !settings gembay");
    }

    // --- Reset laut Anzeige "Journey cost resets in: ..." (Autor, 29.09.) ---
    private static final Path RESET_DATEI = Path.of("gembay-reset.txt");

    /**
     * Darf die im Besuch gelesene Reset-Zeit jetzt in gembay-reset.txt? Nur wenn der Besuch das
     * Zeitfenster erledigt: geschafft (oder heute schon alles gespielt), oder der zweite
     * Fehlversuch. Nach dem ERSTEN Fehlversuch nicht -- eine Reset-Zeit in der Zukunft sperrt
     * faelligBei bis zum Reset (meist viele Stunden), und der versprochene zweite Versuch nach
     * 20 Minuten kaeme nie (Pruefung 05.10.: frueher schon beim ersten Blick ins Fenster geschrieben).
     */
    public static boolean resetJetztMerken(boolean erledigt, int fehlversuche) {
        return erledigt || fensterZu(fehlversuche);
    }

    /** Die abgelesene Reset-Zeit merken (ueberlebt einen Neustart). */
    public static synchronized void resetMerken(LocalDateTime um) {
        try {
            Files.writeString(RESET_DATEI, um.withNano(0).toString());
        } catch (Exception e) {
            Log.debug("Gem Bay", "Reset-Zeit nicht geschrieben: " + e.getMessage());
        }
    }

    private static LocalDateTime resetZeit() {
        try {
            if (!Files.exists(RESET_DATEI)) return null;
            String t = Dateien.text(RESET_DATEI).trim();
            return t.isEmpty() ? null : LocalDateTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** "15h 20m", "2d 3h", "45m 10s" -> Dauer; null, wenn nichts Lesbares darin steht. */
    public static Duration dauerAusText(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*([dhms])", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        Duration d = Duration.ZERO;
        boolean gefunden = false;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'd' -> d = d.plusDays(n);
                case 'h' -> d = d.plusHours(n);
                case 'm' -> d = d.plusMinutes(n);
                default -> d = d.plusSeconds(n);
            }
            gefunden = true;
        }
        return gefunden && d.compareTo(Duration.ofDays(3)) < 0 ? d : null;
    }

    /** Ist Gem Bay jetzt dran? Liefert den Anfang des offenen Fensters. */
    public static synchronized Optional<LocalDateTime> faellig() {
        if (!an) return Optional.empty();
        return faelligBei(Zeitzone.jetzt(), resetZeit(), vermerk(), angestossenFuer,
                System.currentTimeMillis() - angestossenUm);
    }

    /**
     * Dieselbe Frage ohne Dateien und ohne Uhr (fuer GemBayplanTest). msSeitAnstoss: wie lange
     * der letzte Anstoss her ist -- ein angestossener Besuch, der nicht drankam (etwa nach !stop)
     * oder der fehlschlug, ist nach WIEDER_MS noch einmal dran. Ein vermerktes Fenster nie wieder
     * (Pruefung 04.10., P4: frueher wurde nie vermerkt, der Bot ging alle 20 Minuten hin).
     */
    static Optional<LocalDateTime> faelligBei(LocalDateTime jetzt, LocalDateTime reset, LocalDateTime vermerk,
                                              LocalDateTime angestossenFuer, long msSeitAnstoss) {
        boolean kuerzlich = msSeitAnstoss < WIEDER_MS;
        // Reset bekannt und noch nicht erreicht: noch nichts Neues zu holen. Deshalb wird die
        // Reset-Zeit erst vermerkt, wenn das Fenster erledigt ist (resetJetztMerken).
        if (reset != null && jetzt.isBefore(reset)) return Optional.empty();
        // Reset vorbei und noch nicht besucht: gleich hin (nicht erst um 02:00/12:00).
        if (reset != null && jetzt.isBefore(reset.plusHours(12)) && !reset.equals(vermerk)
                && !(reset.equals(angestossenFuer) && kuerzlich)) {
            return Optional.of(reset);
        }
        LocalDateTime fenster = offenesFenster(jetzt);
        if (fenster == null) return Optional.empty();
        if (fenster.equals(vermerk)) return Optional.empty();
        if (fenster.equals(angestossenFuer) && kuerzlich) return Optional.empty();
        return Optional.of(fenster);
    }

    public static synchronized void angestossen(LocalDateTime fenster) {
        angestossenFuer = fenster;
        angestossenUm = System.currentTimeMillis();
    }

    /** So lange gilt ein Anstoss als "dieser Besuch" (danach war es ein !gembay von Hand). */
    private static final long ANSTOSS_GILT_MS = 2 * 60 * 60_000L;

    /**
     * Fuer welches Fenster zaehlt der laufende Besuch? Der angestossene (Reset-Zeit oder
     * 02:00/12:00), wenn er frisch ist -- sonst wie faellig(): der Reset, dann das offene
     * Fenster. Null: ausserhalb jedes Fensters (!gembay von Hand), nichts zu vermerken.
     */
    static LocalDateTime besuchsFensterBei(LocalDateTime jetzt, LocalDateTime reset, LocalDateTime vermerk,
                                           LocalDateTime angestossenFuer, long msSeitAnstoss) {
        if (angestossenFuer != null && msSeitAnstoss < ANSTOSS_GILT_MS) return angestossenFuer;
        if (reset != null && !jetzt.isBefore(reset) && jetzt.isBefore(reset.plusHours(12)) && !reset.equals(vermerk)) {
            return reset;
        }
        return offenesFenster(jetzt);
    }

    private static LocalDateTime besuchsFenster() {
        return besuchsFensterBei(Zeitzone.jetzt(), resetZeit(), vermerk(), angestossenFuer,
                System.currentTimeMillis() - angestossenUm);
    }

    /** Fuer das Fenster dieses Besuchs erledigt (gespielt, nichts mehr zu tun, oder heute schon gespielt). */
    public static synchronized void erledigt() {
        LocalDateTime fenster = besuchsFenster();
        if (fenster != null) vermerkSchreiben(fenster);
    }

    private static void vermerkSchreiben(LocalDateTime fenster) {
        try {
            Files.writeString(VERMERK, fenster.toString());
        } catch (Exception e) {
            Log.debug("Gem Bay", "Vermerk nicht geschrieben: " + e.getMessage());
        }
    }

    // --- Fehlversuche je Fenster (Pruefung 04.10., P4) ---
    /** So viele abgebrochene Besuche je Fenster, dann ist es fuer dieses Fenster erledigt. */
    static final int MAX_FEHLVERSUCHE = 2;
    private static final Path FEHL_DATEI = Path.of("gembay-fehlversuche.txt");

    /**
     * Ein Besuch wurde abgebrochen (Zeitgrenze, Bildschirm unbekannt, Weltkarte, Preis unlesbar).
     * Der erste Fehlversuch darf nach WIEDER_MS noch einmal (die Reise kann offen sein), beim
     * zweiten ist das Fenster erledigt. Der Zaehler steht in einer Datei, damit ihn der
     * Spiel-Neustart alle 2 Stunden nicht verliert.
     *
     * @return wie viele Fehlversuche es in diesem Fenster jetzt sind; 0 = ausserhalb jedes Fensters
     */
    public static synchronized int fehlschlag() {
        LocalDateTime fenster = besuchsFenster();
        if (fenster == null) return 0;
        String alt = null;
        try {
            if (Files.exists(FEHL_DATEI)) alt = Dateien.text(FEHL_DATEI);
        } catch (Exception e) {
            Log.debug("Gem Bay", "Fehlversuche nicht lesbar: " + e.getMessage());
        }
        int n = fehlversucheAus(alt, fenster) + 1;
        try {
            Files.writeString(FEHL_DATEI, fehlversuchZeile(fenster, n));
        } catch (Exception e) {
            Log.debug("Gem Bay", "Fehlversuche nicht geschrieben: " + e.getMessage());
        }
        if (fensterZu(n)) vermerkSchreiben(fenster);
        return n;
    }

    /** Nach so vielen Fehlversuchen ist das Fenster erledigt. */
    static boolean fensterZu(int fehlversuche) {
        return fehlversuche >= MAX_FEHLVERSUCHE;
    }

    /** "2026-10-05T12:00 1": Fenster und Zahl der Fehlversuche. */
    static String fehlversuchZeile(LocalDateTime fenster, int n) {
        return fenster + " " + n + "\n";
    }

    /** Fehlversuche fuer dieses Fenster aus dem Dateiinhalt; ein anderes Fenster zaehlt nicht. */
    static int fehlversucheAus(String inhalt, LocalDateTime fenster) {
        if (inhalt == null || fenster == null) return 0;
        String[] t = inhalt.trim().split("\\s+");
        if (t.length != 2 || !t[0].equals(fenster.toString())) return 0;
        try {
            return Math.max(0, Integer.parseInt(t[1]));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDateTime vermerk() {
        try {
            if (!Files.exists(VERMERK)) return null;
            String t = Dateien.text(VERMERK).trim();
            return t.isEmpty() ? null : LocalDateTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime offenesFenster(LocalDateTime jetzt) {
        for (LocalTime zeit : ZEITEN) {
            LocalDateTime start = jetzt.toLocalDate().atTime(zeit);
            if (!jetzt.isBefore(start) && jetzt.isBefore(start.plus(FENSTER))) return start;
        }
        return null;
    }

    // --- "Restore all units" (Video) nur einmal am Tag, Reset 02:00 (Autor, 29.09.) ---
    private static final Path WATCH_VERMERK = Path.of("gembay-watch.txt");

    /** Der Gem-Bay-Tag: ab 02:00 zaehlt der neue Tag. */
    private static java.time.LocalDate tag() {
        return Zeitzone.jetzt().minusHours(2).toLocalDate();
    }

    /** Wurde "Restore all units" an diesem Gem-Bay-Tag schon benutzt? */
    public static synchronized boolean watchBenutzt() {
        try {
            return Files.exists(WATCH_VERMERK) && Dateien.text(WATCH_VERMERK).trim().equals(tag().toString());
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void watchVermerken() {
        try {
            Files.writeString(WATCH_VERMERK, tag().toString());
        } catch (Exception e) {
            Log.debug("Gem Bay", "Watch-Vermerk nicht geschrieben: " + e.getMessage());
        }
    }

    /** Eine Zeile fuer !settings. */
    public static String beschreibung() {
        LocalDateTime reset = resetZeit();
        return (an ? "an" : "aus") + (reset != null && Zeitzone.jetzt().isBefore(reset)
                ? " (naechster Reset " + reset.toLocalTime().withSecond(0) + ", dann geht der Bot hin)"
                : " (nach dem Reset, sonst 02:00 und 12:00, nach dem Titanen-Turm)");
    }
}
