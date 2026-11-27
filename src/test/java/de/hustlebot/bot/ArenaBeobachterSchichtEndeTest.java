package de.hustlebot.bot;

import de.hustlebot.schicht.ArenaStand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaBeobachterSchichtEndeTest {

    private static GameMode anderer() {
        return (GameMode) java.lang.reflect.Proxy.newProxyInstance(GameMode.class.getClassLoader(), new Class<?>[]{GameMode.class}, (p, m, a) -> null);
    }

    @Test
    void andererModusBeendetDieSchicht() {
        GameMode andererModus = anderer();
        assertTrue(ArenaBeobachter.schichtBeenden(BotEngine.State.LAEUFT, andererModus, true));
    }

    @Test
    void arenaModusUndAutorsEigenesSpielenBleiben() {
        assertFalse(ArenaBeobachter.schichtBeenden(BotEngine.State.LAEUFT, null, true));
        assertFalse(ArenaBeobachter.schichtBeenden(BotEngine.State.GESTOPPT, anderer(), true));
        assertFalse(ArenaBeobachter.schichtBeenden(BotEngine.State.LAEUFT, anderer(), false));
    }

    @Test
    void beendeterStandGiltNichtMehrNeuerStandSchon() {
        long vorher = System.currentTimeMillis() - 1000;
        ArenaStand.beenden();
        assertTrue(ArenaStand.beendetNach(vorher));
        assertFalse(ArenaStand.beendetNach(System.currentTimeMillis() + 5000));
    }
}
