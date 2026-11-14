package de.hustlebot.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 29.10.: die PaddleOCR-Lesung der Listenbilder als zweiter Name (ArenaListenNetz.mitLesungen). Echte Listenbilder aus
 * src/test/resources/arena-namen (das JtNXED-Bild zeigt "JINXED MURENA" in Zierschrift, das Koroleva-Bild "Королева ll").
 */
class ArenaListenLesungTest {

    private static Path bild(String name) throws Exception {
        return Path.of(ArenaListenLesungTest.class.getResource("/arena-namen/" + name).toURI());
    }

    @Test
    void lesungDerListenbilderWirdZweiterName() throws Exception {
        Assumptions.assumeTrue(PaddleLeser.verfuegbar(), "PaddleOCR fehlt");
        List<Path> bilder = List.of(bild("ar_block_JtNXED.png"), bild("ar_block_Xenos.png"), bild("ar_machbar_Koroleva.png"),
                bild("ar_sicher_Hobbit.png"));
        Map<String, List<String>> ausDateien = ArenaListenNetz.listenNamen(List.of("ar_block_JtNXED", "ar_block_Xenos",
                "ar_machbar_Koroleva", "ar_sicher_Hobbit"));
        Map<String, List<String>> mit = ArenaListenNetz.mitLesungen(ausDateien, bilder);
        System.out.println("Lesungen: " + mit);

        // Der Dateiname "JtNXED" ist ein alter Lesefehler -- ueber ihn allein faende der Bot "JENXED mUREN" nicht.
        assertTrue(ArenaListenNetz.treffer(ausDateien, "JENXED mUREN").isEmpty(), "ohne Lesung kein Treffer");
        // Mit der Lesung des Bildes schon (gleicher Leser, gleicher Fehler) -- und zwar auf der NIEMALS-Liste.
        String jinxed = ArenaListenNetz.lesung(bild("ar_block_JtNXED.png"));
        var t = ArenaListenNetz.treffer(mit, jinxed);
        assertFalse(t.isEmpty(), "die eigene Lesung muss passen: " + jinxed);
        assertEquals(ArenaListenNetz.NIEMALS, t.get(0).liste());
        // Russischer Name: Lesung kyrillisch (neue Schriftwahl), Treffer auf machbar
        String koroleva = ArenaListenNetz.lesung(bild("ar_machbar_Koroleva.png"));
        assertTrue(koroleva.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC),
                "Королева kyrillisch gelesen: " + koroleva);
        assertEquals(ArenaListenNetz.MACHBAR, ArenaListenNetz.treffer(mit, koroleva).get(0).liste());
        // Das Schild in der Pyramide liest derselbe Leser: Zierschrift mit einem anderen Buchstaben ("JENXED mUREN",
        // Messung 29.10. am textKasten) passt ueber den erlaubten Lesefehler trotzdem.
        assertEquals(ArenaListenNetz.NIEMALS, ArenaListenNetz.treffer(mit, "JENXED mUREN").get(0).liste());
        // Fremde Namen bleiben fremd
        assertTrue(ArenaListenNetz.treffer(mit, "Bandolens").isEmpty());
        assertTrue(ArenaListenNetz.treffer(mit, "Hobbit").stream().noneMatch(x -> x.liste().equals(ArenaListenNetz.NIEMALS)));
    }
}
