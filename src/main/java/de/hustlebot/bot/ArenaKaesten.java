package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Autor 24.10. (Ideenliste A01): feste Suchplaetze fuer Banner und Fenster-Bilder der Arena.
 *
 * WARUM. Diese Bilder erscheinen im Spiel immer an derselben Stelle -- gesucht wurden sie bisher trotzdem im GANZEN
 * Bild (25-33 ms je Vergleich statt ~1 ms im Kasten), weil der Matcher eine Stelle erst kennt, wenn das Bild einmal
 * gesehen wurde: "ar_defeat" kam in 13 Minuten Arena 2024 Mal im ganzen Bild dran (49 s), "ar_item_fenster" 473 Mal
 * (15 s), "ar_schirm_titel" 466 Mal (12 s), "ar_pal_titel" 462 Mal (12 s), "ar_dwellers" ueber die Pruefer-Abfrage 449 Mal
 * (19 s). Die Arena-Pruefung hatte "IMMER im ganzen Bild" ausdruecklich so gewollt, damit ein offenes Fenster nie fuer
 * zu gehalten wird -- das bleibt gewaehrleistet: kein Bild wird uebersprungen, nur der Suchbereich wird kleiner, und
 * gerechnet wird genau derselbe Vergleich (gleiche Werte, gleiche Schwelle).
 *
 * LAGEN. Gemessen an 232 Arena-Fotos (fotos-arena22/23) und gut 1000 weiteren Fotos (logs/auge, fotoserie, team) mit
 * derselben Vergleichsrechnung wie der Bot: ar_defeat 519,176 (ein Foto); ar_item_fenster x 453, y 225-287 (50 Fotos);
 * ar_pal_titel 288,93 (4); ar_schirm_titel 816,40 (2) und 223,36 (1, Fenster auf der anderen Seite); ar_dwellers
 * 330-333,660-665 (225). Wo nur wenige Fotos vorliegen oder das Fenster die Seite wechselt, ist der Kasten ein Streifen
 * ueber die ganze Breite; sonst hat er 40-80 Bildpunkte Luft.
 *
 * SICHERHEITSNETZ. Steht das Bild nicht im Kasten, wird trotzdem hin und wieder im ganzen Bild gesucht (das erste Mal
 * gleich, danach jeder {@link #NETZ_ALLE}-te Fehlschlag) -- wuerde ein Bild je anderswo auftauchen (anderes Fenstermass,
 * Spiel-Update), merkt der Bot es nach wenigen Bildern statt nie.
 *
 * NICHT hier: "update_button" und "other_device" (Rauswurf, "anderes Geraet"): dort schuetzt die Suche im ganzen Bild
 * Autor am Handy (Vorfall 13.10.), und es gibt keine Fotos von beiden, an denen sich eine Lage messen liesse.
 */
final class ArenaKaesten {

    private ArenaKaesten() {
    }

    /** Das Fenstermass, in dem die Zahlen unten gemessen wurden. */
    static final int BREITE = 1159, HOEHE = 770;

    /** Jeder Kasten {x, y, Breite, Hoehe} bei 1159 x 770; mehrere, wenn das Bild an mehreren Stellen vorkommt. */
    private static final Map<String, int[][]> KAESTEN = Map.of(
            // Niederlage-Banner: ein Foto (519,176, 115x30). Das Sieg-Banner (511,174) sitzt gleich mittig -- Kasten weit.
            "ar_defeat", new int[][]{{439, 126, 275, 130}},
            // "Switch dwellers" (Einheitenfenster): derselbe Kasten wie im Matcher (300 Fotos, 22.10.; hier 225 Fotos
            // bei 330-333,660-665).
            "ar_dwellers", new int[][]{{292, 630, 283, 87}},
            // Gegenstandsfenster: x immer 453, die Oberkante je nach Gegenstand 225-287 (50 Fotos).
            "ar_item_fenster", new int[][]{{333, 165, 422, 200}},
            // Pal-Fenster (288,93 -- links, 4 Fotos) und Fenster der Schirmherrschaften (816,40 rechts, 223,36 links):
            // je nach Seite links oder rechts, darum ein Streifen ueber die ganze Breite.
            "ar_pal_titel", new int[][]{{0, 33, 1159, 146}},
            "ar_schirm_titel", new int[][]{{0, 0, 1159, 117}});

    /** Nach so vielen Fehlschlaegen im Kasten wird einmal im ganzen Bild gesucht (Sicherheitsnetz). */
    static final int NETZ_ALLE = 8;

    private static final Object SCHLOSS = new Object();
    /** Antworten zum zuletzt untersuchten Bild (je Bild und Schwelle nur einmal rechnen). */
    private static Mat memoBild;
    private static final Map<String, Boolean> MEMO = new HashMap<>();
    /** Fehlschlaege im Kasten je Vorlage -- fuer das Sicherheitsnetz. */
    private static final Map<String, Integer> FEHLSCHLAEGE = new HashMap<>();

    /** Hat diese Vorlage einen festen Suchplatz? */
    static boolean hatKasten(String name) {
        return KAESTEN.containsKey(name);
    }

    /** Die Kaesten in Bildpunkten eines Fensters der Groesse w x h; leer, wenn es keine gibt. */
    static java.util.List<Rect> kaesten(String name, int w, int h) {
        int[][] ks = KAESTEN.get(name);
        java.util.List<Rect> aus = new java.util.ArrayList<>();
        if (ks == null || w <= 0 || h <= 0) return aus;
        double fx = w / (double) BREITE, fy = h / (double) HOEHE;
        for (int[] k : ks) {
            aus.add(new Rect((int) Math.floor(k[0] * fx), (int) Math.floor(k[1] * fy),
                    (int) Math.ceil(k[2] * fx), (int) Math.ceil(k[3] * fy)));
        }
        return aus;
    }

    /**
     * Ist die Vorlage im Bild? Erst im Kasten, dann (selten) im ganzen Bild.
     *
     * @param schwelle    ab diesem Wert gilt ein Treffer (wie bei der bisherigen Suche im ganzen Bild)
     * @param ganzesBild  die bisherige Suche im ganzen Bild -- nur fuer das Sicherheitsnetz; ohne Kasten fuer diese
     *                    Vorlage ist es die einzige Suche
     */
    static boolean sichtbar(Matcher m, Mat screen, String name, double schwelle, BooleanSupplier ganzesBild) {
        if (!KAESTEN.containsKey(name) || m == null || screen == null || screen.empty()) return ganzesBild.getAsBoolean();
        String schluessel = name + "|" + schwelle;
        synchronized (SCHLOSS) {
            if (screen != memoBild) {
                MEMO.clear();
                memoBild = screen;
            }
            Boolean bekannt = MEMO.get(schluessel);
            if (bekannt != null) return bekannt;
        }
        boolean da = false;
        for (Rect r : kaesten(name, screen.width(), screen.height())) {
            if (m.findImBereich(screen, name, r, schwelle).isPresent()) {
                da = true;
                break;
            }
        }
        if (!da) {
            int n;
            synchronized (SCHLOSS) {
                n = FEHLSCHLAEGE.merge(name, 1, Integer::sum);
            }
            // Das erste Mal und danach jedes netzAlle-te Mal: ganz suchen. Die uebrigen Male gilt der Kasten.
            da = (n - 1) % NETZ_ALLE == 0 && ganzesBild.getAsBoolean();
        }
        synchronized (SCHLOSS) {
            if (screen == memoBild) MEMO.put(schluessel, da);
        }
        return da;
    }

    /** Nur fuer Tests: Zaehler und Zwischenspeicher leeren. */
    static void zuruecksetzen() {
        synchronized (SCHLOSS) {
            MEMO.clear();
            FEHLSCHLAEGE.clear();
            memoBild = null;
        }
    }
}
