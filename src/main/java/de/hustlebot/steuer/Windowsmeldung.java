package de.hustlebot.steuer;

import de.hustlebot.core.Sprache;

import de.hustlebot.core.Log;
import de.hustlebot.discord.DiscordClient;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Windows-Meldungen unten rechts (Infobereich der Taskleiste) -- fuer Leute ohne
 * Discord: Fehler, "fertig" (doku/STEUERPROGRAMM.md).
 *
 * Das kleine Symbol im Infobereich hat ein Menue: Steuerprogramm zeigen, Bot
 * beenden. Gibt es keinen Infobereich (anderes System), passiert einfach nichts.
 */
public final class Windowsmeldung {

    private TrayIcon symbol;
    private final Runnable beiZeigen;
    private final Runnable beiBeenden;

    public Windowsmeldung(Runnable beiZeigen, Runnable beiBeenden) {
        this.beiZeigen = beiZeigen;
        this.beiBeenden = beiBeenden;
    }

    /** Legt das Symbol in den Infobereich. Liefert false, wenn das System keinen hat. */
    public boolean einrichten() {
        if (!SystemTray.isSupported()) {
            Log.info("Steuerprogramm", Sprache.t("No notification area -- Windows notifications are off.",
                    "Kein Infobereich -- Windows-Meldungen sind aus."));
            return false;
        }
        try {
            PopupMenu menue = new PopupMenu();
            MenuItem zeigen = new MenuItem(Sprache.t("Show control window", "Steuerprogramm zeigen"));
            zeigen.addActionListener(e -> beiZeigen.run());
            MenuItem beenden = new MenuItem(Sprache.t("Quit bot", "Bot beenden"));
            beenden.addActionListener(e -> beiBeenden.run());
            menue.add(zeigen);
            menue.addSeparator();
            menue.add(beenden);

            symbol = new TrayIcon(bild(), "Hustle Castle Bot", menue);
            symbol.setImageAutoSize(true);
            symbol.addActionListener(e -> beiZeigen.run());   // Doppelklick
            SystemTray.getSystemTray().add(symbol);
            return true;
        } catch (Exception e) {
            Log.warn("Steuerprogramm", Sprache.t("Icon in the notification area failed: " + e.getMessage(),
                    "Symbol im Infobereich ging nicht: " + e.getMessage()));
            symbol = null;
            return false;
        }
    }

    public boolean bereit() {
        return symbol != null;
    }

    /** Eine Meldung unten rechts. Art nach Kartenfarbe: Fehler rot, Warnung gelb, sonst Info. */
    public void zeigen(String titel, String text, int farbe) {
        TrayIcon s = symbol;
        if (s == null) return;
        TrayIcon.MessageType art = switch (farbe) {
            case DiscordClient.FARBE_FEHLER -> TrayIcon.MessageType.ERROR;
            case DiscordClient.FARBE_WARNUNG -> TrayIcon.MessageType.WARNING;
            default -> TrayIcon.MessageType.INFO;
        };
        String t = Holzstil.ohneMarkdown(titel);
        String x = Holzstil.ohneMarkdown(text);
        if (x.length() > 240) x = x.substring(0, 237) + "…";
        try {
            s.displayMessage(t.isEmpty() ? "Hustle Castle Bot" : t, x, art);
        } catch (Exception e) {
            Log.debug("Steuerprogramm", Sprache.t("Windows notification failed: " + e.getMessage(),
                    "Windows-Meldung ging nicht: " + e.getMessage()));
        }
    }

    /** Kurzer Hinweis-Text neben dem Symbol (beim Zeigen mit der Maus). */
    public void hinweis(String text) {
        TrayIcon s = symbol;
        if (s != null) s.setToolTip("Hustle Castle Bot – " + text);
    }

    public void entfernen() {
        TrayIcon s = symbol;
        symbol = null;
        if (s != null) {
            try {
                SystemTray.getSystemTray().remove(s);
            } catch (Exception ignored) {
                // war schon weg
            }
        }
    }

    /** Ein kleines Holz-Quadrat mit "H" -- kein Bild noetig, kein Pfad, der fehlen koennte. */
    private static Image bild() {
        int n = 16;
        BufferedImage b = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Holzstil.KOPF);
        g.fillRoundRect(0, 0, n, n, 4, 4);
        g.setColor(Holzstil.HELL);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("H", 4, 13);
        g.dispose();
        return b;
    }
}
