package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 28.10.: Listenbild <-> Namensschild ueber die weisse Schrift. Echte Listenbilder aus Autors "Arena lists"
 * (src/test/resources/arena-namen, 12 Stueck), auf Schilder mit anderem Grund gesetzt (gold, grau, blau, abgedunkelt,
 * kleiner/groesser, verschoben) -- derselbe Name muss treffen, ein anderer nie.
 */
class ListenBildVergleichTest {

    private static final int W = 1159, H = 770;
    /** Lagen der Schilder in der Pyramide (Mitte x, oberer Rand von "Reward:"), wie in Autors Fotos. */
    private static final int[][] LAGEN = {{579, 244}, {462, 378}, {696, 377}, {345, 512}, {579, 512}, {228, 645},
            {462, 645}, {696, 645}, {929, 645}};
    private static final Color KARTE = new Color(211, 208, 204);   // heller Grund wie in Autors Listenbildern
    private static final Color[] GRUENDE = {null, new Color(225, 170, 60), new Color(125, 125, 125),
            new Color(70, 120, 190)};
    private static final String[] GRUND_NAMEN = {"original", "gold", "grau", "blau"};

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @BeforeEach
    void leeren() {
        ListenBildVergleich.vergessen();
    }

    @AfterEach
    void zurueck() {
        ListenBildVergleich.pruefenNachMs = 3000;
    }

    static List<Path> namen() throws Exception {
        Path ordner = Path.of(ListenBildVergleichTest.class.getResource("/arena-namen").toURI());
        try (Stream<Path> s = Files.list(ordner)) {
            return s.filter(p -> p.toString().endsWith(".png")).sorted().toList();
        }
    }

    // ------------------------------------------------------------------
    //  Hilfen: Schilder bauen
    // ------------------------------------------------------------------

    /** Grund neu einfaerben, weisse Schrift und dunkler Rand bleiben. grund null = unveraendert. */
    static BufferedImage einfaerben(BufferedImage name, Color grund) {
        BufferedImage b = new BufferedImage(name.getWidth(), name.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < name.getHeight(); y++) {
            for (int x = 0; x < name.getWidth(); x++) {
                int p = name.getRGB(x, y);
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, bl = p & 0xff;
                int lo = Math.min(r, Math.min(g, bl)), hi = Math.max(r, Math.max(g, bl));
                boolean weiss = lo >= 235 && hi - lo <= 30, dunkel = hi <= 100;
                if (grund == null || weiss || dunkel) {
                    b.setRGB(x, y, p & 0xffffff);
                    continue;
                }
                double f = Math.min(1.2, (r + g + bl) / 3.0 / 210.0);
                b.setRGB(x, y, new Color(Math.min(255, (int) (grund.getRed() * f)), Math.min(255, (int) (grund.getGreen() * f)),
                        Math.min(255, (int) (grund.getBlue() * f))).getRGB());
            }
        }
        return b;
    }

    /**
     * Groesser/kleiner, so wie das Spiel eine andere Schriftgroesse zeichnet: Umriss weich skaliert, der Kern der
     * Buchstaben bleibt reines Weiss (scharf=true). Ein bloss weich skaliertes BILDSCHIRMFOTO (scharf=false) verwischt
     * duenne weisse Striche mit dem dunklen Rand -- das gibt es im Spiel nicht.
     */
    static BufferedImage skalieren(BufferedImage b, double f, boolean scharf) {
        BufferedImage s = new BufferedImage((int) Math.round(b.getWidth() * f), (int) Math.round(b.getHeight() * f),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = s.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(b, 0, 0, s.getWidth(), s.getHeight(), null);
        g.dispose();
        if (!scharf) return s;
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getRGB(x, y);
                int r = (p >> 16) & 0xff, gr = (p >> 8) & 0xff, bl = p & 0xff;
                int lo = Math.min(r, Math.min(gr, bl)), hi = Math.max(r, Math.max(gr, bl));
                if (lo >= 200 && hi - lo <= 30) s.setRGB(x, y, 0xfcfcfc);
            }
        }
        return s;
    }

    static BufferedImage abdunkeln(BufferedImage b, double f) {
        BufferedImage d = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getRGB(x, y);
                d.setRGB(x, y, new Color((int) (((p >> 16) & 0xff) * f), (int) (((p >> 8) & 0xff) * f),
                        (int) ((p & 0xff) * f)).getRGB());
            }
        }
        return d;
    }

    /** Eine leere Pyramide: dunkelblauer Grund. */
    static BufferedImage pyramide() {
        BufferedImage b = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(40, 70, 120));
        g.fillRect(0, 0, W, H);
        g.dispose();
        return b;
    }

    /**
     * Setzt ein Schild (Karte, buntes Wappen links, Name) an die Lage und gibt den Name-Kasten so zurueck, wie ArenaMode
     * ihn rechnet: x-0,046W ... x+0,070W, oben+12 ... oben+33 (oben = reward.y - 80).
     */
    static Rectangle schild(BufferedImage bild, BufferedImage name, Color karte, int mitteX, int rewardY, int dx, int dy) {
        int oben = rewardY - 80;
        Graphics2D g = bild.createGraphics();
        g.setColor(karte == null ? KARTE : karte);
        g.fillRect(mitteX - 81, oben, 162, 80);
        g.setColor(new Color(230, 140, 40));   // Wappen
        g.fillRect(mitteX - 78, oben + 6, 26, 30);
        g.setColor(new Color(170, 30, 30));
        g.fillRect(mitteX - 72, oben + 12, 14, 16);
        // links am Wappen; ein langer Name reicht bis an den rechten Rand der Karte (wie "JtNXED MURENA")
        g.drawImage(name, Math.min(mitteX - 45, mitteX + 81 - name.getWidth()) + dx, oben + 22 - name.getHeight() / 2 + dy, null);
        g.dispose();
        int x0 = mitteX - (int) (W * 0.046), x1 = mitteX + (int) (W * 0.070);
        return new Rectangle(x0, oben + 12, x1 - x0, 21);
    }

    // ------------------------------------------------------------------
    //  Tests
    // ------------------------------------------------------------------

    @Test
    void derselbeNameTrifftAufJedemGrund() throws Exception {
        List<Path> alle = namen();
        assertEquals(12, alle.size());
        double schlechtester = 1;
        int faelle = 0, richtig = 0;
        List<String> fehler = new ArrayList<>();
        for (Path p : alle) {
            BufferedImage name = ImageIO.read(p.toFile());
            for (int i = 0; i < GRUENDE.length; i++) {
                for (int[] versatz : new int[][]{{0, 0}, {-6, -3}, {7, 4}}) {
                    BufferedImage bild = pyramide();
                    int[] lage = LAGEN[faelle % LAGEN.length];
                    Rectangle kasten = schild(bild, einfaerben(name, GRUENDE[i]), GRUENDE[i], lage[0], lage[1],
                            versatz[0], versatz[1]);
                    faelle++;
                    Optional<ListenBildVergleich.Treffer> t = ListenBildVergleich.besterTreffer(bild, kasten, alle);
                    if (t.isPresent() && t.get().datei().getFileName().equals(p.getFileName())) {
                        richtig++;
                        schlechtester = Math.min(schlechtester, t.get().wert());
                    } else {
                        fehler.add(p.getFileName() + "/" + GRUND_NAMEN[i] + " -> "
                                + t.map(x -> x.datei().getFileName() + " " + x.wert()).orElse("nichts"));
                    }
                }
            }
        }
        System.out.printf("Gleicher Name auf anderem Grund: %d von %d richtig, schlechtester Wert %.3f%n", richtig, faelle,
                schlechtester);
        assertTrue(fehler.isEmpty(), "nicht erkannt: " + fehler);
    }

    @Test
    void andererNameTrifftNie() throws Exception {
        List<Path> alle = namen();
        double hoechster = 0;
        int faelle = 0;
        List<String> fehler = new ArrayList<>();
        for (Path p : alle) {
            List<Path> andere = alle.stream().filter(q -> !q.equals(p)).toList();
            BufferedImage name = ImageIO.read(p.toFile());
            for (int i = 0; i < GRUENDE.length; i++) {
                BufferedImage bild = pyramide();
                int[] lage = LAGEN[faelle % LAGEN.length];
                Rectangle kasten = schild(bild, einfaerben(name, GRUENDE[i]), GRUENDE[i], lage[0], lage[1], 0, 0);
                faelle++;
                List<ListenBildVergleich.Treffer> m = ListenBildVergleich.messen(bild, kasten, andere);
                if (!m.isEmpty()) hoechster = Math.max(hoechster, m.get(0).wert());
                ListenBildVergleich.besterTreffer(bild, kasten, andere).ifPresent(t ->
                        fehler.add(p.getFileName() + " -> " + t.datei().getFileName() + " " + t.wert()));
            }
        }
        System.out.printf("Anderer Name: %d Schilder x 11 Bilder, kein Treffer erwartet, hoechster Wert %.3f (Schwelle %.3f)%n",
                faelle, hoechster, ListenBildVergleich.SCHWELLE);
        assertTrue(fehler.isEmpty(), "falsch erkannt: " + fehler);
    }

    @Test
    void etwasKleinerOderGroesserUndAbgedunkelt() throws Exception {
        List<Path> alle = namen();
        List<String> fehler = new ArrayList<>();
        int faelle = 0;
        for (Path p : alle) {
            BufferedImage name = ImageIO.read(p.toFile());
            for (double f : new double[]{0.93, 0.95, 1.05, 1.07}) {
                for (boolean scharf : new boolean[]{true, false}) {
                    BufferedImage v = skalieren(einfaerben(name, GRUENDE[f < 1 ? 1 : 3]), f, scharf);
                    BufferedImage bild = pyramide();
                    Rectangle kasten = schild(bild, v, null, 579, 512, 0, 0);
                    List<ListenBildVergleich.Treffer> m = ListenBildVergleich.messen(bild, kasten, alle);
                    boolean ok = !m.isEmpty() && m.get(0).gilt() && m.get(0).datei().equals(p);
                    if (scharf) {
                        faelle++;
                        if (!ok) fehler.add(p.getFileName() + " Groesse " + f + ": " + (m.isEmpty() ? "nichts" : m.get(0)));
                    } else if (!ok) {
                        // nur zur Auskunft: weich skaliertes Foto (gibt es im Spiel nicht)
                        System.out.printf("  (weich skaliert %.2f: %s %.3f)%n", f, p.getFileName(), m.isEmpty() ? 0 : m.get(0).wert());
                    }
                }
            }
            // ganzes Schild abgedunkelt (Schrift ~200 statt 252): die Schwelle rutscht mit
            BufferedImage bild = pyramide();
            Rectangle kasten = schild(bild, name, null, 462, 378, 0, 0);
            bild = abdunkeln(bild, 0.8);
            faelle++;
            Optional<ListenBildVergleich.Treffer> t = ListenBildVergleich.besterTreffer(bild, kasten, alle);
            if (t.isEmpty() || !t.get().datei().equals(p)) fehler.add(p.getFileName() + " dunkel");
        }
        System.out.printf("Groesse 93-107 %% (scharf) und abgedunkelt: %d von %d richtig%n", faelle - fehler.size(), faelle);
        assertTrue(fehler.isEmpty(), "nicht erkannt: " + fehler);
    }

    @Test
    void kurzerNameImLaengerenZaehltNicht() throws Exception {
        Path lore = namen().stream().filter(p -> p.getFileName().toString().equals("ar_sicher_Lore.png")).findFirst().orElseThrow();
        Path mhpm = namen().stream().filter(p -> p.getFileName().toString().equals("ar_sicher_mhpm.png")).findFirst().orElseThrow();
        BufferedImage a = ImageIO.read(lore.toFile()), b = ImageIO.read(mhpm.toFile());
        // "Loremhpm": beide Namen dicht nebeneinander -- ein laengerer Name, in dem beide stecken
        BufferedImage lang = new BufferedImage(a.getWidth() + b.getWidth() - 2, Math.max(a.getHeight(), b.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = lang.createGraphics();
        g.setColor(KARTE);
        g.fillRect(0, 0, lang.getWidth(), lang.getHeight());
        g.drawImage(a, 0, (lang.getHeight() - a.getHeight()) / 2, null);
        g.drawImage(b, a.getWidth() - 2, 0, null);
        g.dispose();
        BufferedImage bild = pyramide();
        Rectangle kasten = schild(bild, lang, null, 579, 244, 0, 0);
        List<ListenBildVergleich.Treffer> m = ListenBildVergleich.messen(bild, kasten, List.of(lore, mhpm));
        assertEquals(2, m.size());
        for (ListenBildVergleich.Treffer t : m) {
            // die Buchstaben passen (hoher Wert), aber nur ein Teil der Zeile ist abgedeckt
            assertTrue(t.abdeckung() < ListenBildVergleich.MIN_ABDECKUNG, t.name() + " Abdeckung " + t.abdeckung());
        }
        assertTrue(ListenBildVergleich.besterTreffer(bild, kasten, List.of(lore, mhpm)).isEmpty());
    }

    @Test
    void ohneNamenKeinTreffer() throws Exception {
        BufferedImage bild = pyramide();
        BufferedImage leer = new BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = leer.createGraphics();
        g.setColor(KARTE);
        g.fillRect(0, 0, 60, 20);
        g.dispose();
        Rectangle kasten = schild(bild, leer, null, 579, 244, 0, 0);
        assertTrue(ListenBildVergleich.messen(bild, kasten, namen()).isEmpty());
        assertTrue(ListenBildVergleich.besterTreffer(null, kasten, namen()).isEmpty());
        assertTrue(ListenBildVergleich.besterTreffer(bild, null, namen()).isEmpty());
        assertTrue(ListenBildVergleich.besterTreffer(bild, kasten, List.of()).isEmpty());
        // Kasten am Bildrand: kein Absturz
        assertTrue(ListenBildVergleich.besterTreffer(bild, new Rectangle(W - 5, H - 3, 134, 21), namen()).isEmpty());
    }

    @Test
    void geaendertesListenbildWirdNeuGelesen(@TempDir Path tmp) throws Exception {
        ListenBildVergleich.pruefenNachMs = 0;
        Path xenos = namen().stream().filter(p -> p.getFileName().toString().equals("ar_block_Xenos.png")).findFirst().orElseThrow();
        Path idkfa = namen().stream().filter(p -> p.getFileName().toString().equals("ar_block_IDKFA.png")).findFirst().orElseThrow();
        Path datei = tmp.resolve("ar_block_Test_1028-1200.png");
        Files.copy(xenos, datei);
        BufferedImage bild = pyramide();
        Rectangle kasten = schild(bild, einfaerben(ImageIO.read(idkfa.toFile()), GRUENDE[1]), GRUENDE[1], 579, 244, 0, 0);
        assertTrue(ListenBildVergleich.besterTreffer(bild, kasten, List.of(datei)).isEmpty());
        // Autor ersetzt das Bild: jetzt ist es IDKFA
        Files.copy(idkfa, datei, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(datei, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Optional<ListenBildVergleich.Treffer> t = ListenBildVergleich.besterTreffer(bild, kasten, List.of(datei));
        assertTrue(t.isPresent());
        assertEquals("ar_block_", t.get().liste());
        assertEquals("Test", t.get().name());
        assertTrue(kasten.intersects(t.get().stelle()), "Stelle im Bild: " + t.get().stelle());
        // kaputtes Bild: wird uebersprungen, kein Absturz
        Path kaputt = tmp.resolve("ar_sicher_Kaputt.png");
        Files.writeString(kaputt, "kein Bild");
        assertTrue(ListenBildVergleich.besterTreffer(bild, kasten, List.of(kaputt, tmp.resolve("fehlt.png"))).isEmpty());
        assertTrue(ListenBildVergleich.besterTreffer(bild, kasten, List.of(kaputt, datei)).isPresent());
    }

    @Test
    void laufzeitEinePyramide(@TempDir Path tmp) throws Exception {
        // ~50 Listenbilder (die 12 echten, mehrfach unter anderem Namen) und 9 Schilder
        List<Path> echte = namen();
        List<Path> liste = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Path p = echte.get(i % echte.size());
            Path kopie = tmp.resolve(p.getFileName().toString().replace(".png", "_" + i + ".png"));
            Files.copy(p, kopie);
            liste.add(kopie);
        }
        BufferedImage bild = pyramide();
        List<Rectangle> kaesten = new ArrayList<>();
        for (int i = 0; i < LAGEN.length; i++) {
            BufferedImage name = einfaerben(ImageIO.read(echte.get(i).toFile()), GRUENDE[i % GRUENDE.length]);
            kaesten.add(schild(bild, name, GRUENDE[i % GRUENDE.length], LAGEN[i][0], LAGEN[i][1], 0, 0));
        }
        long t0 = System.nanoTime();
        int treffer = 0;
        for (Rectangle k : kaesten) if (ListenBildVergleich.besterTreffer(bild, k, liste).isPresent()) treffer++;
        long erst = System.nanoTime() - t0;
        long bester = Long.MAX_VALUE;
        for (int runde = 0; runde < 5; runde++) {
            long t1 = System.nanoTime();
            for (Rectangle k : kaesten) ListenBildVergleich.besterTreffer(bild, k, liste);
            bester = Math.min(bester, System.nanoTime() - t1);
        }
        System.out.printf("Laufzeit: 9 Schilder x 50 Listenbilder = %.1f ms (erster Durchgang mit Laden %.1f ms), %d Treffer%n",
                bester / 1e6, erst / 1e6, treffer);
        assertEquals(9, treffer);
        assertTrue(bester < 3_000_000_000L, "zu langsam: " + bester / 1e6 + " ms");
    }
}
