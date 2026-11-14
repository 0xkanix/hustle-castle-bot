package de.hustlebot.bot;

import de.hustlebot.core.Log;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import de.hustlebot.core.Sprache;

/**
 * Steine (Edelsteine) auf den Gegenstaenden einer gegnerischen Einheit zaehlen (Autor, 30.09.):
 * "bei jeder Einheit schauen, ob sie so einen Stein auf Brustplatte/Ring/Amulett/Waffe/
 * Waffenseele/Ruestungsseele/Totem/Artefakt hat -- je gelbem Stein +0,5 Punkte, je rotem
 * +0,1. Orange und violette Steine sind egal."
 *
 * WO DIE STEINE SITZEN. Im Einheitenfenster liegen acht Gegenstaende: vier grosse (zwei
 * Spalten, zwei Reihen: links Waffenseele und Ruestungsseele, rechts Waffe und Brustplatte)
 * und vier kleine darunter (Totem, Ring, Amulett, Artefakt). Unter jedem Gegenstand sitzt
 * eine Reihe Sechsecke, MITTIG unter dem Gegenstand, rund 90 Bildpunkte breit -- und es sind
 * nicht immer gleich viele: Jackal (30.09.) hatte 5, 6, 5, 6, 5, 6, 6, 5 Plaetze. Bei sechs
 * ueberlappen die Sechsecke enger (Abstand 15), bei fuenf weiter (18). Leer = dunkelgrau, sonst
 * die Farbe des Steins.
 *
 * Gemessen an Autors Bildern vom 30.09. (Fenster 1159 x 770), bezogen auf die Mitte der
 * Lebens-Zeile ("2 133 356/2 133 356" neben dem Herzen):
 *
 *   Reihe 1 (obere grosse):  +170 tiefer, Mitte der Reihe bei -140 (links) und +133 (rechts)
 *   Reihe 2 (untere grosse): +300 tiefer, gleiche Spalten
 *   Reihe 3 (vier kleine):   +425 tiefer, Mitten bei -148, -51, +47, +145
 *
 * Weil Autors Bilder eine Titelleiste hatten und die feste Stelle von Hand gezogen ist,
 * sucht der Zaehler jede Reihe im Umkreis von 6 Bildpunkten und probiert 6, 5, 4 und 3
 * Plaetze: Die Anordnung, bei der die meisten Stellen wie ein Stein-Platz aussehen (grauer
 * Leerplatz oder satter Stein), ist die richtige -- bei der falschen Zahl liegen die
 * Messstellen auf den dunklen Raendern zwischen den Steinen.
 *
 * FARBEN (gemessen): gelb (195,170,0) und (232,194,5), Farbton 50-53; rot (195,0,46) bis
 * (232,0,54), Farbton 345-355; orange (195,85,14) bis (232,119,16), Farbton 22-29; leer
 * (88,85,89); Hintergrund zwischen den Reihen (187,244,241). Unterschieden wird ueber den
 * Farbton: rot ab 335 und bis 12, gelb ab 48, alles andere (orange, violett, gruen) zaehlt 0.
 *
 * GLANZ (Autor, 30.09.: "die Items glaenzen nur"): Ueber die Gegenstaende laeuft ein heller
 * Schimmer. Ein roter Stein darunter wird orange-rot (255,102,52), ein oranger gelblich
 * (255,184,18). Der Schimmer wandert -- darum zaehlt der Pruefer ZWEI Bilder kurz
 * nacheinander und nimmt je Platz die "dunklere" Lesung: rot vor orange vor gelb. Der Glanz
 * macht einen Stein nie roeter, also gilt rot, sobald ein Bild rot sagt.
 */
final class ArenaSteine {

    /** ORANGE = legendaer, VIOLETT = episch (beide 0 Punkte, aber sauber auseinandergehalten). */
    enum Art { NICHTS, LEER, GELB, ROT, ORANGE, VIOLETT, ANDERE }

    /** Autor 22.10.: je legendaerem (orangem) Stein -0,1, je epischem (violettem) -0,05 -- als Abzug auf die Set-Punkte. */
    static final double LEGENDAER = -0.1, EPISCH = -0.05;

    /** Gezaehlte Steine einer Einheit. */
    record Zaehlung(int gelb, int rot, int andere, int leer, int reihenGefunden, List<List<Art>> reihen) {
        /** Punkte in Zehnteln: 5 je gelbem, 1 je rotem Stein. */
        int zehntel() { return 5 * gelb + rot; }
        /** Legendaere (orange) Steine -- stecken in andere. */
        int orange() { return anzahl(Art.ORANGE); }
        /** Epische (violette) Steine -- stecken in andere. */
        int violett() { return anzahl(Art.VIOLETT); }

        private int anzahl(Art art) {
            int n = 0;
            for (List<Art> r : reihen) for (Art a : r) if (a == art) n++;
            return n;
        }
    }

    /** Abzug fuer legendaere und epische Steine (negativ oder 0). */
    static double abzug(int orange, int violett) {
        return LEGENDAER * orange + EPISCH * violett;
    }

    private static final int SUCHRADIUS = 6;

    /** Reihen: {dy, dx1, dx2, ...} -- Versatz der Reihen-MITTE zur Mitte der Lebens-Zeile bei 1159 x 770. */
    private static final int[][] REIHEN = {
            {170, -140, 133},
            {300, -140, 133},
            {425, -148, -51, 47, 145},
    };

    private ArenaSteine() { }

    /**
     * @param bild    das ganze Spielbild
     * @param ankerX  Mitte der Lebens-Zeile (x)
     * @param ankerY  Mitte der Lebens-Zeile (y)
     */
    static Zaehlung zaehlen(BufferedImage bild, int ankerX, int ankerY) {
        List<List<Art>> reihen = new ArrayList<>();
        if (bild == null) return zusammen(reihen);
        double fx = bild.getWidth() / 1159.0, fy = bild.getHeight() / 770.0;
        for (int[] reihe : REIHEN) {
            int y0 = ankerY + (int) Math.round(reihe[0] * fy);
            for (int i = 1; i < reihe.length; i++) {
                int cx = ankerX + (int) Math.round(reihe[i] * fx);
                reihen.add(reiheLesen(bild, cx, y0, fx));
            }
        }
        return zusammen(reihen);
    }

    /** Zwei Lesungen derselben Einheit vereinen: je Platz die dunklere (rot vor orange vor gelb). */
    static Zaehlung vereinen(Zaehlung a, Zaehlung b) {
        if (a == null) return b;
        if (b == null) return a;
        List<List<Art>> reihen = new ArrayList<>();
        for (int r = 0; r < Math.max(a.reihen().size(), b.reihen().size()); r++) {
            List<Art> ra = r < a.reihen().size() ? a.reihen().get(r) : List.of();
            List<Art> rb = r < b.reihen().size() ? b.reihen().get(r) : List.of();
            if (ra.size() != rb.size()) {
                // Verschiedene Platzzahl erkannt: die Lesung mit mehr Plaetzen ist die sicherere.
                reihen.add(ra.size() >= rb.size() ? ra : rb);
                continue;
            }
            List<Art> m = new ArrayList<>();
            for (int k = 0; k < ra.size(); k++) m.add(dunkler(ra.get(k), rb.get(k)));
            reihen.add(m);
        }
        return zusammen(reihen);
    }

    private static Art dunkler(Art x, Art y) {
        if (x == Art.ROT || y == Art.ROT) return Art.ROT;
        if (x == Art.VIOLETT || y == Art.VIOLETT) return Art.VIOLETT;
        if (x == Art.ORANGE || y == Art.ORANGE) return Art.ORANGE;
        if (x == Art.ANDERE || y == Art.ANDERE) return Art.ANDERE;
        if (x == Art.GELB || y == Art.GELB) return Art.GELB;
        if (x == Art.LEER || y == Art.LEER) return Art.LEER;
        return Art.NICHTS;
    }

    private static Zaehlung zusammen(List<List<Art>> reihen) {
        int gelb = 0, rot = 0, andere = 0, leer = 0, gefunden = 0;
        for (List<Art> r : reihen) {
            if (!r.isEmpty()) gefunden++;
            for (Art a : r) {
                switch (a) {
                    case GELB -> gelb++;
                    case ROT -> rot++;
                    case ORANGE, VIOLETT, ANDERE -> andere++;
                    case LEER -> leer++;
                    default -> { }
                }
            }
        }
        return new Zaehlung(gelb, rot, andere, leer, gefunden, reihen);
    }

    /**
     * Eine Reihe lesen: Die Sechsecke werden entlang der Reihe GEZAEHLT -- zwischen zwei Steinen
     * liegt immer ein dunkler Rand (bei grauen Leerplaetzen fast schwarz, bei roten Steinen
     * dunkelrot, immer deutlich dunkler als die Fuellung). Jeder helle Abschnitt dazwischen ist
     * ein Platz. So stimmt die Zahl bei 1 bis 7 Plaetzen, ohne dass der Bot die Anzahl raten muss.
     * Die Reihe wird in 13 Zeilen (Mitte +/- 6) gesucht: die Zeile mit den meisten sauberen
     * Abschnitten ist die Mitte der Sechsecke.
     * Leere Liste = keine Reihe erkannt (Fenster verschoben, verdeckt, Kachel ohne Plaetze).
     */
    private static List<Art> reiheLesen(BufferedImage bild, int cx, int cy, double fx) {
        int halb = (int) Math.round(52 * fx);
        int minLaenge = (int) Math.round(6 * fx), maxLaenge = (int) Math.round(26 * fx);
        List<int[]> besteAbschnitte = List.of();
        int bestY = cy, bestAbstand = Integer.MAX_VALUE, besteWertung = Integer.MIN_VALUE;
        for (int dy = -SUCHRADIUS; dy <= SUCHRADIUS; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= bild.getHeight()) continue;
            int[] geteilt = new int[1];
            List<int[]> roh = abschnitte(bild, cx - halb, cx + halb, y, minLaenge, maxLaenge, geteilt);
            // Nur Abschnitte, die innerhalb der Reihe liegen und wie ein Platz aussehen: Der Rahmen
            // der Kachel (rosa, tuerkis) und das Level-Abzeichen ragen an den Enden hinein
            // (Mar2, 30.09.: rosa Rahmen als achter "Stein").
            List<int[]> abschnitte = new ArrayList<>();
            for (int[] ab : roh) {
                int mitte = (ab[0] + ab[1]) / 2;
                if (Math.abs(mitte - cx) > 48 * fx) continue;
                Art a = steinArt(bild, mitte, y);
                if (a == Art.NICHTS || a == Art.ANDERE) continue;
                abschnitte.add(ab);
            }
            if (abschnitte.size() > 7) continue;   // mehr als sieben Plaetze gibt es nicht -- falsche Zeile
            // Genau in der Mitte der Sechsecke beruehren sich die Nachbarn an den Spitzen -- dort
            // fehlt der Rand oft ("13", 30.09.: zwei gelbe Steine als ein 19er-Abschnitt). Etwas
            // darueber oder darunter sind die Raender breit. Darum zaehlt eine Zeile, in der
            // Abschnitte geteilt werden mussten, weniger als eine mit sauberen Abschnitten.
            int wertung = abschnitte.size() - 3 * geteilt[0];
            int abstand = Math.abs(dy);
            if (wertung > besteWertung || (wertung == besteWertung && abstand < bestAbstand)) {
                besteWertung = wertung;
                besteAbschnitte = abschnitte;
                bestY = y;
                bestAbstand = abstand;
            }
        }
        List<Art> plaetze = new ArrayList<>();
        if (Boolean.getBoolean("arena.steine.debug")) {
            StringBuilder b = new StringBuilder();
            for (int[] ab : besteAbschnitte) b.append(ab[1] - ab[0] + 1).append(' ');
            System.out.println("  Reihe " + cx + "," + cy + " -> Zeile " + bestY + ", Wertung " + besteWertung
                    + ", Abschnitte [" + b.toString().trim() + "]");
        }
        if (besteAbschnitte.isEmpty() || besteAbschnitte.size() > 7) {
            Log.debug("Arena", "Stein-Reihe bei " + cx + "," + cy + ": nicht erkannt ("
                    + besteAbschnitte.size() + " Abschnitte).");
            return plaetze;
        }
        for (int[] ab : besteAbschnitte) plaetze.add(steinArt(bild, (ab[0] + ab[1]) / 2, bestY));
        Log.debug("Arena", "Stein-Reihe bei " + cx + "," + cy + ": " + plaetze.size() + " Plaetze in Zeile "
                + bestY + ": " + plaetze);
        return plaetze;
    }

    /**
     * Helle Abschnitte einer Zeile zwischen dunklen Raendern. Hell heisst: satte Farbe mit
     * mindestens 72 % der Reihen-Fuellung, oder das Grau eines Leerplatzes (60..130). Der helle
     * Hintergrund (187,244,241) ist blass und zaehlt nicht. Ein Abschnitt, der laenger ist als
     * anderthalb typische, sind zwei Steine, deren Rand der Glanz ueberstrahlt hat.
     */
    private static List<int[]> abschnitte(BufferedImage bild, int x0, int x1, int y, int minLaenge, int maxLaenge,
                                          int[] geteilt) {
        List<int[]> ergebnis = new ArrayList<>();
        x0 = Math.max(0, x0);
        x1 = Math.min(bild.getWidth() - 1, x1);
        if (x1 - x0 < 10) return ergebnis;
        int n = x1 - x0 + 1;
        int[] hell = new int[n];
        double[] satt = new double[n];
        List<Integer> satte = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int p = bild.getRGB(x0 + i, y);
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            hell[i] = max;
            satt[i] = max == 0 ? 0 : (max - min) / (double) max;
            if (satt[i] >= 0.35 && max >= 100) satte.add(max);
        }
        // Fuellung der Reihe: das 85. Prozent der satten Helligkeiten (Glanzpunkte weggelassen).
        int fuellung = 0;
        if (satte.size() >= 8) {
            satte.sort(null);
            fuellung = satte.get(satte.size() * 85 / 100);
        }
        int schwelle = (int) (fuellung * 0.72);
        int start = -1;
        List<int[]> roh = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            boolean platz = i < n && ((satt[i] >= 0.35 && fuellung > 0 && hell[i] >= schwelle)
                    || (satt[i] < 0.35 && hell[i] >= 60 && hell[i] <= 130));
            if (platz && start < 0) start = i;
            if (!platz && start >= 0) {
                roh.add(new int[]{x0 + start, x0 + i - 1});
                start = -1;
            }
        }
        // Typische Laenge = Median; zu lange Abschnitte teilen (ueberstrahlter Rand), zu kurze weg.
        List<Integer> laengen = new ArrayList<>();
        for (int[] ab : roh) {
            int l = ab[1] - ab[0] + 1;
            if (l >= minLaenge) laengen.add(l);
        }
        if (laengen.isEmpty()) return ergebnis;
        laengen.sort(null);
        int typisch = laengen.get(laengen.size() / 2);
        for (int[] ab : roh) {
            int l = ab[1] - ab[0] + 1;
            if (l < minLaenge) continue;
            int teile = (int) Math.round(l / (double) typisch);
            if (teile < 1) teile = 1;
            if (teile == 1 && l > maxLaenge) continue;     // eine einzelne lange Flaeche ist kein Stein
            if (teile > 1) geteilt[0]++;
            for (int t = 0; t < teile; t++) {
                int von = ab[0] + (int) Math.round(t * l / (double) teile);
                int bis = ab[0] + (int) Math.round((t + 1) * l / (double) teile) - 1;
                ergebnis.add(new int[]{von, bis});
            }
        }
        return ergebnis;
    }

    /**
     * Was an dieser Stelle sitzt: 3 x 3 Bildpunkte um die Mitte, damit ein Bildpunkt Versatz
     * nichts ausmacht. Ein satter Farbpunkt zaehlt als Stein, die Farbe entscheidet die Mehrheit.
     */
    private static Art steinArt(BufferedImage bild, int cx, int cy) {
        int gelb = 0, rot = 0, orange = 0, violett = 0, andere = 0, leer = 0;
        // Zwei Bildpunkte unter der Mitte: das Level-Abzeichen (rechts oben an der Kachel) ragt
        // von oben in die letzten Plaetze der Reihe ("13", 30.09.: zwei gelbe Steine als orange).
        cy += 2;
        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (x < 0 || y < 0 || x >= bild.getWidth() || y >= bild.getHeight()) continue;
                int p = bild.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max < 70) continue;                 // Rand/Schatten
                double saettigung = (max - min) / (double) max;
                if (saettigung < 0.35) {
                    // Grau: der leere Platz (etwa 88,85,89). Der helle Hintergrund (187,244,241)
                    // ist blaeulich und hell -- der zaehlt nicht.
                    if (max < 130) leer++;
                    continue;
                }
                if (max < 150) continue;                // dunkler Rand eines Steins
                double ton = farbton(r, g, b);
                if (ton >= 335 || ton < 12) rot++;
                else if (ton >= 48 && ton < 70) gelb++;   // echte gelbe Steine: Farbton 50-53
                else if (ton >= 12 && ton < 48) orange++; // legendaer (~25), auch orange unter Glanz
                else if (ton >= 250 && ton < 335) violett++;   // episch (~280)
                else andere++;                          // gruen, blau ...
                // Ein oranger Stein unter dem Glanz wird gelblich (Farbton 41-46) -- der zaehlt
                // nicht als gelb, darum die Grenze bei 48. Ein roter unter dem Glanz wird
                // orange-rot (Farbton 9-23) -- das faengt das zweite Bild (vereinen).
            }
        }
        int satt = gelb + rot + orange + violett + andere;
        if (satt >= 3) {
            int max = Math.max(Math.max(gelb, rot), Math.max(Math.max(orange, violett), andere));
            if (rot == max) return Art.ROT;
            if (violett == max) return Art.VIOLETT;
            if (orange == max) return Art.ORANGE;
            if (gelb == max) return Art.GELB;
            return Art.ANDERE;
        }
        return leer >= 4 ? Art.LEER : Art.NICHTS;
    }

    /** Farbton 0..360 wie bei HSV. */
    private static double farbton(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == min) return 0;
        double d = max - min, h;
        if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    /** Fuer das Protokoll: "3 gelb, 12 rot (+1,7)". */
    static String text(int gelb, int rot) {
        int zehntel = 5 * gelb + rot;
        return gelb + " gelb, " + rot + " rot (+" + (zehntel / 10) + "," + (zehntel % 10) + ")";
    }

    static void protokoll(int einheit, Zaehlung z) {
        int sonst = z.andere() - z.orange() - z.violett();
        Log.info("Arena", Sprache.t("Unit " + einheit + ": stones " + text(z.gelb(), z.rot())
                + (z.orange() > 0 ? ", " + z.orange() + " legendary" : "") + (z.violett() > 0 ? ", " + z.violett() + " epic" : "")
                + (sonst > 0 ? ", " + sonst + " other" : "") + (z.leer() > 0 ? ", " + z.leer() + " empty" : "") + ", rows found " + z.reihenGefunden() + " of 8.",
                "Einheit " + einheit + ": Steine " + text(z.gelb(), z.rot())
                + (z.orange() > 0 ? ", " + z.orange() + " legendaer" : "")
                + (z.violett() > 0 ? ", " + z.violett() + " episch" : "")
                + (sonst > 0 ? ", " + sonst + " andere" : "")
                + (z.leer() > 0 ? ", " + z.leer() + " leer" : "")
                + ", Reihen erkannt " + z.reihenGefunden() + " von 8."));
    }
}
