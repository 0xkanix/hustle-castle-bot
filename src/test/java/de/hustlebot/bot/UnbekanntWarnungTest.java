package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Bugjagd 29.10.: 156 von 166 Warnungen "Nothing recognized for 12 frames" der Kathedrale kamen nach weniger als 8 s
 * (101 nach "Run completed", 35 nach "Troops confirmed") -- normale Uebergaenge. Echte Stillstaende (bag 50 s,
 * Partnersuche 55 s) sollen weiter warnen.
 */
class UnbekanntWarnungTest {

    @Test
    void uebergangNachRunEndeWarntNicht() {
        assertFalse(Stoerfenster.warnungFaellig(12, 3), "Protokoll 03:35:29: 12 Bilder, 3 s nach 'Run completed'");
        assertFalse(Stoerfenster.warnungFaellig(12, 4));
        assertFalse(Stoerfenster.warnungFaellig(12, 7));
    }

    @Test
    void echterStillstandWarntWeiter() {
        assertTrue(Stoerfenster.warnungFaellig(24, 8), "Protokoll 03:09:16: 24 Bilder, 8 s");
        assertTrue(Stoerfenster.warnungFaellig(36, 12));
        assertTrue(Stoerfenster.warnungFaellig(144, 50), "Tasche 28.10. 05:34");
        assertTrue(Stoerfenster.warnungFaellig(12, 55), "Partnersuche 28.10. 20:08");
    }

    @Test
    void nurAlleZwoelfBilder() {
        assertFalse(Stoerfenster.warnungFaellig(13, 30));
        assertFalse(Stoerfenster.warnungFaellig(0, 30));
        assertFalse(Stoerfenster.warnungFaellig(11, 30));
    }
}
