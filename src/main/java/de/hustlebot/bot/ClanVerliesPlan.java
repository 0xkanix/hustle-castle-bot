package de.hustlebot.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Die Entscheidungen fuer die Event-Quest "Go to a Dungeon with a clan mate" (Autor 18./19.11.) -- ohne Bild, ohne Maus,
 * damit sie sich in Tests pruefen lassen. DungeonMode fragt hier, was als Naechstes dran ist; VerliesClan macht die
 * Klicks.
 *
 * Zustaende:
 *  FRAGEN      -- noch nicht im Clan-Chat gefragt.
 *  EINGELADEN  -- gefragt; die Einladung im Verlies ("Clan" -> Nachricht -> Send) geht raus bzw. ist raus.
 *  WARTEN      -- "Waiting for a clan mate" steht. Hoechstens 10 Minuten (Autor 19.11. 20:42); ab und zu in den Clan-Chat
 *                 schauen (sofort, wenn am Chat-Pfeil ein rotes Abzeichen mit neuen Nachrichten steht).
 *  SPAETER     -- jemand schrieb "in 5 min": nach 5 Minuten neu einladen (Autor 19.11. 20:38).
 *  ANGENOMMEN  -- ein Clan-Kamerad ist im Verlies: spielen hat Vorrang vor allem.
 *  AUFGEGEBEN  -- niemand online, Absage ("can't") oder 10 Minuten um: Einladung zurueck, spaeter noch einmal.
 *
 * "Thanks" (Autor 19.11. 20:53): beim vorletzten Stuetzpunkt (nur noch einer offen, Balken ab ~80-90 %) im Verlies-Chat,
 * solange der Mitspieler noch da ist. Ist er schon weg ("Your ally left the Dungeon" / "The other player left the
 * Dungeon"), kommt das Thanks stattdessen in den Clan-Chat (Autor 19.11. 21:25: "nicht weglassen").
 */
final class ClanVerliesPlan {

    enum Zustand { FRAGEN, EINGELADEN, WARTEN, SPAETER, ANGENOMMEN, AUFGEGEBEN }

    /** Was eine Zeile im Clan-Chat bedeutet. */
    enum Antwort { ZUSAGE, SPAETER, ABSAGE, SONST }

    /** Was im Fenster "Waiting for a clan mate" jetzt zu tun ist. */
    enum Schritt { WARTEN, CHAT_ANSEHEN, NEU_EINLADEN, ABBRECHEN }

    /** Wohin das "Thanks" geht (KEINER = jetzt nicht). */
    enum Dank { KEINER, VERLIES_CHAT, CLAN_CHAT }

    /** Hoechstens so lange auf ein Clan-Mitglied warten (Autor 19.11. 20:42: "bis zu 10 min"). */
    static final long WARTEN_MS = 10 * 60_000L;
    /** Auch mit "in 5 min" und Zusagen nie laenger als so lange ab der ersten Einladung. */
    static final long HOECHSTENS_MS = 20 * 60_000L;
    /** Ohne Abzeichen so oft in den Clan-Chat schauen. */
    static final long CHAT_BLICK_MS = 90_000L;
    /** Mit rotem Abzeichen am Chat-Pfeil (neue Nachrichten) schon nach so kurzer Zeit wieder. */
    static final long ABZEICHEN_BLICK_MS = 20_000L;
    /** "in 5 min": so lange, bis neu eingeladen wird. */
    static final long SPAETER_MS = 5 * 60_000L;
    /** Nach der neuen Einladung noch so lange warten. */
    static final long NACH_NEU_EINLADEN_MS = 5 * 60_000L;
    /** Schreibt jemand "yes", noch mindestens so lange warten. */
    static final long ZUSAGE_PUFFER_MS = 3 * 60_000L;
    /**
     * Ab diesem Fortschritt ist nur noch ein Stuetzpunkt offen. Gemessen 19.11.: 7 Stuetzpunkte = 0/15/29/43/58/72/86/100 %
     * -- bei 86 % ist nur noch der letzte offen; bei 5 Stuetzpunkten 80 %, bei 6 83 %. Bei 100 % wird nur noch gedankt,
     * wenn der Mitspieler noch da ist (19.11.: da war er schon raus).
     */
    static final int DANK_AB_PROZENT = 80;

    private Zustand zustand = Zustand.FRAGEN;
    private String grund = "";
    private long eingeladenUm;
    private long wartenBis;
    private long neuEinladenUm;
    private long letzterBlickUm;
    private boolean ersterBlick = true;
    private final Set<String> gesehen = new HashSet<>();
    private final List<String> eigeneTexte = new ArrayList<>();
    private String letzteAntwortZeile = "";

    private boolean gedankt;
    private boolean dankAusgelassen;
    private boolean mitspielerWeg;
    private boolean antwortGeprueft;

    ClanVerliesPlan() {
        this(List.of());
    }

    /** @param eigeneTexte was der Bot selbst schreibt (Frage, Einladungstext) -- das ist nie eine Antwort */
    ClanVerliesPlan(List<String> eigeneTexte) {
        for (String t : eigeneTexte) this.eigeneTexte.add(norm(t));
    }

    Zustand zustand() {
        return zustand;
    }

    /** Warum aufgegeben wurde (fuer Protokoll und Hinweis). */
    String grund() {
        return grund;
    }

    /** Die Zeile, die zuletzt als Antwort gewertet wurde (fuer die Meldung an Autor). */
    String letzteAntwortZeile() {
        return letzteAntwortZeile;
    }

    long wartenBis() {
        return wartenBis;
    }

    // ---- 1. Fragen und einladen -------------------------------------------

    /** "x/34 online" gelesen. 0 = niemand kann annehmen -> aufgeben. Null (unlesbar) aendert nichts. */
    void onlineGelesen(Integer online) {
        if (online != null && online == 0 && zustand != Zustand.ANGENOMMEN) {
            aufgeben("niemand online (0 online im Clan-Chat)");
        }
    }

    /** Die Frage im Clan-Chat ist raus (oder ging nicht -- das Verlies geht trotzdem). */
    void gefragt() {
        if (zustand == Zustand.FRAGEN) zustand = Zustand.EINGELADEN;
    }

    /** Die Einladung im Verlies ist raus ("Send"). */
    void eingeladen(long jetzt) {
        if (zustand == Zustand.AUFGEGEBEN || zustand == Zustand.ANGENOMMEN) return;
        if (eingeladenUm == 0) {
            eingeladenUm = jetzt;
            wartenBis = jetzt + WARTEN_MS;
        }
        neuEinladenUm = 0;
        letzterBlickUm = jetzt;
        zustand = Zustand.EINGELADEN;
    }

    /** Das Fenster "Waiting for a clan mate" ist zu sehen. */
    void wartefensterGesehen(long jetzt) {
        if (eingeladenUm == 0) eingeladen(jetzt);
        if (zustand == Zustand.EINGELADEN) zustand = Zustand.WARTEN;
    }

    /** Ein Clan-Kamerad ist im Verlies (Karte, Aufstellung ...). */
    void angenommen() {
        if (zustand != Zustand.AUFGEGEBEN) zustand = Zustand.ANGENOMMEN;
    }

    boolean aufgegeben() {
        return zustand == Zustand.AUFGEGEBEN;
    }

    private void aufgeben(String warum) {
        if (zustand == Zustand.AUFGEGEBEN) return;
        zustand = Zustand.AUFGEGEBEN;
        grund = warum;
    }

    // ---- 2. Warten -------------------------------------------------------

    /**
     * Ein Blick auf "Waiting for a clan mate": was jetzt?
     *
     * @param abzeichen am Chat-Pfeil links steht ein rotes Abzeichen (neue Nachrichten)
     */
    Schritt imWartefenster(long jetzt, boolean abzeichen) {
        if (zustand == Zustand.AUFGEGEBEN) return Schritt.ABBRECHEN;
        wartefensterGesehen(jetzt);
        if (zustand == Zustand.SPAETER && neuEinladenUm > 0 && jetzt >= neuEinladenUm) return Schritt.NEU_EINLADEN;
        if (jetzt >= wartenBis) {
            aufgeben("in " + ((wartenBis - eingeladenUm) / 60_000) + " Minuten hat niemand angenommen");
            return Schritt.ABBRECHEN;
        }
        long seit = jetzt - letzterBlickUm;
        if (abzeichen && seit >= ABZEICHEN_BLICK_MS) return Schritt.CHAT_ANSEHEN;
        if (seit >= CHAT_BLICK_MS) return Schritt.CHAT_ANSEHEN;
        return Schritt.WARTEN;
    }

    /**
     * Was im Clan-Chat steht (Zeilen von oben nach unten, neueste unten). Gewertet werden nur Zeilen, die neu sind und
     * NACH der eigenen Frage stehen; eigene Texte, Namen, Zeitangaben und Knoepfe zaehlen nie.
     *
     * Mehrere Antworten: eine Zusage geht vor "spaeter", "spaeter" vor einer Absage (einer kann ja trotzdem kommen).
     *
     * @return die staerkste neue Antwort (SONST = nichts Neues, das etwas aendert)
     */
    Antwort chatGelesen(List<String> zeilen, Integer online, long jetzt) {
        letzterBlickUm = jetzt;
        onlineGelesen(online);
        if (zustand == Zustand.AUFGEGEBEN || zustand == Zustand.ANGENOMMEN || zeilen == null) return Antwort.SONST;

        int ab = 0;
        boolean frageGefunden = false;
        for (int i = 0; i < zeilen.size(); i++) {
            if (istFrage(zeilen.get(i))) {
                ab = i + 1;
                frageGefunden = true;
            }
        }
        // Steht die eigene Frage beim ersten Blick nicht (mehr) da, ist unklar, was davor und danach kam: alles
        // Vorhandene nur merken, erst spaeter Neues werten (sonst zaehlte ein altes "no" als Absage).
        boolean nurMerken = ersterBlick && !frageGefunden;
        ersterBlick = false;

        Antwort beste = Antwort.SONST;
        String besteZeile = "";
        for (int i = 0; i < zeilen.size(); i++) {
            String z = zeilen.get(i);
            String n = norm(z);
            if (n.isEmpty()) continue;
            boolean neu = gesehen.add(n);
            if (!neu || nurMerken || i < ab || istEigen(n)) continue;
            Antwort a = einordnen(z);
            if (rang(a) > rang(beste)) {
                beste = a;
                besteZeile = z.trim();
            }
        }
        if (beste != Antwort.SONST) letzteAntwortZeile = besteZeile;
        switch (beste) {
            case ZUSAGE -> wartenBis = Math.min(Math.max(wartenBis, jetzt + ZUSAGE_PUFFER_MS), obergrenze());
            case SPAETER -> {
                zustand = Zustand.SPAETER;
                neuEinladenUm = jetzt + SPAETER_MS;
                wartenBis = Math.min(Math.max(wartenBis, neuEinladenUm + NACH_NEU_EINLADEN_MS), obergrenze());
            }
            case ABSAGE -> aufgeben("Absage im Clan-Chat: \"" + besteZeile + "\"");
            default -> { }
        }
        return beste;
    }

    private long obergrenze() {
        return eingeladenUm + HOECHSTENS_MS;
    }

    private static int rang(Antwort a) {
        return switch (a) {
            case ZUSAGE -> 3;
            case SPAETER -> 2;
            case ABSAGE -> 1;
            case SONST -> 0;
        };
    }

    private boolean istFrage(String zeile) {
        String n = norm(zeile);
        return !eigeneTexte.isEmpty() && n.length() >= 8 && eigeneTexte.get(0).contains(n);
    }

    /** Teil eines eigenen Textes (die Texterkennung bricht lange Saetze in mehrere Zeilen). */
    private boolean istEigen(String n) {
        if (n.length() < 8) return false;
        for (String t : eigeneTexte) {
            if (t.contains(n)) return true;
        }
        return false;
    }

    // ---- Zeilen einordnen ----------------------------------------------------

    /** Zeilen, die nie eine Antwort sind: Zeitangaben, Rollen, Knoepfe, Kopf des Chats. */
    private static final Pattern RAUSCHEN = Pattern.compile(
            "\\bago\\b|just now|^you$|^cancel$|^accept$|^decline$|is inviting you|message of the day|\\bonline\\b"
            + "|^(officer|member|veteran|leader|squad leaders?|recruit|deputy)$");
    private static final Pattern SPAETER_MUSTER = Pattern.compile(
            "\\bin\\s*\\d+\\s*(min|mins|minutes?|m)\\b|\\b\\d+\\s*(min|mins|minutes)\\b|\\bfew min|\\bgive me\\b|\\bbrb\\b"
            + "|\\blater\\b|\\bin a (bit|sec|second|minute|min)\\b|\\bone (sec|second|min|minute)\\b|\\bsoon\\b"
            + "|\\bafter (this|that|my)\\b|\\bwait\\b");
    private static final Pattern ABSAGE_MUSTER = Pattern.compile(
            "can'?t|cannot|can not|not now|not ready|\\bbusy\\b|\\bno time\\b|\\bnope\\b|not today|\\bsorry\\b|\\bnah\\b");
    private static final Pattern ZUSAGE_MUSTER = Pattern.compile(
            "\\byes\\b|\\byep\\b|\\byeah\\b|\\bya\\b|\\bsure\\b|\\bok\\b|\\bokay\\b|\\bk\\b|i'?m in\\b|\\bcoming\\b|\\bjoin(ing|ed)?\\b"
            + "|\\baccept(ed|ing)?\\b|on my way|let'?s go|\\bi can\\b|i'?ll (do|join|come|go)|\\bready\\b|no problem|\\bnp\\b"
            + "|\\bgo go\\b|\\bwith you\\b");

    /**
     * Eine Zeile aus dem Clan-Chat einordnen (Autor 19.11.: "in 5 min" -> spaeter neu einladen; "can't" -> aufgeben).
     * Reihenfolge: erst "spaeter" (auch "sorry, in 5 min"), dann Absage, dann Zusage. "no" allein ist nur in ganz kurzen
     * Zeilen eine Absage -- in laengeren Saetzen steht es zu oft in anderem Sinn.
     */
    static Antwort einordnen(String zeile) {
        if (zeile == null) return Antwort.SONST;
        String n = zeile.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").trim();
        if (n.isEmpty() || RAUSCHEN.matcher(n).find()) return Antwort.SONST;
        if (SPAETER_MUSTER.matcher(n).find()) return Antwort.SPAETER;
        boolean np = n.contains("no problem") || n.matches(".*\\bnp\\b.*");
        if (!np && ABSAGE_MUSTER.matcher(n).find()) return Antwort.ABSAGE;
        if (!np && n.matches("(no|no\\W*thanks?|not me|no sorry)\\W*")) return Antwort.ABSAGE;
        if (ZUSAGE_MUSTER.matcher(n).find()) return Antwort.ZUSAGE;
        return Antwort.SONST;
    }

    /** Fuer den Vergleich: klein, nur Buchstaben/Ziffern, einfache Leerzeichen. */
    static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    // ---- 3. "Thanks" ----------------------------------------------------------

    /**
     * Jetzt "Thanks" schreiben, und wohin? Nur einmal, ab dem vorletzten Stuetzpunkt (Balken ab DANK_AB_PROZENT);
     * unbekannter Fortschritt (-1): nein. Ist der Mitspieler noch da: Verlies-Chat. Ist er schon weg: Clan-Chat
     * (Autor 19.11. 21:25 -- nicht weglassen).
     *
     * @param mitspielerWegGesehen "Your ally left the Dungeon" ist auf der Karte zu sehen
     */
    Dank dankJetzt(int prozent, boolean mitspielerWegGesehen) {
        if (mitspielerWegGesehen) mitspielerWeg = true;
        if (gedankt || dankAusgelassen || prozent < DANK_AB_PROZENT) return Dank.KEINER;
        return mitspielerWeg ? Dank.CLAN_CHAT : Dank.VERLIES_CHAT;
    }

    void gedankt() {
        gedankt = true;
    }

    /** Ging nicht (Chat nicht auf, Reiter nicht sicher): nicht noch einmal versuchen -- spielen hat Vorrang. */
    void dankAusgelassen() {
        dankAusgelassen = true;
    }

    /** Der Mitspieler hat das Verlies verlassen (Karte oder Verlies-Chat): Thanks nur noch im Clan-Chat, keine Antwort. */
    void mitspielerWeg() {
        mitspielerWeg = true;
    }

    boolean istGedankt() {
        return gedankt;
    }

    boolean istMitspielerWeg() {
        return mitspielerWeg;
    }

    /**
     * Am Ende (100 %, vor "End journey") einmal nachsehen, ob der Mitspieler auf das Thanks geantwortet hat -- nur wenn
     * vorher gedankt wurde und er noch da ist.
     */
    boolean antwortPruefenJetzt(int prozent, boolean mitspielerWegGesehen) {
        if (mitspielerWegGesehen) mitspielerWeg = true;
        return gedankt && !antwortGeprueft && !mitspielerWeg && prozent >= 100;
    }

    void antwortGeprueft() {
        antwortGeprueft = true;
    }

    /** Steht im Verlies-Chat, dass der andere gegangen ist ("The other player left the Dungeon")? */
    static boolean mitspielerWegImText(List<String> zeilen) {
        if (zeilen == null) return false;
        for (String z : zeilen) {
            String n = norm(z);
            if (n.contains("other player left") || n.contains("ally left") || n.contains("player left the dungeon")) return true;
        }
        return false;
    }

    /**
     * Was der Mitspieler nach dem eigenen Text geschrieben hat (Zeilen unter dem letzten eigenen Text, ohne "you",
     * Zeitangaben und Systemzeilen). Leer = nichts.
     */
    static List<String> neuNach(List<String> zeilen, String eigenerText) {
        List<String> neu = new ArrayList<>();
        if (zeilen == null) return neu;
        String eigen = norm(eigenerText);
        int ab = -1;
        for (int i = 0; i < zeilen.size(); i++) {
            if (norm(zeilen.get(i)).equals(eigen)) ab = i;
        }
        if (ab < 0) return neu;
        for (int i = ab + 1; i < zeilen.size(); i++) {
            String z = zeilen.get(i);
            String n = norm(z);
            if (n.isEmpty() || n.equals(eigen) || n.equals("you") || RAUSCHEN.matcher(n).find()) continue;
            if (n.contains("welcome to the joint journey") || n.contains("be polite") || n.contains("behavior is prohibited")) continue;
            neu.add(z.trim());
        }
        return neu;
    }
}
