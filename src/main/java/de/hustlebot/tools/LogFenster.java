package de.hustlebot.tools;

import de.hustlebot.core.Sprache;

import de.hustlebot.steuer.Holzstil;
import de.hustlebot.steuer.Symbol;
import de.hustlebot.steuer.Warnhilfe;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Ein Fenster, das neben dem Spiel mitlaeuft und zeigt, was der Bot gerade tut.
 *
 * ------------------------------------------------------------------
 *  Warum nicht einfach das Bedienfeld im Browser
 * ------------------------------------------------------------------
 *
 * Das Bedienfeld auf localhost:7331 zeigt das Protokoll laengst mit --
 * aber es ist eine ganze Seite mit Vorschaubild, Knoepfen und Statistik,
 * und ein Browserfenster liegt beim Spielen im Weg oder verschwindet
 * dahinter.
 *
 * Dieses Fenster tut genau eine Sache: zeigen, was gerade passiert. Es
 * bleibt obenauf und ist schmal genug fuer den Bildschirmrand.
 *
 * ------------------------------------------------------------------
 *  Lesbarkeit ist hier die eigentliche Aufgabe
 * ------------------------------------------------------------------
 *
 * Die erste Fassung war ein Block gleichfarbiger Zeilen. Autor dazu:
 * "alles hat die gleiche farbe im log ... aktuell tut es in den augen
 * weh das anzuschauen." Das war berechtigt -- wer nebenbei hinsieht,
 * will nicht lesen, sondern ERKENNEN.
 *
 * Deshalb:
 *
 *   - Oben gross und in Ruhe: was der Bot GERADE macht. Ein Blick
 *     genuegt, der Rest ist Nachschlagewerk.
 *   - Farbe nach Bedeutung, nicht nach Laune: Klicks blau-grau, Warnungen
 *     bernstein, Fehler rot. Gewoehnliche Meldungen bleiben ruhig.
 *   - Warnungen und Fehler bekommen zusaetzlich ein Zeichen am
 *     Zeilenanfang und Fettschrift. Farbe allein reicht nicht, wenn man
 *     von der Seite draufschaut.
 *   - Die Uhrzeit und der Bereich ("Arena", "Verlies") stehen gedimmt
 *     da. Sie sind wichtig zum Suchen, sollen aber nicht die Zeile
 *     beherrschen.
 *
 * Seit Auftrag 4 (Autor, 11.10.) in den Holz-Toenen des Steuerprogramms, mit Suche und Filter je Stufe
 * (Klicks, Meldungen, Warnungen, Fehler, Details). Die Zeilen bleiben im Speicher (Protokollzeile); beim
 * Aendern von Suche oder Filter wird die Anzeige aus ihnen neu aufgebaut.
 *
 * ------------------------------------------------------------------
 *  Woher die Zeilen kommen
 * ------------------------------------------------------------------
 *
 * Aus der Protokolldatei im Ordner logs, nicht aus dem laufenden
 * Programm. Damit ueberlebt das Fenster einen Neustart des Bots: legt
 * er eine neue Datei an, springt es von selbst darauf um.
 */
public final class LogFenster extends JFrame {

    /** So oft wird nach neuen Zeilen gesehen. */
    private static final int TAKT_MS = 300;

    /**
     * So oft wird nachgesehen, ob der Bot eine NEUE Protokolldatei angelegt
     * hat. Die Datei wechselt nur beim Neustart des Bots; das Anhaengen neuer
     * Zeilen braucht die Suche gar nicht.
     */
    private static final long DATEISUCHE_MS = 5_000;

    /** So viele Zeilen bleiben im Speicher. Darueber faellt oben etwas weg. */
    private static final int MAX_ZEILEN = 1500;

    private final Path ordner;
    private final JTextPane bereich = new JTextPane();
    private final JLabel jetzt = new JLabel(Sprache.t("waiting for the bot …", "wartet auf den Bot …"));
    private final JLabel kopf = Holzstil.kopfzeile(" ");
    private final JTextField suche = Holzstil.feld("", 18);
    private final JCheckBox klicks = Holzstil.kaestchen(Sprache.t("Clicks", "Klicks"), true, Sprache.t("What the bot clicks.", "Was der Bot anklickt."));
    private final JCheckBox meldungen = Holzstil.kaestchen(Sprache.t("Messages", "Meldungen"), true, Sprache.t("Normal messages.", "Gewoehnliche Meldungen."));
    private final JCheckBox warnungen = Holzstil.kaestchen(Sprache.t("Warnings", "Warnungen"), true, Sprache.t("Something was unexpected, the bot keeps going.", "Etwas war unerwartet, der Bot macht weiter."));
    private final JCheckBox fehler = Holzstil.kaestchen(Sprache.t("Errors", "Fehler"), true, Sprache.t("Something went wrong.", "Etwas ging schief."));
    private final JCheckBox details = Holzstil.kaestchen("Details", false, Sprache.t("Also the technical steps in between (DEBUG).", "Auch die technischen Zwischenschritte (DEBUG)."));
    private final JCheckBox obenauf = Holzstil.kaestchen(Sprache.t("always on top", "immer obenauf"), false, Sprache.t("Keeps the window in front of the game.", "Haelt das Fenster vor dem Spiel."));
    private final JLabel zaehler = Holzstil.hinweis(" ");
    /** Autor 24.10. (F36): die Zeile, auf die zuletzt geklickt wurde -- fuer "Was heisst das?". */
    private String gewaehlteZeile = "";

    private final Deque<Protokollzeile> zeilen = new ArrayDeque<>();
    private Path aktuelleDatei;
    private long gelesenBis;
    private long letzteDateisuche;
    private final Timer takt;

    public LogFenster(Path ordner) {
        this.ordner = ordner;

        setTitle(Sprache.t("Bot - what is happening", "Bot - was gerade passiert"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setAlwaysOnTop(false);   // Autor 11.10.: nicht ueber Discord & Co. liegen
        getContentPane().setBackground(Holzstil.GRUND);

        // ---- oben: was gerade passiert --------------------------------
        JPanel oben = Holzstil.kopfleiste(Sprache.t("Log", "Protokoll"));
        jetzt.setFont(Holzstil.GROSS);
        jetzt.setForeground(Holzstil.aufKopf());
        JPanel kopfMitte = Holzstil.flaeche(new GridLayout(2, 1, 0, 2));
        kopfMitte.add(jetzt);
        kopfMitte.add(kopf);
        oben.add(kopfMitte, BorderLayout.SOUTH);

        // ---- Suche und Filter ------------------------------------------
        JPanel filter = Holzstil.karte(new GridLayout(2, 1, 0, 2));
        JPanel sucheZeile = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        JLabel lupe = new JLabel(new Symbol(Symbol.Art.LUPE, 14));
        lupe.setForeground(Holzstil.LEISE);
        sucheZeile.add(lupe);
        suche.setToolTipText(Sprache.t("Only lines with this word (area, text or time).", "Nur Zeilen mit diesem Wort (Bereich, Text oder Uhrzeit)."));
        sucheZeile.add(suche);
        sucheZeile.add(zaehler);
        JPanel stufen = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT, 0));
        stufen.add(Holzstil.hinweis(Sprache.t("Show:", "Zeigen:")));
        for (JCheckBox k : new JCheckBox[]{klicks, meldungen, warnungen, fehler, details}) {
            k.addActionListener(e -> neuAufbauen());
            stufen.add(k);
        }
        // Autor 24.10. (F36): zwei Abkuerzungen -- nur das Wichtige (Warnungen und Fehler) oder alles ausser den Details
        JButton wichtig = Holzstil.kleinerKnopf(Sprache.t("Important only", "Nur Wichtiges"),
                Sprache.t("Shows only warnings and errors.", "Zeigt nur Warnungen und Fehler."));
        wichtig.addActionListener(e -> stufenSetzen(false, false, true, true));
        JButton alles = Holzstil.kleinerKnopf(Sprache.t("Everything", "Alles"),
                Sprache.t("Clicks, messages, warnings and errors.", "Klicks, Meldungen, Warnungen und Fehler."));
        alles.addActionListener(e -> stufenSetzen(true, true, true, true));
        stufen.add(wichtig);
        stufen.add(alles);
        filter.add(sucheZeile);
        filter.add(stufen);
        suche.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { neuAufbauen(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { neuAufbauen(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { neuAufbauen(); }
        });

        // ---- Mitte: die Zeilen -----------------------------------------
        bereich.setEditable(false);
        bereich.setBackground(Holzstil.HELL);
        bereich.setForeground(Holzstil.TEXT);
        bereich.setFont(Holzstil.FEST);
        bereich.setMargin(new Insets(8, 10, 8, 10));
        bereich.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                gewaehlteZeile = zeileBei(e.getPoint());
                if (e.getClickCount() >= 2 && !gewaehlteZeile.isBlank()) warnungErklaeren(gewaehlteZeile);
            }
        });
        JScrollPane rollen = Holzstil.rollen(bereich);
        rollen.getVerticalScrollBar().setUnitIncrement(18);

        JPanel mitte = Holzstil.grund(new BorderLayout(0, Holzstil.LUFT_KLEIN), Holzstil.LUFT);
        mitte.add(filter, BorderLayout.NORTH);
        mitte.add(rollen, BorderLayout.CENTER);

        // ---- unten: Legende und Schalter --------------------------------
        obenauf.addActionListener(e -> setAlwaysOnTop(obenauf.isSelected()));
        JButton leeren = Holzstil.kleinerKnopf(Sprache.t("Clear", "Leeren"), Sprache.t("Only the display -- the file stays.", "Nur die Anzeige -- die Datei bleibt."), Symbol.Art.KREUZ);
        leeren.addActionListener(e -> {
            zeilen.clear();
            neuAufbauen();
        });
        JPanel unten = Holzstil.grund(new BorderLayout(), 0);
        unten.setBorder(BorderFactory.createEmptyBorder(0, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));
        JPanel links = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT, 0));
        links.add(punkt(Sprache.t("Click", "Klick"), Holzstil.KLICK));
        links.add(punkt(Sprache.t("Message", "Meldung"), Holzstil.TEXT));
        links.add(punkt(Sprache.t("Warning", "Warnung"), Holzstil.WARNUNG));
        links.add(punkt(Sprache.t("Errors", "Fehler"), Holzstil.FEHLER));
        JPanel rechts = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        // Autor, 11.10.: "Im Log ein Knopf, wo man den Log kopieren kann" -- und ein Fehlerbericht (nur lokal gespeichert).
        JButton kopieren = Holzstil.kleinerKnopf(Sprache.t("Copy", "Kopieren"), Sprache.t("Copies the log (as shown) -- paste with Ctrl+V.", "Kopiert das Protokoll (wie angezeigt) -- mit Strg+V einfuegen."));
        kopieren.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(bereich.getText()), null);
            kopf.setText(Sprache.t("Log copied -- paste with Ctrl+V.", "Protokoll kopiert -- mit Strg+V einfuegen."));
        });
        JButton melden = Holzstil.kleinerKnopf(Sprache.t("Report error", "Fehler melden"), Sprache.t("Saves an error report (ZIP) with a short description on this PC -- nothing is sent.", "Speichert einen Fehlerbericht (ZIP) mit kurzer Beschreibung auf diesem PC -- nichts wird verschickt."));
        melden.addActionListener(e -> kopf.setText(fehlerMelden()
                ? Sprache.t("Report error: the form comes in the control window.", "Fehler melden: das Formular kommt im Steuerprogramm.") : Sprache.t("Report error only works while the bot is running.", "Fehler melden geht nur, wenn der Bot laeuft.")));
        // Autor 24.10. (F36): "Was heisst das?" -- die Warnung in Alltagssprache, mit einem Satz zur Loesung
        JButton hilfe = Holzstil.kleinerKnopf(Sprache.t("What does this mean?", "Was heißt das?"),
                Sprache.t("Click a line (or double-click it), then press this: explains the warning in plain words. "
                        + "Without a click it explains the last warning.",
                        "Klick auf eine Zeile (oder Doppelklick), dann diesen Knopf: erklärt die Warnung in einfachen Worten. "
                        + "Ohne Klick erklärt er die letzte Warnung."), Symbol.Art.LUPE);
        hilfe.addActionListener(e -> warnungErklaeren(gewaehlteZeile));
        rechts.add(hilfe);
        rechts.add(kopieren);
        rechts.add(melden);
        rechts.add(obenauf);
        rechts.add(leeren);
        unten.add(links, BorderLayout.WEST);
        unten.add(rechts, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(oben, BorderLayout.NORTH);
        add(mitte, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);

        // Ganz aussen am rechten Rand. Der Notizblock stellt sich links
        // daneben, damit beide gleichzeitig zu sehen sind.
        Fensterstil.anDenRand(this, Fensterstil.BREITE_LOG, 800, Fensterstil.RAND);

        takt = new Timer(TAKT_MS, e -> nachsehen());
        takt.start();
    }

    /** Ein farbiger Punkt mit Beschriftung, fuer die Legende. */
    private static JLabel punkt(String text, Color farbe) {
        JLabel l = new JLabel(text, new Symbol(Symbol.Art.PUNKT, 12), SwingConstants.LEFT);
        l.setForeground(farbe);
        l.setFont(Holzstil.KLEIN);
        l.setIconTextGap(4);
        return l;
    }

    @Override
    public void dispose() {
        takt.stop();
        super.dispose();
    }

    // ==================================================================
    //  Nachlesen
    // ==================================================================

    /** Fuer die Vorschau: sofort lesen, ohne auf den Takt zu warten. */
    public void nachsehenJetzt() {
        nachsehen();
    }

    /**
     * Holt, was seit dem letzten Blick dazugekommen ist.
     *
     * Gelesen wird ab der gemerkten Stelle, nicht die ganze Datei -- sonst
     * waere das Fenster nach einer Stunde damit beschaeftigt, jedes Mal
     * mehrere Megabyte durchzugehen.
     */
    private void nachsehen() {
        long jetztMs = System.currentTimeMillis();
        if (aktuelleDatei == null || jetztMs - letzteDateisuche >= DATEISUCHE_MS) {
            letzteDateisuche = jetztMs;
            Optional<Path> neueste = neuesteDatei();
            if (neueste.isEmpty()) {
                kopf.setText(Sprache.t("No log file yet in ", "Noch keine Protokolldatei in ") + ordner.toAbsolutePath());
                return;
            }
            Path gefunden = neueste.get();
            if (!gefunden.equals(aktuelleDatei)) {
                aktuelleDatei = gefunden;
                gelesenBis = 0;
                aufnehmen(new Protokollzeile("", "", "", "---- neuer Botstart ----"));
                kopf.setText(gefunden.getFileName() + Sprache.t("   ·   updates by itself", "   –   aktualisiert sich von selbst"));
            }
        }
        if (aktuelleDatei == null) return;
        Path datei = aktuelleDatei;

        try (RandomAccessFile f = new RandomAccessFile(datei.toFile(), "r")) {
            long laenge = f.length();
            if (laenge < gelesenBis) gelesenBis = 0;   // Datei wurde geleert
            if (laenge == gelesenBis) return;

            f.seek(gelesenBis);
            byte[] neu = new byte[(int) Math.min(laenge - gelesenBis, 200_000)];
            f.readFully(neu);
            gelesenBis += neu.length;

            boolean etwasNeu = false;
            for (String zeile : new String(neu, StandardCharsets.UTF_8).split("\n")) {
                Protokollzeile z = Protokollzeile.lesen(zeile);
                if (z == null) continue;
                aufnehmen(z);
                if (z.passt(filter(), suche.getText())) {
                    anhaengen(z);
                    etwasNeu = true;
                }
                if (!z.istKopfzeile() && !z.stufe().equals("DEBUG")) {
                    jetzt.setText(z.text().length() > 90 ? z.text().substring(0, 88) + "…" : z.text());
                    jetzt.setForeground(z.wichtig() ? farbe(z) : Holzstil.aufKopf());
                }
            }
            if (etwasNeu) {
                kuerzen();
                bereich.setCaretPosition(bereich.getDocument().getLength());
                zaehlerNeu();
            }
        } catch (Exception ignored) {
            // Der Bot schreibt gerade -- beim naechsten Takt noch einmal.
        }
    }

    private void aufnehmen(Protokollzeile z) {
        zeilen.addLast(z);
        while (zeilen.size() > MAX_ZEILEN) zeilen.removeFirst();
    }

    private Optional<Path> neuesteDatei() {
        try (var stream = Files.list(ordner)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("bot_"))
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ==================================================================
    //  Autor 24.10. (Ideenliste F36): Warnungen in Alltagssprache, Filter Wichtig / Alles
    // ==================================================================

    private void stufenSetzen(boolean k, boolean m, boolean w, boolean f) {
        klicks.setSelected(k);
        meldungen.setSelected(m);
        warnungen.setSelected(w);
        fehler.setSelected(f);
        details.setSelected(false);
        neuAufbauen();
    }

    /** Der Text der Zeile, auf die an dieser Stelle geklickt wurde. */
    private String zeileBei(Point p) {
        try {
            int pos = bereich.viewToModel2D(p);
            int von = javax.swing.text.Utilities.getRowStart(bereich, pos);
            int bis = javax.swing.text.Utilities.getRowEnd(bereich, pos);
            if (von < 0 || bis < von) return "";
            return bereich.getText(von, bis - von).strip();
        } catch (BadLocationException | RuntimeException e) {
            return "";
        }
    }

    /** Zeigt die Erklaerung zu einer Zeile; ohne Zeile zur letzten Warnung oder dem letzten Fehler. */
    private void warnungErklaeren(String zeile) {
        String text = zeile == null ? "" : zeile;
        if (text.isBlank()) {
            for (java.util.Iterator<Protokollzeile> it = zeilen.descendingIterator(); it.hasNext(); ) {
                Protokollzeile z = it.next();
                if (z.wichtig()) {
                    text = z.text();
                    break;
                }
            }
        }
        if (text.isBlank()) {
            kopf.setText(Sprache.t("No warning in the log yet -- click a line first.", "Noch keine Warnung im Protokoll -- klick erst auf eine Zeile."));
            return;
        }
        JOptionPane.showMessageDialog(this, Warnhilfe.alsHtml(text, Warnhilfe.finde(text)),
                Sprache.t("What does this mean?", "Was heißt das?"), JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================================================================
    //  Anzeigen
    // ==================================================================

    private Protokollzeile.Filter filter() {
        return new Protokollzeile.Filter(klicks.isSelected(), meldungen.isSelected(), warnungen.isSelected(),
                fehler.isSelected(), details.isSelected());
    }

    private static Color farbe(Protokollzeile z) {
        return switch (z.stufe()) {
            case "ACTION" -> Holzstil.KLICK;
            case "WARN" -> Holzstil.WARNUNG;
            case "ERROR" -> Holzstil.FEHLER;
            case "DEBUG", "" -> Holzstil.LEISE;
            default -> Holzstil.TEXT;
        };
    }

    /** Die Anzeige aus den gemerkten Zeilen neu aufbauen (Suche oder Filter geaendert). */
    private void neuAufbauen() {
        bereich.setText("");
        Protokollzeile.Filter f = filter();
        String s = suche.getText();
        List<Protokollzeile> passend = new ArrayList<>();
        for (Protokollzeile z : zeilen) if (z.passt(f, s)) passend.add(z);
        for (Protokollzeile z : passend) anhaengen(z);
        bereich.setCaretPosition(bereich.getDocument().getLength());
        zaehlerNeu();
    }

    private void zaehlerNeu() {
        int gezeigt = bereich.getStyledDocument().getDefaultRootElement().getElementCount() - 1;
        zaehler.setText(gezeigt == zeilen.size() ? zeilen.size() + Sprache.t(" lines", " Zeilen") : gezeigt + Sprache.t(" of", " von ") + zeilen.size() + Sprache.t(" lines", " Zeilen"));
    }

    private void anhaengen(Protokollzeile z) {
        if (z.istKopfzeile()) {
            schreiben(z.text() + "\n", Holzstil.LEISE, false);
            return;
        }
        Color farbe = farbe(z);
        boolean wichtig = z.wichtig();
        schreiben(z.zeichen() + " ", farbe, wichtig);
        schreiben(z.zeitKurz() + "  ", Holzstil.LEISE, false);
        if (!z.bereich().isEmpty()) schreiben(z.bereich() + "  ", Holzstil.LEISE, true);
        schreiben(z.text() + "\n", farbe, wichtig);
    }

    private void schreiben(String text, Color farbe, boolean fett) {
        StyledDocument doc = bereich.getStyledDocument();
        SimpleAttributeSet stil = new SimpleAttributeSet();
        StyleConstants.setForeground(stil, farbe);
        StyleConstants.setBold(stil, fett);
        StyleConstants.setSpaceBelow(stil, 3f);
        try {
            doc.insertString(doc.getLength(), text, stil);
        } catch (BadLocationException ignored) {
            // Kann nur passieren, wenn nebenher geleert wurde.
        }
    }

    /** Haelt die Anzeige leichtgewichtig, auch nach Stunden. */
    private void kuerzen() {
        StyledDocument doc = bereich.getStyledDocument();
        Element wurzel = doc.getDefaultRootElement();
        int zuviel = wurzel.getElementCount() - MAX_ZEILEN;
        if (zuviel <= 0) return;
        try {
            doc.remove(0, wurzel.getElement(zuviel - 1).getEndOffset());
        } catch (BadLocationException ignored) {
            // Nicht schlimm -- beim naechsten Mal wieder.
        }
    }

    // ==================================================================
    //  Von aussen auf- und zumachen
    // ==================================================================

    private static LogFenster offen;

    /** Vom Steuerprogramm gesetzt: schickt einen Befehl wie in Discord. */
    public static volatile java.util.function.Consumer<String> befehlSender;

    /** Vom Steuerprogramm gesetzt (16.10.): Fehlerbericht, wird nur lokal gespeichert. */
    public static volatile Runnable berichtDialog;

    /** "Fehler melden" -- lokaler Fehlerbericht. false, wenn niemand ihn annimmt. */
    public static boolean fehlerMelden() {
        Runnable dialog = berichtDialog;
        if (dialog == null) return false;
        dialog.run();
        return true;
    }

    /** Macht das Fenster auf. Zweimal aufmachen holt es nur nach vorn. */
    public static synchronized void aufmachen(Path ordner) {
        SwingUtilities.invokeLater(() -> {
            if (offen != null && offen.isDisplayable()) {
                offen.toFront();
                return;
            }
            offen = new LogFenster(ordner);
            offen.setVisible(true);
        });
    }

    /** Macht es wieder zu. */
    public static synchronized void zumachen() {
        SwingUtilities.invokeLater(() -> {
            if (offen != null) {
                offen.dispose();
                offen = null;
            }
        });
    }

    public static synchronized boolean istOffen() {
        return offen != null && offen.isDisplayable();
    }

    public static void main(String[] args) {
        Path ordner = Path.of(args.length > 0 ? args[0] : "logs");
        SwingUtilities.invokeLater(() -> new LogFenster(ordner).setVisible(true));
    }
}
