package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Wann der Bot im Clan-Laden ("Royal Store") Food chests kauft (Autor, 27.09.):
 * "genau wie beim Turm einmal um 2 Uhr nachts kaufen, nach dem Turm, und einmal
 * um 12 Uhr reinschauen, ob man noch Kisten kaufen kann, auch nach dem Turm.
 * Turm geht vor." Die Kisten setzen sich jeden Tag um 2 Uhr zurueck.
 *
 * Zusaetzlich kauft der Nahrungskisten-Gang vor dem Clan-Krieg, wenn die
 * Nahrung fehlt und keine Kiste in der Tasche liegt -- das steht nicht hier.
 *
 * Gebaut wie Turmplan: Jede Zeit ist der Anfang eines Fensters; ist der Kauf
 * dafuer erledigt, steht das in einer Datei und gilt auch nach einem Neustart.
 */
public final class Ladenplan {

    private Ladenplan() {}

    private static final List<LocalTime> ZEITEN = List.of(LocalTime.of(2, 0), LocalTime.of(12, 0));
    /** Endet vor dem Clan-Krieg um 03:01 (wie beim Turm). */
    private static final Duration FENSTER = Duration.ofMinutes(55);
    /** Nach einem gescheiterten Versuch so lange warten. */
    private static final long WIEDER_MS = 20 * 60_000L;

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("clanladen.txt"), "Clan-Laden", false);   // Freunde: Standard aus (gibt Clan-Muenzen aus)
    private static volatile boolean an = SCHALTER.lesen();
    private static final Path VERMERK = Path.of("clanladen-erledigt.txt");

    private static volatile LocalDateTime angestossenFuer;
    private static volatile long angestossenUm;

    public static boolean istAn() { return an; }

    public static void setAn(boolean neu) {
        an = neu;
        SCHALTER.setzen(neu, "Food chests im Clan-Laden um 02:00 und 12:00 kaufen (an/aus), gesetzt mit !settings clanladen");
    }

    /** Ist der Kauf jetzt dran? Liefert den Anfang des offenen Fensters. */
    public static synchronized Optional<LocalDateTime> faellig() {
        if (!an) return Optional.empty();
        LocalDateTime fenster = offenesFenster(Zeitzone.jetzt());
        if (fenster == null || fenster.equals(vermerk())) return Optional.empty();
        if (fenster.equals(angestossenFuer) && System.currentTimeMillis() - angestossenUm < WIEDER_MS) {
            return Optional.empty();
        }
        return Optional.of(fenster);
    }

    public static synchronized void angestossen(LocalDateTime fenster) {
        angestossenFuer = fenster;
        angestossenUm = System.currentTimeMillis();
    }

    /** Fuer das offene Fenster erledigt (gekauft, ausverkauft oder zu wenig Clan-Muenzen). */
    public static synchronized void erledigt() {
        LocalDateTime fenster = offenesFenster(Zeitzone.jetzt());
        if (fenster == null) fenster = angestossenFuer;
        if (fenster == null) return;
        try {
            Files.writeString(VERMERK, fenster.toString());
        } catch (Exception e) {
            Log.debug("Clan-Laden", "Vermerk nicht geschrieben: " + e.getMessage());
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
        return Sprache.t((an ? "on" : "off") + " (02:00 and 12:00 after the tower, and when food is short before the clan war)", (an ? "an" : "aus") + " (02:00 und 12:00 nach dem Turm, und wenn vor dem Clan-Krieg Nahrung fehlt)");
    }
}
