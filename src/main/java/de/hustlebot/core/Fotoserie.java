package de.hustlebot.core;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Fotoserie aus den Bildern, die der Bot ohnehin aufnimmt (Autor 18.10.: "der Bot nimmt nicht immer den besten Weg zu
 * den HC-Belohnungen -- Fotoserie 5 min"). Eine eigene Bildschirmfoto-Schleife blockt der Virenschutz; hier speichert
 * der Bot nur seine eigenen Bilder, hoechstens eins je 500 ms, nach logs\fotoserie\f_NNN_HHMMSS.png.
 *
 * Starten: "!fotoserie 5" (Minuten) oder die Datei fotoserie.txt im Bot-Ordner mit der Minutenzahl -- die liest der
 * Bot innerhalb von 2 s und loescht sie.
 */
public final class Fotoserie {

    private static final long ABSTAND_MS = 500;   // Autor 21.10.: "alle 0,5 Sekunden ein Bild" (vorher 600)
    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HHmmss");
    private static final ThreadPoolExecutor SCHREIBER = new ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "fotoserie");
                t.setDaemon(true);
                return t;
            });

    private static volatile long bis;
    private static long letztesBild, letzteDateiPruefung;
    private static int nummer;
    private static Path ordner;

    private Fotoserie() {
    }

    /** Serie fuer so viele Minuten starten (1 bis 30). Liefert den Ordner. */
    public static synchronized Path starten(int minuten) {
        int m = Math.max(1, Math.min(10, minuten));   // Autor 22.10.: hoechstens 10 Minuten
        ordner = Ordner.bei("logs").resolve("fotoserie");
        try {
            Files.createDirectories(ordner);
        } catch (Exception e) {
            Log.warn("Fotoserie", "Ordner nicht angelegt: " + e.getMessage());
        }
        nummer = 0;
        bis = System.currentTimeMillis() + m * 60_000L;
        Log.info("Fotoserie", Sprache.t("Photo series for " + m + " min -> " + ordner, "Fotoserie fuer " + m + " min -> " + ordner));
        return ordner;
    }

    public static boolean laeuft() {
        return System.currentTimeMillis() < bis;
    }

    /** Vom Bildholer bei jedem neuen Bild aufgerufen -- kostet ausserhalb einer Serie nur einen Zeitvergleich. */
    public static void bild(BufferedImage b) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzteDateiPruefung > 2000) {
            letzteDateiPruefung = jetzt;
            dateiPruefen();
        }
        if (b == null || jetzt >= bis || jetzt - letztesBild < ABSTAND_MS) return;
        letztesBild = jetzt;
        if (SCHREIBER.getQueue().size() > 5) return;   // Platte zu langsam: lieber eins auslassen
        Path ziel = ordner.resolve(String.format("f_%03d_%s.png", ++nummer, LocalTime.now().format(UHR)));
        SCHREIBER.execute(() -> {
            try {
                javax.imageio.ImageIO.write(b, "png", ziel.toFile());
            } catch (Exception e) {
                Log.debug("Fotoserie", "Bild nicht gespeichert: " + e.getMessage());
            }
        });
    }

    private static void dateiPruefen() {
        Path d = Ordner.bei("fotoserie.txt");
        if (!Files.exists(d)) return;
        int minuten = 5;
        try {
            String t = Dateien.text(d).replaceAll("[^0-9]", "");
            if (!t.isEmpty() && t.length() <= 3) minuten = Integer.parseInt(t);
            Files.deleteIfExists(d);
        } catch (Exception e) {
            Log.debug("Fotoserie", "fotoserie.txt: " + e.getMessage());
        }
        starten(minuten);
    }
}
