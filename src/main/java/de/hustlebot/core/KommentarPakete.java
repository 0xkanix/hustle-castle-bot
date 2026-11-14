package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * Autor 30.10.: "Alle Kommentare werden als Paket nach jeder Arena an eine Sammelstelle weitergeleitet, und wenn es mindestens 10 Pakete
 * sind, an einen Helfer." Nur Arenen mit Kommentar ergeben ein Paket.
 *
 * Ordner arena-kommentare: offen.txt nimmt die Zeilen der laufenden Arena auf; ist die Arena zu Ende (der Bot beginnt die naechste,
 * oder der letzte Kommentar ist laenger her als eine Arena dauern kann), wird daraus paket-(Zeit).txt. Das Zaehlen und Weitergeben
 * macht die geplante Aufgabe (werkzeuge/kommentar-pakete.py).
 */
public final class KommentarPakete {

    public static final String ORDNER = "arena-kommentare";
    static final String OFFEN = "offen.txt";
    /** Eine Arena dauert rund 12 Minuten; so lange Stille heisst: das war eine andere Arena. */
    static final long LUECKE_MS = 15 * 60_000L;
    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    private KommentarPakete() { }

    /** Eine Kommentarzeile in die Sammelstelle. */
    public static void anhaengen(String zeile) {
        anhaengen(Ordner.bei(ORDNER), zeile, System.currentTimeMillis());
    }

    /** Die Arena ist zu Ende: was offen ist, wird ein Paket. */
    public static void arenaZuEnde() {
        abschliessen(Ordner.bei(ORDNER), System.currentTimeMillis());
    }

    static synchronized void anhaengen(Path ordner, String zeile, long jetzt) {
        try {
            Files.createDirectories(ordner);
            Path offen = ordner.resolve(OFFEN);
            if (Files.exists(offen) && jetzt - Files.getLastModifiedTime(offen).toMillis() > LUECKE_MS) abschliessen(ordner, jetzt);
            Files.writeString(offen, zeile + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Comment not collected: ", "Kommentar nicht gesammelt: ") + e.getMessage());
        }
    }

    static synchronized void abschliessen(Path ordner, long jetzt) {
        Path offen = ordner.resolve(OFFEN);
        try {
            if (!Files.exists(offen)) return;
            if (Files.size(offen) == 0) {
                Files.delete(offen);
                return;
            }
            Path paket = ordner.resolve("paket-" + STEMPEL.format(Instant.ofEpochMilli(jetzt)) + ".txt");
            for (int i = 2; Files.exists(paket); i++) {
                paket = ordner.resolve("paket-" + STEMPEL.format(Instant.ofEpochMilli(jetzt)) + "-" + i + ".txt");
            }
            Files.move(offen, paket);
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Comment package not closed: ", "Kommentar-Paket nicht abgeschlossen: ") + e.getMessage());
        }
    }

    /** Wie viele fertige Pakete warten. */
    static synchronized long pakete(Path ordner) {
        if (!Files.isDirectory(ordner)) return 0;
        try (Stream<Path> s = Files.list(ordner)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("paket-") && n.endsWith(".txt");
            }).count();
        } catch (Exception e) {
            return 0;
        }
    }
}
