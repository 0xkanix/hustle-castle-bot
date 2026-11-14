package de.hustlebot.steuer;

import de.hustlebot.core.ArenaPlaetze;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Laufstatistik;
import de.hustlebot.core.Zeitzone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Autor 24.10. (Ideenliste F29): Die Statistik ueberlebt den Neustart. Bisher zeigten die Diagramme nach jedem Neustart
 * "noch kein Durchlauf in dieser Sitzung" (Quelle war nur Laufstatistik.history). Jetzt schreibt das Steuerprogramm die
 * Tageswerte in eine kleine Datei (statistik-tage.txt, eine Zeile je Tag) -- und die Statistik zeigt "Heute / 7 Tage / Gesamt".
 *
 * Mitgeschrieben wird, was ohnehin da ist: fertige Durchlaeufe samt Zeit und verlorenen Kaempfen (Laufgeschichte des Motors),
 * die Nahrung aus den Burg-Stand-Karten (erste und letzte Ablesung des Tages), die Durchlaeufe je Stunde. Die Arena-Quote
 * steht schon dauerhaft in arena-plaetze.txt -- sie wird nur dazugelesen.
 *
 * Zeile: {@code 2026-10-24;Durchlaeufe;Sekunden;verloreneKaempfe;NahrungErste;NahrungLetzte;h0,h1,...,h23}
 */
final class Tagesstatistik {

    /** Ein Tag. Nahrung -1 = nicht abgelesen. */
    static final class Tag {
        final LocalDate datum;
        int laeufe;
        long sekunden;
        int niederlagen;
        long nahrungErste = -1, nahrungLetzte = -1;
        final int[] jeStunde = new int[24];

        Tag(LocalDate datum) {
            this.datum = datum;
        }

        String zeile() {
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 24; i++) h.append(i == 0 ? "" : ",").append(jeStunde[i]);
            return datum + ";" + laeufe + ";" + sekunden + ";" + niederlagen + ";" + nahrungErste + ";" + nahrungLetzte + ";" + h;
        }

        static Tag aus(String zeile) {
            try {
                String[] t = zeile.strip().split(";", -1);
                if (t.length < 6) return null;
                Tag tag = new Tag(LocalDate.parse(t[0]));
                tag.laeufe = Integer.parseInt(t[1]);
                tag.sekunden = Long.parseLong(t[2]);
                tag.niederlagen = Integer.parseInt(t[3]);
                tag.nahrungErste = Long.parseLong(t[4]);
                tag.nahrungLetzte = Long.parseLong(t[5]);
                if (t.length > 6) {
                    String[] h = t[6].split(",");
                    for (int i = 0; i < Math.min(24, h.length); i++) tag.jeStunde[i] = Integer.parseInt(h[i].strip());
                }
                return tag;
            } catch (RuntimeException e) {
                return null;   // kaputte Zeile -- weglassen
            }
        }
    }

    /** Mehr Tage behaelt die Datei nicht (gut ein Jahr). */
    static final int MAX_TAGE = 400;

    private final Path datei;
    private final TreeMap<LocalDate, Tag> tage = new TreeMap<>();
    private boolean geaendert;
    /** Wie viele Eintraege der Laufgeschichte schon gezaehlt sind -- sie wird bei jedem Start eines Modus geleert. */
    private int gezaehlt;

    Tagesstatistik(Path datei) {
        this.datei = datei;
        laden();
    }

    private void laden() {
        if (datei == null || !Files.isRegularFile(datei)) return;
        try {
            for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                Tag t = Tag.aus(z);
                if (t != null) tage.put(t.datum, t);
            }
        } catch (Exception e) {
            Log.debug("Statistik", "statistik-tage.txt nicht lesbar: " + e.getMessage());
        }
    }

    private Tag tag(LocalDate d) {
        return tage.computeIfAbsent(d, Tag::new);
    }

    /** Ein fertiger Durchlauf. {@code stunde} -1 = unbekannt (dann zaehlt die jetzige Stunde nicht in den Stunden). */
    synchronized void lauf(LocalDate tag, int stunde, long sekunden, int niederlagen) {
        Tag t = tag(tag);
        t.laeufe++;
        t.sekunden += Math.max(0, sekunden);
        t.niederlagen += Math.max(0, niederlagen);
        if (stunde >= 0 && stunde <= 23) t.jeStunde[stunde]++;
        geaendert = true;
    }

    /** Eine Nahrungs-Ablesung (aus einer Burg-Stand-Karte). */
    synchronized void nahrung(LocalDate tag, long wert) {
        if (wert < 0) return;
        Tag t = tag(tag);
        if (t.nahrungErste < 0) t.nahrungErste = wert;
        t.nahrungLetzte = wert;
        geaendert = true;
    }

    /**
     * Neue Durchlaeufe aus der Laufgeschichte des Motors nachtragen. Wird die Liste kuerzer als beim letzten Mal, wurde sie
     * geleert (neuer Modus, Neustart) -- dann von vorn zaehlen. Gibt zurueck, ob etwas dazukam.
     */
    synchronized boolean nachtragen(List<Laufstatistik.RunRecord> geschichte, LocalDate heute) {
        if (geschichte == null) return false;
        if (geschichte.size() < gezaehlt) gezaehlt = 0;
        boolean neu = false;
        for (int i = gezaehlt; i < geschichte.size(); i++) {
            Laufstatistik.RunRecord r = geschichte.get(i);
            lauf(heute, Statistikdaten.stunde(r.finishedAt()), r.seconds(), r.failedBattles());
            neu = true;
        }
        gezaehlt = geschichte.size();
        return neu;
    }

    /** Schreibt die Datei, wenn sich etwas geaendert hat (die letzten MAX_TAGE Tage). */
    synchronized void speichern() {
        if (!geaendert || datei == null) return;
        try {
            while (tage.size() > MAX_TAGE) tage.pollFirstEntry();
            List<String> zeilen = new ArrayList<>();
            zeilen.add("# Tageswerte des Bots (Datum;Durchlaeufe;Sekunden;verlorene Kaempfe;Nahrung erste;Nahrung letzte;Durchlaeufe je Stunde)");
            for (Tag t : tage.values()) zeilen.add(t.zeile());
            Files.write(datei, zeilen, StandardCharsets.UTF_8);
            geaendert = false;
        } catch (Exception e) {
            Log.debug("Statistik", "statistik-tage.txt nicht geschrieben: " + e.getMessage());
        }
    }

    synchronized Tag tagOderNull(LocalDate d) {
        return tage.get(d);
    }

    synchronized boolean leer() {
        return tage.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Anzeige
    // ------------------------------------------------------------------------------------------------------------

    /** Durchlaeufe je Stunde fuer einen Tag (von der ersten bis zur letzten Stunde mit einem Lauf). */
    synchronized List<Diagramm.Wert> laeufeJeStunde(LocalDate d) {
        List<Diagramm.Wert> werte = new ArrayList<>();
        Tag t = tage.get(d);
        if (t == null) return werte;
        int von = -1, bis = -1;
        for (int h = 0; h < 24; h++) {
            if (t.jeStunde[h] > 0) {
                if (von < 0) von = h;
                bis = h;
            }
        }
        if (von < 0) return werte;
        for (int h = von; h <= bis; h++) werte.add(new Diagramm.Wert(h + Sprache.t("", " Uhr"), t.jeStunde[h]));
        return werte;
    }

    /** Durchlaeufe je Tag, die letzten {@code anzahl} Tage bis heute (Tage ohne Eintrag zaehlen 0). */
    synchronized List<Diagramm.Wert> laeufeJeTag(LocalDate heute, int anzahl) {
        List<Diagramm.Wert> werte = new ArrayList<>();
        for (int i = anzahl - 1; i >= 0; i--) {
            LocalDate d = heute.minusDays(i);
            Tag t = tage.get(d);
            werte.add(new Diagramm.Wert(String.format("%02d.%02d.", d.getDayOfMonth(), d.getMonthValue()), t == null ? 0 : t.laeufe));
        }
        return werte;
    }

    /** "Heute / 7 Tage / Gesamt" in Klartext, je eine Zeile. {@code arenaPlaetze} = Text von arena-plaetze.txt (darf leer sein). */
    synchronized String text(LocalDate heute, String arenaPlaetze) {
        TreeMap<String, int[]> arena = ArenaPlaetze.jeTag(arenaPlaetze == null ? "" : arenaPlaetze);
        StringBuilder b = new StringBuilder();
        b.append(zeile(Sprache.t("Today", "Heute"), heute, heute, arena)).append('\n');
        b.append(zeile(Sprache.t("Last 7 days", "Letzte 7 Tage"), heute.minusDays(6), heute, arena)).append('\n');
        LocalDate erste = tage.isEmpty() ? heute : tage.firstKey();
        b.append(zeile(Sprache.t("Total (since ", "Gesamt (seit ") + erste.format(DateTimeFormatter.ofPattern("dd.MM.")) + ")", erste, heute, arena));
        return b.toString();
    }

    private String zeile(String name, LocalDate von, LocalDate bis, TreeMap<String, int[]> arena) {
        int laeufe = 0, niederlagen = 0, turniere = 0, top3 = 0;
        long sekunden = 0, nahrungVorher = -1, nahrungNachher = -1;
        for (Map.Entry<LocalDate, Tag> e : tage.subMap(von, true, bis, true).entrySet()) {
            Tag t = e.getValue();
            laeufe += t.laeufe;
            sekunden += t.sekunden;
            niederlagen += t.niederlagen;
            if (t.nahrungErste >= 0 && nahrungVorher < 0) nahrungVorher = t.nahrungErste;
            if (t.nahrungLetzte >= 0) nahrungNachher = t.nahrungLetzte;
        }
        for (Map.Entry<String, int[]> e : arena.entrySet()) {
            try {
                LocalDate d = LocalDate.parse(e.getKey());
                if (d.isBefore(von) || d.isAfter(bis)) continue;
                int[] w = e.getValue();
                turniere += w[0];
                top3 += w[1] + w[2] + w[3];
            } catch (RuntimeException ignored) {
                // Tag nicht lesbar -- uebergehen
            }
        }
        StringBuilder b = new StringBuilder(name).append(":  ");
        if (laeufe == 0 && turniere == 0) return b.append(Sprache.t("nothing recorded", "nichts aufgezeichnet")).toString();
        b.append(laeufe).append(laeufe == 1 ? Sprache.t(" run", " Durchlauf") : Sprache.t(" runs", " Durchläufe"));
        if (sekunden > 0) b.append(" · ").append(stunden(sekunden));
        if (niederlagen > 0) b.append(" · ").append(niederlagen).append(niederlagen == 1 ? Sprache.t(" lost fight", " verlorener Kampf") : Sprache.t(" lost fights", " verlorene Kämpfe"));
        if (turniere > 0) {
            b.append(" · Arena ").append(turniere).append(Sprache.t(" played, ", " gespielt, ")).append(top3)
                    .append(" Top 3 (").append(Math.round(100.0 * top3 / turniere)).append(" %)");
        }
        if (nahrungVorher >= 0 && nahrungNachher >= 0 && nahrungVorher != nahrungNachher) {
            long d = nahrungNachher - nahrungVorher;
            b.append(" · ").append(Sprache.t("food ", "Nahrung ")).append(d > 0 ? "+" : "−").append(Statistikdaten.kurz(Math.abs(d)));
        }
        return b.toString();
    }

    /** 18720 s -> "5:12 h". */
    static String stunden(long sekunden) {
        long min = Math.round(sekunden / 60.0);
        return (min / 60) + ":" + String.format("%02d", min % 60) + " h";
    }

    // ------------------------------------------------------------------------------------------------------------
    //  Das Mitschreiben im Hintergrund
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Ein Faden, der alle 10 Sekunden die Laufgeschichte ansieht und neue Durchlaeufe nachtraegt (und die Datei schreibt,
     * wenn sich etwas getan hat). Er liest nur -- am Bot aendert er nichts.
     */
    Thread starten(Supplier<Laufstatistik> laufstatistik) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                    Laufstatistik tele = laufstatistik.get();
                    if (tele != null) nachtragen(tele.history(), Zeitzone.heute());
                    speichern();
                } catch (InterruptedException e) {
                    speichern();
                    return;
                } catch (Exception e) {
                    Log.debug("Statistik", "Mitschreiben: " + e.getMessage());
                }
            }
        }, "statistik-tage");
        t.setDaemon(true);
        t.start();
        // Beim Beenden noch einmal schreiben -- sonst gingen die letzten Sekunden verloren.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Laufstatistik tele = laufstatistik.get();
                if (tele != null) nachtragen(tele.history(), Zeitzone.heute());
                speichern();
            } catch (Exception ignored) {
                // beim Beenden nichts mehr melden
            }
        }, "statistik-tage-ende"));
        return t;
    }
}
