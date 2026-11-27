package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Warteschlange des Steuerprogramms (Autor 15.11.): bis zu fuenf Modi nacheinander, jeder mit seinen Einstellungen wie im
 * Modus-Menue, dazu die Schalter "Zwischendurch" (Leuchtturm, Kolosseum, Titanen-Turm, Clan-Krieg) und eine Endzeit.
 *
 * Hier steht nur, was ohne Bildschirm geht. Aus den Zeilen werden Befehle, genau wie in Discord getippt:
 *
 *     !settings leuchtturm an
 *     !ende 06:30
 *     !queue start cathedral 50 c food noevent | dungeon 10 bob | portal bis 135 farm 80:2mio
 *
 * Der letzte Befehl bringt die ganze Liste auf einmal zum Bot (DiscordBridge.warteschlangeStarten): steht er, startet
 * Nummer 1 gleich und der Rest folgt der Reihe nach. Dieselben Zeilen sind ein Profil (Profile) -- und aus einem Profil
 * werden wieder die Zeilen des Fensters (lesen). Das Fenster selbst ist Warteschlangenfenster.
 */
final class Warteschlange {

    private Warteschlange() {
    }

    /** So viele Zeilen hat das Fenster. */
    static final int MAX = 5;

    /** Kathedrale: Event-Kreislauf wie eingestellt, mit Event oder nur Kathedrale. Das Wort versteht !start. */
    enum Ablauf {
        WIE_EINGESTELLT("", Sprache.t("event: as set", "Event: wie eingestellt")),
        MIT_EVENT("eventloop", Sprache.t("with event loop", "mit Event-Kreislauf")),
        OHNE_EVENT("noevent", Sprache.t("without event", "ohne Event"));

        final String wort;
        final String anzeige;

        Ablauf(String wort, String anzeige) {
            this.wort = wort;
            this.anzeige = anzeige;
        }

        @Override
        public String toString() {
            return anzeige;
        }
    }

    /** Die vier Schalter "Zwischendurch" -- dieselben Befehle wie im Reiter Einstellungen. */
    enum Schalter {
        LEUCHTTURM("!settings leuchtturm", Sprache.t("Lighthouse", "Leuchtturm")),
        KOLOSSEUM("!settings kolosseum", Sprache.t("Colosseum", "Kolosseum")),
        TITANTURM("!settings titanturm", Sprache.t("Titan tower", "Titanen-Turm")),
        CLANKRIEG("!kriegsplan", Sprache.t("Clan war", "Clan-Krieg"));

        /** Der Befehl ohne Wert; so heisst er auch in Einstellungswerte. */
        final String basis;
        final String anzeige;

        Schalter(String basis, String anzeige) {
            this.basis = basis;
            this.anzeige = anzeige;
        }

        String befehl(boolean an) {
            return basis + (an ? " an" : " aus");
        }
    }

    /**
     * Eine Zeile: was gespielt wird (ein {@link Befehlsbau.Modus} oder ein {@link Befehlsbau.Stigma}; null = leer) und alles,
     * was dazu einstellbar ist. Felder, die zum Modus nicht passen, werden beim Bauen nicht benutzt.
     *
     * @param anzahl  wie oft; 0 = ohne Zahl (Event und Titan-Grabung: bis alles aufgebraucht)
     * @param weg     Kathedrale: "c" (Turbo) oder "b" (alle Wegpunkte)
     * @param titan   Stigmas: das Titan-Wort ("" = Last Guardian, "medusa", "keeper" ...)
     */
    record Eintrag(Object art, int anzahl, String weg, Befehlsbau.Bezahlart bezahl, Ablauf ablauf, String freund,
                   String portalBis, String level1, String nahrung1, String level2, String nahrung2, String titan) {

        Eintrag {
            weg = "b".equalsIgnoreCase(weg == null ? "" : weg.trim()) ? "b" : "c";
            bezahl = bezahl == null ? Befehlsbau.Bezahlart.STANDARD : bezahl;
            ablauf = ablauf == null ? Ablauf.WIE_EINGESTELLT : ablauf;
            // "bob, alice" -> "bob,alice" (Befehlsbau macht aus jedem Leerzeichen ein Komma)
            // klein wie in der Mitspieler-Liste; die Bruecke macht die Bildnamen ohnehin klein
            freund = sauber(freund).toLowerCase(Locale.ROOT).replaceAll("[,\\s]+", ",").replaceAll("^,|,$", "");
            portalBis = sauber(portalBis);
            level1 = sauber(level1);
            nahrung1 = sauber(nahrung1);
            level2 = sauber(level2);
            nahrung2 = sauber(nahrung2);
            titan = sauber(titan).toLowerCase(Locale.ROOT);
            anzahl = Math.max(0, anzahl);
        }

        /** Ein Eintrag mit den Vorgaben des Modus-Menues. */
        static Eintrag von(Object art, int anzahl) {
            return new Eintrag(art, anzahl, "c", null, null, "", "", "", "", "", "", "");
        }

        boolean leer() {
            return !(art instanceof Befehlsbau.Modus) && !(art instanceof Befehlsbau.Stigma);
        }

        /**
         * Der Eintrag fuer den Bot -- "cathedral 50 c food noevent", "portal bis 135 farm 80:2mio", "stigmas leveln medusa".
         * @param nichtsBehalten beim Stigma-Loeschen: es wird kein Set behalten (dann verlangt der Bot "alles", wie im Steuerfenster)
         */
        String text(boolean nichtsBehalten) {
            if (art instanceof Befehlsbau.Stigma s) {
                String b = s.befehl(s == Befehlsbau.Stigma.LOESCHEN ? "" : titan, false).substring(1);
                return s == Befehlsbau.Stigma.LOESCHEN && nichtsBehalten ? b + " alles" : b;
            }
            if (!(art instanceof Befehlsbau.Modus m)) return "";
            List<String> extras = new ArrayList<>();
            if (m == Befehlsbau.Modus.PORTAL) {
                extras.addAll(Befehlsbau.portalPlan(portalBis, Befehlsbau.portalFarm(level1, nahrung1, level2, nahrung2)));
            }
            if (m == Befehlsbau.Modus.KATHEDRALE && !ablauf.wort.isEmpty()) extras.add(ablauf.wort);
            return Befehlsbau.auftrag(m, m.mitAnzahl ? anzahl : 0, weg, mitBezahlart(m) ? bezahl : null, freund, extras);
        }
    }

    /** Nur diese Modi haben eine eigene Bezahlart (wie BEZAHL_MODI in der Bruecke). */
    static boolean mitBezahlart(Befehlsbau.Modus m) {
        return m == Befehlsbau.Modus.KATHEDRALE || m == Befehlsbau.Modus.VERLIES || m == Befehlsbau.Modus.ARENA;
    }

    /** Laeuft dieser Modus ohne Zahl bis alles aufgebraucht ist (Event, Titan-Grabung)? */
    static boolean bisLeer(Object art) {
        return art == Befehlsbau.Modus.HAMMER || art == Befehlsbau.Modus.GRABUNG;
    }

    private static String sauber(String s) {
        return s == null ? "" : s.trim();
    }

    /** Der eine Befehl mit der ganzen Liste: "!queue start A | B | C"; leer, wenn keine Zeile belegt ist. */
    static String startBefehl(List<Eintrag> eintraege, boolean nichtsBehalten) {
        List<String> teile = new ArrayList<>();
        for (Eintrag e : eintraege) {
            if (e == null || e.leer()) continue;
            String t = e.text(nichtsBehalten);
            if (!t.isBlank()) teile.add(t);
        }
        return teile.isEmpty() ? "" : "!queue start " + String.join(" | ", teile);
    }

    /**
     * Alle Befehle der Reihe nach: erst die Schalter (nur die in der Karte), dann die Endzeit, zuletzt die Liste.
     * @param endeUm "06:30" oder leer (keine Endzeit)
     */
    static List<String> befehle(List<Eintrag> eintraege, Map<Schalter, Boolean> schalter, String endeUm, boolean nichtsBehalten) {
        List<String> l = new ArrayList<>();
        for (Schalter s : Schalter.values()) {
            Boolean an = schalter == null ? null : schalter.get(s);
            if (an != null) l.add(s.befehl(an));
        }
        String u = uhrzeit(endeUm);
        if (u != null && !u.isEmpty()) l.add(Befehlsbau.ende(u));
        String start = startBefehl(eintraege, nichtsBehalten);
        if (!start.isEmpty()) l.add(start);
        return l;
    }

    /** "6:30", "06.30", "0630" -> "06:30"; leer -> ""; nicht lesbar -> null. */
    static String uhrzeit(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})(?:[:.h ]?(\\d{2}))?\\s*(?:uhr)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return null;
        return String.format("%02d:%02d", h, min);
    }

    /** Zeile i um eins nach oben (-1) oder unten (+1) tauschen; am Rand bleibt alles, wie es ist. Liefert die neue Stelle. */
    static <T> int verschieben(List<T> l, int i, int richtung) {
        int j = i + Integer.signum(richtung);
        if (i < 0 || i >= l.size() || j < 0 || j >= l.size()) return i;
        T a = l.get(i);
        l.set(i, l.get(j));
        l.set(j, a);
        return j;
    }

    /** Das Titan-Wort fuer den Stigma-Befehl aus dem Namen in der Auswahl ("" = Last Guardian). */
    static String titanWort(String anzeige) {
        String t = String.valueOf(anzeige).replaceAll("\\s*\\((Tank|Archer)\\)$", "");
        return t.startsWith("Last") ? "" : t.startsWith("Keeper") ? "keeper" : t.startsWith("Thunder") ? "thunder"
                : t.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    //  Aus einem Profil wieder die Zeilen
    // ------------------------------------------------------------------

    /** Was aus einem Profil gelesen wurde. schalter enthaelt nur, was im Profil steht. */
    record Geladen(List<Eintrag> eintraege, Map<Schalter, Boolean> schalter, String endeUm) { }

    /**
     * Liest die Befehle eines Profils: "!queue start ..." (aus diesem Fenster), aber auch alte Profile mit "!start X" und
     * "!after Y" (frueherer Knopf "Danach anhaengen") und "!stigmas ...". Schalter und "!ende" kommen in ihre Felder.
     * Mehr als fuenf Eintraege werden abgeschnitten.
     */
    static Geladen lesen(List<String> befehle) {
        List<Eintrag> l = new ArrayList<>();
        Map<Schalter, Boolean> schalter = new EnumMap<>(Schalter.class);
        String ende = "";
        for (String roh : befehle == null ? List.<String>of() : befehle) {
            String b = roh == null ? "" : roh.trim().replaceAll("\\s+", " ");
            String k = b.toLowerCase(Locale.ROOT);
            java.util.regex.Matcher q = java.util.regex.Pattern.compile("^!(?:queue|wq|warteschlange) (?:start|starten|los|go) (.*)$").matcher(k);
            if (q.matches()) {
                for (String teil : b.substring(b.length() - q.group(1).length()).split("\\s*\\|\\s*")) {
                    Eintrag e = eintragAus(teil);
                    if (e != null) l.add(e);
                }
                continue;
            }
            if (k.startsWith("!start ") || k.startsWith("!after ")) {
                Eintrag e = eintragAus(b.substring(7));
                if (e != null) l.add(e);
                continue;
            }
            if (k.startsWith("!stigmas ")) {
                Eintrag e = eintragAus(b.substring(1));
                if (e != null) l.add(e);
                continue;
            }
            if (k.matches("!ende \\S+")) {
                String u = uhrzeit(b.substring(6));
                if (u != null) ende = u;
                continue;
            }
            for (Schalter s : Schalter.values()) {
                if (k.equals(s.basis + " an") || k.equals(s.basis + " on")) schalter.put(s, true);
                else if (k.equals(s.basis + " aus") || k.equals(s.basis + " off")) schalter.put(s, false);
            }
            if (k.equals("!clanwar on")) schalter.put(Schalter.CLANKRIEG, true);
            if (k.equals("!clanwar off")) schalter.put(Schalter.CLANKRIEG, false);
        }
        if (l.size() > MAX) l = new ArrayList<>(l.subList(0, MAX));
        return new Geladen(l, schalter, ende);
    }

    /** Ein Eintrag aus dem Text, den {@link Eintrag#text} baut (auch von Hand getippt); null, wenn kein Modus darin steht. */
    static Eintrag eintragAus(String text) {
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        String k = t.toLowerCase(Locale.ROOT);
        if (k.startsWith("!")) {
            k = k.substring(1);
            t = t.substring(1);
        }
        for (Befehlsbau.Stigma s : new Befehlsbau.Stigma[]{Befehlsbau.Stigma.LOESCHEN, Befehlsbau.Stigma.LEVELN, Befehlsbau.Stigma.HUNDERT}) {
            String wort = s.befehl.substring(1);   // "stigmas loeschen"
            if (k.equals(wort) || k.startsWith(wort + " ")) {
                String titan = s == Befehlsbau.Stigma.LOESCHEN ? "" : k.substring(wort.length()).replaceAll("\\b(probe|alles)\\b", "").trim();
                return new Eintrag(s, 1, "c", null, null, "", "", "", "", "", "", titan);
            }
        }
        String[] w = k.isEmpty() ? new String[0] : k.split(" ");
        if (w.length == 0) return null;
        Befehlsbau.Modus m = null;
        int ab = 0;
        for (Befehlsbau.Modus mo : Befehlsbau.Modus.values()) {   // erst das ganze Befehlswort ("event hammer")
            if (k.equals(mo.befehlswort) || k.startsWith(mo.befehlswort + " ")) {
                m = mo;
                ab = mo.befehlswort.split(" ").length;
                break;
            }
        }
        if (m == null) {
            m = Befehlsbau.Modus.aus(w[0]);
            ab = 1;
        }
        if (m == null) return null;
        int anzahl = 0;
        String weg = "c", bis = "";
        Befehlsbau.Bezahlart bezahl = Befehlsbau.Bezahlart.STANDARD;
        Ablauf ablauf = Ablauf.WIE_EINGESTELLT;
        List<String> freunde = new ArrayList<>();
        String[] farm = new String[4];
        for (int i = ab; i < w.length; i++) {
            String x = w[i];
            if (m == Befehlsbau.Modus.PORTAL && x.matches("bis|ziel|to|until") && i + 1 < w.length && w[i + 1].matches("\\d{1,3}")) {
                bis = w[++i];
            } else if (m == Befehlsbau.Modus.PORTAL && x.matches("farm|farmen") && i + 1 < w.length) {
                String[] paare = w[++i].split("[;,](?=\\d{1,3}:)");
                for (int p = 0; p < Math.min(2, paare.length); p++) {
                    String[] lv = paare[p].split(":", 2);
                    if (lv.length == 2) {
                        farm[p * 2] = lv[0];
                        farm[p * 2 + 1] = lv[1];
                    }
                }
            } else if (x.matches("\\d{1,7}") && anzahl == 0) {
                anzahl = Integer.parseInt(x);
            } else if (m == Befehlsbau.Modus.KATHEDRALE && x.matches("[bc]")) {
                weg = x;
            } else if (bezahlart(x) != null) {
                bezahl = bezahlart(x);
            } else if (x.matches("eventloop|eventablauf|kreislauf")) {
                ablauf = Ablauf.MIT_EVENT;
            } else if (x.matches("noevent|ohneevent")) {
                ablauf = Ablauf.OHNE_EVENT;
            } else if (m == Befehlsbau.Modus.VERLIES && !x.matches("friend|freund|random")) {
                freunde.add(x);
            }
        }
        return new Eintrag(m, anzahl, weg, bezahl, ablauf, String.join(",", freunde), bis,
                farm[0], farm[1], farm[2], farm[3], "");
    }

    private static Befehlsbau.Bezahlart bezahlart(String wort) {
        for (Befehlsbau.Bezahlart b : Befehlsbau.Bezahlart.values()) {
            if (!b.wort.isEmpty() && b.wort.equals(wort)) return b;
        }
        return null;
    }
}
