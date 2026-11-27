package de.hustlebot.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.awt.Point;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lauscht im Hintergrund-Modus auf Autors ECHTE Mausbewegungen (Autor/Live-Test 20.11., Vorlage Geist11): wann er die
 * Maus zuletzt bewegt hat (vor einem Tipp warten, bis sie 40 ms ruht) und wo sein Zeiger danach steht (nach dem Tipp
 * den Zeiger genau dorthin, nicht auf die alte Stelle).
 *
 * Ein Low-Level-Maushaken (WH_MOUSE_LL) auf einem eigenen Faden mit Nachrichtenschleife. Gezaehlt werden nur
 * Ereignisse OHNE LLMHF_INJECTED (die aus dem Touch erzeugten Mausbewegungen sind "eingespeist"). Angemeldet wird er
 * erst beim ersten Tipp und nach {@link #UNBENUTZT_MS} ohne Tipp wieder abgemeldet -- ein dauernder Haken in Java
 * koennte Autors Maus bei einer Speicherbereinigung kurz aufhalten.
 */
final class Mauslauscher {

    private Mauslauscher() {
    }

    static final long UNBENUTZT_MS = 10_000;
    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_ANMELDEN = 0x8001;    // WM_APP + 1
    private static final int WM_ABMELDEN = 0x8002;    // WM_APP + 2
    private static final int LLMHF_INJECTED = 0x01;
    private static final int LLMHF_LOWER_IL_INJECTED = 0x02;
    private static final long ANMELDEN_WARTEN_MS = 100;

    /** Was Windows bei jedem Mausereignis ruft (long statt WPARAM/LPARAM: keine Objekte je Aufruf). */
    interface RohHaken extends WinUser.HOOKPROC {
        long callback(int nCode, long wParam, long lParam);
    }

    interface Haken32 extends Library {
        Haken32 INSTANCE = Native.load("user32", Haken32.class);

        long CallNextHookEx(Pointer hhk, int nCode, long wParam, long lParam);
    }

    // Fester Verweis -- sonst raeumt der Garbage Collector den Rueckruf weg, waehrend Windows ihn noch ruft.
    private static final RohHaken PROC = Mauslauscher::ereignis;

    private static Thread faden;
    private static volatile int fadenId = 0;
    private static volatile boolean angemeldet = false;
    private static volatile long zuletztBenutzt = 0;
    private static WinUser.HHOOK haken;   // nur auf dem Haken-Faden benutzt

    private static volatile long anzahl = 0;
    private static volatile int echtX, echtY;
    private static volatile long echtUm = 0;

    /** Zahl der echten Bewegungen bisher (aendert sie sich, hat Autor die Maus bewegt). Meldet den Haken an. */
    static long anzahl() {
        benutzen();
        return anzahl;
    }

    /** Wo Autors Zeiger nach seiner letzten echten Bewegung stand. */
    static Point stelle() {
        return new Point(echtX, echtY);
    }

    /** Wie lange die echte Maus schon ruht (ms; nach dem Anmelden zaehlt die Anmeldung als Bewegung). */
    static long ruhtSeit() {
        benutzen();
        return System.currentTimeMillis() - echtUm;
    }

    /** Vom Wache-Faden: nach langer Pause abmelden. */
    static void aufraeumen() {
        if (angemeldet && System.currentTimeMillis() - zuletztBenutzt > UNBENUTZT_MS) abmelden();
    }

    static synchronized void abmelden() {
        if (!angemeldet || fadenId == 0) return;
        angemeldet = false;
        User32.INSTANCE.PostThreadMessage(fadenId, WM_ABMELDEN, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
    }

    private static synchronized void benutzen() {
        zuletztBenutzt = System.currentTimeMillis();
        if (angemeldet || !starten()) return;
        Point p = Geist.zeiger();
        echtX = p.x;
        echtY = p.y;
        echtUm = System.currentTimeMillis();
        angemeldet = true;
        CountDownLatch quittung = new CountDownLatch(1);
        warteAufAnmeldung = quittung;
        User32.INSTANCE.PostThreadMessage(fadenId, WM_ANMELDEN, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        try {
            quittung.await(ANMELDEN_WARTEN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static volatile CountDownLatch warteAufAnmeldung;

    private static long ereignis(int nCode, long wParam, long lParam) {
        try {
            if (nCode >= 0 && wParam == WM_MOUSEMOVE && lParam != 0) {
                Pointer d = new Pointer(lParam);
                int flags = d.getInt(12);   // MSLLHOOKSTRUCT: pt.x (0), pt.y (4), mouseData (8), flags (12)
                if ((flags & (LLMHF_INJECTED | LLMHF_LOWER_IL_INJECTED)) == 0) {
                    echtX = d.getInt(0);
                    echtY = d.getInt(4);
                    echtUm = System.currentTimeMillis();
                    anzahl++;
                }
            }
        } catch (Throwable ignored) {
            // nie die Maus des ganzen Rechners aufhalten
        }
        return Haken32.INSTANCE.CallNextHookEx(null, nCode, wParam, lParam);
    }

    private static boolean starten() {
        if (faden != null) return fadenId != 0;
        CountDownLatch bereit = new CountDownLatch(1);
        faden = new Thread(() -> {
            try {
                fadenId = Kernel32.INSTANCE.GetCurrentThreadId();
                WinUser.MSG msg = new WinUser.MSG();
                User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0);   // legt die Nachrichtenschlange an (PostThreadMessage)
                bereit.countDown();
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_ANMELDEN) {
                        if (haken == null) {
                            haken = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_MOUSE_LL, PROC,
                                    Kernel32.INSTANCE.GetModuleHandle(null), 0);
                            if (haken == null) angemeldet = false;
                        }
                        CountDownLatch q = warteAufAnmeldung;
                        if (q != null) q.countDown();
                    } else if (msg.message == WM_ABMELDEN) {
                        if (haken != null) User32.INSTANCE.UnhookWindowsHookEx(haken);
                        haken = null;
                    } else {
                        User32.INSTANCE.TranslateMessage(msg);
                        User32.INSTANCE.DispatchMessage(msg);
                    }
                }
            } catch (Throwable t) {
                Log.warn("Hintergrund", Sprache.t("Mouse listener stopped: ", "Mauslauscher gestoppt: ") + t.getMessage());
            } finally {
                bereit.countDown();
            }
        }, "hintergrund-mauslauscher");
        faden.setDaemon(true);
        faden.start();
        try {
            bereit.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fadenId != 0;
    }
}
