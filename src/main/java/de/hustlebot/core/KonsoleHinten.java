package de.hustlebot.core;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/**
 * Autor, 15.10.: "Den Log bitte immer im Hintergrund starten, dass er mein Spiel nicht ueberdeckt."
 *
 * HustleBot.exe ist ein Konsolenprogramm -- beim Start geht das schwarze Protokollfenster ueber dem
 * Spiel auf. Hier wird es gleich am Anfang minimiert, ohne den Fokus zu nehmen (SW_SHOWMINNOACTIVE).
 * Unter Windows Terminal ist das Konsolenfenster nur ein Stellvertreter; darum werden zusaetzlich
 * sichtbare Fenster mit "HustleBot.exe" im Titel minimiert (so heisst dort der Reiter).
 * Es ist nur Komfort: geht etwas schief, bleibt das Fenster eben offen.
 */
public final class KonsoleHinten {

    private static final int SW_SHOWMINNOACTIVE = 7;

    private KonsoleHinten() {}

    /** Sofort und noch zweimal kurz danach: Windows Terminal oeffnet seinen Reiter erst einen Moment spaeter. */
    public static void minimieren() {
        markierModusAus();
        Thread t = new Thread(() -> {
            for (long warte : new long[]{0, 800, 2500}) {
                try {
                    Thread.sleep(warte);
                } catch (InterruptedException e) {
                    return;
                }
                einmal();
            }
        }, "Konsole-hinten");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Start-Probleme-Recherche 16.10. (doku/START-PROBLEME-16-10.md, Punkt 1): in der klassischen Konsole
     * (Windows 10) schaltet ein Klick ins schwarze Fenster den Markier-Modus ein -- dann haelt jede
     * Protokollzeile den Bot an ("laeuft, aber nichts passiert"), bis jemand eine Taste drueckt.
     * Darum den Markier-Modus (QuickEdit) ausschalten. Unter Windows Terminal aendert das nichts.
     */
    static void markierModusAus() {
        try {
            var eingabe = Kernel32.INSTANCE.GetStdHandle(Kernel32.STD_INPUT_HANDLE);
            var modus = new com.sun.jna.ptr.IntByReference();
            if (eingabe == null || !Kernel32.INSTANCE.GetConsoleMode(eingabe, modus)) return;
            int neu = (modus.getValue() | 0x80) & ~0x40;   // ENABLE_EXTENDED_FLAGS an, ENABLE_QUICK_EDIT_MODE aus
            if (neu != modus.getValue() && Kernel32.INSTANCE.SetConsoleMode(eingabe, neu)) {
                Log.debug("Start", "Konsole: Markier-Modus (QuickEdit) aus -- ein Klick ins Fenster haelt den Bot nicht mehr an.");
            }
        } catch (Throwable t) {
            Log.debug("Start", "Konsole: Markier-Modus nicht geaendert: " + t.getMessage());
        }
    }

    private static void einmal() {
        try {
            WinDef.HWND konsole = Kernel32.INSTANCE.GetConsoleWindow();
            if (konsole != null) User32.INSTANCE.ShowWindow(konsole, SW_SHOWMINNOACTIVE);
            User32.INSTANCE.EnumWindows((h, daten) -> {
                char[] puffer = new char[300];
                User32.INSTANCE.GetWindowText(h, puffer, puffer.length);
                String titel = com.sun.jna.Native.toString(puffer);
                if (User32.INSTANCE.IsWindowVisible(h) && titel.endsWith("HustleBot.exe")) {
                    User32.INSTANCE.ShowWindow(h, SW_SHOWMINNOACTIVE);
                }
                return true;
            }, null);
        } catch (Throwable t) {
            Log.debug("Start", "Konsole nicht minimiert: " + t.getMessage());
        }
    }
}
