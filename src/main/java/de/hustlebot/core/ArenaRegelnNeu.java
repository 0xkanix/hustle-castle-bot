package de.hustlebot.core;

/**
 * Schalter fuer die Arena-Regeln, die auf Daten vom 16.-24.10. beruhen (Autor 28.10.): Datei arena-regeln-neu.txt im
 * Bot-Ordner, "an" oder "aus". Standard AUS -- die Freunde-Bots aendern sich nicht, Claude schaltet ihn bei Autor ein.
 * Gelesen wird die Datei einmal beim Start.
 *
 * Bisher haengt daran: das Listen-Netz ({@link ArenaListenNetz}) -- ein Spieler mit Listenbild (niemals, meiden, machbar,
 * sicher) wird auch am Namen seines Bildes erkannt, wenn das Bild nicht trifft; ein "niemals"-Spieler wird ausserdem
 * an der Notizblock-Bilanz (0 Siege aus 3) erkannt und im Zweig "verlieren kostet nichts" nie angegriffen.
 */
public final class ArenaRegelnNeu {

    private ArenaRegelnNeu() {}

    private static final boolean AN =
            new Schalterdatei(Ordner.bei("arena-regeln-neu.txt"), "Arena-Regeln-neu", false).lesen();

    /** Nur fuer Tests: ueberschreibt den Schalter (null = wieder die Datei). */
    private static volatile Boolean fuerTest;

    /** Sind die neuen Arena-Regeln an? (Datei gelesen beim Start) */
    public static boolean an() {
        Boolean t = fuerTest;
        return t != null ? t : AN;
    }

    /** Fuer Tests (auch aus anderen Paketen): ueberschreibt den Schalter, null = wieder die Datei. */
    public static void testWert(Boolean an) {
        fuerTest = an;
    }
}
