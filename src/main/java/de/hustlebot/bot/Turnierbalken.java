package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Texterkennung;

import java.awt.image.BufferedImage;
import java.util.regex.Pattern;
import de.hustlebot.core.Sprache;

/**
 * Der Turnier-Balken der Arena oben in der Mitte (Autor, 11.10. 15:05):
 *
 *     "Wait for the next stage of the tournament: 1m 13s"
 *     "Time left to select an opponent: 27s"
 *
 * Er steht ueber der Vorratsleiste, solange das letzte Arena-Turnier noch nicht
 * ganz zu Ende ist -- auch in der Burg. Steht er da, darf nichts darunter
 * abgelesen werden (Nahrung), und kein anderer Modus soll anfangen: erst wird
 * die Arena zu Ende gespielt (BotEngine, Nachspiel).
 *
 * Erkannt wird er per Texterkennung. Ohne Texterkennung, ohne Bild oder bei
 * einem Fehler gilt: kein Balken -- lieber nichts tun als falsch klicken.
 */
final class Turnierbalken {

    private Turnierbalken() {}

    private static final Pattern MUSTER = Pattern.compile(
            "(?i)(wait\\W*for\\W*the\\W*next\\W*stage"
            + "|next\\W*stage\\W*of\\W*the\\W*tournament"
            + "|time\\W*left\\W*to\\W*select"
            + "|select\\W*an\\W*opponent"
            + "|tournament\\W*ends\\W*in)");

    /** Wann der Balken zuletzt gesehen wurde (0 = nicht), fuer den Motor. */
    private static volatile long gesehenUm;
    private static volatile long gemeldetUm;
    /** Letzte Pruefung (Puffer, damit Lese-Schleifen nicht bei jedem Blick nachsehen). */
    private static volatile long geprueftUm;
    private static volatile boolean zuletztDa;

    /** Steht der Balken gerade oben? Liest den Streifen oben mittig per Texterkennung. */
    static boolean sichtbar(BotContext ctx) {
        return sichtbar(ctx, 0);
    }

    /**
     * Wie {@link #sichtbar(BotContext)}, aber eine Pruefung, die juenger als pufferMs ist,
     * wird wiederverwendet (die Nahrung wird in Schleifen gelesen -- nicht jedes Mal lesen).
     */
    static boolean sichtbar(BotContext ctx, long pufferMs) {
        long jetzt = System.currentTimeMillis();
        if (pufferMs > 0 && geprueftUm != 0 && jetzt - geprueftUm < pufferMs) return zuletztDa;
        boolean da = lesen(ctx);
        geprueftUm = jetzt;
        zuletztDa = da;
        if (da) gesehenUm = jetzt;
        return da;
    }

    /** Steht in dem gelesenen Text der Turnier-Balken? (Fuer den Test getrennt.) */
    static boolean erkennt(String text) {
        return text != null && MUSTER.matcher(text).find();
    }

    private static boolean lesen(BotContext ctx) {
        try {
            if (ctx == null || !Texterkennung.istDa()) return false;
            // Das Bild, das der Motor gerade frisch aufgenommen hat (Pruefung 11.10.): KEIN
            // ctx.refresh() hier -- das gibt das alte Bild frei, und der Motor bzw. der Modus
            // arbeiten in diesem Schritt noch damit (Nahrung lesen, dann der naechste Blick).
            // Nur wenn noch gar kein Bild da ist, wird eines aufgenommen.
            BufferedImage bild = ctx.frame();
            if (bild == null) {
                if (ctx.refresh() == null) return false;
                bild = ctx.frame();
            }
            if (bild == null || bild.getWidth() < 40 || bild.getHeight() < 40) return false;
            int w = bild.getWidth(), h = bild.getHeight();
            // Oben mittig: das mittlere Drittel-plus der Breite, die obersten 11 % der Hoehe.
            int x = (int) (w * 0.20), b = (int) (w * 0.60);
            int hoehe = Math.max(8, (int) (h * 0.11));
            String text = ArenaLeser.liesText(bild, x, 0, b, hoehe);
            if (!erkennt(text)) {
                String gross = ArenaLeser.liesTextGross(bild, x, 0, b, hoehe);
                if (gross != null && !gross.isBlank()) text = gross;
            }
            boolean da = erkennt(text);
            long jetzt = System.currentTimeMillis();
            if (da && jetzt - gemeldetUm > 60_000) {
                gemeldetUm = jetzt;
                Log.info("Arena", Sprache.t("The tournament bar is at the top (\"" + text.strip() + "\") -- the last arena tournament is still running.",
                        "Oben steht der Turnier-Balken (\"" + text.strip()
                        + "\") -- das letzte Arena-Turnier laeuft noch."));
            } else if (!da) {
                Log.debug("Arena", "Kein Turnier-Balken oben (gelesen: \"" + (text == null ? "" : text.strip()) + "\").");
            }
            return da;
        } catch (Exception e) {
            Log.debug("Arena", "Turnier-Balken liess sich nicht pruefen: " + e.getMessage());
            return false;
        }
    }

    /** Fuer den Motor: wurde der Balken in den letzten hoechstensAltMs gesehen? Einmal abholbar. */
    static boolean abholen(long hoechstensAltMs) {
        long g = gesehenUm;
        if (g == 0 || System.currentTimeMillis() - g > hoechstensAltMs) return false;
        gesehenUm = 0;
        return true;
    }

    static void vergessen() {
        gesehenUm = 0;
        // Pruefung 11.10.: auch den Puffer leeren -- sonst hielte der Nahrungs-Leser den Balken nach einem
        // kurzen Nachspiel noch bis zu 20 s fuer sichtbar und liesse die Nahrung ungelesen.
        geprueftUm = 0;
        zuletztDa = false;
    }
}
