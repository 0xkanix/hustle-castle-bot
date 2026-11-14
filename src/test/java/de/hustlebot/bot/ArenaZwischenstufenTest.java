package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hustlebot.bot.ArenaListenRegel.Entscheidung;
import de.hustlebot.bot.ArenaListenRegel.Kampf;
import de.hustlebot.bot.ArenaListenRegel.Liste;
import de.hustlebot.bot.ArenaListenRegel.Stand;
import org.junit.jupiter.api.Test;

/** Autor 31.10.: erst "fast sicher", beim naechsten Sieg "sicher"; erst "Pause", bei der naechsten Niederlage "blockiert". */
class ArenaZwischenstufenTest {

    private static final Stand FAST = new Stand(false, false, false, false, true, false);
    private static final Stand PAUSE = new Stand(false, false, false, false, false, true);

    private static Kampf sieg(long dauer, int siegeNotiz) {
        return new Kampf(true, dauer, false, siegeNotiz, siegeNotiz, 0, siegeNotiz);
    }

    private static Kampf niederlage(long dauer) {
        return new Kampf(false, dauer, false, 0, 1, 1, 0);
    }

    private static Liste liste(Kampf k, Stand s) {
        return ArenaListenRegel.mitZwischenstufen(k, s, ArenaListenRegel.entscheide(k, s)).liste();
    }

    @Test
    void derDritteSiegFuehrtErstNachFastSicher() {
        assertEquals(Liste.SICHER, ArenaListenRegel.entscheide(sieg(30, 3), Stand.LEER).liste(), "die alte Regel");
        assertEquals(Liste.FAST_SICHER, liste(sieg(30, 3), Stand.LEER));
    }

    @Test
    void einSchnellerSiegFuehrtErstNachFastSicher() {
        assertEquals(Liste.FAST_SICHER, liste(sieg(5, 1), Stand.LEER));
    }

    @Test
    void derNaechsteSiegFuehrtNachSicher() {
        assertEquals(Liste.SICHER, liste(sieg(30, 1), FAST));
    }

    @Test
    void einMachbarerSpringtNichtDirektNachSicher() {
        assertEquals(Liste.FAST_SICHER, liste(sieg(30, 4), new Stand(false, false, false, true)));
    }

    @Test
    void niederlageBeiFastSicherFuehrtNachMachbar() {
        assertEquals(Liste.MACHBAR, liste(niederlage(30), FAST));
        assertEquals(Liste.MACHBAR, liste(niederlage(5), FAST));
    }

    @Test
    void eineKlareNiederlageFuehrtErstInDiePause() {
        assertEquals(Liste.NIEMALS, ArenaListenRegel.entscheide(niederlage(5), Stand.LEER).liste(), "die alte Regel");
        assertEquals(Liste.PAUSE, liste(niederlage(5), Stand.LEER));
    }

    @Test
    void dreiNiederlagenOhneSiegFuehrenErstInDiePause() {
        Kampf k = new Kampf(false, 30, false, 0, 3, 3, 0);
        Entscheidung e = ArenaListenRegel.mitZwischenstufen(k, Stand.LEER, ArenaListenRegel.entscheide(k, Stand.LEER));
        assertEquals(Liste.PAUSE, e.liste());
    }

    @Test
    void dieNaechsteNiederlageAusDerPauseBlockiert() {
        assertEquals(Liste.NIEMALS, liste(niederlage(30), PAUSE));
    }

    @Test
    void einSiegAusDerPauseFuehrtNachMeiden() {
        assertEquals(Liste.MEIDEN, liste(sieg(30, 1), PAUSE));
    }

    @Test
    void blockiertBleibtBlockiert() {
        assertEquals(Liste.KEINE, liste(sieg(5, 3), new Stand(true, false, false, false)));
    }

    @Test
    void sicherVerlorenBleibtBeiDerAbstufung() {
        Stand s = new Stand(false, false, true, false);
        Kampf k = niederlage(30);
        Entscheidung e = ArenaListenRegel.mitZwischenstufen(k, s, ArenaListenRegel.entscheide(k, s));
        assertEquals(Liste.KEINE, e.liste());
        assertEquals(true, e.abstufen());
    }

    @Test
    void dieNormalenStufenAendernSichNicht() {
        assertEquals(Liste.MACHBAR, liste(sieg(10, 1), Stand.LEER));
        assertEquals(Liste.MEIDEN, liste(niederlage(10), Stand.LEER));
    }
}
