package de.hustlebot.bot;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** Spieler-ID im Profilfenster (Autor 21.11.). Das Testbild ist nur Beispiel -- im Bot steht keine ID fest. */
class SpielerProfilTest {

    private static BufferedImage bild(String name) throws Exception {
        try (var in = SpielerProfilTest.class.getResourceAsStream(name)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(in != null, "Testbild ist privat und fehlt in der offenen Fassung");
            return ImageIO.read(in);
        }
    }

    /** Der linke Teil des Profilfensters in einer Spielflaeche 1159 x 770 (Rest schwarz). */
    private static BufferedImage profil() throws Exception {
        BufferedImage teil = bild("/konto/profil-links.png");
        BufferedImage ganz = new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB);
        ganz.getGraphics().drawImage(teil, 0, 0, null);
        return ganz;
    }

    @Test
    void ziffernreiheOhneIdDavorUndKopierSymbolDahinter() throws Exception {
        int[] r = SpielerProfil.ziffernreihe(profil());
        assertNotNull(r);
        assertEquals(8, r[4], "acht Ziffern");
        // "id:" endet bei x ~103, die Ziffern reichen bis ~210, das Kopier-Symbol beginnt bei ~223
        assertTrue(r[0] >= 108 && r[0] <= 120, "links " + r[0]);
        assertTrue(r[0] + r[2] >= 205 && r[0] + r[2] <= 216, "rechts " + (r[0] + r[2]));
        assertTrue(r[1] >= 155 && r[1] <= 165, "oben " + r[1]);
    }

    @Test
    void ohneProfilKeineIdReihe() throws Exception {
        assertNull(SpielerProfil.ziffernreihe(new BufferedImage(1159, 770, BufferedImage.TYPE_INT_RGB)));
        assertNull(SpielerProfil.ziffernreihe(null));
    }

    /** Autor 22.11.: die ID wirklich lesen (Windows-Texterkennung) -- ohne Erkennung (z. B. Linux) wird der Test uebersprungen. */
    @Test
    void idUndNameMitTexterkennung() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(de.hustlebot.core.Texterkennung.istDa());
        BufferedImage b = profil();
        assertEquals("50017349", SpielerProfil.id(b));
        // Der echte Name im Testbild steht nicht im Code (offene Fassung) -- gelesen werden muss aber ein Name.
        String name = SpielerProfil.name(b);
        assertTrue(name != null && name.strip().length() >= 3, "Name im Profil: " + name);
    }

    @Test
    void idNurMitPassenderZiffernzahl() {
        assertEquals("50017349", SpielerProfil.idAusText("50017349", 8));
        assertEquals("50017349", SpielerProfil.idAusText("5OO17349", 8));
        assertEquals("50017349", SpielerProfil.idAusText(" 5001 7349 ", 8));
        assertEquals("", SpielerProfil.idAusText("5001734", 8), "eine Ziffer verschluckt");
        assertEquals("", SpielerProfil.idAusText("50017349x", 8), "fremdes Zeichen");
        assertEquals("", SpielerProfil.idAusText(null, 8));
    }
}
