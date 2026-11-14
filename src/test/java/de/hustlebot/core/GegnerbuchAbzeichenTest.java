package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruefer 29.10. (3.109-3.112): vor 11 von 23 Gegnernamen stand die Stufe aus dem Abzeichen ("G4nawa", "49cowboyup",
 * "53HELIOS", "i66Bernd"). Saubere Namen wurden dauerhaft ersetzt ('Schreibweise verbessert: "Rina" -> "G6RinassCr"',
 * 'Merged: паша () into G4nawa ()'), weil schoener() +0,1 je Zeichen gab.
 */
class GegnerbuchAbzeichenTest {

    @TempDir
    Path ordner;

    private Gegnerbuch buch() {
        return new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"),
                new Glyphenschrift(ordner.resolve("glyphen")));
    }

    @Test
    void abzeichenVorEinemBekanntenNamen() {
        assertTrue(Gegnerbuch.abzeichenVor("G4nawa", "паша"));          // Rest gleiches Skelett
        assertTrue(Gegnerbuch.abzeichenVor("49cowboyup", "cowboyup"));
        assertTrue(Gegnerbuch.abzeichenVor("53HELIOS", "HELIOS"));
        assertTrue(Gegnerbuch.abzeichenVor("i66Bernd", "Bernd"));
        assertTrue(Gegnerbuch.abzeichenVor("G1Mauricio (1", "Mauricio (1)"));
        // Echte Namen mit Ziffern vorn bleiben, solange der Rest zu keinem bekannten Namen passt.
        assertFalse(Gegnerbuch.abzeichenVor("53vilaase", "Bernd"));
        assertFalse(Gegnerbuch.abzeichenVor("53vilaase", "53vilaase"));
        assertFalse(Gegnerbuch.abzeichenVor("cowboyup", "49cowboyup"));   // umgekehrt ist kein Abzeichen
        assertFalse(Gegnerbuch.abzeichenVor("G1kerobaar", "keroba"));     // Rest nur aehnlich: nicht anfassen
        assertFalse(Gegnerbuch.abzeichenVor("4ab", "ab"));                // zu kurz
    }

    @Test
    void laengererNameMitAbzeichenIstNichtSchoener() {
        assertFalse(Gegnerbuch.schoener("Rina", "G6RinassCr"));
        assertFalse(Gegnerbuch.schoener("HELIOS", "53HELIOS"));
        assertFalse(Gegnerbuch.schoener("cowboyup", "49cowboyup"));
        // Umgekehrt: der alte Eintrag hat das Abzeichen, der neue nicht -> der neue gewinnt.
        assertTrue(Gegnerbuch.schoener("G4nawa", "паша"));
        assertTrue(Gegnerbuch.schoener("53HELIOS", "HELIOS"));
    }

    @Test
    void beimLadenHeiltDerEintragMitAbzeichen() {
        Gegnerbuch b = buch();
        // Erst mit Abzeichen gelesen (mehr Kaempfe), dann sauber -- beide Wege muessen beim sauberen Namen enden.
        b.ergebnis("G4nawa", "", true, 33_331_334, 20, 0, null);
        b.ergebnis("G4nawa", "", true, 33_331_334, 20, 0, null);
        b.ergebnis("паша", "", false, 33_331_334, 20, 0, null);
        b.ergebnis("49cowboyup", "ohne Clan", true, 19_270_635, 15, 0, null);
        b.ergebnis("cowboyup", "ohne Clan", true, 19_270_635, 15, 0, null);
        b.jetztSpeichern();

        List<Gegnerbuch.Notiz> alle = buch().alle();
        assertEquals(2, alle.size(), alle.toString());
        Gegnerbuch.Notiz pascha = alle.stream().filter(n -> n.name().equals("паша")).findFirst().orElseThrow();
        assertEquals(3, pascha.kaempfe());
        Gegnerbuch.Notiz cowboy = alle.stream().filter(n -> n.name().equals("cowboyup")).findFirst().orElseThrow();
        assertEquals(2, cowboy.kaempfe());
    }

    @Test
    void echterNameMitZiffernBleibtAllein() {
        Gegnerbuch b = buch();
        b.ergebnis("53vilaase", "ohne Clan", true, 25_000_000, 15, 0, null);
        b.ergebnis("Bernd", "MeinClan", true, 23_854_440, 15, 0, null);
        b.jetztSpeichern();
        List<Gegnerbuch.Notiz> alle = buch().alle();
        assertEquals(2, alle.size());
        assertTrue(alle.stream().anyMatch(n -> n.name().equals("53vilaase")));
    }
}
