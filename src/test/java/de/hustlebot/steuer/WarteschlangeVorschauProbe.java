package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Malt das Fenster "Warteschlange" (Autor 15.11.) mit Beispielzeilen in ein PNG -- so sieht Autor es, ohne den Bot zu starten.
 *
 * Nur auf Wunsch und mit Bildschirm:
 *   mvn -o test -Dtest=WarteschlangeVorschauProbe -Dwarteschlange.bild=C:\...\warteschlange.png [-Dwarteschlange.sprache=de]
 * Die Sprache gilt fuer den ganzen Lauf (die Modus-Namen werden beim ersten Laden festgelegt) -- je Sprache ein Lauf.
 */
class WarteschlangeVorschauProbe {

    @Test
    void bildMalen() throws Exception {
        String ziel = System.getProperty("warteschlange.bild", "");
        Assumptions.assumeFalse(ziel.isBlank(), "nur auf Wunsch (-Dwarteschlange.bild=...)");
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm");
        // Mit -Dtest laeuft auch die zweite Ausfuehrung (Arbeitsordner target/speicher-pruefung) -- sie wuerde das Bild mit
        // ihren Test-Schaltern ueberschreiben.
        Assumptions.assumeFalse(Path.of("").toAbsolutePath().toString().contains("speicher-pruefung"), "nur im Projektordner");
        Sprache.set("de".equalsIgnoreCase(System.getProperty("warteschlange.sprache", "en")) ? Sprache.Lang.DE : Sprache.Lang.EN);
        Path ordner = Files.createTempDirectory("warteschlange-vorschau");
        Steuerprogramm steuer = Steuerprogramm.vorschau(ordner);
        steuer.profile.setzen(Sprache.t("Night", "Nacht"), List.of("!queue start cathedral 100 c"));

        SwingUtilities.invokeAndWait(() -> {
            try {
                Warteschlangenfenster f = new Warteschlangenfenster(null, steuer, null);
                f.laden(Warteschlange.lesen(List.of(
                        "!settings kolosseum aus",
                        "!ende 06:30",
                        "!queue start portal bis 135 farm 80:2mio,135:1,5mio | cathedral 50 c food noevent | dungeon 10 bob,alice"
                                + " | stigmas loeschen | event hammer")));
                f.addNotify();
                f.validate();
                // Fuers Bild ganz aufziehen: so hoch, dass der Rollbereich alles zeigt.
                JScrollPane rollen = rollbereich(f.getContentPane());
                if (rollen != null) {
                    int fehlt = rollen.getViewport().getView().getPreferredSize().height - rollen.getViewport().getExtentSize().height;
                    if (fehlt > 0) f.setSize(f.getWidth(), f.getHeight() + fehlt + 2);
                    f.validate();
                }
                schreiben(f, Path.of(ziel), Sprache.t("Queue", "Warteschlange"));
                f.dispose();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static JScrollPane rollbereich(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JScrollPane s) return s;
            if (k instanceof Container inner) {
                JScrollPane s = rollbereich(inner);
                if (s != null) return s;
            }
        }
        return null;
    }

    /** Wie SteuerVorschauProbe: mit einer kleinen Titelleiste in ein PNG. */
    private static void schreiben(Window f, Path datei, String titel) throws Exception {
        JRootPane wurzel = ((RootPaneContainer) f).getRootPane();
        int w = wurzel.getWidth(), h = wurzel.getHeight(), leiste = 30;
        BufferedImage b = new BufferedImage(w, h + leiste, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xE6, 0xE6, 0xE6));
        g.fillRect(0, 0, w, leiste);
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(titel, 12, 20);
        g.drawString("–    ▢    ✕", w - 90, 20);
        g.translate(0, leiste);
        wurzel.print(g);
        g.dispose();
        Files.createDirectories(datei.toAbsolutePath().getParent());
        ImageIO.write(b, "png", datei.toFile());
    }
}
