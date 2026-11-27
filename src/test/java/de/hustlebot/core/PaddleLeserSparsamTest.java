package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sparsam-Auftrag 20.11. (VM neben Minecraft): Modelle erst bei Bedarf laden, nach Leerlauf freigeben, gleiche Zeilen
 * nicht zweimal rechnen -- und dabei GENAU dasselbe lesen. Echte Arena-Namen aus src/test/resources/arena-namen.
 * Schreibt Privatspeicher und Zeiten als "MESSUNG ..." ins Testprotokoll.
 *
 * Vorher (alter Stand, gleicher Test): verfuegbar() lud alle zehn Modelle, 1629 ms, +357 MB Privatspeicher.
 */
class PaddleLeserSparsamTest {

    /** Privatspeicher des Vorgangs in MB (Windows: Zusagen im Auslagerungsspeicher, enthaelt den Speicher von ONNX). */
    static long privatMb() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getCommittedVirtualMemorySize() / (1024 * 1024);
    }

    static List<BufferedImage> namen() throws Exception {
        Path ordner = Path.of(PaddleLeserSparsamTest.class.getResource("/arena-namen").toURI());
        List<BufferedImage> aus = new ArrayList<>();
        try (Stream<Path> s = Files.list(ordner)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".png")).sorted().toList()) aus.add(ImageIO.read(p.toFile()));
        }
        return aus;
    }

    static List<String> lesenAlle(List<BufferedImage> bilder, String sprache) {
        List<String> aus = new ArrayList<>();
        for (BufferedImage b : bilder) aus.add(String.join("|", PaddleLeser.zeilen(b, sprache)));
        return aus;
    }

    @Test
    void erstBeiBedarfLadenFreigebenUndGleichLesen() throws Exception {
        long vorher = privatMb();
        long t0 = System.nanoTime();
        Assumptions.assumeTrue(PaddleLeser.verfuegbar(), "PaddleOCR fehlt");
        long ladenMs = (System.nanoTime() - t0) / 1_000_000;
        long nachLaden = privatMb();
        // Andere Tests im selben Lauf koennen schon Modelle geladen haben: erst alles freigeben, dann von vorn.
        PaddleLeser.freigebenWennUnbenutzt(Long.MAX_VALUE / 2, PaddleLeser.LEERLAUF_MS);
        assertTrue(PaddleLeser.geladen().isEmpty(), "nach dem Freigeben nichts im Speicher");

        List<BufferedImage> bilder = namen();
        // Eine reine Latein-Lesung laedt nur das lateinische Modell
        PaddleLeser.lesen(bilder.get(0), PaddleLeser.Schrift.LATEIN);
        assertEquals(java.util.EnumSet.of(PaddleLeser.Schrift.LATEIN), PaddleLeser.geladen(), "nur Latein, wenn nur Latein gefragt ist");
        PaddleLeser.bericht();
        long t1 = System.nanoTime();
        List<String> normal = lesenAlle(bilder, "");
        List<String> alsNamen = lesenAlle(bilder, "namen");
        long lesenMs = (System.nanoTime() - t1) / 1_000_000;
        long nachLesen = privatMb();
        var geladenNachLesen = PaddleLeser.geladen();
        String bericht1 = PaddleLeser.bericht();

        // Zweites Mal dieselben Bilder: alles aus dem Gedaechtnis, gleiches Ergebnis
        long t2 = System.nanoTime();
        assertEquals(normal, lesenAlle(bilder, ""));
        assertEquals(alsNamen, lesenAlle(bilder, "namen"));
        long gemerktMs = (System.nanoTime() - t2) / 1_000_000;
        String bericht2 = PaddleLeser.bericht();
        assertTrue(bericht2.contains(" 0 lines computed") || bericht2.contains(" 0 Zeilen gerechnet"), bericht2);

        // Freigeben (Leerlauf simuliert), dann neu laden und wieder genau dasselbe lesen
        assertFalse(PaddleLeser.freigebenWennUnbenutzt(System.currentTimeMillis(), PaddleLeser.LEERLAUF_MS), "nicht zu frueh");
        assertTrue(PaddleLeser.freigebenWennUnbenutzt(System.currentTimeMillis() + PaddleLeser.LEERLAUF_MS + 1, PaddleLeser.LEERLAUF_MS));
        assertTrue(PaddleLeser.geladen().isEmpty());
        System.gc();
        long nachFreigabe = privatMb();
        long t3 = System.nanoTime();
        assertEquals(normal, lesenAlle(bilder, ""), "nach dem Freigeben gleich gelesen");
        assertEquals(alsNamen, lesenAlle(bilder, "namen"), "nach dem Freigeben gleich gelesen (Namen)");
        long neuMs = (System.nanoTime() - t3) / 1_000_000;

        System.out.println("MESSUNG Texterkennung: Privatspeicher vorher " + vorher + " MB, nach verfuegbar() " + nachLaden
                + " MB (" + ladenMs + " ms), nach " + bilder.size() + " Bildern normal+Namen " + nachLesen + " MB ("
                + lesenMs + " ms, Modelle " + geladenNachLesen + "), nach Freigabe " + nachFreigabe + " MB, neu laden+lesen "
                + neuMs + " ms, aus dem Gedaechtnis " + gemerktMs + " ms");
        System.out.println("MESSUNG " + bericht1 + " / " + bericht2);
        // Alter Stand (alle Modelle beim Start) las dieselben Bilder als 605699735/1798719641
        System.out.println("MESSUNG Lesungen: " + normal.hashCode() + "/" + alsNamen.hashCode());
        assertEquals(605699735, normal.hashCode(), "wie vorher gelesen: " + normal);
        assertEquals(1798719641, alsNamen.hashCode(), "wie vorher gelesen (Namen): " + alsNamen);
    }

    @Test
    void ganzesBildWirdNichtZweimalGelesen() throws Exception {
        Assumptions.assumeTrue(PaddleLeser.verfuegbar(), "PaddleOCR fehlt");
        BufferedImage b = namen().get(1);
        Texterkennung.bericht();
        List<String> erst = Texterkennung.lies(b);
        List<String> nochmal = Texterkennung.lies(b);
        assertEquals(erst, nochmal);
        String bericht = Texterkennung.bericht();
        if (erst.stream().anyMatch(z -> !z.isBlank())) {
            assertTrue(bericht.contains("from memory 1x") || bericht.contains("aus dem Gedaechtnis 1x"), bericht);
        }
        nochmal.add("veraendert");
        assertEquals(erst, Texterkennung.lies(b), "das Gedaechtnis gibt eine Kopie heraus");
    }
}
