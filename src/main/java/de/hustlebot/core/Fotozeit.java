package de.hustlebot.core;

import java.util.Arrays;

/**
 * WIE LANGE EIN FOTO DES SPIELFENSTERS DAUERT (Autor 20.11.: das VM-Auge soll die Leistung im Gast mitschauen,
 * "so richtig kompakt"). In der VirtualBox-VM kann das Foto spuerbar langsamer sein als auf Autors PC -- bisher stand
 * das nirgends.
 *
 * Jedes Foto ueber {@link ScreenCapture#grab} wird hier gemerkt; hoechstens einmal je {@value #ABSTAND_MS} ms entsteht
 * EINE Zeile fuers Protokoll, etwa "Fotozeit: 512 Fotos in 60 s, Median 31 ms, langsamstes 88 ms". Das VM-Auge
 * (werkzeuge\vm-auge\vm-auge.ps1) liest sie und vergleicht mit Autors PC.
 *
 * Reine Rechnung ohne Uhr und Fenster: die Zeiten kommen von aussen (getestet in FotozeitTest).
 */
public final class Fotozeit {

    /** Die eine Messstelle des Bots. */
    public static final Fotozeit BOT = new Fotozeit();

    /** So oft hoechstens eine Protokollzeile. */
    static final long ABSTAND_MS = 60_000;
    /** Mehr Einzelwerte je Abschnitt braucht der Median nicht (bei ~10 Fotos je Sekunde sind es ~600). */
    static final int PLATZ = 4096;

    private final int[] dauerMs = new int[PLATZ];
    private int gemerkt;
    private int anzahl;
    private int langsamstes;
    private long seitMs = -1;

    /**
     * Ein Foto hat {@code dauerNs} gedauert.
     *
     * @return die Protokollzeile, wenn seit der letzten {@value #ABSTAND_MS} ms vergangen sind -- sonst null
     */
    public synchronized String merke(long dauerNs, long jetztMs) {
        if (seitMs < 0) seitMs = jetztMs;
        int ms = (int) Math.min(Integer.MAX_VALUE, Math.max(0, dauerNs / 1_000_000));
        if (gemerkt < PLATZ) dauerMs[gemerkt++] = ms;
        anzahl++;
        langsamstes = Math.max(langsamstes, ms);
        long vergangen = jetztMs - seitMs;
        if (vergangen < ABSTAND_MS) return null;
        int[] sortiert = Arrays.copyOf(dauerMs, gemerkt);
        Arrays.sort(sortiert);
        String zeile = "Fotozeit: " + anzahl + " Fotos in " + vergangen / 1000 + " s, Median " + sortiert[sortiert.length / 2]
                + " ms, langsamstes " + langsamstes + " ms";
        gemerkt = 0;
        anzahl = 0;
        langsamstes = 0;
        seitMs = jetztMs;
        return zeile;
    }
}
