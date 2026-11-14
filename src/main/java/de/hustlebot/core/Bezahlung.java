package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Womit Verlies, Kathedrale und Arena bezahlt werden (Teil A, Autor 27.09.).
 *
 * Je Modus eine Standard-Bezahlart, dazu ein Nahrungs-Limit: so viel Nahrung
 * darf ein Auftrag in diesem Modus hoechstens verbrauchen. Beides steht in
 * bezahlung.txt und laesst sich per "!settings bezahlen ..." oder im
 * Formular "Bezahlen" aendern. Ein Wort im Start-Befehl ("!start dungeon 10
 * food") gilt nur fuer diesen Auftrag und schlaegt den Standard.
 *
 * Keine Reserve fuer den Clan-Krieg -- Autor: "alles verbrauchen".
 * Diamanten gibt der Bot NIE aus; das regeln die Modi selbst (bei zu wenig
 * Nahrung wird "Participate" gar nicht erst gedrueckt).
 */
public final class Bezahlung {

    private Bezahlung() {}

    /** Die vier Bezahlarten aus Autors Auftrag, in seiner Reihenfolge. */
    public enum Art {
        TICKETS("tickets", "nur Tickets", "tickets only"),
        NAHRUNG("food", "nur Nahrung", "food only"),
        ERST_TICKETS("tickets+food", "erst Tickets, dann Nahrung", "tickets first, then food"),
        ALLES("alles", "Tickets, dann Nahrung, dann Nahrungskisten (Tasche)", "tickets, then food, then food chests (bag)"),
        // Mia 21.10.: "food only" oeffnet absichtlich keine Kisten, "alles" nimmt zuerst Tickets --
        // wer ohne Tickets nur Nahrung und Kisten will, hatte keine Wahl.
        NAHRUNG_KISTEN("food+chests", "erst Nahrung, dann Nahrungskisten (ohne Tickets)", "food, then food chests (no tickets)");

        public final String wort;
        public final String anzeige;
        public final String englisch;

        Art(String wort, String anzeige, String englisch) {
            this.wort = wort;
            this.anzeige = anzeige;
            this.englisch = englisch;
        }

        /** Darf dieser Modus ueberhaupt Nahrung ausgeben? */
        public boolean mitNahrung() {
            return this != TICKETS;
        }

        /** Mia 21.10.: Rutscht der Modus nie auf Tickets (Reiter, Fackeln, Schluessel) um? */
        public boolean nieTickets() {
            return this == NAHRUNG_KISTEN;
        }

        /** Mia 21.10.: Darf der Bot bei knapper Nahrung Nahrungskisten aus der Tasche oeffnen? */
        public boolean oeffnetKisten() {
            return this == ALLES || this == NAHRUNG_KISTEN;
        }

        /** Mia 21.10.: Wird vom ersten Teilnehmen an mit Nahrung bezahlt (Tickets kommen gar nicht vor)? */
        public boolean startetMitNahrung() {
            return this == NAHRUNG || this == NAHRUNG_KISTEN;
        }
    }

    /** Die Modi mit Bezahlart (Kennungen wie GameMode.id()). */
    public static final List<String> MODI = List.of("dungeon", "cathedral", "arena");

    private static final Path DATEI = Path.of("bezahlung.txt");
    private static final Map<String, Art> STANDARD = new ConcurrentHashMap<>();
    private static final Map<String, Long> LIMIT = new ConcurrentHashMap<>();
    /**
     * So viel Nahrung fasst das Lager (Autor, 29.09.: "mein aktueller maximal
     * Betrag", 1,2 Mio). Muessen Kisten geoeffnet werden, fuellt der Bot
     * moeglichst genau bis hierhin auf (leicht drueber ist erlaubt).
     */
    private static volatile long lager = 1_200_000L;

    public static long lager() {
        return lager;
    }

    public static void setLager(long neu) {
        lager = Math.max(100_000L, Math.min(100_000_000L, neu));
        speichern();
        Log.info("Bezahlung", Sprache.t("Food storage: " + zahl(lager) + " -- chests fill up to that.",
                "Nahrungslager: " + zahl(lager) + " -- Kisten fuellen bis dahin auf."));
    }

    /** Bezahlart aus dem Start-Befehl; null = der Standard je Modus gilt. */
    private static volatile Art auftrag;
    /** In diesem Auftrag schon verbrauchte Nahrung je Modus. */
    private static final Map<String, Long> VERBRAUCHT = new ConcurrentHashMap<>();

    static {
        laden();
    }

    /** Was ein Wort bedeutet ("food", "essen", "tickets+food" ...), oder null. */
    public static Art ausWort(String wort) {
        if (wort == null) return null;
        return switch (wort.trim().toLowerCase(Locale.ROOT)) {
            case "tickets", "ticket", "schluessel", "schlüssel", "keys", "fackeln", "torches", "1" -> Art.TICKETS;
            case "food", "essen", "nahrung", "aepfel", "äpfel", "apfel", "apples", "2" -> Art.NAHRUNG;
            case "tickets+food", "tickets+essen", "tickets+nahrung", "erst-tickets", "ersttickets", "3" -> Art.ERST_TICKETS;
            case "alles", "tickets+food+kisten", "kisten", "all", "4" -> Art.ALLES;
            case "food+chests", "food+chest", "food+kisten", "nahrung+kisten", "essen+kisten", "5" -> Art.NAHRUNG_KISTEN;
            default -> null;
        };
    }

    /** "dungeon", "verlies", "kathedrale" ... -> Kennung, oder null. */
    public static String modusAusWort(String wort) {
        if (wort == null) return null;
        return switch (wort.trim().toLowerCase(Locale.ROOT)) {
            case "dungeon", "verlies", "du" -> "dungeon";
            case "cathedral", "kathedrale", "dc", "kath" -> "cathedral";
            case "arena", "turnier", "tournament", "ar" -> "arena";
            default -> null;
        };
    }

    public static String anzeigeName(String modus) {
        return switch (modus) {
            case "dungeon" -> "Verlies";
            case "cathedral" -> "Kathedrale";
            case "arena" -> "Arena";
            default -> modus;
        };
    }

    /** Womit dieser Modus bezahlt: Wort aus dem Start-Befehl, sonst Standard. */
    public static Art art(String modus) {
        Art a = auftrag;
        return a != null ? a : standard(modus);
    }

    /** Das Wort aus dem Start-Befehl; null = es gilt der Standard je Modus. */
    public static Art auftragsArt() {
        return auftrag;
    }

    public static Art standard(String modus) {
        return STANDARD.getOrDefault(modus, Art.TICKETS);
    }

    public static void setStandard(String modus, Art art) {
        STANDARD.put(modus, art);
        speichern();
        Log.info("Bezahlung", Sprache.t(anzeigeName(modus) + ": paying from now on with " + art.anzeige + ".",
                anzeigeName(modus) + ": bezahlt ab jetzt " + art.anzeige + "."));
    }

    /** Nahrungs-Limit je Tag (Autor, 29.09.: "pro Tag"), -1 = ohne Grenze. */
    public static long limit(String modus) {
        // Autor, 29.09.: "fuer den Run heute bis 9 Uhr unendlich viel Futter" -- bis zu
        // dieser Uhrzeit gilt kein Nahrungs-Limit, danach wieder das eingestellte.
        LocalDateTime bis = ohneLimitBis;
        if (bis != null && Zeitzone.jetzt().isBefore(bis)) return -1L;
        return LIMIT.getOrDefault(modus, -1L);
    }

    /** Das eingestellte Limit, ohne die Zeit "ohne Limit bis" (fuer das Start-Formular). -1 = ohne Grenze. */
    public static long gespeichertesLimit(String modus) {
        return LIMIT.getOrDefault(modus, -1L);
    }

    /** Bis wann kein Nahrungs-Limit gilt (null = immer das eingestellte). Zeile "ohne-limit-bis=" in bezahlung.txt. */
    private static volatile LocalDateTime ohneLimitBis;

    public static LocalDateTime ohneLimitBis() {
        return ohneLimitBis;
    }

    public static void setOhneLimitBis(LocalDateTime bis) {
        ohneLimitBis = bis;
        speichern();
        Log.info("Bezahlung", Sprache.t(bis == null ? "Food limit applies again." : "No food limit until " + bis + ".",
                bis == null ? "Nahrungs-Limit gilt wieder." : "Kein Nahrungs-Limit bis " + bis + "."));
    }

    public static void setLimit(String modus, long limit) {
        if (limit <= 0) LIMIT.remove(modus);
        else LIMIT.put(modus, limit);
        speichern();
        Log.info("Bezahlung", Sprache.t(anzeigeName(modus) + ": food limit " + (limit <= 0 ? "off" : zahl(limit) + " per round (reset after each event)") + ".",
                anzeigeName(modus) + ": Nahrungs-Limit "
                + (limit <= 0 ? "aus" : zahl(limit) + " je Runde (nach jedem Event neu)") + "."));
    }

    /**
     * Ein neuer Auftrag beginnt (!start). Das Wort aus dem Start-Befehl gilt
     * bis zum naechsten !start; der Verbrauch faengt wieder bei null an.
     */
    public static void auftragBeginnt(Art ausStartBefehl) {
        auftrag = ausStartBefehl;
        // Die Arena-Pause stellt hiermit kurz um und wieder zurueck (Freunde 11.10.) -- deshalb nichts weiter hier.
        // Der Verbrauch zaehlt je TAG (Autor, 29.09.), nicht je Auftrag -- sonst finge er
        // bei jedem Spiel-Neustart (alle 2 h ein neuer Auftrag) wieder bei null an.
    }

    private static final Path HEUTE_DATEI = Path.of("nahrung-heute.txt");
    private static volatile java.time.LocalDate verbrauchtAm = heuteLaden();

    /** Neuer Tag: der Zaehler faengt um Mitternacht wieder bei null an. */
    private static void tagPruefen() {
        java.time.LocalDate heute = Zeitzone.heute();
        if (!heute.equals(verbrauchtAm)) {
            VERBRAUCHT.clear();
            verbrauchtAm = heute;
        }
    }

    public static long verbraucht(String modus) {
        tagPruefen();
        return VERBRAUCHT.getOrDefault(modus, 0L);
    }

    /** Participate mit Nahrung gedrueckt: den Preis mitzaehlen (und merken, ueberlebt Neustarts). */
    public static void verbuche(String modus, long preis) {
        tagPruefen();
        if (preis > 0) VERBRAUCHT.merge(modus, preis, Long::sum);
        heuteSpeichern();
    }

    private static java.time.LocalDate heuteLaden() {
        java.time.LocalDate heute = Zeitzone.heute();
        try {
            if (!Files.exists(HEUTE_DATEI)) return heute;
            List<String> z = Dateien.zeilen(HEUTE_DATEI);
            if (z.isEmpty() || !z.get(0).trim().equals(heute.toString())) return heute;
            for (String zeile : z.subList(1, z.size())) {
                int g = zeile.indexOf('=');
                if (g > 0) VERBRAUCHT.put(zeile.substring(0, g).trim(), Long.parseLong(zeile.substring(g + 1).trim()));
            }
        } catch (Exception e) {
            Log.debug("Bezahlung", "nahrung-heute.txt nicht lesbar: " + e.getMessage());
        }
        return heute;
    }

    private static void heuteSpeichern() {
        StringBuilder b = new StringBuilder(String.valueOf(verbrauchtAm)).append('\n');
        VERBRAUCHT.forEach((m, v) -> b.append(m).append('=').append(v).append('\n'));
        try {
            Files.writeString(HEUTE_DATEI, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.debug("Bezahlung", "nahrung-heute.txt nicht geschrieben: " + e.getMessage());
        }
    }

    /**
     * Neue Runde im Kreislauf (Autor, 30.09.): nach jedem Gang ins Hammer-Event darf wieder
     * bis zum Limit Nahrung ausgegeben werden -- das Limit gilt je Runde, nicht mehr je Tag.
     */
    public static void rundeNeu(String modus) {
        tagPruefen();
        Long alt = VERBRAUCHT.remove(modus);
        heuteSpeichern();
        Log.info("Bezahlung", Sprache.t(anzeigeName(modus) + ": new round -- food consumption back at 0" + (alt != null ? " (last round: " + zahl(alt) + ")" : "") + ".",
                anzeigeName(modus) + ": neue Runde -- Nahrungs-Verbrauch wieder bei 0"
                + (alt != null ? " (letzte Runde: " + zahl(alt) + ")" : "") + "."));
    }

    /** Passt der naechste Preis noch unter das Limit? */
    public static boolean limitErlaubt(String modus, long preis) {
        long l = limit(modus);
        return l <= 0 || verbraucht(modus) + preis <= l;
    }

    /** Test (!nahrungstest, 28.09.): der naechste Food-Run oeffnet vorher genau eine Food-Kiste. */
    private static volatile boolean kistenTest;

    public static void kistenTestAnfordern() {
        kistenTest = true;
    }

    /** Einmal abholbar. */
    public static boolean kistenTestAbholen() {
        boolean war = kistenTest;
        kistenTest = false;
        return war;
    }

    public static String zahl(long n) {
        return String.format(Locale.GERMANY, "%,d", n).replace('.', ' ');
    }

    // --- Datei --------------------------------------------------------------

    /** Konto-Wechsel (Autor 21.10.): Bezahlart, Limits, Lager und Tagesverbrauch des neuen Kontos lesen. */
    public static synchronized void neuLaden() {
        STANDARD.clear();
        LIMIT.clear();
        VERBRAUCHT.clear();
        lager = 1_200_000L;
        ohneLimitBis = null;
        laden();
        verbrauchtAm = heuteLaden();
    }

    private static void laden() {
        try {
            if (!Files.exists(DATEI)) return;
            for (String zeile : Dateien.zeilen(DATEI)) {
                String z = zeile.trim();
                int gleich = z.indexOf('=');
                if (z.isEmpty() || z.startsWith("#") || gleich <= 0) continue;
                String schluessel = z.substring(0, gleich).trim().toLowerCase(Locale.ROOT);
                String wert = z.substring(gleich + 1).trim();
                if (schluessel.equals("ohne-limit-bis")) {
                    try {
                        ohneLimitBis = wert.isEmpty() || wert.equals("aus") ? null : LocalDateTime.parse(wert);
                    } catch (Exception e) {
                        ohneLimitBis = null;
                    }
                } else if (schluessel.equals("lager")) {
                    String ziffern = wert.replaceAll("[^0-9]", "");
                    if (!ziffern.isEmpty() && ziffern.length() < 12)
                        lager = Math.max(100_000L, Math.min(100_000_000L, Long.parseLong(ziffern)));
                } else if (schluessel.startsWith("limit.")) {
                    String m = modusAusWort(schluessel.substring(6));
                    String ziffern = wert.replaceAll("[^0-9]", "");
                    if (m != null && !ziffern.isEmpty() && ziffern.length() < 13) {
                        LIMIT.put(m, Long.parseLong(ziffern));
                    }
                } else {
                    String m = modusAusWort(schluessel);
                    Art a = ausWort(wert);
                    if (m != null && a != null) STANDARD.put(m, a);
                    else Log.warn("Bezahlung", Sprache.t(DATEI + ": \"" + zeile.trim() + "\" not understood.",
                            DATEI + ": \"" + zeile.trim() + "\" nicht verstanden."));
                }
            }
        } catch (Exception e) {
            Log.warn("Bezahlung", Sprache.t(DATEI + " could not be read: " + e.getMessage(),
                    DATEI + " liess sich nicht lesen: " + e.getMessage()));
        }
    }

    private static void speichern() {
        List<String> z = new ArrayList<>();
        z.add("# Bezahlart je Modus: tickets | food | tickets+food | alles | food+chests");
        z.add("# (alles = Tickets, dann Nahrung, dann Nahrungskisten aus der Tasche)");
        z.add("# (food+chests = Nahrung, dann Nahrungskisten aus der Tasche, nie Tickets)");
        Map<String, String> werte = new LinkedHashMap<>();
        for (String m : MODI) werte.put(m, standard(m).wort);
        werte.forEach((m, w) -> z.add(m + "=" + w));
        z.add("# Nahrungs-Limit je Runde im Kreislauf, nach jedem Event neu (aus = ohne Grenze)");
        for (String m : MODI) {
            long l = LIMIT.getOrDefault(m, -1L);   // das eingestellte, nicht "gerade ohne Limit"
            z.add("limit." + m + "=" + (l > 0 ? String.valueOf(l) : "aus"));
        }
        z.add("# Bis dahin gilt kein Nahrungs-Limit (z. B. fuer einen Nachtlauf), danach wieder das eingestellte");
        z.add("ohne-limit-bis=" + (ohneLimitBis != null ? ohneLimitBis.toString() : "aus"));
        z.add("# So viel Nahrung fasst das Lager -- Kisten fuellen moeglichst genau bis hierhin auf");
        z.add("lager=" + lager);
        try {
            Files.write(DATEI, z, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Bezahlung", Sprache.t("bezahlung.txt could not be saved: " + e.getMessage(),
                    "bezahlung.txt liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
