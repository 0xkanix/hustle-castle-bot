package de.hustlebot.core;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Rectangle;

/**
 * Findet das Hustle-Castle-Fenster, bringt es nach vorne und setzt es auf eine
 * feste Groesse. Ohne feste Groesse funktioniert die Bilderkennung nicht, weil
 * die Vorlagen sonst nicht mehr massstabsgetreu passen.
 */
public final class GameWindow {

    /**
     * Erweiterte User32-Schnittstelle.
     *
     * JNA bringt die meisten Fensterfunktionen bereits mit. Nur diese beiden
     * fehlen dort und werden deshalb hier selbst angemeldet. Alles andere
     * (FindWindow, GetClientRect, SetWindowPos, ShowWindow, GetAsyncKeyState
     * und so weiter) wird von User32 geerbt.
     */
    public interface Ext extends User32 {
        Ext INSTANCE = Native.load("user32", Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        /** Meldet, ob das Fenster gerade minimiert ist. */
        boolean IsIconic(WinDef.HWND hWnd);

        /** Rechnet einen Punkt im Inhaltsbereich in Bildschirmkoordinaten um. */
        boolean ClientToScreen(WinDef.HWND hWnd, WinDef.POINT lpPoint);

        /** Bildschirmmasse. SM_CXSCREEN (0) ist die Breite des Hauptbildschirms. */
        int GetSystemMetrics(int nIndex);

        /** Eine Taste druecken/loslassen (hier nur Alt, fuer die Vordergrund-Sperre). */
        void keybd_event(byte bVk, byte bScan, int dwFlags, int dwExtraInfo);
    }

    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SW_RESTORE = 9;
    private static final int SM_CXSCREEN = 0;

    /**
     * Das Fenster oben rechts andocken. Autor, 17.09.: fuer die Kathedrale im
     * Turbo sollen die Knoepfe immer an derselben Stelle liegen, damit der Bot
     * blind draufdruecken kann. "am besten in die rechte obere ecke schieben".
     *
     * WARUM DIE ZEHN. Windows legt links, rechts und unten einen unsichtbaren
     * Greifrand von rund sieben Bildpunkten um jedes Fenster. Setzt man die
     * rechte Fensterkante genau auf den Bildschirmrand, endet das SICHTBARE
     * Bild sieben Punkte davor -- die Luecke, die Autor gesehen hat. Zehn
     * Punkte ueber den Rand hinaus schieben den Greifrand hinaus, und das Bild
     * sitzt buendig. Auf Autors Bildschirm (1920 breit, Fenster 1175) ergibt
     * das Left = 755 -- genau die Stelle, die er von Hand als perfekt
     * bestaetigt hat.
     */
    private static final int RAND_KOMPENSATION = 10;

    /**
     * Start-Probleme 16.10. (doku/START-PROBLEME-16-10.md, Punkt 2): die Bilder passen auf ein Spiel mit dem
     * INNENmass 1159 x 770 (bei Autor: aussen 1175 x 809). Bei anderer Windows-Skalierung, anderem Design oder
     * randlosem Fenster ergibt dasselbe Aussenmass ein anderes Innenmass -- dann wird das Aussenmass einmal
     * um die Abweichung nachgestellt. Bei Autor ist die Abweichung 0, es aendert sich nichts.
     */
    private static final int INNEN_BREITE = 1159;
    private static final int INNEN_HOEHE = 770;
    private int korrekturBreite = 0;
    private int korrekturHoehe = 0;
    private boolean innenGeprueft = false;

    private final String windowTitle;
    private final int targetWidth;
    private final int targetHeight;

    private WinDef.HWND handle;

    public GameWindow(String windowTitle, int targetWidth, int targetHeight) {
        this.windowTitle = windowTitle;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }

    /**
     * Sucht ausschliesslich ein SICHTBARES Fenster.
     *
     * Fuer den MY.GAMES Launcher unentbehrlich. Der haelt naemlich ein
     * unsichtbares Fenster mit demselben Titel dauerhaft im Hintergrund.
     * locate() findet ueber FindWindow zuerst dieses -- und meldet dann
     * "da, aber nicht sichtbar", egal wie oft man nachsieht.
     *
     * Genau daran hat sich der Bot am 02.09. festgefahren: Er hat den
     * Launcher alle zwanzig Sekunden neu gestartet, weil er ihn nie als
     * "schon gesehen" verbuchen konnte. Autor hat ihn dreimal zugemacht,
     * dreimal kam er wieder.
     */
    public boolean locateVisible() {
        handle = findByPartialTitle(windowTitle);
        return handle != null;
    }

    /** Sucht das Fenster. Gibt false zurueck, wenn es nicht offen ist. */
    public boolean locate() {
        handle = Ext.INSTANCE.FindWindow(null, windowTitle);
        if (handle == null) {
            // Zweiter Versuch: Fenstertitel enthaelt den Suchbegriff nur teilweise.
            handle = findByPartialTitle(windowTitle);
        }
        if (handle == null) {
            Log.warn("Fenster", Sprache.t("No window with the title '" + windowTitle + "' found.",
                    "Kein Fenster mit dem Titel '" + windowTitle + "' gefunden."));
            return false;
        }
        return true;
    }

    /**
     * Ist Hustle Castle gerade offen? NUR nachsehen.
     *
     * Anders als locate() merkt es sich kein Fenster und schreibt nichts ins
     * Protokoll. Der SpielWaechter fragt das alle fuenfzehn Sekunden -- ein
     * "Kein Fenster gefunden" jedes Mal waere reiner Laerm, und das Fenster,
     * mit dem der Motor gerade arbeitet, soll ihm niemand austauschen.
     *
     * Gezaehlt werden nur SICHTBARE Fenster (ein minimiertes zaehlt als
     * sichtbar), siehe findByPartialTitle().
     */
    public boolean istDa() {
        return findByPartialTitle(windowTitle) != null;
    }

    /**
     * Sucht ein Fenster, dessen Titel zum Suchbegriff passt.
     *
     * HIER LAG EIN BOESER FEHLER. Frueher genuegte es, dass der Suchbegriff im
     * Titel IRGENDWO vorkam. Bei "Hustle Castle" passte damit auch:
     *
     *     "Hustle Castle Bot"                              -- das eigene schwarze Fenster
     *     "#bot-ueberwachung | Hustle Castle Bot ... Discord"  -- Autors Discord
     *
     * Solange das Spiel lief, fiel das nie auf: Es wurde einfach zuerst
     * gefunden. War das Spiel aber zu -- und genau das ist nach dem
     * Hochfahren der Fall -- griff der Bot nach dem naechstbesten Fenster.
     * Er haette Discord auf 1175x809 zurechtgestutzt, nach vorne geholt und
     * angefangen, hineinzuklicken. Der Selbststart des Spiels waere zudem nie
     * gelaufen, weil ja "schon ein Fenster da" war.
     *
     * Jetzt wird in zwei Durchgaengen gesucht:
     *
     *   1. Der Titel IST der gesuchte, bis auf Leerzeichen. Das Spiel heisst
     *      "Hustle Castle  " -- mit angehaengten Leerzeichen, deshalb reicht
     *      ein einfacher Vergleich nicht.
     *
     *   2. Der Titel faengt damit an, und danach kommt ein Trennzeichen.
     *      Damit passt "Hustle Castle: Fantasy Kingdom", falls das Spiel
     *      seinen Titel einmal aendert -- "Hustle Castle Bot" aber nicht.
     */
    private WinDef.HWND findByPartialTitle(String needle) {
        WinDef.HWND treffer = suche(needle, true);
        if (treffer != null) return treffer;
        return suche(needle, false);
    }

    private WinDef.HWND suche(String needle, boolean genau) {
        final WinDef.HWND[] found = new WinDef.HWND[1];
        final String gesucht = needle.trim().toLowerCase();

        Ext.INSTANCE.EnumWindows((hWnd, data) -> {
            if (!Ext.INSTANCE.IsWindowVisible(hWnd)) return true;

            char[] buffer = new char[512];
            Ext.INSTANCE.GetWindowText(hWnd, buffer, buffer.length);
            String titel = Native.toString(buffer).trim().toLowerCase();

            // NICHT das Spiel aus Google Play Games (27.09., Autors zweites Konto
            // "Hustle Castle: Ritterspiele - QuaintSole110"). Das passte bisher
            // als Untertitel -- war das PC-Spiel gerade zu (Spiel-Neustart), haette
            // der Bot im zweiten Konto geklickt oder das PC-Spiel gar nicht gestartet.
            if (titel.contains("ritterspiele") || titel.contains("google play")) return true;

            boolean passt;
            if (genau) {
                passt = titel.equals(gesucht);
            } else {
                if (!titel.startsWith(gesucht)) return true;
                String rest = titel.substring(gesucht.length()).trim();
                // Nach dem Namen darf nichts mehr kommen -- oder ein
                // Trennzeichen, hinter dem ein Untertitel steht.
                passt = rest.isEmpty()
                        || rest.startsWith(":") || rest.startsWith("-")
                        || rest.startsWith("–") || rest.startsWith("—");
            }

            if (passt) {
                found[0] = hWnd;
                return false;
            }
            return true;
        }, null);
        return found[0];
    }

    /** Holt das Fenster nach vorne, stellt es wieder her und setzt die Zielgroesse. */
    public void prepare() {
        if (handle == null) return;

        if (Ext.INSTANCE.IsIconic(handle)) {
            Log.info("Fenster", Sprache.t("Window was minimized, restoring it.",
                    "Fenster war minimiert, wird wiederhergestellt."));
            Ext.INSTANCE.ShowWindow(handle, SW_RESTORE);
            sleep(400);
        }

        WinDef.RECT outer = new WinDef.RECT();
        Ext.INSTANCE.GetWindowRect(handle, outer);
        int currentW = outer.right - outer.left;
        int currentH = outer.bottom - outer.top;

        // Bei einer festen Groesse (das Spielfenster, nicht der Launcher mit 0)
        // das Fenster oben rechts andocken. So liegen die Knoepfe jedes Mal an
        // derselben Stelle -- die Voraussetzung fuer die Blindklicks im Turbo.
        // Der Launcher (targetWidth 0) wird nicht angefasst.
        boolean festeGroesse = targetWidth > 0 && targetHeight > 0;
        int sollBreite = targetWidth + korrekturBreite;
        int sollHoehe = targetHeight + korrekturHoehe;
        int zielLinks = outer.left;
        int zielOben = outer.top;
        if (festeGroesse) {
            int screenW = Ext.INSTANCE.GetSystemMetrics(SM_CXSCREEN);
            if (screenW > sollBreite) {
                zielLinks = screenW - sollBreite + RAND_KOMPENSATION;
                zielOben = 0;
            }
        }

        if (currentW != sollBreite || currentH != sollHoehe
                || outer.left != zielLinks || outer.top != zielOben) {
            Log.action("Fenster", Sprache.t("Window is set to " + sollBreite + "x" + sollHoehe + " and docked at the top right (corner " + zielLinks + "," + zielOben + ").",
                    "Fenster wird auf " + sollBreite + "x" + sollHoehe
                    + " gesetzt und oben rechts angedockt (Ecke " + zielLinks + "," + zielOben + ")."));
            Ext.INSTANCE.SetWindowPos(handle, null, zielLinks, zielOben,
                    sollBreite, sollHoehe, SWP_NOZORDER | SWP_NOACTIVATE);
            sleep(500);
        }
        if (festeGroesse && !innenGeprueft && targetWidth == 1175 && targetHeight == 809) {
            innenPruefen(zielLinks, zielOben);
        }

        if (!handle.equals(Ext.INSTANCE.GetForegroundWindow())) {
            Log.debug("Fenster", "Fenster ist verdeckt, wird nach vorne geholt.");
            Ext.INSTANCE.SetForegroundWindow(handle);
            sleep(250);
            // VORDERGRUND-SPERRE (28.09.): Windows laesst ein Programm aus dem
            // Hintergrund nicht einfach nach vorne. Lag die Claude-App ueber dem
            // Spiel, sah der Bot nur sie -- Leuchtturm "Bildschirm nicht erkannt".
            // Der bekannte Ausweg: einmal Alt antippen, dann erlaubt Windows es.
            if (!handle.equals(Ext.INSTANCE.GetForegroundWindow())) {
                Ext.INSTANCE.keybd_event((byte) 0x12, (byte) 0, 0, 0);            // Alt runter
                Ext.INSTANCE.SetForegroundWindow(handle);
                Ext.INSTANCE.keybd_event((byte) 0x12, (byte) 0, 0x0002, 0);       // Alt hoch
                sleep(250);
                if (!handle.equals(Ext.INSTANCE.GetForegroundWindow())
                        && System.currentTimeMillis() - vordergrundGewarntUm > 60_000) {
                    vordergrundGewarntUm = System.currentTimeMillis();
                    Log.warn("Fenster", Sprache.t("The game window cannot be brought to the front -- another " + "window lies over it. The bot then does not see the game.",
                            "Das Spielfenster laesst sich nicht nach vorne holen -- ein "
                            + "anderes Fenster liegt darueber. Der Bot sieht dann nicht das Spiel."));
                }
            }
        }
    }

    private long vordergrundGewarntUm = 0;

    /** Einmal je Start: Innenmass messen, ins Protokoll schreiben und bei Abweichung das Aussenmass nachstellen. */
    private void innenPruefen(int links, int oben) {
        Rectangle innen = clientArea();
        if (innen == null) return;
        innenGeprueft = true;
        int dB = INNEN_BREITE - innen.width;
        int dH = INNEN_HOEHE - innen.height;
        if (Math.abs(dB) <= 2 && Math.abs(dH) <= 2) {
            Log.info("Fenster", Sprache.t("Game window inside " + innen.width + "x" + innen.height + " -- fits the images.",
                    "Spielfenster innen " + innen.width + "x" + innen.height + " -- passt zu den Bildern."));
            return;
        }
        korrekturBreite += dB;
        korrekturHoehe += dH;
        Log.warn("Fenster", Sprache.t("Game window inside " + innen.width + "x" + innen.height + " instead of " + INNEN_BREITE + "x"
                        + INNEN_HOEHE + " (Windows scaling or design?) -- the window is adjusted by " + dB + "/" + dH + " pixels.",
                "Spielfenster innen " + innen.width + "x" + innen.height + " statt " + INNEN_BREITE + "x" + INNEN_HOEHE
                        + " (Windows-Skalierung oder Design?) -- das Fenster wird um " + dB + "/" + dH + " Pixel nachgestellt."));
        Ext.INSTANCE.SetWindowPos(handle, null, links - dB, oben,
                targetWidth + korrekturBreite, targetHeight + korrekturHoehe, SWP_NOZORDER | SWP_NOACTIVATE);
        sleep(500);
        Rectangle neu = clientArea();
        if (neu != null) {
            boolean passt = Math.abs(INNEN_BREITE - neu.width) <= 2 && Math.abs(INNEN_HOEHE - neu.height) <= 2;
            Log.info("Fenster", Sprache.t("Game window inside now " + neu.width + "x" + neu.height
                            + (passt ? " -- fits." : " -- still off. Please set Windows scaling to 100 % (run PC-Check)."),
                    "Spielfenster innen jetzt " + neu.width + "x" + neu.height
                            + (passt ? " -- passt." : " -- weicht noch ab. Bitte Windows-Skalierung auf 100 % stellen (PC-Check).")));
        }
    }

    /**
     * Autor 01.11.: "Check now" soll eine falsche Fenstergroesse selbst richten statt "What to do?" zu zeigen. Stellt das
     * Innenmass ein und dockt oben rechts an wie {@link #prepare()}, holt das Fenster aber nicht nach vorn.
     *
     * @return true, wenn das Innenmass danach passt
     */
    public boolean innenmassRichten() {
        if (handle == null || Ext.INSTANCE.IsIconic(handle)) return false;
        Rectangle innen = clientArea();
        if (innen == null) return false;
        int dB = INNEN_BREITE - innen.width;
        int dH = INNEN_HOEHE - innen.height;
        if (Math.abs(dB) <= 2 && Math.abs(dH) <= 2) return true;
        WinDef.RECT outer = new WinDef.RECT();
        Ext.INSTANCE.GetWindowRect(handle, outer);
        int breite = outer.right - outer.left + dB;
        int hoehe = outer.bottom - outer.top + dH;
        int screenW = Ext.INSTANCE.GetSystemMetrics(SM_CXSCREEN);
        int links = screenW > breite ? screenW - breite + RAND_KOMPENSATION : outer.left;
        int oben = screenW > breite ? 0 : outer.top;
        Ext.INSTANCE.SetWindowPos(handle, null, links, oben, breite, hoehe, SWP_NOZORDER | SWP_NOACTIVATE);
        sleep(500);
        Rectangle neu = clientArea();
        boolean passt = neu != null && Math.abs(INNEN_BREITE - neu.width) <= 2 && Math.abs(INNEN_HOEHE - neu.height) <= 2;
        Log.info("Fenster", Sprache.t("Check now: game window inside was " + innen.width + "x" + innen.height + ", now "
                        + (neu == null ? "?" : neu.width + "x" + neu.height) + (passt ? " -- fits." : " -- still off."),
                "Jetzt pruefen: Spielfenster innen war " + innen.width + "x" + innen.height + ", jetzt "
                        + (neu == null ? "?" : neu.width + "x" + neu.height) + (passt ? " -- passt." : " -- weicht noch ab.")));
        return passt;
    }

    /** Der Fenstergriff (fuer die Bildschirmaufnahme per PrintWindow), oder null. */
    public WinDef.HWND handle() {
        return handle;
    }

    /**
     * Liefert den Inhaltsbereich des Fensters in Bildschirmkoordinaten,
     * also ohne Titelleiste und Rahmen.
     */
    public Rectangle clientArea() {
        if (handle == null) return null;

        WinDef.RECT client = new WinDef.RECT();
        if (!Ext.INSTANCE.GetClientRect(handle, client)) return null;

        WinDef.POINT origin = new WinDef.POINT(0, 0);
        if (!Ext.INSTANCE.ClientToScreen(handle, origin)) return null;

        int width = client.right - client.left;
        int height = client.bottom - client.top;
        if (width <= 0 || height <= 0) return null;

        return new Rectangle(origin.x, origin.y, width, height);
    }

    /**
     * Das ganze Fenster in Bildschirmkoordinaten, mit Rahmen.
     *
     * Gebraucht fuer Fenster, die ihren Rahmen selbst zeichnen -- der
     * MY.GAMES Launcher ist so eines. Bei denen ist "Inhaltsbereich" nicht
     * das, was man auf einem Bildschirmfoto sieht.
     */
    public Rectangle windowRect() {
        if (handle == null) return null;

        WinDef.RECT r = new WinDef.RECT();
        if (!Ext.INSTANCE.GetWindowRect(handle, r)) return null;

        int width = r.right - r.left;
        int height = r.bottom - r.top;
        if (width <= 0 || height <= 0) return null;

        return new Rectangle(r.left, r.top, width, height);
    }

    /**
     * Holt das Fenster nach vorne, OHNE seine Groesse anzufassen.
     *
     * Fuer fremde Fenster wie den MY.GAMES Launcher: Die haben ihre eigene
     * Groesse, und ein Zurechtstutzen wuerde ihren Aufbau durcheinander
     * bringen, waehrend gerade darin geklickt werden soll.
     *
     * Minimierte Fenster werden wiederhergestellt. Das ist wichtig: Der
     * Launcher legt sich nach dem Spielstart in die Leiste, und ein
     * minimiertes Fenster gilt fuer Windows trotzdem als "sichtbar".
     */
    public void bringToFront() {
        if (handle == null) return;

        // ACHTUNG: IsIconic() allein reicht nicht.
        //
        // Der MY.GAMES Launcher meldet "nicht minimiert", parkt sein Fenster
        // aber trotzdem auf -32000/-32000 -- der Stelle, an die Windows
        // minimierte Fenster schiebt. Wer nur IsIconic() fragt, laesst es
        // dort liegen und fotografiert danach 160 mal 28 Bildpunkte Nichts.
        WinDef.RECT r = new WinDef.RECT();
        boolean geparkt = Ext.INSTANCE.GetWindowRect(handle, r) && r.left < -30000;

        if (Ext.INSTANCE.IsIconic(handle) || geparkt) {
            Ext.INSTANCE.ShowWindow(handle, SW_RESTORE);
            sleep(800);
        }

        // SetForegroundWindow schlaegt bei einem Hintergrundprogramm oft
        // still fehl -- Windows laesst nicht zu, dass irgendwer dem Benutzer
        // den Bildschirm wegnimmt. Deshalb wird es nicht als erledigt
        // angesehen, sondern nur versucht.
        Ext.INSTANCE.SetForegroundWindow(handle);
        sleep(400);
    }

    /** Ist dieses Fenster gerade das aktive? */
    public boolean isForeground() {
        return handle != null && handle.equals(Ext.INSTANCE.GetForegroundWindow());
    }

    /** Ist das Fenster gerade in die Leiste gelegt? */
    public boolean isMinimized() {
        return handle != null && Ext.INSTANCE.IsIconic(handle);
    }

    /**
     * Ist das Fenster ueberhaupt zu sehen?
     *
     * Der MY.GAMES Launcher verschwindet, sobald das Spiel laeuft: Das
     * Fenster gibt es weiter, es ist nur ausgeblendet. Wer darauf klickt,
     * klickt auf den Schreibtisch.
     */
    public boolean isVisible() {
        return handle != null && Ext.INSTANCE.IsWindowVisible(handle);
    }

    public boolean isAlive() {
        return handle != null && Ext.INSTANCE.IsWindow(handle);
    }

    /**
     * Steht das Spielfenster gerade ganz vorn und ist es nicht minimiert?
     *
     * WOZU. Der Bot fotografiert immer denselben RECHTECKIGEN AUSSCHNITT des
     * Bildschirms -- er weiss ja nur, wo das Spielfenster liegt, nicht was
     * darueber liegt. Schiebt sich etwas anderes davor, fotografiert er
     * dieses andere. Das ist nicht nur nutzlos, es ist Autors Bildschirm,
     * und der geht den Bot nichts an.
     *
     * Deshalb: Bevor zwischendurch fotografiert wird, wird gefragt, ob das
     * Spiel ueberhaupt zu sehen ist.
     */
    public boolean istImVordergrund() {
        if (!isAlive()) return false;
        if (Ext.INSTANCE.IsIconic(handle)) return false;
        return handle.equals(Ext.INSTANCE.GetForegroundWindow());
    }

    public String title() {
        return windowTitle;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
