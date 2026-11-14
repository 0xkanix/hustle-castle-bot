package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

/**
 * Zaehlt abgeschlossene Durchlaeufe und ueberwacht, wie lange nichts mehr
 * passiert ist. Daraus ergibt sich der Abbruch bei einem untaetigen Mitspieler.
 */
public final class RunTracker {

    /**
     * Wie viele Durchlaeufe geplant sind.
     *
     * Nicht endgueltig: Mit "!wq runs 1 20" laesst sich das Ziel auch
     * mitten im Betrieb aendern. Setzt Autor es unter den Stand, der schon
     * erreicht ist, gilt der Modus beim naechsten Blick als fertig -- das
     * ist gewollt und die einfachste Art, einen laufenden Modus vorzeitig
     * sauber zu beenden.
     */
    private volatile int targetRuns;
    private volatile int completedRuns = 0;
    private volatile int failedBattles = 0;

    private volatile long lastProgressAt = System.currentTimeMillis();
    private volatile long runStartedAt = System.currentTimeMillis();
    private volatile String lastProgressNote = "Start";
    private volatile long lastRunSeconds = 0;

    /** Nach dieser Zeit ohne Fortschritt gilt der Mitspieler als untaetig. */
    private long idleTimeoutMillis = 5 * 60 * 1000L;

    public RunTracker(int targetRuns) {
        this.targetRuns = targetRuns;
    }

    /** Meldet, dass sich etwas getan hat. Setzt die Untaetigkeitsuhr zurueck -- ausser bei einer Klick-Schleife (siehe unten). */
    public void progress(String note) {
        progress(note, note.equals(lastProgressNote) && klickSchleife());
    }

    /**
     * @param ohneWirkung true = dieselbe Meldung wie eben UND der Bot klickt nur immer dasselbe: das ist kein Fortschritt,
     *                    die Untaetigkeitsuhr laeuft weiter (Haenger-Bericht nach 20 s, Abbruch nach 15 Minuten).
     */
    void progress(String note, boolean ohneWirkung) {
        if (!ohneWirkung) lastProgressAt = System.currentTimeMillis();
        if (!note.equals(lastProgressNote)) {
            lastProgressNote = note;
            Log.debug("Fortschritt", note);
        }
    }

    long letzterFortschrittUm() { return lastProgressAt; }

    /** Klick-Schleife: mindestens so viele Klicks in so vielen Sekunden, davon 90 % derselbe (ben: 1 Klick je 2 s = 30 je Minute). */
    static final int SCHLEIFE_KLICKS = 25, SCHLEIFE_SEKUNDEN = 60;

    private volatile long schleifeGeprueftUm = 0;
    private volatile boolean schleifeGefunden = false;

    /** Hoechstens einmal je Sekunde nachsehen (Log.snapshot kopiert bis zu 2000 Zeilen). */
    private boolean klickSchleife() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - schleifeGeprueftUm >= 1000) {
            schleifeGeprueftUm = jetzt;
            boolean neu = de.hustlebot.core.Report.klickSchleife(SCHLEIFE_SEKUNDEN, SCHLEIFE_KLICKS);
            if (neu && !schleifeGefunden) {
                Log.warn("Motor", Sprache.t("The bot has been clicking the same thing over and over for a minute -- that does not count as progress.",
                        "Der Bot klickt seit einer Minute immer dasselbe -- das zaehlt nicht als Fortschritt."));
            }
            schleifeGefunden = neu;
        }
        return schleifeGefunden;
    }

    public boolean isIdleTooLong() {
        return System.currentTimeMillis() - lastProgressAt > idleTimeoutMillis;
    }

    public long idleSeconds() {
        return (System.currentTimeMillis() - lastProgressAt) / 1000;
    }

    /**
     * Wird nach jedem Durchlauf gefragt, wie viel die Bilderkennung gekostet
     * hat. Leer, wenn niemand etwas zu sagen hat.
     *
     * Seit dem Umbau auf die Kastensuche (20.09.) ist das die Zahl, an der
     * sich ablesen laesst, ob der Bot wirklich nur noch dort hinsieht, wo
     * der Knopf sein kann -- statt jedes Mal das ganze Bild durchzurechnen.
     */
    private java.util.function.Supplier<String> erkennungsBericht;

    public void setErkennungsBericht(java.util.function.Supplier<String> bericht) {
        this.erkennungsBericht = bericht;
    }

    // ------------------------------------------------------------------
    //  Pausen zaehlen nicht mit
    // ------------------------------------------------------------------
    //
    // Autor, 23.09.: "Nachdem ich resume geschrieben hab, weil ich 15 min
    // lang am Handy in-game was gemacht hab ... soll diese Zeit nicht zu der
    // durchschnittlichen Geschwindigkeit dazugezaehlt werden."

    /** Seit wann pausiert ist, oder 0. */
    private long pauseSeit = 0;
    /** Pausenzeit im laufenden Durchlauf. */
    private long pauseImLaufMs = 0;
    private volatile long letztePauseMs = 0;

    public synchronized void pauseBeginnt() {
        if (pauseSeit == 0) pauseSeit = System.currentTimeMillis();
    }

    public synchronized void pauseEndet() {
        if (pauseSeit == 0) return;
        letztePauseMs = System.currentTimeMillis() - pauseSeit;
        pauseImLaufMs += letztePauseMs;
        pauseSeit = 0;
    }

    /** Wie lang die letzte Pause war, in Sekunden. */
    public long letztePauseSekunden() { return letztePauseMs / 1000; }

    // ------------------------------------------------------------------
    //  Nur die Zeit in der Reise zaehlt (Kathedrale)
    // ------------------------------------------------------------------
    //
    // Autor, 25.09.: "Die durchschnittliche Zeit pro Cathedral-Run soll
    // wirklich nur anhand der Zeit berechnet werden, die der Bot in der
    // Cathedral gebraucht hat." Wege von der Burg in die Kathedrale, der
    // Kisten-Gang in die Tasche und zurueck oder ein Abstecher in den
    // Clan-Krieg zaehlen nicht mit. Gemessen wird ab "Participate" bis zum
    // Abschlussfenster der Reise. Modi, die reiseBeginnt() nie rufen,
    // messen wie bisher vom Ende des letzten Durchlaufs an.

    /** Wann "Participate" gedrueckt wurde, oder 0. */
    private long reiseBeginn = 0;

    /**
     * Die Reise beginnt jetzt (Klick auf "Participate"). Wird er mehrmals
     * gedrueckt, gilt der letzte Klick. Pausen davor zaehlen nicht mehr.
     */
    public synchronized void reiseBeginnt() {
        long jetzt = System.currentTimeMillis();
        reiseBeginn = jetzt;
        pauseImLaufMs = 0;
        if (pauseSeit > 0) pauseSeit = jetzt;
    }

    /** Ab wann der laufende Durchlauf zaehlt. */
    private long laufBeginn() {
        return reiseBeginn > 0 ? reiseBeginn : runStartedAt;
    }

    /** Wie lange der laufende Durchlauf schon dauert, Pausen abgezogen. */
    public synchronized long laufendeSekunden() {
        long jetzt = System.currentTimeMillis();
        long pause = pauseImLaufMs + (pauseSeit > 0 ? jetzt - pauseSeit : 0);
        return Math.max(0, jetzt - laufBeginn() - pause) / 1000;
    }

    public void completeRun() {
        completedRuns++;
        long pauseMs, beginn;
        synchronized (this) {
            long jetzt = System.currentTimeMillis();
            pauseMs = pauseImLaufMs + (pauseSeit > 0 ? jetzt - pauseSeit : 0);
            pauseImLaufMs = 0;
            if (pauseSeit > 0) pauseSeit = jetzt;
            beginn = laufBeginn();
            reiseBeginn = 0;
        }
        long dauerMs = Math.max(0, System.currentTimeMillis() - beginn - pauseMs);
        lastRunSeconds = dauerMs / 1000;
        // In Sekunden, solange es Sekunden sind. "etwa 1 Minuten" sagt bei
        // einem Durchlauf von 70 Sekunden nichts mehr -- und genau dorthin
        // soll es ja gehen.
        String dauer = dauerMs < 180_000
                ? Sprache.t((dauerMs / 1000) + " seconds", (dauerMs / 1000) + " Sekunden")
                : Sprache.t("about " + (dauerMs / 60000) + " minutes", "etwa " + (dauerMs / 60000) + " Minuten");
        Log.info("Durchlauf", Sprache.t("Run " + completedRuns + " of " + targetRuns
                + " completed after " + dauer + ".",
                "Durchlauf " + completedRuns + " von " + targetRuns
                + " abgeschlossen nach " + dauer + "."));
        if (erkennungsBericht != null) {
            String b = erkennungsBericht.get();
            if (b != null && !b.isBlank()) Log.info("Bilderkennung", b);
        }
        runStartedAt = System.currentTimeMillis();
        progress(Sprache.t("Run completed", "Durchlauf abgeschlossen"));
    }

    public void countFailedBattle() {
        failedBattles++;
        Log.warn("Kampf", Sprache.t("Battle lost. Battles lost in total: " + failedBattles,
                "Kampf verloren. Verlorene Kaempfe insgesamt: " + failedBattles));
    }

    public boolean isDone() {
        return completedRuns >= targetRuns;
    }

    public int completedRuns()  { return completedRuns; }
    public int targetRuns()     { return targetRuns; }

    /** Aendert das Ziel mitten im Betrieb. Siehe Kommentar bei targetRuns. */
    public void setTargetRuns(int runs) {
        this.targetRuns = Math.max(1, Math.min(1_000_000, runs));   // 29.09.: 500er-Grenze raus (Autor)
        Log.info("Durchlauf", Sprache.t("Target changed: " + completedRuns + " of "
                + this.targetRuns + " runs.",
                "Ziel geaendert: " + completedRuns + " von "
                + this.targetRuns + " Durchlaeufen."));
    }
    public int failedBattles()  { return failedBattles; }
    public String lastNote()    { return lastProgressNote; }
    public long lastRunSeconds(){ return lastRunSeconds; }

    public void setIdleTimeoutMinutes(int minutes) {
        this.idleTimeoutMillis = minutes * 60_000L;
    }

    /** Die eingestellte Frist in Millisekunden, etwa fuer die Mitspielersuche. */
    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    public void resetIdleClock() {
        lastProgressAt = System.currentTimeMillis();
    }
}
