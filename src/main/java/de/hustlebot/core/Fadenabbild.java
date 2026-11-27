package de.hustlebot.core;

import java.util.Map;

/**
 * Wo steckt ein Faden gerade? -- als Text fuers Protokoll (VM 21.11.).
 *
 * Anlass: Die Hauptschleife in der HustleVM hing ab 17:38, gemeldet wurde nur "antwortet seit 181 s nicht". Bis jemand
 * nachsehen konnte, war die VM neu gestartet; die mitgelieferte Laufzeit hat kein jstack/jcmd. Ab jetzt schreibt der
 * Haenger-Waechter selbst ins Protokoll, in welcher Zeile die Hauptschleife steht.
 */
public final class Fadenabbild {

    private Fadenabbild() { }

    /** Alle lebenden Faeden mit genau diesem Namen, je Faden Kopfzeile und hoechstens maxZeilen Aufrufe; "" wenn keiner. */
    public static String von(String name, int maxZeilen) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (!name.equals(t.getName())) continue;
                sb.append(text(t.getName(), t.getState(), e.getValue(), maxZeilen));
            }
        } catch (RuntimeException e) {
            // SecurityException o. ae.: dann eben ohne Abbild -- der Waechter meldet trotzdem
        }
        return sb.toString();
    }

    static String text(String name, Thread.State zustand, StackTraceElement[] stapel, int maxZeilen) {
        StringBuilder sb = new StringBuilder();
        sb.append("Faden \"").append(name).append("\" (").append(zustand).append("):\n");
        int n = Math.min(stapel.length, Math.max(0, maxZeilen));
        for (int i = 0; i < n; i++) sb.append("    at ").append(stapel[i]).append('\n');
        if (stapel.length > n) sb.append("    ... (+").append(stapel.length - n).append(")\n");
        return sb.toString();
    }
}
