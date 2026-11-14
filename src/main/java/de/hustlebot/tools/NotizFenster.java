package de.hustlebot.tools;

import de.hustlebot.core.Sprache;
import de.hustlebot.steuer.Holzstil;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Der Notizblock der Arena als eigenes Fenster.
 *
 * Zuerst war er ein zweiter Reiter im Mitschau-Fenster. Autor wollte es
 * getrennt: "kann man den notizblock fuer arena und den log getrennt
 * haben? also zwei fenster ... das ich beide gleichzeitig anschauen
 * kann." Mit Reitern sieht man immer nur eines von beiden -- und gerade
 * beim Zusehen ist die interessante Frage, was der Bot ueber den Gegner
 * WEISS, waehrend er ihn auswaehlt.
 *
 * Seit Auftrag 4 (Autor, 11.10.) eine Tabelle in den Holz-Toenen: je Gegner eine Zeile mit Clan, Kraft,
 * Siegen, Niederlagen, Quote, Kampfzeit, Punkten, Arena-Punkten und den letzten Kaempfen; jede zweite
 * Zeile getoent, Klick auf die Kopfzeile sortiert. Darueber, solange es frisch ist, die Liste des
 * laufenden Turniers.
 *
 * Gelesen wird dieselbe Datei, die der Arena-Modus schreibt. Kein
 * zweiter Stand der Dinge, der irgendwann abweicht -- was hier steht,
 * ist genau das, wonach der Bot sich richtet.
 */
public final class NotizFenster extends JFrame {

    private final Path datei;
    private final Path listeDatei;
    private final JLabel kopf = Holzstil.kopfzeile(" ");
    private final DefaultTableModel modell = new DefaultTableModel(Notizblocktabelle.SPALTEN, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
        @Override public Class<?> getColumnClass(int c) {
            return c == 3 || c == 4 || c == 5 ? Integer.class : String.class;
        }
    };
    private final JTable tabelle = Holzstil.tabelle(modell);
    // Autor 22.10.: zwei Notizbloecke -- "gescannt" und "gescannt und angegriffen" (NotizblockListen).
    private final DefaultTableModel modellGescannt = listenModell(NotizblockListen.SPALTEN_GESCANNT, NotizblockListen.ZAHLEN_GESCANNT);
    private final DefaultTableModel modellAngegriffen = listenModell(NotizblockListen.SPALTEN_ANGEGRIFFEN, NotizblockListen.ZAHLEN_ANGEGRIFFEN);
    private final javax.swing.JTabbedPane reiter = Holzstil.reiter();
    private long standListen = Long.MIN_VALUE;

    private static DefaultTableModel listenModell(String[] spalten, int[] zahlen) {
        return new DefaultTableModel(spalten, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                for (int z : zahlen) if (z == c) return Integer.class;
                return String.class;
            }
        };
    }
    private final JTextArea turnier = Holzstil.lesebereich();
    private final JPanel turnierKarte;
    private final JTextArea leerText = Holzstil.lesebereich();
    private final JPanel mitte;
    private final JScrollPane tabelleRollen;
    private long stand = Long.MIN_VALUE;
    private final Timer takt;

    public NotizFenster() {
        this(de.hustlebot.core.Gegnerbuch.NOTIZBLOCK, Path.of("arena-liste.txt"));
    }

    /** Mit eigenen Dateien -- fuer die Vorschau und zum Ansehen alter Staende. */
    public NotizFenster(Path notizblock, Path arenaListe) {
        this.datei = notizblock;
        this.listeDatei = arenaListe;
        setTitle(Sprache.t("Arena notebook", "Notizblock Arena"));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setAlwaysOnTop(false);   // Autor 11.10.: nicht ueber Discord & Co. liegen
        getContentPane().setBackground(Holzstil.GRUND);

        JPanel oben = Holzstil.kopfleiste(Sprache.t("Arena notebook", "Notizblock Arena"));
        oben.add(kopf, BorderLayout.SOUTH);

        turnier.setOpaque(false);
        turnier.setFont(Holzstil.FEST);
        turnier.setMargin(new Insets(0, 0, 0, 0));
        turnierKarte = Holzstil.karte(Sprache.t("Current tournament", "Aktuelles Turnier"), new BorderLayout());
        JScrollPane turnierRollen = Holzstil.rollen(turnier);
        turnierRollen.setBorder(BorderFactory.createEmptyBorder());
        turnierKarte.add(turnierRollen, BorderLayout.CENTER);
        turnierKarte.setPreferredSize(new Dimension(100, 160));
        turnierKarte.setVisible(false);

        int[] breiten = {130, 120, 70, 36, 36, 70, 50, 70, 122, 70};
        for (int i = 0; i < breiten.length; i++) tabelle.getColumnModel().getColumn(i).setPreferredWidth(breiten[i]);
        tabelle.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        tabelleRollen = Holzstil.rollen(tabelle);
        tabelleRollen.getViewport().setBackground(Holzstil.HELL);

        leerText.setOpaque(false);
        leerText.setForeground(Holzstil.LEISE);
        leerText.setMargin(new Insets(0, 0, 0, 0));
        leerText.setText(Sprache.t("After every Arena fight the bot notes here whom it fought and how it went.\n\n"
                + "From three fights against the same opponent on, it goes by these notes. Only the last ten count – "
                + "people get stronger.\n\nAs soon as the first tournament is over, something shows up here.",
                "Hier trägt der Bot nach jedem Arena-Kampf ein, gegen wen er angetreten ist und wie es ausging.\n\n"
                + "Ab drei Kämpfen gegen denselben Gegner richtet er sich danach. Gezählt werden immer nur die letzten zehn – "
                + "Leute werden stärker.\n\nSobald das erste Turnier durch ist, steht hier etwas."));
        JPanel leerKarte = Holzstil.karte(Sprache.t("No notes yet", "Noch keine Notizen"), new BorderLayout());
        leerKarte.add(leerText, BorderLayout.CENTER);

        JTable tGescannt = Holzstil.tabelle(modellGescannt);
        JTable tAngegriffen = Holzstil.tabelle(modellAngegriffen);
        tGescannt.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        tAngegriffen.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane rGescannt = Holzstil.rollen(tGescannt);
        JScrollPane rAngegriffen = Holzstil.rollen(tAngegriffen);
        rGescannt.getViewport().setBackground(Holzstil.HELL);
        rAngegriffen.getViewport().setBackground(Holzstil.HELL);
        reiter.addTab(Sprache.t("Scanned", "Gescannt"), rGescannt);
        reiter.addTab(Sprache.t("Scanned + attacked", "Gescannt + angegriffen"), rAngegriffen);
        reiter.addTab(Sprache.t("Overview", "Übersicht"), tabelleRollen);

        mitte = Holzstil.grund(new BorderLayout(0, Holzstil.LUFT), Holzstil.LUFT);
        mitte.add(turnierKarte, BorderLayout.NORTH);
        mitte.add(reiter, BorderLayout.CENTER);
        mitte.add(leerKarte, BorderLayout.SOUTH);
        leerKarte.setVisible(false);
        this.leerKarte = leerKarte;

        JLabel fuss = Holzstil.hinweis(Sprache.t("W = wins, L = losses. Last: S = win, N = loss, newest on the right. Click a column to sort.",
                "Zuletzt: S = Sieg, N = Niederlage, der neueste ganz rechts. Klick auf eine Spalte sortiert."));
        JPanel unten = Holzstil.grund(new BorderLayout(), 0);
        unten.setBorder(BorderFactory.createEmptyBorder(0, Holzstil.LUFT, Holzstil.LUFT, Holzstil.LUFT));
        unten.add(fuss, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(oben, BorderLayout.NORTH);
        add(mitte, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);

        // Links neben das Mitschau-Fenster, damit beide gleichzeitig zu
        // sehen sind. Breiter als frueher: eine Tabelle braucht Platz.
        Fensterstil.anDenRand(this, Fensterstil.BREITE_NOTIZEN, 800,
                Fensterstil.RAND + Fensterstil.BREITE_LOG + Fensterstil.LUECKE);

        takt = new Timer(2000, e -> nachsehen());
        takt.start();
        nachsehen();
    }

    private final JPanel leerKarte;

    @Override
    public void dispose() {
        takt.stop();
        super.dispose();
    }

    /**
     * Liest die Datei neu -- aber nur, wenn sie sich geaendert hat.
     *
     * Sonst wuerde die Tabelle alle zwei Sekunden ersetzt, und wer gerade
     * darin scrollt oder sortiert, waere staendig wieder oben.
     */
    private void nachsehen() {
        listenNachsehen();
        try {
            if (!Files.exists(datei)) {
                if (stand != -1) {
                    stand = -1;
                    kopf.setText(Sprache.t("No notes yet", "Noch keine Notizen"));
                    modell.setRowCount(0);
                    tabelleRollen.setVisible(false);
                    leerKarte.setVisible(true);
                    turnierKarte.setVisible(false);
                    mitte.revalidate();
                }
                return;
            }

            // Seit 24.09. steht oben die Liste des laufenden Turniers
            // (arena-liste.txt): alle Gegner mit Kraft, Sets, Artefakten,
            // Punkten und Bilanz. Sie gilt, solange sie keine 3 Stunden alt ist.
            long listeStand = Files.exists(listeDatei) ? Files.getLastModifiedTime(listeDatei).toMillis() : 0;
            boolean listeFrisch = listeStand > System.currentTimeMillis() - 3 * 3600_000L;
            long jetzt = Files.getLastModifiedTime(datei).toMillis() + (listeFrisch ? listeStand : 0);
            if (jetzt == stand) return;
            stand = jetzt;

            if (listeFrisch) {
                turnier.setText(Files.readString(listeDatei, StandardCharsets.UTF_8));
                turnier.setCaretPosition(0);
            }
            turnierKarte.setVisible(listeFrisch);

            String block = Files.readString(datei, StandardCharsets.UTF_8);
            List<Notizblocktabelle.Gegner> gegner = Notizblocktabelle.lesen(block);
            modell.setRowCount(0);
            for (Notizblocktabelle.Gegner g : gegner) {
                modell.addRow(new Object[]{g.name(), g.clan(), g.kraft(), g.siege(), g.niederlagen(),
                        g.quote() < 0 ? null : g.quote(), g.zeit(), g.punkte(), g.arenaPunkte(), g.zuletzt()});
            }
            tabelleRollen.setVisible(!gegner.isEmpty());
            leerKarte.setVisible(gegner.isEmpty());
            String kopfzeile = Notizblocktabelle.kopfzeile(block);
            kopf.setText(!kopfzeile.isEmpty() ? kopfzeile : gegner.size() == 1 ? Sprache.t("1 opponent known", "1 Gegner bekannt")
                    : Sprache.t(gegner.size() + " opponents known", gegner.size() + " Gegner bekannt"));
            mitte.revalidate();
            mitte.repaint();
        } catch (Exception e) {
            kopf.setText(Sprache.t("Could not be read: " + e.getMessage(), "Konnte nicht gelesen werden: " + e.getMessage()));
        }
    }

    /** Die beiden Listen "Gescannt" und "Gescannt + angegriffen" neu lesen -- nur, wenn sich eine Datei geaendert hat. */
    private void listenNachsehen() {
        try {
            long a = Files.exists(NotizblockListen.DATEN) ? Files.getLastModifiedTime(NotizblockListen.DATEN).toMillis() : 0;
            long b = Files.exists(NotizblockListen.KAEMPFE) ? Files.getLastModifiedTime(NotizblockListen.KAEMPFE).toMillis() : 0;
            if (a * 31 + b == standListen) return;
            standListen = a * 31 + b;
            List<Object[]> g = NotizblockListen.gescannt(NotizblockListen.DATEN);
            List<Object[]> k = NotizblockListen.angegriffen(NotizblockListen.KAEMPFE);
            modellGescannt.setRowCount(0);
            for (Object[] r : g) modellGescannt.addRow(r);
            modellAngegriffen.setRowCount(0);
            for (Object[] r : k) modellAngegriffen.addRow(r);
            reiter.setTitleAt(0, Sprache.t("Scanned (" + g.size() + ")", "Gescannt (" + g.size() + ")"));
            reiter.setTitleAt(1, Sprache.t("Scanned + attacked (" + k.size() + ")", "Gescannt + angegriffen (" + k.size() + ")"));
        } catch (Exception e) {
            // beim naechsten Takt wieder
        }
    }

    // ==================================================================
    //  Von aussen auf- und zumachen
    // ==================================================================

    private static NotizFenster offen;

    public static synchronized void aufmachen() {
        SwingUtilities.invokeLater(() -> {
            if (offen != null && offen.isDisplayable()) {
                offen.toFront();
                return;
            }
            offen = new NotizFenster();
            offen.setVisible(true);
        });
    }

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
        SwingUtilities.invokeLater(() -> new NotizFenster().setVisible(true));
    }
}
