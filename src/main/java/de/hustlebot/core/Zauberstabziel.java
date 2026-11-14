package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zauberstab-Grenze im Hammer-Event (Autor, 29.09.): "bis 1000 Zauberstaebe Hammer
 * benutzen, danach wieder Kathedrale". Gesetzt mit {@code !settings zauberstaebe 1000},
 * aus mit {@code !settings zauberstaebe aus}. Die Zahl liest das Event im Reiter
 * "Rankings" ab (eigene Zeile). Ist sie erreicht, verlaesst der Bot das Event und
 * geht nicht wieder hinein, bis Autor die Grenze aendert oder ausschaltet.
 */
public final class Zauberstabziel {

    private Zauberstabziel() {}

    private static final Path DATEI = Path.of("zauberstaebe.txt");

    /** Grenze, 0 = aus. */
    private static volatile long ziel = laden();
    /** Zuletzt abgelesener Stand, -1 = noch nicht gelesen (seit Start oder seit neuer Grenze). */
    private static volatile long stand = -1;

    public static long ziel() {
        return ziel;
    }

    public static long stand() {
        return stand;
    }

    /** Grenze gesetzt und schon erreicht (abgelesen). */
    public static boolean erreicht() {
        return ziel > 0 && stand >= ziel;
    }

    /** Neue Grenze (0 = aus). Der alte Stand gilt dann nicht mehr als "erreicht". */
    public static void setZiel(long neu) {
        ziel = Math.max(0, neu);
        stand = -1;
        try {
            Files.writeString(DATEI, "# Zauberstab-Grenze im Hammer-Event (0 = aus) -- gesetzt mit !settings zauberstaebe\n"
                    + ziel + "\n");
        } catch (Exception e) {
            Log.warn("Zauberstaebe", Sprache.t("zauberstaebe.txt not written: " + e.getMessage(),
                    "zauberstaebe.txt nicht geschrieben: " + e.getMessage()));
        }
        Log.info("Zauberstaebe", beschreibung());
    }

    /** Vom Event abgelesener Stand. */
    public static void merken(long n) {
        stand = n;
    }

    public static String beschreibung() {
        if (ziel <= 0) return Sprache.t("Wand limit off -- the event runs as configured.", "Zauberstab-Grenze aus -- das Event laeuft wie eingestellt.");
        String s = Sprache.t("Hammer Event until " + ziel + " wands, then Cathedral.", "Hammer-Event bis " + ziel + " Zauberstaebe, danach Kathedrale.");
        if (stand >= 0) s += Sprache.t(" Last read: " + stand + (erreicht() ? " -- reached." : "."), " Zuletzt abgelesen: " + stand + (erreicht() ? " -- erreicht." : "."));
        return s;
    }

    private static long laden() {
        try {
            if (!Files.exists(DATEI)) return 0;
            for (String z : Dateien.zeilen(DATEI)) {
                String t = z.trim();
                if (t.matches("\\d{1,9}")) return Long.parseLong(t);
            }
        } catch (Exception e) {
            // aus
        }
        return 0;
    }
}
