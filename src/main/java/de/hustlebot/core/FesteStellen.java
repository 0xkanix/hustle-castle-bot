package de.hustlebot.core;

import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Feste Stellen im Spielbild, von Autor mit dem Aufnahme-Werkzeug markiert.
 *
 * WOFUER. Manche Stellen haben kein festes Aussehen, nur einen festen Ort:
 * die Brustplatte im Einheitenfenster der Arena (jede Einheit traegt eine
 * andere), das Artefakt unten rechts, der Titel eines Gegenstands. Eine
 * Bildvorlage hilft dort nicht -- man muss wissen, WO.
 *
 * WIE. Speichert Autor im Aufnahme-Werkzeug eine Vorlage, deren Name mit
 * "ar_stelle_" beginnt, merkt sich das Werkzeug zusaetzlich den gezogenen
 * Rahmen in stellen-fest.txt: Lage, Groesse und die Groesse des ganzen Bildes
 * dazu. Ist das Spielfenster spaeter anders gross, wird umgerechnet.
 */
public final class FesteStellen {

    public static final Path DATEI = Path.of("stellen-fest.txt");

    /** Rahmen samt der Bildgroesse, in der er gezogen wurde. */
    public record Stelle(int x, int y, int breite, int hoehe, int bildBreite, int bildHoehe) {
        /** Der Rahmen umgerechnet auf ein Bild der Groesse w x h. */
        public Rectangle in(int w, int h) {
            double fx = bildBreite > 0 ? (double) w / bildBreite : 1.0;
            double fy = bildHoehe > 0 ? (double) h / bildHoehe : 1.0;
            return new Rectangle((int) Math.round(x * fx), (int) Math.round(y * fy),
                    Math.max(1, (int) Math.round(breite * fx)),
                    Math.max(1, (int) Math.round(hoehe * fy)));
        }
    }

    private FesteStellen() {
    }

    /** Merkt (oder ersetzt) eine Stelle. */
    public static synchronized void merke(String name, Rectangle r, int bildBreite, int bildHoehe) {
        try {
            List<String> zeilen = Files.exists(DATEI)
                    ? new ArrayList<>(Dateien.zeilen(DATEI))
                    : new ArrayList<>(List.of(
                            Sprache.t("# Fixed positions (capture tool, names ar_stelle_...).",
                                      "# Feste Stellen (Aufnahme-Werkzeug, Namen ar_stelle_...)."),
                            Sprache.t("# name=x,y,width,height,imagewidth,imageheight",
                                      "# name=x,y,breite,hoehe,bildbreite,bildhoehe")));
            zeilen.removeIf(z -> z.startsWith(name + "="));
            zeilen.add(name + "=" + r.x + "," + r.y + "," + r.width + "," + r.height
                    + "," + bildBreite + "," + bildHoehe);
            Files.write(DATEI, zeilen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Stellen", Sprache.t("Could not remember the position " + name + ": " + e.getMessage(),
                    "Konnte die Stelle " + name + " nicht merken: " + e.getMessage()));
        }
    }

    /**
     * FREUNDE-FASSUNG (Arena, 11.10.): Autors im Hauptbot gemessene Stellen als Vorgabe. Das
     * Aufnahme-Werkzeug der Freunde merkt keine Stellen -- ohne diese Werte bliebe die
     * Einheiten-Pruefung der Arena (Sets, Artefakte, Leben) aus. Umgerechnet wird wie immer
     * ueber die Bildgroesse; eine eigene Zeile in stellen-fest.txt hat Vorrang.
     */
    private static final java.util.Map<String, Stelle> VORGABE = java.util.Map.of(
            "ar_stelle_brustplatte", new Stelle(518, 366, 104, 97, 1159, 770),
            "ar_stelle_artefakt",    new Stelle(539, 510, 84, 76, 1159, 770),
            // Autor 22.10.: das Totem ist das ERSTE der vier kleinen Felder (Totem, Ring, Amulett, Artefakt), je ~97 Punkte Abstand.
            "ar_stelle_totem",       new Stelle(248, 510, 84, 76, 1159, 770),
            "ar_stelle_setname",     new Stelle(461, 75, 238, 41, 1159, 770),
            "ar_stelle_leben",       new Stelle(338, 144, 255, 50, 1159, 770));   // 22.10.: Zaehler-Test, 115 von 115 Bildern

    /** Die gemerkte Stelle, falls es sie gibt. */
    public static synchronized Optional<Stelle> holen(String name) {
        try {
            if (!Files.exists(DATEI)) return Optional.ofNullable(VORGABE.get(name));
            for (String z : Dateien.zeilen(DATEI)) {
                if (!z.startsWith(name + "=")) continue;
                String[] t = z.substring(name.length() + 1).split(",");
                if (t.length < 6) return Optional.empty();
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(t[i].trim());
                return Optional.of(new Stelle(v[0], v[1], v[2], v[3], v[4], v[5]));
            }
        } catch (Exception e) {
            Log.debug("Stellen", "stellen-fest.txt nicht lesbar: " + e.getMessage());
        }
        return Optional.ofNullable(VORGABE.get(name));
    }
}
