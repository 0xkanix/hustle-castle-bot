package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Handy-Schalter (19.11.): ohne handy.txt bleibt alles am PC. */
class HandyprofilTest {

    @TempDir
    Path tmp;

    @Test
    void ohneDateiKeinHandy() {
        assertNull(Handyprofil.laden(tmp));
    }

    @Test
    void mitDateiHandy() throws Exception {
        Files.writeString(tmp.resolve(Handyprofil.DATEI), "# Handy\nadb=C:\\\\platform-tools\\\\adb.exe\ngeraet=192.168.178.42:37123\n");
        Handyprofil h = Handyprofil.laden(tmp);
        assertEquals("C:\\\\platform-tools\\\\adb.exe", h.adb());
        assertEquals("192.168.178.42:37123", h.geraet());
        assertEquals("templates-handy", h.vorlagen());
    }

    @Test
    void abschaltbarUndOhneAdbNichts() {
        assertNull(Handyprofil.aus(List.of("adb=adb", "an=nein")));
        assertNull(Handyprofil.aus(List.of("geraet=1.2.3.4:5")));
        assertEquals("eigene", Handyprofil.aus(List.of("adb=adb", "vorlagen=eigene")).vorlagen());
    }
}
