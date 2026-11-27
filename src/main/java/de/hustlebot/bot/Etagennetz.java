package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import de.hustlebot.core.Sprache;

/**
 * Das Wegenetz einer Etage der Kathedrale -- und der kuerzeste Weg darauf.
 *
 * Autor, 27.09.: "Der Bot spielt viel zu viele Stuetzpunkte. Er soll sich
 * wirklich nur auf alle Boss- und Etagenwaechter-Stuetzpunkte fokussieren
 * und pro Etage den schlausten Weg finden."
 *
 * WAS VORHER SCHIEFLIEF (gemessen an 10 Runs, 30 Etagen, und am Video):
 * Eine Etage hat 14 Stuetzpunkte, verbunden durch gepunktete Linien. Am
 * Anfang sind fast alle Linien blass -- graue Kreise mit dunklem Rand, nicht
 * heller als der Boden. Die alte Erkennung suchte helle Stellen und fand so
 * nur 5 bis 6 von 16 Linien; am Ende der Etage (Linien leuchten gelb) alle 16,
 * dazu 3 falsche. Mit einem halben Netz rechnet die Wegsuche Umwege: im
 * Schnitt 10 Stuetzpunkte je Etage, bis zu 14 von 14.
 *
 * WAS JETZT ANDERS IST:
 *   1. Die einzelnen PUNKTE werden gesucht (zwei gemittelte Vorlagen: blass
 *      und hell). Eine Linie gilt, wenn zwischen zwei Sockeln Punkte in
 *      gleichmaessigem Abstand liegen -- gerechnet nur ueber die Strecke,
 *      die nicht hinter einer Figur verschwindet. Gemessen an denselben
 *      Bildern: 13 von 16 am Anfang wie am Ende, keine falsche.
 *   2. DAS SPIEL ZEIGT DIE LINIEN SELBST: Wird nach einem abgeschlossenen
 *      Stuetzpunkt ein anderer frei, sind die beiden verbunden. Bleibt einer
 *      zu, sind sie es nicht. Das ist sicherer als jedes Bild.
 *   3. Das Netz WAECHST ueber die Etage: jedes Bild traegt bei, was es zeigt.
 *   4. Das Netz wird GELERNT: Es gibt nur wenige Karten (mindestens fuenf),
 *      die immer wiederkommen. Am Ende einer Etage wird ihr Netz in
 *      etagen-netze.txt gemerkt und beim naechsten Mal von Anfang an benutzt.
 *   5. Der Weg wird AUSGERECHNET statt geschaetzt: die kleinste Menge
 *      Stuetzpunkte, mit der alle Fragezeichen aufgedeckt, alle Bosse besiegt
 *      und der Waechter erreicht werden. Bei 14 Stuetzpunkten sind das
 *      hoechstens ein paar tausend Moeglichkeiten -- ein Wimpernschlag.
 *
 * Die Regeln des Spiels dahinter: Ein Stuetzpunkt wird frei, sobald ein
 * Nachbar abgeschlossen ist. Ein Fragezeichen wird dabei aufgedeckt -- dahinter
 * stecken die Bosse und der Waechter. Abgeschlossene Stuetzpunkte kosten
 * nichts mehr, man springt frei zwischen allem, was offen ist.
 *
 * Gibt das Netz nichts her (zu wenige Linien, keine Ziele), liefert die
 * Planung nichts, und die Kathedrale spielt wie bisher.
 */
final class Etagennetz {

    // ------------------------------------------------------------------
    //  Die Punkte der Linien
    // ------------------------------------------------------------------

    /** Gemessen an 1159x770 (Autors Fenster); bei anderer Breite werden die Vorlagen mitskaliert. */
    private static final double BREITE_GEMESSEN = 1159.0;
    private static final double SCHWELLE_BLASS = 0.62;
    private static final double SCHWELLE_HELL = 0.70;
    /** So weit darf ein Punkt neben der geraden Strecke liegen (die Linien sind leicht gebogen). */
    private static final double QUER_PX = 16;
    /** Abstand von Punkt zu Punkt, gemessen 17,0. */
    private static final double PUNKT_ABSTAND_PX = 17;
    /** So viel der erwarteten Punkte muss mindestens da sein. */
    private static final double ANTEIL = 0.45;
    /** Groesste erlaubte Luecke auf sichtbarer Strecke, in Punktabstaenden. */
    private static final double LUECKE = 2.0;
    /** Nahe am Sockel wird nicht gesucht: dort sitzt das Symbol. */
    private static final double RAND_PX = 26;
    private static final double MAX_KANTE_PX = 260;
    private static final double MIN_KANTE_PX = 60;
    /** Eine Figur verdeckt ueber ihrem Sockel: so breit (halb) und so hoch. */
    private static final int FIGUR_HALB_PX = 32;
    private static final int FIGUR_HOCH_PX = 95;
    private static final int FIGUR_TIEF_PX = 12;

    private static Mat vorlageBlass;
    private static Mat vorlageHell;
    private static double vorlagenMassstab = -1;
    private static boolean vorlagenFehlen = false;

    private static synchronized boolean vorlagenLaden(int breite) {
        if (vorlagenFehlen) return false;
        double massstab = breite / BREITE_GEMESSEN;
        if (Math.abs(massstab - 1) < 0.03) massstab = 1;
        if (vorlageBlass != null && massstab == vorlagenMassstab) return true;
        try {
            vorlageBlass = lade("/kathedrale/punkt_blass.png", massstab);
            vorlageHell = lade("/kathedrale/punkt_hell.png", massstab);
            vorlagenMassstab = massstab;
            return true;
        } catch (Exception e) {
            vorlagenFehlen = true;
            Log.warn("Kathedrale", Sprache.t("The dot templates for the path network are missing (" + e.getMessage() + "). Path planning stays off, the old path search applies.",
                    "Die Punkt-Vorlagen fuer das Wegenetz fehlen (" + e.getMessage()
                    + "). Die Wegplanung bleibt aus, es gilt die alte Wegsuche."));
            return false;
        }
    }

    private static Mat lade(String name, double massstab) throws Exception {
        try (InputStream in = Etagennetz.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " fehlt im Programm");
            Mat bild = Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_GRAYSCALE);
            if (bild.empty()) throw new IllegalStateException(name + " nicht lesbar");
            if (massstab == 1) return bild;
            Mat skaliert = new Mat();
            Imgproc.resize(bild, skaliert, new Size(), massstab, massstab, Imgproc.INTER_AREA);
            bild.release();
            return skaliert;
        }
    }

    /** Alle Punkte (blass und hell) auf dem Bild, als {x, y} ihrer Mitte. */
    static List<double[]> punkteFinden(Mat screen) {
        List<double[]> punkte = new ArrayList<>();
        if (screen == null || screen.empty() || !vorlagenLaden(screen.cols())) return punkte;
        Mat grau = new Mat();
        try {
            Imgproc.cvtColor(screen, grau, Imgproc.COLOR_BGR2GRAY);
            maxima(grau, vorlageBlass, SCHWELLE_BLASS, punkte);
            maxima(grau, vorlageHell, SCHWELLE_HELL, punkte);
        } catch (Exception e) {
            Log.debug("Kathedrale", "Punkte des Wegenetzes nicht lesbar: " + e.getMessage());
        } finally {
            grau.release();
        }
        return punkte;
    }

    /** Oertliche Hoechstwerte des Vergleichs ueber der Schwelle. */
    private static void maxima(Mat grau, Mat vorlage, double schwelle, List<double[]> ziel) {
        Mat erg = new Mat();
        Mat gedehnt = new Mat();
        Mat kern = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
        try {
            Imgproc.matchTemplate(grau, vorlage, erg, Imgproc.TM_CCOEFF_NORMED);
            Imgproc.dilate(erg, gedehnt, kern);
            float[] e = new float[(int) erg.total()];
            float[] d = new float[(int) gedehnt.total()];
            erg.get(0, 0, e);
            gedehnt.get(0, 0, d);
            int w = erg.cols();
            for (int k = 0; k < e.length; k++) {
                if (e[k] >= schwelle && e[k] == d[k]) {
                    ziel.add(new double[]{k % w + vorlage.cols() / 2.0, k / w + vorlage.rows() / 2.0});
                }
            }
        } finally {
            erg.release();
            gedehnt.release();
            kern.release();
        }
    }

    /** Liegt zwischen den beiden Stuetzpunkten eine gepunktete Linie? */
    private boolean linie(int i, int j, List<double[]> punkte) {
        Knoten a = knoten.get(i), b = knoten.get(j);
        double len = Math.hypot(b.x - a.x, b.y - a.y);
        if (len > MAX_KANTE_PX || len < MIN_KANTE_PX) return false;
        double dx = (b.x - a.x) / len, dy = (b.y - a.y) / len;

        // Liegt ein dritter Stuetzpunkt auf der Strecke, ist es keine eigene
        // Linie, sondern zwei hintereinander.
        for (int k = 0; k < knoten.size(); k++) {
            if (k == i || k == j) continue;
            Knoten c = knoten.get(k);
            double s = (c.x - a.x) * dx + (c.y - a.y) * dy;
            double q = Math.abs(-(c.x - a.x) * dy + (c.y - a.y) * dx);
            if (s > 30 && s < len - 30 && q < 35) return false;
        }

        // Sichtbare Strecke: Figuren stehen ueber ihrem Sockel und verdecken
        // die Punkte dahinter.
        int n = (int) ((len - 2 * RAND_PX) / 3) + 1;
        if (n < 3) return false;
        boolean[] frei = new boolean[n];
        int sichtbar = 0;
        for (int t = 0; t < n; t++) {
            double s = RAND_PX + t * 3, x = a.x + dx * s, y = a.y + dy * s;
            boolean verdeckt = false;
            for (Knoten k : knoten) {
                if (Math.abs(x - k.x) <= FIGUR_HALB_PX && y >= k.y - FIGUR_HOCH_PX && y <= k.y + FIGUR_TIEF_PX) {
                    verdeckt = true;
                    break;
                }
            }
            frei[t] = !verdeckt;
            if (!verdeckt) sichtbar++;
        }
        double sichtLaenge = sichtbar * 3.0;
        if (sichtLaenge < 2.0 * PUNKT_ABSTAND_PX) return false;

        boolean[] belegt = new boolean[n];
        int halb = (int) Math.round(PUNKT_ABSTAND_PX / 3 / 2) + 1;
        int zahl = 0;
        for (double[] p : punkte) {
            double s = (p[0] - a.x) * dx + (p[1] - a.y) * dy;
            double q = Math.abs(-(p[0] - a.x) * dy + (p[1] - a.y) * dx);
            if (s < RAND_PX || s > len - RAND_PX || q > QUER_PX) continue;
            int t = (int) Math.round((s - RAND_PX) / 3);
            if (t < 0 || t >= n) continue;
            zahl++;
            for (int u = Math.max(0, t - halb); u <= Math.min(n - 1, t + halb); u++) belegt[u] = true;
        }
        if (zahl < ANTEIL * sichtLaenge / PUNKT_ABSTAND_PX) return false;

        double luecke = 0, groesste = 0;
        for (int t = 0; t < n; t++) {
            if (!frei[t]) continue;
            if (belegt[t]) {
                luecke = 0;
            } else {
                luecke += 3;
                groesste = Math.max(groesste, luecke);
            }
        }
        return groesste <= LUECKE * PUNKT_ABSTAND_PX;
    }

    // ------------------------------------------------------------------
    //  Der Zustand der Etage
    // ------------------------------------------------------------------

    /** Ein Stuetzpunkt. Die Stelle ist die Mitte seines Sockels. */
    static final class Knoten {
        int x, y;
        /** Stelle stammt von einem Sockel (sonst nur vom Symbol darueber). */
        boolean sockel;
        boolean fertig;
        boolean frage;
        boolean boss;
        boolean waechter;
        /** Anklickbarer Treffer aus dem JETZIGEN Bild, sonst null. */
        Matcher.Hit klick;
        /** Im jetzigen Bild mit Schloss gesehen. */
        boolean gesperrt;
        /** Im jetzigen Bild als Fragezeichen gesehen. */
        boolean frageJetzt;
        /** Im jetzigen Bild offen gesehen (Motiv, Boss oder Waechter ohne Schloss). */
        boolean aufJetzt;
        /** Wann zuletzt als Fragezeichen gesehen. */
        long frageGesehenUm;
        /** Letzter sicher gesehener Stand: zu (Schloss oder Fragezeichen). */
        boolean zuletztZu;
        /** Auf so vielen Bildern dieser Etage als Fragezeichen gesehen. */
        int frageBlicke;
        /**
         * War auf dieser Etage ein Fragezeichen (auf mindestens zwei Bildern
         * -- ein einzelnes kann taeuschen, Test 27.09., 04:43). Solange kein
         * Nachbar fertig ist, bleibt es Ziel, auch wenn es gerade verdeckt ist.
         */
        boolean warFrage;
        /** Seit wann ein Nachbar fertig ist (das Fragezeichen also aufgedeckt); 0 = noch nicht. */
        long aufgedecktUm;
        /** War auf dieser Etage als Boss zu sehen (bleibt auch nach dem Kampf stehen). */
        boolean warBoss;
        /**
         * Seit wann dieser Stuetzpunkt offen sein muesste (Nachbar fertig),
         * der Bot an ihm aber nichts Anklickbares erkennt; 0 = erkannt.
         */
        long ohneKlickSeit;
        /** So oft wurde dieser Stuetzpunkt auf seine bekannte Stelle hin angeklickt, ohne dass ein Motiv erkannt war. */
        int blindKlicks;
        /**
         * GEDAECHTNIS (Bauplan L1, 09.10.): das zuletzt gesehene Motiv dieses
         * Stuetzpunkts -- offen ODER mit Schloss. Bleibt stehen, wenn er auf
         * einem Bild verdeckt ist (Schriftzug "You have got a blessing").
         * Neu nur mit der Etage; nach einem Klick auf ihn verbraucht.
         */
        Matcher.Hit motiv;
        /** Grundname des Motivs (dc_skirmish, dc_cube ...), sonst null. */
        String art;

        Knoten(int x, int y, boolean sockel) {
            this.x = x;
            this.y = y;
            this.sockel = sockel;
        }

        /** Eine Kopie mit allen Feldern -- fuer die Vorausplanung (Bauplan L2). */
        Knoten kopie() {
            Knoten c = new Knoten(x, y, sockel);
            c.fertig = fertig;
            c.frage = frage;
            c.boss = boss;
            c.waechter = waechter;
            c.klick = klick;
            c.gesperrt = gesperrt;
            c.frageJetzt = frageJetzt;
            c.aufJetzt = aufJetzt;
            c.frageGesehenUm = frageGesehenUm;
            c.zuletztZu = zuletztZu;
            c.frageBlicke = frageBlicke;
            c.warFrage = warFrage;
            c.aufgedecktUm = aufgedecktUm;
            c.warBoss = warBoss;
            c.ohneKlickSeit = ohneKlickSeit;
            c.blindKlicks = blindKlicks;
            c.motiv = motiv;
            c.art = art;
            return c;
        }

        /** Motiv merken (Gedaechtnis). */
        void motivMerken(Matcher.Hit m) {
            motiv = m;
            art = grundname(m.name());
        }

        boolean zuJetzt() {
            return gesperrt || frageJetzt;
        }
    }

    /** Grundname einer Vorlage: dc_skirmish_alt -> dc_skirmish (die ersten zwei Teile). */
    static String grundname(String name) {
        if (name == null) return null;
        int a = name.indexOf('_');
        if (a < 0) return name;
        int b = name.indexOf('_', a + 1);
        return b < 0 ? name : name.substring(0, b);
    }

    /** Zwei Sockel naeher als das sind derselbe. */
    private static final double GLEICH_PX = 40;
    /** Ein Symbol sitzt ueber seinem Sockel: hoechstens so weit seitlich ... */
    private static final int SYMBOL_SEITLICH_PX = 45;
    /** ... und hoechstens so weit darueber (bzw. ein Stueck darunter). */
    private static final int SYMBOL_DARUEBER_PX = 110;
    private static final int SYMBOL_DARUNTER_PX = 30;
    /** Ein Ziel ohne erkannte Linie haengt am naechsten Stuetzpunkt, hoechstens so weit weg. */
    private static final double ISOLIERT_MAX_PX = 230;
    /** Eine gesehene Linie gilt erst ab so vielen Bildern. */
    private static final int LERNEN_AB = 2;
    /** Ab so vielen Stuetzpunkten gilt das Bild als ganze Etagenkarte. */
    private static final int KARTE_AB_KNOTEN = 10;
    /** So lange nicht mehr als Fragezeichen gesehen: aufgedeckt. */
    private static final long FRAGE_WEG_MS = 1100;

    private final List<Knoten> knoten = new ArrayList<>();
    /** Linie (kleiner Index * 1000 + grosser) -> wie oft in dieser Etage gesehen. */
    private final Map<Integer, Integer> gesehen = new HashMap<>();
    /** Linien aus dem Gelernten. */
    private final Set<Integer> gelernt = new HashSet<>();
    /**
     * Linien, die das SPIEL gezeigt hat: Nach einem abgeschlossenen
     * Stuetzpunkt wurde ein Nachbar frei. Sicherer geht es nicht.
     */
    private final Set<Integer> bewiesen = new HashSet<>();
    /** Keine Linie -- auch das hat das Spiel gezeigt: Nachbar fertig, Stuetzpunkt weiter zu. */
    private final Set<Integer> widerlegt = new HashSet<>();
    private boolean gelerntesGesucht = false;
    /** Diese Karte war beim ersten Blick noch nicht gelernt. */
    private boolean karteNeu = false;
    /** Die Stuetzpunkte stehen fest; aus Farbflecken kommen keine neuen mehr dazu. */
    private boolean aufgenommen = false;
    private int bilder = 0;
    private int gespielt = 0;
    private int planAmAnfang = -1;
    /** Der zuletzt angeklickte Stuetzpunkt und wer davor zu war -- zum Lernen aus dem Spiel. */
    private int letzter = -1;
    /** Der erste Stuetzpunkt dieser Etage (ihr Anfang), -1 = noch keiner gespielt. */
    private int erster = -1;
    private boolean[] zuVorher = new boolean[0];

    private static int schluessel(int i, int j) {
        return Math.min(i, j) * 1000 + Math.max(i, j);
    }

    // ------------------------------------------------------------------
    //  Die festen Karten (27.09.)
    // ------------------------------------------------------------------
    //
    // Autor, 27.09.: "Wegfindung: nur Bosse + Waechter + die noetigsten
    // Stuetzpunkte." Er sieht den Bot Sackgassen und Umwege spielen.
    //
    // GEMESSEN (Test 03:43, Etage mit 14 gespielten bei Plan 7): Der Plan
    // wurde befolgt, aber das Netz stimmte nicht. Der Planer nahm Linien an,
    // die es nicht gibt (nur "gesehene" und "denkbare"), spielte den
    // Stuetzpunkt, das Fragezeichen ging nicht auf, naechster Versuch -- jeder
    // Irrtum ein Stuetzpunkt. Mit dem wahren Netz waeren es 11 gewesen.
    //
    // Die Kathedrale hat fuenf Karten, die immer wiederkommen. Ihre Linien
    // sind hier fest eingetragen: abgelesen von den Fotos in logs\etagen und
    // verglichen mit den Linien, die das Spiel selbst bewiesen hat (77 von 81
    // waren schon bewiesen, keine bewiesene war falsch). Auf einer festen
    // Karte wird nichts mehr geraten und nichts mehr gelernt. Eine unbekannte
    // Karte laeuft wie bisher ueber das gelernte Netz.
    //
    // Je Linie die Sockel-Mitten ihrer beiden Enden, gemessen an 1159x770.

    private static final String[][] FESTE_KARTEN = {
        {   // Gitter
            "599,154-404,257", "599,154-792,260", "270,257-404,257", "270,257-271,392",
            "404,257-792,260", "404,257-410,529", "792,260-790,521", "792,260-915,258",
            "147,387-271,392", "1045,388-915,389", "271,392-272,521", "790,521-912,520",
            "790,521-410,529", "790,521-598,646", "912,520-915,389", "272,521-410,529",
            "410,529-598,646", "915,389-915,258"
        },
        {   // Schnecke (Anfang links unten)
            "853,167-874,350", "519,198-662,214", "519,198-417,240", "519,198-512,314",
            "662,214-766,366", "417,240-512,314", "417,240-283,349", "512,314-425,447",
            "283,349-200,488", "874,350-809,510", "766,366-711,466", "425,447-559,580",
            "711,466-809,510", "711,466-693,581", "809,510-693,581", "559,580-693,581"
        },
        {   // Stern
            "608,145-610,260", "360,190-610,260", "360,190-319,391", "829,191-610,260",
            "829,191-879,372", "610,260-732,381", "610,260-484,385", "879,372-1023,372",
            "879,372-823,564", "732,381-609,527", "484,385-609,527", "319,391-371,566",
            "319,391-160,381", "609,527-371,566", "609,527-823,564", "609,527-608,642"
        },
        {   // Raute
            "609,136-606,260", "606,260-705,292", "606,260-508,293", "705,292-857,323",
            "705,292-709,510", "508,293-348,328", "508,293-500,510", "857,323-1016,390",
            "348,328-167,395", "1016,390-853,456", "853,456-709,510", "349,462-500,510",
            "349,462-167,395", "500,510-603,530", "709,510-603,530", "603,530-603,649"
        },
        {   // Ring
            "579,154-674,228", "674,228-416,264", "674,228-816,302", "416,264-482,372",
            "416,264-340,423", "816,302-853,471", "816,302-940,221", "716,371-605,515",
            "482,372-605,515", "340,423-185,476", "340,423-420,581", "853,471-772,579",
            "772,579-420,581", "772,579-608,643", "420,581-608,643"
        }
    };
    private static final String[] FESTE_NAMEN = {"Gitter", "Schnecke", "Stern", "Raute", "Ring"};

    /** Die festen Karten, ausgepackt: je Karte die Stuetzpunkte {x, y} und die Linien {a, b}. */
    private static final List<List<int[]>> FEST_ORTE = new ArrayList<>();
    private static final List<List<int[]>> FEST_LINIEN = new ArrayList<>();

    static {
        for (String[] karte : FESTE_KARTEN) {
            List<int[]> orte = new ArrayList<>();
            List<int[]> linien = new ArrayList<>();
            for (String linie : karte) {
                String[] enden = linie.split("-");
                int[] ab = new int[2];
                for (int e = 0; e < 2; e++) {
                    String[] xy = enden[e].split(",");
                    int x = Integer.parseInt(xy[0].trim()), y = Integer.parseInt(xy[1].trim());
                    int i = -1;
                    for (int t = 0; t < orte.size(); t++) {
                        if (orte.get(t)[0] == x && orte.get(t)[1] == y) i = t;
                    }
                    if (i < 0) {
                        orte.add(new int[]{x, y});
                        i = orte.size() - 1;
                    }
                    ab[e] = i;
                }
                linien.add(ab);
            }
            FEST_ORTE.add(orte);
            FEST_LINIEN.add(linien);
        }
    }

    /** Diese Etage laeuft auf einer festen Karte: ihre Nummer, sonst -1. */
    private int fest = -1;
    private boolean[][] festNachbar = new boolean[0][0];
    /** So oft hintereinander hat das Spiel der festen Karte widersprochen. */
    private int festWiderspruch = 0;
    /** Erkennt der Bot an einem offenen Stuetzpunkt so lange nichts, wird um ihn herum geplant. */
    private static final long NICHT_KLICKBAR_MS = 800;
    /** Geht es ohne ihn nicht: nach so langer Zeit ohne erkanntes Motiv die bekannte Stelle anklicken. */
    private static final long OHNE_MOTIV_KLICK_MS = 1500;
    private static final int OHNE_MOTIV_KLICKS_MAX = 2;
    /** Das Motiv sitzt so weit ueber der Mitte seines Sockels (gemessen 25 bis 60). */
    private static final int SYMBOL_UEBER_SOCKEL_PX = 45;
    /** So lange nach dem Aufdecken kann ein Motiv noch hinter dem Schriftzug liegen (der zieht rund 1,4 s). */
    private static final long AUFGEDECKT_WARTEN_MS = 1500;
    /** Ab so vielen Widerspruechen hintereinander gilt die feste Karte fuer diese Etage nicht mehr. */
    private static final int FEST_WIDERSPRUCH_MAX = 3;

    /** Laeuft diese Etage auf einer festen Karte? */
    boolean festeKarte() {
        return fest >= 0;
    }

    /** Wer offen sein muesste, aber nichts Anklickbares zeigt: seit wann? */
    private void ohneKlickMerken(long jetzt) {
        for (int i = 0; i < knoten.size(); i++) {
            Knoten k = knoten.get(i);
            if (k.fertig || k.klick != null || k.aufgedecktUm == 0) {
                k.ohneKlickSeit = 0;
            } else if (k.ohneKlickSeit == 0) {
                k.ohneKlickSeit = jetzt;
            }
        }
    }

    /**
     * OFFEN, ABER NICHT ERKANNT (Test 27.09., 05:07 und 05:08): Auf der Raute
     * passte "Random skirmish" an einer Stelle nur 0,83 bis 0,85 -- knapp
     * unter der Schwelle. Der Bot sah dort nichts, wartete 2 s und spielte
     * dann einen Umweg (12 und 13 Stuetzpunkte statt 11).
     *
     * Auf einer festen Karte ist bekannt, WO ein offener Stuetzpunkt stehen
     * muss (Nachbar fertig). Diese Stellen gibt es hier heraus; die Kathedrale
     * sieht dort gezielt und mit niedrigerer Schwelle nach.
     *
     * @return die Sockel-Mitten {x, y} der Stuetzpunkte, die offen sein
     *         muessten, an denen im jetzigen Bild aber nichts erkannt ist
     */
    List<int[]> offenOhneMotiv() {
        List<int[]> aus = new ArrayList<>();
        if (fest < 0) return aus;
        for (Knoten k : knoten) {
            if (k.fertig || k.klick != null || k.gesperrt || k.frageJetzt || k.aufgedecktUm == 0) continue;
            if (k.waechter) continue;     // den Waechter findet die Kathedrale auf ihre Weise
            aus.add(new int[]{k.x, k.y});
        }
        return aus;
    }

    /**
     * Gedaechtnis-Klick erlaubt (Bauplan L1, Schalter gedaechtnisklick)? Setzt
     * die Kathedrale vor jedem planen(). Aus = das alte Verhalten (warten).
     */
    private boolean gedaechtnisKlick = false;

    void gedaechtnisKlickErlauben(boolean an) {
        gedaechtnisKlick = an;
    }

    /** Probelauf der Vorausplanung: planenFest ohne Protokoll und ohne planAmAnfang (Bauplan L2). */
    private boolean probelauf = false;

    /** Ob der zuletzt berechnete Schritt aus dem Gedaechtnis stammt (gemerkte Stelle statt sichtbares Motiv). */
    private boolean planAusGedaechtnis = false;

    /** Liefert und loescht den Merker (gilt nur fuer den Klick, der gleich auf den Plan folgt). */
    boolean planAusGedaechtnisVerbrauchen() {
        boolean b = planAusGedaechtnis;
        planAusGedaechtnis = false;
        return b;
    }

    /** Traegt gezielt gefundene Motive nach (siehe offenOhneMotiv). */
    void nachtragen(List<Matcher.Hit> motive, List<String> bossNamen) {
        if (fest < 0) return;
        for (Matcher.Hit m : motive) {
            Knoten k = zuSymbol(m);
            if (k == null || k.fertig || k.gesperrt || k.klick != null) continue;
            k.motivMerken(m);
            k.klick = m;
            k.aufJetzt = true;
            k.frage = false;
            k.ohneKlickSeit = 0;
            for (String b : bossNamen) {
                if (m.name().startsWith(b)) {
                    k.boss = true;
                    k.warBoss = true;
                }
            }
        }
    }

    /**
     * Was waere auf dieser Etage das Mindeste gewesen? Gerechnet am Ende, wenn
     * alles bekannt ist: Anfang, Bosse, Waechter. Weniger Stuetzpunkte geht
     * auf dieser Karte nicht -- damit laesst sich ehrlich vergleichen, was der
     * Bot gespielt hat. -1, wenn es sich nicht sagen laesst.
     */
    int bestmoeglich() {
        if (fest < 0 || erster < 0 || erster >= knoten.size()) return -1;
        int n = knoten.size();
        int waechter = -1;
        List<Integer> bosse = new ArrayList<>();
        List<Integer> frei = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Knoten k = knoten.get(i);
            if (k.waechter) waechter = i;
            else if (i == erster) continue;
            else if (k.warBoss) bosse.add(i);
            else frei.add(i);
        }
        if (waechter < 0 || waechter == erster) return -1;
        int beste = -1;
        for (int maske = 0; maske < (1 << frei.size()); maske++) {
            int groesse = Integer.bitCount(maske);
            if (beste >= 0 && groesse >= beste) continue;
            boolean[] drin = new boolean[n];
            drin[erster] = true;
            for (int b : bosse) drin[b] = true;
            for (int b = 0; b < frei.size(); b++) {
                if ((maske & (1 << b)) != 0) drin[frei.get(b)] = true;
            }
            boolean[] erreicht = new boolean[n];
            ArrayDeque<Integer> schlange = new ArrayDeque<>();
            erreicht[erster] = true;
            schlange.add(erster);
            while (!schlange.isEmpty()) {
                int k = schlange.poll();
                for (int j = 0; j < n; j++) {
                    if (festNachbar[k][j] && drin[j] && !erreicht[j]) {
                        erreicht[j] = true;
                        schlange.add(j);
                    }
                }
            }
            boolean geht = true;
            for (int i = 0; i < n && geht; i++) if (drin[i] && !erreicht[i]) geht = false;
            boolean nah = false;
            for (int j = 0; j < n; j++) nah |= festNachbar[waechter][j] && erreicht[j];
            if (geht && nah) beste = groesse;
        }
        return beste < 0 ? -1 : beste + 1 + bosse.size() + 1;
    }

    /**
     * Steht neben etwas Fertigem ein Stuetzpunkt, an dem der Bot im jetzigen
     * Bild NICHTS erkennt -- kein Motiv, kein Schloss, keinen Boss? Dann fehlt
     * wohl eine Vorlage; ein Foto davon hilft beim Nachsehen.
     */
    String unerkannt() {
        if (fest < 0) return null;
        for (int i = 0; i < knoten.size(); i++) {
            Knoten k = knoten.get(i);
            if (k.fertig || k.waechter || k.boss || k.klick != null || k.gesperrt || k.frageJetzt) continue;
            if (k.aufgedecktUm != 0 && System.currentTimeMillis() - k.aufgedecktUm > AUFGEDECKT_WARTEN_MS) {
                return ort(i);
            }
        }
        return null;
    }

    /**
     * Welche feste Karte passt zu den Sockeln des jetzigen Bildes? -1, wenn
     * keine sicher passt. Verlangt werden zehn passende Stuetzpunkte und ein
     * klarer Abstand zur zweitbesten Karte (oben und unten liegen die
     * Stuetzpunkte auf allen Karten fast gleich).
     */
    private int festeKarteSuchen(double massstab) {
        int beste = -1, besteTreffer = 0, zweite = 0;
        for (int f = 0; f < FEST_ORTE.size(); f++) {
            int treffer = 0;
            for (int[] o : FEST_ORTE.get(f)) {
                for (Knoten k : knoten) {
                    if (k.sockel && Math.hypot(o[0] * massstab - k.x, o[1] * massstab - k.y) <= GLEICH_PX) {
                        treffer++;
                        break;
                    }
                }
            }
            if (treffer > besteTreffer) {
                zweite = besteTreffer;
                besteTreffer = treffer;
                beste = f;
            } else if (treffer > zweite) {
                zweite = treffer;
            }
        }
        return besteTreffer >= KARTE_AB_KNOTEN && besteTreffer - zweite >= 3 ? beste : -1;
    }

    /** Die feste Karte uebernehmen: genau ihre Stuetzpunkte, genau ihre Linien. */
    private void festUebernehmen(int f, double massstab) {
        knoten.clear();
        for (int[] o : FEST_ORTE.get(f)) {
            knoten.add(new Knoten((int) Math.round(o[0] * massstab), (int) Math.round(o[1] * massstab), true));
        }
        int n = knoten.size();
        festNachbar = new boolean[n][n];
        for (int[] l : FEST_LINIEN.get(f)) festNachbar[l[0]][l[1]] = festNachbar[l[1]][l[0]] = true;
        fest = f;
        festWiderspruch = 0;
        gesehen.clear();
        gelernt.clear();
        bewiesen.clear();
        widerlegt.clear();
        aufgenommen = true;
        karteNeu = false;
        letzter = -1;
        erster = -1;
        zuVorher = new boolean[0];
        Log.info("Kathedrale", de.hustlebot.core.Sprache.t("Path network: fixed map \"", "Wegenetz: feste Karte \"") + FESTE_NAMEN[f] + "\" (" + n + de.hustlebot.core.Sprache.t(" waypoints, ", " Stuetzpunkte, ")
                + FEST_LINIEN.get(f).size() + de.hustlebot.core.Sprache.t(" lines).", " Linien)."));
    }

    /**
     * Widerspricht das Spiel der festen Karte? Ein Stuetzpunkt mit Schloss,
     * dessen Nachbar fertig ist, duerfte es nicht geben. Kommt das mehrmals
     * hintereinander vor, stimmt die Karte nicht (oder es ist eine sechste,
     * die einer festen aehnelt) -- dann gilt fuer den Rest der Etage wieder
     * das gelernte Netz.
     */
    private void festPruefen(boolean reif) {
        if (fest < 0 || !reif) return;
        String wo = null;
        for (int i = 0; i < knoten.size() && wo == null; i++) {
            if (knoten.get(i).fertig || !knoten.get(i).gesperrt) continue;
            for (int j = 0; j < knoten.size(); j++) {
                if (festNachbar[i][j] && knoten.get(j).fertig) {
                    wo = Sprache.t(ort(i) + " is closed although " + ort(j) + " is done", ort(i) + " ist zu, obwohl " + ort(j) + " fertig ist");
                    break;
                }
            }
        }
        if (wo == null) {
            festWiderspruch = 0;
            return;
        }
        if (++festWiderspruch < FEST_WIDERSPRUCH_MAX) return;
        Log.warn("Kathedrale", Sprache.t("Path network: the game contradicts the fixed map \"" + FESTE_NAMEN[fest] + "\" (" + wo + "). The learned network applies for the rest of the floor.",
                "Wegenetz: Das Spiel widerspricht der festen Karte \"" + FESTE_NAMEN[fest]
                + "\" (" + wo + "). Fuer den Rest der Etage gilt das gelernte Netz."));
        for (int i = 0; i < knoten.size(); i++) {
            for (int j = i + 1; j < knoten.size(); j++) {
                if (festNachbar[i][j]) gelernt.add(schluessel(i, j));
            }
        }
        String welche = FESTE_NAMEN[fest];
        fest = -1;
        gelerntesGesucht = true;
        unbekanntMerken(Sprache.t("the game contradicts the fixed map \"" + welche + "\" (" + wo + ")",
                "das Spiel widerspricht der festen Karte \"" + welche + "\" (" + wo + ")"), massstabZuletzt);
    }

    /** Neue Etage: alles vergessen. Vorher wird gelernt, was die alte gezeigt hat. */
    void neueEtage() {
        lernen();
        fest = -1;
        festWiderspruch = 0;
        knoten.clear();
        gesehen.clear();
        gelernt.clear();
        bewiesen.clear();
        widerlegt.clear();
        gelerntesGesucht = false;
        karteNeu = false;
        aufgenommen = false;
        bilder = 0;
        gespielt = 0;
        planAmAnfang = -1;
        letzter = -1;
        erster = -1;
        unklarSeit = 0;
        zuVorher = new boolean[0];
        unbekannt = null;
    }

    // ------------------------------------------------------------------
    //  Suchkaesten an den Stuetzpunkten der festen Karte (21.11.)
    // ------------------------------------------------------------------
    //
    // Autor 21.11.: "Der Bot erkennt am Anfang, welches Muster die Etage hat, und sucht dann nur noch an den Positionen
    // der Wegpunkte, die sind je Muster immer gleich."
    //
    // GEMESSEN an 232 Etagenbildern (logs\etagen beider Bots, 08.10.-21.11.): Jedes war eine der fuenf festen Karten
    // (Ring 122, Stern 85, Schnecke 16, Gitter 8, Raute 1), ihre Sockel lagen im Mittel je Bild hoechstens 4 Punkte neben
    // den eingetragenen Stellen. Die MITTE jedes Treffers ab 0,84 (Motive, Schloesser, Fragezeichen, Bosse, Waechter,
    // Waechter-Schrift, besuchte) lag 6 links bis 9 rechts und 60 ueber bis 17 unter ihrem Stuetzpunkt. Der Kasten unten
    // laesst rundum gut 20 Punkte Luft. Von den 14 Stuetzpunkten der Karte waren auf jedem Bild 11 bis 14 an ihrer
    // Stelle zu sehen, von jeder anderen Karte hoechstens 7.

    /** So weit links/rechts von seinem Stuetzpunkt darf die Mitte eines Symbols liegen (gemessen hoechstens 9). */
    static final int KASTEN_SEITLICH_PX = 30;
    /** So weit darueber (gemessen hoechstens 60) ... */
    static final int KASTEN_DARUEBER_PX = 90;
    /** ... und so weit darunter (gemessen hoechstens 17, die Waechter-Schrift). */
    static final int KASTEN_DARUNTER_PX = 40;
    /** So viele Stuetzpunkte der festen Karte muessen im jetzigen Bild an ihrer Stelle zu sehen sein. */
    static final int KASTEN_AB_TREFFER = 8;

    /** Je feste Karte: wie viele ihrer Stuetzpunkte haben in diesem Bild einen Sockel an ihrer Stelle? */
    static int[] kartenTreffer(List<Matcher.Hit> sockel, double massstab) {
        List<Matcher.Hit> echte = ohneSymbole(sockel);
        int[] t = new int[FEST_ORTE.size()];
        for (int f = 0; f < FEST_ORTE.size(); f++) {
            for (int[] o : FEST_ORTE.get(f)) {
                for (Matcher.Hit s : echte) {
                    if (Math.hypot(o[0] * massstab - s.centerX(), o[1] * massstab - s.centerY()) <= GLEICH_PX) {
                        t[f]++;
                        break;
                    }
                }
            }
        }
        return t;
    }

    /** Zeigt dieses Bild sicher die feste Karte f (genug Stuetzpunkte an ihrer Stelle, klar vor jeder anderen)? */
    static boolean bildZeigt(int f, List<Matcher.Hit> sockel, double massstab) {
        if (f < 0 || f >= FEST_ORTE.size() || sockel == null) return false;
        int[] t = kartenTreffer(sockel, massstab);
        int andere = 0;
        for (int g = 0; g < t.length; g++) if (g != f) andere = Math.max(andere, t[g]);
        return t[f] >= KASTEN_AB_TREFFER && t[f] - andere >= 3;
    }

    /** Die feste Karte, die dieses eine Bild sicher zeigt, sonst -1 (fuer Pruefungen an Fotos). */
    static int festeKarteIm(List<Matcher.Hit> sockel, int breite) {
        double massstab = breite / BREITE_GEMESSEN;
        for (int f = 0; f < FEST_ORTE.size(); f++) if (bildZeigt(f, sockel, massstab)) return f;
        return -1;
    }

    static String festerName(int f) {
        return f >= 0 && f < FESTE_NAMEN.length ? FESTE_NAMEN[f] : "?";
    }

    /** Je Stuetzpunkt der festen Karte f der Kasten, in dem die MITTE eines Symbols liegen darf. */
    static List<org.opencv.core.Rect> symbolKaesten(int f, int breite, int hoehe) {
        double massstab = breite / BREITE_GEMESSEN;
        int sx = (int) Math.ceil(KASTEN_SEITLICH_PX * massstab);
        int oben = (int) Math.ceil(KASTEN_DARUEBER_PX * massstab);
        int unten = (int) Math.ceil(KASTEN_DARUNTER_PX * massstab);
        List<org.opencv.core.Rect> aus = new ArrayList<>();
        for (int[] o : FEST_ORTE.get(f)) {
            int x = (int) Math.round(o[0] * massstab), y = (int) Math.round(o[1] * massstab);
            int x0 = Math.max(0, x - sx), y0 = Math.max(0, y - oben);
            int x1 = Math.min(breite, x + sx + 1), y1 = Math.min(hoehe, y + unten + 1);
            if (x1 > x0 && y1 > y0) aus.add(new org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0));
        }
        return aus;
    }

    /**
     * Wo auf diesem Bild die Mitte eines Symbols liegen kann: je Stuetzpunkt der festen Karte ein Kasten. Null (= wie
     * bisher im ganzen Bild suchen), wenn die Etage nicht auf einer festen Karte laeuft oder das Bild sie nicht sicher
     * zeigt (Schriftzug davor, schon die naechste Etage, anderer Ausschnitt).
     */
    List<org.opencv.core.Rect> symbolKaesten(List<Matcher.Hit> sockel, int breite, int hoehe) {
        if (fest < 0 || !bildZeigt(fest, sockel, breite / BREITE_GEMESSEN)) return null;
        return symbolKaesten(fest, breite, hoehe);
    }

    // ------------------------------------------------------------------
    //  Unbekanntes Etagenmuster (21.11.)
    // ------------------------------------------------------------------

    private double massstabZuletzt = 1;
    /** {Fingerabdruck, Beschreibung} eines unbekannten Musters dieser Etage, bis die Kathedrale es abholt. */
    private String[] unbekannt = null;

    private void unbekanntMerken(String warum, double massstab) {
        List<int[]> orte = new ArrayList<>();
        for (Knoten k : knoten) {
            if (k.sockel) orte.add(new int[]{(int) Math.round(k.x / massstab), (int) Math.round(k.y / massstab)});
        }
        orte.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        int beste = -1, besteTreffer = -1;
        for (int f = 0; f < FEST_ORTE.size(); f++) {
            int t = 0;
            for (int[] o : FEST_ORTE.get(f)) {
                for (int[] k : orte) {
                    if (Math.hypot(o[0] - k[0], o[1] - k[1]) <= GLEICH_PX) {
                        t++;
                        break;
                    }
                }
            }
            if (t > besteTreffer) {
                besteTreffer = t;
                beste = f;
            }
        }
        StringBuilder stellen = new StringBuilder();
        for (int[] o : orte) stellen.append(o[0]).append(',').append(o[1]).append(' ');
        unbekannt = new String[]{fingerabdruck(orte), warum + "; " + orte.size()
                + Sprache.t(" waypoints at ", " Stuetzpunkte bei ") + stellen.toString().trim()
                + Sprache.t(" (1159x770); closest fixed map \"", " (1159x770); naechste feste Karte \"") + FESTE_NAMEN[beste]
                + "\" " + besteTreffer + "/" + FEST_ORTE.get(beste).size()};
    }

    /** Fingerabdruck einer Karte: ihre Sockel auf ein 80-Punkte-Raster gerundet, sortiert, als kurze Zahl. */
    static String fingerabdruck(List<int[]> orte) {
        List<String> zellen = new ArrayList<>();
        for (int[] o : orte) zellen.add(Math.round(o[0] / 80.0) + "/" + Math.round(o[1] / 80.0));
        java.util.Collections.sort(zellen);
        return Integer.toHexString(String.join(" ", zellen).hashCode());
    }

    /** Das unbekannte Muster dieser Etage {Fingerabdruck, Beschreibung} -- einmal, danach null. */
    String[] unbekanntesMusterAbholen() {
        String[] u = unbekannt;
        unbekannt = null;
        return u;
    }

    /** War diese Karte beim ersten Blick noch unbekannt? Dann lohnt ein Foto zum Nachmessen. */
    boolean karteNeu() {
        return karteNeu;
    }

    /** Der Bot klickt diesen Stuetzpunkt an. Gemerkt wird, wer in diesem Augenblick zu ist. */
    void gespielt(Matcher.Hit ziel) {
        gespielt++;
        letzter = ziel == null ? -1 : knotenUnter(ziel.centerX(), ziel.centerY());
        if (gespielt == 1) erster = letzter;
        // Die Uhren "seit wann nicht erkannt" zaehlen nur die Zeit, in der der
        // Bot auf die Karte SIEHT. Test 27.09., 05:52: Ein Stuetzpunkt lag
        // nach zwei Segen hintereinander unter dem Schriftzug; die Zeit der
        // Kaempfe dazwischen zaehlte mit, und seine bekannte Stelle wurde
        // sofort angeklickt statt erst nach anderthalb Sekunden Hinsehen.
        for (Knoten k : knoten) k.ohneKlickSeit = 0;
        unklarSeit = 0;
        // Das gemerkte Motiv ist mit dem Klick verbraucht (Bauplan L1): Wird
        // der Klick nicht angenommen, kommt es erst mit einem neuen Bild
        // wieder -- so wird kein Stuetzpunkt zweimal blind aus dem Gedaechtnis
        // angeklickt.
        if (letzter >= 0 && letzter < knoten.size()) {
            knoten.get(letzter).motiv = null;
            knoten.get(letzter).art = null;
        }
        // Nur was das Bild DIESER Entscheidung als zu zeigt, zaehlt. Der
        // letzte bekannte Stand reicht nicht (Test 27.09., 03:12: 23
        // "bewiesene" Linien auf einer Karte mit rund 16): Ein Stuetzpunkt,
        // der schon frueher frei wurde, aber hinter dem Schriftzug lag, fiel
        // erst spaeter auf -- und wurde dem falschen Nachbarn zugeschrieben.
        zuVorher = new boolean[knoten.size()];
        for (int i = 0; i < knoten.size(); i++) zuVorher[i] = knoten.get(i).zuJetzt();
    }

    int gespielteStuetzpunkte() {
        return gespielt;
    }

    /**
     * DER ZULETZT ANGEKLICKTE STUETZPUNKT IST ABGESCHLOSSEN -- das Spiel hat
     * es gezeigt (Segen nach dem Sieg oder "Waypoint completed").
     *
     * Test 27.09., 05:36: Gleich nach dem Segen liegt der Schriftzug "You have
     * got a blessing" ueber der Karte. Der tuerkise Sockel des eben gespielten
     * Stuetzpunkts war darunter nicht zu sehen, der Planer hielt ihn fuer
     * unfertig und nahm einen anderen Weg -- ein Stuetzpunkt umsonst (12
     * statt 11). Auf einer festen Karte gilt der Abschluss deshalb sofort,
     * auch wenn das Bild ihn noch nicht zeigt.
     */
    void letzterAbgeschlossen() {
        if (fest < 0 || letzter < 0 || letzter >= knoten.size()) return;
        Knoten k = knoten.get(letzter);
        if (k.fertig) return;
        k.fertig = true;
        k.frage = false;
        k.boss = false;
        k.klick = null;
        k.aufJetzt = false;
        k.ohneKlickSeit = 0;
    }

    /** Ist der zuletzt angeklickte Stuetzpunkt abgeschlossen (Segen oder "Waypoint completed")? */
    boolean letzterFertig() {
        return letzter >= 0 && letzter < knoten.size() && knoten.get(letzter).fertig;
    }

    int planAmAnfang() {
        return planAmAnfang;
    }

    private int naechster(double x, double y, double hoechstens) {
        int beste = -1;
        double besteEntfernung = hoechstens;
        for (int i = 0; i < knoten.size(); i++) {
            double d = Math.hypot(knoten.get(i).x - x, knoten.get(i).y - y);
            if (d <= besteEntfernung) {
                besteEntfernung = d;
                beste = i;
            }
        }
        return beste;
    }

    /**
     * Der Stuetzpunkt, zu dem ein Symbol an dieser Stelle gehoert: Symbole
     * (Motiv, Schloss, Fragezeichen) sitzen UEBER ihrem Sockel, nie daneben.
     * -1, wenn dort keiner ist.
     */
    private int knotenUnter(int sx, int sy) {
        int beste = -1;
        double besteEntfernung = Double.MAX_VALUE;
        for (int i = 0; i < knoten.size(); i++) {
            Knoten k = knoten.get(i);
            int dx = Math.abs(k.x - sx);
            int dy = k.y - sy;                       // positiv: Sockel liegt tiefer
            if (dx > SYMBOL_SEITLICH_PX) continue;
            // Ein Stuetzpunkt, der selbst nur ueber sein Symbol bekannt ist,
            // liegt auf gleicher Hoehe.
            int oben = k.sockel ? SYMBOL_DARUEBER_PX : 50;
            if (dy > oben || dy < -SYMBOL_DARUNTER_PX) continue;
            double d = dx + Math.abs(dy - (k.sockel ? 40 : 0));
            if (d < besteEntfernung) {
                besteEntfernung = d;
                beste = i;
            }
        }
        return beste;
    }

    /**
     * Wirft Flecken heraus, die kein Sockel sind, sondern etwas Rotes UEBER
     * einem Sockel -- die Fahne von "Random skirmish", der Griff des Schwerts
     * auf einem abgeschlossenen Stuetzpunkt. Gemessen am 27.09.: Solche
     * Flecken standen 57 Bildpunkte ueber dem Sockel als eigener Stuetzpunkt
     * im Netz (19 statt 14). Echte Stuetzpunkte liegen nie naeher als rund
     * 110 Bildpunkte beieinander.
     */
    static List<Matcher.Hit> ohneSymbole(List<Matcher.Hit> sockel) {
        List<Matcher.Hit> echte = new ArrayList<>();
        for (Matcher.Hit s : sockel) {
            boolean symbol = false;
            for (Matcher.Hit unten : sockel) {
                int dy = unten.centerY() - s.centerY();
                if (unten != s && Math.abs(unten.centerX() - s.centerX()) <= 35 && dy >= 25 && dy <= 100) {
                    symbol = true;
                    break;
                }
            }
            if (!symbol) echte.add(s);
        }
        return echte;
    }

    /** Liegt diese Stelle ueber einem schon bekannten Stuetzpunkt? Dann ist sie ein Symbol, kein Sockel. */
    private boolean ueberEinemKnoten(int x, int y) {
        for (Knoten k : knoten) {
            int dy = k.y - y;
            if (Math.abs(k.x - x) <= 35 && dy >= 25 && dy <= 100) return true;
        }
        return false;
    }

    /** Der Stuetzpunkt zu einem Symbol; gibt es dort noch keinen, wird er angelegt. */
    private Knoten zuSymbol(Matcher.Hit symbol) {
        int i = knotenUnter(symbol.centerX(), symbol.centerY());
        if (i >= 0) return knoten.get(i);
        // Auf einer festen Karte gibt es genau ihre Stuetzpunkte. Ein Symbol
        // ohne Stuetzpunkt darunter ist dort keiner (null).
        if (fest >= 0) return null;
        Knoten neu = new Knoten(symbol.centerX(), symbol.centerY(), false);
        knoten.add(neu);
        return neu;
    }

    /**
     * Traegt ein, was das jetzige Bild zeigt. Was gerade verdeckt ist (der
     * Schriftzug "You have got a blessing" zieht ueber die Karte), behaelt
     * seinen letzten Stand.
     *
     * @param sockel        alle Sockel (ohne den Bildrand)
     * @param fertigeSockel die tuerkisen -- abgeschlossene Stuetzpunkte (mit Bildrand)
     * @param offene        anklickbare Stuetzpunkte (erkanntes Motiv, kein Schloss)
     * @param gesperrteMotive erkannte Motive MIT Schloss -- nur fuers Gedaechtnis (Bauplan L1)
     * @param reif          die Karte steht lange genug (zweiter Blick)
     */
    void beobachten(Mat screen, List<Matcher.Hit> sockel, List<Matcher.Hit> fertigeSockel,
                    List<Matcher.Hit> fragen, List<Matcher.Hit> bosse, Optional<Matcher.Hit> waechter,
                    List<Matcher.Hit> offene, List<Matcher.Hit> gesperrteMotive,
                    List<Matcher.Hit> schloesser, boolean reif) {
        bilder++;

        for (Matcher.Hit s : ohneSymbole(sockel)) {
            int i = naechster(s.centerX(), s.centerY(), GLEICH_PX);
            if (i >= 0) {
                if (!knoten.get(i).sockel) sockelGeben(knoten.get(i), s);
                continue;
            }
            // Ein Stuetzpunkt, der bisher nur ueber sein Symbol bekannt war,
            // bekommt jetzt die Stelle seines Sockels.
            int ueber = -1;
            for (int k = 0; k < knoten.size(); k++) {
                Knoten c = knoten.get(k);
                int dy = s.centerY() - c.y;
                if (!c.sockel && Math.abs(c.x - s.centerX()) <= SYMBOL_SEITLICH_PX
                        && dy >= -SYMBOL_DARUNTER_PX && dy <= SYMBOL_DARUEBER_PX) {
                    ueber = k;
                    break;
                }
            }
            if (ueber >= 0) {
                sockelGeben(knoten.get(ueber), s);
                continue;
            }
            // Steht die Karte fest, kommt aus einem Farbfleck kein neuer
            // Stuetzpunkt mehr: Das waeren Schwertgriffe und Fahnen.
            if (aufgenommen || ueberEinemKnoten(s.centerX(), s.centerY())) continue;
            knoten.add(new Knoten(s.centerX(), s.centerY(), true));
        }

        // Zuerst die festen Karten: Passt eine, gelten genau ihre Stuetzpunkte
        // und Linien. Sonst das Gelernte.
        massstabZuletzt = screen.cols() / BREITE_GEMESSEN;
        if (fest < 0 && !gelerntesGesucht && zahlSockel() >= KARTE_AB_KNOTEN) {
            double massstab = screen.cols() / BREITE_GEMESSEN;
            int f = festeKarteSuchen(massstab);
            if (f >= 0) {
                festUebernehmen(f, massstab);
                gelerntesGesucht = true;
            } else {
                // Autor 21.11.: "Ist ein Muster unbekannt: Fehlerbericht an Claude." Die Etage laeuft weiter wie
                // bisher (gelerntes Netz, Suche im ganzen Bild); die Kathedrale holt das hier ab und meldet es.
                unbekanntMerken(Sprache.t("no fixed map fits", "keine feste Karte passt"), massstab);
            }
        }
        gelerntesUebernehmen();
        if (!aufgenommen && gespielt >= 1 && zahlSockel() >= KARTE_AB_KNOTEN) aufgenommen = true;

        long jetzt = System.currentTimeMillis();
        for (Knoten k : knoten) {
            k.klick = null;
            k.gesperrt = false;
            k.frageJetzt = false;
            k.aufJetzt = false;
        }
        for (Matcher.Hit f : fragen) {
            Knoten k = zuSymbol(f);
            if (k != null && !k.fertig) {
                k.frage = true;
                if (++k.frageBlicke >= 2) k.warFrage = true;
                k.frageJetzt = true;
                k.frageGesehenUm = jetzt;
                k.boss = false;
            }
        }
        // Ein Fragezeichen, das seit ueber einer Sekunde auf keinem Bild mehr
        // zu sehen war, ist aufgedeckt -- auch wenn der Bot das Motiv darunter
        // nicht kennt. (Der Schriftzug verdeckt eine Stelle nur kurz.)
        for (Knoten k : knoten) {
            if (k.frage && !k.frageJetzt && jetzt - k.frageGesehenUm > FRAGE_WEG_MS) k.frage = false;
        }
        for (Matcher.Hit s : schloesser) {
            int i = knotenUnter(s.centerX(), s.centerY());
            if (i >= 0) knoten.get(i).gesperrt = true;
        }
        for (Matcher.Hit o : offene) {
            Knoten k = zuSymbol(o);
            if (k == null) continue;
            k.frage = false;          // ein erkanntes Motiv: aufgedeckt
            k.motivMerken(o);         // Gedaechtnis (Bauplan L1)
            if (!k.fertig && !k.gesperrt) {
                k.klick = o;
                k.aufJetzt = true;
            }
        }
        // Gedaechtnis auch fuer Motive mit Schloss (Bauplan L1): Geht der
        // Stuetzpunkt spaeter auf, waehrend der Schriftzug davor liegt, ist
        // seine Stelle schon bekannt. Nur auf einer festen Karte -- sonst
        // wuerde zuSymbol neue Stuetzpunkte anlegen. Am Zustand aendert das
        // nichts (gesperrt kommt aus den Schloessern oben).
        if (fest >= 0) {
            for (Matcher.Hit g : gesperrteMotive) {
                Knoten k = zuSymbol(g);
                if (k != null && !k.fertig) k.motivMerken(g);
            }
        }
        for (Matcher.Hit b : bosse) {
            Knoten k = zuSymbol(b);
            if (k == null || k.fertig) continue;
            k.frage = false;
            k.boss = true;
            k.warBoss = true;
            if (!k.gesperrt) k.aufJetzt = true;
        }
        if (waechter.isPresent()) {
            Knoten k = zuSymbol(waechter.get());
            if (k != null) {
                for (Knoten anderer : knoten) anderer.waechter = false;
                k.waechter = true;
                k.frage = false;
                k.boss = false;
                if (!k.gesperrt) k.aufJetzt = true;
            }
        }
        for (Matcher.Hit f : fertigeSockel) {
            int i = naechster(f.centerX(), f.centerY(), GLEICH_PX + 10);
            if (i < 0) {
                // Am Bildrand ist ein Stuetzpunkt nur ueber sein Symbol
                // bekannt; das sass ein Stueck ueber dem Sockel.
                i = knotenUnter(f.centerX(), f.centerY() - 40);
                if (i >= 0 && knoten.get(i).sockel) i = -1;
            }
            if (i < 0) continue;      // kein bekannter Stuetzpunkt: ein Knopf am Rand
            Knoten k = knoten.get(i);
            k.fertig = true;
            k.frage = false;
            k.boss = false;
            k.klick = null;
            k.aufJetzt = false;
        }
        for (Knoten k : knoten) {
            if (k.fertig || k.aufJetzt) k.zuletztZu = false;
            else if (k.zuJetzt()) k.zuletztZu = true;
        }

        // Feste Karte: Die Linien stehen fest -- nichts zu lernen, nichts zu
        // suchen (spart je Blick die Punktsuche, gemessen rund 30 ms).
        if (fest >= 0) {
            // Ein Fragezeichen ist aufgedeckt, sobald ein Nachbar fertig ist.
            for (int i = 0; i < knoten.size(); i++) {
                Knoten k = knoten.get(i);
                if (k.aufgedecktUm != 0 || k.fertig) continue;
                for (int j = 0; j < knoten.size(); j++) {
                    if (festNachbar[i][j] && knoten.get(j).fertig) {
                        k.aufgedecktUm = jetzt;
                        break;
                    }
                }
            }
            ohneKlickMerken(jetzt);
            festPruefen(reif);
            if (fest >= 0) return;
        }

        ausDemSpielLernen(reif);

        // Linien aus diesem Bild. Was schon sicher ist, wird nicht noch einmal gesucht.
        List<double[]> punkte = punkteFinden(screen);
        if (punkte.isEmpty()) return;
        for (int i = 0; i < knoten.size(); i++) {
            // Linien laufen von Sockel zu Sockel. Ein Stuetzpunkt, von dem nur
            // das Symbol bekannt ist, hat keine genaue Stelle -- er haengt in
            // der Planung am naechsten Nachbarn.
            if (!knoten.get(i).sockel) continue;
            for (int j = i + 1; j < knoten.size(); j++) {
                if (!knoten.get(j).sockel) continue;
                int s = schluessel(i, j);
                if (gesehen.getOrDefault(s, 0) >= 3 || bewiesen.contains(s) || widerlegt.contains(s)) continue;
                if (linie(i, j, punkte)) gesehen.merge(s, 1, Integer::sum);
            }
        }
    }

    private static void sockelGeben(Knoten k, Matcher.Hit s) {
        k.x = s.centerX();
        k.y = s.centerY();
        k.sockel = true;
    }

    private int zahlSockel() {
        int z = 0;
        for (Knoten k : knoten) if (k.sockel) z++;
        return z;
    }

    /**
     * DAS SPIEL ZEIGT DIE LINIEN SELBST: Ist der zuletzt angeklickte
     * Stuetzpunkt fertig, dann ist alles, was vorher zu war und jetzt offen
     * ist, sein Nachbar -- sonst hat sich auf der Karte nichts geaendert. Und
     * was im jetzigen Bild weiter zu ist, ist sicher KEIN Nachbar.
     */
    private void ausDemSpielLernen(boolean reif) {
        if (letzter >= 0 && letzter < knoten.size() && knoten.get(letzter).fertig) {
            for (int i = 0; i < knoten.size(); i++) {
                if (i == letzter) continue;
                Knoten k = knoten.get(i);
                int s = schluessel(letzter, i);
                if (i < zuVorher.length && zuVorher[i] && k.aufJetzt) {
                    if (bewiesen.add(s)) {
                        widerlegt.remove(s);
                        Log.debug("Kathedrale", "Wegenetz: Linie " + ort(letzter) + " - " + ort(i)
                                + " bewiesen (nach dem Abschluss frei geworden).");
                    }
                    zuVorher[i] = false;
                }
            }
        }
        // Ein Stuetzpunkt, der im jetzigen Bild zu ist, hat KEINEN fertigen
        // Nachbarn -- sonst waere er offen. Das gilt fuer alle fertigen, nicht
        // nur fuer den letzten, und raeumt schnell auf, was nicht sein kann.
        if (!reif) return;
        for (int i = 0; i < knoten.size(); i++) {
            Knoten k = knoten.get(i);
            if (k.fertig || !k.zuJetzt()) continue;
            for (int j = 0; j < knoten.size(); j++) {
                if (j == i || !knoten.get(j).fertig) continue;
                int s = schluessel(i, j);
                if (!bewiesen.contains(s)) widerlegt.add(s);
            }
        }
    }

    /**
     * Gilt die Linie? Bewiesene und gelernte immer; gesehene erst ab dem
     * zweiten Bild -- ein einzelnes Bild kann taeuschen (der Schriftzug).
     */
    private boolean verbunden(int i, int j) {
        if (fest >= 0) return festNachbar[i][j];
        int s = schluessel(i, j);
        if (bewiesen.contains(s)) return true;
        if (widerlegt.contains(s)) return false;
        return gelernt.contains(s) || gesehen.getOrDefault(s, 0) >= LERNEN_AB;
    }

    /** Alle Stuetzpunkte mit ihrem Stand -- zum Nachmessen. */
    String knotenText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < knoten.size(); i++) {
            Knoten k = knoten.get(i);
            b.append(ort(i)).append(k.sockel ? "" : "~")
             .append(k.fertig ? "fertig" : k.waechter ? "WAECHTER" : k.frage ? "?" : k.boss ? "BOSS"
                     : k.klick != null ? "offen" : k.gesperrt ? "zu" : "-")
             .append(' ');
        }
        return b.toString().trim();
    }

    /** Alle geltenden Linien mit ihren Stellen -- zum Nachmessen. */
    String linienText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < knoten.size(); i++) {
            for (int j = i + 1; j < knoten.size(); j++) {
                if (verbunden(i, j)) {
                    b.append(ort(i)).append(bewiesen.contains(schluessel(i, j)) ? '=' : '-')
                     .append(ort(j)).append(' ');
                }
            }
        }
        return b.toString().trim();
    }

    private int linienZahl() {
        int z = 0;
        for (int i = 0; i < knoten.size(); i++) {
            for (int j = i + 1; j < knoten.size(); j++) if (verbunden(i, j)) z++;
        }
        return z;
    }

    // ------------------------------------------------------------------
    //  Der kuerzeste Weg
    // ------------------------------------------------------------------

    enum Art {
        /** Diesen Stuetzpunkt als naechsten spielen. */
        SCHRITT,
        /** Alles erledigt, der Waechter ist frei. */
        WAECHTER,
        /** Gleich noch einmal hinsehen (etwas muesste aufgedeckt sein, ist aber gerade verdeckt). */
        WARTEN
    }

    record Plan(Art art, Matcher.Hit klick, int noetig, String grund) {}

    /** So nah beieinander koennen zwei Stuetzpunkte verbunden sein, auch wenn die Linie nicht zu sehen war. */
    private static final double DENKBAR_PX = 215;
    /** Je Stuetzpunkt hoechstens so viele nur denkbare Linien (die naechsten). */
    private static final int DENKBAR_JE_KNOTEN = 3;

    /**
     * Das Netz, mit dem geplant wird: geltende Linien, und wer keine hat,
     * haengt am naechsten.
     *
     * @param denkbar auch Linien annehmen, die nur DENKBAR sind: zwei nahe
     *                Stuetzpunkte, zwischen denen nichts dagegen spricht.
     *                Test 27.09., 03:28: Auf einer neuen Karte gab die Planung
     *                auf ("kein Plan"), weil zum Waechter keine Linie bekannt
     *                war -- die alte Wegsuche spielte dann fuenf Stuetzpunkte,
     *                wo drei gereicht haetten. Lieber mit dem Wahrscheinlichen
     *                rechnen; was nicht stimmt, streicht das Spiel von selbst.
     */
    private boolean[][] netz(boolean denkbar) {
        int n = knoten.size();
        boolean[][] nachbar = new boolean[n][n];
        int[] grad = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (verbunden(i, j)) {
                    nachbar[i][j] = nachbar[j][i] = true;
                    grad[i]++;
                    grad[j]++;
                }
            }
        }
        if (denkbar) {
            for (int i = 0; i < n; i++) {
                if (!echt(i)) continue;
                // die naechsten Stuetzpunkte zuerst
                List<Integer> nahe = new ArrayList<>();
                for (int j = 0; j < n; j++) {
                    if (j == i || !echt(j) || nachbar[i][j]) continue;
                    if (widerlegt.contains(schluessel(i, j))) continue;
                    if (abstand(i, j) > DENKBAR_PX || liegtDazwischen(i, j)) continue;
                    nahe.add(j);
                }
                final int von = i;
                nahe.sort((a, b) -> Double.compare(abstand(von, a), abstand(von, b)));
                int frei = Math.max(0, DENKBAR_JE_KNOTEN - grad[i]);
                for (int t = 0; t < nahe.size() && t < frei; t++) {
                    int j = nahe.get(t);
                    nachbar[i][j] = nachbar[j][i] = true;
                }
            }
            for (int i = 0; i < n; i++) {
                grad[i] = 0;
                for (int j = 0; j < n; j++) if (nachbar[i][j]) grad[i]++;
            }
        }
        // Ein Stuetzpunkt ohne erkannte Linie haengt am naechsten. Das trifft
        // vor allem die Spitzen der Karte: ihre kurze Linie verschwindet ganz
        // hinter der Figur davor. Was das Spiel widerlegt hat, scheidet aus.
        for (int z = 0; z < n; z++) {
            if (grad[z] > 0) continue;
            Knoten k = knoten.get(z);
            if (!(k.frage || k.boss || k.waechter || k.klick != null || k.fertig || k.gesperrt)) continue;
            int beste = -1;
            double besteEntfernung = ISOLIERT_MAX_PX;
            for (int j = 0; j < n; j++) {
                if (j == z || grad[j] == 0 || widerlegt.contains(schluessel(z, j))) continue;
                double d = Math.hypot(knoten.get(j).x - k.x, knoten.get(j).y - k.y);
                if (d < besteEntfernung) {
                    besteEntfernung = d;
                    beste = j;
                }
            }
            if (beste >= 0) nachbar[z][beste] = nachbar[beste][z] = true;
        }
        return nachbar;
    }

    /** Ein Stuetzpunkt mit einem Zustand -- kein Farbfleck, der zufaellig wie ein Sockel aussieht. */
    private boolean echt(int i) {
        Knoten k = knoten.get(i);
        return k.fertig || k.frage || k.boss || k.waechter || k.klick != null || k.gesperrt || k.zuletztZu;
    }

    private double abstand(int i, int j) {
        return Math.hypot(knoten.get(i).x - knoten.get(j).x, knoten.get(i).y - knoten.get(j).y);
    }

    /** Liegt ein dritter Stuetzpunkt auf der Strecke zwischen den beiden? */
    private boolean liegtDazwischen(int i, int j) {
        Knoten a = knoten.get(i), b = knoten.get(j);
        double len = abstand(i, j);
        if (len < 1) return false;
        double dx = (b.x - a.x) / len, dy = (b.y - a.y) / len;
        for (int k = 0; k < knoten.size(); k++) {
            if (k == i || k == j || !echt(k)) continue;
            Knoten c = knoten.get(k);
            double s = (c.x - a.x) * dx + (c.y - a.y) * dy;
            double q = Math.abs(-(c.x - a.x) * dy + (c.y - a.y) * dx);
            if (s > 30 && s < len - 30 && q < 35) return true;
        }
        return false;
    }

    /**
     * Der naechste Schritt auf dem kuerzesten Weg, oder null, wenn das Netz
     * nichts hergibt -- dann spielt die Kathedrale wie bisher.
     *
     * Erst wird mit den sicheren Linien gerechnet. Fuehrt damit kein Weg zu
     * allen Zielen, kommen die denkbaren dazu.
     *
     * @param reif der zweite Blick ist erfolgt (die Karte steht lange genug)
     */
    Plan planen(boolean reif) {
        planAusGedaechtnis = false;
        if (fest >= 0) return planenFest();
        Plan sicher = planen(reif, false);
        if (sicher != null) return sicher;
        return planen(reif, true);
    }

    /**
     * DER WEG AUF EINER FESTEN KARTE (27.09.).
     *
     * Gespielt werden muessen alle Bosse und alles, was ein Fragezeichen ist
     * oder war (dahinter steckt ein Boss oder der Waechter). Dazu kommt die
     * kleinste Menge gewoehnlicher Stuetzpunkte, die das alles mit dem
     * Fertigen verbindet. Der Waechter kommt zuletzt und ist kein Durchgang.
     *
     * Anders als beim gelernten Netz:
     *   - Ein Fragezeichen ist nach seinem Kampf ein Durchgang wie jeder
     *     andere. Vorher zaehlte es als Sackgasse -- dann ging der Weg aussen
     *     herum, auch wenn er durch den Boss hindurch kuerzer war.
     *   - Ist der beste Schritt gerade nicht zu sehen (der Schriftzug "You
     *     have got a blessing" zieht nach jedem Segen ueber die Karte), wird
     *     kurz GEWARTET statt ein schlechterer genommen. Vorher spielte der
     *     Bot dann einen Umweg.
     */
    private Plan planenFest() {
        Plan plan = planenFest(false);
        // Geht es ohne die nicht anklickbaren Stuetzpunkte gar nicht, dann mit
        // ihnen (der Planer wartet dann, bis sie zu erkennen sind).
        return plan != null ? plan : planenFest(true);
    }

    /** Seit wann auf ein aufgedecktes, noch nicht erkennbares Fragezeichen gewartet wird (0 = gar nicht). */
    private long unklarSeit = 0;

    /**
     * Noch warten? Ein Boss wird erkannt, sobald der Schriftzug weitergezogen
     * ist (rund 1,4 s). Bleibt die Stelle laenger unerkannt, steht dort ein
     * Motiv, das der Bot nicht kennt -- ein Boss ist es dann nicht.
     */
    private boolean aufUnklaresWarten(long jetzt) {
        if (unklarSeit == 0) unklarSeit = jetzt;
        return jetzt - unklarSeit < AUFGEDECKT_WARTEN_MS;
    }

    /**
     * GEDAECHTNIS-KLICK (Bauplan L1, 09.10.): Darf ein gerade verdeckter
     * Stuetzpunkt an der Stelle seines gemerkten Motivs angeklickt werden?
     *
     * Gemessen 08.10. (64 Runs): 144-mal "der naechste Schritt ist gerade
     * verdeckt", jedes Mal rund 450 ms mehr als ein normaler Schritt. Auf
     * einer festen Karte ist bekannt, dass er offen ist (ein Nachbar ist
     * fertig), und wo sein Motiv sitzt (frueheres Bild, auch mit Schloss).
     *
     * Nur, wenn nichts dagegen spricht: Schalter an, ein Nachbar fertig, im
     * jetzigen Bild weder Schloss noch Fragezeichen, und auf der Etage gibt es
     * gerade kein aufgedecktes, noch unerkanntes Fragezeichen (das koennte ein
     * Boss sein -- Regel "a/c: Bosse zuerst"). Der Waechter nie.
     */
    private boolean ausGedaechtnisErlaubt(Knoten k, boolean etwasFertig, boolean unklar) {
        return gedaechtnisKlick && !probelauf && fest >= 0 && etwasFertig && !unklar
                && k.motiv != null && !k.fertig && !k.waechter
                && !k.gesperrt && !k.frageJetzt && !k.frage;
    }

    /**
     * VORAUSPLANUNG (Bauplan L2, 09.10.): Der naechste Schritt, ausgerechnet
     * gleich beim Klick auf den Stuetzpunkt -- so, als waere er schon fertig.
     * Nach Kampf oder Fenster muss die Kathedrale dann nur noch an DIESER
     * Stelle nachsehen statt die ganze Karte auszuwerten (gemessen 08.10.:
     * ~93 ms je voller Auswertung).
     *
     * Gerechnet wird auf einer KOPIE der Stuetzpunkte: Der gespielte gilt als
     * fertig, jeder Stuetzpunkt mit gemerktem Motiv und fertigem Nachbarn als
     * offen. Was hinter einem Fragezeichen steckt, zeigt erst das Spiel --
     * deshalb gibt es nichts, wenn neben dem gespielten Stuetzpunkt ein
     * Fragezeichen ist oder war oder einer, dessen Motiv nie zu sehen war.
     * Auch nichts, wenn die Antwort nicht SCHRITT ist (der Waechter wird nie
     * vorausgeplant).
     *
     * @return der Plan, oder null
     */
    Plan vorausPlanen() {
        if (fest < 0 || letzter < 0 || letzter >= knoten.size()) return null;
        Knoten gespielterKnoten = knoten.get(letzter);
        if (gespielterKnoten.waechter || gespielterKnoten.fertig) return null;
        for (int j = 0; j < knoten.size(); j++) {
            if (j == letzter || !festNachbar[letzter][j]) continue;
            Knoten nb = knoten.get(j);
            if (nb.fertig || nb.waechter) continue;                           // der Waechter ist bekannt
            if (nb.frage || nb.warFrage || nb.frageBlicke > 0) return null;   // dahinter kann ein Boss stecken
            if (nb.motiv == null) return null;                                // nie erkannt
        }
        List<Knoten> echt = new ArrayList<>(knoten);
        long unklarVorher = unklarSeit;
        int planVorher = planAmAnfang;
        long jetzt = System.currentTimeMillis();
        try {
            knoten.clear();
            for (Knoten k : echt) knoten.add(k.kopie());
            Knoten g = knoten.get(letzter);
            g.fertig = true;
            g.frage = false;
            g.boss = false;
            g.klick = null;
            g.aufJetzt = false;
            g.gesperrt = false;
            g.frageJetzt = false;
            for (int i = 0; i < knoten.size(); i++) {
                Knoten k = knoten.get(i);
                k.ohneKlickSeit = 0;
                if (k.fertig || k.waechter) continue;
                boolean dran = false;
                for (int j = 0; j < knoten.size() && !dran; j++) dran = festNachbar[i][j] && knoten.get(j).fertig;
                if (!dran) continue;
                if (k.aufgedecktUm == 0) k.aufgedecktUm = jetzt;
                if (k.motiv != null) {
                    k.klick = k.motiv;
                    k.gesperrt = false;
                    k.frageJetzt = false;
                    k.aufJetzt = true;
                }
            }
            probelauf = true;
            Plan p = planenFest(false);
            return p != null && p.art() == Art.SCHRITT && p.klick() != null ? p : null;
        } finally {
            probelauf = false;
            knoten.clear();
            knoten.addAll(echt);
            unklarSeit = unklarVorher;
            planAmAnfang = planVorher;
        }
    }

    /**
     * @param mitNichtKlickbaren auch ueber Stuetzpunkte planen, die offen sein
     *        muessten, an denen der Bot aber seit NICHT_KLICKBAR_MS nichts
     *        Anklickbares erkennt. Sonst wird um sie herum geplant (Test
     *        27.09., 05:08: 2 s gewartet, dann spielte die alte Wegsuche
     *        einen Stuetzpunkt, der zu nichts fuehrte).
     */
    private Plan planenFest(boolean mitNichtKlickbaren) {
        int n = knoten.size();
        long jetzt = System.currentTimeMillis();
        boolean etwasFertig = false;
        int waechter = -1;
        List<Integer> pflicht = new ArrayList<>();    // Bosse, Fragezeichen: spielen, danach Durchgang
        List<Integer> frei = new ArrayList<>();       // gewoehnliche Stuetzpunkte: moegliche Trittsteine
        // Gerade aufgedeckt, aber noch nicht zu erkennen (der Schriftzug liegt
        // davor): Das kann ein Boss sein. Bevor es zum Waechter geht, wird
        // darauf kurz gewartet.
        boolean unklar = false;
        for (int i = 0; i < n; i++) {
            Knoten k = knoten.get(i);
            if (k.fertig) {
                etwasFertig = true;
            } else if (k.waechter) {
                waechter = i;
            } else if (k.boss) {
                pflicht.add(i);
            } else if (k.aufgedecktUm != 0) {
                // Aufgedeckt: Ziel ist es nur als Boss (oben) oder Waechter.
                // Alles andere ist ein gewoehnlicher Stuetzpunkt -- auch ein
                // Motiv, das der Bot nicht kennt.
                // War hier ein Fragezeichen, und ist jetzt NICHTS zu erkennen,
                // kann es ein Boss sein, der gerade verdeckt ist. Das klaert
                // sich mit einem der naechsten Blicke; gewartet wird darauf
                // erst, wenn sonst nur noch der Waechter uebrig ist (unten).
                if (k.frageBlicke > 0 && k.klick == null && !k.gesperrt) {
                    unklar = true;
                }
                if (!mitNichtKlickbaren && k.ohneKlickSeit != 0
                        && jetzt - k.ohneKlickSeit > NICHT_KLICKBAR_MS) {
                    continue;      // nicht anzuklicken: kein Trittstein
                }
                frei.add(i);
            } else if (k.frage || k.warFrage) {
                pflicht.add(i);
            } else {
                frei.add(i);
            }
        }
        // Was nur UEBER DEN WAECHTER zu erreichen waere, ist kein Ziel: Nach
        // dem Waechter ist die Etage zu Ende. (Im Trockentest lag so ein
        // Schein-Fragezeichen hinter dem Waechter, und es gab gar keinen Plan.)
        if (waechter >= 0 && !pflicht.isEmpty()) {
            boolean[] da = new boolean[n];
            ArrayDeque<Integer> reihe = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                boolean start = etwasFertig ? knoten.get(i).fertig : knoten.get(i).klick != null;
                if (start && i != waechter) {
                    da[i] = true;
                    reihe.add(i);
                }
            }
            while (!reihe.isEmpty()) {
                int k = reihe.poll();
                for (int j = 0; j < n; j++) {
                    if (festNachbar[k][j] && j != waechter && !da[j]) {
                        da[j] = true;
                        reihe.add(j);
                    }
                }
            }
            for (int i = pflicht.size() - 1; i >= 0; i--) {
                int p = pflicht.get(i);
                if (!da[p]) {
                    pflicht.remove(i);
                    frei.add(p);
                }
            }
        }
        if (pflicht.isEmpty() && waechter < 0) {
            // Kein Ziel bekannt -- aber eben wurde etwas aufgedeckt, das noch
            // nicht zu erkennen ist: gleich noch einmal hinsehen.
            if (unklar && aufUnklaresWarten(jetzt)) {
                return new Plan(Art.WARTEN, null, 0, Sprache.t("a revealed question mark cannot be seen yet", "ein aufgedecktes Fragezeichen ist noch nicht zu erkennen"));
            }
            return null;
        }

        int besteGroesse = -1;
        int besterSchritt = -1;
        int besterRang = Integer.MAX_VALUE;
        // Der beste Schritt ist nur aus dem Gedaechtnis bekannt (Bauplan L1).
        boolean besterAusGedaechtnis = false;
        boolean schrittVerdeckt = false;
        int ohneMotiv = -1;
        for (int groesse = 0; groesse <= frei.size() && besteGroesse < 0; groesse++) {
            for (int maske = 0; maske < (1 << frei.size()); maske++) {
                if (Integer.bitCount(maske) != groesse) continue;
                boolean[] zuSpielen = new boolean[n];
                for (int b = 0; b < frei.size(); b++) {
                    if ((maske & (1 << b)) != 0) zuSpielen[frei.get(b)] = true;
                }
                for (int p : pflicht) zuSpielen[p] = true;

                // Von wo geht es los: von allem Fertigen -- oder, ganz am
                // Anfang, von dem einen offenen Stuetzpunkt.
                boolean[] erreicht = new boolean[n];
                ArrayDeque<Integer> schlange = new ArrayDeque<>();
                for (int i = 0; i < n; i++) {
                    boolean start = etwasFertig ? knoten.get(i).fertig
                                                : zuSpielen[i] && knoten.get(i).klick != null;
                    if (start) {
                        erreicht[i] = true;
                        schlange.add(i);
                    }
                }
                while (!schlange.isEmpty()) {
                    int k = schlange.poll();
                    for (int j = 0; j < n; j++) {
                        if (festNachbar[k][j] && zuSpielen[j] && !erreicht[j]) {
                            erreicht[j] = true;
                            schlange.add(j);
                        }
                    }
                }
                boolean geht = true;
                for (int i = 0; i < n && geht; i++) if (zuSpielen[i] && !erreicht[i]) geht = false;
                if (geht && waechter >= 0) {
                    boolean nah = false;
                    for (int j = 0; j < n; j++) nah |= festNachbar[waechter][j] && erreicht[j];
                    geht = nah;
                }
                if (!geht) continue;
                besteGroesse = groesse;

                // Der erste Schritt: neben etwas Fertigem (oder der Anfang).
                for (int i = 0; i < n; i++) {
                    if (!zuSpielen[i]) continue;
                    boolean dran = !etwasFertig;
                    for (int j = 0; j < n && !dran; j++) dran = festNachbar[i][j] && knoten.get(j).fertig;
                    if (!dran) continue;
                    // GEDAECHTNIS (Bauplan L1, 09.10.): Ist der Schritt gerade
                    // verdeckt, aber sein Motiv von einem frueheren Bild
                    // bekannt, gilt die gemerkte Stelle -- nur mit Schalter.
                    boolean ausGedaechtnis = knoten.get(i).klick == null
                            && ausGedaechtnisErlaubt(knoten.get(i), etwasFertig, unklar);
                    if (knoten.get(i).klick == null && !ausGedaechtnis) {
                        schrittVerdeckt = true;
                        Knoten k = knoten.get(i);
                        if (mitNichtKlickbaren && etwasFertig && !k.gesperrt && !k.frageJetzt
                                && k.ohneKlickSeit != 0 && jetzt - k.ohneKlickSeit > OHNE_MOTIV_KLICK_MS
                                && k.blindKlicks < OHNE_MOTIV_KLICKS_MAX) {
                            ohneMotiv = i;
                        }
                        continue;
                    }
                    // Was ohnehin gespielt werden muss, zuerst.
                    int rang = pflicht.contains(i) ? 0 : rang(i, festNachbar);
                    // Bei gleichem Rang geht ein SICHTBARER Schritt vor einem
                    // aus dem Gedaechtnis (ohne Gedaechtnis-Schritte wie bisher).
                    if (rang < besterRang || (rang == besterRang && besterAusGedaechtnis && !ausGedaechtnis)) {
                        besterRang = rang;
                        besterSchritt = i;
                        besterAusGedaechtnis = ausGedaechtnis;
                    }
                }
            }
        }
        if (besteGroesse < 0) {
            if (mitNichtKlickbaren) {
                Log.debug("Kathedrale", "Wegplanung: kein Weg auf der festen Karte (" + pflicht.size()
                        + " Bosse/Fragezeichen, Waechter " + (waechter >= 0 ? ort(waechter) : "unbekannt") + ").");
            }
            return null;
        }

        int noetig = besteGroesse + pflicht.size() + (waechter >= 0 ? 1 : 0);
        if (probelauf) {
            // Vorausplanung (Bauplan L2): nur rechnen, nichts protokollieren.
        } else if (planAmAnfang < 0) {
            planAmAnfang = noetig + zahlFertig();
            Log.info("Kathedrale", de.hustlebot.core.Sprache.t("Route planning: fixed map \"", "Wegplanung: feste Karte \"") + FESTE_NAMEN[fest] + de.hustlebot.core.Sprache.t("\". At least ", "\". Noch mindestens ")
                    + noetig + de.hustlebot.core.Sprache.t(" waypoints to the next floor (", " Stuetzpunkte bis zur naechsten Etage (") + besteGroesse + de.hustlebot.core.Sprache.t(" stepping stones, ", " Trittsteine, ")
                    + pflicht.size() + de.hustlebot.core.Sprache.t(" bosses/question marks", " Bosse/Fragezeichen") + (waechter >= 0 ? de.hustlebot.core.Sprache.t(", guardian", ", Waechter") : "") + ").");
        } else if (gespielt <= 1) {
            // Am Etagenanfang sind noch nicht alle Fragezeichen zu sehen (sie
            // blenden erst ein, oben liegt der Schriftzug): Fuer die Zahl im
            // Protokoll gilt der groesste Plan bis nach dem ersten Stuetzpunkt.
            planAmAnfang = Math.max(planAmAnfang, noetig + zahlFertig());
        }

        if (besterSchritt >= 0) {
            unklarSeit = 0;
            Knoten k = knoten.get(besterSchritt);
            String was = k.boss ? "Boss"
                    : k.warFrage ? de.hustlebot.core.Sprache.t("revealed question mark", "aufgedecktes Fragezeichen")
                    : besterRang == 1 ? de.hustlebot.core.Sprache.t("stepping stone, reveals a question mark", "Trittstein, deckt ein Fragezeichen auf")
                    : de.hustlebot.core.Sprache.t("stepping stone", "Trittstein");
            if (besterAusGedaechtnis) {
                planAusGedaechtnis = true;
                return new Plan(Art.SCHRITT, k.motiv, noetig,
                        was + de.hustlebot.core.Sprache.t(" (fixed map, " + noetig + " to go) (from memory: " + k.art + ")",
                                " (feste Karte, noch " + noetig + ") (aus dem Gedaechtnis: " + k.art + ")"));
            }
            return new Plan(Art.SCHRITT, k.klick, noetig,
                    was + de.hustlebot.core.Sprache.t(" (fixed map, " + noetig + " to go)", " (feste Karte, noch " + noetig + ")"));
        }
        if (pflicht.isEmpty() && besteGroesse == 0) {
            if (unklar && aufUnklaresWarten(jetzt)) {
                return new Plan(Art.WARTEN, null, noetig, Sprache.t("a revealed question mark cannot be seen yet", "ein aufgedecktes Fragezeichen ist noch nicht zu erkennen"));
            }
            return new Plan(Art.WAECHTER, null, noetig, "Etagenwaechter");
        }
        // DER LETZTE AUSWEG: Ohne diesen Stuetzpunkt geht es nicht weiter, er
        // MUSS offen sein (sein Nachbar ist fertig, die Karte steht fest) --
        // aber auch das gezielte Nachsehen erkennt seit anderthalb Sekunden
        // kein Motiv. Dann wird seine bekannte Stelle angeklickt. Das
        // geschieht nur auf der Etagenkarte (die hat der Aufrufer gerade
        // gesehen), hoechstens zweimal je Stuetzpunkt, und steht als Warnung
        // im Protokoll. Ohne das bliebe die Etage haengen.
        if (ohneMotiv >= 0) {
            Knoten k = knoten.get(ohneMotiv);
            k.blindKlicks++;
            Log.warn("Kathedrale", Sprache.t("Path planning: waypoint " + ort(ohneMotiv) + " must be open, " + "but cannot be recognized. Its known spot gets clicked (fixed map \"" + FESTE_NAMEN[fest] + "\", attempt " + k.blindKlicks + ").",
                    "Wegplanung: Der Stuetzpunkt " + ort(ohneMotiv) + " muss offen sein, "
                    + "ist aber nicht zu erkennen. Seine bekannte Stelle wird angeklickt (feste Karte \""
                    + FESTE_NAMEN[fest] + "\", Versuch " + k.blindKlicks + ")."));
            Matcher.Hit stelle = new Matcher.Hit("dc_stelle", k.x - 20, k.y - SYMBOL_UEBER_SOCKEL_PX - 20,
                    40, 40, 0.0);
            return new Plan(Art.SCHRITT, stelle, noetig,
                    de.hustlebot.core.Sprache.t("known spot, motif not recognized (fixed map, " + noetig + " to go)", "bekannte Stelle, Motiv nicht erkannt (feste Karte, noch " + noetig + ")"));
        }
        // Es gibt einen Weg, aber sein erster Schritt ist gerade nicht zu
        // sehen. Kurz warten -- der Aufrufer begrenzt die Zeit.
        if (schrittVerdeckt || etwasFertig) {
            return new Plan(Art.WARTEN, null, noetig, Sprache.t("the next step is covered right now", "der naechste Schritt ist gerade verdeckt"));
        }
        return null;
    }

    private Plan planen(boolean reif, boolean denkbar) {
        int n = knoten.size();
        if (n < KARTE_AB_KNOTEN || n > 24) return null;

        boolean[][] nachbar = netz(denkbar);
        // Das Spiel widerlegt eine Linie: Ein Nachbar ist fertig, der
        // Stuetzpunkt aber im JETZIGEN Bild weiter zu. Das gilt auch fuer
        // die nur vermuteten Linien ("haengt am naechsten").
        if (reif) {
            for (int runde = 0; runde < 4; runde++) {
                boolean geaendert = false;
                for (int i = 0; i < n; i++) {
                    Knoten k = knoten.get(i);
                    if (k.fertig || !k.zuJetzt()) continue;
                    for (int j = 0; j < n; j++) {
                        int s = schluessel(i, j);
                        if (j == i || !knoten.get(j).fertig || !nachbar[i][j] || bewiesen.contains(s)) continue;
                        if (widerlegt.add(s)) {
                            geaendert = true;
                            Log.debug("Kathedrale", "Wegenetz: keine Linie " + ort(i) + " - " + ort(j)
                                    + " (Nachbar fertig, Stuetzpunkt weiter zu).");
                        }
                    }
                }
                if (!geaendert) break;
                nachbar = netz(denkbar);
            }
        }

        List<Integer> bosse = new ArrayList<>();
        List<Integer> blaetter = new ArrayList<>();   // Fragezeichen und Waechter: Ziel, aber kein Durchgang
        List<Integer> frei = new ArrayList<>();       // gewoehnliche Stuetzpunkte: moegliche Trittsteine
        int waechter = -1;
        boolean etwasFertig = false;
        for (int i = 0; i < n; i++) {
            Knoten k = knoten.get(i);
            if (k.fertig) {
                etwasFertig = true;
            } else if (k.waechter) {
                waechter = i;
                blaetter.add(i);
            } else if (k.frage) {
                blaetter.add(i);
            } else if (k.boss) {
                bosse.add(i);
            } else {
                boolean hatLinie = false;
                for (int j = 0; j < n; j++) hatLinie |= nachbar[i][j];
                if (hatLinie || k.klick != null) frei.add(i);
            }
        }
        if (blaetter.isEmpty() && bosse.isEmpty()) return null;
        if (frei.size() > 14) return null;

        boolean nurWaechter = bosse.isEmpty() && blaetter.size() == 1 && waechter >= 0;

        // Alle Mengen von Trittsteinen, von der kleinsten an.
        int besteGroesse = -1;
        int besterSchritt = -1;
        int besterRang = Integer.MAX_VALUE;
        boolean ohneSchritt = false;
        for (int groesse = 0; groesse <= frei.size() && besteGroesse < 0; groesse++) {
            for (int maske = 0; maske < (1 << frei.size()); maske++) {
                if (Integer.bitCount(maske) != groesse) continue;
                boolean[] drin = new boolean[n];      // fertig, Trittsteine, Bosse: begehbar
                boolean[] zuSpielen = new boolean[n];
                for (int i = 0; i < n; i++) drin[i] = knoten.get(i).fertig;
                for (int b = 0; b < frei.size(); b++) {
                    if ((maske & (1 << b)) != 0) drin[frei.get(b)] = zuSpielen[frei.get(b)] = true;
                }
                for (int b : bosse) drin[b] = zuSpielen[b] = true;

                boolean[] erreicht = new boolean[n];
                ArrayDeque<Integer> schlange = new ArrayDeque<>();
                for (int i = 0; i < n; i++) {
                    boolean start = etwasFertig ? knoten.get(i).fertig
                                                : zuSpielen[i] && knoten.get(i).klick != null;
                    if (start) {
                        erreicht[i] = true;
                        schlange.add(i);
                    }
                }
                while (!schlange.isEmpty()) {
                    int k = schlange.poll();
                    for (int j = 0; j < n; j++) {
                        if (nachbar[k][j] && drin[j] && !erreicht[j]) {
                            erreicht[j] = true;
                            schlange.add(j);
                        }
                    }
                }
                boolean geht = true;
                for (int i = 0; i < n && geht; i++) if (zuSpielen[i] && !erreicht[i]) geht = false;
                for (int b : blaetter) {
                    if (!geht) break;
                    boolean nah = false;
                    for (int j = 0; j < n; j++) nah |= nachbar[b][j] && erreicht[j];
                    if (!nah) geht = false;
                }
                if (!geht) continue;

                // Der erste Schritt muss jetzt anklickbar sein.
                boolean irgendeiner = false;
                for (int i = 0; i < n; i++) {
                    if (!zuSpielen[i] || knoten.get(i).klick == null) continue;
                    irgendeiner = true;
                    int rang = rang(i, nachbar);
                    if (besteGroesse < 0 || rang < besterRang) {
                        besterRang = rang;
                        besterSchritt = i;
                    }
                }
                boolean nichtsZuSpielen = groesse == 0 && bosse.isEmpty();
                if (irgendeiner || nichtsZuSpielen) {
                    besteGroesse = groesse;
                    if (nichtsZuSpielen) ohneSchritt = true;
                }
            }
        }
        if (besteGroesse < 0) {
            Log.debug("Kathedrale", "Wegplanung: kein Weg im Netz (" + n + " Stuetzpunkte, "
                    + linienZahl() + " Linien, " + blaetter.size() + " Fragezeichen/Waechter, "
                    + bosse.size() + " Bosse).");
            return null;
        }

        int noetig = besteGroesse + bosse.size() + blaetter.size();
        if (planAmAnfang < 0) {
            planAmAnfang = noetig + zahlFertig();
            Log.info("Kathedrale", de.hustlebot.core.Sprache.t("Route planning: ", "Wegplanung: ") + n + de.hustlebot.core.Sprache.t(" waypoints, ", " Stuetzpunkte, ") + linienZahl() + de.hustlebot.core.Sprache.t(" lines (", " Linien (")
                    + bewiesen.size() + de.hustlebot.core.Sprache.t(" proven, ", " bewiesen, ") + gelernt.size() + de.hustlebot.core.Sprache.t(" learned). At least ", " gelernt). Noch mindestens ")
                    + noetig + de.hustlebot.core.Sprache.t(" waypoints to the next floor (", " Stuetzpunkte bis zur naechsten Etage (") + besteGroesse + de.hustlebot.core.Sprache.t(" stepping stones, ", " Trittsteine, ")
                    + bosse.size() + de.hustlebot.core.Sprache.t(" bosses, ", " Bosse, ") + blaetter.size() + de.hustlebot.core.Sprache.t(" question marks/guardian).", " Fragezeichen/Waechter)."));
        }

        if (ohneSchritt) {
            // Nichts mehr zu spielen ausser dem, was neben Fertigem liegt.
            if (nurWaechter) return new Plan(Art.WAECHTER, null, noetig, "Etagenwaechter");
            // Ein Fragezeichen neben einem fertigen Stuetzpunkt muesste
            // aufgedeckt sein, ist im jetzigen Bild aber nicht zu sehen
            // (verdeckt). Kurz warten -- der Aufrufer begrenzt die Zeit.
            return new Plan(Art.WARTEN, null, noetig, Sprache.t("question mark is being revealed", "Fragezeichen wird aufgedeckt"));
        }
        if (besterSchritt < 0) return null;

        Knoten k = knoten.get(besterSchritt);
        String was = k.boss ? "Boss"
                : besterRang == 1 ? de.hustlebot.core.Sprache.t("stepping stone, reveals a question mark", "Trittstein, deckt ein Fragezeichen auf")
                : de.hustlebot.core.Sprache.t("stepping stone", "Trittstein");
        return new Plan(Art.SCHRITT, k.klick, noetig,
                was + de.hustlebot.core.Sprache.t(" (shortest path", " (kuerzester Weg") + (denkbar ? de.hustlebot.core.Sprache.t(" via possible lines", " ueber denkbare Linien") : "")
                + de.hustlebot.core.Sprache.t(", " + noetig + " to go)", ", noch " + noetig + ")"));
    }

    /** Welcher Schritt zuerst: Bosse (0), dann was ein Fragezeichen aufdeckt (1), dann der Rest (2). */
    private int rang(int i, boolean[][] nachbar) {
        if (knoten.get(i).boss) return 0;
        for (int j = 0; j < knoten.size(); j++) {
            if (nachbar[i][j] && knoten.get(j).frage && !knoten.get(j).fertig) return 1;
        }
        return 2;
    }

    private int zahlFertig() {
        int z = 0;
        for (Knoten k : knoten) if (k.fertig) z++;
        return z;
    }

    private String ort(int i) {
        return "[" + knoten.get(i).x + "," + knoten.get(i).y + "]";
    }

    /** Kurzfassung fuers Protokoll. */
    String stand() {
        int fragen = 0, bosse = 0;
        for (Knoten k : knoten) {
            if (k.fertig) continue;
            if (k.frage) fragen++;
            if (k.boss) bosse++;
        }
        return knoten.size() + " Stuetzpunkte, " + linienZahl() + " Linien (" + bewiesen.size()
                + " bewiesen, " + gelernt.size() + " gelernt), " + zahlFertig() + " fertig, "
                + fragen + " Fragezeichen, " + bosse + " Bosse";
    }

    // ------------------------------------------------------------------
    //  Gelernte Karten
    // ------------------------------------------------------------------

    /** Wo die gelernten Netze liegen (im Bot-Ordner). Loeschen = neu lernen. */
    static final Path DATEI = Path.of("etagen-netze.txt");

    /** Eine gelernte Karte: Stellen der Stuetzpunkte, ihre Linien und die sicheren Nicht-Linien. */
    private static final class Karte {
        final List<int[]> orte = new ArrayList<>();
        final Set<Integer> linien = new HashSet<>();
        final Set<Integer> bewiesen = new HashSet<>();
        final Set<Integer> keine = new HashSet<>();
    }

    private static List<Karte> karten;

    private static synchronized List<Karte> karten() {
        if (karten != null) return karten;
        karten = new ArrayList<>();
        try {
            if (!Files.exists(DATEI)) return karten;
            Karte k = null;
            for (String zeile : de.hustlebot.core.Dateien.zeilen(DATEI)) {
                String[] t = zeile.trim().split("\\s+");
                if (t[0].equals("karte")) {
                    k = new Karte();
                    karten.add(k);
                } else if (k == null || t.length != 3) {
                    continue;
                } else if (t[0].equals("k")) {
                    k.orte.add(new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[2])});
                } else if (t[0].equals("l")) {
                    k.linien.add(schluessel(Integer.parseInt(t[1]), Integer.parseInt(t[2])));
                } else if (t[0].equals("b")) {
                    k.bewiesen.add(schluessel(Integer.parseInt(t[1]), Integer.parseInt(t[2])));
                } else if (t[0].equals("n")) {
                    k.keine.add(schluessel(Integer.parseInt(t[1]), Integer.parseInt(t[2])));
                }
            }
            Log.info("Kathedrale", Sprache.t("Path network: " + karten.size() + " learned maps loaded.",
                    "Wegenetz: " + karten.size() + " gelernte Karten geladen."));
        } catch (Exception e) {
            Log.warn("Kathedrale", Sprache.t("etagen-netze.txt could not be read (" + e.getMessage() + "). The maps will be learned again.",
                    "etagen-netze.txt liess sich nicht lesen (" + e.getMessage()
                    + "). Die Karten werden neu gelernt."));
            karten = new ArrayList<>();
        }
        return karten;
    }

    private static synchronized void kartenSpeichern() {
        StringBuilder b = new StringBuilder("# Gelernte Wegenetze der Kathedrale (Etagennetz). "
                + "Loeschen = neu lernen.\n# karte = neue Karte, k x y = Stuetzpunkt (gezaehlt ab 0), "
                + "b a b = Linie vom Spiel bewiesen,\n# l a b = Linie gesehen, n a b = sicher keine Linie.\n");
        for (Karte k : karten()) {
            b.append("karte\n");
            for (int[] o : k.orte) b.append("k ").append(o[0]).append(' ').append(o[1]).append('\n');
            zeilen(b, "b", k.bewiesen);
            zeilen(b, "l", k.linien);
        }
        try {
            Files.writeString(DATEI, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Kathedrale", "Wegenetze nicht gespeichert: " + e.getMessage());
        }
    }

    private static void zeilen(StringBuilder b, String art, Set<Integer> menge) {
        List<Integer> sortiert = new ArrayList<>(menge);
        java.util.Collections.sort(sortiert);
        for (int l : sortiert) {
            b.append(art).append(' ').append(l / 1000).append(' ').append(l % 1000).append('\n');
        }
    }

    /** Die gelernte Karte, die zu den jetzigen Sockeln passt, oder null. */
    private Karte passendeKarte() {
        List<Knoten> mitSockel = new ArrayList<>();
        for (Knoten k : knoten) if (k.sockel) mitSockel.add(k);
        if (mitSockel.size() < KARTE_AB_KNOTEN) return null;
        Karte beste = null;
        int besteTreffer = 0;
        for (Karte karte : karten()) {
            int treffer = 0;
            for (Knoten k : mitSockel) {
                for (int[] o : karte.orte) {
                    if (Math.hypot(o[0] - k.x, o[1] - k.y) <= GLEICH_PX) {
                        treffer++;
                        break;
                    }
                }
            }
            if (treffer > besteTreffer) {
                besteTreffer = treffer;
                beste = karte;
            }
        }
        return besteTreffer >= 0.8 * mitSockel.size() && besteTreffer >= KARTE_AB_KNOTEN ? beste : null;
    }

    /** Einmal je Etage: das Gelernte zu dieser Karte uebernehmen. */
    private void gelerntesUebernehmen() {
        if (fest >= 0 || gelerntesGesucht || zahlSockel() < KARTE_AB_KNOTEN) return;
        Karte karte = passendeKarte();
        gelerntesGesucht = true;
        karteNeu = karte == null;
        if (karte == null) {
            Log.info("Kathedrale", Sprache.t("Path network: this map is new (" + zahlSockel() + " waypoints). It will be learned on this floor.",
                    "Wegenetz: Diese Karte ist neu (" + zahlSockel()
                    + " Stuetzpunkte). Sie wird auf dieser Etage gelernt."));
            return;
        }
        // Gelernte Stuetzpunkte, die das Bild (noch) nicht zeigt, kommen dazu.
        int[] zu = new int[karte.orte.size()];
        for (int a = 0; a < karte.orte.size(); a++) {
            int[] o = karte.orte.get(a);
            int i = naechster(o[0], o[1], GLEICH_PX);
            if (i < 0) {
                knoten.add(new Knoten(o[0], o[1], true));
                i = knoten.size() - 1;
            }
            zu[a] = i;
        }
        uebertragen(karte.linien, zu, gelernt);
        uebertragen(karte.bewiesen, zu, bewiesen);
        aufgenommen = true;       // die Stuetzpunkte dieser Karte stehen fest
        Log.info("Kathedrale", Sprache.t("Path network: known map with " + karte.orte.size() + " waypoints, " + bewiesen.size() + " proven and " + gelernt.size() + " seen lines.",
                "Wegenetz: bekannte Karte mit " + karte.orte.size() + " Stuetzpunkten, "
                + bewiesen.size() + " bewiesene und " + gelernt.size() + " gesehene Linien."));
    }

    private static void uebertragen(Set<Integer> von, int[] zu, Set<Integer> nach) {
        for (int l : von) {
            int a = l / 1000, b = l % 1000;
            if (a < zu.length && b < zu.length && zu[a] != zu[b]) nach.add(schluessel(zu[a], zu[b]));
        }
    }

    /** Am Ende der Etage: merken, was sie gezeigt hat. */
    private void lernen() {
        if (fest >= 0) return;    // eine feste Karte: nichts zu lernen
        List<Integer> mitSockel = new ArrayList<>();
        for (int i = 0; i < knoten.size(); i++) if (knoten.get(i).sockel) mitSockel.add(i);
        if (mitSockel.size() < KARTE_AB_KNOTEN || bilder < 3) return;

        Karte karte = passendeKarte();
        boolean neu = karte == null;
        if (neu) {
            karte = new Karte();
            karten().add(karte);
        }
        int[] zu = new int[knoten.size()];
        java.util.Arrays.fill(zu, -1);
        boolean geaendert = neu;
        for (int i : mitSockel) {
            Knoten k = knoten.get(i);
            int a = -1;
            for (int t = 0; t < karte.orte.size(); t++) {
                if (Math.hypot(karte.orte.get(t)[0] - k.x, karte.orte.get(t)[1] - k.y) <= GLEICH_PX) {
                    a = t;
                    break;
                }
            }
            if (a < 0) {
                // Nur Stuetzpunkte mit einem Zustand oder einer Linie -- kein
                // Feuerbecken, das zufaellig die Farbe eines Sockels hat.
                boolean echt = k.fertig || k.frage || k.boss || k.waechter || k.zuletztZu;
                for (int j = 0; j < knoten.size() && !echt; j++) {
                    if (j != i && verbunden(i, j)) echt = true;
                }
                if (!echt) continue;
                karte.orte.add(new int[]{k.x, k.y});
                a = karte.orte.size() - 1;
                geaendert = true;
            }
            zu[i] = a;
        }
        int neueLinien = 0;
        for (var e : gesehen.entrySet()) {
            if (e.getValue() < LERNEN_AB || widerlegt.contains(e.getKey())) continue;
            if (eintragen(e.getKey(), zu, karte.linien)) neueLinien++;
        }
        for (int s : bewiesen) if (eintragen(s, zu, karte.bewiesen)) neueLinien++;
        // Was das Spiel auf dieser Etage widerlegt hat, fliegt aus den nur
        // GESEHENEN Linien. Gemerkt wird das Widerlegte selbst nicht: Es
        // ergibt sich auf jeder Etage neu, und ein Irrtum bliebe sonst fuer
        // immer stehen.
        int gestrichen = 0;
        for (int s : widerlegt) {
            if (bewiesen.contains(s)) continue;
            int i = s / 1000, j = s % 1000;
            if (i >= zu.length || j >= zu.length || zu[i] < 0 || zu[j] < 0) continue;
            int t = schluessel(zu[i], zu[j]);
            if (!karte.bewiesen.contains(t) && karte.linien.remove(t)) gestrichen++;
        }
        if (neueLinien > 0 || gestrichen > 0 || geaendert) {
            kartenSpeichern();
            Log.info("Kathedrale", Sprache.t("Path network learned: " + (neu ? "new map, " : "") + karte.orte.size() + " waypoints, " + karte.bewiesen.size() + " proven and " + karte.linien.size() + " seen lines" + (gestrichen > 0 ? " (" + gestrichen + " removed)" : "") + ". Known maps: " + karten().size() + ".",
                    "Wegenetz gelernt: " + (neu ? "neue Karte, " : "") + karte.orte.size()
                    + " Stuetzpunkte, " + karte.bewiesen.size() + " bewiesene und " + karte.linien.size()
                    + " gesehene Linien" + (gestrichen > 0 ? " (" + gestrichen + " gestrichen)" : "")
                    + ". Bekannte Karten: " + karten().size() + "."));
        }
    }

    private static boolean eintragen(int s, int[] zu, Set<Integer> nach) {
        int i = s / 1000, j = s % 1000;
        if (i >= zu.length || j >= zu.length || zu[i] < 0 || zu[j] < 0 || zu[i] == zu[j]) return false;
        return nach.add(schluessel(zu[i], zu[j]));
    }
}
