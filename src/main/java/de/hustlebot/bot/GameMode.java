package de.hustlebot.bot;

import org.opencv.core.Mat;

import java.util.List;

/** Ein spielbarer Modus, etwa das Verlies oder die Dunkle Kathedrale. */
public interface GameMode {

    /** Kennung fuer die Weboberflaeche, etwa "dungeon". */
    String id();

    /** Anzeigename fuer die Weboberflaeche. */
    String displayName();

    /** Vorlagen, ohne die dieser Modus nicht starten kann. */
    List<String> requiredTemplates();

    /** Wird einmal vor dem ersten Durchlauf aufgerufen. */
    void reset();

    /**
     * Ist gerade ein sicherer Moment, um in die Burg zurueckzukehren (!burg)?
     *
     * Standard: ja. Modi mit einem langen Durchlauf -- das Verlies mitten im
     * Kampf -- sagen hier nein, damit der laufende Durchlauf erst zu Ende
     * gespielt (oder bei Stillstand abgebrochen) wird, bevor der Bot heimgeht.
     */
    default boolean darfHeim() {
        return true;
    }

    /**
     * Ist der Modus nach einem gezaehlten Durchlauf wirklich DRAUSSEN -- also
     * an einer Stelle, an der ein anderer Modus anfangen kann?
     *
     * Standard: ja. Das Verlies sagt nein, solange es noch beim Verlassen ist:
     * Es zaehlt den Durchlauf schon beim Klick auf "End journey", hinaus ist
     * es erst nach dem Abschlussfenster (27.09., 09:08 und 09:38: der
     * Clan-Krieg und der Leuchtturm fingen im Verlies an und gaben auf).
     *
     * Eigene Frage, nicht darfHeim(): Das sagt im Verlies auch dann ja, wenn
     * der Bot nur auf den Mitspieler wartet.
     */
    default boolean draussen() {
        return true;
    }

    /**
     * Darf jetzt gewechselt werden, obwohl der eigene Durchlauf-Zaehler steht?
     * Fuer Modi mit einem Teil, der anders zaehlt -- das Hammer-Event im
     * Kathedralen-Kreislauf zaehlt seine Stages auf einem eigenen Zaehler, und
     * der Motor wartete bis zu 15 Minuten (Nachtlauf 29.09.: Clan-Krieg 09:01
     * waere erst 09:16 drangekommen). Wird nur gefragt, solange ein Wechsel
     * angefordert ist.
     */
    default boolean wechselJetzt() {
        return false;
    }

    /**
     * Code-Pruefung 16.10.: Eine empfindliche Phase, in der der Motor NICHTS allgemein klicken darf -- vor allem,
     * waehrend eine Werbung laeuft (Nahrung per Video): Dort fuehrt jeder Klick in die Werbung (fremde Webseite /
     * Store, Belohnung weg). Der Modus steuert dann selbst, was passiert.
     */
    default boolean inEmpfindlicherPhase() {
        return false;
    }

    /**
     * Sichere Stellen zwischen zwei Runs, an denen das Spiel neu gestartet
     * werden darf -- zaehlt nur hoch. Das Hammer-Event im Kathedralen-Kreislauf
     * meldet hier seine fertigen Stages: Der Run-Zaehler steht dort still, und
     * der Spiel-Neustart (alle 2 Stunden) kaeme sonst erst nach dem Event.
     */
    default int sicherePunkte() {
        return 0;
    }

    /**
     * Ein Satz fuer Discord, wenn der Modus von sich aus aufhoert (FINISHED)
     * und es dafuer einen Grund gibt, den Autor wissen soll -- etwa "keine
     * Schluessel und keine Kisten mehr". Null heisst: nichts zu melden.
     *
     * Eigene Meldung, weil die Fertig-Karte nicht immer kommt: Laeuft danach
     * noch etwas aus der Warteschlange, schaltet der Motor nur weiter.
     */
    /**
     * Nach so vielen Sekunden ohne Fortschritt geht ein Fehlerbericht mit Bild an Autor. Autor 18.10.: "immer wenn der
     * Bot 20 Sekunden oder laenger still steht ... nicht nur in der Kathedrale, sondern ueberall (ausser im Titanen-Turm)".
     */
    default long haengerBerichtNachS() {
        return 20;
    }

    /**
     * Soll der Motor JETZT in die Burg gehen, und der Modus danach weiterlaufen? Einmalig: true nur beim ersten Fragen.
     * (Autor 22.10.: Verlies -- nimmt der Freund 3 Minuten nicht an, in die Burg und mit Fremden weiter.)
     */
    default boolean jetztInDieBurg() {
        return false;
    }

    /** Wartet der Modus gerade nur auf einen anderen Spieler? Dann kein Stillstands-Bericht (Autor 18.10.). */
    default boolean wartetNurAufAndere() {
        return false;
    }

    default String endeHinweis() {
        return null;
    }

    /**
     * Ein einzelner Arbeitsschritt. Wird vom Bot fortlaufend aufgerufen und soll
     * genau eine Aktion ausfuehren, nie eine ganze Kette.
     */
    Outcome step(Mat screen, BotContext ctx, RunTracker runs);

    /**
     * Zusaetzliche Zahlen, die nur diesen Modus betreffen. Sie erscheinen in
     * Discord unter "!stats" hinter den allgemeinen Werten.
     *
     * Die allgemeinen Zahlen (Durchlaeufe, Dauer, Tempo, Laufzeit, Ether) zaehlt
     * der Bot ohnehin fuer jeden Modus. Hier kommt nur hinein, was es woanders
     * nicht gibt – etwa "geschaffte Etagen" in der Kathedrale.
     *
     * Wer nichts Eigenes zu melden hat, laesst die Methode einfach weg:
     * dann bleibt die Liste leer und in Discord steht nichts Zusaetzliches.
     * Reihenfolge bleibt erhalten, deshalb eine LinkedHashMap.
     */
    default java.util.Map<String, String> extraStats() {
        return java.util.Map.of();
    }

    /**
     * Ein Auftrag, der nach diesem Modus angehaengt werden soll. weg wie beim !start-Befehl (Kathedrale: "c" = Turbo);
     * ohne Angabe "c" wie bisher.
     */
    record Folge(String modus, int runs, String weg, String plan) {
        public Folge(String modus, int runs) {
            this(modus, runs, "c", "");
        }

        public Folge(String modus, int runs, String weg) {
            this(modus, runs, weg, "");
        }

        /** plan: Portal-Plan-Text ("farm 80:550000 reserve 1000000"); leer = keiner. */
        public Folge {
            plan = plan == null ? "" : plan.strip();
        }

        /** Der Weg fuer die Warteschlange; leer -> "c" (so war es immer). */
        public String wegOderC() {
            return weg == null || weg.isBlank() ? "c" : weg;
        }
    }

    /**
     * Soll nach dem Ende (FINISHED) noch ein Auftrag folgen? Die Kathedrale im
     * Kreislauf der Event-Automatik meldet hier ihre Verlies-Runs (28.09.).
     * Der Motor holt das beim Ende ab und reicht es an die Warteschlange --
     * so gilt es genau fuer diesen einen Lauf und bleibt nie liegen.
     */
    default Folge folgeAuftrag() {
        return null;
    }

    /**
     * Mehrere Folge-Auftraege der Reihe nach, vorn in die Warteschlange (18.11.: z. B. haengen die Tages-Spielquests
     * Gem Bay, Verlies, Arena ... an). Standard: nur folgeAuftrag() -- fuer alle anderen Modi bleibt alles gleich.
     */
    default List<Folge> folgeAuftraege() {
        Folge f = folgeAuftrag();
        return f == null ? List.of() : List.of(f);
    }

    /** Ergebnis eines Arbeitsschritts. */
    enum Outcome {
        /** Es wurde etwas geklickt oder gezogen. */
        ACTED,
        /** Nichts zu tun, es wird auf das Spiel oder den Mitspieler gewartet. */
        WAITING,
        /** Der Bildschirm wurde nicht erkannt. */
        UNKNOWN,
        /** Alle gewuenschten Durchlaeufe sind fertig. */
        FINISHED,
        /**
         * Der Bot soll anhalten, aber NICHT aufgeben.
         *
         * Fuer Faelle, in denen ein Mensch etwas tun muss, bevor es
         * weitergehen kann -- etwa wenn sich Autor mit dem Handy
         * angemeldet hat und das Spiel den Rechner hinauswirft.
         * Zaehler und Warteschlange bleiben erhalten, mit !weiter geht
         * es weiter.
         */
        PAUSE,

        /** Der Bot kann nicht weiterarbeiten und stoppt. */
        FATAL
    }
}
