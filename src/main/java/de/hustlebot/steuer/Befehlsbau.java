package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;
import de.hustlebot.discord.Befehlsnamen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Baut aus Knoepfen, Auswahlfeldern und gesprochenen Woertern genau die Texte, die
 * auch in Discord getippt wuerden -- "!start cathedral 1000 c", "!stop", "!settings
 * bezahlen verlies food".
 *
 * EIN BEFEHLSWEG (Auftrag 1, 11.10.): Das Steuerprogramm baut hier nur den Text.
 * Ausgefuehrt wird er ueber dieselbe Verteilung wie ein Discord-Befehl
 * (DiscordBridge.befehlVonBedienfeld, derselbe Weg wie /api/befehl im WebServer).
 * Nichts wird doppelt gebaut: Was in Discord geht, geht damit auch hier.
 *
 * Alles in dieser Klasse ist ohne Bildschirm und ohne Spiel pruefbar.
 */
public final class Befehlsbau {

    private Befehlsbau() {
    }

    /** Die Modi im Start-Menue (doku/STEUERPROGRAMM.md). Das Befehlswort ist das, was !start versteht. */
    public enum Modus {
        KATHEDRALE("cathedral", Sprache.t("Cathedral", "Kathedrale"), true),
        VERLIES("dungeon", Sprache.t("Dungeon", "Verlies"), true),
        ARENA("arena", "Arena", true),
        GRABUNG("grabung", Sprache.t("Titan excavation", "Titan-Grabung"), true),
        TITANTURM("titantower", Sprache.t("Titan tower", "Titanen-Turm"), false),
        LEUCHTTURM("lighthouse", Sprache.t("Lighthouse", "Leuchtturm"), false),
        KOLOSSEUM("colosseum", Sprache.t("Colosseum", "Kolosseum"), false),
        CLANKRIEG("clanwar", Sprache.t("Clan war", "Clan-Krieg"), false),
        HAMMER("event hammer", Sprache.t("Hammer event", "Hammer-Event"), true),
        // Autor 23.10.: "Edelsteinbucht auch ueber den Modus und den Startknopf im Bot-Programm, nicht ueber den Command."
        GEMBAY("gembay", Sprache.t("Gem Bay", "Edelsteinbucht"), false),
        // Autor 23.10.: Portal mit Aufstieg bis Level und Farm-Liste (Felder im Bot-Fenster).
        PORTAL("portal", "Portal", false),
        // Autor 18.11.: taegliche Spiel-Quests (Buch in der Burg -> Daily quests), haengt die passenden Modi an.
        SPIELQUESTS("spielquests", Sprache.t("Daily game quests", "Tages-Spielquests"), false);

        /** So steht es hinter "!start". */
        public final String befehlswort;
        /** So heisst es fuer Autor. */
        public final String anzeige;
        /** Ob eine Anzahl Sinn hat (Leuchtturm, Turm, Clan-Krieg laufen genau einmal). */
        public final boolean mitAnzahl;

        Modus(String befehlswort, String anzeige, boolean mitAnzahl) {
            this.befehlswort = befehlswort;
            this.anzeige = anzeige;
            this.mitAnzahl = mitAnzahl;
        }

        @Override
        public String toString() {
            return anzeige;
        }

        /** Den Modus zu einem Wort finden -- deutsch, englisch oder das Befehlswort. Null, wenn nichts passt. */
        public static Modus aus(String wort) {
            if (wort == null) return null;
            String w = wort.toLowerCase(Locale.ROOT).trim()
                    .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue");
            if (w.isEmpty()) return null;
            if (w.startsWith("kath") || w.startsWith("cath") || w.equals("dk") || w.equals("kirche")) return KATHEDRALE;
            if (w.startsWith("verl") || w.startsWith("dung")) return VERLIES;
            if (w.startsWith("arena") || w.startsWith("turnier") || w.startsWith("tournament")) return ARENA;
            if (w.startsWith("grab") || w.startsWith("excav") || w.startsWith("titan-grab") || w.equals("dig")
                    || w.startsWith("titangrab") || w.startsWith("titanevent") || w.startsWith("titan-event")) return GRABUNG;
            if (w.startsWith("titan") || w.startsWith("turm") || w.startsWith("tower") || w.equals("tt")) return TITANTURM;
            if (w.startsWith("leucht") || w.startsWith("lighth")) return LEUCHTTURM;
            if (w.startsWith("kolos") || w.startsWith("colos")) return KOLOSSEUM;
            if (w.startsWith("clan") || w.startsWith("krieg") || w.startsWith("war") || w.equals("cw")) return CLANKRIEG;
            if (w.startsWith("hammer") || w.startsWith("event")) return HAMMER;
            if (w.startsWith("gem") || w.startsWith("edelstein")) return GEMBAY;
            if (w.startsWith("portal")) return PORTAL;
            if (w.startsWith("spielquest") || w.startsWith("dailyquest")) return SPIELQUESTS;
            return null;
        }
    }

    /** Die Stigma-Auftraege im Start-Menue: eigene Befehle, kein !start. */
    public enum Stigma {
        LOESCHEN("!stigmas loeschen", Sprache.t("Delete stigmas", "Stigmas löschen"), Sprache.t("Delete", "Löschen")),
        // Autor 13.10. (Punkt 1): nicht "leveln", sondern "filtern" -- Stats filtern, bessere einsetzen, gute hochleveln.
        LEVELN("!stigmas leveln", Sprache.t("Stigmas: filter stats", "Stigmas: Stats filtern"), Sprache.t("Filter stats", "Stats filtern")),
        HUNDERT("!stigmas hundert", Sprache.t("Level stigmas", "Stigmas leveln"), Sprache.t("Level", "Leveln")),
        EINSTELLEN("!stigmas einstellen", Sprache.t("Stigmas: settings (form)", "Stigmas: Einstellungen (Formular)"), Sprache.t("Settings", "Einstellungen")),
        RANGLISTE("!stigmas rangliste", Sprache.t("Stigmas: show ranking", "Stigmas: Rangliste zeigen"), Sprache.t("Ranking", "Rangliste"));

        public final String befehl;
        public final String anzeige;
        /** Kurz fuer die Auswahl im Bereich "Stigmas" des Steuerfensters. */
        public final String kurz;

        Stigma(String befehl, String anzeige, String kurz) {
            this.befehl = befehl;
            this.anzeige = anzeige;
            this.kurz = kurz;
        }

        @Override
        public String toString() {
            return anzeige;
        }

        /** Mit Titan ("medusa", "keeper") und wahlweise als Probelauf. */
        public String befehl(String titan, boolean probe) {
            StringBuilder b = new StringBuilder(befehl);
            // Loeschen gilt fuer alle Titanen gleich -- ein Titan-Wort dahinter lehnt der Bot ab (Pruefung 12.10.).
            if (titan != null && !titan.isBlank() && this != RANGLISTE && this != EINSTELLEN && this != LOESCHEN) {
                b.append(' ').append(titan.trim().toLowerCase(Locale.ROOT));
            }
            if (probe && this != RANGLISTE && this != EINSTELLEN) b.append(" probe");
            return b.toString();
        }
    }

    /** Wie bezahlt wird -- die Woerter, die extrasAbtrennen in der Bruecke versteht. */
    public enum Bezahlart {
        STANDARD("", Sprache.t("as set", "wie eingestellt")),
        TICKETS("tickets", Sprache.t("tickets / keys only", "nur Tickets / Schluessel")),
        FOOD("food", Sprache.t("food only", "nur Nahrung")),
        TICKETS_FOOD("tickets+food", Sprache.t("tickets first, then food", "erst Tickets, dann Nahrung")),
        ALLES("alles", Sprache.t("tickets, food, food chests", "Tickets, Nahrung, Nahrungskisten")),
        // Mia 21.10.: Nahrung, dann Nahrungskisten, nie Tickets
        FOOD_CHESTS("food+chests", Sprache.t("food, food chests (no tickets)", "Nahrung, Nahrungskisten (ohne Tickets)"));

        public final String wort;
        public final String anzeige;

        Bezahlart(String wort, String anzeige) {
            this.wort = wort;
            this.anzeige = anzeige;
        }

        @Override
        public String toString() {
            return anzeige;
        }
    }

    /** Feste Befehle fuer die acht Knoepfe der Steuerung. */
    public static final String PAUSE        = "!pause";
    public static final String WEITER       = "!weiter";
    public static final String STOP         = "!stop";
    public static final String NOTAUS       = "!notaus";
    public static final String WARTESCHLANGE = "!warteschlange";
    public static final String ABLAUF       = "!ablauf";
    public static final String ABLAUF_OHNE  = "!ablauf ohne";
    public static final String NEUSTART     = "!neustart";
    public static final String BEENDEN      = "!beenden";
    public static final String STATUS       = "!status";
    public static final String STATS        = "!stats";
    public static final String STAND        = "!stand";
    public static final String HILFE        = "!hilfe";

    /**
     * Befehle, die das Steuerprogramm NIE ohne Nachfrage ausfuehrt, wenn sie per Sprache oder
     * Tastenkuerzel kommen (Autor 11.10.: "gefaehrliche Befehle (PC aus, Not-Aus) fragen nach").
     * Ein Knopf im Fenster fragt bei Beenden, PC aus und Neustart ebenfalls; der Not-Aus-Knopf
     * selbst wirkt sofort -- dafuer ist er da.
     */
    private static final Set<String> GEFAEHRLICH = Set.of(
            "!notaus", "!beenden", "!quit", "!aus", "!pc", "!neustart", "!clear", "!clearall", "!sperren", "!an");

    /** Ist das ein Befehl, bei dem erst gefragt wird? Versteht alle Schreibweisen ("!off", "!quit"). */
    public static boolean gefaehrlich(String befehl) {
        String verb = verb(befehl);
        if (verb.isEmpty()) return false;
        // Freunde-Bot (16.10.): Befehlsnamen.kanonisch fuehrt manche Woerter auf andere (englische) Namen zurueck
        // als im Hauptbot -- darum das Wort selbst UND seine kanonische Form pruefen, sonst fehlt die Rueckfrage.
        String k = Befehlsnamen.kanonisch(verb);
        return GEFAEHRLICH.contains(verb) || GEFAEHRLICH.stream().anyMatch(g -> Befehlsnamen.kanonisch(g).equals(k));
    }

    /** Das erste Wort eines Befehls, klein und mit "!" davor. */
    public static String verb(String befehl) {
        if (befehl == null) return "";
        String t = befehl.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("!")) t = "!" + t.substring(1).stripLeading();
        // Code-Pruefung 15.10.: "aus jetzt" ohne "!" galt nicht als gefaehrlich -- senden() setzt das "!" erst danach
        // davor, und der PC ging ohne Rueckfrage aus. Darum hier wie dokumentiert immer mit "!".
        else if (!t.isEmpty()) t = "!" + t;
        int leer = t.indexOf(' ');
        return leer > 0 ? t.substring(0, leer) : t;
    }

    /**
     * "!start cathedral 1000 c" und Verwandte.
     *
     * @param modus    was gespielt wird
     * @param anzahl   wie oft; 0 oder weniger heisst "ohne Zahl" (Leuchtturm, Clan-Krieg, Hammer bis leer)
     * @param weg      Kathedrale: "b" (alle Wegpunkte) oder "c" (Turbo); sonst leer
     * @param bezahl   Bezahlart, darf null sein
     * @param freund   Verlies: Name des Mitspielers, darf leer sein
     * @param extras   weitere Woerter wie "pcaus", "ohnekrieg", "ohneorte", "eventablauf"
     */
    public static String start(Modus modus, int anzahl, String weg, Bezahlart bezahl, String freund,
                               List<String> extras) {
        return "!start " + auftrag(modus, anzahl, weg, bezahl, freund, extras);
    }

    /** "!after cathedral 50 c" -- haengt einen Modus hinter den laufenden. */
    public static String danach(Modus modus, int anzahl, String weg, Bezahlart bezahl, String freund) {
        return "!after " + auftrag(modus, anzahl, weg, bezahl, freund, List.of());
    }

    /** Der Auftrag ohne "!start" davor ("cathedral 50 c food") -- auch ein Eintrag der Warteschlange (Autor 15.11.). */
    static String auftrag(Modus modus, int anzahl, String weg, Bezahlart bezahl, String freund,
                          List<String> extras) {
        if (modus == null) throw new IllegalArgumentException(Sprache.t("Mode missing", "Modus fehlt"));
        StringBuilder b = new StringBuilder(modus.befehlswort);
        if (modus.mitAnzahl && anzahl > 0) b.append(' ').append(Math.min(anzahl, 100_000));
        if (modus == Modus.KATHEDRALE && weg != null) {
            String w = weg.trim().toLowerCase(Locale.ROOT);
            if (w.equals("b") || w.equals("c")) b.append(' ').append(w);
        }
        if (modus == Modus.VERLIES && freund != null && !freund.isBlank()) {
            b.append(' ').append(freund.trim().replace(' ', ','));
        }
        if (bezahl != null && !bezahl.wort.isEmpty()) b.append(' ').append(bezahl.wort);
        if (extras != null) {
            for (String e : extras) {
                // "eventablauf" macht im Bot jeden Auftrag zur Kathedrale -- nur dort mitschicken (Pruefung 12.10.).
                if (e != null && modus != Modus.KATHEDRALE && e.trim().equalsIgnoreCase("eventablauf")) continue;
                if (e != null && !e.isBlank()) b.append(' ').append(e.trim().toLowerCase(Locale.ROOT));
            }
        }
        return b.toString();
    }

    /**
     * Portal-Plan aus den Feldern im Bot-Fenster: "bis 135" und "farm 80:2mio,135:2mio,130:1,5mio" (Autor 23.10.).
     * Das Farm-Feld darf Leerzeichen und ";" haben ("80: 2 Mio; 135: 2 Mio") -- daraus wird ein Wort.
     */
    public static List<String> portalPlan(String bis, String farm) {
        List<String> l = new java.util.ArrayList<>();
        String b = bis == null ? "" : bis.replaceAll("[^0-9]", "");
        if (!b.isEmpty() && b.length() <= 3) l.add("bis " + b);
        String f = farm == null ? "" : farm.toLowerCase(Locale.ROOT).replace("millionen", "mio").replace("million", "mio")
                .replaceAll("\\s+", "").replace(';', ',');
        if (f.matches(".*\\d{1,3}:.*")) l.add("farm " + f);
        return l;
    }

    /**
     * Autor 23.10. abends: hoechstens 2 Farm-Level, je mit der hoechsten Nahrung -> "135:2mio,80:500k" fuer portalPlan.
     * Ein Level ohne Nahrung (oder umgekehrt) faellt weg.
     */
    public static String portalFarm(String level1, String nahrung1, String level2, String nahrung2) {
        StringBuilder b = new StringBuilder();
        String[][] paare = {{level1, nahrung1}, {level2, nahrung2}};
        for (String[] p : paare) {
            String l = p[0] == null ? "" : p[0].replaceAll("[^0-9]", "");
            String n = p[1] == null ? "" : p[1].trim();
            if (l.isEmpty() || l.length() > 3 || n.replaceAll("[^0-9]", "").isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(l).append(':').append(n);
        }
        return b.toString();
    }

    /** "!settings bezahlen verlies food" -- Bereich und Wert, leere Teile fallen weg. */
    public static String einstellung(String bereich, String wert) {
        StringBuilder b = new StringBuilder("!settings");
        if (bereich != null && !bereich.isBlank()) b.append(' ').append(bereich.trim());
        if (wert != null && !wert.isBlank()) b.append(' ').append(wert.trim());
        return b.toString();
    }

    /** "!ende 06:30" -- Uhrzeit, zu der der Bot von selbst aufhoert; leer = anzeigen, "aus" = loeschen. */
    public static String ende(String uhrzeit) {
        String u = uhrzeit == null ? "" : uhrzeit.trim();
        return u.isEmpty() ? "!ende" : "!ende " + u;
    }

    /** "!aus 06:00" (PC zu dieser Zeit aus), "!aus jetzt", "!aus weg". */
    public static String pcAus(String wann) {
        String w = wann == null ? "" : wann.trim();
        return w.isEmpty() ? "!aus" : "!aus " + w;
    }

    /** Eine Zahl aus einem Text (erste Ziffernfolge), sonst der Vorgabewert. */
    public static int zahl(String text, int vorgabe) {
        if (text == null) return vorgabe;
        // Code-Review 15.10.: "1.000" ergab 1 -- Tausenderpunkte (und -kommas/-leerzeichen) erst entfernen.
        text = text.replaceAll("(?<=\\d)[.,\\s](?=\\d{3}(?:\\D|$))", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,7}").matcher(text);
        if (!m.find()) return vorgabe;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    /** Die Woerter eines Befehls, fuer Tests und Anzeige. */
    public static List<String> woerter(String befehl) {
        List<String> l = new ArrayList<>();
        if (befehl == null) return l;
        for (String w : befehl.trim().split("\\s+")) {
            if (!w.isEmpty()) l.add(w);
        }
        return l;
    }
}
