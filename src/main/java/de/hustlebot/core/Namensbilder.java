package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Lesbare Namen fuer Namensbilder ("Bild-1002-012347").
 *
 * Kann die Texterkennung einen Namen gar nicht lesen (Zierschrift, abgeschnitten, nur drei Buchstaben),
 * merkt sich der Bot ein Bild des Namens und nennt den Spieler "Bild-MMdd-HHmmss". Erkannt wird er weiter
 * ueber das Bild -- im Notizblock soll aber ein Name stehen (Autor, 11.10.: "schreib sie so gut du kannst
 * in den Notizblock, dass der Bot sie aber erkennt und zuordnen kann"). Die Zuordnung steht in
 * arena-namensbilder.txt im Bot-Ordner, eine Zeile je Bild: "Bild-1002-012347 = Sascha". Die Datei legt der
 * Spieler selbst an; fehlt sie, bleibt die Kennung stehen.
 */
public final class Namensbilder {

    public static final Path DATEI = Path.of("arena-namensbilder.txt");

    private static Map<String, String> namen = Map.of();
    private static long gelesenStand = -1;

    private Namensbilder() { }

    /** Der lesbare Name zum Bild, sonst die Kennung selbst. */
    public static synchronized String name(String kennung) {
        if (kennung == null) return null;
        laden();
        return namen.getOrDefault(kennung.strip(), kennung);
    }

    /**
     * Autor 01.11.: eine glaubhafte Lesung gilt als Name des Namensbildes. "Bild-1024-032130" (Platzhalter vom 24.10.) hiess weiter so, obwohl der Bot
     * "元素神" las -- im Protokoll, im Seitenfenster und im Notizblock. Schreibt "Kennung = Name" in arena-namensbilder.txt, wenn dort noch nichts zu dieser
     * Kennung steht (eine Zuordnung von Autor bleibt). Das Namensbild selbst (der Fingerabdruck) bleibt der Anker: Bilanz und Sperren hangen daran.
     * @return ob eine Zeile geschrieben wurde
     */
    public static synchronized boolean zuordnen(String kennung, String name) {
        boolean neu = zuordnen(DATEI, kennung, name);
        if (neu) gelesenStand = -1;   // beim naechsten Nachschlagen neu lesen
        return neu;
    }

    static boolean zuordnen(Path datei, String kennung, String name) {
        if (kennung == null || name == null || kennung.isBlank() || name.isBlank() || name.contains("\n") || name.contains("=")) return false;
        try {
            if (Files.exists(datei)) {
                for (String z : zeilenLesen(datei)) {
                    if (z.contains("=") && z.substring(0, z.indexOf('=')).strip().equals(kennung.strip())) return false;
                }
            }
            String zeile = kennung.strip() + " = " + name.strip() + System.lineSeparator();
            Files.writeString(datei, zeile, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            Log.warn("Notizblock", Sprache.t("arena-namensbilder.txt not written: " + e.getMessage(), "arena-namensbilder.txt nicht geschrieben: " + e.getMessage()));
            return false;
        }
    }

    private static void laden() {
        try {
            long stand = Files.exists(DATEI) ? Files.getLastModifiedTime(DATEI).toMillis() : 0;
            if (stand == gelesenStand) return;
            gelesenStand = stand;
            Map<String, String> neu = new HashMap<>();
            if (stand > 0) {
                for (String z : zeilenLesen(DATEI)) {
                    if (z.isBlank() || z.startsWith("#") || !z.contains("=")) continue;
                    String k = z.substring(0, z.indexOf('=')).strip(), n = z.substring(z.indexOf('=') + 1).strip();
                    if (!k.isEmpty() && !n.isEmpty()) neu.put(k, n);
                }
            }
            namen = neu;
        } catch (Exception e) {
            Log.warn("Notizblock", Sprache.t("arena-namensbilder.txt not readable: " + e.getMessage(),
                    "arena-namensbilder.txt nicht lesbar: " + e.getMessage()));
        }
    }

    /**
     * Liest eine Listen-Datei des Spielers: erst als UTF-8 (so speichert der Editor von Windows 11), sonst in
     * der Windows-Codepage (aeltere Editoren, "ANSI"). Pruefung 11.10.: vorher wurde eine ANSI-Datei mit
     * Umlaut still ganz ignoriert.
     */
    public static java.util.List<String> zeilenLesen(Path datei) throws java.io.IOException {
        try {
            return Files.readAllLines(datei, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            Log.info("Notizblock", Sprache.t(datei.getFileName() + " is not UTF-8 -- reading it as Windows text (ANSI).",
                    datei.getFileName() + " ist nicht UTF-8 -- wird als Windows-Text (ANSI) gelesen."));
            return Files.readAllLines(datei, java.nio.charset.Charset.forName("windows-1252"));
        }
    }
}
