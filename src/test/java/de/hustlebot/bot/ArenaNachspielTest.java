package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nachspiel der Arena (Autor, 11.10.: ein anderer Modus sieht den Turnier-Balken, die Arena spielt
 * das Turnier erst zu Ende). Danach muss das Spiel wieder in der BURG stehen -- dort war der
 * eigentliche Modus, als er die Nahrung lesen wollte. Hier nur die reine Entscheidung des Heimwegs.
 */
class ArenaNachspielTest {

    @Test
    void inDerBurgIstDasNachspielFertig() {
        assertEquals(ArenaMode.Heimweg.FERTIG, ArenaMode.heimwegSchritt(true, false, 1));
        // Auch wenn nebenbei etwas von der Weltkarte zu sehen waere: Burg gewinnt.
        assertEquals(ArenaMode.Heimweg.FERTIG, ArenaMode.heimwegSchritt(true, true, 1));
    }

    @Test
    void aufDerWeltkarteGehtEsUeberHomeUntenLinks() {
        assertEquals(ArenaMode.Heimweg.WELTKARTE_HEIM, ArenaMode.heimwegSchritt(false, true, 1));
    }

    @Test
    void woandersUeberDenGruenenHomeKnopf() {
        assertEquals(ArenaMode.Heimweg.HOME_KNOPF, ArenaMode.heimwegSchritt(false, false, 1));
    }

    @Test
    void nachZuVielenBildernWirdAufgegeben() {
        assertEquals(ArenaMode.Heimweg.HOME_KNOPF,
                ArenaMode.heimwegSchritt(false, false, ArenaMode.HEIMWEG_BILDER_MAX));
        assertEquals(ArenaMode.Heimweg.AUFGEBEN,
                ArenaMode.heimwegSchritt(false, false, ArenaMode.HEIMWEG_BILDER_MAX + 1));
        assertEquals(ArenaMode.Heimweg.AUFGEBEN,
                ArenaMode.heimwegSchritt(false, true, ArenaMode.HEIMWEG_BILDER_MAX + 1));
        // In der Burg angekommen zaehlt immer, egal wie lange es gedauert hat.
        assertEquals(ArenaMode.Heimweg.FERTIG,
                ArenaMode.heimwegSchritt(true, false, ArenaMode.HEIMWEG_BILDER_MAX + 5));
    }
}
