package de.hustlebot.tools;

import de.hustlebot.bot.BotEngine;
import de.hustlebot.bot.SpielWaechter;
import de.hustlebot.core.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import de.hustlebot.core.Sprache;

/**
 * Das kleine Fenster "Hustle Castle Bot" -- solange es offen ist, laeuft der Bot.
 *
 * Autor, 13.09.: "es soll nicht mehr im hintergrund laufen sondern so dass
 * ich es sehen kann wenn es laeuft um es auszuschalten oder zu schliessen".
 * Vorher startete der Bot nach dem Hochfahren als javaw ganz ohne Fenster;
 * zu sehen war er nur im Task-Manager.
 *
 * Oben steht, was der Bot gerade tut, darunter, ob Hustle Castle offen ist
 * -- und wenn nicht, wann sich der Bot beendet (SpielWaechter). Zwei Knoepfe:
 * "Stoppen" haelt nur den laufenden Modus an, "Beenden" macht das ganze
 * Programm zu. Das X oben rechts tut dasselbe wie "Beenden".
 *
 * BEWUSST NICHT "IMMER OBENAUF". Ein Fenster, das sich ueber alles legt,
 * stoert jedes Spiel im Vollbild -- und genau darueber hatte sich Autor
 * beschwert. Laeuft ein Modus, holt der Bot Hustle Castle ohnehin nach
 * vorn, und dieses Fenster liegt dahinter. Beim Aufgehen nimmt es auch
 * niemandem den Tastaturfokus weg -- es geht ja beim Hochfahren auf.
 *
 * DIE KNOEPFE WIRKEN SCHON BEIM DRUECKEN, nicht erst beim Loslassen.
 * Laeuft ein Modus, holt der Motor Hustle Castle alle paar Zehntelsekunden
 * wieder nach vorn. Ein Klick hierher koennte sonst beim Loslassen schon
 * auf dem Spiel landen.
 */
public final class BotFenster extends JFrame {

    private final BotEngine motor;
    private final SpielWaechter waechter;

    private final JLabel zustand = new JLabel(" ");
    private final JLabel details = new JLabel(" ");
    private final JLabel spiel = new JLabel(" ");
    private final JButton pause = new JButton(de.hustlebot.core.Sprache.t("Pause", "Pause"));
    private final JButton stoppen = new JButton(de.hustlebot.core.Sprache.t("Stop", "Stoppen"));
    private final JButton beenden = new JButton(de.hustlebot.core.Sprache.t("Quit", "Beenden"));

    private BotFenster(BotEngine motor, SpielWaechter waechter) {
        this.motor = motor;
        this.waechter = waechter;

        // "Hustle Castle Bot" -- GameWindow haelt das ausdruecklich NICHT fuer
        // das Spiel (siehe findByPartialTitle). Nie "Hustle Castle - Bot"
        // nennen: Das wuerde als Spielfenster erkannt.
        setTitle("Hustle Castle Bot");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                beendenGewuenscht();
            }
        });
        setAutoRequestFocus(false);
        getContentPane().setBackground(Fensterstil.GRUND);

        zustand.setFont(Fensterstil.GROSS);
        zustand.setForeground(Fensterstil.TEXT);
        details.setFont(Fensterstil.KLEIN);
        details.setForeground(Fensterstil.BEREICH);
        spiel.setFont(Fensterstil.KLEIN);

        JPanel oben = new JPanel(new GridLayout(3, 1, 0, 3));
        oben.setBackground(Fensterstil.KOPFGRUND);
        oben.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        oben.add(zustand);
        oben.add(details);
        oben.add(spiel);

        pause.setToolTipText(de.hustlebot.core.Sprache.t("Pauses the running mode. Press again to carry on right there.", "Haelt den laufenden Modus voruebergehend an. Nochmal druecken setzt genau dort fort."));
        stoppen.setToolTipText(de.hustlebot.core.Sprache.t("Stops only the running mode. The bot stays open.", "Haelt nur den laufenden Modus an. Der Bot bleibt offen."));
        beenden.setToolTipText(de.hustlebot.core.Sprache.t("Closes the whole bot.", "Macht den ganzen Bot zu."));
        beiDruck(pause, this::pauseGewuenscht);
        beiDruck(stoppen, this::stoppenGewuenscht);
        beiDruck(beenden, this::beendenGewuenscht);

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        knoepfe.setBackground(Fensterstil.GRUND);
        knoepfe.add(pause);
        knoepfe.add(stoppen);
        knoepfe.add(beenden);

        setLayout(new BorderLayout());
        add(oben, BorderLayout.CENTER);
        add(knoepfe, BorderLayout.SOUTH);

        // Unten rechts, ueber der Taskleiste.
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        setSize(490, 170);
        setLocation(schirm.x + schirm.width - getWidth() - Fensterstil.RAND,
                    schirm.y + schirm.height - getHeight() - Fensterstil.RAND);

        aktualisieren();
        new Timer(1000, e -> aktualisieren()).start();
    }

    /** Macht das Fenster auf. Aus jedem Faden aufrufbar. */
    public static void zeigen(BotEngine motor, SpielWaechter waechter) {
        SwingUtilities.invokeLater(() -> new BotFenster(motor, waechter).setVisible(true));
    }

    /**
     * Loest beim Herunterdruecken aus (siehe oben). Die Tastatur geht weiter
     * ueber den normalen Weg: Leertaste auf dem Knopf.
     */
    private static void beiDruck(JButton knopf, Runnable aktion) {
        knopf.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (knopf.isEnabled() && SwingUtilities.isLeftMouseButton(e)) aktion.run();
            }
        });
        knopf.addActionListener(e -> {
            // Nur fuer die Tastatur -- ein Mausklick ist oben schon passiert.
            if ((e.getModifiers() & java.awt.event.ActionEvent.MOUSE_EVENT_MASK) == 0) {
                aktion.run();
            }
        });
    }

    private boolean laeuft() {
        BotEngine.State z = motor.state();
        return z == BotEngine.State.LAEUFT || z == BotEngine.State.PAUSIERT;
    }

    private void aktualisieren() {
        BotEngine.State z = motor.state();
        zustand.setText(switch (z) {
            case LAEUFT -> de.hustlebot.core.Sprache.t("The bot is playing", "Der Bot spielt");
            case PAUSIERT -> de.hustlebot.core.Sprache.t("Paused", "Pausiert");
            case BEREIT -> de.hustlebot.core.Sprache.t("Ready – waiting for a command", "Bereit – wartet auf einen Befehl");
            case FERTIG -> de.hustlebot.core.Sprache.t("Done", "Fertig");
            case GESTOPPT -> de.hustlebot.core.Sprache.t("Stopped", "Gestoppt");
            case FEHLER -> de.hustlebot.core.Sprache.t("Stopped with an error", "Abgebrochen");
            default -> z.name();
        });
        String text = motor.statusText();
        details.setText(text == null || text.isBlank() ? " " : text);
        details.setToolTipText(text);

        long rest = waechter.beendetInMs();
        if (rest >= 0) {
            long s = rest / 1000;
            spiel.setText(String.format(de.hustlebot.core.Sprache.t("Hustle Castle is closed – the bot quits in %d:%02d", "Hustle Castle ist zu – der Bot beendet sich in %d:%02d"),
                    s / 60, s % 60));
            spiel.setForeground(Fensterstil.WARNUNG);
        } else if (waechter.spielOffen()) {
            spiel.setText(de.hustlebot.core.Sprache.t("Hustle Castle is open", "Hustle Castle ist offen"));
            spiel.setForeground(Fensterstil.KLICK);
        } else {
            spiel.setText(de.hustlebot.core.Sprache.t("Starting Hustle Castle …", "Hustle Castle wird gestartet …"));
            spiel.setForeground(Fensterstil.BEREICH);
        }

        stoppen.setEnabled(laeuft());
        pause.setEnabled(laeuft());
        pause.setText(z == BotEngine.State.PAUSIERT ? de.hustlebot.core.Sprache.t("Resume", "Fortsetzen") : de.hustlebot.core.Sprache.t("Pause", "Pause"));
    }

    private void pauseGewuenscht() {
        motor.togglePause();
        // Sofort nachziehen, damit der Knopf gleich "Fortsetzen"/"Pause" zeigt,
        // ohne auf den Sekundentakt zu warten.
        aktualisieren();
    }

    private void stoppenGewuenscht() {
        motor.stop(Sprache.t("Stopped in the bot window", "Im Bot-Fenster gestoppt"));
    }

    private void beendenGewuenscht() {
        if (laeuft()) {
            // ZUERST anhalten, dann fragen. Solange ein Modus laeuft, holt
            // der Motor das Spiel immer wieder nach vorn -- die Frage laege
            // sonst gleich hinter Hustle Castle, und niemand kaeme an sie
            // heran.
            motor.stop(Sprache.t("Quit in the bot window", "Beenden im Bot-Fenster"));
            int antwort = JOptionPane.showConfirmDialog(this,
                    de.hustlebot.core.Sprache.t("The running mode is stopped.\n\nQuit the bot completely now?", "Der laufende Modus ist angehalten.\n\nDen Bot jetzt ganz beenden?"),
                    de.hustlebot.core.Sprache.t("Quit Hustle Castle Bot?", "Hustle Castle Bot beenden?"), JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (antwort != JOptionPane.YES_OPTION) return;
        }
        Log.info("Ende", Sprache.t("Quit in the bot window.", "Beenden im Bot-Fenster."));
        // Nicht im Fenster-Faden: Beim Beenden wird noch aufgeraeumt, und das
        // Fenster soll dabei nicht einfrieren.
        new Thread(() -> System.exit(0), "fenster-beenden").start();
    }
}
