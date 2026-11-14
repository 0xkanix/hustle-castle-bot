package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eigene Schwellen je Belohnungs-Vorlage im Hammer-Event und die Kraken-Sperre.
 *
 * Autor 28.10.: "Schau, ob alle Vorlagen fuer die Belohnungen passen -- es gibt einige Belohnungen, die von den Farben
 * her fast gleich aussehen." Und: Kraken-Kisten nie gezielt nehmen (er hat schon sehr viele).
 *
 * Kreuztabelle vom 28.10. (Werkzeug im Scratchpad, Test {@code EventVorlagenSchwellenTest}): jede Kisten-Vorlage gegen
 * 185 Belohnungsarten und gegen jede Zelle der Feld-Fotos logs\event-ende-/event-stab-*.png -- farbig wie der Bot im
 * Event, "mittig" wie beim milden Treffer. Ergebnis:
 * <ul>
 *   <li>Keine Vorlage kommt auf einer FREMDEN Belohnung, die der Bot nicht nehmen soll, oder auf einer Kraken-Kiste
 *       naeher als 0,05 an ihre Schwelle. Am naechsten: Eisen 0,56 auf Kraken, Mana 0,55 auf Kraken, Rolle 0,58 auf
 *       der anderen Rolle (b055), gruener Schatz 0,58 auf der gruenen Figur (b060).</li>
 *   <li>Verwechselt werden nur Belohnungen, die der Bot ohnehin alle nimmt: Extra-Haemmer 3 auf 5 (bis 0,78),
 *       Extra 4 auf gruener Essenz (0,70), Diamant auf blauem Kristall (0,67), Holz auf dem Essenskorb (0,65).</li>
 *   <li>Umgekehrt blieben echte Kisten liegen, weil sie im Feld UNTER ihrer Schwelle passen: Mana 0,617-0,687 (alle
 *       sechs), Eisen ab 0,656, Mithril ab 0,647, gruene Essenz ab 0,681. Fuer diese gilt eine eigene, mildere
 *       Schwelle -- mit mindestens 0,06 Abstand zu allem Fremden. Unter 0,78 zaehlt ein Treffer ohnehin nur mittig in
 *       einem offenen Feld.</li>
 * </ul>
 */
public final class EventVorlagenSchwellen {

    private EventVorlagenSchwellen() {
    }

    /** Eigene milde Schwellen. Kommentar je Zeile: echte Kisten im Feld / Fremdes hoechstens. */
    private static final Map<String, Double> EIGENE = Map.of(
            "ev_kiste_elixier", 0.61,             // Mana: 0,617-0,687 / 0,547 (Kraken)
            "ev_kiste_metall", 0.64,              // Eisen: 0,656-0,731 / 0,564 (Kraken)
            "ev_kiste_mithril", 0.63,             // 0,647-0,715 / 0,416
            "ev_kiste_mithril_feld", 0.64,        // 0,662-0,755 / 0,357
            "ev_kiste_essenz_gruen_feld", 0.66);  // 0,681-0,760 / 0,368

    /**
     * Ab welchem Wert ein MITTIGER Treffer dieser Vorlage zaehlt.
     *
     * @param vorlage  Name der Kisten-Vorlage (ev_kiste_...)
     * @param standard die bisherige milde Schwelle (0,70 fuer *_feld und Nahrung, sonst 0,73)
     * @return die eigene Schwelle, sonst {@code standard}
     */
    public static double schwelle(String vorlage, double standard) {
        Double eigen = EIGENE.get(vorlage);
        return eigen != null ? eigen : standard;
    }

    /** Die Kraken-Kisten (templates\gelernt\event-belohnungen): blau, lila mit HC-Zeichen, gross. */
    public static final List<String> KRAKEN = List.of(
            "ev_nie_kraken_feld", "ev_nie_kraken_lila_feld", "ev_nie_kraken_gross_feld");

    /** Ab hier ist es eine Kraken-Kiste: echte Kraken 0,695-1,00 (72 Felder), alles andere hoechstens 0,54. */
    public static final double KRAKEN_SCHWELLE = 0.64;

    /** Die Kraken-Vorlage darf so viel schlechter passen als die gefundene Belohnung und gewinnt trotzdem. */
    public static final double KRAKEN_ABSTAND = 0.10;

    /**
     * Ist das eher eine Kraken-Kiste als die gefundene Belohnung?
     *
     * @param trefferWert Wert der guten Vorlage an der Stelle
     * @param krakenWert  bester Wert einer Kraken-Vorlage an derselben Stelle
     */
    public static boolean krakenGewinnt(double trefferWert, double krakenWert) {
        return krakenWert >= KRAKEN_SCHWELLE && krakenWert + KRAKEN_ABSTAND > trefferWert;
    }

    /**
     * Liegt an der Stelle eines Kisten-Treffers eine Kraken-Kiste? Dann nicht anklicken und nicht als gutes Feld fuer
     * den blauen Hammer zaehlen (Autor 28.10.: nie GEZIELT auf Kraken -- unter der Form duerfen sie mitkommen).
     * Gesucht wird wie bei den abgeschalteten Kisten 25 Bildpunkte um den Treffer.
     */
    public static boolean krakenAnStelle(Mat screen, Matcher matcher, Matcher.Hit h) {
        Rect um = new Rect(h.x() - 25, h.y() - 25, h.width() + 50, h.height() + 50);
        for (String k : KRAKEN) {
            if (!matcher.has(k)) continue;
            Optional<Matcher.Hit> t = matcher.findImBereich(screen, k, um, KRAKEN_SCHWELLE);
            if (t.isPresent() && krakenGewinnt(h.score(), t.get().score())) {
                Log.debug("Event", "Treffer " + h.name() + " " + String.format("%.2f", h.score()) + " bei "
                        + h.centerX() + "," + h.centerY() + " ist eine Kraken-Kiste (" + k + " "
                        + String.format("%.2f", t.get().score()) + ") -- bleibt liegen.");
                return true;
            }
        }
        return false;
    }
}
