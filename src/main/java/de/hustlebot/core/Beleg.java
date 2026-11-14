package de.hustlebot.core;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Beweisfotos nur noch auf Wunsch (Autor 01.11.: "Am besten soll gar nichts wachsen. Die Bilder sollen nur
 * zwischengespeichert werden. Oder nur die noetigen Vorlagen sollen gespeichert sein."). Fotos, die der Bot nie wieder
 * einliest (event-stab, etagen, team, ...), gehen hier durch und landen standardmaessig NICHT auf der Platte; der
 * Rueckgabewert ist dann false, wie bei ImageIO.write ohne passenden Schreiber.
 *
 * Fuer die gezielte Fehlersuche: Datei beweisfotos.txt im Bot-Ordner mit "an". Sie gilt ab dem naechsten Foto, ohne
 * Neustart. Vorlagen, Namensbilder und alles, was der Bot wieder liest, laufen nicht hier durch.
 */
public final class Beleg {

    static final String SCHALTER = "beweisfotos.txt";

    /** Nur fuer Tests: eine andere Schalterdatei. */
    private static volatile Path schalterFuerTest;

    private Beleg() { }

    /** Sollen Beweisfotos geschrieben werden? Liest die Schalterdatei jedes Mal -- Fotos sind selten. */
    public static boolean an() {
        Path t = schalterFuerTest;
        return new Schalterdatei(t != null ? t : Ordner.bei(SCHALTER), "Beweisfotos", false).lesen();
    }

    /**
     * Ausschnitte, die nur Autors eigene Auswertung braucht (Arena-Boegen, Held-Motive, Schmuckschrift): bei den Freunden
     * liest sie niemand. Darum nur, wenn die Beweisfotos eingeschaltet sind (frueher auch mit Autors Lizenzschluessel).
     */
    public static boolean autorsSammlung() {
        return an();
    }

    /** Wie ImageIO.write; ausgeschaltet: schreibt nichts, legt keinen Ordner an, liefert false. */
    public static boolean schreiben(BufferedImage bild, String format, File ziel) throws IOException {
        if (!an()) return false;
        ordnerAnlegen(ziel.toPath());
        return ImageIO.write(bild, format, ziel);
    }

    /** Wie Imgcodecs.imwrite; ausgeschaltet: schreibt nichts, legt keinen Ordner an, liefert false. */
    public static boolean schreiben(String ziel, Mat bild) {
        if (!an()) return false;
        try {
            ordnerAnlegen(Path.of(ziel));
        } catch (IOException e) {
            Log.debug("Beweisfotos", "Ordner fuer " + ziel + " nicht angelegt: " + e.getMessage());
            return false;
        }
        return Imgcodecs.imwrite(ziel, bild);
    }

    private static void ordnerAnlegen(Path datei) throws IOException {
        Path ordner = datei.getParent();
        if (ordner != null) Files.createDirectories(ordner);
    }

    static void schalterFuerTest(Path datei) {
        schalterFuerTest = datei;
    }
}
