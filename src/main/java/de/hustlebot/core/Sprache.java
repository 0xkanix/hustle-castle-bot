package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Die Sprache, in der der Bot mit dem Spieler redet -- Discord, Fenster, Protokoll.
 *
 * Standard ist ENGLISCH (Autor 11.10.: "Ein Freund bekommt die Freunde-Fassung neu und
 * spricht ausschliesslich Englisch"). Umgestellt wird mit den Discord-Befehlen "!deutsch"
 * und "!english", in Settings.exe und im Einrichtungs-Fenster. Die Wahl wird in
 * sprache.txt im Bot-Ordner GESPEICHERT und gilt nach einem Neustart weiter -- auch fuer
 * Capture-Images.exe, Settings.exe und die Notfall-Wache, die alle dieselbe Datei lesen.
 *
 * Der Weg ist bewusst einfach gehalten: statt einer Datei mit Schluesseln und
 * Uebersetzungen stehen beide Fassungen direkt nebeneinander im Code:
 *
 *     Sprache.t("Runs completed", "Durchlaeufe abgeschlossen")
 *
 * Damit sieht man beim Lesen sofort, was in beiden Sprachen dasteht, und es
 * kann nicht passieren, dass ein Schluessel ins Leere zeigt.
 */
public final class Sprache {

    /** Die beiden Sprachen. */
    public enum Lang {
        /** Englisch -- die Standardeinstellung. */
        EN,
        /** Deutsch. */
        DE
    }

    /** Die Datei im Bot-Ordner, in der die Wahl steht ("en" oder "de"). */
    public static final String DATEI = "sprache.txt";

    private static volatile Lang aktuell = Lang.EN;

    private Sprache() {
    }

    /** Stellt die Sprache um (nur fuer diesen Lauf -- zum Merken {@link #speichern}). */
    public static void set(Lang lang) {
        if (lang == null) return;
        aktuell = lang;
        Log.info("Sprache", lang == Lang.EN
                ? "Output switched to English."
                : "Ausgaben stehen jetzt auf Deutsch.");
    }

    public static Lang get() {
        return aktuell;
    }

    public static boolean istEnglisch() {
        return aktuell == Lang.EN;
    }

    /** Waehlt die passende Fassung: erst Englisch, dann Deutsch. */
    public static String t(String english, String deutsch) {
        return aktuell == Lang.EN ? english : deutsch;
    }

    /**
     * Formatiert eine Zahl in der Schreibweise der eingestellten Sprache:
     * 1,234,567 auf Englisch, 1.234.567 auf Deutsch.
     */
    public static String zahl(long value) {
        return String.format(aktuell == Lang.EN
                ? java.util.Locale.ENGLISH
                : java.util.Locale.GERMANY, "%,d", value);
    }

    /** Dasselbe fuer Kommazahlen, etwa Durchlaeufe pro Stunde. */
    public static String komma(double value, int stellen) {
        return String.format(aktuell == Lang.EN
                ? java.util.Locale.ENGLISH
                : java.util.Locale.GERMANY, "%." + stellen + "f", value);
    }

    // ------------------------------------------------------------------
    //  Merken ueber den Neustart hinaus (11.10.)
    // ------------------------------------------------------------------

    /** Liest die gespeicherte Wahl aus sprache.txt im Bot-Ordner. Ohne Datei: Englisch. */
    public static Lang laden() {
        return laden(Ordner.bei(DATEI));
    }

    /** Wie {@link #laden()}, aber aus einer bestimmten Datei (fuer Tests). */
    public static Lang laden(Path datei) {
        try {
            if (datei != null && Files.exists(datei)) {
                return ausText(Files.readString(datei, StandardCharsets.UTF_8));
            }
            // 16.10.: Die deutschen Pakete bis 2.5 hatten KEINE sprache.txt (Deutsch war damals der Standard),
            // die englischen schon immer (seit 2.1). Ein eingerichteter Bot ohne Datei ist also ein alter
            // deutscher -- nach dem Update auf 3.x soll er deutsch bleiben, nicht auf Englisch springen.
            if (datei != null && altesDeutschesPaket(datei.resolveSibling("config.properties"))) {
                // Code-Pruefung 16.10.: gleich festschreiben -- sonst haengt die Sprache an einer FEHLENDEN Datei,
                // und jedes Werkzeug, das sie anlegt (Installer, Update), schaltet still auf Englisch.
                try {
                    Files.writeString(datei, "de\n", StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // dann eben beim naechsten Start wieder erkennen
                }
                return Lang.DE;
            }
        } catch (Exception e) {
            Log.debug("Sprache", DATEI + " nicht lesbar: " + e.getMessage());
        }
        return Lang.EN;
    }

    /** Eingerichteter Bot (Lizenzschluessel in config.properties) -- siehe laden(Path). */
    static boolean altesDeutschesPaket(Path config) {
        try {
            if (config == null || !Files.exists(config)) return false;
            for (String z : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                if (z.matches("\\s*lizenz\\.schluessel\\s*=\\s*\\S.*")) return true;
            }
        } catch (Exception ignored) {
            // dann Englisch wie bisher
        }
        return false;
    }

    /** Liest die Wahl aus der Datei und stellt sie ein -- beim Start jedes Programms. */
    public static Lang ausDateiUebernehmen() {
        Lang lang = laden();
        aktuell = lang;   // ohne Protokollzeile: beim Start steht das Protokoll noch nicht
        return lang;
    }

    /** "de" (auch "deutsch", "DE\n") = Deutsch; alles andere -- auch leer -- Englisch. */
    public static Lang ausText(String inhalt) {
        return inhalt != null && inhalt.strip().toLowerCase(Locale.ROOT).startsWith("de") ? Lang.DE : Lang.EN;
    }

    /** Was in die Datei geschrieben wird. */
    public static String alsText(Lang lang) {
        return lang == Lang.DE ? "de" : "en";
    }

    /** Merkt die Wahl in sprache.txt im Bot-Ordner, damit sie nach einem Neustart weiter gilt. */
    public static boolean speichern(Lang lang) {
        return speichern(Ordner.bei(DATEI), lang);
    }

    /** Wie {@link #speichern(Lang)}, aber in eine bestimmte Datei (fuer Tests). */
    public static boolean speichern(Path datei, Lang lang) {
        if (lang == null || datei == null) return false;
        try {
            Files.writeString(datei, alsText(lang) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            Log.warn("Sprache", Sprache.t(DATEI + " not saved: " + e.getMessage(),
                    DATEI + " nicht gespeichert: " + e.getMessage()));
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Quellen der Protokollzeilen (11.10.)
    // ------------------------------------------------------------------

    /**
     * Die Quelle einer Protokollzeile ("Motor", "Verlies", "Nahrung") in der eingestellten
     * Sprache. Die Zeilen selbst stehen zweisprachig im Code; die kurzen Quellen-Namen werden
     * hier an EINER Stelle uebersetzt, statt an jeder der ueber tausend Log-Aufrufe. Unbekannte
     * Quellen bleiben, wie sie sind.
     */
    public static String quelle(String source) {
        if (source == null || aktuell == Lang.DE) return source;
        String en = QUELLEN.get(source);
        if (en != null) return en;
        // Zusammengesetzte Quellen wie "Kathedrale (Hammer-Event)": nur das Wort vorn uebersetzen.
        int klammer = source.indexOf(" (");
        if (klammer > 0) {
            String kopf = QUELLEN.get(source.substring(0, klammer));
            if (kopf != null) return kopf + source.substring(klammer);
        }
        return source;
    }

    private static final Map<String, String> QUELLEN = Map.ofEntries(
            Map.entry("Verlies", "Dungeon"),
            Map.entry("Klick", "Click"),
            Map.entry("Kathedrale", "Cathedral"),
            Map.entry("Motor", "Engine"),
            Map.entry("Clan-Krieg", "Clan war"),
            Map.entry("Nahrung", "Food"),
            Map.entry("Rechner", "PC"),
            Map.entry("Lizenz", "Licence"),
            Map.entry("Titanen-Turm", "Titan tower"),
            Map.entry("Event-Automatik", "Event automation"),
            Map.entry("Vorlagen", "Templates"),
            Map.entry("Einrichtung", "Setup"),
            Map.entry("Bezahlung", "Payment"),
            Map.entry("Bezahlen", "Payment"),
            Map.entry("Waechter", "Watchdog"),
            Map.entry("Tipp", "Tip"),
            Map.entry("Tasche", "Bag"),
            Map.entry("Notizblock", "Notebook"),
            Map.entry("Karte", "Map"),
            Map.entry("Clan-Laden", "Clan store"),
            Map.entry("Not-Aus", "Emergency stop"),
            Map.entry("Notfall-Wache", "Emergency watch"),
            Map.entry("Fenster", "Window"),
            Map.entry("Zaehler", "Counter"),
            Map.entry("Warteschlange", "Queue"),
            Map.entry("Laufzeiten", "Run times"),
            Map.entry("Eingabe", "Input"),
            Map.entry("Aufstellung", "Squad"),
            Map.entry("Ziehen", "Drag"),
            Map.entry("Zauberstaebe", "Wands"),
            Map.entry("Wischen", "Swipe"),
            Map.entry("Verhalten", "Behaviour"),
            Map.entry("Meldungen", "Reports"),
            Map.entry("Lernwerte", "Learned values"),
            Map.entry("Ende", "End"),
            Map.entry("Einstellungen", "Settings"),
            Map.entry("Durchlauf", "Run"),
            Map.entry("Zeitbericht", "Time report"),
            Map.entry("Titan-Event", "Titan event"),
            Map.entry("Stellen", "Spots"),
            Map.entry("Sprache", "Language"),
            Map.entry("Liste", "List"),
            Map.entry("Kolosseum", "Colosseum"),
            Map.entry("Leuchtturm", "Lighthouse"),
            Map.entry("Kampf", "Fight"),
            Map.entry("Bilderkennung", "Image matching"),
            Map.entry("Bericht", "Report"),
            Map.entry("Aufnahme", "Capture"),
            Map.entry("Hinweis", "Note"),
            Map.entry("Spiel", "Game"),
            Map.entry("Stoerfenster", "Pop-ups"),
            Map.entry("Weltkarte", "World map"),
            Map.entry("Truhen", "Chests"),
            Map.entry("Kisten", "Chests"),
            Map.entry("Freunde", "Friends"),
            Map.entry("Fehler", "Error"),
            Map.entry("Gegner", "Opponents"),
            Map.entry("Punkte", "Points"),
            Map.entry("Schirm", "Patronage"),
            Map.entry("Steine", "Stones"),
            Map.entry("Allianz", "Alliance"),
            Map.entry("Zeit", "Time"),
            Map.entry("Etagen", "Floors"),
            Map.entry("Wache", "Watch"),
            // 22.11. (Autor: "Es soll alles Englisch sein"): Quellen der Modi, die er gerade nutzt
            Map.entry("Konten", "Accounts"),
            Map.entry("Spiel-Quests", "Daily quests"),
            Map.entry("Verlies-Clan", "Clan dungeon"),
            Map.entry("Titan-Elixier", "Titan elixir"),
            Map.entry("Freunden senden", "Send to friends"),
            Map.entry("Nahrungskisten", "Food chests"),
            Map.entry("Helden-Set", "Hero set"),
            Map.entry("Burgen", "Castles"),
            Map.entry("Kauf", "Buy"),
            Map.entry("Halten", "Hold"),
            Map.entry("Tippen", "Typing"),
            Map.entry("Foto", "Photo"),
            Map.entry("Fotoserie", "Photo series"),
            Map.entry("Fotobremse", "Photo brake"),
            Map.entry("Spiel-Neustart", "Game restart"),
            Map.entry("Steuerprogramm", "Control program"),
            Map.entry("Hintergrund", "Background"),
            Map.entry("Tastenkuerzel", "Shortcuts"),
            Map.entry("Tastatur", "Keyboard"),
            Map.entry("Lesetest", "Read test"),
            Map.entry("Fehlerbericht", "Error report"),
            Map.entry("Zeitzone", "Time zone"),
            Map.entry("Uhr", "Clock"),
            Map.entry("Handy", "Phone"),
            Map.entry("Glyphen", "Glyphs"),
            Map.entry("Schicht", "Overlay"),
            Map.entry("Arena-Schicht", "Arena overlay"),
            Map.entry("Eigenbilder", "Own pictures"),
            Map.entry("Mauswache", "Mouse watch"));
}
