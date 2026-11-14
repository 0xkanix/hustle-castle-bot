package de.hustlebot.discord;

import de.hustlebot.core.FensterFoto;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import de.hustlebot.core.Sprache;

/**
 * Ein Bild NUR vom Spielfenster -- fuer !screenshot, !live und !fix (16.10.).
 *
 * Bisher kam dort der Bildschirmbereich des Spiels: Lag Discord oder ein
 * Browser darueber, war der im Bild. {@link FensterFoto} laesst das Fenster
 * selbst zeichnen (PrintWindow), da ist nur Hustle Castle drauf.
 *
 * Geht das nicht (Fenster weg, minimiert, Griff veraltet), kommt null -- der
 * Aufrufer nimmt dann den bisherigen Weg. Das steht einmal im Protokoll,
 * nicht bei jedem Livebild-Takt.
 */
public final class Spielfoto implements AutoCloseable {

    private final FensterFoto foto = new FensterFoto();
    /** Ersatz schon gemeldet? Wird nach dem naechsten Erfolg wieder frei. */
    private boolean ersatzGemeldet = false;

    /** Das Spielfenster als Bild, oder null, wenn es gerade nicht geht. */
    public synchronized BufferedImage aufnehmen(GameWindow fenster) {
        String grund;
        try {
            if (fenster == null || fenster.handle() == null) {
                grund = Sprache.t("no window handle", "kein Fenstergriff");
            } else {
                Rectangle r = fenster.clientArea();
                if (r == null || r.width <= 0 || r.height <= 0) {
                    grund = Sprache.t("window size unknown", "Fenstergroesse unbekannt");
                } else {
                    byte[] bgra = foto.bgra(fenster.handle(), r.width, r.height);
                    if (bgra != null) {
                        ersatzGemeldet = false;
                        return bgraZuBild(bgra, r.width, r.height);
                    }
                    grund = Sprache.t("PrintWindow failed (window minimized?)", "PrintWindow fehlgeschlagen (Fenster minimiert?)");
                }
            }
        } catch (Throwable t) {
            grund = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (!ersatzGemeldet) {
            ersatzGemeldet = true;
            Log.info("Discord", Sprache.t("Window photo not possible (" + grund + ") -- using the screen area instead.",
                    "Fensterfoto nicht moeglich (" + grund + ") -- es wird der Bildschirmbereich genommen."));
        } else {
            Log.debug("Discord", "Fensterfoto nicht moeglich (" + grund + ").");
        }
        return null;
    }

    /** BGRA-Bytes (4 je Punkt, Zeilen von oben) -> Bild ohne Durchsichtigkeit. */
    static BufferedImage bgraZuBild(byte[] bgra, int breite, int hoehe) {
        BufferedImage bild = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_RGB);
        int[] ziel = ((DataBufferInt) bild.getRaster().getDataBuffer()).getData();
        int n = Math.min(ziel.length, bgra.length / 4);
        for (int i = 0, q = 0; i < n; i++, q += 4) {
            ziel[i] = ((bgra[q + 2] & 0xFF) << 16) | ((bgra[q + 1] & 0xFF) << 8) | (bgra[q] & 0xFF);
        }
        return bild;
    }

    @Override
    public synchronized void close() {
        foto.close();
    }
}
