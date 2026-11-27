package de.hustlebot.bot;

import de.hustlebot.core.Log;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.time.LocalTime;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SELBSTHILFE GEGEN "DERSELBE KLICK WIRKT IMMER WIEDER NICHT" (Bericht UK3S, Ben 19.11.).
 *
 * Um 19:17:56 verlor das Spielfenster den Vordergrund ("cannot be brought to the front"). Danach klickte der Bot
 * 32-mal in 69 s auf "Confirm battle result [home_button at 580,710]" -- ohne Wirkung, das Sieg-Fenster blieb stehen.
 * Bisher gab es dafuer nur den Haenger-Bericht (20 s) und nach 15 Minuten den Abbruch.
 *
 * Jetzt in zwei Stufen, in jedem Modus (der Motor fragt einmal je Sekunde):
 *   1. ab {@value #NACH_VORN_AB} gleichen Klicks in Folge: das Spielfenster mit Nachdruck nach vorne holen, neu
 *      fotografieren -- sofort, wenn das Spiel nicht vorne liegt, sonst erst, wenn das Bild dabei 10 s stillsteht;
 *   2. ab {@value #NEUSTART_AB} gleichen Klicks UND 60 s: Hustle Castle kontrolliert neu starten (derselbe Weg wie der
 *      Neustart alle 2 Stunden), danach laeuft der Modus weiter wie nach jedem Neustart. Aber nur, wenn das Bild seit
 *      Stufe 1 stillsteht (sonst bewirken die Klicks vielleicht doch etwas), kein laufender Kampf zu sehen ist
 *      (ein Sieg-/Ergebnisfenster ist kein laufender Kampf) und hoechstens {@value #NEUSTARTS_JE_STUNDE}-mal je
 *      Stunde. Meldet Windows das Spiel als "reagiert nicht", ist nichts mehr zu retten: dann wird neu gestartet.
 *
 * "Gleich" heisst: dieselbe Zeile im Protokoll -- derselbe Knopf an derselben Stelle. Nur Zeitangaben ("after 1574 ms")
 * und die Trefferguete ("match 0.999") zaehlen nicht mit. Jede andere Aktion dazwischen beendet die Folge.
 *
 * Messung 20.11. ueber alle 3084 Protokolle der Fehlerberichte (Folgen ab 15 gleichen Klicks und 60 s): fast nur echte
 * Haenger -- "Participate" bis 8562-mal, "uncover three vases" 5841-mal, Leuchtturm "Quick Attack" 307-mal, Arena
 * "Home" 66-mal, UK3S selbst 32-mal. Vielleicht echte Arbeit darunter: Titan-Event "Claim" (dort bewegt sich das Bild
 * -- deshalb die Bild-Probe vor dem Neustart) und Gem Bay "Battle begins" (Kampfbeginn -- zaehlt als Kampf).
 *
 * Alles hier ausser {@link #fingerabdruck(Mat)} ist reine Rechnung ohne Fenster und Uhr (getestet in KlickwaechterTest).
 */
public final class Klickwaechter {

    private Klickwaechter() {}

    /** Was der Motor tun soll. Die letzten drei heissen: Neustart waere dran, aber jetzt nicht (einmal melden). */
    public enum Schritt { NICHTS, NACH_VORN, NEUSTART, KAMPF_ABWARTEN, BILD_BEWEGT_SICH, AUSGESCHOEPFT }

    /** Stufe 1: ab so vielen gleichen Klicks in Folge das Fenster nach vorne holen. */
    static final int NACH_VORN_AB = 5;
    /** Liegt das Spiel vorne, braucht Stufe 1 zusaetzlich so lange ein stillstehendes Bild. */
    static final long NACH_VORN_STILL_MS = 10_000;
    /** Stufe 2: ab so vielen gleichen Klicks ... */
    static final int NEUSTART_AB = 15;
    /** ... und so langer Folge (erster bis letzter Klick) das Spiel neu starten. */
    static final long NEUSTART_NACH_MS = 60_000;
    /** Liegt der letzte Klick laenger zurueck, ist die Folge vorbei (der Modus wartet oder tut anderes). */
    static final long FOLGE_AKTUELL_MS = 20_000;
    /** Hoechstens so viele Selbsthilfe-Neustarts je Stunde -- danach greifen Haenger-Bericht und 15-Minuten-Abbruch. */
    static final int NEUSTARTS_JE_STUNDE = 2;
    /** Bild-Probe: bis zu dieser mittleren Abweichung (Graustufen 0-255, grobes 32x18-Bild) steht das Bild still. */
    static final double STILL_BIS = 8.0;

    /** Ein laufender Kampf ist an diesen Vorlagen zu erkennen ... */
    static final List<String> KAMPF_VORLAGEN = List.of("unit_dead_bar", "pt_kampf_laeuft", "pt_kampf_pause",
            "ar_battle_time", "gb_battle_begins");
    /** ... ausser ein Ergebnisfenster ist zu sehen: dann ist der Kampf vorbei (UK3S: "Home" auf dem Sieg-Fenster). */
    static final List<String> ERGEBNIS_VORLAGEN = List.of("home_button", "victory_banner", "defeat_banner",
            "ar_victory", "ar_defeat", "ar_banner_victory", "ar_banner_defeat");

    /** Die Folge gleicher Klicks am Ende des Protokolls. */
    public record Folge(String klick, String erstesUm, int anzahl, long dauerMs, long letzterVorMs) {
        static final Folge KEINE = new Folge("", "", 0, 0, Long.MAX_VALUE);

        /** Der Klick fuers Protokoll (ohne die Quelle davor). */
        public String text() {
            int i = klick.indexOf('|');
            return i >= 0 ? klick.substring(i + 1) : klick;
        }
    }

    private static final Pattern MS = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*ms");
    private static final Pattern GUETE = Pattern.compile("(match|Uebereinstimmung|Treffer)\\s*\\d[.,]\\d+");

    /** Woran zwei Klicks als "derselbe" erkannt werden: Quelle und Text, ohne Zeitangaben und Trefferguete. */
    static String schluessel(Log.Entry e) {
        String m = MS.matcher(e.message()).replaceAll("#ms");
        m = GUETE.matcher(m).replaceAll("$1 #");
        return e.source() + "|" + m.strip();
    }

    /**
     * Die Folge gleicher Klicks (Aktionen) am Ende des Protokolls.
     *
     * @param jetzt      die Uhrzeit, gegen die gerechnet wird
     * @param maxAlterMs aeltere Klicks zaehlen nicht (etwa: nur die seit dem letzten Selbsthilfe-Neustart)
     */
    public static Folge folge(List<Log.Entry> eintraege, LocalTime jetzt, long maxAlterMs) {
        String key = null, erstes = null, letztes = null;
        int n = 0;
        for (int i = eintraege.size() - 1; i >= 0; i--) {
            Log.Entry e = eintraege.get(i);
            if (e.level() != Log.Level.ACTION) continue;
            if (alterMs(e.time(), jetzt) > maxAlterMs) break;
            String k = schluessel(e);
            if (key == null) {
                key = k;
                letztes = e.time();
            } else if (!key.equals(k)) {
                break;
            }
            erstes = e.time();
            n++;
        }
        if (key == null) return Folge.KEINE;
        long vonErstem = alterMs(erstes, jetzt), vonLetztem = alterMs(letztes, jetzt);
        return new Folge(key, erstes, n, Math.max(0, vonErstem - vonLetztem), vonLetztem);
    }

    /** Wie lange ist diese Protokoll-Uhrzeit ("HH:mm:ss.SSS") her? Ueber Mitternacht richtig; unlesbar = sehr alt. */
    static long alterMs(String zeit, LocalTime jetzt) {
        try {
            LocalTime t = LocalTime.parse(zeit.length() > 12 ? zeit.substring(0, 12) : zeit);
            long ms = java.time.Duration.between(t, jetzt).toMillis();
            if (ms < -43_200_000L) ms += 86_400_000L;   // Eintrag von gestern abend, jetzt ist nach Mitternacht
            return Math.max(0, ms);                      // ein paar ms "Zukunft" (Uhr-Koernung) zaehlen als jetzt
        } catch (Exception ex) {
            return Long.MAX_VALUE;
        }
    }

    /** Lang genug fuer Stufe 2 (Neustart)? */
    public static boolean neustartReif(Folge f) {
        return f.anzahl() >= NEUSTART_AB && f.dauerMs() >= NEUSTART_NACH_MS;
    }

    /**
     * Die Entscheidung.
     *
     * @param spielVorne            das Spielfenster ist gerade das aktive Fenster
     * @param nachVornVersucht      Stufe 1 lief fuer genau diese Folge schon
     * @param bildStill             die Bild-Probe: das Bild hat sich seit Beginn der Folge (nach Stufe 1: seit Stufe 1)
     *                              (fast) nicht veraendert
     * @param spielReagiertNicht    Windows meldet das Spielfenster als "reagiert nicht"
     * @param kampfZuSehen          ein laufender Kampf ist zu sehen (siehe {@link #kampfZuSehen(Predicate)})
     * @param neustartsLetzteStunde so oft hat die Selbsthilfe in der letzten Stunde schon neu gestartet
     */
    public static Schritt entscheiden(Folge f, boolean spielVorne, boolean nachVornVersucht, boolean bildStill,
                                      boolean spielReagiertNicht, boolean kampfZuSehen, int neustartsLetzteStunde) {
        if (f.anzahl() < NACH_VORN_AB || f.letzterVorMs() > FOLGE_AKTUELL_MS) return Schritt.NICHTS;
        if (!nachVornVersucht) {
            // Liegt das Spiel nicht vorne, sofort. Sonst nur, wenn die Klicks sichtbar nichts tun: Arena-Durchschauen
            // klickt hundertfach "Next opponent", aber jedes Mal steht ein anderer Gegner da (Messung 20.11.: 338
            // solcher Folgen ueber 10 s) -- das ist Arbeit, kein Haenger.
            boolean wirkungslos = !spielVorne || spielReagiertNicht || (bildStill && f.dauerMs() >= NACH_VORN_STILL_MS);
            return wirkungslos ? Schritt.NACH_VORN : Schritt.NICHTS;
        }
        if (!neustartReif(f)) return Schritt.NICHTS;
        if (neustartsLetzteStunde >= NEUSTARTS_JE_STUNDE) return Schritt.AUSGESCHOEPFT;
        // Ein Spiel, das nicht mehr antwortet, kaempft auch nicht weiter -- da geht durch den Neustart nichts verloren.
        if (spielReagiertNicht) return Schritt.NEUSTART;
        if (kampfZuSehen) return Schritt.KAMPF_ABWARTEN;
        if (!bildStill) return Schritt.BILD_BEWEGT_SICH;
        return Schritt.NEUSTART;
    }

    /** Laeuft ein Kampf? Kampf-Vorlage zu sehen und KEIN Ergebnisfenster (das heisst: der Kampf ist vorbei). */
    public static boolean kampfZuSehen(Predicate<String> sichtbar) {
        for (String v : ERGEBNIS_VORLAGEN) if (sichtbar.test(v)) return false;
        for (String v : KAMPF_VORLAGEN) if (sichtbar.test(v)) return true;
        return false;
    }

    /** Bild-Probe: mittlere Abweichung zweier grober Graubilder; fehlt eins oder passen sie nicht zusammen: unendlich. */
    public static double abweichung(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return Double.POSITIVE_INFINITY;
        long summe = 0;
        for (int i = 0; i < a.length; i++) summe += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        return (double) summe / a.length;
    }

    public static boolean still(double groessteAbweichung) {
        return groessteAbweichung <= STILL_BIS;
    }

    /** Grobes Graubild (32x18) fuer die Bild-Probe; null, wenn es kein Bild gibt. */
    public static byte[] fingerabdruck(Mat bild) {
        if (bild == null || bild.empty()) return null;
        Mat grau = new Mat(), klein = new Mat();
        try {
            int k = bild.channels();
            if (k == 4) Imgproc.cvtColor(bild, grau, Imgproc.COLOR_BGRA2GRAY);
            else if (k == 3) Imgproc.cvtColor(bild, grau, Imgproc.COLOR_BGR2GRAY);
            else bild.copyTo(grau);
            Imgproc.resize(grau, klein, new Size(32, 18), 0, 0, Imgproc.INTER_AREA);
            byte[] b = new byte[32 * 18];
            klein.get(0, 0, b);
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            grau.release();
            klein.release();
        }
    }
}
