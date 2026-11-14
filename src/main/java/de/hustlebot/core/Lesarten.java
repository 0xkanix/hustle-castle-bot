package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Autor 28.10.: Woerterbuch "so hat die Texterkennung den Namen gelesen -> so heisst der Spieler wirklich".
 *
 * PaddleOCR liest manche Namen immer wieder gleich falsch ("JtWXED mUREN" statt "JtNXED", "KoponeBa" statt
 * "Королева"). Wurde der Spieler einmal SICHER erkannt (Listenbild, Namensbild ...), merkt sich der Bot hier die
 * Lesart. Beim naechsten Mal genuegt dieselbe Lesart, um den richtigen Namen samt Clan und Kraft zu finden.
 *
 * Datei arena-lesarten.txt im Bot-Ordner (UTF-8), eine Zeile je Lesart:
 *   gelesen | kanonisch | clan | kraft | anzahl
 * "anzahl" = wie oft bestaetigt. Gilt erst ab 2 Bestaetigungen -- und nur, wenn kein anderer Name mit derselben
 * Lesart genauso oft bestaetigt ist. Verglichen wird die Lesart in der Form von NamensVergleich.form() (nur Buchstaben
 * und Ziffern, klein), aber EXAKT: aehnliche Lesarten zaehlen nicht, sonst wuerde aus "Lord" ploetzlich "Lore".
 *
 * Schalterfrei: merken() und kanonisch() ruft nur ArenaMode auf, und nur mit ArenaSchalter.an() -- bei den Freunden
 * entsteht die Datei gar nicht. Wann eine Erkennung SICHER ist, entscheidet der Aufrufer.
 * Fehlertolerant (kaputte Zeilen werden uebersprungen, Lese-/Schreibfehler nur ins Protokoll) und thread-sicher.
 */
public final class Lesarten {

    /** Eine gemerkte Lesart. kraft -1 = unbekannt. */
    public record Lesart(String gelesen, String kanonisch, String clan, long kraft, int anzahl) { }

    public static final String DATEINAME = "arena-lesarten.txt";
    /** Ab so vielen Bestaetigungen gilt eine Lesart. */
    public static final int AB_BESTAETIGUNGEN = 2;
    /** Mehr Zeilen behaelt die Datei nicht (zuerst fallen die nur einmal gesehenen, aeltesten). */
    static final int HOECHSTENS = 5000;
    /** Die Grenze, die gerade gilt (Tests setzen sie herab). */
    static volatile int hoechstens = HOECHSTENS;

    private static volatile Lesarten standard;

    /** Das Woerterbuch im Bot-Ordner. */
    public static Lesarten standard() {
        Lesarten s = standard;
        if (s == null) {
            synchronized (Lesarten.class) {
                if (standard == null) standard = new Lesarten(Ordner.bei(DATEINAME));
                s = standard;
            }
        }
        return s;
    }

    private final Path datei;
    /** Schluessel: Form der Lesart + "\u0001" + kanonischer Name. Reihenfolge = zuletzt bestaetigt hinten. */
    private final LinkedHashMap<String, Lesart> eintraege = new LinkedHashMap<>();
    private long gelesenStand = Long.MIN_VALUE;

    public Lesarten(Path datei) {
        this.datei = datei;
    }

    /**
     * Merkt sich: die Texterkennung las {@code gelesen}, es war aber sicher {@code kanonisch} (mit Clan und Kraft,
     * beides darf fehlen: "" bzw. -1). Nur aufrufen, wenn der Spieler SICHER erkannt ist.
     */
    public synchronized void merken(String gelesen, String kanonisch, String clan, long kraft) {
        String form = NamensVergleich.form(gelesen);
        String k = sauber(kanonisch);
        if (form.isEmpty() || k.isEmpty()) return;
        laden();
        String schluessel = form + "\u0001" + k;
        Lesart alt = eintraege.remove(schluessel);
        String c = sauber(clan);
        Lesart neu = alt == null
                ? new Lesart(sauber(gelesen), k, c, kraft > 0 ? kraft : -1, 1)
                : new Lesart(alt.gelesen(), k, c.isEmpty() ? alt.clan() : c, kraft > 0 ? kraft : alt.kraft(),
                        alt.anzahl() == Integer.MAX_VALUE ? alt.anzahl() : alt.anzahl() + 1);
        eintraege.put(schluessel, neu);   // hinten = zuletzt bestaetigt
        kuerzen();
        speichern();
    }

    /**
     * Der richtige Spieler zu dieser Lesart -- nur ab {@link #AB_BESTAETIGUNGEN} Bestaetigungen und wenn kein anderer
     * Name mit derselben Lesart gleich oft bestaetigt ist. Sonst leer.
     */
    public synchronized Optional<Lesart> kanonisch(String gelesen) {
        String form = NamensVergleich.form(gelesen);
        if (form.isEmpty()) return Optional.empty();
        laden();
        Lesart beste = null;
        int zweite = 0;
        String vorne = form + "\u0001";
        for (Map.Entry<String, Lesart> e : eintraege.entrySet()) {
            if (!e.getKey().startsWith(vorne)) continue;
            Lesart l = e.getValue();
            if (beste == null || l.anzahl() > beste.anzahl()) {
                if (beste != null) zweite = Math.max(zweite, beste.anzahl());
                beste = l;
            } else {
                zweite = Math.max(zweite, l.anzahl());
            }
        }
        if (beste == null || beste.anzahl() < AB_BESTAETIGUNGEN || beste.anzahl() <= zweite) return Optional.empty();
        return Optional.of(beste);
    }

    /** Wie viele Lesarten gemerkt sind. */
    public synchronized int groesse() {
        laden();
        return eintraege.size();
    }

    // ------------------------------------------------------------------

    /** Ohne Trenner und Zeilenumbrueche -- sonst zerfiele die Zeile beim naechsten Lesen. */
    private static String sauber(String s) {
        if (s == null) return "";
        return s.replace('|', '/').replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
    }

    /** Liest die Datei neu, wenn sie sich geaendert hat (auch von Hand). Fehlt sie, bleibt das Gemerkte. */
    private void laden() {
        try {
            if (!Files.exists(datei)) return;
            long stand = Files.getLastModifiedTime(datei).toMillis() ^ Files.size(datei);
            if (stand == gelesenStand) return;
            LinkedHashMap<String, Lesart> neu = new LinkedHashMap<>();
            for (String z : Namensbilder.zeilenLesen(datei)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] t = z.split("\\|", -1);
                if (t.length < 2) continue;
                String gelesen = t[0].strip(), k = t[1].strip();
                String form = NamensVergleich.form(gelesen);
                if (form.isEmpty() || k.isEmpty()) continue;
                String clan = t.length > 2 ? t[2].strip() : "";
                long kraft = t.length > 3 ? zahl(t[3], -1) : -1;
                int anzahl = (int) Math.max(1, Math.min(Integer.MAX_VALUE, t.length > 4 ? zahl(t[4], 1) : 1));
                String schluessel = form + "\u0001" + k;
                Lesart doppelt = neu.remove(schluessel);
                if (doppelt != null) {
                    anzahl = (int) Math.min(Integer.MAX_VALUE, (long) anzahl + doppelt.anzahl());
                    if (clan.isEmpty()) clan = doppelt.clan();
                    if (kraft <= 0) kraft = doppelt.kraft();
                }
                neu.put(schluessel, new Lesart(gelesen, k, clan, kraft > 0 ? kraft : -1, anzahl));
            }
            eintraege.clear();
            eintraege.putAll(neu);
            gelesenStand = stand;
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t(DATEINAME + " not readable: " + e.getMessage(),
                    DATEINAME + " nicht lesbar: " + e.getMessage()));
        }
    }

    /** "26,304,529" / "26.304.529" / "26304529" -> Zahl; sonst {@code sonst}. */
    private static long zahl(String s, long sonst) {
        String z = s.replaceAll("[\\s.,'_]", "");
        if (z.isEmpty() || !z.chars().allMatch(Character::isDigit) || z.length() > 18) return sonst;
        return Long.parseLong(z);
    }

    /** Zu viele Zeilen: erst die nur einmal gesehenen (aelteste zuerst), dann die aeltesten. */
    private void kuerzen() {
        int grenze = hoechstens;
        if (eintraege.size() <= grenze) return;
        for (Iterator<Lesart> it = eintraege.values().iterator(); it.hasNext() && eintraege.size() > grenze; ) {
            if (it.next().anzahl() <= 1) it.remove();
        }
        for (Iterator<Lesart> it = eintraege.values().iterator(); it.hasNext() && eintraege.size() > grenze; ) {
            it.next();
            it.remove();
        }
    }

    /** Erst in eine Nebendatei schreiben, dann austauschen -- ein Absturz mittendrin laesst die alte Datei heil. */
    private void speichern() {
        List<String> zeilen = new ArrayList<>(eintraege.size() + 3);
        zeilen.add("# Arena: so las die Texterkennung den Namen -> so heisst der Spieler (vom Bot gefuellt).");
        zeilen.add("# gelesen | kanonisch | clan | kraft | anzahl   (gilt ab " + AB_BESTAETIGUNGEN + " Bestaetigungen)");
        for (Lesart l : eintraege.values()) {
            zeilen.add(l.gelesen() + " | " + l.kanonisch() + " | " + l.clan() + " | "
                    + (l.kraft() > 0 ? l.kraft() : "") + " | " + l.anzahl());
        }
        Path neu = datei.resolveSibling(datei.getFileName() + ".neu");
        try {
            if (datei.getParent() != null) Files.createDirectories(datei.getParent());
            Files.write(neu, zeilen, StandardCharsets.UTF_8);
            try {
                Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
            }
            gelesenStand = Files.getLastModifiedTime(datei).toMillis() ^ Files.size(datei);
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t(DATEINAME + " could not be saved: " + e.getMessage(),
                    DATEINAME + " liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
