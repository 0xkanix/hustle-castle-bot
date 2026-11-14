package de.hustlebot.steuer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Malt den Reiter Einstellungen, nachdem ein paar Knoepfe gedrueckt wurden -- zum Nachsehen, ob die Rueckmeldung
 * unter der Zeile wirklich erscheint (15.10.: sie war unsichtbar). Ohne Bot (Vorschau), deshalb steht dort
 * "Vorschau ohne Bot", im Betrieb "Geklappt"/"Nicht geklappt".
 *
 * Nur auf Wunsch: mvn test -Dtest=RueckmeldungProbe -Dsteuer.vorschau=1 (Bild nach -Dsteuer.vorschau.ordner)
 */
class RueckmeldungProbe {

    @Test
    void knoepfeDrueckenUndMalen() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getProperty("steuer.vorschau")), "nur auf Wunsch (-Dsteuer.vorschau=1)");
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm");
        Path ziel = Path.of(System.getProperty("steuer.vorschau.ordner", "doku/steuerprogramm-bilder"));
        Files.createDirectories(ziel);
        Path ordner = Files.createTempDirectory("steuer-rueck");
        Steuerprogramm steuer = Steuerprogramm.vorschau(ordner);
        Steuerfenster[] f = new Steuerfenster[1];
        SwingUtilities.invokeAndWait(() -> {
            steuer.fensterBauen();
            try {
                var feld = steuer.getClass().getDeclaredField("steuerfenster");
                feld.setAccessible(true);
                f[0] = (Steuerfenster) feld.get(steuer);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            f[0].setSize(765, 800);
            f[0].addNotify();
            f[0].reiterWaehlen(2);
            f[0].validate();
            int ja = 0, an = 0, stand = 0;
            for (JButton b : alle(f[0].getContentPane(), JButton.class)) {
                String t = b.getText();
                if ("Ja".equals(t) && ja++ == 0) b.doClick();
                if ("An".equals(t) && an++ == 1) b.doClick();
                if ("Stand".equals(t) && stand++ == 3) b.doClick();
            }
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> { });   // die Rueckrufe (invokeLater) abarbeiten
        SwingUtilities.invokeAndWait(() -> {
            f[0].validate();
            BufferedImage b = new BufferedImage(f[0].getWidth(), f[0].getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            f[0].getRootPane().print(g);
            g.dispose();
            try {
                ImageIO.write(b, "png", ziel.resolve("einstellungen-rueckmeldung.png").toFile());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (Window w : Window.getWindows()) w.dispose();
        });
    }

    private static <T extends Component> List<T> alle(Container c, Class<T> art) {
        List<T> l = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (art.isInstance(k)) l.add(art.cast(k));
            if (k instanceof Container ck) l.addAll(alle(ck, art));
        }
        return l;
    }
}
