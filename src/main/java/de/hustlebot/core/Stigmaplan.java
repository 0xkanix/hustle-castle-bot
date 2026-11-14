package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Was der Bot bei den Stigmas sucht, hochlevelt, tauscht und beim Loeschen behaelt (!stigmas einstellen).
 *
 * Autor, 11.10. 02:05: "Sets und Stats in den Einstellungen oder einem Discord-Formular waehlbar"; 02:50: "dass der
 * Bot auch auf viermal oder dreimal Ruestung filtern kann, wenn ich es so will". Gespeichert in
 * stigma-einstellungen.txt (Zeilen "schluessel=a,b,c"); fehlt die Datei oder ist sie kaputt, gelten Autors Regeln:
 *  - Sorten: alle sechs (Mind, Will, Courage, Strength, Resolve, Tenacity)
 *  - Hochleveln: 3x (bis Level 3-4) und 4x/5x (bis Level 20) magischer Schaden, Leben, Schaden; 3x eines anderen
 *    Werts mit einem lila Strahl (+2 Level)
 *  - Tauschen: Hauptwert magischer Schaden, Zweitwert Leben (Autors Rangliste mit 11 Stufen)
 *  - Beim Loeschen behalten: Bull, Dragon, Wolf
 */
public final class Stigmaplan {

    private Stigmaplan() {}

    private static final Path DATEI = Path.of("stigma-einstellungen.txt");
    /** Autors eigene Rangliste (eine Stufe je Zeile, oben = am besten). Fehlt sie, gilt die Standard-Liste. */
    private static final Path RANG_DATEI = Path.of("stigma-rangliste.txt");

    public static final List<String> NAMEN = List.of("mind", "will", "courage", "strength", "resolve", "tenacity");
    /** Die sieben Werte (Kuerzel), in der Reihenfolge fuer Discord. */
    public static final List<String> WERTE = List.of("md", "l", "d", "r", "mr", "krit", "dodge");
    /** Hochlevel-Regeln: je Wert "3" (genau 3x, bis Level 3-4) und "4" (4x/5x, bis Level 20), dazu anders3. */
    public static final List<String> LEVEL;
    static {
        List<String> l = new ArrayList<>();
        for (String w : WERTE) {
            l.add(w + "3");
            l.add(w + "4");
        }
        l.add("anders3");
        LEVEL = List.copyOf(l);
    }
    /** Alle bekannten Sets (Bull, Dragon, Wolf zuerst). */
    public static final List<String> SETS = List.of("bull", "dragon", "wolf", "hedgehog", "crocodile", "elephant",
            "tiger", "eagle", "phoenix", "hydra", "unicorn", "boar", "bear", "lion", "scorpion", "rat", "snake",
            "turtle", "bat", "spider");
    private static final Set<String> STANDARD_BEHALTEN = Set.of("bull", "dragon", "wolf");
    private static final Set<String> STANDARD_LEVEL = Set.of("md3", "md4", "l3", "l4", "d3", "d4", "anders3");

    private static volatile Set<String> namen;
    private static volatile Set<String> level;
    private static volatile String haupt;
    private static volatile String zweit;
    private static volatile Set<String> behalten;
    /** Eigene Rangliste als Text (null = Standard aus Haupt- und Zweitwert) und fertig gelesen. */
    private static volatile List<String> eigeneZeilen;
    private static volatile List<List<Muster>> eigeneStufen;

    static { laden(); }

    // ==========================================================================================================
    //  Titanen (Autor 11.10. 03:30): je Titan Haupt-/Zweitwert, Rangliste und Gewichte der Nebenwerte.
    //  Der Last Guardian nimmt die Einstellungen von "!stigmas einstellen", die anderen ihre festen Regeln.
    // ==========================================================================================================

    /** zeilen/gewichte null = Standard (Autors 11 Stufen bzw. Leben > mag. Ruestung > Dodge > Ruestung > Crit > Schaden). */
    public record Titan(String schluessel, String titelWort, String anzeige, String haupt, String zweit,
                        List<String> zeilen, java.util.Map<String, Integer> gewichte) {}

    public static final List<Titan> TITANEN = List.of(
            new Titan("lastguardian", "guardian", "Last Guardian", "md", "l", null, null),
            new Titan("medusa", "medusa", "Medusa", "md", "l", null, null),
            new Titan("thundersaurus", "thunder", "Thundersaurus", "md", "l", null, null),
            // Autor: "Beim Torturer auf Schaden plus Leben achten, nicht magischer Schaden."
            new Titan("torturer", "torturer", "Torturer", "d", "l", null, null),
            // Autor 03:30-03:40: Keeper of Stars ("Sternenwaechter") -- Leben und magische Ruestung GLEICH wichtig
            // (Gegner machen meist magischen Schaden), danach Dodge > Ruestung > alles andere (auch mag. Schaden).
            new Titan("keeperofstars", "keeper", "Keeper of Stars", "l", "mr",
                    List.of("5x Leben/mag. Rüstung", "4x Leben/mag. Rüstung", "3x Leben/mag. Rüstung",
                            "2x Leben/mag. Rüstung", "1x Leben/mag. Rüstung"),
                    java.util.Map.of("health", 0, "magic armor", 0, "dodge", 8, "armor", 6,
                            "crit", 2, "damage", 2, "magic damage", 2)));

    private static volatile Titan aktiv = TITANEN.get(0);

    /** Fuer welchen Titan gerade bewertet wird (StigmaMode setzt das beim Start). */
    public static void titanSetzen(String schluessel) { aktiv = titanFuer(schluessel); }
    public static Titan titan() { return aktiv; }

    /**
     * Titan mit festen eigenen Regeln (Medusa, Thundersaurus, Torturer, Keeper of Stars)? Freunde, Pruefung 11.10.: ein
     * frei genannter Titan ist der eigene Titan des Freundes -- er nimmt dessen Einstellungen (Tauschen nach, eigene
     * Rangliste) wie der Last Guardian, nicht fest mag. Schaden + Leben.
     */
    private static boolean eigenerTitan() { return !aktiv.schluessel().equals("lastguardian") && !frei(aktiv); }

    public static Titan titanFuer(String schluessel) {
        for (Titan t : TITANEN) if (t.schluessel().equals(schluessel)) return t;
        if (schluessel != null && schluessel.startsWith(EIGEN)) {
            String name = schluessel.substring(EIGEN.length());
            if (!titelWort(name).isEmpty()) {
                return new Titan(schluessel, titelWort(name), anzeigeName(name), "md", "l", null, null);
            }
        }
        return TITANEN.get(0);
    }

    // ==========================================================================================================
    //  FREUNDE (11.10.): Die Titanen der Freunde sind nicht Autors. "!stigmas leveln <name>" mit einem Namen, der
    //  keiner der fuenf bekannten ist, gilt als eigener Titan des Freundes: bewertet nach seinen Einstellungen (wie der
    //  Last Guardian, Pruefung 11.10.); der Name ist das Suchwort fuer die Titel-Pruefung oben auf dem Titan-Bildschirm.
    // ==========================================================================================================

    /** Schluessel-Anfang fuer einen frei genannten Titan ("name:frost giant"). */
    public static final String EIGEN = "name:";

    /** Ein frei genannter Titan (Einstellungen des Freundes), oder null, wenn der Name keine 3 Buchstaben hat. */
    public static Titan titanFrei(String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("ß", "ss").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (n.length() > 40) n = n.substring(0, 40).trim();
        if (titelWort(n).length() < 3) return null;
        return titanFuer(EIGEN + n);
    }

    /** Ein frei genannter Titan (nicht einer der fuenf bekannten)? */
    public static boolean frei(Titan t) {
        return t != null && t.schluessel().startsWith(EIGEN);
    }

    /** Nur Buchstaben und Ziffern, klein -- so wird auch der gelesene Titel verglichen ("Frost Giant" -> "frostgiant"). */
    public static String titelWort(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String anzeigeName(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /**
     * Freunde (Pruefung 11.10.): bekannte Titanen nur an GANZEN Woertern erkennen. Vorher reichten Teilwoerter ("kos",
     * "stern", "watcher", "saurus", "folter", "guardian") -- "nightwatcher" wurde Keeper of Stars, "...saurus" der
     * Thundersaurus, und es galten Autors feste Regeln. Volle Namen duerfen mit weiteren Woertern stehen (der Befehl
     * fragt dann nach), Kurzformen gelten nur, wenn sie der ganze Name sind.
     */
    private static final java.util.Map<String, String> VOLLE_NAMEN = java.util.Map.ofEntries(
            java.util.Map.entry("keeperofstars", "keeperofstars"), java.util.Map.entry("keeperofthestars", "keeperofstars"),
            java.util.Map.entry("sternenwaechter", "keeperofstars"), java.util.Map.entry("sternenwachter", "keeperofstars"),
            java.util.Map.entry("sternwaechter", "keeperofstars"), java.util.Map.entry("starwatcher", "keeperofstars"),
            java.util.Map.entry("medusa", "medusa"), java.util.Map.entry("meduse", "medusa"),
            java.util.Map.entry("thundersaurus", "thundersaurus"), java.util.Map.entry("donnersaurus", "thundersaurus"),
            java.util.Map.entry("torturer", "torturer"), java.util.Map.entry("folterer", "torturer"),
            java.util.Map.entry("lastguardian", "lastguardian"), java.util.Map.entry("letzterwaechter", "lastguardian"));
    private static final java.util.Map<String, String> KURZFORMEN = java.util.Map.of(
            "keeper", "keeperofstars", "kos", "keeperofstars", "thunder", "thundersaurus", "donner", "thundersaurus",
            "tortur", "torturer", "folter", "torturer", "guardian", "lastguardian", "lg", "lastguardian",
            "waechter", "lastguardian");

    /** Ein bekannter Titan in den Woertern von..bis (bis exklusiv). */
    public record Fund(Titan titan, int von, int bis) {}

    /**
     * Bekannter Titan aus den Woertern hinter dem Befehl ("keeper of stars", "medusa", "lg" ...), sonst null. Ist der
     * ganze Text ein Name oder eine Kurzform, deckt der Fund alle Woerter ab; sonst der laengste volle Name darin.
     */
    public static Fund titanFund(List<String> worte) {
        if (worte == null || worte.isEmpty()) return null;
        String alles = titelWort(String.join("", worte));
        String k = KURZFORMEN.containsKey(alles) ? KURZFORMEN.get(alles) : VOLLE_NAMEN.get(alles);
        if (k != null) return new Fund(titanFuer(k), 0, worte.size());
        Fund best = null;
        for (int i = 0; i < worte.size(); i++) {
            StringBuilder lauf = new StringBuilder();
            for (int j = i; j < worte.size() && j < i + 4; j++) {
                lauf.append(titelWort(worte.get(j)));
                String s = VOLLE_NAMEN.get(lauf.toString());
                if (s != null && (best == null || j + 1 - i > best.bis() - best.von())) best = new Fund(titanFuer(s), i, j + 1);
            }
        }
        return best;
    }

    // ==========================================================================================================
    //  Rangliste (Autor 11.10. 02:55: "dass ich theoretisch alle moeglichen Kombinationen rausfiltern kann, wie wir
    //  es gerade mit meiner Reihenfolge gemacht haben"). Eine Zeile je Stufe, oben = am besten:
    //      5x mag. Schaden oder 4x mag. Schaden + 1x Leben
    //      4x mag. Schaden ohne Schaden
    //      3x mag. Schaden + 2 verschiedene
    //  "Nx Wert" heisst GENAU N-mal, "ohne Wert" = 0-mal, "2 verschiedene" / "2 gleiche" = die uebrigen Werte sind alle
    //  verschieden / alle gleich, "oder" trennt Moeglichkeiten. Was keine Zeile trifft, kommt nach der letzten.
    // ==========================================================================================================

    /** Eine Moeglichkeit einer Stufe: genaue Anzahlen je Wert, dazu "Rest verschieden/gleich". */
    public record Muster(java.util.Map<String, Integer> genau, int rest) {   // rest: 0 egal, 1 verschieden, 2 gleich
        boolean passt(List<String> werte) {
            // Ein Schluessel kann eine Gruppe sein ("health|magic armor" = zusammen genau N, Autor 11.10.: "Leben und
            // magische Ruestung gleich wichtig").
            Set<String> benannt = new java.util.HashSet<>();
            for (var e : genau.entrySet()) {
                int n = 0;
                for (String teil : e.getKey().split("\\|")) {
                    n += anzahl(werte, teil);
                    benannt.add(teil);
                }
                if (n != e.getValue()) return false;
            }
            if (rest == 0) return true;
            List<String> uebrig = new ArrayList<>();
            for (String w : werte) if (!benannt.contains(w)) uebrig.add(w);
            Set<String> eins = new java.util.HashSet<>(uebrig);
            return rest == 1 ? eins.size() == uebrig.size() : eins.size() <= 1;
        }
    }

    public static boolean eigeneRangliste() { return eigeneZeilen != null; }

    /**
     * Die Rangliste aus "!stigmas einstellen" (gilt fuer den Last Guardian und frei genannte Titanen) -- egal, fuer
     * welchen Titan zuletzt gelevelt wurde. Freunde 11.10.: die fruehere ranglisteZeilen() hing am zuletzt gesetzten
     * Titan; nach "!stigmas leveln medusa" zeigte das Formular sonst Medusas Liste (darum entfernt).
     */
    public static List<String> einstellungsZeilen() {
        List<String> e = eigeneZeilen;
        return e != null ? e : gleicheZeilen();   // 23.10.: Grundregel 3x/4x/5x derselbe Wert (siehe gleicheRegel)
    }

    /** Autors 11 Stufen (10.10. 20:28), allgemein fuer Hauptwert H, Zweitwert Z und den "falschen" Schaden S. */
    public static List<String> standardZeilen(String h, String z) {
        String H = wertText(h), Z = wertText(z), S = wertText(h.equals("d") ? "md" : "d");
        // Freunde: auf Englisch "or/without/different" -- lesen() versteht beide Sprachen.
        String oder = Sprache.t(" or ", " oder "), ohne = Sprache.t(" without ", " ohne ");
        return List.of(
                "5x " + H + oder + "4x " + H + " + 1x " + Z,
                "4x " + H + ohne + S,
                "4x " + H,
                "5x " + Z + oder + "4x " + Z + " + 1x " + H,
                "4x " + Z + ohne + S,
                "4x " + Z,
                "3x " + H + " + 2x " + Z,
                "3x " + H + Sprache.t(" + 2 different", " + 2 verschiedene"),
                "3x " + H,
                "3x " + Z + " + 2x " + H);
    }

    /** Rang 1 = beste Stufe; was keine Stufe trifft, bekommt Stufen + 1. */
    public static int rang(List<String> werte) {
        if (gleicheRegel()) {
            int m = meisteGleiche(werte);
            return m >= 5 ? 1 : m == 4 ? 2 : m == 3 ? 3 : 4;
        }
        List<List<Muster>> stufen = aktiveStufen();
        for (int i = 0; i < stufen.size(); i++) {
            for (Muster m : stufen.get(i)) if (m.passt(werte)) return i + 1;
        }
        return stufen.size() + 1;
    }

    /** Der Rang, den "trifft keine Stufe" bekommt. */
    public static int restRang() {
        return gleicheRegel() ? 4 : aktiveStufen().size() + 1;
    }

    /**
     * Autor 23.10.: "Als Grundeinstellung immer nach Dreier-Stigmas filtern -- nach Dreier-, Vierer- und Fuenfer-Stigmas,
     * egal welcher Wert." Gilt, solange keine eigene Rangliste (!stigmas einstellen) und keine feste Titan-Liste (Keeper of
     * Stars) gilt: 5x derselbe Wert = Rang 1, 4x = Rang 2, 3x = Rang 3, sonst Rang 4. Bei gleichem Rang entscheiden wie
     * bisher die Gewichte der Werte.
     */
    public static boolean gleicheRegel() {
        return eigenerTitan() ? aktiv.zeilen() == null : eigeneStufen == null;
    }

    /** Wie oft kommt der haeufigste Wert vor ("3x Leben + 2 andere" -> 3)? */
    static int meisteGleiche(List<String> werte) {
        int best = 0;
        for (String w : new java.util.HashSet<>(werte)) best = Math.max(best, anzahl(werte, w));
        return best;
    }

    /** Die Grundregel als Zeilen fuer das Formular. */
    static List<String> gleicheZeilen() {
        return List.of(Sprache.t("5x the same value (any)", "5x derselbe Wert (egal welcher)"),
                Sprache.t("4x the same value (any)", "4x derselbe Wert (egal welcher)"),
                Sprache.t("3x the same value (any)", "3x derselbe Wert (egal welcher)"));
    }

    /** Die Stufen, die gerade gelten: Titan-eigene, Autors eigene Rangliste (Last Guardian) oder die Standard-Liste. */
    private static List<List<Muster>> aktiveStufen() {
        if (!eigenerTitan()) {
            List<List<Muster>> e = eigeneStufen;
            return e != null ? e : standardStufen();
        }
        Titan t = aktiv;
        String fuer = t.schluessel() + "#" + t.haupt() + "/" + t.zweit();
        if (!fuer.equals(titanStufenFuer)) {
            titanStufenCache = lesen(t.zeilen() != null ? t.zeilen() : standardZeilen(t.haupt(), t.zweit()), new StringBuilder());
            titanStufenFuer = fuer;
        }
        return titanStufenCache;
    }

    private static volatile List<List<Muster>> titanStufenCache;
    private static volatile String titanStufenFuer;

    private static volatile List<List<Muster>> standardStufenCache;
    private static volatile String standardStufenFuer;

    private static List<List<Muster>> standardStufen() {
        String fuer = haupt + "/" + zweit;
        if (!fuer.equals(standardStufenFuer)) {
            standardStufenCache = lesen(standardZeilen(haupt, zweit), new StringBuilder());
            standardStufenFuer = fuer;
        }
        return standardStufenCache;
    }

    /**
     * Eigene Rangliste setzen. Leer oder "standard" = zurueck zur Standard-Liste. Liefert null bei Erfolg, sonst die
     * Fehler (je Zeile), und dann bleibt alles, wie es war.
     */
    public static synchronized String ranglisteSetzen(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("standard") || t.equalsIgnoreCase("default")) {
            eigeneZeilen = null;
            eigeneStufen = null;
            try {
                if (Files.exists(RANG_DATEI)) Files.write(RANG_DATEI,
                        List.of(Sprache.t("# Empty = default ranking (from main and second stat under !stigmas settings).",
                                "# Leer = Standard-Rangliste (aus Haupt- und Zweitwert unter !stigmas einstellen).")), StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.warn("Stigmas", Sprache.t("stigma-rangliste.txt not reset: ", "stigma-rangliste.txt nicht zurueckgesetzt: ") + e.getMessage());
            }
            return null;
        }
        List<String> zeilen = new ArrayList<>();
        for (String z : t.split("\\r?\\n")) {
            String s = z.trim().replaceFirst("^\\d{1,2}\\s*[.:)]\\s*", "");
            // Code-Review 23.10.: Das Formular ist mit der Grundregel vorbelegt ("5x derselbe Wert (egal welcher)") --
            // diese Zeilen kannte lesen() nicht, unveraendert speichern schlug fehl. Sie bedeuten "Standard".
            if (s.matches("(?i)\\d\\s*x\\s+(derselbe wert|the same value)\\b.*")) continue;
            if (!s.isEmpty() && !s.startsWith("#")) zeilen.add(s);
        }
        if (zeilen.isEmpty()) return ranglisteSetzen("standard");
        if (zeilen.size() > 40) return Sprache.t("At most 40 lines.", "Hoechstens 40 Zeilen.");
        StringBuilder fehler = new StringBuilder();
        List<List<Muster>> stufen = lesen(zeilen, fehler);
        if (fehler.length() > 0) return fehler.toString();
        eigeneZeilen = List.copyOf(zeilen);
        eigeneStufen = stufen;
        try {
            Files.write(RANG_DATEI, zeilen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Stigmas", Sprache.t("stigma-rangliste.txt not saved: ", "stigma-rangliste.txt nicht gespeichert: ") + e.getMessage());
        }
        return null;
    }

    /** Text -> Stufen. Fehler kommen je Zeile in "fehler" ("Zeile 3: 'Ruestng' kenne ich nicht"). */
    static List<List<Muster>> lesen(List<String> zeilen, StringBuilder fehler) {
        List<List<Muster>> stufen = new ArrayList<>();
        for (int i = 0; i < zeilen.size(); i++) {
            List<Muster> moegl = new ArrayList<>();
            for (String alt : zeilen.get(i).toLowerCase(Locale.ROOT).split("\\s+oder\\s+|\\s+or\\s+|\\|")) {
                java.util.Map<String, Integer> genau = new java.util.LinkedHashMap<>();
                int rest = 0;
                boolean leer = true;
                // "4x mag. Schaden ohne Schaden": vor "ohne"/"kein" wird auch getrennt (das Wort bleibt am Teil).
                for (String roh : alt.split("\\+|,|\\s+und\\s+|\\s+and\\s+|\\s+mit\\s+|\\s+with\\s+"
                        + "|(?=\\s(?:ohne|kein|keine|keinen|no|without)\\s)")) {
                    String teil = roh.trim();
                    if (teil.isEmpty()) continue;
                    leer = false;
                    java.util.regex.Matcher ohne = java.util.regex.Pattern
                            .compile("^(?:ohne|kein|keine|keinen|no|without|0\\s*x?)\\s+(.+)$").matcher(teil);
                    java.util.regex.Matcher zahl = java.util.regex.Pattern.compile("^(\\d)\\s*x?\\s*(.+)$").matcher(teil);
                    if (ohne.find()) {
                        String w = wertGruppe(ohne.group(1));
                        if (w == null) { fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(": '").append(ohne.group(1)).append(Sprache.t("' is unknown\n", "' kenne ich nicht\n")); continue; }
                        genau.put(w, 0);
                    } else if (zahl.find()) {
                        String rest2 = zahl.group(2).trim();
                        if (rest2.matches("(?:andere\\s+)?(?:verschiedene?|unterschiedliche?|different|verschieden)(?:\\s+.*)?")) { rest = 1; continue; }
                        if (rest2.matches("(?:gleiche?|same|identische?)(?:\\s+.*)?")) { rest = 2; continue; }
                        if (rest2.matches("(?:andere?|beliebige?|egal|other|any|rest).*")) continue;
                        String w = wertGruppe(rest2);
                        int n = Integer.parseInt(zahl.group(1));
                        if (w == null) { fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(": '").append(rest2).append(Sprache.t("' is unknown\n", "' kenne ich nicht\n")); continue; }
                        if (n > 5) { fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(Sprache.t(": more than 5x is not possible\n", ": mehr als 5x geht nicht\n")); continue; }
                        genau.put(w, n);
                    } else if (teil.matches("(?:rest|egal|beliebig|any|other).*")) {
                        // nichts
                    } else {
                        fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(": '").append(teil)
                                .append(Sprache.t("' not understood (example: 4x armor + 1x health)\n",
                                        "' verstehe ich nicht (Beispiel: 4x Rüstung + 1x Leben)\n"));
                    }
                }
                int summe = 0;
                for (int v : genau.values()) summe += v;   // eine Gruppe zaehlt einmal (zusammen N)
                if (summe > 5) fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(Sprache.t(": more than 5 stats together\n", ": zusammen mehr als 5 Werte\n"));
                if (!leer) moegl.add(new Muster(java.util.Map.copyOf(genau), rest));
            }
            if (moegl.isEmpty()) fehler.append(Sprache.t("Line ", "Zeile ")).append(i + 1).append(Sprache.t(": empty\n", ": leer\n"));
            stufen.add(List.copyOf(moegl));
        }
        return List.copyOf(stufen);
    }

    /** "Leben/mag. Ruestung" -> "health|magic armor" (Gruppe, zusammen gezaehlt); ein einzelner Wert wie wertAus. */
    static String wertGruppe(String text) {
        if (!text.contains("/")) return wertAus(text);
        java.util.TreeSet<String> teile = new java.util.TreeSet<>();
        for (String t : text.split("/")) {
            String w = wertAus(t.trim());
            if (w == null) return null;
            teile.add(w);
        }
        return String.join("|", teile);
    }

    /** Ein Wertname (Deutsch, Englisch, Kuerzel, mit Tippfehlern wie "ruestung") -> Name im Spiel, sonst null. */
    static String wertAus(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace(".", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(?:x\\s+)", "");
        if (t.matches("(?:mag|magisch|magische|magischer|magischen|magic|m)\\s*(?:schaden|damage|dmg|s|d)|md|mdmg|magieschaden"))
            return "magic damage";
        if (t.matches("(?:mag|magisch|magische|magischer|magic|m)\\s*(?:ruestung|rustung|armor|armour|r)|mr|marmor|magieruestung"))
            return "magic armor";
        if (t.matches("leben|health|hp|l|gesundheit|lp")) return "health";
        if (t.matches("schaden|damage|dmg|d|phys(?:ischer)? schaden|physical damage|normaler schaden")) return "damage";
        if (t.matches("ruestung|rustung|armor|armour|r|panzerung")) return "armor";
        if (t.matches("crit|krit|kritisch|kritischer treffer|critical|kt|c")) return "crit";
        if (t.matches("dodge|ausweichen|ausweich|ausweichrate|a")) return "dodge";
        // Tippfehler am Handy ("Rüstng", "Lebn", "mag Schadn"): der eindeutig naechste Name, 1-2 Buchstaben Unterschied.
        String n = t.replace(" ", "");
        if (n.length() < 4) return null;
        String[][] namen = {{"magschaden", "magic damage"}, {"magischerschaden", "magic damage"}, {"magicdamage", "magic damage"},
                {"magruestung", "magic armor"}, {"magischeruestung", "magic armor"}, {"magicarmor", "magic armor"},
                {"leben", "health"}, {"health", "health"}, {"schaden", "damage"}, {"damage", "damage"},
                {"ruestung", "armor"}, {"armor", "armor"}, {"crit", "crit"}, {"krit", "crit"},
                {"dodge", "dodge"}, {"ausweichen", "dodge"}};
        int grenze = n.length() <= 5 ? 1 : 2, beste = Integer.MAX_VALUE;
        String treffer = null;
        boolean doppelt = false;
        for (String[] e : namen) {
            int d = abstand(n, e[0]);
            if (d > grenze) continue;
            if (d < beste) { beste = d; treffer = e[1]; doppelt = false; }
            else if (d == beste && !e[1].equals(treffer)) doppelt = true;
        }
        return doppelt ? null : treffer;
    }

    private static int abstand(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
        }
        return d[a.length()][b.length()];
    }

    public static Set<String> namen() { return namen; }
    public static boolean leveln(String regel) { return level.contains(regel); }
    public static Set<String> level() { return level; }
    public static Set<String> behalten() { return behalten; }
    public static String hauptKuerzel() { return haupt; }
    public static String zweitKuerzel() { return zweit; }

    /** Der Wert, nach dem zuerst getauscht wird ("magic damage", "health" ...). */
    public static String haupt() { return art(eigenerTitan() ? aktiv.haupt() : haupt); }

    /** Der zweitwichtigste Wert. */
    public static String zweit() { return art(eigenerTitan() ? aktiv.zweit() : zweit); }

    /** Der Wert, der am wenigsten zaehlt: der "falsche" Schaden (beim Standard normaler Schaden). */
    public static String schlecht() { return (eigenerTitan() ? aktiv.haupt() : haupt).equals("d") ? "magic damage" : "damage"; }

    /** Kuerzel -> Name im Spiel, so wie StigmaMode die Werte liest. */
    public static String art(String kuerzel) {
        return switch (kuerzel) {
            case "md" -> "magic damage";
            case "l" -> "health";
            case "d" -> "damage";
            case "r" -> "armor";
            case "mr" -> "magic armor";
            case "krit" -> "crit";
            case "dodge" -> "dodge";
            default -> kuerzel;
        };
    }

    /** Kuerzel -> deutscher Name fuer Discord. */
    public static String wertText(String kuerzel) {
        // Freunde: auf Englisch die Namen aus dem Spiel (der Ranglisten-Leser versteht beide).
        return switch (kuerzel) {
            case "md" -> Sprache.t("magic damage", "mag. Schaden");
            case "l" -> Sprache.t("health", "Leben");
            case "d" -> Sprache.t("damage", "Schaden");
            case "r" -> Sprache.t("armor", "Rüstung");
            case "mr" -> Sprache.t("magic armor", "mag. Rüstung");
            case "krit" -> "Crit";
            case "dodge" -> "Dodge";
            default -> kuerzel;
        };
    }

    public static String levelText(String r) {
        if (r.equals("anders3")) return Sprache.t("3x another stat: 1 purple ray", "3x anderer Wert: 1 lila Strahl");
        String w = r.substring(0, r.length() - 1);
        return (r.endsWith("4") ? "4x/5x " : "3x ") + wertText(w);
    }

    /**
     * Bis zu welchem Level ein Stigma mit diesen Werten gelevelt wird: 20 (4x/5x eines angehakten Werts), 3 (genau 3x)
     * oder 0 (keine Regel passt). "Gleich gut wie das eingesetzte" und der lila Strahl kommen in StigmaMode dazu.
     */
    public static int levelZiel(List<String> werte) {
        // Autor 23.10.: "Alle Stigmas sind gleich viel wert. 4 gleiche Stats mindestens auf Level 5, 3 gleiche nur auf
        // Level 4 oder niedriger, 5 gleiche auf Level 10 -- so stechen die raus." Gilt mit der Grundregel (gleicheRegel).
        if (gleicheRegel()) {
            // Code-Review 23.10.: "Hochleveln: nie" (nichts angehakt) gilt auch fuer die Grundregel.
            if (level == null || level.isEmpty()) return 0;
            int m = meisteGleiche(werte);
            return m >= 5 ? 10 : m == 4 ? 5 : m == 3 ? 4 : 0;
        }
        boolean drei = false;
        for (String w : WERTE) {
            int n = anzahl(werte, art(w));
            if (n >= 4 && level.contains(w + "4")) return 20;
            if (n == 3 && level.contains(w + "3")) drei = true;
        }
        return drei ? 3 : 0;
    }

    /** Ein lila Strahl (+2 Level): keine Regel passt, aber ein Wert kommt 3x oder oefter vor, und anders3 ist an. */
    public static boolean episch(List<String> werte) {
        if (!level.contains("anders3") || levelZiel(werte) > 0) return false;
        for (String w : WERTE) if (anzahl(werte, art(w)) >= 3) return true;
        return false;
    }

    /** Gelb in der Uebersicht: 3x+ des Haupt- oder Zweitwerts oder eines Werts mit angehakter Level-Regel. */
    public static boolean interessant(List<String> werte) {
        if (anzahl(werte, haupt()) >= 3 || anzahl(werte, zweit()) >= 3) return true;
        for (String w : WERTE) {
            if (anzahl(werte, art(w)) >= 3 && (level.contains(w + "3") || level.contains(w + "4"))) return true;
        }
        return false;
    }

    /**
     * Gewicht eines Nebenwerts beim Vergleich gleich guter Stigmas (doppelt, damit ganze Zahlen reichen). Autor, 20:28:
     * "Leben > mag. Ruestung > Dodge > Ruestung > Crit > Schaden". Der Hauptwert zaehlt schon im Rang (0), der Zweitwert
     * ganz oben, der "falsche" Schaden ganz unten. Beim Standard (mag. Schaden / Leben) genau Autors Reihenfolge.
     */
    public static int gewicht(String art) {
        if (eigenerTitan() && aktiv.gewichte() != null) return aktiv.gewichte().getOrDefault(art, 1);
        if (art.equals(haupt())) return 0;
        if (art.equals(zweit())) return 16;
        if (art.equals(schlecht())) return 1;
        return switch (art) {
            case "health" -> 14;
            case "magic damage" -> 12;
            case "magic armor" -> 10;
            case "dodge" -> 8;
            case "armor" -> 6;
            case "crit" -> 4;
            case "damage" -> 2;
            default -> 0;
        };
    }

    /** Neu setzen und speichern. Leere oder unbekannte Angaben lassen den alten Wert stehen (ausser level/behalten). */
    public static synchronized void setzen(Set<String> neueNamen, Set<String> neuesLevel, String neuerHaupt,
                                           String neuerZweit, Set<String> neuesBehalten) {
        if (neueNamen != null) {
            Set<String> n = gefiltert(neueNamen, NAMEN);
            if (!n.isEmpty()) namen = n;
        }
        if (neuesLevel != null) level = gefiltert(neuesLevel, LEVEL);
        if (neuerHaupt != null && WERTE.contains(neuerHaupt)) haupt = neuerHaupt;
        if (neuerZweit != null && WERTE.contains(neuerZweit)) zweit = neuerZweit;
        if (zweit.equals(haupt)) zweit = haupt.equals("l") ? "md" : "l";
        if (neuesBehalten != null) behalten = gefiltert(neuesBehalten, SETS);
        speichern();
    }

    /** Zurueck auf Autors Regeln. */
    public static synchronized void standard() {
        namen = Set.copyOf(NAMEN);
        level = STANDARD_LEVEL;
        haupt = "md";
        zweit = "l";
        behalten = STANDARD_BEHALTEN;
        speichern();
        ranglisteSetzen("");
    }

    /** Der Stand in ein paar Zeilen fuer Discord. */
    public static String beschreibung() {
        // Freunde: zweisprachig (viele spielen auf Englisch).
        return Sprache.t("Kinds: **", "Sorten: **") + anzeige(namen, NAMEN) + "**\n"
                + Sprache.t("Level up when: **", "Hochleveln bei: **") + (level.isEmpty() ? Sprache.t("never", "nie") : anzeigeLevel()) + "**\n"
                + Sprache.t("Swap by: **", "Tauschen nach: **") + (eigeneZeilen != null
                        ? Sprache.t("own ranking (" + eigeneZeilen.size() + " tiers)", "eigene Rangliste (" + eigeneZeilen.size() + " Stufen)")
                        : wertText(haupt) + Sprache.t(" first, then ", " zuerst, dann ") + wertText(zweit)
                        + (haupt.equals("md") && zweit.equals("l") ? Sprache.t(" (default)", " (Standard)") : "")) + "**\n"
                + Sprache.t("Keep when deleting: **", "Beim Loeschen behalten: **") + (behalten.isEmpty()
                        ? Sprache.t("nothing (everything with level 1-79 is deleted)", "nichts (alles mit Level 1-79 wird geloescht)")
                        : anzeige(behalten, SETS)) + "**\n"
                + Sprache.t("Titans with their own rules: ", "Titanen mit eigenen Regeln: ")
                + "Medusa, Thundersaurus, Torturer, Keeper of Stars"
                + Sprache.t(" -- these settings apply to the Last Guardian and to every other titan name.",
                            " -- diese Einstellungen gelten fuer den Last Guardian und jeden anderen Titan-Namen.");
    }

    public static String gross(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static int anzahl(List<String> werte, String art) {
        int n = 0;
        if (werte != null) for (String w : werte) if (w.equals(art)) n++;
        return n;
    }

    private static String anzeigeLevel() {
        List<String> t = new ArrayList<>();
        for (String r : LEVEL) if (level.contains(r)) t.add(levelText(r));
        return String.join(", ", t);
    }

    private static String anzeige(Set<String> werte, List<String> reihenfolge) {
        if (werte.size() == reihenfolge.size()) return Sprache.t("all", "alle");
        List<String> t = new ArrayList<>();
        for (String w : reihenfolge) if (werte.contains(w)) t.add(gross(w));
        return String.join(", ", t);
    }

    private static Set<String> gefiltert(Set<String> werte, List<String> erlaubt) {
        Set<String> s = new LinkedHashSet<>();
        for (String w : werte) {
            String k = w.trim().toLowerCase(Locale.ROOT);
            if (erlaubt.contains(k)) s.add(k);
        }
        return Set.copyOf(s);
    }

    private static void laden() {
        namen = Set.copyOf(NAMEN);
        level = STANDARD_LEVEL;
        haupt = "md";
        zweit = "l";
        behalten = STANDARD_BEHALTEN;
        if (!Files.exists(DATEI)) return;
        try {
            for (String zeile : Dateien.zeilen(DATEI)) {
                int gl = zeile.indexOf('=');
                if (zeile.startsWith("#") || gl < 0) continue;
                String k = zeile.substring(0, gl).trim();
                String roh = zeile.substring(gl + 1).trim();
                Set<String> v = new LinkedHashSet<>();
                for (String t : roh.split(",")) if (!t.isBlank()) v.add(t.trim());
                switch (k) {
                    case "namen" -> { Set<String> n = gefiltert(v, NAMEN); if (!n.isEmpty()) namen = n; }
                    case "level" -> level = gefiltert(v, LEVEL);
                    case "haupt" -> { if (WERTE.contains(roh)) haupt = roh; }
                    case "zweit" -> { if (WERTE.contains(roh)) zweit = roh; }
                    // Fruehere Fassung (11.10. 02:20): nur drei Ranglisten.
                    case "rangliste" -> {
                        if (roh.equals("leben")) { haupt = "l"; zweit = "md"; }
                        else if (roh.equals("schaden")) { haupt = "d"; zweit = "l"; }
                    }
                    case "behalten" -> behalten = gefiltert(v, SETS);
                    default -> { }
                }
            }
            if (zweit.equals(haupt)) zweit = haupt.equals("l") ? "md" : "l";
        } catch (Exception e) {
            Log.warn("Stigmas", Sprache.t("stigma-einstellungen.txt not readable -- the default rules apply: ",
                    "stigma-einstellungen.txt nicht lesbar -- es gelten die Standard-Regeln: ") + e.getMessage());
        }
        ranglisteLaden();
    }

    private static void ranglisteLaden() {
        eigeneZeilen = null;
        eigeneStufen = null;
        if (!Files.exists(RANG_DATEI)) return;
        try {
            List<String> zeilen = new ArrayList<>();
            for (String z : Dateien.zeilen(RANG_DATEI)) {
                if (!z.isBlank() && !z.trim().startsWith("#")) zeilen.add(z.trim());
            }
            if (zeilen.isEmpty()) return;
            StringBuilder fehler = new StringBuilder();
            List<List<Muster>> stufen = lesen(zeilen, fehler);
            if (fehler.length() > 0) {
                Log.warn("Stigmas", Sprache.t("stigma-rangliste.txt has errors -- the default list applies: ",
                        "stigma-rangliste.txt hat Fehler -- es gilt die Standard-Liste: ") + fehler.toString().replace("\n", "; "));
                return;
            }
            eigeneZeilen = List.copyOf(zeilen);
            eigeneStufen = stufen;
        } catch (Exception e) {
            Log.warn("Stigmas", Sprache.t("stigma-rangliste.txt not readable -- the default list applies: ",
                    "stigma-rangliste.txt nicht lesbar -- es gilt die Standard-Liste: ") + e.getMessage());
        }
    }

    private static void speichern() {
        List<String> z = new ArrayList<>();
        z.add(Sprache.t("# Stigma settings (!stigmas settings). If the file is missing, the default rules apply.",
                "# Stigma-Einstellungen (!stigmas einstellen). Fehlt die Datei, gelten Autors Standard-Regeln."));
        z.add("namen=" + String.join(",", sortiert(namen, NAMEN)));
        z.add("level=" + String.join(",", sortiert(level, LEVEL)));
        z.add("haupt=" + haupt);
        z.add("zweit=" + zweit);
        z.add("behalten=" + String.join(",", sortiert(behalten, SETS)));
        try {
            Files.write(DATEI, z, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Stigmas", Sprache.t("stigma-einstellungen.txt not saved: ", "stigma-einstellungen.txt nicht gespeichert: ") + e.getMessage());
        }
    }

    private static List<String> sortiert(Set<String> werte, List<String> reihenfolge) {
        List<String> l = new ArrayList<>();
        for (String w : reihenfolge) if (werte.contains(w)) l.add(w);
        return l;
    }
}
