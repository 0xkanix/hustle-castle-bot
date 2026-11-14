package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Autor 24.10.: den gesperrten Spieler am Aussehen wiedererkennen. Echte Vergleichsfenster vom 23.10. (Ausschnitt
 * x 285-905, y 400-540 aus 1159 x 770): "~ЦИРИ~" aus Myth Gods einmal mit Pfoten-Knopf (19:07:10), einmal ohne
 * (19:03:35, Banner 70 px weiter links), "Svolotsch" aus Alkatras, und ein Gegner mit nur 5 Einheiten.
 */
class ArenaSperrBilderTest {

    /** Die Schwerter (ar_power_swords) lagen bei 536/323 -- im Ausschnitt also hier. */
    static final int AX = 536 - 285, AY = 323 - 400;

    static ArenaSperrBilder.Merkmale merkmale(String name) throws Exception {
        try (var in = ArenaSperrBilderTest.class.getResourceAsStream("/sperrbilder/" + name + ".png")) {
            return ArenaSperrBilder.ausVergleich(ImageIO.read(in), AX, AY, 1.0);
        }
    }

    @Test
    void findetDiePortraetsAuchBeiFuenfEinheiten() throws Exception {
        assertEquals(6, merkmale("myth-gods-pfote").anzahl());
        assertEquals(6, merkmale("myth-gods-ohne-pfote").anzahl());
        assertEquals(6, merkmale("alkatras").anzahl());
        assertEquals(5, merkmale("fuenf-einheiten").anzahl());   // die Reihe steht 62 px weiter rechts
        assertNotNull(merkmale("myth-gods-pfote").banner());
    }

    @Test
    void derselbeSpielerZuEinerAnderenZeit() throws Exception {
        var a = merkmale("myth-gods-ohne-pfote");
        var b = merkmale("myth-gods-pfote");
        assertEquals(6, ArenaSperrBilder.gleicheEinheiten(a, b, 0));
        assertEquals(Boolean.TRUE, ArenaSperrBilder.gleichesBanner(a, b));   // Banner mit und ohne Pfoten-Knopf gefunden
    }

    @Test
    void einAndererSpielerIstNichtGleich() throws Exception {
        var myth = merkmale("myth-gods-pfote");
        var alkatras = merkmale("alkatras");
        assertTrue(ArenaSperrBilder.gleicheEinheiten(myth, alkatras, 0) <= 1);
        assertEquals(Boolean.FALSE, ArenaSperrBilder.gleichesBanner(myth, alkatras));
        assertTrue(ArenaSperrBilder.gleicheEinheiten(myth, merkmale("fuenf-einheiten"), 0) <= 1);
    }

    @Test
    void ablegenUndWiederLaden(@TempDir Path ordner) throws Exception {
        var m = merkmale("myth-gods-pfote");
        Path datei = ordner.resolve("bilder").resolve("x.png");
        ArenaSperrBilder.speichern(datei, m);
        var geladen = ArenaSperrBilder.laden(datei);
        assertNotNull(geladen);
        assertEquals(6, geladen.anzahl());
        assertEquals(6, ArenaSperrBilder.gleicheEinheiten(geladen, merkmale("myth-gods-ohne-pfote"), 0));
        assertEquals(Boolean.TRUE, ArenaSperrBilder.gleichesBanner(geladen, m));
        assertNull(ArenaSperrBilder.laden(ordner.resolve("fehlt.png")));
    }
}
