package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Handy-Umbau 19.11.: AdbSteuerung mit einem nachgebauten "adb" (Shell-Skript), das jeden Befehl mitschreibt und als
 * Bildschirmfoto ein Handybild liefert. Prueft, dass Tippen/Ziehen als DOWN/MOVE/UP ankommen -- in der richtigen
 * Reihenfolge, an den richtigen Stellen, und dass nie ein Finger aufgelegt bleibt.
 */
class AdbSteuerungTest {

    @TempDir
    Path tmp;

    Path falschesAdb() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "nur mit bash");
        Path log = tmp.resolve("log.txt");
        Path bild = Path.of(AdbSteuerungTest.class.getResource("/handy/handy-klein.png").toURI());
        Path adb = tmp.resolve("adb");
        Files.writeString(adb, String.join("\n",
                "#!/bin/bash",
                "echo \"ARGS $*\" >> " + log,
                "if [[ \"$*\" == *\"exec-out screencap -p\"* ]]; then cat " + bild + "; exit 0; fi",
                "if [[ \"$*\" == *shell ]]; then while IFS= read -r z; do echo \"$z\" >> " + log + "; [ \"$z\" = exit ] && break; done; fi",
                ""));
        adb.toFile().setExecutable(true);
        return adb;
    }

    List<String> zeilen() throws Exception {
        return Files.readAllLines(tmp.resolve("log.txt")).stream().filter(z -> z.startsWith("input")).toList();
    }

    @Test
    void tippenUndZiehenKommenAlsBeruehrungAn() throws Exception {
        Path adb = falschesAdb();
        AdbSteuerung st = new AdbSteuerung(adb.toString(), "192.168.0.5:4444");
        Input in = new Input(st, new Humanizer());
        in.setClientArea(new Rectangle(0, 0, 2712, 1220));
        assertTrue(in.tippSchnell(811, 286, 45, 60));
        assertTrue(in.ziehenSchnell(811, 286, 1219, 694, 30, 30, 70, 30));
        st.close();
        Thread.sleep(300);
        List<String> z = zeilen();
        assertEquals("input motionevent DOWN 811 286", z.get(0));
        assertEquals("input motionevent UP 811 286", z.get(1));
        assertEquals("input motionevent DOWN 811 286", z.get(2));
        assertEquals("input motionevent UP 1219 694", z.get(z.size() - 1));
        // dazwischen nur MOVE, und die letzte Bewegung vor UP ist am Ziel
        for (int i = 3; i < z.size() - 1; i++) assertTrue(z.get(i).startsWith("input motionevent MOVE "), z.get(i));
        assertEquals("input motionevent MOVE 1219 694", z.get(z.size() - 2));
        // DOWN und UP immer paarweise
        long down = z.stream().filter(s -> s.contains(" DOWN ")).count(), up = z.stream().filter(s -> s.contains(" UP ")).count();
        assertEquals(down, up);
        // das Geraet wird immer mitgegeben
        assertTrue(Files.readAllLines(tmp.resolve("log.txt")).stream().filter(s -> s.startsWith("ARGS"))
                .allMatch(s -> s.contains("-s 192.168.0.5:4444")));
    }

    @Test
    void fotoVomHandyUndAusschnitt() throws Exception {
        Path adb = falschesAdb();
        AdbSteuerung st = new AdbSteuerung(adb.toString(), null);
        BufferedImage ganz = st.ganzesFoto();
        assertNotNull(ganz);
        assertEquals(678, ganz.getWidth());
        BufferedImage teil = st.foto(new Rectangle(100, 50, 200, 100));
        assertEquals(200, teil.getWidth());
        assertEquals(100, teil.getHeight());
        assertEquals(ganz.getRGB(150, 80), teil.getRGB(50, 30));
        // ScreenCapture nimmt die Fotos von der Steuerung
        assertEquals(200, new ScreenCapture(st).grab(new Rectangle(100, 50, 200, 100)).getWidth());
        st.close();
    }

    @Test
    void schliessenHebtDenFinger() throws Exception {
        Path adb = falschesAdb();
        AdbSteuerung st = new AdbSteuerung(adb.toString(), null);
        st.bewegen(10, 20);
        st.druecken();
        st.close();
        Thread.sleep(300);
        List<String> z = zeilen();
        assertEquals("input motionevent UP 10 20", z.get(z.size() - 1));
    }
}
