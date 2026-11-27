package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Die Zeitzone, in der der Bot rechnet (Autor 11.10., Auftrag 6, Punkt 8).
 *
 * Bisher nahm jeder Zeitplan (Clan-Krieg 03:01/09:01/..., Titanen-Turm und Gem Bay um 02:00 und
 * 12:00, Freunden senden, Tipp des Tages, Spieltag-Reset um 02:00, Event-Ende, Abschaltzeit) die
 * Uhr von Windows -- bei Autor ist das Berlin, bei einem Freund in einer anderen Zeitzone liefe
 * der Bot zur falschen Stunde los. Das Spiel selbst setzt um 02:00 BERLINER Zeit zurueck; wer
 * woanders wohnt, stellt hier ein, nach welcher Uhr der Bot gehen soll.
 *
 * Einstellbar im Steuerprogramm (Einstellungen -> Zeitzone) und in Discord ("!zeitzone
 * Europe/Berlin", "!timezone London"). Gespeichert in {@code zeitzone.txt} im Bot-Ordner, eine
 * Zeile mit dem Namen der Zone. Ohne Datei: Europe/Berlin.
 *
 * Alle Stellen im Bot fragen die Uhrzeit ueber {@link #jetzt()}, {@link #heute()} und
 * {@link #uhrzeit()} ab -- nie mehr direkt LocalDateTime.now().
 *
 * ACHTUNG: Diese Klasse darf beim Lesen NICHT ins Protokoll schreiben -- Log holt sich seine
 * Zeitstempel hier, das gaebe sonst einen Kreis. Ein Lesefehler steht in {@link #fehler()}.
 */
public final class Zeitzone {

    private Zeitzone() {}

    public static final ZoneId STANDARD = ZoneId.of("Europe/Berlin");

    /** Fuer Tests austauschbar (wie Matcher.STELLEN_DATEI). */
    static volatile Path datei = Path.of("zeitzone.txt");

    private static volatile ZoneId zone;
    private static volatile long gelesenUm;
    private static volatile String fehler = "";

    /** Kurznamen, die Autor und seine Freunde tippen koennten -- alles klein, ohne Leerzeichen. */
    private static final Map<String, String> KURZ = new LinkedHashMap<>();
    static {
        KURZ.put("berlin", "Europe/Berlin");
        KURZ.put("deutschland", "Europe/Berlin");
        KURZ.put("germany", "Europe/Berlin");
        KURZ.put("de", "Europe/Berlin");
        KURZ.put("bayern", "Europe/Berlin");
        KURZ.put("mez", "Europe/Berlin");    // Konrad 19.11.: deutsche Abkuerzungen (CET kennt Java selbst)
        KURZ.put("mesz", "Europe/Berlin");
        KURZ.put("cest", "Europe/Berlin");
        KURZ.put("muenchen", "Europe/Berlin");
        KURZ.put("munich", "Europe/Berlin");
        KURZ.put("wien", "Europe/Vienna");
        KURZ.put("vienna", "Europe/Vienna");
        KURZ.put("oesterreich", "Europe/Vienna");
        KURZ.put("austria", "Europe/Vienna");
        KURZ.put("zuerich", "Europe/Zurich");
        KURZ.put("zurich", "Europe/Zurich");
        KURZ.put("schweiz", "Europe/Zurich");
        KURZ.put("switzerland", "Europe/Zurich");
        KURZ.put("london", "Europe/London");
        KURZ.put("england", "Europe/London");
        KURZ.put("uk", "Europe/London");
        KURZ.put("paris", "Europe/Paris");
        KURZ.put("frankreich", "Europe/Paris");
        KURZ.put("france", "Europe/Paris");
        KURZ.put("madrid", "Europe/Madrid");
        KURZ.put("spanien", "Europe/Madrid");
        KURZ.put("rom", "Europe/Rome");
        KURZ.put("rome", "Europe/Rome");
        KURZ.put("italien", "Europe/Rome");
        KURZ.put("amsterdam", "Europe/Amsterdam");
        KURZ.put("warschau", "Europe/Warsaw");
        KURZ.put("warsaw", "Europe/Warsaw");
        KURZ.put("polen", "Europe/Warsaw");
        KURZ.put("prag", "Europe/Prague");
        KURZ.put("prague", "Europe/Prague");
        KURZ.put("athen", "Europe/Athens");
        KURZ.put("athens", "Europe/Athens");
        KURZ.put("istanbul", "Europe/Istanbul");
        KURZ.put("tuerkei", "Europe/Istanbul");
        KURZ.put("turkey", "Europe/Istanbul");
        KURZ.put("kiew", "Europe/Kyiv");
        KURZ.put("kyiv", "Europe/Kyiv");
        KURZ.put("ukraine", "Europe/Kyiv");
        KURZ.put("moskau", "Europe/Moscow");
        KURZ.put("moscow", "Europe/Moscow");
        KURZ.put("russland", "Europe/Moscow");
        KURZ.put("newyork", "America/New_York");
        KURZ.put("ny", "America/New_York");
        KURZ.put("usaost", "America/New_York");
        KURZ.put("chicago", "America/Chicago");
        KURZ.put("denver", "America/Denver");
        KURZ.put("losangeles", "America/Los_Angeles");
        KURZ.put("la", "America/Los_Angeles");
        KURZ.put("usawest", "America/Los_Angeles");
        KURZ.put("toronto", "America/Toronto");
        KURZ.put("saopaulo", "America/Sao_Paulo");
        KURZ.put("brasilien", "America/Sao_Paulo");
        KURZ.put("tokio", "Asia/Tokyo");
        KURZ.put("tokyo", "Asia/Tokyo");
        KURZ.put("japan", "Asia/Tokyo");
        KURZ.put("peking", "Asia/Shanghai");
        KURZ.put("shanghai", "Asia/Shanghai");
        KURZ.put("china", "Asia/Shanghai");
        KURZ.put("dubai", "Asia/Dubai");
        KURZ.put("sydney", "Australia/Sydney");
        KURZ.put("australien", "Australia/Sydney");
        KURZ.put("utc", "UTC");
        KURZ.put("gmt", "UTC");
        KURZ.put("weltzeit", "UTC");
    }

    /** Die eingestellte Zone (ohne Datei: Europe/Berlin). */
    public static ZoneId zone() {
        ZoneId z = zone;
        long jetzt = System.currentTimeMillis();
        if (z == null || jetzt - gelesenUm > 10_000) {
            gelesenUm = jetzt;
            z = laden();
            zone = z;
        }
        return z;
    }

    public static String name() {
        return zone().getId();
    }

    /**
     * Die Uhr, nach der der ganze Bot geht. Konrad 19.11.: der Augenblick kommt aus der {@link Weltuhr} (Rechneruhr,
     * richtiggestellt mit der Zeit aus dem Netz) -- geht die Windows-Uhr falsch, rechnet der Bot trotzdem richtig.
     */
    public static LocalDateTime jetzt() {
        return LocalDateTime.ofInstant(Weltuhr.jetzt(), zone());
    }

    public static LocalDate heute() {
        return jetzt().toLocalDate();
    }

    public static LocalTime uhrzeit() {
        return jetzt().toLocalTime();
    }

    public static ZonedDateTime jetztMitZone() {
        return ZonedDateTime.ofInstant(Weltuhr.jetzt(), zone());
    }

    /**
     * Ein Zeitpunkt der Rechneruhr ({@code System.currentTimeMillis()} plus etwas) als Ortszeit der Bot-Zone, richtig-
     * gestellt mit der Weltuhr. Konrad 20.11. (Geschwister): "Fertig etwa" in Discord rechnete roh nach Berlin um und lag
     * bei ihm 9 h daneben.
     */
    public static LocalDateTime ausRechnerMs(long rechnerMs) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rechnerMs + Weltuhr.versatzMs()), zone());
    }

    /** Ein Lesefehler der Datei, sonst leer -- fuer die Anzeige, nicht fuers Protokoll. */
    public static String fehler() {
        return fehler;
    }

    /**
     * Macht aus "Europe/Berlin", "berlin", "London", "utc" oder "+2" eine Zone. Leer, wenn nichts
     * davon passt -- dann bleibt alles, wie es ist.
     *
     * Konrad 19.11. (Geschwister): Passt das Ganze nicht, zaehlt das erste Wort ("Europe/Berlin (UTC+01:00)",
     * "Berlin, Germany") und danach der Teil hinter dem letzten Schraegstrich ("Europa/Berlin").
     */
    public static Optional<ZoneId> verstehen(String eingabe) {
        Optional<ZoneId> z = verstehenGanz(eingabe);
        if (z.isPresent() || eingabe == null) return z;
        String[] teile = eingabe.strip().split("[\\s,;()]+");
        String erstes = teile.length > 0 ? teile[0] : "";
        if (teile.length > 1 && !erstes.isEmpty()) {
            z = verstehenGanz(erstes);
            if (z.isPresent()) return z;
        }
        int schraeg = erstes.lastIndexOf('/');
        if (schraeg >= 0 && schraeg < erstes.length() - 1) return verstehenGanz(erstes.substring(schraeg + 1));
        return Optional.empty();
    }

    private static Optional<ZoneId> verstehenGanz(String eingabe) {
        if (eingabe == null) return Optional.empty();
        String e = eingabe.strip();
        if (e.isEmpty()) return Optional.empty();
        // Genau so, wie Java sie kennt ("Europe/Berlin", "UTC", "GMT+2").
        try {
            return Optional.of(ZoneId.of(e));
        } catch (Exception ignored) {
            // weiter unten
        }
        String k = e.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9+:/_-]", "");
        if (k.isEmpty()) return Optional.empty();
        String kurz = KURZ.get(k.replaceAll("[^a-z0-9]", ""));
        if (kurz != null) return Optional.of(ZoneId.of(kurz));
        // "+2", "-5", "+5:30", "utc+1"
        String versatz = k.startsWith("utc") || k.startsWith("gmt") ? k.substring(3) : k;
        if (versatz.matches("[+-]\\d{1,2}(:\\d{2})?")) {
            try {
                String[] hm = versatz.substring(1).split(":");
                int vorzeichen = versatz.charAt(0) == '-' ? -1 : 1;
                int stunden = Integer.parseInt(hm[0]), minuten = hm.length > 1 ? Integer.parseInt(hm[1]) : 0;
                return Optional.of(ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofHoursMinutes(vorzeichen * stunden, vorzeichen * minuten)));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        // Gross/klein egal: "europe/berlin", "berlin" als Stadtteil eines Java-Namens ("Europe/Berlin").
        String ohne = k.replaceAll("[^a-z]", "");
        ZoneId treffer = null;
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            String idKlein = id.toLowerCase(Locale.ROOT);
            if (idKlein.equals(k)) return Optional.of(ZoneId.of(id));
            int schraeg = idKlein.lastIndexOf('/');
            String stadt = schraeg >= 0 ? idKlein.substring(schraeg + 1).replaceAll("[^a-z]", "") : idKlein;
            if (!ohne.isEmpty() && stadt.equals(ohne) && !idKlein.startsWith("etc/") && !idKlein.startsWith("systemv/")) {
                if (treffer == null) treffer = ZoneId.of(id);
            }
        }
        return Optional.ofNullable(treffer);
    }

    /**
     * Stellt die Zone um und speichert sie. Liefert false, wenn die Eingabe keine Zone ist --
     * dann bleibt die alte.
     */
    public static synchronized boolean setzen(String eingabe) {
        Optional<ZoneId> z = verstehen(eingabe);
        if (z.isEmpty()) return false;
        try {
            Files.writeString(datei, "# Zeitzone des Bots (Autor 11.10.): nach dieser Uhr gehen Clan-Krieg, Turm, Gem Bay,\n"
                    + "# Freunde senden, Tipp des Tages, Spieltag-Reset, Event und Abschaltzeit.\n"
                    + "# Gesetzt mit !zeitzone <Name> oder im Steuerprogramm. Standard: Europe/Berlin.\n"
                    + z.get().getId() + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Zeitzone", datei + " liess sich nicht schreiben: " + e.getMessage());
        }
        zone = z.get();
        gelesenUm = System.currentTimeMillis();
        fehler = "";
        Log.info("Zeitzone", "Zeitzone ist jetzt " + z.get().getId() + " (" + uhrText() + ").");
        return true;
    }

    /** Nur fuer Tests: beim naechsten Zugriff neu lesen. */
    static synchronized void neuLesen() {
        zone = null;
        gelesenUm = 0;
    }

    private static ZoneId laden() {
        try {
            if (!Files.exists(datei)) {
                fehler = "";
                return STANDARD;
            }
            for (String z : Dateien.zeilen(datei)) {
                String t = z.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                Optional<ZoneId> id = verstehen(t);
                if (id.isPresent()) {
                    fehler = "";
                    return id.get();
                }
                fehler = "\"" + t + "\" in " + datei + " ist keine Zeitzone -- es gilt " + STANDARD.getId() + ".";
                return STANDARD;
            }
            fehler = "";
            return STANDARD;
        } catch (Exception e) {
            fehler = datei + " nicht lesbar (" + e.getMessage() + ") -- es gilt " + STANDARD.getId() + ".";
            return STANDARD;
        }
    }

    /** "Europe/Berlin, jetzt 14:35 (UTC+02:00)". */
    public static String uhrText() {
        ZonedDateTime z = jetztMitZone();
        return z.getZone().getId() + ", " + Sprache.t("now ", "jetzt ") + z.format(DateTimeFormatter.ofPattern("HH:mm"))
                + " (UTC" + z.getOffset().getId().replace("Z", "+00:00") + ")";
    }

    /** Eine Zeile fuer !settings und das Steuerprogramm (Konrad 19.11.: mit Hinweis, wenn die Windows-Uhr falsch geht). */
    public static String beschreibung() {
        String f = fehler;
        String uhr = Weltuhr.hinweis();
        return uhrText() + (uhr.isEmpty() ? "" : "  -- " + uhr) + (f.isEmpty() ? "" : "  -- " + f);
    }

    /** Die Namen, die man tippen kann (fuer die Hilfe). */
    public static String beispiele() {
        return "Europe/Berlin, Europe/London, Europe/Vienna, Europe/Kyiv, America/New_York, UTC, +2 -- "
                + Sprache.t("or just a city: berlin, london, kyiv, new york",
                            "oder nur eine Stadt: berlin, london, kiew, new york");
    }
}
