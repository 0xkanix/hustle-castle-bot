package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Log;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Autor, 11.10.: "Unten bei meinem Screenshot ist noch Platz -- vielleicht kann man da einen Log reinquetschen."
 *
 * Eine schmale Leiste im freien Streifen unter dem Spiel: die letzten Zeilen aus dem Protokoll, ohne Klicks und
 * Details; Warnungen orange, Fehler rot. Sie liegt nie ueber dem Spiel -- der Bot klickt mit der echten Maus, ein
 * Fenster darueber finge die Klicks ab. Lage und Groesse merkt sie sich wie das Steuerfenster. Wer sie zumacht,
 * holt sie unter Einstellungen -> Darstellung wieder.
 */
final class Protokollleiste extends JFrame implements Steuerprogramm.Abmeldbar {

    /** So viele Zeilen werden gezeigt; der Rest ist mit dem Rad erreichbar. */
    private static final int ZEILEN = 40;

    private final JEditorPane text = new JEditorPane("text/html", "");
    private final JScrollPane rollen;
    private final Timer takt = new Timer(1000, e -> nachziehen());
    private Log.Entry zuletzt;

    Protokollleiste(Steuereinstellungen einst, boolean obenauf) {
        setTitle(Sprache.t("Log", "Protokoll"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                setVisible(false);
                einst.setzen(Steuereinstellungen.PROTOKOLLLEISTE, false);
            }
        });
        setAlwaysOnTop(obenauf);
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);
        text.setEditable(false);
        text.setBackground(Holzstil.GRUND);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        text.setFont(Holzstil.KLEIN);
        text.setForeground(Holzstil.TEXT);
        rollen = Holzstil.rollenOhneRahmen(text);
        setLayout(new BorderLayout());
        add(rollen, BorderLayout.CENTER);
        // Wie im Protokoll-Fenster: kopieren und "Fehler melden" (lokaler Bericht), Autor 11.10.
        JButton kopieren = Holzstil.kleinerKnopf(Sprache.t("Copy", "Kopieren"), Sprache.t("The last 300 lines of the log -- paste with Ctrl+V.", "Die letzten 300 Zeilen des Protokolls -- mit Strg+V einfuegen."));
        kopieren.addActionListener(e -> {
            StringBuilder b = new StringBuilder();
            List<Log.Entry> alle = Log.snapshot();
            for (Log.Entry z : alle.subList(Math.max(0, alle.size() - 300), alle.size())) {
                b.append(z.time()).append("  [").append(z.level()).append("] ").append(z.source()).append(": ").append(z.message()).append('\n');
            }
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(b.toString()), null);
        });
        JButton melden = Holzstil.kleinerKnopf(Sprache.t("Report error", "Fehler melden"), Sprache.t("Saves an error report (ZIP) with a short description on this PC -- nothing is sent.", "Speichert einen Fehlerbericht (ZIP) mit kurzer Beschreibung auf diesem PC -- nichts wird verschickt."));
        melden.addActionListener(e -> de.hustlebot.tools.LogFenster.fehlerMelden());
        JPanel knoepfe = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        knoepfe.add(kopieren);
        knoepfe.add(melden);
        add(knoepfe, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(300, 100));
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) {
            zuletzt = null;
            nachziehen();
            takt.start();
        } else {
            takt.stop();
        }
    }

    @Override
    public void abmelden() {
        takt.stop();
    }

    private void nachziehen() {
        List<Log.Entry> alle = Log.snapshot().stream()
                .filter(e -> e.level() != Log.Level.DEBUG && e.level() != Log.Level.ACTION).toList();
        if (alle.isEmpty() || alle.get(alle.size() - 1) == zuletzt) return;
        zuletzt = alle.get(alle.size() - 1);
        StringBuilder h = new StringBuilder("<html><body style='margin:2px 6px'>");
        for (Log.Entry e : alle.subList(Math.max(0, alle.size() - ZEILEN), alle.size())) {
            Color farbe = e.level() == Log.Level.ERROR ? Holzstil.FEHLER
                    : e.level() == Log.Level.WARN ? Holzstil.WARNUNG : Holzstil.TEXT;
            h.append("<div style='color:").append(hex(farbe)).append("'><span style='color:").append(hex(Holzstil.LEISE))
                    .append("'>").append(e.time().length() >= 8 ? e.time().substring(0, 8) : e.time()).append("</span>&nbsp; <b>")
                    .append(html(e.source())).append("</b>: ").append(html(e.message())).append("</div>");
        }
        text.setText(h.append("</body></html>").toString());
        SwingUtilities.invokeLater(() -> {
            JScrollBar s = rollen.getVerticalScrollBar();
            s.setValue(s.getMaximum());
        });
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String html(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
