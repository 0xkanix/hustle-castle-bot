package de.hustlebot.bot;

import de.hustlebot.core.Kriegsplan;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clan-Krieg. Der Bot stellt zu den Kriegszeiten die Truppen auf.
 *
 * ------------------------------------------------------------------
 *  Was zu tun ist -- und vor allem, wie WENIG
 * ------------------------------------------------------------------
 *
 *  Autor: "ich kann genau einmal mein team in einen titanen turm setzen
 *  und einmal mein team in einen normalen turm."
 *
 *  Das ist der ganze Auftrag. Zwei Trupps, dann ist Feierabend:
 *
 *      ein Trupp in einen NORMALEN Turm
 *      ein Trupp in einen TITANEN-Aussenposten
 *
 *  Pro Turm geht nur ein Trupp, und mehr als diese zwei Plaetze hat er
 *  auf dem ganzen Spielfeld nicht. Welchen er zuerst setzt, ist egal.
 *
 *  Fertig ist der Bot, wenn ueber einem normalen Turm UND ueber einem
 *  Aussenposten das blaue Schild "Your squad" steht. Nicht vorher, und
 *  vor allem nicht, weil er meint, geklickt zu haben -- gesehen zaehlt,
 *  nicht geklickt.
 *
 * ------------------------------------------------------------------
 *  Der Weg
 * ------------------------------------------------------------------
 *
 *  Burg -> das Kriegssymbol unten links (nur waehrend der Kriegszeiten
 *  ueberhaupt da) -> Kriegskarte -> Turm anklicken -> "Deploy squad"
 *  -> im Truppenfenster "Deploy the best" (normaler Turm) oder
 *  "Previous Squad" (Aussenposten) -> der gruene Knopf unten
 *  -> zurueck auf die Karte, naechster Turm.
 *
 *  Die Tuerme selbst werden NICHT als Bild gesucht -- davon gibt es zu
 *  viele Motive. Gefunden werden sie ueber die Belegung darunter, den
 *  Schraegstrich aus "0/3": wo ein Schraegstrich ist, ist ein Ziel. Ob
 *  es ein Aussenposten ist, verraten die drei Aussenposten-Motive.
 *
 * ------------------------------------------------------------------
 *  Was NIE passieren darf
 * ------------------------------------------------------------------
 *
 *  KEINE AEPFEL MIT DIAMANTEN KAUFEN. Reicht das Essen fuer den
 *  normalen Turm nicht, wird das Fenster "Buy the missing resources?"
 *  mit dem roten "No" geschlossen und der normale Turm bleibt eben
 *  leer. Der Titanen-Trupp kostet beim Setzen nichts und wird trotzdem
 *  gesetzt. Autor dazu ausdruecklich: "wenn die aepfel nicht reichen
 *  soll er bitte keine aepfel mit diamenten kaufen und dann einfach nur
 *  den titanen trupp setzen."
 *
 *  Ein Turm mit rotem Kasten ("Your Throne Room must ...") ist fuer
 *  Autor gesperrt -- da kann er gar nichts einsetzen. Solche Tuerme
 *  werden gemerkt und nicht wieder angefasst.
 *
 * ------------------------------------------------------------------
 *  NOCH UNGEPRUEFT -- am 21-Uhr-Krieg nachzusehen
 * ------------------------------------------------------------------
 *
 *  Ich habe die Kriegskarte nie gesehen. Die Vorlagennamen sind nach
 *  Autors Beschreibung entstanden, nicht nach einem Bild. Zwei Stellen
 *  sind deshalb geraten und stehen bewusst als benannte Konstanten da,
 *  damit sie sich in einer Zeile richtigstellen lassen:
 *
 *    TURM_UEBER_ZAEHLER -- wie weit ueber der Belegung der Turm selbst
 *                          liegt, den man anklicken muss
 *    NAH_AM_TURM        -- wie nah ein Aussenposten-Motiv an der
 *                          Belegung sein muss, damit beides zusammen
 *                          gehoert
 *
 *  Beim Verlies hat mich einmal genau so ein geratener Wert Stunden
 *  gekostet. Deshalb schreibt der Bot bei jedem Durchgang ins
 *  Protokoll, was er auf der Karte gefunden hat. Steht dort Unsinn,
 *  sieht man es sofort und nicht erst nach zweihundert Klicks.
 */
public final class ClanWarMode implements GameMode {

    // ---- Weg zur Karte ------------------------------------------------
    private static final String T_ICON        = "cw_icon";
    /**
     * Das Symbol, WAEHREND der Krieg laeuft (rotes Schild mit Flammen, Autor
     * 28.09.). Dann ist die Vorbereitung vorbei, aufstellen geht nicht.
     * Freiwillig -- ohne die Vorlage wartet der Bot wie bisher ~30 s auf das
     * normale Symbol und macht dann weiter.
     */
    private static final String T_ICON_LAEUFT = "cw_symbol_krieg_laeuft";
    private static final String T_PREP        = "cw_prep_stage";
    private static final String T_PROGRESS    = "cw_war_progress";
    private static final String T_ENDED       = "cw_war_ended";
    private static final String T_BACK        = "cw_back";
    /** Der Weg zurueck in die Burg, falls das Spiel woanders steht. */
    private static final String T_HOME        = "home_button";

    // ---- Tuerme auf der Karte -----------------------------------------
    private static final String T_SLASH       = "cw_slash";
    private static final String T_YOUR_SQUAD  = "cw_your_squad";

    /**
     * Ab welcher Uebereinstimmung ein "Your squad" zaehlt.
     *
     * DEUTLICH niedriger als die uebliche Schwelle von 0.84, und zwar
     * gemessen, nicht geraten.
     *
     * Das obere Schild auf Autors Karte liegt auf dem dunklen Band
     * "Preparatory stage" und wird vom Spiel abgedunkelt gezeichnet. Der
     * Bot hat am 5. September nachgemessen:
     *
     *     helles Schild ueber dem Turm      0.84
     *     blasses Schild auf dem Band       0.69   <- wurde verfehlt
     *     bester Fehltreffer sonstwo        0.46
     *
     * Zwischen dem echten blassen Schild und dem naechstbesten Zufall
     * liegen also 23 Hundertstel. 0.60 sitzt bequem dazwischen: weit genug
     * unter 0.69, um das blasse Schild sicher zu fangen, und weit genug
     * ueber 0.46, um nichts zu erfinden.
     *
     * Warum das wichtig ist: Zwei Schilder heissen "fertig". Ein Trugbild
     * wuerde den zweiten Turm kosten -- deshalb steht jeder Treffer mit
     * seiner Guete im Protokoll, und was knapp verfehlt, ebenfalls.
     */
    private static final double SCHILD_SCHWELLE = 0.60;

    /**
     * Ab welcher Uebereinstimmung ein Aussenposten-Motiv zaehlt.
     *
     * Tiefer als die Schilder-Schwelle, weil ein Turm auf der Karte von
     * Fackeln, Baeumen und Schildern teilweise verdeckt sein kann. Ein
     * falsch erkannter Aussenposten ist ausserdem viel harmloser als ein
     * falsches "Your squad": Der Bot macht den Turm auf, findet keinen
     * brauchbaren Knopf und geht weiter.
     */
    private static final double POSTEN_SCHWELLE = 0.72;


    /** Damit die Motiv-Zeile nicht bei jedem Blick neu im Protokoll steht. */
    private String letzterPostenBericht = "";
    /** Alle Aussenposten-Motive fangen so an: _orange, _blue, _green, _2, _3 ... */
    private static final String T_OUTPOST_PREFIX = "cw_outpost";

    // ---- Im Turm -------------------------------------------------------
    private static final String T_DEPLOY_SQUAD = "cw_deploy_squad";
    private static final String T_DEPLOY_GREY  = "cw_deploy_grey";
    /** "Number of squads:" -- daran erkennt der Bot, dass ein Turm offen ist. */
    private static final String T_SQUADS_LABEL = "cw_squads_label";
    private static final String T_CLOSE_X      = "cw_close_x";
    private static final String T_ERROR_RED    = "cw_error_red";

    // ---- Truppenfenster -------------------------------------------------
    private static final String T_CREATE_SQUAD   = "cw_create_squad";
    private static final String T_DEPLOY_BEST    = "cw_deploy_best";
    private static final String T_PREVIOUS_SQUAD = "cw_previous_squad";
    /** Der gruene Knopf unten im NORMALEN Turm: "Deploy" mit dem Apfelpreis. */
    private static final String T_DEPLOY_COST    = "cw_deploy_cost";

    /**
     * Der gruene Knopf unten im AUSSENPOSTEN: "Confirm".
     *
     * DEN GIBT ES DOCH. Hier stand einmal das Gegenteil: "cw_confirm gibt
     * es nicht" -- so hatte Autor es mir gesagt, und ich hatte den Eintrag
     * daraufhin gestrichen. Beide haben wir dabei nur den normalen Turm vor
     * Augen gehabt, wo der Knopf "Deploy" heisst und den Apfelpreis
     * darunter hat.
     *
     * Sein Bild vom 5. September aus dem Titanen-Aussenposten zeigt es
     * anders: Dort steht unten ein gruenes "Confirm", ohne Preis -- der
     * Titanen-Trupp kostet ja nichts. Zwei Fenster, zwei Knoepfe.
     */
    private static final String T_CONFIRM        = "cw_confirm";

    /**
     * Alle Knoepfe, mit denen sich eine Aufstellung bestaetigen laesst --
     * in der Reihenfolge, in der sie probiert werden.
     *
     * Zuerst die beiden eigenen: "Deploy" mit Apfelpreis (normaler Turm)
     * und "Confirm" (Aussenposten). Danach dieselben gruenen Knoepfe aus
     * Verlies und Arena -- es ist im ganzen Spiel derselbe Knopf, und
     * aufgenommen sind sie laengst.
     */
    private static final List<String> BESTAETIGEN =
            List.of(T_DEPLOY_COST, "cw_confirm", "confirm", "ar_confirm");

    // ---- Zwischenfenster -------------------------------------------------
    // cw_apple_red wird NICHT mehr benutzt -- siehe truppeStellen(). Die
    // Vorlage ist nur ein roter Apfel, und der Apfel im Deploy-Knopf ist
    // immer rot. Sie hat damit jeden normalen Turm ausgelassen.
    private static final String T_BUY       = "cw_buy_resources";
    private static final String T_BUY_NO    = "cw_buy_no";
    private static final String T_WARNING   = "cw_warning";
    private static final String T_YES       = "cw_yes";

    /** Der Text ueber dem Yes-Knopf (bis 150 px darueber, halbe Fensterbreite), klein geschrieben. */
    private static String frageText(BotContext ctx, Matcher.Hit ja) {
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return "";
        double f = ctx.width() / 1159.0;
        int x = Math.max(0, ja.centerX() - (int) (240 * f)), w = Math.min(bild.getWidth() - x, (int) (480 * f));
        int y = Math.max(0, ja.y() - (int) (150 * f)), h = Math.min(bild.getHeight() - y, (int) (145 * f));
        if (w < 50 || h < 30) return "";
        return ArenaLeser.liesText(bild, x, y, w, h).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /**
     * Code-Pruefung 15.10. (Hauptbot): Nur die Frage "Trupp unvollstaendig" -- nichts vom Kaufen, von Diamanten
     * oder fehlenden Ressourcen. Sonst kaufte ein Yes Aepfel mit Diamanten, wenn cw_buy_resources gerade nicht passte.
     */
    static boolean truppFrage(String t) {
        if (t == null || t.isBlank()) return false;
        boolean kauf = t.contains("buy") || t.contains("purchase") || t.contains("diamond") || t.contains("gem")
                || t.contains("resource") || t.contains("missing") || t.contains("kauf");
        boolean trupp = t.contains("incomplete") || t.contains("reserve") || t.contains("add unit") || t.contains("not full")
                || t.contains("empty slot") || t.contains("unvollst");
        return trupp && !kauf;
    }

    /**
     * Der Satz "Are you sure you want to set out with an incomplete squad?".
     *
     * Damit laesst sich die harmlose Nachfrage vom Apfelkauf unterscheiden,
     * ohne auf "kein Kauffenster im Bild" schliessen zu muessen. Fehlt die
     * Vorlage, geht es auch ohne -- dann zaehlt eben der Umkehrschluss.
     */
    private static final String T_INCOMPLETE = "cw_incomplete";

    /**
     * Namensanfang der Sperrliste: cw_skip_1, cw_skip_2, cw_skip_...
     *
     * Jede Vorlage, die so heisst, ist der NAME eines Turms, in den Autor
     * nicht setzen will. Am 5. September waren das die beiden Tempel
     * "Temple of the Unstoppable Elements" und "Temple of the Rampaging
     * Elements" -- die verlangen Feuer-Titanen.
     *
     * Als Vorsilbe und nicht als feste Liste, damit weitere Tuerme ohne
     * Codeaenderung dazukommen koennen. Aufnehmen, neu starten, fertig.
     */
    private static final String T_SKIP_PREFIX = "cw_skip";

    /**
     * Wie weit ueber der Belegung "0/3" der Turm liegt, gemessen in
     * Bruchteilen der Fensterhoehe. GERATEN -- siehe Kopf der Datei.
     */
    private static final double TURM_UEBER_ZAEHLER = 0.06;

    /**
     * Wie nah ein Aussenposten-Motiv an einer Belegung liegen muss, damit
     * beides zum selben Turm gehoert. Ebenfalls in Bruchteilen der
     * Fensterhoehe, und ebenfalls geraten.
     */
    private static final double NAH_AM_TURM = 0.12;

    /** Nach so vielen unbekannten Bildern hintereinander wird abgebrochen. */
    private static final int MAX_UNBEKANNT = 25;

    /**
     * So lange wird auf einen freien Turm gewartet.
     *
     * Lange genug, dass ein Mitspieler seine Truppe noch umstellen kann, kurz
     * genug, dass der Bot nicht bis zum Totmann-Schalter dasteht.
     * Pruefer 29.10.: Frueher waren es 240 BILDER -- am PC rund 4 Minuten, auf dem langsamen Server (bis 5x) ein
     * Vielfaches. Jetzt eine feste Zeit, egal wie schnell der Rechner ist.
     */
    static final long OHNE_ZIEL_GEDULD_MS = 180_000;
    /** Seit wann ohne freien Turm gewartet wird (gilt, solange ohneZiel > 0). */
    private long ohneZielSeit;

    /** Ist die Geduld fuer einen freien Turm um? (rein rechnerisch, testbar) */
    static boolean ohneZielGeduldUm(long seitMs) {
        return seitMs >= OHNE_ZIEL_GEDULD_MS;
    }

    /**
     * Ist jeder Turm der noch offenen Sorte(n) sicher als voll gelesen ("2/2", beide Ziffern)? Ungelesene Tuerme der
     * ANDEREN Sorte zaehlen nicht mit (Pruefer 29.10.). Fehlt ein Turm dieser Sorte ganz, ist das kein "voll".
     */
    static boolean alleGesuchtenVoll(List<Turm> tuerme, List<Sorte> offeneSorten) {
        return !offeneSorten.isEmpty() && offeneSorten.stream().allMatch(s -> {
            List<Turm> dieser = tuerme.stream()
                    .filter(tt -> (tt.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) == s)
                    .toList();
            return !dieser.isEmpty() && dieser.stream().allMatch(Turm::voll);   // voll() verlangt beide Ziffern gelesen
        });
    }
    /** 23.10. (Bericht #H6LS): kartenschritt wartet gerade gewollt auf einen freien Turm. */
    private boolean wartetAufTurm;

    /** Ein Turm auf der Karte, gefunden ueber seine Belegung. */
    /**
     * @param leer true, wenn links vom Schraegstrich eine Null steht --
     *             also noch kein Mitspieler im Turm ist
     */
    record Turm(int x, int y, boolean aussenposten, boolean leer, int drin, int platz) {
        String schluessel() { return (aussenposten ? "A" : "N") + (x / 20) + ":" + (y / 20); }

        /**
         * Voll, wenn links vom Strich so viel steht wie rechts ("2/2", "4/4").
         * Autor, 27.09.: "daran erkennt er auch, ob ein Turm voll ist." Nur
         * wenn BEIDE Ziffern gelesen wurden -- im Zweifel gilt er als offen.
         */
        boolean voll() { return drin >= 0 && platz > 0 && drin >= platz; }
    }

    /** Welche Sorte Turm gerade gesucht wird. */
    enum Sorte { NORMAL, AUSSENPOSTEN }

    // --- Erkundung (Autor 17.10.) -------------------------------------------------------------------------------
    // "Wenn du den Clan-Krieg setzt, schau dir bitte jeden Stuetzpunkt und Outpost durch, dort stehen jetzt seit
    // neustem immer Namen ('Message from the Clan Administration: Autor ; Robin'). Bis er den Namen findet von dem
    // Spieler, der den Bot gerade benutzt -- dort soll der Bot das Team setzen, bei den Outposts das Titanen-Team.
    // Wenn er den Spielernamen nirgends finden kann, einfach einen Stuetzpunkt, wo noch kein Spieler drin ist."
    // Dazu NUR bei Autor (Schalter titan-ruhm.txt): steht sein Name an keinem Outpost, kommt das Titanen-Team in den
    // Outpost mit dem meisten "Glory for winning" -- nie in einen "Temple of ... Elements", ausser es ist der einzige.

    /** Was im Turmfenster steht: Titel, die Namen der Clan-Verwaltung, "Glory for winning". */
    record TurmInfo(String titel, String namen, int ruhm, boolean meinTrupp, boolean verschieben) {
        boolean tempel() { return titel.toLowerCase(java.util.Locale.ROOT).contains("temple"); }
    }

    /** Schon angesehene Tuerme dieses Krieges (Schluessel wie Turm.schluessel()). */
    private final Map<String, TurmInfo> erkundet = new LinkedHashMap<>();
    /** Der Turm, der gerade nur zum Lesen geoeffnet wurde (null = keiner). */
    private Turm erkundenTurm;
    /** Das Lese-Fenster einmal gesehen (erst beim zweiten Bild lesen -- das Fenster faehrt noch aus). */
    private boolean erkundenGesehen;
    private int erkundenVersuche;
    /** Ziel wurde ueber den Spielernamen gewaehlt: dann gilt die Sperrliste (Temple) fuer diesen Turm nicht. */
    private boolean zielNachName;
    /** Namens-Tuerme, in denen schon einmal ein roter Hinweis stand (zweiter Blick, Server 29.10.). */
    private final java.util.Set<String> rotImNamensturm = new java.util.HashSet<>();
    /** Hoechstens so viele Tuerme je Krieg zum Lesen oeffnen. */
    private static final int MAX_ERKUNDUNG = 30;
    private int erkundungsKlicks;
    private static final java.nio.file.Path RUHM_SCHALTER = java.nio.file.Path.of("titan-ruhm.txt");

    /** Autors Regel fuers Titanen-Team (nur bei ihm an, Datei titan-ruhm.txt mit "an"). */
    static boolean ruhmRegelAn() {
        try {
            return java.nio.file.Files.exists(RUHM_SCHALTER)
                    && de.hustlebot.core.Dateien.text(RUHM_SCHALTER).trim().toLowerCase(java.util.Locale.ROOT).startsWith("an");
        } catch (Exception e) {
            return false;
        }
    }

    /** Steht der Spieler unter den Namen der Clan-Verwaltung ("Autor ; Robin", "Carl ; Ben ; Bruno")? */
    static boolean enthaeltNamen(String namen, String spieler) {
        if (namen == null || spieler == null || spieler.isBlank()) return false;
        String ich = de.hustlebot.core.NamensVergleich.form(spieler);
        if (ich.length() < 2) return false;
        for (String teil : namen.split("[;,|/:\\n]+")) {
            String t = teil.strip();
            if (t.isEmpty()) continue;
            if (de.hustlebot.core.NamensVergleich.form(t).equals(ich)) return true;
            if (de.hustlebot.core.NamensVergleich.gleicherName(t, spieler)) return true;
        }
        return false;
    }

    /** "Glory for winning: 5 300" (auch "Glory for winning:" und darunter "3 000") -> 5300, sonst -1. */
    static int ruhmAus(String text) {
        // Live-Test 17.10.: gelesen kam "5 300 Glory for winning:" -- die Zahl VOR dem Wort. Daher: die groesste Zahl ab
        // 1000 im Ausschnitt; Boni ("+35%", "Outpost bonus: +770") sind kleiner oder Prozent.
        if (text == null || !text.toLowerCase(java.util.Locale.ROOT).contains("glory")) return -1;
        return ruhmZahl(text);
    }

    /**
     * Die groesste Zahl ab 1000 im Ruhm-Ausschnitt, ohne das Wort "Glory" (der Ausschnitt sitzt schon auf der Ruhm-Zeile).
     * Die Server-Erkennung (en-US) liest Nullen in einer Zahlgruppe als "o": "8 ooo" -> 8000.
     */
    static int ruhmZahl(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher o = java.util.regex.Pattern.compile("\\d{1,3}(?:[ .,][0-9oO]{3})+").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (o.find()) o.appendReplacement(sb, o.group().replaceAll("[oO]", "0"));
        o.appendTail(sb);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3}(?:[ .,]\\d{3})+|\\d{4,6})(?!\\s*%)").matcher(sb);
        int best = -1;
        while (m.find()) {
            try {
                int z = Integer.parseInt(m.group(1).replaceAll("[^0-9]", ""));
                if (z >= 1000 && z > best) best = z;
            } catch (NumberFormatException ex) {
                // weiter
            }
        }
        return best;
    }

    /** Liest Titel, Namen und Ruhm aus dem offenen Turmfenster (Stellen aus Autors Bildern, 1159x770). */
    private TurmInfo turmLesen(BotContext ctx, boolean aussenposten, Mat screen) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return new TurmInfo("", "", -1, false, false);
        // Tempo (Autor 17.10.: "die Namen ein wenig schneller ablesen"): Titel und Ruhm braucht nur der Aussenposten
        // (Tempel, Ruhm-Regel) -- bei normalen Tuermen faellt das Lesen weg.
        // 23.10. abends: den Titel jetzt bei JEDEM Turm lesen -- daran erkennt der Bot, ob es wirklich ein Outpost ist
        // ("Struggle road" galt nach dem Bild als Outpost, dort stand Autors Name fuer den normalen Trupp).
        String titel = String.join(" ", de.hustlebot.core.Texterkennung.lies(
                de.hustlebot.core.Texterkennung.weisseSchrift(teil(b, 0.25, 0.07, 0.55, 0.08), 3))).strip();
        // Live-Test 17.10.: Mit 0..25 % Breite kam auch der Name ueber der ersten Trupp-Zeile mit ("Autor" im Wrath Outpost,
        // wo sein Titanen-Trupp stand). Jetzt nur die weisse Sprechblase der Clan-Verwaltung (gemessen 15..185 x 60..180).
        // Autor 21.11. (Server 14:23, Titan nicht gesetzt): die Blase ist so hoch wie der Text -- im Tempel standen 11 Namen
        // in 6 Zeilen ("Ben ; Bruno : / Carl ; Paula ; / Anna ; Sam ; / Autor ; ..."), gelesen wurden nur die ersten
        // zwei (bis y 180), "Autor" an 7. Stelle fehlte -> Turm galt als "fuer andere reserviert". Jetzt bis zur Oberkante
        // der Knoepfe unter der Blase (Bild 21.11.: letzte Zeile ~282, Stift-Knopf ab ~312 im Spielbild).
        // Autor 21.11. 15:5x: "die komplette Spalte links vom Turm durchlesen, bis Zeile 20" -- die Blase waechst mit dem
        // Text; ihre Unterkante wird am weissen Hintergrund erkannt (sonst wie zuletzt bis 0,40).
        // Autor 22.11.: "mein Server-Bot setzt mein normales Team immer in den falschen WP" -- seit 3.157 stand bei JEDEM
        // Turm names "": die Blasen-Suche hoerte an der Ueberschrift (weisse Schrift auf der Karte, ~35 % weiss) bzw. an der
        // ersten fetten Namenszeile (11..28 % weiss) auf, gelesen wurde nur "Message from the Clan Administration". Ohne
        // Zettel las der Rueckfall 0,08..0,40 die Turmnamen der Karte ("of the immort ; Treachery of Kings") -> freie
        // Tuerme galten als "fuer andere reserviert". Jetzt nur die weisse Blase selbst: von ihrer Oberkante bis zum gelben
        // Stift-Knopf (bzw. Blasenende); keine Blase = kein Zettel, nichts lesen.
        double[] zettel = zettelBereich(b);
        List<String> zeilen = zettel == null ? List.of()
                : de.hustlebot.core.Texterkennung.lies(teil(b, 0.0, zettel[0], ZETTEL_BREITE, zettel[1] - zettel[0]));
        List<String> namen = new ArrayList<>();
        for (String z : zeilen) {
            String k = z.toLowerCase(java.util.Locale.ROOT);
            if (k.contains("message") || k.contains("clan") || k.contains("istration") || k.contains("from the")) continue;
            if (!z.isBlank()) namen.add(z.strip());
        }
        // Autor 03.11.: auf dem Server stand bei jedem Outpost "glory -1", obwohl "Glory for winning: 5 000" zu sehen war. Die
        // Texterkennung dort (en-US) las "lory or" bzw. "8 ooo". Erst die weisse Zahl allein (zuverlaessig an 5 von 5 Proben,
        // am PC wie auf dem Server), sonst wie bisher der ganze Ausschnitt mit dem Wort "Glory".
        int ruhm = -1;
        if (aussenposten) {
            java.awt.image.BufferedImage ruhmTeil = teil(b, 0.18, 0.82, 0.40, 0.14);
            ruhm = ruhmZahl(String.join(" ", de.hustlebot.core.Texterkennung.lies(
                    de.hustlebot.core.Texterkennung.weisseSchrift(ruhmTeil, 2))));
            if (ruhm < 0) ruhm = ruhmAus(String.join(" ", de.hustlebot.core.Texterkennung.lies(ruhmTeil)));
        }
        // Autor 17.10.: "Ich sag dir nicht, ob ich mein Titanen-Team rausgenommen habe -- das soll der Bot selber erkennen."
        // 1) Ueber jeder Trupp-Zeile im Turm steht der Besitzer ("Autor  4 074 659") -- steht dort der Spielername, ist
        //    sein Trupp dieser Sorte schon gesetzt. 2) Statt "Deploy squad" steht "Move squad": sein Trupp steht woanders.
        String ich = MeinTeam.spielername();
        boolean mein = false;
        if (!ich.isBlank()) {
            for (String z : de.hustlebot.core.Texterkennung.lies(teil(b, 0.19, 0.15, 0.28, 0.60))) {
                String s = z.replaceAll("[0-9 .,]+$", "").strip();
                if (s.length() >= 2 && s.length() <= 24 && de.hustlebot.core.NamensVergleich.gleicherName(s, ich)) {
                    mein = true;
                    break;
                }
            }
        }
        boolean verschieben = ctx.visibleIfKnown(screen, T_MOVE);   // Autors Bild vom Knopf (statt Texterkennung)
        // Autor 25.10. (Drittkonto): "Stuetzpunkt fuer TR10-Konten -- wenn 'your throne room has to be at lvl 10' dasteht".
        // Nur beim Zweitkonto-Bot fuer mehrere Konten gelesen (kostet eine Texterkennung je Turm).
        letzterTurmMitStufe = false;
        if (mehrereKonten && !aussenposten) {
            String unten = String.join(" ", de.hustlebot.core.Texterkennung.lies(teil(b, 0.20, 0.55, 0.60, 0.35)))
                    .toLowerCase(java.util.Locale.ROOT);
            letzterTurmMitStufe = unten.contains("has to be at") || unten.contains("throne room has to");
        }
        return new TurmInfo(titel, String.join(" ; ", namen), ruhm, mein, verschieben);
    }

    /** Spielername in diesem Krieg schon in der Burg gesucht. */
    private boolean nameVersucht;

    // Autor 17.10.: "Zu schade, dass ich meine Einheiten in den falschen Outpost und Stuetzpunkt eingesetzt habe -- das
    // soll der Bot auch erkennen und aendern: auch wenn er zweimal 'Your squad' sieht, einmal alle Stuetzpunkte/Outposts
    // durchgehen und schauen, ob meine Teams an der richtigen Stelle stehen." Umgesetzt wird mit "Move squad".
    static final String T_MOVE = "cw_move_squad";
    /** "Got it" im Fenster "Warning!" nach dem Bearbeitungsmodus -- noch nicht aufgenommen (Pruefer 29.10.). */
    static final String T_GOT_IT = "cw_got_it";
    /** Sorten, deren Platz schon geprueft (oder vom Bot selbst nach den Regeln gesetzt) ist. */
    private final java.util.EnumSet<Sorte> geprueft = java.util.EnumSet.noneOf(Sorte.class);
    /** Sorten, bei denen der eigene Name schon gefunden wurde -- die anderen Tuerme dieser Art nicht mehr ansehen. */
    private final java.util.EnumSet<Sorte> nameGefunden = java.util.EnumSet.noneOf(Sorte.class);
    /**
     * Autor 25.10.: "Beim Nachpruefen erst den Turm anklicken, wo 'Your squad' dasteht. Wenn meine Einheiten im richtigen
     * Turm schon drin sind, wo mein Name auch dasteht, soll er die anderen Tuerme gar nicht mehr anklicken."
     * Sorten, deren Trupp schon im Turm mit dem eigenen Namen gesehen wurde.
     */
    private final java.util.EnumSet<Sorte> richtigGesehen = java.util.EnumSet.noneOf(Sorte.class);
    /** Wo auf der Karte "Your squad" steht (zuletzt gesehen) -- diese Tuerme beim Nachpruefen zuerst. */
    private final List<java.awt.Point> schildOrte = new ArrayList<>();
    /**
     * Autor 25.10.: "Wenn du nachcheckst, bei jedem Account nur auf die Stuetzpunkte druecken, wo Your Squad drueber steht.
     * Wenn dort mein Team eingesetzt ist ... passt das so. Wenn kein Name da steht, passt es auch so." -- im Nachpruef-
     * Fenster (15 min vor Kriegsbeginn) oder mit der Datei clanwar-nur-pruefen.txt: nur diese Tuerme ansehen, nie umsetzen.
     * Fehlt ein Trupp, wird er wie sonst gesetzt.
     */
    private boolean nurPruefen;
    /**
     * Zweitkonto-Bot fuer mehrere Konten (Datei clanwar-name-lesen.txt, 25.10.): den Spielernamen bei jedem Lauf in der
     * Burg neu lesen und alle offenen Tuerme ansehen (Turm fuer die eigene Thronsaal-Stufe vor dem Namen).
     */
    private boolean mehrereKonten;
    /** Tuerme mit der Bedingung "Your Throne Room has to be at level ..." (ohne roten Kasten = fuer dieses Konto gedacht). */
    private final java.util.Set<String> stufenTuerme = new java.util.HashSet<>();
    private boolean letzterTurmMitStufe;
    /** Abstand (1159er Bild), bis zu dem ein "Your squad"-Schild zu einem Turm gehoert. */
    private static final double SCHILD_NAH = 110;
    /** Alle gesetzten Trupps stehen richtig (oder es gibt keinen Namen zum Vergleichen). */
    private boolean verifikationFertig;
    /** Gerade wird ein Trupp hierher umgesetzt. */
    private Turm umsetzenTurm;
    private Sorte umsetzenSorte;
    private boolean umsetzenGeklickt;
    private int umsetzenVersuche;
    /** Fuer den normalen Trupp gab es keinen Turm -- weiter mit den Outposts statt zu warten. */
    private boolean normalUebersprungen;
    /** Die noch anzusehenden Tuerme der laufenden Phase, links nach rechts (Tempo: ohne neue Kartenauswertung). */
    private final List<Turm> erkundenListe = new ArrayList<>();
    /** So oft schon gewartet, weil auf der Karte (noch) kein Aussenposten erkannt war. */
    private int kartenWarten;

    /**
     * Autors Bild 17.10.: Nach "Move squad" kommt "Create squad" mit orangem "Move" und rotem "Cancel" (Mitte unten,
     * gemessen 468,663 bzw. 686,663 bei 1159x770). Gelesen wird nur der Text auf dem linken Knopf.
     */
    private Optional<java.awt.Point> moveKnopf(BotContext ctx) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return Optional.empty();
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(
                de.hustlebot.core.Texterkennung.weisseSchrift(teil(b, 0.33, 0.83, 0.15, 0.07), 3))).toLowerCase(java.util.Locale.ROOT);
        if (!text.contains("move") || text.contains("cancel") || text.contains("squad")) return Optional.empty();
        return Optional.of(new java.awt.Point((int) (0.404 * b.getWidth()), (int) (0.861 * b.getHeight())));
    }

    /** Reihenfolge, in der ein Turm "der richtige" ist: erst der Spielername, beim Titanen (nur Autor) dann der Ruhm. */
    private List<Turm> rangliste(List<Turm> tuerme, Sorte sorte) {
        String ich = MeinTeam.spielername();
        List<Turm> dieser = tuerme.stream()
                .filter(tt -> (tt.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) == sorte)
                .filter(tt -> erkundet.containsKey(tt.schluessel()))
                .toList();
        List<Turm> liste = new ArrayList<>();
        for (Turm tt : dieser) if (enthaeltNamen(erkundet.get(tt.schluessel()).namen(), ich)) liste.add(tt);
        if (sorte == Sorte.AUSSENPOSTEN && ruhmRegelAn()) {
            List<Turm> rest = dieser.stream().filter(tt -> !liste.contains(tt)).toList();
            liste.addAll(nachRuhm(rest, tt -> erkundet.get(tt.schluessel()), !TitanElixier.umtauschMoeglich()));
        }
        return liste;
    }

    /**
     * Autor 21.11.: steht der Spielername an keinem Titanen-Turm, kommt das Titanen-Team in den mit dem meisten Ruhm
     * ("Glory for winning: 11 500"). Die Tempel ("Temple of the ... Elements") verlangen Feuer-Titanen -- solange der
     * Elixier-Umtausch nicht moeglich ist (tempelMeiden), kommen sie erst nach allen anderen. Tuerme ohne gelesenen Ruhm
     * fallen weg. Absteigend nach Ruhm.
     */
    static <T> List<T> nachRuhm(List<T> kandidaten, java.util.function.Function<T, TurmInfo> info, boolean tempelMeiden) {
        java.util.Comparator<T> ruhmAb = java.util.Comparator.comparingInt(t -> -info.apply(t).ruhm());
        java.util.Comparator<T> folge = tempelMeiden
                ? java.util.Comparator.<T>comparingInt(t -> info.apply(t).tempel() ? 1 : 0).thenComparing(ruhmAb)
                : ruhmAb;
        return kandidaten.stream().filter(t -> info.apply(t) != null && info.apply(t).ruhm() > 0).sorted(folge).toList();
    }

    /** Der erste nach {@link #nachRuhm}: meister Ruhm, Tempel nur als letzte Wahl, solange tempelMeiden. */
    static <T> Optional<T> meisterRuhm(List<T> kandidaten, java.util.function.Function<T, TurmInfo> info, boolean tempelMeiden) {
        return nachRuhm(kandidaten, info, tempelMeiden).stream().findFirst();
    }

    /** Die Nachfrage nach "Move squad": nur Yes, wenn sie vom Verschieben spricht und KEINE Zahl (Preis) darin steht. */
    static boolean moveFrage(String t) {
        if (t == null || t.isBlank()) return false;
        String k = t.toLowerCase(java.util.Locale.ROOT);
        boolean kauf = k.contains("buy") || k.contains("purchase") || k.contains("diamond") || k.contains("gem")
                || k.contains("resource") || k.contains("missing") || k.matches(".*\\d.*");
        return (k.contains("move") || k.contains("relocat") || k.contains("transfer")) && !kauf;
    }

    /** Einen Turm zum Umsetzen oeffnen. */
    /** Autor 26.10.: der volle Titanen-Turm mit seinem Namen, sonst der mit dem meisten Ruhm. */
    private Optional<Turm> rauswurfZiel(List<Turm> tuerme) {
        String ich = MeinTeam.spielername();
        List<Turm> volle = tuerme.stream().filter(Turm::aussenposten).filter(Turm::voll).toList();
        for (Turm t : volle) {
            TurmInfo i = erkundet.get(t.schluessel());
            if (i != null && enthaeltNamen(i.namen(), ich)) return Optional.of(t);
        }
        return volle.stream().max(java.util.Comparator.comparingInt(t -> {
            TurmInfo i = erkundet.get(t.schluessel());
            return i == null ? -1 : i.ruhm();
        }));
    }

    /**
     * Der Text im Kasten "Special requirements" des offenen Turmfensters (Fotos 26.10.), klein geschrieben, sonst "".
     * Autors Bild 21.11.: der Satz "You can only deploy earth titans" steht ueber "Glory for winning", etwa x 220..940,
     * y 560..640 im 1159x770-Bild -- das liegt ganz in diesem Ausschnitt (x 174..985, y 508..647).
     */
    private static String elementTextImBild(BotContext ctx) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return "";
        int x = (int) (b.getWidth() * 0.15), y = (int) (b.getHeight() * 0.66), w = (int) (b.getWidth() * 0.70), h = (int) (b.getHeight() * 0.18);
        try {
            return String.join(" ", de.hustlebot.core.Texterkennung.lies(b.getSubimage(x, y, w, h))).toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Steht im Text "You can only deploy ... titans"? (wie bisher nur der Satzanfang) */
    static boolean elementPflicht(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("only deploy") || t.contains("can only");
    }

    /**
     * Das verlangte Element aus dem Kasten "Special requirements" -- nur, wenn der Satz selbst zu sehen ist (ein Wort wie
     * "deploy", "only" oder "titans"), damit ein zufaelliges Wort im Ausschnitt keinen Umtausch ausloest.
     */
    static TitanElixier.Element verlangtesElement(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (!(t.contains("depl") || t.contains("only") || t.contains("titan"))) return null;
        return elementAus(t);
    }

    /** Die Element-Woerter, wie sie im Turmfenster stehen koennen (englisch; deutsch fuer eine deutsche Spielsprache). */
    private static final Map<String, TitanElixier.Element> ELEMENT_WOERTER = Map.of(
            "earth", TitanElixier.Element.ERDE, "water", TitanElixier.Element.WASSER, "fire", TitanElixier.Element.FEUER,
            "light", TitanElixier.Element.LICHT, "dark", TitanElixier.Element.DUNKEL);
    private static final Map<String, TitanElixier.Element> ELEMENT_DEUTSCH = Map.of(
            "erd", TitanElixier.Element.ERDE, "wasser", TitanElixier.Element.WASSER, "feuer", TitanElixier.Element.FEUER,
            "licht", TitanElixier.Element.LICHT, "dunkel", TitanElixier.Element.DUNKEL);

    /**
     * Autor 21.11.: welches Element verlangt der Titanen-Turm? "You can only deploy earth titans" -> ERDE. Tolerant gegen
     * Lesefehler (ein Buchstabe falsch/fehlend/zuviel, "1" statt "l", "0" statt "o", Satzzeichen). Stehen zwei
     * verschiedene Elemente da, zaehlt das Wort direkt nach "deploy" bzw. direkt vor "titans"; sonst null. Kein Element
     * (oder kein Text) = null.
     */
    static TitanElixier.Element elementAus(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT).replace('1', 'l').replace('|', 'l').replace('0', 'o')
                .replaceAll("[^a-zäöüß]+", " ").strip();
        if (t.isEmpty()) return null;
        String[] w = t.split(" ");
        java.util.Set<TitanElixier.Element> gefunden = java.util.EnumSet.noneOf(TitanElixier.Element.class);
        TitanElixier.Element nachDeploy = null, vorTitans = null;
        for (int i = 0; i < w.length; i++) {
            TitanElixier.Element e = elementWort(w[i]);
            if (e == null) continue;
            gefunden.add(e);
            if (i > 0 && w[i - 1].startsWith("depl")) nachDeploy = e;
            if (i + 1 < w.length && (w[i + 1].startsWith("tita") || w[i + 1].startsWith("titn"))) vorTitans = e;
        }
        if (gefunden.size() == 1) return gefunden.iterator().next();
        if (nachDeploy != null) return nachDeploy;
        return vorTitans;
    }

    /** Ein einzelnes Wort -> Element (Abstand hoechstens 1 Buchstabe; deutsch auch als Wortanfang: "erdtitanen"). */
    private static TitanElixier.Element elementWort(String wort) {
        if (wort.length() < 3) return null;
        for (Map.Entry<String, TitanElixier.Element> e : ELEMENT_WOERTER.entrySet()) {
            if (wort.equals(e.getKey())) return e.getValue();
        }
        for (Map.Entry<String, TitanElixier.Element> e : ELEMENT_DEUTSCH.entrySet()) {
            if (wort.startsWith(e.getKey())) return e.getValue();
        }
        if (wort.length() < 4) return null;
        for (Map.Entry<String, TitanElixier.Element> e : ELEMENT_WOERTER.entrySet()) {
            if (abstandHoechstensEins(wort, e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Levenshtein-Abstand <= 1 (ein Buchstabe ersetzt, eingefuegt oder weggelassen). */
    static boolean abstandHoechstensEins(String a, String b) {
        if (a.equals(b)) return true;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0, j = 0;
        boolean fehler = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (fehler) return false;
            fehler = true;
            if (la > lb) i++;
            else if (lb > la) j++;
            else {
                i++;
                j++;
            }
        }
        return !(fehler && (i < la || j < lb));
    }

    private Outcome turmOeffnen(BotContext ctx, Turm t) {
        turmAntippen(ctx, t, false, "Open the tower to move the squad there", "Turm zum Umsetzen oeffnen");
        return Outcome.ACTED;
    }

    // ---- Sperrzone "Editing mode" (Autors oberste Regel: im Clan-Krieg NIE "Editing mode" druecken) ----------------
    // Server 29.10., 20:24: ein Klick auf "Deploy squad" traf die schon geschlossene Karte -- genau an derselben Stelle
    // liegt dort "Editing mode" (586,204 in Autors 1159x770-Fenster, Vorlage cw_edit_mode 180x44). Jeder Klick des
    // Clan-Kriegs geht deshalb durch klickErlaubt(): nie in das gesehene "Editing mode", und ein BERECHNETER Klick
    // (Turm, "No" neben Yes, Move) auch nie an dessen bekannte Stelle -- egal, was das Bild gerade zeigt.
    static final int EDIT_X = 586, EDIT_Y = 204, EDIT_HALB_B = 100, EDIT_HALB_H = 30;

    /**
     * Rein rechnerisch (testbar): liegt der Klick (x,y) in der Sperrzone?
     *
     * @param editGesehen wo cw_edit_mode gerade im Bild steht (null = nicht zu sehen)
     * @param berechnet   true = Klick an einer ausgerechneten Stelle (dann gilt auch die bekannte Stelle 586,204)
     */
    static boolean inSperrzone(int x, int y, int breite, int hoehe, java.awt.Rectangle editGesehen, boolean berechnet) {
        if (editGesehen != null) {
            java.awt.Rectangle r = new java.awt.Rectangle(editGesehen);
            r.grow(10, 8);
            if (r.contains(x, y)) return true;
        }
        if (!berechnet || breite <= 0 || hoehe <= 0) return false;
        double sx = breite / 1159.0, sy = hoehe / 770.0;
        return Math.abs(x - EDIT_X * sx) <= EDIT_HALB_B * sx && Math.abs(y - EDIT_Y * sy) <= EDIT_HALB_H * sy;
    }

    /** Die zentrale Pruefung vor jedem Klick im Clan-Krieg. false = NICHT klicken (steht dann im Protokoll). */
    private boolean klickErlaubt(Mat bild, BotContext ctx, int x, int y, boolean berechnet, String was) {
        Mat b = bild != null ? bild : ctx.aktuellesBild();
        java.awt.Rectangle edit = null;
        if (b != null && ctx.matcher().has(TitanRauswurf.T_EDIT_MODE)) {
            Optional<Matcher.Hit> h = ctx.find(b, TitanRauswurf.T_EDIT_MODE);
            if (h.isPresent()) edit = new java.awt.Rectangle(h.get().x(), h.get().y(), h.get().width(), h.get().height());
        }
        if (!inSperrzone(x, y, ctx.width(), ctx.height(), edit, berechnet)) return true;
        Log.warn("Clan-Krieg", Sprache.t("Click at " + x + "," + y + " (" + was + ") NOT done -- \"Editing mode\" is there"
                        + (edit != null ? " (visible)" : " (known spot)") + ".",
                "Klick bei " + x + "," + y + " (" + was + ") NICHT gemacht -- dort liegt \"Editing mode\""
                        + (edit != null ? " (im Bild)" : " (bekannte Stelle)") + "."));
        if (sperrzoneFotos++ < 3) testFoto(ctx, "sperrzone");
        return false;
    }
    private int sperrzoneFotos;

    /** Wie ctx.clickIfVisible(), nur mit der Sperrzone. */
    private boolean klickVorlage(Mat screen, BotContext ctx, String vorlage, String grund) {
        Optional<Matcher.Hit> h = ctx.find(screen, vorlage);
        return h.isPresent() && klickTreffer(screen, ctx, h.get(), grund);
    }

    /** Wie ctx.clickIfVisibleIfKnown(), nur mit der Sperrzone. */
    private boolean klickVorlageWennBekannt(Mat screen, BotContext ctx, String vorlage, String grund) {
        return ctx.matcher().has(vorlage) && klickVorlage(screen, ctx, vorlage, grund);
    }

    /** Einen gefundenen Treffer anklicken (wie ctx.klick), nur mit der Sperrzone. */
    private boolean klickTreffer(Mat screen, BotContext ctx, Matcher.Hit h, String grund) {
        if (!klickErlaubt(screen, ctx, h.centerX(), h.centerY(), false, grund)) return false;
        ctx.klick(h, grund);
        return true;
    }

    /** Ein Klick an einer ausgerechneten Stelle -- nie in die Sperrzone. logText steht wie bisher im Protokoll. */
    private boolean klickPunkt(Mat screen, BotContext ctx, int x, int y, String was, String logText) {
        if (!klickErlaubt(screen, ctx, x, y, true, was)) return false;
        Log.action("Klick", logText);
        ctx.input().click(x, y);
        return true;
    }

    // ---- Einen Turm antippen: einmal, dann auf sein Fenster warten (TR5 29.10., 14:55) -----------------------------
    // 14:55:08.911 und 10.294 wurde derselbe Turm zweimal geoeffnet: Nach dem Antippen kam kein Warten, das naechste
    // Bild zeigte noch die Karte, der zweite Tipp landete im gerade aufgehenden Turmfenster. Das zaehlte doppelt
    // (ab 5 Mal gilt ein Turm als gesperrt), und schloss ein solcher Fehlklick das Fenster, drueckte der Bot auf dem
    // alten Bild "Deploy squad" -- an der Stelle von "Editing mode". Jetzt: nach dem Tipp auf das Fenster warten, und
    // solange es nicht da ist (hoechstens TURM_FENSTER_WARTEN_MS x Rechnertempo), wird auf der Karte nichts angetippt.
    static final long TURM_FENSTER_WARTEN_MS = 3000;
    /** So oft darf ein Turm zum Setzen angetippt werden, ohne dass sein Fenster aufgeht -- dann gilt er als gesperrt. */
    static final int MAX_KLICKS_OHNE_FENSTER = 3;
    /** Schluessel des zuletzt angetippten Turms, dessen Fenster noch nicht gesehen ist (null = keiner). */
    private String turmKlickSchluessel;
    private boolean turmKlickZumSetzen;
    private long turmKlickUm;
    private final Map<String, Integer> klicksOhneFenster = new java.util.HashMap<>();

    /** Tippt den Turm an (Sperrzone beachtet). false = nicht getippt, weil die Stelle unter "Editing mode" liegt. */
    private boolean turmAntippen(BotContext ctx, Turm t, boolean zumSetzen, String en, String de) {
        int klickY = klickYOhneSchild(ctx, t);
        // Liegt die Tippstelle ueber dem Zaehler unter "Editing mode", den Zaehler selbst antippen (ging immer schon).
        String was = Sprache.t(en, de);
        Mat bild = ctx.aktuellesBild();
        if (!klickErlaubt(bild, ctx, t.x(), klickY, true, was)) {
            if (klickY == t.y() || !klickErlaubt(bild, ctx, t.x(), t.y(), true, was)) {
                if (!gesperrteTuerme.contains(t.schluessel())) {
                    gesperrteTuerme.add(t.schluessel());
                    warnung(Sprache.t("Tower at " + t.x() + "," + t.y() + " lies under \"Editing mode\" -- it is not tapped.",
                            "Turm bei " + t.x() + "," + t.y() + " liegt unter \"Editing mode\" -- er wird nicht angetippt."));
                }
                return false;
            }
            klickY = t.y();
        }
        Log.action("Klick", Sprache.t(en + " at " + t.x() + "," + klickY, de + " bei " + t.x() + "," + klickY));
        ctx.input().click(t.x(), klickY);
        turmKlickSchluessel = t.schluessel();
        turmKlickZumSetzen = zumSetzen;
        turmKlickUm = System.currentTimeMillis();
        // Tempo: weiter, sobald das Turmfenster da ist (die Geduld mit Rechnertempo steckt in turmFensterAbwarten()).
        ctx.warteAufKnopf(1500, 0.8, T_SQUADS_LABEL, T_DEPLOY_SQUAD);
        return true;
    }

    /** Das Fenster des angetippten Turms ist da: jetzt erst zaehlt das Oeffnen (Pruefer 29.10.: nur echte Fenster). */
    private void turmFensterGesehen() {
        if (turmKlickSchluessel == null) return;
        if (turmKlickZumSetzen) oeffnenJeTurm.merge(turmKlickSchluessel, 1, Integer::sum);
        klicksOhneFenster.remove(turmKlickSchluessel);
        turmKlickSchluessel = null;
    }

    /**
     * Auf der Karte: wartet der Bot noch auf das Fenster eines eben angetippten Turms? true = nichts antippen.
     * Ist die Geduld um, zaehlt es als Tipp ohne Wirkung; nach MAX_KLICKS_OHNE_FENSTER wird der Turm uebersprungen.
     */
    private boolean turmFensterAbwarten() {
        if (turmKlickSchluessel == null) return false;
        if (System.currentTimeMillis() - turmKlickUm < de.hustlebot.core.Rechnertempo.mal(TURM_FENSTER_WARTEN_MS)) return true;
        String k = turmKlickSchluessel;
        turmKlickSchluessel = null;
        if (!turmKlickZumSetzen) return false;   // Ansehen/Umsetzen zaehlen ihre Versuche selbst
        int n = klicksOhneFenster.merge(k, 1, Integer::sum);
        Log.info("Clan-Krieg", Sprache.t("The tower window did not open after tapping (" + n + ". time).",
                "Das Turmfenster ging nach dem Antippen nicht auf (" + n + ". Mal)."));
        if (n >= MAX_KLICKS_OHNE_FENSTER && !gesperrteTuerme.contains(k)) {
            gesperrteTuerme.add(k);
            warnung(Sprache.t("Tower tapped " + n + " times, its window never opened -- skipped, trying another one.",
                    "Turm " + n + " Mal angetippt, sein Fenster ging nie auf -- uebersprungen, ein anderer wird probiert."));
        }
        return false;
    }

    /** Einen Turm nur zum Lesen oeffnen (wie zum Setzen, nur ohne "Deploy"). */
    /**
     * Tippstelle ueber dem Zaehler eines Turms. Autor 18.10.: Das "Your squad"-Schild lag ueber dem Temple of the
     * Unstoppable Elements -- der Tipp traf das Schild, der Temple wurde nie angesehen. Liegt ein Schild auf der
     * Tippstelle, hoeher auf den Turm selbst tippen. Code-Review 23.10.: gilt fuers Ansehen, Setzen und Umsetzen.
     */
    private int klickYOhneSchild(BotContext ctx, Turm t) {
        int klickY = t.y() - (int) (ctx.height() * TURM_UEBER_ZAEHLER);
        if (klickY < 0) klickY = t.y();
        if (ctx.matcher().has(T_YOUR_SQUAD)) {
            Mat jetzt = ctx.refresh();
            if (jetzt != null) {
                for (Matcher.Hit s : ctx.matcher().findAll(jetzt, T_YOUR_SQUAD, 0.85, 4)) {
                    if (Math.abs(s.centerX() - t.x()) < s.width() / 2 + 10 && Math.abs(s.centerY() - klickY) < s.height() / 2 + 12) {
                        klickY = Math.max(5, s.y() - (int) (ctx.height() * 0.05));
                        break;
                    }
                }
            }
        }
        return klickY;
    }

    private Outcome erkundenKlick(BotContext ctx, Turm t) {
        turmAntippen(ctx, t, false, "Look at " + (t.aussenposten() ? "outpost" : "tower") + " (whose name is written there?)",
                (t.aussenposten() ? "Aussenposten" : "Turm") + " ansehen (wessen Name steht dort?)");
        return Outcome.ACTED;
    }

    private static java.awt.image.BufferedImage teil(java.awt.image.BufferedImage b, double x, double y, double w, double h) {
        int px = (int) (x * b.getWidth()), py = (int) (y * b.getHeight());
        int pw = Math.min((int) (w * b.getWidth()), b.getWidth() - px), ph = Math.min((int) (h * b.getHeight()), b.getHeight() - py);
        return b.getSubimage(px, py, Math.max(1, pw), Math.max(1, ph));
    }

    /** Breite des Lesebereichs fuer den Zettel: die Blase reicht bis ~0,162, ab ~0,172 beginnt der Rand des Turmfensters. */
    static final double ZETTEL_BREITE = 0.17;

    /**
     * Bereich der Namen im Zettel der Clan-Verwaltung links neben dem Turmfenster: {oben, unten} relativ zur Bildhoehe,
     * {@code null} = kein Zettel (keine weisse Blase). Autor 22.11. (Server-Fotos 21./22.11.): die Ueberschrift "Message
     * from the Clan Administration" steht weiss auf der Karte UEBER der Blase (hoechstens ~36 % weisse Punkte je Zeile),
     * fette Namenszeilen in der Blase haben nur noch 11..28 % Weiss. Darum: Oberkante = erste von 3 Zeilen am Stueck, die
     * fast ganz weiss sind (Rand der Blase ueber der ersten Namenszeile, bis 0,45 gesucht). Unterkante = gelber Stift-Knopf
     * (sieht die Clan-Verwaltung) oder das Blasenende (6 Zeilen am Stueck fast ohne Weiss). Bis zu 20 Zeilen (Autor 21.11.).
     */
    static double[] zettelBereich(java.awt.image.BufferedImage b) {
        if (b == null || b.getWidth() < 50 || b.getHeight() < 50) return null;
        int h = b.getHeight(), x0 = (int) (b.getWidth() * 0.02), x1 = (int) (b.getWidth() * 0.16);
        int oben = -1, amStueck = 0;
        for (int y = (int) (h * 0.10); y < (int) (h * 0.45); y++) {
            amStueck = anteil(b, y, x0, x1, false) >= 85 ? amStueck + 1 : 0;
            if (amStueck == 3) {
                oben = y - 2;
                break;
            }
        }
        if (oben < 0) return null;
        int bis = (int) (h * 0.95), unten = bis, dunkel = 0;
        for (int y = oben + 3; y < bis; y++) {
            if (anteil(b, y, x0, x1, true) >= 25) {
                unten = y;
                break;
            }
            dunkel = anteil(b, y, x0, x1, false) < 5 ? dunkel + 1 : 0;
            if (dunkel >= 6) {
                unten = y - 5;
                break;
            }
        }
        if (unten - oben < 8) return null;
        return new double[] {oben / (double) h, unten / (double) h};
    }

    /** Anteil (Prozent) fast weisser bzw. gelber Punkte einer Bildzeile im Bereich x0..x1 (jeder 2. Punkt). */
    private static int anteil(java.awt.image.BufferedImage b, int y, int x0, int x1, boolean gelb) {
        int treffer = 0, n = 0;
        for (int x = x0; x < x1; x += 2) {
            int rgb = b.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
            if (gelb ? r > 200 && g > 130 && bl < 120 && r - bl > 100 : r > 230 && g > 230 && bl > 230) treffer++;
            n++;
        }
        return n == 0 ? 0 : treffer * 100 / n;
    }

    /**
     * Nach der Erkundung: Turm nach Autors Regeln -- erst der Spielername, dann (nur Titanen, nur bei Autor) der meiste
     * Ruhm ohne Temple. Leer = wie bisher (leere Tuerme zuerst, sonst zufaellig).
     */
    private Optional<Map.Entry<Sorte, Turm>> zielNachRegeln(List<Turm> tuerme, List<Sorte> offeneSorten) {
        String ich = MeinTeam.spielername();
        for (Sorte sorte : offeneSorten) {
            List<Turm> dieser = tuerme.stream()
                    .filter(t -> (t.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) == sorte)
                    .filter(t -> !t.voll())
                    .filter(t -> erkundet.containsKey(t.schluessel()))
                    .filter(t -> !gesperrteTuerme.contains(t.schluessel()) || nurNamensGesperrt.contains(t.schluessel()))
                    .toList();
            // Autor 25.10. (Drittkonto): zuerst ein Turm "Your throne room has to be at level ..." (fuer dieses Konto gedacht).
            if (sorte == Sorte.NORMAL) {
                for (Turm t : dieser) {
                    if (!stufenTuerme.contains(t.schluessel())) continue;
                    Log.info("Clan-Krieg", Sprache.t("Tower for this throne room level: \"" + erkundet.get(t.schluessel()).titel()
                                    + "\" -- the squad goes there.",
                            "Turm fuer diese Thronsaal-Stufe: \"" + erkundet.get(t.schluessel()).titel() + "\" -- dorthin kommt der Trupp."));
                    zielNachName = true;
                    return Optional.of(Map.entry(sorte, t));
                }
            }
            for (Turm t : dieser) {
                TurmInfo i = erkundet.get(t.schluessel());
                if (enthaeltNamen(i.namen(), ich)) {
                    Log.info("Clan-Krieg", Sprache.t("Name \"" + ich + "\" found at \"" + i.titel() + "\" (" + i.namen() + ") -- the "
                            + (sorte == Sorte.AUSSENPOSTEN ? "titan squad" : "squad") + " goes there.",
                            "Name \"" + ich + "\" steht bei \"" + i.titel() + "\" (" + i.namen() + ") -- dorthin kommt der "
                            + (sorte == Sorte.AUSSENPOSTEN ? "Titanen-Trupp" : "Trupp") + "."));
                    zielNachName = true;
                    return Optional.of(Map.entry(sorte, t));
                }
            }
            // Autor 03.11.: "wenn der Bot irgendwas mit TR11 liest, soll er dort auch mein Team setzen" -- nach dem eigenen
            // Namen kommt ein Turm, dessen Notiz zur eigenen Thronsaal-Stufe passt (beim Titanen der mit dem meisten Ruhm).
            List<Turm> furMich = dieser.stream().filter(t -> stufePasst(erkundet.get(t.schluessel()).namen(), ich)).toList();
            Optional<Turm> stufenTurm = furMich.stream().max(java.util.Comparator.comparingInt(t -> erkundet.get(t.schluessel()).ruhm()));
            if (stufenTurm.isPresent()) {
                TurmInfo i = erkundet.get(stufenTurm.get().schluessel());
                Log.info("Clan-Krieg", Sprache.t("Note \"" + i.namen() + "\" at \"" + i.titel() + "\" fits the own throne room level -- the "
                                + (sorte == Sorte.AUSSENPOSTEN ? "titan squad" : "squad") + " goes there (glory " + i.ruhm() + ").",
                        "Notiz \"" + i.namen() + "\" bei \"" + i.titel() + "\" passt zur eigenen Thronsaal-Stufe -- dorthin kommt der "
                                + (sorte == Sorte.AUSSENPOSTEN ? "Titanen-Trupp" : "Trupp") + " (Ruhm " + i.ruhm() + ")."));
                zielNachName = true;
                return Optional.of(Map.entry(sorte, stufenTurm.get()));
            }
            if (sorte == Sorte.AUSSENPOSTEN && ruhmRegelAn()) {
                List<Turm> mitRuhm = dieser.stream().filter(t -> erkundet.get(t.schluessel()).ruhm() > 0)
                        .filter(t -> !gesperrteTuerme.contains(t.schluessel()) || nurNamensGesperrt.contains(t.schluessel()))
                        .filter(t -> !fremderName(t))   // Pruefer 29.10.: auch hier nie in einen fremden (Autor 23.10.)
                        .toList();
                Optional<Turm> best = meisterRuhm(mitRuhm, t -> erkundet.get(t.schluessel()), !TitanElixier.umtauschMoeglich());
                if (best.isPresent()) {
                    TurmInfo i = erkundet.get(best.get().schluessel());
                    Log.info("Clan-Krieg", Sprache.t("Titan squad: \"" + ich + "\" not at any outpost -- most glory: \"" + i.titel()
                            + "\" (" + i.ruhm() + ")" + (i.tempel() ? ", temple" : ", no temple") + ".",
                            "Titanen-Trupp: \"" + ich + "\" steht an keinem Outpost -- meister Ruhm: \"" + i.titel()
                            + "\" (" + i.ruhm() + ")" + (i.tempel() ? ", Tempel" : ", kein Tempel") + "."));
                    zielNachName = i.tempel();   // Tempel (einziger, oder Umtausch moeglich): dann trotz Sperrliste dorthin
                    return Optional.of(Map.entry(sorte, best.get()));
                }
            }
        }
        return Optional.empty();
    }

    private boolean normalGesetzt;
    private boolean titanGesetzt;

    /** Nur wegen ihres NAMENS gesperrt -- wird im Notfall wieder aufgehoben. */
    private final List<String> nurNamensGesperrt = new ArrayList<>();
    /**
     * Server 21.11. 14:23: der Notausgang hob die Sperrliste auf, beim erneuten Oeffnen griff die Namenspruefung aber
     * wieder ("This tower is on the skip list") -- 4x im Kreis, Titan nicht gesetzt. Nach dem Aufheben gilt sie in diesem
     * Krieg darum gar nicht mehr.
     */
    private boolean sperrlisteAufgehoben;

    /** Fuer die Zufallswahl unter gleich schlechten Tuermen. */
    private final java.util.Random zufall = new java.util.Random();

    /** Tuerme, die nicht gehen -- zu hoher Thronsaal, kein Platz, kein Essen. */
    private final List<String> gesperrteTuerme = new ArrayList<>();
    /** Wie oft jeder Turm in diesem Lauf geoeffnet wurde (Ben 21.10.: Endlosschleife an einem Turm mit Sonderregel). */
    private final java.util.Map<String, Integer> oeffnenJeTurm = new java.util.HashMap<>();
    static final int MAX_OEFFNEN_JE_TURM = 4;

    /** Welche Sorte gerade bearbeitet wird, damit das Truppenfenster weiss, was zu druecken ist. */
    private Sorte offenerTurm;

    /**
     * Welchen Turm der Bot gerade offen hat.
     *
     * Hier stand zuerst nur die Sorte. Damit liess sich ein missglueckter
     * Turm aber nicht merken -- die Sperrliste bekam einen erfundenen
     * Schluessel, der zu keinem Turm auf der Karte passte, und der Bot
     * haette denselben Turm bis in alle Ewigkeit wieder geoeffnet. Genau
     * diese Sorte Schleife hat im Verlies schon einmal eine Nacht
     * gekostet.
     */
    private Turm offenePosition;

    /** Aepfel reichen nicht -- der normale Turm wird aufgegeben. */
    private boolean essenReichtNicht;
    /** Nahrung vor dem Krieg aus den Nahrungskisten holen (Autor, 27.09.). */
    private final Nahrungskisten nahrung = new Nahrungskisten();
    /** In diesem Krieg schon nach der Nahrung gesehen. */
    private boolean nahrungGeprueft;

    /**
     * Das offene Turmfenster soll beim naechsten Schritt geschlossen werden.
     *
     * Warum nicht sofort: GameMode.step() schreibt vor, "genau eine Aktion
     * auszufuehren, nie eine ganze Kette". Frueher wurde hier im selben
     * Schritt erst "No" im Kauffenster und direkt danach das rote X des
     * Turmfensters geklickt. Zu diesem Zeitpunkt schliesst sich das
     * Kauffenster gerade erst -- das X liegt noch darunter und ist entweder
     * gar nicht getroffen oder es wird auf etwas geklickt, das im naechsten
     * Augenblick woanders ist.
     */
    private boolean turmSchliessen;

    /**
     * Wann zuletzt "Deploy squad" gedrueckt wurde -- und wie lange danach
     * auf der Karte nichts angefasst wird.
     *
     * Vier Sekunden reichen fuer die Animation des Truppenfensters mit
     * grossem Abstand. Erkennt der Bot das Fenster ordentlich, greift die
     * Sperre gar nicht erst -- er ist dann laengst im Truppenzweig.
     */
    private long deploySquadGeklickt = 0L;
    private boolean ruheGemeldet = false;
    private boolean knopfGemeldet = false;
    /** Wo und welcher Art der zuletzt bestaetigte Turm war. */
    private Turm zuletztGesetzt = null;
    private Sorte zuletztGesetzteSorte = null;

    /** Ob im gerade offenen Turm die Truppe schon gewaehlt wurde. */
    private boolean truppeGewaehlt = false;
    /** Autor 26.10.: Titanen-Turm mit Element-Pflicht ("You can only deploy water titans") -> "Deploy the best". */
    private boolean elementGeprueft, elementPflicht;
    /** Autor 26.10.: alle Titanen-Tuerme voll -> das schwaechste Team herausnehmen (TitanRauswurf), einmal je Krieg. */
    private final TitanRauswurf rauswurf = new TitanRauswurf();
    private boolean rauswurfVersucht;
    private String rauswurfTurm;
    /** Wie oft hintereinander "Exit" in einem ungewollten Bearbeitungsmodus gedrueckt wurde (hoechstens 5). */
    private int bearbeitungsExits;

    /**
     * Bildfehler-Suche 30.10. (Server 29.10. 20:24, Bericht #BMGQ): Ein Klick traf den gruenen Knopf "Editing mode" der
     * Kriegskarte. Der Bearbeitungsmodus hat kein rotes X und war keiner Vorlage des Clan-Kriegs bekannt -- der Bot las
     * ihn als Kriegskarte (Turm "Eye of the Enchantress" angeklickt, rote Meldung als "Thronsaal passt nicht" gedeutet)
     * und stand dann 6 Minuten still, bis das Spiel die Verbindung verlor. Der Fehler-Fix vom 29.10. verhindert nur
     * diesen einen Fehlklick; jeder andere Weg hinein haette wieder gehangen.
     * Erkennungszeichen: "Save changes" oben UND "Exit" unten rechts zugleich -- beides gibt es nur dort (echtes Foto
     * des Servers: 0,999 und 0,997; auf Kriegskarte, Weltkarte und Etagenkarte hoechstens 0,37 bzw. 0,56).
     */
    static boolean bearbeitungsmodusOffen(java.util.function.Predicate<String> sichtbar) {
        return sichtbar.test(TitanRauswurf.T_EXIT) && sichtbar.test(TitanRauswurf.T_SAVE);
    }
    /** Seit wann im offenen Turm auf den Auswahl-Knopf gewartet wird. 0 = gerade nicht. */
    private long auswahlGesuchtSeit = 0;
    /** So lange wartet der Bot auf "Deploy the best" / "Previous Squad", bevor er ohne bestaetigt. */
    private static final long AUSWAHL_WARTEN_MS = 4000;
    /**
     * So lange steht das Truppenfenster, bevor die Auswahl gedrueckt wird (x Rechnertempo). Server 30.10.: 5 s nach
     * "Deploy squad" wirkte "Previous Squad" nicht, 7,7 s danach im normalen Turm schon.
     */
    private static final long FENSTER_RUHE_MS = 1500;
    private final TruppRunden truppRunden = new TruppRunden();
    private static final long NACH_DEPLOY_RUHE_MS = 4000;

    private int unbekannteBilder;
    private long letzterKartenbericht;

    /**
     * War der Bot ueberhaupt schon einmal auf der Kriegskarte?
     *
     * Daran haengt der Unterschied zwischen "es ist kein Krieg" (voellig in
     * Ordnung, ordentliches Ende) und "ich habe mich verloren" (Fehler).
     */
    private boolean kriegskarteGesehen;
    /** In diesem Versuch war die Burg zu sehen ("Map"-Knopf unten links). */
    private boolean burgGesehen;
    /**
     * Der letzte Versuch hat aufgegeben, ohne die Burg je gesehen zu haben.
     * Die Discord-Bruecke laesst ihn dann wiederholen (Kriegsplan.nochmalErlauben).
     */
    private static volatile boolean ohneBurgAufgegeben = false;

    public static boolean ohneBurgAufgegeben() {
        return ohneBurgAufgegeben;
    }

    public static void ohneBurgVergessen() {
        ohneBurgAufgegeben = false;
    }

    /** Wie viele Bilder lang schon kein freier Turm der gesuchten Sorte da war. */
    private int ohneZiel;
    /**
     * Kein freier Turm mehr fuer das, was noch fehlt (Autor, 28.09., 21:53):
     * dann ordentlich raus aus dem Clan-Krieg (Turmfenster, "Back", rotes X)
     * wie nach einer fertigen Aufstellung -- sonst stand der naechste Modus
     * auf der Kriegskarte und erkannte nichts.
     */
    private boolean nichtsMehrFrei;
    /** Seit wann die Burg (Map-Knopf) ohne Kriegssymbol zu sehen ist, 0 = noch nicht. */
    private long burgOhneSymbolSeit;
    private int burgBilderOhneSymbol;
    /** Einmal Weltkarte und zurueck, bevor "kein Krieg" gilt (Server 29.10.: angewaehlte Kaserne verdeckte das Symbol). */
    private boolean kartenRundeGemacht;

    @Override
    public String id() {
        return "clanwar";
    }

    @Override
    public String displayName() {
        return Sprache.t("Clan war", "Clan-Krieg");
    }

    @Override
    public boolean inEmpfindlicherPhase() {
        return nahrung.werbungLaeuft();
    }

    @Override
    public List<String> requiredTemplates() {
        return List.of(
                T_ICON, T_PREP, T_BACK,
                T_SLASH, T_YOUR_SQUAD,
                T_DEPLOY_SQUAD, T_CREATE_SQUAD,
                T_DEPLOY_BEST, T_PREVIOUS_SQUAD,
                T_CLOSE_X);
    }

    /** Hilfreich, aber der Modus laeuft auch ohne. */
    public static List<String> optionalTemplates() {
        return List.of(
                T_PROGRESS, T_ENDED, T_DEPLOY_GREY, T_ERROR_RED, T_SQUADS_LABEL,
                T_DEPLOY_COST, T_CONFIRM,
                T_BUY, T_BUY_NO, T_WARNING, T_YES, T_INCOMPLETE,
                T_SKIP_PREFIX + "_1", T_SKIP_PREFIX + "_2", T_ERROR_RED + "_2",
                T_OUTPOST_PREFIX + "_orange",
                T_OUTPOST_PREFIX + "_blue",
                T_OUTPOST_PREFIX + "_green");
    }

    /**
     * Helden-Set (Ben 17.10.): beim AUTOMATISCHEN Krieg vorher "War", nach dem Aufstellen wieder "Normal".
     * Beide null = aus (Standard, oder der Krieg wurde von Hand gestartet).
     */
    private HeldenSetWechsel heldVorher, heldNachher;
    /** Versuche fuer das Set vorher; ob der Krieg schon begonnen hat (dann nicht mehr umstellen). */
    private int heldVorherVersuche;
    private boolean kriegBegonnen;
    /** Der Krieg ist fertig aufgestellt -- jetzt nur noch das Set zuruecksetzen, dann FINISHED. */
    private boolean kriegFertig;
    private int heldNachherVersuche;
    /** Womit der Krieg endete (FINISHED oder FATAL) -- gilt, sobald das Set zurueckgestellt ist. */
    private Outcome endeNachSet = Outcome.FINISHED;

    @Override
    public void reset() {
        boolean automatisch = HeldenSet.automatischAbholen();
        heldVorher = null;
        heldVorherVersuche = 0;
        heldNachherVersuche = 0;
        endeNachSet = Outcome.FINISHED;
        kriegBegonnen = false;
        heldNachher = null;
        kriegFertig = false;
        if ((automatisch || HeldenSet.auchVonHand()) && HeldenSet.an()) {
            heldVorher = new HeldenSetWechsel(HeldenSet.vorher(), HeldenSet.vorherPlatz());
            heldNachher = new HeldenSetWechsel(HeldenSet.nachher(), HeldenSet.nachherPlatz());
        }
        normalGesetzt = false;
        titanGesetzt = false;
        gesperrteTuerme.clear();
        oeffnenJeTurm.clear();
        nurNamensGesperrt.clear();
        sperrlisteAufgehoben = false;
        erkundet.clear();
        erkundenTurm = null;
        erkundenGesehen = zielNachName = nameVersucht = false;
        rotImNamensturm.clear();
        erkundenVersuche = erkundungsKlicks = 0;
        geprueft.clear();
        verifikationFertig = false;
        umsetzenTurm = null;
        umsetzenSorte = null;
        umsetzenGeklickt = false;
        umsetzenVersuche = 0;
        normalUebersprungen = false;
        nameGefunden.clear();
        richtigGesehen.clear();
        schildOrte.clear();
        stufenTuerme.clear();
        nurPruefen = java.nio.file.Files.exists(de.hustlebot.core.Ordner.bei("clanwar-nur-pruefen.txt"))
                || de.hustlebot.core.Kriegsplan.istKontrolleJetzt();
        mehrereKonten = java.nio.file.Files.exists(de.hustlebot.core.Ordner.bei("clanwar-name-lesen.txt"));
        // Nacht 26.10. (Drittkonto): der Name des VORIGEN Kontos (Zweitkonto A) blieb stehen -- dessen Trupp galt als "MEIN
        // Trupp". Bei mehreren Konten darum den alten Namen vergessen; gelesen wird er gleich in der Burg neu.
        if (mehrereKonten) {
            MeinTeam.spielernameVergessen();
            // Code-Review 26.10.: die Burg-Lesung schwankt ("Zweitkonto", "-ZweitkontołA", "Zweitkonto A") -- "Zweitkonto"
            // passte dann auf beide Zweitkonto-Konten. Steht der Name des Kontos in clanwar-konto.txt, gilt der und wird nicht
            // ueberlesen (der Kontowechsel schreibt ihn vor jedem Konto).
            String vorgabe = "";
            try {
                java.nio.file.Path f = de.hustlebot.core.Ordner.bei("clanwar-konto.txt");
                if (java.nio.file.Files.exists(f)) {
                    // Code-Review 26.10.: ohne BOM (PowerShell schreibt eins), nur die erste Zeile ohne Kommentar
                    for (String z : de.hustlebot.core.Dateien.zeilen(f)) {
                        String s = z.replace("\uFEFF", "").strip();
                        if (!s.isEmpty() && !s.startsWith("#")) {
                            vorgabe = s;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.debug("Clan-Krieg", "clanwar-konto.txt nicht lesbar: " + e.getMessage());
            }
            if (!vorgabe.isBlank()) {
                MeinTeam.setSpielername(vorgabe);
                nameVersucht = true;
            }
        }
        ohneAussenposten = 0;
        if (nurPruefen) Log.info("Clan-Krieg", Sprache.t("Quick check: only the towers with \"Your squad\" are opened.",
                "Nachpruefen: nur die Tuerme mit \"Your squad\" werden geoeffnet."));
        fremdGemeldet.clear();
        artKorrektur.clear();
        kartenWarten = 0;
        offenerTurm = null;
        offenePosition = null;
        turmSchliessen = false;
        essenReichtNicht = false;
        nahrungGeprueft = false;
        unbekannteBilder = 0;
        letzterKartenbericht = 0;
        kriegskarteGesehen = false;
        burgGesehen = false;
        ohneZiel = 0;
        nichtsMehrFrei = false;
        burgOhneSymbolSeit = 0;
        burgBilderOhneSymbol = 0;
        kartenRundeGemacht = false;
        deploySquadGeklickt = 0L;
        ruheGemeldet = false;
        knopfGemeldet = false;
        truppeGewaehlt = false;
        elementGeprueft = elementPflicht = false;
        rauswurfVersucht = false;
        rauswurfTurm = null;
        rauswurf.aus();
        auswahlGesuchtSeit = 0;
        zuletztGesetzt = null;
        zuletztGesetzteSorte = null;
        gezaehlteSchilder.clear();
        abschlussSchritt = 0;
        abschlussBlicke = 0;
        zuletztSchilder = 0;
        bestaetigt = 0;
        zaehler.leeren();
        letzterPostenBericht = "";
        deployVersuche = 0;
        scrollVersuche = 0;
        geradeGesetzt = false;
        Log.info("Clan-Krieg", Sprache.t("Mode reset. Two squads will be deployed: "
                + "one in a normal tower, one in an outpost.",
                "Modus zurueckgesetzt. Es werden zwei Truppen gesetzt: "
                + "einer in einen normalen Turm, einer in einen Aussenposten."));
    }

    @Override
    public Map<String, String> extraStats() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(Sprache.t("Normal tower", "Normaler Turm"),
                normalGesetzt ? Sprache.t("deployed", "steht")
                        : essenReichtNicht ? Sprache.t("skipped, not enough food",
                                                       "ausgelassen, Essen reicht nicht")
                        : Sprache.t("open", "offen"));
        extra.put(Sprache.t("Titan outpost", "Titanen-Aussenposten"),
                titanGesetzt ? Sprache.t("deployed", "steht") : Sprache.t("open", "offen"));
        if (!gesperrteTuerme.isEmpty()) {
            extra.put(Sprache.t("Towers skipped", "Uebersprungene Tuerme"),
                    String.valueOf(gesperrteTuerme.size()));
        }
        return extra;
    }

    /**
     * Bedenkzeit vor jedem Schritt -- nur im Clan-Krieg.
     *
     * Autor: "kannst gern langsamer aufstellen im clan krieg (langsamer
     * bitte nur im clan krieg, da ist es egal) wenn es dann ohne fehler
     * geht."
     *
     * Er hat recht, und die Rechnung geht klar auf: Die ganze Aufstellung
     * dauert rund dreissig Sekunden und passiert viermal am Tag. Ob sie
     * dreissig oder sechzig Sekunden braucht, merkt niemand. Ein Fenster,
     * das beim Fotografieren noch halb ausgefahren war, kostet dagegen
     * einen ganzen Krieg -- und den gibt es nur einmal.
     *
     * Im Verlies und in der Kathedrale waere dieselbe Pause teuer: Dort
     * laufen hunderte Durchlaeufe, und jede halbe Sekunde je Schritt
     * summiert sich ueber die Nacht zu Stunden. Deshalb steht die Pause
     * hier und nicht im Motor.
     */
    // 27.09.: 700 -> 350 ms. Autor: "nur leicht schneller / smoother, keine enorm langen
    // Pausen". Die 0,7 s vor JEDEM Schritt waren bei 30-40 Schritten je Krieg der
    // groesste Wartebrocken; nach jedem Klick wartet der Bot ohnehin, bis sich das Bild
    // geaendert hat (sleepUntilChanged).
    private static final long BEDENKZEIT_MS = 350;

    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Hier ganz vorsichtig (der Krieg muss immer klappen, siehe oben:
     * ein halb ausgefahrenes Fenster kostet einen ganzen Krieg): nur die Bedenkzeit vor jedem Schritt (350 ms) und die zwei
     * kurzen Pausen beim Erkunden (250 / 150 ms) laufen x Faktor (clankrieg-tempo.txt). Die Wartezeiten auf die Karte,
     * die Kontrolle nach dem Setzen, der Update-Neustart und alle Bildwechsel bleiben. Runde = "Aufstellung fertig";
     * Fehler = jede Warnung zu eigenen Klicks, Fenstern und Bildschirmen -- nicht die zum Essen, zum Kriegsende, zu vollen
     * Tuermen und zum Rauswurf durch Mitspieler (liegen nicht am Tempo).
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("clankrieg-tempo.txt", "Clan-Krieg");
    private int runFehler;
    /** Fertige Aufstellungen seit dem Start (fuer die Tempo-Zeile alle 10 Runden; nicht in reset()). */
    private int tempoRunden;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn("Clan-Krieg", text);
    }

    private void tempoRundeFertig() {
        tempoRunden++;
        tempo.rundeFertig(runFehler == 0);
        if (tempoRunden % 10 == 0) {
            Log.info("Clan-Krieg", Sprache.t("Speed after " + tempoRunden + " deployments: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + tempoRunden + " Aufstellungen: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        // Helden-Set (Ben 17.10.): erst "War" anlegen, dann wie immer aufstellen, danach "Normal" -- und erst dann fertig.
        if (kriegFertig) {
            if (heldNachher != null && !heldNachher.fertig()) return mitFortschritt(heldNachher.schritt(screen, ctx), runs);
            // Code-Review 23.10.: "Normal" hatte nur einen Versuch (bei "War" sind es drei) -- sonst liefen danach
            // stundenlang andere Modi mit dem Kriegs-Set.
            if (heldNachher != null && !heldNachher.erfolg() && ++heldNachherVersuche < 3) {
                warnung(Sprache.t("Hero set not switched back -- trying again (" + (heldNachherVersuche + 1) + ". try).",
                        "Helden-Set nicht zurueckgestellt -- noch einmal (" + (heldNachherVersuche + 1) + ". Versuch)."));
                heldNachher = new HeldenSetWechsel(HeldenSet.nachher(), HeldenSet.nachherPlatz());
                return Outcome.ACTED;
            }
            return endeNachSet;
        }
        if (heldVorher != null && !heldVorher.fertig()) return mitFortschritt(heldVorher.schritt(screen, ctx), runs);
        // Autor 18.10.: "hat mein Clan-Krieg-Team eingesetzt, aber vorher das Helden-Set nicht auf War geaendert" --
        // klappt das Umstellen nicht, noch zweimal versuchen.
        if (heldVorher != null && !heldVorher.erfolg() && !kriegBegonnen) {
            if (++heldVorherVersuche < 3) {
                warnung(Sprache.t("Hero set not switched -- trying again (" + (heldVorherVersuche + 1) + ". try).",
                        "Helden-Set nicht umgestellt -- noch einmal (" + (heldVorherVersuche + 1) + ". Versuch)."));
                heldVorher = new HeldenSetWechsel(HeldenSet.vorher(), HeldenSet.vorherPlatz());
                return Outcome.ACTED;
            }
            // Autor 18.10. (gleich danach): "Er soll die Einheiten trotzdem einsetzen, auch wenn er das Preset nicht
            // geaendert hat." -- also nach 3 Versuchen ohne Set weiter.
            warnung(Sprache.t("Hero set could not be switched 3 times -- deploying anyway.",
                    "Helden-Set liess sich dreimal nicht umstellen -- Truppen werden trotzdem eingesetzt."));
            heldVorher = null;
        }
        kriegBegonnen = true;
        Outcome o;
        if (rauswurf.laeuft()) {
            o = rauswurf.schritt(screen, ctx);
            if (!rauswurf.laeuft() && rauswurf.erfolg() && rauswurfTurm != null) {
                // Ein Platz ist frei: der Turm darf wieder Ziel sein, und die Karte wird neu gelesen.
                gesperrteTuerme.remove(rauswurfTurm);
                oeffnenJeTurm.remove(rauswurfTurm);
                ohneZiel = 0;
                nichtsMehrFrei = false;
            }
        } else {
            o = schrittKrieg(screen, ctx, runs);
        }
        // Ben 19.-21.10. (Berichte U69U, T59W, HSMS: "No progress for 20 s, last: Start"): der Clan-Krieg meldete
        // Fortschritt nur ganz am Ende -- das Ansehen der 17 Tuerme (je gut 2 s) galt deshalb als Stillstand.
        mitFortschritt(o, runs);
        // Code-Review 23.10.: auch nach einem Abbruch (FATAL) das Set zuruecksetzen, nicht nur nach FINISHED.
        if ((o == Outcome.FINISHED || o == Outcome.FATAL) && heldNachher != null && !heldNachher.fertig()) {
            kriegFertig = true;
            endeNachSet = o;
            return Outcome.ACTED;
        }
        return o;
    }

    /** Bericht WTK9: Die Karte liest je Turm den Namen unter der Zahl (einmal je Turm, je 1-8 s) -- das ist noch kein Stillstand. */
    @Override public long haengerBerichtNachS() { return 45; }

    /** Jeder Klick im Clan-Krieg (auch Helden-Set) ist Fortschritt fuer die Stillstands-Pruefung. */
    private static Outcome mitFortschritt(Outcome o, RunTracker runs) {
        if (o == Outcome.ACTED) runs.progress(Sprache.t("Clan war: deploying / checking towers", "Clan-Krieg: aufstellen / Tuerme ansehen"));
        return o;
    }

    private Outcome schrittKrieg(Mat screen, BotContext ctx, RunTracker runs) {
        // Code-Review 23.10.: Einen Rauswurf direkt nach "Deploy" faengt meist schon der Motor ab (alle 2 s vor step()) --
        // dann sah der Clan-Krieg ihn nie, und die Aufstellung zaehlte trotzdem als gesetzt.
        if (geradeGesetzt && Math.max(Stoerfenster.updateGedruecktUm(), Stoerfenster.rauswurfUm()) > geradeGesetztUm) {
            rauswurfNachDeploy(Sprache.t("handled by the engine", "vom Motor behandelt"));
        }

        // AFK-Rauswurf ("are you still here?"): der gruene Update-Knopf laedt
        // das Spiel neu und bringt zurueck zur Burg. Hat Vorrang.
        // Waehrend einer Werbung (Nahrung per Video) wird NICHTS gedrueckt -- auch nicht "Update".
        if (!nahrung.werbungLaeuft()
                && Stoerfenster.updateDruecken(screen, ctx, "Clan-Krieg")) {
            rauswurfNachDeploy("Update");
            ctx.sleep(20000);
            return Outcome.ACTED;
        }
        // Spieler selbst im Spiel: VOR allem anderen anhalten, sonst tippt der Schritt auf die Meldung (Hauptbot 62b1968).
        // Waehrend einer Werbung (Nahrung per Video) NICHT: anderesGeraet() tippt ein "Try again" in die Bildmitte, und
        // ein gruener Werbeknopf dort fuehrt sonst in die Werbung (Code-Pruefung 16.10.).
        if (!nahrung.werbungLaeuft()) {
            switch (Stoerfenster.anderesGeraet(screen, ctx, "Clan-Krieg")) {
                case ANHALTEN -> { rauswurfNachDeploy("anderes Geraet"); return Outcome.PAUSE; }
                case GEKLICKT -> { rauswurfNachDeploy("Try again"); return Outcome.ACTED; }
                case NICHTS   -> { }
            }
        }

        // Erst einmal durchatmen. Siehe BEDENKZEIT_MS.
        ctx.sleep(tempo.ms(BEDENKZEIT_MS));
        // Pruefer 29.10.: Das Bild stammte von VOR der Bedenkzeit (350 ms x Rechnertempo, Server bis 5x) -- entschieden
        // und geklickt wurde auf einem alten Bild, und sleepUntilChanged() verglich mit genau diesem und kam sofort
        // zurueck. Darum jetzt frisch aufnehmen und alles Weitere darauf entscheiden.
        Mat frisch = ctx.refresh();
        if (frisch == null) return Outcome.WAITING;   // Spielfenster gerade nicht greifbar -- naechstes Bild
        return schrittNachBedenkzeit(frisch, ctx, runs);
    }

    private Outcome schrittNachBedenkzeit(Mat screen, BotContext ctx, RunTracker runs) {
        // Nahrungskisten (Autor, 27.09.): laeuft der Gang durch die Tasche,
        // gehoert ihm jeder Schritt, bis er wieder in der Burg steht.
        if (nahrung.aktiv()) {
            if (nahrung.schritt(screen, ctx)) return Outcome.ACTED;
            unbekannteBilder = 0;
            return Outcome.ACTED;   // frisches Bild, dann weiter zum Clan-Krieg
        }

        // ---- 0b. Bearbeitungsmodus, den der Bot nicht wollte -------------
        // Hier laeuft nie der Titanen-Rauswurf (der hat seinen eigenen Schritt) -- ein offener "Editing mode" ist also
        // ein Versehen. Siehe bearbeitungsmodusOffen().
        if (bearbeitungsmodusOffen(n -> ctx.visibleIfKnown(screen, n))) {
            if (bearbeitungsExits < 5 && klickVorlageWennBekannt(screen, ctx, TitanRauswurf.T_EXIT, Sprache.t(
                    "Editing mode is open, the bot did not want it -- Exit (nothing is saved)",
                    "Editing mode ist offen, das wollte der Bot nicht -- Exit (es wird nichts gespeichert)"))) {
                bearbeitungsExits++;
                unbekannteBilder = 0;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        } else {
            bearbeitungsExits = 0;
        }
        // Pruefer 29.10.: nach dem Bearbeitungsmodus kommt "Warning! ... Got it". Dafuer gibt es noch KEINE Vorlage --
        // sobald Autor sie als cw_got_it aufnimmt, wird das Fenster hier wie ein Stoerfenster weggeklickt (ohne die
        // Vorlage passiert nichts; sonst blieb der Bot vor dem "Warning" stehen, siehe 4.).
        if (klickVorlageWennBekannt(screen, ctx, T_GOT_IT, Sprache.t("Warning after editing mode -- Got it",
                "Warnung nach dem Editing mode -- Got it"))) {
            unbekannteBilder = 0;
            ctx.sleepUntilChanged(1500);
            return Outcome.ACTED;
        }

        // Pruefer 29.10.: Fenster des eben angetippten Turms ist da -- erst jetzt zaehlt das Oeffnen, und die Karte ist
        // wieder frei fuer den naechsten Tipp.
        if (turmKlickSchluessel != null && (ctx.visible(screen, T_DEPLOY_SQUAD) || ctx.visible(screen, T_SQUADS_LABEL)
                || ctx.visibleIfKnown(screen, T_DEPLOY_GREY) || truppenfensterOffen(screen, ctx)
                || ersteSichtbare(screen, ctx, T_ERROR_RED) != null)) {
            turmFensterGesehen();
        }

        // ---- 1. Sind wir fertig? ---------------------------------------
        //
        // Gesehen zaehlt, nicht geklickt: erst wenn beide Schilder oben
        // stehen, ist der Auftrag erledigt. Reicht das Essen nicht, gilt
        // der normale Turm als erledigt -- das ist Autors Entscheidung,
        // nicht ein Fehler des Bots.
        //
        // ZWEI SCHILDER "YOUR SQUAD" HEISSEN FERTIG.
        //
        // Autors Regel, in seinen Worten: "wenn er dieses schild zwei mal
        // sieht, heißt das im klartext das mein team schon gesetzt ist."
        //
        // Das ist die einfachste und sicherste Bedingung, die es hier gibt:
        // kein Sortenraten, keine gemerkten Klicks, keine geschaetzten
        // Abstaende -- nur zaehlen. Mehr als zwei Plaetze hat Autor auf der
        // ganzen Karte nicht, zwei Schilder sind also gleichbedeutend mit
        // "beide Truppen stehen".
        //
        // Die Sortenerkennung darunter bleibt trotzdem. Sie beantwortet die
        // andere Frage: WAS fehlt noch, solange erst ein Schild da ist.
        // ABER NICHT, SOLANGE NOCH EINE NACHFRAGE OFFEN STEHT.
        //
        // Diesen Fehler habe ich mir mit dem Mitzaehlen selbst eingebaut.
        // Beim normalen Turm kommt nach "Deploy" noch die Frage "Are you
        // sure you want to set out with an incomplete squad?" -- und erst
        // das "Yes" darauf setzt den Trupp wirklich. Der Zaehler stand aber
        // schon beim Klick auf "Deploy" auf zwei, der Bot hielt sich fuer
        // fertig, drueckte das X und ging.
        //
        // Autor hat es gesehen: "beim normalen trupp muss er nachdem er auf
        // confirm gedrückt hat meistens noch bei diesem screen auf yes
        // drücken bevor er auf das X oben drückt."
        //
        // Solange also ein Warnfenster im Bild ist, ist gar nichts fertig.
        // Der Ablauf weiter unten beantwortet es, und im naechsten Bild
        // greift die Fertig-Regel dann ganz normal.
        boolean nachfrageOffen = ctx.visible(screen, T_WARNING);

        // Code-Review 23.10.: Steht nach dem letzten Deploy das Turmfenster noch offen, zuerst unten (7.) nachsehen, ob der
        // Trupp wirklich drin steht -- sonst lief die Kontrolle fuer den zweiten Trupp nie.
        // Pruefer 29.10.: Nach dem zweiten Confirm zeigte das naechste Bild auf dem langsamen Server noch das
        // Truppenfenster (oder einen Uebergang) -- dann kamen X und "fertig", der Titan fehlte. Darum gar keine
        // Fertig-Regel, solange die letzte Aufstellung noch nicht im Turm gesehen ist oder das Truppenfenster offen steht.
        boolean kontrolleOffen = geradeGesetzt || truppenfensterOffen(screen, ctx);
        int gesehen = zaehler.gesehenAnzahl();
        if (!nachfrageOffen && !kontrolleOffen
                && (gesehen >= 2
                    || nichtsMehrFrei
                    // Autor 17.10.: zwei Schilder reichen nicht mehr -- erst pruefen, ob sie an der richtigen Stelle stehen.
                    || (verifikationFertig && (zuletztSchilder >= 2 || ((normalGesetzt || essenReichtNicht) && titanErledigt()))))) {

            // ZWEI KLICKS BIS ZURUECK IN DIE BURG, EINER JE SCHRITT.
            //
            // "Back" fuehrt naemlich NICHT in die Burg, sondern auf die
            // Uebersichtsseite des Clan-Kriegs -- die mit dem Banner "Clan
            // War", dem gruenen "Go to map" und der Belohnungsleiste. Erst
            // das rote X oben rechts schliesst die.
            //
            // Autor: "wenn der bot nachdem er beide teams gesetzt hat auf
            // back drückt um zurück in die burg zu kommen, kommt er immer
            // erst in diesen screen, dabei bitte auf das obere X drücken."
            //
            // Ohne diesen zweiten Klick blieb das Spiel auf der Uebersicht
            // stehen, und der naechste Modus musste sich von dort erst
            // wieder herausfinden.
            // JEDER SCHRITT DARF WARTEN, BIS SEIN KNOPF DA IST.
            //
            // Hier lag der Fehler vom 5. September um 04:44. Die drei
            // Schritte hakten sich selbst ab, BEVOR geklickt wurde -- war
            // der Knopf gerade nicht zu sehen, rutschte der Bot durch alle
            // drei in einem einzigen Bild und meldete "fertig". Und genau
            // so war es: Die Fertig-Regel greift in dem Moment, in dem die
            // zweite Aufstellung bestaetigt ist, und da steht das
            // Turmfenster noch offen. Weder "Back" noch das rote X waren
            // im Bild.
            //
            // Ergebnis: Das Spiel blieb auf der Kriegskarte stehen, die
            // Kathedrale fing an und erkannte nichts mehr.
            //
            //     04:44:26  Start: Dark Cathedral - 200 Durchlaeufe
            //     04:44:40  Seit 12 Bildern nichts erkannt.
            //
            // Jetzt bekommt jeder Schritt bis zu ABSCHLUSS_GEDULD Bilder
            // Zeit, seinen Knopf zu finden. Erst wenn er da war und gedrueckt
            // wurde -- oder die Geduld abgelaufen ist -- geht es weiter.
            //
            //   0: ein noch offenes Turmfenster zumachen
            //   1: "Back" -- von der Karte auf die Uebersicht
            //   2: das rote X -- von der Uebersicht in die Burg
            //   3: fertig
            if (abschlussSchritt == 0) {
                if (klickVorlage(screen, ctx, T_CLOSE_X, Sprache.t("Close the tower window (wrap-up)", "Turmfenster zumachen (Abschluss)"))) {
                    naechsterAbschlussSchritt();
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
                // Kein Turmfenster offen? Dann ist dieser Schritt erledigt.
                if (!ctx.visible(screen, T_DEPLOY_SQUAD)
                        && !ctx.visibleIfKnown(screen, T_SQUADS_LABEL)) {
                    naechsterAbschlussSchritt();
                } else if (abschlussWarten()) {
                    return Outcome.WAITING;
                }
            }
            if (abschlussSchritt == 1) {
                if (klickVorlage(screen, ctx, T_BACK, Sprache.t("Close the war map", "Kriegskarte schliessen"))) {
                    naechsterAbschlussSchritt();
                    ctx.sleepUntilChanged(2500);
                    return Outcome.ACTED;
                }
                if (abschlussWarten()) return Outcome.WAITING;
            }
            if (abschlussSchritt == 2) {
                for (String x : List.of(T_CLOSE_X, "close_x")) {
                    if (klickVorlageWennBekannt(screen, ctx, x,
                            Sprache.t("Close the clan war overview (" + x + ")", "Clan-Krieg-Uebersicht schliessen (" + x + ")"))) {
                        naechsterAbschlussSchritt();
                        ctx.sleepUntilChanged(2500);
                        return Outcome.ACTED;
                    }
                }
                if (abschlussWarten()) return Outcome.WAITING;
            }

            String grund;
            if (nichtsMehrFrei && gesehen < 2 && zuletztSchilder < 2) {
                grund = Sprache.t("no free tower left -- normal tower " + (normalGesetzt ? "deployed" : "no")
                        + ", outpost " + (titanGesetzt ? "deployed" : "no"),
                        "kein freier Turm mehr -- normaler Turm " + (normalGesetzt ? "gesetzt" : "nein")
                        + ", Aussenposten " + (titanGesetzt ? "gesetzt" : "nein"));
            } else if (gesehen >= 2) {
                grund = Sprache.t("both deployments were confirmed and seen in the tower",
                        "beide Aufstellungen wurden bestaetigt und im Turm gesehen");
            } else if (zuletztSchilder >= 2) {
                grund = Sprache.t("\"Your squad\" is on the map " + zuletztSchilder + " times",
                        "\"Your squad\" steht " + zuletztSchilder + " Mal auf der Karte");
            } else {
                grund = Sprache.t("normal tower "
                        + (normalGesetzt ? "deployed" : "skipped (not enough food)")
                        + (titanGesetzt ? ", outpost deployed" : ", no outpost possible"),
                        "normaler Turm "
                        + (normalGesetzt ? "gesetzt" : "ausgelassen (Essen reichte nicht)")
                        + (titanGesetzt ? ", Aussenposten gesetzt" : ", kein Aussenposten moeglich"));
            }
            Log.info("Clan-Krieg", Sprache.t("Deployment done -- ", "Aufstellung fertig -- ") + grund + ".");

            // Und fuer dieses Kriegsfenster ist Schluss -- auch ueber einen
            // Neustart hinweg. Das naechste Mal wird zur naechsten
            // Kriegszeit nachgesehen, also um 09:01.
            boolean beide = gesehen >= 2 || zuletztSchilder >= 2;
            Kriegsplan.aufstellungErledigt(beide || normalGesetzt, beide || titanGesetzt);

            // Konrad 20.11. (Bericht UJQ8): beide Trupps standen schon, nichts umzusetzen -- trotzdem hiess es
            // "Fertig: 0 Durchlaeufe" (in der neuen Schlusszeile sogar "0 von 1, dann abgebrochen"), und es sah aus, als
            // waere der Krieg nicht gesetzt. Steht mindestens ein Trupp, ist der Auftrag erledigt: ein Durchlauf.
            if (beide || normalGesetzt || titanGesetzt) runs.completeRun();
            runs.progress(Sprache.t("Deployment done", "Aufstellung fertig"));
            tempoRundeFertig();
            return Outcome.FINISHED;
        }

        // ---- 2. Fenster, die dazwischenfunken (anderes Geraet: ganz oben im Schritt) ----
        if (Stoerfenster.belohnungWegklicken(screen, ctx, "Clan-Krieg")) {
            return Outcome.ACTED;
        }

        // ---- 2b. Ein vorgemerktes Turmfenster zumachen ------------------
        //
        // Eigener Schritt, damit nie zwei Klicks aus einem step() kommen.
        // Das Kauf- oder Warnfenster ist jetzt weg, das rote X liegt frei.
        if (turmSchliessen) {
            turmSchliessen = false;
            if (klickVorlage(screen, ctx, T_CLOSE_X, Sprache.t("Close the tower window", "Turmfenster schliessen"))) {
                return Outcome.ACTED;
            }
            // Kein X da: das Fenster hat sich von selbst geschlossen.
            Log.debug("Clan-Krieg", "Kein rotes X zu sehen -- das Turmfenster ist "
                    + "offenbar schon zu.");
        }

        // ---- 3. Auf keinen Fall kaufen ---------------------------------
        //
        // Diese Abfrage steht bewusst GANZ WEIT VORNE. Das Kauffenster
        // deckt alles andere zu, und ein Klick daneben landet schnell auf
        // dem gruenen "Yes" -- das waere Autors Diamantenvorrat.
        if (ctx.visible(screen, T_BUY)) {
            essenReichtNicht = true;
            if (klickVorlage(screen, ctx, T_BUY_NO,
                    Sprache.t("Apples are NOT bought -- \"No\"", "Aepfel werden NICHT gekauft -- \"No\""))) {
                Log.warn("Clan-Krieg", Sprache.t("Not enough food for the normal tower. "
                        + "Nothing is bought. Deploying the titan squad costs "
                        + "nothing, so it is deployed anyway.",
                        "Das Essen reicht fuer den normalen Turm nicht. "
                        + "Es wird nichts gekauft. Der Titanen-Trupp kostet beim Setzen "
                        + "nichts und wird trotzdem gesetzt."));
                merkeGesperrt(Sprache.t("not enough food", "Essen reicht nicht"));
                schliesseSpaeter();
                return Outcome.ACTED;
            }
            // Fenster ist da, aber das rote "No" wurde nicht gefunden.
            // Lieber gar nichts klicken als das Falsche.
            warnung(Sprache.t("The window \"Buy the missing resources?\" is open, "
                    + "but the red \"No\" button was not found. Nothing is "
                    + "clicked -- the template cw_buy_no is missing or does not match.",
                    "Das Fenster \"Buy the missing resources?\" ist offen, "
                    + "aber der rote Knopf \"No\" wurde nicht gefunden. Es wird nichts "
                    + "geklickt -- die Vorlage cw_buy_no fehlt oder passt nicht."));
            return Outcome.WAITING;
        }

        // ---- 4. "Unvollstaendiger Trupp?" -- ja, losziehen -------------
        //
        // ZWEI FENSTER MIT DERSELBEN UEBERSCHRIFT. Ueber beiden steht
        // "Warning", und beide haben ein gruenes "Yes" und ein rotes "No":
        //
        //   A) "Buy the missing resources?"     -> NIEMALS Yes (Diamanten)
        //   B) "Are you sure you want to set out with an incomplete squad?"
        //                                       -> IMMER Yes
        //
        // Am 5. September habe ich B fuer A gehalten und den Yes-Klick
        // vorsichtshalber ganz herausgenommen. Damit war der normale Turm
        // gar nicht mehr zu fuellen -- Autors Trupp hat 6 von 8 Plaetzen
        // belegt, also kommt B bei JEDEM Aufstellen. Er hat es aufgeklaert:
        // "der screen wo du dachtest das meine äpfel fehlen ist eigentlich
        // ein warn fenster das der bot ein unvollständiges team einsetzen
        // will, bei diesem speziellen screen bitte immer auf yes drücken."
        //
        // Unterschieden wird an der Frage selbst, nicht am Wort "Warning":
        // A hat mit cw_buy_resources einen ganzen, unverwechselbaren Satz
        // und wird eine Ebene weiter oben abgefangen. Bleibt hier also B.
        //
        // Zusaetzlich, als Guertel zum Hosentraeger: Vor dem Yes wird noch
        // einmal ausdruecklich geprueft, dass die Kauffrage NICHT im Bild
        // ist. Lieber eine Pruefung zu viel als ein Ja auf die falsche
        // Frage.
        if (ctx.visible(screen, T_WARNING)) {
            boolean kauffrage = ctx.visible(screen, T_BUY);
            boolean unvollstaendig = ctx.visibleIfKnown(screen, T_INCOMPLETE);

            // Code-Pruefung 15.10. (Hauptbot): Bisher reichte "Kauffrage NICHT erkannt" fuer das Yes. War cw_buy_resources
            // nur gerade nicht zu sehen (Aufklapp-Animation, andere Stelle), kaufte das Yes Aepfel mit Diamanten. Jetzt muss
            // der Text ueber dem Yes ausdruecklich vom unvollstaendigen Trupp sprechen und darf nichts vom Kaufen sagen.
            Optional<Matcher.Hit> ja = kauffrage ? Optional.empty() : ctx.find(screen, T_YES);
            String frage = ja.isPresent() ? frageText(ctx, ja.get()) : "";
            boolean umsetzFrage = umsetzenGeklickt && moveFrage(frage);
            if (ja.isPresent() && !truppFrage(frage) && !umsetzFrage) {
                warnung(Sprache.t("Warning window with Yes, but the text does not clearly say \"incomplete squad\" (\""
                        + frage + "\") -- NO Yes.",
                        "Warnfenster mit Yes, der Text sagt aber nicht sicher \"unvollstaendiger Trupp\" (\""
                        + frage + "\") -- KEIN Yes."));
                kauffrage = true;   // wie eine Kauffrage behandeln: No, sonst warten
            }
            // Autor 22.10. abends: "Dann kommt diese Warning-Anzeige. Drueckst du bitte auf No." -- freie Plaetze mit den
            // staerksten Einheiten auffuellen, die noch nicht im Trupp stehen (TruppAuffuellen), dann wieder Deploy. Erst
            // wenn das in diesem Turm schon versucht wurde, wie bisher Yes.
            if (!kauffrage && ja.isPresent() && !umsetzFrage && !auffuellen.versucht()) {
                // Code-Review 23.10.: Der erste Deploy-Klick war schon gezaehlt -- nach No + Auffuellen + Deploy zaehlte
                // derselbe Turm doppelt (bestaetigt 2 = "beide gesetzt"), und der zweite Trupp wurde nie gesetzt.
                aufstellungZuruecknehmen();
                auffuellen.starten();
                testFoto(ctx, "warnung");
                Optional<Matcher.Hit> nein = ctx.matcher().has(TruppAuffuellen.T_NEIN)
                        ? ctx.matcher().find(screen, TruppAuffuellen.T_NEIN) : Optional.empty();
                if (nein.isPresent()) {
                    klickTreffer(screen, ctx, nein.get(), Sprache.t("Incomplete squad -- No, fill the free slots first",
                            "Unvollstaendiger Trupp -- No, erst die freien Plaetze auffuellen"));
                } else {
                    // "No" steht rechts neben "Yes" (Autors Bild: Yes 478, No 682 bei 1159 Punkten Breite).
                    int x = ja.get().centerX() + (int) Math.round(ctx.width() * 204 / 1159.0);
                    klickPunkt(screen, ctx, x, ja.get().centerY(), "No", Sprache.t("Incomplete squad -- No (next to Yes)  [",
                            "Unvollstaendiger Trupp -- No (neben Yes)  [") + x + "," + ja.get().centerY() + "]");
                }
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            if (!kauffrage && ja.isPresent()) {
                if (!klickTreffer(screen, ctx, ja.get(), Sprache.t("Incomplete squad -- set out anyway (Yes)",
                              "Unvollstaendiger Trupp -- trotzdem losziehen (Yes)"))) return Outcome.WAITING;
                Log.info("Clan-Krieg", Sprache.t("Answered the \"incomplete squad\" prompt with Yes"
                        + (unvollstaendig ? " (text recognised)."
                                          : " (no purchase window in the picture).")
                        + " The squad sets out as it is.",
                        "Nachfrage \"unvollstaendiger Trupp\" mit Ja "
                        + "beantwortet" + (unvollstaendig ? " (Text erkannt)."
                                                          : " (kein Kauffenster im Bild).")
                        + " Der Trupp zieht so los, wie er zusammengestellt ist."));
                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }

            // Kauffrage im Bild, aber Schritt 3 hat sie nicht erwischt --
            // dann wird hier auf keinen Fall bestaetigt.
            if (kauffrage) {
                Log.warn("Clan-Krieg", Sprache.t("A warning window with the purchase question is open. It "
                        + "is NOT confirmed.",
                        "Ein Warnfenster mit der Kauffrage ist offen. Es "
                        + "wird NICHT bestaetigt."));
                if (klickVorlage(screen, ctx, T_BUY_NO, Sprache.t("Decline the purchase (No)", "Kauf ablehnen (No)"))) {
                    essenReichtNicht = true;
                    merkeGesperrt(Sprache.t("not enough food", "Essen reicht nicht"));
                    schliesseSpaeter();
                    return Outcome.ACTED;
                }
            }
            return Outcome.WAITING;
        }

        // ---- 4d. Umsetzen, zweiter Schritt: "Move" in "Create squad" (Autors Bild 17.10.) ----
        if (umsetzenGeklickt) {
            Optional<java.awt.Point> move = moveKnopf(ctx);
            if (move.isPresent()) {
                if (!klickPunkt(screen, ctx, move.get().x, move.get().y, "Move", Sprache.t("Confirm moving the squad (Move in \"Create squad\")  [",
                        "Umsetzen bestaetigen (Move in \"Create squad\")  [") + move.get().x + "," + move.get().y + "]")) {
                    return Outcome.WAITING;
                }
                ctx.sleepUntilChanged(2500);
                return Outcome.ACTED;
            }
        }

        // ---- 4c. Umsetzen: "Move squad" (Autor 17.10.) ---------------
        if (umsetzenTurm != null && (ctx.visible(screen, T_DEPLOY_SQUAD) || ctx.visible(screen, T_SQUADS_LABEL)
                || ctx.visibleIfKnown(screen, T_MOVE))) {
            unbekannteBilder = 0;
            if (!umsetzenGeklickt) {
                if (klickVorlageWennBekannt(screen, ctx, T_MOVE, Sprache.t("Move the squad here (Move squad)", "Trupp hierher umsetzen (Move squad)"))) {
                    umsetzenGeklickt = true;
                    ctx.sleepUntilChanged(2500);
                    return Outcome.ACTED;
                }
                Log.info("Clan-Krieg", Sprache.t("No \"Move squad\" in this tower -- the squad stays where it is.",
                        "Kein \"Move squad\" in diesem Turm -- der Trupp bleibt, wo er ist."));
            } else {
                Log.info("Clan-Krieg", Sprache.t("Squad moved (\"Move squad\" pressed, no purchase question).",
                        "Trupp umgesetzt (\"Move squad\" gedrueckt, keine Kauffrage)."));
                TurmInfo alt = erkundet.get(umsetzenTurm.schluessel());
                if (alt != null) erkundet.put(umsetzenTurm.schluessel(), new TurmInfo(alt.titel(), alt.namen(), alt.ruhm(), true, false));
            }
            umsetzenTurm = null;
            umsetzenGeklickt = false;
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // ---- 4a. Erkundung: Turm nur zum Lesen offen (Autor 17.10.) ----
        if (erkundenTurm != null && (ctx.visible(screen, T_DEPLOY_SQUAD) || ctx.visible(screen, T_SQUADS_LABEL)
                || ctx.visibleIfKnown(screen, T_DEPLOY_GREY))) {
            unbekannteBilder = 0;
            if (!erkundenGesehen) {   // das Fenster faehrt noch aus -- erst im naechsten Bild lesen
                erkundenGesehen = true;
                ctx.sleep(tempo.ms(250));
                return Outcome.WAITING;
            }
            TurmInfo info = turmLesen(ctx, erkundenTurm.aussenposten(), screen);
            // Autor 23.10. abends: "Struggle road" ist ein normaler Stuetzpunkt, das Bild hielt ihn fuer einen Outpost --
            // Autors Name stand dort, der normale Trupp suchte aber nie dort. Der Titel entscheidet ("... Outpost").
            String tk = info.titel().toLowerCase(java.util.Locale.ROOT);
            if (tk.replaceAll("[^a-z]", "").length() >= 5) {
                boolean art = titanenName(tk);   // Pruefer 29.10.: auch "Temple of the ... Elements"
                if (art != erkundenTurm.aussenposten()) {
                    artKorrektur.add(new int[]{erkundenTurm.x(), erkundenTurm.y(), art ? 1 : 0});
                    Log.info("Clan-Krieg", Sprache.t("\"" + info.titel() + "\" is " + (art ? "an outpost" : "a normal tower")
                            + " -- the picture said otherwise, corrected.",
                            "\"" + info.titel() + "\" ist " + (art ? "ein Outpost" : "ein normaler Stuetzpunkt")
                            + " -- das Bild sagte etwas anderes, korrigiert."));
                    erkundenTurm = new Turm(erkundenTurm.x(), erkundenTurm.y(), art, erkundenTurm.leer(), erkundenTurm.drin(), erkundenTurm.platz());
                }
            }
            erkundet.put(erkundenTurm.schluessel(), info);
            if (letzterTurmMitStufe) {
                stufenTuerme.add(erkundenTurm.schluessel());
                Log.info("Clan-Krieg", Sprache.t("Tower \"" + info.titel() + "\" is meant for this throne room level (\"has to be at level\").",
                        "Turm \"" + info.titel() + "\" ist fuer diese Thronsaal-Stufe gedacht (\"has to be at level\")."));
            }
            Log.info("Clan-Krieg", Sprache.t("Looked at " + (erkundenTurm.aussenposten() ? "outpost" : "tower") + " \"" + info.titel()
                    + "\": names \"" + info.namen() + "\", glory " + info.ruhm() + ", squads " + erkundenTurm.drin() + "/" + erkundenTurm.platz()
                    + (info.meinTrupp() ? ", MY squad is in it" : "")
                    + (info.verschieben() ? ", \"Move squad\"" : "") + ".",
                    "Angesehen: " + (erkundenTurm.aussenposten() ? "Aussenposten" : "Turm") + " \"" + info.titel()
                    + "\": Namen \"" + info.namen() + "\", Ruhm " + info.ruhm() + (info.meinTrupp() ? ", MEIN Trupp steht drin" : "")
                    + (info.verschieben() ? ", \"Move squad\"" : "") + "."));
            // Autor/Ben 19.10.: "Echo of the war" (ein NORMALER Turm) galt als Aussenposten; dort stand "Move squad", weil der
            // normale Trupp schon stand -- der Bot schloss "Titanen-Trupp steht schon" und setzte die Titanen nie. Bei einem
            // Aussenposten zaehlt deshalb nur "MEIN Trupp steht drin", nicht "Move squad".
            if (info.meinTrupp() || (info.verschieben() && !erkundenTurm.aussenposten())) {
                boolean titan = erkundenTurm.aussenposten();
                String wo = info.meinTrupp() ? "\"" + info.titel() + "\"" : null;
                if (titan && !titanGesetzt) {
                    titanGesetzt = true;
                    Log.info("Clan-Krieg", Sprache.t("Your titan squad already stands " + (wo != null ? "in " + wo
                            : "somewhere (\"Move squad\" instead of \"Deploy squad\")") + " -- nothing to deploy for it.",
                            "Dein Titanen-Trupp steht schon " + (wo != null ? "in " + wo
                            : "woanders (\"Move squad\" statt \"Deploy squad\")") + " -- fuer ihn ist nichts zu tun."));
                } else if (!titan && !normalGesetzt) {
                    normalGesetzt = true;
                    Log.info("Clan-Krieg", Sprache.t("Your normal squad already stands " + (wo != null ? "in " + wo
                            : "somewhere (\"Move squad\" instead of \"Deploy squad\")") + " -- nothing to deploy for it.",
                            "Dein normaler Trupp steht schon " + (wo != null ? "in " + wo
                            : "woanders (\"Move squad\" statt \"Deploy squad\")") + " -- fuer ihn ist nichts zu tun."));
                }
            }
            // Autor 18.10.: "Wenn der Bot meinen Namen findet, muss er die anderen Stuetzpunkte nicht mehr weiter anschauen.
            // Er soll einfach mein Team da hinsetzen, wo der Name stand." -- die restlichen gleicher Art nicht mehr ansehen.
            String ichName = MeinTeam.spielername();
            if (ichName != null && !ichName.isBlank() && enthaeltNamen(info.namen(), ichName)) {
                boolean art = erkundenTurm.aussenposten();
                if (info.meinTrupp() && richtigGesehen.add(art ? Sorte.AUSSENPOSTEN : Sorte.NORMAL)) {
                    Log.info("Clan-Krieg", Sprache.t("Your " + (art ? "titan squad" : "squad") + " already stands where your name is -- no other "
                                    + (art ? "outposts" : "towers") + " are opened.",
                            "Dein " + (art ? "Titanen-Trupp" : "Trupp") + " steht schon bei deinem Namen -- keine weiteren "
                                    + (art ? "Aussenposten" : "Tuerme") + " werden geoeffnet."));
                }
                int vorher = erkundenListe.size();
                // Zweitkonto-Bot (Drittkonto, 25.10.): bei normalen Tuermen weiter ansehen -- ein Turm fuer die eigene
                // Thronsaal-Stufe geht vor dem Namen.
                boolean weiterSuchen = mehrereKonten && !art && !nurPruefen;
                if (!weiterSuchen) {
                    erkundenListe.removeIf(tt -> tt.aussenposten() == art);
                    // Code-Review 23.10.: die Liste wird je Kartenbild neu gebaut -- ohne Merker sah er die anderen doch an.
                    nameGefunden.add(art ? Sorte.AUSSENPOSTEN : Sorte.NORMAL);
                }
                if (erkundenListe.size() < vorher) {
                    Log.info("Clan-Krieg", Sprache.t("Own name found -- the other " + (art ? "outposts" : "towers") + " are not looked at.",
                            "Eigener Name gefunden -- die anderen " + (art ? "Aussenposten" : "Tuerme") + " werden nicht mehr angesehen."));
                }
            }
            erkundenTurm = null;
            erkundenGesehen = false;
            // Tempo (Autor 17.10.: "schau dir die einzelnen Stuetzpunkte schneller an"): Fenster gleich zu und -- ist es
            // sicher weg -- sofort den naechsten Turm der Liste oeffnen, ohne die ganze Karte neu auszuwerten (vorher ~4 s
            // je Turm). Geht das Fenster nicht sicher zu, wie bisher ueber den naechsten Schritt.
            erkundenListe.removeIf(tt -> erkundet.containsKey(tt.schluessel()));
            if (!erkundenListe.isEmpty() && erkundungsKlicks < MAX_ERKUNDUNG
                    && klickVorlage(screen, ctx, T_CLOSE_X, Sprache.t("Close the tower window (looked at)", "Turmfenster schliessen (angesehen)"))) {
                if (ctx.warteBisWeg(1500, 0.8, T_SQUADS_LABEL)) {
                    ctx.sleep(tempo.ms(150));
                    Turm naechster = erkundenListe.remove(0);
                    erkundungsKlicks++;
                    erkundenTurm = naechster;
                    erkundenGesehen = false;
                    erkundenVersuche = 0;
                    return erkundenKlick(ctx, naechster);
                }
                return Outcome.ACTED;
            }
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // ---- 4b. Tuerme, die Autor nicht haben will --------------------
        //
        // Manche Tuerme stellen Bedingungen, die sein Trupp nicht erfuellt.
        // Auf seinem Bild steht es ausdruecklich darin: "Special
        // requirements for deploying a squad at waypoint: You can only
        // deploy fire titans." Da hilft kein Klicken -- der Turm ist fuer
        // ihn schlicht der falsche.
        //
        // Erkannt werden sie am NAMEN im Band oben, nicht an der Bedingung.
        // Der Name ist ein kurzer, ruhiger Schriftzug an immer derselben
        // Stelle; die Bedingung darunter ist ein langer Satz, der sich von
        // Turm zu Turm aendert.
        //
        // Jede Vorlage, deren Name mit cw_skip anfaengt, sperrt einen Turm.
        // So kann Autor spaeter weitere dazunehmen -- cw_skip_3, cw_skip_4
        // --, ohne dass am Code etwas geaendert werden muss.
        // Nach dem Spielernamen gewaehlt (oder einziger Tempel): dorthin trotz Sperrliste (Autor 17.10.).
        String gesperrterName = zielNachName || sperrlisteAufgehoben ? null : gesperrterTurmname(screen, ctx);
        if (gesperrterName != null) {
            Log.info("Clan-Krieg", Sprache.t("This tower is on the skip list ("
                    + gesperrterName + "). The squad goes elsewhere.",
                    "Dieser Turm steht auf der Sperrliste ("
                    + gesperrterName + "). Der Trupp kommt woanders hin."));
            merkeNamensGesperrt(Sprache.t("tower on the skip list: " + gesperrterName,
                                          "gesperrter Turm: " + gesperrterName));
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // ---- 5. Roter Kasten: dieser Turm geht nicht -------------------
        //
        // ES GIBT MEHRERE SAETZE. Aufgenommen war nur einer:
        //
        //     "Your Throne Room must not exceed level ..."   (zu hoch)
        //     "Your Throne Room has to be at level 10 ..."   (zu niedrig)
        //
        // Am 5. September stand der Bot vor "Trial Trail" mit dem zweiten
        // Satz, erkannte ihn nicht und drueckte dreimal ins Leere auf
        // ausgegraute Knoepfe. Autor hat es gesehen: "bei einem normalen
        // turm hast du die fehlermeldung unten im turm nicht bemerkt."
        //
        // Deshalb zaehlt jetzt jede Vorlage, die mit cw_error_red anfaengt
        // -- cw_error_red_2, cw_error_red_3 und so weiter. Neue Wortlaute
        // brauchen dann keine Codeaenderung mehr, nur eine Aufnahme.
        String roterKasten = ersteSichtbare(screen, ctx, T_ERROR_RED);
        if (roterKasten != null) {
            // Server 29.10. 20:24: der rote Hinweis kam im Turm mit Autors Namen -- waehrend der Bot versehentlich im
            // Bearbeitungsmodus stand. Der Turm blieb danach gesperrt, der Trupp kam woanders hin. Autor: "Der Bot soll
            // darauf besonders gut achten." Im Namens-Turm darum erst ein zweiter Blick (zu, neu oeffnen); erst wenn er
            // dann wieder rot ist, kommt der Trupp in einen anderen Turm (Autor: "freien Turm nehmen").
            if (zielNachName && offenePosition != null && rotImNamensturm.add(offenePosition.schluessel())) {
                warnung(Sprache.t("Red notice (" + roterKasten + ") in the tower with your name -- checked once more "
                                + "(closed and opened again) before another tower is taken.",
                        "Roter Hinweis (" + roterKasten + ") im Turm mit deinem Namen -- wird noch einmal geprueft "
                                + "(zu und neu oeffnen), bevor ein anderer Turm genommen wird."));
                testFoto(ctx, "rot-namensturm");
                schliesseSpaeter();
                return Outcome.ACTED;
            }
            if (zielNachName) {
                warnung(Sprache.t("The tower with your name really does not work (red notice twice) -- the squad goes "
                                + "into another tower.",
                        "Der Turm mit deinem Namen geht wirklich nicht (zweimal rot) -- der Trupp kommt in einen anderen Turm."));
                // Pruefer 29.10.: Die Erkundung hatte beim Namen aufgehoert -- die Tuerme dahinter waren ungelesen und
                // galten damit nicht als "fremd". So haette Autors Trupp in einem fuer einen Mitspieler reservierten
                // Turm landen koennen (Autor 23.10.: nie in einen fremden). Darum weiter ansehen, bevor ausgewichen wird.
                Sorte sorte = offenerTurm != null ? offenerTurm
                        : offenePosition != null ? (offenePosition.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) : null;
                if (sorte != null && nameGefunden.remove(sorte)) {
                    Log.info("Clan-Krieg", Sprache.t("The other " + (sorte == Sorte.AUSSENPOSTEN ? "outposts" : "towers")
                                    + " are looked at first (whose name is there?).",
                            "Die anderen " + (sorte == Sorte.AUSSENPOSTEN ? "Aussenposten" : "Tuerme")
                                    + " werden erst noch angesehen (wessen Name steht dort?)."));
                }
            }
            Log.info("Clan-Krieg", Sprache.t("Red notice in the tower (" + roterKasten + ") -- the "
                    + "throne room does not meet this tower's requirements. It is "
                    + "skipped.",
                    "Roter Hinweis im Turm (" + roterKasten + ") -- der "
                    + "Thronsaal passt nicht zu den Bedingungen dieses Turms. Er wird "
                    + "uebersprungen."));
            merkeGesperrt(Sprache.t("throne room does not match", "Thronsaal passt nicht"));
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // ---- 6. Truppenfenster ist offen -------------------------------
        if (truppenfensterOffen(screen, ctx)) {
            return truppeStellen(screen, ctx);
        }

        // ---- 7. Ein Turm ist offen -------------------------------------
        if (ctx.visible(screen, T_DEPLOY_SQUAD)) {
            unbekannteBilder = 0;
            // Live-Test 17.10., 15:54: nach "Yes" (unvollstaendiger Trupp) tippte der Bot noch einmal "Deploy squad".
            if (geradeGesetzt) {
                // Autor 22.10.: erst nachsehen, ob "Autor" wirklich mit Trupp im Turm steht (Rauswurf beim gleichzeitigen Setzen).
                Boolean da = eigenerTruppDa(screen, ctx);
                if (da == null) return Outcome.WAITING;   // noch einmal hinsehen
                geradeGesetzt = false;
                Log.info("Clan-Krieg", da ? Sprache.t("Squad is in this tower -- closing the tower window.",
                        "Trupp steht in diesem Turm -- Turmfenster wird geschlossen.")
                        : Sprache.t("Closing the tower window -- the deployment is checked again on the map.",
                        "Turmfenster wird geschlossen -- die Aufstellung wird auf der Karte noch einmal geprueft."));
                schliesseSpaeter();
                return Outcome.ACTED;
            }
            // Server 29.10., 20:24: Das Turmfenster war nur zum Ansehen offen und "zu" schon gedrueckt. Ein Bild spaeter
            // stand es noch da, der Bot drueckte "Deploy squad" -- da war es schon zu, und der Klick traf auf der Karte an
            // derselben Stelle "Editing mode". Im Bearbeitungsmodus hing er dann fest. "Deploy squad" also nur in einem
            // Turm, den er selbst zum Aufstellen geoeffnet hat; jedes andere Turmfenster wird zugemacht.
            if (offenerTurm == null) {
                Log.info("Clan-Krieg", Sprache.t("Tower window open, but no tower was opened for deploying -- it is closed, no \"Deploy squad\".",
                        "Turmfenster offen, aber kein Turm zum Aufstellen geoeffnet -- es wird zugemacht, kein \"Deploy squad\"."));
                turmSchliessen = true;
                ctx.sleepUntilChanged(800);
                return Outcome.ACTED;
            }
            // DREIMAL UND DANN GENUG.
            //
            // Manche Tuerme stellen Bedingungen -- "You can only deploy fire
            // titans" -- und dort passiert auf "Deploy squad" schlicht
            // nichts. Das Truppenfenster geht nie auf, der Knopf bleibt
            // stehen, und der Bot drueckt ihn bis in alle Ewigkeit:
            //
            //     04:00:17  Truppe aufstellen (Deploy squad)
            //     04:00:20  Truppe aufstellen (Deploy squad)
            //     04:00:23  Truppe aufstellen (Deploy squad)
            //     04:00:27  Truppe aufstellen (Deploy squad)
            //
            // Mit den Vorlagen cw_skip_1 und cw_skip_2 erkennt er solche
            // Tuerme am Namen und geht gar nicht erst hinein. Diese Sperre
            // hier greift auch OHNE sie -- sie braucht nichts als das
            // Ausbleiben einer Wirkung.
            if (deployVersuche >= MAX_DEPLOY_VERSUCHE) {
                Log.info("Clan-Krieg", Sprache.t("\"Deploy squad\" was pressed "
                        + deployVersuche + " times in this tower without the squad window "
                        + "opening. The tower probably has requirements the squad "
                        + "does not meet. It is skipped.",
                        "\"Deploy squad\" wurde in diesem Turm "
                        + deployVersuche + " Mal gedrueckt, ohne dass das Truppenfenster "
                        + "aufging. Der Turm stellt vermutlich Bedingungen, die der Trupp "
                        + "nicht erfuellt. Er wird uebersprungen."));
                merkeGesperrt(Sprache.t("Deploy squad had no effect", "Deploy squad ohne Wirkung"));
                schliesseSpaeter();
                deployVersuche = 0;
                return Outcome.ACTED;
            }
            if (offenerTurm == Sorte.AUSSENPOSTEN && !elementGeprueft && ctx.visible(screen, T_DEPLOY_SQUAD)) {
                elementGeprueft = true;
                String elementText = elementTextImBild(ctx);
                elementPflicht = elementPflicht(elementText);
                if (elementPflicht) Log.info("Clan-Krieg", Sprache.t("Outpost with an element requirement -- the titans go in with \"Deploy the best\".",
                        "Outpost mit Element-Pflicht -- die Titanen kommen mit \"Deploy the best\"."));
                // Autor 21.11.: verlangt der Turm EIN Element, soll spaeter vorher das Titanen-Elixier umgetauscht werden.
                // Der Klickweg fehlt noch (Fotoserie) -- jetzt nur melden; bei Schalter aus passiert nichts weiter.
                TitanElixier.Element verlangt = verlangtesElement(elementText);
                if (verlangt != null) {
                    String stand = TitanElixier.umtauschMoeglich() ? Sprache.t("on", "an")
                            : TitanElixier.schalterAn() ? Sprache.t("switch on, click path still missing", "Schalter an, Klickweg fehlt noch")
                            : Sprache.t("still off", "noch aus");
                    Log.info("Clan-Krieg", Sprache.t("Titan tower requires " + verlangt.anzeige() + " -- elixir exchange (" + stand + ")",
                            "Titanen-Turm verlangt " + verlangt.anzeige() + " -- Elixier-Umtausch (" + stand + ")"));
                } else if (elementPflicht) {
                    Log.info("Clan-Krieg", Sprache.t("Element requirement seen, but the element was not readable: \"" + elementText.strip() + "\"",
                            "Element-Pflicht gesehen, aber das Element war nicht lesbar: \"" + elementText.strip() + "\""));
                }
            }
            // Pruefer 29.10.: "Deploy squad" (586,204) liegt genau dort, wo auf der Karte "Editing mode" ist. Darum direkt
            // vor dem Klick noch ein frisches Bild: ist das Turmfenster inzwischen zu (oder "Editing mode" zu sehen), wird
            // NICHT geklickt, sondern im naechsten Bild neu entschieden.
            Mat vorDeploy = ctx.refresh();
            if (vorDeploy == null || !ctx.visible(vorDeploy, T_DEPLOY_SQUAD)
                    || ctx.visibleIfKnown(vorDeploy, TitanRauswurf.T_EDIT_MODE)) {
                Log.info("Clan-Krieg", Sprache.t("\"Deploy squad\" is no longer clearly visible in a fresh picture -- not clicked.",
                        "\"Deploy squad\" ist im frischen Bild nicht mehr sicher zu sehen -- nicht geklickt."));
                return Outcome.WAITING;
            }
            if (klickVorlage(vorDeploy, ctx, T_DEPLOY_SQUAD, Sprache.t("Deploy the squad (Deploy squad)", "Truppe aufstellen (Deploy squad)"))) {
                deploySquadGeklickt = System.currentTimeMillis();
                deployVersuche++;
                ruheGemeldet = false;
                // WARTEN, BIS DAS FENSTER WIRKLICH DA IST.
                //
                // Hier stand kein Warten. Der Motor nimmt das naechste Bild
                // schon nach zweihundert Millisekunden auf -- da faehrt das
                // Truppenfenster gerade erst aus. Der Bot erkannte es nicht,
                // fiel bis zur Kriegskarte durch und klickte den naechsten
                // Turm an. Damit war das halb offene Fenster wieder zu, und
                // das Ganze ging von vorn los:
                //
                //     03:04:17.442  Truppe aufstellen (Deploy squad)
                //     03:04:18.304  Normaler Turm oeffnen bei 109,442
                //     03:04:18.960  Truppe aufstellen (Deploy squad)
                //     03:04:19.979  Normaler Turm oeffnen bei 109,442
                //
                // Achtzehnmal in zwanzig Sekunden, bis Autor !pause tippte.
                ctx.sleepUntilChanged(2500);
                return Outcome.ACTED;
            }
        }
        if (ctx.visibleIfKnown(screen, T_DEPLOY_GREY)) {
            Log.info("Clan-Krieg", Sprache.t("\"Deploy squad\" is greyed out -- nothing works here. "
                    + "Tower is skipped.",
                    "\"Deploy squad\" ist ausgegraut -- hier geht nichts. "
                    + "Turm wird uebersprungen."));
            merkeGesperrt(Sprache.t("button greyed out", "Knopf ausgegraut"));
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // Ein Turm ist offen, aber KEIN Knopf laesst sich gebrauchen.
        //
        // Das ist der Fall, wenn der Turm voll ist und "Deploy squad" grau
        // dasteht -- und die Vorlage cw_deploy_grey fehlt. Ohne diesen
        // Zweig lief der Bot dann bis ans Ende von step() durch: die
        // Kriegskarte wurde nicht erkannt (wir stehen ja im Turm), das
        // Burgsymbol auch nicht, und nach fuenfundzwanzig Bildern gab er
        // mit FATAL auf -- wegen eines vollen Turms.
        //
        // "Number of squads:" steht in jedem Turmfenster. Daran erkennt er
        // sicher, dass er in einem steht, und macht es einfach zu.
        if (ctx.visible(screen, T_SQUADS_LABEL)) {
            // Gerade eben in diesen Turm gesetzt? Dann ist hier nichts mehr zu
            // tun -- zumachen. Bisher wischte der Bot danach noch zweimal
            // nach einem freien Platz (Autor, 27.09.: "das muss er nicht").
            if (geradeGesetzt) {
                // Autor 22.10.: erst nachsehen, ob "Autor" wirklich mit Trupp im Turm steht (Rauswurf beim gleichzeitigen Setzen).
                Boolean da = eigenerTruppDa(screen, ctx);
                if (da == null) return Outcome.WAITING;   // noch einmal hinsehen
                geradeGesetzt = false;
                Log.info("Clan-Krieg", da ? Sprache.t("Squad is in this tower -- closing the tower window.",
                        "Trupp steht in diesem Turm -- Turmfenster wird geschlossen.")
                        : Sprache.t("Closing the tower window -- the deployment is checked again on the map.",
                        "Turmfenster wird geschlossen -- die Aufstellung wird auf der Karte noch einmal geprueft."));
                schliesseSpaeter();
                return Outcome.ACTED;
            }
            // ERST NACH UNTEN SCROLLEN (Autor, 27.09., Bilder "Rage Outpost"):
            // "Number of squads: 3/4" -- ein Platz ist frei, aber "Deploy squad"
            // steht im leeren Feld UNTER den drei Trupps, ausserhalb des
            // sichtbaren Teils. Der Bot hielt den Turm deshalb fuer voll und
            // fand fuer die Titanen keinen Platz mehr (16:32, 36 Tuerme
            // angeklickt). Jetzt wird die Liste erst hochgeschoben.
            if (scrollVersuche < MAX_SCROLL_VERSUCHE) {
                scrollVersuche++;
                int x = ctx.width() / 2;
                Log.action("Wischen", Sprache.t("No \"Deploy squad\" in sight -- scrolling down "
                        + "in the tower, maybe there is room further down (attempt " + scrollVersuche + ").",
                        "Kein \"Deploy squad\" zu sehen -- im Turm nach unten "
                        + "scrollen, vielleicht ist unten noch Platz (" + scrollVersuche + ". Mal)."));
                ctx.input().drag(x, (int) (ctx.height() * 0.68), x, (int) (ctx.height() * 0.20));
                ctx.sleepUntilChanged(1200);
                return Outcome.ACTED;
            }
            Log.info("Clan-Krieg", Sprache.t("Tower window open, but even after scrolling " + scrollVersuche
                    + " times there is no usable button. "
                    + "The tower is probably full. It is skipped. "
                    + "(With the template cw_deploy_grey the bot would know for sure.)",
                    "Turmfenster offen, aber auch nach " + scrollVersuche
                    + " Mal Scrollen kein brauchbarer Knopf. "
                    + "Vermutlich ist der Turm voll. Wird uebersprungen. "
                    + "(Mit der Vorlage cw_deploy_grey wuesste der Bot es genauer.)"));
            merkeGesperrt(Sprache.t("no button in the tower", "kein Knopf im Turm"));
            schliesseSpaeter();
            return Outcome.ACTED;
        }

        // ---- 8. Die Kriegskarte ----------------------------------------
        // Pruefer 29.10.: eben einen Turm angetippt, sein Fenster ist noch nicht da -- nicht noch einmal tippen.
        if (turmFensterAbwarten()) {
            ctx.sleep(150);
            return Outcome.WAITING;
        }
        if (aufDerKarte(screen, ctx)) {
            // Pruefer 29.10.: Nach "Confirm" kam das Turmfenster nicht wieder, die Karte ist da. Erst eine Ruhe abwarten
            // (vielleicht kommt das Fenster noch), dann gilt: im Turm nicht gesehen -- auf der Karte (Schilder) pruefen.
            if (geradeGesetzt) {
                if (System.currentTimeMillis() - geradeGesetztUm < de.hustlebot.core.Rechnertempo.mal(NACH_DEPLOY_RUHE_MS)) {
                    ctx.sleep(200);
                    return Outcome.WAITING;
                }
                geradeGesetzt = false;
                Log.info("Clan-Krieg", Sprache.t("After Confirm the tower window did not come back -- the deployment is checked on the map.",
                        "Nach Confirm kam das Turmfenster nicht wieder -- die Aufstellung wird auf der Karte geprueft."));
            }
            wartetAufTurm = false;
            Outcome karte = kartenschritt(screen, ctx);
            // 23.10. (Bericht #H6LS): das gewollte Warten ist begrenzt (OHNE_ZIEL_GEDULD_MS) und zaehlt
            // als Fortschritt -- sonst schickte der Haenger-Melder nach 20 s einen Bericht.
            if (wartetAufTurm) runs.progress(Sprache.t("Clan war: waiting for a free tower", "Clan-Krieg: warte auf freien Turm"));
            return karte;
        }

        // ---- 9. Noch in der Burg ---------------------------------------
        // Vorher: reicht die Nahrung fuer den normalen Trupp (280 000)? Wenn
        // nicht, erst Nahrungskisten oeffnen (Autor, 27.09., !settings nahrung).
        // Autor 17.10.: Fuer die Namenssuche in den Tuermen muss der Bot wissen, wie der Spieler heisst -- oben links lesen.
        if (!nameVersucht && (MeinTeam.spielername().isBlank() || mehrereKonten) && ctx.visible(screen, T_ICON)) {
            nameVersucht = true;
            try {
                String n = Burgstand.spieler(screen, ctx).getOrDefault("Name", "").strip();
                if (!de.hustlebot.core.NamensVergleich.keinName(n) && !n.equals(MeinTeam.spielername())) {
                    MeinTeam.setSpielername(n);
                    Log.info("Clan-Krieg", Sprache.t("Player name read in the castle: \"" + n + "\" -- the bot looks for it in the towers.",
                            "Spielername in der Burg gelesen: \"" + n + "\" -- danach sucht der Bot in den Tuermen."));
                }
            } catch (Exception e) {
                Log.debug("Clan-Krieg", "Spielername nicht lesbar: " + e.getMessage());
            }
        }
        // Nacht 26.10. (3:30, Zweitkonto A): Beim Nachpruefen sah der Bot zuerst ein Nahrungs-Werbevideo an (3 min) --
        // dort wird nur geschaut, ob der Trupp steht. Nahrung erst, wenn wirklich gesetzt werden muss.
        if (!nurPruefen && !nahrungGeprueft && !normalGesetzt && Nahrungskisten.istAn()
                && ctx.visible(screen, T_ICON) && nahrung.einsatzbereit(ctx)) {
            nahrungGeprueft = true;
            nahrung.starten();
            return Outcome.ACTED;
        }
        // Der Krieg laeuft schon (Test 28.09., 23:12: der Bot wartete 30 s auf
        // das normale Symbol, Autor hielt ihn an). Nichts aufzustellen -- weiter.
        if (!ctx.visible(screen, T_ICON) && ctx.visibleIfKnown(screen, T_ICON_LAEUFT)) {
            Log.info("Clan-Krieg", Sprache.t("The war is running right now (icon with flames) -- deploying is only possible "
                    + "in the next preparatory stage. Nothing to do, moving on.",
                    "Der Krieg laeuft gerade (Symbol mit Flammen) -- aufstellen geht erst "
                    + "in der naechsten Vorbereitung. Nichts zu tun, es geht weiter."));
            Kriegsplan.fensterAbhaken();   // Code-Review 23.10.: geklaert, aber nichts gesetzt
            return Outcome.FINISHED;
        }
        if (klickVorlage(screen, ctx, T_ICON, Sprache.t("To the clan war (icon in the castle)", "Zum Clan-Krieg (Symbol in der Burg)"))) {
            unbekannteBilder = 0;
            burgGesehen = true;
            // HIER STAND kriegskarteGesehen = true. Das war falsch.
            //
            // Auf das Symbol GEKLICKT zu haben heisst nicht, die Kriegskarte
            // GESEHEN zu haben. Geht der Klick daneben oder war das Symbol
            // gar keins, stand die Merkung trotzdem -- und weiter unten
            // entscheidet genau sie darueber, ob ein ausbleibender Krieg als
            // stiller Feierabend oder als Absturz gemeldet wird.
            //
            // Das ist die Meldung, ueber die Autor sich beschwert hat:
            // "der bot laeuft grade keinen modus und trotzdem zeigt er mir
            // das hier die ganze zeit im discord an" -- Stopped with an
            // error, obwohl nur kein Krieg lief.
            //
            // Gesetzt wird die Merkung jetzt nur noch dort, wo die Karte
            // wirklich erkannt wird: in aufDerKarte(). Gesehen zaehlt, nicht
            // geklickt -- dieselbe Regel, nach der auch die Aufstellung
            // selbst als fertig gilt.
            return Outcome.ACTED;
        }

        // ---- 10. Kein Kriegssymbol da ----------------------------------
        //
        // Steht der Bot auf der Weltkarte (nach Leuchtturm/Kolosseum bleibt er
        // dort, Autor 26.09.), braucht der Krieg die Burg: "Home" unten links.
        if (Weltkarte.erkannt(screen, ctx)) {
            Weltkarte.heim(screen, ctx, "Clan-Krieg");
            return Outcome.ACTED;
        }
        // Ist das ueberhaupt die Burg? Nur dann sagt ein fehlendes
        // Kriegssymbol etwas (siehe unten).
        boolean burgJetzt = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
        if (!burgJetzt) {   // Burg nicht (mehr) zu sehen: die Uhr faengt neu an
            burgOhneSymbolSeit = 0;
            burgBilderOhneSymbol = 0;
        }
        if (burgJetzt) {
            burgGesehen = true;
            // AUSSERHALB DER KRIEGSZEITEN GIBT ES KEIN SYMBOL (Autor, 28.09., 23:17:
            // der Bot wartete ~30 s, bis er "kein Krieg" sagte). Steht die Burg
            // sicher da (Map-Knopf) und fehlt das Symbol, ist keine
            // Kriegszeit -- weiter mit dem naechsten Modus. Autor: "extrem kuerzen, 5 s max" --
            // 1,5 s nach dem ersten Blick auf die fertige Burg.
            if (burgOhneSymbolSeit == 0) burgOhneSymbolSeit = System.currentTimeMillis();
            // Mindestens 3 Bilder hintereinander UND 1,5 s -- ein kurz verdecktes Symbol
            // (Werbefenster) laesst den Krieg nicht mehr ausfallen (Code-Pruefung 29.09.).
            burgBilderOhneSymbol++;
            if (burgBilderOhneSymbol >= 3 && System.currentTimeMillis() - burgOhneSymbolSeit >= 1500
                    && !kriegskarteGesehen) {
                // Server-Test 29.10. 14:26: Nach dem Team-Scan war die Kaserne noch angewaehlt (unten "Barracks", Knoepfe
                // Info/Upgrade) -- dann blendet das Spiel das Kriegssymbol aus, und der Bot meldete "kein Krieg", obwohl
                // einer lief (Autor: "Der Serverbot hat auf die Barracks gedrueckt. Dann ist das Clan-Symbol nicht da.").
                // Deshalb einmal zur Weltkarte und zurueck: danach ist nichts mehr angewaehlt. Den Rueckweg macht der
                // Zweig oben (Weltkarte.erkannt -> heim). Erst wenn auch dann kein Symbol da ist, gilt "kein Krieg".
                if (!kartenRundeGemacht) {
                    String karte = ctx.visibleIfKnown(screen, "map_button") ? "map_button"
                            : ctx.visibleIfKnown(screen, "map_button_alt") ? "map_button_alt" : null;
                    if (karte != null && klickVorlage(screen, ctx, karte, Sprache.t(
                            "No war icon -- once to the world map and back (a selected room hides the icon)",
                            "Kein Kriegssymbol -- einmal zur Weltkarte und zurueck (ein angewaehlter Raum verdeckt es)"))) {
                        kartenRundeGemacht = true;
                        burgOhneSymbolSeit = 0;
                        burgBilderOhneSymbol = 0;
                        return Outcome.ACTED;
                    }
                }
                Log.info("Clan-Krieg", Sprache.t("There is no war icon in the castle -- it is not war time. "
                        + "Nothing to deploy, moving on.",
                        "In der Burg ist kein Kriegssymbol -- es ist keine Kriegszeit. "
                        + "Nichts aufzustellen, es geht weiter."));
                Kriegsplan.fensterAbhaken();
                return Outcome.FINISHED;
            }
        }
        unbekannteBilder++;

        // DAS ROTE X FUEHRT ZURUECK.
        //
        // Autor, auf die Frage, wie der Bot aus dem Teilnahmefenster der
        // Kathedrale wieder herausfindet, wenn um 09:01 der Krieg faellig
        // wird: "der bot muss nur auf das rote X drücken im screenshot
        // okay. das wars."
        //
        // Fast jedes Vollbild-Fenster im Spiel hat dieses eine rote runde X
        // oben rechts, und es fuehrt immer eine Ebene zurueck Richtung
        // Burg. Es braucht also keine eigene Vorlage je Bildschirm -- nur
        // die fuenf schon aufgenommenen X der Reihe nach.
        //
        // Frueher gab es das nur ueber Stoerfenster.notausgang, und der
        // wartet zwoelf Bilder. Fuer den Clan-Krieg ist das zu lang: Das
        // Fenster ist zwei Stunden offen, aber der Bot soll nicht die
        // halbe Zeit vor einem Bildschirm stehen, den ein Klick aufloest.
        //
        // Ab dem dritten unerkannten Bild wird gedrueckt, dann alle drei.
        // Nicht sofort, damit ein kurzes Laden nicht schon als "fremd"
        // gilt -- und nur hier, wo der Bildschirm ohnehin unbekannt ist.
        // Ein rotes X blind zu druecken waere gefaehrlich: Es sieht
        // ueberall gleich aus.
        // Nacht 26.10. (Berichte #8T7E/#FMM2): Der Clan-Krieg startete, waehrend das Spiel noch auf der Etagenkarte der
        // Kathedrale stand -- "castle was not reached". Dort (und auf der Weltkarte) fuehrt der Knopf "Home" unten links
        // in die Burg; die laufende Kathedralen-Reise bleibt dabei erhalten (NIE "End journey").
        // Bildfehler-Suche 30.10. (#H4Z3): worldmap_home passt auf der Etagenkarte nur 0,724 -- die Standardschwelle 0,84
        // fand ihn dort nie, der Clan-Krieg stand 20 s still. Jetzt nur in der Ecke, dort ab 0,65 (Weltkarte.homeUntenLinks).
        if (unbekannteBilder >= 2 && Weltkarte.homeUntenLinksKlicken(screen, ctx,
                Sprache.t("Unknown screen -- Home at the bottom left (towards the castle)", "Fremder Bildschirm -- Home unten links (Richtung Burg)"))) {
            ctx.sleepUntilChanged(2000);
            return Outcome.ACTED;
        }
        if (unbekannteBilder >= 3 && unbekannteBilder % 3 == 0) {
            for (String x : List.of(T_CLOSE_X, "close_x", "dc_close_x",
                                    "dg_close_x", "ar_close_x")) {
                if (klickVorlageWennBekannt(screen, ctx, x,
                        Sprache.t("Unknown screen -- red X (" + x + ")", "Fremder Bildschirm -- rotes X (" + x + ")"))) {
                    Log.info("Clan-Krieg", Sprache.t("This screen does not belong to the clan war. "
                            + "The red X leads one level back towards the castle.",
                            "Der Bildschirm gehoert nicht zum Clan-Krieg. "
                            + "Das rote X fuehrt eine Ebene zurueck Richtung Burg."));
                    ctx.sleepUntilChanged(2000);
                    return Outcome.ACTED;
                }
            }
        }

        if (Stoerfenster.notausgang(screen, ctx, "Clan-Krieg", unbekannteBilder)) {
            return Outcome.ACTED;
        }

        // Vielleicht steht das Spiel nur woanders. Der Weg zurueck in die
        // Burg ist einen Versuch wert, bevor aufgegeben wird.
        if (unbekannteBilder == MAX_UNBEKANNT / 2
                && klickVorlage(screen, ctx, T_HOME, Sprache.t("Back to the castle", "Zurueck in die Burg"))) {
            return Outcome.ACTED;
        }

        if (unbekannteBilder >= MAX_UNBEKANNT) {
            // KEIN KRIEG IST KEIN FEHLER.
            //
            // Hier stand FATAL. Das war falsch, und Autor hat es sofort
            // gemerkt: Um 21:01 lief der Zeitplan an, es war aber gar kein
            // Krieg -- und in Discord stand "Stopped with an error. Abbruch
            // nach 0 Durchlaeufen. Bitte das Protokoll pruefen." Zweimal,
            // weil ein Neustart dazwischenlag.
            //
            // Das Kriegssymbol gibt es nur waehrend der Kriegszeiten. Ist es
            // nicht da, gibt es schlicht nichts zu tun. Das ist ein
            // ordentliches Ende, kein Absturz -- der Bot macht danach
            // einfach mit dem weiter, was in der Warteschlange steht.
            //
            // Ein echter Fehler waere es nur, wenn er die Kriegskarte schon
            // gesehen hatte und sich DANN verloren haette.
            if (!kriegskarteGesehen) {
                // DIE BURG NIE GESEHEN HEISST NICHT "KEIN KRIEG" (27.09., 09:08):
                // Der Bot stand noch im Verlies, kam nicht hinaus und meldete
                // trotzdem "es laeuft kein Krieg". Das Kriegssymbol kann nur
                // fehlen, wo es hingehoert -- in der Burg.
                if (!burgGesehen) {
                    ohneBurgAufgegeben = true;
                    warnung(Sprache.t("The castle was not reached -- the bot is on "
                            + "a different screen. Whether a war is running is therefore NOT "
                            + "settled. It will try again later.",
                            "Die Burg wurde nicht erreicht -- der Bot steht auf "
                            + "einem anderen Bildschirm. Ob ein Krieg laeuft, ist damit NICHT "
                            + "geklaert. Es wird spaeter noch einmal versucht."));
                    return Outcome.FINISHED;
                }
                Log.info("Clan-Krieg", Sprache.t("The war icon is not visible in the castle. "
                        + "No war is running right now -- there is nothing to deploy.",
                        "Das Kriegssymbol ist in der Burg nicht zu sehen. "
                        + "Es laeuft gerade kein Krieg -- es gibt nichts aufzustellen."));
                Kriegsplan.fensterAbhaken();
                return Outcome.FINISHED;
            }
            Log.alarm("Clan-Krieg", Sprache.t("Nothing has been recognised for " + unbekannteBilder + " pictures, "
                    + "although the war map was there before. Something "
                    + "is wrong here.",
                    "Seit " + unbekannteBilder + " Bildern ist nichts mehr "
                    + "zu erkennen, obwohl die Kriegskarte vorher da war. Hier stimmt "
                    + "etwas nicht."));
            return Outcome.FATAL;
        }
        return Outcome.UNKNOWN;
    }

    // ==================================================================
    //  Die Kriegskarte
    // ==================================================================

    /** "Aussenposten", "normalen Turm" oder beides -- fuer die Protokollzeile. */
    private static String sortenText(List<Sorte> sorten) {
        if (sorten.isEmpty()) return Sprache.t("nothing", "nichts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorten.size(); i++) {
            if (i > 0) sb.append(Sprache.t(" and ", " und "));
            sb.append(sorten.get(i) == Sorte.AUSSENPOSTEN ? Sprache.t("the outpost", "Aussenposten")
                                                          : Sprache.t("the normal tower", "normalen Turm"));
        }
        return sb.toString();
    }

    /**
     * Steht das Fenster "Create squad" offen?
     *
     * NICHT NUR AN DER UEBERSCHRIFT. Frueher hing es allein an
     * cw_create_squad. Passte die Vorlage einmal nicht -- eine andere
     * Fensterhoehe, ein anderer Ausschnitt --, war der Bot blind fuer das
     * Fenster und lief bis zur Kriegskarte durch.
     *
     * Jetzt zaehlen auch die Knoepfe DARIN. "Deploy the best" und "Previous
     * Squad" gibt es NUR in diesem Fenster; wer einen von beiden sieht,
     * steht sicher davor. Und es sind genau die Knoepfe, die der Bot als
     * naechstes druecken will -- passt keiner von ihnen, kann er ohnehin
     * nichts ausrichten.
     */
    private boolean truppenfensterOffen(Mat screen, BotContext ctx) {
        return ctx.visible(screen, T_CREATE_SQUAD)
            || ctx.visible(screen, T_DEPLOY_BEST)
            || ctx.visible(screen, T_PREVIOUS_SQUAD);
    }

    /**
     * Sind wir auf der Kriegskarte?
     *
     * DER "BACK"-KNOPF ZAEHLT NICHT MEHR. Er war hier die schwaechste
     * Stelle: Das Fenster "Create squad" deckt nur die Mitte ab, unten
     * links bleibt "Back" sichtbar -- und oben schaut sogar noch
     * "Preparatory stage" hervor. Der Bot hielt sich deshalb mitten im
     * offenen Truppenfenster fuer die Kriegskarte und klickte den naechsten
     * Turm an, was das Fenster wieder zumachte.
     *
     * Deshalb zwei Aenderungen, die zusammengehoeren:
     *
     *   - Das Truppenfenster wird VORHER geprueft und faengt den Fall ab
     *     (siehe truppenfensterOffen).
     *   - Und selbst wenn es einmal nicht erkannt wird, gilt ein offenes
     *     Fenster als Sperre: liegt irgendein Turm- oder Truppenfenster
     *     ueber der Karte, ist es NICHT die Karte.
     *
     * Ein Beweis, der auch unter einem Fenster noch da ist, ist kein Beweis.
     */
    private boolean aufDerKarte(Mat screen, BotContext ctx) {
        // Liegt ein Fenster darueber, wird auf der Karte nichts angeklickt.
        if (truppenfensterOffen(screen, ctx)) return false;
        if (ctx.visible(screen, T_DEPLOY_SQUAD)) return false;
        if (ctx.visibleIfKnown(screen, T_SQUADS_LABEL)) return false;

        // Notbremse, die ohne jede Vorlage auskommt.
        //
        // Falls das Truppenfenster einmal von KEINER Vorlage erkannt wird,
        // wuerde der Bot wieder Tuerme anklicken und es damit zumachen.
        // Deshalb: Kurz nach einem "Deploy squad" wird auf der Karte
        // grundsaetzlich nichts angefasst. Was auch immer da gerade offen
        // ist -- es ist nicht die Karte.
        long seit = System.currentTimeMillis() - deploySquadGeklickt;
        // Pruefer 29.10.: x Rechnertempo -- auf dem langsamen Server faehrt das Truppenfenster bis 5x langsamer aus.
        if (seit < de.hustlebot.core.Rechnertempo.mal(NACH_DEPLOY_RUHE_MS)) {
            if (seit > de.hustlebot.core.Rechnertempo.mal(1200) && !ruheGemeldet) {
                ruheGemeldet = true;
                warnung(Sprache.t("After \"Deploy squad\" the squad window "
                        + "opened, but no template matches it -- neither "
                        + T_CREATE_SQUAD + " nor " + T_DEPLOY_BEST + " nor "
                        + T_PREVIOUS_SQUAD + ". Nothing on the map is clicked "
                        + "meanwhile. Please capture these three again with Capture-Images.exe.",
                        "Nach \"Deploy squad\" ist das Truppenfenster "
                        + "aufgegangen, aber keine Vorlage passt darauf -- weder "
                        + T_CREATE_SQUAD + " noch " + T_DEPLOY_BEST + " noch "
                        + T_PREVIOUS_SQUAD + ". Auf der Karte wird solange nichts "
                        + "angeklickt. Bitte diese drei mit Capture-Images.exe neu aufnehmen."));
            }
            return false;
        }

        boolean da = ctx.visible(screen, T_PREP)
                || ctx.visible(screen, T_PROGRESS)
                || ctx.visible(screen, T_ENDED)
                || ctx.visible(screen, T_BACK);
        if (da) kriegskarteGesehen = true;
        return da;
    }

    /**
     * Ein Schritt auf der Karte: den naechsten brauchbaren Turm suchen und
     * anklicken.
     */
    private Outcome kartenschritt(Mat screen, BotContext ctx) {
        unbekannteBilder = 0;

        if (ctx.visible(screen, T_ENDED)) {
            Log.warn("Clan-Krieg", Sprache.t("\"War ended\" is shown at the top -- the war is over, "
                    + "nothing more can be deployed.",
                    "Oben steht \"War ended\" -- der Krieg ist vorbei, "
                    + "es laesst sich nichts mehr aufstellen."));
            Kriegsplan.fensterAbhaken();
            return Outcome.FINISHED;
        }

        // Erst nachsehen, was schon steht. Das Schild "Your squad" ist der
        // einzige verlaessliche Beweis, dass eine Truppe wirklich drin ist.
        List<Turm> tuerme = tuermeFinden(screen, ctx);
        // Nacht 26.10.: ein Krieg ganz ohne Aussenposten (8 normale Tuerme) -- der Bot wartete endlos auf einen.
        // Live-Test 17.10., 16:14: im ersten Bild der Karte waren die Aussenposten-Motive noch nicht gezeichnet -- Wrath,
        // Rage und Storm galten als normale Stuetzpunkte. Erst auswerten, wenn ein Aussenposten erkannt ist (hoechstens ~3 s).
        if (tuerme.stream().noneMatch(Turm::aussenposten) && kartenWarten < 5) {
            kartenWarten++;
            ctx.sleep(600);
            return Outcome.WAITING;
        }
        // Code-Review 26.10.: erst NACH dem Warten zaehlen -- die Wartebilder (Motive noch nicht gezeichnet) zaehlten mit.
        if (!tuerme.isEmpty()) ohneAussenposten = tuerme.stream().anyMatch(Turm::aussenposten) ? 0 : ohneAussenposten + 1;
        nochUngesehen = !nurPruefen && erkundungsKlicks < MAX_ERKUNDUNG && !ungeseheneTuerme(tuerme).isEmpty();
        pruefeWasSteht(screen, ctx, tuerme);
        if ((normalGesetzt || essenReichtNicht) && titanErledigt() && verifikationFertig) {
            return Outcome.ACTED;
        }

        bericht(tuerme);

        if (tuerme.isEmpty()) {
            warnung(Sprache.t("No tower can be recognised on the map. The bot looks for "
                    + "the occupancy below it (cw_slash). Is the template missing, or was the "
                    + "capture area too large?",
                    "Auf der Karte ist kein Turm zu erkennen. Gesucht wird "
                    + "die Belegung darunter (cw_slash). Fehlt die Vorlage, oder ist der "
                    + "Ausschnitt zu gross geraten?"));
            return Outcome.WAITING;
        }

        // Was fehlt noch?
        //
        // Der Aussenposten zuerst, weil der nichts kostet -- reicht das
        // Essen spaeter nicht, ist wenigstens der sichere Teil erledigt.
        //
        // ABER NICHT NUR DER. Hier stand frueher eine einzige Sorte:
        //
        //     Sorte gesucht = !titanGesetzt ? AUSSENPOSTEN : NORMAL;
        //
        // War kein freier Aussenposten auf der Karte -- weil alle voll sind,
        // oder weil es in diesem Krieg gar keinen gibt --, blieb es bis zum
        // Schluss bei AUSSENPOSTEN. Der Bot wartete die volle Geduld ab und
        // hoerte dann auf mit "normaler Turm: nein". Ein fehlender
        // Aussenposten kostete also auch den normalen Turm, obwohl der frei
        // danebenstand.
        //
        // Jetzt wird der Reihe nach gesucht: fehlt der erste, kommt der
        // zweite dran. Aufgegeben wird erst, wenn fuer KEINE offene Sorte
        // ein Turm da ist.
        List<Sorte> offeneSorten = new java.util.ArrayList<>();
        if (!titanGesetzt && titanMoeglich()) offeneSorten.add(Sorte.AUSSENPOSTEN);
        if (!normalGesetzt && !essenReichtNicht) offeneSorten.add(Sorte.NORMAL);

        // ERKUNDUNG (Autor 17.10.): erst jeden offenen Turm einmal oeffnen und lesen, wessen Name dort steht.
        if (erkundenTurm != null) {
            // Wieder auf der Karte, das Lese-Fenster ging nicht auf: noch einmal, nach zwei Versuchen ohne Lesung weiter.
            if (++erkundenVersuche > 2) {
                erkundet.put(erkundenTurm.schluessel(), new TurmInfo("", "", -1, false, false));
                erkundenTurm = null;
            } else {
                return erkundenKlick(ctx, erkundenTurm);
            }
        }
        // Gesetzte, aber noch nicht gepruefte Sorten (nur mit bekanntem Spielernamen).
        List<Sorte> pruefSorten = new ArrayList<>();
        if (!MeinTeam.spielername().isBlank()) {
            if (titanGesetzt && !geprueft.contains(Sorte.AUSSENPOSTEN)) pruefSorten.add(Sorte.AUSSENPOSTEN);
            if (normalGesetzt && !geprueft.contains(Sorte.NORMAL)) pruefSorten.add(Sorte.NORMAL);
        }
        // Autor 17.10.: "Von links nach rechts anschauen -- erst alle Stuetzpunkte + mein Team in den richtigen stecken,
        // dann alle Outposts und mein Team dort auch in den richtigen stecken."
        boolean normalDran = !normalUebersprungen
                && (offeneSorten.contains(Sorte.NORMAL) || pruefSorten.contains(Sorte.NORMAL));
        final Sorte phase = normalDran ? Sorte.NORMAL : Sorte.AUSSENPOSTEN;
        if (erkundungsKlicks < MAX_ERKUNDUNG) {
            erkundenListe.clear();
            // Kontrolle nach dem Setzen bleibt -- ausser der Trupp steht schon im Turm mit dem eigenen Namen (Autor 25.10.).
            if ((!nameGefunden.contains(phase) || pruefSorten.contains(phase)) && !richtigGesehen.contains(phase)) {
                // Beim Nachpruefen zuerst die Tuerme mit "Your squad" darueber (naechstes Schild), sonst von links nach rechts.
                // Autor 30.10. (Server: 13 Tuerme ansehen dauerten 3,5 Minuten): Tuerme mit Spielern zuerst -- steht dort sein Name, hoert das
                // Ansehen frueher auf. Unter gleich vollen von links nach rechts (Autor 17.10.).
                java.util.Comparator<Turm> reihenfolge = ansehReihenfolge();
                if (pruefSorten.contains(phase) && !schildOrte.isEmpty()) {
                    reihenfolge = java.util.Comparator.<Turm>comparingDouble(tt -> schildOrte.stream()
                            .mapToDouble(p -> p.distance(tt.x(), tt.y())).min().orElse(Double.MAX_VALUE)).thenComparing(reihenfolge);
                }
                // Nachpruefen (Autor 25.10.): nur die Tuerme, ueber denen "Your squad" steht.
                double nah = SCHILD_NAH * ctx.width() / 1159.0;
                boolean nurSchilder = nurPruefen && pruefSorten.contains(phase) && !offeneSorten.contains(phase);
                tuerme.stream()
                        .filter(tt -> (tt.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) == phase)
                        .filter(tt -> offeneSorten.contains(phase) || pruefSorten.contains(phase))
                        .filter(tt -> !nurSchilder || schildOrte.stream().anyMatch(p -> p.distance(tt.x(), tt.y()) <= nah))
                        // volle Tuerme nur beim Pruefen ansehen (dort kann der eigene Trupp stehen)
                        .filter(tt -> !tt.voll() || pruefSorten.contains(phase))
                        .filter(tt -> !erkundet.containsKey(tt.schluessel()))
                        .filter(tt -> !gesperrteTuerme.contains(tt.schluessel()) || nurNamensGesperrt.contains(tt.schluessel()))
                        .sorted(reihenfolge)
                        .forEach(erkundenListe::add);
            }
            // Autor 30.10.: "jeden Titanen-Outpost durchschauen nach meinem Namen". Kein Aussenposten erkannt, der normale Trupp ist
            // durch und der Titan fehlt: jeden noch nicht geoeffneten Turm ansehen, ein Tempel korrigiert sich dann am Titel.
            if (erkundenListe.isEmpty() && !normalDran && !titanGesetzt && !nurPruefen && offeneSorten.contains(Sorte.AUSSENPOSTEN)
                    && tuerme.stream().noneMatch(Turm::aussenposten)) {
                erkundenListe.addAll(ungeseheneTuerme(tuerme));
            }
            // Code-Review 23.10.: dieselbe Filterkette stand zweimal -- der naechste Turm ist der erste der Liste.
            Optional<Turm> neu = erkundenListe.stream().findFirst();
            if (neu.isPresent()) {
                erkundungsKlicks++;
                erkundenTurm = neu.get();
                erkundenGesehen = false;
                erkundenVersuche = 0;
                return erkundenKlick(ctx, neu.get());
            }
        }

        // PRUEFEN UND UMSETZEN (Autor 17.10.)
        if (umsetzenTurm != null && umsetzenGeklickt) {
            // Live-Test 17.10., 16:06: "Move squad" -> Nachfrage "unvollstaendiger Trupp" -> Yes -> das Fenster ist zu, der
            // Trupp steht im neuen Turm. Zurueck auf der Karte heisst also: umgesetzt (vorher: zweimal neu geoeffnet).
            Log.info("Clan-Krieg", Sprache.t("Squad moved (\"Move squad\" pressed and confirmed).",
                    "Trupp umgesetzt (\"Move squad\" gedrueckt und bestaetigt)."));
            TurmInfo alt = erkundet.get(umsetzenTurm.schluessel());
            if (alt != null) erkundet.put(umsetzenTurm.schluessel(), new TurmInfo(alt.titel(), alt.namen(), alt.ruhm(), true, false));
            umsetzenTurm = null;
            umsetzenGeklickt = false;
            return Outcome.ACTED;
        }
        if (umsetzenTurm != null) {
            // Wieder auf der Karte, das Fenster ging nicht auf: noch einmal, nach zwei Versuchen aufgeben.
            if (++umsetzenVersuche > 2) {
                warnung(Sprache.t("The tower to move to did not open -- the squad stays where it is.",
                        "Der Turm zum Umsetzen ging nicht auf -- der Trupp bleibt, wo er ist."));
                umsetzenTurm = null;
            } else {
                return turmOeffnen(ctx, umsetzenTurm);
            }
        }
        for (Sorte sorte : pruefSorten.contains(phase) ? List.of(phase) : List.<Sorte>of()) {
            geprueft.add(sorte);
            String art = sorte == Sorte.AUSSENPOSTEN ? Sprache.t("titan squad", "Titanen-Trupp") : Sprache.t("squad", "Trupp");
            if (nurPruefen) {
                Log.info("Clan-Krieg", Sprache.t("Quick check: the " + art + " stands -- fine (nothing is moved).",
                        "Nachpruefen: der " + art + " steht -- passt (es wird nichts umgesetzt)."));
                continue;
            }
            // Autor 18.10.: "Wenn bei keinem Stuetzpunkt ein Name drin steht, ist es egal, in welchem Stuetzpunkt mein Team
            // steht. Hauptsache es steht." -- dann nicht umsetzen.
            String ichName = MeinTeam.spielername();
            boolean nameIrgendwo = false, trupStehtIrgendwo = false;
            for (Turm tt : tuerme) {
                if ((tt.aussenposten()) != (sorte == Sorte.AUSSENPOSTEN)) continue;
                TurmInfo i = erkundet.get(tt.schluessel());
                if (i == null) continue;
                if (ichName != null && !ichName.isBlank() && enthaeltNamen(i.namen(), ichName)) nameIrgendwo = true;
                if (i.meinTrupp() || i.verschieben()) trupStehtIrgendwo = true;
            }
            if (!nameIrgendwo && trupStehtIrgendwo) {
                Log.info("Clan-Krieg", Sprache.t("Check: no name at any " + (sorte == Sorte.AUSSENPOSTEN ? "outpost" : "tower")
                                + ", the " + art + " stands -- fine.",
                        "Pruefung: an keinem " + (sorte == Sorte.AUSSENPOSTEN ? "Aussenposten" : "Turm") + " ein Name, der "
                                + art + " steht -- passt."));
                continue;
            }
            Turm wohin = null;
            boolean richtig = false;
            for (Turm tt : rangliste(tuerme, sorte)) {
                TurmInfo i = erkundet.get(tt.schluessel());
                if (i.meinTrupp()) { richtig = true; wohin = tt; break; }
                if (!tt.voll()) { wohin = tt; break; }
            }
            if (wohin == null) {
                Log.info("Clan-Krieg", Sprache.t("Check: no tower for the " + art + " by the rules (name not found) -- it stays where it is.",
                        "Pruefung: kein Turm fuer den " + art + " nach den Regeln (Name nicht gefunden) -- er bleibt, wo er ist."));
                continue;
            }
            TurmInfo ziel1 = erkundet.get(wohin.schluessel());
            if (ziel1.titel().isBlank()) ziel1 = new TurmInfo(Sprache.t("tower with ", "Turm mit ") + ziel1.namen(), ziel1.namen(),
                    ziel1.ruhm(), ziel1.meinTrupp(), ziel1.verschieben());
            if (richtig) {
                Log.info("Clan-Krieg", Sprache.t("Check: the " + art + " stands in the right place (\"" + ziel1.titel() + "\").",
                        "Pruefung: der " + art + " steht richtig (\"" + ziel1.titel() + "\")."));
                continue;
            }
            Log.info("Clan-Krieg", Sprache.t("Check: the " + art + " stands in the wrong place -- it is moved to \"" + ziel1.titel()
                    + "\" (" + ziel1.namen() + ", glory " + ziel1.ruhm() + ").",
                    "Pruefung: der " + art + " steht falsch -- er wird nach \"" + ziel1.titel() + "\" umgesetzt ("
                    + ziel1.namen() + ", Ruhm " + ziel1.ruhm() + ")."));
            umsetzenTurm = wohin;
            umsetzenSorte = sorte;
            umsetzenGeklickt = false;
            umsetzenVersuche = 0;
            return turmOeffnen(ctx, wohin);
        }
        boolean ohneName = MeinTeam.spielername().isBlank();
        verifikationFertig = (!titanGesetzt || ohneName || geprueft.contains(Sorte.AUSSENPOSTEN))
                && (!normalGesetzt || ohneName || geprueft.contains(Sorte.NORMAL));
        if (!offeneSorten.contains(phase)) {
            // Code-Review 23.10.: Wurde Normal uebersprungen (kein Turm frei) und steht der Titan jetzt, ist Normal wieder
            // dran -- sonst blieb die Phase fuer immer beim Aussenposten (Schleife ohne Ende, ohne Haenger-Meldung).
            if (normalUebersprungen && titanErledigt() && offeneSorten.contains(Sorte.NORMAL)) normalUebersprungen = false;
            return Outcome.ACTED;        // diese Sorte steht und ist geprueft -- im naechsten Bild die naechste (oder Abschluss)
        }
        offeneSorten.retainAll(List.of(phase));   // gesetzt wird nur die Sorte, die gerade dran ist

        Optional<Turm> ziel = Optional.empty();
        Sorte gesucht = offeneSorten.isEmpty() ? Sorte.NORMAL : offeneSorten.get(0);
        var nachRegel = zielNachRegeln(tuerme, offeneSorten);
        if (nachRegel.isPresent()) {
            ziel = Optional.of(nachRegel.get().getValue());
            gesucht = nachRegel.get().getKey();
        } else {
            zielNachName = false;
        }

        // LEERE TUERME ZUERST -- bei BEIDEN Sorten.
        //
        // Autor zuerst nur fuer die normalen: "prioritisiere bitte türme die
        // noch keine anderen truppen drinnen haben." Und nach dem naechsten
        // Lauf auch fuer die Aussenposten: "der bot sol bitte auch bei
        // titanen türmen schauen, ob es türme gibt wo noch kein spieler
        // drinnen ist."
        //
        // Der Anlass war eindeutig: Der Bot uebersprang richtig den
        // gesperrten Tempel, setzte den Titanen-Trupp dann aber in den
        // "Storm Outpost", in dem schon ein Mitspieler stand -- waehrend der
        // "Rage Outpost" mit 0/4 daneben frei war.
        //
        // "Wenn es geht" bleibt: Ist kein leerer Turm frei, wird trotzdem
        // gesetzt. Ein Trupp in einem halbvollen Turm ist besser als keiner.
        // Nacht 26.10.: Alle Tuerme trugen Namen anderer Spieler (Clan-Verwaltung) -- Zweitkonto A wurde gar nicht gesetzt.
        // Beim Zweitkonto-Bot (mehrere Konten) darum als zweiter Versuch auch reservierte Tuerme (Autor: "einfach in einen
        // zufaelligen"). Autor 03.11.: jetzt auch beim Hauptkonto -- "sonst bitte in irgendeinem Stuetzpunkt, aber er soll
        // mein normales Team und Titanen-Team zu 100 % setzen". Zuerst bleiben die ohne fremde Notiz dran.
        for (boolean ohneFremde : new boolean[]{ true, false }) {
        if (ziel.isPresent()) break;
        for (Sorte sorte : ziel.isPresent() ? List.<Sorte>of() : offeneSorten) {
            {
                List<Turm> passende = tuerme.stream()
                        .filter(t -> (t.aussenposten() ? Sorte.AUSSENPOSTEN : Sorte.NORMAL) == sorte)
                        .filter(t -> !gesperrteTuerme.contains(t.schluessel()))
                        .filter(t -> !t.voll())   // "2/2": gar nicht erst anklicken
                        // Autor 23.10. abends: "Da stand Bruno und nicht Autor als Name" -- ein Turm mit dem Namen eines
                        // anderen Spielers gehoert dem; nie dorthin.
                        .filter(t -> !ohneFremde || !fremderName(t))
                        .toList();
                Optional<Turm> wahl = ausweichWahl(passende, t -> gelesen(erkundet.get(t.schluessel())));

                if (wahl.isPresent()) {
                    // Unter den leeren der erste, unter den vollen ein
                    // zufaelliger.
                    //
                    // Autor: "kann auch mal sein das alle normalen türme
                    // schon mindestens einen spieler drinnen haben. dann
                    // soll der bot sich einfach einen zufäligen turm
                    // aussuchen und dort setzen."
                    //
                    // Sinnvoll ist das auch abseits seines Wunsches: Immer
                    // denselben zu nehmen hiesse, sich immer auf denselben
                    // Turm zu setzen, den auch alle anderen zuerst sehen.
                    // Autor 17.10.: "...in einen Stuetzpunkt, der am wenigsten Spieler in sich hat, am besten 0/?" -- bei
                    // Gleichstand der erste (vorher unter den nicht leeren ein zufaelliger).
                    // Pruefer 29.10.: zuerst gelesene Tuerme ohne fremden Namen, dann erst ungelesene (siehe ausweichWahl).
                    Turm gewaehlt = wahl.get();
                    ziel = Optional.of(gewaehlt);
                    gesucht = sorte;
                    if (!gewaehlt.leer()) {
                        Log.info("Clan-Krieg", Sprache.t("No empty "
                                + (sorte == Sorte.AUSSENPOSTEN ? "outpost" : "normal tower")
                                + " left -- the squad goes into one of "
                                + passende.size() + " that already has someone in it "
                                + "(the one with the fewest players).",
                                "Kein leerer "
                                + (sorte == Sorte.AUSSENPOSTEN ? "Aussenposten" : "normaler Turm")
                                + " mehr frei -- der Trupp kommt in einen von "
                                + passende.size() + ", in dem schon jemand steht "
                                + "(der mit den wenigsten Spielern)."));
                    }
                    break;
                }
            }
            if (ziel.isPresent()) break;
        }
        }

        // NOTAUSGANG: doch der gesperrte Turm.
        //
        // Autor: "wenn alle titanen türme voll sind außer dieser mit dem
        // speziellen namen, soll er da trotzdem setzen."
        //
        // Die Sperrliste ist eine Vorliebe, kein Verbot. Ein Trupp im
        // ungeliebten Tempel bringt immer noch Punkte; ein leerer Platz
        // bringt keine. Deshalb werden die Namenssperren hier wieder
        // aufgehoben, sobald sonst nichts mehr da ist -- und nur dann.
        if (ziel.isEmpty() && !nurNamensGesperrt.isEmpty()) {
            Log.warn("Clan-Krieg", Sprache.t("All other towers are full or unusable. "
                    + "The skip list is lifted for this war ("
                    + nurNamensGesperrt.size() + " tower(s)) -- better to stand in the unloved "
                    + "tower than not at all.",
                    "Alle anderen Tuerme sind voll oder gehen nicht. "
                    + "Die Sperrliste wird fuer diesen Krieg aufgehoben ("
                    + nurNamensGesperrt.size() + " Turm/Tuerme) -- lieber im ungeliebten "
                    + "Turm stehen als gar nicht."));
            gesperrteTuerme.removeAll(nurNamensGesperrt);
            nurNamensGesperrt.clear();
            sperrlisteAufgehoben = true;
            return Outcome.ACTED;   // naechstes Bild sucht neu
        }

        if (ziel.isEmpty() && phase == Sorte.NORMAL && !titanErledigt()) {
            // Kein Stuetzpunkt fuer den normalen Trupp: nicht warten, erst die Outposts (Autor: Titanen kosten nichts).
            normalUebersprungen = true;
            Log.info("Clan-Krieg", Sprache.t("No tower for the normal squad right now -- on to the outposts first.",
                    "Gerade kein Stuetzpunkt fuer den normalen Trupp -- erst weiter mit den Outposts."));
            return Outcome.ACTED;
        }
        if (ziel.isEmpty()) {
            // Nicht ewig warten.
            //
            // Am 3.9. um 21:45 stand der normale Trupp schon, und der
            // einzige Aussenposten auf der Karte war voll. Der Bot hat
            // daraufhin neunundfuenfzig Mal dieselbe Warnung geschrieben und
            // waere bis zum Totmann-Schalter weitergelaufen -- obwohl der
            // Fall voellig klar war: hier ist nichts mehr zu holen.
            //
            // Auf der Karte aendert sich waehrend eines Krieges durchaus
            // etwas, Mitspieler stellen ja auch um. Ein paar Minuten warten
            // ist also richtig. Danach ist Schluss, und zwar ordentlich:
            // was steht, steht.
            // ALLES SCHON ERLEDIGT (Autor, 28.09., 21:49): Der normale Trupp stand,
            // der einzige Titanen-Turm war voll (6/6) -- "in so einem Fall soll der
            // Bot einfach weitermachen". Ist jeder Turm der noch offenen Sorte
            // sicher als voll gelesen, gibt es nichts zu warten.
            // (Fehlt ein Turm dieser Sorte ganz, wird wie bisher gewartet -- dann
            // kann auch die Erkennung danebenliegen.)
            // Pruefer 29.10.: "gelesen" verlangte die Belegung ALLER Tuerme -- auf dem Server oft nur 8 von 13. Dann wartete
            // der Bot die volle Geduld ab, obwohl jeder Turm der GESUCHTEN Sorte sicher als voll gelesen war.
            boolean alleVoll = alleGesuchtenVoll(tuerme, offeneSorten);
            // Autor 26.10.: Titanen-Tuerme alle voll -> im Turm mit seinem Namen (sonst meister Ruhm) das schwaechste
            // Team herausnehmen und dann ganz normal einsetzen. Nur beim Setzen, nicht beim Nachpruefen.
            if (alleVoll && offeneSorten.contains(Sorte.AUSSENPOSTEN) && !titanGesetzt && !nurPruefen
                    && !rauswurfVersucht && TitanRauswurf.istAn()) {
                Optional<Turm> voll = rauswurfZiel(tuerme);
                if (voll.isPresent()) {
                    rauswurfVersucht = true;
                    Turm z = voll.get();
                    rauswurfTurm = z.schluessel();
                    TurmInfo info = erkundet.get(z.schluessel());
                    rauswurf.starten(z.x(), klickYOhneSchild(ctx, z), info != null && !info.titel().isBlank() ? info.titel()
                            : Sprache.t("outpost at ", "Outpost bei ") + z.x() + "," + z.y());
                    return Outcome.ACTED;
                }
            }
            if (alleVoll) {
                Log.info("Clan-Krieg", Sprache.t("All towers for " + sortenText(offeneSorten)
                        + " are full -- nothing left to do. The bot leaves the clan war "
                        + "and carries on.",
                        "Alle Tuerme fuer " + sortenText(offeneSorten)
                        + " sind voll -- nichts mehr zu tun. Der Bot geht aus dem Clan-Krieg raus "
                        + "und macht weiter."));
                nichtsMehrFrei = true;   // naechstes Bild: Abschluss (Back, rotes X)
                return Outcome.ACTED;
            }

            if (++ohneZiel == 1) ohneZielSeit = System.currentTimeMillis();
            if (!ohneZielGeduldUm(System.currentTimeMillis() - ohneZielSeit)) {
                if (ohneZiel % 40 == 1) {
                    Log.warn("Clan-Krieg", Sprache.t("No free tower left for "
                            + sortenText(offeneSorten)
                            + " (" + gesperrteTuerme.size() + " skipped). "
                            + "Still waiting to see whether something changes on the map.",
                            "Kein freier Turm mehr uebrig fuer "
                            + sortenText(offeneSorten)
                            + " (" + gesperrteTuerme.size() + " uebersprungen). "
                            + "Es wird noch gewartet, ob sich auf der Karte etwas aendert."));
                }
                wartetAufTurm = true;
                return Outcome.WAITING;
            }

            Log.info("Clan-Krieg", Sprache.t("There is no free tower left for "
                    + sortenText(offeneSorten)
                    + ". Deployment ends with what stands -- normal tower: "
                    + (normalGesetzt ? "deployed" : "no")
                    + ", outpost: " + (titanGesetzt ? "deployed" : "no") + ".",
                    "Es ist kein freier Turm mehr da fuer "
                    + sortenText(offeneSorten)
                    + ". Aufstellung beendet mit dem, was steht -- normaler Turm: "
                    + (normalGesetzt ? "gesetzt" : "nein")
                    + ", Aussenposten: " + (titanGesetzt ? "gesetzt" : "nein") + "."));
            nichtsMehrFrei = true;   // erst raus aus dem Clan-Krieg (Abschluss)
            return Outcome.ACTED;
        }
        ohneZiel = 0;

        Turm t = ziel.get();
        // Ben 21.10. (03:28-03:43, 399 Mal "Open normal tower at 109,442"): ein Turm mit Sonderregel ("Trial Trail",
        // "Special requirements ... no more than 7 units") liess sich nicht besetzen, und der Bot oeffnete ihn eine
        // Viertelstunde lang immer wieder. Nach dem dritten Oeffnen ohne Aufstellung wird der Turm uebersprungen.
        // Pruefer 29.10.: gezaehlt wird erst, wenn das Fenster wirklich aufging (turmFensterGesehen) -- ein doppelter Tipp
        // auf dem langsamen Server zaehlte sonst mit.
        int geoeffnet = oeffnenJeTurm.getOrDefault(t.schluessel(), 0);
        if (geoeffnet >= MAX_OEFFNEN_JE_TURM) {
            if (!gesperrteTuerme.contains(t.schluessel())) {
                gesperrteTuerme.add(t.schluessel());
                warnung(Sprache.t("Tower at " + t.x() + "," + t.y() + " opened " + MAX_OEFFNEN_JE_TURM
                        + " times without deploying (special requirements?) -- skipped, trying another one.",
                        "Turm bei " + t.x() + "," + t.y() + " " + MAX_OEFFNEN_JE_TURM
                        + " Mal ohne Aufstellung geoeffnet (Sonderregel?) -- uebersprungen, ein anderer wird probiert."));
            }
            return Outcome.ACTED;
        }
        offenerTurm = gesucht;
        offenePosition = t;
        truppeGewaehlt = false;   // neuer Turm, neue Auswahl
        truppRunden.neuerTurm();
        elementGeprueft = elementPflicht = false;
        auffuellen.neuerTurm();
        kontrolleFehlversuche = 0;
        auswahlGesuchtSeit = 0;
        deployVersuche = 0;
        scrollVersuche = 0;
        geradeGesetzt = false;
        if (!turmAntippen(ctx, t, true, gesucht == Sorte.AUSSENPOSTEN ? "Open outpost" : "Open normal tower",
                gesucht == Sorte.AUSSENPOSTEN ? "Aussenposten oeffnen" : "Normaler Turm oeffnen")) {
            offenerTurm = null;   // nicht getippt (Sperrzone) -- kein Turm offen
            offenePosition = null;
        }
        return Outcome.ACTED;
    }

    /**
     * Sucht alle Tuerme ueber ihre Belegung.
     *
     * Unter jedem Turm steht "0/3" oder aehnlich. Der Schraegstrich darin
     * ist das einzige Zeichen, das bei jedem Turm gleich aussieht -- die
     * Ziffern wechseln, die Turmbilder sowieso. Wo ein Schraegstrich ist,
     * ist ein Ziel.
     */
    private List<Turm> tuermeFinden(Mat screen, BotContext ctx) {
        List<Turm> gefunden = new ArrayList<>();
        List<Matcher.Hit> zaehler = ctx.matcher().findAll(screen, T_SLASH);

        // Alle Aussenposten-Motive einsammeln, egal wie viele es gibt.
        //
        // Auch hier in Graustufen: Ein Turm, ueber dem ein Schild oder eine
        // Fackel liegt, wird vom Spiel abgedunkelt gezeichnet. Die Form
        // bleibt dieselbe.
        List<Matcher.Hit> posten = new ArrayList<>();
        StringBuilder postenBericht = new StringBuilder();
        for (String name : ctx.matcher().names()) {
            if (!name.startsWith(T_OUTPOST_PREFIX)) continue;
            List<Matcher.Hit> treffer =
                    ctx.matcher().findAllGrau(screen, name, POSTEN_SCHWELLE, 6);
            posten.addAll(treffer);
            if (postenBericht.length() > 0) postenBericht.append(" | ");
            postenBericht.append(name.substring(T_OUTPOST_PREFIX.length() + 1))
                         .append(": ").append(treffer.size());
        }
        if (!postenBericht.toString().equals(letzterPostenBericht)) {
            letzterPostenBericht = postenBericht.toString();
            Log.info("Clan-Krieg", Sprache.t("Outpost motifs found -- ", "Aussenposten-Motive gefunden -- ") + postenBericht);
        }

        // Die Nullen einsammeln -- fuer die Frage, welcher Turm noch leer ist.
        //
        // Unter jedem Turm steht seine Belegung, "0/4" oder "1/4". Steht
        // links vom Schraegstrich eine Null, ist noch niemand drin. Genau
        // die will Autor zuerst: "prioritisiere bitte türme die noch keine
        // anderen truppen drinnen haben."
        List<Matcher.Hit> nullen = ctx.matcher().findAll(screen, "cw_digit_0");

        // ALLE Ziffern -- fuer die Frage, welcher Turm VOLL ist (27.09.).
        // Bisher las der Bot nur die Null links vom Strich. Volle Tuerme
        // ("2/2") klickte er deshalb an und fand darin keinen Knopf -- am
        // 27.09. um 16:32 sechsunddreissig Klicks fuer den Titanen-Trupp.
        List<int[]> ziffern = new ArrayList<>();   // {ziffer, x, y, breite, hoehe, guete*1000}
        for (int d = 0; d <= 9; d++) {
            String name = "cw_digit_" + d;
            if (!ctx.matcher().has(name)) continue;
            List<Matcher.Hit> treffer = d == 0 ? nullen : ctx.matcher().findAll(screen, name);
            for (Matcher.Hit h : treffer) {
                ziffern.add(new int[]{d, h.x(), h.y(), h.width(), h.height(), (int) (h.score() * 1000)});
            }
        }

        double naheGrenze = ctx.height() * NAH_AM_TURM;
        for (Matcher.Hit z : zaehler) {
            boolean istPosten = posten.stream().anyMatch(p ->
                    Math.abs(p.centerX() - z.centerX()) < naheGrenze
                            && p.centerY() < z.centerY()
                            && z.centerY() - p.centerY() < naheGrenze * 2);
            // Autor 23.10. abends ("Struggle road" galt als Outpost): der Name unter der Zahl entscheidet -- einmal je Turm
            // gelesen und gemerkt; nicht lesbar = wie bisher das Bild. Ein beim Ansehen gelesener Titel gilt zuletzt.
            boolean gemerkt = false;
            for (int[] k : artKorrektur) {
                if (Math.abs(k[0] - z.centerX()) < 30 && Math.abs(k[1] - z.centerY()) < 30) {
                    gemerkt = true;
                    if (k[2] >= 0) istPosten = k[2] == 1;
                }
            }
            if (!gemerkt) {
                int art = artAmNamen(ctx, z);
                artKorrektur.add(new int[]{z.centerX(), z.centerY(), art});
                if (art >= 0) istPosten = art == 1;
            }
            int drin = zahlNeben(z, ziffern, true);
            int platz = zahlNeben(z, ziffern, false);
            // Einen Turm mit nur einem Platz gibt es nicht (gesehen: 2, 4, 5, 8, 9, 10, 12) -- "1" ist eine abgeschnittene 10/12
            // und darf nie "voll" bedeuten.
            platz = platzPlausibel(drin, platz);
            // "10/12": die Null am Strich gehoert zur 10 -- dann ist der Turm nicht leer.
            gefunden.add(new Turm(z.centerX(), z.centerY(), istPosten,
                    drin < 10 && (istLeer(z, nullen) || drin == 0), drin, platz));
        }
        return gefunden;
    }

    /**
     * Die Ziffer unmittelbar links (oder rechts) vom Schraegstrich, -1 wenn
     * keine da ist. Sehen sich zwei Ziffern aehnlich (3 und 8), gewinnt die
     * mit der besseren Uebereinstimmung. Suchraum wie bei istLeer().
     */
    private static int[] zifferNeben(int kanteLinks, int kanteRechts, int mitteY, int hoehe, List<int[]> ziffern, boolean links) {
        int[] beste = null;
        int besterAbstand = 0;
        for (int[] z : ziffern) {
            int abstandX = links ? kanteLinks - (z[1] + z[3]) : z[1] - kanteRechts;
            boolean nah = abstandX >= -2 && abstandX < z[3] * 1.5;
            boolean gleicheZeile = Math.abs(z[2] + z[4] / 2 - mitteY) < hoehe;
            if (!nah || !gleicheZeile) continue;
            // Die naechste Ziffer gewinnt ("10/": die 0 am Strich, nicht die 1 davor); an derselben Stelle die bessere (3/8).
            boolean klarNaeher = beste != null && abstandX < besterAbstand - z[3] / 2;
            boolean gleichNah = beste != null && Math.abs(abstandX - besterAbstand) <= z[3] / 2;
            if (beste == null || klarNaeher || (gleichNah && z[5] > beste[5])) {
                beste = z;
                besterAbstand = abstandX;
            }
        }
        return beste;
    }

    /**
     * Die ganze Zahl links (oder rechts) vom Schraegstrich, -1 wenn keine da ist. Autor 03.11. 08:20: der "Rage Outpost" mit
     * "4/12" galt als "4/1" und damit als voll -- gelesen wurde nur die Ziffer direkt am Strich. Jetzt kommt eine zweite
     * Ziffer dazu, wenn sie gleich daneben in derselben Zeile steht ("1/10", "10/12").
     */
    /**
     * Platzzahl nur, wenn sie moeglich ist, sonst -1 (unbekannt, gilt nie als voll). Einen Platz gibt es nie (gesehen: 2, 4, 5,
     * 8, 9, 10, 12), und mehr drin als Plaetze auch nicht -- Autor 03.11.: "4/1 ist unmoeglich, haette der Bot bemerken sollen".
     */
    static int platzPlausibel(int drin, int platz) {
        return platz == 1 || (platz > 0 && drin > platz) ? -1 : platz;
    }

    static int zahlNeben(Matcher.Hit strich, List<int[]> ziffern, boolean links) {
        int mitteY = strich.y() + strich.height() / 2;
        int[] erste = zifferNeben(strich.x(), strich.x() + strich.width(), mitteY, strich.height(), ziffern, links);
        if (erste == null) return -1;
        int[] zweite = zifferNeben(erste[1], erste[1] + erste[3], mitteY, strich.height(), ziffern, links);
        if (zweite == null) return erste[0];
        return links ? zweite[0] * 10 + erste[0] : erste[0] * 10 + zweite[0];
    }

    /**
     * Steht links vom Schraegstrich eine Null?
     *
     * Gesucht wird eng: dieselbe Zeile (halbe Zeichenhoehe Abstand nach oben
     * oder unten) und unmittelbar links davor. Die Ziffer sitzt direkt am
     * Strich; anderthalb Ziffernbreiten Suchraum reichen und schliessen die
     * Zahl RECHTS vom Strich sicher aus -- sonst waere "1/0" faelschlich
     * leer.
     */
    private boolean istLeer(Matcher.Hit strich, List<Matcher.Hit> nullen) {
        for (Matcher.Hit n : nullen) {
            int abstandX = strich.x() - (n.x() + n.width());
            boolean linksDavor = abstandX >= -2 && abstandX < n.width() * 1.5;
            boolean gleicheZeile =
                    Math.abs(n.centerY() - strich.centerY()) < strich.height();
            if (linksDavor && gleicheZeile) return true;
        }
        return false;
    }

    /**
     * Traegt ein, was auf der Karte schon steht.
     *
     * Das blaue Schild "Your squad" ueber einem Turm ist der Beweis. Liegt
     * es ueber einem Aussenposten-Motiv, gilt der Titanen-Trupp als
     * gesetzt, sonst der normale.
     */
    private void pruefeWasSteht(Mat screen, BotContext ctx, List<Turm> tuerme) {
        // In Graustufen suchen -- siehe Matcher.findAllGrau.
        //
        // Das Schild ist mal tuerkis und mal blass, je nachdem ob ein
        // anderes Schild darueber liegt. Am 5. September stand der zweite
        // Trupp laengst im Turm, der Bot sah das Schild aber nicht und
        // klapperte danach zehn Tuerme vergeblich ab. Autor: "your squad ist
        // immernoch your squad egal ob grau oder nicht."
        List<Matcher.Hit> schilder =
                ctx.matcher().findAllGrau(screen, T_YOUR_SQUAD, SCHILD_SCHWELLE, 10);
        schildOrte.clear();
        for (Matcher.Hit s : schilder) schildOrte.add(new java.awt.Point(s.centerX(), s.centerY()));

        // Die Zahl merken, auch wenn sie null ist. Sie wird oben in step()
        // gebraucht -- dort laeuft die Fertig-Pruefung auch dann, wenn
        // gerade ein Turmfenster im Weg ist und gar keine Karte zu sehen
        // waere. Gezaehlt wird also nur hier, auf der Karte.
        if (schilder.size() != zuletztSchilder) {
            StringBuilder wo = new StringBuilder();
            for (Matcher.Hit s : schilder) {
                if (wo.length() > 0) wo.append(", ");
                wo.append(s.centerX()).append(",").append(s.centerY())
                  .append(Sprache.t(" (score ", " (Guete ")).append(String.format("%.2f", s.score())).append(")");
            }
            Log.info("Clan-Krieg", Sprache.t("\"Your squad\" appears on the map "
                    + schilder.size() + (schilder.size() == 1 ? " time" : " times")
                    + (wo.length() > 0 ? ": " + wo : "."),
                    "Auf der Karte steht \"Your squad\" "
                    + schilder.size() + " Mal"
                    + (wo.length() > 0 ? ": " + wo : ".")));

            // Fehlt ein Schild, sagen WIE KNAPP es war.
            //
            // Ohne diese Zeile bleibt bei einem fehlenden Schild nur Raten:
            // Liegt es knapp unter der Schwelle (dann muss die Schwelle
            // runter), oder passt die Vorlage gar nicht (dann muss sie neu
            // aufgenommen werden)? Die Zahl beantwortet das in einem Lauf.
            if (schilder.size() < 2) {
                var knapp = ctx.matcher().findAllGrau(screen, T_YOUR_SQUAD, 0.40, 4);
                StringBuilder beste = new StringBuilder();
                for (Matcher.Hit h : knapp) {
                    if (h.score() >= SCHILD_SCHWELLE) continue;   // die zaehlen schon
                    if (beste.length() > 0) beste.append(", ");
                    beste.append(h.centerX()).append(",").append(h.centerY())
                         .append(" (").append(String.format("%.2f", h.score())).append(")");
                }
                if (beste.length() > 0) {
                    Log.info("Clan-Krieg", Sprache.t("Narrowly missed (threshold "
                            + SCHILD_SCHWELLE + "): " + beste,
                            "Knapp verfehlt (Schwelle "
                            + SCHILD_SCHWELLE + "): " + beste));
                }
            }
        }
        zuletztSchilder = schilder.size();

        if (schilder.isEmpty()) return;

        List<Matcher.Hit> posten = new ArrayList<>();
        for (String name : ctx.matcher().names()) {
            if (name.startsWith(T_OUTPOST_PREFIX)) {
                posten.addAll(ctx.matcher().findAll(screen, name));
            }
        }

        double naheGrenze = ctx.height() * NAH_AM_TURM;
        for (Matcher.Hit s : schilder) {

            // Steht das Schild ueber dem Turm, in den der Bot GERADE gesetzt
            // hat? Dann ist die Sorte bekannt und muss nicht geraten werden.
            //
            // Genau daran ging es schief: Der Bot hatte den Titanen-Posten
            // bei 877,535 gefuellt, sah danach "Your squad" bei 876,413 --
            // und ordnete es ueber die Aussenposten-Motive dem NORMALEN Turm
            // zu. Damit stand im Protokoll "Der normale Trupp steht", obwohl
            // der Titan gesetzt war, und der Bot suchte den Rest des Krieges
            // nach einem Aussenposten, den er schon hatte.
            //
            // Das Schild muss weiterhin GESEHEN werden -- geklickt allein
            // zaehlt nach wie vor nicht. Nur die Sorte kommt jetzt aus dem,
            // was der Bot nachweislich getan hat, statt aus geratenen
            // Abstaenden.
            if (zuletztGesetzt != null
                    && Math.abs(zuletztGesetzt.x() - s.centerX()) < naheGrenze
                    && Math.abs(zuletztGesetzt.y() - s.centerY()) < naheGrenze * 2) {
                if (schonGezaehlt(s, naheGrenze)) continue;
                if (zuletztGesetzteSorte == Sorte.AUSSENPOSTEN) {
                    if (!titanGesetzt) {
                        titanGesetzt = true;
                        merkeSchild(s);
                        Log.info("Clan-Krieg", Sprache.t("The titan squad is in place -- \"Your squad\" "
                                + "above the outpost at " + s.centerX() + ","
                                + s.centerY() + ", which was just deployed to.",
                                "Der Titanen-Trupp steht -- \"Your squad\" "
                                + "ueber dem Aussenposten bei " + s.centerX() + ","
                                + s.centerY() + ", in den gerade gesetzt wurde."));
                    }
                } else if (!normalGesetzt) {
                    normalGesetzt = true;
                    merkeSchild(s);
                    Log.info("Clan-Krieg", Sprache.t("The normal squad is in place -- \"Your squad\" "
                            + "above the tower at " + s.centerX() + "," + s.centerY()
                            + ", which was just deployed to.",
                            "Der normale Trupp steht -- \"Your squad\" "
                            + "ueber dem Turm bei " + s.centerX() + "," + s.centerY()
                            + ", in den gerade gesetzt wurde."));
                }
                continue;
            }

            // EIN SCHILD ZAEHLT NUR EINMAL.
            //
            // Ohne diese Sperre ging Folgendes schief: Auf der Karte stand
            // genau ein "Your squad", naemlich ueber dem Titanen-Posten bei
            // 876,413. Es war schon als Titan verbucht. Beim naechsten
            // Durchgang fiel dasselbe Schild durch die Aussenposten-Pruefung
            // und wurde ein zweites Mal gezaehlt -- diesmal als normaler
            // Turm:
            //
            //     03:38:20  Der Titanen-Trupp steht -- bei 876,413
            //     03:38:25  Der normale Trupp steht -- bei 876,413
            //
            // Der Bot meldete "Aufstellung fertig", obwohl im normalen Turm
            // nichts stand. Ein Schild, ein Trupp.
            if (schonGezaehlt(s, naheGrenze)) continue;

            // Live-Test 17.10.: Autors Titanen-Trupp im Wrath Outpost -- der Turm leuchtet dann GRUEN, das gruene Motiv war
            // nicht bekannt, das Schild galt als "normaler Trupp". Jetzt zaehlt zuerst der Turm, ueber dessen Belegung das
            // Schild steht (Belegung bis 30 % der Hoehe darunter).
            Optional<Turm> darunter = tuerme.stream()
                    .filter(tt -> Math.abs(tt.x() - s.centerX()) < naheGrenze)
                    .filter(tt -> tt.y() > s.centerY() && tt.y() - s.centerY() < ctx.height() * 0.30)
                    .min(java.util.Comparator.comparingInt(tt -> tt.y() - s.centerY()));
            boolean beiPosten = darunter.isPresent() ? darunter.get().aussenposten() : posten.stream().anyMatch(p ->
                    Math.abs(p.centerX() - s.centerX()) < naheGrenze
                            && Math.abs(p.centerY() - s.centerY()) < naheGrenze * 2);
            if (beiPosten && !titanGesetzt) {
                titanGesetzt = true;
                merkeSchild(s);
                Log.info("Clan-Krieg", Sprache.t("The titan squad is in place -- \"Your squad\" above "
                        + "an outpost at " + s.centerX() + "," + s.centerY() + ".",
                        "Der Titanen-Trupp steht -- \"Your squad\" ueber "
                        + "einem Aussenposten bei " + s.centerX() + "," + s.centerY() + "."));
            } else if (!beiPosten && !normalGesetzt) {
                normalGesetzt = true;
                merkeSchild(s);
                Log.info("Clan-Krieg", Sprache.t("The normal squad is in place -- \"Your squad\" at "
                        + s.centerX() + "," + s.centerY() + ".",
                        "Der normale Trupp steht -- \"Your squad\" bei "
                        + s.centerX() + "," + s.centerY() + "."));
            }
        }
    }

    /** Wie oft "Deploy squad" im gerade offenen Turm schon gedrueckt wurde. */
    private int deployVersuche = 0;
    private static final int MAX_DEPLOY_VERSUCHE = 3;
    /** Wie oft im gerade offenen Turm schon nach unten gescrollt wurde (freier Platz unten). */
    private int scrollVersuche = 0;
    private static final int MAX_SCROLL_VERSUCHE = 4;
    /** Im gerade offenen Turm wurde eben bestaetigt -- nicht mehr wischen, nur zumachen. */
    private boolean geradeGesetzt = false;
    private long geradeGesetztUm;
    /** Autor 22.10. abends: unvollstaendigen Trupp auffuellen statt mit Luecken loszuziehen. */
    private final TruppAuffuellen auffuellen = new TruppAuffuellen();
    /** Wie oft die Kontrolle "Autor steht im Turm" nach dem Deploy schon nichts fand (beim ersten Mal noch einmal sehen). */
    private int kontrolleFehlversuche = 0;

    /**
     * Autor 22.10.: "Wenn ich mein Team setze und genau in der Sekunde setzt ein anderer Mitspieler sein Team auf denselben
     * Platz, werde ich aus dem Spiel geworfen -- der Bot denkt aber, er hat gesetzt." Geht das Turmfenster nach "Deploy"
     * wieder auf, muss dort der eigene Name ueber einer Trupp-Zeile stehen. true = steht drin, false = nicht (die
     * Aufstellung zaehlt dann nicht), null = noch einmal hinsehen. Ohne bekannten Spielernamen wird nicht geprueft.
     */
    private Boolean eigenerTruppDa(Mat screen, BotContext ctx) {
        String ich = MeinTeam.spielername();
        if (ich == null || ich.isBlank()) {
            zaehler.gesehen();   // ohne Namen: das Turmfenster kam nach dem Deploy wieder -- mehr laesst sich nicht pruefen
            return Boolean.TRUE;
        }
        TurmInfo info = turmLesen(ctx, zuletztGesetzteSorte == Sorte.AUSSENPOSTEN, screen);
        if (info.meinTrupp()) {
            zaehler.gesehen();   // Pruefer 29.10.: erst jetzt zaehlt die Aufstellung fuer "fertig"
            kontrolleFehlversuche = 0;
            Log.info("Clan-Krieg", Sprache.t("Check: " + ich + " is in the tower \"" + info.titel() + "\" with the squad.",
                    "Kontrolle: " + ich + " steht mit Trupp im Turm \"" + info.titel() + "\"."));
            testFoto(ctx, "kontrolle-ok");
            return Boolean.TRUE;
        }
        if (++kontrolleFehlversuche < 2) {
            ctx.sleep(700);
            return null;
        }
        kontrolleFehlversuche = 0;
        warnung(Sprache.t("Check: " + ich + " is NOT in the tower after Deploy (names: \"" + info.namen()
                        + "\") -- this deployment does not count.",
                "Kontrolle: " + ich + " steht nach Deploy NICHT im Turm (Namen: \"" + info.namen()
                        + "\") -- diese Aufstellung zaehlt nicht."));
        testFoto(ctx, "kontrolle-fehlt");
        aufstellungZuruecknehmen();
        return Boolean.FALSE;
    }

    /** Rauswurf oder Neuverbinden direkt nach "Deploy": die Aufstellung ist nicht bewiesen und zaehlt nicht. */
    private void rauswurfNachDeploy(String wie) {
        if (!geradeGesetzt) return;
        Log.warn("Clan-Krieg", Sprache.t("Thrown out right after Deploy (" + wie + ") -- maybe someone took the same slot. "
                        + "This deployment does not count; the map is checked again.",
                "Direkt nach Deploy rausgeflogen (" + wie + ") -- vielleicht hat jemand gleichzeitig denselben Platz genommen. "
                        + "Diese Aufstellung zaehlt nicht, die Karte wird noch einmal geprueft."));
        aufstellungZuruecknehmen();
    }

    private void aufstellungZuruecknehmen() {
        zaehler.zuruecknehmen();
        bestaetigt = zaehler.anzahl();
        if (zuletztGesetzteSorte != null) geprueft.remove(zuletztGesetzteSorte);
        geradeGesetzt = false;
        truppRunden.vergessen();
    }

    /** Fotos fuer den Test in der Nacht (Autor 22.10.: "beim Clan-Krieg um 3:20 testen") -- hoechstens 60. */
    static void testFoto(BotContext ctx, String was) {
        if (!de.hustlebot.core.Beleg.an()) return;   // 01.11.: nur noch zur gezielten Fehlersuche, kein Ordner, kein Zaehlen
        try {
            java.awt.image.BufferedImage b = ctx.frame();
            if (b == null) return;
            java.nio.file.Path ordner = de.hustlebot.core.Ordner.bei("logs").resolve("clankrieg-test");
            java.nio.file.Files.createDirectories(ordner);
            try (var l = java.nio.file.Files.list(ordner)) {
                if (l.count() >= 60) return;
            }
            String zeit = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            de.hustlebot.core.Beleg.schreiben(b, "png", ordner.resolve(zeit + "-" + was + ".png").toFile());
        } catch (Exception e) {
            Log.debug("Clan-Krieg", "Testfoto nicht gespeichert: " + e.getMessage());
        }
    }

    /**
     * Wie viele Aufstellungen der Bot in diesem Lauf bestaetigt hat.
     *
     * Zwei heisst fertig -- mehr Plaetze gibt es nicht. Das ist der einzige
     * Beweis, der weder verblassen noch von einem Schild verdeckt werden
     * kann, weil er aus dem eigenen Tun kommt und nicht aus dem Bild.
     */
    private int bestaetigt = 0;

    /** Zaehlt die bestaetigten Aufstellungen je Sorte -- {@link #bestaetigt} ist immer {@code zaehler.anzahl()}. */
    private final Aufstellungen zaehler = new Aufstellungen();

    /**
     * Server 30.10., 02:28:57: "Previous Squad" gedrueckt, das Spiel meldete "You don't have a saved squad from previous
     * journeys" (Foto 022859), die Felder blieben leer ("Take at least 1 titan on a journey!"), "Confirm" tat darum nichts. Die
     * Auswahl gab es nur einmal, also drueckte der Bot 10 Minuten lang nur noch Confirm, bis die Frist um war. Jetzt gilt:
     * Steht das Fenster nach Confirm noch, zaehlt die Aufstellung nicht, und es kommt die staerkste Truppe statt "Previous
     * Squad" (einen gespeicherten Trupp bringt auch ein zweiter Versuch nicht). Nach dem dritten Fehlschlag wird dieser Turm
     * aufgegeben und laut gemeldet.
     */
    static final class TruppRunden {
        static final int MAX_RUNDEN = 3;
        /** So lange darf das Fenster nach Confirm noch stehen (x Rechnertempo) -- der Klick kann spaet wirken. */
        static final long WARTEN_MS = 5000;

        enum Was { WEITER, WARTEN, NOCHMAL, AUFGEBEN }

        private long confirmUm = 0;
        private int fehlschlaege = 0;

        void confirmGedrueckt(long jetzt) {
            confirmUm = jetzt;
        }

        /** Es kam ein anderes Fenster (Warnung, Rauswurf): das Confirm hat also gewirkt und sagt nichts mehr. */
        void vergessen() {
            confirmUm = 0;
        }

        void neuerTurm() {
            confirmUm = 0;
            fehlschlaege = 0;
        }

        /** Aufruf bei offenem Truppenfenster: Was ist zu tun, wenn zuvor Confirm gedrueckt wurde? */
        Was naechstes(long jetzt, long wartenMs) {
            if (confirmUm == 0) return Was.WEITER;
            if (jetzt - confirmUm < wartenMs) return Was.WARTEN;
            confirmUm = 0;
            return ++fehlschlaege >= MAX_RUNDEN ? Was.AUFGEBEN : Was.NOCHMAL;
        }

        /** "Previous Squad" hat nicht gewirkt -- lieber die Staerksten als gar keinen Trupp. */
        boolean lieberDieStaerksten() {
            return fehlschlaege >= 1;
        }

        int fehlschlaege() {
            return fehlschlaege;
        }
    }

    /**
     * Bestaetigte Aufstellungen je Sorte (normaler Trupp / Titanen-Trupp). Es gibt nur diese zwei Plaetze, also zaehlt
     * ein zweites "Deploy" fuer dieselbe Sorte nicht noch einmal (Server 29.10.: doppelter Klick = "beide gesetzt",
     * der Titan fehlte).
     */
    static final class Aufstellungen {
        private boolean normal, titan;
        /** Sorte des letzten Bestaetigens -- wird bei "Warnung -> No" oder Rauswurf zurueckgenommen. */
        private Boolean zuletzt;

        /**
         * @param titan true = Titanen-Aussenposten, false = normaler Turm, null = unbekannt (dann die noch freie Sorte)
         * @return true, wenn die Aufstellung neu gezaehlt wurde
         */
        boolean bestaetigt(Boolean titan) {
            boolean t = titan != null ? titan : normal && !this.titan;
            zuletzt = t;
            if (t ? this.titan : normal) return false;
            if (t) this.titan = true; else normal = true;
            return true;
        }

        void zuruecknehmen() {
            if (zuletzt == null) return;
            if (zuletzt) titan = titanGesehen = false; else normal = normalGesehen = false;
            zuletzt = null;
        }

        int anzahl() {
            return (normal ? 1 : 0) + (titan ? 1 : 0);
        }

        /**
         * Pruefer 29.10.: "Zwei Klicks auf Confirm" sind noch keine zwei Trupps. Auf dem langsamen Server stand nach dem
         * zweiten Confirm noch das Truppenfenster -- der Bot drueckte X, meldete "beide bestaetigt", der Titan fehlte.
         * Darum zaehlt fuer "fertig" nur, was danach im Turm GESEHEN wurde (eigener Trupp in der Liste).
         */
        private boolean normalGesehen, titanGesehen;

        /** Die zuletzt bestaetigte Aufstellung steht wirklich im Turm (Kontrolle nach dem Deploy). */
        void gesehen() {
            if (zuletzt == null) return;
            if (zuletzt) titanGesehen = titan; else normalGesehen = normal;
        }

        /** Bestaetigt UND danach im Turm gesehen -- nur das zaehlt fuer die Fertig-Regel. */
        int gesehenAnzahl() {
            return (normal && normalGesehen ? 1 : 0) + (titan && titanGesehen ? 1 : 0);
        }

        void leeren() {
            normal = titan = normalGesehen = titanGesehen = false;
            zuletzt = null;
        }
    }

    /** Wie viele Schilder "Your squad" beim letzten Blick auf die Karte standen. */
    private int zuletztSchilder = 0;

    /** Wie weit der Rueckweg in die Burg schon ist: 0 = Karte zu, 1 = X, 2 = fertig. */
    private int abschlussSchritt = 0;
    private int abschlussBlicke = 0;

    /** Die Schilder "Your squad", die schon einem Trupp zugeordnet sind. */
    private final List<int[]> gezaehlteSchilder = new ArrayList<>();

    /**
     * Steht ueber dem offenen Turm ein Name, den Autor gesperrt hat?
     *
     * @return der Vorlagenname, oder null wenn der Turm in Ordnung ist
     */
    /**
     * Die erste sichtbare Vorlage, deren Name so anfaengt -- oder null.
     *
     * Damit lassen sich Familien von Vorlagen behandeln, ohne sie einzeln
     * im Code aufzuzaehlen: cw_error_red, cw_error_red_2, cw_skip_1 ...
     */
    private String ersteSichtbare(Mat screen, BotContext ctx, String vorsilbe) {
        for (String name : ctx.matcher().names()) {
            if (!name.equals(vorsilbe) && !name.startsWith(vorsilbe)) continue;
            if (ctx.visible(screen, name)) return name;
        }
        return null;
    }

    private String gesperrterTurmname(Mat screen, BotContext ctx) {
        // Nur nachsehen, wenn ueberhaupt ein Turm offen ist. Auf der Karte
        // stehen die Namen aller Tuerme gleichzeitig -- dort wuerde die
        // Sperre sonst den ganzen Krieg abwuergen.
        boolean turmOffen = ctx.visible(screen, T_DEPLOY_SQUAD)
                || ctx.visibleIfKnown(screen, T_SQUADS_LABEL)
                || truppenfensterOffen(screen, ctx);
        if (!turmOffen) return null;

        for (String name : ctx.matcher().names()) {
            if (!name.startsWith(T_SKIP_PREFIX)) continue;
            if (ctx.visible(screen, name)) return name;
        }
        return null;
    }

    private void merkeSchild(Matcher.Hit s) {
        gezaehlteSchilder.add(new int[]{ s.centerX(), s.centerY() });
    }

    private boolean schonGezaehlt(Matcher.Hit s, double grenze) {
        for (int[] p : gezaehlteSchilder) {
            if (Math.abs(p[0] - s.centerX()) < grenze
                    && Math.abs(p[1] - s.centerY()) < grenze) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schreibt alle 15 Sekunden ins Protokoll, was auf der Karte zu sehen
     * ist.
     *
     * Kostet nichts und ist die einzige Moeglichkeit, die geratenen
     * Abstaende zu pruefen, ohne dass Autor ein Bildschirmfoto schicken
     * muss. Findet der Bot acht Tuerme, aber keinen einzigen
     * Aussenposten, steht NAH_AM_TURM falsch -- und man sieht es hier.
     */
    private void bericht(List<Turm> tuerme) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzterKartenbericht < 15_000) return;
        letzterKartenbericht = jetzt;

        long anzahlPosten = tuerme.stream().filter(Turm::aussenposten).count();
        long leereNormale = tuerme.stream()
                .filter(t -> !t.aussenposten() && t.leer()).count();
        long leerePosten = tuerme.stream()
                .filter(t -> t.aussenposten() && t.leer()).count();
        StringBuilder sb = new StringBuilder();
        sb.append(Sprache.t("War map: ", "Kriegskarte: ")).append(tuerme.size()).append(Sprache.t(" towers, of which ", " Tuerme, davon "))
          .append(anzahlPosten).append(Sprache.t(" outposts (", " Aussenposten (")).append(leerePosten)
          .append(Sprache.t(" empty) and ", " leer) und "))
          .append(leereNormale).append(Sprache.t(" empty normal ones (0/x). Normal: ", " leere normale (0/x). Normal: "))
          .append(normalGesetzt ? Sprache.t("deployed", "steht") : Sprache.t("open", "offen"))
          .append(" | Titan: ").append(titanGesetzt ? Sprache.t("deployed", "steht") : Sprache.t("open", "offen"));
        long volle = tuerme.stream().filter(Turm::voll).count();
        long gelesen = tuerme.stream().filter(t -> t.drin() >= 0 && t.platz() > 0).count();
        sb.append(Sprache.t(" | full: ", " | voll: ")).append(volle).append(Sprache.t(" (occupancy read at ", " (Belegung gelesen bei "))
          .append(gelesen).append(Sprache.t(" of ", " von ")).append(tuerme.size()).append(')');
        if (!gesperrteTuerme.isEmpty()) {
            sb.append(Sprache.t(" | skipped: ", " | uebersprungen: ")).append(gesperrteTuerme.size());
        }
        Log.info("Clan-Krieg", sb.toString());
    }

    // ==================================================================
    //  Im Truppenfenster
    // ==================================================================

    /**
     * Truppe waehlen und bestaetigen.
     *
     * Normaler Turm: "Deploy the best". Aussenposten: "Previous Squad" --
     * dort steht die Truppe vom letzten Mal, und das ist die richtige.
     */
    private Outcome truppeStellen(Mat screen, BotContext ctx) {
        unbekannteBilder = 0;

        // HIER STAND EINE PRUEFUNG AUF DEN ROTEN APFEL. Sie ist raus.
        //
        // Die Idee war: Ist der Apfel im Knopf rot, reicht das Essen nicht,
        // also gar nicht erst druecken. Nur ist der Apfel im Spiel IMMER
        // rot -- Aepfel sind nun einmal rot. Die Vorlage cw_apple_red ist
        // schlicht ein roter Apfel und passte damit auf jeden Deploy-Knopf.
        //
        // Folge: Der Bot hat JEDEN normalen Turm ausgelassen und dazu
        // behauptet, das Essen reiche nicht. Am 5. September stand im
        // Protokoll "Der Apfel im Knopf ist rot -- das Essen reicht nicht",
        // Autor sah aber einen ganz normalen Knopf: "der apfel knopf war
        // nicht rot".
        //
        // Gebraucht wird die Pruefung ohnehin nicht. Reicht das Essen
        // wirklich nicht, macht das Spiel das Kauffenster auf -- und das
        // faengt Schritt 3 ganz oben in step() ab: dort wird das rote "No"
        // gedrueckt, der Turm gemerkt und uebersprungen. Diamanten sind
        // damit genauso sicher, nur ohne falschen Alarm.
        //
        // Die Lehre: Eine Vorlage, die auf einen ZUSTAND schliessen soll,
        // muss diesen Zustand auch zeigen. Ein roter Apfel zeigt nur einen
        // Apfel.

        // Zuerst die Truppe auswaehlen ...
        //
        // GENAU EINMAL. Der Knopf bleibt naemlich stehen, nachdem er
        // gedrueckt wurde -- er laedt nur die Einheiten in die Felder
        // darueber, er verschwindet nicht. Ohne diese Sperre klickt der Bot
        // ihn wieder und wieder und kommt nie zum Bestaetigen:
        //
        //     03:21:44.325  Truppe vom letzten Mal (Previous Squad)
        //     03:21:45.003  Truppe vom letzten Mal (Previous Squad)
        //     03:21:45.680  Truppe vom letzten Mal (Previous Squad)
        //     ... zehnmal in sechs Sekunden
        //
        // Derselbe Fehler wie damals in der Arena bei "Deploy the best".
        // Zurueckgesetzt wird die Sperre, sobald ein anderer Turm aufgeht.
        //
        // WELCHE AUSWAHL: im normalen Turm "Deploy the best", im
        // Aussenposten "Previous Squad".
        //
        // Der normale Turm bekam bis zum 12.09. GAR KEINE Auswahl. Autor
        // damals: "der bot soll bei einem normalem turm bitte nicht auf
        // deploy the best drücken. einfach nur auf den turm -> deploy squad
        // und auf confirm." Am 12.09. hat er es umgestellt: "jedes mal wenn
        // der bot seine einheiten im clan kireg setzt soll er erst auf
        // deploy best drücken". Beim Aussenposten bleibt es auf seine
        // Nachfrage bei "Previous Squad" -- dort steht die Truppe vom
        // letzten Mal, und das ist die richtige.
        //
        // Steht das Fenster nach Confirm noch, hat Confirm nicht gewirkt (TruppRunden): dann gilt die Sperre nicht mehr.
        // Nach dem Auffuellen nicht neu waehlen -- die Auswahl wuerde die aufgefuellten Plaetze wieder ueberschreiben.
        switch (truppRunden.naechstes(System.currentTimeMillis(),
                de.hustlebot.core.Rechnertempo.mal(TruppRunden.WARTEN_MS))) {
            case WARTEN:
                return Outcome.WAITING;
            case NOCHMAL:
                Log.warn("Clan-Krieg", Sprache.t("Confirm had no effect, the squad window is still open -- selecting the squad again ("
                                + truppRunden.fehlschlaege() + " of " + TruppRunden.MAX_RUNDEN + ").",
                        "Confirm hat nicht gewirkt, das Truppenfenster steht noch -- die Truppe wird noch einmal gewaehlt ("
                                + truppRunden.fehlschlaege() + " von " + TruppRunden.MAX_RUNDEN + ")."));
                testFoto(ctx, "confirm-ohne-wirkung");
                aufstellungZuruecknehmen();
                if (!auffuellen.versucht()) {
                    truppeGewaehlt = false;
                    auswahlGesuchtSeit = 0;
                }
                break;
            case AUFGEBEN:
                warnung(Sprache.t("Confirm had no effect " + TruppRunden.MAX_RUNDEN + " times -- this tower is given up and another one is tried.",
                        "Confirm hat " + TruppRunden.MAX_RUNDEN + " Mal nicht gewirkt -- dieser Turm wird aufgegeben und ein anderer probiert."));
                testFoto(ctx, "confirm-aufgegeben");
                aufstellungZuruecknehmen();
                merkeGesperrt(Sprache.t("Confirm had no effect", "Confirm ohne Wirkung"));
                schliesseSpaeter();
                return Outcome.ACTED;
            default:
                break;
        }
        if (!truppeGewaehlt) {
            boolean aussenposten = offenerTurm == Sorte.AUSSENPOSTEN;
            // Welcher Knopf: Autors Einstellung je Stelle (!settings). Standard
            // wie bisher -- Turm "Deploy the best", Aussenposten "Previous Squad".
            // Autor 26.10.: Outpost mit Element-Pflicht -> "Deploy the best" (die passenden Titanen), sonst wie eingestellt.
            boolean previous = !(aussenposten && elementPflicht) && !truppRunden.lieberDieStaerksten() && Aufstellung.istPrevious(
                    aussenposten ? "clanwar_outpost" : "clanwar_tower");
            String auswahl = previous ? T_PREVIOUS_SQUAD : T_DEPLOY_BEST;
            long jetzt = System.currentTimeMillis();
            if (auswahlGesuchtSeit == 0) auswahlGesuchtSeit = jetzt;
            if (jetzt - auswahlGesuchtSeit < de.hustlebot.core.Rechnertempo.mal(FENSTER_RUHE_MS)) return Outcome.WAITING;
            if (klickVorlage(screen, ctx, auswahl, previous
                    ? Sprache.t("Squad from last time (Previous Squad)", "Truppe vom letzten Mal (Previous Squad)")
                    : Sprache.t("Strongest squad (Deploy the best)", "Staerkste Truppe (Deploy the best)"))) {
                truppeGewaehlt = true;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
            // Nicht gleich ohne Auswahl bestaetigen: Der Knopf kommt mit dem
            // Fenster, im ersten Bild ist er manchmal noch nicht zu sehen.
            // Kommt er gar nicht, wird mit der Truppe bestaetigt, die in den
            // Feldern steht -- lieber so aufgestellt als gar nicht.
            // Pruefer 29.10.: x Rechnertempo (Server bis 5x langsamer), sonst wurde dort ohne Auswahl bestaetigt.
            if (jetzt - auswahlGesuchtSeit < de.hustlebot.core.Rechnertempo.mal(AUSWAHL_WARTEN_MS)) return Outcome.WAITING;
            warnung(Sprache.t("The button " + auswahl + " is not visible. Confirming with "
                    + "the squad that is in the slots.",
                    "Der Knopf " + auswahl + " ist nicht zu sehen. Es wird mit "
                    + "der Truppe bestaetigt, die in den Feldern steht."));
            truppeGewaehlt = true;
        }

        // Autor 22.10. abends: nach "No" auf die Warnung erst die freien Plaetze auffuellen, bis der Zaehler voll ist.
        if (auffuellen.aktiv()) {
            Outcome o = auffuellen.schritt(screen, ctx);
            if (o != null) return o;
        }

        // ... dann bestaetigen.
        //
        // Der Knopf heisst NICHT ueberall gleich: im normalen Turm "Deploy"
        // mit dem Apfelpreis darunter, im Aussenposten schlicht "Confirm".
        // Beide werden probiert, sichtbar ist ohnehin nur einer.
        // Der gruene "Confirm" ist ueberall im Spiel derselbe Knopf. Autor
        // hat ihn laengst aufgenommen -- nur unter den Namen der anderen
        // Modi ("confirm" im Verlies, "ar_confirm" in der Arena). Der
        // Matcher liest alle Unterordner gemeinsam ein, also lassen sie sich
        // hier einfach mitbenutzen, statt denselben Knopf ein drittes Mal
        // aufnehmen zu lassen.
        //
        // Gefaehrlich ist das nicht: Hierher kommt der Bot nur, wenn das
        // Truppenfenster offen ist UND die Truppe schon in den Feldern
        // steht. Ein "Confirm" bedeutet an dieser Stelle nichts anderes.
        // PREIS MERKEN (Autor, 27.09.: "im Thronsaal 9 kostet es weniger, der
        // Preis veraendert sich"). Unter "Deploy" steht, was der normale
        // Trupp an Nahrung kostet -- fuer die Pruefung vor dem naechsten Krieg.
        if (ctx.matcher().has(T_DEPLOY_COST)) {
            ctx.matcher().find(screen, T_DEPLOY_COST).ifPresent(k -> Nahrungskisten.preisLesen(ctx, k));
        }
        for (String knopfName : BESTAETIGEN) {
            if (klickVorlageWennBekannt(screen, ctx, knopfName,
                    Sprache.t("Confirm the deployment (" + knopfName + ")", "Aufstellen bestaetigen (" + knopfName + ")"))) {
                // Merken, WO und WELCHE Sorte -- damit das Schild "Your
                // squad" gleich richtig zugeordnet wird und nicht ueber
                // geratene Abstaende.
                zuletztGesetzt = offenePosition;
                zuletztGesetzteSorte = offenerTurm;
                // In DIESEM Turm steht jetzt der Trupp: nicht mehr nach einem
                // freien Platz wischen, nur noch zumachen (Autor, 27.09.).
                geradeGesetzt = true;
                geradeGesetztUm = System.currentTimeMillis();
                truppRunden.confirmGedrueckt(geradeGesetztUm);

                // MITZAEHLEN, WAS ER SELBST GETAN HAT.
                //
                // Das ist der Beweis, der nicht verblassen kann. Am
                // 5. September lief der Bot dreimal hintereinander in
                // dieselbe Falle: Beide Truppen standen, aber das obere
                // Schild "Your squad" lag halb auf einem dunklen Band und
                // wurde nicht erkannt -- also klapperte er weiter Turm um
                // Turm ab. Autor: "du musst irgendwie einbauen das der bot
                // es checkt das er zwei teams gesetzt hat."
                //
                // Hier zaehlt er es mit. Zwei bestaetigte Aufstellungen
                // sind zwei Truppen: Mehr als zwei Plaetze gibt es auf der
                // ganzen Karte nicht, und bestaetigt wird nur, wenn das
                // Truppenfenster wirklich offen war und der gruene Knopf
                // wirklich getroffen wurde.
                //
                // Das Schild bleibt daneben stehen und zaehlt weiterhin --
                // es ist der bessere Beweis, wenn es zu sehen ist. Nur ist
                // "nicht zu sehen" eben kein Gegenbeweis mehr.
                //
                // Server 29.10., 20:32: "Deploy" zweimal gedrueckt (der erste Klick wirkte erst spaet), beide gezaehlt --
                // "2 von 2" hiess "fertig", der Titanen-Trupp wurde nie gesetzt. Jetzt zaehlt jede Sorte nur einmal.
                boolean titan = offenerTurm == Sorte.AUSSENPOSTEN;
                boolean neu = zaehler.bestaetigt(offenerTurm == null ? null : titan);
                bestaetigt = zaehler.anzahl();
                if (offenerTurm != null) geprueft.add(offenerTurm);   // nach den Regeln gesetzt -- steht richtig
                if (neu) {
                    Log.info("Clan-Krieg", Sprache.t("Deployment " + bestaetigt + " of 2 "
                            + "confirmed (" + (titan ? "titan outpost" : "normal tower") + ").",
                            "Aufstellung Nummer " + bestaetigt + " von 2 "
                            + "bestaetigt (" + (titan ? "Titanen-Aussenposten" : "normaler Turm") + ")."));
                } else {
                    Log.info("Clan-Krieg", Sprache.t("Confirm pressed again for the " + (titan ? "titan squad" : "normal squad")
                                    + " -- the same deployment, it does not count twice (" + bestaetigt + " of 2).",
                            "Bestaetigen noch einmal gedrueckt fuer den " + (titan ? "Titanen-Trupp" : "normalen Trupp")
                                    + " -- dieselbe Aufstellung, sie zaehlt nicht doppelt (" + bestaetigt + " von 2)."));
                }

                ctx.sleepUntilChanged(2000);
                return Outcome.ACTED;
            }
        }

        // Laut, nicht leise.
        //
        // Hier stand eine DEBUG-Zeile. Der Bot konnte also minutenlang vor
        // einem offenen Truppenfenster stehen, ohne dass im Protokoll etwas
        // dazu auftauchte -- und ein Krieg laesst sich nur einmal
        // aufstellen. Jetzt sagt er einmal deutlich, welche Vorlage fehlt.
        if (!knopfGemeldet) {
            knopfGemeldet = true;
            // Die Auswahl ist hier schon erledigt (oder nach kurzem Warten
            // uebersprungen) -- fehlen kann nur der Bestaetigungsknopf.
            warnung(Sprache.t("The squad window is open and the squad is in "
                    + "the slots, but this is missing: " + String.join(" or ", BESTAETIGEN)
                    + ". Deploying is not possible like this -- please capture it with Capture-Images.exe.",
                    "Das Truppenfenster steht offen und die Truppe steht in "
                    + "den Feldern, aber es fehlt: " + String.join(" oder ", BESTAETIGEN)
                    + ". Aufstellen geht so nicht -- bitte mit Capture-Images.exe aufnehmen."));
        }
        return Outcome.WAITING;
    }

    // ==================================================================
    //  Kleinkram
    // ==================================================================

    /**
     * Merkt sich, dass der gerade offene Turm nicht geht.
     *
     * Ohne den Schluessel des tatsaechlich offenen Turms waere das
     * wirkungslos: die Auswahl auf der Karte filtert nach genau diesem
     * Schluessel, und ein erfundener Eintrag wuerde nie passen. Der Bot
     * haette denselben Turm endlos wieder geoeffnet.
     */
    private void merkeGesperrt(String grund) {
        if (offenePosition == null) {
            Log.debug("Clan-Krieg", "Turm faellt aus (" + grund + "), aber es ist nicht "
                    + "bekannt, welcher -- nichts zu merken.");
            return;
        }
        String schluessel = offenePosition.schluessel();
        if (!gesperrteTuerme.contains(schluessel)) {
            gesperrteTuerme.add(schluessel);
            Log.info("Clan-Krieg", Sprache.t("Tower at " + offenePosition.x() + ","
                    + offenePosition.y() + " is skipped: " + grund + ".",
                    "Turm bei " + offenePosition.x() + ","
                    + offenePosition.y() + " wird uebersprungen: " + grund + "."));
        }
    }

    /**
     * Wie merkeGesperrt, aber nur WEGEN DES NAMENS.
     *
     * Der Unterschied ist wichtig. Ein Turm mit rotem Kasten geht wirklich
     * nicht -- da hilft kein zweiter Versuch. Ein Turm auf Autors
     * Sperrliste dagegen ginge schon, er waere nur nicht die erste Wahl.
     *
     * Autor: "es kann auch sein das alle türme voll sind, dann muss der bot
     * mal einen turm wählen mit dieser überschrift von vorhin ... als not
     * aus quasi."
     *
     * Deshalb werden diese Sperren getrennt gefuehrt. Findet sich sonst
     * nichts mehr, werden sie wieder aufgehoben -- lieber ein Trupp im
     * ungeliebten Tempel als gar keiner.
     */
    /**
     * Wie viele Bilder ein Abschluss-Schritt auf seinen Knopf warten darf.
     *
     * Acht Bilder sind mit der Bedenkzeit rund acht Sekunden -- genug fuer
     * jede Animation, und kurz genug, dass ein fehlender Knopf den Bot
     * nicht festhaelt. Laeuft die Geduld ab, geht es trotzdem weiter: Der
     * naechste Modus findet auch von der Kriegskarte aus zurueck, es dauert
     * nur laenger.
     */
    private static final int ABSCHLUSS_GEDULD = 8;

    private void naechsterAbschlussSchritt() {
        abschlussSchritt++;
        abschlussBlicke = 0;
    }

    /** @return true, solange noch gewartet werden soll */
    private boolean abschlussWarten() {
        abschlussBlicke++;
        if (abschlussBlicke < ABSCHLUSS_GEDULD) return true;
        Log.info("Clan-Krieg", Sprache.t("Wrap-up step " + abschlussSchritt + ": the button did not "
                + "appear within " + ABSCHLUSS_GEDULD + " pictures. Carrying on "
                + "without it.",
                "Abschluss-Schritt " + abschlussSchritt + ": der Knopf kam "
                + "in " + ABSCHLUSS_GEDULD + " Bildern nicht ins Bild. Es geht ohne ihn "
                + "weiter."));
        naechsterAbschlussSchritt();
        return false;
    }

    /** Beim Ansehen stand in diesem Turm ein Name, aber nicht der eigene (Autor 23.10.: der Turm gehoert dem anderen). */
    /** So oft hintereinander eine Karte ganz ohne Aussenposten gelesen (ab 3: diesen Krieg keine Titanen). */
    private int ohneAussenposten;

    /** Beim Ansehen: die meisten Spieler zuerst, sonst von links nach rechts. Unbekannte Belegung (-1) zaehlt wie leer. */
    static java.util.Comparator<Turm> ansehReihenfolge() {
        return java.util.Comparator.<Turm>comparingInt(t -> -Math.max(t.drin(), 0)).thenComparingInt(Turm::x);
    }

    /** Noch nie geoeffnete, nicht gesperrte Tuerme von links nach rechts. */
    private List<Turm> ungeseheneTuerme(List<Turm> tuerme) {
        return tuerme.stream()
                .filter(tt -> !erkundet.containsKey(tt.schluessel()) && !gesperrteTuerme.contains(tt.schluessel()))
                .sorted(java.util.Comparator.comparingInt(Turm::x))
                .toList();
    }

    /**
     * Gibt es fuer den Titanen-Trupp ueberhaupt etwas? Nicht unter Thronsaal 7 (Titanen gibt es erst ab 7, Autor 25.10.)
     * und nicht, wenn die Karte dreimal hintereinander keinen einzigen Aussenposten zeigt (Krieg 26.10. 4:00).
     */
    private boolean titanMoeglich() {
        // Autor 27.10.: Schalter clanwar-kein-titan.txt im Bot-Ordner -> nie nach Titan-Aussenposten suchen (Zweitkonten
        // Zweitkonto A/Drittkonto: kein bzw. zu schwaches Titan-Team; sonst scrollte der Bot 120 s vergeblich).
        if (java.nio.file.Files.exists(de.hustlebot.core.Ordner.bei("clanwar-kein-titan.txt"))) return false;
        if (de.hustlebot.core.Thronsaal.fehltJetzt(de.hustlebot.core.Thronsaal.Teil.TITANENTURM)) return false;
        // Server 30.10.: erst, wenn jeder Turm einmal offen war -- der Titel im Fenster ist sicherer als die Schrift auf der Karte.
        if (ohneAussenposten >= 3 && !nochUngesehen) {
            if (!keineAussenpostenGemeldet) {
                keineAussenpostenGemeldet = true;
                Log.info("Clan-Krieg", Sprache.t("This war has no outposts -- no titan squad to deploy.",
                        "Dieser Krieg hat keine Aussenposten -- kein Titanen-Trupp zu setzen."));
            }
            return false;
        }
        return true;
    }
    private boolean keineAussenpostenGemeldet;
    /** Gibt es auf der Karte Tuerme, die der Bot noch nicht geoeffnet hat (und noch oeffnen darf)? */
    private boolean nochUngesehen;

    /**
     * Code-Review 26.10.: Titan steht -- ODER ist gar nicht moeglich (TR5-Konto ohne Titanenturm, Krieg ohne Aussenposten).
     * Die Fertig-Regel fragte nur titanGesetzt ab: ohne Titan-Moeglichkeit blieb der Modus fuer immer in der
     * Aussenposten-Phase (offeneSorten leer, return ACTED, kein Abschluss) -- bei den TR5-Konten in JEDEM Krieg.
     */
    private boolean titanErledigt() {
        return titanGesetzt || !titanMoeglich();
    }

    private boolean fremderName(Turm t) {
        String ich = MeinTeam.spielername();
        if (ich == null || ich.isBlank()) return false;
        TurmInfo i = erkundet.get(t.schluessel());
        if (i == null || i.namen() == null || i.namen().isBlank() || enthaeltNamen(i.namen(), ich)) return false;
        // Autor 03.11.: "2 T11's FILL" / "LEAVE SPACE FOR 8 TR11 TITANS" sind Stufen-Hinweise der Clan-Verwaltung, keine
        // Spielernamen -- um 02:20 blieben deshalb alle drei Outposts (je 2/4) leer. Passt die eigene Thronsaal-Stufe, ist
        // der Turm frei; "T7 and BELOW" bleibt fuer Autor (TR11) gesperrt.
        int[] stufen = stufenNotiz(i.namen());
        if (stufen != null) {
            int meine = eigeneStufe(ich);
            boolean passt = meine <= 0 || (meine >= stufen[0] && meine <= stufen[1]);
            if (fremdGemeldet.add(t.schluessel())) {
                Log.info("Clan-Krieg", Sprache.t("Tower with \"" + i.namen() + "\" is a throne room note (TR" + stufen[0]
                                + (stufen[1] == stufen[0] ? "" : stufen[1] >= 99 ? "+" : "-" + stufen[1]) + "), own level "
                                + (meine > 0 ? meine : "unknown") + " -- " + (passt ? "may be used." : "not used."),
                        "Turm mit \"" + i.namen() + "\" ist ein Thronsaal-Hinweis (TR" + stufen[0]
                                + (stufen[1] == stufen[0] ? "" : stufen[1] >= 99 ? "+" : "-" + stufen[1]) + "), eigene Stufe "
                                + (meine > 0 ? meine : "unbekannt") + " -- " + (passt ? "darf genommen werden." : "wird nicht genommen.")));
            }
            return !passt;
        }
        if (fremdGemeldet.add(t.schluessel())) {
            Log.info("Clan-Krieg", Sprache.t("Tower with \"" + i.namen() + "\" is reserved for someone else -- not used.",
                    "Turm mit \"" + i.namen() + "\" ist fuer jemand anderen reserviert -- wird nicht genommen."));
        }
        return true;
    }

    private final java.util.Set<String> fremdGemeldet = new java.util.HashSet<>();

    /** Thronsaal des Spielers: vom Team-Scan gemerkt (thronsaal.txt), sonst das gerade gespielte Konto, -1 = unbekannt. */
    private static int eigeneStufe(String ich) {
        int gemerkt = de.hustlebot.core.Thronsaal.stufe(ich);
        return gemerkt > 0 ? gemerkt : de.hustlebot.core.Thronsaal.aktuell();
    }

    /** Nennt die Notiz eine Thronsaal-Stufe, und passt die eigene (bekannte) Stufe genau dazu? */
    static boolean stufePasst(String notiz, int meine) {
        int[] s = stufenNotiz(notiz);
        return s != null && meine > 0 && meine >= s[0] && meine <= s[1];
    }

    private static boolean stufePasst(String notiz, String ich) {
        return stufePasst(notiz, eigeneStufe(ich));
    }

    // Autor 03.11.: "1 T11, 2T11, 2TR11 -- alle moeglichen Schreibweisen". Vor dem T darf eine Zahl kleben, aber kein Buchstabe
    // ("MeinClan"), nach der Stufe kein Buchstabe ("Titans").
    private static final java.util.regex.Pattern STUFE = java.util.regex.Pattern.compile("(?<![A-Z])T\\s?R?\\s?([0-9IL|!]{1,2})(?:['’]?S)?(?![A-Z0-9])");
    private static final java.util.regex.Pattern STUFEN_WORT = java.util.regex.Pattern.compile(
            "BELOW|UNDER|LOWER|ABOVE|HIGHER|FILL|LEAVE|SPACE|TITAN|ONLY");

    /**
     * Ist die Notiz der Clan-Verwaltung ein Thronsaal-Hinweis statt Spielernamen? Liefert {von, bis} (99 = nach oben offen),
     * sonst null. Beispiele vom 03.11.: "2 Til's FILL" (gelesen fuer "2 T11's FILL") -> {11, 11}, "LEAVE SPACE ; FOR 8 TRII"
     * -> {11, 11}, "T7 and BELOW" -> {1, 7}, "T9 & Below" -> {1, 9}, "T10+" -> {10, 99}. Ein Spieler "Til" allein bleibt ein
     * Name: ohne echte Ziffer in der Stufe muss ein Stufen-Wort dabei sein.
     */
    static int[] stufenNotiz(String notiz) {
        if (notiz == null || notiz.isBlank()) return null;
        String g = notiz.toUpperCase(java.util.Locale.ROOT);
        java.util.regex.Matcher m = STUFE.matcher(g);
        int von = 99, bis = 0;
        boolean ziffer = false;
        while (m.find()) {
            String roh = m.group(1);
            int n;
            try {
                n = Integer.parseInt(roh.replaceAll("[IL|!]", "1"));
            } catch (NumberFormatException e) {
                continue;
            }
            if (n < 1 || n > 30) continue;
            ziffer |= roh.matches(".*\\d.*");
            von = Math.min(von, n);
            bis = Math.max(bis, n);
        }
        if (bis == 0) return null;
        boolean wort = STUFEN_WORT.matcher(g).find();
        if (!ziffer && !wort) return null;
        if (g.contains("BELOW") || g.contains("UNDER") || g.contains("LOWER")) return new int[]{1, bis};
        if (g.contains("ABOVE") || g.contains("HIGHER") || g.matches(".*\\d\\s?\\+.*")) return new int[]{von, 99};
        return new int[]{von, bis};
    }

    /** Wurde der Turm wirklich gelesen? (Ein Eintrag ohne Titel und Namen heisst: Lese-Fenster ging nicht auf.) */
    static boolean gelesen(TurmInfo i) {
        return i != null && !(i.titel().isBlank() && (i.namen() == null || i.namen().isBlank()));
    }

    /**
     * Pruefer 29.10.: Wahl beim Ausweichen unter den freien Tuermen (fremde sind schon heraus). Ein UNGELESENER Turm kann
     * fuer einen Mitspieler reserviert sein -- darum zuerst die gelesenen, dann erst die ungelesenen. Darin wie bisher:
     * leere zuerst, sonst der mit den wenigsten Spielern (Autor 17.10.), bei Gleichstand der erste.
     */
    static Optional<Turm> ausweichWahl(List<Turm> passende, java.util.function.Predicate<Turm> gelesen) {
        for (boolean nurGelesene : new boolean[]{ true, false }) {
            for (boolean nurLeere : new boolean[]{ true, false }) {
                Optional<Turm> t = passende.stream()
                        .filter(tt -> !nurGelesene || gelesen.test(tt))
                        .filter(tt -> !nurLeere || tt.leer())
                        .min(java.util.Comparator.comparingInt(tt -> tt.drin() < 0 ? 99 : tt.drin()));
                if (t.isPresent()) return t;
            }
        }
        return Optional.empty();
    }
    /** Art je Turm aus dem Namen: {x, y, 1 = Outpost / 0 = normal / -1 = nicht lesbar} (Autor 23.10.: "Struggle road"). */
    private final List<int[]> artKorrektur = new ArrayList<>();

    /**
     * Der Name unter der Zahl auf der Kriegskarte ("Struggle road", "Wrath Outpost"): 1 = Outpost, 0 = normal, -1 = nicht
     * lesbar. Live gemessen 23.10.: Name ~12 px unter der Zahlmitte, 240 x 24, dreifach vergroessert gut lesbar.
     */
    private static int artAmNamen(BotContext ctx, Matcher.Hit zahl) {
        java.awt.image.BufferedImage b = ctx.frame();
        if (b == null) return -1;
        // Server 30.10.: "Temple of the Unstoppable Elements" ist rund 300 Punkte breit, 240 schnitten "Temple" ab -- der Tempel galt
        // als normaler Turm und "dieser Krieg hat keine Aussenposten", der Titan blieb ungesetzt.
        int w = (int) Math.round(380.0 / 1159 * ctx.width()), h = (int) Math.round(24.0 / 770 * ctx.height());
        int x = Math.max(0, zahl.centerX() - w / 2), y = zahl.centerY() + (int) Math.round(12.0 / 770 * ctx.height());
        w = Math.min(w, b.getWidth() - x);
        if (w < 40 || y + h > b.getHeight()) return -1;
        java.awt.image.BufferedImage gross = new java.awt.image.BufferedImage(w * 3, h * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(b.getSubimage(x, y, w, h), 0, 0, w * 3, h * 3, null);
        g.dispose();
        String t = String.join(" ", de.hustlebot.core.Texterkennung.lies(gross)).toLowerCase(java.util.Locale.ROOT);
        if (t.replaceAll("[^a-z]", "").length() < 5) return -1;
        return titanenName(t) ? 1 : 0;
    }

    /**
     * Ist das der Name eines Titanen-Turms? "... Outpost" / "...posten" -- und Pruefer 29.10.: auch die Tempel
     * ("Temple of the Unstoppable/Rampaging Elements", dort duerfen nur Titanen hinein, TitanRauswurfTest: 6/6).
     * Vorher galt ein Tempel am Namen als normaler Turm, und das ueberschrieb sogar das richtige Bild.
     */
    static boolean titanenName(String name) {
        if (name == null) return false;
        String t = name.toLowerCase(java.util.Locale.ROOT);
        String nurBuchstaben = t.replaceAll("[^a-z]", "");
        return nurBuchstaben.contains("outpost") || t.contains("posten")
                || nurBuchstaben.contains("temple") || nurBuchstaben.contains("tempel")
                // Abgeschnitten gelesen ("ple of the Unstoppable Eleme"): die Tempel heissen "... Unstoppable/Rampaging Elements".
                || nurBuchstaben.contains("unstoppable") || nurBuchstaben.contains("rampaging") || nurBuchstaben.contains("element");
    }

    private void merkeNamensGesperrt(String grund) {
        if (offenePosition == null) return;
        String schluessel = offenePosition.schluessel();
        merkeGesperrt(grund);
        if (!nurNamensGesperrt.contains(schluessel)) {
            nurNamensGesperrt.add(schluessel);
        }
    }

    /**
     * Merkt vor, dass das Turmfenster zu soll -- geklickt wird erst im
     * naechsten Schritt.
     */
    private void schliesseSpaeter() {
        offenerTurm = null;
        offenePosition = null;
        turmSchliessen = true;
    }
}
