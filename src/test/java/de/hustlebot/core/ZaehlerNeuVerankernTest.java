package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: Particle of Light blieb nach zwei Stunden bei 310,5 Mio stehen, obwohl der Zaehler bis 312,6 Mio
 * weiterlief -- jede richtige Ablesung galt als "Sprung ueber 500 000" und wurde fuer immer verworfen
 * (30 Mal im Protokoll 28.10., alle steigend). Die Reihe unten ist genau die aus dem Protokoll.
 */
class ZaehlerNeuVerankernTest {

    @Test
    void echteReiheAusDemProtokollWirdWiederAngenommen() {
        Laufstatistik t = new Laufstatistik();
        t.recordLight(308_992_089L);                 // Ausgangsstand 27.10. 23:51
        t.recordLight(310_527_000L);                 // lief normal bis hier (letzter angenommener Stand)
        long[] echt = {311_198_834L, 311_326_194L, 311_357_859L, 311_533_129L, 311_576_379L, 311_618_444L,
                311_737_599L, 311_819_389L, 311_942_329L, 312_027_109L, 312_069_069L, 312_101_899L, 312_139_809L,
                312_180_604L, 312_222_669L, 312_297_794L, 312_343_354L, 312_425_784L, 312_469_024L, 312_515_979L,
                312_559_869L, 312_601_274L, 312_643_109L};
        for (long v : echt) t.recordLight(v);
        // Frueher: stehengeblieben bei 310 527 000 -> Gewinn 1,5 Mio. Jetzt folgt der Stand dem Zaehler.
        assertEquals(312_643_109L, t.lightNow());
        assertEquals(312_643_109L - 308_992_089L, t.lightGained());
    }

    @Test
    void einzelnerLesefehlerBleibtVerworfen() {
        Laufstatistik t = new Laufstatistik();
        t.recordLight(310_000_000L);
        t.recordLight(11_198_834L);      // erste Ziffern verloren
        assertEquals(310_000_000L, t.lightNow());
        t.recordLight(310_050_000L);     // richtiger Wert danach
        assertEquals(310_050_000L, t.lightNow());
    }

    @Test
    void zweiVerschiedeneLesefehlerHintereinanderVerankernNicht() {
        Laufstatistik t = new Laufstatistik();
        t.recordLight(310_000_000L);
        t.recordLight(11_198_834L);
        t.recordLight(51_234_567L);      // anderer Unsinn
        t.recordLight(11_198_834L);
        assertEquals(310_000_000L, t.lightNow());
    }

    @Test
    void zweiStimmigeAblesungenGenuegenNochNicht() {
        Laufstatistik t = new Laufstatistik();
        t.recordLight(310_000_000L);
        t.recordLight(11_198_834L);
        t.recordLight(11_198_900L);
        assertEquals(310_000_000L, t.lightNow(), "erst die dritte stimmige Ablesung verankert neu");
    }

    @Test
    void auchEtherUndAbzeichenFolgenEinemEchtenSprung() {
        Laufstatistik t = new Laufstatistik();
        t.recordEther(1_000_000L);
        for (int i = 1; i <= 3; i++) t.recordEther(2_000_000L + i * 10_000L);   // 1 Mio gekauft/geholt, bleibt dort
        assertEquals(2_030_000L, t.etherNow());

        t.recordBadges(100_000L);
        t.recordBadges(300_000L);
        t.recordBadges(300_100L);
        assertEquals(100_000L, t.badgesNow());
        t.recordBadges(300_200L);
        assertEquals(300_200L, t.badgesNow());
    }

    @Test
    void normalerGangUnveraendert() {
        Laufstatistik t = new Laufstatistik();
        t.recordLight(1_000L);
        t.recordLight(21_000L);
        t.recordLight(41_000L);
        assertEquals(41_000L, t.lightNow());
        assertEquals(40_000L, t.lightGained());
    }
}
