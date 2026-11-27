package de.hustlebot.core;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Die richtige Weltzeit -- auch wenn die Windows-Uhr falsch geht (Konrad 19.11.).
 *
 * WAS PASSIERT WAR. Konrad hatte als Zeitzone Europe/Berlin eingestellt. Bei ihm war es 13:04, der Bot zeigte 22:03 und
 * stellte im Clan-Krieg nie auf ("In der Burg ist kein Kriegssymbol"). Seine Berichte seit dem 29.10. (neuer Rechner
 * 723989d5): Das Protokoll (Uhr von Windows) stimmt mit der Discord-Zeit ueberein, die Bot-Uhr ("Time: Europe/Berlin,
 * jetzt ...") lag bis zum 01.11. acht, danach neun Stunden vorn -- genau der Abstand Berlin/Pazifik, inklusive der
 * US-Zeitumstellung am 01.11. In Windows steht also die Zeitzone "Pazifik" (UTC-8), und die Uhrzeit wurde von Hand auf
 * die deutsche Zeit gestellt. Damit zeigt Windows zwar 13:04, zaehlt intern aber 13:04 PAZIFIK = 22:04 Berliner Zeit.
 * Der Bot rechnet (richtig) vom inneren Augenblick in Europe/Berlin um -- und landet neun Stunden daneben.
 *
 * WAS DIESE KLASSE TUT. Jede Antwort aus dem Netz (Sperrliste, Vermittler, Discord) traegt einen "Date"-Kopf mit der
 * Uhrzeit des Servers. Daraus ergibt sich, wie weit die Rechneruhr danebenliegt. Zwei Messungen, die sich einig sind,
 * stellen die Bot-Uhr um diesen Versatz richtig; Zeitzone und Kriegsplan fragen die Zeit hier ab. Abweichungen unter
 * einer Minute gelten als Messrauschen (der Kopf hat nur ganze Sekunden) -- dann bleibt alles wie bisher.
 *
 * Der Versatz wird in weltuhr.txt gemerkt (mit der Windows-Zeitzone), damit er gleich beim naechsten Start gilt, noch
 * bevor die erste Antwort da ist. Stellt jemand seine Windows-Zeitzone um, gilt der alte Wert nicht mehr.
 *
 * ACHTUNG: Wie Zeitzone darf {@link #jetzt()} NICHT ins Protokoll schreiben (es wird sehr oft gerufen).
 */
public final class Weltuhr {

    private Weltuhr() {}

    /** Ab so viel Abweichung geht die Rechneruhr falsch; darunter ist es Messrauschen. */
    static final long SCHWELLE_MS = 60_000;

    /** Zwei Messungen sind sich einig, wenn sie hoechstens so weit auseinanderliegen. */
    static final long EINIG_MS = 30_000;

    /** Die Uhr von Windows (mit der Windows-Zeitzone). Fuer Tests austauschbar. */
    static volatile Clock rechner = Clock.systemDefaultZone();

    /** Hier wird der Versatz gemerkt. Fuer Tests austauschbar (wie Zeitzone.datei). */
    static volatile Path datei = Path.of("weltuhr.txt");

    /** Weltzeit minus Rechnerzeit, in ms. 0 = die Rechneruhr stimmt. */
    private static volatile long versatz = 0;
    private static volatile boolean geladen = false;
    /** Wurde schon einmal gemessen (dann ist "stimmt" belegt, nicht nur angenommen)? */
    private static volatile boolean gemessen = false;
    /** Die letzte, noch unbestaetigte Messung (Weltzeit minus Rechnerzeit). */
    private static Long letzteMessung = null;

    // ------------------------------------------------------------------
    //  Abfragen
    // ------------------------------------------------------------------

    /** Der richtige Augenblick (Rechneruhr plus Versatz). */
    public static Instant jetzt() {
        laden();
        return rechner.instant().plusMillis(versatz);
    }

    /** Weltzeit minus Rechnerzeit in ms (0 = die Rechneruhr stimmt). */
    public static long versatzMs() {
        laden();
        return versatz;
    }

    /** Ein Augenblick der Weltzeit, umgerechnet auf die Rechneruhr (fuer Vergleiche mit System.currentTimeMillis()). */
    public static long rechnerMs(Instant welt) {
        return welt.toEpochMilli() - versatzMs();
    }

    // ------------------------------------------------------------------
    //  Messen
    // ------------------------------------------------------------------

    /** Den "Date"-Kopf einer erfolgreichen Antwort auswerten (mit "Age", falls die Antwort aus einem Zwischenspeicher kam). */
    public static void datumKopf(HttpHeaders kopf) {
        if (kopf == null) return;
        try {
            String date = kopf.firstValue("date").orElse(null);
            long alter = 0;
            String age = kopf.firstValue("age").orElse("").strip();
            if (age.matches("\\d{1,7}")) alter = Long.parseLong(age);
            datumKopf(date, alter);
        } catch (Exception e) {
            // nie stoeren
        }
    }

    /** Einen "Date"-Kopf (RFC 1123, z. B. "Thu, 19 Nov 2026 12:04:00 GMT") plus Alter in Sekunden auswerten. */
    static void datumKopf(String date, long alterSekunden) {
        if (date == null || date.isBlank()) return;
        long ms;
        try {
            ms = ZonedDateTime.parse(date.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception e) {
            return;
        }
        messen(ms + Math.max(0, alterSekunden) * 1000);
    }

    /** Eine Serverzeit (ms seit 1970) mit der Rechneruhr vergleichen. */
    static void messen(long serverMs) {
        // Unsinn abfangen: vor 2024 oder nach 2100 glauben wir keinem Server (wie Lizenzstand).
        if (serverMs < 1_704_067_200_000L || serverMs > 4_102_444_800_000L) return;
        laden();
        String meldung = null;
        boolean warnung = false;
        synchronized (Weltuhr.class) {
            long v = serverMs - rechner.millis();
            Long vorher = letzteMessung;
            letzteMessung = v;
            if (vorher == null || Math.abs(v - vorher) > EINIG_MS) return;   // erst eine zweite, passende Messung zaehlt
            gemessen = true;
            long neu = Math.abs(v) < SCHWELLE_MS ? 0 : v;
            if (Math.abs(neu - versatz) < EINIG_MS && (neu == 0) == (versatz == 0)) return;   // nichts Neues
            versatz = neu;
            speichern();
            if (neu != 0) {
                warnung = true;
                meldung = falschText();
            } else {
                meldung = Sprache.t("The Windows clock is right again -- the bot uses it as before.",
                        "Die Windows-Uhr stimmt wieder -- der Bot nimmt sie wie bisher.");
            }
        }
        // ausserhalb der Sperre ins Protokoll (das Protokoll fragt nie hier nach)
        if (warnung) Log.warn("Uhr", meldung);
        else Log.info("Uhr", meldung);
    }

    // ------------------------------------------------------------------
    //  Texte
    // ------------------------------------------------------------------

    /** "9:00 h" -- Betrag des Versatzes in Stunden:Minuten. */
    static String dauerText(long ms) {
        long min = Math.round(Math.abs(ms) / 60_000.0);
        return String.format("%d:%02d h", min / 60, min % 60);
    }

    private static String falschText() {
        long v = versatz;
        String wie = v < 0 ? Sprache.t("fast", "vor") : Sprache.t("slow", "nach");
        String zone = rechner.getZone().getId();
        return Sprache.t(
                "The Windows clock on this PC is " + dauerText(v) + " " + wie + " compared with internet time (Windows time zone: "
                        + zone + "). Usually the wrong time zone is set in Windows and the time was set by hand. The bot now "
                        + "uses internet time (" + Zeitzone.uhrText() + "). To fix it in Windows: Settings -> Time & language "
                        + "-> Date & time: choose your time zone and turn on 'Set time automatically'.",
                "Die Windows-Uhr dieses PCs geht gegenueber der Internetzeit " + dauerText(v) + " " + wie + " (Windows-Zeitzone: "
                        + zone + "). Meist ist in Windows die falsche Zeitzone eingestellt und die Uhrzeit von Hand nachgestellt. "
                        + "Der Bot rechnet ab jetzt mit der Internetzeit (" + Zeitzone.uhrText() + "). Beheben in Windows: "
                        + "Einstellungen -> Zeit und Sprache -> Datum und Uhrzeit: richtige Zeitzone waehlen und 'Uhrzeit "
                        + "automatisch festlegen' einschalten.");
    }

    /** Kurzer Zusatz fuer die Zeitzonen-Zeile ("" wenn die Rechneruhr stimmt). */
    public static String hinweis() {
        long v = versatzMs();
        if (v == 0) return "";
        return Sprache.t("Windows clock " + dauerText(v) + (v < 0 ? " fast" : " slow") + ", bot uses internet time",
                "Windows-Uhr geht " + dauerText(v) + (v < 0 ? " vor" : " nach") + ", Bot nimmt die Internetzeit");
    }

    /** Eine Zeile fuer info.txt im Fehlerbericht: Windows-Zeitzone, was Windows anzeigt, Abgleich mit dem Netz. */
    public static String beschreibung() {
        long v = versatzMs();
        String zone = rechner.getZone().getId();
        String zeigt = LocalDateTime.now(rechner).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"));
        String abgleich;
        if (v != 0) {
            abgleich = (v < 0 ? "fast by " : "slow by ") + dauerText(v) + " against internet time -> bot uses internet time";
        } else if (gemessen) {
            abgleich = "matches internet time";
        } else {
            abgleich = "not checked against internet time yet";
        }
        return "Windows zone " + zone + ", Windows shows " + zeigt + ", " + abgleich;
    }

    // ------------------------------------------------------------------
    //  Datei
    // ------------------------------------------------------------------

    private static void laden() {
        if (geladen) return;
        synchronized (Weltuhr.class) {
            if (geladen) return;
            geladen = true;
            try {
                if (!Files.exists(datei)) return;
                long v = 0;
                String zone = "";
                for (String z : Dateien.zeilen(datei)) {
                    String t = z.strip();
                    if (t.startsWith("versatz_ms=")) v = Long.parseLong(t.substring(11).strip());
                    else if (t.startsWith("windows_zone=")) zone = t.substring(13).strip();
                }
                // Neue Windows-Zeitzone: dann hat jemand die Uhr gerichtet, der alte Versatz gilt nicht mehr.
                if (zone.equals(rechner.getZone().getId()) && Math.abs(v) >= SCHWELLE_MS) versatz = v;
            } catch (Exception e) {
                // unlesbar: dann eben ohne Versatz, bis die erste Antwort aus dem Netz da ist
            }
        }
    }

    private static void speichern() {
        try {
            if (versatz == 0) {
                Files.deleteIfExists(datei);
                return;
            }
            Files.writeString(datei, "# Weltuhr (Konrad 19.11.): So weit geht die Windows-Uhr falsch (Weltzeit minus Windows-Zeit, ms).\n"
                    + "# Der Bot misst das selbst an den Antworten aus dem Netz -- nicht von Hand aendern.\n"
                    + "versatz_ms=" + versatz + "\n"
                    + "windows_zone=" + rechner.getZone().getId() + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            // nicht schlimm: dann wird nach dem naechsten Start neu gemessen
        }
    }

    /** Nur fuer Tests: alles vergessen und beim naechsten Zugriff neu lesen. */
    static synchronized void zuruecksetzen() {
        versatz = 0;
        geladen = false;
        gemessen = false;
        letzteMessung = null;
    }
}
