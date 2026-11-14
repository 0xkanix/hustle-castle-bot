package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Was oben in der Burg steht (Autor, 27.09., markiertes Bild): Eisen, Holz,
 * Mana, Nahrung, Gold (obere Reihe), Mythril und Diamanten (darunter), dazu
 * Level, Name und Clan oben links, die Pokale darunter und die Zahl an der
 * Tasche. Jede Zahl steht rechts neben ihrem Symbol; aufgenommen wird nur das
 * Symbol (Capture-Images.exe, Gruppe BURG-STAND), die Zahl liest der Bot selbst.
 *
 * Nur lesen -- hier wird nichts angeklickt.
 */
public final class Burgstand {

    private Burgstand() {}

    /** Symbol -> Anzeigename, in der Reihenfolge des Spiels. */
    static final Map<String, String> ROHSTOFFE = new LinkedHashMap<>();
    static {
        ROHSTOFFE.put("rs_eisen", "Eisen");
        ROHSTOFFE.put("rs_holz", "Holz");
        ROHSTOFFE.put("rs_mana", "Mana");
        ROHSTOFFE.put(Nahrungskisten.T_NAHRUNG, "Nahrung");   // cw_nahrung_burg, gibt es schon
        ROHSTOFFE.put("rs_gold", "Gold");
        ROHSTOFFE.put("rs_mithril", "Mythril");
        ROHSTOFFE.put("rs_diamant", "Diamanten");
        ROHSTOFFE.put("rs_pokal", "Pokale");
    }

    /** Ein Kern-Ausschnitt: nur das Innere eines Symbols, {@code dx/dy} = Lage im ganzen Symbol (Breite x Hoehe). */
    record Kern(String vorlage, int dx, int dy, int breite, int hoehe) { }

    /**
     * Lesetest 22.10. (30 Bilder, Burg verschoben): die Vorlagen fuer Eisen, Mana, Mythril und Diamanten tragen
     * Hintergrund am Rand und passten nur zu 50-80 % -- Eisen fand sich dann eher auf dem Mythril-Wuerfel. Die Kerne
     * (templates/gelernt/burgstand) passten auf allen 30 Bildern zu 85-100 %.
     */
    static final Map<String, Kern> KERNE = Map.of(
            "rs_eisen", new Kern("rs_eisen_kern", 13, 10, 49, 36),
            "rs_mana", new Kern("rs_mana_kern", 6, 6, 32, 35),
            "rs_mithril", new Kern("rs_mithril_kern", 8, 7, 46, 40),
            "rs_diamant", new Kern("rs_diamant_kern", 6, 8, 35, 43),
            "rs_gold", new Kern("rs_gold_kern", 6, 6, 33, 37),
            "rs_holz", new Kern("rs_holz_kern", 6, 8, 35, 43),
            Nahrungskisten.T_NAHRUNG, new Kern("cw_nahrung_burg_kern", 6, 6, 34, 33));

    /** Ab dieser Aehnlichkeit gilt ein Kern als gefunden. */
    static final double MIN_KERN = 0.75;

    /** Ein Symbol der Leiste: die alte Vorlage ab {@code min} oder sein Kern ab MIN_KERN -- der bessere Fund zaehlt. */
    static Optional<Matcher.Hit> symbol(Mat screen, Matcher matcher, String vorlage, double min) {
        Optional<Matcher.Hit> h = matcher.has(vorlage) ? matcher.find(screen, vorlage, min) : Optional.empty();
        Kern k = KERNE.get(vorlage);
        if (k != null && matcher.has(k.vorlage())) {
            Optional<Matcher.Hit> kh = matcher.find(screen, k.vorlage(), MIN_KERN);
            if (kh.isPresent() && (h.isEmpty() || kh.get().score() >= h.get().score())) {
                Matcher.Hit t = kh.get();   // auf das ganze Symbol umrechnen (die Zahl beginnt rechts davon)
                h = Optional.of(new Matcher.Hit(vorlage, t.x() - k.dx(), t.y() - k.dy(), k.breite(), k.hoehe(), t.score()));
            }
        }
        return h;
    }

    /**
     * Nur die Nahrung (vor jeder Bezahlung, Nahrungskisten.nahrungLesen), -1 = nicht lesbar. Lesetest 22.10.: die
     * weissen Ziffern lasen sie auf 30 von 30 Bildern, die Texterkennung auf 23. Die Zahl endet vor dem Gold-Symbol
     * (154 Bildpunkte weiter bei 1159 Breite).
     */
    static long nahrung(Mat screen, BufferedImage bild, Matcher matcher) {
        Optional<Matcher.Hit> h = symbol(screen, matcher, Nahrungskisten.T_NAHRUNG, 0.72);
        if (h.isEmpty() || h.get().centerY() >= bild.getHeight() * 0.16 || h.get().centerX() < bild.getWidth() / 2) return -1;
        Matcher.Hit a = h.get();
        int x0 = a.x() + a.width(), bis = a.x() + (int) (150 * bild.getWidth() / 1159.0);
        return burgZahl(ziffernStreifen(bild, a, x0, Math.max(10, bis - x0)));
    }
    /** Das Level-Abzeichen oben links -- rechts daneben Name (oben) und Clan (unten). */
    static final String T_LEVEL = "rs_level";
    /** Der Tasche-Knopf (gibt es schon); die Zahl steht im gruenen Kreis oben rechts daran. */
    static final String T_TASCHE = "bag_button";

    static List<String> vorlagen() {
        List<String> l = new java.util.ArrayList<>(ROHSTOFFE.keySet());
        l.add(T_LEVEL);
        return l;
    }

    /** Alle Zahlen, deren Symbol zu sehen ist (Name -> Wert). Fehlendes fehlt einfach. */
    public static Map<String, Long> zahlen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return new LinkedHashMap<>();
        return zahlen(screen, bild, ctx.matcher());
    }

    /**
     * Wie oben, ohne BotContext (auch fuer Tests an gespeicherten Bildern).
     *
     * Lesetest 22.10. (Burg verschoben): Eisen las die Holz-Zahl mit ("9682389132..."), Holz nur "132", Mythril die
     * Diamanten, Mana und Diamanten gar nicht. Jetzt: (1) zeigen zwei Vorlagen auf dieselbe Stelle, gilt die bessere;
     * (2) jede Zahl endet vor dem naechsten Symbol in derselben Zeile; (3) zuerst die weissen Ziffern der Spielschrift
     * (ArenaTabelle.MUSTER), erst danach die Texterkennung.
     */
    static Map<String, Long> zahlen(Mat screen, BufferedImage bild, Matcher matcher) {
        Map<String, Long> werte = new LinkedHashMap<>();
        Map<String, Matcher.Hit> treffer = new LinkedHashMap<>();
        for (var e : ROHSTOFFE.entrySet()) {
            // Lesetest 22.10.: je nach Hintergrund passen die Symbole nur zu 60-80 % (Mythril immer um 0,6) -- in der
            // Leiste reicht 0,55; unsichere Funde gelten nur mit sauber gelesenen Ziffern (keine Texterkennung).
            Optional<Matcher.Hit> h = symbol(screen, matcher, e.getKey(), MIN_SYMBOL);
            // Nur oben in der Leiste (obere 16 %) -- nicht irgendwo in der Burg. Die Pokale stehen links unter dem
            // Level-Abzeichen (Mitte bei 126 von 770, Autor 22.10.) -- fuer sie gilt 20 %.
            if (h.isPresent() && h.get().centerY() < bild.getHeight() * (e.getKey().equals("rs_pokal") ? 0.20 : 0.16)) treffer.put(e.getKey(), h.get());
        }
        // (1) dieselbe Stelle: die bessere Vorlage behalten
        java.util.List<String> namen = new java.util.ArrayList<>(treffer.keySet());
        for (String a : namen)
            for (String b : namen) {
                if (a.equals(b) || !treffer.containsKey(a) || !treffer.containsKey(b)) continue;
                Matcher.Hit ha = treffer.get(a), hb = treffer.get(b);
                if (Math.abs(ha.centerX() - hb.centerX()) < 20 && Math.abs(ha.centerY() - hb.centerY()) < 20) {
                    treffer.remove(ha.score() >= hb.score() ? b : a);
                }
            }
        for (var e : treffer.entrySet()) {
            Matcher.Hit h = e.getValue();
            // (2) rechte Grenze: das naechste Symbol in derselben Zeile
            int grenze = h.x() + h.width() * 5;
            for (Matcher.Hit o : treffer.values()) {
                if (o == h || Math.abs(o.centerY() - h.centerY()) > h.height() / 2 || o.x() <= h.x()) continue;
                grenze = Math.min(grenze, o.x() - 2);
            }
            int x0 = h.x() + h.width(), breite = Math.max(10, Math.min(grenze, bild.getWidth()) - x0);
            // (3) weisse Ziffern
            long z = burgZahl(ziffernStreifen(bild, h, x0, breite));
            double sicher = e.getKey().equals(Nahrungskisten.T_NAHRUNG) ? 0.72 : Matcher.DEFAULT_THRESHOLD;
            OptionalLong n = z >= 0 ? OptionalLong.of(z) : h.score() >= sicher ? zahlRechts(bild, h, breite) : OptionalLong.empty();
            String name = ROHSTOFFE.get(e.getKey());
            n.ifPresent(v -> werte.put(name, v));
        }
        // in der Reihenfolge des Spiels
        Map<String, Long> geordnet = new LinkedHashMap<>();
        for (String name : ROHSTOFFE.values()) if (werte.containsKey(name)) geordnet.put(name, werte.get(name));
        return geordnet;
    }

    /** Name und Clan rechts neben dem Level-Abzeichen; dazu das Level selbst, wenn lesbar. */
    public static Map<String, String> spieler(Mat screen, BotContext ctx) {
        Map<String, String> s = new LinkedHashMap<>();
        BufferedImage bild = ctx.frame();
        if (bild == null || !ctx.matcher().has(T_LEVEL)) return s;
        Optional<Matcher.Hit> h = ctx.matcher().find(screen, T_LEVEL);
        if (h.isEmpty()) return s;
        Matcher.Hit a = h.get();
        OptionalLong level = ArenaLeser.zahl(bild, a.x(), a.y(), a.width(), a.height());
        level.ifPresent(v -> s.put("Level", String.valueOf(v)));
        int breite = (int) (ctx.width() * 0.17);
        List<String> zeilen = ArenaLeser.lies(bild, a.x() + a.width(), a.y(), breite, a.height());
        if (!zeilen.isEmpty()) s.put("Name", zeilen.get(0).trim());
        if (zeilen.size() > 1) s.put("Clan", zeilen.get(1).trim());
        return s;
    }

    /**
     * Die Zahl an der Tasche (gruener Kreis oben rechts am Knopf). Leer, wenn
     * kein Kreis zu sehen oder die Zahl nicht lesbar ist -- dann lieber
     * nachsehen als etwas uebersehen.
     */
    public static OptionalLong taschenZahl(Mat screen, BotContext ctx) {
        // Manche Aufrufer haben gerade kein Bild (29.09.: "Bild 0x0" -- die Suche nach
        // der Tasche lief ins Leere, die Zahl an der Tasche wurde nie gelesen).
        if (screen == null || screen.empty()) screen = ctx.refresh();
        if (screen == null || screen.empty()) return OptionalLong.empty();
        BufferedImage bild = ctx.frame();
        Optional<Matcher.Hit> h = ctx.matcher().find(screen, T_TASCHE);
        if (bild == null || h.isEmpty()) return OptionalLong.empty();
        Matcher.Hit t = h.get();
        int x = t.x() + t.width() / 3, y = Math.max(0, t.y() - t.height() / 2);
        int w = t.width(), hoehe = t.height() * 3 / 4;
        OptionalLong n = ArenaLeser.zahl(bild, x, y, w, hoehe);
        if (n.isEmpty()) n = Nahrungskisten.zahlVerstaerkt(bild, x, y, w, hoehe, true);
        return n.isPresent() && n.getAsLong() >= 0 && n.getAsLong() < 100_000 ? n : OptionalLong.empty();
    }

    /**
     * Die zehn Ziffern der Burg-Leiste, je 8 x 12 ('#' = weisse Fuellung). Weisse Schrift wie in der Arena, aber eine
     * andere Form (die "1" hat einen Fuss) -- gelernt an Autors Burg-Bildern vom 22.10. mit bekannten Zahlen (21 Zahlen
     * aus zwei Bildern, je Ziffer 4-11 Beispiele; mit nur einem Bild wurde die "4" in "12 634 814" nicht erkannt).
     */
    static final String[] MUSTER_BURG = {
            "...####..#######.###.######...#####...#####...#####...#####...#####...#####...##.###..##.######.",   // 0
            "...###....####..######..######.....###.....###.....###.....###.....###.....###.....###..########",   // 1
            ".######.########.....###.....###.....###.....###....###....###.....##.....##.....###.....#######",   // 2
            ".######.########.....###.....###.....###...####...#####......###......##......##.#...##########.",   // 3
            ".....##.....###....####....####....####...#####..##..##..#...##.###.###.########.#######....###.",   // 4
            "###################.....###.....###.....#######.#########.....##......##......##......#########.",   // 5
            "...#####..######.###.....##.....###.....#######.############..#####...##.##...##.###..##..#####.",   // 6
            "################.....###.....###.....###....###.....###....###.....##......##.....##.....###....",   // 7
            "..#####..##########...#####...##.##...##.###.###..######.###.######...#####...#####...##########",   // 8
            "..####...######.###...####....####....####....##.#######..###.##.......#......##......#########.",   // 9
    };

    /** So viele der 96 Bildpunkte duerfen hoechstens vom besten Muster abweichen. */
    static final int BURG_TOLERANZ = 20;

    /**
     * Die Weiss-Maske nur auf Hoehe der Ziffern: Symbolmitte +/- 12 Bildpunkte (bei 770 hoch). Autor 22.10.: bei langen
     * Zahlen ("12 634 814") beruehrte der helle Rand oben an der Kapsel die letzte Ziffer -- die Zahl galt als unsicher.
     */
    static boolean[][] ziffernStreifen(BufferedImage bild, Matcher.Hit symbol, int x0, int breite) {
        int halb = (int) Math.round(12 * bild.getHeight() / 770.0);
        return weissMaske(bild, x0, symbol.centerY() - halb, breite, 2 * halb);
    }

    /** Wie ArenaTabelle.weissMaske. Eigene Kopie: Die Burg muss auch mit einem Schluessel ohne Arena-Teil lesbar bleiben (Schutz-Plan 31.10.). */
    private static boolean[][] weissMaske(BufferedImage bild, int x, int y, int breite, int hoehe) {
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        boolean[][] m = new boolean[Math.max(0, y2 - y1)][Math.max(0, x2 - x1)];
        for (int yy = y1; yy < y2; yy++) {
            for (int xx = x1; xx < x2; xx++) {
                int p = bild.getRGB(xx, yy);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                m[yy - y1][xx - x1] = Math.min(r, Math.min(g, b)) > 238;   // ArenaTabelle.WEISS_AB
            }
        }
        return m;
    }

    /** Ab dieser Aehnlichkeit gilt ein Symbol oben in der Leiste als gefunden. */
    static final double MIN_SYMBOL = 0.55;

    /**
     * Die Zahl aus einer Weiss-Maske der Burg-Leiste, -1 = nicht lesbar. Jede Ziffer ist ein zusammenhaengender weisser
     * Fleck; Glanzpunkte der Symbole (Diamant) und helle Stellen der Burg liegen oft in denselben Spalten -- als eigene
     * Flecken fallen sie weg (andere Hoehe oder andere Zeile als die Ziffern).
     */
    static long burgZahl(boolean[][] m) {
        if (m.length == 0 || m[0].length == 0) return -1;
        int[][] marke = new int[m.length][m[0].length];
        java.util.List<int[]> gl = glyphen(m, marke);
        if (gl.isEmpty()) return -1;
        // Nur die erste Zahl: Tausender sind durch eine schmale Luecke getrennt (~9 Bildpunkte), hinter einer Luecke von
        // mehr als einer Ziffernhoehe beginnt schon das naechste Feld (Lesetest 22.10.: "2659684" = Mythril + Diamanten).
        int hoehe = gl.get(0)[3] - gl.get(0)[2] + 1, luecke = (int) (hoehe * 1.2), bis = 1;
        while (bis < gl.size() && gl.get(bis)[0] - gl.get(bis - 1)[1] <= luecke) bis++;
        // Dahinter darf nur weit weg noch etwas stehen (das naechste Feld). Naeher dran heisst: eine Ziffer ist verdeckt
        // (Lesetest 22.10.: eine fliegende Goldmuenze ueber "266 635" ergab "26") -- lieber nichts als Falsches.
        if (bis < gl.size() && gl.get(bis)[0] - gl.get(bis - 1)[1] < hoehe * 2.5) return -1;
        gl = gl.subList(0, bis);
        if (gl.size() > 12) return -1;
        // Tausender stehen in Dreiergruppen (schmale Luecke dazwischen): vorn 1-3 Ziffern, danach immer genau 3.
        int gruppe = 1;
        boolean erste = true;
        for (int i = 1; i <= gl.size(); i++) {
            if (i < gl.size() && gl.get(i)[0] - gl.get(i - 1)[1] <= hoehe * 0.4) {
                gruppe++;
                continue;
            }
            if (erste ? gruppe > 3 : gruppe != 3) return -1;
            erste = false;
            gruppe = 1;
        }
        StringBuilder zahl = new StringBuilder();
        for (int[] g : gl) {
            int z = burgZiffer(muster(marke, g));
            if (z < 0) return -1;
            zahl.append((char) ('0' + z));
        }
        return Long.parseLong(zahl.toString());
    }

    /** Die ziffernartigen Flecken einer Maske von links nach rechts: {x1, x2, y1, y2, Marke}; {@code marke} wird gefuellt. */
    static java.util.List<int[]> glyphen(boolean[][] m, int[][] marke) {
        int h = m.length, w = m[0].length, nr = 0;
        java.util.List<int[]> alle = new java.util.ArrayList<>();
        int[] stapel = new int[h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y][x] || marke[y][x] != 0) continue;
                nr++;
                int x1 = x, x2 = x, y1 = y, y2 = y, punkte = 0, sp = 0;
                marke[y][x] = nr;
                stapel[sp++] = y * w + x;
                while (sp > 0) {
                    int p = stapel[--sp], py = p / w, px = p % w;
                    punkte++;
                    x1 = Math.min(x1, px); x2 = Math.max(x2, px); y1 = Math.min(y1, py); y2 = Math.max(y2, py);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int qy = py + dy, qx = px + dx;
                            if (qy < 0 || qy >= h || qx < 0 || qx >= w || !m[qy][qx] || marke[qy][qx] != 0) continue;
                            marke[qy][qx] = nr;
                            stapel[sp++] = qy * w + qx;
                        }
                    }
                }
                if (punkte >= 6 && y2 - y1 >= 7) alle.add(new int[]{x1, x2, y1, y2, nr});
            }
        }
        if (alle.isEmpty()) return alle;
        // Die Ziffern sind gleich hoch und stehen in einer Zeile: Hoehe und Mitte nach der Mehrheit.
        int[] hoehen = alle.stream().mapToInt(g -> g[3] - g[2]).sorted().toArray();
        int[] mitten = alle.stream().mapToInt(g -> g[2] + g[3]).sorted().toArray();
        int hoehe = hoehen[hoehen.length / 2], mitte2 = mitten[mitten.length / 2];
        java.util.List<int[]> ziffern = new java.util.ArrayList<>();
        for (int[] g : alle) {
            int gh = g[3] - g[2];
            if (gh < hoehe * 0.8 || gh > hoehe * 1.25 || Math.abs(g[2] + g[3] - mitte2) > hoehe * 0.8) continue;
            ziffern.add(g);
        }
        ziffern.sort(java.util.Comparator.comparingInt(g -> g[0]));
        // Ein grosser heller Fleck mitten in der Zahlenzeile (Glanzstreifen, der an einer Ziffer klebt -- Lesetest
        // 22.10.: aus "625 447" fiel die "6" weg) -- dann ist die Zahl nicht sicher: lieber gar keine.
        if (!ziffern.isEmpty()) {
            int luecke = (int) (hoehe * 1.2);
            int von = ziffern.get(0)[0] - luecke, bis = ziffern.get(ziffern.size() - 1)[1] + luecke;
            int oben = ziffern.stream().mapToInt(g -> g[2]).min().getAsInt();
            int unten = ziffern.stream().mapToInt(g -> g[3]).max().getAsInt();
            for (int[] g : alle) {
                if (ziffern.contains(g)) continue;
                if (g[1] >= von && g[0] <= bis && g[3] >= oben && g[2] <= unten) return new java.util.ArrayList<>();
            }
        }
        return ziffern;
    }

    /** Ein Fleck auf 8 x 12 verkleinert ('#' = mindestens halb weiss). */
    static String muster(int[][] marke, int[] g) {
        int w = g[1] - g[0] + 1, h = g[3] - g[2] + 1;
        StringBuilder s = new StringBuilder(96);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 8; x++) {
                int sx0 = g[0] + x * w / 8, sx1 = Math.max(sx0 + 1, g[0] + (x + 1) * w / 8);
                int sy0 = g[2] + y * h / 12, sy1 = Math.max(sy0 + 1, g[2] + (y + 1) * h / 12);
                int an = 0, alle = 0;
                for (int yy = sy0; yy < sy1; yy++) {
                    for (int xx = sx0; xx < sx1; xx++) {
                        alle++;
                        if (marke[yy][xx] == g[4]) an++;
                    }
                }
                s.append(an * 2 >= alle ? '#' : '.');
            }
        }
        return s.toString();
    }

    /** Die aehnlichste Ziffer, -1 = keine passt gut genug. */
    static int burgZiffer(String muster) {
        int beste = -1, besteAbw = Integer.MAX_VALUE;
        for (int z = 0; z < 10; z++) {
            int abw = 0;
            for (int i = 0; i < 96; i++) if (muster.charAt(i) != MUSTER_BURG[z].charAt(i)) abw++;
            if (abw < besteAbw) {
                besteAbw = abw;
                beste = z;
            }
        }
        return besteAbw <= BURG_TOLERANZ ? beste : -1;
    }

    private static OptionalLong zahlRechts(BufferedImage bild, Matcher.Hit h, int breite) {
        OptionalLong n = ArenaLeser.zahlRechtsVon(bild, h, breite);
        if (n.isEmpty()) {
            n = Nahrungskisten.zahlVerstaerkt(bild, h.x() + h.width(), h.y() - 4, breite, h.height() + 8, true);
        }
        return n;
    }
}
