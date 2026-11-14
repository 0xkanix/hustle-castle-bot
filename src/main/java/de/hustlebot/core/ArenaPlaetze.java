package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Autor 21.10.: "bei den Stats im Bot-Programm sehen, wie oft der Bot Top 1, Top 2, Top 3 geworden ist -- anhand der
 * gespielten Arenas insgesamt und aufgeteilt in jeden Tag."
 *
 * Jedes beendete Turnier kommt als Zeile "2026-10-21 21:40<TAB>2" in arena-plaetze.txt. Fehlt die Datei, holt der Bot
 * die Turniere einmal aus seinen Protokollen nach ("Tournament finished in place 2" / "Turnier beendet auf Platz 2").
 */
public final class ArenaPlaetze {

    private ArenaPlaetze() { }

    public static final String DATEI = "arena-plaetze.txt";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern ZEILE = Pattern.compile("^(\\d{2}):(\\d{2}):\\d{2}.*(?:Tournament finished in place|Turnier beendet auf Platz) (\\d+)");
    private static final Pattern LOGNAME = Pattern.compile("bot_(\\d{4}-\\d{2}-\\d{2})_\\d{2}-\\d{2}-\\d{2}\\.log");

    /** Ein beendetes Turnier eintragen. */
    public static synchronized void eintragen(int platz) {
        if (platz < 1 || platz > 10) return;
        try {
            Path d = Ordner.bei(DATEI);
            if (!Files.exists(d)) nachholen(Ordner.bei("logs"), d);
            Files.writeString(d, LocalDateTime.now().format(FORMAT) + "\t" + platz + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Arena", DATEI + ": " + e.getMessage());
        }
    }

    /** Die Datei lesen; fehlt sie, erst aus den Protokollen nachholen. Leer, wenn es nichts gibt. */
    public static synchronized String lesen() {
        try {
            Path d = Ordner.bei(DATEI);
            if (!Files.exists(d)) nachholen(Ordner.bei("logs"), d);
            return Files.exists(d) ? Dateien.text(d) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Alle Turniere aus den Protokollen in {@code ziel} schreiben (nur wenn es noch keine Datei gibt). */
    static void nachholen(Path logs, Path ziel) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(Sprache.t(
                "# Arena: place of every finished tournament (date time <TAB> place). From the logs since the start.\n",
                "# Arena: Platz jedes beendeten Turniers (Datum Uhrzeit <TAB> Platz). Seit Beginn aus den Protokollen.\n"));
        if (Files.isDirectory(logs)) {
            List<Path> dateien = new ArrayList<>();
            try (Stream<Path> s = Files.list(logs)) {
                s.filter(p -> LOGNAME.matcher(p.getFileName().toString()).matches()).sorted().forEach(dateien::add);
            }
            for (Path p : dateien) sb.append(ausProtokoll(p.getFileName().toString(), Dateien.zeilen(p)));
        }
        Files.writeString(ziel, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Die Turnier-Zeilen eines Protokolls; der Tag kommt aus dem Dateinamen (ueber Mitternacht: naechster Tag). */
    static String ausProtokoll(String dateiname, List<String> zeilen) {
        Matcher n = LOGNAME.matcher(dateiname);
        if (!n.matches()) return "";
        LocalDate tag = LocalDate.parse(n.group(1));
        int vorher = -1;
        StringBuilder sb = new StringBuilder();
        for (String z : zeilen) {
            if (z.length() < 8 || !Character.isDigit(z.charAt(0))) continue;
            int minute;
            try {
                minute = Integer.parseInt(z.substring(0, 2)) * 60 + Integer.parseInt(z.substring(3, 5));
            } catch (NumberFormatException e) {
                continue;
            }
            if (vorher >= 0 && minute < vorher - 60) tag = tag.plusDays(1);
            vorher = minute;
            Matcher m = ZEILE.matcher(z);
            if (m.find()) sb.append(tag).append(' ').append(m.group(1)).append(':').append(m.group(2)).append('\t').append(m.group(3)).append('\n');
        }
        return sb.toString();
    }

    /** Je Tag {Turniere, Platz 1, Platz 2, Platz 3}; Schluessel "2026-10-21". */
    public static TreeMap<String, int[]> jeTag(String text) {
        TreeMap<String, int[]> t = new TreeMap<>();
        if (text == null) return t;
        for (String zeile : text.split("\n")) {
            String z = zeile.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            String[] teil = z.split("\t");
            if (teil.length < 2 || teil[0].length() < 10) continue;
            int platz;
            try {
                platz = Integer.parseInt(teil[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            int[] w = t.computeIfAbsent(teil[0].substring(0, 10), k -> new int[4]);
            w[0]++;
            if (platz >= 1 && platz <= 3) w[platz]++;
        }
        return t;
    }

    /** Zusammen ueber alle Tage: {Turniere, Platz 1, Platz 2, Platz 3}. */
    public static int[] gesamt(TreeMap<String, int[]> jeTag) {
        int[] g = new int[4];
        for (int[] w : jeTag.values()) for (int i = 0; i < 4; i++) g[i] += w[i];
        return g;
    }

    /** Eine Zeile Text: "12 tournaments -- 1st: 3 · 2nd: 2 · 3rd: 4 · top 3: 75 %". */
    public static String zeile(int[] w) {
        int top3 = w[1] + w[2] + w[3];
        // Autor 22.10.: je Tag auch Platz 4-10 und die Quote "Top 1-3 von allen gespielten".
        return w[0] + Sprache.t(w[0] == 1 ? " tournament" : " tournaments", w[0] == 1 ? " Turnier" : " Turniere")
                + " -- " + Sprache.t("1st", "1.") + ": " + w[1] + " · " + Sprache.t("2nd", "2.") + ": " + w[2]
                + " · " + Sprache.t("3rd", "3.") + ": " + w[3] + " · " + Sprache.t("4th-10th", "4.-10.") + ": " + (w[0] - top3)
                + (w[0] > 0 ? " · Top 1-3: " + top3 + Sprache.t(" of ", " von ") + w[0] + " = " + Math.round(100.0 * top3 / w[0]) + " %" : "");
    }

    /** Der ganze Text fuers Statistik-Fenster: insgesamt und je Tag (neuester oben). */
    public static String text(String dateiText) {
        TreeMap<String, int[]> t = jeTag(dateiText);
        if (t.isEmpty()) return Sprache.t("No arena tournament finished yet.", "Noch kein Arena-Turnier beendet.");
        StringBuilder sb = new StringBuilder(Sprache.t("Total: ", "Gesamt: ")).append(zeile(gesamt(t)));
        for (String tag : t.descendingKeySet()) {
            sb.append('\n').append(tag, 8, 10).append('.').append(tag, 5, 7).append(".  ").append(zeile(t.get(tag)));
        }
        return sb.toString();
    }
}
