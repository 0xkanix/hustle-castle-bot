package de.hustlebot.core;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Wann der Bot wieder in den Leuchtturm bzw. ins Kolosseum geht -- und ob
 * ueberhaupt. Je Ort ein eigener Plan (LEUCHTTURM, KOLOSSEUM).
 *
 * Autor, 26.09.: Immer wenn Verlies, Kathedrale oder Arena laufen, zuerst
 * nachsehen. Stehen 5/5 Energie da ("Max"), alle Kaempfe machen; sonst den
 * Modus weiterspielen und spaeter wiederkommen. Eine Energie waechst in 20
 * Minuten nach, also:
 *
 *   - nach den Kaempfen (Energie 0)  -> in 100 Minuten wieder hin (Autor:
 *     "alle 100 min")
 *   - nicht voll / Besuch gescheitert -> in 21 Minuten nachsehen
 *
 * "Nur Max erkennen und sonst alle 21 Minuten nachsehen" ergibt dieselben
 * Zeiten wie Autors Tabelle (4/5 -> 21, 3/5 -> 42 ...), ohne dass fuer jede
 * Zahl ein eigenes Bild aufgenommen werden muss (Autor: "Ja, alle 21 min").
 *
 * Diese Klasse weiss nur, WANN. Was vor Ort passiert, steht im
 * LighthouseMode; wer es anstoesst, ist die Discord-Bruecke (wie beim
 * Clan-Krieg). Der Schalter "an/aus" ueberlebt einen Neustart (eigene Datei
 * je Ort, gesetzt mit !settings). Die Uhr nicht: Nach einem Neustart wird
 * zuerst nachgesehen -- genau das, was Autor beim Start eines Modus will.
 */
public final class Energieplan {

    public static final Energieplan LEUCHTTURM =
            new Energieplan("Leuchtturm", Path.of("leuchtturm.txt"), "leuchtturm");
    /** Autor, 26.09.: "genau gleich wie der Leuchtturm, nur fuer Titanen". */
    public static final Energieplan KOLOSSEUM =
            new Energieplan("Kolosseum", Path.of("kolosseum.txt"), "kolosseum");

    /** Nach allen Kaempfen: 5 Energie zu je 20 Minuten -- Autor, 29.09.: "alle 105 min". */
    public static final int NACH_KAEMPFEN_MIN = 105;
    /** Nicht voll: in dieser Zeit waechst eine Energie nach. */
    public static final int NICHT_VOLL_MIN = 21;
    /** So viele Kaempfe gibt es hoechstens je Besuch (volle Energie). */
    public static final int KAEMPFE_JE_BESUCH = 5;

    // Zaehler seit dem letzten !start in einer Datei (Autor, 29.09.: "5 of 10" ueber
    // mehrere Besuche) -- sonst fingen sie bei jedem Spiel-Neustart bei null an.
    /** Steht VOR dem static-Block: der liest die Datei schon beim Laden der Klasse. */
    private static final Path FAELLIG_DATEI = Path.of("orte-faellig.txt");

    static {
        zaehlerLaden();
        faelligLaden();
    }

    private final String name;
    private final Schalterdatei schalter;
    private final String settingsWort;
    private volatile boolean an;
    /** Ab wann der naechste Besuch faellig ist. 0 = sofort. */
    private volatile long faelligAb = 0;

    private Energieplan(String name, Path datei, String settingsWort) {
        this.name = name;
        this.schalter = new Schalterdatei(datei, name, true);   // Standard: an
        this.settingsWort = settingsWort;
        this.an = schalter.lesen();
    }

    public String name() {
        return name;
    }

    /** Das Wort fuer "!settings <wort> an|aus". */
    public String settingsWort() {
        return settingsWort;
    }

    public boolean istAn() {
        return an;
    }

    /** Schalter aus !settings. Wird in der eigenen Datei gemerkt. */
    public void setAn(boolean neu) {
        an = neu;
        schalter.setzen(neu, name + " zwischen den Runs (an/aus), gesetzt mit !settings " + settingsWort);
    }

    /** Ist ein Besuch dran (Schalter an und Zeit erreicht)? */
    public boolean faellig() {
        return an && System.currentTimeMillis() >= faelligAb;
    }

    /**
     * Ein Besuch ist angestossen: nicht noch einmal anstossen. Kommt er nie
     * dran (etwa nach !stop), wird es in 30 Minuten neu versucht.
     */
    public void angestossen() {
        faelligAb = System.currentTimeMillis() + 30 * 60_000L;
        faelligSpeichern();
    }

    /** Naechsten Besuch in so vielen Minuten, mit Grund fuers Protokoll. */
    public void naechsterBesuchIn(int minuten, String grund) {
        faelligAb = System.currentTimeMillis() + minuten * 60_000L;
        faelligSpeichern();
        besuchGezaehlt();   // wird am Ende jedes Besuchs gerufen
        Log.info(name, grund + de.hustlebot.core.Sprache.t(" Next visit at ", " Naechster Besuch um ") + naechsterBesuch() + ".");
    }

    // --- Kaempfe und Siege fuer !stats (Autor, 27.09.) ------------------
    //
    // Gezaehlt ab dem letzten !start, wie die Zeilen zum Clan-Krieg.

    private volatile int kaempfe = 0;
    private volatile int siege = 0;
    private volatile int besuche = 0;
    // Seit der Bot laeuft (Autor, 28.09.: "3 von 3" sah nach einem einzigen
    // Besuch aus, obwohl der Bot an dem Tag dreimal dort war -- die Zahlen oben
    // zaehlen erst ab dem letzten !start).
    private volatile int besucheGesamt = 0, kaempfeGesamt = 0, siegeGesamt = 0;

    /** Besuche, in denen gekaempft wurde (fuer "3 von 5": je Besuch 5 moegliche Kaempfe). */
    private volatile int kampfBesuche = 0, kampfBesucheGesamt = 0;
    private volatile boolean kampfImBesuch;
    /**
     * Moegliche Kaempfe seit dem letzten !start: die abgelesene Energie je Besuch (30.09.: ein
     * Besuch mit 1/5 Energie zaehlte in !stats als 5 moegliche Kaempfe -- "3 von 10" statt "3 von 6").
     */
    private volatile int moeglich = 0;

    /** Beim Ablesen der Energie: so viele Kaempfe sind in diesem Besuch moeglich. */
    public synchronized void kaempfeMoeglich(int n) {
        moeglich += Math.max(0, n);
        zaehlerSpeichern();
    }

    /** Moegliche Kaempfe seit dem letzten !start (0 = noch nie abgelesen, dann gilt 5 je Besuch). */
    public int moegliche() { return moeglich; }

    /** Ein Kampf ist ausgegangen. */
    public synchronized void kampfGezaehlt(boolean sieg) {
        kaempfe++;
        kaempfeGesamt++;
        if (sieg) {
            siege++;
            siegeGesamt++;
        }
        kampfImBesuch = true;
        zaehlerSpeichern();
    }

    private synchronized void besuchGezaehlt() {
        besuche++;
        besucheGesamt++;
        if (kampfImBesuch) {
            kampfBesuche++;
            kampfBesucheGesamt++;
        }
        kampfImBesuch = false;
        zaehlerSpeichern();
    }

    public int kampfBesuche() { return kampfBesuche + (kampfImBesuch ? 1 : 0); }
    public int kampfBesucheGesamt() { return kampfBesucheGesamt + (kampfImBesuch ? 1 : 0); }

    public int kaempfe() { return kaempfe; }
    public int siege()   { return siege; }
    public int besuche() { return besuche; }
    public int besucheGesamt() { return besucheGesamt; }
    public int kaempfeGesamt() { return kaempfeGesamt; }
    public int siegeGesamt()   { return siegeGesamt; }

    /** Beim frischen Start einer Kette wieder bei null anfangen (beide Orte). */
    public static void zaehlerZuruecksetzen() {
        for (Energieplan p : new Energieplan[]{LEUCHTTURM, KOLOSSEUM}) {
            synchronized (p) {
                p.kaempfe = 0;
                p.siege = 0;
                p.besuche = 0;
                p.kampfBesuche = 0;
                p.kampfImBesuch = false;
                p.moeglich = 0;
            }
        }
        zaehlerSpeichern();
    }

    private static synchronized void zaehlerSpeichern() {
        StringBuilder b = new StringBuilder("# Leuchtturm/Kolosseum seit dem letzten !start (ueberlebt Neustarts)\n");
        for (Energieplan p : new Energieplan[]{LEUCHTTURM, KOLOSSEUM}) {
            if (p == null) return;   // waehrend die Klasse noch geladen wird
            b.append(p.name).append('=').append(p.kaempfe).append(',').append(p.siege).append(',')
             .append(p.besuche).append(',').append(p.kampfBesuche).append(',').append(p.moeglich).append('\n');
        }
        try {
            java.nio.file.Files.writeString(Path.of("orte-zaehler.txt"), b.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Energie", "orte-zaehler.txt nicht geschrieben: " + e.getMessage());
        }
    }

    private static void zaehlerLaden() {
        try {
            Path d = Path.of("orte-zaehler.txt");
            if (!java.nio.file.Files.exists(d)) return;
            for (String z : Dateien.zeilen(d)) {
                int g = z.indexOf('=');
                if (z.startsWith("#") || g <= 0) continue;
                String[] w = z.substring(g + 1).trim().split(",");
                if (w.length < 4) continue;
                for (Energieplan p : new Energieplan[]{LEUCHTTURM, KOLOSSEUM}) {
                    if (!p.name.equals(z.substring(0, g).trim())) continue;
                    p.kaempfe = Integer.parseInt(w[0].trim());
                    p.siege = Integer.parseInt(w[1].trim());
                    p.besuche = Integer.parseInt(w[2].trim());
                    p.kampfBesuche = Integer.parseInt(w[3].trim());
                    if (w.length >= 5) p.moeglich = Integer.parseInt(w[4].trim());
                }
            }
        } catch (Exception e) {
            Log.debug("Energie", "orte-zaehler.txt nicht lesbar: " + e.getMessage());
        }
    }

    /**
     * Wann der naechste Besuch faellig ist, steht in orte-faellig.txt (aus dem Hauptbot; Autor, 09.10.: "Nach
     * einem Spielneustart soll der Bot nicht Leuchtturm und Kolosseum spielen. Immer erst alle 105 Minuten").
     * Vorher stand die Zeit nur im Speicher: nach jedem Neustart war sofort ein Besuch dran (Autor 15.10.:
     * "wieso spielt der Bot jedes Mal Lighthouse und Kolosseum nach jedem Dungeon?").
     */
    private static synchronized void faelligSpeichern() {
        StringBuilder b = new StringBuilder("# Leuchtturm/Kolosseum: naechster Besuch ab (Millisekunden seit 1970)\n");
        for (Energieplan p : new Energieplan[]{LEUCHTTURM, KOLOSSEUM}) {
            if (p == null) return;   // waehrend die Klasse noch geladen wird
            b.append(p.name).append('=').append(p.faelligAb).append('\n');
        }
        try {
            java.nio.file.Files.writeString(FAELLIG_DATEI, b.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Energie", FAELLIG_DATEI + " nicht geschrieben: " + e.getMessage());
        }
    }

    private static void faelligLaden() {
        try {
            if (!java.nio.file.Files.exists(FAELLIG_DATEI)) return;
            for (String z : Dateien.zeilen(FAELLIG_DATEI)) {
                int g = z.indexOf('=');
                if (z.startsWith("#") || g <= 0) continue;
                for (Energieplan p : new Energieplan[]{LEUCHTTURM, KOLOSSEUM}) {
                    if (p.name.equals(z.substring(0, g).trim())) p.faelligAb = Long.parseLong(z.substring(g + 1).trim());
                }
            }
        } catch (Exception e) {
            Log.debug("Energie", FAELLIG_DATEI + " nicht lesbar: " + e.getMessage());
        }
    }

    /** Uhrzeit des naechsten Besuchs, fuer Protokoll und !settings. */
    public String naechsterBesuch() {
        long ab = faelligAb;
        if (ab <= System.currentTimeMillis()) return "sofort";
        return LocalTime.now().plusSeconds((ab - System.currentTimeMillis()) / 1000)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
