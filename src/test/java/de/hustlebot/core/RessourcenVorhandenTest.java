package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste B18): Im Protokoll stand "/kathedrale/punkt_blass.png fehlt im Programm" -- die Wegplanung fuer
 * unbekannte Karten lief seit Wochen ohne ihre Punkt-Vorlagen, und niemand hat es beim Bauen gemerkt. Dieser Test findet das
 * beim Bauen: JEDER Ressourcen-Pfad, der im Code als Text steht (mit fuehrendem Schraegstrich und Bild-/Textendung), muss
 * wirklich im Programm liegen.
 */
class RessourcenVorhandenTest {

    /** "/ordner/datei.png" als Text im Code -- so heissen Ressourcen, die per getResourceAsStream geladen werden. */
    private static final Pattern RESSOURCE = Pattern.compile(
            "\"(/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.(?:png|jpg|txt|properties|ps1|bat|html|json|csv))\"");

    @Test
    void jederRessourcenPfadImCodeLiegtImProgramm() throws IOException {
        Path quellen = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(quellen), "Quellordner nicht gefunden: " + quellen.toAbsolutePath());

        List<String> fehlen = new ArrayList<>();
        int geprueft = 0;
        try (Stream<Path> alle = Files.walk(quellen)) {
            for (Path datei : (Iterable<Path>) alle.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(datei, StandardCharsets.UTF_8);
                Matcher m = RESSOURCE.matcher(text);
                while (m.find()) {
                    geprueft++;
                    String pfad = m.group(1);
                    // Offene Fassung: die PaddleOCR-Modelle (174 MB) liegen nicht im Projekt -- ohne sie liest die
                    // Windows-Texterkennung.
                    if (pfad.startsWith("/paddleocr/")) continue;
                    if (RessourcenVorhandenTest.class.getResource(pfad) == null) {
                        fehlen.add(pfad + "  (in " + datei.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(geprueft > 0, "Es wurde kein einziger Ressourcen-Pfad gefunden -- stimmt die Suche noch?");
        assertTrue(fehlen.isEmpty(), "Diese Ressourcen fehlen im Programm: " + fehlen);
    }
}
