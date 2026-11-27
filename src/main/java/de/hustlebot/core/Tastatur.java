package de.hustlebot.core;

import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text in ein Eingabefeld des Spiels tippen (18.11.: Clan-Chat und Verlies-Nachricht fuer die
 * Quest "Go to a Dungeon with a clan mate").
 *
 * Getippt wird wie mit der echten Tastatur -- so wie es das SendKeys von Windows macht, mit dem der Text am 18.11. auf
 * Autors Rechner angekommen ist: Windows sagt (VkKeyScanEx), welche Taste mit welcher Umschalt-/AltGr-Kombination das
 * Zeichen auf DER Tastaturbelegung des Spielfensters ergibt. Auf Autors deutscher Tastatur ist "?" also Umschalt + ss,
 * "y" die Taste Y (links unten) -- das stimmt von selbst. Gibt es ein Zeichen auf der Belegung nicht, geht es als
 * Unicode-Zeichen hinaus (KEYEVENTF_UNICODE).
 *
 * Tasten, die als Not-Aus/Pause/Weiter eingestellt sind, werden NIE gedrueckt (die Wachen lesen GetAsyncKeyState und
 * saehen auch die Tasten des Bots): Das Zeichen geht dann als Unicode-Zeichen hinaus.
 *
 * Nur unter Windows. Anderswo (Tests in der Cloud) tut tippen() nichts und meldet false.
 *
 * LIVE PRUEFEN: kommt der Text im Chat-Feld des Spiels vollstaendig an (Satzzeichen ? ! : ) eingeschlossen)?
 */
public final class Tastatur {

    private Tastatur() {
    }

    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;   // Alt
    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int KEYEVENTF_UNICODE = 0x0004;

    /** So lange zwischen zwei Zeichen (Millisekunden) -- grob wie ein schneller Mensch, nie im Maschinentakt. */
    private static final int PAUSE_MIN_MS = 35;
    private static final int PAUSE_MAX_MS = 90;

    /** Virtuelle Tastencodes, die der Bot nicht druecken darf (Not-Aus, Pause, Weiter). */
    private static final Set<Integer> GESPERRT = ConcurrentHashMap.newKeySet();

    /** Die Wachen melden hier ihre Taste an (NotAusWache). */
    public static void nichtDruecken(int vk) {
        if (vk != 0) GESPERRT.add(vk);
    }

    /**
     * Ein Schritt beim Tippen: eine Taste runter oder hoch -- oder ein Unicode-Zeichen (dann ist taste das Zeichen).
     */
    public record Schritt(int taste, boolean hoch, boolean unicode) {
    }

    /**
     * Die Tastenfolge fuer ein Zeichen. vkScan ist das Ergebnis von VkKeyScanEx: unteres Byte die Taste, oberes Byte
     * 1 = Umschalt, 2 = Strg, 4 = Alt (AltGr = Strg+Alt). -1 (alle Bits) heisst: gibt es auf dieser Belegung nicht.
     *
     * Paketoeffentlich, damit es ohne Windows pruefbar ist.
     */
    static List<Schritt> schritte(char zeichen, short vkScan, Set<Integer> gesperrt) {
        List<Schritt> s = new ArrayList<>();
        int vk = vkScan & 0xFF;
        int zustand = (vkScan >> 8) & 0xFF;
        boolean fehlt = vkScan == -1 || vk == 0xFF || (zustand & ~0x07) != 0;
        if (fehlt || (gesperrt != null && gesperrt.contains(vk))) {
            s.add(new Schritt(zeichen, false, true));
            s.add(new Schritt(zeichen, true, true));
            return s;
        }
        List<Integer> mod = new ArrayList<>();
        if ((zustand & 1) != 0) mod.add(VK_SHIFT);
        if ((zustand & 2) != 0) mod.add(VK_CONTROL);
        if ((zustand & 4) != 0) mod.add(VK_MENU);
        for (int m : mod) s.add(new Schritt(m, false, false));
        s.add(new Schritt(vk, false, false));
        s.add(new Schritt(vk, true, false));
        for (int i = mod.size() - 1; i >= 0; i--) s.add(new Schritt(mod.get(i), true, false));
        return s;
    }

    /** Laeuft der Bot unter Windows? Nur dort kann getippt werden. */
    public static boolean moeglich() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Tippt den Text in das Fenster, das gerade vorne ist (vorher das Eingabefeld des Spiels antippen). Liefert false,
     * wenn es hier nicht geht (kein Windows) oder Windows Tasten nicht angenommen hat.
     */
    public static boolean tippen(String text) {
        if (text == null || text.isEmpty()) return true;
        if (!moeglich()) {
            Log.warn("Tastatur", Sprache.t("Typing only works on Windows -- nothing typed.",
                    "Tippen geht nur unter Windows -- nichts getippt."));
            return false;
        }
        if (Hintergrund.an()) return tippenHinten(text);
        try {
            User32 u = User32.INSTANCE;
            WinDef.HWND vorn = u.GetForegroundWindow();
            int faden = vorn == null ? 0 : u.GetWindowThreadProcessId(vorn, null);
            WinDef.HKL belegung = u.GetKeyboardLayout(faden);
            java.util.Random zufall = new java.util.Random();
            boolean alles = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                short vkScan = u.VkKeyScanExW(c, belegung);
                for (Schritt s : schritte(c, vkScan, GESPERRT)) {
                    alles &= senden(u, s);
                    Thread.sleep(8);
                }
                Thread.sleep(PAUSE_MIN_MS + zufall.nextInt(PAUSE_MAX_MS - PAUSE_MIN_MS + 1));
            }
            return alles;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.warn("Tastatur", Sprache.t("Typing failed: ", "Tippen ging nicht: ") + t.getMessage());
            return false;
        }
    }

    /**
     * Hintergrund-Modus (Autor 20.11.): NIE per SendInput -- das ginge in Autors Vordergrundprogramm. Stattdessen jedes
     * Zeichen als WM_CHAR direkt an das Spielfenster. UNGETESTET: ob Unity das im Hintergrund annimmt, ist nicht
     * gemessen.
     */
    static boolean tippenHinten(String text) {
        WinDef.HWND spiel = Hintergrund.spielFenster();
        if (spiel == null) {
            Log.warn("Tastatur", Sprache.t("Background mode: game window unknown -- nothing typed.",
                    "Hintergrund-Modus: Spielfenster unbekannt -- nichts getippt."));
            return false;
        }
        try {
            java.util.Random zufall = new java.util.Random();
            for (int i = 0; i < text.length(); i++) {
                User32.INSTANCE.PostMessage(spiel, 0x0102, new WinDef.WPARAM(text.charAt(i)), new WinDef.LPARAM(1));   // WM_CHAR
                Thread.sleep(PAUSE_MIN_MS + zufall.nextInt(PAUSE_MAX_MS - PAUSE_MIN_MS + 1));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.warn("Tastatur", Sprache.t("Typing failed: ", "Tippen ging nicht: ") + t.getMessage());
            return false;
        }
    }

    private static boolean senden(User32 u, Schritt s) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        int flags = s.hoch() ? KEYEVENTF_KEYUP : 0;
        if (s.unicode()) {
            in.input.ki.wVk = new WinDef.WORD(0);
            in.input.ki.wScan = new WinDef.WORD(s.taste());
            flags |= KEYEVENTF_UNICODE;
        } else {
            in.input.ki.wVk = new WinDef.WORD(s.taste());
            in.input.ki.wScan = new WinDef.WORD(0);
        }
        in.input.ki.dwFlags = new WinDef.DWORD(flags);
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        WinDef.DWORD n = u.SendInput(new WinDef.DWORD(1), (WinUser.INPUT[]) in.toArray(1), in.size());
        return n != null && n.intValue() == 1;
    }
}
