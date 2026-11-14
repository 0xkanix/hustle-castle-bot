package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.bot.BotEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Die Mini-Leiste: eine kleine Leiste, immer obenauf, mit Zustands-Pille, kurzem Text und den
 * Knoepfen Start, Pause/Weiter und Stopp (doku/STEUERPROGRAMM.md). Per Kuerzel
 * ein- und ausblendbar; die Stelle merkt sie sich.
 *
 * Ohne Fensterrahmen, damit sie wenig Platz nimmt -- verschieben geht mit
 * gedrueckter Maus auf dem Text. Runde Ecken, wenn Windows durchsichtige Fenster kann.
 */
final class Minileiste extends JWindow {

    private final Steuerprogramm steuer;
    private final Steuereinstellungen einst;
    private final Holzstil.Pille pille = new Holzstil.Pille(true);
    private final JLabel zustand = new JLabel(" ");
    private final JButton pause = knopf("Pause", Sprache.t("Pause / Resume -- like !pause and !resume", "Pause / Weiter -- wie !pause und !weiter"), Symbol.Art.PAUSE);
    private final JButton stopp = knopf(Sprache.t("Stop", "Stopp"), Sprache.t("Stops the running mode -- like !stop", "Haelt den laufenden Modus an -- wie !stop"), Symbol.Art.STOPP);
    private final Timer takt;
    private Point zieheVon;

    Minileiste(Steuerprogramm steuer, Steuereinstellungen einst) {
        this.steuer = steuer;
        this.einst = einst;
        setAlwaysOnTop(true);
        setFocusableWindowState(false);

        boolean rund = durchsichtigMoeglich();
        if (rund) setBackground(new Color(0, 0, 0, 0));

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, Holzstil.LUFT_KLEIN, 6)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Holzstil.weich(g);
                g2.setPaint(new GradientPaint(0, 0, Holzstil.betont(Holzstil.KOPF, 0.07), 0, getHeight(), Holzstil.KOPF));
                if (rund) g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                else g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Holzstil.betont(Holzstil.KOPF, 0.25));
                if (rund) g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                else g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));

        zustand.setFont(Holzstil.NORMAL);
        zustand.setForeground(Holzstil.leiseAufKopf());
        zustand.setPreferredSize(new Dimension(220, 24));
        zustand.setToolTipText(Sprache.t("Drag with the mouse. Double-click: control window.", "Mit gedrueckter Maus verschieben. Doppelklick: Steuerprogramm."));

        JButton start = knopf("Start", Sprache.t("Opens the control window with the start menu", "Oeffnet das Steuerprogramm mit dem Start-Menue"), Symbol.Art.START);
        start.addActionListener(e -> steuer.steuerfensterZeigen());
        pause.addActionListener(e -> steuer.senden(
                steuer.zustand() == BotEngine.State.PAUSIERT ? Befehlsbau.WEITER : Befehlsbau.PAUSE, Sprache.t("Mini bar", "Mini-Leiste")));
        stopp.addActionListener(e -> steuer.senden(Befehlsbau.STOP, Sprache.t("Mini bar", "Mini-Leiste")));
        JButton zu = Holzstil.symbolKnopf(Symbol.Art.KREUZ, Sprache.t("Hide the bar (shortcut or control window bring it back)", "Leiste ausblenden (Kuerzel oder Steuerprogramm holen sie wieder)"));
        dunkelKnopf(zu);
        zu.addActionListener(e -> steuer.umschalten(Tastenkuerzel.Aktion.MINILEISTE));

        p.add(pille);
        p.add(zustand);
        p.add(start);
        // Autor 14.10.: Pause/Stopp nur per Taste (F10 / Not-Aus-Taste) -- die Leiste zeigt nur den Zustand.
        p.add(zu);
        setContentPane(p);
        pack();

        MouseAdapter ziehen = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                zieheVon = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (zieheVon == null) return;
                Point hier = getLocation();
                setLocation(hier.x + e.getX() - zieheVon.x, hier.y + e.getY() - zieheVon.y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                zieheVon = null;
                einst.setzen(Steuereinstellungen.MINILEISTE_X, getX());
                einst.setzen(Steuereinstellungen.MINILEISTE_Y, getY());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) steuer.steuerfensterZeigen();
            }
        };
        for (Component c : new Component[]{zustand, pille, p}) {
            c.addMouseListener(ziehen);
            c.addMouseMotionListener(ziehen);
        }

        int x = einst.zahl(Steuereinstellungen.MINILEISTE_X, -1);
        int y = einst.zahl(Steuereinstellungen.MINILEISTE_Y, -1);
        if (x >= 0 && y >= 0) setLocation(x, y);
        else Holzstil.obenMitte(this);

        takt = new Timer(1000, e -> aktualisieren());
        aktualisieren();
    }

    private static boolean durchsichtigMoeglich() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ein kleiner Knopf auf dunklem Holz. */
    private static JButton knopf(String text, String hinweis, Symbol.Art symbol) {
        JButton b = Holzstil.kleinerKnopf(text, hinweis, symbol);
        dunkelKnopf(b);
        return b;
    }

    private static void dunkelKnopf(JButton b) {
        b.setBackground(Holzstil.betont(Holzstil.KOPF, 0.16));
        b.setForeground(Holzstil.aufKopf());
    }

    private void aktualisieren() {
        BotEngine.State z = steuer.zustand();
        pille.setzen(z);
        String details = steuer.statusText();
        String text = details == null || details.isBlank() || details.trim().equalsIgnoreCase(Sprache.t("Ready.", "Bereit."))
                ? "Hustle Castle Bot" : details;
        zustand.setText(text.length() > 30 ? text.substring(0, 28) + "…" : text);
        zustand.setToolTipText(details == null || details.isBlank() ? Sprache.t("Drag with the mouse.", "Mit gedrueckter Maus verschieben.") : details);
        boolean laeuft = z == BotEngine.State.LAEUFT || z == BotEngine.State.PAUSIERT;
        pause.setEnabled(laeuft);
        stopp.setEnabled(laeuft);
        pause.setText(z == BotEngine.State.PAUSIERT ? Sprache.t("Resume", "Weiter") : "Pause");
        pause.setIcon(new Symbol(z == BotEngine.State.PAUSIERT ? Symbol.Art.WEITER : Symbol.Art.PAUSE, 12));
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b) takt.start();
        else takt.stop();
    }
}
