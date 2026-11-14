package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Antworten auf Knoepfe des Steuerprogramms (Autor 14.10.: "Den Meldungen-Reiter kannst du rausnehmen, komplett").
 *
 * Statt eines Reiters, in den man erst wechseln muss, geht dieses kleine Fenster nur dann auf, wenn ein Knopf
 * im Programm etwas geschickt hat und der Bot darauf antwortet -- "Team zeigen", "Anzeigen" bei einer
 * Einstellung, "Hilfe", Rueckfragen mit Knoepfen. Vorher passierte bei "Team zeigen" scheinbar nichts: die Antwort
 * stand im Reiter Meldungen. Startbefehle und Profile oeffnen es nicht (dann gehoert die Maus dem Bot).
 */
final class Antwortfenster extends JFrame {

    private static final int HOECHSTENS = 30;

    private final Steuerprogramm steuer;
    private final JPanel karten = new Holzstil.Rollflaeche(null);
    private final JScrollPane rollen;
    private int zahl;

    Antwortfenster(Steuerprogramm steuer) {
        this.steuer = steuer;
        setTitle(Sprache.t("Hustle Castle Bot – Replies", "Hustle Castle Bot – Antworten"));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
            }
        });
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);
        karten.setLayout(new BoxLayout(karten, BoxLayout.Y_AXIS));
        karten.setBorder(BorderFactory.createEmptyBorder(Holzstil.LUFT_KLEIN, Holzstil.LUFT_KLEIN, Holzstil.LUFT_KLEIN, Holzstil.LUFT_KLEIN));
        rollen = Holzstil.rollenOhneRahmen(karten);
        JPanel unten = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, Holzstil.LUFT_KLEIN));
        JButton leeren = Holzstil.kleinerKnopf(Sprache.t("Clear", "Leeren"),
                Sprache.t("Only this view -- nothing in Discord.", "Nur diese Anzeige -- nichts in Discord."), Symbol.Art.KREUZ);
        leeren.addActionListener(e -> {
            karten.removeAll();
            zahl = 0;
            karten.revalidate();
            karten.repaint();
        });
        JButton zu = Holzstil.kleinerKnopf(Sprache.t("Close", "Schliessen"),
                Sprache.t("Closes the window. It opens again as soon as a button gets a reply.",
                        "Fenster zu. Es geht wieder auf, sobald ein Knopf eine Antwort bekommt."));
        zu.addActionListener(e -> setVisible(false));
        unten.add(leeren);
        unten.add(zu);
        setLayout(new BorderLayout());
        add(rollen, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        // In die Spalte des Steuerfensters (links), nie ueber das Spiel.
        Holzstil.lageMerken(this, steuer.einst, "antworten", new Rectangle(0, 420, 760, 380));
    }

    /** Eine Antwort anhaengen und das Fenster zeigen (im Fenster-Faden aufrufen). */
    void zeigen(Meldung m) {
        JComponent k = Steuerfenster.karteBauen(m, b -> steuer.senden(b, Sprache.t("Button in reply", "Knopf in Antwort")));
        karten.add(k);
        karten.add(Box.createVerticalStrut(Holzstil.LUFT_KLEIN));
        zahl++;
        while (zahl > HOECHSTENS && karten.getComponentCount() >= 2) {
            karten.remove(0);
            karten.remove(0);
            zahl--;
        }
        karten.revalidate();
        karten.repaint();
        if (!isVisible()) setVisible(true);
        toFront();
        SwingUtilities.invokeLater(() -> {
            JScrollBar s = rollen.getVerticalScrollBar();
            s.setValue(s.getMaximum());
        });
    }
}
