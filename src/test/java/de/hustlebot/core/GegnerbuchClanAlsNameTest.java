package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefung 29.10.: 26 von 716 Erkundungen trugen als Namen die Clanzeile eines anderen Spielers ("響蛎茶多" = Clan von
 * Xenos). Ein Text, der nur als Clan im Notizblock steht, ist kein Spielername.
 */
class GegnerbuchClanAlsNameTest {

    @TempDir
    Path ordner;

    private Gegnerbuch buch() {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
    }

    @Test
    void clanEinesAnderenIstKeinName() {
        Gegnerbuch b = buch();
        b.ergebnis("Xenos", "響蛎茶多", false, 30_497_325, 20, 0, null);
        assertTrue(b.nurAlsClanBekannt("響蛎茶多"));
        assertFalse(b.nurAlsClanBekannt("Xenos"));          // ein Spielername
        assertFalse(b.nurAlsClanBekannt("Unbekannt123"));   // weder noch
        assertFalse(b.nurAlsClanBekannt(""));
    }

    @Test
    void spielerMitClanNamenBleibtSpieler() {
        Gegnerbuch b = buch();
        b.ergebnis("Xenos", "Nordwacht", false, 30_000_000, 20, 0, null);
        b.ergebnis("Nordwacht", "Andere", true, 25_000_000, 10, 0, null);   // ein Spieler heisst wie der Clan
        assertFalse(b.nurAlsClanBekannt("Nordwacht"));
    }
}
