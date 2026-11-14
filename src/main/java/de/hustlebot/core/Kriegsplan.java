package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Der Zeitplan des Clan-Kriegs.
 *
 * Viermal am Tag beginnt ein Krieg, immer zu denselben Zeiten. Kurz danach
 * darf aufgestellt werden, und genau dann soll der Bot von selbst hingehen.
 *
 * Diese Klasse weiss nur, WANN etwas zu tun ist. Was zu tun ist, steht im
 * ClanWarMode; wer es anstoesst, ist die Discord-Bruecke. Getrennt gehalten,
 * weil sich Zeitlogik ohne laufendes Spiel pruefen laesst und der Rest nicht.
 *
 * ------------------------------------------------------------------
 *  Warum ein Fenster und kein Zeitpunkt
 * ------------------------------------------------------------------
 *
 * Um 21:01 laeuft womoeglich gerade ein Verlies. Der angefangene Durchlauf
 * wird zu Ende gespielt -- ihn abzubrechen hiesse, eine halb gelaufene
 * Fackel wegzuwerfen --, und das kann ein paar Minuten dauern. Ein exakter
 * Zeitpunkt waere dann verpasst und der Krieg gelaufen.
 *
 * Deshalb ist jede Kriegszeit der Anfang eines FENSTERS. Solange das offen
 * ist, gilt die Aufstellung als faellig. Fuenfzig Minuten sind reichlich
 * bemessen: sie ueberstehen einen langen Durchlauf, einen Neustart des
 * Rechners und selbst einen Spielabsturz, enden aber sicher vor der
 * naechsten Kriegszeit sechs Stunden spaeter.
 *
 * ------------------------------------------------------------------
 *  Doppelt aufstellen schadet nicht, doppelt losrennen schon
 * ------------------------------------------------------------------
 *
 * Jedes Fenster wird genau einmal angestossen. Ohne diese Sperre wuerde der
 * Zweisekundentakt der Bruecke fuenfzig Minuten lang immer wieder einen
 * Moduswechsel anfordern und der Bot kaeme nie ins Verlies zurueck.
 *
 * Der Vermerk lebt nur solange der Bot laeuft. Nach einem Neustart mitten im
 * Fenster wird also noch einmal hingegangen -- das ist Absicht und harmlos:
 * der ClanWarMode sieht die Schilder "Your squad" ueber den Tuermen und ist
 * sofort wieder fertig. Lieber einmal zu viel nachsehen als einen Krieg
 * verschlafen, weil der Rechner kurz aus war.
 */
public final class Kriegsplan {

    /**
     * Ben 20./21.10. (Berichte 44DK, Clan-Krieg 100 min zu spaet): Die Kriegszeiten sind BERLINER Spielzeit -- der Krieg
     * beginnt fuer alle Spieler im selben Augenblick. Mit eingestellter Zeitzone London lief der Bot eine Stunde zu spaet
     * los. Darum rechnet der Kriegsplan immer mit Berlin, egal welche Zeitzone eingestellt ist.
     */
    static LocalDateTime spielzeit() {
        return LocalDateTime.now(INTERN);
    }

    /**
     * Die vier Kriegszeiten in WELTZEIT (UTC). Das Spiel stellt die Uhr nicht um: Im Sommer begann das Aufstellen um 21:00
     * Berliner Zeit, seit der Umstellung am 25.10. um 20:00 (Autor 25.10.: "um 20 Uhr setzen und um 21.50 Uhr nochmal
     * rechecken ... in Zukunft auch auf Zeitumstellung achten"). In Weltzeit sind es immer 19:00 / 01:00 / 07:00 / 13:00
     * -- so rutscht der Plan im Maerz und im Oktober von selbst mit. Autor 25.10. spaet abends: "immer 20 Minuten
     * verzoegert" -- also 20:20 / 02:20 / 08:20 / 14:20 im Winter (eine Stunde zuvor hatte er noch 20:00 gewaehlt).
     */
    private static final List<LocalTime> ZEITEN = List.of(
            LocalTime.of(1, 20),
            LocalTime.of(7, 20),
            LocalTime.of(13, 20),
            LocalTime.of(19, 20));
    /**
     * Autor 18.10.: "10 Minuten bevor der Clan-Krieg beginnt (4:50, 10:50, 16:50, 22:50) noch einmal nachschauen, ob
     * mein Team wirklich gesetzt ist -- und beim richtigen Stuetzpunkt, wo mein Name steht. Steht nirgends ein Name,
     * egal wo, Hauptsache es steht." Ein zweiter, kurzer Lauf des Clan-Krieg-Modus (der sieht nach und setzt nach).
     * Ebenfalls Weltzeit. Autor 25.10.: "15 Minuten bevor ein Clankrieg beginnt ... rechecken, um 3.45 Uhr" -- im Winter
     * 3:45, 9:45, 15:45, 21:45 Berliner Zeit, bis Kriegsbeginn.
     */
    private static final List<LocalTime> KONTROLLE = List.of(
            LocalTime.of(2, 45),
            LocalTime.of(8, 45),
            LocalTime.of(14, 45),
            LocalTime.of(20, 45));
    private static final Duration KONTROLL_FENSTER = Duration.ofMinutes(15);

    /**
     * Innere Uhr des Kriegsplans (Autor 26.10.: "immer die jeweilige Zeitzone anschauen und die Zeitumstellungen
     * beruecksichtigen -- fehlerfrei"): feste Weltzeit+1 OHNE Sommerzeit. Mit Europe/Berlin gab es in der Nacht der
     * Zeitumstellung 02:00-03:00 zweimal -- beim ersten Mal (eine Stunde zu frueh) galt das Fenster schon als offen, und
     * "kein Krieg" haette es abgehakt. Eine feste Uhr laeuft immer nur vorwaerts. Angezeigt wird in der Zeitzone des
     * Spielers (Zeitzone.zone(): Ben London, Autor Berlin) -- im Winter ist die innere Uhr gleich der Berliner.
     */
    static final ZoneOffset INTERN = ZoneOffset.ofHours(1);

    /** Eine Zeit in Weltzeit am Tag 'datum' (Weltzeit) auf der inneren Uhr -- damit rechnet der Rest der Klasse. */
    static LocalDateTime intern(LocalDate datum, LocalTime weltzeit) {
        return datum.atTime(weltzeit).atOffset(ZoneOffset.UTC).withOffsetSameInstant(INTERN).toLocalDateTime();
    }

    /** Alle Zeiten der Liste vom Vortag bis zum Folgetag, in Berliner Uhrzeit. */
    private static List<LocalDateTime> umHerum(LocalDateTime jetzt, List<LocalTime> zeiten) {
        List<LocalDateTime> l = new ArrayList<>();
        for (int tag = -1; tag <= 1; tag++) {
            for (LocalTime z : zeiten) l.add(intern(jetzt.toLocalDate().plusDays(tag), z));
        }
        return l;
    }

    /** Die heutigen Aufstell-Zeiten fuer Texte, z. B. "02:01, 08:01, 14:01, 20:01" -- im Sommer eine Stunde spaeter. */
    public static String zeitenText(String trenner) {
        List<String> t = new ArrayList<>();
        LocalDate heute = spielzeit().toLocalDate();
        for (LocalTime z : ZEITEN) t.add(zeitText(intern(heute, z)));
        t.sort(null);
        return String.join(trenner, t);
    }

    /**
     * Wie lange nach einer Kriegszeit das Aufstellen noch moeglich ist.
     *
     * HIER STANDEN FUENFZIG MINUTEN. Das war zu kurz gegriffen -- ich hatte
     * die Spanne geschaetzt, nicht erfragt. Autor am 5. September:
     *
     *     "zwischen diesen zeiten, 3 bis 5, 9 bis 11, 15 bis 17 und 21 bis
     *      23 uhr soll der bot ... erst in clan krieg schauen ob ich meinen
     *      trupp schon gesetzt habe"
     *
     * Also zwei Stunden, nicht eine knappe. 119 Minuten ab 03:01 enden
     * punktgenau um 05:00 -- der Anfang bleibt bei :01, weil erst dann
     * aufgestellt werden kann.
     *
     * Das ist mehr als eine Zahl: Um 03:11 war der Bot losgelaufen und hatte
     * nichts gesetzt. Mit der alten Spanne waere um 03:51 Schluss gewesen,
     * obwohl in Wahrheit noch ueber eine Stunde Zeit war.
     */
    // Code-Review 23.10.: Seit dem Start um :20 (fec98c8) reichten 119 min bis :19 -- mitten in den Krieg (Beginn :00).
    // 25.10. spaet: Start um :20 (Autor "immer 20 Minuten verzoegert") -- 100 min enden genau mit dem Kriegsbeginn.
    private static final Duration FENSTER = Duration.ofMinutes(100);

    /** Anfang des Fensters, das schon angestossen wurde. */
    private LocalDateTime angestossen;

    /**
     * Hier steht, fuer welches Fenster die Aufstellung wirklich FERTIG ist.
     *
     * Eine Datei, kein Feld -- sie muss einen Neustart ueberleben.
     *
     * Der Unterschied zu "angestossen" ist wichtig. Angestossen heisst nur:
     * der Bot ist losgelaufen. Startet der Rechner mitten im Fenster neu,
     * SOLL er noch einmal nachsehen -- vielleicht ist er beim ersten Mal
     * abgestuerzt, und ein verschlafener Krieg waere aergerlich.
     *
     * Steht die Aufstellung aber (zweimal "Your squad" auf der Karte), gibt
     * es nichts mehr zu tun. Dann ist auch nach einem Neustart Ruhe bis zur
     * naechsten Kriegszeit. Autor: "erst wieder in den clan krieg schauen
     * um die naechste uhrzeit ... also das naechste mal erst um 3 uhr
     * nachts."
     */
    private static final Path VERMERK = Path.of("kriegsplan-erledigt.txt");

    /**
     * Hier steht, ob der Bot von selbst zum Clan-Krieg gehen darf.
     *
     * ------------------------------------------------------------------
     *  Warum es AUS anfaengt
     * ------------------------------------------------------------------
     *
     * Weil der Modus noch nicht geprueft ist. Autor am 4. September:
     *
     *     "kannst du die automatische clan kriegs setzen funktion nochmal
     *      aus machen / pausieren? da sie noch nicht ganz funktioniert
     *      will ich nicht das der bot morgen um 9 uhr automatisch im clan
     *      krieg setzt."
     *
     * Ein Selbstlaeufer, der um drei Uhr nachts losgeht, waehrend niemand
     * hinsieht, und dabei die Truppen falsch verteilt, ist schlimmer als
     * gar kein Selbstlaeufer -- aufstellen kann man je Krieg nur einmal.
     *
     * ------------------------------------------------------------------
     *  Warum eine Datei und kein blosses Feld
     * ------------------------------------------------------------------
     *
     * Ein Feld waere nach jedem Neustart wieder auf dem Ausgangswert. Der
     * Bot startet aber bei jeder Anmeldung neu -- und dann haette Autors
     * "aus" nur bis zum naechsten Hochfahren gehalten, ohne dass er es
     * merkt. Eine Einstellung, die stillschweigend zurueckspringt, ist
     * schlimmer als keine.
     *
     * Angeschaltet wird ueber Discord: !kriegsplan an
     */
    private static final Path SCHALTER = Path.of("kriegsplan-an.txt");

    private volatile boolean selbsttaetig = schalterLesen();

    /**
     * Sagt beim Start ins Protokoll, wie der Schalter steht.
     *
     * Vorher stand darueber nichts. Ob das selbsttaetige Aufstellen
     * ueberhaupt scharf ist, haette man erst daran gemerkt, dass um 09:01
     * nichts passiert -- und dann ist der Krieg vorbei. Eine Zeile beim
     * Start kostet nichts und beantwortet die Frage vorher.
     */
    public Kriegsplan() {
        Log.info("Clan-Krieg", Sprache.t("Automatic deployment: ", "Selbsttaetiges Aufstellen: ") + beschreibung());
    }

    public void setSelbsttaetig(boolean an) {
        this.selbsttaetig = an;
        schalterSchreiben(an);
    }

    public boolean istSelbsttaetig()        { return selbsttaetig; }

    /** Liest den Schalter. Fehlt die Datei, ist er an. */
    private static boolean schalterLesen() {
        try {
            // Autor 18.10.: "Der Schalter soll standardmaessig auf an sein." -- ohne Datei an; "aus" in der Datei = aus.
            if (!Files.exists(SCHALTER)) return true;
            // Code-Review 23.10.: "Aus"/"OFF", ein BOM (PowerShell 5.1) oder eine #-Zeile davor liessen ihn an.
            String wort = Dateien.text(SCHALTER).replace("﻿", "").lines()
                    .map(String::strip).filter(z -> !z.isEmpty() && !z.startsWith("#")).findFirst().orElse("")
                    .toLowerCase(java.util.Locale.ROOT);
            return !wort.startsWith("aus") && !wort.startsWith("off");
        } catch (Exception e) {
            // Im Zweifel aus. Ein Krieg, den der Bot verschlaeft, laesst
            // sich von Hand nachholen; eine falsche Aufstellung nicht.
            return false;
        }
    }

    private static void schalterSchreiben(boolean an) {
        try {
            Files.writeString(SCHALTER,
                    (an ? "an" : "aus") + "\n"
                    + "\n"
                    + Sprache.t("# Whether the bot goes to the clan war by itself and deploys the\n"
                    + "# squads -- at " + zeitenText(", ") + " (summer: one hour later).\n"
                    + "#\n"
                    + "# Switch in Discord:  !clanwar on   /   !clanwar off\n"
                    + "# The value is in the first line (an = on, aus = off).\n",
                      "# Ob der Bot von selbst zum Clan-Krieg geht und die Truppen\n"
                    + "# aufstellt -- um " + zeitenText(", ") + " (Sommerzeit: eine Stunde spaeter).\n"
                    + "#\n"
                    + "# Umschalten in Discord:  !clanwar an   /   !clanwar aus\n"
                    + "# Der Wert steht in der ersten Zeile.\n"),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Clan-Krieg", Sprache.t("The switch could not be saved: "
                    + e.getMessage() + " -- after a restart it is off again.",
                    "Der Schalter liess sich nicht speichern: "
                    + e.getMessage() + " -- nach einem Neustart steht er wieder auf aus."));
        }
    }

    /**
     * Steht gerade eine Aufstellung an?
     *
     * @return der Anfang des offenen Fensters, oder leer
     */
    public synchronized Optional<LocalDateTime> faellig() {
        return faellig(spielzeit());
    }

    /** Wie {@link #faellig()}, aber mit vorgegebener Uhrzeit -- zum Pruefen. */
    public synchronized Optional<LocalDateTime> faellig(LocalDateTime jetzt) {
        if (!selbsttaetig) return Optional.empty();

        LocalDateTime fenster = offenesFenster(jetzt);
        if (fenster == null) return Optional.empty();
        if (fenster.equals(angestossen)) return Optional.empty();
        if (erledigt(fenster)) return Optional.empty();
        return Optional.of(fenster);
    }

    /**
     * Steht JETZT noch eine Aufstellung aus?
     *
     * Der Unterschied zu {@link #faellig()}: Dort zaehlt auch, ob der Bot in
     * dieser Sitzung schon einmal losgelaufen ist -- damit der
     * Zweisekundentakt nicht fuenfzig Minuten lang immer wieder einen
     * Moduswechsel anfordert.
     *
     * Genau diese Sperre ist aber falsch, wenn Autor selbst etwas tippt. Am
     * 5. September um 03:11 war der Bot losgelaufen, hatte aber nichts
     * gesetzt (das Spiel stand auf einem fremden Bildschirm). Das Fenster
     * galt damit als "angestossen" -- und ein "!start verlies" haette den
     * Krieg endgueltig verschlafen, obwohl noch vierzig Minuten Zeit waren.
     *
     * Hier zaehlt deshalb nur das, was wirklich zaehlt: Ist ein Fenster
     * offen, und stehen die Truppen noch nicht?
     *
     * Der Schalter !kriegsplan aus gilt weiterhin -- wer das Aufstellen
     * abgeschaltet hat, will auch beim Tippen nicht hingeschickt werden.
     */
    public synchronized Optional<LocalDateTime> nochAufzustellen() {
        if (!selbsttaetig) return Optional.empty();
        LocalDateTime fenster = offenesFenster(spielzeit());
        if (fenster == null) return Optional.empty();
        if (erledigt(fenster)) return Optional.empty();
        return Optional.of(fenster);
    }

    /**
     * Vermerkt, dass fuer das gerade offene Fenster alles steht.
     *
     * Wird vom ClanWarMode gerufen, sobald beide Truppen auf der Karte zu
     * sehen sind. Ab da wird bis zur naechsten Kriegszeit nicht mehr
     * nachgesehen, auch nicht nach einem Neustart.
     */
    /**
     * Wie oft seit dem Start des Bots eine Aufstellung fertig geworden ist.
     *
     * Autor: "Beispiel ich lasse den bot von 0 in der Nacht bis 10 Uhr
     * laufen, dann muss bei !stats stehen Clan Krieg gesetzt 2. Da der bot
     * mein Team um 3 Uhr und um 9 Uhr gesetzt hat."
     *
     * Gezaehlt wird genau hier -- an der einen Stelle, an der eine
     * Aufstellung als wirklich fertig gilt. Ein Krieg, in dem etwas
     * schiefging, kommt nicht mit; dann steht dort eben 1 statt 2, und
     * genau das will Autor ja sehen.
     *
     * Statisch und ohne Datei: Die Zahl gehoert zur laufenden Sitzung, so
     * wie die Laufzeit und die Durchlaeufe daneben.
     */
    private static volatile int gesetzteKriege = 0;

    /** Wie viele Kriege der Bot in dieser Sitzung aufgestellt hat. */
    public static int gesetzteKriege() {
        return gesetzteKriege;
    }

    /**
     * Getrennt gezaehlt (Autor, 23.09.): Hat der Bot keine Aepfel fuer den
     * normalen Trupp, setzt er wenigstens den Titanen-Trupp -- dann soll in
     * !stats nur bei den Titanen eine 1 stehen.
     */
    private static volatile int gesetzteEinheiten = 0;
    private static volatile int gesetzteTitanen = 0;

    public static int gesetzteEinheiten() { return gesetzteEinheiten; }
    public static int gesetzteTitanen()   { return gesetzteTitanen; }

    /** Beim frischen Start einer Kette wieder bei null anfangen. */
    public static void zaehlerZuruecksetzen() {
        gesetzteKriege = 0;
        gesetzteEinheiten = 0;
        gesetzteTitanen = 0;
    }

    public static void aufstellungErledigt() {
        aufstellungErledigt(true, true);
    }

    /**
     * Code-Review 23.10.: Kein Krieg, Krieg laeuft schon oder ist vorbei -- dieses Fenster ist abgehakt, aber es wurde
     * nichts gesetzt (zaehlt nicht in !stats). Frueher hakte die Bruecke jeden Lauf als "beide gesetzt" ab.
     */
    public static void fensterAbhaken() {
        LocalDateTime fenster = offenesFenster(spielzeit());
        if (fenster == null) return;
        try {
            Files.writeString(VERMERK, fenster.toString());
        } catch (Exception e) {
            Log.debug("Clan-Krieg", "kriegsplan-erledigt.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /** @param einheiten der normale Trupp steht, @param titanen der Titanen-Trupp steht */
    public static void aufstellungErledigt(boolean einheiten, boolean titanen) {
        LocalDateTime fenster = offenesFenster(spielzeit());
        if (fenster == null) return;
        // Ben 21.10. (03:54 "Deployment ... is done", obwohl nichts gesetzt war): ohne einen einzigen Trupp NICHT als
        // erledigt vermerken -- dann sieht der Bot spaeter noch einmal nach (Kontrolle 10 min vor Beginn, Neustart).
        if (!einheiten && !titanen) {
            Log.warn("Clan-Krieg", Sprache.t("Nothing was deployed for the war at " + zeitText(fenster)
                    + " -- not marked as done, the bot looks again later.",
                    "Fuer den Krieg von " + zeitText(fenster) + " wurde nichts gesetzt -- nicht als erledigt vermerkt, "
                    + "der Bot sieht spaeter noch einmal nach."));
            return;
        }
        gesetzteKriege++;
        if (einheiten) gesetzteEinheiten++;
        if (titanen) gesetzteTitanen++;
        try {
            Files.writeString(VERMERK, fenster.toString());
            Log.info("Clan-Krieg", Sprache.t("Deployment for the war at " + zeitText(fenster)
                    + " is done. Next look at the next war time.",
                    "Aufstellung fuer den Krieg von " + zeitText(fenster)
                    + " ist erledigt. Naechster Blick zur naechsten Kriegszeit."));
        } catch (Exception e) {
            // Nicht schlimm: dann wird nach einem Neustart eben noch einmal
            // nachgesehen, und der Bot stellt fest, dass alles steht.
            Log.debug("Clan-Krieg", "Vermerk liess sich nicht schreiben: " + e.getMessage());
        }
    }

    /** Fuer welches Fenster die Aufstellung laut Datei fertig ist, oder null. */
    private static LocalDateTime gelesenerVermerk() {
        try {
            if (!Files.exists(VERMERK)) return null;
            String text = Dateien.text(VERMERK).trim();
            return text.isEmpty() ? null : LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;   // unlesbar heisst: lieber noch einmal nachsehen
        }
    }

    /** Vermerkt, dass fuer dieses Fenster losgelaufen wurde. */
    public synchronized void angestossen(LocalDateTime fenster) {
        this.angestossen = fenster;
    }

    /** Fuer welches Fenster schon wiederholt wurde, und wie oft. */
    private LocalDateTime wiederholtFuer;
    private int wiederholt = 0;
    /** So oft hoechstens wird ein Versuch je Krieg wiederholt. */
    private static final int WIEDERHOLEN_MAX = 3;

    /**
     * Der letzte Versuch ist gar nicht bis in die Burg gekommen (27.09.,
     * 09:08) -- er soll das Fenster nicht verbrauchen. Hebt die Sperre
     * "angestossen" fuer das offene Fenster auf, hoechstens dreimal je Krieg.
     *
     * @return true, wenn noch einmal losgelaufen werden darf
     */
    public synchronized boolean nochmalErlauben() {
        LocalDateTime fenster = offenesFenster(spielzeit());
        if (fenster == null || !fenster.equals(angestossen)) return false;
        if (erledigt(fenster)) return false;
        if (!fenster.equals(wiederholtFuer)) {
            wiederholtFuer = fenster;
            wiederholt = 0;
        }
        if (wiederholt >= WIEDERHOLEN_MAX) return false;
        wiederholt++;
        angestossen = null;
        return true;
    }

    /**
     * Der Anfang des gerade offenen Fensters, oder null.
     *
     * Geprueft wird auch der gestrige Abend, damit ein Fenster, das ueber
     * Mitternacht reicht, nicht verlorengeht. Bei den heutigen Zeiten kommt
     * das nicht vor -- aber es kostet nichts, und Zeiten aendern sich.
     */
    /** Fenster erledigt: der Vermerk ist gleich oder spaeter (eine Kontrolle um 4:50 deckt die Aufstellung von 3:20 mit ab). */
    private static boolean erledigt(LocalDateTime fenster) {
        LocalDateTime v = gelesenerVermerk();
        return v != null && !v.isBefore(fenster);
    }

    static LocalDateTime offenesFenster(LocalDateTime jetzt) {
        for (LocalDateTime start : umHerum(jetzt, KONTROLLE)) {
            if (!jetzt.isBefore(start) && jetzt.isBefore(start.plus(KONTROLL_FENSTER))) return start;
        }
        for (LocalDateTime start : umHerum(jetzt, ZEITEN)) {
            if (!jetzt.isBefore(start) && jetzt.isBefore(start.plus(FENSTER))) return start;
        }
        return null;
    }

    /** Laeuft gerade das Nachpruef-Fenster (15 Minuten vor Kriegsbeginn)? (Autor 25.10.: dann nur kurz nachsehen) */
    public static boolean istKontrolle(LocalDateTime jetzt) {
        for (LocalDateTime start : umHerum(jetzt, KONTROLLE)) {
            if (!jetzt.isBefore(start) && jetzt.isBefore(start.plus(KONTROLL_FENSTER))) return true;
        }
        return false;
    }

    public static boolean istKontrolleJetzt() {
        return istKontrolle(spielzeit());
    }

    /** Wann der naechste Krieg anfaengt -- fuer !status. */
    /**
     * Die naechste Kriegszeit in der ORTSZEIT des Spielers (fuer Spiel-Neustart und Zeitleiste, die mit Zeitzone.jetzt()
     * rechnen). Vorher bekamen sie die Berliner Zeit -- bei Ben (London) eine Stunde daneben.
     */
    public static LocalDateTime naechsteKriegszeitHier(LocalDateTime jetztHier) {
        java.time.ZoneId zone = Zeitzone.zone();
        // In der Nacht der Zeitumstellung gibt es eine Ortszeit zweimal -- dann gilt der Versatz, den die Zone JETZT hat.
        java.time.ZonedDateTime hier = java.time.ZonedDateTime.ofLocal(jetztHier, zone, zone.getRules().getOffset(java.time.Instant.now()));
        java.time.ZonedDateTime k = naechsteKriegszeitIn(hier.toInstant(), zone);
        return k == null ? null : k.toLocalDateTime();
    }

    /** Die naechste Kriegszeit nach dem Augenblick 'jetzt', in der Zone 'zone' -- eindeutig, auch bei der Zeitumstellung. */
    static java.time.ZonedDateTime naechsteKriegszeitIn(java.time.Instant jetzt, java.time.ZoneId zone) {
        LocalDateTime k = naechsteKriegszeit(LocalDateTime.ofInstant(jetzt, INTERN));
        return k == null ? null : k.atOffset(INTERN).atZoneSameInstant(zone);
    }

    /** Minuten bis zur naechsten Kriegszeit -- auf der inneren Uhr, ohne Ortszeit (fuer den Spiel-Neustart). */
    public static long minutenBisKrieg() {
        LocalDateTime jetzt = spielzeit();
        LocalDateTime k = naechsteKriegszeit(jetzt);
        return k == null ? Long.MAX_VALUE : Duration.between(jetzt, k).toMinutes();
    }

    static LocalDateTime naechsteKriegszeit(LocalDateTime jetzt) {
        LocalDateTime beste = null;
        for (LocalDateTime start : umHerum(jetzt, ZEITEN)) {
            if (start.isAfter(jetzt) && (beste == null || start.isBefore(beste))) beste = start;
        }
        return beste;
    }

    /** Kurzer Satz fuer Discord. */
    public synchronized String beschreibung() {
        if (!selbsttaetig) {
            return de.hustlebot.core.Sprache.t("off -- the bot does not go to the clan war by itself", "aus -- der Bot geht nicht von selbst zum Clan-Krieg");
        }
        LocalDateTime jetzt = spielzeit();
        LocalDateTime offen = offenesFenster(jetzt);
        if (offen != null && offen.equals(gelesenerVermerk())) {
            return de.hustlebot.core.Sprache.t("already deployed for the war at ", "steht schon fuer den Krieg von ") + zeitText(offen)
                 + de.hustlebot.core.Sprache.t(", next look at ", ", naechster Blick um ") + zeitText(naechsteKriegszeit(jetzt));
        }
        if (offen != null && !offen.equals(angestossen)) {
            return de.hustlebot.core.Sprache.t("due NOW (war at ", "JETZT faellig (Krieg von ") + zeitText(offen) + ")";
        }
        if (offen != null) {
            return de.hustlebot.core.Sprache.t("done for the war at ", "erledigt fuer den Krieg von ") + zeitText(offen);
        }
        return de.hustlebot.core.Sprache.t("next war at ", "naechster Krieg um ") + zeitText(naechsteKriegszeit(jetzt));
    }

    /** Eine Zeit der inneren Uhr als Uhrzeit des Spielers (Autor 26.10.: "die jeweilige Zeitzone"). */
    private static String zeitText(LocalDateTime t) {
        return zeitText(t, Zeitzone.zone());
    }

    static String zeitText(LocalDateTime intern, java.time.ZoneId zone) {
        LocalDateTime h = intern.atOffset(INTERN).atZoneSameInstant(zone).toLocalDateTime();
        return String.format("%02d:%02d", h.getHour(), h.getMinute());
    }
}
