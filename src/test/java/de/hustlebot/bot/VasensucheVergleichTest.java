package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste A04): Beweis mit echten Event-Fotos, dass die Fass-Suche im Spielfeld-Ausschnitt dieselben
 * geschlossenen Felder liefert wie die Suche im ganzen Bild -- und wie viel schneller sie ist. Laeuft nur, wo die
 * Fotos (C:\HustleBot\logs\event-*.png) und die Vorlagen (C:\HustleBot\templates) liegen.
 */
class VasensucheVergleichTest {

    private static final Path LOGS = Path.of("C:/HustleBot/logs");
    private static final Path VORLAGEN = Path.of("C:/HustleBot/templates");
    private static final double SCHWELLE = 0.72;

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static boolean[][] karte(List<Matcher.Hit> hits, EventMode.Raster r) {
        boolean[][] zu = new boolean[EventMode.SPALTEN][EventMode.REIHEN];
        for (Matcher.Hit v : hits) {
            int sp = (int) Math.round((v.centerX() - r.x0()) / (double) r.schrittX());
            int re = (int) Math.round((v.centerY() - r.y0()) / (double) r.schrittY());
            if (sp >= 0 && sp < EventMode.SPALTEN && re >= 0 && re < EventMode.REIHEN) zu[sp][re] = true;
        }
        return zu;
    }

    private static String text(boolean[][] zu) {
        StringBuilder sb = new StringBuilder();
        for (int re = 0; re < EventMode.REIHEN; re++) {
            for (int sp = 0; sp < EventMode.SPALTEN; sp++) sb.append(zu[sp][re] ? '#' : '.');
            sb.append('/');
        }
        return sb.toString();
    }

    @Test
    void ausschnittGleichGanzesBild() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(LOGS) && Files.isDirectory(VORLAGEN));
        Matcher m = new Matcher(VORLAGEN);
        m.loadAll();
        List<String> motive = m.serie("ev_fass_zu");
        Assumptions.assumeTrue(motive.size() >= 5);
        EventMode.Raster r = new EventMode.Raster(386, 271, 96, 79);

        List<Path> fotos = new ArrayList<>();
        try (Stream<Path> s = Files.list(LOGS)) {
            s.filter(p -> p.getFileName().toString().matches("event-(ende|stab|fenster)-\\d+\\.png"))
                    .sorted(Comparator.comparing(Path::toString)).forEach(fotos::add);
        }
        Assumptions.assumeTrue(!fotos.isEmpty());

        StringBuilder out = new StringBuilder();
        out.append("Motive: ").append(motive).append("\n");
        long nsGanz = 0, nsAusschnitt = 0;
        int n = 0, verschieden = 0, trefferGanz = 0, trefferAusschnitt = 0, nurGanzImFeld = 0, nurAusschnitt = 0;
        // Aufwaermen
        Mat warm = Imgcodecs.imread(fotos.get(0).toString(), Imgcodecs.IMREAD_COLOR);
        for (int i = 0; i < 3; i++) {
            Vasensuche.suchen(m, warm, motive, SCHWELLE, 25, null);
            Vasensuche.suchen(m, warm, motive, SCHWELLE, 25, Vasensuche.feldBereich(r, warm.width(), warm.height()));
        }
        warm.release();
        for (Path f : fotos) {
            Mat bild = Imgcodecs.imread(f.toString(), Imgcodecs.IMREAD_COLOR);
            if (bild.empty() || bild.width() != 1159 || bild.height() != 770) { bild.release(); continue; }
            long t0 = System.nanoTime();
            List<Matcher.Hit> ganz = Vasensuche.suchen(m, bild, motive, SCHWELLE, 25, null);
            long t1 = System.nanoTime();
            List<Matcher.Hit> teil = Vasensuche.suchen(m, bild, motive, SCHWELLE, 25,
                    Vasensuche.feldBereich(r, bild.width(), bild.height()));
            long t2 = System.nanoTime();
            nsGanz += t1 - t0;
            nsAusschnitt += t2 - t1;
            n++;
            trefferGanz += ganz.size();
            trefferAusschnitt += teil.size();
            boolean[][] a = karte(ganz, r), b = karte(teil, r);
            if (!text(a).equals(text(b))) {
                verschieden++;
                out.append("VERSCHIEDEN ").append(f.getFileName()).append("  ganz ").append(text(a))
                        .append("  ausschnitt ").append(text(b)).append("\n");
            }
            // Treffer im Ganzen, die der Ausschnitt nicht hat (in Feldnaehe)
            for (Matcher.Hit g : ganz) {
                boolean da = false;
                for (Matcher.Hit t : teil) da |= Math.abs(g.centerX() - t.centerX()) <= 3 && Math.abs(g.centerY() - t.centerY()) <= 3;
                if (!da) {
                    nurGanzImFeld++;
                    out.append("nur im ganzen Bild: ").append(f.getFileName()).append(" ").append(g.name())
                            .append(" @").append(g.centerX()).append(",").append(g.centerY())
                            .append(" ").append(String.format("%.3f", g.score())).append("\n");
                }
            }
            for (Matcher.Hit t : teil) {
                boolean da = false;
                for (Matcher.Hit g : ganz) da |= Math.abs(g.centerX() - t.centerX()) <= 3 && Math.abs(g.centerY() - t.centerY()) <= 3;
                if (!da) {
                    nurAusschnitt++;
                    out.append("nur im Ausschnitt: ").append(f.getFileName()).append(" ").append(t.name())
                            .append(" @").append(t.centerX()).append(",").append(t.centerY())
                            .append(" ").append(String.format("%.3f", t.score())).append("\n");
                }
            }
            bild.release();
        }
        out.append(String.format("Fotos %d, Karten verschieden %d, Treffer ganz %d / Ausschnitt %d, nur ganz %d, nur Ausschnitt %d%n",
                n, verschieden, trefferGanz, trefferAusschnitt, nurGanzImFeld, nurAusschnitt));
        out.append(String.format("Zeit je Bild (alle %d Motive): ganzes Bild %.1f ms, Ausschnitt %.1f ms%n",
                motive.size(), nsGanz / 1e6 / n, nsAusschnitt / 1e6 / n));
        Files.writeString(Path.of("target", "vasensuche-vergleich.txt"), out.toString());
        assertEquals(0, verschieden, out.toString());
    }
}
