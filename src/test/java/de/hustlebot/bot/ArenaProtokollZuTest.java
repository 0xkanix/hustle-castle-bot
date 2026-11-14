package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static de.hustlebot.bot.ArenaMode.ProtokollZu.Was.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Kampfprotokoll zuklappen: nie mehr 102 Klicks in 32 s, bis die Runde um ist (Nacht 30.10., 04:52). */
class ArenaProtokollZuTest {

    private static final long GEDULD = 1500;

    @Test
    void nachDemKlickWirdGewartet() {
        var z = new ArenaMode.ProtokollZu();
        assertEquals(KLICKEN, z.naechstes(3, 10_000, GEDULD));
        z.geklickt(10_000);
        assertEquals(WARTEN, z.naechstes(3, 10_280, GEDULD), "das naechste Bild nach 0,28 s klickt nicht wieder");
        assertEquals(KLICKEN, z.naechstes(3, 11_600, GEDULD));
    }

    @Test
    void nachDreiKlicksIstInDieserRundeSchluss() {
        var z = new ArenaMode.ProtokollZu();
        long t = 10_000;
        for (int i = 0; i < 3; i++) {
            assertEquals(KLICKEN, z.naechstes(3, t, GEDULD));
            z.geklickt(t);
            t += 2000;
        }
        assertEquals(AUFGEBEN, z.naechstes(3, t, GEDULD));
        assertEquals(AUFGEBEN, z.naechstes(3, t + 30_000, GEDULD), "bleibt so bis zur naechsten Runde");
    }

    @Test
    void top1RegelNimmtNurSichere225er() {
        assertEquals(true, ArenaMode.lieber225er(0.99, 0.95), "04:43: FLIMSY 95 % statt Dezorth 99 %");
        assertEquals(true, ArenaMode.lieber225er(0.98, 0.90));
        assertEquals(false, ArenaMode.lieber225er(0.99, 0.90), "mehr als 8 Punkte unter dem 150er");
        assertEquals(false, ArenaMode.lieber225er(0.85, 0.82), "unter 90 %: kein 225er aus dieser Regel");
    }

    @Test
    void neueRundeFaengtVonVorneAn() {
        var z = new ArenaMode.ProtokollZu();
        z.naechstes(3, 10_000, GEDULD);
        z.geklickt(10_000);
        z.geklickt(12_000);
        z.geklickt(14_000);
        assertEquals(AUFGEBEN, z.naechstes(3, 16_000, GEDULD));
        assertEquals(KLICKEN, z.naechstes(4, 16_100, GEDULD));
    }
}
