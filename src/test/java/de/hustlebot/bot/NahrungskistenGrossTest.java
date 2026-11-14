package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** Autor 25.10.: lieber 2 x 1 Mio als 7 x 135.000 + 1 x 1 Mio -- wenn nicht viel zu viel kommt. */
class NahrungskistenGrossTest {

    @Test
    void zweiGrosseStattAchtKisten() {
        long[] werte = {135_000, 1_000_000};
        int[] anzahl = {7, 5};
        int[] alt = Nahrungskisten.bestePlanung(werte, anzahl, 1_900_000);
        int[] neu = Nahrungskisten.wenigerKisten(werte, anzahl, 1_900_000, alt);
        assertArrayEquals(new int[]{0, 2}, neu);
    }

    @Test
    void kleinerBedarfBleibtBeiKleinenKisten() {
        // 200.000 fehlen: eine 1-Mio-Kiste braechte 800.000 zu viel (Grenze 200.000) -- es bleibt bei zwei kleinen.
        long[] werte = {135_000, 1_000_000};
        int[] anzahl = {7, 5};
        int[] alt = Nahrungskisten.bestePlanung(werte, anzahl, 200_000);
        assertArrayEquals(alt, Nahrungskisten.wenigerKisten(werte, anzahl, 200_000, alt));
    }

    @Test
    void ohneGrosseKistenWieBisher() {
        long[] werte = {135_000};
        int[] anzahl = {20};
        int[] alt = Nahrungskisten.bestePlanung(werte, anzahl, 500_000);
        assertArrayEquals(alt, Nahrungskisten.wenigerKisten(werte, anzahl, 500_000, alt));
    }
}
