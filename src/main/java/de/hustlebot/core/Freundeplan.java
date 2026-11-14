package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Wann der Bot den Freunden Ressourcen schickt und ihre Geschenke abholt.
 *
 * Autor, 05.10.: "jeden Tag um 2 Uhr den Titanenturm spielen. Und dann gleich nach dem
 * Titanenturm in die Burg gehen ... Freunden Ressourcen senden."
 * Autor, 08.10.: "nur einmal am Tag einsammeln ... Einfach um 2 Uhr und um 12 Uhr nochmal.
 * Wie beim Titanenturm auch" -- um 12 Uhr "Beides: erst senden, was noch geht, dann abholen."
 *
 * Wie beim Titanen-Turm gibt es also ZWEI Fenster am Tag, 02:00 und 12:00. Jedes Fenster
 * gilt bis zum naechsten; erledigt wird je Fenster vermerkt (freunde-senden-erledigt.txt).
 * War der Bot um 02:00 aus, wird im 02:00-Fenster nachgeholt, sobald es geht.
 * Nach einem Gang ohne Ergebnis wird 20 Minuten gewartet, hoechstens 3 Versuche je Fenster.
 */
public final class Freundeplan {

    private Freundeplan() {}

    static final long WIEDER_MS = 20 * 60_000L;
    static final int MAX_VERSUCHE = 3;
    /** Die beiden Fenster (Autor, 08.10.) -- das erste ist auch der Reset des Spiels. */
    static final List<LocalTime> ZEITEN = List.of(LocalTime.of(2, 0), LocalTime.of(12, 0));
    private static final LocalTime RESET = LocalTime.of(2, 0);
    private static final LocalTime RESET_PUFFER_ENDE = LocalTime.of(2, 10);

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("freunde-senden.txt"), "Freunden senden", true);
    private static volatile boolean an = SCHALTER.lesen();
    private static final Path VERMERK = Path.of("freunde-senden-erledigt.txt");

    private static LocalDateTime angestossenFuer;
    private static long angestossenUm;
    private static int versuche;

    public static boolean istAn() { return an; }

    public static void setAn(boolean neu) {
        an = neu;
        SCHALTER.setzen(neu, "Freunden Ressourcen senden und abholen, um 02:00 und 12:00 nach dem "
                + "Titanen-Turm (an/aus), gesetzt mit !settings freunde");
    }

    /** Das Fenster, in dem t liegt: heute 02:00, heute 12:00 oder (vor 02:00) gestern 12:00. */
    static LocalDateTime fenster(LocalDateTime t) {
        LocalDate tag = t.toLocalDate();
        if (t.toLocalTime().isBefore(ZEITEN.get(0))) return tag.minusDays(1).atTime(ZEITEN.get(1));
        if (t.toLocalTime().isBefore(ZEITEN.get(1))) return tag.atTime(ZEITEN.get(0));
        return tag.atTime(ZEITEN.get(1));
    }

    public static LocalDateTime fenster() {
        return fenster(Zeitzone.jetzt());
    }

    /** Der Spieltag (Reset 02:00) -- fuer Anzeigen. */
    public static LocalDate spieltag() {
        return Zeitzone.jetzt().minusHours(RESET.getHour()).toLocalDate();
    }

    /** Ist das Senden jetzt dran? Liefert das offene Fenster. */
    public static synchronized Optional<LocalDateTime> faellig() {
        return pruefen(an, Zeitzone.jetzt(), vermerk(), angestossenFuer,
                System.currentTimeMillis() - angestossenUm, versuche);
    }

    /** Die reine Regel, ohne Uhr und Dateien (fuer den Test). */
    static Optional<LocalDateTime> pruefen(boolean an, LocalDateTime jetzt, LocalDateTime erledigtFuer,
                                           LocalDateTime angestossenFuer, long seitAnstossMs, int versuche) {
        if (!an) return Optional.empty();
        LocalDateTime f = fenster(jetzt);
        if (f.equals(erledigtFuer)) return Optional.empty();
        if (f.equals(angestossenFuer) && (seitAnstossMs < WIEDER_MS || versuche >= MAX_VERSUCHE)) {
            return Optional.empty();
        }
        return Optional.of(f);
    }

    public static synchronized void angestossen(LocalDateTime f) {
        if (!f.equals(angestossenFuer)) versuche = 0;   // neues Fenster: wieder von vorn zaehlen
        angestossenFuer = f;
        angestossenUm = System.currentTimeMillis();
        versuche++;
    }

    /**
     * Das Fenster ist erledigt (oben 0, Limit-Meldung, oder kein Send-Knopf mehr). Kurz nach dem
     * Reset um 02:00 kann die 0 noch der Stand von gestern sein -- dann nicht vermerken.
     */
    public static synchronized boolean erledigt(LocalDateTime f, int gesendet) {
        if (gesendet == 0 && kurzNachReset(Zeitzone.jetzt())) {
            Log.info("Freunden senden", Sprache.t("The counter showed 0 shortly after the reset -- maybe still "
                    + "yesterday's. Looking again in 20 minutes.",
                    "Der Zaehler stand kurz nach dem Reset auf 0 -- vielleicht noch der "
                    + "Stand von gestern. In 20 Minuten wird noch einmal nachgesehen."));
            return false;
        }
        try {
            Files.writeString(VERMERK, f.toString());
            return true;
        } catch (Exception e) {
            Log.debug("Freunden senden", "Vermerk nicht geschrieben: " + e.getMessage());
            return false;
        }
    }

    static boolean kurzNachReset(LocalDateTime t) {
        LocalTime u = t.toLocalTime();
        return !u.isBefore(RESET) && u.isBefore(RESET_PUFFER_ENDE);
    }

    private static LocalDateTime vermerk() {
        try {
            if (!Files.exists(VERMERK)) return null;
            String t = Dateien.text(VERMERK).trim();
            if (t.isEmpty()) return null;
            // Alter Vermerk (bis 07.10.) war nur ein Datum: zaehlt fuer das 02:00-Fenster dieses Tages.
            return t.length() <= 10 ? LocalDate.parse(t).atTime(ZEITEN.get(0)) : LocalDateTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean jetztErledigt() {
        return fenster().equals(vermerk());
    }

    public static String beschreibung() {
        return (an ? "an" : "aus") + " (um 02:00 und 12:00 gleich nach dem Titanen-Turm: senden und "
                + "Geschenke abholen, sonst sobald moeglich; dieses Fenster: "
                + (jetztErledigt() ? "erledigt" : "offen") + ")";
    }
}
