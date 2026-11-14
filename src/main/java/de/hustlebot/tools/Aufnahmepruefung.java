package de.hustlebot.tools;

import de.hustlebot.core.ScreenCapture;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Autor 24.10. (Ideenliste F13, F17, F22): Was das Aufnahme-Programm (Capture-Images) dem Freund SOFORT sagt, bevor ein Bild
 * gespeichert wird -- bisher pruefte {@code speichereUnter} nur "mindestens 6 x 6 Punkte", ein zu grosser, zu kleiner oder
 * doppelter Ausschnitt fiel erst im Spiel auf.
 *
 *  - F13 Test-Suche: der Ausschnitt wird im eigenen Foto gesucht (derselbe Vergleich wie im Bot, TM_CCOEFF_NORMED). Gruen =
 *    genau eine Stelle; gelb = es gibt eine aehnliche zweite Stelle; rot = zweite Stelle fast gleich oder der Ausschnitt wird
 *    nicht einmal an seiner eigenen Stelle wiedergefunden (einfarbig).
 *  - F17 Qualitaetswarnung: unter 12 Punkten, nahezu einfarbig, ueber 40 % des Fensters, unscharf. Dazu die Vorschau in
 *    dreifacher Groesse.
 *  - F22: passt die Fenstergroesse des Fotos zur Bot-Groesse (1159 x 770 innen)? Sonst wird nie etwas gefunden.
 *
 * Reine Rechnung ohne Fenster -- dadurch pruefbar. Setzt voraus, dass OpenCV geladen ist (das Aufnahme-Programm tut das in
 * main()); ohne OpenCV liefert {@link #bewerte} null und das Programm arbeitet wie bisher.
 */
public final class Aufnahmepruefung {

    private Aufnahmepruefung() {
    }

    /** Innenmass des Spielfensters bei 100 % Windows-Skalierung (wie GameWindow.INNEN_BREITE/-HOEHE). */
    public static final int SOLL_BREITE = 1159;
    public static final int SOLL_HOEHE = 770;
    /** Toleranz fuer das Fenstermass (wie GameWindow.innenPruefen). */
    static final int MASS_TOLERANZ = 2;

    static final int MIN_SEITE = 12;
    static final double GROSS_ANTEIL = 0.40;
    /** Standardabweichung der Grauwerte, darunter gilt ein Ausschnitt als einfarbig. */
    static final double EINFARBIG_STD = 6.0;
    /**
     * Varianz des Laplace-Bildes, darunter wirkt ein Ausschnitt unscharf/kontrastarm. Gemessen an den 712 mitgelieferten
     * Vorlagen (24.10.): der schwaechste echte Wert ist 97, keine liegt unter 60 -- gute Aufnahmen werden also nie gewarnt.
     */
    static final double UNSCHARF_VARIANZ = 60.0;
    /** Der Ausschnitt muss an seiner eigenen Stelle mindestens so gut wiedergefunden werden. */
    static final double ERWARTET_BESTE = 0.95;
    static final double ZWEITE_ROT = 0.90;
    static final double ZWEITE_GELB = 0.80;

    public enum Stufe { GRUEN, GELB, ROT }

    public enum Mangel { NICHT_GEFUNDEN, NICHT_EINDEUTIG, UNSICHER, ZU_KLEIN, EINFARBIG, ZU_GROSS, UNSCHARF }

    /** Ergebnis der Test-Suche: beste und zweitbeste getrennte Stelle (zweite = 0, wenn es keine gibt). */
    public record Suchtest(double beste, double zweite) {
    }

    /**
     * Das Gesamtergebnis: Stufe (Ampel), die Maengel, die Zahlen und der fertige Text (je Mangel eine Zeile, mit "&lt;br&gt;"
     * getrennt, ohne html-Rahmen).
     */
    public record Bericht(Stufe stufe, EnumSet<Mangel> maengel, Suchtest suche, int breite, int hoehe, String text) {
    }

    // ------------------------------------------------------------------------------------------------------------
    //  F13: Test-Suche
    // ------------------------------------------------------------------------------------------------------------

    /** Sucht den Ausschnitt im Foto und meldet die beste und die zweitbeste getrennte Stelle. */
    public static Suchtest suche(BufferedImage foto, Rectangle rahmen) {
        Rectangle r = begrenzt(foto, rahmen);
        if (r == null) return new Suchtest(0, 0);
        Mat f = ScreenCapture.toMatAusschnitt(foto, 0, 0, foto.getWidth(), foto.getHeight());
        Mat v = ScreenCapture.toMatAusschnitt(foto, r.x, r.y, r.width, r.height);
        Mat erg = new Mat();
        try {
            Imgproc.matchTemplate(f, v, erg, Imgproc.TM_CCOEFF_NORMED);
            Core.patchNaNs(erg, 0);   // ein einfarbiger Ausschnitt hat keine Streuung -> kein Vergleichswert
            Core.MinMaxLocResult m = Core.minMaxLoc(erg);
            double beste = m.maxVal;
            // Die Umgebung der besten Stelle ausblenden (sonst waere die zweite Stelle nur der Nachbar-Punkt) und noch einmal.
            int ax = Math.max(2, r.width / 2), ay = Math.max(2, r.height / 2);
            Imgproc.rectangle(erg, new org.opencv.core.Point(m.maxLoc.x - ax, m.maxLoc.y - ay),
                    new org.opencv.core.Point(m.maxLoc.x + ax, m.maxLoc.y + ay), new Scalar(-1), -1);
            double zweite = Core.minMaxLoc(erg).maxVal;
            return new Suchtest(beste, Math.max(0, zweite));
        } finally {
            erg.release();
            v.release();
            f.release();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    //  F17: Qualitaet
    // ------------------------------------------------------------------------------------------------------------

    /** Die Warnungen zur Qualitaet des Ausschnitts (ohne Suche): zu klein, einfarbig, zu gross, unscharf. */
    public static EnumSet<Mangel> qualitaet(BufferedImage foto, Rectangle rahmen) {
        EnumSet<Mangel> m = EnumSet.noneOf(Mangel.class);
        Rectangle r = begrenzt(foto, rahmen);
        if (r == null) return m;
        if (r.width < MIN_SEITE || r.height < MIN_SEITE) m.add(Mangel.ZU_KLEIN);
        if ((double) r.width * r.height > GROSS_ANTEIL * foto.getWidth() * foto.getHeight()) m.add(Mangel.ZU_GROSS);
        Mat farbe = ScreenCapture.toMatAusschnitt(foto, r.x, r.y, r.width, r.height);
        Mat grau = new Mat();
        Mat lap = new Mat();
        MatOfDouble mittel = new MatOfDouble(), streu = new MatOfDouble();
        try {
            Imgproc.cvtColor(farbe, grau, Imgproc.COLOR_BGR2GRAY);
            Core.meanStdDev(grau, mittel, streu);
            double std = streu.get(0, 0)[0];
            if (std < EINFARBIG_STD) {
                m.add(Mangel.EINFARBIG);
            } else if (r.width >= 3 && r.height >= 3) {
                Imgproc.Laplacian(grau, lap, CvType.CV_64F);
                Core.meanStdDev(lap, mittel, streu);
                double s = streu.get(0, 0)[0];
                if (s * s < UNSCHARF_VARIANZ) m.add(Mangel.UNSCHARF);
            }
        } finally {
            farbe.release();
            grau.release();
            lap.release();
            mittel.release();
            streu.release();
        }
        return m;
    }

    /** Alles zusammen: Test-Suche + Qualitaet, mit fertigem Text. Null, wenn OpenCV nicht bereit ist oder der Rahmen leer. */
    public static Bericht bewerte(BufferedImage foto, Rectangle rahmen) {
        try {
            Rectangle r = begrenzt(foto, rahmen);
            if (r == null) return null;
            EnumSet<Mangel> maengel = qualitaet(foto, rahmen);
            Suchtest s = suche(foto, rahmen);
            if (!maengel.contains(Mangel.EINFARBIG)) {   // einfarbig sagt schon alles
                if (s.beste() < ERWARTET_BESTE) maengel.add(Mangel.NICHT_GEFUNDEN);
                else if (s.zweite() >= ZWEITE_ROT) maengel.add(Mangel.NICHT_EINDEUTIG);
                else if (s.zweite() >= ZWEITE_GELB) maengel.add(Mangel.UNSICHER);
            }
            return new Bericht(stufe(maengel), maengel, s, r.width, r.height, text(maengel, s, r.width, r.height));
        } catch (Throwable e) {   // OpenCV nicht geladen o. ae. -- dann einfach keine Pruefung
            return null;
        }
    }

    static Stufe stufe(EnumSet<Mangel> m) {
        if (m.contains(Mangel.NICHT_GEFUNDEN) || m.contains(Mangel.NICHT_EINDEUTIG) || m.contains(Mangel.EINFARBIG)) {
            return Stufe.ROT;
        }
        return m.isEmpty() ? Stufe.GRUEN : Stufe.GELB;
    }

    private static String z(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return Sprache.istEnglisch() ? s : s.replace('.', ',');
    }

    static String text(EnumSet<Mangel> m, Suchtest s, int breite, int hoehe) {
        List<String> zeilen = new ArrayList<>();
        if (m.isEmpty()) {
            zeilen.add(Sprache.t("Looks good: found exactly once in the picture (" + z(s.beste()) + ").",
                    "Sieht gut aus: wird im Foto genau 1× gefunden (" + z(s.beste()) + ")."));
        }
        if (m.contains(Mangel.EINFARBIG)) {
            zeilen.add(Sprache.t("Almost one single colour -- the bot cannot recognise that again. Frame something with more detail.",
                    "Fast einfarbig -- das kann der Bot nicht wiedererkennen. Rahme etwas mit mehr Einzelheiten ein."));
        }
        if (m.contains(Mangel.NICHT_GEFUNDEN)) {
            zeilen.add(Sprache.t("Not found again in the picture (" + z(s.beste()) + ") -- the frame is unclear. Take a new picture or frame a different spot.",
                    "Wird im Foto nicht wiedergefunden (" + z(s.beste()) + ") -- der Ausschnitt ist unklar. Neu aufnehmen oder eine andere Stelle einrahmen."));
        }
        if (m.contains(Mangel.NICHT_EINDEUTIG)) {
            zeilen.add(Sprache.t("Not unique: the same cut-out fits a second spot too (" + z(s.zweite()) + "). The bot could click the wrong place -- frame a bit more of the surroundings or a more special part.",
                    "Nicht eindeutig: derselbe Ausschnitt passt noch an eine zweite Stelle (" + z(s.zweite()) + "). Der Bot könnte an die falsche Stelle klicken -- nimm etwas mehr Umgebung oder einen besonderen Teil."));
        }
        if (m.contains(Mangel.UNSICHER)) {
            zeilen.add(Sprache.t("A similar spot exists (" + z(s.zweite()) + ") -- probably fine, but check the frame.",
                    "Es gibt eine ähnliche Stelle (" + z(s.zweite()) + ") -- wahrscheinlich in Ordnung, prüf den Rahmen."));
        }
        if (m.contains(Mangel.ZU_KLEIN)) {
            zeilen.add(Sprache.t("Very small (" + breite + "x" + hoehe + "): under " + MIN_SEITE + " points. Frame it a bit larger if you can.",
                    "Sehr klein (" + breite + "x" + hoehe + "): unter " + MIN_SEITE + " Punkten. Zieh den Rahmen etwas größer, wenn es geht."));
        }
        if (m.contains(Mangel.ZU_GROSS)) {
            zeilen.add(Sprache.t("Very big: more than 40 % of the window. Frame only the spot itself.",
                    "Sehr groß: mehr als 40 % des Fensters. Rahme nur die Stelle selbst ein."));
        }
        if (m.contains(Mangel.UNSCHARF)) {
            zeilen.add(Sprache.t("Looks blurry or low in contrast. Check that the game is not zoomed and HD graphics are off.",
                    "Wirkt unscharf oder kontrastarm. Prüf, dass das Spiel nicht vergrößert ist und die HD-Grafik aus ist."));
        }
        return String.join("<br>", zeilen);
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Vorschau (F17)
    // ------------------------------------------------------------------------------------------------------------

    /** Der Ausschnitt in dreifacher Groesse (scharfe Punkte), hoechstens maxB x maxH -- dann kleiner. Null bei leerem Rahmen. */
    public static BufferedImage vorschau(BufferedImage foto, Rectangle rahmen, int maxB, int maxH) {
        Rectangle r = begrenzt(foto, rahmen);
        if (r == null) return null;
        double faktor = Math.min(3.0, Math.min(maxB / (double) r.width, maxH / (double) r.height));
        int w = Math.max(1, (int) Math.round(r.width * faktor)), h = Math.max(1, (int) Math.round(r.height * faktor));
        BufferedImage aus = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    faktor >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(foto, 0, 0, w, h, r.x, r.y, r.x + r.width, r.y + r.height, null);
        } finally {
            g.dispose();
        }
        return aus;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  F22: Fenstergroesse
    // ------------------------------------------------------------------------------------------------------------

    /** Leer, wenn das Foto so gross ist wie das Spielfenster bei 100 % Skalierung; sonst der Hinweis in Klartext. */
    public static String groessenHinweis(int breite, int hoehe) {
        if (Math.abs(breite - SOLL_BREITE) <= MASS_TOLERANZ && Math.abs(hoehe - SOLL_HOEHE) <= MASS_TOLERANZ) return "";
        return Sprache.t("Your game window is " + breite + " x " + hoehe + " points, it should be " + SOLL_BREITE + " x " + SOLL_HOEHE
                        + ". Set Windows scaling to 100 % (Settings > System > Display > Scale), restart the game and take the picture again -- "
                        + "otherwise the bot will not find your pictures later.",
                "Dein Spielfenster ist " + breite + " x " + hoehe + " Punkte groß, es sollte " + SOLL_BREITE + " x " + SOLL_HOEHE
                        + " sein. Stell die Windows-Skalierung auf 100 % (Einstellungen > System > Anzeige > Skalierung), starte das Spiel neu "
                        + "und mach das Bild noch einmal -- sonst findet der Bot deine Bilder später nicht.");
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Rectangle begrenzt(BufferedImage foto, Rectangle rahmen) {
        if (foto == null || rahmen == null) return null;
        Rectangle r = rahmen.intersection(new Rectangle(0, 0, foto.getWidth(), foto.getHeight()));
        return r.isEmpty() || r.width < 1 || r.height < 1 ? null : r;
    }
}
