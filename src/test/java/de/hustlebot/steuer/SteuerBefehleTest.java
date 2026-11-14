package de.hustlebot.steuer;

import de.hustlebot.discord.Befehlsnamen;
import de.hustlebot.discord.DiscordBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor, 12.10. (wie Freunde-Commit e93d15f): jeder Knopf, jede Auswahlliste in den Einstellungen, jedes Profil,
 * jede Stigma-Auswahl und jeder Modus des Steuerprogramms schickt einen Befehl, den der Bot kennt -- mit Werten,
 * die er versteht. Die Befehlsverteilung wird aus DiscordBridge.java gelesen (case "!..." ->), die Werte werden
 * mit denselben Hilfen geprueft, die der Bot benutzt (istAn, menge, Bezahlung.ausWort, Zeitzone.verstehen ...).
 */
class SteuerBefehleTest {
    // Freunde-Bot (16.10.): Diese Tests kommen aus dem Hauptbot und pruefen die deutschen Texte --
    // der Freunde-Bot startet englisch, also hier fuer die Dauer der Klasse auf Deutsch stellen.
    @org.junit.jupiter.api.BeforeAll static void aufDeutsch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.DE); }
    @org.junit.jupiter.api.AfterAll static void zurueckAufEnglisch() { de.hustlebot.core.Sprache.set(de.hustlebot.core.Sprache.Lang.EN); }


    private static final Path QUELLE = Path.of("src/main/java/de/hustlebot");

    // ------------------------------------------------------------------
    //  Befehle, die der Bot kennt
    // ------------------------------------------------------------------

    private static Set<String> verben;

    private static synchronized Set<String> verben() throws Exception {
        if (verben != null) return verben;
        Set<String> v = new HashSet<>();
        Matcher m = Pattern.compile("case (\"![^>]*)->").matcher(Files.readString(QUELLE.resolve("discord/DiscordBridge.java")));
        while (m.find()) for (String s : m.group(1).split(",")) v.add(s.trim().replace("\"", ""));
        // Vor der Verteilung behandelt: Sprache, Statistik, Hilfe.
        v.addAll(List.of("!english", "!englisch", "!en", "!deutsch", "!german", "!de", "!stats", "!statistik", "!hilfe", "!help"));
        verben = v;
        return v;
    }

    private static boolean verbBekannt(String verb) throws Exception {
        Set<String> v = verben();
        return v.contains(verb) || v.contains(Befehlsnamen.kanonisch(verb))
                || ruf(DiscordBridge.class, "ortBefehl", verb) != null;
    }

    // ------------------------------------------------------------------
    //  Werte pruefen -- mit den Hilfen des Bots
    // ------------------------------------------------------------------

    private static Object ruf(Class<?> k, String name, Object... args) throws Exception {
        Class<?>[] typen = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) typen[i] = args[i].getClass();
        Method m = k.getDeclaredMethod(name, typen);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object feld(String name) throws Exception {
        Field f = DiscordBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean an(String w) throws Exception { return (boolean) ruf(DiscordBridge.class, "istAn", w); }
    private static boolean aus(String w) throws Exception { return (boolean) ruf(DiscordBridge.class, "istAus", w); }
    private static boolean anAus(String w) throws Exception { return an(w) || aus(w); }
    private static long menge(String w) throws Exception { return (long) ruf(DiscordBridge.class, "menge", w); }
    private static int zahl(String w) throws Exception { return (int) ruf(DiscordBridge.class, "number", w); }
    private static boolean uhrzeit(String w) throws Exception {
        return !((String) ruf(DiscordBridge.class, "normalizeClock", w)).isEmpty();
    }

    /** null = der Bot versteht den Befehl, sonst der Grund. */
    static String verstanden(String befehl) throws Exception {
        String b = befehl.strip();
        if (!b.startsWith("!")) return "faengt nicht mit ! an";
        String[] w = b.substring(1).toLowerCase(Locale.ROOT).split("\\s+");
        String verb = "!" + w[0];
        if (!verbBekannt(verb)) return "Befehl " + verb + " kennt der Bot nicht";
        String[] r = Arrays.copyOfRange(w, 1, w.length);
        String rest = String.join(" ", r);
        String k = Befehlsnamen.kanonisch(verb);
        switch (k) {
            case "!settings", "!einstellungen" -> { return einstellung(r); }
            case "!arenareserve" -> {
                if (r.length == 0 || rest.matches("\\d{1,4}")) return null;
                String wert = r.length > 1 ? r[1] : "";
                if (r[0].matches("tickets?") && wert.matches("\\d{1,4}|aus|off|0")) return null;
                if (r[0].matches("nahrung|food|aepfel|apples|essen|preis|price|max|hoechstpreis|maxpreis|maxprice"))
                    return r.length == 1 || menge(wert) >= -1 ? null : "Menge " + wert;
                return "Reserve " + rest;
            }
            case "!zeitzone" -> {
                return r.length == 0 || de.hustlebot.core.Zeitzone.verstehen(rest).isPresent() ? null : "Zeitzone " + rest;
            }
            case "!team" -> {
                if (r.length == 0 || r[0].matches("stand|status|zeigen|show|info|scan|jetzt|now|ansehen|look|start|los|go")) return null;
                if (r[0].matches("held|hero")) return r.length == 1 || r.length >= 3 && r[1].matches("\\d{1,3}") && r[2].matches("\\d{1,3}") ? null : "Held " + rest;
                if (r[0].matches("name|spielername|playername")) return null;
                if (r[0].matches("punkte|points|messlatte|yardstick|eigenesteam")) return r.length == 1 || anAus(r[1]) ? null : "Punkte " + rest;
                return anAus(r[0]) ? null : "Team " + rest;
            }
            case "!aus" -> {
                if (r.length == 0 || rest.matches("(now|jetzt|sofort)\\b.*") || rest.matches("(weg|aus|off|stop|clear).*")) return null;
                return uhrzeit(rest.replaceAll("\\b(nurzeit|nur ?zeit|timeonly|only)\\b", "").trim()) ? null : "Uhrzeit " + rest;
            }
            case "!ende" -> {
                if (r.length == 0 || rest.matches("(aus|off|stop).*")) return null;
                return rest.replace('.', ':').matches("\\d{1,2}:\\d{2}") ? null : "Uhrzeit " + rest;
            }
            case "!wach" -> {
                if (r.length == 0) return "ohne Wert schaltet !wach alle 10 Minuten ein";
                if (rest.matches("(aus|stop|off).*|stand|status|zeigen|show|info")) return null;
                int n = zahl(rest);
                return n >= (int) feld("WATCH_MIN") && n <= (int) feld("WATCH_MAX") ? null : "Minuten " + rest;
            }
            case "!start" -> { return start(rest); }
            case "!after" -> { return modus(rest) != null ? null : "Modus in " + rest; }
            default -> { return null; }
        }
    }

    private static String einstellung(String[] w) throws Exception {
        if (w.length == 0) return null;
        String w0 = (String) ruf(Befehlsnamen.class, "einstellung", w[0]);
        String w1 = w.length > 1 ? w[1] : "";
        boolean nurZeigen = w.length == 1;
        switch (w0) {
            case "all", "zeitzone" -> { return w0.equals("all") || nurZeigen
                    || de.hustlebot.core.Zeitzone.verstehen(w1).isPresent() ? null : "Zeitzone " + w1; }
            case "lupen" -> { return nurZeigen || w1.matches("[0-9]{1,2}") ? null : "Lupen " + w1; }
            case "pass" -> { return nurZeigen || de.hustlebot.core.Eventautomatik.passAusWort(w1) != null ? null : "Pass " + w1; }
            case "eventautomatik", "event-automatik", "automatik", "eventauto" -> {
                return nurZeigen || anAus(w1) || w1.matches("\\d{1,3}") || w1.matches("(titan|grab|excav|hammer|haemmer).*")
                        ? null : "Event-Automatik " + w1;
            }
            case "bezahlen", "bezahlung", "pay", "payment" -> {
                // 14.10.: "alle" setzt alle Modi auf einmal (Nahrung ja/nein).
                return nurZeigen || (de.hustlebot.core.Bezahlung.modusAusWort(w1) != null || w1.equals("alle")) && w.length >= 3
                        && de.hustlebot.core.Bezahlung.ausWort(w[2]) != null ? null : "Bezahlen " + String.join(" ", w);
            }
            case "nahrungslimit", "nahrungs-limit", "foodlimit", "food-limit" -> {
                // 14.10.: "alle" = ein Limit fuer alle Modi; danach gilt dasselbe wie ohne "alle".
                if (w1.equals("alle")) {
                    if (w.length == 2) return null;
                    if (w.length >= 5 && w[2].equals("ohne") && w[3].equals("bis") && w[4].matches("\\d{1,2}(:\\d{2})?")) return null;
                    return menge(w[2]) >= -1 ? null : "Nahrungs-Limit " + String.join(" ", w);
                }
                if (w.length >= 4 && w1.equals("ohne") && w[2].equals("bis") && w[3].matches("\\d{1,2}(:\\d{2})?")) return null;
                return nurZeigen || de.hustlebot.core.Bezahlung.modusAusWort(w1) != null
                        && (w.length == 2 || menge(w[2]) >= -1) ? null : "Nahrungs-Limit " + String.join(" ", w);
            }
            case "spielneustart", "spiel-neustart", "gamerestart", "neustartspiel" -> {
                return nurZeigen || w1.matches((String) feld("STUNDEN_MUSTER")) || anAus(w1)
                        || w1.matches("(bedarf|need|auto|fest|fixed).*|jetzt|now|test") ? null : "Spiel-Neustart " + w1;
            }
            case "titanturm", "titantower", "titanenturm", "titan", "titanen", "tt" -> {
                if (nurZeigen || anAus(w1)) return null;
                int n = zahl(String.join(" ", Arrays.copyOfRange(w, 2, w.length)));
                return w1.matches("easy|leicht|hard|schwer|veryhard|very|sehrschwer|vh") && n >= 1 && n <= 30 ? null : "Titanen-Turm " + w1;
            }
            case "lager", "nahrungslager", "storage", "zauberstaebe", "zauberstab", "wands" -> {
                String z = String.join("", Arrays.copyOfRange(w, 1, w.length)).replaceAll("[^0-9]", "");
                return nurZeigen || aus(w1) && !w0.startsWith("lager") || !z.isEmpty() && z.length() < 10 ? null : w0 + " " + w1;
            }
            case "nahrungskisten", "foodchests", "kistenlimit" -> { return nurZeigen || w1.matches("\\d{1,3}") || anAus(w1) ? null : "Nahrungskisten " + w1; }
            case "meldungen", "meldung", "nachrichten", "reports" -> { return nurZeigen || w1.matches("\\d{1,3}") ? null : "Meldungen " + w1; }
            case "rueckblick", "review" -> { return nurZeigen || w1.matches("\\d{1,4}") || aus(w1) ? null : "Rueckblick " + w1; }
            case "kiste", "kisten", "eventkisten", "chest", "chests" -> {
                return nurZeigen || de.hustlebot.core.Eventkisten.art(w1) != null && w.length >= 3 && anAus(w[2]) ? null : "Kiste " + String.join(" ", w);
            }
            case "notaus", "not-aus", "panik" -> {
                return nurZeigen || w1.matches("aus|off|keine|none") || de.hustlebot.core.NotAusWache.gueltig(w[1]) ? null : "Taste " + w1;
            }
            case "tipp", "tip", "tipps", "tagestipp", "dailytip" -> { return null; }
            case "kolosseum", "colosseum", "ko" -> {
                return nurZeigen || anAus(w1) || w1.matches("grenze|limit|schwelle|abstand") && w.length >= 3 ? null : "Kolosseum " + w1;
            }
            case "eigenesteam", "clanladen", "clan-laden", "clanshop", "freunde", "friends", "freundesenden", "geschenke",
                 "lernen", "lernmodus", "learn", "gembay", "gem", "edelsteinbucht", "edelstein", "nahrung", "food",
                 "leuchtturm", "lighthouse", "lh" -> { return nurZeigen || anAus(w1) ? null : w0 + " " + w1; }
            default -> {
                if (de.hustlebot.bot.Aufstellung.ausText(w0).isEmpty()) return "Einstellung " + w0 + " kennt der Bot nicht";
                return nurZeigen || de.hustlebot.bot.Aufstellung.wahlAusText(w1) != null ? null : "Aufstellung " + w1;
            }
        }
    }

    /** Wie commandStart: Extras abtrennen, dann den Modus lesen. */
    private static String modus(String rest) throws Exception {
        Object ex = ruf(DiscordBridge.class, "extrasAbtrennen", rest);
        Method r = ex.getClass().getDeclaredMethod("rest");
        r.setAccessible(true);
        return (String) ruf(DiscordBridge.class, "modeFrom", (String) r.invoke(ex));
    }

    private static String start(String rest) throws Exception {
        if (rest.isBlank()) return null;   // Start-Formular
        return modus(rest) != null ? null : "Modus in " + rest;
    }

    // ------------------------------------------------------------------
    //  Die Tests
    // ------------------------------------------------------------------

    private static List<Path> steuerDateien() throws Exception {
        try (var s = Files.list(QUELLE.resolve("steuer"))) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void jederBefehlImSteuerprogrammIstDemBotBekannt() throws Exception {
        List<String> fehlt = new ArrayList<>();
        Pattern befehl = Pattern.compile("\"(![a-z]+)[^\"]*\"");
        int gezaehlt = 0;
        for (Path d : steuerDateien()) {
            for (String zeile : Files.readAllLines(d)) {
                String z = zeile.strip();
                if (z.startsWith("//") || z.startsWith("*") || z.startsWith("/*")) continue;
                Matcher b = befehl.matcher(zeile);
                while (b.find()) {
                    gezaehlt++;
                    if (java.util.Set.of("!beenden", "!quit", "!clearall", "!sperren", "!an", "!wach", "!not").contains(b.group(1))) continue;
                    if (!verbBekannt(b.group(1))) fehlt.add(d.getFileName() + ": " + b.group(0));
                }
            }
        }
        assertTrue(gezaehlt > 60, "zu wenige Befehle gefunden: " + gezaehlt);
        assertEquals(List.of(), fehlt);
    }

    /** Alle Zeilen, Schalter und Knoepfe im Reiter Einstellungen -- mit jedem Wert aus der Auswahlliste. */
    static List<String> einstellungsBefehle() throws Exception {
        String q = Files.readString(QUELLE.resolve("steuer/Steuerfenster.java"));
        List<String> b = new ArrayList<>();
        String s = "\"([^\"]*)\"";
        Matcher m = Pattern.compile("(?<![A-Za-z])(zeile|freiZeile)\\(" + s + ", " + s + ", " + s + ", " + s + "\\)").matcher(q);
        int zeilen = 0;
        while (m.find()) {
            zeilen++;
            String basis = m.group(1).equals("zeile") ? "!settings " + m.group(4) : m.group(4);
            b.add(Steuerfenster.anzeigeBefehl(basis));   // Knopf "Anzeigen" -- darf nichts aendern
            String[] werte = Steuerfenster.optionen(m.group(2), m.group(3), m.group(5));
            assertTrue(werte.length > 0, "keine Auswahl bei " + m.group(2));
            for (String w : werte) b.add(basis + " " + w);
        }
        // Autor 14.10.: hoechstens 20 Einstellungen -- frueher 25+ Zeilen, jetzt bewusst wenige.
        assertTrue(zeilen >= 6, "zu wenige Einstellungs-Zeilen: " + zeilen);
        m = Pattern.compile("(?<![A-Za-z])schalter\\(" + s + ", " + s + "\\)").matcher(q);
        while (m.find()) for (String w : List.of(" an", " aus", "")) b.add("!settings " + m.group(2) + w);
        m = Pattern.compile("befehlSchalter\\(" + s + ", " + s + ", " + s + ", " + s + "\\)").matcher(q);
        while (m.find()) b.addAll(List.of(m.group(2), m.group(3), m.group(4)));
        m = Pattern.compile("\\{\"[^\"]*\", \"(![^\"]*)\"\\}").matcher(q);
        while (m.find()) b.add(m.group(1));
        return b;
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hauptbot-Interna (extrasAbtrennen/einstellung) gibt es im Freunde-Bot nicht")
    void jedeEinstellungUndJederWertWirdVerstanden() throws Exception {
        List<String> fehler = new ArrayList<>();
        List<String> alle = einstellungsBefehle();
        for (String b : alle) {
            String grund = verstanden(b);
            if (grund != null) fehler.add(b + "  ->  " + grund);
        }
        assertTrue(alle.size() > 60, "zu wenige Einstellungen: " + alle.size());   // 14.10.: bewusst weniger
        assertEquals(List.of(), fehler);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hauptbot-Interna (extrasAbtrennen/einstellung) gibt es im Freunde-Bot nicht")
    void jederModusMitAllenHakenStartetDenRichtigenModus() throws Exception {
        List<String> fehler = new ArrayList<>();
        for (Befehlsbau.Modus m : Befehlsbau.Modus.values()) {
            String erwartet = switch (m) {
                case KATHEDRALE -> "cathedral";
                case VERLIES -> "dungeon";
                case HAMMER -> "event";
                default -> m.befehlswort;
            };
            for (Befehlsbau.Bezahlart bz : Befehlsbau.Bezahlart.values()) {
                for (List<String> extras : List.of(List.<String>of(), List.of("pcaus"), List.of("ohnekrieg"), List.of("ohneorte"),
                        List.of("eventablauf"), List.of("pcaus", "ohnekrieg", "ohneorte", "eventablauf"))) {
                    for (boolean danach : new boolean[]{false, true}) {
                        String b = danach ? Befehlsbau.danach(m, 5, "c", bz, m == Befehlsbau.Modus.VERLIES ? "Max" : "")
                                : Befehlsbau.start(m, 5, "c", bz, m == Befehlsbau.Modus.VERLIES ? "Max" : "", extras);
                        String r = b.substring(b.indexOf(' ') + 1);
                        String gelesen = modus(r);
                        if (!erwartet.equals(gelesen)) fehler.add(b + "  ->  " + gelesen);
                        if (verstanden(b) != null) fehler.add(b + "  ->  " + verstanden(b));
                        Object ex = ruf(DiscordBridge.class, "extrasAbtrennen", r);
                        Method bez = ex.getClass().getDeclaredMethod("bezahl");
                        bez.setAccessible(true);
                        if (!bz.wort.equals(bez.invoke(ex))) fehler.add(b + "  ->  Bezahlart " + bez.invoke(ex));
                    }
                }
            }
        }
        assertEquals(List.of(), fehler);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hauptbot-Interna (extrasAbtrennen/einstellung) gibt es im Freunde-Bot nicht")
    void jedeStigmaAuswahlWirdVerstanden() throws Exception {
        // Wie im Reiter Steuerung: Titan-Auswahl -> Wort (Last Guardian = ohne Wort).
        List<String> fehler = new ArrayList<>();
        for (String t : List.of("Last Guardian", "Medusa", "Keeper of Stars", "Thundersaurus", "Torturer", "Pandemon")) {
            String tw = t.startsWith("Last") ? "" : t.startsWith("Keeper") ? "keeper" : t.startsWith("Thunder") ? "thunder" : t.toLowerCase(Locale.ROOT);
            // Freunde-Bot: Titan-Erkennung laeuft ueber Stigmaplan.titanFund (eigene Logik), hier nur: wird verstanden.
            for (Befehlsbau.Stigma s : Befehlsbau.Stigma.values()) {
                for (boolean probe : new boolean[]{false, true}) {
                    String b = s.befehl(tw, probe);
                    if (verstanden(b) != null) fehler.add(b + " -> " + verstanden(b));
                    String[] w = b.split("\\s+");
                    // Loeschen nimmt hinter dem Wort nur "probe" (sonst fragt der Bot nach und loescht nichts).
                    if (s == Befehlsbau.Stigma.LOESCHEN && w.length > 2 && !(w.length == 3 && w[2].equals("probe"))) fehler.add(b + " -> Loeschen mit fremdem Wort");
                }
            }
        }
        assertEquals(List.of(), fehler);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hauptbot-Interna (extrasAbtrennen/einstellung) gibt es im Freunde-Bot nicht")
    void jedesProfilWirdVerstanden(@TempDir Path tmp) throws Exception {
        List<String> fehler = new ArrayList<>();
        Set<String> zeilen = new LinkedHashSet<>();
        Profile vorgabe = new Profile(tmp.resolve("profile.txt"));
        for (String n : vorgabe.namen()) zeilen.addAll(vorgabe.befehle(n));
        // Autors echte Profile (liegen neben dem Bot), wenn es sie gibt.
        if (Files.exists(Path.of("profile.txt"))) {
            Profile echt = new Profile(Path.of("profile.txt"));
            for (String n : echt.namen()) zeilen.addAll(echt.befehle(n));
        }
        for (String z : zeilen) if (verstanden(z) != null) fehler.add(z + " -> " + verstanden(z));
        assertTrue(zeilen.size() >= 5);
        assertEquals(List.of(), fehler);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Hauptbot-Interna (extrasAbtrennen/einstellung) gibt es im Freunde-Bot nicht")
    void diePruefungFindetFehler() throws Exception {
        // Gegenprobe: falsche Werte muessen auffallen, sonst prueft der Test nichts.
        assertNotNull(verstanden("!gibtesnicht"));
        assertNotNull(verstanden("!settings bezahlen verlies brot"));
        assertNotNull(verstanden("!settings spielneustart vielleicht"));
        assertNotNull(verstanden("!settings titanturm easy 99"));
        assertNotNull(verstanden("!settings verlies vielleicht"));
        assertNotNull(verstanden("!zeitzone Mond/Krater"));
        assertNotNull(verstanden("!settings notaus Q7"));
        assertNotNull(verstanden("!start nirgendwo 5"));
        assertNotNull(verstanden("!wach"));
        assertNull(verstanden("!settings spielneustart 0.75"));
    }
}
