package de.hustlebot;

import org.junit.jupiter.api.Test;

import javax.swing.JPasswordField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Autor 03.11.: auf Pumas Bildschirmfoto des Einrichtungsfensters stand sein Bot-Token lesbar -- jetzt nur Punkte. */
class EinrichtungTokenTest {

    @Test
    void tokenStehtNurAlsPunkteDa() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path d = Files.createTempFile("einrichtung", ".properties");
        Properties p = new Properties();
        p.setProperty(Einrichtung.K_TOKEN, "KEIN.ECHTER.TOKEN-nur-ein-Testwert-0000000000000000000000000000000");
        Einrichtung.Fenster f = new Einrichtung.Fenster(d, p, "");
        assertInstanceOf(JPasswordField.class, f.token);
        assertNotEquals(0, f.token.getEchoChar(), "ohne Punkte waere der Token wieder lesbar");
        assertEquals(p.getProperty(Einrichtung.K_TOKEN), new String(f.token.getPassword()));
    }
}
