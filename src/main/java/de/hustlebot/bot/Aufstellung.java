package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Welcher Knopf beim Truppenaufstellen gedrueckt wird -- je Modus getrennt.
 *
 * Autor, 16.09.: "da soll dann ein fenster aufploppen wo man fuer bestimmte
 * moduse entscheiden kann ob der bot beim team einsetzen lieber auf deploy
 * best oder previous squad druecken soll. weil jeder das gerne anders hat."
 *
 * Der Aufstell-Bildschirm sieht in JEDEM Modus gleich aus: links "Deploy the
 * best", rechts "Previous Squad", darunter das gruene "Confirm" (Autors Bild
 * vom 16.09.). Der Bot drueckt den hier gewaehlten Knopf und dann Confirm.
 *
 * Umgestellt wird ueber Discord: !settings verlies previous
 * Gespeichert in aufstellung.properties, damit es einen Neustart uebersteht.
 */
public final class Aufstellung {

    public enum Wahl { BEST, PREVIOUS }

    /** Die Modi in der Reihenfolge, wie sie in der Karte stehen sollen. */
    private static final List<String> MODI = List.of(
            "dungeon", "cathedral", "arena", "clanwar_tower", "clanwar_outpost");

    /** Standard = wie es bisher war: ueberall "Deploy the best", nur der Aussenposten "Previous". */
    private static final Map<String, Wahl> WAHL = new LinkedHashMap<>();
    static {
        WAHL.put("dungeon", Wahl.BEST);
        WAHL.put("cathedral", Wahl.BEST);
        WAHL.put("arena", Wahl.BEST);
        WAHL.put("clanwar_tower", Wahl.BEST);
        WAHL.put("clanwar_outpost", Wahl.PREVIOUS);
    }

    private static Path datei = Path.of("aufstellung.properties");

    private Aufstellung() {
    }

    /**
     * Legt fest, wo gespeichert wird, und liest die Datei ein. Beim Hauptbot
     * ist das der Arbeitsordner, bei der Freunde-Fassung Ordner.bei(...).
     */
    public static synchronized void konfigurieren(Path speicherort) {
        if (speicherort != null) datei = speicherort;
        laden();
    }

    /** Gibt es diesen Modus? */
    public static boolean kennt(String modus) {
        return modus != null && WAHL.containsKey(modus);
    }

    public static synchronized Wahl wahl(String modus) {
        return WAHL.getOrDefault(modus, Wahl.BEST);
    }

    public static boolean istPrevious(String modus) {
        return wahl(modus) == Wahl.PREVIOUS;
    }

    public static synchronized void setzen(String modus, Wahl w) {
        if (!WAHL.containsKey(modus) || w == null) return;
        WAHL.put(modus, w);
        speichern();
        Log.info("Aufstellung", modus + " -> " + (w == Wahl.PREVIOUS ? "Previous Squad"
                                                                      : "Deploy the best"));
    }

    public static List<String> modi() {
        return MODI;
    }

    /** Anzeigename fuer die Karte, zweisprachig. */
    public static String anzeige(String modus) {
        return switch (modus) {
            case "dungeon" -> Sprache.t("Dungeon", "Verlies");
            case "cathedral" -> Sprache.t("Cathedral", "Kathedrale");
            case "arena" -> "Arena";
            case "clanwar_tower" -> Sprache.t("Clan war: tower", "Clan-Krieg: Turm");
            case "clanwar_outpost" -> Sprache.t("Clan war: outpost", "Clan-Krieg: Aussenposten");
            default -> modus;
        };
    }

    /**
     * Findet die Kennung eines Modus aus dem, was Autor tippt: "verlies",
     * "turm", "aussenposten" ... Leer, wenn nichts passt.
     */
    public static String ausText(String wort) {
        if (wort == null) return "";
        String w = wort.trim().toLowerCase(java.util.Locale.ROOT);
        if (w.contains("verlies") || w.contains("verliess") || w.contains("dungeon")) return "dungeon";
        if (w.contains("kathedral") || w.contains("cathedral") || w.contains("kirche")) return "cathedral";
        if (w.contains("arena") || w.contains("turnier")) return "arena";
        // Clan-Krieg: Turm gegen Aussenposten unterscheiden.
        boolean ck = w.contains("clan") || w.contains("krieg") || w.contains("war") || w.contains("cw");
        boolean aussen = w.contains("aussen") || w.contains("außen") || w.contains("outpost")
                || w.contains("titan");
        boolean turm = w.contains("turm") || w.contains("tower");
        if (aussen) return "clanwar_outpost";
        if (turm) return "clanwar_tower";
        if (ck) return "clanwar_tower";   // "clanwar" allein: der normale Turm
        return "";
    }

    /** Aus dem, was Autor tippt, eine Wahl machen. Null, wenn unklar. */
    public static Wahl wahlAusText(String wort) {
        if (wort == null) return null;
        String w = wort.trim().toLowerCase(java.util.Locale.ROOT);
        if (w.startsWith("prev") || w.contains("previous") || w.contains("letzt")
                || w.contains("alt")) {
            return Wahl.PREVIOUS;
        }
        if (w.startsWith("dep") || w.contains("best") || w.contains("beste")
                || w.contains("staerk") || w.contains("stärk")) {
            return Wahl.BEST;
        }
        return null;
    }

    // ------------------------------------------------------------------

    private static void laden() {
        try {
            if (!Files.exists(datei)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(datei)) {
                p.load(in);
            }
            for (String modus : MODI) {
                String v = p.getProperty(modus);
                if (v == null) continue;
                if (v.trim().equalsIgnoreCase("previous")) WAHL.put(modus, Wahl.PREVIOUS);
                else if (v.trim().equalsIgnoreCase("best")) WAHL.put(modus, Wahl.BEST);
            }
        } catch (Exception e) {
            Log.warn("Aufstellung", Sprache.t("aufstellung.properties is not readable: " + e.getMessage()
                    + " -- the defaults apply.",
                    "aufstellung.properties nicht lesbar: " + e.getMessage()
                    + " -- es gelten die Standardwerte."));
        }
    }

    private static void speichern() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Sprache.t("# Which button is pressed when deploying the squad.\n",
                                "# Welcher Knopf beim Truppenaufstellen gedrueckt wird.\n"));
            sb.append("# best = Deploy the best, previous = Previous Squad.\n");
            sb.append(Sprache.t("# Change in Discord:  !settings <mode> <best|previous>\n\n",
                                "# Umstellen in Discord:  !settings <modus> <best|previous>\n\n"));
            for (String modus : MODI) {
                sb.append(modus).append(" = ")
                  .append(WAHL.get(modus) == Wahl.PREVIOUS ? "previous" : "best").append('\n');
            }
            Files.writeString(datei, sb.toString());
        } catch (Exception e) {
            Log.warn("Aufstellung", Sprache.t("aufstellung.properties could not be saved: "
                    + e.getMessage() + " -- the change only lasts until the next restart.",
                    "aufstellung.properties liess sich nicht speichern: "
                    + e.getMessage() + " -- die Aenderung gilt nur bis zum Neustart."));
        }
    }
}
