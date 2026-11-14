package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Autor 31.10.: "Das soll nicht doppelt zaehlen": bewertete Gegenstaende ersetzen den Abzug fuer "kein Set". */
class ArenaItemDoppelwertungTest {

    private static final ArenaSets.Treffer KEIN_SET = new ArenaSets.Treffer("kein Set", '-', ArenaSets.KEIN_SET);

    @Test
    void mitBewertetenGegenstaendenZaehltKeinSetNichts() {
        var t = ArenaPruefer.ohneDoppelwertung(KEIN_SET, true);
        assertEquals(0, t.punkte());
        assertEquals("kein Set", t.set());
    }

    @Test
    void ohneBewertungBleibtDerAbzug() {
        assertSame(KEIN_SET, ArenaPruefer.ohneDoppelwertung(KEIN_SET, false));
    }

    @Test
    void einUnlesbarerSetNameBleibtUnveraendert() {
        var unlesbar = new ArenaSets.Treffer("?", '?', ArenaSets.UNBEKANNT);
        assertSame(unlesbar, ArenaPruefer.ohneDoppelwertung(unlesbar, true));
    }
}
