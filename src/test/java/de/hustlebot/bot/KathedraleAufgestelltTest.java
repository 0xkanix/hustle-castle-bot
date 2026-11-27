package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Robin 21.11. (X3NQ): nach "Deploy the best" gilt die Truppe als aufgestellt -> Kampf starten statt endlos neu aufstellen. */
class KathedraleAufgestelltTest {

    @Test
    void geradeAufgestelltGiltAlsStehend() {
        long t = 1_000_000L;
        assertTrue(CathedralMode.giltAlsAufgestellt(t + 500, t));
        assertTrue(CathedralMode.giltAlsAufgestellt(t + 19_999, t));
    }

    @Test
    void nieOderLaengerHerNicht() {
        long t = 1_000_000L;
        assertFalse(CathedralMode.giltAlsAufgestellt(t, 0), "nie aufgestellt");
        assertFalse(CathedralMode.giltAlsAufgestellt(t + 20_000, t), "zu lange her");
    }
}
