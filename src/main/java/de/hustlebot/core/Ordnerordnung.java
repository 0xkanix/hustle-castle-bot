package de.hustlebot.core;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Autor 24.10. (Robin hatte im Bot-Ordner herumprobiert): Im Bot-Ordner sind nur noch HustleBot.exe, Capture-Images.exe,
 * die Anleitung 0-START-HERE.txt und der Ordner "My pictures" (Eigenbilder) zu sehen -- alles andere (app, runtime,
 * templates, Einstellungen, Protokolle ...) ist versteckt, damit niemand aus Versehen etwas veraendert oder loescht. Der
 * Bot selbst findet versteckte Dateien wie immer. Bei jedem Start, damit auch neu angelegte Dateien verschwinden.
 * Liegt "ordner-offen.txt" im Ordner (Autors PC), bleibt alles sichtbar -- und was vorher versteckt war, wird wieder gezeigt.
 *
 * "My pictures" enthaelt alles, was der Spieler selbst aufnimmt, und die Arena-Gegnerlisten -- getrennt von Autors ~800
 * mitgelieferten Bildern in templates\ (Autor 24.10.: "nicht zusammen mit den ganzen Templates").
 */
public final class Ordnerordnung {
    /** Was sichtbar bleibt (klein geschrieben). */
    static final Set<String> SICHTBAR = Set.of("hustlebot.exe", "capture-images.exe", "0-start-here.txt",
            Eigenbilder.ORDNER.toLowerCase(Locale.ROOT));
    static final String OFFEN = "ordner-offen.txt";

    private Ordnerordnung() {
    }

    /** Im Hintergrund, damit der Start nicht wartet. Nur unter Windows. */
    public static void aufraeumen(Path ordner) {
        if (ordner == null || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        Thread t = new Thread(() -> {
            try {
                int n = anwenden(ordner);
                if (n > 0) Log.debug("Ordner", n + " Eintraege im Bot-Ordner versteckt bzw. gezeigt.");
            } catch (Exception e) {
                Log.debug("Ordner", "Ordnung im Bot-Ordner nicht moeglich: " + e.getMessage());
            }
        }, "ordner-ordnung");
        t.setDaemon(true);
        t.start();
    }

    /** Setzt "versteckt" so, wie es sein soll; liefert, wie viele Eintraege geaendert wurden. */
    static int anwenden(Path ordner) throws java.io.IOException {
        boolean allesOffen = Files.exists(ordner.resolve(OFFEN));
        int geaendert = 0;
        try (DirectoryStream<Path> eintraege = Files.newDirectoryStream(ordner)) {
            for (Path p : eintraege) {
                boolean soll = !allesOffen && !SICHTBAR.contains(p.getFileName().toString().toLowerCase(Locale.ROOT));
                try {
                    Object ist = Files.getAttribute(p, "dos:hidden", LinkOption.NOFOLLOW_LINKS);
                    if (Boolean.valueOf(soll).equals(ist)) continue;
                    Files.setAttribute(p, "dos:hidden", soll, LinkOption.NOFOLLOW_LINKS);
                    geaendert++;
                } catch (Exception e) {
                    // einzelne Datei (gerade offen, keine Rechte) -- weiter mit der naechsten
                }
            }
        }
        return geaendert;
    }
}
