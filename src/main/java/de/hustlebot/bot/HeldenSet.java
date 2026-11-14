package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helden-Set fuer den Clan-Krieg (Ben 17.10., ueber Autor): "Ben benutzt zum Clankrieg setzen immer ein anderes
 * Setup vom Helden. Vor jedem Clankrieg auf das Preset War druecken, und wenn er seine Truppen gesetzt hat, wieder in
 * den Helden zurueck und auf Normal druecken." Nur beim automatischen Aufstellen zu den Kriegszeiten, die Sets am
 * Namen erkannt. Schalter und Namen stehen in heldenset-krieg.txt (Standard: aus, "War" / "Normal").
 */
public final class HeldenSet {

    private HeldenSet() {}

    private static final Path DATEI = Ordner.bei("heldenset-krieg.txt");

    /** Ein automatischer Krieg wurde gerade eingereiht (Zeitpunkt) -- ClanWarMode.reset() holt es ab. */
    private static volatile long automatischUm = 0;
    private static final long GILT_MS = 30 * 60_000L;

    /** Von der Discord-Bruecke, wenn sie den Krieg zu den Kriegszeiten SELBST einreiht. */
    public static void automatischerKrieg() {
        automatischUm = System.currentTimeMillis();
    }

    /** Abholen (einmal): war der Krieg, der jetzt anfaengt, ein automatischer? */
    static boolean automatischAbholen() {
        long um = automatischUm;
        automatischUm = 0;
        return um > 0 && System.currentTimeMillis() - um < GILT_MS;
    }

    public static boolean an() {
        return "an".equals(wert("an", "aus"));
    }

    /**
     * Autor 29.10.: "Der Bot soll trotzdem mein Hero-Preset davor auswaehlen und wieder zurueck switchen" -- auch wenn der
     * Krieg von Hand / von der Kriegsautomatik ("!start clanwar") kommt. Nur mit "vonhand=an" in heldenset-krieg.txt
     * (Standard aus: bei den Freunden bleibt es beim automatischen Krieg, Ben 17.10.).
     */
    public static boolean auchVonHand() {
        return "an".equals(wert("vonhand", "aus"));
    }
    public static String vorher() {
        return wert("vorher", "War");
    }

    public static String nachher() {
        return wert("nachher", "Normal");
    }

    /**
     * Doppelte Absicherung (Autor 17.10.): an welcher Stelle im Set-Fenster das Set steht (1 = oben). Gewechselt wird
     * nur, wenn an DIESER Stelle der richtige Name steht.
     */
    public static int vorherPlatz() {
        return platz(wert("vorherplatz", "2"), 2);
    }

    public static int nachherPlatz() {
        return platz(wert("nachherplatz", "1"), 1);
    }

    private static int platz(String s, int standard) {
        try {
            int p = Integer.parseInt(s.strip());
            return p >= 1 && p <= 6 ? p : standard;
        } catch (NumberFormatException e) {
            return standard;
        }
    }

    public static void setAn(boolean an) {
        speichern(an, vorher(), vorherPlatz(), nachher(), nachherPlatz());
    }

    /** "War 2" = Name War auf Platz 2; ohne Zahl bleibt der Platz, wie er war. */
    public static void setVorher(String eingabe) {
        String[] np = nameUndPlatz(eingabe, vorherPlatz());
        speichern(an(), np[0], Integer.parseInt(np[1]), nachher(), nachherPlatz());
    }

    public static void setNachher(String eingabe) {
        String[] np = nameUndPlatz(eingabe, nachherPlatz());
        speichern(an(), vorher(), vorherPlatz(), np[0], Integer.parseInt(np[1]));
    }

    static String[] nameUndPlatz(String eingabe, int bisher) {
        String s = eingabe.strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*\\S)\\s+([1-6])$").matcher(s);
        if (m.matches()) return new String[]{m.group(1).strip(), m.group(2)};
        return new String[]{s, String.valueOf(bisher)};
    }

    public static String beschreibung() {
        return Sprache.t((an() ? "**On**" : "**Off**") + " -- before the automatic clan war (" + de.hustlebot.core.Kriegsplan.zeitenText(" / ") + ") the "
                        + "hero gets the set **" + vorher() + "** (slot " + vorherPlatz() + "), after deploying **" + nachher()
                        + "** (slot " + nachherPlatz() + ") again. Only if name AND slot match.",
                (an() ? "**An**" : "**Aus**") + " -- vor dem automatischen Clan-Krieg (" + de.hustlebot.core.Kriegsplan.zeitenText(" / ") + ") bekommt der "
                        + "Held das Set **" + vorher() + "** (Platz " + vorherPlatz() + "), nach dem Aufstellen wieder **" + nachher()
                        + "** (Platz " + nachherPlatz() + "). Nur wenn Name UND Platz passen.");
    }

    private static String wert(String schluessel, String standard) {
        try {
            if (!Files.exists(DATEI)) return standard;
            for (String z : de.hustlebot.core.Dateien.zeilen(DATEI)) {
                String s = z.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i > 0 && s.substring(0, i).strip().equalsIgnoreCase(schluessel)) {
                    String w = s.substring(i + 1).strip();
                    if (schluessel.equals("an")) return w.equalsIgnoreCase("an") || w.equalsIgnoreCase("on") ? "an" : "aus";
                    return w.isEmpty() ? standard : w;
                }
            }
        } catch (Exception e) {
            Log.debug("Helden-Set", DATEI + " nicht lesbar: " + e.getMessage());
        }
        return standard;
    }

    private static void speichern(boolean an, String vorher, int vorherPlatz, String nachher, int nachherPlatz) {
        try {
            Files.writeString(DATEI, "# Helden-Set fuer den automatischen Clan-Krieg (Einstellungen -> Squad and game restart)\n"
                    + "# Gewechselt wird nur, wenn an der Stelle (Platz, 1 = oben) genau dieser Name steht.\n"
                    + "an=" + (an ? "an" : "aus") + "\nvorher=" + vorher + "\nvorherplatz=" + vorherPlatz
                    + "\nnachher=" + nachher + "\nnachherplatz=" + nachherPlatz + "\n"
                    + (auchVonHand() ? "vonhand=an\n" : ""), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Helden-Set", Sprache.t("Could not save: ", "Nicht gespeichert: ") + e.getMessage());
        }
    }
}
