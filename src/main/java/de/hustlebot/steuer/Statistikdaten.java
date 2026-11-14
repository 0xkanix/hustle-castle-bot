package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Laufstatistik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rechnet die Werte fuer die kleinen Diagramme der Statistik aus (Auftrag 4): Durchlaeufe je Stunde,
 * Arena-Siegquote je Tag, Nahrung im Verlauf. Reine Rechnung ohne Bildschirm, damit sie sich pruefen laesst.
 *
 * Quellen, alle schon vorhanden -- nichts wird neu mitgeschrieben:
 *   - Durchlaeufe: die Laufgeschichte des Motors (Laufstatistik.history, je Lauf die Uhrzeit "HH:mm:ss").
 *   - Arena: arena-lernen.txt (je Kampf eine Zeile: Zeit, ..., Ergebnis S/N in Spalte 11).
 *   - Nahrung: die Burg-Stand-Karten dieser Sitzung (Zeile "Nahrung: 1 234 567" bzw. "Food: ...").
 *   - Vorrat: eine Burg-Stand-Karte als Tabelle "Name -> Wert".
 */
public final class Statistikdaten {

    private Statistikdaten() {
    }

    /** Fertige Durchlaeufe je Stunde, von der ersten bis zur letzten Stunde mit einem Lauf ("14 Uhr" -> 12). */
    public static List<Diagramm.Wert> laeufeJeStunde(List<Laufstatistik.RunRecord> laeufe) {
        TreeMap<Integer, Integer> jeStunde = new TreeMap<>();
        if (laeufe != null) {
            for (Laufstatistik.RunRecord r : laeufe) {
                int h = stunde(r.finishedAt());
                if (h >= 0) jeStunde.merge(h, 1, Integer::sum);
            }
        }
        List<Diagramm.Wert> werte = new ArrayList<>();
        if (jeStunde.isEmpty()) return werte;
        int von = jeStunde.firstKey(), bis = jeStunde.lastKey();
        // Ueber Mitternacht (23, 0, 1) ist selten -- dann einfach alle Stunden von 0 bis 23 mit Laeufen.
        for (int h = von; h <= bis; h++) {
            werte.add(new Diagramm.Wert(h + Sprache.t("", " Uhr"), jeStunde.getOrDefault(h, 0)));
        }
        return werte;
    }

    /** Die Stunde aus "14:23:05" oder "14:23"; -1, wenn nicht lesbar. */
    static int stunde(String uhrzeit) {
        if (uhrzeit == null) return -1;
        String t = uhrzeit.trim();
        int doppel = t.indexOf(':');
        if (doppel <= 0) return -1;
        try {
            int h = Integer.parseInt(t.substring(0, doppel).trim());
            return h >= 0 && h <= 23 ? h : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Arena-Siegquote je Tag in Prozent aus dem Text von arena-lernen.txt, die letzten `tage` Tage mit
     * Kaempfen ("08.10." -> 75). Kommentarzeilen (#) und unlesbare Zeilen werden uebergangen.
     */
    public static List<Diagramm.Wert> arenaQuoteJeTag(String arenaLernen, int tage) {
        TreeMap<String, int[]> jeTag = new TreeMap<>();   // Datum -> {Siege, Kaempfe}
        if (arenaLernen != null) {
            for (String zeile : arenaLernen.split("\n")) {
                String z = zeile.strip();
                if (z.isEmpty() || z.startsWith("#")) continue;
                String[] t = z.split("\t");
                if (t.length < 11 || t[0].length() < 10) continue;
                String ergebnis = t[10].trim();
                if (!ergebnis.equals("S") && !ergebnis.equals("N")) continue;
                int[] z2 = jeTag.computeIfAbsent(t[0].substring(0, 10), k -> new int[2]);
                z2[1]++;
                if (ergebnis.equals("S")) z2[0]++;
            }
        }
        List<Diagramm.Wert> werte = new ArrayList<>();
        List<String> tageListe = new ArrayList<>(jeTag.keySet());
        int ab = Math.max(0, tageListe.size() - Math.max(1, tage));
        for (int i = ab; i < tageListe.size(); i++) {
            String tag = tageListe.get(i);
            int[] z = jeTag.get(tag);
            double quote = z[1] == 0 ? 0 : Math.round(100.0 * z[0] / z[1]);
            werte.add(new Diagramm.Wert(tagKurz(tag), quote));
        }
        return werte;
    }

    /**
     * Autor 22.10.: die Siegquote je Tag zaehlt TURNIERE, nicht Kaempfe -- Top 1-3 ist ein Sieg, Top 4-10 eine
     * Niederlage. Aus arena-plaetze.txt, die letzten `tage` Tage mit Turnieren ("21.10." -> 60).
     */
    public static List<Diagramm.Wert> top3QuoteJeTag(String arenaPlaetze, int tage) {
        TreeMap<String, int[]> jeTag = de.hustlebot.core.ArenaPlaetze.jeTag(arenaPlaetze);
        List<Diagramm.Wert> werte = new ArrayList<>();
        List<String> tageListe = new ArrayList<>(jeTag.keySet());
        int ab = Math.max(0, tageListe.size() - Math.max(1, tage));
        for (int i = ab; i < tageListe.size(); i++) {
            int[] w = jeTag.get(tageListe.get(i));
            double quote = w[0] == 0 ? 0 : Math.round(100.0 * (w[1] + w[2] + w[3]) / w[0]);
            werte.add(new Diagramm.Wert(tagKurz(tageListe.get(i)), quote));
        }
        return werte;
    }

    /** Siege und Kaempfe insgesamt aus arena-lernen.txt: {Siege, Kaempfe}. */
    public static int[] arenaBilanz(String arenaLernen) {
        int[] b = new int[2];
        if (arenaLernen == null) return b;
        for (String zeile : arenaLernen.split("\n")) {
            String z = zeile.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            String[] t = z.split("\t");
            if (t.length < 11) continue;
            String e = t[10].trim();
            if (e.equals("S")) { b[0]++; b[1]++; }
            else if (e.equals("N")) b[1]++;
        }
        return b;
    }

    /** "2026-10-08" -> "08.10." */
    static String tagKurz(String isoTag) {
        if (isoTag == null || isoTag.length() < 10) return String.valueOf(isoTag);
        return isoTag.substring(8, 10) + "." + isoTag.substring(5, 7) + ".";
    }

    /**
     * Nahrung im Verlauf dieser Sitzung aus den Burg-Stand-Karten ("14:05" -> 1 234 567).
     * Hoechstens die letzten `hoechstens` Ablesungen.
     */
    public static List<Diagramm.Wert> nahrungAusMeldungen(List<Meldung> meldungen, int hoechstens) {
        List<Diagramm.Wert> werte = new ArrayList<>();
        if (meldungen == null) return werte;
        for (Meldung m : meldungen) {
            if (!m.titelEnthaelt("Burg-Stand", "Castle status")) continue;
            Long n = nahrung(m.textLesbar());
            if (n != null) werte.add(new Diagramm.Wert(m.uhrzeit().substring(0, Math.min(5, m.uhrzeit().length())), n));
        }
        while (werte.size() > Math.max(1, hoechstens)) werte.remove(0);
        return werte;
    }

    /** Die Nahrung aus einem Burg-Stand-Text, oder null. */
    static Long nahrung(String text) {
        Map<String, String> z = vorratZeilen(text);
        for (var e : z.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("nahrung") || k.equals("food")) return zahl(e.getValue());
        }
        return null;
    }

    /**
     * "Name: Wert" je Zeile einer Burg-Stand-Karte als Tabelle, in der Reihenfolge der Karte.
     * Zeilen ohne Doppelpunkt (Hinweise wie "Nichts lesbar") landen unter "Hinweis".
     */
    public static Map<String, String> vorratZeilen(String text) {
        Map<String, String> z = new LinkedHashMap<>();
        if (text == null) return z;
        for (String zeile : Holzstil.ohneMarkdown(text).split("\n")) {
            String s = zeile.strip();
            if (s.isEmpty()) continue;
            int d = s.indexOf(':');
            if (d <= 0 || d == s.length() - 1 && s.indexOf(':') == s.lastIndexOf(':') && s.length() < 3) {
                z.put("Hinweis", s);
                continue;
            }
            String name = s.substring(0, d).strip();
            String wert = s.substring(d + 1).strip();
            if (name.isEmpty()) continue;
            z.put(name, wert);
        }
        return z;
    }

    /** "1 234 567" oder "1.234.567" oder "1234567" als Zahl, sonst null. */
    public static Long zahl(String wert) {
        if (wert == null) return null;
        String s = wert.strip().replace(" ", "").replace(".", "").replace(" ", "");
        if (s.isEmpty() || !s.matches("-?\\d+")) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 1234567 -> "1,2 Mio", 45000 -> "45 Tsd", 320 -> "320". */
    public static String kurz(long wert) {
        if (Math.abs(wert) >= 1_000_000) return String.format(java.util.Locale.GERMANY, Sprache.t("%.1f M", "%.1f Mio"), wert / 1_000_000.0);
        if (Math.abs(wert) >= 10_000) return Math.round(wert / 1000.0) + Sprache.t(" k", " Tsd");
        return Holzstil.zahl(wert);
    }
}
