package de.hustlebot.bot;

import de.hustlebot.core.GemBayplan;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Die Edelsteinbucht (Gem Bay), Autor 29.09.
 *
 * Ablauf, so einfach wie moeglich:
 *
 *   Burg: Nahrung ablesen -> Map -> "Gem Bay" -> Preis unter "Start journey":
 *     - Diamanten statt Nahrung: heute schon gespielt -> X, fertig.
 *     - zu wenig Nahrung: X, Burg, Food-Kisten (Tasche, sonst Clan-Laden),
 *       dann noch einmal hin. Reicht es danach immer noch nicht: fuer heute aus.
 *     - genug: "Start journey" -> "Deploy the best ones" -> "Confirm" -> ggf. "Yes".
 *   In der Bucht ("Victories x/15"):
 *     - "Battle begins in ..." -> druecken (Wartezeit ueberspringen).
 *     - "Pause" zu sehen -> Automodus laeuft, NICHT druecken.
 *     - "Auto" zu sehen -> erst nach links wischen: ist ein Platz frei (Einheit
 *       gestorben), die erste lebende Einheit von links aus der Reserve
 *       hineinziehen. Keine mehr da und alle tot -> "Restore all units" (Video,
 *       nichts anklicken). Dann "Auto" -> "To battle!".
 *   "Journey over!" -> "Claim reward" -> Burg -> NOCH EINMAL (Autor, 29.09.: "gem bay kann
 *   man taeglich mehrmals spielen, nach jedem Mal wird der Food-Preis teurer, bis er
 *   Diamanten kostet -- ab dann nicht mehr weiter"). Erst beim Diamant-Preis ist Gem Bay
 *   fuer heute erledigt.
 *
 * NIE: "End journey" / oranges "Claim reward" in der Bucht (beendet die Reise
 * vorzeitig), und nie ein Preis in Diamanten.
 *
 * Grenzen (Pruefung 04.10., doku\DAUERLAUF-CHECK.md; Autor 05.10.: "so jetzt kannst du den bot
 * starten und die edelsteinbucht testen") -- die Zahlen stehen in GemBayGrenzen:
 *   - hoechstens 45 Minuten je Besuch (Autor, 06.10.: so viele Reisen, bis der Preis in
 *     Diamanten steht; vorher 15), danach sauber zurueck in die Burg; steigen die Siege
 *     noch, darf die laufende Reise zu Ende (hoechstens 60 Minuten); "Start journey" nur mit
 *     mindestens 4 Minuten Besuchszeit uebrig (Pruefung 05.10., Lauf 1 06.10.: 2,6 Minuten je Reise);
 *
 * Tempo (Autor, 06.10.: "verkuerz Wartezeiten bis es nicht mehr geht, bau Fotozyklen und
 * Multi-/Vorausklicks ein, Kastenaufnahme wo es nur geht"):
 *   - Im Kampf und in der Bucht laeuft ein FOTOZYKLUS im Modus (fotozyklus): alle 100 ms wird
 *     nur der kleine Kasten um "Battle begins" fotografiert (BotContext.kastenAufnahme), bei
 *     Treffer sofort geklickt und bis zu 3-mal nachgeklickt, solange der Knopf noch da ist.
 *     Alle 2 s kommt trotzdem ein ganzes Bild als Sicherheitsblick (Kauf-Fenster, Stoerfenster,
 *     Yes-Dialog, Einheit tot) -- der Kastenmodus darf nichts uebersehen, was Geld kosten koennte.
 *   - Nach "Start journey" / "Deploy" / "Confirm" wartet der Bot per Kastenblick nur auf den
 *     naechsten erwarteten Knopf und drueckt ihn sofort (Vorausklick mit Vorlagen-Bestaetigung).
 *   - NIE mehrfach: "Start journey", "Yes", "Auto" (EINZELKLICK_SPERRE_MS). Mehrfach erlaubt:
 *     "Battle begins", "Deploy the best ones", "Confirm", gruenes "Claim reward".
 *   - "Pause" (Automodus laeuft) wird nie gedrueckt; "Quick Attack!" gibt es in der Bucht nicht.
 *   - nie das rote X, solange ein Kampf laufen kann (P1);
 *   - hoechstens 6 Klicks auf "Gem Bay" in der Weltkarte ohne Fortschritt (P2);
 *   - jeder Besuch endet mit einem Vermerk: erledigt, oder ein Fehlversuch -- beim
 *     zweiten Fehlversuch im selben Zeitfenster ist es auch erledigt (P4, GemBayplan);
 *   - kommt das Video nicht zurueck: nach 90 s normal weiter, nie ohne Ende anhalten,
 *     nie ein Klick in die Werbung, Autor bekommt Bescheid (P8);
 *   - "Yes" nur nach Textpruefung, nie mit Preis (P9);
 *   - kein Fenster haelt den Besuch ohne Grenze fest ("Journey over!" ohne "Claim",
 *     Kauf-Fenster ohne "No"), und der Totmann im Motor kommt nie vor dem Heimweg.
 */
public final class GemBayMode implements GameMode {

    private static final String WO = "Gem Bay";

    static final String T_KARTE = "gb_karte";           // "Gem Bay" auf der Weltkarte
    static final String T_START = "gb_start";           // "Start journey"
    static final String T_APFEL = "gb_preis_apfel";     // Apfel im Preis unter "Start journey"
    static final String T_DIAMANT = "gb_preis_diamant"; // Diamant im Preis -> heute schon gespielt
    static final String T_DEPLOY = "gb_deploy_best";    // "Deploy the best ones"
    static final String T_CONFIRM = "gb_confirm";       // "Confirm"
    static final String T_YES = "gb_yes";               // "Yes" in der Warnung (kommt nicht immer)
    static final String T_VICTORIES = "gb_victories";   // die Leiste "Victories" oben in der Bucht (auf dunklem Grund)
    /**
     * Dasselbe Wort auf BLAUEM Grund (templates\gelernt\gembay, aus Foto 151415): der Balken
     * fuellt sich mit jedem Sieg blau, ab etwa 5/15 liegt das Wort auf Blau und die dunkle Vorlage
     * passt nicht mehr (Lauf 1, 06.10.: ab Sieg 5 "Bildschirm nicht erkannt", 10 Kaempfe lang
     * keine Wartezeit uebersprungen). Ueber matcher.serie(T_VICTORIES) zaehlen beide -- und jede
     * spaetere gb_victories_2 ... automatisch mit.
     */
    static final String T_VICTORIES_BLAU = "gb_victories_blau";
    static final String T_AUTO = "gb_auto";             // "Auto" (Automodus aus)
    static final String T_PAUSE = "gb_pause";           // "Pause" (Automodus laeuft) -- Autor 06.10.: NIE druecken
    static final String T_BEGINNT = "gb_battle_begins"; // "Battle begins in: 7s"
    static final String T_TO_BATTLE = "gb_to_battle";   // "To battle!" in "Auto Battle Settings"
    static final String T_FREI = "gb_platz_frei";       // freier Platz (+) in "Current squad"
    static final String T_RESERVE = "gb_reserve";       // das Wort "Reserve:"
    static final String T_WATCH = "gb_watch";           // "Restore all units! Watch"
    static final String T_ENDE = "gb_journey_over";     // "Journey over!"
    static final String T_CLAIM = "gb_claim";           // gruenes "Claim reward" im Fenster "Journey over!"
    static final String T_BELOHNUNG = "gb_belohnung";   // "You have received a reward" in der Burg danach
    static final String T_RESET = "gb_reset";           // "Journey cost resets in:" -- rechts daneben die Zeit
    /**
     * Ein Stueck des Kampfbilds (gibt es noch nicht; aus logs\gembay-kampf-*.png auszuschneiden).
     * Solange es zu sehen ist, laeuft sicher ein Kampf: nie das rote X (P1, Pruefung 05.10.).
     */
    static final String T_KAMPF = "gb_kampf";
    private static final String T_X = "close_x";
    /** Einmaliger Kisten-Test (Autor, 06.10.), siehe fenster(). */
    private static final java.nio.file.Path KISTEN_TEST = java.nio.file.Path.of("gembay-kisten-test.txt");
    private boolean kistenTest;
    /** Das Kauf-Fenster "Buy the missing resources?" und sein "No" (aus dem Clan-Krieg, gibt es schon). */
    private static final String T_KAUF = "cw_buy_resources";
    private static final String T_KAUF_NEIN = "cw_buy_no";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";
    /** Das orange "Claim reward" oben rechts in der Bucht: beendet die Reise vorzeitig (Autor 17.10.). */
    static final String T_CLAIM_OBEN = "gb_claim_oben";
    /** "Journey over!" im Fenster "no one left to continue" und sein ROTES "Claim reward" (Live-Test 17.10.). */
    static final String T_ENDE_LEER = "gb_ende_leer";
    static final String T_CLAIM_ROT = "gb_claim_rot";
    /** Danach "Gem Bay chest": "By claiming the reward, you will end the journey!" und gruenes "Claim reward" mit Kiste. */
    static final String T_KISTE_SATZ = "gb_kiste_satz";
    static final String T_CLAIM_KISTE = "gb_claim_kiste";
    /** Dann noch "Warning! By claiming the reward, you will end the journey!" mit gruenem "Claim reward" und "Cancel". */
    static final String T_WARNUNG_SATZ = "gb_warnung_satz";
    static final String T_CLAIM_WARNUNG = "gb_claim_warnung";
    /**
     * Autor 17.10.: Die Reserve-Einheiten (beim Video noch lebend) als Bilder -- nach der Wiederbelebung
     * kommen erst die Team-Einheiten hoch, die Reserve erst, wenn keine Team-Einheit mehr lebt.
     */
    private static final java.nio.file.Path RESERVE_ORDNER = java.nio.file.Path.of("gembay-reserve");
    /** Wann zuletzt "Watch" gedrueckt wurde (Millisekunden) -- ein Neustart wartet dann weiter auf die Wiederbelebung. */
    private static final java.nio.file.Path VIDEO_UM = RESERVE_ORDNER.resolve("video-um.txt");
    /** So aehnlich muss ein Bild einem gemerkten Reserve-Bild sein (Graustufen-Korrelation). */
    private static final double RESERVE_GLEICH = 0.80;
    /** Nach dem Video: so oft kurz raus aus der Bucht und wieder hinein, bis die Einheiten leben (Autor 17.10.). */
    private static final long NEU_BETRETEN_MS = 60_000;
    /** So lange ab "Watch" auf die Wiederbelebung warten; danach geht es ohne weiter (Reserve, dann Claim reward). */
    private static final long WIEDERBELEBUNG_MAX_MS = 12 * 60_000L;

    /** Sicherheitsgrenze: so viele Reisen hoechstens in einem Durchgang. */
    /**
     * Autor, 06.10.: "spiel so viel Edelsteinbucht wie nur geht, bis du Diamanten ausgeben musst"
     * -- 12 ist nur die Sicherheitsgrenze (30.09. bis 05.10. waren es 2). Die eigentliche Bremse
     * bleibt der Preis: steht er in Diamanten oder ohne Apfel (fenster(), preisArt), wird nie
     * gestartet; reicht die Nahrung nicht, erst Nahrungskisten. gembay-reisen.txt kann die Zahl
     * ohne Code aendern (maxReisen).
     */
    private static final int MAX_REISEN = 12;
    /**
     * Feste Kaesten im 1159x770-Raum fuer die Kastenaufnahme (aus Lauf 1, 06.10.; Fotos
     * logs\gembay-kampf-151415.png): "Battle begins" sass immer mit der Mitte bei 895,627
     * (Vorlage 159x28), "Journey over!"/"Claim reward" bei 457,585 (gb_claim 191x85). Mit Luft
     * drumherum. Kennt der Matcher die Stelle schon (gemerkt in dieser Fenstergroesse), gilt die.
     */
    private static final int[] K_BEGINNT = {775, 582, 240, 90};
    private static final int[] K_CLAIM = {300, 520, 320, 130};
    /** Luft um eine gemerkte Stelle, wenn der Matcher sie kennt. */
    private static final int KASTEN_LUFT = 30;
    private static final int MAX_BLIND = 60;
    private static final int MAX_ZUEGE = 10;
    private static final long WERBUNG_MIN_MS = 25_000;
    /** Hoechstens so oft je Besuch das rote X, wenn nichts erkannt wird. */
    private static final int MAX_BLIND_X = 3;
    /** So lange muss ein fremdes "Yes"-Fenster stehen, bevor "No"/X kommt (der Text blendet erst ein). */
    private static final long FREMDES_JA_MS = 2500;
    /** Hoechstens so viele Fotos je Besuch nach logs\gembay-*.png (fuer neue Vorlagen). */
    private static final int MAX_FOTOS = 10;
    private static final String ZU_VIELE_KARTENKLICKS = Sprache.t("\"Map\" and \"Gem Bay\" on the world map were clicked ", "\"Map\" und \"Gem Bay\" auf der Weltkarte wurden ")
            + GemBayGrenzen.MAX_KARTEN_KLICKS + Sprache.t(" times, but no Gem Bay window opened -- ", "-mal angeklickt, aber kein Gem-Bay-Fenster ging auf -- ")
            + Sprache.t("the visit ends, back to the castle.", "der Besuch wird beendet, zurueck in die Burg.");

    private enum Phase { NORMAL, KISTEN, WERBUNG }

    private final Nahrungskisten kisten = new Nahrungskisten();
    private final GemBayGrenzen grenzen = new GemBayGrenzen();
    private Phase phase = Phase.NORMAL;
    /** In der Burg abgelesen, -1 = unbekannt. */
    private long nahrung = -1;
    /** Unter "Start journey", -1 = unbekannt. */
    private long preis = -1;
    /** Die Reset-Zeit wird je Besuch einmal abgelesen. */
    private boolean resetGelesen, resetFehltGemeldet;
    /**
     * Die abgelesene Reset-Zeit -- erst am Ende des Besuchs in gembay-reset.txt, und nur wenn das
     * Zeitfenster damit erledigt ist (Pruefung 05.10.: sofort geschrieben sperrte sie den
     * zweiten Versuch nach einem Fehlversuch bis zum Reset, meist viele Stunden).
     */
    private java.time.LocalDateTime resetUm;
    /** Der laufende Besuch -- fuer die Fortschrittsmeldung beim Heimweg (Totmann im Motor). */
    private RunTracker lauf;
    /** "Journey over!" ohne "Claim reward", Kauf-Fenster ohne "No"/X: seit wann zu sehen. */
    private final GemBayGrenzen.Anblick endeOhneClaim = new GemBayGrenzen.Anblick();
    private final GemBayGrenzen.Anblick kaufOhneKnopf = new GemBayGrenzen.Anblick();
    /** Wann die Siege zuletzt gelesen wurden, und ob das schon einmal geklappt hat. */
    private long siegeGelesenUm;
    private boolean siegeGemeldet, siegeFehlenGemeldet;
    private boolean fertig, deployGedrueckt, kistenVersucht, reiheGeprueft, beendet;
    /** Ist das Ende dieses Besuchs schon vermerkt (erledigt oder Fehlversuch)? */
    private boolean vermerkt;
    private int blind, zuege, leseVersuche, preisVersuche, nachEnde, blindX;
    private long squadSeit;
    /** Wann "Confirm" gedrueckt wurde -- nur kurz danach darf "Yes" (Warnung) gedrueckt werden. */
    private long confirmUm;
    private int kauffenster;
    /** Geschaffte Reisen in diesem Durchgang. */
    private int reisen;
    /**
     * Autor 24.10.: "pro abgeschlossene Runde 0,1 % schneller; wenn das maximale Tempo erreicht ist, die Stufe 1 vor dem
     * maximalen Tempo behalten. Ohne Fehler am besten." Feste Pausen in den Menues x Faktor (gembay-tempo.txt); Runde =
     * eine geschaffte Reise, Fehler = jede Warnung von Gem Bay (auch ein abgebrochener Besuch). Fotozyklus, "Battle
     * begins"-Klicken, Kampf, Video und Wiederbelebung warten wie bisher -- ebenso die Sicherheitssperren (Yes, Start
     * journey, Kauf-Fenster).
     */
    private final de.hustlebot.core.Tempolerner tempo = de.hustlebot.core.Tempolerner.fuer("gembay-tempo.txt", WO);
    private int runFehler;
    /** Geschaffte Reisen seit dem Start (fuer die Tempo-Zeile alle 10 Reisen; nicht in reset()). */
    private int reisenGesamt;

    private void warnung(String text) {
        if (de.hustlebot.core.Tempolerner.zaehltAlsFehler(text)) runFehler++;   // 24.10.: nur Klick-Fehler
        Log.warn(WO, text);
    }

    /** Eine Reise geschafft: das Tempo lernen (Autor 24.10.). */
    private void reiseFertig() {
        reisenGesamt++;
        tempo.rundeFertig(runFehler == 0);
        if (reisenGesamt % 10 == 0) {
            Log.info(WO, de.hustlebot.core.Sprache.t("Speed after " + reisenGesamt + " journeys: pauses x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fixed)" : ""),
                    "Tempo nach " + reisenGesamt + " Reisen: Pausen x "
                    + String.format(java.util.Locale.GERMANY, "%.3f", tempo.faktor()) + (tempo.fest() ? " (fest)" : "")));
        }
        runFehler = 0;
    }

    /** Kurz warten in den Menues (wie warten(), aber mit dem gelernten Tempo; Autor 24.10.). */
    private Outcome kurzWarten(BotContext ctx) {
        ctx.sleep(tempo.ms(150));
        return Outcome.WAITING;
    }
    /** Ein Fenster mit "Yes", dessen Text nicht passt: seit wann, zuletzt gesehen, wie oft. */
    private long fremdesJaSeit, fremdesJaZuletzt;
    private int fremdeJa;
    private long kampfGemeldetUm;
    private int fotos;
    /** Seit wann die Nahrung in der Burg nicht lesbar ist (0 = kein offener Versuch), und ob es dazu schon ein Foto gibt. */
    private long nahrungLesenSeit;
    private boolean nahrungFotoGemacht;
    /** Wann zuletzt ein ganzes Bild mit allen Pruefungen lief (Sicherheitsblick im Kampftakt). */
    private long sicherheitsBlickUm;
    /** Letzte Stoerfenster-Pruefung (Kauf-Fenster, Update, anderes Geraet) -- nur alle 6 s (08.10.). */
    private long stoerBlickUm;
    /** Wann das Kauf-Fenster zuletzt zu sehen war -- solange es offen ist, wird jeder Sicherheitsblick genutzt. */
    private long kaufGesehenUm;
    /** Wann "Auto" gedrueckt wurde -- nur kurz danach lohnt die Suche nach "To battle!" (teuerste Vorlage). */
    private long autoGeklicktUm;
    /** Punkt 3 (14.10.): wie oft in diesem Kampf schon "No" statt "Yes" gedrueckt wurde, um die Reserve nachzuziehen. */
    private int nachziehVersuche;
    /** Wie oft die Reserve-Reihe in dieser Aufstellung schon weitergeschoben wurde (Autor 17.10.). */
    private int reserveSchiebungen;
    /** Wie oft die Aufstellung schon nach links gewischt wurde (24.10.: zwei Wisch-Arten im Wechsel). */
    private int reiheWischer;
    /** Nach dem Video: seit wann auf gruene Einheiten gewartet wird (0 = nicht), wann zuletzt raus und wieder hinein. */
    private long wiederbelebungSeit, neuBetretenUm, belebtGesehenUm;
    /** Nach "Watch" war die Bucht einmal nicht zu sehen -- das Video lief wirklich. */
    private boolean videoGesehen;
    /** Keine Team-Einheit mehr zu finden: jetzt duerfen die gemerkten Reserve-Einheiten hoch. */
    private boolean reserveErlaubt;
    /** In dieser Suche lag eine lebende Reserve-Einheit in der Reihe, die (noch) nicht hoch durfte. */
    private boolean gesperrtGesehen;
    /** Wie oft in diesem Besuch das orange "Claim reward" oben gedrueckt wurde. */
    private int obenClaims;
    /** Gemerkte Einheiten-Bilder (gembay-reserve\): beim Video gefallen = Team, noch lebend = Reserve. */
    private List<BufferedImage> teamBilder = List.of(), reserveBilder = List.of();
    private long nachziehGemeldetUm;
    /** Wann "Start journey" bzw. "Yes" zuletzt gedrueckt wurden -- nie zweimal binnen EINZELKLICK_SPERRE_MS. */
    private long startGeklicktUm, jaGeklicktUm;
    /** Wie oft die Reset-Zeit in diesem Besuch schon vergeblich gelesen wurde. */
    private int resetVersuche;

    @Override public String id() { return "gembay"; }
    /**
     * Bugjagd 29.10. (Berichte 6M6R und LWCJ, 26.10.): "No progress for 20 s (mode gembay, last: Reise 1 geschafft)" -- nach dem
     * Wegtippen des Belohnungsfensters laedt das Spiel die naechste Reise 15-20 s lang, ohne Klick. "Alles im Fluss" stand im Bericht.
     * Gem Bay meldet darum erst nach 60 s.
     */
    @Override public long haengerBerichtNachS() { return 60; }

    @Override public String displayName() { return "Gem Bay"; }

    @Override
    public List<String> requiredTemplates() {
        return List.of(T_KARTE, T_START, T_APFEL, T_DEPLOY, T_CONFIRM, T_VICTORIES, T_AUTO, T_PAUSE,
                T_BEGINNT, T_TO_BATTLE, T_ENDE, T_CLAIM);
    }

    @Override
    public void reset() {
        resetGelesen = resetFehltGemeldet = false;
        resetUm = null;
        lauf = null;
        endeOhneClaim.vergessen();
        kaufOhneKnopf.vergessen();
        siegeGelesenUm = 0;
        siegeGemeldet = siegeFehlenGemeldet = false;
        phase = Phase.NORMAL;
        grenzen.besuchBeginnt(System.currentTimeMillis());
        nahrung = -1;
        preis = -1;
        fertig = deployGedrueckt = kistenVersucht = reiheGeprueft = beendet = vermerkt = false;
        nachziehVersuche = 0;
        reserveSchiebungen = 0;
        reserveErlaubt = gesperrtGesehen = videoGesehen = false;
        obenClaims = 0;
        neuBetretenUm = belebtGesehenUm = 0;
        long video = videoUm();
        wiederbelebungSeit = video > 0 && System.currentTimeMillis() - video < WIEDERBELEBUNG_MAX_MS ? video : 0;
        List<List<BufferedImage>> bilder = einheitenLaden();
        teamBilder = bilder.get(0);
        reserveBilder = bilder.get(1);
        nachziehGemeldetUm = 0;
        blind = zuege = leseVersuche = preisVersuche = nachEnde = blindX = 0;
        squadSeit = 0;
        reisen = 0;
        confirmUm = 0;
        kauffenster = 0;
        fremdesJaSeit = fremdesJaZuletzt = 0;
        fremdeJa = 0;
        kampfGemeldetUm = 0;
        fotos = 0;
        nahrungLesenSeit = sicherheitsBlickUm = autoGeklicktUm = startGeklicktUm = jaGeklicktUm = 0;
        stoerBlickUm = kaufGesehenUm = 0;
        nahrungFotoGemacht = false;
        resetVersuche = 0;
    }

    @Override
    public Outcome step(Mat screen, BotContext ctx, RunTracker runs) {
        long jetzt = System.currentTimeMillis();
        lauf = runs;
        grenzen.schritt(jetzt);   // eine Pause (F10, !pause) zaehlt nicht zur Besuchszeit
        // Stopp/Not-Aus angefordert: kein Klick mehr, der Motor haelt gleich an.
        if (BotEngine.stoppAngefordert()) return Outcome.WAITING;

        // Zeitgrenze je Besuch (vorher 45 Minuten je Reise): danach sauber zurueck in die Burg.
        // Steigen die Siege noch, darf die laufende Reise zu Ende (GemBayGrenzen.besuchAbgelaufen).
        if (!fertig && grenzen.besuchAbgelaufen(jetzt)) {
            abbrechen(ctx, "zeitgrenze", Sprache.t("After ", "Nach ") + grenzen.besuchMs(jetzt) / 60_000 + Sprache.t(" minutes not done -- ", " Minuten nicht fertig -- ")
                    + Sprache.t("going back to the castle cleanly", "es geht sauber zurueck in die Burg") + (grenzen.kampfMoeglich(jetzt)
                    ? Sprache.t(" (a running fight is waited for, no red X in the middle of a fight)", " (ein laufender Kampf wird noch abgewartet, kein rotes X mitten im Kampf)") : "") + ".");
        }
        // Der Totmann im Motor haelt nach 15 Minuten ohne Fortschritt an -- und dann steht die ganze
        // Warteschlange (Pruefung 05.10.). Vorher selbst sauber heim.
        if (!fertig && runs.idleSeconds() * 1000 >= GemBayGrenzen.LEERLAUF_MAX_MS) {
            abbrechen(ctx, "leerlauf", Sprache.t("No progress for ", "Seit ") + runs.idleSeconds() / 60 + Sprache.t(" minutes -- ", " Minuten kein Fortschritt -- ")
                    + Sprache.t("going back to the castle cleanly before the bot stops completely.", "es geht sauber zurueck in die Burg, bevor der Bot ganz anhaelt."));
        }
        if (fertig) grenzen.heimwegBeginnt(jetzt);
        if (grenzen.heimwegZuLang(jetzt)) {
            warnung(Sprache.t("The way back to the castle has not worked for ", "Der Weg zurueck in die Burg klappt seit ") + grenzen.heimwegMs(jetzt) / 60_000
                    + Sprache.t(" minutes -- Gem Bay ends here, the next mode finds its own way home.", " Minuten nicht -- Gem Bay endet hier, der naechste Modus findet selbst heim."));
            foto(ctx, "heimweg");
            return Outcome.FINISHED;
        }

        // Video "Restore all units" laeuft: NICHTS anklicken (Autor; fremde Webseiten).
        if (phase == Phase.WERBUNG) {
            long d = grenzen.videoMs(jetzt);
            boolean bucht = victories(screen, ctx).isPresent();
            if (!bucht) videoGesehen = true;
            // Live-Test 17.10.: Nach 32 s galt das Video als vorbei, weil die Bucht zu sehen war -- die Einheiten waren
            // aber noch grau ("Waiting for ad network to respond"), und der Bot zog die Reserve hoch. Jetzt: vorbei erst,
            // wenn die Bucht NACH dem Video wieder da ist; dann wird auf die Wiederbelebung gewartet (bucht()).
            if (bucht && d >= WERBUNG_MIN_MS && (videoGesehen || grenzen.videoZuLang(jetzt))) {
                phase = Phase.NORMAL;
                reiheGeprueft = false;
                grenzen.werbungVorbei();
                grenzen.kampfZeichen(jetzt);
                wiederbelebungSeit = jetzt;
                neuBetretenUm = belebtGesehenUm = 0;
                runs.progress(Sprache.t("Gem Bay: video over", "Gem Bay: Video vorbei"));
                Log.info(WO, Sprache.t("Video over (", "Video vorbei (") + d / 1000 + Sprache.t(" s) -- now waiting until all units are green again. ", " s) -- jetzt warten, bis alle Einheiten wieder gruen sind. ")
                        + Sprache.t("For that briefly out of the bay and back in (then the ad network answers faster).", "Dafuer kurz raus aus der Bucht und wieder hinein (Autor: dann antwortet das Werbe-Netz schneller)."));
                return Outcome.ACTED;
            }
            if (!bucht && grenzen.videoZuLang(jetzt)) {
                // P8 (Pruefung 04.10.): frueher PAUSE ohne Ende -- der Bot stand, bis Autor kam.
                // Jetzt geht es weiter, aber auch jetzt wird NICHTS in die Werbung gedrueckt (wie im
                // Nahrungsvideo: ein Klick kann eine fremde Webseite oeffnen). Bis die Bucht, die Burg
                // oder die Weltkarte wieder zu sehen ist, auch kein rotes X (Pruefung 05.10.: vorher
                // ab 180 s erlaubt) -- dann endet der Besuch ueber Zeitgrenze und Heimweg-Grenze.
                // Wie im Nahrungsvideo bekommt Autor Bescheid.
                Log.alarm(WO, Sprache.t("The \"Restore all units\" video is not back after ", "Das Video \"Restore all units\" ist nach ") + d / 1000 + Sprache.t(" s. ", " s nicht zurueck. ")
                        + Sprache.t("The bot presses NOTHING in the ad (no X either) and waits until the game ", "Der Bot drueckt in der Werbung NICHTS (auch kein X) und wartet, bis das Spiel ")
                        + Sprache.t("is visible again -- otherwise Gem Bay ends after the time limit. Please check at the PC.", "wieder zu sehen ist -- sonst endet Gem Bay nach der Zeitgrenze. Bitte am Rechner nachsehen."));
                foto(ctx, "video");
                grenzen.videoHaengt();
                phase = Phase.NORMAL;
                reiheGeprueft = false;
                wiederbelebungSeit = jetzt;   // kommt die Bucht wieder: erst auf gruene Einheiten warten
                neuBetretenUm = belebtGesehenUm = 0;
                return Outcome.WAITING;
            }
            ctx.sleep(1000);
            return Outcome.WAITING;
        }
        if (phase == Phase.KISTEN) {
            if (kisten.aktiv()) {
                kisten.schritt(screen, ctx);
                return Outcome.ACTED;
            }
            phase = Phase.NORMAL;
            kistenVersucht = true;
            nahrung = -1;          // gleich frisch ablesen
            leseVersuche = 0;
            return Outcome.ACTED;
        }

        // KAMPFTAKT (Autor, 06.10., Punkt 4): Solange ein Kampf laufen kann, kostet jeder Schritt
        // sonst 5-8 Vollbildsuchen nach Dingen, die es im Kampf nicht gibt (Kauf-Fenster, Update,
        // anderes Geraet, Weltkarte ...; Lauf 1: 84 von 161 s in der Vorlagensuche). Deshalb laeuft
        // die ganze Kette nur noch alle SICHERHEITSBLICK_MS -- der Sicherheitsblick --, dazwischen
        // nur die Bucht-Vorlagen und der Fotozyklus. Ausserhalb des Kampfs wie immer jeder Schritt voll.
        boolean kampfTakt = !fertig && !beendet && phase == Phase.NORMAL && grenzen.kampfMoeglich(jetzt)
                && !grenzen.videoMoeglich(jetzt);
        boolean voll = !kampfTakt || jetzt - sicherheitsBlickUm >= GemBayGrenzen.SICHERHEITSBLICK_MS;
        if (voll) sicherheitsBlickUm = jetzt;
        // 08.10. (Tempo-Analyse): die drei Stoerfenster-Suchen (Kauf-Fenster, Update, anderes
        // Geraet) machten 16 % der Vollbild-Suchzeit aus und fanden nie etwas -- jetzt nur alle 6 s
        // (wie der Arena-Stoerblick). Das Kauf-Fenster wird so hoechstens 6 s spaeter gesehen; dort
        // drueckt der Bot ohnehin nur "No".
        // Solange das Kauf-Fenster zuletzt zu sehen war, in JEDEM Sicherheitsblick weiter pruefen --
        // sonst waere die 30-s-Notbremse (OHNE_KNOPF_MS) tot (Pruefung 08.10.).
        boolean stoerBlick = voll && (jetzt - stoerBlickUm >= 6000 || jetzt - kaufGesehenUm < 40_000);
        if (stoerBlick) stoerBlickUm = jetzt;

        // KAUF-FENSTER "Buy the missing resources?" -- NIE Diamanten (Sicherheitspruefung
        // 29.09.). Kommt, wenn Nahrung/Preis falsch gelesen wurden und "Start journey"
        // trotz zu wenig Nahrung gedrueckt wurde. Solange es offen ist: nur "No" oder X.
        if (stoerBlick && ctx.visibleIfKnown(screen, T_KAUF)) {
            kaufGesehenUm = jetzt;
            long offen = kaufOhneKnopf.gesehen(jetzt);
            if (ctx.clickIfVisibleIfKnown(screen, T_KAUF_NEIN, WO + Sprache.t(": buy window: \"No\" (never diamonds)", ": Kauf-Fenster: \"No\" (nie Diamanten)"))
                    || ctx.clickIfVisibleIfKnown(screen, T_X, WO + Sprache.t(": close the buy window (never diamonds)", ": Kauf-Fenster schliessen (nie Diamanten)"))) {
                kaufOhneKnopf.vergessen();
                Log.alarm(WO, Sprache.t("The buy window \"Buy the missing resources?\" came up -- closed with \"No\", ", "Das Kauf-Fenster \"Buy the missing resources?\" kam -- mit \"No\" weggeklickt, ")
                        + Sprache.t("NO diamonds were spent. Probably not enough food.", "es wurden KEINE Diamanten ausgegeben. Die Nahrung reicht wohl nicht."));
                if (++kauffenster >= 2) {   // zweimal: fuer dieses Zeitfenster Schluss
                    beenden(Sprache.t("The buy window came up twice -- Gem Bay is done for this time window.", "Das Kauf-Fenster kam zweimal -- Gem Bay ist fuer dieses Zeitfenster fertig."));
                }
                nahrung = -1;
                preis = -1;
                ctx.sleepUntilChanged(1000);
                return Outcome.ACTED;
            }
            // Weder "No" noch X passt (Pruefung 05.10.: vorher Warten ohne Grenze). Hier wird NIE
            // etwas anderes gedrueckt -- nach 30 s endet der Besuch, Autor bekommt Bescheid, und
            // die Heimweg-Grenze beendet Gem Bay, falls das Fenster offen bleibt.
            if (!fertig && offen > GemBayGrenzen.OHNE_KNOPF_MS) {
                Log.alarm(WO, Sprache.t("The buy window \"Buy the missing resources?\" has been open for ", "Das Kauf-Fenster \"Buy the missing resources?\" ist seit ") + offen / 1000 + Sprache.t(" s, ", " s offen, ")
                        + Sprache.t("but neither \"No\" nor the red X can be recognised. The bot presses NOTHING -- please check.", "aber weder \"No\" noch das rote X sind zu erkennen. Der Bot drueckt NICHTS -- bitte nachsehen."));
                abbrechen(ctx, "kauf", Sprache.t("Buy window without a recognisable \"No\" -- the visit ends.", "Kauf-Fenster ohne erkennbares \"No\" -- der Besuch wird beendet."));
            }
            return warten(ctx);
        }

        if (stoerBlick) {
            if (Stoerfenster.afkUpdate(screen, ctx, WO)) return Outcome.ACTED;
            switch (Stoerfenster.anderesGeraet(screen, ctx, WO)) {
                case ANHALTEN -> { return Outcome.PAUSE; }
                case GEKLICKT -> { return Outcome.ACTED; }
                case NICHTS -> { /* weiter */ }
            }
        }

        // --- "Journey over!" -> gruenes "Claim reward" -------------------------------
        // Nur mit der Ueberschrift: in der Bucht gibt es ein ORANGES "Claim reward",
        // das die Reise vorzeitig beendet. Auch auf dem Heimweg: die Belohnung gehoert abgeholt.
        // Im Kampftakt nur im Sicherheitsblick -- der Fotozyklus weckt ihn, sobald sein Kasten
        // ein "Claim reward" sieht (sicherheitsBlickUm = 0).
        // Live-Test 17.10.: Fallen alle (auch die Reserve) und ist das Video schon benutzt, kommt mitten in der Bucht
        // "Journey over! ... no one left" mit ROTEM "Claim reward" (daneben "Mercenaries" -- nie druecken). Autor: dann
        // "Claim reward" und weiter mit der naechsten Reise. Vorher stand der Bot davor und las nur "Siege 13/15".
        // ... und danach die Warnung "By claiming the reward, you will end the journey!" -> gruenes "Claim reward" (nie
        // "Cancel"). Sie liegt ueber dem Kisten-Fenster, darum zuerst.
        if ((voll || beendet || obenClaims > 0) && ctx.visibleIfKnown(screen, T_WARNUNG_SATZ)) {
            blind = 0;
            if (ctx.clickIfVisibleIfKnown(screen, T_CLAIM_WARNUNG, WO + Sprache.t(": confirm ending the journey (Claim reward in the warning)", ": Reise beenden bestaetigen (Claim reward in der Warnung)"))) {
                if (!ctx.warteBisWeg(1500, 0.80, T_CLAIM_WARNUNG)) ctx.sleepUntilChanged(600);
                ctx.warteAufKnopf(5000, Matcher.DEFAULT_THRESHOLD, T_BELOHNUNG, T_MAP, T_MAP_ALT);
                return Outcome.ACTED;
            }
            return warten(ctx);
        }
        // Live-Test 17.10.: Nach dem roten (oder dem orangen) "Claim reward" kommt "Gem Bay chest" -- "By claiming the
        // reward, you will end the journey!" -- mit gruenem "Claim reward" samt Kiste (Autor: druecken).
        if ((voll || beendet || obenClaims > 0) && ctx.visibleIfKnown(screen, T_KISTE_SATZ)) {
            blind = 0;
            grenzen.kampfVorbei();
            if (ctx.clickIfVisibleIfKnown(screen, T_CLAIM_KISTE, WO + Sprache.t(": claim the chest, end the journey (Claim reward)", ": Kiste abholen, Reise beenden (Claim reward)"))) {
                if (!beendet) {
                    beendet = true;
                    reisen++;
                    reiseFertig();
                    runs.progress(Sprache.t("Gem Bay: journey ", "Gem Bay: Reise ") + reisen + Sprache.t(" finished", " beendet"));
                    Log.info(WO, Sprache.t("Journey ", "Reise ") + reisen + Sprache.t(" finished (Gem Bay chest claimed) -- straight to the next one while ", " beendet (Gem Bay chest abgeholt) -- gleich die naechste, solange ")
                            + Sprache.t("the price is in food.", "der Preis in Nahrung steht."));
                }
                if (!ctx.warteBisWeg(1500, 0.80, T_CLAIM_KISTE)) ctx.sleepUntilChanged(600);
                ctx.warteAufKnopf(5000, Matcher.DEFAULT_THRESHOLD, T_BELOHNUNG, T_MAP, T_MAP_ALT);
                return Outcome.ACTED;
            }
            return kurzWarten(ctx);
        }
        boolean leer = voll && ctx.visibleIfKnown(screen, T_ENDE_LEER);
        if (voll && (leer || ctx.visibleIfKnown(screen, T_ENDE))) {
            blind = 0;
            grenzen.kampfVorbei();
            long offen = endeOhneClaim.gesehen(jetzt);
            String knopf = leer && ctx.visibleIfKnown(screen, T_CLAIM_ROT) ? T_CLAIM_ROT : T_CLAIM;
            if (ctx.clickIfVisibleIfKnown(screen, knopf, WO + Sprache.t(": claim the reward (Claim reward)", ": Belohnung holen (Claim reward)"))) {
                endeOhneClaim.vergessen();
                if (!beendet) {
                    beendet = true;
                    reisen++;
                    reiseFertig();
                    runs.progress(Sprache.t("Gem Bay: journey ", "Gem Bay: Reise ") + reisen + Sprache.t(" done", " geschafft"));
                    Log.info(WO, leer ? Sprache.t("Journey ", "Reise ") + reisen + Sprache.t(" finished -- all units fell, the video is already ", " beendet -- alle Einheiten gefallen, das Video ist schon ")
                            + Sprache.t("used. Reward claimed, straight to the next journey while the price is in food.", "benutzt. Belohnung geholt, gleich die naechste Reise, solange der Preis in Nahrung steht.")
                            : Sprache.t("Journey ", "Reise ") + reisen + Sprache.t(" done -- 15 of 15 wins. Straight to the next one, ", " geschafft -- 15 von 15 Siegen. Gleich die naechste, ")
                            + Sprache.t("while the price is in food.", "solange der Preis in Nahrung steht."));
                }
                // Lauf 1 (06.10.): sleepUntilChanged(3000) kam schon nach 120 ms zurueck (nur die
                // Knopf-Animation). Jetzt per Kastenblick: erst bis der Knopf weg ist, dann bis das
                // Belohnungsfenster oder die Burg da ist -- leer heisst nur: der naechste Schritt sieht
                // wie bisher am ganzen Bild nach.
                if (!ctx.warteBisWeg(1500, 0.80, knopf)) ctx.sleepUntilChanged(600);
                ctx.warteAufKnopf(5000, Matcher.DEFAULT_THRESHOLD, T_BELOHNUNG, T_MAP, T_MAP_ALT);
                return Outcome.ACTED;
            }
            // "Claim reward" passt nicht (gb_claim live noch nie gesehen; Pruefung 05.10.: vorher
            // Warten ohne Grenze, bis der Totmann den Bot anhielt). Nach 30 s: Foto (daraus laesst
            // sich die Vorlage neu ausschneiden), Besuch beenden, dann das rote X -- die Reise ist
            // vorbei, es kann kein Kampf laufen ("Rotes X fuehrt zurueck", Autor).
            if (offen > GemBayGrenzen.OHNE_KNOPF_MS) {
                if (!fertig) {
                    abbrechen(ctx, "ende", Sprache.t("\"Journey over!\" has been visible for ", "\"Journey over!\" ist seit ") + offen / 1000 + Sprache.t(" s, but \"Claim ", " s zu sehen, aber \"Claim ")
                            + Sprache.t("reward\" (template ", "reward\" (Vorlage ") + T_CLAIM + Sprache.t(") does not match -- the visit ends.", ") passt nicht -- der Besuch wird beendet."));
                }
                if (blindX < MAX_BLIND_X && ctx.clickIfVisibleIfKnown(screen, T_X,
                        WO + Sprache.t(": close \"Journey over!\" (red X, \"Claim reward\" not recognised)", ": \"Journey over!\" schliessen (rotes X, \"Claim reward\" nicht erkannt)"))) {
                    blindX++;
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
            }
            return kurzWarten(ctx);
        }
        if (beendet) {
            // Ladebildschirm, dann die Burg mit "You have received a reward".
            if (belohnungWegtippen(screen, ctx)) return Outcome.ACTED;
            if (Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
            if (inBurg(screen, ctx) || ++nachEnde > 40) {
                // P4 (Pruefung 04.10.): frueher ohne Vermerk -- der Bot ging im selben Zeitfenster
                // alle 20 Minuten wieder hin.
                if (!fertig && reisen >= maxReisen()) {
                    beenden(reisen + Sprache.t(" journeys done in this visit -- Gem Bay is done for this time window.", " Reisen in diesem Besuch geschafft -- Gem Bay ist fuer dieses Zeitfenster fertig."));
                }
                if (fertig) {
                    beendet = false;
                    grenzen.heimwegBeginnt(jetzt);
                    return heimweg(screen, ctx, runs, jetzt);
                }
                naechsteReise();
                return Outcome.ACTED;
            }
            return warten(ctx);
        }

        // Besuch zu Ende (geschafft, nichts mehr zu tun, abgebrochen): nur noch heim.
        if (fertig) return heimweg(screen, ctx, runs, jetzt);

        // "Auto Battle Settings" -> "To battle!" -- VOR der Bucht: die Bucht passt auch
        // hinter dem abgedunkelten Dialog noch (Code-Pruefung 29.09.).
        // gb_to_battle (191x80) ist die teuerste Vorlage (Lauf 1: 191 Suchen, 6,8 s) -- im Kampftakt
        // nur kurz nach einem Klick auf "Auto" oder im Sicherheitsblick.
        if ((voll || jetzt - autoGeklicktUm < 5000)
                && ctx.clickIfVisibleIfKnown(screen, T_TO_BATTLE, WO + Sprache.t(": start auto mode (To battle!)", ": Automodus starten (To battle!)"))) {
            blind = 0;
            reiheGeprueft = false;
            nachziehVersuche = 0;
            reserveSchiebungen = 0;
            reserveErlaubt = gesperrtGesehen = false;   // naechste Aufstellung: wieder erst das Team
            grenzen.kampfZeichen(jetzt);
            runs.progress(Sprache.t("Gem Bay: auto mode", "Gem Bay: Automodus"));
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }

        // --- In der Bucht ------------------------------------------------------------
        // Zeichen der Bucht: "Victories" (dunkel ODER blau, Lauf 1 06.10.) -- oder "Battle begins"
        // bzw. "Pause", die es nur dort gibt (bei Sieg 4-6 kann die blaue Kante durchs Wort laufen,
        // dann passt keine der beiden Victories-Vorlagen). Die Siege werden NACH dem Klick gelesen,
        // damit die Texterkennung den Klick nie verzoegert (die Zahl aendert sich im Kampf nicht).
        Optional<Matcher.Hit> vict = victories(screen, ctx);
        if (vict.isPresent() || ctx.visibleIfKnown(screen, T_BEGINNT) || ctx.visibleIfKnown(screen, T_PAUSE)) {
            blind = 0;
            grenzen.werbungVorbei();
            grenzen.kampfZeichen(jetzt);
            grenzen.fortschritt();
            runs.progress(Sprache.t("Gem Bay: in the bay", "Gem Bay: in der Bucht"));
            Outcome aus = bucht(screen, ctx, jetzt);
            if (vict.isPresent()) siegeLesen(vict.get(), ctx, jetzt);
            return aus;
        }

        // Warnung "You can take more units with you ..." -> Yes (kommt nicht bei jedem). Nur kurz
        // nach "Confirm" UND nur nach Textpruefung (P9, Pruefung 04.10.: vorher bis 6 s nach
        // Confirm ohne jeden Blick auf den Text). Passt der Text nicht: "No" oder X, nie "Yes".
        // Im Kampftakt nur kurz nach "Confirm" oder im Sicherheitsblick.
        Optional<Matcher.Hit> ja = voll || jetzt - confirmUm < 6000 ? jaKnopf(screen, ctx) : Optional.empty();
        if (ja.isPresent()) {
            blind = 0;
            JaText text = jaText(ctx, ja.get());
            // Auch mitten in der Reise (die Trupp-Warnung, wenn die Bucht dahinter nicht zu sehen ist).
            boolean erwartet = jetzt - confirmUm < 6000 || grenzen.kampfMoeglich(jetzt);
            if (erwartet && text.erlaubt()) {
                // NIE zweimal "Yes" (Autor, 06.10.): steht das Fenster nach 3 s noch, ist der Klick
                // nicht angekommen -- erst dann noch einmal.
                if (jetzt - jaGeklicktUm < GemBayGrenzen.EINZELKLICK_SPERRE_MS) return warten(ctx);
                ctx.klick(ja.get(), WO + Sprache.t(": confirm the warning (Yes, text checked)", ": Warnung bestaetigen (Yes, Text geprueft)"));
                // Die Sperre zaehlt ab dem KLICK, nicht ab Schrittanfang (Pruefung 06.10.): die
                // Texterkennung davor (~0,7 s) frass sonst einen Teil der 3 s.
                jaGeklicktUm = System.currentTimeMillis();
                grenzen.kampfZeichen(jetzt);
                ctx.sleepUntilChanged(600);
                buchtAbwarten(ctx);
                return Outcome.ACTED;
            }
            return fremdesJa(screen, ctx, jetzt, text,
                    erwartet ? Sprache.t("the text is not the expected warning (or it contains a price)", "der Text ist nicht die erwartete Warnung (oder es steht ein Preis darin)")
                            : Sprache.t("it did not come right after \"Confirm\" and not during the journey", "es kam nicht direkt nach \"Confirm\" und nicht in der Reise"));
        }

        // Im Kampftakt endet der schnelle Schritt hier: Weltkarte, Burg und Stoerfenster gibt es
        // mitten im Kampf nicht (sie kommen im Sicherheitsblick dran). Weiter mit dem Fotozyklus.
        if (!voll) {
            if (kampfBild(screen, ctx, runs, jetzt)) return warten(ctx);
            return unbekannt(screen, ctx, jetzt);
        }

        // --- "Journey squad" -----------------------------------------------------------
        if (ctx.visibleIfKnown(screen, T_CONFIRM) || ctx.visibleIfKnown(screen, T_DEPLOY)) {
            blind = 0;
            grenzen.fortschritt();
            if (squadSeit == 0) squadSeit = System.currentTimeMillis();
            // Deploy-Knopf nicht zu sehen (grau, schon aufgestellt): nach 2,5 s trotzdem bestaetigen.
            if (!deployGedrueckt && System.currentTimeMillis() - squadSeit > 2500 && !ctx.visibleIfKnown(screen, T_DEPLOY)) {
                Log.info(WO, Sprache.t("\"Deploy the best ones\" is not visible -- confirming as it is.", "\"Deploy the best ones\" ist nicht zu sehen -- es wird so bestaetigt."));
                deployGedrueckt = true;
            }
            if (!deployGedrueckt && ctx.clickIfVisibleIfKnown(screen, T_DEPLOY,
                    WO + Sprache.t(": best units (Deploy the best ones)", ": Beste Einheiten (Deploy the best ones)"))) {
                deployGedrueckt = true;
                ctx.sleepUntilChanged(600);
                return confirmVoraus(ctx);
            }
            if (deployGedrueckt && ctx.clickIfVisibleIfKnown(screen, T_CONFIRM, WO + Sprache.t(": confirm the squad (Confirm)", ": Aufstellung bestaetigen (Confirm)"))) {
                return nachConfirm(ctx);
            }
            return kurzWarten(ctx);
        }

        // --- Fenster "Gem Bay" mit "Start journey" -----------------------------------------
        if (ctx.visibleIfKnown(screen, T_START)) {
            blind = 0;
            grenzen.fortschritt();
            grenzen.kampfVorbei();
            runs.progress(Sprache.t("Gem Bay: Start journey window", "Gem Bay: Fenster Start journey"));
            return fenster(screen, ctx, jetzt);
        }

        // --- Weltkarte -----------------------------------------------------------------
        boolean karte = ctx.visibleIfKnown(screen, T_KARTE);
        if (karte || Weltkarte.erkannt(screen, ctx)) {
            blind = 0;
            grenzen.kampfVorbei();
            // Nahrung noch unbekannt oder Kisten noetig: erst in die Burg.
            if (nahrung < 0 || kistenNoetig()) {
                Weltkarte.heim(screen, ctx, WO);
                return Outcome.ACTED;
            }
            if (karte) {
                zuege = 0;
                // P2 (Pruefung 04.10.): 37 Klicks in 58 s auf dieselbe Stelle, ohne dass sich etwas tat.
                if (!grenzen.kartenKlick()) {
                    abbrechen(ctx, "karte", ZU_VIELE_KARTENKLICKS);
                    return Outcome.ACTED;   // naechster Schritt: Heimweg
                }
                Optional<Matcher.Hit> schild = ctx.find(screen, T_KARTE);
                if (schild.isEmpty()) return kurzWarten(ctx);
                String klickText = Sprache.t(" (click ", " (Klick ") + grenzen.kartenKlicks() + Sprache.t(" of ", " von ") + GemBayGrenzen.MAX_KARTEN_KLICKS + Sprache.t(" without progress)", " ohne Fortschritt)");
                // Live-Test 17.10. (Autor): Laeuft eine Reise, steht das Schild hoeher, darunter "Victories 13/15" -- ein
                // Klick auf das Wort oeffnete nichts (12:26 und 13:28, je 4 Klicks). Darum jeder zweite Klick auf das
                // Kaestchen darunter (Autors roter Kreis: Schild, Kaestchen und Schiff).
                if (grenzen.kartenKlicks() % 2 == 0) {
                    int x = schild.get().centerX(), y = schild.get().centerY() + px(ctx, 55);
                    Log.action(WO, Sprache.t("Open Gem Bay on the world map -- box below the sign at ", "Gem Bay auf der Weltkarte oeffnen -- Kaestchen unter dem Schild bei ") + x + "," + y + klickText);
                    ctx.input().click(x, y, 4);
                } else {
                    ctx.klick(schild.get(), WO + Sprache.t(": open Gem Bay on the world map", ": Gem Bay auf der Weltkarte oeffnen") + klickText);
                }
                // Live-Test 17.10.: nach "Verbindung neu aufbauen" lud die Bucht laenger als 1 s -- 4 Klicks in 4 s, dann
                // Abbruch. Jetzt bis zu 5 s auf ein Gem-Bay-Bild warten, bevor wieder geklickt wird.
                ctx.warteAufKnopf(5000, Matcher.DEFAULT_THRESHOLD, T_VICTORIES, T_VICTORIES_BLAU, T_START, T_DEPLOY);
                return Outcome.ACTED;
            }
            if (++zuege > MAX_ZUEGE) {
                abbrechen(ctx, "karte", Sprache.t("Gem Bay cannot be found on the world map (template ", "Gem Bay ist auf der Weltkarte nicht zu finden (Vorlage ") + T_KARTE + "?).");
                return Outcome.ACTED;
            }
            int y = ctx.height() / 2;
            int links = (int) (ctx.width() * 0.25), rechts = (int) (ctx.width() * 0.75);
            boolean nachLinks = zuege <= 3;   // Gem Bay liegt unten rechts: erst das Bild nach links ziehen
            Log.action("Karte", Sprache.t("Swipe the world map ", "Weltkarte nach ") + (nachLinks ? Sprache.t("left", "links") : Sprache.t("right", "rechts")) + Sprache.t(" to find Gem Bay ", " ziehen, um Gem Bay ")
                    + Sprache.t("(swipe ", "zu finden (Zug ") + zuege + Sprache.t(" of ", " von ") + MAX_ZUEGE + ").");
            if (nachLinks) ctx.input().drag(rechts, y, links, y);
            else ctx.input().drag(links, y, rechts, y);
            ctx.sleepUntilChanged(500);
            return Outcome.ACTED;
        }

        // --- Burg ---------------------------------------------------------------------
        if (inBurg(screen, ctx)) {
            blind = 0;
            grenzen.kampfVorbei();
            if (nahrung < 0) {
                // Lauf 1 (06.10.): nach "Claim reward" war die Nahrung 6 s lang nicht lesbar, der Besuch
                // wurde aufgegeben, Reise 2 ausgelassen -- ohne Foto. Jetzt: erst Belohnungs- und
                // Stoerfenster weg, dann bis NAHRUNG_LESEN_MAX_MS lesen, nach 3 s ein Foto davon.
                if (belohnungWegtippen(screen, ctx)) return Outcome.ACTED;
                if (Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
                OptionalLong n = Nahrungskisten.nahrungLesen(ctx);
                if (n.isEmpty()) {
                    if (nahrungLesenSeit == 0) nahrungLesenSeit = jetzt;
                    leseVersuche++;
                    long dauer = System.currentTimeMillis() - nahrungLesenSeit;
                    if (!nahrungFotoGemacht && dauer > 3000) {
                        nahrungFotoGemacht = true;
                        foto(ctx, "nahrung");
                    }
                    if (dauer > GemBayGrenzen.NAHRUNG_LESEN_MAX_MS && leseVersuche >= 3) {
                        abbrechen(ctx, nahrungFotoGemacht ? null : "nahrung", Sprache.t("The food in the castle has not been readable for ", "Die Nahrung in der Burg ist seit ")
                                + dauer / 1000 + Sprache.t(" s (", " s nicht lesbar (") + leseVersuche + Sprache.t(" tries) -- Gem Bay is ", " Versuche) -- Gem Bay wird ")
                                + Sprache.t("skipped (no journey without known food).", "ausgelassen (ohne bekannte Nahrung keine Reise)."));
                    } else {
                        ctx.sleep(tempo.ms(400));
                    }
                    return Outcome.ACTED;
                }
                nahrungLesenSeit = 0;
                nahrung = n.getAsLong();
                Log.info(WO, Sprache.t("Food in the castle: ", "Nahrung in der Burg: ") + zahl(nahrung)
                        + (preis > 0 ? Sprache.t(", the journey costs ", ", die Reise kostet ") + zahl(preis) : "") + ".");
            }
            if (kistenNoetig()) {
                if (kistenVersucht) {
                    beenden(Sprache.t("Not enough food even after the chests (", "Die Nahrung reicht auch nach den Kisten nicht (") + zahl(nahrung) + Sprache.t(" of ", " von ")
                            + zahl(preis) + Sprache.t(") -- Gem Bay is done for this time window.", ") -- Gem Bay ist fuer dieses Zeitfenster fertig."));
                    return Outcome.ACTED;
                }
                if (!kisten.einsatzbereit(ctx)) {
                    beenden(Sprache.t("Not enough food, and templates for the food chests are missing -- ", "Die Nahrung reicht nicht, und fuer die Nahrungskisten fehlen Vorlagen -- ")
                            + Sprache.t("Gem Bay is done for this time window.", "Gem Bay ist fuer dieses Zeitfenster fertig."));
                    return Outcome.ACTED;
                }
                Log.info(WO, Sprache.t("Not enough food for the journey (", "Die Nahrung reicht nicht fuer die Reise (") + zahl(nahrung) + Sprache.t(" of ", " von ") + zahl(preis)
                        + Sprache.t(") -- food chests first (bag, otherwise clan store).", ") -- erst Nahrungskisten (Tasche, sonst Clan-Laden)."));
                // Nur die Tasche (Autor, 06.10.: "Er soll bitte nur Kisten aus der Tasche nehmen. Der
                // Clan-Laden ist ausschliesslich fuer den Clan-Krieg gedacht"), keine Werbevideos.
                long ziel = preis;
                if (kistenTest) {
                    // Kisten-Test: die Nahrung reicht in Wahrheit -- damit wirklich eine Kiste aufgeht,
                    // ist das Ziel "ein bisschen mehr als jetzt da ist".
                    kistenTest = false;
                    OptionalLong echt = Nahrungskisten.nahrungLesen(ctx);
                    ziel = Math.max(preis, echt.orElse(preis) + 1);
                    Log.info(WO, Sprache.t("Chest test: goal ", "Kisten-Test: Ziel ") + zahl(ziel) + Sprache.t(" food, so that at least one chest opens.", " Nahrung, damit mindestens eine Kiste aufgeht."));
                }
                // Autor, 08.10.: "am besten so viele Nahrungskisten, dass mein Lager fast maxed out ist"
                // -- also bis nahe ans Lager (1,5 Mio) auffuellen, mindestens aber den Reisepreis.
                long lager = de.hustlebot.core.Bezahlung.lager();
                if (lager > ziel) kisten.startenAuffuellen(lager, ziel, false, false);
                else kisten.starten(ziel, false, false);
                phase = Phase.KISTEN;
                return Outcome.ACTED;
            }
            // Auch der "Map"-Knopf zaehlt mit (P2): geht die Weltkarte nicht auf, nicht endlos druecken.
            if (!grenzen.kartenKlick()) {
                abbrechen(ctx, "karte", ZU_VIELE_KARTENKLICKS);
                return Outcome.ACTED;   // naechster Schritt: Heimweg (schon in der Burg -> fertig)
            }
            if (ctx.clickIfVisibleIfKnown(screen, T_MAP, WO + Sprache.t(": open the world map", ": Weltkarte oeffnen"))
                    || ctx.clickIfVisibleIfKnown(screen, T_MAP_ALT, WO + Sprache.t(": open the world map", ": Weltkarte oeffnen"))) {
                zuege = 0;
                ctx.sleepUntilChanged(1500);
                return Outcome.ACTED;
            }
        }

        // --- Unbekannt: Kampf, Stoerfenster, sonst warten ------------------------------------
        if (kampfBild(screen, ctx, runs, jetzt)) return warten(ctx);
        // Haengt das Video, kann das hier noch Werbung sein: nichts anklicken (P8).
        if (!grenzen.videoHaengtNoch() && Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
        return unbekannt(screen, ctx, jetzt);
    }

    /**
     * Ist das Kampfbild zu sehen (Vorlage gb_kampf, sobald es sie gibt)? Dann laeuft sicher ein
     * Kampf: Kampfzeichen setzen, Fortschritt melden, nie das rote X (P1, Pruefung 05.10.).
     */
    private boolean kampfBild(Mat screen, BotContext ctx, RunTracker runs, long jetzt) {
        if (!ctx.visibleIfKnown(screen, T_KAMPF)) return false;
        blind = 0;
        grenzen.werbungVorbei();
        grenzen.kampfZeichen(jetzt);
        runs.progress(Sprache.t("Gem Bay: fight running", "Gem Bay: Kampf laeuft"));
        return true;
    }

    /**
     * "Victories x/15" lesen (hoechstens alle 15 s): steigt die Zahl, darf die laufende Reise ueber
     * die 15 Minuten hinaus zu Ende (Pruefung 05.10.). Die Zahl steht neben oder unter dem Wort --
     * gelesen wird grosszuegig drumherum; nicht lesbar heisst nur: keine Verlaengerung.
     */
    private void siegeLesen(Matcher.Hit h, BotContext ctx, long jetzt) {
        if (jetzt - siegeGelesenUm < 15_000) return;
        siegeGelesenUm = jetzt;
        // Das grosse Bild vom letzten refresh() -- der Treffer (dunkel oder blau, beide ~100x25 an
        // derselben Stelle) stammt aus demselben Bild, der Kasten drumherum passt also.
        BufferedImage bild = ctx.frame();
        if (h == null || bild == null) return;
        int x = Math.max(0, h.x() - h.width() / 2);
        int y = Math.max(0, h.y() - h.height());
        int w = Math.min(bild.getWidth() - x, h.width() * 4);
        int hoehe = Math.min(bild.getHeight() - y, h.height() * 4);
        if (w <= 0 || hoehe <= 0) return;
        long t0 = System.currentTimeMillis();
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, hoehe)));
        Log.debug(WO, "Siege-Texterkennung: " + (System.currentTimeMillis() - t0) + " ms (" + h.name() + ").");
        int n = GemBayGrenzen.siegeAusText(text);
        if (n < 0) {
            if (!siegeFehlenGemeldet) {
                siegeFehlenGemeldet = true;
                Log.info(WO, Sprache.t("The wins (\"Victories x/15\") are not readable (yet) (\"", "Die Siege (\"Victories x/15\") sind (noch) nicht lesbar (\"") + text.trim() + "\") -- "
                        + Sprache.t("until then the time limit of ", "solange gilt die Zeitgrenze von ") + GemBayGrenzen.BESUCH_MAX_MS / 60_000 + Sprache.t(" minutes applies without extension.", " Minuten ohne Verlaengerung."));
            }
            return;
        }
        grenzen.siegeGelesen(n, jetzt);
        if (!siegeGemeldet) {
            siegeGemeldet = true;
            Log.info(WO, Sprache.t("Wins read: ", "Siege gelesen: ") + n + "/15.");
        } else {
            Log.debug(WO, "Siege: " + n + "/15.");
        }
    }

    /**
     * Nichts erkannt. P1 (Pruefung 04.10.): frueher drueckte der Bot nach 60 blinden Runden
     * (67 s) das rote X -- mitten im Kampf, denn der Kampf selbst zeigt keine Vorlage der
     * Bucht. Jetzt: solange ein Kampf (oder die Werbung des Videos) laufen kann, nur warten.
     * Im laufenden Besuch nach einem Zeichen aus der Bucht nie ein blindes X (Pruefung 05.10.:
     * wie lange ein Kampf dauert, ist nicht gemessen) -- dort endet der Besuch ueber die Zeitgrenze.
     */
    private Outcome unbekannt(Mat screen, BotContext ctx, long jetzt) {
        blind++;
        if (!grenzen.xErlaubt(jetzt, fertig)) {
            // Im Kampftakt ist ein unbekanntes Bild der Normalfall (der Kampf selbst): erst ab 5
            // Runden hintereinander (~10 s mit Fotozyklus) melden -- ein Kampf dauert 5-7 s.
            if (jetzt - kampfGemeldetUm > 30_000 && (blind > 5 || !kampfTaktErlaubt(jetzt))) {
                kampfGemeldetUm = jetzt;
                Log.info(WO, Sprache.t("Screen not recognised -- probably still running: ", "Bildschirm nicht erkannt -- vermutlich laeuft ")
                        + (grenzen.videoHaengtNoch() ? Sprache.t("the ad (the video did not come back)", "noch die Werbung (das Video kam nicht zurueck)")
                        : grenzen.videoMoeglich(jetzt) ? Sprache.t("the video", "noch das Video") : Sprache.t("a fight", "ein Kampf"))
                        + Sprache.t(". No red X, waiting.", ". Kein rotes X, es wird gewartet."));
                if (blind > MAX_BLIND / 2) foto(ctx, grenzen.videoMoeglich(jetzt) ? "video" : "kampf");
            }
            // Im Kampf: statt 300 ms blind zu warten, den Kasten um "Battle begins" im Schnelltakt
            // ansehen (Autor, 06.10.) -- der Kampf selbst zeigt keine Vorlage der Bucht.
            if (kampfTaktErlaubt(jetzt)) return fotozyklus(ctx);
            return warten(ctx);
        }
        if (blind <= MAX_BLIND) return warten(ctx);
        if (blindX < MAX_BLIND_X && ctx.clickIfVisibleIfKnown(screen, T_X,
                WO + Sprache.t(": close the window (red X, surely no fight running)", ": Fenster schliessen (rotes X, es laeuft sicher kein Kampf)"))) {
            blindX++;
            blind = MAX_BLIND / 2;
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        if (!fertig) {
            abbrechen(ctx, "unbekannt", Sprache.t("Screen not recognised -- the visit ends, back to the castle.", "Bildschirm nicht erkannt -- der Besuch wird beendet, zurueck in die Burg."));
            return Outcome.ACTED;
        }
        warnung(Sprache.t("Screen not recognised, also on the way home -- Gem Bay ends here, carrying on.", "Bildschirm nicht erkannt, auch auf dem Heimweg -- Gem Bay endet hier, es geht weiter."));
        return Outcome.FINISHED;
    }

    /**
     * Der Besuch ist zu Ende: zurueck in die Burg. Nie mehr "Start journey", "Confirm" oder
     * "Yes" -- nur X, "No" und "Home". Das rote X in der Bucht nur mit "Victories" (dann laeuft
     * gerade kein Kampf; die Reise bleibt offen und geht beim naechsten Besuch weiter).
     */
    private Outcome heimweg(Mat screen, BotContext ctx, RunTracker runs, long jetzt) {
        if (inBurg(screen, ctx)) {
            Log.info(WO, Sprache.t("Back in the castle -- the visit to the Gem Bay is over", "Zurueck in der Burg -- der Besuch in der Edelsteinbucht ist zu Ende")
                    + (reisen > 0 ? " (" + reisen + (reisen == 1 ? Sprache.t(" journey", " Reise") : Sprache.t(" journeys", " Reisen")) + " geschafft)" : "") + ".");
            runs.completeRun();
            return Outcome.FINISHED;
        }
        Optional<Matcher.Hit> ja = jaKnopf(screen, ctx);
        if (ja.isPresent()) {
            blind = 0;
            return fremdesJa(screen, ctx, jetzt, jaText(ctx, ja.get()), Sprache.t("the visit is over", "der Besuch ist zu Ende"));
        }
        if (victories(screen, ctx).isPresent()) {
            blind = 0;
            grenzen.werbungVorbei();
            grenzen.kampfZeichen(jetzt);
            return schliessen(screen, ctx);   // raus aus der Bucht
        }
        if (kampfBild(screen, ctx, runs, jetzt)) return warten(ctx);   // Kampf abwarten, nie X
        if (ctx.visibleIfKnown(screen, T_START) || ctx.visibleIfKnown(screen, T_CONFIRM)
                || ctx.visibleIfKnown(screen, T_DEPLOY)) {
            blind = 0;
            grenzen.kampfVorbei();
            return schliessen(screen, ctx);
        }
        if (ctx.visibleIfKnown(screen, T_KARTE) || Weltkarte.erkannt(screen, ctx)) {
            blind = 0;
            grenzen.kampfVorbei();
            Weltkarte.heim(screen, ctx, WO);
            return Outcome.ACTED;
        }
        if (!grenzen.videoHaengtNoch() && Stoerfenster.belohnungWegklicken(screen, ctx, WO)) return Outcome.ACTED;
        return unbekannt(screen, ctx, jetzt);
    }

    /**
     * Der Heimweg beginnt: einmal Fortschritt melden. Sonst kann der Totmann im Motor (15 Minuten
     * ohne Fortschritt) vor der Heimweg-Grenze zuschlagen -- dann steht die ganze Warteschlange
     * (Pruefung 05.10.). So hat der Heimweg seine vollen 5 Minuten.
     */
    private void heimwegMelden() {
        if (lauf != null) lauf.progress(Sprache.t("Gem Bay: way home", "Gem Bay: Heimweg"));
    }

    /** Die im Besuch gelesene Reset-Zeit jetzt vermerken (nur wenn das Zeitfenster erledigt ist). */
    private void resetVermerken() {
        if (resetUm != null) GemBayplan.resetMerken(resetUm);
    }

    /** Ende des Besuchs ohne Fehler: geschafft, nichts mehr zu tun, oder heute schon alles gespielt. */
    private void beenden(String grund) {
        if (fertig) return;
        fertig = true;
        heimwegMelden();
        Log.info(WO, grund);
        if (!vermerkt) {
            vermerkt = true;
            GemBayplan.erledigt();
            resetVermerken();
        }
    }

    /**
     * Ende des Besuchs mit Fehler. Zaehlt als Fehlversuch fuer das Zeitfenster: der erste darf
     * in 20 Minuten noch einmal, beim zweiten ist das Fenster erledigt (P4, GemBayplan).
     */
    private void abbrechen(BotContext ctx, String foto, String grund) {
        if (fertig) return;
        fertig = true;
        heimwegMelden();
        warnung(grund);
        if (foto != null) foto(ctx, foto);
        if (vermerkt) return;
        vermerkt = true;
        int n = GemBayplan.fehlschlag();
        if (GemBayplan.resetJetztMerken(false, n)) resetVermerken();
        if (n == 0) {
            Log.info(WO, Sprache.t("Cancelled outside a time window (started by hand) -- nothing noted.", "Abgebrochen ausserhalb eines Zeitfensters (von Hand gestartet) -- nichts vermerkt."));
        } else if (n >= 2) {
            Log.info(WO, Sprache.t("That was failed attempt no. ", "Das war der ") + n + Sprache.t(" in this time window -- only again in the next window.", ". Fehlversuch in diesem Zeitfenster -- erst im naechsten Fenster wieder."));
        } else {
            Log.info(WO, Sprache.t("First failed attempt in this time window -- one more try in 20 minutes", "Erster Fehlversuch in diesem Zeitfenster -- in 20 Minuten noch ein Versuch")
                    + (resetUm != null ? Sprache.t(" (the reset time is only noted after that)", " (die Reset-Zeit wird erst danach vermerkt)") : "") + ".");
        }
    }

    /** Nach einer Reise: alles fuer die naechste frisch (neuer Preis, neue Nahrung). */
    private void naechsteReise() {
        beendet = false;
        preis = -1;
        nahrung = -1;
        kistenVersucht = deployGedrueckt = reiheGeprueft = false;
        squadSeit = 0;
        blind = zuege = leseVersuche = preisVersuche = nachEnde = 0;
        nahrungLesenSeit = startGeklicktUm = jaGeklicktUm = autoGeklicktUm = 0;
        nahrungFotoGemacht = false;
    }

    // ------------------------------------------------------------------------------------

    /** Das Fenster "Gem Bay": Preis lesen und entscheiden. */
    private Outcome fenster(Mat screen, BotContext ctx, long jetzt) {
        // NIE zweimal "Start journey" (Autor, 06.10.): kurz nach dem Klick kann das Fenster noch im
        // Uebergang zu sehen sein -- dann weder lesen noch druecken. Steht es nach 3 s noch, ist der
        // Klick nicht angekommen, und es geht normal weiter.
        if (jetzt - startGeklicktUm < GemBayGrenzen.EINZELKLICK_SPERRE_MS) return warten(ctx);
        // Die Reset-Zeit zuerst: auch beim Diamant-Preis soll der Bot wissen, wann er wiederkommt.
        if (!resetGelesen) resetGelesen = resetLesen(screen, ctx);
        // Diamanten statt Nahrung: heute schon gespielt. NIE starten.
        if (ctx.visibleIfKnown(screen, T_DIAMANT) && !ctx.visibleIfKnown(screen, T_APFEL)) {
            beenden(Sprache.t("The price is in diamonds -- all food journeys for today are played", "Der Preis steht in Diamanten -- fuer heute sind alle Nahrungs-Reisen gespielt")
                    + (reisen > 0 ? " (" + reisen + Sprache.t(" in this round)", " in diesem Durchgang)") : "") + ".");
            return schliessen(screen, ctx);
        }
        if (preis < 0) {
            Optional<Matcher.Hit> apfel = apfelUnterStart(screen, ctx);
            OptionalLong p = apfel.isPresent() ? preisLesen(ctx, apfel.get()) : OptionalLong.empty();
            if (p.isEmpty()) {
                GemBayGrenzen.PreisArt art = preisArt(screen, ctx, apfel.isPresent());
                // Der erste Run am Tag ist kostenlos (Autor, 29.09.): "Free" statt Apfel und Zahl.
                if (art == GemBayGrenzen.PreisArt.FREI && !ctx.visibleIfKnown(screen, T_APFEL)) {
                    p = OptionalLong.of(0);
                    Log.info(WO, Sprache.t("The journey is free (\"Free\" on \"Start journey\").", "Die Reise ist kostenlos (\"Free\" auf \"Start journey\")."));
                } else if (art == GemBayGrenzen.PreisArt.ANDERER_PREIS && preisVersuche >= 1) {
                    // gb_preis_diamant fehlt noch: eine Zahl OHNE Apfel ist ein Preis in einer anderen
                    // Waehrung -- nach Autor (29.09.) Diamanten, also fuer heute alles gespielt.
                    // Es wird NICHTS angetippt, nur das Fenster geschlossen.
                    foto(ctx, "preis");
                    beenden(Sprache.t("Below \"Start journey\" there is a price without an apple (probably diamonds) -- ", "Unter \"Start journey\" steht ein Preis ohne Apfel (wohl Diamanten) -- fuer heute ")
                            + Sprache.t("all food journeys for today are played", "sind alle Nahrungs-Reisen gespielt") + (reisen > 0 ? " (" + reisen + Sprache.t(" in this round)", " in diesem Durchgang)") : "")
                            + Sprache.t(". Nothing is tapped.", ". Es wird nichts angetippt."));
                    return schliessen(screen, ctx);
                }
            }
            if (p.isEmpty()) {
                if (++preisVersuche < 4) {
                    ctx.sleep(tempo.ms(300));
                    return Outcome.WAITING;
                }
                abbrechen(ctx, "preis", Sprache.t("The price below \"Start journey\" is not readable -- no journey without ", "Der Preis unter \"Start journey\" ist nicht lesbar -- keine Reise ohne ")
                        + Sprache.t("a known price.", "bekannten Preis."));
                return schliessen(screen, ctx);
            }
            preis = p.getAsLong();
            if (preis > 0 && !GemBayGrenzen.reiseErlaubt(istAutor(), GemBayGrenzen.mehrAn(), GemBayGrenzen.PreisArt.NAHRUNG)) {
                // Freunde (Autor, 20.11.): nur kostenlose Reisen; Preis in Nahrung = fuer heute fertig, keine Kisten.
                beenden(Sprache.t("The journey costs food -- all free journeys for today are played", "Die Reise kostet Nahrung -- fuer heute sind alle kostenlosen Reisen gespielt")
                        + (reisen > 0 ? " (" + reisen + Sprache.t(" in this round)", " in diesem Durchgang)") : "")
                        + Sprache.t(". To play more: gembay-mehr.txt with \"an\".", ". Mehr spielen: gembay-mehr.txt mit \"an\"."));
                return schliessen(screen, ctx);
            }
            if (preis > 0) Log.info(WO, Sprache.t("The journey costs ", "Die Reise kostet ") + zahl(preis) + Sprache.t(" food.", " Nahrung."));
            // Kisten-Test (Autor, 06.10.: "testen, ob der Bot von der Edelsteinbucht in meine Tasche
            // zurueckfindet, eine Nahrungskiste oeffnen kann und dann wieder in die Edelsteinbucht
            // geht"): liegt gembay-kisten-test.txt im Bot-Ordner, gilt die Nahrung bei der naechsten
            // bezahlten Reise einmal als zu knapp. Die Datei wird dabei geloescht (einmalig).
            if (preis > 0 && nahrung >= 0 && java.nio.file.Files.exists(KISTEN_TEST)) {
                try { java.nio.file.Files.deleteIfExists(KISTEN_TEST); } catch (java.io.IOException ignored) { }
                Log.info(WO, Sprache.t("Chest test (gembay-kisten-test.txt): the food counts as too low once -- ", "Kisten-Test (gembay-kisten-test.txt): die Nahrung gilt einmal als zu knapp -- ")
                        + Sprache.t("castle, bag, food chest, back to the bay.", "Burg, Tasche, Nahrungskiste, zurueck in die Bucht."));
                nahrung = 0;
                kistenTest = true;
            }
        }
        if (nahrung < 0) {
            Log.info(WO, Sprache.t("Food not read yet -- to the castle first.", "Nahrung noch nicht abgelesen -- erst in die Burg."));
            return schliessen(screen, ctx);
        }
        if (kistenNoetig()) return schliessen(screen, ctx);
        // SPERRE (30.09., gb_preis_diamant fehlt noch): eine Reise nur starten, wenn der Preis
        // JETZT sicher in Nahrung steht (Apfel-Symbol unter "Start journey") oder "Free" zu lesen
        // ist -- nie Diamanten.
        boolean sicher = preis > 0 ? apfelUnterStart(screen, ctx).isPresent() : kostenlos(screen, ctx);
        if (!sicher) {
            abbrechen(ctx, "preis", Sprache.t("Neither an apple price nor \"Free\" clearly visible -- no journey (never diamonds).", "Weder Apfel-Preis noch \"Free\" sicher zu sehen -- keine Reise (nie Diamanten)."));
            return schliessen(screen, ctx);
        }
        // Pruefung 05.10.: nie eine bezahlte Reise kurz vor der Zeitgrenze starten (sie waere gleich
        // wieder abgebrochen). Lieber sauber heim; der Fehlversuch bringt in 20 Minuten einen neuen
        // Besuch mit frischen 15 Minuten.
        if (!grenzen.startErlaubt(jetzt)) {
            abbrechen(ctx, null, Sprache.t("Only ", "Vom Besuch sind nur noch ") + Math.max(0, (GemBayGrenzen.BESUCH_MAX_MS
                    - grenzen.besuchMs(jetzt)) / 60_000) + Sprache.t(" minutes of the visit left (a new journey needs at least ", " Minuten uebrig (fuer eine neue Reise mindestens ")
                    + GemBayGrenzen.START_REST_MS / 60_000 + Sprache.t(") -- no \"Start journey\", cleanly back to the castle.", ") -- kein \"Start journey\", sauber zurueck in die Burg."));
            return schliessen(screen, ctx);
        }
        deployGedrueckt = false;
        squadSeit = 0;
        if (!ctx.clickIfVisibleIfKnown(screen, T_START, WO + Sprache.t(": start the journey (Start journey, ", ": Reise starten (Start journey, ")
                + (preis == 0 ? Sprache.t("free", "kostenlos") : zahl(preis) + Sprache.t(" food", " Nahrung")) + ")")) {
            return kurzWarten(ctx);
        }
        // Die Sperre zaehlt ab dem KLICK, nicht ab Schrittanfang (Pruefung 06.10.): Reset-Zeit und
        // Preis lesen dauerten in Lauf 1 schon 1,9 s -- mit "jetzt" waere die Sperre nach dem
        // naechsten Schritt fast abgelaufen und "Start journey" koennte doch zweimal kommen.
        startGeklicktUm = System.currentTimeMillis();
        ctx.sleepUntilChanged(600);
        // Vorausklick mit Bestaetigung (Autor, 06.10.): per Kastenblick auf "Deploy the best ones"
        // warten und es sofort druecken, dann ebenso "Confirm" -- statt je einen ganzen Schritt
        // (Lauf 1: Start -> Deploy 0,65 s, Deploy -> Confirm 0,41 s).
        return aufstellungVoraus(ctx);
    }

    /**
     * Nach "Start journey": "Deploy the best ones" und "Confirm" per Kastenblick abwarten und sofort
     * druecken. Nur, was gesehen wird (Vorlage passt) -- nie blind. Ist keine Stelle bekannt (erste
     * Sitzung) oder kommt nichts, sieht der naechste Schritt wie bisher am ganzen Bild nach.
     * "Deploy" grau (schon aufgestellt): dann ist nur "Confirm" zu sehen und die 2,5-s-Regel im
     * normalen Schritt entscheidet -- hier wird "Confirm" nur nach eigenem "Deploy" gedrueckt.
     */
    private Outcome aufstellungVoraus(BotContext ctx) {
        Optional<Matcher.Hit> k = ctx.warteAufKnopf(VORAUS_MS, Matcher.DEFAULT_THRESHOLD, T_DEPLOY, T_CONFIRM);
        if (k.isPresent() && T_DEPLOY.equals(k.get().name())) {
            ctx.klick(k.get(), WO + Sprache.t(": best units (Deploy the best ones, click ahead)", ": Beste Einheiten (Deploy the best ones, Vorausklick)"));
            deployGedrueckt = true;
            if (squadSeit == 0) squadSeit = System.currentTimeMillis();
            // 08.10. (Tempo-Analyse): kein sleepUntilChanged(600) mehr -- in 2 von 4 Faellen lief es in
            // die Obergrenze (Deploy -> Confirm 716 statt 170 ms); warteAufKnopf sieht Confirm ohnehin
            // erst, wenn es da ist.
            return confirmVoraus(ctx);
        }
        return Outcome.ACTED;
    }

    /** Nach "Deploy": per Kastenblick auf "Confirm" warten und es sofort druecken (Mehrfachklick ungefaehrlich). */
    private Outcome confirmVoraus(BotContext ctx) {
        Optional<Matcher.Hit> c = ctx.warteAufKnopf(VORAUS_MS, Matcher.DEFAULT_THRESHOLD, T_CONFIRM);
        if (c.isEmpty() || !deployGedrueckt) return Outcome.ACTED;
        ctx.klick(c.get(), WO + Sprache.t(": confirm the squad (Confirm, click ahead)", ": Aufstellung bestaetigen (Confirm, Vorausklick)"));
        return nachConfirm(ctx);
    }

    /**
     * Nach "Confirm": Fortschritt melden, dann per Kastenblick abwarten, was kommt -- die Warnung
     * mit "Yes" (die drueckt NUR der naechste Schritt nach Textpruefung, nie hier) oder die Bucht.
     */
    private Outcome nachConfirm(BotContext ctx) {
        confirmUm = System.currentTimeMillis();
        if (lauf != null) lauf.progress(Sprache.t("Gem Bay: squad confirmed", "Gem Bay: Aufstellung bestaetigt"));
        ctx.sleepUntilChanged(600);
        ctx.warteAufKnopf(VORAUS_MS, Matcher.DEFAULT_THRESHOLD, buchtNamen(ctx, T_YES));
        return Outcome.ACTED;
    }

    /** Nach "Yes": per Kastenblick auf die Bucht warten, damit der naechste Schritt nicht zu frueh kommt. */
    private void buchtAbwarten(BotContext ctx) {
        ctx.warteAufKnopf(VORAUS_MS, Matcher.DEFAULT_THRESHOLD, buchtNamen(ctx));
    }

    /** Die Vorlagen, die die Bucht anzeigen (Victories dunkel/blau, Battle begins, Pause, Auto) -- plus weitere. */
    private static String[] buchtNamen(BotContext ctx, String... dazu) {
        List<String> namen = new java.util.ArrayList<>(List.of(dazu));
        namen.add(T_BEGINNT);
        namen.addAll(ctx.matcher().serie(T_VICTORIES));
        namen.add(T_PAUSE);
        namen.add(T_AUTO);
        return namen.toArray(new String[0]);
    }

    /** Hoechstens so lange per Kastenblick auf den naechsten erwarteten Knopf warten. */
    private static final long VORAUS_MS = 1500;

    /**
     * Steht "Free" auf "Start journey"? Nur mit gelesenem Wort -- nie raten: Ohne
     * Apfel koennte der Preis auch in Diamanten stehen (fuer den Diamanten gibt es
     * noch kein Bild), und Diamanten werden nie ausgegeben.
     */
    private boolean kostenlos(Mat screen, BotContext ctx) {
        if (ctx.visibleIfKnown(screen, T_APFEL) || ctx.visibleIfKnown(screen, T_DIAMANT)) return false;
        return preisArt(screen, ctx, false) == GemBayGrenzen.PreisArt.FREI;
    }

    /** Die Zeilen unter "Start journey" lesen und einordnen (Free, Zahl ohne Apfel, nichts). */
    private GemBayGrenzen.PreisArt preisArt(Mat screen, BotContext ctx, boolean apfel) {
        if (apfel) return GemBayGrenzen.PreisArt.NAHRUNG;
        Optional<Matcher.Hit> start = ctx.find(screen, T_START);
        BufferedImage bild = ctx.frame();
        if (start.isEmpty() || bild == null) return GemBayGrenzen.PreisArt.UNKLAR;
        Matcher.Hit s = start.get();
        int x = Math.max(0, s.x() - s.width() / 4);
        int y = Math.max(0, s.y());
        int w = Math.min(bild.getWidth() - x, s.width() * 3 / 2);
        int h = Math.min(bild.getHeight() - y, s.height() * 3);
        if (w <= 0 || h <= 0) return GemBayGrenzen.PreisArt.UNKLAR;
        List<String> zeilen = de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, h));
        GemBayGrenzen.PreisArt art = GemBayGrenzen.preisArt(zeilen, false);
        if (art != GemBayGrenzen.PreisArt.FREI) Log.debug(WO, "Unter \"Start journey\" gelesen: " + zeilen + " -> " + art);
        return art;
    }

    /**
     * Der Apfel des Preises -- nur UNTER "Start journey". Ein Apfel anderswo (etwa die Nahrung
     * oben in der Leiste) ist nicht der Preis: Steht der Preis in Diamanten, wuerde der Bot sonst
     * die Nahrungsmenge oben als Preis lesen und "Start journey" druecken.
     */
    private Optional<Matcher.Hit> apfelUnterStart(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_APFEL)) return Optional.empty();
        Optional<Matcher.Hit> start = ctx.find(screen, T_START);
        if (start.isEmpty()) return Optional.empty();
        Matcher.Hit s = start.get();
        org.opencv.core.Rect bereich = new org.opencv.core.Rect(s.x() - s.width() / 2, s.y() - s.height() / 2,
                s.width() * 9 / 4, s.height() * 7 / 2);
        Optional<Matcher.Hit> a = ctx.find(screen, T_APFEL);
        if (a.isPresent() && bereich.contains(new org.opencv.core.Point(a.get().centerX(), a.get().centerY()))) {
            return a;
        }
        return ctx.matcher().findImBereich(screen, T_APFEL, bereich, Matcher.DEFAULT_THRESHOLD);
    }

    /**
     * "Journey cost resets in: 15h 20m" ablesen und merken (Autor, 29.09.: Gem Bay
     * setzt sich nicht um 02:00 zurueck, sondern wenn diese Anzeige abgelaufen ist).
     * Danach schaut der Bot erst wieder vorbei, wenn die Zeit um ist. Vermerkt wird sie erst am
     * Ende des Besuchs (resetVermerken), sonst sperrt sie den zweiten Versuch (Pruefung 05.10.).
     *
     * @return true, wenn die Zeit gelesen wurde (sonst beim naechsten Blick noch einmal)
     */
    private boolean resetLesen(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> r = ctx.matcher().has(T_RESET) ? ctx.find(screen, T_RESET) : Optional.empty();
        BufferedImage bild = ctx.frame();
        if (r.isEmpty() || bild == null) {
            if (!resetFehltGemeldet) {
                resetFehltGemeldet = true;
                Log.info(WO, Sprache.t("The \"Journey cost resets in\" display is not visible (yet) (template ", "Die Anzeige \"Journey cost resets in\" ist (noch) nicht zu sehen (Vorlage ") + T_RESET
                        + Sprache.t(") -- the reset time is read at the next look.", ") -- die Reset-Zeit wird beim naechsten Blick gelesen."));
            }
            return false;
        }
        Matcher.Hit h = r.get();
        // Lauf 1 (06.10.): der Kasten rechts neben dem Anker (gleich breit, +8 hoch) blieb leer
        // ("Text: 0 Zeilen"), 2 s nach dem Karte-Klick -- das Fenster blendete wohl noch ein.
        // Jetzt: breiterer Kasten rechts, dazu einer unter dem Anker, beide 3-fach vergroessert
        // und verstaerkt (Nahrungskisten.textVerstaerkt); bis zu 3 Versuche je Besuch, beim
        // letzten ein Foto, damit sich der richtige Kasten ausmessen laesst.
        int xr = h.x() + h.width();
        int yr = Math.max(0, h.y() - 6);
        int wr = Math.min(bild.getWidth() - xr, h.width() * 3 / 2);
        int hr = Math.min(bild.getHeight() - yr, h.height() + 12);
        String text = "";
        java.time.Duration d = null;
        if (wr > 0 && hr > 0) {
            text = String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(xr, yr, wr, hr)));
            d = GemBayplan.dauerAusText(text);
            if (d == null) {
                String v = Nahrungskisten.textVerstaerkt(bild, xr, yr, wr, hr, true);
                if (v != null) {
                    d = GemBayplan.dauerAusText(v);
                    if (d != null) text = v;
                }
            }
        }
        if (d == null) {
            int xu = h.x(), yu = h.y() + h.height();
            int wu = Math.min(bild.getWidth() - xu, h.width() * 3 / 2);
            int hu = Math.min(bild.getHeight() - yu, h.height() + 10);
            if (wu > 0 && hu > 0) {
                String u = Nahrungskisten.textVerstaerkt(bild, xu, yu, wu, hu, true);
                if (u != null) {
                    d = GemBayplan.dauerAusText(u);
                    if (d != null) text = u;
                    else Log.debug(WO, "Reset-Zeit roh: rechts \"" + text + "\", darunter \"" + u + "\".");
                }
            }
        }
        if (d == null) {
            if (++resetVersuche < 3) return false;   // beim naechsten Blick ins Fenster noch einmal
            Log.info(WO, Sprache.t("The time until the reset was not readable after ", "Die Zeit bis zum Reset war nach ") + resetVersuche + Sprache.t(" tries (\"", " Versuchen nicht lesbar (\"") + text + "\").");
            foto(ctx, "fenster");
            return true;   // nicht bei jedem Blick neu lesen
        }
        java.time.LocalDateTime um = de.hustlebot.core.Zeitzone.jetzt().plus(d);
        resetUm = um;
        Log.info(WO, Sprache.t("Journey costs reset in ", "Reisekosten setzen sich zurueck in ") + text.trim() + Sprache.t(" -- so at ", " -- also um ")
                + um.toLocalTime().withSecond(0).withNano(0) + (um.toLocalDate().isAfter(de.hustlebot.core.Zeitzone.heute()) ? Sprache.t(" (tomorrow)", " (morgen)") : "")
                + Sprache.t(". This is noted when the visit completes the time window.", ". Vermerkt wird das, wenn der Besuch das Zeitfenster erledigt."));
        return true;
    }

    /** Die Zahl rechts vom Apfel unter "Start journey". */
    private OptionalLong preisLesen(BotContext ctx, Matcher.Hit a) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return OptionalLong.empty();
        OptionalLong p = ArenaLeser.zahlRechtsVon(bild, a, a.width() * 5);
        if (p.isEmpty() || p.getAsLong() < 1000) {
            p = Nahrungskisten.zahlVerstaerkt(bild, a.x() + a.width(), a.y() - 4, a.width() * 5, a.height() + 8, true);
        }
        if (p.isEmpty() || p.getAsLong() < 1000 || p.getAsLong() > 50_000_000L) return OptionalLong.empty();
        return p;
    }

    private static Optional<Matcher.Hit> jaKnopf(Mat screen, BotContext ctx) {
        return ctx.matcher().has(T_YES) ? ctx.find(screen, T_YES) : Optional.empty();
    }

    /**
     * Der Text eines Fensters mit "Yes", getrennt gelesen (Pruefung 05.10.): oben die Zeilen ueber
     * den Knoepfen (der Bereich vom 30.09., dort sauber gelesen), unten die Knopfreihe selbst --
     * steht auf einem Knopf ein Preis, ist er dort zu sehen (P9). Kleinbuchstaben.
     */
    private record JaText(String oben, String knoepfe) {
        boolean erlaubt() {
            return GemBayGrenzen.jaErlaubt(oben, knoepfe);
        }

        /** "Your squad is incomplete. You can add units from your reserve ..." (12.10., 12:11). */
        boolean truppUnvollstaendig() {
            String o = oben == null ? "" : oben;
            return o.contains("reserve") || o.contains("incomplete") || o.contains("add units");
        }

        @Override public String toString() {
            return oben + " | Knoepfe: " + knoepfe;
        }
    }

    private JaText jaText(BotContext ctx, Matcher.Hit ja) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return new JaText("", "");
        // Nur die Breite des Fensters lesen (Foto 08.10., 13:53): links und rechts daneben stehen die
        // Kampfwerte der Einheiten ("798"), die sonst als Preis gelten und das Yes verhindern.
        int x = Math.max(0, ja.centerX() - px(ctx, 140));
        int w = Math.min(bild.getWidth() - x, px(ctx, 480));
        // oben: endet 5 Pixel ueber dem Knopf (wie am 30.09.)
        int yOben = Math.max(0, ja.y() - px(ctx, 135));
        int hOben = Math.min(bild.getHeight() - yOben, px(ctx, 130));
        // die Knopfreihe: der Yes-Knopf mit 5 Pixel Rand, ueber die ganze Breite (auch "No")
        int yKnopf = Math.max(0, ja.y() - px(ctx, 5));
        int hKnopf = Math.min(bild.getHeight() - yKnopf, ja.height() + px(ctx, 10));
        if (w < 50 || hOben < 30) return new JaText("", "");
        String oben = lies(bild, x, yOben, w, hOben);
        String knoepfe = hKnopf > 0 ? lies(bild, x, yKnopf, w, hKnopf) : "";
        JaText text = new JaText(oben, knoepfe);
        Log.debug(WO, "Fenster mit Yes, Text: " + text);
        return text;
    }

    private static String lies(BufferedImage bild, int x, int y, int w, int h) {
        return String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, h)))
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Ein Fenster mit "Yes", das nicht bestaetigt werden darf: erst kurz warten (der Text
     * blendet ein), dann "No", sonst das rote X -- nie "Yes". Kommt das zweimal, endet der
     * Besuch (lieber eine Reise weniger als ein Kauf, Kauf-Vorfall 03.10.).
     */
    private Outcome fremdesJa(Mat screen, BotContext ctx, long jetzt, JaText text, String warum) {
        if (fremdesJaSeit == 0 || jetzt - fremdesJaZuletzt > 2000) fremdesJaSeit = jetzt;
        fremdesJaZuletzt = jetzt;
        if (jetzt - fremdesJaSeit < FREMDES_JA_MS) return warten(ctx);
        fremdesJaSeit = 0;
        warnung(Sprache.t("A window with \"Yes\" is open, but ", "Ein Fenster mit \"Yes\" ist offen, aber ") + warum + Sprache.t(" -- it is NOT confirmed. ", " -- es wird NICHT bestaetigt. ")
                + "Text: \"" + text + "\"");
        foto(ctx, "yes");
        boolean zu = ctx.clickIfVisibleIfKnown(screen, T_KAUF_NEIN, WO + Sprache.t(": decline the window (No, never Yes)", ": Fenster ablehnen (No, nie Yes)"))
                || ctx.clickIfVisibleIfKnown(screen, T_X, WO + Sprache.t(": close the window (red X, never Yes)", ": Fenster schliessen (rotes X, nie Yes)"));
        if (!fertig && ++fremdeJa >= 2) {
            abbrechen(ctx, null, Sprache.t("Twice a window with \"Yes\" that does not fit -- the visit ends.", "Zweimal ein Fenster mit \"Yes\", das nicht passt -- der Besuch wird beendet."));
        }
        if (zu) {
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        return warten(ctx);
    }

    /** Hoechstens so viele Reisen je Durchgang (gembay-reisen.txt, sonst MAX_REISEN). */
    private static int maxReisen() {
        try {
            java.nio.file.Path d = java.nio.file.Path.of("gembay-reisen.txt");
            if (java.nio.file.Files.exists(d)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(d)) {
                    String t = z.trim();
                    if (t.matches("[0-9]{1,2}")) return Integer.parseInt(t);
                }
            }
        } catch (Exception e) {
            // Standard
        }
        return MAX_REISEN;
    }

    /** In der Bucht: Wartezeit ueberspringen, Automodus einschalten, gestorbene Einheiten ersetzen. */
    private Outcome bucht(Mat screen, BotContext ctx, long jetzt) {
        // Warnung in der Bucht: Your squad does not have enough units ... Start the battle now?
        // Autor, 30.09.: Yes. Sie kommt nach dem eigenen Klick auf Auto; dahinter passt Auto
        // abgedunkelt weiter -- der Bot drueckte es 112 Mal. Yes nur, wenn der Text wirklich
        // die Trupp-Warnung ist: nie ein Kauf-Yes (Diamanten), nie mit Preis (P9).
        Optional<Matcher.Hit> ja = jaKnopf(screen, ctx);
        if (ja.isPresent()) {
            JaText text = jaText(ctx, ja.get());
            if (text.erlaubt()) {
                // Autor, 13.10. (Punkt 3): "Stirbt eine Einheit, geht der Bot raus, statt die Extra-Einheiten in
                // die freien Plaetze zu ziehen." Bisher: "Your squad is incomplete" -> sofort Yes (12.10., 12:11).
                // Jetzt: erst "No", dann die freien Plaetze aus der Reserve fuellen -- hoechstens 2 Anlaeufe je
                // Kampf, danach wie bisher Yes (lieber unvollstaendig kaempfen als haengen).
                // 24.10.: 3 Anlaeufe (zwei Wisch-Arten im Wechsel, siehe unten).
                if (text.truppUnvollstaendig() && nachziehVersuche < 3 && ctx.matcher().has(T_FREI)) {
                    nachziehVersuche++;
                    reiheGeprueft = false;
                    foto(ctx, "platz-frei");
                    if (!ctx.clickIfVisibleIfKnown(screen, T_KAUF_NEIN, WO + Sprache.t(": squad incomplete -- pull in the reserve first (No)", ": Trupp unvollstaendig -- erst Reserve nachziehen (No)"))) {
                        // "No" sitzt gespiegelt rechts neben "Yes" (Foto 10.10., 13:53).
                        int x = ctx.width() - ja.get().centerX(), y = ja.get().centerY();
                        Log.action("Klick", WO + Sprache.t(": squad incomplete -- pull in the reserve first (No, mirrored to Yes)  [", ": Trupp unvollstaendig -- erst Reserve nachziehen (No, gespiegelt zu Yes)  [") + x + "," + y + "]");
                        ctx.input().click(x, y, 2);
                    }
                    Log.info(WO, Sprache.t("Squad incomplete -- first fill the free slots from the reserve (attempt ", "Trupp unvollstaendig -- erst die freien Plaetze aus der Reserve fuellen (Anlauf ")
                            + nachziehVersuche + Sprache.t(" of 3).", " von 3)."));
                    ctx.sleepUntilChanged(700);
                    return Outcome.ACTED;
                }
                if (jetzt - jaGeklicktUm < GemBayGrenzen.EINZELKLICK_SPERRE_MS) return warten(ctx);   // NIE zweimal "Yes"
                ctx.klick(ja.get(), WO + Sprache.t(": squad incomplete -- fight anyway (Yes, text checked)", ": Trupp unvollstaendig -- trotzdem kaempfen (Yes, Text geprueft)"));
                jaGeklicktUm = System.currentTimeMillis();   // ab dem Klick, nicht ab Schrittanfang (06.10.)
                ctx.sleepUntilChanged(600);
                return Outcome.ACTED;
            }
            return fremdesJa(screen, ctx, jetzt, text, Sprache.t("the text is not the squad warning (or it contains a price)", "der Text ist nicht die Trupp-Warnung (oder es steht ein Preis darin)"));
        }
        // Autor 17.10.: Nach dem Video dauert es, bis die Einheiten wieder leben ("Waiting for ad network to respond",
        // bis zu einer Minute und mehr). Bis dahin nichts druecken ausser kurz raus (rotes X) und wieder hinein.
        if (wiederbelebungSeit > 0) {
            Outcome w = wiederbelebungAbwarten(screen, ctx, jetzt);
            if (w != null) return w;
        }
        // "Battle begins in: 7s" -> sofort druecken, mit Autoclicker (Autor, 06.10.); der Knopf
        // verschwindet, ein zweiter Klick richtet nichts an.
        Optional<Matcher.Hit> beginnt = ctx.matcher().has(T_BEGINNT) ? ctx.find(screen, T_BEGINNT) : Optional.empty();
        if (beginnt.isPresent()) {
            battleBeginsKlicken(ctx, beginnt.get(), kastenFuer(ctx, T_BEGINNT, K_BEGINNT));
            grenzen.kampfZeichen(jetzt);
            return Outcome.ACTED;
        }
        if (ctx.visibleIfKnown(screen, T_PAUSE)) {   // Automodus laeuft -- "Pause" NIE druecken (Autor, 06.10.)
            return fotozyklus(ctx);
        }
        // Live-Test 17.10. (Autor, Siege 13/15): alle Einheiten gefallen, der Trupp leer -- dann ist "Auto" GRAU
        // (Treffer 0,818) und der Bot kam hier nie weiter: kein "Watch", kein Hochziehen, nur "Siege 13/15" lesen.
        // Die Aufstellung erkennt er jetzt auch an "Reserve:" zusammen mit freiem Platz (+) oder "Watch".
        boolean autoDa = ctx.visibleIfKnown(screen, T_AUTO);
        boolean aufstellungDa = ctx.visibleIfKnown(screen, T_RESERVE)
                && (ctx.visibleIfKnown(screen, T_FREI) || ctx.visibleIfKnown(screen, T_WATCH));
        if (!autoDa && !aufstellungDa) return warten(ctx);

        // Automodus aus: erst nachsehen, ob eine Einheit fehlt (einmal nach links wischen).
        Optional<Matcher.Hit> reserve = ctx.matcher().has(T_RESERVE) ? ctx.find(screen, T_RESERVE) : Optional.empty();
        if (!reiheGeprueft && reserve.isPresent() && ctx.matcher().has(T_FREI)) {
            reiheGeprueft = true;
            Matcher.Hit r = reserve.get();
            // Autor 24.10. (Foto platz-frei-nicht-erkannt-131815): die 6. Einheit war gefallen, ihr Platz lag rechts
            // ausserhalb -- der schnelle Wisch oben auf den Abzeichen bewegte die Reihe nicht (griff wohl eine Einheit).
            // Jetzt langsam und abwechselnd: in der Luecke zwischen zwei Einheiten auf Kreis-Hoehe, sonst wie bisher.
            boolean luecke = reiheWischer++ % 2 == 0;
            int y = r.centerY() - px(ctx, luecke ? 73 : 122);
            int von = r.centerX() + px(ctx, luecke ? 207 : 270), nach = r.centerX() + px(ctx, luecke ? -93 : 40);
            Log.action(WO, Sprache.t("Swipe the squad slowly to the left (", "Aufstellung langsam nach links wischen (") + (luecke ? Sprache.t("between two units", "zwischen zwei Einheiten") : Sprache.t("over the badges", "ueber den Abzeichen"))
                    + Sprache.t(") -- has a slot become free?", ") -- ist ein Platz frei geworden?"));
            ctx.input().dragLangsam(von, y, nach, y);
            ctx.sleepUntilChanged(700);
            return Outcome.ACTED;
        }
        // Freien Platz (+) suchen -- etwas grosszuegiger (0,78 statt 0,84) und den linkesten nehmen (14.10.):
        // am 12.10. wurde der Platz nach dem Wischen nicht erkannt, der Bot kaempfte unvollstaendig.
        Optional<Matcher.Hit> frei = Optional.empty();
        if (ctx.matcher().has(T_FREI)) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, T_FREI, 0.78, 12)) {
                if (frei.isEmpty() || h.centerX() < frei.get().centerX()) frei = Optional.of(h);
            }
        }
        if (frei.isEmpty() && nachziehVersuche > 0 && reiheGeprueft && reserve.isPresent()
                && jetzt - nachziehGemeldetUm > 20_000) {
            nachziehGemeldetUm = jetzt;
            warnung(Sprache.t("Squad incomplete, but no free slot (+) recognised in the squad -- photo ", "Trupp unvollstaendig, aber kein freier Platz (+) in der Aufstellung erkannt -- Foto ")
                    + Sprache.t("platz-frei-nicht-erkannt. Then the fight goes ahead incomplete.", "platz-frei-nicht-erkannt. Es wird dann unvollstaendig gekaempft."));
            foto(ctx, "platz-frei-nicht-erkannt");
        }
        if (frei.isPresent() && reserve.isPresent()) {
            // Autor 17.10. (Live-Test): "Du musst erst das Video anschauen" -- ist das Team gefallen (Trupp leer, rote
            // Balken in der Reserve) und das Video heute noch frei, ZUERST "Restore all units". Vorher zog der Bot die
            // 3 noch lebenden Reserve-Einheiten hoch und kaempfte unvollstaendig weiter. Faellt nur ein Teil, wird wie
            // bisher aus der Reserve aufgefuellt (13.10.) -- das Video bleibt fuer den Fall, dass alle fallen.
            if (!GemBayplan.watchBenutzt() && !lebtNoch(screen, ctx, reserve.get()) && toteInReserve(ctx, reserve.get())
                    && ctx.visibleIfKnown(screen, T_WATCH)) {
                einheitenMerken(ctx, reserve.get());   // vor dem Klick: das Bild zeigt noch, wer gefallen ist
                if (ctx.clickIfVisibleIfKnown(screen, T_WATCH, WO + Sprache.t(": restore all units (Restore all units, video)", ": Alle Einheiten wiederherstellen (Restore all units, Video)"))) {
                    videoStarten(jetzt);
                    Log.info(WO, Sprache.t("The team has fallen -- first the video for \"Restore all units\". Meanwhile ", "Das Team ist gefallen -- zuerst das Video fuer \"Restore all units\". Waehrenddessen ")
                            + Sprache.t("nothing is clicked; afterwards the team units are pulled up first.", "wird nichts angeklickt; danach werden erst die Team-Einheiten hochgezogen."));
                    return Outcome.ACTED;
                }
            }
            int[] einheit = lebendeReserve(ctx, reserve.get());
            if (einheit != null) {
                reserveSchiebungen = 0;
                gesperrtGesehen = false;
                Log.action(WO, Sprache.t("Fill a free slot: ", "Freien Platz fuellen: ") + (einheit[2] == 1 ? Sprache.t("team unit", "Team-Einheit") : einheit[2] == 2
                        ? Sprache.t("reserve unit", "Reserve-Einheit") : Sprache.t("unit", "Einheit")) + Sprache.t(" at ", " bei ") + einheit[0] + "," + einheit[1] + Sprache.t(" drag in.", " hineinziehen."));
                ctx.input().drag(einheit[0], einheit[1], frei.get().centerX(), frei.get().centerY());
                ctx.sleepUntilChanged(900);
                return Outcome.ACTED;
            }
            // Autor 17.10.: Die Reserve zeigt nur 6 Einheiten -- "in diese Richtung scrollen, dass er alle Einheiten
            // sieht, sonst kann er nur drei reinziehen". Keine lebende zu sehen, aber noch ein Platz frei: die Reihe
            // weiterschieben -- erst wie Autors Pfeil (nach rechts ziehen), dann zurueck; hoechstens 6 Mal je Aufstellung.
            // Autor 17.10.: "nach oben ziehen, nicht auf die Seite" -- die Lebenden stehen in der Reihe immer links vor
            // den Toten. Ist ein roter Balken zu sehen, sind also alle Lebenden schon im Bild: nicht schieben.
            if (reserveSchiebungen < 6 && !toteInReserve(ctx, reserve.get())) {
                reserveSchiebungen++;
                boolean wiePfeil = reserveSchiebungen <= 3;
                int y = reserve.get().centerY() + px(ctx, 77);
                int von = reserve.get().centerX() + px(ctx, wiePfeil ? -150 : 200);
                int nach = reserve.get().centerX() + px(ctx, wiePfeil ? 200 : -150);
                Log.action(WO, Sprache.t("Move the reserve on (", "Reserve weiterschieben (") + reserveSchiebungen + "/6, " + (wiePfeil ? Sprache.t("to the right", "nach rechts") : Sprache.t("to the left", "nach links"))
                        + Sprache.t(") -- looking for more living units.", ") -- weitere lebende Einheiten suchen."));
                ctx.input().dragLangsam(von, y, nach, y);
                ctx.sleepUntilChanged(700);
                return Outcome.ACTED;
            }
            // Autor 17.10.: "Wenn er wieder stirbt, dann erst alle Reserve-Einheiten" -- keine Team-Einheit mehr zu
            // finden, aber gemerkte Reserve-Einheiten leben: jetzt duerfen sie hoch (neue Suche durch die Reihe).
            if (!reserveErlaubt && gesperrtGesehen) {
                reserveErlaubt = true;
                gesperrtGesehen = false;
                reserveSchiebungen = 0;
                Log.info(WO, Sprache.t("No team unit alive any more -- now the reserve units go into the squad.", "Keine Team-Einheit mehr am Leben -- jetzt kommen die Reserve-Einheiten in den Trupp."));
                return Outcome.ACTED;
            }
            // "Restore all units" geht nur einmal am Tag (Autor, 29.09.).
            if (!lebtNoch(screen, ctx, reserve.get()) && GemBayplan.watchBenutzt()) {
                // Autor 17.10.: "Wenn du wieder verlierst, dann soll der Bot oben auf Claim reward druecken. Und einfach
                // weitermachen mit den Edelsteinbuchten." Danach kommt "Journey over!" wie nach 15 Siegen.
                if (obenClaims < 2 && ctx.clickIfVisibleIfKnown(screen, T_CLAIM_OBEN, WO + Sprache.t(": end the journey ", ": Reise beenden ")
                        + Sprache.t("(Claim reward at the top) -- all units fell, the video is already used", "(Claim reward oben) -- alle Einheiten gefallen, das Video ist schon benutzt"))) {
                    obenClaims++;
                    Log.info(WO, Sprache.t("All units have fallen, \"Restore all units\" is already used -- the journey is ", "Alle Einheiten sind gefallen, \"Restore all units\" ist schon benutzt -- die Reise wird ")
                            + Sprache.t("ended with \"Claim reward\", then the next journey follows.", "mit \"Claim reward\" beendet, danach geht es mit der naechsten Reise weiter."));
                    ctx.sleepUntilChanged(1200);
                    return Outcome.ACTED;
                }
                // raus ueber das rote X, in der Burg dann FINISHED; vermerkt, damit der Bot nicht
                // nach 20 Minuten im selben Fenster wieder hineingeht
                beenden(Sprache.t("All units have fallen, and \"Restore all units\" is already used today. ", "Alle Einheiten sind gefallen, und \"Restore all units\" ist heute schon benutzt. ")
                        + Sprache.t("Gem Bay ends for now (the journey stays open).", "Gem Bay wird fuer jetzt beendet (die Reise bleibt offen)."));
                return Outcome.ACTED;
            }
            if (!lebtNoch(screen, ctx, reserve.get()) && ctx.visibleIfKnown(screen, T_WATCH)) {
                einheitenMerken(ctx, reserve.get());
                if (ctx.clickIfVisibleIfKnown(screen, T_WATCH, WO + Sprache.t(": restore all units (Restore all units, video)", ": Alle Einheiten wiederherstellen (Restore all units, Video)"))) {
                    videoStarten(jetzt);
                    Log.info(WO, Sprache.t("All units have fallen -- video for \"Restore all units\". Meanwhile ", "Alle Einheiten sind gefallen -- Video fuer \"Restore all units\". Waehrenddessen ")
                            + Sprache.t("nothing is clicked.", "wird nichts angeklickt."));
                    return Outcome.ACTED;
                }
            }
        }
        // Nie zweimal kurz hintereinander (30.09.: 112 Klicks auf das abgedunkelte Auto hinter der Warnung).
        if (jetzt - autoGeklicktUm < GemBayGrenzen.EINZELKLICK_SPERRE_MS) return warten(ctx);
        if (!ctx.clickIfVisibleIfKnown(screen, T_AUTO, WO + Sprache.t(": switch on auto mode (Auto)", ": Automodus einschalten (Auto)"))) return warten(ctx);
        // Erst NACH dem wirklichen Klick merken (Pruefung 06.10.): vorher wurde die Zeit auch
        // gesetzt, wenn gar nicht geklickt wurde -- und dann 3 s lang nichts versucht.
        autoGeklicktUm = System.currentTimeMillis();
        ctx.sleepUntilChanged(600);
        return Outcome.ACTED;
    }

    /** Ein Lebensbalken unter einer Einheit der Reserve: Mitte x, Zeile y, gruen (lebt) oder rot (tot). */
    record Balken(int x, int y, boolean lebt) {}

    /**
     * Alle Lebensbalken der sichtbaren Reserve. Live-Test 17.10. (Autor: "Grau heisst tot"): Vorher wurde ueber den
     * Bildern nach bunten Punkten gesucht -- dort sitzen die roten Faehigkeits-Abzeichen, die auch bei toten Einheiten
     * bunt sind. Jetzt zaehlt der LEBENSBALKEN unter jeder Einheit (gemessen: 127 px unter dem Wort "Reserve:",
     * ~50 px breit): gruen = lebt, rot (D84922) = tot. Das Bild der Einheit sitzt 50 px darueber.
     */
    private static List<Balken> balken(BotContext ctx, Matcher.Hit reserve) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return List.of();
        return balken(bild, reserve.centerY() + px(ctx, 127), reserve.centerX() - px(ctx, 320),
                reserve.centerX() + px(ctx, 300), px(ctx, 12));
    }

    static List<Balken> balken(BufferedImage bild, int y, int von, int bis, int mindestens) {
        List<Balken> liste = new java.util.ArrayList<>();
        von = Math.max(0, von);
        bis = Math.min(bild.getWidth() - 1, bis);
        int start = -1, gruen = 0, rot = 0;
        for (int x = von; x <= bis + 1; x++) {
            int art = x <= bis ? balkenArt(bild, x, y) : 0;   // 1 = gruen, 2 = rot, 0 = kein Balken
            if (art != 0) {
                if (start < 0) { start = x; gruen = 0; rot = 0; }
                if (art == 1) gruen++; else rot++;
            } else if (start >= 0) {
                if (x - start >= mindestens) liste.add(new Balken((start + x) / 2, y, gruen > rot));
                start = -1;
            }
        }
        return liste;
    }

    /**
     * Die naechste LEBENDE Einheit fuer einen freien Platz: {x, y, art} (art 1 = Team, 0 = unbekannt, 2 = Reserve) oder null.
     * Autor 17.10.: erst die Team-Einheiten (die beim Video gefallen waren), dann unbekannte, die Reserve-Einheiten
     * (die beim Video noch lebten) erst, wenn keine Team-Einheit mehr lebt (reserveErlaubt). Ohne gemerkte Bilder,
     * etwa bei einem Spieler ohne Reserve, ist jede Einheit "unbekannt" und darf hoch.
     */
    private int[] lebendeReserve(BotContext ctx, Matcher.Hit reserve) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return null;
        Balken unbekannt = null, gesperrt = null;
        for (Balken b : balken(ctx, reserve)) {
            if (!b.lebt()) continue;
            int art = art(einheitBild(bild, b, px(ctx, 64), px(ctx, 50)));
            if (art == 1) return ziel(ctx, b, 1);
            if (art == 2) { if (gesperrt == null) gesperrt = b; }
            else if (unbekannt == null) unbekannt = b;
        }
        if (unbekannt != null) return ziel(ctx, unbekannt, 0);
        if (gesperrt != null) {
            if (reserveErlaubt) return ziel(ctx, gesperrt, 2);
            gesperrtGesehen = true;
        }
        return null;
    }

    private static int[] ziel(BotContext ctx, Balken b, int art) {
        return new int[]{b.x(), b.y() - px(ctx, 50), art};
    }

    /** 1 = sieht aus wie eine gemerkte Team-Einheit, 2 = wie eine gemerkte Reserve-Einheit, 0 = unbekannt. */
    private int art(BufferedImage einheit) {
        if (einheit == null) return 0;
        double team = 0, reserve = 0;
        for (BufferedImage t : teamBilder) team = Math.max(team, aehnlich(einheit, t));
        for (BufferedImage r : reserveBilder) reserve = Math.max(reserve, aehnlich(einheit, r));
        if (team >= RESERVE_GLEICH && team >= reserve) return 1;
        return reserve >= RESERVE_GLEICH ? 2 : 0;
    }

    /** Steht in der sichtbaren Reserve mindestens eine gefallene Einheit (roter Balken)? */
    private static boolean toteInReserve(BotContext ctx, Matcher.Hit reserve) {
        for (Balken b : balken(ctx, reserve)) if (!b.lebt()) return true;
        return false;
    }

    /** Ein Punkt des Lebensbalkens: 1 = gruen (lebt), 2 = rot (tot), 0 = kein Balken. Drei Zeilen, der Balken ist ~5 px hoch. */
    private static int balkenArt(BufferedImage b, int x, int y) {
        int g = 0, r = 0;
        for (int dy = -2; dy <= 2; dy += 2) {
            int yy = y + dy;
            if (yy < 0 || yy >= b.getHeight()) continue;
            int p = b.getRGB(x, yy);
            int rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
            if (gg > 140 && gg > rr + 40 && gg > bb + 40) g++;
            else if (rr > 170 && gg < 130 && bb < 90) r++;
        }
        return g >= 2 ? 1 : r >= 2 ? 2 : 0;
    }

    /**
     * Steht im Trupp ("Current squad") noch eine Einheit? Live-Test 17.10.: Nach einer Niederlage wandern die Toten in
     * die Reserve, der Trupp ist dann leer -- alle sichtbaren Plaetze sind frei (+). Vorher wurde ueber den Plaetzen
     * nach Farbe gesucht und dabei die tuerkise Schrift "Current squad:" fuer eine Einheit gehalten -- dann kam nie
     * "Watch". Jetzt: weniger als 5 freie Plaetze sichtbar = es steht noch jemand.
     */
    private static boolean lebtNoch(Mat screen, BotContext ctx, Matcher.Hit reserve) {
        return ctx.matcher().findAll(screen, T_FREI, 0.78, 12).size() < 5;
    }

    // --- Autor 17.10.: Team und Reserve merken, nach dem Video auf die Wiederbelebung warten --------------------

    /**
     * Nach dem Video: warten, bis in der Reserve kein roter Balken mehr steht und gruene da sind (zweimal gesehen).
     * Bis dahin wird nichts gedrueckt -- nur alle NEU_BETRETEN_MS das rote X: raus in die Burg, die Burg-Schritte
     * gehen ueber "Map" und "Gem Bay" wieder hinein (Autor: dann antwortet das Werbe-Netz schneller).
     * null = wiederbelebt (oder aufgegeben), es geht im normalen Ablauf weiter.
     */
    private Outcome wiederbelebungAbwarten(Mat screen, BotContext ctx, long jetzt) {
        Optional<Matcher.Hit> reserve = ctx.matcher().has(T_RESERVE) ? ctx.find(screen, T_RESERVE) : Optional.empty();
        List<Balken> alle = reserve.isPresent() ? balken(ctx, reserve.get()) : List.of();
        boolean rot = alle.stream().anyMatch(b -> !b.lebt());
        boolean gruen = alle.stream().anyMatch(Balken::lebt);
        long d = (jetzt - wiederbelebungSeit) / 1000;
        if (gruen && !rot) {
            if (belebtGesehenUm == 0) {   // einmal bestaetigen: das Bild kann gerade einblenden
                belebtGesehenUm = jetzt;
                ctx.sleep(1000);
                return Outcome.WAITING;
            }
            wiederbelebungSeit = belebtGesehenUm = 0;
            reiheGeprueft = false;
            reserveSchiebungen = 0;
            reserveErlaubt = gesperrtGesehen = false;
            videoVergessen();
            Log.info(WO, Sprache.t("All units are green again (", "Alle Einheiten sind wieder gruen (") + d + Sprache.t(" s after the video start) -- now first the ", " s nach dem Video-Start) -- jetzt kommen erst die ")
                    + Sprache.t("team units go up, the reserve only when the team falls again.", "Team-Einheiten hoch, die Reserve erst, wenn das Team noch einmal faellt."));
            return null;
        }
        belebtGesehenUm = 0;
        if (jetzt - wiederbelebungSeit > WIEDERBELEBUNG_MAX_MS) {
            Log.alarm(WO, Sprache.t("The units are still not revived ", "Die Einheiten sind ") + d / 60 + Sprache.t(" minutes after the video. Carrying on without them: ", " Minuten nach dem Video noch nicht wiederbelebt. Es geht ohne ")
                    + Sprache.t("first the reserve, if it falls too, \"Claim reward\" and the next journey.", "weiter: erst die Reserve, faellt die auch, \"Claim reward\" und die naechste Reise."));
            foto(ctx, "wiederbelebung");
            wiederbelebungSeit = 0;
            videoVergessen();
            return null;
        }
        if (jetzt - neuBetretenUm >= NEU_BETRETEN_MS && ctx.clickIfVisibleIfKnown(screen, T_X,
                WO + Sprache.t(": briefly out of the bay and straight back in (units still grey, ", ": kurz raus aus der Bucht und gleich wieder hinein (Einheiten noch grau, ") + d + " s)")) {
            neuBetretenUm = jetzt;
            ctx.sleepUntilChanged(1200);
            return Outcome.ACTED;
        }
        ctx.sleep(1000);
        return Outcome.WAITING;
    }

    /** "Watch" ist gedrueckt: ab jetzt laeuft das Video (nichts anklicken), die Zeit wird fuer einen Neustart vermerkt. */
    private void videoStarten(long jetzt) {
        phase = Phase.WERBUNG;
        videoGesehen = false;
        grenzen.videoGestartet(jetzt);
        GemBayplan.watchVermerken();
        try {
            java.nio.file.Files.createDirectories(RESERVE_ORDNER);
            java.nio.file.Files.writeString(VIDEO_UM, Long.toString(jetzt));
        } catch (Exception e) {
            Log.debug(WO, "Video-Zeit nicht vermerkt: " + e.getMessage());
        }
    }

    private static long videoUm() {
        try {
            return java.nio.file.Files.exists(VIDEO_UM) ? Long.parseLong(de.hustlebot.core.Dateien.text(VIDEO_UM).trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void videoVergessen() {
        try {
            java.nio.file.Files.deleteIfExists(VIDEO_UM);
        } catch (Exception e) {
            Log.debug(WO, "Video-Zeit nicht geloescht: " + e.getMessage());
        }
    }

    /**
     * Autor 17.10.: "Der Bot soll sich das Team merken" -- jeder Spieler hat andere Einheiten, manche gar keine Reserve.
     * Darum lernt der Bot es selbst, im Augenblick der Niederlage vor "Watch": wer jetzt gefallen ist (rot), gehoert
     * zum Team; wer noch lebt (gruen), ist Reserve. Die Bilder liegen in gembay-reserve\ und gelten bis zum
     * naechsten Video (auch nach einem Neustart).
     */
    private void einheitenMerken(BotContext ctx, Matcher.Hit reserve) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        List<BufferedImage> team = new java.util.ArrayList<>(), res = new java.util.ArrayList<>();
        for (Balken b : balken(ctx, reserve)) {
            BufferedImage e = einheitBild(bild, b, px(ctx, 64), px(ctx, 50));
            if (e != null) (b.lebt() ? res : team).add(e);
        }
        try {
            java.nio.file.Files.createDirectories(RESERVE_ORDNER);
            try (var alt = java.nio.file.Files.newDirectoryStream(RESERVE_ORDNER, "*.png")) {
                for (java.nio.file.Path p : alt) java.nio.file.Files.delete(p);
            }
            for (int i = 0; i < team.size(); i++)
                javax.imageio.ImageIO.write(team.get(i), "png", RESERVE_ORDNER.resolve("team-" + (i + 1) + ".png").toFile());
            for (int i = 0; i < res.size(); i++)
                javax.imageio.ImageIO.write(res.get(i), "png", RESERVE_ORDNER.resolve("reserve-" + (i + 1) + ".png").toFile());
        } catch (Exception e) {
            Log.debug(WO, "Einheiten-Bilder nicht gespeichert: " + e.getMessage());
        }
        teamBilder = team;
        reserveBilder = res;
        Log.info(WO, Sprache.t("Noted: ", "Gemerkt: ") + team.size() + Sprache.t(" fallen team units, ", " gefallene Team-Einheiten, ") + res.size() + Sprache.t(" living reserve units.", " lebende Reserve-Einheiten."));
    }

    /** Die gemerkten Bilder laden: [0] Team, [1] Reserve. */
    private static List<List<BufferedImage>> einheitenLaden() {
        List<BufferedImage> team = new java.util.ArrayList<>(), res = new java.util.ArrayList<>();
        if (java.nio.file.Files.isDirectory(RESERVE_ORDNER)) {
            try (var dateien = java.nio.file.Files.newDirectoryStream(RESERVE_ORDNER, "*.png")) {
                for (java.nio.file.Path p : dateien) {
                    BufferedImage b = javax.imageio.ImageIO.read(p.toFile());
                    if (b == null) continue;
                    String n = p.getFileName().toString();
                    if (n.startsWith("team-")) team.add(b);
                    else if (n.startsWith("reserve-")) res.add(b);
                }
            } catch (Exception e) {
                Log.debug(WO, "Einheiten-Bilder nicht geladen: " + e.getMessage());
            }
        }
        return List.of(team, res);
    }

    /** Das Bild einer Einheit ueber ihrem Balken (Kante x Kante), oder null am Bildrand. */
    static BufferedImage einheitBild(BufferedImage bild, Balken b, int kante, int hoch) {
        int x = b.x() - kante / 2, y = b.y() - hoch - kante / 2;
        if (x < 0 || y < 0 || x + kante > bild.getWidth() || y + kante > bild.getHeight()) return null;
        BufferedImage kopie = new BufferedImage(kante, kante, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = kopie.createGraphics();
        g.drawImage(bild.getSubimage(x, y, kante, kante), 0, 0, null);
        g.dispose();
        return kopie;
    }

    /**
     * Wie aehnlich sind zwei Einheiten-Bilder (-1..1)? Das Mittelstueck von a (3/4 der Kante) wird ueber b geschoben,
     * verglichen wird hell/dunkel im Verhaeltnis (Korrelation) -- so passt eine graue (tote) Einheit auch zu ihrem
     * bunten (lebenden) Bild.
     */
    static double aehnlich(BufferedImage a, BufferedImage b) {
        int k = Math.min(Math.min(a.getWidth(), a.getHeight()), Math.min(b.getWidth(), b.getHeight()));
        int s = k * 3 / 4, rand = (k - s) / 2;
        double[] ga = grau(a), gb = grau(b);
        // Mittelstueck von a ohne Mittelwert
        double[] stueck = new double[s * s];
        double summe = 0;
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) summe += stueck[y * s + x] = ga[(y + rand) * a.getWidth() + x + rand];
        double mittel = summe / stueck.length, aa = 0;
        for (int i = 0; i < stueck.length; i++) {
            stueck[i] -= mittel;
            aa += stueck[i] * stueck[i];
        }
        if (aa == 0) return 0;
        // Live-Test 17.10.: in 2er-Schritten verschoben fand die rosa Reserve-Einheit bei 1 px Versatz nur 0,69 --
        // feine Haare/Glanz verrutschen. Jetzt jeder Pixel.
        double best = -1;
        int w = b.getWidth();
        for (int oy = 0; oy + s <= b.getHeight(); oy++)
            for (int ox = 0; ox + s <= w; ox++) {
                double sb = 0, sbb = 0, sab = 0;
                for (int y = 0; y < s; y++) {
                    int zeile = (oy + y) * w + ox;
                    for (int x = 0; x < s; x++) {
                        double v = gb[zeile + x];
                        sb += v;
                        sbb += v * v;
                        sab += stueck[y * s + x] * v;
                    }
                }
                double n = (double) s * s, varianz = sbb - sb * sb / n;
                if (varianz > 0) best = Math.max(best, sab / Math.sqrt(aa * varianz));
            }
        return best;
    }

    /** Helligkeit jedes Bildpunkts (Zeile fuer Zeile). */
    private static double[] grau(BufferedImage b) {
        int w = b.getWidth(), h = b.getHeight();
        double[] v = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = b.getRGB(x, y);
                v[y * w + x] = 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
            }
        return v;
    }

    /** Offene Fassung: kein Lizenzschluessel -- mehr als kostenlose Reisen nur mit gembay-mehr.txt = "an". */
    private static boolean istAutor() {
        return false;
    }

    private boolean kistenNoetig() {
        return preis > 0 && nahrung >= 0 && nahrung < preis
                && GemBayGrenzen.kistenErlaubt(istAutor(), GemBayGrenzen.mehrAn());
    }

    private Outcome schliessen(Mat screen, BotContext ctx) {
        if (ctx.clickIfVisibleIfKnown(screen, T_X, WO + Sprache.t(": close the window", ": Fenster schliessen"))) {
            ctx.sleepUntilChanged(800);
            return Outcome.ACTED;
        }
        return kurzWarten(ctx);
    }

    /**
     * Foto des ganzen Bildes nach logs\gembay-ANLASS-HHmmss.png -- daraus lassen sich fehlende
     * Vorlagen ausschneiden (Diamant-Preis, Kampf-Ende, fremdes Yes-Fenster). Hoechstens
     * MAX_FOTOS je Besuch; seit 01.11. nur noch mit beweisfotos.txt (core.Beleg).
     */
    private void foto(BotContext ctx, String anlass) {
        BufferedImage bild = ctx.frame();
        if (bild == null || fotos >= MAX_FOTOS) return;
        fotos++;
        try {
            java.nio.file.Path datei = java.nio.file.Path.of("logs", "gembay-" + anlass + "-"
                    + de.hustlebot.core.Zeitzone.uhrzeit().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            if (de.hustlebot.core.Beleg.schreiben(bild, "png", datei.toFile())) Log.info(WO, Sprache.t("Photo: ", "Foto: ") + datei);
        } catch (Exception e) {
            Log.debug(WO, "Foto nicht gespeichert: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------
    //  Tempo (Autor, 06.10.): Victories-Serie, Fotozyklus, Kastenaufnahme, Autoclicker
    // ------------------------------------------------------------------------------------

    /**
     * Die Leiste "Victories" -- dunkel ODER blau (Lauf 1, 06.10.: ab Sieg 5 liegt das Wort auf dem
     * blauen Balken). serie(T_VICTORIES) liefert gb_victories, gb_victories_blau und jede spaetere
     * gb_victories_*; genommen wird der erste Treffer (dunkel zuerst).
     */
    private static Optional<Matcher.Hit> victories(Mat screen, BotContext ctx) {
        for (String name : ctx.matcher().serie(T_VICTORIES)) {
            Optional<Matcher.Hit> h = ctx.find(screen, name);
            if (h.isPresent()) return h;
        }
        return Optional.empty();
    }

    /**
     * Der Kasten fuer die Kastenaufnahme einer Vorlage: die vom Matcher gemerkte Stelle (in dieser
     * Fenstergroesse) mit KASTEN_LUFT drumherum -- sonst der feste Kasten aus Lauf 1, auf die
     * Fenstergroesse umgerechnet. Null, wenn nichts davon geht.
     */
    private static int[] kastenFuer(BotContext ctx, String name, int[] grund) {
        List<int[]> bekannt = ctx.matcher().kaesten(name, ctx.width(), ctx.height(), KASTEN_LUFT);
        if (!bekannt.isEmpty()) return bekannt.get(0);
        return GemBayGrenzen.kasten(ctx.width(), ctx.height(), grund);
    }

    /** Darf der Fotozyklus laufen? Nur im laufenden Besuch, wenn ein Kampf laufen kann und sicher keine Werbung. */
    private boolean kampfTaktErlaubt(long jetzt) {
        return !fertig && !beendet && phase == Phase.NORMAL && grenzen.kampfMoeglich(jetzt)
                && !grenzen.videoMoeglich(jetzt) && !grenzen.videoHaengtNoch();
    }

    /**
     * FOTOZYKLUS (Autor, 06.10.: "bau Fotozyklen und Multi-/Vorausklicks ein, fast clicks /
     * autoclicker"): bis FOTOZYKLUS_MS lang alle FOTOZYKLUS_TAKT_MS NUR den kleinen Kasten um
     * "Battle begins" fotografieren (Kastenaufnahme, kein ganzes Bild) und bei Treffer sofort
     * druecken -- mit Autoclicker. Lauf 1: ein Schritt am ganzen Bild dauerte 0,72-0,78 s, der Knopf
     * wurde also im Mittel ~0,4 s nach dem Erscheinen gedrueckt; jetzt hoechstens ein Takt.
     *
     * Alle 5 Blicke zusaetzlich der Kasten um "Claim reward": sieht er etwas, ist die Reise
     * vorbei -- geklickt wird das aber NUR im naechsten ganzen Schritt unter der Ueberschrift
     * "Journey over!" (das orange "Claim reward" in der Bucht beendet die Reise vorzeitig).
     *
     * Nach spaetestens FOTOZYKLUS_MS kommt der Motor zurueck und macht ein ganzes Bild -- der
     * Sicherheitsblick (Kauf-Fenster, Update, anderes Geraet, Stoerfenster, Yes-Dialog, Einheit
     * tot). "Pause" und "Quick Attack!" sind hier nie ein Klickziel (Autor, 06.10.).
     * Die Messgroesse fuer Lauf 2 steht als DEBUG-Zeile im Protokoll: "Battle begins nach N ms".
     */
    private Outcome fotozyklus(BotContext ctx) {
        int[] kBeginnt = kastenFuer(ctx, T_BEGINNT, K_BEGINNT);
        if (kBeginnt == null || !ctx.matcher().has(T_BEGINNT)) return warten(ctx);
        int[] kClaim = ctx.matcher().has(T_CLAIM) ? kastenFuer(ctx, T_CLAIM, K_CLAIM) : null;
        long los = System.currentTimeMillis(), ohneKnopf = los;
        int blicke = 0;
        while (System.currentTimeMillis() - los < GemBayGrenzen.FOTOZYKLUS_MS) {
            if (BotEngine.stoppAngefordert() || Thread.currentThread().isInterrupted()) return Outcome.WAITING;
            // Pause (F10, !pause) sonst erst nach bis zu FOTOZYKLUS_MS bemerkt (wie beim
            // Quest-Kisten-Haenger 04.10.: der Motor prueft "paused" nur zwischen zwei Schritten).
            if (BotEngine.pausiert()) {
                ctx.sleep(300);
                continue;
            }
            blicke++;
            Optional<Matcher.Hit> b = ctx.kastenAufnahme(kBeginnt, Matcher.DEFAULT_THRESHOLD, T_BEGINNT);
            if (b.isPresent()) {
                long t = System.currentTimeMillis();
                Log.debug(WO, "Fotozyklus: \"Battle begins\" nach " + (t - ohneKnopf) + " ms seit dem letzten Blick "
                        + "ohne Knopf (Blick " + blicke + ", " + (t - los) + " ms im Zyklus).");
                battleBeginsKlicken(ctx, b.get(), kBeginnt);
                grenzen.kampfZeichen(t);
                blind = 0;
                if (lauf != null) lauf.progress(Sprache.t("Gem Bay: waiting time skipped", "Gem Bay: Wartezeit uebersprungen"));
                return Outcome.ACTED;
            }
            ohneKnopf = System.currentTimeMillis();
            if (kClaim != null && blicke % 5 == 0
                    && ctx.kastenAufnahme(kClaim, Matcher.DEFAULT_THRESHOLD, T_CLAIM).isPresent()) {
                sicherheitsBlickUm = 0;   // der naechste Schritt ist ein ganzer: "Journey over!" + Claim
                return Outcome.ACTED;
            }
            ctx.sleep(GemBayGrenzen.FOTOZYKLUS_TAKT_MS);
        }
        return Outcome.ACTED;   // der Zyklus war die Pause; der Motor macht jetzt das ganze Bild
    }

    /**
     * AUTOCLICKER auf "Battle begins" (Autor, 06.10.): erst der normale Klick, dann bis zu
     * AUTOKLICK_MAX Klicks insgesamt im Abstand AUTOKLICK_ABSTAND_MS -- aber nur, solange die
     * Kastenaufnahme den Knopf noch zeigt (Vorlage passt). Ist er weg, ist Schluss. So trifft nie
     * ein Klick ins Leere oder in den Kampf dahinter.
     * Abstand 300 ms statt 80 (Pruefung 06.10.): ein angekommener Klick blendet den Knopf ueber
     * ~150 ms aus, und ein halb durchsichtiger Knopf passt der Vorlage noch -- nach 80 ms kaeme
     * der Nachklick ins beginnende Kampfbild. Nach 300 ms ist ein angekommener Knopf sicher weg,
     * ein nicht angekommener steht noch voll da. Kostet nichts, im Kampf gibt es nichts zu tun.
     * Messung fuer Lauf 2: kommen die DEBUG-Zeilen "ist noch da -- Klick 2 von 3" regelmaessig,
     * sind die Nachklicks ueberfluessig (in Lauf 1 sassen alle 7 Erstklicks).
     */
    private static void battleBeginsKlicken(BotContext ctx, Matcher.Hit h, int[] kasten) {
        ctx.klick(h, WO + Sprache.t(": skip the waiting time (Battle begins)", ": Wartezeit ueberspringen (Battle begins)"));
        for (int i = 2; i <= GemBayGrenzen.AUTOKLICK_MAX; i++) {
            ctx.sleep(GemBayGrenzen.AUTOKLICK_ABSTAND_MS);
            if (kasten == null) return;
            Optional<Matcher.Hit> nochDa = ctx.kastenAufnahme(kasten, Matcher.DEFAULT_THRESHOLD, T_BEGINNT);
            if (nochDa.isEmpty()) return;
            Log.debug(WO, "\"Battle begins\" ist noch da -- Klick " + i + Sprache.t(" of ", " von ") + GemBayGrenzen.AUTOKLICK_MAX
                    + " (Uebereinstimmung " + String.format("%.3f", nochDa.get().score()) + ").");
            ctx.input().click(nochDa.get());
        }
    }

    /**
     * "You have received a reward" in der Burg nach "Claim reward": 30 % der Fensterhoehe unter
     * dem Text tippen (schliesst das Fenster). Vorher nur im Zweig "beendet"; jetzt auch vor dem
     * Ablesen der Nahrung (Lauf 1, 06.10.: dort stand wohl noch ein Fenster davor).
     */
    private boolean belohnungWegtippen(Mat screen, BotContext ctx) {
        if (!ctx.visibleIfKnown(screen, T_BELOHNUNG)) return false;
        Matcher.Hit b = ctx.find(screen, T_BELOHNUNG).orElseThrow();
        int y = Math.min(ctx.height() - 20, b.centerY() + (int) (ctx.height() * 0.30));
        Log.action("Klick", WO + Sprache.t(": tap away the reward window  [", ": Belohnungsfenster wegtippen  [") + b.centerX() + "," + y + "]");
        ctx.input().click(b.centerX(), y, 6);
        ctx.sleepUntilChanged(1200);
        return true;
    }

    private static boolean inBurg(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT);
    }

    private static Outcome klicken(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (ctx.clickIfVisibleIfKnown(screen, vorlage, WO + ": " + grund)) {
            // Lauf 1 (06.10.): alle 13 Messungen 119-135 ms, die Obergrenze 1200 wurde nie gebraucht.
            ctx.sleepUntilChanged(600);
            return Outcome.ACTED;
        }
        return warten(ctx);
    }

    /** Kurz warten (Autor, 06.10.: "verkuerz Wartezeiten bis es nicht mehr geht"; vorher 300 ms). */
    private static Outcome warten(BotContext ctx) {
        ctx.sleep(150);
        return Outcome.WAITING;
    }

    /** Abstaende aus Autors Bildern (Fenster 770 Pixel hoch) auf die echte Groesse umrechnen. */
    private static int px(BotContext ctx, int wert) {
        return (int) Math.round(wert * ctx.height() / 770.0);
    }

    private static String zahl(long n) {
        return de.hustlebot.core.Bezahlung.zahl(n);
    }
}
