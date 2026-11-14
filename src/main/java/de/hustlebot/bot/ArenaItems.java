package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gegenstaende im Einheitenfenster nach Autors Bewertung (Autor 25.10., Zweitkonto TR5): Aus der Fotoserie hat er jedes
 * Item bewertet -- unglaublich OP, gut, mittel, neutral, schlecht. Im Einheitenfenster der Arena stehen rechts Waffe und
 * Ruestung, unten Totem, Ring und Amulett. Der Bot vergleicht jeden Platz mit den bewerteten Bildern:
 *
 *   gut +3, mittel +1, neutral -1 (Autor 25.10. abends), schlecht -3 Punkte (der Gegner gilt als staerker, wie bei den
 *   guten Artefakten).
 *   "unglaublich OP" (nur Thronsaal 5): so einen Gegner nie angreifen -- ausser du hast selbst ein OP-Item, dann
 *   nur 30 % weniger Siegchance (Autor 25.10.).
 *
 * Alles liegt im Bot-Ordner unter arena-items\ (also nur beim Zweitkonto-Bot, Daten getrennt vom Hauptkonto):
 *   bewertung.tsv   id TAB platz TAB wert (op | gut | mittel | neutral | schlecht), Bilder in bilder\<id>.png
 *   eigen.tsv       id TAB einheit TAB platz -- deine eigenen Items (Team-Scan / dein Team in der Arena), Bilder in
 *                   eigen\<id>.png oder bilder\<id>.png
 *   op-gegner.tsv   Name TAB Anzahl OP-Items -- was der Bot bei der letzten Pruefung des Gegners gesehen hat
 *
 * Gleiches Item: Fingerabdruck aus 16 x 16 Farbpunkten (Mitte jeder Zelle, 0-1), mittlere Abweichung unter 0,04 -- wie
 * die Bewertungsseite die Items zusammenfasst (dort 0,035), etwas Luft fuer das Bild im laufenden Spiel.
 */
final class ArenaItems {

    enum Wert { OP, GUT, MITTEL, NEUTRAL, SCHLECHT }

    static final String[] PLAETZE = {"waffe", "ruestung", "totem", "ring", "amulett"};
    /** Die Plaetze im Einheitenfenster (1159 x 770), wie auf der Bewertungsseite ausgeschnitten. */
    private static final int[][] RAHMEN = {
            {522, 237, 102, 102}, {522, 367, 102, 102}, {244, 507, 88, 88}, {342, 507, 88, 88}, {440, 507, 88, 88}};
    static final double GLEICH_UNTER = 0.04;
    static final int PUNKTE_GUT = 3, PUNKTE_MITTEL = 1, PUNKTE_NEUTRAL = -1, PUNKTE_SCHLECHT = -3;
    /** Autor 25.10.: mit eigenem OP-Item gegen einen Gegner mit OP-Item "deutlich" schlechter -- minus 30 %. */
    static final double OP_ABZUG = 0.30;
    /** Die OP-Regel gilt nur auf Thronsaal 5 (Autor 25.10.). */
    static final int OP_THRONSAAL = 5;

    /** Ein Item im Fenster: Platz, Fingerabdruck und Ausschnitt (fuer die eigenen Items). */
    record Gesehen(String platz, float[] fp, BufferedImage bild) { }

    /** @param goettlich gelber Rahmen: gut und mittel zaehlen doppelt (Autor 31.10.), vierte Spalte "g" in bewertung.tsv */
    record Bewertet(String id, String platz, float[] fp, Wert wert, boolean goettlich, float[] mitte) {
        /** Ohne Mitten-Abdruck (Tests, aeltere Aufrufe): nur der ganze Fingerabdruck zaehlt. */
        Bewertet(String id, String platz, float[] fp, Wert wert, boolean goettlich) {
            this(id, platz, fp, wert, goettlich, null);
        }
    }

    private static List<Bewertet> bewertet = List.of();
    private static long bewertetStand = Long.MIN_VALUE;
    private static Boolean eigeneOp;
    private static long eigeneOpStand = Long.MIN_VALUE;
    private static long eigeneOpGeprueft;
    private static Map<String, Integer> opGegner;

    private ArenaItems() { }

    static Path ordner() {
        return Ordner.bei("arena-items");
    }

    // --- Fingerabdruck -----------------------------------------------------------------------------------------

    /** 16 x 16 Farbpunkte (RGB, 0-1) aus der Mitte jeder Zelle -- wie ItemsSchneiden der Bewertungsseite. */
    static float[] fingerabdruck(BufferedImage b) {
        float[] v = new float[16 * 16 * 3];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = b.getRGB(x * b.getWidth() / 16 + b.getWidth() / 32, y * b.getHeight() / 16 + b.getHeight() / 32);
                v[i++] = ((p >> 16) & 255) / 255f;
                v[i++] = ((p >> 8) & 255) / 255f;
                v[i++] = (p & 255) / 255f;
            }
        }
        return v;
    }

    static double abstand(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    /**
     * 12 x 12 Farbpunkte (RGB, 0-1) nur aus der Bildmitte: von 25 % bis 85 % der Breite und von 25 % bis 80 % der Hoehe. Autors Bilder tragen links oben
     * das Hemd-Abzeichen und unten die Sechsecke, das kleine Bild im Feld "Basic item" nicht -- die Mitte ist bei beiden dasselbe (Pruefung 01.11.).
     */
    static float[] mitteAbdruck(BufferedImage b) {
        final int n = 12;
        float[] v = new float[n * n * 3];
        int w = b.getWidth(), h = b.getHeight(), i = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = (int) (w * (0.25 + 0.60 * (x + 0.5) / n)), py = (int) (h * (0.25 + 0.55 * (y + 0.5) / n));
                int p = b.getRGB(Math.min(w - 1, px), Math.min(h - 1, py));
                v[i++] = ((p >> 16) & 255) / 255f;
                v[i++] = ((p >> 8) & 255) / 255f;
                v[i++] = (p & 255) / 255f;
            }
        }
        return v;
    }

    /** Ein bewertetes Item aus seinem Bild (beide Fingerabdruecke). */
    static Bewertet bewertetAusBild(String id, String platz, BufferedImage bild, Wert w, boolean goettlich) {
        return new Bewertet(id, platz, fingerabdruck(bild), w, goettlich, mitteAbdruck(bild));
    }

    /** Anteil tuerkiser Punkte -- ein leerer Platz zeigt den tuerkisen Platzhalter. */
    static double tuerkis(BufferedImage b) {
        int n = 0, t = 0;
        for (int y = 0; y < b.getHeight(); y += 2) {
            for (int x = 0; x < b.getWidth(); x += 2) {
                int p = b.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                n++;
                if (r < 140 && g > 160 && bl > 160 && Math.abs(g - bl) < 50) t++;
            }
        }
        return n == 0 ? 0 : (double) t / n;
    }

    /** Kurze, stabile Kennung wie auf der Bewertungsseite: "platz-" + MD5 ueber den groben Fingerabdruck (3 Bit je Wert). */
    static String kennung(String platz, float[] fp) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] grob = new byte[fp.length];
            for (int i = 0; i < fp.length; i++) grob[i] = (byte) Math.min(7, (int) (fp[i] * 8));
            byte[] h = md.digest(grob);
            StringBuilder sb = new StringBuilder(platz).append('-');
            for (int i = 0; i < 5; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return platz + "-" + Integer.toHexString(java.util.Arrays.hashCode(fp));
        }
    }

    // --- Lesen im Einheitenfenster ----------------------------------------------------------------------------

    /**
     * Die belegten Plaetze im Einheitenfenster. Je Platz neun Ausschnitte (bis 2 Punkte verschoben) -- das Fenster sitzt
     * nicht immer pixelgenau; verglichen wird mit dem naechsten. Leere Plaetze (tuerkis) fehlen.
     */
    static List<List<Gesehen>> lesen(Mat screen) {
        List<List<Gesehen>> aus = new ArrayList<>();
        if (screen == null || screen.empty() || screen.channels() != 3) return aus;
        for (int p = 0; p < PLAETZE.length; p++) {
            BufferedImage mitte = ausschnitt(screen, RAHMEN[p], 0, 0);
            if (mitte == null || tuerkis(mitte) > 0.55) continue;   // leerer Platz
            List<Gesehen> varianten = new ArrayList<>();
            varianten.add(new Gesehen(PLAETZE[p], fingerabdruck(mitte), mitte));   // die Mitte immer zuerst
            for (int dy = -2; dy <= 2; dy += 2) {
                for (int dx = -2; dx <= 2; dx += 2) {
                    if (dx == 0 && dy == 0) continue;
                    BufferedImage b = ausschnitt(screen, RAHMEN[p], dx, dy);
                    if (b != null) varianten.add(new Gesehen(PLAETZE[p], fingerabdruck(b), b));
                }
            }
            aus.add(varianten);
        }
        return aus;
    }

    private static BufferedImage ausschnitt(Mat screen, int[] r, int dx, int dy) {
        double fx = screen.cols() / 1159.0, fy = screen.rows() / 770.0;
        int x = (int) Math.round((r[0] + dx) * fx), y = (int) Math.round((r[1] + dy) * fy);
        int w = (int) Math.round(r[2] * fx), h = (int) Math.round(r[3] * fy);
        if (x < 0 || y < 0 || x + w > screen.cols() || y + h > screen.rows() || w < 16 || h < 16) return null;
        Mat teil = screen.submat(new Rect(x, y, w, h));
        try {
            return bild(teil);
        } finally {
            teil.release();
        }
    }

    private static BufferedImage bild(Mat m) {
        Mat c = m.clone();
        try {
            BufferedImage b = new BufferedImage(c.cols(), c.rows(), BufferedImage.TYPE_3BYTE_BGR);
            c.get(0, 0, ((DataBufferByte) b.getRaster().getDataBuffer()).getData());
            return b;
        } finally {
            c.release();
        }
    }

    /** Autors Wert fuer dieses Item, oder null (nicht bewertet / nicht erkannt). */
    static Wert wert(List<Gesehen> varianten) {
        Bewertet b = naechstes(varianten);
        return b == null ? null : b.wert();
    }

    /** Die Punkte fuer dieses Item: Autors Wert, goettlich doppelt, aber nur bei Pluspunkten. Unbekannt: 0. */
    static int punkteFuer(List<Gesehen> varianten) {
        Bewertet b = naechstes(varianten);
        return b == null ? 0 : punkte(b.wert(), b.goettlich());
    }

    static int punkte(Wert w, boolean goettlich) {
        int p = punkte(w);
        return goettlich && p > 0 ? p * 2 : p;
    }

    /** Die id des bewerteten Items, das so aussieht, oder null. */
    static String bewerteteId(List<Gesehen> varianten) {
        Bewertet b = naechstes(varianten);
        return b == null ? null : b.id();
    }

    private static Bewertet naechstes(List<Gesehen> varianten) {
        if (varianten.isEmpty()) return null;
        Bewertet beste = null;
        double besterAbstand = GLEICH_UNTER;
        for (Bewertet b : bewertungen()) {
            if (!b.platz().equals(varianten.get(0).platz())) continue;
            for (Gesehen g : varianten) {
                double a = abstand(g.fp(), b.fp());
                if (a < besterAbstand) {
                    besterAbstand = a;
                    beste = b;
                }
            }
        }
        return beste;
    }

    // --- Basic item -------------------------------------------------------------------------------------------
    //
    // Autor 01.11.: Im Fenster eines Set-Gegenstands steht rechts in der Mitte auf schwarzem Feld "Basic item" mit dem Bild des
    // Items, aus dem das Set gecraftet wurde. Gut +2, mittel +1, schlecht -2 (mehr Punkte = staerkerer Gegner).
    // Nachgemessen an 4 Fotos (Deadly Lightning, Supreme Sovereign): das kleine Bild hat 48-50 Punkte, 32 unter der Ueberschrift.
    // Der beste Treffer lag bei 0,10-0,15, der zweitbeste bei 0,19-0,23; Autors Bilder zeigen dasselbe Item vor anderem Grund,
    // darum gilt hier nicht die Grenze GLEICH_UNTER.
    static final double BASIS_HOECHSTENS = 0.16, BASIS_LUECKE = 0.05;
    /** Zweiter Weg ueber die Bildmitte: Abstand unter 0,16 UND dasselbe Item wie beim ganzen Bild (Pruefung 01.11., 82 Felder: 17 von 24 verpassten gerettet, kein Falschtreffer). */
    static final double MITTE_HOECHSTENS = 0.16;

    /** Zeile der gelben Ueberschrift "Basic item" (im Mass 1159 x 770) auf dem schwarzen Feld, oder -1, wenn kein Feld da ist. */
    static int basisUeberschrift(BufferedImage b) {
        if (b == null) return -1;
        double fx = b.getWidth() / 1159.0, fy = b.getHeight() / 770.0;
        for (int y = 120; y < 330; y++) {
            int gelb = 0;
            for (int x = 900; x < 1010; x++) {
                int p = b.getRGB((int) (x * fx), (int) (y * fy));
                if ((p >> 16 & 255) > 220 && (p >> 8 & 255) > 200 && (p & 255) < 110) gelb++;
            }
            if (gelb < 6) continue;
            int dunkel = 0, n = 0;
            for (int x = 820; x < 890; x += 2) {
                int p = b.getRGB((int) (x * fx), (int) (y * fy));
                n++;
                if ((p >> 16 & 255) < 70 && (p >> 8 & 255) < 70 && (p & 255) < 70) dunkel++;
            }
            return dunkel >= n * 0.6 ? y : -1;
        }
        return -1;
    }

    /** Das bewertete Item, das am besten zum Bild unter "Basic item" passt, oder null: kein Feld, kein klarer Treffer. */
    static Bewertet basisNaechstes(BufferedImage b, String platz, List<Bewertet> liste) {
        int titel = basisUeberschrift(b);
        if (titel < 0) return null;
        double fx = b.getWidth() / 1159.0, fy = b.getHeight() / 770.0;
        Bewertet beste = null, besteMitte = null;
        double besterAbstand = 9, zweiter = 9, besterMitte = 9;
        List<float[]> varianten = new ArrayList<>();
        for (int g = 48; g <= 52; g += 2) {
            for (int dy = 28; dy <= 36; dy += 2) {
                for (int x = 832; x <= 842; x += 2) {
                    int px = (int) (x * fx), py = (int) ((titel + dy) * fy), pw = (int) (g * fx), ph = (int) (g * fy);
                    if (px + pw > b.getWidth() || py + ph > b.getHeight()) continue;
                    varianten.add(fingerabdruck(b.getSubimage(px, py, pw, ph)));
                }
            }
        }
        // Zweiter Weg (Pruefung 01.11.): nur die Bildmitte vergleichen, am festen Ausschnitt (50 Punkte, 836 / 32 unter der Ueberschrift)
        float[] mitteFeld = null;
        int mx = (int) (836 * fx), my = (int) ((titel + 32) * fy), mw = (int) (50 * fx), mh = (int) (50 * fy);
        if (mx + mw <= b.getWidth() && my + mh <= b.getHeight()) mitteFeld = mitteAbdruck(b.getSubimage(mx, my, mw, mh));
        for (Bewertet e : liste) {
            if (!e.platz().equals(platz)) continue;
            double a = 9;
            for (float[] v : varianten) a = Math.min(a, abstand(v, e.fp()));
            if (a < besterAbstand) {
                zweiter = besterAbstand;
                besterAbstand = a;
                beste = e;
            } else if (a < zweiter) {
                zweiter = a;
            }
            if (mitteFeld != null && e.mitte() != null) {
                double m = abstand(mitteFeld, e.mitte());
                if (m < besterMitte) {
                    besterMitte = m;
                    besteMitte = e;
                }
            }
        }
        if (besterAbstand <= BASIS_HOECHSTENS && zweiter - besterAbstand >= BASIS_LUECKE) return beste;
        // Das ganze Bild fand keinen klaren Sieger (Hemd-Abzeichen/Sechsecke verfaelschen den Abstand, oder zwei Eintraege desselben Items vor anderem Grund liegen
        // dicht beieinander): zaehlt trotzdem, wenn auch die Bildmitte genau dieses Item nennt und nahe genug liegt.
        return beste != null && beste == besteMitte && besterMitte < MITTE_HOECHSTENS ? beste : null;
    }

    /**
     * Zeigt das Feld nur den gelben Platzhalter (Foto 165938-891, "Lady's Order Outfit": gelbes Quadrat mit gestricheltem Kreis, das Bild ist noch nicht geladen)?
     * Gemessen an 82 Feldern: Platzhalter 81 % gelb im inneren Ausschnitt, jedes echte Item hoechstens 49 %.
     */
    static boolean basisPlatzhalter(BufferedImage b) {
        int titel = basisUeberschrift(b);
        if (titel < 0) return false;
        double fx = b.getWidth() / 1159.0, fy = b.getHeight() / 770.0;
        int x0 = (int) (844 * fx), y0 = (int) ((titel + 40) * fy), w = (int) (34 * fx), h = (int) (34 * fy);
        if (x0 + w > b.getWidth() || y0 + h > b.getHeight() || w < 4 || h < 4) return false;
        int n = 0, gelb = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int p = b.getRGB(x, y);
                n++;
                if ((p >> 16 & 255) > 215 && (p >> 8 & 255) > 175 && (p & 255) < 90) gelb++;
            }
        }
        return gelb >= n * 0.70;
    }

    // --- Unerkannte Basic items fuer Autors Bewertung (03.11.) --------------------------------------------------

    /** Ablage unter arena-items: je unbekanntes Basic item ein Bild basis-<platz>-<schluessel>.png (das Feld unter der Ueberschrift). */
    static final String NEU = "neu";
    static final int NEU_HOECHSTENS = 600;
    /** Gleich wie ein schon gemerktes Bild: dann nicht noch einmal ablegen (gleiches Item, anderer Hintergrund liegt um 0,10-0,15). */
    static final double NEU_GLEICH_UNTER = 0.10;
    private static List<float[]> neuAbdruecke;

    /** Warum das Basic item nicht erkannt wurde (Probe 03.11. an 550 Beweisfotos), fuer das Protokoll. */
    static String basisGrund(BufferedImage b, String platz, List<Bewertet> liste) {
        int titel = basisUeberschrift(b);
        if (titel < 0) return "kein Basic-item-Feld im Fenster";
        if (basisPlatzhalter(b)) return "Bild noch nicht geladen (gelber Platzhalter)";
        float[] fp = basisAbdruck(b, titel);
        double beste = 9, zweite = 9;
        for (Bewertet e : liste) {
            if (!e.platz().equals(platz) || fp == null) continue;
            double a = abstand(fp, e.fp());
            if (a < beste) {
                zweite = beste;
                beste = a;
            } else if (a < zweite) {
                zweite = a;
            }
        }
        if (beste > BASIS_HOECHSTENS) return String.format(java.util.Locale.ROOT, "nicht in deiner Liste (naechstes %.2f)", beste);
        return String.format(java.util.Locale.ROOT, "zwei bewertete Items zu aehnlich (%.2f / %.2f)", beste, zweite);
    }

    /** Das Bild unter "Basic item" an der festen Stelle (50 Punkte, 836 / 32 unter der Ueberschrift), oder null. */
    private static BufferedImage basisBild(BufferedImage b, int titel) {
        double fx = b.getWidth() / 1159.0, fy = b.getHeight() / 770.0;
        int x = (int) (836 * fx), y = (int) ((titel + 32) * fy), w = (int) (50 * fx), h = (int) (50 * fy);
        if (w < 4 || h < 4 || x + w > b.getWidth() || y + h > b.getHeight()) return null;
        return b.getSubimage(x, y, w, h);
    }

    private static float[] basisAbdruck(BufferedImage b, int titel) {
        BufferedImage feld = basisBild(b, titel);
        return feld == null ? null : fingerabdruck(feld);
    }

    /**
     * Autor 03.11.: "Alle Items, die der Bot nicht zuordnen kann, soll er aufnehmen, damit ich sie spaeter bewerten kann." Legt das Bild
     * unter "Basic item" in arena-items\neu ab, wenn es dort noch kein sehr aehnliches gibt. true = neu abgelegt.
     */
    static synchronized boolean basisMerken(BufferedImage b, String platz) {
        if (b == null) return false;
        int titel = basisUeberschrift(b);
        if (titel < 0 || basisPlatzhalter(b)) return false;
        BufferedImage feld = basisBild(b, titel);
        if (feld == null) return false;
        Path ordner = ordner().resolve(NEU);
        try {
            if (neuAbdruecke == null) {
                neuAbdruecke = new ArrayList<>();
                if (Files.isDirectory(ordner)) {
                    try (var s = Files.list(ordner)) {
                        for (Path p : s.filter(p -> p.getFileName().toString().startsWith("basis-")).toList()) {
                            BufferedImage alt = javax.imageio.ImageIO.read(p.toFile());
                            if (alt != null) neuAbdruecke.add(fingerabdruck(alt));
                        }
                    }
                }
            }
            float[] fp = fingerabdruck(feld);
            for (float[] alt : neuAbdruecke) if (abstand(fp, alt) < NEU_GLEICH_UNTER) return false;
            if (neuAbdruecke.size() >= NEU_HOECHSTENS) return false;
            Files.createDirectories(ordner);
            BufferedImage kopie = new BufferedImage(feld.getWidth(), feld.getHeight(), BufferedImage.TYPE_INT_RGB);
            kopie.getGraphics().drawImage(feld, 0, 0, null);
            java.io.ByteArrayOutputStream puffer = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(kopie, "png", puffer);
            byte[] png = puffer.toByteArray();
            String schluessel = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(png)).substring(0, 10);
            Files.write(ordner.resolve("basis-" + platz + "-" + schluessel + ".png"), png);
            neuAbdruecke.add(fp);
            return true;
        } catch (Exception e) {
            Log.debug("Arena", "Basic item nicht abgelegt: " + e.getMessage());
            return false;
        }
    }

    /** Autors Wert fuer das Item unter "Basic item", oder null. */
    static Wert basisItemWert(BufferedImage b, String platz) {
        Bewertet e = basisNaechstes(b, platz, bewertungen());
        return e == null ? null : e.wert();
    }

    /**
     * Wie {@link #basisItemWert(BufferedImage, String)}, mit Nachlese: Zeigt das Feld nur den gelben Platzhalter (Bild noch nicht geladen, Foto 165938-891), holt
     * {@code neuAufnehmen} EINMAL ein neues Bild (der Bot wartet kurz und fotografiert neu) und es wird noch einmal verglichen -- nur in diesem Fall.
     */
    static Wert basisItemWert(BufferedImage b, String platz, List<Bewertet> liste, java.util.function.Supplier<BufferedImage> neuAufnehmen) {
        Bewertet e = basisNaechstes(b, platz, liste);
        if (e == null && neuAufnehmen != null && basisPlatzhalter(b)) {
            BufferedImage neu = neuAufnehmen.get();
            if (neu != null) e = basisNaechstes(neu, platz, liste);
        }
        return e == null ? null : e.wert();
    }

    /** Autor 01.11.: gut +2, mittel +1, schlecht -2. */
    static int basisPunkte(Wert w) {
        if (w == null) return 0;
        return switch (w) {
            case OP, GUT -> 2;
            case MITTEL -> 1;
            case NEUTRAL -> 0;
            case SCHLECHT -> -2;
        };
    }

    // --- Bewertungen ------------------------------------------------------------------------------------------

    /** Gibt es Bewertungen? Ohne bewertung.tsv macht der Bot alles wie bisher. */
    static boolean aktiv() {
        return !bewertungen().isEmpty();
    }

    static synchronized List<Bewertet> bewertungen() {
        Path datei = ordner().resolve("bewertung.tsv");
        long stand;
        try {
            stand = Files.exists(datei) ? Files.getLastModifiedTime(datei).toMillis() : -1;
        } catch (Exception e) {
            stand = -1;
        }
        if (stand == bewertetStand) return bewertet;
        bewertetStand = stand;
        eigeneOpStand = Long.MIN_VALUE;
        List<Bewertet> neu = new ArrayList<>();
        if (stand >= 0) {
            try {
                for (String z : de.hustlebot.core.Dateien.zeilen(datei)) {
                    String[] t = z.split("\t");
                    if (t.length < 3 || z.startsWith("#")) continue;
                    Wert w = wertAusText(t[2]);
                    BufferedImage bild = bildVon(t[0].trim(), "bilder");
                    if (w == null || bild == null) continue;
                    neu.add(bewertetAusBild(t[0].trim(), t[1].trim(), bild, w, t.length >= 4 && "g".equals(t[3].trim())));
                }
            } catch (Exception e) {
                Log.warn("Arena", Sprache.t("Item ratings not readable: " + e.getMessage(), "Item-Bewertungen nicht lesbar: " + e.getMessage()));
            }
            Log.info("Arena", Sprache.t(neu.size() + " rated items loaded (arena-items).", neu.size() + " bewertete Items geladen (arena-items)."));
        }
        bewertet = List.copyOf(neu);
        return bewertet;
    }

    static Wert wertAusText(String s) {
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "op", "unglaublich op" -> Wert.OP;
            case "gut" -> Wert.GUT;
            case "mittel" -> Wert.MITTEL;
            case "neutral" -> Wert.NEUTRAL;
            case "schlecht" -> Wert.SCHLECHT;
            default -> null;
        };
    }

    /** Punkte fuer ein Item beim Gegner (Autor 25.10.). OP zaehlt wie gut -- die Sperre kommt dazu. */
    static int punkte(Wert w) {
        if (w == null) return 0;
        return switch (w) {
            case OP, GUT -> PUNKTE_GUT;
            case MITTEL -> PUNKTE_MITTEL;
            case SCHLECHT -> PUNKTE_SCHLECHT;
            case NEUTRAL -> PUNKTE_NEUTRAL;   // Autor 25.10.: "fuer neutral auch minus einen Punkt rechnen"
        };
    }

    private static BufferedImage bildVon(String id, String... ordnerNamen) {
        for (String o : ordnerNamen) {
            Path p = ordner().resolve(o).resolve(id + ".png");
            if (!Files.exists(p)) continue;
            try {
                BufferedImage b = ImageIO.read(p.toFile());
                if (b != null) return b;
            } catch (Exception e) {
                Log.debug("Arena", "Item-Bild " + p + " nicht lesbar: " + e.getMessage());
            }
        }
        return null;
    }

    private static float[] fingerabdruckVon(String id, String... ordnerNamen) {
        for (String o : ordnerNamen) {
            Path p = ordner().resolve(o).resolve(id + ".png");
            if (!Files.exists(p)) continue;
            try {
                BufferedImage b = ImageIO.read(p.toFile());
                if (b != null) return fingerabdruck(b);
            } catch (Exception e) {
                Log.debug("Arena", "Item-Bild " + p + " nicht lesbar: " + e.getMessage());
            }
        }
        return null;
    }

    // --- Deine eigenen Items ------------------------------------------------------------------------------------

    /**
     * Deine Items aus dem Team-Scan (Squad Challenge) oder deinem Team in der Arena: ersetzt eigen.tsv und legt die
     * Bilder nach eigen\. Ein schon bewertetes Item bekommt dessen id -- dann gilt Autors Bewertung auch fuer seins.
     *
     * @param jeEinheit je Einheit (Name, die belegten Plaetze)
     */
    static synchronized void eigeneSpeichern(List<Map.Entry<String, List<List<Gesehen>>>> jeEinheit) {
        if (jeEinheit.isEmpty()) return;
        try {
            Path eigen = ordner().resolve("eigen");
            Files.createDirectories(eigen);
            StringBuilder sb = new StringBuilder("# id\teinheit\tplatz -- deine Items (Bot, " + java.time.LocalDateTime.now().withNano(0) + ")\n");
            int n = 0;
            for (Map.Entry<String, List<List<Gesehen>>> e : jeEinheit) {
                for (List<Gesehen> varianten : e.getValue()) {
                    Gesehen mitte = varianten.get(0);
                    String id = bewerteteId(varianten);
                    if (id == null) id = kennung(mitte.platz(), mitte.fp());
                    Path bild = eigen.resolve(id + ".png");
                    if (!Files.exists(bild)) ImageIO.write(mitte.bild(), "png", bild.toFile());
                    sb.append(id).append('\t').append(e.getKey()).append('\t').append(mitte.platz()).append('\n');
                    n++;
                }
            }
            Files.writeString(ordner().resolve("eigen.tsv"), sb.toString(), StandardCharsets.UTF_8);
            eigeneOpStand = Long.MIN_VALUE;
            Log.info("Arena", Sprache.t("Your items saved: " + n + " (arena-items\\eigen.tsv).", "Deine Items gemerkt: " + n + " (arena-items\\eigen.tsv)."));
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Your items not saved: " + e.getMessage(), "Deine Items nicht gemerkt: " + e.getMessage()));
        }
    }

    /** Hast du selbst ein Item, das Autor "unglaublich OP" genannt hat? (eigen.tsv, alle paar Sekunden neu geprueft) */
    static synchronized boolean eigeneOp() {
        long jetzt = System.currentTimeMillis();
        if (eigeneOp != null && jetzt - eigeneOpGeprueft < 5000) return eigeneOp;
        eigeneOpGeprueft = jetzt;
        List<Bewertet> alle = bewertungen();
        Path datei = ordner().resolve("eigen.tsv");
        long stand;
        try {
            stand = Files.exists(datei) ? Files.getLastModifiedTime(datei).toMillis() : -1;
        } catch (Exception e) {
            stand = -1;
        }
        if (eigeneOp != null && stand == eigeneOpStand) return eigeneOp;
        eigeneOpStand = stand;
        boolean op = false;
        if (stand >= 0) {
            try {
                for (String z : de.hustlebot.core.Dateien.zeilen(datei)) {
                    String[] t = z.split("\t");
                    if (t.length < 3 || z.startsWith("#")) continue;
                    String id = t[0].trim(), platz = t[2].trim();
                    if (alle.stream().anyMatch(b -> b.id().equals(id) && b.wert() == Wert.OP)) {
                        op = true;
                        break;
                    }
                    float[] fp = fingerabdruckVon(id, "eigen", "bilder");
                    if (fp != null && alle.stream().anyMatch(b -> b.wert() == Wert.OP && b.platz().equals(platz)
                            && abstand(b.fp(), fp) < GLEICH_UNTER)) {
                        op = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.debug("Arena", "eigen.tsv nicht lesbar: " + e.getMessage());
            }
        }
        eigeneOp = op;
        return op;
    }

    // --- OP-Items je Gegner -------------------------------------------------------------------------------------

    /** Was die letzte Pruefung des Gegners ergab (0 = kein OP-Item -- dann faellt er aus der Liste). */
    static synchronized void opMerken(String schluessel, int anzahl) {
        if (schluessel == null || schluessel.isBlank() || !aktiv()) return;
        Map<String, Integer> m = opGegner();
        Integer vorher = anzahl > 0 ? m.put(schluessel, anzahl) : m.remove(schluessel);
        if (vorher != null && vorher == anzahl) return;
        if (vorher == null && anzahl <= 0) return;
        StringBuilder sb = new StringBuilder("# Name\tOP-Items (letzte Pruefung)\n");
        m.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        try {
            Files.createDirectories(ordner());
            Files.writeString(ordner().resolve("op-gegner.tsv"), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Arena", "op-gegner.tsv nicht geschrieben: " + e.getMessage());
        }
    }

    /** Wie viele OP-Items der Gegner bei seiner letzten Pruefung hatte (0 = keins / nie geprueft). */
    static synchronized int opBei(String schluessel) {
        if (schluessel == null || schluessel.isBlank()) return 0;
        return opGegner().getOrDefault(schluessel, 0);
    }

    private static Map<String, Integer> opGegner() {
        if (opGegner != null) return opGegner;
        Map<String, Integer> m = new LinkedHashMap<>();
        Path datei = ordner().resolve("op-gegner.tsv");
        try {
            if (Files.exists(datei)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(datei)) {
                    String[] t = z.split("\t");
                    if (t.length < 2 || z.startsWith("#")) continue;
                    try {
                        m.put(t[0], Integer.parseInt(t[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // kaputte Zeile auslassen
                    }
                }
            }
        } catch (Exception e) {
            Log.debug("Arena", "op-gegner.tsv nicht lesbar: " + e.getMessage());
        }
        opGegner = m;
        return m;
    }

    /** Nur fuer Tests: alles neu einlesen. */
    static synchronized void vergessen() {
        bewertet = List.of();
        bewertetStand = Long.MIN_VALUE;
        eigeneOp = null;
        eigeneOpStand = Long.MIN_VALUE;
        opGegner = null;
    }

    /** Zaehler je Wert -- fuer die Log-Zeile einer Einheit. */
    static String zusammenfassung(Map<Wert, Integer> z) {
        Map<Wert, Integer> sortiert = new HashMap<>(z);
        StringBuilder sb = new StringBuilder();
        for (Wert w : Wert.values()) {
            Integer n = sortiert.get(w);
            if (n == null || n == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n).append("x ").append(w.name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }
}
