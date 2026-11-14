package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ein kleines Anzeige-Fenster, das eine bestimmte Karte des Bots zeigt -- den
 * Status -- und sie mit einem Knopf neu holt. (Statistik und Vorrat haben seit
 * Auftrag 4 eigene Fenster mit Diagrammen bzw. Tabelle.)
 *
 * Es sucht sich aus den Meldungen die neueste Karte heraus, deren Titel zu ihm
 * passt ("Zustand"/"State"). Oben steht die Zustands-Pille und eine Zeile, die
 * sich jede Sekunde erneuert -- so ist der Status auch ohne Karte immer frisch.
 *
 * Ein- und ausblenden ueber Steuerprogramm (Knopf, Kuerzel oder Sprache).
 */
final class Kartenfenster extends JFrame implements Steuerprogramm.Abmeldbar {

    private final JLabel kopfzeile = Holzstil.kopfzeile(" ");
    private final Holzstil.Pille pille = new Holzstil.Pille(true);
    private final JLabel kartentitel = new JLabel(" ");
    private final JTextArea inhalt = Holzstil.lesebereich();
    private final JLabel stand = Holzstil.hinweis(" ");
    private final String[] titelWoerter;
    private final Meldungen meldungen;
    private final Supplier<String> lebendeZeile;
    private final Steuerprogramm steuer;
    private final Consumer<Meldung> zuhoerer;
    private Timer takt;

    /**
     * @param titel        Fenstertitel
     * @param befehl       der Discord-Befehl, der die Karte neu holt (z. B. "!status")
     * @param titelWoerter woran die Karte erkannt wird
     * @param lebendeZeile Zeile oben, die sich jede Sekunde erneuert; null = keine
     */
    Kartenfenster(String titel, String befehl, String[] titelWoerter, Meldungen meldungen,
                  Supplier<String> lebendeZeile, Steuerprogramm steuer, boolean obenauf, int breite, int hoehe) {
        this.titelWoerter = titelWoerter;
        this.meldungen = meldungen;
        this.lebendeZeile = lebendeZeile;
        this.steuer = steuer;

        setTitle(titel);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setAlwaysOnTop(obenauf);
        setAutoRequestFocus(false);
        getContentPane().setBackground(Holzstil.GRUND);

        JPanel kopf = Holzstil.kopfleiste(titel, pille);
        kopf.add(kopfzeile, BorderLayout.SOUTH);

        kartentitel.setFont(Holzstil.KNOPF);
        kartentitel.setForeground(Holzstil.LEISE);

        inhalt.setMargin(new Insets(4, 0, 4, 0));
        inhalt.setOpaque(false);
        JPanel karte = Holzstil.karte(new BorderLayout(0, Holzstil.LUFT_KLEIN));
        karte.add(kartentitel, BorderLayout.NORTH);
        JScrollPane rollen = Holzstil.rollen(inhalt);
        rollen.setBorder(BorderFactory.createEmptyBorder());
        rollen.getViewport().setBackground(Holzstil.HELL);
        karte.add(rollen, BorderLayout.CENTER);
        JPanel mitte = Holzstil.grund(new BorderLayout(), Holzstil.LUFT);
        mitte.add(karte, BorderLayout.CENTER);

        JButton neu = Holzstil.kleinerKnopf(Sprache.t("Refresh", "Neu holen"), Sprache.t("Sends ", "Schickt ") + befehl + Sprache.t(" -- like in Discord.", " -- wie in Discord."), Symbol.Art.NEU);
        neu.addActionListener(e -> steuer.senden(befehl, Sprache.t("Window ", "Fenster ") + titel));
        JButton zu = Holzstil.kleinerKnopf(Sprache.t("Hide", "Ausblenden"), Sprache.t("Closes the window. Shortcut or button bring it back.", "Macht das Fenster zu. Kuerzel oder Knopf holen es wieder."), Symbol.Art.KREUZ);
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
        setSize(breite, hoehe);

        zuhoerer = m -> {
            if (m.titelEnthaelt(titelWoerter)) SwingUtilities.invokeLater(() -> zeigen(m));
        };
        meldungen.zuhoeren(zuhoerer);
        Meldung letzte = meldungen.letzteMit(titelWoerter);
        if (letzte != null) zeigen(letzte);
        else {
            kartentitel.setText(Sprache.t("Nothing fetched yet", "Noch nichts geholt"));
            inhalt.setText(Sprache.t("Press \"Refresh\" -- the answer appears here.", "\"Neu holen\" druecken -- die Antwort erscheint hier."));
        }
        aktualisieren();
    }

    @Override
    public void abmelden() {
        meldungen.nichtMehrZuhoeren(zuhoerer);
        if (takt != null) takt.stop();
    }

    private void zeigen(Meldung m) {
        kartentitel.setText(m.titelLesbar());
        kartentitel.setForeground(Holzstil.kartenfarbe(m.farbe()));
        inhalt.setText(m.textLesbar());
        inhalt.setCaretPosition(0);
        stand.setText(Sprache.t("As of ", "Stand ") + m.uhrzeit());
    }

    private void aktualisieren() {
        try {
            pille.setzen(steuer.zustand());
            if (lebendeZeile == null) return;
            String z = lebendeZeile.get();
            kopfzeile.setText(z == null || z.isBlank() ? " " : z);
        } catch (Exception ignored) {
            // nichts
        }
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) {
            if (takt == null) {
                takt = new Timer(1000, e -> aktualisieren());
                takt.start();
            }
            Meldung letzte = meldungen.letzteMit(titelWoerter);
            if (letzte != null) zeigen(letzte);
            aktualisieren();
        } else if (takt != null) {
            takt.stop();
            takt = null;
        }
    }
}
