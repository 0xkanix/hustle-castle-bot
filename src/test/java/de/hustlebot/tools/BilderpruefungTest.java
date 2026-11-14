package de.hustlebot.tools;

import de.hustlebot.tools.Bilderpruefung.Kandidat;
import de.hustlebot.tools.Bilderpruefung.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F23): "Alle meine Bilder pruefen" -- gefunden, fehlt hier, anderer Bildschirm.
 */
class BilderpruefungTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static BufferedImage rauschen(int b, int h, long samen) {
        BufferedImage f = new BufferedImage(b, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(samen);
        for (int y = 0; y < h; y++) for (int x = 0; x < b; x++) f.setRGB(x, y, r.nextInt(0xFFFFFF));
        return f;
    }

    private static Kandidat k(String name, String ort) {
        return new Kandidat(name, ort, Path.of(name + ".png"));
    }

    @Test
    void bewertungUnterscheidetFehltHierVonAnderemBildschirm() {
        List<Kandidat> ks = List.of(k("a", "Squad"), k("b", "Squad"), k("c", "Map"), k("d", "Arena"));
        // a gefunden -> der Foto-Bildschirm ist "Squad": b fehlt hier; c und d gehoeren woanders hin
        var z = Bilderpruefung.bewerte(ks, List.of(0.99, 0.30, 0.20, 0.10), null);
        assertEquals(Status.FEHLT_HIER, z.get(0).status(), "was fehlt, steht oben");
        assertEquals("b", z.get(0).kandidat().name());
        assertEquals(Status.GEFUNDEN, z.get(1).status());
        assertEquals(Status.ANDERER_BILDSCHIRM, z.get(2).status());
        assertEquals(Status.ANDERER_BILDSCHIRM, z.get(3).status());
    }

    @Test
    void erwarteterBildschirmZaehltAuchOhneFund() {
        List<Kandidat> ks = List.of(k("c", "Map"), k("d", "Arena"));
        var z = Bilderpruefung.bewerte(ks, List.of(0.2, 0.1), "Map");
        assertEquals(Status.FEHLT_HIER, z.get(0).status());
        assertEquals("c", z.get(0).kandidat().name());
        assertEquals(Status.ANDERER_BILDSCHIRM, z.get(1).status());
    }

    @Test
    void echteDateienGegenFoto(@TempDir Path d) throws Exception {
        BufferedImage foto = rauschen(300, 200, 11);
        Path da = d.resolve("da.png"), fremd = d.resolve("fremd.png"), kaputt = d.resolve("kaputt.png");
        ImageIO.write(foto.getSubimage(40, 30, 50, 30), "png", da.toFile());
        ImageIO.write(rauschen(50, 30, 99), "png", fremd.toFile());
        java.nio.file.Files.writeString(kaputt, "kein bild");
        List<Kandidat> ks = new ArrayList<>(List.of(new Kandidat("da", "Squad", da), new Kandidat("fremd", "Squad", fremd),
                new Kandidat("kaputt", "Map", kaputt)));
        List<Integer> fortschritt = new ArrayList<>();
        var z = Bilderpruefung.pruefe(foto, ks, null, fortschritt::add);
        assertEquals(List.of(1, 2, 3), fortschritt);
        var nachName = z.stream().collect(java.util.stream.Collectors.toMap(x -> x.kandidat().name(), x -> x));
        assertEquals(Status.GEFUNDEN, nachName.get("da").status());
        assertTrue(nachName.get("da").wert() >= 0.99);
        assertEquals(Status.FEHLT_HIER, nachName.get("fremd").status());
        assertTrue(nachName.get("fremd").wert() < Bilderpruefung.FUND_SCHWELLE);
        assertEquals(-1, nachName.get("kaputt").wert());
        assertEquals(Status.ANDERER_BILDSCHIRM, nachName.get("kaputt").status());
        assertTrue(Bilderpruefung.zusammenfassung(z).contains("fremd"));
    }
}
