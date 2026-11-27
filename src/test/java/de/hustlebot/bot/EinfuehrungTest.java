package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 21.11.: Event-Einfuehrungen selbst durchspielen. Bilder: Burg mit Pfeil auf eine Figur (echtes Bild
 * 1159x770), "Congratulations"-Fenster (echtes Bild), dazu Bildschirmfotos (0,3-fach) aus zwei Event-Einfuehrungen
 * -- die werden hier wieder auf 1160 Breite vergroessert.
 */
class EinfuehrungTest {

    private static final Path ORDNER = ordner("/einfuehrung/burg-pfeil-koch.jpg");
    /** Die Vorlagen aus dem Nachschub (liegen auf dem Klassenpfad, unabhaengig vom Arbeitsordner der Tests). */
    private static final Path VORLAGEN = ordner("/nachschub/gelernt/stoer/einf_namensband.png");

    private static Path ordner(String datei) {
        try {
            return Path.of(EinfuehrungTest.class.getResource(datei).toURI()).getParent();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat bild(String name) {
        Mat m = Imgcodecs.imread(ORDNER.resolve(name).toString(), Imgcodecs.IMREAD_COLOR);
        assertFalse(m.empty(), name);
        if (m.width() < 900) {   // Bildschirmfoto 0,3-fach -> Spielgroesse
            Mat gross = new Mat();
            double f = 1160.0 / m.width();
            Imgproc.resize(m, gross, new Size(), f, f, Imgproc.INTER_CUBIC);
            m.release();
            return gross;
        }
        return m;
    }

    private static Matcher matcher(Path tmp) throws Exception {
        for (String n : new String[]{"einf_namensband.png", "st_glueckwunsch.png", "st_zur_mailbox.png"}) {
            Files.copy(VORLAGEN.resolve(n), tmp.resolve(n));
        }
        Matcher m = new Matcher(tmp);
        m.loadAll();
        assertTrue(m.has(Einfuehrung.T_NAMENSBAND));
        return m;
    }

    private static Einfuehrung.Pfeil einPfeil(String name) {
        Mat b = bild(name);
        List<Einfuehrung.Pfeil> p = Einfuehrung.pfeile(b);
        b.release();
        assertEquals(1, p.size(), name + ": " + p);
        return p.get(0);
    }

    /** Burg: der Pfeil zeigt nach links auf die Figur -- getippt wird die Figur, nicht der Pfeil. */
    @Test
    void pfeilInDerBurgZeigtAufDenKoch() {
        Einfuehrung.Pfeil p = einPfeil("burg-pfeil-koch.jpg");
        assertTrue(p.richtungX() < -0.9, "nach links: " + p);
        assertEquals(616, p.spitzeX(), 8);
        assertEquals(380, p.spitzeY(), 10);
        Point z = p.ziel();
        assertTrue(z.x > 530 && z.x < 605, "Koch (x 512-612): " + z);
        assertTrue(z.y > 330 && z.y < 430, "Koch (y 300-460): " + z);
    }

    /** Im Event-Fenster: Pfeile nach oben auf die Reiter, nach links auf Frogus bzw. den Store im Brett. */
    @Test
    void pfeileImEventZeigenAufReiterUndZiele() {
        Einfuehrung.Pfeil lottery = einPfeil("grim-pfeil-lottery.png");
        assertTrue(lottery.richtungY() < -0.9, "nach oben: " + lottery);
        assertEquals(235, lottery.spitzeX(), 12);
        assertTrue(lottery.ziel().y < lottery.spitzeY() - 20 && lottery.ziel().y > 120, "Reiter Lottery: " + lottery.ziel());

        Einfuehrung.Pfeil rankings = einPfeil("grim-pfeil-rankings.png");
        assertTrue(rankings.richtungY() < -0.9, "nach oben: " + rankings);
        assertEquals(726, rankings.spitzeX(), 12);

        Einfuehrung.Pfeil kitchen = einPfeil("rest-pfeil-kitchen.png");
        assertTrue(kitchen.richtungY() < -0.9, "nach oben: " + kitchen);

        Einfuehrung.Pfeil frogus = einPfeil("grim-frogus-pfeil.png");
        assertTrue(frogus.richtungX() < -0.9, "nach links: " + frogus);

        Einfuehrung.Pfeil store = einPfeil("rest-pfeil-store.png");
        assertTrue(store.richtungX() < -0.9, "nach links: " + store);
        assertEquals(591, store.spitzeX(), 12);
    }

    /** Kein Pfeil auf Bildern ohne Einfuehrungs-Pfeil -- auch nicht auf allen anderen grossen Testbildern des Bots. */
    @Test
    void keinPfeilAufNormalenBildern() throws Exception {
        for (String n : new String[]{"glueckwunsch-mailbox.jpg", "grim-sage-blase.png", "grim-fertig.png", "grim-store-buy.png",
                "rest-hand.png"}) {
            Mat b = bild(n);
            assertEquals(List.of(), Einfuehrung.pfeile(b), n);
            b.release();
        }
        Path wurzel = ORDNER.getParent();
        List<Path> alle;
        try (Stream<Path> s = Files.walk(wurzel)) {
            alle = s.filter(p -> !p.startsWith(ORDNER))
                    .filter(p -> p.toString().endsWith(".png") || p.toString().endsWith(".jpg")).toList();
        }
        int gross = 0;
        for (Path p : alle) {
            Mat b = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_COLOR);
            if (b.empty() || b.width() < 900) {
                b.release();
                continue;
            }
            gross++;
            assertEquals(List.of(), Einfuehrung.pfeile(b), p.toString());
            b.release();
        }
        // Die offene Fassung hat weniger grosse Testbilder (private Bildschirmfotos fehlen) -- ein paar muessen es sein.
        assertTrue(gross >= 3, "zu wenige grosse Testbilder: " + gross);
    }

    /** Sprechblase am Namensband: Burg (Abyss Lord), Grim (Sage rechts, Frogus links), Event-Fenster; nicht ohne Blase. */
    @Test
    void sprechblaseErkannt(@TempDir Path tmp) throws Exception {
        Matcher m = matcher(tmp);
        for (String n : new String[]{"burg-pfeil-koch.jpg", "grim-sage-blase.png", "grim-frogus-pfeil.png",
                "rest-pfeil-store.png", "grim-store-buy.png"}) {
            Mat b = bild(n);
            Optional<Matcher.Hit> h = Einfuehrung.sprechblase(b, m);
            assertTrue(h.isPresent(), n + ": keine Sprechblase");
            assertTrue(h.get().y() > 540 && h.get().y() < 600, n + ": " + h.get());
            Point t = Einfuehrung.textStelle(h.get(), b.width(), b.height());
            assertTrue(t.y > 630 && t.y < 720, n + ": Text " + t);
            b.release();
        }
        Mat burg = bild("burg-pfeil-koch.jpg");
        Point t = Einfuehrung.textStelle(Einfuehrung.sprechblase(burg, m).get(), burg.width(), burg.height());
        assertEquals(499, t.x, 10);   // mitten im Text "I HAVE DISCOVERED ..." (x 290-930, y 630-740)
        burg.release();
        for (String n : new String[]{"glueckwunsch-mailbox.jpg", "grim-pfeil-lottery.png", "grim-pfeil-rankings.png", "grim-fertig.png"}) {
            Mat b = bild(n);
            assertTrue(Einfuehrung.sprechblase(b, m).isEmpty(), n + ": Sprechblase, wo keine ist");
            b.release();
        }
        for (String ordner : new String[]{"verlies-clan", "burg", "event-fenster", "stoerbilder", "kathedrale"}) {
            try (Stream<Path> s = Files.list(ORDNER.getParent().resolve(ordner))) {
                for (Path p : s.filter(p -> p.toString().endsWith(".png") || p.toString().endsWith(".jpg")).toList()) {
                    Mat b = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_COLOR);
                    if (b.width() >= 900) assertTrue(Einfuehrung.sprechblase(b, m).isEmpty(), p + ": Sprechblase, wo keine ist");
                    b.release();
                }
            }
        }
    }

    /** Autor 21.11.: "Congratulations ... Go to mailbox" -- Knopf nur mit Ueberschrift. */
    @Test
    void glueckwunschFensterMitMailboxKnopf(@TempDir Path tmp) throws Exception {
        Matcher m = matcher(tmp);
        Mat b = bild("glueckwunsch-mailbox.jpg");
        Optional<Matcher.Hit> k = Stoerfenster.glueckwunschKnopf(b, m);
        assertTrue(k.isPresent());
        assertEquals(580, k.get().centerX(), 10);
        assertEquals(492, k.get().centerY(), 10);
        b.release();
        for (String n : new String[]{"burg-pfeil-koch.jpg", "grim-store-buy.png", "grim-fertig.png"}) {
            Mat x = bild(n);
            assertTrue(Stoerfenster.glueckwunschKnopf(x, m).isEmpty(), n);
            x.release();
        }
    }

    /** Hand: aus der Spur der Fingerspitze Start und Ende (gleichmaessig hin, dann Rueck-Sprung). */
    @Test
    void handSpurGibtStartUndEnde() {
        List<Point> spur = new ArrayList<>();
        for (int runde = 0; runde < 2; runde++) {
            for (int i = 0; i <= 8; i++) spur.add(new Point(600, 200 + i * 12));
        }
        Einfuehrung.Hand h = Einfuehrung.bewegungAusSpur(spur).orElseThrow();
        assertEquals(200, h.startY(), 1);
        assertEquals(296, h.endeY(), 1);
        // Umgekehrte Richtung: von unten nach oben.
        List<Point> rueck = new ArrayList<>();
        for (int runde = 0; runde < 2; runde++) {
            for (int i = 0; i <= 8; i++) rueck.add(new Point(600, 296 - i * 12));
        }
        Einfuehrung.Hand r = Einfuehrung.bewegungAusSpur(rueck).orElseThrow();
        assertEquals(296, r.startY(), 1);
        assertEquals(200, r.endeY(), 1);
        // Stillstand ist keine Bewegung.
        assertTrue(Einfuehrung.bewegungAusSpur(List.of(new Point(5, 5), new Point(6, 5), new Point(5, 6), new Point(6, 6))).isEmpty());
    }

    /** Hand in einer Bildreihe: hautfarbene Hand gleitet ueber die Burg von (600,150) nach (600,270). */
    @Test
    void handInBildreiheGefunden() {
        Mat grund = bild("burg-pfeil-koch.jpg");
        List<Mat> reihe = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Mat b = grund.clone();
            int y = 150 + (i % 8) * 17;   // 8 Bilder hin, dann Sprung zurueck
            Imgproc.ellipse(b, new Point(620, y + 45), new Size(26, 45), 0, 0, 360, new Scalar(165, 175, 235), -1);
            reihe.add(b);
        }
        Optional<Einfuehrung.Hand> h = Einfuehrung.hand(reihe, 570);
        assertTrue(h.isPresent());
        assertEquals(150, h.get().startY(), 12);
        assertEquals(269, h.get().endeY(), 12);
        assertEquals(620, h.get().startX(), 12);
        for (Mat m : reihe) m.release();
        grund.release();
    }

    /** Nie kaufen: gruener Knopf mit Preis/"Buy" wird nicht getippt. */
    @Test
    void kaufKnopfErkannt() {
        assertTrue(Einfuehrung.kaufText("Buy 255"));
        assertTrue(Einfuehrung.kaufText("1 300"));
        assertTrue(Einfuehrung.kaufText(""));
        assertFalse(Einfuehrung.kaufText("Kitchen"));
        assertFalse(Einfuehrung.kaufText("Lottery"));
        Mat b = bild("glueckwunsch-mailbox.jpg");
        assertTrue(Einfuehrung.gruenerKnopf(b, new Point(580, 492)), "Go to mailbox ist gruen");
        assertFalse(Einfuehrung.gruenerKnopf(b, new Point(580, 380)), "Fenstertext ist nicht gruen");
        b.release();
    }
}
