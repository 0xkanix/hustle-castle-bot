package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hustlebot.core.Ordner;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Autor 01.11. (Pruefung 3.137, Punkt 8): ein Beweisfoto je Lesung (Set-Popups, Abzeichen, Schirmherren/Stigma-Bonus-Sets, Pals) -- nur mit beweisfotos.txt "an".
 * Ohne Schalter wird nichts gespeichert und kein Ordner angelegt (Autors Vorgabe: nichts waechst).
 */
class ArenaBeweisFotoTest {

    private final Path schalter = Ordner.bei("beweisfotos.txt");
    private final Path ordner = Ordner.bei("logs").resolve("beweis");

    private static void loeschen(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(x);
        }
    }

    @BeforeEach
    @AfterEach
    void aufraeumen() throws IOException {
        Files.deleteIfExists(schalter);
        loeschen(ordner);
    }

    private static BufferedImage bild() {
        return new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void ohneSchalterWirdNichtsGespeichertUndKeinOrdnerAngelegt() {
        assertFalse(ArenaPruefer.beweisFotoSchreiben(bild(), "set", "Denis", 3));
        assertFalse(ArenaPruefer.beweisFotoSchreiben(bild(), "pals", "Denis", 0));
        assertFalse(Files.exists(ordner), "kein Ordner ohne Schalter");
    }

    @Test
    void mitSchalterEinFotoJeLesungInIhremOrdner() throws Exception {
        Files.writeString(schalter, "an");
        assertTrue(ArenaPruefer.beweisFotoSchreiben(bild(), "set", "Denis", 3));
        assertTrue(ArenaPruefer.beweisFotoSchreiben(bild(), "schirm-stigma", "vovan4ik24", 0));
        assertTrue(ArenaPruefer.beweisFotoSchreiben(bild(), "pals", "~ЦИРИ~", 0));
        assertTrue(ArenaPruefer.beweisFotoSchreiben(bild(), "abzeichen", "Xenos", 0));
        for (String art : new String[]{"set", "schirm-stigma", "pals", "abzeichen"}) {
            try (var l = Files.list(ordner.resolve(art))) {
                assertEquals(1, l.count(), "genau ein Foto in " + art);
            }
        }
        assertFalse(ArenaPruefer.beweisFotoSchreiben(null, "set", "Denis", 3), "ohne Bild nichts");
    }

    @Test
    void auchMitSchalterGibtEsEineGrenzeJeArtUndTag() throws Exception {
        Files.writeString(schalter, "an");
        int geschrieben = 0;
        for (int i = 0; i < ArenaPruefer.BEWEIS_MAX_JE_ART + 5; i++) {
            if (ArenaPruefer.beweisFotoSchreiben(bild(), "grenztest", "x", i)) geschrieben++;
        }
        assertEquals(ArenaPruefer.BEWEIS_MAX_JE_ART, geschrieben);
    }

    @Test
    void dateinameMitArtUhrzeitSpielerUndEinheit() {
        assertEquals("set/083015-123-Denis-e3.png", ArenaPruefer.beweisDateiname("set", "Denis", 3, LocalTime.of(8, 30, 15, 123_000_000)));
        assertEquals("pals/083015-000-____-e0.png", ArenaPruefer.beweisDateiname("pals", "~/ ~", 0, LocalTime.of(8, 30, 15)), "Sonderzeichen im Namen werden zu _");
        assertEquals("pals/083015-000--e0.png", ArenaPruefer.beweisDateiname("pals", null, 0, LocalTime.of(8, 30, 15)));
    }
}
