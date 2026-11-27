package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 15.11. / 4GVL, 5MCQ (Karla, 3.123, Arena): das Spielfenster "Maintenance -- The game server will shut down in 0s. The Arena is
 * not available at the moment" mit "OK" war unbekannt -- der Bot klickte 3 min lang die Arena auf der Weltkarte an.
 */
class StoerfensterWartungTest {

    @Test
    void ankuendigungMitNotAvailableUndShutDown() {
        // Texterkennung ueber den Bereich 0,15/0,10 bis 0,85/0,45 (Spielbild 4GVL), mit Beschriftungen der Weltkarte drumherum.
        String text = "scorching ground ice realm orc woods enemies: 1 tower maintenance the game server will shut down in "
                + "0s. the arena is not available at";
        assertEquals(Stoerfenster.WartungsFenster.ANKUENDIGUNG, Stoerfenster.wartungsFenster(text));
        assertEquals(Stoerfenster.WartungsFenster.ANKUENDIGUNG,
                Stoerfenster.wartungsFenster("maintenance the arena is not available at the moment."));
        assertEquals(Stoerfenster.WartungsFenster.ANKUENDIGUNG,
                Stoerfenster.wartungsFenster("maintenance the game server will shut down in 45s."));
        assertEquals(Stoerfenster.WartungsFenster.ANKUENDIGUNG,
                Stoerfenster.wartungsFenster("maintenance the game server will shutdown in 45s."));
    }

    @Test
    void geschlossenWieBisher() {
        // #K4AL (Ben 26.10.): Server zu, gruenes "Retry".
        assertEquals(Stoerfenster.WartungsFenster.GESCHLOSSEN,
                Stoerfenster.wartungsFenster("server maintenance game servers are closed for prevention retry go to"));
        // Steht beides da, gilt "geschlossen" (Retry-Fenster).
        assertEquals(Stoerfenster.WartungsFenster.GESCHLOSSEN,
                Stoerfenster.wartungsFenster("maintenance servers closed, not available retry"));
    }

    @Test
    void keineWartung() {
        assertEquals(Stoerfenster.WartungsFenster.KEINS, Stoerfenster.wartungsFenster(""));
        assertEquals(Stoerfenster.WartungsFenster.KEINS, Stoerfenster.wartungsFenster(null));
        // Code-Review 26.10.: die Vorankuendigung allein ist keine Wartung.
        assertEquals(Stoerfenster.WartungsFenster.KEINS, Stoerfenster.wartungsFenster("server maintenance in 30 min"));
        // "not available" ohne "maintenance" ist ein anderes Fenster.
        assertEquals(Stoerfenster.WartungsFenster.KEINS, Stoerfenster.wartungsFenster("the arena is not available at the moment"));
        assertEquals(Stoerfenster.WartungsFenster.KEINS, Stoerfenster.wartungsFenster("arena tournament select the next opponent"));
    }

    @Test
    void nachOkNochFuenfMinutenWarten() {
        long jetzt = 5_000_000L;
        long bis = jetzt + Stoerfenster.WARTUNG_ANKUENDIGUNG_MS;
        assertEquals(5 * 60_000L, Stoerfenster.WARTUNG_ANKUENDIGUNG_MS);
        // Fenster nach "OK" weg: weiter warten, statt die gesperrte Arena wieder anzuklicken.
        assertTrue(Stoerfenster.nochWarten(jetzt + 15_000, bis));
        assertTrue(Stoerfenster.nochWarten(bis - 1, bis));
        // Danach darf der Modus es wieder versuchen.
        assertFalse(Stoerfenster.nochWarten(bis, bis));
        assertFalse(Stoerfenster.nochWarten(jetzt, 0), "ohne Ankuendigung keine Wartezeit");
    }
}
