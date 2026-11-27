package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hustlebot.bot.Kaempferbilder.Karte;
import de.hustlebot.bot.Kaempferbilder.Klasse;
import de.hustlebot.bot.Kaempferbilder.Schritt;
import de.hustlebot.core.Matcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Autor 21.11.: Kaempferbilder selbst aufnehmen. Bilder: Truppenbildschirm der Kathedrale (t006, alle vier in der Ablage),
 * Altar of Blood (t008, dieselbe Reihe, Segen-Schrift blendet) und t022 (drei oben in der Truppe, einer in der Ablage) --
 * Fenster 1175 x 809 MIT Rahmen, die Spielflaeche beginnt bei x 8 / y 31 und ist 1159 x 770. Einheiten: Heike, Elke
 * (Bogen), Andrea (Magier), Bernt (Schild). Dazu Autors eigene unit_1..6 / name_1..6 (anderes Konto) als "fremde" Bilder.
 */
class KaempferbilderTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat bild(String pfad) throws Exception {
        try (var in = KaempferbilderTest.class.getResourceAsStream("/kaempferbilder/" + pfad)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            return Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_COLOR);
        }
    }

    /** Spielflaeche aus dem Fensterbild. */
    private static Mat spiel(String datei) throws Exception {
        return new Mat(bild(datei), new Rect(8, 31, 1159, 770)).clone();
    }

    private static List<Klasse> klassen(List<Karte> karten) {
        return karten.stream().map(Karte::klasse).toList();
    }

    @Test
    void truppenbildschirmVierKartenMitNamenUndKlassen() throws Exception {
        List<Karte> karten = Kaempferbilder.kartenFinden(spiel("t006.png"));
        assertEquals(4, karten.size(), "Karten: " + karten);
        int[] mitte = {405, 517, 629, 741};
        for (int i = 0; i < 4; i++) {
            Karte k = karten.get(i);
            assertTrue(Math.abs(k.mitteX() - mitte[i]) <= 2, "Mitte " + k);
            assertEquals(559, k.leisteOben(), 1);
            assertTrue(k.lebt(), "lebt " + k);
            assertEquals(88, k.portraet().width);
            assertEquals(69, k.portraet().height);
            assertNotNull(k.name(), "Name " + i);
            // wie Autors name_N: Schrift mit wenig Rand, 14-20 hoch, nicht breiter als die Karte
            assertTrue(k.name().height >= 14 && k.name().height <= 20, "Namenshoehe " + k.name());
            assertTrue(k.name().width >= 25 && k.name().width <= 90, "Namensbreite " + k.name());
            assertTrue(Math.abs(k.name().x + k.name().width / 2 - k.mitteX()) <= 6, "Name mittig " + k.name());
        }
        assertEquals(List.of(Klasse.BOGEN, Klasse.BOGEN, Klasse.MAGIER, Klasse.TANK), klassen(karten));
    }

    @Test
    void altarMitSegenSchriftGleicheReihe() throws Exception {
        List<Karte> karten = Kaempferbilder.kartenFinden(spiel("t008.png"));
        assertEquals(4, karten.size(), "Karten: " + karten);
        assertEquals(List.of(Klasse.BOGEN, Klasse.BOGEN, Klasse.MAGIER, Klasse.TANK), klassen(karten));
        assertTrue(karten.stream().allMatch(k -> k.name() != null && k.lebt()));
    }

    @Test
    void truppeObenUndAblageUntenWerdenBeideGefunden() throws Exception {
        List<Karte> karten = Kaempferbilder.kartenFinden(spiel("t022.png"));
        assertEquals(4, karten.size(), "Karten: " + karten);
        // erst die Reihe oben (Truppe), dann die Ablage
        assertEquals(322, karten.get(0).leisteOben(), 1);
        assertEquals(559, karten.get(3).leisteOben(), 1);
        assertEquals(List.of(Klasse.BOGEN, Klasse.BOGEN, Klasse.MAGIER, Klasse.TANK), klassen(karten));
    }

    @Test
    void reihenfolgeFernkaempferZuerstVonLinks() throws Exception {
        Mat s = spiel("t006.png");
        List<Karte> reihe = Kaempferbilder.reihenfolge(s, Kaempferbilder.kartenFinden(s));
        // Heike (Bogen), Elke (Bogen), Andrea (Magier) -- von links --, dann Bernt (Schild)
        assertEquals(4, reihe.size());
        assertTrue(Math.abs(reihe.get(0).mitteX() - 405) <= 2);
        assertTrue(Math.abs(reihe.get(1).mitteX() - 517) <= 2);
        assertTrue(Math.abs(reihe.get(2).mitteX() - 629) <= 2);
        assertEquals(Klasse.TANK, reihe.get(3).klasse());
    }

    @Test
    void autorsBilderWerdenRichtigeingeordnet() throws Exception {
        // Autor: unit_1 Magier 1, unit_2 Bogen 2, unit_3 Bogen 1, unit_4 Magier 2, unit_5 Tank, unit_6 Magier 3
        Klasse[] soll = {Klasse.MAGIER, Klasse.BOGEN, Klasse.BOGEN, Klasse.MAGIER, Klasse.TANK, Klasse.MAGIER};
        Map<Klasse, Mat> zeichen = Kaempferbilder.zeichen();
        assertEquals(3, zeichen.size(), "Klassen-Zeichen aus der Jar");
        for (int i = 1; i <= 6; i++) {
            Mat u = bild("autor/unit_" + i + ".png");
            Klasse beste = Klasse.UNBEKANNT;
            double wert = 0;
            for (var e : zeichen.entrySet()) {
                Mat erg = new Mat();
                Imgproc.matchTemplate(u, e.getValue(), erg, Imgproc.TM_CCOEFF_NORMED);
                double v = Core.minMaxLoc(erg).maxVal;
                if (v > wert) {
                    wert = v;
                    beste = e.getKey();
                }
            }
            assertEquals(soll[i - 1], beste, "unit_" + i + " (" + wert + ")");
            assertTrue(wert >= Kaempferbilder.KLASSE_AB, "unit_" + i + " " + wert);
        }
    }

    /** Wie im Bot: (Vorlage, Bereich) -> Wert ab der Schwelle, sonst 0. Farbe (der Bot sucht im Modus grau, siehe unten). */
    private static Kaempferbilder.Wertung wertung(Mat schirm, Map<String, Mat> vorlagen) {
        return (name, bereich) -> {
            Mat v = vorlagen.get(name);
            if (v == null || v.cols() > bereich.width || v.rows() > bereich.height) return 0;
            double w = wert(schirm, v, bereich, false);
            double schwelle = name.startsWith("name_") ? Kaempferbilder.NAME_PASST_AB : Kaempferbilder.UNIT_PASST_AB;
            return w >= schwelle ? w : 0;
        };
    }

    @Test
    void aufnehmenRaeumtAltesWegUndNeueBilderPassen(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates"), eigene = tmp.resolve("My pictures");
        Files.createDirectories(templates.resolve("dungeon"));
        Files.createDirectories(eigene.resolve("Dungeon"));
        Files.write(templates.resolve("dungeon").resolve("unit_1.png"), new byte[]{1});
        Files.write(templates.resolve("dungeon").resolve("unit_dead_bar.png"), new byte[]{2});
        Files.write(templates.resolve("dungeon").resolve("name_sacrifice.png"), new byte[]{3});
        Files.write(eigene.resolve("Dungeon").resolve("name_2.png"), new byte[]{4});
        Path ziel = templates.resolve("dungeon").resolve("einheiten");
        Path weg = tmp.resolve("templates-aussortiert").resolve("kaempferbilder-test");

        Mat s = spiel("t006.png");
        Kaempferbilder.Ergebnis e = Kaempferbilder.aufnehmen(s, Kaempferbilder.kartenFinden(s), ziel, List.of(templates, eigene), weg);

        assertEquals(4, e.reihe().size());
        assertEquals(2, e.weggeraeumt().size(), "weggeraeumt: " + e.weggeraeumt());
        assertTrue(Files.exists(weg.resolve("templates").resolve("dungeon").resolve("unit_1.png")));
        assertTrue(Files.exists(weg.resolve("My pictures").resolve("Dungeon").resolve("name_2.png")));
        assertFalse(Files.exists(templates.resolve("dungeon").resolve("unit_1.png")));
        assertTrue(Files.exists(templates.resolve("dungeon").resolve("unit_dead_bar.png")), "andere Bilder bleiben");
        assertTrue(Files.exists(templates.resolve("dungeon").resolve("name_sacrifice.png")), "andere Bilder bleiben");
        assertNotNull(e.bild());
        assertTrue(e.bild().length > 1000);

        Map<String, Mat> neu = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            Mat u = Imgcodecs.imread(ziel.resolve("unit_" + i + ".png").toString());
            Mat n = Imgcodecs.imread(ziel.resolve("name_" + i + ".png").toString());
            assertEquals(88, u.cols());
            assertEquals(69, u.rows());
            assertTrue(n.cols() >= 25 && n.rows() >= 14, "name_" + i + " " + n.size());
            neu.put("unit_" + i, u);
            neu.put("name_" + i, n);
        }
        assertFalse(Files.exists(ziel.resolve("unit_5.png")));

        // Die neuen Bilder passen auf allen drei Bildschirmen -- jede Karte am Portraet, auch wenn die Truppe oben steht (t022).
        for (String datei : List.of("t006.png", "t008.png", "t022.png")) {
            Mat schirm = spiel(datei);
            List<Karte> karten = Kaempferbilder.kartenFinden(schirm);
            Kaempferbilder.Befund b = Kaempferbilder.abgleichen(schirm, karten, wertung(schirm, neu));
            assertEquals(Kaempferbilder.Abgleich.PASST, b.abgleich(), datei);
            for (int nr : b.portraetNr()) assertTrue(nr > 0, datei + " " + java.util.Arrays.toString(b.portraetNr()));
        }

        // Ein zweites Mal ueberschreibt nichts: die eben geschriebenen wandern selbst nach "aussortiert".
        Path weg2 = tmp.resolve("templates-aussortiert").resolve("kaempferbilder-test2");
        Kaempferbilder.Ergebnis e2 = Kaempferbilder.aufnehmen(s, Kaempferbilder.kartenFinden(s), ziel, List.of(templates, eigene), weg2);
        assertEquals(8, e2.weggeraeumt().size());
        assertTrue(Files.exists(ziel.resolve("unit_1.png")));
    }

    @Test
    void autorsBilderPassenNichtAufDasFremdeKonto() throws Exception {
        Map<String, Mat> autor = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            autor.put("unit_" + i, bild("autor/unit_" + i + ".png"));
            autor.put("name_" + i, bild("autor/name_" + i + ".png"));
        }
        for (String datei : List.of("t006.png", "t008.png", "t022.png")) {
            Mat schirm = spiel(datei);
            List<Karte> karten = Kaempferbilder.kartenFinden(schirm);
            assertEquals(Kaempferbilder.Abgleich.NICHTS,
                    Kaempferbilder.abgleichen(schirm, karten, wertung(schirm, autor)).abgleich(), datei);
        }
    }

    @Test
    void ausloeser() {
        var passt = Kaempferbilder.Abgleich.PASST;
        var nichts = Kaempferbilder.Abgleich.NICHTS;
        var namen = Kaempferbilder.Abgleich.NUR_NAMEN;
        var skins = Kaempferbilder.Abgleich.EINZELNE_SKINS;
        // keine Karten -> spaeter
        assertEquals(Schritt.SPAETER, Kaempferbilder.schritt(0, true, false, nichts, true));
        // eigene Bilder passen -> fertig, auch wenn jemand gefallen ist
        assertEquals(Schritt.PASST, Kaempferbilder.schritt(4, false, true, passt, false));
        // (a) keine Bilder: erst ein zweites Bild, dann aufnehmen
        assertEquals(Schritt.NOCHMAL_SEHEN, Kaempferbilder.schritt(4, true, false, nichts, false));
        assertEquals(Schritt.AUFNEHMEN, Kaempferbilder.schritt(4, true, false, nichts, true));
        // (b) Bilder da, passen nicht (fremdes Konto oder alle Skins neu) -> ersetzen (nach dem zweiten Bild)
        assertEquals(Schritt.NOCHMAL_SEHEN, Kaempferbilder.schritt(4, true, true, nichts, false));
        assertEquals(Schritt.ERSETZEN, Kaempferbilder.schritt(4, true, true, nichts, true));
        assertEquals(Schritt.ERSETZEN, Kaempferbilder.schritt(4, true, true, namen, true));
        // eine Einheit gefallen (ausgegraut) -> nicht jetzt alles aufnehmen
        assertEquals(Schritt.SPAETER, Kaempferbilder.schritt(4, false, false, nichts, true));
        assertEquals(Schritt.SPAETER, Kaempferbilder.schritt(4, false, true, nichts, true));
        assertEquals(Schritt.SPAETER, Kaempferbilder.schritt(4, false, true, namen, true));
        // (c) einzelne neue Skins: nach dem zweiten Bild nur diese -- auch wenn eine andere Einheit gefallen ist
        assertEquals(Schritt.NOCHMAL_SEHEN, Kaempferbilder.schritt(4, true, true, skins, false));
        assertEquals(Schritt.SKINS, Kaempferbilder.schritt(4, true, true, skins, true));
        assertEquals(Schritt.SKINS, Kaempferbilder.schritt(4, false, true, skins, true));
    }

    /** Die selbst aufgenommenen Bilder von t006 (unit_1..4, name_1..4) als Vorlagen. */
    private static Map<String, Mat> eigeneBilder(Path tmp) throws Exception {
        Mat s = spiel("t006.png");
        Path ziel = tmp.resolve("eigen");
        Kaempferbilder.aufnehmen(s, Kaempferbilder.kartenFinden(s), ziel, List.of(), tmp.resolve("weg-eigen"));
        Map<String, Mat> v = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            v.put("unit_" + i, Imgcodecs.imread(ziel.resolve("unit_" + i + ".png").toString()));
            v.put("name_" + i, Imgcodecs.imread(ziel.resolve("name_" + i + ".png").toString()));
        }
        return v;
    }

    @Test
    void einzelnerNeuerSkinNurDiesesPortraet(@TempDir Path tmp) throws Exception {
        Map<String, Mat> v = eigeneBilder(tmp);
        // "Skin gewechselt": unit_2 zeigt ein anderes Gesicht (Autors Magier), der Name passt weiter
        v.put("unit_2", bild("autor/unit_1.png"));
        Mat s = spiel("t006.png");
        List<Karte> karten = Kaempferbilder.kartenFinden(s);
        Kaempferbilder.Befund b = Kaempferbilder.abgleichen(s, karten, wertung(s, v));
        assertEquals(Kaempferbilder.Abgleich.EINZELNE_SKINS, b.abgleich(), java.util.Arrays.toString(b.portraetNr()));
        assertEquals(1, b.skins().size(), "nur eine Karte: " + b.skins());
        int index = b.skins().keySet().iterator().next();
        assertEquals(2, b.skins().get(index));
        assertEquals(0, b.portraetNr()[index]);
        assertEquals(2, b.nameNr()[index]);
    }

    @Test
    void skinNichtErneuernWennDasPortraetAufEinerAnderenKarteSteht(@TempDir Path tmp) throws Exception {
        // Autors Namen "Magier 1/2/3": eine Einheit ohne eigenes Portraet, deren Name name_1 trifft, ist nicht Magier 1,
        // solange Magier 1 mit seinem Portraet daneben steht.
        Map<String, Mat> v = eigeneBilder(tmp);
        Mat s = spiel("t006.png");
        List<Karte> karten = Kaempferbilder.kartenFinden(s);
        // unit_1 = Portraet von Karte 0. Karte 1 hat kein eigenes Portraet mehr, ihr Name gilt aber als name_1:
        v.put("name_1", v.get("name_2"));
        v.remove("name_2");
        v.remove("unit_2");
        Kaempferbilder.Befund b = Kaempferbilder.abgleichen(s, karten, wertung(s, v));
        assertEquals(1, b.portraetNr()[0], "Karte 0 am Portraet erkannt");
        assertEquals(0, b.portraetNr()[1]);
        assertEquals(1, b.nameNr()[1], "Karte 1 traegt name_1");
        assertEquals(Kaempferbilder.Abgleich.PASST, b.abgleich(), "unit_1 bleibt -- skins: " + b.skins());
    }

    @Test
    void alleSkinsNeuNummernBleibenAmNamen(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates");
        Path dungeon = templates.resolve("dungeon");
        Mat s = spiel("t006.png");
        List<Karte> karten = Kaempferbilder.kartenFinden(s);
        Kaempferbilder.aufnehmen(s, karten, tmp.resolve("eigen"), List.of(), tmp.resolve("weg-eigen"));
        // Autors Reihenfolge ist eine andere als die automatische: Nummern 1 und 3 tauschen (Namen),
        // die Portraets sind alle fremd ("alle Skins neu").
        Files.createDirectories(dungeon);
        int[] tausch = {0, 3, 2, 1, 4};
        for (int i = 1; i <= 4; i++) {
            Files.copy(tmp.resolve("eigen").resolve("name_" + tausch[i] + ".png"), dungeon.resolve("name_" + i + ".png"));
            try (var in = KaempferbilderTest.class.getResourceAsStream("/kaempferbilder/autor/unit_" + i + ".png")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
                Files.write(dungeon.resolve("unit_" + i + ".png"), in.readAllBytes());
            }
        }
        Matcher m = new Matcher(templates);
        m.loadAll();
        imModus(m);
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        assertEquals(GameMode.Outcome.WAITING, kb.pruefen(s, m, KaempferbilderTest::sleepLeise));
        imModus(m);
        assertNull(kb.pruefen(s, m, KaempferbilderTest::sleepLeise));
        assertEquals(8, dateien(tmp.resolve("templates-aussortiert")), "alte 8 verschoben");

        // Die neue unit_1 ist das Portraet der Karte, die vorher unit_3 hiess (Name 1 = alter Name 3), usw.
        Path neu = dungeon.resolve("einheiten");
        for (int i = 1; i <= 4; i++) {
            assertEquals(java.util.Arrays.toString(Files.readAllBytes(tmp.resolve("eigen").resolve("unit_" + tausch[i] + ".png"))),
                    java.util.Arrays.toString(Files.readAllBytes(neu.resolve("unit_" + i + ".png"))), "unit_" + i);
        }
        // und beim naechsten Modus-Start passt alles
        kb.neuerStart();
        imModus(m);
        assertNull(kb.pruefen(s, m, ms -> { throw new AssertionError("passt nicht"); }));
    }

    @Test
    void imModusEinzelnerSkinWirdErneuertNameBleibt(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates");
        Path dungeon = templates.resolve("dungeon");
        Mat s = spiel("t008.png");
        Kaempferbilder.aufnehmen(s, Kaempferbilder.kartenFinden(s), dungeon, List.of(), tmp.resolve("weg-eigen"));
        // unit_3 hat ein fremdes Gesicht (neuer Skin), dazu liegt eine zweite alte unit_3 im Lernordner
        Files.delete(dungeon.resolve("unit_3.png"));
        try (var in = KaempferbilderTest.class.getResourceAsStream("/kaempferbilder/autor/unit_5.png")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            byte[] fremd = in.readAllBytes();
            Files.write(dungeon.resolve("unit_3.png"), fremd);
            Files.createDirectories(tmp.resolve("gelernt").resolve("dungeon"));
            Files.write(tmp.resolve("gelernt").resolve("dungeon").resolve("unit_3.png"), fremd);
        }
        byte[] name3 = Files.readAllBytes(dungeon.resolve("name_3.png"));
        Matcher m = new Matcher(templates);
        m.loadAll();
        imModus(m);
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        assertEquals(GameMode.Outcome.WAITING, kb.pruefen(s, m, KaempferbilderTest::sleepLeise));
        imModus(m);
        assertNull(kb.pruefen(s, m, KaempferbilderTest::sleepLeise));

        assertEquals(2, dateien(tmp.resolve("templates-aussortiert")), "beide alten unit_3 verschoben, sonst nichts");
        assertTrue(Files.exists(dungeon.resolve("einheiten").resolve("unit_3.png")), "neues unit_3");
        assertFalse(Files.exists(dungeon.resolve("unit_3.png")));
        assertTrue(java.util.Arrays.equals(name3, Files.readAllBytes(dungeon.resolve("name_3.png"))), "name_3 unberuehrt");
        for (int i : new int[]{1, 2, 4}) assertTrue(Files.exists(dungeon.resolve("unit_" + i + ".png")), "unit_" + i + " bleibt");
        kb.neuerStart();
        imModus(m);
        assertNull(kb.pruefen(s, m, ms -> { throw new AssertionError("passt nicht"); }));
    }

    @Test
    void gefalleneEinheitMitEigenenBildernPasstTrotzdem(@TempDir Path tmp) throws Exception {
        Map<String, Mat> v = eigeneBilder(tmp);
        Mat s = spiel("t006.png");
        // Lebensleiste der dritten Karte rot malen (gefallen); der Rahmen bleibt
        Karte k = Kaempferbilder.kartenFinden(s).get(2);
        Imgproc.rectangle(s, new org.opencv.core.Point(k.mitteX() - 30, k.leisteOben() + 3),
                new org.opencv.core.Point(k.mitteX() + 30, k.leisteUnten() - 3), new org.opencv.core.Scalar(30, 30, 200), -1);
        List<Karte> karten = Kaempferbilder.kartenFinden(s);
        assertEquals(4, karten.size());
        assertFalse(karten.get(2).lebt(), "gefallen erkannt");
        assertEquals(Schritt.PASST, Kaempferbilder.schritt(karten.size(), false, true,
                Kaempferbilder.abgleichen(s, karten, wertung(s, v)).abgleich(), false));
    }

    /** Bester Wert einer Vorlage im Bereich -- grau wie der Bot in Kathedrale/Verlies (BotEngine: setGrauErlaubt). */
    private static double wert(Mat schirm, Mat vorlage, Rect bereich, boolean grau) {
        Mat feld = new Mat(schirm, bereich), v = vorlage, f = feld, erg = new Mat();
        if (grau) {
            v = new Mat();
            f = new Mat();
            Imgproc.cvtColor(vorlage, v, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(feld, f, Imgproc.COLOR_BGR2GRAY);
        }
        Imgproc.matchTemplate(f, v, erg, Imgproc.TM_CCOEFF_NORMED);
        return Core.minMaxLoc(erg).maxVal;
    }

    @Test
    void autorsBilderPassenAuchInGraustufenNicht() throws Exception {
        // Der Bot sucht in Kathedrale und Verlies in Graustufen -- dort liegen fremde Bilder naeher dran als in Farbe.
        Map<String, Mat> autor = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            autor.put("unit_" + i, bild("autor/unit_" + i + ".png"));
            autor.put("name_" + i, bild("autor/name_" + i + ".png"));
        }
        for (String datei : List.of("t006.png", "t008.png", "t022.png")) {
            Mat schirm = spiel(datei);
            List<Karte> karten = Kaempferbilder.kartenFinden(schirm);
            double[] max = new double[2];   // {unit, name} -- in genau den Kaesten, in denen abgleichen() sucht
            Kaempferbilder.Befund b = Kaempferbilder.abgleichen(schirm, karten, (name, bereich) -> {
                Mat v = autor.get(name);
                if (v == null || v.cols() > bereich.width || v.rows() > bereich.height) return 0;
                double w = wert(schirm, v, bereich, true);
                int art = name.startsWith("unit_") ? 0 : 1;
                max[art] = Math.max(max[art], w);
                return w >= (art == 0 ? Kaempferbilder.UNIT_PASST_AB : Kaempferbilder.NAME_PASST_AB) ? w : 0;
            });
            System.out.printf("Kaempferbilder grau %s: unit hoechstens %.3f, name hoechstens %.3f%n", datei, max[0], max[1]);
            assertEquals(Kaempferbilder.Abgleich.NICHTS, b.abgleich(), datei);
            assertTrue(max[0] < Kaempferbilder.UNIT_PASST_AB, datei + " unit " + max[0]);
            assertTrue(max[1] < Kaempferbilder.NAME_PASST_AB, datei + " name " + max[1]);
        }
    }

    /** Ein Vorlagenordner mit Autors Bildern (fremdes Konto fuer t006) wie beim Bot: templates/dungeon/unit_1.png ... */
    private static Matcher matcherMitAutorsBildern(Path templates) throws Exception {
        Files.createDirectories(templates.resolve("dungeon"));
        for (int i = 1; i <= 6; i++) {
            for (String art : List.of("unit_", "name_")) {
                try (var in = KaempferbilderTest.class.getResourceAsStream("/kaempferbilder/autor/" + art + i + ".png")) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
                    Files.write(templates.resolve("dungeon").resolve(art + i + ".png"), in.readAllBytes());
                }
            }
        }
        Matcher m = new Matcher(templates);
        m.loadAll();
        imModus(m);
        return m;
    }

    /** Wie im Bot: Kathedrale/Verlies suchen in Graustufen, und dieser Faden holt die Bilder. */
    private static void imModus(Matcher m) {
        m.setGrauErlaubt(true);
        m.neuesBild();
    }

    private static long dateien(Path ordner) throws Exception {
        if (!Files.isDirectory(ordner)) return 0;
        try (var s = Files.walk(ordner)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void imModusFremdeBilderErsetzenEinmalUndDannPassenSie(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates");
        Matcher m = matcherMitAutorsBildern(templates);
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        Mat s = spiel("t006.png");
        List<Long> geschlafen = new ArrayList<>();
        LongConsumer schlafen = ms -> {
            geschlafen.add(ms);
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // 1. Blick: fremde Bilder, Karten zu sehen -- erst ein zweites Bild abwarten (blendet das Fenster noch ein?)
        assertEquals(GameMode.Outcome.WAITING, kb.pruefen(s, m, schlafen));
        assertEquals(1, geschlafen.size());
        assertEquals(12, dateien(templates.resolve("dungeon")), "noch nichts angefasst");

        // 2. Blick, dieselben Karten: alte weg (verschoben, nicht geloescht), neue aufgenommen und geladen
        imModus(m);
        assertNull(kb.pruefen(s, m, schlafen));
        Path aussortiert = tmp.resolve("templates-aussortiert");
        assertEquals(12, dateien(aussortiert), "Autors 12 Bilder verschoben");
        Path ziel = templates.resolve("dungeon").resolve("einheiten");
        for (int i = 1; i <= 4; i++) {
            assertTrue(Files.exists(ziel.resolve("unit_" + i + ".png")), "unit_" + i);
            assertTrue(Files.exists(ziel.resolve("name_" + i + ".png")), "name_" + i);
            assertTrue(m.has("unit_" + i) && m.has("name_" + i), "geladen " + i);
        }
        assertFalse(m.has("unit_5"), "die alten unit_5/6 sind vergessen");
        assertFalse(Files.exists(templates.resolve("dungeon").resolve("unit_1.png")));

        // Einmal je Modus-Start: kein weiterer Blick, nichts geschlafen
        assertNull(kb.pruefen(s, m, ms -> { throw new AssertionError("nicht mehr warten"); }));
        assertTrue(kb.toString().contains("fertig"));

        // Naechster Modus-Start: die eigenen Bilder passen -- nichts wird angefasst, auch nicht auf den anderen Bildschirmen
        for (String datei : List.of("t006.png", "t008.png", "t022.png")) {
            kb.neuerStart();
            imModus(m);
            Mat schirm = spiel(datei);
            assertNull(kb.pruefen(schirm, m, ms -> { throw new AssertionError("passt nicht: " + datei); }), datei);
            assertTrue(kb.toString().contains("fertig"), datei);
        }
        assertEquals(12, dateien(aussortiert));
        assertEquals(8, dateien(ziel));
    }

    @Test
    void ohneBilderWirdAufgenommenOhneEtwasZuVerschieben(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates");
        Files.createDirectories(templates.resolve("dungeon"));
        org.junit.jupiter.api.Assumptions.assumeTrue(KaempferbilderTest.class.getResource("/kaempferbilder/autor/unit_1.png") != null, "Testbild ist privat und fehlt in der offenen Fassung");
        Files.write(templates.resolve("dungeon").resolve("unit_dead_bar.png"), Files.readAllBytes(
                Path.of(KaempferbilderTest.class.getResource("/kaempferbilder/autor/unit_1.png").toURI())));
        Matcher m = new Matcher(templates);
        m.loadAll();
        imModus(m);
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        Mat s = spiel("t008.png");   // Altar: die Segen-Schrift blendet, die Karten stehen trotzdem still
        assertEquals(GameMode.Outcome.WAITING, kb.pruefen(s, m, ms -> sleepLeise(ms)));
        imModus(m);
        assertNull(kb.pruefen(s, m, ms -> sleepLeise(ms)));
        assertTrue(m.has("unit_1") && m.has("unit_4") && m.has("name_4"));
        assertEquals(0, dateien(tmp.resolve("templates-aussortiert")), "nichts zum Wegraeumen");
        assertTrue(Files.exists(templates.resolve("dungeon").resolve("unit_dead_bar.png")), "Todesleiste bleibt");
    }

    private static void sleepLeise(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void ohneKartenHoechstensZwoelfBlickeDannAufgeben(@TempDir Path tmp) throws Exception {
        Matcher m = new Matcher(tmp.resolve("templates"));
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        Mat leer = new Mat(770, 1159, org.opencv.core.CvType.CV_8UC3, new org.opencv.core.Scalar(30, 40, 50));
        for (int i = 0; i < 12; i++) {
            assertNull(kb.pruefen(leer, m, ms -> { throw new AssertionError("ohne Karten nie warten"); }));
            assertFalse(kb.toString().contains("fertig"), "Blick " + (i + 1));
        }
        assertNull(kb.pruefen(leer, m, ms -> { throw new AssertionError(); }));
        assertTrue(kb.toString().contains("fertig"), "nach 12 Blicken aufgegeben");
        kb.neuerStart();
        assertFalse(kb.toString().contains("fertig"), "naechster Modus-Start darf wieder");
    }

    /**
     * Tempo (Kathedrale soll 65 s je Run schaffen): mit passenden Bildern kostet nur der erste Truppenbildschirm je
     * Modus-Start etwas -- jeder weitere Aufruf gibt sofort auf. Gemessen und ausgegeben, Grenzen grosszuegig.
     */
    @Test
    void tempoNormalerWeg(@TempDir Path tmp) throws Exception {
        Path templates = tmp.resolve("templates");
        Mat s = spiel("t006.png");
        Kaempferbilder.aufnehmen(s, Kaempferbilder.kartenFinden(s), templates.resolve("dungeon").resolve("einheiten"),
                List.of(templates), tmp.resolve("weg"));
        Matcher m = new Matcher(templates);
        m.loadAll();
        Kaempferbilder kb = new Kaempferbilder("Test", tmp);
        long[] erste = new long[7];
        for (int lauf = 0; lauf < erste.length; lauf++) {
            kb.neuerStart();
            imModus(m);
            long los = System.nanoTime();
            assertNull(kb.pruefen(s, m, ms -> { throw new AssertionError("passt nicht"); }));
            erste[lauf] = System.nanoTime() - los;
        }
        long los = System.nanoTime();
        int n = 100_000;
        for (int i = 0; i < n; i++) kb.pruefen(s, m, ms -> { throw new AssertionError(); });
        double jeWeitererNs = (System.nanoTime() - los) / (double) n;
        long[] sortiert = erste.clone();
        java.util.Arrays.sort(sortiert);
        double median = sortiert[sortiert.length / 2] / 1e6;
        long[] karten = new long[7];
        for (int i = 0; i < karten.length; i++) {
            long t = System.nanoTime();
            Kaempferbilder.kartenFinden(s);
            karten[i] = System.nanoTime() - t;
        }
        java.util.Arrays.sort(karten);
        System.out.printf("Kaempferbilder Tempo: erster Blick je Modus-Start Median %.1f ms (davon Karten finden %.1f ms), "
                + "jeder weitere %.0f ns%n", median, karten[karten.length / 2] / 1e6, jeWeitererNs);
        assertTrue(median < 400, "erster Blick " + median + " ms");
        assertTrue(jeWeitererNs < 10_000, "weitere " + jeWeitererNs + " ns");
    }

    @Test
    void lageStehtStill() throws Exception {
        Mat a = spiel("t006.png"), b = spiel("t008.png");
        int[][] la = Kaempferbilder.lage(a, Kaempferbilder.kartenFinden(a));
        assertTrue(Kaempferbilder.gleicheLage(la, Kaempferbilder.lage(a, Kaempferbilder.kartenFinden(a))));
        int[][] verschoben = new int[la.length][];
        for (int i = 0; i < la.length; i++) {
            verschoben[i] = la[i].clone();
            verschoben[i][0] += 10;
        }
        assertFalse(Kaempferbilder.gleicheLage(la, verschoben));
        assertFalse(Kaempferbilder.gleicheLage(la, null));
        assertNotNull(Kaempferbilder.lage(b, Kaempferbilder.kartenFinden(b)));
    }
}
