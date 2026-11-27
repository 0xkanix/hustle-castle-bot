package de.hustlebot.core;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * PaddleOCR (Autor 25.10.: "am besten ueberall die komplette Texterkennung ersetzen"). Nur der Erkennungsteil ("rec")
 * der PP-OCR-Modelle, als ONNX ueber ONNX Runtime -- ohne Python, ohne Internet. Gemessen an 342 Arena-Namen: 5-10 ms je
 * Name und Modell (Windows 12-22 ms); las "Martins", "Denis", "FLIMSY", "King", "元素神", wo Windows falsch lag oder leer blieb.
 *
 * Jede Schrift hat ihr Modell (lateinisch, kyrillisch, chinesisch/japanische Kanji, koreanisch, arabisch). Welches gilt,
 * entscheidet die Schrift im Ergebnis: das kyrillische Modell erfindet bei lateinischen Namen sonst "cYalowb".
 *
 * Die Modelle liegen im Bot-Ordner unter modelle\paddleocr\ (oder im Paket unter /paddleocr/). Fehlen sie oder ONNX
 * Runtime, ist verfuegbar() false und alles laeuft wie bisher ueber die Windows-Texterkennung.
 */
public final class PaddleLeser {

    private PaddleLeser() { }

    public enum Schrift {
        LATEIN("latin"), ENGLISCH("english"), KYRILLISCH("eslav"), CHINESISCH("chinese-v4"), KOREANISCH("korean"), ARABISCH("arabic"),
        /** PP-OCRv5 server: Chinesisch + Japanisch, genauer, aber 20-35 ms je Name -- nur fuer Namen mit chinesischen/japanischen Zeichen. */
        CHINESISCH_GROSS("chinese-v5"),
        /**
         * Latein, nachtrainiert an Arena-Namen (Trainings-Helfer 29.10., Runde 2): echte Namen 94,3 -> 98,1 %, Akzente
         * 52 -> 88,5 %, Zierschrift (kuenstlich) 23,5 -> 85 %. NUR fuer Spielernamen -- Zahlen und Knopftexte liest weiter
         * das Original (dort ist das neue Modell nicht gemessen).
         */
        LATEIN_NAMEN("latin-namen"),
        /**
         * Kyrillisch (+ Latein), nachtrainiert an Arena-Namen (Trainings-Helfer 29.10., eslav Runde 1). Gemessen im
         * Bot-Code am Testsatz (1958 Bilder) gegen das vorige PP-OCRv5-Modell (cyrillic-v5): kyrillisch kuenstlich
         * 85 -> 97,5 %, echt 91,4 -> 94,3 %, Sonderzeichen 78,5 -> 83 %, Latein gleich. Nur fuer Spielernamen.
         */
        KYRILLISCH_NAMEN("eslav-namen"),
        /**
         * Arabisch, nachtrainiert an kuenstlichen arabischen Namen (Trainings-Helfer, arabic Runde 2 = arabic_r2, 30.10.).
         * Nur fuer Spielernamen; Autor 29.10. nachts: arabische Namen sollen mindestens 90 % erreichen.
         * Eigener Ordnername ("-r2"): r1 und r2 sind gleich gross, ausDemPaket haette sonst die schon ausgepackte alte
         * Datei in %TEMP% behalten (es vergleicht nur die Groesse) und nie das neue Modell geladen.
         */
        ARABISCH_NAMEN("arabic-namen-r2");

        final String ordner;

        Schrift(String ordner) {
            this.ordner = ordner;
        }
    }

    /** Eine gelesene Zeile: Text, mittlere Sicherheit der Zeichen (0-1), mit welchem Modell. */
    public record Lesung(String text, double sicher, Schrift schrift) {
        public boolean leer() {
            return text == null || text.isBlank();
        }
    }

    private record Modell(OrtSession sitzung, List<String> zeichen, int hoehe) { }

    /** Schalter paddleocr.txt ("aus" = wie bisher nur Windows). Ohne Datei an. */
    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("paddleocr.txt"), "PaddleOCR", true);

    public static boolean an() {
        return SCHALTER.lesen();
    }

    private static final Map<Schrift, Modell> MODELLE = new EnumMap<>(Schrift.class);
    /** Modelle, die fehlen oder nicht laden -- wird nicht bei jedem Lesen neu versucht (wie frueher: einmal je Start). */
    private static final java.util.Set<Schrift> UNBRAUCHBAR = java.util.EnumSet.noneOf(Schrift.class);
    private static OrtEnvironment env;
    private static Boolean da;   // null = noch nicht versucht

    // ------------------------------------------------------------------
    //  Sparsam (Autor 20.11.: Bot laeuft kuenftig in einer VM neben Minecraft)
    // ------------------------------------------------------------------
    //
    // Gemessen 20.11. (PaddleLeserSparsamTest): ALLE zehn Modelle beim ersten Lesen zu laden kostete 1,6 s und
    // +357 MB Privatspeicher -- auch wenn der Bot danach nur Latein liest oder (Kathedrale) gar nichts mehr. Jetzt:
    //   - ein Modell wird erst geladen, wenn es zum ersten Mal gebraucht wird (Latein gleich in verfuegbar());
    //   - nach LEERLAUF_MS ohne jedes Lesen werden alle Modelle wieder freigegeben (Leerlauf), beim naechsten Lesen
    //     laedt das gebrauchte Modell neu (~0,1 s);
    //   - dieselbe Zeile (gleiche Bildpunkte, gleiches Modell) wird nicht zweimal gerechnet (GEDAECHTNIS).
    // Gelesen wird genau wie vorher: gleiche Modelle, gleiche Reihenfolge, gleiche Rechnung.

    /** Beim Rechnen Lesesperre, beim Freigeben Schreibsperre -- nie ein Modell schliessen, das gerade rechnet. */
    private static final java.util.concurrent.locks.ReentrantReadWriteLock NUTZUNG = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private static volatile long zuletztGenutzt = System.currentTimeMillis();

    /** So lange ohne Lesen, dann werden die Modelle freigegeben. */
    static final long LEERLAUF_MS = 10 * 60_000L;

    /** Ist PaddleOCR einsatzbereit (mindestens das lateinische Modell)? Laedt beim ersten Aufruf das lateinische Modell. */
    public static synchronized boolean verfuegbar() {
        if (da != null) return da;
        try {
            Onnxnativ.vorbereiten();
            env = OrtEnvironment.getEnvironment();
            da = modell(Schrift.LATEIN) != null;
            Log.info("Text", Sprache.t("PaddleOCR: " + (da ? "ready, models load when first needed (" + Arrays.toString(Schrift.values()) + ")" : "not available -- Windows text recognition as before") + ".",
                    "PaddleOCR: " + (da ? "bereit, Modelle laden beim ersten Gebrauch (" + Arrays.toString(Schrift.values()) + ")" : "nicht da -- Windows-Texterkennung wie bisher") + "."));
            if (da) Leerlauf.beobachten();
        } catch (Throwable t) {
            da = false;
            Log.info("Text", "PaddleOCR nicht nutzbar: " + t);
            Onnxnativ.laufzeitMelden(t);
        }
        return da;
    }

    /** Das Modell dieser Schrift, beim ersten Gebrauch (oder nach dem Freigeben) geladen; null = fehlt/laedt nicht. */
    private static synchronized Modell modell(Schrift s) {
        Modell m = MODELLE.get(s);
        if (m != null || env == null || UNBRAUCHBAR.contains(s)) return m;
        long t0 = System.nanoTime();
        try {
            // 28.10.: je Modell abgesichert -- ein Modell, das nicht laedt, nimmt den anderen nicht alles weg
            m = laden(s);
        } catch (Throwable t) {
            Log.info("Text", "PaddleOCR-Modell " + s + " nicht nutzbar: " + t);
            m = null;
        }
        if (m == null) {
            UNBRAUCHBAR.add(s);
            return null;
        }
        MODELLE.put(s, m);
        LADUNGEN.incrementAndGet();
        Log.debug("Text", "PaddleOCR-Modell " + s + " geladen (" + (System.nanoTime() - t0) / 1_000_000 + " ms).");
        return m;
    }

    /** Gibt es das Modell (laedt es bei Bedarf)? Ersetzt das fruehere "ist geladen" -- frueher war immer alles geladen. */
    static boolean hat(Schrift s) {
        return modell(s) != null;
    }

    /** Welche Modelle gerade im Speicher liegen (fuer Bericht und Test). */
    static synchronized java.util.Set<Schrift> geladen() {
        return java.util.EnumSet.copyOf(MODELLE.isEmpty() ? java.util.EnumSet.noneOf(Schrift.class) : MODELLE.keySet());
    }

    /**
     * Alle Modelle schliessen, wenn seit {@code nachMs} nichts gelesen wurde. Rechnet gerade jemand, bleibt alles (der
     * naechste Blick des Leerlauf-Fadens kommt bald). true = freigegeben.
     */
    static boolean freigebenWennUnbenutzt(long jetzt, long nachMs) {
        if (jetzt - zuletztGenutzt < nachMs) return false;
        if (!NUTZUNG.writeLock().tryLock()) return false;
        try {
            synchronized (PaddleLeser.class) {
                if (MODELLE.isEmpty()) return false;
                java.util.Set<Schrift> welche = java.util.EnumSet.copyOf(MODELLE.keySet());
                for (Modell m : MODELLE.values()) {
                    try {
                        m.sitzung().close();
                    } catch (Exception e) {
                        Log.debug("Text", "PaddleOCR-Modell nicht sauber geschlossen: " + e.getMessage());
                    }
                }
                MODELLE.clear();
                GEDAECHTNIS.clear();
                Log.info("Text", Sprache.t("Text recognition unused for " + (jetzt - zuletztGenutzt) / 60_000 + " min -- models "
                        + welche + " released (memory free; they load again when needed).",
                        "Texterkennung " + (jetzt - zuletztGenutzt) / 60_000 + " min nicht gebraucht -- Modelle " + welche
                        + " freigegeben (Speicher frei; sie laden bei Bedarf neu)."));
                return true;
            }
        } finally {
            NUTZUNG.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    //  Gedaechtnis: dieselbe Zeile nicht zweimal rechnen
    // ------------------------------------------------------------------

    /** Schluessel: Modell, Groesse und 64-Bit-Fingerabdruck der Bildpunkte. */
    private record Schluessel(Schrift schrift, int breite, int hoehe, long abdruck) { }

    /** So viele Lesungen werden gemerkt (je ein kurzer Text -- wenige KB zusammen). */
    static final int GEDAECHTNIS_GROESSE = 512;

    private static final Map<Schluessel, Lesung> GEDAECHTNIS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Schluessel, Lesung> e) {
                    return size() > GEDAECHTNIS_GROESSE;
                }
            });

    /** FNV-1a ueber alle Bildpunkte (ohne Alphakanal). Kosten: ~0,05 ms fuer eine Namenszeile. */
    static long abdruck(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = b.getRGB(0, 0, w, h, null, 0, w);
        long x = 0xcbf29ce484222325L;
        for (int p : px) {
            x ^= (p & 0xFFFFFF);
            x *= 0x100000001b3L;
        }
        return x;
    }

    // Zaehler fuer den Bericht nach jedem Durchlauf (bericht()).
    private static final java.util.concurrent.atomic.AtomicLong GERECHNET = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GEMERKT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NANOS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LADUNGEN = new java.util.concurrent.atomic.AtomicLong();

    /**
     * "Text recognition: 40 lines computed (812 ms), 12 from memory, models in memory [LATEIN]" -- und zuruecksetzen.
     * Leer, wenn seit dem letzten Bericht nichts gelesen wurde.
     */
    public static String bericht() {
        long g = GERECHNET.getAndSet(0), m = GEMERKT.getAndSet(0), ms = NANOS.getAndSet(0) / 1_000_000, l = LADUNGEN.getAndSet(0);
        if (g == 0 && m == 0 && l == 0) return "";
        return Sprache.t("Text recognition: " + g + " lines computed (" + ms + " ms), " + m + " from memory, " + l
                        + " model loads, in memory " + geladen(),
                "Texterkennung: " + g + " Zeilen gerechnet (" + ms + " ms), " + m + " aus dem Gedaechtnis, " + l
                        + " Modell-Ladungen, im Speicher " + geladen());
    }

    private static Modell laden(Schrift s) throws Exception {
        String woerter = null;
        Path ordner = Ordner.bei("modelle").resolve("paddleocr").resolve(s.ordner);
        Path rec = ordner.resolve("rec.onnx");
        if (!Files.exists(rec)) rec = ordner.resolve("ch_PP-OCRv4_rec_infer.onnx");
        if (Files.exists(rec)) {
            Path dict = ordner.resolve("dict.txt");
            if (Files.exists(dict)) woerter = Files.readString(dict, StandardCharsets.UTF_8);
        } else {
            rec = ausDemPaket(s);
            byte[] d = ressource("/paddleocr/" + s.ordner + "/" + "dict.txt");   // Woerterliste nur in der Paket-Fassung (nicht im Archiv)
            if (d != null) woerter = new String(d, StandardCharsets.UTF_8);
        }
        if (rec == null) return null;
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(2);
        // Gemessen 20.11.: ohne den Speicher-Vorrat von ONNX (CPU-Arena) 76 MB weniger, aber Lesen 45 % langsamer -- bleibt an.
        // 28.10.: das Modell laedt ONNX Runtime selbst von der Platte. Vorher kam jedes Modell erst ganz in den
        // Java-Speicher (readAllBytes) -- 135 MB fuer alle sieben, bei -Xmx192m: "OutOfMemoryError: Java heap space"
        // bei JEDEM Start seit dem 26.10., und alle Namen liefen wieder ueber die Windows-Texterkennung.
        OrtSession sitzung = env.createSession(rec.toString(), o);
        List<String> zeichen = new ArrayList<>();
        if (woerter != null) {
            for (String z : woerter.split("\n", -1)) zeichen.add(z.replace("\r", ""));
            if (!zeichen.isEmpty() && zeichen.get(zeichen.size() - 1).isEmpty()) zeichen.remove(zeichen.size() - 1);
        } else {
            // Das chinesische Modell (RapidOCR) traegt seine Zeichen in sich.
            zeichen.addAll(Arrays.asList(sitzung.getMetadata().getCustomMetadata().getOrDefault("character", "").split("\n")));
        }
        long[] form = ((TensorInfo) sitzung.getInputInfo().values().iterator().next().getInfo()).getShape();
        int hoehe = form.length == 4 && form[2] > 0 ? (int) form[2] : 48;
        return new Modell(sitzung, zeichen, hoehe);
    }

    /**
     * Das Modell aus dem Paket einmal auf die Platte legen (gestreamt -- nie ganz im Java-Speicher) und den Pfad liefern;
     * null = nicht im Paket. Liegt es schon in gleicher Groesse da, wird nichts kopiert.
     */
    static Path ausDemPaket(Schrift s) throws java.io.IOException {
        return ausDemPaket(s.ordner, "rec.onnx");
    }

    static Path ausDemPaket(String unterordner, String datei) throws java.io.IOException {
        java.net.URL u = PaddleLeser.class.getResource("/paddleocr/" + unterordner + "/" + datei);
        if (u == null) return null;
        java.net.URLConnection c = u.openConnection();
        c.setUseCaches(false);   // sonst haelt die Jar-Datei offen und das Update kann sie nicht ersetzen
        long groesse = c.getContentLengthLong();
        Path ziel = Path.of(System.getProperty("java.io.tmpdir"), "hustlebot-paddleocr", unterordner, datei);
        if (Files.exists(ziel) && groesse > 0 && Files.size(ziel) == groesse) {
            c.getInputStream().close();
            return ziel;
        }
        Files.createDirectories(ziel.getParent());
        // eigener Zwischenname je Programm: Bot und Aufnahme-Programm koennen gleichzeitig starten
        Path teil = ziel.resolveSibling(datei + "." + ProcessHandle.current().pid() + ".teil");
        try (InputStream in = c.getInputStream()) {
            Files.copy(in, teil, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(teil, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            // das andere Programm war schneller und hat die Datei schon offen -- dann gilt seine
            Files.deleteIfExists(teil);
            if (!Files.exists(ziel) || Files.size(ziel) != groesse) throw e;
        }
        return ziel;
    }

    private static byte[] ressource(String pfad) {
        try (InputStream in = PaddleLeser.class.getResourceAsStream(pfad)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Eine Textzeile mit genau diesem Modell lesen (null = Modell fehlt). */
    public static Lesung lesen(BufferedImage zeile, Schrift s) {
        if (zeile == null || !verfuegbar()) return null;
        zuletztGenutzt = System.currentTimeMillis();
        Schluessel k = new Schluessel(s, zeile.getWidth(), zeile.getHeight(), abdruck(zeile));
        Lesung schonGelesen = GEDAECHTNIS.get(k);
        if (schonGelesen != null) {
            GEMERKT.incrementAndGet();
            return schonGelesen;
        }
        NUTZUNG.readLock().lock();
        try {
            Modell m = modell(s);
            if (m == null) return null;
            long t0 = System.nanoTime();
            Lesung l = dekodieren(m, rechnen(m, zeile), s);
            NANOS.addAndGet(System.nanoTime() - t0);
            GERECHNET.incrementAndGet();
            // Messung 29.10. (Trainings-Helfer): arabische Namen kamen VERKEHRT HERUM heraus -- PaddleOCR dreht die Lesung
            // des arabischen Modells selbst um (CTCLabelDecode.pred_reverse), wir taten es nicht: 0 % richtig, mit Umkehr 52 %.
            if (l != null && (s == Schrift.ARABISCH || (s == Schrift.ARABISCH_NAMEN && ARABISCH_NAMEN_UMDREHEN)) && !l.leer()) {
                l = new Lesung(arabischUmdrehen(l.text()), l.sicher(), l.schrift());
            }
            if (l != null) GEDAECHTNIS.put(k, l);
            return l;
        } catch (Exception e) {
            Log.debug("Text", "PaddleOCR (" + s + "): " + e.getMessage());
            return null;
        } finally {
            zuletztGenutzt = System.currentTimeMillis();
            NUTZUNG.readLock().unlock();
        }
    }

    /**
     * Eine Zeile lesen und die passende Schrift waehlen: lateinisch zuerst; kyrillisch, wenn das kyrillische Modell
     * wirklich kyrillische Buchstaben sieht; chinesisch/koreanisch/arabisch nur, wenn deren Zeichen herauskommen.
     */
    public static Lesung lesenBeste(BufferedImage zeile) {
        return lesenBeste(zeile, false);
    }

    /**
     * Wie lesenBeste, mit namen = true fuer Spielernamen: nachtrainiertes Latein-Modell und vorher der Schrift-Erkenner
     * (Autor 29.10.). Erkennt er sicher (ab 0,8) Chinesisch/Japanisch/Gemischt, Koreanisch oder Arabisch, liest nur das
     * passende Modell -- vorher scheiterten genau diese Namen an "sicher lateinisch" ("麒麟Blue" -> "Blue",
     * "시우122" -> "122"). Latein/Kyrillisch/Zierschrift lesen weiter wie bisher (gemessen: so am besten).
     */
    public static Lesung lesenBeste(BufferedImage zeile, boolean namen) {
        SchriftErkenner.Ergebnis erk = namen ? SchriftErkenner.erkennen(zeile).orElse(null) : null;
        if (erk != null) {
            Lesung l = nachSchrift(zeile, erk);
            if (l != null) return l;
        }
        // Messung 29.10. (echter Bot-Code): "La Peña" -> "ейа" (kyrillisch gewaehlt), "ХИЩНИК" -> "XNWHNK" (lateinisch
        // gewaehlt). Ist sich der Erkenner ab 0,9 sicher, entscheidet er zwischen Latein und Kyrillisch.
        boolean sicherLatein = erk != null && erk.wahrscheinlich() >= 0.9
                && java.util.Set.of("latein", "latein_akzent", "schmuck", "ziffern_symbole").contains(erk.klasse());
        boolean sicherKyrillisch = erk != null && erk.wahrscheinlich() >= 0.9 && "kyrillisch".equals(erk.klasse());
        Schrift latein = namen && hat(Schrift.LATEIN_NAMEN) ? Schrift.LATEIN_NAMEN : Schrift.LATEIN;
        Lesung lat = lesen(zeile, latein);
        if (lat == null) return null;
        // Autor 25.10.: "Dobry1810" las das lateinische Modell "Dobry41810" (0,90), das englische richtig (1,00) --
        // beide fragen und das sicherere nehmen (lateinisch kennt dafuer Akzente wie é, ü, ł).
        Lesung eng = lesen(zeile, Schrift.ENGLISCH);
        if (eng != null && !eng.leer() && (lat.leer() || eng.sicher() > lat.sicher())) lat = eng;
        // Sicher lateinisch gelesen (Zahlen, englische Woerter, Sets)? Aber nur mit genug Buchstaben/Ziffern:
        // "Анатолий." las lateinisch "." mit 0,97.
        boolean latSicher = !lat.leer() && lat.sicher() >= 0.93 && zeichenAnteil(lat.text()) >= 2;
        // Nacht 28.10.: hier stand "latSicher -> sofort zurueck", das kyrillische Modell wurde dann nie gefragt. Russische
        // Namen aus lateinisch aussehenden Buchstaben las das lateinische Modell trotzdem sicher als Salat: "Егорыч1" ->
        // "Eropbr41" (0,976), "Королева ll" -> "KoponeBa Ll", "ОТСТАНЬ!!!" -> "OTCTAHB!!!". Gemessen an 1315 echten
        // Arena-Schildern: immer auch kyrillisch fragen aendert 37 Lesungen, fast alle von Salat zum richtigen russischen
        // Namen (Бармалей, Дракон, Сержик, Игорь ...); "kyrillisch nur wenn sicherer" (statt -0,05) machte 10 richtige
        // wieder lateinisch. Kosten: ~6 ms je Name.
        Lesung beste = lat;
        Lesung kyr = lesen(zeile, namen && hat(Schrift.KYRILLISCH_NAMEN) ? Schrift.KYRILLISCH_NAMEN : Schrift.KYRILLISCH);
        // Messung 29.10.: das Namen-Modell kennt Latein UND Kyrillisch und mischt gleich aussehende Buchstaben in einem
        // Wort ("Poмаpio" statt "Ромаріо", "Остаn", "Маx007") -- je Wort auf eine Schrift bringen.
        if (kyr != null && namen) kyr = new Lesung(wortweiseEinheitlich(kyr.text()), kyr.sicher(), kyr.schrift());
        if (kyr != null) {
            String kern = kyrillischMitAnhang(kyr.text(), namen);
            // "Михаил" las lateinisch leer, "Твой Успех" lateinisch "oé Ycnex" (0,77) -- kyrillisch 0,73 / 0,82.
            // 29.10. gemessen an 433 von Hand beschrifteten Arena-Namen: das kyrillische Modell liegt bei russischen Namen
            // oft richtig, ist aber weniger "sicher" als die lateinische Fehllesung ("Анико" 0,92 vs "AHMKO" 0,99, "Юра"
            // 0,59 vs "HOpa" 0,99). Spielraum 0,40 statt 0,05 und Mindestsicherheit 0,55: Russisch 62 % -> 83 %, Latein
            // unveraendert 278/292 -- WENN die kyrillische Lesung zu mindestens 60 % aus kyrillischen Buchstaben besteht
            // (sonst "El Tirano" -> "о еlTirano").
            int kyrBuchstaben = anteil(kern, Character.UnicodeScript.CYRILLIC, false);
            boolean kyrPasst = kyrBuchstaben >= 2 && kyrBuchstaben >= 0.6 * buchstaben(kern) && kyr.sicher() >= 0.55
                    && (lat.leer() || kyr.sicher() >= lat.sicher() - 0.40);
            if (!sicherLatein && (kyrPasst || (sicherKyrillisch && kyrBuchstaben >= 2 && kyr.sicher() >= 0.4))) {
                // Messung 29.10. nachts: lateinische Clan-Kuerzel und Anhaenge fielen weg ("[GEA]Лев Щука" -> "]Лев Щука",
                // "злой&Blue" -> "злой&"). Steht zwischen Latein und Kyrillisch immer ein Zeichen wie [ ] & @ + |, gilt
                // die ganze Lesung (24 Namen richtig, keiner falsch); Muell direkt am Namen ("cYМихаилowb") wird weiter
                // abgeschnitten.
                if (namen && lateinAbgetrennt(kyr.text().strip())) kern = kyr.text().strip();
                beste = new Lesung(kern, kyr.sicher(), Schrift.KYRILLISCH);
            }
        }
        // Messung 29.10. nachts: las Latein nur Ziffern/Zeichen oder hoechstens EINEN Buchstaben, fragt der Bot die
        // Fremd-Modelle trotzdem -- vorher "恶魔808" -> "808", "狐666" -> "666", "برق286" -> "286", "فارس النار" -> "L"
        // (6 Namen richtig, keiner falsch). Gilt nur fuer Spielernamen.
        boolean kaumLatein = namen && (lat.text().codePoints().noneMatch(Character::isLetter) || zeichenAnteil(lat.text()) <= 1);
        if ((latSicher || sicherLatein || sicherKyrillisch) && beste == lat && !kaumLatein) return lat;   // sicher lateinisch und nicht kyrillisch: die anderen Schriften nicht fragen
        if (sicherKyrillisch) return beste;   // Erkenner sicher kyrillisch: keine Fremdschrift-Modelle
        for (Schrift s : new Schrift[]{Schrift.CHINESISCH, Schrift.KOREANISCH, arabisch(namen)}) {
            Lesung l = lesen(zeile, s);
            if (l == null || l.leer() || l.sicher() < 0.8) continue;
            Character.UnicodeScript eigen = switch (s) {
                case CHINESISCH -> Character.UnicodeScript.HAN;
                case KOREANISCH -> Character.UnicodeScript.HANGUL;
                default -> Character.UnicodeScript.ARABIC;
            };
            int eigene = anteil(l.text(), eigen, s == Schrift.CHINESISCH);
            if (eigene >= 1 && eigene * 2 >= buchstaben(l.text())) {
                if (beste == lat || l.sicher() > beste.sicher()) beste = l;
                // Autor 25.10.: chinesische/japanische Zeichen gesehen -> das grosse Modell (liest auch Japanisch) nachlesen lassen.
                if (s == Schrift.CHINESISCH) {
                    Lesung gross = lesen(zeile, Schrift.CHINESISCH_GROSS);
                    // Messung 29.10.: das grosse Modell allein las Chinesisch 92,5 %, Japanisch 95,5 % richtig, die Auswahl nur
                    // 60,5 % / 40 % -- es verlor, weil es "weniger sicher" klang als die Fehllesung von v4 (Japanisch v4: 20,5 %).
                    // Jetzt gilt es, sobald es selbst Han/Kana liest und mindestens 0,6 sicher ist.
                    if (gross != null && !gross.leer() && anteil(gross.text(), Character.UnicodeScript.HAN, true) >= 1
                            && gross.sicher() >= 0.6 && beste == l) {
                        beste = gross;
                    }
                }
            }
        }
        return beste;
    }

    /**
     * Wie PaddleOCR (pred_reverse): die Reihenfolge umkehren, lateinische Buchstaben/Ziffern/Satzzeichen-Stuecke aber
     * als Ganzes lassen ("ABC12" bleibt "ABC12", nur seine Stelle wandert).
     */
    /**
     * Auch das nachtrainierte arabische Modell liest verkehrt herum (Messung 29.10. nachts im Bot-Code, 200 Bilder:
     * mit Umkehr 72,5 % genau / 83,5 % ohne Leerzeichen und Gross/Klein, ohne Umkehr 0 %).
     * 30.10.: arabic_r2 (liest in der Reihenfolge des Bildes, auch Zahlen links vom Wort wie im Testbild) mit dieser
     * Umkehr: 94,5 % genau (200 kuenstliche Bilder; r1: 72,5 % genau / 89,0 % ohne Leerzeichen und Gross/Klein).
     * Die Regel "jeder arabische Lauf einzeln" (wie die Trainings-Beschriftung) stellt Zahlen hinter das Wort und kam nur
     * auf 60,5 % -- das Modell liest trotz dieser Beschriftung Zahlen dort, wo sie im Bild stehen.
     */
    static final boolean ARABISCH_NAMEN_UMDREHEN = true;

    /** Arabisch fuer Spielernamen: das nachtrainierte Modell, wenn es da ist, sonst das Original. */
    private static Schrift arabisch(boolean namen) {
        return namen && hat(Schrift.ARABISCH_NAMEN) ? Schrift.ARABISCH_NAMEN : Schrift.ARABISCH;
    }

    /**
     * Die Reihenfolge der Bildlesung umkehren; lateinische Buchstaben/Ziffern/Satzzeichen bleiben als Ganzes, ein Leerzeichen
     * gehoert nur dazu, wenn danach noch ein lateinisches Stueck folgt ("Hello World"). Ein Leerzeichen zwischen Zahl und
     * arabischem Wort bleibt dort stehen ("608 رون" -> "نور 608"); PaddleOCR selbst haengt es an die Zahl und es ging
     * verloren (Messung 30.10.: arabische Namen mit Zahl nur "formgleich", nie genau).
     */
    static String arabischUmdrehen(String text) {
        List<String> teile = new ArrayList<>();
        StringBuilder lauf = new StringBuilder();
        StringBuilder leer = new StringBuilder();   // Leerzeichen hinter dem Stueck, noch nicht klar, ob sie dazugehoeren
        for (int cp : text.codePoints().toArray()) {
            boolean lateinisch = cp < 128 && (Character.isLetterOrDigit(cp) || ":*./%+-".indexOf(cp) >= 0);
            if (lateinisch) {
                if (lauf.length() > 0) { lauf.append(leer); leer.setLength(0); }
                lauf.appendCodePoint(cp);
                continue;
            }
            if (cp == ' ' && lauf.length() > 0) {
                leer.append(' ');
                continue;
            }
            if (lauf.length() > 0) { teile.add(lauf.toString()); lauf.setLength(0); }
            for (int i = 0; i < leer.length(); i++) teile.add(" ");
            leer.setLength(0);
            teile.add(new String(Character.toChars(cp)));
        }
        if (lauf.length() > 0) teile.add(lauf.toString());
        for (int i = 0; i < leer.length(); i++) teile.add(" ");
        java.util.Collections.reverse(teile);
        return String.join("", teile);
    }

    /** Lesung nach dem Schrift-Erkenner -- oder null: dann liest lesenBeste wie bisher. */
    private static Lesung nachSchrift(BufferedImage zeile, SchriftErkenner.Ergebnis e) {
        java.util.List<Lesung> kandidaten = new ArrayList<>();
        for (var k : e.rangfolge()) {
            boolean sicher = k == e.rangfolge().get(0) && k.getValue() >= 0.8;
            if (!sicher && k.getValue() < 0.15) break;   // unsicher: die wahrscheinlichsten (ab 15 %) mitlesen
            Lesung l = switch (k.getKey()) {
                case "gemischt" -> gemischt(zeile);
                case "chinesisch", "japanisch" -> eigeneSchrift(lesen(zeile,
                        hat(Schrift.CHINESISCH_GROSS) ? Schrift.CHINESISCH_GROSS : Schrift.CHINESISCH),
                        Character.UnicodeScript.HAN, true);
                case "koreanisch" -> eigeneSchrift(lesen(zeile, Schrift.KOREANISCH), Character.UnicodeScript.HANGUL, false);
                case "arabisch" -> eigeneSchrift(lesen(zeile, arabisch(true)), Character.UnicodeScript.ARABIC, false);
                default -> null;
            };
            if (sicher && !"gemischt".equals(k.getKey())) return l;   // sicher erkannt: das passende Modell oder (null) wie bisher
            // Messung 29.10.: Zierzeichen wurden als fremde Zeichen mitgelesen ("Frank" -> "Frank么") -- bei unsicherer
            // Schrift und bei "gemischt" zaehlt eine Fremdschrift erst ab zwei Zeichen ("Robin宝宝", "시우122").
            // Ausnahmen fuer EIN Zeichen siehe einzelnesFremdzeichenZaehlt.
            if (l != null && fremdeZeichen(l.text()) < 2 && !einzelnesFremdzeichenZaehlt(l, e)) l = null;
            if (l != null && zierpaar(l.text())) l = null;
            if (sicher) return l;
            if (l != null) kandidaten.add(l);
            if (kandidaten.size() >= 3) break;
        }
        if (kandidaten.isEmpty()) return null;
        Lesung beste = kandidaten.stream().max(java.util.Comparator.comparingDouble(Lesung::sicher)).orElseThrow();
        return beste.sicher() >= 0.6 ? beste : null;
    }

    /** Gemischter Name (Latein + Han/Kana/Hangul/Arabisch, z. B. "시우122", "Robin宝宝"): das passende Fremd-Modell. */
    private static Lesung gemischt(BufferedImage zeile) {
        Lesung beste = null;
        for (Lesung l : new Lesung[]{
                eigeneSchrift(lesen(zeile, hat(Schrift.CHINESISCH_GROSS) ? Schrift.CHINESISCH_GROSS : Schrift.CHINESISCH),
                        Character.UnicodeScript.HAN, true),
                eigeneSchrift(lesen(zeile, Schrift.KOREANISCH), Character.UnicodeScript.HANGUL, false),
                eigeneSchrift(lesen(zeile, arabisch(true)), Character.UnicodeScript.ARABIC, false)}) {
            if (l != null && (beste == null || l.sicher() > beste.sicher())) beste = l;
        }
        return beste;
    }

    /**
     * Zaehlt ein EINZELNES Han/Kana/Hangul/Arabisch-Zeichen doch (Messung 29.10. nachts, 32 solche Lesungen im Testsatz:
     * 13 echte, 19 Zierzeichen)? Ja, wenn es mindestens 0,8 sicher gelesen ist UND entweder sonst keine Buchstaben
     * dastehen ("桜109", "용664", "刀" -- alle 19 Zierzeichen stehen neben lateinischen Buchstaben) oder der
     * Schrift-Erkenner sich ab 0,8 sicher ist, dass der Name gemischt ist ("Ruby빛"; "Frank么" hat 0,58). Brachte
     * 7 Namen zurueck, keiner wurde falsch.
     */
    private static boolean einzelnesFremdzeichenZaehlt(Lesung l, SchriftErkenner.Ergebnis e) {
        if (fremdeZeichen(l.text()) != 1 || l.sicher() < 0.8) return false;
        boolean andereBuchstaben = false;
        for (int cp : l.text().codePoints().toArray()) {
            if (Character.isLetter(cp) && fremdeZeichen(new String(Character.toChars(cp))) == 0) andereBuchstaben = true;
        }
        return !andereBuchstaben || ("gemischt".equals(e.klasse()) && e.wahrscheinlich() >= 0.8);
    }

    /**
     * Zierzeichen-Paar um einen lateinischen Namen (Messung 29.10. nachts): je ein "fremdes" Zeichen ganz vorne und ganz
     * hinten, dazwischen nur Latein/Ziffern -- "兰WILD兰", "쓰PIRATE브", "브CHAOS브". Echte gemischte Namen haben ihre
     * Fremdzeichen nur auf einer Seite ("Robin宝宝", "시우122"). Testsatz: 9 Namen richtig, keiner falsch.
     */
    static boolean zierpaar(String text) {
        int[] cp = text.strip().codePoints().toArray();
        if (cp.length < 4 || fremdeZeichen(new String(cp, 0, 1)) == 0 || fremdeZeichen(new String(cp, cp.length - 1, 1)) == 0) return false;
        String innen = new String(cp, 1, cp.length - 2);
        return fremdeZeichen(innen) == 0 && anteil(innen, Character.UnicodeScript.LATIN, false) >= 2;
    }

    private static final String KANA_AEHNLICH_HAN = "力口工二卜夕八一", KANA_AEHNLICH = "カロエニトタハー";

    /**
     * Han-Zeichen, die wie Katakana aussehen, direkt neben Katakana als Katakana lesen (Messung 29.10. nachts:
     * "タナ力290" -> "タナカ290", "タイガ一光" -> "タイガー光"; 2 Namen richtig, keiner falsch).
     */
    static String kanaEinheitlich(String text) {
        char[] s = text.toCharArray();
        for (int i = 0; i < s.length; i++) {
            int p = KANA_AEHNLICH_HAN.indexOf(s[i]);
            if (p < 0) continue;
            if ((i > 0 && katakana(s[i - 1])) || (i + 1 < s.length && katakana(s[i + 1]))) s[i] = KANA_AEHNLICH.charAt(p);
        }
        return new String(s);
    }

    private static boolean katakana(char c) {
        return c == 'ー' || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA;
    }

    /** Zeichen aus Han/Kana/Hangul/Arabisch in einer Lesung. */
    private static int fremdeZeichen(String text) {
        return anteil(text, Character.UnicodeScript.HAN, true) + anteil(text, Character.UnicodeScript.HANGUL, false)
                + anteil(text, Character.UnicodeScript.ARABIC, false);
    }

    /** Die Lesung nur, wenn darin Zeichen dieser Schrift stehen; sonst null. */
    private static Lesung eigeneSchrift(Lesung l, Character.UnicodeScript schrift, boolean auchKana) {
        return l != null && !l.leer() && anteil(l.text(), schrift, auchKana) >= 1 ? l : null;
    }

    /** Wie viele Buchstaben und Ziffern stehen in der Lesung? */
    private static int zeichenAnteil(String text) {
        int n = 0;
        for (int cp : text.codePoints().toArray()) if (Character.isLetterOrDigit(cp)) n++;
        return n;
    }

    /**
     * Die kyrillische Lesung ohne den Muell davor/dahinter ("cYМихаилowb"), aber MIT Ziffern, roemischen Zahlen und
     * Satzzeichen am Rand -- nurSchrift allein machte (Messung 28.10.) aus "Королева II" "Королева" und aus "нига 228"
     * "нига". Behalten wird ein Rand nur, wenn er ausschliesslich daraus besteht.
     */
    static String kyrillischMitAnhang(String text) {
        return kyrillischMitAnhang(text, false);
    }

    /** namen = true: roemische Zahlen im Anhang mit I statt l/| ("Охотник ll" -> "Охотник II", Messung 29.10.). */
    static String kyrillischMitAnhang(String text, boolean namen) {
        String kern = nurSchrift(text, Character.UnicodeScript.CYRILLIC);
        if (kern.isEmpty()) return kern;
        int von = text.indexOf(kern);
        if (von < 0) return kern;
        String davor = text.substring(0, von), danach = text.substring(von + kern.length());
        if (danach.matches("[\\s\\d.,!?_\\-IVXivxl|]*")) {   // auch "король v2"
            if (namen) {
                danach = java.util.regex.Pattern.compile("(?<![A-Za-z])[IVXl|]+(?![A-Za-z])").matcher(danach)
                        .replaceAll(m -> m.group().replace('l', 'I').replace('|', 'I'));
            }
            kern = kern + danach;
        }
        if (davor.matches("[\\s\\d._\\-]*")) kern = davor + kern;
        return kern.strip();
    }

    /**
     * Wechselt die Lesung zwischen lateinischen und kyrillischen Buchstaben nur ueber ein Zeichen, das kein Buchstabe und
     * kein Leerzeichen ist ("[GEA]Лев", "злой&Blue", "ведьма950+Shadow")? Ohne lateinische Buchstaben immer ja.
     */
    static boolean lateinAbgetrennt(String text) {
        Character.UnicodeScript vorher = null;
        boolean trenner = false;
        for (int cp : text.codePoints().toArray()) {
            if (Character.isLetter(cp)) {
                Character.UnicodeScript u = Character.UnicodeScript.of(cp);
                boolean wechsel = vorher != null && u != vorher
                        && (u == Character.UnicodeScript.LATIN || u == Character.UnicodeScript.CYRILLIC)
                        && (vorher == Character.UnicodeScript.LATIN || vorher == Character.UnicodeScript.CYRILLIC);
                if (wechsel && !trenner) return false;
                vorher = u;
                trenner = false;
            } else if (!Character.isWhitespace(cp)) {
                trenner = true;
            }
        }
        return true;
    }

    /** Kyrillische Buchstaben, die einem lateinischen gleichen (А/A, р/p ...), und ihr lateinisches Gegenstueck. */
    private static final String KYR_MEHRDEUTIG = "АВЕКМНОРСТУХІЈЅаеорсухіјѕкёїЁЇ", KYR_MEHRDEUTIG_LAT = "ABEKMHOPCTYXIJSaeopcyxijskëïËÏ";
    /** Lateinische Buchstaben, die einem kyrillischen gleichen (auch n/п, u/и), und ihr kyrillisches Gegenstueck. */
    private static final String LAT_GLEICH = "ABCEHKMOPTXYIJaceopxyijëïnuk", LAT_GLEICH_KYR = "АВСЕНКМОРТХУІЈасеорхуіјёїпик";

    /**
     * Jedes Wort mit lateinischen UND kyrillischen Buchstaben auf eine Schrift bringen: steht ein eindeutig kyrillischer
     * Buchstabe darin (м, л, в ...), wird es kyrillisch ("Poмаpio" -> "Ромаріо", "lван" -> "Іван"), sonst lateinisch
     * ("Маx007" -> "Max007"). Woerter aus nur einer Schrift bleiben, wie sie sind.
     */
    static String wortweiseEinheitlich(String text) {
        int[] cp = text.codePoints().toArray();
        StringBuilder aus = new StringBuilder(text.length());
        int i = 0;
        while (i < cp.length) {
            if (!Character.isLetter(cp[i])) {
                aus.appendCodePoint(cp[i++]);
                continue;
            }
            int j = i;
            boolean kyr = false, lat = false, eindeutig = false;
            for (; j < cp.length && Character.isLetter(cp[j]); j++) {
                Character.UnicodeScript u = Character.UnicodeScript.of(cp[j]);
                if (u == Character.UnicodeScript.CYRILLIC) {
                    kyr = true;
                    if (KYR_MEHRDEUTIG.indexOf(cp[j]) < 0) eindeutig = true;
                } else if (u == Character.UnicodeScript.LATIN) {
                    lat = true;
                }
            }
            for (int k = i; k < j; k++) {
                int c = cp[k];
                if (kyr && lat) {
                    Character.UnicodeScript u = Character.UnicodeScript.of(c);
                    if (eindeutig && u == Character.UnicodeScript.LATIN) {
                        int p = LAT_GLEICH.indexOf(c);
                        c = c == 'l' && k == i ? 'І' : p >= 0 ? LAT_GLEICH_KYR.charAt(p) : c;
                    } else if (!eindeutig && u == Character.UnicodeScript.CYRILLIC) {
                        int p = KYR_MEHRDEUTIG.indexOf(c);
                        if (p >= 0) c = KYR_MEHRDEUTIG_LAT.charAt(p);
                    }
                }
                aus.appendCodePoint(c);
            }
            i = j;
        }
        return aus.toString();
    }

    private static String nurSchrift(String text, Character.UnicodeScript schrift) {
        // Muell davor/dahinter ("cYМихаилowb") abschneiden: vom ersten bis zum letzten Zeichen dieser Schrift.
        int[] cp = text.codePoints().toArray();
        int von = -1, bis = -1;
        for (int i = 0; i < cp.length; i++) {
            if (Character.UnicodeScript.of(cp[i]) == schrift) {
                if (von < 0) von = i;
                bis = i;
            }
        }
        if (von < 0) return "";
        // Satzzeichen und Ziffern direkt daneben gehoeren zum Namen ("Анатолий.", "~ЦИРИ~", "Денис2"), Fremdbuchstaben nicht.
        while (von > 0 && !Character.isLetter(cp[von - 1]) && !Character.isWhitespace(cp[von - 1])) von--;
        while (bis < cp.length - 1 && !Character.isLetter(cp[bis + 1]) && !Character.isWhitespace(cp[bis + 1])) bis++;
        return new String(cp, von, bis - von + 1).strip();
    }

    private static int anteil(String text, Character.UnicodeScript schrift, boolean auchKana) {
        int n = 0;
        for (int cp : text.codePoints().toArray()) {
            Character.UnicodeScript u = Character.UnicodeScript.of(cp);
            if (u == schrift || (auchKana && (u == Character.UnicodeScript.HIRAGANA || u == Character.UnicodeScript.KATAKANA))) n++;
        }
        return n;
    }

    private static int buchstaben(String text) {
        int n = 0;
        for (int cp : text.codePoints().toArray()) if (Character.isLetter(cp)) n++;
        return Math.max(1, n);
    }

    /**
     * Ein Bild mit einer oder mehreren Zeilen (Name, darunter Clan): Zeilen an den hellen Schriftbaendern trennen und
     * jede einzeln lesen. Leere Zeilen fallen weg.
     */
    public static List<String> zeilen(BufferedImage bild) {
        return zeilen(bild, "");
    }

    /**
     * Wie oben, mit Sprach-Hinweis der alten Windows-Aufrufe: "ru"/"uk"/"bg"/"sr" kyrillisch, "zh"/"ja" chinesisch,
     * "ko" koreanisch, "ar" arabisch -- sonst waehlt PaddleOCR die Schrift selbst.
     */
    public static List<String> zeilen(BufferedImage bild, String sprache) {
        List<String> aus = new ArrayList<>();
        for (Lesung l : lesungen(bild, sprache)) aus.add(l.text());
        return aus;
    }

    /** Wie zeilen(), mit der Sicherheit je Zeile (fuer die Mischung mit Windows, siehe Texterkennung.lies). */
    public static List<Lesung> lesungen(BufferedImage bild, String sprache) {
        List<Lesung> aus = new ArrayList<>();
        if (bild == null || !verfuegbar()) return aus;
        // Autor 29.10.: "namen" = Spielernamen (Schrift-Erkenner + nachtrainiertes Latein), sonst wie bisher
        boolean namen = "namen".equals(sprache);
        Schrift fest = namen ? null : schriftFuer(sprache);
        for (int[] band : baender(bild)) {
            BufferedImage z = bild.getSubimage(0, band[0], bild.getWidth(), band[1] - band[0]);
            Lesung l = fest != null ? lesen(z, fest) : lesenBeste(z, namen);
            if (l == null || l.leer()) continue;
            String t = l.text();
            // Messung 29.10. nachts: 丶 灬 々 sind in chinesischen Spielernamen Zierzeichen (Testsatz-Regel: nicht Teil des
            // Namens); das grosse Modell las "皇帝々星辰" statt "皇帝星辰" -- 5 Namen richtig, keiner falsch.
            if (namen) t = kanaEinheitlich(t.replace("丶", "").replace("灬", "").replace("々", ""));
            if (fest == Schrift.KYRILLISCH) {
                t = nurSchrift(t, Character.UnicodeScript.CYRILLIC);   // ohne "cY...owb" davor/dahinter
            } else if (fest != null) {
                boolean cjk = fest == Schrift.CHINESISCH || fest == Schrift.CHINESISCH_GROSS;
                Character.UnicodeScript eigen = cjk ? Character.UnicodeScript.HAN
                        : fest == Schrift.KOREANISCH ? Character.UnicodeScript.HANGUL : Character.UnicodeScript.ARABIC;
                if (anteil(t, eigen, cjk) == 0) t = "";   // ausdruecklich gefragte Schrift nicht da
            }
            if (!t.isBlank()) aus.add(new Lesung(t.strip(), l.sicher(), l.schrift()));
        }
        return aus;
    }

    /** Ist sich PaddleOCR bei allen Zeilen sicher (ab 0,9)? Gemessen 25.10. an 112 strittigen Namen: dann lag es meist richtig. */
    public static boolean sicher(List<Lesung> l) {
        return !l.isEmpty() && l.stream().allMatch(x -> x.sicher() >= 0.9);
    }

    private static Schrift schriftFuer(String sprache) {
        String s = sprache == null ? "" : sprache.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("ru") || s.startsWith("uk") || s.startsWith("bg") || s.startsWith("sr-cyrl") || s.equals("be")) return Schrift.KYRILLISCH;
        if (s.startsWith("zh") || s.startsWith("ja")) {
            return hat(Schrift.CHINESISCH_GROSS) ? Schrift.CHINESISCH_GROSS : Schrift.CHINESISCH;
        }
        if (s.startsWith("ko")) return Schrift.KOREANISCH;
        if (s.startsWith("ar")) return Schrift.ARABISCH;
        return null;
    }

    /** Hoechste Luecke (Bildzeilen ohne Tinte), die noch innerhalb EINER Textzeile liegt (siehe baender). */
    static final int MAX_LUECKE_IN_ZEILE = 4;

    /** Zeilen-Baender (von, bis) ueber den Anteil heller, wenig farbiger Punkte je Bildzeile; keins gefunden = ganzes Bild. */
    static List<int[]> baender(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        // Nacht 28.10. (Messung an 1315 Arena-Schildern): auf den hellgrauen Karten (Grund 211/208/204) zaehlte der GRUND
        // als Tinte (heller als 170, kaum farbig) -- ein dreizeiliges Schild wurde als EINE Zeile gelesen, Ergebnis Salat,
        // Rueckfall auf Windows. Tinte heisst jetzt: deutlich heller als der typische Grund dieses Bildes (Median + 25,
        // mindestens wie bisher 170). Weisse Schrift (~255) bleibt auf jedem Grund Tinte.
        int[] hell = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = b.getRGB(x, y);
                hell[Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255))]++;
            }
        }
        int median = 0;
        for (int i = 0, summe = 0; i < 256; i++) {
            summe += hell[i];
            if (summe * 2 >= w * h) { median = i; break; }
        }
        int ab = Math.max(170, median + 25);
        int[] tinte = new int[h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = b.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
                if (max > ab && max - min < 70) tinte[y]++;
            }
        }
        List<int[]> baender = new ArrayList<>();
        int schwelle = Math.max(2, w / 40);
        int start = -1;
        int letztesEnde = -1000;   // erste Zeile ohne Tinte hinter dem letzten Band
        for (int y = 0; y <= h; y++) {
            boolean an = y < h && tinte[y] >= schwelle;
            if (an && start < 0) start = y;
            if (!an && start >= 0) {
                if (y - start >= Math.max(6, h / 8)) {
                    // Messung 30.10. (Testsatz): 9 koreanische und 11 Zierschrift-Namen wurden an einer 1-4 Pixel schmalen
                    // Luecke INNERHALB der Zeile geteilt (Hangul-Silben, duenne Zierbuchstaben) und nur das obere Stueck
                    // gelesen ("도윤" -> "EO", "JINXED" -> "LL"). Echte Zeilen (Name / Clan) liegen mindestens 5 Pixel
                    // auseinander (335 echte Schilder: Luecken meist 23-27, kleinste echte 5).
                    if (!baender.isEmpty() && start - letztesEnde <= MAX_LUECKE_IN_ZEILE) {
                        baender.get(baender.size() - 1)[1] = Math.min(h, y + 3);
                    } else {
                        baender.add(new int[]{Math.max(0, start - 3), Math.min(h, y + 3)});
                    }
                    letztesEnde = y;
                }
                start = -1;
            }
        }
        if (baender.size() <= 1) return List.of(new int[]{0, h});
        return baender;
    }

    /** PP-OCR "rec": Hoehe auf die des Modells, Seitenverhaeltnis bleibt, BGR, (x/255 - 0,5) / 0,5. */
    private static float[][][] rechnen(Modell m, BufferedImage b) throws Exception {
        int h = m.hoehe();
        int w = Math.max(16, (int) Math.ceil(b.getWidth() * (double) h / Math.max(1, b.getHeight())));
        BufferedImage k = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = k.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(b, 0, 0, w, h, null);
        g.dispose();
        float[] d = new float[3 * h * w];
        int[] px = k.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            d[i] = ((p & 255) / 255f - 0.5f) / 0.5f;
            d[h * w + i] = (((p >> 8) & 255) / 255f - 0.5f) / 0.5f;
            d[2 * h * w + i] = (((p >> 16) & 255) / 255f - 0.5f) / 0.5f;
        }
        String ein = m.sitzung().getInputNames().iterator().next();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(d), new long[]{1, 3, h, w});
             OrtSession.Result r = m.sitzung().run(Map.of(ein, t))) {
            return (float[][][]) r.get(0).getValue();
        }
    }

    /** CTC gierig: je Zeitschritt das wahrscheinlichste Zeichen, 0 = leer, Wiederholungen zusammen. */
    private static Lesung dekodieren(Modell m, float[][][] aus, Schrift schrift) {
        StringBuilder sb = new StringBuilder();
        int vorher = -1;
        double summe = 0;
        int n = 0;
        for (float[] schritt : aus[0]) {
            int best = 0;
            for (int c = 1; c < schritt.length; c++) if (schritt[c] > schritt[best]) best = c;
            if (best != 0 && best != vorher) {
                int idx = best - 1;
                sb.append(idx < m.zeichen().size() ? m.zeichen().get(idx) : " ");
                summe += schritt[best];
                n++;
            }
            vorher = best;
        }
        return new Lesung(sb.toString(), n == 0 ? 0 : summe / n, schrift);
    }
}
