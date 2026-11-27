package de.hustlebot.bot;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Log;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Zeitzone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Autors eigenes Team: Punktescore fuer die Arena und alles, was der Team-Scan (bot/TeamScan,
 * Auftrag 6 vom 11.10.) in Kaserne, Thronsaal und Rift gesehen hat.
 *
 * Autor, 08.10.: "der Bot soll mein eigenes Team ... anschauen -- Pals, Titanen, Steine, Sets,
 * Artefakte -- und ganz am Ende 15 Punkte dazurechnen. Einmal alle drei Tage."
 * Autor, 11.10. (Auftrag 6): beim allerersten Start und danach alle 3 ECHTZEIT-Tage in der Burg
 * ansehen (Kaserne -> Info, Einheiten einzeln, Held im Thronsaal, Schirmherren im Rift), die
 * Einheit mit der kleinsten Kraft als Opfereinheit fuers Verlies merken, Punkte wie bei Gegnern.
 *
 * Steht in arena-ich.txt, damit alles einen Neustart ueberlebt und Autor es lesen kann:
 *
 *     stand=2026-10-11 20:15
 *     punkte=73
 *     team=Sets 31, Artefakte +6, ...
 *     quelle=kaserne
 *     name=Autor
 *     squadpower=42464130
 *     einheit=Bogen 1;6362024
 *     einheit=Magier 3;4120000
 *     opfer=Magier 3
 *     held=Tom;4351600;haupt=?;neben=0
 *     schirm=3/1/1
 *     schirmstand=2026-10-11 20:05
 *     schirmtext=Schirmherrschaften 3 (1 gute, 1 Stigma, +8) -- gute: Nr. 2; Stigma: Baer
 *     schirmlevel=3;9;5
 *     versuch=2026-10-11 20:10
 *
 * Die Zeilen schirm* (Nachtrag Autor 11.10. zu Auftrag 6, Punkt 4, Baustein der Schwester-Sitzung): die
 * eigenen Schirmherrschaften aus Burg -> Rift -> Titan Patrons -> Patronage, gelesen und gepunktet wie bei
 * Gegnern (bot/SchirmLeser). Der Team-Scan rechnet sie in punkte= mit hinein; {@link #schirmPunkte()} gibt
 * sie auch getrennt her.
 *
 * Alte Dateien (nur stand/punkte/team) bleiben lesbar; neue Zeilen stehen hinten dran.
 * Aelter als drei Tage gilt der Score als nicht vorhanden -- dann sieht der Bot sein Team wieder an.
 * Bis dahin misst er die Gegner am staerksten je geschlagenen Gegner.
 */
public final class MeinTeam {
    private MeinTeam() {}

    /** Fuer Tests austauschbar. */
    static volatile Path DATEI = Path.of("arena-ich.txt");
    /** Haupt-/Nebenfaehigkeit des Helden: Punkte (Autor 11.10.: +12 / +4, einstellbar). */
    static volatile Path PUNKTE_DATEI = Path.of("eigenesteam.txt");
    /** Autor, 08.10.: "einmal alle drei Tage. Also Echtzeittage." */
    static final Duration GUELTIG = Duration.ofDays(3);
    /** Nach einem abgebrochenen Scan so lange warten -- Autor 26.10.: "nur alle 3 Tage, nicht alle 6 Stunden". */
    static final Duration NACH_FEHLVERSUCH = Duration.ofDays(3);
    /** Autor, 08.10.: "ganz am Ende von meinem Punktescore nochmal 15 Punkte dazu rechnen." */
    // Freunde-Bot (Code-Pruefung 16.10.): die Hauptbot-Werte (+0 / -30) sind Autors persoenliche Eichung ("nur bei
    // mir"). Fuer die Freunde gilt wie bisher -15 -- sonst lag jede Messlatte 15 Punkte zu tief.
    // Autor 22.10.: -8 statt -15. Nachgerechnet an 508 Arena-Kaempfen: die Punkte-Schaetzung traf mit -8 um ein Fuenftel
    // besser (Brier 0,128 statt 0,160 bei den knappen Kaempfen) -- die Messlatte lag zu tief.
    public static final int BONUS = -8;
    /** Autor, 11.10.: "Wieso hat mein Team so viel mehr Punkte als jeder Gegner? Zieh fuer mein eigenes Team 30 ab -- nur bei mir." */
    public static final int ABZUG = 0;
    public static final int HELD_HAUPT_STANDARD = 12, HELD_NEBEN_STANDARD = 4;
    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Der 3-Tage-Scan von selbst (an/aus mit !team an|aus); Standard an. */
    private static final Schalterdatei AUTOMATIK = new Schalterdatei(Path.of("teamscan.txt"), "Team-Scan", true);
    private static volatile boolean automatik = AUTOMATIK.lesen();

    /** Eine Einheit aus der Kaserne. kraft -1 = nicht gelesen. */
    public record Einheit(String name, long kraft) {}

    /** Der Held: hauptGut null = nicht pruefbar (keine Vorlagen). */
    public record Held(String name, long kraft, Boolean hauptGut, int guteNeben) {}

    /** Die eigenen Schirmherrschaften, wie sie in der Datei stehen; {@code text} wie im Arena-Protokoll. */
    public record Schirme(int anzahl, int gute, int stigmas, String text, LocalDateTime stand) {
        /** Dieselben Punkte wie beim Gegner. */
        public int punkte() {
            return de.hustlebot.core.Gegnerbuch.schirmPunkte(anzahl, gute, stigmas);
        }
    }

    /** Alles, was ueber das eigene Team bekannt ist. schirme null = nie gelesen; schirmLevel = die Level der aktiven. */
    public record Stand(LocalDateTime stand, int punkte, String beschreibung, String quelle, String name,
                        long squadPower, List<Einheit> einheiten, String opfer, Held held, Schirme schirme,
                        List<Integer> schirmLevel, LocalDateTime versuch) {
        public static Stand leer() {
            return new Stand(null, -1, "", "", "", -1, List.of(), "", null, null, List.of(), null);
        }

        Stand mit(Schirme s, LocalDateTime schirmStand) {
            return new Stand(stand, punkte, beschreibung, quelle, name, squadPower, einheiten, opfer, held, s, schirmLevel, versuch);
        }
    }

    private static volatile Stand stand;
    private static volatile long gelesenUm;

    // ------------------------------------------------------------------
    //  Lesen
    // ------------------------------------------------------------------

    /** Der Score samt Bonus, oder -1, wenn keiner da oder aelter als drei Tage. */
    public static int punkte() {
        Stand s = lesen();
        if (s.punkte() < 0 || s.stand() == null) return -1;
        if (Duration.between(s.stand(), Zeitzone.jetzt()).compareTo(GUELTIG) > 0) return -1;
        return s.punkte();
    }

    /** Muss der Bot sein Team (wieder) ansehen? */
    public static boolean faellig() {
        return punkte() < 0;
    }

    /**
     * Ist der Team-Scan jetzt dran (Auftrag 6)? Automatik an, kein gueltiger Stand, und der letzte
     * abgebrochene Versuch liegt mindestens 3 Tage zurueck.
     */
    public static boolean scanFaellig() {
        // Robin 26.10. ("schaut vor der Kathedrale immer sein Team an und bricht nach ein paar Minuten ab"): nach einem
        // Fehlversuch 3 Tage Ruhe -- auch wenn arena-ich.txt nicht geschrieben werden konnte. Autor 26.10.: "nur alle
        // 3 Tage" -- auch eine Anforderung (Opfer-Einheit nicht erkannt) loest keinen Scan mehr dazwischen aus.
        LocalDateTime v = versuchImSpeicher;
        if (v != null && Duration.between(v, Zeitzone.jetzt()).compareTo(NACH_FEHLVERSUCH) < 0) return false;
        return scanFaellig(automatik, lesen(), Zeitzone.jetzt());
    }

    /** Letzter abgebrochener Versuch in diesem Lauf (falls arena-ich.txt nicht geschrieben werden kann). */
    private static volatile LocalDateTime versuchImSpeicher;

    /**
     * Autor 18.10.: Erkennt der Bot eine Einheit weder am Bild noch am Namen, den Team-Scan neu machen. Seit Autor 26.10.
     * ("nur alle 3 Tage") nur noch ein Hinweis -- der Scan kommt im festen 3-Tage-Takt (von Hand: !team scan).
     */
    private static volatile boolean angefordertGemeldet;

    public static void neuScannenAnfordern(String grund) {
        if (angefordertGemeldet) return;
        angefordertGemeldet = true;
        Log.info("Team", Sprache.t(grund + " -- the next team scan comes with the 3-day rhythm (or now: !team scan).",
                grund + " -- der naechste Team-Scan kommt im 3-Tage-Takt (sofort: !team scan)."));
    }

    /** Die reine Regel, ohne Uhr und Datei (fuer den Test). */
    static boolean scanFaellig(boolean automatik, Stand s, LocalDateTime jetzt) {
        if (!automatik) return false;
        boolean gueltig = s.punkte() >= 0 && s.stand() != null
                && Duration.between(s.stand(), jetzt).compareTo(GUELTIG) <= 0;
        if (gueltig) return false;
        return s.versuch() == null || Duration.between(s.versuch(), jetzt).compareTo(NACH_FEHLVERSUCH) >= 0;
    }

    /** Wann zuletzt angesehen, oder null. */
    public static LocalDateTime stand() {
        return lesen().stand();
    }

    public static Stand alles() {
        return lesen();
    }

    /** Der eigene Spielername (oben links in der Burg gelesen oder mit !team name gesetzt); leer = unbekannt. */
    /** Deine Kampfkraft aus dem letzten Team-Scan ("Squad power"), -1 = unbekannt (Burgen angreifen, Autor 25.10.). */
    public static long squadPower() {
        long k = lesen().squadPower();
        return k > 0 ? k : -1;
    }

    /** Wie viele eigene Einheiten der letzte Team-Scan gesehen hat (0 = noch keiner). */
    public static int einheitenAnzahl() {
        return lesen().einheiten().size();
    }

    public static String spielername() {
        return lesen().name();
    }

    /** Die Einheit mit der kleinsten Kraft -- fuer das Verlies als Opfer (Autor: "Magier 3"). Leer = unbekannt. */
    public static String opfereinheit() {
        return lesen().opfer();
    }

    /** Autor 14.10.: "Automatik soll immer an sein, alle 3 Tage soll der Bot nachschauen" -- kein Schalter mehr. */
    public static boolean automatikAn() { return true; }

    // ------------------------------------------------------------------
    //  Eigene Schirmherrschaften (Nachtrag 11.10. zu Auftrag 6, Punkt 4)
    // ------------------------------------------------------------------

    /** Die eigenen Schirmherrschaften, oder null, wenn nie gelesen oder aelter als drei Tage. */
    public static Schirme schirme() {
        Schirme s = lesen().schirme();
        if (s == null || s.stand() == null) return null;
        if (Duration.between(s.stand(), Zeitzone.jetzt()).compareTo(GUELTIG) > 0) return null;
        return s;
    }

    /** Punkte der eigenen Schirmherrschaften (wie beim Gegner), oder -1, wenn unbekannt. */
    public static int schirmPunkte() {
        Schirme s = schirme();
        return s == null ? -1 : s.punkte();
    }

    /** Muessen die eigenen Schirmherrschaften (wieder) angesehen werden? */
    public static boolean schirmeFaellig() {
        return schirme() == null;
    }

    /** "Schirmherrschaften 3 (1 gute, 1 Stigma, +8) -- gute: Nr. 2 (Stand 11.10. 20:05)" fuer Status/Notizblock. */
    public static String schirmText() {
        Schirme s = schirme();
        if (s == null) return "Schirmherrschaften: noch nicht angesehen";
        return s.text() + " (Stand " + s.stand().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm")) + ")";
    }

    /** Legt die frisch gelesenen eigenen Schirmherrschaften ab (alles andere bleibt). */
    static synchronized void schirmeSpeichern(SchirmLeser.Ergebnis e) {
        if (e == null || !e.bekannt()) {
            Log.info("Team", "Eigene Schirmherrschaften: nichts gelesen -- der alte Stand bleibt.");
            return;
        }
        Schirme s = schirmeSpeichern(DATEI, e, Zeitzone.jetzt());
        if (s != null) Log.info("Team", "Eigene Schirmherrschaften: " + s.text() + " -- gespeichert in " + DATEI + " (gilt 3 Tage).");
    }

    /** Wie oben, in eine beliebige Datei (Tests). Liefert, was geschrieben wurde, oder null bei Fehler. */
    static synchronized Schirme schirmeSpeichern(Path datei, SchirmLeser.Ergebnis e, LocalDateTime jetzt) {
        String ein = e.einordnung();
        String text = e.text() + (ein.isEmpty() ? "" : " -- " + ein);
        Schirme s = new Schirme(e.anzahl(), e.gute(), e.stigmas(), text.replace('\n', ' '), jetzt);
        Stand alt = datei.equals(DATEI) ? lesen() : lesenAus(datei);
        return schreiben(datei, alt.mit(s, jetzt)) ? s : null;
    }

    /** Liest nur die Schirmherrschaften einer Datei (Tests); null, wenn keine da. */
    static Schirme schirmeLesen(Path datei) {
        return lesenAus(datei).schirme();
    }

    public static void setAutomatik(boolean an) {
        automatik = an;
        AUTOMATIK.setzen(an, "Eigenes Team alle 3 Tage von selbst ansehen (an/aus), gesetzt mit !team an|aus");
    }

    // ------------------------------------------------------------------
    //  Punkte
    // ------------------------------------------------------------------

    /** Punkte fuer eine gute Hauptfaehigkeit des Helden (eigenesteam.txt: haupt=12). */
    public static int heldHaupt() { return punkteWert("haupt", HELD_HAUPT_STANDARD); }

    /** Punkte je guter Nebenfaehigkeit (eigenesteam.txt: neben=4). */
    public static int heldNeben() { return punkteWert("neben", HELD_NEBEN_STANDARD); }

    /** Punkte der Schirmherrschaften aus dem letzten Kaserne-Scan, -1 = unbekannt (Idee 7, 22.10.). */
    public static int schirmPunkteAusScan() {
        Stand s = lesen();
        return s.schirme() == null ? -1 : s.schirme().punkte();
    }

    public static int heldPunkte(Held h) {
        return heldPunkte(h, heldHaupt(), heldNeben());
    }

    static int heldPunkte(Held h, int haupt, int neben) {
        if (h == null) return 0;
        return (Boolean.TRUE.equals(h.hauptGut()) ? haupt : 0) + Math.max(0, h.guteNeben()) * neben;
    }

    public static synchronized void setHeldPunkte(int haupt, int neben) {
        try {
            Files.writeString(PUNKTE_DATEI, "# Punkte fuer den Helden im eigenen Team (Autor 11.10.): gute Hauptfaehigkeit, je gute Nebenfaehigkeit.\n"
                    + "# Gesetzt mit !team held <haupt> <neben>.\n"
                    + "haupt=" + Math.max(0, haupt) + "\nneben=" + Math.max(0, neben) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Team", PUNKTE_DATEI + " liess sich nicht schreiben: " + e.getMessage());
        }
    }

    private static int punkteWert(String schluessel, int standard) {
        try {
            if (!Files.exists(PUNKTE_DATEI)) return standard;
            for (String z : de.hustlebot.core.Dateien.zeilen(PUNKTE_DATEI)) {
                String s = z.strip();
                if (s.startsWith(schluessel + "=")) return Integer.parseInt(s.substring(schluessel.length() + 1).strip());
            }
        } catch (Exception e) {
            Log.debug("Team", PUNKTE_DATEI + " nicht lesbar: " + e.getMessage());
        }
        return standard;
    }

    /**
     * Die Opfereinheit: die Einheit mit der kleinsten gelesenen Kraft. Leer, wenn keine Kraft
     * gelesen wurde (dann besser keine als eine falsche -- geopfert wird endgueltig).
     */
    static String opferAus(List<Einheit> einheiten) {
        Einheit beste = null;
        for (Einheit e : einheiten == null ? List.<Einheit>of() : einheiten) {
            if (e == null || e.kraft() <= 0 || e.name() == null || e.name().isBlank()) continue;
            if (beste == null || e.kraft() < beste.kraft()) beste = e;
        }
        return beste == null ? "" : beste.name();
    }

    // ------------------------------------------------------------------
    //  Schreiben
    // ------------------------------------------------------------------

    /**
     * Schreibt den frisch ermittelten Score aus der Arena (ohne Bonus -- der kommt hier dazu).
     * Alles, was der Team-Scan sonst wusste (Name, Einheiten, Opfer, Held, Schirme), bleibt stehen.
     */
    public static synchronized void speichern(int teamPunkte, String beschreibung) {
        speichern(teamPunkte, beschreibung, false);
    }

    /** @param heldGelesen der Held wurde in der Arena angesehen (Autor 22.10.) -- steckt schon in teamPunkte. */
    public static synchronized void speichern(int teamPunkte, String beschreibung, boolean heldGelesen) {
        Stand alt = lesen();
        // Idee 7 (22.10.): im Vergleichsfenster der Arena liest der Bot deine Einheiten, aber nicht Held, Schirmherren
        // und Pals -- die kommen aus dem letzten Kaserne-Scan (Autor: "jede Arena unterschiedliche Teams, ohne dass ich
        // jedes Mal einen Teamscan machen muss"). Ohne sie waere die Messlatte rund 20 Punkte zu niedrig.
        if (alt.held() != null && !heldGelesen) {
            int held = heldPunkte(alt.held());
            teamPunkte += held;
            beschreibung = (beschreibung == null ? "" : beschreibung) + ", Held aus dem Team-Scan +" + held;
        }
        Stand neu = new Stand(Zeitzone.jetzt(), teamPunkte + BONUS - ABZUG, beschreibung == null ? "" : beschreibung.replace('\n', ' '),
                "arena", alt.name(), alt.squadPower(), alt.einheiten(), alt.opfer(), alt.held(), alt.schirme(), alt.schirmLevel(), null);
        schreiben(DATEI, neu);
        Log.info("Arena", "Dein Team: " + teamPunkte + " Punkte + " + BONUS + " Bonus - " + ABZUG + " Abzug = " + neu.punkte()
                + " -- gespeichert in " + DATEI + " (gilt 3 Tage).");
    }

    /** Das Ergebnis des Team-Scans (Auftrag 6). teamPunkte ohne Bonus. */
    public static synchronized void speichernScan(int teamPunkte, String beschreibung, String name, long squadPower,
                                                  List<Einheit> einheiten, Held held, SchirmLeser.Ergebnis schirm,
                                                  List<Integer> schirmLevel) {
        angefordertGemeldet = false;
        Stand alt = lesen();
        List<Einheit> e = einheiten == null ? List.of() : List.copyOf(einheiten);
        String opfer = opferAus(e);
        LocalDateTime jetzt = Zeitzone.jetzt();
        Schirme s = alt.schirme();
        if (schirm != null && schirm.bekannt()) {
            String ein = schirm.einordnung();
            s = new Schirme(schirm.anzahl(), schirm.gute(), schirm.stigmas(), schirm.text() + (ein.isEmpty() ? "" : " -- " + ein), jetzt);
        }
        // Code-Review 15.10.: unter 30 Punkten wurde der Wert negativ, nie gespeichert -> Scan alle 30 min.
        Stand neu = new Stand(jetzt, Math.max(0, teamPunkte + BONUS - ABZUG), beschreibung == null ? "" : beschreibung.replace('\n', ' '),
                "kaserne", name == null || name.isBlank() ? alt.name() : name.strip(), squadPower, e,
                opfer.isEmpty() ? alt.opfer() : opfer, held, s, schirmLevel == null ? List.of() : List.copyOf(schirmLevel), null);
        schreiben(DATEI, neu);
        Log.info("Team", "Dein Team: " + teamPunkte + " Punkte + " + BONUS + " Bonus - " + ABZUG + " Abzug = " + neu.punkte()
                + (squadPower > 0 ? ", Squad power " + String.format(Locale.GERMANY, "%,d", squadPower) : "")
                + ", " + e.size() + " Einheiten" + (opfer.isEmpty() ? "" : ", Opfereinheit " + opfer)
                + (held != null ? ", Held " + held.name() : "") + " -- gespeichert in " + DATEI + " (gilt 3 Tage).");
    }

    /** Ein Scan ging schief: merken, damit es nicht jeden Run noch einmal probiert wird (3 Tage Pause). */
    public static synchronized void versuchVermerken() {
        // Robin 26.10.: die Anforderung (Opfer-Einheit nicht erkannt) blieb nach einem Fehlversuch stehen -- dann kam der
        // Scan alle 30 Minuten wieder (je bis zu 6 min). Ein Fehlversuch zaehlt: in 3 Tagen noch einmal.
        angefordertGemeldet = false;
        versuchImSpeicher = Zeitzone.jetzt();
        Stand alt = lesen();
        schreiben(DATEI, new Stand(alt.stand(), alt.punkte(), alt.beschreibung(), alt.quelle(), alt.name(), alt.squadPower(),
                alt.einheiten(), alt.opfer(), alt.held(), alt.schirme(), alt.schirmLevel(), Zeitzone.jetzt()));
    }

    /** Den eigenen Spielernamen setzen (!team name Autor, oder in der Burg gelesen). */
    public static synchronized void setSpielername(String name) {
        if (name == null || name.isBlank()) return;
        Stand alt = lesen();
        if (name.strip().equals(alt.name())) return;
        schreiben(DATEI, new Stand(alt.stand(), alt.punkte(), alt.beschreibung(), alt.quelle(), name.strip(), alt.squadPower(),
                alt.einheiten(), alt.opfer(), alt.held(), alt.schirme(), alt.schirmLevel(), alt.versuch()));
        Log.info("Team", "Dein Spielername: " + name.strip() + ".");
    }

    /** Code-Review 26.10.: beim Kontowechsel den Namen des vorigen Kontos vergessen -- setSpielername("") tat nichts. */
    public static synchronized void spielernameVergessen() {
        Stand alt = lesen();
        if (alt.name().isEmpty()) return;
        schreiben(DATEI, new Stand(alt.stand(), alt.punkte(), alt.beschreibung(), alt.quelle(), "", alt.squadPower(),
                alt.einheiten(), alt.opfer(), alt.held(), alt.schirme(), alt.schirmLevel(), alt.versuch()));
    }

    private static boolean schreiben(Path datei, Stand s) {
        List<String> zeilen = new ArrayList<>();
        zeilen.add("# Dein eigenes Arena-Team, vom Bot angesehen (alle 3 Tage neu). Score = Team + " + BONUS + " Bonus.");
        zeilen.add("# Seit 11.10. (Auftrag 6) dazu: Spielername, Squad power, Einheiten mit Kraft, Opfereinheit, Held, Schirmherren.");
        if (s.stand() != null) zeilen.add("stand=" + s.stand().format(STEMPEL));
        if (s.punkte() >= 0) zeilen.add("punkte=" + s.punkte());
        zeilen.add("team=" + s.beschreibung());
        if (!s.quelle().isEmpty()) zeilen.add("quelle=" + s.quelle());
        if (!s.name().isEmpty()) zeilen.add("name=" + s.name());
        if (s.squadPower() > 0) zeilen.add("squadpower=" + s.squadPower());
        for (Einheit e : s.einheiten()) zeilen.add("einheit=" + e.name().replace(';', ',') + ";" + e.kraft());
        if (!s.opfer().isEmpty()) zeilen.add("opfer=" + s.opfer());
        if (s.held() != null) {
            zeilen.add("held=" + s.held().name().replace(';', ',') + ";" + s.held().kraft() + ";haupt="
                    + (s.held().hauptGut() == null ? "?" : s.held().hauptGut() ? "gut" : "normal") + ";neben=" + s.held().guteNeben());
        }
        if (s.schirme() != null) {
            zeilen.add("schirm=" + s.schirme().anzahl() + "/" + s.schirme().gute() + "/" + s.schirme().stigmas());
            if (s.schirme().stand() != null) zeilen.add("schirmstand=" + s.schirme().stand().format(STEMPEL));
            zeilen.add("schirmtext=" + s.schirme().text());
        }
        if (!s.schirmLevel().isEmpty()) {
            StringBuilder b = new StringBuilder("schirmlevel=");
            for (int i = 0; i < s.schirmLevel().size(); i++) b.append(i > 0 ? ";" : "").append(s.schirmLevel().get(i));
            zeilen.add(b.toString());
        }
        if (s.versuch() != null) zeilen.add("versuch=" + s.versuch().format(STEMPEL));
        try {
            Files.write(datei, zeilen, StandardCharsets.UTF_8);
            if (datei.equals(DATEI)) {
                stand = s;
                gelesenUm = System.currentTimeMillis();
            }
            return true;
        } catch (Exception e) {
            Log.warn("Arena", datei + " liess sich nicht schreiben: " + e.getMessage());
            // Code-Review 26.10.: trotzdem im Speicher -- sonst blieb bei schreibgeschuetzter arena-ich.txt (Robin) der
            // alte Name/Versuch stehen, und spielernameVergessen()/setSpielername() taten nichts.
            if (datei.equals(DATEI)) {
                stand = s;
                gelesenUm = System.currentTimeMillis();
            }
            return false;
        }
    }

    /** Liest die Datei hoechstens einmal je Minute. */
    private static synchronized Stand lesen() {
        long jetzt = System.currentTimeMillis();
        Stand s = stand;
        if (s != null && jetzt - gelesenUm < 60_000L) return s;
        gelesenUm = jetzt;
        s = lesenAus(DATEI);
        stand = s;
        return s;
    }

    private static Stand lesenAus(Path datei) {
        if (!Files.exists(datei)) return Stand.leer();
        try {
            return parsen(de.hustlebot.core.Dateien.zeilen(datei));
        } catch (Exception e) {
            Log.warn("Arena", datei + " nicht lesbar (" + e.getMessage() + ") -- gilt als nicht vorhanden.");
            return Stand.leer();
        }
    }

    /** Nur fuer Tests: beim naechsten Zugriff neu lesen. */
    static synchronized void neuLesen() {
        stand = null;
        gelesenUm = 0;
    }

    /**
     * Autor 21.11.: nach einem Kontowechsel gehoert arena-ich.txt einem anderen Konto -- sofort neu lesen, und der
     * Fehlversuch des alten Kontos gilt nicht fuer das neue.
     */
    static synchronized void kontoGewechselt() {
        neuLesen();
        versuchImSpeicher = null;
        angefordertGemeldet = false;
    }

    /**
     * Autor 21.11.: Hat dieses Konto noch nie einen Team-Scan gehabt (und auch keinen Fehlversuch in den letzten 3 Tagen)?
     * Dann kommt nach einem Kontowechsel zuerst der Team-Scan, danach der Modus.
     */
    static boolean ohneScan() {
        Stand s = lesen();
        if (s.stand() != null) return false;
        LocalDateTime jetzt = Zeitzone.jetzt();
        for (LocalDateTime v : new LocalDateTime[]{s.versuch(), versuchImSpeicher}) {
            if (v != null && Duration.between(v, jetzt).compareTo(NACH_FEHLVERSUCH) < 0) return false;
        }
        return true;
    }

    static Stand parsen(List<String> zeilen) {
        LocalDateTime st = null, versuch = null;
        int p = -1;
        String team = "", quelle = "", name = "", opfer = "";
        long squad = -1;
        List<Einheit> einheiten = new ArrayList<>();
        Held held = null;
        List<Integer> schirmLevel = new ArrayList<>();
        int[] schirm = null;
        String schirmText = "";
        LocalDateTime schirmStand = null;
        for (String zeile : zeilen) {
            String z = zeile.strip();
            if (z.isEmpty() || z.startsWith("#")) continue;
            int gleich = z.indexOf('=');
            if (gleich <= 0) continue;
            String k = z.substring(0, gleich).strip().toLowerCase(Locale.ROOT), v = z.substring(gleich + 1).strip();
            try {
                switch (k) {
                    case "punkte" -> p = Integer.parseInt(v);
                    case "stand" -> st = LocalDateTime.parse(v, STEMPEL);
                    case "versuch" -> versuch = LocalDateTime.parse(v, STEMPEL);
                    case "team" -> team = v;
                    case "quelle" -> quelle = v;
                    case "name" -> name = v;
                    case "squadpower" -> squad = Long.parseLong(v.replaceAll("[^0-9]", ""));
                    case "opfer" -> opfer = v;
                    case "einheit" -> {
                        String[] t = v.split(";");
                        long kraft = t.length > 1 && t[1].strip().matches("-?\\d+") ? Long.parseLong(t[1].strip()) : -1;
                        if (!t[0].isBlank()) einheiten.add(new Einheit(t[0].strip(), kraft));
                    }
                    case "held" -> {
                        String[] t = v.split(";");
                        long kraft = t.length > 1 && t[1].strip().matches("-?\\d+") ? Long.parseLong(t[1].strip()) : -1;
                        Boolean haupt = null;
                        int neben = 0;
                        for (String teil : t) {
                            String x = teil.strip().toLowerCase(Locale.ROOT);
                            if (x.startsWith("haupt=")) haupt = x.endsWith("?") ? null : x.endsWith("gut");
                            if (x.startsWith("neben=") && x.substring(6).matches("\\d+")) neben = Integer.parseInt(x.substring(6));
                        }
                        held = new Held(t[0].strip(), kraft, haupt, neben);
                    }
                    case "schirm" -> {
                        String[] x = v.split("/");
                        if (x.length == 3) schirm = new int[]{Integer.parseInt(x[0].strip()), Integer.parseInt(x[1].strip()), Integer.parseInt(x[2].strip())};
                    }
                    case "schirmstand" -> schirmStand = LocalDateTime.parse(v, STEMPEL);
                    case "schirmtext" -> schirmText = v;
                    case "schirmlevel" -> {
                        for (String x : v.split("[;,]")) if (x.strip().matches("-?\\d+")) schirmLevel.add(Integer.parseInt(x.strip()));
                    }
                    default -> { }
                }
            } catch (Exception e) {
                Log.debug("Team", "Zeile in arena-ich.txt uebersprungen: " + z);
            }
        }
        Schirme s = schirm != null && schirmStand != null ? new Schirme(schirm[0], schirm[1], schirm[2], schirmText, schirmStand) : null;
        return new Stand(st, p, team, quelle, name, squad, einheiten, opfer, held, s, schirmLevel, versuch);
    }

    // ------------------------------------------------------------------
    //  Anzeige
    // ------------------------------------------------------------------

    /** Der Stand als Text fuer !team, !status und den Notizblock. */
    public static String karte() {
        Stand s = lesen();
        StringBuilder b = new StringBuilder();
        if (s.stand() == null) {
            b.append(Sprache.t("Not looked at yet.", "Noch nicht angesehen."));
        } else {
            boolean gueltig = punkte() >= 0;
            b.append(Sprache.t("Looked at: ", "Angesehen: ")).append(s.stand().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm")))
             .append(gueltig ? "" : Sprache.t(" (older than 3 days -- will be looked at again)", " (aelter als 3 Tage -- wird neu angesehen)"))
             .append(s.quelle().isEmpty() ? "" : " [" + s.quelle() + "]").append('\n');
            b.append(Sprache.t("Points: ", "Punkte: ")).append(s.punkte()).append(Sprache.t(" (incl. ", " (davon ")).append(BONUS).append(" Bonus)").append('\n');
            if (!s.beschreibung().isEmpty()) b.append(Sprache.t("Included: ", "Dabei: ")).append(s.beschreibung()).append('\n');
        }
        if (!s.name().isEmpty()) b.append(Sprache.t("Player name: ", "Spielername: ")).append(s.name()).append('\n');
        if (s.squadPower() > 0) b.append("Squad power: ").append(String.format(Locale.GERMANY, "%,d", s.squadPower())).append('\n');
        if (!s.einheiten().isEmpty()) {
            b.append(Sprache.t("Units: ", "Einheiten: "));
            for (int i = 0; i < s.einheiten().size(); i++) {
                Einheit e = s.einheiten().get(i);
                b.append(i > 0 ? ", " : "").append(e.name());
                if (e.kraft() > 0) b.append(" (").append(String.format(Locale.GERMANY, "%,d", e.kraft())).append(")");
            }
            b.append('\n');
        }
        if (!s.opfer().isEmpty()) b.append(Sprache.t("Sacrifice unit (lowest power): ", "Opfereinheit (kleinste Kraft): ")).append(s.opfer()).append('\n');
        if (s.held() != null) {
            b.append(Sprache.t("Hero: ", "Held: ")).append(s.held().name());
            if (s.held().kraft() > 0) b.append(" (").append(String.format(Locale.GERMANY, "%,d", s.held().kraft())).append(")");
            b.append(Sprache.t(", main skill ", ", Hauptfaehigkeit ")).append(s.held().hauptGut() == null ? Sprache.t("not checkable", "nicht pruefbar") : s.held().hauptGut() ? Sprache.t("good", "gut") : Sprache.t("normal", "normal"))
             .append(Sprache.t(", good side skills ", ", gute Nebenfaehigkeiten ")).append(s.held().guteNeben())
             .append(" (+").append(heldPunkte(s.held())).append(")").append('\n');
        }
        if (s.schirme() != null) b.append(s.schirme().text()).append('\n');
        if (!s.schirmLevel().isEmpty()) {
            b.append(Sprache.t("Patrons (level): ", "Schirmherren (Level): "));
            for (int i = 0; i < s.schirmLevel().size(); i++) b.append(i > 0 ? ", " : "").append(s.schirmLevel().get(i));
            b.append('\n');
        }
        if (s.versuch() != null && s.stand() == null) {
            b.append(Sprache.t("Last attempt cancelled: ", "Letzter Versuch abgebrochen: ")).append(s.versuch().format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))).append('\n');
        }
        b.append(Sprache.t("Automatic (every 3 days): ", "Automatik (alle 3 Tage): ")).append(automatik ? Sprache.t("on", "an") : Sprache.t("off", "aus"));
        return b.toString();
    }
}
