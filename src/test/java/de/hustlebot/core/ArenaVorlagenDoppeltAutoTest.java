package de.hustlebot.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 28.10.: Bilder, die der Bot von selbst eintraegt, tragen "_auto" -- der Name kommt ohne "_auto" heraus. */
class ArenaVorlagenDoppeltAutoTest {

    private static final LocalDateTime WANN = LocalDateTime.of(2026, 10, 28, 14, 30);

    @Test
    void autoDateinameWieImBot() {
        String n = ArenaListenOrdner.autoDateiname("ar_sicher_", "Den is!", WANN);
        assertEquals("ar_sicher_Denis_auto_1028-1430.png", n);
        // Liste auch als "sicher" oder Ordnername "safe"
        assertEquals(n, ArenaListenOrdner.autoDateiname("sicher", "Denis", WANN));
        assertEquals(n, ArenaListenOrdner.autoDateiname("safe", "Denis", WANN));
        assertEquals("ar_block_gegner_auto_1028-1430.png", ArenaListenOrdner.autoDateiname("never", "!!", WANN));
        assertEquals("ar_ungern_X_auto_1028-1430.png", ArenaListenOrdner.autoDateiname("avoid", "X", WANN));
        assertEquals("ar_machbar_X_auto_1028-1430.png", ArenaListenOrdner.autoDateiname("doable", "X", WANN));
        // kyrillisch bleibt
        String kyr = "Сволочь";
        String k = ArenaListenOrdner.autoDateiname("ar_block_", kyr, WANN);
        assertEquals(kyr, ArenaVorlagenDoppelt.nameAusDatei(k));
        assertThrows(IllegalArgumentException.class, () -> ArenaListenOrdner.autoDateiname("clans", "X", WANN));
    }

    @Test
    void mitAuto() {
        String d = "ar_block_Denis_auto_1028-1430.png";
        assertEquals("Denis", ArenaVorlagenDoppelt.nameAusDatei(d));
        assertTrue(ArenaVorlagenDoppelt.vomBot(d));
        assertEquals("ar_block_", ArenaVorlagenDoppelt.liste(d));
        assertTrue(ArenaListenOrdner.istAuto(d));
        assertEquals("Loki", ArenaVorlagenDoppelt.nameAusDatei("ar_ungern_Loki_auto_1028-1430.PNG"));
        assertTrue(ArenaListenOrdner.istAuto("ar_ungern_Loki_auto_1028-1430.PNG"));
        // "auto" im Namen selbst bleibt stehen
        assertEquals("Xauto", ArenaVorlagenDoppelt.nameAusDatei("ar_sicher_Xauto_auto_1028-1430.png"));
        // keine Liste -> kein Listen-Bild des Bots
        assertFalse(ArenaListenOrdner.istAuto("ar_clan_Foo_auto_1028-1430.png"));
    }

    @Test
    void ohneAuto() {
        assertEquals("Denis", ArenaVorlagenDoppelt.nameAusDatei("ar_sicher_Denis_1019-0249.png"));
        assertTrue(ArenaVorlagenDoppelt.vomBot("ar_sicher_Denis_1019-0249.png"));
        assertFalse(ArenaListenOrdner.istAuto("ar_sicher_Denis_1019-0249.png"));
        assertEquals("WalkingDeath", ArenaVorlagenDoppelt.nameAusDatei("ar_machbar_WalkingDeath.png"));
        assertFalse(ArenaVorlagenDoppelt.vomBot("ar_machbar_WalkingDeath.png"));
        assertFalse(ArenaListenOrdner.istAuto("ar_machbar_WalkingDeath.png"));
        assertEquals("ar_machbar_", ArenaVorlagenDoppelt.liste("ar_machbar_WalkingDeath.png"));
        // ein Spieler namens "auto" ist keine Bot-Aufnahme
        assertEquals("auto", ArenaVorlagenDoppelt.nameAusDatei("ar_sicher_auto.png"));
        assertFalse(ArenaVorlagenDoppelt.vomBot("ar_sicher_auto.png"));
        assertFalse(ArenaListenOrdner.istAuto("ar_sicher_auto.png"));
        assertFalse(ArenaListenOrdner.istAuto(null));
    }
}
