package de.hustlebot.discord;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * "!live" -- ein Bild in Discord, das sich selbst erneuert.
 *
 * Autor, 23.09.: "wenn ich einmal !live eingebe ... soll sich alle 0.5
 * Sekunden refreshen, mich nicht vollspammen mit screenshots. Das soll alles
 * in einer Nachricht sein."
 *
 * Es ist EINE Nachricht, deren Bild immer wieder ersetzt wird. Alle 0,5
 * Sekunden geht nicht: Discord sperrt einen Bot, der dieselbe Nachricht
 * oefter als etwa einmal je Sekunde aendert. Autor hat sich fuer alle
 * 2 Sekunden entschieden, und nach 5 Minuten hoert es von selbst auf -- damit
 * es nicht die ganze Nacht Datenvolumen frisst, wenn man es vergisst.
 *
 * Das Bild wird verkleinert und als JPG geschickt (rund 60-100 KB statt
 * 1 MB), damit es auch am Handy im Mobilnetz fluessig bleibt.
 */
public final class LiveBild {

    private static final long TAKT_MS = 2000;
    private static final long DAUER_MS = 5 * 60_000L;
    private static final int BREITE = 960;

    private final DiscordClient client;
    private final Supplier<BufferedImage> quelle;
    /**
     * Ersatzquelle (16.10.): Die Hauptquelle liefert nur das Spielfenster
     * (PrintWindow). Geht das nicht (null), kommt das Bild von hier -- wie
     * frueher der Bildschirmbereich. Null, wenn es keinen Ersatz gibt.
     */
    private final Supplier<BufferedImage> ersatz;
    /** Ersatz schon gemeldet? Einmal je Livebild, nicht alle 2 Sekunden. */
    private volatile boolean ersatzGemeldet = false;

    private volatile boolean laeuft = false;
    /**
     * Zaehlt die Livebilder durch. "!live stop" und gleich danach "!live":
     * Der alte Faden schlaeft da noch und saehe beim Aufwachen wieder
     * "laeuft" -- ohne die Nummer liefen dann zwei Livebilder nebeneinander.
     */
    private volatile int runde = 0;

    public LiveBild(DiscordClient client, Supplier<BufferedImage> quelle) {
        this(client, quelle, null);
    }

    /** Mit Ersatzquelle: zuerst quelle (nur das Spielfenster), bei null der ersatz (bisheriger Weg). */
    public LiveBild(DiscordClient client, Supplier<BufferedImage> quelle, Supplier<BufferedImage> ersatz) {
        this.client = client;
        this.quelle = quelle;
        this.ersatz = ersatz;
    }

    /** Das naechste Bild: Spielfenster, sonst Ersatz. Der Wechsel steht einmal je Livebild im Protokoll. */
    private BufferedImage naechstesBild() {
        BufferedImage bild = null;
        try {
            bild = quelle.get();
        } catch (Exception e) {
            Log.debug("Discord", "Livebild: Spielfenster nicht aufgenommen: " + e.getMessage());
        }
        if (bild != null || ersatz == null) return bild;
        if (!ersatzGemeldet) {
            ersatzGemeldet = true;
            Log.info("Discord", Sprache.t("Live view: window photo not possible -- using the screen area instead.",
                    "Livebild: Fensterfoto nicht moeglich -- es wird der Bildschirmbereich genommen."));
        }
        return ersatz.get();
    }

    public boolean laeuft() { return laeuft; }

    /** Beendet das Livebild beim naechsten Takt. */
    public void stoppen() { laeuft = false; }

    /** Startet das Livebild im angegebenen Kanal. False, wenn es schon laeuft. */
    public synchronized boolean starten(String kanal) {
        if (laeuft) return false;
        laeuft = true;
        ersatzGemeldet = false;
        int meine = ++runde;
        Thread t = new Thread(() -> schleife(kanal, meine), "discord-live");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void schleife(String kanal, int meine) {
        DateTimeFormatter uhr = DateTimeFormatter.ofPattern("HH:mm:ss");
        long ende = System.currentTimeMillis() + DAUER_MS;
        String endeText = LocalTime.now().plusSeconds(DAUER_MS / 1000)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        String id = "";
        byte[] letztes = null;
        String letzteZeit = "";

        try {
            while (laeuft && runde == meine && System.currentTimeMillis() < ende) {
                long beginn = System.currentTimeMillis();
                byte[] bild = jpg(naechstesBild());
                if (bild != null) {
                    letztes = bild;
                    letzteZeit = LocalTime.now().format(uhr);
                    String text = "🔴 **Live** · " + letzteZeit + " · "
                            + Sprache.t("stops by itself at ", "endet von selbst um ")
                            + endeText + " · `!live stop`";
                    if (id.isEmpty()) {
                        id = client.sendFileAndGetId(kanal, text, "live.jpg", bild);
                        if (id.isEmpty()) {
                            Log.warn("Discord", Sprache.t("Live view: the first image could not be sent.",
                                    "Livebild: das erste Bild liess sich nicht senden."));
                            break;
                        }
                    } else {
                        long warten = client.replaceFile(kanal, id, text, "live.jpg", bild);
                        if (warten < 0) break;               // Nachricht geloescht o.ae.
                        if (warten > 0) Thread.sleep(warten); // Discord bremst
                    }
                }
                long rest = TAKT_MS - (System.currentTimeMillis() - beginn);
                if (rest > 0) Thread.sleep(rest);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (runde == meine) laeuft = false;
            }
            if (!id.isEmpty() && letztes != null) {
                client.replaceFile(kanal, id, "⏹ " + Sprache.t("Live picture ended – last picture from ",
                        "Livebild beendet – letztes Bild von ") + letzteZeit
                        + Sprache.t(". Again with `!live`.", ". Neu mit `!live`."),
                        "live.jpg", letztes);
            }
        }
    }

    /** Verkleinert das Bild und macht ein JPG daraus. */
    static byte[] jpg(BufferedImage bild) {
        if (bild == null) return null;
        try {
            int b = Math.min(BREITE, bild.getWidth());
            int h = (int) Math.round(bild.getHeight() * (b / (double) bild.getWidth()));
            BufferedImage klein = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = klein.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bild, 0, 0, b, h, null);
            g.dispose();

            ImageWriter schreiber = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam p = schreiber.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.7f);
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            try (ImageOutputStream strom = ImageIO.createImageOutputStream(aus)) {
                schreiber.setOutput(strom);
                schreiber.write(null, new IIOImage(klein, null, null), p);
            } finally {
                schreiber.dispose();
            }
            return aus.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
