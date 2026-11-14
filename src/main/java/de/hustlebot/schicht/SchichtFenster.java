package de.hustlebot.schicht;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Eine durchsichtige Schicht ueber dem Spiel, auf der duenne farbige Rahmen
 * liegen (zum Beispiel die Ampel: sicher / machbar / meiden / niemals).
 *
 * DREI DINGE MUESSEN STIMMEN, sonst stoert die Schicht mehr, als sie hilft:
 *
 *   1. Klicks gehen hindurch, und sie wird nie das aktive Fenster
 *      (WS_EX_TRANSPARENT, WS_EX_NOACTIVATE). Autor und der Bot klicken
 *      weiter direkt ins Spiel.
 *
 *   2. Sie taucht in keinem Bildschirmfoto auf. Der Bot sieht ueber
 *      ScreenCapture genau den Bildschirmbereich des Spiels -- laege die
 *      Schicht darin, faende die Bilderkennung ploetzlich gruene Rahmen statt
 *      Knoepfe. Dafuer SetWindowDisplayAffinity mit WDA_EXCLUDEFROMCAPTURE
 *      (ab Windows 10 Version 2004). Ob das geklappt hat, sagt
 *      vorAufnahmeVersteckt().
 *
 *   3. Sie zeichnet nur Linien und kleine Schilder, keine Flaechen.
 *
 * Alle oeffentlichen Methoden duerfen von jedem Faden aus gerufen werden; die
 * eigentliche Arbeit laeuft immer auf dem Swing-Faden (EDT).
 *
 * KOORDINATEN. zeigen() bekommt den Bildschirmbereich in echten
 * Bildschirmpunkten (so wie GameWindow.clientArea() ihn liefert), die Rahmen
 * relativ dazu. Der Bot setzt sun.java2d.uiScale=1, dann ist das 1:1. Bei
 * einer anderen Windows-Skalierung wird hier umgerechnet.
 */
public final class SchichtFenster {

    /**
     * Ein Rahmen. bereich ist relativ zum abgedeckten Bereich (Spiel-Innenbereich), text darf null sein.
     * auswahl: der per Taste gewaehlte Gegner -- etwas kraeftiger gezeichnet (Stufe 2, Autor 28.10.).
     */
    public record Rahmen(Rectangle bereich, Color farbe, String text, boolean auswahl, boolean ziel) {
        public Rahmen {
            Objects.requireNonNull(bereich, "bereich");
            Objects.requireNonNull(farbe, "farbe");
            bereich = new Rectangle(bereich);   // Rectangle ist veraenderlich -- eigene Kopie
        }

        public Rahmen(Rectangle bereich, Color farbe, String text, boolean auswahl) {
            this(bereich, farbe, text, auswahl, false);
        }

        public Rahmen(Rectangle bereich, Color farbe, String text) {
            this(bereich, farbe, text, false);
        }

        public Rahmen(Rectangle bereich, Color farbe) {
            this(bereich, farbe, null, false);
        }

        @Override
        public Rectangle bereich() {
            return new Rectangle(bereich);
        }
    }

    // ---- Ampel ------------------------------------------------------------
    // Deckend angegeben; der Rahmen selbst wird mit RAHMEN_ALPHA gezeichnet.
    // Kraeftig und weit auseinander, damit sie auf dem blau-grauen Spielbild
    // gut zu lesen und sicher zu unterscheiden sind.

    /** Gruen: sicher. */
    public static final Color SICHER = new Color(64, 214, 112);
    /** Gelb: machbar. Autor 28.10.: warmes Sonnenblumen-Gold statt Zitronengelb. */
    public static final Color MACHBAR = new Color(255, 200, 61);
    /** Hellgruen: fast sicher (Autor 31.10.). */
    public static final Color FAST_SICHER = new Color(178, 226, 78);
    /** Violett: Pause, vorerst nicht angreifen (Autor 31.10.). */
    public static final Color PAUSE = new Color(176, 120, 240);
    /** Orange: lieber meiden. */
    public static final Color MEIDEN = new Color(255, 140, 26);
    /** Rot: niemals. */
    public static final Color NIEMALS = new Color(240, 62, 62);
    /** Grau, halb durchsichtig: Gegner, den der Bot noch nicht einstuft (Autor 28.10.: sichtbar, aber nicht nervig). */
    public static final Color UNBEKANNT = new Color(205, 210, 220, 85);

    /** Hell: der gewaehlte Gegner, wenn er auf keiner Liste steht (grau waere zu blass). */
    public static final Color AUSWAHL = new Color(235, 240, 255);

    /** Deckkraft der Rahmenlinien, wenn die Farbe selbst keine mitbringt. */
    public static final int RAHMEN_ALPHA = 225;   // Autor 29.10. abends: "ein bisschen besser sehen" (vorher 150)
    /** Deckkraft des gewaehlten Rahmens -- kraeftiger als die anderen, aber kein Block. */
    static final int AUSWAHL_ALPHA = 230;

    private static final float STAERKE = 3.5f;          // vorher 2
    private static final float AUSWAHL_STAERKE = 4f;    // Autor 30.10.: "komisch fett" bei 5
    private static final BasicStroke AUSWAHL_LINIE = new BasicStroke(AUSWAHL_STAERKE);
    private static final BasicStroke AUSWAHL_AUSSEN_LINIE = new BasicStroke(2f);
    private static final BasicStroke AUSWAHL_SAUM_LINIE = new BasicStroke(4f);
    private static final Color AUSWAHL_SAUM = new Color(10, 12, 18, 190);
    private static final float RUNDUNG = 24f;   // Autor 28.10.: um die Schilder gelegt, nicht als Quadrat
    private static final float ECKE = 22f;      // gerades Stueck je Ecke (29.10. nachts: nur Ecken statt ganzer Rahmen)
    private static final BasicStroke LINIE = new BasicStroke(STAERKE);
    private static final Font SCHRIFT = schrift(Font.BOLD, 11);
    private static final Color PILLE = new Color(16, 20, 28, 170);
    private static final Color SCHRIFT_FARBE = new Color(255, 255, 255, 235);

    // ---- Windows-Konstanten (WinUser.h) -------------------------------------
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int STILE = WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    /** Nur auf dem Bildschirm, in keiner Aufnahme. */
    static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    /**
     * Windows 10 Version 2004. VORHER wirkt WDA_EXCLUDEFROMCAPTURE wie
     * WDA_MONITOR -- die Schicht waere dann in jedem Foto ein SCHWARZES
     * Rechteck ueber dem ganzen Spiel. Deshalb davor gar nicht erst setzen.
     */
    private static final int ERSTER_BUILD_MIT_AUSNAHME = 19041;

    /** Die zwei Funktionen, die JNAs User32 nicht mitbringt. */
    interface Anzeige32 extends Library {
        Anzeige32 INSTANCE = Native.load("user32", Anzeige32.class);

        boolean SetWindowDisplayAffinity(WinDef.HWND hWnd, int dwAffinity);

        boolean GetWindowDisplayAffinity(WinDef.HWND hWnd, IntByReference pdwAffinity);
    }

    // Nur auf dem EDT benutzt.
    private JWindow fenster;
    private Flaeche flaeche;
    private Pointer eingerichtetFuer;   // das Fenster (HWND), fuer das der Aufnahmeschutz gesetzt ist
    private Boolean durchsichtGeht;     // null = noch nicht geprueft

    private volatile boolean versteckt = false;
    private final AtomicBoolean gewarntAufnahme = new AtomicBoolean();
    private final AtomicBoolean gewarntStil = new AtomicBoolean();
    private final AtomicBoolean gewarntDurchsicht = new AtomicBoolean();

    /**
     * Zeigt die Schicht ueber schirmBereich (Bildschirmpunkte) mit diesen
     * Rahmen. Ein erneuter Aufruf ersetzt alle Rahmen und verschiebt die
     * Schicht, falls das Spiel sich bewegt hat. Ohne Rahmen wird sie versteckt.
     */
    public void zeigen(Rectangle schirmBereich, List<Rahmen> rahmen) {
        if (schirmBereich == null || schirmBereich.width <= 0 || schirmBereich.height <= 0) {
            verstecken();
            return;
        }
        List<Rahmen> kopie = new ArrayList<>();
        if (rahmen != null) {
            for (Rahmen r : rahmen) if (r != null) kopie.add(r);
        }
        if (kopie.isEmpty()) {
            verstecken();
            return;
        }
        Rectangle bereich = new Rectangle(schirmBereich);
        List<Rahmen> fest = List.copyOf(kopie);
        aufEdt(() -> zeigenEdt(bereich, fest));
    }

    /** Blendet die Schicht aus (sie bleibt fuer das naechste zeigen() bereit). */
    public void verstecken() {
        aufEdt(() -> {
            if (fenster != null) fenster.setVisible(false);
        });
    }

    /** Gibt das Fenster frei. Ein spaeteres zeigen() baut es neu auf. */
    public void schliessen() {
        aufEdt(() -> {
            if (fenster != null) fenster.dispose();
            fenster = null;
            flaeche = null;
            eingerichtetFuer = null;
            versteckt = false;
        });
    }

    /**
     * true, wenn Windows die Schicht aus Bildschirmfotos heraushaelt. false
     * vor dem ersten zeigen() und wenn es nicht geklappt hat -- dann koennen
     * die Rahmen in den Fotos des Bots auftauchen.
     */
    public boolean vorAufnahmeVersteckt() {
        return versteckt;
    }

    /** Kann dieses Windows Fenster aus Aufnahmen heraushalten (ab Windows 10 Version 2004)? */
    public static boolean aufnahmeSchutzMoeglich() {
        return windowsBuild() >= ERSTER_BUILD_MIT_AUSNAHME;
    }

    // ---- Arbeit auf dem EDT ------------------------------------------------

    /** Fehler hoechstens alle 30 s ins Protokoll (sonst bei jedem Takt). */
    private static volatile long fehlerGemeldetUm;

    private static void aufEdt(Runnable arbeit) {
        Runnable sicher = () -> {
            try {
                arbeit.run();
            } catch (Throwable t) {
                long jetzt = System.currentTimeMillis();
                if (jetzt - fehlerGemeldetUm > 30_000) {
                    fehlerGemeldetUm = jetzt;
                    Log.warn("Schicht", Sprache.t("Overlay error: " + t, "Fehler in der Schicht: " + t));
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) sicher.run();
        else SwingUtilities.invokeLater(sicher);
    }

    private void zeigenEdt(Rectangle schirm, List<Rahmen> rahmen) {
        if (!durchsichtMoeglich()) return;
        if (fenster == null) bauen();

        Abbildung abb = zuSwing(schirm);
        flaeche.setze(rahmen, abb.skalaX(), abb.skalaY());

        // pack() legt das Windows-Fenster an, ohne es zu zeigen -- so sind
        // Stile und Aufnahmeschutz gesetzt, BEVOR es zum ersten Mal erscheint.
        if (!fenster.isDisplayable()) fenster.pack();
        if (!abb.bereich().equals(fenster.getBounds())) fenster.setBounds(abb.bereich());
        nativEinrichten();
        // Review 28.10.: ohne Aufnahmeschutz NIE zeigen -- sonst stuenden die Rahmen in den Fotos des Bots.
        if (!versteckt) {
            if (fenster.isVisible()) fenster.setVisible(false);
            return;
        }
        if (!fenster.isVisible()) {
            fenster.setVisible(true);
            // AWT fasst beim Zeigen die Stile manchmal noch einmal an -- nachsehen.
            nativEinrichten();
        }
        flaeche.repaint();
    }

    private void bauen() {
        fenster = new JWindow();
        fenster.setFocusableWindowState(false);
        fenster.setAutoRequestFocus(false);
        fenster.setAlwaysOnTop(true);
        flaeche = new Flaeche();
        fenster.setContentPane(flaeche);
        // Voll durchsichtiger Hintergrund: Swing zeichnet dann je Punkt mit Deckkraft.
        fenster.setBackground(new Color(0, 0, 0, 0));
    }

    /**
     * Ohne Durchsicht je Punkt waere die Schicht ein schwarzes Brett ueber
     * dem Spiel. Dann lieber gar nichts zeigen.
     */
    private boolean durchsichtMoeglich() {
        if (durchsichtGeht == null) {
            GraphicsDevice schirm = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            durchsichtGeht = schirm.isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        }
        if (!durchsichtGeht && gewarntDurchsicht.compareAndSet(false, true)) {
            Log.warn("Schicht", Sprache.t(
                    "This screen cannot draw see-through windows -- the overlay stays off.",
                    "Dieser Bildschirm kann keine durchsichtigen Fenster -- die Schicht bleibt aus."));
        }
        return durchsichtGeht;
    }

    /** Klick-durchlaessig, nie aktiv, nicht in der Taskleiste -- und raus aus den Bildschirmfotos. */
    private void nativEinrichten() {
        Pointer zeiger;
        try {
            zeiger = Native.getWindowPointer(fenster);
        } catch (Throwable t) {
            zeiger = null;
        }
        if (zeiger == null) {
            if (gewarntStil.compareAndSet(false, true)) {
                Log.warn("Schicht", Sprache.t(
                        "Overlay: window handle not available -- clicks may not pass through and it may show up in screenshots.",
                        "Schicht: kein Fenstergriff -- Klicks gehen evtl. nicht hindurch, und sie kann in Fotos auftauchen."));
            }
            return;
        }
        WinDef.HWND hwnd = new WinDef.HWND(zeiger);

        int alt = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if ((alt & STILE) != STILE) {
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, alt | STILE);
            // Manche Stile merkt sich Windows zwischen -- so werden sie sicher uebernommen.
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
        }

        // Einmal je Windows-Fenster (nach schliessen() gibt es ein neues).
        if (!zeiger.equals(eingerichtetFuer)) {
            eingerichtetFuer = zeiger;
            versteckt = aufnahmeAusschliessen(hwnd);
        }
    }

    private boolean aufnahmeAusschliessen(WinDef.HWND hwnd) {
        int build = windowsBuild();
        if (build < ERSTER_BUILD_MIT_AUSNAHME) {
            warnenAufnahme(Sprache.t(
                    "Windows " + (build > 0 ? "build " + build : "version unknown")
                            + " cannot keep the overlay out of screenshots (needs Windows 10 2004 or newer). "
                            + "The frames may appear in the bot's pictures.",
                    "Windows " + (build > 0 ? "Build " + build : "Version unbekannt")
                            + " kann die Schicht nicht aus Bildschirmfotos heraushalten (erst ab Windows 10 2004). "
                            + "Die Rahmen koennen in den Bildern des Bots auftauchen."));
            return false;
        }
        try {
            if (!Anzeige32.INSTANCE.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)) {
                int fehler = Native.getLastError();
                warnenAufnahme(Sprache.t(
                        "Overlay could not be hidden from screenshots (Windows error " + fehler + "). "
                                + "The frames may appear in the bot's pictures.",
                        "Die Schicht liess sich nicht vor Bildschirmfotos verstecken (Windows-Fehler " + fehler + "). "
                                + "Die Rahmen koennen in den Bildern des Bots auftauchen."));
                return false;
            }
            IntByReference wert = new IntByReference();
            if (Anzeige32.INSTANCE.GetWindowDisplayAffinity(hwnd, wert)
                    && wert.getValue() != WDA_EXCLUDEFROMCAPTURE) {
                warnenAufnahme(Sprache.t(
                        "Overlay: Windows reports display affinity " + wert.getValue() + " instead of 17.",
                        "Schicht: Windows meldet Anzeige-Schutz " + wert.getValue() + " statt 17."));
                return false;
            }
            Log.debug("Schicht", Sprache.t("Overlay is hidden from screenshots.",
                    "Schicht ist vor Bildschirmfotos versteckt."));
            return true;
        } catch (Throwable t) {
            warnenAufnahme(Sprache.t("Overlay: hiding from screenshots failed: " + t,
                    "Schicht: Verstecken vor Bildschirmfotos ging schief: " + t));
            return false;
        }
    }

    /**
     * Haelt ein beliebiges eigenes Fenster aus Bildschirmfotos heraus (fuer das Seitenfenster, Review 28.10.).
     * Vor dem ersten setVisible rufen (nach pack()). false, wenn es nicht geht.
     */
    static boolean vorAufnahmeVerstecken(java.awt.Window w) {
        if (windowsBuild() < ERSTER_BUILD_MIT_AUSNAHME) return false;
        try {
            Pointer zeiger = Native.getWindowPointer(w);
            return zeiger != null && Anzeige32.INSTANCE.SetWindowDisplayAffinity(new WinDef.HWND(zeiger), WDA_EXCLUDEFROMCAPTURE);
        } catch (Throwable t) {
            return false;
        }
    }

    private void warnenAufnahme(String text) {
        if (gewarntAufnahme.compareAndSet(false, true)) Log.warn("Schicht", text);
    }

    /** Windows-Buildnummer aus der Registry (GetVersionEx luegt ohne Manifest), -1 wenn unbekannt. */
    private static int windowsBuild() {
        try {
            String wert = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber");
            return Integer.parseInt(wert.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- Bildschirmpunkte -> Swing-Punkte ----------------------------------

    private record Abbildung(Rectangle bereich, double skalaX, double skalaY) {}

    /**
     * Java legt unter Windows jeden Bildschirm mit seinem Ursprung in echten
     * Punkten an, rechnet ab dort aber in skalierten Punkten. Bei 100 %
     * (und mit uiScale=1 wie im Bot) kommt genau der Eingabebereich heraus.
     */
    private static Abbildung zuSwing(Rectangle schirm) {
        Point mitte = new Point(schirm.x + schirm.width / 2, schirm.y + schirm.height / 2);
        for (GraphicsDevice geraet : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = geraet.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            AffineTransform t = gc.getDefaultTransform();
            double sx = t.getScaleX() > 0 ? t.getScaleX() : 1;
            double sy = t.getScaleY() > 0 ? t.getScaleY() : 1;
            Rectangle echt = new Rectangle(b.x, b.y, (int) Math.round(b.width * sx), (int) Math.round(b.height * sy));
            if (!echt.contains(mitte)) continue;
            if (sx == 1 && sy == 1) return new Abbildung(new Rectangle(schirm), 1, 1);
            int x = (int) Math.floor(b.x + (schirm.x - b.x) / sx);
            int y = (int) Math.floor(b.y + (schirm.y - b.y) / sy);
            int w = (int) Math.ceil(schirm.width / sx);
            int h = (int) Math.ceil(schirm.height / sy);
            return new Abbildung(new Rectangle(x, y, w, h), sx, sy);
        }
        return new Abbildung(new Rectangle(schirm), 1, 1);
    }

    // ---- Zeichnen -----------------------------------------------------------

    /**
     * Segoe UI MIT Ersatzschriften (29.10. nachts, Vorschaubild): new Font("Segoe UI") ist eine einzelne Schrift --
     * chinesische, koreanische und japanische Namen, ▶ und ↵ kamen als leere Kaestchen. Ueber StyleContext liefert
     * Java dieselbe Schrift mit den Windows-Ersatzschriften dahinter (wie Swing fuer seine eigenen Schriften).
     */
    static Font schrift(int stil, int groesse) {
        return javax.swing.text.StyleContext.getDefaultStyleContext().getFont("Segoe UI", stil, groesse);
    }

    /** Fuer Vorschaubilder (SchichtVorschau): zeichnet die Rahmen wie auf dem Schirm, Massstab 1:1. */
    static void vorschauZeichnen(Graphics2D g2, List<Rahmen> rahmen, int breite) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Rahmen r : rahmen) Flaeche.rahmenZeichnen(g2, r);
        for (Rahmen r : rahmen) Flaeche.schildZeichnen(g2, r, breite);
    }

    /** Die Zeichenflaeche: nur Linien und Schilder, der Rest bleibt voll durchsichtig. */
    static final class Flaeche extends JComponent {
        private List<Rahmen> rahmen = List.of();
        private double skalaX = 1;
        private double skalaY = 1;

        Flaeche() {
            setOpaque(false);
        }

        void setze(List<Rahmen> neu, double sx, double sy) {
            rahmen = neu;
            skalaX = sx;
            skalaY = sy;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // Alten Inhalt sicher loeschen -- sonst bleiben verschobene Rahmen stehen.
                g2.setComposite(AlphaComposite.Clear);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setComposite(AlphaComposite.SrcOver);

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                // Ab hier in echten Bildschirmpunkten relativ zum Spielbereich.
                g2.scale(1 / skalaX, 1 / skalaY);
                int breite = (int) Math.round(getWidth() * skalaX);

                for (Rahmen r : rahmen) rahmenZeichnen(g2, r);
                // Schilder zuletzt, damit kein Rahmen ueber einem Schild liegt.
                for (Rahmen r : rahmen) schildZeichnen(g2, r, breite);
            } finally {
                g2.dispose();
            }
        }

        /**
         * Autor 29.10. nachts: "das Overlay soll nicht stoeren". Ganze Rahmen nur noch um die Auswahl und das Bot-Ziel;
         * alle anderen bekommen nur vier Ecken in ihrer Farbe -- die Farbe bleibt auf einen Blick lesbar, Name,
         * Clan und Belohnung im Schild liegen frei.
         */
        private static void rahmenZeichnen(Graphics2D g2, Rahmen r) {
            Rectangle b = r.bereich;
            if (b.width < 4 || b.height < 4) return;
            float staerke = r.auswahl ? AUSWAHL_STAERKE : STAERKE;
            // Autor 30.10.: "Auf der Sicherliste hat er schon eine gruene Umrandung, die Auswahl sieht man ziemlich schlecht." Eine weisse Linie mit
            // dunklem Saum aussen um die Listenfarbe. Ohne Liste ist der Rahmen selbst schon hell.
            if (r.auswahl && !AUSWAHL.equals(r.farbe)) {
                Rectangle aussen = new Rectangle(b.x - 5, b.y - 5, b.width + 10, b.height + 10);
                g2.setColor(AUSWAHL_SAUM);
                g2.setStroke(AUSWAHL_SAUM_LINIE);
                g2.draw(schildUmriss(aussen, 2f));
                g2.setColor(AUSWAHL);
                g2.setStroke(AUSWAHL_AUSSEN_LINIE);
                g2.draw(schildUmriss(aussen, 2f));
            }
            g2.setColor(r.auswahl ? new Color(r.farbe.getRed(), r.farbe.getGreen(), r.farbe.getBlue(), AUSWAHL_ALPHA)
                    : durchscheinend(r.farbe));
            g2.setStroke(r.auswahl ? AUSWAHL_LINIE : LINIE);
            // Autor 30.10.: "Der Layer soll die Gegner genau so umrahmen wie dieser goldene Rahmen bei mir." Vorher nur vier Ecken.
            g2.draw(schildUmriss(b, staerke / 2f));
        }

        /** Unterkante des goldenen Rahmens, aus Autors Foto vom 30.10. gelesen: {x, y} als Anteil des Schilds, von rechts nach links. */
        private static final float[][] UNTERKANTE = {
                {1f, .846f}, {.954f, .868f}, {.908f, .853f}, {.862f, .875f}, {.816f, .883f}, {.770f, .919f}, {.724f, .934f}, {.678f, .948f},
                {.632f, .964f}, {.586f, .978f}, {.540f, .993f}, {.494f, 1f}, {.448f, .985f}, {.402f, .971f}, {.356f, .956f}, {.310f, .948f},
                {.264f, .934f}, {.218f, .912f}, {.172f, .868f}, {.126f, .883f}, {.080f, .875f}, {.034f, .860f}, {0f, .850f}};

        /** Der Umriss des Schilds im Bereich b: oben gerade mit kleinen Ecken, unten die Spitze; Linie innerhalb des Bereichs. */
        static java.awt.geom.Path2D.Float schildUmriss(Rectangle b, float rand) {
            float x = b.x + rand, y = b.y + rand, w = b.width - 2 * rand, h = b.height - 2 * rand;
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            p.moveTo(x + .02f * w, y);
            p.lineTo(x + .98f * w, y);
            p.lineTo(x + w, y + .03f * h);
            for (float[] q : UNTERKANTE) p.lineTo(x + q[0] * w, y + q[1] * h);
            p.lineTo(x, y + .03f * h);
            p.closePath();
            return p;
        }

        /** Kleines dunkles Schild mit Farbpunkt ueber der linken oberen Ecke (passt es nicht, innen). */
        private static void schildZeichnen(Graphics2D g2, Rahmen r, int flaechenBreite) {
            String text = r.text;
            if (text == null || text.isBlank()) return;
            text = text.strip();
            g2.setFont(SCHRIFT);
            FontMetrics fm = g2.getFontMetrics();
            int randX = 5, randY = 1, punkt = 6, luecke = 4;
            int hoehe = fm.getAscent() + fm.getDescent() + 2 * randY;
            int breite = randX + punkt + luecke + fm.stringWidth(text) + randX;

            Rectangle b = r.bereich;
            int x = b.x;
            int y = b.y - hoehe - 2;
            if (y < 0) {
                x = b.x + 4;
                y = b.y + 4;
            }
            x = Math.max(0, Math.min(x, flaechenBreite - breite));

            g2.setColor(PILLE);
            g2.fill(new RoundRectangle2D.Float(x, y, breite, hoehe, hoehe, hoehe));
            g2.setColor(deckend(r.farbe));
            g2.fill(new Ellipse2D.Float(x + randX, y + (hoehe - punkt) / 2f, punkt, punkt));
            g2.setColor(SCHRIFT_FARBE);
            g2.drawString(text, x + randX + punkt + luecke, y + randY + fm.getAscent());
        }

        private static Color durchscheinend(Color c) {
            return c.getAlpha() == 255 ? new Color(c.getRed(), c.getGreen(), c.getBlue(), RAHMEN_ALPHA) : c;
        }

        private static Color deckend(Color c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 230);
        }
    }
}
