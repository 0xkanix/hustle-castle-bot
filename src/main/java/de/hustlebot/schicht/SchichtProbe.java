package de.hustlebot.schicht;

import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Handprobe fuer die Schicht -- NICHT Teil des Bots, nur zum Ausprobieren:
 *
 *   java -cp hustle-bot.jar de.hustlebot.schicht.SchichtProbe ["Fenstertitel"]
 *
 * Sucht das Spiel wie der Bot (nur nachsehen, das Fenster wird NICHT
 * verschoben), legt 30 Sekunden lang drei Beispielrahmen darueber und
 * schreibt jedes Tastatur-/Maus-Ereignis ins Protokoll. Verschluckt wird
 * nichts, es wird nichts geklickt oder getippt.
 *
 * Dazu eine Gegenprobe, ob der Aufnahmeschutz wirkt: ein Bildschirmfoto der
 * oberen Kante des ersten Rahmens vor und nach dem Zeigen. Aendert sich die
 * Kante deutlich staerker als ein Streifen daneben, ist der Rahmen im Foto
 * zu sehen.
 */
public final class SchichtProbe {

    private static final long DAUER_MS = 30_000;

    private SchichtProbe() {}

    public static void main(String[] args) throws Exception {
        // Wie Main: Swing-Punkte = Bildschirmpunkte.
        System.setProperty("sun.java2d.uiScale", "1");

        String titel = args.length > 0 ? String.join(" ", args) : fensterTitel();
        GameWindow spiel = new GameWindow(titel, 0, 0);
        if (!spiel.locateVisible()) {
            Log.warn("Schicht", Sprache.t("Game window '" + titel + "' not found.",
                    "Spielfenster '" + titel + "' nicht gefunden."));
            return;
        }
        Rectangle innen = spiel.clientArea();
        if (innen == null) {
            Log.warn("Schicht", Sprache.t("Game area not readable (minimised?).",
                    "Spielbereich nicht lesbar (minimiert?)."));
            return;
        }
        Log.info("Schicht", Sprache.t("Game area: ", "Spielbereich: ")
                + innen.x + "," + innen.y + " " + innen.width + "x" + innen.height);

        EingabeHaken haken = new EingabeHaken();   // schluckt nichts (Grundeinstellung)
        haken.zuhoererDazu(new EingabeHaken.Zuhoerer() {
            @Override
            public void taste(int vkCode, boolean gedrueckt) {
                Log.info("Schicht", Sprache.t("key 0x" + Integer.toHexString(vkCode) + (gedrueckt ? " down" : " up"),
                        "Taste 0x" + Integer.toHexString(vkCode) + (gedrueckt ? " gedrueckt" : " losgelassen")));
            }

            @Override
            public void rechtsKlick(Point p) {
                Log.info("Schicht", Sprache.t("right click at ", "Rechtsklick bei ") + punkt(p));
            }

            @Override
            public void mittelKlick(Point p) {
                Log.info("Schicht", Sprache.t("middle click at ", "Mittelklick bei ") + punkt(p));
            }

            @Override
            public void seitenTaste(boolean vorne) {
                Log.info("Schicht", Sprache.t("side button " + (vorne ? "forward" : "back"),
                        "Seitentaste " + (vorne ? "vorne" : "hinten")));
            }
        });
        boolean hakenAn = haken.start();
        Log.info("Schicht", Sprache.t("Hooks active: ", "Haken aktiv: ") + hakenAn);

        List<SchichtFenster.Rahmen> rahmen = beispielRahmen(innen);
        Rectangle probe = rahmen.get(0).bereich();
        // Obere Kante des ersten Rahmens (ohne die runden Ecken) und ein Streifen 10 Punkte darunter.
        Rectangle kante = new Rectangle(innen.x + probe.x + 12, innen.y + probe.y, probe.width - 24, 2);
        Rectangle daneben = new Rectangle(kante.x, kante.y + 10, kante.width, 2);

        Robot robot = new Robot();
        BufferedImage kanteVor = robot.createScreenCapture(kante);
        BufferedImage danebenVor = robot.createScreenCapture(daneben);

        SchichtFenster schicht = new SchichtFenster();
        schicht.zeigen(innen, rahmen);
        Thread.sleep(800);

        double dKante = unterschied(kanteVor, robot.createScreenCapture(kante));
        double dDaneben = unterschied(danebenVor, robot.createScreenCapture(daneben));
        boolean imFoto = dKante > dDaneben + 20;
        Log.info("Schicht", Sprache.t("Hidden from screenshots (Windows says): ",
                "Vor Fotos versteckt (laut Windows): ") + schicht.vorAufnahmeVersteckt()
                + Sprache.t(" | build supports it: ", " | Windows kann es: ") + SchichtFenster.aufnahmeSchutzMoeglich());
        Log.info("Schicht", Sprache.t(
                "Screenshot check: edge changed " + Sprache.komma(dKante, 1) + ", next to it "
                        + Sprache.komma(dDaneben, 1) + " -> frame " + (imFoto ? "VISIBLE in screenshots" : "not in screenshots"),
                "Foto-Gegenprobe: Kante veraendert " + Sprache.komma(dKante, 1) + ", daneben "
                        + Sprache.komma(dDaneben, 1) + " -> Rahmen " + (imFoto ? "IM FOTO ZU SEHEN" : "nicht im Foto")));

        // 30 s stehen lassen; verschiebt Autor das Spiel, wandert die Schicht mit.
        long ende = System.currentTimeMillis() + DAUER_MS;
        while (System.currentTimeMillis() < ende) {
            Thread.sleep(1000);
            Rectangle neu = spiel.clientArea();
            if (neu == null) {
                schicht.verstecken();
            } else if (!neu.equals(innen)) {
                innen = neu;
                rahmen = beispielRahmen(innen);
                schicht.zeigen(innen, rahmen);
            }
        }

        schicht.schliessen();
        haken.stop();
        Log.info("Schicht", Sprache.t("Probe finished.", "Probe beendet."));
        Thread.sleep(300);
        System.exit(0);
    }

    /** Drei Rahmen nebeneinander im oberen Drittel, passend zur Spielgroesse. */
    private static List<SchichtFenster.Rahmen> beispielRahmen(Rectangle innen) {
        int b = innen.width;
        int h = innen.height;
        int breite = b * 24 / 100;
        int hoehe = h * 22 / 100;
        int y = Math.max(40, h * 12 / 100);
        return List.of(
                new SchichtFenster.Rahmen(new Rectangle(b * 6 / 100, y, breite, hoehe),
                        SchichtFenster.SICHER, Sprache.t("safe", "sicher")),
                new SchichtFenster.Rahmen(new Rectangle(b * 38 / 100, y, breite, hoehe),
                        SchichtFenster.MACHBAR, Sprache.t("doable", "machbar")),
                new SchichtFenster.Rahmen(new Rectangle(b * 70 / 100, y, breite, hoehe),
                        SchichtFenster.NIEMALS, Sprache.t("never", "niemals")));
    }

    /** Mittlerer Farbunterschied je Punkt (0..255). */
    private static double unterschied(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (w <= 0 || h <= 0) return 0;
        long summe = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                summe += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF))
                        + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
            }
        }
        return summe / (3.0 * w * h);
    }

    /** window.title aus config.properties (nur lesen), sonst "Hustle Castle". */
    private static String fensterTitel() {
        Properties p = new Properties();
        try {
            Path datei = Ordner.bei("config.properties");
            if (Files.exists(datei)) {
                try (InputStream in = Files.newInputStream(datei)) {
                    p.load(in);
                }
            }
        } catch (Exception e) {
            // dann eben der Standard
        }
        return p.getProperty("window.title", "Hustle Castle");
    }

    private static String punkt(Point p) {
        return p.x + "," + p.y;
    }
}
