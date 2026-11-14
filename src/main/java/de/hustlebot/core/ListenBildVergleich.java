package de.hustlebot.core;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autor 28.10.: Erkennt Arena-Gegner an Autors Listenbildern (ar_sicher_ / ar_machbar_ / ar_ungern_ / ar_block_ in
 * "My pictures\Arena lists") -- verglichen wird NUR die weisse Schrift.
 *
 * WARUM. Gemessen: das Bild von "Xenos" traf nur in 14 von 21 Pyramiden. Das Listenbild ist mit Hintergrund
 * ausgeschnitten und wurde farbig ueber das ganze Bild gesucht; die Schilder sehen aber je nach Lage anders aus
 * (blau, gold/orange beim 225er, grau besiegt). Dann fehlten die Listenpunkte, oder ein Blockierter wurde angegriffen.
 *
 * WIE. Wie Matcher.weisseSchrift(): beide Seiten werden zu Masken "hell UND farblos" (alle drei Farbkanaele hoch und
 * dicht beieinander) -- nur die Buchstaben bleiben, der Grund faellt heraus. Die Schwelle fuer "hell" liegt hier hoeher
 * (235 statt 185): Autors Listenbilder haben hellgrauen Grund (211/208/204), die Schrift ist fast reines Weiss (252).
 * Mit 185 war zwei Drittel jedes Listenbildes "weiss". Ist ein ganzes Schild abgedunkelt, rutscht die Schwelle mit
 * (17 unter dem Hellsten, nie unter 170) -- fuer beide Seiten gleich.
 * Gesucht wird nur im Name-Kasten EINES Schildes (+ Rand), in fuenf Groessen (90-110 %). Zaehlen muss zusaetzlich die
 * ABDECKUNG: fast alle weissen Punkte in der Zeile muessen im Treffer liegen -- sonst steckt ein kurzer Name
 * ("Lore") nur in einem laengeren ("LordBeri").
 *
 * GEMESSEN (28.10., 1315 verschiedene Schilder aus Autors Fotos und den Fehlerberichten, 30 Listenbilder):
 * gleiche Namen 0,854 bis 1,0 -- andere Namen hoechstens 0,831, mit Abdeckung ab 0,85 hoechstens 0,813.
 * Daher SCHWELLE 0,835 und MIN_ABDECKUNG 0,85. Feinere Groessenstufen brachten mehr falsche Treffer, nicht weniger.
 *
 * Schalterfrei: der Aufrufer (ArenaMode) prueft ArenaSchalter.an() -- bei den Freunden aendert sich so nichts.
 * Thread-sicher; die Masken der Listenbilder bleiben im Speicher (Pfad + Aenderungszeit + Groesse).
 */
public final class ListenBildVergleich {

    private ListenBildVergleich() { }

    /**
     * Ein Vergleich: Listenbild, seine Liste ("ar_block_" ...), der Name aus dem Dateinamen, der Wert (0-1), die
     * Abdeckung (0-1) und wo im Bild die Schrift lag.
     */
    public record Treffer(Path datei, String liste, String name, double wert, double abdeckung, Rectangle stelle) {
        /** Reicht es fuer "dieser Spieler steht auf der Liste"? */
        public boolean gilt() {
            return wert >= SCHWELLE && abdeckung >= MIN_ABDECKUNG;
        }
    }

    /** Ab diesem Wert gilt der Name als gleich (gemessen 28.10., siehe oben). */
    public static final double SCHWELLE = 0.835;
    /** So viel der weissen Schrift in der Namenszeile muss im Treffer liegen. */
    public static final double MIN_ABDECKUNG = 0.85;

    /** Rand um den Name-Kasten: der Name sitzt nicht immer gleich (Autors Formel x-0,046W ... +0,070W, oben+12 ... +33). */
    static final int RAND_X = 10;
    static final int RAND_Y = 12;
    /** Groessen von 90 bis 110 %. */
    static final double KLEINSTE = 0.90, GROESSTE = 1.10;
    /** Von Stufe zu Stufe hoechstens so viele Punkte Breite -- lange Namen brauchen feinere Stufen. */
    private static final double PUNKTE_JE_STUFE = 3.0;

    /**
     * Die Groessenstufen fuer eine Schrift dieser Breite: je Stufe ~3 Punkte breiter, hoechstens 5 %, mindestens 2 %
     * Abstand; 100 % ist immer dabei. Kurze Namen bekommen nur 5 Stufen -- mehr Stufen gaeben ihnen nur mehr
     * Gelegenheit, zufaellig in einen anderen Namen zu passen (gemessen 28.10.: feste 3-%-Stufen fuer alle hoben den
     * hoechsten falschen Wert von 0,813 auf 0,837).
     */
    static double[] groessen(int breite) {
        double schritt = Math.max(0.02, Math.min(0.05, PUNKTE_JE_STUFE / Math.max(1, breite)));
        int je = (int) Math.ceil((1.0 - KLEINSTE) / schritt - 1e-9);
        double[] g = new double[2 * je + 1];
        for (int i = 0; i <= je; i++) {
            double f = (double) i / je;
            g[je - i] = 1.0 - f * (1.0 - KLEINSTE);
            g[je + i] = 1.0 + f * (GROESSTE - 1.0);
        }
        g[je] = 1.0;
        return g;
    }

    /** "Weiss": hellster Farbkanal-Tiefstwert ab hier (siehe Klassenkommentar). */
    static final int WEISS_HELL = 235;
    static final int WEISS_HELL_MIN = 170;
    /** Abstand der Schwelle unter dem Hellsten (30. hellster farbloser Punkt). */
    private static final int WEISS_UNTER_HELLSTEM = 17;
    private static final int HELLSTE_PUNKTE = 30;
    /** Hoechstens so weit duerfen die drei Farbkanaele auseinanderliegen -- sonst ist es eine Farbe. */
    static final int WEISS_FARBE = 30;
    /** Mehr Weiss als das im Ausschnitt ist keine Schrift mehr (heller Grund ohne Namen). */
    private static final double HOECHSTENS_WEISS = 0.45;
    /** Weniger weisse Punkte sind kein Name. */
    private static final int MINDESTENS_WEISS = 20;
    /** Die Aenderungszeit eines Listenbildes hoechstens so oft nachsehen (9 Schilder x 50 Bilder je Pyramide). */
    static volatile long pruefenNachMs = 3000;

    // ------------------------------------------------------------------
    //  Oeffentlich
    // ------------------------------------------------------------------

    /** Das beste Listenbild, das im Name-Kasten GILT (Wert und Abdeckung reichen), sonst leer. */
    public static Optional<Treffer> besterTreffer(BufferedImage bild, Rectangle nameKasten, List<Path> listenBilder) {
        return messen(bild, nameKasten, listenBilder).stream().filter(Treffer::gilt).findFirst();
    }

    /**
     * Alle Listenbilder, die gelten, bester zuerst. Steht derselbe Spieler auf zwei Listen, entscheidet der Aufrufer
     * (bisher: die strengere gilt).
     */
    public static List<Treffer> alleTreffer(BufferedImage bild, Rectangle nameKasten, List<Path> listenBilder) {
        return messen(bild, nameKasten, listenBilder).stream().filter(Treffer::gilt).toList();
    }

    /** Alle Vergleiche OHNE Schwelle, bester zuerst -- fuers Protokoll und zum Nachmessen. */
    public static List<Treffer> messen(BufferedImage bild, Rectangle nameKasten, List<Path> listenBilder) {
        if (bild == null || nameKasten == null || listenBilder == null || listenBilder.isEmpty()) return List.of();
        int x0 = Math.max(0, nameKasten.x - RAND_X), y0 = Math.max(0, nameKasten.y - RAND_Y);
        int x1 = Math.min(bild.getWidth(), nameKasten.x + nameKasten.width + RAND_X);
        int y1 = Math.min(bild.getHeight(), nameKasten.y + nameKasten.height + RAND_Y);
        if (x1 - x0 < 12 || y1 - y0 < 8) return List.of();

        Karte schild = karte(bild.getRGB(x0, y0, x1 - x0, y1 - y0, null, 0, x1 - x0), x1 - x0, y1 - y0);
        try {
            if (schild.weisse < MINDESTENS_WEISS || schild.weisse > HOECHSTENS_WEISS * schild.breite * schild.hoehe) {
                return List.of();
            }
            List<Treffer> alle = new ArrayList<>();
            for (Path p : listenBilder) {
                Vorlage v = vorlage(p);
                if (v == null) continue;
                Treffer t = vergleichen(schild, v, x0, y0);
                if (t != null) alle.add(t);
            }
            alle.sort(Comparator.comparingDouble(Treffer::wert).reversed());
            return alle;
        } finally {
            schild.weich.release();
        }
    }

    /** Nur fuer Tests: alle gemerkten Masken vergessen. */
    static void vergessen() {
        VORLAGEN.clear();
        ohneWeissGemeldet.clear();
    }

    // ------------------------------------------------------------------
    //  Masken
    // ------------------------------------------------------------------

    /** Weisse Stellen eines Ausschnitts: geglaettet zum Vergleichen, dazu 0/1 zum Zaehlen. */
    private static final class Karte {
        final Mat weich;
        final byte[] weiss;
        final int breite;
        final int hoehe;
        final int weisse;

        Karte(Mat weich, byte[] weiss, int breite, int hoehe, int weisse) {
            this.weich = weich;
            this.weiss = weiss;
            this.breite = breite;
            this.hoehe = hoehe;
            this.weisse = weisse;
        }
    }

    /** Aus ARGB-Punkten die Weiss-Karte -- fuer Schild und Listenbild genau gleich. */
    private static Karte karte(int[] argb, int breite, int hoehe) {
        int n = breite * hoehe;
        int[] tief = new int[n];
        boolean[] farblos = new boolean[n];
        int[] verteilung = new int[256];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            int lo = Math.min(r, Math.min(g, b)), hi = Math.max(r, Math.max(g, b));
            tief[i] = lo;
            farblos[i] = hi - lo <= WEISS_FARBE;
            if (farblos[i]) verteilung[lo]++;
        }
        int schwelle = schwelle(verteilung);
        byte[] weiss = new byte[n];
        float[] f = new float[n];
        int weisse = 0;
        for (int i = 0; i < n; i++) {
            if (farblos[i] && tief[i] >= schwelle) {
                weiss[i] = 1;
                f[i] = 1f;
                weisse++;
            }
        }
        Mat m = new Mat(hoehe, breite, CvType.CV_32F);
        m.put(0, 0, f);
        // leicht weichzeichnen: ein Bildpunkt Versatz soll einen Buchstaben nicht kippen (wie Matcher.weissMaske)
        Imgproc.GaussianBlur(m, m, new Size(3, 3), 0);
        return new Karte(m, weiss, breite, hoehe, weisse);
    }

    /**
     * 235, wenn es reines Weiss gibt (so gemessen: 1311 von 1315 Schildern, Schrift 250-255). Ist das ganze Bild
     * dunkler: 17 unter dem 30. hellsten farblosen Punkt, nie unter 170.
     */
    static int schwelle(int[] verteilung) {
        int gezaehlt = 0, hellster = 0;
        for (int v = 255; v >= 0; v--) {
            gezaehlt += verteilung[v];
            if (gezaehlt >= HELLSTE_PUNKTE) {
                hellster = v;
                break;
            }
        }
        if (hellster >= WEISS_HELL + 10) return WEISS_HELL;
        return Math.max(WEISS_HELL_MIN, Math.min(WEISS_HELL, hellster - WEISS_UNTER_HELLSTEM));
    }

    // ------------------------------------------------------------------
    //  Listenbilder (gemerkt)
    // ------------------------------------------------------------------

    /** Ein Listenbild als Weiss-Maske, eng um die Schrift geschnitten, in allen Groessen. */
    private static final class Vorlage {
        final Path datei;
        final String liste;
        final String name;
        final long stand;
        final long groesse;
        final Mat[] stufen;
        volatile long geprueft;

        Vorlage(Path datei, long stand, long groesse, Mat[] stufen) {
            this.datei = datei;
            String n = datei.getFileName().toString();
            this.liste = ArenaVorlagenDoppelt.liste(n);
            this.name = ArenaVorlagenDoppelt.nameAusDatei(n);
            this.stand = stand;
            this.groesse = groesse;
            this.stufen = stufen;
            this.geprueft = System.currentTimeMillis();
        }
    }

    private static final Map<Path, Vorlage> VORLAGEN = new ConcurrentHashMap<>();
    /** Listenbilder ohne weisse Schrift -- einmal sagen reicht. */
    private static final Set<Path> ohneWeissGemeldet = ConcurrentHashMap.newKeySet();
    /** Platzhalter fuer "lesbar, aber ohne Schrift" (damit das Bild nicht bei jedem Schild neu gelesen wird). */
    private static final Mat[] KEINE = new Mat[0];

    /** Das Listenbild aus dem Speicher; neu gelesen, wenn sich die Datei geaendert hat. null = nicht brauchbar. */
    private static Vorlage vorlage(Path p) {
        Path schluessel = p.toAbsolutePath().normalize();
        Vorlage v = VORLAGEN.get(schluessel);
        long jetzt = System.currentTimeMillis();
        if (v != null && jetzt - v.geprueft < pruefenNachMs) return v.stufen.length == 0 ? null : v;
        try {
            long stand = Files.getLastModifiedTime(schluessel).toMillis();
            long groesse = Files.size(schluessel);
            if (v != null && v.stand == stand && v.groesse == groesse) {
                v.geprueft = jetzt;
                return v.stufen.length == 0 ? null : v;
            }
            Vorlage neu = new Vorlage(schluessel, stand, groesse, stufen(schluessel));
            // Die alten Masken nicht freigeben: ein anderer Faden kann gerade damit vergleichen (der Speicher raeumt sie weg).
            VORLAGEN.put(schluessel, neu);
            return neu.stufen.length == 0 ? null : neu;
        } catch (Exception e) {
            VORLAGEN.remove(schluessel);
            Log.debug("Arena", "Listenbild nicht lesbar: " + schluessel.getFileName() + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private static Mat[] stufen(Path datei) throws java.io.IOException {
        BufferedImage b = ImageIO.read(datei.toFile());
        if (b == null || b.getWidth() < 8 || b.getHeight() < 6) return KEINE;
        Karte k = karte(b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()), b.getWidth(), b.getHeight());
        try {
            if (k.weisse < MINDESTENS_WEISS) {
                if (ohneWeissGemeldet.add(datei)) {
                    Log.warn("Arena", Sprache.t(datei.getFileName() + " has hardly any white lettering -- it cannot be "
                                    + "compared by its letters.",
                            datei.getFileName() + " hat kaum weisse Schrift -- es laesst sich nicht ueber die "
                                    + "Buchstaben vergleichen."));
                }
                return KEINE;
            }
            // eng um die Schrift (3 Punkte Rand): wie knapp Autor ausgeschnitten hat, spielt dann keine Rolle
            int minX = k.breite, minY = k.hoehe, maxX = -1, maxY = -1;
            for (int y = 0; y < k.hoehe; y++) {
                for (int x = 0; x < k.breite; x++) {
                    if (k.weiss[y * k.breite + x] == 0) continue;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            int x0 = Math.max(0, minX - 3), y0 = Math.max(0, minY - 3);
            int x1 = Math.min(k.breite, maxX + 4), y1 = Math.min(k.hoehe, maxY + 4);
            Mat eng = k.weich.submat(y0, y1, x0, x1).clone();
            double[] groessen = groessen(eng.cols());
            Mat[] stufen = new Mat[groessen.length];
            for (int i = 0; i < groessen.length; i++) {
                if (groessen[i] == 1.0) {
                    stufen[i] = eng;
                    continue;
                }
                Mat s = new Mat();
                Imgproc.resize(eng, s, new Size(Math.max(4, Math.round(eng.cols() * groessen[i])),
                        Math.max(4, Math.round(eng.rows() * groessen[i]))), 0, 0, Imgproc.INTER_LINEAR);
                stufen[i] = s;
            }
            return stufen;
        } finally {
            k.weich.release();
        }
    }

    // ------------------------------------------------------------------
    //  Vergleich
    // ------------------------------------------------------------------

    private static Treffer vergleichen(Karte schild, Vorlage v, int x0, int y0) {
        double bester = -2;
        int bx = 0, by = 0, bw = 0, bh = 0;
        Mat erg = new Mat();
        try {
            for (Mat stufe : v.stufen) {
                if (stufe.cols() > schild.breite || stufe.rows() > schild.hoehe) continue;
                Imgproc.matchTemplate(schild.weich, stufe, erg, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult r = Core.minMaxLoc(erg);
                if (Double.isNaN(r.maxVal) || r.maxVal <= bester) continue;
                bester = r.maxVal;
                bx = (int) r.maxLoc.x;
                by = (int) r.maxLoc.y;
                bw = stufe.cols();
                bh = stufe.rows();
            }
        } finally {
            erg.release();
        }
        if (bester < -1) return null;
        // Abdeckung: weisse Punkte in der Zeile des Treffers -- wie viele davon liegen im Treffer (2 Punkte Spiel)?
        int inZeile = 0, drin = 0;
        int links = Math.max(0, bx - 2), rechts = Math.min(schild.breite, bx + bw + 2);
        for (int y = by; y < by + bh; y++) {
            for (int x = 0; x < schild.breite; x++) {
                if (schild.weiss[y * schild.breite + x] == 0) continue;
                inZeile++;
                if (x >= links && x < rechts) drin++;
            }
        }
        double abdeckung = inZeile == 0 ? 0 : (double) drin / inZeile;
        return new Treffer(v.datei, v.liste, v.name, Math.min(1.0, bester), abdeckung,
                new Rectangle(x0 + bx, y0 + by, bw, bh));
    }
}
