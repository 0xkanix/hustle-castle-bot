package de.hustlebot.core;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Eigene Schrifterkennung fuer Spielernamen in der Arena -- ohne Windows-Texterkennung (Autor 24.10.).
 *
 * Anlass: "OMYth Gods" wurde als "-UMPM-", "Gods", "-UVIPh-", "Myth 5Qds" und "V Myth Gods" gelesen, jedes
 * Mal anders -- die Sperrliste erkannte ihn deshalb nie wieder. Schmuckschrift (verzierte Unicode-Buchstaben),
 * Sonderzeichen und Umlaute liest Windows oft gar nicht oder jedes Mal anders.
 *
 * So geht es:
 *  - GlyphenZerlegung schneidet das Namensbild in einzelne Zeichen (auch Satzzeichen).
 *  - Jedes Zeichen wird mit der Glyphen-Bibliothek verglichen (glyphen\<nummer>.png + glyphen\glyphen.txt im
 *    Bot-Ordner). Passt keins gut genug, bekommt es eine neue Nummer. Welcher Buchstabe das ist, muss dafuer
 *    niemand wissen.
 *  - Der FINGERABDRUCK eines Namens ist die Folge der Nummern, z. B. "4.17.9.9.23". Derselbe Name ergibt
 *    (fast) dieselbe Folge, egal wie Windows ihn liest. derselbeName() verzeiht ein falsches Zeichen je vier.
 *  - Nebenbei lernt die Bibliothek die echten Zeichen: Ist die Windows-Lesung sauber und hat sie genau so viele
 *    Zeichen wie das Bild, bekommt jede Glyphe eine Stimme fuer ihren Buchstaben (die Mehrheit gewinnt).
 *    Verzierte Buchstaben (z. B. 𝔍 oder 𝓜), Chinesisch, Arabisch usw. kann man mit beschriften() einmal von
 *    Hand zuordnen -- gespeichert wird das exakte Unicode-Zeichen (Datei in UTF-8). lesen() gibt den Namen
 *    so zurueck, wie er im Spiel steht, unbekannte Zeichen als "?".
 *  - Zu jedem Fingerabdruck wird EIN Namensbild abgelegt (glyphen\namen\<kurz>.png), damit Notizblock und
 *    Bot-Programm den Namen als Bild zeigen koennen, solange die Zeichen nicht bekannt sind.
 *
 * Gemessen 24.10. an 207 Namensbildern des Bots und 301 Namensausschnitten aus Arena-Fotos: Zerlegen und
 * Vergleichen dauert je Name rund 0,5-2 ms (ohne Windows-Texterkennung).
 */
public final class Glyphenschrift {

    /** Ordner der Bibliothek im Bot-Ordner. */
    public static final Path ORDNER = Path.of("glyphen");

    /**
     * Ab diesem Abstand ist ein Zeichen neu. Gemessen 24.10. an 246 Paaren desselben Spielers aus Arena-Fotos:
     * derselbe Buchstabe zweier Aufnahmen 0,07 (Mitte) bis 0,15 (98 von 100); verschiedene Kleinbuchstaben nur zu
     * 0,8 % darunter. 0,14/0,16/0,18/0,20 durchprobiert: 0,18 gab die meisten gleichen Fingerabdruecke (160 von 246).
     */
    static final double NEU_AB = 0.18;

    /**
     * Zwei Glyphen naeher als das gelten beim Namensvergleich als "vielleicht dasselbe Zeichen" (zwei Nummern fuer
     * denselben Buchstaben, weil eine Aufnahme knapp ueber NEU_AB lag). Gemessen 24.10.: damit 240 von 246 Paaren
     * desselben Spielers erkannt, 0 von 31.254 Paaren klar verschiedener Namen verwechselt.
     */
    static final double VERWANDT = 0.24;

    /** Beschriftung "gehoert zum Zeichen davor, nichts anzeigen" (z. B. rechte Haelfte eines chinesischen Zeichens). */
    public static final String TEIL = "\u200D";
    private static final String TEIL_IN_DATEI = "[teil]";

    private static Glyphenschrift standard;

    private final Path ordner;
    private final TreeMap<Integer, Glyphe> glyphen = new TreeMap<>();
    /** Namensbilder: kurz -> Fingerabdruck (aus glyphen\namen\namen.txt). */
    private final Map<String, String> namen = new LinkedHashMap<>();
    private boolean geladen, geaendert;
    private long zuletztGespeichert = System.currentTimeMillis();
    private boolean namensbilderSpeichern = true;

    /** Die Bibliothek im Bot-Ordner (glyphen\). */
    public static synchronized Glyphenschrift standard() {
        if (standard == null) standard = new Glyphenschrift(ORDNER);
        return standard;
    }

    /** Bibliothek in einem eigenen Ordner (Tests, Pruefprogramm). */
    public Glyphenschrift(Path ordner) {
        this.ordner = ordner;
    }

    /** Ergebnis fuer ein Namensbild: Fingerabdruck, die Glyphen-Nummern, der gelesene Text ("?" = unbekannt). */
    public record Ergebnis(String fingerabdruck, int[] nummern, String text, int neueGlyphen, List<Schriftbild> bilder) {
        /** Ohne Schriftbild (Stand vor dem 21.11.). */
        public Ergebnis(String fingerabdruck, int[] nummern, String text, int neueGlyphen) {
            this(fingerabdruck, nummern, text, neueGlyphen, List.of());
        }

        public Ergebnis {
            bilder = bilder == null ? List.of() : List.copyOf(bilder);
        }

        public boolean leer() { return nummern.length == 0; }

        /** Autor 21.11.: hat das Namensbild ein Schriftbild (der Name als kleines Bild, siehe Schriftbild)? */
        public boolean hatBild() { return !bilder.isEmpty(); }

        /** Dasselbe Ergebnis mit einem weiteren Schriftbild (z. B. aus dem Vergleichsfenster); null = unveraendert. */
        public Ergebnis mitBild(Schriftbild b) {
            if (b == null) return this;
            List<Schriftbild> l = new ArrayList<>(bilder);
            l.add(b);
            return new Ergebnis(fingerabdruck, nummern, text, neueGlyphen, l);
        }

        /** Nur ein Schriftbild, ohne Glyphen (leer() ist dann true). null bei null. */
        public static Ergebnis nurBild(Schriftbild b) {
            return b == null ? null : new Ergebnis("", new int[0], "", 0, List.of(b));
        }
    }

    /** Namensbilder ablegen ja/nein (Pruefprogramm schaltet es ab). */
    public synchronized void setNamensbilderSpeichern(boolean ja) {
        namensbilderSpeichern = ja;
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Erkennen
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Zerlegt das Namensbild, ordnet jedes Zeichen einer Glyphe zu (neue Zeichen bekommen eine neue Nummer) und
     * legt beim ersten Mal ein Namensbild zum Fingerabdruck ab. Nie null.
     */
    public synchronized Ergebnis erkennen(BufferedImage bild) {
        laden();
        GlyphenZerlegung.Zerlegung z = GlyphenZerlegung.zerlegen(bild);
        int[] nr = new int[z.anzahl()];
        int neu = 0;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < z.anzahl(); i++) {
            GlyphenZerlegung.Stueck s = z.stuecke().get(i);
            Glyphe g = naechste(s, NEU_AB);
            if (g == null) {
                g = anlegen(s);
                neu++;
            }
            g.gesehen++;
            nr[i] = g.nummer;
            if (s.leerDavor() && text.length() > 0) text.append(' ');
            String zeichen = g.zeichen();
            text.append(zeichen == null ? "?" : TEIL.equals(zeichen) ? "" : zeichen);
        }
        geaendert |= nr.length > 0;
        String fp = fingerabdruck(nr);
        if (nr.length > 0 && namensbilderSpeichern) namensbildAblegen(bild, z.kasten(), fp, text.toString());
        if (neu > 0 || System.currentTimeMillis() - zuletztGespeichert > 60_000) speichern();
        // Autor 21.11.: dazu der ganze Name als kleines Bild (Schriftbild) -- vergleicht auch, wenn die Zerlegung kippt
        Schriftbild sb = nr.length > 0 ? Schriftbild.aus(bild, Schriftbild.SCHILD) : null;
        return new Ergebnis(fp, nr, text.toString(), neu, sb == null ? List.of() : List.of(sb));
    }

    /** Fingerabdruck eines Namensbildes, z. B. "4.17.9.9.23" (leer, wenn keine Schrift zu sehen ist). */
    public String fingerabdruck(BufferedImage bild) {
        return erkennen(bild).fingerabdruck();
    }

    /** Liest den Namen mit den gelernten bzw. von Hand zugeordneten Zeichen; Unbekanntes als "?". */
    public String lesen(BufferedImage bild) {
        return erkennen(bild).text();
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Lernen und Beschriften
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Lernt die Zeichen aus einer sauberen Windows-Lesung: nur wenn die Lesung (ohne Leerzeichen) genau so viele
     * Zeichen hat wie das Bild Glyphen. Jede Glyphe bekommt eine Stimme fuer ihr Zeichen; die Mehrheit ueber
     * viele Faelle gewinnt, eine einzelne Fehllesung schadet also nicht. true = gelernt.
     */
    public synchronized boolean lernen(BufferedImage bild, String windowsLesung) {
        if (sauber(windowsLesung) == null) return false;
        return lernen(erkennen(bild), windowsLesung);
    }

    /** Wie oben, fuer ein Bild, das schon mit erkennen() zerlegt wurde (spart die zweite Zerlegung). */
    public synchronized boolean lernen(Ergebnis e, String windowsLesung) {
        String sauber = sauber(windowsLesung);
        if (sauber == null || e == null) return false;
        laden();
        int[] cps = sauber.codePoints().toArray();
        if (cps.length != e.nummern().length) return false;
        for (int i = 0; i < cps.length; i++) {
            Glyphe g = glyphen.get(e.nummern()[i]);
            if (g != null) g.stimmen.merge(new String(Character.toChars(cps[i])), 1, Integer::sum);
        }
        geaendert = true;
        if (System.currentTimeMillis() - zuletztGespeichert > 60_000) speichern();
        return true;
    }

    /**
     * Ordnet einer Glyphe von Hand ihr Zeichen zu -- jedes Unicode-Zeichen, auch verzierte (𝔍, 𝓜), Chinesisch
     * oder Arabisch. "" heisst: gehoert zum Zeichen davor, nichts anzeigen (zweite Haelfte eines breiten
     * Zeichens). null nimmt die Zuordnung von Hand wieder weg. Von Hand geht immer vor gelernt.
     */
    public synchronized boolean beschriften(int nummer, String zeichen) {
        laden();
        Glyphe g = glyphen.get(nummer);
        if (g == null) return false;
        if (zeichen == null) g.vonHand = null;
        else {
            String z = Normalizer.normalize(zeichen, Normalizer.Form.NFC).strip();
            if (z.indexOf('\t') >= 0 || z.indexOf('\n') >= 0 || z.indexOf('\r') >= 0) return false;
            g.vonHand = z.isEmpty() ? TEIL : z;
        }
        geaendert = true;
        speichern();
        return true;
    }

    /** Das Zeichen einer Glyphe (von Hand, sonst die Mehrheit der Stimmen); null = unbekannt, TEIL = nichts. */
    public synchronized String zeichen(int nummer) {
        laden();
        Glyphe g = glyphen.get(nummer);
        return g == null ? null : g.zeichen();
    }

    /** Wie viele Glyphen die Bibliothek kennt. */
    public synchronized int anzahl() {
        laden();
        return glyphen.size();
    }

    /** Pfad des Glyphen-Bildes (zum Anzeigen beim Beschriften). */
    public Path glyphenBild(int nummer) {
        return ordner.resolve(nummer + ".png");
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Fingerabdruecke vergleichen
    // ------------------------------------------------------------------------------------------------------------

    /** Aehnlichkeit zweier Fingerabdruecke: 1 = gleich, 0 = nichts gemeinsam (1 - Editierabstand / Laenge). */
    public static double aehnlichkeit(String a, String b) {
        return aehnlichkeit(nummern(a), nummern(b), null);
    }

    /**
     * Derselbe Name? Gleich, oder hoechstens ein falsches Zeichen je vier (ein Zeichen, das in einer Aufnahme
     * anders zerlegt oder einer Nachbar-Glyphe zugeordnet wurde). Kurze Namen (bis 3 Zeichen) muessen damit genau
     * gleich sein.
     */
    public static boolean derselbeName(String a, String b) {
        return derselbe(nummern(a), nummern(b), null);
    }

    /**
     * Wie derselbeName(), zaehlt aber verwandte Glyphen (zwei Nummern fuer fast dasselbe Bild) nur zu einem
     * Viertel als Fehler. Fuer den Einbau in Sperrliste/Notizblock die bessere Wahl.
     */
    public synchronized boolean derselbeNameVerwandt(String a, String b) {
        laden();
        return derselbe(nummern(a), nummern(b), this);
    }

    /** Aehnlichkeit mit verwandten Glyphen (siehe derselbeNameVerwandt). */
    public synchronized double aehnlichkeitVerwandt(String a, String b) {
        laden();
        return aehnlichkeit(nummern(a), nummern(b), this);
    }

    private static boolean derselbe(int[] a, int[] b, Glyphenschrift bib) {
        if (a.length == 0 || b.length == 0) return false;
        if (java.util.Arrays.equals(a, b)) return true;
        return aehnlichkeit(a, b, bib) >= 0.75 - 1e-9;
    }

    private static double aehnlichkeit(int[] a, int[] b, Glyphenschrift bib) {
        int n = Math.max(a.length, b.length);
        if (n == 0) return 1;
        double[] vor = new double[b.length + 1], jetzt = new double[b.length + 1];
        for (int j = 0; j <= b.length; j++) vor[j] = j;
        for (int i = 1; i <= a.length; i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length; j++) {
                double tausch = a[i - 1] == b[j - 1] ? 0 : bib != null && bib.verwandt(a[i - 1], b[j - 1]) ? 0.25 : 1;
                jetzt[j] = Math.min(Math.min(vor[j] + 1, jetzt[j - 1] + 1), vor[j - 1] + tausch);
            }
            double[] t = vor; vor = jetzt; jetzt = t;
        }
        return Math.max(0, 1 - vor[b.length] / n);
    }

    /** Merkt sich, welche Glyphen-Paare verwandt sind (Rechnung nur einmal je Paar). */
    private final Map<Long, Boolean> verwandtschaft = new java.util.HashMap<>();

    private boolean verwandt(int x, int y) {
        Glyphe a = glyphen.get(x), b = glyphen.get(y);
        if (a == null || b == null) return false;   // (noch) unbekannte Nummer -- nicht merken
        long k = ((long) Math.min(x, y) << 32) | Math.max(x, y);
        return verwandtschaft.computeIfAbsent(k, q -> {
            if (!passtGrob(a, b.breite, b.tinte, b.lage)) return false;
            for (int i = 0; i < GlyphenZerlegung.SKALEN.length; i++)   // beide Richtungen: Ergebnis haengt nicht von der Reihenfolge ab
                if (GlyphenZerlegung.abstand(a.variante(i), b.raster, a.tinteVariante(i), b.tinte, VERWANDT) < VERWANDT
                        || GlyphenZerlegung.abstand(b.variante(i), a.raster, b.tinteVariante(i), a.tinte, VERWANDT) < VERWANDT) return true;
            return false;
        });
    }

    /** "4.17.9" -> {4, 17, 9}; Unlesbares wird uebersprungen. */
    public static int[] nummern(String fingerabdruck) {
        if (fingerabdruck == null || fingerabdruck.isBlank()) return new int[0];
        String[] t = fingerabdruck.strip().split("\\.");
        int[] aus = new int[t.length];
        int n = 0;
        for (String s : t) {
            try {
                aus[n] = Integer.parseInt(s.strip());
                n++;
            } catch (NumberFormatException e) {
                // kein Zeichen -- weglassen
            }
        }
        return java.util.Arrays.copyOf(aus, n);
    }

    /** {4, 17, 9} -> "4.17.9". */
    public static String fingerabdruck(int[] nummern) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nummern.length; i++) sb.append(i == 0 ? "" : ".").append(nummern[i]);
        return sb.toString();
    }

    /** Kurzform eines Fingerabdrucks fuer Dateinamen: 8 Hex-Zeichen. */
    public static String kurz(String fingerabdruck) {
        CRC32 c = new CRC32();
        c.update(fingerabdruck.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", c.getValue());
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Namensbilder
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Das abgelegte Namensbild zu einem Fingerabdruck: genau dieser, sonst der aehnlichste mit demselben Namen;
     * null, wenn keins da ist.
     */
    /** Das Namensbild genau dieses Fingerabdrucks (ohne Ersatz durch einen aehnlichen), oder null (Autor 21.11.). */
    public synchronized Path namensbildGenau(String fingerabdruck) {
        if (fingerabdruck == null || fingerabdruck.isBlank()) return null;
        Path genau = ordner.resolve("namen").resolve(kurz(fingerabdruck) + ".png");
        return Files.exists(genau) ? genau : null;
    }

    public synchronized Path namensbild(String fingerabdruck) {
        laden();
        if (fingerabdruck == null || fingerabdruck.isBlank()) return null;
        Path genau = ordner.resolve("namen").resolve(kurz(fingerabdruck) + ".png");
        if (Files.exists(genau)) return genau;
        String best = aehnlichsterName(fingerabdruck);
        return best == null ? null : ordner.resolve("namen").resolve(kurz(best) + ".png");
    }

    /** Der bekannte Fingerabdruck mit demselben Namen (aehnlichster zuerst) oder null. */
    private String aehnlichsterName(String fingerabdruck) {
        int[] a = nummern(fingerabdruck);
        String best = null;
        double bestWert = -1;
        for (String fp : namen.values()) {
            int[] b = nummern(fp);
            if (Math.abs(a.length - b.length) > 3 || !derselbe(a, b, this)) continue;
            double w = aehnlichkeit(a, b, this);
            if (w > bestWert) { bestWert = w; best = fp; }
        }
        return best;
    }

    private void namensbildAblegen(BufferedImage bild, Rectangle kasten, String fp, String text) {
        if (kasten == null || namen.containsValue(fp) || aehnlichsterName(fp) != null) return;
        String k = kurz(fp);
        Path dir = ordner.resolve("namen"), ziel = dir.resolve(k + ".png");
        try {
            Files.createDirectories(dir);
            int x0 = Math.max(0, kasten.x - 3), y0 = Math.max(0, kasten.y - 3);
            int x1 = Math.min(bild.getWidth(), kasten.x + kasten.width + 3), y1 = Math.min(bild.getHeight(), kasten.y + kasten.height + 3);
            ImageIO.write(bild.getSubimage(x0, y0, x1 - x0, y1 - y0), "png", ziel.toFile());
            Files.writeString(dir.resolve("namen.txt"), k + "\t" + fp + "\t" + text.replace('\t', ' ') + "\t"
                    + java.time.LocalDateTime.now().withNano(0) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            namen.put(k, fp);
        } catch (IOException | RuntimeException e) {
            Log.warn("Glyphen", Sprache.t("Name image could not be saved: " + e.getMessage(),
                    "Namensbild konnte nicht gespeichert werden: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Bibliothek
    // ------------------------------------------------------------------------------------------------------------

    /** Eine Glyphe: Rasterbild (erste Aufnahme, bleibt fest), Breite, Stimmen fuer Zeichen, Zuordnung von Hand. */
    private static final class Glyphe {
        final int nummer;
        final float[] raster;
        final double tinte;
        final int breite, hoehe;
        double lage;
        final Map<String, Integer> stimmen = new LinkedHashMap<>();
        String vonHand;
        int gesehen;

        Glyphe(int nummer, float[] raster, int breite, int hoehe) {
            this.nummer = nummer;
            this.raster = raster;
            double t = 0;
            for (float f : raster) t += f;
            this.tinte = t;
            this.breite = breite;
            this.hoehe = hoehe;
        }

        /** Das Raster in Groesse SKALEN[i] (fuer den Vergleich zweier Glyphen; einmal gerechnet). */
        private float[][] varianten;
        private double[] tinten;

        float[] variante(int i) {
            if (varianten == null) {
                varianten = new float[GlyphenZerlegung.SKALEN.length][];
                tinten = new double[varianten.length];
                for (int k = 0; k < varianten.length; k++) {
                    varianten[k] = GlyphenZerlegung.skaliert(raster, GlyphenZerlegung.SKALEN[k]);
                    for (float f : varianten[k]) tinten[k] += f;
                }
            }
            return varianten[i];
        }

        double tinteVariante(int i) {
            variante(i);
            return tinten[i];
        }

        /** Von Hand, sonst das Zeichen mit den meisten Stimmen (bei Gleichstand unbekannt). */
        String zeichen() {
            if (vonHand != null) return vonHand;
            String best = null;
            int erste = 0, zweite = 0;
            for (var e : stimmen.entrySet()) {
                if (e.getValue() > erste) { zweite = erste; erste = e.getValue(); best = e.getKey(); }
                else if (e.getValue() > zweite) zweite = e.getValue();
            }
            return erste > zweite ? best : null;
        }
    }

    /** So weit (Bildpunkte) darf die Hoehenlage zweier gleicher Zeichen abweichen (Bindestrich vs. Unterstrich). */
    static final double LAGE = 1.5;

    /** Grobe Vorauswahl: aehnliche Hoehenlage, Breite und Tintenmenge -- sonst lohnt der genaue Vergleich nicht. */
    private static boolean passtGrob(Glyphe g, int breite, double tinte, double lage) {
        if (Math.abs(g.lage - lage) > LAGE) return false;
        if (Math.abs(g.breite - breite) > Math.max(3, breite / 4)) return false;
        return tinte <= g.tinte * 1.7 + 2 && g.tinte <= tinte * 1.7 + 2;
    }

    /** Die naechste Glyphe mit Abstand unter "grenze" oder null. */
    private Glyphe naechste(GlyphenZerlegung.Stueck s, double grenze) {
        Glyphe best = null;
        double bestAbstand = grenze;
        for (Glyphe g : glyphen.values()) {
            if (!passtGrob(g, s.breite(), s.tinte(), s.lage())) continue;
            for (int i = 0; i < s.varianten().length; i++) {
                double d = GlyphenZerlegung.abstand(s.varianten()[i], g.raster, s.tinten()[i], g.tinte, bestAbstand);
                if (d < bestAbstand) { bestAbstand = d; best = g; }
            }
        }
        return best;
    }

    private Glyphe anlegen(GlyphenZerlegung.Stueck s) {
        int nr = glyphen.isEmpty() ? 1 : glyphen.lastKey() + 1;
        Glyphe g = new Glyphe(nr, s.raster(), s.breite(), s.hoehe());
        g.lage = s.lage();
        glyphen.put(nr, g);
        try {
            Files.createDirectories(ordner);
            ImageIO.write(rasterBild(g.raster), "png", glyphenBild(nr).toFile());
        } catch (IOException | RuntimeException e) {
            Log.warn("Glyphen", Sprache.t("Glyph image could not be saved: " + e.getMessage(),
                    "Glyphenbild konnte nicht gespeichert werden: " + e.getMessage()));
        }
        Log.debug("Glyphen", "Neue Glyphe " + nr + " (" + s.breite() + "x" + s.hoehe() + " Punkte).");
        return g;
    }

    private static BufferedImage rasterBild(float[] r) {
        BufferedImage b = new BufferedImage(GlyphenZerlegung.RB, GlyphenZerlegung.RH, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < GlyphenZerlegung.RH; y++)
            for (int x = 0; x < GlyphenZerlegung.RB; x++) {
                int g = Math.round(Math.max(0, Math.min(1, r[y * GlyphenZerlegung.RB + x])) * 255);
                b.getRaster().setSample(x, y, 0, g);
            }
        return b;
    }

    private static float[] rasterAusBild(BufferedImage b) {
        float[] r = new float[GlyphenZerlegung.RB * GlyphenZerlegung.RH];
        if (b.getWidth() != GlyphenZerlegung.RB || b.getHeight() != GlyphenZerlegung.RH) return null;
        // Rohwert lesen, nicht getRGB(): das rechnet Graustufen mit Gamma nach sRGB um und verfaelscht das Raster
        // (gemessen 24.10.: nach dem Neuladen wurden 5 von 11 Zeichen nicht wiedererkannt).
        for (int y = 0; y < GlyphenZerlegung.RH; y++)
            for (int x = 0; x < GlyphenZerlegung.RB; x++)
                r[y * GlyphenZerlegung.RB + x] = b.getRaster().getSample(x, y, 0) / 255f;
        return r;
    }

    /**
     * Saubere Windows-Lesung fuers Lernen: ohne Leerzeichen, Unicode zusammengesetzt (e + Akzent = é), keine
     * Fragezeichen/Ersatzzeichen, mindestens ein Buchstabe oder eine Ziffer. null = nicht zum Lernen geeignet.
     */
    static String sauber(String lesung) {
        if (lesung == null) return null;
        String s = Normalizer.normalize(lesung, Normalizer.Form.NFC).replaceAll("[\\s\\u00A0\\u200B]+", "");
        if (s.isEmpty() || s.indexOf('?') >= 0 || s.indexOf('�') >= 0) return null;
        boolean buchstabe = false;
        for (int cp : s.codePoints().toArray()) {
            if (Character.isISOControl(cp)) return null;
            buchstabe |= Character.isLetterOrDigit(cp);
        }
        return buchstabe ? s : null;
    }

    /** Laedt die Bibliothek beim ersten Gebrauch (fehlende Dateien = leere Bibliothek). */
    private void laden() {
        if (geladen) return;
        geladen = true;
        Path liste = ordner.resolve("glyphen.txt");
        if (Files.exists(liste)) {
            try {
                for (String zeile : Files.readAllLines(liste, StandardCharsets.UTF_8)) {
                    if (zeile.isBlank() || zeile.startsWith("#")) continue;
                    String[] t = zeile.split("\t", -1);
                    try {
                        int nr = Integer.parseInt(t[0].strip());
                        Path png = glyphenBild(nr);
                        if (!Files.exists(png)) continue;
                        float[] r = rasterAusBild(ImageIO.read(png.toFile()));
                        if (r == null) continue;
                        Glyphe g = new Glyphe(nr, r, zahl(t, 3, 8), zahl(t, 4, 12));
                        g.lage = kommazahl(t, 5);
                        g.gesehen = zahl(t, 2, 0);
                        String z = t.length > 1 ? t[1] : "";
                        if (t.length > 6 && t[6].strip().equals("ja") && !z.isEmpty())
                            g.vonHand = z.equals(TEIL_IN_DATEI) ? TEIL : z;
                        if (t.length > 7) stimmenLesen(t[7], g.stimmen);
                        glyphen.put(nr, g);
                    } catch (RuntimeException | IOException e) {
                        Log.debug("Glyphen", "Zeile in glyphen.txt uebersprungen: " + zeile);
                    }
                }
            } catch (IOException e) {
                Log.warn("Glyphen", Sprache.t("glyphen.txt not readable: " + e.getMessage(),
                        "glyphen.txt nicht lesbar: " + e.getMessage()));
            }
        }
        Path index = ordner.resolve("namen").resolve("namen.txt");
        if (Files.exists(index)) {
            try {
                for (String zeile : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                    String[] t = zeile.split("\t", -1);
                    if (t.length >= 2 && !t[0].isBlank()) namen.put(t[0].strip(), t[1].strip());
                }
            } catch (IOException e) {
                Log.debug("Glyphen", "namen.txt nicht lesbar: " + e.getMessage());
            }
        }
    }

    private static double kommazahl(String[] t, int i) {
        try {
            return t.length > i ? Double.parseDouble(t[i].strip()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int zahl(String[] t, int i, int sonst) {
        try {
            return t.length > i ? Integer.parseInt(t[i].strip()) : sonst;
        } catch (NumberFormatException e) {
            return sonst;
        }
    }

    /** "5:a 1:o" -> {a=5, o=1}; das Zeichen ist alles nach dem ersten Doppelpunkt (auch ":" selbst). */
    private static void stimmenLesen(String feld, Map<String, Integer> aus) {
        for (String s : feld.strip().split(" ")) {
            int p = s.indexOf(':');
            if (p <= 0 || p == s.length() - 1) continue;
            try {
                aus.merge(s.substring(p + 1), Integer.parseInt(s.substring(0, p)), Integer::sum);
            } catch (NumberFormatException e) {
                // kaputter Eintrag -- weglassen
            }
        }
    }

    /** Schreibt glyphen.txt (UTF-8), wenn sich etwas geaendert hat. Die Glyphen-Bilder liegen schon. */
    public synchronized void speichern() {
        if (!geladen || !geaendert) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# Glyphen-Bibliothek fuer Spielernamen (Glyphenschrift, Autor 24.10.). UTF-8.").append(System.lineSeparator());
        sb.append("# Nummer\tZeichen\tgesehen\tBreite\tHoehe\tLage\tvon Hand\tStimmen (Anzahl:Zeichen)").append(System.lineSeparator());
        for (Glyphe g : glyphen.values()) {
            String z = g.zeichen();
            sb.append(g.nummer).append('\t').append(z == null ? "" : TEIL.equals(z) ? TEIL_IN_DATEI : z).append('\t')
                    .append(g.gesehen).append('\t').append(g.breite).append('\t').append(g.hoehe).append('\t')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", g.lage)).append('\t')
                    .append(g.vonHand != null ? "ja" : "").append('\t');
            boolean erst = true;
            for (var e : g.stimmen.entrySet()) {
                sb.append(erst ? "" : " ").append(e.getValue()).append(':').append(e.getKey());
                erst = false;
            }
            sb.append(System.lineSeparator());
        }
        try {
            Files.createDirectories(ordner);
            Path tmp = ordner.resolve("glyphen.txt.neu");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, ordner.resolve("glyphen.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            geaendert = false;
            zuletztGespeichert = System.currentTimeMillis();
        } catch (IOException | RuntimeException e) {
            Log.warn("Glyphen", Sprache.t("glyphen.txt could not be saved: " + e.getMessage(),
                    "glyphen.txt konnte nicht gespeichert werden: " + e.getMessage()));
        }
    }

    /** Fuer Tests/Pruefprogramm: alle Nummern mit ihrem Zeichen (null = unbekannt). */
    synchronized List<String> uebersicht() {
        laden();
        List<String> l = new ArrayList<>();
        for (Glyphe g : glyphen.values()) l.add(g.nummer + "=" + g.zeichen() + " (" + g.gesehen + "x)");
        return l;
    }
}
