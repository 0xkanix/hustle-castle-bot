package de.hustlebot.core;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.util.function.BooleanSupplier;

/**
 * Erkennt, wenn AUTOR die Maus bewegt, waehrend der Bot spielt -- dann pausiert der Ablauf
 * (Autor, 14.10.: "Wenn der Bot laeuft, kann ich die Maus nicht bewegen, er zieht sie immer wieder zurueck.
 * Kannst du einbauen, dass der Bot erkennt, wann der Benutzer die Maus benutzt, und dann automatisch pausiert?").
 *
 * So geht es: Jede Mausbewegung des Bots meldet sich hier (botBewegt). Alle 200 ms wird nachgesehen, wo der Zeiger
 * steht. Steht er zweimal hintereinander mehr als 40 Bildpunkte neben der Stelle, an die der Bot ihn zuletzt gesetzt
 * hat, war es ein Mensch. Dann ruft die Wache einmal beiBenutzer auf (der Bot pausiert).
 *
 * Sicherheitsnetz fuer die Nacht (Clan-Krieg 03:01!): Steht die Maus danach 5 Minuten still, wird von selbst
 * fortgesetzt -- ein Fehlalarm kann den Bot also nie die ganze Nacht anhalten. Sofort weiter: F10.
 */
public final class Mauswache {

    /**
     * Ab so viel Abstand gilt eine Bewegung als menschlich (Rundungen, 1-Pixel-Wackeln des Wach-Haltens liegen darunter).
     * Autor 21.10.: "resistenter -- nicht nach einer Mini-Bewegung sofort stoppen, sondern erst, wenn man sie ein bisschen
     * bewegt hat. Bisschen mehr." Darum 120 statt 40 Bildpunkte, und es braucht zwei Sichtungen (siehe BESTAETIGEN_MS).
     */
    static final int SCHWELLE = 120;
    /** Eine fremde Zeigerstelle zaehlt erst, wenn sie innerhalb dieser Zeit ein zweites Mal gesehen wird (Autor 21.10.). */
    static final long BESTAETIGEN_MS = 2_000;
    /** Wann zuletzt eine fremde Zeigerstelle direkt vor einer Bot-Bewegung gesehen wurde (0 = nie). */
    private static volatile long ersteSichtungUm = 0;
    /** Nach so langer Ruhe geht es von selbst weiter. */
    static final long WEITER_NACH_MS = 5 * 60_000L;
    /**
     * Fehlerbericht von Karla (16.10.): Start mit der Maus geklickt, die Hand noch bewegt -- 1,2 s spaeter
     * "mouse moved", der Bot stand. Nach jedem Start (und Fortsetzen) zaehlt die Maus darum 3 s lang nicht.
     */
    static final long SCHONFRIST_MS = 3_000;
    private static volatile long schonfristBis = 0;

    private static volatile int botX = Integer.MIN_VALUE, botY = Integer.MIN_VALUE;
    private static volatile Thread faden;

    private Mauswache() { }

    /** 16.10.: ein Mensch hat die Maus bewegt -- gesehen unmittelbar VOR einer Bewegung des Bots. */
    private static volatile boolean menschGesehen;

    /**
     * Vor jeder Mausbewegung des Bots aufrufen. Autor 16.10. ("Der Bot hat nicht aufgehoert, nachdem ich meine Maus
     * bewegt habe"): In der Arena klickt der Bot fast ununterbrochen und setzt den Zeiger jedes Mal selbst -- die Wache
     * (alle 200 ms, zwei Treffer hintereinander noetig) sah die fremde Bewegung nie zweimal. Steht der Zeiger direkt vor
     * einer Bot-Bewegung weit weg von der letzten Bot-Stelle, war es ein Mensch: sofort pausieren.
     */
    /** Spiel-Neustart (16.10.): der Launcher klickt mit eigener Maus -- so lange zaehlt keine Bewegung als Mensch. */
    public static void ruheFuer(long ms) {
        schonfristBis = Math.max(schonfristBis, System.currentTimeMillis() + ms);
    }

    public static void vorBotBewegung() {
        long jetzt = System.currentTimeMillis();
        if (jetzt < schonfristBis) return;
        if (!zeigerWeg()) return;
        // Autor 21.10.: eine einzelne Sichtung reicht nicht mehr -- erst die zweite innerhalb von 2 s (ein Mensch
        // bewegt die Maus laenger als einen Augenblick; ein einzelner Ausreisser stoppt den Bot nicht).
        if (zweiteSichtung(ersteSichtungUm, jetzt)) menschGesehen = true;
        else ersteSichtungUm = jetzt;
    }

    /** Ist dies die zweite Sichtung innerhalb von BESTAETIGEN_MS nach der ersten? */
    static boolean zweiteSichtung(long ersteUm, long jetzt) {
        return ersteUm > 0 && jetzt - ersteUm <= BESTAETIGEN_MS;
    }

    /** Von jeder Mausbewegung des Bots aufrufen -- vor und nach robot.mouseMove. */
    public static void botBewegt(int x, int y) {
        botX = x;
        botY = y;
    }

    /**
     * Dirk 17.10. ("Er macht immer nur einen Klick und danach denkt er, dass ich die Maus bewege -- obwohl die Maus
     * nicht mal angeschlossen war"), Karla 16.10. ebenso: Bei Windows-Skalierung ungleich 100 % meldet Java die
     * Zeigerstelle in anderen Einheiten, als robot.mouseMove sie setzt -- nach dem ersten Klick des Bots lag der
     * Zeiger "40 Bildpunkte daneben" und galt als Mensch. Darum merkt sich die Wache nach jeder Bot-Bewegung auch,
     * wo der Zeiger laut Java wirklich steht; als Mensch zaehlt nur, was von BEIDEN Stellen weit weg ist.
     */
    private static volatile int istX = Integer.MIN_VALUE, istY = Integer.MIN_VALUE;

    /** Direkt nach robot.mouseMove aufrufen: die gemessene Zeigerstelle als Bot-Stelle merken. */
    public static void botGemessen() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return;
            Point p = pi.getLocation();
            istX = p.x;
            istY = p.y;
        } catch (Exception ignored) {
            // ohne Messung gilt wie bisher nur die gesetzte Stelle
        }
    }

    /** Weit weg von der gesetzten UND der gemessenen Bot-Stelle? Dann war es ein Mensch. */
    static boolean weitWeg(int x, int y) {
        return menschlich(x, y, botX, botY) && (istX == Integer.MIN_VALUE || menschlich(x, y, istX, istY));
    }

    private static void beideMerken(int x, int y) {
        botBewegt(x, y);
        istX = x;
        istY = y;
    }

    /** Steht der Zeiger gerade weit weg von der Stelle, an die der Bot ihn zuletzt gesetzt hat? (Dauerklick: dann abbrechen) */
    public static boolean zeigerWeg() {
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return false;
            Point p = pi.getLocation();
            return weitWeg(p.x, p.y);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ist (x,y) so weit von der Bot-Stelle (bx,by) weg, dass es ein Mensch war? */
    static boolean menschlich(int x, int y, int bx, int by) {
        if (bx == Integer.MIN_VALUE) return false;
        long dx = x - bx, dy = y - by;
        return dx * dx + dy * dy > (long) SCHWELLE * SCHWELLE;
    }

    /**
     * @param laeuft      true, solange ein Modus spielt (nicht pausiert)
     * @param pausiert    true, solange der Bot pausiert ist
     * @param beiBenutzer Mensch hat die Maus bewegt: pausieren; true = wirklich pausiert (sonst keine fremde Pause uebernehmen)
     * @param weiter      nach 5 min Ruhe: fortsetzen (nur wenn die Wache selbst pausiert hat)
     */
    /**
     * Schalter (Autor 16.10.: "eine Einstellung, dass man das mit der Maus an- und ausmachen kann"): "aus" in
     * mauswache.txt = eine Mausbewegung pausiert den Bot NICHT. Ohne Datei: an (wie bisher).
     */
    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("mauswache.txt"), "Mauswache", true);

    public static boolean an() {
        return SCHALTER.lesen();
    }

    public static void setzen(boolean an) {
        SCHALTER.setzen(an, Sprache.t("Stop when the mouse moves: on/off (Settings -> Mouse)",
                "Stopp bei Mausbewegung: an/aus (Einstellungen -> Maus)"));
    }

    public static synchronized void starten(BooleanSupplier laeuft, BooleanSupplier pausiert, BooleanSupplier beiBenutzer, Runnable weiter) {
        if (faden != null) return;
        Thread t = new Thread(() -> {
            boolean warAktiv = false;
            int verdacht = 0;
            boolean selbstPausiert = false;
            Point letzte = null;
            long stillSeit = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(200);
                    PointerInfo pi = MouseInfo.getPointerInfo();
                    if (pi == null) continue;
                    Point p = pi.getLocation();
                    long jetzt = System.currentTimeMillis();
                    if (selbstPausiert) {
                        if (!pausiert.getAsBoolean()) {          // jemand hat fortgesetzt (F10, !weiter)
                            selbstPausiert = false;
                            warAktiv = false;
                            continue;
                        }
                        // "Still" = nicht weiter als die Schwelle vom letzten Ruhepunkt weg (Review 14.10.: vorher reichte
                        // 1 Bildpunkt Wackeln, um die 5-Minuten-Uhr immer wieder auf null zu setzen -> Pause die ganze Nacht).
                        if (letzte == null || menschlich(p.x, p.y, letzte.x, letzte.y)) {
                            stillSeit = jetzt;
                            letzte = p;
                        }
                        if (jetzt - stillSeit >= WEITER_NACH_MS) {
                            selbstPausiert = false;
                            warAktiv = false;
                            weiter.run();
                        }
                        continue;
                    }
                    // Hintergrund-Modus (Autor 20.11.): Autor bewegt seine Maus absichtlich (Minecraft) -- keine Wache.
                    // Beim Umschalten zaehlt das wie ein frischer Start (Schonfrist).
                    boolean aktiv = laeuft.getAsBoolean() && !Hintergrund.an();
                    if (!aktiv) {
                        warAktiv = false;
                        verdacht = 0;
                        menschGesehen = false;
                        continue;
                    }
                    if (!warAktiv) {                             // gerade gestartet oder fortgesetzt: hier steht die Maus
                        beideMerken(p.x, p.y);
                        warAktiv = true;
                        verdacht = 0;
                        menschGesehen = false;
                        schonfristBis = jetzt + SCHONFRIST_MS;
                        continue;
                    }
                    if (jetzt < schonfristBis) {                 // Schonfrist: wo die Maus jetzt steht, gilt als Bot-Stelle
                        beideMerken(p.x, p.y);
                        verdacht = 0;
                        menschGesehen = false;
                        continue;
                    }
                    boolean vorBot = menschGesehen;
                    if (vorBot || weitWeg(p.x, p.y)) {
                        // Autor 21.10.: drei Blicke hintereinander (0,6 s) statt zwei -- eine kurze Zuckung stoppt nicht.
                        if (vorBot || ++verdacht >= 3) {
                            menschGesehen = false;
                            ersteSichtungUm = 0;
                            verdacht = 0;
                            if (beiBenutzer.getAsBoolean()) {
                                selbstPausiert = true;
                                letzte = p;
                                stillSeit = jetzt;
                            }
                        }
                    } else {
                        verdacht = 0;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.debug("Mauswache", e.getMessage());
                }
            }
        }, "mauswache");
        t.setDaemon(true);
        t.start();
        faden = t;
        Log.info("Mauswache", de.hustlebot.core.Sprache.t("Active: if you move the mouse while the bot is playing, it stops (switch off: Settings -> Mouse).", "Aktiv: bewegst du die Maus, waehrend der Bot spielt, stoppt er (abschalten: Einstellungen -> Maus)."));
    }
}
