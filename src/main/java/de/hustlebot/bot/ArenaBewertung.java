package de.hustlebot.bot;

import de.hustlebot.core.Dateien;
import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;

import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Autors Bewertungen aus den Arena-Boegen (gut / mittel / schlecht / neutral), Autor 30.10.: "Du sollst meine Bewertungen in den Bot
 * uebernehmen. Nur auf meinem PC. Sollte die Pruefung aber nicht langsamer machen."
 *
 * Darum keine 390 neuen Vorlagen, sondern derselbe Bildvergleich wie auf den Boegen: je Bild 8 x 8 Farbmittel, Vergleich mit den
 * gespeicherten Werten (Datei arena-bewertungen.txt, gebaut von werkzeuge\sitzung-22-10\bewertungen_einspielen.py). Das sind
 * Bruchteile einer Millisekunde je Feld. Ohne die Datei aendert sich nichts, sie liegt nur auf Autors PC.
 * Rueckrechnung 30.10. an 162 Kaempfen: Bewertung unter -12: 81 von 82 gewonnen, ab +6: 31 von 39.
 */
final class ArenaBewertung {

    private ArenaBewertung() {}

    static final String GUT = "gut", MITTEL = "mittel", SCHLECHT = "schlecht";

    /** @param goettlich gelber Rahmen (Autor 31.10.: goettlich zaehlt doppelt, nur bei gut und mittel) */
    private record Eintrag(String art, String wahl, float[] merkmal, boolean goettlich) {}

    private static List<Eintrag> alle;

    private static synchronized List<Eintrag> alle() {
        if (alle == null) {
            List<Eintrag> l = List.of();
            Path datei = Ordner.bei("arena-bewertungen.txt");
            try {
                if (Files.exists(datei)) {
                    l = lesen(Dateien.zeilen(datei));
                    Log.info("Arena", "Bewertungen aus den Boegen geladen: " + l.size());
                }
            } catch (Exception e) {
                Log.warn("Arena", "arena-bewertungen.txt nicht lesbar: " + e.getMessage());
            }
            alle = l;
        }
        return alle;
    }

    /** Zeilen: Art TAB Wahl TAB Schluessel TAB Zahl,Zahl,... (192 Zahlen), dahinter wahlweise TAB g = goettlich. Kaputte Zeilen werden uebergangen. */
    private static List<Eintrag> lesen(List<String> zeilen) {
        List<Eintrag> l = new ArrayList<>();
        for (String z : zeilen) {
            String[] t = z.split("\t");
            if (t.length < 4) continue;
            String[] zahlen = t[3].split(",");
            if (zahlen.length != 192) continue;
            try {
                float[] m = new float[192];
                for (int i = 0; i < 192; i++) m[i] = Float.parseFloat(zahlen[i]);
                l.add(new Eintrag(t[0], t[1], m, t.length >= 5 && "g".equals(t[4].trim())));
            } catch (NumberFormatException ignored) { }
        }
        return l;
    }

    /** Wie auf den Boegen: Artefakte und Totems duerfen staerker abweichen (Stufe, Hintergrund) als die anderen Bilder. */
    static double schwelle(String art) {
        return art.equals("artefakte") || art.equals("totems") ? 18 : 9;
    }

    /** Die Bewertung des aehnlichsten Bildes dieser Art, oder null: unbekannt. */
    static String wert(String art, float[] merkmal) {
        return wert(alle(), art, merkmal);
    }

    private static String wert(List<Eintrag> liste, String art, float[] merkmal) {
        if (merkmal == null) return null;
        double best = Double.MAX_VALUE;
        String wahl = null;
        for (Eintrag e : liste) {
            if (!e.art().equals(art)) continue;
            double d = abstand(merkmal, e.merkmal());
            if (d < best) {
                best = d;
                wahl = e.wahl();
            }
        }
        return best < schwelle(art) ? wahl : null;
    }

    /**
     * Autor 31.10.: "goettlich x2", nur bei gut und mittel; legendaer und mythisch bleiben x1. Rueckrechnung an 263 Kaempfen: alle 26 Niederlagen gegen
     * Spieler mit einem goettlichen Stueck, die Trefferquote steigt von 0,920 auf 0,928. Ist das aehnlichste Bild dieser Art goettlich?
     */
    static boolean goettlich(String art, float[] merkmal) {
        return goettlich(alle(), art, merkmal);
    }

    private static boolean goettlich(List<Eintrag> liste, String art, float[] merkmal) {
        if (merkmal == null) return false;
        double best = Double.MAX_VALUE;
        boolean g = false;
        for (Eintrag e : liste) {
            if (!e.art().equals(art)) continue;
            double d = abstand(merkmal, e.merkmal());
            if (d < best) {
                best = d;
                g = e.goettlich();
            }
        }
        return best < schwelle(art) && g;
    }

    /** Nur fuer den Test: dieselbe Suche mit Zeilen statt der Datei. */
    static String wertAusZeilen(List<String> zeilen, String art, float[] merkmal) {
        return wert(lesen(zeilen), art, merkmal);
    }

    /** Nur fuer den Test. */
    static boolean goettlichAusZeilen(List<String> zeilen, String art, float[] merkmal) {
        return goettlich(lesen(zeilen), art, merkmal);
    }

    static double abstand(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    private interface Pixel {
        /** r, g, b nach {@code aus}. */
        void lies(int x, int y, int[] aus);
    }

    /** Dieselbe Rechnung wie bogen.py merkmal(): 8 x 8 Felder, jedes zweite Bild-Punkt, Mittel von r, g, b. */
    private static float[] merkmal(int w, int h, Pixel p) {
        float[] v = new float[192];
        int[] rgb = new int[3];
        int i = 0;
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = gy * h / 8; y < (gy + 1) * h / 8; y += 2) {
                    for (int x = gx * w / 8; x < (gx + 1) * w / 8; x += 2) {
                        p.lies(x, y, rgb);
                        r += rgb[0];
                        g += rgb[1];
                        b += rgb[2];
                        n++;
                    }
                }
                int t = Math.max(1, n);
                v[i++] = (float) r / t;
                v[i++] = (float) g / t;
                v[i++] = (float) b / t;
            }
        }
        return v;
    }

    /** Merkmal eines Ausschnitts aus einem OpenCV-Bild (BGR), null, wenn er nicht hineinpasst. */
    static float[] merkmal(Mat bild, int x, int y, int w, int h) {
        if (bild == null || bild.empty() || bild.channels() < 3 || x < 0 || y < 0 || x + w > bild.cols() || y + h > bild.rows()) return null;
        int kan = bild.channels();
        byte[] d = new byte[w * h * kan];
        Mat teil = new Mat(bild, new Rect(x, y, w, h));
        try {
            // Ein Ausschnitt liegt nicht am Stueck im Speicher: erst kopieren, dann auf einmal holen.
            Mat kopie = teil.clone();
            try {
                kopie.get(0, 0, d);
            } finally {
                kopie.release();
            }
        } finally {
            teil.release();
        }
        return merkmal(w, h, (px, py, aus) -> {
            int i = (py * w + px) * kan;
            aus[0] = d[i + 2] & 255;
            aus[1] = d[i + 1] & 255;
            aus[2] = d[i] & 255;
        });
    }

    /** Merkmal eines Ausschnitts aus dem Bild des Spielfensters, null, wenn er nicht hineinpasst. */
    static float[] merkmal(BufferedImage bild, int x, int y, int w, int h) {
        if (bild == null || x < 0 || y < 0 || x + w > bild.getWidth() || y + h > bild.getHeight()) return null;
        return merkmal(w, h, (px, py, aus) -> {
            int c = bild.getRGB(x + px, y + py);
            aus[0] = (c >> 16) & 255;
            aus[1] = (c >> 8) & 255;
            aus[2] = c & 255;
        });
    }
}
