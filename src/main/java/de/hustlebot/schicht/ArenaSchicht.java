package de.hustlebot.schicht;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Arena-Schicht (Autor 28.10., Alpha): solange die Pyramide zu sehen ist (der Bot spielt Arena, oder Autor spielt
 * selbst und der Beobachter schaut zu), liegt um jeden Gegner ein dezenter Rahmen -- Ampel-Farbe, wenn er auf einer
 * Liste steht, sonst grau --, und neben dem Spiel steht das Seitenfenster mit Ziel und Rechenzettel.
 *
 * Stufe 2 (Autor 28.10.): Bedienung ohne Maus ueber einen Tastatur-/Maus-Haken (EingabeHaken, Logik in
 * SchichtSteuerung). Er verschluckt nur die Tasten der Schicht und nur, solange die Pyramide zu sehen ist UND das
 * Spiel vorn ist; F9/F10/F11 nie, die eigenen Klicks des Bots nie. Was Autor entscheidet, geht als Befehl in
 * ArenaStand (der Arena-Modus holt es ab); Kommentare, Zustimmen und Ablehnen zusaetzlich in arena-kommentare.txt
 * und nach Discord.
 *
 * Nur mit arena-schicht.txt "an" (ArenaSchalter). Fasst das Spielfenster nie an (kein prepare(), keine Klicks).
 * Ist das Spiel nicht vorn oder minimiert, oder ist die Pyramide weg, verschwindet alles.
 */
public final class ArenaSchicht {

    /** Wie oft nachgesehen wird. */
    private static final long TAKT_MS = 400;
    /**
     * So lange bleibt das Seitenfenster mit dem letzten Stand stehen, wenn die Pyramide gerade nicht zu sehen ist. Autor 30.10.: "fuer die
     * kompletten 5 Arena-Stages offen" -- auch bei Kampf, Vergleichsfenster, Chat und wenn ein anderes Fenster vorn ist; danach geht es zu.
     */
    private static final long SEITE_HALTBAR_MS = 10 * 60_000L;
    /** Kommentare, Zustimmen, Ablehnen -- eine Zeile je Eintrag, im Bot-Ordner. */
    static final String KOMMENTAR_DATEI = "arena-kommentare.txt";

    private static ArenaSchicht laufend;

    private final GameWindow spiel;
    private final SchichtFenster schicht = new SchichtFenster();
    private final SeitenFenster seite;
    private final SchichtSteuerung steuerung;
    private final EingabeHaken haken = new EingabeHaken();
    private final Consumer<String> discord;
    /** Takt und sofortiges Neuzeichnen -- ein Faden, darum braucht der Takt-Zustand unten keinen Abgleich. */
    private final ScheduledExecutorService takt = Executors.newSingleThreadScheduledExecutor(faden("arena-schicht"));
    /** Datei und Discord (darf dauern -- nie im Haken). */
    private final ExecutorService schreiber = Executors.newSingleThreadExecutor(faden("arena-schicht-schreiber"));
    private final AtomicBoolean angestossen = new AtomicBoolean();

    /** Fuer den Haken (anderer Faden), vom Takt gesetzt: das Spielfenster und sein Innenbereich in Bildschirmpunkten. */
    private volatile WinDef.HWND spielHwnd;
    private volatile Rectangle innenSchirm;
    /** Der letzte echte Stand -- waehrend eines Kommentars bleibt das Seitenfenster damit stehen. Der Takt setzt, ein Klick liest. */
    private volatile ArenaStand.Stand zuletzt;
    /** Wann Autor zuletzt auf einen Spieler geklickt hat (zum Nachmessen: wie schnell sind die Rahmen weg?), 0 = schon gemessen. */
    private volatile long klickUm;

    /** So lange bleiben die Rahmen nach einem Klick auf einen Spieler weg, falls gar kein Vergleichsfenster kommt. */
    private static final long KLICK_AUS_MS = 1_000;
    /** So lange nach der letzten Pyramide werden Klicks noch verfolgt (Vergleichsfenster, Kampf, Ergebnis). */
    private static final long KLICK_NUR_ARENA_MS = 5 * 60_000L;

    // ---- nur auf dem Takt-Faden ----
    private boolean sichtbar;
    /** Review 28.10.: nur neu zeichnen, wenn sich etwas geaendert hat. */
    private List<SchichtFenster.Rahmen> gezeigteRahmen;
    private Rectangle rahmenFuer;
    private ArenaStand.Stand seiteStand;
    /** Der letzte Stand mit gelesenen Gegnern. */
    private volatile ArenaStand.Stand seiteGelesen;
    private long seiteGemaltUm;
    private SchichtSteuerung.Anzeige seiteAnzeige;
    private Rectangle seiteFuer;

    private ArenaSchicht(String fensterTitel, Consumer<String> discord) {
        // Eigene Instanz nur zum Nachsehen (locateVisible/clientArea) -- die des Bots bleibt unberuehrt.
        this.spiel = new GameWindow(fensterTitel, 0, 0);
        this.discord = discord;
        this.steuerung = new SchichtSteuerung(new WindowsTastatur(() -> spielHwnd), ArenaStand::befehl, this::notieren);
        this.seite = new SeitenFenster(platz -> {
            if (platz == SchichtSteuerung.KLICK_WEG) {
                seiteUmschalten();
                return;
            }
            ArenaStand.Stand s = ArenaStand.aktuell();
            ArenaStand.Stand st = s != null ? s : zuletzt;
            boolean meinung = platz == SchichtSteuerung.KLICK_ZUSTIMMEN || platz == SchichtSteuerung.KLICK_ABLEHNEN
                    || platz == SchichtSteuerung.KLICK_KOMMENTAR || platz == SchichtSteuerung.KLICK_STAGE_KOMMENTAR;
            steuerung.klick(platz, meinung ? fuerSeite(st) : st, System.currentTimeMillis());
            anstossen();
        });
    }

    private static ThreadFactory faden(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Startet die Schicht (Rahmen, Seitenfenster, Tasten), wenn der Schalter an ist. Mehrfacher Aufruf schadet nicht.
     *
     * @param discord bekommt Kommentare, Zustimmen und Ablehnen als Text (null = nicht nach Discord)
     */
    public static synchronized void starten(String fensterTitel, Consumer<String> discord) {
        if (!ArenaStand.an() || laufend != null || GraphicsEnvironment.isHeadless()) return;
        ArenaSchicht a = new ArenaSchicht(fensterTitel, discord);
        laufend = a;
        a.takt.scheduleWithFixedDelay(a::taktSicher, 1000, TAKT_MS, TimeUnit.MILLISECONDS);
        Log.info("Arena-Schicht", Sprache.t("Arena overlay is on (arena-schicht.txt): frames and side window "
                        + "appear while the arena pyramid is on screen.",
                "Arena-Schicht ist an (arena-schicht.txt): Rahmen und Seitenfenster erscheinen, solange die "
                        + "Arena-Pyramide zu sehen ist."));
        if (!SchichtFenster.aufnahmeSchutzMoeglich()) {
            Log.warn("Arena-Schicht", Sprache.t("This Windows cannot hide the frames from screenshots (needs Windows 10 "
                            + "2004 or newer) -- the frames stay off so the bot is not disturbed; the side window still works.",
                    "Dieses Windows kann die Rahmen nicht vor Bildschirmfotos verstecken (braucht Windows 10 ab 2004) -- "
                            + "die Rahmen bleiben aus, damit der Bot nicht gestoert wird; das Seitenfenster geht trotzdem."));
        }
        a.haken.setSchlucken(a::schlucken);
        a.haken.setLinksKlick(a::linksKlick);
        ArenaStand.beiWechsel(a::anstossen);
        if (a.haken.start()) {
            Log.info("Arena-Schicht", Sprache.t("Arena keys (only while the pyramid is on screen and the game is in "
                            + "front): " + SchichtSteuerung.hilfe(true),
                    "Arena-Tasten (nur solange die Pyramide zu sehen und das Spiel vorn ist): "
                            + SchichtSteuerung.hilfe(true)));
        }
    }

    // ---- Eingabe-Haken (laeuft IM Haken: muss sofort antworten) ---------------------------------------------------

    /** Verschlucken? Nur Tasten der Schicht, nur bei Pyramide (oder laufendem Kommentar) und Spiel vorn. */
    private boolean schlucken(EingabeHaken.Ereignis e) {
        if (e.art() == EingabeHaken.Art.TASTE && SchichtSteuerung.nieSchlucken(e.vkCode())) return false;
        if (ArenaStand.chatGeradeOffen()) return false;   // Autor 29.10.: im Spiel-Chat muss jede Taste ankommen
        // Auch ohne Pyramide: das Seitenfenster bleibt ja 10 Minuten stehen. Beim Schreiben eines Kommentars ist ^ ein Buchstabe.
        if (e.art() == EingabeHaken.Art.TASTE && e.vkCode() == SchichtSteuerung.VK_DACH && sichtbar && !steuerung.modal() && spielVorn()) {
            seiteUmschalten();
            return true;
        }
        ArenaStand.Stand s = ArenaStand.aktuell();
        if (s == null && !steuerung.modal()) return false;   // der haeufigste Fall: sofort raus
        if (!spielVorn()) return false;
        long jetzt = System.currentTimeMillis();
        // J, N und die Kommentare meinen, was das Seitenfenster gerade zeigt (23:48: Kommentar zu Platz 7 ohne Namen, der Stand war gerade leer).
        // Die Listen-Tasten bleiben beim frischen Stand: die Pyramide kann sich umgeordnet haben.
        boolean meinung = e.art() == EingabeHaken.Art.TASTE && (e.vkCode() == SchichtSteuerung.VK_J || e.vkCode() == SchichtSteuerung.VK_N
                || e.vkCode() == SchichtSteuerung.VK_K || e.vkCode() == SchichtSteuerung.VK_G);
        boolean geschluckt = e.art() == EingabeHaken.Art.TASTE
                ? steuerung.taste(e.vkCode(), meinung ? fuerSeite(s) : s, jetzt)
                : steuerung.maus(e.art(), e.vorne(), platzBei(s, innenSchirm, e.schirmPunkt()), s, jetzt);
        if (geschluckt) anstossen();
        return geschluckt;
    }

    /**
     * Autor 30.10.: "Er kann ja meine Klicks nachvollziehen." Laeuft IM Haken, der Klick geht immer ans Spiel. Merkt nur, wohin geklickt
     * wurde; der Arena-Modus ordnet daraus zu, gegen wen Autor kaempft. Klick auf einen Spieler: die Rahmen sofort weg, das
     * Vergleichsfenster geht gleich auf (kommt keins, sind sie nach KLICK_AUS_MS wieder da).
     */
    private void linksKlick(java.awt.Point p) {
        Rectangle innen = innenSchirm;
        if (innen == null || innen.width <= 0 || innen.height <= 0 || !innen.contains(p) || !spielVorn()) return;
        long jetzt = System.currentTimeMillis();
        ArenaStand.Stand s = ArenaStand.aktuell();
        ArenaStand.Stand z = zuletzt;
        if (s == null && (z == null || jetzt - z.um() > KLICK_NUR_ARENA_MS)) return;   // ausserhalb der Arena interessiert kein Klick
        int platz = s == null ? 0 : platzBei(s, innen, p);
        ArenaStand.Gegner g = platz == 0 ? null : SchichtSteuerung.gegnerAuf(s, platz);
        ArenaStand.klickMelden(new ArenaStand.Klick(jetzt, (p.x - innen.x) / (double) innen.width,
                (p.y - innen.y) / (double) innen.height, platz, g == null ? "" : g.name(), g == null ? "" : g.clan()));
        if (platz > 0) {
            klickUm = jetzt;
            ArenaStand.kurzAus(KLICK_AUS_MS);
            anstossen();
        }
    }

    private boolean spielVorn() {
        WinDef.HWND h = spielHwnd;
        if (h == null) return false;
        WinDef.HWND vorn = User32.INSTANCE.GetForegroundWindow();
        return vorn != null && h.equals(vorn);
    }

    /** Gleich neu zeichnen (auf dem Takt-Faden), statt bis zum naechsten Takt zu warten. Blockiert nie. */
    private void anstossen() {
        if (!angestossen.compareAndSet(false, true)) return;
        try {
            takt.execute(() -> {
                angestossen.set(false);
                taktSicher();
            });
        } catch (RejectedExecutionException ignoriert) {
            angestossen.set(false);
        }
    }

    /** Kommentar/Zustimmen/Ablehnen: Datei + Discord auf eigenem Faden (aus dem Haken heraus gerufen). */
    private void notieren(SchichtSteuerung.Notiz n) {
        try {
            schreiber.execute(() -> notizSchreiben(n));
        } catch (RejectedExecutionException ignoriert) {
            // beim Beenden
        }
    }

    /** Der Stand mit gelesenen Gegnern, wie ihn das Seitenfenster zeigt (anderer Faden: nur lesen). */
    private ArenaStand.Stand seiteGelesenFuerNotiz() {
        ArenaStand.Stand g = seiteGelesen;
        return g != null ? g : zuletzt;
    }

    /** Autor hat das Seitenfenster weggedrueckt (Minus oder ^). Die naechste Arena macht es wieder auf: sie soll es sofort zeigen. */
    private volatile boolean seiteAus;
    private volatile int seiteAusRunde;
    private boolean seiteWeg;

    private void seiteUmschalten() {
        ArenaStand.Stand z = zuletzt;
        seiteAusRunde = z == null ? 0 : z.runde();
        seiteAus = !seiteAus;
        anstossen();
    }

    /** Was das Seitenfenster zu diesem Stand zeigt: liest der Beobachter gerade neu, bleibt die letzte Gegnerliste stehen, nur eine neue Arena (kleinere Stage) beendet sie. */
    private ArenaStand.Stand fuerSeite(ArenaStand.Stand s) {
        ArenaStand.Stand g = seiteGelesen;
        return s != null && s.gegner().isEmpty() && g != null && g.runde() <= s.runde() ? g : s;
    }

    /** Die Pyramide in einer Zeile: fuer Kommentare zur Stage und fuer Ablehnungen ("Platz Name +Belohnung Chance"). */
    static String lageText(ArenaStand.Stand st) {
        StringBuilder b = new StringBuilder();
        for (ArenaStand.Gegner x : st.gegner()) {
            if (b.length() > 0) b.append("; ");
            b.append(x.platz()).append(' ').append(x.anzeige()).append(" +").append(x.belohnung());
            if (x.siegchance() >= 0) b.append(' ').append(x.siegchance()).append(" %");
            if (x.ziel()) b.append(" (bot)");
        }
        return b.toString();
    }

    private void notizSchreiben(SchichtSteuerung.Notiz n) {
        String text = SchichtSteuerung.discordText(n);
        Log.info("Arena-Schicht", text);
        // Autor 30.10.: Kommentare, Zustimmen und Ablehnen als Paket je Arena an die Sammelstelle, dazu Stage und Chance des Spielers.
        if (n.art() == ArenaStand.Befehl.Art.KOMMENTAR || n.art() == ArenaStand.Befehl.Art.ZUSTIMMEN || n.art() == ArenaStand.Befehl.Art.ABLEHNEN) {
            ArenaStand.Stand st = seiteGelesenFuerNotiz();
            ArenaStand.Gegner g = st == null ? null : SchichtSteuerung.gegnerAuf(st, n.platz());
            boolean mitLage = st != null && (n.platz() == 0 || n.art() == ArenaStand.Befehl.Art.ABLEHNEN);
            de.hustlebot.core.KommentarPakete.anhaengen(SchichtSteuerung.zeile(n)
                    + (st == null ? "" : " | Stage " + st.runde())
                    + (g == null || g.siegchance() < 0 ? "" : " | Chance " + g.siegchance() + " %")
                    + (mitLage ? " | Lage: " + SchichtSteuerung.feld(lageText(st)) : ""));
        }
        try {
            Files.writeString(Ordner.bei(KOMMENTAR_DATEI), SchichtSteuerung.zeile(n) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.warn("Arena-Schicht", Sprache.t("Could not write " + KOMMENTAR_DATEI + ": " + e.getMessage(),
                    KOMMENTAR_DATEI + " nicht schreibbar: " + e.getMessage()));
        }
        if (discord == null) return;
        try {
            discord.accept(text);
        } catch (Exception e) {
            Log.debug("Arena-Schicht", "Discord: " + e);
        }
    }

    // ---- Takt -------------------------------------------------------------------------------------------------

    private void taktSicher() {
        try {
            takt();
        } catch (Throwable t) {
            // Die Schicht darf den Bot nie stoeren -- ein Fehler versteckt sie nur.
            Log.debug("Arena-Schicht", "Fehler: " + t);
            sichtbar = true;   // sicher alles verstecken
            verstecken();
        }
    }

    private void takt() {
        long jetzt = System.currentTimeMillis();
        // Autor 29.10.: Chat offen -> keine Rahmen. Autor 30.10.: "Das Seitenfenster soll fuer die kompletten 5 Arena-Stages offen bleiben,
        // auch wenn ich im Chat oder Vergleichsfenster bin." Es haengt also nur noch an SEITE_HALTBAR_MS.
        ArenaStand.Stand echt = ArenaStand.chatGeradeOffen() ? null : ArenaStand.aktuell();
        SchichtSteuerung.Anzeige a = steuerung.anzeige(echt, jetzt);
        if (echt != null) zuletzt = echt;
        // Waehrend Kommentar/Grund-Frage bleibt das Seitenfenster stehen, auch wenn die Pyramide kurz weg ist.
        // Autor 29.10.: "das Seitenfenster ist viel zu selten offen" -- es steht neben dem Spiel und verdeckt nichts: den
        // letzten Stand bis SEITE_HALTBAR_MS weiter zeigen (Erkunden, Vergleichsfenster, Ergebnis). Rahmen nur bei echter
        // Pyramide (weiter unten, echt == null -> keine Rahmen).
        ArenaStand.Stand s = echt != null ? echt
                : a.modus() != SchichtSteuerung.Modus.NORMAL || (zuletzt != null && jetzt - zuletzt.um() < SEITE_HALTBAR_MS) ? zuletzt : null;
        if (s == null) {
            verstecken();
            return;
        }
        // Review 28.10.: nur neu suchen, wenn das alte Fenster weg ist (die Suche geht alle Fenster durch).
        if (!spiel.isAlive() && !spiel.locateVisible()) {
            spielHwnd = null;
            verstecken();
            return;
        }
        spielHwnd = spiel.handle();
        if (spiel.isMinimized()) {
            verstecken();
            return;
        }
        // Rahmen nur bei Spiel vorn (sie liegen immer obenauf, auch ueber einer anderen App); das Seitenfenster steht daneben und bleibt.
        boolean vorn = spiel.istImVordergrund();
        Rectangle innen = spiel.clientArea();
        if (innen == null) {
            verstecken();
            return;
        }
        innenSchirm = innen;
        sichtbar = true;   // vor dem Zeigen: wirft danach etwas, versteckt taktSicher() trotzdem alles

        // Rahmen nur bei echter Pyramide. SchichtFenster zeigt nichts, solange Windows es nicht vor Fotos versteckt.
        List<SchichtFenster.Rahmen> r = echt == null || !vorn ? List.of() : rahmen(echt, innen.width, innen.height, a);
        if (r.isEmpty()) {
            if (gezeigteRahmen != null) schicht.verstecken();
            gezeigteRahmen = null;
            long geklickt = klickUm;
            if (geklickt != 0) {
                klickUm = 0;
                // Das Verstecken laeuft auf dem Fenster-Faden, dies kommt direkt danach dran: so wird die echte Zeit gemessen.
                javax.swing.SwingUtilities.invokeLater(() -> Log.debug("Arena-Schicht",
                        "Rahmen " + (System.currentTimeMillis() - geklickt) + " ms nach dem Klick weg."));
            }
        } else if (!r.equals(gezeigteRahmen) || !innen.equals(rahmenFuer)) {
            schicht.zeigen(innen, r);
            gezeigteRahmen = r;
            rahmenFuer = innen;
        }

        // Seitenfenster: geht mit der ersten Pyramide auf und bleibt. Liest der Beobachter gerade neu, bleibt die letzte Gegnerliste stehen,
        // auch beim Wechsel der Stage; nur in einer neuen Arena (kleinere Stage) gilt sie nicht mehr.
        if (!s.gegner().isEmpty()) seiteGelesen = s;
        if (seiteAus && s.runde() < seiteAusRunde) seiteAus = false;
        if (seiteAus) {
            if (!seiteWeg) seite.verstecken();
            seiteWeg = true;
            seiteStand = null;
            return;
        }
        seiteWeg = false;
        ArenaStand.Stand zeige = fuerSeite(s);
        boolean nurUhr = seiteStand != null && zeige.runde() == seiteStand.runde() && zeige.gegner().equals(seiteStand.gegner())
                && zeige.plaetze().equals(seiteStand.plaetze());
        if (!nurUhr || jetzt - seiteGemaltUm >= 1_000 || !a.equals(seiteAnzeige) || !innen.equals(seiteFuer)) {
            seite.zeigen(innen, zeige, a);
            seiteStand = zeige;
            seiteAnzeige = a;
            seiteFuer = innen;
            seiteGemaltUm = jetzt;
        }
    }

    private void verstecken() {
        gezeigteRahmen = null;
        seiteStand = null;
        if (!sichtbar) return;
        schicht.verstecken();
        seite.verstecken();
        sichtbar = false;
    }

    // ---- Rahmen und Schirmpunkte ----------------------------------------------------------------------------------

    static List<SchichtFenster.Rahmen> rahmen(ArenaStand.Stand s, int innenBreite, int innenHoehe) {
        return rahmen(s, innenBreite, innenHoehe, null);
    }

    /**
     * Ein Rahmen um JEDEN Gegner (auch die schon besiegten), umgerechnet auf die Groesse des Spielfensters:
     * Ampel-Farbe, wenn er auf einer Liste steht, sonst dezent grau (Autor 28.10.). Das eigene Schild hat keinen.
     * Mit Anzeige (Stufe 2): gerade Gespeichertes gilt sofort; die Auswahl ist kraeftiger, Vorgemerktes steht daran.
     */
    static List<SchichtFenster.Rahmen> rahmen(ArenaStand.Stand s, int innenBreite, int innenHoehe,
                                              SchichtSteuerung.Anzeige a) {
        List<SchichtFenster.Rahmen> l = new ArrayList<>();
        double fx = s.bildBreite() > 0 ? innenBreite / (double) s.bildBreite() : 1.0;
        double fy = s.bildHoehe() > 0 ? innenHoehe / (double) s.bildHoehe() : 1.0;
        // Autor 29.10. nachts: das Ziel des Bots auch im Spiel zeigen (ganzer Rahmen + kleines Schild "Bot ▶ 72 %").
        ArenaStand.Gegner ziel = SchichtSteuerung.ziel(s);
        for (ArenaStand.Platz p : s.plaetze()) {
            String stufe = a == null ? p.stufe() : a.stufen().getOrDefault(p.platz(), p.stufe());
            boolean gewaehlt = a != null && a.auswahl() == p.platz();
            boolean istZiel = ziel != null && ziel.platz() == p.platz();
            String text = null;
            if (gewaehlt && a.vormerkStufe() != null) stufe = a.vormerkStufe();
            if (gewaehlt) text = SchichtSteuerung.vormerkKurz(a);
            else if (istZiel) text = "Bot ▶" + (ziel.siegchance() >= 0 ? " " + ziel.siegchance() + " %" : "");
            Color f = SeitenFenster.farbe(stufe);
            Color farbe = f != null ? f : gewaehlt || istZiel ? SchichtFenster.AUSWAHL : SchichtFenster.UNBEKANNT;
            Rectangle r = schildRahmen(p.x(), p.y(), s.bildBreite());
            l.add(new SchichtFenster.Rahmen(new Rectangle((int) Math.round(r.x * fx), (int) Math.round(r.y * fy),
                    (int) Math.round(r.width * fx), (int) Math.round(r.height * fy)), farbe, text, gewaehlt, istZiel));
        }
        return l;
    }

    /**
     * Das ganze Schild um die Punktezeile (Schwerter + Zahl, ArenaTabelle), im Spielbild: es reicht 55 Punkte
     * darueber (Name, Clan) und 71 darunter (Belohnung samt Spitze) und ist 18,3 % des Bildes breit (212 von
     * 1159) -- an Autors echter Pyramide vom 28.10. abends nachgemessen ("sassen gut").
     * 30.10. ("liegt ziemlich ungerade"): der goldene Rahmen auf 6 echten Fotos ist 220 breit und 136 hoch, 60 ueber und 76 unter
     * der Punktezeile -- der alte Rahmen war 8 schmaler, oben 5 zu tief und unten 10 zu kurz. Die Plaetze selbst (ArenaTabelle)
     * stimmen: alle zehn weichen bei 522 echten Schildern aus dem Protokoll hoechstens 2 Punkte ab. Die Hoehe waechst jetzt mit dem Bild.
     */
    static Rectangle schildRahmen(int mitteX, int punkteY, int bildBreite) {
        double s = bildBreite > 0 ? bildBreite / 1159.0 : 1.0;
        int b = (int) Math.round(220 * s);
        return new Rectangle(mitteX - b / 2, punkteY - (int) Math.round(60 * s), b, (int) Math.round(136 * s));
    }

    /**
     * Welcher Platz liegt unter diesem Bildschirmpunkt (Rechts-/Mausrad-Klick)? Ueber dieselben Rechtecke wie die
     * Rahmen; liegen zwei uebereinander, gewinnt das, dessen Mitte naeher ist. 0 = keiner.
     */
    static int platzBei(ArenaStand.Stand s, Rectangle innen, Point schirm) {
        if (s == null || innen == null || schirm == null) return 0;
        int px = schirm.x - innen.x;
        int py = schirm.y - innen.y;
        List<SchichtFenster.Rahmen> r = rahmen(s, innen.width, innen.height);
        int best = 0;
        long bestAbstand = Long.MAX_VALUE;
        for (int i = 0; i < r.size(); i++) {
            Rectangle b = r.get(i).bereich();
            if (!b.contains(px, py)) continue;
            long dx = px - (long) b.getCenterX();
            long dy = py - (long) b.getCenterY();
            long d = dx * dx + dy * dy;
            if (d < bestAbstand) {
                bestAbstand = d;
                best = s.plaetze().get(i).platz();
            }
        }
        return best;
    }

    // ---- Windows: Zeichen und Umschalter (nur auf dem Haken-Faden) ------------------------------------------------

    private static final class WindowsTastatur implements SchichtSteuerung.Tastatur {
        private static final int VK_SHIFT = 0x10;
        private static final int VK_CONTROL = 0x11;
        private static final int VK_MENU = 0x12;
        private static final int VK_LWIN = 0x5B;
        private static final int VK_RWIN = 0x5C;
        private static final int VK_LCONTROL = 0xA2;
        private static final int VK_LMENU = 0xA4;
        private static final int VK_RMENU = 0xA5;
        private static final int MAPVK_VK_TO_VSC = 0;
        /** ToUnicodeEx: Tastaturzustand nicht veraendern (ab Windows 10 1607) -- Tottasten im Spiel bleiben heil. */
        private static final int ZUSTAND_LASSEN = 0x4;

        private final Supplier<WinDef.HWND> fenster;
        private final byte[] zustand = new byte[256];
        private final char[] puffer = new char[8];

        WindowsTastatur(Supplier<WinDef.HWND> fenster) {
            this.fenster = fenster;
        }

        private static boolean unten(int vk) {
            return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
        }

        @Override
        public boolean kombi() {
            // AltGr (rechte Alt-Taste) meldet Windows als Strg+Alt -- das ist Tippen (@, €, \), keine Kombi.
            boolean altGr = unten(VK_RMENU);
            return unten(VK_LMENU) || unten(VK_LWIN) || unten(VK_RWIN) || (unten(VK_CONTROL) && !altGr);
        }

        @Override
        public String zeichen(int vk) {
            try {
                Arrays.fill(zustand, (byte) 0);
                if (unten(VK_SHIFT)) zustand[VK_SHIFT] = (byte) 0x80;
                if (unten(VK_RMENU)) {
                    zustand[VK_CONTROL] = (byte) 0x80;
                    zustand[VK_LCONTROL] = (byte) 0x80;
                    zustand[VK_MENU] = (byte) 0x80;
                    zustand[VK_RMENU] = (byte) 0x80;
                }
                WinDef.HWND h = fenster.get();
                int faden = h == null ? 0 : User32.INSTANCE.GetWindowThreadProcessId(h, null);
                WinDef.HKL layout = User32.INSTANCE.GetKeyboardLayout(faden);
                int scan = User32.INSTANCE.MapVirtualKeyEx(vk, MAPVK_VK_TO_VSC, layout);
                int n = User32.INSTANCE.ToUnicodeEx(vk, scan, zustand, puffer, puffer.length, ZUSTAND_LASSEN, layout);
                if (n == 0) return "";
                if (n < 0) n = 1;   // Tottaste (^ ´ `): das Zeichen selbst
                return new String(puffer, 0, Math.min(n, puffer.length));
            } catch (Throwable t) {
                return "";
            }
        }
    }
}
