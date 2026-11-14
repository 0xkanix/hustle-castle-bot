package de.hustlebot.bot;

import de.hustlebot.core.Texterkennung;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Die Truppe ist leer: das Spiel zeigt "Move your troops to the Barracks!" und startet keinen Kampf und keine Reise.
 *
 * Bericht UZ4S (ben, 28.10.; Foto im Bericht): Im Leuchtturm standen neben dem Helden fuenf leere Platze ("?"), und der Bot
 * drueckte endlos "Quick Attack". Auch die Kathedrale klickte 27.10. ueber drei Stunden "Participate" ins Leere. Die Einheiten
 * waren vermutlich im Clan-Krieg unterwegs. Der Text ist gross und rot -- die Windows-Texterkennung liest ihn auf dem ganzen
 * Bild ("Move your Barracks!" bis "Move your troops to the Barracks!").
 */
final class LeereTruppe {

    private LeereTruppe() {}

    /** Steht im gelesenen Text die Meldung? "Barracks" allein genuegt nicht (Raumname in der Burg), dazu "move" oder "troop". */
    static boolean ausText(String gelesen) {
        if (gelesen == null) return false;
        String t = gelesen.toLowerCase(Locale.ROOT);
        return t.contains("barracks") && (t.contains("mov") || t.contains("troop"));
    }

    /** Liest das aktuelle Bild (ca. 0,3 s) -- nur aufrufen, wenn etwas haengt. */
    static boolean sichtbar(BotContext ctx) {
        try {
            BufferedImage bild = ctx.frame();
            if (bild == null || !Texterkennung.istDa()) return false;
            return ausText(String.join(" ", Texterkennung.lies(bild)));
        } catch (Exception e) {
            return false;
        }
    }
}
