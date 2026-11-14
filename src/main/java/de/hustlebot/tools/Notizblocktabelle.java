package de.hustlebot.tools;

import de.hustlebot.core.Sprache;

import java.util.ArrayList;
import java.util.List;

/**
 * Liest den lesbaren Notizblock der Arena (arena-notizblock.txt, so wie Gegnerbuch.lesbar ihn schreibt)
 * in Zeilen fuer eine Tabelle (Auftrag 4: Notizblock als Tabelle, sortierbar). Gelesen wird dieselbe
 * Datei wie bisher -- was hier steht, ist genau das, wonach der Bot sich richtet.
 *
 * Ein Eintrag sieht so aus:
 *     -> Rina (6/0)
 *        Clan: ohne Clan
 *        Kraft: 21,5 Mio
 *        Zeit: 15 sek (Durchschnitt aus 6 Kaempfen)
 *        Punkte: 58 (mit Kraft 63 -- wegen etwas schwaecher)
 *        Arena-Punkte: 150, 150, 225
 *        zuletzt: S S S
 */
public final class Notizblocktabelle {

    /** Ein Gegner, wie er in der Tabelle steht. */
    public record Gegner(String name, String clan, String kraft, int siege, int niederlagen, String zeit,
                         String punkte, String arenaPunkte, String zuletzt) {
        public int kaempfe() {
            return siege + niederlagen;
        }

        /** Siegquote in Prozent, -1 ohne Kampf. */
        public int quote() {
            return kaempfe() == 0 ? -1 : (int) Math.round(100.0 * siege / kaempfe());
        }
    }

    /** S = Siege, N = Niederlagen (der Fuss des Fensters erklaert es), Quote in Prozent. */
    public static final String[] SPALTEN = {Sprache.t("Opponent", "Gegner"), "Clan", Sprache.t("Power", "Kraft"),
            Sprache.t("W", "S"), Sprache.t("L", "N"), Sprache.t("Win %", "Quote"), Sprache.t("Time", "Zeit"),
            Sprache.t("Points", "Punkte"), Sprache.t("Arena points", "Arena-Punkte"), Sprache.t("Last", "Zuletzt")};

    private Notizblocktabelle() {
    }

    /** Die erste Zeile der Datei ("Arena: 4 Gegner mit ..."), oder "". */
    public static String kopfzeile(String text) {
        if (text == null) return "";
        for (String z : text.split("\n")) {
            String s = z.strip();
            if (!s.isEmpty()) return s.startsWith("->") ? "" : s;
        }
        return "";
    }

    public static List<Gegner> lesen(String text) {
        List<Gegner> liste = new ArrayList<>();
        if (text == null) return liste;
        String name = null, clan = "", kraft = "", zeit = "", punkte = "", arena = "", zuletzt = "";
        int siege = 0, niederlagen = 0;
        for (String zeile : text.split("\n")) {
            String z = zeile.strip();
            if (z.startsWith("-> ")) {
                if (name != null) liste.add(new Gegner(name, clan, kraft, siege, niederlagen, zeit, punkte, arena, zuletzt));
                clan = ""; kraft = ""; zeit = ""; punkte = ""; arena = ""; zuletzt = ""; siege = 0; niederlagen = 0;
                String rest = z.substring(3).strip();
                int klammer = rest.lastIndexOf('(');
                name = klammer > 0 ? rest.substring(0, klammer).strip() : rest;
                if (klammer > 0 && rest.endsWith(")")) {
                    String[] sn = rest.substring(klammer + 1, rest.length() - 1).split("/");
                    siege = zahl(sn.length > 0 ? sn[0] : "");
                    niederlagen = zahl(sn.length > 1 ? sn[1] : "");
                }
                continue;
            }
            if (name == null) continue;
            int d = z.indexOf(':');
            if (d <= 0) continue;
            String feld = z.substring(0, d).strip().toLowerCase(java.util.Locale.ROOT);
            String wert = z.substring(d + 1).strip();
            switch (feld) {
                case "clan" -> clan = wert;
                case "kraft" -> kraft = wert;
                case "zeit" -> zeit = kurz(wert);
                case "punkte" -> punkte = punkteKurz(wert);
                case "arena-punkte" -> arena = wert;
                case "zuletzt" -> zuletzt = wert;
                default -> { }
            }
        }
        if (name != null) liste.add(new Gegner(name, clan, kraft, siege, niederlagen, zeit, punkte, arena, zuletzt));
        return liste;
    }

    /** "66 (mit Kraft 71 -- wegen etwas schwaecher)" -> "66 (71)"; "? (noch nicht geprueft)" -> "?". */
    static String punkteKurz(String wert) {
        int k = wert.indexOf('(');
        if (k < 0) return wert.strip();
        String vorn = wert.substring(0, k).strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mit Kraft (-?\\d+)").matcher(wert);
        return m.find() ? vorn + " (" + m.group(1) + ")" : vorn;
    }

    /** "15 sek (Durchschnitt aus 6 Kaempfen)" -> "15 s"; "? (nicht gelesen)" -> "?". */
    static String kurz(String wert) {
        String w = wert;
        int k = w.indexOf('(');
        if (k > 0) w = w.substring(0, k).strip();
        return w.replace(" sek", " s");
    }

    private static int zahl(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
