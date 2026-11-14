package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;

import de.hustlebot.core.Turmplan;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import de.hustlebot.core.Sprache;

/**
 * Der Titanen-Turm ("Tower"), Autor 27.09. mit 12 Bildern.
 *
 * DER WEG:
 *   Burg -> Weltkarte -> "Tower" (dieselbe Stelle wie fuers Kolosseum)
 *   -> "Select an activity": diesmal "Tower", nicht "Colosseum"
 *   -> "Select the difficulty level": Easy, dann Hard, dann Very Hard
 *   -> im Turm unten "Auto" -> "Auto Battle Settings": "Reach the level" auf
 *      Autors Hoechstwert stellen (Minus), "To squad selection"
 *   -> Truppenfenster: "Deploy the best", "Start auto battle!"
 *   -> bis zu 30 Kaempfe laufen von selbst ("Battles finished: 1/30"), der
 *      Bot fasst NICHTS an
 *   -> "Auto battle finished!": "Home" -> zurueck zu den drei Schwierigkeiten
 *   -> fertig ist ein Turm, wenn dort "30/30" steht (oder Autors Hoechstwert
 *      erreicht ist)
 *
 * VERLOREN ("You didn't get your rewards"): "Home" fuehrt zurueck in den Turm;
 * das Level wird noch dreimal versucht. Klappt es dann nicht, hoert der Bot mit
 * dem ganzen Turm auf (Autor) und macht mit dem eigentlichen Modus weiter.
 *
 * Gedrueckt wird nur, was gesehen wurde. "Finish auto battle" wird nie
 * gedrueckt. Waehrend die Kaempfe laufen, wird nur gewartet.
 */
public final class TitanTurmMode implements GameMode {

    /** Autor 18.10.: im Titanen-Turm kein Stillstands-Bericht -- dort muss der Bot oft lange nichts tun. */
    @Override
    public long haengerBerichtNachS() {
        return Long.MAX_VALUE;
    }

    /** Wann zuletzt nach Werbe-/Belohnungsfenstern gesehen wurde (hoechstens jede Sekunde). */
    private long stoerGeprueftUm = 0;

    // --- Vorlagen (Autor nimmt sie mit Capture-Images.exe auf, Gruppe TITANEN-TURM) ---
    static final String T_KARTE_TURM = "ko_tower";          // "Tower" auf der Weltkarte (gibt es schon)
    static final String T_AKTIVITAET = "tt_aktivitaet";      // "Select an activity": der Turm links
    static final String T_AKTIVITAET_TITEL = "tt_aktivitaet_titel";
    static final String T_TITEL = "tt_titel";                // "Select the difficulty level"
    static final String T_EASY = "tt_easy";
    static final String T_HARD = "tt_hard";
    static final String T_VERYHARD = "tt_veryhard";
    static final String T_VOLL = "tt_voll";                  // gruener Balken "30/30"
    static final String T_LISTE_HOME = "tt_liste_home";      // "Home" unten links in der Auswahl
    static final String T_FLOOR = "tt_floor";                // das Wort "Floor" im Turm
    static final String T_AUTO = "tt_auto";                  // "Auto" unten im Turm
    static final String T_TO_BATTLE = "tt_to_battle";        // "To battle!" (nur zum Erkennen)
    static final String T_BACK = "tt_back";                  // "Back" unten links im Turm
    static final String T_AUTO_TITEL = "tt_auto_titel";      // "Auto Battle Settings"
    static final String T_MINUS = "tt_minus";
    static final String T_ZUR_TRUPPE = "tt_zur_truppe";      // "To squad selection"
    static final String T_DEPLOY = "tt_deploy_best";
    static final String T_START = "tt_start";                // "Start auto battle!"
    static final String T_LAEUFT = "tt_laeuft";              // "Battles finished:"
    static final String T_NAECHSTER = "tt_naechster";        // "Next battle in"
    static final String T_FERTIG = "tt_fertig";              // "Auto battle finished!" (Sieg und Niederlage)
    static final String T_VERLOREN = "tt_verloren";          // "You didn't get your rewards."
    static final String T_ERGEBNIS_HOME = "tt_home";         // oranges "Home" im Ergebnis
    private static final String T_X = "close_x";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";

    private static final int VERSUCHE_JE_LEVEL = 4;          // einmal + dreimal nachgeholt (Autor)
    private static final long LAUF_MAX_MS = 30 * 60_000L;    // 30 Kaempfe dauern keine halbe Stunde
    private static final long GESAMT_MAX_MS = 100 * 60_000L;
    private static final int MAX_BLIND = 60;
    private static final int MAX_WISCHER = 12;

    // --- Zustand eines Besuchs ---------------------------------------------
    private String turm;                 // der gerade gespielte Turm (easy/hard/veryhard)
    private int suchBlicke;              // so oft schon nach einem Turm-Knopf gesucht, der nicht zu sehen war
    private static final int SUCHBLICKE = 6;
    private final Set<String> erledigt = new HashSet<>();
    private int niederlagen;
    private boolean aufgeben, fertig, autoLaeuft, deployGedrueckt, ergebnisGezaehlt;
    /** Den Balken "90/90" im Auswahlfenster schon gelesen (einmal je Lauf). */
    private boolean balkenGelesen;

    /**
     * Wann der volle Balken zuletzt gesehen wurde -- auch vom Kolosseum aus, das
     * im selben Auswahlfenster steht (Autor, 28.09.: "bevor der Bot auf Colosseum
     * drueckt, kann er schon checken, ob der Turm gemacht wurde").
     */
    private static volatile long vollGesehenUm = 0;

    /** Zu welchem Turm-Tag (Reset 02:00) der volle Balken gehoert. */
    private static volatile java.time.LocalDate vollGesehenTag = null;

    static void vollGesehen() {
        vollGesehenUm = System.currentTimeMillis();
        vollGesehenTag = turmTag();
    }

    /** Der Turm-Tag: ab 02:00 zaehlt der neue Tag (dann ist der Balken zurueckgesetzt). */
    private static java.time.LocalDate turmTag() {
        return de.hustlebot.core.Zeitzone.jetzt().minusHours(2).toLocalDate();
    }

    /** Der Fortschritt unter "Tower" im Auswahlfenster ("90/90"), oder null. */
    static int[] balkenLesen(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_AKTIVITAET)) return null;
        Optional<Matcher.Hit> schrift = ctx.matcher().find(screen, T_AKTIVITAET);
        java.awt.image.BufferedImage bild = ctx.frame();
        if (schrift.isEmpty() || bild == null) return null;
        Matcher.Hit s = schrift.get();
        double f = ctx.height() / 770.0;
        return Nahrungskisten.bruchVerstaerkt(bild, s.centerX() - (int) (35 * f),
                s.centerY() + (int) (40 * f), (int) (90 * f), (int) (30 * f), true);
    }
    private int minusKlicks, blind, wischer, homeKlicks;
    private long begonnen, startUm, karteGeklicktUm, letzterKlick;

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Die wenigen kurzen festen Pausen (turm-tempo.txt) laufen x Faktor;
     * das Warten auf die Auto-Kaempfe, das Ergebnis-Einblenden (600 ms) und alle Bildwechsel bleiben. Runde = ein
     * abgeschlossener Auto-Kampf (Ergebnisfenster, Sieg oder Niederlage). Fehler = jede Warnung und jeder Abbruch des Turms.
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("turm-tempo.txt", "Titanen-Turm");
    private int runFehler;
    /** Abgeschlossene Auto-Kaempfe seit dem Start (fuer die Tempo-Zeile alle 10 Runden; nicht in reset()). */
    private int tempoRunden;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn("Titanen-Turm", text);
    }

    private void fehler(String text) {
        runFehler++;
        Log.error("Titanen-Turm", text);
    }

    private void tempoRundeFertig() {
        tempoRunden++;
        tempo.rundeFertig(runFehler == 0);
        if (tempoRunden % 10 == 0) {
            Log.info("Titanen-Turm", Sprache.t("Speed after " + tempoRunden + " auto battles: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + tempoRunden + " Auto-Kaempfen: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    @Override public String id() { return "titantower"; }

    @Override public String displayName() { return de.hustlebot.core.Sprache.t("Titan tower", "Titanen-Turm"); }

    @Override
    public List<String> requiredTemplates() {
        return List.of(T_KARTE_TURM, T_AKTIVITAET, T_TITEL, T_EASY, T_HARD, T_VERYHARD,
                T_AUTO, T_ZUR_TRUPPE, T_START, T_FERTIG, T_ERGEBNIS_HOME);
    }

    // Autor 24.10.: der Clan-Krieg darf den Turm unterbrechen ("frueher abbrechen, Krieg setzen, wieder zum Turm").
    // Gewechselt wird zwischen zwei Autokaempfen; die schon geschafften Tuerme bleiben fuer die Fortsetzung gemerkt.
    private static volatile long unterbrochenUm;
    private static volatile java.time.LocalDate unterbrochenTag;
    private static final Set<String> erledigtGemerkt = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile long fortsetzungErwartetUm;
    /** Die Warteschlange startet gleich die Fortsetzung nach einem Einschub (Clan-Krieg). */
    public static void fortsetzungErwarten() { fortsetzungErwartetUm = System.currentTimeMillis(); }
    /** Fortsetzung erwartet, aber der Turm war vor dem Wechsel schon fertig: gleich beenden. */
    private boolean nichtsMehr;

    @Override
    public boolean wechselJetzt() {
        // Code-Review 24.10.: beim Aufgeben (4 Niederlagen) erst zu Ende -- die Fortsetzung kennt "aufgeben" nicht und
        // griffe den Turm sonst noch einmal viermal an.
        if (autoLaeuft || aufgeben) return false;
        erledigtGemerkt.clear();
        erledigtGemerkt.addAll(erledigt);
        unterbrochenUm = System.currentTimeMillis();
        unterbrochenTag = turmTag();
        Log.info("Titanen-Turm", Sprache.t("Between two battles -- the tower pauses for the interlude and carries on afterwards.",
                "Zwischen zwei Kaempfen -- der Turm macht Pause fuer den Einschub und geht danach weiter."));
        return true;
    }

    @Override
    public void reset() {
        turm = null;
        erledigt.clear();
        boolean unterbrochen = System.currentTimeMillis() - unterbrochenUm < 3 * 60 * 60_000L && turmTag().equals(unterbrochenTag);
        if (unterbrochen) erledigt.addAll(erledigtGemerkt);   // Fortsetzung nach dem Einschub
        nichtsMehr = System.currentTimeMillis() - fortsetzungErwartetUm < 120_000L && !unterbrochen;
        fortsetzungErwartetUm = 0;
        unterbrochenUm = 0;
        erledigtGemerkt.clear();
        niederlagen = 0;
        aufgeben = fertig = autoLaeuft = deployGedrueckt = ergebnisGezaehlt = balkenGelesen = false;
        minusKlicks = blind = wischer = homeKlicks = suchBlicke = 0;
        begonnen = System.currentTimeMillis();
        startUm = karteGeklicktUm = letzterKlick = 0;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        if (nichtsMehr) {
            Log.info("Titanen-Turm", Sprache.t("The tower was already finished before the interlude -- nothing left to do.",
                    "Der Turm war schon vor dem Einschub fertig -- nichts mehr zu tun."));
            runs.completeRun();
            return Outcome.FINISHED;
        }
        if (jetzt - begonnen > GESAMT_MAX_MS) {
            fehler(Sprache.t("Not done after " + GESAMT_MAX_MS / 60000 + " minutes -- aborted.",
                    "Nach " + GESAMT_MAX_MS / 60000 + " Minuten nicht fertig -- abgebrochen."));
            return Outcome.FATAL;
        }

        // Eben erst beim Kolosseum im Auswahlfenster gesehen: alles fertig
        // (90/90) -- gar nicht erst hin.
        if (!fertig && jetzt - vollGesehenUm < 10 * 60_000L && turmTag().equals(vollGesehenTag)) {
            Log.info("Titanen-Turm", Sprache.t("According to the selection window (seen at the Colosseum) all towers are done -- " + "the tower is skipped.",
                    "Laut Auswahlfenster (beim Kolosseum gesehen) sind alle Tuerme fertig -- "
                    + "der Turm wird uebersprungen."));
            fertig = true;
            Turmplan.erledigt();
            runs.completeRun();
            return Outcome.FINISHED;
        }

        // AFK-Rauswurf ("are you still here?"): Update, das Spiel laedt neu.
        if (Stoerfenster.afkUpdate(screen, ctx, "Titanen-Turm")) return Outcome.ACTED;
        if (Stoerfenster.ereignisTextWegklicken(screen, ctx, "Titanen-Turm")) return Outcome.ACTED;
        // Werbefenster "Running out of apples?", Tagesbelohnung (Autor, 29.09.: der Bot
        // drueckte 8x "Weltkarte", waehrend das Apfel-Fenster offen war -- der Map-Knopf
        // "passt" auch hinter dem Fenster). Hoechstens jede Sekunde (~0,2 s Suche).
        if (System.currentTimeMillis() - stoerGeprueftUm >= 1000) {
            stoerGeprueftUm = System.currentTimeMillis();
            if (Stoerfenster.belohnungWegklicken(screen, ctx, "Titanen-Turm")) return Outcome.ACTED;
        }

        // --- 1. Ergebnis: "Auto battle finished!" ----------------------------
        if (sichtbar(screen, ctx, T_FERTIG) && sichtbar(screen, ctx, T_ERGEBNIS_HOME)) {
            blind = 0;
            autoLaeuft = false;
            if (!ergebnisGezaehlt) {
                ergebnisGezaehlt = true;
                // Zweiter Blick, bevor eine Niederlage gezaehlt wird: der Text
                // "You didn't get your rewards" blendet mit dem Fenster ein.
                ctx.sleep(600);
                Mat frisch = ctx.refresh();
                boolean verloren = sichtbar(frisch != null ? frisch : screen, ctx, T_VERLOREN);
                if (verloren) {
                    niederlagen++;
                    Log.info("Titanen-Turm", Sprache.t(name(turm) + ": lost (" + niederlagen + ". time at this level).",
                            name(turm) + ": verloren (" + niederlagen + ". Mal an diesem Level)."));
                    if (niederlagen >= VERSUCHE_JE_LEVEL) {
                        aufgeben = true;
                        Log.info("Titanen-Turm", Sprache.t(name(turm) + ": lost " + niederlagen + " times -- the tower " + "is finished for today, carrying on with the actual mode.",
                                name(turm) + ": " + niederlagen + " Mal verloren -- der Turm "
                                + "wird fuer heute beendet, es geht mit dem eigentlichen Modus weiter."));
                    }
                } else {
                    Log.info("Titanen-Turm", Sprache.t(name(turm) + ": auto battle done, won.",
                            name(turm) + ": Auto-Kampf fertig, gewonnen."));
                    if (turm != null) erledigt.add(turm);
                    niederlagen = 0;
                }
                tempoRundeFertig();
            }
            return klicken(screen, ctx, T_ERGEBNIS_HOME, Sprache.t("Confirm the result (Home)", "Ergebnis bestaetigen (Home)"));
        }

        // --- 2. Die Kaempfe laufen: nur warten ----------------------------------
        boolean laeuft = sichtbar(screen, ctx, T_LAEUFT) || sichtbar(screen, ctx, T_NAECHSTER);
        if (laeuft || (autoLaeuft && jetzt - startUm < LAUF_MAX_MS && !bekannterBildschirm(screen, ctx))) {
            blind = 0;
            if (laeuft && !autoLaeuft) {   // Code-Review 24.10.: auch ein Kampf, der schon lief -- dann kein Wechsel
                autoLaeuft = true;
                startUm = jetzt;
            }
            ctx.sleep(1000);
            return Outcome.WAITING;
        }

        // --- 3. Truppenfenster: "Deploy the best", dann "Start auto battle!" ----
        if (sichtbar(screen, ctx, T_START)) {
            blind = 0;
            if (!deployGedrueckt && sichtbar(screen, ctx, T_DEPLOY)) {
                deployGedrueckt = true;
                return klicken(screen, ctx, T_DEPLOY, Sprache.t("Deploy the best titans (Deploy the best)", "Beste Titanen aufstellen (Deploy the best)"));
            }
            Outcome o = klicken(screen, ctx, T_START, Sprache.t("Start the auto battle (" + name(turm) + ")", "Auto-Kampf starten (" + name(turm) + ")"));
            if (o == Outcome.ACTED) {
                autoLaeuft = true;
                startUm = System.currentTimeMillis();
                ergebnisGezaehlt = false;
                deployGedrueckt = false;
            }
            return o;
        }

        // --- 4. "Auto Battle Settings": Level einstellen, dann weiter --------------
        if (sichtbar(screen, ctx, T_ZUR_TRUPPE) || sichtbar(screen, ctx, T_AUTO_TITEL)) {
            blind = 0;
            int max = turm != null ? Turmplan.maximum(turm) : 30;
            if (max < 30 && minusKlicks < 30) {
                OptionalLong wert = levelImFenster(screen, ctx);
                if (wert.isPresent() && wert.getAsLong() > max) {
                    minusKlicks++;
                    return klicken(screen, ctx, T_MINUS, Sprache.t("\"Reach the level\" one less (" + wert.getAsLong() + " -> target " + max + ")",
                            "\"Reach the level\" einen weniger (" + wert.getAsLong()
                            + " -> Ziel " + max + ")"));
                }
                if (wert.isEmpty()) {
                    warnung(Sprache.t("The level in the \"Auto Battle Settings\" window is not readable -- " + "it stays as it is.",
                            "Das Level im Fenster \"Auto Battle Settings\" ist nicht lesbar -- "
                            + "es bleibt, wie es ist."));
                    minusKlicks = 99;
                }
            }
            deployGedrueckt = false;
            return klicken(screen, ctx, T_ZUR_TRUPPE, "Zur Truppenauswahl (To squad selection)");
        }

        // --- 5. Im Turm (Etage, "Auto" unten) --------------------------------------
        if (sichtbar(screen, ctx, T_AUTO)) {
            blind = 0;
            if (aufgeben || fertig || (turm != null && erledigt.contains(turm))) {
                return zurueck(screen, ctx);
            }
            if (turm != null) {
                OptionalLong etage = etageLesen(screen, ctx);
                if (etage.isPresent() && etage.getAsLong() > Turmplan.maximum(turm)) {
                    Log.info("Titanen-Turm", Sprache.t(name(turm) + ": floor " + etage.getAsLong() + " is above the " + "maximum " + Turmplan.maximum(turm) + " -- done.",
                            name(turm) + ": Etage " + etage.getAsLong() + " liegt ueber dem "
                            + "Hoechstwert " + Turmplan.maximum(turm) + " -- fertig."));
                    erledigt.add(turm);
                    return zurueck(screen, ctx);
                }
            }
            minusKlicks = 0;
            return klicken(screen, ctx, T_AUTO, Sprache.t("Set up the auto battle (Auto)", "Auto-Kampf einstellen (Auto)"));
        }

        // --- 6. Die drei Schwierigkeiten --------------------------------------------
        if (sichtbar(screen, ctx, T_TITEL)) {
            blind = 0;
            List<String> nichtGefunden = new ArrayList<>();
            if (!aufgeben) {
                Set<String> voll = volleTuerme(screen, ctx);
                for (String t : Turmplan.TUERME) {
                    if (voll.contains(t)) erledigt.add(t);
                }
                for (String t : Turmplan.TUERME) {
                    if (erledigt.contains(t)) continue;
                    Optional<Matcher.Hit> knopf = turmKnopf(screen, ctx, t);
                    if (knopf.isEmpty()) {
                        nichtGefunden.add(name(t));
                        continue;
                    }
                    if (!t.equals(turm)) niederlagen = 0;
                    turm = t;
                    Matcher.Hit h = knopf.get();
                    Log.action("Klick", Sprache.t("Open tower " + name(t) + "  [" + h.name() + " at " + h.centerX() + "," + h.centerY() + String.format(", match %.3f]", h.score()),
                            "Turm " + name(t) + " oeffnen  [" + h.name() + " bei " + h.centerX() + ","
                            + h.centerY() + String.format(", Uebereinstimmung %.3f]", h.score())));
                    ctx.input().click(h);
                    letzterKlick = System.currentTimeMillis();
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
                // Autor 31.10., 00:23: "Very Hard" nicht gefunden, Besuch nach 5 s zu Ende; beim zweiten Start passte das Bild zu 100 %. Das Bild rutschte
                // noch nach dem Verschieben der Karte: erst ein paarmal hinsehen, dann aufgeben.
                if (!nichtGefunden.isEmpty() && suchBlicke < SUCHBLICKE) {
                    suchBlicke++;
                    ctx.sleep(tempo.ms(500));
                    return Outcome.WAITING;
                }
                suchBlicke = 0;
                if (nichtGefunden.isEmpty()) Log.info("Titanen-Turm", Sprache.t("All three towers done.", "Alle drei Tuerme fertig."));
                else warnung(Sprache.t("Not found: " + String.join(", ", nichtGefunden) + " (templates tt_easy/tt_hard/tt_veryhard?). The bot tries again in 20 minutes.",
                        "Nicht gefunden: " + String.join(", ", nichtGefunden)
                        + " (Vorlagen tt_easy/tt_hard/tt_veryhard?). Der Bot versucht es in 20 Minuten noch einmal."));
            }
            if (!fertig) {
                fertig = true;
                // Nur abhaken, wenn wirklich nichts mehr offen ist -- oder Autors
                // Regel greift (viermal verloren: fuer heute Schluss).
                if (aufgeben || nichtGefunden.isEmpty()) Turmplan.erledigt();
            }
            if (++homeKlicks > 5) return Outcome.FATAL;
            if (sichtbar(screen, ctx, T_LISTE_HOME)) return klicken(screen, ctx, T_LISTE_HOME, Sprache.t("Leave the tower (Home)", "Turm verlassen (Home)"));
            Weltkarte.heim(screen, ctx, "Titanen-Turm");
            return Outcome.ACTED;
        }

        // --- 7. "Select an activity" ---------------------------------------------------
        if (sichtbar(screen, ctx, T_AKTIVITAET) || sichtbar(screen, ctx, T_AKTIVITAET_TITEL)) {
            blind = 0;
            if (fertig || aufgeben) {
                if (ctx.clickIfVisibleIfKnown(screen, T_X, Sprache.t("Close the selection window", "Auswahlfenster schliessen"))) {
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
                return warten(screen, ctx);
            }
            // NICHT auf die Schrift, sondern leicht darueber auf das Turm-Wappen
            // (Autor, 27.09.: "wenn er auf Tower klickt, passiert nichts").
            Optional<Matcher.Hit> schrift = ctx.matcher().find(screen, T_AKTIVITAET);
            if (schrift.isEmpty()) return warten(screen, ctx);
            Matcher.Hit s = schrift.get();
            // BALKEN SCHON IN DER AUSWAHL (Autor, 28.09., Bild): Unter "Tower" steht
            // der Fortschritt ("90/90"). Voll heisst: alle Tuerme fertig -- gar nicht
            // erst hinein, gleich weiter. Nicht lesbar: wie bisher hinein.
            if (!balkenGelesen) {
                balkenGelesen = true;
                int[] b = balkenLesen(screen, ctx);
                if (b != null && b[1] > 0 && b[0] >= b[1]) {
                    Log.info("Titanen-Turm", Sprache.t("According to the selection " + b[0] + "/" + b[1] + " -- all towers done, not entered.",
                            "Laut Auswahl " + b[0] + "/" + b[1]
                            + " -- alle Tuerme fertig, nicht hineingegangen."));
                    fertig = true;
                    Turmplan.erledigt();
                    vollGesehen();
                    return Outcome.ACTED;   // naechstes Bild: Auswahl schliessen (fertig)
                }
                if (b != null) Log.info("Titanen-Turm", Sprache.t("According to the selection " + b[0] + "/" + b[1] + " -- something is still open.",
                        "Laut Auswahl " + b[0] + "/" + b[1] + " -- es ist noch etwas offen."));
            }
            int x = s.centerX(), y = Math.max(0, s.centerY() - (int) (ctx.height() * 0.12));
            Log.action("Klick", Sprache.t("Choose Tower (not Colosseum), on the crest above the text  [" + x + "," + y + "]",
                    "Tower waehlen (nicht Colosseum), aufs Wappen ueber der Schrift  [" + x + "," + y + "]"));
            ctx.input().click(x, y);
            blind = 0;
            letzterKlick = System.currentTimeMillis();
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // --- 8. Weltkarte mit "Tower" -----------------------------------------------------
        boolean ortZuSehen = sichtbar(screen, ctx, T_KARTE_TURM);
        if (ortZuSehen) {
            blind = 0;
            if (fertig || aufgeben) {
                runs.completeRun();
                return Outcome.FINISHED;
            }
            return klicken(screen, ctx, T_KARTE_TURM, Sprache.t("Open the Tower on the world map", "Tower auf der Weltkarte oeffnen"));
        }

        // --- 9. Die Burg -------------------------------------------------------------------
        if (sichtbar(screen, ctx, T_MAP) || sichtbar(screen, ctx, T_MAP_ALT)) {
            blind = 0;
            if (fertig || aufgeben) {
                runs.completeRun();
                return Outcome.FINISHED;
            }
            if (jetzt - karteGeklicktUm < 2500) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            karteGeklicktUm = jetzt;
            wischer = 0;
            if (ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, de.hustlebot.core.Sprache.t("Open the world map (Titan Tower)", "Weltkarte oeffnen (Titanen-Turm)"))
                    || ctx.clickIfVisibleIfKnown(screen, T_MAP, de.hustlebot.core.Sprache.t("Open the world map (Titan Tower)", "Weltkarte oeffnen (Titanen-Turm)"))) {
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- 10. Weltkarte ohne "Tower": wischen wie beim Kolosseum ------------------------
        if (Weltkarte.erkannt(screen, ctx) || (karteGeklicktUm > 0 && jetzt - karteGeklicktUm < 20_000)) {
            blind = 0;
            if (fertig || aufgeben) {
                runs.completeRun();
                return Outcome.FINISHED;
            }
            if (jetzt - karteGeklicktUm < 1500) {
                ctx.sleep(tempo.ms(200));
                return Outcome.WAITING;
            }
            if (wischer < MAX_WISCHER) {
                wischer++;
                boolean nachRechts = wischer <= 3;   // wie beim Kolosseum: der Turm liegt links
                int y = ctx.height() / 2;
                int links = (int) (ctx.width() * 0.25), rechts = (int) (ctx.width() * 0.75);
                Log.action("Karte", de.hustlebot.core.Sprache.t("Dragging the world map ", "Weltkarte nach ") + (nachRechts ? de.hustlebot.core.Sprache.t("right", "rechts") : de.hustlebot.core.Sprache.t("left", "links"))
                        + de.hustlebot.core.Sprache.t(" to find the tower (swipe ", " ziehen, um den Tower zu finden (Zug ") + wischer + de.hustlebot.core.Sprache.t(" of ", " von ") + MAX_WISCHER + ").");
                if (nachRechts) ctx.input().drag(links, y, rechts, y);
                else ctx.input().drag(rechts, y, links, y);
                ctx.sleepUntilChanged(400);
                return Outcome.ACTED;
            }
            fehler(Sprache.t("The Tower was not found on the world map (template ko_tower).",
                    "Der Tower wurde auf der Weltkarte nicht gefunden (Vorlage ko_tower)."));
            return Outcome.FATAL;
        }

        // --- 11. Nichts erkannt ----------------------------------------------------------
        return warten(screen, ctx);
    }

    // --- Hilfen ------------------------------------------------------------------------

    private static String name(String t) {
        if (t == null) return "Turm";
        return switch (t) {
            case "easy" -> "Easy";
            case "hard" -> "Hard";
            case "veryhard" -> "Very Hard";
            default -> t;
        };
    }

    private static boolean sichtbar(Mat screen, BotContext ctx, String vorlage) {
        return screen != null && ctx.visibleIfKnown(screen, vorlage);
    }

    /** Ein Bildschirm, auf dem der Bot etwas tun wuerde (dann laeuft kein Kampf mehr). */
    private boolean bekannterBildschirm(Mat screen, BotContext ctx) {
        for (String v : new String[]{T_FERTIG, T_START, T_ZUR_TRUPPE, T_AUTO, T_TITEL, T_AKTIVITAET,
                T_MAP, T_MAP_ALT, T_KARTE_TURM}) {
            if (sichtbar(screen, ctx, v)) return true;
        }
        // Hauptbot 11.10. 12:20: Nach "Update" (Spiel neu geladen) stand die Weltkarte OHNE "Tower" im Bild -- der Turm
        // wartete bis zu 30 min auf einen Auto-Kampf, der gar nicht mehr lief (Haenger-Alarm 12:16).
        return Weltkarte.erkannt(screen, ctx);
    }

    private Outcome klicken(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.clickIfVisibleIfKnown(screen, vorlage, grund)) return warten(screen, ctx);
        blind = 0;
        letzterKlick = System.currentTimeMillis();
        ctx.sleepUntilChanged(1500);
        return Outcome.ACTED;
    }

    private Outcome warten(Mat screen, BotContext ctx) {
        // Fremdes Fenster davor (30.09., 02:00: das Hammer-Event lag noch offen, der Turm
        // wartete 80 s und gab auf): nach 12 Bildern das rote X, wie Arena und Leuchtturm.
        if (screen != null && blind >= 12 && blind % 12 == 0
                && (ctx.clickIfVisibleIfKnown(screen, "dg_close_x", Sprache.t("Close the foreign window (Titan tower)", "Fremdes Fenster schliessen (Titanen-Turm)"))
                    || Stoerfenster.notausgang(screen, ctx, "Titanen-Turm", blind))) {
            blind++;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (++blind > MAX_BLIND) {
            fehler(Sprache.t("Screen not recognized -- the tower is aborted.",
                    "Bildschirm nicht erkannt -- der Turm wird abgebrochen."));
            return Outcome.FATAL;
        }
        ctx.sleep(tempo.ms(500));
        return Outcome.UNKNOWN;
    }

    /** "Back" unten links im Turm; ohne Vorlage die feste Stelle (wie "Home" auf der Karte). */
    private Outcome zurueck(Mat screen, BotContext ctx) {
        if (sichtbar(screen, ctx, T_BACK)) return klicken(screen, ctx, T_BACK, Sprache.t("Leave the tower (Back)", "Turm verlassen (Back)"));
        Weltkarte.heim(screen, ctx, Sprache.t("Titan tower (Back)", "Titanen-Turm (Back)"));
        return Outcome.ACTED;
    }

    /** Welche Tuerme zeigen den gruenen Balken "30/30"? Zuordnung ueber die Stelle (links/Mitte/rechts). */
    private static Set<String> volleTuerme(Mat screen, BotContext ctx) {
        Set<String> voll = new HashSet<>();
        if (!ctx.matcher().has(T_VOLL)) return voll;
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_VOLL, 0.88, 3)) {
            voll.add(turmAnStelle(h.centerX(), ctx.width()));
        }
        return voll;
    }

    private static String turmAnStelle(int x, int breite) {
        double f = x / (double) breite;
        return f < 0.40 ? "easy" : f < 0.60 ? "hard" : "veryhard";
    }

    /**
     * Der Knopf eines Turms -- und nur, wenn er an der richtigen Stelle steht:
     * "Hard" steht auch in "Very Hard", deshalb zaehlt die Stelle mit.
     */
    private static Optional<Matcher.Hit> turmKnopf(Mat screen, BotContext ctx, String turm) {
        String vorlage = switch (turm) {
            case "easy" -> T_EASY;
            case "hard" -> T_HARD;
            default -> T_VERYHARD;
        };
        if (!ctx.matcher().has(vorlage)) return Optional.empty();
        for (Matcher.Hit h : ctx.matcher().findAll(screen, vorlage, Matcher.DEFAULT_THRESHOLD, 3)) {
            if (turmAnStelle(h.centerX(), ctx.width()).equals(turm)) return Optional.of(h);
        }
        return Optional.empty();
    }

    /** Die Etage im Turm: die Zahl rechts neben "Floor". */
    private static OptionalLong etageLesen(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_FLOOR)) return OptionalLong.empty();
        Optional<Matcher.Hit> a = ctx.matcher().find(screen, T_FLOOR);
        BufferedImage bild = ctx.frame();
        if (a.isEmpty() || bild == null) return OptionalLong.empty();
        Matcher.Hit h = a.get();
        int x = h.x() + h.width(), y = h.y() - 4, w = (int) (h.width() * 1.3), hh = h.height() + 8;
        OptionalLong n = ArenaLeser.zahl(bild, x, y, w, hh);
        if (n.isEmpty()) n = Nahrungskisten.zahlVerstaerkt(bild, x, y, w, hh, true);
        if (n.isPresent() && (n.getAsLong() < 1 || n.getAsLong() > 31)) return OptionalLong.empty();
        return n;
    }

    /** Das Level in "Auto Battle Settings": die Zahl rechts neben dem Minus-Knopf. */
    private static OptionalLong levelImFenster(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_MINUS)) return OptionalLong.empty();
        Optional<Matcher.Hit> m = ctx.matcher().find(screen, T_MINUS);
        BufferedImage bild = ctx.frame();
        if (m.isEmpty() || bild == null) return OptionalLong.empty();
        Matcher.Hit h = m.get();
        int x = h.x() + h.width(), w = (int) (h.width() * 2.0);
        OptionalLong n = ArenaLeser.zahl(bild, x, h.y(), w, h.height());
        if (n.isEmpty()) n = Nahrungskisten.zahlVerstaerkt(bild, x, h.y(), w, h.height(), false);
        if (n.isPresent() && (n.getAsLong() < 1 || n.getAsLong() > 30)) return OptionalLong.empty();
        return n;
    }
}
