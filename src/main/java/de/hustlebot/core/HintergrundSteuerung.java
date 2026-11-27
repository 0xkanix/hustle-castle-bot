package de.hustlebot.core;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.LongSupplier;

/**
 * Steuerung im Hintergrund-Modus (EXPERIMENTELL, Autor 20.11., Schalter hintergrund.txt): der Bot spielt Hustle Castle,
 * waehrend Autor am selben Rechner etwas anderes macht. Das Spielfenster ist ein unsichtbarer Geist ausserhalb des
 * Bildschirms ({@link Geist}); Fotos kommen per PrintWindow, Tipps per Touch UNTER Autors Mauszeiger.
 *
 * NICHT NEBEN SPIELEN MIT VIELEN KLICKS BENUTZEN (Live-Test 20.11.): haelt das Vordergrundprogramm die Maus fest
 * (Maustaste gedrueckt, SetCapture), gehen die aus dem Touch erzeugten Mausklicks DORTHIN. Abgesichert wird das, so
 * gut es geht -- nie tippen, solange eine Maustaste gedrueckt oder eine Mausaufnahme aktiv ist, und das direkt vor dem
 * Touch noch einmal pruefen --, ein Restrisiko von wenigen Millisekunden bleibt.
 *
 * KOORDINATEN. Der Bot rechnet so, als laege die Fensterecke (mit Rahmen) bei 0,0: Punkt (x,y) des Bots = Punkt (x,y)
 * im PrintWindow-Bild. GameWindow.clientArea() liefert im Hintergrund-Modus genau diesen Innenbereich.
 *
 * EIN TIPP (Vorlage Autors Probe Geist11, live erprobt):
 *   1. warten, bis keine Maustaste gedrueckt und keine Mausaufnahme aktiv ist (ohne Zeitgrenze) und die echte Maus
 *      40 ms ruht (hoechstens 1,5 s);
 *   2. Zeiger c lesen, Tippstelle m = c +/- 1 (Seite wechselt: erzwingt ein WM_MOUSEMOVE, sonst wertet Unity die ALTE
 *      Mausstelle aus);
 *   3. Fenster so schieben, dass der Zielpunkt genau auf m liegt, klickbar machen, warten bis das Spiel zweimal seine
 *      Nachrichten abgeholt hat (Spiel-Takt -- passt sich der Last an; feste 15 ms reichten neben Minecraft nicht);
 *   4. Zeiger um mehr als 3 Punkte bewegt: von vorn; an m liegt nicht das Spiel oder die Maus ist belegt: nicht tippen,
 *      kurz warten, von vorn (hoechstens {@link #VERSUCHE}-mal, dann auslassen);
 *   5. Touch runter und sofort hoch an m -- EIN Moment; danach EINMAL den Zeiger zurueck: dorthin, wo Autors echte Maus
 *      jetzt steht (bewegt) bzw. auf c; Fenster wieder weg.
 * Darum fuehrt druecken() noch nichts aus: erst loslassen() (Tipp) oder die erste Bewegung (Zug) entscheidet. Hielt der
 * Bot laenger als {@link #LANG_AB_MS} (langer Klick), wird wirklich so lange gehalten. Ziehen: runter wie oben, dann
 * jeder Schritt an Autors aktueller Zeigerstelle +/- 1 mit neu geschobenem Fenster; hoch immer genau am Punkt des
 * letzten Schritts (sonst lehnt Windows ab und der Finger bliebe liegen). Bei jedem Fehler sofort hoch.
 *
 * Die Windows-Seite steckt hinter {@link Fenster}, damit der Ablauf ohne Windows pruefbar ist.
 */
public final class HintergrundSteuerung implements Steuerung {

    /** Was die Steuerung vom Spielfenster und von Windows braucht. Echt: {@link Geist.Zugang}. */
    public interface Fenster {
        /** Ist das Spielfenster da (und nicht gerade bei Autor)? */
        boolean da();

        /** Fenstergroesse mit Rahmen, oder null. */
        Dimension groesse();

        /** Das ganze Fenster per PrintWindow, Zeile fuer Zeile (0xRRGGBB), oder null. */
        int[] bild(int breite, int hoehe);

        Point zeiger();

        void zeigerSetzen(Point p);

        /** Maustaste gedrueckt oder Mausaufnahme (SetCapture) im Vordergrund? Dann NIE tippen. */
        boolean mausBelegt();

        boolean aufSchirm(Point p);

        /** Fenster so schieben, dass Fensterpunkt (fx,fy) auf m liegt; klickbar machen. */
        void ausrichten(int fx, int fy, Point m);

        /** Warten, bis das Spiel seine Nachrichten (zweimal) abgeholt hat. */
        void takt();

        /** Liegt an m wirklich das Spiel? */
        boolean frei(Point m);

        /** Wieder durchlaessig und an den Ruheplatz. */
        void ruhe();

        boolean beruehren(Point m, int flags);

        /** Zahl von Autors echten Mausbewegungen bisher. */
        long echteBewegungen();

        /** Wo Autors echte Maus zuletzt stand. */
        Point echteStelle();

        /** Seit wie vielen ms Autors echte Maus ruht. */
        long ruhtSeit();

        /** Warten; false = unterbrochen (Bot wird gestoppt). */
        boolean warten(long ms);
    }

    /** So lange muss die echte Maus vor einem Tipp ruhen ... */
    static final long RUHE_MS = 40;
    /** ... hoechstens so lange wird darauf gewartet (eine gedrueckte Taste dagegen wird ohne Grenze abgewartet). */
    static final long RUHE_MAX_MS = 1_500;
    static final long BELEGT_TAKT_MS = 20;
    /** Hielt der Bot kuerzer: ein Tipp (runter + hoch in einem Moment); laenger: wirklich so lange halten. */
    static final long LANG_AB_MS = 60;
    static final int ZEIGER_TOLERANZ = 3;
    static final int VERSUCHE = 4;
    static final long VERDECKT_PAUSE_MS = 60;
    static final long AUFFRISCHEN_MS = 250;
    static final long NOTLOS_MS = 8_000;
    /** Mehrere Ausschnitte desselben Moments (Kastenblick) teilen sich ein Foto. */
    static final long FOTO_GUELTIG_MS = 12;

    private final Fenster fenster;
    private final LongSupplier uhr;
    private final boolean mitPfleger;

    private int x, y;              // wohin der Bot "zeigt" (Bot-Koordinaten = Fensterpunkt)
    private boolean gedrueckt;     // der Bot hat druecken() gerufen, noch nicht loslassen()
    private long gedruecktUm;
    private int druckX, druckY;
    private boolean unten;         // der Touch liegt wirklich auf
    private Point letzt;           // Stelle des letzten Runter/Schritts (hoch muss genau dort sein)
    private Point basis;           // Autors Zeiger beim Vorbereiten
    private int seite = 1;
    private long letzteBeruehrungUm, untenSeit;
    private Thread pfleger;
    private long gewarntUm = -60_000;

    private final Object fotoSperre = new Object();
    private int[] bild;
    private int bildB, bildH;
    private long bildUm;
    private volatile boolean bildVeraltet = true;

    public HintergrundSteuerung(Fenster fenster) {
        this(fenster, System::currentTimeMillis, true);
    }

    /** Fuer Tests: eigene Uhr, ohne Pflege-Faden (dann pflegen() selbst rufen). */
    HintergrundSteuerung(Fenster fenster, LongSupplier uhr, boolean mitPfleger) {
        this.fenster = fenster;
        this.uhr = uhr;
        this.mitPfleger = mitPfleger;
    }

    // ---- Steuerung --------------------------------------------------------------------------------------------

    @Override
    public synchronized void bewegen(int nx, int ny) {
        x = nx;
        y = ny;
        if (!gedrueckt) return;
        if (!unten) {
            if (nx == druckX && ny == druckY) return;
            long halten = uhr.getAsLong() - gedruecktUm;   // so lange hielt der Bot vor dem Zug
            if (!runter(druckX, druckY)) {
                gedrueckt = false;                          // Zug faellt aus; loslassen() tut dann nichts
                return;
            }
            halten(halten);
            if (!unten) return;
        }
        schritt(nx, ny);
    }

    @Override
    public synchronized void druecken() {
        if (gedrueckt) return;
        gedrueckt = true;
        gedruecktUm = uhr.getAsLong();
        druckX = x;
        druckY = y;
    }

    @Override
    public synchronized void loslassen() {
        if (!gedrueckt) return;
        gedrueckt = false;
        if (unten) {
            hoch();
            return;
        }
        long halten = uhr.getAsLong() - gedruecktUm;
        if (halten < LANG_AB_MS) {
            tippen(druckX, druckY);
        } else if (runter(druckX, druckY)) {   // langer Klick: wirklich so lange halten
            halten(halten);
            if (unten) hoch();
        }
    }

    @Override
    public synchronized Point zeiger() {
        return new Point(x, y);
    }

    /** Ausschnitt aus dem PrintWindow-Bild; bereich in Bot-Koordinaten (= Fensterpunkte). */
    @Override
    public BufferedImage foto(Rectangle bereich) {
        int b = Math.max(1, bereich.width), h = Math.max(1, bereich.height);
        BufferedImage aus = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        synchronized (fotoSperre) {
            Dimension g = fenster.groesse();
            if (g == null) {
                warnen("no picture of the game window (closed or minimised?)", "kein Bild vom Spielfenster (zu oder minimiert?)");
                return aus;
            }
            long jetzt = uhr.getAsLong();
            if (bild == null || bildVeraltet || bildB != g.width || bildH != g.height || jetzt - bildUm > FOTO_GUELTIG_MS) {
                int[] neu = fenster.bild(g.width, g.height);
                if (neu == null) {
                    warnen("PrintWindow delivered no picture", "PrintWindow lieferte kein Bild");
                    return aus;
                }
                bild = neu;
                bildB = g.width;
                bildH = g.height;
                bildUm = jetzt;
                bildVeraltet = false;
            }
            ausschneiden(bild, bildB, bildH, bereich.x, bereich.y,
                    ((DataBufferInt) aus.getRaster().getDataBuffer()).getData(), b, h);
        }
        return aus;
    }

    /** Kein PC-Zeiger des Bots: keine Mauswache. */
    @Override
    public boolean istPcMaus() {
        return false;
    }

    /** Bot-Ende/Absturz: Finger heben, Fenster in Ruhe. */
    public synchronized void aufraeumen() {
        try {
            gedrueckt = false;
            if (unten) hoch();
            else fenster.ruhe();
        } catch (Throwable ignored) {
            // beim Beenden nichts mehr aufhalten
        }
    }

    // ---- Ablauf ----------------------------------------------------------------------------------------------

    /** Tipp: runter und hoch in EINEM Moment, danach einmal den Zeiger zurueck. */
    private void tippen(int fx, int fy) {
        Point m = vorbereiten(fx, fy);
        if (m == null) return;
        long vorher = fenster.echteBewegungen();
        if (fenster.beruehren(m, Geist.RUNTER)) {
            if (!fenster.beruehren(m, Geist.HOCH)) fenster.beruehren(m, Geist.HOCH);   // nie liegen lassen
        } else {
            warnen("Windows refused the touch -- tap skipped", "Windows hat den Touch abgelehnt -- Tipp ausgelassen");
        }
        folgen(basis, vorher);
        fenster.ruhe();
        bildVeraltet = true;
    }

    /** Langer Druck oder Zugbeginn: Finger auflegen. false = ging nicht (dann liegt nichts auf). */
    private boolean runter(int fx, int fy) {
        Point m = vorbereiten(fx, fy);
        if (m == null) return false;
        long vorher = fenster.echteBewegungen();
        if (!fenster.beruehren(m, Geist.RUNTER)) {
            warnen("Windows refused the touch -- tap skipped", "Windows hat den Touch abgelehnt -- Tipp ausgelassen");
            folgen(basis, vorher);
            fenster.ruhe();
            return false;
        }
        unten = true;
        letzt = m;
        untenSeit = uhr.getAsLong();
        letzteBeruehrungUm = untenSeit;
        bildVeraltet = true;
        folgen(basis, vorher);
        pflegerWecken();
        return true;
    }

    /** Ein Zugschritt an Autors aktueller Zeigerstelle +/- 1, Fenster neu geschoben. Fehler: sofort hoch. */
    private void schritt(int fx, int fy) {
        Point c = fenster.zeiger();
        long vorher = fenster.echteBewegungen();
        Point m = tippstelle(c);
        fenster.ausrichten(fx, fy, m);
        fenster.takt();
        if (!fenster.frei(m) || fenster.mausBelegt() || !fenster.beruehren(m, Geist.ZIEHEN)) {
            warnen("a drag was broken off (other window or mouse busy)", "ein Zug wurde abgebrochen (anderes Fenster oder Maus belegt)");
            folgen(c, vorher);
            abbrechen();
            return;
        }
        letzt = m;
        letzteBeruehrungUm = uhr.getAsLong();
        bildVeraltet = true;
        folgen(c, vorher);
    }

    /** Finger heben -- immer genau am Punkt des letzten Runter/Schritts. */
    private void hoch() {
        Point c = fenster.zeiger();
        long vorher = fenster.echteBewegungen();
        if (!fenster.beruehren(letzt, Geist.HOCH)) fenster.beruehren(letzt, Geist.HOCH);
        unten = false;
        bildVeraltet = true;
        folgen(c, vorher);
        fenster.ruhe();
    }

    /** Fehler waehrend eines Drucks: sofort hoch, der Rest des Zugs faellt aus. */
    private void abbrechen() {
        gedrueckt = false;
        if (unten) hoch();
        else fenster.ruhe();
    }

    /**
     * Schritte 1-4 (siehe oben): bis alles fuer einen Touch an Fensterpunkt (fx,fy) bereit ist. Liefert die
     * Bildschirmstelle m (und merkt Autors Zeiger in basis) -- oder null: nicht tippen.
     */
    private Point vorbereiten(int fx, int fy) {
        if (!fenster.da()) {
            warnen("no game window (or you opened it) -- tap skipped", "kein Spielfenster (oder du hast es geoeffnet) -- Tipp ausgelassen");
            return null;
        }
        for (int versuch = 0; versuch < VERSUCHE; versuch++) {
            if (!ruheAbwarten()) {
                fenster.ruhe();
                return null;                          // Bot wird gestoppt: lieber gar nicht tippen
            }
            Point c = fenster.zeiger();
            Point m = tippstelle(c);
            fenster.ausrichten(fx, fy, m);
            fenster.takt();
            if (weit(fenster.zeiger(), c)) continue; // Autor hat die Maus bewegt: neu ausrichten
            // Direkt vor dem Touch: liegt dort das Spiel, und ist die Maus frei? Sonst NIE tippen.
            if (!fenster.frei(m) || fenster.mausBelegt()) {
                fenster.ruhe();
                if (!fenster.warten(VERDECKT_PAUSE_MS)) return null;
                continue;
            }
            basis = c;
            return m;
        }
        fenster.ruhe();
        warnen("tap skipped -- the game could not be put safely under the mouse pointer",
                "Tipp ausgelassen -- das Spiel liess sich nicht sicher unter den Mauszeiger legen");
        return null;
    }

    /**
     * Warten, bis keine Maustaste gedrueckt und keine Mausaufnahme aktiv ist (OHNE Zeitgrenze -- sonst landeten Klicks
     * im Vordergrundprogramm) und die echte Maus RUHE_MS ruht (hoechstens RUHE_MAX_MS). false = Bot wird gestoppt.
     */
    private boolean ruheAbwarten() {
        long start = uhr.getAsLong();
        while (true) {
            if (fenster.mausBelegt()) {
                if (!fenster.warten(BELEGT_TAKT_MS)) return false;
                continue;
            }
            if (fenster.ruhtSeit() >= RUHE_MS || uhr.getAsLong() - start >= RUHE_MAX_MS) return true;
            if (!fenster.warten(2)) return false;
        }
    }

    /** So lange halten, wie der Bot gehalten hat (in Haeppchen, damit der Finger aufgefrischt wird). */
    private void halten(long ms) {
        long bis = uhr.getAsLong() + ms;
        while (unten) {
            long rest = bis - uhr.getAsLong();
            if (rest <= 0) return;
            if (!fenster.warten(Math.min(rest, AUFFRISCHEN_MS))) return;
            if (uhr.getAsLong() - letzteBeruehrungUm >= AUFFRISCHEN_MS) auffrischen();
        }
    }

    /** Ein gehaltener Finger bekommt ein Lebenszeichen an derselben Stelle (Fenster liegt unveraendert). */
    private void auffrischen() {
        if (!unten || fenster.mausBelegt()) return;   // Maus belegt: nichts ins Vordergrundprogramm schicken
        Point c = fenster.zeiger();
        long vorher = fenster.echteBewegungen();
        fenster.beruehren(letzt, Geist.ZIEHEN);
        letzteBeruehrungUm = uhr.getAsLong();
        folgen(c, vorher);
    }

    /** Zeiger einmal zurueck: hat Autor die Maus bewegt, dorthin, sonst auf c. */
    private void folgen(Point c, long vorher) {
        if (fenster.echteBewegungen() != vorher) fenster.zeigerSetzen(fenster.echteStelle());
        else if (c != null) fenster.zeigerSetzen(c);
    }

    /** c +/- 1 (Seite wechselt jedes Mal); am Bildschirmrand die Seite, die noch auf dem Schirm liegt. */
    private Point tippstelle(Point c) {
        seite = -seite;
        Point p = new Point(c.x + seite, c.y);
        if (!fenster.aufSchirm(p)) {
            seite = -seite;
            p = new Point(c.x + seite, c.y);
        }
        return p;
    }

    private static boolean weit(Point a, Point b) {
        return Math.abs(a.x - b.x) > ZEIGER_TOLERANZ || Math.abs(a.y - b.y) > ZEIGER_TOLERANZ;
    }

    /** Pflege eines liegenden Fingers (eigener Faden): auffrischen; liegt er zu lange, heben. Paketoeffentlich (Test). */
    synchronized void pflegen() {
        if (!unten) return;
        long jetzt = uhr.getAsLong();
        if (jetzt - untenSeit >= NOTLOS_MS) {
            warnen("a touch lay on for 8 s -- lifted", "ein Touch lag 8 s auf -- gehoben");
            abbrechen();
            return;
        }
        if (jetzt - letzteBeruehrungUm >= AUFFRISCHEN_MS) auffrischen();
    }

    private void pflegerWecken() {
        if (!mitPfleger) return;
        if (pfleger == null) {
            pfleger = new Thread(() -> {
                while (true) {
                    try {
                        synchronized (this) {
                            while (!unten) wait();
                        }
                        Thread.sleep(50);
                        pflegen();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        // die Pflege darf nie den Bot stoeren
                    }
                }
            }, "hintergrund-pfleger");
            pfleger.setDaemon(true);
            pfleger.start();
        }
        notifyAll();
    }

    private void warnen(String en, String de) {
        long jetzt = uhr.getAsLong();
        if (jetzt - gewarntUm < 30_000) return;
        gewarntUm = jetzt;
        Log.warn("Hintergrund", Sprache.t("Background mode: " + en + ".", "Hintergrund-Modus: " + de + "."));
    }

    /**
     * Schneidet aus dem ganzen Fensterbild (quelle, qb x qh) den Bereich ab (sx,sy) in ziel (zb x zh). Was ausserhalb
     * des Fensters liegt, bleibt schwarz.
     */
    static void ausschneiden(int[] quelle, int qb, int qh, int sx, int sy, int[] ziel, int zb, int zh) {
        int x0 = Math.max(0, -sx);
        int x1 = Math.min(zb, qb - sx);
        if (x1 <= x0) return;
        for (int zy = 0; zy < zh; zy++) {
            int qy = sy + zy;
            if (qy < 0 || qy >= qh) continue;
            System.arraycopy(quelle, qy * qb + sx + x0, ziel, zy * zb + x0, x1 - x0);
        }
    }
}
