package de.hustlebot.bot;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Die Turnierpunkte aller zehn Plaetze der Pyramide und die Top-3-Rechnung (Autor 21.10.).
 *
 * Autor: "Der Bot soll auf die Punkte der Gegner schauen und sich ausrechnen, wen er am besten angreift / wie viele
 * Punkte er braucht, um Top 1-3 zu bekommen." Und: "Wenn ich in der fuenften Runde auf Platz 2 bin und Platz 1 ist
 * insanely stark, soll er lieber auf 150 gehen und Top 2-3 sichern, anstatt Top 1 zu bekommen."
 *
 * In jedem Schild steht unter Name und Clan die Turnierpunktzahl (gekreuzte Schwerter, dahinter z. B. "825", ein
 * gruener Haken, wenn der Spieler in dieser Runde schon angegriffen hat). Die Pyramide ist nach diesen Punkten
 * sortiert: Platz 1 oben, Platz 10 unten rechts.
 */
final class ArenaTabelle {

    private ArenaTabelle() { }

    /** Mitte der Punktezeile je Platz (Index 1..10), als Anteil des Bildes -- gemessen an Autors Bild vom 15.10. (1159x770). */
    static final double[] X = {0, 0.500, 0.400, 0.601, 0.298, 0.500, 0.701, 0.197, 0.400, 0.601, 0.802};
    static final double[] Y = {0, 0.292, 0.465, 0.465, 0.639, 0.639, 0.639, 0.813, 0.813, 0.813, 0.813};

    /** Mehr Turnierpunkte gibt es nicht (5 x 225 plus Zuschlaege) -- alles darueber ist verlesen. */
    static final long MAX_PUNKTE = 1300;

    /** Punkte je Platz (Index 1..10), -1 = nicht gelesen. {@code eigenerPlatz}: dein Schild (gelbe Zahl), 0 = unbekannt. */
    static long[] punkteLesen(BufferedImage bild, int eigenerPlatz) {
        long[] p = new long[11];
        Arrays.fill(p, -1);
        if (bild == null) return p;
        int w = bild.getWidth(), h = bild.getHeight();
        for (int i = 1; i <= 10; i++) {
            int x0 = (int) ((X[i] - 0.055) * w), y0 = (int) ((Y[i] - 0.013) * h);
            int bw = (int) (0.115 * w), bh = (int) (0.028 * h);
            long z = ziffernLesen(weissMaske(bild, x0, y0, bw, bh));
            if (z < 0 && i == eigenerPlatz) {   // eigenes Schild: gelbe Zahl -- nur dort die (langsamere) Texterkennung
                z = ArenaLeser.zahlAus(ArenaLeser.liesTextGross(bild, x0, y0, bw, bh)).orElse(-1);
            }
            if (z >= 0 && z <= MAX_PUNKTE) p[i] = z;
        }
        return p;
    }

    /**
     * Die Punkte stehen in WEISSER Schrift mit dunklem Rand auf hellem Schild. Nur reines Weiss zaehlt -- Schwerter,
     * gruener Haken, Rand und Schildgrund fallen weg (gemessen an Autors Bild vom 15.10.: alle neun Gegnerzahlen glatt).
     * Die Windows-Texterkennung las die einzelnen Zahlen trotzdem nur wacklig, darum erkennt der Bot die zehn Ziffern
     * der Spielschrift selbst (MUSTER).
     */
    static boolean[][] weissMaske(BufferedImage bild, int x, int y, int breite, int hoehe) {
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        boolean[][] m = new boolean[Math.max(0, y2 - y1)][Math.max(0, x2 - x1)];
        for (int yy = y1; yy < y2; yy++) {
            for (int xx = x1; xx < x2; xx++) {
                int p = bild.getRGB(xx, yy);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                m[yy - y1][xx - x1] = Math.min(r, Math.min(g, b)) > WEISS_AB;
            }
        }
        return m;
    }

    /** Breite und Hoehe der Ziffernmuster. */
    static final int MW = 8, MH = 12;

    /**
     * Die zehn Ziffern der Spielschrift, je 8 x 12 ('#' = weisse Fuellung) -- aus Autors Pyramiden-Bildern vom 15.10.
     * (825, 812, 591, 523, 300, 375, 150, 143, 0, 820, 749, 593, 668, 503, 295).
     */
    static final String[] MUSTER = {
            "...###...######..##...#####...#####...####.....###.....###.....####...#####...##.##...##..#####.",
            "....#.....###...#####...#####......##......##......##......##......##......##......###..########",
            "#######.########.....###......##.....###.....##.....###....###....###....###....################",
            "..####..########.....###......##......##.....##...#####......###......##......###.....#########.",
            "......#.....###.....###....####....####......##...#..##..#...##..##.############.....###.....##.",
            ".##################.....###.....###.....#######.########......##.......#.......#.#....#########.",
            "...#####..######.###.....##.....###.....############.######...#####...##.##...##.###.###..#####.",
            "################......##......##.....##......##.....##......##.....##.....###.....##.....###....",
            "..#####..##..#####....####....##.##...##.######..######.##....####.....###....#####..###.######.",
            ".#####..#######.##....####....####....#####..###.#######..##..##......##......####..###########.",
    };

    /** So viele der 96 Bildpunkte duerfen hoechstens vom besten Muster abweichen. */
    static final int MUSTER_TOLERANZ = 22;

    /** Die Zahl aus einer Weiss-Maske: Ziffern spaltenweise trennen und mit den Mustern vergleichen. -1 = nicht lesbar. */
    static long ziffernLesen(boolean[][] m) {
        if (m.length == 0) return -1;
        int h = m.length, w = m[0].length;
        StringBuilder zahl = new StringBuilder();
        int start = -1;
        for (int x = 0; x <= w; x++) {
            boolean spalte = false;
            if (x < w) for (int y = 0; y < h; y++) spalte |= m[y][x];
            if (spalte && start < 0) start = x;
            if (spalte || start < 0) continue;
            int y1 = h, y2 = -1, n = 0;
            for (int xx = start; xx < x; xx++) {
                for (int y = 0; y < h; y++) {
                    if (m[y][xx]) { y1 = Math.min(y1, y); y2 = Math.max(y2, y); n++; }
                }
            }
            int breite = x - start;
            if (n >= 6 && y2 - y1 >= 7) {   // Krumen (Glanzpunkte) zaehlen nicht
                if (breite > 2 * MW + 2) return -1;   // zwei Ziffern ohne Luecke -- lieber nichts als Falsches
                int z = ziffer(m, start, x - 1, y1, y2);
                if (z < 0) return -1;
                zahl.append((char) ('0' + z));
            }
            start = -1;
        }
        if (zahl.isEmpty() || zahl.length() > 4) return -1;
        return Long.parseLong(zahl.toString());
    }

    /**
     * Die Stage-Nummer aus "Stage: 3/5" (Ben 19.-21.10.: die Texterkennung lieferte die Zahl oft nicht, der Bot wartete
     * noch auf Runde 3, waehrend schon Stage 4 lief). Rechts neben dem Wort "Stage:" stehen weiss "3", "/", "5" -- die
     * Ziffer und der Schraegstrich beruehren sich manchmal. Die "5" am Ende ist immer da: ihre Breite ist die
     * Ziffernbreite, und sie muss als 5 erkannt werden, sonst gilt nichts. -1 = nicht lesbar.
     */
    static int stageLesen(boolean[][] m) {
        if (m.length == 0) return -1;
        int h = m.length, w = m[0].length;
        java.util.List<int[]> laeufe = new java.util.ArrayList<>();   // {von, bis}
        int start = -1;
        for (int x = 0; x <= w; x++) {
            boolean spalte = false;
            if (x < w) for (int y = 0; y < h; y++) spalte |= m[y][x];
            if (spalte && start < 0) start = x;
            if (!spalte && start >= 0) {
                laeufe.add(new int[]{start, x - 1});
                start = -1;
            }
        }
        // die "5": der letzte Lauf vor einer Luecke von mindestens 8 Spalten (danach kommt das Fragezeichen)
        int fuenf = -1;
        for (int i = 0; i < laeufe.size(); i++) {
            boolean letzter = i == laeufe.size() - 1 || laeufe.get(i + 1)[0] - laeufe.get(i)[1] > 8;
            if (letzter && i >= 1) { fuenf = i; break; }
        }
        if (fuenf < 1) return -1;
        int[] f = laeufe.get(fuenf);
        int breite = f[1] - f[0] + 1;
        if (zifferIn(m, f[0], f[1]) != 5) return -1;
        int[] erste = laeufe.get(0);
        int bis = Math.min(erste[1], erste[0] + breite - 1);
        int z = zifferIn(m, erste[0], bis);
        return z >= 1 && z <= 5 ? z : -1;
    }

    /**
     * Die Rundenuhr "01:40" in Sekunden (Ben 21.10.: mit der Texterkennung endete die Runde 12-19 s frueher als
     * gedacht). Weisse Ziffern; der Doppelpunkt ist schmal und wird uebergangen. Genau vier Ziffern, sonst -1.
     */
    static long uhrLesen(boolean[][] m) {
        if (m.length == 0) return -1;
        int h = m.length, w = m[0].length;
        StringBuilder ziffern = new StringBuilder();
        int start = -1;
        for (int x = 0; x <= w; x++) {
            boolean spalte = false;
            if (x < w) for (int y = 0; y < h; y++) spalte |= m[y][x];
            if (spalte && start < 0) start = x;
            if (spalte || start < 0) continue;
            int breite = x - start;
            if (breite >= 4) {   // Doppelpunkt (2-3 Spalten) und Krumen uebergehen
                if (breite > 2 * MW + 2) return -1;
                int z = zifferIn(m, start, x - 1);
                if (z < 0) return -1;
                ziffern.append((char) ('0' + z));
            }
            start = -1;
            if (ziffern.length() == 4) break;
        }
        if (ziffern.length() != 4) return -1;
        int min = Integer.parseInt(ziffern.substring(0, 2)), sek = Integer.parseInt(ziffern.substring(2));
        if (sek > 59 || min > 2) return -1;
        return min * 60L + sek;
    }

    /** Die Ziffer in den Spalten von..bis (Zeilen selbst bestimmt), -1 = keine. */
    private static int zifferIn(boolean[][] m, int von, int bis) {
        int y1 = m.length, y2 = -1;
        for (int x = von; x <= bis; x++) for (int y = 0; y < m.length; y++) if (m[y][x]) { y1 = Math.min(y1, y); y2 = Math.max(y2, y); }
        if (y2 - y1 < 7) return -1;
        return ziffer(m, von, bis, y1, y2);
    }

    /** Die aehnlichste Ziffer fuer einen Ausschnitt, -1 = keine passt gut genug. */
    private static int ziffer(boolean[][] m, int x1, int x2, int y1, int y2) {
        int beste = -1, besteAbw = Integer.MAX_VALUE;
        for (int z = 0; z < 10; z++) {
            int abw = abweichung(m, x1, x2, y1, y2, MUSTER[z]);
            for (String[] zusatz : MUSTER_ZUSATZ) {
                if (zusatz[0].charAt(0) - '0' == z) abw = Math.min(abw, abweichung(m, x1, x2, y1, y2, zusatz[1]));
            }
            if (abw < besteAbw) { besteAbw = abw; beste = z; }
        }
        return besteAbw <= MUSTER_TOLERANZ ? beste : -1;
    }

    /** Wie viele der 8 x 12 Felder anders sind als im Muster. */
    private static int abweichung(boolean[][] m, int x1, int x2, int y1, int y2, String muster) {
        int w = x2 - x1 + 1, h = y2 - y1 + 1, abw = 0;
        for (int y = 0; y < MH; y++) {
            for (int x = 0; x < MW; x++) {
                int sx0 = x1 + x * w / MW, sx1 = Math.max(sx0 + 1, x1 + (x + 1) * w / MW);
                int sy0 = y1 + y * h / MH, sy1 = Math.max(sy0 + 1, y1 + (y + 1) * h / MH);
                int c = 0, t = 0;
                for (int yy = sy0; yy < sy1; yy++) for (int xx = sx0; xx < sx1; xx++) { t++; if (m[yy][xx]) c++; }
                if ((c * 2 >= t) != (muster.charAt(y * MW + x) == '#')) abw++;
            }
        }
        return abw;
    }

    /**
     * Zweite Formen einzelner Ziffern {Ziffer, Muster} (Zaehler-Test 22.10., Arena 6): auf manchen Schilden ist die Schrift
     * eine Zeile kleiner -- die "1" lag dort bei 25-26 Abweichungen (Grenze 22), die ganze Zahl ("150", "149") fiel weg.
     * Gelernt an Bild f_208 (Platz 1 = 150, Platz 5 = 149, mit dem Bild verglichen).
     */
    static final String[][] MUSTER_ZUSATZ = {
            {"1", "...###...#####..######.....###......##......##......##......##......##......##...#######.#######"},
            {"4", ".....##.....###....####....####...#####..##..##..#...##.##...##.################....####.....##."},
    };

    /** Ab dieser Helligkeit (dunkelster Farbkanal) gilt ein Bildpunkt als Schrift. */
    static final int WEISS_AB = 238;

    // ------------------------------------------------------------------
    //  Top-3-Rechnung (Autor 21.10.)
    // ------------------------------------------------------------------

    /** Welcher Platz liegt dieser Stelle (Anteile des Bildes) am naechsten? 0 = keiner in der Naehe. */
    static int platzBei(double fx, double fy) {
        int bester = 0;
        double besterAbstand = 0.08;
        for (int i = 1; i <= 10; i++) {
            double d = Math.hypot(fx - X[i], (fy - Y[i]) * 1.5);
            if (d < besterAbstand) { besterAbstand = d; bester = i; }
        }
        return bester;
    }

    /**
     * Ab dieser Stage rechnet der Bot mit den Turnierpunkten aller Spieler. Idee 13 (22.10.): schon ab Stage 3 statt 4 --
     * ein einzelner 225er in Runde 3 brachte bei 5 Siegen 10 von 10 Mal die Top 3, und 5 der 32 Niederlagen fielen in
     * Runde 3, also vor der Rechnung.
     */
    static final int RECHNUNG_AB = 2;   // Autor 22.10. (Vorausplanen): schon ab Runde 2
    /** So viele der 9 Gegnerzahlen muessen gelesen sein, sonst wird nicht gerechnet. */
    static final int MINDESTENS_GELESEN = 7;

    /** So viele Punkte holt ein Spieler im Schnitt je Runde, die noch kommt (Annahme: ein Sieg zu 150). */
    static final double JE_RUNDE_ANDERE = 150;
    /** Deine eigenen kuenftigen Runden: etwas weniger, Niederlagen und lange Kaempfe mitgerechnet. */
    static final double JE_RUNDE_ICH = 140;

    /**
     * Die erwarteten Endpunkte der anderen: was sie jetzt haben, + 150 fuer diese Runde, wenn sie noch nicht angegriffen
     * haben (kein gruener Haken), + 150 je weitere Runde. Nicht gelesene Plaetze fehlen in der Liste.
     */
    static double[] endpunkteAndere(long[] punkte, boolean[] angegriffen, int ich, int restRunden) {
        java.util.List<Double> l = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            if (i == ich || punkte[i] < 0) continue;
            l.add(punkte[i] + (angegriffen[i] ? 0 : JE_RUNDE_ANDERE) + restRunden * JE_RUNDE_ANDERE);
        }
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Dein Rang mit Pokal-Reihenfolge (Autor 22.10.): "Oft haben Spieler die gleiche Anzahl an Arena-Punkten. Dann wird
     * entschieden, wer am meisten Pokale hat -- das siehst du in der ersten Runde." {@code mehrPokale[i]}: der Spieler
     * {@code andere[i]} stand in Runde 1 ueber dir (unbekannt = true, wie bisher gegen dich).
     */
    static int rang(double meine, double[] andere, boolean[] mehrPokale) {
        int r = 1;
        for (int i = 0; i < andere.length; i++) {
            boolean vorn = mehrPokale == null || i >= mehrPokale.length || mehrPokale[i];
            if (andere[i] > meine || (andere[i] == meine && vorn)) r++;
        }
        return r;
    }

    /**
     * Autor 03.11. ("Ja, bau den Vorschlag ein"): Stage 5, 600 Punkte, die anderen oben 900/825/825/823 -- der Bot nahm тифани
     * (150, 96 %) statt БУМС (225, 91 %), weil er gegen den gleich starken БУМС weniger als 225 erwartete und so mit keiner Wahl
     * Top 3 sah. In der letzten Runde bringen die Plaetze 4-10 aber nichts: ein sicherer Sieg auf Platz 5 ist so viel wert wie
     * eine Niederlage. Wert eines Gegners, wenn KEINE Wahl vorsichtig gerechnet Top 3 bringt: die Siegchance, wenn ein Sieg mit
     * vollen Punkten (Kampf unter 10 s) Top 3 bringt; x 0,6, wenn dafuer auch jeder Gleichstand an dich gehen muss; sonst -1.
     */
    static double letzteRundeChance(double chance, double meine, double vollePunkte, double[] andere, boolean[] mehrPokale) {
        if (rang(meine + vollePunkte, andere, mehrPokale) <= 3) return chance;
        if (rang(meine + vollePunkte, andere, new boolean[andere.length]) <= 3) return chance * 0.6;
        return -1;
    }

    /**
     * Wie viel bringen dir die restlichen Runden? Autor 22.10. ("vorausdenken wie beim Schach"): wer allein ganz oben steht,
     * bekommt nur noch 150er -- 225 gibt es nur gegen Spieler ueber dir. Bleibst du darunter, ist (mindestens) ein 225er
     * drin: {@link #OHNE_SPITZE_BONUS} dazu.
     */
    static double zukunft(double meineNachRunde, double[] andereNachRunde, boolean[] mehrPokale, int rest) {
        if (rest <= 0) return 0;
        // 30.10., 04:35: Der Bonus sprang von 60 auf 0, sobald man vorn lag -- ein Sieg mit mehr Punkten rechnete sich dadurch schlechter als
        // einer mit 6 Punkten weniger (Endstand 744 gegen 750). Jetzt stetig: mehr Punkte heute heissen nie weniger am Ende.
        if (de.hustlebot.core.ArenaRegelnNeu.an()) {
            double spitze = 0;
            for (double a : andereNachRunde) spitze = Math.max(spitze, a);
            return rest * JE_RUNDE_ICH + Math.max(0, Math.min(OHNE_SPITZE_BONUS, spitze - meineNachRunde));
        }
        boolean alleinOben = rang(meineNachRunde, andereNachRunde, mehrPokale) == 1;
        return rest * JE_RUNDE_ICH + (alleinOben ? 0 : OHNE_SPITZE_BONUS);
    }

    /** Ein 225er statt eines 150ers in den restlichen Runden, mit Abschlag fuer das Risiko (75 x 0,8). */
    static final double OHNE_SPITZE_BONUS = 60;

    /**
     * Wert eines Angriffs mit Vorausplanung: wie wahlWert, aber die restlichen Runden haengen davon ab, ob du nach diesem
     * Angriff allein oben stehst, und Gleichstaende entscheiden die Pokale. {@code andereJetzt}: die anderen nach dieser
     * Runde, {@code andereEnde}: am Turnierende.
     */
    static double wahlWertVoraus(double chance, double meine, double gewinn, double[] andereJetzt, double[] andereEnde,
                                 boolean[] mehrPokale, int rest) {
        return wahlWertVoraus(chance, meine, gewinn, andereJetzt, andereEnde, mehrPokale, rest, false);
    }

    /**
     * Wie oben; {@code untenBleiben}: Rang-Leiter-Test (Autor 22.10.) -- ein 225er mit 215-224 Punkten laesst die anderen
     * 225er-Sieger ueber dir, der naechste 225er bleibt moeglich: die Zukunft nach dem Sieg rechnet dann wie "nicht allein oben".
     */
    static double wahlWertVoraus(double chance, double meine, double gewinn, double[] andereJetzt, double[] andereEnde,
                                 boolean[] mehrPokale, int rest, boolean untenBleiben) {
        double zs = untenBleiben && rest > 0 ? rest * JE_RUNDE_ICH + OHNE_SPITZE_BONUS
                : zukunft(meine + gewinn, andereJetzt, mehrPokale, rest);
        double zn = zukunft(meine, andereJetzt, mehrPokale, rest);
        double sieg = platzWert(rang(meine + gewinn + zs, andereEnde, mehrPokale));
        double niederlage = platzWert(rang(meine + zn, andereEnde, mehrPokale));
        return chance * sieg + (1 - chance) * niederlage;
    }

    /**
     * Autor 30.10.: "Der Bot soll nicht davon ausgehen, dass er 225 oder 150 Punkte erreicht", sondern anhand der letzten Kaempfe
     * gegen diesen Spieler rechnen, wie wahrscheinlich die noetigen Punkte sind. Darum jede fruehere Punktzahl einzeln durchrechnen:
     * FLIMSY brauchte 05:01 mindestens 213 fuer Platz 3 und schaffte das in 2 von 10 Kaempfen, nicht bei jedem Sieg.
     *
     * @param moeglich die Punkte der letzten Kaempfe, auf den jetzigen Lohn umgerechnet
     */
    static double wahlWertVoraus(double chance, double meine, double[] moeglich, double[] andereJetzt, double[] andereEnde,
                                 boolean[] mehrPokale, int rest, boolean untenBleiben) {
        double summe = 0;
        for (double g : moeglich) summe += wahlWertVoraus(chance, meine, g, andereJetzt, andereEnde, mehrPokale, rest, untenBleiben);
        return summe / moeglich.length;
    }

    /** Darunter ist ein Angriff eine Wette. */
    static final double WETTE_BIS = 0.70;
    /** Ab da ist ein Angriff sicher. */
    static final double SICHER_AB = 0.90;
    /** Mehr als das bringt eine Runde nie. */
    static final double HOECHSTENS_JE_RUNDE = 225;

    /**
     * Autor 30.10. (nur mit arena-regeln-neu.txt "an"): Solange noch Runden folgen, keine Wette, wenn ein sicherer Gegner da ist und
     * Platz 3 danach noch erreichbar bleibt. "Sieg -> Platz 4" ist vor der letzten Runde nur eine Schaetzung, die anderen sind mit
     * 150 je Runde vorsichtig hoch gerechnet, eine Niederlage aber ist endgueltig: ohne Niederlage 89 % Top 3, mit Niederlage 8 %.
     *
     * Erreichbar heisst: mit einem vollen 225er in jeder restlichen Runde. 01.11., 14:00: Hier stand vorher die Schaetzung inklusive Bonus
     * plus noch einmal 75 je Runde -- 593 Punkte und eine Runde Rest galten als erreichbar (868), hoechstens gingen 818, der Dritte stand bei 823.
     *
     * @param meineNachSieg deine Punkte nach einem Sieg gegen den sicheren Gegner, ohne die restlichen Runden
     * @param andereEnde die geschaetzten Endpunkte der anderen
     */
    static boolean keineWette(double chanceWahl, double chanceSicher, int rest, double meineNachSieg, double[] andereEnde) {
        if (rest <= 0 || chanceWahl >= WETTE_BIS || chanceSicher < SICHER_AB || andereEnde.length < 3) return false;
        double[] a = andereEnde.clone();
        java.util.Arrays.sort(a);
        return meineNachSieg + rest * HOECHSTENS_JE_RUNDE >= a[a.length - 3];
    }

    /** Dein Rang mit {@code meine} Punkten (1 = vorn). Gleichstand zaehlt gegen dich -- lieber vorsichtig. */
    static int rang(double meine, double[] andere) {
        int r = 1;
        for (double a : andere) if (a >= meine) r++;
        return r;
    }

    /**
     * Was ein Endplatz wert ist. Autor 22.10.: "Platz 1 soll immer das Ziel sein. Platz 1 ist 10-mal mehr wert als Platz 2.
     * Platz 2 und Platz 3 sind auch gut. Platz 4 bis 10 ist scheisse." Gleich danach die Werte: "Platz 1 = 10, Platz 2 = 5,
     * Platz 3 = 3, Platz 4-10 = -10." (Vorher, 21.10.: Platz 1 nur wenig mehr als 2 und 3.)
     */
    static double platzWert(int platz) {
        return switch (platz) {
            case 1 -> 10.0;
            case 2 -> 5.0;
            case 3 -> 3.0;
            default -> -10.0;
        };
    }

    /**
     * Wert eines Angriffs: Chance x Platzwert nach einem Sieg + (1 - Chance) x Platzwert nach einer Niederlage.
     * @param gewinn  erwartete Punkte bei einem Sieg (150 / 225, abzueglich langer Kaempfe)
     * @param zukunft deine erwarteten Punkte aus den Runden danach
     */
    static double wahlWert(double chance, double meinePunkte, double gewinn, double zukunft, double[] andere) {
        double sieg = platzWert(rang(meinePunkte + gewinn + zukunft, andere));
        double niederlage = platzWert(rang(meinePunkte + zukunft, andere));
        return chance * sieg + (1 - chance) * niederlage;
    }
}
