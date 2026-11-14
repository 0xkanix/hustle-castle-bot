package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Event-Automatik (Autor, 28.09.): solange ein Hammer-Event laeuft, wird aus
 * "!start kathedrale c" ein Kreislauf:
 *
 *   Kathedrale, bis die Schluessel alle sind
 *   -> Tasche: Schluessel-Kisten oeffnen, weiter Kathedrale
 *   -> keine Kisten mehr: ins Hammer-Event, alle Haemmer benutzen
 *      (die Schluessel-Kisten daraus landen in der Tasche -> wieder Kathedrale)
 *   -> keine Schluessel, keine Kisten, keine Haemmer: eine eingestellte Zahl
 *      Verlies-Runs, danach ist der Auftrag zu Ende.
 *
 * Leuchtturm, Kolosseum und Titanen-Turm laufen nebenbei nach ihren eigenen
 * Schaltern. Im Kreislauf bezahlt die Kathedrale nur mit Schluesseln.
 */
public final class Eventautomatik {

    private Eventautomatik() {}

    // --- Kreislauf ohne Ende (Autor, 29.09.) ---
    private static volatile boolean eventZuerst;
    private static int leereRunden;

    /** Die naechste Kreislauf-Kathedrale soll zuerst ins Event (nach den Verlies-Runs). */
    public static void eventZuerstMerken() { eventZuerst = true; }

    public static boolean eventZuerstAbholen() {
        boolean e = eventZuerst;
        eventZuerst = false;
        return e;
    }

    /**
     * Die Event-Phase ist wirklich zu Ende (Haemmer alle oder Weg nicht gefunden).
     * Bis dahin bleibt der Merker stehen: Kommt mitten im Event ein Einschub
     * (Leuchtturm, Kolosseum, Clan-Krieg), geht der Kreislauf danach zurueck ins
     * Event statt von vorne mit der Kathedrale (Nachtlauf 29.09.: ab 08:26 nur
     * noch Kathedrale, die Haemmer blieben liegen).
     */
    public static void eventFertig() { eventZuerst = false; }

    /** Steht der Merker gerade? (fuer den Spiel-Neustart: danach wieder ins Event) */
    public static boolean eventZuerstGemerkt() { return eventZuerst; }

    /**
     * Kreislauf fuer DIESEN Auftrag an oder aus (null = der Schalter gilt).
     * Gesetzt vom Start-Befehl ("eventablauf" / "ohneevent"), gilt bis zum
     * naechsten !start -- auch fuer die Verlies-Runs und die Kathedrale danach.
     */
    private static volatile Boolean imAuftrag;

    public static void fuerAuftrag(Boolean kreislauf) { imAuftrag = kreislauf; }

    /** Die Wahl fuer den laufenden Auftrag (null = der Schalter gilt) -- zum Zuruecksetzen nach der Arena-Pause. */
    public static Boolean imAuftrag() { return imAuftrag; }

    /** Der gespeicherte Schalter (fuer die Anzeige unter !settings). */
    public static boolean schalterAn() {
        return an;
    }

    /** Zaehlt Runden ohne einen einzigen gespielten Durchlauf; liefert, wie viele hintereinander. */
    public static synchronized int leereRunde(boolean leer) {
        leereRunden = leer ? leereRunden + 1 : 0;
        return leereRunden;
    }

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("event-automatik.txt"), "Event-Automatik", false);
    private static final Path RUNS_DATEI = Path.of("event-automatik-verlies.txt");
    public static final int RUNS_STANDARD = 0;   // Freunde, 30.09.: wie bei Autor -- keine Verlies-Runs am Ende

    private static volatile boolean an = SCHALTER.lesen();
    private static volatile int verliesRuns = runsLaden();

    /** Ausnahme fuer die letzten Event-Tage (Autor 28.10.): je Stage ALLE Lupen, bis alle Vasen offen sind, und spielen,
     *  bis Haemmer und Super-Haemmer leer sind. Datei event-lupen-alle.txt mit "an"; gelesen beim Start. */
    private static final boolean ALLE_LUPEN =
            new Schalterdatei(Path.of("event-lupen-alle.txt"), "Alle Lupen je Stage", false).lesen();

    public static boolean alleLupen() {
        return ALLE_LUPEN;
    }

    /**
     * Welcher Event-Pass (Autor, 28.09.): ohne Pass gibt es nur Haemmer, mit
     * dem Silber-Pass (Echtgeld) Haemmer und Lupen, mit dem Gold-Pass dazu die
     * blauen Super-Haemmer. Autor hat immer Gold -- das ist der Standard.
     */
    public enum Pass {
        KEIN("kein Pass", "no pass"), SILBER("Silber-Pass", "silver pass"), GOLD("Gold-Pass", "gold pass");

        public final String anzeige, englisch;

        Pass(String anzeige, String englisch) {
            this.anzeige = anzeige;
            this.englisch = englisch;
        }
    }

    private static final Path PASS_DATEI = Path.of("event-pass.txt");
    private static volatile Pass pass = passLaden();

    public static Pass pass() {
        return pass;
    }

    public static void setPass(Pass neu) {
        pass = neu;
        try {
            Files.writeString(PASS_DATEI, Sprache.t("# Event pass: none | silver | gold\n", "# Event-Pass: kein | silber | gold\n") + neu.name().toLowerCase() + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Pass could not be saved: ", "Pass liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", Sprache.t("Pass: " + neu.englisch + ".", "Pass: " + neu.anzeige + "."));
        // Autor 27.10.: ohne eigene event-lupen.txt haengt die Lupenzahl am Pass (Gold 6, sonst 5) -- bei Pass-Wechsel neu rechnen.
        if (!Files.exists(LUPEN_DATEI)) maxLupen = lupenStandard();
    }

    /** Hoechstens so viele Lupen je Feld (einstellbar). Standard: mit Gold-Pass 6, sonst 5
     *  (Autor 27.10.: mit dem Gold-Pass kommen Lupen zurueck, ohne nicht). Eine eigene event-lupen.txt sticht. */
    private static final Path LUPEN_DATEI = Path.of("event-lupen.txt");
    private static volatile int maxLupen = zahlLaden(LUPEN_DATEI, lupenStandard(), 0, 10);

    /** Standard-Lupenzahl je Pass, wenn es keine eigene event-lupen.txt gibt: Gold-Pass 6, sonst 5. */
    private static int lupenStandard() {
        return pass == Pass.GOLD ? 6 : 5;
    }

    public static int maxLupen() {
        return maxLupen;
    }

    public static void setMaxLupen(int n) {
        maxLupen = Math.max(0, Math.min(10, n));
        try {
            Files.writeString(LUPEN_DATEI, Sprache.t("# At most this many magnifiers per field in the Hammer Event\n", "# Hoechstens so viele Lupen je Feld im Hammer-Event\n") + maxLupen + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Magnifier count could not be saved: ", "Lupen-Zahl liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", Sprache.t("At most " + maxLupen + " magnifiers per field.", "Hoechstens " + maxLupen + " Lupen je Feld."));
    }

    // Autor 27.10.: blaue Super-Haemmer stapeln sich (219 ungenutzt). Mit "viel" in superhammer-modus.txt setzt der Bot
    // sie schon ab ZWEI geschlossenen Faessern ein (statt erst ab drei) -- so werden sie staerker abgebaut.
    private static final Path SUPER_MODUS_DATEI = Path.of("superhammer-modus.txt");
    private static volatile boolean superAggressiv = superModusLaden();

    /** true = blaue Haemmer schon ab zwei Faessern einsetzen (Datei superhammer-modus.txt "viel"); sonst erst ab drei. */
    public static boolean superHammerAggressiv() {
        return superAggressiv;
    }

    private static boolean superModusLaden() {
        try {
            if (!Files.exists(SUPER_MODUS_DATEI)) return false;
            for (String z : Dateien.zeilen(SUPER_MODUS_DATEI)) {
                String t = z.replace("﻿", "").trim().toLowerCase();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return t.startsWith("viel") || t.startsWith("alle") || t.startsWith("aggress") || t.equals("an") || t.equals("mehr");
            }
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(SUPER_MODUS_DATEI + " not readable, normal mode applies.", SUPER_MODUS_DATEI + " nicht lesbar, es gilt der normale Modus."));
        }
        return false;
    }

    private static int zahlLaden(Path datei, int standard, int min, int max) {
        return zahlLaden(datei, null, standard, min, max);
    }

    /**
     * Erste Zahl der ersten Zeile, die keine Leer- oder #-Zeile ist (mit vorangestelltem "praefix", z. B. "alle=", sonst
     * jede Zeile), in [min, max] geklemmt. Code-Review 24.10.: BOM am Dateianfang abstreifen (sonst galt die #-Zeile als
     * Wert und "(0 = aus)" ergab 0), nur die ERSTE Zahl nehmen ("250 # (siehe 30)" war 25030), riesige Zahlen = max.
     * Keine passende Zeile: Integer.MIN_VALUE, wenn standard so gewaehlt ist (fuer den Rueckfall auf eine andere Datei).
     */
    private static int zahlLaden(Path datei, String praefix, int standard, int min, int max) {
        try {
            if (!Files.exists(datei)) return standard;
            for (String z : Dateien.zeilen(datei)) {
                String t = z.replace("\uFEFF", "").trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (praefix != null) {
                    if (!t.startsWith(praefix)) continue;
                    t = t.substring(praefix.length()).trim();
                }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(t);
                if (!m.find()) continue;
                String d = m.group();
                if (d.startsWith("-")) return min;
                if (d.length() > 6) return max;
                return Math.max(min, Math.min(max, Integer.parseInt(d)));
            }
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(datei + " not readable, " + standard + " applies.", datei + " nicht lesbar, es gilt " + standard + "."));
        }
        return standard;
    }

    /** Lupen ("See through") nur mit Silber- oder Gold-Pass. */
    public static boolean lupenErlaubt() {
        return pass != Pass.KEIN;
    }

    /** Blaue Super-Haemmer nur mit dem Gold-Pass. */
    public static boolean blaueHaemmerErlaubt() {
        return pass == Pass.GOLD;
    }

    /** "gold", "silber", "silver", "kein", "none", "ohne" ... -> Pass, sonst null. */
    public static Pass passAusWort(String w) {
        if (w == null) return null;
        String t = w.trim().toLowerCase();
        if (t.startsWith("gold") || t.equals("g")) return Pass.GOLD;
        if (t.startsWith("silb") || t.startsWith("silv") || t.equals("s")) return Pass.SILBER;
        if (t.startsWith("kein") || t.startsWith("ohne") || t.startsWith("none") || t.startsWith("no")
                || t.startsWith("free") || t.startsWith("gratis") || t.equals("0")) return Pass.KEIN;
        return null;
    }

    private static Pass passLaden() {
        try {
            if (!Files.exists(PASS_DATEI)) return Pass.GOLD;
            for (String z : Dateien.zeilen(PASS_DATEI)) {
                String t = z.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                Pass p = passAusWort(t);
                if (p != null) return p;
            }
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(PASS_DATEI + " not readable, the gold pass applies.", PASS_DATEI + " nicht lesbar, es gilt der Gold-Pass."));
        }
        return Pass.GOLD;
    }

    /** Laeuft der Kreislauf -- fuer diesen Auftrag gewaehlt, sonst der Schalter. */
    public static boolean istAn() {
        Boolean a = imAuftrag;
        return a != null ? a : an;
    }

    public static void setAn(boolean neu) {
        an = neu;
        imAuftrag = null;   // von Hand umgeschaltet: das gilt ab sofort, auch fuer den laufenden Auftrag
        SCHALTER.setzen(neu, Sprache.t("Event automation (Cathedral -> bag -> Hammer Event -> Dungeon): an = on, aus = off",
                "Event-Automatik (Kathedrale -> Tasche -> Hammer-Event -> Verlies): an oder aus"));
        Log.info("Event-Automatik", Sprache.t(neu ? "on" : "off", neu ? "an" : "aus"));
    }

    // --- Welches Event (Hauptbot 09.10., Autor: "anstatt dem hammer event" das Titan-Event) ------
    private static final Path ART_DATEI = Path.of("event-art.txt");
    /**
     * Freunde (10.10.): ohne event-art.txt gilt das Titan-Event, solange "Titanic Excavation" laeuft (09.-19.10.2026),
     * danach wieder das Hammer-Event wie vor dem Update -- sonst suchte der Kreislauf nach dem 19.10. ein Event,
     * das es nicht mehr gibt. Mit "!settings eventautomatik titan|hammer" fest gewaehlt.
     */
    private static final java.time.LocalDate TITAN_BIS = java.time.LocalDate.of(2026, 10, 19);
    /** null = nichts gewaehlt (dann nach Datum). */
    private static volatile Boolean titanGewaehlt = artLaden();

    /** true: der Kreislauf geht in die Titan-Grabung (Boss + Spitzhacken), false: ins Hammer-Event. */
    public static boolean titan() {
        Boolean g = titanGewaehlt;
        if (g != null) return g;
        java.time.LocalDate heute = Zeitzone.heute();
        if (heute.isAfter(TITAN_BIS)) return false;
        // Freunde, 10.10. (Gegenpruefung): am letzten Tag zaehlt das im Event abgelesene Ende -- sonst
        // suchte der Kreislauf nach Eventschluss bis Mitternacht alle 5 Runs die Kalenderzeile. Nur am
        // 19.10. selbst, damit eine Fehllesung vorher das Event nicht abschaltet.
        if (!heute.isBefore(TITAN_BIS) && Titanende.vorbei()) return false;
        return true;
    }

    public static void setTitan(boolean neu) {
        titanGewaehlt = neu;
        try {
            Files.writeString(ART_DATEI, Sprache.t("# Event in the loop: titan | hammer\n", "# Event im Kreislauf: titan | hammer\n") + (neu ? "titan" : "hammer") + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(ART_DATEI + " not written: ", ART_DATEI + " nicht geschrieben: ") + e.getMessage());
        }
        Log.info("Event-Automatik", Sprache.t("Event in the loop: " + (neu ? "Titan Excavation" : "Hammer Event"),
                "Event im Kreislauf: " + (neu ? "Titan-Grabung" : "Hammer-Event")));
    }

    private static Boolean artLaden() {
        try {
            if (!Files.exists(ART_DATEI)) return null;
            for (String z : Dateien.zeilen(ART_DATEI)) {
                String t = z.strip().toLowerCase();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return !t.startsWith("hammer");
            }
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(ART_DATEI + " not readable, the choice by date applies.", ART_DATEI + " nicht lesbar, es gilt die Wahl nach Datum."));
        }
        return null;
    }

    // --- Nahrungskisten je Event-Gang (Autor, 29./30.09.) ------------------------------
    private static final Path NAHRUNGSKISTEN_DATEI = Path.of("event-nahrungskisten.txt");
    public static final int NAHRUNGSKISTEN_STANDARD = 999;   // Freunde, 30.09.: wie bei Autor -- Event, bis die Haemmer alle sind
    private static volatile int nahrungskisten = nahrungskistenLaden();

    /** Nach so vielen im Event erhaltenen Nahrungskisten geht es zurueck in die Kathedrale. */
    public static int nahrungskistenJeEvent() {
        return nahrungskisten;
    }

    public static void setNahrungskisten(int n) {
        nahrungskisten = Math.max(1, Math.min(999, n));
        try {
            Files.writeString(NAHRUNGSKISTEN_DATEI, Sprache.t("# Food chests per visit to the Hammer Event, then Cathedral (!settings foodchests)\n",
                    "# Nahrungskisten je Gang ins Hammer-Event, danach Kathedrale (!settings nahrungskisten)\n")
                    + nahrungskisten + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Number of food chests could not be saved: ", "Zahl der Nahrungskisten liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", Sprache.t("Event until " + nahrungskisten + " food chests, then Cathedral.", "Event bis " + nahrungskisten + " Nahrungskisten, dann Kathedrale."));
    }

    private static int nahrungskistenLaden() {
        try {
            if (!Files.exists(NAHRUNGSKISTEN_DATEI)) return NAHRUNGSKISTEN_STANDARD;
            for (String z : Dateien.zeilen(NAHRUNGSKISTEN_DATEI)) {
                String t = z.trim();
                if (t.matches("\\d{1,3}")) return Math.max(1, Integer.parseInt(t));
            }
        } catch (Exception e) {
            // Standard
        }
        return NAHRUNGSKISTEN_STANDARD;
    }

    // Autor, 24.10.: im Kreislauf alle N Kathedralen ins Hammer-Event (alle Haemmer), dann weiter. 0 = aus
    // (dann wie frueher erst, wenn Schluessel und Kisten alle sind).
    private static final Path HAMMER_ALLE_DATEI = Path.of("event-hammer-alle.txt");
    public static final int HAMMER_ALLE_STANDARD = 100;
    public static final int MAX_ALLE = 500;
    private static volatile int hammerAlle = hammerAlleLaden();

    /** Nach wie vielen Kathedralen-Runs der Kreislauf ins Hammer-Event geht (0 = nur wenn alles leer ist). */
    public static int hammerAlleRuns() {
        return hammerAlle;
    }

    public static void setHammerAlleRuns(int runs) {
        hammerAlle = Math.max(0, Math.min(MAX_ALLE, runs));   // Autor, 24.10.: 1 bis 500
        try {
            Files.writeString(HAMMER_ALLE_DATEI, Sprache.t("# Hammer event every N cathedral runs (0 = off)\n",
                    "# Hammer-Event alle N Kathedralen-Runs (0 = aus)\n") + hammerAlle + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Hammer event interval could not be saved: ", "Hammer-Abstand liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", hammerAlle > 0
                ? Sprache.t("Hammer event every " + hammerAlle + " cathedral runs.", "Hammer-Event alle " + hammerAlle + " Kathedralen-Runs.")
                : Sprache.t("Hammer event only when keys and chests are gone.", "Hammer-Event nur, wenn Schluessel und Kisten alle sind."));
    }

    private static int hammerAlleLaden() {
        return zahlLaden(HAMMER_ALLE_DATEI, HAMMER_ALLE_STANDARD, 0, MAX_ALLE);
    }

    // Autor, 24.10.: auch das Titan-Event bekommt seinen Abstand als Einstellung (1 bis 500, 0 = nur wenn alles leer
    // ist). Ohne eigene Datei gilt wie bisher "alle=" aus event-plan.txt, sonst 100.
    private static final Path TITAN_ALLE_DATEI = Path.of("event-titan-alle.txt");
    public static final int TITAN_ALLE_STANDARD = 100;
    private static volatile int titanAlle = titanAlleLaden();

    /** Nach wie vielen Kathedralen-Runs der Kreislauf ins Titan-Event geht (0 = nur wenn alles leer ist). */
    public static int titanAlleRuns() {
        return titanAlle;
    }

    public static void setTitanAlleRuns(int runs) {
        titanAlle = Math.max(0, Math.min(MAX_ALLE, runs));
        try {
            Files.writeString(TITAN_ALLE_DATEI, Sprache.t("# Titan event every N cathedral runs (0 = off)\n",
                    "# Titan-Event alle N Kathedralen-Runs (0 = aus)\n") + titanAlle + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Titan event interval could not be saved: ", "Titan-Abstand liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", titanAlle > 0
                ? Sprache.t("Titan event every " + titanAlle + " cathedral runs.", "Titan-Event alle " + titanAlle + " Kathedralen-Runs.")
                : Sprache.t("Titan event only when keys and chests are gone.", "Titan-Event nur, wenn Schluessel und Kisten alle sind."));
    }

    private static int titanAlleLaden() {
        int eigen = zahlLaden(TITAN_ALLE_DATEI, Integer.MIN_VALUE, 0, MAX_ALLE);
        if (eigen != Integer.MIN_VALUE) return eigen;
        // Ohne eigene Einstellung wie bisher "alle=" aus event-plan.txt (Hauptbot), sonst 100.
        return zahlLaden(Path.of("event-plan.txt"), "alle=", TITAN_ALLE_STANDARD, 0, MAX_ALLE);
    }

    // Autor, 24.10.: hoechstens N Stages (Zauberstab-Felder) je Gang ins Hammer-Event, dann weiter im Kreislauf.
    // 0 = keine Grenze (alle Haemmer).
    private static final Path STAGES_DATEI = Path.of("event-stages-je-gang.txt");
    private static volatile int stagesJeGang = stagesLaden();

    /** Hoechstens so viele Stages je Gang ins Hammer-Event (0 = bis die Haemmer alle sind). */
    public static int stagesJeGang() {
        return stagesJeGang;
    }

    public static void setStagesJeGang(int n) {
        stagesJeGang = Math.max(0, Math.min(MAX_ALLE, n));
        try {
            Files.writeString(STAGES_DATEI, Sprache.t("# At most N stages per hammer event visit (0 = all hammers)\n",
                    "# Hoechstens N Stages je Gang ins Hammer-Event (0 = alle Haemmer)\n") + stagesJeGang + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Stage limit could not be saved: ", "Stage-Grenze liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", stagesJeGang > 0
                ? Sprache.t("Hammer event: at most " + stagesJeGang + " stages per visit.", "Hammer-Event: hoechstens " + stagesJeGang + " Stages je Gang.")
                : Sprache.t("Hammer event: until the hammers are gone.", "Hammer-Event: bis die Haemmer alle sind."));
    }

    private static int stagesLaden() {
        return zahlLaden(STAGES_DATEI, 0, 0, MAX_ALLE);
    }

    /** Wie viele Verlies-Runs am Ende des Kreislaufs gespielt werden. */
    public static int verliesRuns() {
        return verliesRuns;
    }

    public static void setVerliesRuns(int runs) {
        verliesRuns = Math.max(0, Math.min(500, runs));   // Freunde: 0 = keine Verlies-Runs (wie bei Autor)
        try {
            Files.writeString(RUNS_DATEI, Sprache.t("# Dungeon runs at the end of the event automation\n", "# Verlies-Runs am Ende der Event-Automatik\n") + verliesRuns + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t("Number of dungeon runs could not be saved: ", "Zahl der Verlies-Runs liess sich nicht speichern: ") + e.getMessage());
        }
        Log.info("Event-Automatik", Sprache.t("At the end " + verliesRuns + " dungeon runs.", "Am Ende " + verliesRuns + " Verlies-Runs."));
    }

    private static int runsLaden() {
        try {
            if (!Files.exists(RUNS_DATEI)) return RUNS_STANDARD;
            for (String z : Dateien.zeilen(RUNS_DATEI)) {
                String t = z.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return Math.max(0, Math.min(500, Integer.parseInt(t.replaceAll("[^0-9]", ""))));   // 0 = keine (Freunde)
            }
        } catch (Exception e) {
            Log.warn("Event-Automatik", Sprache.t(RUNS_DATEI + " not readable, " + RUNS_STANDARD + " dungeon runs apply.", RUNS_DATEI + " nicht lesbar, es gelten " + RUNS_STANDARD + " Verlies-Runs."));
        }
        return RUNS_STANDARD;
    }
}
