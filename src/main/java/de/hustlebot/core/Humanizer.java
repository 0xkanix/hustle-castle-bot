package de.hustlebot.core;

import java.util.Random;

/**
 * Sorgt dafuer, dass der Bot nicht im Maschinentakt arbeitet.
 *
 * Menschen handeln nicht gleichmaessig: die Reaktionszeit schwankt, gelegentlich
 * zoegert man, und nach laengerem Spielen macht man Pausen. Diese Klasse bildet
 * das nach. Sie ersetzt keine Tarnung, sondern glaettet nur die auffaelligsten
 * Muster einer starren Schleife.
 */
public final class Humanizer {

    private final Random random = new Random();

    private boolean enabled = true;

    /**
     * Wie stark das menschliche Verhalten ausfaellt.
     * 0.0 heisst maschinell praezise und schnell, 1.0 heisst deutlich
     * menschlich mit langen Reaktionszeiten. 0.5 ist die Mitte.
     */
    private double intensity = 0.5;

    /** Grenzen der Pause zwischen zwei Durchlaeufen, in Sekunden. */
    private int breakMinSeconds = 15;
    private int breakMaxSeconds = 45;

    /** Nach so vielen Durchlaeufen folgt eine laengere Pause. */
    private int longBreakEveryRuns = 5;
    private int longBreakMinMinutes = 1;
    private int longBreakMaxMinutes = 3;

    /** Wie oft der Bot mitten im Ablauf kurz innehaelt, in Prozent. */
    private int hesitationPercent = 7;

    // ------------------------------------------------------------------
    //  Zeitabstaende
    // ------------------------------------------------------------------

    /**
     * Streut eine Wartezeit. Statt exakt der vorgegebenen Dauer wird ein Wert
     * um diesen Mittelwert herum gewaehlt, mit einer leichten Neigung zu
     * laengeren Zeiten, wie sie auch bei menschlichen Reaktionen auftritt.
     */
    public long vary(long baseMillis) {
        if (!enabled) return baseMillis;

        double spread = 0.30 * intensity;
        double factor = 1.0 + random.nextGaussian() * spread;
        factor = Math.max(1.0 - spread * 2, Math.min(1.0 + spread * 2.5, factor));

        long result = Math.round(baseMillis * factor);

        // Gelegentlich zoegert der Bot spuerbar laenger.
        int chance = (int) Math.round(hesitationPercent * intensity * 2);
        if (chance > 0 && random.nextInt(100) < chance) {
            long extra = Math.round((250 + random.nextInt(1100)) * intensity);
            Log.debug("Verhalten", "Kurzes Zoegern, " + extra + " ms zusaetzlich.");
            result += extra;
        }
        return Math.max(60, result);
    }

    /** Zusaetzliche Bedenkzeit, bevor ein Knopf gedrueckt wird. */
    public long reactionDelay() {
        if (!enabled) return 0;
        double base = 240 * intensity;
        return Math.round(base + Math.abs(random.nextGaussian()) * 320 * intensity);
    }

    /** Pause zwischen zwei Schritten der Mausbewegung, in Millisekunden. */
    public long stepPause() {
        if (!enabled) return 0;   // Autor, 19.09.: schneller klicken -- ohne Humanizer keine Pause noetig
        return Math.round(2 + random.nextInt(4) + 8 * intensity);
    }

    /** Wie weit der Zeiger unterwegs vom geraden Weg abweicht, in Bildpunkten. */
    public int driftPixels() {
        if (!enabled || intensity < 0.25) return 0;
        int limit = (int) Math.round(2 * intensity);
        if (limit <= 0) return 0;
        return random.nextInt(limit * 2 + 1) - limit;
    }

    // ------------------------------------------------------------------
    //  Pausen zwischen Durchlaeufen
    // ------------------------------------------------------------------

    /**
     * Liefert die Pause nach einem abgeschlossenen Durchlauf. Nach mehreren
     * Durchlaeufen faellt sie deutlich laenger aus, so als wuerde man
     * zwischendurch etwas anderes tun.
     */
    public long breakAfterRun(int completedRuns) {
        if (!enabled) return 0;

        boolean longBreak = longBreakEveryRuns > 0
                && completedRuns > 0
                && completedRuns % longBreakEveryRuns == 0;

        if (longBreak) {
            int minutes = longBreakMinMinutes
                    + random.nextInt(Math.max(1, longBreakMaxMinutes - longBreakMinMinutes + 1));
            long millis = minutes * 60_000L + random.nextInt(45_000);
            Log.info("Verhalten", Sprache.t("Longer break after " + completedRuns + " runs: about " + (millis / 60_000) + " minutes.",
                    "Laengere Pause nach " + completedRuns
                    + " Durchlaeufen: etwa " + (millis / 60_000) + " Minuten."));
            return millis;
        }

        int seconds = breakMinSeconds
                + random.nextInt(Math.max(1, breakMaxSeconds - breakMinSeconds + 1));
        Log.info("Verhalten", Sprache.t("Break before the next run: " + seconds + " seconds.",
                "Pause vor dem naechsten Durchlauf: " + seconds + " Sekunden."));
        return seconds * 1000L;
    }

    // ------------------------------------------------------------------
    //  Streuung fuer Klicks
    // ------------------------------------------------------------------

    /** Zufaelliger Versatz innerhalb eines Knopfes, mit Schwerpunkt in der Mitte. */
    public int offsetWithin(int extent) {
        if (!enabled || extent <= 2) return 0;
        int limit = Math.max(1, (int) Math.round(extent / 3.0 * (0.4 + intensity * 0.6)));
        double value = random.nextGaussian() * (limit / 2.2);
        return (int) Math.max(-limit, Math.min(limit, Math.round(value)));
    }

    /** True, wenn die Maus diesmal ueber das Ziel hinausschiessen soll. */
    public boolean shouldOvershoot() {
        if (!enabled) return false;
        return random.nextInt(100) < Math.round(40 * intensity);
    }

    public Random random() {
        return random;
    }

    // ------------------------------------------------------------------
    //  Einstellungen
    // ------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 0.0 maschinell, 1.0 stark menschlich, 0.5 die Mitte. */
    public void setIntensity(double intensity) {
        this.intensity = Math.max(0.0, Math.min(1.0, intensity));
    }

    public double intensity() {
        return intensity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setBreakSeconds(int min, int max) {
        this.breakMinSeconds = Math.max(0, min);
        this.breakMaxSeconds = Math.max(this.breakMinSeconds, max);
    }

    public void setLongBreak(int everyRuns, int minMinutes, int maxMinutes) {
        this.longBreakEveryRuns = everyRuns;
        this.longBreakMinMinutes = Math.max(0, minMinutes);
        this.longBreakMaxMinutes = Math.max(this.longBreakMinMinutes, maxMinutes);
    }

    public void setHesitationPercent(int percent) {
        this.hesitationPercent = Math.max(0, Math.min(50, percent));
    }
}
