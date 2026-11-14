package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefer 29.10.: eine abgebrochene Einheiten-Pruefung meldet -1 Einheiten (unbekannt). Der Notizblock behaelt dann die
 * zuletzt vollstaendig gezaehlte Zahl, statt "4" einzutragen.
 */
class GegnerbuchEinheitenUnbekanntTest {

    @TempDir
    Path ordner;

    @Test
    void abgebrochenePruefungBehaeltDieAlteEinheitenzahl() {
        Gegnerbuch b = new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
        b.merkePruefung("Sharon", "Wolves", 40, "Paladin 6/6", 2, 6, 0, 0, 0);
        assertEquals(6, b.nachschlagen("Sharon", "Wolves").orElseThrow().einheiten());
        b.merkePruefung("Sharon", "Wolves", 28, "Paladin 4/6, ?", 2, -1, 0, 0, 0);
        Gegnerbuch.Notiz n = b.nachschlagen("Sharon", "Wolves").orElseThrow();
        assertEquals(6, n.einheiten());
        assertEquals(28, n.setPunkte());   // was gelesen wurde, gilt
        // Ganz neu und abgebrochen: unbekannt, keine Strafe.
        b.merkePruefung("Neu", "Wolves", 20, "?", 0, -1, 0, 0, 0);
        assertEquals(-1, b.nachschlagen("Neu", "Wolves").orElseThrow().einheiten());
    }
}
