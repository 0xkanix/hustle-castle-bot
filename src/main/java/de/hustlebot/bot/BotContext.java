package de.hustlebot.bot;

import de.hustlebot.core.*;
import org.opencv.core.Mat;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Buendelt alles, was ein Spielmodus zum Arbeiten braucht: Fenster, Aufnahme,
 * Bilderkennung und Maussteuerung. Haelt ausserdem das zuletzt aufgenommene
 * Bild fuer die Vorschau in der Weboberflaeche bereit.
 */
public final class BotContext {

    private final GameWindow window;
    private final ScreenCapture capture;
    private final Matcher matcher;

    /**
     * Ergebnisse der Vorlagensuche im aktuellen Bild. Wird bei jeder neuen
     * Aufnahme geleert, damit nie ein veralteter Treffer benutzt wird.
     */
    private final java.util.Map<String, Optional<Matcher.Hit>> frameCache =
            new java.util.HashMap<>();
    private final Input input;
    private final Laufstatistik laufstatistik;

    private volatile BufferedImage lastFrame;
    private volatile Mat lastMat;
    /** Der Fensterbereich vom letzten Bild -- aendert er sich, gelten die gemerkten Knopfstellen nicht mehr. */
    private Rectangle letzterBereich;

    public BotContext(GameWindow window, ScreenCapture capture, Matcher matcher, Input input) {
        this(window, capture, matcher, input, new Laufstatistik());
    }

    public BotContext(GameWindow window, ScreenCapture capture, Matcher matcher,
                      Input input, Laufstatistik laufstatistik) {
        this.window = window;
        this.capture = capture;
        this.matcher = matcher;
        this.input = input;
        this.laufstatistik = laufstatistik;
        matcher.setLaufstatistik(laufstatistik);
        input.setLaufstatistik(laufstatistik);
    }

    /** Nur zum Zusehen (Arena-Beobachter 21.10.): eigener Zwischenspeicher, Matcher und Eingabe behalten ihre Laufstatistik. */
    private BotContext(BotContext von) {
        this.window = von.window;
        this.capture = von.capture;
        this.matcher = von.matcher;
        this.input = von.input;
        this.laufstatistik = von.laufstatistik;
    }

    /** Ein zweiter Blick fuer den Arena-Beobachter -- klickt nie, holt nichts nach vorn. */
    public BotContext zumZusehen() {
        return new BotContext(this);
    }

    /** Ein eingefrorener Blick auf dieses eine Bild (Autor 30.10.): zum Lesen in einem eigenen Faden, eigener Zwischenspeicher, klickt nie. */
    public BotContext standbild(BufferedImage bild, Mat mat) {
        BotContext b = new BotContext(this);
        b.lastFrame = bild;
        b.lastMat = mat;
        return b;
    }

    /**
     * Ein Bild nur, wenn das Spiel ohnehin ganz vorn steht (Arena-Beobachter, Autor 21.10.): nichts nach vorn holen,
     * nichts an der Eingabe aendern. null, wenn das Spiel nicht zu sehen ist.
     */
    public Mat beobachten() {
        // 22.10.: nach einem Neustart kannte der Zuschauer das Spielfenster nicht, bis einmal ein Modus lief -- dann gab es
        // weder Beobachter noch Fotoserie, wenn Autor selbst spielte. Still suchen (locateVisible schreibt nichts ins Protokoll).
        if (!window.isAlive() && !window.locateVisible()) return null;
        if (!window.istImVordergrund()) return null;
        Rectangle area = window.clientArea();
        if (area == null || area.width < 200 || area.height < 200) return null;
        frameCache.clear();
        matcher.neuesBild();
        BufferedImage frame = capture.grab(area);
        if (frame == null) return null;
        lastFrame = frame;
        bildUm = System.currentTimeMillis();
        // Autor 22.10.: auch wenn er selbst spielt (der Bot schaut nur zu), soll die Fotoserie laufen.
        de.hustlebot.core.Fotoserie.bild(frame);
        if (lastMat != null) lastMat.release();
        lastMat = ScreenCapture.toMat(frame);
        return lastMat;
    }

    public Laufstatistik laufstatistik() {
        return laufstatistik;
    }

    /**
     * Nimmt ein frisches Bild des Spielfensters auf. Gibt null zurueck, wenn das
     * Fenster verschwunden ist.
     */
    /** Das zuletzt aufgenommene Bild (nach refresh() ist ein frueher geholtes ungueltig). */
    public Mat aktuellesBild() {
        return lastMat;
    }

    public Mat refresh() {
        laufstatistik.beginFrame();
        frameCache.clear();
        matcher.neuesBild();

        if (!window.isAlive() && !window.locate()) {
            return null;
        }
        window.prepare();

        Rectangle area = window.clientArea();
        if (area == null || area.width < 200 || area.height < 200) {
            Log.warn("Aufnahme", Sprache.t("Invalid window area: " + area, "Ungueltiger Fensterbereich: " + area));
            return null;
        }
        input.setClientArea(area);

        // Hat sich das Fenster verschoben oder seine Groesse geaendert, sind
        // die gemerkten Knopfstellen wertlos -- der Bot wuerde an der alten
        // Stelle nachsehen, nichts finden und den Knopf fuer abwesend halten,
        // obwohl er da ist. Also lieber einmal alles neu lernen.
        // 24.09.: nur noch bei einer anderen GROESSE. Die Stellen gelten
        // innerhalb des Fensters -- ein verschobenes Fenster aendert daran
        // nichts. Und beim allerersten Bild auch nicht mehr: Da wurden sonst
        // die gespeicherten Stellen vom letzten Start gleich wieder verworfen.
        if (letzterBereich != null && (letzterBereich.width != area.width
                || letzterBereich.height != area.height)) {
            // Karla 17.10.: wenige Bildpunkte (Windows-Skalierung) -> Stellen behalten, nur neu bestaetigen.
            if (Math.abs(letzterBereich.width - area.width) <= 16 && Math.abs(letzterBereich.height - area.height) <= 16) {
                matcher.bestaetigungVergessen();
            } else {
                matcher.stellenVergessen();
            }
        }
        letzterBereich = new Rectangle(area);

        long aufnahmeVon = System.nanoTime();
        BufferedImage frame = capture.grab(area);
        // Karla 17.10.: fuer den Fehlerbericht -- wie lange ein Bild vom Spielfenster auf diesem PC dauert.
        double aufnahmeMs = (System.nanoTime() - aufnahmeVon) / 1e6;
        bildMsSchnitt = bildMsSchnitt <= 0 ? aufnahmeMs : bildMsSchnitt * 0.98 + aufnahmeMs * 0.02;
        lastFrame = frame;
        bildUm = System.currentTimeMillis();
        de.hustlebot.core.Fotoserie.bild(frame);   // Autor 18.10.: Fotoserie aus den eigenen Bildern

        if (lastMat != null) lastMat.release();
        lastMat = ScreenCapture.toMat(frame);
        return lastMat;
    }

    /**
     * Sucht eine Vorlage im aktuellen Bild -- und merkt sich das Ergebnis.
     *
     * Innerhalb eines Bildes wird dieselbe Vorlage oft mehrfach abgefragt: erst
     * "ist sie da?", dann "klicke sie". Jede Suche geht ueber das ganze Bild und
     * kostet Rechenzeit. Der Zwischenspeicher wird bei jeder neuen Aufnahme
     * geleert, das Ergebnis ist also immer frisch.
     */
    public Optional<Matcher.Hit> find(Mat screen, String template) {
        screen = lebendesBild(screen, lastMat);
        Optional<Matcher.Hit> known = frameCache.get(template);
        if (known != null) return known;

        Optional<Matcher.Hit> result = matcher.find(screen, template);
        frameCache.put(template, result);
        return result;
    }

    public boolean visible(Mat screen, String template) {
        return find(screen, template).isPresent();
    }

    /**
     * Bildfehler-Suche 30.10.: 664 Mal im Oktober "Template larger than the screen ... screen 0x0" -- ein Modus las
     * die Nahrung (Nahrungskisten.nahrungLesen holt dafuer selbst frische Bilder, refresh() gibt dabei das alte frei)
     * und suchte danach im SELBEN Schritt mit seinem alten, schon freigegebenen Bild weiter: Gem Bay fand den Karten-Knopf
     * nicht (51 Mal), die Tasche ihren Knopf nicht (48 Mal), Fenster-X und Tasche je 16-26 Mal. Jeder solche Blick sah
     * "nichts" und kostete einen Schritt. Statt jede Stelle einzeln zu flicken: ist das uebergebene Bild leer
     * (freigegeben), gilt das neueste Bild -- es ist nie aelter als das, mit dem der Modus rechnete.
     */
    static Mat lebendesBild(Mat screen, Mat aktuell) {
        if (screen == null || !screen.empty()) return screen;   // null bleibt null (wie bisher)
        if (aktuell != null && aktuell != screen && !aktuell.empty()) return aktuell;
        return screen;
    }

    /**
     * Wie visible(), aber still, wenn es die Vorlage ueberhaupt nicht gibt.
     *
     * Fuer freiwillige Vorlagen. visible() geht sonst ueber matcher.find(),
     * und das warnt bei jedem Aufruf "Unbekannte Vorlage angefragt" -- was
     * bei einer Pruefung in jedem Bild fuenfundzwanzig gleiche Warnungen in
     * einem Bericht ergibt und die echten Meldungen zudeckt. Genau so ist
     * es mit cw_deploy_grey passiert.
     *
     * Fuer Pflichtvorlagen weiter visible() nehmen: dort IST die Warnung
     * die Meldung, die man sehen will.
     */
    public boolean visibleIfKnown(Mat screen, String template) {
        return matcher.has(template) && visible(screen, template);
    }

    /** Wie clickIfVisible(), aber still, wenn es die Vorlage nicht gibt. */
    public boolean clickIfVisibleIfKnown(Mat screen, String template, String reason) {
        return matcher.has(template) && clickIfVisible(screen, template, reason);
    }

    /** Sucht eine Vorlage und klickt sie, wenn sie da ist. */
    public boolean clickIfVisible(Mat screen, String template, String reason) {
        Optional<Matcher.Hit> hit = find(screen, template);
        if (hit.isEmpty()) return false;

        Matcher.Hit h = hit.get();
        Log.action("Klick", reason + "  [" + template + Sprache.t(" at ", " bei ") + h.centerX() + "," + h.centerY()
                + Sprache.t(", match ", ", Uebereinstimmung ") + String.format("%.3f", h.score()) + "]");
        laufstatistik.setStep(template, reason);
        input.click(h);
        Stoerfenster.klickGemacht(template);   // 30.10. (#ETD4): "Update" -> 45 s keine Haenger-Meldung, egal welcher Modus
        return true;
    }

    /** Ein schon gefundener Treffer wird angeklickt und wie ueblich protokolliert. */
    public void klick(Matcher.Hit h, String reason) {
        Log.action("Klick", reason + "  [" + h.name() + Sprache.t(" at ", " bei ") + h.centerX() + "," + h.centerY()
                + Sprache.t(", match ", ", Uebereinstimmung ") + String.format("%.3f", h.score()) + "]");
        laufstatistik.setStep(h.name(), reason);
        input.click(h);
        Stoerfenster.klickGemacht(h.name());
    }

    /**
     * Bitte um ein sofortiges Ablesen der Zaehler beim naechsten Bild.
     *
     * Warum es das braucht: Ether, Particle of Light, Schluessel und
     * Multiplikator stehen jeweils nur auf einem bestimmten Bildschirm, und
     * der ist oft nur ein, zwei Sekunden zu sehen. Der regelmaessige Takt
     * traf ihn deshalb fast nie -- in einem Lauf ueber 17 Minuten wurde genau
     * einmal abgelesen.
     *
     * Die Spielmodi wissen dagegen genau, wann so ein Bildschirm offen ist.
     * Sie sagen hier Bescheid, und dann wird beim naechsten Bild gelesen,
     * unabhaengig vom Takt.
     */
    public void requestCounterRead() {
        counterReadWanted = true;
    }

    /** Holt die Bitte ab und loescht sie. Nur fuer den Motor gedacht. */
    public boolean consumeCounterRead() {
        boolean wanted = counterReadWanted;
        counterReadWanted = false;
        return wanted;
    }

    private volatile boolean counterReadWanted = false;

    /**
     * Ein frisches Bild des Spielfensters, ohne den Motor-Takt abzuwarten (aus dem Hauptbot, 10.10. --
     * die Titan-Grabung fotografiert damit die Rangliste). null, wenn das Fenster weg ist.
     */
    public BufferedImage frischesFoto() {
        if (!window.isAlive() && !window.locate()) return null;
        Rectangle area = window.clientArea();
        if (area == null || area.width < 200 || area.height < 200) return null;
        return capture.grab(area);
    }

    public GameWindow window()   { return window; }
    public Matcher matcher()     { return matcher; }
    public Input input()         { return input; }
    public BufferedImage frame() { return lastFrame; }

    /** Pruefer 29.10.: wann frame() aufgenommen wurde (0 = noch nie) -- Werte vom Bild gelten fuer diese Zeit, nicht fuer jetzt. */
    private volatile long bildUm;
    public long bildUm() { return bildUm; }

    public int width()  { return lastFrame != null ? lastFrame.getWidth() : 0; }
    public int height() { return lastFrame != null ? lastFrame.getHeight() : 0; }

    /**
     * Wartet die angegebene Zeit, aber nicht auf die Millisekunde genau.
     * Dadurch entsteht kein gleichfoermiger Maschinentakt.
     */
    public void sleep(long ms) {
        long begonnen = System.currentTimeMillis();
        try {
            Thread.sleep(input.humanizer().vary(de.hustlebot.core.Rechnertempo.mal(ms)));   // langsamer Rechner (29.10.)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        gewartetMs.addAndGet(System.currentTimeMillis() - begonnen);
    }

    /**
     * Wie lange die Modi insgesamt gewartet haben (sleep und
     * sleepUntilChanged), in Millisekunden. Fuer den Zeitbericht je Run
     * (Autor, 26.09.): welcher Teil eines Runs ist Warten des Bots, welcher
     * gehoert dem Spiel.
     */
    private final java.util.concurrent.atomic.AtomicLong gewartetMs =
            new java.util.concurrent.atomic.AtomicLong();

    public long gewartetMs() {
        return gewartetMs.get();
    }

    // ------------------------------------------------------------------
    //  Kastenblick (27.09.): nur die Stelle des erwarteten Knopfs ansehen
    // ------------------------------------------------------------------

    /** So viel Luft um die bekannte Stelle eines Knopfs. */
    private static final int KASTENBLICK_LUFT = 14;

    /**
     * EIN BLICK auf die bekannten Stellen dieser Knoepfe: ein Foto (7 ms),
     * umgerechnet und verglichen werden nur die kleinen Kaesten. Geliefert
     * wird der erste Knopf der Liste, der gut genug passt.
     *
     * Leer heisst: keiner da -- oder es ist noch keine Stelle bekannt, oder
     * das Spiel ist nicht vorn. Dann entscheidet der Aufrufer wie bisher am
     * ganzen Bild. Geklickt wird also weiterhin nur, was gesehen wurde.
     */
    public Optional<Matcher.Hit> kastenBlick(double schwelle, String... namen) {
        Rectangle area = letzterBereich;
        if (area == null || !window.istImVordergrund()) return Optional.empty();
        BufferedImage foto = null;
        for (String name : namen) {
            for (int[] k : matcher.kaesten(name, area.width, area.height, KASTENBLICK_LUFT)) {
                if (foto == null) foto = capture.grab(area);
                Mat kasten = ScreenCapture.toMatAusschnitt(foto, k[0], k[1], k[2], k[3]);
                try {
                    Optional<Matcher.Hit> h = matcher.imAusschnitt(kasten, k[0], k[1], name);
                    if (h.isPresent() && h.get().score() >= schwelle) return h;
                } finally {
                    kasten.release();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * KASTENAUFNAHME (Autor, 06.10.: "Dass der Bot so wenig wie moeglich
     * aufnehmen muss vom Bildschirm, um einen Knopf zu finden"): Es wird NUR
     * der Kasten {x, y, breite, hoehe} (Fensterkoordinaten) fotografiert, nicht
     * das ganze Fenster -- und darin nach den Vorlagen gesucht. Der Treffer
     * steht in Fensterkoordinaten, zaehlt in der Bilderkennungs-Zeile als "im
     * kleinen Kasten", und ein Klick darauf trifft wie nach einem ganzen Bild.
     *
     * Anders als kastenBlick() braucht das keine gemerkte Stelle: Der Aufrufer
     * sagt, wo er den Knopf erwartet (etwa aus einem frueheren Lauf errechnet).
     * Leer heisst: nicht da, Kasten unbrauchbar, oder das Spiel ist nicht vorn.
     * Das grosse Bild (frame()) bleibt dabei das vom letzten refresh() -- OCR
     * muss weiter daraus lesen.
     */
    public Optional<Matcher.Hit> kastenAufnahme(int[] kasten, double schwelle, String... namen) {
        Rectangle area = letzterBereich;
        if (area == null || kasten == null || kasten.length < 4 || !window.istImVordergrund()) return Optional.empty();
        int x0 = Math.max(0, kasten[0]);
        int y0 = Math.max(0, kasten[1]);
        int x1 = Math.min(area.width, kasten[0] + kasten[2]);
        int y1 = Math.min(area.height, kasten[1] + kasten[3]);
        if (x1 - x0 < 8 || y1 - y0 < 8) return Optional.empty();
        BufferedImage foto;
        try {
            foto = capture.grab(new Rectangle(area.x + x0, area.y + y0, x1 - x0, y1 - y0));
        } catch (Exception e) {
            return Optional.empty();
        }
        // Nicht ueber toMat(): das haelt ein Zwischenbild in Fenstergroesse vor und
        // wuerde es bei jedem anderen Kasten neu anlegen (siehe ScreenCapture).
        Mat mat = ScreenCapture.toMatAusschnitt(foto, 0, 0, foto.getWidth(), foto.getHeight());
        try {
            for (String name : namen) {
                if (!matcher.has(name)) continue;
                Optional<Matcher.Hit> h = matcher.imAusschnitt(mat, x0, y0, name);
                if (h.isPresent() && h.get().score() >= schwelle) return h;
            }
            return Optional.empty();
        } finally {
            mat.release();
        }
    }

    /**
     * EIN FOTO, ALLE KNOEPFE: welche dieser Knoepfe sind gerade zu sehen?
     * Fuer Stellen, an denen mehrere Fenster in Frage kommen (nach einem
     * Wegpunkt: Kampf, Auswahl, Belohnung ...). Geliefert wird je Name der
     * Treffer; was fehlt, ist nicht da oder hat noch keine bekannte Stelle.
     */
    public java.util.Map<String, Matcher.Hit> kastenBlicke(double schwelle, String... namen) {
        java.util.Map<String, Matcher.Hit> da = new java.util.LinkedHashMap<>();
        Rectangle area = letzterBereich;
        if (area == null || !window.istImVordergrund()) return da;
        BufferedImage foto = null;
        for (String name : namen) {
            for (int[] k : matcher.kaesten(name, area.width, area.height, KASTENBLICK_LUFT)) {
                if (foto == null) foto = capture.grab(area);
                Mat kasten = ScreenCapture.toMatAusschnitt(foto, k[0], k[1], k[2], k[3]);
                try {
                    Optional<Matcher.Hit> h = matcher.imAusschnitt(kasten, k[0], k[1], name);
                    if (h.isPresent() && h.get().score() >= schwelle) {
                        Matcher.Hit alt = da.get(name);
                        if (alt == null || h.get().score() > alt.score()) da.put(name, h.get());
                    }
                } finally {
                    kasten.release();
                }
            }
        }
        return da;
    }

    /**
     * Dieselbe Frage wie kastenBlicke(), aber am GANZEN Bild und mit der
     * ueblichen Suche (60 px Luft um die gemerkten Stellen, ab und zu ganz).
     * Fuer den Fall, dass der Kastenblick nichts sieht, weil ein Knopf in
     * diesem Fenster woanders sitzt (27.09., Wuerfel: "Auswahl abschliessen"
     * 28 px tiefer). Kostet ein ganzes Bild (rund 35 ms) statt 8 ms; die Suche
     * merkt sich die neue Stelle, danach reicht wieder der Kastenblick.
     * Null heisst: kein Bild zu bekommen.
     */
    public java.util.Map<String, Matcher.Hit> weitBlicke(double schwelle, String... namen) {
        Mat bild = refresh();
        if (bild == null) return null;
        java.util.Map<String, Matcher.Hit> da = new java.util.LinkedHashMap<>();
        for (String name : namen) {
            if (!matcher.has(name)) continue;
            matcher.find(bild, name, schwelle).ifPresent(h -> da.put(name, h));
        }
        return da;
    }

    /** Ist fuer wenigstens einen dieser Knoepfe eine Stelle bekannt? Sonst lohnt das Warten nicht. */
    public boolean kastenBekannt(String... namen) {
        Rectangle area = letzterBereich;
        if (area == null) return false;
        for (String name : namen) {
            if (!matcher.kaesten(name, area.width, area.height, KASTENBLICK_LUFT).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Wartet per Kastenblick, bis einer der Knoepfe da ist -- laengstens maxMs.
     * Ein Blick kostet rund 8 ms; dazwischen liegt keine Pause, das Foto
     * selbst wartet schon auf das naechste Bild des Bildschirms.
     */
    public Optional<Matcher.Hit> warteAufKnopf(long maxMs, double schwelle, String... namen) {
        long begonnen = System.currentTimeMillis();
        try {
            if (!kastenBekannt(namen)) return Optional.empty();
            while (System.currentTimeMillis() - begonnen < maxMs) {
                Optional<Matcher.Hit> h = kastenBlick(schwelle, namen);
                if (h.isPresent()) return h;
                if (Thread.currentThread().isInterrupted()) return Optional.empty();
                if (!window.istImVordergrund()) {
                    // Spiel verdeckt: nicht heisslaufen, der Aufrufer sieht gleich am ganzen Bild nach.
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } finally {
            gewartetMs.addAndGet(System.currentTimeMillis() - begonnen);
        }
    }

    /**
     * Wartet per Kastenblick, bis der Knopf (oder das Fenster) WEG ist --
     * laengstens maxMs. True, wenn er weg ist.
     */
    public boolean warteBisWeg(long maxMs, double schwelle, String name) {
        maxMs = de.hustlebot.core.Rechnertempo.mal(maxMs);   // langsamer Rechner (29.10.)
        long begonnen = System.currentTimeMillis();
        try {
            if (!kastenBekannt(name)) return false;
            while (System.currentTimeMillis() - begonnen < maxMs) {
                if (!window.istImVordergrund()) return false;
                if (kastenBlick(schwelle, name).isEmpty()) return true;
                if (Thread.currentThread().isInterrupted()) return false;
            }
            return false;
        } finally {
            gewartetMs.addAndGet(System.currentTimeMillis() - begonnen);
        }
    }

    /** Takt und Blindzeit fuer sleepUntilChanged neu setzen (lernwerte.txt). */
    public void setWarteTakt(long takt, long blind) {
        pollMs = Math.max(5, takt);
        blindeWartezeitMs = Math.max(0, blind);
    }

    // ------------------------------------------------------------------
    //  Klug warten
    // ------------------------------------------------------------------

    /**
     * Ab dieser Abweichung gilt der Bildschirm als gewechselt.
     *
     * Gemessen wird auf einem stark verkleinerten Graubild, 0 bis 255. Das
     * Flackern der Fackeln und Kerzen bewegt sich dort im Bereich von 1 bis 3.
     * Ein neues Fenster oder ein Bildschirmwechsel liegt weit darueber. Der
     * Wert ist bewusst hoch angesetzt: lieber einmal zu lange warten als zu
     * frueh weiterklicken.
     */
    private static final double CHANGE_THRESHOLD = 8.0;

    /**
     * Wie oft nachgesehen wird, ob sich schon etwas getan hat. War 60: Im
     * Protokoll kam die Aenderung fast immer schon beim ERSTEN Nachsehen
     * (gewartet 110-130 ms) -- sie war also frueher da, der Bot schaute nur
     * zu spaet hin. Das kleine Graubild zu holen kostet nur wenige ms.
     * Autor, 24.09.: "so schnell wie du nur kannst".
     */
    private volatile long pollMs = 60;

    /**
     * Wartet, bis sich der Bildschirm sichtbar geaendert hat -- laengstens aber
     * die angegebene Zeit.
     *
     * Frueher wartete der Bot nach jedem Klick stur eine feste Zeit, damit die
     * Animation des Spiels durchlaufen kann. Meist war sie viel frueher fertig,
     * und der Bot sass die Restzeit ab. Jetzt schaut er nach: sobald sich etwas
     * getan hat, macht er weiter.
     *
     * Langsamer als vorher kann es dadurch nie werden -- die alte Wartezeit ist
     * die Obergrenze. Und sicherer ist es auch: haengt das Spiel einmal, wird
     * weiterhin voll gewartet.
     */
    /**
     * Wie lange blind gewartet wird, bevor ueberhaupt hingesehen wird.
     *
     * Hier standen 180 Millisekunden. Das klingt nach nichts, kommt aber je
     * Kathedralen-Durchlauf rund zweihundertmal vor -- sechsunddreissig
     * Sekunden, in denen der Bot die Augen zuhaelt.
     *
     * Frueher anzufangen kann naemlich nichts kaputt machen: Beendet wird das
     * Warten erst, wenn sich der Bildschirm WIRKLICH geaendert hat. Wer
     * frueher hinschaut, sieht die Aenderung nur frueher -- nicht eine, die
     * es nicht gibt.
     *
     * Aus genau dem Grund jetzt 40 statt 80 (Autor, 18.09.: "finde sinnlose
     * Wartezeiten, verringer sie oder entfern sie ganz"). Das spart auf einer
     * Kathedralen-Reise noch einmal rund acht Sekunden, ohne dass der Bot
     * etwas anders macht -- er sieht nur frueher hin.
     */
    private volatile long blindeWartezeitMs = 40;

    private volatile java.util.function.BiConsumer<String,String> discordHinweis = (t, x) -> { };

    /** Hinweise aus den Spielmodi nach Discord (Freunde-Fassung, z. B. "Vorlage neu aufnehmen"). */
    public void setDiscordHinweis(java.util.function.BiConsumer<String,String> melder) {
        this.discordHinweis = (melder == null) ? (t, x) -> { } : melder;
    }

    public void melde(String titel, String text) {
        try {
            discordHinweis.accept(titel, text);
        } catch (Exception ignored) {
            // eine misslungene Meldung darf den Modus nicht stoeren
        }
    }

    public void sleepUntilChanged(long maxMs) {
        sleepUntilChanged(Math.min(blindeWartezeitMs, maxMs / 3), maxMs);
    }

    /**
     * Schnelles Hinsehen (20 ms Takt, 10 ms blind) -- nur fuer die Kathedrale.
     *
     * 25.09.: Am 24.09. galt das fuer ALLE Modi, geprueft war es nur in der
     * Kathedrale. In der Arena sah der Bot dann schon im Uebergangsbild
     * nach: Arena-Insel angeklickt, Weltkarte noch zu sehen, wieder weg
     * geklickt -- fuenf Minuten im Kreis; beim Blaettern durch die Gegner
     * las er halb geladene Bilder. Die anderen Modi bekommen wieder die
     * alten, erprobten Werte (60 ms Takt, 40 ms blind).
     */
    public void setSchnell(boolean schnell) {
        pollMs = schnell ? 20 : 60;
        blindeWartezeitMs = schnell ? 10 : 40;
    }

    /**
     * Messmodus: Schreibt fuer jede Wartestelle mit, wie lange es WIRKLICH
     * bis zur Bildaenderung gedauert hat.
     *
     * Autor, 19.09.: "Mach beispielsweise einen Run und messe die Wartezeiten
     * von jedem click ... und anhand den Werten sollen die Wartezeiten gesetzt
     * werden." Genau dafuer. Die Zeile steht direkt hinter der Klick-Zeile im
     * Protokoll, dadurch laesst sich beides zusammenbringen.
     *
     * Kostet nichts: Die Zeit wird ohnehin gestoppt, es ist nur eine
     * zusaetzliche Protokollzeile auf Debug-Ebene.
     */
    public void sleepUntilChanged(long minMs, long maxMs) {
        // Langsamer Rechner (Autor 29.10., Server bis 5x langsamer): Blindzeit und Obergrenze x Faktor -- es endet weiter,
        // sobald sich das Bild aendert.
        minMs = de.hustlebot.core.Rechnertempo.mal(minMs);
        maxMs = de.hustlebot.core.Rechnertempo.mal(maxMs);
        long begonnen = System.currentTimeMillis();
        long vorher = gewartetMs.get();
        boolean geaendert = sleepUntilChangedIntern(minMs, maxMs);
        long gebraucht = System.currentTimeMillis() - begonnen;
        // Die Blindzeit darin lief ueber sleep() und ist schon gezaehlt.
        gewartetMs.addAndGet(Math.max(0, gebraucht - (gewartetMs.get() - vorher)));
        Log.debug("Messung", "gewartet " + gebraucht + " ms von hoechstens " + maxMs
                + (geaendert ? " (Bild hat sich geaendert)" : " -- OBERGRENZE erreicht, "
                        + "das Bild hat sich nicht sichtbar geaendert"));
    }

    /**
     * Klick-Kontrolle (17.10.): wartet hoechstens maxMs, ob sich das Bild gegenueber dem letzten refresh() aendert.
     * Vorher refresh() aufrufen, sonst ist das Vergleichsbild alt.
     */
    public boolean bildGeaendert(long minMs, long maxMs) {
        return sleepUntilChangedIntern(minMs, maxMs);
    }

    /** Gleitender Schnitt: so lange dauert ein Bild vom Spielfenster auf diesem PC (Fehlerbericht, 17.10.). */
    private static volatile double bildMsSchnitt;

    public static double bildMsSchnitt() {
        return bildMsSchnitt;
    }

    /** Das eigentliche Warten. True, wenn sich das Bild geaendert hat. */
    private boolean sleepUntilChangedIntern(long minMs, long maxMs) {
        sleep(minMs);

        // Vergleichsbild ist die Aufnahme VOR dem Klick. Die liegt schon vor,
        // kostet also nichts extra.
        Mat before = shrink(lastMat);
        if (before == null) {
            sleep(Math.max(0, maxMs - minMs));
            return false;
        }

        long deadline = System.currentTimeMillis() + Math.max(0, maxMs - minMs);
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollMs);

                Mat now = grabSmall();
                if (now == null) continue;
                double difference;
                try {
                    difference = meanDifference(before, now);
                } finally {
                    // In den Vergleich hinein kann eine OpenCV-Ausnahme
                    // fallen. Ohne finally bliebe dieses Bild im Speicher
                    // liegen -- und zwar im nativen Speicher, den der
                    // Java-Aufraeumer nicht anfasst. Bei einem Bild alle
                    // paar hundert Millisekunden ueber eine Nacht waere das
                    // kein Einzelfall mehr.
                    now.release();
                }

                if (difference > CHANGE_THRESHOLD) return true;   // Bildschirm ist gewechselt
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            before.release();
        }
        return false;
    }

    /** Ein winziges Graubild des Spielfensters, nur zum Vergleichen. */
    private Mat grabSmall() {
        // Nur fotografieren, wenn das Spiel auch wirklich zu sehen ist.
        //
        // Diese Aufnahmen laufen alle sechzig Millisekunden, waehrend auf
        // eine Animation gewartet wird -- und anders als beim grossen Bild
        // wird das Fenster dabei nicht nach vorn geholt. Ist Hustle Castle
        // zu, minimiert oder von etwas anderem verdeckt, wuerde der Bot
        // stattdessen Autors uebrigen Bildschirm fotografieren. Das ist
        // nutzlos fuer den Vergleich und geht ihn nichts an.
        if (!window.istImVordergrund()) return null;

        Rectangle area = window.clientArea();
        if (area == null) return null;
        Mat full = null;
        try {
            full = ScreenCapture.toMat(capture.grab(area));
            return shrink(full);
        } catch (Exception e) {
            return null;
        } finally {
            if (full != null) full.release();
        }
    }

    /** Verkleinert ein Bild auf Briefmarkengroesse und macht es grau. */
    private static Mat shrink(Mat source) {
        if (source == null || source.empty()) return null;
        Mat small = new Mat();
        org.opencv.imgproc.Imgproc.resize(source, small,
                new org.opencv.core.Size(64, 48));
        org.opencv.imgproc.Imgproc.cvtColor(small, small,
                org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
        return small;
    }

    /** Mittlere Helligkeitsabweichung zweier Briefmarken, 0 bis 255. */
    private static double meanDifference(Mat a, Mat b) {
        Mat diff = new Mat();
        try {
            org.opencv.core.Core.absdiff(a, b, diff);
            return org.opencv.core.Core.mean(diff).val[0];
        } finally {
            diff.release();
        }
    }
}
