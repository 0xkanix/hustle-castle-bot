package de.hustlebot.tools;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Autor 24.10. (Ideenliste F19): Die Liste "Meine Bilder" im Aufnahme-Programm: alle eigenen Bilder aus dem Ordner
 * "My pictures" mit Vorschau, Groesse und Datum -- und "Entfernen". Entfernt wird nichts endgueltig: das Bild wird zu
 * {@code name.png.alt} umbenannt (der Bot liest nur *.png), und "Letztes Bild zuruecknehmen" holt es wieder.
 * So lassen sich Fehler beheben, ohne den Dateimanager zu kennen.
 */
public final class MeineBilder {

    private MeineBilder() {
    }

    /** Mehr Zeilen mit Vorschau werden nicht geladen (Arena-Listen koennen gross werden). */
    static final int MAX = 600;
    private static final int VORSCHAU_B = 150, VORSCHAU_H = 40;

    /** Alle PNG-Dateien unter der Wurzel, nach Ordner und Name sortiert (hoechstens {@code max}). */
    static List<Path> bilder(Path wurzel, int max) {
        List<Path> liste = new ArrayList<>();
        if (wurzel == null || !Files.isDirectory(wurzel)) return liste;
        try (Stream<Path> s = Files.walk(wurzel)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString()))
                    .limit(max)
                    .forEach(liste::add);
        } catch (IOException e) {
            Log.debug("Vorlagen", "Meine Bilder nicht lesbar: " + e.getMessage());
        }
        return liste;
    }

    /** Vorschau (hoechstens 150 x 40, Seitenverhaeltnis bleibt) und Groesse in Punkten eines Bildes; Vorschau null, wenn nicht lesbar. */
    record Vorschau(ImageIcon icon, String groesse) {
    }

    static Vorschau vorschau(Path datei) {
        try {
            BufferedImage b = ImageIO.read(datei.toFile());
            if (b == null) return new Vorschau(null, "?");
            double f = Math.min(1.0, Math.min(VORSCHAU_B / (double) b.getWidth(), VORSCHAU_H / (double) b.getHeight()));
            int w = Math.max(1, (int) Math.round(b.getWidth() * f)), h = Math.max(1, (int) Math.round(b.getHeight() * f));
            BufferedImage k = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = k.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(b, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            return new Vorschau(new ImageIcon(k), b.getWidth() + " x " + b.getHeight());
        } catch (Exception e) {
            return new Vorschau(null, "?");
        }
    }

    private static final class Modell extends AbstractTableModel {
        private final Path wurzel;
        private final List<Path> dateien;
        /** Vorschau und Groesse je Datei -- im Hintergrund geladen, damit das Zeichnen der Tabelle nie Dateien liest. */
        private final java.util.Map<Path, Vorschau> vorschauen = new java.util.HashMap<>();
        private final String[] spalten = {Sprache.t("Picture", "Bild"), Sprache.t("File", "Datei"),
                Sprache.t("Folder", "Ordner"), Sprache.t("Size", "Größe"), Sprache.t("Saved", "Gespeichert")};

        Modell(Path wurzel, List<Path> dateien) {
            this.wurzel = wurzel;
            this.dateien = dateien;
        }

        @Override public int getRowCount() {
            return dateien.size();
        }

        @Override public int getColumnCount() {
            return spalten.length;
        }

        @Override public String getColumnName(int c) {
            return spalten[c];
        }

        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Icon.class : String.class;
        }

        @Override public Object getValueAt(int r, int c) {
            Path p = dateien.get(r);
            return switch (c) {
                case 0 -> {
                    Vorschau v = vorschauen.get(p);
                    yield v == null ? null : v.icon();
                }
                case 1 -> p.getFileName().toString();
                case 2 -> {
                    Path rel = wurzel.relativize(p).getParent();
                    yield rel == null ? "" : rel.toString();
                }
                case 3 -> {
                    Vorschau v = vorschauen.get(p);
                    yield v == null ? "" : v.groesse();
                }
                default -> {
                    try {
                        yield LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
                    } catch (IOException e) {
                        yield "";
                    }
                }
            };
        }
    }

    /**
     * Zeigt die Liste (blockiert, bis das Fenster zu ist).
     *
     * @param journal       hier landet jedes Entfernen als Eintrag, damit "Letztes Bild zuruecknehmen" es zuruecknimmt
     * @param nachAenderung wird nach jedem Entfernen gerufen (Anzeige des Aufnahme-Programms auffrischen); darf null sein
     */
    public static void zeigen(Component eltern, Path wurzel, Bildablage.Journal journal, Runnable nachAenderung) {
        Window fenster = eltern instanceof Window w ? w : eltern == null ? null : SwingUtilities.getWindowAncestor(eltern);
        JDialog dialog = new JDialog(fenster, Sprache.t("My pictures", "Meine Bilder"), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        List<Path> dateien = new ArrayList<>(bilder(wurzel, MAX));
        Modell modell = new Modell(wurzel, dateien);
        JTable tabelle = new JTable(modell);
        tabelle.setRowHeight(VORSCHAU_H + 6);
        tabelle.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tabelle.getColumnModel().getColumn(0).setPreferredWidth(170);
        tabelle.getColumnModel().getColumn(1).setPreferredWidth(240);
        tabelle.getColumnModel().getColumn(2).setPreferredWidth(190);
        tabelle.getColumnModel().getColumn(3).setPreferredWidth(80);
        tabelle.getColumnModel().getColumn(4).setPreferredWidth(120);

        JLabel kopf = new JLabel("<html><body style='width:640px'>"
                + Sprache.t("Everything you captured yourself (and what the bot captured for you). Select pictures and click <b>Remove</b> -- "
                        + "they are only renamed to .alt, nothing is deleted for good. <b>Undo last picture</b> in the capture window brings them back.",
                "Alles, was du selbst aufgenommen hast (und was der Bot für dich aufgenommen hat). Bilder auswählen und auf <b>Entfernen</b> "
                        + "klicken -- sie werden nur in .alt umbenannt, nichts wird endgültig gelöscht. <b>Letztes Bild zurücknehmen</b> im "
                        + "Aufnahme-Fenster holt sie wieder.")
                + (dateien.size() >= MAX ? "<br>" + Sprache.t("Only the first " + MAX + " are shown.", "Es werden nur die ersten " + MAX + " gezeigt.") : "")
                + (dateien.isEmpty() ? "<br><b>" + Sprache.t("No pictures yet.", "Noch keine Bilder.") + "</b>" : "")
                + "</body></html>");
        kopf.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JButton entfernen = new JButton(Sprache.t("Remove selected", "Ausgewählte entfernen"));
        entfernen.addActionListener(e -> {
            int[] zeilen = tabelle.getSelectedRows();
            if (zeilen.length == 0) return;
            List<Runnable> rueck = new ArrayList<>();
            List<Path> weg = new ArrayList<>();
            for (int z : zeilen) {
                Path p = dateien.get(tabelle.convertRowIndexToModel(z));
                try {
                    rueck.add(Bildablage.nachAlt(p));
                    weg.add(p);
                } catch (IOException ex) {
                    Log.debug("Vorlagen", "Nicht entfernt: " + p + " -- " + ex.getMessage());
                }
            }
            if (weg.isEmpty()) return;
            journal.neu(Sprache.t("Removed " + weg.size() + " picture(s): ", "Entfernt: " + weg.size() + " Bild(er): ")
                    + weg.get(0).getFileName().toString().replaceAll("\\.png$", "") + (weg.size() > 1 ? ", ..." : ""),
                    -1, rueck.toArray(new Runnable[0]));
            // Aus der Liste nehmen (von hinten, damit die Nummern stimmen).
            for (int i = zeilen.length - 1; i >= 0; i--) {
                int m = tabelle.convertRowIndexToModel(zeilen[i]);
                if (weg.contains(dateien.get(m))) {
                    dateien.remove(m);
                }
            }
            modell.fireTableDataChanged();
            if (nachAenderung != null) nachAenderung.run();
        });

        JButton ordner = new JButton(Sprache.t("Open folder", "Ordner öffnen"));
        ordner.addActionListener(e -> {
            try {
                Files.createDirectories(wurzel);
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(wurzel.toFile());
            } catch (Exception ex) {
                Log.debug("Vorlagen", "Ordner nicht geoeffnet: " + ex.getMessage());
            }
        });
        JButton zu = new JButton(Sprache.t("Close", "Schließen"));
        zu.addActionListener(e -> dialog.dispose());

        JPanel unten = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        unten.add(entfernen);
        unten.add(ordner);
        unten.add(zu);

        dialog.setLayout(new BorderLayout());
        dialog.add(kopf, BorderLayout.NORTH);
        JScrollPane rolle = new JScrollPane(tabelle);
        rolle.setPreferredSize(new Dimension(840, 420));
        dialog.add(rolle, BorderLayout.CENTER);
        dialog.add(unten, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(eltern);

        // Vorschauen im Hintergrund laden -- die Liste ist sofort da.
        List<Path> zuLaden = new ArrayList<>(dateien);
        SwingWorker<Void, Object[]> lader = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                for (Path p : zuLaden) {
                    if (isCancelled()) break;
                    publish(new Object[]{p, vorschau(p)});
                }
                return null;
            }

            @Override protected void process(List<Object[]> teile) {
                for (Object[] t : teile) {
                    modell.vorschauen.put((Path) t[0], (Vorschau) t[1]);
                    int i = dateien.indexOf((Path) t[0]);   // zwischendurch kann etwas entfernt worden sein
                    if (i >= 0) modell.fireTableRowsUpdated(i, i);
                }
            }
        };
        lader.execute();
        dialog.setVisible(true);
        lader.cancel(true);
    }
}
