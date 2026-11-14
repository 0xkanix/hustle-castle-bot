package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste A18): "5/5" gilt vor Ablauf der 0,8 s nur, wenn das Bild zwischen den zwei Lesungen gleich hell
 * blieb -- blendet die Liste noch ein, wird wie bisher gewartet.
 */
class LighthouseFruehTest {

    @Test
    void gleicheHelligkeitIstRuhig() {
        assertTrue(LighthouseMode.helligkeitRuhig(100, 100));
        assertTrue(LighthouseMode.helligkeitRuhig(100, 102.9));
        assertTrue(LighthouseMode.helligkeitRuhig(100, 97.1));
    }

    @Test
    void einblendenIstNichtRuhig() {
        assertFalse(LighthouseMode.helligkeitRuhig(100, 103.5));
        assertFalse(LighthouseMode.helligkeitRuhig(100, 96));
        assertFalse(LighthouseMode.helligkeitRuhig(60, 90));
    }

    @Test
    void schwarzesBildIstNieRuhig() {
        assertFalse(LighthouseMode.helligkeitRuhig(0, 0));
        assertFalse(LighthouseMode.helligkeitRuhig(0, 50));
    }
}
