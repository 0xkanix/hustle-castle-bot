package de.hustlebot.tools;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F14): jedes Beispielbild zeigt den gruenen Rahmen und sieht anders aus als die anderen.
 */
class BeispielbildTest {

    private static BufferedImage zeichnen(Beispielbild.Motiv m) {
        BufferedImage b = new BufferedImage(Beispielbild.B, Beispielbild.H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        try {
            Beispielbild.zeichne(g, m);
        } finally {
            g.dispose();
        }
        return b;
    }

    @Test
    void jedesMotivHatEinenGruenenRahmenUndSiehtAndersAus() {
        Set<Integer> abdruecke = new HashSet<>();
        for (Beispielbild.Motiv m : Beispielbild.Motiv.values()) {
            BufferedImage b = zeichnen(m);
            int rahmen = 0;
            long summe = 0;
            for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getRGB(x, y) & 0xFFFFFF;
                if (p == (Beispielbild.RAHMEN.getRGB() & 0xFFFFFF)) rahmen++;
                summe = summe * 31 + p;
            }
            assertTrue(rahmen > 80, m + ": der gruene Rahmen fehlt (" + rahmen + " Punkte)");
            assertTrue(abdruecke.add(Long.hashCode(summe)), m + " sieht aus wie ein anderes Motiv");
        }
    }

    @Test
    void motivWechselnUndWiederholen() {
        Beispielbild b = new Beispielbild();
        assertEquals(Beispielbild.Motiv.GESICHT, b.motiv());
        b.setMotiv(Beispielbild.Motiv.KNOPF);
        assertEquals(Beispielbild.Motiv.KNOPF, b.motiv());
        b.setMotiv(null);
        assertEquals(Beispielbild.Motiv.KNOPF, b.motiv());
    }
}
