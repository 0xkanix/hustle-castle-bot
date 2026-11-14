package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 28.10.: derselbe Arena-Gegner nur EINMAL -- "JtNXED", "JkNXED MURENA" und "JINXED MURENA" (Clan "Июльский",
 * Kraft 34,7 bis 35,5 Mio) standen als vier Eintraege in der Sperrliste. Zwei verschiedene Spieler mit aehnlichem Namen,
 * aber anderer Kraft ("Denis" 20 Mio, "Dennis" 31 Mio) bleiben getrennt. Alles nur mit Arena-Schalter.
 */
class ArenaZusammenfuehrenTest {

    @TempDir
    Path ordner;

    private static final String KOPF = "# Kraft\tSiege\tNiederlagen\tName\tClan\tSets\tEinheiten\tBild\tFingerabdruecke\n";
    /** Die vier Zeilen aus Autors arena-sperre.txt (28.10.). */
    private static final String JINXED_SPERRE = KOPF
            + "35501945\t0\t3\tJtNXED\tИюльский\t\t\t\t\n"
            + "35439211\t0\t3\tJtNXED\tИюльский\t\t\t\t\n"
            + "34737932\t0\t3\tJkNXED MURENA\tИюльский\t\t\t\t\n"
            + "0\t0\t3\tJINXED MURENA\tИюльский\t\t\t\t\n";
    /** Dieselben Lesarten im Notizblock (Name, Clan, Verlauf, Kraft, Artefakte, zuletzt). */
    private static final String JINXED_DATEN =
            "JtNXED\tИюльский\tNNN\t35501945\t-1\t2026-10-19 12:00\n"
            + "JkNXED MURENA\tИюльский\tNN\t34737932\t-1\t2026-10-20 12:00\n"
            + "JINXED MURENA\tV hionbCKb4i B Tep\tNNNSNN\t35439211\t-1\t2026-10-22 15:30\n"
            + "JINXED MURENA\tИюльский\tN\t-1\t-1\t2026-10-21 12:00\n";

    @AfterEach
    void schalterZurueck() {
        ArenaSchalter.fuerTest(null);
    }

    private Glyphenschrift leereBibliothek() {
        return new Glyphenschrift(ordner.resolve("glyphen"));
    }

    private Path daten() {
        return ordner.resolve("arena-daten.txt");
    }

    private Path sperre() {
        return ordner.resolve("arena-sperre.txt");
    }

    private Supplier<Gegnerbuch> buch() {
        return () -> new Gegnerbuch(daten(), ordner.resolve("arena-notizblock.txt"), leereBibliothek());
    }

    private List<String> sperrZeilen() throws Exception {
        return Files.readAllLines(sperre(), StandardCharsets.UTF_8).stream().filter(z -> !z.startsWith("#")).toList();
    }

    // ------------------------------------------------------------------ die Regel

    @Test
    void ersterWortMitVerwechslern() {
        assertEquals("jinxed", ArenaZusammenfuehren.erstesWort("JtNXED"));
        assertEquals("jinxed", ArenaZusammenfuehren.erstesWort("JkNXED MURENA"));
        assertEquals("jinxed", ArenaZusammenfuehren.erstesWort("JINXED MURENA"));
        assertEquals("jinxed", ArenaZusammenfuehren.erstesWort("  J|NXED  "));
        assertEquals("ocean", ArenaZusammenfuehren.erstesWort("0cean"));
        assertEquals("", ArenaZusammenfuehren.erstesWort("  ~~ "));
    }

    @Test
    void regelKraftUndKurzeNamen() {
        // JtNXED / JkNXED MURENA: 2,2 % auseinander, gleicher Clan -> derselbe
        assertTrue(ArenaZusammenfuehren.gleicherSpieler("JtNXED", "Июльский", 35_501_945, "JkNXED MURENA", "Июльский", 34_737_932));
        // ... mit anderem Clan nur bis 1,5 %
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("JtNXED", "Nordwacht", 35_501_945, "JkNXED MURENA", "Suedwind", 34_737_932));
        assertTrue(ArenaZusammenfuehren.gleicherSpieler("JtNXED", "Nordwacht", 35_501_945, "JINXED", "Suedwind", 35_439_211));
        // Pruefung 29.10.: haeufiges kurzes erstes Wort + anderer Clan -> nie derselbe (0,52 % lagen dazwischen)
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("Lord Rekster", "V Not in clan", 28_514_434, "lord", "The Legend", 28_663_568));
        // anderer Clan, langes Wort, aber 1 % auseinander -> getrennt
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("JINXED", "Nordwacht", 35_501_945, "JINXED", "Suedwind", 35_150_000));
        // Denis / Dennis: anderer Name, ganz andere Kraft -> getrennt; derselbe Name mit anderer Kraft auch
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("Denis", "Nordwacht", 20_123_456, "Dennis", "Nordwacht", 31_234_567));
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("Denis", "Nordwacht", 20_123_456, "Denis", "Nordwacht", 31_234_567));
        // kurze Namen (unter 4 Zeichen) nur mit 1 %
        assertTrue(ArenaZusammenfuehren.gleicherSpieler("Max", "", 20_000_123, "Max", "", 20_150_456));
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("Max", "", 20_000_123, "Max", "", 20_250_456));
        // Kraft unbekannt: nur ganz gleicher Name im selben Clan
        assertTrue(ArenaZusammenfuehren.gleicherSpieler("JINXED MURENA", "Июльский", 0, "JkNXED MURENA", "Июльский", 34_737_932));
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("JINXED MURENA", "Июльский", 0, "JtNXED", "Июльский", 35_501_945));
        assertFalse(ArenaZusammenfuehren.gleicherSpieler("JINXED MURENA", "", 0, "JkNXED MURENA", "", 34_737_932));
    }

    // ------------------------------------------------------------------ Altbestand

    @Test
    void jinxedWirdInDerSperrlisteEinSpieler() throws Exception {
        ArenaSchalter.fuerTest(true);
        Files.writeString(sperre(), JINXED_SPERRE, StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());

        assertEquals(3, ArenaZusammenfuehren.zusammenfuehren(daten(), buch()));

        List<String> zeilen = sperrZeilen();
        assertEquals(1, zeilen.size(), String.join("\n", zeilen));
        assertTrue(zeilen.get(0).startsWith("35501945\t0\t12\tJtNXED\tИюльский"), zeilen.get(0));
        // Sicherung mit dem alten Stand, Bericht mit den Namen
        assertEquals(JINXED_SPERRE, Files.readString(ordner.resolve("arena-sperre.txt.vor-zusammenfuehren"), StandardCharsets.UTF_8));
        String bericht = Files.readString(ordner.resolve(ArenaZusammenfuehren.BERICHT), StandardCharsets.UTF_8);
        assertTrue(bericht.contains("JkNXED MURENA") && bericht.contains("JINXED MURENA") && bericht.contains("->"), bericht);
        assertTrue(Files.exists(ordner.resolve(ArenaZusammenfuehren.MARKE)));
        // gesperrt bleibt er unter jeder Lesart
        assertNotNull(ArenaSperre.sperrgrund("JkNXED MURENA", "Июльский", 34_737_932, ""));

        // zweiter Start, gleicher Datei-Stand: nichts zu tun
        assertEquals(0, ArenaZusammenfuehren.zusammenfuehren(daten(), buch()));
        assertEquals(bericht, Files.readString(ordner.resolve(ArenaZusammenfuehren.BERICHT), StandardCharsets.UTF_8));
    }

    @Test
    void jinxedWirdImNotizblockEinSpieler() throws Exception {
        ArenaSchalter.fuerTest(true);
        Files.writeString(daten(), JINXED_DATEN, StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());

        assertTrue(ArenaZusammenfuehren.zusammenfuehren(daten(), buch()) > 0);

        Gegnerbuch neu = buch().get();
        assertEquals(1, neu.anzahl());
        Gegnerbuch.Notiz n = neu.alle().get(0);
        assertEquals("JINXED MURENA", n.name());   // der Eintrag mit den meisten Kaempfen bleibt
        assertEquals(10, n.kaempfe());              // 12 Kaempfe, die letzten zehn zaehlen
        assertEquals(1, n.siege());
        assertTrue(n.verlauf().endsWith("SNN"), n.verlauf());   // der neueste Verlauf hinten
        assertEquals(35_439_211, n.staerke());
        assertEquals(JINXED_DATEN, Files.readString(ordner.resolve("arena-daten.txt.vor-zusammenfuehren"), StandardCharsets.UTF_8));
    }

    @Test
    void denisUndDennisBleibenGetrennt() throws Exception {
        ArenaSchalter.fuerTest(true);
        Files.writeString(daten(), "Denis\tNordwacht\tNNN\t20123456\t-1\t2026-10-20 12:00\n"
                + "Dennis\tSuedwind\tSSN\t31234567\t-1\t2026-10-21 12:00\n", StandardCharsets.UTF_8);
        Files.writeString(sperre(), KOPF + "20123456\t0\t3\tDenis\tNordwacht\t\t\t\t\n"
                + "31234567\t2\t1\tDennis\tNordwacht\t\t\t\t\n", StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());

        assertEquals(0, ArenaZusammenfuehren.zusammenfuehren(daten(), buch()));
        assertEquals(2, buch().get().anzahl());
        assertEquals(2, sperrZeilen().size());
        assertFalse(Files.exists(ordner.resolve(ArenaZusammenfuehren.BERICHT)));
    }

    @Test
    void gleichesNamensbildWirdZusammengelegt() throws Exception {
        ArenaSchalter.fuerTest(true);
        Files.writeString(sperre(), KOPF + "0\t0\t1\tGods\t\t\t\t\t1.2.3.4.5.6.7.8\n"
                + "0\t0\t2\tMyth 5Qds\t\t\t\t\t1.2.3.4.5.6.7.9\n", StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());

        assertEquals(1, ArenaZusammenfuehren.zusammenfuehren(daten(), buch()));
        List<String> zeilen = sperrZeilen();
        assertEquals(1, zeilen.size());
        assertTrue(zeilen.get(0).startsWith("0\t0\t3\tMyth 5Qds\t"), zeilen.get(0));
        assertTrue(zeilen.get(0).endsWith("1.2.3.4.5.6.7.9|1.2.3.4.5.6.7.8"), zeilen.get(0));
    }

    @Test
    void schalterAusNichtsPassiert() throws Exception {
        ArenaSchalter.fuerTest(false);
        Files.writeString(daten(), JINXED_DATEN, StandardCharsets.UTF_8);
        Files.writeString(sperre(), JINXED_SPERRE, StandardCharsets.UTF_8);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());

        assertEquals(0, ArenaZusammenfuehren.zusammenfuehren(daten(), () -> fail("Notizblock darf nicht geladen werden")));
        assertEquals(JINXED_DATEN, Files.readString(daten(), StandardCharsets.UTF_8));
        assertEquals(JINXED_SPERRE, Files.readString(sperre(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(ordner.resolve("arena-daten.txt.vor-zusammenfuehren")));
        assertFalse(Files.exists(ordner.resolve("arena-sperre.txt.vor-zusammenfuehren")));
        assertFalse(Files.exists(ordner.resolve(ArenaZusammenfuehren.BERICHT)));
        assertFalse(Files.exists(ordner.resolve(ArenaZusammenfuehren.MARKE)));
    }

    // ------------------------------------------------------------------ neue Eintraege

    @Test
    void neuerEintragInDerSperrliste() throws Exception {
        ArenaSchalter.fuerTest(true);
        ArenaSperre.fuerTest(sperre(), leereBibliothek());
        ArenaSperre.ergebnis("JtNXED", "Июльский", 35_501_945, "", false);
        ArenaSperre.ergebnis("JkNXED MURENA", "Июльский", 34_737_932, "", false);
        ArenaSperre.ergebnis("JINXED MURENA", "Июльский", -1, "", false);
        ArenaSperre.ergebnis("Denis", "Nordwacht", 20_123_456, "", false);
        ArenaSperre.ergebnis("Dennis", "Suedwind", 31_234_567, "", false);
        List<String> zeilen = sperrZeilen();
        assertEquals(3, zeilen.size(), String.join("\n", zeilen));
        assertTrue(zeilen.get(0).contains("\t0\t3\tJINXED MURENA\t"), zeilen.get(0));   // drei Lesarten, eine Bilanz
        assertNotNull(ArenaSperre.sperrgrund("JtNXED", "Июльский", 35_400_123, ""));

        // Schalter aus: wie bisher ein neues Profil
        ArenaSchalter.fuerTest(false);
        Files.delete(sperre());
        ArenaSperre.fuerTest(sperre(), leereBibliothek());
        ArenaSperre.ergebnis("JtNXED", "Июльский", 35_501_945, "", false);
        ArenaSperre.ergebnis("JkNXED MURENA", "Июльский", 34_737_932, "", false);
        assertEquals(2, sperrZeilen().size());
    }

    @Test
    void neuerEintragImNotizblock() {
        ArenaSchalter.fuerTest(true);
        Gegnerbuch b = buch().get();
        b.ergebnis("JtNXED", "Июльский", false, 35_501_945);
        b.ergebnis("JkNXED MURENA", "Июльский", false, 34_737_932);
        b.ergebnis("Denis", "Nordwacht", false, 20_123_456);
        b.ergebnis("Dennis", "Suedwind", true, 31_234_567);
        assertEquals(3, b.anzahl());
        // beide Lesarten (mit der Kraft aus dem Turnier) finden denselben Eintrag mit beiden Kaempfen
        assertEquals(2, b.nachschlagen("JkNXED MURENA", "Июльский", 34_737_932, null).orElseThrow().kaempfe());
        assertEquals(2, b.nachschlagen("JtNXED", "Июльский", 35_501_945, null).orElseThrow().kaempfe());

        // Schalter aus: wie bisher zwei Eintraege
        ArenaSchalter.fuerTest(false);
        Gegnerbuch aus = new Gegnerbuch(ordner.resolve("aus.txt"), ordner.resolve("aus-notizblock.txt"), leereBibliothek());
        aus.ergebnis("JtNXED", "Июльский", false, 35_501_945);
        aus.ergebnis("JkNXED MURENA", "Июльский", false, 34_737_932);
        assertEquals(2, aus.anzahl());
    }
}
