package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Texterkennung;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stigmas loeschen (Autor, 10.10.: "!stigmas loeschen").
 *
 * DER WEG (aus Autors Aufnahme vom 10.10., 19:46, Klick fuer Klick ausgewertet):
 *   Burg -> Map -> "Tower" -> "Select an activity": Tower -> "Select the difficulty level"
 *   -> lila Titan-Knopf links (30,518) -> "Titan Squad" -> Truhe links (52,570) -> "Titan Inventory"
 *   -> gruener Muelleimer unten rechts (1031,640) -> "Disassembling items" -> 4. Reiter (Stigmas)
 *   -> Quick add: Reiter Stigmas, dann beide "+" (blau = selten, lila = episch; Autor: "trotzdem druecken")
 *   -> Kachel fuer Kachel: antippen, im Fenster Titel ("Hedgehog Stigma - Mind"), Sterne und Level lesen,
 *      "Add" nur wenn ALLES stimmt, sonst mit dem Pfeil zurueck
 *   -> "Disassemble (N)" -> Warnung "these items: N?" mit der eigenen Zaehlung vergleichen -> nach dem
 *      Countdown "OK".
 *
 * AUTORS REGEL (10.10., 20:40): "Geloescht wird nur, was Level 1 und 1 Stern hat und nicht Bull oder Dragon
 * Stigma ist." Alles andere -- und alles, was nicht sicher lesbar ist -- bleibt liegen.
 *
 * PROBELAUF ("!stigmas loeschen probe"): geht die ganze Liste durch und schreibt auf, was er loeschen wuerde,
 * drueckt aber weder "+" noch "Add" noch "Disassemble".
 *
 * FREUNDE-FASSUNG (uebernommen 10.10. aus dem Hauptbot, Commit 02fe88a; 11.10. mit "!stigmas leveln/einstellen/
 * hundert" bis Commit 7156348): gleicher Ablauf, Wolf bleibt auch (Autor 21:16), Tempo ohne Datei 0,5. Das Spiel
 * muss auf Englisch stehen (Titel werden gelesen). Die Titanen der Freunde sind nicht Autors: ohne Titan gilt der
 * Last Guardian, ein anderer Name gilt als eigener Titan des Freundes (Stigmaplan.titanFrei, bewertet nach seinen
 * Einstellungen).
 */
public final class StigmaMode implements GameMode {

    private static final String WER = "Stigmas";
    private static final int BREITE = 1159, HOEHE = 770;

    /** Fuer den naechsten Start (vom Befehl gesetzt). */
    private static volatile boolean probeNaechster = false;
    private static volatile boolean levelnNaechster = false;
    /**
     * Code-Pruefung 15.10. (Hauptbot): Nur ein frisch erteilter Auftrag darf laufen. Ein Neustart mit den letzten
     * Angaben (BotEngine.startWithLastSettings / !neustart) ruft naechsterStart NICHT -- dann waeren probe/leveln
     * false gewesen und aus "filtern" oder "probe" waere echtes LOESCHEN geworden.
     */
    private static volatile boolean auftragNaechster = false;
    private boolean ohneAuftrag;

    public static void naechsterStart(boolean probe) {
        naechsterStart(probe, false);
    }

    /** leveln = "!stigmas leveln" (Last Guardian), sonst "!stigmas loeschen". */
    public static void naechsterStart(boolean probe, boolean leveln) {
        naechsterStart(probe, leveln, 0);
    }

    private static volatile int startNameNaechster = 0;

    /** startName: 0 = Mind, 1 = Will, ... (Autor 01:41: nach einem Neustart bei Will weitermachen, nicht wieder bei Mind). */
    public static void naechsterStart(boolean probe, boolean leveln, int startName) {
        naechsterStart(probe, leveln, startName, "lastguardian", false, false, false);
    }

    private static volatile String titanNaechster = "lastguardian";
    private static volatile boolean hundertNaechster, nurGelevelteNaechster, nurHundertNaechster;

    /**
     * Autor 11.10. 03:40: je Titan eigene Regeln (Stigmaplan.TITANEN). nurGelevelte: nur Kacheln ab Level 2 oeffnen
     * ("die aufgelevelten, die der Bot markiert hat"); hundert: danach die eingesetzten so weit wie moeglich auf
     * Level 100; nurHundert: nur das (ohne Durchsehen).
     */
    public static void naechsterStart(boolean probe, boolean leveln, int startName, String titan,
                                      boolean hundert, boolean nurGelevelte, boolean nurHundert) {
        probeNaechster = probe;
        levelnNaechster = leveln;
        auftragNaechster = true;
        startNameNaechster = Math.max(0, Math.min(5, startName));
        titanNaechster = titan == null ? "lastguardian" : titan;
        hundertNaechster = hundert;
        nurGelevelteNaechster = nurGelevelte;
        nurHundertNaechster = nurHundert;
    }

    /** Ergebnis des letzten Laufs fuer Discord. */
    private static volatile String bericht = null;
    private static volatile String kurzBericht = null;

    public static String letzterBericht() {
        return bericht;
    }

    // --- Stellen im Spielfenster (1159 x 770) -----------------------------------------------------------------
    private static final int[] TITAN_KNOPF = {30, 518};          // Turm: lila Kristall links
    private static final int[] TRUHE = {52, 570};                // Titan Squad: Tasche
    private static final int[] TITAN_COLLECTION = {508, 660};    // Titan Squad: Knopf "Titan Collection" (23.10. gemessen)
    private static final int[] ERSTE_KARTE = {246, 170};         // Titan Collection: erste Karte oben links
    private static final int[] K_COLLECTION = {250, 12, 280, 42}; // Titan Collection: Ueberschrift "Titan Collection"

    /** Wie die Vorlagen in templates/gelernt/titanen heissen: "Keeper of Stars" -> "keeper_of_stars". */
    static String titanSlug(String name) {
        return (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
    private static final int[] MUELLEIMER = {1031, 640};         // Titan Inventory: gruener Knopf unten rechts
    private static final int[] REITER_STIGMA = {1090, 128};      // Disassembling: 4. Reiter rechts
    private static final int[] QUICK_STIGMA = {572, 478};        // Quick add: 4. Symbol (Stigmas)
    private static final int[] PLUS_BLAU = {245, 550};
    private static final int[] PLUS_LILA = {466, 550};
    private static final int[] ADD = {580, 656};
    // NICHT der Pfeil oben links im Stigma-Fenster: der oeffnet die Set-Uebersicht ("Hedgehog Set"), 10.10. 21:01.
    // Bis 15.10.: Tipp rechts neben das Fenster (990,410). Dort lag eine Kachel: jeder Tipp schloss das Fenster und
    // oeffnete sofort das naechste. Jetzt: auf die Ueberschrift "Disassembling items" -- dort liegt kein Knopf.
    private static final int[] SCHLIESSEN = {250, 145};
    private static final int[] DISASSEMBLE = {448, 641};
    private static final int[] OK = {483, 520};
    private static final int[] NEIN = {675, 520};

    private static final int[] SPALTEN = {745, 853, 961, 1069};
    // Senkrechte Linie durch die linke Kachel-Kante. 708 war falsch fuer "New"-Kacheln: ihr tuerkiser Rahmen teilt dort
    // die orange Kante (21:14, lauter neue Krokodile -> "keine Kacheln", der Bot schob weiter). Bei 704 beginnt die
    // Kante bei beiden Sorten an derselben Stelle (gemessen: 208 / 343 / 479 / 615).
    private static final int REIHEN_X = 704;
    private static final int LISTE_OBEN = 172, LISTE_UNTEN = 667;
    private static final int KACHEL_MITTE = 36;                  // vom oberen Rand der Kachel bis zum Tipp-Punkt
    /** Ab diesem Level bleibt ein Stigma immer liegen (Autor 23.10.: "ausser es hat Level 80 oder hoeher"). */
    static final int LOESCH_LEVEL_AB = 80;

    // Texte (Kasten x, y, b, h)
    private static final int[] K_SQUAD = {440, 4, 280, 44};
    private static final int[] K_SQUAD_LEISTE = {150, 700, 560, 56};   // "Squad modification  Titan Patrons ..."
    private static final int[] K_INVENTAR = {430, 112, 300, 42};
    private static final int[] K_ZERLEGEN = {200, 124, 310, 40};
    private static final int[] K_TITEL = {430, 84, 330, 38};
    private static final int[] K_LEVEL = {470, 508, 220, 36};
    private static final int[] K_KNOPF = {360, 605, 180, 70};   // "Disassemble" und darunter "(N)"
    private static final int[] K_WARNUNG = {260, 240, 640, 62};

    /** Sets, die geloescht werden duerfen (alle anderen bleiben -- auch Lesefehler). */
    private static final java.util.Set<String> LOESCHBAR = java.util.Set.of("hedgehog", "crocodile", "elephant", "tiger", "eagle", "phoenix",
            // 21:40, beim ersten Lauf gefunden; Autor: "Lion und Scorpion schon loeschen ... und die Maus (Rat) auch",
            // Regel "alles ausser Bull, Dragon und Wolf"
            "hydra", "unicorn", "boar", "bear", "lion", "scorpion", "rat", "snake", "turtle");
    private static final java.util.Set<String> NAMEN = java.util.Set.of("mind", "will", "courage", "strength", "resolve", "tenacity");
    /** Autor: Bull und Dragon behalten, 21:16 auch Wolf. */
    private static final java.util.Set<String> GESCHUETZT = java.util.Set.of("bull", "dragon", "wolf");
    private static final java.util.Set<String> ALLE_SETS;
    static {
        java.util.Set<String> alle = new java.util.HashSet<>(LOESCHBAR);
        alle.addAll(GESCHUETZT);
        // Code-Pruefung 15.10. (Hauptbot): alle bekannten Set-Namen aufnehmen -- sonst wurde ein richtig gelesenes
        // "Bat" oder "Spider" zu einem aehnlichen Namen (z. B. "Rat"). Geloescht wird weiter nur, was in LOESCHBAR steht.
        alle.addAll(de.hustlebot.core.Stigmaplan.SETS);
        ALLE_SETS = java.util.Set.copyOf(alle);
    }

    /** Das gelesene Wort -- oder das bekannte, von dem es hoechstens einen Buchstaben abweicht (nur wenn eindeutig). */
    static String aehnlich(String wort, java.util.Set<String> bekannt, int erlaubt) {
        String w = wort.toLowerCase(Locale.ROOT);
        if (bekannt.contains(w)) return w;
        String treffer = null;
        for (String b : bekannt) {
            if (abstand(w, b) <= erlaubt) {
                if (treffer != null) return w;   // nicht eindeutig
                treffer = b;
            }
        }
        return treffer != null ? treffer : w;
    }

    private static int abstand(String a, String b) {
        int[] vor = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vor[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vor[j] + 1), vor[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = vor; vor = jetzt; jetzt = t;
        }
        return vor[b.length()];
    }
    private static final Pattern TITEL = Pattern.compile("([A-Za-z]+)\\s*Stigma\\s*[-–—]+\\s*([A-Za-z]+)");
    private static final Pattern LEVEL = Pattern.compile("Level\\s*(\\d+)\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZAHL_KLAMMER = Pattern.compile("\\((\\d[\\d\\s.,]*)\\)");
    private static final Pattern WARN_ZAHL = Pattern.compile("items[:;]?\\s*(\\d[\\d\\s.,]*)", Pattern.CASE_INSENSITIVE);

    private static final long GESAMT_MAX_MS = 60 * 60_000L;
    /**
     * Autor, 10.10. 21:07: "immer, wenn der Bot 50 Stigmas hinzugefuegt hat, den Ablauf ein wenig schneller machen --
     * das Maximale rausholen, bis es irgendwann gar nicht mehr klappt." Nach je 50 ohne Fehler: Pausen kuerzer.
     * Gab es Fehler (verschluckter Add, Fenster nicht rechtzeitig da): wieder langsamer. Das schnellste fehlerfreie
     * Tempo wird in stigma-tempo.txt gemerkt und gilt beim naechsten Start.
     */
    // Autor, 21:24: "nach jedem zehnten Stigma um 0,01 runter mit dem Tempo, und schau, was das Minimalste ist -- ohne
    // Fehler. Bei 0,1 ging er gar nicht mehr." Beim ersten Fehler: zurueck auf das letzte fehlerfreie Tempo + 0,02, dort
    // bleiben (gefunden).
    private static final int BLOCK = 10;
    private static final double SCHRITT_SCHNELLER = 0.01, SICHERHEIT = 0.02, FAKTOR_MIN = 0.5;   // Autor, 21:37: "fest auf 0,5 -- das war bis jetzt am besten. Und lass es auch so."

    /**
     * Autor, 10.10. 21:02: "erst mal langsamer, und schneller, wenn es fehlerfrei laeuft -- immer weiter, bis es
     * maximal schnell ist." Alle Pausen werden mit diesem Faktor gestreckt; Datei stigma-tempo.txt (z. B. 2 = doppelt
     * so lange, 1 = normal, 0,5 = halb so lange). Standard 2.
     */
    private static double tempo() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of("stigma-tempo.txt");
            if (java.nio.file.Files.exists(p)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(p)) {
                    z = z.trim().replace(',', '.');
                    if (!z.isEmpty() && !z.startsWith("#")) return Math.max(0.2, Math.min(5, Double.parseDouble(z)));
                }
            }
        } catch (Exception ignored) {
            // Standard
        }
        // Freunde (10.10.): ohne Datei gleich 0,5 wie bei Autor (21:37 "fest auf 0,5 -- das war bis jetzt am besten").
        // stigma-tempo.txt kommt mit einem Update nicht in den Bot-Ordner; bei Fehlern wird der Bot von selbst langsamer.
        return 0.5;
    }

    private double faktor = 2.0;

    private final java.util.Set<String> neueSets = new java.util.HashSet<>();
    private boolean tempoGefunden;
    private double letztesGutes = -1;

    /** Nach je 10 Adds: ohne Fehler 0,01 schneller; beim ersten Fehler zurueck aufs letzte fehlerfreie + 0,02 und bleiben. */
    private void tempoAnpassen() {
        double alt = faktor;
        if (blockFehler == 0) {
            letztesGutes = faktor;
            if (!tempoGefunden) {
                tempoMerken(faktor);
                faktor = Math.max(FAKTOR_MIN, Math.round((faktor - SCHRITT_SCHNELLER) * 100) / 100.0);
            }
        } else {
            double ziel = letztesGutes > 0 ? letztesGutes + SICHERHEIT : faktor + 0.1;
            faktor = Math.min(3, Math.round(Math.max(faktor + SICHERHEIT, ziel) * 100) / 100.0);
            if (!tempoGefunden) {
                tempoGefunden = true;
                Log.info(WER, Sprache.t("Fastest error-free speed found: " + letztesGutes + " -- continuing with " + faktor + ".",
                        "Schnellstes fehlerfreies Tempo gefunden: " + letztesGutes + " -- es geht mit " + faktor + " weiter."));
                tempoMerken(faktor);
            }
        }
        Log.info(WER, Sprache.t("Memory: " + gemerktTreffer + "x recognised, " + gemerktGelesen + "x read.",
                "Gedaechtnis: " + gemerktTreffer + "x wiedererkannt, " + gemerktGelesen + "x gelesen."));
        Log.info(WER, Sprache.t(BLOCK + " stigmas with " + blockFehler + " error(s) at speed " + alt
                + (faktor < alt ? " -- faster: " : faktor > alt ? " -- errors, slower: " : " -- stays: ") + faktor,
                BLOCK + " Stigmas mit " + blockFehler + " Fehler(n) bei Tempo " + alt
                + (faktor < alt ? " -- schneller: " : faktor > alt ? " -- Fehler, langsamer: " : " -- bleibt: ") + faktor));
        blockAdds = blockFehler = 0;
    }

    private static void tempoMerken(double f) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("stigma-tempo.txt"),
                    Sprache.t("# Speed for !stigmas: pauses x factor (the bot remembers the fastest error-free one here)\n",
                            "# Tempo fuer !stigmas: Pausen x Faktor (der Bot merkt sich hier das schnellste fehlerfreie)\n")
                    + String.valueOf(f).replace('.', ',') + "\n");
        } catch (Exception e) {
            Log.debug(WER, "stigma-tempo.txt nicht geschrieben: " + e.getMessage());
        }
    }

    private void ruhe(BotContext ctx, long ms) {
        ctx.sleep(Math.round(ms * faktor));
    }

    private enum Schritt { WEG, VORBEREITEN, KACHELN, ZERLEGEN, BESTAETIGEN, FERTIG, L_START, L_NAME, L_KACHELN, L_HUNDERT }

    private Schritt schritt;
    private boolean probe;
    private long begonnen;
    private int blind, wischer;
    private long karteUm;

    // Zaehlung
    private int vorgemerkt;          // eigene Adds
    private int quickAdd;            // durch die "+"-Knoepfe dazugekommen
    private int schonDrin;           // stand schon vor dem Start im Knopf "Disassemble (N)"
    private int zeiger;              // Platz in der sichtbaren Liste (Lesereihenfolge)
    private int fehlversuche;
    private int ohneBewegung;
    private final Map<String, Integer> geloescht = new LinkedHashMap<>();
    private final Map<String, Integer> behalten = new LinkedHashMap<>();
    /** Oberkante der zuletzt bearbeiteten Reihe (fuer das Weiterschieben). */
    private int letzteReiheOben = -1;
    private int zuletztVerglichen = -1;
    private boolean listeFertig;
    private int blockAdds, blockFehler;
    private int zerlegtGesamt;
    private int leerBlicke;
    private boolean quickErledigt;

    @Override
    public String endeHinweis() {
        // Autor 11.10. 02:10: "Du sollst mir keinen Text schicken, sondern einen Screenshot" -- beim Leveln kommen die
        // Bilder je Sorte; die Fertig-Meldung ist nur noch eine Zeile.
        return kurzBericht != null ? kurzBericht : bericht;
    }

    @Override public String id() { return "stigmas"; }

    /** 22.10. nachts: die Liste ganz nach oben schieben dauert ~27 s (40 Wischer) -- Haenger-Bericht erst nach 60 s. */
    @Override
    public long haengerBerichtNachS() {
        return 60;
    }

    @Override public String displayName() { return de.hustlebot.core.Sprache.t("Stigmas", "Stigmas"); }

    @Override public List<String> requiredTemplates() { return List.of(); }

    @Override
    public void reset() {
        schritt = Schritt.WEG;
        ohneAuftrag = !auftragNaechster;
        auftragNaechster = false;
        schliessenFolge = 0;   // Code-Pruefung 16.10.: sonst stand der Zaehler vom letzten Lauf noch (ggf. >= 30 -> sofort FATAL)
        probe = probeNaechster;
        probeNaechster = false;
        leveln = levelnNaechster;
        levelnNaechster = false;
        lStartName = startNameNaechster;
        startNameNaechster = 0;
        titan = de.hustlebot.core.Stigmaplan.titanFuer(titanNaechster);
        de.hustlebot.core.Stigmaplan.titanSetzen(titan.schluessel());
        titanNaechster = "lastguardian";
        hundert = hundertNaechster;
        nurGelevelte = nurGelevelteNaechster;
        nurHundert = nurHundertNaechster;
        hundertNaechster = nurGelevelteNaechster = nurHundertNaechster = false;
        hSlot = 0;
        hSteineAus = false;
        hErgebnis.clear();
        hRunde = 0;
        hRangErhoeht = 0;
        hBlockiert.clear();
        hStand.clear();
        lReset();
        // Autor, 11.10. 01:21: "erst mal so langsam, wie du nur kannst, dass du jedes Bild einzeln ablesen kannst."
        faktor = tempo();
        if (leveln) {
            // Autor 23.10.: gemerktes Filter-Tempo nehmen (siehe filterTempoLesen) -- nur beim allerersten Mal langsam anfangen.
            double[] gemerkt = filterTempoLesen();
            if (gemerkt != null) {
                faktor = gemerkt[0];
                lTempoFest = gemerkt[1] > 0;
                Log.info(WER, Sprache.t("Filter speed " + faktor + (lTempoFest ? " (found earlier, stays)." : " (still searching for the fastest)."),
                        "Filter-Tempo " + faktor + (lTempoFest ? " (frueher gefunden, bleibt so)." : " (die Suche nach dem schnellsten geht weiter).")));
            } else {
                faktor = Math.max(3.0, faktor);
            }
        }
        begonnen = System.currentTimeMillis();
        blind = wischer = 0;
        karteUm = 0;
        vorgemerkt = quickAdd = schonDrin = zeiger = fehlversuche = ohneBewegung = 0;
        geloescht.clear();
        behalten.clear();
        letzteReiheOben = -1;
        zuletztVerglichen = -1;
        leerBlicke = 0;
        quickErledigt = false;
        listeFertig = false;
        nurImFenster = false;
        blockAdds = blockFehler = 0;
        tempoGefunden = false;
        letztesGutes = -1;
        zerlegtGesamt = 0;
        fotos = 0;
        bericht = null;
        kurzBericht = null;
        Log.info(WER, leveln ? (probe ? Sprache.t("Levelling (dry run): " + titan.anzeige() + " -- only look, rate, take pictures; no Equip, no Increase.",
                                "Leveln (Probelauf): " + titan.anzeige() + " -- nur ansehen, bewerten, Fotos; kein Equip, kein Increase.")
                        : nurHundert ? Sprache.t("Level 100: " + titan.anzeige() + " -- level the equipped stigmas up as far as possible.",
                                "Level 100: " + titan.anzeige() + " -- die eingesetzten Stigmas so weit wie moeglich hochleveln.")
                        : Sprache.t("Levelling: " + titan.anzeige() + " -- look at Bull stigmas, equip better ones, level up good ones"
                        + (nurGelevelte ? " (only tiles from level 2)" : "") + (hundert ? ", then level 100." : "."),
                                "Leveln: " + titan.anzeige() + " -- Bull-Stigmas ansehen, bessere einsetzen, gute hochleveln"
                        + (nurGelevelte ? " (nur Kacheln ab Level 2)" : "") + (hundert ? ", danach Level 100." : ".")))
                : probe ? Sprache.t("Dry run: only look and write down, nothing is deleted.", "Probelauf: nur ansehen und aufschreiben, nichts wird geloescht.")
                // Freunde (Pruefung 11.10.): die behaltenen Sets sind einstellbar -- im Protokoll steht, was wirklich gilt.
                : Sprache.t("Delete stigmas: everything with level 1-79, except: " + (de.hustlebot.core.Stigmaplan.behalten().isEmpty()
                        ? "nothing (no set is kept)" : String.join(", ", new java.util.TreeSet<>(de.hustlebot.core.Stigmaplan.behalten()))) + ".",
                        "Stigmas loeschen: alles mit Level 1-79, ausser: " + (de.hustlebot.core.Stigmaplan.behalten().isEmpty()
                        ? "nichts (kein Set wird behalten)" : String.join(", ", new java.util.TreeSet<>(de.hustlebot.core.Stigmaplan.behalten()))) + "."));
    }

    @Override
    public boolean darfHeim() {
        return schritt != Schritt.BESTAETIGEN;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        if (ohneAuftrag) {
            Log.warn(WER, Sprache.t("No fresh stigma order (e.g. a restart with the last settings) -- nothing deleted and nothing levelled, to be safe. Please give the command again.",
                    "Kein frischer Stigma-Auftrag (z. B. Neustart mit den letzten Angaben) -- sicherheitshalber nichts geloescht und nichts gelevelt. Bitte den Befehl neu geben."));
            return Outcome.FINISHED;
        }
        if (System.currentTimeMillis() - begonnen > GESAMT_MAX_MS) {
            Log.error(WER, Sprache.t("Not finished after 60 minutes -- stopped.", "Nach 60 Minuten nicht fertig -- abgebrochen."));
            return Outcome.FATAL;
        }
        BufferedImage bild = ctx.frame();
        if (screen == null || bild == null) return Outcome.WAITING;
        return switch (schritt) {
            case WEG -> weg(screen, bild, ctx, runs);
            case VORBEREITEN -> vorbereiten(ctx, runs);
            case KACHELN -> kachel(ctx, runs);
            case ZERLEGEN -> zerlegen(ctx, runs);
            case BESTAETIGEN -> bestaetigen(ctx, runs);
            case L_START -> lStart(ctx, runs);
            case L_NAME -> lName(ctx, runs);
            case L_KACHELN -> lKachel(ctx, runs);
            case L_HUNDERT -> lHundert(ctx, runs);
            case FERTIG -> {
                nurImFenster = false;
                runs.completeRun();
                yield Outcome.FINISHED;
            }
        };
    }

    // ==========================================================================================================
    //  Der Weg bis "Disassembling items"
    // ==========================================================================================================

    private Outcome weg(Mat screen, BufferedImage bild, BotContext ctx, RunTracker runs) {
        // Freunde (Pruefung 11.10.): die Wege-Klicks unten (Tower, Karte) laufen nicht ueber tippe() -- bei Stopp/Pause gar
        // nicht erst anfangen.
        if (BotEngine.stoppAngefordert() || BotEngine.pauseAngefordert()) return Outcome.WAITING;
        // Autor 23.10.: "Beim Hammerhand Bull-Stigmas filtern geht nicht, weil er kein Archer ist -- Bull nur fuer Archer."
        if (leveln && !nurHundert && "tank".equals(titanKlasse(titan.titelWort()))) {
            Log.error(WER, Sprache.t(titan.anzeige() + " is a tank -- bull stigmas only fit archers. Nothing is filtered.",
                    titan.anzeige() + " ist ein Tank -- Bull-Stigmas passen nur auf Archer. Es wird nichts gefiltert."));
            Log.hinweis("Stigmas", Sprache.t("\"" + titan.anzeige() + "\" is a tank (shield top left). Bull stigmas only fit "
                            + "archer titans (bow top left) -- choose an archer.",
                    "\"" + titan.anzeige() + "\" ist ein Tank (Schild oben links). Bull-Stigmas passen nur auf Archer-Titanen "
                            + "(Bogen oben links) -- bitte einen Archer waehlen."));
            return Outcome.FATAL;
        }
        if (Stoerfenster.afkUpdate(screen, ctx, WER)) return Outcome.ACTED;
        if (raysSchliessen(ctx, bild)) return Outcome.ACTED;
        if (leveln && titelPasst(lies(bild, K_TITAN_NAME))) {
            blind = 0;
            Log.info(WER, Sprache.t(titan.anzeige() + " is open.", titan.anzeige() + " ist offen."));
            schritt = nurHundert ? Schritt.L_HUNDERT : Schritt.L_START;
            return Outcome.ACTED;
        }
        // Ein anderer Titan ist offen (11.10. 01:13: der Last Guardian stand nicht mehr in der Mitte): mit dem Pfeil
        // "Switch Titans" weiterblaettern, bis oben "Last Guardian" steht.
        if (leveln && enthaelt(lies(bild, K_SWITCH_TITANS), "switch")) {
            blind = 0;
            // Freunde: mehrmals (sie koennen mehr oder andere Titanen haben als Autors fuenf) -- und sagen, warum Schluss ist.
            // Autor 23.10.: jetzt jeder seiner 41 Titanen waehlbar -- "Switch Titans" geht durch alle, darum bis 45 Mal.
            if (++titanBlaettern > TITAN_BLAETTERN_MAX) {
                Log.error(WER, Sprache.t(titan.anzeige() + " not found among the titans (switched " + TITAN_BLAETTERN_MAX + " times).",
                        titan.anzeige() + " unter den Titanen nicht gefunden (" + TITAN_BLAETTERN_MAX + " Mal weitergeblaettert)."));
                Log.hinweis("Stigmas", de.hustlebot.core.Sprache.t(
                        "Titan \"" + titan.anzeige() + "\" not found (switched " + TITAN_BLAETTERN_MAX + " times). Write the name as it stands above the "
                                + "titan in the game (English), e.g. `!stigmas level last guardian`.",
                        "Titan \"" + titan.anzeige() + "\" nicht gefunden (" + TITAN_BLAETTERN_MAX + " Mal weitergeblaettert). Den Namen so schreiben, wie "
                                + "er im Spiel ueber dem Titan steht (Englisch), z. B. `!stigmas leveln last guardian`."));
                return Outcome.FATAL;
            }
            // 11.10.: nach links (Reihenfolge ab Last Guardian: Thundersaurus, Keeper of Stars, Torturer, Medusa).
            // 23.10.: Steht die Reihenfolge aller Titanen fest (templates/gelernt/titanen/titanen.txt), der kuerzere Weg.
            String oben = lies(bild, K_TITAN_NAME);
            boolean rechts = titanNachRechts(oben, titan.titelWort());
            tippe(ctx, rechts ? SWITCH_RECHTS : SWITCH_LINKS, Sprache.t("Other titan (" + (rechts ? "->" : "<-") + " Switch Titans), looking for "
                    + titan.anzeige() + ", the top said \"" + oben + "\"",
                    "Anderer Titan (" + (rechts ? "->" : "<-") + " Switch Titans), gesucht " + titan.anzeige() + ", oben stand \"" + oben + "\""));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        if (sichtbar(screen, ctx, TitanTurmMode.T_TITEL)) {
            blind = 0;
            tippe(ctx, TITAN_KNOPF, Sprache.t("To the titans (purple button on the left in the tower)", "Zu den Titanen (lila Knopf links im Turm)"));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        if (sichtbar(screen, ctx, TitanTurmMode.T_AKTIVITAET) || sichtbar(screen, ctx, TitanTurmMode.T_AKTIVITAET_TITEL)) {
            blind = 0;
            var schrift = ctx.matcher().has(TitanTurmMode.T_AKTIVITAET)
                    ? ctx.matcher().find(screen, TitanTurmMode.T_AKTIVITAET) : java.util.Optional.<de.hustlebot.core.Matcher.Hit>empty();
            if (schrift.isEmpty()) return Outcome.WAITING;
            int x = schrift.get().centerX(), y = Math.max(0, schrift.get().centerY() - (int) (ctx.height() * 0.12));
            Log.action("Klick", WER + Sprache.t(": choose Tower (on the crest above the text)  [", ": Tower waehlen (aufs Wappen ueber der Schrift)  [") + x + "," + y + "]");
            ctx.input().click(x, y);
            ctx.sleepUntilChanged(2500);
            return Outcome.ACTED;
        }
        if (sichtbar(screen, ctx, TitanTurmMode.T_KARTE_TURM)) {
            blind = 0;
            if (ctx.clickIfVisibleIfKnown(screen, TitanTurmMode.T_KARTE_TURM, WER + Sprache.t(": Tower on the world map", ": Tower auf der Weltkarte"))) {
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }
        long jetzt = System.currentTimeMillis();
        if (sichtbar(screen, ctx, "map_button") || sichtbar(screen, ctx, "map_button_alt")) {
            blind = 0;
            if (jetzt - karteUm < 2500) {
                ctx.sleep(200);
                return Outcome.WAITING;
            }
            karteUm = jetzt;
            wischer = 0;
            if (ctx.clickIfVisibleIfKnown(screen, "map_button_alt", WER + Sprache.t(": open world map", ": Weltkarte oeffnen"))
                    || ctx.clickIfVisibleIfKnown(screen, "map_button", WER + Sprache.t(": open world map", ": Weltkarte oeffnen"))) {
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }
        // Die Titan-Bildschirme haben keine Vorlagen -- ihre Ueberschriften werden gelesen. Der Reihe nach:
        // Zerlegen und Tasche liegen ueber "Titan Squad" (dessen Leiste unten bleibt sichtbar).
        // "disassemb": ein offenes Stigma-Fenster verdeckt den Rest (15.10.).
        if (enthaelt(lies(bild, K_ZERLEGEN), "disassemb")) {
            blind = 0;
            if (leveln) {
                tippe(ctx, ZERLEGEN_BACK, Sprache.t("Close disassembling (Back) -- for levelling", "Zerlegen schliessen (Back) -- zum Leveln"))
                ;
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            Log.info(WER, Sprache.t("\"Disassembling items\" is open.", "\"Disassembling items\" ist offen."));
            nurImFenster = true;
            schritt = Schritt.VORBEREITEN;
            return Outcome.ACTED;
        }
        if (enthaelt(lies(bild, K_INVENTAR), "inventory")) {
            blind = 0;
            if (leveln) {
                tippe(ctx, INVENTAR_X, Sprache.t("Close titan inventory -- for levelling", "Titan-Tasche schliessen -- zum Leveln"));
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            tippe(ctx, MUELLEIMER, Sprache.t("Open disassembling (green button bottom right)", "Zerlegen oeffnen (gruener Knopf unten rechts)"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        // Autor 23.10.: "Nicht auf meine Einheiten druecken -- erst auf Titan Collection gehen, dort Heavyweight suchen, drauf
        // druecken und dann erst Stigmas." Aus dem Trupp geoeffnet blaettert "Switch Titans" nur durch die 5 Trupp-Titanen
        // (Heavyweight kam nie), aus der Collection durch alle. Die Karte wird am Motiv erkannt (tc_<name>.png).
        if (leveln && enthaelt(lies(bild, K_COLLECTION), "collection")) {
            blind = 0;
            String karte = "tc_" + titanSlug(titan.anzeige());
            if (ctx.matcher().has(karte)) {
                var treffer = ctx.find(screen, karte);
                if (treffer.isPresent()) {
                    tippeXY(ctx, treffer.get().centerX(), treffer.get().centerY(), Sprache.t(titan.anzeige() + " in the Titan Collection (card)",
                            titan.anzeige() + " in der Titan Collection (Karte)"));
                    ctx.sleepUntilChanged(1500);
                    return Outcome.ACTED;
                }
                if (++collectionWische <= 8) {
                    Log.action("Klick", Sprache.t("Stigmas: " + titan.anzeige() + " not visible yet -- scrolling the Titan Collection ("
                            + collectionWische + ")", "Stigmas: " + titan.anzeige() + " noch nicht zu sehen -- Titan Collection weiterschieben (" + collectionWische + ")"));
                    ctx.input().drag(px(ctx, 580), py(ctx, 640), px(ctx, 580), py(ctx, 180));
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
            }
            // Keine Karte (anderer Titan, keine Vorlage): den Titan oben links oeffnen -- von dort geht Switch Titans durch alle.
            if (++squadTipps > 5) {
                Log.error(WER, Sprache.t(titan.anzeige() + " not found in the Titan Collection.", titan.anzeige() + " in der Titan Collection nicht gefunden."));
                return Outcome.FATAL;
            }
            tippe(ctx, ERSTE_KARTE, Sprache.t("First titan at the top left of the Titan Collection, then switch",
                    "Ersten Titan oben links in der Titan Collection oeffnen, dann blaettern"));
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }
        String unten = lies(bild, K_SQUAD_LEISTE) + " " + lies(bild, K_SQUAD);
        if (enthaelt(unten, "squad") || enthaelt(unten, "patrons")) {
            blind = 0;
            if (leveln && !titan.schluessel().equals("lastguardian")) {
                // Autor 23.10.: andere Titanen immer ueber die Titan Collection (siehe oben), nie ueber den Trupp.
                if (++squadTipps > 5) {
                    Log.error(WER, Sprache.t("\"Titan Collection\" did not open (tapped 5 times).", "\"Titan Collection\" ging nicht auf (5 Mal getippt)."));
                    return Outcome.FATAL;
                }
                tippe(ctx, TITAN_COLLECTION, Sprache.t("Titan Collection (button at the bottom)", "Titan Collection (Knopf unten)"));
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            if (leveln) {
                // Freunde (Pruefung 11.10.): oeffnet sich hier nie ein Titan (keiner in der Mitte, kein Switch-Knopf),
                // tippte der Bot bis zum 15-Minuten-Totmann weiter. Jetzt nach 5 Versuchen dieselbe klare Meldung wie
                // beim Blaettern.
                if (++squadTipps > 5) {
                    Log.error(WER, Sprache.t(titan.anzeige() + " not found: on \"Titan Squad\" no titan opened (tapped 5 times).",
                            titan.anzeige() + " nicht gefunden: auf \"Titan Squad\" ging kein Titan auf (5 Mal getippt)."));
                    Log.hinweis("Stigmas", de.hustlebot.core.Sprache.t(
                            "Titan \"" + titan.anzeige() + "\" not found: on \"Titan Squad\" no titan opened (tapped 5 times). "
                                    + "Open the titan in the game once by hand and try again, or write the name as it stands "
                                    + "above the titan (English), e.g. `!stigmas level last guardian`.",
                            "Titan \"" + titan.anzeige() + "\" nicht gefunden: auf \"Titan Squad\" ging kein Titan auf (5 Mal "
                                    + "getippt). Den Titan im Spiel einmal von Hand oeffnen und noch einmal versuchen, oder den "
                                    + "Namen so schreiben, wie er ueber dem Titan steht (Englisch), z. B. `!stigmas leveln last guardian`."));
                    return Outcome.FATAL;
                }
                // Autor: "Last Guardian, der mittlere".
                // Autor, 11.10. 01:15: "am Aussehen erkennen, nicht an der Position" -- Gesicht (Kapuze, lila Augen).
                int[] lg = lastGuardianSuchen(screen);
                if (lg != null) {
                    tippeXY(ctx, lg[0], lg[1], Sprache.t("Last Guardian recognised by his face", "Last Guardian am Gesicht erkannt"));
                } else {
                    tippe(ctx, LAST_GUARDIAN, Sprache.t("Last Guardian not recognised by his face -- open a titan, then switch",
                            "Last Guardian nicht am Gesicht erkannt -- einen Titan oeffnen, dann blaettern"));
                }
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            tippe(ctx, TRUHE, Sprache.t("Open titan inventory (chest on the left)", "Titan-Tasche oeffnen (Truhe links)"));
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        // 11.10. 01:13: NUR die erkannte Weltkarte -- "kurz nach dem Map-Klick" galt jeder fremde Bildschirm als Karte,
        // und der Bot wischte auf einem Titan-Bildschirm herum.
        if (Weltkarte.erkannt(screen, ctx)) {
            blind = 0;
            if (jetzt - karteUm < 1500) {
                ctx.sleep(200);
                return Outcome.WAITING;
            }
            if (wischer < 12) {
                wischer++;
                boolean nachRechts = wischer <= 3;
                int y = ctx.height() / 2, links = (int) (ctx.width() * 0.25), rechts = (int) (ctx.width() * 0.75);
                Log.action("Karte", WER + Sprache.t(": drag the world map to find the Tower (drag " + wischer + ").",
                        ": Weltkarte ziehen, um den Tower zu finden (Zug " + wischer + ")."));
                if (nachRechts) ziehe(ctx, false, links, y, rechts, y);
                else ziehe(ctx, false, rechts, y, links, y);
                ctx.sleepUntilChanged(400);
                return Outcome.ACTED;
            }
            Log.error(WER, Sprache.t("The Tower was not found on the world map.", "Der Tower wurde auf der Weltkarte nicht gefunden."));
            return Outcome.FATAL;
        }
        if (++blind > 40) {
            Log.error(WER, Sprache.t("Screen not recognised -- stopped.", "Bildschirm nicht erkannt -- abgebrochen."));
            return Outcome.FATAL;
        }
        ctx.sleep(400);
        return Outcome.WAITING;
    }

    // ==========================================================================================================
    //  Im Zerlegen-Fenster
    // ==========================================================================================================

    private Outcome vorbereiten(BotContext ctx, RunTracker runs) {
        if (!quickErledigt) {
            quickErledigt = true;
            // Autor, 10.10. 21:00 (Bild, nummeriert): 1 Stigma-Zeichen unter "Quick add", 2 rechtes "+", 3 linkes "+",
            // 4 Reiter Stigmas, 5 erst dann die Stigmas. "Es gaebe auch Stigmas in blau und episch ... trotzdem auf den
            // Knopf druecken." Im Probelauf wird nichts dazugelegt (2 und 3 fallen weg).
            int vorher = probe ? -1 : knopfZahl(foto(ctx));
            tippe(ctx, QUICK_STIGMA, Sprache.t("Quick add: stigma symbol (1)", "Quick add: Stigma-Zeichen (1)"));
            ruhe(ctx, 500);
            if (!probe) {
                tippe(ctx, PLUS_LILA, Sprache.t("Quick add: right + (2)", "Quick add: rechtes + (2)"));
                ruhe(ctx, 900);
                tippe(ctx, PLUS_BLAU, Sprache.t("Quick add: left + (3)", "Quick add: linkes + (3)"));
                ruhe(ctx, 900);
                int nachher = knopfZahl(foto(ctx));
                // 22.10. nachts (Spiel eben neu geladen): der Knopf zeigte erst 0, spaeter 29 -- 9 Teile kamen verzoegert
                // dazu, der Vergleich nach 20 Adds brach ab. Jetzt lesen, bis zweimal hintereinander dieselbe Zahl steht.
                for (int i = 0; i < 6; i++) {
                    ruhe(ctx, 500);
                    int nochmal = knopfZahl(foto(ctx));
                    if (nochmal == nachher) break;
                    nachher = nochmal;
                }
                // Was schon vorher im Knopf stand (Autor hatte 21:04 selbst eins dazugelegt), zaehlt mit.
                schonDrin = Math.max(0, vorher);
                quickAdd = Math.max(0, nachher - schonDrin);
                Log.info(WER, Sprache.t("Quick add: " + quickAdd + " items added (button: " + (nachher < 0 ? "no number" : nachher) + ").",
                        "Quick add: " + quickAdd + " Teile dazugelegt (Knopf: " + (nachher < 0 ? "ohne Zahl" : nachher) + ")."));
            }
        }
        tippe(ctx, REITER_STIGMA, Sprache.t("Stigmas tab (4th tab on the right)", "Reiter Stigmas (4. Reiter rechts)"));
        ruhe(ctx, 700);
        BufferedImage b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        if (!reiterStigmaAktiv(b)) {
            if (++fehlversuche > 3) {
                Log.error(WER, Sprache.t("The Stigmas tab cannot be selected -- stopped.", "Der Reiter Stigmas laesst sich nicht waehlen -- abgebrochen."));
                return Outcome.FATAL;
            }
            return Outcome.ACTED;
        }
        fehlversuche = 0;
        // Autor, 10.10. 21:15: "Kannst du wieder nach oben scrollen ... du startest in der Mitte." Die Liste immer von
        // ganz oben anfangen: ein paar schnelle Wischer nach unten (die Liste rollt dabei nach oben).
        // 21:43: 6 Wischer reichten bei der langen Liste nicht bis oben (Hydra/Unicorn oberhalb blieben ungesehen).
        // Jetzt: wischen, bis sich die Liste nicht mehr bewegt (hoechstens 60 Mal).
        BufferedImage vorWisch = foto(ctx);
        for (int i = 0; i < 60 && vorWisch != null; i++) {
            ziehe(ctx, true, px(ctx, 1110), py(ctx, 230), px(ctx, 1110), py(ctx, 650));
            ctx.sleep(i < 3 ? 150 : 450);
            BufferedImage nach = foto(ctx);
            if (nach == null) break;
            if (i >= 2 && listeVerschieden(vorWisch, nach, ctx) < 0.01) {
                Log.info(WER, Sprache.t("List at the very top (after " + (i + 1) + " swipes).", "Liste ganz oben (nach " + (i + 1) + " Wischern)."));
                break;
            }
            vorWisch = nach;
        }
        ruhe(ctx, 600);
        runs.progress(Sprache.t("Stigmas: going through the list", "Stigmas: Liste wird durchgesehen"));
        schritt = Schritt.KACHELN;
        zeiger = 0;
        return Outcome.ACTED;
    }

    /** Eine Kachel: antippen, lesen, entscheiden. */
    private Outcome kachel(BotContext ctx, RunTracker runs) {
        BufferedImage b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        if (fensterOffen(b)) {
            // Aus Versehen die Warnung "Are you sure ...?" (ein Add ins Leere trifft "Disassemble"): immer "No".
            if (enthaelt(lies(b, K_WARNUNG), "sure")) {
                tippe(ctx, NEIN, Sprache.t("Warning too early -- No", "Warnung zu frueh -- No"));
                ruhe(ctx, 600);
                return Outcome.ACTED;
            }
            // Noch ein Stigma-Fenster offen (z. B. nach einem verschluckten Tipp): erst zu.
            // Bremse (Hauptbot 02bed78): geht das "Fenster" nach 8 Tipps nicht zu, Foto; nach 30 Abbruch statt Endlosschleife.
            if (++schliessenFolge == 8) fotoSichern(b, "fenster-bleibt");
            if (schliessenFolge >= 30) {
                Log.error(WER, Sprache.t("The stigma window did not close after 30 taps -- run stopped.",
                        "Das Stigma-Fenster ging nach 30 Tipps nicht zu -- Lauf abgebrochen."));
                return Outcome.FATAL;
            }
            tippe(ctx, SCHLIESSEN, Sprache.t("Close stigma window", "Stigma-Fenster schliessen"));
            ruhe(ctx, 400);
            return Outcome.ACTED;
        }
        schliessenFolge = 0;
        // Hauptbot (Code-Review 15.10.): Warf das Spiel den Bot hinaus (Burg), tippte er weiter auf die Kachel-Stellen.
        // Jeder achte Blick prueft, ob "Disassembling items" noch offen ist (die Texterkennung kostet Zeit).
        if (kachelBlicke++ % 8 == 0 && !zerlegenOffen(b)) return zerlegenVerloren("Kacheln");
        // Ab und zu die eigene Zaehlung mit dem Knopf "Disassemble (N)" vergleichen.
        if (!probe && vorgemerkt > 0 && vorgemerkt % 20 == 0 && vorgemerkt != zuletztVerglichen) {
            zuletztVerglichen = vorgemerkt;
            int imKnopf = knopfZahl(b), erwartet = schonDrin + vorgemerkt + quickAdd;
            // 22.10. nachts: direkt nach dem 20. Add las die Texterkennung "29" statt "20" -- Abbruch ohne Grund. Erst noch
            // bis zu dreimal frisch lesen; abgebrochen wird nur, wenn der Knopf dabei bleibt.
            for (int i = 0; i < 3 && imKnopf > erwartet; i++) {
                ruhe(ctx, 300);
                BufferedImage n = foto(ctx);
                int nochmal = knopfZahl(n);
                Log.info(WER, Sprache.t("Button read again: " + nochmal + " (counted " + erwartet + ").",
                        "Knopf noch einmal gelesen: " + nochmal + " (gezaehlt " + erwartet + ")."));
                if (nochmal >= 0) imKnopf = nochmal;
            }
            if (imKnopf > erwartet) {
                fotoSichern(b, "knopf-zahl");
                Log.error(WER, Sprache.t("The button says " + imKnopf + ", counted are " + erwartet
                        + " -- something foreign is in there. Stopped, NOT disassembled.",
                        "Im Knopf stehen " + imKnopf + ", gezaehlt sind " + erwartet
                        + " -- da liegt etwas Fremdes drin. Abgebrochen, NICHT zerlegt."));
                return Outcome.FATAL;
            }
            // 21:43: die Zahl war einmal unlesbar (0) -- daraus wurde "gezaehlt 0", und der naechste Vergleich brach ab.
            // Unlesbar (0 oder weniger) wird ignoriert; nach unten korrigiert wird nur bei hoechstens 3 Unterschied.
            if (imKnopf > 0 && imKnopf < erwartet && erwartet - imKnopf <= 3) {
                Log.warn(WER, Sprache.t("The button says " + imKnopf + ", counted " + erwartet + " -- an Add was lost, "
                        + "the count is adjusted.",
                        "Im Knopf stehen " + imKnopf + ", gezaehlt " + erwartet + " -- ein Add ging verloren, "
                        + "die Zaehlung wird angepasst."));
                vorgemerkt = Math.max(0, imKnopf - quickAdd - schonDrin);
            }
        }
        List<Integer> reihen = reihen(b, ctx);
        List<int[]> plaetze = new ArrayList<>();
        for (int oben : reihen) {
            for (int s = 0; s < SPALTEN.length; s++) {
                if (orange(b, px(ctx, REIHEN_X + 108 * s), oben + py(ctx, 15))) {
                    plaetze.add(new int[]{px(ctx, SPALTEN[s]), oben + py(ctx, KACHEL_MITTE), oben});
                }
            }
        }
        if (plaetze.isEmpty() && ++leerBlicke <= 6) {
            // 10.10. 20:54: direkt nach dem Schliessen eines Fensters ist die Liste noch abgedunkelt -- keine Kachel
            // erkannt, und der Bot meldete "Ende der Liste" nach dem ersten Stigma. Erst noch einmal hinsehen.
            ruhe(ctx, 250);
            return Outcome.WAITING;
        }
        if (plaetze.isEmpty()) fotoSichern(b, "keine-kacheln");
        leerBlicke = 0;
        if (zeiger >= plaetze.size()) {
            // Alles Sichtbare ist durch: weiterschieben. Ob die Liste zu Ende ist, zeigt erst das Schieben.
            return weiterschieben(ctx, b, reihen);
        }
        // Behaltene Sets (Bull, Dragon, Wolf ...) am Bild erkennen und gar nicht erst oeffnen -- sie bleiben ja ohnehin.
        // Autor 23.10.: "Der Bot macht das bei den anderen Stigmas auch: drueckt drauf und wieder raus."
        java.util.Set<String> schutz = de.hustlebot.core.Stigmaplan.behalten();
        int uebersprungen = 0;
        String erkannt;
        while (zeiger < plaetze.size() && (erkannt = schutzKachel(b, plaetze.get(zeiger), schutz)) != null) {
            behalten.merge(Sprache.t(erkannt + " (recognised from the picture, tiles)", erkannt + " (am Bild erkannt, Kacheln)"), 1, Integer::sum);
            zeiger++;
            uebersprungen++;
        }
        if (uebersprungen > 0) {
            runs.progress(Sprache.t("Stigmas: " + uebersprungen + " kept skipped", "Stigmas: " + uebersprungen + " behaltene uebersprungen"));
            if (zeiger >= plaetze.size()) return weiterschieben(ctx, b, reihen);
        }
        int[] p = plaetze.get(zeiger);
        letzteListe = b;
        letzterPlatz = p;   // letzterKopfWert gilt schon fuer diese Kachel (letzte Pruefung der Schleife oben)
        tippeXY(ctx, p[0], p[1], Sprache.t("Open stigma no. " + (zeiger + 1) + " on the screen", "Stigma Nr. " + (zeiger + 1) + " auf dem Bildschirm oeffnen"));
        letzteReiheOben = p[2];

        // Auf das Fenster warten und lesen.
        String titel = null;
        BufferedImage f = null;
        for (int i = 0; i < 6 && titel == null; i++) {
            ruhe(ctx, i == 0 ? 250 : 200);
            f = foto(ctx);
            // Erst lesen, wenn das Fenster ganz steht (der gruene "Add" ist da) -- ein Add waehrend des Einblendens
            // wird verschluckt (Aufnahme 105,2 s).
            if (f != null && fensterOffen(f) && addSichtbar(f)) {
                String t = liesGemerkt(f, K_TITEL, titelGedaechtnis, TITEL);
                if (TITEL.matcher(t).find()) titel = t;
            }
        }
        if (titel == null) {
            blockFehler++;
            if (++fehlversuche > 4) {
                Log.warn(WER, Sprache.t("At no. " + (zeiger + 1) + " no readable window opened -- skipped.",
                        "Bei Nr. " + (zeiger + 1) + " ging kein lesbares Fenster auf -- uebersprungen."));
                if (f != null && fensterOffen(f)) tippe(ctx, SCHLIESSEN, Sprache.t("Close window", "Fenster schliessen"));
                fehlversuche = 0;
                zeiger++;
            }
            return Outcome.ACTED;
        }
        fehlversuche = 0;
        Matcher m = TITEL.matcher(titel);
        m.find();
        // Ein falscher Buchstabe ist erlaubt (21:21: "Elephant Witt" statt "Will"). Geschuetzte und loeschbare Sets liegen
        // so weit auseinander, dass daraus nie ein Bull/Dragon/Wolf zu einem loeschbaren wird.
        String set = aehnlich(m.group(1), ALLE_SETS, 1), name = aehnlich(m.group(2), NAMEN, 2);   // 21:34 "tiger witt" = Will
        // 10.10. 21:18 bei Tempo 0,1: "Crocodile Tenacity (0 Sterne)" -- das Fenster stand, die Sterne noch nicht. Jedes
        // Stigma hat mindestens 1 Stern und ein lesbares Level: sonst noch einmal hinsehen (und das zaehlt als Fehler,
        // damit das Tempo wieder etwas langsamer wird).
        int sterne = sterne(f, ctx), level = -1;
        for (int i = 0; i < 5; i++) {
            Matcher lv = LEVEL.matcher(liesGemerkt(f, K_LEVEL, levelGedaechtnis, LEVEL));
            level = lv.find() ? Integer.parseInt(lv.group(1)) : -1;
            if (sterne >= 1 && level >= 1) break;
            if (i == 0) blockFehler++;
            ctx.sleep(80);
            BufferedImage g = foto(ctx);
            if (g == null || !fensterOffen(g)) break;
            f = g;
            sterne = sterne(f, ctx);
        }
        String wer = Sprache.t(set + " " + name + " (" + sterne + " star" + (sterne == 1 ? "" : "s") + ", level " + (level < 0 ? "?" : level) + ")",
                set + " " + name + " (" + sterne + " Stern" + (sterne == 1 ? "" : "e") + ", Level " + (level < 0 ? "?" : level) + ")");

        // Autor, 10.10. 21:16: "Wolf-Stigmas sollst du auch behalten." Dragon nie mit Eagle/Phoenix verwechseln -- deshalb
        // entscheidet immer der gelesene Titel, nie das Bild.
        // Autor, 21:41: "alles ausser Bull, Dragon und Wolf" (beim ersten Lauf tauchten 11 weitere Sets auf: Hydra, Unicorn,
        // Boar, Bear, Lion, Scorpion, Rat, Snake, Turtle, Bat, Spider). Darum: geloescht wird jedes Set, das NICHT wie ein
        // geschuetztes aussieht -- und "wie" ist grosszuegig: bis zu 2 Buchstaben Unterschied ("Bul", "Draqon", "Wo1f")
        // zaehlt als geschuetzt. Ein Lesefehler fuehrt so nie zum Loeschen eines Bull/Dragon/Wolf.
        String setKlein = set.toLowerCase(Locale.ROOT);
        boolean schuetzen = false;
        // 11.10.: welche Sets bleiben, steht unter !stigmas einstellen (Standard: Bull, Dragon, Wolf).
        for (String g : de.hustlebot.core.Stigmaplan.behalten()) {
            // Pruefung 11.10.: "Bear" behalten schuetzte auch "Boar" (1 Buchstabe Unterschied). Ein genau gelesener
            // Set-Name entscheidet selbst; nur unsicher gelesene ("Bul", "Wo1f") werden grosszuegig geschuetzt.
            if (de.hustlebot.core.Stigmaplan.SETS.contains(setKlein) ? g.equals(setKlein) : abstand(setKlein, g) <= 2) schuetzen = true;
        }
        // Loeschen nur bei sauber gelesenem Titel: ein Wort aus 3-12 Buchstaben und einer der 6 Namen.
        boolean bekannt = setKlein.matches("[a-z]{3,12}") && NAMEN.contains(name.toLowerCase(Locale.ROOT));
        if (bekannt) sorteMerken(set, name, f, letzteListe, letzterPlatz);   // Autor 23.10.: jede neue Sorte einmal merken
        if (bekannt && !schuetzen && !LOESCHBAR.contains(setKlein) && neueSets.add(setKlein)) {
            // 22.10. nachts: die Meldung nannte fest "Bull/Dragon/Wolf" -- es gilt die eingestellte Liste (!stigmas einstellen).
            String bleiben = String.join("/", new java.util.TreeSet<>(de.hustlebot.core.Stigmaplan.behalten()));
            Log.info(WER, Sprache.t("New set \"" + set + "\" -- deleted (everything except " + bleiben + ").",
                    "Neues Set \"" + set + "\" -- wird geloescht (alles ausser " + bleiben + ")."));
        }
        // Hauptbot (Code-Review 15.10.): Die Sterne wurden nur einmal gelesen. Stand der zweite Stern noch nicht da, galt ein
        // 2-Sterne-Stigma als 1 Stern -- und waere zerlegt worden. Vor dem Loeschen deshalb ein zweiter Blick.
        // Autor 23.10.: "Wenn ich eingebe, dass er Krokodil-Stigmas loeschen soll, dann soll er es loeschen, egal welches
        // Level es hat. Ausser es hat Level 80 oder hoeher." Bisher galt nur Level 1 (sein Krokodil Lv. 6 blieb liegen).
        boolean levelPasst = level >= 1 && level < LOESCH_LEVEL_AB;
        // Code-Review 23.10.: "genau 1 Stern" deckelte das Loeschen auf Level 1-20 (Sterne = Rang, je 20 Level einer) --
        // Autors "egal welches Level" galt so nie. Jetzt jedes Level unter 80, wenn Sterne und Level zusammenpassen
        // (sternePassen) -- ein falsch gelesenes Level ("100" als "10") fuehrt so nie zum Loeschen.
        if (!schuetzen && bekannt && sternePassen(sterne, level) && levelPasst) {
            ruhe(ctx, 120);   // Code-Review 23.10.: mit dem Tempo skaliert (vorher fest 120 ms je Loeschung)
            BufferedImage g = foto(ctx);
            if (g != null && fensterOffen(g)) {
                int nochmal = sterne(g, ctx);
                if (nochmal > sterne) {
                    Log.info(WER, Sprache.t(set + " " + name + ": " + nochmal + " stars at second look -- stays.",
                            set + " " + name + ": beim zweiten Blick " + nochmal + " Sterne -- bleibt liegen."));
                    sterne = nochmal;
                }
            }
        }
        boolean loeschen = !schuetzen && bekannt && sternePassen(sterne, level) && levelPasst;
        runs.progress("Stigmas: " + wer);
        if (loeschen && !probe) {
            Log.action("Klick", WER + ": " + wer + Sprache.t(" -> add for disassembling (Add)", " -> zum Zerlegen dazulegen (Add)"));
            tippe(ctx, ADD, null);
            // Warten, bis das Fenster zu ist; die Liste rueckt nach, der Zeiger bleibt stehen. Bleibt es offen,
            // war der Tipp verschluckt: nicht zaehlen -- der naechste Schritt schliesst es und versucht es neu.
            boolean zu = false;
            for (int i = 0; i < 8 && !zu; i++) {
                ruhe(ctx, 150);
                BufferedImage g = foto(ctx);
                zu = g != null && !fensterOffen(g);
            }
            if (zu) {
                vorgemerkt++;
                zaehle(geloescht, set);
                if (++blockAdds >= BLOCK) tempoAnpassen();
            } else {
                blockFehler++;
                Log.info(WER, Sprache.t("Add did not arrive (window still open) -- error " + blockFehler + " in this block.",
                        "Add nicht angekommen (Fenster noch offen) -- Fehler " + blockFehler + " in diesem Block."));
            }
            return Outcome.ACTED;
        }
        String grund = schuetzen ? Sprache.t(set + " always stays", set + " bleibt immer") : loeschen ? Sprache.t("dry run", "Probelauf")
                : !bekannt ? Sprache.t("unknown set or read uncertainly", "unbekanntes Set oder unsicher gelesen")
                : Sprache.t("more than 1 star or level " + LOESCH_LEVEL_AB + "+ (or not readable)",
                        "mehr als 1 Stern oder Level " + LOESCH_LEVEL_AB + "+ (oder nicht lesbar)");
        Log.info(WER, wer + Sprache.t(" -> stays (", " -> bleibt (") + grund + ").");
        // Nur bei sauber gelesenem Set-Namen lernen -- ein "Bul" soll keine eigene Vorlagen-Familie anlegen.
        if (schuetzen && de.hustlebot.core.Stigmaplan.SETS.contains(setKlein) && letzterKopfWert < BULL_GRENZE) {
            Log.debug(WER, setKlein + "-Kopf auf der Kachel nicht erkannt (bester Wert " + Math.round(letzterKopfWert * 100) / 100.0
                    + ", Reihe oben bei " + letzteReiheOben + ").");
            kopfLernen(setKlein, letzteListe, letzterPlatz);
        }
        if (loeschen) zaehle(geloescht, set);   // Probelauf: wuerde geloescht
        else zaehle(behalten, set + (schuetzen ? "" : !bekannt ? Sprache.t(" (unknown: \"", " (unbekannt: \"") + titel.trim() + "\")" : Sprache.t(" (stars/level)", " (Sterne/Level)")));
        tippe(ctx, SCHLIESSEN, null);
        zeiger++;
        for (int i = 0; i < 8; i++) {
            ruhe(ctx, 120);
            BufferedImage g = foto(ctx);
            if (g != null && !fensterOffen(g)) break;
        }
        ruhe(ctx, 150);
        return Outcome.ACTED;
    }

    /**
     * Die sichtbaren Kacheln sind durch: die Liste um gut zwei Reihen weiterschieben (340 px, KEIN Vielfaches des
     * Reihenabstands 136 -- im Bull-Block sehen alle Reihen gleich aus, ein Schub um genau 3 Reihen saehe aus wie
     * "nichts bewegt"). Ob sich etwas bewegt hat, zeigt der Vergleich der ganzen Liste vorher/nachher. Danach beginnt
     * der Zeiger wieder oben: was oben steht, bleibt ohnehin (Bull wird am Bild sofort uebersprungen, alles andere ist
     * schon gelesen und bleibt -- es wird nur noch einmal kurz angesehen).
     */
    private Outcome weiterschieben(BotContext ctx, BufferedImage vorher, List<Integer> reihen) {
        BufferedImage alt = foto(ctx);
        if (alt == null) return Outcome.WAITING;
        int x = px(ctx, 1110);   // ganz rechts in der Liste ziehen
        ziehe(ctx, false, x, py(ctx, 600), x, py(ctx, 260));
        ruhe(ctx, 1100);
        BufferedImage neu = foto(ctx);
        if (neu == null) return Outcome.WAITING;
        double anteil = listeVerschieden(alt, neu, ctx);
        Log.info(WER, String.format(Locale.ROOT, Sprache.t("List moved on (%.0f %% of the list different).", "Liste weitergeschoben (%.0f %% der Liste anders)."), anteil * 100));
        if (anteil < 0.02) {
            if (++ohneBewegung >= 3) return listeZuEnde();
            return Outcome.ACTED;
        }
        ohneBewegung = 0;
        zeiger = 0;
        return Outcome.ACTED;
    }

    /** Anteil der Punkte in der Liste (jeder 4. Pixel), die sich um mehr als 24 Graustufen unterscheiden. */
    private static double listeVerschieden(BufferedImage a, BufferedImage b, BotContext ctx) {
        int x0 = px(ctx, 690), x1 = px(ctx, 1130), y0 = py(ctx, LISTE_OBEN + 4), y1 = py(ctx, LISTE_UNTEN - 4);
        int n = 0, anders = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                n++;
                if (Math.abs(grau(a.getRGB(x, y)) - grau(b.getRGB(x, y))) > 24) anders++;
            }
        }
        return n == 0 ? 0 : anders / (double) n;
    }

    private static int grau(int p) {
        return ((p >> 16 & 255) * 3 + (p >> 8 & 255) * 6 + (p & 255)) / 10;
    }

    // --- Bull am Bild erkennen (Autor, 21:29: "Bull-Stigmas ueberspringen, ein bisschen runterwischen, bis andere
    //     Stigmas wieder auftauchen") ----------------------------------------------------------------------------
    // Vorlage: der Stierkopf einer Kachel (templates\gelernt\stigma\st_bull_kopf.png, 50 x 48, aus dem Spiel 21:28).
    // Gemessen (Korrelation): Bull 0,94-1,00 (im Video mit "New" 0,82-0,86), Igel 0,11-0,16, Krokodil 0,06-0,07,
    // Adler/Drache/Phoenix 0,03-0,09, andere bis 0,22. Grenze 0,6. Erkannte Bulls werden nicht geoeffnet -- sie bleiben
    // ja ohnehin; ein nicht erkannter Bull wird wie bisher geoeffnet, gelesen und bleibt.
    private static final double BULL_GRENZE = 0.6;
    // Autor 23.10.: Jede Sorte eines Sets (bull mind, bull will, ...) hat einen anderen Kopf -- "bull will" kam gegen die eine
    // Vorlage nur auf 0,06-0,19 und wurde jedes Mal geoeffnet, Drachen ebenso ("drueckt drauf und wieder raus"). Darum je
    // behaltenem Set mehrere Koepfe, und der Bot lernt neue selbst: Eine geoeffnete Kachel war ein behaltenes Set, der Kopf
    // wurde aber nicht erkannt -> Kopf aus dem Listenbild ausschneiden und als st_kopf_<set>_gelernt_N.png merken.
    // Der alte Stierkopf (st_bull_kopf.png) zaehlt fuer "bull".
    private static final java.nio.file.Path KOPF_ORDNER = java.nio.file.Path.of("templates", "gelernt", "stigma");
    private static final int GELERNT_MAX_JE_SET = 12;
    private static final java.util.Map<String, List<double[]>> schutzKoepfe = new java.util.HashMap<>();
    private static boolean koepfeGeladen;

    private static synchronized java.util.Map<String, List<double[]>> schutzKoepfe() {
        if (!koepfeGeladen) {
            koepfeGeladen = true;
            try (var dateien = java.nio.file.Files.newDirectoryStream(KOPF_ORDNER, "st_*kopf*.png")) {
                for (java.nio.file.Path d : dateien) {
                    String name = d.getFileName().toString();
                    String set = name.startsWith("st_bull_kopf") ? "bull"
                            : name.startsWith("st_kopf_") ? name.substring(8, Math.max(8, name.indexOf("_gelernt"))) : "";
                    if (set.isEmpty()) continue;
                    BufferedImage v = javax.imageio.ImageIO.read(d.toFile());
                    if (v != null && v.getWidth() == 50 && v.getHeight() == 48) {
                        schutzKoepfe.computeIfAbsent(set, k -> new ArrayList<>()).add(grauFeld(v, 0, 0, 50, 48));
                    }
                }
            } catch (Exception e) {
                Log.warn(WER, Sprache.t("Stigma head templates not readable: ", "Stigma-Kopf-Vorlagen nicht lesbar: ") + e.getMessage());
            }
        }
        return schutzKoepfe;
    }

    /** Eine geoeffnete Kachel war ein behaltenes Set, der Kopf wurde aber nicht erkannt: als neue Vorlage merken. */
    private static synchronized void kopfLernen(String set, BufferedImage liste, int[] p) {
        if (set == null || set.isBlank() || liste == null || p == null) return;
        int x = p[0] - 25, y = p[2] + 5;
        if (x < 0 || y < 0 || x + 50 > liste.getWidth() || y + 48 > liste.getHeight()) return;
        List<double[]> koepfe = schutzKoepfe().computeIfAbsent(set, k -> new ArrayList<>());
        if (koepfe.size() > GELERNT_MAX_JE_SET) return;
        BufferedImage kopf = new BufferedImage(50, 48, BufferedImage.TYPE_INT_RGB);
        kopf.getGraphics().drawImage(liste.getSubimage(x, y, 50, 48), 0, 0, null);
        koepfe.add(grauFeld(kopf, 0, 0, 50, 48));
        try {
            java.nio.file.Files.createDirectories(KOPF_ORDNER);
            java.nio.file.Path ziel = KOPF_ORDNER.resolve("st_kopf_" + set + "_gelernt_" + koepfe.size() + ".png");
            javax.imageio.ImageIO.write(kopf, "png", ziel.toFile());
            Log.info(WER, Sprache.t("New " + set + " head learned (" + ziel.getFileName() + ") -- such tiles are skipped from now on.",
                    "Neuen " + set + "-Kopf gelernt (" + ziel.getFileName() + ") -- solche Kacheln werden ab jetzt uebersprungen."));
        } catch (Exception e) {
            Log.warn(WER, Sprache.t("Head not saved: ", "Kopf nicht gespeichert: ") + e.getMessage());
        }
    }

    private static double[] grauFeld(BufferedImage b, int x, int y, int w, int h) {
        double[] g = new double[w * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) g[j * w + i] = grau(b.getRGB(x + i, y + j));
        return g;
    }

    private static double korrelation(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length;
        mb /= b.length;
        double s = 0, sa = 0, sb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            s += x * y;
            sa += x * x;
            sb += y * y;
        }
        return s / Math.sqrt(sa * sb + 1e-9);
    }

    /** Bester Wert der zuletzt geprueften Kachel (fuer die Meldung, wenn ein behaltenes Set trotzdem geoeffnet wurde). */
    private static volatile double letzterKopfWert = -1;
    /** Listenbild und Kachel vor dem letzten Oeffnen (zum Lernen eines neuen Kopfs). */
    private BufferedImage letzteListe;
    private int[] letzterPlatz;

    /**
     * Zu welchem der Sets gehoert die Kachel {mitteX, tippY, oben}? Null = keins erkannt. Code-Review 23.10.: wieder
     * senkrecht +-12 px wie in 1eba218 (nach dem Wischen liegt die Kante der obersten Reihe ein paar Punkte daneben),
     * in 3er-Schritten; jeder Ausschnitt wird nur einmal gebildet und gegen alle Koepfe verglichen.
     */
    private static String schutzKachel(BufferedImage b, int[] p, java.util.Set<String> sets) {
        double best = -1;
        for (int dy = -12; dy <= 12; dy += 3) {
            for (int dx = -4; dx <= 4; dx += 2) {
                int x = p[0] - 25 + dx, y = p[2] + 5 + dy;
                if (x < 0 || y < 0 || x + 50 > b.getWidth() || y + 48 > b.getHeight()) continue;
                double[] feld = grauFeld(b, x, y, 50, 48);
                for (String set : sets) {
                    for (double[] v : schutzKoepfe().getOrDefault(set, List.of())) {
                        double k = korrelation(v, feld);
                        if (k > best) best = k;
                        if (k >= BULL_GRENZE) {
                            letzterKopfWert = k;
                            return set;
                        }
                    }
                }
            }
        }
        letzterKopfWert = best;
        return null;
    }

    private Outcome listeZuEnde() {
        if (leveln) {
            listeFertig = true;
            return Outcome.ACTED;
        }
        Log.info(WER, Sprache.t("End of the list reached. Marked: " + vorgemerkt + (quickAdd > 0 ? " + " + quickAdd + " via Quick add" : "")
                + ". Would be deleted: " + geloescht + ". Stay: " + behalten + ".",
                "Ende der Liste erreicht. Vorgemerkt: " + vorgemerkt + (quickAdd > 0 ? " + " + quickAdd + " per Quick add" : "")
                + ". Geloescht wuerden: " + geloescht + ". Bleiben: " + behalten + "."));
        listeFertig = true;
        schritt = probe ? Schritt.FERTIG : Schritt.ZERLEGEN;
        if (probe) berichtSchreiben(true);
        return Outcome.ACTED;
    }

    private int kachelBlicke;

    /** Steht "Disassembling items" (oder der Knopf "Disassemble") noch da? */
    private static boolean zerlegenOffen(BufferedImage b) {
        return enthaelt(lies(b, K_ZERLEGEN), "disassemb") || knopfZahl(b) >= 0;
    }

    /**
     * Sterne (Rang) und Level passen zusammen und liegen unter Rang 5: Rang s reicht von Level 20*(s-1) bis 20*s
     * (Rang 1: Level 1-20, "Level 5/20"). Code-Review 23.10.
     */
    static boolean sternePassen(int sterne, int level) {
        return sterne >= 1 && sterne <= 4 && level >= Math.max(1, 20 * (sterne - 1)) && level <= 20 * sterne;
    }

    /** Das Zerlegen-Fenster ist weg: nichts antippen, den Weg neu suchen (die Auswahl im Spiel ist ohnehin weg). */
    private Outcome zerlegenVerloren(String wo) {
        Log.warn(WER, Sprache.t("\"Disassembling items\" is no longer open (" + wo + ") -- nothing is tapped, looking for the way again.",
                "\"Disassembling items\" ist nicht mehr offen (" + wo + ") -- es wird nichts angetippt, der Weg wird neu gesucht."));
        schritt = Schritt.WEG;
        // Code-Review 23.10.: Die Auswahl im Spiel ist weg -- auch die eigene Zaehlung und die Sperre "nur im Fenster tippen"
        // zuruecksetzen. Sonst blockte tippeXY den Weg zurueck (Titan-Knopf, Truhe) und die alte Zaehlung fuehrte zu "No".
        nurImFenster = false;
        vorgemerkt = quickAdd = schonDrin = 0;
        quickErledigt = false;
        zuletztVerglichen = -1;
        return Outcome.ACTED;
    }

    private Outcome zerlegen(BotContext ctx, RunTracker runs) {
        BufferedImage b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        int imKnopf = knopfZahl(b);
        if (imKnopf < 0 && !zerlegenOffen(b)) return zerlegenVerloren("Disassemble");   // Hauptbot, Code-Review 15.10.
        // 21:46: Am Ende war die Zahl im Knopf unlesbar ("Disassemble" ohne "(1871)") -- der Bot meldete "nichts zu
        // zerlegen" und hoerte auf. Die eigene Zaehlung entscheidet; die Zahl in der Warnung wird ohnehin geprueft.
        if (schonDrin + vorgemerkt + quickAdd == 0 || imKnopf == 0 && schonDrin + vorgemerkt + quickAdd == 0) {
            Log.info(WER, Sprache.t("Nothing (more) marked for disassembling.", "Nichts (mehr) zum Zerlegen vorgemerkt."));
            if (listeFertig) {
                berichtSchreiben(false);
                schritt = Schritt.FERTIG;
            } else {
                schritt = Schritt.KACHELN;
            }
            return Outcome.ACTED;
        }
        tippe(ctx, DISASSEMBLE, "Disassemble (" + (imKnopf > 0 ? imKnopf : Sprache.t("number not readable, counted ", "Zahl unlesbar, gezaehlt ") + (schonDrin + vorgemerkt + quickAdd)) + ")");
        ruhe(ctx, 900);
        schritt = Schritt.BESTAETIGEN;
        fehlversuche = 0;
        return Outcome.ACTED;
    }

    private Outcome bestaetigen(BotContext ctx, RunTracker runs) {
        BufferedImage b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        String warnung = lies(b, K_WARNUNG);
        Matcher m = WARN_ZAHL.matcher(warnung);
        if (!enthaelt(warnung, "disassemble") || !m.find()) {
            if (++fehlversuche > 6) {
                Log.error(WER, Sprache.t("The warning \"Are you sure ...\" did not appear -- nothing confirmed.",
                        "Die Warnung \"Are you sure ...\" kam nicht -- nichts bestaetigt."));
                return Outcome.FATAL;
            }
            ruhe(ctx, 500);
            return Outcome.WAITING;
        }
        int n = zahl(m.group(1));
        int erwartet = schonDrin + vorgemerkt + quickAdd;
        if (n != erwartet) {
            Log.error(WER, Sprache.t("The warning names " + n + " stigmas, counted were " + erwartet + " -- stopped (No).",
                    "Die Warnung nennt " + n + " Stigmas, gezaehlt waren " + erwartet + " -- abgebrochen (No)."));
            tippe(ctx, NEIN, "No");
            return Outcome.FATAL;
        }
        // Countdown "OK (5)" ... abwarten, dann "OK".
        for (int i = 0; i < 20; i++) {
            // Waehrend des Countdowns ist der Knopf grau ("OK (5)" ... "OK (1)"), danach gruen (gemessen 77,198,15).
            BufferedImage k = foto(ctx);
            if (k != null && okGruen(k)) {
                Log.action("Klick", WER + Sprache.t(": disassemble " + n + " stigmas (OK)", ": " + n + " Stigmas zerlegen (OK)"));
                tippe(ctx, OK, null);
                ruhe(ctx, 1500);
                runs.progress(Sprache.t("Stigmas disassembled", "Stigmas zerlegt"));
                zerlegtGesamt += n;
                Log.info(WER, Sprache.t(n + " stigmas disassembled (" + zerlegtGesamt + " in total).", n + " Stigmas zerlegt (zusammen " + zerlegtGesamt + ")."));
                schonDrin = vorgemerkt = quickAdd = 0;
                zuletztVerglichen = -1;
                if (listeFertig) {
                    berichtSchreiben(false);
                    schritt = Schritt.FERTIG;
                } else {
                    schritt = Schritt.KACHELN;
                }
                return Outcome.ACTED;
            }
            ruhe(ctx, 500);
        }
        Log.error(WER, Sprache.t("The OK button did not become available -- nothing confirmed.", "Der OK-Knopf wurde nicht frei -- nichts bestaetigt."));
        tippe(ctx, NEIN, "No");
        return Outcome.FATAL;
    }

    private void berichtSchreiben(boolean nurProbe) {
        int summe = nurProbe ? geloescht.values().stream().mapToInt(Integer::intValue).sum() : zerlegtGesamt;
        StringBuilder s = new StringBuilder();
        // Freunde: zweisprachig (kommt am Ende nach Discord).
        s.append(nurProbe ? de.hustlebot.core.Sprache.t("Dry run: would delete ", "Probelauf: geloescht wuerden ")
                : de.hustlebot.core.Sprache.t("Deleted: ", "Geloescht: ")).append(summe).append(" Stigmas");
        if (!geloescht.isEmpty()) s.append(" (").append(liste(geloescht)).append(")");
        if (quickAdd > 0) s.append(de.hustlebot.core.Sprache.t(", of them ", ", davon ")).append(quickAdd)
                .append(de.hustlebot.core.Sprache.t(" blue/epic", " blau/episch"));
        s.append(de.hustlebot.core.Sprache.t(". Kept: ", ". Behalten: "))
                .append(behalten.isEmpty() ? de.hustlebot.core.Sprache.t("none", "keine") : liste(behalten)).append(".");
        bericht = s.toString();
        Log.info(WER, bericht);
    }

    private static String liste(Map<String, Integer> m) {
        StringBuilder s = new StringBuilder();
        m.forEach((k, v) -> s.append(s.length() > 0 ? ", " : "").append(v).append("x ").append(k));
        return s.toString();
    }

    private static void zaehle(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    /** Foto fuer die Fehlersuche: logs\stigmas\<Uhrzeit>-<was>.png (hoechstens 30 je Lauf). */
    private int fotos;

    private void fotoSichern(BufferedImage b, String was) {
        if (b == null || fotos >= 30) return;
        fotos++;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "stigmas");
            java.nio.file.Path datei = ordner.resolve(java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH-mm-ss-SSS")) + "-" + was + ".png");
            if (de.hustlebot.core.Beleg.schreiben(b, "png", datei.toFile())) Log.info(WER, Sprache.t("Picture saved: ", "Foto gesichert: ") + datei);
        } catch (Exception e) {
            Log.debug(WER, "Foto nicht gesichert: " + e.getMessage());
        }
    }

    // ==========================================================================================================
    //  !stigmas leveln -- Last Guardian (Autor, 10.10.)
    // ==========================================================================================================
    //
    // Autor: "Last Guardian, der mittlere -> Stigmas -> All classes: Range -> Namen-Filter: Mind, dann Will, Courage,
    // Strength, Resolve, Tenacity -> nur die Bull-Stigmas mit 'New' ansehen (die anderen hat er schon angesehen) ->
    // besser als das eingesetzte (Rangliste) = Equip; gleich gut = hochleveln und behalten; 3x Schaden/Leben/mag.
    // Schaden = auf Level 3, 4x (oder 5x) = auf Level 20; von jedem mit mindestens 3x ein Foto und am Ende eine Liste.
    // Leere Slots: das beste gefundene einsetzen. Wenn der Last Guardian fertig ist: Bescheid sagen, NICHT weitergehen."
    //
    // Fenster (Aufnahme 10.10., Bild 3630/3640/4900): Tipp auf eine Kachel oeffnet links "Titan's equipment" (das
    // eingesetzte) und rechts den Kandidaten. Rechts: Titel 808,103; offene Werte ab y 340 (je 25); "Unlocks at the
    // following ranks" darunter; nach einem Zug im rechten Feld (900,430 -> 900,355) stehen die Zeilen fuer 2-5 Sterne
    // bei y 394/418/441/466 (Wert 1 ist dann oben weg) -- darum zwei Blicke. Increase 809,571; Equip 900,657;
    // Break apart 718,657 (NIE). Schliessen: Tipp rechts neben das Fenster (1060,400).
    // Ohne eingesetztes Stigma (leerer Slot) steht nur EIN Fenster in der Mitte -- dasselbe Feld 231 px weiter links.

    private static final int[] K_TITAN_NAME = {330, 56, 270, 46};       // "Last Guardian" oben
    private static final int[] LAST_GUARDIAN = {585, 400};             // Titan Squad: Notloesung, wenn das Gesicht fehlt
    private static final int[] K_SWITCH_TITANS = {360, 660, 180, 32};  // "Switch Titans" unter dem Titan
    private static final int[] SWITCH_RECHTS = {614, 686};             // Pfeil rechts daneben
    private static final int[] SWITCH_LINKS = {277, 686};              // Pfeil links (11.10. gemessen)
    private int titanBlaettern;
    private static final int TITAN_BLAETTERN_MAX = 45;

    /** titanen.txt: je Zeile Kurzname, Name, Klasse (archer/tank) -- in der Reihenfolge der Titan Collection. */
    private static List<String[]> titanListe() {
        List<String[]> l = new ArrayList<>();
        try {
            for (String z : de.hustlebot.core.Dateien.zeilen(java.nio.file.Path.of("templates", "gelernt", "titanen", "titanen.txt"))) {
                String[] t = z.split("\t");
                if (t.length >= 2) l.add(t);
            }
        } catch (Exception e) {
            // keine Liste: wie bisher nach links blaettern, Klasse unbekannt
        }
        return l;
    }

    /** Reihenfolge der eigenen Titanen beim Blaettern (wie in der Titan Collection), als Titel-Woerter; leer = unbekannt. */
    static List<String> titanReihenfolge() {
        List<String> l = new ArrayList<>();
        for (String[] t : titanListe()) l.add(de.hustlebot.core.Stigmaplan.titelWort(t[1]));
        return l;
    }

    /**
     * "archer", "tank" oder "" (unbekannt). Autor 23.10.: Das Zeichen oben links in der Titan-Ansicht -- Bogen = Archer,
     * Schild = Tank. Bull-Stigmas passen nur auf Archer.
     */
    static String titanKlasse(String titelWort) {
        String w = de.hustlebot.core.Stigmaplan.titelWort(titelWort);
        if (w.isEmpty()) return "";
        for (String[] t : titanListe()) {
            String n = de.hustlebot.core.Stigmaplan.titelWort(t[1]);
            if (t.length >= 3 && (n.equals(w) || n.contains(w))) return t[2].trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /** Kuerzerer Weg nach rechts? Unbekannte Titanen (oder ohne Liste): links wie bisher. */
    static boolean titanNachRechts(String obenGelesen, String zielWort) {
        List<String> l = titanReihenfolge();
        String jetzt = de.hustlebot.core.Stigmaplan.titelWort(obenGelesen), ziel = de.hustlebot.core.Stigmaplan.titelWort(zielWort);
        int von = -1, nach = -1;
        for (int i = 0; i < l.size(); i++) {
            if (von < 0 && !jetzt.isEmpty() && (jetzt.contains(l.get(i)) || l.get(i).contains(jetzt))) von = i;
            if (nach < 0 && !ziel.isEmpty() && (l.get(i).contains(ziel) || ziel.contains(l.get(i)))) nach = i;
        }
        if (von < 0 || nach < 0) return false;
        int rechts = Math.floorMod(nach - von, l.size());
        return rechts <= l.size() - rechts;
    }
    /** Tipps auf "Titan Squad", ohne dass ein Titan aufging (Freunde 11.10.). */
    private int squadTipps;
    /** Wie oft die Titan Collection weitergeschoben wurde, um die Karte des Titanen zu finden. */
    private int collectionWische;

    /**
     * Der Last Guardian auf "Titan Squad", am Gesicht erkannt (Kapuze mit lila Augen, Vorlage
     * templates\gelernt\stigma\st_lg_gesicht.png aus der Aufnahme vom 10.10.). Die Titanen stehen vorn und hinten
     * verschieden gross -- darum in mehreren Groessen gesucht. Mitte des Treffers, oder null.
     */
    private static int[] lastGuardianSuchen(Mat screen) {
        Mat vorlage = org.opencv.imgcodecs.Imgcodecs.imread(
                java.nio.file.Path.of("templates", "gelernt", "stigma", "st_lg_gesicht.png").toAbsolutePath().toString());
        if (vorlage.empty() || screen == null) return null;
        double best = -1;
        int bx = 0, by = 0;
        try {
            for (double f : new double[]{0.8, 0.9, 1.0, 1.1, 1.2}) {
                Mat v = new Mat();
                Imgproc.resize(vorlage, v, new org.opencv.core.Size(vorlage.cols() * f, vorlage.rows() * f));
                if (v.cols() >= screen.cols() || v.rows() >= screen.rows()) { v.release(); continue; }
                Mat erg = new Mat();
                Imgproc.matchTemplate(screen, v, erg, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mm = Core.minMaxLoc(erg);
                if (mm.maxVal > best) {
                    best = mm.maxVal;
                    bx = (int) mm.maxLoc.x + v.cols() / 2;
                    by = (int) mm.maxLoc.y + v.rows() / 2;
                }
                erg.release();
                v.release();
            }
        } finally {
            vorlage.release();
        }
        Log.info(WER, String.format(Locale.ROOT, Sprache.t("Last Guardian face: best match %.2f at %d,%d.", "Last-Guardian-Gesicht: beste Uebereinstimmung %.2f bei %d,%d."), best, bx, by));
        return best >= 0.6 ? new int[]{bx, by} : null;
    }
    private static final int[] ZERLEGEN_BACK = {262, 641};
    private static final int[] INVENTAR_X = {968, 107};
    private static final int[] REITER_STIGMAS = {1066, 146};
    private static final int[] KLASSEN_KNOPF = {838, 686};             // "All classes" / "Ranged"
    private static final int[] KLASSE_RANGED = {820, 622};
    private static final int[] K_KLASSE = {740, 668, 200, 38};
    private static final int[] TRICHTER = {692, 684};
    private static final int[] FILTER_RESET = {803, 360};
    private static final int[] K_FILTER_RESET = {745, 343, 120, 34};
    private static final String[] L_NAMEN = {"mind", "will", "courage", "strength", "resolve", "tenacity"};
    private static final int[] FILTER_Y = {407, 452, 496, 540, 584, 628};   // x 808
    private static final int L_VERSATZ = 231;                          // rechtes Feld gegen Einzelfenster
    private static final int[] K_LINKS_TITEL = {200, 112, 300, 34};     // "Titan's equipment:"
    private static final int[] SCHLIESSEN_L = {1080, 400};
    private static final int NEW_WEISS = 120;
    // Autor 23.10.: "Nur Stigmas anschauen, die als New markiert sind -- die anderen wurden schon angeschaut."
    // (11.10. hiess es noch: jedes Bull oeffnen, weil seine Mind-Bulls kein "New" mehr hatten.)
    private static final boolean ALLE_BULLS = false;                         // gemessen: New 176-278, ohne 21-40

    private static final Pattern WERT = Pattern.compile(
            // Name mit Doppelpunkt genuegt -- die Zahl steht oft in einer eigenen Lese-Zeile (Pruefung 22:50)
            "(magic\\s*damage|magic\\s*armou?r|damage|health|armou?r|crit|dodge)\\s*(?:[:;]|\\+?\\s*\\d)", Pattern.CASE_INSENSITIVE);

    private boolean leveln;
    private de.hustlebot.core.Stigmaplan.Titan titan = de.hustlebot.core.Stigmaplan.TITANEN.get(0);
    private boolean hundert, nurGelevelte, nurHundert;
    /** Wie oft hintereinander "Fenster schliessen" getippt wurde, ohne dass es zuging (Bremse, Hauptbot 02bed78). */
    private int schliessenFolge;
    /** Je Name: die 5 Werte (in Lese-Reihenfolge) jedes schon bewerteten Stigmas. */
    private final java.util.Set<String> lGesehen = new java.util.HashSet<>();
    private int lDoppelt;
    private int lStartName;
    private int lNameIdx;
    private List<String> eingesetzt;          // Werte des eingesetzten Stigmas fuer den aktuellen Namen (null = unbekannt)
    private boolean slotLeer;
    private int lGeoeffnet, lFehler;
    private final List<String> lEquips = new ArrayList<>();
    private final List<String> lGute = new ArrayList<>();
    private final List<String> lLevel = new ArrayList<>();
    private final List<String> lUebergangen = new ArrayList<>();
    private final List<List<String>> lAbgelegt = new ArrayList<>();
    private boolean eingesetztUnlesbar;

    private void lReset() {
        lNameIdx = 0;
        titanBlaettern = 0;
        squadTipps = 0;
        collectionWische = 0;
        eingesetzt = null;
        slotLeer = false;
        lGeoeffnet = lFehler = 0;
        lEquips.clear();
        lGute.clear();
        lLevel.clear();
        lUebergangen.clear();
        lAbgelegt.clear();
        lGesehen.clear();
        lDoppelt = 0;
        lFotos.clear();
        lFotoSchon.clear();
        lUebersichten.clear();
        lTausche.clear();
        lTempoZaehler = lFehlerStand = 0;
        lTempoFest = false;
        lFestGebremst = false;
        lTempoGut = -1;
        eingesetztUnlesbar = false;
    }

    // Pruefung 10.10. 22:50 (drei Pruefer): Jeder Tipp in den Menues nur, wenn am Bild belegt ist, dass das Menue
    // offen ist -- ein verschluckter Tipp liess sonst den naechsten auf eine Kachel fallen, und der Tipp danach landete
    // auf "Break apart" (692,684) bzw. "Equip" (838,686) des dann offenen Stigma-Fensters.
    private static final int[] K_KLASSEN_MENUE = {740, 535, 200, 110};   // "Melee" / "Ranged" ueber dem Knopf
    private static final int[] K_NAMEN_MENUE = {755, 330, 170, 320};   // "Reset", "Mind" ... "Tenacity"     // "Mind" ... "Tenacity"

    /** 11.10. 02:08: Stand noch "Your Power Rays" offen (vom Lauf davor), erkannte der Bot nichts und brach ab. */
    private boolean raysSchliessen(BotContext ctx, BufferedImage b) {
        if (b == null || !enthaelt(lies(b, K_RAYS_TITEL), "power")) return false;
        tippe(ctx, RAYS_X, Sprache.t("Close open Power Rays window", "Offenes Power-Rays-Fenster schliessen"));
        ruhe(ctx, 900);
        return true;
    }

    /** Ein Stigma-Fenster (zwei Felder oder eins) ist offen? Nur am Text -- das Titanenbild ist dunkel. */
    private boolean stigmaFensterOffen(BufferedImage b) {
        return b != null && (zweiFenster(b) || TITEL.matcher(lies(b, K_TITEL)).find());
    }

    /** Last Guardian, Reiter Stigmas (unten "All classes"/"Ranged"), kein Fenster offen? Sonst null mit Grund. */
    private String lageFalsch(BufferedImage b) {
        if (b == null) return Sprache.t("no picture", "kein Bild");
        if (!titelPasst(lies(b, K_TITAN_NAME))) return Sprache.t("no longer at " + titan.anzeige(), "nicht mehr beim " + titan.anzeige());
        String k = lies(b, K_KLASSE);
        if (!enthaelt(k, "classes") && !enthaelt(k, "ranged")) return Sprache.t("not on the Stigmas tab", "nicht im Reiter Stigmas");
        return null;
    }

    /**
     * lageFalsch, aber bis zu 3 Mal nachgesehen. 11.10. 03:47: Das Stigma-Fenster blendete noch aus, der Klassen-Knopf
     * war nicht lesbar -- "nicht im Reiter Stigmas", und der Lauf brach nach 3 von 6 Sorten ab.
     */
    private String lageFalschSicher(BotContext ctx, BufferedImage b) {
        String f = lageFalsch(b);
        // 15.10.: ein kurzes Stoerbild ueber dem Titan-Namen brach das Leveln ab. Jetzt 5 Blicke ueber gut 5 s.
        for (int i = 0; i < 5 && f != null && !BotEngine.stoppAngefordert(); i++) {
            ctx.sleep(1000);
            if (i == 1) tippe(ctx, SICHER_ZU, Sprache.t("Something covers the screen -- tapping the top edge", "Etwas liegt ueber dem Bild -- oben am Rand tippen"));
            BufferedImage n = foto(ctx);
            if (n != null && stigmaFensterOffen(n)) {
                tippe(ctx, SCHLIESSEN_L, Sprache.t("Close window (before checking the position)", "Fenster schliessen (vor der Lage-Pruefung)"));
                ctx.sleep(700);
                n = foto(ctx);
            }
            f = lageFalsch(n);
        }
        return f;
    }

    /**
     * Freunde (Pruefung 11.10.): die Abbruch- und Level-100-Gruende stehen deutsch im Protokoll; englische Freunde
     * bekamen sie in der Endmeldung ebenfalls deutsch. Hier fuer die Meldung uebersetzt (Protokoll bleibt deutsch).
     */
    static String grundText(String deutsch) {
        if (deutsch == null) return "";
        String en = deutsch.replaceAll("Filter laesst sich nicht genau auf (.*) setzen", "filter cannot be set to exactly $1")
                .replace("nicht mehr beim ", "no longer at ")
                .replace("nicht im Reiter Stigmas", "not on the Stigmas tab")
                .replace("kein Bild", "no picture")
                .replace("Klassen-Menue bleibt offen", "class menu stays open")
                .replace("Namens-Menue geht nicht auf", "name menu does not open")
                .replace("Namens-Menue ging beim Anhaken zu", "name menu closed while ticking")
                .replace("Namens-Menue geht nicht zu", "name menu does not close")
                .replace("kein Stigma-Fenster", "no stigma window")
                .replace("Level nicht lesbar -- aufgehoert", "level not readable -- stopped")
                .replace("Power-Rays-Fenster kam nicht", "power rays window did not open")
                .replace("Rang-Fenster unklar", "rank window unclear")
                .replace("nach Rang-Klick keine Meldung", "no message after the rank click")
                .replace("abgebrochen bei Level", "stopped at level")
                .replace("(Level ", "(level ");
        return de.hustlebot.core.Sprache.t(en, deutsch);
    }

    private Outcome lAbbruch(String grund) {
        Log.error(WER, Sprache.t("Levelling stopped: ", "Leveln abgebrochen: ") + grund);
        fotoSichern(null, "abbruch");
        lFertig();
        bericht = Sprache.t("STOPPED (", "ABGEBROCHEN (") + grund + "). " + bericht;
        kurzBericht = de.hustlebot.core.Sprache.t("Stigma levelling stopped: " + grundText(grund) + ". The pictures of the finished kinds are above.",
                "Stigmas leveln abgebrochen: " + grund + ". Die Bilder der fertigen Sorten stehen oben.");
        schritt = Schritt.FERTIG;
        return Outcome.ACTED;
    }

    /** Reiter Stigmas, Klasse "Ranged" -- jeder Schritt am Bild geprueft. */
    private Outcome lStart(BotContext ctx, RunTracker runs) {
        BufferedImage b = foto(ctx);
        if (raysSchliessen(ctx, b)) return Outcome.ACTED;
        if (stigmaFensterOffen(b)) {
            tippe(ctx, SCHLIESSEN_L, Sprache.t("Close open stigma window", "Offenes Stigma-Fenster schliessen"));
            ctx.sleep(600);
            return Outcome.ACTED;
        }
        // Reiter Stigmas, bis unten "All classes" (oder "Ranged") steht.
        for (int i = 0; i < 3 && lageFalsch(b) != null; i++) {
            if (!listenTipp(ctx, REITER_STIGMAS, Sprache.t("Stigmas tab", "Reiter Stigmas"))) return Outcome.ACTED;
            ctx.sleep(1000);
            b = foto(ctx);
        }
        String falsch = lageFalsch(b);
        if (falsch != null) return lAbbruch(falsch);
        // Autor, 21:09: "All classes -> immer Range, dann muss er nicht so weit scrollen."
        for (int versuch = 0; versuch < 3 && !enthaelt(lies(foto(ctx), K_KLASSE), "ranged"); versuch++) {
            if (!listenTipp(ctx, KLASSEN_KNOPF, Sprache.t("Open class filter", "Klassen-Filter oeffnen"))) return Outcome.ACTED;
            ctx.sleep(800);
            String menue = lies(foto(ctx), K_KLASSEN_MENUE);
            if (!enthaelt(menue, "melee") && !enthaelt(menue, "ranged")) {
                Log.info(WER, Sprache.t("Class menu did not open (\"" + menue + "\") -- again.", "Klassen-Menue ging nicht auf (\"" + menue + "\") -- neu."));
                if (stigmaFensterOffen(ctx.frame())) {
                    tippe(ctx, SCHLIESSEN_L, Sprache.t("Close accidentally opened window", "Versehentlich geoeffnetes Fenster schliessen"));
                    ctx.sleep(600);
                }
                continue;
            }
            if (!listenTipp(ctx, KLASSE_RANGED, Sprache.t("Choose Ranged", "Ranged waehlen"))) return Outcome.ACTED;
            ctx.sleep(800);
            // Bleibt das Menue offen (Bild 5582), mit dem Knopf schliessen -- nur wenn es wirklich noch offen ist.
            if (enthaelt(lies(foto(ctx), K_KLASSEN_MENUE), "melee")) {
                if (!listenTipp(ctx, KLASSEN_KNOPF, Sprache.t("Close class menu", "Klassen-Menue schliessen"))) return Outcome.ACTED;
                ctx.sleep(700);
            }
        }
        b = foto(ctx);
        if (enthaelt(lies(b, K_KLASSEN_MENUE), "melee")) return lAbbruch(Sprache.t("class menu stays open", "Klassen-Menue bleibt offen"));
        if (!enthaelt(lies(b, K_KLASSE), "ranged")) {
            fotoSichern(b, "ranged-nicht-gesetzt");
            Log.warn(WER, Sprache.t("\"Ranged\" could not be set -- continuing with all classes (every stigma is checked by its title).",
                    "\"Ranged\" liess sich nicht setzen -- weiter mit allen Klassen (jedes Stigma wird am Titel geprueft)."));
        }
        runs.progress(Sprache.t("Stigma levelling: " + titan.anzeige() + ", filter Ranged", "Stigmas leveln: " + titan.anzeige() + ", Filter Ranged"));
        lNameIdx = lStartName;
        schritt = Schritt.L_NAME;
        return Outcome.ACTED;
    }

    private boolean namenMenueOffen(BufferedImage b) {
        String t = lies(b, K_NAMEN_MENUE);
        return enthaelt(t, "mind") && (enthaelt(t, "tenacity") || enthaelt(t, "resolve") || enthaelt(t, "courage")
                || enthaelt(t, "strength"));
    }

    /** Gelber Haken in Zeile idx des Namens-Menues (gemessen 11.10.: gelb 255/204/0, leer 5/81/99 bei x 913). */
    private static boolean haken(BufferedImage b, int idx) {
        int c = b.getRGB(sx(b, 913), sy(b, FILTER_Y[idx]));
        return (c >> 16 & 255) > 200 && (c >> 8 & 255) > 150 && (c & 255) < 90;
    }

    /** Bis zu maxMs warten, bis das Namens-Menue offen (bzw. zu) ist. Feste Pausen, nicht am Tempo-Regler. */
    private BufferedImage warteAufNamenMenue(BotContext ctx, boolean offen, long maxMs) {
        long bis = System.currentTimeMillis() + maxMs;
        BufferedImage b = foto(ctx);
        while (b != null && namenMenueOffen(b) != offen && System.currentTimeMillis() < bis) {
            ctx.sleep(200);
            b = foto(ctx);
        }
        return b;
    }

    /**
     * Namens-Filter auf genau den naechsten Namen setzen, Liste nach oben, eingesetztes Stigma vergessen.
     * 11.10. 01:17: nach dem Tipp auf "Mind" galt das Menue als zu (zu frueh hingesehen) -- es blieb offen, verdeckte
     * die halbe Liste, und der Bot erkannte die Bulls nicht. Jetzt wird jeder Schritt abgewartet und am Bild geprueft:
     * Menue offen, genau EIN Haken (der richtige), Menue wieder zu.
     */
    private Outcome lName(BotContext ctx, RunTracker runs) {
        // 11.10.: nur die Sorten, die unter !stigmas einstellen angehakt sind (Standard: alle sechs).
        while (lNameIdx < L_NAMEN.length && !de.hustlebot.core.Stigmaplan.namen().contains(L_NAMEN[lNameIdx])) lNameIdx++;
        if (lNameIdx >= L_NAMEN.length) return lFertig();
        String name = L_NAMEN[lNameIdx];
        BufferedImage b = foto(ctx);
        if (raysSchliessen(ctx, b)) return Outcome.ACTED;
        if (stigmaFensterOffen(b)) {
            tippe(ctx, SCHLIESSEN_L, Sprache.t("Close open stigma window", "Offenes Stigma-Fenster schliessen"));
            ctx.sleep(700);
            return Outcome.ACTED;
        }
        if (!namenMenueOffen(b)) {
            String falsch = lageFalschSicher(ctx, b);
            if (falsch != null) return lAbbruch(falsch);
            for (int i = 0; i < 3 && !namenMenueOffen(b); i++) {
                if (!listenTipp(ctx, TRICHTER, Sprache.t("Open name filter", "Namens-Filter oeffnen"))) return Outcome.ACTED;
                b = warteAufNamenMenue(ctx, true, 2000);
                if (b != null && stigmaFensterOffen(b)) {
                    tippe(ctx, SCHLIESSEN_L, Sprache.t("Close accidentally opened window", "Versehentlich geoeffnetes Fenster schliessen"));
                    ctx.sleep(700);
                    b = foto(ctx);
                }
            }
            if (b == null || !namenMenueOffen(b)) return lAbbruch(Sprache.t("name menu does not open", "Namens-Menue geht nicht auf"));
        }
        // Genau den einen Haken setzen (jede Zeile einzeln, jeweils am gelben Haken geprueft).
        for (int runde = 0; runde < 2; runde++) {
            for (int i = 0; i < L_NAMEN.length; i++) {
                boolean soll = i == lNameIdx;
                if (haken(b, i) == soll) continue;
                if (!listenTipp(ctx, new int[]{808, FILTER_Y[i]}, Sprache.t(soll ? "Filter on: " : "Filter off: ", soll ? "Filter an: " : "Filter aus: ") + L_NAMEN[i])) return Outcome.ACTED;
                long bis = System.currentTimeMillis() + 1500;
                do {
                    ctx.sleep(200);
                    b = foto(ctx);
                } while (b != null && haken(b, i) != soll && System.currentTimeMillis() < bis);
                if (b == null) return Outcome.WAITING;
                if (!namenMenueOffen(b)) return lAbbruch(Sprache.t("name menu closed while ticking", "Namens-Menue ging beim Anhaken zu"));
            }
        }
        for (int i = 0; i < L_NAMEN.length; i++) {
            if (haken(b, i) != (i == lNameIdx)) {
                fotoSichern(b, "filter-haken-falsch");
                return lAbbruch(Sprache.t("filter cannot be set to exactly " + name, "Filter laesst sich nicht genau auf " + name + " setzen"));
            }
        }
        // Menue schliessen und abwarten, dass es zu ist.
        for (int i = 0; i < 3 && b != null && namenMenueOffen(b); i++) {
            if (!listenTipp(ctx, TRICHTER, Sprache.t("Close name filter", "Namens-Filter schliessen"))) return Outcome.ACTED;
            b = warteAufNamenMenue(ctx, false, 2000);
        }
        if (b == null || namenMenueOffen(b) || stigmaFensterOffen(b)) return lAbbruch(Sprache.t("name menu does not close", "Namens-Menue geht nicht zu"));
        ctx.sleep(800);   // die Liste baut sich neu auf
        // Autor 11.10. 01:41: "Nach dem Filter faengt die Liste automatisch ganz oben an -- er muss nicht hochscrollen."
        eingesetzt = null;
        slotLeer = false;
        eingesetztUnlesbar = false;
        lGesehen.clear();
        zeiger = 0;
        ohneBewegung = 0;
        listeFertig = false;
        Log.info(WER, Sprache.t("Levelling: kind " + name + " (" + (lNameIdx + 1) + " of 6).", "Leveln: Name " + name + " (" + (lNameIdx + 1) + " von 6)."));
        runs.progress(Sprache.t("Stigma levelling: " + name, "Stigmas leveln: " + name));
        schritt = Schritt.L_KACHELN;
        return Outcome.ACTED;
    }

    private Outcome lFertig() {
        StringBuilder s = new StringBuilder();
        s.append(probe ? Sprache.t("Levelling (dry run) at the " + titan.anzeige() + " done: ", "Leveln (Probelauf) beim " + titan.anzeige() + " fertig: ")
                        : Sprache.t("Levelling at the " + titan.anzeige() + " done: ", "Leveln beim " + titan.anzeige() + " fertig: "))
                .append(lGeoeffnet - lDoppelt).append(Sprache.t(" different Bull stigmas looked at (", " verschiedene Bull-Stigmas angesehen (")).append(lDoppelt)
                .append(Sprache.t(" opened twice and closed again right away)", " doppelt geoeffnet und gleich wieder zu)"));
        s.append(lEquips.isEmpty() ? Sprache.t(", nothing swapped", ", nichts getauscht") : Sprache.t(", equipped: ", ", eingesetzt: ") + String.join("; ", lEquips));
        s.append(lLevel.isEmpty() ? Sprache.t(", nothing levelled", ", nichts gelevelt") : Sprache.t(", levelled: ", ", gelevelt: ") + String.join("; ", lLevel));
        s.append(lGute.isEmpty() ? Sprache.t(". None with 3x or more.", ". Keins mit 3x oder mehr.")
                : Sprache.t(". With 3x or more (pictures in logs\\stigmas\\gute): ", ". Mit 3x oder mehr (Fotos in logs\\stigmas\\gute): ")
                + String.join("; ", lGute));
        if (lFehler > 0) s.append(" (").append(lFehler).append(Sprache.t(" times not cleanly readable.)", " Mal nicht sauber lesbar.)"));
        if (!lUebergangen.isEmpty()) s.append(Sprache.t(" Skipped: ", " Uebergangen: ")).append(String.join("; ", lUebergangen)).append(".");
        List<String> uebersichten = new ArrayList<>(lUebersichten);
        if (!uebersichten.isEmpty()) s.append(Sprache.t(" Overviews: ", " Uebersichten: ")).append(String.join(", ", uebersichten));
        bericht = s.toString();
        // Freunde: die Fertig-Zeile auch auf Englisch.
        kurzBericht = de.hustlebot.core.Sprache.t(
                "Stigma levelling done (" + titan.anzeige() + "): " + (lGeoeffnet - lDoppelt) + " looked at, "
                        + (lEquips.isEmpty() ? "nothing swapped" : lEquips.size() + "x swapped") + ", "
                        + (lLevel.isEmpty() ? "nothing levelled" : lLevel.size() + "x levelled") + ". The pictures per kind are above.",
                "Stigmas leveln fertig (" + titan.anzeige() + "): " + (lGeoeffnet - lDoppelt) + " angesehen, "
                        + (lEquips.isEmpty() ? "nichts getauscht" : lEquips.size() + "x getauscht") + ", "
                        + (lLevel.isEmpty() ? "nichts gelevelt" : lLevel.size() + "x gelevelt") + ". Die Bilder je Sorte stehen oben.");
        // Pruefung 11.10.: eine uebersprungene Sorte hat kein Bild -- dann wenigstens hier sagen, welche.
        List<String> weg = new ArrayList<>();
        for (String u : lUebergangen) {
            if (u.startsWith("Slot ") && (u.contains("uebersprungen") || u.contains("skipped"))) weg.add(u.substring(5, u.indexOf(':')));
        }
        if (!weg.isEmpty()) kurzBericht += de.hustlebot.core.Sprache.t(" Skipped (equipped one not readable): ",
                " Uebersprungen (eingesetztes nicht lesbar): ") + String.join(", ", weg) + ".";
        Log.info(WER, bericht);
        // Autor: "Wenn alles fertig ist mit dem Last Guardian, sagst du mir Bescheid. Nicht weitergehen."
        // 11.10. 03:40: beim Keeper of Stars & Co. danach die eingesetzten so weit wie moeglich auf Level 100.
        schritt = hundert && !probe ? Schritt.L_HUNDERT : Schritt.FERTIG;
        return Outcome.ACTED;
    }

    /** Eine Kachel der gefilterten Liste: nur Bull mit "New" wird geoeffnet. */
    private Outcome lKachel(BotContext ctx, RunTracker runs) {
        BufferedImage b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        if (stigmaFensterOffen(b)) {
            tippe(ctx, SCHLIESSEN_L, Sprache.t("Close window", "Fenster schliessen"));
            ruhe(ctx, 500);
            return Outcome.ACTED;
        }
        if (eingesetztUnlesbar) {
            lNameIdx++;
            schritt = Schritt.L_NAME;
            return Outcome.ACTED;
        }
        if (raysSchliessen(ctx, b)) return Outcome.ACTED;
        String falsch = lageFalschSicher(ctx, b);
        if (falsch != null) return lAbbruch(falsch);
        b = foto(ctx);
        if (b == null) return Outcome.WAITING;
        List<Integer> reihen = reihenL(b, ctx);
        List<int[]> plaetze = new ArrayList<>();
        for (int oben : reihen) {
            for (int sp = 0; sp < 4; sp++) {
                if (orange(b, px(ctx, L_KANTE_X + 111 * sp), oben + py(ctx, 15))) {
                    plaetze.add(new int[]{px(ctx, L_SPALTE_1 + 111 * sp), oben + py(ctx, 30), oben});
                }
            }
        }
        if (plaetze.isEmpty() && ++leerBlicke <= 6) {
            ruhe(ctx, 250);
            return Outcome.WAITING;
        }
        leerBlicke = 0;
        // Autor, 11.10. 01:23: "Er soll jedes Bull-Stigma einzeln anklicken" -- nicht nur die mit "New" (seine Mind-Bulls
        // hatten alle kein "New" mehr, der Bot oeffnete nichts).
        // Autor 11.10. 03:45: beim Keeper of Stars nur die "aufgelevelten" (vom Bot markierten, Level 2 und mehr) oeffnen.
        while (zeiger < plaetze.size() && !(bullKachelL(b, plaetze.get(zeiger), ctx) && (ALLE_BULLS || nurGelevelte || neu(b, plaetze.get(zeiger), ctx))
                && (!nurGelevelte || !kachelIstLv1(b, plaetze.get(zeiger))))) {
            letzteReiheOben = plaetze.get(zeiger)[2];
            zeiger++;
        }
        if (zeiger >= plaetze.size()) {
            Outcome o = weiterschiebenL(ctx, b);
            if (listeFertig) {
                // Ende der Liste fuer diesen Namen (weiterschieben meldet das ueber listeZuEnde): naechster Name.
                listeFertig = false;
                String u = sorteFertig(L_NAMEN[lNameIdx]);
                if (u != null) lUebersichten.add(u);
                lNameIdx++;
                schritt = Schritt.L_NAME;
                return Outcome.ACTED;
            }
            return o;
        }
        int[] p = plaetze.get(zeiger);
        letzteReiheOben = p[2];
        zeiger++;   // diese Kachel ist danach angesehen
        tippeXY(ctx, p[0], p[1], Sprache.t("open new Bull stigma", "neues Bull-Stigma oeffnen"));
        ruhe(ctx, 500);
        return kandidat(ctx, runs);
    }

    // Titan-Liste (Aufnahme 10.10. Bild 3626/5100): Kacheln 8 px weiter links als im Zerlegen-Fenster, Spalten 111 px
    // auseinander (737/848/959/1070), Liste endet bei y 640. Orange Kante bei x 697; "New" 56-76 px unter dem Beginn
    // der Kante (weiss: mit New 241-297 Punkte, ohne 17-24). Stierkopf: Korrelation Bull 0,58-0,96 (Video), andere
    // hoechstens 0,25 -- Grenze hier 0,45.
    private static final int L_KANTE_X = 697, L_SPALTE_1 = 737, L_LISTE_UNTEN = 640;
    private static final double BULL_GRENZE_L = 0.45;

    private static List<Integer> reihenL(BufferedImage b, BotContext ctx) {
        List<Integer> r = new ArrayList<>();
        int x = px(ctx, L_KANTE_X), oben = py(ctx, LISTE_OBEN), unten = py(ctx, L_LISTE_UNTEN);
        int start = -1, letzt = -1;
        for (int y = oben; y <= unten; y++) {
            boolean o = orange(b, x, y);
            if (o) {
                if (start < 0) start = y;
                letzt = y;
            }
            if (start >= 0 && ((!o && y - letzt > 6) || y == unten)) {
                // ganz sichtbar samt "New" darunter
                if (letzt - start >= py(ctx, 30) && start > oben + 4 && start + py(ctx, 80) <= unten) r.add(start);
                start = -1;
            }
        }
        return r;
    }

    private static boolean bullKachelL(BufferedImage b, int[] p, BotContext ctx) {
        double best = -1;
        for (double[] v : schutzKoepfe().getOrDefault("bull", List.of())) {   // alle bekannten Bull-Koepfe (23.10.)
            for (int dy = -6; dy <= 6; dy += 2) {
                for (int dx = -12; dx <= 12; dx += 2) {
                    int x = p[0] - 25 + dx, y = p[2] + 2 + dy;
                    if (x < 0 || y < 0 || x + 50 > b.getWidth() || y + 48 > b.getHeight()) continue;
                    best = Math.max(best, korrelation(v, grauFeld(b, x, y, 50, 48)));
                    if (best >= 0.8) return true;
                }
            }
        }
        return best >= BULL_GRENZE_L;
    }

    /** Kachel mit "New": weisse Schrift unten auf der Kachel. */
    private static boolean neu(BufferedImage b, int[] p, BotContext ctx) {
        int x0 = p[0] - 28, y0 = p[2] + py(ctx, 56), n = 0;
        for (int y = y0; y < y0 + py(ctx, 20) && y < b.getHeight(); y++) {
            for (int x = x0; x < x0 + 56 && x < b.getWidth(); x++) {
                int c = b.getRGB(x, y);
                if ((c >> 16 & 255) > 225 && (c >> 8 & 255) > 225 && (c & 255) > 225) n++;
            }
        }
        return n >= NEW_WEISS;
    }

    /** Zwei Fenster nebeneinander (links "Titan's equipment")? */
    private final List<Object[]> equipGedaechtnis = new ArrayList<>();
    private static final Pattern EQUIPMENT = Pattern.compile("equipment", Pattern.CASE_INSENSITIVE);

    /** Zwei Fenster (links "Titan's equipment")? Das Wort wird gemerkt statt jedes Mal gelesen (01:45, Tempo). */
    private boolean zweiFenster(BufferedImage b) {
        return enthaelt(liesGemerkt(b, K_LINKS_TITEL, equipGedaechtnis, EQUIPMENT), "equipment");
    }

    private int[] r(int[] p, int dx) {
        return new int[]{p[0] + dx, p[1]};
    }

    private int[] k(int[] kasten, int dx) {
        return new int[]{kasten[0] + dx, kasten[1], kasten[2], kasten[3]};
    }

    // --- 11.10. 01:30 (Autor) ---------------------------------------------------------------------------------------
    // "Nicht so schnell runterscrollen -- der Bot hat eine Reihe Bull-Stigmas uebersprungen. Nur immer so, dass die
    // naechsten Reihen gerade so angezeigt werden." Darum: langsam um genau 2 Reihen (278 px) ziehen, danach am Bild
    // ausrechnen, wie weit es wirklich ging (Reihenabstand 139 px), und nur die Reihen als erledigt zaehlen, die sicher
    // schon dran waren (eine halbe Reihe Sicherheit). Lieber eine Kachel doppelt als eine uebersehen.
    private static final int L_REIHE = 139, L_SCHUB = 2 * L_REIHE;

    private Outcome weiterschiebenL(BotContext ctx, BufferedImage vorher) {
        List<Integer> alt = reihenL(vorher, ctx);
        // Autor 01:41: "Mach, dass der Bot erkennt, wann er das Ende erreicht hat." Steht unten schon eine Reihe mit
        // weniger als 4 Kacheln (oder gar keine mehr nach der letzten), ist das Ende zu sehen -- gar nicht erst ziehen.
        if (!alt.isEmpty()) {
            int letzte = alt.get(alt.size() - 1), n = 0;
            for (int sp = 0; sp < 4; sp++) if (orange(vorher, px(ctx, L_KANTE_X + 111 * sp), letzte + py(ctx, 15))) n++;
            boolean danach = orange(vorher, px(ctx, L_KANTE_X), Math.min(vorher.getHeight() - 1, letzte + py(ctx, L_REIHE + 15)));
            if (n < 4 || (!danach && letzte + py(ctx, L_REIHE + 30) < py(ctx, L_LISTE_UNTEN))) {
                Log.info(WER, Sprache.t("End of the list visible (last row " + n + " tiles).", "Ende der Liste zu sehen (letzte Reihe " + n + " Kacheln)."));
                listeFertig = true;
                return Outcome.ACTED;
            }
        }
        int x = px(ctx, 1110);
        ziehe(ctx, false, x, py(ctx, 600), x, py(ctx, 600 - L_SCHUB));
        ctx.sleep(Math.max(1200, Math.round(1200 * faktor / 2)));
        BufferedImage neu = foto(ctx);
        if (neu == null) return Outcome.WAITING;
        double anteil = listeVerschieden(vorher, neu, ctx);
        if (anteil < 0.01) {
            // Bewegt sich nichts mehr: Ende (einmal nachgeprueft, falls der Zug nicht angekommen ist).
            if (++ohneBewegung >= 2) {
                listeFertig = true;
            }
            return Outcome.ACTED;
        }
        ohneBewegung = 0;
        // 11.10. 01:55 (Autors Video, Bild fuer Bild): Die Schaetzung "wie weit ist die Liste gerutscht" lag einmal eine
        // ganze Reihe daneben (212 statt 278+139 px, Nachrutschen) -- die oberste Reihe wurde uebersprungen. Jetzt wird
        // nicht mehr geschaetzt: immer oben anfangen; schon gesehene Stigmas erkennt kandidat() an ihren 5 Werten.
        zeiger = 0;
        return Outcome.ACTED;
    }

    /**
     * Steht unter der Kachel {mitteX, tippY, oben} "Lv. 1"? Die Texterkennung liest das kurze "Lv. 1" nicht (11.10.
     * 03:35 getestet, auch vergroessert und schwarz-weiss), darum Bildvergleich mit der Vorlage
     * templates/gelernt/stigma/st_lv1.png (96 x 28, weisse Schrift): "Lv." ausrichten (+-6 Punkte), dann nur die
     * Ziffer vergleichen (Ausschnitt 83 Punkte unter der Zeilenkante). Gemessen: Lv. 1 weicht 3-20 Punkte ab, Lv. 3 50-52, Lv. 4 62, Lv. 100 ueber 150. Grenze 30;
     * im Zweifel (keine Vorlage, anderes Bildformat) false = die Kachel wird geoeffnet.
     */
    private static boolean kachelIstLv1(BufferedImage b, int[] p) {
        BufferedImage v = lv1Vorlage();
        if (v == null || b.getWidth() != BREITE || b.getHeight() != HOEHE) return false;
        int bx = p[0] - 48, by = p[2] + 83;   // Zeilenkante (reihenL) bis Beschriftung: 79-85 gemessen
        if (bx - 6 < 0 || by - 6 < 0 || bx + 96 + 6 > b.getWidth() || by + 28 + 6 > b.getHeight()) return false;
        int best = Integer.MAX_VALUE, bdx = 0, bdy = 0;
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                int n = 0;
                for (int y = 0; y < 28; y++) for (int x = 0; x < 56; x++) if (weiss(v, x, y) != weiss(b, bx + dx + x, by + dy + y)) n++;
                if (n < best) { best = n; bdx = dx; bdy = dy; }
            }
        }
        if (best > 40) return false;   // "Lv." passt nicht (z. B. "Lv. 100" ist anders ausgerichtet)
        int ziffer = 0;
        for (int y = 0; y < 28; y++) for (int x = 56; x < 80; x++) if (weiss(v, x, y) != weiss(b, bx + bdx + x, by + bdy + y)) ziffer++;
        return ziffer <= 30;
    }

    private static boolean weiss(BufferedImage b, int x, int y) {
        int c = b.getRGB(x, y);
        return (c >> 16 & 255) > 200 && (c >> 8 & 255) > 200 && (c & 255) > 200;
    }

    private static BufferedImage lv1;
    private static boolean lv1Geladen;

    private static synchronized BufferedImage lv1Vorlage() {
        if (!lv1Geladen) {
            lv1Geladen = true;
            try {
                BufferedImage v = javax.imageio.ImageIO.read(java.nio.file.Path.of("templates", "gelernt", "stigma", "st_lv1.png").toFile());
                if (v != null && v.getWidth() == 96 && v.getHeight() == 28) lv1 = v;
            } catch (Exception e) {
                Log.warn(WER, Sprache.t("Template st_lv1.png missing -- all tiles are opened: ", "Vorlage st_lv1.png fehlt -- es werden alle Kacheln geoeffnet: ") + e.getMessage());
            }
        }
        return lv1;
    }

    // Schneller werden (Autor 01:30: "nach jedem dritten Bull-Stigma um 10 % schneller, bis es nicht mehr geht").
    private int lTempoZaehler, lFehlerStand;
    private boolean lTempoFest, lFestGebremst;
    private double lTempoGut = -1;

    private void lTempo() {
        if (lFehler > lFehlerStand) {
            lFehlerStand = lFehler;
            if (!lTempoFest && lTempoGut > 0) {
                faktor = Math.round(lTempoGut * 1.1 * 100) / 100.0;
                lTempoFest = true;
                filterTempoMerken(faktor, true);
                Log.info(WER, Sprache.t("Levelling: error at this speed -- back to " + faktor + " (last error-free " + lTempoGut + "), stays there.",
                        "Leveln: Fehler bei Tempo -- zurueck auf " + faktor + " (letztes fehlerfreies " + lTempoGut + "), bleibt so."));
            } else if (lTempoFest && !lFestGebremst) {
                // Code-Review 23.10.: Auch ein festes Tempo darf bei Fehlern eine Stufe langsamer werden (Autor: "oder eine
                // Stufe vor dem schnellsten") -- hoechstens einmal je Lauf, und gemerkt fuer die naechsten Laeufe.
                lFestGebremst = true;
                faktor = Math.min(3.0, Math.round(faktor * 1.1 * 100) / 100.0);
                filterTempoMerken(faktor, true);
                Log.info(WER, Sprache.t("Levelling: error at the fixed speed -- one step slower: " + faktor + ".",
                        "Leveln: Fehler beim festen Tempo -- eine Stufe langsamer: " + faktor + "."));
            }
            lTempoZaehler = 0;
            return;
        }
        if (lTempoFest || ++lTempoZaehler < 3) return;
        lTempoZaehler = 0;
        lTempoGut = faktor;
        faktor = Math.max(0.3, Math.round(faktor * 0.9 * 100) / 100.0);   // 01:45: bis 0,3 (vorher 0,5)
        // Code-Review 23.10.: erst fest, wenn auch 0,3 drei Stigmas lang fehlerfrei lief -- vorher wurde "0.3 fest"
        // gespeichert, bevor ein einziges Stigma bei 0,3 lief, und Fehler bremsten danach nie mehr.
        if (lTempoGut <= 0.3) lTempoFest = true;   // schneller geht es nicht
        filterTempoMerken(lTempoFest ? faktor : lTempoGut, lTempoFest);
        Log.info(WER, Sprache.t("Levelling: 3 stigmas without errors -- speed " + lTempoGut + " -> " + faktor + ".",
                "Leveln: 3 Stigmas ohne Fehler -- Tempo " + lTempoGut + " -> " + faktor + "."));
    }

    // Autor 23.10.: "Das schrittweise Beschleunigen war nur gedacht, um die maximale Geschwindigkeit herauszufinden und die
    // dann fuer alle weiteren Filterungen einzustellen -- er soll nicht jedes Mal von vorn schneller werden." Darum merkt
    // sich der Bot das Tempo in stigma-filter-tempo.txt: "0.81 fest" = gefunden, so bleibt es; "1.2" = letztes fehlerfreies,
    // dort geht die Suche beim naechsten Mal weiter.
    private static final java.nio.file.Path FILTER_TEMPO = de.hustlebot.core.Ordner.bei("stigma-filter-tempo.txt");

    /** {Tempo, 1 = fest / 0 = Suche geht weiter} oder null, wenn noch nichts gemerkt ist. */
    static double[] filterTempoLesen() {
        try {
            String[] t = de.hustlebot.core.Dateien.text(FILTER_TEMPO).trim().split("\\s+");
            double wert = Double.parseDouble(t[0].replace(',', '.'));
            if (wert < 0.3 || wert > 5) return null;
            return new double[]{wert, t.length > 1 && t[1].equalsIgnoreCase("fest") ? 1 : 0};
        } catch (Exception e) {
            return null;
        }
    }

    private static void filterTempoMerken(double wert, boolean fest) {
        try {
            java.nio.file.Files.writeString(FILTER_TEMPO, wert + (fest ? " fest" : "") + System.lineSeparator());
        } catch (Exception e) {
            Log.warn(WER, Sprache.t("Filter speed not saved: ", "Filter-Tempo nicht gespeichert: ") + e.getMessage());
        }
    }

    // Fotos fuer Autor: jedes Bull-Stigma mit 3x (oder mehr) DESSELBEN Werts, egal welcher -- am Ende je Name eine
    // Uebersicht mit Beschriftung ("Das sind alle Mind, das sind alle Will").
    private final Map<String, List<Object[]>> lFotos = new LinkedHashMap<>();
    private final java.util.Set<String> lFotoSchon = new java.util.HashSet<>();
    private final List<String> lUebersichten = new ArrayList<>();
    private final List<String> lTausche = new ArrayList<>();

    /**
     * Autor, 11.10. 02:33: "sehr viele gelb markierte, aber kein gruenes" -- das eingesetzte Stigma fehlte im Bild. Jetzt
     * kommt es als erstes Bild in die Uebersicht (linkes Feld, vorher ganz nach oben geschoben, damit Wert 1 zu sehen ist).
     */
    private void eingesetztMerken(BotContext ctx, String name) {
        int x = px(ctx, 360);
        ziehe(ctx, true, x, py(ctx, 400), x, py(ctx, 480));
        ruhe(ctx, 400);
        BufferedImage f = foto(ctx);
        if (f == null || eingesetzt == null) return;
        // Das linke Feld liegt 459 px links und 43 px tiefer als das rechte (siehe K_LINKS_A).
        // 02:40: ab y 80 statt 118 -- sonst verdeckte das blaue Band oben den Titel.
        int bx = sx(f, 141), by = sy(f, 80);
        int w = Math.min(sx(f, 425), f.getWidth() - bx), h = Math.min(sy(f, 560), f.getHeight() - by);
        if (bx < 0 || w <= 0 || h <= 0) return;
        BufferedImage kopie = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        kopie.getGraphics().drawImage(f.getSubimage(bx, by, w, h), 0, 0, null);
        lFotos.computeIfAbsent(name, n -> new ArrayList<>()).add(0, new Object[]{kopie, werte(eingesetzt), new ArrayList<>(eingesetzt), Boolean.TRUE});
    }

    private void fotoMerken(BufferedImage f, String name, List<String> werte, int dx) {
        String text = werte(werte);
        String schluessel = name + "|" + text + "|" + lies(f, k(K_LEVEL_R, dx - L_VERSATZ));
        if (f == null || !lFotoSchon.add(schluessel)) return;
        int x = sx(f, 600 + dx - L_VERSATZ), y = sy(f, 75);
        int w = Math.min(sx(f, 425), f.getWidth() - x), h = Math.min(sy(f, 560), f.getHeight() - y);
        if (x < 0 || w <= 0 || h <= 0) return;
        BufferedImage kopie = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        kopie.getGraphics().drawImage(f.getSubimage(x, y, w, h), 0, 0, null);
        lFotos.computeIfAbsent(name, n -> new ArrayList<>()).add(new Object[]{kopie, text, new ArrayList<>(werte)});
        fotoGut(f, name, werte);
    }

    /** Je Name eine Uebersicht logs/stigmas/gute/uebersicht-NAME.png. Liefert die Dateien. */
    /**
     * Autor, 11.10. 02:05: "Immer wenn eine Sorte durch ist (Mind, Will ...), einen Screenshot von allen, die er gefunden
     * hat -- gruen markiert = Empfehlung, gelb = gut, aber koennte besser sein -- und sagen, ob er eins ausgeruestet hat."
     * Gruen: das jetzt eingesetzte (bzw. gleich gute). Gelb: 3x mag. Schaden/Leben/Schaden, aber schlechter als das
     * eingesetzte. Ohne Rahmen: 3x eines anderen Werts. Datei logs/stigmas/gute/uebersicht-NAME.png.
     */
    private static volatile java.util.function.BiConsumer<String, byte[]> discordBild;

    /** Die Discord-Bruecke meldet hier, wohin die Uebersicht je Sorte geschickt wird. */
    public static void discordBildZiel(java.util.function.BiConsumer<String, byte[]> ziel) {
        discordBild = ziel;
    }

    private String sorteFertig(String name) {
        List<Object[]> bilder = lFotos.getOrDefault(name, List.of());
        int getauscht = 0;
        for (String e : lEquips) if (e.startsWith("Bull " + name + ":")) getauscht++;
        String gross = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        List<String> tausche = new ArrayList<>();
        for (String t : lTausche) if (t.startsWith(name + "|")) tausche.add(t.substring(name.length() + 1));
        // Pruefung 11.10.: Im Probelauf wird nichts gedrueckt -- "(dry run)"-Tausche zaehlen nicht, sonst stuende das nur
        // gedachte Stigma als "CURRENTLY EQUIPPED" im Bild.
        int echteTausche = 0;
        for (String t : tausche) if (!t.endsWith("(dry run)")) echteTausche++;
        List<String> jetztDrin = eingesetzt;
        if (echteTausche == 0) {
            for (Object[] bild : bilder) {
                if (bild.length > 3 && Boolean.TRUE.equals(bild[3])) {
                    @SuppressWarnings("unchecked") List<String> m = (List<String>) bild[2];
                    jetztDrin = m;
                }
            }
        }
        String kopfText = "Bull " + gross + "  |  equipped: " + (jetztDrin == null ? "none" : werte(jetztDrin) + " (rank " + rang(jetztDrin) + ")")
                + "  |  " + (echteTausche > 0 ? "swapped " + echteTausche + "x" : tausche.isEmpty() ? "not swapped" : "dry run, nothing swapped");
        String meldung = "**Stigmas " + titan.anzeige() + " -- " + gross + "**";
        String zeile2 = tausche.isEmpty() ? "Blue = currently equipped, green = as good or better, yellow = good but worse"
                : "Swapped: " + String.join(" ; ", tausche);
        Log.info(WER, Sprache.t("Kind " + name + " done: " + (eingesetzt == null ? "nothing equipped" : "equipped " + werte(eingesetzt))
                + (getauscht > 0 ? ", " + getauscht + "x swapped" : ", not swapped") + ", " + bilder.size() + " pictures.",
                "Sorte " + name + " fertig: " + (eingesetzt == null ? "nichts eingesetzt" : "eingesetzt " + werte(eingesetzt))
                + (getauscht > 0 ? ", " + getauscht + "x getauscht" : ", nicht getauscht") + ", " + bilder.size() + " Bilder."));
        int cw = 425, ch = 560, lab = 46, kopf = 92, rand = 8, cols = Math.max(1, Math.min(4, bilder.size())),
                rows = (bilder.size() + cols - 1) / cols;
        BufferedImage c = new BufferedImage(Math.max(cols * cw, 1100), kopf + (bilder.isEmpty() ? 40 : rows * (ch + lab)),
                BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = c.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(30, 24, 40));
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
        g.drawString(kopfText, 12, 34);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 19));
        g.setColor(new java.awt.Color(220, 210, 235));
        g.drawString(zeile2, 12, 70);
        if (bilder.isEmpty()) g.drawString("No Bull stigma with 3x of one stat found.", 12, 112);
        java.awt.Color gruen = new java.awt.Color(40, 220, 70), gelb = new java.awt.Color(255, 212, 40),
                blau = new java.awt.Color(60, 160, 255), grau = new java.awt.Color(150, 140, 165);
        // Autor, 11.10. 02:35: "das eingesetzte Stigma hinzufuegen und klar machen, dass es das aktuell benutzte ist" --
        // blauer Rahmen und ein Band "CURRENTLY EQUIPPED". Nach einem Tausch: das alte grau ("WAS EQUIPPED"), das neue blau.
        boolean getauschtJetzt = echteTausche > 0, blauVergeben = false;
        for (int i = 0; i < bilder.size(); i++) {
            int x = (i % cols) * cw, y = kopf + (i / cols) * (ch + lab);
            @SuppressWarnings("unchecked") List<String> w = (List<String>) bilder.get(i)[2];
            g.drawImage((BufferedImage) bilder.get(i)[0], x + rand, y + rand, cw - 2 * rand, ch - 2 * rand, null);
            int vergleich = jetztDrin == null ? 1 : rang(w) < rang(jetztDrin) ? 1 : rang(w) > rang(jetztDrin) ? -1
                    : nebenVergleich(w, jetztDrin);
            boolean markiert = bilder.get(i).length > 3 && Boolean.TRUE.equals(bilder.get(i)[3]);
            boolean istEingesetzt = !blauVergeben && jetztDrin != null
                    && (markiert ? !getauschtJetzt : getauschtJetzt && w.equals(jetztDrin));
            boolean warEingesetzt = markiert && getauschtJetzt;
            if (istEingesetzt) blauVergeben = true;
            java.awt.Color farbe = istEingesetzt ? blau : warEingesetzt ? grau : vergleich >= 0 ? gruen
                    : de.hustlebot.core.Stigmaplan.interessant(w) ? gelb : null;
            if (farbe != null) {
                g.setColor(farbe);
                g.setStroke(new java.awt.BasicStroke(istEingesetzt ? rand * 2 : rand));
                g.drawRect(x + rand / 2, y + rand / 2, cw - rand, ch - rand);
            }
            if (istEingesetzt || warEingesetzt) {
                g.setColor(farbe);
                g.fillRect(x + rand, y + rand, cw - 2 * rand, 40);
                g.setColor(java.awt.Color.WHITE);
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 24));
                g.drawString(istEingesetzt ? "CURRENTLY EQUIPPED" : "WAS EQUIPPED (swapped out)", x + 20, y + rand + 29);
            }
            g.setColor(farbe != null ? farbe : new java.awt.Color(220, 210, 235));
            String beschriftung = (istEingesetzt ? "EQUIPPED: " : warEingesetzt ? "OLD: " : farbe == gruen ? "RECOMMENDED: " : "")
                    + bilder.get(i)[1];
            // 02:40: lange Beschriftungen liefen ins Nachbarbild -- Schrift verkleinern, bis sie passt.
            int groesse = 19;
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, groesse));
            while (groesse > 12 && g.getFontMetrics().stringWidth(beschriftung) > cw - 16) {
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, --groesse));
            }
            g.drawString(beschriftung, x + 8, y + ch + 30);
        }
        g.dispose();
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "stigmas", "gute");
            java.nio.file.Files.createDirectories(ordner);
            java.nio.file.Path datei = ordner.resolve("uebersicht-" + name + ".png");
            javax.imageio.ImageIO.write(c, "png", datei.toFile());
            Log.info(WER, Sprache.t("Overview ", "Uebersicht ") + name + ": " + datei.toAbsolutePath());
            var ziel = discordBild;
            if (ziel != null) {
                try {
                    ziel.accept(meldung, java.nio.file.Files.readAllBytes(datei));
                } catch (Exception ex) {
                    Log.warn(WER, Sprache.t("Overview not sent to Discord: ", "Uebersicht nicht nach Discord: ") + ex.getMessage());
                }
            }
            return datei.toAbsolutePath().toString();
        } catch (Exception ex) {
            Log.warn(WER, Sprache.t("Overview " + name + " not saved: ", "Uebersicht " + name + " nicht gespeichert: ") + ex.getMessage());
            return null;
        }
    }

    private List<String> uebersichtenBauen() {
        List<String> dateien = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> e : lFotos.entrySet()) {
            List<Object[]> bilder = e.getValue();
            if (bilder.isEmpty()) continue;
            int cw = 425, ch = 560, lab = 46, kopf = 50, cols = Math.min(4, bilder.size()), rows = (bilder.size() + cols - 1) / cols;
            BufferedImage c = new BufferedImage(cols * cw, kopf + rows * (ch + lab), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = c.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(30, 24, 40));
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 30));
            String nameGross = e.getKey().substring(0, 1).toUpperCase(Locale.ROOT) + e.getKey().substring(1);
            g.drawString("Bull " + nameGross + " -- 3x or more of one stat (" + bilder.size() + ")", 14, 36);
            for (int i = 0; i < bilder.size(); i++) {
                int x = (i % cols) * cw, y = kopf + (i / cols) * (ch + lab);
                g.drawImage((BufferedImage) bilder.get(i)[0], x, y, cw, ch, null);
                g.setColor(new java.awt.Color(255, 214, 60));
                g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 20));
                g.drawString((String) bilder.get(i)[1], x + 10, y + ch + 30);
            }
            g.dispose();
            try {
                java.nio.file.Path ordner = java.nio.file.Path.of("logs", "stigmas", "gute");
                java.nio.file.Files.createDirectories(ordner);
                java.nio.file.Path datei = ordner.resolve("uebersicht-" + e.getKey() + ".png");
                javax.imageio.ImageIO.write(c, "png", datei.toFile());
                dateien.add(datei.toAbsolutePath().toString());
            } catch (Exception ex) {
                Log.warn(WER, Sprache.t("Overview " + e.getKey() + " not saved: ", "Uebersicht " + e.getKey() + " nicht gespeichert: ") + ex.getMessage());
            }
        }
        return dateien;
    }

    /** Das geoeffnete Kandidaten-Fenster lesen, bewerten, handeln, schliessen. */
    private Outcome kandidat(BotContext ctx, RunTracker runs) {
        String name = L_NAMEN[lNameIdx];
        BufferedImage f = null;
        boolean zwei = false, einzeln = false;
        for (int i = 0; i < 8; i++) {
            f = foto(ctx);
            if (f == null) continue;
            zwei = zweiFenster(f);
            einzeln = !zwei && TITEL.matcher(lies(f, K_TITEL)).find();
            if (zwei || einzeln) break;
            ctx.sleep(150);
        }
        if (!zwei && !einzeln) {
            lFehler++;
            fotoSichern(f, "kein-fenster");
            Log.warn(WER, Sprache.t("After the tap no readable window opened.", "Nach dem Tipp ging kein lesbares Fenster auf."));
            return Outcome.ACTED;
        }
        int dx = zwei ? L_VERSATZ : 0;
        String titel = liesGemerkt(f, k(K_TITEL, dx), titelGedaechtnis, TITEL);
        Matcher m = TITEL.matcher(titel);
        boolean bull = false;
        if (m.find() && name.equals(aehnlich(m.group(2), NAMEN, 2))) {
            // "Bull" kam im Video als "Butt" an. Eindeutig Bull (1 Buchstabe) -- oder bis 2 Buchstaben, wenn auch die
            // Zeile "Included in Bull Set" Bull sagt (Pruefung 22:50: "Boar"/"Bear" liegen nur 3 von "Bull").
            String set = m.group(1).toLowerCase(Locale.ROOT);
            bull = "bull".equals(aehnlich(set, ALLE_SETS, 1));
            if (!bull && abstand(set, "bull") <= 2) {
                Matcher in = Pattern.compile("in\\s+([A-Za-z]+)\\s+set", Pattern.CASE_INSENSITIVE).matcher(lies(f, k(K_INCLUDED, dx)));
                bull = in.find() && abstand(in.group(1).toLowerCase(Locale.ROOT), "bull") <= 1;
            }
        }
        if (!bull) {
            Log.info(WER, Sprache.t("No Bull " + name + " (\"" + titel.trim() + "\") -- only closed.", "Kein Bull " + name + " (\"" + titel.trim() + "\") -- nur geschlossen."));
            tippe(ctx, SCHLIESSEN_L, null);
            ruhe(ctx, 400);
            return Outcome.ACTED;
        }
        lGeoeffnet++;
        // Kandidat: zwei Blicke (vor und nach dem Hochschieben), bei Lesefehlern noch einmal.
        List<String> werte = werteLesen(ctx, f, dx);
        if (werte == null) {
            lFehler++;
            fotoSichern(f, "werte-unlesbar");
            lUebergangen.add(Sprache.t("Bull " + name + " (stats not readable)", "Bull " + name + " (Werte nicht lesbar)"));
            tippe(ctx, SCHLIESSEN_L, null);
            ruhe(ctx, 400);
            return Outcome.ACTED;
        }
        // Schon gesehen (nach dem Weiterschieben oder nach einem Equip fängt er oben neu an): nur schliessen.
        String kennung = String.join(",", werte);
        if (!lGesehen.add(kennung)) {
            lDoppelt++;
            tippe(ctx, SCHLIESSEN_L, null);
            ctx.sleep(300);
            return Outcome.ACTED;
        }
        // Das nach einem Tausch zurueckgekommene alte Stigma ("New") nicht noch einmal bewerten.
        for (List<String> alt : lAbgelegt) {
            if (alt.equals(werte)) {
                Log.info(WER, Sprache.t("The " + name + " just swapped out -- only closed.", "Das eben abgelegte " + name + " -- nur geschlossen."));
                tippe(ctx, SCHLIESSEN_L, null);
                ruhe(ctx, 400);
                return Outcome.ACTED;
            }
        }
        // Eingesetztes: einmal je Name aus dem linken Feld lesen (leer = nur ein Fenster), zweimal gleich gelesen.
        if (eingesetzt == null && !slotLeer) {
            if (zwei) {
                List<String> e1 = werteLinksLesen(ctx), e2 = e1 != null ? werteLinksLesen(ctx) : null;
                if (e1 == null || !e1.equals(e2)) {
                    // Pruefung 22:50: sonst wuerde JEDES weitere neue Stigma dieses Namens geoeffnet und verloere sein
                    // "New", ohne bewertet zu werden. Lieber zum naechsten Namen und melden.
                    lFehler++;
                    eingesetztUnlesbar = true;
                    fotoSichern(ctx.frame(), "eingesetzt-unlesbar");
                    lUebergangen.add(Sprache.t("Slot " + name + ": equipped stigma not readable -- kind skipped",
                            "Slot " + name + ": eingesetztes Stigma nicht lesbar -- Name uebersprungen"));
                    tippe(ctx, SCHLIESSEN_L, null);
                    ruhe(ctx, 400);
                    return Outcome.ACTED;
                }
                eingesetzt = e1;
                Log.info(WER, Sprache.t("Equipped (" + name + "): " + werte(eingesetzt) + " -- rank " + rang(eingesetzt),
                        "Eingesetzt (" + name + "): " + werte(eingesetzt) + " -- Rang " + rang(eingesetzt)));
                eingesetztMerken(ctx, name);
            } else {
                slotLeer = true;
                fotoSichern(f, "einzelfenster-leerer-slot");
                Log.info(WER, Sprache.t("Slot " + name + " is empty -- the best one found will be equipped.",
                        "Slot " + name + " ist leer -- das beste gefundene wird eingesetzt."));
            }
        }
        int rk = rang(werte), re = slotLeer && eingesetzt == null ? 99 : rang(eingesetzt);
        int neben = rk == re && eingesetzt != null ? nebenVergleich(werte, eingesetzt) : 0;
        boolean besser = rk < re || (rk == re && neben > 0);
        boolean gleich = rk == re && neben == 0;
        int hoechst = Math.max(anzahl(werte, "magic damage"), Math.max(anzahl(werte, "health"), anzahl(werte, "damage")));
        int gleichMax = 0;
        for (String art : werte) gleichMax = Math.max(gleichMax, anzahl(werte, art));
        // 11.10.: welche Werte hochgelevelt werden, steht unter !stigmas einstellen (Standard: 3x/4x mag. Schaden, Leben,
        // Schaden). "Gleich gut wie das eingesetzte" wird auf 3 gelevelt -- ausser Hochleveln ist ganz aus.
        int ziel = de.hustlebot.core.Stigmaplan.levelZiel(werte);
        // 23.10.: mit der Grundregel (5x/4x/3x gleiche) nicht -- sonst bekaemen "gleich gute" ohne 3 gleiche Werte Level 3.
        if (ziel == 0 && gleich && !de.hustlebot.core.Stigmaplan.gleicheRegel()
                && !de.hustlebot.core.Stigmaplan.level().isEmpty()) ziel = 3;
        // Autor 01:30: 3x eines ANDEREN Werts (Crit, Dodge, Ruestung ...) -- auch leveln, aber nur mit einem lila
        // (epischen) Strahl, das sind +2 Level.
        boolean episch = ziel == 0 && de.hustlebot.core.Stigmaplan.episch(werte);
        String wer = "Bull " + name + ": " + werte(werte) + Sprache.t(" (rank ", " (Rang ") + rk + ")";
        Log.info(WER, wer + Sprache.t(besser ? " -- BETTER than equipped (rank " + (re == 99 ? "empty" : re) + ")"
                : gleich ? " -- as good" : " -- not better",
                besser ? " -- BESSER als eingesetzt (Rang " + (re == 99 ? "leer" : re) + ")"
                : gleich ? " -- gleich gut" : " -- nicht besser") + (ziel > 0 ? Sprache.t(", to level ", ", auf Level ") + ziel : ""));
        runs.progress(Sprache.t("Stigma levelling: " + name + " looked at", "Stigmas leveln: " + name + " angesehen"));
        if (gleichMax >= 3 || besser) {   // auch das, was eingesetzt wird (fuer die gruene Markierung)
            fotoMerken(f, name, werte, dx);   // Bild VOR dem Hochschieben (Wert 1 noch zu sehen)
            if (!lGute.contains(wer)) lGute.add(wer);
        }
        if (!probe && ziel > 0) {
            String lv = hochleveln(ctx, dx, ziel, false);
            if (lv != null) lLevel.add("Bull " + name + " " + lv);
        } else if (!probe && episch) {
            String lv = hochleveln(ctx, dx, 3, true);
            if (lv != null) lLevel.add("Bull " + name + " " + lv + Sprache.t(" (purple)", " (lila)"));
        }
        lTempo();
        if (besser) {
            boolean getauscht = false;
            if (!probe) {
                getauscht = einsetzen(ctx, dx, name, wer);
                if (!getauscht) {
                    lUebergangen.add(wer + Sprache.t(" -- Equip not safely possible", " -- Equip nicht sicher moeglich"));
                    tippe(ctx, SCHLIESSEN_L, null);
                    ruhe(ctx, 400);
                    return Outcome.ACTED;
                }
            }
            lEquips.add(wer + (probe ? Sprache.t(" (dry run: NOT pressed)", " (Probelauf: NICHT gedrueckt)") : ""));
            lTausche.add(name + "|" + werte(eingesetzt) + "  ->  " + werte(werte) + (probe ? " (dry run)" : ""));
            if (eingesetzt != null) lAbgelegt.add(eingesetzt);
            eingesetzt = werte;
            slotLeer = false;
            if (getauscht) {
                // Das alte kommt mit "New" in die Liste zurueck -- von oben neu durchsehen.
                zeiger = 0;
                return Outcome.ACTED;
            }
        }
        tippe(ctx, SCHLIESSEN_L, null);
        ruhe(ctx, 400);
        return Outcome.ACTED;
    }

    /**
     * Equip nur, wenn das Fenster noch das richtige Stigma zeigt und an der Stelle wirklich der gruene Knopf ist
     * (gemessen: Equip 7/163/20 gruen, Break apart 182/0/1 rot). Danach muss das Fenster zu sein.
     */
    private boolean einsetzen(BotContext ctx, int dx, String name, String wer) {
        BufferedImage g = foto(ctx);
        if (g == null) return false;
        Matcher m = TITEL.matcher(lies(g, k(K_TITEL, dx)));
        if (!m.find() || !name.equals(aehnlich(m.group(2), NAMEN, 2))) {
            fotoSichern(g, "equip-titel-falsch");
            Log.warn(WER, Sprache.t("Before Equip the window no longer shows Bull " + name + " -- not pressed.",
                    "Vor Equip steht nicht mehr Bull " + name + " im Fenster -- nicht gedrueckt."));
            return false;
        }
        int[] p = r(EQUIP_R, dx - L_VERSATZ);
        // Neben dem weissen Schriftzug "Equip" pruefen (01:40: genau auf dem Text stand 141/154/130).
        int c = g.getRGB(sx(g, p[0] + 55), sy(g, p[1]));
        int rr = c >> 16 & 255, gg = c >> 8 & 255, bb = c & 255;
        if (!(gg > 130 && rr < 120 && bb < 90)) {
            fotoSichern(g, "equip-nicht-gruen");
            Log.warn(WER, Sprache.t("No green button at the Equip spot (" + rr + "/" + gg + "/" + bb + ") -- not pressed.",
                    "An der Equip-Stelle ist kein gruener Knopf (" + rr + "/" + gg + "/" + bb + ") -- nicht gedrueckt."));
            return false;
        }
        tippe(ctx, p, "Equip -- " + wer);
        for (int i = 0; i < 8; i++) {
            ruhe(ctx, 250);
            if (!stigmaFensterOffen(foto(ctx))) return true;
        }
        fotoSichern(ctx.frame(), "equip-fenster-noch-offen");
        Log.warn(WER, Sprache.t("After Equip the window is still open -- not counted as swapped.",
                "Nach Equip ist das Fenster noch offen -- nicht als getauscht gezaehlt."));
        return false;
    }

    private static final int[] EQUIP_R = {900, 657};
    private static final int[] INCREASE_R = {809, 571};
    // Kaesten im rechten Feld (fuer das Einzelfenster um L_VERSATZ nach links)
    private static final int[] K_WERTE_A = {612, 325, 380, 150};
    private static final int[] K_WERTE_B = {612, 348, 380, 132};
    // 11.10. 02:25: bei 1-Stern-Stigmas steht der Wert bei y 383 -- ab 370 wurde er abgeschnitten ("nicht lesbar").
    private static final int[] K_LINKS_A = {152, 345, 410, 175};   // Pruefung 22:50: linkes Feld 43 px tiefer
    private static final int[] K_LINKS_B = {152, 345, 410, 182};
    private static final int[] K_INCLUDED = {420, 272, 340, 36};     // "Included in Bull Set" (Einzelfenster-Lage)
    private static final int[] K_LEVEL_R = {700, 484, 220, 34};      // "Level 1/20" im rechten Feld

    /** Die 5 Werte des Kandidaten (rechtes Feld bzw. Einzelfenster), oder null. Bei Lesefehlern ein zweiter Versuch. */
    private List<String> werteLesen(BotContext ctx, BufferedImage f, int dx) {
        int sx = dx - L_VERSATZ;   // Kaesten sind fuer das rechte Feld angegeben
        List<String> offen = null;
        boolean unlocks = false;
        BufferedImage bild = f;
        for (int versuch = 0; versuch < 2 && offen == null; versuch++) {
            if (versuch > 0) {
                ruhe(ctx, 300);
                bild = foto(ctx);
            }
            List<String> o = new ArrayList<>();
            boolean u = false, kaputt = false;
            for (String z : liesZeilen(bild, k(K_WERTE_A, sx))) {
                if (istUnlocks(z)) { u = true; break; }
                String w = wertArt(z);
                if ("?".equals(w)) kaputt = true;
                else if (w != null) o.add(w);
            }
            if (kaputt || o.isEmpty() || (!u && o.size() != 5)) continue;
            offen = o;
            unlocks = u;
        }
        if (offen == null) return null;
        if (!unlocks) return offen;   // 5 Sterne: alles offen
        // Hochschieben, zweiter Blick (Wert 1 ist danach oben weg).
        int x = px(ctx, 900 + sx);
        // 01:45 (Autor: "schneller"): schneller Wisch statt langsamem Zug -- das Feld laesst sich ohnehin nur ~21 px
        // schieben (Bild 3630 -> 3640: "Unlocks" 383 -> 362), mehr geht nicht.
        ziehe(ctx, true, x, py(ctx, 430), x, py(ctx, 355));
        ruhe(ctx, 400);
        int fehlt = 5 - offen.size();
        for (int versuch = 0; versuch < 2; versuch++) {
            BufferedImage g = foto(ctx);
            if (g == null) return null;
            List<String> unten = new ArrayList<>();
            boolean nach = false, kaputt = false;
            for (String z : liesZeilen(g, k(K_WERTE_B, sx))) {
                if (istUnlocks(z)) { nach = true; continue; }
                String w = wertArt(z);
                if (!nach) continue;
                if ("?".equals(w)) kaputt = true;
                else if (w != null) unten.add(w);
            }
            if (!kaputt && unten.size() >= fehlt) {
                List<String> alle = new ArrayList<>(offen);
                alle.addAll(unten.subList(unten.size() - fehlt, unten.size()));
                return alle;
            }
            Log.debug(WER, "Werte unten unvollstaendig: offen " + offen + ", unten " + unten);
            ruhe(ctx, 300);
        }
        return null;
    }

    /**
     * Das eingesetzte Stigma im linken Feld. Das Feld liegt 43 px tiefer als das rechte (Pruefung 22:50), darum die
     * groesseren Kaesten. Jeder Blick wird zweimal gelesen und nur bei gleichem Ergebnis geglaubt.
     */
    private List<String> werteLinksLesen(BotContext ctx) {
        List<String> offen = null;
        boolean unlocks = false;
        List<String> vorher = null;
        boolean hochGeholt = false;
        // 11.10. 02:19: das linke Feld blendet sich langsam ein -- bis zu 6 Blicke statt 3.
        for (int versuch = 0; versuch < 6 && offen == null; versuch++) {
            List<String> o = new ArrayList<>();
            boolean u = false, kaputt = false;
            for (String z : liesZeilen(foto(ctx), K_LINKS_A)) {
                if (istUnlocks(z)) { u = true; break; }
                String w = wertArt(z);
                if ("?".equals(w)) kaputt = true;
                else if (w != null) o.add(w);
            }
            // 11.10. 02:31: Das Feld war noch vom letzten Mal hochgeschoben -- "Unlocks" da, Wert 1 unter dem Balken
            // versteckt. Dann einmal nach unten ziehen (ganz nach oben schieben) und neu lesen.
            if (u && o.isEmpty() && !hochGeholt) {
                hochGeholt = true;
                int xs = px(ctx, 360);
                ziehe(ctx, true, xs, py(ctx, 400), xs, py(ctx, 480));
                ruhe(ctx, 400);
                vorher = null;
                continue;
            }
            if (kaputt || o.isEmpty() || (!u && o.size() != 5)) { vorher = null; ruhe(ctx, 250); continue; }
            if (o.equals(vorher)) {
                offen = o;
                unlocks = u;
            }
            vorher = o;
            ruhe(ctx, 150);
        }
        if (offen == null) return null;
        if (!unlocks) return offen;
        int x = px(ctx, 360);
        ziehe(ctx, true, x, py(ctx, 480), x, py(ctx, 400));
        ruhe(ctx, 400);
        int fehlt = 5 - offen.size();
        List<String> vorherUnten = null;
        for (int versuch = 0; versuch < 3; versuch++) {
            List<String> unten = new ArrayList<>();
            boolean nach = false, kaputt = false;
            for (String z : liesZeilen(foto(ctx), K_LINKS_B)) {
                if (istUnlocks(z)) { nach = true; continue; }
                String w = wertArt(z);
                if (!nach) continue;
                if ("?".equals(w)) kaputt = true;
                else if (w != null) unten.add(w);
            }
            if (!kaputt && unten.size() >= fehlt) {
                List<String> rest = unten.subList(unten.size() - fehlt, unten.size());
                if (rest.equals(vorherUnten)) {
                    List<String> alle = new ArrayList<>(offen);
                    alle.addAll(rest);
                    return alle;
                }
                vorherUnten = new ArrayList<>(rest);
            }
            ruhe(ctx, 150);
        }
        return null;
    }

    /** Text in einem Kasten lesen, Zeile fuer Zeile. */
    private static List<String> liesZeilen(BufferedImage b, int[] k) {
        List<String> z = new ArrayList<>();
        if (b == null) return z;
        int x = sx(b, k[0]), y = sy(b, k[1]);
        int w = Math.min(sx(b, k[2]), b.getWidth() - x), h = Math.min(sy(b, k[3]), b.getHeight() - y);
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return z;
        BufferedImage aus = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(b.getSubimage(x, y, w, h), 0, 0, w * 2, h * 2, null);
        g.dispose();
        for (String t : Texterkennung.lies(aus)) if (!t.isBlank()) z.add(t.trim());
        return z;
    }

    /** Die Zeile "Unlocks at the following ranks:" (kam auch als "Untocks ..." an). */
    private static boolean istUnlocks(String zeile) {
        String z = zeile.toLowerCase(Locale.ROOT);
        return z.contains("unlock") || z.contains("following") || z.contains("ranks");
    }

    /**
     * "Magic damage: +866" -> "magic damage" (eine der 7 Wertarten); keine Wertzeile -> null; unsicher -> "?".
     * Pruefung 22:50: Die Texterkennung trennt oft Name und Zahl ("Magic damage:" / "+866") -- der Name mit Doppelpunkt
     * genuegt. Und "Magic" kam als "tac"/"MA" an: "damage"/"armor" ohne "magic" gilt nur, wenn davor hoechstens ein
     * Zeichen steht (ein Symbol wie "O Armor"), sonst ist die Zeile unsicher.
     */
    private static String wertArt(String zeile) {
        Matcher m = WERT.matcher(zeile);
        if (!m.find()) return null;
        String w = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replace("armour", "armor");
        w = w.replaceAll("^magic ?", "magic ");
        if ((w.equals("damage") || w.equals("armor")) && zeile.substring(0, m.start()).replaceAll("\\s", "").length() > 1) {
            return "?";
        }
        return w;
    }

    private static int anzahl(List<String> werte, String art) {
        int n = 0;
        if (werte != null) for (String w : werte) if (w.equals(art)) n++;
        return n;
    }

    private static String werte(List<String> w) {
        if (w == null) return Sprache.t("empty", "leer");
        // Autor, 11.10. 01:48: immer mit Anzahl ("1x crit") und die groesste Anzahl zuerst --
        // "3x magic damage, 1x crit, 1x damage".
        Map<String, Integer> z = new LinkedHashMap<>();
        for (String x : w) z.merge(x, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(z.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Integer> e : l) s.append(s.length() > 0 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey());
        return s.toString();
    }

    /**
     * Autors Rangliste fuer Bull (10.10.), 1 = am besten. MD = magischer Schaden, L = Leben, S = (normaler) Schaden.
     *  1 5xMD oder 4xMD+1L | 2 4xMD+1 anderer (kein S) | 3 4xMD+1S | 4 4xL+1MD (auch 5xL) | 5 4xL+1 anderer (kein S)
     *  6 4xL+1S | 7 3xMD+2L | 8 3xMD+2 verschiedene (auch mit S, Autor 20:28: "Platz 8") | 9 3xMD+2 gleiche
     *  10 3xL+2MD | 11 Rest (innerhalb: mehr MD, dann mehr L)
     */
    static int rang(List<String> w) {
        if (w == null) return 99;
        // 11.10. 02:55: Die Stufen stehen in Stigmaplan -- Autors 11 Stufen (10.10. 20:28) fuer den eingestellten
        // Haupt- und Zweitwert, oder seine eigene Rangliste (!stigmas einstellen, Knopf "Rangliste").
        return de.hustlebot.core.Stigmaplan.rang(w);
    }

    /**
     * Gleicher Rang: Nebenwerte vergleichen (Autor, 20:28: "Leben > mag. Ruestung > Dodge > Ruestung > Crit > Schaden").
     * Im Rest (Rang 11) zaehlt vorher: mehr MD, dann mehr L. >0 = a besser, 0 = gleich.
     */
    static int nebenVergleich(List<String> a, List<String> b) {
        // Autor 23.10.: "Alle Stigmas sind gleich viel wert" -- mit der Grundregel entscheidet nur, wie viele gleiche Werte.
        if (de.hustlebot.core.Stigmaplan.gleicheRegel()) return 0;
        if (rang(a) == de.hustlebot.core.Stigmaplan.restRang()) {
            int d = anzahl(a, de.hustlebot.core.Stigmaplan.haupt()) - anzahl(b, de.hustlebot.core.Stigmaplan.haupt());
            if (d != 0) return d;
            d = anzahl(a, de.hustlebot.core.Stigmaplan.zweit()) - anzahl(b, de.hustlebot.core.Stigmaplan.zweit());
            if (d != 0) return d;
        }
        List<Integer> wa = gewichte(a), wb = gewichte(b);
        for (int i = 0; i < Math.min(wa.size(), wb.size()); i++) {
            int d = wa.get(i) - wb.get(i);
            if (d != 0) return d;
        }
        return 0;
    }

    private static List<Integer> gewichte(List<String> w) {
        List<Integer> g = new ArrayList<>();
        for (String x : w) {
            // 11.10.: Gewichte aus Stigmaplan (Hauptwert 0, Zweitwert ganz oben, "falscher" Schaden ganz unten); beim
            // Standard genau Autors Reihenfolge Leben > mag. Ruestung > Dodge > Ruestung > Crit > Schaden.
            int v = de.hustlebot.core.Stigmaplan.gewicht(x);
            if (v > 0) g.add(v);
        }
        g.sort(java.util.Comparator.reverseOrder());
        return g;
    }

    // --- Hochleveln (Fenster "Your Power Rays", Aufnahme 10.10. Bild 4634) ------------------------------------------
    // Increase im Stigma-Fenster -> links das Stigma mit "Level 1/20 -> 8/20", rechts "Your Power Rays:" mit dem
    // orangen Strahl (705,185). Jeder Tipp legt einen Strahl ins Fach; dann "Increase" (354,642), dann X (1121,33).
    // "More Power Rays" (808,364) fuehrt in den Laden -- NIE.
    private static final int[] ORANGER_STRAHL = {705, 185};
    private static final int[] RAYS_INCREASE = {354, 642};
    private static final int[] LILA_STRAHL = {808, 184};   // Autors Bild 11.10.: ein lila Strahl = +2 Level (1 -> 3)
    private static final int[] RAYS_X = {1121, 33};
    private static final int[] K_RAYS_TITEL = {620, 100, 300, 36};
    private static final int[] K_RAYS_LEVEL = {200, 498, 320, 36};

    /**
     * Auf Level "ziel" bringen. Ergebnis fuer die Liste ("von 1 auf 3"), oder null.
     * Pruefung 22:50: vorher das jetzige Level lesen (schon hoch genug -> nichts tun); je Tipp nur EIN Strahl und danach
     * die Vorschau neu lesen; Increase nur, wenn die LETZTE Lesung gueltig ist und das Ziel erreicht; hoechstens 4
     * Strahlen fuer Level 3, 80 fuer Level 20; nach dem X pruefen, dass das Fenster zu ist.
     */
    private String hochleveln(BotContext ctx, int dx, int ziel, boolean episch) {
        int sx = dx - L_VERSATZ;
        Matcher jetzt = LEVEL.matcher(lies(foto(ctx), k(K_LEVEL_R, sx)));
        if (!jetzt.find()) {
            Log.warn(WER, Sprache.t("Level in the stigma window not readable -- not levelled.", "Level im Stigma-Fenster nicht lesbar -- nicht gelevelt."));
            return null;
        }
        int von = Integer.parseInt(jetzt.group(1)), max = Integer.parseInt(jetzt.group(2));
        // Autor 01:31: "Im zweiten Durchgang, wenn ein Bull-Stigma schon Level 4 oder ein anderes Level hat, nicht
        // weiter hochleveln." Nur fuer Level 20 (4x/5x) geht es weiter.
        if (von >= ziel || von >= max || (ziel <= 3 && von > 1)) return null;
        tippe(ctx, r(INCREASE_R, sx), "Increase (Level " + von + " -> " + Math.min(ziel, max) + ")");
        ruhe(ctx, 900);
        // 11.10. 02:06: Das Fenster kam erst nach gut einer Sekunde -- der Bot gab auf, und der naechste Lauf stand
        // im offenen Fenster. Darum bis zu 3 s warten, und kommt es doch noch, sicher wieder schliessen.
        BufferedImage f = foto(ctx);
        for (int w = 0; w < 6 && (f == null || !enthaelt(lies(f, K_RAYS_TITEL), "power")); w++) {
            ctx.sleep(400);
            f = foto(ctx);
        }
        if (f == null || !enthaelt(lies(f, K_RAYS_TITEL), "power")) {
            fotoSichern(f, "power-rays-fenster-fehlt");
            Log.warn(WER, Sprache.t("The window \"Your Power Rays\" did not open -- not levelled.", "Das Fenster \"Your Power Rays\" kam nicht -- nicht gelevelt."));
            return null;
        }
        Pattern vorschau = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\D+(\\d+)\\s*/\\s*(\\d+)");
        int grenze = episch ? 1 : ziel <= 3 ? 4 : 80, erreicht = -1;
        for (int i = 0; i <= grenze; i++) {
            Matcher m = vorschau.matcher(lies(foto(ctx), K_RAYS_LEVEL));
            erreicht = m.find() ? Integer.parseInt(m.group(3)) : -1;
            if (erreicht >= Math.min(ziel, max)) break;
            if (i == grenze) break;
            tippe(ctx, episch ? LILA_STRAHL : ORANGER_STRAHL, i == 0 ? Sprache.t(episch ? "Purple (epic) Power Ray" : "Orange Power Ray",
                    episch ? "Lila (epischer) Power Ray" : "Oranger Power Ray") : null);
            ruhe(ctx, 250);
        }
        boolean ok = erreicht >= Math.min(ziel, max);
        if (ok) {
            tippe(ctx, RAYS_INCREASE, Sprache.t("Increase (use Power Rays, preview level " + erreicht + ")", "Increase (Power Rays einsetzen, Vorschau Level " + erreicht + ")"));
            ruhe(ctx, 1200);
        } else {
            fotoSichern(ctx.frame(), "leveln-ziel-nicht-erreicht");
            Log.warn(WER, Sprache.t("Preview does not show level " + ziel + " (read " + erreicht + ") -- NO Increase.",
                    "Vorschau zeigt nicht Level " + ziel + " (gelesen " + erreicht + ") -- KEIN Increase."));
        }
        for (int i = 0; i < 3; i++) {
            tippe(ctx, RAYS_X, Sprache.t("Close Power Rays window", "Power-Rays-Fenster schliessen"));
            ruhe(ctx, 900);
            if (!enthaelt(lies(foto(ctx), K_RAYS_TITEL), "power")) break;
        }
        return ok ? Sprache.t("from " + von + " to " + erreicht, "von " + von + " auf " + erreicht) : null;
    }

    // ==========================================================================================================
    //  Level 100 (Autor 11.10. 03:05: "Alle 20 Level musst du eine andere Currency benutzen"; 03:15: "druck den Knopf"
    //  -- der gruene Auto-Knopf im Power-Rays-Fenster). Je Slot: Increase -> Auto -> Increase, bei voller Stufe
    //  Develop -> Stones of Creation (10/20/30/60) -> Increase -> Claim. Jeder Klick erst nach Pruefung am Bild; das
    //  gruene "+" (Steine kaufen) und "More Power Rays" werden nie gedrueckt. Fehlen Steine: aufhoeren.
    // ==========================================================================================================

    private static final int[][] SLOTS = {{300, 255}, {300, 395}, {300, 535}, {587, 255}, {587, 395}, {587, 535}};
    private static final int[] E_TITEL = {380, 80, 420, 70};          // "Titan's equipment:" im Einzelfenster
    private static final int[] E_LEVEL = {150, 480, 650, 110};        // "Level a/b" (Einzelfenster und Strahlen-Ansicht)
    private static final int[] E_KNOPF = {580, 571};                  // Increase / Develop im Einzelfenster
    private static final int[] AUTO_FUELLEN = {985, 308};             // gruener Knopf: Strahlen automatisch
    private static final int[] K_RAYS_MELDUNG = {600, 140, 420, 330}; // "upgraded to the level limit" / "max level"
    private static final int[] K_RANG = {220, 90, 720, 600};          // "Rank Up the Stigma" ... "550/30"
    private static final int[] RANG_JA = {579, 633}, RANG_CLAIM = {579, 626}, RANG_X = {944, 92};
    private static final int[] K_RANG_FERTIG = {180, 130, 800, 60};   // "The rank of the stigma has been increased!"
    private static final int[] SICHER_ZU = {640, 25};                 // leerer Rand oben: schliesst ein Fenster
    private int hSlot;
    private boolean hSteineAus;
    private final List<String> hErgebnis = new ArrayList<>();
    // 11.10. 03:45: Nur 100 Stones of Creation uebrig (1 -> 100 kostet 120 je Stigma). Darum reihum: Runde 0 nur
    // Strahlen bis zur Rang-Grenze (kostet keine Steine), dann je Runde hoechstens EIN Rang je Slot -- so werden die
    // billigen Raenge (10, 20) bei allen zuerst gekauft, statt alle Steine in ein Stigma zu stecken.
    private int hRunde, hRangErhoeht;
    private final java.util.Set<Integer> hBlockiert = new java.util.HashSet<>();
    private final Map<String, int[]> hStand = new LinkedHashMap<>();   // Name -> {von, jetzt}

    private Outcome lHundert(BotContext ctx, RunTracker runs) {
        if (hSlot >= SLOTS.length) {
            // Runde zu Ende: weiter, solange in der letzten Runde ein Rang gekauft wurde (Runde 0 = nur Strahlen).
            boolean weiter = hRunde == 0 || hRangErhoeht > 0;
            hRunde++;
            hSlot = 0;
            hRangErhoeht = 0;
            if (!weiter || hRunde > 5) hSlot = SLOTS.length + 1;
        }
        if (hSlot > SLOTS.length || BotEngine.stoppAngefordert()) {
            List<String> zeilen = new ArrayList<>();
            for (Map.Entry<String, int[]> e : hStand.entrySet()) {
                int[] v = e.getValue();
                zeilen.add(e.getKey() + " " + (v[0] == v[1] ? (v[1] >= 100 ? de.hustlebot.core.Sprache.t("already 100", "schon 100")
                        : de.hustlebot.core.Sprache.t("stays ", "bleibt ") + v[1]) : v[0] + " -> " + v[1]));
            }
            for (String e : hErgebnis) zeilen.add(grundText(e));
            // Freunde: zweisprachig (die Zeile kommt am Ende nach Discord).
            String t = "Level 100 (" + titan.anzeige() + "): " + (zeilen.isEmpty() ? de.hustlebot.core.Sprache.t("nothing done", "nichts gemacht")
                    : String.join("; ", zeilen))
                    + (hSteineAus ? de.hustlebot.core.Sprache.t(" -- not enough Stones of Creation for more.",
                            " -- Stones of Creation reichen nicht fuer mehr.") : ".");
            Log.info(WER, t);
            bericht = (bericht == null ? "" : bericht + " ") + t;
            kurzBericht = (kurzBericht == null ? "" : kurzBericht + " ") + t;
            schritt = Schritt.FERTIG;
            return Outcome.ACTED;
        }
        BufferedImage b = foto(ctx);
        if (raysSchliessen(ctx, b)) return Outcome.ACTED;
        if (stigmaFensterOffen(b) || enthaelt(lies(b, E_TITEL), "equipment")) {
            tippe(ctx, SICHER_ZU, Sprache.t("Close open window (at the top edge)", "Offenes Fenster schliessen (oben am Rand)"));
            ctx.sleep(1200);
            return Outcome.ACTED;
        }
        if (!titelPasst(lies(b, K_TITAN_NAME))) return lAbbruch(Sprache.t("no longer at " + titan.anzeige(), "nicht mehr beim " + titan.anzeige()));
        if (BotEngine.pauseAngefordert()) return Outcome.WAITING;   // Freunde 11.10.: nach "!resume" diesen Slot
        int nr = hSlot++;
        if (hBlockiert.contains(nr)) return Outcome.ACTED;
        runs.progress(Sprache.t("Stigmas level 100: " + titan.anzeige() + ", round " + hRunde + ", slot " + (nr + 1),
                "Stigmas Level 100: " + titan.anzeige() + ", Runde " + hRunde + ", Slot " + (nr + 1)));
        String fehler = hundertSlot(ctx, nr, hRunde == 0 ? 0 : 1);
        if (fehler != null) hErgebnis.add(fehler);
        BufferedImage n = foto(ctx);
        if (n != null && enthaelt(lies(n, E_TITEL), "equipment")) {
            tippe(ctx, SICHER_ZU, Sprache.t("Close stigma window (at the top edge)", "Stigma-Fenster schliessen (oben am Rand)"));
            ctx.sleep(1200);
        }
        return Outcome.ACTED;
    }

    /**
     * Slot nr mit Strahlen bis zur Rang-Grenze, dabei hoechstens maxRaenge Raenge kaufen. Haelt den Stand in hStand;
     * Fehlen Steine, wird der Slot fuer die naechsten Runden gesperrt. Liefert null oder einen Fehlertext.
     */
    private String hundertSlot(BotContext ctx, int nr, int maxRaenge) {
        int[] slot = SLOTS[nr];
        int raenge = 0;
        tippe(ctx, slot, Sprache.t("Open equipped stigma (level 100)", "Eingesetztes Stigma oeffnen (Level 100)"));
        ctx.sleep(1600);
        BufferedImage f = foto(ctx);
        String kopf = lies(f, E_TITEL);
        if (!enthaelt(kopf, "equipment")) {
            fotoSichern(f, "hundert-kein-fenster");
            return Sprache.t("Slot " + slot[0] + "," + slot[1] + ": no stigma window", "Slot " + slot[0] + "," + slot[1] + ": kein Stigma-Fenster");
        }
        String name = kopf.replaceAll("(?i).*stigma\\s*-\\s*", "").trim();
        if (name.isEmpty()) name = "Slot " + (nr + 1);
        int von = -1;
        // Freunde (Pruefung 11.10.): auch bei Pause raus -- bis zu 40 Runden mit Increase-, Strahlen- und Rang-Klicks
        // liefen sonst nach "!pause" weiter. Der Slot kommt in der naechsten Runde wieder dran.
        for (int runde = 0; runde < 40 && !BotEngine.stoppAngefordert() && !BotEngine.pauseAngefordert(); runde++) {
            Matcher m = LEVEL.matcher(lies(foto(ctx), E_LEVEL));
            if (!m.find()) {
                fotoSichern(foto(ctx), "hundert-level-unlesbar");
                return Sprache.t(name + ": level not readable -- stopped", name + ": Level nicht lesbar -- aufgehoert");
            }
            int a = Integer.parseInt(m.group(1)), bis = Integer.parseInt(m.group(2));
            if (von < 0) von = a;
            int[] stand = hStand.computeIfAbsent(name, k -> new int[]{-1, -1});
            if (stand[0] < 0) stand[0] = a;
            stand[1] = a;
            if (a >= 100) {
                hBlockiert.add(nr);
                return null;
            }
            if (a < bis) {
                if (!enthaelt(lies(foto(ctx), K_RAYS_TITEL), "power")) {
                    tippe(ctx, E_KNOPF, "Increase (Level " + a + "/" + bis + ")");
                    ctx.sleep(1500);
                }
                if (!enthaelt(lies(foto(ctx), K_RAYS_TITEL), "power")) {
                    fotoSichern(foto(ctx), "hundert-rays-fehlt");
                    return Sprache.t(name + ": power rays window did not open (level " + a + ")", name + ": Power-Rays-Fenster kam nicht (Level " + a + ")");
                }
                tippe(ctx, AUTO_FUELLEN, Sprache.t("Insert rays automatically", "Strahlen automatisch einlegen"));
                ctx.sleep(1000);
                String vorschau = lies(foto(ctx), new int[]{150, 490, 420, 70});
                tippe(ctx, RAYS_INCREASE, Sprache.t("Use rays (", "Strahlen einsetzen (") + vorschau + ")");
                ctx.sleep(2500);
                BufferedImage g = foto(ctx);
                if (enthaelt(lies(g, K_RAYS_TITEL), "power")) {
                    Matcher m2 = LEVEL.matcher(lies(g, E_LEVEL));
                    if (m2.find() && Integer.parseInt(m2.group(1)) < Integer.parseInt(m2.group(2))) continue;   // 600er-Grenze
                }
                String meldung = lies(g, K_RAYS_MELDUNG).toLowerCase(Locale.ROOT);
                if (meldung.contains("upgraded") || meldung.contains("max level") || enthaelt(lies(g, K_RAYS_TITEL), "power")) {
                    tippe(ctx, RAYS_X, Sprache.t("Close rays window", "Strahlen-Fenster schliessen"));
                    ctx.sleep(1500);
                }
                continue;
            }
            // Stufe voll, Level < 100: Rang erhoehen -- aber je Runde hoechstens maxRaenge.
            if (raenge >= maxRaenge) return null;
            tippe(ctx, E_KNOPF, Sprache.t("Develop (rank, level " + a + "/" + bis + ")", "Develop (Rang, Level " + a + "/" + bis + ")"));
            ctx.sleep(1500);
            String kosten = lies(foto(ctx), K_RANG);
            Matcher k = Pattern.compile("(\\d[\\d ]*)\\s*/\\s*(\\d+)").matcher(
                    kosten.contains("requirements") ? kosten.substring(kosten.indexOf("requirements")) : kosten);
            if (!enthaelt(kosten, "rank up") || !k.find()) {
                fotoSichern(foto(ctx), "hundert-rang-unklar");
                return Sprache.t(name + ": rank window unclear (level " + a + ")", name + ": Rang-Fenster unklar (Level " + a + ")");
            }
            int hat = Integer.parseInt(k.group(1).replace(" ", "")), braucht = Integer.parseInt(k.group(2));
            if (hat < braucht) {
                tippe(ctx, RANG_X, Sprache.t("Close rank window (not enough stones " + hat + "/" + braucht + ")",
                        "Rang-Fenster schliessen (zu wenig Steine " + hat + "/" + braucht + ")"));
                ctx.sleep(1200);
                hSteineAus = true;
                hBlockiert.add(nr);
                return null;
            }
            tippe(ctx, RANG_JA, Sprache.t("Rank up (Stones of Creation " + hat + " -> " + (hat - braucht) + ")",
                    "Rang erhoehen (Stones of Creation " + hat + " -> " + (hat - braucht) + ")"));
            ctx.sleep(3000);
            if (!enthaelt(lies(foto(ctx), K_RANG_FERTIG), "increased")) {
                fotoSichern(foto(ctx), "hundert-rang-ohne-meldung");
                return Sprache.t(name + ": no message after the rank click (level " + a + ")", name + ": nach Rang-Klick keine Meldung (Level " + a + ")");
            }
            tippe(ctx, RANG_CLAIM, "Claim");
            ctx.sleep(1500);
            raenge++;
            hRangErhoeht++;
        }
        if (BotEngine.pauseAngefordert() && !BotEngine.stoppAngefordert()) return null;
        return Sprache.t(name + ": stopped at level " + hStand.getOrDefault(name, new int[]{0, 0})[1],
                name + ": abgebrochen bei Level " + hStand.getOrDefault(name, new int[]{0, 0})[1]);
    }

    /** Foto eines guten Stigmas (mindestens 3x) fuer Autor: logs\stigmas\gute\<Zeit>-<Name>.png */
    private void fotoGut(BufferedImage b, String name, List<String> werte) {
        if (b == null) return;
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "stigmas", "gute");
            java.nio.file.Files.createDirectories(ordner);
            String datei = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd_HH-mm-ss"))
                    + "-bull-" + name + "-" + werte(werte).replaceAll("[^a-zA-Z0-9]+", "_") + ".png";
            javax.imageio.ImageIO.write(b, "png", ordner.resolve(datei).toFile());
        } catch (Exception e) {
            Log.debug(WER, "Foto nicht gesichert: " + e.getMessage());
        }
    }

    // ==========================================================================================================
    //  Bild-Hilfen
    // ==========================================================================================================

    /** Oberkanten der ganz sichtbaren Kachel-Reihen (orange Kante links in der ersten Spalte). */
    private static List<Integer> reihen(BufferedImage b, BotContext ctx) {
        List<Integer> r = new ArrayList<>();
        int x = px(ctx, REIHEN_X), oben = py(ctx, LISTE_OBEN), unten = py(ctx, LISTE_UNTEN);
        int start = -1, letzt = -1;
        for (int y = oben; y <= unten; y++) {
            boolean o = orange(b, x, y);
            if (o) {
                if (start < 0) start = y;
                letzt = y;
            }
            // Ende eines Laufs: mehr als 6 px ohne Orange (kleine Unterbrechungen durch Rahmen/Bild zaehlen nicht)
            if (start >= 0 && ((!o && y - letzt > 6) || y == unten)) {
                int lang = letzt - start;
                // ganz sichtbar: beginnt nicht am oberen Rand, und der Tipp-Punkt liegt noch in der Liste
                if (lang >= py(ctx, 30) && start > oben + 4 && start + py(ctx, KACHEL_MITTE) <= py(ctx, 655)) r.add(start);
                start = -1;
            }
        }
        // Zwei Laeufe in einer Kachel (Icon unterbricht die Kante) zu einer Reihe zusammenfassen.
        List<Integer> z = new ArrayList<>();
        for (int y : r) if (z.isEmpty() || y - z.get(z.size() - 1) > py(ctx, 90)) z.add(y);
        return z;
    }

    private static boolean orange(BufferedImage b, int x, int y) {
        if (x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) return false;
        int p = b.getRGB(x, y), r = p >> 16 & 255, g = p >> 8 & 255, bl = p & 255;
        return r > 200 && g > 90 && g < 190 && bl < 90;
    }

    /** Ein Stigma-Fenster ist offen: dunkles Feld in der Mitte (gemessen 36,25,41 gegen 107,95,117 ohne Fenster). */
    static boolean fensterOffen(BufferedImage b) {
        int p = b.getRGB(sx(b, 450), sy(b, 400));
        boolean dunkel = (p >> 16 & 255) < 70 && (p >> 8 & 255) < 60 && (p & 255) < 80;
        // #UFCX (Autor 19.10.): mit 297 Stigmas in der Zerlege-Liste ist dieser Punkt auch OHNE Fenster dunkel (Kachel
        // "New") -- der Bot drueckte 30 Mal "Schliessen" und brach ab. Ein offenes Fenster legt einen dunklen Schleier
        // ueber alles: die linke Tafel (sonst 241) ist dann um 90 (gemessen 22.10. nachts).
        return dunkel && hell(b, 150, 450) < 160 && hell(b, 420, 140) < 160;
    }

    private static int hell(BufferedImage b, int x, int y) {
        int p = b.getRGB(sx(b, x), sy(b, y));
        return ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3;
    }

    /** Der gruene "Add"-Knopf (gemessen 76,188,15). */
    private static boolean addSichtbar(BufferedImage b) {
        int p = b.getRGB(sx(b, 600), sy(b, 672));
        return (p >> 8 & 255) > 140 && (p >> 16 & 255) < 140 && (p & 255) < 90;
    }

    /** Der OK-Knopf der Warnung ist frei (gruen statt grau 108,108,108). */
    private static boolean okGruen(BufferedImage b) {
        int p = b.getRGB(sx(b, 470), sy(b, 535));
        return (p >> 8 & 255) > 150 && (p >> 16 & 255) < 140 && (p & 255) < 90;
    }

    /** 4. Reiter aktiv = dunkel (gemessen 63,48,71 bzw. 22,20,25), inaktiv beige (216,194,153). */
    private static boolean reiterStigmaAktiv(BufferedImage b) {
        int p = b.getRGB(sx(b, 1060), sy(b, 112));
        return (p >> 16 & 255) < 120;
    }

    /** Gelbe Sterne unter dem Bild im Stigma-Fenster (gemessen: gelb 255,233,15, grau 111,96,119). */
    private static int sterne(BufferedImage b, BotContext ctx) {
        int n = 0;
        for (int i = 0; i < 5; i++) {
            int p = b.getRGB(px(ctx, 401 + 24 * i), py(ctx, 238));
            int r = p >> 16 & 255, g = p >> 8 & 255, bl = p & 255;
            if (r > 200 && g > 150 && bl < 100) n++;
            else break;
        }
        return n;
    }

    /** Die Zahl im Knopf "Disassemble (N)", 0 ohne Zahl, -1 unlesbar. */
    private static int knopfZahl(BufferedImage b) {
        if (b == null) return -1;
        String t = lies(b, K_KNOPF);
        Matcher m = ZAHL_KLAMMER.matcher(t);
        if (m.find()) return zahl(m.group(1));
        if (!enthaelt(t, "disassemble")) return -1;
        // Autor 23.10.: Der Knopf zeigte "Disassemble (3)", gelesen wurde nur "Disassemble" -- die Texterkennung ueberliest
        // die kurze "(3)" auf Gruen. Am Ende nannte die Warnung 5 statt der gezaehlten 2, und der Bot brach ab (nichts
        // zerlegt). Schwarz-weiss (weisse Schrift schwarz, alles andere weiss) liest sie "(3)" -- am Bild getestet.
        m = ZAHL_KLAMMER.matcher(liesWeisseSchrift(b, K_KNOPF));
        return m.find() ? zahl(m.group(1)) : 0;
    }

    /** Wie lies(), aber nur die weisse Schrift (hell und grau, nicht gruen) wird schwarz auf weissem Grund gelesen. */
    private static String liesWeisseSchrift(BufferedImage b, int[] k) {
        int x = sx(b, k[0]), y = sy(b, k[1]);
        int w = Math.min(sx(b, k[2]), b.getWidth() - x), h = Math.min(sy(b, k[3]), b.getHeight() - y);
        if (w <= 0 || h <= 0) return "";
        BufferedImage gross = new BufferedImage(w * 3, h * 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(b.getSubimage(x, y, w, h), 0, 0, w * 3, h * 3, null);
        g.dispose();
        for (int j = 0; j < gross.getHeight(); j++) {
            for (int i = 0; i < gross.getWidth(); i++) {
                int p = gross.getRGB(i, j), r = p >> 16 & 255, gr = p >> 8 & 255, bl = p & 255;
                boolean weiss = (r + gr + bl) / 3 >= 200 && Math.abs(r - gr) < 40;
                gross.setRGB(i, j, weiss ? 0x000000 : 0xFFFFFF);
            }
        }
        return String.join(" ", Texterkennung.lies(gross)).trim();
    }

    private static int zahl(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Steht das Spiel schon auf einem Titan-Bildschirm (Tasche, Zerlegen, Titan)? Dann kein Heimweg vorab (15.10.). */
    public static boolean titanBildschirm(BotContext ctx) {
        BufferedImage b = foto(ctx);
        if (b == null) return false;
        String unten = lies(b, K_SQUAD_LEISTE) + " " + lies(b, K_SQUAD);
        return enthaelt(unten, "squad") || enthaelt(unten, "patrons") || enthaelt(lies(b, K_ZERLEGEN), "disassemb")
                || enthaelt(lies(b, K_INVENTAR), "inventory") || enthaelt(lies(b, K_SWITCH_TITANS), "switch");
    }

    private static boolean enthaelt(String t, String wort) {
        return t != null && t.toLowerCase(Locale.ROOT).contains(wort);
    }

    /**
     * Steht oben der gesuchte Titan? Freunde (11.10.): ohne Leerzeichen und Satzzeichen verglichen, damit auch ein frei
     * genannter Name aus mehreren Woertern passt ("Frost Giant" -> "frostgiant"). Bei einem frei genannten Titan ab
     * 6 Buchstaben darf die Texterkennung einen Buchstaben falsch lesen.
     * Pruefung 11.10.: ein frei genannter Name muss GANZE Woerter des Titels treffen -- "star" passte sonst auf "Keeper
     * of Stars" und der Bot tauschte dort Stigmas. Verglichen werden aufeinanderfolgende gelesene Woerter ohne
     * Leerzeichen ("Frost Giant", "FrostGiant" und "Fro st Giant" passen alle auf "frost giant").
     */
    private boolean titelPasst(String gelesen) {
        String g = de.hustlebot.core.Stigmaplan.titelWort(gelesen), w = de.hustlebot.core.Stigmaplan.titelWort(titan.titelWort());
        if (w.isEmpty() || g.isEmpty()) return false;
        // Code-Review 23.10.: "guardian" (Last Guardian) steckt auch in "Bridge Guardian".
        if (!de.hustlebot.core.Stigmaplan.frei(titan)) return g.contains(w) && !(g.contains("bridge") && !w.contains("bridge"));
        String[] teile = gelesen.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (int i = 0; i < teile.length; i++) {
            if (teile[i].isEmpty()) continue;
            StringBuilder lauf = new StringBuilder();
            for (int j = i; j < teile.length && lauf.length() <= w.length() + 1; j++) {
                lauf.append(teile[j]);
                String l = lauf.toString();
                if (l.equals(w)) return true;
                if (w.length() >= 6 && Math.abs(l.length() - w.length()) <= 1 && abstand(l, w) <= 1) return true;
            }
        }
        return false;
    }

    /** Text in einem Kasten lesen (Fensterkoordinaten 1159 x 770, umgerechnet). */
    /**
     * Autor, 10.10. 21:17: "Kannst du den Ablauf noch schneller machen? Ich habe vielleicht 2000 Stigmas." Das Lesen
     * kostet am meisten -- und die Titel wiederholen sich (72x "Crocodile Stigma - Mind"). Ein Kasten, der Pixel fuer
     * Pixel so aussieht wie ein schon gelesener, bekommt dessen Text, ohne neu zu lesen. Gemerkt wird nur Text, der
     * zum erwarteten Muster passt. "So aussieht": hoechstens 0,5 % der Pixel weichen um mehr als 24 Graustufen ab --
     * zwei verschiedene Namen ("Bull" / "Crocodile") liegen weit darueber.
     */
    private final List<Object[]> titelGedaechtnis = new ArrayList<>();
    private final List<Object[]> levelGedaechtnis = new ArrayList<>();
    private int gemerktTreffer, gemerktGelesen;

    private String liesGemerkt(BufferedImage b, int[] k, List<Object[]> gedaechtnis, Pattern muster) {
        int[] grau = grauwerte(b, k);
        if (grau != null) {
            for (Object[] e : gedaechtnis) {
                if (gleich((int[]) e[0], grau)) {
                    gemerktTreffer++;
                    return (String) e[1];
                }
            }
        }
        String t = lies(b, k);
        gemerktGelesen++;
        if (grau != null && muster.matcher(t).find() && gedaechtnis.size() < 300) gedaechtnis.add(new Object[]{grau, t});
        return t;
    }

    private static int[] grauwerte(BufferedImage b, int[] k) {
        int x = sx(b, k[0]), y = sy(b, k[1]);
        int w = Math.min(sx(b, k[2]), b.getWidth() - x), h = Math.min(sy(b, k[3]), b.getHeight() - y);
        if (w <= 0 || h <= 0) return null;
        int[] px = b.getRGB(x, y, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = ((p >> 16 & 255) * 3 + (p >> 8 & 255) * 6 + (p & 255)) / 10;
        }
        return px;
    }

    private static boolean gleich(int[] a, int[] b) {
        if (a.length != b.length) return false;
        int erlaubt = a.length / 200, abweichend = 0;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 24 && ++abweichend > erlaubt) return false;
        }
        return true;
    }

    private static String lies(BufferedImage b, int[] k) {
        if (b == null) return "";
        int x = sx(b, k[0]), y = sy(b, k[1]);
        int w = Math.min(sx(b, k[2]), b.getWidth() - x), h = Math.min(sy(b, k[3]), b.getHeight() - y);
        if (w <= 0 || h <= 0) return "";
        BufferedImage aus = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(b.getSubimage(x, y, w, h), 0, 0, w * 2, h * 2, null);
        g.dispose();
        return String.join(" ", Texterkennung.lies(aus)).trim();
    }

    private static BufferedImage foto(BotContext ctx) {
        ctx.refresh();
        return ctx.frame();
    }

    private static boolean sichtbar(Mat screen, BotContext ctx, String vorlage) {
        return screen != null && ctx.visibleIfKnown(screen, vorlage);
    }

    /**
     * Autor, 10.10. 21:09 (Bild, alles ausserhalb des Zerlegen-Fensters weiss uebermalt): "Der Bot soll auf keinen Fall
     * hier aussen hinklicken, waehrend er Stigmas loescht -- dann wird der Loeschvorgang abgebrochen und er muesste alle
     * noch einmal auswaehlen." Ab "Disassembling items" ist jeder Klick ausserhalb des Fensters gesperrt.
     */
    private static volatile boolean nurImFenster;
    private static final int[] ZERLEGEN_FENSTER = {58, 100, 1145, 695};   // links, oben, rechts, unten

    private static void tippe(BotContext ctx, int[] p, String grund) {
        tippeXY(ctx, px(ctx, p[0]), py(ctx, p[1]), grund);
    }

    /**
     * Klick an Bildschirm-Koordinaten (schon umgerechnet). Freunde, Pruefung 11.10.: ALLE Klicks des Stigma-Modus laufen
     * hier durch (vorher klickten das Last-Guardian-Gesicht und die Kacheln direkt, an Stopp und Fenster-Grenze vorbei).
     */
    private static void tippeXY(BotContext ctx, int x, int y, String grund) {
        // 11.10. 03:14: Nach "Stopp" klickte der Bot seinen Schritt noch 5 s zu Ende -- und setzte dabei ein Hedgehog-
        // Stigma ein. Ist Stopp angefordert, wird im Stigma-Modus gar nicht mehr geklickt.
        if (gesperrt(grund)) return;
        if (nurImFenster && (x < px(ctx, ZERLEGEN_FENSTER[0]) || y < py(ctx, ZERLEGEN_FENSTER[1])
                || x > px(ctx, ZERLEGEN_FENSTER[2]) || y > py(ctx, ZERLEGEN_FENSTER[3]))) {
            Log.error(WER, Sprache.t("Click at " + x + "," + y + " is outside the disassembling window -- NOT clicked",
                    "Klick bei " + x + "," + y + " liegt ausserhalb des Zerlegen-Fensters -- NICHT geklickt")
                    + (grund != null ? " (" + grund + ")" : "") + ".");
            return;
        }
        if (grund != null) Log.action("Klick", WER + ": " + grund + "  [" + x + "," + y + "]");
        ctx.input().tippeSofort(x, y);
    }

    /** Wischen/Ziehen -- mit derselben Stopp- und Pausen-Sperre wie die Klicks (Freunde, Pruefung 11.10.). */
    private static void ziehe(BotContext ctx, boolean sofort, int x1, int y1, int x2, int y2) {
        if (gesperrt(Sprache.t("Swipe", "Wischen"))) return;
        if (sofort) ctx.input().ziehenSofort(x1, y1, x2, y2);
        else ctx.input().drag(x1, y1, x2, y2);
    }

    /**
     * Stopp oder Pause angefordert? Dann wird nicht mehr geklickt. Freunde, Pruefung 11.10.: "!pause, um selbst zu
     * spielen" -- der Motor prueft die Pause nur zwischen zwei Schritten, ein Level-100-Schritt klickte sonst weiter.
     * Nach "!resume" liest der naechste Schritt das Bild neu.
     */
    private static boolean gesperrt(String grund) {
        boolean stopp = BotEngine.stoppAngefordert();
        if (!stopp && !BotEngine.pauseAngefordert()) return false;
        Log.info(WER, Sprache.t((stopp ? "Stop" : "Pause") + " requested -- no more clicks", (stopp ? "Stopp" : "Pause") + " angefordert -- nicht mehr geklickt")
                + (grund != null ? " (" + grund + ")" : "") + ".");
        return true;
    }

    /**
     * Ein Klick, der der Liste oder den Filtern gilt (Reiter, Klassen-Knopf, Trichter, Namens-Haken). 11.10. 03:14: Das
     * Klassen-Menue ging nicht schnell genug auf, ein Klick oeffnete eine Kachel -- und der naechste "Filter oeffnen"
     * (838,686) traf genau "Equip". Darum: vorher nachsehen, und ist ein Stigma- oder Strahlen-Fenster offen, NICHT
     * klicken (Equip, Break apart und Increase liegen unter den Filter-Knoepfen).
     */
    private boolean listenTipp(BotContext ctx, int[] p, String grund) {
        BufferedImage f = foto(ctx);
        if (f == null || stigmaFensterOffen(f) || enthaelt(lies(f, K_RAYS_TITEL), "power")) {
            fotoSichern(f, "listen-klick-verweigert");
            Log.warn(WER, Sprache.t("A window is open -- \"" + grund + "\" NOT clicked (it would hit Equip/Break apart).",
                    "Ein Fenster ist offen -- \"" + grund + "\" NICHT geklickt (es traefe Equip/Break apart)."));
            return false;
        }
        tippe(ctx, p, grund);
        return true;
    }

    private static int px(BotContext ctx, int x) {
        int w = ctx.width();
        return w > 0 ? (int) Math.round(x * (double) w / BREITE) : x;
    }

    private static int py(BotContext ctx, int y) {
        int h = ctx.height();
        return h > 0 ? (int) Math.round(y * (double) h / HOEHE) : y;
    }

    private static int sx(BufferedImage b, int x) {
        return (int) Math.round(x * (double) b.getWidth() / BREITE);
    }

    private static int sy(BufferedImage b, int y) {
        return (int) Math.round(y * (double) b.getHeight() / HOEHE);
    }

    // Autor 23.10.: "Bei jedem Stigma, das der Bot noch nicht kennt, soll er immer eine Vorlage, einen Screenshot nehmen
    // und sich merken. Das brauche ich dann spaeter." -- "einfach beim Loeschen aufnehmen". Je Sorte (Set + Name, z. B.
    // dragon-mind) einmal: das ganze Fenster und der Kopf der Kachel (50 x 48, wie die Kopf-Vorlagen) aus der Liste.
    private static final java.nio.file.Path SORTEN_ORDNER = KOPF_ORDNER.resolve("sorten");

    private static synchronized void sorteMerken(String set, String name, BufferedImage fenster, BufferedImage liste, int[] p) {
        if (set == null || name == null || fenster == null) return;
        String s = set.toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
        if (!s.matches("[a-z]{3,12}") || !NAMEN.contains(n)) return;
        try {
            java.nio.file.Files.createDirectories(SORTEN_ORDNER);
            java.nio.file.Path bild = SORTEN_ORDNER.resolve(s + "-" + n + "-fenster.png");
            boolean neu = !java.nio.file.Files.exists(bild);
            if (neu) javax.imageio.ImageIO.write(fenster, "png", bild.toFile());
            java.nio.file.Path kopfDatei = SORTEN_ORDNER.resolve(s + "-" + n + "-kopf.png");
            if (liste != null && p != null && !java.nio.file.Files.exists(kopfDatei)) {
                int x = p[0] - 25, y = p[2] + 5;
                if (x >= 0 && y >= 0 && x + 50 <= liste.getWidth() && y + 48 <= liste.getHeight()) {
                    BufferedImage kopf = new BufferedImage(50, 48, BufferedImage.TYPE_INT_RGB);
                    kopf.getGraphics().drawImage(liste.getSubimage(x, y, 50, 48), 0, 0, null);
                    javax.imageio.ImageIO.write(kopf, "png", kopfDatei.toFile());
                }
            }
            if (neu) Log.info(WER, Sprache.t("New stigma kind saved: " + set + " " + name + " (templates\\gelernt\\stigma\\sorten).",
                    "Neue Stigma-Sorte gemerkt: " + set + " " + name + " (templates\\gelernt\\stigma\\sorten)."));
        } catch (Exception e) {
            Log.warn(WER, Sprache.t("Stigma kind not saved: ", "Stigma-Sorte nicht gespeichert: ") + e.getMessage());
        }
    }
}
