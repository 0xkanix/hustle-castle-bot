package de.hustlebot.tools;

import de.hustlebot.core.ScreenCapture;
import de.hustlebot.core.Sprache;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Autor 24.10. (Ideenliste F23): "Alle meine Bilder pruefen". Vergleicht jede eigene Aufnahme mit EINEM frischen Foto und
 * listet, welche darin gefunden werden und welche nicht -- die Selbstpruefung nach dem Aufnehmen. Freunde merkten bisher
 * erst im Spiel, dass ein Bild nicht passt.
 *
 * Bilder, die zu einem ANDEREN Bildschirm gehoeren, koennen im Foto nicht vorkommen -- das ist kein Fehler. Darum wird je
 * Bildschirm unterschieden: Zeigt das Foto erkennbar diesen Bildschirm (mindestens ein Bild dazu gefunden) oder ist es der
 * Bildschirm des aktuellen Assistenten-Schritts, gilt ein nicht gefundenes Bild als "fehlt hier" (rot); alle anderen
 * Bildschirme stehen grau als "anderer Bildschirm".
 */
public final class Bilderpruefung {

    private Bilderpruefung() {
    }

    /** Wie beim Bot: ab diesem Wert gilt eine Vorlage als gefunden (Matcher.DEFAULT_THRESHOLD). */
    public static final double FUND_SCHWELLE = 0.84;

    /** Ein eigenes Bild: Name, auf welchem Bildschirm es zu finden ist (Klartext) und die Datei. */
    public record Kandidat(String name, String ort, Path datei) {
    }

    public enum Status { GEFUNDEN, FEHLT_HIER, ANDERER_BILDSCHIRM }

    public record Zeile(Kandidat kandidat, double wert, Status status) {
    }

    /**
     * Prueft alle Kandidaten gegen das Foto.
     *
     * @param erwarteterOrt der Bildschirm des aktuellen Schritts (oder null) -- gilt auch dann als "hier", wenn dort
     *                      nichts gefunden wurde
     * @param fortschritt   bekommt nach jedem Bild die Zahl der fertigen (darf null sein)
     */
    public static List<Zeile> pruefe(BufferedImage foto, List<Kandidat> kandidaten, String erwarteterOrt, IntConsumer fortschritt) {
        List<Double> werte = new ArrayList<>();
        Mat f = ScreenCapture.toMatAusschnitt(foto, 0, 0, foto.getWidth(), foto.getHeight());
        try {
            int fertig = 0;
            for (Kandidat k : kandidaten) {
                werte.add(wert(f, k.datei()));
                if (fortschritt != null) fortschritt.accept(++fertig);
            }
        } finally {
            f.release();
        }
        return bewerte(kandidaten, werte, erwarteterOrt);
    }

    /** Aus den Werten die Zeilen machen (ohne OpenCV -- pruefbar). Je Bildschirm: gefunden, fehlt hier, anderer Bildschirm. */
    static List<Zeile> bewerte(List<Kandidat> kandidaten, List<Double> werte, String erwarteterOrt) {
        Map<String, Integer> gefundenJeOrt = new LinkedHashMap<>();
        for (int i = 0; i < kandidaten.size(); i++) {
            if (werte.get(i) >= FUND_SCHWELLE) gefundenJeOrt.merge(kandidaten.get(i).ort(), 1, Integer::sum);
        }
        List<Zeile> zeilen = new ArrayList<>();
        for (int i = 0; i < kandidaten.size(); i++) {
            Kandidat k = kandidaten.get(i);
            double w = werte.get(i);
            Status s;
            if (w >= FUND_SCHWELLE) s = Status.GEFUNDEN;
            else if (gefundenJeOrt.containsKey(k.ort()) || k.ort().equals(erwarteterOrt)) s = Status.FEHLT_HIER;
            else s = Status.ANDERER_BILDSCHIRM;
            zeilen.add(new Zeile(k, w, s));
        }
        zeilen.sort(Comparator.comparingInt((Zeile z) -> rang(z.status()))
                .thenComparing(z -> z.kandidat().ort()).thenComparing(z -> z.kandidat().name()));
        return zeilen;
    }

    /** Reihenfolge in der Liste: erst was fehlt, dann was gefunden wurde, zuletzt die anderen Bildschirme. */
    private static int rang(Status s) {
        return switch (s) {
            case FEHLT_HIER -> 0;
            case GEFUNDEN -> 1;
            case ANDERER_BILDSCHIRM -> 2;
        };
    }

    /** Der beste Vergleichswert einer Vorlage im Foto; -1, wenn die Datei nicht lesbar oder groesser als das Foto ist. */
    static double wert(Mat foto, Path datei) {
        BufferedImage bild;
        try {
            bild = ImageIO.read(datei.toFile());
        } catch (Exception e) {
            return -1;
        }
        if (bild == null || bild.getWidth() > foto.width() || bild.getHeight() > foto.height()) return -1;
        Mat v = ScreenCapture.toMatAusschnitt(bild, 0, 0, bild.getWidth(), bild.getHeight());
        Mat erg = new Mat();
        try {
            Imgproc.matchTemplate(foto, v, erg, Imgproc.TM_CCOEFF_NORMED);
            Core.patchNaNs(erg, 0);
            return Core.minMaxLoc(erg).maxVal;
        } finally {
            erg.release();
            v.release();
        }
    }

    /** Die Zusammenfassung in einem Satz-Block (html ohne Rahmen). */
    public static String zusammenfassung(List<Zeile> zeilen) {
        long gefunden = zeilen.stream().filter(z -> z.status() == Status.GEFUNDEN).count();
        List<String> fehlt = zeilen.stream().filter(z -> z.status() == Status.FEHLT_HIER)
                .map(z -> z.kandidat().name()).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(Sprache.t("Found " + gefunden + " of " + zeilen.size() + " of your pictures in this photo.",
                "In diesem Foto wurden " + gefunden + " von " + zeilen.size() + " deiner Bilder gefunden."));
        if (!fehlt.isEmpty()) {
            sb.append("<br><b>").append(Sprache.t("Missing on this screen: ", "Auf diesem Bildschirm fehlen: "))
                    .append(String.join(", ", fehlt)).append("</b><br>")
                    .append(Sprache.t("Capture these again (or check Windows scaling = 100 % and that HD graphics are off).",
                            "Nimm diese noch einmal auf (oder prüf: Windows-Skalierung 100 % und HD-Grafik aus)."));
        } else if (gefunden > 0) {
            sb.append("<br>").append(Sprache.t("Nothing missing on this screen.", "Auf diesem Bildschirm fehlt nichts."));
        }
        sb.append("<br><i>").append(Sprache.t("Pictures of other screens cannot appear here -- that is normal (grey).",
                "Bilder von anderen Bildschirmen können hier nicht vorkommen – das ist normal (grau).")).append("</i>");
        return sb.toString();
    }

    /** Zeigt das Ergebnis als Tabelle mit Zusammenfassung. */
    public static void zeigeErgebnis(Component eltern, List<Zeile> zeilen) {
        String[] spalten = {Sprache.t("Result", "Ergebnis"), Sprache.t("Picture", "Bild"),
                Sprache.t("Screen", "Bildschirm"), Sprache.t("Match", "Treffer")};
        DefaultTableModel modell = new DefaultTableModel(spalten, 0) {
            @Override public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        for (Zeile z : zeilen) {
            String ergebnis = switch (z.status()) {
                case GEFUNDEN -> "✓ " + Sprache.t("found", "gefunden");
                case FEHLT_HIER -> "✗ " + Sprache.t("missing here", "fehlt hier");
                case ANDERER_BILDSCHIRM -> "– " + Sprache.t("other screen", "anderer Bildschirm");
            };
            modell.addRow(new Object[]{ergebnis, z.kandidat().name(), z.kandidat().ort(),
                    z.wert() < 0 ? "-" : String.format(Locale.ROOT, "%.2f", z.wert())});
        }
        JTable tabelle = new JTable(modell);
        tabelle.setRowHeight(22);
        tabelle.getColumnModel().getColumn(0).setPreferredWidth(130);
        tabelle.getColumnModel().getColumn(1).setPreferredWidth(220);
        tabelle.getColumnModel().getColumn(2).setPreferredWidth(300);
        tabelle.getColumnModel().getColumn(3).setPreferredWidth(60);
        JScrollPane rolle = new JScrollPane(tabelle);
        rolle.setPreferredSize(new Dimension(760, 280));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html><body style='width:700px'>" + zusammenfassung(zeilen) + "</body></html>"), BorderLayout.NORTH);
        p.add(rolle, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(eltern, p, Sprache.t("Check of all my pictures", "Alle meine Bilder prüfen"),
                JOptionPane.INFORMATION_MESSAGE);
    }
}
