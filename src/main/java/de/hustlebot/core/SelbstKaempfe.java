package de.hustlebot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Autor 30.10.: "Wenn er den Spieler noch nicht kennt, soll er sich bitte nur den Namen des jeweiligen Spielers genau merken.
 * Getrennt in einem anderen Notizblock." Der eigene Notizblock fuer Spieler, die nur Autor selbst angegriffen oder angesehen hat --
 * ohne Sets, Pals und Schirmherren, die sieht er sich selten an. Die Liste nach dem dritten Kampf rechnet
 * ArenaListenRegel.nachSelbstKaempfen. Kaempft spaeter der Bot gegen ihn, wandern die Kaempfe in den normalen Notizblock (entfernen).
 *
 * Datei arena-selbst-kaempfe.txt (je Konto), je Zeile: Name TAB Clan TAB Kaempfe TAB eingetragene Liste.
 * Kaempfe, durch ; getrennt, der aelteste vorn: S oder N, Uhrzeit, Kampfdauer in s, Punkte (-1 = nicht gelesen).
 */
public final class SelbstKaempfe {

    public static final String DATEI = "arena-selbst-kaempfe.txt";
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SelbstKaempfe() { }

    public record Kampf(boolean sieg, String um, int dauerS, long punkte) {
        String text() {
            return (sieg ? "S" : "N") + "," + um + "," + dauerS + "," + punkte;
        }

        static Kampf aus(String t) {
            String[] f = t.split(",", -1);
            if (f.length != 4 || !(f[0].equals("S") || f[0].equals("N"))) return null;
            try {
                return new Kampf(f[0].equals("S"), f[1], Integer.parseInt(f[2]), Long.parseLong(f[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public record Eintrag(String name, String clan, List<Kampf> kaempfe, String liste) {
        public Eintrag {
            kaempfe = List.copyOf(kaempfe);
        }

        /** S = Sieg, N = Niederlage, der aelteste vorn. */
        public String ergebnisse() {
            StringBuilder b = new StringBuilder();
            for (Kampf k : kaempfe) b.append(k.sieg() ? 'S' : 'N');
            return b.toString();
        }

        public int siege() {
            return (int) kaempfe.stream().filter(Kampf::sieg).count();
        }

        public int niederlagen() {
            return kaempfe.size() - siege();
        }
    }

    /** Der Eintrag zu diesem Namen; null = Autor hat ihn noch nie angesehen oder angegriffen. */
    public static Eintrag finde(String name) {
        return finde(Ordner.bei(DATEI), name);
    }

    /** Im Vergleichsfenster gesehen: nur Name und Clan merken (legt ihn an, zaehlt keinen Kampf). */
    public static void gesehen(String name, String clan) {
        gesehen(Ordner.bei(DATEI), name, clan);
    }

    /** Einen Kampf buchen (legt den Spieler an, wenn er noch fehlt). */
    public static Eintrag ergebnis(String name, String clan, boolean sieg, LocalDateTime um, int dauerS, long punkte) {
        return ergebnis(Ordner.bei(DATEI), name, clan, sieg, um, dauerS, punkte);
    }

    /** Merkt, dass sein Namensbild jetzt in dieser Liste liegt. */
    public static void eingetragen(String name, String liste) {
        eingetragen(Ordner.bei(DATEI), name, liste);
    }

    /** Nimmt den Spieler heraus (der Bot kaempft jetzt selbst gegen ihn); null = stand nicht drin. */
    public static Eintrag entfernen(String name) {
        return entfernen(Ordner.bei(DATEI), name);
    }

    static synchronized Eintrag finde(Path datei, String name) {
        List<Eintrag> alle = lesen(datei);
        int i = alle == null ? -1 : stelle(alle, name, "");
        return i < 0 ? null : alle.get(i);
    }

    static synchronized void gesehen(Path datei, String name, String clan) {
        if (name == null || name.isBlank()) return;
        List<Eintrag> alle = lesen(datei);
        if (alle == null) return;
        int i = stelle(alle, name, clan);
        String c = sauber(clan);
        if (i < 0) {
            alle.add(new Eintrag(sauber(name), c, List.of(), ""));
        } else {
            Eintrag e = alle.get(i);
            if (c.isEmpty() || c.equals(e.clan())) return;   // nichts Neues: nicht schreiben
            alle.set(i, new Eintrag(e.name(), c, e.kaempfe(), e.liste()));
        }
        schreiben(datei, alle);
    }

    static synchronized Eintrag ergebnis(Path datei, String name, String clan, boolean sieg, LocalDateTime um, int dauerS, long punkte) {
        List<Eintrag> alle = lesen(datei);
        Kampf k = new Kampf(sieg, um.format(ZEIT), dauerS, punkte);
        // Nicht lesbar: nichts schreiben, sonst waere der Rest der Datei weg. Der Kampf steht dann nur im Protokoll.
        if (alle == null) return new Eintrag(sauber(name), sauber(clan), List.of(k), "");
        int i = stelle(alle, name, clan);
        List<Kampf> kaempfe = new ArrayList<>(i < 0 ? List.of() : alle.get(i).kaempfe());
        kaempfe.add(k);
        String c = sauber(clan);
        Eintrag e = i < 0 ? new Eintrag(sauber(name), c, kaempfe, "")
                : new Eintrag(alle.get(i).name(), c.isEmpty() ? alle.get(i).clan() : c, kaempfe, alle.get(i).liste());
        if (i < 0) alle.add(e);
        else alle.set(i, e);
        schreiben(datei, alle);
        return e;
    }

    static synchronized void eingetragen(Path datei, String name, String liste) {
        List<Eintrag> alle = lesen(datei);
        int i = alle == null ? -1 : stelle(alle, name, "");
        if (i < 0) return;
        Eintrag e = alle.get(i);
        alle.set(i, new Eintrag(e.name(), e.clan(), e.kaempfe(), liste == null ? "" : liste));
        schreiben(datei, alle);
    }

    static synchronized Eintrag entfernen(Path datei, String name) {
        List<Eintrag> alle = lesen(datei);
        int i = alle == null ? -1 : stelle(alle, name, "");
        if (i < 0) return null;
        Eintrag e = alle.remove(i);
        schreiben(datei, alle);
        return e;
    }

    /**
     * Erst der genaue Name, dann derselbe Name mit hoechstens einem Lesefehler (dieselbe strenge Regel wie bei den Listenbildern;
     * "Dragon" und "Dragon99" bleiben zwei Spieler). Stehen zwei verschiedene Clans dabei, sind es zwei Spieler.
     */
    private static int stelle(List<Eintrag> alle, String name, String clan) {
        if (name == null || name.isBlank()) return -1;
        String n = sauber(name), c = sauber(clan);
        for (int i = 0; i < alle.size(); i++) {
            if (alle.get(i).name().equals(n) && !andererClan(alle.get(i).clan(), c)) return i;
        }
        for (int i = 0; i < alle.size(); i++) {
            if (ArenaVorlagenDoppelt.derselbeName(alle.get(i).name(), n) && !andererClan(alle.get(i).clan(), c)) return i;
        }
        return -1;
    }

    private static boolean andererClan(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && !NamensVergleich.gleicherName(a, b);
    }

    private static String sauber(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
    }

    /** @return null = Datei da, aber nicht lesbar: dann darf nichts geschrieben werden. */
    private static List<Eintrag> lesen(Path datei) {
        List<Eintrag> l = new ArrayList<>();
        if (!Files.exists(datei)) return l;
        try {
            for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                String[] t = z.split("\t", -1);
                if (t.length < 3 || t[0].isBlank()) continue;
                List<Kampf> kaempfe = new ArrayList<>();
                boolean heil = true;
                if (!t[2].isEmpty()) {
                    for (String k : t[2].split(";")) {
                        Kampf ka = Kampf.aus(k);
                        if (ka == null) heil = false;
                        else kaempfe.add(ka);
                    }
                }
                if (heil) l.add(new Eintrag(t[0], t[1], kaempfe, t.length > 3 ? t[3] : ""));
            }
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t(DATEI + " could not be read: ", DATEI + " nicht lesbar: ") + e.getMessage());
            return null;
        }
        return l;
    }

    private static void schreiben(Path datei, List<Eintrag> alle) {
        StringBuilder sb = new StringBuilder();
        for (Eintrag e : alle) {
            StringBuilder k = new StringBuilder();
            for (Kampf ka : e.kaempfe()) k.append(k.isEmpty() ? "" : ";").append(ka.text());
            sb.append(e.name()).append('\t').append(e.clan()).append('\t').append(k).append('\t').append(e.liste()).append("\r\n");
        }
        try {
            Gegnerbuch.sicherSchreiben(datei, sb.toString());
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t(DATEI + " could not be written: ", DATEI + " nicht geschrieben: ") + e.getMessage());
        }
    }
}
