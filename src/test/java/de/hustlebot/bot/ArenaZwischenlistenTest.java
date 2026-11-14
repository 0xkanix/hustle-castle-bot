package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.hustlebot.core.ArenaListenNetz;
import de.hustlebot.core.ArenaVorlagenDoppelt;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Autor 31.10.: die Listen "fast sicher" (zwischen machbar und sicher) und "Pause" (zwischen meiden und niemals). */
class ArenaZwischenlistenTest {

    private static final long TAG = 86_400_000L;

    @Test
    void pauseEndetNachVierzehnTagen() {
        long jetzt = 100 * TAG;
        assertFalse(ArenaMode.pauseVorbei(jetzt - TAG, jetzt));
        assertFalse(ArenaMode.pauseVorbei(jetzt - ArenaMode.PAUSE_TAGE * TAG, jetzt));
        assertTrue(ArenaMode.pauseVorbei(jetzt - (ArenaMode.PAUSE_TAGE + 1) * TAG, jetzt));
    }

    @Test
    void fastSicherLiegtZwischenMachbarUndSicher() {
        assertTrue(ArenaMode.FAST_PUNKTE > 15 && ArenaMode.FAST_PUNKTE < 40, "Punkte");
        assertTrue(ArenaMode.FAST_MINDESTENS > 0.80 && ArenaMode.FAST_MINDESTENS < 0.90, "Mindest-Chance");
    }

    @Test
    void dieStrengereListeGewinnt() {
        assertEquals(List.of(ArenaListenNetz.NIEMALS, ArenaListenNetz.PAUSE, ArenaListenNetz.MEIDEN, ArenaListenNetz.MACHBAR,
                ArenaListenNetz.FAST, ArenaListenNetz.SICHER), ArenaListenNetz.STRENG);
    }

    @Test
    void dieNeuenBilderZaehlenAlsListen() {
        assertTrue(List.of(ArenaVorlagenDoppelt.LISTEN).contains("ar_fastsicher_"));
        assertTrue(List.of(ArenaVorlagenDoppelt.LISTEN).contains("ar_pause_"));
    }

    @Test
    void fastSicherZaehltAmNamenNurBeiGleichemNamen() {
        Map<String, List<String>> listen = Map.of(ArenaListenNetz.FAST, List.of("Hobbit"));
        assertTrue(ArenaListenNetz.treffer(listen, "HOBBIM").isEmpty(), "ein Zeichen anders");
        assertEquals(1, ArenaListenNetz.treffer(listen, "Hobbit").size());
    }

    @Test
    void pauseZaehltAmNamenAuchBeiEinemLesefehler() {
        // Wie "niemals" und "meiden": im Zweifel lieber nicht angreifen.
        Map<String, List<String>> listen = Map.of(ArenaListenNetz.PAUSE, List.of("Gandalf"));
        assertEquals(1, ArenaListenNetz.treffer(listen, "Gandalt").size());
    }
}
