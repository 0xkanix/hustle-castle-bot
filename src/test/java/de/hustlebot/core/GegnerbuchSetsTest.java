package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10.: Clan ganz anders gelesen (z. B. arabisch statt kyrillisch, oder der Name selbst als Clan) und keine
 * Kraft zum Vergleichen -- dieselben Sets Einheit fuer Einheit sind derselbe Spieler (Notizblock 22.10.: "Jge Black",
 * "Baut", "lord" doppelt). Zwei "Нидо" mit ganz anderen Sets bleiben zwei Spieler.
 */
class GegnerbuchSetsTest {

    @TempDir
    Path ordner;

    private static final String SETS_JGE = "Crusader 2/6 + Necromancer 2/6 + Forest Watcher 2/6, Robin Bad 6/6, "
            + "Ice Angler 6/6, Armor Breaker 6/6, kein Set, 1x ab 7 Mio Kraft (+2), 2x gutes Totem (+6)";

    private Gegnerbuch buch() {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
    }

    private static void pruefung(Gegnerbuch b, String name, String clan, String sets) {
        b.merkePruefung(name, clan, 21, sets, 0, 5, 0, 0, 0, -1, 0, 0, -1);
    }

    @Test
    void gleicheSetsAndererClanWirdZusammengelegt() {
        Gegnerbuch b = buch();
        b.ergebnis("Jge Black", "шорох", false, 34_722_504);
        b.ergebnis("Jge Black", "шорох", true, 34_722_504);
        b.ergebnis("Jge Black", "шорох", true, 34_722_504);
        pruefung(b, "Jge Black", "шорох", SETS_JGE);
        // Vergleichsfenster: Clan auf Arabisch gelesen, keine Kraft -- dieselben Sets
        pruefung(b, "Jge Black", "ب ومهس", SETS_JGE);
        assertEquals(1, b.anzahl());
        // Kampf unter der falschen Lesart landet beim alten Eintrag, ohne neuen anzulegen
        b.ergebnis("Jge Black", "ب ومهس", true, -1);
        assertEquals(1, b.anzahl());
        assertEquals(4, b.nachschlagen("Jge Black", "шорох").orElseThrow().kaempfe());
    }

    @Test
    void ganzAndereSetsBleibenZweiSpieler() {
        Gegnerbuch b = buch();
        pruefung(b, "Нидо", "Bando eros BR", "Incinerator 6/6, Illusionist 6/6, Darkula 6/6, Defender of Nature 6/6, "
                + "Priest 6/6, Necromancer 6/6, 3x ab 7 Mio Kraft (+7)");
        pruefung(b, "Нидо", "Chill out", "Incinerator 6/6, Executioner of Darkness 6/6, Servant of the Night 6/6, "
                + "Ice Angler 6/6, Robin Bad 6/6, Armor Breaker 6/6, 3x ab 7 Mio Kraft (+7)");
        assertEquals(2, b.anzahl());
    }

    @Test
    void nurKeinSetReichtNicht() {
        Gegnerbuch b = buch();
        String leer = "kein Set, kein Set, kein Set, kein Set, Ice Angler 6/6";
        pruefung(b, "Baut", "Совесть", leer);
        pruefung(b, "Baut", "Baut", leer);
        assertEquals(2, b.anzahl(), "4x 'kein Set' + 1 echtes Set ist zu wenig, um sicher zu sein");
        // mit zwei echten Sets reicht es
        String echt = "kein Set, kein Set, Ice Angler 6/6, Defender of Nature 6/6, kein Set, 2x ab 7 Mio Kraft (+6)";
        pruefung(b, "Baut", "Совесть", echt);
        pruefung(b, "Baut", "Baut", echt);
        assertEquals(1, b.anzahl());
    }
}
