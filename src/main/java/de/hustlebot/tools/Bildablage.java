package de.hustlebot.tools;

import de.hustlebot.core.Log;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Autor 24.10. (Ideenliste F19, F21): Dateiarbeit des Aufnahme-Programms MIT Rueckweg.
 *
 * Bisher ueberschrieb "Neu aufnehmen und weiter" ein Bild sofort -- den Rueckweg gab es nur ueber den Dateimanager. Jetzt
 * bleibt die alte Datei als {@code name.png.alt} liegen (der Bot liest nur *.png, das .alt stoert ihn nicht), und jede
 * Aktion liefert einen Rueckweg, den das {@link Journal} aufhebt: Knopf "Letztes Bild zuruecknehmen".
 * Nichts wird endgueltig geloescht: auch "Loeschen" in der Bilderliste benennt nur in .alt um.
 */
public final class Bildablage {

    public static final String ALT = ".alt";

    private Bildablage() {
    }

    /** Die Sicherungsdatei zu einem Bild: "x.png" -> "x.png.alt". */
    public static Path altDatei(Path bild) {
        return bild.resolveSibling(bild.getFileName().toString() + ALT);
    }

    /** Schreibt das PNG. Liegt dort schon eines, bleibt es als .alt liegen. Liefert den Rueckweg. */
    public static Runnable schreibeMitSicherung(Path ziel, BufferedImage bild) throws IOException {
        Files.createDirectories(ziel.getParent());
        Path alt = altDatei(ziel);
        boolean ueberschrieben = Files.exists(ziel);
        if (ueberschrieben) Files.move(ziel, alt, StandardCopyOption.REPLACE_EXISTING);
        Runnable zurueck = () -> {
            try {
                Files.deleteIfExists(ziel);
                if (ueberschrieben && Files.exists(alt)) {
                    Files.move(alt, ziel, StandardCopyOption.REPLACE_EXISTING);
                    jetzt(ziel);   // die wiederhergestellte Aufnahme zaehlt wieder als die neuere
                }
            } catch (IOException e) {
                Log.debug("Vorlagen", "Rueckgaengig fehlgeschlagen: " + ziel + " -- " + e.getMessage());
            }
        };
        try {
            if (!ImageIO.write(bild, "png", new File(ziel.toString()))) throw new IOException("kein PNG-Schreiber");
        } catch (IOException | RuntimeException e) {
            zurueck.run();
            throw e instanceof IOException io ? io : new IOException(e);
        }
        return zurueck;
    }

    /** "Loeschen": das Bild wird zu name.png.alt umbenannt (nichts geht verloren). Liefert den Rueckweg. */
    public static Runnable nachAlt(Path bild) throws IOException {
        Path alt = altDatei(bild);
        Files.move(bild, alt, StandardCopyOption.REPLACE_EXISTING);
        return () -> {
            try {
                Files.move(alt, bild, StandardCopyOption.REPLACE_EXISTING);
                jetzt(bild);
            } catch (IOException e) {
                Log.debug("Vorlagen", "Rueckgaengig fehlgeschlagen: " + bild + " -- " + e.getMessage());
            }
        };
    }

    /** Verschiebt eine Datei (z. B. nach templates-aussortiert). Liefert den Rueckweg. */
    public static Runnable verschiebe(Path von, Path nach) throws IOException {
        Files.createDirectories(nach.getParent());
        Files.move(von, nach, StandardCopyOption.REPLACE_EXISTING);
        return () -> {
            try {
                Files.createDirectories(von.getParent());
                Files.move(nach, von, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.debug("Vorlagen", "Rueckgaengig fehlgeschlagen: " + von + " -- " + e.getMessage());
            }
        };
    }

    /** Merkt den jetzigen Stand einer Textdatei (oder dass es sie nicht gibt); der Rueckweg stellt ihn wieder her. */
    public static Runnable dateiStand(Path datei) {
        byte[] inhalt;
        try {
            inhalt = Files.exists(datei) ? Files.readAllBytes(datei) : null;
        } catch (IOException e) {
            return () -> { };
        }
        return () -> {
            try {
                if (inhalt == null) Files.deleteIfExists(datei);
                else Files.write(datei, inhalt);
            } catch (IOException e) {
                Log.debug("Vorlagen", "Rueckgaengig fehlgeschlagen: " + datei + " -- " + e.getMessage());
            }
        };
    }

    private static void jetzt(Path datei) {
        try {
            Files.setLastModifiedTime(datei, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // das Datum ist nur fuer "die neuere Aufnahme gilt" -- ohne bleibt es beim alten
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Die Liste der Rueckwege: der letzte Eintrag zuerst. */
    public static final class Journal {
        /** Eine Aktion des Nutzers: Text fuer die Meldung, der Assistenten-Schritt davor (-1 = keiner) und die Rueckwege. */
        public record Eintrag(String text, int schritt, List<Runnable> zurueck) {
        }

        private static final int MAX = 100;
        private final Deque<Eintrag> stapel = new ArrayDeque<>();

        public synchronized void neu(String text, int schritt, Runnable... zurueck) {
            List<Runnable> liste = new ArrayList<>(List.of(zurueck));
            stapel.push(new Eintrag(text, schritt, liste));
            while (stapel.size() > MAX) stapel.removeLast();
        }

        /** Haengt einen weiteren Rueckweg an den letzten Eintrag (z. B. Punkte-Datei nach dem Speichern). */
        public synchronized void ergaenze(Runnable zurueck) {
            if (stapel.isEmpty() || zurueck == null) return;
            stapel.peek().zurueck().add(zurueck);
        }

        public synchronized boolean leer() {
            return stapel.isEmpty();
        }

        public synchronized String oberster() {
            return stapel.isEmpty() ? null : stapel.peek().text();
        }

        /** Nimmt die letzte Aktion zurueck (Rueckwege in umgekehrter Reihenfolge). Null, wenn nichts da ist. */
        public synchronized Eintrag zuruecknehmen() {
            if (stapel.isEmpty()) return null;
            Eintrag e = stapel.pop();
            for (int i = e.zurueck().size() - 1; i >= 0; i--) {
                try {
                    e.zurueck().get(i).run();
                } catch (RuntimeException ex) {
                    Log.debug("Vorlagen", "Rueckgaengig-Schritt fehlgeschlagen: " + ex);
                }
            }
            return e;
        }
    }
}
