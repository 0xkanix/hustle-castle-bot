package de.hustlebot.bot;

import org.opencv.core.Mat;

import java.util.function.DoublePredicate;

/**
 * Zustand der Captcha-Wache im Motor (15.11. / X3A2): solange ein Captcha offen ist, klickt kein Modus. Der Bot ruft den
 * Menschen (einmal sofort, dann alle 10 Minuten) und loest NIE selbst. Verschwindet das Captcha (Mensch hat es geloest),
 * geht es nach drei leeren Bildern weiter.
 */
final class CaptchaWache {

    /** Ohne offenes Captcha nur so oft suchen (die Suche kostet einige Millisekunden je Bild). */
    static final long TAKT_MS = 700;
    static final long ERINNERUNG_MS = 10 * 60_000L;
    static final int WEG_BILDER = 3;

    /** Was der Motor tun soll (Meldungen, Foto). */
    interface Folgen {
        void neu(Mat screen, double wert);
        void erinnerung(long minutenOffen);
        void weg(long sekundenOffen);
    }

    private final DoublePredicate gefunden;   // Trefferwert -> ist es ein Captcha?
    private final java.util.function.ToDoubleFunction<Mat> messen;
    private final Folgen folgen;
    /** Schalter (captcha-wache.txt, Standard AUS): aus = Bot verhaelt sich wie ohne Wache. */
    private final java.util.function.BooleanSupplier aktiv;
    private long schalterGelesenUm = Long.MIN_VALUE / 2;
    private boolean schalterWert;
    private boolean offen;
    private long seit, zuletztGeprueft, erinnertUm;
    private int wegBilder;

    CaptchaWache(java.util.function.ToDoubleFunction<Mat> messen, DoublePredicate gefunden, Folgen folgen,
                 java.util.function.BooleanSupplier aktiv) {
        this.aktiv = aktiv;
        this.messen = messen;
        this.gefunden = gefunden;
        this.folgen = folgen;
    }

    boolean offen() {
        return offen;
    }

    /** true, solange ein Captcha offen ist -- dann darf niemand klicken, der Motor beginnt von vorn. */
    boolean pruefen(Mat screen, long jetzt) {
        // Schalter hoechstens alle 5 s lesen. Aus: nichts erkennen, nichts anhalten (Autor 29.10.: Captcha ausgebaut).
        if (jetzt - schalterGelesenUm >= 5000) {
            schalterGelesenUm = jetzt;
            schalterWert = aktiv.getAsBoolean();
        }
        if (!schalterWert) {
            offen = false;
            wegBilder = 0;
            return false;
        }
        if (!offen && jetzt - zuletztGeprueft < TAKT_MS) return false;
        zuletztGeprueft = jetzt;
        double wert = messen.applyAsDouble(screen);
        if (!gefunden.test(wert)) {
            if (!offen) return false;
            if (++wegBilder < WEG_BILDER) return true;
            offen = false;
            wegBilder = 0;
            folgen.weg((jetzt - seit) / 1000);
            return false;
        }
        wegBilder = 0;
        if (!offen) {
            offen = true;
            seit = jetzt;
            erinnertUm = jetzt;
            folgen.neu(screen, wert);
        } else if (jetzt - erinnertUm >= ERINNERUNG_MS) {
            erinnertUm = jetzt;
            folgen.erinnerung((jetzt - seit) / 60_000);
        }
        return true;
    }
}
