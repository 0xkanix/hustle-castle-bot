package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Bugjagd 29.10.: Gem-Bay-Reisen laden nach dem Belohnungsfenster 15-20 s ohne Klick -- die 20-s-Haengerfrist gab Fehlalarme (26.10., 2 Berichte). */
class GemBayHaengerTest {

    @Test
    void gemBayMeldetErstNachSechzigSekunden() {
        assertEquals(60, new GemBayMode().haengerBerichtNachS());
    }
}
