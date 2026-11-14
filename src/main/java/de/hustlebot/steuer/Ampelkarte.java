package de.hustlebot.steuer;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.steuer.Gesundheitscheck.Punkt;
import de.hustlebot.steuer.Gesundheitscheck.Stufe;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Autor 24.10. (Ideenliste F33): Die Karte "Selbstcheck" auf der Steuerung -- je Punkt ein Farbpunkt (gruen / gelb / rot), ein Satz
 * und bei Bedarf "Was tun?" mit der Erklaerung und, wo es einen gibt, einem Knopf, der die richtige Windows-Seite oeffnet.
 *
 * Gemessen wird in einem eigenen Faden (nie im Fenster-Faden): 6 Sekunden nach dem Aufbau die schnellen Punkte, auf Knopfdruck
 * ("Jetzt pruefen") zusaetzlich Virenschutz und Energiesparplan, die Windows fragen muessen.
 */
final class Ampelkarte extends JPanel {

    private static final String[][] PUNKTE = {
            {"fenster", "Game window", "Spielfenster"}, {"groesse", "Window size", "Fenstergröße"},
            {"skalierung", "Windows scaling", "Windows-Skalierung"}, {"viren", "Virus protection", "Virenschutz"},
            {"energie", "Power plan", "Energiesparplan"}, {"bilder", "Your own pictures", "Deine eigenen Bilder"}};

    private final Map<String, JLabel> zeilen = new LinkedHashMap<>();
    private final Map<String, JButton> hilfeKnoepfe = new LinkedHashMap<>();
    private final Map<String, Punkt> stand = new LinkedHashMap<>();
    private final JLabel ueberblick = Holzstil.hinweis(" ");
    private final Runnable aufnahmeProgramm;
    private boolean laeuft;

    /** @param aufnahmeProgramm startet das Aufnahme-Programm (fuer "Deine eigenen Bilder") */
    Ampelkarte(Runnable aufnahmeProgramm) {
        super(new BorderLayout(0, Holzstil.LUFT_KLEIN));
        this.aufnahmeProgramm = aufnahmeProgramm;
        setOpaque(false);
        setBorder(Holzstil.bereich(Sprache.t("Check-up before you start", "Selbstcheck vor dem Start")));

        JPanel liste = Holzstil.flaeche(new GridLayout(0, 1, 0, 2));
        for (String[] p : PUNKTE) {
            JPanel z = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
            JLabel l = new JLabel(Sprache.t(p[1], p[2]) + ": " + Sprache.t("not checked yet", "noch nicht geprüft"), new Symbol(Symbol.Art.PUNKT, 12), JLabel.LEFT);
            l.setFont(Holzstil.NORMAL);
            l.setForeground(Holzstil.LEISE);
            l.setIconTextGap(6);
            JButton h = Holzstil.kleinerKnopf(Sprache.t("What to do?", "Was tun?"), Sprache.t("Explains the problem and what you can do.", "Erklärt das Problem und was du tun kannst."));
            h.setVisible(false);
            String id = p[0];
            h.addActionListener(e -> hilfeZeigen(id));
            zeilen.put(id, l);
            hilfeKnoepfe.put(id, h);
            z.add(l);
            z.add(h);
            // Kein Zeilentext darf das Fenster breiter machen, als es ist: notfalls wird er abgeschnitten (der Tooltip hat alles).
            l.setMinimumSize(new java.awt.Dimension(40, l.getPreferredSize().height));
            z.setMinimumSize(new java.awt.Dimension(40, z.getPreferredSize().height));
            liste.add(z);
        }
        JButton jetzt = Holzstil.kleinerKnopf(Sprache.t("Check now", "Jetzt prüfen"),
                Sprache.t("Looks again, including virus protection and power plan (takes a moment). A wrong game window size is set right away.",
                        "Prüft noch einmal, auch Virenschutz und Energiesparplan (dauert einen Moment). Eine falsche Spielfenster-Größe wird gleich richtig eingestellt."), Symbol.Art.NEU);
        jetzt.addActionListener(e -> pruefen(true));
        JPanel unten = Holzstil.flaeche(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 0));
        unten.add(jetzt);
        unten.add(ueberblick);
        add(liste, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);

        // 6 Sekunden nach dem Aufbau die schnellen Punkte -- nie waehrend das Fenster gebaut wird, nie im Fenster-Faden
        Timer erster = new Timer(6000, e -> pruefen(false));
        erster.setRepeats(false);
        erster.start();
    }

    /** Misst im Hintergrund; {@code voll} nimmt Virenschutz und Energiesparplan dazu. */
    void pruefen(boolean voll) {
        if (laeuft) return;
        laeuft = true;
        ueberblick.setText(Sprache.t("checking …", "prüfe …"));
        Thread t = new Thread(() -> {
            List<Punkt> l = new ArrayList<>();
            try {
                l.addAll(Gesundheitscheck.schnell());
                if (voll) {
                    // Autor 01.11.: falsche Fenstergroesse nicht erklaeren, sondern gleich richten und neu messen.
                    if (l.stream().anyMatch(p -> "groesse".equals(p.id()) && p.stufe() == Stufe.ROT)) {
                        Gesundheitscheck.groesseRichten();
                        l.clear();
                        l.addAll(Gesundheitscheck.schnell());
                    }
                    l.add(Gesundheitscheck.virenschutz());
                    l.add(Gesundheitscheck.energieplan());
                }
            } catch (Throwable e) {
                Log.debug("Steuerprogramm", "Selbstcheck: " + e);
            }
            SwingUtilities.invokeLater(() -> {
                for (Punkt p : l) setzen(p);
                laeuft = false;
                ueberblickNeu();
            });
        }, "selbstcheck");
        t.setDaemon(true);
        t.start();
    }

    /** Einen Punkt zeigen (nur im Fenster-Faden). */
    void setzen(Punkt p) {
        JLabel l = zeilen.get(p.id());
        if (l == null) return;
        stand.put(p.id(), p);
        String text = p.titel() + ": " + p.befund();
        l.setText(text.length() > 72 ? text.substring(0, 71) + "…" : text);
        l.setForeground(switch (p.stufe()) {
            case GRUEN -> Holzstil.GUT;
            case GELB -> Holzstil.WARNUNG;
            case ROT -> Holzstil.FEHLER;
            case GRAU -> Holzstil.LEISE;
        });
        l.setToolTipText("<html><div style='width:360px'>" + (text.length() > 72 ? text + "<br><br>" : "") + (p.hilfe() == null ? "" : p.hilfe()) + "</div></html>");
        hilfeKnoepfe.get(p.id()).setVisible(p.hilfe() != null);
        revalidate();
    }

    private void ueberblickNeu() {
        int gelb = 0, rot = 0;
        for (Punkt p : stand.values()) {
            if (p.stufe() == Stufe.GELB) gelb++;
            if (p.stufe() == Stufe.ROT) rot++;
        }
        ueberblick.setText(rot > 0 ? Sprache.t(rot + " problem(s) -- fix them before you start", rot + " Problem(e) -- erst beheben, dann starten")
                : gelb > 0 ? Sprache.t(gelb + " hint(s) -- the bot can still run", gelb + " Hinweis(e) -- der Bot kann trotzdem laufen")
                : Sprache.t("all good", "alles in Ordnung"));
        ueberblick.setForeground(rot > 0 ? Holzstil.FEHLER : gelb > 0 ? Holzstil.WARNUNG : Holzstil.GUT);
    }

    /** "Was tun?": die Erklaerung, bei manchen Punkten mit einem Knopf, der die passende Windows-Seite oder das Aufnahme-Programm oeffnet. */
    private void hilfeZeigen(String id) {
        Punkt p = stand.get(id);
        if (p == null || p.hilfe() == null) return;
        String text = "<html><div style='width:400px'><b>" + p.titel() + ":</b> " + p.befund() + "<br><br>" + p.hilfe() + "</div></html>";
        String oeffnen = switch (id) {
            case "groesse", "skalierung" -> Sprache.t("Open display settings", "Anzeige-Einstellungen öffnen");
            case "energie" -> Sprache.t("Open power settings", "Energie-Einstellungen öffnen");
            case "bilder" -> Sprache.t("Open capture program", "Aufnahme-Programm öffnen");
            default -> null;
        };
        Component eltern = SwingUtilities.getWindowAncestor(this);
        if (oeffnen == null) {
            JOptionPane.showMessageDialog(eltern, text, p.titel(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] knoepfe = {oeffnen, Sprache.t("Close", "Schließen")};
        int a = JOptionPane.showOptionDialog(eltern, text, p.titel(), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, knoepfe, knoepfe[1]);
        if (a != 0) return;
        switch (id) {
            case "groesse", "skalierung" -> windowsSeite("ms-settings:display");
            case "energie" -> windowsSeite("ms-settings:powersleep");
            case "bilder" -> aufnahmeProgramm.run();
            default -> { }
        }
    }

    /** Oeffnet eine Seite der Windows-Einstellungen (nur anzeigen -- am PC aendert das Programm nichts). */
    private static void windowsSeite(String adresse) {
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", adresse).start();
        } catch (Exception e) {
            Log.debug("Steuerprogramm", "Windows-Seite nicht geoeffnet: " + e.getMessage());
        }
    }

    /** Fuer Tests und die Vorschau: der Stand eines Punktes. */
    Punkt stand(String id) {
        return stand.get(id);
    }

    Color farbeVon(String id) {
        JLabel l = zeilen.get(id);
        return l == null ? null : l.getForeground();
    }
}
