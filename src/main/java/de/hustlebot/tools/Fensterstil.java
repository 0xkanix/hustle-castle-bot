package de.hustlebot.tools;

import javax.swing.*;
import java.awt.*;

/**
 * Aussehen der beiden Begleitfenster an einer Stelle.
 *
 * Es gibt zwei davon -- das Mitschau-Fenster und den Notizblock --, und
 * sie sollen nebeneinander wie ein Stueck wirken. Zwei Kopien derselben
 * Farbwerte waeren nach der ersten Aenderung auseinandergelaufen.
 *
 * Die Farben sind bewusst weich. Fast schwarz mit hellgrauer Schrift hat
 * den staerksten Kontrast, den ein Bildschirm kann, und genau das
 * ermuedet, wenn man stundenlang danebensitzt. Autor dazu: "aktuell tut
 * es in den augen weh das anzuschauen."
 */
final class Fensterstil {

    static final Color GRUND     = new Color(0x21, 0x27, 0x2C);
    static final Color KOPFGRUND = new Color(0x2A, 0x32, 0x38);
    static final Color TEXT      = new Color(0xDC, 0xE4, 0xEA);
    static final Color ZEIT      = new Color(0x5A, 0x6B, 0x75);
    static final Color BEREICH   = new Color(0x8F, 0xA3, 0xAE);
    static final Color KLICK     = new Color(0x86, 0xD9, 0xA0);
    static final Color WARNUNG   = new Color(0xF2, 0xC0, 0x69);
    static final Color FEHLER    = new Color(0xFF, 0x8F, 0x8F);
    static final Color LEISE     = new Color(0x5F, 0x6E, 0x78);

    static final Font SCHRIFT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    static final Font KLEIN   = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    static final Font GROSS   = new Font(Font.SANS_SERIF, Font.BOLD, 15);

    private Fensterstil() {
    }

    /** Ein Kaestchen im gemeinsamen Stil. */
    static JCheckBox kaestchen(String text, boolean an, String hinweis) {
        JCheckBox b = new JCheckBox(text, an);
        b.setBackground(KOPFGRUND);
        b.setForeground(BEREICH);
        b.setFont(KLEIN);
        b.setToolTipText(hinweis);
        return b;
    }

    /** Ein farbiger Punkt mit Beschriftung, fuer die Legende. */
    static JLabel punkt(String text, Color farbe) {
        JLabel l = new JLabel("● " + text);
        l.setForeground(farbe);
        l.setFont(KLEIN);
        return l;
    }

    /** Wie weit das aeusserste Fenster vom Bildschirmrand wegbleibt. */
    static final int RAND = 20;

    /** Breite des Mitschau-Fensters. Der Notizblock stellt sich links daneben. */
    static final int BREITE_LOG = 560;

    /** Breite des Notizblocks. Schmaler, weil dort nur eine kurze Liste steht. */
    static final int BREITE_NOTIZEN = 400;

    /** Luft zwischen den beiden Fenstern. */
    static final int LUECKE = 12;

    /**
     * Stellt ein Fenster an den rechten Bildschirmrand.
     *
     * @param abstandRechts wie viele Bildpunkte rechts frei bleiben. Das
     *                      aeussere Fenster nimmt RAND, das daneben
     *                      zusaetzlich die Breite des aeusseren plus die
     *                      Luecke. In Bildpunkten statt in "Fensterbreiten",
     *                      weil die beiden Fenster verschieden breit sind --
     *                      mit Vielfachen der eigenen Breite haetten sie
     *                      sich ueberlappt.
     */
    static void anDenRand(Window w, int breite, int maxHoehe, int abstandRechts) {
        Rectangle schirm = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int hoehe = Math.min(maxHoehe, schirm.height - 60);
        w.setSize(breite, hoehe);
        int x = schirm.x + schirm.width - breite - abstandRechts;
        w.setLocation(Math.max(schirm.x, x), schirm.y + 20);
    }
}
