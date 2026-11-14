package de.hustlebot.schicht;

import de.hustlebot.core.Sprache;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Die Bedienung der Arena-Schicht OHNE Maus -- reine Logik, ohne Bildschirm und ohne Windows, darum testbar. Die
 * Mauswache stoppt den Bot bei jeder Mausbewegung; deshalb geht alles ueber die Tastatur.
 *
 * Autor 29.10. abends: "so einfach wie moeglich mit der Tastatur", gewaehlt wird nur noch in der Liste im
 * Seitenfenster (dort stehen alle Gegner), keine Maustasten mehr:
 * <pre>
 *   ↑ ↓            Gegner in der Liste des Seitenfensters waehlen
 *   1 2 3 4        sofort speichern: 1 sicher, 2 machbar, 3 meiden, 4 niemals
 *   0              aus der Liste nehmen
 *   Ruecktaste     letzte Aenderung zuruecknehmen
 *   K              Kommentar tippen (Enter schickt ab, Esc bricht ab)
 *   Leertaste      Rechenzettel der Auswahl an/aus
 *   Esc            Zettel bzw. Auswahl schliessen
 * </pre>
 *
 * FAEDEN. taste() und maus() kommen direkt aus dem Eingabe-Haken und muessen sofort antworten (ob die Taste
 * verschluckt wird). Darum wird hier nur im Speicher gerechnet: Befehle gehen an einen Rueckruf, der sie nur in eine
 * Schlange legt, Notizen (Datei + Discord) an einen, der sie an einen anderen Faden weiterreicht. Alles ist
 * synchronized, die Sperre wird nur Mikrosekunden gehalten.
 */
final class SchichtSteuerung {

    // ---- Tastencodes (WinUser.h) -------------------------------------------------------------------------------
    static final int VK_BACK = 0x08;
    static final int VK_TAB = 0x09;
    static final int VK_RETURN = 0x0D;
    static final int VK_ESCAPE = 0x1B;
    static final int VK_SPACE = 0x20;
    static final int VK_LEFT = 0x25;
    static final int VK_UP = 0x26;
    static final int VK_RIGHT = 0x27;
    static final int VK_DOWN = 0x28;
    static final int VK_A = 'A';
    static final int VK_C = 'C';
    static final int VK_D = 'D';
    /** Die Taste links neben der 1 auf Autors deutscher Tastatur ("^"): Seitenfenster weg und wieder da. */
    static final int VK_DACH = 0xDC;
    static final int VK_G = 'G';
    static final int VK_J = 'J';
    static final int VK_N = 'N';
    static final int VK_K = 'K';
    static final int VK_L = 'L';
    static final int VK_S = 'S';
    static final int VK_W = 'W';
    private static final int VK_NUMPAD1 = 0x61;

    /** So lange bleibt eine Meldung ("gespeichert ...") stehen. */
    static final long MELDUNG_MS = 6_000;
    /** So lange wartet die Grund-Frage nach dem Ablehnen. */
    static final long GRUND_MS = 30_000;
    /** Ein angefangener Kommentar ohne Tastendruck so lange -- dann verworfen (Autor ist wohl weg). */
    static final long KOMMENTAR_MS = 180_000;
    /** Laengster Kommentar. */
    static final int KOMMENTAR_ZEICHEN = 300;
    /** So viele gespeicherte Aenderungen lassen sich zuruecknehmen. */
    static final int VERLAUF = 20;
    /** Liegen zwei Staende so weit auseinander, war die Pyramide weg (Kampf, neues Turnier): neue Runde. */
    static final long LUECKE_MS = 25_000;

    enum Modus { NORMAL, KOMMENTAR, GRUND }

    /** Die Stufen von gefaehrlich nach sicher: ↑ geht eins nach rechts, ↓ eins nach links (Autor 28.10.). */
    static final List<String> LEITER = List.of("neutral", "niemals", "pause", "meiden", "machbar", "fastsicher", "sicher");
    /** Rechtsklick (Bot spielt nicht): 1× sicher, 2× machbar, 3× meiden, 4× niemals, 5× wieder neutral. */
    static final List<String> KREIS = List.of("neutral", "sicher", "machbar", "meiden", "niemals");
    /** C / Mausrad-Klick: kein Vermerk → starker Clan (+8) → schwacher Clan (−4) → kein Vermerk. */
    static final List<String> CLANS = List.of("nichts", "stark", "schwach");
    /** Gruende nach dem Ablehnen, Taste 1-4. */
    static final List<String> GRUENDE = List.of("zu_stark", "starker_clan", "anderer", "sonstiges");
    /** Autor 30.10.: die Art eines Kommentars ("Knoepfe + freier Text"); Tab geht reihum, "" = keine Art gewaehlt. */
    static final List<String> KOMMENTAR_ARTEN = List.of("warum_angegriffen", "chance_zu_hoch", "chance_zu_niedrig");

    // Klicks im Seitenfenster, die keine Zeile treffen (Zeile = ihr Platz, 0 = daneben, -1 = nichts).
    static final int KLICK_SENDEN = -2;
    static final int KLICK_ABBRECHEN = -3;
    static final int KLICK_ZUSTIMMEN = -4;
    static final int KLICK_ABLEHNEN = -5;
    static final int KLICK_KOMMENTAR = -6;
    static final int KLICK_STAGE_KOMMENTAR = -7;
    /** Das Minus oben rechts: erledigt ArenaSchicht, die Steuerung sieht es nie. */
    static final int KLICK_WEG = -8;
    /** Autor 30.10.: "zu den einzelnen Spielern einen Kommentar schreiben koennen, aber auch zu der Stage." Steht als Art im Kommentar. */
    static final String STAGE_ART = "stage";
    /** Erste Art; die uebrigen darunter: -10, -11, -12. */
    static final int KLICK_ART = -10;
    /** Erster Grund nach dem Ablehnen; die uebrigen darunter: -20 bis -23. */
    static final int KLICK_GRUND = -20;

    /** Was Windows zu einer Taste weiss -- im Test ein Platzhalter. */
    interface Tastatur {
        /** Was die Taste tippt (mit Umschalt/AltGr), "" wenn nichts. */
        String zeichen(int vk);

        /** Wird gerade Alt (links), die Windows-Taste oder Strg (ohne AltGr) gehalten? Dann gehoert die Taste Windows. */
        boolean kombi();
    }

    /** Eine Zeile fuer arena-kommentare.txt und Discord (Kommentar, Zustimmen, Ablehnen). */
    record Notiz(long um, ArenaStand.Befehl.Art art, int platz, String name, String clan, String botZiel,
                 String wert, String text) { }

    /**
     * Was Seitenfenster und Rahmen zeigen sollen -- unveraenderlich; equals sagt der Schicht, ob neu zu zeichnen ist.
     *
     * @param auswahl      gewaehlter Platz, 0 = keiner
     * @param bezugPlatz   Kommentar/Grund-Frage: um welchen Platz es geht
     * @param eingabeGrund der Kommentar ist der Grund zum Ablehnen
     * @param stufen       gerade gespeicherte Stufen je Platz (bis der Bot sie selbst meldet)
     * @param clans        dasselbe fuer den Clan
     * @param kommentarArt die gewaehlte Art des Kommentars, "" = keine
     */
    record Anzeige(int auswahl, String vormerkStufe, String vormerkClan, boolean zettel, boolean liste, Modus modus,
                   int bezugPlatz, String bezugName, boolean eingabeGrund, String eingabe,
                   String meldung, boolean meldungGut, Map<Integer, String> stufen, Map<Integer, String> clans,
                   String kommentarArt) {

        static final Anzeige LEER = new Anzeige(0, null, null, false, false, Modus.NORMAL, 0, "", false, "",
                null, false, Map.of(), Map.of(), "");

        public Anzeige {
            stufen = Map.copyOf(stufen);
            clans = Map.copyOf(clans);
        }

        /** Bedient Autor gerade etwas? Dann zeigt sich das Seitenfenster auch ohne Rechnung des Bots. */
        boolean hatEtwas() {
            return auswahl != 0 || modus != Modus.NORMAL || liste || meldung != null;
        }

        boolean vorgemerkt() {
            return vormerkStufe != null || vormerkClan != null;
        }
    }

    /** Ein Stueck einer gespeicherten Aenderung (fuer die Ruecktaste). */
    private record Teil(ArenaStand.Befehl.Art art, int platz, String alt, String neu, String name) { }

    private record Aenderung(int rundeNr, List<Teil> teile) { }

    private final Tastatur tastatur;
    private final Consumer<ArenaStand.Befehl> befehle;
    private final Consumer<Notiz> notizen;

    /** volatile: der Haken fragt modal() ohne Sperre. */
    private volatile Modus modus = Modus.NORMAL;
    private int runde = Integer.MIN_VALUE;
    private long standUm;
    /** Zaehlt jede neue Runde -- gespeicherte Aenderungen lassen sich nur in derselben zuruecknehmen. */
    private int rundeNr;

    private int auswahl;
    private String vormerkStufe;
    private String vormerkClan;
    private boolean zettel;
    private boolean liste;

    // Kommentar / Grund-Frage
    private final StringBuilder eingabe = new StringBuilder();
    private String kommentarArt = "";
    private boolean eingabeGrund;
    private long eingabeZuletzt;
    private int bezugPlatz;
    private String bezugName = "";
    private String bezugClan = "";
    private String bezugZiel = "";
    private long bezugSeit;

    private String meldung;
    private boolean meldungGut;
    private long meldungBis;

    private final Map<Integer, String> stufen = new HashMap<>();
    private final Map<Integer, String> clans = new HashMap<>();
    private final Deque<Aenderung> verlauf = new ArrayDeque<>();

    SchichtSteuerung(Tastatur tastatur, Consumer<ArenaStand.Befehl> befehle, Consumer<Notiz> notizen) {
        this.tastatur = tastatur;
        this.befehle = befehle;
        this.notizen = notizen;
    }

    /** Laeuft ein Kommentar oder die Grund-Frage? Dann zaehlen Tasten auch, wenn die Pyramide kurz weg ist. Ohne Sperre. */
    boolean modal() {
        return modus != Modus.NORMAL;
    }

    /**
     * Tasten, die die Schicht nie anfasst: F1-F24 (F9 Not-Aus, F10 Pause, F11 Weiter!), Umschalt, Strg, Alt,
     * Windows-Taste, Feststell, Druck, Num, Rollen, Medientasten. Die Umschalter gehen auch beim Tippen ans Spiel --
     * nur so kennt Windows Umschalt/AltGr fuer die Gross- und Sonderzeichen.
     */
    static boolean nieSchlucken(int vk) {
        return (vk >= 0x70 && vk <= 0x87)
                || (vk >= 0x10 && vk <= 0x14)
                || vk == 0x2C
                || vk == 0x5B || vk == 0x5C || vk == 0x5D
                || vk == 0x90 || vk == 0x91
                || (vk >= 0xA0 && vk <= 0xB7);
    }

    // ---- Eingaben ------------------------------------------------------------------------------------------------

    /**
     * Ein Tastendruck, solange das Spiel vorn ist und die Pyramide zu sehen ist (oder ein Kommentar laeuft).
     *
     * @param s der aktuelle Stand, null = Pyramide gerade weg
     * @return true = verschlucken (das Spiel sieht die Taste nicht)
     */
    synchronized boolean taste(int vk, ArenaStand.Stand s, long jetzt) {
        if (nieSchlucken(vk)) return false;
        abgleich(s, jetzt);
        if (tastatur.kombi()) return false;   // Alt+Tab, Win+D, Strg+...: gehoeren Windows und dem Spiel
        return switch (modus) {
            case KOMMENTAR -> kommentarTaste(vk, jetzt);
            case GRUND -> grundTaste(vk, s, jetzt);
            case NORMAL -> normalTaste(vk, s, jetzt);
        };
    }

    /**
     * Eine Maustaste. RECHTS/MITTE nur, wenn der Bot NICHT spielt (keine Rechnung im Stand) und auf ein Schild
     * geklickt wurde; SEITE immer, solange die Pyramide da ist.
     *
     * @param platz der Platz unter dem Mauszeiger (ArenaSchicht.platzBei), 0 = keiner
     */
    synchronized boolean maus(EingabeHaken.Art art, boolean vorne, int platz, ArenaStand.Stand s, long jetzt) {
        if (!EingabeHaken.MAUS) return false;   // Autor 29.10.: keine Maustasten mehr
        abgleich(s, jetzt);
        if (art == EingabeHaken.Art.SEITE) {
            if (modus == Modus.KOMMENTAR) return true;   // beim Tippen nichts ausloesen, aber auch nicht ans Spiel
            if (modus == Modus.GRUND) grundOhne(jetzt);
            if (s == null) return false;
            if (vorne) zustimmen(s, jetzt);
            else ablehnen(s, jetzt);
            return true;
        }
        if (s == null || !s.gegner().isEmpty() || platz <= 0) return false;
        if (art == EingabeHaken.Art.RECHTS) {
            auswahlSetzen(platz, jetzt);
            String eff = stufeVon(s, stufen, platz);
            String neu = KREIS.get((Math.max(0, KREIS.indexOf(eff)) + 1) % KREIS.size());
            speichernTeile(List.of(new Teil(ArenaStand.Befehl.Art.LISTE, platz, eff, neu, nameVon(s, platz))), jetzt);
            return true;
        }
        if (art == EingabeHaken.Art.MITTE) {
            auswahlSetzen(platz, jetzt);
            String eff = clanVon(s, clans, platz);
            String neu = CLANS.get((CLANS.indexOf(eff) + 1) % CLANS.size());
            speichernTeile(List.of(new Teil(ArenaStand.Befehl.Art.CLAN, platz, eff, neu, nameVon(s, platz))), jetzt);
            return true;
        }
        return false;
    }

    /**
     * Klick im Seitenfenster. Autor 30.10.: "Im Seitenfenster mit der Maus einen Spieler anklicken und einen Kommentar schreiben."
     * Das Fenster nimmt nie die Tastatur: das Spiel bleibt vorn, getippt wird ueber den Haken wie sonst auch.
     *
     * @param platz die angeklickte Zeile, 0 = daneben, oder ein KLICK_-Wert
     * @param s     was das Seitenfenster gerade zeigt (null = nichts)
     */
    synchronized void klick(int platz, ArenaStand.Stand s, long jetzt) {
        if (platz == -1) return;
        if (platz <= KLICK_GRUND) {
            int i = KLICK_GRUND - platz;
            if (modus == Modus.GRUND && i < GRUENDE.size()) grundTaste('1' + i, s, jetzt);
            return;
        }
        // Autor 30.10.: "einen Knopf, dass ich dem Bot zustimmen kann: Ja, das passt."
        if (platz == KLICK_ZUSTIMMEN || platz == KLICK_ABLEHNEN) {
            if (modus != Modus.NORMAL || s == null) return;
            if (platz == KLICK_ZUSTIMMEN) zustimmen(s, jetzt);
            else ablehnen(s, jetzt);
            return;
        }
        if (platz == KLICK_KOMMENTAR || platz == KLICK_STAGE_KOMMENTAR) {
            if (modus != Modus.NORMAL || s == null) return;
            if (platz == KLICK_KOMMENTAR) kommentarStarten(s, jetzt);
            else stageKommentarStarten(s, jetzt);
            return;
        }
        if (platz <= KLICK_ART) {
            int i = KLICK_ART - platz;
            if (modus == Modus.KOMMENTAR && i < KOMMENTAR_ARTEN.size()) {
                eingabeZuletzt = jetzt;
                String art = KOMMENTAR_ARTEN.get(i);
                kommentarArt = art.equals(kommentarArt) ? "" : art;
            }
            return;
        }
        if (platz == KLICK_SENDEN) {
            if (modus == Modus.KOMMENTAR) kommentarAbschicken(jetzt);
            return;
        }
        if (platz == KLICK_ABBRECHEN) {
            if (modus == Modus.KOMMENTAR) kommentarAbbrechen(jetzt, Sprache.t("Comment cancelled.", "Kommentar abgebrochen."));
            else if (modus == Modus.GRUND) grundOhne(jetzt);
            return;
        }
        if (platz == 0) {
            zettel = false;
            return;
        }
        if (modus != Modus.NORMAL || s == null) return;   // ein angefangener Kommentar geht nicht durch einen Klick verloren
        auswahlSetzen(platz, jetzt);
        kommentarStarten(s, jetzt);
    }

    /** Fuer den Takt: Zeitablauf pruefen und den Zustand fuer die Anzeige holen. */
    synchronized Anzeige anzeige(ArenaStand.Stand s, long jetzt) {
        abgleich(s, jetzt);
        return new Anzeige(auswahl, vormerkStufe, vormerkClan, zettel, liste, modus, bezugPlatz, bezugName,
                eingabeGrund, eingabe.toString(), meldung, meldungGut, stufen, clans, kommentarArt);
    }

    // ---- Normalbetrieb -------------------------------------------------------------------------------------------

    private boolean normalTaste(int vk, ArenaStand.Stand s, long jetzt) {
        if (s == null) return false;
        // Autor 30.10.: "Ich kann keinen Gegner auswaehlen mit WASD."
        if (vk == VK_UP || vk == VK_W || vk == VK_LEFT || vk == VK_A) {
            weiterWaehlen(s, -1, jetzt);
            return true;
        }
        if (vk == VK_DOWN || vk == VK_S || vk == VK_RIGHT || vk == VK_D) {
            weiterWaehlen(s, 1, jetzt);
            return true;
        }
        String stufe = stufeAusZiffer(vk);
        if (stufe != null) {
            stufeSetzen(s, stufe, jetzt);
            return true;
        }
        switch (vk) {
            case VK_SPACE -> {
                if (!zettel) auswahlSicher(s);
                zettel = !zettel;
            }
            case VK_K -> kommentarStarten(s, jetzt);
            case VK_G -> stageKommentarStarten(s, jetzt);
            case VK_J -> zustimmen(s, jetzt);
            case VK_N -> ablehnen(s, jetzt);
            case VK_BACK -> zuruecknehmen(s, jetzt);
            case VK_ESCAPE -> {
                return abbrechen();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Ziffernreihe und Ziffernblock: 1 sicher, 2 machbar, 3 meiden, 4 niemals, 0 aus der Liste; sonst null. */
    static String stufeAusZiffer(int vk) {
        int z = vk >= '0' && vk <= '9' ? vk - '0' : vk >= 0x60 && vk <= 0x69 ? vk - 0x60 : -1;
        return switch (z) {
            case 0 -> "neutral";
            case 1 -> "sicher";
            case 2 -> "machbar";
            case 3 -> "meiden";
            case 4 -> "niemals";
            case 5 -> "fastsicher";
            case 6 -> "pause";
            default -> null;
        };
    }

    /** Eine Ziffer: die Liste des gewaehlten Gegners sofort speichern (ohne Auswahl nur ein Hinweis). */
    private void stufeSetzen(ArenaStand.Stand s, String neu, long jetzt) {
        if (auswahl == 0 || !reihe(s).contains(auswahl)) {
            melden(Sprache.t("First choose an opponent with ↑/↓.", "Erst mit ↑/↓ einen Gegner waehlen."), false, jetzt);
            return;
        }
        String eff = stufeVon(s, stufen, auswahl);
        if (neu.equals(eff)) {
            melden(Sprache.t("Unchanged.", "Unveraendert."), false, jetzt);
            return;
        }
        speichernTeile(List.of(new Teil(ArenaStand.Befehl.Art.LISTE, auswahl, eff, neu, nameVon(s, auswahl))), jetzt);
    }

    /** Die Plaetze in Pyramiden-Reihenfolge (Platz, dann von links), jeder einmal; ohne Plaetze die Gegner. */
    static List<Integer> reihe(ArenaStand.Stand s) {
        List<int[]> l = new ArrayList<>();
        for (ArenaStand.Platz p : s.plaetze()) l.add(new int[]{p.platz(), p.x()});
        if (l.isEmpty()) for (ArenaStand.Gegner g : s.gegner()) l.add(new int[]{g.platz(), g.x()});
        l.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        List<Integer> r = new ArrayList<>();
        for (int[] a : l) if (a[0] > 0 && !r.contains(a[0])) r.add(a[0]);
        return r;
    }

    private void weiterWaehlen(ArenaStand.Stand s, int schritt, long jetzt) {
        List<Integer> r = reihe(s);
        if (r.isEmpty()) return;
        int i = r.indexOf(auswahl);
        int neu;
        if (i < 0) {
            // Erster Druck: beim Bot-Ziel anfangen, sonst am Anfang bzw. Ende.
            int ziel = zielPlatz(s);
            neu = r.contains(ziel) ? ziel : schritt > 0 ? r.get(0) : r.get(r.size() - 1);
        } else {
            neu = r.get(Math.floorMod(i + schritt, r.size()));
        }
        auswahlSetzen(neu, jetzt);
    }

    /** Waehlt um; was fuer den alten Platz vorgemerkt war, verfaellt (mit Hinweis). */
    private void auswahlSetzen(int neu, long jetzt) {
        if (neu != auswahl && (vormerkStufe != null || vormerkClan != null)) {
            melden(Sprache.t("Not saved (moved on) -- press Enter to save.",
                    "Nicht gespeichert (weitergewaehlt) -- Enter speichert."), false, jetzt);
        }
        if (neu != auswahl) {
            vormerkStufe = null;
            vormerkClan = null;
        }
        auswahl = neu;
    }

    /** Ohne Auswahl: das Bot-Ziel, sonst der erste Platz. false, wenn es gar keinen Platz gibt. */
    private boolean auswahlSicher(ArenaStand.Stand s) {
        List<Integer> r = reihe(s);
        if (r.contains(auswahl)) return true;
        if (r.isEmpty()) {
            auswahl = 0;
            return false;
        }
        int ziel = zielPlatz(s);
        auswahl = r.contains(ziel) ? ziel : r.get(0);
        vormerkStufe = null;
        vormerkClan = null;
        return true;
    }

    private void stufeVormerken(ArenaStand.Stand s, boolean sicherer) {
        if (!auswahlSicher(s)) return;
        String eff = stufeVon(s, stufen, auswahl);
        String basis = vormerkStufe != null ? vormerkStufe : eff;
        int i = Math.max(0, LEITER.indexOf(basis)) + (sicherer ? 1 : -1);
        String neu = LEITER.get(Math.max(0, Math.min(LEITER.size() - 1, i)));
        vormerkStufe = neu.equals(eff) ? null : neu;
    }

    private void clanVormerken(ArenaStand.Stand s) {
        if (!auswahlSicher(s)) return;
        String eff = clanVon(s, clans, auswahl);
        String basis = vormerkClan != null ? vormerkClan : eff;
        String neu = CLANS.get((Math.max(0, CLANS.indexOf(basis)) + 1) % CLANS.size());
        vormerkClan = neu.equals(eff) ? null : neu;
    }

    private void speichern(ArenaStand.Stand s, long jetzt) {
        if (auswahl == 0 || (vormerkStufe == null && vormerkClan == null)) {
            melden(Sprache.t("Nothing marked -- ↑/↓ level, C clan, then Enter.",
                    "Nichts vorgemerkt -- ↑/↓ Stufe, C Clan, dann Enter."), false, jetzt);
            return;
        }
        String name = nameVon(s, auswahl);
        List<Teil> teile = new ArrayList<>();
        String effStufe = stufeVon(s, stufen, auswahl);
        if (vormerkStufe != null && !vormerkStufe.equals(effStufe)) {
            teile.add(new Teil(ArenaStand.Befehl.Art.LISTE, auswahl, effStufe, vormerkStufe, name));
        }
        String effClan = clanVon(s, clans, auswahl);
        if (vormerkClan != null && !vormerkClan.equals(effClan)) {
            teile.add(new Teil(ArenaStand.Befehl.Art.CLAN, auswahl, effClan, vormerkClan, name));
        }
        vormerkStufe = null;
        vormerkClan = null;
        if (teile.isEmpty()) {
            melden(Sprache.t("Unchanged.", "Unveraendert."), false, jetzt);
            return;
        }
        speichernTeile(teile, jetzt);
    }

    /** Schickt die Befehle, merkt die neuen Werte fuer die Anzeige und fuer die Ruecktaste. */
    private void speichernTeile(List<Teil> teile, long jetzt) {
        StringBuilder text = new StringBuilder();
        for (Teil t : teile) {
            befehle.accept(new ArenaStand.Befehl(t.art(), t.platz(), t.neu(), t.name(), jetzt));
            (t.art() == ArenaStand.Befehl.Art.CLAN ? clans : stufen).put(t.platz(), t.neu());
            text.append(text.length() == 0 ? "" : " · ")
                    .append(t.art() == ArenaStand.Befehl.Art.CLAN ? clanText(t.neu()) : stufeText(t.neu()));
        }
        verlauf.addFirst(new Aenderung(rundeNr, List.copyOf(teile)));
        while (verlauf.size() > VERLAUF) verlauf.removeLast();
        Teil erster = teile.get(0);
        melden(Sprache.t("Saved: ", "Gespeichert: ") + wer(erster.platz(), erster.name()) + " → " + text, true, jetzt);
    }

    private void zuruecknehmen(ArenaStand.Stand s, long jetzt) {
        Aenderung a = verlauf.peekFirst();
        if (a == null) {
            melden(Sprache.t("Nothing to undo.", "Nichts zum Zuruecknehmen."), false, jetzt);
            return;
        }
        if (a.rundeNr() != rundeNr) {
            verlauf.clear();
            melden(Sprache.t("Can't undo any more -- new round.", "Nicht mehr zuruecknehmbar -- neue Runde."), false, jetzt);
            return;
        }
        verlauf.removeFirst();
        StringBuilder text = new StringBuilder();
        for (Teil t : a.teile()) {
            befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.ZURUECK, t.platz(), t.alt(), t.name(), jetzt));
            (t.art() == ArenaStand.Befehl.Art.CLAN ? clans : stufen).put(t.platz(), t.alt());
            text.append(text.length() == 0 ? "" : " · ")
                    .append(t.art() == ArenaStand.Befehl.Art.CLAN ? clanText(t.alt()) : stufeText(t.alt()));
        }
        vormerkStufe = null;
        vormerkClan = null;
        Teil erster = a.teile().get(0);
        melden(Sprache.t("Undone: ", "Zurueckgenommen: ") + wer(erster.platz(), erster.name()) + " → " + text,
                true, jetzt);
    }

    /** Esc: Vorgemerktes verwerfen, sonst Liste, Zettel, Auswahl schliessen. Nichts offen -> Esc geht ans Spiel. */
    private boolean abbrechen() {
        if (vormerkStufe != null || vormerkClan != null) {
            vormerkStufe = null;
            vormerkClan = null;
        } else if (liste) {
            liste = false;
        } else if (zettel) {
            zettel = false;
        } else if (auswahl != 0) {
            auswahl = 0;
        } else {
            return false;
        }
        return true;
    }

    // ---- Zustimmen / Ablehnen -----------------------------------------------------------------------------------

    /**
     * Worauf sich J und N beziehen. Autor 30.10.: "Wenn ich agree oder disagree druecken will, steht ganz oft oben No Bot Target Now." Das Fenster
     * zeigte den letzten Vorschlag noch, waehrend die Taste auf den frischen Stand ohne Vorschlag schaute (neue Lesung, schon angegriffen).
     */
    static ArenaStand.Gegner zielFuerMeinung(ArenaStand.Stand s) {
        ArenaStand.Gegner z = ziel(s);
        return z != null || s == null ? z : ArenaStand.letztesZiel(s.runde());
    }

    private void keinZielMelden(ArenaStand.Stand s, long jetzt) {
        melden(s != null && s.gegner().isEmpty()
                ? Sprache.t("The bot is still reading the players, its pick comes in a moment.", "Der Bot liest noch die Spieler, sein Vorschlag kommt gleich.")
                : Sprache.t("The bot has no pick in this stage.", "Der Bot hat in dieser Stage keinen Vorschlag."), false, jetzt);
    }

    private void zustimmen(ArenaStand.Stand s, long jetzt) {
        ArenaStand.Gegner z = zielFuerMeinung(s);
        if (z == null) {
            keinZielMelden(s, jetzt);
            return;
        }
        befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.ZUSTIMMEN, z.platz(), "", "", jetzt));
        notizen.accept(new Notiz(jetzt, ArenaStand.Befehl.Art.ZUSTIMMEN, z.platz(), leer(z.name()), leer(z.clan()),
                leer(z.name()), "", ""));
        melden(Sprache.t("Agreed: ", "Zugestimmt: ") + wer(z.platz(), z.name()), true, jetzt);
    }

    private void ablehnen(ArenaStand.Stand s, long jetzt) {
        ArenaStand.Gegner z = zielFuerMeinung(s);
        if (z == null) {
            keinZielMelden(s, jetzt);
            return;
        }
        // Sofort: der Bot soll dieses Ziel lassen. Der Grund kommt danach (oder gar nicht).
        befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.ABLEHNEN, z.platz(), "", "", jetzt));
        bezugSetzen(z.platz(), z.name(), z.clan(), z.name(), jetzt);
        meldung = null;
        modus = Modus.GRUND;
    }

    private boolean grundTaste(int vk, ArenaStand.Stand s, long jetzt) {
        int nr = vk >= '1' && vk <= '4' ? vk - '1' : vk >= VK_NUMPAD1 && vk <= VK_NUMPAD1 + 3 ? vk - VK_NUMPAD1 : -1;
        if (nr >= 0) {
            String grund = GRUENDE.get(nr);
            befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.ABLEHNEN, bezugPlatz, grund, "", jetzt));
            notizen.accept(new Notiz(jetzt, ArenaStand.Befehl.Art.ABLEHNEN, bezugPlatz, bezugName, bezugClan,
                    bezugZiel, grund, ""));
            modus = Modus.NORMAL;
            melden(Sprache.t("Rejected: ", "Abgelehnt: ") + wer(bezugPlatz, bezugName) + " -- " + grundText(grund),
                    true, jetzt);
            return true;
        }
        if (vk == VK_K) {
            eingabe.setLength(0);
            eingabeGrund = true;
            eingabeZuletzt = jetzt;
            modus = Modus.KOMMENTAR;
            return true;
        }
        grundOhne(jetzt);
        if (vk == VK_ESCAPE) return true;
        return normalTaste(vk, s, jetzt);   // eine andere Taste: Frage zu, die Taste wirkt ganz normal
    }

    /** Grund-Frage ohne Antwort geschlossen: die Ablehnung trotzdem notieren. */
    private void grundOhne(long jetzt) {
        modus = Modus.NORMAL;
        eingabeGrund = false;
        notizen.accept(new Notiz(jetzt, ArenaStand.Befehl.Art.ABLEHNEN, bezugPlatz, bezugName, bezugClan, bezugZiel,
                "", ""));
    }

    // ---- Kommentar -----------------------------------------------------------------------------------------------

    /** Ein Kommentar zur ganzen Stage: kein Spieler, die Lage der Pyramide schreibt ArenaSchicht dazu. */
    private void stageKommentarStarten(ArenaStand.Stand s, long jetzt) {
        ArenaStand.Gegner z = zielFuerMeinung(s);
        bezugSetzen(0, Sprache.t("this stage", "diese Stage"), "", z == null ? "" : z.name(), jetzt);
        eingabe.setLength(0);
        kommentarArt = STAGE_ART;
        eingabeGrund = false;
        eingabeZuletzt = jetzt;
        modus = Modus.KOMMENTAR;
    }

    private void kommentarStarten(ArenaStand.Stand s, long jetzt) {
        int platz = auswahlSicher(s) ? auswahl : 0;
        ArenaStand.Gegner z = zielFuerMeinung(s);
        ArenaStand.Gegner g = gegnerAuf(s, platz);
        bezugSetzen(platz, g == null ? "" : g.name(), g == null ? "" : g.clan(), z == null ? "" : z.name(), jetzt);
        eingabe.setLength(0);
        kommentarArt = "";
        eingabeGrund = false;
        eingabeZuletzt = jetzt;
        modus = Modus.KOMMENTAR;
    }

    /** Tab: die naechste Art, nach der letzten wieder keine. */
    static String naechsteArt(String art) {
        int i = KOMMENTAR_ARTEN.indexOf(art);
        return i + 1 < KOMMENTAR_ARTEN.size() ? KOMMENTAR_ARTEN.get(i + 1) : "";
    }

    private boolean kommentarTaste(int vk, long jetzt) {
        eingabeZuletzt = jetzt;
        switch (vk) {
            case VK_TAB -> {
                if (!eingabeGrund && !STAGE_ART.equals(kommentarArt)) kommentarArt = naechsteArt(kommentarArt);
            }
            case VK_RETURN -> kommentarAbschicken(jetzt);
            case VK_ESCAPE -> kommentarAbbrechen(jetzt, Sprache.t("Comment cancelled.", "Kommentar abgebrochen."));
            case VK_BACK -> {
                int n = eingabe.length();
                if (n > 0) eingabe.setLength(eingabe.offsetByCodePoints(n, -1));
            }
            default -> {
                String z = tastatur.zeichen(vk);
                if (z != null) {
                    for (int i = 0; i < z.length() && eingabe.length() < KOMMENTAR_ZEICHEN; i++) {
                        char c = z.charAt(i);
                        if (c >= 0x20 && c != 0x7F) eingabe.append(c);
                    }
                }
            }
        }
        return true;   // beim Tippen bekommt das Spiel nichts
    }

    private void kommentarAbschicken(long jetzt) {
        String text = eingabe.toString().strip();
        eingabe.setLength(0);
        modus = Modus.NORMAL;
        if (eingabeGrund) {
            eingabeGrund = false;
            String grund = text.isEmpty() ? "" : "sonstiges";
            if (!text.isEmpty()) {
                befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.ABLEHNEN, bezugPlatz, grund, text, jetzt));
            }
            notizen.accept(new Notiz(jetzt, ArenaStand.Befehl.Art.ABLEHNEN, bezugPlatz, bezugName, bezugClan,
                    bezugZiel, grund, text));
            melden(Sprache.t("Rejected: ", "Abgelehnt: ") + wer(bezugPlatz, bezugName)
                    + (text.isEmpty() ? "" : " -- " + text), true, jetzt);
            return;
        }
        if (text.isEmpty()) {
            melden(Sprache.t("Empty comment -- nothing sent.", "Leerer Kommentar -- nichts geschickt."), false, jetzt);
            return;
        }
        befehle.accept(new ArenaStand.Befehl(ArenaStand.Befehl.Art.KOMMENTAR, bezugPlatz, bezugName, text, jetzt));
        notizen.accept(new Notiz(jetzt, ArenaStand.Befehl.Art.KOMMENTAR, bezugPlatz, bezugName, bezugClan, bezugZiel,
                kommentarArt, text));
        melden(Sprache.t("Comment sent", "Kommentar geschickt")
                + (bezugPlatz > 0 || !bezugName.isBlank() ? ": " + wer(bezugPlatz, bezugName) : "."), true, jetzt);
    }

    private void kommentarAbbrechen(long jetzt, String hinweis) {
        eingabe.setLength(0);
        modus = Modus.NORMAL;
        if (eingabeGrund) grundOhne(jetzt);
        eingabeGrund = false;
        melden(hinweis, false, jetzt);
    }

    private void bezugSetzen(int platz, String name, String clan, String ziel, long jetzt) {
        bezugPlatz = platz;
        bezugName = leer(name);
        bezugClan = leer(clan);
        bezugZiel = leer(ziel);
        bezugSeit = jetzt;
    }

    // ---- Zeit und Runden -----------------------------------------------------------------------------------------

    /** Neue Runde erkennen, Zeitablauf (Grund-Frage, liegengebliebener Kommentar, Meldung). */
    private void abgleich(ArenaStand.Stand s, long jetzt) {
        if (s != null) {
            boolean neu = runde != Integer.MIN_VALUE
                    && (s.runde() != runde || (standUm > 0 && s.um() - standUm > LUECKE_MS));
            if (neu) neueRunde();
            runde = s.runde();
            standUm = Math.max(standUm, s.um());
        }
        if (modus == Modus.GRUND && jetzt - bezugSeit > GRUND_MS) grundOhne(jetzt);
        if (modus == Modus.KOMMENTAR && jetzt - eingabeZuletzt > KOMMENTAR_MS) {
            kommentarAbbrechen(jetzt, Sprache.t("Comment discarded (no key for 3 minutes).",
                    "Kommentar verworfen (3 Minuten keine Taste)."));
        }
        if (meldung != null && jetzt > meldungBis) meldung = null;
    }

    /** Andere Gegner auf den Plaetzen: Auswahl, Vorgemerktes und gerade Gespeichertes gelten nicht mehr. */
    private void neueRunde() {
        rundeNr++;
        auswahl = 0;
        vormerkStufe = null;
        vormerkClan = null;
        zettel = false;
        liste = false;
        stufen.clear();
        clans.clear();
    }

    private void melden(String text, boolean gut, long jetzt) {
        meldung = text;
        meldungGut = gut;
        meldungBis = jetzt + MELDUNG_MS;
    }

    // ---- Nachschlagen (auch fuer Seitenfenster und Rahmen) -------------------------------------------------------

    static ArenaStand.Gegner gegnerAuf(ArenaStand.Stand s, int platz) {
        if (s == null || platz <= 0) return null;
        for (ArenaStand.Gegner g : s.gegner()) if (g.platz() == platz) return g;
        return null;
    }

    static ArenaStand.Gegner ziel(ArenaStand.Stand s) {
        if (s == null) return null;
        for (ArenaStand.Gegner g : s.gegner()) if (g.ziel()) return g;
        return null;
    }

    private static int zielPlatz(ArenaStand.Stand s) {
        ArenaStand.Gegner z = ziel(s);
        return z == null ? 0 : z.platz();
    }

    private static String nameVon(ArenaStand.Stand s, int platz) {
        ArenaStand.Gegner g = gegnerAuf(s, platz);
        return g == null ? "" : leer(g.name());
    }

    /** Die Stufe eines Platzes: gerade gespeichert, sonst wie der Bot sie meldet; "" heisst "neutral". */
    static String stufeVon(ArenaStand.Stand s, Map<Integer, String> neu, int platz) {
        String st = neu.get(platz);
        if (st == null && s != null) {
            for (ArenaStand.Platz p : s.plaetze()) {
                if (p.platz() == platz) {
                    st = p.stufe();
                    break;
                }
            }
            if (st == null) {
                ArenaStand.Gegner g = gegnerAuf(s, platz);
                if (g != null) st = g.stufe();
            }
        }
        return st == null || st.isBlank() ? "neutral" : st;
    }

    /** Der Clan-Vermerk: gerade gespeichert, sonst "stark", wenn der Clan auf der Clan-Liste steht (+8). */
    static String clanVon(ArenaStand.Stand s, Map<Integer, String> neu, int platz) {
        String c = neu.get(platz);
        if (c != null) return c;
        ArenaStand.Gegner g = gegnerAuf(s, platz);
        return g != null && g.clanListe() ? "stark" : "nichts";
    }

    private static String leer(String s) {
        return s == null ? "" : s;
    }

    // ---- Texte ---------------------------------------------------------------------------------------------------

    static String wer(int platz, String name) {
        if (platz <= 0) return name == null || name.isBlank() ? Sprache.t("(general)", "(allgemein)") : name;
        return Sprache.t("place ", "Platz ") + platz + (name == null || name.isBlank() ? "" : " " + name);
    }

    static String stufeText(String stufe) {
        return switch (stufe == null ? "" : stufe) {
            case "sicher" -> Sprache.t("safe", "sicher");
            case "machbar" -> Sprache.t("doable", "machbar");
            case "fastsicher" -> Sprache.t("almost safe", "fast sicher");
            case "pause" -> Sprache.t("pause", "Pause");
            case "meiden" -> Sprache.t("avoid", "meiden");
            case "niemals" -> Sprache.t("never", "niemals");
            default -> Sprache.t("no list", "keine Liste");
        };
    }

    static String clanText(String clan) {
        return switch (clan == null ? "" : clan) {
            case "stark" -> Sprache.t("strong clan (+8)", "starker Clan (+8)");
            case "schwach" -> Sprache.t("weak clan (−4)", "schwacher Clan (−4)");
            default -> Sprache.t("no clan mark", "kein Clan-Vermerk");
        };
    }

    static String grundText(String grund) {
        return switch (grund == null ? "" : grund) {
            case "zu_stark" -> Sprache.t("too strong", "zu stark");
            case "starker_clan" -> Sprache.t("strong clan", "starker Clan");
            case "anderer" -> Sprache.t("rather someone else", "lieber jemand anders");
            case "sonstiges" -> Sprache.t("other", "Sonstiges");
            default -> Sprache.t("no reason given", "ohne Grund");
        };
    }

    /** Kurz fuer das Schildchen am Rahmen: "→ safe · strong clan (+8) ↵". */
    static String vormerkKurz(Anzeige a) {
        if (a == null || !a.vorgemerkt()) return null;
        StringBuilder b = new StringBuilder("→ ");
        if (a.vormerkStufe() != null) b.append(stufeText(a.vormerkStufe()));
        if (a.vormerkClan() != null) b.append(a.vormerkStufe() != null ? " · " : "").append(clanText(a.vormerkClan()));
        return b.append("  ↵").toString();
    }

    /** Die kleine Tasten-Hilfe unten im Seitenfenster (75-%-Tastatur: keine F-Tasten, kein Ziffernblock). */
    static String hilfe(boolean botSpielt) {
        return Sprache.t(
                "WASD or arrows choose · 1 safe · 2 doable · 3 avoid · 4 never · 5 almost safe · 6 pause · 0 none · Backspace undo · K comment · G stage comment · J agree · N disagree · ^ hide window · Space info",
                "WASD oder Pfeile waehlen · 1 sicher · 2 machbar · 3 meiden · 4 niemals · 5 fast sicher · 6 Pause · 0 keine · Ruecktaste zurueck · K Kommentar · G Stage-Kommentar · J passt · N passt nicht · ^ Fenster weg · "
                        + "Leertaste Info");
    }

    /** Der Text auf dem Knopf einer Kommentar-Art. */
    static String artText(String art) {
        return switch (art) {
            case "warum_angegriffen" -> Sprache.t("Why attacked", "Warum angegriffen");
            case "chance_zu_hoch" -> Sprache.t("Chance too high", "Chance zu hoch");
            case "chance_zu_niedrig" -> Sprache.t("Chance too low", "Chance zu niedrig");
            case STAGE_ART -> Sprache.t("Stage", "Stage");
            default -> art;
        };
    }

    private static final DateTimeFormatter ZEIT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Eine Zeile fuer arena-kommentare.txt: Zeit | Art | Platz | Name | Clan | Bot-Ziel | Wert | Text -- immer genau eine. */
    static String zeile(Notiz n) {
        return String.join(" | ", ZEIT.format(Instant.ofEpochMilli(n.um())), n.art().name(),
                String.valueOf(n.platz()), feld(n.name()), feld(n.clan()), feld(n.botZiel()), feld(n.wert()),
                feld(n.text()));
    }

    /** Ein Feld zeilensicher: Zeilenumbrueche werden " / ", der Trenner | wird ¦, Steuerzeichen werden Leerzeichen. */
    static String feld(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", " / ").replace("\r", " / ").replace("\n", " / ")
                .replace("\u0085", " / ").replace(" ", " / ").replace(" ", " / ")
                .replace('|', '¦');
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().strip();
    }

    /** Die Discord-Meldung zu einer Notiz. Ein "@" aus dem Text pingt niemanden (keine @everyone-Pings). */
    static String discordText(Notiz n) {
        String wer = wer(n.platz(), n.name()) + (n.clan() == null || n.clan().isBlank() ? "" : " [" + n.clan() + "]");
        String text = n.text() == null || n.text().isBlank() ? "" : n.text().strip();
        String m = switch (n.art()) {
            case ZUSTIMMEN -> Sprache.t("Arena: agreed with the bot target -- ", "Arena: Bot-Ziel zugestimmt -- ") + wer;
            case ABLEHNEN -> Sprache.t("Arena: rejected the bot target -- ", "Arena: Bot-Ziel abgelehnt -- ") + wer
                    + " -- " + grundText(n.wert()) + (text.isEmpty() ? "" : ": " + text);
            case KOMMENTAR -> Sprache.t("Arena comment on ", "Arena-Kommentar zu ") + wer
                    + (n.wert() == null || n.wert().isBlank() ? "" : " [" + artText(n.wert()) + "]")
                    + (n.botZiel() == null || n.botZiel().isBlank() ? ""
                    : Sprache.t(" (bot target: ", " (Bot-Ziel: ") + n.botZiel() + ")") + ": " + text;
            default -> zeile(n);
        };
        return m.replace("@", "@​");
    }
}
