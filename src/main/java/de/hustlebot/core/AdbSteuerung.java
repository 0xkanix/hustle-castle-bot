package de.hustlebot.core;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Handy-Steuerung ueber ADB (Handy-Umbau, Autor 19.11.; Anleitung doku/handy/ANLEITUNG-ADB.md).
 *
 * Beruehrungen: eine dauerhaft offene "adb shell", in die "input motionevent DOWN/MOVE/UP x y" geschrieben wird (Android 11
 * und neuer; das Edge 60 Fusion hat Android 15). Jeder input-Befehl braucht auf dem Handy ~0,1 s -- deshalb werden
 * Zwischenbewegungen bei gedruecktem Finger zusammengefasst (hoechstens eine je ZWISCHEN_MS), die letzte Stelle kommt
 * immer vor dem Loslassen an. Ohne gedrueckten Finger wird gar nichts geschickt (es gibt keinen Zeiger).
 *
 * Fotos: "adb exec-out screencap -p" (ganzer Bildschirm als PNG), FOTO_GUELTIG_MS lang zwischengespeichert, damit mehrere
 * Ausschnitte desselben Moments nicht je ein Foto kosten.
 */
public final class AdbSteuerung implements Steuerung, AutoCloseable {

    static final long ZWISCHEN_MS = 60;
    static final long FOTO_GUELTIG_MS = 40;

    private final String adb;
    private final String geraet;
    private Process shell;
    private BufferedWriter zumHandy;
    private int x, y;
    private boolean unten;
    private long letzteZwischenUm;
    private boolean zwischenOffen;
    private BufferedImage letztesFoto;
    private long fotoUm;

    /** adb = Pfad zu adb (z. B. "C:\\platform-tools\\adb.exe"), geraet = "IP:Port" oder Seriennummer, null = das einzige. */
    public AdbSteuerung(String adb, String geraet) {
        this.adb = adb;
        this.geraet = geraet;
    }

    private List<String> befehl(String... teile) {
        List<String> l = new ArrayList<>();
        l.add(adb);
        if (geraet != null && !geraet.isBlank()) {
            l.add("-s");
            l.add(geraet);
        }
        l.addAll(List.of(teile));
        return l;
    }

    private synchronized void senden(String zeile) {
        try {
            if (shell == null || !shell.isAlive()) {
                shell = new ProcessBuilder(befehl("shell")).redirectErrorStream(true).start();
                zumHandy = new BufferedWriter(new OutputStreamWriter(shell.getOutputStream(), StandardCharsets.US_ASCII));
                Thread leser = new Thread(() -> {   // Ausgabe der Shell wegraeumen, sonst blockiert sie irgendwann
                    try (InputStream in = shell.getInputStream()) {
                        in.transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                        // Shell zu
                    }
                }, "adb-shell-leser");
                leser.setDaemon(true);
                leser.start();
            }
            zumHandy.write(zeile);
            zumHandy.write('\n');
            zumHandy.flush();
        } catch (IOException e) {
            Log.warn("Handy", "ADB-Befehl ging nicht: " + zeile + " (" + e.getMessage() + ")");
            shell = null;
        }
    }

    @Override
    public synchronized void bewegen(int nx, int ny) {
        x = nx;
        y = ny;
        if (!unten) return;
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzteZwischenUm >= ZWISCHEN_MS) {
            senden("input motionevent MOVE " + x + " " + y);
            letzteZwischenUm = jetzt;
            zwischenOffen = false;
        } else {
            zwischenOffen = true;   // kommt spaetestens vor dem Loslassen
        }
    }

    @Override
    public synchronized void druecken() {
        senden("input motionevent DOWN " + x + " " + y);
        unten = true;
        letzteZwischenUm = System.currentTimeMillis();
        zwischenOffen = false;
    }

    @Override
    public synchronized void loslassen() {
        if (!unten) return;
        if (zwischenOffen) senden("input motionevent MOVE " + x + " " + y);
        senden("input motionevent UP " + x + " " + y);
        unten = false;
        zwischenOffen = false;
    }

    @Override
    public synchronized Point zeiger() {
        return new Point(x, y);
    }

    @Override
    public synchronized BufferedImage foto(Rectangle bereich) {
        long jetzt = System.currentTimeMillis();
        if (letztesFoto == null || jetzt - fotoUm > FOTO_GUELTIG_MS) {
            letztesFoto = ganzesFoto();
            fotoUm = System.currentTimeMillis();
        }
        if (letztesFoto == null) return null;
        int bx = Math.max(0, bereich.x), by = Math.max(0, bereich.y);
        int bw = Math.min(letztesFoto.getWidth() - bx, bereich.width), bh = Math.min(letztesFoto.getHeight() - by, bereich.height);
        if (bw <= 0 || bh <= 0) return null;
        BufferedImage aus = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_RGB);
        aus.getGraphics().drawImage(letztesFoto.getSubimage(bx, by, bw, bh), 0, 0, null);
        return aus;
    }

    /** Ganzer Bildschirm des Handys (null, wenn es nicht ging). */
    public BufferedImage ganzesFoto() {
        try {
            Process p = new ProcessBuilder(befehl("exec-out", "screencap", "-p")).start();
            BufferedImage b;
            try (InputStream in = p.getInputStream()) {
                b = ImageIO.read(in);
            }
            p.waitFor();
            return b;
        } catch (IOException e) {
            Log.warn("Handy", "Bildschirmfoto ueber ADB ging nicht: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (unten) loslassen();   // nie mit aufgelegtem Finger aufhoeren
        if (shell != null) {
            try {
                zumHandy.write("exit\n");
                zumHandy.flush();
            } catch (IOException ignored) {
                // schon zu
            }
            try {
                shell.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            shell.destroy();
        }
    }
}
