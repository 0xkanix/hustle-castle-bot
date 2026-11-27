package de.hustlebot.bot;

import de.hustlebot.core.Log;
import org.opencv.core.Mat;

import java.util.List;
import de.hustlebot.core.Sprache;

/**
 * Steht der Bot auf der Weltkarte? Und wie kommt er von dort in die Burg?
 *
 * Autor, 26.09.: Nach Leuchtturm oder Kolosseum steht der Bot auf der
 * Weltkarte -- "da, wo die ganzen Spielmodi sind" -- und soll von dort gleich
 * weitermachen, statt erst in die Burg zu laufen. Die Modi (Kathedrale,
 * Verlies, Arena) klicken ihr Ziel auf der Karte an, wenn es zu sehen ist.
 * Ist es nicht zu sehen, oder braucht der Modus die Burg (Clan-Krieg), geht
 * es mit "Home" unten links in die Burg und von dort den gewohnten Weg.
 *
 * Nur im Zweig "nichts erkannt" der Modi benutzen: Die Orte auf der Karte
 * werden im ganzen Bild gesucht, das kostet je Aufruf einige Vergleiche.
 *
 * NICHT NUR AM HOME-KNOPF ERKENNEN (Test 27.09., 01:15): Nach der
 * Code-Pruefung zaehlte nur noch worldmap_home. Autors Aufnahme stammt aber
 * aus der Kolosseum-Liste (dort 1,00) -- auf der Weltkarte selbst passt sie
 * nur zu 0,72. Folge: Karte nicht erkannt, kein Wischen zum Kolosseum und
 * zur Kathedrale. Deshalb zaehlt wieder jeder bekannte Ort auf der Karte.
 */
final class Weltkarte {

    private Weltkarte() {}

    static final String T_HOME = "worldmap_home";

    /** Orte auf der Weltkarte (und ihr "Home"), an denen sie zu erkennen ist. */
    private static final List<String> ORTE = List.of(
            T_HOME, "dc_node", "ar_node", "dungeon_node", "lh_node", "ko_tower");

    static boolean erkannt(Mat screen, BotContext ctx) {
        // Autor 21.11. (Kathedrale schneller, Vorlagen pruefen): auf der Weltkarte ist "Home" unten links immer da (gemessen
        // 0,68-1,00, alles andere in der Ecke hoechstens 0,33, siehe HOME_ECKE_AB). Fehlt er, ist es nie die Weltkarte -- dann
        // nicht alle Orte im ganzen Bild suchen. Das kostete je unerkanntem Bild ~85 ms (ar_node 26, dc_node 24, lh_node 13,
        // dungeon_node 11 ...), in der Kathedrale ~20x je Run = ~1,7 s (Nachtprotokolle 21.11., 224 Runs).
        if (!vielleichtKarte(screen, ctx)) return false;
        for (String n : ORTE) {
            if (ctx.visibleIfKnown(screen, n)) return true;
        }
        return false;
    }

    /** So oft wird die Karte auf der Suche nach einem Ort hoechstens geschoben (3 nach links, 6 nach rechts, 3 zurueck). */
    static final int ZUEGE = 12;

    /**
     * Die Karte einen Zug weiter schieben, um einen Ort zu finden, der nicht im Bild ist: erst 3 Zuege nach links
     * (der Ausschnitt wandert nach rechts), dann 6 nach rechts, dann 3 zurueck -- so ist jede Seite einmal dran.
     * Auf halber Hoehe gezogen, dort liegen keine Knoepfe.
     */
    static void schieben(BotContext ctx, int zug, String wer) {
        boolean nachLinks = zug < 3 || zug >= 9;
        int y = (int) (ctx.height() * 0.52), nah = (int) (ctx.width() * 0.24), fern = (int) (ctx.width() * 0.78);
        Log.action("Karte", Sprache.t(wer + ": dragging the world map to the " + (nachLinks ? "left" : "right") + " (swipe " + (zug + 1) + " of " + ZUEGE + ").",
                wer + ": Weltkarte nach " + (nachLinks ? "links" : "rechts") + " schieben (Zug " + (zug + 1) + " von " + ZUEGE + ")."));
        if (nachLinks) ctx.input().drag(fern, y, nah, y);
        else ctx.input().drag(nah, y, fern, y);
        ctx.sleepUntilChanged(1300);
    }

    /**
     * "Home" unten links: Vorlage worldmap_home, sonst die feste Stelle. Auf
     * der Weltkarte fuehrt es in die Burg, aus der Kolosseum-Liste auf die
     * Weltkarte (dort sitzt derselbe Knopf). Eine Stelle fuer alle --
     * Leuchtturm/Kolosseum und Kisten-Gang rufen das hier.
     */
    /**
     * Bildfehler-Suche 29./30.10. (Bericht #H4Z3, 28.10. 02:35): Die Vorlage worldmap_home schliesst den Hintergrund um
     * den runden Knopf mit ein. Gemessen an 888 Fotos des Bots (Etagenkarten, Fotoserien, Clan-Krieg): das echte "Home"
     * unten links liegt farbig bei 0,72-1,00, grau (so sucht der Bot-Faden) bei 0,68-1,00 (alle 150 Etagenkarten der
     * Kathedrale 0,724, Weltkarte mit anderem Ausschnitt 0,726), alles andere in dieser Ecke hoechstens 0,33 (grau 0,29).
     * Mit der Standardschwelle 0,84 wurden 167 von 184 Home-Knoepfen NICHT gefunden -- der Clan-Krieg stand deshalb auf
     * der Etagenkarte der Kathedrale 20 s still (#H4Z3). Drei Stellen hatten sich schon einzeln geholfen (Nahrungskisten
     * 0,65, Helden-Set feste Stelle, heim() feste Stelle); jetzt eine Regel fuer alle: nur in der Ecke unten links
     * suchen, dort reicht 0,55 (Mitte zwischen 0,33 und 0,68).
     */
    static final double HOME_ECKE_AB = 0.55;

    /** Der Home-Knopf unten links (Weltkarte, Etagenkarte, Kolosseum-Liste) -- nur in der Ecke gesucht. */
    static java.util.Optional<de.hustlebot.core.Matcher.Hit> homeUntenLinks(Mat screen, de.hustlebot.core.Matcher m) {
        if (screen == null || screen.empty() || m == null || !m.has(T_HOME)) return java.util.Optional.empty();
        int y = screen.rows() * 3 / 5;
        return m.findImBereich(screen, T_HOME, new org.opencv.core.Rect(0, y, screen.cols() / 3, screen.rows() - y), HOME_ECKE_AB);
    }

    /**
     * Kann das die Weltkarte sein? Nur, wenn "Home" unten links zu sehen ist (billig: nur die Ecke). Fehlt die Vorlage,
     * gilt wie bisher "vielleicht" -- dann entscheiden die Orte im ganzen Bild.
     */
    static boolean vielleichtKarte(Mat screen, BotContext ctx) {
        return !ctx.matcher().has(T_HOME) || homeUntenLinks(screen, ctx.matcher()).isPresent();
    }

    /** Home unten links druecken, wenn er zu sehen ist (siehe {@link #homeUntenLinks}). */
    static boolean homeUntenLinksKlicken(Mat screen, BotContext ctx, String grund) {
        java.util.Optional<de.hustlebot.core.Matcher.Hit> h = homeUntenLinks(screen, ctx.matcher());
        if (h.isEmpty()) return false;
        ctx.klick(h.get(), grund);
        return true;
    }

    static void heim(Mat screen, BotContext ctx, String wer) {
        if (homeUntenLinksKlicken(screen, ctx, wer + Sprache.t(": Home at the bottom left -- towards the castle", ": Home unten links -- Richtung Burg"))) {
            ctx.sleepUntilChanged(1500);
            return;
        }
        int x = (int) Math.round(ctx.width() * 0.070), y = (int) Math.round(ctx.height() * 0.905);
        Log.action("Klick", Sprache.t(wer + ": Home at the bottom left -- towards the castle (fixed spot)  [" + x + "," + y + "]",
                wer + ": Home unten links -- Richtung Burg (feste Stelle)  [" + x + "," + y + "]"));
        ctx.input().click(x, y, 2);
        ctx.sleepUntilChanged(1500);
    }
}
