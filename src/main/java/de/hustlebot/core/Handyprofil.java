package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Schalter "Bot spielt am Handy" (Handy-Umbau, Autor 19.11.): liegt im Bot-Ordner die Datei handy.txt, steuert der Bot
 * das Handy ueber ADB statt das Spielfenster am PC. Inhalt (Zeilen "schluessel=wert", # = Kommentar):
 *
 *   adb=C:\platform-tools\adb.exe
 *   geraet=192.168.178.42:37123
 *   vorlagen=templates-handy        (freiwillig, Standard templates-handy)
 *   an=ja                          (freiwillig; "nein" schaltet ab, ohne die Datei zu loeschen)
 *
 * Ohne die Datei (oder mit an=nein) laeuft alles wie bisher am PC.
 */
public record Handyprofil(String adb, String geraet, String vorlagen) {

    public static final String DATEI = "handy.txt";

    /** Das Profil aus handy.txt im Ordner, oder null (kein Handy). */
    public static Handyprofil laden(Path ordner) {
        Path p = ordner.resolve(DATEI);
        if (!Files.isRegularFile(p)) return null;
        try {
            return aus(Files.readAllLines(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.warn("Handy", DATEI + " nicht lesbar: " + e.getMessage() + " -- der Bot bleibt am PC.");
            return null;
        }
    }

    /** Aus den Zeilen; null, wenn abgeschaltet oder adb fehlt. */
    static Handyprofil aus(List<String> zeilen) {
        String adb = null, geraet = null, vorlagen = "templates-handy";
        boolean an = true;
        for (String z : zeilen) {
            String t = z == null ? "" : z.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            String k = t.substring(0, t.indexOf('=')).trim().toLowerCase(java.util.Locale.ROOT);
            String v = t.substring(t.indexOf('=') + 1).trim();
            switch (k) {
                case "adb" -> adb = v;
                case "geraet", "gerät", "device" -> geraet = v;
                case "vorlagen" -> vorlagen = v;
                case "an" -> an = !List.of("nein", "aus", "0", "false", "off", "no").contains(v.toLowerCase(java.util.Locale.ROOT));
                default -> { }
            }
        }
        if (!an || adb == null || adb.isBlank()) return null;
        return new Handyprofil(adb, geraet, vorlagen == null || vorlagen.isBlank() ? "templates-handy" : vorlagen);
    }
}
