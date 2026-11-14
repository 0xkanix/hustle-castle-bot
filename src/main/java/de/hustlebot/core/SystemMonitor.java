package de.hustlebot.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * Misst, wie es dem Rechner geht: Auslastung, Arbeitsspeicher, Festplatte und
 * -- wenn moeglich -- die Temperatur.
 *
 * Wozu das gut ist: Der Bot laeuft oft nachts, ohne dass jemand danebensitzt.
 * Dann soll er selbst merken, wenn der Rechner heiss laeuft oder etwas
 * vollzulaufen droht, und rechtzeitig langsamer machen oder aufhoeren.
 *
 * Alles hier ist bewusst genuegsam: Auslastung und Speicher kommen aus der
 * Java-Laufzeit und kosten praktisch nichts. Nur die Temperatur braucht einen
 * Windows-Aufruf, und der laeuft deshalb hoechstens alle paar Minuten.
 */
public final class SystemMonitor {

    /** Ein Messwert-Satz. Alles, was nicht ermittelt werden konnte, ist -1. */
    public record Reading(
            double cpuPercent,      // Auslastung des ganzen Rechners, 0 bis 100
            double botCpuPercent,   // davon der Bot selbst
            long ramUsedMb,
            long ramTotalMb,
            long diskFreeGb,
            long logsMb,
            double tempCelsius,     // -1, wenn nicht messbar
            long javaHeapMb,
            long javaHeapMaxMb,
            Sensors sensors) {      // nie null, aber moeglicherweise leer

        public double ramPercent() {
            return ramTotalMb > 0 ? (ramUsedMb * 100.0 / ramTotalMb) : -1;
        }

        public boolean hasTemperature() {
            return tempCelsius > 0;
        }
    }

    /**
     * Die zusaetzlichen Werte, die es nur mit LibreHardwareMonitor gibt.
     *
     * Windows selbst gibt davon nichts heraus. Laeuft das Programm mit, meldet
     * es seine Sensoren an Windows, und der Bot findet sie von selbst -- ohne
     * dass hier etwas eingestellt werden muesste.
     *
     * Alles, was fehlt, ist -1.
     */
    public record Sensors(
            double cpuTemp,
            double gpuTemp,
            double mainboardTemp,
            double cpuClockMhz,
            double cpuPowerWatt,
            double gpuPowerWatt,
            int fanRpm,
            int fanCount,
            double gpuLoad,          // Auslastung der Grafikkarte in Prozent
            double diskUsedPercent) {

        public static Sensors empty() {
            return new Sensors(-1, -1, -1, -1, -1, -1, -1, 0, -1, -1);
        }

        /** True, wenn ueberhaupt etwas gemessen werden konnte. */
        public boolean any() {
            return cpuTemp > 0 || gpuTemp > 0 || cpuClockMhz > 0
                || cpuPowerWatt > 0 || fanRpm > 0;
        }

        /** Was der Rechner insgesamt gerade zieht, soweit bekannt. */
        public double totalWatt() {
            double sum = 0;
            if (cpuPowerWatt > 0) sum += cpuPowerWatt;
            if (gpuPowerWatt > 0) sum += gpuPowerWatt;
            return sum > 0 ? sum : -1;
        }
    }

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();

    static {
        // Einmal ins Leere messen, damit eine Vergleichsbasis da ist.
        //
        // Die Auslastung wird als Mittel SEIT DEM LETZTEN ABRUF berechnet --
        // beim allerersten Abruf gibt es keinen letzten, und Java meldet 0.
        // Im Bericht sah das so aus, als wuerde der Rechner schlafen, obwohl
        // der Bot gerade lief. Dieser Aufruf verbrennt genau diese eine
        // wertlose Messung, noch bevor jemand hinsieht.
        OS.getCpuLoad();
        OS.getProcessCpuLoad();
    }

    /**
     * Die Sensoren werden alle 15 Sekunden neu geholt. Die Anfrage geht an den
     * eigenen Rechner und kostet Millisekunden. Dazwischen gilt der letzte Wert.
     */
    private static final long TEMP_INTERVAL_MS = 15_000;
    private static volatile long lastSensorsAt = 0;
    private static volatile Sensors lastSensors = Sensors.empty();
    private static volatile boolean monitorMissing = false;
    private static volatile boolean monitorReported = false;

    /**
     * Wo LibreHardwareMonitor seine Werte herausgibt.
     *
     * Der Standard passt fuer den Normalfall: das Programm laeuft auf
     * demselben Rechner wie der Bot. Laeuft es woanders, wird die Adresse in
     * config.properties unter monitor.url eingetragen.
     */
    private static volatile String monitorUrl = "http://localhost:8085/data.json";

    /**
     * Traegt die Adresse aus config.properties ein. Wird einmal beim Start
     * aufgerufen. Danach wird von vorne gesucht, damit ein Wechsel der Adresse
     * nicht an einer alten Fehlmeldung haengen bleibt.
     */
    public static void configure(String url) {
        if (url != null && !url.isBlank()) monitorUrl = url.trim();
        monitorReported = false;
        monitorMissing = false;
        lastSensorsAt = 0;
    }

    /** Name der geplanten Aufgabe, die LibreHardwareMonitor startet. */
    private static final String MONITOR_TASK = "LibreHardwareMonitor";

    /** Damit nicht bei jedem Fehlversuch neu gestartet wird. */
    private static volatile long letzterStartversuch = 0;

    /**
     * Startet LibreHardwareMonitor, falls er nicht laeuft.
     *
     * Der Trick sitzt in der geplanten Aufgabe: Sie ist mit hoechsten Rechten
     * angemeldet, und die haengen an der Aufgabe, nicht am Aufrufer. Der Bot
     * darf sie also ausloesen, ohne dass Windows nachfragt -- was er selbst
     * nicht duerfte, denn fuer die Sensoren braucht das Programm
     * Administratorrechte.
     *
     * Ohne das musste Autor nach jedem Neustart daran denken. Und wenn er es
     * vergass, standen im Bericht wochenlang nur Striche, ohne dass jemand
     * gemerkt haette, warum.
     *
     * Hoechstens alle fuenf Minuten ein Versuch. Fehlt die Aufgabe oder ist
     * das Programm gar nicht installiert, laeuft der Bot ganz normal weiter --
     * dann bleiben eben Temperatur und Luefter leer.
     */
    public static void ensureRunning() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzterStartversuch < 5 * 60_000) return;
        letzterStartversuch = jetzt;

        if (laeuftSchon()) return;

        try {
            Process p = new ProcessBuilder("schtasks", "/run", "/tn", MONITOR_TASK)
                    .redirectErrorStream(true)
                    .start();
            boolean fertig = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!fertig) { p.destroyForcibly(); return; }

            if (p.exitValue() == 0) {
                Log.info("Rechner", Sprache.t("LibreHardwareMonitor was not running and has been " + "started. Temperature and fans are back shortly.",
                        "LibreHardwareMonitor war nicht gestartet und wurde "
                        + "nachgeholt. Temperatur und Luefter sind gleich wieder da."));
            } else {
                Log.debug("Rechner", "LibreHardwareMonitor liess sich nicht starten "
                        + "(Aufgabe \"" + MONITOR_TASK + "\" fehlt?). Der Bot laeuft ohne "
                        + "Sensorwerte weiter.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.debug("Rechner", "LibreHardwareMonitor liess sich nicht starten: "
                    + e.getMessage());
        }
    }

    /** Laeuft das Programm schon? Geprueft wird an der Windows-Prozessliste. */
    private static boolean laeuftSchon() {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI",
                    "IMAGENAME eq LibreHardwareMonitor.exe", "/NH")
                    .redirectErrorStream(true)
                    .start();
            String antwort;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(),
                            java.nio.charset.Charset.defaultCharset()))) {
                StringBuilder sb = new StringBuilder();
                String zeile;
                while ((zeile = r.readLine()) != null) sb.append(zeile);
                antwort = sb.toString();
            }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return antwort.contains("LibreHardwareMonitor.exe");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;   // im Zweifel nichts anfassen
        } catch (Exception e) {
            return true;
        }
    }

    private SystemMonitor() {
    }

    /**
     * Der zuletzt gemessene Satz. Wird zwei Sekunden lang wiederverwendet.
     *
     * Das ist keine Sparmassnahme, sondern noetig: Die Auslastung wird als
     * Mittel SEIT DEM LETZTEN ABRUF berechnet. Fragen zwei Stellen kurz
     * hintereinander -- der Schutzwaechter alle 15 Sekunden und der Bericht
     * fuer Discord --, bekommt die zweite den Zeitraum von wenigen
     * Millisekunden und meldet 0 Prozent. Genau das stand im Bericht.
     */
    private static volatile Reading cached;
    private static volatile long cachedAt = 0;
    private static final long CACHE_MS = 2000;

    /** Die zuletzt brauchbare Auslastung, falls eine Messung 0 liefert. */
    private static volatile double lastGoodCpu = -1;

    /** Wann die Auslastung zuletzt abgefragt wurde (auch der Leer-Abruf beim Laden). */
    private static volatile long letzterAbruf = System.currentTimeMillis();

    /** Nimmt einen frischen Satz Messwerte auf. */
    public static synchronized Reading read() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) return cached;

        double cpu = OS.getCpuLoad() * 100.0;
        double botCpu = OS.getProcessCpuLoad() * 100.0;
        // 15.10.: Beim allerersten Abruf direkt nach dem Laden dieser Klasse
        // lag die Messung nur Millisekunden hinter dem Leer-Abruf oben und
        // meldete 100 Prozent. Die Freunde-Fassung (ohne Bedienfeld, das
        // vorher schon misst) hat sich dadurch beim ersten Start eine Minute
        // lang selbst gebremst -- die Kathedrale lief spuerbar langsamer.
        // Zu kurzer Zeitraum = keine Aussage.
        boolean zuKurz = now - letzterAbruf < 1000;
        letzterAbruf = now;
        if (zuKurz) cpu = 0;

        // Eine Messung ueber einen zu kurzen Zeitraum liefert 0 oder -1.
        // Dann lieber den letzten brauchbaren Wert zeigen als eine Null, die
        // aussieht, als wuerde der Rechner schlafen.
        if (cpu > 0) {
            lastGoodCpu = cpu;
        } else if (lastGoodCpu > 0) {
            cpu = lastGoodCpu;
        }

        long ramTotal = OS.getTotalMemorySize() / (1024 * 1024);
        long ramFree = OS.getFreeMemorySize() / (1024 * 1024);

        Runtime rt = Runtime.getRuntime();
        long heap = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);

        Sensors sensors = sensors();

        cached = new Reading(
                round(cpu), round(botCpu),
                ramTotal - ramFree, ramTotal,
                new File(".").getUsableSpace() / (1024L * 1024 * 1024),
                folderSizeMb(new File("logs")),
                sensors.cpuTemp() > 0 ? sensors.cpuTemp() : windowsTemperature(),
                heap, heapMax,
                sensors);
        cachedAt = now;
        return cached;
    }

    private static double round(double value) {
        if (value < 0) return -1;
        return Math.round(value * 10) / 10.0;
    }

    /**
     * Holt die Sensorwerte von LibreHardwareMonitor.
     *
     * Der Weg fuehrt ueber dessen eingebauten Web-Server: eine kurze Anfrage
     * im eigenen Rechner, Antwort in Millisekunden. Frueher lief das ueber
     * Windows, und das hatte zwei Nachteile -- jede Abfrage brauchte gut eine
     * Sekunde, und auf AMD-Mainboards kam ueberhaupt nichts zurueck, weil
     * Windows die Sensoren dort gar nicht kennt.
     *
     * Damit es geht, muss im Programm einmal ein Haken gesetzt werden:
     * Options, dann Remote Web Server, dann Run. Ohne den ist der Server aus.
     *
     * Klappt es nicht, wird einmal darauf hingewiesen und danach Ruhe gegeben.
     * Der Bot laeuft ohne die Werte ganz normal weiter -- ueberwacht wird er
     * auch dann, denn die Auslastung ist die Ursache und die Waerme nur die
     * Folge.
     */
    private static Sensors sensors() {
        if (ServerSparsam.an()) return lastSensors;   // Autor 30.10.: auf dem Server antwortet ohnehin kein Hardware-Monitor
        long now = System.currentTimeMillis();
        if (now - lastSensorsAt < TEMP_INTERVAL_MS) return lastSensors;
        lastSensorsAt = now;

        Sensors fresh = readWebServer();

        if (fresh != null && fresh.any()) {
            lastSensors = fresh;
            Verlauf.note(fresh);
            monitorMissing = false;
            if (!monitorReported) {
                monitorReported = true;
                Log.info("Rechner", de.hustlebot.core.Sprache.t("Hardware monitor found at " + monitorUrl + ". Temperature, fans, clock and power draw are now part of the report.", "Hardware-Monitor gefunden unter " + monitorUrl
                        + ". Temperatur, Luefter, Takt und Leistungsaufnahme stehen ab jetzt "
                        + "mit im Bericht."));
            }
            return lastSensors;
        }

        if (!monitorReported) {
            monitorReported = true;
            Log.info("Rechner", Sprache.t("No hardware monitor answers at " + monitorUrl + ". " + "Temperature, fans, clock and power draw therefore stay empty. " + "To see them, start LibreHardwareMonitor and tick once " + "Options, Remote Web Server, Run. Monitoring works " + "without it too: the load is the cause, the heat only the effect.",
                    "Unter " + monitorUrl + " antwortet kein Hardware-Monitor. "
                    + "Temperatur, Luefter, Takt und Leistungsaufnahme bleiben deshalb leer. "
                    + "Wer sie sehen will, startet LibreHardwareMonitor und setzt dort einmal "
                    + "den Haken bei Options, Remote Web Server, Run. Ueberwacht wird auch "
                    + "ohne: die Auslastung ist die Ursache, die Waerme nur die Folge."));
        }
        monitorMissing = true;
        return Sensors.empty();
    }

    /**
     * Die Ueberschriften, unter denen LibreHardwareMonitor seine Sensoren
     * einsortiert. Alles andere auf dieser Ebene ist ein Bauteil.
     */
    private static final java.util.Set<String> GRUPPEN = java.util.Set.of(
            "Voltages", "Temperatures", "Fans", "Powers", "Clocks", "Load",
            "Data", "Controls", "Level", "Factor", "Throughput", "Currents");

    /** Woran ein Prozessor bzw. eine Grafikkarte im Namen zu erkennen ist. */
    private static final String CPU = "(?i).*(ryzen|intel|core i|cpu|athlon|xeon).*";
    private static final String GPU = "(?i).*(radeon|geforce|nvidia|arc|gpu).*";

    /**
     * Nur die eingebaute Grafikkarte, ohne die im Prozessor. Die heisst bei
     * AMD "Radeon(TM) Graphics" und liefert waehrend des Spiels ganz andere
     * Zahlen als die Karte, auf der es tatsaechlich laeuft.
     */
    private static final String GPU_EIGEN =
            "(?i).*(geforce|radeon rx|radeon pro|arc a|quadro).*";

    /** Der Ueberwachungschip auf dem Mainboard. */
    private static final String CHIP = "(?i).*(ite it|nuvoton|fintek|winbond|nct).*";

    /** Ein einzelner abgelesener Wert samt Herkunft. */
    private record Wert(String bauteil, String gruppe, String name, double zahl) {}

    /**
     * Fuer die Abfrage von LibreHardwareMonitor wiederverwendet, statt bei
     * jedem Aufruf neu angelegt zu werden.
     *
     * Frueher stand hier bei jedem Aufruf ein frisches HttpClient.newBuilder()
     * -- alle 15 Sekunden, die ganze Nacht. Jeder neue HttpClient startet im
     * Hintergrund einen eigenen Verwaltungsfaden; bei Tagen im Dauerbetrieb
     * waeren das mehrere Zehntausend. Ein einziger, wiederverwendeter Client
     * tut genau dasselbe ohne dieses staendige Neuanlegen.
     */
    private static final java.net.http.HttpClient MONITOR_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    /** Fragt den Web-Server ab. Gibt null zurueck, wenn er nicht antwortet. */
    private static Sensors readWebServer() {
        try {
            java.net.http.HttpClient client = MONITOR_HTTP;
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(monitorUrl))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());

            java.util.List<Wert> werte = new java.util.ArrayList<>();
            sammle(root, "", "", werte);
            if (werte.isEmpty()) return null;

            // Die Namen unterscheiden sich je nach Hersteller, deshalb steht
            // hinter jedem Wert eine Liste von Schreibweisen. Genommen wird
            // die erste, die ueberhaupt etwas findet.
            return new Sensors(
                    hole(werte, "Temperatures", CPU, "Core \\(Tctl.*", "CPU Package",
                            "Core Average", "CPU Core.*"),

                    // Erst die eingebaute Grafikkarte, dann irgendeine. In
                    // diesem Rechner stecken zwei: die RTX 4070 und die im
                    // Prozessor. Ohne diese Reihenfolge kaeme die Temperatur
                    // von der einen und die Auslastung von der anderen.
                    erstesVon(
                            hole(werte, "Temperatures", GPU_EIGEN, "GPU Core", "GPU Hot ?Spot.*"),
                            hole(werte, "Temperatures", GPU, "GPU Core", "GPU Hot ?Spot.*",
                                    "GPU.*")),

                    // Mainboards beschriften ihre Sensoren oft gar nicht --
                    // dann heissen sie beim Ueberwachungschip schlicht
                    // "Temperature #1" bis "#6".
                    erstesVon(
                            hole(werte, "Temperatures", null, "Motherboard", "Mainboard",
                                    "System"),
                            hole(werte, "Temperatures", CHIP, "Temperature #\\d+")),

                    hole(werte, "Clocks", CPU, "Core #\\d+", "CPU Core.*"),
                    hole(werte, "Powers", CPU, "Package", "CPU Package", "CPU PPT"),
                    erstesVon(
                            hole(werte, "Powers", GPU_EIGEN, "GPU Package", "GPU Total.*",
                                    "Total Power"),
                            hole(werte, "Powers", GPU, "GPU Package", "GPU Total.*",
                                    "Total Power")),
                    (int) hole(werte, "Fans", null, ".*"),
                    zaehleLuefter(werte),
                    erstesVon(
                            hole(werte, "Load", GPU_EIGEN, "GPU Core", "D3D 3D"),
                            hole(werte, "Load", GPU, "GPU Core", "D3D 3D")),
                    hole(werte, "Load", null, "Used Space"));

        } catch (Exception e) {
            Log.debug("Rechner", "Hardware-Monitor nicht erreichbar: " + e.getMessage());
            return null;
        }
    }

    /**
     * Geht den Baum durch, den der Web-Server liefert, und sammelt unten alle
     * Blaetter ein. Der Aufbau ist: Rechner, Bauteil, Ueberschrift, Wert. Bei
     * Mainboards steckt manchmal noch ein Bauteil im Bauteil, deshalb wird
     * nicht nach Tiefe unterschieden, sondern nach der Ueberschrift.
     */
    private static void sammle(com.fasterxml.jackson.databind.JsonNode knoten,
                               String bauteil, String gruppe, java.util.List<Wert> raus) {
        String text = knoten.path("Text").asText("");
        com.fasterxml.jackson.databind.JsonNode kinder = knoten.path("Children");

        if (kinder.isArray() && kinder.size() > 0) {
            boolean istGruppe = GRUPPEN.contains(text);
            for (com.fasterxml.jackson.databind.JsonNode kind : kinder) {
                if (istGruppe) sammle(kind, bauteil, text, raus);
                else           sammle(kind, text, "", raus);
            }
            return;
        }

        String roh = knoten.path("Value").asText("");
        if (roh.isBlank() || gruppe.isEmpty()) return;
        raus.add(new Wert(bauteil, gruppe, text, zahlAus(roh)));
    }

    /**
     * Sucht den hoechsten Wert einer Gruppe. Probiert die Schreibweisen der
     * Reihe nach durch und nimmt die erste, die etwas findet.
     *
     * Der hoechste und nicht der erste: Bei acht Prozessorkernen interessiert
     * der waermste, bei fuenf Luefterplaetzen der schnellste.
     */
    private static double hole(java.util.List<Wert> werte, String gruppe, String bauteilMuster,
                               String... namensMuster) {
        for (String muster : namensMuster) {
            double beste = -1;
            for (Wert w : werte) {
                if (!w.gruppe().equals(gruppe)) continue;
                if (bauteilMuster != null && !w.bauteil().matches(bauteilMuster)) continue;
                if (!w.name().matches(muster)) continue;
                if (w.zahl() > beste) beste = w.zahl();
            }
            if (beste > 0) return beste;
        }
        return -1;
    }

    /**
     * Nimmt den ersten Wert, der ueberhaupt einer ist.
     *
     * Fuer Faelle, in denen es einen bevorzugten und einen Notnagel gibt --
     * etwa die eingebaute Grafikkarte vor irgendeiner.
     */
    private static double erstesVon(double... kandidaten) {
        for (double k : kandidaten) if (k > 0) return k;
        return -1;
    }

    /** Wie viele Luefter sich ueberhaupt drehen. Stillstehende zaehlen nicht. */
    private static int zaehleLuefter(java.util.List<Wert> werte) {
        int anzahl = 0;
        for (Wert w : werte) {
            if (w.gruppe().equals("Fans") && w.zahl() > 0) anzahl++;
        }
        return anzahl;
    }

    /**
     * Aus "54,6 Grad" wird 54.6, aus "5240,0 MHz" wird 5240.
     *
     * Der Web-Server liefert die Werte so, wie sie im Fenster stehen -- mit
     * Einheit dahinter und deutschem Komma. Gelesen wird von vorne, bis nach
     * der Zahl etwas anderes kommt.
     */
    private static double zahlAus(String text) {
        StringBuilder ziffern = new StringBuilder();
        for (char c : text.trim().toCharArray()) {
            if (Character.isDigit(c) || c == '.' || c == '-') ziffern.append(c);
            else if (c == ',') ziffern.append('.');
            else if (ziffern.length() > 0) break;
        }
        return num(ziffern.toString());
    }


    private static double num(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Die Temperatur direkt von Windows. Braucht Administratorrechte und
     * liefert auf vielen Mainboards ohnehin nur den Chipsatz -- deshalb nur
     * der Rueckfall, wenn kein Hardware-Monitor laeuft.
     */
    private static double windowsTemperature() {
        if (!monitorMissing) return -1;   // dann kam der Wert schon vom Monitor
        return -1;                        // ohne Adminrechte aussichtslos, spart einen Aufruf
    }

    /** Fuehrt ein PowerShell-Stueck aus und liest die erste Zeile zurueck. */
    private static String runPowerShell(String script) {
        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), Charset.defaultCharset()))) {
                output = reader.readLine();
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return output;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Wie viel Platz ein Ordner belegt, in Megabyte. */
    private static long folderSizeMb(File folder) {
        if (!folder.isDirectory()) return 0;
        File[] files = folder.listFiles();
        if (files == null) return 0;

        long bytes = 0;
        for (File file : files) {
            if (file.isFile()) bytes += file.length();
        }
        return bytes / (1024 * 1024);
    }

    /**
     * Loescht Protokolle und Berichte, die aelter sind als die angegebene Zahl
     * Tage. Ohne das waechst der Ordner Nacht fuer Nacht weiter.
     */
    public static int cleanOldLogs(int keepDays) {
        File folder = new File("logs");
        if (!folder.isDirectory()) return 0;

        File[] files = folder.listFiles();
        if (files == null) return 0;

        long cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000;
        int removed = 0;

        for (File file : files) {
            if (!file.isFile() || file.lastModified() >= cutoff) continue;
            // Die Datei, in die gerade geschrieben wird, bleibt in jedem Fall.
            if (file.getName().endsWith(".log") && file.lastModified()
                    > System.currentTimeMillis() - 60_000) continue;
            if (file.delete()) removed++;
        }

        if (removed > 0) {
            Log.info("Rechner", Sprache.t(removed + " old log files deleted " + "(older than " + keepDays + " days).",
                    removed + " alte Protokolldateien geloescht "
                    + "(aelter als " + keepDays + " Tage)."));
        }
        return removed;
    }
}
