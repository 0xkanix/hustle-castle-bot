package de.hustlebot.schicht;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hoert systemweit auf Tastatur und Maus (WH_KEYBOARD_LL / WH_MOUSE_LL) --
 * NUR zuhoeren. Es wird nie eine Eingabe erzeugt. Auf Wunsch des Besitzers
 * (setSchlucken) wird ein Ereignis verschluckt, dann sieht das Spiel es nicht.
 *
 * WARUM SO VORSICHTIG. Solange ein solcher Haken haengt, wartet Windows bei
 * JEDER Mausbewegung und JEDEM Tastendruck im ganzen System auf uns. Ist der
 * Haken zu langsam, ruckelt die Maus ueberall -- und Windows wirft ihn nach
 * einer Weile still hinaus. Deshalb:
 *   - Mausbewegungen werden sofort durchgereicht, ohne jede Arbeit.
 *   - Im Haken wird nur entschieden "schlucken oder nicht"; die Zuhoerer
 *     laufen danach auf einem eigenen Faden (melder).
 *   - Alle zwei Minuten wird der Haken still neu angemeldet, falls Windows
 *     ihn doch einmal hinausgeworfen hat (etwa bei einer langen GC-Pause).
 *
 * EIGENE KLICKS DES BOTS. Was per SendInput (java.awt.Robot) erzeugt wird,
 * traegt bei Windows den Vermerk "eingespeist". Solche Ereignisse werden
 * standardmaessig ganz ignoriert -- die Klicks des Bots sind nie Autors
 * Eingabe.
 *
 * Nur 64-Bit-Java (wParam/lParam als long).
 */
public final class EingabeHaken {

    /** Was beim Zuhoerer ankommt. Alle Methoden laufen auf dem Melder-Faden, nicht im Haken. */
    public interface Zuhoerer {
        /** Eine Taste (virtueller Tastencode, VK_...). Gedrueckt halten meldet nur den ersten Druck. */
        default void taste(int vkCode, boolean gedrueckt) {}

        /** Rechte Maustaste gedrueckt, Punkt in Bildschirmpunkten. */
        default void rechtsKlick(Point schirmPunkt) {}

        /** Mittlere Maustaste (Rad) gedrueckt. */
        default void mittelKlick(Point schirmPunkt) {}

        /** Seitentaste gedrueckt: vorne = XBUTTON2 (vor), sonst XBUTTON1 (zurueck). */
        default void seitenTaste(boolean vorne) {}
    }

    /** Art des Ereignisses fuer die Schlucken-Entscheidung. */
    public enum Art { TASTE, RECHTS, MITTE, SEITE }

    /**
     * Ein Ereignis, wie es die Schlucken-Entscheidung sieht. Gefragt wird nur
     * beim DRUECKEN; das Loslassen (und Wiederholungen beim Gedrueckthalten)
     * bekommt automatisch dieselbe Antwort -- sonst saehe das Spiel ein
     * Loslassen ohne Druecken.
     *
     * @param vkCode      nur bei TASTE, sonst 0
     * @param schirmPunkt bei Mausereignissen, sonst null
     * @param vorne       nur bei SEITE
     */
    public record Ereignis(Art art, int vkCode, boolean gedrueckt, Point schirmPunkt, boolean vorne) {}

    // ---- Windows-Konstanten (WinUser.h) -------------------------------------
    private static final int WM_QUIT = 0x0012;
    private static final int WM_USER = 0x0400;
    private static final int WM_APP = 0x8000;
    private static final int WM_NEU_ANMELDEN = WM_APP + 0x51;
    private static final int PM_NOREMOVE = 0x0000;

    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_KEYUP = 0x0101;
    private static final int WM_SYSKEYDOWN = 0x0104;
    private static final int WM_SYSKEYUP = 0x0105;

    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_RBUTTONDOWN = 0x0204;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_MBUTTONDOWN = 0x0207;
    private static final int WM_MBUTTONUP = 0x0208;
    private static final int WM_XBUTTONDOWN = 0x020B;
    private static final int WM_XBUTTONUP = 0x020C;
    private static final int XBUTTON1 = 0x0001;
    private static final int XBUTTON2 = 0x0002;

    private static final int LLKHF_LOWER_IL_INJECTED = 0x02;
    private static final int LLKHF_INJECTED = 0x10;
    private static final int LLMHF_INJECTED = 0x01;
    private static final int LLMHF_LOWER_IL_INJECTED = 0x02;

    // Lage der Felder in KBDLLHOOKSTRUCT / MSLLHOOKSTRUCT (64 Bit). Direkt
    // gelesen statt ueber eine JNA-Structure: spart je Ereignis Reflexion.
    private static final int KB_VKCODE = 0;
    private static final int KB_FLAGS = 8;
    private static final int KB_TIME = 12;
    private static final int MS_X = 0;
    private static final int MS_Y = 4;
    private static final int MS_MOUSEDATA = 8;
    private static final int MS_FLAGS = 12;

    /** Rueckgabe "verschluckt": Windows reicht das Ereignis nicht weiter. */
    private static final long SCHLUCKEN = 1;

    /** Abstand, unter dem ein zweiter Druck derselben Taste als Wiederholung gilt (Windows: hoechstens 1 s Anfangsverzoegerung). */
    private static final int WIEDERHOLUNG_MS = 1100;

    private static final long NEU_ANMELDEN_MINUTEN = 2;

    private static final int K_RECHTS = 0;
    private static final int K_MITTE = 1;
    private static final int K_ZURUECK = 2;
    private static final int K_VOR = 3;

    /** Was Windows bei jedem Ereignis ruft. long statt WPARAM/LPARAM: keine Objekte je Aufruf. */
    interface RohHaken extends WinUser.HOOKPROC {
        long callback(int nCode, long wParam, long lParam);
    }

    /** CallNextHookEx mit long-Werten (JNAs Fassung will WPARAM/LPARAM-Objekte). */
    interface Haken32 extends Library {
        Haken32 INSTANCE = Native.load("user32", Haken32.class);

        long CallNextHookEx(Pointer hhk, int nCode, long wParam, long lParam);
    }

    // Feste Verweise -- sonst raeumt der Garbage Collector die Rueckrufe weg,
    // waehrend Windows sie noch ruft (Absturz).
    private final RohHaken tastenProc = this::tastenEreignis;
    private final RohHaken mausProc = this::mausEreignis;

    private final List<Zuhoerer> zuhoerer = new CopyOnWriteArrayList<>();
    private volatile Predicate<Ereignis> schlucken = null;
    private volatile boolean eingespeisteIgnorieren = true;

    private Thread faden;
    private volatile int fadenId = 0;
    private volatile boolean angemeldet = false;
    private volatile ScheduledThreadPoolExecutor melder;
    private final AtomicLong letzteFehlermeldung = new AtomicLong();

    // Nur auf dem Haken-Faden benutzt -- kein Abgleich noetig.
    private WinUser.HHOOK tastenHaken;
    private WinUser.HHOOK mausHaken;

    /** Autor 29.10. abends: Bedienung nur mit der Tastatur -- rechte, mittlere und Seitentaste bedienen nichts. */
    static final boolean MAUS = false;
    /** Autor 30.10.: der linke Klick wird nur mitgelesen, nie verschluckt. Nur mit Zuhoerer haengt der Maus-Haken ueberhaupt. */
    private volatile Consumer<Point> linksKlick;
    private final boolean[] tasteUnten = new boolean[256];
    private final boolean[] tasteGeschluckt = new boolean[256];
    private final int[] tasteZeit = new int[256];
    private final boolean[] knopfGeschluckt = new boolean[4];

    // ---- Einstellungen -------------------------------------------------------

    public void zuhoererDazu(Zuhoerer z) {
        if (z != null) zuhoerer.add(z);
    }

    public void zuhoererWeg(Zuhoerer z) {
        zuhoerer.remove(z);
    }

    /**
     * Entscheidet je Druck, ob er verschluckt wird (true = das Spiel sieht ihn
     * nicht). Laeuft IM Haken: muss sofort antworten -- nichts nachschlagen,
     * nichts protokollieren, nicht warten. null = nichts schlucken (Standard).
     * Wirft der Test eine Ausnahme, wird nicht geschluckt.
     */
    /** Vor start() setzen: laeuft im Haken, darf also nur Felder setzen und einen Faden anstossen. */
    public void setLinksKlick(Consumer<Point> zuhoerer) {
        linksKlick = zuhoerer;
    }

    public void setSchlucken(Predicate<Ereignis> test) {
        schlucken = test;
    }

    /** Standard true: eingespeiste Ereignisse (SendInput, auch die Klicks des Bots) werden ganz uebergangen. */
    public void setEingespeisteIgnorieren(boolean ignorieren) {
        eingespeisteIgnorieren = ignorieren;
    }

    /** Sind beide Haken angemeldet? */
    public boolean laeuft() {
        return angemeldet;
    }

    // ---- Start / Stopp ---------------------------------------------------------

    /**
     * Meldet beide Haken auf einem eigenen Faden an. true, wenn beide sitzen.
     * Ein zweiter Aufruf waehrend des Laufs tut nichts.
     */
    public synchronized boolean start() {
        if (faden != null) return angemeldet;
        if (Native.POINTER_SIZE != 8) {
            Log.warn("Eingabe", Sprache.t("Input hooks need 64-bit Java -- not started.",
                    "Eingabe-Haken brauchen 64-Bit-Java -- nicht gestartet."));
            return false;
        }
        try {
            // Vorab laden: sonst passiert das beim ersten Ereignis IM Haken (dauert).
            vorladen();
        } catch (Throwable t) {
            Log.warn("Eingabe", Sprache.t("Input hooks: user32 not available: " + t,
                    "Eingabe-Haken: user32 nicht verfuegbar: " + t));
            return false;
        }

        ScheduledThreadPoolExecutor m = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "eingabe-melder");
            t.setDaemon(true);
            return t;
        });
        m.setRemoveOnCancelPolicy(true);
        melder = m;

        CountDownLatch bereit = new CountDownLatch(1);
        Thread f = new Thread(() -> schleife(bereit), "eingabe-haken");
        f.setDaemon(true);
        f.setPriority(Thread.MAX_PRIORITY);
        faden = f;
        f.start();
        try {
            bereit.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!angemeldet) {
            Log.warn("Eingabe", Sprache.t("Keyboard/mouse hooks could not be installed.",
                    "Tastatur-/Maus-Haken liessen sich nicht anmelden."));
            stop();
            return false;
        }
        m.scheduleWithFixedDelay(this::neuAnmeldenAnstossen,
                NEU_ANMELDEN_MINUTEN, NEU_ANMELDEN_MINUTEN, TimeUnit.MINUTES);
        Log.info("Eingabe", Sprache.t("Keyboard and mouse hooks active.",
                "Tastatur- und Maus-Haken aktiv."));
        return true;
    }

    /** Meldet die Haken ab und beendet beide Faeden. Nicht aus dem Schlucken-Test heraus rufen. */
    public synchronized void stop() {
        Thread f = faden;
        if (f != null) {
            int id = fadenId;
            if (id != 0) {
                // Bis zu drei Versuche: die Nachrichtenschlange kann kurz voll sein.
                for (int i = 0; i < 3; i++) {
                    if (User32.INSTANCE.PostThreadMessage(id, WM_QUIT,
                            new WinDef.WPARAM(0), new WinDef.LPARAM(0)) != 0) break;
                }
            }
            if (f != Thread.currentThread()) {
                try {
                    f.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            faden = null;
        }
        ScheduledThreadPoolExecutor m = melder;
        melder = null;
        if (m != null) m.shutdown();
    }

    private static void vorladen() {
        if (Haken32.INSTANCE == null) throw new IllegalStateException("user32");
    }

    // ---- Haken-Faden -----------------------------------------------------------

    /**
     * Haken MUESSEN auf dem Faden angemeldet werden, der danach Nachrichten
     * abholt -- Windows ruft sie waehrend GetMessage auf genau diesem Faden.
     */
    private void schleife(CountDownLatch bereit) {
        WinUser.MSG msg = new WinUser.MSG();
        try {
            fadenId = Kernel32.INSTANCE.GetCurrentThreadId();
            // Legt die Nachrichtenschlange an, damit PostThreadMessage (stop) sicher ankommt.
            User32.INSTANCE.PeekMessage(msg, null, WM_USER, WM_USER, PM_NOREMOVE);
            anmelden();
            angemeldet = tastenHaken != null && (!MAUS || mausHaken != null);
            bereit.countDown();
            if (!angemeldet) return;

            int r;
            while ((r = User32.INSTANCE.GetMessage(msg, null, 0, 0)) != 0) {
                if (r == -1) {
                    fehlerMelden("GetMessage", new IllegalStateException("GetMessage -1, Fehler " + Native.getLastError()));
                    break;
                }
                if (msg.message == WM_NEU_ANMELDEN) anmelden();
            }
        } catch (Throwable t) {
            fehlerMelden("Schleife", t);
        } finally {
            abmelden();
            angemeldet = false;
            fadenId = 0;
            bereit.countDown();
        }
    }

    /**
     * Neu anmelden, ERST dann die alten abmelden. Dazwischen holt dieser
     * Faden keine Nachrichten ab, also kann kein Ereignis doppelt oder gar
     * nicht ankommen.
     */
    private void anmelden() {
        WinDef.HMODULE modul = Kernel32.INSTANCE.GetModuleHandle(null);

        WinUser.HHOOK neuTasten = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, tastenProc, modul, 0);
        if (neuTasten != null) {
            if (tastenHaken != null) User32.INSTANCE.UnhookWindowsHookEx(tastenHaken);
            tastenHaken = neuTasten;
        } else if (tastenHaken == null) {
            fehlerMelden("Tastatur", new IllegalStateException("SetWindowsHookEx Fehler " + Native.getLastError()));
        }

        if (!MAUS && linksKlick == null) return;
        WinUser.HHOOK neuMaus = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, mausProc, modul, 0);
        if (neuMaus != null) {
            if (mausHaken != null) User32.INSTANCE.UnhookWindowsHookEx(mausHaken);
            mausHaken = neuMaus;
        } else if (mausHaken == null) {
            fehlerMelden("Maus", new IllegalStateException("SetWindowsHookEx Fehler " + Native.getLastError()));
        }
    }

    private void abmelden() {
        if (tastenHaken != null) User32.INSTANCE.UnhookWindowsHookEx(tastenHaken);
        if (mausHaken != null) User32.INSTANCE.UnhookWindowsHookEx(mausHaken);
        tastenHaken = null;
        mausHaken = null;
    }

    private void neuAnmeldenAnstossen() {
        int id = fadenId;
        if (id != 0 && angemeldet) {
            User32.INSTANCE.PostThreadMessage(id, WM_NEU_ANMELDEN, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        }
    }

    // ---- Die Haken selbst (laufen auf dem Haken-Faden, muessen schnell sein) ----

    private long tastenEreignis(int nCode, long wParam, long lParam) {
        if (nCode >= 0) {
            try {
                if (tasteBehandeln((int) wParam, lParam)) return SCHLUCKEN;
            } catch (Throwable t) {
                fehlerMelden("Tastatur", t);
            }
        }
        return weiter(nCode, wParam, lParam);
    }

    private boolean tasteBehandeln(int nachricht, long lParam) {
        boolean runter = nachricht == WM_KEYDOWN || nachricht == WM_SYSKEYDOWN;
        if (!runter && nachricht != WM_KEYUP && nachricht != WM_SYSKEYUP) return false;
        Pointer daten = new Pointer(lParam);
        int flags = daten.getInt(KB_FLAGS);
        if (eingespeisteIgnorieren && (flags & (LLKHF_INJECTED | LLKHF_LOWER_IL_INJECTED)) != 0) return false;
        int vk = daten.getInt(KB_VKCODE) & 0xFF;

        if (!runter) {
            boolean geschluckt = tasteGeschluckt[vk];
            tasteGeschluckt[vk] = false;
            tasteUnten[vk] = false;
            melden(z -> z.taste(vk, false));
            return geschluckt;
        }

        // Gedrueckt halten schickt dieselbe Taste immer wieder. Nur der erste
        // Druck zaehlt. Ueber die Zeit statt nur "war schon unten": ging das
        // Loslassen einmal verloren (Win+L, Haken kurz weg), haengt die Taste
        // sonst fuer immer.
        int zeit = daten.getInt(KB_TIME);
        int abstand = zeit - tasteZeit[vk];
        tasteZeit[vk] = zeit;
        if (tasteUnten[vk] && abstand >= 0 && abstand < WIEDERHOLUNG_MS) return tasteGeschluckt[vk];

        tasteUnten[vk] = true;
        boolean geschluckt = fragen(new Ereignis(Art.TASTE, vk, true, null, false));
        tasteGeschluckt[vk] = geschluckt;
        melden(z -> z.taste(vk, true));
        return geschluckt;
    }

    private long mausEreignis(int nCode, long wParam, long lParam) {
        // Mausbewegungen sind die grosse Masse (bis 1000 je Sekunde): sofort weiter.
        if (nCode >= 0 && wParam != WM_MOUSEMOVE) {
            try {
                if (mausBehandeln((int) wParam, lParam)) return SCHLUCKEN;
            } catch (Throwable t) {
                fehlerMelden("Maus", t);
            }
        }
        return weiter(nCode, wParam, lParam);
    }

    /** Laeuft IM Haken: der Zuhoerer darf nur Felder setzen und einen Faden anstossen. Der Klick geht immer ans Spiel. */
    private void linksMelden(long lParam) {
        Consumer<Point> l = linksKlick;
        if (l == null) return;
        Pointer daten = new Pointer(lParam);
        if (eingespeisteIgnorieren && (daten.getInt(MS_FLAGS) & (LLMHF_INJECTED | LLMHF_LOWER_IL_INJECTED)) != 0) return;
        l.accept(new Point(daten.getInt(MS_X), daten.getInt(MS_Y)));
    }

    private boolean mausBehandeln(int nachricht, long lParam) {
        if (nachricht == WM_LBUTTONDOWN) {
            linksMelden(lParam);
            return false;
        }
        if (!MAUS) return false;
        // Rad und Doppelklick-Meldungen interessieren nicht.
        if (nachricht < WM_RBUTTONDOWN || nachricht > WM_XBUTTONUP) return false;
        Pointer daten = new Pointer(lParam);

        int knopf;
        boolean runter;
        switch (nachricht) {
            case WM_RBUTTONDOWN: knopf = K_RECHTS; runter = true; break;
            case WM_RBUTTONUP: knopf = K_RECHTS; runter = false; break;
            case WM_MBUTTONDOWN: knopf = K_MITTE; runter = true; break;
            case WM_MBUTTONUP: knopf = K_MITTE; runter = false; break;
            case WM_XBUTTONDOWN:
            case WM_XBUTTONUP: {
                int welcher = (daten.getInt(MS_MOUSEDATA) >>> 16) & 0xFFFF;
                if (welcher == XBUTTON1) knopf = K_ZURUECK;
                else if (welcher == XBUTTON2) knopf = K_VOR;
                else return false;
                runter = nachricht == WM_XBUTTONDOWN;
                break;
            }
            default:
                return false;
        }

        int flags = daten.getInt(MS_FLAGS);
        if (eingespeisteIgnorieren && (flags & (LLMHF_INJECTED | LLMHF_LOWER_IL_INJECTED)) != 0) return false;

        if (!runter) {
            boolean geschluckt = knopfGeschluckt[knopf];
            knopfGeschluckt[knopf] = false;
            return geschluckt;
        }

        int x = daten.getInt(MS_X);
        int y = daten.getInt(MS_Y);
        Ereignis e = switch (knopf) {
            case K_RECHTS -> new Ereignis(Art.RECHTS, 0, true, new Point(x, y), false);
            case K_MITTE -> new Ereignis(Art.MITTE, 0, true, new Point(x, y), false);
            default -> new Ereignis(Art.SEITE, 0, true, new Point(x, y), knopf == K_VOR);
        };
        boolean geschluckt = fragen(e);
        knopfGeschluckt[knopf] = geschluckt;

        switch (knopf) {
            case K_RECHTS -> melden(z -> z.rechtsKlick(new Point(x, y)));
            case K_MITTE -> melden(z -> z.mittelKlick(new Point(x, y)));
            default -> {
                boolean vorne = knopf == K_VOR;
                melden(z -> z.seitenTaste(vorne));
            }
        }
        return geschluckt;
    }

    /** hhk wird von Windows ignoriert, null genuegt. */
    private static long weiter(int nCode, long wParam, long lParam) {
        return Haken32.INSTANCE.CallNextHookEx(null, nCode, wParam, lParam);
    }

    private boolean fragen(Ereignis e) {
        Predicate<Ereignis> test = schlucken;
        if (test == null) return false;
        try {
            return test.test(e);
        } catch (Throwable t) {
            fehlerMelden("Schlucken", t);
            return false;
        }
    }

    /** Uebergibt an den Melder-Faden; blockiert nie. */
    private void melden(Consumer<Zuhoerer> aufruf) {
        if (zuhoerer.isEmpty()) return;
        ScheduledThreadPoolExecutor m = melder;
        if (m == null) return;
        try {
            m.execute(() -> {
                for (Zuhoerer z : zuhoerer) {
                    try {
                        aufruf.accept(z);
                    } catch (Throwable t) {
                        fehlerMelden("Zuhoerer", t);
                    }
                }
            });
        } catch (RejectedExecutionException ignoriert) {
            // gerade beim Stoppen
        }
    }

    /**
     * Fehler hoechstens alle 30 s ins Protokoll -- und nie direkt aus dem
     * Haken: Schreiben in die Datei waere dort viel zu langsam.
     */
    private void fehlerMelden(String wo, Throwable t) {
        long jetzt = System.currentTimeMillis();
        long zuletzt = letzteFehlermeldung.get();
        if (jetzt - zuletzt < 30_000 || !letzteFehlermeldung.compareAndSet(zuletzt, jetzt)) return;
        Runnable schreiben = () -> Log.warn("Eingabe", Sprache.t(
                "Input hook (" + wo + "): " + t, "Eingabe-Haken (" + wo + "): " + t));
        ScheduledThreadPoolExecutor m = melder;
        if (m != null && Thread.currentThread() == faden) {
            try {
                m.execute(schreiben);
                return;
            } catch (RejectedExecutionException ignoriert) {
                // dann eben direkt
            }
        }
        schreiben.run();
    }
}
