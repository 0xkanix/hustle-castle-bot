package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wie langsam dieser Rechner ist -- alle Wartezeiten des Bots werden damit malgenommen.
 *
 * Autor 29.10. abends: "Der VPS-Server, auf dem der Bot fuer den Clankrieg um 2 und 8 Uhr laeuft, ist bis zu 5-mal
 * langsamer als mein Computer. Wartezeiten ueberarbeiten -- behutsam, praezise. Es sollte aber nicht mehr als 10 Minuten
 * dauern." Auf dem Server steht darum eine Zahl in rechner-tempo.txt (z. B. 3); ohne Datei bleibt alles wie bisher (1).
 *
 * Es zaehlen nur Wartezeiten: feste Pausen (sleep), die Obergrenze beim Warten auf ein neues Bild (sleepUntilChanged
 * endet weiter, sobald sich das Bild aendert) und das Warten, bis ein Knopf weg ist.
 */
public final class Rechnertempo {

    /** Mehr als 6-mal langsamer wird nicht angenommen -- sonst wird aus einem Lauf eine Stunde. */
    static final double MAX = 6;

    private static volatile double faktor = laden(Ordner.bei("rechner-tempo.txt"));

    private Rechnertempo() {
    }

    public static double faktor() {
        return faktor;
    }

    /** Eine Wartezeit mit dem Faktor (0 und negative bleiben, wie sie sind). */
    public static long mal(long ms) {
        return ms <= 0 || faktor == 1.0 ? ms : Math.round(ms * faktor);
    }

    /** Erste Zahl der Datei (Kommentare mit #), auf 1..6 begrenzt; ohne Datei oder unlesbar: 1. */
    static double laden(Path datei) {
        try {
            if (datei == null || !Files.isRegularFile(datei)) return 1.0;
            for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                z = z.replace("﻿", "").trim();
                if (z.isEmpty() || z.startsWith("#")) continue;
                double w = Double.parseDouble(z.split("\\s+")[0].replace(',', '.'));
                if (Double.isNaN(w)) return 1.0;
                double f = Math.max(1.0, Math.min(MAX, w));
                if (f != 1.0) Log.info("Rechner", Sprache.t("Slow computer: all waiting times x " + f + " (rechner-tempo.txt).",
                        "Langsamer Rechner: alle Wartezeiten x " + f + " (rechner-tempo.txt)."));
                return f;
            }
        } catch (Exception e) {
            Log.debug("Rechner", "rechner-tempo.txt nicht lesbar: " + e.getMessage());
        }
        return 1.0;
    }

    /** Nur fuer Tests. */
    static void setzen(double f) {
        faktor = f;
    }
}
