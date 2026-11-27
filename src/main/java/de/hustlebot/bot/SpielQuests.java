package de.hustlebot.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Taegliche Spiel-Quests (Autor zeigte 18.11.):
 * Burg -> Buch-Symbol -> "Storyline quests" -> lila Reiter mit Sanduhr -> "Daily quests". Links die Quests, rechts zur
 * angetippten Quest die Aufgabe, "Progress x/y", Belohnung und ein Knopf ("Watch" bei Werbung, "Show" fuehrt zum Ort).
 *
 * Diese Klasse ist die reine Logik dazu -- kein Bild, kein Klick, alles ohne Spiel pruefbar (SpielQuestsTest):
 * Text -> Bot-Modus (zuordnen), "Progress 1/3" -> Zahlen (fortschritt), noetige Laeufe, die Folge-Auftraege fuer die
 * Warteschlange. Der Modus selbst (Klicks, Texterkennung) steht in SpielQuestsMode.
 */
final class SpielQuests {

    private SpielQuests() {
    }

    /**
     * Wohin eine Quest fuehrt.
     *
     * modus = Kennung des Bot-Modus (wie in BotEngine registriert); null = der Bot spielt dafuer keinen Modus.
     * einheitenJeLauf = so viel bringt ein Lauf dieses Modus fuer die Quest -- GESCHAETZT, live
     * nachmessen. hoechstens = so viele Laeufe hoechstens je Quest (Leuchtturm, Turm, Kolosseum laufen genau einmal).
     */
    enum Ziel {
        /** Werbevideo: macht der Modus selbst (Knopf "Watch"), kein Folge-Auftrag. Ein Lauf = ein Video. */
        WERBUNG(null, 1, 5, "Werbung ansehen", "Watch ads"),
        /** Ein Gem-Bay-Lauf spielt Reisen, bis der Preis Diamanten kostet -- mehr als einer bringt nichts. */
        GEMBAY("gembay", 15, 1, "Gem Bay", "Gem Bay"),
        ARENA("arena", 3, 5, "Arena", "Arena"),
        VERLIES("dungeon", 1, 10, "Verlies", "Dungeon"),
        KATHEDRALE("cathedral", 1, 6, "Kathedrale", "Cathedral"),
        LEUCHTTURM("lighthouse", 5, 1, "Leuchtturm", "Lighthouse"),
        KOLOSSEUM("colosseum", 5, 1, "Kolosseum", "Colosseum"),
        TURM("titantower", 30, 1, "Titanen-Turm", "Titan Tower"),
        /** Autor 18.11. 22:07: Burgen angreifen (PvP) vorerst gar nicht. */
        PVP(null, 1, 0, "Burgen angreifen (PvP) -- Autor 18.11.: nicht spielen", "Attack castles (PvP) -- not played"),
        /** Clan-Aufgaben (Verlies mit Clan-Kamerad, Clan-Krieg, Spenden ...) macht der Bot nicht von sich aus. */
        CLAN(null, 1, 0, "Clan-Aufgabe -- macht der Bot nicht von sich aus", "Clan task -- the bot does not do it by itself"),
        UNBEKANNT(null, 1, 0, "unbekannt -- uebersprungen", "unknown -- skipped");

        final String modus;
        final int einheitenJeLauf;
        final int hoechstens;
        final String text;
        /** Derselbe Text auf Englisch (Autor 22.11.: bei Englisch alles englisch). */
        final String englisch;

        /** Text in der eingestellten Sprache (Protokoll). */
        String anzeige() {
            return de.hustlebot.core.Sprache.t(englisch, text);
        }

        Ziel(String modus, int einheitenJeLauf, int hoechstens, String text, String englisch) {
            this.englisch = englisch;
            this.modus = modus;
            this.einheitenJeLauf = einheitenJeLauf;
            this.hoechstens = hoechstens;
            this.text = text;
        }

        /** Kann der Bot dafuer etwas tun (Modus anhaengen oder Werbung ansehen)? */
        boolean botKann() {
            return modus != null || this == WERBUNG;
        }
    }

    /** Ergebnis der Zuordnung: Ziel und wie viel ein Lauf bringt (Arena-Abzeichen zaehlen anders als Arena-Siege). */
    record Zuordnung(Ziel ziel, int einheitenJeLauf) {
        static Zuordnung von(Ziel z) {
            return new Zuordnung(z, z.einheitenJeLauf);
        }
    }

    /** Quest-Namen aus dem Spiel, deren Name allein schon sagt, was zu tun ist (KLICK-PROTOKOLL 18.11.). */
    private static final Map<String, Ziel> BEKANNTE_TITEL = new LinkedHashMap<>();

    static {
        BEKANNTE_TITEL.put("ad related quest", Ziel.WERBUNG);   // 1 Werbevideo
        BEKANNTE_TITEL.put("ad related", Ziel.WERBUNG);
        BEKANNTE_TITEL.put("movie theater", Ziel.WERBUNG);      // 3 Werbevideos
        BEKANNTE_TITEL.put("movie theatre", Ziel.WERBUNG);
        BEKANNTE_TITEL.put("boarding crew", Ziel.GEMBAY);       // 5 Siege in der Gem Bay
    }

    // ------------------------------------------------------------------ Woerter (mit Lesefehlern)

    /**
     * Text -> kleine Woerter aus Buchstaben und Ziffern. In Woertern mit Buchstaben werden die typischen Lesefehler der
     * Texterkennung zurueckgebogen: "L1ghthouse" -> "lighthouse", "Dung3on" bleibt (3 ist selten), "B0sses" -> "bosses".
     */
    static List<String> woerter(String text) {
        List<String> aus = new ArrayList<>();
        if (text == null) return aus;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            long buchstaben = w.chars().filter(Character::isLetter).count();
            long ziffern = w.chars().filter(Character::isDigit).count();
            // nur Woerter, die ueberwiegend aus Buchstaben bestehen -- "5h", "20m", "x10" bleiben Zahlen
            if (ziffern > 0 && buchstaben > ziffern) {
                w = w.replace('0', 'o').replace('1', 'l').replace('5', 's');
            }
            aus.add(w);
        }
        return aus;
    }

    /** Abstand zweier Woerter (Levenshtein), fuer verlesene Buchstaben. */
    static int abstand(String a, String b) {
        int[] vorher = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vorher[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int k = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vorher[j] + 1), vorher[j - 1] + k);
            }
            int[] t = vorher;
            vorher = jetzt;
            jetzt = t;
        }
        return vorher[b.length()];
    }

    /**
     * Passt ein gelesenes Wort zum Stichwort? Gleich; oder es faengt damit an (Mehrzahl: "bosses", "battles", ab 4
     * Buchstaben); oder hoechstens 1 Buchstabe falsch (ab 6 Buchstaben) bzw. 2 (ab 9). Kurze Stichwoerter muessen genau
     * passen -- sonst wuerde "tower" zu "power" und "arena" zu "areas".
     */
    static boolean passt(String wort, String stichwort) {
        if (wort.equals(stichwort)) return true;
        if (stichwort.length() >= 4 && wort.startsWith(stichwort) && wort.length() <= stichwort.length() + 3) return true;
        int erlaubt = stichwort.length() >= 9 ? 2 : stichwort.length() >= 6 ? 1 : 0;
        return erlaubt > 0 && Math.abs(wort.length() - stichwort.length()) <= erlaubt && abstand(wort, stichwort) <= erlaubt;
    }

    /**
     * Kommt das Stichwort vor? Mehrere Woerter ("gem bay") muessen hintereinander stehen; zusammengeschrieben
     * ("gembay") oder auseinandergerissen ("light house") zaehlt auch.
     */
    static boolean hat(List<String> w, String stichwort) {
        String[] teile = stichwort.split(" ");
        String zusammen = stichwort.replace(" ", "");
        for (int i = 0; i < w.size(); i++) {
            if (teile.length > 1 && i + teile.length <= w.size()) {
                boolean alle = true;
                for (int k = 0; k < teile.length && alle; k++) alle = passt(w.get(i + k), teile[k]);
                if (alle) return true;
            }
            if (passt(w.get(i), zusammen)) return true;
            if (i + 1 < w.size() && zusammen.length() >= 6 && passt(w.get(i) + w.get(i + 1), zusammen)) return true;
        }
        return false;
    }

    private static boolean eines(List<String> w, String... stichwoerter) {
        for (String s : stichwoerter) if (hat(w, s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ Zuordnung Text -> Modus

    /** Nur ein Text (Titel und Aufgabe zusammen). */
    static Zuordnung zuordnen(String text) {
        return zuordnen(null, text);
    }

    /**
     * Quest -> Ziel. Erst der Titel allein (bekannte Namen wie "Boarding Crew"), dann Titel und Aufgabe zusammen nach
     * Stichwoertern. Reihenfolge wichtig: PvP und Clan zuerst (die werden NIE gespielt, auch wenn "Dungeon" oder "battles"
     * darin steht), dann die Orte, Werbung zuletzt (ein Ort-Text erwaehnt nie Werbung, aber "watch" koennte verlesen
     * irgendwo stehen).
     */
    static Zuordnung zuordnen(String titel, String aufgabe) {
        List<String> tw = woerter(titel);
        if (!tw.isEmpty()) {
            for (Map.Entry<String, Ziel> e : BEKANNTE_TITEL.entrySet()) {
                if (hat(tw, e.getKey())) return Zuordnung.von(e.getValue());
            }
        }
        List<String> w = new ArrayList<>(tw);
        w.addAll(woerter(aufgabe));
        if (w.isEmpty()) return Zuordnung.von(Ziel.UNBEKANNT);

        if (eines(w, "pvp") || eines(w, "castle", "castles") && eines(w, "attack", "attacking", "raid", "rob", "plunder",
                "player", "players", "enemy")) {
            return Zuordnung.von(Ziel.PVP);
        }
        if (eines(w, "clan", "clanmate", "clanmates", "guild")) return Zuordnung.von(Ziel.CLAN);

        if (eines(w, "gem bay", "boarding", "bay")) return Zuordnung.von(Ziel.GEMBAY);
        if (eines(w, "cathedral", "guardian", "guardians")) return Zuordnung.von(Ziel.KATHEDRALE);
        if (eines(w, "lighthouse")) return Zuordnung.von(Ziel.LEUCHTTURM);
        if (eines(w, "colosseum", "coliseum")) return Zuordnung.von(Ziel.KOLOSSEUM);
        if (eines(w, "titan tower", "tower")) return Zuordnung.von(Ziel.TURM);
        if (eines(w, "arena", "tournament", "badge", "courage")) {
            // Abzeichen kommen viele je Turnier, Siege wenige (150 bzw. 3 -- geschaetzt)
            return eines(w, "badge", "courage") ? new Zuordnung(Ziel.ARENA, 150) : Zuordnung.von(Ziel.ARENA);
        }
        if (eines(w, "dungeon", "boss")) return Zuordnung.von(Ziel.VERLIES);
        if (eines(w, "waves")) return Zuordnung.von(Ziel.GEMBAY);   // "Clear waves ..." ohne Ort: so heisst es nur in der Gem Bay

        if (eines(w, "ad", "ads", "advert", "advertisement", "video", "videos", "movie", "movies", "theater", "theatre",
                "watch", "commercial")) {
            return Zuordnung.von(Ziel.WERBUNG);
        }
        return Zuordnung.von(Ziel.UNBEKANNT);
    }

    // ------------------------------------------------------------------ Progress x/y

    /** Zahl mit Tausender-Leerzeichen ("1 500") oder einfach Ziffern. */
    private static final Pattern BRUCH = Pattern.compile("(\\d{1,3}(?:[ .,]\\d{3})+|\\d+)\\s*/\\s*(\\d{1,3}(?:[ .,]\\d{3})+|\\d+)");
    /** "Progress" mit typischen Lesefehlern ("Pr0gress", "Progres", "rogress", "Progess"). */
    private static final Pattern PROGRESS = Pattern.compile("pr?[o0]gr?[e3]s{1,2}|r[o0]gres{1,2}|pr[o0]gr",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ein Bruch "x/y" irgendwo in der Zeile -> {stand, ziel}. Verlesene Ziffern direkt am Bruch werden repariert
     * ("l/3" -> 1/3, "O / 5" -> 0/5). Keiner da, oder Ziel 0: null.
     */
    static long[] bruch(String zeile) {
        if (zeile == null || zeile.indexOf('/') < 0) return null;
        // O/o und l/I/| nur dort, wo sie zwischen Ziffern, Leerzeichen und "/" stehen -- "Gold" bleibt "Gold"
        String t = zeile.replaceAll("(?<=^|[\\d/\\s])[Oo](?=[\\d/\\s]|$)", "0")
                .replaceAll("(?<=^|[\\d/\\s])[Il|](?=[\\d/\\s]|$)", "1");
        for (int i = 0; i < 2; i++) {   // "lO/l5" braucht zwei Durchgaenge
            t = t.replaceAll("(?<=\\d)[Oo]|[Oo](?=\\d)", "0").replaceAll("(?<=\\d)[Il|]|[Il|](?=\\d)", "1");
        }
        Matcher m = BRUCH.matcher(t);
        if (!m.find()) return null;
        String a = m.group(1).replaceAll("[ .,]", ""), b = m.group(2).replaceAll("[ .,]", "");
        if (a.length() > 9 || b.length() > 9) return null;
        long stand = Long.parseLong(a), ziel = Long.parseLong(b);
        // "0/1" las sich als "10/1" (Kante am Balken) -- mehr als das Ziel gibt es nicht
        if (stand > ziel && a.length() > 1 && a.startsWith("1") && Long.parseLong(a.substring(1)) <= ziel) {
            stand = Long.parseLong(a.substring(1));
        }
        if (ziel <= 0) return null;
        return new long[]{Math.min(stand, ziel), ziel};
    }

    /** "Progress 1/3", "Progress: 0 / 5", "Pr0gress l/3", "Progress" und die Zahl auf der naechsten Zeile. */
    static long[] fortschritt(List<String> zeilen) {
        if (zeilen == null) return null;
        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i);
            if (z == null) continue;
            Matcher m = PROGRESS.matcher(z);
            if (!m.find()) continue;
            long[] f = bruch(z.substring(m.end()));
            if (f == null && i + 1 < zeilen.size()) f = bruch(zeilen.get(i + 1));
            if (f != null) return f;
        }
        // kein "Progress" lesbar: eine Zeile, die nur aus dem Bruch besteht
        for (String z : zeilen) {
            if (z != null && z.strip().matches("[\\dOoIl| .,]+/[\\dOoIl| .,]+")) {
                long[] f = bruch(z);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Eine einzelne Zeile. */
    static long[] fortschritt(String zeile) {
        return fortschritt(zeile == null ? null : List.of(zeile));
    }

    // ------------------------------------------------------------------ Laeufe

    /**
     * Noetige Laeufe: was fehlt, geteilt durch das, was ein Lauf bringt (aufgerundet), mindestens 1, hoechstens die
     * Grenze des Ziels. Fortschritt nicht gelesen (ziel <= 0): 1 Lauf -- lieber einmal hin als gar nicht.
     * Erledigt oder der Bot kann es nicht: 0.
     */
    static int laeufe(Zuordnung z, long stand, long ziel) {
        if (z == null || !z.ziel().botKann()) return 0;
        if (ziel > 0 && stand >= ziel) return 0;
        if (ziel <= 0) return 1;
        long offen = ziel - Math.max(0, stand);
        long n = (offen + Math.max(1, z.einheitenJeLauf()) - 1) / Math.max(1, z.einheitenJeLauf());
        return (int) Math.max(1, Math.min(n, z.ziel().hoechstens));
    }

    // ------------------------------------------------------------------ Werbung am PC

    /**
     * 21.11.: am PC zeigt das Spiel bei "Watch" nur den Hinweis "To use this feature, you must restart the game ..." --
     * die Werbung laeuft nie. Merkt sich ein Modus das, gilt es bis zum naechsten Tages-Reset (10:00) fuer ALLE Modi
     * (Burg-Quests): nicht alle 30 min neu versuchen.
     */
    private static volatile java.time.LocalDateTime werbungAusBis;

    /** Tages-Reset der Quests im Spiel (10:00 Ortszeit). */
    static final java.time.LocalTime RESET_UHR = java.time.LocalTime.of(10, 0);

    /** Der naechste Reset nach t: heute 10:00, oder morgen 10:00, wenn es schon 10:00 oder spaeter ist. */
    static java.time.LocalDateTime naechsterReset(java.time.LocalDateTime t) {
        java.time.LocalDateTime heute = t.toLocalDate().atTime(RESET_UHR);
        return t.isBefore(heute) ? heute : heute.plusDays(1);
    }

    /** Werbung fuer heute aus: bis 10:05 nach dem naechsten Reset. */
    static java.time.LocalDateTime werbungAusBerechnen(java.time.LocalDateTime jetzt) {
        return naechsterReset(jetzt).plusMinutes(5);
    }

    static void werbungAus(java.time.LocalDateTime jetzt) {
        werbungAusBis = werbungAusBerechnen(jetzt);
    }

    static boolean werbungAus(java.time.LocalDateTime jetzt, boolean pruefen) {
        java.time.LocalDateTime b = werbungAusBis;
        return b != null && jetzt.isBefore(b);
    }

    /** Nur fuer Tests. */
    static void werbungAusLoeschen() {
        werbungAusBis = null;
    }

    // ------------------------------------------------------------------ eine Quest

    /** Eine gelesene Quest. stand/ziel = -1: nicht gelesen. abgeholt = Belohnung eben abgeholt (Claim). */
    record Quest(String titel, String aufgabe, long stand, long ziel, boolean abgeholt) {

        Zuordnung zuordnung() {
            return zuordnen(titel, aufgabe);
        }

        Ziel zielArt() {
            return zuordnung().ziel();
        }

        boolean erledigt() {
            return abgeholt || ziel > 0 && stand >= ziel;
        }

        /** Was noch fehlt; nicht gelesen: 1. */
        long offen() {
            if (erledigt()) return 0;
            return ziel > 0 ? ziel - Math.max(0, stand) : 1;
        }

        int laeufe() {
            return erledigt() ? 0 : SpielQuests.laeufe(zuordnung(), stand, ziel);
        }

        Quest mitAbgeholt() {
            return new Quest(titel, aufgabe, stand, ziel, true);
        }

        /** Fuers Protokoll. */
        String zeile() {
            Zuordnung z = zuordnung();
            return "\"" + titel + "\"" + (aufgabe == null || aufgabe.isBlank() ? "" : " (" + aufgabe + ")") + ": "
                    + (ziel > 0 ? stand + "/" + ziel : "?/?")
                    + (abgeholt ? de.hustlebot.core.Sprache.t(" claimed", " abgeholt") : erledigt() ? de.hustlebot.core.Sprache.t(" done", " fertig") : "")
                    + " -> " + z.ziel().anzeige() + (laeufe() > 0 ? " x" + laeufe() : "");
        }
    }

    /** Schluessel, unter dem dieselbe Quest nur einmal zaehlt (Name auf zwei Zeilen, nach dem Wischen noch einmal). */
    static String schluessel(String text) {
        return String.join("", woerter(text));
    }

    // ------------------------------------------------------------------ Liste links und Feld rechts

    /** Zeilen der Oberflaeche, die keine Quest sind. */
    private static final Set<String> OBERFLAECHE = Set.of("daily quests", "dailyquests", "daily", "storyline quests",
            "storyline", "quests", "progress", "reward", "rewards", "watch", "show", "claim", "claim reward", "go",
            "completed", "complete", "done", "new", "free", "quest", "task", "tasks", "close");

    /** Ist eine Zeile der Liste links ein Quest-Name? Nicht: Zahlen "0/1", Zeiten "5h 20m", "x10", Oberflaeche. */
    static boolean istQuestName(String zeile) {
        if (zeile == null) return false;
        String t = zeile.strip();
        if (t.isEmpty()) return false;
        if (bruch(t) != null && (t.replaceAll("[^A-Za-z]", "").length() < 4 || PROGRESS.matcher(t).find())) return false;
        List<String> w = woerter(t);
        if (OBERFLAECHE.contains(String.join(" ", w))) return false;
        for (String x : w) {
            if (x.matches("\\d+[dhms]")) return false;    // Restzeit "5h 20m", "Ends in 47s"
        }
        if (w.size() == 1 && w.get(0).matches("x\\d+")) return false;   // Belohnung "x10"
        return t.chars().filter(Character::isLetter).count() >= 4;
    }

    /** Was rechts zur angetippten Quest steht. */
    record Detail(String titel, String aufgabe, long stand, long ziel) {
        Quest alsQuest() {
            return new Quest(titel, aufgabe, stand, ziel, false);
        }
    }

    /**
     * Die Zeilen rechts -> Detail. Titel = der angetippte Name aus der Liste (rechts steht er meist noch einmal oben);
     * Aufgabe = alle uebrigen Textzeilen ohne Progress, Belohnung, Knoepfe und Zahlen.
     */
    static Detail detail(String titelAusListe, List<String> zeilen) {
        long[] f = fortschritt(zeilen);
        String titel = titelAusListe == null ? "" : titelAusListe.strip();
        String titelSchluessel = schluessel(titel);
        List<String> aufgabe = new ArrayList<>();
        if (zeilen != null) {
            for (String roh : zeilen) {
                if (roh == null) continue;
                String z = roh.replaceAll("\\s+", " ").strip();
                if (z.isEmpty()) continue;
                if (PROGRESS.matcher(z).find() && bruch(z) != null) continue;
                if (!istQuestName(z)) continue;
                if (!titelSchluessel.isEmpty() && schluessel(z).equals(titelSchluessel)) continue;
                if (titel.isEmpty()) {
                    titel = z;
                    titelSchluessel = schluessel(z);
                    continue;
                }
                aufgabe.add(z);
            }
        }
        return new Detail(titel, String.join(" ", aufgabe), f == null ? -1 : f[0], f == null ? -1 : f[1]);
    }

    // ------------------------------------------------------------------ Folge-Auftraege

    /** Reihenfolge der Modi in der Warteschlange (Gem Bay, Verlies, Arena, ... Turm zuletzt). */
    static final List<Ziel> REIHENFOLGE = List.of(Ziel.GEMBAY, Ziel.VERLIES, Ziel.ARENA, Ziel.KATHEDRALE,
            Ziel.LEUCHTTURM, Ziel.KOLOSSEUM, Ziel.TURM);

    /**
     * Offene Quests -> Folge-Auftraege. Je Modus EIN Auftrag: ein Lauf zaehlt fuer alle Quests desselben Modus, darum
     * der hoechste Wert, nicht die Summe ("3 Arena-Siege" und "500 Abzeichen" brauchen dieselben Turniere).
     * Werbung, PvP, Clan und Unbekanntes kommen nicht hinein.
     */
    static List<GameMode.Folge> folgen(List<Quest> quests) {
        Map<Ziel, Integer> je = new LinkedHashMap<>();
        if (quests != null) {
            for (Quest q : quests) {
                Ziel z = q.zielArt();
                if (z.modus == null) continue;
                int n = q.laeufe();
                if (n > 0) je.merge(z, n, Math::max);
            }
        }
        List<GameMode.Folge> aus = new ArrayList<>();
        for (Ziel z : REIHENFOLGE) {
            Integer n = je.get(z);
            if (n != null && n > 0) aus.add(new GameMode.Folge(z.modus, n));
        }
        return aus;
    }

    /** Fuers Protokoll und Discord: kurze Zusammenfassung. */
    static String zusammenfassung(List<Quest> quests, List<GameMode.Folge> folgen, int werbungen, int abgeholt) {
        int offen = 0, uebersprungen = 0;
        for (Quest q : quests) {
            if (q.erledigt()) continue;
            if (q.zielArt().botKann()) offen++;
            else uebersprungen++;
        }
        StringBuilder b = new StringBuilder();
        b.append(quests.size()).append(t(" quests read, ", " Quests gelesen, ")).append(offen).append(t(" open", " offen"));
        if (uebersprungen > 0) b.append(", ").append(uebersprungen).append(t(" skipped", " uebersprungen"));
        if (werbungen > 0) b.append(", ").append(werbungen).append(t(" ad(s) watched", " Werbung(en) angesehen"));
        if (abgeholt > 0) b.append(", ").append(abgeholt).append(t(" reward(s) claimed", " Belohnung(en) abgeholt"));
        if (folgen != null && !folgen.isEmpty()) {
            b.append(t(". Then: ", ". Danach: "));
            for (int i = 0; i < folgen.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(folgen.get(i).modus()).append(" x").append(folgen.get(i).runs());
            }
        }
        return b.toString();
    }

    private static String t(String en, String deutsch) {
        return de.hustlebot.core.Sprache.t(en, deutsch);
    }
}
