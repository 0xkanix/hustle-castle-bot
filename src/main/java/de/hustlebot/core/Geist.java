package de.hustlebot.core;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.function.Supplier;

/**
 * Das Spielfenster als "Geist" (Hintergrund-Modus, Autor 20.11.): Autor spielt am selben Rechner Minecraft oder schaut
 * YouTube, waehrend der Bot Hustle Castle spielt -- ohne Fokus-Klau und ohne dass das Spiel zu sehen ist.
 *
 * LIVE GEMESSEN (20.11., HC.exe, Unity 6000.3, altes Input-System):
 *   - Foto: PrintWindow(PW_RENDERFULLCONTENT = 2) liefert das echte Bild, auch verdeckt, ausserhalb des Bildschirms oder
 *     fast durchsichtig (16,9 ms). Das Bild beginnt an der FENSTERecke (mit Titelleiste/Rand). Minimiert: kein Bild.
 *   - Maus-Nachrichten (PostMessage/SendMessage) ignoriert das Spiel. Es reagiert nur auf die Maus-Nachrichten, die
 *     Windows aus dem HAUPT-Touch macht (InjectTouchInput). Der "Anker-Trick" (zweiter Touch) wirkt deshalb NICHT.
 *   - Verlaesslich (24/24): "Tipp unter dem Zeiger mit 1 Pixel Versatz" -- Zeiger m0 lesen, Tippstelle m = m0 +/- 1,
 *     das Spielfenster so schieben, dass der Zielpunkt genau auf m liegt, klickbar machen, 15 ms warten, Touch an m,
 *     Zeiger auf m0 zurueck, Fenster wieder weg. Der Versatz erzwingt ein WM_MOUSEMOVE (ohne wertet Unity die alte
 *     Mausstelle aus).
 *
 * In Ruhe ist das Fenster ein Geist: WS_EX_LAYERED mit Deckkraft 1/255 (nicht 0 -- sonst nimmt es keinen Touch an),
 * WS_EX_TRANSPARENT (Klicks gehen durch), WS_EX_NOACTIVATE (wird nie aktiv), "immer oben", AUSSERHALB des Bildschirms.
 * Nie minimieren (dann gibt es kein Bild). Ausgeschaltet: Stil normal, nicht mehr "immer oben", bei 0,0.
 *
 * Die Ablauf-Logik (wann gedrueckt, gewartet, zurueckgesetzt wird) steht in {@link HintergrundSteuerung}; hier nur die
 * Windows-Aufrufe und die reinen Rechnungen dazu.
 *
 * EXPERIMENTELL: nicht neben Spielen mit vielen Klicks benutzen (siehe HintergrundSteuerung).
 */
public final class Geist {

    private Geist() {
    }

    // ---- Fensterstil (reine Rechnungen, pruefbar) --------------------------------------------------------------

    static final long WS_EX_TOPMOST = 0x8L;
    static final long WS_EX_TRANSPARENT = 0x20L;
    static final long WS_EX_LAYERED = 0x80000L;
    static final long WS_EX_NOACTIVATE = 0x08000000L;

    /** Der Stil des Geists: unsichtbar und nie aktiv; durchlaessig = Klicks gehen hindurch. */
    static long geistStil(long stil, boolean durchlaessig) {
        long s = stil | WS_EX_LAYERED | WS_EX_NOACTIVATE;
        return durchlaessig ? s | WS_EX_TRANSPARENT : s & ~WS_EX_TRANSPARENT;
    }

    /** Wieder ein normales Fenster. */
    static long normalStil(long stil) {
        return stil & ~(WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
    }

    /** Traegt das Fenster unsere Geist-Kennung (LAYERED + NOACTIVATE)? TRANSPARENT fehlt waehrend eines Tipps. */
    static boolean istGeistStil(long stil) {
        long beide = WS_EX_LAYERED | WS_EX_NOACTIVATE;
        return (stil & beide) == beide;
    }

    /** So weit links neben allen Bildschirmen liegt das Fenster in Ruhe. */
    static final int RUHE_ABSTAND = 200;

    /** Ruheplatz: links ausserhalb ALLER Bildschirme (virtueller Schirm), damit es nirgends im Weg liegt. */
    static Point ruheplatz(Rectangle virtuell, Dimension fenster) {
        return new Point(virtuell.x - fenster.width - RUHE_ABSTAND, virtuell.y);
    }

    // ---- Touch ----------------------------------------------------------------------------------------------

    static final int PT_TOUCH = 2;
    static final int POINTER_FLAG_INRANGE = 0x2;
    static final int POINTER_FLAG_INCONTACT = 0x4;
    static final int POINTER_FLAG_DOWN = 0x10000;
    static final int POINTER_FLAG_UPDATE = 0x20000;
    static final int POINTER_FLAG_UP = 0x40000;
    /** Finger auflegen. */
    public static final int RUNTER = POINTER_FLAG_DOWN | POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
    /** Aufgelegten Finger bewegen (oder nur auffrischen). */
    public static final int ZIEHEN = POINTER_FLAG_UPDATE | POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT;
    /** Finger heben. */
    public static final int HOCH = POINTER_FLAG_UP;
    /** Autor 20.11.: KEINE Touch-Kreise von Windows (TOUCH_FEEDBACK_NONE statt DEFAULT). */
    static final int TOUCH_FEEDBACK_NONE = 0x3;

    /** POINTER_INFO (Windows), 96 Bytes auf 64 Bit. */
    @Structure.FieldOrder({"pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice", "hwndTarget",
            "ptPixelLocation", "ptHimetricLocation", "ptPixelLocationRaw", "ptHimetricLocationRaw", "dwTime",
            "historyCount", "InputData", "dwKeyStates", "PerformanceCount", "ButtonChangeType"})
    public static class ZeigerInfo extends Structure {
        public int pointerType;
        public int pointerId;
        public int frameId;
        public int pointerFlags;
        public Pointer sourceDevice;
        public Pointer hwndTarget;
        public WinDef.POINT ptPixelLocation = new WinDef.POINT();
        public WinDef.POINT ptHimetricLocation = new WinDef.POINT();
        public WinDef.POINT ptPixelLocationRaw = new WinDef.POINT();
        public WinDef.POINT ptHimetricLocationRaw = new WinDef.POINT();
        public int dwTime;
        public int historyCount;
        public int InputData;
        public int dwKeyStates;
        public long PerformanceCount;
        public int ButtonChangeType;
    }

    /** POINTER_TOUCH_INFO (Windows), 144 Bytes auf 64 Bit. */
    @Structure.FieldOrder({"pointerInfo", "touchFlags", "touchMask", "rcContact", "rcContactRaw", "orientation", "pressure"})
    public static class TouchInfo extends Structure {
        public ZeigerInfo pointerInfo = new ZeigerInfo();
        public int touchFlags;
        public int touchMask;
        public WinDef.RECT rcContact = new WinDef.RECT();
        public WinDef.RECT rcContactRaw = new WinDef.RECT();
        public int orientation;
        public int pressure;

        /** Fuer den Test: Lage eines Felds in Bytes. */
        int versatz(String feld) {
            return fieldOffset(feld);
        }

        /** Ein Finger an (x,y) wie in Autors Probe: Druck 32000, Ausrichtung 90, Kontaktflaeche 4 x 4. */
        void fuellen(int x, int y, int flags) {
            pointerInfo.pointerType = PT_TOUCH;
            pointerInfo.pointerId = 0;
            pointerInfo.pointerFlags = flags;
            pointerInfo.ptPixelLocation.x = x;
            pointerInfo.ptPixelLocation.y = y;
            touchFlags = 0;
            touchMask = 0x7;   // TOUCH_MASK_CONTACTAREA | ORIENTATION | PRESSURE
            orientation = 90;
            pressure = 32000;
            rcContact.left = x - 2;
            rcContact.right = x + 2;
            rcContact.top = y - 2;
            rcContact.bottom = y + 2;
        }
    }

    /** Die Windows-Aufrufe, die JNA nicht schon mitbringt. */
    interface W extends User32 {
        W INSTANCE = Native.load("user32", W.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean IsIconic(WinDef.HWND hWnd);

        boolean ClientToScreen(WinDef.HWND hWnd, WinDef.POINT lpPoint);

        int GetSystemMetrics(int nIndex);

        boolean GetLayeredWindowAttributes(WinDef.HWND hwnd, IntByReference crKey, ByteByReference alpha, IntByReference flags);

        boolean InitializeTouchInjection(int maxCount, int dwMode);

        boolean InjectTouchInput(int count, TouchInfo contacts);

        WinDef.HWND WindowFromPoint(WinDef.POINT.ByValue point);
    }

    private static final int GWL_EXSTYLE = -20;
    private static final int LWA_ALPHA = 0x2;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int SW_RESTORE = 9;
    private static final int GA_ROOT = 2;
    private static final int PW_RENDERFULLCONTENT = 2;
    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;
    private static final WinDef.HWND HWND_TOPMOST = new WinDef.HWND(Pointer.createConstant(-1));
    private static final WinDef.HWND HWND_NOTOPMOST = new WinDef.HWND(Pointer.createConstant(-2));

    /** Liegt das Fenster gerade fuer einen Tipp auf dem Bildschirm? Dann schiebt es niemand weg. */
    private static volatile boolean ausgerichtet = false;

    // ---- Zustand des Fensters ------------------------------------------------------------------------------

    public static boolean lebt(WinDef.HWND h) {
        return h != null && W.INSTANCE.IsWindow(h);
    }

    static long stil(WinDef.HWND h) {
        return W.INSTANCE.GetWindowLongPtr(h, GWL_EXSTYLE).longValue();
    }

    static void stilSetzen(WinDef.HWND h, long stil) {
        W.INSTANCE.SetWindowLongPtr(h, GWL_EXSTYLE, Pointer.createConstant(stil));
    }

    /** Ist das Fenster gerade ein Geist (von uns unsichtbar gemacht)? */
    public static boolean istGeist(WinDef.HWND h) {
        return lebt(h) && istGeistStil(stil(h));
    }

    public static boolean istVorne(WinDef.HWND h) {
        return h != null && h.equals(W.INSTANCE.GetForegroundWindow());
    }

    public static boolean minimiert(WinDef.HWND h) {
        return lebt(h) && W.INSTANCE.IsIconic(h);
    }

    private static Rectangle virtuellerSchirm() {
        W u = W.INSTANCE;
        return new Rectangle(u.GetSystemMetrics(SM_XVIRTUALSCREEN), u.GetSystemMetrics(SM_YVIRTUALSCREEN),
                u.GetSystemMetrics(SM_CXVIRTUALSCREEN), u.GetSystemMetrics(SM_CYVIRTUALSCREEN));
    }

    private static Dimension groesse(WinDef.HWND h) {
        WinDef.RECT r = new WinDef.RECT();
        if (!W.INSTANCE.GetWindowRect(h, r)) return null;
        int b = r.right - r.left, hoehe = r.bottom - r.top;
        return b > 0 && hoehe > 0 ? new Dimension(b, hoehe) : null;
    }

    private static Point ruheplatz(WinDef.HWND h) {
        Dimension g = groesse(h);
        return ruheplatz(virtuellerSchirm(), g != null ? g : new Dimension(1175, 809));
    }

    private static void alphaSicherstellen(WinDef.HWND h, boolean sofort) {
        if (!sofort) {
            IntByReference schluessel = new IntByReference();
            ByteByReference alpha = new ByteByReference();
            IntByReference flags = new IntByReference();
            if (W.INSTANCE.GetLayeredWindowAttributes(h, schluessel, alpha, flags)
                    && (flags.getValue() & LWA_ALPHA) != 0 && alpha.getValue() == 1) {
                return;
            }
        }
        W.INSTANCE.SetLayeredWindowAttributes(h, 0, (byte) 1, LWA_ALPHA);
    }

    /**
     * Ruhezustand herstellen (vom Bot vor jedem Bild, GameWindow.prepare): Geist-Stil, Deckkraft 1, nicht minimiert,
     * "immer oben" und ausserhalb des Bildschirms. Liegt das Fenster gerade fuer einen Tipp unter dem Zeiger, bleibt es
     * dort. Hat Autor das Spiel geoeffnet, wird nichts angefasst.
     */
    public static synchronized void ruheSicherstellen(WinDef.HWND h) {
        if (!lebt(h) || Hintergrund.nutzerHatSpiel()) return;
        if (W.INSTANCE.IsIconic(h)) W.INSTANCE.ShowWindow(h, SW_SHOWNOACTIVATE);   // nie minimiert: sonst kein Bild
        long s = stil(h);
        boolean neu = !istGeistStil(s);
        long soll = geistStil(s, !ausgerichtet);
        if (soll != s) stilSetzen(h, soll);
        alphaSicherstellen(h, neu);
        if (!ausgerichtet) {
            Point r = ruheplatz(h);
            WinDef.RECT w = new WinDef.RECT();
            W.INSTANCE.GetWindowRect(h, w);
            if ((s & WS_EX_TOPMOST) == 0 || w.left != r.x || w.top != r.y) {
                W.INSTANCE.SetWindowPos(h, HWND_TOPMOST, r.x, r.y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
            }
            if (neu) {
                Log.info("Hintergrund", Sprache.t("Background mode: the game is now invisible and lies outside the screen (" + r.x + "," + r.y + ").",
                        "Hintergrund-Modus: das Spiel ist jetzt unsichtbar und liegt ausserhalb des Bildschirms (" + r.x + "," + r.y + ")."));
            }
        }
    }

    /**
     * Fuer einen Tipp: das Fenster so schieben, dass der Fensterpunkt (fx,fy) -- gezaehlt ab der Fensterecke wie im
     * PrintWindow-Bild -- genau auf dem Bildschirmpunkt m liegt, dann klickbar machen (WS_EX_TRANSPARENT weg).
     * Reihenfolge wie Autors Probe G6: erst schieben, dann klickbar.
     */
    public static synchronized void ausrichten(WinDef.HWND h, int fx, int fy, Point m) {
        if (!lebt(h)) return;
        if (W.INSTANCE.IsIconic(h)) W.INSTANCE.ShowWindow(h, SW_SHOWNOACTIVATE);
        long s = stil(h);
        if (!istGeistStil(s)) {   // noch kein Geist: erst unsichtbar machen, sonst blitzt es unter dem Zeiger auf
            s = geistStil(s, true);
            stilSetzen(h, s);
            alphaSicherstellen(h, true);
        }
        ausgerichtet = true;
        W.INSTANCE.SetWindowPos(h, HWND_TOPMOST, m.x - fx, m.y - fy, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        long klickbar = geistStil(s, false);
        if (klickbar != s) stilSetzen(h, klickbar);
    }

    /** Sicherheit vor JEDEM Touch: liegt an m wirklich das Spiel? Sonst landete der Tipp in Autors Fenster. */
    public static boolean frei(WinDef.HWND h, Point m) {
        if (!lebt(h)) return false;
        WinDef.HWND unter = W.INSTANCE.WindowFromPoint(new WinDef.POINT.ByValue(m.x, m.y));
        if (unter == null) return false;
        return h.equals(W.INSTANCE.GetAncestor(unter, GA_ROOT));
    }

    /**
     * Nach dem Tipp: wieder durchlaessig und zurueck an den Ruheplatz. Synchron (nicht SWP_ASYNCWINDOWPOS): ein
     * verspaetetes "zurueck" koennte sonst NACH dem Ausrichten des naechsten Tipps ankommen und das Fenster unter dem
     * Zeiger wegziehen -- dann ginge der Tipp in Autors Fenster.
     */
    public static synchronized void ruhe(WinDef.HWND h) {
        ausgerichtet = false;
        if (!lebt(h)) return;
        long s = stil(h);
        if (!istGeistStil(s)) return;   // inzwischen aufgehoben (Autor hat das Spiel geoeffnet): nicht wieder verstecken
        if ((s & WS_EX_TRANSPARENT) == 0) stilSetzen(h, s | WS_EX_TRANSPARENT);
        if (Hintergrund.nutzerHatSpiel()) return;
        Point r = ruheplatz(h);
        W.INSTANCE.SetWindowPos(h, HWND_TOPMOST, r.x, r.y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
    }

    /** Geist aufheben: Stil normal, nicht mehr "immer oben", bei 0,0 (Autor 20.11.). Wird NICHT aktiviert. */
    public static synchronized void aus(WinDef.HWND h, String grundEn, String grundDe) {
        ausgerichtet = false;
        if (!lebt(h)) return;
        long s = stil(h);
        if ((s & WS_EX_LAYERED) != 0) W.INSTANCE.SetLayeredWindowAttributes(h, 0, (byte) 255, LWA_ALPHA);
        stilSetzen(h, normalStil(s));
        if (W.INSTANCE.IsIconic(h)) W.INSTANCE.ShowWindow(h, SW_SHOWNOACTIVATE);
        W.INSTANCE.SetWindowPos(h, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        Log.info("Hintergrund", Sprache.t("Background mode: the game is visible again at 0,0 (" + grundEn + ").",
                "Hintergrund-Modus: das Spiel ist wieder sichtbar bei 0,0 (" + grundDe + ")."));
    }

    /** Autor hat das Spiel ueber die Taskleiste geoeffnet: sichtbar bei 0,0 und nach vorne. */
    public static synchronized void zeigenFuerAutor(WinDef.HWND h) {
        aus(h, "you opened the game", "du hast das Spiel geoeffnet");
        if (!lebt(h)) return;
        if (W.INSTANCE.IsIconic(h)) W.INSTANCE.ShowWindow(h, SW_RESTORE);
        W.INSTANCE.SetForegroundWindow(h);
    }

    /**
     * Notfall (Bot hart beendet, Fernwache): JEDES Hustle-Castle-Fenster (Unity) mit unserer Geist-Kennung wieder normal
     * machen -- damit das Spiel nie unsichtbar haengen bleibt.
     *
     * @return wie viele Fenster es waren
     */
    public static int allesAufheben() {
        int[] n = {0};
        try {
            W.INSTANCE.EnumWindows((h, daten) -> {
                char[] puffer = new char[256];
                int k = W.INSTANCE.GetClassName(h, puffer, puffer.length);
                if (k <= 0 || !"UnityWndClass".equals(new String(puffer, 0, k))) return true;
                char[] t = new char[256];
                W.INSTANCE.GetWindowText(h, t, t.length);
                if (!Native.toString(t).trim().toLowerCase(java.util.Locale.ROOT).startsWith("hustle castle")) return true;
                if (istGeistStil(stil(h))) {
                    aus(h, "clean-up", "Aufraeumen");
                    n[0]++;
                }
                return true;
            }, null);
        } catch (Throwable t) {
            // nur unter Windows; anderswo gibt es nichts aufzuraeumen
        }
        return n[0];
    }

    // ---- Zeiger, Tasten, Touch -----------------------------------------------------------------------------

    public static Point zeiger() {
        WinDef.POINT p = new WinDef.POINT();
        W.INSTANCE.GetCursorPos(p);
        return new Point(p.x, p.y);
    }

    public static void zeigerSetzen(Point p) {
        W.INSTANCE.SetCursorPos(p.x, p.y);
    }

    /** Haelt Autor gerade eine Maustaste (links, rechts, Mitte)? */
    public static boolean tasteGedrueckt() {
        W u = W.INSTANCE;
        return (u.GetAsyncKeyState(0x01) & 0x8000) != 0 || (u.GetAsyncKeyState(0x02) & 0x8000) != 0
                || (u.GetAsyncKeyState(0x04) & 0x8000) != 0;
    }

    /**
     * Ist die Maus gerade belegt -- eine Maustaste gedrueckt ODER eine Mausaufnahme (SetCapture) im Vordergrund?
     * Live 20.11.: dann gingen die aus dem Touch erzeugten Mausklicks ins VORDERGRUNDPROGRAMM (Minecraft auf einem
     * Server -- sieht nach Cheaten aus, absolut verboten). Dann NIE tippen.
     */
    public static boolean mausBelegt() {
        if (tasteGedrueckt()) return true;
        WinUser.GUITHREADINFO info = new WinUser.GUITHREADINFO();
        info.cbSize = info.size();
        // 0 = der Faden des Vordergrundfensters
        return !W.INSTANCE.GetGUIThreadInfo(0, info) || info.hwndCapture != null;
    }

    /**
     * Spiel-Takt (Live-Test 20.11., Vorlage Geist11): zweimal WM_NULL per SendMessageTimeout -- kommt zurueck, wenn das
     * Spiel seine Nachrichten abgeholt hat (also nach einem Bildwechsel). Passt sich der Last an; feste 15 ms reichten
     * neben Minecraft nicht (33/40). SMTO_ABORTIFHUNG, hoechstens 200 ms je Mal.
     */
    public static void takt(WinDef.HWND h) {
        if (!lebt(h)) return;
        WinDef.DWORDByReference antwort = new WinDef.DWORDByReference();
        for (int i = 0; i < 2; i++) {
            W.INSTANCE.SendMessageTimeout(h, 0x0000, new WinDef.WPARAM(0), new WinDef.LPARAM(0), 0x0002, 200, antwort);
        }
    }

    /** Liegt der Punkt auf irgendeinem Bildschirm? (Touch ausserhalb nimmt Windows nicht an.) */
    public static boolean aufSchirm(Point p) {
        return W.INSTANCE.MonitorFromPoint(new WinDef.POINT.ByValue(p.x, p.y), 0) != null;   // MONITOR_DEFAULTTONULL
    }

    private static final Object TOUCH = new Object();
    private static boolean touchBereit = false;
    private static TouchInfo kontakt;
    private static long touchGewarntUm = 0;

    /** Ein Touch-Schritt (RUNTER, ZIEHEN, HOCH) an Bildschirmpunkt m. */
    public static boolean beruehren(Point m, int flags) {
        synchronized (TOUCH) {
            if (!touchBereit) {
                touchBereit = W.INSTANCE.InitializeTouchInjection(1, TOUCH_FEEDBACK_NONE);
                if (!touchBereit) {
                    warnenTouch("InitializeTouchInjection", Native.getLastError());
                    return false;
                }
            }
            if (kontakt == null) kontakt = new TouchInfo();
            kontakt.fuellen(m.x, m.y, flags);
            boolean ok = W.INSTANCE.InjectTouchInput(1, kontakt);
            if (!ok) warnenTouch("InjectTouchInput", Native.getLastError());
            return ok;
        }
    }

    private static void warnenTouch(String was, int fehler) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - touchGewarntUm < 30_000) return;
        touchGewarntUm = jetzt;
        Log.warn("Hintergrund", Sprache.t("Background mode: Windows refused the touch (" + was + ", error " + fehler + ").",
                "Hintergrund-Modus: Windows hat den Touch abgelehnt (" + was + ", Fehler " + fehler + ")."));
    }

    // ---- Der Zugang fuer die HintergrundSteuerung ------------------------------------------------------------

    /** Das echte Windows hinter {@link HintergrundSteuerung.Fenster}. */
    public static final class Zugang implements HintergrundSteuerung.Fenster {

        private final Supplier<WinDef.HWND> griff;
        private Memory puffer;
        private int[] pixel;

        /** griff liefert das aktuelle Spielfenster (GameWindow::handle -- nach einem Spiel-Neustart ein neues). */
        public Zugang(Supplier<WinDef.HWND> griff) {
            this.griff = griff;
        }

        private WinDef.HWND h() {
            return griff.get();
        }

        @Override
        public boolean da() {
            return lebt(h()) && !Hintergrund.nutzerHatSpiel();
        }

        @Override
        public Dimension groesse() {
            WinDef.HWND h = h();
            if (!lebt(h) || W.INSTANCE.IsIconic(h)) return null;
            return Geist.groesse(h);
        }

        @Override
        public int[] bild(int breite, int hoehe) {
            WinDef.HWND h = h();
            if (!lebt(h) || breite <= 0 || hoehe <= 0) return null;
            int n = breite * hoehe;
            long bytes = (long) n * 4;
            if (puffer == null || puffer.size() < bytes) {
                if (puffer != null) puffer.close();
                puffer = new Memory(bytes);
            }
            if (pixel == null || pixel.length != n) pixel = new int[n];
            WinDef.HDC fensterDc = W.INSTANCE.GetDC(h);
            if (fensterDc == null) return null;
            WinDef.HDC speicherDc = GDI32.INSTANCE.CreateCompatibleDC(fensterDc);
            WinDef.HBITMAP bmp = GDI32.INSTANCE.CreateCompatibleBitmap(fensterDc, breite, hoehe);
            WinNT.HANDLE alt = GDI32.INSTANCE.SelectObject(speicherDc, bmp);
            try {
                if (!W.INSTANCE.PrintWindow(h, speicherDc, PW_RENDERFULLCONTENT)) return null;
                WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
                info.bmiHeader.biWidth = breite;
                info.bmiHeader.biHeight = -hoehe;   // Zeilen von oben nach unten
                info.bmiHeader.biPlanes = 1;
                info.bmiHeader.biBitCount = 32;
                info.bmiHeader.biCompression = WinGDI.BI_RGB;
                if (GDI32.INSTANCE.GetDIBits(speicherDc, bmp, 0, hoehe, puffer, info, WinGDI.DIB_RGB_COLORS) != hoehe) return null;
                puffer.read(0, pixel, 0, n);
                for (int i = 0; i < n; i++) pixel[i] &= 0xFFFFFF;   // BGRX: das vierte Byte ist kein Alpha
                return pixel;
            } finally {
                GDI32.INSTANCE.SelectObject(speicherDc, alt);
                GDI32.INSTANCE.DeleteObject(bmp);
                GDI32.INSTANCE.DeleteDC(speicherDc);
                W.INSTANCE.ReleaseDC(h, fensterDc);
            }
        }

        @Override
        public Point zeiger() {
            return Geist.zeiger();
        }

        @Override
        public void zeigerSetzen(Point p) {
            Geist.zeigerSetzen(p);
        }

        @Override
        public boolean mausBelegt() {
            return Geist.mausBelegt();
        }

        @Override
        public void takt() {
            Geist.takt(h());
        }

        @Override
        public boolean aufSchirm(Point p) {
            return Geist.aufSchirm(p);
        }

        @Override
        public void ausrichten(int fx, int fy, Point m) {
            Geist.ausrichten(h(), fx, fy, m);
        }

        @Override
        public boolean frei(Point m) {
            return Geist.frei(h(), m);
        }

        @Override
        public void ruhe() {
            Geist.ruhe(h());
        }

        @Override
        public boolean beruehren(Point m, int flags) {
            return Geist.beruehren(m, flags);
        }

        @Override
        public long echteBewegungen() {
            return Mauslauscher.anzahl();
        }

        @Override
        public Point echteStelle() {
            return Mauslauscher.stelle();
        }

        @Override
        public long ruhtSeit() {
            return Mauslauscher.ruhtSeit();
        }

        @Override
        public boolean warten(long ms) {
            try {
                Thread.sleep(ms);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
