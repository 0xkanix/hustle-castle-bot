package de.hustlebot.bot;

import java.awt.image.BufferedImage;

/**
 * Autor 01.11. (Pruefung 3.137): wann verdeckt etwas die Schilder der Pyramide, sodass Namen, Listen und Tabelle nicht gelesen werden duerfen?
 *
 * Zwei Faelle. Das aufgeklappte Kampfprotokoll liegt links ueber den Schildern (Xenos wurde zu "enos" und traf das Listenbild "enes" = sicher). Und
 * das Banner "Tournament phase N has begun. Choose your opponent." (weisse Schrift mit dunklem Rand) liegt ueber den oberen Schildern -- die
 * Tabelle stand beim Rundenstart komplett auf "?". Das Protokoll erkennt der Bot an seinem Pfeil (Vorlage ar_log_close); fuers Banner gibt es keine
 * Vorlage (der Hintergrund wechselt), darum zaehlt hier die Schrift selbst: ein Streifen quer ueber das Bild, in dem fast ueberall weisse Punkte mit
 * dunklem Rand sitzen.
 *
 * Gemessen an den 623 Bildern der Fotoserie vom 01.11. (14:57-15:07): die 8 Banner-Bilder (in Ruhe und beim Gleiten) hatten in 28-40 Spaltenblocken weisse Randpunkte, alle anderen
 * Bilder hoechstens 12 (Wolken, helle Schilder, die gelben Zahlen); gesucht wird mit einem Fenster ueber den ganzen Bereich, weil das Banner gleitet.
 */
final class ArenaVerdeckt {

    private ArenaVerdeckt() { }

    /**
     * Der Bereich, in dem die Banner-Schrift steht (Anteile an Breite/Hoehe). Das Banner GLEITET von oben herein und wieder hinaus: in Ruhe liegt die Schrift bei
     * 0,23-0,27 der Hoehe (1159x770: Zeilen 173-206), beim Ein- und Ausgleiten hoeher (um 0,15-0,19). Darum wird der ganze Bereich Y_VON-Y_BIS mit einem Fenster der
     * Schrifthoehe {@link #HOEHE} abgesucht.
     */
    static final double X_VON = 0.10, X_BIS = 0.90, Y_VON = 0.10, Y_BIS = 0.36, HOEHE = 0.043;
    /** Spaltenbloecke im Streifen; so viele davon muessen weisse Schrift mit dunklem Rand enthalten. */
    static final int BLOECKE = 40, MIN_BLOECKE = 28;
    /** So viele weisse Randpunkte macht einen Block aus. */
    static final int PUNKTE_JE_BLOCK = 6;
    /** Das Banner blendet aus: so lange nach dem letzten Sehen gilt die Pyramide noch als verdeckt (ms). */
    static final long NACHLAUF_MS = 1200;

    /** Steht das "Tournament phase N has begun"-Banner im Bild (an irgendeiner Hoehe des Gleitens)? */
    static boolean banner(BufferedImage bild) {
        if (bild == null) return false;
        int w = bild.getWidth(), h = bild.getHeight();
        if (w < 200 || h < 120) return false;
        int x0 = (int) (w * X_VON), x1 = (int) (w * X_BIS), y0 = (int) (h * Y_VON), y1 = (int) (h * Y_BIS);
        int fenster = Math.max(4, (int) Math.round(h * HOEHE));
        if (x1 - x0 < BLOECKE || y1 - y0 < fenster) return false;
        int[] zeile = new int[w];
        int rand = Math.max(2, h / 190);   // Breite des dunklen Rands um die Buchstaben (bei 770 Zeilen: 4)
        // je Bildzeile: wie viele weisse Randpunkte je Spaltenblock; ueber ein Fenster von {fenster} Zeilen aufsummiert (gleitende Summe)
        int zeilen = y1 - y0;
        int[][] je = new int[zeilen][BLOECKE];
        for (int y = y0; y < y1; y++) {
            bild.getRGB(0, y, w, 1, zeile, 0, w);
            int[] z = je[y - y0];
            for (int x = x0; x < x1; x++) {
                if (!weiss(zeile[x])) continue;
                boolean dunkel = false;
                for (int d = -rand; d <= rand && !dunkel; d++) {
                    int xx = x + d;
                    if (xx >= 0 && xx < w && dunkel(zeile[xx])) dunkel = true;
                }
                if (dunkel) z[(x - x0) * BLOECKE / (x1 - x0)]++;
            }
        }
        int[] summe = new int[BLOECKE];
        for (int y = 0; y < zeilen; y++) {
            for (int b = 0; b < BLOECKE; b++) {
                summe[b] += je[y][b];
                if (y >= fenster) summe[b] -= je[y - fenster][b];
            }
            if (y < fenster - 1) continue;
            int voll = 0;
            for (int n : summe) if (n >= PUNKTE_JE_BLOCK) voll++;
            if (voll >= MIN_BLOECKE) return true;
        }
        return false;
    }

    private static boolean weiss(int p) {
        return ((p >> 16) & 255) >= 250 && ((p >> 8) & 255) >= 250 && (p & 255) >= 250;
    }

    private static boolean dunkel(int p) {
        return ((p >> 16) & 255) < 90 && ((p >> 8) & 255) < 90 && (p & 255) < 90;
    }

    /** Gilt die Pyramide noch als verdeckt? Das Protokoll ist offen, oder das Banner war vor weniger als {@link #NACHLAUF_MS} zu sehen. */
    static boolean verdeckt(boolean protokollOffen, long bannerGesehenUm, long jetzt) {
        return protokollOffen || (bannerGesehenUm > 0 && jetzt - bannerGesehenUm < NACHLAUF_MS);
    }
}
