package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Wann der Bot in den Titanen-Turm ("Tower") geht -- und ob ueberhaupt.
 *
 * Autor, 27.09.: "Immer wenn der Bot um 2 Uhr nachts aktiv ist, soll er den
 * aktuellen Run beenden und dann ... auf Tower klicken", dort Easy, Hard und
 * Very Hard je 30 Kaempfe im Auto-Kampf. "Wenn der Bot um 12:00 mittags aktiv
 * ist, soll er sich einmal anschauen", ob alles fertig ist -- sonst die
 * fehlenden Tuerme nachholen.
 *
 * Wie beim Clan-Krieg: Jede Zeit ist der Anfang eines Fensters. Solange es
 * offen ist und ein Modus laeuft, wird der Turm nach dem laufenden Run
 * eingeschoben. Ist er fuer dieses Fenster erledigt, steht das in einer Datei
 * und gilt auch nach einem Neustart.
 *
 * Einstellungen (!settings titanturm ...): an/aus und je Turm das hoechste
 * Level, bis zu dem der Bot kaempft (Standard 30 = alles).
 */
public final class Turmplan {

    private Turmplan() {}

    /** Die beiden Zeiten (Autor). */
    private static final List<LocalTime> ZEITEN = List.of(LocalTime.of(2, 0), LocalTime.of(12, 0));
    /** So lange nach der Zeit wird der Turm noch eingeschoben -- endet vor dem Clan-Krieg um 03:01. */
    private static final Duration FENSTER = Duration.ofMinutes(55);
    /** Nach einem gescheiterten Versuch so lange warten, bevor es im selben Fenster wieder versucht wird. */
    private static final long WIEDER_MS = 20 * 60_000L;

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("titanturm.txt"), "Titanen-Turm", true);
    private static volatile boolean an = SCHALTER.lesen();
    private static final Path VERMERK = Path.of("titanturm-erledigt.txt");
    private static final Path MAXIMA = Path.of("titanturm-max.txt");

    /** Die drei Tuerme, in dieser Reihenfolge. */
    public static final List<String> TUERME = List.of("easy", "hard", "veryhard");
    private static final int[] maximum = maximaLaden();

    private static volatile LocalDateTime angestossenFuer;
    private static volatile long angestossenUm;

    public static boolean istAn() { return an; }

    public static void setAn(boolean neu) {
        an = neu;
        SCHALTER.setzen(neu, "Titanen-Turm um 02:00 und 12:00 (an/aus), gesetzt mit !settings titanturm");
    }

    /** Hoechstes Level, bis zu dem im Turm gekaempft wird (1..30). */
    public static int maximum(String turm) {
        int i = TUERME.indexOf(turm);
        return i < 0 ? 30 : maximum[i];
    }

    public static synchronized void setMaximum(String turm, int level) {
        int i = TUERME.indexOf(turm);
        if (i < 0) return;
        maximum[i] = Math.max(1, Math.min(30, level));
        StringBuilder b = new StringBuilder("# Titanen-Turm: bis zu welchem Level gekaempft wird "
                + "(gesetzt mit !settings titanturm <easy|hard|veryhard> <1-30>)\n");
        for (int k = 0; k < TUERME.size(); k++) b.append(TUERME.get(k)).append('=').append(maximum[k]).append('\n');
        try {
            Files.writeString(MAXIMA, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Titanen-Turm", Sprache.t("Maxima could not be saved: " + e.getMessage(),
                    "Maxima liessen sich nicht speichern: " + e.getMessage()));
        }
    }

    private static int[] maximaLaden() {
        int[] m = {30, 30, 30};
        try {
            if (!Files.exists(MAXIMA)) return m;
            for (String z : Dateien.zeilen(MAXIMA)) {
                z = z.trim();
                if (z.isEmpty() || z.startsWith("#") || !z.contains("=")) continue;
                String[] t = z.split("=");
                int i = TUERME.indexOf(t[0].trim().toLowerCase());
                if (i >= 0) m[i] = Math.max(1, Math.min(30, Integer.parseInt(t[1].trim())));
            }
        } catch (Exception e) {
            Log.warn("Titanen-Turm", Sprache.t("titanturm-max.txt unreadable, 30 applies for all towers.",
                    "titanturm-max.txt unlesbar, es gilt 30 fuer alle Tuerme."));
        }
        return m;
    }

    /** Ist der Turm jetzt dran? Liefert den Anfang des offenen Fensters. */
    public static synchronized Optional<LocalDateTime> faellig() {
        if (!an) return Optional.empty();
        LocalDateTime fenster = offenesFenster(Zeitzone.jetzt());
        if (fenster == null) return Optional.empty();
        if (fenster.equals(vermerk())) return Optional.empty();
        if (fenster.equals(angestossenFuer) && System.currentTimeMillis() - angestossenUm < WIEDER_MS) {
            return Optional.empty();
        }
        return Optional.of(fenster);
    }

    public static synchronized void angestossen(LocalDateTime fenster) {
        angestossenFuer = fenster;
        angestossenUm = System.currentTimeMillis();
    }

    /** Alle Tuerme fuer das offene Fenster erledigt (oder bewusst aufgegeben). */
    public static synchronized void erledigt() {
        LocalDateTime fenster = offenesFenster(Zeitzone.jetzt());
        if (fenster == null) fenster = angestossenFuer;
        if (fenster == null) return;
        try {
            Files.writeString(VERMERK, fenster.toString());
        } catch (Exception e) {
            Log.debug("Titanen-Turm", "Vermerk nicht geschrieben: " + e.getMessage());
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

    /** Eine Zeile fuer !settings. */
    public static String beschreibung() {
        return Sprache.t((an ? "on" : "off") + " (02:00 and 12:00) -- highest level: Easy " + maximum[0] + ", Hard " + maximum[1] + ", Very Hard " + maximum[2], (an ? "an" : "aus") + " (02:00 und 12:00) -- hoechstes Level: Easy " + maximum[0]
                + ", Hard " + maximum[1] + ", Very Hard " + maximum[2]);
    }
}
