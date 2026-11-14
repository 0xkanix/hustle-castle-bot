package de.hustlebot.tools;

import de.hustlebot.core.Sprache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Autor 22.10.: "Kannst du den Notizblock aufteilen? Es soll einen Notizblock geben, der nur Gegner beinhaltet, die der
 * Bot gescannt hat, und einen, wo Gegner drinstehen, die der Bot gescannt und angegriffen hat -- mit den jeweiligen
 * Zeiten, Punkten, Siegchance, Win or Lose."
 *
 * Gescannt: aus arena-daten.txt (dem Notizblock, nach dem der Bot waehlt) alle Gegner mit Pruefdatum.
 * Angegriffen: aus arena-lernen.txt (jeder Kampf: Schaetzung vorher, Ergebnis nachher) alle Kaempfe gegen gepruefte
 * Gegner (Team-Punkte bekannt), der neueste oben.
 */
public final class NotizblockListen {

    public static final Path DATEN = Path.of("arena-daten.txt");
    public static final Path KAEMPFE = Path.of("arena-lernen.txt");

    public static final String[] SPALTEN_GESCANNT = {Sprache.t("Opponent", "Gegner"), "Clan", Sprache.t("Power (M)", "Kraft (Mio)"),
            Sprache.t("Points", "Punkte"), Sprache.t("W", "S"), Sprache.t("L", "N"), Sprache.t("Units", "Einheiten"),
            Sprache.t("Builders", "Bauarbeiter"), Sprache.t("Scanned", "Gescannt am"), "Sets"};
    /** Spalten mit Zahlen (zum richtigen Sortieren). */
    public static final int[] ZAHLEN_GESCANNT = {3, 4, 5, 6, 7};

    public static final String[] SPALTEN_ANGEGRIFFEN = {Sprache.t("Time", "Zeit"), Sprache.t("Opponent", "Gegner"), "Clan",
            Sprache.t("Power (M)", "Kraft (Mio)"), Sprache.t("Points", "Punkte"), Sprache.t("Win chance %", "Siegchance %"),
            Sprache.t("Result", "Ergebnis"), Sprache.t("Duration (s)", "Dauer (s)"), Sprache.t("Arena points", "Arena-Punkte"),
            Sprache.t("Round", "Runde")};
    public static final int[] ZAHLEN_ANGEGRIFFEN = {4, 5, 7, 8, 9};

    private NotizblockListen() {
    }

    /** Alle gescannten Gegner aus arena-daten.txt (Spalte 8 = Pruefdatum), zuletzt gescannte oben. */
    public static List<Object[]> gescannt(Path datei) {
        List<Object[]> zeilen = new ArrayList<>();
        for (String z : zeilenLesen(datei)) {
            String[] t = z.split("\t", -1);
            if (t.length < 9 || t[0].isBlank() || t[8].isBlank()) continue;
            String verlauf = t[2];
            int siege = 0, nieder = 0;
            for (char c : verlauf.toCharArray()) {
                if (c == 'S') siege++;
                else if (c == 'N') nieder++;
            }
            zeilen.add(new Object[]{t[0], t[1], mio(t[3]), zahl(t[6]), siege, nieder,
                    t.length > 10 ? zahl(t[10]) : null, t.length > 11 ? zahl(t[11]) : null, t[8], t[7]});
        }
        zeilen.sort((a, b) -> String.valueOf(b[8]).compareTo(String.valueOf(a[8])));
        return zeilen;
    }

    /** Alle Kaempfe gegen gescannte Gegner (Team-Punkte bekannt) aus arena-lernen.txt, der neueste oben. */
    public static List<Object[]> angegriffen(Path datei) {
        List<Object[]> zeilen = new ArrayList<>();
        for (String z : zeilenLesen(datei)) {
            String[] t = z.split("\t", -1);
            if (t.length < 11 || !(t[10].equals("S") || t[10].equals("N"))) continue;
            Integer punkte = zahl(t[5]);
            if (punkte == null) continue;   // nicht gescannt
            zeilen.add(new Object[]{zeit(t[0]), t[1], t[2], mio(t[3]), punkte, zahl(t[8]),
                    t[10].equals("S") ? Sprache.t("Win", "Sieg") : Sprache.t("Loss", "Niederlage"),
                    t.length > 11 ? zahl(t[11]) : null, t.length > 12 ? zahl(t[12]) : null, t.length > 13 ? zahl(t[13]) : null,
                    t[0]});
        }
        zeilen.sort((a, b) -> String.valueOf(b[10]).compareTo(String.valueOf(a[10])));
        List<Object[]> ohneSchluessel = new ArrayList<>();
        for (Object[] r : zeilen) ohneSchluessel.add(java.util.Arrays.copyOf(r, 10));
        return ohneSchluessel;
    }

    private static List<String> zeilenLesen(Path datei) {
        List<String> aus = new ArrayList<>();
        try {
            if (!Files.exists(datei)) return aus;
            for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                if (!z.isBlank() && !z.startsWith("#")) aus.add(z);
            }
        } catch (Exception e) {
            // leer anzeigen
        }
        return aus;
    }

    /** "2026-10-22 14:13:05" -> "22.10. 14:13". */
    static String zeit(String s) {
        if (s == null || s.length() < 16) return s == null ? "" : s;
        return s.substring(8, 10) + "." + s.substring(5, 7) + ". " + s.substring(11, 16);
    }

    /** "29122289" -> "29,1". */
    static String mio(String s) {
        try {
            long v = Long.parseLong(s.trim());
            if (v > 0) return String.format(java.util.Locale.GERMANY, "%.1f", v / 1_000_000.0);
        } catch (NumberFormatException | NullPointerException e) {
            // leer
        }
        return "";
    }

    static Integer zahl(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
