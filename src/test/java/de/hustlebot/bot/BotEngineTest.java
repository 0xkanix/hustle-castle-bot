package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reine Rechenregeln des Motors, ohne Spiel und ohne Bild. */
class BotEngineTest {

    /**
     * Arena-Nachspiel (Pruefung 11.10.): Hat die Arena im Nachspiel KEIN Turnier gespielt
     * (Balken falsch gelesen, oder sie kam nicht weiter), sperrt der Motor zehn Minuten lang
     * jedes weitere Nachspiel -- sonst liefe er im Kreis. Nach einem gespielten Turnier keine Sperre.
     */
    @Test
    void nachspielOhneTurnierSperrtZehnMinuten() {
        long jetzt = 1_000_000L;
        assertEquals(jetzt + 10 * 60_000L, BotEngine.nachspielSperreBis(false, jetzt));
    }

    @Test
    void nachspielMitTurnierSperrtNicht() {
        assertEquals(0L, BotEngine.nachspielSperreBis(true, 1_000_000L));
    }
}
