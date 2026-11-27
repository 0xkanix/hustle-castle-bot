package de.hustlebot.schicht;

import de.hustlebot.core.ArenaSchalter;
import de.hustlebot.core.Sprache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Was der Bot gerade von der Arena-Pyramide weiss -- fuer die Arena-Schicht (Autor 28.10.).
 *
 * Der Arena-Modus legt hier nach jeder Zielwahl einen frischen Stand ab; die Schicht liest ihn und
 * zeichnet Rahmen und Seitenfenster. Nur ein Zettel zum Abholen: keine Rueckwirkung auf den Bot -- ausser den
 * Befehlen (Stufe 2), die Autor per Taste gibt und die der Arena-Modus selbst abholt (befehleNehmen).
 *
 * Nur mit arena-schicht.txt "an" im Bot-Ordner (Standard aus). Autor 28.10.: erst nur in seinem englischen
 * Bot, nicht bei den Freunden.
 */
public final class ArenaStand {

    private ArenaStand() {}

    /** Ist die Arena-Schicht eingeschaltet? (arena-schicht.txt, siehe ArenaSchalter) */
    public static boolean an() {
        return ArenaSchalter.an();
    }

    // Autor 01.11.: "Oben steht Stage 1/5, ich bin in Stage 2." Der Stand kommt erst nach dem Lesen aller Schilder (4-12 s) und nannte bis dahin die alte
    // Stage. Die Stage steht aber schon am Bild (billig zu lesen): der Arena-Modus meldet sie hier sofort, das Seitenfenster zeigt sie in der Kopfleiste.
    private static volatile int gelesenRunde;
    private static volatile long gelesenRundeUm;
    /** So alt darf die gemeldete Stage sein: eine Stage dauert zwei Minuten. */
    static final long GELESENE_RUNDE_MS = 3 * 60_000L;

    /** Der Arena-Modus meldet die Stage, die er gerade am Bild sieht (1-5; anderes wird ignoriert). */
    public static void rundeGelesen(int runde) {
        if (runde < 1 || runde > 5) return;
        gelesenRunde = runde;
        gelesenRundeUm = System.currentTimeMillis();
    }

    /** Die Stage fuer die Kopfleiste: die frisch am Bild gelesene, sonst die des Standes. */
    public static int anzeigeRunde(int standRunde) {
        return anzeigeRunde(standRunde, gelesenRunde, gelesenRundeUm, System.currentTimeMillis());
    }

    static int anzeigeRunde(int standRunde, int gelesen, long gelesenUm, long jetzt) {
        return gelesen >= 1 && gelesen <= 5 && gelesenUm > 0 && jetzt - gelesenUm <= GELESENE_RUNDE_MS ? gelesen : standRunde;
    }

    /** Aelter als das gilt ein Stand nicht mehr -- die Pyramide ist dann wohl weg (gemeldet wird alle 1,5 s). */
    public static final long HALTBAR_MS = 5_000;   // 30.10.: 3 s liessen die Rahmen flackern, solange der Beobachter die Schilder liest; weg kommen sie jetzt ausdruecklich

    /**
     * Ein Gegner-Schild.
     *
     * @param x            Mitte der "Reward:"-Zeile, Spielbild-Punkte
     * @param y            Oberkante der "Reward:"-Zeile
     * @param platz        Platz in der Pyramide (1-10), aus der Lage
     * @param stufe        "sicher", "machbar", "meiden", "niemals" oder "" (keine Liste)
     * @param autoListe    stammt die Stufe aus dem Notizblock (2 von 3 gewonnen) statt aus einem Bild?
     * @param belohnung    150 oder 225
     * @param siegchance   in Prozent, -1 = nicht berechnet
     * @param erklaerung   woher die Siegchance kommt (Teile mit ", " getrennt)
     * @param listen       Listen-Posten mit Punkten, z. B. "SAFE WIN (-40)"
     * @param steckbrief   was der Bot ueber ihn weiss (Bilanz, Dauer, Punkte, Kraft), je Zeile ein Posten; "" = nichts
     */
    public record Gegner(int x, int y, int platz, String name, String clan, boolean ohneClan, String stufe,
                         boolean clanListe, boolean autoListe, long belohnung, int siegchance, String erklaerung,
                         String listen, boolean ziel, String steckbrief) {

        /** Ohne Steckbrief. */
        public Gegner(int x, int y, int platz, String name, String clan, boolean ohneClan, String stufe,
                      boolean clanListe, boolean autoListe, long belohnung, int siegchance, String erklaerung,
                      String listen, boolean ziel) {
            this(x, y, platz, name, clan, ohneClan, stufe, clanListe, autoListe, belohnung, siegchance, erklaerung, listen, ziel, "");
        }

        /** Derselbe Gegner, aber nicht das Ziel des Bots. */
        public Gegner ohneZiel() {
            return !ziel ? this : new Gegner(x, y, platz, name, clan, ohneClan, stufe, clanListe, autoListe, belohnung, siegchance, erklaerung, listen, false, steckbrief);
        }

        /**
         * Anzeigename. Autor 01.11.: "lieber 'Name nicht lesbar' als ein Bruchstueck" -- Namen mit weniger als zwei Buchstaben/Ziffern ("d", "y", "д") sind
         * nur Reste einer misslungenen Lesung (Bild im Wechsel, Windows-Lesung ohne Treffer) und werden nicht angezeigt. Nur die Anzeige: name() bleibt, wie gelesen.
         */
        public String anzeige() {
            if (!lesbar(name)) return Sprache.t("(name not readable)", "(Name nicht lesbar)");
            return name;
        }
    }

    /** Hat der gelesene Name mindestens zwei Buchstaben oder Ziffern? */
    public static boolean lesbar(String name) {
        if (name == null) return false;
        if (name.strip().startsWith("Bild-")) return false;   // Autor 01.11.: nie "Bild-1024-032130" anzeigen -- das ist nur die Kennung des Namensbildes
        return name.codePoints().filter(Character::isLetterOrDigit).count() >= 2;
    }

    /**
     * Ein Platz der Pyramide fuer die Rahmen -- ALLE Gegner, auch die schon besiegten ("Victory!" statt "Reward:"),
     * ohne das eigene Schild.
     *
     * @param x     Mitte der Punktezeile (Schwerter + Zahl), Spielbild-Punkte
     * @param y     Hoehe der Punktezeile
     * @param stufe "sicher", "machbar", "meiden", "niemals" oder "" (unbekannt: grau)
     * @param besiegt true = am Bild als besiegt erkannt (kein "Reward:"); false = unsicher (wird noch gelesen) -- nur fuer die Anzeige
     */
    public record Platz(int platz, int x, int y, String stufe, boolean besiegt) {
        /** Ohne Angabe gilt: wo kein Gegner angreifbar ist, ist er besiegt (wie bisher). */
        public Platz(int platz, int x, int y, String stufe) {
            this(platz, x, y, stufe, true);
        }
    }

    /** Der ganze Stand: wann, welche Runde, wie gross das Spielbild war, die angreifbaren Gegner und alle Plaetze. */
    public record Stand(long um, int runde, int bildBreite, int bildHoehe, List<Gegner> gegner, List<Platz> plaetze) {
        public Stand {
            gegner = List.copyOf(gegner);
            plaetze = List.copyOf(plaetze);
        }

        /** Derselbe Stand ohne Bot-Ziel (Autor hat in dieser Stage schon angegriffen). */
        public Stand ohneZiel() {
            return new Stand(um, runde, bildBreite, bildHoehe, gegner.stream().map(Gegner::ohneZiel).toList(), plaetze);
        }
    }

    /** Autor 30.10.: "Wenn ich in dieser Stage schon angegriffen habe, kann er mir keinen anderen mehr vorschlagen." Das Spiel zeigt dann "Next stage in". */
    private static volatile boolean stageFertig;

    public static void stageFertig(boolean fertig) {
        stageFertig = fertig;
    }

    public static boolean stageFertig() {
        return stageFertig;
    }

    /**
     * Autor 30.10.: "Ich soll die ganzen 5 Stages ueber agreeen oder disagreeen mit der Meinung des Bots." Der letzte Vorschlag je Stage bleibt
     * stehen, auch nach dem eigenen Angriff und solange die naechste Lesung laeuft. Nach dem Angriff gilt, was der Bot davor vorgeschlagen hat.
     */
    private static volatile Gegner letztesZiel;
    private static volatile int letztesZielRunde;
    private static volatile long letztesZielUm;
    /** Eine Stage dauert zwei Minuten: danach gilt der Vorschlag nicht mehr (dieselbe Stage einer neuen Arena). */
    static final long LETZTES_ZIEL_MS = 3 * 60_000L;

    private static void zielMerken(Stand s) {
        if (s == null) return;
        for (Gegner g : s.gegner()) {
            if (!g.ziel()) continue;
            if (stageFertig && letztesZiel != null && letztesZielRunde == s.runde()) return;
            letztesZiel = g;
            letztesZielRunde = s.runde();
            letztesZielUm = System.currentTimeMillis();
            return;
        }
    }

    /** Der letzte Vorschlag des Bots in dieser Stage, oder null. */
    public static Gegner letztesZiel(int runde) {
        Gegner g = letztesZiel;
        return g != null && letztesZielRunde == runde && System.currentTimeMillis() - letztesZielUm < LETZTES_ZIEL_MS ? g : null;
    }

    private static volatile Stand stand;

    /**
     * Die Arena ist fuer den Bot vorbei (Arena-Modus zu Ende oder ein anderer Modus spielt): Stand weg, und auch das Seitenfenster
     * darf den letzten Stand nicht mehr zehn Minuten weiterzeigen (23:12 am 20.11.: Fenster blieb bis 23:21 neben einem anderen Modus offen).
     */
    private static volatile long beendetUm;

    public static void beenden() {
        beendetUm = System.currentTimeMillis();
        setzen(null);
    }

    /** Wurde die Arena beendet, nachdem dieser Stand entstanden ist? Dann gilt er nicht mehr. */
    public static boolean beendetNach(long standUm) {
        return beendetUm != 0 && beendetUm >= standUm;
    }


    /**
     * Autor 29.10.: "Wenn ich den Chat ingame oeffne innerhalb einer Arena, kann ich nichts schreiben und es ist immer
     * noch offen." Sieht der Bot den offenen Chat (Pfeil "chat_close" am rechten Chat-Rand), verschwindet die Schicht und
     * schluckt keine Taste -- fuer 4 s nach dem letzten Blick auf den offenen Chat.
     */
    private static volatile long chatOffenUm;
    public static final long CHAT_GEDULD_MS = 4_000;

    public static void chatOffen() {
        chatOffenUm = System.currentTimeMillis();
        stand = null;
    }

    public static boolean chatGeradeOffen() {
        return System.currentTimeMillis() - chatOffenUm < CHAT_GEDULD_MS;
    }

    /** Autor 30.10.: "Wenn ich den Chat wieder schliesse, dauert das vielleicht 5 Sekunden." Der Beobachter sieht den Chat nicht mehr: sofort zurueck. */
    public static void chatZu() {
        chatOffenUm = 0;
    }

    public static void setzen(Stand neu) {
        boolean wechsel = (stand == null) != (neu == null);
        stand = neu;
        zielMerken(neu);
        Runnable r = beiWechsel;
        if (wechsel && r != null) r.run();
    }

    /** Der zuletzt gesetzte Stand, auch wenn die Schicht gerade kurz aus ist (kurzAus). */
    public static Stand zuletztGesetzt() {
        return stand;
    }

    /** Wird gerufen, wenn der Stand gerade verschwunden oder erschienen ist: die Schicht zeichnet dann sofort, nicht erst im naechsten Takt. Darf nicht blockieren. */
    public static void beiWechsel(Runnable r) {
        beiWechsel = r;
    }

    /** Autor 30.10.: Klick auf einen Spieler: die Rahmen sofort weg. Kommt kein Vergleichsfenster, sind sie danach von selbst wieder da. */
    public static void kurzAus(long ms) {
        ausBis = System.currentTimeMillis() + ms;
    }

    /**
     * Autors linker Mausklick im Spielfenster (Autor 30.10.: "Er kann ja meine Klicks nachvollziehen").
     *
     * @param fx    Lage im Spielfenster als Anteil der Breite (0-1)
     * @param fy    dasselbe fuer die Hoehe
     * @param platz der angeklickte Pyramiden-Platz, 0 = keiner
     * @param name  Name auf diesem Platz, wie die Schicht ihn kennt ("" = unbekannt)
     * @param clan  dasselbe fuer den Clan
     */
    public record Klick(long um, double fx, double fy, int platz, String name, String clan) { }

    public static void klickMelden(Klick k) {
        klick = k;
        klickWecker.drainPermits();
        klickWecker.release();
    }

    public static Klick letzterKlick() {
        return klick;
    }

    /** Wartet hoechstens so lange; ein Klick beendet das Warten sofort. */
    public static void aufKlickWarten(long ms) throws InterruptedException {
        klickWecker.tryAcquire(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static volatile Runnable beiWechsel;
    private static volatile long ausBis;
    private static volatile Klick klick;
    private static final java.util.concurrent.Semaphore klickWecker = new java.util.concurrent.Semaphore(0);

    /**
     * Den letzten Stand weiter gelten lassen (Pyramide noch zu sehen, der Bot wartet oder erkundet) -- nur die Zeit wird
     * erneuert (Autor 29.10.: Schicht in jeder Stage, solange die Pyramide da ist). Gilt nur fuer dieselbe Runde.
     */
    public static synchronized boolean auffrischen(int runde) {
        Stand s = stand;
        if (s == null || s.runde() != runde) return false;
        stand = new Stand(System.currentTimeMillis(), s.runde(), s.bildBreite(), s.bildHoehe(), s.gegner(), s.plaetze());
        return true;
    }

    /** Der aktuelle Stand, oder null, wenn keiner da oder er zu alt ist. */
    public static Stand aktuell() {
        Stand s = stand;
        long jetzt = System.currentTimeMillis();
        if (s == null || jetzt < ausBis || jetzt - s.um() > HALTBAR_MS) return null;
        return s;
    }

    // ---- Befehle von Autor an den Bot (Stufe 2, Autor 28.10.) -------------------------------------------------

    /**
     * Was Autor per Taste/Maustaste an der Pyramide entschieden hat. Die Schicht legt es hier ab, der Arena-Modus
     * holt es mit befehleNehmen() ab und setzt es um (Namensbild ausschneiden, Liste setzen, neu waehlen ...).
     *
     * <ul>
     *   <li>LISTE: platz, wert = "sicher" / "machbar" / "meiden" / "niemals" / "neutral" (= von allen Listen),
     *       text = Name, wie die Schicht ihn sah ("" = unbekannt) -- zum Gegenpruefen.</li>
     *   <li>CLAN: platz, wert = "stark" (+8) / "schwach" (-4) / "nichts", text = Name.</li>
     *   <li>ZURUECK: Ruecktaste -- macht eine gespeicherte LISTE/CLAN-Aenderung rueckgaengig. wert = der ALTE Wert,
     *       der wieder gelten soll (aus der Wertemenge erkennbar, ob Liste oder Clan), platz und text wie dort.</li>
     *   <li>ZUSTIMMEN: Autor findet das Bot-Ziel gut. platz = Ziel, wert/text "".</li>
     *   <li>ABLEHNEN: zuerst sofort mit wert "" (= dieses Ziel nicht, neu waehlen), danach -- wenn Autor einen Grund
     *       nennt -- ein zweites Mal mit wert = "zu_stark" / "starker_clan" / "anderer" / "sonstiges" und
     *       text = freier Grund ("" ohne).</li>
     *   <li>KOMMENTAR: platz (0 = keiner), wert = Name des Gegners, text = Autors Kommentar.</li>
     * </ul>
     *
     * @param platz Platz in der Pyramide (1-10), 0 = keiner
     * @param um    wann (Millisekunden)
     */
    public record Befehl(Art art, int platz, String wert, String text, long um) {
        public enum Art { LISTE, CLAN, ZURUECK, ZUSTIMMEN, ABLEHNEN, KOMMENTAR }

        public Befehl {
            if (art == null) throw new IllegalArgumentException("art");
            wert = wert == null ? "" : wert;
            text = text == null ? "" : text;
        }

        /** Ist wert ein Clan-Wert (stark/schwach/nichts)? Fuer ZURUECK: dann ist es eine Clan-Aenderung. */
        public boolean clanWert() {
            return "stark".equals(wert) || "schwach".equals(wert) || "nichts".equals(wert);
        }
    }

    /** Mehr bleibt nicht liegen -- holt der Arena-Modus lange nichts ab, fallen die aeltesten weg. */
    static final int BEFEHLE_HOECHSTENS = 200;

    private static final ConcurrentLinkedQueue<Befehl> befehle = new ConcurrentLinkedQueue<>();

    /** Legt einen Befehl ab. Jeder Faden darf rufen; blockiert nie (auch aus dem Eingabe-Haken). */
    public static void befehl(Befehl b) {
        if (b == null) return;
        befehle.add(b);
        while (befehle.size() > BEFEHLE_HOECHSTENS) befehle.poll();
    }

    /** Alle wartenden Befehle in der Reihenfolge, in der Autor sie gegeben hat; danach ist die Schlange leer. */
    public static List<Befehl> befehleNehmen() {
        List<Befehl> l = new ArrayList<>();
        Befehl b;
        while ((b = befehle.poll()) != null) l.add(b);
        return l;
    }
}
