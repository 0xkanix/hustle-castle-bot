package de.hustlebot.steuer;

import de.hustlebot.core.Eventautomatik;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Zeitzone;

import java.util.Locale;
import java.util.Optional;

/**
 * Autor 24.10. (Ideenliste F25): Eingaben in den Einstellungen pruefen, BEVOR man "Setzen" drueckt. Bisher nahmen die
 * Felder Freitext und schickten ihn als "!settings ..." -- ein Tippfehler zeigte sich erst in der Antwortzeile.
 *
 *  - {@link #zahlenbereich}: Felder, die nur eine ganze Zahl in einem festen Bereich nehmen, werden ein Drehfeld
 *    (JSpinner) mit diesem Bereich -- falsch eintippen geht dann gar nicht.
 *  - {@link #fehler}: bei den uebrigen Feldern wird eine falsche Eingabe rot und sagt in einem Satz, was erwartet wird.
 *    Das ist nur ein Hinweis (der Bot prueft selbst): "Setzen" bleibt immer frei, damit eine zu strenge Pruefung nie etwas
 *    verhindert, was der Bot versteht.
 *
 * Die Regeln spiegeln, was die Discord-Bruecke versteht (DiscordBridge, "!settings ...").
 */
final class Eingabepruefung {

    private Eingabepruefung() {
    }

    /** Bereich eines Zahlenfelds: kleinster und groesster Wert, Schrittweite der Pfeile. */
    record Zahlenbereich(int min, int max, int schritt) {
    }

    /** Das Drehfeld zu einem Befehl (ohne Wert), oder null, wenn das Feld mehr als eine Zahl nimmt. */
    static Zahlenbereich zahlenbereich(String befehl) {
        return switch (Einstellungswerte.normal(befehl)) {
            case "!settings hammerevent", "!settings titanevent" -> new Zahlenbereich(0, 500, 10);   // 0 = nur wenn Schluessel/Kisten alle
            case "!settings hammerstages" -> new Zahlenbereich(0, 500, 1);
            case "!settings lupen" -> new Zahlenbereich(0, 10, 1);
            default -> null;
        };
    }

    /**
     * Was an der Eingabe nicht stimmt, in einem Satz -- leer, wenn sie stimmt, wenn das Feld leer ist (leer = nur anzeigen)
     * oder wenn es zu diesem Befehl keine Regel gibt.
     */
    static Optional<String> fehler(String befehl, String eingabe) {
        String e = eingabe == null ? "" : eingabe.strip();
        if (e.isEmpty()) return Optional.empty();
        String b = Einstellungswerte.normal(befehl);
        String k = e.toLowerCase(Locale.ROOT);

        Zahlenbereich z = zahlenbereich(b);
        if (z != null) {
            return zahlImBereich(k, z.min(), z.max());
        }
        switch (b) {
            case "!settings lager": {
                long n = menge(k);
                if (n >= 100_000 && n <= 2_000_000) return Optional.empty();
                return Optional.of(Sprache.t("Enter your storage size from 100000 to 2000000 (e.g. 2m).",
                        "Gib deine Lagergröße von 100000 bis 2000000 ein (z. B. 2m)."));
            }
            case "!settings nahrungslimit alle":
                return mengeOk(k) ? Optional.empty() : Optional.of(Sprache.t(
                        "Enter an amount like 30m, 500k or 15000000 – or “off”.",
                        "Gib eine Menge ein wie 30m, 500k oder 15000000 – oder „aus“."));
            case "!settings pass":
                return Eventautomatik.passAusWort(k) != null ? Optional.empty() : Optional.of(Sprache.t(
                        "Choose gold, silver or none.", "Wähle gold, silber oder kein."));
            case "!settings eventautomatik":
                if (k.matches("an|aus|on|off|ja|nein|true|false|1|0|ein|titan.*|grab.*|excav.*|hammer.*|\\d{1,3}")) return Optional.empty();
                return Optional.of(Sprache.t("Enter on, off, titan, hammer – or a number of dungeon runs (0 to 999).",
                        "Gib an, aus, titan, hammer ein – oder eine Zahl Verlies-Runs (0 bis 999)."));
            case "!settings spielneustart":
                if (k.matches("an|aus|on|off|ja|nein|true|false|1|0|ein|jetzt|now")) return Optional.empty();
                return Optional.of(Sprache.t("Enter on, off or now.", "Gib an, aus oder jetzt ein."));
            case "!zeitzone":
                return Zeitzone.verstehen(e).isPresent() ? Optional.empty() : Optional.of(Sprache.t(
                        "Unknown time zone – use a name like Europe/Berlin or a city.",
                        "Unbekannte Zeitzone – nimm einen Namen wie Europe/Berlin oder eine Stadt."));
            default:
                return Optional.empty();
        }
    }

    private static Optional<String> zahlImBereich(String k, int min, int max) {
        if (!k.matches("\\d{1,9}")) {
            return Optional.of(Sprache.t("Please enter a whole number from " + min + " to " + max + ".",
                    "Bitte eine ganze Zahl von " + min + " bis " + max + " eingeben."));
        }
        long n = Long.parseLong(k);
        if (n < min || n > max) {
            return Optional.of(Sprache.t("The number must be between " + min + " and " + max + ".",
                    "Die Zahl muss zwischen " + min + " und " + max + " liegen."));
        }
        return Optional.empty();
    }

    /** Eine Menge fuer das Limit: "aus", "off", "0", eine Zahl mit k/m/mio ... ("ohne bis ..." gilt auch als Eingabe). */
    private static boolean mengeOk(String k) {
        String t = k.strip();
        if (t.matches("ohne.*|without.*|until.*")) return true;
        return menge(t) != -2;
    }

    /** Wie DiscordBridge.menge(): Menge in Stueck; -1 = aus/ohne Grenze, -2 = nicht verstanden. */
    static long menge(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (t.isEmpty() || t.equals("aus") || t.equals("off") || t.equals("0") || t.equals("kein") || t.equals("none")) return -1;
        if (t.startsWith("unendlich") || t.startsWith("unbegrenzt") || t.equals("inf") || t.equals("∞")
                || t.equals("infinite") || t.equals("unlimited")) return -1;
        t = t.replace("millionen", "m").replace("million", "m").replace("mio", "m");
        try {
            double faktor = t.endsWith("m") ? 1_000_000 : t.endsWith("k") ? 1_000 : 1;
            String zahl = t.replaceAll("[^0-9.]", "");
            if (!t.endsWith("m") && !t.endsWith("k")) zahl = zahl.replace(".", "");
            long n = Math.round(Double.parseDouble(zahl) * faktor);
            return n <= 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -2;
        }
    }
}
