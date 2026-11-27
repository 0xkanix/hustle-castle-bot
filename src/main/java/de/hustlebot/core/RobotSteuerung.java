package de.hustlebot.core;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

/** Die bisherige PC-Steuerung: java.awt.Robot und die echte Maus. Verhalten unveraendert. */
public final class RobotSteuerung implements Steuerung {

    private final Robot robot;

    public RobotSteuerung(Robot robot) {
        this.robot = robot;
    }

    @Override public void bewegen(int x, int y) { robot.mouseMove(x, y); }
    @Override public void druecken() { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); }
    @Override public void loslassen() { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
    @Override public Point zeiger() { return MouseInfo.getPointerInfo().getLocation(); }
    @Override public BufferedImage foto(Rectangle bereich) {
        if (dxgiAn()) {
            BufferedImage b = dxgiFoto(bereich);
            if (b != null) return b;
        }
        if (fensterAn() && System.currentTimeMillis() >= fensterPauseBis) {
            BufferedImage b = fensterFoto(bereich);
            if (b != null) return b;
        }
        if (gdiAn()) {
            BufferedImage b = gdi.foto(bereich, GdiFoto.Quelle.BILDSCHIRM, null);
            if (b != null) return b;
        }
        return robot.createScreenCapture(bereich);
    }

    // Autor 21.11. (jedes Foto per Robot/GDI kostet 16-19 ms, auf dem PC wie in der VM -- Warten auf den naechsten
    // Bildwechsel des Fenster-Managers): mit foto-dxgi.txt "an" kommen die Fotos per DXGI Desktop Duplication
    // (DxgiFoto). Windows meldet dort selbst, ob es seit dem letzten Foto ein neues Bild gibt; wenn nicht, kommt das Foto
    // sofort aus dem zuletzt kopierten Bild (nie aelter als das letzte vom System gemeldete). Fehlt ein Bereich im Puffer
    // ("Luecke") oder klappt DXGI nicht, gilt fuer dieses Foto die bisherige Reihenfolge (Fenster -> GDI -> Robot). Klappt
    // DXGI 3x hintereinander nicht (oder liefert schwarz), eine Minute wie bisher, dann neuer Versuch. Datei hoechstens
    // alle 5 s gelesen; ohne Datei oder "aus": alles wie bisher.
    // Autor 21.11. abends: in einer VM ist DXGI ohne Datei von selbst an (Konrad spielt in einer VM); "aus" schaltet ab.
    private static final Schalterdatei DXGI_SCHALTER = new Schalterdatei(Ordner.bei("foto-dxgi.txt"), "Foto",
            VirtuelleMaschine.erkannt());
    // Windows erlaubt je Programm nur EIN Duplikat je Monitor (ein zweites bekommt E_INVALIDARG, FotoMessung 21.11.) --
    // darum ein gemeinsames Objekt fuer alle Steuerungen dieses Programms (es ist synchronized).
    private static final DxgiFoto DXGI = new DxgiFoto();
    private final Aussetzer dxgiAussetzer = new Aussetzer(3, 60_000);
    private volatile boolean dxgiAn;
    private volatile long dxgiGelesen;
    private volatile String dxgiLetzteMeldung = "";

    private boolean dxgiAn() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - dxgiGelesen > 5000) {
            dxgiGelesen = jetzt;
            boolean neu = DXGI_SCHALTER.lesen();
            if (neu != dxgiAn) {
                Log.info("Foto", Sprache.t("Photos via DXGI desktop duplication: " + (neu ? "on" : "off") + ".",
                        "Fotos per DXGI-Bildschirmkopie: " + (neu ? "an" : "aus") + "."));
                if (!neu) dxgiSchliessen();   // nichts mehr halten, wenn aus
            }
            dxgiAn = neu;
        }
        return dxgiAn;
    }

    // VM 21.11.: ab 17:38 stand die Hauptschleife fuer immer in einem DXGI-Foto, ohne jede Protokollzeile. VirtualBox'
    // Bildschirmaufnahme hatte auf dem PC den Faden der virtuellen Grafikkarte abstuerzen lassen (VBox.log: Zugriffs-
    // verletzung in "VMSVGA FIFO"); Kopie + Map warten auf die Grafikkarte und kamen nie zurueck. Darum laeuft jedes
    // DXGI-Foto (und alles andere am gemeinsamen, gesperrten DXGI-Objekt) auf einem eigenen Faden mit Frist: kommt es nicht
    // binnen DXGI_FRIST_MS zurueck, gilt fuer dieses Foto die bisherige Art; solange der Aufruf haengt, wird DXGI gar nicht
    // mehr angefasst (eine Zeile ins Protokoll). Kommt er doch noch zurueck, geht es mit DXGI weiter.
    static final long DXGI_FRIST_MS = 5000;
    private static final Fristfaden DXGI_FADEN = new Fristfaden("dxgi-foto", DXGI_FRIST_MS);

    /** Alles, was ein DXGI-Foto ergibt -- gleich auf dem DXGI-Faden geholt, damit der Aufrufer das Objekt nie selbst sperrt. */
    private record DxgiErgebnis(BufferedImage bild, boolean fehler, String grund, String minutenZeile) { }

    private static void dxgiSchliessen() {
        DXGI_FADEN.rufe(() -> {
            DXGI.close();
            return Boolean.TRUE;
        });
    }

    private BufferedImage dxgiFoto(Rectangle bereich) {
        long jetzt = System.currentTimeMillis();
        if (!dxgiAussetzer.darf(jetzt)) return null;
        DxgiErgebnis e = DXGI_FADEN.rufe(() -> {
            DxgiFoto.Aufnahme a = DXGI.aufnehmen(bereich);
            return new DxgiErgebnis(a.bild(), a.fehler(), a.fehler() ? DXGI.letzterFehler() : "",
                    a.bild() != null ? DXGI.minutenZeile(jetzt) : null);
        });
        if (e == null) {
            if (DXGI_FADEN.haengerMelden()) {
                Log.warn("Foto", Sprache.t("A DXGI photo has not come back for " + (DXGI_FRIST_MS / 1000)
                                + " s -- the graphics driver is stuck (in a VM: the virtual graphics card). Photos are taken "
                                + "the old way until it answers again. If the game picture stays frozen: restart the PC/VM.",
                        "Ein DXGI-Foto kommt seit " + (DXGI_FRIST_MS / 1000) + " s nicht zurueck -- der Grafiktreiber haengt "
                                + "(in einer VM: die virtuelle Grafikkarte). Fotos wie bisher, bis er wieder antwortet. Bleibt "
                                + "das Spielbild eingefroren: PC/VM neu starten."));
            }
            return null;
        }
        if (DXGI_FADEN.freiMelden()) {
            Log.info("Foto", Sprache.t("DXGI photos answer again.", "DXGI-Fotos antworten wieder."));
        }
        BufferedImage b = e.bild();
        boolean fehler = e.fehler();
        String grund = e.grund() == null ? "" : e.grund();
        if (b != null && schwarz(((DataBufferByte) b.getRaster().getDataBuffer()).getData())) {
            b = null;          // schwarz: Treiber liefert nichts Brauchbares (oder Ladebild) -- wie ein Fehler
            fehler = true;
        }
        if (b != null) {
            dxgiAussetzer.erfolg();
            if (e.minutenZeile() != null) Log.debug("Foto", e.minutenZeile());
            return b;
        }
        if (fehler && dxgiAussetzer.fehler(jetzt)) {
            String text = "DXGI-Fotos 3x hintereinander fehlgeschlagen (" + (grund.isEmpty() ? "schwarzes Bild" : grund)
                    + ") -- eine Minute wie bisher.";
            // gleicher Grund wie beim letzten Mal: nur ins Debug-Protokoll (sonst jede Minute eine Zeile)
            if (text.equals(dxgiLetzteMeldung)) Log.debug("Foto", text);
            else Log.info("Foto", Sprache.t("DXGI photos failed 3 times in a row (" + (grund.isEmpty() ? "black image" : grund)
                    + ") -- one minute as before.", text));
            dxgiLetzteMeldung = text;
            dxgiSchliessen();  // nach der Pause frisch anlegen
        }
        return null;
    }

    /** Pausen-Regel einer Foto-Art: nach {@code grenze} Fehlern hintereinander {@code pauseMs} lang nicht benutzen. */
    static final class Aussetzer {
        private final int grenze;
        private final long pauseMs;
        private int fehlerInFolge;
        private long pauseBis = Long.MIN_VALUE;

        Aussetzer(int grenze, long pauseMs) {
            this.grenze = grenze;
            this.pauseMs = pauseMs;
        }

        synchronized boolean darf(long jetzt) {
            return jetzt >= pauseBis;
        }

        synchronized void erfolg() {
            fehlerInFolge = 0;
        }

        /** Einen Fehler zaehlen; true, wenn damit die Pause beginnt. */
        synchronized boolean fehler(long jetzt) {
            if (++fehlerInFolge < grenze) return false;
            fehlerInFolge = 0;
            pauseBis = jetzt + pauseMs;
            return true;
        }
    }

    // Sparsam 20.11. (VM: ein Foto 72 ms statt 16 ms): mit foto-gdi.txt "an" kommt das Foto per GDI ohne CAPTUREBLT in
    // einen BGR-Puffer (GdiFoto) -- kein Umzeichnen mehr in ScreenCapture.toMat. Ohne Datei wie bisher java.awt.Robot.
    // Welche Art in der VM am schnellsten ist, misst tools.FotoMessung. Die Datei wird hoechstens alle 5 s gelesen.
    private static final Schalterdatei GDI_SCHALTER = new Schalterdatei(Ordner.bei("foto-gdi.txt"), "Foto", false);
    private final GdiFoto gdi = new GdiFoto();
    private volatile boolean gdiAn;
    private volatile long gdiGelesen;

    private boolean gdiAn() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - gdiGelesen > 5000) {
            gdiGelesen = jetzt;
            gdiAn = GDI_SCHALTER.lesen();
        }
        return gdiAn;
    }

    // Autor 21.11. (VM soll so schnell sein wie der PC): FotoMessung in der HustleVM -- Robot 16,9 ms, GDI vom Bildschirm
    // 15,9 ms, GDI vom Fenster-DC des Spiels 1,2 ms bei gleichem Bild (Abweichung 0,7). Mit foto-fenster.txt "an" kommt das
    // Foto darum per BitBlt direkt vom Inhaltsbereich des Spielfensters (ohne den Umweg ueber das fertig gemalte
    // Bildschirmbild). Liegt der Bereich nicht ganz im Spielfenster, ist es minimiert/weg oder kommt ein schwarzes Bild,
    // gilt wie bisher GDI/Robot (schwarz: eine Minute Pause, dann neuer Versuch). Nebenwirkung: Fenster ueber dem Spiel
    // (Claude, Overlays) sind auf diesen Fotos nicht zu sehen.
    private static final Schalterdatei FENSTER_SCHALTER = new Schalterdatei(Ordner.bei("foto-fenster.txt"), "Foto", false);
    private final GdiFoto fensterGdi = new GdiFoto();
    private volatile boolean fensterAn;
    private volatile long fensterGelesen, fensterPauseBis;
    private WinDef.HWND spiel;
    private long spielGesucht;

    private boolean fensterAn() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - fensterGelesen > 5000) {
            fensterGelesen = jetzt;
            boolean neu = FENSTER_SCHALTER.lesen();
            if (neu != fensterAn) Log.info("Foto", Sprache.t("Photos from the game window directly: " + (neu ? "on" : "off") + ".",
                    "Fotos direkt vom Spielfenster: " + (neu ? "an" : "aus") + "."));
            fensterAn = neu;
        }
        return fensterAn;
    }

    private synchronized BufferedImage fensterFoto(Rectangle bereich) {
        WinDef.HWND h = spielfenster();
        if (h == null) return null;
        GameWindow.Ext u = GameWindow.Ext.INSTANCE;
        if (u.IsIconic(h)) return null;
        WinDef.POINT ursprung = new WinDef.POINT(0, 0);
        WinDef.RECT innen = new WinDef.RECT();
        if (!u.ClientToScreen(h, ursprung) || !u.GetClientRect(h, innen)) {
            spiel = null;
            return null;
        }
        Rectangle rel = imFenster(bereich, ursprung.x, ursprung.y, innen.right - innen.left, innen.bottom - innen.top);
        if (rel == null) return null;
        BufferedImage b = fensterGdi.foto(rel, GdiFoto.Quelle.FENSTER_DC, h);
        if (b == null) return null;
        if (schwarz(((DataBufferByte) b.getRaster().getDataBuffer()).getData())) {
            fensterPauseBis = System.currentTimeMillis() + 60_000;
            Log.debug("Foto", "Fenster-Foto schwarz -- eine Minute wie bisher fotografieren.");
            return null;
        }
        return b;
    }

    /** Das Spielfenster (genauer Titel), hoechstens alle 5 s neu gesucht. */
    private WinDef.HWND spielfenster() {
        long jetzt = System.currentTimeMillis();
        if (spiel != null && User32.INSTANCE.IsWindow(spiel)) return spiel;
        if (jetzt - spielGesucht < 5000) return null;
        spielGesucht = jetzt;
        spiel = User32.INSTANCE.FindWindow(null, "Hustle Castle");
        return spiel;
    }

    /**
     * Bildschirm-Bereich in Koordinaten des Inhaltsbereichs umrechnen; null, wenn er nicht ganz im Inhaltsbereich liegt.
     */
    static Rectangle imFenster(Rectangle bereich, int ursprungX, int ursprungY, int breite, int hoehe) {
        if (bereich == null || bereich.width <= 0 || bereich.height <= 0 || breite <= 0 || hoehe <= 0) return null;
        int x = bereich.x - ursprungX, y = bereich.y - ursprungY;
        if (x < 0 || y < 0 || x + bereich.width > breite || y + bereich.height > hoehe) return null;
        return new Rectangle(x, y, bereich.width, bereich.height);
    }

    /** Schwarzes (leeres) Bild? Stichprobe von bis zu 256 Stellen, alle Farbwerte unter 4. */
    static boolean schwarz(byte[] bgr) {
        if (bgr == null || bgr.length == 0) return true;
        int schritt = Math.max(1, bgr.length / 256);
        for (int i = 0; i < bgr.length; i += schritt) if ((bgr[i] & 0xFF) >= 4) return false;
        return true;
    }

    @Override public boolean istPcMaus() { return true; }
}
