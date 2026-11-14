package de.hustlebot.steuer;

import de.hustlebot.bot.Aufstellung;
import de.hustlebot.bot.HeldenSet;
import de.hustlebot.core.Bezahlung;
import de.hustlebot.core.Energieplan;
import de.hustlebot.core.Eventautomatik;
import de.hustlebot.core.Konten;
import de.hustlebot.core.Mauswache;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Spielneustart;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Turmplan;
import de.hustlebot.core.Zeitzone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Autor 24.10. (Ideenliste F26, F27): Was die Einstellungen JETZT sind -- gelesen direkt aus dem Bot (dieselbe Anwendung,
 * nur lesen), nicht aus einer Antwort auf einen Befehl. So steht der Wert schon beim Oeffnen neben jedem Schalter und Feld,
 * und das Programm kennt den Wert VOR einer Aenderung: damit gibt es "Rueckgaengig" (der alte Wert wird mit demselben
 * Befehl zurueckgesetzt) und "Auf Standard zurueck" (der Standard ist der des Bots ohne Datei).
 *
 * Ein {@link Wert} gehoert zu einem Befehl ohne Wert, z. B. "!settings leuchtturm". {@link #finde} erkennt auch
 * "!settings leuchtturm aus". Nur was sich eindeutig lesen UND wieder setzen laesst, hat einen Rohwert ({@code roh}); alles
 * andere (z. B. Bezahlart, wenn jeder Modus eine andere hat) zeigt nur den Text und bietet kein Rueckgaengig.
 */
final class Einstellungswerte {

    private Einstellungswerte() {
    }

    /**
     * @param schluessel Befehl ohne Wert, kleingeschrieben, z. B. "!settings leuchtturm"
     * @param setzBasis  Anfang des Befehls, der einen Wert setzt ("!settings bezahlen alle"); danach kommt der Rohwert
     * @param anzeige    der Wert fuer die Zeile, in der Sprache des Programms; null = nicht lesbar
     * @param roh        der Wert so, wie der Befehl ihn nimmt ("an", "100", "gold"); null = nicht eindeutig
     * @param standard   der Rohwert ohne jede Datei; null = es gibt keinen festen Standard
     */
    record Wert(String schluessel, String setzBasis, Supplier<String> anzeige, Supplier<String> roh, String standard) {

        String setzBefehl(String rohWert) {
            return setzBasis + " " + rohWert;
        }

        String aktuellAnzeige() {
            try {
                return anzeige.get();
            } catch (RuntimeException e) {
                return null;
            }
        }

        String aktuellRoh() {
            try {
                return roh.get();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final List<Wert> LISTE = baue();

    /** Alle bekannten Werte. */
    static List<Wert> alle() {
        return LISTE;
    }

    /** Befehl in Kleinbuchstaben mit einfachen Leerzeichen. */
    static String normal(String befehl) {
        return befehl == null ? "" : befehl.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Der Wert zu einem Befehl ("!settings leuchtturm aus" findet "!settings leuchtturm"); null, wenn es keinen gibt. */
    static Wert finde(String befehl) {
        String b = normal(befehl);
        if (b.isEmpty()) return null;
        Wert beste = null;
        for (Wert w : LISTE) {
            if ((b.equals(w.schluessel()) || b.startsWith(w.schluessel() + " "))
                    && (beste == null || w.schluessel().length() > beste.schluessel().length())) {
                beste = w;
            }
        }
        return beste;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static String anAus(boolean an) {
        return an ? "an" : "aus";
    }

    private static String anAusAnzeige(boolean an) {
        return an ? Sprache.t("on", "an") : Sprache.t("off", "aus");
    }

    /** Ein An/Aus-Schalter: Rohwert "an"/"aus". */
    private static Wert schalter(String schluessel, String standard, Supplier<Boolean> an) {
        return new Wert(schluessel, schluessel, () -> anAusAnzeige(an.get()), () -> anAus(an.get()), standard);
    }

    /** Dieselben Schalter, aber mit den Woertern on/off (Befehl !clanwar). */
    private static Wert schalterEnglisch(String schluessel, String standard, Supplier<Boolean> an) {
        return new Wert(schluessel, schluessel, () -> anAusAnzeige(an.get()), () -> an.get() ? "on" : "off", standard);
    }

    private static Wert zahl(String schluessel, String standard, Supplier<Integer> wert) {
        return new Wert(schluessel, schluessel, () -> String.valueOf(wert.get()), () -> String.valueOf(wert.get()), standard);
    }

    /** Ein Schalter, dessen Datei der Bot selbst liest (Schalterdatei: fehlt sie, gilt der Standard). */
    private static boolean datei(String name, boolean standard) {
        return new Schalterdatei(Ordner.bei(name), "Einstellungen", standard).lesen();
    }

    private static List<Wert> baue() {
        List<Wert> l = new ArrayList<>();
        l.add(schalter("!settings leuchtturm", "an", Energieplan.LEUCHTTURM::istAn));
        l.add(schalter("!settings kolosseum", "an", Energieplan.KOLOSSEUM::istAn));
        l.add(schalter("!settings titanturm", "an", Turmplan::istAn));
        // Clan-Krieg selbst aufstellen: zwei Zeilen, dieselbe Datei kriegsplan-an.txt (ohne Datei: an)
        l.add(schalter("!kriegsplan", "an", () -> datei("kriegsplan-an.txt", true)));
        l.add(schalterEnglisch("!clanwar", "on", () -> datei("kriegsplan-an.txt", true)));
        l.add(schalter("!settings mauswache", "an", Mauswache::an));
        l.add(schalter("!settings arenawatch", "an", () -> datei("arena-beobachter.txt", true)));
        l.add(schalter("!settings accounts", "aus", Konten::an));
        l.add(schalter("!settings spielneustart", "an", Spielneustart::istAn));
        l.add(schalter("!settings heldenset", "aus", HeldenSet::an));
        l.add(new Wert("!settings heldenset vorher", "!settings heldenset vorher",
                () -> HeldenSet.vorher() + " " + HeldenSet.vorherPlatz(), () -> HeldenSet.vorher() + " " + HeldenSet.vorherPlatz(), null));
        l.add(new Wert("!settings heldenset nachher", "!settings heldenset nachher",
                () -> HeldenSet.nachher() + " " + HeldenSet.nachherPlatz(), () -> HeldenSet.nachher() + " " + HeldenSet.nachherPlatz(), null));

        // Event-Automatik: an/aus ist der Rohwert; welches Event und die Verlies-Runs stehen nur im Text
        l.add(new Wert("!settings eventautomatik", "!settings eventautomatik",
                () -> anAusAnzeige(Eventautomatik.istAn()) + " · " + (Eventautomatik.titan() ? "Titan" : "Hammer")
                        + (Eventautomatik.verliesRuns() > 0 ? " · " + Sprache.t("dungeon runs in an emergency: ", "Verlies-Runs im Notfall: ")
                        + Eventautomatik.verliesRuns() : ""),
                () -> anAus(Eventautomatik.istAn()), "aus"));
        l.add(zahl("!settings hammerevent", "100", Eventautomatik::hammerAlleRuns));
        l.add(zahl("!settings titanevent", "100", Eventautomatik::titanAlleRuns));
        l.add(zahl("!settings hammerstages", "0", Eventautomatik::stagesJeGang));
        l.add(zahl("!settings lupen", "5", Eventautomatik::maxLupen));
        l.add(new Wert("!settings pass", "!settings pass",
                () -> Sprache.t(Eventautomatik.pass().englisch, Eventautomatik.pass().anzeige),
                () -> Eventautomatik.pass().name().toLowerCase(Locale.ROOT), "gold"));

        l.add(new Wert("!zeitzone", "!zeitzone", Zeitzone::name, Zeitzone::name, "Europe/Berlin"));

        // Nahrung: Lager, Limit und Bezahlart -- Limit und Bezahlart nur, wenn alle Modi gleich sind (sonst nur der Text)
        l.add(new Wert("!settings lager", "!settings lager", () -> Bezahlung.zahl(Bezahlung.lager()),
                () -> String.valueOf(Bezahlung.lager()), null));
        l.add(new Wert("!settings nahrungslimit alle", "!settings nahrungslimit alle", Einstellungswerte::limitAnzeige,
                Einstellungswerte::limitRoh, "aus"));
        l.add(new Wert("!settings bezahlen", "!settings bezahlen alle", Einstellungswerte::bezahlAnzeige,
                Einstellungswerte::bezahlRoh, null));
        l.add(new Wert("!settings squad", "!settings squad", Einstellungswerte::squadAnzeige, Einstellungswerte::squadRoh, null));

        l.sort(Comparator.comparingInt((Wert w) -> -w.schluessel().length()));
        return List.copyOf(l);
    }

    // --- Nahrungs-Limit (gespeichertes Limit je Modus; -1 = ohne Grenze) -----------------------------------------

    private static String limitRoh() {
        long erstes = Bezahlung.gespeichertesLimit(Bezahlung.MODI.get(0));
        for (String m : Bezahlung.MODI) if (Bezahlung.gespeichertesLimit(m) != erstes) return null;
        return erstes <= 0 ? "aus" : String.valueOf(erstes);
    }

    private static String limitAnzeige() {
        String roh = limitRoh();
        if (roh != null) return roh.equals("aus") ? Sprache.t("off", "aus") : Bezahlung.zahl(Long.parseLong(roh));
        StringBuilder b = new StringBuilder();
        for (String m : Bezahlung.MODI) {
            long g = Bezahlung.gespeichertesLimit(m);
            if (b.length() > 0) b.append(" · ");
            b.append(modusName(m)).append(' ').append(g <= 0 ? Sprache.t("off", "aus") : Bezahlung.zahl(g));
        }
        return b.toString();
    }

    // --- Bezahlart -------------------------------------------------------------------------------------------------

    private static String modusName(String m) {
        return switch (m) {
            case "dungeon" -> Sprache.t("Dungeon", "Verlies");
            case "cathedral" -> Sprache.t("Cathedral", "Kathedrale");
            default -> "Arena";
        };
    }

    private static String bezahlRoh() {
        Bezahlung.Art erste = Bezahlung.standard(Bezahlung.MODI.get(0));
        for (String m : Bezahlung.MODI) if (Bezahlung.standard(m) != erste) return null;
        return erste.wort;
    }

    private static String bezahlAnzeige() {
        String roh = bezahlRoh();
        if (roh != null) return roh;
        StringBuilder b = new StringBuilder();
        for (String m : Bezahlung.MODI) {
            if (b.length() > 0) b.append(" · ");
            b.append(modusName(m)).append(' ').append(Bezahlung.standard(m).wort);
        }
        return b.toString();
    }

    // --- Truppe (best / previous je Modus) ------------------------------------------------------------------------

    private static String squadRoh() {
        List<String> modi = Aufstellung.modi();
        boolean erste = Aufstellung.istPrevious(modi.get(0));
        for (String m : modi) if (Aufstellung.istPrevious(m) != erste) return null;
        return erste ? "previous" : "best";
    }

    private static String squadAnzeige() {
        String roh = squadRoh();
        if (roh != null) return roh;
        StringBuilder b = new StringBuilder();
        for (String m : Aufstellung.modi()) {
            if (b.length() > 0) b.append(" · ");
            b.append(Aufstellung.anzeige(m)).append(' ').append(Aufstellung.istPrevious(m) ? "previous" : "best");
        }
        return b.toString();
    }
}
