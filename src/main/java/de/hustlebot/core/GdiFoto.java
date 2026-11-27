package de.hustlebot.core;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Bildschirmfoto direkt ueber GDI (Sparsam 20.11., VM-Messung: ein Bot-Foto dauerte in der VirtualBox-VM im Median 72 ms
 * statt 16 ms auf Autors PC, die VM-CPU lag bei 85-87 %).
 *
 * Was java.awt.Robot je Foto tut: BitBlt vom Bildschirm MIT "CAPTUREBLT" (bezieht ueberlagerte Fenster ein -- unter
 * DWM/VMs bekannt langsam), dann alle Bildpunkte in ein frisches int-Feld (3,5 MB je Foto bei 1159x770), dann zeichnet
 * ScreenCapture.toMat das Bild noch einmal in BGR-Bytes um. Hier:
 *  - BitBlt wahlweise ohne CAPTUREBLT (das Spielfenster ist kein ueberlagertes Fenster),
 *  - in einen dauerhaften 24-Bit-Puffer (DIB-Section), der schon BGR ist -- kein Umzeichnen, kein neuer Speicher je Foto,
 *  - das Ergebnis ist ein TYPE_3BYTE_BGR-Bild: ScreenCapture.toMat uebernimmt es ohne Umweg.
 * Wahlweise auch vom Fenster selbst (Fenster-DC oder PrintWindow) -- was in einer VM am schnellsten ist, misst
 * {@code tools.FotoMessung}.
 *
 * Nicht threadsicher von aussen gedacht: alle Methoden sind synchronized (Bot-Faden und Discord teilen sich ein Objekt).
 */
public final class GdiFoto implements AutoCloseable {

    /** GdiFlush fehlt in JNAs GDI32. */
    interface Gdi extends GDI32 {
        Gdi INSTANCE = Native.load("gdi32", Gdi.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean GdiFlush();
    }

    /** BitBlt: ueberlagerte (layered) Fenster mitnehmen -- das tut java.awt.Robot. */
    public static final int CAPTUREBLT = 0x40000000;
    /** PrintWindow: nur der Inhaltsbereich, und auch mit DirectX gezeichnete Inhalte (ab Windows 8.1). */
    public static final int PW_CLIENTONLY = 1, PW_RENDERFULLCONTENT = 2;

    public enum Quelle {
        /** Bildschirm, wie Robot (mit CAPTUREBLT). */
        BILDSCHIRM_CAPTUREBLT,
        /** Bildschirm ohne CAPTUREBLT. */
        BILDSCHIRM,
        /** Vom Fenster-DC des Spiels (Inhaltsbereich), Koordinaten relativ zum Inhaltsbereich. */
        FENSTER_DC,
        /** PrintWindow (Inhaltsbereich), auch wenn das Fenster verdeckt ist. */
        PRINTWINDOW
    }

    private WinDef.HDC speicherDc;
    private WinDef.HBITMAP bitmap;
    private WinNT.HANDLE altesObjekt;
    private Pointer bits;
    private int breite, hoehe, zeilenBytes;

    /** Puffer fuer genau diese Groesse anlegen (oder behalten). */
    private boolean puffer(WinDef.HDC vorlageDc, int w, int h) {
        if (bitmap != null && w == breite && h == hoehe) return true;
        freigeben();
        speicherDc = GDI32.INSTANCE.CreateCompatibleDC(vorlageDc);
        if (speicherDc == null) return false;
        WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
        info.bmiHeader.biWidth = w;
        info.bmiHeader.biHeight = -h;            // negativ: Zeilen von oben nach unten (wie OpenCV)
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 24;          // B, G, R -- genau die Reihenfolge von OpenCV
        info.bmiHeader.biCompression = WinGDI.BI_RGB;
        PointerByReference ziel = new PointerByReference();
        bitmap = GDI32.INSTANCE.CreateDIBSection(speicherDc, info, WinGDI.DIB_RGB_COLORS, ziel, null, 0);
        if (bitmap == null || ziel.getValue() == null) {
            freigeben();
            return false;
        }
        altesObjekt = GDI32.INSTANCE.SelectObject(speicherDc, bitmap);
        bits = ziel.getValue();
        breite = w;
        hoehe = h;
        zeilenBytes = ((w * 3) + 3) & ~3;        // DIB-Zeilen sind auf 4 Bytes aufgerundet
        return true;
    }

    /**
     * Ein Foto als TYPE_3BYTE_BGR-Bild, oder null bei einem Fehler.
     *
     * @param bereich Bildschirmkoordinaten (BILDSCHIRM*), sonst relativ zum Inhaltsbereich des Fensters
     * @param fenster das Spielfenster (nur fuer FENSTER_DC / PRINTWINDOW)
     */
    public synchronized BufferedImage foto(Rectangle bereich, Quelle quelle, WinDef.HWND fenster) {
        BufferedImage bild = new BufferedImage(bereich.width, bereich.height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] ziel = ((DataBufferByte) bild.getRaster().getDataBuffer()).getData();
        return fotoIn(bereich, quelle, fenster, ziel) ? bild : null;
    }

    /** Wie foto(), aber in ein vorhandenes BGR-Feld (Laenge breite*hoehe*3). */
    public synchronized boolean fotoIn(Rectangle bereich, Quelle quelle, WinDef.HWND fenster, byte[] ziel) {
        int w = bereich.width, h = bereich.height;
        if (w <= 0 || h <= 0 || ziel.length < w * h * 3) return false;
        boolean vomFenster = quelle == Quelle.FENSTER_DC || quelle == Quelle.PRINTWINDOW;
        if (vomFenster && fenster == null) return false;
        WinDef.HWND dcVon = vomFenster ? fenster : null;
        WinDef.HDC quellDc = User32.INSTANCE.GetDC(dcVon);
        if (quellDc == null) return false;
        try {
            if (!puffer(quellDc, w, h)) return false;
            boolean ok;
            if (quelle == Quelle.PRINTWINDOW) {
                // PrintWindow zeichnet immer ab der linken oberen Ecke des Inhaltsbereichs -- fuer einen Ausschnitt
                // muesste der Puffer so gross wie das Fenster sein; gemessen wird hier nur das ganze Fenster.
                ok = User32.INSTANCE.PrintWindow(fenster, speicherDc, PW_CLIENTONLY | PW_RENDERFULLCONTENT);
            } else {
                int rop = GDI32.SRCCOPY | (quelle == Quelle.BILDSCHIRM_CAPTUREBLT ? CAPTUREBLT : 0);
                ok = GDI32.INSTANCE.BitBlt(speicherDc, 0, 0, w, h, quellDc, bereich.x, bereich.y, rop);
            }
            if (!ok) return false;
            Gdi.INSTANCE.GdiFlush();
            int zeile = w * 3;
            if (zeilenBytes == zeile) {
                bits.read(0, ziel, 0, zeile * h);
            } else {
                for (int y = 0; y < h; y++) bits.read((long) y * zeilenBytes, ziel, y * zeile, zeile);
            }
            return true;
        } finally {
            User32.INSTANCE.ReleaseDC(dcVon, quellDc);
        }
    }

    private void freigeben() {
        if (speicherDc != null && altesObjekt != null) GDI32.INSTANCE.SelectObject(speicherDc, altesObjekt);
        if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
        if (speicherDc != null) GDI32.INSTANCE.DeleteDC(speicherDc);
        speicherDc = null;
        bitmap = null;
        altesObjekt = null;
        bits = null;
        breite = hoehe = 0;
    }

    @Override
    public synchronized void close() {
        freigeben();
    }
}
