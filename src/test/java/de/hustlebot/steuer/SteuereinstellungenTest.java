package de.hustlebot.steuer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SteuereinstellungenTest {

    @TempDir
    Path tmp;

    @Test
    void vorgabenOhneDatei() {
        Steuereinstellungen e = new Steuereinstellungen(tmp.resolve("s.properties"));
        assertFalse(e.an(Steuereinstellungen.MINILEISTE, false));
        assertTrue(e.an(Steuereinstellungen.WINDOWS_MELDUNGEN, true));
        assertEquals("hell", e.text(Steuereinstellungen.AUSSEHEN, "hell"));
        assertEquals(0.6, e.kommazahl("probe.zahl", 0.6));
        assertEquals(7, e.zahl("x", 7));
    }

    @Test
    void setzenUndWiederLesen() {
        Path d = tmp.resolve("s.properties");
        Steuereinstellungen e = new Steuereinstellungen(d);
        e.setzen(Steuereinstellungen.MINILEISTE, true);
        e.setzen(Steuereinstellungen.AUSSEHEN, "dunkel");
        e.setzen("probe.zahl", "0,75");
        e.setzen(Steuereinstellungen.MINILEISTE_X, 120);

        Steuereinstellungen neu = new Steuereinstellungen(d);
        assertTrue(neu.an(Steuereinstellungen.MINILEISTE, false));
        assertEquals("dunkel", neu.text(Steuereinstellungen.AUSSEHEN, "hell"));
        assertEquals(0.75, neu.kommazahl("probe.zahl", 0.6));
        assertEquals(120, neu.zahl(Steuereinstellungen.MINILEISTE_X, 0));
        neu.setzen(Steuereinstellungen.MINILEISTE, false);
        assertFalse(new Steuereinstellungen(d).an(Steuereinstellungen.MINILEISTE, true));
    }
}
