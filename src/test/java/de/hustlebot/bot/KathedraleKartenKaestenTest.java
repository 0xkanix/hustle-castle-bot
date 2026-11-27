package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hustlebot.core.Matcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Autor 21.11.: "Der Bot erkennt am Anfang, welches Muster die Etage hat, und sucht dann nur noch an den Positionen der
 * Wegpunkte, die sind je Muster immer gleich. Ist ein Muster unbekannt: Fehlerbericht."
 *
 * Die Pruefung mit ECHTEN Bildern (nur auf Autors Rechner, sonst uebersprungen): Fuer jedes Etagenbild aus logs\etagen
 * wird im ganzen Bild und in den Kaesten an den Stuetzpunkten gesucht -- dieselben Vorlagen, Schwellen und Wege wie in
 * der Kathedrale. Verlangt sind dieselben Treffer (Vorlage und Stelle auf 5 Punkte genau). Die Zeit je Bild steht im
 * Testprotokoll. Nur auf Wunsch (dauert einige Minuten): mvn-reihe.sh -Dtest=KathedraleKartenKaestenTest -Dkaesten.messen=true
 */
class KathedraleKartenKaestenTest {

    private static final int W = 1159, H = 770;
    private static final String[] MOTIVE = {"dc_cube", "dc_skirmish", "dc_minion", "dc_altar", "dc_camp", "dc_danger", "dc_ally", "dc_arena"};

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    // ---------------------------------------------------------------- ohne echte Bilder

    @Test
    void kaestenLiegenUmDieStuetzpunkteDerKarte() {
        for (int f = 0; f < 5; f++) {
            List<Rect> k = Etagennetz.symbolKaesten(f, W, H);
            assertEquals(14, k.size(), "jede feste Karte hat 14 Stuetzpunkte");
        }
        // Gitter, erster Stuetzpunkt 599,154: Mitte darf 30 links/rechts, 90 darueber, 40 darunter liegen.
        Rect r = Etagennetz.symbolKaesten(0, W, H).get(0);
        assertEquals(569, r.x);
        assertEquals(64, r.y);
        assertEquals(61, r.width);
        assertEquals(131, r.height);
        // Doppelt so grosses Fenster: alles doppelt so gross.
        Rect g = Etagennetz.symbolKaesten(0, 2 * W, 2 * H).get(0);
        assertEquals(1138, g.x);
        assertEquals(128, g.y);
    }

    @Test
    void ohneFesteKarteOderOhnePassendesBildKeineKaesten() {
        Etagennetz netz = new Etagennetz();
        assertNull(netz.symbolKaesten(List.of(), W, H), "ohne feste Karte: ganzes Bild");
        List<Matcher.Hit> stern = sockelVon(2, 0, 0, 14);
        assertEquals(2, Etagennetz.festeKarteIm(stern, W));
        netz.beobachten(Mat.zeros(H, W, CvType.CV_8UC3), stern, List.of(), List.of(), List.of(), Optional.empty(),
                List.of(), List.of(), List.of(), false);
        assertTrue(netz.festeKarte());
        assertNotNull(netz.symbolKaesten(stern, W, H));
        assertNull(netz.unbekanntesMusterAbholen(), "eine feste Karte ist kein unbekanntes Muster");
        // Nur 7 Sockel zu sehen (Schriftzug davor): ganzes Bild.
        assertNull(netz.symbolKaesten(sockelVon(2, 0, 0, 7), W, H));
        // Das Bild zeigt eine andere Karte (schon die naechste Etage): ganzes Bild.
        assertNull(netz.symbolKaesten(sockelVon(4, 0, 0, 14), W, H));
        // Leicht verschoben (gemessen hoechstens 4 Punkte): Kaesten.
        assertNotNull(netz.symbolKaesten(sockelVon(2, 5, -5, 14), W, H));
    }

    @Test
    void unbekanntesMusterWirdEinmalGemeldet() {
        Etagennetz netz = new Etagennetz();
        List<Matcher.Hit> fremd = new ArrayList<>();
        for (int i = 0; i < 12; i++) fremd.add(sockel(150 + 80 * i, 420));
        netz.beobachten(Mat.zeros(H, W, CvType.CV_8UC3), fremd, List.of(), List.of(), List.of(), Optional.empty(),
                List.of(), List.of(), List.of(), false);
        assertFalse(netz.festeKarte());
        String[] u = netz.unbekanntesMusterAbholen();
        assertNotNull(u, "zwoelf Sockel, keine feste Karte: unbekanntes Muster");
        assertFalse(u[0].isBlank());
        assertTrue(u[1].contains("12"), u[1]);
        assertNull(netz.unbekanntesMusterAbholen(), "nur einmal abholen");
        netz.beobachten(Mat.zeros(H, W, CvType.CV_8UC3), fremd, List.of(), List.of(), List.of(), Optional.empty(),
                List.of(), List.of(), List.of(), false);
        assertNull(netz.unbekanntesMusterAbholen(), "auf derselben Etage nicht noch einmal");
        assertEquals(Etagennetz.fingerabdruck(List.of(new int[]{150, 420}, new int[]{230, 421})),
                Etagennetz.fingerabdruck(List.of(new int[]{232, 419}, new int[]{151, 418})), "kleine Verschiebung, gleicher Abdruck");
    }

    @Test
    void jeFingerabdruckEinmalAmTagZusammenHoechstensDrei(@TempDir Path d) {
        Path datei = d.resolve("gemeldet.txt");
        assertTrue(CathedralMode.musterMeldenErlaubt(datei, "2026-11-21", "a1"));
        assertFalse(CathedralMode.musterMeldenErlaubt(datei, "2026-11-21", "a1"), "derselbe Abdruck am selben Tag");
        assertTrue(CathedralMode.musterMeldenErlaubt(datei, "2026-11-21", "b2"));
        assertTrue(CathedralMode.musterMeldenErlaubt(datei, "2026-11-21", "c3"));
        assertFalse(CathedralMode.musterMeldenErlaubt(datei, "2026-11-21", "d4"), "hoechstens drei am Tag");
        assertTrue(CathedralMode.musterMeldenErlaubt(datei, "2026-11-22", "a1"), "am naechsten Tag wieder");
    }

    @Test
    void suchenInKaestenFindetDasselbeWieImGanzenBild(@TempDir Path ordner) {
        Mat vorlage = rauschen(34, 65, 5);
        Imgcodecs.imwrite(ordner.resolve("dc_minion.png").toString(), vorlage);
        Matcher m = new Matcher(ordner);
        m.loadAll();
        Mat bild = rauschen(W, H, 9);
        List<Rect> kaesten = Etagennetz.symbolKaesten(2, W, H);   // Stern
        int[][] stellen = {{608, 145 - 31}, {610, 260 - 31}, {360, 190 - 60}, {829, 191 + 15}};   // Mitten an Stuetzpunkten
        for (int[] s : stellen) vorlage.copyTo(bild.submat(new Rect(s[0] - 17, s[1] - 32, 34, 65)));
        List<String> ganz = namen(m.findAll(bild, "dc_minion", 0.84, 6));
        List<String> kasten = namen(m.findAllInKaesten(bild, "dc_minion", 0.84, 6, kaesten));
        assertEquals(4, ganz.size());
        assertEquals(ganz.stream().sorted().toList(), kasten.stream().sorted().toList(), "gleiche Treffer an gleichen Stellen");
        Matcher.Hit bester = m.findInKaesten(bild, "dc_minion", kaesten, 0.84).orElseThrow();
        assertTrue(bester.score() > 0.99 && ganz.contains(bester.name() + "@" + bester.x() + "," + bester.y()));
        assertEquals(1, m.findAllInKaesten(bild, "dc_minion", 0.84, 1, kaesten).size(), "hoechstens maxHits");
        // Ein Treffer weit weg von jedem Stuetzpunkt wird in den Kaesten nicht gesucht -- das ist der Sinn.
        vorlage.copyTo(bild.submat(new Rect(40, 700, 34, 65)));
        assertEquals(5, m.findAll(bild, "dc_minion", 0.84, 6).size());
        assertEquals(4, m.findAllInKaesten(bild, "dc_minion", 0.84, 6, kaesten).size());
    }

    // ---------------------------------------------------------------- mit echten Bildern (Autors Rechner)

    @Test
    void echteEtagenbilderGleicheTrefferInDenKaesten() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("kaesten.messen"), "nur auf Wunsch: -Dkaesten.messen=true (dauert einige Minuten)");
        Assumptions.assumeFalse(Path.of("").toAbsolutePath().endsWith("speicher-pruefung"), "nur einmal messen");
        org.opencv.core.Core.setNumThreads(2);
        // Die Etagenbilder des Bots (alle muessen eine feste Karte zeigen), dazu weitere Fotos, auf denen eine Etagenkarte
        // sein KANN (Waechter-fehlt-Bilder, Fotoserien und Spielbilder aus Freunde-Berichten). Zeigt so ein Foto keine
        // feste Karte sicher, sucht der Bot dort wie bisher im ganzen Bild -- dann gibt es nichts zu vergleichen.
        List<Path> etagen = bilderIn(Path.of("C:/HustleBot/logs/etagen"), Path.of("C:/hustle-bot/logs/etagen"));
        List<Path> bilder = new ArrayList<>(etagen);
        bilder.addAll(bilderIn(Path.of("C:/hustle-bot/logs/waechter-fehlt"), Path.of("C:/HustleBot/logs/waechter-fehlt")));
        bilder.addAll(berichtBilder(false));
        Assumptions.assumeFalse(bilder.isEmpty(), "keine Etagenbilder auf diesem Rechner");
        boolean voll = !Boolean.getBoolean("kaesten.kurz");
        Path vorlagen = Files.isDirectory(Path.of("C:/HustleBot/templates")) ? Path.of("C:/HustleBot/templates") : Path.of("templates");
        Matcher m = new Matcher(vorlagen);
        m.loadAll();
        m.setGrauErlaubt(true);   // wie in der Kathedrale
        List<String> schnell = new ArrayList<>();
        for (String base : MOTIVE) schnell.addAll(varianten(m, base));
        schnell.add("dc_question");
        schnell.add("lock_icon");
        List<String> bosse = varianten(m, "dc_minion");
        List<String> alleGenau = new ArrayList<>(bosse);
        for (String n : schnell) if (!alleGenau.contains(n)) alleGenau.add(n);   // dieselben fuer wegsuche_schnell = aus

        int geprueft = 0, ohneKarte = 0, ohneKarteEtagen = 0, abweichungen = 0, treffer = 0, gelesen = 0;
        long ganzNs = 0, kastenNs = 0, ganzBossWaechterNs = 0, kastenBossWaechterNs = 0;
        int[] jeKarte = new int[5];
        StringBuilder fehler = new StringBuilder();
        for (Path p : bilder) {
            Mat bild = Imgcodecs.imread(p.toString());
            if (bild.empty()) continue;
            gelesen++;
            int f = Etagennetz.festeKarteIm(CathedralMode.sockelFuerNetz(bild, false), bild.cols());
            if (f < 0) {
                ohneKarte++;
                if (etagen.contains(p)) ohneKarteEtagen++;
                bild.release();
                continue;
            }
            jeKarte[f]++;
            geprueft++;
            List<Rect> kaesten = Etagennetz.symbolKaesten(f, bild.cols(), bild.rows());
            Rect karte = CathedralMode.kartenBereich(bild);
            m.neuesBild();
            m.findAllSchnell(bild, "lock_icon", new Rect(0, 0, 64, 64), 0.99, 0.99, 1);   // Halbbild und Graubild vorab
            m.findAll(bild, "dc_question", 2.0, 1);
            for (String name : schnell) {
                int max = name.equals("lock_icon") ? 20 : name.equals("dc_question") ? 8 : 6;
                long t0 = System.nanoTime();
                List<Matcher.Hit> a = m.findAllSchnell(bild, name, karte, 0.70, 0.84, max);
                long t1 = System.nanoTime();
                List<Matcher.Hit> b = m.findAllSchnellInKaesten(bild, name, kaesten, karte, 0.70, 0.84, max);
                long t2 = System.nanoTime();
                ganzNs += t1 - t0;
                kastenNs += t2 - t1;
                treffer += a.size();
                abweichungen += vergleichen(a, b, p + " schnell " + name, fehler);
            }
            // Die Bosse (immer genaue Suche) auf jedem Bild; die uebrigen Vorlagen genau (nur mit wegsuche_schnell = aus)
            // auf jedem vierten -- sonst dauert der Test ueber eine Minute (am 21.11. einmal ganz: 0 Abweichungen).
            List<String> genau = voll || geprueft % 4 == 1 ? alleGenau : bosse;
            for (String name : genau) {
                int max = name.equals("lock_icon") ? 20 : name.equals("dc_question") ? 8 : 6;
                long t0 = System.nanoTime();
                List<Matcher.Hit> a = m.findAll(bild, name, 0.84, max);
                long t1 = System.nanoTime();
                List<Matcher.Hit> b = m.findAllInKaesten(bild, name, 0.84, max, kaesten);
                long t2 = System.nanoTime();
                if (name.startsWith("dc_minion")) {
                    ganzBossWaechterNs += t1 - t0;
                    kastenBossWaechterNs += t2 - t1;
                }
                treffer += a.size();
                abweichungen += vergleichen(a, b, p + " genau " + name, fehler);
            }
            if (m.has("dc_guardian")) {
                long t0 = System.nanoTime();
                List<Matcher.Hit> a = m.findAll(bild, "dc_guardian", 0.84, 1);   // der beste im ganzen Bild
                long t1 = System.nanoTime();
                Optional<Matcher.Hit> b = m.findInKaesten(bild, "dc_guardian", kaesten, 0.84);
                long t2 = System.nanoTime();
                ganzBossWaechterNs += t1 - t0;
                kastenBossWaechterNs += t2 - t1;
                treffer += a.size();
                abweichungen += vergleichen(a, b.map(List::of).orElse(List.of()), p + " waechter", fehler);
            }
            bild.release();
        }
        System.out.printf("Kartenkaesten: %d Bilder gelesen, %d mit sicherer fester Karte (Gitter %d, Schnecke %d, Stern %d, Raute %d,"
                        + " Ring %d), %d ohne (davon %d aus logs/etagen), Vorlagen %s, %d Treffer, %d Abweichungen.%n", gelesen, geprueft,
                jeKarte[0], jeKarte[1], jeKarte[2], jeKarte[3], jeKarte[4], ohneKarte, ohneKarteEtagen, vorlagen, treffer, abweichungen);
        if (geprueft > 0) {
            System.out.printf("  Halbbild-Suche (%d Vorlagen: Motive, Fragezeichen, Schloss) je Bild: ganz %.1f ms, Kaesten %.1f ms%n",
                    schnell.size(), ganzNs / 1e6 / geprueft, kastenNs / 1e6 / geprueft);
            System.out.printf("  Boss + Waechter (dc_minion, dc_guardian) je Bild: ganz %.1f ms, Kaesten %.1f ms%n",
                    ganzBossWaechterNs / 1e6 / geprueft, kastenBossWaechterNs / 1e6 / geprueft);
        }
        if (fehler.length() > 0) System.out.println(fehler);
        assertEquals(0, abweichungen, "Kaesten muessen dasselbe finden wie das ganze Bild:\n" + fehler);
        assertEquals(0, ohneKarteEtagen, "jedes Etagenbild aus logs/etagen ist eine der fuenf festen Karten");
    }

    // ---------------------------------------------------------------- Hilfen

    /** Zaehlt Treffer, die nur auf einer Seite stehen (Vorlage gleich, Mitte hoechstens 5 Punkte daneben). */
    static int vergleichen(List<Matcher.Hit> ganz, List<Matcher.Hit> kasten, String wo, StringBuilder fehler) {
        int n = 0;
        for (Matcher.Hit a : ganz) {
            if (kasten.stream().noneMatch(b -> gleich(a, b))) {
                n++;
                if (fehler.length() < 4000) fehler.append("  fehlt in den Kaesten: ").append(wo).append(' ').append(a).append('\n');
            }
        }
        for (Matcher.Hit b : kasten) {
            if (ganz.stream().noneMatch(a -> gleich(a, b))) {
                n++;
                if (fehler.length() < 4000) fehler.append("  nur in den Kaesten: ").append(wo).append(' ').append(b).append('\n');
            }
        }
        return n;
    }

    private static boolean gleich(Matcher.Hit a, Matcher.Hit b) {
        return a.name().equals(b.name()) && Math.abs(a.centerX() - b.centerX()) <= 5 && Math.abs(a.centerY() - b.centerY()) <= 5;
    }

    private static List<String> varianten(Matcher m, String base) {
        List<String> aus = new ArrayList<>();
        for (String n : m.names()) if ((n.equals(base) || n.startsWith(base + "_")) && !n.endsWith("_screen")) aus.add(n);
        java.util.Collections.sort(aus);
        return aus;
    }

    private static List<Path> bilderIn(Path... ordner) throws Exception {
        List<Path> aus = new ArrayList<>();
        for (Path o : ordner) {
            if (!Files.isDirectory(o)) continue;
            try (Stream<Path> s = Files.list(o)) {
                s.filter(p -> p.toString().endsWith(".jpg") || p.toString().endsWith(".png")).sorted().forEach(aus::add);
            }
        }
        return aus;
    }

    /** Fotoserien der Kathedrale und Spielbilder aus den Freunde-Berichten (nur Autors Rechner). */
    static List<Path> berichtBilder(boolean nurFotoserien) throws Exception {
        List<Path> aus = new ArrayList<>();
        Path freunde = Path.of("C:/hustle-bot/fehlerberichte/freunde");
        if (!Files.isDirectory(freunde)) return aus;
        try (Stream<Path> s = Files.walk(freunde, 3)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                boolean serie = p.getParent() != null && p.getParent().getFileName().toString().equals("fotoserie-kathedrale")
                        && n.endsWith(".jpg");
                return serie || (!nurFotoserien && n.equals("spielbild.png"));
            }).sorted().forEach(aus::add);
        }
        return aus;
    }

    private static List<String> namen(List<Matcher.Hit> hits) {
        List<String> aus = new ArrayList<>();
        for (Matcher.Hit h : hits) aus.add(h.name() + "@" + h.x() + "," + h.y());
        return aus;
    }

    private static Matcher.Hit sockel(int x, int y) {
        return new Matcher.Hit("sockel", x - 20, y - 10, 40, 20, 1.0);
    }

    /** Die ersten n Stuetzpunkte der festen Karte f als Sockel, um (dx, dy) verschoben. */
    private static List<Matcher.Hit> sockelVon(int f, int dx, int dy, int n) {
        List<Matcher.Hit> aus = new ArrayList<>();
        for (Rect r : Etagennetz.symbolKaesten(f, W, H)) {
            if (aus.size() >= n) break;
            int x = r.x + 30, y = r.y + 90;   // zurueck zur Mitte des Stuetzpunkts (Kasten: 30 seitlich, 90 darueber)
            aus.add(sockel(x + dx, y + dy));
        }
        return aus;
    }

    private static Mat rauschen(int w, int h, long samen) {
        byte[] b = new byte[w * h * 3];
        new Random(samen).nextBytes(b);
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        m.put(0, 0, b);
        return m;
    }
}
