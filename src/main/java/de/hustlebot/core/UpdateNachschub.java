package de.hustlebot.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Legt Skripte aus der Jar (Ordner "skripte") neben den Bot und liest fassung.txt aus der Jar.
 * Die frueheren Update-Werkzeuge (UPDATE.bat mit Siegel-Pruefung) sind entfernt -- hier passiert nur Lokales.
 */
public final class UpdateNachschub {

    private UpdateNachschub() {
    }

    /** Laeuft das Programm als fertiges Paket (HustleBot.exe)? jpackage setzt diese Eigenschaft. */
    static boolean imPaket() {
        String exe = System.getProperty("jpackage.app-path");
        return exe != null && !exe.isBlank();
    }

    /**
     * Legt ein Skript aus der Jar (Ordner "skripte") neben den Bot und haelt es auf Jar-Stand.
     *
     * WARUM (Sicherheits-Update 16.10.): texterkennung.ps1 und texterkennung-dauer.ps1 wurden
     * nur kopiert, wenn sie FEHLTEN. Nach einem reinen Jar-Update blieb so das alte Skript
     * liegen -- und wer es austauschte, bekam seines vom Bot gestartet. Jetzt wie bei den
     * Update-Skripten: Inhalt vergleichen, bei Abweichung ueberschreiben. Nur im fertigen
     * Paket -- beim Entwickeln IST die Datei im Projektordner das Original, aus dem die Jar
     * gebaut wird; dort wird sie wie bisher nur angelegt, wenn sie fehlt.
     *
     * @return true, wenn die Datei geschrieben wurde
     */
    static boolean skriptAufJarStand(Path ziel) {
        String name = ziel.getFileName().toString();
        try (InputStream in = UpdateNachschub.class.getResourceAsStream("/skripte/" + name)) {
            if (in == null) return false;
            byte[] inhalt = in.readAllBytes();
            boolean da = Files.exists(ziel);
            if (da && (!imPaket() || Arrays.equals(Files.readAllBytes(ziel), inhalt))) return false;
            Files.write(ziel, inhalt);
            Log.info("Start", Sprache.t((da ? "Script brought up to the jar's version: " : "Script created: ") + ziel.toAbsolutePath(),
                    (da ? "Skript auf Jar-Stand gebracht: " : "Skript angelegt: ") + ziel.toAbsolutePath()));
            return true;
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("Script " + name + " could not be placed: " + e.getMessage(),
                    "Skript " + name + " liess sich nicht ablegen: " + e.getMessage()));
            return false;
        }
    }

    /** Die Fassung, die beim Bau in die Jar geschrieben wurde (fassung.txt), sonst null. */
    public static String fassungInDerJar() {
        try (InputStream in = UpdateNachschub.class.getResourceAsStream("/fassung.txt")) {
            if (in == null) return null;
            String t = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
