package de.hustlebot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Sammelt alles, was die Oberflaeche anzeigen soll: was der Bot gerade erkannt
 * hat, wohin er geklickt oder gezogen hat, wie die Durchlaeufe gelaufen sind
 * und ein paar Kennzahlen.
 *
 * Alle Koordinaten beziehen sich auf den Inhaltsbereich des Spielfensters.
 */
public final class Laufstatistik {

    /** Art einer Markierung im Vorschaubild. */
    public enum Kind { TREFFER, KLICK, ZUG }

    /**
     * Eine Markierung. Bei TREFFER und KLICK beschreibt das Rechteck die Stelle,
     * bei ZUG sind zusaetzlich Endpunkt x2/y2 gesetzt.
     */
    public record Marker(Kind kind, String label, int x, int y, int width, int height,
                         int x2, int y2, double score, long createdAt) {}

    /** Ein abgeschlossener Durchlauf. */
    public record RunRecord(int number, long seconds, int failedBattles,
                            int resurrections, String teammate, String finishedAt) {}

    private static final int MAX_MARKERS = 60;
    private static final long CLICK_LIFETIME_MS = 2500;

    private final List<Marker> matches = new ArrayList<>();
    private final List<Marker> actions = new ArrayList<>();
    private final List<RunRecord> history = new ArrayList<>();
    private final Object lock = new Object();

    private volatile String currentStep = "Bereit";
    private volatile String currentScreen = "–";
    private volatile long startedAt = 0;
    private volatile int resurrections = 0;

    /**
     * Ether-Zaehler des Spiels. Beim ersten Ablesen einer Sitzung wird der Stand
     * als Ausgangswert gemerkt; die Differenz zum letzten Stand ist der Gewinn.
     * -1 heisst: noch nichts abgelesen.
     */
    private volatile long etherStart = -1;
    private volatile long etherNow = -1;
    private volatile long etherReadAt = 0;

    /**
     * "Particle of Light" aus der Dunklen Kathedrale. Gleiche Rechnung wie beim
     * Ether: erster Stand ist der Ausgangswert, die Differenz ist der Gewinn.
     */
    private volatile long lightStart = -1;
    private volatile long lightNow = -1;

    /**
     * Verbleibende Schluessel der Kathedrale. -1 heisst: noch nicht abgelesen.
     * Der erste Stand einer Sitzung wird festgehalten, damit sich sagen laesst,
     * wie viele seither verbraucht wurden.
     */
    private volatile long keysStart = -1;
    private volatile long keysNow = -1;

    /**
     * "Badge of Courage" aus der Arena -- der rote Stein mit dem Schwert.
     * Gleiche Rechnung wie bei Particle of Light: erster Stand ist der
     * Ausgangswert, die Differenz ist der Gewinn der Sitzung.
     *
     * Laut Spiel gehen die Abzeichen beim Saisonwechsel NICHT verloren, sie
     * sammeln sich also wirklich an. Deshalb ist der Zuwachs die Zahl, die
     * etwas aussagt -- nicht der Gesamtstand.
     */
    private volatile long badgesStart = -1;
    private volatile long badgesNow = -1;

    /**
     * Verbleibende Turnier-Tickets. -1 heisst: noch nicht abgelesen. Wie bei
     * den Schluesseln der Kathedrale: der erste Stand bleibt stehen, damit
     * sich der Verbrauch ausrechnen laesst.
     */
    private volatile long ticketsStart = -1;
    private volatile long ticketsNow = -1;

    /**
     * Verbleibende Multiplikator-Reisen, getrennt fuer beide Spielmodi.
     * -1 heisst: noch nicht abgelesen.
     *
     * Der Multiplikator verdoppelt die Beute und laeuft mit jeder Reise um
     * eins herunter. Zu wissen, wie viele noch uebrig sind, hilft beim
     * Planen -- deshalb steht die Zahl in der Statistik.
     */
    private volatile long dcMultiplier = -1;
    private volatile long dungeonMultiplier = -1;

    // ------------------------------------------------------------------
    //  Erkennungen
    // ------------------------------------------------------------------

    /** Verwirft die Treffer des letzten Bildes. Wird vor jeder Aufnahme gerufen. */
    public void beginFrame() {
        synchronized (lock) {
            matches.clear();
        }
    }

    public void recordMatch(String label, int x, int y, int width, int height, double score) {
        synchronized (lock) {
            if (matches.size() >= MAX_MARKERS) return;
            matches.add(new Marker(Kind.TREFFER, label, x, y, width, height,
                    0, 0, score, System.currentTimeMillis()));
        }
    }

    public void recordClick(int x, int y) {
        synchronized (lock) {
            actions.add(new Marker(Kind.KLICK, "Klick", x - 12, y - 12, 24, 24,
                    0, 0, 1.0, System.currentTimeMillis()));
            trimActions();
        }
    }

    /** Merkt sich eine Ziehbewegung, damit die Linie im Vorschaubild sichtbar wird. */
    public void recordDrag(int fromX, int fromY, int toX, int toY) {
        synchronized (lock) {
            actions.add(new Marker(Kind.ZUG, "Ziehen", fromX, fromY, 0, 0,
                    toX, toY, 1.0, System.currentTimeMillis()));
            trimActions();
        }
    }

    private void trimActions() {
        long cutoff = System.currentTimeMillis() - CLICK_LIFETIME_MS;
        actions.removeIf(marker -> marker.createdAt() < cutoff);
        while (actions.size() > MAX_MARKERS) actions.remove(0);
    }

    /** Alle Markierungen, die gerade gezeichnet werden sollen. */
    public List<Marker> markers() {
        synchronized (lock) {
            trimActions();
            List<Marker> all = new ArrayList<>(matches);
            all.addAll(actions);
            return all;
        }
    }

    // ------------------------------------------------------------------
    //  Aktueller Schritt
    // ------------------------------------------------------------------

    public void setStep(String screen, String step) {
        this.currentScreen = screen;
        this.currentStep = step;
    }

    public String currentStep()   { return currentStep; }
    public String currentScreen() { return currentScreen; }

    public void countResurrection() {
        resurrections++;
    }

    // ------------------------------------------------------------------
    //  Verlauf und Kennzahlen
    // ------------------------------------------------------------------

    /**
     * Ein neuer Abschnitt faengt an.
     *
     * ACHTUNG, HIER LAG EIN STILLER FEHLER: Frueher wurden hier auch die
     * Anfangsstaende von Ether, Particle of Light und Schluesseln geloescht.
     * Das wird aber bei JEDEM Glied einer Kette gerufen -- und beim naechsten
     * Ablesen galt der aktuelle Stand als "Anfang". Im Bericht stand dann:
     *
     *     Particle of Light
     *     Jetzt: 506.801   Am Anfang: 506.801   Dazugekommen: +0
     *
     * Autor hatte vor den fuenf Kathedralen keine 506.801 -- die Zahl war
     * schlicht neu gesetzt worden. Und "+0" nach fuenf Durchlaeufen sieht
     * aus, als haette der Bot nichts geholt.
     *
     * Die Anfangsstaende bleiben jetzt stehen und werden nur bei einem
     * frischen !start zurueckgesetzt, ueber resetZaehlerstaende().
     */
    public void startSession() {
        startedAt = System.currentTimeMillis();
        resurrections = 0;
        synchronized (lock) {
            history.clear();
        }
    }

    /**
     * Setzt die Anfangsstaende zurueck: Ether, Particle of Light,
     * Schluessel und die Multiplikatoren.
     *
     * Wird nur bei einem frischen !start gerufen, nicht beim Weiterschalten
     * einer Kette und nicht beim Moduswechsel.
     */
    public void resetZaehlerstaende() {
        etherStart = -1;
        etherNow = -1;
        etherReadAt = 0;
        lightStart = -1;
        lightNow = -1;
        keysStart = -1;
        schluesselVerbraucht = 0;
        laeufeMitSchluessel = 0;
        keysNow = -1;
        badgesStart = -1;
        badgesNow = -1;
        ticketsStart = -1;
        ticketsNow = -1;
        ticketsVerbraucht = 0;
        dcMultiplier = -1;
        dungeonMultiplier = -1;
        etherPruefung.zuruecksetzen();
        lightPruefung.zuruecksetzen();
        badgesPruefung.zuruecksetzen();
    }

    public void addRun(int number, long seconds, int failedBattles, String teammate) {
        synchronized (lock) {
            history.add(new RunRecord(number, seconds, failedBattles, resurrections,
                    teammate, timeOfDay()));
        }
    }

    public List<RunRecord> history() {
        synchronized (lock) {
            return new ArrayList<>(history);
        }
    }

    /** Laufzeit der laufenden Sitzung in Sekunden. */
    public long sessionSeconds() {
        return startedAt == 0 ? 0 : (System.currentTimeMillis() - startedAt) / 1000;
    }

    /**
     * Hochgerechnete Durchlaeufe pro Stunde, aus der gemessenen mittleren
     * Dauer eines Durchlaufs.
     *
     * FRUEHER STAND HIER ETWAS ANDERES, naemlich fertige Durchlaeufe geteilt
     * durch die Laufzeit der Sitzung. Das ergab Zahlen, die der Statistik
     * daneben widersprachen -- Autor hat es gemeldet:
     *
     *     Mittlere Dauer je Kathedralen-Durchlauf: 4:36
     *     Durchlaeufe pro Stunde: 8.9
     *
     * Bei 4:36 je Durchlauf sind es dreizehn in der Stunde, nicht neun. Der
     * Fehler lag darin, dass in der Laufzeit auch alles steckt, was NICHT
     * Durchlauf ist: der Start des Bots, der Weg zur Kathedrale, die Minuten
     * vor dem ersten fertigen Durchlauf. Nach zwei Durchlaeufen waren das
     * vier von dreizehn Minuten -- die Zahl war um ein Drittel zu klein.
     * Nach dreiundzwanzig Durchlaeufen fiel es kaum noch auf (11.7 statt
     * 12.0), aber falsch war es die ganze Zeit.
     *
     * Die Fertig-um-Zeit rechnete ohnehin schon mit der mittleren Dauer.
     * Jetzt sagen beide Zahlen dasselbe.
     */
    public double runsPerHour() {
        long schnitt = averageRunSeconds();
        if (schnitt <= 0) return 0;
        return 3600.0 / schnitt;
    }

    /** Dasselbe fuer einen einzelnen Modus. */
    public double runsPerHour(String modus) {
        long schnitt = averageRunSeconds(modus);
        if (schnitt <= 0) return 0;
        return 3600.0 / schnitt;
    }

    /** Durchschnittliche Dauer eines Durchlaufs in Sekunden. */
    public long averageRunSeconds() {
        synchronized (lock) {
            long total = 0;
            int anzahl = 0;
            for (RunRecord record : history) {
                if (record.seconds() < ZU_KURZ_S) continue;
                total += record.seconds();
                anzahl++;
            }
            return anzahl == 0 ? 0 : total / anzahl;
        }
    }

    /**
     * Kuerzere "Durchlaeufe" zaehlen nicht in den Schnitt. 24.09.: Startet
     * ein Modus auf dem Abschlussbild einer frueheren Reise, wird die in einer
     * Sekunde "fertig" -- und !stats zeigte Autor 74 statt 93 s Schnitt.
     */
    private static final long ZU_KURZ_S = 20;

    // ------------------------------------------------------------------
    //  Mittlere Dauer, getrennt nach Modus
    // ------------------------------------------------------------------
    /**
     * Summe und Anzahl der Durchlaeufe je Modus.
     *
     * Warum getrennt von history: Die wird bei jedem Start eines Modus
     * geleert. Bei einer Kette wie "!start verlies 100" + "!after kathedrale
     * 50" waere damit die Verlies-Statistik weg, sobald die Kathedrale
     * anfaengt -- und genau die will Autor ja daneben stehen sehen.
     *
     * Geleert wird das hier nur auf Zuruf, naemlich wenn ein frisches
     * !start eine neue Kette aufsetzt.
     *
     * Aufbau je Eintrag: [Summe der Sekunden, Anzahl].
     */
    private final java.util.Map<String, long[]> proModus =
            new java.util.LinkedHashMap<>();

    /**
     * Das Gedaechtnis ueber alle Sitzungen hinweg.
     *
     * proModus oben gilt nur fuer die laufende Kette und ist danach weg.
     * Fuer die Frage "um wie viel Uhr sind meine 60 Durchlaeufe fertig"
     * reicht das nicht -- ein Modus, der erst in Stunden drankommt, hat in
     * dieser Sitzung noch keine einzige Messung. Deshalb daneben ein
     * dauerhafter Stand in laufzeiten.txt.
     */
    private final Laufzeiten laufzeiten = new Laufzeiten();

    /** Die gemessenen Laufzeiten ueber alle Sitzungen. */
    public Laufzeiten laufzeiten() {
        return laufzeiten;
    }

    /** Merkt einen fertigen Durchlauf fuer die Statistik nach Modus. */
    public void addRunForMode(String modus, long seconds) {
        if (modus == null || modus.isBlank() || seconds < ZU_KURZ_S) return;
        if ("cathedral".equals(modus) && keysNow >= 0) laeufeMitSchluessel++;
        synchronized (lock) {
            long[] w = proModus.computeIfAbsent(modus, k -> new long[2]);
            w[0] += seconds;
            w[1]++;
        }
        // Auch dauerhaft festhalten. Getrennt vom Sperrblock oben, weil
        // dabei eine Datei geschrieben wird -- das gehoert nicht in einen
        // Abschnitt, auf den die Oberflaeche wartet.
        laufzeiten.merke(modus, seconds);
    }

    /** Mittlere Dauer in diesem Modus. 0 heisst: noch nichts gemessen. */
    public long averageRunSeconds(String modus) {
        synchronized (lock) {
            long[] w = proModus.get(modus);
            if (w == null || w[1] == 0) return 0;
            return w[0] / w[1];
        }
    }

    /** Wie viele Durchlaeufe in diesem Modus gemessen wurden. */
    public int runsOfMode(String modus) {
        synchronized (lock) {
            long[] w = proModus.get(modus);
            return w == null ? 0 : (int) w[1];
        }
    }

    /** Alle Modi, zu denen es Zahlen gibt -- in der Reihenfolge des Auftretens. */
    public java.util.List<String> gemesseneModi() {
        synchronized (lock) {
            return new ArrayList<>(proModus.keySet());
        }
    }

    /** Setzt die Statistik nach Modus zurueck. Nur bei einem frischen !start. */
    public void resetModusStatistik() {
        synchronized (lock) {
            proModus.clear();
        }
    }

    public int resurrections() {
        return resurrections;
    }

    // ------------------------------------------------------------------
    //  Glaubwuerdigkeit abgelesener Zaehler (mit Neu-Verankerung)
    // ------------------------------------------------------------------

    /**
     * Wie viele unglaubwuerdige, aber UNTEREINANDER stimmige Ablesungen hintereinander es braucht, bis der
     * Zaehler neu verankert wird. Ein Lesefehler ist zufaellig und wiederholt sich nicht zweimal in dieselbe
     * Gegend; ein echter Zaehler, der weitergelaufen ist, tut es.
     */
    static final int NEU_VERANKERN_NACH = 3;

    /**
     * Pruefung "ist dieser abgelesene Stand glaubhaft?" fuer Zaehler mit festem Obergrenze-fuer-einen-Sprung.
     *
     * WARUM (Bugjagd 29.10., Protokolle 27.-29.10.): bis dahin wurde ein Stand mit zu grossem Sprung gegenueber dem
     * LETZTEN ANGENOMMENEN Stand verworfen -- fuer immer. Lief der echte Zaehler (Particle of Light) in zwei
     * Stunden um mehr als die Grenze weiter (500 000), war jede weitere, richtige Ablesung "ein Sprung": 30 Mal
     * verworfen, alle steigend (311,2 -> 312,6 Mio), die Anzeige "Gewinn dieser Sitzung" blieb bei 1,5 statt 3,6 Mio
     * stehen. Jetzt: stimmen NEU_VERANKERN_NACH unglaubwuerdige Ablesungen hintereinander untereinander (je
     * hoechstens {@code maxSprung} auseinander), ist der Zaehler wirklich dort -- der neue Stand gilt.
     */
    static final class Zaehlerpruefung {
        private final long maxSprung;
        private long verdacht = -1;
        private int anzahl = 0;

        Zaehlerpruefung(long maxSprung) { this.maxSprung = maxSprung; }

        /** true = {@code wert} uebernehmen. {@code anker} ist der zuletzt angenommene Stand. */
        boolean annehmen(long anker, long wert) {
            if (Math.abs(wert - anker) <= maxSprung) {
                verdacht = -1;
                anzahl = 0;
                return true;
            }
            if (verdacht >= 0 && Math.abs(wert - verdacht) <= maxSprung) anzahl++;
            else anzahl = 1;
            verdacht = wert;
            if (anzahl >= NEU_VERANKERN_NACH) {
                verdacht = -1;
                anzahl = 0;
                return true;
            }
            return false;
        }

        /** Nach einem Neustart der Sitzung vergessen. */
        void zuruecksetzen() { verdacht = -1; anzahl = 0; }
    }

    private final Zaehlerpruefung etherPruefung = new Zaehlerpruefung(200_000);
    private final Zaehlerpruefung lightPruefung = new Zaehlerpruefung(500_000);
    private final Zaehlerpruefung badgesPruefung = new Zaehlerpruefung(50_000);

    // ------------------------------------------------------------------
    //  Ether-Zaehler
    // ------------------------------------------------------------------

    /**
     * Traegt einen frisch abgelesenen Ether-Stand ein.
     *
     * Der erste Wert einer Sitzung ist der Ausgangswert. Unsinnige Spruenge
     * werden verworfen: das Ablesen kann sich vertun, und ein Zaehler, der
     * ploetzlich um das Zehnfache springt, ist mit Sicherheit ein Lesefehler.
     */
    public void recordEther(long value) {
        if (value < 0) return;

        if (etherStart < 0) {
            etherStart = value;
            etherNow = value;
            etherReadAt = System.currentTimeMillis();
            Log.info("Ether", "Ausgangsstand abgelesen: " + format(value));
            return;
        }

        // Ein einzelner Verliesdurchlauf bringt nach Spielangabe bis 20 000.
        // Alles darueber hinaus ist kein Gewinn, sondern ein Lesefehler.
        long jump = value - etherNow;
        if (!etherPruefung.annehmen(etherNow, value)) {
            Log.debug("Ether", "Stand " + format(value) + " wird verworfen, das waere ein "
                    + "Sprung von " + format(jump) + " gegenueber " + format(etherNow) + ".");
            return;
        }
        if (Math.abs(jump) > 200_000) {
            Log.info("Ether", "Stand " + format(value) + " neu uebernommen: dreimal hintereinander stimmig, "
                    + "der Zaehler ist wirklich weitergelaufen (vorher " + format(etherNow) + ").");
        }

        etherNow = value;
        etherReadAt = System.currentTimeMillis();
    }

    /** True, sobald mindestens einmal abgelesen werden konnte. */
    public boolean hasEther()   { return etherStart >= 0; }
    public long etherStart()    { return etherStart; }
    public long etherNow()      { return etherNow; }
    public long etherReadAt()   { return etherReadAt; }

    /** Seit dem Start dazugekommener Ether. Kann negativ sein, wenn ausgegeben. */
    public long etherGained() {
        if (etherStart < 0 || etherNow < 0) return 0;
        return etherNow - etherStart;
    }

    /** Zahl mit Tausenderpunkten, damit sie sich lesen laesst. */
    public static String format(long value) {
        return String.format(java.util.Locale.GERMANY, "%,d", value);
    }

    // ------------------------------------------------------------------
    //  Particle of Light und Schluessel (Dunkle Kathedrale)
    // ------------------------------------------------------------------

    /** Wie recordEther, nur fuer "Particle of Light". */
    public void recordLight(long value) {
        if (value < 0) return;

        if (lightStart < 0) {
            lightStart = value;
            lightNow = value;
            Log.info("Kathedrale", "Particle of Light, Ausgangsstand: " + format(value));
            return;
        }
        long jump = value - lightNow;
        if (!lightPruefung.annehmen(lightNow, value)) {
            Log.debug("Kathedrale", "Stand " + format(value) + " verworfen, das waere ein "
                    + "Sprung von " + format(jump) + ".");
            return;
        }
        if (Math.abs(jump) > 500_000) {
            Log.info("Kathedrale", "Particle of Light: Stand " + format(value) + " neu uebernommen -- dreimal "
                    + "hintereinander stimmig, der Zaehler ist wirklich weitergelaufen (vorher " + format(lightNow) + ").");
        }
        lightNow = value;
    }

    public boolean hasLight() { return lightStart >= 0; }
    public long lightStart()  { return lightStart; }
    public long lightNow()    { return lightNow; }

    public long lightGained() {
        if (lightStart < 0 || lightNow < 0) return 0;
        return lightNow - lightStart;
    }

    /**
     * Ein Stand, der so weit unter dem letzten liegt, gilt erst nach
     * Bestaetigung.
     *
     * Eine Reise kostet einen Schluessel. Zwei, drei Stufen dazwischen sind
     * moeglich, wenn ein Bildschirm uebersprungen wurde. Ein Sturz um
     * dreissig ist dagegen kein Spielverlauf, sondern ein Ablesefehler --
     * so einer wie am 6. September, als aus 140 die Zahl 10 wurde.
     *
     * Nach OBEN gibt es keine solche Grenze: Schluessel kommen sprunghaft
     * dazu, aus Kisten, vom Handy, aus dem Laden. Ein hoeherer Stand ist
     * immer glaubhaft.
     */
    private static final long SCHLUESSEL_STURZ = 30;

    /** Ein einmal gesehener, aber noch nicht geglaubter Sturz. */
    private volatile long keysVerdacht = -1;

    /**
     * Traegt den zuletzt abgelesenen Schluesselstand ein.
     *
     * Ein grosser Sturz wird beim ersten Mal nur gemerkt, nicht uebernommen.
     * Steht dieselbe Zahl beim naechsten Ablesen wieder da, ist sie echt.
     */
    public void recordKeys(long value) {
        if (value < 0 || value > 100_000) return;

        boolean sturz = keysNow >= 0 && value < keysNow - SCHLUESSEL_STURZ;
        if (sturz && value != keysVerdacht) {
            keysVerdacht = value;
            Log.info("Kathedrale", Sprache.t("Key count " + value + " read, before it was " + keysNow + ". That would be a drop of " + (keysNow - value) + " -- only taken over when the same number shows up again.",
                    "Schluesselstand " + value + " gelesen, vorher waren es "
                    + keysNow + ". Das waere ein Sturz um " + (keysNow - value)
                    + " -- wird erst uebernommen, wenn dieselbe Zahl noch einmal dasteht."));
            return;
        }
        keysVerdacht = -1;

        if (keysNow != value) {
            Log.debug("Kathedrale", "Schluessel: " + value);
        }
        // Fuer die Vorschau "reicht noch fuer ... Runs": nur das Weniger-
        // werden zaehlt. Mehr (Kisten, Handy) ist kein Verbrauch.
        if (keysNow >= 0 && value < keysNow) schluesselVerbraucht += keysNow - value;
        if (keysStart < 0) keysStart = value;
        keysNow = value;
    }

    /** Summe aller Rueckgaenge des Schluesselstands in dieser Sitzung. */
    private volatile long schluesselVerbraucht = 0;
    /** Fertige Kathedralen-Runs, waehrend der Stand bekannt war. */
    private volatile int laeufeMitSchluessel = 0;

    /**
     * Wie viele Schluessel ein Kathedralen-Run im Schnitt kostet, oder -1,
     * solange es weniger als drei Runs mit bekanntem Stand sind (Autor,
     * 25.09.: Schluessel-Vorschau).
     */
    public double schluesselJeLauf() {
        if (laeufeMitSchluessel < 3 || schluesselVerbraucht <= 0) return -1;
        return (double) schluesselVerbraucht / laeufeMitSchluessel;
    }

    /**
     * Vergisst den Schluesselstand, damit er frisch abgelesen wird.
     *
     * Gebraucht nach dem Kisten-Gang: Danach sind neue Schluessel da, und
     * der alte Stand wuerde den Bot glauben lassen, er sei immer noch leer.
     * Der ANFANGSSTAND bleibt stehen -- sonst waere die Rechnung "verbraucht"
     * hinueber.
     */
    public void vergissSchluessel() {
        keysNow = -1;
        keysVerdacht = -1;
    }

    public boolean hasKeys() { return keysNow >= 0; }
    public long keysNow()    { return keysNow; }
    public long keysStart()  { return keysStart; }

    // ------------------------------------------------------------------
    //  Badge of Courage und Tickets (Arena)
    // ------------------------------------------------------------------

    /** Wie recordLight, nur fuer die Abzeichen der Arena. */
    public void recordBadges(long value) {
        if (value < 0) return;

        if (badgesStart < 0) {
            badgesStart = value;
            badgesNow = value;
            Log.info("Arena", "Badge of Courage, Ausgangsstand: " + format(value));
            return;
        }
        // Dieselbe Sicherung wie beim Ether: ein wilder Sprung kommt vom
        // Verlesen, nicht vom Spiel. Ein Turnier bringt hoechstens ein paar
        // hundert Abzeichen, deshalb ist die Grenze hier enger gezogen.
        long jump = value - badgesNow;
        if (!badgesPruefung.annehmen(badgesNow, value)) {
            Log.debug("Arena", "Stand " + format(value) + " verworfen, das waere ein "
                    + "Sprung von " + format(jump) + ".");
            return;
        }
        if (Math.abs(jump) > 50_000) {
            Log.info("Arena", "Badge of Courage: Stand " + format(value) + " neu uebernommen -- dreimal "
                    + "hintereinander stimmig (vorher " + format(badgesNow) + ").");
        }
        badgesNow = value;
    }

    public boolean hasBadges() { return badgesStart >= 0; }
    public long badgesNow()    { return badgesNow; }

    public long badgesGained() {
        if (badgesStart < 0 || badgesNow < 0) return 0;
        return badgesNow - badgesStart;
    }

    /** Traegt den zuletzt abgelesenen Ticketstand ein. */
    public void recordTickets(long value) {
        if (value < 0 || value > 100_000) return;
        if (ticketsNow != value) {
            Log.debug("Arena", "Tickets: " + value);
        }
        // Wie bei den Schluesseln: jeder Rueckgang ist ein verbrauchtes
        // Ticket. "Anfang minus jetzt" waere falsch, sobald Tickets dazukommen.
        if (ticketsNow >= 0 && value < ticketsNow) ticketsVerbraucht += ticketsNow - value;
        if (ticketsStart < 0) ticketsStart = value;
        ticketsNow = value;
    }

    /** Summe aller Rueckgaenge des Ticketstands in dieser Sitzung. */
    private volatile long ticketsVerbraucht = 0;

    public boolean hasTickets() { return ticketsNow >= 0; }
    public long ticketsNow()    { return ticketsNow; }

    /** Wie viele Tickets seit dem Start der Sitzung verbraucht wurden. */
    public long ticketsUsed() {
        if (ticketsStart < 0) return 0;
        return ticketsVerbraucht;
    }

    public long ticketsStart() { return ticketsStart; }

    /** Traegt die verbleibenden Multiplikator-Reisen der Kathedrale ein. */
    public void recordCathedralMultiplier(long value) {
        if (value < 0 || value > 100_000) return;
        if (dcMultiplier != value) {
            Log.debug("Kathedrale", "Multiplikator-Reisen: " + value);
        }
        dcMultiplier = value;
    }

    /** Dasselbe fuer das Verlies. */
    public void recordDungeonMultiplier(long value) {
        if (value < 0 || value > 100_000) return;
        if (dungeonMultiplier != value) {
            Log.debug("Verlies", "Multiplikator-Reisen: " + value);
        }
        dungeonMultiplier = value;
    }

    public boolean hasCathedralMultiplier() { return dcMultiplier >= 0; }
    public long cathedralMultiplier()       { return dcMultiplier; }
    public boolean hasDungeonMultiplier()   { return dungeonMultiplier >= 0; }
    public long dungeonMultiplier()         { return dungeonMultiplier; }

    /**
     * Wie viele Schluessel seit dem Start der Sitzung verbraucht wurden.
     *
     * Hier stand "Anfang minus jetzt". Das ging schief, sobald der Bot
     * zwischendurch Kathedralen-Kisten oeffnet (Autor, 27.09.: "am Anfang 20,
     * jetzt 35 uebrig, verbraucht 0 -- obwohl ich beim 29. Run war"). Jetzt
     * zaehlt jeder Rueckgang des Zaehlers; nachgerechnet am Protokoll vom
     * 27.09.: 44 Runs, 44 Schluessel.
     */
    public long keysUsed() {
        if (keysStart < 0) return 0;
        return schluesselVerbraucht;
    }

    /** Wie viele Schluessel dazugekommen sind (Kisten, Handy): jetzt - Anfang + verbraucht. */
    public long keysGained() {
        if (keysStart < 0 || keysNow < 0) return 0;
        return Math.max(0, keysNow - keysStart + schluesselVerbraucht);
    }

    private static String timeOfDay() {
        return java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}
