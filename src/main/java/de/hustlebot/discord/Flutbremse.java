package de.hustlebot.discord;

import de.hustlebot.core.Sprache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Flut-Bremse (Sicherheit, 16.10.): Foto- und Berichts-Befehle hoechstens
 * einmal je 5 Sekunden je Nutzer.
 *
 * !screenshot, !live, !log und !watch erzeugen Bilder oder Dateien und
 * schicken sie nach Discord. Wer sie im Sekundentakt tippt, legt den Bot
 * lahm und laesst Discord den Bot sperren (zu viele Nachrichten). Deshalb:
 * je Nutzer-ID der Zeitpunkt des letzten solchen Befehls; kommt der naechste
 * zu schnell, gibt es nur ein kurzes "bitte warten".
 *
 * Alles nur im Speicher -- nach einem Neustart ist die Bremse leer.
 */
public final class Flutbremse {

    /** Mindestabstand zwischen zwei Foto-/Berichts-Befehlen desselben Nutzers. */
    public static final long ABSTAND_MS = 5_000;

    /** Die gebremsten Befehle -- englische Grundform aus {@link Befehlsnamen}. */
    private static final Set<String> GEBREMST = Set.of("!screenshot", "!live", "!log", "!watch");

    /** Nutzer-ID -> Zeitpunkt des letzten erlaubten Befehls. */
    private final Map<String, Long> zuletzt = new HashMap<>();
    private final LongSupplier uhr;

    public Flutbremse() {
        this(System::currentTimeMillis);
    }

    /** Mit eigener Uhr -- fuer die Tests. */
    Flutbremse(LongSupplier uhr) {
        this.uhr = uhr;
    }

    /** Ist das ein Befehl, den die Bremse betrifft? Jede Schreibweise ("!bild", "!bericht") zaehlt. */
    public static boolean gebremst(String verb) {
        if (verb == null || verb.isBlank()) return false;
        String v = verb.trim().toLowerCase(java.util.Locale.ROOT);
        return GEBREMST.contains(v) || GEBREMST.contains(Befehlsnamen.kanonisch(v));
    }

    /**
     * Darf dieser Nutzer jetzt einen Foto-/Berichts-Befehl ausfuehren? Ja heisst:
     * der Zeitpunkt ist gemerkt. Der eigene Rechner wird nie gebremst.
     */
    public synchronized boolean erlaubt(String nutzerId) {
        if (nutzerId == null || nutzerId.isBlank() || Rechte.EIGENER_RECHNER.equals(nutzerId)) return true;
        long jetzt = uhr.getAsLong();
        Long letzter = zuletzt.get(nutzerId);
        if (letzter != null && jetzt - letzter < ABSTAND_MS) return false;
        zuletzt.put(nutzerId, jetzt);
        aufraeumen(jetzt);
        return true;
    }

    /** Wie lange der Nutzer noch warten muss, in Sekunden (aufgerundet, mindestens 1). */
    public synchronized long restSekunden(String nutzerId) {
        Long letzter = nutzerId == null ? null : zuletzt.get(nutzerId);
        if (letzter == null) return 0;
        long rest = ABSTAND_MS - (uhr.getAsLong() - letzter);
        return rest <= 0 ? 0 : Math.max(1, (rest + 999) / 1000);
    }

    public static String wartetext(long sekunden) {
        String s = sekunden <= 0 ? "a moment" : sekunden + " s";
        String d = sekunden <= 0 ? "einen Moment" : sekunden + " s";
        return Sprache.t("Please wait " + s + " -- pictures and reports at most once every 5 seconds.",
                         "Bitte " + d + " warten -- Bilder und Berichte hoechstens alle 5 Sekunden.");
    }

    /** Alte Eintraege loswerden, damit die Tabelle nicht waechst. */
    private void aufraeumen(long jetzt) {
        if (zuletzt.size() < 200) return;
        Iterator<Map.Entry<String, Long>> it = zuletzt.entrySet().iterator();
        while (it.hasNext()) {
            if (jetzt - it.next().getValue() > 10 * ABSTAND_MS) it.remove();
        }
    }
}
