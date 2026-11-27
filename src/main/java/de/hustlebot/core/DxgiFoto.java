package de.hustlebot.core;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Locale;

/**
 * Bildschirmfoto per DXGI Desktop Duplication (IDXGIOutputDuplication), Autor 21.11.
 *
 * Gemessen 21.11.: ein Foto per java.awt.Robot oder GDI-BitBlt vom Bildschirm kostet 16-19 ms (PC wie VM) -- das ist im
 * Wesentlichen das Warten auf den naechsten Bildwechsel des Fenster-Managers (DWM). BitBlt vom Fenster-DC ist schnell,
 * liefert aber ein eingefrorenes Bild (das Spiel zeichnet per DirectX).
 *
 * Hier meldet Windows selbst, wann es ein neues Bildschirmbild gibt: {@code AcquireNextFrame} mit Wartezeit 0 sagt
 * entweder "neues Bild" (dann wird es auf der Grafikkarte in eine Staging-Textur kopiert, gelesen und als BGR in einen
 * Puffer des ganzen Monitors geschrieben) oder "nichts Neues" (DXGI_ERROR_WAIT_TIMEOUT) -- dann ist der Puffer noch
 * aktuell und das Foto kommt ohne jedes Warten aus ihm. Ein Foto ist so nie aelter als das letzte vom System gemeldete
 * Bild. Der Mauszeiger ist (wie bei Robot) nicht im Bild.
 *
 * Kopiert wird bei einem neuen Bild nicht der ganze Monitor, sondern nur der "Merkbereich": alles, was in den letzten
 * 30-60 s verlangt wurde (beim Bot: das Spielfenster). Wird danach ein Bereich ausserhalb verlangt und es gab kein
 * neues Bild, fehlt er im Puffer ("Luecke"): dann null ohne Fehler, der Aufrufer nimmt fuer dieses eine Foto die
 * bisherige Art, ab dem naechsten neuen Bild ist der Bereich dabei. Mit {@code ganzerMonitor} wird immer alles kopiert
 * (zum Vergleich in tools.FotoMessung). Ebenso Luecke: bis zum ersten echten Bildwechsel nach dem Anlegen (das erste
 * Bild ohne Bildwechsel ist schwarz, gemessen 21.11.) -- beim ersten Foto wird darauf bis zu 50 ms gewartet.
 *
 * Fehler (kein passender Treiber, Bildschirmwechsel, Auflösung geaendert, UAC-Bildschirm: DXGI_ERROR_ACCESS_LOST /
 * E_ACCESSDENIED): alles wird freigegeben und beim naechsten Foto neu angelegt; das Foto selbst ist null, der Aufrufer
 * faellt zurueck. Nur Windows 64 Bit. Alle oeffentlichen Methoden sind synchronized.
 *
 * Windows erlaubt je Programm nur EIN Duplikat je Monitor: ein zweites DxgiFoto-Objekt bekommt beim Anlegen
 * E_INVALIDARG (0x80070057), solange das erste lebt (gemessen 21.11.). Darum teilen sich alle Steuerungen ein Objekt.
 *
 * <h3>COM-Aufrufe ueber die vtable (Index = Platz in der Funktionstabelle, gezaehlt ab 0, inkl. aller Vorfahren)</h3>
 * <pre>
 * IUnknown                      0 QueryInterface, 1 AddRef, 2 Release
 * IDXGIObject : IUnknown        3 SetPrivateData, 4 SetPrivateDataInterface, 5 GetPrivateData, 6 GetParent
 * IDXGIFactory : IDXGIObject    7 EnumAdapters, 8 MakeWindowAssociation, 9 GetWindowAssociation, 10 CreateSwapChain,
 *                               11 CreateSoftwareAdapter
 * IDXGIFactory1                 12 EnumAdapters1, 13 IsCurrent
 * IDXGIAdapter : IDXGIObject    7 EnumOutputs, 8 GetDesc, 9 CheckInterfaceSupport
 * IDXGIOutput : IDXGIObject     7 GetDesc, 8 GetDisplayModeList, 9 FindClosestMatchingMode, 10 WaitForVBlank,
 *                               11 TakeOwnership, 12 ReleaseOwnership, 13 GetGammaControlCapabilities, 14 SetGammaControl,
 *                               15 GetGammaControl, 16 SetDisplaySurface, 17 GetDisplaySurfaceData, 18 GetFrameStatistics
 * IDXGIOutput1 : IDXGIOutput    19 GetDisplayModeList1, 20 FindClosestMatchingMode1, 21 GetDisplaySurfaceData1,
 *                               22 DuplicateOutput
 * IDXGIOutputDuplication        7 GetDesc, 8 AcquireNextFrame, 9 GetFrameDirtyRects, 10 GetFrameMoveRects,
 *   : IDXGIObject               11 GetFramePointerShape, 12 MapDesktopSurface, 13 UnMapDesktopSurface, 14 ReleaseFrame
 * ID3D11Device : IUnknown       3 CreateBuffer, 4 CreateTexture1D, 5 CreateTexture2D, ...
 * ID3D11DeviceChild : IUnknown  3 GetDevice, 4 GetPrivateData, 5 SetPrivateData, 6 SetPrivateDataInterface
 * ID3D11DeviceContext : Child   7 VSSetConstantBuffers, 8 PSSetShaderResources, 9 PSSetShader, 10 PSSetSamplers,
 *                               11 VSSetShader, 12 DrawIndexed, 13 Draw, 14 Map, 15 Unmap, ... 46 CopySubresourceRegion,
 *                               47 CopyResource
 * ID3D11Resource : Child        7 GetType, 8 SetEvictionPriority, 9 GetEvictionPriority
 * ID3D11Texture2D : Resource    10 GetDesc
 * </pre>
 * Jedes per COM erhaltene Objekt wird genau einmal mit Release (Index 2) freigegeben; dauerhaft gehalten werden nur
 * Geraet, Kontext, Duplikat und Staging-Textur (bis {@link #close()} oder einem Fehler). Ein Bild (AcquireNextFrame)
 * und eine Abbildung (Map) werden nie ueber einen Aufruf hinaus gehalten.
 */
public final class DxgiFoto implements AutoCloseable {

    // ---- HRESULT-Werte ----
    static final int S_OK = 0;
    static final int E_FAIL = 0x80004005;
    static final int DXGI_ERROR_INVALID_CALL = 0x887A0001;
    static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    static final int DXGI_ERROR_UNSUPPORTED = 0x887A0004;
    static final int DXGI_ERROR_ACCESS_LOST = 0x887A0026;
    static final int DXGI_ERROR_WAIT_TIMEOUT = 0x887A0027;

    // ---- vtable-Indizes (siehe Tabelle oben) ----
    private static final int UNKNOWN_QUERY_INTERFACE = 0, UNKNOWN_RELEASE = 2;
    private static final int FACTORY1_ENUM_ADAPTERS1 = 12;
    private static final int ADAPTER_ENUM_OUTPUTS = 7;
    private static final int OUTPUT_GET_DESC = 7;
    private static final int OUTPUT1_DUPLICATE_OUTPUT = 22;
    private static final int DUPLIKAT_ACQUIRE_NEXT_FRAME = 8, DUPLIKAT_RELEASE_FRAME = 14;
    private static final int GERAET_CREATE_TEXTURE2D = 5;
    private static final int KONTEXT_MAP = 14, KONTEXT_UNMAP = 15, KONTEXT_COPY_SUBRESOURCE_REGION = 46;
    private static final int TEXTUR2D_GET_DESC = 10;

    // ---- Konstanten aus dxgi.h / d3d11.h ----
    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0;     // Pflicht, wenn ein Adapter mitgegeben wird
    private static final int D3D11_SDK_VERSION = 7;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87, DXGI_FORMAT_B8G8R8A8_UNORM_SRGB = 91;
    private static final int D3D11_USAGE_STAGING = 3, D3D11_CPU_ACCESS_READ = 0x20000, D3D11_MAP_READ = 1;
    private static final int DXGI_MODE_ROTATION_IDENTITY = 1; // 0 = UNSPECIFIED gilt auch als ungedreht

    private static final Guid.GUID IID_IDXGI_FACTORY1 = new Guid.GUID("{770aae78-f26f-4dba-a829-253c83d1b387}");
    private static final Guid.GUID IID_IDXGI_OUTPUT1 = new Guid.GUID("{00cddea8-939b-4b83-a340-a685226666cc}");
    private static final Guid.GUID IID_ID3D11_TEXTURE2D = new Guid.GUID("{6f15aaf2-d208-4e89-9ab4-489535d34f9c}");

    /** Beim ersten Foto nach dem Anlegen so lange auf den ersten echten Bildwechsel warten. */
    static final int ERSTES_BILD_WARTEN_MS = 50;

    interface DxgiBib extends Library {
        DxgiBib INSTANCE = Native.load("dxgi", DxgiBib.class);

        int CreateDXGIFactory1(Guid.GUID riid, PointerByReference fabrik);
    }

    interface D3d11Bib extends Library {
        D3d11Bib INSTANCE = Native.load("d3d11", D3d11Bib.class);

        int D3D11CreateDevice(Pointer adapter, int treiberArt, Pointer software, int flags, Pointer stufen,
                              int stufenZahl, int sdkVersion, PointerByReference geraet, IntByReference stufe,
                              PointerByReference kontext);
    }

    /** Ergebnis eines Fotos: Bild, oder null mit fehler=false (Luecke, siehe oben) bzw. fehler=true. */
    public record Aufnahme(BufferedImage bild, boolean fehler) { }

    /** Zaehler seit dem Start (fuer FotoMessung und die Minutenzeile). */
    public record Zaehler(long fotos, long neueBilder, long bildwechsel, long ausPuffer, long luecken, long fehler) { }

    private final boolean ganzerMonitor;

    // dauerhaft gehaltene COM-Objekte (null = nicht angelegt)
    private Pointer geraet, kontext, duplikat, staging;
    /** Der Monitor in Bildschirmkoordinaten. */
    private Rectangle monitor;
    /** BGR-Puffer des ganzen Monitors; gueltig ist nur der Teil {@link #gueltig} (Monitorkoordinaten). */
    private byte[] monitorBgr;
    private byte[] zeile;
    private Rectangle gueltig;
    /** Seit dem Anlegen kam ein echter Bildwechsel: Textur und Puffer ({@link #gueltig}) zeigen das aktuelle Bild. */
    private boolean hatBild;
    private boolean ersterAufruf;
    private final Merkbereich merk = new Merkbereich();

    // wiederverwendete Speicherbloecke fuer die Aufrufe
    private final Memory bildInfo = new Memory(48);   // DXGI_OUTDUPL_FRAME_INFO
    private final Memory abbildung = new Memory(16);  // D3D11_MAPPED_SUBRESOURCE
    private final Memory kasten = new Memory(24);     // D3D11_BOX

    private long fotos, neueBilder, bildwechsel, ausPuffer, luecken, fehler;
    private String letzterFehler = "";
    private long minutenStart;
    private Zaehler minutenZaehler;

    public DxgiFoto() {
        this(false);
    }

    /** @param ganzerMonitor bei jedem neuen Bild den ganzen Monitor kopieren statt nur den Merkbereich */
    public DxgiFoto(boolean ganzerMonitor) {
        this.ganzerMonitor = ganzerMonitor;
    }

    /** Ein Foto als TYPE_3BYTE_BGR-Bild, oder null (Fehler oder Luecke -- der Aufrufer nimmt dann eine andere Art). */
    public synchronized BufferedImage foto(Rectangle bildschirmBereich) {
        return aufnehmen(bildschirmBereich).bild();
    }

    /** Wie {@link #foto}, sagt aber zusaetzlich, ob ein null ein Fehler war. */
    public synchronized Aufnahme aufnehmen(Rectangle bildschirmBereich) {
        fotos++;
        if (bildschirmBereich == null || bildschirmBereich.width <= 0 || bildschirmBereich.height <= 0) {
            return fehlschlag("Bereich leer", S_OK);
        }
        try {
            for (int versuch = 0; versuch < 2; versuch++) {
                if (!bereit(bildschirmBereich)) return fehlschlag(null, S_OK);
                Rectangle rel = imMonitor(bildschirmBereich, monitor);
                merk.merken(rel, System.nanoTime() / 1_000_000);
                int hr;
                if (ersterAufruf) {
                    // Erstes Foto nach dem Anlegen: bis zu 50 ms auf den ersten echten Bildwechsel warten (vorher ist
                    // die Textur leer, siehe naechstesBild). Auf ruhigem Bildschirm kommt keiner -- dann Luecke.
                    ersterAufruf = false;
                    long bis = System.nanoTime() + ERSTES_BILD_WARTEN_MS * 1_000_000L;
                    do {
                        hr = naechstesBild((int) Math.max(0, (bis - System.nanoTime()) / 1_000_000), rel);
                    } while (hr >= 0 && !hatBild && System.nanoTime() < bis);
                } else {
                    hr = naechstesBild(0, rel);
                }
                if (hr == DXGI_ERROR_ACCESS_LOST) {
                    // Bildschirmwechsel/UAC/Vollbild: einmal sofort neu anlegen, danach Fehler.
                    letzterFehler = "DXGI_ERROR_ACCESS_LOST";
                    freigeben();
                    continue;
                }
                if (hr == DXGI_ERROR_WAIT_TIMEOUT) {
                    if (hatBild) ausPuffer++;
                } else if (hr < 0) {
                    freigeben();
                    return fehlschlag("AcquireNextFrame/Kopie", hr);
                }
                if (!hatBild || gueltig == null || !gueltig.contains(rel)) {
                    luecken++;
                    return new Aufnahme(null, false);
                }
                BufferedImage bild = new BufferedImage(bildschirmBereich.width, bildschirmBereich.height, BufferedImage.TYPE_3BYTE_BGR);
                byte[] ziel = ((DataBufferByte) bild.getRaster().getDataBuffer()).getData();
                int dx = rel.x - (bildschirmBereich.x - monitor.x), dy = rel.y - (bildschirmBereich.y - monitor.y);
                if (dx == 0 && dy == 0 && rel.width == bildschirmBereich.width && rel.height == bildschirmBereich.height) {
                    ausschneiden(monitorBgr, monitor.width, rel, ziel);
                } else {
                    // Bereich ragt ein paar Punkte ueber den Monitorrand (VM 21.11.: Spielfenster 2 Punkte zu weit rechts):
                    // der Rest bleibt schwarz -- wie bei Robot ausserhalb des Bildschirms.
                    ausschneidenMitRand(monitorBgr, monitor.width, rel, ziel, bildschirmBereich.width, dx, dy);
                }
                return new Aufnahme(bild, false);
            }
            return fehlschlag(null, DXGI_ERROR_ACCESS_LOST);
        } catch (RuntimeException | LinkageError e) {
            // JNA-Fehler oder fehlende DLL (kein Windows/kein Direct3D 11): wie jeder andere Fehler -> Rueckfall
            close();
            return fehlschlag(e.getClass().getSimpleName() + ": " + e.getMessage(), S_OK);
        }
    }

    private Aufnahme fehlschlag(String was, int hr) {
        fehler++;
        if (was != null) letzterFehler = hr == S_OK ? was : was + " (" + hex(hr) + ")";
        return new Aufnahme(null, true);
    }

    static String hex(int hr) {
        return String.format("0x%08X", hr);
    }

    /**
     * Holt ein neues Bild, falls es eins gibt, und kopiert den Merkbereich in den Puffer.
     *
     * @return S_OK (neues Bild verarbeitet), DXGI_ERROR_WAIT_TIMEOUT (nichts Neues) oder ein Fehler
     */
    private int naechstesBild(int warteMs, Rectangle rel) {
        bildInfo.clear();
        PointerByReference ressourceRef = new PointerByReference();
        int hr = rufe(duplikat, DUPLIKAT_ACQUIRE_NEXT_FRAME, warteMs, bildInfo, ressourceRef);
        if (hr < 0) return hr;
        Pointer ressource = ressourceRef.getValue();
        Pointer textur = null;
        try {
            // DXGI_OUTDUPL_FRAME_INFO: LastPresentTime @0 (0 = nur der Mauszeiger hat sich bewegt), AccumulatedFrames @16
            boolean neu = bildInfo.getLong(0) != 0;
            if (neu) {
                neueBilder++;
                bildwechsel += Math.max(1, bildInfo.getInt(16));
            }
            // Gemessen 21.11.: auf ruhigem Bildschirm kommt das erste Bild nach DuplicateOutput mit LastPresentTime 0 und
            // einer SCHWARZEN Textur. Ihr ist erst nach dem ersten echten Bildwechsel zu trauen (der bringt das ganze Bild).
            if (!neu && !hatBild) return S_OK;
            if (!neu && gueltig != null && gueltig.contains(rel)) return S_OK;   // Puffer ist noch aktuell
            if (ressource == null) return E_FAIL;
            textur = abfragen(ressource, IID_ID3D11_TEXTURE2D);
            if (textur == null) {
                letzterFehler = "QueryInterface(ID3D11Texture2D)";
                return E_FAIL;
            }
            if (staging == null && (hr = stagingAnlegen(textur)) < 0) return hr;
            Rectangle box = ganzerMonitor ? new Rectangle(0, 0, monitor.width, monitor.height) : merk.kasten();
            hr = kopieren(textur, box);
            if (hr < 0) return hr;
            gueltig = box;
            hatBild = true;
            return S_OK;
        } finally {
            freigeben(textur);
            freigeben(ressource);
            rufe(duplikat, DUPLIKAT_RELEASE_FRAME);
        }
    }

    /** Staging-Textur (von der CPU lesbar) in Groesse und Format des Desktop-Bildes. */
    private int stagingAnlegen(Pointer textur) {
        // D3D11_TEXTURE2D_DESC (44 Bytes): Width @0, Height @4, MipLevels @8, ArraySize @12, Format @16,
        // SampleDesc.Count @20, SampleDesc.Quality @24, Usage @28, BindFlags @32, CPUAccessFlags @36, MiscFlags @40
        Memory desc = new Memory(44);
        desc.clear();
        rufeOhne(textur, TEXTUR2D_GET_DESC, desc);
        int w = desc.getInt(0), h = desc.getInt(4), format = desc.getInt(16);
        if (format != DXGI_FORMAT_B8G8R8A8_UNORM && format != DXGI_FORMAT_B8G8R8A8_UNORM_SRGB) {
            letzterFehler = "Desktop-Format " + format + " statt BGRA";
            return DXGI_ERROR_UNSUPPORTED;
        }
        if (w != monitor.width || h != monitor.height) {
            // z. B. Programm nicht DPI-bewusst: Bildschirmkoordinaten passen nicht zu den Bildpunkten
            letzterFehler = "Desktop-Bild " + w + "x" + h + " passt nicht zum Monitor " + monitor.width + "x" + monitor.height;
            return DXGI_ERROR_UNSUPPORTED;
        }
        desc.setInt(8, 1);
        desc.setInt(12, 1);
        desc.setInt(20, 1);
        desc.setInt(24, 0);
        desc.setInt(28, D3D11_USAGE_STAGING);
        desc.setInt(32, 0);
        desc.setInt(36, D3D11_CPU_ACCESS_READ);
        desc.setInt(40, 0);
        PointerByReference neu = new PointerByReference();
        int hr = rufe(geraet, GERAET_CREATE_TEXTURE2D, desc, Pointer.NULL, neu);
        if (hr < 0) {
            letzterFehler = "CreateTexture2D (" + hex(hr) + ")";
            return hr;
        }
        staging = neu.getValue();
        return S_OK;
    }

    /** Bereich {@code box} (Monitorkoordinaten) per GPU in die Staging-Textur, dann lesen und als BGR in den Puffer. */
    private int kopieren(Pointer textur, Rectangle box) {
        // D3D11_BOX: left, top, front, right, bottom, back
        kasten.setInt(0, box.x);
        kasten.setInt(4, box.y);
        kasten.setInt(8, 0);
        kasten.setInt(12, box.x + box.width);
        kasten.setInt(16, box.y + box.height);
        kasten.setInt(20, 1);
        rufeOhne(kontext, KONTEXT_COPY_SUBRESOURCE_REGION, staging, 0, box.x, box.y, 0, textur, 0, kasten);
        abbildung.clear();
        int hr = rufe(kontext, KONTEXT_MAP, staging, 0, D3D11_MAP_READ, 0, abbildung);   // wartet, bis die Kopie fertig ist
        if (hr < 0) {
            letzterFehler = "Map (" + hex(hr) + ")";
            return hr;
        }
        try {
            // D3D11_MAPPED_SUBRESOURCE: pData @0, RowPitch @Zeigergroesse
            Pointer daten = abbildung.getPointer(0);
            int zeilenAbstand = abbildung.getInt(Native.POINTER_SIZE);
            if (daten == null || zeilenAbstand < monitor.width * 4) {
                letzterFehler = "Map lieferte keine Daten";
                return E_FAIL;
            }
            int bytes = box.width * 4;
            for (int y = box.y; y < box.y + box.height; y++) {
                daten.read((long) y * zeilenAbstand + (long) box.x * 4, zeile, 0, bytes);
                bgraZuBgr(zeile, 0, monitorBgr, (y * monitor.width + box.x) * 3, box.width);
            }
            return S_OK;
        } finally {
            rufeOhne(kontext, KONTEXT_UNMAP, staging, 0);
        }
    }

    /** Duplikat fuer den Monitor, auf dem der Bereich liegt (anlegen oder behalten). */
    private boolean bereit(Rectangle bereich) {
        if (duplikat != null && monitor != null && imMonitor(bereich, monitor) != null) return true;
        freigeben();
        if (!Platform.isWindows() || !Platform.is64Bit()) {
            letzterFehler = "nur Windows 64 Bit";
            return false;
        }
        boolean ok = false;
        try {
            ok = anlegen(bereich);
            return ok;
        } finally {
            if (!ok) freigeben();
        }
    }

    private boolean anlegen(Rectangle bereich) {
        PointerByReference ref = new PointerByReference();
        int hr = DxgiBib.INSTANCE.CreateDXGIFactory1(IID_IDXGI_FACTORY1, ref);
        if (hr < 0) {
            letzterFehler = "CreateDXGIFactory1 (" + hex(hr) + ")";
            return false;
        }
        Pointer fabrik = ref.getValue(), adapter = null, ausgabe = null, ausgabe1 = null;
        try {
            Rectangle gefunden = null;
            int drehung = 0;
            Memory desc = new Memory(96);   // DXGI_OUTPUT_DESC
            suche:
            for (int a = 0; ; a++) {
                ref = new PointerByReference();
                hr = rufe(fabrik, FACTORY1_ENUM_ADAPTERS1, a, ref);
                if (hr == DXGI_ERROR_NOT_FOUND) break;
                if (hr < 0) {
                    letzterFehler = "EnumAdapters1 (" + hex(hr) + ")";
                    return false;
                }
                Pointer ad = ref.getValue();
                for (int o = 0; ; o++) {
                    ref = new PointerByReference();
                    if (rufe(ad, ADAPTER_ENUM_OUTPUTS, o, ref) < 0) break;   // DXGI_ERROR_NOT_FOUND = keine weiteren
                    Pointer aus = ref.getValue();
                    desc.clear();
                    // DXGI_OUTPUT_DESC: DeviceName WCHAR[32] @0, DesktopCoordinates RECT @64, AttachedToDesktop @80,
                    // Rotation @84, Monitor (HMONITOR) @88
                    if (rufe(aus, OUTPUT_GET_DESC, desc) >= 0 && desc.getInt(80) != 0) {
                        Rectangle r = rechteck(desc.getInt(64), desc.getInt(68), desc.getInt(72), desc.getInt(76));
                        if (imMonitor(bereich, r) != null) {
                            adapter = ad;
                            ausgabe = aus;
                            gefunden = r;
                            drehung = desc.getInt(84);
                            break suche;
                        }
                    }
                    freigeben(aus);
                }
                freigeben(ad);
            }
            if (gefunden == null) {
                letzterFehler = "Bereich " + bereich.x + "," + bereich.y + " " + bereich.width + "x" + bereich.height
                        + " liegt auf keinem Monitor ganz";
                return false;
            }
            if (drehung != 0 && drehung != DXGI_MODE_ROTATION_IDENTITY) {
                letzterFehler = "Monitor ist gedreht (" + drehung + ")";
                return false;
            }
            ausgabe1 = abfragen(ausgabe, IID_IDXGI_OUTPUT1);
            if (ausgabe1 == null) {
                letzterFehler = "IDXGIOutput1 fehlt (Windows 7?)";
                return false;
            }
            PointerByReference g = new PointerByReference(), k = new PointerByReference();
            hr = D3d11Bib.INSTANCE.D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, Pointer.NULL, 0, Pointer.NULL, 0,
                    D3D11_SDK_VERSION, g, new IntByReference(), k);
            if (hr < 0) {
                letzterFehler = "D3D11CreateDevice (" + hex(hr) + ")";
                return false;
            }
            geraet = g.getValue();
            kontext = k.getValue();
            ref = new PointerByReference();
            hr = rufe(ausgabe1, OUTPUT1_DUPLICATE_OUTPUT, geraet, ref);
            if (hr < 0) {
                letzterFehler = "DuplicateOutput (" + hex(hr) + ")";
                return false;
            }
            duplikat = ref.getValue();
            monitor = gefunden;
            monitorBgr = new byte[monitor.width * monitor.height * 3];
            zeile = new byte[monitor.width * 4];
            gueltig = null;
            hatBild = false;
            ersterAufruf = true;
            merk.vergessen();
            return true;
        } finally {
            freigeben(ausgabe1);
            freigeben(ausgabe);
            freigeben(adapter);
            freigeben(fabrik);
        }
    }

    /** Alle gehaltenen Objekte freigeben; beim naechsten Foto wird neu angelegt. */
    private void freigeben() {
        freigeben(staging);
        freigeben(duplikat);
        freigeben(kontext);
        freigeben(geraet);
        staging = duplikat = kontext = geraet = null;
        monitor = null;
        monitorBgr = null;
        zeile = null;
        gueltig = null;
        hatBild = false;
    }

    @Override
    public synchronized void close() {
        try {
            freigeben();
        } catch (RuntimeException | LinkageError e) {
            staging = duplikat = kontext = geraet = null;
            monitor = null;
            monitorBgr = null;
            gueltig = null;
            hatBild = false;
        }
    }

    public synchronized Zaehler zaehler() {
        return new Zaehler(fotos, neueBilder, bildwechsel, ausPuffer, luecken, fehler);
    }

    /** Text des letzten Fehlers (fuer Protokoll und FotoMessung). */
    public synchronized String letzterFehler() {
        return letzterFehler;
    }

    /** Einmal je Minute eine Zeile "Fotos/s, neue Bilder/s ..." (sonst null). */
    public synchronized String minutenZeile(long jetztMs) {
        Zaehler z = zaehler();
        if (minutenZaehler == null) {
            minutenStart = jetztMs;
            minutenZaehler = z;
            return null;
        }
        long dauer = jetztMs - minutenStart;
        if (dauer < 60_000) return null;
        Zaehler a = minutenZaehler;
        minutenStart = jetztMs;
        minutenZaehler = z;
        double s = dauer / 1000.0;
        long f = z.fotos() - a.fotos();
        return String.format(Locale.ROOT, "DXGI: %.1f Fotos/s, %.1f neue Bilder/s vom System (%.1f Bildwechsel/s), "
                        + "%d %% aus dem Puffer, %d Luecken, %d Fehler",
                f / s, (z.neueBilder() - a.neueBilder()) / s, (z.bildwechsel() - a.bildwechsel()) / s,
                f == 0 ? 0 : Math.round(100.0 * (z.ausPuffer() - a.ausPuffer()) / f),
                z.luecken() - a.luecken(), z.fehler() - a.fehler());
    }

    // ---------------------------------------------------------------- reine Rechnungen (ohne Bildschirm pruefbar)

    /** So viele Punkte darf ein Bereich je Seite ueber den Monitor ragen (der Rest des Fotos bleibt schwarz). */
    static final int RAND_ERLAUBT = 16;

    /**
     * Bildschirm-Bereich in Monitorkoordinaten, auf den Monitor zugeschnitten; null, wenn er an einer Seite mehr als
     * {@link #RAND_ERLAUBT} Punkte ueber den Monitor ragt (VM 21.11.: das Spielfenster ragte 2 Punkte rechts hinaus --
     * vorher scheiterte DXGI dann bei jedem Foto).
     */
    static Rectangle imMonitor(Rectangle bereich, Rectangle monitor) {
        if (bereich == null || monitor == null || bereich.width <= 0 || bereich.height <= 0
                || monitor.width <= 0 || monitor.height <= 0) return null;
        int x = bereich.x - monitor.x, y = bereich.y - monitor.y;
        int links = Math.max(0, -x), oben = Math.max(0, -y);
        int rechts = Math.max(0, x + bereich.width - monitor.width), unten = Math.max(0, y + bereich.height - monitor.height);
        if (links > RAND_ERLAUBT || oben > RAND_ERLAUBT || rechts > RAND_ERLAUBT || unten > RAND_ERLAUBT) return null;
        int w = bereich.width - links - rechts, h = bereich.height - oben - unten;
        if (w <= 0 || h <= 0) return null;
        return new Rectangle(x + links, y + oben, w, h);
    }

    /** Wie ausschneiden, aber an Stelle (dx, dy) in ein breiteres Ziel (Zeilenlaenge zielBreite); der Rest bleibt 0. */
    static void ausschneidenMitRand(byte[] monitorBgr, int monitorBreite, Rectangle rel, byte[] ziel, int zielBreite, int dx, int dy) {
        int zeilenBytes = rel.width * 3;
        for (int r = 0; r < rel.height; r++) {
            System.arraycopy(monitorBgr, ((rel.y + r) * monitorBreite + rel.x) * 3, ziel, ((dy + r) * zielBreite + dx) * 3, zeilenBytes);
        }
    }

    /** RECT (links, oben, rechts, unten) als Rectangle. */
    static Rectangle rechteck(int links, int oben, int rechts, int unten) {
        return new Rectangle(links, oben, rechts - links, unten - oben);
    }

    /** {@code pixel} Bildpunkte BGRA (4 Bytes) in BGR (3 Bytes) umschreiben. */
    static void bgraZuBgr(byte[] bgra, int von, byte[] bgr, int nach, int pixel) {
        int q = von, z = nach;
        for (int i = 0; i < pixel; i++) {
            bgr[z] = bgra[q];
            bgr[z + 1] = bgra[q + 1];
            bgr[z + 2] = bgra[q + 2];
            q += 4;
            z += 3;
        }
    }

    /** Den Bereich {@code rel} aus dem BGR-Puffer des Monitors (Breite {@code monitorBreite}) in {@code ziel} kopieren. */
    static void ausschneiden(byte[] monitorBgr, int monitorBreite, Rectangle rel, byte[] ziel) {
        int zeilenBytes = rel.width * 3;
        for (int r = 0; r < rel.height; r++) {
            System.arraycopy(monitorBgr, ((rel.y + r) * monitorBreite + rel.x) * 3, ziel, r * zeilenBytes, zeilenBytes);
        }
    }

    /**
     * Welcher Teil des Monitors bei einem neuen Bild kopiert wird: die Huelle aller Bereiche, die im laufenden und im
     * vorigen 30-s-Abschnitt verlangt wurden. So waechst er nicht fuer immer, wenn einmal ein grosser Bereich kam.
     */
    static final class Merkbereich {
        static final long ABSCHNITT_MS = 30_000;
        private Rectangle jetzt, vorher;
        private long start;
        private boolean leer = true;

        /** Bereich (Monitorkoordinaten) merken; liefert den Kopierbereich. */
        Rectangle merken(Rectangle rel, long ms) {
            if (leer) {
                start = ms;
                leer = false;
            } else if (ms - start >= ABSCHNITT_MS) {
                vorher = jetzt;
                jetzt = null;
                start = ms;
            }
            jetzt = jetzt == null ? new Rectangle(rel) : jetzt.union(rel);
            return kasten();
        }

        Rectangle kasten() {
            if (jetzt == null) return vorher == null ? null : new Rectangle(vorher);
            return vorher == null ? new Rectangle(jetzt) : jetzt.union(vorher);
        }

        void vergessen() {
            jetzt = vorher = null;
            leer = true;
        }
    }

    // ---------------------------------------------------------------- COM ueber JNA

    private static Function funktion(Pointer objekt, int index) {
        Pointer vtabelle = objekt.getPointer(0);
        return Function.getFunction(vtabelle.getPointer((long) index * Native.POINTER_SIZE));
    }

    private static Object[] mitThis(Pointer objekt, Object[] args) {
        Object[] alle = new Object[args.length + 1];
        alle[0] = objekt;
        System.arraycopy(args, 0, alle, 1, args.length);
        return alle;
    }

    /** COM-Methode mit HRESULT-Rueckgabe. */
    private static int rufe(Pointer objekt, int index, Object... args) {
        return funktion(objekt, index).invokeInt(mitThis(objekt, args));
    }

    /** COM-Methode ohne Rueckgabe (void). */
    private static void rufeOhne(Pointer objekt, int index, Object... args) {
        funktion(objekt, index).invokeVoid(mitThis(objekt, args));
    }

    /** QueryInterface; null, wenn das Objekt die Schnittstelle nicht kann. */
    private static Pointer abfragen(Pointer objekt, Guid.GUID iid) {
        PointerByReference ref = new PointerByReference();
        int hr = rufe(objekt, UNKNOWN_QUERY_INTERFACE, iid, ref);
        return hr < 0 ? null : ref.getValue();
    }

    private static void freigeben(Pointer objekt) {
        if (objekt != null) funktion(objekt, UNKNOWN_RELEASE).invokeInt(new Object[]{objekt});
    }
}
