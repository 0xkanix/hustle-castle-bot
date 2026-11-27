package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Titanen-Elixier im Clankrieg umtauschen (Autor 21.11.).
 *
 * Verlangt der Titanen-Turm im Clankrieg EIN Element ("You can only deploy earth titans"), wird VOR dem Setzen das
 * Elixier umgetauscht: im Titanen-Turm der Burg, Bildschirm "Titan Enhancement", bei allen Titanen des normalen Teams
 * "Reset all enhancements" (gibt das ganze Elixier kostenlos zurueck), dann die besten Titanen dieses Elements bei
 * jedem Wert auf MAX. Danach normales Team + Titanen-Team setzen (Helden-Preset wie bisher), Helden-Preset zurueck,
 * dann wieder zurueck tauschen: die Element-Titanen resetten, das normale Team nach seiner Soll-Verteilung ausmaxen.
 * Verlangt der Turm KEIN einzelnes Element, bleibt alles wie es ist.
 *
 * Diese Klasse ist die Plan-Logik OHNE Bildschirm. Der Klickweg zum Enhancement-Fenster ist noch nicht bekannt
 * (Fotoserie fehlt) -- die Bildschirm-Schritte unten sind leer und melden das nur. Alles haengt am Schalter
 * titan-elixier.txt ("an"), Standard aus; bei Schalter aus wird keine Bildschirm-Methode aufgerufen.
 */
public final class TitanElixier {

    private TitanElixier() {
    }

    /** Die Elemente, die ein Titanen-Turm verlangen kann. */
    public enum Element {
        ERDE("earth", "Erde"), WASSER("water", "Wasser"), FEUER("fire", "Feuer"), LICHT("light", "Licht"), DUNKEL("dark", "Dunkel");

        final String englisch;
        final String deutsch;

        Element(String englisch, String deutsch) {
            this.englisch = englisch;
            this.deutsch = deutsch;
        }

        /** Fuer Logzeilen in der eingestellten Sprache. */
        public String anzeige() {
            return Sprache.t(englisch, deutsch);
        }

        /** "erde", "earth", "Erd", "Wasser" ... -> Element, sonst null. */
        static Element ausSchluessel(String s) {
            if (s == null) return null;
            String k = s.strip().toLowerCase(Locale.ROOT);
            for (Element e : values()) {
                if (k.equals(e.englisch) || k.equals(e.deutsch.toLowerCase(Locale.ROOT))) return e;
            }
            if (k.equals("erd")) return ERDE;
            return null;
        }
    }

    // ---- Schalter + Datei ------------------------------------------------------------------------------------------

    /** Schalter im Bot-Ordner: "an" = Umtausch erlaubt. Fehlt die Datei oder steht etwas anderes drin: aus. */
    static final Path SCHALTER = Path.of("titan-elixier.txt");
    /** Bestenliste je Element + Soll-Verteilung des normalen Teams. Fehlt sie, gilt die VORLAGE. */
    static final Path KONFIG = Path.of("titanen-elixier.txt");

    /**
     * Ob die Bildschirm-Schritte (Weg zum Enhancement-Fenster, Reset, MAX) gebaut sind. Erst nach Autors Fotoserie auf
     * true stellen -- bis dahin wird selbst bei Schalter "an" nichts umgetauscht.
     */
    static final boolean KLICKWEG_GEBAUT = false;

    static boolean schalterAn() {
        try {
            return Files.exists(SCHALTER) && schalterText(Files.readString(SCHALTER, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Inhalt der Schalter-Datei: nur "an" (auch "an # Kommentar", gross/klein egal) schaltet ein. */
    static boolean schalterText(String inhalt) {
        if (inhalt == null) return false;
        for (String z : inhalt.split("\\R")) {
            String s = ohneKommentar(z).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            return s.equals("an") || s.startsWith("an ");
        }
        return false;
    }

    /** Schalter an UND Klickweg gebaut -- nur dann darf der Clankrieg umtauschen (und Tuerme mit Element-Pflicht waehlen). */
    static boolean umtauschMoeglich() {
        return KLICKWEG_GEBAUT && schalterAn();
    }

    /** Autors Liste vom 21.11. Zeilen "schluessel: wert", "#" = Kommentar. */
    static final String VORLAGE = """
            # Titanen-Elixier im Clankrieg (Autor 21.11.). Zeilen "schluessel: wert", alles hinter # ist Kommentar.
            #
            # Bestenliste je Element: diese Titanen bekommen VOR dem Setzen das Elixier, bei jedem Wert MAX.
            # Reihenfolge = Wichtigkeit (geht das Elixier aus, sind die vorderen schon voll).
            erde: Medusa, Chief, Heavyweight, Moving Castle, Bridge Guardian
            wasser:
            feuer:
            licht:
            dunkel:
            #
            # Normales Titanen-Team mit Soll-Verteilung -- bekommt das Elixier NACH dem Setzen zurueck.
            #   normal: <Titan> = <Werte>     Werte: "alles", oder Namen mit Komma ("health, damage"),
            #                                 "alles ohne crit, dodge", danach "; rest crit" = Crit bekommt den Rest.
            normal: Thundersaurus = health, damage
            normal: Medusa = health, damage
            normal: Torturer = health, damage
            normal: Keeper of Stars = alles
            normal: Last Guardian = alles ohne crit, dodge; rest crit
            """;

    // ---- Soll-Verteilung -------------------------------------------------------------------------------------------

    /**
     * Wie ein Titan ausgemaxt wird. "alles" = jeder Wert MAX (ausser denen in "ohne"); sonst nur die genannten Werte.
     * "rest" bekommt am Ende, was an Elixier uebrig ist. Wertnamen klein, Vergleich per "enthaelt" -- "damage" passt so
     * auch zu "Magic damage" (Autor: "(Magic) Damage").
     */
    record Verteilung(boolean alles, Set<String> max, Set<String> ohne, String rest) {
        static final Verteilung ALLES = new Verteilung(true, Set.of(), Set.of(), null);

        /** Bekommt dieser Wert (Name wie auf dem Bildschirm) MAX? */
        boolean maxen(String wert) {
            String w = wert == null ? "" : wert.toLowerCase(Locale.ROOT);
            if (w.isBlank()) return false;
            if (rest != null && w.contains(rest)) return false;
            for (String o : ohne) if (w.contains(o)) return false;
            if (alles) return true;
            for (String m : max) if (w.contains(m)) return true;
            return false;
        }

        /** Bekommt dieser Wert am Ende den Rest? */
        boolean bekommtRest(String wert) {
            return rest != null && wert != null && wert.toLowerCase(Locale.ROOT).contains(rest);
        }

        /** "health, damage" / "alles" / "alles ohne crit, dodge; rest crit" -> Verteilung, unlesbar -> null. */
        static Verteilung aus(String text) {
            if (text == null) return null;
            String t = text.strip().toLowerCase(Locale.ROOT);
            String rest = null;
            int semi = t.indexOf(';');
            if (semi >= 0) {
                String r = t.substring(semi + 1).strip();
                t = t.substring(0, semi).strip();
                if (r.startsWith("rest")) r = r.substring(4).strip();
                if (!r.isEmpty()) rest = r;
            }
            if (t.isEmpty()) return null;
            boolean alles = t.startsWith("alles") || t.startsWith("all");
            Set<String> max = new LinkedHashSet<>();
            Set<String> ohne = new LinkedHashSet<>();
            if (alles) {
                int o = t.indexOf("ohne");
                if (o < 0) o = t.indexOf("except");
                if (o >= 0) ohne.addAll(liste(t.substring(o + (t.startsWith("ohne", o) ? 4 : 6))));
            } else {
                max.addAll(liste(t));
            }
            if (!alles && max.isEmpty()) return null;
            return new Verteilung(alles, Set.copyOf(max), Set.copyOf(ohne), rest);
        }

        private static List<String> liste(String s) {
            List<String> l = new ArrayList<>();
            for (String p : s.split("[,+/]")) {
                String q = p.strip();
                if (!q.isEmpty()) l.add(q);
            }
            return l;
        }
    }

    /** Ein Titan mit seiner Verteilung (Reihenfolge = Wichtigkeit). */
    record Ziel(String titan, Verteilung verteilung) {
    }

    // ---- Konfiguration ---------------------------------------------------------------------------------------------

    /** Inhalt von titanen-elixier.txt: Bestenliste je Element + normales Team. */
    record Konfig(Map<Element, List<String>> bestenliste, List<Ziel> normal) {

        /** Liest den Text der Datei. Unbekannte oder kaputte Zeilen werden uebersprungen (und gemeldet). */
        static Konfig aus(String text) {
            Map<Element, List<String>> beste = new EnumMap<>(Element.class);
            Map<String, Ziel> normal = new LinkedHashMap<>();
            if (text != null) {
                for (String roh : text.split("\\R")) {
                    String z = ohneKommentar(roh);
                    if (z.isEmpty()) continue;
                    int dp = z.indexOf(':');
                    if (dp <= 0) {
                        Log.debug("Titan-Elixier", "Zeile ohne \":\" uebersprungen: " + z);
                        continue;
                    }
                    String schluessel = z.substring(0, dp).strip().toLowerCase(Locale.ROOT);
                    String wert = z.substring(dp + 1).strip();
                    if (schluessel.equals("normal")) {
                        int gl = wert.indexOf('=');
                        String titan = (gl < 0 ? wert : wert.substring(0, gl)).strip();
                        Verteilung v = gl < 0 ? Verteilung.ALLES : Verteilung.aus(wert.substring(gl + 1));
                        if (titan.isEmpty() || v == null) {
                            Log.debug("Titan-Elixier", "Zeile \"normal\" nicht lesbar: " + z);
                            continue;
                        }
                        normal.put(schluesselName(titan), new Ziel(titan, v));
                        continue;
                    }
                    Element e = Element.ausSchluessel(schluessel);
                    if (e == null) {
                        Log.debug("Titan-Elixier", "Unbekannter Schluessel uebersprungen: " + z);
                        continue;
                    }
                    List<String> namen = new ArrayList<>();
                    for (String n : wert.split(",")) {
                        String s = n.strip();
                        if (!s.isEmpty() && namen.stream().noneMatch(a -> schluesselName(a).equals(schluesselName(s)))) namen.add(s);
                    }
                    beste.put(e, List.copyOf(namen));
                }
            }
            return new Konfig(beste, List.copyOf(normal.values()));
        }

        /** Die Datei im Bot-Ordner, sonst die Vorlage. */
        static Konfig laden() {
            try {
                if (Files.exists(KONFIG)) return aus(Files.readString(KONFIG, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.warn("Titan-Elixier", Sprache.t("titanen-elixier.txt could not be read (" + e.getMessage() + ") -- using the template.",
                        "titanen-elixier.txt nicht lesbar (" + e.getMessage() + ") -- nehme die Vorlage."));
            }
            return aus(VORLAGE);
        }

        List<String> beste(Element e) {
            return e == null ? List.of() : bestenliste.getOrDefault(e, List.of());
        }
    }

    /** Legt die Vorlage an, wenn titanen-elixier.txt fehlt (nur bei Schalter an aufrufen). */
    static void vorlageAnlegenWennFehlt() {
        try {
            if (!Files.exists(KONFIG)) Files.writeString(KONFIG, VORLAGE, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Titan-Elixier", "Vorlage nicht angelegt: " + e.getMessage());
        }
    }

    // ---- Plan ------------------------------------------------------------------------------------------------------

    /**
     * Was umgetauscht wird. hin = vor dem Setzen (normales Team resetten, Element-Titanen ausmaxen), zurueck = nach dem
     * Setzen (Element-Titanen resetten, normales Team nach Soll-Verteilung ausmaxen).
     */
    record Plan(Element element, List<String> hinReset, List<Ziel> hinMax, List<String> zurueckReset, List<Ziel> zurueckMax) {
    }

    /**
     * Der Plan fuer ein verlangtes Element, oder null = nichts umtauschen (kein Element verlangt, keine Bestenliste fuer
     * das Element, oder kein normales Team eingetragen).
     */
    static Plan plan(Konfig k, Element verlangt) {
        if (k == null || verlangt == null) return null;
        List<String> beste = k.beste(verlangt);
        if (beste.isEmpty() || k.normal().isEmpty()) return null;
        List<String> hinReset = new ArrayList<>();
        for (Ziel z : k.normal()) hinReset.add(z.titan());
        List<Ziel> hinMax = new ArrayList<>();
        for (String t : beste) hinMax.add(new Ziel(t, Verteilung.ALLES));
        return new Plan(verlangt, List.copyOf(hinReset), List.copyOf(hinMax), List.copyOf(beste), k.normal());
    }

    // ---- Ablauf-Zustaende ------------------------------------------------------------------------------------------

    /** Wo der Umtausch steht. Wird gespeichert, damit nach einem Neustart das Zuruecktauschen nicht vergessen wird. */
    enum Zustand {
        /** Nichts umgetauscht -- das normale Team hat sein Elixier. */
        RUHE,
        /** Umtausch vor dem Setzen laeuft (normales Team wird resettet, Element-Titanen ausgemaxt). */
        HIN,
        /** Element-Titanen haben das Elixier -- jetzt im Clankrieg setzen. */
        SETZEN,
        /** Gesetzt (oder Setzen aufgegeben) -- Elixier zurueck aufs normale Team. */
        ZURUECK
    }

    /**
     * Der Ablauf: RUHE -> (Element verlangt) HIN -> SETZEN -> ZURUECK -> RUHE. Wichtig: ist einmal etwas umgetauscht
     * (HIN begonnen), MUSS am Ende ZURUECK kommen -- auch wenn das Setzen scheitert oder der Umtausch halb abbricht.
     */
    static final class Ablauf {
        private Zustand zustand = Zustand.RUHE;
        private Element element;

        Zustand zustand() {
            return zustand;
        }

        Element element() {
            return element;
        }

        /** Turm verlangt ein Element: mit Plan -> HIN, ohne Plan (kein Element / keine Liste) bleibt alles wie es ist. */
        boolean starten(Plan p) {
            if (zustand != Zustand.RUHE || p == null) return false;
            zustand = Zustand.HIN;
            element = p.element();
            return true;
        }

        /** Umtausch hin fertig: jetzt setzen. */
        void hinFertig() {
            if (zustand == Zustand.HIN) zustand = Zustand.SETZEN;
        }

        /** Umtausch hin abgebrochen: trotzdem zuruecktauschen, damit das normale Team sein Elixier wieder bekommt. */
        void hinAbgebrochen() {
            if (zustand == Zustand.HIN) zustand = Zustand.ZURUECK;
        }

        /** Setzen fertig (oder aufgegeben): zuruecktauschen. */
        void gesetzt() {
            if (zustand == Zustand.SETZEN) zustand = Zustand.ZURUECK;
        }

        /** Zuruecktauschen fertig: alles wieder wie vorher. */
        void zurueckFertig() {
            if (zustand == Zustand.ZURUECK) {
                zustand = Zustand.RUHE;
                element = null;
            }
        }

        /** Muss vor allem anderen noch zurueckgetauscht werden (z. B. nach einem Neustart mitten im Umtausch)? */
        boolean zurueckOffen() {
            return zustand == Zustand.HIN || zustand == Zustand.ZURUECK;
        }

        /** Fuer die Stand-Datei: "SETZEN erde". */
        String alsText() {
            return zustand.name() + (element == null ? "" : " " + element.englisch);
        }

        /** Aus der Stand-Datei; Unlesbares = RUHE. HIN nach Neustart = halb umgetauscht -> ZURUECK. */
        static Ablauf ausText(String text) {
            Ablauf a = new Ablauf();
            if (text == null || text.isBlank()) return a;
            String[] t = text.strip().split("\\s+");
            try {
                a.zustand = Zustand.valueOf(t[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return new Ablauf();
            }
            a.element = t.length > 1 ? Element.ausSchluessel(t[1]) : null;
            if (a.zustand == Zustand.HIN) a.zustand = Zustand.ZURUECK;
            if (a.zustand == Zustand.RUHE) a.element = null;
            return a;
        }
    }

    // ---- Bildschirm-Schritte (TODO: Fotoserie fehlt) ---------------------------------------------------------------
    // Alle noch leer: der Klickweg zum Fenster "Titan Enhancement" und die Knoepfe ("Reset all enhancements", MAX je
    // Wert) sind ohne Autors Fotoserie nicht bekannt. Werden nur aufgerufen, wenn umtauschMoeglich() (KLICKWEG_GEBAUT).

    private static boolean nochNichtGebaut(String schritt) {
        Log.info("Titan-Elixier", Sprache.t(schritt + ": not built yet -- photo series missing.",
                schritt + ": noch nicht gebaut -- Fotoserie fehlt."));
        return false;
    }

    /** TODO Fotoserie: von der Burg zum Titanen-Turm, dort ins Fenster "Titan Enhancement". */
    static boolean zumEnhancementFenster(BotContext ctx) {
        return nochNichtGebaut(Sprache.t("Way to Titan Enhancement", "Weg zu Titan Enhancement"));
    }

    /** TODO Fotoserie: im Enhancement-Fenster den Titan mit diesem Namen auswaehlen. */
    static boolean titanWaehlen(BotContext ctx, String titan) {
        return nochNichtGebaut(Sprache.t("Select titan " + titan, "Titan " + titan + " auswaehlen"));
    }

    /** TODO Fotoserie: gruener Knopf "Reset all enhancements" (+ Bestaetigung, falls es eine gibt). */
    static boolean allesZuruecksetzen(BotContext ctx, String titan) {
        return nochNichtGebaut("Reset all enhancements (" + titan + ")");
    }

    /** TODO Fotoserie: die Wertnamen des gewaehlten Titanen lesen (Health, Damage, Crit, Dodge ...). */
    static List<String> werteLesen(BotContext ctx) {
        nochNichtGebaut(Sprache.t("Read stat names", "Wertnamen lesen"));
        return List.of();
    }

    /** TODO Fotoserie: beim Wert mit diesem Namen auf MAX druecken. */
    static boolean wertMax(BotContext ctx, String wert) {
        return nochNichtGebaut("MAX " + wert);
    }

    /** TODO Fotoserie: Fenster schliessen und zurueck zur Burg/Weltkarte. */
    static boolean zurueckZurBurg(BotContext ctx) {
        return nochNichtGebaut(Sprache.t("Back to the castle", "Zurueck zur Burg"));
    }

    /**
     * Einen Titan nach seiner Verteilung ausmaxen: erst alle MAX-Werte, zuletzt der Rest-Wert. Nutzt nur die
     * Bildschirm-Schritte oben -- liefert false, solange die nicht gebaut sind.
     */
    static boolean ausmaxen(BotContext ctx, Ziel z) {
        if (!titanWaehlen(ctx, z.titan())) return false;
        List<String> werte = werteLesen(ctx);
        if (werte.isEmpty()) return false;
        boolean ok = true;
        for (String w : werte) if (z.verteilung().maxen(w)) ok &= wertMax(ctx, w);
        for (String w : werte) if (z.verteilung().bekommtRest(w)) ok &= wertMax(ctx, w);
        return ok;
    }

    /** Welche Werte in welcher Reihenfolge MAX bekommen (fuer Log und Tests): erst die MAX-Werte, dann der Rest. */
    static List<String> maxReihenfolge(Verteilung v, List<String> werte) {
        List<String> r = new ArrayList<>();
        for (String w : werte) if (v.maxen(w)) r.add(w);
        for (String w : werte) if (v.bekommtRest(w)) r.add(w);
        return r;
    }

    // ---- Hilfen ----------------------------------------------------------------------------------------------------

    static String ohneKommentar(String zeile) {
        if (zeile == null) return "";
        int h = zeile.indexOf('#');
        return (h >= 0 ? zeile.substring(0, h) : zeile).strip();
    }

    /** Titan-Namen vergleichbar machen ("Keeper of Stars" = "keeper of  stars"). */
    static String schluesselName(String titan) {
        return titan == null ? "" : titan.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
