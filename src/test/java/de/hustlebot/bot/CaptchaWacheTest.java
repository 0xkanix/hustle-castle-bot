package de.hustlebot.bot;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 15.11. / X3A2: Captcha offen = niemand klickt, Mensch wird gerufen, nach drei leeren Bildern geht es weiter. */
class CaptchaWacheTest {

    private final List<String> ereignisse = new ArrayList<>();
    private double wert = 0;
    private boolean schalter = true;

    private CaptchaWache wache() {
        return new CaptchaWache(m -> wert, w -> w >= 0.7, new CaptchaWache.Folgen() {
            public void neu(Mat s, double w) { ereignisse.add("neu"); }
            public void erinnerung(long min) { ereignisse.add("erinnerung " + min); }
            public void weg(long sek) { ereignisse.add("weg " + sek); }
        }, () -> schalter);
    }

    @Test
    void erkanntHaeltAnUndRuftEinmal() {
        CaptchaWache w = wache();
        wert = 0.95;
        long t = 10_000;
        assertTrue(w.pruefen(null, t));
        assertTrue(w.pruefen(null, t + 300));
        assertTrue(w.pruefen(null, t + 1000));
        assertEquals(List.of("neu"), ereignisse);   // nur einmal gerufen
        assertTrue(w.offen());
    }

    @Test
    void erinnertNachZehnMinuten() {
        CaptchaWache w = wache();
        wert = 0.95;
        long t = 10_000;
        w.pruefen(null, t);
        w.pruefen(null, t + CaptchaWache.ERINNERUNG_MS + 5);
        assertEquals(List.of("neu", "erinnerung 10"), ereignisse);
    }

    @Test
    void erstNachDreiLeerenBildernWeiter() {
        CaptchaWache w = wache();
        wert = 0.95;
        long t = 10_000;
        w.pruefen(null, t);
        wert = 0.1;
        assertTrue(w.pruefen(null, t + 1000));
        assertTrue(w.pruefen(null, t + 1300));
        assertFalse(w.pruefen(null, t + 1600));   // dritte leere Pruefung: weg
        assertEquals(List.of("neu", "weg 1"), ereignisse);
        assertFalse(w.offen());
    }

    @Test
    void einzelnesLeeresBildBeendetNichtUndNeuerTreffferSetztZurueck() {
        CaptchaWache w = wache();
        wert = 0.95;
        w.pruefen(null, 10_000);
        wert = 0.1;
        w.pruefen(null, 11_000);
        wert = 0.95;
        w.pruefen(null, 11_300);
        wert = 0.1;
        assertTrue(w.pruefen(null, 11_600));
        assertTrue(w.pruefen(null, 11_900));
        assertEquals(List.of("neu"), ereignisse);
    }

    @Test
    void ohneCaptchaWirdNurImTaktGesucht() {
        int[] aufrufe = {0};
        CaptchaWache w = new CaptchaWache(m -> { aufrufe[0]++; return 0.1; }, x -> x >= 0.7, new CaptchaWache.Folgen() {
            public void neu(Mat s, double x) { }
            public void erinnerung(long m) { }
            public void weg(long s) { }
        }, () -> true);
        assertFalse(w.pruefen(null, 10_000));
        assertFalse(w.pruefen(null, 10_100));   // zu frueh: keine neue Suche
        assertFalse(w.pruefen(null, 10_800));
        assertEquals(2, aufrufe[0]);
    }

    @Test
    void schalterAusHeisstKeineWirkung() {
        schalter = false;
        CaptchaWache w = wache();
        wert = 0.95;
        assertFalse(w.pruefen(null, 10_000));
        assertFalse(w.pruefen(null, 11_000));
        assertTrue(ereignisse.isEmpty());
        assertFalse(w.offen());
    }

    @Test
    void schalterStandardIstAus(@org.junit.jupiter.api.io.TempDir java.nio.file.Path d) {
        var s = new de.hustlebot.core.Schalterdatei(d.resolve("captcha-wache.txt"), "Captcha", false);
        assertFalse(s.lesen());   // ohne Datei = aus
    }

    @Test
    void schalterAnHaeltAn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path d) throws Exception {
        java.nio.file.Files.writeString(d.resolve("captcha-wache.txt"), "an");
        var s = new de.hustlebot.core.Schalterdatei(d.resolve("captcha-wache.txt"), "Captcha", false);
        assertTrue(s.lesen());
        schalter = s.lesen();
        CaptchaWache w = wache();
        wert = 0.95;
        assertTrue(w.pruefen(null, 10_000));
        assertEquals(List.of("neu"), ereignisse);
    }
}
