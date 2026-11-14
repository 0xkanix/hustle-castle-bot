package de.hustlebot.steuer;

import de.hustlebot.steuer.Gesundheitscheck.Stufe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10. (Ideenliste F33): die Gesundheits-Ampel -- die Bewertung der einzelnen Punkte.
 */
class GesundheitscheckTest {

    @Test
    void spielfenster() {
        assertEquals(Stufe.GRUEN, Gesundheitscheck.spielfensterAus(true).stufe());
        var weg = Gesundheitscheck.spielfensterAus(false);
        assertEquals(Stufe.GELB, weg.stufe());
        assertNotNull(weg.hilfe());
    }

    @Test
    void fenstergroesseWieDerBotSieErwartet() {
        assertEquals(Stufe.GRUEN, Gesundheitscheck.fenstergroesseAus(1159, 770).stufe());
        assertEquals(Stufe.GRUEN, Gesundheitscheck.fenstergroesseAus(1157, 771).stufe(), "bis 2 Punkte Abweichung");
        var falsch = Gesundheitscheck.fenstergroesseAus(1175, 809);
        assertEquals(Stufe.ROT, falsch.stufe());
        assertTrue(falsch.befund().contains("1175") && falsch.befund().contains("809"));
        assertNotNull(falsch.hilfe());
    }

    @Test
    void skalierung() {
        assertEquals(Stufe.GRUEN, Gesundheitscheck.skalierungAusDpi(96).stufe());
        var p125 = Gesundheitscheck.skalierungAusDpi(120);
        assertEquals(Stufe.GELB, p125.stufe());
        assertTrue(p125.befund().contains("125"));
        assertTrue(Gesundheitscheck.skalierungAusDpi(144).befund().contains("150"));
    }

    @Test
    void energiesparplan() {
        assertEquals(Stufe.GRUEN, Gesundheitscheck.energieplanAusText(
                "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)").stufe());
        var sparen = Gesundheitscheck.energieplanAusText("Power Scheme GUID: a1841308-3541-4fab-bc81-f71556f20b4a  (Power saver)");
        assertEquals(Stufe.GELB, sparen.stufe());
        assertTrue(sparen.befund().contains("Power saver"));
        assertEquals(Stufe.GRAU, Gesundheitscheck.energieplanAusText("").stufe());
        assertEquals(Stufe.GRAU, Gesundheitscheck.energieplanAusText(null).stufe());
    }

    @Test
    void virenschutz() {
        assertEquals(Stufe.GRAU, Gesundheitscheck.virenschutzAus(List.of()).stufe());
        assertEquals(Stufe.GRUEN, Gesundheitscheck.virenschutzAus(List.of("Windows Defender")).stufe());
        assertEquals(Stufe.GRUEN, Gesundheitscheck.virenschutzAus(List.of("Microsoft Defender Antivirus")).stufe());
        var norton = Gesundheitscheck.virenschutzAus(List.of("Norton 360", "Windows Defender"));
        assertEquals(Stufe.GELB, norton.stufe());
        assertTrue(norton.hilfe().contains("Norton 360"));
        assertFalse(norton.hilfe().contains("Windows Defender"), "nur der fremde Virenschutz wird genannt");
    }

    @Test
    void bilder() {
        assertEquals(Stufe.ROT, Gesundheitscheck.bilderAus(false, true, true, 6, 6).stufe());
        var fehlt = Gesundheitscheck.bilderAus(true, false, false, 6, 6);
        assertEquals(Stufe.ROT, fehlt.stufe());
        assertTrue(fehlt.befund().contains("Opfer") || fehlt.befund().toLowerCase().contains("sacrifice"));
        assertEquals(Stufe.GELB, Gesundheitscheck.bilderAus(true, true, true, 0, 6).stufe());
        assertEquals(Stufe.GRUEN, Gesundheitscheck.bilderAus(true, true, true, 3, 6).stufe());
    }

    @Test
    void vorlageDaFindetMyPicturesUndTemplates(@TempDir Path bot) throws Exception {
        Path vorlagen = bot.resolve("templates");
        assertFalse(Gesundheitscheck.vorlageDa(bot, vorlagen, "kaempfer_1"));
        Path eigen = de.hustlebot.core.Eigenbilder.ordnerFuer(bot, "kaempfer_1");
        Files.createDirectories(eigen);
        Files.writeString(eigen.resolve("kaempfer_1.png"), "x");
        assertTrue(Gesundheitscheck.vorlageDa(bot, vorlagen, "kaempfer_1"));
        Files.createDirectories(vorlagen.resolve("dungeon"));
        Files.writeString(vorlagen.resolve("dungeon").resolve("nameplate_me.png"), "x");
        assertTrue(Gesundheitscheck.vorlageDa(bot, vorlagen, "nameplate_me"));
    }

    @Test
    void schnellePunkteWerfenNie() {
        List<Gesundheitscheck.Punkt> l = Gesundheitscheck.schnell();
        assertTrue(l.size() >= 3);   // Spielfenster, Skalierung, Bilder (Lizenz-Punkt entfernt)
        for (var p : l) assertNotNull(p.stufe());
    }
}
