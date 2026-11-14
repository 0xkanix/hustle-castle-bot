package de.hustlebot.bot;

import de.hustlebot.core.Log;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;

/**
 * Warten, bis der Chat fertig ausgefahren ist.
 *
 * Der Chat faehrt mit einer Animation von links herein. Wer zu frueh
 * fotografiert, erwischt ihn halb draussen: Die Nachrichten stehen dann noch
 * ein Stueck weiter links als gleich danach, und auf dem Bild fehlt ihre
 * linke Haelfte.
 *
 * Gebraucht ueberall, wo der Bot den Chat aufmacht -- fuer die Einladungen
 * von Freunden (FreundesChat), im Hauptbot auch zum Vorlesen und Schreiben
 * (ChatBedienung). Frueher hatte jede Stelle ihr eigenes Warten: die eine
 * schaute hin, bis das Bild still stand, die andere wartete fest 400
 * Millisekunden. Die feste Zeit war das schlechtere von beiden -- war der
 * Chat dann noch unterwegs, suchte der Bot den Namen des Freundes an der
 * falschen Stelle und erkannte die Einladung nicht.
 */
final class ChatAnimation {

    private ChatAnimation() {
    }

    /**
     * Wartet, bis sich auf dem Bildschirm nichts mehr bewegt, und liefert ein
     * frisches Bild.
     *
     * Bewusst nicht "warte zwei Sekunden": Wie lange eine Animation braucht,
     * haengt vom Rechner und von der Bildrate ab. Eine feste Zahl ist
     * entweder zu kurz -- dann ist das Bild halb fertig -- oder unnoetig
     * lang. Hier wird stattdessen hingesehen: Sind zwei Aufnahmen
     * hintereinander gleich, steht das Bild.
     */
    static Mat warteBisRuhig(BotContext ctx, long hoechstensMs) {
        // Erst einen Moment, damit die Animation ueberhaupt anlaeuft. Sonst
        // gelten die zwei Bilder von VOR der Bewegung als "steht schon".
        ctx.sleep(700);

        long ende = System.currentTimeMillis() + hoechstensMs;
        BufferedImage vorher = null;
        int ruhig = 0;

        while (System.currentTimeMillis() < ende) {
            ctx.refresh();
            BufferedImage jetzt = ctx.frame();
            if (jetzt == null) return ctx.refresh();

            if (vorher != null && gleicht(vorher, jetzt)) {
                if (++ruhig >= 2) {
                    Log.debug("Chat", "Das Bild steht still, es wird fotografiert.");
                    return ctx.refresh();
                }
            } else {
                ruhig = 0;
            }
            vorher = jetzt;
            ctx.sleep(250);
        }
        Log.debug("Chat", "Das Bild kam nicht zur Ruhe, es wird trotzdem fotografiert.");
        return ctx.refresh();
    }

    /**
     * Sind zwei Aufnahmen praktisch gleich?
     *
     * Es wird nur ein Raster von Punkten verglichen, nicht jedes einzelne --
     * das genuegt, um eine fahrende Animation zu erkennen, und kostet fast
     * nichts. Kleine Unterschiede sind erlaubt: Im Spiel flackern staendig
     * Fackeln und Figuren, davon soll das Warten nicht endlos werden.
     */
    private static boolean gleicht(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;

        // Nur die linke Haelfte: dort faehrt der Chat aus. Rechts steht die
        // Burg mit ihren Fackeln, die flackern immer.
        int bisX = a.getWidth() / 2;
        long summe = 0;
        int punkte = 0;

        for (int y = 0; y < a.getHeight(); y += 8) {
            for (int x = 0; x < bisX; x += 8) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                summe += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                summe += Math.abs(((pa >> 8)  & 0xFF) - ((pb >> 8)  & 0xFF));
                summe += Math.abs((pa & 0xFF) - (pb & 0xFF));
                punkte += 3;
            }
        }
        return punkte == 0 || (summe / (double) punkte) < 2.0;
    }
}
