package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wie die Einheiten eines Arena-Gegners aussehen (Autor 22.10.): "Spieler wie MasterSyncro jedes Mal neu durchschauen,
 * da er immer unterschiedliche Einheiten / Teams benutzt -- jede Einheit ganz schnell durchklicken und schauen, ob die
 * Einheiten in der neuen Arena-Runde genauso aussehen wie in den vorherigen. Bei einer kleinen Abweichung den Spieler
 * neu aufnehmen, ausser er ist 'sicher'; wenn nur die Bauarbeiter (unter 250k Leben) anders aussehen, nicht."
 *
 * Verglichen werden die AUSRUESTUNGSFELDER im Einheitenfenster (links zwei grosse, rechts zwei grosse, unten die vier
 * kleinen, samt Steinen) -- nicht die Figur in der Mitte, die bewegt sich. Gemessen an Autors Kasernen-Bildern vom
 * 16.-19.10.: dieselbe Einheit 0,99-1,00, eine andere 0,27-0,65, dieselbe Einheit mit kleiner Aenderung unten 0,90.
 * Ab 0,95 gilt eine Einheit als gleich.
 *
 * Abgelegt je Gegner in arena-aussehen/<Name>/ (je Einheit drei kleine Bilder und eine Zeile in info.txt).
 */
final class ArenaAussehen {

    private ArenaAussehen() { }

    /** Die drei Ausruestungsfelder {x, y, Breite, Hoehe}, gemessen bei 1159 x 770. */
    static final int[][] FELDER = {{243, 236, 105, 246}, {518, 236, 108, 246}, {240, 505, 388, 103}};
    static final int BREITE = 1159, HOEHE = 770;
    /**
     * Ab dieser Aehnlichkeit (in jedem der drei Felder) ist es dieselbe Einheit mit derselben Ausruestung. Arena 4
     * (22.10.): bei Litos' "Paisley" glitzert ein Gegenstand rechts -- dieselbe Einheit nur 0,941/0,942, der Bot sah
     * sie jede Runde neu an. Eine kleine echte Aenderung (Kasernen-Bild) liegt bei 0,90, eine andere Einheit unter 0,71.
     */
    static final double GLEICH_AB = 0.92;
    /** Spielraum beim Vergleich: die Vorlage wird um so viele Bildpunkte je Seite verkleinert. */
    private static final int RAND = 4;
    static final String ORDNER = "arena-aussehen";
    /**
     * Autor 01.11.: "in der Arena ziemlich wenig Speicherplatz". Beim Ablegen fallen die untersten 3 Bit jeder Farbe weg
     * (hoechstens 4 von 255 daneben, gerundet), das spart gegenueber 2 Bit noch einmal 18 % (je Gegner 787 -> 645 KB). Erst waren
     * es 2 Bit, die Messung dazu steht im Commit 163dd8ab. Gemessen an 694 echten Paaren aus arena-aussehen (dieselbe Ausruestung,
     * zu verschiedenen Zeiten aufgenommen): der Vergleichswert aendert sich bei 3 Bit um hoechstens 0,0039 (bei 2 Bit 0,001),
     * von den 24 Paaren nahe der Schwelle 0,92 wechselt keins die Seite. Talent und Guardian: hoechstens 0,003 daneben, ebenfalls
     * kein Wechsel. Kraft- und Lebenszahl: hoechstens 0,0023 daneben; was die Seite wechselt (2 von 6000 bei der Kraft, 24 bei den
     * Leben, mit 2 Bit waren es 0 und 31), sind verschiedene Zahlen, die vorher knapp als "gleich" galten und nun gelesen werden --
     * nie umgekehrt, und dieselbe Zahl bleibt bei 0,99 und mehr.
     */
    static final int FARB_BITS = 3;
    /**
     * Autor 01.11.: "Am besten soll gar nichts wachsen." Aussehen von Gegnern, die 30 Tage nicht mehr da waren, raeumt die
     * Fotobremse beim Start weg. "Zuletzt gesehen" ist der Zeitstempel von info.txt: {@link #speichern} schreibt sie neu, und
     * jedes Nachschlagen ({@link #bekannt}, {@link #laden}, {@link #kraftWerte(String)}) fasst sie an -- ohne ein Bild neu zu
     * schreiben, und je Gegner hoechstens alle 6 Stunden (die Abfragen kommen bei jedem Bild).
     */
    static final long GESEHEN_ALLE_MS = 6 * 3600_000L;
    /** Wann info.txt zuletzt angefasst wurde (im Speicher, damit nicht jede Abfrage die Platte beschreibt). */
    private static final Map<Path, Long> gesehenUm = new ConcurrentHashMap<>();

    /**
     * Autor 22.10.: "Der Bot soll sich die einzelnen Einheiten pro Spieler merken, jeden Skin der einzelnen Einheit -- in
     * den folgenden Arenen den gemerkten Namen plus Skin mit dem neuen vergleichen, ausserdem Talent, Guardian und
     * Power. Ab 3 % Kraftunterschied, anderes Talent oder anderer Guardian, oder 5 % Abweichung am Skin." Die Felder,
     * gemessen an Autors Kasernen-Bild (1159 x 770): Name ueber der Figur, Talent ("Emblem") und Guardian links, die
     * Kraftzahl oben links, die Figur in der Mitte.
     */
    // Autor 22.10. (Bild mit den Feldern): "Wie erkennst du einen Bauarbeiter? Da steht ja nicht Bauarbeiter als Name.
    // Dazu musst du die Leben noch hinzufuegen." -- die Lebenszahl neben dem Herz ist das sechste Merkmal.
    static final int[][] MERKMALE = {{300, 118, 300, 38}, {70, 250, 95, 90}, {68, 385, 100, 90}, {30, 150, 180, 48}, {350, 222, 168, 270},
            {372, 156, 188, 28}};
    static final int M_NAME = 0, M_TALENT = 1, M_GUARDIAN = 2, M_KRAFT = 3, M_SKIN = 4, M_LEBEN = 5;
    static final String[] MERKMAL_NAMEN = {"Name", "Talent", "Guardian", "Kraft", "Skin", "Leben"};
    /** Name, Talent, Guardian, Kraftzahl: ab dieser Bildaehnlichkeit gleich (es sind ruhige Bilder). */
    static final double MERKMAL_GLEICH_AB = 0.90;
    /**
     * Skin: Autor wollte 5 % Abweichung. Gemessen in Arena 4 (22.10.): dieselbe Figur bewegt sich bis 8 % (Paisley 0,922
     * und 0,927), eine andere Einheit liegt bei 0,28-0,56. Darum 10 %, und vor einem Alarm ein zweiter Blick.
     */
    static final double SKIN_GLEICH_AB = 0.90;
    /** Auf diese Groesse verkleinert {@link #skinAehnlichkeit} die Figur fuer den Vergleich -- groesser muss sie nicht abgelegt sein. */
    static final int SKIN_BREITE = 42, SKIN_HOEHE = 68;
    /** Kraft: ab 3 % Unterschied ist es eine andere Einheit oder neu ausgeruestet (Autor). */
    static final double KRAFT_ABWEICHUNG = 0.03;

    /**
     * Eine Einheit: ihre drei Ausruestungsfelder (je in Messgroesse), ob sie ein Bauarbeiter ist, dazu (seit 22.10.)
     * Name, Talent, Guardian, Kraftzahl, Figur und Lebenszahl als Bild ({@link #MERKMALE}, leer bei alten Ablagen),
     * die gelesene Kraft und die vollen Leben (-1 = nicht gelesen).
     */
    record Einheit(List<Mat> felder, boolean bauarbeiter, List<Mat> merkmale, long kraft, long leben) {
        Einheit(List<Mat> felder, boolean bauarbeiter) {
            this(felder, bauarbeiter, List.of(), -1, -1);
        }

        void freigeben() {
            for (Mat m : felder) m.release();
            for (Mat m : merkmale) m.release();
        }
    }

    /** Die drei Felder aus einem Bild des Einheitenfensters, auf Messgroesse gebracht (eigene Kopien). */
    static List<Mat> ausschneiden(Mat bild) {
        return ausschneiden(bild, FELDER);
    }

    /** Name, Talent, Guardian, Kraftzahl und Figur aus einem Bild des Einheitenfensters (eigene Kopien). */
    static List<Mat> merkmale(Mat bild) {
        return ausschneiden(bild, MERKMALE);
    }

    private static List<Mat> ausschneiden(Mat bild, int[][] felder) {
        List<Mat> f = new ArrayList<>();
        if (bild == null || bild.empty()) return f;
        double sx = bild.cols() / (double) BREITE, sy = bild.rows() / (double) HOEHE;
        for (int[] r : felder) {
            int x = (int) Math.round(r[0] * sx), y = (int) Math.round(r[1] * sy);
            int w = Math.min((int) Math.round(r[2] * sx), bild.cols() - x), h = Math.min((int) Math.round(r[3] * sy), bild.rows() - y);
            if (w <= 2 * RAND || h <= 2 * RAND) { freigeben(f); return new ArrayList<>(); }
            Mat teil = new Mat(bild, new Rect(x, y, w, h));
            Mat m = new Mat();
            Imgproc.resize(teil, m, new Size(r[2], r[3]));
            teil.release();
            f.add(m);
        }
        return f;
    }

    /** Aehnlichkeit zweier Einheiten: das schwaechste der drei Felder (0..1). */
    static double aehnlichkeit(List<Mat> alt, List<Mat> neu) {
        if (alt.size() != FELDER.length || neu.size() != FELDER.length) return 0;
        double min = 1;
        for (int i = 0; i < FELDER.length; i++) {
            Mat a = alt.get(i), b = neu.get(i);
            if (a.cols() <= 2 * RAND || a.rows() <= 2 * RAND || a.type() != b.type()) return 0;
            Mat vorlage = new Mat(a, new Rect(RAND, RAND, a.cols() - 2 * RAND, a.rows() - 2 * RAND));
            Mat erg = new Mat();
            try {
                Imgproc.matchTemplate(b, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
                min = Math.min(min, Core.minMaxLoc(erg).maxVal);
            } finally {
                erg.release();
                vorlage.release();
            }
        }
        return min;
    }

    /** Aehnlichkeit zweier gleich grosser Bilder (Vorlage um RAND verkleinert, 0..1). */
    private static double bildAehnlichkeit(Mat a, Mat b) {
        if (a == null || b == null || a.cols() <= 2 * RAND || a.rows() <= 2 * RAND || a.type() != b.type()) return 0;
        Mat vorlage = new Mat(a, new Rect(RAND, RAND, a.cols() - 2 * RAND, a.rows() - 2 * RAND));
        Mat erg = new Mat();
        try {
            Imgproc.matchTemplate(b, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(erg).maxVal;
        } finally {
            erg.release();
            vorlage.release();
        }
    }

    /**
     * Skin: die Figur verkleinert (42 x 68) und leicht weichgezeichnet verglichen, 0..1. Gemessen an Autors Kasernen-
     * Bildern: dieselbe Einheit zu anderen Zeiten 0,987 und 0,996 (die Figur bewegt sich, Magie-Effekte flackern),
     * eine andere Einheit 0,281. Die Farbverteilung trennte schlechter (dieselbe Einheit nur 0,46).
     */
    static double skinAehnlichkeit(Mat a, Mat b) {
        if (a == null || b == null || a.empty() || b.empty()) return 0;
        Mat ka = new Mat(), kb = new Mat(), erg = new Mat();
        try {
            Imgproc.resize(a, ka, new Size(SKIN_BREITE, SKIN_HOEHE), 0, 0, Imgproc.INTER_AREA);
            Imgproc.resize(b, kb, new Size(SKIN_BREITE, SKIN_HOEHE), 0, 0, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(ka, ka, new Size(3, 3), 0);
            Imgproc.GaussianBlur(kb, kb, new Size(3, 3), 0);
            Mat vorlage = new Mat(ka, new Rect(2, 2, 38, 64));
            try {
                Imgproc.matchTemplate(kb, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
                return Math.max(0, Core.minMaxLoc(erg).maxVal);
            } finally {
                vorlage.release();
            }
        } finally {
            ka.release();
            kb.release();
            erg.release();
        }
    }

    /**
     * Was an einer wiedererkannten Einheit (gleiche Ausruestung) sonst anders ist: "Name", "Talent", "Guardian" oder
     * "Skin". Kraft und Leben stehen als Zahl da -- ob sie um 3 % anders sind, entscheidet der Aufrufer mit der
     * gelesenen Zahl ({@link #zahlBildGleich}). null = alles gleich oder nichts zu vergleichen (alte Ablage).
     */
    static String abweichung(Einheit alt, List<Mat> neu) {
        if (!vergleichbar(alt, neu)) return null;
        // Autor 22.10.: "Wenn ein Name einer Einheit nicht richtig abgelesen werden kann oder Einheiten gleich heissen, ist
        // das egal -- er kann sie am Skin, Guardian, Talent, der Kraft und den Leben erkennen." Der Name zaehlt nicht mit.
        for (int i : new int[]{M_TALENT, M_GUARDIAN}) {
            if (bildAehnlichkeit(alt.merkmale().get(i), neu.get(i)) < MERKMAL_GLEICH_AB) return MERKMAL_NAMEN[i];
        }
        if (skinAehnlichkeit(alt.merkmale().get(M_SKIN), neu.get(M_SKIN)) < SKIN_GLEICH_AB) return MERKMAL_NAMEN[M_SKIN];
        return null;
    }

    static boolean vergleichbar(Einheit alt, List<Mat> neu) {
        return alt != null && alt.merkmale().size() == MERKMALE.length && neu != null && neu.size() == MERKMALE.length;
    }

    /** Sieht die Zahl (M_KRAFT oder M_LEBEN) genauso aus wie beim letzten Mal? Dann muss sie nicht gelesen werden. */
    static boolean zahlBildGleich(Einheit alt, List<Mat> neu, int merkmal) {
        return !vergleichbar(alt, neu) || bildAehnlichkeit(alt.merkmale().get(merkmal), neu.get(merkmal)) >= GLEICH_AB;
    }

    /**
     * Dieselbe Einheit nach den ruhigen Merkmalen -- Talent, Guardian, Kraftzahl, Lebenszahl (ohne Skin: der bewegt sich,
     * ohne Name: gleich heissende Einheiten). Autor 22.10.: seine zwei "ersatz" sehen fast gleich aus, haben aber eine
     * andere Kraft, andere Leben und ein anderes Talent. true, wenn nichts zu vergleichen ist.
     */
    static boolean gleicheMerkmale(List<Mat> a, List<Mat> b) {
        if (a == null || b == null || a.size() != MERKMALE.length || b.size() != MERKMALE.length) return true;
        return bildAehnlichkeit(a.get(M_TALENT), b.get(M_TALENT)) >= MERKMAL_GLEICH_AB
                && bildAehnlichkeit(a.get(M_GUARDIAN), b.get(M_GUARDIAN)) >= MERKMAL_GLEICH_AB
                && bildAehnlichkeit(a.get(M_KRAFT), b.get(M_KRAFT)) >= GLEICH_AB
                && bildAehnlichkeit(a.get(M_LEBEN), b.get(M_LEBEN)) >= GLEICH_AB;
    }

    /** Ist die Kraft (oder sind die Leben) um mindestens 3 % anders? Unbekannt (-1) zaehlt nicht als anders. */
    static boolean kraftAnders(long alt, long neu) {
        if (alt <= 0 || neu <= 0) return false;
        return Math.abs(neu - alt) >= alt * KRAFT_ABWEICHUNG;
    }

    /** Welche der bekannten Einheiten sieht genauso aus? -1 = keine. */
    static int passend(List<Einheit> bekannt, List<Mat> neu) {
        if (bekannt == null) return -1;
        int beste = -1;
        double besterWert = GLEICH_AB;
        for (int i = 0; i < bekannt.size(); i++) {
            double w = aehnlichkeit(bekannt.get(i).felder(), neu);
            if (w >= besterWert) {
                besterWert = w;
                beste = i;
            }
        }
        return beste;
    }

    /**
     * Wie {@link #passend(List, List)}, aber zuerst die Einheit an der erwarteten Stelle: Zwei gleich ausgeruestete
     * Einheiten (Zwillinge) sollen nicht vertauscht werden und so eine Umstellung vortaeuschen.
     */
    static int passend(List<Einheit> bekannt, List<Mat> neu, int bevorzugt) {
        if (bekannt != null && bevorzugt >= 0 && bevorzugt < bekannt.size()
                && aehnlichkeit(bekannt.get(bevorzugt).felder(), neu) >= GLEICH_AB) return bevorzugt;
        return passend(bekannt, neu);
    }

    /**
     * Stehen die Einheiten noch in derselben Reihenfolge (Autor 22.10.: "kannst du dir auch merken, auf welcher Position
     * die einzelnen Einheiten standen -- bei MasterSyncro sind manchmal seine Einheiten auf anderen Positionen, dann ist
     * es meistens ein anderes Team")? "Weiter" im Einheitenfenster geht die Aufstellung der Reihe nach durch; begonnen
     * wird nicht immer bei derselben Einheit. Gleich ist die Reihenfolge, wenn die Nummern der wiedererkannten
     * Einheiten (in der Reihenfolge des Durchklickens) hoechstens einmal kleiner werden -- reihum gezaehlt, also auch
     * vom letzten zum ersten. Bauarbeiter gehoeren nicht in die Folge.
     */
    static boolean reihenfolgeGleich(List<Integer> folge) {
        return abstiege(folge, true) <= 1;
    }

    /** Wie oft die Folge kleiner wird; reihum = auch vom letzten zum ersten Glied. */
    static int abstiege(List<Integer> folge, boolean reihum) {
        if (folge == null || folge.size() < 2) return 0;
        int n = 0;
        for (int i = 1; i < folge.size(); i++) if (folge.get(i) < folge.get(i - 1)) n++;
        if (reihum && folge.get(0) < folge.get(folge.size() - 1)) n++;
        return n;
    }

    /** Wie viele der bekannten Einheiten keine Bauarbeiter sind. */
    static int ohneBauarbeiter(List<Einheit> bekannt) {
        int n = 0;
        if (bekannt != null) for (Einheit e : bekannt) if (!e.bauarbeiter()) n++;
        return n;
    }

    // ------------------------------------------------------------------ ablegen / laden

    /** Ordner eines Gegners: nur Buchstaben und Ziffern, dazu ein kurzer Pruefwert (verschiedene Namen, gleicher Ordner?). */
    static Path ordner(String schluessel) {
        return ordner(Ordner.bei(ORDNER), schluessel);
    }

    /** Wie oben, in einer anderen Ablage (die Tests legen sie in einen Wegwerf-Ordner). */
    static Path ordner(Path ablage, String schluessel) {
        String s = schluessel == null ? "" : schluessel.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        if (s.length() > 40) s = s.substring(0, 40);
        return ablage.resolve(s + "-" + Integer.toHexString((schluessel == null ? "" : schluessel).hashCode()));
    }

    /** Liegt von diesem Gegner ein Aussehen vor? Wer gefragt wird, ist gerade da: das zaehlt als gesehen ({@link #gesehen}). */
    static boolean bekannt(String schluessel) {
        return bekannt(Ordner.bei(ORDNER), schluessel);
    }

    static boolean bekannt(Path ablage, String schluessel) {
        if (schluessel == null || schluessel.isEmpty()) return false;
        Path info = ordner(ablage, schluessel).resolve("info.txt");
        if (!Files.exists(info)) return false;
        gesehen(info, System.currentTimeMillis());
        return true;
    }

    /**
     * Merkt, dass dieser Gegner jetzt da ist: der Zeitstempel von info.txt wird auf jetzt gesetzt (Autor 01.11., siehe
     * {@link #GESEHEN_ALLE_MS}). Die Bilder bleiben, wie sie sind -- ein Gegner kann monatelang gleich aussehen und trotzdem jede
     * Woche kommen. Gibt true zurueck, wenn die Datei angefasst wurde; ein Fehler (Datei weg, gesperrt) bricht nie etwas ab.
     */
    static boolean gesehen(Path info, long jetzt) {
        Long zuletzt = gesehenUm.get(info);
        if (zuletzt != null && jetzt - zuletzt < GESEHEN_ALLE_MS) return false;
        gesehenUm.put(info, jetzt);   // auch bei einem Fehler: sonst versucht es jeder Aufruf neu
        try {
            Files.setLastModifiedTime(info, FileTime.fromMillis(jetzt));
            return true;
        } catch (Exception e) {
            Log.debug("Arena", "Aussehen: 'gesehen' nicht vermerkt (" + info + "): " + e.getMessage());
            return false;
        }
    }

    /** Das Aussehen nach einer vollstaendigen Pruefung ablegen (ersetzt das alte). Gibt die Bilder NICHT frei. */
    static void speichern(String schluessel, List<Einheit> einheiten) {
        speichern(schluessel, einheiten, List.of(), "");
    }

    /**
     * Wie oben, dazu die Bewertung des Helden in held.txt (Autor 22.10.: "plus den Helden"). Ohne neue Held-Bilder bleibt
     * die zuletzt gemerkte stehen. 01.11.: die Bilder held-0..4.png las nie jemand (dieselben liegen in logs\arena-helden).
     */
    static void speichern(String schluessel, List<Einheit> einheiten, List<java.awt.image.BufferedImage> held, String heldText) {
        speichern(Ordner.bei(ORDNER), schluessel, einheiten, held, heldText);
    }

    static void speichern(Path ablage, String schluessel, List<Einheit> einheiten, List<java.awt.image.BufferedImage> held, String heldText) {
        if (schluessel == null || schluessel.isEmpty() || einheiten == null || einheiten.isEmpty()) return;
        try {
            Path o = ordner(ablage, schluessel);
            Files.createDirectories(o);
            boolean neuerHeld = held != null && !held.isEmpty();
            try (var alt = Files.list(o)) {
                for (Path p : (Iterable<Path>) alt::iterator) {
                    if (!neuerHeld && p.getFileName().toString().equals("held.txt")) continue;
                    Files.deleteIfExists(p);
                }
            }
            if (neuerHeld) {
                Files.writeString(o.resolve("held.txt"), heldText == null ? "" : heldText, StandardCharsets.UTF_8);
            }
            StringBuilder info = new StringBuilder("# " + schluessel + "\n");
            for (int i = 0; i < einheiten.size(); i++) {
                Einheit e = einheiten.get(i);
                for (int f = 0; f < e.felder().size(); f++) bildSchreiben(o.resolve("e" + (i + 1) + "-" + f + ".png"), e.felder().get(f));
                for (int m = 0; m < e.merkmale().size(); m++) {
                    Path ziel = o.resolve("e" + (i + 1) + "-m" + m + ".png");
                    if (m == M_SKIN) figurSchreiben(ziel, e.merkmale().get(m));
                    else bildSchreiben(ziel, e.merkmale().get(m));
                }
                info.append(i + 1).append('\t').append(e.bauarbeiter() ? "bau" : "-").append('\t').append(e.kraft())
                    .append('\t').append(e.leben()).append('\n');
            }
            // info.txt kommt zuletzt und neu: der Zeitstempel ist damit zugleich "zuletzt gesehen" (siehe GESEHEN_ALLE_MS).
            Files.writeString(o.resolve("info.txt"), info.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Log.debug("Arena", "Aussehen von " + schluessel + " nicht abgelegt: " + ex.getMessage());
        }
    }

    /** Ueber Java schreiben, nicht imwrite: OpenCV scheitert unter Windows an Pfaden mit Umlauten. */
    private static void bildSchreiben(Path ziel, Mat bild) throws java.io.IOException {
        Mat gerundet = farbenGerundet(bild);
        try {
            Files.write(ziel, sparsamesPng(gerundet));
        } finally {
            gerundet.release();
        }
    }

    private static void figurSchreiben(Path ziel, Mat figur) throws java.io.IOException {
        Mat klein = figurFuerAblage(figur);
        try {
            Files.write(ziel, sparsamesPng(klein));
        } finally {
            klein.release();
        }
    }

    /**
     * Die Figur in Vergleichsgroesse (01.11.): {@link #skinAehnlichkeit} rechnet sie ohnehin so herunter, das Ergebnis bleibt
     * auf den Punkt gleich (gemessen an 2160 Paaren: Abweichung 0). Aus rund 71 KB werden rund 6 KB.
     */
    static Mat figurFuerAblage(Mat figur) {
        Mat klein = new Mat();
        Imgproc.resize(figur, klein, new Size(SKIN_BREITE, SKIN_HOEHE), 0, 0, Imgproc.INTER_AREA);
        return klein;
    }

    /** Eine Kopie ohne die untersten {@link #FARB_BITS} Bit jeder Farbe, gerundet statt abgeschnitten (das Bild wird nicht dunkler). */
    static Mat farbenGerundet(Mat bild) {
        Mat aus = bild.clone();
        if (aus.depth() != org.opencv.core.CvType.CV_8U) return aus;
        byte[] d = new byte[(int) (aus.total() * aus.channels())];
        aus.get(0, 0, d);
        int halb = 1 << (FARB_BITS - 1);
        for (int i = 0; i < d.length; i++) d[i] = (byte) Math.min(255, (((d[i] & 255) + halb) >> FARB_BITS) << FARB_BITS);
        aus.put(0, 0, d);
        return aus;
    }

    /** PNG mit starker Kompression und schneller Lauflaengen-Art: gemessen 12 % kleiner als der Standard, unter 1 ms je Bild. */
    static byte[] sparsamesPng(Mat bild) {
        org.opencv.core.MatOfByte png = new org.opencv.core.MatOfByte();
        org.opencv.core.MatOfInt art = new org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 9,
                Imgcodecs.IMWRITE_PNG_STRATEGY, Imgcodecs.IMWRITE_PNG_STRATEGY_RLE);
        try {
            Imgcodecs.imencode(".png", bild, png, art);
            return png.toArray();
        } finally {
            png.release();
            art.release();
        }
    }

    /** Autor 22.10.: besonders gute Guardians +2 (seine Auswahl auf doku/guardians.html), kein Guardian (Schloss) -2 -- je Einheit. */
    static final int GUTER_GUARDIAN = 2, KEIN_GUARDIAN = -2;
    private static List<Mat> guteGuardians, schloesser;
    private static Path guardianWurzel;

    /** +2 (guter Guardian), -2 (Schloss = keiner freigeschaltet) oder 0 fuer den Guardian dieser Einheit. */
    static synchronized int guardianPunkte(List<Mat> merkmale, Path templateRoot) {
        if (merkmale == null || merkmale.size() != MERKMALE.length || templateRoot == null) return 0;
        if (guteGuardians == null || !templateRoot.equals(guardianWurzel)) {
            guardianWurzel = templateRoot;
            guteGuardians = guardianVorlagen(templateRoot, "ar_guardian_gut_");
            schloesser = guardianVorlagen(templateRoot, "ar_guardian_schloss_");
        }
        Mat g = merkmale.get(M_GUARDIAN);
        if (bester(g, schloesser) >= MERKMAL_GLEICH_AB) return KEIN_GUARDIAN;
        if (bester(g, guteGuardians) >= MERKMAL_GLEICH_AB) return GUTER_GUARDIAN;
        return 0;
    }

    /**
     * Autor 22.10.: "Alles unter Talentstufe 50 macht den Gegner leichter" -- -2 je Einheit (Bauarbeiter nicht). Gelesen
     * wird die weisse Zahl unten links im Talent-Ausschnitt: passt sie zur "50" (Vorlage ar_talent_50_1, weisse Maske,
     * gemessen an 415 Talenten: "50" 0,96-1,0, alle anderen hoechstens 0,8), ist es Stufe 50; eine andere Zahl = darunter.
     */
    static final int TALENT_UNTER_50 = -2;
    private static Mat talent50;
    private static Path talentWurzel;

    static synchronized int talentPunkte(List<Mat> merkmale, Path templateRoot) {
        if (merkmale == null || merkmale.size() != MERKMALE.length || templateRoot == null) return 0;
        if (talent50 == null || !templateRoot.equals(talentWurzel)) {
            talentWurzel = templateRoot;
            try {
                Mat t = bildLesen(templateRoot.resolve("gelernt").resolve("arena").resolve("talent").resolve("ar_talent_50_1.png"));
                talent50 = t == null || t.empty() ? new Mat() : weiss(t);
            } catch (Exception e) {
                talent50 = new Mat();
            }
        }
        if (talent50.empty()) return 0;
        Mat t = merkmale.get(M_TALENT);
        if (t == null || t.cols() < 50 || t.rows() < 90) return 0;
        Mat zahl = weiss(new Mat(t, new Rect(0, 66, 50, Math.min(24, t.rows() - 66))));
        Mat erg = new Mat();
        try {
            if (Core.countNonZero(zahl) < 15) return 0;   // keine Zahl zu sehen
            if (zahl.cols() < talent50.cols() || zahl.rows() < talent50.rows()) return 0;
            Imgproc.matchTemplate(zahl, talent50, erg, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(erg).maxVal >= 0.9 ? 0 : TALENT_UNTER_50;
        } finally {
            zahl.release();
            erg.release();
        }
    }

    /** Weisse Pixel (alle Farben ueber 200) als Maske -- die Zahl, egal welche Farbe der Grund hat. */
    private static Mat weiss(Mat bunt) {
        Mat m = new Mat();
        Core.inRange(bunt, new org.opencv.core.Scalar(201, 201, 201), new org.opencv.core.Scalar(255, 255, 255), m);
        return m;
    }

    private static double bester(Mat g, List<Mat> vorlagen) {
        double b = 0;
        for (Mat v : vorlagen) b = Math.max(b, bildAehnlichkeit(v, g));
        return b;
    }

    private static List<Mat> guardianVorlagen(Path wurzel, String anfang) {
        List<Mat> l = new ArrayList<>();
        Path o = wurzel.resolve("gelernt").resolve("arena").resolve("guardian");
        if (!Files.isDirectory(o)) return l;
        try (var dateien = Files.list(o)) {
            for (Path p : (Iterable<Path>) dateien::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith(anfang) || !n.endsWith(".png")) continue;
                Mat m = bildLesen(p);
                if (m != null && !m.empty()) l.add(m);
            }
        } catch (Exception e) {
            Log.debug("Arena", "Guardian-Vorlagen nicht lesbar: " + e.getMessage());
        }
        return l;
    }

    private static Mat bildLesen(Path bild) throws java.io.IOException {
        if (!Files.exists(bild)) return null;
        org.opencv.core.MatOfByte roh = new org.opencv.core.MatOfByte(Files.readAllBytes(bild));
        Mat m = Imgcodecs.imdecode(roh, Imgcodecs.IMREAD_COLOR);
        roh.release();
        if (m.empty()) {
            m.release();
            return null;
        }
        return m;
    }

    /**
     * Aus den Zeilen von info.txt ("Nummer TAB bau|- TAB Kraft TAB Leben"): {staerkste Einheit, Bauarbeiter, Schnitt der Kaempfer ohne Bauarbeiter}.
     * Null, wenn keine Einheit eine lesbare Kraft hat.
     */
    static long[] kraftWerte(List<String> zeilen) {
        long staerkste = -1, summe = 0;
        int bau = 0, kaempfer = 0;
        for (String z : zeilen) {
            if (z.isBlank() || z.startsWith("#")) continue;
            String[] t = z.split("\t");
            if (t.length < 3) continue;
            boolean istBau = "bau".equals(t[1].trim());
            if (istBau) bau++;
            long k;
            try {
                k = Long.parseLong(t[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (k <= 0) continue;
            staerkste = Math.max(staerkste, k);
            if (!istBau) {
                summe += k;
                kaempfer++;
            }
        }
        return staerkste <= 0 ? null : new long[]{staerkste, bau, kaempfer == 0 ? 0 : summe / kaempfer};
    }

    /** Dasselbe fuer diesen Gegner, null ohne abgelegtes Aussehen. */
    static long[] kraftWerte(String schluessel) {
        return kraftWerte(Ordner.bei(ORDNER), schluessel);
    }

    static long[] kraftWerte(Path ablage, String schluessel) {
        try {
            Path info = ordner(ablage, schluessel).resolve("info.txt");
            if (!Files.exists(info)) return null;
            gesehen(info, System.currentTimeMillis());
            return kraftWerte(de.hustlebot.core.Dateien.zeilen(info));
        } catch (Exception e) {
            return null;
        }
    }

    /** Das abgelegte Aussehen, leer, wenn keins da ist. Die Bilder gibt der Aufrufer frei. */
    static List<Einheit> laden(String schluessel) {
        return laden(Ordner.bei(ORDNER), schluessel);
    }

    static List<Einheit> laden(Path ablage, String schluessel) {
        List<Einheit> liste = new ArrayList<>();
        try {
            Path o = ordner(ablage, schluessel);
            Path info = o.resolve("info.txt");
            if (!Files.exists(info)) return liste;
            gesehen(info, System.currentTimeMillis());
            for (String z : de.hustlebot.core.Dateien.zeilen(info)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] t = z.split("\t");
                List<Mat> felder = new ArrayList<>();
                for (int f = 0; f < FELDER.length; f++) {
                    Mat m = bildLesen(o.resolve("e" + t[0].trim() + "-" + f + ".png"));
                    if (m == null) break;
                    felder.add(m);
                }
                List<Mat> merkmale = new ArrayList<>();
                for (int m = 0; m < MERKMALE.length; m++) {
                    Mat b = bildLesen(o.resolve("e" + t[0].trim() + "-m" + m + ".png"));
                    if (b == null) break;
                    merkmale.add(b);
                }
                if (merkmale.size() != MERKMALE.length) {
                    freigeben(merkmale);
                    merkmale = List.of();
                }
                long kraft = -1, leben = -1;
                try {
                    if (t.length > 2) kraft = Long.parseLong(t[2].trim());
                    if (t.length > 3) leben = Long.parseLong(t[3].trim());
                } catch (NumberFormatException ignored) {
                    // alte Ablage ohne Kraft/Leben
                }
                if (felder.size() == FELDER.length) {
                    liste.add(new Einheit(felder, t.length > 1 && t[1].trim().equals("bau"), merkmale, kraft, leben));
                } else {
                    freigeben(felder);
                    freigeben(merkmale);
                }
            }
        } catch (Exception ex) {
            Log.debug("Arena", "Aussehen von " + schluessel + " nicht lesbar: " + ex.getMessage());
        }
        return liste;
    }

    static void freigeben(List<Mat> felder) {
        if (felder != null) for (Mat m : felder) m.release();
    }

    static void freigebenAlle(List<Einheit> einheiten) {
        if (einheiten != null) for (Einheit e : einheiten) e.freigeben();
    }
}
