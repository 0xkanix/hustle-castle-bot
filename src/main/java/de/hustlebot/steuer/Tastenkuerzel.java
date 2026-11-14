package de.hustlebot.steuer;

import de.hustlebot.core.Log;
import de.hustlebot.core.NotAusWache;
import de.hustlebot.core.Sprache;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Die Tastenkuerzel des Steuerprogramms: welche Tastenkombination welches
 * Anzeige-Fenster auf- und zuklappt (Autor 11.10.: "Beispiel: O+A = Protokoll").
 *
 * Der Bot laeuft dabei weiter -- keines dieser Kuerzel pausiert oder stoppt.
 * Not-Aus (F9), Not-Pause (F10) und Weiter (F11) bleiben, wo sie sind
 * (config.properties, umstellbar mit "!settings notaus <Taste>").
 *
 * In den Einstellungen ist jedes Kuerzel aenderbar und einzeln abschaltbar,
 * dazu ein Schalter fuer alle zusammen. Gespeichert wird in
 * tastenkuerzel.properties neben config.properties -- eine eigene Datei, damit
 * config.properties unangetastet bleibt.
 *
 * Eine Kombination ist eine Liste von Tasten, die gleichzeitig gedrueckt sein
 * muessen: "Strg+Alt+A", "O+A", "F7". Einzeltasten wie beim Not-Aus (siehe
 * NotAusWache.vkFuer), dazu die Zusatztasten STRG, ALT, UMSCHALT und WIN.
 */
public final class Tastenkuerzel {

    /** Was ein Kuerzel tun kann. Die Vorgabe darf leer sein (= kein Kuerzel). */
    public enum Aktion {
        // Autor 11.10.: Strg+Alt+Buchstabe statt O+Buchstabe ("O+S loest beim normalen Tippen aus").
        // Nicht E und M: Strg+Alt ist auf der deutschen Tastatur AltGr, AltGr+E = Euro, AltGr+M = Mikro.
        PROTOKOLL(Sprache.t("Log show/hide", "Protokoll auf/zu"), "Strg+Alt+A", "O+A"),
        NOTIZBLOCK(Sprache.t("Notebook show/hide", "Notizblock auf/zu"), "Strg+Alt+N", "O+N"),
        VORRAT(Sprache.t("Resources (castle status) show/hide", "Vorrat (Burg-Stand) auf/zu"), "Strg+Alt+V", "O+V"),
        STATISTIK(Sprache.t("Statistics show/hide", "Statistik auf/zu"), "Strg+Alt+S", "O+S"),
        STATUS(Sprache.t("Status show/hide", "Status auf/zu"), "Strg+Alt+Z", "O+Z"),
        MINILEISTE(Sprache.t("Mini bar show/hide", "Mini-Leiste auf/zu"), "Strg+Alt+I", "O+M"),
        MELDUNGEN(Sprache.t("Messages show/hide", "Meldungen auf/zu"), "Strg+Alt+G", "O+E"),
        STEUERFENSTER(Sprache.t("Control window show/hide", "Steuerprogramm auf/zu"), "Strg+Alt+B", "O+B"),
        // Autor 14.10.: "Bot beenden" nur noch per Taste, nicht als Knopf (die Maus gehoert beim Spielen dem Bot).
        BEENDEN(Sprache.t("Quit bot program (asks first)", "Bot-Programm beenden (fragt erst)"), "Strg+Alt+X", ""),
        PROFIL_1(Sprache.t("Apply profile 1", "Profil 1 anwenden"), "", ""),
        PROFIL_2(Sprache.t("Apply profile 2", "Profil 2 anwenden"), "", ""),
        PROFIL_3(Sprache.t("Apply profile 3", "Profil 3 anwenden"), "", "");

        public final String anzeige;
        public final String vorgabe;
        /** Die fruehere Vorgabe (O+Buchstabe): steht sie noch in der Datei, gilt die neue. */
        final String frueher;

        Aktion(String anzeige, String vorgabe, String frueher) {
            this.anzeige = anzeige;
            this.vorgabe = vorgabe;
            this.frueher = frueher;
        }

        String schluessel() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /**
         * Die Aktion zu einem getippten Wort ("protokoll", "log", "notizblock", "profil 2", "sprache"), fuer
         * "!steuer kuerzel protokoll O+A" in Discord (Auftrag 6, 11.10.). Null, wenn nichts passt.
         */
        public static Aktion ausWort(String wort) {
            if (wort == null) return null;
            String w = wort.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                    .replaceAll("[^a-z0-9]", "");
            if (w.isEmpty()) return null;
            if (w.startsWith("protokoll") || w.startsWith("log")) return PROTOKOLL;
            if (w.startsWith("notiz") || w.startsWith("note")) return NOTIZBLOCK;
            if (w.startsWith("vorrat") || w.startsWith("stand") || w.startsWith("stock") || w.startsWith("burg")) return VORRAT;
            if (w.startsWith("statistik") || w.startsWith("stats")) return STATISTIK;
            if (w.startsWith("status") || w.startsWith("zustand") || w.startsWith("state")) return STATUS;
            if (w.startsWith("mini") || w.startsWith("leiste") || w.startsWith("bar")) return MINILEISTE;
            if (w.startsWith("meldung") || w.startsWith("message") || w.startsWith("nachricht")) return MELDUNGEN;
            if (w.startsWith("steuer") || w.startsWith("fenster") || w.startsWith("window") || w.startsWith("control")) return STEUERFENSTER;
            if (w.startsWith("beenden") || w.startsWith("quit") || w.startsWith("exit")) return BEENDEN;
            if (w.matches("profil(e)?1")) return PROFIL_1;
            if (w.matches("profil(e)?2")) return PROFIL_2;
            if (w.matches("profil(e)?3")) return PROFIL_3;
            return null;
        }
    }

    /** Eine Tastenkombination: die Windows-Tastencodes und die Schreibweise fuers Fenster. */
    public record Kombination(List<Integer> codes, String anzeige) {
        public boolean leer() {
            return codes.isEmpty();
        }
    }

    /** Ein Eintrag: Kombination plus Schalter. */
    public record Eintrag(Aktion aktion, Kombination kombination, boolean an) {
        public boolean wirksam() {
            return an && kombination != null && !kombination.leer();
        }
    }

    private static final int VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B;

    private final Path datei;
    private final Map<Aktion, Eintrag> eintraege = new EnumMap<>(Aktion.class);
    private boolean alleAn = true;

    public Tastenkuerzel(Path datei) {
        this.datei = datei;
        laden();
    }

    // ------------------------------------------------------------------
    //  Kombinationen lesen und schreiben
    // ------------------------------------------------------------------

    /**
     * "O+A", "strg + alt + p", "F7" -> Tastencodes. Null, wenn ein Teil unbekannt ist oder
     * nichts dasteht. Doppelte Tasten zaehlen einmal. Hoechstens vier Tasten.
     */
    public static Kombination lesen(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        Set<Integer> codes = new LinkedHashSet<>();
        List<String> namen = new ArrayList<>();
        for (String teil : t.split("\\+", -1)) {   // -1: "O+" bleibt ungueltig statt zu "O" zu werden
            String s = teil.trim();
            if (s.isEmpty()) return null;
            int vk = zusatztaste(s);
            String name;
            if (vk != 0) {
                name = zusatzName(vk);
            } else {
                vk = NotAusWache.vkFuer(s);
                if (vk == 0) return null;
                name = NotAusWache.anzeigeName(s);
            }
            if (codes.add(vk)) namen.add(name);
        }
        if (codes.isEmpty() || codes.size() > 4) return null;
        return new Kombination(List.copyOf(codes), String.join("+", namen));
    }

    private static int zusatztaste(String s) {
        return switch (s.toUpperCase(Locale.GERMAN)) {
            case "STRG", "CTRL", "CONTROL", "STEUERUNG" -> VK_CONTROL;
            case "ALT" -> VK_MENU;
            case "UMSCHALT", "SHIFT", "HOCHSTELL" -> VK_SHIFT;
            case "WIN", "WINDOWS" -> VK_LWIN;
            default -> 0;
        };
    }

    private static String zusatzName(int vk) {
        return switch (vk) {
            case VK_CONTROL -> Sprache.t("Ctrl", "Strg");
            case VK_MENU -> "Alt";
            case VK_SHIFT -> Sprache.t("Shift", "Umschalt");
            case VK_LWIN -> "Win";
            default -> "?";
        };
    }

    /** Ist die Schreibweise gueltig? Leer gilt als gueltig (= kein Kuerzel). */
    public static boolean gueltig(String text) {
        return text == null || text.trim().isEmpty() || lesen(text) != null;
    }

    /**
     * Wirkt die Kombination auch beim normalen Tippen? Zwei Buchstaben wie O+A kommen beim
     * Schreiben zwar selten exakt gleichzeitig, aber es kann passieren -- deshalb der Hinweis.
     * Mit Strg/Alt/Win oder einer Funktionstaste dabei ist es sicher, ausser Strg+Alt mit einer
     * AltGr-Taste (Strg+Alt+E = Euro-Zeichen).
     */
    public static boolean imAlltagMoeglich(Kombination k) {
        if (k == null || k.leer()) return false;
        // AltGr meldet Windows als Strg+Alt: Strg+Alt+E tippt man beim Euro-Zeichen mit (ebenso @, Mikro, Klammern).
        if (k.codes().contains(VK_CONTROL) && k.codes().contains(VK_MENU) && !k.codes().contains(VK_LWIN)) {
            for (int vk : k.codes()) if (ALTGR_ZEICHEN.contains(vk)) return true;
        }
        for (int vk : k.codes()) {
            if (vk == VK_CONTROL || vk == VK_MENU || vk == VK_LWIN) return false;
            if (vk >= 0x70 && vk <= 0x7B) return false;   // F1-F12
        }
        return true;
    }

    /** Tasten, die mit AltGr (= Strg+Alt) auf der deutschen Tastatur ein Zeichen schreiben: E Q M 2 3 7 8 9 0. */
    private static final Set<Integer> ALTGR_ZEICHEN = Set.of((int) 'E', (int) 'Q', (int) 'M',
            (int) '2', (int) '3', (int) '7', (int) '8', (int) '9', (int) '0');

    // ------------------------------------------------------------------
    //  Eintraege
    // ------------------------------------------------------------------

    public synchronized Eintrag eintrag(Aktion a) {
        return eintraege.get(a);
    }

    public synchronized List<Eintrag> alle() {
        return new ArrayList<>(eintraege.values());
    }

    public synchronized boolean alleAn() {
        return alleAn;
    }

    public synchronized void setAlleAn(boolean an) {
        alleAn = an;
        speichern();
    }

    /**
     * Setzt die Kombination einer Aktion. Leer schaltet sie ab. Liefert false und aendert
     * nichts, wenn die Schreibweise unbekannt ist oder eine andere Aktion dieselbe
     * Kombination schon hat.
     */
    public synchronized boolean setzen(Aktion a, String text) {
        String t = text == null ? "" : text.trim();
        Kombination k;
        if (t.isEmpty()) {
            k = new Kombination(List.of(), "");
        } else {
            k = lesen(t);
            if (k == null) return false;
            for (Eintrag e : eintraege.values()) {
                if (e.aktion() != a && e.kombination() != null && gleich(e.kombination(), k)) return false;
            }
        }
        Eintrag alt = eintraege.get(a);
        eintraege.put(a, new Eintrag(a, k, alt == null || alt.an()));
        speichern();
        return true;
    }

    public synchronized void setAn(Aktion a, boolean an) {
        Eintrag alt = eintraege.get(a);
        eintraege.put(a, new Eintrag(a, alt == null ? new Kombination(List.of(), "") : alt.kombination(), an));
        speichern();
    }

    /** Welche Aktion hat diese Kombination? Null, wenn keine. */
    public synchronized Aktion aktionFuer(Kombination k) {
        if (!alleAn || k == null) return null;
        for (Eintrag e : eintraege.values()) {
            if (e.wirksam() && gleich(e.kombination(), k)) return e.aktion();
        }
        return null;
    }

    private static boolean gleich(Kombination a, Kombination b) {
        return new java.util.HashSet<>(a.codes()).equals(new java.util.HashSet<>(b.codes()));
    }

    /** Die wirksamen Eintraege -- das, was die Wache ueberwacht. Leer, wenn alle aus sind. */
    public synchronized List<Eintrag> wirksame() {
        List<Eintrag> l = new ArrayList<>();
        if (!alleAn) return l;
        for (Eintrag e : eintraege.values()) {
            if (e.wirksam()) l.add(e);
        }
        return l;
    }

    /** Zurueck auf die Vorgaben. */
    public synchronized void vorgaben() {
        eintraege.clear();
        for (Aktion a : Aktion.values()) {
            Kombination k = lesen(a.vorgabe);
            eintraege.put(a, new Eintrag(a, k == null ? new Kombination(List.of(), "") : k, true));
        }
        alleAn = true;
        speichern();
    }

    // ------------------------------------------------------------------
    //  Datei
    // ------------------------------------------------------------------

    private void laden() {
        Properties p = new Properties();
        if (Files.exists(datei)) {
            try (InputStream in = Files.newInputStream(datei)) {
                p.load(in);
            } catch (Exception e) {
                Log.warn("Tastenkuerzel", Sprache.t(datei + " could not be read: " + e.getMessage(),
                        datei + " liess sich nicht lesen: " + e.getMessage()));
            }
        }
        alleAn = !"aus".equalsIgnoreCase(p.getProperty("alle", "an").trim())
                && !"false".equalsIgnoreCase(p.getProperty("alle", "an").trim());
        for (Aktion a : Aktion.values()) {
            String text = p.getProperty(a.schluessel(), a.vorgabe);
            Kombination k = lesen(text);
            // Steht noch die alte Vorgabe (O+A ...) in der Datei, gilt die neue (Strg+Alt+A ...).
            Kombination alt = lesen(a.frueher);
            if (k != null && alt != null && gleich(k, alt)) k = lesen(a.vorgabe);
            if (k == null) k = new Kombination(List.of(), "");
            boolean an = !"aus".equalsIgnoreCase(p.getProperty(a.schluessel() + ".an", "an").trim())
                    && !"false".equalsIgnoreCase(p.getProperty(a.schluessel() + ".an", "an").trim());
            eintraege.put(a, new Eintrag(a, k, an));
        }
    }

    private void speichern() {
        Properties p = new Properties();
        p.setProperty("alle", alleAn ? "an" : "aus");
        for (Eintrag e : eintraege.values()) {
            p.setProperty(e.aktion().schluessel(), e.kombination() == null ? "" : e.kombination().anzeige());
            p.setProperty(e.aktion().schluessel() + ".an", e.an() ? "an" : "aus");
        }
        try (OutputStream out = Files.newOutputStream(datei)) {
            p.store(out, "Tastenkuerzel des Steuerprogramms. Aendern im Steuerprogramm unter Einstellungen > Tastenkuerzel.\n"
                    + "Schreibweise: Tasten mit + verbinden, z. B. Strg+Alt+A, F7. Leer = kein Kuerzel.");
        } catch (Exception e) {
            Log.warn("Tastenkuerzel", Sprache.t(datei + " could not be saved: " + e.getMessage(),
                    datei + " liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
