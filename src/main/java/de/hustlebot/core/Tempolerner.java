package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Autor 24.10.: "das gleiche Prinzip wie beim Stigmas loeschen -- pro abgeschlossene Stage um 0,1 % schneller. Und wenn
 * du das maximale Tempo erreicht hast, nimmst du das Tempo 1 vor der maximalen Stufe." (Hammer-Event; danach auch die
 * Arena: "pro abgeschlossene Arena um 0,1 % schneller".)
 *
 * Alle Pausen eines Modus werden mit {@link #faktor()} malgenommen (1 = wie bisher, 0,9 = 10 % schneller). Nach jeder
 * fehlerfreien Runde (Stage, Arena) wird der Faktor 0,1 % kleiner. Beim ersten Fehler gilt das letzte fehlerfreie Tempo
 * als das schnellste -- der Bot nimmt dann eine Stufe davor (0,1 % langsamer) und bleibt dort ("fest"). Gemerkt wird in
 * einer Datei im Bot-Ordner: "0,9512" = Suche laeuft weiter, "0,9531 fest" = gefunden.
 */
public final class Tempolerner {
    /** Autors Schritt: 0,1 % je fehlerfreier Runde. */
    static final double SCHRITT = 0.999;
    /** Nie schneller als halbe Pausen (Sicherheit, wie FAKTOR_MIN bei den Stigmas). */
    static final double MIN = 0.5;

    private final Path datei;
    private final String wer;
    /** Faktor je fehlerfreier Runde (SCHRITT = 0,1 %; die Hammer-Event-Ausnahme 1 %). */
    private final double schritt;
    private double faktor = 1.0;
    private boolean fest;

    public Tempolerner(Path datei, String wer) {
        this(datei, wer, SCHRITT);
    }

    public Tempolerner(Path datei, String wer, double schritt) {
        this.datei = datei;
        this.wer = wer;
        this.schritt = schritt;
        laden();
    }

    /** Fuer den Bot-Ordner (Ordner.bei). */
    public static Tempolerner fuer(String dateiname, String wer) {
        return new Tempolerner(Ordner.bei(dateiname), wer);
    }

    /** Wie oben, mit eigenem Schritt (Autor 28.10.: Hammer-Event-Ausnahme "pro Stage 1 % schneller" = 0,99). */
    public static Tempolerner fuer(String dateiname, String wer, double schritt) {
        return new Tempolerner(Ordner.bei(dateiname), wer, schritt);
    }

    public synchronized double faktor() {
        return faktor;
    }

    public synchronized boolean fest() {
        return fest;
    }

    /**
     * Zaehlt diese Warnung als Tempo-Fehler? Autor 24.10.: in 70 von 445 Kathedralen-Runs stand eine Warnung, die zum
     * normalen Ablauf gehoert ("Nothing recognized ... after Run completed", "No waypoint ... clickable", "Best matches").
     * Haetten die gezaehlt, waere das Tempo nach wenigen Runs fest gewesen, ohne schneller zu werden. Als Fehler gilt nur,
     * was nach einem zu fruehen Klick aussieht: Klick nicht angekommen, ohne Wirkung, verschluckt, noch einmal gedrueckt.
     */
    public static boolean zaehltAlsFehler(String warnung) {
        if (warnung == null) return false;
        String t = warnung.toLowerCase(java.util.Locale.ROOT);
        for (String w : KLICK_FEHLER) if (t.contains(w)) return true;
        return false;
    }

    private static final String[] KLICK_FEHLER = {
            "did not register", "did not take", "nothing happened", "no effect", "without effect", "swallowed",
            "pressing it again", "press it again", "tapping differently", "no kind of tap", "tapping its known place",
            "nicht angekommen", "nicht gegriffen", "keine wirkung", "ohne wirkung", "wirkungslos", "verschluckt",
            "noch einmal gedrueckt", "anders tippen", "keine klickart", "an seiner bekannten stelle"};

    /** Eine Pause mit dem Faktor (mindestens 1 ms bei positiver Eingabe). */
    public long ms(long ms) {
        return ms <= 0 ? ms : Math.max(1, Math.round(ms * faktor()));
    }

    /** Nach jeder abgeschlossenen Runde (Stage, Arena): fehlerfrei -> 0,1 % schneller; Fehler -> eine Stufe zurueck, fest. */
    public synchronized void rundeFertig(boolean fehlerfrei) {
        if (fest) return;
        double alt = faktor;
        if (fehlerfrei) {
            faktor = Math.max(MIN, faktor * schritt);
            if (faktor <= MIN) fest = true;
        } else {
            // Das Tempo VOR dieser Runde war das letzte fehlerfreie (= schnellste); Autor: eine Stufe davor nehmen.
            faktor = Math.min(1.0, (faktor / schritt) / schritt);
            fest = true;
            Log.info(wer, Sprache.t("Fastest error-free speed found -- taking one step before it: " + zahl(faktor) + " (fixed).",
                    "Schnellstes fehlerfreies Tempo gefunden -- es bleibt eine Stufe davor: " + zahl(faktor) + " (fest)."));
        }
        if (faktor != alt || fest) speichern();
    }

    private void laden() {
        try {
            if (!Files.isRegularFile(datei)) return;
            for (String z : Dateien.zeilen(datei)) {
                z = z.replace("﻿", "").trim();
                if (z.isEmpty() || z.startsWith("#")) continue;
                String[] t = z.split("\\s+");
                double w = Double.parseDouble(t[0].replace(',', '.'));
                if (w >= MIN && w <= 1.0) {
                    faktor = w;
                    fest = t.length > 1 && t[1].equalsIgnoreCase("fest");
                }
                return;
            }
        } catch (Exception e) {
            Log.debug(wer, datei.getFileName() + " nicht lesbar: " + e.getMessage());
        }
    }

    private void speichern() {
        try {
            Files.writeString(datei, Sprache.t("# Speed: pauses x factor (1 = normal). \"fest\" = fastest error-free found.\n",
                    "# Tempo: Pausen x Faktor (1 = normal). \"fest\" = schnellstes fehlerfreies gefunden.\n")
                    + zahl(faktor) + (fest ? " fest" : "") + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug(wer, datei.getFileName() + " nicht geschrieben: " + e.getMessage());
        }
    }

    private static String zahl(double f) {
        return String.format(java.util.Locale.GERMANY, "%.4f", f);
    }
}
