package de.hustlebot.core;

import java.nio.file.Path;

/**
 * Autors Arena-Neuerungen vom 28.10. -- Arena-Schicht und Seitenfenster, Namensschild vor jedem Angriff, selbst
 * eingestufte Gegner als Bild in den Listen, "der Ordner entscheidet". Autor: "erstmal nur fuer meinen englischen
 * Bot". Darum nur mit arena-schicht.txt "an" im Bot-Ordner; Standard aus, die Freunde merken nichts davon.
 */
public final class ArenaSchalter {

    private ArenaSchalter() {}

    private static final boolean AN =
            new Schalterdatei(Path.of("arena-schicht.txt"), "Arena-Neuerungen", false).lesen();

    /** Nur fuer Tests: ueberschreibt den Schalter (null = wieder die Datei). */
    private static volatile Boolean fuerTest;

    /** Sind die Arena-Neuerungen an? (Datei gelesen beim Start) */
    public static boolean an() {
        Boolean t = fuerTest;
        return t != null ? t : AN;
    }

    static void fuerTest(Boolean an) {
        fuerTest = an;
    }

    /** Fuer Tests aus anderen Paketen. */
    public static void testWert(Boolean an) {
        fuerTest = an;
    }
}
