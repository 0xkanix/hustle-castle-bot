package de.hustlebot.discord;

import de.hustlebot.core.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import de.hustlebot.core.Sprache;

/**
 * Eigene Kurzbefehle: "!maus" statt "!start cathedral 25 c".
 *
 * Autor, 23.09.: "Kannst du einen shortcut Befehl hinzufuegen wo man
 * bestimmte commands abkuerzen kann mit einem auswaehlbaren oder selbst
 * erstellten Wort ... immer wenn ich !maus oder !Maus oder !MAUS ... in den
 * Chat reinschreibe soll automatisch der dazugehoerige Befehl ausgefuehrt
 * werden ... Anzahl der Spielmodus runs kann ich beispielsweise mit
 * !maus anzahl festlegen." Auch eine einzelne Zahl soll gehen ("!1").
 *
 * Gespeichert wird in kuerzel.txt neben config.properties, eine Zeile je
 * Kuerzel: "maus = cathedral|c". Gross/klein spielt keine Rolle -- alles wird
 * klein abgelegt und klein verglichen.
 */
public final class Kuerzel {

    /** Was sich abkuerzen laesst. */
    public enum Ziel {
        KATHEDRALE_B("cathedral", "b", "Kathedrale b – alle Wegpunkte", "Cathedral b – every waypoint"),
        KATHEDRALE_C("cathedral", "c", "Kathedrale c – Turbo", "Cathedral c – turbo"),
        VERLIES("dungeon", "", "Verlies – zufällige Mitspieler", "Dungeon – random partners"),
        VERLIES_FREUND("dungeon", "freund", "Verlies – mit einem Freund", "Dungeon – with a friend"),
        CLANKRIEG("clanwar", "", "Clan-Krieg jetzt aufstellen", "Deploy clan war now");

        public final String modus;
        public final String zusatz;
        public final String de;
        public final String en;

        Ziel(String modus, String zusatz, String de, String en) {
            this.modus = modus;
            this.zusatz = zusatz;
            this.de = de;
            this.en = en;
        }
    }

    /** Ein gespeichertes Kuerzel: Ziel plus (beim Verlies mit Freund) der Name. */
    public record Eintrag(Ziel ziel, String freund) { }

    private static Path DATEI = Path.of("kuerzel.txt");

    /** Wo die Datei liegt -- der Freunde-Bot legt sie in seinen Programmordner. */
    public static void setDatei(Path datei) { if (datei != null) DATEI = datei; }

    private final Map<String, Eintrag> kuerzel = new TreeMap<>();

    /** Befehle, die schon vergeben sind -- ein Kuerzel darf sie nicht ueberdecken. */
    private final Set<String> belegt;

    public Kuerzel(Set<String> belegteBefehle) {
        this.belegt = belegteBefehle;
        laden();
    }

    // ------------------------------------------------------------------
    //  Auswahl
    // ------------------------------------------------------------------

    /**
     * Versteht die Wahl im Chat: eine Nummer ("3") oder Worte wie
     * "cathedral c", "cath c", "kathedrale turbo", "dungeon", "verlies lisa".
     *
     * @return das Ziel, oder null wenn nichts passt
     */
    public static Ziel zielAus(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        String nr = t.split("\\s+")[0];
        if (nr.matches("[1-9]") && Integer.parseInt(nr) <= Ziel.values().length) {
            return Ziel.values()[Integer.parseInt(nr) - 1];
        }

        String[] w = t.split("\\s+");
        String erstes = w[0];
        boolean kathedrale = erstes.startsWith("cath") || erstes.startsWith("kath")
                || erstes.equals("dc") || erstes.equals("kat");
        boolean verlies = erstes.startsWith("dung") || erstes.startsWith("verl") || erstes.equals("dg");
        boolean krieg = erstes.startsWith("clan") || erstes.startsWith("krieg") || erstes.startsWith("war");

        if (kathedrale) {
            String weg = w.length > 1 ? w[1] : "c";
            if (weg.equals("b") || weg.startsWith("alle") || weg.startsWith("full")) return Ziel.KATHEDRALE_B;
            if (weg.equals("c") || weg.startsWith("turbo") || weg.startsWith("fast")) return Ziel.KATHEDRALE_C;
            return Ziel.KATHEDRALE_C;   // Weg a gibt es seit 26.09. nicht mehr
        }
        if (verlies) return w.length > 1 ? Ziel.VERLIES_FREUND : Ziel.VERLIES;
        if (krieg) return Ziel.CLANKRIEG;
        return null;
    }

    /** Mindestens zehn kurze, gut merkbare Vorschlaege je Ziel. */
    public List<String> vorschlaege(Ziel ziel) {
        List<String> roh = switch (ziel) {
            case KATHEDRALE_B -> List.of("kb", "alle", "voll", "k2", "full", "b", "2", "lang", "tour", "rund", "kbb", "kathb");
            case KATHEDRALE_C -> List.of("kc", "turbo", "fast", "k3", "blitz", "c", "3", "rush", "tt", "flink", "maus", "kathc");
            case VERLIES -> List.of("v", "vl", "dung", "dg", "d1", "4", "keller", "gruft", "loch", "dd", "vv", "tief");
            case VERLIES_FREUND -> List.of("vf", "duo", "team", "zu2", "d2", "5", "paar", "bff", "fr", "mit", "df", "zwei");
            case CLANKRIEG -> List.of("cw", "krieg", "war", "ck", "6", "clan", "turm", "kw", "setzen", "wk", "cwar", "front");
        };
        List<String> frei = new ArrayList<>();
        for (String s : roh) {
            if (istFrei(s) == null) frei.add(s);
        }
        return frei;
    }

    // ------------------------------------------------------------------
    //  Anlegen, loeschen, nachschlagen
    // ------------------------------------------------------------------

    /**
     * Prueft ein Wunschwort. Null heisst: frei. Sonst der Grund, warum nicht.
     */
    public String istFrei(String wort) {
        String w = normal(wort);
        if (w.isEmpty()) return de.hustlebot.core.Sprache.t("empty", "leer");
        if (!w.matches("[a-z0-9äöüß_-]{1,20}")) return de.hustlebot.core.Sprache.t("only letters and numbers, at most 20", "nur Buchstaben und Zahlen, höchstens 20");
        if (belegt.contains("!" + w)) return de.hustlebot.core.Sprache.t("is already a bot command", "ist schon ein Befehl des Bots");
        if (w.equals("shortcut") || w.equals("shortcuts") || w.equals("kuerzel")) return de.hustlebot.core.Sprache.t("is the command itself", "ist der Befehl selbst");
        return null;
    }

    /** Legt ein Kuerzel an oder ueberschreibt ein eigenes. */
    public synchronized void setzen(String wort, Ziel ziel, String freund) {
        kuerzel.put(normal(wort), new Eintrag(ziel, freund == null ? "" : freund.trim()));
        speichern();
    }

    public synchronized boolean loeschen(String wort) {
        boolean weg = kuerzel.remove(normal(wort)) != null;
        if (weg) speichern();
        return weg;
    }

    public synchronized Map<String, Eintrag> alle() {
        return new TreeMap<>(kuerzel);
    }

    /**
     * Macht aus "!maus 25" den echten Befehl, z.B. "!start cathedral 25 c".
     *
     * @param verb  das erste Wort, klein, mit Ausrufezeichen
     * @param rest  was danach kam (die Anzahl, oder leer)
     * @return der Befehl, oder null wenn es kein Kuerzel ist
     */
    public synchronized String befehlFuer(String verb, String rest) {
        if (verb == null || !verb.startsWith("!")) return null;
        Eintrag e = kuerzel.get(normal(verb.substring(1)));
        if (e == null) return null;

        String anzahl = "";
        if (rest != null) {
            for (String w : rest.trim().split("\\s+")) {
                if (w.matches("\\d{1,3}")) { anzahl = w; break; }
            }
        }
        StringBuilder b = new StringBuilder("!start ").append(e.ziel().modus);
        if (!anzahl.isEmpty() && e.ziel() != Ziel.CLANKRIEG) b.append(' ').append(anzahl);
        switch (e.ziel()) {
            case KATHEDRALE_B -> b.append(" b");
            case KATHEDRALE_C -> b.append(" c");
            case VERLIES_FREUND -> { if (!e.freund().isEmpty()) b.append(' ').append(e.freund()); }
            default -> { }
        }
        return b.toString();
    }

    /** Wie ein Kuerzel in Listen erscheint. */
    public static String beschreibung(Eintrag e, boolean deutsch) {
        String s = deutsch ? e.ziel().de : e.ziel().en;
        if (e.ziel() == Ziel.VERLIES_FREUND && !e.freund().isEmpty()) {
            s += (deutsch ? " (" : " (") + e.freund() + ")";
        }
        return s;
    }

    // ------------------------------------------------------------------
    //  Datei
    // ------------------------------------------------------------------

    private static String normal(String wort) {
        String w = wort == null ? "" : wort.trim().toLowerCase(Locale.ROOT);
        return w.startsWith("!") ? w.substring(1).trim() : w;
    }

    private void laden() {
        if (!Files.exists(DATEI)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(DATEI)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            for (String k : p.stringPropertyNames()) {
                String[] teile = p.getProperty(k, "").split("\\|", 2);
                try {
                    String name = teile[0].trim();
                    if (name.equals("KATHEDRALE_A")) name = "KATHEDRALE_C";   // Weg a entfallen, 26.09.
                    Ziel z = Ziel.valueOf(name);
                    kuerzel.put(normal(k), new Eintrag(z, teile.length > 1 ? teile[1].trim() : ""));
                } catch (IllegalArgumentException unbekannt) {
                    // alte oder kaputte Zeile -- ueberspringen
                }
            }
        } catch (Exception e) {
            Log.warn("Discord", Sprache.t("kuerzel.txt could not be read: " + e.getMessage(),
                    "kuerzel.txt liess sich nicht lesen: " + e.getMessage()));
        }
    }

    private void speichern() {
        Properties p = new Properties();
        for (var e : kuerzel.entrySet()) {
            p.setProperty(e.getKey(), e.getValue().ziel().name() + "|" + e.getValue().freund());
        }
        try (OutputStream out = Files.newOutputStream(DATEI)) {
            p.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                    " Eigene Kurzbefehle (!shortcut). Wort = Ziel|Freund");
        } catch (Exception e) {
            Log.warn("Discord", Sprache.t("kuerzel.txt could not be written: " + e.getMessage(),
                    "kuerzel.txt liess sich nicht schreiben: " + e.getMessage()));
        }
    }
}
