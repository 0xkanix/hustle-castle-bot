package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Autor 31.10.: 2 Einheiten je 9 Mio und 4 Bauarbeiter: je Kampf mitschreiben, um die Kraft je Einheit spaeter zu pruefen. */
class ArenaKraftWerteTest {

    @Test
    void autorsBeispiel() {
        List<String> z = List.of("# gegner", "1\t-\t9000000\t1", "2\t-\t9000000\t-1", "3\tbau\t50000\t1", "4\tbau\t50000\t1", "5\tbau\t50000\t1", "6\tbau\t50000\t1");
        assertArrayEquals(new long[]{9_000_000L, 4, 9_000_000L}, ArenaAussehen.kraftWerte(z));
    }

    @Test
    void nurBauarbeiterHabenKeinenKaempferSchnitt() {
        assertArrayEquals(new long[]{50_000L, 2, 0}, ArenaAussehen.kraftWerte(List.of("1\tbau\t50000\t1", "2\tbau\t40000\t1")));
    }

    @Test
    void unlesbareKraftZaehltNichtAlsEinheitMitKraft() {
        assertArrayEquals(new long[]{5_000_000L, 0, 5_000_000L}, ArenaAussehen.kraftWerte(List.of("1\t-\t-1\t1", "2\t-\tkaputt\t1", "3\t-\t5000000\t1")));
        assertNull(ArenaAussehen.kraftWerte(List.of("# leer", "1\t-\t-1\t1")));
        assertNull(ArenaAussehen.kraftWerte(List.of()));
    }
}
