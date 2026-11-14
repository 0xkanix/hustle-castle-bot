package de.hustlebot.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liefert neue Vorlagen mit der Jar-Datei nach.
 *
 * WARUM. Ein Freund aktualisiert, indem er nur app\hustle-bot.jar
 * austauscht. Bringt ein Update neue Vorlagen mit -- etwa die Schrift
 * "Guardian's lair" fuer die Waechter-Erkennung --, kamen die bisher nie bei
 * ihm an: Der Ordner templates wird beim Update nicht angefasst.
 *
 * Deshalb stecken solche Bilder zusaetzlich in der Jar (Ordner
 * "nachschub"). Beim Start wird jedes Bild, das im Ordner templates noch
 * FEHLT, hineinkopiert. Vorhandene Bilder werden nie ueberschrieben -- hat
 * ein Freund eine Vorlage selbst neu aufgenommen, bleibt seine.
 */
public final class VorlagenNachschub {

    /** Liste der mitgelieferten Bilder, eine Zeile je Bild ("cathedral/x.png"). */
    private static final String LISTE = "/nachschub/liste.txt";

    private VorlagenNachschub() {
    }

    /** Kopiert fehlende Vorlagen in den Ordner. Liefert, wie viele neu sind. */
    public static int liefern(Path vorlagenOrdner) {
        int neu = 0;
        try (InputStream in = VorlagenNachschub.class.getResourceAsStream(LISTE)) {
            if (in == null) return 0;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            for (String zeile : text.split("\\R")) {
                String name = zeile.strip();
                if (name.isEmpty() || name.startsWith("#") || name.contains("..")) continue;

                Path ziel = vorlagenOrdner.resolve(name).normalize();
                if (!ziel.startsWith(vorlagenOrdner.normalize())) continue;
                if (Files.exists(ziel)) continue;

                try (InputStream bild = VorlagenNachschub.class
                        .getResourceAsStream("/nachschub/" + name)) {
                    if (bild == null) continue;
                    Files.createDirectories(ziel.getParent());
                    Files.copy(bild, ziel);
                    neu++;
                }
            }
        } catch (Exception e) {
            Log.warn("Start", Sprache.t("New templates from the update could not be stored: ",
                    "Neue Vorlagen aus dem Update liessen sich nicht ablegen: ")
                    + e.getMessage());
        }

        if (neu > 0) {
            Log.info("Start", Sprache.t(neu + " new templates from the update placed in the templates "
                    + "folder.", neu + " neue Vorlagen aus dem Update in den Ordner templates "
                    + "gelegt."));
        }
        return neu;
    }
}
