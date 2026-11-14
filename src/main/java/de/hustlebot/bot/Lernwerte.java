package de.hustlebot.bot;

import de.hustlebot.core.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import de.hustlebot.core.Sprache;

/**
 * Wartezeiten und Schalter der Kathedrale (Weg c), die sich OHNE Neustart
 * verstellen lassen: aus lernwerte.txt, bei jedem Run-Start neu gelesen.
 *
 * Autor, 26.09.: "ich wuerde heute 50 Runs spielen in der Kathedrale und du
 * drehst alle 10 Runs einmal kurz die Wartepausen bisschen runter, quasi ein
 * Lernmodus." Bisher waren das feste Zahlen im Programm -- jede Aenderung
 * hiess neu bauen und neu starten, mitten im Betrieb. Jetzt reicht es, die
 * Datei zu aendern; der naechste Run nimmt die neuen Werte.
 *
 * Fehlt die Datei, wird sie mit den erprobten Werten angelegt. Fehlt ein
 * Wert oder ist er kaputt, gilt der erprobte Wert -- ein Tippfehler in der
 * Datei kann den Bot also nicht aus dem Tritt bringen.
 */
final class Lernwerte {

    static final Path DATEI = Path.of("lernwerte.txt");

    /** Name -> erprobter Wert (Stand 26.09.) und Erklaerung fuer die Datei. */
    private static final Map<String, String[]> STANDARD = new LinkedHashMap<>();
    static {
        STANDARD.put("takt_ms",        new String[]{"20",  "So oft schaut der Bot nach, ob sich das Bild geaendert hat"});
        STANDARD.put("blind_ms",       new String[]{"10",  "So lange nach einem Klick gar nicht hinsehen"});
        STANDARD.put("turbo_min_ms",   new String[]{"35",  "Mindestpause nach einem Klick (Weg c)"});
        STANDARD.put("kette_ms",       new String[]{"200", "Hoechstens so lange zwischen zwei verketteten Klicks warten"});
        STANDARD.put("segen_wahl_ms",  new String[]{"30",  "Pause zwischen mittlerer Segen-Karte und Bestaetigen"});
        STANDARD.put("segen_takt_ms",  new String[]{"60",  "Fotozyklus nach Home: so oft nach dem Segen sehen"});
        STANDARD.put("nach_kampf_ms",  new String[]{"200", "Etagenkarte nach Home so lange nicht anfassen"});
        STANDARD.put("fotozyklus_etage", new String[]{"aus", "Nach dem Segen gleich auf die Etagenkarte sehen (an/aus)"});
        STANDARD.put("fotozyklus_kampf", new String[]{"aus", "Nach dem Wegpunkt gleich nach dem Kampfknopf sehen (an/aus)"});
        STANDARD.put("sieg_antippen",  new String[]{"aus", "Sieg-Animation antippen, damit Home frueher kommt (an/aus)"});
        STANDARD.put("sieg_tipp_ab_ms", new String[]{"450", "Erst so lange nach Kampf starten antippen"});
        STANDARD.put("kastenblick", new String[]{"an", "Nur die Stelle des erwarteten Knopfs ansehen, ~8 ms je Blick (an/aus); aus = die Fotozyklen"});
        STANDARD.put("aufstellen_ms", new String[]{"70", "Pause zwischen Deploy the best und Quick Attack (nur mit kastenblick)"});
        STANDARD.put("wegplanung", new String[]{"an", "Kuerzesten Weg je Etage ausrechnen (an/aus); aus = die alte Wegsuche"});
        STANDARD.put("waechter_blick_ms", new String[]{"600", "Vor dem Waechter: zweiter Blick so lange nach dem Erscheinen der Etagenkarte (Schriftzug 'You have got a blessing' zieht weiter)"});
    }

    private final Map<String, String> werte = new LinkedHashMap<>();
    private long gelesenStand = -1;

    Lernwerte() {
        for (var e : STANDARD.entrySet()) werte.put(e.getKey(), e.getValue()[0]);
    }

    /**
     * Beim naechsten neuLesen() auf jeden Fall uebernehmen -- nach einem
     * Modus-Start, der die Taktwerte im BotContext zurueckgesetzt hat.
     */
    void vergessen() {
        gelesenStand = -1;
    }

    /**
     * Liest die Datei neu, wenn sie sich seit dem letzten Mal geaendert hat.
     *
     * @return true, wenn neue Werte gelten
     */
    boolean neuLesen() {
        try {
            if (!Files.exists(DATEI)) anlegen();
            long stand = Files.getLastModifiedTime(DATEI).toMillis();
            if (stand == gelesenStand) return false;
            gelesenStand = stand;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(DATEI, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (var e : STANDARD.entrySet()) {
                werte.put(e.getKey(), p.getProperty(e.getKey(), e.getValue()[0]).trim());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.warn("Lernwerte", Sprache.t("lernwerte.txt could not be read (" + e.getMessage() + "). The previous values apply.",
                    "lernwerte.txt liess sich nicht lesen (" + e.getMessage()
                    + "). Es gelten die bisherigen Werte."));
            return false;
        }
    }

    private void anlegen() throws IOException {
        try (Writer w = Files.newBufferedWriter(DATEI, StandardCharsets.UTF_8)) {
            w.write("# Wartezeiten und Schalter der Kathedrale (Weg c).\n");
            w.write("# Wird bei jedem Run-Start neu gelesen -- kein Neustart noetig.\n");
            w.write("# Fehlt ein Wert oder ist er kaputt, gilt der erprobte Wert.\n\n");
            for (var e : STANDARD.entrySet()) {
                w.write("# " + e.getValue()[1] + "\n");
                w.write(e.getKey() + "=" + e.getValue()[0] + "\n\n");
            }
        }
    }

    /** Eine Zahl; bei Unsinn der erprobte Wert. Nie negativ. */
    long zahl(String name) {
        try {
            return Math.max(0, Long.parseLong(werte.get(name)));
        } catch (RuntimeException e) {
            return Long.parseLong(STANDARD.get(name)[0]);
        }
    }

    /** Ein Schalter: "an", "ja", "on", "true", "1" gelten als an. */
    boolean an(String name) {
        String w = werte.getOrDefault(name, "aus").toLowerCase();
        return w.equals("an") || w.equals("ja") || w.equals("on") || w.equals("true") || w.equals("1");
    }

    /** Alle Werte in einer Zeile, fuer das Protokoll. */
    String zeile() {
        StringBuilder b = new StringBuilder();
        for (var e : werte.entrySet()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }
}
