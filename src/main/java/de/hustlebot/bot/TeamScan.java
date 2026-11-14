package de.hustlebot.bot;

import de.hustlebot.core.Gegnerbuch;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.NamensVergleich;
import de.hustlebot.core.Zeitzone;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Das eigene Team ansehen (Autor 11.10., Auftrag 6, Bilder doku/team-scan/01-14):
 *
 *   1. Burg: Spielername oben links lesen. Kaserne (Raum mit Globus) antippen -> "Info" ->
 *      Fenster "Barracks (Level N)": Squad power lesen, Allianz-Talente an den Einheiten zaehlen,
 *      Foto nach logs/team. Einmal nach links wischen (die 6. Einheit), noch einmal zaehlen. X.
 *   2. Eine Figur in der Kaserne antippen -> Einheitenfenster (wie in der Arena: Kraft, Emblem,
 *      Brustplatte -> Set, Artefakt, Steine) -- dieselbe Lese-Logik wie ArenaPruefer, mit den
 *      Pfeilen "Switch dwellers" alle durch; je Einheit den Namen lesen. Fenster zu.
 *   3. Burg durchscrollen, bis der Thronsaal (goldener Thron) da ist; dort laeuft der Held herum --
 *      an den STELLEN im Raum antippen, bis das Heldenfenster ("Active" / "Battle") offen ist:
 *      Name, Kraft, Hauptfaehigkeit, 4 Nebenfaehigkeiten (gut = Vorlagen held_haupt_gut_*,
 *      held_neben_gut_* -- fehlen die, bleibt es offen). Foto. X.
 *   4. Burg durchscrollen bis zum Rift (blaues Portal) -> antippen -> "Titan Patrons" -> "Patronage":
 *      die 3 aktiven Schirmherren mit Level lesen ("Combat abilities"). Foto. X.
 *   5. Opfereinheit = kleinste Kraft (MeinTeam). 6. Punkte wie bei Gegnern (MeinTeam.speichernScan).
 *
 * EIN SCHRITT JE BILD, nie eine innere Schleife -- Stopp und Pause des Motors greifen zwischen
 * je zwei Klicks. Jeder Block hat eine Zeitgrenze. Erkennt der Bot einen Schritt nicht, wird der
 * Block abgebrochen: offene Fenster mit X zu, zurueck in die Burg, naechster Block. Nie haengen
 * bleiben. Was bis dahin gelesen wurde, wird trotzdem gespeichert.
 *
 * Die Vorlagen (templates/gelernt/team, aus Autors Bildern geschnitten) muessen am Spiel
 * nachgemessen werden -- siehe doku/TEAM-SCAN.md.
 */
public final class TeamScan {

    static final String WER = "Team";

    // --- Vorlagen (templates/gelernt/team) ---
    static final String T_GLOBUS = "ts_kaserne_globus";       // Kaserne: der Globus an der Rueckwand
    static final String T_INFO = "ts_info_knopf";             // "Info"-Knopf (Sprechblase mit i) unten
    static final String T_KASERNE_TITEL = "ts_kaserne_titel"; // "Barracks" im Info-Fenster
    static final String T_SQUAD_POWER = "ts_squad_power";     // "Squad power" -- die Zahl steht darunter
    static final String T_THRON = "ts_thron";                 // der goldene Thron
    static final String T_RIFT = "ts_rift_portal";            // das blaue Portal
    static final String T_PATRONS = "ts_titan_patrons";       // Knopf "Titan Patrons"
    static final String T_HELD_ACTIVE = "ts_held_active";     // "Active" unter der Hauptfaehigkeit
    static final String T_HELD_BATTLE = "ts_held_battle";     // Reiter "Battle"
    static final String T_ABILITIES = "ts_combat_abilities";  // "Combat abilities:" im Patronage-Fenster
    static final String T_PATRONAGE = "ts_patronage_titel";   // "Patronage"
    static final String T_X = "ts_close_x";                   // rotes X
    static final String T_BURG_KNOPF = "ts_burg_knopf";       // Burg-Knopf unten links (zurueck in die Burg)
    /** Gute Faehigkeiten des Helden -- Serien, von Autor aufzunehmen (doku/arena-held gibt es noch nicht). */
    static final String S_HELD_HAUPT_GUT = "held_haupt_gut";
    static final String S_HELD_NEBEN_GUT = "held_neben_gut";
    private static final List<String> X_VORLAGEN = List.of(T_X, "close_x", "ar_close_x", "cw_close_x", "dc_close_x", "dg_close_x");
    private static final String T_MAP = "map_button", T_MAP_ALT = "map_button_alt";

    /** Ohne diese geht gar nichts (Kaserne finden, Fenster zu). Alles andere ist je Block freiwillig. */
    static List<String> vorlagen() {
        return List.of(T_GLOBUS, T_INFO, T_X);
    }

    /** Alle Vorlagen des Scans -- fuer die Liste "was fehlt". */
    static List<String> alleVorlagen() {
        return List.of(T_GLOBUS, T_INFO, T_KASERNE_TITEL, T_SQUAD_POWER, T_THRON, T_RIFT, T_PATRONS, T_HELD_ACTIVE,
                T_HELD_BATTLE, T_ABILITIES, T_PATRONAGE, T_X, T_BURG_KNOPF,
                ArenaPruefer.T_KASERNE_FENSTER, ArenaPruefer.T_KASERNE_WEITER);
    }

    // --- Stellen, gemessen an Autors Bildern (1159x770; Bilder 1157x769 ohne Titelleiste) ---
    /** Spielername oben links neben dem Level-Abzeichen (Bild 01: "Autor" bei 95-150 / 17-35). */
    private static final double[] NAME_STELLE = {0.075, 0.012, 0.13, 0.05};
    /** Die Zahl unter "Squad power" (Bild 03: 190-330 / 145-172), relativ zum Treffer "Squad power". */
    private static final double SQUAD_ZAHL_BREITE = 0.16, SQUAD_ZAHL_HOEHE = 0.05;
    /** Reihe der Einheiten im Info-Fenster mit den Talent-Kreisen (Bild 03: 300-980 / 200-330). */
    private static final double[] INFO_REIHE = {300 / 1159.0, 200 / 770.0, 980 / 1159.0, 330 / 770.0};
    /** Die Allianz-Talente sind in der Kaserne 1,1-mal so gross wie in der Arena (gemessen 11.10.: 0,81-0,90 statt 0,54-0,63). */
    private static final double KASERNE_SKALA = 1.1;
    /** Rotes Stufen-Schild unter jeder Einheit der Kasernen-Reihe (nicht beim Helden, nicht bei gesperrten Plaetzen). */
    static final String T_EINHEIT_SCHILD = "ts_einheit_schild";
    /** Squad Challenge (Bilder 25.10., 1159x770): Abstand der Knoepfe unter der Kaserne (Info, Upgrade, Squad Challenge ...). */
    private static final double MENUE_ABSTAND = 153 / 1159.0;
    /** Ueberschrift "Squad Challenge" oben mittig. */
    private static final double[] SQUAD_TITEL = {380 / 1159.0, 55 / 770.0, 400 / 1159.0, 45 / 770.0};
    /** Die eigenen Einheiten oben (Mitte), Abstand 95: ungerade Zahl -> Mitte, gerade -> eine halbe Stelle daneben. */
    private static final double[] SQUAD_EINHEIT_MITTE = {582 / 1159.0, 189 / 770.0};
    private static final double[] SQUAD_VERSATZ = {0, -47 / 1159.0, 47 / 1159.0};
    /** Ins Leere (Einheitenfenster zu) und das rote X oben rechts. */
    private static final double[] SQUAD_LEER = {330 / 1159.0, 40 / 770.0};
    private static final double[] SQUAD_X = {1119 / 1159.0, 37 / 770.0};
    /** Thronsaal links im Profil ("Level 5" neben dem Thron, Bild 25.10.: 120-200 / 352-382). */
    private static final double[] P_THRON_STUFE = {40 / 1159.0, 335 / 770.0, 260 / 1159.0, 60 / 770.0};
    /** Ohne Held (unter Thronsaal 9) steht oben im Reiter "Battles" gleich "Titans" / "Patronage" / "Pals" (Bild 25.10.). */
    private static final double[] P_BATTLES_OBEN = {560 / 1159.0, 85 / 770.0, 330 / 1159.0, 95 / 770.0};
    /** Held (Thronsaal) und Schirmherren (Rift) ansehen? Vorerst aus (Autor 11.10.). */
    static final boolean HELD_UND_SCHIRME = false;
    /**
     * Kaserne -> Verlies: Gesichter sind in der Kaserne (und oben in "Gather troops") etwa 1,1-mal, Namen 1,2-mal so gross wie Autors Verlies-Bilder
     * (gemessen 11.10. an unit_magier2/unit_tank und name_bogi1/name_tank). Die Bot-Bilder werden darum verkleinert.
     */
    static final double GESICHT_ZUM_VERLIES = 1 / 1.1, NAME_ZUM_VERLIES = 1 / 1.2;
    /** Wischen im Info-Fenster: von rechts nach links in Hoehe der Einheiten. */
    // Kuerzer und langsam (Autor 11.10.: "nicht schnell, schnell -- akkurat, fehlerfrei"): etwa 3 Einheiten je Wisch.
    private static final double WISCH_Y = 300 / 770.0, WISCH_VON = 0.75, WISCH_BIS = 0.45;
    /** Name der Einheit oben im Einheitenfenster (Bild 07: "Magier 1" bei 330-580 / 125-160). */
    private static final double[] EINHEIT_NAME = {0.28, 0.155, 0.23, 0.055};
    /** Figuren in der Kaserne: Abstand zum Globus (Bild 06: Figuren bei 375,445,520,640,730,810 / ~405; Globus 568/430). */
    private static final int[][] FIGUR_VERSATZ = {{-190, 0}, {-120, 0}, {-50, 0}, {70, 0}, {160, 0}, {240, 0}};
    /** Der Held im Thronsaal: Stellen relativ zum Thron (Bild 10: Held 130 Punkte rechts vom Thron). */
    private static final int[][] HELD_VERSATZ = {{135, 20}, {-150, 20}, {60, 30}, {-60, 30}, {210, 20}, {-230, 20}};
    /** Heldenfenster (Bild 11): Name oben links (200-370 / 20-50), Kraft (30-170 / 130-160). */
    private static final double[] HELD_NAME = {0.17, 0.02, 0.17, 0.055};
    private static final double[] HELD_KRAFT = {0.02, 0.16, 0.16, 0.05};
    /** Hauptfaehigkeit ueber "Active" (Bild 11: 240-340 / 580-690), Nebenfaehigkeiten an vier festen Plaetzen. */
    static final double[] HELD_HAUPT = {0.20, 0.74, 0.10, 0.17};
    static final double[][] HELD_NEBEN = {{0.01, 0.57, 0.09, 0.14}, {0.07, 0.72, 0.09, 0.14},
                                                  {0.40, 0.57, 0.09, 0.14}, {0.34, 0.72, 0.09, 0.14}};

    // --- Zeitgrenzen ---
    private static final long BLOCK_MAX_MS = 75_000L;
    private static final long FENSTER_MAX_MS = 6_000L;
    private static final long GESAMT_MAX_MS = 6 * 60_000L;
    /** Erst seitwaerts suchen: links, rechts, rechts, links (= wieder Mitte). */
    private static final char[] SEITWAERTS = {'L', 'R', 'R', 'L'};
    private static final int SCROLL_MAX = 10 + 4;
    private static final int TIPP_MAX = 6;

    // --- Profil (Autor 15.10.): Burg oben links antippen -> Reiter "Battles": Held mit Haupt- und Nebenfaehigkeiten,
    // Titanen-Schirmherrschaften mit Stigma-Sets, darunter die Pals. Gemessen am offenen Spiel (1159x770), als Anteile.
    private static final double[] P_BURG = {52 / 1159.0, 48 / 770.0};           // Burg-Symbol mit der Stufe ("61")
    private static final double[] P_BATTLES = {955 / 1159.0, 52 / 770.0};       // Reiter "Battles"
    private static final double[] P_X = {1120 / 1159.0, 33 / 770.0};            // rotes X oben rechts
    private static final double[] P_REITER = {340 / 1159.0, 33 / 770.0, 750 / 1159.0, 38 / 770.0};   // "Rewards Castle Battles"
    private static final double[] P_HERO = {600 / 1159.0, 92 / 770.0, 230 / 1159.0, 34 / 770.0};     // Ueberschrift "Hero"
    private static final double[] P_HELD_NAME = {340 / 1159.0, 270 / 770.0, 125 / 1159.0, 34 / 770.0};
    private static final double[] P_HELD_KRAFT = {600 / 1159.0, 132 / 770.0, 180 / 1159.0, 36 / 770.0};
    private static final double[] P_SPIELER = {90 / 1159.0, 106 / 770.0, 160 / 1159.0, 36 / 770.0};   // "Autor" links oben
    private static final double[] P_POWER = {110 / 1159.0, 420 / 770.0, 170 / 1159.0, 36 / 770.0};    // "44 237 316" links
    /** Die fuenf Faehigkeiten rechts neben dem Helden: die erste ist die Hauptfaehigkeit, dann vier Nebenfaehigkeiten. */
    private static final int[] P_FAEHIGKEIT_X = {518, 617, 706, 795, 882};
    private static final int P_FAEHIGKEIT_Y = 215, P_FAEHIGKEIT = 84;
    private static final double[] P_TITANEN = {500 / 1159.0, 380 / 770.0, 430 / 1159.0, 195 / 770.0};  // die 3 Titan-Karten
    private static final double[] P_ZIEHEN = {715 / 1159.0, 620 / 770.0, 250 / 770.0};                  // x, von y, nach y
    private static final double[] P_PALS = {340 / 1159.0, 100 / 770.0, 760 / 1159.0, 340 / 770.0};     // nach dem Ziehen (Test 11:45: "Pals" bei y 150)
    /** Nur die Ueberschriften-Zeile "Pals" / "Pal Squad:" (schmal und mittig) -- die Texterkennung liest sie dort oft besser als im grossen Ausschnitt. */
    private static final double[] P_PALS_SCHMAL = {560 / 1159.0, 100 / 770.0, 330 / 1159.0, 90 / 770.0};
    /** Gute Pals: dieselben Bilder wie in der Arena (ArenaPruefer). */
    private static final String S_PAL_GUT = "ar_pal_gut";
    /** "Meh"-Pals: +0,5 Punkte je (Autor 15.10.). */
    private static final String S_PAL_MEH = "ar_pal_meh";

    /** Die Bloecke des Scans, in dieser Reihenfolge. */
    enum Block { START, EINSTELLUNGEN, KASERNE_INFO, KASERNE_EINHEITEN, PROFIL, HELD, SCHIRMHERREN, FERTIG, ENDE }

    /** Die Schritte innerhalb der Bloecke. */
    enum Schritt {
        HEIM, NAME_LESEN,
        EINST_OEFFNEN, EINST_LESEN, EINST_WARTEN, EINST_ZU,
        KASERNE_SUCHEN, KASERNE_TIPPEN, INFO_TIPPEN, INFO_LESEN, INFO_WISCHEN, INFO_LESEN_2, INFO_ZU,
        SQUAD_OEFFNEN, SQUAD_EINHEIT, FIGUR_TIPPEN, EINHEITEN_LESEN, EINHEITEN_ZU,
        THRON_SUCHEN, HELD_TIPPEN, HELD_LESEN, HELD_ZU,
        RIFT_SUCHEN, RIFT_TIPPEN, PATRONS_TIPPEN, PATRONAGE_LESEN, PATRONAGE_ZU,
        PROFIL_OEFFNEN, PROFIL_BATTLES, PROFIL_LESEN, PROFIL_PALS, PROFIL_ZU,
        AUFRAEUMEN, AUS
    }

    /**
     * Der naechste Block nach einem (fertigen oder abgebrochenen) Block -- reine Ablaufregel, ohne
     * Bildschirm, fuer den Test: abgebrochen heisst "zurueck in die Burg, dann der naechste Block".
     */
    static Block naechsterBlock(Block b) {
        return switch (b) {
            // Autor, 11.10.: "Am besten erst Settings und dann die Einheiten anschauen und abfotografieren."
            case START -> Block.EINSTELLUNGEN;
            // Autor 25.10.: erst das Profil (Thronsaal) -- davon haengt ab, was es bei den Einheiten gibt (unter 11 keine
            // Sets, Steine, Bauarbeiter-Regel). Der Name oben links wurde auf dem Zweitkonto mal "Léft", mal "-ZweitkontołA"
            // gelesen; ueber den Namen allein kam die gemerkte Stufe nicht an.
            case EINSTELLUNGEN -> Block.PROFIL;
            case PROFIL -> Block.KASERNE_INFO;
            case KASERNE_INFO -> Block.KASERNE_EINHEITEN;
            // Autor, 11.10.: "Kannst du den Helden weglassen erstmal sowie die Schirmherrschaften" -- beides ging im
            // ersten Lauf nicht auf (Held: 6 Stellen probiert; Patronage-Fenster nicht erkannt).
            // Autor, 15.10.: Held, Schirmherrschaften mit Stigma-Sets und Pals ueber die Burg oben links -> "Battles"
            // ansehen -- deutlich einfacher als Thronsaal und Rift (die bleiben aus).
            case KASERNE_EINHEITEN -> HELD_UND_SCHIRME ? Block.HELD : Block.FERTIG;
            case HELD -> Block.SCHIRMHERREN;
            case SCHIRMHERREN -> Block.FERTIG;
            default -> Block.ENDE;
        };
    }

    /** Der erste Schritt eines Blocks. */
    static Schritt ersterSchritt(Block b) {
        return switch (b) {
            case START -> Schritt.HEIM;
            case EINSTELLUNGEN -> Schritt.EINST_OEFFNEN;
            case KASERNE_INFO -> Schritt.KASERNE_SUCHEN;
            case KASERNE_EINHEITEN -> Schritt.SQUAD_OEFFNEN;   // Autor 25.10.: ueber "Squad Challenge", Figuren nur als Notloesung
            case PROFIL -> Schritt.PROFIL_OEFFNEN;
            case HELD -> Schritt.THRON_SUCHEN;
            case SCHIRMHERREN -> Schritt.RIFT_SUCHEN;
            case FERTIG -> Schritt.AUFRAEUMEN;
            default -> Schritt.AUS;
        };
    }

    /**
     * Die Richtung des naechsten Wischers beim Durchsuchen der Burg: erst 3x nach oben scrollen
     * (Inhalt nach unten ziehen), dann 6x nach unten, dann wieder hoch -- so wird alles einmal
     * gesehen, ohne die Richtung bei jedem Fehlschlag zu wechseln. +1 = Inhalt nach oben ziehen
     * (weiter unten in der Burg sehen), -1 = Inhalt nach unten ziehen.
     */
    static int scrollRichtung(int versuch) {
        if (versuch < 3) return -1;
        if (versuch < 9) return 1;
        return -1;
    }

    // --- Zustand ---
    private boolean aktiv;
    private Block block = Block.ENDE;
    private Schritt schritt = Schritt.AUS;
    private long begonnen, blockSeit, schrittSeit, letzterSchritt, aktivMs;
    private int scrolls, tipps, lesefehler;
    private boolean zurueckInBurg, abgebrochen;
    private final List<String> fehlgeschlagen = new ArrayList<>();
    private final ArenaPruefer pruefer = new ArenaPruefer();

    // --- Ergebnis ---
    private String name = "";
    private long squadPower = -1;
    private int allianzMaske;
    /** Gesicht und Name jeder Einheit aus der Kasernen-Reihe, nach gelesenem Namen (Autor 11.10.: der Bot nimmt sie selbst auf). */
    private final java.util.Map<String, Ausschnitt> ausschnitte = new java.util.LinkedHashMap<>();
    private int gewischt, einstRunden;
    private boolean infoNochmal;
    private int[] reiheVorher;

    record Ausschnitt(String name, BufferedImage gesicht, BufferedImage schrift) {}
    private final List<MeinTeam.Einheit> einheiten = new ArrayList<>();
    private ArenaPruefer.Ergebnis einheitenErgebnis;
    private MeinTeam.Held held;
    private final List<Integer> schirmLevel = new ArrayList<>();
    private int guteSchirme, guteStigmas, schirmAnzahl = -1;
    /** Gute Pals aus dem Profil (-1 = nicht angesehen). */
    private int gutePals = -1;
    /** Die Power links im Profil ("44 237 316"), -1 = nicht gelesen. */
    private long profilPower = -1;
    /** Meh-Pals aus dem Profil. */
    private int mehPals;
    /** Was der SchirmLeser im Patronage-Fenster sah (null = nicht angesehen). */
    private SchirmLeser.Ergebnis schirmErgebnis;
    private Matcher.Hit globus, thron;
    private int zuVersuche;
    /** Autor 25.10.: der Info-Knopf unter der Kaserne (daneben "Squad Challenge"); Einheiten ueber die Squad Challenge angesehen? */
    private Matcher.Hit infoKnopf;
    private boolean ueberSquad, squadMenueGetippt;
    /** Thronsaal-Stufe links im Profil (-1 = nicht gelesen). */
    private int thronsaal = -1;

    public boolean laeuft() { return aktiv; }

    public boolean zurueckInBurg() { return zurueckInBurg; }

    public boolean abgebrochen() { return abgebrochen; }

    public List<String> fehlgeschlagen() { return List.copyOf(fehlgeschlagen); }

    public void starten() {
        aktiv = true;
        begonnen = System.currentTimeMillis();
        letzterSchritt = begonnen;
        aktivMs = 0;
        zurueckInBurg = false;
        abgebrochen = false;
        fehlgeschlagen.clear();
        name = "";
        squadPower = -1;
        allianzMaske = 0;
        ausschnitte.clear();
        gewischt = 0;
        reiheVorher = null;
        einstRunden = 0;
        blasen = 0;
        infoNochmal = false;
        einheiten.clear();
        einheitenErgebnis = null;
        held = null;
        schirmLevel.clear();
        guteSchirme = 0;
        guteStigmas = 0;
        schirmAnzahl = -1;
        gutePals = -1;
        profilPower = -1;
        mehPals = 0;
        schirmErgebnis = null;
        globus = null;
        thron = null;
        infoKnopf = null;
        ueberSquad = false;
        squadMenueGetippt = false;
        thronsaal = -1;
        blockBeginnen(Block.START);
        Log.info(WER, "Sehe mir dein Team an: Spiel-Einstellungen, Kaserne (Info, Einheiten)" + (HELD_UND_SCHIRME
                ? ", Thronsaal (Held), Rift (Schirmherren)." : ", Profil (Held, Schirmherrschaften, Pals)."));
    }

    private void blockBeginnen(Block b) {
        block = b;
        blockSeit = System.currentTimeMillis();
        scrolls = 0;
        tipps = 0;
        lesefehler = 0;
        zuVersuche = 0;
        schrittSetzen(ersterSchritt(b));
    }

    private void schrittSetzen(Schritt s) {
        schritt = s;
        schrittSeit = System.currentTimeMillis();
        zuVersuche = 0;
    }

    /** Ein Block ist nicht erkennbar: aufraeumen (X, Burg) und zum naechsten. */
    private void blockAbbrechen(BotContext ctx, String grund) {
        Log.warn(WER, "Schritt " + schritt + " (" + block + ") nicht erkannt: " + grund + " -- dieser Teil wird uebersprungen.");
        fehlgeschlagen.add(block.name().toLowerCase(Locale.ROOT) + ": " + grund);
        if (block == Block.KASERNE_EINHEITEN) pruefer.abbrechen("Team-Scan: " + grund);
        Block naechster = naechsterBlock(block);
        blockBeginnen(Block.FERTIG);
        // Nach dem Aufraeumen geht es mit dem Block weiter, der jetzt dran gewesen waere.
        nachAufraeumen = naechster;
    }

    private Block nachAufraeumen = Block.ENDE;

    /**
     * Ein Schritt. Liefert true, solange der Scan laeuft (etwas getan oder gewartet); false = fertig.
     */
    public boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;
        long jetzt = System.currentTimeMillis();
        long luecke = jetzt - letzterSchritt;
        if (luecke < 5000) aktivMs += luecke;   // Pausen des Motors zaehlen nicht
        letzterSchritt = jetzt;
        if (aktivMs > GESAMT_MAX_MS && block != Block.FERTIG && block != Block.ENDE) {
            Log.warn(WER, "Der Team-Scan dauert schon " + (aktivMs / 1000) + " s -- Schluss, zurueck in die Burg.");
            fehlgeschlagen.add("zeit: laenger als " + (GESAMT_MAX_MS / 60_000) + " Minuten");
            abgebrochen = true;
            nachAufraeumen = Block.ENDE;
            blockBeginnen(Block.FERTIG);
        }
        if (block != Block.FERTIG && block != Block.ENDE && jetzt - blockSeit > BLOCK_MAX_MS) {
            blockAbbrechen(ctx, "Zeitgrenze des Blocks (" + (BLOCK_MAX_MS / 1000) + " s)");
            return true;
        }
        try {
            return schrittInnen(screen, ctx);
        } catch (Exception e) {
            Log.error(WER, "Fehler im Team-Scan bei " + schritt, e);
            blockAbbrechen(ctx, "Fehler: " + e.getMessage());
            return true;
        }
    }

    private boolean schrittInnen(Mat screen, BotContext ctx) {
        long seit = System.currentTimeMillis() - schrittSeit;
        switch (schritt) {
            // ---------------- START ----------------
            case HEIM -> {
                if (Weltkarte.erkannt(screen, ctx)) {
                    Weltkarte.heim(screen, ctx, WER);
                    return true;
                }
                // 29.10. (Bericht RBVS): nach !stop mitten im Hammer-Event lag noch die
                // Lupen-Form ("Select an area"); das Schliess-X des Events ist dann grau.
                // Erst die Form mit ihrem roten X abbrechen, danach greift close_x.
                if (ctx.clickIfVisibleIfKnown(screen, "ev_form_x", WER + ": Lupen-Auswahl im Event abbrechen (X)")) {
                    ctx.sleepUntilChanged(1200);
                    return true;
                }
                if (fensterZu(screen, ctx)) return true;
                if (!inBurg(screen, ctx)) {
                    if (seit < 8000) { ctx.sleep(400); return true; }
                    // Nicht in der Burg und keine Karte: einmal den Burg-Knopf probieren.
                    if (ctx.clickIfVisibleIfKnown(screen, T_BURG_KNOPF, WER + ": zurueck in die Burg")) {
                        ctx.sleepUntilChanged(1500);
                        return true;
                    }
                    blockAbbrechen(ctx, "die Burg ist nicht zu sehen");
                    return true;
                }
                schrittSetzen(Schritt.NAME_LESEN);
                return true;
            }
            case NAME_LESEN -> {
                nameLesen(screen, ctx);
                blockBeginnen(naechsterBlock(Block.START));
                return true;
            }
            // ---------------- SPIEL-EINSTELLUNGEN (Zahnrad unten rechts) ----------------
            case EINST_OEFFNEN -> {
                if (einstellungenOffen(ctx)) { schrittSetzen(Schritt.EINST_LESEN); return true; }
                if (seit > 9000) { blockAbbrechen(ctx, "das Zahnrad (Einstellungen) ging nicht auf"); return true; }
                Optional<Matcher.Hit> z = suchen(screen, ctx, T_ZAHNRAD);
                int x = z.map(h -> h.x() + h.width() / 2).orElse((int) (ctx.width() * ZAHNRAD[0]));
                int y = z.map(h -> h.y() + h.height() / 2).orElse((int) (ctx.height() * ZAHNRAD[1]));
                tippen(ctx, x, y, "Zahnrad (Spiel-Einstellungen)");
                ctx.sleep(600);
                return true;
            }
            case EINST_LESEN -> {
                schrittSetzen(einstellungenPruefen(ctx) ? Schritt.EINST_WARTEN : Schritt.EINST_ZU);
                return true;
            }
            case EINST_WARTEN -> {
                // Autor, 11.10.: "Wenn der Bot V Sync oder HD Graphics ausmacht, laedt das Spiel einmal neu -- da soll er
                // mit rechnen und nach dem Neuladen weitermachen. Bei der Sprache genauso (kurz der Ladebildschirm)."
                if (seit < 4000) { ctx.sleep(500); return true; }
                blockSeit = System.currentTimeMillis();   // das Neuladen zaehlt nicht gegen die 75 s des Blocks
                // Autor, 11.10.: "Irgendwie soll der Bot erkennen, dass er wieder in der Burg ist" -- auch am Zahnrad
                // unten rechts (in jeder Sprache gleich). ZUERST pruefen: einstellungenOffen holt ein neues Bild, danach
                // war "screen" veraltet und die Burg wurde nie erkannt (11.10., 22:10).
                if (inBurg(screen, ctx) || suchen(screen, ctx, T_ZAHNRAD).isPresent()) {
                    blockSeit = System.currentTimeMillis();   // jedes Neuladen bekommt seine eigene Zeit
                    if (++einstRunden > 4) { blockBeginnen(naechsterBlock(Block.EINSTELLUNGEN)); return true; }
                    Log.info(WER, "Spiel ist nach dem Umstellen wieder da -- Einstellungen noch einmal ansehen.");
                    schrittSetzen(Schritt.EINST_OEFFNEN);
                    return true;
                }
                if (einstellungenOffen(ctx)) { schrittSetzen(Schritt.EINST_LESEN); return true; }   // ohne Neuladen
                if (seit > 120_000) { blockAbbrechen(ctx, "das Spiel kam nach dem Umstellen nicht zurueck in die Burg"); return true; }
                ctx.sleep(800);
                return true;
            }
            case EINST_ZU -> {
                if (!einstellungenOffen(ctx)) { blockBeginnen(naechsterBlock(Block.EINSTELLUNGEN)); return true; }
                if (zuVersuche++ >= 3) { blockAbbrechen(ctx, "das Einstellungs-Fenster ging nicht zu"); return true; }
                if (!fensterZu(screen, ctx)) tippen(ctx, px(ctx, EINST_X), py(ctx, EINST_X), "Einstellungen schliessen (X)");
                return true;
            }
            // ---------------- KASERNE: INFO ----------------
            case KASERNE_SUCHEN -> {
                // Autor, 11.10.: "Sprechblasen unzufriedener Einheiten einmal antippen -- dann ist die Burg gluecklicher und
                // produziert mehr." Eine Blase hatte ausserdem den Globus verdeckt.
                Optional<Matcher.Hit> blase = blaseSuchen(screen, ctx);
                if (blase.isPresent() && blasen++ < 8) {
                    tippen(ctx, blase.get().centerX(), blase.get().centerY(), "Sprechblase antippen (Einheit zufrieden machen)");
                    return true;
                }
                Optional<Matcher.Hit> g = suchen(screen, ctx, T_GLOBUS);
                if (g.isEmpty()) g = kaserneAnDerWand(screen, ctx);
                if (g.isPresent()) {
                    globus = wieGlobus1(g.get());
                    schrittSetzen(Schritt.KASERNE_TIPPEN);
                    return true;
                }
                if (!scrollen(ctx, "Kaserne")) blockAbbrechen(ctx, "die Kaserne (Globus) ist nirgends zu sehen");
                return true;
            }
            case KASERNE_TIPPEN -> {
                // Die Kaserne antippen: etwas unter dem Globus, dort steht der Raum.
                tippen(ctx, globus.centerX(), globus.centerY() + (int) (ctx.height() * 0.06), "Kaserne antippen");
                schrittSetzen(Schritt.INFO_TIPPEN);
                return true;
            }
            case INFO_TIPPEN -> {
                Optional<Matcher.Hit> info = ctx.matcher().has(T_INFO) ? ctx.find(screen, T_INFO) : Optional.empty();
                if (info.isPresent()) {
                    infoKnopf = info.get();
                    ctx.klick(infoKnopf, WER + ": Info der Kaserne");
                    ctx.sleepUntilChanged(1500);
                    schrittSetzen(Schritt.INFO_LESEN);
                    return true;
                }
                // Autor, 11.10.: "Der Bot muss nur EINMAL auf die Kaserne druecken -- drueckt er nochmal, wird ganz nah an den
                // Globus gezoomt." Darum kein zweites Antippen mehr, nur warten, bis Info erscheint.
                if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "kein Info-Knopf nach dem Antippen der Kaserne");
                else ctx.sleep(300);
                return true;
            }
            case INFO_LESEN -> {
                if (!infoFensterOffen(screen, ctx)) {
                    // 11.10., 22:41: das Info-Fenster ging nach dem ersten Tippen nicht auf -- nach 3 s noch einmal Info
                    // (nicht die Kaserne, die wuerde heranzoomen).
                    if (seit > 3000 && !infoNochmal) {
                        infoNochmal = true;
                        if (ctx.clickIfVisibleIfKnown(screen, T_INFO, WER + ": Info der Kaserne (noch einmal)")) {
                            ctx.sleepUntilChanged(1500);
                            schrittSeit = System.currentTimeMillis();
                            return true;
                        }
                    }
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Info-Fenster der Kaserne ging nicht auf");
                    else ctx.sleep(300);
                    return true;
                }
                ctx.sleep(300);
                Mat frisch = ctx.refresh();
                infoLesen(frisch != null ? frisch : screen, ctx, true);
                reiheVorher = reiheMerkmal(ctx.frame());
                schrittSetzen(Schritt.INFO_WISCHEN);
                return true;
            }
            case INFO_WISCHEN -> {
                int y = (int) (ctx.height() * WISCH_Y);
                Log.action("Ziehen", WER + ": Einheiten im Info-Fenster nach links wischen");
                gewischt++;
                ctx.input().dragLangsam((int) (ctx.width() * WISCH_VON), y, (int) (ctx.width() * WISCH_BIS), y);
                ctx.sleepUntilChanged(1000);
                ctx.sleep(900);   // bis die Reihe ganz still steht
                schrittSetzen(Schritt.INFO_LESEN_2);
                return true;
            }
            case INFO_LESEN_2 -> {
                ctx.sleep(300);
                Mat frisch = ctx.refresh();
                boolean offen = infoFensterOffen(frisch != null ? frisch : screen, ctx);
                if (offen) infoLesen(frisch != null ? frisch : screen, ctx, false);
                // Autor 11.10.: je nach Kaserne 3 bis 8 Einheiten -- weiterwischen, bis sich die Reihe nicht mehr bewegt.
                int[] jetzt = reiheMerkmal(ctx.frame());
                boolean bewegt = !gleicheReihe(jetzt, reiheVorher);
                reiheVorher = jetzt;
                schrittSetzen(offen && bewegt && gewischt < 3 ? Schritt.INFO_WISCHEN : Schritt.INFO_ZU);
                return true;
            }
            case INFO_ZU -> {
                if (infoFensterOffen(screen, ctx)) {
                    if (zuVersuche++ < 3 && fensterZu(screen, ctx)) return true;
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Info-Fenster ging nicht zu");
                    else ctx.sleep(300);
                    return true;
                }
                blockBeginnen(Block.KASERNE_EINHEITEN);
                return true;
            }
            // ---------------- KASERNE: EINHEITEN ----------------
            // ---------------- KASERNE: EINHEITEN UEBER "SQUAD CHALLENGE" (Autor 25.10.) ----------------
            // Die Figuren laufen in der Kaserne herum -- auf dem Zweitkonto (Thronsaal 5) gingen alle sechs Stellen daneben.
            // Kaserne antippen -> unten "Squad Challenge" -> oben eine der eigenen Einheiten: dort steht immer das ganze Team,
            // die Pfeile "Switch dwellers" gehen durch alle. Danach ins Leere tippen und das rote X.
            case SQUAD_OEFFNEN -> {
                BufferedImage b = ctx.frame();
                if (b != null && squadOffen(b)) {
                    tipps = 0;
                    schrittSetzen(Schritt.SQUAD_EINHEIT);
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= 3 || seit > 15_000) {
                    Log.info(WER, "Squad Challenge ging nicht auf -- die Einheiten ueber die Figuren in der Kaserne.");
                    fensterZu(screen, ctx);
                    tipps = 0;
                    schrittSetzen(Schritt.FIGUR_TIPPEN);
                    return true;
                }
                Optional<Matcher.Hit> info = ctx.matcher().has(T_INFO) ? ctx.find(screen, T_INFO) : Optional.empty();
                if (info.isEmpty()) {
                    // Ein falsches Fenster offen (Upgrade, Alliances)? Erst zu. Sonst ist das Menue weg: die Kaserne EINMAL
                    // antippen (Autor 11.10.: ein zweites Antippen zoomt an den Globus heran).
                    if (fensterZu(screen, ctx)) return true;
                    if (!squadMenueGetippt && globus != null) {
                        squadMenueGetippt = true;
                        tippen(ctx, globus.centerX(), globus.centerY() + (int) (ctx.height() * 0.06), "Kaserne antippen (fuer Squad Challenge)");
                        return true;
                    }
                    if (seit > 3000) tipps = 3;   // nichts zu sehen -- im naechsten Bild die Figuren
                    else ctx.sleep(300);
                    return true;
                }
                infoKnopf = info.get();
                // Info, Upgrade, Squad Challenge, Alliances, Pals (Bild 25.10.): zuerst die dritte Stelle, ohne "Upgrade" die zweite.
                int stelle = tipps == 0 ? 2 : tipps == 1 ? 1 : 3;
                tipps++;
                tippen(ctx, infoKnopf.centerX() + (int) Math.round(MENUE_ABSTAND * ctx.width() * stelle), infoKnopf.centerY(),
                        "Squad Challenge (unten, Knopf " + (stelle + 1) + ")");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case SQUAD_EINHEIT -> {
                if (einheitenFensterOffen(screen, ctx)) {
                    ueberSquad = true;
                    pruefer.startenKaserne(this::einheitLesen);
                    schrittSetzen(Schritt.EINHEITEN_LESEN);
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= SQUAD_VERSATZ.length) {
                    Log.warn(WER, "Squad Challenge: keine eigene Einheit liess sich oeffnen -- Fenster zu, weiter ohne Einheiten.");
                    fehlgeschlagen.add("SQUAD_EINHEIT");
                    ueberSquad = true;
                    schrittSetzen(Schritt.EINHEITEN_ZU);
                    return true;
                }
                // Die eigenen Einheiten stehen oben mittig: ungerade Zahl -> eine genau in der Mitte, gerade -> daneben.
                double v = SQUAD_VERSATZ[tipps++];
                tippen(ctx, rx(ctx, SQUAD_EINHEIT_MITTE[0] + v), ry(ctx, SQUAD_EINHEIT_MITTE[1]),
                        "eigene Einheit oben in der Squad Challenge antippen");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case FIGUR_TIPPEN -> {
                if (einheitenFensterOffen(screen, ctx)) {
                    pruefer.startenKaserne(this::einheitLesen);
                    schrittSetzen(Schritt.EINHEITEN_LESEN);
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= FIGUR_VERSATZ.length) {
                    blockAbbrechen(ctx, "keine Figur in der Kaserne liess sich oeffnen");
                    return true;
                }
                if (globus == null) {
                    Optional<Matcher.Hit> g = ctx.matcher().find(screen, T_GLOBUS);
                    if (g.isEmpty()) { blockAbbrechen(ctx, "die Kaserne ist nach dem Info-Fenster nicht mehr zu sehen"); return true; }
                    globus = g.get();
                }
                double f = ctx.width() / 1159.0;
                int[] v = FIGUR_VERSATZ[tipps++];
                tippen(ctx, globus.centerX() + (int) Math.round(v[0] * f), globus.centerY() + (int) Math.round((v[1] + 25) * f),
                        "Figur " + tipps + " in der Kaserne antippen");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case EINHEITEN_LESEN -> {
                GameMode.Outcome o = pruefer.schritt(screen, ctx, null);
                if (o != null && pruefer.laeuft()) return true;
                pruefer.abholen().ifPresent(e -> einheitenErgebnis = e);
                schrittSetzen(Schritt.EINHEITEN_ZU);
                return true;
            }
            case EINHEITEN_ZU -> {
                if (ueberSquad) {
                    BufferedImage b = ctx.frame();
                    if (zuVersuche >= 6) {
                        ueberSquad = false;
                        blockAbbrechen(ctx, "die Squad Challenge ging nicht zu");
                        return true;
                    }
                    if (einheitenFensterOffen(screen, ctx)) {
                        // Autor 25.10.: einfach ins Leere tippen (oben neben "Opponents refresh"), dann ist das Einheitenfenster zu.
                        zuVersuche++;
                        tippen(ctx, rx(ctx, SQUAD_LEER[0]), ry(ctx, SQUAD_LEER[1]), "Einheitenfenster zu (ins Leere tippen)");
                        return true;
                    }
                    if (b != null && squadOffen(b)) {
                        zuVersuche++;
                        if (!fensterZu(screen, ctx)) tippen(ctx, rx(ctx, SQUAD_X[0]), ry(ctx, SQUAD_X[1]), "Squad Challenge schliessen (rotes X)");
                        return true;
                    }
                    ueberSquad = false;
                    blockBeginnen(naechsterBlock(Block.KASERNE_EINHEITEN));
                    return true;
                }
                if (einheitenFensterOffen(screen, ctx)) {
                    if (zuVersuche++ < 3) {
                        if (ctx.clickIfVisibleIfKnown(screen, "ar_dweller_close", WER + ": Einheitenfenster schliessen")
                                || fensterZu(screen, ctx)
                                || ctx.clickIfVisibleIfKnown(screen, T_BURG_KNOPF, WER + ": Einheitenfenster verlassen (Burg)")) {
                            ctx.sleepUntilChanged(1200);
                            return true;
                        }
                    }
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Einheitenfenster ging nicht zu");
                    else ctx.sleep(300);
                    return true;
                }
                blockBeginnen(naechsterBlock(Block.KASERNE_EINHEITEN));
                return true;
            }
            // ---------------- THRONSAAL: HELD ----------------
            case THRON_SUCHEN -> {
                if (!ctx.matcher().has(T_THRON)) { blockAbbrechen(ctx, "keine Vorlage " + T_THRON); return true; }
                Optional<Matcher.Hit> t = suchen(screen, ctx, T_THRON);
                if (t.isPresent()) {
                    thron = t.get();
                    schrittSetzen(Schritt.HELD_TIPPEN);
                    return true;
                }
                if (!scrollen(ctx, "Thronsaal")) blockAbbrechen(ctx, "der Thronsaal ist nirgends zu sehen");
                return true;
            }
            case HELD_TIPPEN -> {
                if (heldFensterOffen(screen, ctx)) {
                    schrittSetzen(Schritt.HELD_LESEN);
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= HELD_VERSATZ.length) {
                    blockAbbrechen(ctx, "der Held im Thronsaal liess sich nicht oeffnen (" + tipps + " Stellen probiert)");
                    return true;
                }
                double f = ctx.width() / 1159.0;
                int[] v = HELD_VERSATZ[tipps++];
                tippen(ctx, thron.centerX() + (int) Math.round(v[0] * f), thron.centerY() + (int) Math.round(v[1] * f),
                        "Held im Thronsaal antippen (Stelle " + tipps + ")");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case HELD_LESEN -> {
                ctx.sleep(400);
                Mat frisch = ctx.refresh();
                heldLesen(frisch != null ? frisch : screen, ctx);
                schrittSetzen(Schritt.HELD_ZU);
                return true;
            }
            case HELD_ZU -> {
                if (heldFensterOffen(screen, ctx)) {
                    if (zuVersuche++ < 3 && fensterZu(screen, ctx)) return true;
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Heldenfenster ging nicht zu");
                    else ctx.sleep(300);
                    return true;
                }
                blockBeginnen(Block.SCHIRMHERREN);
                return true;
            }
            // ---------------- PROFIL: HELD, SCHIRMHERRSCHAFTEN, PALS (Autor 15.10.) ----------------
            case PROFIL_OEFFNEN -> {
                BufferedImage b = ctx.frame();
                if (b != null && profilOffen(b)) {
                    schrittSetzen(Schritt.PROFIL_BATTLES);
                    tipps = 0;
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= 3) { blockAbbrechen(ctx, "das Profil (Burg oben links) ging nicht auf"); return true; }
                tipps++;
                tippen(ctx, rx(ctx, P_BURG[0]), ry(ctx, P_BURG[1]), "Burg oben links antippen (Profil)");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case PROFIL_BATTLES -> {
                BufferedImage b = ctx.frame();
                if (b != null && battlesOffen(b)) {
                    ctx.sleep(400);
                    schrittSetzen(Schritt.PROFIL_LESEN);
                    return true;
                }
                if (tipps > 0 && seit < 1500) { ctx.sleep(200); return true; }
                if (tipps >= 3) { blockAbbrechen(ctx, "der Reiter \"Battles\" im Profil ging nicht auf"); return true; }
                tipps++;
                tippen(ctx, rx(ctx, P_BATTLES[0]), ry(ctx, P_BATTLES[1]), "Reiter \"Battles\" im Profil");
                schrittSeit = System.currentTimeMillis();
                return true;
            }
            case PROFIL_LESEN -> {
                Mat frisch = ctx.refresh();
                profilLesen(frisch != null ? frisch : screen, ctx);
                // Autor 25.10. (Thronsaal 5): ohne Held stehen die Pals schon oben -- nach dem Ziehen waren sie weg.
                BufferedImage oben = ctx.frame();
                if (held == null && oben != null && palsSichtbar(oben)) {
                    Mat jetzt = ctx.refresh();
                    palsLesen(jetzt != null ? jetzt : screen, ctx);
                    schrittSetzen(Schritt.PROFIL_ZU);
                    return true;
                }
                // Die Pals stehen weiter unten: im Fenster nach oben ziehen.
                int x = rx(ctx, P_ZIEHEN[0]);
                Log.action("Ziehen", WER + ": Profil nach unten scrollen (Pals)");
                ctx.input().drag(x, ry(ctx, P_ZIEHEN[1]), x, ry(ctx, P_ZIEHEN[2]));
                ctx.sleepUntilChanged(900);
                ctx.sleep(400);
                schrittSetzen(Schritt.PROFIL_PALS);
                return true;
            }
            case PROFIL_PALS -> {
                Mat frisch = ctx.refresh();
                palsLesen(frisch != null ? frisch : screen, ctx);
                schrittSetzen(Schritt.PROFIL_ZU);
                return true;
            }
            case PROFIL_ZU -> {
                BufferedImage b = ctx.frame();
                if (b != null && profilOffen(b)) {
                    if (zuVersuche++ < 3) {
                        if (!fensterZu(screen, ctx)) tippen(ctx, rx(ctx, P_X[0]), ry(ctx, P_X[1]), "Profil schliessen (rotes X oben rechts)");
                        return true;
                    }
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Profil ging nicht zu");
                    else ctx.sleep(300);
                    return true;
                }
                blockBeginnen(naechsterBlock(Block.PROFIL));
                return true;
            }
            // ---------------- RIFT: SCHIRMHERREN ----------------
            case RIFT_SUCHEN -> {
                if (!ctx.matcher().has(T_RIFT)) { blockAbbrechen(ctx, "keine Vorlage " + T_RIFT); return true; }
                Optional<Matcher.Hit> r = suchen(screen, ctx, T_RIFT);
                if (r.isPresent()) {
                    tippen(ctx, r.get().centerX(), r.get().centerY(), "Rift antippen");
                    schrittSetzen(Schritt.PATRONS_TIPPEN);
                    return true;
                }
                if (!scrollen(ctx, "Rift")) blockAbbrechen(ctx, "der Rift ist nirgends zu sehen");
                return true;
            }
            case RIFT_TIPPEN, PATRONS_TIPPEN -> {
                if (ctx.clickIfVisibleIfKnown(screen, T_PATRONS, WER + ": Titan Patrons")) {
                    ctx.sleepUntilChanged(1500);
                    schrittSetzen(Schritt.PATRONAGE_LESEN);
                    return true;
                }
                if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "kein Knopf \"Titan Patrons\" nach dem Antippen des Rifts");
                else ctx.sleep(300);
                return true;
            }
            case PATRONAGE_LESEN -> {
                if (!patronageOffen(screen, ctx)) {
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Fenster \"Patronage\" ging nicht auf");
                    else ctx.sleep(300);
                    return true;
                }
                ctx.sleep(300);
                Mat frisch = ctx.refresh();
                schirmeLesen(frisch != null ? frisch : screen, ctx);
                schrittSetzen(Schritt.PATRONAGE_ZU);
                return true;
            }
            case PATRONAGE_ZU -> {
                if (patronageOffen(screen, ctx)) {
                    if (zuVersuche++ < 3 && fensterZu(screen, ctx)) return true;
                    if (seit > FENSTER_MAX_MS) blockAbbrechen(ctx, "das Fenster \"Patronage\" ging nicht zu");
                    else ctx.sleep(300);
                    return true;
                }
                nachAufraeumen = Block.ENDE;
                blockBeginnen(Block.FERTIG);
                return true;
            }
            // ---------------- AUFRAEUMEN ----------------
            case AUFRAEUMEN -> {
                // Offene Fenster zu, dann in die Burg. Dann entweder der naechste Block oder Schluss.
                // Code-Review 15.10.: ohne Grenze -- fuehrte "heim" nicht in die Burg, ging es hier ewig im Kreis.
                if (zuVersuche < 6 && Weltkarte.erkannt(screen, ctx)) {
                    zuVersuche++;
                    Weltkarte.heim(screen, ctx, WER);
                    return true;
                }
                if (zuVersuche < 4 && fensterZu(screen, ctx)) { zuVersuche++; return true; }
                if (!inBurg(screen, ctx)) {
                    if (zuVersuche < 6 && ctx.clickIfVisibleIfKnown(screen, T_BURG_KNOPF, WER + ": zurueck in die Burg")) {
                        zuVersuche++;
                        ctx.sleepUntilChanged(1500);
                        return true;
                    }
                    if (seit < 10_000) { ctx.sleep(400); return true; }
                    Log.warn(WER, "Nach dem Team-Scan ist die Burg nicht zu sehen -- der naechste Modus muss selbst hinfinden.");
                    zurueckInBurg = false;
                    beenden();
                    return false;
                }
                zurueckInBurg = true;
                if (nachAufraeumen != Block.ENDE && nachAufraeumen != Block.FERTIG) {
                    Block b = nachAufraeumen;
                    nachAufraeumen = Block.ENDE;
                    blockBeginnen(b);
                    return true;
                }
                beenden();
                return false;
            }
            default -> {
                beenden();
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    //  Lesen
    // ------------------------------------------------------------------

    /** Der Spielername oben links in der Burg (Bild 01: "Autor") -- erst ueber das Level-Abzeichen, sonst feste Stelle. */
    private void nameLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        String gelesen = "";
        try {
            var spieler = Burgstand.spieler(screen, ctx);
            if (spieler.containsKey("Name")) gelesen = spieler.get("Name");
        } catch (Exception e) {
            Log.debug(WER, "Name ueber das Level-Abzeichen nicht lesbar: " + e.getMessage());
        }
        // Autor 25.10.: "Zweitkonto A" kam ueber das Abzeichen nur als "Zweitkonto" -- die feste Stelle auch lesen und die
        // laengere Lesung nehmen, wenn sie die kuerzere enthaelt (sonst waeren Left und Zweitkonto B gleich).
        List<String> zeilen = ArenaLeser.liesNamenZeilen(bild, (int) (NAME_STELLE[0] * bild.getWidth()), (int) (NAME_STELLE[1] * bild.getHeight()),
                (int) (NAME_STELLE[2] * bild.getWidth()), (int) (NAME_STELLE[3] * bild.getHeight()));
        String fest = zeilen.isEmpty() ? "" : zeilen.get(0).strip();
        if (gelesen.isBlank() || (fest.length() > gelesen.length()
                && NamensVergleich.form(fest).contains(NamensVergleich.form(gelesen)))) {
            gelesen = fest;
        }
        // Autor 25.10.: "Ich habe Zweitkonto A mit normalen englischen Buchstaben geschrieben" -- die Erkennung las "Léft".
        String ohneAkzent = java.text.Normalizer.normalize(gelesen, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (ohneAkzent.matches("[A-Za-z0-9 ._'-]+")) gelesen = ohneAkzent;
        if (NamensVergleich.keinName(gelesen) || NamensVergleich.form(gelesen).length() < 2) {
            Log.info(WER, "Spielername oben links nicht lesbar" + (gelesen.isBlank() ? "" : " (\"" + gelesen + "\")") + ".");
            return;
        }
        name = gelesen;
        Log.info(WER, "Spielername oben links: \"" + name + "\".");
        // Was dieses Konto schon hat (Thronsaal vom letzten Scan), gilt schon fuer die Einheiten (keine Sets/Steine unter 11).
        de.hustlebot.core.Thronsaal.aktuellSetzen(de.hustlebot.core.Thronsaal.stufe(name));
        MeinTeam.setSpielername(name);
        nameFotoSichern(bild);
    }

    /** Autor 14.10.: "Der Bot soll sich selber auch ein Bild vom Spielernamen machen." Eigener Ausschnitt -> templates\gelernt. */
    static final java.nio.file.Path NAME_FOTO = java.nio.file.Path.of("templates", "gelernt", "team", "spielername.png");

    private static void nameFotoSichern(BufferedImage bild) {
        try {
            int x = (int) (NAME_STELLE[0] * bild.getWidth()), y = (int) (NAME_STELLE[1] * bild.getHeight());
            int w = Math.min((int) (NAME_STELLE[2] * bild.getWidth()), bild.getWidth() - x);
            int h = Math.min((int) (NAME_STELLE[3] * bild.getHeight()), bild.getHeight() - y);
            if (w < 10 || h < 5) return;
            // Autor 15.10.: Der feste Ausschnitt (151 px) nahm rechts und unten ein Stueck Burg mit -- der Name selbst
            // ist nur ~60 px breit. Jetzt eng um die weisse Schrift mit dunklem Rand (wie die Arena-Namensbilder).
            java.awt.Rectangle eng = ArenaMode.textKasten(bild, x, y, x + w, y + h);
            if (eng != null && eng.width >= 8 && eng.height >= 6) {
                x = eng.x; y = eng.y; w = eng.width; h = eng.height;
            }
            java.nio.file.Files.createDirectories(NAME_FOTO.getParent());
            javax.imageio.ImageIO.write(bild.getSubimage(x, y, w, h), "png", NAME_FOTO.toFile());
            Log.info(WER, "Bild vom Spielernamen gesichert: " + NAME_FOTO);
        } catch (Exception e) {
            Log.debug(WER, "Bild vom Spielernamen nicht gesichert: " + e.getMessage());
        }
    }

    /** Das gesicherte Bild vom Spielernamen (fuer das Steuerprogramm), null wenn keins da ist. */
    public static java.nio.file.Path nameFoto() {
        return java.nio.file.Files.exists(NAME_FOTO) ? NAME_FOTO : null;
    }

    private boolean infoFensterOffen(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, T_KASERNE_TITEL) || ctx.visibleIfKnown(screen, T_SQUAD_POWER);
    }

    /** Info-Fenster: Squad power (nur beim ersten Blick), Allianz-Talente in der Reihe, Foto. */
    private void infoLesen(Mat screen, BotContext ctx, boolean erster) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        if (erster) {
            Optional<Matcher.Hit> sp = ctx.matcher().find(screen, T_SQUAD_POWER);
            if (sp.isPresent()) {
                Matcher.Hit h = sp.get();
                int x = h.x() - 4, y = h.y() + h.height(), w = (int) (ctx.width() * SQUAD_ZAHL_BREITE), hh = (int) (ctx.height() * SQUAD_ZAHL_HOEHE);
                List<Long> zahlen = ArenaLeser.zahlenIn(bild, x, y, w, hh).stream().filter(z -> z > 100_000 && z < 2_000_000_000L).toList();
                if (zahlen.size() == 1) {
                    squadPower = zahlen.get(0);
                    Log.info(WER, "Squad power: " + String.format(Locale.GERMANY, "%,d", squadPower) + ".");
                    Gegnerbuch.setMeineKraft(squadPower);
                } else {
                    long k = squadAusText(ArenaLeser.liesTextEnglisch(bild, x, y, w, hh));
                    if (k > 0) { squadPower = k; Log.info(WER, "Squad power: " + String.format(Locale.GERMANY, "%,d", k) + "."); Gegnerbuch.setMeineKraft(k); }
                    else Log.info(WER, "Squad power nicht sicher gelesen: " + zahlen);
                }
            } else {
                Log.info(WER, "\"Squad power\" nicht gefunden (Vorlage " + T_SQUAD_POWER + ") -- Kraft bleibt offen.");
            }
            foto(bild, "kaserne-info");
        }
        int maske = ArenaPruefer.allianzMaske(bild, (int) (INFO_REIHE[0] * bild.getWidth()), (int) (INFO_REIHE[1] * bild.getHeight()),
                (int) (INFO_REIHE[2] * bild.getWidth()), (int) (INFO_REIHE[3] * bild.getHeight()), KASERNE_SKALA);
        allianzMaske |= maske;
        if (maske != 0) Log.info(WER, "Allianz-Talente im Info-Fenster: " + ArenaPruefer.allianzText(maske) + ".");
        einheitenAusschneiden(screen, ctx, bild);
    }

    /** Grobes Abbild der Einheiten-Reihe (32 x 6 Grauwerte): bewegt sie sich beim Wischen noch? */
    private static int[] reiheMerkmal(BufferedImage bild) {
        if (bild == null) return null;
        int x0 = (int) (INFO_REIHE[0] * bild.getWidth()), y0 = (int) (INFO_REIHE[1] * bild.getHeight());
        int w = (int) ((INFO_REIHE[2] - INFO_REIHE[0]) * bild.getWidth()), h = (int) ((INFO_REIHE[3] - INFO_REIHE[1]) * bild.getHeight());
        int[] m = new int[32 * 6];
        for (int j = 0; j < 6; j++) {
            for (int i = 0; i < 32; i++) {
                int rgb = bild.getRGB(x0 + i * w / 32 + w / 64, y0 + j * h / 6 + h / 12);
                m[j * 32 + i] = ((rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255)) / 3;
            }
        }
        return m;
    }

    private static boolean gleicheReihe(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        long d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length < 6;
    }

    /**
     * Autor, 11.10.: "Kannst du einfach alle meine Namen und Skins, meine Einheiten selber aufnehmen? Die mit der meisten
     * Power als Einheit 1 ..." Je rotem Stufen-Schild in der Reihe: das Gesicht darueber und den Namen darunter
     * ausschneiden, den Namen lesen. Halb verdeckte Einheiten am Rand kommen nach dem Wischen dran.
     */
    private void einheitenAusschneiden(Mat screen, BotContext ctx, BufferedImage bild) {
        if (bild == null) return;
        int bw = bild.getWidth(), bh = bild.getHeight();
        int rand0 = (int) (175 / 1159.0 * bw), rand1 = (int) (985 / 1159.0 * bw);
        for (java.awt.Point h : schildeFinden(bild)) {
            int gx = h.x - 5, gy = h.y - 86, gw = 66, gh = 56;
            if (gx < rand0 || gx + gw > rand1 || gy < 0) continue;
            java.awt.Rectangle schrift = schriftRahmen(bild, Math.max(rand0, h.x + 29 - 62), h.y + 27, 124, 24);
            if (schrift == null) continue;
            // Mit Rand lesen (11.10.: 124 x 34 liest "Bogen 1", der enge Rahmen gar nichts).
            // Doppelt so gross lesen, wie /api/lesen es tut -- unvergroessert las die Texterkennung hier nichts (11.10.).
            BufferedImage teil = kopie(bild, Math.max(rand0, h.x + 29 - 62), h.y + 21, 124, 34);
            String n = String.join(" ", de.hustlebot.core.Texterkennung.lies(skaliert(teil, 2.0)))
                    .replaceAll("[^\\p{L}\\p{N} '._-]", " ").replaceAll("\\s+", " ").strip();
            if (n.isBlank()) Log.debug(WER, "Einheit in der Reihe bei x " + h.x + ": Name nicht gelesen.");
            if (n.isBlank() || ausschnitte.containsKey(schluessel(n))) continue;
            ausschnitte.put(schluessel(n), new Ausschnitt(n, kopie(bild, gx, gy, gw, gh),
                    kopie(bild, schrift.x, schrift.y, schrift.width, schrift.height)));
            Log.info(WER, "Einheit in der Reihe: \"" + n + "\" -- Gesicht und Name ausgeschnitten.");
        }
    }

    private static org.opencv.core.Mat schildVorlage;

    /**
     * Die roten Stufen-Schilde in der Einheiten-Reihe (Band 300-400 von 770), selbst gesucht wie die Allianz-Zeichen:
     * die Bildsuche des Bots fand das kleine 16x16-Schild im ersten Lauf nicht (11.10.). Farbig, Schwelle 0,70
     * (gemessen: 1,00 und 0,77 an den Einheiten, sonst hoechstens 0,54).
     */
    static List<java.awt.Point> schildeFinden(BufferedImage bild) {
        List<java.awt.Point> treffer = new ArrayList<>();
        synchronized (TeamScan.class) {
            if (schildVorlage == null) {
                schildVorlage = org.opencv.imgcodecs.Imgcodecs.imread(Matcher.gelernt("team", T_EINHEIT_SCHILD + ".png").toString());
            }
        }
        if (bild == null || schildVorlage == null || schildVorlage.empty()) return treffer;
        int y0 = (int) (300 / 770.0 * bild.getHeight()), y1 = (int) (400 / 770.0 * bild.getHeight());
        // TYPE_3BYTE_BGR: so nimmt toMat den direkten Weg (eigener Speicher). Mit TYPE_INT_RGB zeichnete es in einen
        // wiederverwendeten Bildspeicher -- 11.10. landeten die Ausschnitte dadurch an der falschen Stelle.
        BufferedImage rgb = new BufferedImage(bild.getWidth(), y1 - y0, BufferedImage.TYPE_3BYTE_BGR);
        rgb.getGraphics().drawImage(bild.getSubimage(0, y0, bild.getWidth(), y1 - y0), 0, 0, null);
        org.opencv.core.Mat band = de.hustlebot.core.ScreenCapture.toMat(rgb), erg = new org.opencv.core.Mat();
        try {
            org.opencv.imgproc.Imgproc.matchTemplate(band, schildVorlage, erg, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            for (int n = 0; n < 10; n++) {
                org.opencv.core.Core.MinMaxLocResult m = org.opencv.core.Core.minMaxLoc(erg);
                if (m.maxVal < 0.70) break;
                treffer.add(new java.awt.Point((int) m.maxLoc.x, (int) m.maxLoc.y + y0));
                org.opencv.imgproc.Imgproc.rectangle(erg, new org.opencv.core.Point(m.maxLoc.x - 40, m.maxLoc.y - 40),
                        new org.opencv.core.Point(m.maxLoc.x + 40, m.maxLoc.y + 40), new org.opencv.core.Scalar(-1), -1);
            }
        } finally {
            band.release();
            erg.release();
        }
        treffer.sort(java.util.Comparator.comparingInt(p -> p.x));
        return treffer;
    }

    // ------------------------------------------------------------------
    //  Spiel-Einstellungen (Autor 11.10.: beim Rundgang zuerst, falsches selbst richtig stellen)
    // ------------------------------------------------------------------

    static final String T_ZAHNRAD = "ts_zahnrad";
    /** Stellen im Fenster "Settings" (gemessen an Autors Bildern vom 11.10., 1159 x 770 ohne Titelleiste). */
    private static final double[] ZAHNRAD = {1110 / 1159.0, 576 / 770.0};
    private static final double[] EINST_TITEL = {470 / 1159.0, 188 / 770.0, 220 / 1159.0, 38 / 770.0};
    private static final double[] EINST_SPRACHE = {290 / 1159.0, 255 / 770.0, 265 / 1159.0, 34 / 770.0};
    private static final double[] EINST_SPRACHE_KNOPF = {423 / 1159.0, 271 / 770.0};
    private static final double[] EINST_ENGLISH = {583 / 1159.0, 250 / 770.0};
    private static final double[] EINST_HD = {340 / 1159.0, 376 / 770.0, 210 / 1159.0, 34 / 770.0};
    private static final double[] EINST_HD_KNOPF = {423 / 1159.0, 393 / 770.0};
    private static final double[] EINST_VSYNC = {655 / 1159.0, 376 / 770.0, 215 / 1159.0, 34 / 770.0};
    private static final double[] EINST_VSYNC_KNOPF = {737 / 1159.0, 393 / 770.0};
    // Nur die Leiste zwischen "-" und "+" (gemessen 11.10.: Fuellung 129, Knopf 156, leer 78; die Knoepfe sind weiss).
    private static final double[] REGLER = {592 / 1159.0, 826 / 1159.0, 485 / 770.0};
    private static final double[] REGLER_MINUS = {566 / 1159.0, 485 / 770.0};
    private static final double[] REGLER_PLUS = {852 / 1159.0, 485 / 770.0};
    private static final double[] EINST_X = {912 / 1159.0, 174 / 770.0};
    private static final java.nio.file.Path EINST_DATEI = java.nio.file.Path.of("spieleinstellungen.txt");

    private static int px(BotContext ctx, double[] s) { return (int) (ctx.width() * s[0]); }

    private static int py(BotContext ctx, double[] s) { return (int) (ctx.height() * s[1]); }

    private static String lies(BufferedImage b, double[] r) {
        return ArenaLeser.liesTextEnglisch(b, (int) (r[0] * b.getWidth()), (int) (r[1] * b.getHeight()),
                (int) (r[2] * b.getWidth()), (int) (r[3] * b.getHeight())).strip();
    }

    /**
     * Ist das Fenster "Settings" offen? Am roten HD-Zeichen (in jeder Sprache gleich) oder am Titel -- 11.10.: Autor
     * hatte das Spiel auf Deutsch gestellt, der Titel hiess "Einstellungen", und der Bot tippte 8-mal aufs Zahnrad.
     */
    private boolean einstellungenOffen(BotContext ctx) {
        Mat jetzt = ctx.refresh();
        if (jetzt != null && suchen(jetzt, ctx, T_EINST_HD).isPresent()) return true;
        BufferedImage b = ctx.frame();
        if (b == null) return false;
        String t = lies(b, EINST_TITEL).toLowerCase(Locale.ROOT);
        for (String w : EINST_TITEL_WOERTER) if (t.contains(w)) return true;
        return false;
    }

    static final String T_EINST_HD = "ts_einst_hd";
    private static final String[] EINST_TITEL_WOERTER = {"setting", "einstellung", "настрой", "param", "impostazion",
            "ajuste", "configura", "ayar"};

    /** "On" (an), nicht "Off". */
    static boolean istAn(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        // Englisch On/Off -- zur Sicherheit auch Deutsch An/Aus, falls die Sprache noch nicht umgestellt ist.
        // Autor, 11.10.: auf Deutsch stand "Eingeschaltet" / "Aktiviert" -- auch das zaehlt als an.
        if (t.matches(".*\\boff\\b.*") || t.contains("aus") || t.contains("deaktiv")) return false;
        return t.matches(".*\\b(on|an)\\b.*") || t.contains("ein") || t.contains("aktiv");
    }

    /** Wo der Regler "Scrolling sensitivity" steht: 0 = ganz links, 1 = ganz rechts; -1 = nicht erkannt. */
    static double reglerStand(BufferedImage b) {
        if (b == null) return -1;
        int x0 = (int) (REGLER[0] * b.getWidth()), x1 = (int) (REGLER[1] * b.getWidth()), y = (int) (REGLER[2] * b.getHeight());
        int letzte = -1;
        for (int x = x0; x <= x1; x++) {
            int summe = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int rgb = b.getRGB(x, y + dy);
                summe += ((rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255)) / 3;
            }
            if (summe / 3 > 100) letzte = x;
        }
        return letzte < 0 ? -1 : (letzte - x0) / (double) (x1 - x0);
    }

    /** Der gemerkte Regler-Stand (beim ersten Mal der jetzige). */
    private static double reglerSoll(double jetzt) {
        try {
            if (java.nio.file.Files.exists(EINST_DATEI)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(EINST_DATEI)) {
                    if (z.startsWith("scroll_soll=")) return Double.parseDouble(z.substring(12).trim());
                }
            }
        } catch (Exception e) {
            Log.debug(WER, "spieleinstellungen.txt nicht lesbar: " + e.getMessage());
        }
        // Ohne Datei: Autors Stand vom 11.10. (Bild mit Kreisen, Regler bei etwa 17 %), nicht der jetzige --
        // der kann gerade verstellt sein.
        return REGLER_STANDARD;
    }

    // Autor, 11.10. (Bild mit rotem Kreis, Knopf bei etwa x 622): "ungefaehr auf diese Laenge vom Balken bringen".
    static final double REGLER_STANDARD = 0.17;
    /** Haken rechts im Knopf (Autor 11.10.: "Wenn dieser Haken da ist, ist es aktiviert"), Bild: 520-552 / 379-409. */
    private static final double[] EINST_HD_HAKEN = {518 / 1159.0, 378 / 770.0, 38 / 1159.0, 32 / 770.0};
    private static final double[] EINST_VSYNC_HAKEN = {832 / 1159.0, 378 / 770.0, 38 / 1159.0, 32 / 770.0};

    /** Steht im Feld ein weisser Haken? (viele fast weisse Punkte; der Knopf selbst ist hellblau) */
    static boolean haken(BufferedImage b, double[] r) {
        if (b == null) return false;
        int x0 = (int) (r[0] * b.getWidth()), y0 = (int) (r[1] * b.getHeight());
        int w = (int) (r[2] * b.getWidth()), h = (int) (r[3] * b.getHeight());
        int weiss = 0;
        for (int y = y0; y < y0 + h && y < b.getHeight(); y++) {
            for (int x = x0; x < x0 + w && x < b.getWidth(); x++) {
                int rgb = b.getRGB(x, y);
                if ((rgb >> 16 & 255) > 235 && (rgb >> 8 & 255) > 235 && (rgb & 255) > 235) weiss++;
            }
        }
        return weiss >= 25;
    }
    /** "Bestaetigen" in der Rueckfrage "Moechtest du die Sprache wirklich aendern?" (Autors Bild 11.10.: 475/487). */
    private static final double[] SPRACHE_BESTAETIGEN = {475 / 1159.0, 487 / 770.0};

    /**
     * Liest das Fenster "Settings" und stellt Falsches richtig -- immer nur EINE Sache, die das Spiel neu laedt
     * (HD Graphics, V Sync, Sprache). true = umgestellt, das Spiel laedt jetzt neu (Schritt EINST_WARTEN).
     * Der Regler "Scrolling sensitivity" laedt nichts neu und wird zuerst gestellt.
     */
    private boolean einstellungenPruefen(BotContext ctx) {
        BufferedImage b = ctx.frame();
        if (b == null) return false;
        foto(b, "einstellungen");
        String sprache = lies(b, EINST_SPRACHE), hd = lies(b, EINST_HD), vsync = lies(b, EINST_VSYNC);
        double regler = reglerStand(b);
        double soll = reglerSoll(regler);
        boolean reglerGestellt = false;
        for (int i = 0; i < 10 && regler >= 0 && soll >= 0 && Math.abs(regler - soll) > 0.05; i++) {
            boolean mehr = regler < soll;
            double[] k = mehr ? REGLER_PLUS : REGLER_MINUS;
            tippen(ctx, px(ctx, k), py(ctx, k), "Scrolling sensitivity " + (mehr ? "+" : "-"));
            reglerGestellt = true;
            ctx.refresh();
            regler = reglerStand(ctx.frame());
        }
        merken(sprache, hd, vsync, regler, soll);
        String stand = "Sprache \"" + sprache + "\", HD \"" + hd + "\", V Sync \"" + vsync + "\", Scrolling "
                + (regler < 0 ? "nicht erkannt" : Math.round(regler * 100) + " %") + (reglerGestellt ? " (zurueckgestellt)" : "");
        if (!sprache.toLowerCase(Locale.ROOT).contains("english") && sprache.length() >= 4) {
            Log.info(WER, "Spiel-Einstellungen: " + stand + " -- Sprache wird auf English gestellt, das Spiel laedt neu.");
            tippen(ctx, px(ctx, EINST_SPRACHE_KNOPF), py(ctx, EINST_SPRACHE_KNOPF), "Sprache waehlen");
            ctx.sleep(700);
            tippen(ctx, px(ctx, EINST_ENGLISH), py(ctx, EINST_ENGLISH), "Sprache English");
            ctx.sleep(800);
            // Autor, 11.10.: "Er muss auf Bestaetigen druecken, wenn er die Sprache aendert."
            tippen(ctx, px(ctx, SPRACHE_BESTAETIGEN), py(ctx, SPRACHE_BESTAETIGEN), "Sprache bestaetigen");
            return true;
        }
        if (istAn(hd) || haken(b, EINST_HD_HAKEN)) {
            Log.info(WER, "Spiel-Einstellungen: " + stand + " -- HD Graphics wird ausgeschaltet, das Spiel laedt neu.");
            tippen(ctx, px(ctx, EINST_HD_KNOPF), py(ctx, EINST_HD_KNOPF), "HD Graphics ausschalten");
            return true;
        }
        if (istAn(vsync) || haken(b, EINST_VSYNC_HAKEN)) {
            Log.info(WER, "Spiel-Einstellungen: " + stand + " -- V Sync wird ausgeschaltet, das Spiel laedt neu.");
            tippen(ctx, px(ctx, EINST_VSYNC_KNOPF), py(ctx, EINST_VSYNC_KNOPF), "V Sync ausschalten");
            return true;
        }
        Log.info(WER, "Spiel-Einstellungen: " + stand + " -- alles richtig.");
        return false;
    }

    private static void merken(String sprache, String hd, String vsync, double regler, double soll) {
        try {
            java.nio.file.Files.writeString(EINST_DATEI, "# Vom Team-Rundgang gemerkt (Zahnrad im Spiel), alle 3 Tage neu\n"
                    + "stand=" + Zeitzone.jetzt() + "\nsprache=" + sprache + "\nhd=" + hd + "\nvsync=" + vsync
                    + "\nscroll=" + regler + "\nscroll_soll=" + soll + "\n");
        } catch (Exception e) {
            Log.debug(WER, "spieleinstellungen.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /** Die dunkle Schrift (Name) im hellen Feld: kleinster Rahmen um alle dunklen Punkte, null wenn keine. */
    private static java.awt.Rectangle schriftRahmen(BufferedImage b, int x, int y, int w, int h) {
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, b.getWidth() - x);
        h = Math.min(h, b.getHeight() - y);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int j = y; j < y + h; j++) {
            for (int i = x; i < x + w; i++) {
                int rgb = b.getRGB(i, j);
                if (((rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255)) / 3 < 100) {
                    minX = Math.min(minX, i);
                    maxX = Math.max(maxX, i);
                    minY = Math.min(minY, j);
                    maxY = Math.max(maxY, j);
                }
            }
        }
        if (maxX < 0 || maxX - minX < 8) return null;
        int rx = Math.max(0, minX - 1), ry = Math.max(0, minY - 1);
        return new java.awt.Rectangle(rx, ry, Math.min(b.getWidth() - rx, maxX - minX + 3), Math.min(b.getHeight() - ry, maxY - minY + 3));
    }

    private static BufferedImage kopie(BufferedImage b, int x, int y, int w, int h) {
        BufferedImage k = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = k.createGraphics();
        g.drawImage(b.getSubimage(x, y, w, h), 0, 0, null);
        g.dispose();
        return k;
    }

    private static String schluessel(String name) {
        // "Bogen I" (Reihe) und "Bogen 1" (Einheiten-Fenster) sind derselbe: i/l/| wie 1, 0 wie o.
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[il|!]", "1").replace('0', 'o')
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * Autor, 11.10.: die Einheit mit der meisten Kraft wird Einheit 1, die naechste Einheit 2 ..., die schwaechste ist
     * zugleich die Opfereinheit. Gespeichert in Verlies-Groesse nach templates/gelernt/dungeon/einheiten/auto
     * (unit_auto_1 / name_auto_1 ..., unit_auto_opfer / name_auto_opfer), die Liste in einheiten-auto.txt.
     * Autors eigene Bilder in templates/ bleiben unberuehrt.
     */
    private int opferIndexAus(List<Ausschnitt> rang, java.util.Map<String, Long> kraft) {
        int i = -1;
        for (int j = 0; j < rang.size(); j++) if (kraft.getOrDefault(schluessel(rang.get(j).name()), -1L) > 0) i = j;
        return i;
    }

    private void einheitenBilderSpeichern() {
        if (ausschnitte.isEmpty()) return;
        java.util.Map<String, Long> kraftNachName = new java.util.HashMap<>();
        for (MeinTeam.Einheit e : einheiten) if (e.kraft() > 0) kraftNachName.merge(schluessel(e.name()), e.kraft(), Math::max);
        List<Ausschnitt> rang = new ArrayList<>(ausschnitte.values());
        rang.sort((a, b) -> Long.compare(kraftNachName.getOrDefault(schluessel(b.name()), -1L),
                kraftNachName.getOrDefault(schluessel(a.name()), -1L)));
        java.nio.file.Path ordner = Matcher.GELERNT.resolve("dungeon").resolve("einheiten").resolve("auto");
        StringBuilder liste = new StringBuilder("# Vom Bot in der Kaserne aufgenommen (Team-Rundgang), meiste Kraft = 1\n");
        StringBuilder text = new StringBuilder();
        try {
            java.nio.file.Files.createDirectories(ordner);
            // Code-Review 15.10.: Opfer ist die schwaechste Einheit MIT gelesener Kraft. Vorher war es
            // einfach die letzte -- und eine Einheit, deren Kraft nicht lesbar war (-1), landete immer dort.
            int opferIndex = opferIndexAus(rang, kraftNachName);
            for (int i = 0; i < rang.size(); i++) {
                Ausschnitt a = rang.get(i);
                long k = kraftNachName.getOrDefault(schluessel(a.name()), -1L);
                bilderSchreiben(a, ordner, String.valueOf(i + 1));
                if (i == opferIndex) bilderSchreiben(a, ordner, "opfer");
                liste.append(i + 1).append(" | ").append(a.name()).append(" | ").append(k).append('\n');
                text.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(a.name())
                        .append(k > 0 ? " (" + String.format(Locale.GERMANY, "%,d", k) + ")" : " (Kraft unbekannt)");
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("einheiten-auto.txt"), liste.toString());
        } catch (Exception e) {
            Log.warn(WER, "Einheiten-Bilder nicht gespeichert: " + e.getMessage());
            return;
        }
        Log.info(WER, "Einheiten-Bilder aufgenommen: " + text + " -- Opfereinheit: " + (opferIndexAus(rang, kraftNachName) >= 0 ? rang.get(opferIndexAus(rang, kraftNachName)).name() : "keine (Kraft nicht lesbar)") + ".");
    }

    private static void bilderSchreiben(Ausschnitt a, java.nio.file.Path ordner, String nr) throws java.io.IOException {
        javax.imageio.ImageIO.write(skaliert(a.gesicht(), GESICHT_ZUM_VERLIES), "png", ordner.resolve("unit_auto_" + nr + ".png").toFile());
        javax.imageio.ImageIO.write(skaliert(a.schrift(), NAME_ZUM_VERLIES), "png", ordner.resolve("name_auto_" + nr + ".png").toFile());
    }

    /**
     * Autor, 11.10.: "Verlies soll die Bot-Bilder benutzen." Die Bot-Bilder (meiste Kraft = 1) kommen vorne in eine
     * Liste, die festen Vorlagen bleiben dahinter als Rueckfall. Fehlt ein Bild, ueberspringt der Bot es ohnehin.
     */
    static String[] mitBotBildern(String art, String... fest) {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= 8; i++) l.add(art + "_auto_" + i);
        l.addAll(List.of(fest));
        return l.toArray(new String[0]);
    }

    private static BufferedImage skaliert(BufferedImage b, double f) {
        int w = Math.max(1, (int) Math.round(b.getWidth() * f)), h = Math.max(1, (int) Math.round(b.getHeight() * f));
        BufferedImage k = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = k.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(b, 0, 0, w, h, null);
        g.dispose();
        return k;
    }

    /**
     * "42 930 1301" -> 42930130. Das orange "i" rechts neben der Zahl liest die Texterkennung als 1 oder 0 mit
     * (11.10., Autors Kaserne) -- darum nur die Zahl in Dreiergruppen nehmen.
     */
    static long squadAusText(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,3}(?:[ .,]\\d{3}){1,3}").matcher(text);
        if (!m.find()) return -1;
        long k = Long.parseLong(m.group().replaceAll("[ .,]", ""));
        return k > 100_000 ? k : -1;
    }

    private boolean einheitenFensterOffen(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, "ar_dwellers") || ctx.visibleIfKnown(screen, ArenaPruefer.T_KASERNE_FENSTER);
    }

    /** Je Einheit (aus dem ArenaPruefer): Name oben im Fenster, Kraft aus der Pruefung. */
    private void einheitLesen(BufferedImage bild, Long kraft) {
        if (bild == null) return;
        int x = (int) (EINHEIT_NAME[0] * bild.getWidth()), y = (int) (EINHEIT_NAME[1] * bild.getHeight());
        int w = (int) (EINHEIT_NAME[2] * bild.getWidth()), h = (int) (EINHEIT_NAME[3] * bild.getHeight());
        String n = ArenaLeser.liesText(bild, x, y, w, h);
        n = n.replaceAll("[^\\p{L}\\p{N} '._-]", " ").replaceAll("\\s+", " ").strip();
        if (n.isBlank()) n = "Einheit " + (einheiten.size() + 1);
        long k = kraft == null ? -1 : kraft;
        einheiten.add(new MeinTeam.Einheit(n, k));
        Log.info(WER, "Einheit " + einheiten.size() + ": \"" + n + "\"" + (k > 0 ? ", Kraft " + String.format(Locale.GERMANY, "%,d", k) : ", Kraft nicht gelesen") + ".");
        if (einheiten.size() <= 2) foto(bild, "einheit-" + einheiten.size());
    }

    private boolean heldFensterOffen(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, T_HELD_ACTIVE) || ctx.visibleIfKnown(screen, T_HELD_BATTLE);
    }

    /** Heldenfenster: Name, Kraft, Haupt- und Nebenfaehigkeiten gegen die Serien held_haupt_gut_* / held_neben_gut_*. */
    private void heldLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int bw = bild.getWidth(), bh = bild.getHeight();
        String n = ArenaLeser.liesText(bild, (int) (HELD_NAME[0] * bw), (int) (HELD_NAME[1] * bh), (int) (HELD_NAME[2] * bw), (int) (HELD_NAME[3] * bh));
        n = n.replaceAll("[^\\p{L}\\p{N} '._-]", " ").replaceAll("\\s+", " ").strip();
        if (n.isBlank()) n = "Held";
        List<Long> zahlen = ArenaLeser.zahlenIn(bild, (int) (HELD_KRAFT[0] * bw), (int) (HELD_KRAFT[1] * bh), (int) (HELD_KRAFT[2] * bw), (int) (HELD_KRAFT[3] * bh))
                .stream().filter(z -> z > 100_000 && z < 2_000_000_000L).toList();
        long kraft = zahlen.size() == 1 ? zahlen.get(0) : -1;
        Boolean hauptGut = null;
        int guteNeben = 0;
        boolean vorlagen = !ctx.matcher().serie(S_HELD_HAUPT_GUT).isEmpty() || !ctx.matcher().serie(S_HELD_NEBEN_GUT).isEmpty();
        if (vorlagen) {
            hauptGut = !ctx.matcher().serie(S_HELD_HAUPT_GUT).isEmpty()
                    && trefferIn(screen, ctx, S_HELD_HAUPT_GUT, HELD_HAUPT, 0.84);
            for (double[] stelle : HELD_NEBEN) {
                if (trefferIn(screen, ctx, S_HELD_NEBEN_GUT, stelle, 0.84)) guteNeben++;
            }
        }
        held = new MeinTeam.Held(n, kraft, hauptGut, guteNeben);
        Log.info(WER, "Held: \"" + n + "\"" + (kraft > 0 ? ", Kraft " + String.format(Locale.GERMANY, "%,d", kraft) : ", Kraft nicht gelesen")
                + (vorlagen ? ", Hauptfaehigkeit " + (Boolean.TRUE.equals(hauptGut) ? "gut" : "normal") + ", gute Nebenfaehigkeiten " + guteNeben
                            : ", Faehigkeiten nicht pruefbar (keine Vorlagen held_haupt_gut_* / held_neben_gut_*)") + ".");
        foto(bild, "held");
    }

    // ------------------------------------------------------------------
    //  Profil (Autor 15.10.): Burg oben links -> "Battles"
    // ------------------------------------------------------------------

    /**
     * Ausschnitt doppelt so gross lesen -- wie das Bedienfeld (/api/lesen). Test 15.10.: in Originalgroesse las die
     * Erkennung die Reiter nicht, dreifach (ArenaLeser.liesTextGross) wurde "Tom" zu "Sbor" und "Pals" fehlte.
     */
    static String liesDoppelt(BufferedImage bild, int x, int y, int breite, int hoehe) {
        if (bild == null) return "";
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return "";
        BufferedImage gross = new BufferedImage((x2 - x1) * 2, (y2 - y1) * 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        try {
            return String.join(" ", de.hustlebot.core.Texterkennung.lies(gross)).trim();
        } catch (Exception e) {
            Log.debug(WER, "Ausschnitt nicht lesbar: " + e.getMessage());
            return "";
        }
    }

    private static int rx(BotContext ctx, double anteil) { return (int) Math.round(anteil * ctx.width()); }

    private static int ry(BotContext ctx, double anteil) { return (int) Math.round(anteil * ctx.height()); }

    private static String text(BufferedImage b, double[] s) {
        int bw = b.getWidth(), bh = b.getHeight();
        // Test 15.10. 11:42: in Originalgroesse las die Erkennung die Reiter-Zeile nicht ("Profil ging nicht auf", obwohl es
        // offen war); das Bedienfeld liest doppelt so gross und sah "Rewards Castle Battles" sofort.
        return liesDoppelt(b, (int) (s[0] * bw), (int) (s[1] * bh), (int) (s[2] * bw), (int) (s[3] * bh))
                .toLowerCase(Locale.ROOT);
    }

    /** Das Profil ist offen: oben stehen die Reiter "Rewards", "Castle", "Battles". */
    private static boolean profilOffen(BufferedImage b) {
        String t = text(b, P_REITER);
        return t.contains("battles") || t.contains("rewards");
    }

    /** Reiter "Battles" offen: Ueberschrift "Hero" ueber dem Helden -- ohne Held (Thronsaal unter 9) "Titans"/"Patronage"/"Pals". */
    private static boolean battlesOffen(BufferedImage b) {
        if (text(b, P_HERO).contains("hero")) return true;
        String t = text(b, P_BATTLES_OBEN);
        return t.contains("titans") || t.contains("patronage") || t.contains("pals");
    }

    /** Das Fenster "Squad Challenge" ist offen (Ueberschrift oben mittig). */
    private static boolean squadOffen(BufferedImage b) {
        String t = text(b, SQUAD_TITEL);
        return t.contains("squad") || t.contains("challenge");
    }

    /** Stelle der i-ten Faehigkeit (0 = Hauptfaehigkeit) als Anteile x, y, b, h. */
    static double[] faehigkeit(int i) {
        return new double[]{(P_FAEHIGKEIT_X[i] - P_FAEHIGKEIT / 2.0) / 1159.0, (P_FAEHIGKEIT_Y - P_FAEHIGKEIT / 2.0) / 770.0,
                P_FAEHIGKEIT / 1159.0, P_FAEHIGKEIT / 770.0};
    }

    /**
     * Held, Faehigkeiten und Titanen-Schirmherrschaften aus dem Reiter "Battles". Bewertet wie bisher: Faehigkeiten
     * gegen held_haupt_gut_* / held_neben_gut_*, Schirmherrschaften und Stigma-Sets mit denselben Bildern und Punkten
     * wie bei Gegnern in der Arena (SchirmLeser). Die fuenf Faehigkeiten werden jedes Mal als
     * logs/team/held-faehigkeit-N.png abgelegt -- daraus werden die "guten" Bilder, sobald Autor sie bestimmt hat.
     */
    private void profilLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int bw = bild.getWidth(), bh = bild.getHeight();
        // Autor 25.10.: der Thronsaal steht links im Profil ("Level 5" neben dem Thron). Davon haengt ab, was es schon gibt
        // (Kathedrale ab 6, Titanenturm/Schirmherren ab 7, Edelsteinbucht ab 8, Verlies/Held ab 9, Sets/Guardians ab 11).
        thronsaal = de.hustlebot.core.Thronsaal.ausText(liesDoppelt(bild, (int) (P_THRON_STUFE[0] * bw), (int) (P_THRON_STUFE[1] * bh),
                (int) (P_THRON_STUFE[2] * bw), (int) (P_THRON_STUFE[3] * bh)));
        Log.info(WER, "Thronsaal (Profil links): " + (thronsaal > 0 ? "Stufe " + thronsaal : "nicht gelesen") + ".");
        boolean mitHeld = text(bild, P_HERO).contains("hero");
        // Lesefehler abfangen: "Level 11" als "Level 1" gelesen wuerde die Kathedrale sperren. Mit Held ist es mindestens
        // Thronsaal 9 -- eine kleinere Zahl ist dann falsch gelesen und zaehlt nicht (lieber nichts sperren).
        if (mitHeld && thronsaal > 0 && thronsaal < de.hustlebot.core.Thronsaal.Teil.HELD.ab) {
            Log.info(WER, "Thronsaal " + thronsaal + " passt nicht zum Helden im Profil -- falsch gelesen, wird nicht gemerkt.");
            thronsaal = -1;
        }
        if (!mitHeld) {
            held = null;
            Log.info(WER, "Kein Held im Profil" + (thronsaal > 0 ? " (Thronsaal " + thronsaal + ")" : "") + " -- der Held wird uebersprungen.");
            profilOhneHeld(screen, bild, ctx);
            return;
        }
        // Test 15.10. 11:45: in Originalgroesse las die Erkennung "Tom" und die Kraft nicht -- vergroessert lesen.
        String n = liesDoppelt(bild, (int) (P_HELD_NAME[0] * bw), (int) (P_HELD_NAME[1] * bh), (int) (P_HELD_NAME[2] * bw), (int) (P_HELD_NAME[3] * bh))
                .replaceAll("[^\\p{L}\\p{N} '._-]", " ").replaceAll("\\s+", " ").strip();
        if (n.isBlank()) n = "Held";
        // "4 851 600" (Kraft) und "9" (Talente) stehen nebeneinander -- die grosse Zahl ist die Kraft.
        String ziffern = liesDoppelt(bild, (int) (P_HELD_KRAFT[0] * bw), (int) (P_HELD_KRAFT[1] * bh),
                (int) (P_HELD_KRAFT[2] * bw), (int) (P_HELD_KRAFT[3] * bh)).replaceAll("[^0-9]", "");
        long kraft = ziffern.length() >= 6 && ziffern.length() <= 10 ? Long.parseLong(ziffern) : -1;
        boolean vorlagen = !ctx.matcher().serie(S_HELD_HAUPT_GUT).isEmpty() || !ctx.matcher().serie(S_HELD_NEBEN_GUT).isEmpty();
        Boolean hauptGut = null;
        int guteNeben = 0;
        for (int i = 0; i < P_FAEHIGKEIT_X.length; i++) {
            double[] stelle = faehigkeit(i);
            faehigkeitSichern(bild, stelle, i + 1);
            if (!vorlagen) continue;
            if (i == 0) hauptGut = !ctx.matcher().serie(S_HELD_HAUPT_GUT).isEmpty() && trefferIn(screen, ctx, S_HELD_HAUPT_GUT, stelle, 0.84);
            else if (trefferIn(screen, ctx, S_HELD_NEBEN_GUT, stelle, 0.84)) guteNeben++;
        }
        held = new MeinTeam.Held(n, kraft, hauptGut, guteNeben);
        // Autor 15.10.: "Dort kann der Bot ausserdem die Power und den Namen ablesen" (links im Profil).
        String spieler = liesDoppelt(bild, (int) (P_SPIELER[0] * bw), (int) (P_SPIELER[1] * bh), (int) (P_SPIELER[2] * bw), (int) (P_SPIELER[3] * bh))
                .replaceAll("[^A-Za-z0-9 ._-]", " ").replaceAll(" +", " ").strip();
        if (name.isBlank() && !spieler.isBlank()) name = spieler;
        String powerZiffern = liesDoppelt(bild, (int) (P_POWER[0] * bw), (int) (P_POWER[1] * bh), (int) (P_POWER[2] * bw), (int) (P_POWER[3] * bh))
                .replaceAll("[^0-9]", "");
        profilPower = powerZiffern.length() >= 6 && powerZiffern.length() <= 11 ? Long.parseLong(powerZiffern) : -1;
        Log.info(WER, "Profil links: Name \"" + spieler + "\", Power "
                + (profilPower > 0 ? String.format(Locale.GERMANY, "%,d", profilPower) : "nicht gelesen") + ".");
        Log.info(WER, "Held (Profil):\"" + n + "\"" + (kraft > 0 ? ", Kraft " + String.format(Locale.GERMANY, "%,d", kraft) : ", Kraft nicht gelesen")
                + (vorlagen ? ", Hauptfaehigkeit " + (Boolean.TRUE.equals(hauptGut) ? "gut" : "normal") + ", gute Nebenfaehigkeiten " + guteNeben
                            : ", Faehigkeiten noch nicht bewertet (keine Bilder held_haupt_gut_* / held_neben_gut_*)") + ".");

        profilOhneHeld(screen, bild, ctx);
    }

    /** Spielername und Power links, Schirmherrschaften unter "Titans" (auch ohne Held). */
    private void profilOhneHeld(Mat screen, BufferedImage bild, BotContext ctx) {
        int bw = bild.getWidth(), bh = bild.getHeight();
        if (held == null) {
            String spieler = liesDoppelt(bild, (int) (P_SPIELER[0] * bw), (int) (P_SPIELER[1] * bh), (int) (P_SPIELER[2] * bw), (int) (P_SPIELER[3] * bh))
                    .replaceAll("[^A-Za-z0-9 ._-]", " ").replaceAll(" +", " ").strip();
            if (spieler.length() > name.length() && NamensVergleich.form(spieler).contains(NamensVergleich.form(name))) name = spieler;
            String powerZiffern = liesDoppelt(bild, (int) (P_POWER[0] * bw), (int) (P_POWER[1] * bh), (int) (P_POWER[2] * bw), (int) (P_POWER[3] * bh))
                    .replaceAll("[^0-9]", "");
            profilPower = powerZiffern.length() >= 5 && powerZiffern.length() <= 11 ? Long.parseLong(powerZiffern) : -1;
            Log.info(WER, "Profil links: Name \"" + spieler + "\", Power "
                    + (profilPower > 0 ? String.format(Locale.GERMANY, "%,d", profilPower) : "nicht gelesen") + ".");
        }
        if (thronsaal > 0 && !name.isBlank()) de.hustlebot.core.Thronsaal.merken(name, thronsaal);
        if (thronsaal > 0) de.hustlebot.core.Thronsaal.aktuellSetzen(thronsaal);
        // Autor 25.10.: Schirmherrschaften gibt es erst ab Thronsaal 7 -- vorher steht dort "You haven't activated ..."
        if (thronsaal > 0 && thronsaal < de.hustlebot.core.Thronsaal.Teil.SCHIRMHERREN.ab) {
            Log.info(WER, "Schirmherrschaften erst ab Thronsaal " + de.hustlebot.core.Thronsaal.Teil.SCHIRMHERREN.ab + " -- uebersprungen.");
            foto(bild, "profil");
            return;
        }
        // Titanen-Schirmherrschaften mit Stigma-Sets: nur die drei Karten unter "Titans".
        org.opencv.core.Rect titanen = new org.opencv.core.Rect((int) (P_TITANEN[0] * screen.width()), (int) (P_TITANEN[1] * screen.height()),
                (int) (P_TITANEN[2] * screen.width()), (int) (P_TITANEN[3] * screen.height()));
        List<String> fehlt = SchirmLeser.fehlt(ctx.matcher());
        if (!fehlt.isEmpty()) Log.info(WER, "Schirmherren: es fehlen Vorlagen " + fehlt + " -- gezaehlt wird, was geht.");
        SchirmLeser.Ergebnis e = SchirmLeser.lesen(screen, ctx.matcher(), titanen, SchirmLeser.SCHWELLE_LV_EIGEN, true);
        schirmErgebnis = e;
        schirmAnzahl = e.anzahl();
        guteSchirme = e.gute();
        guteStigmas = e.stigmas();
        SchirmLeser.protokoll("deines Teams (Profil)", e);
        MeinTeam.schirmeSpeichern(e);
        schirmLevelLesen(screen, bild, ctx, titanen);
        foto(bild, "profil");
    }

    /**
     * Steht die Ueberschrift "Pals" (oder "Pal Squad:") im Bild? Erst der grosse Ausschnitt, dann die schmale Zeile.
     *
     * WARUM so tolerant (Bugjagd 29.10., 14 Fotos aus logs\team): die Windows-Texterkennung liest "Pals" als "Pats", "Pale",
     * "Pala", "als" und "Pal Squad:" als "Pat Squad:", "PalSauad:". Das alte {@code contains("pal")} fand die Ueberschrift
     * nur in 9 von 14 Bildern -- bei den anderen wurden die Pals nicht gezaehlt (Autor: 91 statt 101 Punkte fuer das eigene Team).
     */
    private static boolean palsSichtbar(BufferedImage bild) {
        return palsUeberschrift(text(bild, P_PALS)) || palsUeberschrift(text(bild, P_PALS_SCHMAL));
    }

    /**
     * Kommt in dem gelesenen Text ein Wort vor, das hoechstens einen Buchstaben von "Pals" entfernt ist, oder "Pal Squad"
     * (auch zusammengeschrieben oder verlesen)? "Squad" allein zaehlt nicht: "Squad Leaders" steht im Profil links.
     */
    static boolean palsUeberschrift(String gelesen) {
        if (gelesen == null) return false;
        String[] w = gelesen.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (int i = 0; i < w.length; i++) {
            if (w[i].length() >= 3 && w[i].length() <= 5 && abstand(w[i], "pals") <= 1) return true;
            if (w[i].length() >= 7 && w[i].length() <= 10 && abstand(w[i], "palsquad") <= 2) return true;
            if (i > 0 && w[i].length() >= 4 && w[i].length() <= 6 && abstand(w[i], "squad") <= 1
                    && w[i - 1].length() >= 2 && w[i - 1].length() <= 4 && abstand(w[i - 1], "pal") <= 1) return true;
        }
        return false;
    }

    /** Anzahl Einfuegungen/Loeschungen/Ersetzungen zwischen zwei kurzen Woertern (Levenshtein). */
    static int abstand(String a, String b) {
        int[] vor = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vor[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                jetzt[j] = Math.min(Math.min(jetzt[j - 1], vor[j]) + 1, vor[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = vor; vor = jetzt; jetzt = t;
        }
        return vor[b.length()];
    }

    /** Die Pals nach dem Ziehen: gute Pals mit denselben Bildern wie in der Arena (+4 je guten). */
    private void palsLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        boolean da = palsSichtbar(bild);
        if (!da) {
            // Zweite Chance: die Texterkennung liest eine Zeile wie "Pals" mal so, mal so (Bugjagd 29.10.: 5 von 14 Fotos
            // "nicht gefunden", obwohl die Ueberschrift da war) -- ein frisches Bild, noch einmal.
            ctx.sleep(300);
            Mat nochmal = ctx.refresh();
            BufferedImage b2 = nochmal == null ? null : ctx.frame();
            if (b2 != null && palsSichtbar(b2)) {
                bild = b2;
                screen = nochmal;
                da = true;
            }
        }
        if (!da) {
            Log.warn(WER, "Pals: die Ueberschrift \"Pals\" ist nach dem Ziehen nicht zu sehen -- Pals nicht gezaehlt.");
            foto(bild, "pals-nicht-gefunden");
            return;
        }
        gutePals = SchirmLeser.zaehleSerie(screen, ctx.matcher(), S_PAL_GUT, 0.84);
        // Autor 15.10.: "Mein zweiter Pal ist gut, der dritte ist meh -- meh +0,5 Punkte."
        mehPals = SchirmLeser.zaehleSerie(screen, ctx.matcher(), S_PAL_MEH, 0.84);
        Log.info(WER, "Pals (Profil): " + gutePals + " gute (+" + Gegnerbuch.palPunkte(gutePals) + "), " + mehPals + " meh (+" + (mehPals * 0.5) + ").");
        foto(bild, "pals");
    }

    /** Ausschnitt einer Faehigkeit als logs/team/held-faehigkeit-N.png (wird jedes Mal ueberschrieben). */
    private static void faehigkeitSichern(BufferedImage bild, double[] s, int nr) {
        try {
            int bw = bild.getWidth(), bh = bild.getHeight();
            int x = Math.max(0, (int) (s[0] * bw)), y = Math.max(0, (int) (s[1] * bh));
            int w = Math.min(bw - x, (int) (s[2] * bw)), h = Math.min(bh - y, (int) (s[3] * bh));
            if (w <= 0 || h <= 0) return;
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "team");
            de.hustlebot.core.Beleg.schreiben(bild.getSubimage(x, y, w, h), "png", ordner.resolve("held-faehigkeit-" + nr + ".png").toFile());
        } catch (Exception ex) {
            Log.debug(WER, "Faehigkeit " + nr + " nicht gesichert: " + ex.getMessage());
        }
    }

    /** "Lv." in einem Bereich suchen und die Zahl rechts daneben lesen (hoechstens 3). */
    private void schirmLevelLesen(Mat screen, BufferedImage bild, BotContext ctx, org.opencv.core.Rect bereich) {
        schirmLevel.clear();
        // Test 15.10. 11:50: einzeln gelesen kam [-1, -1, 3] -- zuerst der ganze Bereich auf einmal, doppelt gross.
        String alles = liesDoppelt(bild, bereich.x, bereich.y, bereich.width, bereich.height).toLowerCase(Locale.ROOT);
        java.util.regex.Matcher ganz = java.util.regex.Pattern.compile("lv[^0-9]{0,3}([0-9]{1,2})").matcher(alles);
        while (ganz.find() && schirmLevel.size() < SchirmLeser.MAX) schirmLevel.add(Integer.parseInt(ganz.group(1)));
        if (schirmLevel.size() >= SchirmLeser.MAX) {
            Log.info(WER, "Schirmherren-Level: " + schirmLevel + ".");
            return;
        }
        schirmLevel.clear();
        if (!ctx.matcher().has(SchirmLeser.T_LV)) return;
        List<Matcher.Hit> lv = new ArrayList<>();
        for (Matcher.Hit h : ctx.matcher().findAllGrau(screen, SchirmLeser.T_LV, SchirmLeser.SCHWELLE_LV_EIGEN, 9)) {
            if (h.centerX() >= bereich.x && h.centerX() <= bereich.x + bereich.width
                    && h.centerY() >= bereich.y && h.centerY() <= bereich.y + bereich.height) lv.add(h);
        }
        for (Matcher.Hit h : SchirmLeser.stellen(lv, SchirmLeser.ABSTAND_LV, SchirmLeser.ABSTAND_LV)) {
            // Test 15.10. 11:45: "Lv. 3" ist kleine Schrift -- vergroessert lesen, die Zahl hinter "Lv".
            String lvText = liesDoppelt(bild, Math.max(0, h.x() - 4), Math.max(0, h.y() - 4), h.width() * 3 + 8, h.height() + 8)
                    .toLowerCase(Locale.ROOT).replace("lv", " ");
            java.util.regex.Matcher lvZahl = java.util.regex.Pattern.compile("([0-9]{1,2})").matcher(lvText);
            schirmLevel.add(lvZahl.find() ? Integer.parseInt(lvZahl.group(1)) : -1);
            if (schirmLevel.size() >= SchirmLeser.MAX) break;
        }
        if (!schirmLevel.isEmpty()) Log.info(WER, "Schirmherren-Level: " + schirmLevel + ".");
    }

    /** Trifft eine Vorlage der Serie in diesem Ausschnitt (relative Stelle x, y, b, h)? */
    private static boolean trefferIn(Mat screen, BotContext ctx, String serie, double[] stelle, double schwelle) {
        int x0 = (int) (stelle[0] * ctx.width()), y0 = (int) (stelle[1] * ctx.height());
        int x1 = x0 + (int) (stelle[2] * ctx.width()), y1 = y0 + (int) (stelle[3] * ctx.height());
        for (String t : ctx.matcher().serie(serie)) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, t, schwelle, 3)) {
                if (h.centerX() >= x0 && h.centerX() <= x1 && h.centerY() >= y0 && h.centerY() <= y1) return true;
            }
        }
        return false;
    }

    private boolean patronageOffen(Mat screen, BotContext ctx) {
        // ar_schirm_titel (Arena, klein und weiss) trifft im grossen gelben Titel NICHT (Messung der Schwester-Sitzung).
        return ctx.visibleIfKnown(screen, T_ABILITIES) || ctx.visibleIfKnown(screen, T_PATRONAGE);
    }

    /**
     * Patronage: die aktiven Schirmherren links -- gezaehlt, eingeordnet und gepunktet wie beim Gegner ueber
     * bot/SchirmLeser (Nachtrag Autor 11.10.: dieselben Vorlagen ar_schirm_gut_*, ar_stigma_gut_*, dieselbe
     * Logik). Dazu die Level ("Lv. 3", "Lv. 9", "Lv. 5") per Texterkennung rechts vom "Lv.".
     */
    private void schirmeLesen(Mat screen, BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        List<String> fehlt = SchirmLeser.fehlt(ctx.matcher());
        if (!fehlt.isEmpty()) Log.info(WER, "Schirmherren: es fehlen Vorlagen " + fehlt + " -- gezaehlt wird, was geht.");
        SchirmLeser.Ergebnis e = SchirmLeser.lesenEigenes(screen, ctx);
        schirmErgebnis = e;
        schirmAnzahl = e.anzahl();
        guteSchirme = e.gute();
        guteStigmas = e.stigmas();
        SchirmLeser.protokoll("deines Teams", e);
        MeinTeam.schirmeSpeichern(e);   // gleich ablegen -- auch wenn der Rest des Scans noch schiefgeht
        // Die Level der aktiven (nur links): "Lv." grau wie im SchirmLeser, die Zahl rechts daneben.
        schirmLevelLesen(screen, bild, ctx, SchirmLeser.eigenerBereich(screen.width(), screen.height()));
        foto(bild, "patronage");
    }

    // ------------------------------------------------------------------
    //  Helfer
    // ------------------------------------------------------------------

    private static boolean inBurg(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT) || ctx.visibleIfKnown(screen, "fr_pokal")
                || ctx.visibleIfKnown(screen, "rs_level");
    }

    /**
     * Die Globus-Bilder 2 und 3 (Autor 11.10.) sitzen anders auf dem Globus als Bild 1 -- gemessen am selben Foto:
     * Mitte Bild 1 bei 573/397, Bild 2 bei 581/375, Bild 3 bei 576/385. Alle Stellen (Kaserne, Figuren) sind auf Bild 1
     * gerechnet; ohne Ausgleich tippte der Bot 22 Punkte zu hoch -- ueber die Koepfe auf die Kaserne, und die zoomte heran.
     */
    static Matcher.Hit wieGlobus1(Matcher.Hit h) {
        int dx = 0, dy = 0;
        if (h.name().endsWith("_2")) { dx = -8; dy = 22; }
        else if (h.name().endsWith("_3")) { dx = -3; dy = 12; }
        return new Matcher.Hit(T_GLOBUS, h.x() + dx, h.y() + dy, h.width(), h.height(), h.score());
    }

    static final String T_SPRECHBLASE = "ts_sprechblase";

    /**
     * Sprechblasen (Totenkopf, Gaehnen, Zzz, gruenes Gesicht ...) sind halb durchsichtig -- der Hintergrund schimmert
     * durch. Darum etwas lockerer als sonst (0,72) und alle Varianten ts_sprechblase_1, _2, ...
     */
    private static Optional<Matcher.Hit> blaseSuchen(Mat screen, BotContext ctx) {
        for (String name : ctx.matcher().names()) {
            if (!name.startsWith(T_SPRECHBLASE + "_")) continue;
            List<Matcher.Hit> h = ctx.matcher().findAll(screen, name, 0.72, 1);
            if (!h.isEmpty()) return Optional.of(h.get(0));
        }
        return Optional.empty();
    }
    static final String T_KASERNE_WAND = "ts_kaserne_wand";
    private int blasen;

    /**
     * Autor, 11.10.: "Der Globus dreht sich und kann verdeckt sein -- diese zwei Stellen (links und rechts in der Kaserne)
     * veraendern sich nicht; ein Tipp dort oeffnet die Kaserne genauso." Gefunden wird eine Wandstelle, daraus wird die
     * Lage des Globus gerechnet (gemessen 11.10.: links +348/-15, rechts -274/-17 zur Globus-Mitte), damit auch das
     * Antippen der Figuren stimmt.
     */
    private static Optional<Matcher.Hit> kaserneAnDerWand(Mat screen, BotContext ctx) {
        int[][] versatz = {{348, -15}, {-274, -17}};
        for (int i = 1; i <= 2; i++) {
            String name = T_KASERNE_WAND + "_" + i;
            if (!ctx.matcher().has(name)) continue;
            List<Matcher.Hit> h = ctx.matcher().findAll(screen, name, 0.72, 1);
            if (h.isEmpty()) continue;
            int gx = h.get(0).centerX() + versatz[i - 1][0], gy = h.get(0).centerY() + versatz[i - 1][1];
            Log.info(WER, "Globus nicht zu sehen -- Kaserne an der Wand erkannt (" + name + ").");
            return Optional.of(new Matcher.Hit(T_GLOBUS, gx - 32, gy - 21, 64, 42, h.get(0).score()));
        }
        return Optional.empty();
    }

    /** Die Vorlage im ganzen Bild suchen (findAll, ohne Sparregel). */
    private static Optional<Matcher.Hit> suchen(Mat screen, BotContext ctx, String vorlage) {
        // Autor, 11.10.: "Der Globus dreht sich, hat also leicht unterschiedliche Motive" -- darum auch die Varianten
        // vorlage_2, vorlage_3 ... (ts_kaserne_globus_2 usw.).
        for (String name : ctx.matcher().names()) {
            if (!name.equals(vorlage) && !name.startsWith(vorlage + "_")) continue;
            List<Matcher.Hit> h = ctx.matcher().findAll(screen, name, 0.80, 1);
            if (!h.isEmpty()) return Optional.of(h.get(0));
        }
        return Optional.empty();
    }

    /** Die Burg weiterscrollen. false, wenn alle Versuche verbraucht sind. */
    private boolean scrollen(BotContext ctx, String wonach) {
        if (scrolls >= SCROLL_MAX) return false;
        // Autor, 11.10.: "Kann der Bot auch nach links oder rechts scrollen, um den Globus zu finden?" -- erst
        // links, rechts, zurueck in die Mitte, danach wie bisher hoch und runter.
        int v = scrolls++;
        if (v < SEITWAERTS.length) {
            int xa = (int) (ctx.width() * 0.35), xb = (int) (ctx.width() * 0.65), ym = ctx.height() / 2;
            boolean links = SEITWAERTS[v] == 'L';
            Log.action("Ziehen", WER + ": Burg scrollen (weiter nach " + (links ? "links" : "rechts") + "), suche " + wonach);
            if (links) ctx.input().drag(xa, ym, xb, ym);
            else ctx.input().drag(xb, ym, xa, ym);
            ctx.sleepUntilChanged(900);
            ctx.sleep(300);
            return true;
        }
        int richtung = scrollRichtung(v - SEITWAERTS.length);
        int x = ctx.width() / 2, y1 = (int) (ctx.height() * 0.62), y2 = (int) (ctx.height() * 0.30);
        if (richtung < 0) { int t = y1; y1 = y2; y2 = t; }
        Log.action("Ziehen", WER + ": Burg scrollen (" + (richtung > 0 ? "weiter nach unten" : "weiter nach oben") + "), suche " + wonach);
        ctx.input().drag(x, y1, x, y2);
        ctx.sleepUntilChanged(900);
        ctx.sleep(300);
        return true;
    }

    private static void tippen(BotContext ctx, int x, int y, String grund) {
        x = Math.max(2, Math.min(ctx.width() - 3, x));
        y = Math.max(2, Math.min(ctx.height() - 3, y));
        Log.action("Klick", WER + ": " + grund + "  [" + x + "," + y + "]");
        ctx.input().click(x, y, 3);
        ctx.sleepUntilChanged(1200);
    }

    /** Ein rotes X druecken, wenn eins zu sehen ist. */
    private static boolean fensterZu(Mat screen, BotContext ctx) {
        for (String x : X_VORLAGEN) {
            if (ctx.clickIfVisibleIfKnown(screen, x, WER + ": Fenster schliessen (X)")) {
                ctx.sleepUntilChanged(1200);
                return true;
            }
        }
        return false;
    }

    private static void foto(BufferedImage bild, String was) {
        try {
            java.nio.file.Path ordner = java.nio.file.Path.of("logs", "team");
            String name = Zeitzone.jetzt().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss")) + "-" + was + ".png";
            de.hustlebot.core.Beleg.schreiben(bild, "png", ordner.resolve(name).toFile());
        } catch (Exception e) {
            Log.debug(WER, "Foto " + was + " nicht gespeichert: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Ergebnis
    // ------------------------------------------------------------------

    /** Scan zu Ende: Punkte rechnen, speichern. */
    /** "!team scan waffe": bei jeder Einheit auch die Waffe ansehen (nur fuer den naechsten Rundgang). */
    public static void waffeTestEinmal(boolean an) {
        ArenaPruefer.waffeImmerAnsehen = an;
    }

    /**
     * Gehoert der gelesene Spielername zum aktiven Konto? Ohne aktives Konto (Konten aus, Freunde mit einem Konto) oder
     * ohne lesbaren Namen laesst sich nichts pruefen -- dann wie bisher speichern.
     */
    static boolean kontoPasst(String aktiv, String gelesen) {
        if (aktiv == null || aktiv.isBlank() || gelesen == null || gelesen.isBlank()) return true;
        return de.hustlebot.core.ArenaVorlagenDoppelt.derselbeName(aktiv, gelesen);
    }

    private void beenden() {
        aktiv = false;
        ArenaPruefer.waffeImmerAnsehen = false;
        if (einheitenErgebnis == null && squadPower <= 0 && held == null && schirmAnzahl < 0) {
            Log.warn(WER, "Team-Scan ohne Ergebnis (" + String.join("; ", fehlgeschlagen) + ") -- in 3 Tagen noch einmal.");
            MeinTeam.versuchVermerken();
            abgebrochen = true;
            return;
        }
        if (einheiten.isEmpty()) {
            // 11.10.: ohne Einheiten kam "8 - 30 = -22" heraus und ueberschrieb den guten Stand. Lieber den alten behalten.
            Log.warn(WER, "Team-Scan ohne Einheiten (" + String.join("; ", fehlgeschlagen) + ") -- der alte Punktestand bleibt.");
            einheitenBilderSpeichern();
            MeinTeam.versuchVermerken();
            return;
        }
        // 29.10. (Arena-Pruefung H1): am 27.10. 16:25 lief der Rundgang im Zweitkonto "Zweitkonto A'" und
        // ueberschrieb arena-ich.txt, einheiten-auto.txt und die Einheiten-Bilder des AKTIVEN Kontos Autor (Messlatte 16
        // statt 86). Passt der gelesene Spielername nicht zum aktiven Konto, wird nichts gespeichert.
        if (!kontoPasst(de.hustlebot.core.Konten.aktiv(), name)) {
            Log.warn(WER, de.hustlebot.core.Sprache.t("Team scan in the game account \"" + name + "\", but the bot's active account is \""
                            + de.hustlebot.core.Konten.aktiv() + "\" -- nothing saved (it would overwrite the other account's team).",
                    "Team-Scan im Spielkonto \"" + name + "\", aktiv ist aber das Konto \"" + de.hustlebot.core.Konten.aktiv()
                            + "\" -- nichts gespeichert (sonst gehoerte dessen Team dem falschen Konto)."));
            MeinTeam.versuchVermerken();
            return;
        }
        Punkte p = punkte(einheitenErgebnis, allianzMaske, schirmAnzahl, guteSchirme, guteStigmas, held, gutePals, mehPals);
        MeinTeam.speichernScan(p.gesamt(), p.text(), name, squadPower, einheiten, held, schirmErgebnis, schirmLevel);
        einheitenBilderSpeichern();
        if (!fehlgeschlagen.isEmpty()) {
            Log.warn(WER, "Team-Scan mit Luecken gespeichert -- uebersprungen: " + String.join("; ", fehlgeschlagen));
        }
    }

    /** Die Punkte des eigenen Teams, zerlegt wie bei Gegnern. */
    record Punkte(int einheiten, int steine, int allianz, int schirme, int held, int pals, String text) {
        int gesamt() { return einheiten + steine + allianz + schirme + held + pals; }
    }

    /**
     * Dasselbe Punktesystem wie fuer Gegner (Autor 11.10.): Sets, Artefakte, Bauarbeiter, fehlende
     * Einheiten (Gegnerbuch.teamPunkte), Steine, Kraft je Einheit (+1 ab 6 Mio / +5 ab 8 Mio) und
     * Embleme (stecken im setPunkte des ArenaPruefers), Allianz +8, Schirmherren (+1 je, +3 je gute,
     * +2 je gutes Stigma), Held (+12 gute Haupt-, +4 je gute Nebenfaehigkeit). Ohne Bonus.
     */
    static Punkte punkte(ArenaPruefer.Ergebnis e, int allianzMaske, int schirmAnzahl, int guteSchirme, int guteStigmas,
                         MeinTeam.Held held, int gutePals) {
        return punkte(e, allianzMaske, schirmAnzahl, guteSchirme, guteStigmas, held, gutePals, 0);
    }

    static Punkte punkte(ArenaPruefer.Ergebnis e, int allianzMaske, int schirmAnzahl, int guteSchirme, int guteStigmas,
                         MeinTeam.Held held, int gutePals, int mehPals) {
        int einheiten = 0, steine = 0;
        StringBuilder t = new StringBuilder();
        if (e != null && e.einheiten() > 0) {
            einheiten = Gegnerbuch.teamPunkte(e.setPunkte(), e.guteArtefakte(), e.bauarbeiter(), e.einheiten());
            steine = Gegnerbuch.steinPunkte(e.gelbeSteine(), e.roteSteine());
            t.append("Einheiten ").append(e.einheiten()).append(", Sets/Kraft/Embleme ").append(e.setPunkte())
             .append(" [").append(e.sets()).append("], gute Artefakte ").append(e.guteArtefakte())
             .append(", Bauarbeiter ").append(e.bauarbeiter()).append(" = ").append(einheiten)
             .append(", Steine ").append(e.gelbeSteine()).append(" gelb / ").append(e.roteSteine()).append(" rot +").append(steine);
        } else {
            t.append("Einheiten nicht angesehen");
        }
        int allianz = ArenaPruefer.allianzVoll(allianzMaske) ? 8 : 0;
        if (allianzMaske != 0) t.append(", Allianz ").append(ArenaPruefer.allianzText(allianzMaske)).append(allianz > 0 ? " +8" : " (nicht alle vier)");
        int schirme = schirmAnzahl >= 0 ? Gegnerbuch.schirmPunkte(schirmAnzahl, guteSchirme, guteStigmas) : 0;
        if (schirmAnzahl >= 0) t.append(", Schirmherren ").append(schirmAnzahl).append(" (").append(guteSchirme).append(" gute, ")
                .append(guteStigmas).append(" Stigma) +").append(schirme);
        int h = MeinTeam.heldPunkte(held);
        if (held != null) t.append(", Held ").append(held.name()).append(" +").append(h);
        // Autor 15.10.: Pals wie bei Gegnern (+4 je guten Pal).
        // +4 je guten Pal, +0,5 je Meh-Pal (Autor 15.10.); die Summe ist eine ganze Zahl, 0,5 wird aufgerundet.
        int pals = gutePals >= 0 ? (int) Math.round(Gegnerbuch.palPunkte(gutePals) + 0.5 * Math.max(0, mehPals)) : 0;
        if (gutePals >= 0) t.append(", gute Pals ").append(gutePals).append(mehPals > 0 ? ", meh " + mehPals : "").append(" +").append(pals);
        return new Punkte(einheiten, steine, allianz, schirme, h, pals, t.toString());
    }
}
