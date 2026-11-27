package de.hustlebot.core;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Laedt die Bildvorlagen aus dem Vorlagenordner und sucht sie im aktuellen
 * Spielbild. Jede Vorlage ist ein kleiner Bildausschnitt, etwa ein Knopf.
 */
public final class Matcher {

    /** Ein gefundener Treffer mit Position, Groesse und Uebereinstimmung. */
    public record Hit(String name, int x, int y, int width, int height, double score) {
        public Point center() {
            return new Point(x + width / 2.0, y + height / 2.0);
        }

        public int centerX() { return x + width / 2; }
        public int centerY() { return y + height / 2; }
    }

    /** Standardschwelle. Darunter gilt ein Treffer als unsicher. */
    public static final double DEFAULT_THRESHOLD = 0.84;

    /**
     * Name -> Vorlage. Speicher 20.11. (Autor spielt nebenbei Minecraft): steht hier {@code null}, ist die Datei bekannt
     * (siehe {@link #ungelesen}), aber noch nicht entpackt -- das geschieht erst beim ersten Gebrauch ({@link #holen}).
     * Vorher entpackte jeder Modus-Start alle ~1430 Bilder (68 MB, ~1 s Rechenzeit), gebraucht wird je Modus nur ein
     * kleiner Teil (Titanen-Detailbilder 25 MB und Stigma-Fensterbilder 15 MB nennt kein Modus beim Namen).
     */
    private final Map<String, Mat> templates = new LinkedHashMap<>();
    /** Vorlagen, deren Datei bekannt, aber noch nicht gelesen ist (in {@link #templates} steht fuer sie null). */
    private final Map<String, Path> ungelesen = new HashMap<>();
    /** Breite/Hoehe der noch nicht gelesenen Vorlagen, aus dem PNG-Kopf -- fuer {@link #sizes()} ohne Entpacken. */
    private final Map<String, int[]> masse = new HashMap<>();
    /** PNG-Kopf je Datei {Aenderungszeit, Breite, Hoehe}: bleibt ueber loadAll() hinweg, jede Datei wird nur einmal je Programmlauf geoeffnet. */
    private final Map<Path, long[]> kopfMerker = new HashMap<>();
    private final Path templateRoot;
    private Laufstatistik laufstatistik;

    public Matcher(Path templateRoot) {
        this.templateRoot = templateRoot;
    }

    /** Wenn gesetzt, wird jeder Treffer fuer die Vorschau festgehalten. */
    public void setLaufstatistik(Laufstatistik laufstatistik) {
        this.laufstatistik = laufstatistik;
    }

    private void report(Hit hit) {
        if (laufstatistik != null) {
            laufstatistik.recordMatch(hit.name(), hit.x(), hit.y(),
                    hit.width(), hit.height(), hit.score());
        }
    }

    /** Namen aller geladenen Vorlagen samt Groesse, fuer die Pruefliste. */
    public synchronized Map<String, int[]> sizes() {
        Map<String, int[]> result = new LinkedHashMap<>();
        templates.forEach((name, mat) -> {
            int[] m = mat != null ? new int[]{mat.width(), mat.height()} : masse.get(name);
            if (m != null) result.put(name, new int[]{m[0], m[1]});
        });
        return result;
    }

    /**
     * Die Vorlage; beim ersten Gebrauch aus der Datei lesen (Speicher 20.11.). Nur unter der Sperre (this) aufrufen.
     * Laesst sich die Datei nicht entpacken, ist der Name danach weg -- wie frueher, als sie schon beim Laden ausfiel.
     */
    private Mat holen(String name) {
        Mat t = templates.get(name);
        if (t != null) return t;
        Path datei = ungelesen.remove(name);
        if (datei == null) return null;
        masse.remove(name);
        Mat neu = lesen(datei);
        if (neu.empty()) {
            neu.release();
            templates.remove(name);
            geladenStand.remove(name);
            geladenPfad.remove(name);
            Log.warn("Vorlagen", Sprache.t("File could not be read: ", "Datei konnte nicht gelesen werden: ") + datei.getFileName());
            return null;
        }
        templates.put(name, neu);
        Log.debug("Vorlagen", "Geladen: " + name + " (" + neu.width() + "x" + neu.height() + ")");
        return neu;
    }

    /** Wie viele Vorlagen gerade entpackt im Speicher liegen (fuer Tests und den Speicherblick). */
    synchronized int entpackt() {
        int n = 0;
        for (Mat m : templates.values()) if (m != null) n++;
        return n;
    }

    /** Bild als BGR lesen; leer, wenn es nicht geht. */
    private static Mat lesen(Path file) {
        boolean nurAscii = file.toString().chars().allMatch(c -> c < 128);
        Mat template = nurAscii ? Imgcodecs.imread(file.toString(), Imgcodecs.IMREAD_COLOR) : new Mat();
        if (template.empty()) {
            // OpenCV oeffnet unter Windows keine Pfade mit Umlauten/kyrillischen Buchstaben (Hauptbot 02.10.:
            // Arena-Namensbild "ar_sicher_Турист_..." blieb wirkungslos). Dann die Bytes selbst lesen.
            template.release();
            try {
                MatOfByte roh = new MatOfByte(Files.readAllBytes(file));
                template = Imgcodecs.imdecode(roh, Imgcodecs.IMREAD_COLOR);
                roh.release();
            } catch (IOException e) {
                template = new Mat();
            }
        }
        return template;
    }

    /** Breite und Hoehe aus dem PNG-Kopf (IHDR), ohne das Bild zu entpacken; null = keine lesbare PNG-Datei. */
    static int[] pngMasse(Path datei) {
        try (java.io.InputStream in = Files.newInputStream(datei)) {
            byte[] k = in.readNBytes(24);
            if (k.length < 24 || (k[0] & 0xFF) != 0x89 || k[1] != 'P' || k[2] != 'N' || k[3] != 'G'
                    || k[12] != 'I' || k[13] != 'H' || k[14] != 'D' || k[15] != 'R') return null;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(k);
            int w = b.getInt(16), h = b.getInt(20);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** PNG-Masse mit Merker: dieselbe Datei mit derselben Aenderungszeit wird nicht noch einmal geoeffnet. */
    private int[] kopfMasse(Path datei, long stand) {
        long[] k = kopfMerker.get(datei);
        if (k != null && k[0] == stand) return new int[]{(int) k[1], (int) k[2]};
        int[] m = pngMasse(datei);
        if (m == null) kopfMerker.remove(datei);
        else kopfMerker.put(datei, new long[]{stand, m[0], m[1]});
        return m;
    }

    /** Der Ordner "templates" (11.10.: fuer die Doppelt-Pruefung der Arena-Namensbilder). */
    public Path templateRoot() {
        return templateRoot;
    }

    /**
     * Vorlagen-Pruefung 29.10.: 12x "Unknown template requested" (ar_victory, ar_defeat, ar_your_squad), jedes Mal 0,1-0,3 s
     * vor "N templates loaded" -- ein anderer Faden (Arena-Beobachter) fragte, waehrend loadAll() die Tabelle leerte und neu
     * fuellte. Waehrend des Ladens wartet eine Anfrage nach einer fehlenden Vorlage jetzt kurz, statt "unbekannt" zu sagen.
     */
    private volatile boolean laedt;
    private final Object ladeSperre = new Object();

    /** Die Vorlage; fehlt sie gerade, weil loadAll() laeuft, bis zu 3 s auf das Ende warten. */
    private Mat vorlage(String name) {
        Mat t = holen(name);
        if (t != null || !laedt) return t;
        long bis = System.currentTimeMillis() + 3000;
        synchronized (ladeSperre) {
            while (laedt && System.currentTimeMillis() < bis) {
                try {
                    ladeSperre.wait(Math.max(1, bis - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return holen(name);
    }

    /** Liest alle PNG-Dateien rekursiv ein. Der Dateiname ohne Endung ist der Vorlagenname. */
    public synchronized int loadAll() {
        laedt = true;
        try {
            return ladenIntern();
        } finally {
            synchronized (ladeSperre) {
                laedt = false;
                ladeSperre.notifyAll();
            }
        }
    }

    private int ladenIntern() {
        templates.values().forEach(m -> {
            if (m != null) m.release();
        });
        grauVorlagen.values().forEach(Mat::release);
        grauVorlagen.clear();
        templates.clear();
        ungelesen.clear();
        masse.clear();
        geladenStand.clear();
        geladenPfad.clear();
        doppeltStehend.clear();
        originale.values().forEach(Mat::release);
        originale.clear();
        eigenAusDatei.clear();

        // Ergebnisse zu den alten Vorlagen sind jetzt wertlos.
        frameBest.clear();
        frameScreen = null;
        ohneWeissGemeldet.clear();   // ein neu aufgenommenes Bild darf wieder warnen

        if (!Files.isDirectory(templateRoot)) {
            Log.error("Vorlagen", Sprache.t("Folder not found: ", "Ordner nicht gefunden: ") + templateRoot.toAbsolutePath());
            return 0;
        }

        try {
            ordnerLaden(templateRoot, true);
        } catch (IOException | java.io.UncheckedIOException e) {
            Log.error("Vorlagen", Sprache.t("Folder could not be read", "Ordner konnte nicht gelesen werden"), e);
        }

        // Lernmodus (27.09.): Bilder, die Claude nach einem !fix aus Autors
        // Screenshot ausgeschnitten hat. Eigener Ordner, templates\ bleibt
        // Autors Capture-Images.exe vorbehalten.
        if (Files.isDirectory(GELERNT)) {
            try {
                ordnerLaden(GELERNT, true);
            } catch (IOException | java.io.UncheckedIOException e) {
                Log.warn("Vorlagen", Sprache.t("Folder gelernt\\ could not be read: ",
                        "Ordner gelernt\\ konnte nicht gelesen werden: ") + e.getMessage());
            }
        }

        // Autor 24.10.: eigene Aufnahmen des Spielers (sichtbarer Ordner "My pictures") -- bei gleichem Namen gilt die neuere.
        Path eigene = EIGENE_BILDER;
        if (eigene != null && Files.isDirectory(eigene)) {
            try {
                ordnerLaden(eigene, false);
            } catch (IOException | java.io.UncheckedIOException e) {
                Log.warn("Vorlagen", Sprache.t("Folder \"My pictures\" could not be read: ",
                        "Ordner \"My pictures\" konnte nicht gelesen werden: ") + e.getMessage());
            }
        }

        if (!doppeltStehend.isEmpty()) {
            Log.info("Vorlagen", Sprache.t(doppeltStehend.size() + " names exist in two of the bot's own folders -- the newer capture applies: "
                    + String.join(", ", doppeltStehend) + ".",
                    doppeltStehend.size() + " Namen gibt es in zwei eigenen Ordnern des Bots -- es gilt die neuere Aufnahme: "
                    + String.join(", ", doppeltStehend) + "."));
        }
        Log.info("Vorlagen", Sprache.t(templates.size() + " templates loaded from " + templateRoot.toAbsolutePath(),
                templates.size() + " Vorlagen geladen aus " + templateRoot.toAbsolutePath()));
        return templates.size();
    }

    /** true = die Datei liegt im sichtbaren Ordner "My pictures" des Spielers. */
    private static boolean vomSpieler(Path datei) {
        Path eigene = EIGENE_BILDER;
        if (datei == null || eigene == null) return false;
        try {
            return datei.toAbsolutePath().normalize().startsWith(eigene.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Alle PNG-Dateien unter {@code wurzel} laden, sortiert wie bisher. Speicher/Tempo 20.11.: das Aenderungsdatum kommt
     * gleich aus dem Ordnerlesen (Files.find) -- vorher fragte loadOne je Datei einzeln (1400 Zugriffe, ~140 ms je Modus-Start).
     */
    private void ordnerLaden(Path wurzel, boolean sparsamFiltern) throws IOException {
        Map<Path, Long> staende = new HashMap<>();
        try (Stream<Path> files = Files.find(wurzel, Integer.MAX_VALUE, (p, a) -> {
            if (!a.isDirectory() && p.toString().toLowerCase().endsWith(".png")) staende.put(p, a.lastModifiedTime().toMillis());
            return false;
        })) {
            files.forEach(p -> { });
        }
        staende.keySet().stream()
               .filter(p -> !sparsamFiltern || ServerSparsam.bildLaden(p))
               .sorted()
               .forEach(p -> loadOne(p, staende.get(p)));
    }

    private void loadOne(Path file) {
        long stand = 0;
        try {
            stand = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            // ohne Datum gilt die Datei als alt
        }
        loadOne(file, stand);
    }

    private void loadOne(Path file, long stand) {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - 4);

        boolean neuIstEigen = istEigen(file);
        boolean eigenSpiel = neuIstEigen || eigenAusDatei.contains(name);
        // Speicher 20.11.: eine lesbare PNG-Datei wird erst beim ersten Gebrauch entpackt (holen). Sofort gelesen wird nur,
        // was keinen PNG-Kopf hat (dann gilt wie bisher: unlesbar -> Warnung, Name fehlt) und das Paar eigene Aufnahme/Original.
        int[] kopf = eigenSpiel ? null : kopfMasse(file, stand);
        Mat template = null;
        if (kopf == null) {
            template = lesen(file);
            if (template.empty()) {
                template.release();
                Log.warn("Vorlagen", Sprache.t("File could not be read: ", "Datei konnte nicht gelesen werden: ") + file.getFileName());
                return;
            }
        }
        // DOPPELTER NAME: DIE NEUERE AUFNAHME GILT (27.09.).
        //
        // Das Aufnahme-Werkzeug legt "dc_..." nach templates\cathedral, aeltere
        // Aufnahmen liegen aber noch in templates\dungeon. Geladen wird nach
        // dem Alphabet, "dungeon" kommt nach "cathedral" -- bisher gewann also
        // immer die ALTE Datei, und eine neue Aufnahme blieb ohne Wirkung
        // (so bei dc_guardian, und am 27.09. fast bei dc_claim_reward).
        //
        // EIGENE AUFNAHMEN (17.10.): eine Datei aus templates\gelernt\eigen ist die aktive Vorlage, Autors Original
        // bleibt als Rueckfall im Speicher (eigeneZuruecknehmen) -- egal, welche der beiden zuerst geladen wird.
        if (eigenSpiel) {
            Mat alt = holen(name);   // ein noch nicht gelesenes Original jetzt lesen, sonst fehlt der Rueckfall
            if (neuIstEigen) {
                if (alt != null && !eigenAusDatei.contains(name)) originale.put(name, alt);
                else if (alt != null) alt.release();
                templates.put(name, template);
                eigenAusDatei.add(name);
                geladenStand.put(name, Long.MAX_VALUE);
            } else if (!originale.containsKey(name)) {
                originale.put(name, template);   // das Original kam nach der eigenen Aufnahme
            } else {
                template.release();
            }
            return;
        }
        Long vorher = geladenStand.get(name);
        if (vorher != null) {
            boolean neuer = stand > vorher;
            // Bugjagd 29.10.: 1078 von 1611 Warnungen in drei Tagen waren dieselben acht Doppelnamen (unit_1..6 in
            // dungeon\ und gelernt\dungeon\, dc_claim_reward in cathedral\ und dungeon\), bei jedem Laden neu --
            // sie verdeckten die echten Warnungen. Eine Warnung ist nur noch dann sinnvoll, wenn eine der beiden
            // Dateien aus "My pictures" stammt (der Spieler hat selbst aufgenommen): Warnhilfe erklaert das dann.
            boolean vomSpieler = vomSpieler(file) || vomSpieler(geladenPfad.get(name));
            if (!vomSpieler) {
                doppeltStehend.add(name);
                Log.debug("Vorlagen", "Name doppelt (eigene Ordner): " + name + " -- es gilt die neuere Aufnahme.");
            } else if (!doppeltGewarnt.add(name)) {
                Log.debug("Vorlagen", "Name doppelt (Bild des Spielers, schon gemeldet): " + name + " -- es gilt die neuere Aufnahme.");
            } else Log.warn("Vorlagen", Sprache.t(
                    "Name used twice: " + name + ". Keeping the "
                    + (neuer ? "newer capture " + file.getParent().getFileName() + "\\" + file.getFileName()
                             : "already loaded, newer capture") + ".",
                    "Name doppelt vergeben: " + name + ". Es gilt die "
                    + (neuer ? "neuere Aufnahme " + file.getParent().getFileName() + "\\" + file.getFileName()
                             : "schon geladene, neuere Aufnahme") + "."));
            if (!neuer) {
                if (template != null) template.release();
                return;
            }
            Mat alt = templates.get(name);
            if (alt != null) alt.release();
        }
        geladenStand.put(name, stand);
        geladenPfad.put(name, file);
        templates.put(name, template);
        if (template == null) {
            ungelesen.put(name, file);
            masse.put(name, kopf);
        } else {
            ungelesen.remove(name);
            masse.remove(name);
            Log.debug("Vorlagen", "Geladen: " + name + " (" + template.width() + "x" + template.height() + ")");
        }
    }

    // ------------------------------------------------------------------
    //  Eigene Vorlagen vom Bildschirm des Freundes (Autor 17.10.)
    // ------------------------------------------------------------------
    //
    // Autor: "Bei Knoepfen, die sicher funktionieren, soll der Bot sie anhand von Karlas Screen aufnehmen und das
    // fuer die zukuenftigen Runs benutzen. So dass am Ende alle meine Vorlagen langsam aber sicher durch sicher
    // passende Vorlagen von ihrem Screen ersetzt werden." -- Aufgenommen wird nur bei einem sehr sicheren Fund (ab
    // 0,92), einmal je Vorlage, nach templates\gelernt\eigen\<Name>.png. Autors Original bleibt im Speicher:
    // haengt der Bot (BotEngine, 2 Minuten ohne Fortschritt), nimmt eigeneZuruecknehmen() alle eigenen Bilder
    // zurueck (Dateien weg, fuer diese Sitzung gesperrt) -- dann gilt wieder Autors Bild.

    /** Wohin eigene Aufnahmen gehen; null = aus (so bleibt es im Hauptbot). Der Freunde-Bot setzt es beim Start. */
    public static volatile Path EIGEN_ORDNER = null;
    /** Ab diesem Wert gilt ein Fund als "sicher" genug, um ihn als eigene Vorlage aufzunehmen. */
    // Vorlagen-Pruefung 29.10.: dc_finish_confirm wurde am 26.10. bei nur 0,92 aufgenommen (Bild mitten in einer
    // Einblendung) -- danach fiel der Median von 0,932 auf 0,880, der Anteil unter 0,90 von 15 % auf 51 %. Jetzt erst ab
    // 0,95 UND nur, wenn eine zweite Sichtung (mind. 1 s spaeter) dem ersten Ausschnitt zu mindestens 0,97 gleicht --
    // ein Zwischenbild einer Animation kommt so nicht mehr als Vorlage durch.
    private static final double EIGEN_AB = 0.95;
    private static final double EIGEN_GLEICH = 0.97;
    /** Erste sichere Sichtung je Vorlage: Ausschnitt und Zeit, bis eine zweite gleiche kommt. */
    private final Map<String, Mat> eigenKandidat = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> eigenKandidatUm = new java.util.concurrent.ConcurrentHashMap<>();
    /** Knoepfe mit grauer Variante -- nie als eigene Vorlage aufnehmen. */
    private static final java.util.Set<String> NIE_EIGEN = java.util.Set.of("quick_attack", "to_battle");
    /** Autors Originale der Vorlagen, die gerade durch eine eigene Aufnahme ersetzt sind. */
    private final Map<String, Mat> originale = new java.util.concurrent.ConcurrentHashMap<>();
    /** Vorlagen, deren aktives Bild eine eigene Aufnahme ist. */
    private final Set<String> eigenAusDatei = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** In dieser Sitzung nicht (wieder) aufnehmen -- nach einem Rueckfall oder einem Schreibfehler. */
    private final Set<String> eigenGesperrt = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean istEigen(Path datei) {
        String s = datei.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        return s.contains("\\gelernt\\eigen\\");
    }

    /** Wie viele Vorlagen gerade mit einer eigenen Aufnahme laufen. */
    public int eigeneAnzahl() {
        return eigenAusDatei.size();
    }

    /** Nach einem sicheren Fund: den Knopf aus dem Bild ausschneiden und ab jetzt als Vorlage benutzen. */
    private void eigenVielleicht(Mat screen, Hit h) {
        Path ordner = EIGEN_ORDNER;
        if (ordner == null || h.score() < EIGEN_AB) return;
        String name = h.name();
        // Autor 18.10. (Verlies): Quick Attack/To battle gibt es auch GRAU (Spiel synchronisiert) -- der Vergleich ist
        // helligkeitsunabhaengig, die graue Aufnahme passte danach auf den orangen Knopf nur 0,77: 11-15 s Warten und
        // "To battle" statt Quick Attack. Solche Knoepfe nie selbst aufnehmen.
        if (NIE_EIGEN.contains(name)) return;
        // 24.10. 04:19 live: "ev_refresh_grau" passte grau gerechnet zu 0,97 auf den GELBEN Knopf und wurde von ihm neu
        // aufgenommen -- danach galt der gelbe Knopf als grau, das Feld wurde nach dem Zauberstab nie neu gemischt und das
        // Hammer-Event endete nach 3 Faessern. Vorlagen fuer einen Farbzustand (grau/gelb) nie selbst aufnehmen.
        String klein = name.toLowerCase(Locale.ROOT);
        if (klein.contains("grau") || klein.contains("gelb") || klein.contains("grey") || klein.contains("gray")) return;
        if (eigenAusDatei.contains(name) || eigenGesperrt.contains(name)) return;
        Mat ausschnitt = null;
        try {
            synchronized (this) {
                Mat original = holen(name);
                if (original == null || h.width() != original.width() || h.height() != original.height()
                        || h.x() < 0 || h.y() < 0 || h.x() + h.width() > screen.width() || h.y() + h.height() > screen.height()
                        || screen.type() != original.type()) {
                    eigenGesperrt.add(name);
                    return;
                }
                ausschnitt = new Mat(screen, new Rect(h.x(), h.y(), h.width(), h.height())).clone();
                long jetzt = System.currentTimeMillis();
                Mat kandidat = eigenKandidat.get(name);
                if (kandidat == null) {
                    eigenKandidat.put(name, ausschnitt);
                    eigenKandidatUm.put(name, jetzt);
                    ausschnitt = null;
                    return;
                }
                if (jetzt - eigenKandidatUm.getOrDefault(name, jetzt) < 1000) {
                    ausschnitt.release();
                    ausschnitt = null;
                    return;
                }
                double gleich = gleichheit(kandidat, ausschnitt);
                if (gleich < EIGEN_GLEICH) {
                    // anders als beim ersten Mal (Einblendung, Hintergrund) -- neu anfangen
                    kandidat.release();
                    eigenKandidat.put(name, ausschnitt);
                    eigenKandidatUm.put(name, jetzt);
                    ausschnitt = null;
                    Log.debug("Vorlagen", "Eigene Vorlage " + name + " noch nicht aufgenommen: zweite Sichtung nur "
                            + String.format("%.3f", gleich) + " gleich.");
                    return;
                }
                eigenKandidat.remove(name);
                eigenKandidatUm.remove(name);
                kandidat.release();
                MatOfByte png = new MatOfByte();
                Imgcodecs.imencode(".png", ausschnitt, png);
                Files.createDirectories(ordner);
                Files.write(ordner.resolve(name + ".png"), png.toArray());
                png.release();
                originale.put(name, original);
                templates.put(name, ausschnitt);
                eigenAusDatei.add(name);
                Mat g = grauVorlagen.remove(name);
                if (g != null) g.release();
                Mat hb = halbeVorlagen.remove(name);
                if (hb != null) hb.release();
                frameBest.clear();
            }
            Log.debug("Vorlagen", "Eigene Vorlage aufgenommen: " + name + String.format(" (%.3f), jetzt %d eigene", h.score(), eigenAusDatei.size()));
        } catch (Exception e) {
            if (ausschnitt != null && !eigenAusDatei.contains(name)) ausschnitt.release();
            eigenGesperrt.add(name);
            Log.debug("Vorlagen", "Eigene Vorlage " + name + " nicht aufgenommen: " + e.getMessage());
        }
    }

    /** Wie gleich zwei gleich grosse Ausschnitte sind (1 = gleich). */
    static double gleichheit(Mat a, Mat b) {
        if (a.width() != b.width() || a.height() != b.height() || a.type() != b.type()) return 0;
        Mat r = new Mat();
        try {
            Imgproc.matchTemplate(a, b, r, Imgproc.TM_CCOEFF_NORMED);
            double v = Core.minMaxLoc(r).maxVal;
            return Double.isNaN(v) ? (Core.norm(a, b) == 0 ? 1 : 0) : v;
        } finally {
            r.release();
        }
    }

    /**
     * Rueckfall: alle eigenen Aufnahmen weg, wieder Autors Originale (Dateien geloescht, fuer diese Sitzung gesperrt).
     * Liefert, wie viele zurueckgenommen wurden.
     */
    public synchronized int eigeneZuruecknehmen(String grund) {
        int n = 0;
        for (var e : new ArrayList<>(originale.entrySet())) {
            String name = e.getKey();
            Mat eigen = templates.put(name, e.getValue());
            if (eigen != null && eigen != e.getValue()) eigen.release();
            Mat g = grauVorlagen.remove(name);
            if (g != null) g.release();
            Mat hb = halbeVorlagen.remove(name);
            if (hb != null) hb.release();
            eigenAusDatei.remove(name);
            eigenGesperrt.add(name);
            Path ordner = EIGEN_ORDNER;
            if (ordner != null) {
                try {
                    Files.deleteIfExists(ordner.resolve(name + ".png"));
                } catch (IOException ex) {
                    Log.debug("Vorlagen", "Eigene Vorlage nicht geloescht: " + ex.getMessage());
                }
            }
            n++;
        }
        originale.clear();
        frameBest.clear();
        if (n > 0) {
            Log.warn("Vorlagen", Sprache.t(n + " own pictures taken back (" + grund + ") -- Autor's pictures again.",
                    n + " eigene Vorlagen zurueckgenommen (" + grund + ") -- wieder Autors Bilder."));
        }
        return n;
    }

    /** Vom Lernmodus ausgeschnittene Vorlagen (tools/Ausschneiden). */
    public static final Path GELERNT = Path.of("gelernt");
    /** Autor 24.10.: "My pictures" -- eigene Aufnahmen des Spielers (Capture-Images) und Arena-Listen; null = keiner. */
    public static volatile Path EIGENE_BILDER = null;

    /**
     * Freunde-Bot (Code-Pruefung 16.10.): der Nachschub liefert die Lernbilder nach templates\gelernt, eigene
     * Ausschnitte landen in gelernt\. Erst templates\gelernt, sonst gelernt\ (wie die alte Freunde-Fassung).
     */
    public static Path gelernt(String... teile) {
        Path geliefert = Path.of("templates", "gelernt");
        Path eigen = GELERNT;
        for (String s : teile) {
            geliefert = geliefert.resolve(s);
            eigen = eigen.resolve(s);
        }
        return java.nio.file.Files.exists(geliefert) ? geliefert : eigen;
    }

    /**
     * Eine einzelne Vorlage zur Laufzeit (nach)laden -- fuer Bilder, die der Bot selbst
     * ausschneidet (eigene Event-Motive, 03.10.). Gleicher Name: die neuere Datei gilt.
     */
    public synchronized void vorlageNachladen(Path datei) {
        loadOne(datei);
        frameBest.clear();
    }

    /** Aenderungszeit der geladenen Datei je Vorlage -- bei doppelten Namen gilt die neuere. */
    private final Map<String, Long> geladenStand = new HashMap<>();
    /** Woher die geladene Vorlage stammt (zum Unterscheiden "stehender" Doppelnamen von selbst gemachten). */
    private final Map<String, Path> geladenPfad = new HashMap<>();
    /** Doppelte Namen aus Autors eigenen Ordnern (templates\, gelernt\) -- seit Jahren so, keine Warnung wert. */
    private final java.util.TreeSet<String> doppeltStehend = new java.util.TreeSet<>();
    /**
     * Doppelnamen mit einem Bild des Spielers, fuer die schon gewarnt wurde. Konrad 20.11. (BUX9): die Vorlagen werden bei
     * jedem Modus-Start neu geladen -- "Name doppelt vergeben: confirm" stand 31 Mal in einer Nacht im Protokoll und
     * ueberdeckte echte Warnungen. Einmal je Programmstart reicht, danach nur noch im Feinprotokoll.
     */
    private final java.util.Set<String> doppeltGewarnt = new java.util.HashSet<>();

    public synchronized boolean has(String name) {
        return templates.containsKey(name);
    }

    /**
     * Liegt eine Vorlage dieses Namens im Vorlagenordner -- auch wenn sie
     * noch nicht geladen ist?
     *
     * Fuer Pruefungen VOR dem Start, etwa ob das Namensbild eines Freundes
     * schon aufgenommen wurde. has() taugt dafuer nicht: Geladen wird erst
     * beim Start, ein eben aufgenommenes Bild kennt es also noch nicht.
     */
    public synchronized boolean liegtBereit(String name) {
        if (has(name)) return true;
        String datei = name + ".png";
        try (Stream<Path> files = Files.walk(templateRoot, 3)) {
            return files.anyMatch(p -> p.getFileName().toString().equalsIgnoreCase(datei));
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized Set<String> names() {
        // Autor 30.10.: eine Kopie -- der Beobachter liest die Schilder in einem eigenen Faden, loadAll darf nicht mitten in eine Schleife fallen.
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(templates.keySet()));
    }

    /**
     * Alle Motive einer Serie (28.09.): der Grundname selbst und alles, was
     * mit Grundname + "_" anfaengt -- cw_nahrung_kiste_1, _2 ... _11.
     *
     * Vorher standen feste Obergrenzen im Code (Nahrungskisten bis 8,
     * Kathedralen-Kisten bis 6). Seit das Aufnahme-Programm mit "Weiteres
     * Motiv hinzufuegen" beliebig viele Nummern vergibt, haette Motiv 9 dort
     * einfach nicht gewirkt. Sortiert nach der Nummer am Ende, damit Motiv 10
     * nicht vor Motiv 2 kommt; Namen ohne Nummer (_alt, _ohne) stehen hinten.
     */
    public synchronized List<String> serie(String grundname) {
        String grund = grundname.endsWith("_")
                ? grundname.substring(0, grundname.length() - 1) : grundname;
        List<String> aus = new ArrayList<>();
        for (String n : templates.keySet()) {
            if (n.equals(grund) || n.startsWith(grund + "_")) aus.add(n);
        }
        aus.sort(Comparator.comparingInt((String n) -> nummerInSerie(n, grund))
                .thenComparing(n -> n));
        return aus;
    }

    /** Die Nummer am Ende eines Serien-Namens; der Grundname selbst zaehlt als 1. */
    private static int nummerInSerie(String name, String grund) {
        String rest = name.substring(grund.length());
        if (rest.isEmpty()) return 1;
        if (rest.matches("_\\d{1,6}")) return Integer.parseInt(rest.substring(1));
        return Integer.MAX_VALUE;
    }

    /**
     * Bestes Ergebnis je Vorlage im gerade untersuchten Bild -- ohne Ruecksicht
     * auf die Schwelle.
     *
     * Warum ohne Schwelle: Die Suche rechnet ohnehin immer das ganze Bild
     * durch, die Schwelle entscheidet erst hinterher, ob der Treffer zaehlt.
     * Wer den besten Treffer einmal kennt, kann damit JEDE Anfrage beantworten,
     * egal mit welcher Schwelle. Vorher wurde dieselbe Vorlage im selben Bild
     * mehrfach durchgerechnet, sobald zwei Stellen mit verschiedenen Schwellen
     * fragten -- reine Rechenlast ohne neuen Erkenntniswert.
     */
    private final Map<String, Optional<Hit>> frameBest = new HashMap<>();
    /** Das Bild, zu dem die zwischengespeicherten Ergebnisse gehoeren. */
    private Mat frameScreen = null;

    /**
     * Der beste Treffer einer Vorlage im aktuellen Bild, aus dem
     * Zwischenspeicher oder frisch gerechnet.
     *
     * Kommt ein anderes Bild als beim letzten Mal, wird der Speicher zuerst
     * geleert -- verglichen wird das Bild selbst, nicht die Zeit. Damit kann
     * nie ein veralteter Treffer benutzt werden, auch nicht bei Ausschnitten.
     */
    /**
     * Die Kaestchen der Arena {x, y, Breite, Hoehe} bei 1159 x 770 (22.10.): gemessen an 300 Fotos dreier Arenen
     * (StellenProbe), je 40 Bildpunkte Luft links/rechts und 30 oben/unten um die gefundenen Lagen. In einem Turnier
     * kosteten Suchen im ganzen Bild 174-223 s; allein ar_dwellers 581 Mal / 29 s. Nur Vorlagen mit fester Lage -- nicht
     * die, die auch anderswo gebraucht werden (ar_victory im Leuchtturm, ar_schirm_titel in der Kaserne) oder zu
     * selten gesehen wurden (ar_ended, ar_defeat, ar_item_fenster, ar_pal_titel).
     */
    static final Map<String, int[]> KAESTCHEN = Map.ofEntries(
            Map.entry("ar_dwellers", new int[]{292, 630, 283, 87}),          // "Switch dwellers" (auch Kaserne, gleiche Lage)
            Map.entry("ar_next_stage", new int[]{699, 0, 235, 58}),
            Map.entry("ar_select_opponent", new int[]{699, 0, 355, 57}),
            Map.entry("ar_your_squad", new int[]{303, 104, 211, 87}),
            Map.entry("ar_power_swords", new int[]{496, 293, 165, 124}),
            Map.entry("ar_points_label", new int[]{382, 382, 393, 85}),
            Map.entry("ar_battle_time", new int[]{470, 298, 219, 85}),
            Map.entry("ar_quick_attack", new int[]{539, 550, 263, 135}),
            Map.entry("ar_searching", new int[]{158, 422, 417, 92}),
            Map.entry("ar_dweller_next", new int[]{514, 634, 258, 120}),      // zwei Fenster, zwei Lagen
            Map.entry("ar_dweller_close", new int[]{892, 47, 267, 134}));      // zwei Fenster, zwei Lagen

    private Optional<Hit> bestHit(Mat screen, String name) {
        if (screen != frameScreen) {
            frameBest.clear();
            frameScreen = screen;
        }

        Optional<Hit> known = frameBest.get(name);
        if (known != null) return known;

        // Autor 22.10.: "Unterteil die Arena in Kaestchen. Wenn der Bot eine Sache nicht findet, soll er nicht den ganzen
        // Screen durchschauen, sondern nur bestimmte Kaestchen, wo das gesuchte Objekt sein koennte." Diese Vorlagen
        // werden NUR in ihrem Kaestchen gesucht -- auch beim ersten Mal und nach einem Neustart.
        int[] k = KAESTCHEN.get(name);
        if (k != null) {
            double fx = screen.width() / 1159.0, fy = screen.height() / 770.0;
            Rect r = new Rect((int) (k[0] * fx), (int) (k[1] * fy), (int) Math.ceil(k[2] * fx), (int) Math.ceil(k[3] * fy));
            Optional<Hit> imKaestchen = findImBereich(screen, name, r, -1);
            frameBest.put(name, imKaestchen);
            return imKaestchen;
        }

        Optional<Hit> computed = computeBest(screen, name);
        frameBest.put(name, computed);
        return computed;
    }

    // ==================================================================
    //  Nur dort suchen, wo der Knopf sein kann
    // ==================================================================
    //
    // Gemessen am 20.09.: EIN Vergleich einer Vorlage gegen das ganze Bild
    // (1159x770) kostet 33,63 ms. Ein Kathedralen-Schritt prueft rund zehn
    // Vorlagen, ein Durchlauf braucht 400 bis 500 solcher Schritte -- damit
    // gehen etwa 150 der 194 Sekunden eines Durchlaufs fuers Hinsehen drauf.
    //
    // Dabei sitzen die Knoepfe des Spiels IMMER an derselben Stelle.
    // "Quick Attack" war in Autors Protokollen jedes Mal bei 749,638.
    // Es reicht also, dort nachzusehen statt an allen 890.000 Stellen:
    //
    //     ganzes Bild 1159x770 : 33,63 ms
    //     Kasten 287x122       :  0,85 ms   -- 40x schneller
    //
    // Gerechnet wird dabei GENAU DASSELBE, nur auf einem kleineren
    // Ausschnitt. Die Uebereinstimmungswerte aendern sich nicht, die
    // Schwellen bleiben, kein Bild muss neu aufgenommen werden.
    //
    // Nicht gemacht: Graustufen (dort sehen der gruene und der rote
    // "End journey"-Knopf gleich aus) und eine grobe Vorsuche im
    // verkleinerten Bild. Letztere wurde gemessen und verworfen: eine
    // SICHTBARE Vorlage faellt im Viertelbild um bis zu 0,163, eine NICHT
    // sichtbare klettert auf 0,951. Die beiden Baender ueberlappen sich --
    // damit haette der Bot Knoepfe uebersehen.

    /** So viele verschiedene Stellen je Vorlage werden gemerkt. */
    private static final int MAX_STELLEN = 4;
    /** So viel Luft um die gemerkte Stelle herum (Pixel je Seite). */
    private static final int KASTEN_LUFT = 60;
    /** Ab diesem Wert gilt der Treffer im Kasten als der beste ueberhaupt. */
    private static final double KASTEN_VERTRAUEN = 0.80;

    /** So oft wird trotz bekannter Stelle noch einmal ganz gesucht. */
    private static final int VOLLPRUEFUNG_JEDER = 25;

    /** Wo eine Vorlage schon einmal gefunden wurde: {x, y, Bildbreite, Bildhoehe}. */
    private final Map<String, List<int[]>> stellen = new HashMap<>();
    /** Vorlagen ohne festen Platz -- die werden immer ganz gesucht. */
    private final Set<String> wandert = new HashSet<>();
    /** Wie oft eine Vorlage an ihrer bekannten Stelle schon nicht da war. */
    private final Map<String, Integer> fehlschlaege = new HashMap<>();
    /** Abfragen je Vorlage ohne bekannte Stelle -- fuer "nur jede dritte ganz". */
    private final Map<String, Integer> seltenAbfragen = new HashMap<>();
    /** Seltene Bilder, fuer die die Sparregel auch in Farbe gilt (Arena, 22.10.). */
    // Code-Pruefung 22.10.: NIE "other_device" / "update_button" sparen -- autorIstDrin() schuetzt damit Autor am Handy
    // (Vorfall 13.10.: der Bot drueckte "Try again", waehrend Autor spielte). Auch "ar_victory"/"ar_defeat" nicht: der
    // Leuchtturm entscheidet Sieg/Niederlage mit EINEM Blick.
    private static final Set<String> SELTEN_AUCH_IN_FARBE = Set.of(
            "ar_ended", "ar_return",
            "ev_view", "daily_reward", "ad_running_out",
            "event_reward", "dc_reward_text", "dialog_weiter");

    /**
     * Karla 17.10.: ihr Fenster wechselte zwischen 1157x758, 1157x762 und 1159x770 (Windows-Skalierung, zwei
     * Bildschirme). Jedes Mal galten alle gemerkten Stellen als falsch -- der Bot suchte das ganze Bild ab und
     * brauchte ~2 s je Klick statt 0,2-0,6 s. Bis 16 Bildpunkte Unterschied gilt eine Stelle weiter (der Kasten hat
     * 60 Bildpunkte Luft).
     */
    private static final int GROESSE_TOLERANZ = 16;

    private static boolean gleicheGroesse(int[] s, int breite, int hoehe) {
        return Math.abs(s[2] - breite) <= GROESSE_TOLERANZ && Math.abs(s[3] - hoehe) <= GROESSE_TOLERANZ;
    }

    /**
     * Kleine Groessenaenderung des Fensters: die Stellen bleiben, nur "nicht im Kasten = nicht da" gilt erst wieder,
     * wenn ein Knopf in der neuen Groesse gefunden wurde (bis dahin bei einem Fehlschlag das ganze Bild).
     */
    public void bestaetigungVergessen() {
        bestaetigt.clear();
        fehlschlaege.clear();
        Log.debug("Vorlagen", "Fenster um wenige Bildpunkte anders -- gemerkte Knopfstellen bleiben, werden neu bestaetigt.");
    }

    /** Wie oft verglichen wurde, und wie viel davon im kleinen Kasten. */
    private long vergleicheGesamt;
    private long vergleicheImKasten;

    /**
     * Alle gemerkten Stellen vergessen.
     *
     * Muss gerufen werden, wenn sich das Spielfenster verschoben oder seine
     * Groesse geaendert hat -- dann stimmt keine gemerkte Stelle mehr. Ohne
     * das wuerde der Bot an der alten Stelle nachsehen, nichts finden und
     * den Knopf fuer abwesend halten, obwohl er da ist.
     */
    public void stellenVergessen() {
        seltenAbfragen.clear();   // Code-Pruefung 22.10.: sonst gilt die Sparregel nach dem Vergessen sofort wieder
        if (stellen.isEmpty() && wandert.isEmpty()) return;
        stellen.clear();
        wandert.clear();
        bestaetigt.clear();
        fehlschlaege.clear();
        Log.debug("Vorlagen", "Gemerkte Knopfstellen verworfen -- das Fenster hat sich geaendert.");
    }

    // ------------------------------------------------------------------
    //  Gemerkte Stellen ueber einen Neustart retten
    // ------------------------------------------------------------------
    //
    // Autor, 24.09.: "kann man die bilderkennung nicht bei den meisten
    // vorlagen auf kastenerkennung umstellen?" -- Die Kastensuche gab es
    // schon, nur fing sie nach JEDEM Start bei null an: Jeder Knopf musste
    // erst einmal im ganzen Bild gefunden werden, und Knoepfe, die in einer
    // Sitzung nie auftauchten (Burg-Knoepfe waehrend der Kathedrale), blieben
    // die ganze Nacht teure Vollsuchen. Jetzt stehen die Stellen in einer
    // Datei und gelten ab dem ersten Bild. Jede Stelle traegt die
    // Bildgroesse mit -- passt die nicht mehr, wird sie nicht benutzt.

    /**
     * Vorlagen, die in DIESER Sitzung schon gefunden wurden. Nur fuer sie gilt
     * "nicht im Kasten = nicht da". Stellen aus der Datei sind bis dahin nur
     * ein schneller erster Blick (25.09., verpasster Clan-Krieg um 03:01).
     */
    private final Set<String> bestaetigt = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Wo die gelernten Stellen liegen (im Bot-Ordner). Der Freunde-Bot setzt
     * sie in seinen Programmordner (Ordner.bei), sein Arbeitsordner ist ein
     * anderer.
     */
    public static volatile Path STELLEN_DATEI = Path.of("vorlagen-stellen.txt");

    /** Schreibt die gemerkten Stellen in eine Datei (eine Zeile je Vorlage). */
    public void stellenSpeichern(Path datei) {
        StringBuilder b = new StringBuilder(Sprache.t("# Remembered button positions (box search). Delete = learn again.\n",
                "# Gemerkte Knopfstellen (Kastensuche). Loeschen = neu lernen.\n"));
        try {
            for (var e : new TreeMap<>(stellen).entrySet()) {
                if (e.getValue().isEmpty()) continue;
                b.append(e.getKey()).append('=');
                for (int i = 0; i < e.getValue().size(); i++) {
                    int[] s = e.getValue().get(i);
                    if (i > 0) b.append(';');
                    b.append(s[0]).append(',').append(s[1]).append(',')
                     .append(s[2]).append(',').append(s[3]);
                }
                b.append('\n');
            }
            for (String w : new TreeSet<>(wandert)) b.append(w).append("=*\n");
            Files.writeString(datei, b.toString());
        } catch (Exception e) {
            Log.debug("Vorlagen", "Knopfstellen nicht gespeichert: " + e.getMessage());
        }
    }

    /** Liest gespeicherte Stellen ein -- nur fuer Vorlagen, die es gibt. */
    public void stellenLaden(Path datei) {
        if (!Files.exists(datei)) return;
        int anzahl = 0;
        try {
            for (String zeile : Dateien.zeilen(datei)) {
                int gleich = zeile.indexOf('=');
                if (zeile.startsWith("#") || gleich <= 0) continue;
                String name = zeile.substring(0, gleich).trim();
                String rest = zeile.substring(gleich + 1).trim();
                if (!templates.containsKey(name)) continue;
                if (rest.equals("*")) {
                    wandert.add(name);
                    continue;
                }
                List<int[]> liste = new ArrayList<>();
                for (String teil : rest.split(";")) {
                    String[] z = teil.split(",");
                    if (z.length != 4) continue;
                    liste.add(new int[]{Integer.parseInt(z[0].trim()), Integer.parseInt(z[1].trim()),
                            Integer.parseInt(z[2].trim()), Integer.parseInt(z[3].trim())});
                }
                if (!liste.isEmpty() && !wandert.contains(name)) {
                    stellen.put(name, liste);
                    anzahl++;
                }
            }
            Log.info("Vorlagen", Sprache.t(anzahl + " remembered button positions loaded -- these templates "
                    + "are searched right away in the small box.",
                    anzahl + " gemerkte Knopfstellen geladen -- diese Vorlagen "
                    + "werden gleich im kleinen Kasten gesucht."));
        } catch (Exception e) {
            Log.warn("Vorlagen", Sprache.t("Button positions not readable, learning them again: ",
                    "Knopfstellen nicht lesbar, werden neu gelernt: ") + e.getMessage());
            stellen.clear();
            wandert.clear();
        }
    }

    /**
     * Wie lange die Suche im GANZEN Bild je Vorlage gedauert hat: Anzahl
     * und Nanosekunden. Damit steht nach jedem Durchlauf im Protokoll,
     * welche Vorlagen die Zeit fressen -- statt zu raten (23.09.: auf der
     * Etagenkarte rechnete der Bot 3,3 Sekunden am Stueck).
     */
    private final Map<String, long[]> zeitJeVorlage = new java.util.concurrent.ConcurrentHashMap<>();

    private void zeitMerken(String name, long t0) {
        long dauer = System.nanoTime() - t0;
        zeitJeVorlage.compute(name, (k, v) -> {
            if (v == null) return new long[]{1, dauer};
            v[0]++;
            v[1] += dauer;
            return v;
        });
    }

    /** "1234 Vergleiche, davon 1180 im kleinen Kasten (96%)." -- plus die teuersten Vorlagen. */
    public String vergleichsBericht() {
        if (vergleicheGesamt == 0) return Sprache.t("no comparisons", "keine Vergleiche");
        long anteil = Math.round(100.0 * vergleicheImKasten / vergleicheGesamt);
        StringBuilder b = new StringBuilder();
        b.append(vergleicheGesamt).append(Sprache.t(" comparisons, ", " Vergleiche, davon ")).append(vergleicheImKasten)
         .append(Sprache.t(" of them in the small box (", " im kleinen Kasten (")).append(anteil).append("%), ").append(vergleicheGrau)
         .append(Sprache.t(" in greyscale", " in Graustufen"));
        if (vergleicheImBereich > 0) {
            b.append(", ").append(vergleicheImBereich).append(Sprache.t(" in the fixed search area", " im festen Suchbereich"));
        }

        long gesamtMs = zeitJeVorlage.values().stream().mapToLong(v -> v[1]).sum() / 1_000_000;
        if (gesamtMs > 0) {
            b.append(Sprache.t(". Full picture: ", ". Ganzes Bild: ")).append(gesamtMs).append(Sprache.t(" ms, most expensive: ", " ms, am teuersten: "));
            zeitJeVorlage.entrySet().stream()
                    .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
                    .limit(8)
                    .forEach(e -> b.append(e.getKey()).append(' ')
                            .append(e.getValue()[0]).append("x/")
                            .append(e.getValue()[1] / 1_000_000).append("ms  "));
        }
        return b.toString().trim();
    }

    public void vergleicheZuruecksetzen() {
        vergleicheGesamt = 0;
        vergleicheImKasten = 0;
        vergleicheGrau = 0;
        vergleicheImBereich = 0;
        zeitJeVorlage.clear();
    }

    // ------------------------------------------------------------------
    //  Wann trotz gemerkter Stelle das ganze Bild abgesucht wird
    // ------------------------------------------------------------------
    //
    // DER FEHLER VOM 22. UND 23. SEPTEMBER. Die Kastensuche sah nur dort
    // nach, wo eine Vorlage zuletzt war, und suchte erst beim 25. Fehlversuch
    // das ganze Bild ab. Auf der Weltkarte wandert die Kathedrale aber mit
    // jedem Wischen -- nach 8 Zuegen hiess es "nicht gefunden", obwohl sie im
    // Bild war (23.09., 22:21; vermutlich auch der Abbruch nach 76 Runs am
    // 22.09.). Dieselbe Verzoegerung traf jedes Fenster, das an einer neuen
    // Stelle aufging.
    //
    // Deshalb zwei Regeln:
    //   1. Orte auf der Weltkarte ("..._node") werden immer ganz gesucht.
    //   2. Hat der Modus ein Bild nicht erkannt, wird das NAECHSTE Bild
    //      ueberall abgesucht (BotEngine ruft naechstesBildGruendlich).

    // ------------------------------------------------------------------
    //  Graustufen fuer die Suche im ganzen Bild
    // ------------------------------------------------------------------
    //
    // Gemessen am 23.09. im Testlauf: 141 Sekunden je Kathedralen-Run,
    // davon 98 Sekunden fuer die Suche im GANZEN Bild -- vor allem Knoepfe,
    // die fast nie da sind (Join squad, Update, Move on, End journey), und
    // die deshalb keine gemerkte Stelle haben. In Graustufen ist dieselbe
    // Suche rund viermal schneller (ein Kanal statt drei).
    //
    // Sicher ist das fuer 324 von 338 Vorlagen (Pruefung vom 20.09., OFFEN.md
    // 3a). Die 14 unsicheren bleiben in Farbe, dazu vorsichtshalber: alle
    // End-journey-Knoepfe (gruen gegen rot sieht grau gleich aus), alle
    // Ziffern, und die sieben Vorlagen, die erst nach der Pruefung dazukamen.
    // Die Suche im kleinen Kasten bleibt ohnehin in Farbe.

    /** Auf false setzen, und alles laeuft wieder wie vorher in Farbe. */
    private static final boolean GRAU_AN = true;

    private static final Set<String> NUR_FARBE = Set.of(
            "ar_log_lost", "cw_digit_0", "cw_digit_1", "cw_digit_2", "cw_digit_9",
            "dc_done_empty", "dc_end_journey", "digit_3", "ev_refresh_gelb",
            "ev_wand_fertig", "key_3", "key_5", "key_8", "unit_dead_bar",
            // nach dem 20.09. dazugekommen, nicht geprueft:
            "dc_guardian_lair", "dc_guardian_text", "dc_reward_text", "dc_visited",
            "dc_text_altar", "dc_text_arena", "dc_text_camp", "dc_text_danger",
            "dc_text_skirmish");

    private final Map<String, Mat> grauVorlagen = new HashMap<>();
    private Mat grauFuerSchirm = null;
    private Mat grauSchirmBild = null;
    private long vergleicheGrau;

    private static boolean nurFarbe(String name) {
        return NUR_FARBE.contains(name)
                // Der Clan-Krieg laeuft viermal am Tag, Tempo ist dort egal --
                // Sicherheit nicht (Titanen- gegen normalen Turm). Bleibt Farbe.
                || name.startsWith("cw_") || name.startsWith("clanwar")
                || name.contains("end_journey")
                || name.startsWith("digit_") || name.startsWith("key_")
                || name.startsWith("cw_digit_") || name.contains("_digit_")
                // Arena (24.09.): Sieg/Niederlage, Ticket an/aus, Haekchen und
                // die Artefakte unterscheidet oft nur die Farbe.
                || name.startsWith("ar_log_") || name.startsWith("ar_banner_")
                || name.startsWith("ar_art_") || name.startsWith("ar_artschlecht_") || name.startsWith("ar_totem_") || name.startsWith("ar_totemschlecht_") || name.startsWith("ar_for_tickets")
                || name.equals("ar_victory") || name.equals("ar_defeat")
                || name.equals("ar_check");
    }

    /** Fuer welchen Modus Graustufen gelten -- setzt der Motor beim Start eines Modus. */
    private volatile boolean grauErlaubt = false;

    public void setGrauErlaubt(boolean erlaubt) { this.grauErlaubt = erlaubt; }

    /**
     * Der Faden des Bots, der die Bilder holt (gesetzt in {@link #neuesBild}).
     * Nur er sucht in Graustufen. Prueft nebenher Discord etwas im Bild (etwa
     * "ist der Chat offen?"), sucht das in Farbe -- sonst raeumten sich die
     * beiden gegenseitig das zwischengespeicherte Graubild weg.
     */
    private volatile Thread bildFaden = null;

    private boolean grauHier() {
        return grauErlaubt && Thread.currentThread() == bildFaden;
    }

    /** Die Vorlage in Graustufen, oder null wenn sie in Farbe gesucht werden muss. */
    private Mat grauVorlage(String name, Mat template) {
        if (!GRAU_AN || !grauHier() || nurFarbe(name)) return null;
        return grauVorlagen.computeIfAbsent(name, k -> {
            Mat g = new Mat();
            Imgproc.cvtColor(template, g, Imgproc.COLOR_BGR2GRAY);
            return g;
        });
    }

    /** Das aktuelle Bild in Graustufen, je Bild nur einmal umgerechnet. */
    private Mat grauSchirm(Mat screen) {
        if (screen != grauFuerSchirm || grauSchirmBild == null) {
            if (grauSchirmBild != null) grauSchirmBild.release();
            grauSchirmBild = new Mat();
            Imgproc.cvtColor(screen, grauSchirmBild, Imgproc.COLOR_BGR2GRAY);
            grauFuerSchirm = screen;
        }
        return grauSchirmBild;
    }

    private volatile boolean gruendlich = false;
    private volatile boolean gruendlichNaechstes = false;

    /** Das naechste Bild ohne gemerkte Stellen absuchen. */
    public void naechstesBildGruendlich() { gruendlichNaechstes = true; }

    /** Wird bei jedem neuen Bild gerufen (BotContext.refresh). */
    public synchronized void neuesBild() {
        gruendlich = gruendlichNaechstes;
        gruendlichNaechstes = false;
        grauFuerSchirm = null;   // neues Bild: Graustufen neu rechnen
        alleFuerSchirm = null;   // ... und die Mehrfachsuchen auch
        bildFaden = Thread.currentThread();
    }

    /** Merkt sich, wo eine Vorlage gefunden wurde. */
    private void stelleMerken(String name, Hit hit, Mat screen) {
        if (wandert.contains(name)) return;
        if (name.endsWith("_node")) {
            wandert.add(name);
            return;
        }
        List<int[]> liste = stellen.computeIfAbsent(name, k -> new ArrayList<>());
        for (int[] s : liste) {
            if (Math.abs(s[0] - hit.x()) <= 8 && Math.abs(s[1] - hit.y()) <= 8) return;
        }
        if (liste.size() >= MAX_STELLEN) {
            // Diese Vorlage taucht ueberall auf -- Merken lohnt nicht mehr.
            wandert.add(name);
            stellen.remove(name);
            return;
        }
        liste.add(new int[]{hit.x(), hit.y(), screen.width(), screen.height()});
    }

    /** Ab so viel Abstand zu allen gemerkten Stellen gilt ein Fund als weitere Stelle. */
    private static final int ZWEITE_STELLE_AB = 12;

    /**
     * DERSELBE KNOPF, ANDERES FENSTER (27.09., Wuerfel-Haenger): "Auswahl
     * abschliessen" sitzt im Wuerfel-Fenster 28 px tiefer als bei Dangerous
     * Game. Die Suche hier fand ihn trotzdem (60 px Luft), gemerkt blieb aber
     * nur die erste Stelle -- und der Kastenblick mit seinen 14 px Luft sah
     * ihn nie und wartete 4 s. Liegt ein sicherer Fund deutlich neben allen
     * gemerkten Stellen, wird er deshalb als weitere Stelle gemerkt.
     *
     * Anders als stelleMerken() macht das nie eine Vorlage zur "wandernden":
     * Ist die Liste voll, bleibt alles, wie es ist.
     */
    private void weitereStelleMerken(String name, Hit hit, Mat screen) {
        List<int[]> liste = stellen.get(name);
        if (liste == null || liste.size() >= MAX_STELLEN || wandert.contains(name)) return;
        for (int[] s : liste) {
            if (!gleicheGroesse(s, screen.width(), screen.height())) continue;
            if (Math.abs(s[0] - hit.x()) <= ZWEITE_STELLE_AB
                    && Math.abs(s[1] - hit.y()) <= ZWEITE_STELLE_AB) return;
        }
        liste.add(new int[]{hit.x(), hit.y(), screen.width(), screen.height()});
        Log.debug("Vorlagen", "Weitere Stelle gemerkt: " + name + " bei " + hit.x() + "," + hit.y());
    }

    /**
     * Sucht die Vorlage nur in den Kaesten um ihre bekannten Stellen.
     * Leer heisst "hier war nichts Gutes" -- dann wird ganz gesucht.
     */
    private Optional<Hit> imKastenSuchen(Mat screen, String name, Mat template) {
        List<int[]> liste = stellen.get(name);
        if (liste == null || liste.isEmpty()) return Optional.empty();

        Hit beste = null;
        for (int[] s : liste) {
            // Ein anderer Bildausschnitt als beim Merken: Stelle gilt nicht.
            if (!gleicheGroesse(s, screen.width(), screen.height())) continue;

            int x0 = Math.max(0, s[0] - KASTEN_LUFT);
            int y0 = Math.max(0, s[1] - KASTEN_LUFT);
            int x1 = Math.min(screen.width(),  s[0] + template.width()  + KASTEN_LUFT);
            int y1 = Math.min(screen.height(), s[1] + template.height() + KASTEN_LUFT);
            if (x1 - x0 < template.width() || y1 - y0 < template.height()) continue;

            Mat kasten = new Mat(screen, new org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0));
            Mat ergebnis = new Mat();
            try {
                Imgproc.matchTemplate(kasten, template, ergebnis, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mm = Core.minMaxLoc(ergebnis);
                vergleicheGesamt++;
                vergleicheImKasten++;
                if (beste == null || mm.maxVal > beste.score()) {
                    beste = new Hit(name, x0 + (int) mm.maxLoc.x, y0 + (int) mm.maxLoc.y,
                            template.width(), template.height(), mm.maxVal);
                }
            } finally {
                ergebnis.release();
                kasten.release();
            }
        }
        // Den besten Fund zurueckgeben, EGAL wie gut er ist. Ueber die
        // Schwelle entscheidet der Aufrufer, nicht diese Methode -- sie
        // kennt sie gar nicht. Manche Stellen fragen mit 0,60 (etwa das
        // Reise-Ende); wuerde hier alles unter 0,80 verschwiegen, wuerde
        // genau dieser Knopf uebersehen.
        return Optional.ofNullable(beste);
    }

    // ------------------------------------------------------------------
    //  Kastenblick (27.09.)
    // ------------------------------------------------------------------
    //
    // Autor, 27.09.: "so viele Fotozyklen wie moeglich, und wenn es geht noch
    // etwas Schnelleres". Gemessen: Ein Blick des Bots kostete 60 bis 75 ms
    // (ganzes Bild umrechnen, verkleinern, vergleichen, dazu die Pause). Das
    // Foto selbst dauert nur 7 ms -- gleich, wie gross es ist. Wird nur die
    // kleine Stelle umgerechnet und verglichen, an der der erwartete Knopf
    // sitzt, kostet ein Blick rund 8 ms.

    /**
     * Die bekannten Stellen einer Vorlage als Kaesten {x, y, breite, hoehe}
     * im Fenster, mit etwas Luft drumherum. Leer, wenn keine Stelle bekannt
     * ist oder sie zu einer anderen Fenstergroesse gehoert.
     */
    public synchronized List<int[]> kaesten(String name, int fensterBreite, int fensterHoehe, int luft) {
        List<int[]> aus = new ArrayList<>();
        Mat template = holen(name);
        List<int[]> liste = stellen.get(name);
        if (template == null || liste == null) return aus;
        for (int[] s : new ArrayList<>(liste)) {
            if (!gleicheGroesse(s, fensterBreite, fensterHoehe)) continue;
            int x0 = Math.max(0, s[0] - luft);
            int y0 = Math.max(0, s[1] - luft);
            int x1 = Math.min(fensterBreite, s[0] + template.width() + luft);
            int y1 = Math.min(fensterHoehe, s[1] + template.height() + luft);
            if (x1 - x0 < template.width() || y1 - y0 < template.height()) continue;
            aus.add(new int[]{x0, y0, x1 - x0, y1 - y0});
        }
        return aus;
    }

    /**
     * Vergleicht die Vorlage mit einem kleinen Ausschnitt, der bei (ax, ay)
     * im Fenster liegt. Derselbe Vergleich wie im Kasten des ganzen Bildes --
     * gleiche Werte, gleiche Schwellen. Der Treffer steht in Fensterkoordinaten.
     */
    public synchronized Optional<Hit> imAusschnitt(Mat ausschnitt, int ax, int ay, String name) {
        Mat template = holen(name);
        if (template == null || ausschnitt == null || ausschnitt.empty()
                || template.width() > ausschnitt.width()
                || template.height() > ausschnitt.height()) {
            return Optional.empty();
        }
        Mat ergebnis = new Mat();
        try {
            Imgproc.matchTemplate(ausschnitt, template, ergebnis, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(ergebnis);
            vergleicheGesamt++;
            vergleicheImKasten++;
            Hit treffer = new Hit(name, ax + (int) mm.maxLoc.x, ay + (int) mm.maxLoc.y,
                    template.width(), template.height(), mm.maxVal);
            if (treffer.score() >= KASTEN_VERTRAUEN) bestaetigt.add(name);
            return Optional.of(treffer);
        } finally {
            ergebnis.release();
        }
    }

    /** Rechnet den besten Treffer aus. Leer, wenn die Vorlage nicht taugt. */
    private Optional<Hit> computeBest(Mat screen, String name) {
        Mat template = vorlage(name);
        if (template == null) {
            Log.warn("Vorlagen", Sprache.t("Unknown template requested: ", "Unbekannte Vorlage angefragt: ") + name);
            return Optional.empty();
        }
        if (template.width() > screen.width() || template.height() > screen.height()) {
            Log.warn("Vorlagen", Sprache.t("Template larger than the screen: " + name + " (template "
                    + template.width() + "x" + template.height() + ", screen " + screen.width() + "x" + screen.height() + ")",
                    "Vorlage groesser als der Bildschirm: " + name + " (Vorlage "
                    + template.width() + "x" + template.height() + ", Bild " + screen.width() + "x" + screen.height() + ")"));
            return Optional.empty();
        }

        // Zuerst dort nachsehen, wo die Vorlage schon einmal war. Das ist
        // derselbe Vergleich auf einem kleineren Ausschnitt -- gleiche Werte,
        // gleiche Schwellen, nur 40 mal schneller.
        boolean stelleBekannt = !gruendlich && stellen.containsKey(name) && !stellen.get(name).isEmpty();
        if (stelleBekannt) {
            Optional<Hit> imKasten = imKastenSuchen(screen, name, template);

            // Sitzt der Knopf da, ist die Sache erledigt.
            if (imKasten.isPresent() && imKasten.get().score() >= KASTEN_VERTRAUEN) {
                bestaetigt.add(name);
                if (imKasten.get().score() >= DEFAULT_THRESHOLD) {
                    weitereStelleMerken(name, imKasten.get(), screen);
                }
                return imKasten;
            }

            // NUR AUS DER DATEI GELADEN, IN DIESER SITZUNG NOCH NIE GESEHEN:
            // dann heisst "nicht im Kasten" NICHT "nicht da" (25.09.).
            //
            // Um 03:02 fand der Clan-Krieg das Kriegssymbol nicht, weil die
            // Burg anders verschoben war als beim Krieg davor -- der Bot sah
            // an der gespeicherten Stelle nach, fand nichts und meldete "es
            // laeuft kein Krieg". Genauso die Knoepfe im Truppenfenster, die
            // je Turm woanders sitzen. Deshalb gilt eine geladene Stelle nur
            // als schneller erster Blick; bis der Knopf in dieser Sitzung
            // einmal gefunden wurde, wird wie frueher das ganze Bild gesucht.
            if (!bestaetigt.contains(name)) {
                return ganzesBild(screen, name, template, true);
            }

            // NICHTS IM KASTEN HEISST: DER KNOPF IST NICHT DA.
            //
            // Das ist der Schritt, auf den es ankommt. Der haeufigste Fall
            // ist naemlich nicht "Knopf gefunden", sondern "Knopf gerade
            // nicht da" -- und genau dafuer wurde bisher jedes Mal das ganze
            // Bild durchgerechnet. Gemessen: von 209 Vorlagen waren auf einem
            // Etagenbild 13 sichtbar. 196 Vergleiche fuer nichts.
            //
            // Die Knoepfe des Spiels sitzen immer an derselben Stelle. Ist
            // die Stelle leer, ist der Knopf weg. Verschiebt sich das
            // Fenster, verwirft BotContext alle Stellen (stellenVergessen).
            //
            // Trotzdem wird ab und zu ganz gesucht, damit ein Knopf, der an
            // einer NEUEN Stelle auftaucht, dort gelernt wird. Jeder
            // fuenfundzwanzigste Fehlschlag kostet 33 ms -- im Schnitt also
            // 1,3 ms obendrauf statt 33.
            int fehl = fehlschlaege.merge(name, 1, Integer::sum);
            if (fehl % VOLLPRUEFUNG_JEDER != 0) {
                // Nicht "leer" melden, sondern den schwachen Fund aus dem
                // Kasten. Wer mit einer niedrigen Schwelle fragt, bekommt so
                // seine Antwort; wer mit der ueblichen fragt, bekommt wie
                // bisher ein Nein. Leer zu melden haette den Unterschied
                // verschluckt.
                return imKasten;
            }
        }
        return ganzesBild(screen, name, template, !stelleBekannt);
    }

    /**
     * Die Suche im ganzen Bild. {@code unbekannt}: keine (bestaetigte) Stelle
     * -- dann gilt die Sparregel unten.
     */
    private Optional<Hit> ganzesBild(Mat screen, String name, Mat template, boolean unbekannt) {
        // NOCH NIE GESEHEN: nur jede dritte Abfrage im ganzen Bild.
        //
        // Gemessen am 23.09. (Kathedrale, Graustufen): Die meiste Zeit ging an
        // Knoepfe, die in dieser Sitzung noch nie da waren -- update_button
        // 307x, dc_ally_screen 282x, switch_dwellers 200x je Durchlauf. Ohne
        // bekannte Stelle bleibt nur das ganze Bild, jedes Mal. Jetzt wird bei
        // zwei von drei Abfragen "nicht da" geantwortet. Taucht so ein Knopf
        // zum ersten Mal auf, wird er also hoechstens zwei Bilder spaeter
        // erkannt (rund 0,3 s) -- danach ist seine Stelle bekannt. Und nach
        // drei unbekannten Bildern hintereinander wird ohnehin alles
        // gruendlich abgesucht. Vorerst nur, wo auch Graustufen gelten.
        //
        // NICHT fuer Orte auf der Weltkarte ("..._node") und nicht fuer
        // wandernde Knoepfe: Dort loest ein "nicht da" sofort etwas aus (die
        // Karte wird verschoben) -- ein uebersprungenes Bild waere ein
        // falscher Zug. Und erst nach zehn vergeblichen Vollsuchen: Was gleich
        // erwartet wird, soll ohne Verzoegerung gefunden werden.
        // 22.10. (Helfer B, Ideen B02/B04): in der Arena gilt kein Grau, also auch die Sparregel nicht. Ein Turnier
        // kostete so: ar_ended 1229x/40,9 s, ar_defeat 1147x/28,1 s, update_button 19,1 s, ev_view 14,8 s, ar_return
        // 13,8 s, other_device 13,1 s -- Bilder, die einmal je Turnier oder nie kommen. Fuer sie gilt die Regel jetzt
        // auch in Farbe (nur im Bild-Faden; Discord fragt weiter jedes Mal ganz).
        boolean sparen = grauHier() || (Thread.currentThread() == bildFaden && SELTEN_AUCH_IN_FARBE.contains(name));
        if (unbekannt && sparen && !gruendlich
                && !name.endsWith("_node") && !wandert.contains(name)) {
            int n = seltenAbfragen.merge(name, 1, Integer::sum);
            // 24.09. abends: jede sechste statt jede dritte. Das sind fast
            // nur Knoepfe aus Verlies und Burg, die die Kathedrale zur
            // Sicherheit mitprueft (participate, tab_torches, switch_dwellers
            // ...) -- zusammen rund 20 s Rechenzeit je Run. Seit ihre Stellen
            // gespeichert werden, trifft es sie ohnehin nur, bis sie EINMAL
            // gesehen wurden.
            if (n > 10 && n % 6 != 1) return Optional.empty();
        }

        // Sparsam 20.11.: statt im ganzen Bild nur im festen Suchbereich der Vorlage (Suchbereiche, belegt an 1544
        // Freunde-Berichten). Gleiche Rechnung auf einem Ausschnitt. Jede 25. Suche und jedes gruendliche Bild gehen
        // weiter ueber das ganze Bild; liegt der Fund dort ausserhalb, gilt der Bereich fuer diese Vorlage nicht mehr.
        Rect bereich = bereichFuer(name, screen, template);
        if (bereich != null) return imBereichSuchen(screen, name, template, bereich);

        // Ganzes Bild: beim ersten Mal, nach einem Fensterwechsel, und bei
        // der gelegentlichen Nachpruefung.
        Mat result = new Mat();
        try {
            long t0 = System.nanoTime();
            Mat grauVorlage = grauVorlage(name, template);
            Mat grauSchirm = grauVorlage != null ? grauSchirm(screen) : null;
            if (grauVorlage != null && grauSchirm != null) {
                Imgproc.matchTemplate(grauSchirm, grauVorlage, result, Imgproc.TM_CCOEFF_NORMED);
                vergleicheGrau++;
            } else {
                Imgproc.matchTemplate(screen, template, result, Imgproc.TM_CCOEFF_NORMED);
            }
            zeitMerken(name, t0);
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);
            vergleicheGesamt++;
            Hit treffer = new Hit(name, (int) mm.maxLoc.x, (int) mm.maxLoc.y,
                    template.width(), template.height(), mm.maxVal);
            // Nur sichere Treffer merken. Ein schwacher Bestwert ist nur die
            // hellste Stelle im Rauschen und waere als Stelle wertlos.
            if (treffer.score() >= DEFAULT_THRESHOLD) {
                stelleMerken(name, treffer, screen);
                bestaetigt.add(name);
                bereichPruefen(name, treffer, screen, template);
            }
            return Optional.of(treffer);
        } finally {
            result.release();
        }
    }

    // ------------------------------------------------------------------
    //  Feste Suchbereiche (Sparsam 20.11.)
    // ------------------------------------------------------------------

    /** Schalter: false = immer das ganze Bild wie vor dem 20.11. */
    static volatile boolean BEREICHE_AN = true;
    /** Vorlagen, die ausserhalb ihres Bereichs gefunden wurden -- fuer sie gilt bis zum Neustart wieder das ganze Bild. */
    private final Set<String> bereichAus = new HashSet<>();
    /** Suchen je Vorlage mit festem Bereich (fuer "jede 25. ganz"). */
    private final Map<String, Integer> bereichAbfragen = new HashMap<>();
    private long vergleicheImBereich;

    /** feste-suchbereiche.txt "aus" = wie vor dem 20.11. immer das ganze Bild (ohne Datei: an). Hoechstens je Minute gelesen. */
    private static final Schalterdatei BEREICHE_SCHALTER = new Schalterdatei(Ordner.bei("feste-suchbereiche.txt"), "Suchbereiche", true);
    private static volatile boolean bereicheSchalterAn = true;
    private static volatile long bereicheSchalterGelesen;

    private static boolean bereicheErlaubt() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - bereicheSchalterGelesen > 60_000) {
            bereicheSchalterGelesen = jetzt;
            bereicheSchalterAn = BEREICHE_SCHALTER.lesen();
        }
        return BEREICHE_AN && bereicheSchalterAn;
    }

    /** Der feste Suchbereich fuer diese Suche, oder null = ganzes Bild. */
    private Rect bereichFuer(String name, Mat screen, Mat template) {
        if (gruendlich || bereichAus.contains(name) || !Suchbereiche.hat(name) || !bereicheErlaubt()) return null;
        int n = bereichAbfragen.merge(name, 1, Integer::sum);
        if (n % VOLLPRUEFUNG_JEDER == 0) return null;   // Nachpruefung im ganzen Bild
        return Suchbereiche.bereich(name, screen.width(), screen.height(), template.width(), template.height());
    }

    /** Dieselbe Suche wie im ganzen Bild (gleiche Farbe/Graustufen, gleiche Werte), nur im Bereich. */
    private Optional<Hit> imBereichSuchen(Mat screen, String name, Mat template, Rect bereich) {
        Mat grauV = grauVorlage(name, template);
        Mat quelle = grauV != null ? grauSchirm(screen) : screen;
        Mat ausschnitt = new Mat(quelle, bereich);
        Mat result = new Mat();
        try {
            long t0 = System.nanoTime();
            Imgproc.matchTemplate(ausschnitt, grauV != null ? grauV : template, result, Imgproc.TM_CCOEFF_NORMED);
            zeitMerken(name, t0);
            if (grauV != null) vergleicheGrau++;
            vergleicheGesamt++;
            vergleicheImBereich++;
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);
            Hit treffer = new Hit(name, bereich.x + (int) mm.maxLoc.x, bereich.y + (int) mm.maxLoc.y,
                    template.width(), template.height(), mm.maxVal);
            if (treffer.score() >= DEFAULT_THRESHOLD) {
                stelleMerken(name, treffer, screen);
                bestaetigt.add(name);
            }
            return Optional.of(treffer);
        } finally {
            result.release();
            ausschnitt.release();
        }
    }

    /** Sicherer Fund im ganzen Bild AUSSERHALB des festen Bereichs: Bereich fuer diese Vorlage abschalten. */
    private void bereichPruefen(String name, Hit treffer, Mat screen, Mat template) {
        if (bereichAus.contains(name) || !Suchbereiche.hat(name)) return;
        Rect b = Suchbereiche.bereich(name, screen.width(), screen.height(), template.width(), template.height());
        if (b == null) return;
        boolean drin = treffer.x() >= b.x && treffer.y() >= b.y
                && treffer.x() + treffer.width() <= b.x + b.width && treffer.y() + treffer.height() <= b.y + b.height;
        if (drin) return;
        bereichAus.add(name);
        Log.info("Vorlagen", Sprache.t(name + " found outside its search area (at " + treffer.x() + "," + treffer.y()
                        + ") -- searching the whole picture for it again from now on.",
                name + " ausserhalb ihres Suchbereichs gefunden (bei " + treffer.x() + "," + treffer.y()
                        + ") -- fuer diese Vorlage ab jetzt wieder das ganze Bild."));
    }

    /** Sucht die beste Fundstelle einer Vorlage. Leer, wenn nichts sicher genug passt. */
    public synchronized Optional<Hit> find(Mat screen, String name, double threshold) {
        Optional<Hit> best = bestHit(screen, name);
        if (best.isEmpty() || best.get().score() < threshold) return Optional.empty();

        report(best.get());
        eigenVielleicht(screen, best.get());   // Autor 17.10.: sichere Funde als eigene Vorlage (nur Freunde-Bot)
        return best;
    }

    public synchronized Optional<Hit> find(Mat screen, String name) {
        return find(screen, name, DEFAULT_THRESHOLD);
    }

    /**
     * Die beste Stelle einer Vorlage nur INNERHALB eines Bereichs (Event-Spielfeld, 28.09.).
     *
     * Gerechnet wird genau dasselbe wie im ganzen Bild, nur auf dem Ausschnitt:
     * An jeder Stelle im Bereich kommt derselbe Wert heraus. Das Feld ist etwa
     * ein Sechstel des Bildes -- entsprechend schneller. Beruehrt weder den
     * Zwischenspeicher je Bild noch die gemerkten Stellen.
     */
    public synchronized Optional<Hit> findImBereich(Mat screen, String name, Rect bereich, double threshold) {
        Mat template = holen(name);
        if (template == null) return Optional.empty();
        int x = Math.max(0, bereich.x);
        int y = Math.max(0, bereich.y);
        int w = Math.min(screen.width(), bereich.x + bereich.width) - x;
        int h = Math.min(screen.height(), bereich.y + bereich.height) - y;
        if (w < template.width() || h < template.height()) return Optional.empty();

        Mat grauVorlage = grauVorlage(name, template);
        Mat grauSchirm = grauVorlage != null ? grauSchirm(screen) : null;
        boolean grau = grauVorlage != null && grauSchirm != null;
        Mat ausschnitt = new Mat(grau ? grauSchirm : screen, new Rect(x, y, w, h));
        Mat result = new Mat();
        try {
            Imgproc.matchTemplate(ausschnitt, grau ? grauVorlage : template, result, Imgproc.TM_CCOEFF_NORMED);
            vergleicheGesamt++;
            vergleicheImKasten++;
            if (grau) vergleicheGrau++;
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);
            if (mm.maxVal < threshold) return Optional.empty();
            Hit treffer = new Hit(name, x + (int) mm.maxLoc.x, y + (int) mm.maxLoc.y,
                    template.width(), template.height(), mm.maxVal);
            report(treffer);
            return Optional.of(treffer);
        } finally {
            result.release();
            ausschnitt.release();
        }
    }

    /** Vorlagen in halber Groesse fuer findAllSchnell, je Vorlage einmal gerechnet. */
    private final Map<String, Mat> halbeVorlagen = new java.util.concurrent.ConcurrentHashMap<>();
    private Mat halberSchirm;
    private Mat halberSchirmVon;

    /**
     * Alle Stellen im Bereich, an denen die Vorlage mindestens {@code schwelle}
     * passt -- SCHNELL (Event, 29.09.): erst im halb so grossen Bild suchen
     * (35 Belohnungs-Vorlagen im Feld: 70 statt 300 ms), jede Stelle ab
     * {@code vorSchwelle} dann in voller Groesse in einem kleinen Kasten
     * nachpruefen -- die Werte sind dieselben wie bei findImBereich. Gemessen an
     * 69 echten Treffern: halb liegt hoechstens 0,09 unter voll, die Vorschwelle
     * sollte also gut 0,1 unter der Schwelle liegen.
     */
    public synchronized List<Hit> findAllSchnell(Mat screen, String name, Rect bereich, double vorSchwelle,
                                                 double schwelle, int maxHits) {
        List<Hit> treffer = new ArrayList<>();
        Mat template = holen(name);
        if (template == null) return treffer;
        int x = Math.max(0, bereich.x) & ~1;
        int y = Math.max(0, bereich.y) & ~1;
        int w = Math.min(screen.width(), bereich.x + bereich.width) - x;
        int h = Math.min(screen.height(), bereich.y + bereich.height) - y;
        if (w < template.width() || h < template.height()) return treffer;
        if (halberSchirmVon != screen || halberSchirm == null) {
            if (halberSchirm != null) halberSchirm.release();
            halberSchirm = new Mat();
            Imgproc.resize(screen, halberSchirm, new org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA);
            halberSchirmVon = screen;
        }
        Mat halb = halbeVorlagen.computeIfAbsent(name, n -> {
            Mat k = new Mat();
            Imgproc.resize(template, k, new org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA);
            return k;
        });
        int hx = x / 2, hy = y / 2;
        int hw = Math.min(halberSchirm.width() - hx, w / 2), hh = Math.min(halberSchirm.height() - hy, h / 2);
        if (hw < halb.width() || hh < halb.height()) return treffer;
        Mat ausschnitt = new Mat(halberSchirm, new Rect(hx, hy, hw, hh));
        Mat result = new Mat();
        try {
            Imgproc.matchTemplate(ausschnitt, halb, result, Imgproc.TM_CCOEFF_NORMED);
            vergleicheGesamt++;
            vergleicheImKasten++;
            for (int versuch = 0; versuch < maxHits + 3 && treffer.size() < maxHits; versuch++) {
                Core.MinMaxLocResult mm = Core.minMaxLoc(result);
                if (mm.maxVal < vorSchwelle) break;
                int vx = x + (int) mm.maxLoc.x * 2, vy = y + (int) mm.maxLoc.y * 2;
                // Nachbarschaft loeschen, damit dieselbe Stelle nicht zweimal kommt.
                Imgproc.rectangle(result,
                        new org.opencv.core.Point(mm.maxLoc.x - halb.width() / 2.0, mm.maxLoc.y - halb.height() / 2.0),
                        new org.opencv.core.Point(mm.maxLoc.x + halb.width() / 2.0, mm.maxLoc.y + halb.height() / 2.0),
                        new org.opencv.core.Scalar(-1), -1);
                findImBereich(screen, name, new Rect(vx - 6, vy - 6, template.width() + 12, template.height() + 12), schwelle)
                        .ifPresent(treffer::add);
            }
        } finally {
            result.release();
            ausschnitt.release();
        }
        return treffer;
    }

    /** Prueft mehrere Vorlagen und liefert den ersten Treffer in der angegebenen Reihenfolge. */
    public synchronized Optional<Hit> findFirst(Mat screen, double threshold, String... names) {
        for (String name : names) {
            Optional<Hit> hit = find(screen, name, threshold);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    public synchronized boolean visible(Mat screen, String name) {
        return find(screen, name).isPresent();
    }

    public synchronized boolean visible(Mat screen, String name, double threshold) {
        return find(screen, name, threshold).isPresent();
    }

    /**
     * Findet alle Fundstellen einer Vorlage. Wird fuer die Wegpunkte gebraucht,
     * von denen mehrere gleichzeitig auf der Karte liegen.
     */
    public synchronized List<Hit> findAll(Mat screen, String name, double threshold, int maxHits) {
        Mat template = holen(name);
        if (template == null) return List.of();

        // Je Bild nur einmal rechnen (24.09.). Ein Schritt auf der Etagenkarte
        // fragt dieselben Listen mehrmals ab (offene Stuetzpunkte bis zu
        // dreimal, Fragezeichen bis zu viermal) -- jedes Mal mit rund zwanzig
        // Suchen ueber das ganze Bild. Gleiches Bild, gleiche Frage, gleiche
        // Antwort: also merken. Nur im Faden des Bots (siehe bildFaden).
        boolean merken = ALLE_MERKEN && Thread.currentThread() == bildFaden;
        String schluessel = name + "|" + threshold + "|" + maxHits;
        if (merken) {
            if (screen != alleFuerSchirm) {
                alleImBild.clear();
                alleFuerSchirm = screen;
            }
            List<Hit> bekannt = alleImBild.get(schluessel);
            if (bekannt != null) return new ArrayList<>(bekannt);
        }
        List<Hit> ergebnis = findAllRechnen(screen, name, template, threshold, maxHits);
        if (merken) alleImBild.put(schluessel, ergebnis);
        return new ArrayList<>(ergebnis);
    }

    /**
     * Wie findAll, aber nur im Bereich (Sparsam 20.11.): dieselbe Rechnung auf einem Ausschnitt -- jede Stelle im
     * Bereich bekommt denselben Wert wie im ganzen Bild. Fuer Aufrufer, die Funde ausserhalb ohnehin verwerfen
     * (Verlies: tote Einheiten nur unterhalb der Truppenzeile). Treffer in Bildkoordinaten.
     */
    public synchronized List<Hit> findAllImBereich(Mat screen, String name, double threshold, int maxHits, Rect bereich) {
        Mat template = holen(name);
        if (template == null) return List.of();
        int x = Math.max(0, bereich.x), y = Math.max(0, bereich.y);
        int w = Math.min(screen.width(), bereich.x + bereich.width) - x;
        int h = Math.min(screen.height(), bereich.y + bereich.height) - y;
        if (w < template.width() || h < template.height()) return List.of();
        if (x == 0 && y == 0 && w == screen.width() && h == screen.height()) return findAll(screen, name, threshold, maxHits);
        boolean merken = ALLE_MERKEN && Thread.currentThread() == bildFaden;
        String schluessel = name + "|" + threshold + "|" + maxHits + "|" + x + "," + y + "," + w + "," + h;
        if (merken) {
            if (screen != alleFuerSchirm) {
                alleImBild.clear();
                alleFuerSchirm = screen;
            }
            List<Hit> bekannt = alleImBild.get(schluessel);
            if (bekannt != null) return new ArrayList<>(bekannt);
        }
        Mat grauV = grauVorlage(name, template);
        Mat quelle = grauV != null ? grauSchirm(screen) : screen;
        Mat ausschnitt = new Mat(quelle, new Rect(x, y, w, h));
        List<Hit> ergebnis = new ArrayList<>();
        try {
            for (Hit t : searchAll(ausschnitt, grauV != null ? grauV : template, name, threshold, maxHits)) {
                ergebnis.add(new Hit(t.name(), t.x() + x, t.y() + y, t.width(), t.height(), t.score()));
            }
            if (grauV != null) vergleicheGrau++;
            vergleicheImBereich++;
        } finally {
            ausschnitt.release();
        }
        ergebnis.forEach(this::report);
        if (merken) alleImBild.put(schluessel, ergebnis);
        return new ArrayList<>(ergebnis);
    }

    // ------------------------------------------------------------------
    //  Suche in Kaesten um bekannte Stellen (Kathedrale, 21.11.)
    // ------------------------------------------------------------------
    //
    // Autor 21.11.: "Der Bot erkennt am Anfang, welches Muster die Etage hat, und sucht dann nur noch an den Positionen
    // der Wegpunkte." Die Kaesten beschreiben, wo die MITTE eines Treffers liegen darf; gesucht wird darin mit der
    // Vorlage drumherum. Gerechnet wird je Stelle genau dasselbe wie im ganzen Bild (gleiche Farbe/Graustufen, gleiche
    // Werte, gleiche Schwellen) -- nur an weniger Stellen.

    /**
     * Der Suchkasten fuer eine Vorlage, deren MITTE in {@code mitte} liegen darf: die Mitte plus die Vorlage drumherum
     * (und 1 Punkt Luft), auf das Bild beschnitten. Null, wenn die Vorlage nicht hineinpasst.
     */
    public static Rect suchKasten(Rect mitte, int vorlageB, int vorlageH, int bildB, int bildH) {
        int x0 = Math.max(0, mitte.x - vorlageB / 2 - 1);
        int y0 = Math.max(0, mitte.y - vorlageH / 2 - 1);
        int x1 = Math.min(bildB, mitte.x + mitte.width + (vorlageB - vorlageB / 2) + 1);
        int y1 = Math.min(bildH, mitte.y + mitte.height + (vorlageH - vorlageH / 2) + 1);
        if (x1 - x0 < vorlageB || y1 - y0 < vorlageH) return null;
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * Wie findAll, aber nur dort, wo die MITTE eines Treffers in einem der Kaesten liegen darf. Je Kasten dieselbe
     * Rechnung wie im ganzen Bild; danach wie dort: der beste zuerst, was naeher als die halbe Vorlage an einem
     * besseren liegt, faellt weg, hoechstens maxHits. Je Bild nur einmal gerechnet (wie findAll).
     */
    public synchronized List<Hit> findAllInKaesten(Mat screen, String name, double threshold, int maxHits, List<Rect> mitten) {
        Mat template = holen(name);
        if (template == null || mitten == null) return List.of();
        boolean merken = ALLE_MERKEN && Thread.currentThread() == bildFaden;
        StringBuilder sb = new StringBuilder(name).append('|').append(threshold).append('|').append(maxHits).append("|K");
        for (Rect m : mitten) sb.append(m.x).append(',').append(m.y).append(',').append(m.width).append(',').append(m.height).append(';');
        String schluessel = sb.toString();
        if (merken) {
            if (screen != alleFuerSchirm) {
                alleImBild.clear();
                alleFuerSchirm = screen;
            }
            List<Hit> bekannt = alleImBild.get(schluessel);
            if (bekannt != null) return new ArrayList<>(bekannt);
        }
        Mat grauV = grauVorlage(name, template);
        Mat quelle = grauV != null ? grauSchirm(screen) : screen;
        List<Hit> alle = new ArrayList<>();
        for (Rect m : mitten) {
            Rect k = suchKasten(m, template.width(), template.height(), screen.width(), screen.height());
            if (k == null) continue;
            Mat ausschnitt = new Mat(quelle, k);
            try {
                for (Hit t : searchAll(ausschnitt, grauV != null ? grauV : template, name, threshold, maxHits)) {
                    alle.add(new Hit(t.name(), t.x() + k.x, t.y() + k.y, t.width(), t.height(), t.score()));
                }
            } finally {
                ausschnitt.release();
            }
            if (grauV != null) vergleicheGrau++;
            vergleicheImBereich++;
        }
        List<Hit> ergebnis = zusammenlegen(alle, template.width(), template.height(), maxHits);
        ergebnis.forEach(this::report);
        if (merken) alleImBild.put(schluessel, ergebnis);
        return new ArrayList<>(ergebnis);
    }

    /**
     * Wie find, aber nur dort, wo die MITTE des Treffers in einem der Kaesten liegen darf: der beste Wert aller Kaesten
     * (dieselbe Rechnung wie im ganzen Bild). Leer, wenn er unter der Schwelle liegt.
     */
    public synchronized Optional<Hit> findInKaesten(Mat screen, String name, List<Rect> mitten, double threshold) {
        List<Hit> h = findAllInKaesten(screen, name, threshold, 1, mitten);
        if (h.isEmpty()) return Optional.empty();
        eigenVielleicht(screen, h.get(0));
        return Optional.of(h.get(0));
    }

    /**
     * Wie findAllSchnell, aber je Kasten (die MITTE eines Treffers liegt im Kasten), beschnitten auf {@code grenze}
     * (null = ganzes Bild). Dieselbe Vorsuche in halber Groesse und dieselbe Nachpruefung in voller Groesse -- nur
     * kleinere Flaechen. Zusammengelegt wie bei findAllInKaesten.
     */
    public synchronized List<Hit> findAllSchnellInKaesten(Mat screen, String name, List<Rect> mitten, Rect grenze,
                                                          double vorSchwelle, double schwelle, int maxHits) {
        Mat template = holen(name);
        if (template == null || mitten == null) return List.of();
        List<Hit> alle = new ArrayList<>();
        for (Rect m : mitten) {
            Rect k = suchKasten(m, template.width(), template.height(), screen.width(), screen.height());
            if (k == null) continue;
            if (grenze != null) {
                int x0 = Math.max(k.x, grenze.x), y0 = Math.max(k.y, grenze.y);
                int x1 = Math.min(k.x + k.width, grenze.x + grenze.width), y1 = Math.min(k.y + k.height, grenze.y + grenze.height);
                if (x1 - x0 < template.width() || y1 - y0 < template.height()) continue;
                k = new Rect(x0, y0, x1 - x0, y1 - y0);
            }
            alle.addAll(findAllSchnell(screen, name, k, vorSchwelle, schwelle, maxHits));
        }
        return zusammenlegen(alle, template.width(), template.height(), maxHits);
    }

    /** Treffer aus mehreren Kaesten wie bei der Suche im ganzen Bild: bester zuerst, Nachbarn eines besseren fallen weg. */
    static List<Hit> zusammenlegen(List<Hit> alle, int breite, int hoehe, int maxHits) {
        List<Hit> sortiert = new ArrayList<>(alle);
        sortiert.sort((p, q) -> Double.compare(q.score(), p.score()));
        List<Hit> aus = new ArrayList<>();
        for (Hit t : sortiert) {
            if (aus.size() >= maxHits) break;
            boolean nah = false;
            for (Hit a : aus) {
                if (Math.abs(a.x() - t.x()) <= breite / 2 && Math.abs(a.y() - t.y()) <= hoehe / 2) {
                    nah = true;
                    break;
                }
            }
            if (!nah) aus.add(t);
        }
        return aus;
    }

    /** Schalter fuer den findAll-Zwischenspeicher (Messblock 3 am 24.09.). */
    private static final boolean ALLE_MERKEN = true;

    /** Zwischenspeicher fuer findAll: Ergebnisse zum Bild alleFuerSchirm. */
    private final Map<String, List<Hit>> alleImBild = new HashMap<>();
    private Mat alleFuerSchirm = null;

    private List<Hit> findAllRechnen(Mat screen, String name, Mat template,
                                     double threshold, int maxHits) {
        // Auch die Mehrfach-Suche in Graustufen, wo es sicher ist (24.09.):
        // Auf der Etagenkarte laufen je Blick rund zwanzig solcher Suchen
        // ueber das ganze Bild, in Farbe je ~29 ms -- zusammen gut eine halbe
        // Sekunde, bevor der Bot den naechsten Wegpunkt anklicken kann, und
        // das rund 25 Mal je Kathedralen-Run. Was nur in Farbe sicher ist
        // (besuchte Felder, Ziffern ...), bleibt in Farbe (nurFarbe).
        Mat grauV = grauVorlage(name, template);
        List<Hit> hits;
        if (grauV != null) {
            hits = searchAll(grauSchirm(screen), grauV, name, threshold, maxHits);
            vergleicheGrau++;
        } else {
            hits = searchAll(screen, template, name, threshold, maxHits);
        }
        hits.forEach(this::report);
        return hits;
    }

    /**
     * Wie findAll, aber ohne Farbe -- beide Bilder werden vorher in
     * Graustufen umgerechnet.
     *
     * WOFUER. Manche Anzeigen im Spiel sind mal bunt und mal ausgegraut,
     * ohne dass sich etwas an ihrer Bedeutung aendert. Das Schild "Your
     * squad" ueber einem Turm der Kriegskarte ist so eines: Liegt es hinter
     * einem anderen Schild, zeichnet das Spiel es blass. Autor dazu: "your
     * squad ist immernoch your squad egal ob grau oder nicht."
     *
     * Fuer den Farbvergleich sind das zwei verschiedene Bilder -- Tuerkis
     * und Grau haben in den drei Farbkanaelen voellig andere Werte, und die
     * Uebereinstimmung faellt unter die Schwelle. In Graustufen bleibt
     * dagegen die Form: derselbe Schriftzug, dasselbe Band, nur etwas
     * heller. Und weil TM_CCOEFF_NORMED ohnehin auf den Mittelwert
     * normiert, faellt selbst der Helligkeitsunterschied kaum ins Gewicht.
     *
     * Nicht ueberall verwenden: Wo die Farbe die Bedeutung TRAEGT -- ein
     * gruener gegen einen roten Knopf, ein roter gegen einen normalen Apfel
     * --, waere Grau genau die falsche Wahl.
     */
    public synchronized List<Hit> findAllGrau(Mat screen, String name, double threshold, int maxHits) {
        Mat template = holen(name);
        if (template == null) return List.of();

        Mat grauSchirm = new Mat();
        Mat grauVorlage = new Mat();
        try {
            Imgproc.cvtColor(screen, grauSchirm, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(template, grauVorlage, Imgproc.COLOR_BGR2GRAY);
            List<Hit> hits = searchAll(grauSchirm, grauVorlage, name, threshold, maxHits);
            hits.forEach(this::report);
            return hits;
        } catch (Exception e) {
            Log.debug("Vorlagen", "Graustufensuche nach " + name + " ging schief: "
                    + e.getMessage() + " -- es wird in Farbe gesucht.");
            return findAll(screen, name, threshold, maxHits);
        } finally {
            grauSchirm.release();
            grauVorlage.release();
        }
    }

    /**
     * Die eigentliche Suche nach mehreren Fundstellen. Nimmt ein fertiges Bild
     * statt eines Namens, damit auch zugeschnittene oder verkleinerte Vorlagen
     * gesucht werden koennen.
     */
    private List<Hit> searchAll(Mat screen, Mat template, String name,
                                double threshold, int maxHits) {
        List<Hit> hits = new ArrayList<>();
        if (template.width() > screen.width() || template.height() > screen.height()) return hits;

        // Zaehlt mit, damit der Bericht nach dem Durchlauf stimmt. findAll
        // sucht immer das ganze Bild ab -- die Wegpunkte koennen ueberall
        // liegen, da hilft keine gemerkte Stelle.
        vergleicheGesamt++;

        Mat result = new Mat();
        try {
            long t0 = System.nanoTime();
            Imgproc.matchTemplate(screen, template, result, Imgproc.TM_CCOEFF_NORMED);
            zeitMerken(name, t0);

            for (int i = 0; i < maxHits; i++) {
                Core.MinMaxLocResult mm = Core.minMaxLoc(result);
                if (mm.maxVal < threshold) break;

                hits.add(new Hit(name, (int) mm.maxLoc.x, (int) mm.maxLoc.y,
                        template.width(), template.height(), mm.maxVal));

                // Gefundenen Bereich ausblenden, damit der naechste Durchlauf
                // nicht denselben Treffer erneut liefert.
                int x0 = Math.max(0, (int) mm.maxLoc.x - template.width() / 2);
                int y0 = Math.max(0, (int) mm.maxLoc.y - template.height() / 2);
                int x1 = Math.min(result.cols(), (int) mm.maxLoc.x + template.width() / 2);
                int y1 = Math.min(result.rows(), (int) mm.maxLoc.y + template.height() / 2);
                if (x1 > x0 && y1 > y0) {
                    Imgproc.rectangle(result, new Point(x0, y0), new Point(x1, y1), new Scalar(0), -1);
                }
            }
        } finally {
            result.release();
        }
        return hits;
    }

    /**
     * Sucht nur den KERN einer Vorlage -- ihre Mitte ohne die Raender -- und
     * das wahlweise in mehreren Groessen.
     *
     * Wozu das gut ist: Manche Knoepfe kommen im selben Bild zweimal vor. Der
     * "Finish selection" der Kathedrale etwa steht gross unten rechts und noch
     * einmal schmaler im Warnfenster darueber. Die Beschriftung ist dieselbe,
     * der gruene Knopf aussen herum nicht -- eine Vorlage vom ganzen Knopf
     * findet deshalb immer nur einen von beiden. Wer nur die Beschriftung
     * sucht, findet beide.
     *
     * keepX und keepY sagen, wie viel von Breite und Hoehe uebrig bleibt:
     * 0.55 behaelt die mittleren 55 Prozent. scales sind die Groessen, in
     * denen gesucht wird: 1.0 ist die Vorlage selbst, 0.85 eine kleinere.
     * Damit ist es egal, ob die Schrift im zweiten Knopf etwas kleiner ist.
     *
     * Die Treffer kommen nach Guete sortiert zurueck, der beste zuerst.
     */
    /**
     * Sucht nur den OBEREN Teil einer Vorlage.
     *
     * Fuer Knoepfe mit einer wechselnden zweiten Zeile. Der Teilnahmeknopf
     * der Arena ist der Anlass: aufgenommen wurde er in der Essens-Fassung
     * mit "Participate" und darunter "Apfel 90 000". Der Bot spielt aber
     * fuer Tickets, und dort steht darunter "Ticket 1". Die untere Zeile ist
     * also IMMER anders -- die ganze Vorlage konnte damit nie passen, und
     * der Bot stand vor dem Turnierfenster und tat nichts.
     *
     * findCore() hilft hier nicht: das schneidet aus der MITTE, und die
     * liegt genau zwischen den beiden Zeilen.
     *
     * @param keepY wie viel von oben behalten wird, 0.55 heisst die oberen
     *              fuenfundfuenfzig Prozent
     */
    public synchronized Optional<Hit> findOberteil(Mat screen, String name, double keepY, double threshold) {
        Mat template = vorlage(name);
        if (template == null) {
            Log.warn("Vorlagen", Sprache.t("Unknown template requested: ", "Unbekannte Vorlage angefragt: ") + name);
            return Optional.empty();
        }

        int hoehe = Math.max(8, (int) (template.height() * keepY));

        // Der Ausschnitt wird eigens freigegeben.
        //
        // new Mat(vorlage, rechteck) legt keinen neuen Bildinhalt an, nur
        // einen Kopf, der auf den alten zeigt -- aber auch der gehoert
        // freigegeben. Frueher stand hier ein Einzeiler mit .clone() daran,
        // und dieser Kopf blieb liegen. Einmal je Bild, die ganze Nacht.
        Mat sicht = new Mat(template, new Rect(0, 0, template.width(), hoehe));
        Mat oben;
        try {
            oben = sicht.clone();
        } finally {
            sicht.release();
        }
        try {
            List<Hit> treffer = searchAll(screen, oben, name, threshold, 1);
            if (treffer.isEmpty()) return Optional.empty();
            Hit bester = treffer.get(0);
            report(bester);
            return Optional.of(bester);
        } finally {
            oben.release();
        }
    }

    public synchronized List<Hit> findCore(Mat screen, String name, double keepX, double keepY,
                              double[] scales, double threshold, int maxHits) {
        Mat template = holen(name);
        if (template == null) return List.of();

        int coreW = Math.max(8, (int) (template.width() * keepX));
        int coreH = Math.max(8, (int) (template.height() * keepY));
        int coreX = (template.width() - coreW) / 2;
        int coreY = (template.height() - coreH) / 2;

        // Wie bei findOberteil: den Ausschnittskopf eigens freigeben.
        Mat kernSicht = new Mat(template, new Rect(coreX, coreY, coreW, coreH));
        Mat core;
        try {
            core = kernSicht.clone();
        } finally {
            kernSicht.release();
        }
        List<Hit> found = new ArrayList<>();
        try {
            for (double scale : scales) {
                if (scale == 1.0) {
                    found.addAll(searchAll(screen, core, name, threshold, maxHits));
                    continue;
                }
                Mat scaled = new Mat();
                try {
                    Imgproc.resize(core, scaled, new Size(
                            Math.max(8, Math.round(coreW * scale)),
                            Math.max(8, Math.round(coreH * scale))));
                    found.addAll(searchAll(screen, scaled, name, threshold, maxHits));
                } finally {
                    scaled.release();
                }
            }
        } finally {
            core.release();
        }

        // Dieselbe Stelle schlaegt in mehreren Groessen an. Pro Stelle bleibt
        // nur der beste Treffer uebrig.
        found.sort(Comparator.comparingDouble(Hit::score).reversed());
        List<Hit> unique = new ArrayList<>();
        for (Hit hit : found) {
            boolean alreadyKnown = false;
            for (Hit kept : unique) {
                if (Math.abs(kept.centerX() - hit.centerX()) < 40
                        && Math.abs(kept.centerY() - hit.centerY()) < 40) {
                    alreadyKnown = true;
                    break;
                }
            }
            if (alreadyKnown) continue;
            unique.add(hit);
            report(hit);
            if (unique.size() >= maxHits) break;
        }
        return unique;
    }

    // ------------------------------------------------------------------
    //  Weisse Schrift, gleich auf welchem Grund
    // ------------------------------------------------------------------

    /** Ab dieser Helligkeit (des schwaechsten Farbkanals) gilt ein Punkt als weiss. */
    private static final double WEISS_HELL = 185;
    /** Hoechstens so weit duerfen die drei Farbkanaele auseinanderliegen -- sonst ist es eine Farbe. */
    private static final double WEISS_FARBE = 30;

    /** Vorlagen, zu denen die Warnung "kaum weisse Schrift" schon im Protokoll steht. */
    private final Set<String> ohneWeissGemeldet = new HashSet<>();

    /**
     * Die weissen Stellen eines Bildausschnitts -- einmal ausgerechnet, fuer
     * beliebig viele Vergleiche mit weisseSchrift(). Nach Gebrauch schliessen
     * (try-with-resources), sonst bleibt Speicher von OpenCV belegt.
     */
    public static final class WeissBild implements AutoCloseable {
        private final Mat maske;
        private final int x0;
        private final int y0;

        private WeissBild(Mat maske, int x0, int y0) {
            this.maske = maske;
            this.x0 = x0;
            this.y0 = y0;
        }

        @Override
        public void close() {
            maske.release();
        }
    }

    /**
     * Schneidet den Bereich aus dem Bild und macht daraus die Maske der
     * weissen Stellen, siehe weisseSchrift(). Null, wenn vom Bereich im Bild
     * zu wenig uebrig bleibt.
     *
     * bereich grenzt die Suche ein -- im ganzen Bild gibt es zu viel weisse
     * Schrift, die zufaellig aehnlich aussieht.
     */
    public synchronized WeissBild weissBild(Mat screen, Rect bereich) {
        int x0 = Math.max(0, bereich.x);
        int y0 = Math.max(0, bereich.y);
        int x1 = Math.min(screen.cols(), bereich.x + bereich.width);
        int y1 = Math.min(screen.rows(), bereich.y + bereich.height);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;

        Mat sicht = new Mat(screen, new Rect(x0, y0, x1 - x0, y1 - y0));
        try {
            return new WeissBild(weissMaske(sicht), x0, y0);
        } finally {
            sicht.release();
        }
    }

    /**
     * Wie weissBild, aber jede HELLE Schrift zaehlt, auch farbige (27.09.):
     * In der Chat-Einladung stehen Namen von Clan-Mitgliedern HELLGRUEN statt
     * weiss ("Paulaskelett"). Die weisse Maske sah darin gar nichts, der Name
     * passte zu 0 % -- die Einladung wurde nie angenommen. Auf dem dunklen
     * Petrol des Chats ist "hell" eindeutig genug: der hellste Farbkanal
     * zaehlt. Verglichen wird weiter mit der weissen Maske der Vorlage.
     */
    public synchronized WeissBild hellBild(Mat screen, Rect bereich) {
        int x0 = Math.max(0, bereich.x);
        int y0 = Math.max(0, bereich.y);
        int x1 = Math.min(screen.cols(), bereich.x + bereich.width);
        int y1 = Math.min(screen.rows(), bereich.y + bereich.height);
        if (x1 - x0 < 8 || y1 - y0 < 8) return null;
        Mat sicht = new Mat(screen, new Rect(x0, y0, x1 - x0, y1 - y0));
        List<Mat> kanaele = new ArrayList<>();
        Mat max = new Mat();
        Mat maske = new Mat();
        try {
            Core.split(sicht, kanaele);
            Core.max(kanaele.get(0), kanaele.get(1), max);
            Core.max(max, kanaele.get(2), max);
            Imgproc.threshold(max, maske, HELL_SCHRIFT, 255, Imgproc.THRESH_BINARY);
            Imgproc.GaussianBlur(maske, maske, new Size(3, 3), 0);
            return new WeissBild(maske, x0, y0);
        } finally {
            sicht.release();
            max.release();
            kanaele.forEach(Mat::release);
        }
    }

    /** Ab dieser Helligkeit (hellster Farbkanal) gilt ein Punkt im Chat als Schrift. */
    private static final double HELL_SCHRIFT = 170;

    /**
     * Wie gut passt ein WEISSER Schriftzug in den Ausschnitt -- gleich, auf
     * welchem Grund er steht, und auch etwas groesser oder kleiner als in der
     * Vorlage?
     *
     * WOFUER. Den Namen eines Freundes nimmt Autor in der Freundesliste auf:
     * weisse Buchstaben auf hellem Tuerkis. In der Einladung im Chat steht
     * derselbe Name weiss auf dunklem Petrol, und gut ein Siebtel groesser.
     * Der gewoehnliche Vergleich haelt das fuer zwei verschiedene Bilder --
     * der Grund macht den groessten Teil der Flaeche aus.
     *
     * Deshalb werden hier nur die weissen Stellen verglichen. Beide Bilder
     * werden zu Schwarz-Weiss-Masken: weiss ist, was hell ist UND keine Farbe
     * hat. Das Tuerkis der Liste (186/244/246) hat Farbe und faellt heraus,
     * das Petrol des Chats ist zu dunkel. Uebrig bleiben nur die Buchstaben.
     * Nachgemessen an Autors sieben Freundesbildern: 22 bis 30 Prozent jedes
     * Bildes sind Buchstaben, der Grund faellt ganz heraus.
     *
     * Zurueck kommt der beste Treffer ueber alle Groessen, OHNE Schwelle --
     * ob er reicht, entscheidet der Aufrufer. Deshalb geht er auch nicht an
     * die Vorschau: FreundesChat misst jeden Namen gegen alle Freunde, und die
     * Vorschau zeigte sonst bei jedem Blick in den Chat Kaesten fuer Freunde,
     * die gar nicht da sind.
     */
    public synchronized Optional<Hit> weisseSchrift(WeissBild bild, String name, double[] scales) {
        Mat template = holen(name);
        if (bild == null || template == null) return Optional.empty();

        Mat maskeVorlage = weissMaske(template);
        try {
            // Ein Name ohne Weiss -- etwa in Farbe geschrieben -- taugt hierfuer
            // nicht. Einmal sagen reicht: Gefragt wird bei jedem Blick in den Chat.
            if (Core.countNonZero(maskeVorlage) < 20) {
                if (ohneWeissGemeldet.add(name)) {
                    Log.warn("Vorlagen", Sprache.t(name + " has hardly any white lettering. The comparison by "
                            + "letters does not work with it.",
                            name + " hat kaum weisse Schrift. Der Vergleich ueber "
                            + "die Buchstaben geht damit nicht."));
                }
                return Optional.empty();
            }
            Hit bester = null;
            for (double scale : scales) {
                Mat probe = maskeVorlage;
                if (scale != 1.0) {
                    probe = new Mat();
                    Imgproc.resize(maskeVorlage, probe, new Size(
                            Math.max(8, Math.round(template.width() * scale)),
                            Math.max(8, Math.round(template.height() * scale))));
                }
                try {
                    List<Hit> h = searchAll(bild.maske, probe, name, 0.0, 1);
                    if (!h.isEmpty() && (bester == null || h.get(0).score() > bester.score())) {
                        Hit t = h.get(0);
                        bester = new Hit(name, t.x() + bild.x0, t.y() + bild.y0, t.width(),
                                t.height(), t.score());
                    }
                } finally {
                    if (probe != maskeVorlage) probe.release();
                }
            }
            return Optional.ofNullable(bester);
        } finally {
            maskeVorlage.release();
        }
    }

    /**
     * Macht aus einem Farbbild eine Maske: 255, wo es weiss ist, sonst 0 --
     * leicht weichgezeichnet, damit ein vergroesserter Buchstabe nicht an
     * einem einzigen Bildpunkt Versatz scheitert.
     */
    private static Mat weissMaske(Mat bgr) {
        List<Mat> kanaele = new ArrayList<>();
        Core.split(bgr, kanaele);
        Mat min = new Mat();
        Mat max = new Mat();
        Mat spanne = new Mat();
        Mat hell = new Mat();
        Mat farblos = new Mat();
        Mat maske = new Mat();
        try {
            Core.min(kanaele.get(0), kanaele.get(1), min);
            Core.min(min, kanaele.get(2), min);
            Core.max(kanaele.get(0), kanaele.get(1), max);
            Core.max(max, kanaele.get(2), max);
            Core.subtract(max, min, spanne);
            Imgproc.threshold(min, hell, WEISS_HELL, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(spanne, farblos, WEISS_FARBE, 255, Imgproc.THRESH_BINARY_INV);
            Core.bitwise_and(hell, farblos, maske);
            Imgproc.GaussianBlur(maske, maske, new Size(3, 3), 0);
            return maske;
        } finally {
            kanaele.forEach(Mat::release);
            min.release();
            max.release();
            spanne.release();
            hell.release();
            farblos.release();
        }
    }

    public synchronized List<Hit> findAll(Mat screen, String name) {
        return findAll(screen, name, DEFAULT_THRESHOLD, 30);
    }

    /** Meldet, welche der erwarteten Vorlagen fehlen. Hilft beim Einrichten. */
    public synchronized List<String> missing(Collection<String> required) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (!templates.containsKey(name)) missing.add(name);
        }
        return missing;
    }
}
