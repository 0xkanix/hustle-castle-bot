package de.hustlebot.bot;

import de.hustlebot.core.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import de.hustlebot.core.Sprache;

/**
 * Die Set-Bewertungen fuer die Arena.
 *
 * Autor, 24.09.: Der Bot klickt bei jeder gegnerischen Einheit auf die
 * Brustplatte, oben steht dann der Name des Teils -- etwa "Armor Breaker's
 * Plate Mail". Daran haengt das Set ("Armor Breaker"), und fuer jedes Set gibt
 * es auf Hustlepedia eine Stufe von S bis F. Punkte je Einheit:
 *
 *     F=1  E=2  D=3  C=4  B=5  A=6  S=8
 *
 * Die Liste liegt im Programm (arena-sets.txt). Eine Datei gleichen Namens im
 * Bot-Ordner hat Vorrang -- so kann Autor eine Stufe selbst aendern, ohne
 * dass neu gebaut werden muss (Armor Breaker steht bei ihm auf S).
 *
 * Ein Teil, das zu keinem bekannten Set passt (keine Ancient-Ruestung, oder
 * die Texterkennung hat sich verlesen), zaehlt wie F: 1 Punkt.
 */
final class ArenaSets {

    private static final String DATEI = "arena-sets.txt";
    private static final Map<Character, Integer> PUNKTE = Map.of(
            'F', 1, 'E', 2, 'D', 3, 'C', 4, 'B', 5, 'A', 6, 'S', 8);
    /** Punkte fuer ein unbekanntes Set. */
    static final int UNBEKANNT = 4;   // 08.10.: wie C -- "unbekannt darf nicht wie schwach wirken" (Autor: Punkte zu niedrig)

    /**
     * Ein Set: Name in Vergleichsform, Anzeigename, Stufe ('-' = kein Set, Autor 11.10.) und Lesevarianten
     * (4. Spalte, durch Komma getrennt, z. B. "breaxer,oreake" fuer Armor Breaker -- die Texterkennung verdreht
     * manche Namen zu stark fuer die Ein-Fehler-Suche).
     */
    record Set(String schluessel, String name, char stufe, List<String> aliase, Integer zahl) {
        int punkte() { return zahl != null ? zahl : stufe == '-' ? KEIN_SET : PUNKTE.getOrDefault(stufe, UNBEKANNT); }
    }

    /** Ergebnis fuer ein gelesenes Teil. */
    record Treffer(String set, char stufe, int punkte, boolean nurName) {
        Treffer(String set, char stufe, int punkte) { this(set, stufe, punkte, false); }
    }

    private static volatile List<Set> sets;

    private ArenaSets() {
    }

    /** Welches Set steckt in diesem Titel? Laengster passender Name gewinnt. */
    static Treffer erkennen(String titel) {
        String t = vergleichsform(titel);
        Set beste = null;
        for (Set s : alle()) {
            if (!t.contains(s.schluessel())) continue;
            if (beste == null || s.schluessel().length() > beste.schluessel().length()) beste = s;
        }
        // 11.10.: bekannte Lesevarianten ("vøø•rmor øreaxer Mail", "uetenaer ot Nature•s Outfit") -- laengste gewinnt.
        if (beste == null) {
            int laenge = 0;
            for (Set s : alle()) {
                for (String a : s.aliase()) {
                    if (a.length() > laenge && t.contains(a)) {
                        laenge = a.length();
                        beste = s;
                    }
                }
            }
        }
        // Ein verlesener Buchstabe ("Armor Breaxer's Plate Mail", 01.10.) oder der erste
        // abgeschnitten (":orest Guardian"): aehnlichster Abschnitt mit hoechstens einem Fehler
        // je acht Zeichen.
        if (beste == null && t.length() >= 4) {
            double besteAehnlichkeit = 0;
            for (Set s : alle()) {
                String k = s.schluessel();
                if (k.length() < 5) continue;
                double a = besterAbschnitt(t, k);
                // Arena-Review 15.10. (aus dem Hauptbot): Bei Namen unter 8 Zeichen war kein einziger Lesefehler erlaubt
                // ("Cultlst's Robe" wurde "kein Set"). Ab 7 Zeichen darf jetzt genau ein Zeichen falsch sein.
                double schwelle = k.length() >= 8 ? 1.0 - 1.0 / 8 : k.length() == 7 ? 1.0 - 1.0 / 7 - 1e-9 : 1.0;
                if (a >= schwelle && a > besteAehnlichkeit) {
                    besteAehnlichkeit = a;
                    beste = s;
                }
            }
        }
        if (beste == null) {
            // Autor, 08.10.: "Diese Sets existieren nicht -- das sind einfach Namen von Ausruestungen.
            // Leute, die nur so eine Ruestung benutzen, haben kein Set und sind dafuer ziemlich
            // schlecht: minus 4 Punkte." Nur, wenn der Name sauber gelesen ist (Ruestungswort plus
            // mindestens ein Wort davor) -- abgeschnittenes ("Plate Mail") oder Fremdes ("Equipped
            // by character:") bleibt unlesbar und zaehlt neutral.
            // nurName: nur am Namen erkannt, nicht an der Liste -- zeigt der Set-Balken Teile, ist es doch ein (fehlendes)
            // Set (Pruefung 29.10.: "Thicket Lord's Gear" einmal -4, einmal "Set (unbekannt) 6/6").
            if (ausruestungOhneSet(titel)) return new Treffer("kein Set", '-', KEIN_SET, true);
            unbekanntMerken(titel);
            return new Treffer("?", '?', UNBEKANNT);
        }
        if (beste.stufe() == '-') return new Treffer("kein Set", '-', KEIN_SET);   // Autor 11.10.: ausdruecklich kein Set
        return new Treffer(beste.name(), beste.stufe(), beste.punkte());
    }

    /**
     * Punkte fuer eine Einheit ohne Set (nur Ausruestung). Autor, 08.10.: -4; 11.10.: +1; 22.10.: "setlose Ruestung -2".
     */
    static final int KEIN_SET = -2;

    /** Was das farbige Banner unter dem Namen der Ruestung sagt (Autor 11.10.). */
    enum Banner { SET, KEIN_SET, UNKLAR }

    /**
     * Autor, 11.10.: "Immer wenn Immortal armor oder Supreme armor da steht, ist das ein wirkliches Set. Divine armor,
     * Mythical armor und Legendary armor sind kein Set."
     */
    static Banner banner(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("immortal") || t.contains("supreme")) return Banner.SET;
        if (t.contains("divine") || t.contains("mythic") || t.contains("legenda") || NIEDRIGE_STUFE.matcher(t).find()) return Banner.KEIN_SET;
        return Banner.UNKLAR;
    }

    /**
     * Pruefung 3.138 (01.11.): Arbeiter-Fenster ("Watchmaker suit", Banner "Rare outfit", Waffe "Rare item") zaehlten +4 "neutral" -- ihr Banner stand
     * nicht in der Regel und sitzt dort weiter unten. Niedrige Stufen tragen nie ein Set: Rare, Epic, Uncommon, Common, gefolgt von der Art des Teils
     * ("outfit", "item", "armor", "weapon", "ring" ...); das Wort davor darf kein Buchstabe sein ("prepare armor").
     */
    private static final java.util.regex.Pattern NIEDRIGE_STUFE = java.util.regex.Pattern.compile(
            "(?<![a-z])(rare|epic|uncommon|common)\\s*(armou?r|outfit|item|weapon|ring|amulet|totem|equipment)");

    /** Steht im Text irgendeine bekannte Banner-Stufe? Dann ist das Banner gelesen (sonst lohnt die zweite Suche weiter unten). */
    static boolean bannerGelesen(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return setStufe(t) || banner(t) == Banner.KEIN_SET;
    }

    /** Tragen Gegenstaende mit diesem Banner Sets? Nur Ancient, Immortal und Supreme (Pruefung 01.11., an 82 Set-Fotos gemessen). */
    static boolean setStufe(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("ancient") || t.contains("immortal") || t.contains("supreme");
    }

    private static final java.util.regex.Pattern RUESTUNGSWORT = java.util.regex.Pattern.compile(
            "(?i)\\b(outfit|gear|plate\\s*mail|mail|armou?r|robe|equipment|attire|garb)\\b");

    /** Ein vollstaendig gelesener Ausruestungsname, der zu keinem Set gehoert? */
    static boolean ausruestungOhneSet(String titel) {
        if (titel == null) return false;
        String t = titel.strip();
        if (t.toLowerCase().startsWith("equipped")) return false;
        var m = RUESTUNGSWORT.matcher(t);
        if (!m.find()) return false;
        String davor = t.substring(0, m.start()).replaceAll("[^\\p{L}\\s']", "").strip();
        // Mindestens ein ganzes Wort vor dem Ruestungswort, sauber geschrieben (Grossbuchstabe vorn).
        return davor.length() >= 4 && Character.isUpperCase(davor.charAt(0));
    }

    /** Groesste Aehnlichkeit von {@code k} mit einem gleich langen (+-1) Abschnitt von {@code t}. */
    private static double besterAbschnitt(String t, String k) {
        double best = 0;
        for (int laenge = k.length() - 1; laenge <= k.length() + 1; laenge++) {
            if (laenge <= 0 || laenge > t.length()) continue;
            for (int i = 0; i + laenge <= t.length(); i++) {
                String teil = t.substring(i, i + laenge);
                int d = abstand(teil, k);
                best = Math.max(best, 1.0 - d / (double) Math.max(teil.length(), k.length()));
            }
        }
        return best;
    }

    private static int abstand(String a, String b) {
        int[] vor = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vor[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int k = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vor[j] + 1), vor[j - 1] + k);
            }
            int[] tmp = vor; vor = jetzt; jetzt = tmp;
        }
        return vor[b.length()];
    }

    /**
     * Set-Namen, die in der Liste fehlen, fuer Autor sammeln (01.10.: "Snake Charmer",
     * "Jade Keeper", "Crystal Gatherer" ... zaehlten nur 1 Punkt). Eine Zeile je Name, mit
     * Anzahl -- Autor traegt die Stufe dann in arena-sets.txt im Bot-Ordner ein.
     */
    static final Path UNBEKANNT_DATEI = Path.of("arena-sets-unbekannt.txt");

    static synchronized void unbekanntMerken(String titel) {
        String name = titel == null ? "" : titel.strip();
        if (name.length() < 5) return;
        try {
            java.util.Map<String, Integer> liste = new java.util.TreeMap<>();
            if (Files.exists(UNBEKANNT_DATEI)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(UNBEKANNT_DATEI)) {
                    if (z.startsWith("#") || !z.contains("|")) continue;
                    String[] t = z.split("\\|");
                    try { liste.put(t[0].strip(), Integer.parseInt(t[1].strip())); } catch (Exception ignored) { }
                }
            }
            liste.merge(name, 1, Integer::sum);
            StringBuilder sb = new StringBuilder("# Set-Namen, die der Bot gelesen hat, die aber in der Liste fehlen.\n"
                    + "# Name | wie oft gesehen. Stufe eintragen: in arena-sets.txt (Bot-Ordner) als \"Name | S\".\n");
            liste.forEach((n, c) -> sb.append(n).append(" | ").append(c).append('\n'));
            Files.writeString(UNBEKANNT_DATEI, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Arena", "arena-sets-unbekannt.txt nicht geschrieben: " + e.getMessage());
        }
    }

    static List<Set> alle() {
        List<Set> s = sets;
        if (s == null) {
            s = laden();
            sets = s;
        }
        return s;
    }

    private static List<Set> laden() {
        List<Set> liste = new ArrayList<>();
        try {
            Path eigene = Path.of(DATEI);
            List<String> zeilen;
            if (Files.exists(eigene)) {
                zeilen = de.hustlebot.core.Dateien.zeilen(eigene);
            } else {
                zeilen = new ArrayList<>();
                try (InputStream in = ArenaSets.class.getResourceAsStream("/" + DATEI)) {
                    if (in != null) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(in, StandardCharsets.UTF_8));
                        for (String z; (z = r.readLine()) != null; ) zeilen.add(z);
                    }
                }
            }
            for (String roh : zeilen) {
                String z = roh.contains("#") ? roh.substring(0, roh.indexOf('#')) : roh;
                String[] t = z.split("\\|");
                if (t.length < 2 || t[0].isBlank() || t[1].isBlank()) continue;
                // Autor 22.10.: statt der Stufe darf eine Zahl stehen (Punkte je Einheit, auch negativ) -- Stufe '#'.
                String wert = t[1].trim();
                Integer zahl = null;
                char stufe;
                if (wert.matches("[+-]?\\d{1,2}")) {
                    zahl = Integer.parseInt(wert.replace("+", ""));
                    stufe = '#';
                } else {
                    stufe = Character.toUpperCase(wert.charAt(0));
                    if (!PUNKTE.containsKey(stufe) && stufe != '-') continue;
                }
                // Arena-Review 15.10. (aus dem Hauptbot): Ein Name ohne lateinische Buchstaben ergab den Schluessel "" --
                // und contains("") trifft jeden Titel. Solche Zeilen werden uebersprungen.
                if (vergleichsform(t[0]).strip().length() < 3) {
                    Log.warn("Arena", "arena-sets.txt: \"" + t[0].trim() + "\" uebersprungen (zu kurz/keine lateinischen Buchstaben).");
                    continue;
                }
                List<String> aliase = new ArrayList<>();
                if (t.length >= 4) {
                    for (String a : t[3].split(",")) {
                        String v = vergleichsform(a);
                        if (v.length() >= 4) aliase.add(v);
                    }
                }
                liste.add(new Set(vergleichsform(t[0]), t[0].trim(), stufe, List.copyOf(aliase), zahl));
            }
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t("Set list not readable: " + e.getMessage(), "Set-Liste nicht lesbar: " + e.getMessage()));
        }
        Log.debug("Arena", liste.size() + " Sets mit Bewertung geladen.");
        return liste;
    }

    /** Klein, ohne Apostrophe und Sonderzeichen, einfache Leerzeichen. */
    static String vergleichsform(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace('ø', 'o')   // 11.10.: "vøø•rmor øreaxer" -- die Texterkennung liest o oft als ø
                .replaceAll("['’`´]s\\b", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
