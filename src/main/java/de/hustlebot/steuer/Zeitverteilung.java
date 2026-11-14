package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autor 24.10. (Ideenliste F30): "Wohin geht die Zeit?" in Klartext. Die Daten gibt es schon: der Zeitbericht nach jedem
 * Kathedralen-Run (Laden, Kaempfe, Segen, Wegwahl ... und "davon wartet der Bot") steht in kathedrale-zeiten.txt. Hier wird
 * er ueber die letzten Runs zusammengezaehlt: "Kaempfe 34 %, Wegwahl 16 % ... Der Bot wartet 53 % der Zeit" -- dazu ein Satz,
 * der sagt, ob das gut ist, und was man bei viel Warten pruefen kann (Spiel-Einstellungen, Virenschutz).
 *
 * Gelesen wird nur die Datei; am Bot aendert sich nichts. Beide Sprachen werden verstanden (der Bericht steht in der
 * Sprache, die beim Run eingestellt war).
 */
final class Zeitverteilung {

    private Zeitverteilung() {
    }

    /** Die letzten Runs zusammengezaehlt (Sekunden je Abschnitt, Abschnitte mit festem Namen). */
    record Verteilung(int runs, double gesamtSek, Map<String, Double> abschnitte, double wartetSek) {

        double anteil(double sek) {
            return gesamtSek <= 0 ? 0 : sek / gesamtSek;
        }

        double wartAnteil() {
            return anteil(wartetSek);
        }
    }

    /** Abschnitt (englisch oder deutsch, kleingeschrieben) -> fester Schluessel. */
    private static final Map<String, String> NAMEN = Map.ofEntries(
            Map.entry("loading", "laden"), Map.entry("laden", "laden"),
            Map.entry("start-up", "anlauf"), Map.entry("anlauf", "anlauf"),
            Map.entry("fights", "kampf"), Map.entry("kampf", "kampf"),
            Map.entry("blessings", "segen"), Map.entry("segen", "segen"),
            Map.entry("path choice", "wegwahl"), Map.entry("wegwahl", "wegwahl"),
            Map.entry("windows", "fenster"), Map.entry("fenster", "fenster"),
            Map.entry("floor change", "etage"), Map.entry("etagenwechsel", "etage"),
            Map.entry("completion", "abschluss"), Map.entry("abschluss", "abschluss"));

    private static final Pattern ABSCHNITT = Pattern.compile("^(.*?)\\s+(\\d+(?:[.,]\\d+)?)\\s*s(?:\\s*\\(.*\\))?$");
    private static final Pattern GESAMT = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*s\\b.*$");
    private static final Pattern WARTET = Pattern.compile("(?:of which the bot waits|davon Bot wartet)\\s+(\\d+(?:[.,]\\d+)?)\\s*s");

    private static double zahl(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    /** Liest die letzten {@code letzte} Zeilen der Datei (kathedrale-zeiten.txt); leer, wenn es sie nicht gibt. */
    static Verteilung lesen(Path datei, int letzte) {
        List<String> zeilen = new ArrayList<>();
        try {
            if (datei != null && Files.isRegularFile(datei)) {
                List<String> alle = Files.readAllLines(datei, StandardCharsets.UTF_8);
                zeilen = alle.subList(Math.max(0, alle.size() - Math.max(1, letzte)), alle.size());
            }
        } catch (Exception e) {
            zeilen = List.of();
        }
        return aus(zeilen);
    }

    /** Aus Zeilen ("ms TAB Datum  m:ss  123 s total | Laden 5 s | ... | davon Bot wartet 22 s") zusammenzaehlen. */
    static Verteilung aus(List<String> zeilen) {
        int runs = 0;
        double gesamt = 0, wartet = 0;
        Map<String, Double> sek = new LinkedHashMap<>();
        for (String zeile : zeilen) {
            int tab = zeile.indexOf('\t');
            String rest = tab >= 0 ? zeile.substring(tab + 1) : zeile;
            int erste = firstIndexOfTotal(rest);
            if (erste < 0) continue;
            String[] teile = rest.substring(erste).split("\\s\\|\\s");
            Matcher g = GESAMT.matcher(teile[0].strip());
            if (!g.matches()) continue;
            double summe = zahl(g.group(1));
            if (summe <= 0) continue;
            Map<String, Double> dieser = new LinkedHashMap<>();
            double dieserWartet = 0;
            for (int i = 1; i < teile.length; i++) {
                String t = teile[i].strip();
                Matcher w = WARTET.matcher(t);
                if (w.find()) {
                    dieserWartet = zahl(w.group(1));
                    continue;
                }
                Matcher a = ABSCHNITT.matcher(t);
                if (!a.matches()) continue;
                String schluessel = NAMEN.get(a.group(1).strip().toLowerCase(Locale.ROOT));
                if (schluessel != null) dieser.merge(schluessel, zahl(a.group(2)), Double::sum);
            }
            runs++;
            gesamt += summe;
            wartet += dieserWartet;
            for (var e : dieser.entrySet()) sek.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return new Verteilung(runs, gesamt, sek, wartet);
    }

    /** Wo in der Zeile die Gesamtzeit "123 s total" / "123 s gesamt" beginnt (nach "Datum  m:ss  "). */
    private static int firstIndexOfTotal(String s) {
        Matcher m = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*s\\s+(?:total|gesamt)\\b").matcher(s);
        return m.find() ? m.start() : -1;
    }

    private static String name(String schluessel) {
        return switch (schluessel) {
            case "laden" -> Sprache.t("loading", "Laden");
            case "anlauf" -> Sprache.t("start-up", "Anlauf");
            case "kampf" -> Sprache.t("fights", "Kämpfe");
            case "segen" -> Sprache.t("blessings", "Segen");
            case "wegwahl" -> Sprache.t("path choice", "Wegwahl");
            case "fenster" -> Sprache.t("windows", "Fenster");
            case "etage" -> Sprache.t("floor change", "Etagenwechsel");
            default -> Sprache.t("completion", "Abschluss");
        };
    }

    /** Der Text fuer die Statistik: Anteile und ein Satz dazu. */
    static String text(Verteilung v) {
        if (v.runs() < 3) {
            return Sprache.t("Too few cathedral runs measured yet (" + v.runs() + "). Play a few runs – then this shows where the time goes.",
                    "Noch zu wenige Kathedralen-Runs gemessen (" + v.runs() + "). Spiel ein paar Runs – dann steht hier, wohin die Zeit geht.");
        }
        List<Map.Entry<String, Double>> sortiert = new ArrayList<>(v.abschnitte().entrySet());
        sortiert.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder b = new StringBuilder();
        b.append(Sprache.t("Last " + v.runs() + " cathedral runs (average " + minuten(v.gesamtSek() / v.runs()) + "):",
                "Letzte " + v.runs() + " Kathedralen-Runs (Schnitt " + minuten(v.gesamtSek() / v.runs()) + "):")).append('\n');
        int n = 0;
        for (var e : sortiert) {
            if (n++ >= 5) break;
            long prozent = Math.round(100 * v.anteil(e.getValue()));
            if (prozent < 1) continue;
            b.append("  ").append(name(e.getKey())).append(' ').append(prozent).append(" %\n");
        }
        long wartet = Math.round(100 * v.wartAnteil());
        b.append(Sprache.t("The bot waits for the game ", "Der Bot wartet auf das Spiel ")).append(wartet)
                .append(Sprache.t(" % of the time.", " % der Zeit.")).append('\n');
        b.append(urteil(v.wartAnteil()));
        return b.toString();
    }

    /** Ein Satz: ist das gut, normal oder viel Warten (und was kann man pruefen)? */
    static String urteil(double wartAnteil) {
        if (wartAnteil < 0.35) {
            return Sprache.t("Good: your game answers quickly – the bot is about as fast as it can be.",
                    "Gut: dein Spiel antwortet schnell – der Bot ist so schnell, wie er sein kann.");
        }
        if (wartAnteil <= 0.60) {
            return Sprache.t("Normal: about half of the time the bot waits for animations and loading screens of the game.",
                    "Normal: etwa die Hälfte der Zeit wartet der Bot auf Animationen und Ladebilder des Spiels.");
        }
        return Sprache.t("A lot of waiting. Check: HD Graphics Off and V Sync Off in the game, close other programs, and let your "
                        + "virus protection ignore the bot folder (it can slow down every click).",
                "Viel Warten. Prüf: im Spiel HD Graphics Off und V Sync Off, andere Programme schließen, und den Virenschutz den "
                        + "Bot-Ordner ignorieren lassen (er kann jeden Klick bremsen).");
    }

    private static String minuten(double sek) {
        long s = Math.round(sek);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
