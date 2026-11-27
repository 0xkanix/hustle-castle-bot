package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 15.11. (XPVY/U7BK): Titanen-Turm und Freunden senden muessen erst in die Burg, wenn das Spiel auf einer Verlies-Etage steht. */
class VorBesuchBurgTest {

    @Test
    void turmUndFreundeGehenErstInDieBurg() {
        assertTrue(BotEngine.vorBesuchNoetig(new TitanTurmMode(), false));
        assertTrue(BotEngine.vorBesuchNoetig(new FreundeSendenMode(), false));
        assertTrue(BotEngine.vorBesuchNoetig(new LighthouseMode(), false));
    }

    @Test
    void stigmasNurAusserhalbDesTitanBildschirms() {
        assertTrue(BotEngine.vorBesuchNoetig(new StigmaMode(), false));
        assertFalse(BotEngine.vorBesuchNoetig(new StigmaMode(), true));
    }

    @Test
    void kathedraleUndAndereBleibenWieSie() {
        assertFalse(BotEngine.vorBesuchNoetig(new CathedralMode(), false));
    }

    @Test
    void notausgangZaehltNurBeiBildaenderung() {
        // 15.11. / X3A2: wirkungsloser Klick aufs rote X ist kein Fortschritt
        assertFalse(BotContext.notausgangZaehlt(false));
        assertTrue(BotContext.notausgangZaehlt(true));
    }
}
