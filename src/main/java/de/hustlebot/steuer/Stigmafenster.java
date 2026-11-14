package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;
import de.hustlebot.core.Stigmaplan;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Das Auswahlfenster vor "Stigmas los" (Autor 11.10. abends: "Beim Stigmas loeschen ein Auswahlfenster mit
 * Haekchen je Stigma-Set ... Beim Stigmas leveln auswaehlen, auf welche Werte er achtet. Alles gespeichert.").
 *
 *  - Loeschen (seit 15.10. umgedreht, Autor): je Set ein Haekchen = LOESCHEN (nur Stigmas mit Level 1-79);
 *    ohne Haken bleibt es liegen. Gespeichert als "behalten" (= alle ohne Haken) in stigma-einstellungen.txt.
 *  - Leveln: fuer den gewaehlten Titan Haupt- und Zweitwert, "beide gleich wichtig" und die Reihenfolge der
 *    uebrigen Werte (je Titan getrennt, Stigmaplan.wahlSetzen); dazu fuer alle Titanen die Hochlevel-Regeln und
 *    die Sorten (Mind, Will ...).
 *
 * "Starten" speichert und schickt den Befehl, "Nur speichern" speichert, "Abbrechen" laesst alles, wie es war.
 * Das Fenster liegt obenauf -- sonst verschwindet es hinter dem Spiel, das der Motor nach vorn holt.
 */
final class Stigmafenster extends JDialog {

    private Runnable speichern = () -> { };
    /** Prueft vor dem Speichern; liefert false, wenn etwas nicht stimmt (und hat dann schon gefragt/gemeldet). */
    private java.util.function.BooleanSupplier pruefen = () -> true;

    private Stigmafenster(Window owner, String titel) {
        super(owner, titel, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        getContentPane().setBackground(Holzstil.GRUND);
        setLayout(new BorderLayout());
    }

    // ==================================================================
    //  Loeschen: welche Sets nie geloescht werden
    // ==================================================================

    /** Zeigt die Auswahl fuers Loeschen; "Starten" speichert und ruft starten auf. */
    static void loeschen(Window owner, Runnable starten) {
        loeschenBauen(owner, starten).setVisible(true);
    }

    /** Baut das Fenster fertig, zeigt es aber nicht (auch fuer die Vorschaubilder). */
    static Stigmafenster loeschenBauen(Window owner, Runnable starten) {
        Stigmafenster f = new Stigmafenster(owner, Sprache.t("Delete stigmas – selection", "Stigmas löschen – Auswahl"));
        Set<String> behalten = Stigmaplan.behalten();

        // Autor 15.10.: lieber auswaehlen, WELCHE geloescht werden -- Haken = loeschen (gespeichert bleibt intern
        // "behalten" = alle Sets ohne Haken; unbekannte Sets werden nie geloescht).
        JPanel liste = Holzstil.karte(Sprache.t("Ticked = delete these stigmas", "Haken = diese Stigmas löschen"), new GridLayout(0, 4, Holzstil.LUFT, 2));
        List<JCheckBox> kaestchen = new ArrayList<>();
        for (String set : Stigmaplan.SETS) {
            JCheckBox k = Holzstil.kaestchen(Stigmaplan.gross(set), !behalten.contains(set),
                    Sprache.t("Ticked: the bot deletes " + Stigmaplan.gross(set) + " stigmas (1 star, level 1-79). Unticked ones are kept.",
                            "Haken: der Bot löscht " + Stigmaplan.gross(set) + "-Stigmas (1 Stern, Level 1-79). Ohne Haken bleiben sie liegen."));
            kaestchen.add(k);
            liste.add(k);
        }

        // Freunde-Bot: Quick add laeuft beim Loeschen immer (StigmaMode), einen Schalter dafuer gibt es
        // dort nicht -- darum hier kein Kaestchen.
        JPanel schnell = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        JButton standard = Holzstil.kleinerKnopf(Sprache.t("Default (all except Bull, Dragon, Wolf)", "Standard (alles außer Bull, Dragon, Wolf)"),
                Sprache.t("Delete all except Bull, Dragon and Wolf.", "Alles löschen außer Bull, Dragon und Wolf."), Symbol.Art.NEUSTART);
        standard.addActionListener(e -> {
            for (int i = 0; i < kaestchen.size(); i++) {
                String s = Stigmaplan.SETS.get(i);
                kaestchen.get(i).setSelected(!(s.equals("bull") || s.equals("dragon") || s.equals("wolf")));
            }
        });
        JButton alle = Holzstil.kleinerKnopf(Sprache.t("Delete all", "Alle löschen"), Sprache.t("Tick everything.", "Haken überall."), Symbol.Art.HAKEN);
        alle.addActionListener(e -> kaestchen.forEach(k -> k.setSelected(true)));
        JButton keine = Holzstil.kleinerKnopf(Sprache.t("Delete nothing", "Nichts löschen"),
                Sprache.t("All ticks off -- then it deletes nothing.", "Alle Haken weg -- dann löscht er nichts."), Symbol.Art.KREUZ);
        // Code-Pruefung 15.10.: "Nichts loeschen" liess Quick add an -- dann zerlegte er trotzdem alle blauen und lila.
        keine.addActionListener(e -> {
            kaestchen.forEach(k -> k.setSelected(false));
        });
        schnell.add(standard);
        schnell.add(alle);
        schnell.add(keine);

        JLabel hinweis = Holzstil.hinweis(Sprache.t("<html>With a tick the bot deletes the set – as before, only stigmas with "
                + "<b>level 1-79</b>. Everything without a tick is kept. The selection stays saved.</html>",
                "<html>Mit Haken löscht der Bot das Set – wie bisher nur Stigmas mit "
                + "<b>Level 1-79</b>. Alles ohne Haken bleibt liegen. Die Auswahl bleibt gespeichert.</html>"));

        f.pruefen = () -> {
            // Code-Pruefung 16.10.: ohne Haken startet nichts -- "Nichts loeschen" darf nie doch etwas zerlegen.
            boolean irgendeins = kaestchen.stream().anyMatch(AbstractButton::isSelected);
            if (!irgendeins) {
                JOptionPane.showMessageDialog(f, Sprache.t("No set ticked -- nothing will be deleted, the bot does not start.",
                        "Kein Set angehakt -- es wird nichts gelöscht, der Bot startet nicht."), f.getTitle(), JOptionPane.INFORMATION_MESSAGE);
                return false;
            }
            List<String> weg = new ArrayList<>();
            for (int i = 0; i < kaestchen.size(); i++) {
                String s = Stigmaplan.SETS.get(i);
                if (kaestchen.get(i).isSelected() && (s.equals("bull") || s.equals("dragon") || s.equals("wolf"))) {
                    weg.add(Stigmaplan.gross(s));
                }
            }
            if (!weg.isEmpty() && !frage(f, Sprache.t(String.join(", ", weg) + " is ticked -- the bot deletes these stigmas "
                    + "(1 star, level 1-79).\n\nReally save it like this?", String.join(", ", weg) + " hat einen Haken – der Bot löscht diese Stigmas dann "
                    + "(1 Stern, Level 1-79).\n\nWirklich so speichern?"))) return false;
            // Im Freunde-Bot legt der Loeschlauf mit Quick add IMMER auch alle blauen und lila Stigmas dazu (StigmaMode).
            return frage(f, Sprache.t("Note: with Quick add the bot also disassembles ALL blue and purple stigmas, "
                    + "no matter which set.\n\nStart deleting now?",
                    "Achtung: Mit Quick add zerlegt der Bot auch ALLE blauen und lila Stigmas, egal welches Set.\n\nJetzt löschen?"));
        };
        f.speichern = () -> {
            Set<String> neu = new LinkedHashSet<>();
            for (int i = 0; i < kaestchen.size(); i++) if (!kaestchen.get(i).isSelected()) neu.add(Stigmaplan.SETS.get(i));
            Stigmaplan.setzen(null, null, null, null, neu);
        };
        f.fertig(starten, hinweis, schnell, liste);
        return f;
    }

    // Freunde-Bot: das Fenster "Stigmas filtern" (Auswahl je Titan) gibt es hier nicht -- die
    // Stigma-Logik der Freunde liest diese Auswahl nicht. Der Knopf schickt direkt "!stigmas leveln".

    // ==================================================================
    //  Gemeinsam: Inhalt untereinander, unten Starten / Nur speichern / Abbrechen
    // ==================================================================

    private void fertig(Runnable starten, JComponent... teile) {
        JPanel mitte = Holzstil.grund(null, Holzstil.LUFT);
        mitte.setLayout(new BoxLayout(mitte, BoxLayout.Y_AXIS));
        for (JComponent t : teile) {
            t.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (mitte.getComponentCount() > 0) mitte.add(Box.createVerticalStrut(Holzstil.LUFT_KLEIN));
            mitte.add(t);
        }

        JButton los = Holzstil.knopf(Sprache.t("Start", "Starten"), Sprache.t("Saves the selection and starts.", "Speichert die Auswahl und startet."),
                Holzstil.GUT, Symbol.Art.START);
        los.addActionListener(e -> {
            if (!pruefen.getAsBoolean()) return;
            speichern.run();
            dispose();
            starten.run();
        });
        JButton nurSpeichern = Holzstil.kleinerKnopf(Sprache.t("Save only", "Nur speichern"), Sprache.t("Saves, but starts nothing.", "Speichert, startet aber nichts."), Symbol.Art.HAKEN);
        nurSpeichern.addActionListener(e -> {
            if (!pruefen.getAsBoolean()) return;
            speichern.run();
            dispose();
        });
        JButton abbrechen = Holzstil.kleinerKnopf(Sprache.t("Cancel", "Abbrechen"), Sprache.t("Save nothing, start nothing.", "Nichts speichern, nichts starten."), Symbol.Art.KREUZ);
        abbrechen.addActionListener(e -> dispose());
        JPanel unten = Holzstil.grund(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0), 0);
        unten.setBorder(BorderFactory.createEmptyBorder(0, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));
        unten.add(abbrechen);
        unten.add(nurSpeichern);
        unten.add(los);

        add(mitte, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(los);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(getOwner());
    }

    private static boolean frage(Component owner, String text) {
        JOptionPane pane = new JOptionPane(text, JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog d = pane.createDialog(owner, Sprache.t("Delete stigmas", "Stigmas löschen"));
        d.setAlwaysOnTop(true);
        d.setVisible(true);
        d.dispose();
        return pane.getValue() instanceof Integer i && i == JOptionPane.YES_OPTION;
    }

    private static void melde(Component owner, String text) {
        JOptionPane pane = new JOptionPane(text, JOptionPane.INFORMATION_MESSAGE);
        JDialog d = pane.createDialog(owner, "Stigmas");
        d.setAlwaysOnTop(true);
        d.setVisible(true);
        d.dispose();
    }
}
