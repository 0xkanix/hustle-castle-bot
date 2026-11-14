package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Kathedralen-Zeitmesser (Autor 17.10.: "einen Kathedralen-Zeitmesser in das Bot-Programm, damit Karla mir genau
 * sagen kann, wie lange eine Kathedrale bei ihr dauert").
 *
 * Nach jedem Kathedralen-Run (Zeitbericht) wird die Zeit gemerkt -- im Speicher (die letzten 50) und als Zeile in
 * kathedrale-zeiten.txt im Bot-Ordner, damit sie einen Neustart uebersteht. Danach kommt eine kurze Meldung ins
 * Steuerfenster (und nach Discord, falls eingerichtet); "!times" zeigt die Uebersicht, und jeder Fehlerbericht an
 * Autor enthaelt sie.
 */
public final class Kathedralzeiten {
    private static final Path DATEI = Ordner.bei("kathedrale-zeiten.txt");
    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
    private static final List<Long> zeiten = new ArrayList<>();
    private static final List<String> zeilen = new ArrayList<>();
    /** Wer nach jedem Run Bescheid bekommt (DiscordBridge: Karte im Steuerfenster/Discord). */
    public static volatile Consumer<String> melder;
    private static boolean geladen;
    /** Eine Karte je so vielen Runs (Autor 24.10.). */
    private static final int MELDEN_ALLE = 10;
    private static int seitMeldung;

    private Kathedralzeiten() {
    }

    /** Nach einem Run: Gesamtzeit und die Aufteilung aus dem Zeitbericht. */
    public static void merken(long gesamtMs, String aufteilung) {
        String zeile;
        synchronized (Kathedralzeiten.class) {
            laden();
            zeile = LocalDateTime.now().format(UHR) + "  " + dauer(gesamtMs) + "  " + aufteilung;
            zeiten.add(gesamtMs);
            zeilen.add(zeile);
            while (zeiten.size() > 50) {
                zeiten.remove(0);
                zeilen.remove(0);
            }
            try {
                Files.writeString(DATEI, gesamtMs + "\t" + zeile + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                Log.debug("Kathedrale", "kathedrale-zeiten.txt: " + e.getMessage());
            }
        }
        // Autor 24.10.: "Nur noch alle 10 Runs" -- jede Minute eine Karte war zu viel. Eine Karte je 10 Runs mit dem Schnitt
        // der letzten 10 und dem letzten Run zum Vergleich; alle Zeiten weiter mit !zeiten.
        Consumer<String> m = melder;
        boolean melden;
        synchronized (Kathedralzeiten.class) {
            melden = ++seitMeldung >= MELDEN_ALLE;
            if (melden) seitMeldung = 0;
        }
        if (m != null && melden) {
            try {
                m.accept(Sprache.t("Cathedral, **", "Kathedrale, **") + schnittText() + "**\n"
                        + Sprache.t("Last run: ", "Letzter Run: ") + dauer(gesamtMs) + " min  ·  " + aufteilung);
            } catch (Exception e) {
                Log.debug("Kathedrale", "Zeitmeldung: " + e.getMessage());
            }
        }
    }

    /** Die Uebersicht fuer "!times" und den Fehlerbericht. */
    public static synchronized String uebersicht() {
        laden();
        if (zeiten.isEmpty()) {
            return Sprache.t("No cathedral run measured yet.", "Noch kein Kathedralen-Run gemessen.");
        }
        StringBuilder b = new StringBuilder(schnittText()).append('\n');
        int ab = Math.max(0, zeilen.size() - 10);
        for (int i = zeilen.size() - 1; i >= ab; i--) b.append(zeilen.get(i)).append('\n');
        return b.toString().strip();
    }

    private static String schnittText() {
        List<Long> letzte = zeiten.subList(Math.max(0, zeiten.size() - 10), zeiten.size());
        if (letzte.isEmpty()) return "";
        long summe = 0, min = Long.MAX_VALUE, max = 0;
        for (long z : letzte) {
            summe += z;
            min = Math.min(min, z);
            max = Math.max(max, z);
        }
        return Sprache.t("last " + letzte.size() + " runs: average " + dauer(summe / letzte.size()) + ", fastest "
                        + dauer(min) + ", slowest " + dauer(max),
                "letzte " + letzte.size() + " Runs: Schnitt " + dauer(summe / letzte.size()) + ", schnellster "
                        + dauer(min) + ", langsamster " + dauer(max));
    }

    /** 272000 ms -> "4:32". */
    public static String dauer(long ms) {
        long s = Math.round(ms / 1000.0);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** Einmal die Zeilen aus der Datei holen (nach einem Neustart). */
    private static void laden() {
        if (geladen) return;
        geladen = true;
        try {
            if (!Files.exists(DATEI)) return;
            List<String> alle = Dateien.zeilen(DATEI);
            for (String z : alle.subList(Math.max(0, alle.size() - 50), alle.size())) {
                int tab = z.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    zeiten.add(Long.parseLong(z.substring(0, tab).trim()));
                    zeilen.add(z.substring(tab + 1));
                } catch (NumberFormatException ignored) {
                    // kaputte Zeile -- weglassen
                }
            }
        } catch (Exception e) {
            Log.debug("Kathedrale", "kathedrale-zeiten.txt lesen: " + e.getMessage());
        }
    }
}
