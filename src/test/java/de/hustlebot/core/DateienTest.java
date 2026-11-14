package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bugjagd 29.10.: event-lupen-alle.txt (mit PowerShell geschrieben, UTF-8 mit Kennung) hiess im Protokoll
 * 'neither on nor off' -- die Kennung machte aus "# Kommentar" eine ungueltige Zeile. Jetzt liest EINE Stelle alle
 * Schreibweisen, die Windows erzeugt.
 */
class DateienTest {

    private static byte[] mit(byte[] vorn, byte[] rest) {
        byte[] r = new byte[vorn.length + rest.length];
        System.arraycopy(vorn, 0, r, 0, vorn.length);
        System.arraycopy(rest, 0, r, vorn.length, rest.length);
        return r;
    }

    @Test
    void utf8MitKennungOhneKennungGelesen(@TempDir Path d) throws Exception {
        Path f = d.resolve("a.txt");
        Files.write(f, mit(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "# Autor 28.10.: je Stage alle Lupen\r\nan\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("# Autor 28.10.: je Stage alle Lupen", "an"), Dateien.zeilen(f));
        assertEquals("# Autor 28.10.: je Stage alle Lupen\r\nan\r\n", Dateien.text(f));
    }

    @Test
    void echteSchalterdateiMitKennungGiltAn(@TempDir Path d) throws Exception {
        // genau die Datei aus dem Protokoll: Kommentar + "an", mit Kennung -> vorher "weder an noch aus"
        Path f = d.resolve("event-lupen-alle.txt");
        Files.write(f, mit(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "# Autor 28.10.: je Stage alle Lupen, bis Haemmer und Super-Haemmer leer sind\nan\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(new Schalterdatei(f, "Test", false).lesen());
    }

    @Test
    void utf16VonPowerShellAusgabeUmleitung(@TempDir Path d) throws Exception {
        Path f = d.resolve("b.txt");
        Files.write(f, mit(new byte[]{(byte) 0xFF, (byte) 0xFE}, "aus\r\nZeile Ü\r\n".getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(List.of("aus", "Zeile Ü"), Dateien.zeilen(f));
    }

    @Test
    void ansiMitUmlautenStattMalformedInputException(@TempDir Path d) throws Exception {
        Path f = d.resolve("c.txt");
        Files.write(f, "Müller=5\nGrüße\n".getBytes(java.nio.charset.Charset.forName("windows-1252")));
        assertThrows(java.nio.charset.MalformedInputException.class, () -> Files.readAllLines(f, StandardCharsets.UTF_8),
                "so kam es bisher an: die Datei wurde gar nicht gelesen");
        assertEquals(List.of("Müller=5", "Grüße"), Dateien.zeilen(f));
    }

    @Test
    void normalesUtf8BleibtUnveraendert(@TempDir Path d) throws Exception {
        Path f = d.resolve("d.txt");
        Files.writeString(f, "Егорыч1\nLa Peña\n", StandardCharsets.UTF_8);
        assertEquals(List.of("Егорыч1", "La Peña"), Dateien.zeilen(f));
        Files.writeString(f, "", StandardCharsets.UTF_8);
        assertEquals(List.of(), Dateien.zeilen(f));
    }

    @Test
    void listeIstVeraenderlichWieReadAllLines(@TempDir Path d) throws Exception {
        Path f = d.resolve("e.txt");
        Files.writeString(f, "b\na\n", StandardCharsets.UTF_8);
        List<String> z = Dateien.zeilen(f);
        z.add("c");
        java.util.Collections.sort(z);
        assertEquals(List.of("a", "b", "c"), z, "Aufrufer duerfen die Liste aendern (add/sort/remove)");
    }
}
