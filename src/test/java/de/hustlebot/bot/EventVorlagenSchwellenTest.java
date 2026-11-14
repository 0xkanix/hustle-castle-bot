package de.hustlebot.bot;

import de.hustlebot.core.Matcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 28.10.: Passen die Belohnungs-Vorlagen, auch bei Belohnungen, die fast gleich aussehen? Und Kraken-Kisten nie.
 *
 * 23 echte Felder aus den Event-Fotos (144x124, Mitte = Feldmitte; Quelle C:\HustleBot\logs\event-ende/-stab-*.png),
 * verglichen wie der Bot im Event: farbig, TM_CCOEFF_NORMED, milder Treffer nur mittig (Mitte hoechstens 24/20
 * Bildpunkte neben der Feldmitte), ab 0,78 ueberall.
 */
class EventVorlagenSchwellenTest {

    private static final Path VORLAGEN = Path.of("templates");
    private static final double KISTEN_SCHWELLE = 0.78;   // wie EventMode.KISTEN_SCHWELLE
    private static final double ABSTAND = 0.05;

    private static final Map<String, Mat> gute = new TreeMap<>();
    private static final Map<String, Mat> kraken = new TreeMap<>();
    private static final Map<String, Mat> bilder = new LinkedHashMap<>();

    @BeforeAll
    static void laden() throws Exception {
        nu.pattern.OpenCV.loadLocally();
        Assumptions.assumeTrue(Files.isDirectory(VORLAGEN.resolve("event-belohnungen")));
        for (Path ordner : List.of(VORLAGEN.resolve("event-belohnungen"), VORLAGEN.resolve("gelernt/event-belohnungen"))) {
            File[] dateien = ordner.toFile().listFiles((d, n) -> n.endsWith(".png"));
            if (dateien == null) continue;
            for (File f : dateien) {
                String n = f.getName().substring(0, f.getName().length() - 4);
                if ((n.startsWith("ev_kiste_") && !n.equals("ev_kiste_hc")) || n.equals("ev_gems")) gute.put(n, lies(f.toPath()));
                if (EventVorlagenSchwellen.KRAKEN.contains(n)) kraken.put(n, lies(f.toPath()));
            }
        }
        Path res = Path.of(EventVorlagenSchwellenTest.class.getResource("/event-belohnungen/kraken_blau.png").toURI()).getParent();
        File[] fs = res.toFile().listFiles((d, n) -> n.endsWith(".png"));
        assertNotNull(fs);
        java.util.Arrays.sort(fs);
        for (File f : fs) bilder.put(f.getName().substring(0, f.getName().length() - 4), lies(f.toPath()));
        assertEquals(3, kraken.size(), "alle drei Kraken-Vorlagen da");
        assertTrue(gute.size() >= 30, "Kisten-Vorlagen geladen: " + gute.size());
    }

    @AfterAll
    static void frei() {
        gute.values().forEach(Mat::release);
        kraken.values().forEach(Mat::release);
        bilder.values().forEach(Mat::release);
    }

    private static Mat lies(Path p) {
        Mat m = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_COLOR);
        assertFalse(m.empty(), "Bild nicht lesbar: " + p);
        return m;
    }

    /** Wie EventMode.mildeSchwelle: *_feld und Nahrung 0,70, sonst 0,73. */
    private static double standard(String n) {
        return n.endsWith("_feld") || n.equals("ev_kiste_essen") || n.startsWith("ev_kiste_essen_") ? 0.70 : 0.73;
    }

    private static double schwelle(String n) {
        return EventVorlagenSchwellen.schwelle(n, standard(n));
    }

    /** Bester Wert, Vorlagen-Mitte hoechstens 24/20 neben der Bildmitte (= Feldmitte). */
    private static double mittig(Mat bild, Mat t) {
        int x = bild.cols() / 2 - t.cols() / 2 - 24, y = bild.rows() / 2 - t.rows() / 2 - 20;
        return bester(new Mat(bild, new Rect(x, y, t.cols() + 48, t.rows() + 40)), t);
    }

    private static double ueberall(Mat bild, Mat t) {
        return bester(bild, t);
    }

    private static double bester(Mat bild, Mat t) {
        Mat r = new Mat();
        try {
            Imgproc.matchTemplate(bild, t, r, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(r).maxVal;
        } finally {
            r.release();
        }
    }

    /** Familien, die der Bot alle nimmt -- Verwechslung innerhalb ist harmlos. */
    private static String familie(String n) {
        n = n.replace("ev_kiste_", "");
        if (n.startsWith("essenz")) return "essenz";
        if (n.startsWith("extra")) return "hammer";
        if (n.contains("kristall") || n.startsWith("diamant") || n.equals("ev_gems")) return "blau";
        if (n.startsWith("essen")) return "essen";
        if (n.startsWith("holz") || n.startsWith("gold") || n.startsWith("metall") || n.startsWith("elixier")) return "kiste";
        if (n.startsWith("mithril")) return "mithril";
        if (n.startsWith("fackeln")) return "fackeln";
        if (n.startsWith("schluessel")) return "schluessel";
        if (n.startsWith("schatz_gold")) return "schatz_gold";
        if (n.startsWith("schatz_gruen")) return "schatz_gruen";
        if (n.startsWith("rolle")) return "rolle";
        return n;
    }

    /** Was im Bild liegt: Familie, "kraken" oder "fremd" (Belohnung, die der Bot nicht nehmen soll). */
    private static String art(String bild) {
        if (bild.startsWith("kraken")) return "kraken";
        if (bild.startsWith("nie_") || bild.startsWith("fremd_")) return "fremd";
        return familie(bild.replaceAll("_\\d+$", "").replace("essen_korb", "essen"));
    }

    @Test
    void keineGuteVorlageAufKrakenOderFremdem() {
        List<String> fehler = new ArrayList<>();
        for (var b : bilder.entrySet()) {
            String art = art(b.getKey());
            if (!art.equals("kraken") && !art.equals("fremd")) continue;
            for (var t : gute.entrySet()) {
                double m = mittig(b.getValue(), t.getValue()), u = ueberall(b.getValue(), t.getValue());
                if (m >= schwelle(t.getKey()) - ABSTAND || u >= KISTEN_SCHWELLE - ABSTAND) {
                    fehler.add(String.format("%s auf %s: mittig %.3f (Schwelle %.2f), ueberall %.3f",
                            t.getKey(), b.getKey(), m, schwelle(t.getKey()), u));
                }
            }
        }
        assertTrue(fehler.isEmpty(), "Gute Vorlage zu nah an Kraken/Fremdem:\n" + String.join("\n", fehler));
    }

    @Test
    void andereFamilieBleibtUnterDerSchwelle() {
        // Verwechslung zwischen gewollten Belohnungen ist harmlos, soll aber unter der Schwelle bleiben.
        List<String> fehler = new ArrayList<>();
        for (var b : bilder.entrySet()) {
            String art = art(b.getKey());
            if (art.equals("kraken") || art.equals("fremd")) continue;
            for (var t : gute.entrySet()) {
                if (familie(t.getKey()).equals(art)) continue;
                double m = mittig(b.getValue(), t.getValue()), u = ueberall(b.getValue(), t.getValue());
                if (m >= schwelle(t.getKey()) || u >= KISTEN_SCHWELLE) {
                    fehler.add(String.format("%s auf %s: mittig %.3f (Schwelle %.2f), ueberall %.3f",
                            t.getKey(), b.getKey(), m, schwelle(t.getKey()), u));
                }
            }
        }
        assertTrue(fehler.isEmpty(), "Fremde Familie ueber der Schwelle:\n" + String.join("\n", fehler));
    }

    @Test
    void jedeGewollteBelohnungWirdErkannt() {
        // Darunter die Felder, die mit den alten Schwellen liegen blieben (Mana 0,617/0,687, Eisen 0,656, Mithril
        // 0,647, gruene Essenz 0,681/0,695).
        List<String> fehler = new ArrayList<>();
        for (var b : bilder.entrySet()) {
            String art = art(b.getKey());
            if (art.equals("kraken") || art.equals("fremd")) continue;
            String bester = null;
            double ueber = -9;   // bester Wert minus Schwelle
            for (var t : gute.entrySet()) {
                if (!familie(t.getKey()).equals(art)) continue;
                double d = mittig(b.getValue(), t.getValue()) - schwelle(t.getKey());
                if (d > ueber) {
                    ueber = d;
                    bester = t.getKey();
                }
            }
            if (ueber < 0) {
                fehler.add(String.format("%s: beste eigene Vorlage %s %.3f unter der Schwelle", b.getKey(), bester, ueber));
            }
        }
        assertTrue(fehler.isEmpty(), "Gewollte Belohnung nicht erkannt:\n" + String.join("\n", fehler));
    }

    @Test
    void manaUndEisenBliebenMitDerAltenSchwelleLiegen() {
        // Belegt den Nutzen der eigenen Schwellen: alt verpasst, neu erkannt.
        for (String[] f : new String[][]{{"elixier_0617", "ev_kiste_elixier"}, {"elixier_0687", "ev_kiste_elixier"},
                {"metall_0656", "ev_kiste_metall"}, {"essenz_gruen_0681", "ev_kiste_essenz_gruen_feld"}}) {
            double m = mittig(bilder.get(f[0]), gute.get(f[1]));
            assertTrue(m < standard(f[1]) && m >= schwelle(f[1]),
                    String.format("%s: %s %.3f, alt %.2f, neu %.2f", f[0], f[1], m, standard(f[1]), schwelle(f[1])));
        }
    }

    @Test
    void krakenWirdErkanntUndNurKraken() {
        List<String> fehler = new ArrayList<>();
        for (var b : bilder.entrySet()) {
            double best = -1;
            for (Mat k : kraken.values()) best = Math.max(best, ueberall(b.getValue(), k));
            boolean istKraken = art(b.getKey()).equals("kraken");
            if (istKraken && best < EventVorlagenSchwellen.KRAKEN_SCHWELLE) {
                fehler.add(String.format("Kraken %s nicht erkannt: %.3f", b.getKey(), best));
            }
            if (!istKraken && best >= EventVorlagenSchwellen.KRAKEN_SCHWELLE - ABSTAND) {
                fehler.add(String.format("Kraken-Vorlage zu hoch auf %s: %.3f", b.getKey(), best));
            }
        }
        assertTrue(fehler.isEmpty(), String.join("\n", fehler));
    }

    @Test
    void schatzGoldVon2228WarWirklichGold() {
        // 22:28 "Collect chest (ev_kiste_schatz_gold_feld) 0,761" -- im Feld lag wirklich der goldene Schatz (den der
        // blaue Hammer kurz vorher schon abgeholt hatte), keine Verwechslung mit dem gruenen.
        Mat b = bilder.get("schatz_gold_2228");
        double gold = mittig(b, gute.get("ev_kiste_schatz_gold_feld"));
        double gruen = mittig(b, gute.get("ev_kiste_schatz_gruen_feld"));
        assertTrue(gold >= 0.74 && gold < 0.78, "gold " + gold);
        assertTrue(gruen < 0.60, "gruen " + gruen);
    }

    @Test
    void schwellen() {
        assertEquals(0.61, EventVorlagenSchwellen.schwelle("ev_kiste_elixier", 0.73), 1e-9);
        assertEquals(0.70, EventVorlagenSchwellen.schwelle("ev_kiste_schatz_gold_feld", 0.70), 1e-9);
        assertEquals(0.73, EventVorlagenSchwellen.schwelle("ev_kiste_unbekannt", 0.73), 1e-9);
        assertTrue(EventVorlagenSchwellen.krakenGewinnt(0.55, 0.80));
        assertTrue(EventVorlagenSchwellen.krakenGewinnt(0.70, 0.66));
        assertFalse(EventVorlagenSchwellen.krakenGewinnt(0.95, 0.66));
        assertFalse(EventVorlagenSchwellen.krakenGewinnt(0.70, 0.60));
    }

    @Test
    void krakenAnStelleMitEchtemMatcher() {
        Matcher m = new Matcher(VORLAGEN);
        m.loadAll();
        for (String k : EventVorlagenSchwellen.KRAKEN) assertTrue(m.has(k), k + " geladen");
        // Kraken-Feld: auch ein (gedachter) schwacher Treffer einer guten Vorlage dort bleibt liegen.
        Mat kr = bilder.get("kraken_lila");
        Matcher.Hit gedacht = new Matcher.Hit("ev_kiste_elixier", 72 - 36, 62 - 30, 73, 61, 0.62);
        assertTrue(EventVorlagenSchwellen.krakenAnStelle(kr, m, gedacht));
        // Echte gute Treffer: nie Kraken.
        for (String[] f : new String[][]{{"elixier_0687", "ev_kiste_elixier"}, {"metall_0656", "ev_kiste_metall"},
                {"schatz_gruen", "ev_kiste_schatz_gruen_feld"}, {"extra_5", "ev_kiste_extra_5"}}) {
            Mat b = bilder.get(f[0]);
            Optional<Matcher.Hit> h = m.findImBereich(b, f[1], new Rect(0, 0, b.cols(), b.rows()), 0.5);
            assertTrue(h.isPresent(), f[1] + " auf " + f[0]);
            assertFalse(EventVorlagenSchwellen.krakenAnStelle(b, m, h.get()), f[0] + " ist keine Kraken-Kiste");
        }
    }
}
