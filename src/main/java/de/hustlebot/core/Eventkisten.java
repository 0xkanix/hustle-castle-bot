package de.hustlebot.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Welche Kisten das Hammer-Event mitnimmt (Autor, 29.09.): "togglen, welche
 * Kisten der Bot im Event nehmen soll -- eisen/holz/gold/mythril/mana und
 * Nahrungskisten ... fuer mich bitte holz und eisen aus".
 *
 * Gilt nur fuer diese sechs Kisten. Alles andere in templates\event-belohnungen
 * (Diamanten, Essenzen, Fackeln, Schluessel, Extras ...) nimmt der Bot immer mit;
 * wer davon etwas nicht will, loescht die Vorlage dort.
 *
 * Datei event-kisten.txt, Zeilen wie "holz=aus". Fehlt eine Zeile: an.
 * Geaendert mit !settings kiste holz an|aus.
 */
public final class Eventkisten {

    private Eventkisten() {}

    /** Art -> Name der Vorlage ohne "ev_kiste_". */
    private static final Map<String, String> ARTEN = new LinkedHashMap<>();
    static {
        ARTEN.put("holz", "holz");
        ARTEN.put("eisen", "metall");
        ARTEN.put("gold", "gold");
        ARTEN.put("mithril", "mithril");
        ARTEN.put("mana", "elixier");
        ARTEN.put("nahrung", "essen");
        ARTEN.put("fackeln", "fackeln");   // Autor, 29.09.: "Fackeln liegen lassen" (jede kostet einen Hammer)
    }

    private static final Path DATEI = Path.of("event-kisten.txt");
    private static final Map<String, Boolean> AN = new ConcurrentHashMap<>();
    static { laden(); }

    /**
     * Gehoert die Vorlage zu dieser Kiste? "ev_kiste_holz" und jede Spielart
     * "ev_kiste_holz_..." (z. B. ev_kiste_holz_feld, aus einem Foto vom Spielfeld).
     * "ev_kiste_essenz" ist dabei NICHT "ev_kiste_essen".
     */
    private static boolean gehoertZu(String vorlage, String art) {
        String basis = "ev_kiste_" + ARTEN.get(art);
        return vorlage.equals(basis) || vorlage.startsWith(basis + "_");
    }

    /** Darf die Vorlage mitgenommen werden? (Nur die sechs Kisten lassen sich abschalten.) */
    public static boolean vorlageErlaubt(String vorlage) {
        for (String art : ARTEN.keySet()) {
            if (gehoertZu(vorlage, art)) return AN.getOrDefault(art, true);
        }
        return true;
    }

    /** Ist das die Nahrungskiste? (Autor: "ein extra Auge auf Nahrungskisten") */
    public static boolean istNahrung(String vorlage) {
        return gehoertZu(vorlage, "nahrung");
    }

    /** Wort aus Discord -> Art, sonst null. */
    public static String art(String wort) {
        if (wort == null) return null;
        String w = wort.toLowerCase().replace("kisten", "").replace("kiste", "").replace("chests", "")
                .replace("chest", "").trim();
        if (w.endsWith("s")) w = w.substring(0, w.length() - 1);   // "nahrungskiste" -> "nahrung"
        return switch (w) {
            case "holz", "wood" -> "holz";
            case "eisen", "metall", "stahl", "iron", "steel", "metal" -> "eisen";
            case "gold" -> "gold";
            case "mithril", "mythril" -> "mithril";
            case "mana", "elixier", "elixir" -> "mana";
            case "nahrung", "essen", "food", "apfel", "aepfel" -> "nahrung";
            case "fackel", "fackeln", "torch", "torche" -> "fackeln";
            default -> null;
        };
    }

    public static void setzen(String art, boolean an) {
        AN.put(art, an);
        speichern();
        Log.info("Event", Sprache.t("Chest " + anzeige(art) + ": " + (an ? "is collected" : "is left behind") + " (via !settings).",
                "Kiste " + art + ": " + (an ? "wird mitgenommen" : "wird liegen gelassen") + " (per !settings)."));
    }

    /** Englische Namen der Arten fuer die Anzeige -- alle werden von {@link #art(String)} auch wieder verstanden. */
    private static final Map<String, String> ENGLISCH = Map.of(
            "holz", "wood", "eisen", "iron", "gold", "gold", "mithril", "mithril",
            "mana", "mana", "nahrung", "food", "fackeln", "torches");

    /** Die Art, wie der Spieler sie liest: auf Englisch "wood", auf Deutsch "holz". */
    private static String anzeige(String art) {
        return Sprache.t(ENGLISCH.getOrDefault(art, art), art);
    }

    /** "holz aus · eisen aus · gold an ..." */
    public static String beschreibung() {
        StringBuilder s = new StringBuilder();
        for (String art : ARTEN.keySet()) {
            if (s.length() > 0) s.append(" · ");
            s.append(anzeige(art)).append(' ').append(AN.getOrDefault(art, true) ? Sprache.t("on", "an") : Sprache.t("off", "aus"));
        }
        return s.toString();
    }

    private static void laden() {
        try {
            if (!Files.exists(DATEI)) return;
            for (String zeile : Dateien.zeilen(DATEI)) {
                String z = zeile.trim();
                if (z.isEmpty() || z.startsWith("#") || !z.contains("=")) continue;
                String art = art(z.substring(0, z.indexOf('=')).trim());
                String wert = z.substring(z.indexOf('=') + 1).trim().toLowerCase();
                if (art != null) AN.put(art, !(wert.equals("aus") || wert.equals("off") || wert.equals("nein")));
            }
        } catch (Exception e) {
            Log.warn("Event", Sprache.t("event-kisten.txt not readable: ", "event-kisten.txt nicht lesbar: ") + e.getMessage());
        }
    }

    private static void speichern() {
        List<String> z = new ArrayList<>();
        z.add(Sprache.t("# Which chests the Hammer Event collects (an = on, aus = off), set with !settings chest <type> on|off",
                "# Welche Kisten das Hammer-Event mitnimmt (an/aus), gesetzt mit !settings kiste <art> an|aus"));
        for (String art : ARTEN.keySet()) z.add(art + "=" + (AN.getOrDefault(art, true) ? "an" : "aus"));
        try {
            Files.write(DATEI, z);
        } catch (Exception e) {
            Log.warn("Event", Sprache.t("event-kisten.txt not written: ", "event-kisten.txt nicht geschrieben: ") + e.getMessage());
        }
    }
}
