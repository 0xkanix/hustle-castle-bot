package de.hustlebot.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Sammelt alle Meldungen des Bots an einer Stelle: im Speicher fuer die
 * Weboberflaeche und zusaetzlich in einer Datei, damit nach einem Absturz
 * nichts verloren geht.
 */
public final class Log {

    public enum Level { DEBUG, INFO, ACTION, WARN, ERROR }

    public record Entry(String time, Level level, String source, String message) {}

    private static final int MAX_ENTRIES = 2000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Deque<Entry> BUFFER = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    private static Path logFile;
    private static boolean debugEnabled = false;

    private Log() {}

    public static void init(Path directory) {
        try {
            Files.createDirectories(directory);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            logFile = directory.resolve("bot_" + stamp + ".log");
            Files.writeString(logFile, Sprache.t("Hustle Castle Bot – Log\n", "Hustle Castle Bot – Protokoll\n"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("Log file could not be created: " + e.getMessage());
        }
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void debug(String source, String message) {
        if (debugEnabled && !wiederholung(source + "|" + message)) write(Level.DEBUG, source, message);
    }

    // 15.10. (Autor: "der Bot spamt den Log voll"): Wartezeilen wie "Erst ein Haken (links) ..." oder
    // "Warte auf Mitspieler, noch 299 Sekunden" kamen bei jedem Blick, drei Mal je Sekunde. Eine
    // Debug-Zeile, die (Zahlen nicht mitgezaehlt) unter den letzten drei schon stand, kommt erst nach
    // 30 s wieder. Wechselnde Ablaeufe (Kampf, Segen, Wegpunkt ...) bleiben vollstaendig.
    private static final long WIEDERHOLUNG_MS = 30_000;
    private static final java.util.ArrayDeque<String> LETZTE_DEBUG = new java.util.ArrayDeque<>();
    private static final java.util.Map<String, Long> DEBUG_ZEIT = new java.util.HashMap<>();

    private static boolean wiederholung(String zeile) {
        String schluessel = zeile.replaceAll("\\d+", "#");
        long jetzt = System.currentTimeMillis();
        synchronized (LETZTE_DEBUG) {
            Long zuletzt = DEBUG_ZEIT.get(schluessel);
            if (zuletzt != null && LETZTE_DEBUG.contains(schluessel) && jetzt - zuletzt < WIEDERHOLUNG_MS) return true;
            DEBUG_ZEIT.put(schluessel, jetzt);
            LETZTE_DEBUG.remove(schluessel);
            LETZTE_DEBUG.addLast(schluessel);
            while (LETZTE_DEBUG.size() > 3) DEBUG_ZEIT.remove(LETZTE_DEBUG.removeFirst());
            return false;
        }
    }

    public static void info(String source, String message)   { write(Level.INFO, source, message); }
    public static void action(String source, String message) { write(Level.ACTION, source, message); }
    public static void warn(String source, String message)   {
        letzteWarnung = message;
        letzteWarnungVon = source;
        letzteWarnungUm = System.currentTimeMillis();
        write(Level.WARN, source, message);
    }

    /** Die letzte Warnung, wer sie schrieb und wann (18.11.: Grund, wenn ein Modus vorzeitig "fertig" meldet). */
    private static volatile String letzteWarnung, letzteWarnungVon;
    private static volatile long letzteWarnungUm;

    /** Der letzte Warntext, wenn er hoechstens so alt ist und von keiner der Quellen {@code ausser} kommt; sonst null. */
    public static String letzteWarnung(long hoechstensMs, String... ausser) {
        String w = letzteWarnung;
        if (w == null || System.currentTimeMillis() - letzteWarnungUm > hoechstensMs) return null;
        String von = letzteWarnungVon;
        if (ausser != null) {
            for (String a : ausser) {
                if (a != null && a.equals(von)) return null;
            }
        }
        return w;
    }
    public static void error(String source, String message)  {
        letzterFehler = message;
        letzterFehlerUm = System.currentTimeMillis();
        write(Level.ERROR, source, message);
    }

    /** Der letzte Fehler und wann er kam -- damit das Bot-Fenster beim Abbruch den Grund zeigt (Autor 23.10.). */
    private static volatile String letzterFehler;
    private static volatile long letzterFehlerUm;

    /** Der letzte Fehlertext, wenn er hoechstens so alt ist; sonst null. */
    public static String letzterFehler(long hoechstensMs) {
        String f = letzterFehler;
        return f != null && System.currentTimeMillis() - letzterFehlerUm <= hoechstensMs ? f : null;
    }

    /**
     * Wer bei einem Alarm zusaetzlich benachrichtigt werden will.
     *
     * Gedacht fuer die Discord-Anbindung: Sie traegt sich hier einmal ein, und
     * ab dann landet jeder Alarm auch im Handy. Traegt sich niemand ein, bleibt
     * es beim Protokolleintrag -- der Bot laeuft davon voellig unbeeindruckt.
     */
    private static volatile java.util.function.BiConsumer<String, String> melder;

    /** Traegt den Empfaenger fuer Alarme ein. null schaltet ihn wieder ab. */
    public static void setMelder(java.util.function.BiConsumer<String, String> empfaenger) {
        melder = empfaenger;
    }

    /** Empfaenger fuer kurze Hinweise, die nach Discord sollen, aber kein Alarm sind (aus dem Hauptbot, 10.10.). */
    private static volatile java.util.function.BiConsumer<String, String> hinweisMelder;

    public static void setHinweisMelder(java.util.function.BiConsumer<String, String> empfaenger) {
        hinweisMelder = empfaenger;
    }

    /** Ein kurzer Hinweis ins Protokoll und -- wenn jemand zuhoert -- nach Discord (Titan-Grabung: alle 1000 Hacken). */
    public static void hinweis(String titel, String text) {
        info("Hinweis", titel + ": " + text);
        java.util.function.BiConsumer<String, String> ziel = hinweisMelder;
        if (ziel == null) return;
        try {
            ziel.accept(titel, text);
        } catch (Exception e) {
            write(Level.WARN, "Log", Sprache.t("Notice could not be sent: ", "Hinweis liess sich nicht verschicken: ") + e.getMessage());
        }
    }

    /**
     * Eine Warnung, die jemanden erreichen soll.
     *
     * Der Unterschied zu warn(): Das hier ist fuer die wenigen Faelle gedacht,
     * bei denen es sich lohnt, nachts geweckt zu werden -- etwa wenn der
     * Rechner heiss wird. Ein misslungener Versand darf den Bot niemals
     * aufhalten, deshalb wird jeder Fehler dabei geschluckt.
     */
    public static void alarm(String source, String message) {
        warn(source, message);
        java.util.function.BiConsumer<String, String> ziel = melder;
        if (ziel == null) return;
        try {
            ziel.accept(source, message);
        } catch (Exception e) {
            write(Level.WARN, "Log", Sprache.t("Alarm could not be sent: ", "Alarm liess sich nicht verschicken: ") + e.getMessage());
        }
    }

    /** Nimmt bewusst Throwable, damit auch Ladefehler nativer Bibliotheken hier ankommen. */
    public static void error(String source, String message, Throwable t) {
        write(Level.ERROR, source, message + " – " + t.getClass().getSimpleName() + ": " + t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().startsWith("de.autor")) {
                write(Level.ERROR, source, Sprache.t("    at ", "    bei ") + el);
            }
        }
    }

    private static void write(Level level, String source, String message) {
        // 11.10.: Die Quelle ("Motor", "Verlies") steht in der Sprache des Spielers -- einmal hier,
        // statt an jedem Aufruf. Die Zeile selbst kommt schon zweisprachig an (Sprache.t).
        source = Sprache.quelle(source);
        Entry entry = new Entry(LocalDateTime.now().format(TIME_FMT), level, source, message);
        synchronized (LOCK) {
            BUFFER.addLast(entry);
            while (BUFFER.size() > MAX_ENTRIES) BUFFER.removeFirst();
        }
        String line = entry.time() + "  [" + level + "] " + source + ": " + message;
        System.out.println(line);
        if (logFile != null) {
            try {
                Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Ein fehlgeschlagener Dateischreibvorgang darf den Bot nicht stoppen.
            }
        }
    }

    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static List<Entry> since(int index) {
        synchronized (LOCK) {
            List<Entry> all = new ArrayList<>(BUFFER);
            if (index >= all.size()) return List.of();
            return new ArrayList<>(all.subList(Math.max(0, index), all.size()));
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return BUFFER.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
    }

    public static Path currentLogFile() {
        return logFile;
    }
}
