package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Portal: Level 80 im Autokampf, je Durchgang 30 Kaempfe (Autor, 09.10. abends).
 *
 * Burg -> Karte -> ein Zug nach links (nicht zu weit, Autor: "sonst findet er das Portal nicht") -> "Portal" ->
 * rechts neben den Belohnungen der Level-80-Zeile tippen (nicht auf "Level 80", nicht auf die Belohnungen) ->
 * "Auto battle" -> Haken bei "Open food chests" -> "Start auto battle!" -> 30 Kaempfe abwarten -> "Home" -> wieder
 * Level 80 ... Ein Durchgang kostet 1,65 Mio Nahrung (30 x 55.000); insgesamt hoechstens 40 Mio (portal-nahrung.txt), neue
 * Durchgaenge nur bis zur Uhrzeit in portal-bis.txt.
 *
 * Nie: "Buy extra food for diamonds" (wird ausgeschaltet, falls an), "Finish auto battle" (bricht ab), Store, Watch.
 * Alle Stellen sind am 09.10. abends live aus dem Spiel geschnitten (Bildschirm 1158 x 770).
 */
public final class PortalMode implements GameMode {

    private static final String WO = "Portal";
    static final String T_KARTE = "pt_karte";              // Schild "Portal" auf der Weltkarte
    static final String T_TITEL = "pt_titel";              // "Dark souls in the portal" (Portal-Fenster)
    static final String T_LEADERS = "pt_leaders", T_STORE = "pt_store";   // Knoepfe rechts im Portal-Fenster (25.10.)
    static final String T_LEVEL80 = "pt_level80";          // "Level 80" in der Liste
    static final String T_DIALOG = "pt_dialog_level80";    // Titel "Level 80" im Kampf-Fenster
    static final String T_AUTO = "pt_auto_battle";         // "Auto battle"
    /** "Buy the missing resources?" (Nahrung reicht nicht fuer den ersten Kampf) und dessen roter Knopf "No". */
    static final String T_KAUF_FRAGE = "pt_kauf_frage", T_KAUF_NEIN = "pt_kauf_nein";
    private static final int[] KAUF_NEIN = {683, 490};
    static final String T_EINSTELLUNGEN = "pt_einstellungen"; // "Auto Battle Settings"
    static final String T_START = "pt_start";              // "Start auto battle!"
    static final String T_LAEUFT = "pt_kampf_laeuft";      // "Finish auto battle" -- NUR Erkennung, NIE tippen
    static final String T_PAUSE = "pt_kampf_pause";        // "Finish auto battle" unten zwischen zwei Kaempfen -- NIE tippen
    static final String T_FERTIG = "pt_fertig";            // "Auto battle finished!"
    static final String T_HOME = "pt_home";                // "Home" unter "Auto battle finished!"
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";

    private static final int BREITE = 1158, HOEHE = 770;
    /** Vom Schild "Level 80" aus: rechts neben den Belohnungen, unter "Completed level" (Autor, 09.10.). */
    private static final int ZEILE_DX = 285, ZEILE_DY = 73;
    private static final int[] HAKEN_KISTEN = {281, 367};     // "Open food chests"
    // Autor 17.10. (Bild "Auto Battle Settings"): "immer diese drei Haken ankreuzen, bevor du den Auto Battle startest" --
    // "Use portal spells" und "Continue if the battle is lost" dazu (je 56 px ueber dem Kisten-Haken).
    private static final int[] HAKEN_SPELLS = {281, 255};     // "Use portal spells"
    private static final int[] HAKEN_WEITER = {281, 311};     // "Continue if the battle is lost"
    private static final int[] HAKEN_DIAMANT = {281, 423};    // "Buy extra food for diamonds" -- muss AUS sein
    private static final int[] BEDARF = {410, 530, 350, 30};  // "Food required: 1 650 000"
    private static final int[] FORTSCHRITT = {20, 18, 120, 36}; // "3/30" oben links im Kampf
    private static final int[] X_EINSTELLUNGEN = {915, 90}, X_DIALOG = {949, 110}, X_PORTAL = {1110, 92};
    private static final long BEDARF_STANDARD = 1_650_000L;
    private static final int MAX_ZUEGE = 6;
    /** Das Schild "Portal" unter dem Portal auf der Weltkarte (Autor 23.10.: bleibt gleich, das Bild darueber wechselt). */
    static final String T_KARTE_SCHILD = "pt_karte_schild";

    private static final Path BUDGET_DATEI = Path.of("portal-nahrung.txt");
    private static final long BUDGET_STANDARD = 40_000_000L;

    private int runden, zuege;

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Die kurzen festen Pausen (nach Tipps, beim Zahl-Einstellen, beim
     * Hinsehen) laufen x Faktor (portal-tempo.txt). Das Warten auf den Autokampf, die Werbung und alle Bildwechsel
     * (sleepUntilChanged) bleiben, wie sie sind. Runde = ein geschafftes Level (Aufstieg / Einzelkampf) oder ein
     * abgeschlossener Autokampf. Als Fehler zaehlt jede Warnung zu eigenen Klicks und Bildschirmen -- nicht Niederlagen,
     * leere Nahrung und der Plan (liegen nicht am Tempo).
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("portal-tempo.txt", WO);
    private int runFehler;
    /** Abgeschlossene Runden seit dem Start (fuer die Tempo-Zeile alle 10 Runden; nicht in reset()). */
    private int tempoRunden;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn(WO, text);
    }

    /** Eine Runde ist abgeschlossen: das Tempo lernen (Autor 24.10.). */
    private void tempoRundeFertig() {
        tempoRunden++;
        tempo.rundeFertig(runFehler == 0);
        if (tempoRunden % 10 == 0) {
            Log.info(WO, de.hustlebot.core.Sprache.t("Speed after " + tempoRunden + " rounds: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + tempoRunden + " Runden: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }
    /** Wie oft die Portal-Liste schon nach unten geschoben wurde, um "Level 80" zu finden. */
    private int listenZuege, letzteSichtGroesste;

    /**
     * Die Levelzahl in einem Ausschnitt ("Level 109" -> 109), -1 = nicht lesbar. Live-Test 17.10.: die Bildvorlage
     * "Level 80" passte auf "Level 109" -- ein Bild allein reicht nicht, die Zahl muss stimmen.
     */
    private static int levelZahl(BotContext ctx, int x, int y, int w, int h) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return -1;
        x = Math.max(0, x); y = Math.max(0, y);
        w = Math.min(w, b.getWidth() - x); h = Math.min(h, b.getHeight() - y);
        if (w < 20 || h < 10) return -1;
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(b.getSubimage(x, y, w, h)));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)l\\s*e\\s*v\\s*e\\s*[l1i|]\\s*(\\d{1,3})").matcher(text);
        if (m.find()) return Integer.parseInt(m.group(1));
        java.util.regex.Matcher z = java.util.regex.Pattern.compile("\\b(\\d{2,3})\\b").matcher(text);
        int letzte = -1;
        while (z.find()) letzte = Integer.parseInt(z.group(1));
        return letzte;
    }
    /**
     * Alle sichtbaren Ueberschriften "Level N" der Portal-Liste: Stufe -> Bildzeile (Mitte des Schriftzugs, Bildpunkte).
     * 23.10. live: Die Vorlage "Level 80" fand Titel ab 110 kaum (Liste 111-114, nur "113" erkannt) -- darum die Spalte
     * der Titel (x 300-520) in ueberlappenden Streifen lesen; gezaehlt wird nur ein echtes "Level N".
     */
    private static java.util.TreeMap<Integer, Integer> stufenSichtbar(BotContext ctx) {
        java.util.TreeMap<Integer, Integer> stufen = new java.util.TreeMap<>();
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return stufen;
        int x = px(ctx, 300), w = px(ctx, 220), h = py(ctx, 45);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)l\\s*e\\s*v\\s*e\\s*[l1i|]\\s*(\\d{1,3})");
        for (int y = py(ctx, 60); y + h < b.getHeight() - py(ctx, 40); y += py(ctx, 30)) {
            String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(b.getSubimage(x, y, w, h)));
            // Balken "Start at level 80?" und Banner "Complete level 81 first" sind keine Ueberschriften (Ben 23.10.).
            String klein = text.toLowerCase(java.util.Locale.ROOT);
            if (klein.contains("start at") || klein.contains("complete") || klein.contains("first")) continue;
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) stufen.putIfAbsent(Integer.parseInt(m.group(1)), y + h / 2);
        }
        return stufen;
    }

    private boolean ende;
    /** Nahrung holen (Burg, Tasche) -- laeuft als eingebetteter Nahrungskisten-Modus, null = gerade nicht. */
    private NahrungskistenMode kisten;
    private RunTracker kistenLauf;
    private long letzteKistenUm;
    private long letzterFortschritt, unbekanntSeit, karteUm;
    /** Code-Pruefung 15.10.: wann der Diamanten-Haken zuletzt angetippt wurde (kein zweiter Tipp, bevor er wirkt). */
    private long homeGetipptUm;
    private int stufeGemeldet = -1;
    private long diamantGetipptUm;
    private String stand = "";

    // ==========================================================================================================
    //  PLAN (Autor 23.10.): "Einmal das Video anschauen, dann bist du bei Level 80, dann bis Level 135 spielen. Dann
    //  2 Mio Nahrung fuer Level 80, 2 Mio fuer Level 135, 1,5 Mio fuer Level 130." -- Aufstieg Level fuer Level bis zum
    //  Ziel (oder bis zur zweiten Niederlage), danach die Farm-Liste im Autokampf (30er-Runden; die Rundenzahl aus dem
    //  Nahrungsbedarf je 30 Kaempfe). Fehlt Nahrung in der Tasche: kein Clan-Laden, keine Videos -- pausieren und melden.
    //  Ohne Plan bleibt alles wie vorher (Level 80 im Autokampf).
    // ==========================================================================================================
    private static volatile int zielNaechstes;
    private static volatile List<long[]> farmNaechste = List.of();

    /** Vor dem Start gesetzt (!start portal bis 135 farm 80:2000000,135:2000000): Ziel-Level (0 = kein Aufstieg), Farm-Liste. */
    public static void planSetzen(int ziel, List<long[]> farm) {
        zielNaechstes = Math.max(0, ziel);
        farmNaechste = farm == null ? List.of() : List.copyOf(farm);
        planGesetztUm = System.currentTimeMillis();
    }

    /**
     * Code-Review 23.10.: Wurde der Start abgelehnt (Bot laeuft schon ...), blieb der Plan stehen und ein spaeterer
     * Portal-Start aus der Warteschlange uebernahm ihn still. Darum gilt er nur fuer den Start gleich danach (2 min).
     */
    private static volatile long planGesetztUm;

    // Autor 24.10.: "Clan-Krieg immer setzen, egal welcher Modus -- danach zurueck zur eigentlichen Taetigkeit."
    // Portal wechselt nur zwischen zwei Kaempfen (nach Victory/Home); der Rest des Plans wartet hier auf die Fortsetzung.
    private static volatile int fortsetzungZiel;
    private static volatile List<long[]> fortsetzungFarm = List.of();
    private static volatile long fortsetzungUm, fortsetzungErwartetUm;
    /** Unterbrochen OHNE Plan (alter Level-80-Autokampf): danach einfach weiter, nicht als "fertig" werten. */
    private static volatile boolean fortsetzungOhnePlan;
    private static final long FORTSETZUNG_GILT_MS = 3 * 60 * 60_000L;

    /** Die Warteschlange startet gleich die Fortsetzung nach einem Einschub (Clan-Krieg). */
    public static void fortsetzungErwarten() { fortsetzungErwartetUm = System.currentTimeMillis(); }
    private static final long PLAN_GILT_MS = 120_000L;

    /**
     * Plan aus dem Start-Befehl lesen und setzen; zurueck kommt der Rest ohne die Plan-Woerter. Versteht
     * "bis 135" (auch ziel/to/until) und "farm 80:2mio,135:2mio,130:1,5mio" (Trenner , oder ;, Mengen als Zahl,
     * "2mio", "1,5mio", "500k"). Ohne Plan-Woerter wird der Plan geleert (Portal wie bisher).
     */
    public static String planAusText(String text) {
        int ziel = 0;
        List<long[]> farm = new java.util.ArrayList<>();
        StringBuilder rest = new StringBuilder();
        String[] w = (text == null ? "" : text.trim()).split("\\s+");
        for (int i = 0; i < w.length; i++) {
            String k = w[i].toLowerCase(java.util.Locale.ROOT);
            if (k.matches("bis|ziel|to|until|bislevel") && i + 1 < w.length && w[i + 1].matches("\\d{1,3}")) {
                ziel = Integer.parseInt(w[++i]);
                continue;
            }
            if (k.matches("farm|farmen|danach") && i + 1 < w.length) {
                for (String teil : w[++i].split("[;,](?=\\d{1,3}:)|;")) {
                    String[] lv = teil.split(":");
                    if (lv.length != 2 || !lv[0].trim().matches("\\d{1,3}")) continue;
                    long menge = mengeAus(lv[1]);
                    if (menge > 0) farm.add(new long[]{Integer.parseInt(lv[0].trim()), menge});
                }
                continue;
            }
            rest.append(rest.length() == 0 ? "" : " ").append(w[i]);
        }
        planSetzen(ziel, farm);
        return rest.toString();
    }

    /** "2mio" -> 2 000 000, "1,5mio" -> 1 500 000, "500k" -> 500 000, "2000000" -> 2 000 000; -1 = nicht lesbar. */
    static long mengeAus(String s) {
        String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        long faktor = 1;
        if (t.endsWith("mio") || t.endsWith("m")) {
            faktor = 1_000_000;
            t = t.replaceAll("(mio|m)$", "");
        } else if (t.endsWith("k")) {
            faktor = 1_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            if (faktor > 1) return Math.round(Double.parseDouble(t.replace(',', '.')) * faktor);
            return Long.parseLong(t.replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Fuer den Test: der zuletzt gesetzte Plan (Ziel, Farm-Liste). */
    static int zielGesetzt() { return zielNaechstes; }
    static List<long[]> farmGesetzt() { return farmNaechste; }

    private boolean mitPlan, aufstieg;
    private int ziel;
    private List<long[]> farm = List.of();
    private int farmIdx;
    /**
     * Autor 23.10. abends: "maximal 2 Millionen fuer Level 135 -- du kannst ja weniger Kaempfe einstellen (Minus/Plus)."
     * Kaempfe, die das Budget dieses Farm-Levels noch hergibt (-1 = noch nicht berechnet), und die der laufenden Runde.
     */
    private int farmKaempfeRest = -1, rundeKaempfe, kampfZahlVersuche;
    /** Preis eines Kampfs auf dem Farm-Level (-1 = noch nicht gelesen); eingestellte Kampfzahl (-1 = unbekannt). */
    private long farmProKampf = -1;
    private int eingestellt = -1;
    /** Nach einem Start: die Zahl steht vermutlich noch (eingestellt) -- erst mit "Food required" pruefen. */
    private boolean zahlVermutet;

    /** Naechstes Farm-Level: alles neu lesen und einstellen. */
    private void farmNeu() {
        farmKaempfeRest = -1;
        farmProKampf = -1;
        rundeKaempfe = kampfZahlVersuche = 0;
        eingestellt = -1;
        zahlVermutet = false;
    }

    /** Ein Farm-Level geht nicht (Zahl nicht einstellbar ...): melden, Einstellungen zu, weiter mit dem naechsten. */
    private Outcome farmLevelAuslassen(BotContext ctx, String grund) {
        String text = grund + " -- Level " + farmLevel() + " wird nicht gefarmt, weiter mit der Farm-Liste.";
        warnung(text);
        Log.hinweis("Portal", text);
        farmIdx++;
        farmNeu();
        if (farmLevel() < 0) ende = true;
        tippe(ctx, X_EINSTELLUNGEN, "Einstellungen zu (Level ausgelassen)");
        ctx.sleepUntilChanged(1000);
        return Outcome.ACTED;
    }

    private void tippeMal(BotContext ctx, int[] knopf, int mal, String was) {
        Log.action("Klick", WO + ": " + was + " (" + mal + "x)  [" + knopf[0] + "," + knopf[1] + "]");
        if (laufGerade != null) laufGerade.progress("Portal: " + was);
        for (int i = 0; i < mal; i++) {
            ctx.input().tippeSofort(px(ctx, knopf[0]), py(ctx, knopf[1]));
            ctx.sleep(tempo.ms(120));
        }
        ctx.sleep(tempo.ms(400));
    }
    /** Aufstieg: zuletzt gewonnenes Level, gerade angegriffenes, zuletzt unten gelesenes; Niederlagen in Folge. */
    private int letzterSieg = -1, angriffLevel = -1, offenGelesen = -1, niederlagen, kistenFuer = -1;
    private long angriffUm, werbungUm;
    private int werbungVersuche;
    /** So oft hintereinander, falls das Video nicht frei schaltet: danach liegt es nicht am Video (Level 1-80 noch nie gespielt). */
    static final int WERBUNG_HOECHSTENS = 5;
    /** Zuletzt gelesener Angriffspreis (23.10. live: bei Level 109 war der Preis auf dem Knopf nicht lesbar). */
    private long letzterPreis = -1;
    /** Kisten-Gaenge in Folge, bei denen die Nahrung danach nicht lesbar war. */
    private int kistenUnklar;

    /** Das Level, das gerade im Autokampf gefarmt wird (ohne Plan wie bisher 80; -1 = Liste fertig). */
    private int farmLevel() {
        if (!mitPlan) return 80;
        return farmIdx < farm.size() ? (int) farm.get(farmIdx)[0] : -1;
    }

    /** Code-Review 23.10.: waehrend der Werbung "Start at level 80?" tippt der Motor nichts weg. */
    @Override public boolean inEmpfindlicherPhase() { return werbungUm > 0; }

    @Override public String id() { return "portal"; }

    /**
     * Autor 24.10. (Berichte #X29Z, #ASZP): ein Portal-Kampf dauert ~30 s ohne Klick, der Weg zum Portal am Anfang
     * auch -- Stillstands-Bericht erst nach 90 s.
     */
    @Override
    public long haengerBerichtNachS() {
        return 90;
    }
    @Override public String displayName() { return "Portal"; }

    @Override
    public List<String> requiredTemplates() {
        return List.of(T_KARTE, T_TITEL, T_LEVEL80, T_AUTO, T_EINSTELLUNGEN, T_START);
    }

    @Override
    public void reset() {
        runden = 0;
        zuege = 0;
        listenZuege = 0;
        ende = false;
        letzterFortschritt = unbekanntSeit = karteUm = 0;
        stand = "";
        kisten = null;
        letzteKistenUm = 0;
        boolean frisch = System.currentTimeMillis() - planGesetztUm < PLAN_GILT_MS;
        ziel = frisch ? zielNaechstes : 0;
        farm = frisch ? farmNaechste : List.of();
        zielNaechstes = 0;
        farmNaechste = List.of();
        planGesetztUm = 0;   // Code-Review 24.10.: verbraucht -- ein zweiter Start in den 2 Minuten bekommt ihn nicht noch einmal
        // Wie beim Plan (PLAN_GILT_MS): die Erwartung gilt nur fuer den Start gleich danach -- ein abgelehnter Start
        // darf keinen spaeteren Portal-Start aus der Warteschlange als "nichts mehr uebrig" beenden.
        boolean erwartet = System.currentTimeMillis() - fortsetzungErwartetUm < PLAN_GILT_MS;
        boolean fortsetzung = !frisch && erwartet && fortsetzungUm > 0
                && System.currentTimeMillis() - fortsetzungUm < FORTSETZUNG_GILT_MS;
        if (fortsetzung) {
            ziel = fortsetzungZiel;
            farm = fortsetzungFarm;
            Log.info(WO, "Portal geht nach dem Einschub weiter (Rest des Plans).");
        }
        boolean nichtsMehr = !frisch && erwartet && !fortsetzung && !fortsetzungOhnePlan;
        fortsetzungUm = fortsetzungErwartetUm = 0;
        fortsetzungOhnePlan = false;
        fortsetzungFarm = List.of();
        kampfOffen = false;
        kampfEndeUm = 0;
        mitPlan = ziel > 0 || !farm.isEmpty();
        aufstieg = ziel > 0;
        farmIdx = 0;
        farmNeu();
        letzterSieg = angriffLevel = offenGelesen = kistenFuer = -1;
        if (aufstieg) letzterSieg = siegLesen();
        niederlagen = 0;
        angriffUm = werbungUm = 0;
        werbungVersuche = 0;
        letzterPreis = -1;
        kistenUnklar = 0;
        aufstiegZuege = stehtStill = 0;
        letzterBereich = "";
        farmEinzelkampf = false;
        if (nichtsMehr) {
            // Der Plan war schon vor dem Einschub fertig -- nicht ohne Plan (Level 80) weiterfarmen.
            ende = true;
            Log.info(WO, "Nach dem Einschub ist vom Portal-Plan nichts mehr uebrig.");
        }
        if (mitPlan) {
            StringBuilder b = new StringBuilder();
            for (long[] f : farm) b.append(b.length() == 0 ? "" : ", ").append("Level ").append(f[0]).append(": ").append(zahl(f[1]));
            Log.info(WO, (aufstieg ? "Aufstieg bis Level " + ziel : "Kein Aufstieg") + (farm.isEmpty() ? "" : ", danach farmen: " + b) + ".");
        }
    }

    /** Der Lauf des aktuellen Schritts (fuer Fortschritt aus Hilfsmethoden wie tippeMal). */
    private RunTracker laufGerade;

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        laufGerade = runs;
        long jetzt = System.currentTimeMillis();
        fortsetzungMerken();
        if (!"unbekannt".equals(stand)) unbekanntSeit = 0;

        // --- Nahrung holen (siehe unten): der Nahrungskisten-Modus fuehrt, bis er fertig ist ---
        if (kisten != null) {
            Outcome o = kisten.step(screen, ctx, kistenLauf);
            // Nur echtes Tun zaehlt als Fortschritt -- sonst verdeckte das Warten einen Haenger (17:49, Weltkarte).
            if (o == Outcome.ACTED) runs.progress("Portal: Nahrung holen");
            if (o != Outcome.FINISHED) return o;
            NahrungskistenMode fertig = kisten;
            kisten = null;
            listenZuege = 0;
            stand = "";
            if (mitPlan) {
                // 23.10. live: 1 Mio reicht im Aufstieg nur fuer ~12 Level (5 Minuten) -- leer ist die Tasche erst, wenn die
                // Kisten nichts mehr bringen, nicht nach einer Uhrzeit. Ist die Nahrung danach nicht lesbar: ein zweites Mal.
                long g = fertig.gebracht(), n = fertig.nachher();
                boolean leer = g >= 0 ? g < 100_000 && n < 500_000 : ++kistenUnklar >= 2;
                if (g >= 0) kistenUnklar = 0;
                if (leer) {
                    return nahrungFehlt("Die Tasche gab keine Nahrung mehr her ("
                            + (g >= 0 ? "+" + zahl(g) + ", jetzt " + zahl(n) : "Nahrung nicht lesbar") + ")");
                }
            }
            Log.info(WO, "Nahrungskisten fertig -- weiter im Portal.");
            return Outcome.ACTED;
        }

        // --- Werbung "Start at level 80?" laeuft (Autor 23.10.: "Das Video endet von selbst"): nichts antippen, warten ---
        if (werbungUm > 0) {
            if (jetzt - werbungUm < 150_000 && !(portalFenster(screen, ctx) && jetzt - werbungUm > 8_000 && !startAt80(ctx))) {
                runs.progress("Portal: Werbung laeuft");
                ctx.sleep(1500);
                return Outcome.WAITING;
            }
            werbungUm = 0;
            // 23.10. live: Danach ist Level 80 offen, die Liste aber nicht neu gerollt (Level 80 steht bei y 325) --
            // als waere 79 gewonnen: die Suche nach "letzter Sieg + 1" findet 80 dann ueberall in der Liste.
            letzterSieg = Math.max(letzterSieg, 79);
            Log.info(WO, "Werbung vorbei -- Foto gesichert, weiter im Portal (Level 80 sollte offen sein).");
            fotoSichern(ctx, "nach-werbung");
            return Outcome.ACTED;
        }

        // --- Aufstieg: Sieg oder Niederlage nach "Attack!" ---
        if ((aufstieg || farmEinzelkampf) && angriffLevel > 0 && jetzt - angriffUm > 6 * 60_000L) {
            warnung("Seit 6 Minuten kein Ergebnis fuer Level " + angriffLevel + " -- weiter ueber die Liste.");
            fotoSichern(ctx, "kein-ergebnis-" + angriffLevel);
            angriffLevel = -1;
            kampfZu();
        }
        if ((aufstieg || farmEinzelkampf) && angriffLevel > 0) {
            // "Home" wirkt erst nach der Sieg-Animation (17.10., Level 112): tippen, bis die Liste wieder da ist --
            // gezaehlt wird nur einmal (ergebnisGezaehlt), angriffLevel loescht erst die Liste (aufstiegListe).
            String ergebnis = liesText(ctx, 440, 150, 280, 70).toLowerCase(java.util.Locale.ROOT);
            if (ergebnis.contains("victory")) {
                if (!ergebnisGezaehlt) {
                    ergebnisGezaehlt = true;
                    kampfZu();
                    Log.info(WO, "Level " + angriffLevel + " geschafft.");
                    if (aufstieg) {
                        letzterSieg = angriffLevel;
                        siegMerken(letzterSieg);
                    } else {
                        // Erst in der Liste zuruecksetzen (siehe Portal-Fenster): "Home" wirkt erst nach der Sieg-Animation und
                        // wird bis dahin wiederholt getippt (23.10. live: nach einem Tipp stand der Bot 3 Minuten).
                        Log.info(WO, "Level " + angriffLevel + " ist jetzt einmal von Hand gewonnen -- weiter mit dem Autokampf.");
                    }
                    niederlagen = 0;
                    runs.progress("Portal: Level " + angriffLevel + " geschafft");
                    tempoRundeFertig();
                }
                tippe(ctx, HOME_SIEG, "Home (nie \"Recommence battle\")");
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            if (ergebnis.contains("defeat") || ergebnis.contains("lost") || ergebnis.contains("lose")) {
                if (!ergebnisGezaehlt) {
                    ergebnisGezaehlt = true;
                    kampfZu();
                    niederlagen++;
                    fotoSichern(ctx, "niederlage-" + angriffLevel);
                    Log.warn(WO, "Level " + angriffLevel + " verloren (" + niederlagen + ". Mal).");
                    if (niederlagen >= 2) {
                        // Autor 23.10.: "Nach dem ersten verlorenen Kampf nochmal probieren und danach erst melden."
                        String text = "Level " + angriffLevel + " zweimal verloren -- der Aufstieg haelt an (bisher geschafft: "
                                + (letzterSieg < 0 ? "-" : "Level " + letzterSieg) + "). Mit !weiter versucht er es noch einmal.";
                        niederlagen = 0;
                        tippe(ctx, HOME_SIEG, "Home nach der Niederlage");
                        ctx.sleepUntilChanged(1500);
                        Log.error(WO, text);
                        Log.hinweis("Portal", text);
                        return Outcome.PAUSE;
                    }
                    Log.info(WO, "Noch ein Versuch auf Level " + angriffLevel + ".");
                }
                tippe(ctx, HOME_SIEG, "Home nach der Niederlage");
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            // Der Kampf laeuft (15-30 s): das ist kein Haenger -- sonst ging alle 20 s ein Fehlerbericht an Autor (#K87V).
            // Haengt er wirklich, greift oben die 6-Minuten-Grenze.
            runs.progress("Portal: Kampf auf Level " + angriffLevel + " laeuft");
        }

        // --- Nahrung leer (Live 17.10., 17:30: 2441 Tipps auf "Auto battle" hinter "Buy the missing resources?",
        //     15 Minuten lang): NIE Diamanten -- "No", dann in der Burg Nahrungskisten aus der Tasche, weiter im Portal.
        //     Vor "Auto battle" pruefen: das Fenster liegt halb durchsichtig darueber, die Vorlage passt trotzdem.
        if (sicht(screen, ctx, T_KAUF_FRAGE)) {
            stand = "kauffrage";
            Optional<Matcher.Hit> nein = ctx.matcher().has(T_KAUF_NEIN) ? ctx.find(screen, T_KAUF_NEIN) : Optional.empty();
            if (nein.isPresent()) {
                Log.action("Klick", WO + ": \"Buy the missing resources?\" -- No (nie Diamanten)");
                ctx.input().tippeSofort(nein.get().centerX(), nein.get().centerY());
            } else {
                tippe(ctx, KAUF_NEIN, "\"Buy the missing resources?\" -- No (nie Diamanten)");
            }
            ctx.sleepUntilChanged(1200);
            // Mit Plan entscheidet nach den Kisten, ob sie etwas gebracht haben (oben) -- im Aufstieg sind 1 Mio nach
            // 5 Minuten weg (23.10. live: Pause bei Level 109, obwohl die Tasche voll war).
            if (!mitPlan && letzteKistenUm > 0 && jetzt - letzteKistenUm < 10 * 60_000L) {
                Log.warn(WO, "Schon wieder zu wenig Nahrung, keine 10 Minuten nach den Kisten -- Tasche leer? Portal hoert auf.");
                ende = true;
                return Outcome.ACTED;
            }
            letzteKistenUm = jetzt;
            angriffLevel = -1;   // 23.10. live: der Angriff kam nie zustande -- kein "Kampf laeuft" waehrend der Kisten
            Log.info(WO, "Zu wenig Nahrung fuer den Autokampf -- in der Burg Nahrungskisten aus der Tasche oeffnen.");
            kisten = mitPlan ? new NahrungskistenMode(KISTEN_MENGE) : new NahrungskistenMode();
            kisten.reset();
            kistenLauf = new RunTracker(1);
            runs.progress("Portal: Nahrung holen");
            return Outcome.ACTED;
        }

        // --- Kampf laeuft: nur warten (Fortschritt "3/30" fuers Protokoll) ---
        if (sicht(screen, ctx, T_LAEUFT) || sicht(screen, ctx, T_PAUSE)) {
            stand = "kampf";
            kampfOffen = true;   // auch ein Kampf, der schon lief, als der Bot anfing (Code-Review 24.10.)
            if (jetzt - letzterFortschritt > 30_000) {
                letzterFortschritt = jetzt;
                String f = ArenaLeser.liesTextGross(ctx.frame(), px(ctx, FORTSCHRITT[0]), py(ctx, FORTSCHRITT[1]),
                        px(ctx, FORTSCHRITT[2]), py(ctx, FORTSCHRITT[3]));
                runs.progress("Portal-Kampf " + (f == null ? "" : f.strip()));
            } else {
                // 23.10. live (#QSRB): nur alle 30 s Fortschritt -> alle 20 s ein "Haenger"-Bericht an Autor. Der Autokampf
                // laeuft sichtbar (Finish-Knopf im Bild) -- das ist kein Haenger.
                runs.progress("Portal-Autokampf laeuft");
            }
            ctx.sleep(1500);
            return Outcome.WAITING;
        }

        // --- 30 Kaempfe vorbei: "Home" ---
        // 24.10. 02:47 live: nach der Restrunde (3 Kaempfe) stand "Battles completed: 3/3" -- die Vorlage (bei 30/30
        // aufgenommen) passte nur zu 52 %, der Bot hielt das Bild 3 Minuten fuer unbekannt und brach das Portal ab (Level 80
        // fiel weg). "Home" passt dort zu 100 %: nach dem eigenen Autokampf (kein Handkampf) gilt er als "fertig".
        if (sicht(screen, ctx, T_FERTIG) || (kampfOffen && angriffLevel <= 0 && sicht(screen, ctx, T_HOME))) {
            stand = "fertig";
            if (System.currentTimeMillis() - homeGetipptUm >= 3000) kampfOffen = true;   // erst "Home" schliesst den Kampf
            Optional<Matcher.Hit> home = ctx.matcher().has(T_HOME) ? ctx.find(screen, T_HOME) : Optional.empty();
            // Code-Review 15.10.: Bei traegem Spiel stand "Home" im naechsten Bild noch da -- der Durchgang
            // zaehlte doppelt. Nach einem Tipp 3 s warten.
            if (home.isPresent() && System.currentTimeMillis() - homeGetipptUm < 3000) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            if (home.isPresent()) {
                homeGetipptUm = System.currentTimeMillis();
                kampfZu();
                runden++;
                Log.info(WO, "Autokampf fertig -- Durchgang " + runden + ". Nahrung fuers Portal bisher "
                        + zahl(verbraucht()) + " von " + zahl(budget()) + ".");
                ctx.input().tippeSofort(home.get().centerX(), home.get().centerY());
                // Mit Plan zaehlt der Bot die Kaempfe selbst (farmKaempfeRest) -- der Lauf endet erst, wenn der Plan fertig ist.
                if (!mitPlan) runs.completeRun();
                else {
                    // Code-Review 23.10.: erst hier zaehlen (nicht beim Tipp auf "Start") -- ein verschluckter Start-Tipp
                    // oder ein traeges Bild zaehlte sonst eine Runde, die nie lief.
                    if (aufstieg || rundeKaempfe <= 0) {
                        Log.info(WO, "Eine Autokampf-Runde von vorher ist fertig -- zaehlt fuer kein Farm-Level.");
                    } else {
                        farmKaempfeRest = Math.max(0, farmKaempfeRest - rundeKaempfe);
                        Log.info(WO, "Level " + farmLevel() + ": Runde mit " + rundeKaempfe + " Kaempfen fertig -- das Budget gibt noch "
                                + farmKaempfeRest + " Kaempfe her.");
                        rundeKaempfe = 0;
                    }
                }
                tempoRundeFertig();
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- Einstellungen: Haken pruefen, Nahrung pruefen, starten ---
        if (sicht(screen, ctx, T_EINSTELLUNGEN)) {
            stand = "einstellungen";
            BufferedImage bild = ctx.frame();
            if (gruen(bild, ctx, HAKEN_DIAMANT)) {
                // Code-Pruefung 15.10.: bei traegem Spiel zeigte das naechste Bild den Haken noch an -- ein zweiter Tipp
                // schaltete ihn wieder EIN, und der Autokampf startete mit Diamanten-Kauf. Erst die Wirkung abwarten.
                if (System.currentTimeMillis() - diamantGetipptUm < 3000) {
                    ctx.sleep(tempo.ms(300));
                    return Outcome.WAITING;
                }
                diamantGetipptUm = System.currentTimeMillis();
                warnung("\"Buy extra food for diamonds\" war an -- wird ausgeschaltet (nie Diamanten).");
                tippe(ctx, HAKEN_DIAMANT, "Diamanten-Haken aus");
                ctx.sleep(tempo.ms(400));
                return Outcome.ACTED;
            }
            if (!gruen(bild, ctx, HAKEN_KISTEN)) {
                tippe(ctx, HAKEN_KISTEN, "Haken \"Open food chests\" setzen");
                ctx.sleep(tempo.ms(400));
                return Outcome.ACTED;
            }
            if (!gruen(bild, ctx, HAKEN_SPELLS)) {
                tippe(ctx, HAKEN_SPELLS, "Haken \"Use portal spells\" setzen");
                ctx.sleep(tempo.ms(400));
                return Outcome.ACTED;
            }
            if (!gruen(bild, ctx, HAKEN_WEITER)) {
                tippe(ctx, HAKEN_WEITER, "Haken \"Continue if the battle is lost\" setzen");
                ctx.sleep(tempo.ms(400));
                return Outcome.ACTED;
            }
            long bedarf = bedarfLesen(ctx);
            // Plan (Autor 23.10.): "Anhand der Kosten ausrechnen, wie viele Autokaempfe er machen muss, um die gewuenschte
            // Nahrung zu erreichen" -- Kaempfe = Budget / Preis eines Kampfs, hoechstens 30 je Runde, nie ueber das Budget.
            if (mitPlan && !ende) {
                if (farmLevel() < 0) {
                    ende = true;
                } else {
                    long budgetLevel = farm.get(farmIdx)[1];
                    // Autor 23.10.: Budget je Level fest einhalten -- die Zahl der Kaempfe mit Minus/Plus einstellen. Die Zahl selbst
                    // ist nicht sicher lesbar (eine einzelne "1" liest die Texterkennung nicht, live gemessen), darum: 35x Minus
                    // = sicher 1 Kampf, dann ist "Food required" der Preis EINES Kampfs; danach (Soll - 1)x Plus. Kontrolle:
                    // "Food required" muss Soll x Preis zeigen.
                    // Autor 24.10.: "Merk dir den Preis von 30 Kaempfen -- aendert er sich nicht, nicht jedes Mal auf 1 runter und
                    // wieder auf 30 hoch." Nach einer Runde steht die Zahl meist noch: zeigt "Food required" ein glattes
                    // Vielfaches des bekannten Preises, gilt diese Zahl; nur sonst wie bisher von 1 aus.
                    if (zahlVermutet && farmProKampf > 0) {
                        zahlVermutet = false;
                        long k = Math.round((double) bedarf / farmProKampf);
                        if (bedarf != BEDARF_STANDARD && k >= 1 && k <= MAX_KAEMPFE
                                && Math.abs(bedarf - k * farmProKampf) <= k * farmProKampf / 50) {
                            eingestellt = (int) k;
                            Log.info(WO, "Zahl der Kaempfe steht noch auf " + k + " (" + zahl(bedarf) + " Nahrung) -- nicht neu einstellen.");
                        } else {
                            eingestellt = -1;
                        }
                    }
                    if (eingestellt < 0) {
                        tippeMal(ctx, KAMPF_MINUS, 35, "Zahl der Kaempfe auf 1 (35x Minus)");
                        eingestellt = 1;
                        return Outcome.ACTED;
                    }
                    if (farmProKampf <= 0) {
                        // Code-Review 23.10.: die Rechnung haengt an EINER Lesung -- erst nehmen, wenn ein frisches Bild dieselbe
                        // Zahl zeigt (und es nicht der Ersatzwert ist).
                        ctx.refresh();
                        if (bedarf == BEDARF_STANDARD || bedarfLesen(ctx) != bedarf) {
                            if (++kampfZahlVersuche > 8) return farmLevelAuslassen(ctx, "Der Preis eines Kampfs war nicht lesbar");
                            ctx.sleep(tempo.ms(300));
                            return Outcome.WAITING;
                        }
                        kampfZahlVersuche = 0;
                        farmProKampf = bedarf;
                        farmKaempfeRest = (int) Math.min(100_000, budgetLevel / farmProKampf);
                        Log.info(WO, "Level " + farmLevel() + ": ein Kampf kostet " + zahl(farmProKampf) + " Nahrung -- fuer hoechstens "
                                + zahl(budgetLevel) + " sind das " + farmKaempfeRest + " Kaempfe.");
                    }
                    int soll = Math.min(farmKaempfeRest, MAX_KAEMPFE);
                    if (soll > 0 && eingestellt != soll) {
                        int d = soll - eingestellt;
                        tippeMal(ctx, d > 0 ? KAMPF_PLUS : KAMPF_MINUS, Math.abs(d), "Zahl der Kaempfe " + eingestellt + " -> " + soll);
                        eingestellt = soll;
                        return Outcome.ACTED;
                    }
                    if (soll > 0 && Math.abs(bedarf - soll * farmProKampf) > soll * farmProKampf / 50) {
                        if (++kampfZahlVersuche > 3) {
                            return farmLevelAuslassen(ctx, "Die Zahl der Kaempfe liess sich nicht auf " + soll + " stellen ("
                                    + zahl(bedarf) + " statt " + zahl(soll * farmProKampf) + " Nahrung)");
                        }
                        runFehler++;   // Autor 24.10.: verschluckte Tipps beim Einstellen = Fehler fuers Tempo
                        Log.info(WO, "\"Food required\" zeigt " + zahl(bedarf) + " statt " + zahl(soll * farmProKampf) + " -- noch einmal einstellen.");
                        eingestellt = -1;
                        ctx.sleep(tempo.ms(300));
                        return Outcome.ACTED;
                    }
                    kampfZahlVersuche = 0;
                    rundeKaempfe = soll;
                    if (soll <= 0) {
                        Log.info(WO, "Level " + farmLevel() + " fertig -- das Nahrungs-Budget ist ausgeschoepft.");
                        farmIdx++;
                        farmNeu();
                        if (farmLevel() < 0) {
                            ende = true;
                            Log.hinweis("Portal", "Farm-Liste fertig.");
                        }
                        tippe(ctx, X_EINSTELLUNGEN, "Einstellungen zu (" + (ende ? "fertig" : "naechstes Level") + ")");
                        ctx.sleepUntilChanged(1000);
                        return Outcome.ACTED;
                    }
                }
            }
            // Code-Review 23.10.: Mit Plan gelten dessen Mengen -- nicht die alte Zeitgrenze (portal-bis.txt) und nicht die
            // nie zurueckgesetzte Gesamtsumme (portal-nahrung.txt, 40 Mio), sonst endete die Farm-Liste irgendwann still.
            if (ende || (!mitPlan && (runs.isDone() || zeitVorbei() || verbraucht() + bedarf > budget()))) {
                if (!ende) Log.info(WO, zeitVorbei() ? "Zeit fuers Portal vorbei (" + bis() + ") -- Schluss."
                        : "Nahrungs-Grenze fuers Portal erreicht: " + zahl(verbraucht()) + " + " + zahl(bedarf) + " > "
                        + zahl(budget()) + " -- Schluss.");
                ende = true;
                tippe(ctx, X_EINSTELLUNGEN, "Einstellungen zu (fertig)");
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
            // Unmittelbar vor dem Start noch einmal frisch nachsehen: der Diamanten-Haken MUSS aus sein.
            Mat frischStart = ctx.refresh();
            BufferedImage frischBild = ctx.frame();
            if (frischStart == null || frischBild == null || gruen(frischBild, ctx, HAKEN_DIAMANT)) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            screen = frischStart;
            if (ctx.clickIfVisibleIfKnown(screen, T_START, "Start auto battle! (" + zahl(bedarf) + " Nahrung)")) {
                kampfOffen = true;
                verbrauchtSetzen(verbraucht() + bedarf);
                // Autor 24.10.: die Zahl bleibt im Spiel meist stehen -- beim naechsten Mal erst nachsehen (zahlVermutet).
                zahlVermutet = mitPlan && rundeKaempfe > 0;
                eingestellt = zahlVermutet ? rundeKaempfe : -1;
                letzterFortschritt = jetzt;
                runs.progress("Portal-Autokampf gestartet");
                ctx.sleepUntilChanged(3000);
                return Outcome.ACTED;
            }
        }

        // --- Aufstieg: Kampf-Fenster "Level N" mit gruenem "Attack!" (Preis darunter) ---
        // Autor 23.10.: Jedes Level muss einmal von Hand gewonnen sein, bevor Autokampf geht (auch 1-79 nach der Werbung).
        // Farm-Level mit grauem "Auto battle" (Vorlage passt nicht): einmal "Attack!", danach Autokampf (live: Level 47).
        if (aufstieg || (mitPlan && !ende && farmLevel() > 0 && !sicht(screen, ctx, T_AUTO))) {
            String knopf = liesWeiss(ctx, ATTACK_TEXT[0], ATTACK_TEXT[1], ATTACK_TEXT[2], ATTACK_TEXT[3]).toLowerCase(java.util.Locale.ROOT);
            // Bericht 8QV5 (Level 83): das Fenster stand offen, aber die Texterkennung las "Attack!" 8x nicht. Der Bot hielt es fuer unbekannt und schloss
            // es wieder, 1,5 Minuten lang. Wurde dieses Level gerade geoeffnet und ist der Knopf gruen, gilt er auch ohne Text; der Titel wird gleich noch geprueft.
            boolean attackGruen = aufstieg && offenGelesen > 0 && gruen(ctx.frame(), ctx, ATTACK);
            if (knopf.contains("attack") || knopf.contains("atta") || attackGruen) {
                stand = "angriff";
                int titel = levelZahl(ctx, (int) (ctx.width() * 0.41), (int) (ctx.height() * 0.09), (int) (ctx.width() * 0.18), (int) (ctx.height() * 0.06));
                // 23.10. live (#GY74): nach einem Neustart stand das Fenster von Level 109 schon offen -- der Bot kannte
                // es nicht und wartete. Ein offenes Angriffs-Fenster bis zum Ziel-Level gilt dann als das richtige.
                if (offenGelesen <= 0 && titel > 0 && (aufstieg ? titel <= ziel : titel == farmLevel())) {
                    Log.info(WO, aufstieg ? "Kampf-Fenster \"Level " + titel + "\" ist schon offen -- weiter damit."
                            : "Level " + titel + ": \"Auto battle\" geht noch nicht (nie von Hand gewonnen) -- einmal \"Attack!\".");
                    offenGelesen = titel;
                    if (!aufstieg) farmEinzelkampf = true;
                }
                if (titel != offenGelesen) {
                    warnung("Im Kampf-Fenster steht nicht \"Level " + offenGelesen + "\" (gelesen: " + titel + ") -- Fenster zu.");
                    tippe(ctx, X_DIALOG, "Kampf-Fenster zu");
                    offenGelesen = -1;
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
                long preis = preisAus(knopf, letzterPreis);
                // 23.10. live (Level 109): der Preis war einmal nicht lesbar -> Angriff mit 1.375 Nahrung, "Buy?" -> Pause.
                for (int i = 0; i < 3 && preis <= 0; i++) {
                    ctx.sleep(tempo.ms(150));
                    ctx.refresh();   // Code-Review 23.10.: ohne neues Bild las die Schleife dreimal dasselbe
                    preis = preisAus(liesWeiss(ctx, ATTACK_TEXT[0], ATTACK_TEXT[1], ATTACK_TEXT[2], ATTACK_TEXT[3]), letzterPreis);
                }
                if (preis > 0) {
                    letzterPreis = preis;
                } else if (letzterPreis > 0) {
                    preis = letzterPreis + 2_000;   // je Level ~1.000 teurer
                    Log.info(WO, "Preis fuer Level " + titel + " nicht lesbar -- geschaetzt " + zahl(preis) + ".");
                }
                long nahrung = Nahrungskisten.nahrungLesen(ctx).orElse(-1);
                if (preis > 0 && nahrung >= 0 && nahrung < preis) {
                    if (kistenFuer == titel) {
                        return nahrungFehlt("Fuer Level " + titel + " fehlt Nahrung (" + zahl(nahrung) + " von " + zahl(preis)
                                + ") -- auch nach den Kisten");
                    }
                    kistenFuer = titel;
                    Log.info(WO, "Level " + titel + " kostet " + zahl(preis) + ", da sind nur " + zahl(nahrung)
                            + " -- Nahrungskisten fuer bis zu " + zahl(KISTEN_MENGE) + " oeffnen.");
                    tippe(ctx, X_DIALOG, "Kampf-Fenster zu (Nahrung holen)");
                    offenGelesen = -1;
                    kisten = new NahrungskistenMode(KISTEN_MENGE);
                    kisten.reset();
                    kistenLauf = new RunTracker(1);
                    ctx.sleepUntilChanged(1000);
                    return Outcome.ACTED;
                }
                Log.action("Klick", WO + ": Attack! auf Level " + titel + (preis > 0 ? " (" + zahl(preis) + " Nahrung)" : ""));
                tippe(ctx, ATTACK, "Attack!");
                kampfOffen = true;
                runs.progress("Portal: Kampf auf Level " + titel);
                angriffLevel = titel;
                angriffUm = jetzt;
                ergebnisGezaehlt = false;
                offenGelesen = -1;
                ctx.sleepUntilChanged(2500);
                return Outcome.ACTED;
            }
        }

        // --- Kampf-Fenster "Level 80": Auto battle ---
        if (sicht(screen, ctx, T_AUTO)) {
            stand = "dialog";
            if (aufstieg && !ende) {
                // Code-Review 23.10.: Im Aufstieg ist "Auto battle" grau (Stufe noch nicht geschafft); wurde "Attack!" in
                // einem Bild nicht gelesen, nur das Fenster schliessen und neu ansetzen -- nicht den ganzen Plan beenden.
                offenGelesen = -1;
                tippe(ctx, X_DIALOG, "Kampf-Fenster zu (Attack! nicht gelesen, neu ansetzen)");
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
            if (ende || (!mitPlan && (runs.isDone() || zeitVorbei())) || (mitPlan && farmLevel() < 0)) {
                ende = true;
                tippe(ctx, X_DIALOG, "Kampf-Fenster zu (fertig)");
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
            // Code-Review 15.10.: Die Vorlage "Level 80" wurde nie geprueft. Ob sie im Kampf-Fenster sicher
            // greift, ist noch nicht belegt -- darum vorerst nur melden (einmal je Durchgang), nicht anhalten.
            if (ctx.matcher().has(T_DIALOG) && !sicht(screen, ctx, T_DIALOG) && stufeGemeldet != runden) {
                stufeGemeldet = runden;
                warnung("Im Kampf-Fenster wurde \"Level 80\" nicht erkannt -- bitte pruefen, ob die Stufe stimmt.");
            }
            // Live-Test 17.10.: Level 109 wurde gestartet. Jetzt NUR mit gelesenem Titel "Level 80" weiter, sonst zu.
            int titel = levelZahl(ctx, (int) (ctx.width() * 0.41), (int) (ctx.height() * 0.09), (int) (ctx.width() * 0.18), (int) (ctx.height() * 0.06));
            if (titel != farmLevel()) {
                warnung("Im Kampf-Fenster steht nicht \"Level " + farmLevel() + "\" (gelesen: " + titel + ") -- Fenster zu, nichts gestartet.");
                ctx.input().tippeSofort((int) (ctx.width() * 0.821), (int) (ctx.height() * 0.143));
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            ctx.clickIfVisibleIfKnown(screen, T_AUTO, "Auto battle");
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // --- Portal-Fenster: Level 80 oeffnen ---
        if (portalFenster(screen, ctx)) {
            stand = "portal";
            zuege = 0;
            if (wechselHalten()) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            if (farmEinzelkampf && ergebnisGezaehlt) {   // zurueck in der Liste nach dem einen Handkampf
                farmEinzelkampf = false;
                angriffLevel = -1;
            }
            if (mitPlan && !ende) {
                // Autor 23.10.: "Wenn bei 'Dark souls in the portal' eine 0 steht, ist das Portal geschlossen (bis Freitag)."
                long seelen = darkSouls(ctx, screen);
                if (seelen == 0) {
                    String text = "Das Portal ist geschlossen (Dark souls: 0) -- es oeffnet wieder am Freitag.";
                    Log.error(WO, text);
                    Log.hinweis("Portal", text);
                    ende = true;
                    tippe(ctx, X_PORTAL, "Portal zu (geschlossen)");
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
                if (aufstieg) {
                    Outcome o = aufstiegListe(ctx, screen);
                    if (o != null) return o;
                }
            }
            if (ende || (!mitPlan && (runs.isDone() || zeitVorbei() || verbraucht() + BEDARF_STANDARD > budget())) || farmLevel() < 0) {
                ende = true;
                tippe(ctx, X_PORTAL, "Portal zu (fertig)");
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            int gesucht = farmLevel();
            // Autor 23.10.: Autokampf geht nur auf Stufen, die schon einmal von Hand gewonnen wurden (grauer Knopf sonst).
            if (mitPlan && letzterSieg > 0 && gesucht > letzterSieg) {
                String text = "Level " + gesucht + " ist noch nicht geschafft (bisher bis " + letzterSieg + ") -- Autokampf geht dort "
                        + "nicht, dieser Teil der Farm-Liste wird uebersprungen.";
                Log.warn(WO, text);
                Log.hinweis("Portal", text);
                farmIdx++;
                farmNeu();
                return Outcome.ACTED;
            }
            // 23.10.: jedes Level, nicht nur 80 -- alle Ueberschriften "Level N" auf dem Bild lesen (Texterkennung je Streifen).
            java.util.TreeMap<Integer, Integer> stufen = stufenSichtbar(ctx);
            int kleinste = stufen.isEmpty() ? Integer.MAX_VALUE : stufen.firstKey();
            int groesste = stufen.isEmpty() ? -1 : stufen.lastKey();
            Integer zeile = stufen.get(gesucht);
            if (zeile == null && listenZuege < MAX_LISTEN_ZUEGE) {
                // 17.10.: Autor steht jetzt bei Level 135 -- die Liste oeffnet oben, Level 80 liegt weit darunter.
                // Liste nach unten schieben, bis "Level 80" zu sehen ist (je Zug ~2,5 Zeilen, es geht keine verloren).
                // 23.10.: Sind alle sichtbaren Level kleiner als das gesuchte, in die andere Richtung.
                listenZuege++;
                boolean hoeher = groesste > 0 && groesste < gesucht;
                Log.action("Wischen", WO + ": Liste weiterschieben, \"Level " + gesucht + "\" suchen (" + listenZuege + "/" + MAX_LISTEN_ZUEGE
                        + (groesste > 0 ? ", zu sehen " + kleinste + "-" + groesste : "") + ")");
                // 24.10. Bericht #KTL4: bis zu 80 Zuege a ~1,4 s ohne Fortschritt -> Haenger-Bericht nach 20 s.
                // Nur zaehlen, wenn sich die sichtbaren Level wirklich geaendert haben (steht die Liste, bleibt der Alarm).
                if (groesste > 0 && groesste != letzteSichtGroesste && laufGerade != null)
                    laufGerade.progress("Portal: Liste durchsuchen (" + kleinste + "-" + groesste + ")");
                letzteSichtGroesste = groesste;
                listeZiehen(ctx, hoeher, groesste > 0 ? (hoeher ? gesucht - groesste : kleinste - gesucht) : 0);
                return Outcome.ACTED;
            }
            if (zeile == null) {
                warnung("\"Level " + gesucht + "\" im Portal nicht gefunden -- Schluss.");
                ende = true;
                return Outcome.ACTED;
            }
            // Live-Test 17.10.: nach dem Schieben stand "Level 80" ganz unten am Rand -- der Tipp rechts neben die
            // Belohnungen landete unter der Zeile. Liegt sie im unteren Viertel: erst ein Stueck hochschieben.
            if (zeile > ctx.height() * 0.70 && listenZuege < MAX_LISTEN_ZUEGE) {
                listenZuege++;
                int lx = (int) (ctx.width() * 0.35);
                Log.action("Wischen", WO + ": \"Level " + gesucht + "\" steht am unteren Rand -- Liste ein Stueck hochschieben");
                ctx.input().drag(lx, (int) (ctx.height() * 0.70), lx, (int) (ctx.height() * 0.40));
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
            listenZuege = 0;
            // Autor 17.10. (Portal-Aufstieg): "einfach knapp unter den Text 'Level ...' druecken" -- nicht auf die
            // Belohnungen, nicht auf Name/Zeit.
            int x = px(ctx, ZEILE_TIPP[0]), y = zeile + py(ctx, ZEILE_TIPP[1]);
            Log.action("Klick", WO + ": Level " + gesucht + " oeffnen (rechts neben dem Schriftzug)  [" + x + "," + y + "]");
            ctx.input().tippeSofort(x, y);
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // --- Weltkarte: Portal suchen ---
        // Autor 23.10.: Das Bild des Portals wechselt, das Schild "Portal" darunter nicht -- zuerst das Schild suchen und
        // ueber ihm (auf das Portal) tippen; das alte Bild bleibt als zweiter Weg.
        Optional<Matcher.Hit> schild = ctx.matcher().has(T_KARTE_SCHILD) ? ctx.find(screen, T_KARTE_SCHILD) : Optional.empty();
        Optional<Matcher.Hit> portal = schild.isPresent() || !ctx.matcher().has(T_KARTE) ? Optional.empty() : ctx.find(screen, T_KARTE);
        if (schild.isPresent() || portal.isPresent()) {
            stand = "karte";
            if (ende) return heim(screen, ctx, runs);
            if (wechselHalten()) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            int x = schild.isPresent() ? schild.get().centerX() : portal.get().centerX();
            int y = schild.isPresent() ? Math.max(py(ctx, 30), schild.get().centerY() - py(ctx, 85)) : portal.get().centerY();
            Log.action("Klick", WO + ": Portal auf der Weltkarte" + (schild.isPresent() ? " (ueber dem Schild)" : "") + "  [" + x + "," + y + "]");
            ctx.input().tippeSofort(x, y);
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }
        if (Weltkarte.erkannt(screen, ctx)) {
            stand = "karte";
            if (ende) return heim(screen, ctx, runs);
            if (wechselHalten()) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            if (jetzt - karteUm < 1500) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            if (zuege >= MAX_ZUEGE) {
                warnung("Portal auf der Weltkarte nicht gefunden (" + zuege + " Zuege) -- Schluss.");
                ende = true;
                return heim(screen, ctx, runs);
            }
            zuege++;
            // Ben 23.10.: Das Portal stand links am Rand ("...al" angeschnitten) -- erst zweimal die linke Seite
            // hereinholen (Karte nach rechts ziehen), dann viermal die rechte (zurueck und darueber hinaus). Vorher ging es
            // erst nach rechts und dann nur zurueck zum Anfang -- links vom Start wurde nie gesucht.
            boolean nachLinks = zuege > 2;
            int y = ctx.height() / 2, a = (int) (ctx.width() * 0.72), b = (int) (ctx.width() * 0.28);
            Log.action("Karte", "Weltkarte nach " + (nachLinks ? "links" : "rechts") + " ziehen, um das Portal zu finden (Zug "
                    + zuege + " von " + MAX_ZUEGE + ").");
            if (nachLinks) ctx.input().drag(a, y, b, y);
            else ctx.input().drag(b, y, a, y);
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }

        // --- Burg: Karte oeffnen (oder fertig) ---
        if (ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT)) {
            stand = "burg";
            if (ende) {
                Log.info(WO, "Fertig: " + runden + " Durchgaenge, Nahrung fuers Portal " + zahl(verbraucht()) + " von "
                        + zahl(budget()) + ".");
                if (mitPlan) runs.completeRun();
                return Outcome.FINISHED;
            }
            if (wechselHalten()) {
                ctx.sleep(tempo.ms(300));
                return Outcome.WAITING;
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, "Weltkarte oeffnen (Portal)")
                    || ctx.clickIfVisibleIfKnown(screen, T_MAP, "Weltkarte oeffnen (Portal)")) {
                karteUm = jetzt;
                zuege = 0;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- Stoerfenster (Belohnung, Angebot) ---
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, WO)) return Outcome.ACTED;

        // --- Unbekannt: warten, nach 3 min aufgeben ---
        if (!"unbekannt".equals(stand) || ((aufstieg || farmEinzelkampf) && angriffLevel > 0)) {
            // Code-Review 23.10.: Im Aufstieg ist der laufende Kampf ein "unbekanntes" Bild -- dort gilt die 6-Minuten-
            // Grenze oben, nicht diese 3 Minuten (sonst endete der ganze Plan ohne Meldung).
            stand = "unbekannt";
            unbekanntSeit = jetzt;
        }
        // 24.10. 04:14 live: nach einem abgebrochenen Kathedralen-Start lag ein Fenster mit rotem X im Weg -- Portal wartete
        // 3 Minuten und gab auf (die Kathedrale drueckte es danach sofort weg). Wie dort: unbekannt + kein Kampf + rotes X
        // sichtbar -> wegdruecken ("Rotes X fuehrt zurueck"), hoechstens alle 3 s.
        if (!kampfOffen && angriffLevel <= 0 && jetzt - unbekanntSeit > 8_000 && jetzt - rotesXUm > 3_000
                && ctx.clickIfVisibleIfKnown(screen, "close_x", "Portal: unbekanntes Fenster mit rotem X schliessen")) {
            rotesXUm = jetzt;
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        if (jetzt - unbekanntSeit > 180_000) {
            warnung("Seit 3 Minuten ein unbekanntes Bild -- Schluss.");
            return Outcome.FINISHED;
        }
        ctx.sleep(tempo.ms(500));
        return Outcome.WAITING;
    }

    // --- Aufstieg (Stellen live gemessen 17.10., Fenster 1159x770; doku PORTAL-AUFSTIEG.md) ---
    /**
     * Autor 24.10. (Bilder Level 135/119/118): "immer dieselbe Stelle, nie die Belohnungen" -- rechts neben "Level N",
     * links von "Completed level", auf Hoehe der Ueberschrift bis knapp darunter. {x, Abstand unter der geschaetzten
     * Ueberschrift}; die Schaetzung aus den Lese-Streifen liegt bis 15 Punkte daneben, das Kaestchen ist ~50 hoch.
     * Vorher (405, +20) traf bei Level 135 die Belohnungs-Reihe.
     */
    private static final int[] ZEILE_TIPP = {580, 15};
    // Autokampf-Einstellungen "Number of battles: [-] 30 [+] [MAX]" (live gemessen 23.10., Level 135)
    private static final int[] KAMPF_MINUS = {509, 490};
    private static final int[] KAMPF_PLUS = {651, 490};
    private static final int MAX_KAEMPFE = 30;
    private static final int[] ATTACK = {850, 586};                    // gruenes "Attack!" im Kampf-Fenster
    private static final int[] ATTACK_TEXT = {780, 560, 150, 60};      // "Attack!" + Preis darunter
    private static final int[] HOME_SIEG = {482, 709};                 // "Home" unter "Victory!" -- NIE "Recommence battle"
    private static final int[] WATCH = {683, 641};                     // "Watch" neben "Start at level 80?" (Bild Autor 23.10.)
    private static final int[] START80_TEXT = {150, 598, 340, 44};     // "Start at level 80?"
    /**
     * Autor 23.10. abends (Level 109): "Nahrungskisten oeffnen, bis mein Lager voll ist -- 2 Millionen" (vorher je 1 Mio).
     * Das Ziel ist hoechstens das Lager (NahrungskistenMode: min(Lager, da + Menge)) -- bei kleinerem Lager so voll wie moeglich.
     */
    private static final long KISTEN_MENGE = 2_000_000L;
    private int leerLesen, aufstiegZuege, stehtStill;
    /** Farm-Level noch nie von Hand gewonnen: gerade laeuft der eine Handkampf ("Attack!") davor. */
    private boolean farmEinzelkampf;
    private String letzterBereich = "";

    /**
     * Die Portal-Liste ein Stueck ziehen; hoeher = hoehere Stufen (stehen oben) sichtbar machen. 23.10. live: Zuege ab
     * 25 % der Hoehe blieben bei Level 107-109 haengen, ein Zug aus der Mitte (250 -> 600) ging -- darum 33 % <-> 78 %.
     */
    private void listeZiehen(BotContext ctx, boolean hoeher, int abstand) {
        int lx = (int) (ctx.width() * 0.35), oben = (int) (ctx.height() * 0.33), unten = (int) (ctx.height() * 0.78);
        // Autor 24.10.: von Level 1 bis 135 reichten 30 Zuege (je ~2,5 Stufen) nicht -- weit weg zwei Zuege auf einmal,
        // ab 60 Stufen Abstand ("staerker scrollen") vier.
        int mal = abstand >= 60 ? 4 : abstand > 10 ? 2 : 1;
        for (int i = 0; i < mal; i++) {
            if (i > 0) ctx.sleep(tempo.ms(250));
            if (hoeher) ctx.input().drag(lx, oben, lx, unten);
            else ctx.input().drag(lx, unten, lx, oben);
        }
        ctx.sleepUntilChanged(800);
    }
    /** So oft hoechstens die Portal-Liste schieben (Autor 24.10.: "deutlich oefter", Level 1 bis 135). */
    private static final int MAX_LISTEN_ZUEGE = 80;
    private boolean ergebnisGezaehlt;
    /** Ein Kampf/Autokampf laeuft (zwischen "Attack!"/"Start" und dem Ergebnis) -- dann kein Wechsel. */
    private boolean kampfOffen;
    private long kampfEndeUm;
    /** Wann der Motor zuletzt nach einem Wechsel gefragt hat (er fragt nur, solange einer ansteht). */
    private long wechselGefragtUm;
    /** Wann zuletzt ein unbekanntes Fenster mit dem roten X geschlossen wurde. */
    private long rotesXUm;

    /** Fuer die Warteschlange: laeuft gerade ein Kampf (der Lauf zaehlt danach noch einen Durchgang)? */
    public boolean kampfLaeuft() { return kampfOffen; }

    /** Draussen in Liste, Karte oder Burg -- dort darf ein anderer Modus anfangen (kein Fenster offen). */
    private boolean sichererStand() {
        return "portal".equals(stand) || "karte".equals(stand) || "burg".equals(stand);
    }

    /**
     * Code-Review 24.10.: Steht ein Wechsel an, an der sicheren Stelle nichts mehr antippen (kein Level, kein Portal, keine
     * Karte) -- sonst ginge der Einschub mit offenem Fenster los. Der Motor fragt im naechsten Durchgang wieder.
     */
    private boolean wechselHalten() {
        return System.currentTimeMillis() - wechselGefragtUm < 3000 && !kampfOffen && kisten == null && !inEmpfindlicherPhase();
    }

    private void kampfZu() {
        if (kampfOffen) kampfEndeUm = System.currentTimeMillis();
        kampfOffen = false;
    }

    /**
     * Einschub (Clan-Krieg ...): nur zwischen zwei Kaempfen, ein paar Sekunden nach Victory/Home, nie waehrend der
     * Werbung. Mit Plan bleibt der Rest (Ziel, Farm-Liste mit dem uebrigen Budget) fuer die Fortsetzung stehen.
     */
    @Override
    public boolean wechselJetzt() {
        wechselGefragtUm = System.currentTimeMillis();
        if (kampfOffen || kisten != null || inEmpfindlicherPhase() || !sichererStand()
                || System.currentTimeMillis() - kampfEndeUm < 4000) return false;
        fortsetzungMerken();
        Log.info(WO, "Zwischen zwei Kaempfen -- Portal macht Pause fuer den Einschub und geht danach weiter.");
        return true;
    }

    /**
     * Stand fuer die Fortsetzung nach einem Einschub festhalten -- bei jedem Schritt, damit auch ein erzwungener Wechsel
     * (Motor nach 15 Minuten) den Rest des Plans kennt (Code-Review 24.10.). Uebernommen wird er nur, wenn die
     * Warteschlange gleich danach die Fortsetzung startet (fortsetzungErwarten).
     */
    private void fortsetzungMerken() {
        // Code-Review 24.10.: das letzte Farm-Level uebersprungen (farmIdx am Ende) zaehlt wie "fertig".
        if (mitPlan && !ende && (aufstieg || farmIdx < farm.size())) {
            List<long[]> rest = new java.util.ArrayList<>();
            for (int i = farmIdx; i < farm.size(); i++) {
                long budget = farm.get(i)[1];
                // Code-Review 24.10.: eine laufende Runde ist schon bezahlt (Start), zaehlt aber erst bei "Home".
                int offen = kampfOffen && !aufstieg ? Math.max(0, rundeKaempfe) : 0;
                if (i == farmIdx && farmKaempfeRest >= 0 && farmProKampf > 0) budget = (long) Math.max(0, farmKaempfeRest - offen) * farmProKampf;
                rest.add(new long[]{farm.get(i)[0], budget});
            }
            fortsetzungZiel = aufstieg ? ziel : 0;
            fortsetzungFarm = List.copyOf(rest);
            fortsetzungUm = System.currentTimeMillis();
            fortsetzungOhnePlan = false;
        } else {
            fortsetzungUm = 0;
            fortsetzungOhnePlan = !mitPlan && !ende;
        }
    }

    /** Portal-Liste im Aufstieg: Werbung fuer Level 80, unterstes offenes Level lesen und antippen. null = wie sonst weiter. */
    private Outcome aufstiegListe(BotContext ctx, Mat screen) {
        angriffLevel = -1;   // zurueck in der Liste: der letzte Kampf ist erledigt
        // Autor 31.10.: "Das Video kann man so oft anschauen, wie man will. Wenn es nicht funktioniert, schauen wir es nochmal an."
        if (werbungVersuche < WERBUNG_HOECHSTENS && ziel >= 80 && startAt80(ctx)) {
            werbungVersuche++;
            werbungUm = System.currentTimeMillis();
            fotoSichern(ctx, "vor-werbung");
            Log.info(WO, "\"Start at level 80?\" -- Werbung ansehen (Watch), danach ist Level 80 offen.");
            tippe(ctx, WATCH, "Watch (Werbung fuer Level 80)");
            ctx.sleep(3000);
            return Outcome.ACTED;
        }
        if (letzterSieg > 0) {
            // Code-Review 23.10.: Am Ende der Liste (17.10. ab Level 133) und direkt nach der Werbung steht die offene
            // Stufe NICHT unten -- darum "Level letzter Sieg + 1" in der ganzen sichtbaren Liste suchen.
            int gesucht = letzterSieg + 1;
            if (gesucht > ziel) return aufstiegFertig("Ziel-Level " + ziel + " erreicht.");
            java.util.TreeMap<Integer, Integer> stufen = stufenSichtbar(ctx);
            int kleinste = stufen.isEmpty() ? Integer.MAX_VALUE : stufen.firstKey();
            int groesste = stufen.isEmpty() ? -1 : stufen.lastKey();
            Integer zeile = stufen.get(gesucht);
            if (zeile != null && zeile > ctx.height() * 0.85) {
                // Ganz unten am Rand: der Tipp darunter laege ausserhalb der Zeile -- erst ein Stueck hochschieben.
                zeile = null;
                groesste = gesucht + 1;   // -> Liste nach oben ziehen
            }
            if (zeile != null) {
                leerLesen = 0;
                aufstiegZuege = 0;
                stehtStill = 0;
                letzterBereich = "";
                offenGelesen = gesucht;
                int x = px(ctx, ZEILE_TIPP[0]), y = zeile + py(ctx, ZEILE_TIPP[1]);
                Log.action("Klick", WO + ": Level " + gesucht + " oeffnen (rechts neben dem Schriftzug)  [" + x + "," + y + "]");
                ctx.input().tippeSofort(x, y);
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            if (groesste < 0) {
                if (++leerLesen > 8) {
                    fotoSichern(ctx, "level-unlesbar");
                    return aufstiegFertig("In der Liste war kein Level lesbar.");
                }
                ctx.sleep(tempo.ms(500));
                return Outcome.WAITING;
            }
            // 23.10. live: Nach einer Farm-Runde stand die Liste bei Level 80 -- 6 kleine Zuege reichten nicht bis 133.
            // Bis zu 30 Zuege; Ende der Liste erst, wenn sich das Sichtbare dreimal nicht mehr bewegt.
            String bereich = kleinste + "-" + groesste;
            stehtStill = bereich.equals(letzterBereich) ? stehtStill + 1 : 0;
            letzterBereich = bereich;
            if (aufstiegZuege < MAX_LISTEN_ZUEGE && stehtStill < 3) {
                // Hoehere Stufen stehen oben: sind alle sichtbaren kleiner als die gesuchte, die Liste nach unten ziehen.
                aufstiegZuege++;
                Log.action("Wischen", WO + ": \"Level " + gesucht + "\" suchen (zu sehen " + bereich + ", " + aufstiegZuege + "/" + MAX_LISTEN_ZUEGE + ")");
                listeZiehen(ctx, groesste < gesucht, groesste < gesucht ? gesucht - groesste : kleinste - gesucht);
                return Outcome.ACTED;
            }
            fotoSichern(ctx, "level-" + gesucht + "-fehlt");
            return aufstiegFertig("Ende der Liste -- Level " + letzterSieg + " war das letzte.");
        }
        // Erste Stufe (noch kein Sieg in diesem Lauf). Ben 23.10. (#9ZQZ): unten lag ein Banner "Make way for the
        // strongest! Complete level 81 first ..." -- die offene Stufe stand hoeher, und an der festen Stelle (y 530) las
        // der Bot eine Belohnungszahl. Jetzt: steht das Banner da, nennt es die Stufe selbst; sonst die unterste
        // sichtbare Ueberschrift "Level N" (Streifen-Lesen ueberspringt die Balken "Start at level 80?" / "Complete").
        int n = bannerLevel(ctx);
        java.util.TreeMap<Integer, Integer> stufen = stufenSichtbar(ctx);
        if (n <= 0) {
            int tiefste = -1;
            for (var e : stufen.entrySet()) {
                if (e.getValue() > tiefste) { tiefste = e.getValue(); n = e.getKey(); }
            }
        }
        if (n <= 0) {
            if (++leerLesen > 8) {
                fotoSichern(ctx, "level-unlesbar");
                return aufstiegFertig("Das unterste Level war nicht lesbar.");
            }
            ctx.sleep(tempo.ms(500));
            return Outcome.WAITING;
        }
        leerLesen = 0;
        if (n > ziel) return aufstiegFertig("Ziel-Level " + ziel + " erreicht.");
        Integer zeile = stufen.get(n);
        if (zeile == null) {
            // Die Stufe aus dem Banner ist (noch) nicht als Ueberschrift zu sehen: ab hier wie nach einem Sieg suchen.
            letzterSieg = n - 1;
            return Outcome.ACTED;
        }
        offenGelesen = n;
        int x = px(ctx, ZEILE_TIPP[0]), y = zeile + py(ctx, ZEILE_TIPP[1]);
        Log.action("Klick", WO + ": Level " + n + " oeffnen (rechts neben dem Schriftzug)  [" + x + "," + y + "]");
        ctx.input().tippeSofort(x, y);
        ctx.sleepUntilChanged(1500);
        return Outcome.ACTED;
    }

    /** "Complete level 81 first to unlock the next levels" (Banner unten in der Liste, Ben 23.10.) -> 81, sonst -1. */
    private static int bannerLevel(BotContext ctx) {
        String t = liesText(ctx, 40, 590, 760, 110);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)complete\\s*level\\s*(\\d{1,3})").matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private Outcome aufstiegFertig(String grund) {
        aufstieg = false;
        String text = "Portal-Aufstieg fertig: " + grund + (farm.isEmpty() ? "" : " Weiter mit der Farm-Liste.");
        Log.info(WO, text);
        Log.hinweis("Portal", text);
        if (farm.isEmpty()) ende = true;
        return Outcome.ACTED;
    }

    /** Nahrung reicht nicht und die Tasche gibt nichts mehr her: kein Clan-Laden, keine Videos -- pausieren, melden. */
    private Outcome nahrungFehlt(String grund) {
        String text = grund + ". Nicht genug Nahrung in der Tasche -- der Bot pausiert (kein Clan-Laden, keine Videos). "
                + "Mit !weiter geht es weiter.";
        Log.error(WO, text);
        Log.hinweis("Portal", text);
        kistenFuer = -1;
        letzteKistenUm = 0;
        return Outcome.PAUSE;
    }

    /** Steht unten "Start at level 80?" (Werbung schaltet Level 80 frei)? */
    private static boolean startAt80(BotContext ctx) {
        String t = liesText(ctx, START80_TEXT[0], START80_TEXT[1], START80_TEXT[2], START80_TEXT[3]).toLowerCase(java.util.Locale.ROOT);
        return t.contains("start at") || t.contains("level 80");
    }

    /** Die Zahl hinter "Dark souls in the portal:" -- -1 = nicht lesbar. */
    private long darkSouls(BotContext ctx, Mat screen) {
        Optional<Matcher.Hit> t = ctx.find(screen, T_TITEL);
        if (t.isEmpty()) return -1;
        Matcher.Hit h = t.get();
        // Ganze Zeile lesen ("Dark souls in the portal: 991 642 603", live 23.10.) und die Ziffern hinter "portal" nehmen.
        String text = liesText(ctx, 230, 62, 420, 36);
        String nach = text.replaceAll("(?is)^.*port[a-z]*", "");
        if (nach.equals(text)) nach = text.contains(":") ? text.substring(text.lastIndexOf(':') + 1) : text;
        String z = nach.replaceAll("[^0-9]", "");
        if (z.isEmpty() || z.length() > 12) return -1;
        try {
            return Long.parseLong(z);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Text in einem Ausschnitt (Koordinaten wie gemessen bei 1159x770), doppelt gross gelesen. */
    private static String liesText(BotContext ctx, int x, int y, int w, int h) {
        BufferedImage b = ausschnitt(ctx, px(ctx, x), py(ctx, y), px(ctx, w), py(ctx, h), 2, false);
        return b == null ? "" : String.join(" ", de.hustlebot.core.Texterkennung.lies(b)).trim();
    }

    /** Nur die weisse Schrift (Zahlen auf Knoepfen), schwarz auf weiss und dreifach gross -- die Texterkennung liest sie sonst nicht. */
    private static String liesWeiss(BotContext ctx, int x, int y, int w, int h) {
        return liesWeissPx(ctx, px(ctx, x), py(ctx, y), px(ctx, w), py(ctx, h));
    }

    private static String liesWeissPx(BotContext ctx, int x, int y, int w, int h) {
        BufferedImage b = ausschnitt(ctx, x, y, w, h, 3, true);
        // Zeilen getrennt lassen: "Attack!" und der Preis darunter (preisAus nimmt nur die Zeilen nach dem Wort).
        return b == null ? "" : String.join("\n", de.hustlebot.core.Texterkennung.lies(b)).trim();
    }

    /**
     * Der Preis unter "Attack!" -- nur aus den Zeilen nach dem Wort (Doku 17.10.: "!" wurde als 1 mitgelesen, 181 100
     * statt 81 100). Liegt er weit neben dem letzten Preis (je Level ~1.000 mehr), gilt er als falsch gelesen: -1.
     */
    private static long preisAus(String knopf, long letzter) {
        String[] zeilen = knopf.split("\n");
        long v = -1;
        for (int i = zeilen.length - 1; i >= 0 && v <= 0; i--) {
            if (zeilen[i].toLowerCase(java.util.Locale.ROOT).contains("atta")) {
                v = zahlAus(zeilen[i].replaceFirst("(?is)^.*?atta\\S*", ""), 1_000, 5_000_000);
                break;
            }
            v = zahlAus(zeilen[i], 1_000, 5_000_000);
        }
        if (v > 0 && letzter > 0 && (v > letzter + 20_000 || v < letzter - 5_000)) return -1;
        return v;
    }

    private static BufferedImage ausschnitt(BotContext ctx, int x, int y, int w, int h, int faktor, boolean weiss) {
        BufferedImage f = ctx.frame();
        if (f == null) return null;
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, f.getWidth() - x);
        h = Math.min(h, f.getHeight() - y);
        if (w < 8 || h < 8) return null;
        BufferedImage g = new BufferedImage(w * faktor, h * faktor, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D gr = g.createGraphics();
        gr.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gr.drawImage(f.getSubimage(x, y, w, h), 0, 0, w * faktor, h * faktor, null);
        gr.dispose();
        if (weiss) {
            for (int j = 0; j < g.getHeight(); j++) {
                for (int i = 0; i < g.getWidth(); i++) {
                    int p = g.getRGB(i, j), r = p >> 16 & 255, gn = p >> 8 & 255, bl = p & 255;
                    g.setRGB(i, j, (r + gn + bl) / 3 >= 200 && Math.abs(r - gn) < 40 ? 0x000000 : 0xFFFFFF);
                }
            }
        }
        return g;
    }

    /** Die Ziffern eines Texts als Zahl, wenn sie im erlaubten Bereich liegt; sonst -1. */
    private static long zahlAus(String text, long min, long max) {
        String z = text == null ? "" : text.replaceAll("[^0-9]", "");
        if (z.isEmpty() || z.length() > 10) return -1;
        long v = Long.parseLong(z);
        return v >= min && v <= max ? v : -1;
    }

    /** Ein Foto fuer Autor/die Fehlersuche: logs\portal\<name>-<Uhrzeit>.png. */
    private static void fotoSichern(BotContext ctx, String name) {
        try {
            BufferedImage f = ctx.frame();
            if (f == null) return;
            Path ordner = de.hustlebot.core.Ordner.bei("logs").resolve("portal");
            String zeit = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            de.hustlebot.core.Beleg.schreiben(f, "png", ordner.resolve(name + "-" + zeit + ".png").toFile());
        } catch (Exception e) {
            Log.debug(WO, "Foto nicht gesichert: " + e.getMessage());
        }
    }

    private Outcome heim(Mat screen, BotContext ctx, RunTracker runs) {
        Log.info(WO, "Fertig: " + runden + " Durchgaenge -- zurueck in die Burg.");
        tippe(ctx, new int[]{81, 697}, "Home unten links");
        ctx.sleepUntilChanged(1500);
        return Outcome.ACTED;
    }

    /**
     * Ist das Portal-Fenster offen? Die Ueberschrift "Dark souls in the portal: 129 275 380" wird mit der Zahl kleiner
     * (Autor 25.10.: am Anfang der Woche 1 Milliarde) -- die alte Vorlage passte nur noch zu 0,72 und der Bot schloss das
     * Portal als fremdes Fenster. Darum zaehlen auch die Knoepfe "Leaders" UND "Store" rechts (beide zusammen).
     */
    private boolean portalFenster(Mat screen, BotContext ctx) {
        if (sicht(screen, ctx, T_TITEL)) return true;
        return ctx.matcher().has(T_LEADERS) && ctx.matcher().has(T_STORE)
                && sicht(screen, ctx, T_LEADERS) && sicht(screen, ctx, T_STORE);
    }

    private boolean sicht(Mat screen, BotContext ctx, String vorlage) {
        return ctx.matcher().has(vorlage) && ctx.find(screen, vorlage).isPresent();
    }

    /** Gruener Haken im Kreis? Gemessen 09.10.: Haken (115,219,23), leerer Kreis (83,66,71). */
    private static boolean gruen(BufferedImage b, BotContext ctx, int[] mitte) {
        if (b == null) return false;
        int cx = px(ctx, mitte[0]), cy = py(ctx, mitte[1]), n = 0;
        for (int y = cy - 12; y <= cy + 12; y += 2) {
            for (int x = cx - 12; x <= cx + 12; x += 2) {
                if (x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) continue;
                int p = b.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                if (g > 150 && g - r > 60 && g - bl > 100) n++;
            }
        }
        return n >= 8;
    }

    private static long bedarfLesen(BotContext ctx) {
        String t = ArenaLeser.liesTextGross(ctx.frame(), px(ctx, BEDARF[0]), py(ctx, BEDARF[1]), px(ctx, BEDARF[2]), py(ctx, BEDARF[3]));
        String z = t == null ? "" : t.replaceAll("[^0-9]", "");
        try {
            long v = z.isEmpty() ? -1 : Long.parseLong(z);
            // 23.10.: niedrige Level kosten weniger (Level 1: 45 000 fuer 30 Kaempfe, Bild Autor); seit dem Einstellen der
            // Kampfzahl wird auch der Preis EINES Kampfs gelesen (Level 1: 1 500) -- ab 500 gilt die Zahl.
            return v >= 500 && v <= 20_000_000 ? v : BEDARF_STANDARD;
        } catch (NumberFormatException e) {
            return BEDARF_STANDARD;
        }
    }

    private static void tippe(BotContext ctx, int[] p, String was) {
        Log.action("Klick", WO + ": " + was + "  [" + p[0] + "," + p[1] + "]");
        ctx.input().tippeSofort(px(ctx, p[0]), py(ctx, p[1]));
    }

    private static int px(BotContext ctx, int x) {
        int w = ctx.width();
        return w > 0 ? (int) Math.round(x * (double) w / BREITE) : x;
    }

    private static int py(BotContext ctx, int y) {
        int h = ctx.height();
        return h > 0 ? (int) Math.round(y * (double) h / HOEHE) : y;
    }

    // --- Zeitgrenze (Autor, 09.10. 23:00: "bis 13:30 Portal spielen; laeuft ein Durchgang darueber, den noch fertig
    //     machen und dann den Bot stoppen") -- portal-bis.txt, z. B. "2026-10-10T13:30". Ohne Datei: keine Zeitgrenze.

    private static final Path BIS_DATEI = Path.of("portal-bis.txt");

    static String bis() {
        try {
            return Files.exists(BIS_DATEI) ? de.hustlebot.core.Dateien.text(BIS_DATEI).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Ab dieser Uhrzeit startet kein neuer Durchgang mehr (ein laufender wird fertig gespielt). */
    // 23.10. live: Nach einem Neustart (Maus bewegt, neue Fassung) kannte der Bot den letzten Sieg nicht und las die
    // unterste Stufe -- am Ende der Liste (ab 133) steht dort aber eine geschaffte. Darum den letzten Sieg merken
    // (portal-stand.txt: "Level Zeitpunkt"); er gilt bis zum naechsten Portal-Reset (Autor: jeden Freitag).
    private static final Path STAND_DATEI = Path.of("portal-stand.txt");

    private static void siegMerken(int level) {
        try {
            Files.writeString(STAND_DATEI, level + " " + System.currentTimeMillis(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug(WO, "portal-stand.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /** Letzter Sieg dieser Portal-Woche (seit Freitag 0 Uhr), sonst -1. */
    static int siegLesen() {
        try {
            if (!Files.exists(STAND_DATEI)) return -1;
            String[] t = de.hustlebot.core.Dateien.text(STAND_DATEI).strip().split("\\s+");
            int level = Integer.parseInt(t[0]);
            long um = Long.parseLong(t[1]);
            java.time.ZonedDateTime jetzt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Berlin"));
            java.time.ZonedDateTime freitag = jetzt.toLocalDate()
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.FRIDAY))
                    .atStartOfDay(jetzt.getZone());
            if (um < freitag.toInstant().toEpochMilli()) return -1;
            Log.info(WO, "Letzter Sieg diese Woche: Level " + level + " (portal-stand.txt) -- weiter mit Level " + (level + 1) + ".");
            return level;
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean zeitVorbei() {
        String b = bis();
        if (b.isEmpty()) return false;
        try {
            return !de.hustlebot.core.Zeitzone.jetzt().isBefore(java.time.LocalDateTime.parse(b));
        } catch (Exception e) {
            Log.debug(WO, BIS_DATEI + " nicht lesbar: " + b);
            return false;
        }
    }

    // --- Nahrungs-Budget (Autor: "fuers Portal maximal 40 Millionen") ---

    static long budget() {
        return lese("budget", BUDGET_STANDARD);
    }

    static long verbraucht() {
        return lese("verbraucht", 0);
    }

    private static void verbrauchtSetzen(long v) {
        try {
            Files.writeString(BUDGET_DATEI, "# Portal: Nahrung insgesamt (Autor, 09.10.: hoechstens 40 Mio)\nbudget=" + budget()
                    + "\nverbraucht=" + v + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn(WO, BUDGET_DATEI + " nicht geschrieben: " + e.getMessage());
        }
    }

    private static long lese(String schluessel, long standard) {
        try {
            if (!Files.exists(BUDGET_DATEI)) return standard;
            for (String z : de.hustlebot.core.Dateien.zeilen(BUDGET_DATEI)) {
                z = z.trim();
                if (z.startsWith(schluessel + "=")) return Long.parseLong(z.substring(schluessel.length() + 1).trim());
            }
        } catch (Exception e) {
            Log.debug(WO, BUDGET_DATEI + " nicht lesbar: " + e.getMessage());
        }
        return standard;
    }

    private static String zahl(long v) {
        return String.format(java.util.Locale.GERMANY, "%,d", v);
    }
}
