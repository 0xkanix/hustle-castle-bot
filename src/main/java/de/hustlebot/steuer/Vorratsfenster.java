package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Das Vorrat-Fenster (Burg-Stand) als Tabelle (Auftrag 4): Name und Wert je Zeile, Zahlen rechts mit
 * Tausenderpunkten, jede zweite Zeile leicht getoent, Kopfzeile zum Sortieren. Darunter steht, wann
 * abgelesen wurde; "Neu holen" schickt !stand (liest den Bildschirm -- nur, wenn der Bot frei ist).
 */
final class Vorratsfenster extends JFrame implements Steuerprogramm.Abmeldbar {

    private static final String[] TITEL = {"Burg-Stand", "Castle status"};

    private final Meldungen meldungen;
    private final JLabel kopfzeile = Holzstil.kopfzeile(Sprache.t("Nothing read yet", "Noch nichts abgelesen"));
    private final JLabel stand = Holzstil.hinweis(" ");
    private final DefaultTableModel modell = new DefaultTableModel(new Object[]{Sprache.t("What", "Was"), Sprache.t("Value", "Wert")}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
        @Override public Class<?> getColumnClass(int c) { return c == 1 ? Object.class : String.class; }
    };
    private final JTable tabelle = Holzstil.tabelle(modell);
    private final JLabel hinweis = Holzstil.hinweis(" ");
    private final Consumer<Meldung> zuhoerer;

    Vorratsfenster(Meldungen meldungen, Steuerprogramm steuer, boolean obenauf) {
        this.meldungen = meldungen;
        setTitle(Sprache.t("Resources (castle status)", "Vorrat (Burg-Stand)"));
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setAlwaysOnTop(obenauf);
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);

        JPanel kopf = Holzstil.kopfleiste(Sprache.t("Resources", "Vorrat"));
        kopf.add(kopfzeile, BorderLayout.SOUTH);

        tabelle.getColumnModel().getColumn(0).setPreferredWidth(150);
        tabelle.getColumnModel().getColumn(1).setPreferredWidth(180);
        // Zahlen rechts, Texte links -- je Zelle entschieden, die Spalte hat beides (Name/Clan und Rohstoffe).
        tabelle.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean gew, boolean f, int r, int c) {
                boolean zahl = v instanceof Number;
                JLabel l = (JLabel) t.getDefaultRenderer(zahl ? Number.class : Object.class)
                        .getTableCellRendererComponent(t, v, gew, f, r, c);
                return l;
            }
        });
        JScrollPane rollen = Holzstil.rollen(tabelle);
        rollen.getViewport().setBackground(Holzstil.HELL);
        JPanel mitte = Holzstil.grund(new BorderLayout(0, Holzstil.LUFT_KLEIN), Holzstil.LUFT);
        mitte.add(rollen, BorderLayout.CENTER);
        mitte.add(hinweis, BorderLayout.SOUTH);

        JButton neu = Holzstil.kleinerKnopf(Sprache.t("Refresh", "Neu holen"),
                Sprache.t("Sends " + Befehlsbau.STAND + " -- reads the screen, only when the bot is free.",
                        "Schickt " + Befehlsbau.STAND + " -- liest den Bildschirm, nur wenn der Bot frei ist."), Symbol.Art.NEU);
        neu.addActionListener(e -> steuer.senden(Befehlsbau.STAND, Sprache.t("Resources window", "Fenster Vorrat")));
        JButton zu = Holzstil.kleinerKnopf(Sprache.t("Hide", "Ausblenden"),
                Sprache.t("Closes the window. A shortcut or button brings it back.", "Macht das Fenster zu. Kuerzel oder Knopf holen es wieder."),
                Symbol.Art.KREUZ);
        zu.addActionListener(e -> setVisible(false));
        JPanel unten = Holzstil.grund(new BorderLayout(), 0);
        unten.setBorder(BorderFactory.createEmptyBorder(0, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));
        JPanel knoepfe = Holzstil.flaeche(new FlowLayout(FlowLayout.RIGHT, Holzstil.LUFT_KLEIN, 0));
        knoepfe.add(neu);
        knoepfe.add(zu);
        unten.add(stand, BorderLayout.WEST);
        unten.add(knoepfe, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(kopf, BorderLayout.NORTH);
        add(mitte, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        setSize(420, 400);

        zuhoerer = m -> {
            if (m.titelEnthaelt(TITEL)) SwingUtilities.invokeLater(() -> zeigen(m));
        };
        meldungen.zuhoeren(zuhoerer);
        Meldung letzte = meldungen.letzteMit(TITEL);
        if (letzte != null) zeigen(letzte);
        else hinweis.setText(Sprache.t("Press \"Refresh\" -- the bot reads the numbers at the top of the castle.",
                "\"Neu holen\" druecken -- der Bot liest die Zahlen oben in der Burg ab."));
    }

    @Override
    public void abmelden() {
        meldungen.nichtMehrZuhoeren(zuhoerer);
    }

    private void zeigen(Meldung m) {
        Map<String, String> zeilen = Statistikdaten.vorratZeilen(m.text());
        modell.setRowCount(0);
        String nurHinweis = null;
        for (var e : zeilen.entrySet()) {
            if (e.getKey().equals("Hinweis")) {
                nurHinweis = e.getValue();
                continue;
            }
            Long zahl = Statistikdaten.zahl(e.getValue());
            modell.addRow(new Object[]{e.getKey(), zahl != null ? zahl : e.getValue()});
        }
        kopfzeile.setText(modell.getRowCount() == 0 ? Sprache.t("Nothing read", "Nichts abgelesen")
                : Sprache.t(modell.getRowCount() + " values, read at " + m.uhrzeit(),
                        modell.getRowCount() + " Werte, abgelesen um " + m.uhrzeit()));
        hinweis.setText(nurHinweis != null ? nurHinweis : (modell.getRowCount() == 0 ? m.textLesbar() : " "));
        hinweis.setForeground(m.farbe() == de.hustlebot.discord.DiscordClient.FARBE_WARNUNG ? Holzstil.WARNUNG : Holzstil.LEISE);
        stand.setText(Sprache.t("As of " + m.uhrzeit(), "Stand " + m.uhrzeit()));
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) {
            Meldung letzte = meldungen.letzteMit(TITEL);
            if (letzte != null) zeigen(letzte);
        }
    }
}
