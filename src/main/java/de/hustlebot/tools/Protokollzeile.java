package de.hustlebot.tools;

import java.util.Locale;

/**
 * Eine Zeile des Protokolls, aufgeteilt in Uhrzeit, Stufe, Bereich und Text -- und die Frage, ob sie zu
 * Suche und Filter passt (Auftrag 4: Protokoll mit Farben je Stufe und Suche/Filter).
 *
 * Das Format steht in Log.java fest:
 *     22:21:37.587  [INFO] Arena: Turnier begonnen
 * Zeilen ohne [Stufe] (die Kopfzeile der Datei) bekommen die Stufe "" und den ganzen Text.
 */
public record Protokollzeile(String zeit, String stufe, String bereich, String text) {

    /** Welche Stufen gerade gezeigt werden. */
    public record Filter(boolean klicks, boolean meldungen, boolean warnungen, boolean fehler, boolean details) {
        public static final Filter ALLES_OHNE_DETAILS = new Filter(true, true, true, true, false);
    }

    public static Protokollzeile lesen(String zeile) {
        if (zeile == null) return null;
        String z = zeile.strip();
        if (z.isEmpty()) return null;
        int auf = z.indexOf('[');
        int zu = z.indexOf(']', auf + 1);
        if (auf < 0 || zu < 0) return new Protokollzeile("", "", "", z);
        String stufe = z.substring(auf + 1, zu).trim();
        String zeit = z.substring(0, auf).strip();
        String rest = z.substring(zu + 1).strip();
        String bereich = "";
        int doppel = rest.indexOf(": ");
        if (doppel > 0 && doppel < 20) {
            bereich = rest.substring(0, doppel);
            rest = rest.substring(doppel + 2);
        }
        return new Protokollzeile(zeit, stufe, bereich, rest);
    }

    /** Die Uhrzeit ohne Tausendstel: "22:21:37.587" -> "22:21:37". */
    public String zeitKurz() {
        int p = zeit.indexOf('.');
        return p > 0 ? zeit.substring(0, p) : zeit;
    }

    public boolean istKopfzeile() {
        return stufe.isEmpty();
    }

    public boolean wichtig() {
        return stufe.equals("WARN") || stufe.equals("ERROR");
    }

    /** Das Zeichen am Zeilenanfang -- Farbe allein reicht nicht, wenn man im Vorbeigehen draufschaut. */
    public String zeichen() {
        return switch (stufe) {
            case "ERROR" -> "!!";
            case "WARN" -> " !";
            case "ACTION" -> " >";
            default -> "  ";
        };
    }

    /** Passt die Zeile zu Filter und Suchwort (Suche ohne Gross/klein, in Bereich und Text)? */
    public boolean passt(Filter f, String suche) {
        if (istKopfzeile()) return suche == null || suche.isBlank();
        boolean stufeOk = switch (stufe) {
            case "ACTION" -> f.klicks();
            case "WARN" -> f.warnungen();
            case "ERROR" -> f.fehler();
            case "DEBUG" -> f.details();
            default -> f.meldungen();
        };
        if (!stufeOk) return false;
        if (suche == null || suche.isBlank()) return true;
        String s = suche.trim().toLowerCase(Locale.ROOT);
        return text.toLowerCase(Locale.ROOT).contains(s) || bereich.toLowerCase(Locale.ROOT).contains(s)
                || zeit.startsWith(s);
    }
}
