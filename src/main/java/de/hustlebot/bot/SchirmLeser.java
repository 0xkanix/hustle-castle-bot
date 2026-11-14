package de.hustlebot.bot;

import de.hustlebot.core.Gegnerbuch;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Liest das Fenster "Patronage" (Schirmherrschaften): wie viele, wie viele gute, wie viele gute Stigmas.
 *
 * Autor 11.10. (Nachtrag zu Auftrag 6, Punkt 4): "Schirmherren (Patrons) und die Stigma-Sets darauf NICHT nur
 * merken, sondern einordnen und punkten -- genau wie in der Arena bei Gegnern: dieselben Vorlagen
 * (ar_schirm_gut_*, ar_stigma_gut_*) und dieselbe Logik/Punkte wie in ArenaPruefer. Es gibt nur wenige gute."
 * Darum steckt das Zaehlen jetzt hier, und der ArenaPruefer (Gegner) wie der Teamscan (eigenes Team) rufen
 * dieselbe Stelle auf. Punkte: Gegnerbuch.schirmPunkte -- +1 je Schirmherrschaft, +3 je guter, +2 je gutem Stigma.
 *
 * Zwei Fenster, dieselben Karten (Vorlagen in templates/gelernt/arena/schirmherrschaften):
 *  - Arena, Gegner: kleines Fenster "Patronage" mit bis zu 3 Karten -- das ganze Bild wird durchsucht.
 *  - Eigenes Team (Burg -> Rift -> Titan Patrons -> Patronage, Autors Bild doku/team-scan/14.webp): grosses Fenster,
 *    LINKS die 3 aktiven ("Combat abilities"), RECHTS die Liste ALLER Schirmherren mit Stufen -- es darf nur links
 *    gezaehlt werden, sonst zaehlt der Bot die Liste mit (9 x "Lv." statt 3).
 *
 * Gemessen 11.10. an Autors Bild 14 (per Handy verkleinert, darum etwas unscharf): die gute Schirmherrschaft
 * ar_schirm_gut_2 trifft links unten mit 0,81/0,83 (farbig/grau) -- die Karten sind also gleich gross wie in der
 * Arena. "Lv." trifft dort nur mit 0,70-0,75 (grau), die Stigma-Vorlagen hoechstens 0,57 -- ob das an der
 * Unschaerfe liegt, muss Autors PC mit einem echten Bild zeigen (SchirmProbe). Der Titel "Patronage" steht im
 * eigenen Fenster gross und gelb, nicht wie in der Arena klein und weiss: ar_schirm_titel trifft dort NICHT.
 */
final class SchirmLeser {

    private SchirmLeser() { }

    /** Titel "Patronage" des kleinen Arena-Fensters (im grossen eigenen Fenster anders gezeichnet). */
    static final String T_TITEL = "ar_schirm_titel";
    /** "Lv." unter jeder Karte -- zum Zaehlen. */
    static final String T_LV = "ar_schirm_lv";
    /** Serie der guten Schirmherrschaften (Faehigkeits-Bild in der Mitte der Karte, 40x40). */
    static final String S_SCHIRM_GUT = "ar_schirm_gut";
    /** Serie der guten Stigmas (weisses Tier auf Orange, oben rechts an der Karte, 30x30). */
    static final String S_STIGMA_GUT = "ar_stigma_gut";

    /** Schwellen wie seit 01.10. im ArenaPruefer. */
    static final double SCHWELLE_LV = 0.80, SCHWELLE_SCHIRM = 0.80, SCHWELLE_STIGMA = 0.84;
    /**
     * Im eigenen Patronage-Fenster wird "Lv." in Graustufen und mit 0,70 gezaehlt (Autors Bild 14: 0,70-0,75).
     * Ein zu viel oder zu wenig kostet nur +-1 Punkt; die guten (+3) und Stigmas (+2) behalten ihre Schwellen.
     */
    static final double SCHWELLE_LV_EIGEN = 0.70;
    /** Hoechstens so viele Schirmherrschaften sind aktiv. */
    static final int MAX = 3;
    /** Zwei Treffer naeher beieinander sind dieselbe Stelle: "Lv." nur waagerecht, Karten-Bilder in beide Richtungen. */
    static final int ABSTAND_LV = 40, ABSTAND_BILD = 30;

    /** Namen der guten Stigmas in der Reihenfolge der Serie ar_stigma_gut_1..3 (doku/OFFEN.md 0001d, 02.10.). */
    static final String[] STIGMA_NAMEN = {"Baer", "Loewe", "Wolf"};

    /**
     * Was im Fenster zu sehen war. {@code anzahl} -1 = nicht angesehen. {@code guteVorlagen} / {@code stigmaVorlagen}:
     * die Vorlagennamen der Treffer (ar_schirm_gut_2, ar_stigma_gut_1 ...), damit Autor sieht, WELCHE es sind.
     */
    record Ergebnis(int anzahl, int gute, int stigmas, List<String> guteVorlagen, List<String> stigmaVorlagen) {

        static Ergebnis unbekannt() {
            return new Ergebnis(-1, 0, 0, List.of(), List.of());
        }

        boolean bekannt() { return anzahl >= 0; }

        /** Dieselben Punkte wie beim Gegner (und dieselben Schalter aus arena-extras.txt). */
        int punkte() {
            return bekannt() ? Gegnerbuch.schirmPunkte(anzahl, gute, stigmas) : 0;
        }

        /** "3 (1 gute, 1 Stigma, +8)" -- unbekannt: "?". */
        String kurz() {
            if (!bekannt()) return "?";
            return anzahl + " (" + gute + " gute, " + stigmas + " Stigma, +" + punkte() + ")";
        }

        /** "Schirmherrschaften 3 (1 gute, 1 Stigma, +8)" -- wie im Arena-Protokoll; unbekannt: "Schirmherrschaften ?". */
        String text() {
            return "Schirmherrschaften " + kurz();
        }

        /** "gute: Nr. 2; Stigma: Baer" -- die Einordnung; leer, wenn nichts Gutes dabei ist. */
        String einordnung() {
            StringBuilder b = new StringBuilder();
            if (!guteVorlagen.isEmpty()) b.append("gute: ").append(String.join(", ", kurznamen(guteVorlagen, S_SCHIRM_GUT, null)));
            if (!stigmaVorlagen.isEmpty()) {
                if (b.length() > 0) b.append("; ");
                b.append("Stigma: ").append(String.join(", ", kurznamen(stigmaVorlagen, S_STIGMA_GUT, STIGMA_NAMEN)));
            }
            return b.toString();
        }
    }

    // ------------------------------------------------------------------
    //  Lesen
    // ------------------------------------------------------------------

    /** Arena: das kleine Fenster des Gegners, ganzes Bild. */
    static Ergebnis lesen(Mat screen, BotContext ctx) {
        return lesen(screen, ctx.matcher(), null, SCHWELLE_LV, false);
    }

    /** Eigenes Team: das grosse Patronage-Fenster, nur die linke Seite mit den 3 aktiven. */
    static Ergebnis lesenEigenes(Mat screen, BotContext ctx) {
        return lesen(screen, ctx.matcher(), eigenerBereich(screen.width(), screen.height()), SCHWELLE_LV_EIGEN, true);
    }

    /**
     * Die linke Seite des eigenen Patronage-Fensters (Autors Bild 14, 1159x770): die drei aktiven Karten stehen
     * zwischen x 60-520 und y 150-640; die Liste rechts beginnt bei x ~580. Anteile, damit es bei jeder
     * Fenstergroesse passt.
     */
    static Rect eigenerBereich(int breite, int hoehe) {
        int x = 0, y = (int) Math.round(hoehe * 0.15);
        int w = (int) Math.round(breite * 0.50), h = (int) Math.round(hoehe * 0.72);
        return new Rect(x, y, w, h);
    }

    /**
     * Kern: zaehlt "Lv." (Anzahl), die Serie der guten und die Serie der Stigmas -- je hoechstens {@link #MAX}.
     *
     * @param bereich   null = ganzes Bild; sonst nur dieser Ausschnitt (Treffer kommen in Bildkoordinaten zurueck)
     * @param schwelleLv Schwelle fuer "Lv."
     * @param lvGrau    "Lv." in Graustufen suchen (eigenes Fenster) statt farbig (Arena, wie bisher)
     */
    static Ergebnis lesen(Mat screen, Matcher m, Rect bereich, double schwelleLv, boolean lvGrau) {
        if (screen == null || screen.empty() || m == null) return Ergebnis.unbekannt();
        Mat bild = screen;
        int dx = 0, dy = 0;
        if (bereich != null) {
            Rect r = eingepasst(bereich, screen.width(), screen.height());
            if (r.width <= 0 || r.height <= 0) return Ergebnis.unbekannt();
            bild = new Mat(screen, r);
            dx = r.x;
            dy = r.y;
        }
        try {
            List<Matcher.Hit> lv = lvGrau ? m.findAllGrau(bild, T_LV, schwelleLv, 3 * MAX)
                                          : m.findAll(bild, T_LV, schwelleLv, 3 * MAX);
            int anzahl = Math.min(MAX, stellen(lv, ABSTAND_LV, Integer.MAX_VALUE).size());
            List<Matcher.Hit> gute = serieTreffer(bild, m, S_SCHIRM_GUT, SCHWELLE_SCHIRM);
            List<Matcher.Hit> stigmas = serieTreffer(bild, m, S_STIGMA_GUT, SCHWELLE_STIGMA);
            return new Ergebnis(anzahl, Math.min(MAX, gute.size()), Math.min(MAX, stigmas.size()),
                    namen(gute), namen(stigmas));
        } finally {
            if (bild != screen) bild.release();
        }
    }

    /** Wie viele verschiedene Stellen eine Bilderserie trifft (hoechstens {@link #MAX}) -- auch fuer die Pals. */
    static int zaehleSerie(Mat screen, Matcher m, String serie, double schwelle) {
        return Math.min(MAX, serieTreffer(screen, m, serie, schwelle).size());
    }

    /** Je verschiedener Stelle ein Treffer (der erste, der sie fand). */
    static List<Matcher.Hit> serieTreffer(Mat screen, Matcher m, String serie, double schwelle) {
        List<Matcher.Hit> alle = new ArrayList<>();
        for (String t : m.serie(serie)) {
            alle.addAll(m.findAll(screen, t, schwelle, MAX));
        }
        return stellen(alle, ABSTAND_BILD, ABSTAND_BILD);
    }

    /**
     * Treffer an verschiedenen Stellen: zwei Treffer, die waagerecht naeher als {@code dx} UND senkrecht naeher als
     * {@code dy} liegen, sind dieselbe Stelle (der erste bleibt). Reine Rechnung, ohne Bild -- fuer Tests.
     */
    static List<Matcher.Hit> stellen(List<Matcher.Hit> treffer, int dx, int dy) {
        List<Matcher.Hit> aus = new ArrayList<>();
        for (Matcher.Hit h : treffer) {
            boolean neu = true;
            for (Matcher.Hit s : aus) {
                if (Math.abs(s.centerX() - h.centerX()) < dx && Math.abs(s.centerY() - h.centerY()) < dy) {
                    neu = false;
                    break;
                }
            }
            if (neu) aus.add(h);
        }
        return aus;
    }

    /** Fehlt etwas im Vorlagenordner? Leere Liste = alles da. */
    static List<String> fehlt(Matcher m) {
        List<String> f = new ArrayList<>();
        if (!m.has(T_LV)) f.add(T_LV);
        if (m.serie(S_SCHIRM_GUT).isEmpty()) f.add(S_SCHIRM_GUT + "_1");
        if (m.serie(S_STIGMA_GUT).isEmpty()) f.add(S_STIGMA_GUT + "_1");
        return f;
    }

    /** Ins Protokoll, einheitlich fuer Gegner und eigenes Team. */
    static void protokoll(String wessen, Ergebnis e) {
        String ein = e.einordnung();
        Log.info("Arena", "Schirmherrschaften " + wessen + ": " + e.kurz() + (ein.isEmpty() ? "" : " -- " + ein) + ".");
    }

    // ------------------------------------------------------------------

    private static Rect eingepasst(Rect r, int breite, int hoehe) {
        int x = Math.max(0, r.x), y = Math.max(0, r.y);
        int w = Math.min(breite, r.x + r.width) - x, h = Math.min(hoehe, r.y + r.height) - y;
        return new Rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    private static List<String> namen(List<Matcher.Hit> hits) {
        List<String> n = new ArrayList<>();
        for (Matcher.Hit h : hits) n.add(h.name());
        return n;
    }

    /** "ar_stigma_gut_1" -> "Baer", "ar_schirm_gut_2" -> "Nr. 2", "ar_schirm_gut_alt" -> "alt". */
    static List<String> kurznamen(List<String> vorlagen, String serie, String[] namen) {
        List<String> aus = new ArrayList<>();
        for (String v : vorlagen) {
            String rest = v.startsWith(serie + "_") ? v.substring(serie.length() + 1) : v.equals(serie) ? "1" : v;
            String kurz = rest;
            if (rest.matches("\\d{1,6}")) {
                int nr = Integer.parseInt(rest);
                kurz = namen != null && nr >= 1 && nr <= namen.length ? namen[nr - 1] : "Nr. " + nr;
            }
            aus.add(kurz);
        }
        return aus;
    }
}
