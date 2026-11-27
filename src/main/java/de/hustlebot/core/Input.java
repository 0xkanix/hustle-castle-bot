package de.hustlebot.core;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.Random;

/**
 * Bedient Maus und Tastatur. Alle Koordinaten werden relativ zum Inhaltsbereich
 * des Spielfensters angegeben und hier in Bildschirmkoordinaten umgerechnet.
 * Klicks bekommen eine leichte zufaellige Streuung, damit die Bewegung nicht
 * exakt gleichfoermig ausfaellt.
 */
public final class Input {

    private final Steuerung st;
    private final Random random = new Random();
    private final Humanizer human;
    private Laufstatistik laufstatistik;
    private Rectangle clientArea;

    public Input(Robot robot) {
        this(robot, new Humanizer());
    }

    public Input(Robot robot, Humanizer human) {
        this(new RobotSteuerung(robot), human);
    }

    /** Handy-Umbau 19.11.: beliebige Steuerung (am PC RobotSteuerung wie bisher, am Handy AdbSteuerung). */
    public Input(Steuerung st, Humanizer human) {
        this.st = st;
        this.human = human;
    }

    public Steuerung steuerung() {
        return st;
    }

    public Humanizer humanizer() {
        return human;
    }

    /** Wenn gesetzt, werden Klicks und Zuege fuer die Vorschau festgehalten. */
    public void setLaufstatistik(Laufstatistik laufstatistik) {
        this.laufstatistik = laufstatistik;
    }

    public void setClientArea(Rectangle area) {
        this.clientArea = area;
    }

    public Rectangle clientArea() {
        return clientArea;
    }

    /** Klickt auf einen Punkt innerhalb des Spielfensters. */
    public void click(int relX, int relY) {
        click(relX, relY, 3);
    }

    /**
     * Klick-Kontrolle (Autor 17.10., Karla: "I can see the bot clicking but nothing happens"): wie lange ein Klick
     * gedrueckt bleibt (0 = wie bisher 18-31 ms) und wie lange die Maus vorher still steht. Die Kathedrale stellt das
     * um, wenn ein Tipp nichts bewirkt und eine andere Art hilft -- dann gilt sie fuer alle Klicks dieser Sitzung.
     */
    private static volatile int druckMs = 0, ruheVorherMs = 0;

    public static void klickArtSetzen(int druck, int ruheVorher) {
        druckMs = Math.max(0, druck);
        ruheVorherMs = Math.max(0, ruheVorher);
    }

    public static int druckMs()      { return druckMs; }
    public static int ruheVorherMs() { return ruheVorherMs; }

    /** Klickt mit einstellbarer Streuung in Bildpunkten. */
    public void click(int relX, int relY, int jitter) {
        if (clientArea == null) {
            Log.error("Eingabe", Sprache.t("No window area set, click discarded.",
                    "Kein Fensterbereich gesetzt, Klick verworfen."));
            return;
        }
        int offsetX = human.offsetWithin(jitter * 2);
        int offsetY = human.offsetWithin(jitter * 2);

        int localX = clamp(relX + offsetX, 0, clientArea.width - 1);
        int localY = clamp(relY + offsetY, 0, clientArea.height - 1);
        int screenX = clientArea.x + localX;
        int screenY = clientArea.y + localY;

        if (laufstatistik != null) laufstatistik.recordClick(localX, localY);

        moveSmoothly(screenX, screenY);
        pause(human.reactionDelay() + 6 + random.nextInt(10) + ruheVorherMs);
        st.druecken();
        pause(druckMs > 0 ? druckMs : 18 + random.nextInt(14));   // lang genug, dass das Spiel den Klick sieht
        st.loslassen();
    }

    /**
     * Langer Klick (Autor 18.11.): ohne Streuung, Druecken, haltMs halten, Loslassen. Das Spiel nimmt
     * kurze Klicks dort oft nicht an; gemessen klappt 120 ms immer.
     */
    public void klickLang(int relX, int relY, int haltMs) {
        if (clientArea == null) return;
        int lx = clamp(relX, 0, clientArea.width - 1);
        int ly = clamp(relY, 0, clientArea.height - 1);
        if (laufstatistik != null) laufstatistik.recordClick(lx, ly);
        moveSmoothly(clientArea.x + lx, clientArea.y + ly);
        pause(human.reactionDelay() + 6 + random.nextInt(10) + ruheVorherMs);
        st.druecken();
        pause(Math.max(20, haltMs));
        st.loslassen();
    }

    // ---- Weiche Bewegungen (getestet 18.11.). Bewegt ein Mensch die Maus (Mauswache), wird sofort aufgehoert und
    //      losgelassen.

    /** Gleitzeit beim Ziehen: 220 ms + 0,45 ms je Bildpunkt Weg, hoechstens 520 ms. */
    public static int gleitMs(int fromX, int fromY, int toX, int toY) {
        return (int) Math.min(520, 220 + Math.hypot(toX - fromX, toY - fromY) * 0.45);
    }

    /**
     * Tippen: in ~180 ms weich hinfahren (ease-in-out), 60 ms ruhen, druckMs druecken (110 ms klappt immer), loslassen.
     * false = abgebrochen, weil ein Mensch die Maus bewegt hat (dann wurde NICHT gedrueckt).
     */
    public boolean tippWeich(int relX, int relY, int druckMs) {
        if (clientArea == null) return false;
        int lx = clamp(relX, 0, clientArea.width - 1);
        int ly = clamp(relY, 0, clientArea.height - 1);
        if (!gleiteWeich(clientArea.x + lx, clientArea.y + ly, 180)) return abgebrochen("Tippen");
        if (laufstatistik != null) laufstatistik.recordClick(lx, ly);
        pause(60);
        st.druecken();
        try {
            pause(Math.max(20, druckMs));
        } finally {
            st.loslassen();
        }
        return true;
    }

    /**
     * Ziehen (Zusammenlegen): weich zur Quelle (200 ms), 50 ms ruhen, druecken, haltStartMs halten, in gleitMs weich zum
     * Ziel gleiten, haltEndeMs ueber dem Ziel halten, loslassen. false = abgebrochen (Maus bewegt; die Taste ist los).
     */
    public boolean ziehenWeich(int fromX, int fromY, int toX, int toY, int haltStartMs, int gleitMs, int haltEndeMs) {
        if (clientArea == null) return false;
        int x1 = clientArea.x + clamp(fromX, 0, clientArea.width - 1), y1 = clientArea.y + clamp(fromY, 0, clientArea.height - 1);
        int x2 = clientArea.x + clamp(toX, 0, clientArea.width - 1), y2 = clientArea.y + clamp(toY, 0, clientArea.height - 1);
        if (!gleiteWeich(x1, y1, 200)) return abgebrochen("Ziehen");
        pause(50);
        st.druecken();
        try {
            pause(Math.max(20, haltStartMs));
            if (!gleiteWeich(x2, y2, Math.max(80, gleitMs))) return abgebrochen("Ziehen");
            pause(Math.max(20, haltEndeMs));
        } finally {
            st.loslassen();   // nie gedrueckt haengen lassen
        }
        return true;
    }

    /** Ruhe nach einem Sofort-Sprung, bevor gedrueckt wird (etwa ein Bild des Spiels bei 60 Hz). */
    static final int SPRUNG_RUHE_MS = 18;

    /**
     * Sofort-Sprung (Autor 21.11.: "keine Insta-Clicks?" -- Knoepfe sofort statt Gleiten): die Maus springt
     * hin, SPRUNG_RUHE_MS Ruhe, dann wird geprueft, ob ein Mensch die Maus bewegt hat (die Mauswache kennt die neue
     * Bot-Stelle schon -- ein Sprung zaehlt nie als Mensch). false = abgebrochen.
     */
    private boolean springe(int x, int y) {
        bewege(x, y);
        pause(SPRUNG_RUHE_MS);
        return !zeigerWeg();
    }

    /**
     * Knopf sofort tippen: hinspringen, kurz ruhen, druckMs druecken (110 ms klappt immer), loslassen.
     * false = abgebrochen, weil ein Mensch die Maus bewegt hat (dann wurde NICHT gedrueckt).
     */
    public boolean tippSofort(int relX, int relY, int druckMs) {
        if (clientArea == null) return false;
        int lx = clamp(relX, 0, clientArea.width - 1);
        int ly = clamp(relY, 0, clientArea.height - 1);
        if (!springe(clientArea.x + lx, clientArea.y + ly)) return abgebrochen("Tippen");
        if (laufstatistik != null) laufstatistik.recordClick(lx, ly);
        st.druecken();
        try {
            pause(Math.max(20, druckMs));
        } finally {
            st.loslassen();
        }
        return true;
    }

    /**
     * Roboter-Tempo (KLICK-PROTOKOLL "Roboter-Tempo", gemessen 18.11. 20:54): in hinMs hinfahren (steht die Maus schon
     * dort: gar nicht; hinMs <= 0: Sofort-Sprung), druckMs druecken, loslassen. Die Pause danach macht der Aufrufer.
     * false = Maus bewegt (abgebrochen).
     */
    public boolean tippSchnell(int relX, int relY, int hinMs, int druckMs) {
        if (clientArea == null) return false;
        int x = clientArea.x + clamp(relX, 0, clientArea.width - 1), y = clientArea.y + clamp(relY, 0, clientArea.height - 1);
        if (!schonDa(x, y) && !(hinMs <= 0 ? springe(x, y) : gleiteWeich(x, y, Math.max(10, hinMs)))) return abgebrochen("Tippen");
        if (laufstatistik != null) laufstatistik.recordClick(x - clientArea.x, y - clientArea.y);
        st.druecken();
        try {
            pause(Math.max(20, druckMs));
        } finally {
            st.loslassen();
        }
        return true;
    }

    /**
     * Roboter-Tempo beim Ziehen: hinMs hinfahren, druecken, haltStartMs halten, gleitMs gleiten, haltEndeMs halten,
     * loslassen (gemessen 18.11.: 30 / 30 / 70 / 30 ms, Pause danach 300 ms macht der Aufrufer). hinMs <= 0: zur Quelle
     * springen (Sofort-Sprung), das Ziehen selbst gleitet immer (sonst nimmt das Spiel den Zug nicht an). false = abgebrochen.
     */
    public boolean ziehenSchnell(int fromX, int fromY, int toX, int toY, int hinMs, int haltStartMs, int gleitMs, int haltEndeMs) {
        if (clientArea == null) return false;
        int x1 = clientArea.x + clamp(fromX, 0, clientArea.width - 1), y1 = clientArea.y + clamp(fromY, 0, clientArea.height - 1);
        int x2 = clientArea.x + clamp(toX, 0, clientArea.width - 1), y2 = clientArea.y + clamp(toY, 0, clientArea.height - 1);
        if (!schonDa(x1, y1) && !(hinMs <= 0 ? springe(x1, y1) : gleiteWeich(x1, y1, Math.max(10, hinMs)))) return abgebrochen("Ziehen");
        st.druecken();
        try {
            pause(Math.max(10, haltStartMs));
            if (!gleiteWeich(x2, y2, Math.max(20, gleitMs))) return abgebrochen("Ziehen");
            pause(Math.max(10, haltEndeMs));
        } finally {
            st.loslassen();   // nie gedrueckt haengen lassen
        }
        return true;
    }

    /** Steht der Zeiger schon (fast) genau dort? Dann muss nicht hingefahren werden. */
    private boolean schonDa(int x, int y) {
        Point p = st.zeiger();
        return Math.abs(p.x - x) <= 1 && Math.abs(p.y - y) <= 1;
    }

    /** Weich gleiten (ease-in-out, alle ~10 ms ein Schritt). false = ein Mensch hat die Maus unterwegs bewegt. */
    private boolean gleiteWeich(int zielX, int zielY, int ms) {
        Point start = st.zeiger();
        int schritte = Math.max(8, ms / 10);
        for (int k = 1; k <= schritte; k++) {
            // erst nach der ersten eigenen Bewegung pruefen (vorher kennt die Mauswache die Bot-Stelle evtl. nicht)
            if (k > 1 && zeigerWeg()) return false;
            double t = (double) k / schritte;
            double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            bewege((int) Math.round(start.x + (zielX - start.x) * e), (int) Math.round(start.y + (zielY - start.y) * e));
            pause(Math.max(1, ms / schritte));
        }
        return !zeigerWeg();
    }

    private static boolean abgebrochen(String was) {
        Log.info("Eingabe", Sprache.t(was + " aborted -- the mouse was moved.", was + " abgebrochen -- die Maus wurde bewegt."));
        return false;
    }

    /** Zug mit einstellbarer Schrittzahl und Haltezeiten: nach dem Druecken und vor dem Loslassen. */
    public void ziehenMit(int fromX, int fromY, int toX, int toY, int schritte, int haltStartMs, int haltEndeMs) {
        drag(fromX, fromY, toX, toY, Math.max(2, schritte), 60, Math.max(20, haltStartMs), Math.max(20, haltEndeMs), 120);
    }

    // Aus dem Hauptbot uebernommen (10.10., Freunde-Update: Titan-Grabung und !stigmas loeschen) --
    // dort ohne die Klickspur fuers Bedienfeld, die es in der Freunde-Fassung nicht gibt.

    /**
     * Dauerklick wie ein Autoclicker (Titan-Grabung, Autor 09.10.: "so schnell wie moeglich, ohne
     * Wartezeiten"). Die Maus springt EINMAL hin und bleibt stehen, danach nur noch Druecken und
     * Loslassen. Haelt an, sobald der Bot gestoppt wird (Faden unterbrochen).
     *
     * @return wie viele Tipps es wirklich waren
     */
    public int dauerklick(int relX, int relY, int anzahl, int haltenMs, int pauseMs) {
        return dauerklick(relX, relY, anzahl, haltenMs, pauseMs, null);
    }

    /**
     * Wie oben, mit Not-Bremse (Titan-Idee 6, 09.10.): alle 40 Tipps wird {@code weiter} gefragt -- false heisst
     * sofort aufhoeren (fremdes Fenster darueber). Die Maustaste wird in jedem Fall wieder losgelassen.
     */
    public int dauerklick(int relX, int relY, int anzahl, int haltenMs, int pauseMs,
                          java.util.function.BooleanSupplier weiter) {
        return dauerklick(relX, relY, anzahl, haltenMs, pauseMs, weiter, 40);
    }

    /**
     * Wie oben, nur wird {@code weiter} alle {@code pruefAlle} Tipps gefragt (HC-Graben 17.10.: alle 6 Tipps, damit der
     * Schub aufhoert, sobald das Zielfeld offen ist).
     */
    public int dauerklick(int relX, int relY, int anzahl, int haltenMs, int pauseMs,
                          java.util.function.BooleanSupplier weiter, int pruefAlle) {
        if (clientArea == null || anzahl <= 0) return 0;
        int alle = Math.max(1, pruefAlle);
        int localX = clamp(relX, 0, clientArea.width - 1);
        int localY = clamp(relY, 0, clientArea.height - 1);
        bewege(clientArea.x + localX, clientArea.y + localY);
        int n = 0;
        while (n < anzahl && !Thread.currentThread().isInterrupted()) {
            // Hat ein Mensch die Maus weggezogen, sofort aufhoeren (Mauswache pausiert danach).
            if (zeigerWeg()) {
                Log.info("Eingabe", Sprache.t("Continuous click aborted after " + n + " taps -- the mouse was moved.",
                        "Dauerklick nach " + n + " Tipps abgebrochen -- die Maus wurde bewegt."));
                break;
            }
            // Autor, 09.10.: alle 5 Tipps 0-2 Bildpunkte nach rechts und 0-2 nach oben versetzen --
            // nie nach links oder unten, so bleibt die Stelle im Block.
            if (n % 5 == 0) {
                bewege(clientArea.x + clamp(localX + random.nextInt(3), 0, clientArea.width - 1),
                        clientArea.y + clamp(localY - random.nextInt(3), 0, clientArea.height - 1));
            }
            if (weiter != null && n > 0 && n % alle == 0 && !weiter.getAsBoolean()) {
                if (alle >= 40) Log.info("Eingabe", Sprache.t("Emergency brake in the continuous click after " + n + " taps -- something lies over the game.",
                        "Not-Bremse im Dauerklick nach " + n + " Tipps -- etwas liegt ueber dem Spiel."));
                break;
            }
            try {
                st.druecken();
                if (haltenMs > 0) pause(haltenMs);
            } finally {
                st.loslassen();   // nie gedrueckt haengen lassen (F9, Fehler)
            }
            n++;
            if (pauseMs > 0) pause(pauseMs);
        }
        if (laufstatistik != null) laufstatistik.recordClick(localX, localY);
        return n;
    }

    /** Mittlere Helligkeit (0-255) eines kleinen Flecks im Spielfenster -- direkt vom Bildschirm, ~2 ms. */
    public double helligkeit(int relX, int relY, int breite, int hoehe) {
        if (clientArea == null || breite <= 0 || hoehe <= 0) return -1;
        try {
            java.awt.image.BufferedImage b = st.foto(new Rectangle(clientArea.x + relX, clientArea.y + relY, breite, hoehe));
            long summe = 0;
            int n = 0;
            for (int y = 0; y < b.getHeight(); y += 2) {
                for (int x = 0; x < b.getWidth(); x += 2) {
                    int p = b.getRGB(x, y);
                    summe += ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255);
                    n++;
                }
            }
            return n == 0 ? -1 : summe / (3.0 * n);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Sofort-Klick fuer die Events (Autor, 09.10.: "sofort draufklicken, nicht erst die Maus rueber gleiten"):
     * die Maus springt hin, kein Gleiten, keine Reaktionspause -- nur so lange gedrueckt, dass das Spiel
     * den Klick sieht.
     */
    public void tippeSofort(int relX, int relY) {
        if (clientArea == null) {
            Log.error("Eingabe", Sprache.t("No window area set, click discarded.",
                    "Kein Fensterbereich gesetzt, Klick verworfen."));
            return;
        }
        int localX = clamp(relX, 0, clientArea.width - 1);
        int localY = clamp(relY, 0, clientArea.height - 1);
        if (laufstatistik != null) laufstatistik.recordClick(localX, localY);
        bewege(clientArea.x + localX, clientArea.y + localY);
        pause(8);
        st.druecken();
        pause(20);
        st.loslassen();
    }

    /** Zug ohne Hingleiten (Events, 09.10.): Maus springt zum Start, dann wenige schnelle Zwischenschritte. */
    public void ziehenSofort(int fromX, int fromY, int toX, int toY) {
        if (clientArea == null) return;
        int sx = clientArea.x + clamp(fromX, 0, clientArea.width - 1);
        int sy = clientArea.y + clamp(fromY, 0, clientArea.height - 1);
        int tx = clientArea.x + clamp(toX, 0, clientArea.width - 1);
        int ty = clientArea.y + clamp(toY, 0, clientArea.height - 1);
        if (laufstatistik != null) laufstatistik.recordDrag(sx - clientArea.x, sy - clientArea.y, tx - clientArea.x, ty - clientArea.y);
        bewege(sx, sy);
        pause(15);
        st.druecken();
        pause(40);
        for (int i = 1; i <= 6; i++) {
            double t = i / 6.0;
            bewege((int) Math.round(sx + (tx - sx) * t), (int) Math.round(sy + (ty - sy) * t));
            pause(12);
        }
        pause(40);
        st.loslassen();
        pause(20);
    }

    /** Klickt auf die Mitte eines gefundenen Treffers. */
    public void click(Matcher.Hit hit) {
        int jitter = Math.max(2, Math.min(hit.width(), hit.height()) / 5);
        click(hit.centerX(), hit.centerY(), jitter);
    }

    /** Zieht ein Element von einem Punkt zum anderen, etwa eine Einheit in ein Feld. */
    public void drag(int fromX, int fromY, int toX, int toY) {
        drag(fromX, fromY, toX, toY, 22, 60, 110, 120, 90);
    }

    /**
     * Langsamer Zug (Autor 11.10.: "Du wischst deutlich zu schnell, wenn du die Namen und Skins perfekt abscannen willst"):
     * viele Zwischenschritte und am Ende 0,6 s festhalten -- dann rollt die Reihe nicht nach.
     */
    public void dragLangsam(int fromX, int fromY, int toX, int toY) {
        drag(fromX, fromY, toX, toY, 60, 80, 150, 600, 200);
    }

    /**
     * Schneller Zug fuer die Formen im Hammer-Event (28.09.): halb so viele
     * Zwischenschritte, kuerzere Pausen. Die Form springt ohnehin aufs Raster;
     * ob das Spiel den Zug annimmt, prueft der Bot im naechsten Bild.
     */
    public void dragSchnell(int fromX, int fromY, int toX, int toY) {
        drag(fromX, fromY, toX, toY, 10, 30, 60, 60, 40);
    }

    /**
     * Karte aus einer Reihe nach oben in einen Platz ziehen (Autor 01.11.: "von unten nach oben"): erst senkrecht aus der
     * Reihe heraus, sonst rollt die Reihe statt der Karte, dann waagerecht zum Platz und lange festhalten. Auf dem langsamen
     * Server haengt die Karte der Maus hinterher; beim schnellen Zug landete sie auf dem Nachbarplatz und warf dort eine
     * Einheit aus dem Trupp (Fotos 01.11. 08:24).
     */
    public void dragHoch(int fromX, int fromY, int toX, int toY) {
        if (clientArea == null) return;

        int lx1 = clamp(fromX, 0, clientArea.width - 1);
        int ly1 = clamp(fromY, 0, clientArea.height - 1);
        int lx2 = clamp(toX, 0, clientArea.width - 1);
        int ly2 = clamp(toY, 0, clientArea.height - 1);

        if (laufstatistik != null) laufstatistik.recordDrag(lx1, ly1, lx2, ly2);

        int sx = clientArea.x + lx1;
        int sy = clientArea.y + ly1;
        int tx = clientArea.x + lx2;
        int ty = clientArea.y + ly2;

        moveSmoothly(sx, sy);
        pause(120);
        st.druecken();
        pause(250);
        strecke(sx, sy, sx, ty, 40);
        strecke(sx, ty, tx, ty, 40);
        pause(1000);
        st.loslassen();
        pause(300);
    }

    private void strecke(int x1, int y1, int x2, int y2, int steps) {
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            bewege((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t));
            pause(11 + random.nextInt(8));
        }
    }

    private void drag(int fromX, int fromY, int toX, int toY,
                      int steps, int vorDemDruecken, int nachDemDruecken, int vorDemLoslassen, int danach) {
        if (clientArea == null) return;

        int lx1 = clamp(fromX, 0, clientArea.width - 1);
        int ly1 = clamp(fromY, 0, clientArea.height - 1);
        int lx2 = clamp(toX, 0, clientArea.width - 1);
        int ly2 = clamp(toY, 0, clientArea.height - 1);

        if (laufstatistik != null) laufstatistik.recordDrag(lx1, ly1, lx2, ly2);

        int sx = clientArea.x + lx1;
        int sy = clientArea.y + ly1;
        int tx = clientArea.x + lx2;
        int ty = clientArea.y + ly2;

        moveSmoothly(sx, sy);
        pause(vorDemDruecken);
        st.druecken();
        pause(nachDemDruecken);

        // In mehreren Schritten ziehen, sonst erkennt das Spiel die Bewegung nicht.
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double eased = t * t * (3 - 2 * t);
            int x = (int) Math.round(sx + (tx - sx) * eased);
            int y = (int) Math.round(sy + (ty - sy) * eased);
            bewege(x, y);
            pause(11 + random.nextInt(8));
        }

        pause(vorDemLoslassen);
        st.loslassen();
        pause(danach);
    }

    /**
     * Bewegt die Maus in kleinen Schritten statt sprunghaft. Bei laengeren
     * Wegen schiesst sie gelegentlich leicht ueber das Ziel hinaus und
     * korrigiert nach, so wie es eine Hand tut.
     */
    private void moveSmoothly(int targetX, int targetY) {
        Point start = st.zeiger();
        int distance = (int) start.distance(targetX, targetY);

        if (distance > 180 && human.shouldOvershoot()) {
            double excess = 0.02 + random.nextDouble() * 0.04;
            int overX = (int) Math.round(targetX + (targetX - start.x) * excess);
            int overY = (int) Math.round(targetY + (targetY - start.y) * excess);
            glide(start, overX, overY, distance);
            pause(30 + random.nextInt(45));
            glide(st.zeiger(), targetX, targetY, 40);
            return;
        }
        glide(start, targetX, targetY, distance);
    }

    /** Eine einzelne weiche Bewegung von einem Punkt zum anderen. */
    private void glide(Point start, int targetX, int targetY, int distance) {
        int steps = Math.max(2, Math.min(12, distance / 45));

        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double eased = t * t * (3 - 2 * t);
            int x = (int) Math.round(start.x + (targetX - start.x) * eased);
            int y = (int) Math.round(start.y + (targetY - start.y) * eased);

            // Leichte Unruhe auf dem Weg, wie ein ruhig gefuehrter Zeiger.
            if (i < steps && distance > 80) {
                x += human.driftPixels();
                y += human.driftPixels();
            }
            bewege(x, y);
            pause(human.stepPause());
        }
        bewege(targetX, targetY);
    }

    /** Parkt die Maus an einer harmlosen Stelle, damit keine Hinweisfenster aufgehen. */
    public void parkMouse() {
        if (clientArea == null) return;
        moveSmoothly(clientArea.x + clientArea.width / 2,
                     clientArea.y + clientArea.height - 12);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    /** Jede Mausbewegung des Bots meldet sich bei der Mauswache (Autor 14.10.: Ablauf pausiert, wenn ein Mensch die Maus bewegt). */
    private void bewege(int x, int y) {
        if (!st.istPcMaus()) {   // Handy: die PC-Maus bleibt, wo sie ist -- keine Mauswache
            st.bewegen(x, y);
            return;
        }
        Mauswache.vorBotBewegung();
        Mauswache.botBewegt(x, y);
        st.bewegen(x, y);
        Mauswache.botBewegt(x, y);
        Mauswache.botGemessen();   // Dirk 17.10.: Skalierung != 100 % -- gemessene Stelle mitmerken
    }

    /** Hat ein Mensch die PC-Maus bewegt? Am Handy gibt es keine Bot-Maus am PC -- dort nie. */
    private boolean zeigerWeg() {
        return st.istPcMaus() && Mauswache.zeigerWeg();
    }

}
