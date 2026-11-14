package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 24.10. (Ideenliste A29): gleiche Bildpunkte = gleiche Lesung -- die Texterkennung wird nicht noch einmal gefragt. */
class ArenaLeserGemerktTest {

    private static BufferedImage bild(int farbe, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.setRGB(x, y, farbe + x * 7 + y * 13);
        return b;
    }

    @Test
    void gleichesBildWirdNurEinmalGelesen() {
        AtomicInteger aufrufe = new AtomicInteger();
        BufferedImage b = bild(0x102030, 40, 12);
        List<String> a = ArenaLeser.gemerktLesen(b, "", () -> { aufrufe.incrementAndGet(); return List.of("+225"); });
        List<String> c = ArenaLeser.gemerktLesen(bild(0x102030, 40, 12), "", () -> { aufrufe.incrementAndGet(); return List.of("+999"); });
        assertEquals(List.of("+225"), a);
        assertEquals(List.of("+225"), c);
        assertEquals(1, aufrufe.get());
    }

    @Test
    void einAnderesBildOderEineAndereSpracheLiestNeu() {
        AtomicInteger aufrufe = new AtomicInteger();
        ArenaLeser.gemerktLesen(bild(0x405060, 40, 12), "", () -> { aufrufe.incrementAndGet(); return List.of("a"); });
        BufferedImage anders = bild(0x405060, 40, 12);
        anders.setRGB(3, 3, 0xFFFFFF);
        ArenaLeser.gemerktLesen(anders, "", () -> { aufrufe.incrementAndGet(); return List.of("b"); });
        ArenaLeser.gemerktLesen(bild(0x405060, 40, 12), "ru", () -> { aufrufe.incrementAndGet(); return List.of("c"); });
        ArenaLeser.gemerktLesen(bild(0x405060, 41, 12), "", () -> { aufrufe.incrementAndGet(); return List.of("d"); });
        assertEquals(4, aufrufe.get());
    }

    @Test
    void nichtsGelesenWirdNichtGemerkt() {
        AtomicInteger aufrufe = new AtomicInteger();
        BufferedImage b = bild(0x708090, 40, 12);
        assertTrue(ArenaLeser.gemerktLesen(b, "", () -> { aufrufe.incrementAndGet(); return List.of(); }).isEmpty());
        assertTrue(ArenaLeser.gemerktLesen(b, "", () -> { aufrufe.incrementAndGet(); return List.of(" "); }).get(0).isBlank());
        // beim dritten Mal liest die Erkennung etwas -- das gilt dann
        assertEquals(List.of("150"), ArenaLeser.gemerktLesen(b, "", () -> { aufrufe.incrementAndGet(); return List.of("150"); }));
        assertEquals(List.of("150"), ArenaLeser.gemerktLesen(b, "", () -> { aufrufe.incrementAndGet(); return List.of("x"); }));
        assertEquals(3, aufrufe.get());
    }

    @Test
    void ausDemSpeicherKommtEineKopie() {
        BufferedImage b = bild(0x0A0B0C, 40, 12);
        ArenaLeser.gemerktLesen(b, "", () -> List.of("Name", "Clan"));
        List<String> kopie = ArenaLeser.gemerktLesen(b, "", () -> List.of("egal"));
        kopie.add("veraendert");   // darf den Speicher nicht beschaedigen
        assertEquals(List.of("Name", "Clan"), ArenaLeser.gemerktLesen(b, "", () -> List.of("egal")));
    }
}
