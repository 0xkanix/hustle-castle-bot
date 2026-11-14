package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Der Notizblock ueber die Arena-Gegner.
 *
 * ------------------------------------------------------------------
 *  Warum es das gibt
 * ------------------------------------------------------------------
 *
 * Autor wollte von Anfang an keinen Bot, der stur eine Liste abarbeitet:
 *
 *     "geht das das der bot ueber alle arenen hinaus immer wie einen
 *      notizblock im hintergrund hat, wo auf sich die namen der spieler
 *      kurz merkt und notiert. gewonnen oder nicht ... das der bot auch
 *      selber mitrechnet, mitdenkt"
 *
 * Genau das steht hier. Nach jedem Kampf kommt eine Zeile dazu, und beim
 * naechsten Turnier schlaegt der Bot nach, bevor er sich entscheidet.
 *
 * ------------------------------------------------------------------
 *  Zwei Regeln, die Autor selbst festgelegt hat
 * ------------------------------------------------------------------
 *
 * ERST AB DREI KAEMPFEN zaehlt die Bilanz. Eine einzelne Niederlage kann
 * Pech gewesen sein -- "1 mal ist keinmal". Vorher gilt der Gegner als
 * unbekannt, und es entscheiden die anderen Merkmale.
 *
 * NUR DIE LETZTEN ZEHN KAEMPFE zaehlen. Leute werden staerker; wen man
 * im Januar nicht geschlagen hat, schafft man im September vielleicht.
 * Deshalb wird nicht mitgezaehlt, sondern eine Kette gefuehrt: "NNSNS",
 * neueste rechts. Faellt vorn etwas raus, ist es vergessen.
 *
 * ------------------------------------------------------------------
 *  Warum eine schlichte Textdatei
 * ------------------------------------------------------------------
 *
 * Ein Datenformat, das nur der Bot lesen kann, waere fuer Autor eine
 * schwarze Kiste -- und er kann nicht programmieren, um hineinzusehen.
 * Seit 25.09. sind es zwei Dateien: arena-notizblock.txt zum Lesen (im
 * Editor, im Notizblock-Fenster, per !notizen), wird nach jeder Aenderung
 * NEU GESCHRIEBEN -- wer dort etwas aendert, verliert es. Berichtigen geht
 * in arena-daten.txt (eine Zeile je Gegner, Spalten durch Tabulator), am
 * besten, waehrend der Bot aus ist.
 */
public final class Gegnerbuch {

    /**
     * Zwei Dateien seit 25.09.: Autor wollte den Notizblock lesbar, je
     * Gegner ein kleiner Block ("-> Rina (1/0)", darunter Zeit und Punkte).
     * Die Tabelle, aus der der Bot liest, steht deshalb in arena-daten.txt;
     * arena-notizblock.txt wird nach jeder Aenderung daraus neu geschrieben.
     * Wer im Notizblock etwas aendert, verliert es beim naechsten Kampf --
     * berichtigen geht in arena-daten.txt.
     */
    static final Path DATEI = Path.of("arena-daten.txt");
    public static final Path NOTIZBLOCK = Path.of("arena-notizblock.txt");

    /** Ab so vielen Kaempfen wird der Bilanz geglaubt. */
    public static final int GENUG_KAEMPFE = 3;

    /** So viele Kaempfe je Gegner bleiben in Erinnerung. */
    public static final int MERKT_SICH = 10;

    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Was ueber einen Gegner bekannt ist.
     *
     * @param verlauf Kette aus S und N, neueste rechts. Daraus ergeben sich
     *                Zahl der Kaempfe und Siege -- getrennt mitzuzaehlen
     *                waere eine zweite Wahrheit, die irgendwann abweicht.
     */
    public record Notiz(String name, String clan, String verlauf,
                        long staerke, int artefakte, String zuletzt,
                        int setPunkte, String sets, String geprueft, String dauern,
                        int einheiten, int bauarbeiter, String punkte, String notiz,
                        String steine, String schirm, String pals,
                        String fingerabdruecke, String schrift) {

        /**
         * Autor 01.11.: der NAME dieses Eintrags ist der eigene Clan oder ein Stueck davon ("V Myth Gods" / Clan "O'Myth Go s") -- die Clan-Zeile wurde als
         * Name gelesen, und unter ihm liefen mehrere Spieler desselben Clans zusammen (Bilanz und Kraft gelten dann fuer jeden aus dem Clan, ein Clan hat
         * keine Kampfkraft). Solche Eintraege bleiben in der Datei, zaehlen aber nicht mehr fuer Bilanz und Kraft.
         */
        public boolean nameIstClan() {
            return Gegnerbuch.nameIstClan(name, clan);
        }

        /** Kurzform fuer die alten Stellen, die von Sets noch nichts wissen. */
        public Notiz(String name, String clan, String verlauf,
                     long staerke, int artefakte, String zuletzt) {
            this(name, clan, verlauf, staerke, artefakte, zuletzt, -1, "", "", "", -1, -1, "", "", "", "", "", "", "");
        }

        /**
         * Ohne Namensbild (Stand vor dem 24.10.). Autor 24.10.: fingerabdruecke = Fingerabdruecke des Namensbildes
         * (Glyphenschrift, "fp|fp|...", neueste zuerst), schrift = der Name aus der eigenen Schrifterkennung zum neuesten
         * Fingerabdruck ("?" = Zeichen noch nicht bekannt).
         */
        public Notiz(String name, String clan, String verlauf,
                     long staerke, int artefakte, String zuletzt,
                     int setPunkte, String sets, String geprueft, String dauern,
                     int einheiten, int bauarbeiter, String punkte, String notiz,
                     String steine, String schirm, String pals) {
            this(name, clan, verlauf, staerke, artefakte, zuletzt, setPunkte, sets, geprueft, dauern, einheiten,
                    bauarbeiter, punkte, notiz, steine, schirm, pals, "", "");
        }

        /** Dieselbe Notiz mit anderem Namensbild (Fingerabdruecke und Schrift). */
        Notiz mitNamensbild(String fps, String schrift) {
            return new Notiz(name, clan, verlauf, staerke, artefakte, zuletzt, setPunkte, sets, geprueft, dauern,
                    einheiten, bauarbeiter, punkte, notiz, steine, schirm, pals, fps == null ? "" : fps,
                    schrift == null ? "" : schrift);
        }

        /** Dieselbe Notiz mit anderem Verlauf (Autor 28.10., ArenaZusammenfuehren). */
        Notiz mitVerlauf(String v) {
            return new Notiz(name, clan, v, staerke, artefakte, zuletzt, setPunkte, sets, geprueft, dauern, einheiten,
                    bauarbeiter, punkte, notiz, steine, schirm, pals, fingerabdruecke, schrift);
        }

        /**
         * Schirmherrschaften (Autor, 01.10.): "anzahl/gute/stigmas", leer = nie angesehen.
         * Punkte: +1 je Schirmherrschaft, +3 je guter, +2 je gutem Stigma.
         */
        public int schirmAnzahl() { return teil(schirm, 0); }
        public int guteSchirme()  { return teil(schirm, 1); }
        public int guteStigmas()  { return teil(schirm, 2); }
        public boolean schirmBekannt() { return schirm != null && !schirm.isBlank(); }

        /** Gute Pals (Autor, 01.10.): +5 je Stueck. Leer = nie angesehen. */
        public int gutePals() { return teil(pals, 0); }
        public boolean palsBekannt() { return pals != null && !pals.isBlank(); }

        private static int teil(String s, int i) {
            if (s == null || s.isBlank()) return 0;
            String[] t = s.split("/");
            try { return t.length > i ? Integer.parseInt(t[i].trim()) : 0; } catch (NumberFormatException e) { return 0; }
        }

        /** Steine auf den Gegenstaenden (Autor, 30.09.): "gelb/rot", leer = nie gezaehlt. */
        public int gelbeSteine() { return steinTeil(0); }
        public int roteSteine()  { return steinTeil(1); }

        private int steinTeil(int i) {
            if (steine == null || steine.isBlank()) return 0;
            String[] t = steine.split("/");
            try { return t.length > i ? Integer.parseInt(t[i].trim()) : 0; } catch (NumberFormatException e) { return 0; }
        }

        /**
         * Was ein Kampf gegen diesen Gegner im Schnitt bringt: Punkte je Sieg
         * (letzte fuenf lesbare) mal Siegquote. -1 = unbekannt.
         */
        public int mittlerePunkte() {
            if (kaempfe() == 0) return -1;
            if (siege() == 0) return 0;
            int jeSieg = mittel(punkte);
            return jeSieg < 0 ? -1 : (int) Math.round(jeSieg * (double) siege() / kaempfe());
        }

        /**
         * Unsere Bewertung ihres Teams: Sets + Artefakte + Bauarbeiter +
         * fehlende Einheiten, ohne Kampfkraft. -1 = noch nicht geprueft.
         */
        public int teamPunkte() {
            if (setPunkte < 0) return -1;
            return Gegnerbuch.teamPunkte(setPunkte, Math.max(0, artefakte),
                    Math.max(0, bauarbeiter), einheiten)
                    + Gegnerbuch.steinPunkte(gelbeSteine(), roteSteine())   // wie beim Waehlen (01.10.)
                    + Gegnerbuch.schirmPunkte(schirmAnzahl(), guteSchirme(), guteStigmas())
                    + Gegnerbuch.palPunkte(gutePals());
        }

        /** Wurden Sets und Artefakte in den letzten {@code tage} Tagen angesehen? */
        public boolean geprueftSeit(int tage) {
            if (geprueft == null || geprueft.isBlank()) return false;
            try {
                return LocalDateTime.parse(geprueft, STEMPEL)
                        .isAfter(LocalDateTime.now().minusDays(tage));
            } catch (Exception e) {
                return false;
            }
        }

        /** Mittlere Kampfdauer in Sekunden, oder -1. */
        public int mittlereDauer() { return mittel(dauern); }

        /** Die Dauer der letzten hoechstens {@code anzahl} Kaempfe gegen ihn, ohne Lesefehler (Autor 30.10.). */
        public List<Integer> letzteDauern(int anzahl) {
            List<Integer> alle = new ArrayList<>();
            if (dauern == null || dauern.isBlank()) return alle;
            for (String d : dauern.trim().split("\\s+")) {
                try {
                    int v = Integer.parseInt(d);
                    if (v <= MAX_KAMPFZEIT_S) alle.add(v);
                } catch (NumberFormatException ignored) { }
            }
            return new ArrayList<>(alle.subList(Math.max(0, alle.size() - anzahl), alle.size()));
        }

        /** Dauer des letzten Kampfes gegen ihn in Sekunden, -1 = unbekannt (Idee 16, 22.10.; Lesefehler ueber 40 s zaehlen nicht). */
        public int letzteDauer() {
            if (dauern == null || dauern.isBlank()) return -1;
            String[] d = dauern.trim().split("\\s+");
            try {
                int v = Integer.parseInt(d[d.length - 1]);
                return v > MAX_KAMPFZEIT_S ? -1 : v;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** Niederlagen im gemerkten Verlauf. */
        public int niederlagen() { return kaempfe() - siege(); }

        public int kaempfe() { return verlauf.length(); }

        public int siege() {
            int n = 0;
            for (int i = 0; i < verlauf.length(); i++) {
                if (verlauf.charAt(i) == 'S') n++;
            }
            return n;
        }

        /** Anteil der Siege, oder -1 wenn es noch zu wenige Kaempfe sind. */
        /**
         * Siegquote, -1 unter drei Kaempfen. Idee 1 (A18, 22.10.): die letzten zwei Kaempfe zaehlen doppelt -- Myth Gods
         * ging S S N N N und blieb lange "gut", weil die alten Siege genauso viel wogen wie die neuen Niederlagen.
         */
        public double quote() {
            if (kaempfe() < GENUG_KAEMPFE) return -1;
            double s = 0, w = 0;
            for (int i = 0; i < verlauf.length(); i++) {
                double g = i >= verlauf.length() - 2 ? 2 : 1;
                w += g;
                if (verlauf.charAt(i) == 'S') s += g;
            }
            return s / w;
        }

        /** Der letzte Kampf gegen ihn: 'S', 'N' oder 0 ohne Kampf. */
        public char letzterKampf() {
            return verlauf.isEmpty() ? 0 : verlauf.charAt(verlauf.length() - 1);
        }

        public boolean aussagekraeftig() { return kaempfe() >= GENUG_KAEMPFE; }
    }

    /** Mittelwert einer Zahlenkette wie "17 21 30", oder -1. */
    private static int mittel(String kette) {
        if (kette == null || kette.isBlank()) return -1;
        int summe = 0, n = 0;
        for (String d : kette.trim().split("\\s+")) {
            try {
                int v = Integer.parseInt(d);
                // Idee 16 (A50, 22.10.): "179 s" und "43 s" waren Lesefehler (die Punkte passten zu 20 und 12 s) --
                // sie zogen "Утка в тапка" bis zu 30 Punkte ab. Laenger als 40 s dauert ein Arena-Kampf nicht.
                if (v > MAX_KAMPFZEIT_S) continue;
                summe += v;
                n++;
            } catch (NumberFormatException ignored) { }
        }
        return n == 0 ? -1 : summe / n;
    }

    /** Haengt eine Zahl an die Kette und behaelt nur die letzten fuenf. */
    /** Laenger dauert ein Arena-Kampf nicht -- mehr ist verlesen (Idee 16, 22.10.). */
    public static final int MAX_KAMPFZEIT_S = 120;   // Autor 31.10.: "Ein Arena-Kampf kann 2 Minuten lang gehen" (vorher 40)

    private static String anhaengen(String kette, long wert) {
        if (wert <= 0) return kette == null ? "" : kette;
        String neu = ((kette == null ? "" : kette) + " " + wert).trim();
        String[] d = neu.split("\\s+");
        return d.length > MERKT_SICH ? String.join(" ", Arrays.copyOfRange(d, d.length - MERKT_SICH, d.length)) : neu;   // 08.10.: 10 statt 5 (Durchschnitt)
    }

    /**
     * Die Punkte-Formel fuer ein Team (24./25.09.): Sets zaehlen je Einheit
     * nach Tier, jedes gute Artefakt +3, jeder Bauarbeiter +5, jede fehlende
     * Einheit unter sechs +5. Hoch = starkes Team.
     */
    public static int teamPunkte(int sets, int artefakte, int bauarbeiter, int einheiten) {
        // Autor 22.10.: "Wenn ein Spieler weniger Einheiten hat als ich (Bauarbeiter sind auch Einheiten), ist das meistens
        // ein Zeichen, dass sie stark sind -- fuer jede Einheit, die sie weniger haben, +12 Punkte." (Vorher +5 je Einheit
        // unter sechs.) Verglichen wird mit deinem Arena-Team (eigene Pruefung in Runde 1), sonst mit 6.
        int fehlend = einheiten > 0 ? Math.max(0, meineEinheiten - einheiten) : 0;
        return sets + 3 * artefakte + 5 * bauarbeiter + JE_FEHLENDE_EINHEIT * fehlend;
    }

    /** Punkte je Einheit, die ein Gegner weniger hat als du (Autor 22.10.). */
    public static final int JE_FEHLENDE_EINHEIT = 12;
    /** Einheiten deines Arena-Teams (Bauarbeiter zaehlen mit); 6, bis die eigene Pruefung sie gezaehlt hat. */
    private static volatile int meineEinheiten = 6;

    public static void setMeineEinheiten(int n) {
        if (n >= 1 && n <= 8) meineEinheiten = n;
    }

    public static int meineEinheiten() {
        return meineEinheiten;
    }

    /**
     * Steine auf den Gegenstaenden (Autor, 30.09.): je gelbem Stein +0,5, je rotem +0,1 --
     * gerechnet in Zehnteln, fuer die Punkte auf ganze gerundet (36 rote = 3,6 = 4 Punkte).
     */
    public static int steinZehntel(int gelb, int rot) {
        return 5 * Math.max(0, gelb) + Math.max(0, rot);
    }

    public static int steinPunkte(int gelb, int rot) {
        return (int) Math.round(steinZehntel(gelb, rot) / 10.0);
    }

    /**
     * Schirmherrschaften (Autor, 01.10.): +1 je Schirmherrschaft, +3 je guter, +2 je gutem Stigma.
     * Was in arena-extras.txt ausgeschaltet ist, zaehlt 0 (Autors Testlaeufe).
     */
    public static int schirmPunkte(int anzahl, int gute, int stigmas) {
        int p = 0;
        if (ArenaExtras.schirm()) p += Math.max(0, anzahl) + 3 * Math.max(0, gute);
        if (ArenaExtras.stigma()) p += 2 * Math.max(0, stigmas);
        return p;
    }

    /** Autors zuletzt gelesene Kampfkraft -- fuer den Kraft-Hinweis im Notizblock (08.10.). */
    private static volatile long meineKraft = -1;

    public static void setMeineKraft(long kraft) {
        if (kraft > 0) meineKraft = kraft;
    }

    /** Ab so viel mehr Kraft gibt es Extrapunkte: 20 Mio +10, 30 Mio +20, 40 Mio +50. */
    private static final long RIESEN_UNTERSCHIED = 20_000_000L;

    /**
     * Kraft-Punkte eines Gegners: je Million mehr als Autor +0,5, je Million weniger -0,5 (Autor,
     * 08.10.; vorher je 1). Dazu ab 20 Mio mehr +10, ab 30 Mio +20, ab 40 Mio +50 -- "diese Gegner
     * sind eigentlich fast unmoeglich zu besiegen". 0, wenn eine der beiden Kraefte fehlt.
     */
    public static int kraftPunkte(long seine, long meine) {
        if (seine <= 0 || meine <= 0) return 0;
        long mehr = seine - meine;
        int punkte = (int) Math.round(mehr / 2_000_000.0);
        if (mehr >= RIESEN_UNTERSCHIED * 2) punkte += 50;
        else if (mehr >= 30_000_000L) punkte += 20;
        else if (mehr >= RIESEN_UNTERSCHIED) punkte += 10;
        return punkte;
    }

    /** "Kraft-Unterschied -18,5 Mio = -9" -- leer, wenn eine Kraft fehlt. */
    public static String kraftHinweis(long seine, long meine) {
        if (seine <= 0 || meine <= 0) return "";
        long mehr = seine - meine;
        int p = kraftPunkte(seine, meine);
        return Sprache.istEnglisch()
                ? String.format(java.util.Locale.ENGLISH, "power difference %+.1f M = %+d", mehr / 1_000_000.0, p)
                : String.format(java.util.Locale.GERMANY, "Kraft-Unterschied %+.1f Mio = %+d", mehr / 1_000_000.0, p);
    }

    /** Gute Pals (Autor, 01.10.): je Stueck +8 (seit 08.10., vorher +5); normale Pals zaehlen nichts. */
    public static int palPunkte(int gute) {
        return ArenaExtras.pals() ? 8 * Math.max(0, gute) : 0;
    }

    /** "3,6" aus Zehnteln. */
    public static String zehntelText(int zehntel) {
        return (zehntel / 10) + "," + (zehntel % 10);
    }

    private final Map<String, Notiz> notizen = new LinkedHashMap<>();
    /** Zusammengelegte Eintraege (Autor 24.10.): alter Schluessel -> der, der blieb -- damit die alte Lesart nicht neu anlegt. */
    private final Map<String, String> umgezogen = new java.util.HashMap<>();
    /** Die beiden Dateien (im Bot arena-daten.txt und arena-notizblock.txt; Tests nehmen eigene). */
    private final Path datei, notizblock;
    /** Glyphen-Bibliothek fuer das Namensbild (null = die im Bot-Ordner, erst beim ersten Gebrauch). */
    private Glyphenschrift glyphen;

    public Gegnerbuch() {
        this(DATEI, NOTIZBLOCK, null);
    }

    /** Fuer Tests: eigene Dateien und eigene Glyphen-Bibliothek (null = die im Bot-Ordner). */
    Gegnerbuch(Path datei, Path notizblock, Glyphenschrift glyphen) {
        this.datei = datei;
        this.notizblock = notizblock;
        this.glyphen = glyphen;
        laden();
    }

    private static Gegnerbuch gemeinsam;

    /**
     * Der eine Notizblock fuer Arena UND Discord (!notiz). Zwei eigene
     * Exemplare wuerden sich gegenseitig ueberschreiben: jedes haelt seinen
     * Stand im Speicher und schreibt beim Speichern die ganze Datei.
     */
    public static synchronized Gegnerbuch gemeinsam() {
        if (gemeinsam == null) gemeinsam = new Gegnerbuch();
        return gemeinsam;
    }

    // ==================================================================
    //  Nachschlagen und eintragen
    // ==================================================================

    /**
     * Der Schluessel ist Name UND Clan.
     *
     * Namen sind im Spiel nicht eindeutig -- zwei verschiedene "Rina"
     * wuerden sonst in einen Topf fallen und sich gegenseitig die Bilanz
     * verderben.
     */
    /**
     * "Not in clan" in allen Lesarten der Texterkennung: "Not", "Not i", "VN0t i",
     * "V Not i", "Ue Not", auch als Namensanfang ("Not iB4an"). Solche Spieler werden
     * nicht gemerkt (Autor, 30.09.) -- sie kommen nie wieder, das waere totes Gewicht.
     */
    public static boolean keinClan(String text) {
        if (text == null || text.isBlank()) return false;
        String c = text.toLowerCase(java.util.Locale.ROOT).replace('0', 'o').replaceAll("[^a-z]", "");
        // Messung 30.10.: "IVNot", "ot in clan", "j V Not in clan", "CONot in clan" standen als Clan im Buch (8 von 162).
        return c.matches("^[a-z]{0,2}not(i[a-z]*)?$") || c.matches("^[a-z]{0,3}n?otinclan$");
    }

    /** Clan-Eintrag fuer echte Spieler ohne Clan (Autor, 08.10.). */
    public static final String OHNE_CLAN = "ohne Clan";

    public static String schluessel(String name, String clan) {
        return sauber(name).toLowerCase() + "|" + sauber(clan).toLowerCase();
    }

    /**
     * Idee 5 (A23, 22.10.): der Eintrag mit genau dieser Kampfkraft (ab 1 Mio, auf die Einerstelle) -- fuer Gegner, deren
     * Name diesmal anders gelesen wurde. Mehrere: der zuletzt gepruefte. Autor: "Wenn Namen falsch erkannt werden, ist es
     * ja nicht schlimm -- du hast ja noch die Power."
     */
    public synchronized Optional<Notiz> nachKraft(long kraft) {
        if (kraft < 1_000_000) return Optional.empty();
        Notiz beste = null;
        for (Notiz n : notizen.values()) {
            if (n.staerke() != kraft || n.setPunkte() < 0) continue;
            if (beste == null || String.valueOf(n.geprueft()).compareTo(String.valueOf(beste.geprueft())) > 0) beste = n;
        }
        return Optional.ofNullable(beste);
    }

    /**
     * Pruefung 29.10.: 26 von 716 Erkundungen trugen als NAMEN die Clanzeile eines anderen Spielers ("Анатолий." =
     * Clan von ebÖ, "響蛎茶多" = Clan von Xenos) -- der Name selbst war unlesbar, uebrig blieb nur die zweite Zeile.
     * Ist der Text als Clan eines bekannten Spielers im Notizblock und nirgends als Spielername, ist er ein Clan.
     */
    /**
     * Ist dieser "Name" der Clan selbst oder ein Stueck davon? Gleicher Clan (mit der Nachsicht der Clan-Vergleichs: ein Zeichen davor/dahinter),
     * oder der Name (ab 4 Zeichen, ohne Leerzeichen/Zeichen dazwischen) steckt bis auf einen Lesefehler in der Clan-Zeile.
     * Beispiele aus dem Notizblock: "V Myth Gods" / "O'Myth Go s", "Gods" / "O'Myth Go s", "Mythŕds" / "Myth Gods".
     */
    public static boolean nameIstClan(String name, String clan) {
        if (name == null || clan == null || name.isBlank() || clan.isBlank()) return false;
        // Wird bei jeder Bilanz fuer jeden Eintrag gefragt: je Paar nur einmal rechnen.
        return CLAN_NAME_GEMERKT.computeIfAbsent(name + "\u0001" + clan, k -> nameIstClanRechnen(name, clan));
    }

    private static final java.util.Map<String, Boolean> CLAN_NAME_GEMERKT = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean nameIstClanRechnen(String name, String clan) {
        if (keinClan(clan) || clan.strip().equalsIgnoreCase(OHNE_CLAN)) return false;
        String n = nurZeichen(name), c = nurZeichen(clan);
        if (n.length() < 3 || c.length() < 3) return false;
        // Autor 01.11.: "Flimsy heisst wirklich so, das ist kein Clan-Name." Name und Clan gleich heisst: der Clan wurde nicht
        // gelesen und traegt den Namen (FLIMSY, Litos, gruul, Sveta ...). Ein Clan-Stueck als Name ist dagegen anders geschrieben.
        if (n.equals(c)) return false;
        if (NamensVergleich.gleicherClan(name, clan) || clanAehnlich(name, clan)) return true;
        return n.length() >= 4 && c.length() >= n.length() - 1 && stecktMitEinemFehler(n, c);
    }

    /** Steckt {@code kurz} bis auf eine Abweichung (ein Zeichen anders, zu viel oder zu wenig) als Stueck in {@code lang}? */
    private static boolean stecktMitEinemFehler(String kurz, String lang) {
        if (lang.contains(kurz)) return true;
        for (int laenge = kurz.length() - 1; laenge <= kurz.length() + 1; laenge++) {
            if (laenge < 3 || laenge > lang.length()) continue;
            for (int i = 0; i + laenge <= lang.length(); i++) {
                if (NamensVergleich.abstandBis(kurz, lang.substring(i, i + laenge), 1) <= 1) return true;
            }
        }
        return false;
    }

    /**
     * Autor 01.11.: ist das als Name glaubhaft -- und damit gut genug, einen Platzhalter ("Bild-1024-032130") oder eine andere Lesart zu ersetzen?
     * Mindestens zwei Buchstaben/Ziffern; nur Schriften, in denen Spieler heissen (lateinisch, kyrillisch, chinesisch/japanisch/koreanisch -- aber
     * nicht Chinesisch UND Koreanisch gemischt, nicht arabisch); keine Clan-Endung wie 联盟 (Allianz). Misslungene Lesungen wie "冕괴困", "نسعع" oder
     * "元奏雄联盟" (Clan-Zeile als Name) sind es nicht und ueberschreiben keinen Namen.
     */
    public static boolean plausiblerName(String name) {
        if (name == null) return false;
        String s = name.strip();
        int zeichen = 0;
        boolean han = false, hangul = false, andere = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (!Character.isLetterOrDigit(c)) continue;
            zeichen++;
            if (!Character.isLetter(c)) continue;
            switch (Character.UnicodeScript.of(c)) {
                case LATIN, CYRILLIC, HIRAGANA, KATAKANA, COMMON, INHERITED -> { }
                case HAN -> han = true;
                case HANGUL -> hangul = true;
                default -> andere = true;
            }
        }
        if (zeichen < 2 || andere || (han && hangul)) return false;
        for (String ende : new String[]{"联盟", "聯盟", "公会", "公會", "军团", "軍團", "战队", "戰隊"}) {
            if (s.endsWith(ende)) return false;
        }
        return true;
    }

    /**
     * Autor 01.11.: koennen zwei gelesene Namen derselbe Spieler sein? Nur wenn einer von beiden nichts sagt (unter 3 Zeichen) oder sie sich aehneln
     * (ein Name steckt im anderen, gleiche Form, oder drei zusammenhaengende Zeichen gemeinsam). Gleiche Kraft oder gleicher Clan allein reichen nicht:
     * "~ЦИРИ~" und "V Myth Gods" (Clan-Zeile) waren nie derselbe Name.
     */
    public static boolean namenVertraeglich(String a, String b) {
        String x = nurZeichen(a), y = nurZeichen(b);
        if (x.length() < 3 || y.length() < 3) return true;
        if (x.equals(y) || x.contains(y) || y.contains(x)) return true;
        if (NamensVergleich.gleicheForm(x, y, 0.6)) return true;
        String kurz = x.length() <= y.length() ? x : y, lang = kurz == x ? y : x;
        for (int i = 0; i + 3 <= kurz.length(); i++) if (lang.contains(kurz.substring(i, i + 3))) return true;
        return false;
    }

    public synchronized boolean nurAlsClanBekannt(String text) {
        if (text == null || text.isBlank()) return false;
        int alsName = 0, alsClan = 0;
        for (Notiz n : notizen.values()) {
            if (NamensVergleich.gleicherName(text, n.name()) && !n.nameIstClan()) alsName++;   // Autor 01.11.: Eintraege mit Clan als Namen sind keine Spieler
            if (n.clan() != null && !n.clan().isBlank() && !keinClan(n.clan())
                    && !n.clan().strip().equalsIgnoreCase(OHNE_CLAN) && NamensVergleich.gleicherClan(text, n.clan())) {
                alsClan++;
            }
        }
        if (alsName == 0) return alsClan > 0;
        // Messung 30.10.: "Myth Gods" stand einmal als Name im Buch (eigentlich zevs), ab da galt der Clan als Spielername. Ein
        // einziger solcher Eintrag zaehlt nicht gegen zwei Spieler, die wirklich in diesem Clan sind.
        return ArenaRegelnNeu.an() && alsName == 1 && alsClan >= 2;
    }

    public synchronized Optional<Notiz> nachschlagen(String name, String clan) {
        if (name == null || name.isBlank()) return Optional.empty();
        String[] kn = kanonisch(name, clan);
        return Optional.ofNullable(notizen.get(schluessel(kn[0], kn[1]))).filter(n -> !n.nameIstClan());
    }

    /**
     * Wie oben, mit dem Fingerabdruck des Namensbildes (Glyphenschrift, Autor 24.10.): derselbe Spieler unter einem
     * anders gelesenen Namen findet dieselbe Notiz -- auch wenn Windows den Namen gar nicht lesen konnte.
     */
    public synchronized Optional<Notiz> nachschlagen(String name, String clan, String fingerabdruck) {
        return nachschlagen(name, clan, -1, fingerabdruck);
    }

    /** Wie oben, dazu die Kampfkraft (hilft, wenn der Clan anders gelesen wurde; -1 = unbekannt). */
    public synchronized Optional<Notiz> nachschlagen(String name, String clan, long staerke, String fingerabdruck) {
        if ((name == null || name.isBlank()) && ArenaSperre.glyphenAnzahl(fingerabdruck) == 0) return Optional.empty();
        String[] kn = kanonisch(name, clan, staerke, fingerabdruck);
        if (kn[0] == null || kn[0].isBlank()) return Optional.empty();
        return Optional.ofNullable(notizen.get(schluessel(kn[0], kn[1]))).filter(n -> !n.nameIstClan());
    }

    /** Wie viele Eintraege tragen den Clan (oder ein Stueck davon) als Namen? Fuer die Messung am Altbestand; geloescht wird nichts. */
    public synchronized List<Notiz> clanAlsName() {
        List<Notiz> l = new ArrayList<>();
        for (Notiz n : notizen.values()) if (n.nameIstClan()) l.add(n);
        return l;
    }

    /**
     * Autor 24.10.: unter welchem Namen steht dieser Spieler im Notizblock? Mit dem Namensbild (Ergebnis der
     * Glyphenschrift) auch dann, wenn Windows den Namen diesmal anders (oder gar nicht) gelesen hat. Steht er schon drin,
     * wird der Fingerabdruck dazugemerkt (gespeichert mit dem naechsten jetztSpeichern() oder Kampf). Liefert
     * {Name, Clan} -- mit diesen Werten finden alle anderen Methoden (nachschlagen, merkePruefung ...) dieselbe Notiz.
     * Unbekannter Spieler: der gelesene Name (leer gelesen und alle Zeichen bekannt: der Name aus der eigenen Schrift).
     */
    public synchronized String[] wiedererkennen(String name, String clan, long staerke, Glyphenschrift.Ergebnis namensbild) {
        String fp = namensbild == null ? null : namensbild.fingerabdruck();
        String gelesen = name == null || name.isBlank() ? schriftName(namensbild) : name;
        String c = clan == null ? "" : clan;
        if (keinClan(c) && gelesen != null && NamensVergleich.siehtMenschlichAus(gelesen)) c = OHNE_CLAN;
        String[] kn = kanonisch(gelesen, c, staerke, fp);
        if (kn[0] == null || kn[0].isBlank()) return new String[]{name == null ? "" : name, clan == null ? "" : clan};
        String key = schluessel(kn[0], kn[1]);
        Notiz n = notizen.get(key);
        if (n != null) {
            Notiz neu = mitNamensbild(n, namensbild);
            if (!neu.equals(n)) {
                notizen.put(key, neu);
                offen = true;
            }
        }
        return kn;
    }

    /** Der Name aus der eigenen Schrifterkennung, wenn alle Zeichen bekannt sind (kein "?"), sonst null. */
    private static String schriftName(Glyphenschrift.Ergebnis e) {
        if (e == null || e.leer() || e.text() == null) return null;
        String t = sauber(e.text());
        return t.isEmpty() || t.contains("?") ? null : t;
    }

    /** Die Notiz mit dem neuen Fingerabdruck vorn und der Schrift dazu (ohne Namensbild: unveraendert). */
    private static Notiz mitNamensbild(Notiz n, Glyphenschrift.Ergebnis e) {
        if (n == null || e == null || e.leer() || ArenaSperre.glyphenAnzahl(e.fingerabdruck()) == 0) return n;
        String fps = ArenaSperre.dazu(n.fingerabdruecke(), e.fingerabdruck());
        return n.mitNamensbild(fps, sauber(e.text()));
    }

    /**
     * Die Notiz, deren Namensbild zu diesem Fingerabdruck passt (verwandt, siehe Glyphenschrift), oder null. Mehrere:
     * die aehnlichste, bei Gleichstand die mit mehr Kaempfen. Ein anderer Clan (beide sicher ein echter Clan) zaehlt nur
     * mit Kampfkraft hoechstens 10 % auseinander -- zwei verschiedene Spieler duerfen gleich heissen, und das Namensbild
     * vergleicht nur den Namen.
     */
    private Notiz nachNamensbild(String fingerabdruck, String clan, long staerke) {
        if (ArenaSperre.glyphenAnzahl(fingerabdruck) < ArenaSperre.MIN_GLYPHEN) return null;
        Notiz beste = null;
        double besterWert = 0;
        for (Notiz n : notizen.values()) {
            if (n.fingerabdruecke() == null || n.fingerabdruecke().isBlank()) continue;
            double w = ArenaSperre.passt(glyphen(), n.fingerabdruecke(), fingerabdruck);
            if (w <= 0 || namensbildWiderspricht(n.clan(), n.staerke(), clan, staerke)) continue;
            if (w > besterWert || (w == besterWert && beste != null && n.kaempfe() > beste.kaempfe())) {
                besterWert = w;
                beste = n;
            }
        }
        return beste;
    }

    /** Anderer echter Clan und keine Kampfkraft, die das ausgleicht (beide bekannt, hoechstens 10 % auseinander)? */
    static boolean namensbildWiderspricht(String clanA, long kraftA, String clanB, long kraftB) {
        if (!echterClan(clanA) || !echterClan(clanB)) return false;
        if (NamensVergleich.gleicherClan(clanA, clanB) || clanAehnlich(clanA, clanB)
                || nurZeichen(clanA).equals(nurZeichen(clanB))) return false;
        boolean kraftPasst = kraftA > 0 && kraftB > 0 && Math.abs(kraftA - kraftB) <= Math.max(kraftA, kraftB) / 10;
        return !kraftPasst;
    }

    private static boolean echterClan(String c) {
        return c != null && !c.isBlank() && !keinClan(c) && !c.strip().equalsIgnoreCase(OHNE_CLAN);
    }

    private Glyphenschrift glyphen() {
        if (glyphen == null) glyphen = Glyphenschrift.standard();
        return glyphen;
    }

    /**
     * Traegt den Ausgang eines Kampfes ein.
     *
     * @param staerke die abgelesene Kampfstaerke, oder -1 wenn unbekannt
     */
    public synchronized void ergebnis(String name, String clan,
                                      boolean gewonnen, long staerke) {
        ergebnis(name, clan, gewonnen, staerke, -1);
    }

    /** Neue Notiz, die alles Uebrige vom alten Eintrag mitnimmt. */
    private static Notiz mitAltem(Notiz alt, String name, String clan, String verlauf,
                                  long staerke, int artefakte, String dauern, String punkte) {
        return new Notiz(sauber(name), sauber(clan), verlauf, staerke, artefakte,
                LocalDateTime.now().format(STEMPEL),
                alt == null ? -1 : alt.setPunkte(),
                alt == null ? "" : alt.sets(),
                alt == null ? "" : alt.geprueft(),
                dauern != null ? dauern : (alt == null ? "" : alt.dauern()),
                alt == null ? -1 : alt.einheiten(),
                alt == null ? -1 : alt.bauarbeiter(),
                punkte != null ? punkte : (alt == null ? "" : alt.punkte()),
                alt == null ? "" : alt.notiz(),
                alt == null ? "" : alt.steine(),
                alt == null ? "" : alt.schirm(),
                alt == null ? "" : alt.pals(),
                alt == null ? "" : alt.fingerabdruecke(),
                alt == null ? "" : alt.schrift());
    }

    /**
     * Wie oben, dazu die Kampfdauer in Sekunden (-1 = unbekannt). Autor,
     * 24.09.: "desto laenger ein Kampf geht desto weniger Punkte bekomm ich"
     * -- die letzten fuenf Dauern werden gemerkt.
     */
    public synchronized void ergebnis(String name, String clan,
                                      boolean gewonnen, long staerke, int dauerSekunden) {
        ergebnis(name, clan, gewonnen, staerke, dauerSekunden, -1);
    }

    /** Dazu die Turnierpunkte, die der Kampf gebracht hat (25.09., fuer den Notizblock). */
    public synchronized void ergebnis(String name, String clan, boolean gewonnen,
                                      long staerke, int dauerSekunden, long turnierPunkte) {
        ergebnis(name, clan, gewonnen, staerke, dauerSekunden, turnierPunkte, null);
    }

    /**
     * Wie oben, mit dem Namensbild (Ergebnis der Glyphenschrift, null = keins). Autor 24.10.: passt der Fingerabdruck
     * zu einer Notiz, kommt der Kampf dorthin -- egal wie Windows den Namen diesmal gelesen hat. Der Fingerabdruck wird
     * zur Notiz gemerkt.
     */
    public synchronized void ergebnis(String name, String clan, boolean gewonnen,
                                      long staerke, int dauerSekunden, long turnierPunkte,
                                      Glyphenschrift.Ergebnis namensbild) {
        String fp = namensbild == null ? null : namensbild.fingerabdruck();
        if (name == null || name.isBlank()) {
            // Zierschrift-Namen liest Windows oft gar nicht: dann der Name der Notiz mit demselben Namensbild, sonst der
            // aus der eigenen Schrift (alle Zeichen bekannt).
            Notiz bekannt = nachNamensbild(fp, clan, staerke);
            name = bekannt != null ? bekannt.name() : schriftName(namensbild);
            if (bekannt != null && (clan == null || clan.isBlank() || keinClan(clan))) clan = bekannt.clan();
        }
        if (keinClan(name)) return;
        if (keinClan(clan)) {   // Autor, 08.10.: ohne Clan, aber Zahlen/Zeichen im Namen = echter Spieler
            if (!NamensVergleich.siehtMenschlichAus(name)) return;
            clan = OHNE_CLAN;
        }
        if (name == null || name.isBlank()) {
            // Ohne lesbaren Namen gibt es nichts zu merken. Kommt bei
            // Zierschrift-Namen vor, die keine Texterkennung liest.
            return;
        }
        String[] kn = kanonischUndSchoener(name, clan, staerke, fp);   // Lese-Varianten auf einen Eintrag
        name = kn[0];
        clan = kn[1];
        String key = schluessel(name, clan);
        Notiz alt = mitNamensbild(vorgemerkt(key, name, clan), namensbild);

        String verlauf = (alt == null ? "" : alt.verlauf()) + (gewonnen ? "S" : "N");
        if (verlauf.length() > MERKT_SICH) {
            verlauf = verlauf.substring(verlauf.length() - MERKT_SICH);
        }

        String dauern = anhaengen(alt == null ? "" : alt.dauern(), dauerSekunden);
        // Nur die Punkte von SIEGEN werden gemerkt; Niederlagen bringen null
        // und stehen schon im Verlauf. mittlerePunkte() rechnet beides
        // zusammen. So verzerrt ein Sieg, dessen Zahl nicht lesbar war,
        // den Schnitt nicht (vorher zaehlte jede Niederlage als 0, ein
        // unlesbarer Sieg aber gar nicht -- der Schnitt fiel zu tief).
        String punkte = alt == null ? "" : alt.punkte();
        if (gewonnen) punkte = anhaengen(punkte, turnierPunkte);
        notizen.put(key, mitNamensbild(mitAltem(alt, name, clan, verlauf,
                staerke > 0 ? staerke : (alt == null ? -1 : alt.staerke()),
                alt == null ? -1 : alt.artefakte(), dauern, punkte), namensbild));

        Notiz neu = notizen.get(key);
        Log.info("Notizblock", Sprache.t(sauber(name) + " (" + sauber(clan) + "): " + (gewonnen ? "won" : "lost") + (dauerSekunden > 0 ? " in " + dauerSekunden + " s" : "") + " -- record " + neu.siege() + " of " + neu.kaempfe() + (neu.aussagekraeftig() ? "" : ", still too few for a verdict"),
                sauber(name) + " (" + sauber(clan) + "): "
                + (gewonnen ? "gewonnen" : "verloren")
                + (dauerSekunden > 0 ? " in " + dauerSekunden + " s" : "")
                + " -- Bilanz " + neu.siege() + " von " + neu.kaempfe()
                + (neu.aussagekraeftig() ? "" : ", noch zu wenig fuer ein Urteil")));
        speichern();
    }

    /**
     * Haelt die Kampfstaerke fest, ohne einen Kampf einzutragen.
     *
     * Fuer die Erkundung: der Bot sieht sich in Runde 1 alle Gegner an und
     * merkt sich, wie stark sie sind -- ohne gegen sie angetreten zu sein.
     * Der Verlauf bleibt dabei unberuehrt, sonst stuende dort eine
     * Niederlage, die es nie gab.
     */
    public synchronized void merkeStaerke(String name, String clan, long staerke) {
        merkeStaerke(name, clan, staerke, null);
    }

    /** Wie oben, mit dem Namensbild (Glyphenschrift, Autor 24.10.; null = keins) -- siehe ergebnis(). */
    public synchronized void merkeStaerke(String name, String clan, long staerke, Glyphenschrift.Ergebnis namensbild) {
        String fp = namensbild == null ? null : namensbild.fingerabdruck();
        if ((name == null || name.isBlank()) && staerke > 0) {
            Notiz bekannt = nachNamensbild(fp, clan, staerke);
            name = bekannt != null ? bekannt.name() : schriftName(namensbild);
            if (bekannt != null && (clan == null || clan.isBlank() || keinClan(clan))) clan = bekannt.clan();
        }
        if (name == null || name.isBlank() || staerke <= 0) return;
        if (keinClan(name)) return;
        if (keinClan(clan)) {   // Autor, 08.10.: ohne Clan, aber Zahlen/Zeichen im Namen = echter Spieler
            if (!NamensVergleich.siehtMenschlichAus(name)) return;
            clan = OHNE_CLAN;
        }
        String[] kn = kanonischUndSchoener(name, clan, staerke, fp);   // Lese-Varianten auf einen Eintrag
        name = kn[0];
        clan = kn[1];
        String key = schluessel(name, clan);
        Notiz vorher = vorgemerkt(key, name, clan);
        Notiz alt = mitNamensbild(vorher, namensbild);
        if (alt != null && alt.staerke() == staerke) {   // nichts Neues (hoechstens ein neuer Fingerabdruck)
            // (vorgemerkt() nimmt einen Eintrag ohne Clan aus der Liste -- dann muss er unter dem neuen Schluessel zurueck)
            if (!alt.equals(vorher) || !notizen.containsKey(key)) {
                notizen.put(key, alt);
                offen = true;
            }
            return;
        }

        notizen.put(key, mitNamensbild(mitAltem(alt, name, clan,
                alt == null ? "" : alt.verlauf(),
                staerke,
                alt == null ? -1 : alt.artefakte(), null, null), namensbild));
        // Autor, 01.10.: die Kraft wird bei jedem Turnier neu gelesen, auch bei bekannten
        // Spielern -- "sie kann sich dauernd aendern". Eine Aenderung soll man im Protokoll sehen.
        if (alt != null && alt.staerke() > 0) {
            Log.info("Notizblock", Sprache.t(sauber(name) + ": power now " + kraftKurz(staerke) + " (before " + kraftKurz(alt.staerke()) + ").",
                    sauber(name) + ": Kraft jetzt " + kraftKurz(staerke)
                    + " (vorher " + kraftKurz(alt.staerke()) + ")."));
        }

        // NICHT sofort schreiben.
        //
        // Waehrend der Erkundung kommen bis zu neun Aufrufe kurz
        // hintereinander, und speichern() baut jedes Mal die ganze Datei neu
        // auf -- mitten in der Phase, in der die Rundenuhr laeuft. Der
        // Aufrufer sagt mit jetztSpeichern(), wann er fertig ist.
        offen = true;
    }

    /** Schreibt aufgeschobene Aenderungen jetzt wirklich auf die Platte. */
    public synchronized void jetztSpeichern() {
        if (!offen) return;
        offen = false;
        speichern();
    }

    /** Steht noch etwas aus, das nicht geschrieben wurde? */
    private boolean offen;

    /** Beide Dateien jetzt neu schreiben (Autor 24.10.: nach "!glyph" -- die Namen stehen dann gleich mit ihren Zeichen da). */
    public synchronized void neuSchreiben() {
        offen = false;
        speichern();
    }

    /** Haelt fest, wie viele starke Artefakte bei diesem Gegner gesehen wurden. */
    public synchronized void merkeArtefakte(String name, String clan, int anzahl) {
        if (name == null || name.isBlank() || anzahl < 0) return;
        String[] kn = kanonisch(name, clan);   // Lese-Varianten auf einen Eintrag
        name = kn[0];
        clan = kn[1];
        String key = schluessel(name, clan);
        Notiz alt = vorgemerkt(key, name, clan);
        notizen.put(key, mitAltem(alt, name, clan,
                alt == null ? "" : alt.verlauf(),
                alt == null ? -1 : alt.staerke(),
                anzahl, null, null));
        speichern();
    }

    /**
     * Haelt fest, was bei der Durchsicht der Einheiten herauskam (24.09.):
     * Set-Punkte (S=8 ... F=1 je Einheit), die Sets in Kurzform und wie
     * viele Einheiten ein gelistetes gutes Artefakt tragen.
     */
    public synchronized void merkePruefung(String name, String clan, int setPunkte,
                                           String sets, int guteArtefakte,
                                           int einheiten, int bauarbeiter) {
        merkePruefung(name, clan, setPunkte, sets, guteArtefakte, einheiten, bauarbeiter, 0, 0);
    }

    public synchronized void merkePruefung(String name, String clan, int setPunkte,
                                           String sets, int guteArtefakte,
                                           int einheiten, int bauarbeiter, int gelbeSteine, int roteSteine) {
        merkePruefung(name, clan, setPunkte, sets, guteArtefakte, einheiten, bauarbeiter, gelbeSteine, roteSteine,
                -1, 0, 0, -1);
    }

    /**
     * Nur Schirmherrschaften und/oder Pals nachtragen (Autor, 01.10.) -- die Einheiten sind frisch
     * geprueft, diese Angaben fehlten noch. -1 = nicht angesehen, der alte Stand bleibt.
     */
    public synchronized void merkeExtras(String name, String clan, int schirmAnzahl, int guteSchirme,
                                         int guteStigmas, int gutePals) {
        if (name == null || name.isBlank()) return;
        if (keinClan(name)) return;
        if (keinClan(clan)) {   // Autor, 08.10.: ohne Clan, aber Zahlen/Zeichen im Namen = echter Spieler
            if (!NamensVergleich.siehtMenschlichAus(name)) return;
            clan = OHNE_CLAN;
        }
        String[] kn = kanonisch(name, clan);
        String key = schluessel(kn[0], kn[1]);
        Notiz n = vorgemerkt(key, kn[0], kn[1]);
        if (n == null) return;
        notizen.put(key, new Notiz(n.name(), n.clan(), n.verlauf(), n.staerke(), n.artefakte(), n.zuletzt(),
                n.setPunkte(), n.sets(), n.geprueft(), n.dauern(), n.einheiten(), n.bauarbeiter(),
                n.punkte(), n.notiz(), n.steine(),
                schirmAnzahl >= 0 ? schirmAnzahl + "/" + guteSchirme + "/" + guteStigmas : n.schirm(),
                gutePals >= 0 ? String.valueOf(gutePals) : n.pals(), n.fingerabdruecke(), n.schrift()));
        speichern();
    }

    /** Dazu Schirmherrschaften und Pals (-1 = nicht angesehen, der alte Stand bleibt). */
    public synchronized void merkePruefung(String name, String clan, int setPunkte,
                                           String sets, int guteArtefakte,
                                           int einheiten, int bauarbeiter, int gelbeSteine, int roteSteine,
                                           int schirmAnzahl, int guteSchirme, int guteStigmas, int gutePals) {
        if (name == null || name.isBlank()) return;
        if (keinClan(name)) return;
        if (keinClan(clan)) {   // Autor, 08.10.: ohne Clan, aber Zahlen/Zeichen im Namen = echter Spieler
            if (!NamensVergleich.siehtMenschlichAus(name)) return;
            clan = OHNE_CLAN;
        }
        String[] kn = kanonisch(name, clan);   // Lese-Varianten auf einen Eintrag
        name = kn[0];
        clan = kn[1];
        String key = schluessel(name, clan);
        Notiz alt = vorgemerkt(key, name, clan);
        String jetzt = LocalDateTime.now().format(STEMPEL);
        notizen.put(key, new Notiz(sauber(name), sauber(clan),
                alt == null ? "" : alt.verlauf(),
                alt == null ? -1 : alt.staerke(),
                guteArtefakte, jetzt, setPunkte, sets == null ? "" : sets, jetzt,
                // Pruefer 29.10.: -1 = abgebrochene Pruefung, die Zahl der Einheiten ist unbekannt -- die alte bleibt.
                alt == null ? "" : alt.dauern(), einheiten >= 0 || alt == null ? einheiten : alt.einheiten(), bauarbeiter,
                alt == null ? "" : alt.punkte(), alt == null ? "" : alt.notiz(),
                gelbeSteine + "/" + roteSteine,
                schirmAnzahl >= 0 ? schirmAnzahl + "/" + guteSchirme + "/" + guteStigmas
                                  : (alt == null ? "" : alt.schirm()),
                gutePals >= 0 ? String.valueOf(gutePals) : (alt == null ? "" : alt.pals()),
                alt == null ? "" : alt.fingerabdruecke(), alt == null ? "" : alt.schrift()));
        zusammenlegenMit(key);   // Autor 24.10.: Clan falsch gelesen, aber dieselben Sets -> gleich zum alten Eintrag
        speichern();
    }

    /** Alle Notizen, die zuletzt gesehenen zuerst. */
    /**
     * Autor 19.10.: Bilanz ueber ALLE Clan-Schreibweisen eines Namens (die Texterkennung las "Июльский" einmal so und
     * einmal als "V hionbCKb4i B Tep" -- zwei Eintraege, jeder unter drei Niederlagen). @return {Siege, Kaempfe}
     */
    public synchronized int[] bilanzNachName(String name) {
        return bilanzNachName(name, 0);
    }

    /** Wie oben; mit der Kampfkraft aus dem Turnier zaehlen auch Namen mit zwei Lesefehlern (Autor 19.10.). */
    public synchronized int[] bilanzNachName(String name, long kraft) {
        return bilanzNachName(name, kraft, null);
    }

    /**
     * Wie oben; dazu zaehlen die Notizen mit demselben Namensbild (Fingerabdruck der Glyphenschrift, Autor 24.10.) --
     * auch wenn Windows den Namen dort ganz anders gelesen hat. Jede Notiz zaehlt nur einmal.
     */
    public synchronized int[] bilanzNachName(String name, long kraft, String fingerabdruck) {
        int siege = 0, kaempfe = 0;
        String gesucht = nurZeichen(name);
        boolean mitBild = ArenaSperre.glyphenAnzahl(fingerabdruck) >= ArenaSperre.MIN_GLYPHEN;
        if (gesucht.isEmpty() && !mitBild) return new int[]{0, 0};
        for (Notiz n : notizen.values()) {
            if (n.nameIstClan()) continue;   // Autor 01.11.: Name = Clan, mehrere Spieler in einem Eintrag -- zaehlt nicht
            boolean zaehlt = !gesucht.isEmpty() && nameZaehlt(nurZeichen(n.name()), gesucht, n.staerke(), kraft);
            if (!zaehlt && mitBild && n.fingerabdruecke() != null && !n.fingerabdruecke().isBlank()) {
                zaehlt = ArenaSperre.passt(glyphen(), n.fingerabdruecke(), fingerabdruck) > 0;
            }
            if (!zaehlt) continue;
            siege += n.siege();
            kaempfe += n.kaempfe();
        }
        return new int[]{siege, kaempfe};
    }

    private static boolean nameZaehlt(String dort, String gesucht, long kraftDort, long kraft) {
        if (!derselbe(dort, gesucht, kraftDort, kraft)) return false;
        // Kurze Namen ("i", "lord") sind oft unlesbare oder haeufige Namen: nur mit gleicher Kraft (1 %) zaehlen.
        return gesucht.length() >= 4 || (kraftDort > 0 && kraft > 0
                && Math.abs(kraftDort - kraft) <= Math.max(kraftDort, kraft) / 100);
    }

    /** Nur Buchstaben und Ziffern, klein -- Leerzeichen und Zeichen dazwischen liest die Texterkennung mal so, mal so. */
    private static String nurZeichen(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Autor 19.10. (Arena, Runde 4): im Turnier "JkNXED", im Notizblock "JtNXED" -- die Regel griff nicht, und der Bot
     * verlor ein weiteres Mal. "Namen, die fast gleich aussehen und gleiche Power haben, sollen als gleicher Spieler
     * zaehlen." Gleich; oder ab 5 Zeichen ein Lesefehler (Zeichen anders, zu viel oder zu wenig); oder ab 4 Zeichen
     * bis zu zwei Lesefehler, wenn die Kampfkraft hoechstens 3 % auseinander liegt.
     */
    static boolean derselbe(String a, String b, long kraftA, long kraftB) {
        // Autor 22.10. (Arena 18/19): derselbe Spieler hiess in der Pyramide "蛎茶多蛎走冰加蛎峯", im Vergleichsfenster
        // "響蛎茶多" -- die Niederlage aus Arena 18 kam beim Pyramiden-Namen nie an, und der Bot verlor gegen ihn noch einmal.
        // Auf die Einerstelle gleiche Kampfkraft (ab 1 Mio) ist derselbe Spieler, egal wie der Name gelesen wurde.
        // Runde Zahlen (Vielfache von 1000) zaehlen nicht -- das sind Platzhalter oder gerundete Werte, keine gelesene Kraft.
        // Autor 01.11.: ... aber nur mit einem Namen, der dazu passt ("~ЦИРИ~" und "V Myth Gods" lagen so in EINEM Eintrag).
        if (kraftA >= 1_000_000 && kraftA == kraftB && kraftA % 1000 != 0 && namenVertraeglich(a, b)) return true;
        int d = abstand(a, b, 2);
        if (d == 0) return true;
        int kuerzer = Math.min(a.length(), b.length());
        if (d == 1 && kuerzer >= 5) return true;
        return d <= 2 && kuerzer >= 4 && gleicheKraft(kraftA, kraftB);
    }

    static boolean gleicheKraft(long a, long b) {
        return a > 0 && b > 0 && Math.abs(a - b) <= Math.max(a, b) * 0.03;
    }

    /**
     * Autor 19.10.: "Июльский" und "Щ Июльский Е" sind derselbe Clan -- die Texterkennung liest mal ein Zeichen davor
     * oder dahinter dazu. Steckt der kuerzere (ab 4 Zeichen) ganz im laengeren, gilt der Clan als gleich.
     */
    static boolean clanAehnlich(String a, String b) {
        String x = nurZeichen(a), y = nurZeichen(b);
        String kurz = x.length() <= y.length() ? x : y, lang = kurz == x ? y : x;
        return kurz.length() >= 4 && lang.contains(kurz);
    }

    /**
     * Autor 19.10.: Derselbe Spieler stand mehrfach im Notizblock (Clan oder ein Buchstabe anders gelesen), jeder
     * Eintrag fuer sich unter drei Kaempfen -- dadurch zaehlten Bilanz und Gewinnchance nicht. Beim Laden einmal
     * zusammenlegen: fast gleicher Name UND (aehnlicher Clan oder gleiche Kraft). Vorher eine Sicherung der Datei.
     */
    private void doppelteZusammenlegen() {
        java.util.List<String> keys = new java.util.ArrayList<>(notizen.keySet());
        int zusammen = 0;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                Notiz a = notizen.get(keys.get(i)), b = notizen.get(keys.get(j));
                if (a == null || b == null || !gehoerenZusammen(a, b)) continue;
                if (zusammen == 0) sichern();
                keys.set(i, zusammenlegen(keys.get(i), keys.get(j)));   // mit dem zusammengelegten Eintrag weiter vergleichen
                zusammen++;
            }
        }
        if (zusammen > 0) speichern();
    }

    /**
     * Autor 24.10.: den eben geprueften Eintrag gleich mit seinen Doppelgaengern zusammenlegen (nicht erst beim naechsten
     * Start). @return ob etwas zusammengelegt wurde
     */
    private boolean zusammenlegenMit(String key) {
        boolean etwas = false;
        for (String k : new ArrayList<>(notizen.keySet())) {
            Notiz a = notizen.get(key), b = notizen.get(k);
            if (k.equals(key) || a == null || b == null || !gehoerenZusammen(a, b)) continue;
            if (!etwas) sichern();
            key = zusammenlegen(key, k);
            etwas = true;
        }
        return etwas;
    }

    /** Derselbe Spieler unter zwei Eintraegen? */
    private boolean gehoerenZusammen(Notiz a, Notiz b) {
        // Autor 24.10.: gleiches Namensbild (Glyphenschrift) = derselbe Spieler, wie Windows ihn auch las.
        if (namensbildGleich(a, b)) return true;
        // Messung 30.10.: Kraft 34.874.371 stand unter 7 Schreibweisen im Buch. Dieselbe Kraft auf alle Stellen, und die Sets
        // widersprechen nicht: derselbe Spieler, auch wenn der Name ganz anders gelesen wurde. Nur mit arena-regeln-neu.txt "an".
        if (ArenaRegelnNeu.an() && gleicheKraftGenau(a, b) && !setsWidersprechen(a, b) && namenVertraeglich(a.name(), b.name())) return true;
        // Pruefer 29.10.: "G4nawa" / "паша", "49cowboyup" / "cowboyup" -- nur das Stufen-Abzeichen davor mitgelesen. Der Rest
        // gleicht einem bekannten Namen: derselbe Spieler, solange der Clan nicht widerspricht.
        if ((abzeichenVor(a.name(), b.name()) || abzeichenVor(b.name(), a.name()))
                && (a.clan().isBlank() || b.clan().isBlank() || NamensVergleich.gleicherClan(a.clan(), b.clan())
                        || clanAehnlich(a.clan(), b.clan()))) return true;
        if (!derselbe(nurZeichen(a.name()), nurZeichen(b.name()), 0, 0)) return false;
        boolean clanGleich = !a.clan().isBlank() && !b.clan().isBlank()
                && (NamensVergleich.gleicherClan(a.clan(), b.clan()) || clanAehnlich(a.clan(), b.clan()));
        // Nur ueber die Kraft (Clan ganz anders gelesen): erst ab 4 Zeichen Name und hoechstens 1 % Unterschied --
        // in der Arena haben viele Gegner aehnliche Kraft, und "i" ist meist ein unlesbarer Name (Test 19.10.).
        boolean kraftGleich = nurZeichen(a.name()).length() >= 4 && a.staerke() > 0 && b.staerke() > 0
                && Math.abs(a.staerke() - b.staerke()) <= Math.max(a.staerke(), b.staerke()) / 100;
        return clanGleich || kraftGleich || gleicheSets(a, b);
    }

    static boolean gleicheKraftGenau(Notiz a, Notiz b) {
        return a.staerke() >= 1_000_000 && a.staerke() == b.staerke();
    }

    /** Beide mit mindestens 4 gelesenen Einheiten, und die Sets passen nicht zusammen. */
    static boolean setsWidersprechen(Notiz a, Notiz b) {
        return einheitenSets(a).size() >= 4 && einheitenSets(b).size() >= 4 && !gleicheSets(a, b);
    }

    /**
     * Autor 24.10.: Clan ganz anders gelesen ("шорох" / "ب ومهس", "Совесть" / der Name selbst) und keine Kraft zum
     * Vergleichen -- aber Einheit fuer Einheit dieselben Sets: derselbe Spieler. Mindestens 4 gleiche Einheiten, davon
     * mindestens 2 mit echtem Set, hoechstens eine Einheit anders. Zwei verschiedene "Нидо" haben ganz andere Sets.
     */
    static boolean gleicheSets(Notiz a, Notiz b) {
        List<String> ea = einheitenSets(a), eb = einheitenSets(b);
        if (ea.size() < 4 || eb.size() < 4) return false;
        List<String> rest = new ArrayList<>(eb);
        int gleich = 0, echt = 0;
        for (String s : ea) {
            if (!rest.remove(s)) continue;
            gleich++;
            if (!s.equals("kein set") && !s.equals("?")) echt++;
        }
        return gleich >= 4 && echt >= 2 && gleich >= Math.max(ea.size(), eb.size()) - 1;
    }

    /** Die Sets je Einheit aus der Sets-Spalte ("Robin Bad 6/6, kein Set, ..."), ohne die Zusaetze dahinter (Kraft, Totem, Held). */
    private static List<String> einheitenSets(Notiz n) {
        List<String> l = new ArrayList<>();
        if (n.sets() == null || n.sets().isBlank()) return l;
        for (String teil : n.sets().split(",")) {
            String s = teil.strip().toLowerCase(java.util.Locale.ROOT);
            if (!s.equals("kein set") && !s.equals("?") && !s.matches(".*[0-9?]/6.*")) break;
            l.add(s);
        }
        return l;
    }

    /** Legt zwei Eintraege zusammen; der mit mehr Kaempfen bleibt. @return der Schluessel, der bleibt */
    private String zusammenlegen(String kA, String kB) {
        return zusammenlegen(kA, kB, null);
    }

    /** Wie oben; vorgabe = true: A bleibt, false: B bleibt, null: der mit mehr Kaempfen (Autor 28.10., ArenaZusammenfuehren). */
    private String zusammenlegen(String kA, String kB, Boolean vorgabe) {
        Notiz a = notizen.get(kA), b = notizen.get(kB);
        // Gleich viele Kaempfe: der mit gelesener Kraft bleibt (sein Clan ist meist der richtig gelesene -- "Baut" / "Совесть").
        boolean aBleibt = vorgabe != null ? vorgabe
                : a.kaempfe() > b.kaempfe() || (a.kaempfe() == b.kaempfe() && (a.staerke() > 0 || b.staerke() <= 0));
        Notiz haupt = aBleibt ? a : b, dazu = aBleibt ? b : a;
        String kHaupt = aBleibt ? kA : kB, kDazu = aBleibt ? kB : kA;
        // Pruefer 29.10.: "Merged: паша () into G4nawa ()" -- der Name, der bleibt, ist der ohne Stufen-Abzeichen davor.
        String name = haupt.name(), clan = haupt.clan();
        if (abzeichenVor(haupt.name(), dazu.name())) {
            String c = clan.isBlank() ? dazu.clan() : clan, k = schluessel(dazu.name(), c);
            if (k.equals(kHaupt) || k.equals(kDazu) || !notizen.containsKey(k)) {   // nicht auf einen dritten Eintrag
                name = dazu.name();
                clan = c;
            }
        }
        Notiz neu = new Notiz(name, clan, letzte(dazu.verlauf() + haupt.verlauf()),
                haupt.staerke() > 0 ? haupt.staerke() : dazu.staerke(),
                haupt.artefakte() >= 0 ? haupt.artefakte() : dazu.artefakte(), haupt.zuletzt(),
                haupt.setPunkte() >= 0 ? haupt.setPunkte() : dazu.setPunkte(),
                haupt.sets().isBlank() ? dazu.sets() : haupt.sets(),
                haupt.geprueft().isBlank() ? dazu.geprueft() : haupt.geprueft(),
                letzteZahlen(dazu.dauern() + " " + haupt.dauern()),
                haupt.einheiten() >= 0 ? haupt.einheiten() : dazu.einheiten(),
                haupt.bauarbeiter() >= 0 ? haupt.bauarbeiter() : dazu.bauarbeiter(),
                letzteZahlen(dazu.punkte() + " " + haupt.punkte()),
                haupt.notiz().isBlank() ? dazu.notiz()
                        : dazu.notiz().isBlank() || dazu.notiz().equals(haupt.notiz()) ? haupt.notiz()
                        : haupt.notiz() + " / " + dazu.notiz(),
                haupt.steine().isBlank() ? dazu.steine() : haupt.steine(),
                haupt.schirm().isBlank() ? dazu.schirm() : haupt.schirm(),
                haupt.pals().isBlank() ? dazu.pals() : haupt.pals(),
                vereint(haupt.fingerabdruecke(), dazu.fingerabdruecke()),
                haupt.schrift().isBlank() ? dazu.schrift() : haupt.schrift());
        notizen.remove(kDazu);
        String kNeu = schluessel(name, clan);
        if (!kNeu.equals(kHaupt)) {
            notizen.remove(kHaupt);
            umgezogen.put(kHaupt, kNeu);
            kHaupt = kNeu;
        }
        notizen.put(kHaupt, neu);
        umgezogen.put(kDazu, kHaupt);
        Log.info("Notizblock", Sprache.t("Merged: " + dazu.name() + " (" + dazu.clan() + ") into " + neu.name()
                + " (" + neu.clan() + ") -- record now " + neu.siege() + " of " + neu.kaempfe() + ".",
                "Zusammengelegt: " + dazu.name() + " (" + dazu.clan() + ") zu " + neu.name() + " (" + neu.clan()
                + ") -- Bilanz jetzt " + neu.siege() + " von " + neu.kaempfe() + "."));
        return kHaupt;
    }

    /** Haben beide Notizen ein passendes Namensbild (und widerspricht der Clan nicht, siehe nachNamensbild)? */
    private boolean namensbildGleich(Notiz a, Notiz b) {
        if (a.fingerabdruecke().isBlank() || b.fingerabdruecke().isBlank()) return false;
        if (namensbildWiderspricht(a.clan(), a.staerke(), b.clan(), b.staerke())) return false;
        for (String f : b.fingerabdruecke().split("[|]")) {
            if (ArenaSperre.passt(glyphen(), a.fingerabdruecke(), f) > 0) return true;
        }
        return false;
    }

    /**
     * Autor 28.10. (ArenaZusammenfuehren, nur mit Arena-Schalter): alle Eintraege desselben Spielers zu einem -- gleiches
     * Namensbild oder {@link ArenaZusammenfuehren#gleicherSpieler}, auch ueber Ketten (A=B und B=C: alle drei). Es bleibt
     * der Eintrag mit den meisten Kaempfen (Name, Clan, Kraft; fehlt dort etwas, vom naechsten). Der Verlauf wird zeitlich
     * zusammengesetzt -- aelteste zuerst nach "zuletzt", ohne Zeit zuerst, bei Gleichstand der laengere vorn -- und die
     * letzten zehn zaehlen wie immer. Speichert selbst. @return je zusammengelegtem Eintrag eine Zeile "alt -> bleibt"
     */
    synchronized List<String> gleicheSpielerZusammenlegen() {
        List<String> keys = new ArrayList<>(notizen.keySet());
        int[] kette = new int[keys.size()];
        for (int i = 0; i < kette.length; i++) kette[i] = i;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (ArenaZusammenfuehren.wurzel(kette, i) == ArenaZusammenfuehren.wurzel(kette, j)) continue;
                Notiz a = notizen.get(keys.get(i)), b = notizen.get(keys.get(j));
                if (namensbildGleich(a, b) || ArenaZusammenfuehren.gleicherSpieler(a.name(), a.clan(), a.staerke(),
                        b.name(), b.clan(), b.staerke())) {
                    ArenaZusammenfuehren.verbinden(kette, i, j);
                }
            }
        }
        List<String> bericht = new ArrayList<>();
        for (List<Integer> gruppe : ArenaZusammenfuehren.gruppen(kette)) {
            if (gruppe.size() < 2) continue;
            List<String> reihe = new ArrayList<>();
            for (int i : gruppe) reihe.add(keys.get(i));
            // Verlauf zeitlich: aelteste zuerst, ohne Zeit zuerst, bei gleicher Zeit der laengere vorn
            List<Notiz> zeitlich = new ArrayList<>();
            for (String k : reihe) zeitlich.add(notizen.get(k));
            zeitlich.sort(Comparator.comparing((Notiz n) -> n.zuletzt() == null ? "" : n.zuletzt())
                    .thenComparing(Comparator.comparingInt((Notiz n) -> n.verlauf().length()).reversed()));
            StringBuilder verlauf = new StringBuilder();
            for (Notiz n : zeitlich) verlauf.append(n.verlauf());
            // Es bleibt: meiste Kaempfe, dann mit Kraft, dann mit Clan und einem Namen ab 3 Zeichen (ohne Clan ist der
            // "Name" oft der verlesene Clan: "Тайное обществ" statt "ЕвЖеНйЙ"), dann zuletzt gesehen (gleich: der vordere).
            reihe.sort(Comparator.comparingInt((String k) -> notizen.get(k).kaempfe())
                    .thenComparing(k -> notizen.get(k).staerke() > 0)
                    .thenComparing(k -> !notizen.get(k).clan().isBlank())
                    .thenComparing(k -> nurZeichen(notizen.get(k).name()).length() >= 3)
                    .thenComparing(k -> String.valueOf(notizen.get(k).zuletzt())).reversed());
            String haupt = reihe.get(0);
            for (String k : reihe.subList(1, reihe.size())) {
                Notiz dazu = notizen.get(k), h = notizen.get(haupt);
                bericht.add(dazu.name() + " (" + dazu.clan() + (dazu.staerke() > 0 ? ", " + kraftKurz(dazu.staerke()) : "")
                        + ") -> " + h.name() + " (" + h.clan() + ")");
                haupt = zusammenlegen(haupt, k, true);
            }
            notizen.put(haupt, notizen.get(haupt).mitVerlauf(letzte(verlauf.toString())));
        }
        if (!bericht.isEmpty()) speichern();
        return bericht;
    }

    /** Zwei Listen "fp|fp|..." zusammen: erst die erste, dann die neuen der zweiten (hoechstens ArenaSperre.MAX_FINGERABDRUECKE). */
    static String vereint(String erste, String zweite) {
        List<String> l = new ArrayList<>();
        for (String s : ((erste == null ? "" : erste) + "|" + (zweite == null ? "" : zweite)).split("[|]")) {
            String f = s.strip();
            if (!f.isEmpty() && !l.contains(f) && l.size() < ArenaSperre.MAX_FINGERABDRUECKE) l.add(f);
        }
        return String.join("|", l);
    }

    private static String letzte(String verlauf) {
        return verlauf.length() > MERKT_SICH ? verlauf.substring(verlauf.length() - MERKT_SICH) : verlauf;
    }

    private static String letzteZahlen(String kette) {
        String k = kette == null ? "" : kette.trim();
        if (k.isEmpty()) return "";
        String[] d = k.split("\\s+");
        return d.length > MERKT_SICH ? String.join(" ", Arrays.copyOfRange(d, d.length - MERKT_SICH, d.length)) : k;
    }

    /** Einmalige Sicherung vor dem Zusammenlegen (arena-daten.txt -> arena-daten-vor-zusammenlegen.txt). */
    private void sichern() {
        try {
            Path ziel = datei.resolveSibling(datei.getFileName().toString().replace(".txt", "-vor-zusammenlegen.txt"));
            if (Files.exists(datei) && !Files.exists(ziel)) Files.copy(datei, ziel);
        } catch (Exception e) {
            Log.debug("Notizblock", "Sicherung vor dem Zusammenlegen nicht moeglich: " + e.getMessage());
        }
    }

    /** Wie viele Zeichen anders, zu viel oder zu wenig sind (ueber der Grenze: grenze + 1). */
    static int abstand(String a, String b, int grenze) {
        if (Math.abs(a.length() - b.length()) > grenze) return grenze + 1;
        int[] vorher = new int[b.length() + 1];
        int[] jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vorher[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            int zeileMin = i;
            for (int j = 1; j <= b.length(); j++) {
                int kosten = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + kosten);
                zeileMin = Math.min(zeileMin, jetzt[j]);
            }
            if (zeileMin > grenze) return grenze + 1;
            int[] t = vorher;
            vorher = jetzt;
            jetzt = t;
        }
        return Math.min(vorher[b.length()], grenze + 1);
    }

    public synchronized List<Notiz> alle() {
        List<Notiz> liste = new ArrayList<>(notizen.values());
        liste.sort(Comparator.comparing(Notiz::zuletzt).reversed());
        return liste;
    }

    public synchronized int anzahl() { return notizen.size(); }

    // ==================================================================
    //  Lesen und Schreiben
    // ==================================================================

    /** Konto-Wechsel (Autor 21.10.): den Notizblock des neuen Kontos lesen. */
    public synchronized void neuLaden() {
        notizen.clear();
        umgezogen.clear();
        laden();
        Log.info("Notizblock", Sprache.t(notizen.size() + " opponents read from the notebook (account switch).",
                notizen.size() + " Gegner aus dem Notizblock gelesen (Kontowechsel)."));
    }

    private void laden() {
        try {
            // Bis 25.09. stand die Tabelle selbst in arena-notizblock.txt.
            // Einmalig von dort uebernehmen; der lesbare Block dort hat keine
            // Tabulatoren und wird unten ohnehin uebersprungen.
            Path quelle = Files.exists(datei) ? datei : notizblock;
            if (!Files.exists(quelle)) return;
            for (String zeile : Dateien.zeilen(quelle)) {
                if (zeile.isBlank() || zeile.startsWith("#")) continue;
                String[] t = zeile.split("\t", -1);
                if (t.length < 6) continue;
                // Spalten 7-10 gibt es seit dem 24.09. (Sets, Pruefung, Dauern).
                Notiz n = new Notiz(t[0], t[1], t[2],
                        zahl(t[3]), (int) zahl(t[4]), t[5],
                        t.length > 6 ? (int) zahl(t[6]) : -1,
                        t.length > 7 ? t[7] : "",
                        t.length > 8 ? t[8] : "",
                        t.length > 9 ? t[9] : "",
                        t.length > 10 ? (int) zahl(t[10]) : -1,
                        t.length > 11 ? (int) zahl(t[11]) : -1,
                        t.length > 12 ? t[12] : "",
                        t.length > 13 ? t[13] : "",
                        t.length > 14 ? t[14] : "",
                        t.length > 15 ? t[15] : "",
                        t.length > 16 ? t[16] : "",
                        // Spalten 18-19 seit 24.10. (Namensbild: Fingerabdruecke und eigene Schrift)
                        t.length > 17 ? t[17].strip() : "",
                        t.length > 18 ? t[18] : "");
                if (keinClan(n.name()) || keinClan(n.clan())) continue;   // "Not in clan": raus (30.09.)
                // Namensbild mit lesbarem Namen (arena-namensbilder.txt, Autor 11.10.): unter dem Namen fuehren.
                String lesbar = n.name().startsWith("Bild-") ? Namensbilder.name(n.name()) : n.name();
                if (!lesbar.equals(n.name()) && !notizen.containsKey(schluessel(lesbar, n.clan()))) {
                    n = new Notiz(lesbar, n.clan(), n.verlauf(), n.staerke(), n.artefakte(), n.zuletzt(), n.setPunkte(),
                            n.sets(), n.geprueft(), n.dauern(), n.einheiten(), n.bauarbeiter(), n.punkte(), n.notiz(),
                            n.steine(), n.schirm(), n.pals(), n.fingerabdruecke(), n.schrift());
                }
                notizen.put(schluessel(n.name(), n.clan()), n);
            }
            Log.info("Notizblock", Sprache.t(notizen.size() + " opponents read from the notebook.",
                    notizen.size() + " Gegner aus dem Notizblock gelesen."));
            if (quelle == notizblock && !notizen.isEmpty()) speichern();   // umziehen
            doppelteZusammenlegen();
        } catch (Exception e) {
            Log.warn("Notizblock", Sprache.t("The notebook could not be read: " + e.getMessage() + " -- a new one is created.",
                    "Der Notizblock liess sich nicht lesen: " + e.getMessage()
                    + " -- es wird ein neuer angelegt."));
        }
    }

    private void speichern() {
        StringBuilder sb = new StringBuilder();
        // FREUNDE-FASSUNG (11.10.): auf Englisch eine kurze Legende oben (nur Kommentarzeilen, das Lesen bleibt gleich).
        if (Sprache.istEnglisch()) {
            sb.append("# Arena notebook -- what the bot knows about its opponents.\n");
            sb.append("# Verlauf = history as a chain: S = win (W), N = loss (L), newest fight on the right.\n");
            sb.append("# Only the last ").append(MERKT_SICH).append(" count. From ").append(GENUG_KAEMPFE)
              .append(" fights on the bot trusts the record, before that the opponent counts as unknown.\n");
            sb.append("# Columns: Staerke = power, Artefakte = artifacts, zuletzt = last seen, SetPunkte = set points,\n");
            sb.append("# geprueft = checked, Dauern(s) = fight times, Einheiten = units, Bauarbeiter = builders,\n");
            sb.append("# Turnierpunkte = tournament points, Notiz = note, Steine(gelb/rot) = stones (yellow/red),\n");
            sb.append("# Schirm(anzahl/gute/stigmas) = patronages (count/good/stigmas), GutePals = good pals.\n");
            sb.append("#\n");
        }
        sb.append("# Notizblock Arena -- was der Bot ueber seine Gegner weiss.\n");
        sb.append("# Der Verlauf steht als Kette: S ist ein Sieg, N eine Niederlage,\n");
        sb.append("# der neueste Kampf ganz rechts. Nur die letzten ").append(MERKT_SICH)
          .append(" zaehlen.\n");
        sb.append("# Ab ").append(GENUG_KAEMPFE)
          .append(" Kaempfen glaubt der Bot der Bilanz, vorher gilt der Gegner als unbekannt.\n");
        sb.append("#\n# Name\tClan\tVerlauf\tStaerke\tArtefakte\tzuletzt"
                + "\tSetPunkte\tSets\tgeprueft\tDauern(s)\tEinheiten\tBauarbeiter\tTurnierpunkte\tNotiz\tSteine(gelb/rot)"
                + "\tSchirm(anzahl/gute/stigmas)\tGutePals\tFingerabdruecke\tNamensschrift\n");
        for (Notiz n : alle()) {
            sb.append(n.name()).append('\t').append(n.clan()).append('\t')
              .append(n.verlauf()).append('\t').append(n.staerke()).append('\t')
              .append(n.artefakte()).append('\t').append(n.zuletzt()).append('\t')
              .append(n.setPunkte()).append('\t').append(n.sets()).append('\t')
              .append(n.geprueft()).append('\t').append(n.dauern()).append('\t')
              .append(n.einheiten()).append('\t').append(n.bauarbeiter()).append('\t')
              .append(n.punkte()).append('\t').append(n.notiz()).append('\t').append(n.steine())
              .append('\t').append(n.schirm()).append('\t').append(n.pals())
              .append('\t').append(n.fingerabdruecke()).append('\t').append(sauber(n.schrift())).append('\n');
        }
        try {
            sicherSchreiben(datei, sb.toString());
            sicherSchreiben(notizblock, lesbar());
        } catch (Exception e) {
            Log.warn("Notizblock", Sprache.t("The notebook could not be written: " + e.getMessage(),
                    "Der Notizblock liess sich nicht schreiben: "
                    + e.getMessage()));
        }
    }

    /**
     * Der Notizblock zum Lesen, so wie Autor ihn am 25.09. vorgemacht hat:
     *
     *     -> Rina (1/0)
     *        Clan: RUSSIA corp
     *        Kraft: 25,9 Mio
     *        Zeit: 17 sek
     *        Punkte: 71
     *        Arena-Punkte: 150
     *
     * (Siege/Niederlagen der letzten zehn Kaempfe), Zeit = mittlere
     * Kampfdauer, Punkte = unsere Bewertung ihres Teams, Arena-Punkte = was
     * ein Kampf gegen sie im Schnitt gebracht hat. Alphabetisch sortiert.
     */
    /**
     * Gehoert der Eintrag in den lesbaren Notizblock? Mindestens ein Kampf, Clan und Kraft
     * bekannt, zu jedem Kampf eine Kampfzeit, Team-Punkte geprueft, zu jedem Sieg die
     * Arena-Punkte (Autor, 08.10.).
     */
    static boolean vollstaendig(Notiz n) {
        if (n.kaempfe() == 0) return false;
        if (n.clan() == null || n.clan().isBlank() || n.staerke() <= 0) return false;
        if (n.teamPunkte() < 0) return false;
        int zeiten = n.dauern() == null || n.dauern().isBlank() ? 0 : n.dauern().trim().split("\\s+").length;
        if (zeiten < Math.min(n.kaempfe(), 5)) return false;   // es werden hoechstens fuenf gemerkt
        int arenaPunkte = n.punkte() == null || n.punkte().isBlank() ? 0 : n.punkte().trim().split("\\s+").length;
        return arenaPunkte >= Math.min(n.siege(), 5);
    }

    synchronized String lesbar() {
        List<Notiz> liste = new ArrayList<>(notizen.values());
        liste.sort(Comparator.comparing((Notiz n) -> n.name().toLowerCase())
                .thenComparing(n -> n.clan().toLowerCase()));
        // Autor, 08.10.: "Ich will den Notizblock blitzeblank haben, ohne Fehler. Ein Gegner soll
        // nur aufgenommen werden, wenn der Bot gegen ihn gekaempft hat." Der lesbare Notizblock
        // zeigt darum nur Gegner mit mindestens einem Kampf UND allen Angaben (Clan, Kraft,
        // Kampfzeit, Punkte, bei Siegen Arena-Punkte). Alles andere bleibt im Hintergrund
        // (arena-daten.txt) -- Kraft und Sets eines erkundeten Gegners sparen beim naechsten
        // Treffen das Ansehen -- und erscheint, sobald es vollstaendig ist.
        int versteckt = 0;
        List<Notiz> zeigen = new ArrayList<>();
        for (Notiz n : liste) {
            if (vollstaendig(n)) zeigen.add(n);
            else versteckt++;
        }
        StringBuilder sb = new StringBuilder("Arena: " + zeigen.size() + " Gegner mit vollstaendigen Angaben"
                + (versteckt > 0 ? " (" + versteckt + " weitere im Hintergrund: nie gekaempft oder noch nicht alles gelesen)" : "")
                + "\n");
        for (Notiz n : zeigen) {
            int team = n.teamPunkte();
            // Autor, 01.10.: Zeiten und Arena-Punkte einzeln aufzaehlen (aelteste zuerst,
            // hoechstens die letzten fuenf), nicht zu einem Schnitt zusammenfassen.
            // Autor, 08.10.: nicht mehr die einzelnen Zeiten, sondern den Durchschnitt aller
            // gemerkten Kaempfe (bis zehn) -- "ob er den Gegner fuenfmal oder zehnmal angreift".
            int anzahlZeiten = n.dauern() == null || n.dauern().isBlank() ? 0 : n.dauern().trim().split("\\s+").length;
            String zeiten = anzahlZeiten == 0 ? "" : n.mittlereDauer() + " sek" + (anzahlZeiten > 1
                    ? " (Durchschnitt aus " + anzahlZeiten + " Kaempfen)" : "");
            String arenaPunkte = n.punkte() == null ? "" : n.punkte().trim().replaceAll("\\s+", ", ");
            // Autor 24.10.: der Name so, wie er in der Arena steht (eigene Schrift, alle Zeichen bekannt) -- sonst wie
            // bisher die Windows-Lesung und darunter das Namensbild.
            String exakt = exakterName(n);
            Path bild = exakt == null ? namensbildVon(n) : null;
            sb.append("\n-> ").append(exakt != null ? exakt : n.name())
              .append(" (").append(n.siege()).append('/').append(n.kaempfe() - n.siege()).append(")\n")
              .append(bild != null ? "   Namensbild: " + bild + "\n" : "")
              .append("   Clan: ").append(n.clan().isBlank() ? "?" : n.clan()).append('\n')
              .append("   Kraft: ").append(kraftKurz(n.staerke())).append('\n')
              .append("   Zeit: ").append(zeiten.isEmpty() ? (n.kaempfe() > 0 ? "? (nicht gelesen)" : "? (noch nicht gekaempft)") : zeiten).append('\n')
              .append("   Punkte: ").append(team >= 0 ? String.valueOf(team) : "? (noch nicht geprueft)")
              // Autor, 08.10.: dazuschreiben, wie viel die Kraft ausmacht -- sonst wirkt ein Gegner mit
              // wenig Punkten schwach, obwohl nur seine Kraft weit unter (oder ueber) Autors liegt.
              .append(team >= 0 && meineKraft > 0 && n.staerke() > 0
                      ? " (mit Kraft " + (team + kraftPunkte(n.staerke(), meineKraft)) + " -- wegen "
                        + kraftHinweis(n.staerke(), meineKraft) + ")" : "")
              .append('\n')
              .append(n.schirmBekannt() ? "   Schirmherrschaften: " + n.schirmAnzahl() + " (" + n.guteSchirme()
                      + " gute, " + n.guteStigmas() + " Stigma) +" + schirmPunkte(n.schirmAnzahl(), n.guteSchirme(), n.guteStigmas()) + "\n" : "")
              .append(n.palsBekannt() ? "   Gute Pals: " + n.gutePals() + " +" + palPunkte(n.gutePals()) + "\n" : "")
              .append("   Arena-Punkte: ").append(arenaPunkte.isEmpty() ? (n.siege() > 0 ? "? (nicht gelesen)" : "?") : arenaPunkte).append('\n');
            // Die letzten drei Kaempfe, neuester rechts (Autor, 25.09.): zeigt,
            // ob die eine Niederlage gerade eben war.
            if (n.kaempfe() > 0) {
                String letzte = n.verlauf().substring(Math.max(0, n.verlauf().length() - 3));
                sb.append("   zuletzt: ").append(String.join(" ", letzte.split(""))).append('\n');
            }
            if (!n.notiz().isBlank()) sb.append("   Notiz: ").append(n.notiz()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Der Name mit den exakten Zeichen aus der eigenen Schrift (Autor 24.10.), oder null, solange ein Zeichen unbekannt
     * ist. Zeichen, die Autor nach dem letzten Kampf beschriftet hat (!glyph), werden gleich eingesetzt.
     */
    String exakterName(Notiz n) {
        if (n.schrift() == null || n.schrift().isBlank() || n.fingerabdruecke().isBlank()) return null;
        if (!n.schrift().contains("?")) return n.schrift();
        String erster = n.fingerabdruecke().split("[|]")[0];
        String s = exakteSchrift(glyphen(), erster, n.schrift());
        return s == null || s.isBlank() || s.contains("?") ? null : s;
    }

    /** Das abgelegte Namensbild zum neuesten Fingerabdruck (glyphen\namen\...png) oder null. */
    private Path namensbildVon(Notiz n) {
        if (n.fingerabdruecke() == null || n.fingerabdruecke().isBlank()) return null;
        try {
            return glyphen().namensbild(n.fingerabdruecke().split("[|]")[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Setzt in eine gemerkte Schrift (mit "?" fuer damals unbekannte Zeichen) die Zeichen ein, die die Bibliothek
     * inzwischen kennt. Die Schrift entstand Glyphe fuer Glyphe aus dem Fingerabdruck (Leerzeichen aus Luecken im Bild,
     * "?" = unbekannt, Teil-Glyphen ohne Zeichen). Passt etwas nicht zusammen (Zeichen inzwischen anders beschriftet):
     * null.
     */
    static String exakteSchrift(Glyphenschrift gs, String fingerabdruck, String schrift) {
        int[] nr = Glyphenschrift.nummern(fingerabdruck);
        if (nr.length == 0 || schrift == null) return null;
        int[] cps = schrift.codePoints().toArray();
        StringBuilder aus = new StringBuilder();
        int p = 0;
        for (int g : nr) {
            if (p < cps.length && cps[p] == ' ' && aus.length() > 0) {
                aus.append(' ');
                p++;
            }
            String jetzt = gs.zeichen(g);
            boolean teil = Glyphenschrift.TEIL.equals(jetzt);
            if (p < cps.length && cps[p] == '?') {   // damals unbekannt
                p++;
                aus.append(jetzt == null ? "?" : teil ? "" : jetzt);
            } else if (jetzt != null && !teil && beginntMit(cps, p, jetzt)) {
                p += jetzt.codePointCount(0, jetzt.length());
                aus.append(jetzt);
            } else if (!teil) {
                return null;   // damals anders beschriftet
            }
        }
        return p == cps.length ? aus.toString() : null;
    }

    private static boolean beginntMit(int[] cps, int ab, String s) {
        int[] t = s.codePoints().toArray();
        if (ab + t.length > cps.length) return false;
        for (int i = 0; i < t.length; i++) if (cps[ab + i] != t[i]) return false;
        return true;
    }

    // ==================================================================
    //  Eigene Notizen (Autor, 25.09.: "da kann ich gleich festhalten wie
    //  viele bauarbeiter bestimmte typen haben")
    // ==================================================================

    /**
     * Welcher bekannte Name steht am Anfang von {@code eingabe}?
     *
     * Namen koennen Leerzeichen enthalten ("Not iB4an"), deshalb wird nicht
     * einfach am ersten Leerzeichen getrennt: der laengste bekannte Name,
     * der vorn steht und von einem Leerzeichen oder dem Ende gefolgt wird,
     * gewinnt. Leer, wenn keiner passt.
     */
    public synchronized String bekannterNameAm(String eingabe) {
        String klein = eingabe.strip().toLowerCase();
        String bester = "";
        for (Notiz n : notizen.values()) {
            String name = n.name().toLowerCase();
            if (name.isEmpty() || name.length() <= bester.length()) continue;
            if (klein.equals(name) || klein.startsWith(name + " ")) bester = n.name();
        }
        return bester;
    }

    /** Alle Eintraege mit diesem Namen (verschiedene Clans moeglich). */
    public synchronized List<Notiz> mitNamen(String name) {
        List<Notiz> treffer = new ArrayList<>();
        for (Notiz n : notizen.values()) {
            if (n.name().equalsIgnoreCase(name.strip())) treffer.add(n);
        }
        return treffer;
    }

    /**
     * Setzt die eigene Notiz fuer alle Eintraege mit diesem Namen (leer =
     * loeschen) und gibt zurueck, wie viele es waren. Gibt es den Namen noch
     * nicht, entsteht ein Eintrag ohne Clan; trifft der Bot den Spieler
     * spaeter mit Clan, wandert die Notiz dorthin (siehe vorgemerkt()).
     */
    public synchronized int setzeNotiz(String name, String text) {
        String notiz = sauber(text).replace('\n', ' ');
        List<Notiz> treffer = mitNamen(name);
        if (treffer.isEmpty()) {
            if (notiz.isEmpty()) return 0;
            Notiz neu = new Notiz(sauber(name), "", "", -1, -1,
                    LocalDateTime.now().format(STEMPEL), -1, "", "", "", -1, -1, "", notiz, "", "", "");
            notizen.put(schluessel(name, ""), neu);
            speichern();
            return 1;
        }
        for (Notiz n : treffer) {
            notizen.put(schluessel(n.name(), n.clan()), new Notiz(n.name(), n.clan(), n.verlauf(),
                    n.staerke(), n.artefakte(), n.zuletzt(), n.setPunkte(), n.sets(), n.geprueft(),
                    n.dauern(), n.einheiten(), n.bauarbeiter(), n.punkte(), notiz, n.steine(), n.schirm(), n.pals(),
                    n.fingerabdruecke(), n.schrift()));
        }
        speichern();
        return treffer.size();
    }

    /**
     * Name und Clan so, wie sie schon im Notizblock stehen, wenn es derselbe
     * Spieler in anderer Lesart ist (25.09.: "Cxa30LUHHKH" kam in sechs
     * Schreibweisen vor). Genau gleich gewinnt immer; sonst ein aehnlicher
     * Name mit passendem Clan. Steht einer der beiden Clans leer, zaehlt der
     * Name allein -- der Clan wird dann nachgetragen, nicht ueberschrieben.
     */
    private String[] kanonisch(String name, String clan) {
        return kanonisch(name, clan, -1);
    }

    /**
     * Wie oben, dazu die Kampfstaerke: gleicher Name und (fast) dieselbe Staerke ist derselbe
     * Spieler, auch wenn der Clan jedes Mal anders gelesen wurde ("HECnATb 2.0", "PHEC Tb2.O",
     * "q HEcnAIX2" -- 01.10., dreimal "eoe" im Notizblock).
     */
    private String[] kanonisch(String name, String clan, long staerke) {
        return kanonisch(name, clan, staerke, null);
    }

    /**
     * Wie oben, dazu der Fingerabdruck des Namensbildes (Autor 24.10.): genau gleicher Name und Clan gewinnt weiter
     * immer; sonst die Notiz mit demselben Namensbild (siehe nachNamensbild), erst dann die aehnlichen Namen.
     */
    private String[] kanonisch(String name, String clan, long staerke, String fingerabdruck) {
        String c = clan == null ? "" : clan;
        if (name != null && !name.isBlank() && notizen.containsKey(schluessel(name, c))) {
            return new String[]{name, c};
        }
        String ziel = name == null ? null : umgezogen.get(schluessel(name, c));
        for (int i = 0; ziel != null && !notizen.containsKey(ziel) && i < 5; i++) ziel = umgezogen.get(ziel);
        if (ziel != null && notizen.containsKey(ziel)) {
            Notiz n = notizen.get(ziel);
            return new String[]{n.name(), n.clan()};
        }
        Notiz perBild = nachNamensbild(fingerabdruck, c, staerke);
        if (perBild != null) return new String[]{perBild.name(), perBild.clan()};
        if (name == null || name.isBlank()) {
            return new String[]{name, c};
        }
        Notiz ohneClan = null, gleicheKraft = null;
        for (Notiz n : notizen.values()) {
            // Autor 19.10.: auch ein Lesefehler im Namen ("JkNXED" / "JtNXED") ist derselbe Spieler.
            if (!NamensVergleich.gleicherName(n.name(), name) && !derselbe(nurZeichen(n.name()), nurZeichen(name), 0, 0)) continue;
            if (n.clan().isBlank() || c.isBlank()) {
                if (ohneClan == null) ohneClan = n;
                continue;
            }
            if (NamensVergleich.gleicherClan(n.clan(), c) || clanAehnlich(n.clan(), c)) return new String[]{n.name(), n.clan()};
            if (staerke > 0 && n.staerke() > 0 && Math.abs(n.staerke() - staerke) <= staerke / 50
                    && gleicheKraft == null) gleicheKraft = n;
        }
        if (gleicheKraft != null) return new String[]{gleicheKraft.name(), gleicheKraft.clan()};
        if (ohneClan != null) {
            return new String[]{ohneClan.name(), c.isBlank() ? ohneClan.clan() : c};
        }
        // Lesefehler-Namen, die Autor einem Eintrag zugeordnet hat: in der Notiz steht
        // "alias:JUXED,JtHXED" (01.10., JINXED MURENA in Fancy-Schrift).
        for (Notiz n : notizen.values()) {
            for (String alias : aliasse(n.notiz())) {
                if (NamensVergleich.gleicherName(alias, name)) return new String[]{n.name(), n.clan()};
            }
        }
        // Autor 28.10. (nur mit Arena-Schalter): bevor ein NEUER Spieler entsteht -- erstes Namenswort mit den
        // Verwechslern der Texterkennung gleich und Kraft fast gleich ("JkNXED MURENA" zu "JtNXED"), siehe ArenaZusammenfuehren.
        if (ArenaSchalter.an()) {
            Notiz gleich = gleicherSpieler(name, c, staerke);
            if (gleich != null) return new String[]{gleich.name(), gleich.clan()};
        }
        return new String[]{name, c};
    }

    /**
     * Autor 28.10.: die Notiz desselben Spielers nach {@link ArenaZusammenfuehren#gleicherSpieler}, oder null. Mehrere:
     * die mit der naechsten Kraft, dann die mit mehr Kaempfen.
     */
    private Notiz gleicherSpieler(String name, String clan, long staerke) {
        Notiz beste = null;
        long besterAbstand = Long.MAX_VALUE;
        for (Notiz n : notizen.values()) {
            if (!ArenaZusammenfuehren.gleicherSpieler(n.name(), n.clan(), n.staerke(), name, clan, staerke)) continue;
            long d = n.staerke() > 0 && staerke > 0 ? Math.abs(n.staerke() - staerke) : Long.MAX_VALUE - 1;
            if (beste == null || d < besterAbstand || (d == besterAbstand && n.kaempfe() > beste.kaempfe())) {
                beste = n;
                besterAbstand = d;
            }
        }
        return beste;
    }

    /**
     * Wie kanonisch(), aber die schoenere Lesart gewinnt (Autor, 08.10.: "der Notizblock soll
     * super aussehen"). Steht der Spieler schon im Buch und die neue Lesung ist sauberer --
     * keine Mischung aus lateinischen und kyrillischen Buchstaben ("king ВоЬ" gegen "King Bob"),
     * keine Sonderzeichen, nicht abgeschnitten ("Winterf." gegen "Winterfell") --, zieht der
     * Eintrag mit allem, was er weiss, auf die neue Schreibweise um.
     */
    private String[] kanonischUndSchoener(String name, String clan, long staerke, String fingerabdruck) {
        String[] kn = kanonisch(name, clan, staerke, fingerabdruck);
        String altName = kn[0], altClan = kn[1] == null ? "" : kn[1];
        String neuName = schoener(altName, sauber(name)) ? sauber(name) : altName;
        String neuClan = clan != null && !clan.isBlank() && schoener(altClan, sauber(clan)) ? sauber(clan) : altClan;
        if (neuName.equals(altName) && neuClan.equals(altClan)) return kn;
        Notiz n = notizen.remove(schluessel(altName, altClan));
        if (n == null) return kn;   // noch kein Eintrag -- dann gibt es nichts umzuziehen
        notizen.put(schluessel(neuName, neuClan), new Notiz(neuName, neuClan, n.verlauf(), n.staerke(),
                n.artefakte(), n.zuletzt(), n.setPunkte(), n.sets(), n.geprueft(), n.dauern(), n.einheiten(),
                n.bauarbeiter(), n.punkte(), n.notiz(), n.steine(), n.schirm(), n.pals(), n.fingerabdruecke(), n.schrift()));
        Log.info("Notizblock", "Schreibweise verbessert: \"" + altName + "\" (" + altClan + ") -> \""
                + neuName + "\" (" + neuClan + ").");
        return new String[]{neuName, neuClan};
    }

    /**
     * Ist {@code neu} eine klar bessere Lesart desselben Namens als {@code alt}? Bewertet werden:
     * Schriftmischung lateinisch/kyrillisch (-3), Sonderzeichen (-1 je Zeichen), abgeschnitten
     * mit Punkt am Ende (-2), Laenge (+0,1 je Zeichen). Ein "Bild-..."-Name (Fingerabdruck) wird
     * nie ersetzt -- ueber ihn wird der Spieler am Bild wiedererkannt.
     */
    static boolean schoener(String alt, String neu) {
        if (neu == null || neu.isBlank() || alt == null) return false;
        if (neu.equals(alt)) return false;
        // Autor 01.11.: "Bild-1024-032130" ist nur ein Platzhalter. Eine glaubhafte Lesung ersetzt ihn als Namen des Eintrags (das Namensbild bleibt der
        // Anker: die Fingerabdruecke bleiben); Fehlformen ("冕괴困", "نسعع", "元奏雄联盟") ueberschreiben nie einen Namen.
        if (!plausiblerName(neu)) return false;
        if (alt.startsWith("Bild-")) return true;
        if (alt.isBlank()) return true;
        // Pruefer 29.10.: "Rina" -> "G6RinassCr" war nur das Stufen-Abzeichen davor (+0,1 je Zeichen liess es gewinnen).
        if (abzeichenVor(neu, alt, true)) return false;
        if (abzeichenVor(alt, neu, false)) return true;   // umgekehrt: der alte Eintrag hat das Abzeichen, der neue nicht
        // 11.10. (Autor: "im Spiel steht er in normaler Schrift"): frueher wurden reine Zwillings-Namen kyrillisch
        // gespeichert ("осеап"). Liest der Bot denselben Namen jetzt sauber lateinisch ("ocean", gleiches Skelett),
        // gilt die lateinische Schreibweise.
        if (nurLateinisch(neu) && enthaeltKyrillisch(alt)
                && NamensVergleich.skelett(neu).equals(NamensVergleich.skelett(alt))) return true;
        return lesartWert(neu) > lesartWert(alt) + 0.5;
    }

    /**
     * Pruefer 29.10. (3.109-3.112): im Vergleichsfenster wurde das Stufen-Abzeichen links vom Namen mitgelesen -- "G4nawa"
     * fuer "паша", "49cowboyup", "53HELIOS", "i66Bernd". Ist {@code mit} = kurzer Vorsatz (hoechstens ein Zeichen, dann
     * ein bis zwei Ziffern) + ein Rest, der {@code ohne} gleicht (gleiche Form oder gleiches Skelett, ab 3 Zeichen)? Echte
     * Namen duerfen mit Ziffern beginnen ("53vilaase") -- abgeschnitten wird darum nur, wenn der Rest zu einem bekannten
     * Namen passt. {@code anfangReicht}: der Rest darf auch nur mit {@code ohne} beginnen ("Rina" -> "G6RinassCr").
     */
    public static boolean abzeichenVor(String mit, String ohne) {
        return abzeichenVor(mit, ohne, false);
    }

    static boolean abzeichenVor(String mit, String ohne, boolean anfangReicht) {
        if (mit == null || ohne == null) return false;
        String m = mit.strip(), o = NamensVergleich.form(ohne);
        if (o.length() < 3 || NamensVergleich.form(m).equals(o)) return false;
        for (int k = 1; k <= Math.min(3, m.length() - 3); k++) {
            if (!ABZEICHEN.matcher(m.substring(0, k)).matches()) continue;
            String rest = m.substring(k).strip(), r = NamensVergleich.form(rest);
            if (r.length() < 3) continue;
            if (r.equals(o) || NamensVergleich.skelett(rest).equals(NamensVergleich.skelett(ohne))) return true;
            if (anfangReicht && r.startsWith(o)) return true;
        }
        return false;
    }

    /** Was vom Abzeichen gelesen wird: hoechstens ein Zeichen (Burgturm, Pal-"i"), dann die Stufe mit 1-2 Ziffern. */
    private static final java.util.regex.Pattern ABZEICHEN = java.util.regex.Pattern.compile("[^\\s\\d]?\\d{1,2}");

    private static boolean nurLateinisch(String s) {
        return s.chars().filter(Character::isLetter).allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN)
                && s.chars().anyMatch(Character::isLetter) && s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ');
    }

    private static boolean enthaeltKyrillisch(String s) {
        return s.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
    }

    static double lesartWert(String s) {
        boolean lateinisch = false, kyrillisch = false;
        int sonder = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                Character.UnicodeScript script = Character.UnicodeScript.of(c);
                if (script == Character.UnicodeScript.LATIN) lateinisch = true;
                else if (script == Character.UnicodeScript.CYRILLIC) kyrillisch = true;
            } else if (!Character.isDigit(c) && c != ' ' && c != '-' && c != '_' && c != '.' && c != '\'') {
                sonder++;
            }
        }
        double wert = 0.1 * s.length() - sonder;
        if (lateinisch && kyrillisch) wert -= 3;
        if (s.length() >= 4 && s.endsWith(".")) wert -= 2;
        return wert;
    }

    /** Die Namen hinter "alias:" in einer Notiz, durch Komma getrennt. */
    static List<String> aliasse(String notiz) {
        List<String> aus = new ArrayList<>();
        if (notiz == null) return aus;
        int i = notiz.indexOf("alias:");
        if (i < 0) return aus;
        String rest = notiz.substring(i + 6);
        int ende = rest.indexOf(' ');
        if (ende >= 0) rest = rest.substring(0, ende);
        for (String a : rest.split(",")) if (!a.isBlank()) aus.add(a.trim());
        return aus;
    }

    /**
     * Der bisherige Eintrag fuer name/clan -- oder eine vorgemerkte Notiz.
     *
     * Hat Autor per !notiz einen Namen notiert, bevor der Bot ihn je
     * getroffen hat, steht der Eintrag ohne Clan da. Trifft der Bot ihn nun
     * MIT Clan, zieht der Eintrag samt Notiz und Bilanz dorthin um.
     */
    private Notiz vorgemerkt(String key, String name, String clan) {
        Notiz alt = notizen.get(key);
        if (alt != null || clan == null || clan.isBlank()) return alt;
        String ohneClan = schluessel(name, "");
        Notiz v = notizen.get(ohneClan);
        // Seit 25.09. zieht JEDER clanlose Eintrag um, nicht nur eine blosse
        // Notiz: "ohne Clan" hiess in der Praxis "Clan nicht lesbar" -- der
        // Spieler ohne Clan ("Not in clan") kommt gar nicht erst ins Buch.
        if (v != null) {
            notizen.remove(ohneClan);
            return v;
        }
        return null;
    }

    /** "25,9 Mio" statt "25914235". */
    private static String kraftKurz(long s) {
        if (s <= 0) return "?";
        if (s >= 1_000_000) return String.format(java.util.Locale.GERMANY, "%.1f Mio", s / 1_000_000.0);
        return String.format(java.util.Locale.GERMANY, "%,d", s);
    }

    /**
     * Schreibt eine Datei so, dass sie nie halb fertig auf der Platte liegt.
     *
     * ------------------------------------------------------------------
     *  Warum der Umweg
     * ------------------------------------------------------------------
     *
     * Ein blosses Files.writeString oeffnet die richtige Datei, leert sie
     * und fuellt sie neu. Wird der Bot genau in diesem Augenblick beendet
     * -- und beendet wird er bei jedem Neustart hart --, steht dort ein
     * halber Notizblock. Beim naechsten Start faellt die abgeschnittene
     * Zeile weg, und alles danach ist verloren.
     *
     * Deshalb: erst vollstaendig in eine Nebendatei schreiben, dann in
     * einem Zug an die richtige Stelle schieben. Das Verschieben ist ein
     * einziger Schritt -- entweder es ist passiert oder nicht. Auf halbem
     * Weg abgebrochen bleibt schlimmstenfalls die alte Fassung stehen, und
     * die ist heil.
     */
    static void sicherSchreiben(Path ziel, String inhalt) throws java.io.IOException {
        Path neben = ziel.resolveSibling(ziel.getFileName() + ".neu");
        Files.writeString(neben, inhalt, StandardCharsets.UTF_8);
        try {
            Files.move(neben, ziel,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Manche Dateisysteme koennen das nicht. Dann eben ohne die
            // Zusicherung -- immer noch besser als direkt hineinzuschreiben.
            Files.move(neben, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Tabulatoren wuerden die Spalten zerreissen. */
    private static String sauber(String s) {
        // Auch Zeilenumbrueche: ein Name mit Umbruch (Discord-Nachricht ueber zwei Zeilen)
        // schriebe sonst eine eigene, kaputte Zeile in die Datei (Security-Review 01.10.).
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static long zahl(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (Exception e) {
            return -1;
        }
    }
}
