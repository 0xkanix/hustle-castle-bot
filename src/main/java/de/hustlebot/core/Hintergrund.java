package de.hustlebot.core;

import com.sun.jna.platform.win32.WinDef;

/**
 * Schalter und Wache des Hintergrund-Modus (Autor 20.11.): liegt im Bot-Ordner hintergrund.txt mit "an", spielt der Bot
 * unsichtbar im Hintergrund ({@link Geist}, {@link HintergrundSteuerung}). Ohne Datei oder mit "aus": alles wie bisher.
 * Die Datei wird alle {@link #NEU_LESEN_MS} neu gelesen -- umschalten geht also ohne Neustart.
 *
 * Die Wache (eigener Faden, alle {@link #WACHE_MS}):
 *   - Schalter aus, Spiel noch Geist: Geist aufheben (Spiel wieder sichtbar bei 0,0);
 *   - Autor oeffnet das Spiel (Taskleiste, Alt+Tab: es wird das aktive Fenster, oder es wird minimiert, waehrend es
 *     vorne war): Bot pausieren, Spiel normal bei 0,0 zeigen. Erst wenn der Bot wieder laeuft (!resume oder neuer
 *     Modus), wird es wieder unsichtbar;
 *   - sonst minimiert (z. B. Win+D): sofort wiederherstellen, ohne es zu aktivieren -- minimiert gibt es kein Bild.
 * Bei !stop und beim Bot-Ende wird der Geist immer aufgehoben.
 */
public final class Hintergrund {

    private Hintergrund() {
    }

    public static final String DATEI = "hintergrund.txt";
    static final long NEU_LESEN_MS = 2_000;
    static final long WACHE_MS = 250;

    private static volatile Boolean fest = null;
    private static volatile boolean wert = false;
    private static volatile long gelesenUm = 0;
    private static volatile boolean nutzerHatSpiel = false;
    private static volatile WinDef.HWND spiel = null;
    private static volatile Runnable beiAutor = null;
    private static Thread wache;
    private static boolean warVorne = false;   // nur der Wache-Faden

    /** Ist der Hintergrund-Modus an? (Datei hintergrund.txt, hoechstens alle 2 s gelesen.) */
    public static boolean an() {
        Boolean f = fest;
        if (f != null) return f;
        long jetzt = System.currentTimeMillis();
        if (gelesenUm == 0 || jetzt - gelesenUm >= NEU_LESEN_MS) {
            boolean neu = new Schalterdatei(Ordner.bei(DATEI), "Hintergrund", false).lesen();
            if (gelesenUm != 0 && neu != wert) {
                Log.info("Hintergrund", neu
                        ? Sprache.t("Background mode switched ON (hintergrund.txt).", "Hintergrund-Modus EIN (hintergrund.txt).")
                        : Sprache.t("Background mode switched OFF (hintergrund.txt).", "Hintergrund-Modus AUS (hintergrund.txt)."));
            }
            wert = neu;
            gelesenUm = jetzt;
        }
        return wert;
    }

    /** Fuer Werkzeug und Tests: fest an/aus; null = wieder aus der Datei. */
    public static void festlegen(Boolean an) {
        fest = an;
    }

    /** Hat Autor das Spiel geoeffnet (Bot pausiert, Spiel sichtbar)? Dann fasst der Hintergrund-Modus es nicht an. */
    public static boolean nutzerHatSpiel() {
        return nutzerHatSpiel;
    }

    /** Das Spielfenster, mit dem der Bot gerade arbeitet (GameWindow meldet es beim Finden). */
    public static void spielFensterMerken(WinDef.HWND h) {
        if (h != null) spiel = h;
    }

    public static WinDef.HWND spielFenster() {
        return spiel;
    }

    /**
     * Startet die Wache. beiAutor wird gerufen, wenn Autor das Spiel oeffnet (Kern: Bot pausieren, Nachricht).
     * Raeumt vorher Geister eines abgestuerzten Laufs weg, falls der Schalter aus ist.
     */
    public static synchronized void starten(Runnable beiAutor) {
        Hintergrund.beiAutor = beiAutor;
        if (wache != null) return;
        if (!an()) Geist.allesAufheben();
        wache = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(WACHE_MS);
                    wachen();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    // die Wache darf nie stoeren
                }
            }
        }, "hintergrund-wache");
        wache.setDaemon(true);
        wache.start();
    }

    /** Was die Wache tun soll. */
    enum Schritt { NICHTS, AUTOR_OEFFNET, WIEDERHERSTELLEN }

    /**
     * Die Entscheidung der Wache (rein, pruefbar): Spiel ist ein Geist; vorne = es ist jetzt das aktive Fenster,
     * warVorne = es war es beim letzten Blick, klein = minimiert.
     */
    static Schritt entscheiden(boolean vorne, boolean warVorne, boolean klein) {
        if (vorne && !warVorne) return Schritt.AUTOR_OEFFNET;       // Taskleiste/Alt+Tab
        if (klein && warVorne) return Schritt.AUTOR_OEFFNET;        // war vorne, Taskleiste geklickt -> minimiert
        if (klein) return Schritt.WIEDERHERSTELLEN;                  // z. B. Win+D: nie minimiert lassen
        return Schritt.NICHTS;
    }

    static void wachen() {
        Mauslauscher.aufraeumen();   // Maushaken nach 10 s ohne Tipp abmelden
        WinDef.HWND h = spiel;
        if (!Geist.lebt(h)) {
            warVorne = false;
            return;
        }
        if (!an()) {
            if (Geist.istGeist(h)) Geist.aus(h, "background mode switched off", "Hintergrund-Modus ausgeschaltet");
            warVorne = false;
            return;
        }
        if (nutzerHatSpiel) return;
        boolean vorne = Geist.istVorne(h);
        if (!Geist.istGeist(h)) {      // noch kein Geist (der Bot macht es beim naechsten Bild dazu): nur mitschauen
            warVorne = vorne;
            return;
        }
        switch (entscheiden(vorne, warVorne, Geist.minimiert(h))) {
            case AUTOR_OEFFNET -> autorOeffnet(h);
            case WIEDERHERSTELLEN -> Geist.ruheSicherstellen(h);
            default -> warVorne = vorne;
        }
    }

    private static void autorOeffnet(WinDef.HWND h) {
        nutzerHatSpiel = true;
        warVorne = false;
        Log.info("Hintergrund", Sprache.t("Background mode: you opened the game -- the bot pauses, the game is shown normally.",
                "Hintergrund-Modus: du hast das Spiel geoeffnet -- der Bot pausiert, das Spiel wird normal gezeigt."));
        Geist.zeigenFuerAutor(h);
        Runnable r = beiAutor;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // die Nachricht ist nur ein Hinweis
            }
        }
    }

    /** Der Bot laeuft (wieder): das Spiel darf wieder unsichtbar werden (BotEngine, Zustand LAEUFT). */
    public static void botLaeuft() {
        if (!nutzerHatSpiel) return;
        nutzerHatSpiel = false;
        Log.info("Hintergrund", Sprache.t("Background mode: the bot runs again -- the game becomes invisible again.",
                "Hintergrund-Modus: der Bot laeuft wieder -- das Spiel wird wieder unsichtbar."));
    }

    /** !stop (BotEngine, Zustand GESTOPPT): Geist aufheben -- das Spiel ist wieder normal bei 0,0. */
    public static void nachStopp() {
        WinDef.HWND h = spiel;
        if (h == null) return;
        if (Geist.istGeist(h)) Geist.aus(h, "bot stopped", "Bot gestoppt");
    }

    /** Bot-Ende (Shutdown-Hook): Geist aufheben, auch bei Resten. */
    public static void beimEnde() {
        try {
            Mauslauscher.abmelden();
            nachStopp();
            Geist.allesAufheben();
        } catch (Throwable ignored) {
            // beim Beenden nichts mehr aufhalten
        }
    }
}
