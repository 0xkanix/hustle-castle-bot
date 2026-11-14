package de.hustlebot.steuer;

import de.hustlebot.core.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import de.hustlebot.core.Sprache;

/**
 * Hoert im ganzen Rechner auf die Tastenkuerzel des Steuerprogramms -- derselbe Weg
 * wie beim Not-Aus (NotAusWache): die Tasten werden mit GetAsyncKeyState abgefragt,
 * ohne Fokus, in einem eigenen kleinen Faden.
 *
 * Eine Kombination gilt als gedrueckt, wenn ALLE ihre Tasten gleichzeitig unten
 * sind; ausgeloest wird einmal je Druck (erst wieder, wenn eine Taste losgelassen
 * wurde). Die Kuerzel werden bei jedem Durchgang frisch aus Tastenkuerzel gelesen,
 * damit eine Aenderung in den Einstellungen sofort gilt.
 *
 * Nur unter Windows -- auf anderen Systemen (und in der Cloud) tut die Wache nichts.
 */
public final class Tastenwache implements Runnable {

    private final Tastenkuerzel kuerzel;
    private final Consumer<Tastenkuerzel.Aktion> beiKuerzel;
    private volatile boolean laufen = true;
    private final Set<Tastenkuerzel.Aktion> gehalten = new HashSet<>();

    public Tastenwache(Tastenkuerzel kuerzel, Consumer<Tastenkuerzel.Aktion> beiKuerzel) {
        this.kuerzel = kuerzel;
        this.beiKuerzel = beiKuerzel;
    }

    /** Startet die Wache als Daemon-Faden. Liefert null, wenn es kein Windows ist. */
    public static Tastenwache starten(Tastenkuerzel kuerzel, Consumer<Tastenkuerzel.Aktion> beiKuerzel) {
        if (!com.sun.jna.Platform.isWindows()) {
            Log.info("Tastenkuerzel", Sprache.t("Not Windows -- the control window's hotkeys are off.",
                    "Kein Windows -- die Tastenkuerzel des Steuerprogramms sind aus."));
            return null;
        }
        Tastenwache w = new Tastenwache(kuerzel, beiKuerzel);
        Thread t = new Thread(w, "tastenkuerzel-wache");
        t.setDaemon(true);
        t.start();
        return w;
    }

    public void stoppen() {
        laufen = false;
    }

    @Override
    public void run() {
        Log.info("Tastenkuerzel", Sprache.t("Hotkeys active: " + beschreibung(), "Tastenkuerzel aktiv: " + beschreibung()));
        while (laufen) {
            try {
                List<Tastenkuerzel.Eintrag> wirksame = kuerzel.wirksame();
                for (Tastenkuerzel.Eintrag e : wirksame) {
                    boolean alleUnten = alleGedrueckt(e.kombination().codes());
                    if (alleUnten && !gehalten.contains(e.aktion())) {
                        gehalten.add(e.aktion());
                        Log.info("Tastenkuerzel", e.kombination().anzeige() + de.hustlebot.core.Sprache.t(" pressed: ", " gedrueckt: ") + e.aktion().anzeige);
                        try {
                            beiKuerzel.accept(e.aktion());
                        } catch (Exception ex) {
                            Log.warn("Tastenkuerzel", Sprache.t(e.aktion().anzeige + " failed: " + ex.getMessage(),
                                    e.aktion().anzeige + " ging nicht: " + ex.getMessage()));
                        }
                    } else if (!alleUnten) {
                        gehalten.remove(e.aktion());
                    }
                }
                Thread.sleep(60);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static boolean alleGedrueckt(List<Integer> codes) {
        for (int vk : codes) {
            if ((com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) == 0) return false;
        }
        return true;
    }

    private String beschreibung() {
        StringBuilder b = new StringBuilder();
        for (Tastenkuerzel.Eintrag e : kuerzel.wirksame()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.kombination().anzeige()).append(" = ").append(e.aktion().anzeige);
        }
        return b.length() == 0 ? Sprache.t("none", "keine") : b.toString();
    }
}
