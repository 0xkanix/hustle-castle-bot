package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Autor 01.11., Lauf 14:45 (3.137): "Myth Gods" ist der CLAN von ~ЦИРИ~, stand aber als Name im Notizblock ("V Myth Gods" / "O'Myth Go s", Bilanz
 * SSSSSSSSSN) -- die Bilanz und Kraft eines Spielers galten fuer jeden aus dem Clan. Alle Namen unten sind echte Eintraege oder Protokollzeilen.
 */
class GegnerbuchClanNameTest {

    @Test
    void clanAlsNameWirdErkannt() {
        assertTrue(Gegnerbuch.nameIstClan("V Myth Gods", "O'Myth Go s"), "echter Eintrag, Bilanz SSSSSSSSSN");
        assertTrue(Gegnerbuch.nameIstClan("Gods", "O'Myth Go s"), "echter Eintrag: Clan-Stueck als Name");
        assertTrue(Gegnerbuch.nameIstClan("Myth Gods", "O'Myth Go s"), "der Name auf dem Schild beim Angriff 14:55");
        assertTrue(Gegnerbuch.nameIstClan("Cthulhu's Re", "Realm") || Gegnerbuch.nameIstClan("Cthulhu's Realr", "Cthulhu's Realm"));
    }

    @Test
    void echteSpielerNamenBleibenNamen() {
        assertFalse(Gegnerbuch.nameIstClan("zevs", "e Myth Go s"), "echter Spieler im selben Clan");
        assertFalse(Gegnerbuch.nameIstClan("россгард", "Mythŕds"));
        assertFalse(Gegnerbuch.nameIstClan("~ЦИРИ~", "Myth Gods"));
        assertFalse(Gegnerbuch.nameIstClan("屈機黑雞", "Cthulhu's Realm"));
        assertFalse(Gegnerbuch.nameIstClan("FLIMSY", "FLIMSY"), "Autor 01.11.: heisst wirklich so, nur der Clan wurde nicht gelesen");
        assertFalse(Gegnerbuch.nameIstClan("Litos", "Litos"));
        assertFalse(Gegnerbuch.nameIstClan("Ava", "Kein Clan"), "ohne Clan kein Clan-Name");
        assertFalse(Gegnerbuch.nameIstClan("元素神", ""), "ohne Clan nichts zu vergleichen");
    }

    @Test
    void gleicheKraftOderGleicherClanAllein_legtKeineSpielerZusammen() {
        // "~ЦИРИ" und "V Myth Gods" haben dieselbe Kraft (37.873.601), aber der Name passt nicht.
        assertFalse(Gegnerbuch.namenVertraeglich("~ЦИРИ~", "V Myth Gods"));
        assertFalse(Gegnerbuch.derselbe("цири", "vmythgods", 37_873_601L, 37_873_601L), "gleiche Kraft allein reicht nicht");
        // die Faelle, fuer die die Kraft-Regel gedacht war: derselbe Name, nur anders gelesen
        assertTrue(Gegnerbuch.namenVertraeglich("響蛎茶多", "蛎茶多蛎走冰加蛎峯"), "Teil des Namens gemeinsam");
        assertTrue(Gegnerbuch.namenVertraeglich("-UMPM-", "UMPM"));
        assertTrue(Gegnerbuch.namenVertraeglich("i", "Rina"), "unlesbar (unter 3 Zeichen): kein Gegenbeweis");
        assertTrue(Gegnerbuch.derselbe("蛎茶多蛎走冰加蛎峯", "响蛎茶多", 40_000_001L, 40_000_001L));
    }

    @Test
    void platzhalterBildNameWirdVonGlaubhafterLesungErsetztFehlformenNicht() {
        // Protokoll 14:49:30: Schild bei 462,378: Namensbild Bild-1024-032130 erkannt (gelesen: "元素神")
        assertTrue(Gegnerbuch.plausiblerName("元素神"));
        assertTrue(Gegnerbuch.schoener("Bild-1024-032130", "元素神"), "echte Lesung ersetzt den Platzhalter");
        assertTrue(Gegnerbuch.schoener("Bild-1024-032130", "Sascha"));
        // Fehlformen aus dem Protokoll
        for (String fehl : new String[]{"冕괴困", "نسعع", "元奏雄联盟", "d", ""}) {
            assertFalse(Gegnerbuch.plausiblerName(fehl), "Fehlform: " + fehl);
            assertFalse(Gegnerbuch.schoener("Bild-1024-032130", fehl), "ersetzt den Platzhalter nicht: " + fehl);
            assertFalse(Gegnerbuch.schoener("元素神", fehl), "ueberschreibt keinen Namen: " + fehl);
        }
        assertFalse(Gegnerbuch.schoener("元素神", "元素神"));
    }

    /** Nur auf Autors Rechner: der Altbestand. Gezaehlt und gezeigt, nichts veraendert (Kopie in einem Temp-Ordner). */
    @Test
    void altbestandImEchtenNotizblockWirdGezaehlt(@TempDir Path ordner) throws Exception {
        Path echt = Path.of("C:/HustleBot/arena-daten.txt");
        Assumptions.assumeTrue(Files.exists(echt));
        Path kopie = ordner.resolve("arena-daten.txt");
        Files.copy(echt, kopie);
        Gegnerbuch buch = new Gegnerbuch(kopie, ordner.resolve("arena-notizblock.txt"), null);
        List<Gegnerbuch.Notiz> treffer = buch.clanAlsName();
        System.out.println("MESSUNG Altbestand: " + treffer.size() + " von " + buch.alle().size() + " Eintraegen tragen den Clan als Namen:");
        for (Gegnerbuch.Notiz n : treffer) {
            System.out.println("MESSUNG   \"" + n.name() + "\" / \"" + n.clan() + "\"  Bilanz " + n.siege() + " von " + n.kaempfe() + "  Kraft " + n.staerke());
        }
        assertTrue(treffer.stream().anyMatch(n -> n.name().equals("V Myth Gods")), "der Eintrag vom 14:55");
        // gezaehlt wird er nicht mehr:
        int[] b = buch.bilanzNachName("Myth Gods");
        assertEquals(0, b[1], "Bilanz nach Name zaehlt den Clan-Eintrag nicht");
        assertTrue(buch.nachschlagen("V Myth Gods", "O'Myth Go s").isEmpty(), "als Clan-Eintrag nicht mehr zu finden");
    }

    /** Der Hergang vom 14:55: zwei Spieler mit derselben Kraft, einer unter dem Clan-Namen -- nie in einem Eintrag, und der Clan-Eintrag zaehlt nicht. */
    @Test
    void zweiSpielerMitGleicherKraftWerdenNichtZusammengelegt(@TempDir Path ordner) {
        Gegnerbuch b = new Gegnerbuch(ordner.resolve("arena-daten.txt"), ordner.resolve("arena-notizblock.txt"), null);
        for (int i = 0; i < 9; i++) b.ergebnis("V Myth Gods", "O'Myth Go s", true, 37_873_601L, 12, 150L, (Glyphenschrift.Ergebnis) null);
        b.ergebnis("V Myth Gods", "O'Myth Go s", false, 37_873_601L, 15, 0L, (Glyphenschrift.Ergebnis) null);
        b.ergebnis("~ЦИРИ~", "", true, 37_873_601L, 10, 150L, (Glyphenschrift.Ergebnis) null);
        assertEquals(2, b.alle().size(), "gleiche Kraft allein legt nicht zusammen");
        assertEquals(0, b.bilanzNachName("Myth Gods")[1], "Eintrag mit Clan als Namen zaehlt nicht fuer die Bilanz");
        assertTrue(b.nachschlagen("V Myth Gods", "O'Myth Go s").isEmpty());
        assertEquals(1, b.nachschlagen("~ЦИРИ~", "").orElseThrow().kaempfe(), "der echte Spieler behaelt seinen eigenen Eintrag");
        assertEquals(1, b.clanAlsName().size());
    }

    @Test
    void namensbildPlatzhalterWirdZugeordnetUndAutorsZuordnungBleibt(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("arena-namensbilder.txt");
        assertTrue(Namensbilder.zuordnen(datei, "Bild-1024-032130", "元素神"), "neue Zuordnung");
        assertFalse(Namensbilder.zuordnen(datei, "Bild-1024-032130", "anderer"), "schon zugeordnet");
        Files.writeString(datei, "Bild-1002-012347 = Sascha" + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertFalse(Namensbilder.zuordnen(datei, "Bild-1002-012347", "元素神"), "Autors Zuordnung wird nie ueberschrieben");
        assertFalse(Namensbilder.zuordnen(datei, "Bild-1", "a=b"), "Namen mit = taugen nicht");
        String inhalt = Files.readString(datei);
        assertTrue(inhalt.contains("Bild-1024-032130 = 元素神"), inhalt);
        assertTrue(inhalt.contains("Bild-1002-012347 = Sascha"), inhalt);
    }
}
