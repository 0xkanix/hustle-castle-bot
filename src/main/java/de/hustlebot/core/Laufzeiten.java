package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wie lange ein Durchlauf in jedem Modus im Schnitt dauert.
 *
 * ------------------------------------------------------------------
 *  Warum es das gibt
 * ------------------------------------------------------------------
 *
 * Autor will in !stats sehen, um wie viel Uhr seine Durchlaeufe fertig
 * sind:
 *
 *     "wenn ich !start eingebe und einen Modus dahinter, Beispiel
 *      !start cathedral 60, soll bei !stats nachdem 3 Runs abgeschlossen
 *      wurden, anhand von der durchschnittlichen Run time und der
 *      aktuellen Uhrzeit angezeigt werden um wie viel Uhr meine
 *      angegebenen Runs fertig sind."
 *
 * Gemessen wurde das schon immer -- aber nur fuer die laufende Kette.
 * Beim Neustart war alles weg. Fuer eine Kette wie
 *
 *     !start cathedral 60 b
 *     !after dungeon 10
 *     !after cathedral 25 b
 *
 * reicht das nicht: die zehn Verlies-Durchlaeufe kommen erst in Stunden
 * dran, und ohne Gedaechtnis koennte der Bot bis dahin nicht sagen, wie
 * lange die dauern werden. Autor auf die Frage, ob die Zeiten den
 * Neustart ueberleben sollen: "Ja, dauerhaft merken."
 *
 * ------------------------------------------------------------------
 *  Warum Summe und Anzahl statt eines fertigen Schnitts
 * ------------------------------------------------------------------
 *
 * Aus Summe und Anzahl laesst sich der Schnitt jederzeit ausrechnen, und
 * jeder neue Durchlauf laesst sich einfach dazuzaehlen. Umgekehrt geht es
 * nicht: aus einem gespeicherten Schnitt allein kann man keinen neuen
 * Schnitt bilden, ohne zu wissen, aus wie vielen Werten er stammt.
 *
 * ------------------------------------------------------------------
 *  Warum alte Messungen verblassen
 * ------------------------------------------------------------------
 *
 * Ohne Vergessen wuerde eine Messung vom Januar eine vom September
 * genauso stark beeinflussen. Das Spiel aendert sich aber, Autors Truppe
 * wird staerker, und Kaempfe gehen schneller. Deshalb werden Summe und
 * Anzahl halbiert, sobald genug Messungen beisammen sind -- die alten
 * verschwinden nicht schlagartig, sie zaehlen nur immer weniger.
 */
public final class Laufzeiten {

    private static final Path DATEI = Path.of("laufzeiten.txt");

    /**
     * Ab so vielen gemessenen Durchlaeufen traut der Bot dem Schnitt.
     *
     * Autors Wunsch: "nachdem 3 Runs abgeschlossen wurden". Bei einem
     * einzelnen Durchlauf verzieht eine Stoerung den Wert so stark, dass
     * die genannte Uhrzeit mehr schadet als hilft.
     */
    public static final int GENUG_MESSUNGEN = 3;

    /** Ab so vielen Messungen werden Summe und Anzahl halbiert. */
    private static final long VERBLASST_AB = 200;

    private static final DateTimeFormatter STEMPEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Was ueber einen Modus bekannt ist. */
    public record Messung(String modus, long summeSekunden, long anzahl, String zuletzt) {

        /** Mittlere Dauer in Sekunden. 0 heisst: noch nichts gemessen. */
        public long schnitt() {
            return anzahl <= 0 ? 0 : summeSekunden / anzahl;
        }

        public boolean aussagekraeftig() {
            return anzahl >= GENUG_MESSUNGEN;
        }
    }

    private final Map<String, Messung> messungen = new LinkedHashMap<>();

    public Laufzeiten() {
        laden();
    }

    // ==================================================================
    //  Eintragen und nachschlagen
    // ==================================================================

    /** Merkt einen fertigen Durchlauf. */
    public synchronized void merke(String modus, long sekunden) {
        if (modus == null || modus.isBlank() || sekunden <= 0) return;

        // Unsinnig lange Durchlaeufe nicht mitzaehlen.
        //
        // Bleibt der Bot haengen und laeuft die Leerlaufuhr ab, wird der
        // Durchlauf trotzdem irgendwann als fertig verbucht. Solche Werte
        // wuerden den Schnitt fuer immer verderben -- und damit auch jede
        // Uhrzeit, die daraus errechnet wird.
        if (sekunden > 3 * 3600) {
            Log.debug("Laufzeiten", "Ein Durchlauf von " + (sekunden / 60)
                    + " Minuten wird nicht mitgezaehlt -- da hat etwas geklemmt.");
            return;
        }

        Messung alt = messungen.get(modus);
        long summe = (alt == null ? 0 : alt.summeSekunden()) + sekunden;
        long anzahl = (alt == null ? 0 : alt.anzahl()) + 1;

        if (anzahl > VERBLASST_AB) {
            summe /= 2;
            anzahl /= 2;
        }

        messungen.put(modus, new Messung(modus, summe, anzahl,
                LocalDateTime.now().format(STEMPEL)));
        speichern();
    }

    /** Mittlere Dauer in diesem Modus, in Sekunden. 0 heisst: unbekannt. */
    public synchronized long schnitt(String modus) {
        Messung m = messungen.get(modus);
        return m == null ? 0 : m.schnitt();
    }

    /** Wie viele Durchlaeufe in diesem Modus je gemessen wurden. */
    public synchronized long anzahl(String modus) {
        Messung m = messungen.get(modus);
        return m == null ? 0 : m.anzahl();
    }

    /** Reicht das Gemessene, um eine Uhrzeit zu nennen? */
    public synchronized boolean weissBescheid(String modus) {
        Messung m = messungen.get(modus);
        return m != null && m.aussagekraeftig();
    }

    /** Alles Gemessene, fuer die Anzeige. */
    public synchronized List<Messung> alle() {
        return new ArrayList<>(messungen.values());
    }

    // ==================================================================
    //  Lesen und Schreiben
    // ==================================================================

    private void laden() {
        try {
            if (!Files.exists(DATEI)) return;
            for (String zeile : Dateien.zeilen(DATEI)) {
                if (zeile.isBlank() || zeile.startsWith("#")) continue;
                String[] t = zeile.split("\t", -1);
                if (t.length < 4) continue;
                long summe = zahl(t[1]);
                long anzahl = zahl(t[2]);
                if (summe <= 0 || anzahl <= 0) continue;
                messungen.put(t[0], new Messung(t[0], summe, anzahl, t[3]));
            }
            if (!messungen.isEmpty()) {
                Log.info("Laufzeiten", messungen.size()
                        + de.hustlebot.core.Sprache.t(" measured modes loaded -- the bot can tell right away when runs will be done.", " gemessene Modi geladen -- der Bot kann sofort sagen, "
                        + "wann Durchlaeufe fertig sind."));
            }
        } catch (Exception e) {
            Log.warn("Laufzeiten", Sprache.t("The run times could not be read: " + e.getMessage() + " -- measuring again.",
                    "Die Laufzeiten liessen sich nicht lesen: "
                    + e.getMessage() + " -- es wird neu gemessen."));
        }
    }

    private void speichern() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Wie lange ein Durchlauf je Modus dauert -- gemessen, nicht geschaetzt.\n");
        sb.append("# Daraus errechnet !stats, um wie viel Uhr deine Durchlaeufe fertig sind.\n");
        sb.append("# Ab ").append(GENUG_MESSUNGEN)
          .append(" Messungen wird eine Uhrzeit genannt, vorher steht dort nichts.\n");
        sb.append("#\n# Modus\tSekunden gesamt\tDurchlaeufe\tzuletzt\tSchnitt\n");
        for (Messung m : messungen.values()) {
            sb.append(m.modus()).append('\t')
              .append(m.summeSekunden()).append('\t')
              .append(m.anzahl()).append('\t')
              .append(m.zuletzt()).append('\t')
              .append("# ").append(m.schnitt() / 60).append(" min ")
              .append(m.schnitt() % 60).append(" s\n");
        }
        try {
            // Ueber die Nebendatei, damit ein harter Abbruch nie eine halbe
            // Datei hinterlaesst -- dieselbe Vorsicht wie beim Notizblock.
            Gegnerbuch.sicherSchreiben(DATEI, sb.toString());
        } catch (Exception e) {
            Log.warn("Laufzeiten", Sprache.t("The run times could not be written: " + e.getMessage(),
                    "Die Laufzeiten liessen sich nicht schreiben: "
                    + e.getMessage()));
        }
    }

    private static long zahl(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (Exception e) {
            return -1;
        }
    }
}
