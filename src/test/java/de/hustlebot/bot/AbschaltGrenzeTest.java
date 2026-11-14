package de.hustlebot.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.hustlebot.core.Rechenlast;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Autor 31.10.: "Normalerweise bleibt es auf 90 Grad, aber wenn du an irgendwelchen Sachen baust, ist 95 auch okay." */
class AbschaltGrenzeTest {

    @Test
    void ohneBauBleibtEsBei90() {
        assertEquals(90, BotEngine.abschaltGrenze(true, List.of(new Rechenlast.Programm("Hustle Castle.exe", 300), new Rechenlast.Programm("javaw.exe", 120))));
    }

    @Test
    void waehrendEinesBausGelten95() {
        assertEquals(95, BotEngine.abschaltGrenze(true, List.of(new Rechenlast.Programm("java.exe", 400))));
        assertEquals(95, BotEngine.abschaltGrenze(true, List.of(new Rechenlast.Programm("jpackage.exe", 80))));
    }

    @Test
    void einFastRuhendesBauProgrammZaehltNicht() {
        assertEquals(90, BotEngine.abschaltGrenze(true, List.of(new Rechenlast.Programm("java.exe", 10))));
    }

    @Test
    void ohneFreigabeBleibtEsBei90AuchBeimBau() {
        assertEquals(90, BotEngine.abschaltGrenze(false, List.of(new Rechenlast.Programm("java.exe", 400))));
    }
}
