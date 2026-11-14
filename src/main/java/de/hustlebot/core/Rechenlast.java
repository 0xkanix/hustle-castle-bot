package de.hustlebot.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Welche Programme rechnen gerade am meisten? (Autor 29.10.: bei Hitze "rausfinden, wieso es so heiss wird".)
 *
 * Gemessen wird die verbrauchte Rechenzeit je Programm zwischen zwei Aufrufen. 100 % = ein voller Rechenkern; ein
 * Programm auf vier Kernen zeigt also bis zu 400 %. Programme mit gleichem Namen (z. B. vier python.exe) werden
 * zusammengezaehlt -- so sieht man "python.exe 310 %" statt vier kleiner Zeilen.
 */
public final class Rechenlast {

    /** Programm (Dateiname), Last in Prozent eines Kerns. */
    public record Programm(String name, int prozent) { }

    private final Map<Long, Long> vorher = new HashMap<>();   // pid -> Rechenzeit in ms
    private long vorherUm = 0;

    /**
     * Die {@code wieviele} Programme mit der meisten Rechenzeit seit dem letzten Aufruf. Beim ersten Aufruf leer (es
     * gibt noch keinen Vergleich).
     */
    public synchronized List<Programm> top(int wieviele) {
        long jetzt = System.currentTimeMillis();
        Map<Long, Long> neu = new HashMap<>();
        Map<String, Long> jeName = new HashMap<>();
        ProcessHandle.allProcesses().forEach(p -> {
            Optional<Duration> cpu = p.info().totalCpuDuration();
            if (cpu.isEmpty()) return;
            long ms = cpu.get().toMillis();
            neu.put(p.pid(), ms);
            Long alt = vorher.get(p.pid());
            if (alt == null || ms < alt) return;
            String name = p.info().command().map(Rechenlast::dateiname).orElse("pid " + p.pid());
            jeName.merge(name, ms - alt, Long::sum);
        });
        long dauer = jetzt - vorherUm;
        boolean ersterAufruf = vorherUm == 0;
        vorher.clear();
        vorher.putAll(neu);
        vorherUm = jetzt;
        if (ersterAufruf || dauer <= 0) return List.of();
        return auswerten(jeName, dauer, wieviele);
    }

    /** Aus Rechenzeit je Name (ms) und Messdauer die groessten Verbraucher in Prozent eines Kerns. */
    static List<Programm> auswerten(Map<String, Long> msJeName, long dauerMs, int wieviele) {
        List<Programm> l = new ArrayList<>();
        for (var e : msJeName.entrySet()) {
            int prozent = (int) Math.round(e.getValue() * 100.0 / dauerMs);
            if (prozent >= 5) l.add(new Programm(e.getKey(), prozent));
        }
        l.sort((a, b) -> Integer.compare(b.prozent(), a.prozent()));
        return l.size() > wieviele ? List.copyOf(l.subList(0, wieviele)) : List.copyOf(l);
    }

    /** Fuer die Meldung: "python.exe 310 %, HustleBot.exe 45 %" (leer: "unbekannt"). */
    public static String text(List<Programm> l) {
        if (l.isEmpty()) return Sprache.t("unknown", "unbekannt");
        StringBuilder sb = new StringBuilder();
        for (Programm p : l) sb.append(sb.length() == 0 ? "" : ", ").append(p.name()).append(' ').append(p.prozent()).append(" %");
        return sb.toString();
    }

    private static String dateiname(String pfad) {
        int i = Math.max(pfad.lastIndexOf('\\'), pfad.lastIndexOf('/'));
        return i >= 0 ? pfad.substring(i + 1) : pfad;
    }
}
