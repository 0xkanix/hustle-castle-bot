package de.hustlebot.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 22.10.: Notizblock aufgeteilt in "gescannt" und "gescannt + angegriffen". */
class NotizblockListenTest {

    @Test
    void gescanntNurMitPruefdatum(@TempDir Path ordner) throws Exception {
        Path d = ordner.resolve("arena-daten.txt");
        Files.writeString(d, "# Kopf\n"
                + "Королева\tcxag\tSSN\t29122289\t3\t2026-10-22 14:13\t36\tArmor Breaker 6/6\t2026-10-21 15:46\t21\t5\t0\t137\t\t2/23\t3/0/0\t1\n"
                + "Ungeprueft\t\tS\t20000000\t0\t2026-10-22 14:13\t-1\t\t\t\t-1\t-1\n");
        List<Object[]> g = NotizblockListen.gescannt(d);
        assertEquals(1, g.size());
        Object[] r = g.get(0);
        assertEquals("Королева", r[0]);
        assertEquals("29,1", r[2]);
        assertEquals(36, r[3]);
        assertEquals(2, r[4]);
        assertEquals(1, r[5]);
        assertEquals(5, r[6]);
    }

    @Test
    void angegriffenNurGescannteNeuesteOben(@TempDir Path ordner) throws Exception {
        Path k = ordner.resolve("arena-lernen.txt");
        Files.writeString(k, "# Kopf\n"
                + "2026-10-16 00:14:12\tFABIcho\t\t23922692\t42555869\t78\t\t\t95\tnein\tS\t8\t150\t1\n"
                + "2026-10-17 10:00:00\tOhnePunkte\t\t23922692\t42555869\t\t\t\t60\tnein\tN\t12\t0\t2\n"
                + "2026-10-18 09:05:00\tIDKFA\tclan\t40000000\t42555869\t91\t\t\t83\tnein\tN\t20\t0\t5\n");
        List<Object[]> a = NotizblockListen.angegriffen(k);
        assertEquals(2, a.size());
        assertEquals("18.10. 09:05", a.get(0)[0]);
        assertEquals("IDKFA", a.get(0)[1]);
        assertEquals(91, a.get(0)[4]);
        assertEquals(83, a.get(0)[5]);
        assertEquals(10, a.get(0).length);
        assertEquals(5, a.get(0)[9]);
    }
}
