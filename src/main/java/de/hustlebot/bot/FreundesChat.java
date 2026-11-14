package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import de.hustlebot.core.Sprache;

/**
 * Einladungen von Freunden im Chat des Spiels.
 *
 * Laedt ein Freund zu einer Reise ins Verlies ein, sieht man das NICHT auf
 * dem Teilnahmebildschirm -- Autor hat es am 12.09. nachgesehen: Dort steht
 * weiter "Participate". Die Einladung liegt im Chat, im dritten Reiter:
 *
 *     A friend is inviting you to join a journey
 *     [Burg 36] [Bild]  NAME (i)                    [ Accept ]
 *                       Clan                        [ Reject ]
 *                       Kampfkraft
 *
 * Nach "Accept" dort springt das Spiel selbst auf den Teilnahmebildschirm,
 * jetzt mit "Decline" und "Accept (1 Fackel)". Den Rest erledigt DungeonMode.
 *
 * Angenommen wird nur die Einladung des RICHTIGEN Freundes -- Autors Wunsch.
 * Erkannt wird er am selben Bild, mit dem der Bot ihn in der Freundesliste
 * sucht: Im Chat ist es dieselbe Schrift, nur groesser und auf dunklem
 * Grund. Wie das trotzdem zusammenpasst, steht bei
 * Matcher.weisseSchrift().
 */
public final class FreundesChat {

    /** Der schmale Reiter mit dem Pfeil am linken Rand. Faehrt den Chat aus. */
    static final String T_OEFFNEN = "chat_tab";
    /** Der Pfeil am rechten Rand des offenen Chats. Faehrt ihn wieder ein. */
    static final String T_SCHLIESSEN = "chat_close";
    /** Der dritte Reiter oben im Chat, in dem die Einladungen stehen. */
    static final String T_REITER = "chat_invite_tab";
    /** Die Zeile "A friend is inviting you to join a journey". */
    static final String T_EINLADUNG = "chat_invite";
    /** Der gruene Knopf "Accept" in der Einladung -- nicht der mit der Fackel. */
    static final String T_ANNEHMEN = "chat_invite_accept";

    /**
     * So gut muss der Name auf der Einladung mindestens zum Bild aus der
     * Freundesliste passen -- UND um NAME_VORSPRUNG besser als zu jedem
     * anderen aufgenommenen Freund.
     *
     * Gemessen am 12.09. an Autors sieben Freunden, mit nachgestellten
     * Chat-Namen (dunkler Grund, 15 % groesser): der richtige Freund 97 %,
     * der aehnlichste falsche bis 72 %. Eine feste Grenze allein waere
     * wacklig -- die Gegenprobe mit den anderen macht es sicher. An einer
     * echten Einladung ist es noch nicht gemessen; jede Pruefung schreibt
     * die Werte ins Protokoll.
     */
    private static final double NAME_SCHWELLE = 0.75;
    /** Um so viel muss der richtige Freund vor jedem anderen liegen. */
    private static final double NAME_VORSPRUNG = 0.10;
    /** Die Vorsilbe aller Freundesbilder. */
    private static final String FREUND_VORSILBE = DungeonMode.freundVorlage("");

    /** Im Chat ist der Name gut ein Siebtel groesser als in der Freundesliste. */
    private static final double[] NAME_GROESSEN = {1.0, 1.05, 1.1, 1.15, 1.2, 1.25, 1.3, 1.35};

    /**
     * So lange wird nach "Accept" im Chat hoechstens auf den
     * Teilnahmebildschirm mit "Accept (1 Fackel)" gewartet. Erst der zeigt,
     * dass die Einladung wirklich noch galt -- siehe angenommen().
     */
    private static final long ANNAHME_PRUEFEN_MS = 5000;

    public enum Ergebnis {
        /** Die Einladung des Freundes wurde angenommen; das Spiel zeigt jetzt "Accept (1 Fackel)". */
        ANGENOMMEN,
        /** Sie liegt da, wurde aber nur angesehen (annehmen = false). */
        GEFUNDEN,
        /** Im Chat liegt keine Einladung von ihm -- oder sie galt beim Annehmen nicht mehr. */
        KEINE,
        /** Nicht nachsehbar: Bilder fehlen, oder hier gibt es den Chat nicht. */
        NICHT_MOEGLICH
    }

    private FreundesChat() {
    }

    /** Ohne diese vier geht es nicht. Der Reiter ist freiwillig. */
    public static boolean bereit(Matcher m) {
        return m.has(T_OEFFNEN) && m.has(T_SCHLIESSEN) && m.has(T_EINLADUNG) && m.has(T_ANNEHMEN);
    }

    /** Alle Bilder dieser Klasse -- fuer die Liste der freiwilligen Vorlagen. */
    public static List<String> vorlagen() {
        return List.of(T_OEFFNEN, T_SCHLIESSEN, T_REITER, T_EINLADUNG, T_ANNEHMEN);
    }

    /**
     * Schaut in den Chat, ob der Freund eingeladen hat. Ein einzelner Freund
     * ist der Sonderfall einer Liste mit einem Namen.
     */
    public static Ergebnis nachsehen(BotContext ctx, String freundVorlage, String freundName,
                                     boolean annehmen) {
        return nachsehen(ctx, List.of(freundVorlage), List.of(freundName), annehmen);
    }

    /**
     * Schaut in den Chat, ob EINER aus der Liste eingeladen hat.
     *
     * Angenommen wird die Einladung des ersten aus der Liste, die im Chat
     * liegt (Autor, 16.09.: "!start dungeon 100 wetter,reaper,locke").
     *
     * annehmen = true: Liegt eine da, wird sie angenommen.
     * annehmen = false: nur nachsehen -- gebraucht, wenn der Bot selbst
     * gerade eingeladen hat und erst seine eigene Einladung zurueckziehen muss.
     *
     * Der Chat ist hinterher wieder zu.
     */
    public static Ergebnis nachsehen(BotContext ctx, List<String> vorlagen, List<String> namen,
                                     boolean annehmen) {
        Matcher m = ctx.matcher();
        // Nur die Namen, fuer die es auch ein Bild gibt.
        java.util.LinkedHashSet<String> ziele = new java.util.LinkedHashSet<>();
        for (String v : vorlagen) if (m.has(v)) ziele.add(v);
        if (!bereit(m) || ziele.isEmpty()) return Ergebnis.NICHT_MOEGLICH;

        String anzeige = String.join(", ", namen);

        Mat screen = ctx.refresh();
        if (screen == null) return Ergebnis.NICHT_MOEGLICH;

        // 1. Chat ausfahren, falls er nicht schon offen ist.
        //
        // 27.09.: Der Pfeil sitzt je nach Bildschirm woanders (Burg: x 689,
        // Verlies-Fenster: schmaler). Der Bot schaut aber zuerst nur an der
        // gemerkten Stelle nach -- ein schon offener Chat an neuer Stelle galt
        // als zu. Deshalb vor dem Urteil einmal das ganze Bild absuchen.
        if (!ctx.visible(screen, T_SCHLIESSEN)) {
            ctx.matcher().naechstesBildGruendlich();
            Mat nochmal = ctx.refresh();
            if (nochmal != null) screen = nochmal;
        }
        if (!ctx.visible(screen, T_SCHLIESSEN)) {
            if (!ctx.clickIfVisible(screen, T_OEFFNEN,
                    Sprache.t("Open the chat (invite from " + anzeige + "?)",
                    "Chat oeffnen (Einladung von " + anzeige + "?)"))) {
                return Ergebnis.NICHT_MOEGLICH;   // hier gibt es den Chat nicht
            }
            screen = warteAuf(ctx, T_SCHLIESSEN, 4000);
            if (screen == null) {
                Log.warn("Chat", Sprache.t("The chat did not open -- or the image " + T_SCHLIESSEN + " does not match.",
                        "Der Chat ist nicht aufgegangen -- oder das Bild "
                        + T_SCHLIESSEN + " passt nicht."));
                return Ergebnis.NICHT_MOEGLICH;
            }
        }

        // 2. Den Reiter mit den Einladungen waehlen, wenn sie nicht schon zu
        //    sehen sind. Der Chat oeffnet sich dort, wo man zuletzt war.
        //
        //    27.09.: Das hat seit dem 20.09. nie gegriffen (kein einziger Klick
        //    im Protokoll). chat_invite_tab zeigt das Verlies-Symbol auf GRUENEM
        //    Grund -- so sieht der Reiter aus, wenn er schon GEWAEHLT ist. Ist ein
        //    anderer Reiter offen, sieht er anders aus. Deshalb zaehlt jetzt jede
        //    Aufnahme chat_invite_tab* (z. B. chat_invite_tab_2 = nicht gewaehlt),
        //    und zur Not wird in Graustufen gesucht.
        // Reiter und Einladung sitzen je nach Chatbreite woanders: das erste
        // Bild im offenen Chat immer ganz absuchen.
        ctx.matcher().naechstesBildGruendlich();
        Mat ganz = ctx.refresh();
        if (ganz != null) screen = ganz;
        if (!ctx.visible(screen, T_EINLADUNG)) {
            Optional<Matcher.Hit> reiter = reiterFinden(screen, ctx);
            if (reiter.isPresent()) {
                Matcher.Hit r = reiter.get();
                Log.action("Klick", Sprache.t("Chat: tab with the invites  [" + r.name() + " at " + r.centerX() + "," + r.centerY() + String.format(", match %.3f]", r.score()),
                        "Chat: Reiter mit den Einladungen  [" + r.name() + " bei " + r.centerX()
                        + "," + r.centerY() + String.format(", Uebereinstimmung %.3f]", r.score())));
                ctx.input().click(r);
                ctx.sleepUntilChanged(1500);
                ctx.sleep(300);
                screen = ctx.refresh();
                if (screen == null) return Ergebnis.NICHT_MOEGLICH;
            } else {
                // Autors Bild 27.09.: Ohne Einladung GIBT es den Verlies-Reiter gar
                // nicht (nur 4 Reiter). Kein Reiter heisst also: keine Einladung.
                Log.info("Chat", Sprache.t("No dungeon tab in the chat -- there is no invite waiting.",
                        "Kein Verlies-Reiter im Chat -- es liegt keine Einladung da."));
            }
        }

        // 3. Die Einladung eines der gewuenschten Freunde suchen.
        Optional<Matcher.Hit> knopf = einladungVon(screen, ctx, ziele, anzeige);

        if (knopf.isPresent() && annehmen) {
            Log.action("Klick", Sprache.t("Accept the invite from " + anzeige + " in the chat",
                    "Einladung von " + anzeige + " im Chat annehmen"));
            ctx.input().click(knopf.get());
            ctx.sleepUntilChanged(3000);
            ctx.sleep(600);
            // Das Spiel faehrt den Chat dabei selbst ein. Falls nicht: zu --
            // er laege sonst ueber dem Bildschirm, an dem sich gleich zeigt,
            // ob es geklappt hat.
            Mat danach = ctx.refresh();
            if (danach != null && ctx.visible(danach, T_SCHLIESSEN)) schliessen(ctx, danach);
            if (!angenommen(ctx)) {
                Log.info("Chat", Sprache.t("The invite apparently no longer applied: after \"Accept\" " + "no participation screen with \"Accept (1 torch)\" appeared.",
                        "Die Einladung galt offenbar nicht mehr: Nach \"Accept\" kam "
                        + "kein Teilnahmebildschirm mit \"Accept (1 Fackel)\"."));
                return Ergebnis.KEINE;
            }
            return Ergebnis.ANGENOMMEN;
        }
        schliessen(ctx, screen);
        return knopf.isPresent() ? Ergebnis.GEFUNDEN : Ergebnis.KEINE;
    }

    /**
     * Hat das Annehmen gegriffen? Dann steht der Teilnahmebildschirm mit
     * "Accept (1 Fackel)" da.
     *
     * Frueher galt schon der Klick als Erfolg. Genau in dem Fall, fuer den
     * der Blick in den Chat gebaut ist, stimmt das nicht: Haben sich zwei
     * Bots gekreuzt und der andere zieht seine Einladung gerade zurueck,
     * bleibt die alte Nachricht mit ihrem gruenen Knopf im Chat stehen. Der
     * Klick ging ins Leere, der Bot hielt die Sache fuer erledigt -- und
     * liess die Zufallspause aus, die die zwei Bots auseinanderhalten soll.
     *
     * Fehlt das Bild invite_accept, laesst es sich nicht pruefen; dann gilt
     * wie frueher der Klick.
     */
    private static boolean angenommen(BotContext ctx) {
        if (!ctx.matcher().has(DungeonMode.T_INVITE_ACCEPT)) return true;
        long ende = System.currentTimeMillis() + ANNAHME_PRUEFEN_MS;
        while (true) {
            Mat bild = ctx.refresh();
            if (bild != null && ctx.visible(bild, DungeonMode.T_INVITE_ACCEPT)) return true;
            if (System.currentTimeMillis() >= ende) return false;
            ctx.sleep(400);
        }
    }

    /**
     * Der Accept-Knopf der Einladung des Freundes -- leer, wenn keine von ihm
     * da ist.
     *
     * Die Lage stammt aus Autors Bild: oben die Zeile "A friend is inviting
     * ...", rechts daneben der Knopf, der Name eine Zeile tiefer links vom
     * Knopf. Liegen mehrere Einladungen da, zaehlt die unterste zuerst -- im
     * Chat steht das Neueste unten.
     */
    private static Optional<Matcher.Hit> einladungVon(Mat screen, BotContext ctx,
                                                      java.util.Set<String> zielVorlagen,
                                                      String anzeige) {
        List<Matcher.Hit> zeilen = new ArrayList<>(
                ctx.matcher().findAll(screen, T_EINLADUNG, 0.80, 8));
        if (zeilen.isEmpty()) {
            Log.info("Chat", Sprache.t("There is no invite in the chat.", "Im Chat liegt keine Einladung."));
            return Optional.empty();
        }
        zeilen.sort(Comparator.comparingInt(Matcher.Hit::y).reversed());
        List<Matcher.Hit> knoepfe = ctx.matcher().findAll(screen, T_ANNEHMEN, 0.80, 8);

        for (Matcher.Hit zeile : zeilen) {
            // Der Knopf dieser Einladung: rechts von der Zeile, auf etwa
            // gleicher Hoehe.
            Matcher.Hit knopf = null;
            for (Matcher.Hit k : knoepfe) {
                int abstand = k.centerY() - zeile.centerY();
                if (abstand < -zeile.height() || abstand > zeile.height() * 3) continue;
                if (k.centerX() < zeile.centerX()) continue;
                if (knopf == null
                        || Math.abs(abstand) < Math.abs(knopf.centerY() - zeile.centerY())) {
                    knopf = k;
                }
            }
            if (knopf == null) continue;

            // Wo der Name steht: unter der Zeile bis knapp unter den Knopf,
            // links vom Knopf. Die Kampfkraft darunter ist auch weiss -- sie
            // liegt tiefer und bleibt draussen.
            int oben = zeile.y() + zeile.height();
            int unten = knopf.y() + knopf.height() + knopf.height() / 2;
            Rect bereich = new Rect(zeile.x(), oben,
                    Math.max(8, knopf.x() - zeile.x()), Math.max(8, unten - oben));

            // Die weissen Stellen des Bereichs nur einmal ausrechnen --
            // verglichen wird er gleich mit JEDEM aufgenommenen Freund.
            // Zwei Masken: weisse Schrift (Freunde ausserhalb des Clans) und helle,
            // auch farbige Schrift (Clan-Mitglieder stehen hellgruen da, 27.09.).
            // Je Freund zaehlt die bessere.
            try (Matcher.WeissBild bild = ctx.matcher().weissBild(screen, bereich);
                 Matcher.WeissBild hell = ctx.matcher().hellBild(screen, bereich)) {
                if (bild == null && hell == null) continue;

                // Bester Treffer unter den GEWUENSCHTEN Freunden ...
                double guete = 0;
                String besterZiel = "";
                for (String ziel : zielVorlagen) {
                    double g = namensGuete(ctx, bild, hell, ziel);
                    if (g > guete) {
                        guete = g;
                        besterZiel = ziel.startsWith(FREUND_VORSILBE)
                                ? ziel.substring(FREUND_VORSILBE.length()) : ziel;
                    }
                }

                // ... und Gegenprobe mit allen ANDEREN aufgenommenen Freunden.
                // Passt der Name zu einem von ihnen fast genauso gut, ist es
                // womoeglich dessen Einladung -- dann lieber nicht annehmen.
                String naechster = "";
                double konkurrenz = 0;
                for (String andere : ctx.matcher().names()) {
                    if (!andere.startsWith(FREUND_VORSILBE) || zielVorlagen.contains(andere)
                            || andere.equals("friend_invite")) {
                        continue;
                    }
                    double g = namensGuete(ctx, bild, hell, andere);
                    if (g > konkurrenz) {
                        konkurrenz = g;
                        naechster = andere.substring(FREUND_VORSILBE.length());
                    }
                }

                String werte = besterZiel + " " + Math.round(guete * 100) + " %"
                        + (naechster.isEmpty() ? ""
                           : Sprache.t(", the most similar other friend (" + naechster + ") ", ", der aehnlichste andere Freund (" + naechster + ") ")
                             + Math.round(konkurrenz * 100) + " %");
                if (guete >= NAME_SCHWELLE && guete >= konkurrenz + NAME_VORSPRUNG) {
                    Log.info("Chat", Sprache.t("Invite from " + besterZiel + " found (name: " + werte + ").",
                            "Einladung von " + besterZiel + " gefunden (Name: "
                            + werte + ")."));
                    return Optional.of(knopf);
                }
                Log.info("Chat", Sprache.t("There is an invite in the chat, but not surely from " + anzeige + " (name: " + werte + "). It is not accepted.",
                        "Im Chat liegt eine Einladung, aber nicht sicher von " + anzeige
                        + " (Name: " + werte + "). Sie wird nicht angenommen."));
            }
        }
        return Optional.empty();
    }

    /**
     * Der Reiter mit den Einladungen: jede Aufnahme chat_invite_tab* (gewaehlt
     * und nicht gewaehlt), die beste gewinnt; sonst dieselben in Graustufen.
     */
    private static Optional<Matcher.Hit> reiterFinden(Mat screen, BotContext ctx) {
        Matcher m = ctx.matcher();
        Matcher.Hit beste = null;
        for (String name : m.names()) {
            if (!name.startsWith(T_REITER)) continue;
            Optional<Matcher.Hit> h = m.find(screen, name, Matcher.DEFAULT_THRESHOLD);
            if (h.isPresent() && (beste == null || h.get().score() > beste.score())) beste = h.get();
        }
        if (beste != null) return Optional.of(beste);
        for (String name : m.names()) {
            if (!name.startsWith(T_REITER)) continue;
            List<Matcher.Hit> grau = m.findAllGrau(screen, name, 0.80, 1);
            if (!grau.isEmpty() && (beste == null || grau.get(0).score() > beste.score())) beste = grau.get(0);
        }
        return Optional.ofNullable(beste);
    }

    /** Wie gut der Name im Ausschnitt zum Freundesbild passt, 0 bis 1 -- die bessere der beiden Masken. */
    private static double namensGuete(BotContext ctx, Matcher.WeissBild bild, Matcher.WeissBild hell,
                                      String vorlage) {
        double w = bild == null ? 0 : ctx.matcher().weisseSchrift(bild, vorlage, NAME_GROESSEN)
                .map(Matcher.Hit::score).orElse(0.0);
        double h = hell == null ? 0 : ctx.matcher().weisseSchrift(hell, vorlage, NAME_GROESSEN)
                .map(Matcher.Hit::score).orElse(0.0);
        return Math.max(w, h);
    }

    /**
     * Wartet, bis ein Bild zu sehen ist, und laesst die Animation noch
     * auslaufen. Null, wenn es in der Zeit nicht kam.
     */
    private static Mat warteAuf(BotContext ctx, String vorlage, long hoechstensMs) {
        long ende = System.currentTimeMillis() + hoechstensMs;
        int runde = 0;
        while (System.currentTimeMillis() < ende) {
            ctx.sleep(200);
            // Jedes dritte Bild ganz absuchen: der Pfeil kann an einer neuen
            // Stelle sitzen (27.09., "Chat ist nicht aufgegangen" um 17:50:52).
            if (++runde % 3 == 0) ctx.matcher().naechstesBildGruendlich();
            Mat bild = ctx.refresh();
            if (bild != null && ctx.visible(bild, vorlage)) {
                // Der Chat faehrt mit einer Animation aus. Der Pfeil ist schon
                // da, bevor alles steht -- erst wenn das Bild still steht,
                // liegen die Einladungen dort, wo sie bleiben.
                return ChatAnimation.warteBisRuhig(ctx, 3000);
            }
        }
        return null;
    }

    private static void schliessen(BotContext ctx, Mat screen) {
        if (ctx.clickIfVisible(screen, T_SCHLIESSEN, Sprache.t("Close the chat", "Chat schliessen"))) {
            ctx.sleepUntilChanged(1500);
            ctx.sleep(300);
        }
    }
}
