package de.hustlebot.core;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;

/**
 * Holt den Inhalt EINES Fensters -- nur Hustle Castle, auch wenn ein anderes
 * Fenster darueber liegt.
 *
 * Autor, 26.09.: Die Bildschirmaufnahme soll "nur Hustle-Castle-Gameplay
 * zeigen, nicht das andere, was auf meinem Homescreen noch rumliegt". Ein
 * Bildschirmfoto des Spielbereichs zeigt alles, was dort gerade liegt --
 * etwa Discord, wenn es darueber geschoben wird. PrintWindow mit
 * PW_RENDERFULLCONTENT laesst dagegen das Fenster selbst zeichnen.
 *
 * Nur fuer die Aufnahme; der Bot selbst sieht weiter ueber ScreenCapture.
 *
 * EIN PUFFER, NICHT EINER JE BILD (Code-Pruefung 26.09.): Bei 20 Bildern je
 * Sekunde und mehreren MB je Bild waeren das ueber 100 MB je Sekunde, die
 * erst der Garbage Collector wieder einsammelt -- Arbeitsspeicher schwankt
 * um Hunderte MB, dazu GC-Pausen im Turbo-Takt. Deshalb ist das hier ein
 * Objekt je Aufnahme, das seinen nativen Puffer und sein Byte-Feld behaelt
 * und am Ende mit close() freigibt.
 */
public final class FensterFoto implements AutoCloseable {

    /** PW_CLIENTONLY (1) | PW_RENDERFULLCONTENT (2): nur der Inhalt, auch bei DirectX. */
    private static final int FLAGS = 3;

    private Memory puffer;
    private byte[] bytes;

    /**
     * Der Inhaltsbereich des Fensters als BGRA-Bytes (4 je Punkt, Zeile fuer
     * Zeile von oben), oder null, wenn es nicht geht (Fenster weg/minimiert).
     *
     * Das gelieferte Feld gehoert diesem Objekt und wird beim naechsten
     * Aufruf ueberschrieben -- wer es laenger braucht, kopiert es. Schlaegt
     * der Aufruf fehl, bleibt der Inhalt vom letzten Erfolg unangetastet.
     *
     * @param breite, hoehe Groesse des Inhaltsbereichs (aus GetClientRect)
     */
    public byte[] bgra(WinDef.HWND fenster, int breite, int hoehe) {
        if (fenster == null || breite <= 0 || hoehe <= 0) return null;
        long groesse = (long) breite * hoehe * 4;
        if (puffer == null || puffer.size() < groesse) {
            if (puffer != null) puffer.close();
            puffer = new Memory(groesse);
            bytes = new byte[(int) groesse];
        }
        WinDef.HDC fensterDc = User32.INSTANCE.GetDC(fenster);
        if (fensterDc == null) return null;
        WinDef.HDC speicherDc = GDI32.INSTANCE.CreateCompatibleDC(fensterDc);
        WinDef.HBITMAP bild = GDI32.INSTANCE.CreateCompatibleBitmap(fensterDc, breite, hoehe);
        WinNT.HANDLE alt = GDI32.INSTANCE.SelectObject(speicherDc, bild);
        try {
            if (!User32.INSTANCE.PrintWindow(fenster, speicherDc, FLAGS)) return null;
            WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
            info.bmiHeader.biWidth = breite;
            info.bmiHeader.biHeight = -hoehe;   // negativ: Zeilen von oben nach unten
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = WinGDI.BI_RGB;
            int zeilen = GDI32.INSTANCE.GetDIBits(speicherDc, bild, 0, hoehe, puffer, info,
                    WinGDI.DIB_RGB_COLORS);
            if (zeilen != hoehe) return null;
            // Erst nach dem Erfolg ins Byte-Feld -- so bleibt bei einem
            // Fehlschlag das letzte gute Bild darin stehen.
            puffer.read(0, bytes, 0, (int) groesse);
            return bytes;
        } finally {
            GDI32.INSTANCE.SelectObject(speicherDc, alt);
            GDI32.INSTANCE.DeleteObject(bild);
            GDI32.INSTANCE.DeleteDC(speicherDc);
            User32.INSTANCE.ReleaseDC(fenster, fensterDc);
        }
    }

    /** Gibt den nativen Puffer sofort frei (statt erst beim GC). */
    @Override
    public void close() {
        if (puffer != null) {
            puffer.close();
            puffer = null;
        }
        bytes = null;
    }
}
