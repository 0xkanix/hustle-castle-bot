package de.hustlebot.schicht;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Autor 01.11.: Seitenfenster "Stage 1/5", obwohl er in Stage 2 ist -- die am Bild gelesene Stage gilt, solange sie frisch ist. */
class ArenaStandRundeTest {

    @Test
    void frischGeleseneStageSchlaegtDenAltenStand() {
        long jetzt = 1_000_000L;
        assertEquals(2, ArenaStand.anzeigeRunde(1, 2, jetzt - 1_000, jetzt));
        assertEquals(4, ArenaStand.anzeigeRunde(3, 4, jetzt - 100_000, jetzt), "eine Stage dauert zwei Minuten: 100 s sind frisch");
    }

    @Test
    void alteOderUnsinnigeMeldungWirdIgnoriert() {
        long jetzt = 1_000_000L;
        assertEquals(1, ArenaStand.anzeigeRunde(1, 5, jetzt - ArenaStand.GELESENE_RUNDE_MS - 1, jetzt), "zu alt (vorige Arena)");
        assertEquals(3, ArenaStand.anzeigeRunde(3, 0, jetzt, jetzt), "nie gemeldet");
        assertEquals(3, ArenaStand.anzeigeRunde(3, 7, jetzt, jetzt), "unsinnige Zahl");
        assertEquals(3, ArenaStand.anzeigeRunde(3, 4, 0, jetzt), "ohne Zeitstempel");
    }

    @Test
    void rundeGelesenWirdUebernommenUndUnsinnigesNicht() {
        ArenaStand.rundeGelesen(4);
        assertEquals(4, ArenaStand.anzeigeRunde(2));
        ArenaStand.rundeGelesen(9);
        assertEquals(4, ArenaStand.anzeigeRunde(2), "9 wird ignoriert");
        ArenaStand.rundeGelesen(1);
        assertEquals(1, ArenaStand.anzeigeRunde(5), "neue Arena: 1 gilt sofort");
    }
}
