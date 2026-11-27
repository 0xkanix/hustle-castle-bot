package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Tastatur;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Verlies mit einem Clan-Mitglied -- Event-Quest "Go to a Dungeon with a clan mate" (Autor hat es
 * am 18.11. 21:12-21:25 und 19.11. 20:34-20:54 selbst gespielt).
 *
 * Ablauf (DungeonMode ruft die Teile hier auf, wenn der Mitspieler-Weg CLAN ist -- "!start dungeon 1 clan"; die
 * Entscheidungen trifft ClanVerliesPlan):
 *  1. In der Burg den Clan-Chat aufmachen, "x/34 online" lesen und fragen (englisch, mit Event-Bezug). Steht dort
 *     "0/34 online", wird nicht gewartet.
 *  2. Im Verlies bei "Teammate search" den Knopf "Clan", kurze Nachricht, "Send". Bis zu 10 Minuten warten; dabei im
 *     Wartefenster ab und zu den Clan-Chat aufmachen und lesen ("in 5 min" -> spaeter neu einladen, "can't" -> aufgeben).
 *     NIE "Cancel" ausser beim Aufgeben/Neu-Einladen.
 *  3. Hat jemand angenommen: spielen (DungeonMode). Beim vorletzten Stuetzpunkt (Balken ab 80 %) "Thanks" im
 *     VERLIES-Chat (Reiter mit dem Verlies-Symbol); ist der Mitspieler schon weg, stattdessen im Clan-Chat (21:25).
 *     Am Ende einmal nachsehen, ob er geantwortet hat: dann Autor per Discord Bescheid geben und kurz freundlich antworten.
 *
 * Alle festen Stellen gelten fuer die Spielflaeche 1159x770 (wie im Klick-Protokoll; Autors Bildschirm-Werte vom 19.11.
 * minus 8,31) und werden auf die echte Groesse umgerechnet.
 */
final class VerliesClan {

    private static final String WO = "Verlies-Clan";

    // ---- Bildvorlagen ------------------------------------------------------
    /** Knopf "Clan" in "Teammate search" -- Autor hat ihn am 18.11. ausgeschnitten. */
    static final String T_CLAN = "rs_verlies_clan";
    /** Gruene Sprechblase unten im offenen Chat (Clan- und Verlies-Chat gleich) -- zeigt: der Chat ist auf. */
    static final String T_CHAT_SCHREIBEN = "rs_chat_schreiben";
    /**
     * Grauer Senden-Kasten rechts neben der offenen Eingabezeile (Pruefung 19.11.: Bild 21:12:32 leer 1,0; Verlies-Chat
     * 21:17:23 / 21:17:57 mit Text 0,996; alle anderen Bilder hoechstens 0,64). Ist er nicht zu sehen, ist die Zeile
     * zu -- dann liegt an der Senden-Stelle (1036,730) in der Burg der Knopf "Market".
     */
    static final String T_CHAT_SENDEN = "rs_chat_senden";
    static final org.opencv.core.Rect SENDEN_BEREICH = new org.opencv.core.Rect(940, 680, 219, 90);
    /** Reiter mit dem Verlies-Symbol, aktiv (tuerkis) -- ausgeschnitten aus Bild 19.11. 20:52:40. */
    static final String T_REISECHAT_REITER = "rs_reisechat_reiter";
    static final org.opencv.core.Rect REITER_BEREICH = new org.opencv.core.Rect(380, 0, 140, 70);
    /** Rote Zeile "The other player left the Dungeon." im Verlies-Chat (Bilder 19.11. 20:51:03-20:52:40). */
    static final String T_REISECHAT_WEG = "rs_reisechat_weg";
    static final org.opencv.core.Rect CHAT_BEREICH = new org.opencv.core.Rect(20, 90, 650, 620);

    /** Alle Vorlagen, die hier freiwillig benutzt werden (fuer DungeonMode.optionalTemplates). */
    static List<String> vorlagen() {
        return List.of(T_CLAN, T_CHAT_SCHREIBEN, T_CHAT_SENDEN, T_REISECHAT_REITER, T_REISECHAT_WEG);
    }

    // ---- Feste Stellen (Klick-Protokoll 18.11., Spielflaeche 1159x770) -----
    private static final int BREITE = 1159;
    private static final int HOEHE = 770;
    /** Pfeil am linken Rand (Burg und Wartefenster "Waiting for a clan mate"): Chat ausfahren. */
    static final int[] CHAT_PFEIL = {16, 360};
    /**
     * Reiter "Clan" oben im Chat (Wappen mit Drache, Bild bilder/clanchat-1.png; im Wartefenster gleich, Bild 20:36:30).
     * Der Chat oeffnet dort, wo man zuletzt war -- deshalb sicherheitshalber antippen.
     */
    static final int[] CHAT_REITER_CLAN = {262, 32};
    /** Gruene Sprechblase unten: Schreiben (Autor 19.11. im Verlies-Chat: Bildschirm 606,760 = Flaeche 598,729). */
    static final int[] CHAT_SPRECHBLASE = {600, 730};
    /** Das Eingabefeld unten. */
    static final int[] CHAT_FELD = {500, 730};
    /** Pfeil-Knopf rechts neben dem Feld: senden. */
    static final int[] CHAT_SENDEN = {1036, 730};
    /** Pfeil am rechten Chat-Rand: Chat einfahren. */
    static final int[] CHAT_ZU = {690, 360};
    /** Wo "8/34 online" steht (unter dem Clan-Namen oben links), x, y, Breite, Hoehe. */
    static final int[] ONLINE_KASTEN = {50, 94, 170, 30};
    /** Nachrichten im offenen Clan-Chat (unter dem Kopf mit Clan-Name), x, y, Breite, Hoehe -- fuer die Texterkennung. */
    static final int[] CLAN_NACHRICHTEN = {20, 130, 630, 570};

    /** Verlieskarte: Chat-Pfeil links (Autor 19.11. live: Bildschirm 18,380). */
    static final int[] REISECHAT_PFEIL = {10, 349};
    /** Im Chat auf der Verlieskarte: Reiter mit dem Verlies-Symbol (Autor 19.11. live: Bildschirm 448,56). */
    static final int[] REISECHAT_REITER = {440, 25};
    /**
     * Im Chat auf der Verlieskarte: Reiter "Clan" (Wappen mit Drache, zweiter von links; Bild 19.11. 20:51:36). Dort
     * sitzt er weiter links als in Burg und Wartefenster (262,32 laege hier am Rand des Reiters).
     */
    static final int[] REISECHAT_REITER_CLAN = {218, 32};
    /** Rotes Abzeichen (neue Nachrichten) am Chat-Pfeil links, x, y, Breite, Hoehe (Bilder 20:34:41, 20:41:07). */
    static final int[] ABZEICHEN = {12, 302, 40, 40};

    /** Knopf "Clan" in "Teammate search" (falls die Vorlage nicht greift). */
    static final int[] VERLIES_CLAN = {142, 395};
    /** Feld "Write a message" nach "Clan". */
    static final int[] VERLIES_NACHRICHT = {580, 360};
    /** Gruener Knopf "Send" darunter. */
    static final int[] VERLIES_SENDEN = {580, 506};
    /** Roter Knopf "Cancel" auf "Waiting for a clan mate". */
    static final int[] VERLIES_CANCEL = {390, 650};

    /** So lange wird hoechstens auf ein Clan-Mitglied gewartet (Autor 19.11. 20:42: "bis zu 10 min"). */
    static final long WARTEN_MS = ClanVerliesPlan.WARTEN_MS;

    // ---- Texte (englisch, wie der Clan spricht) ----------------------------
    static final String DANKE = "Thanks";
    /** Ist der Mitspieler schon weg, kommt das Thanks in den Clan-Chat (Autor 19.11. 21:25) -- mit Bezug, sonst unklar. */
    static final String DANKE_CLAN = "Thanks for the Dungeon run!";
    static final String ABSCHIED = "Haha thanks again! See you around :)";

    /** "1 round" / "3 rounds". */
    static String runden(int n) {
        int r = Math.max(1, n);
        return r + (r == 1 ? " round" : " rounds");
    }

    /** Die Frage im Clan-Chat, mit Rundenzahl und Quest-Bezug (Autor 18.11. + 19.11.). */
    static String clanChatText(int n) {
        return "Anyone up for " + runden(n) + " of Dungeon with me for the event quest (play with a clan mate)? "
                + "Inviting now via Clan, thanks!";
    }

    /** Die kurze Nachricht zur Clan-Einladung im Verlies ("Write a message"). */
    static String verliesText(int n) {
        return "Clan mate quest - " + runden(n) + ", thanks!";
    }

    private static final Pattern ONLINE = Pattern.compile("(\\d{1,3})\\s*/\\s*(\\d{1,3})");

    /**
     * Wie viele Clan-Mitglieder online sind, aus der gelesenen Zeile "8/34 online". Null, wenn es nicht lesbar ist --
     * dann wird so getan, als waere jemand da (lieber umsonst warten als die Quest auslassen).
     *
     * Die Texterkennung liest eine 0 gern als O: "O/34 online" gilt als 0.
     */
    static Integer onlineAus(List<String> zeilen) {
        if (zeilen == null) return null;
        for (String z : zeilen) {
            if (z == null) continue;
            String t = z.replaceAll("(?<![A-Za-z])[Oo](?=\\s*/)", "0");
            java.util.regex.Matcher m = ONLINE.matcher(t);
            while (m.find()) {
                int on = Integer.parseInt(m.group(1));
                int alle = Integer.parseInt(m.group(2));
                if (alle > 0 && on <= alle) return on;
            }
        }
        return null;
    }

    // ---- 1. Clan-Chat in der Burg -----------------------------------------

    enum ChatErgebnis {
        /** Die Frage steht im Clan-Chat. */
        GESENDET,
        /** "0/x online" -- niemand da, nicht warten. */
        NIEMAND_ONLINE,
        /** Der Chat ging auf, aber tippen ging nicht (kein Windows ...). Das Verlies geht trotzdem. */
        NICHT_GETIPPT,
        /** Der Chat ging nicht auf. Das Verlies geht trotzdem. */
        KEIN_CHAT
    }

    /**
     * Clan-Chat in der Burg: aufmachen, Reiter Clan, "x/34 online" lesen, Frage tippen, senden, Chat zu.
     * Laeuft in einem Stueck (wie FreundesChat), dauert ein paar Sekunden.
     */
    static ChatErgebnis clanChatSchreiben(BotContext ctx, int n) {
        if (!clanChatOeffnen(ctx)) return ChatErgebnis.KEIN_CHAT;

        Integer online = onlineLesen(ctx);
        if (online != null) {
            Log.info(WO, Sprache.t("Clan chat: ", "Clan-Chat: ") + online + Sprache.t(" members online.", " Mitglieder online."));
            if (online == 0) {
                chatZu(ctx);
                return ChatErgebnis.NIEMAND_ONLINE;
            }
        } else {
            Log.info(WO, Sprache.t("Clan chat: \"x/34 online\" not readable -- asking anyway.", "Clan-Chat: \"x/34 online\" nicht lesbar -- es wird trotzdem gefragt."));
        }

        boolean ok = imOffenenChatSchreiben(ctx, clanChatText(n), Sprache.t("Clan chat", "Clan-Chat"));
        chatZu(ctx);
        return ok ? ChatErgebnis.GESENDET : ChatErgebnis.NICHT_GETIPPT;
    }

    /** Pfeil links, Reiter Clan; true, wenn der Chat danach sicher offen ist (gruene Sprechblase). */
    private static boolean clanChatOeffnen(BotContext ctx) {
        tippe(ctx, CHAT_PFEIL, Sprache.t("Open the clan chat (arrow left)", "Clan-Chat oeffnen (Pfeil links)"));
        ChatAnimation.warteBisRuhig(ctx, 3000);
        // Pruefung 19.11.: erst nachsehen, ob der Chat offen ist -- in der Burg liegt bei (262,32) die Ressourcenleiste
        Mat bild = ctx.refresh();
        if (bild != null && ctx.matcher().has(T_CHAT_SCHREIBEN) && !ctx.visible(bild, T_CHAT_SCHREIBEN)) {
            Log.warn(WO, Sprache.t("Clan chat did not open (green speech bubble not seen).", "Clan-Chat nicht aufgegangen (gruene Sprechblase nicht gesehen)."));
            return false;
        }
        tippe(ctx, CHAT_REITER_CLAN, Sprache.t("Chat: Clan tab", "Chat: Reiter Clan"));
        ctx.sleepUntilChanged(1000);
        ctx.sleep(400);
        bild = ctx.refresh();
        if (bild != null && ctx.matcher().has(T_CHAT_SCHREIBEN) && !ctx.visible(bild, T_CHAT_SCHREIBEN)) {
            Log.warn(WO, Sprache.t("Clan chat did not open (green speech bubble ", "Clan-Chat nicht aufgegangen (gruene Sprechblase ") + T_CHAT_SCHREIBEN + Sprache.t(" not seen).", " nicht gesehen)."));
            return false;
        }
        return true;
    }

    /**
     * Im offenen Chat (Clan oder Verlies -- unten gleich): gruene Sprechblase, Feld, Text tippen, senden. Getippt wird
     * nur, wenn die Eingabezeile sicher offen ist (Senden-Kasten zu sehen) -- sonst laege an der Senden-Stelle "Market".
     */
    private static boolean imOffenenChatSchreiben(BotContext ctx, String text, String wo) {
        tippe(ctx, CHAT_SPRECHBLASE, wo + Sprache.t(": write (green speech bubble)", ": Schreiben (gruene Sprechblase)"));
        ctx.sleepUntilChanged(1000);
        ctx.sleep(300);
        tippe(ctx, CHAT_FELD, wo + Sprache.t(": input field", ": Eingabefeld"));
        ctx.sleep(500);
        if (sendenKasten(ctx).isEmpty()) {
            Log.warn(WO, wo + Sprache.t(": the input line is not open (send box ", ": die Eingabezeile ist nicht offen (Senden-Kasten ") + T_CHAT_SENDEN + Sprache.t(" not seen) -- ", " nicht gesehen) -- ")
                    + Sprache.t("nothing typed.", "nichts getippt."));
            return false;
        }
        Log.action("Tippen", Sprache.t("Clan dungeon", WO) + ": \"" + text + "\"");
        if (!Tastatur.tippen(text)) {
            Log.warn(WO, wo + Sprache.t(": the text could not be typed.", ": Text liess sich nicht tippen."));
            return false;
        }
        ctx.sleep(400);
        Optional<Matcher.Hit> senden = sendenKasten(ctx);
        if (senden.isEmpty()) {
            Log.warn(WO, wo + Sprache.t(": send box no longer visible after typing -- not sent.", ": Senden-Kasten nach dem Tippen nicht mehr zu sehen -- nicht gesendet."));
            return false;
        }
        ctx.klick(senden.get(), wo + Sprache.t(": send (arrow right)", ": senden (Pfeil rechts)"));
        ctx.sleepUntilChanged(1500);
        ctx.sleep(500);
        return true;
    }

    /** Der Senden-Kasten der offenen Eingabezeile im frischen Bild (leer = Zeile zu oder Vorlage fehlt). */
    private static Optional<Matcher.Hit> sendenKasten(BotContext ctx) {
        if (!ctx.matcher().has(T_CHAT_SENDEN)) return Optional.empty();
        Mat b = ctx.refresh();
        return b == null ? Optional.empty()
                : ctx.matcher().findImBereich(b, T_CHAT_SENDEN, SENDEN_BEREICH, Matcher.DEFAULT_THRESHOLD);
    }

    private static void chatZu(BotContext ctx) {
        Mat bild = ctx.refresh();
        if (bild != null && ctx.clickIfVisibleIfKnown(bild, FreundesChat.T_SCHLIESSEN, Sprache.t("Close the chat", "Chat schliessen"))) {
            // erledigt ueber die Vorlage
        } else {
            tippe(ctx, CHAT_ZU, Sprache.t("Close the chat (arrow right at the chat)", "Chat schliessen (Pfeil rechts am Chat)"));
        }
        ctx.sleepUntilChanged(1500);
        ctx.sleep(300);
        // Noch offen (gruene Sprechblase)? Ein zweites Mal -- ein offener Chat verdeckt alles, woran DungeonMode erkennt.
        bild = ctx.refresh();
        if (bild != null && ctx.matcher().has(T_CHAT_SCHREIBEN) && ctx.visible(bild, T_CHAT_SCHREIBEN)) {
            tippe(ctx, CHAT_ZU, Sprache.t("Close the chat (second try)", "Chat schliessen (zweiter Versuch)"));
            ctx.sleepUntilChanged(1500);
        }
    }

    /** "8/34 online" lesen -- erst als weisse Schrift vergroessert, dann so wie es ist. Null = nicht lesbar. */
    private static Integer onlineLesen(BotContext ctx) {
        try {
            if (!de.hustlebot.core.Texterkennung.istDa()) return null;
            BufferedImage teil = ausschnitt(ctx.frame(), ONLINE_KASTEN);
            if (teil == null) return null;
            List<String> zeilen = de.hustlebot.core.Texterkennung.lies(
                    de.hustlebot.core.Texterkennung.weisseSchrift(teil, 3));
            Log.debug(WO, "Online-Zeile gelesen: " + zeilen);
            Integer on = onlineAus(zeilen);
            if (on != null) return on;
            zeilen = de.hustlebot.core.Texterkennung.lies(teil);
            Log.debug(WO, "Online-Zeile (zweiter Versuch): " + zeilen);
            return onlineAus(zeilen);
        } catch (Exception e) {
            Log.debug(WO, "Online-Zeile nicht gelesen: " + e.getMessage());
            return null;
        }
    }

    /** Text in einem Kasten (x, y, Breite, Hoehe auf der 1159er-Flaeche) lesen; leer, wenn es nicht geht. */
    private static List<String> textLesen(BufferedImage f, int[] kasten) {
        try {
            if (!de.hustlebot.core.Texterkennung.istDa()) return List.of();
            BufferedImage teil = ausschnitt(f, kasten);
            if (teil == null) return List.of();
            List<String> z = de.hustlebot.core.Texterkennung.lies(teil);
            return z == null ? List.of() : z;
        } catch (Exception e) {
            Log.debug(WO, "Chat nicht gelesen: " + e.getMessage());
            return List.of();
        }
    }

    private static BufferedImage ausschnitt(BufferedImage f, int[] kasten) {
        if (f == null) return null;
        double fx = f.getWidth() / (double) BREITE, fy = f.getHeight() / (double) HOEHE;
        int x = (int) Math.round(kasten[0] * fx), y = (int) Math.round(kasten[1] * fy);
        int w = Math.min((int) Math.round(kasten[2] * fx), f.getWidth() - x);
        int h = Math.min((int) Math.round(kasten[3] * fy), f.getHeight() - y);
        if (x < 0 || y < 0 || w <= 0 || h <= 0) return null;
        return f.getSubimage(x, y, w, h);
    }

    // ---- 2. Einladung im Verlies ------------------------------------------

    /**
     * In "Teammate search": "Clan" druecken, kurze Nachricht tippen, "Send". Danach steht "Waiting for a clan mate".
     * Liefert false, wenn die Einladung nicht sicher hinausging.
     */
    static boolean imVerliesEinladen(Mat screen, BotContext ctx, int n, String sendVorlage) {
        Optional<Matcher.Hit> clan = ctx.matcher().has(T_CLAN) ? ctx.find(screen, T_CLAN) : Optional.empty();
        if (clan.isPresent()) {
            ctx.klick(clan.get(), Sprache.t("Team mate: Clan", "Mitspieler: Clan"));
        } else {
            // Ohne Vorlage die Stelle aus dem Protokoll (nur wenn DungeonMode die Mitspielerwahl sicher erkannt hat).
            tippe(ctx, VERLIES_CLAN, Sprache.t("Team mate: Clan (fixed spot, template ", "Mitspieler: Clan (feste Stelle, Vorlage ") + T_CLAN + Sprache.t(" not found)", " nicht gefunden)"));
        }
        ctx.sleepUntilChanged(1800);
        ctx.sleep(500);

        // Pruefung 19.11. (Bild 21:14:21): die feste Stelle des Nachrichtenfelds (580,360) liegt auf der Mitspielerwahl
        // GENAU auf dem Wappen "Random". Ist das Fenster "Write a message" noch nicht offen, haette der Tipp ein Spiel mit
        // Fremden gestartet. Deshalb erst weiter, wenn der Knopf "Send" (nur in diesem Fenster, 21:14:33: 0,974) zu sehen ist.
        if (!nachrichtFensterOffen(ctx, sendVorlage)) {
            Log.warn(WO, Sprache.t("Clan invitation: the \"Write a message\" window did not open -- nothing more typed ", "Clan-Einladung: das Fenster \"Write a message\" ist nicht aufgegangen -- nichts weiter getippt ")
                    + Sprache.t("(otherwise \"Random\" is at the spot of the field).", "(an der Stelle des Felds liegt sonst \"Random\")."));
            return false;
        }
        tippe(ctx, VERLIES_NACHRICHT, Sprache.t("Clan invitation: field \"Write a message\"", "Clan-Einladung: Feld \"Write a message\""));
        ctx.sleep(500);
        String text = verliesText(n);
        Log.action("Tippen", Sprache.t("Clan dungeon", WO) + ": \"" + text + "\"");
        if (!Tastatur.tippen(text)) {
            // Die Einladung geht auch ohne Text hinaus (wie bei Freunden) -- also trotzdem senden.
            Log.warn(WO, Sprache.t("Clan invitation: the text could not be typed -- sending without text.", "Clan-Einladung: Text liess sich nicht tippen -- es wird ohne Text gesendet."));
        }
        ctx.sleep(400);
        Mat bild = ctx.refresh();
        if (bild == null || !ctx.clickIfVisibleIfKnown(bild, sendVorlage, Sprache.t("Send the clan invitation (Send)", "Clan-Einladung abschicken (Send)"))) {
            // nicht blind tippen: das Fenster war eben noch offen -- ist "Send" jetzt weg, stimmt etwas nicht
            Log.warn(WO, Sprache.t("Clan invitation: \"Send\" no longer visible -- not sent.", "Clan-Einladung: \"Send\" nicht mehr zu sehen -- nicht abgeschickt."));
            return false;
        }
        ctx.sleepUntilChanged(2000);
        return true;
    }

    /** Ist das Fenster "Write a message" offen (Knopf "Send" zu sehen)? Bis zu 3 s nachsehen. Ohne Vorlage: nein. */
    private static boolean nachrichtFensterOffen(BotContext ctx, String sendVorlage) {
        if (sendVorlage == null || !ctx.matcher().has(sendVorlage)) return false;
        for (int i = 0; i < 6; i++) {
            Mat b = ctx.refresh();
            if (b != null && ctx.visibleIfKnown(b, sendVorlage)) return true;
            ctx.sleep(500);
        }
        return false;
    }

    /** "Cancel" auf "Waiting for a clan mate" -- ueber die Vorlage, sonst an der festen Stelle. */
    static void wartenAbbrechen(Mat screen, BotContext ctx, String cancelVorlage) {
        if (screen == null || !ctx.clickIfVisibleIfKnown(screen, cancelVorlage, Sprache.t("Stop waiting for a clan member (Cancel)", "Warten auf Clan-Mitglied abbrechen (Cancel)"))) {
            tippe(ctx, VERLIES_CANCEL, Sprache.t("Stop waiting for a clan member (Cancel, fixed spot)", "Warten auf Clan-Mitglied abbrechen (Cancel, feste Stelle)"));
        }
        ctx.sleepUntilChanged(2000);
    }

    /** Was beim Blick in den Clan-Chat zu sehen war. offen = false: der Chat ging nicht auf, nichts gelesen. */
    record ChatLesung(boolean offen, List<String> zeilen, Integer online) { }

    /**
     * Waehrend "Waiting for a clan mate": Chat-Pfeil links, Reiter Clan, "x/34 online" und die Nachrichten lesen, Chat
     * wieder zu. Getippt und gedrueckt wird im Chat NICHTS (dort steht unter der eigenen Einladung ein rotes "Cancel").
     */
    static ChatLesung clanChatLesen(BotContext ctx) {
        if (!clanChatOeffnen(ctx)) {
            return new ChatLesung(false, List.of(), null);   // nicht offen: nichts zu schliessen
        }
        Integer online = onlineLesen(ctx);
        List<String> zeilen = textLesen(ctx.frame(), CLAN_NACHRICHTEN);
        Log.debug(WO, "Clan-Chat gelesen (" + online + " online): " + zeilen);
        chatZu(ctx);
        return new ChatLesung(true, zeilen, online);
    }

    // ---- Bildpruefungen (ohne Vorlage, an festen Stellen; paketoeffentlich fuer die Tests) -------------------------

    private static boolean tuerkis(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return b > 150 && g > 140 && r < 110;
    }

    private static int px(BufferedImage f, int x, int y) {
        int sx = (int) Math.round(x * f.getWidth() / (double) BREITE);
        int sy = (int) Math.round(y * f.getHeight() / (double) HOEHE);
        return f.getRGB(Math.min(f.getWidth() - 1, Math.max(0, sx)), Math.min(f.getHeight() - 1, Math.max(0, sy)));
    }

    /**
     * "Completion progress" oben auf der Verlieskarte in Prozent, -1 = nicht lesbar (Chat offen, anderes Fenster).
     * Gemessen an den Bildern vom 19.11.: der tuerkise Balken beginnt bei x 425 und ist voll 310 breit (oberer Teil
     * y 52-62, die Zahl steht darunter); 15 % endet bei 467, 43 % bei 554, 86 % bei 691. Links daneben (x 410) ist der
     * Rahmen -- ist dort tuerkis, liegt etwas anderes darueber (offener Chat).
     */
    static int fortschrittProzent(BufferedImage f) {
        if (f == null) return -1;
        if (tuerkis(px(f, 410, 57)) || tuerkis(px(f, 418, 57))) return -1;
        int rechts = -1;
        for (int y : new int[]{54, 57, 60}) {
            for (int x = 422; x <= 734; x++) {   // rechts davon (736-740) sitzt eine tuerkise Zierraute
                if (tuerkis(px(f, x, y))) rechts = Math.max(rechts, x);
            }
        }
        if (rechts < 0) {
            // leerer Balken: dunkel an seinem Anfang (0 %) -- sonst ist hier gar kein Balken
            int p = px(f, 440, 57);
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            return (r < 70 && g < 70 && b < 80) ? 0 : -1;
        }
        if (!tuerkis(px(f, 427, 57)) && !tuerkis(px(f, 430, 57))) return -1;   // Balken muss links anfangen
        return (int) Math.max(0, Math.min(100, Math.round((rechts - 425 + 1) * 100.0 / 310)));
    }

    /** Rotes Abzeichen mit Zahl am Chat-Pfeil links (neue Nachrichten im Chat)? */
    static boolean abzeichenAmChatPfeil(BufferedImage f) {
        if (f == null) return false;
        int rot = 0;
        for (int y = ABZEICHEN[1]; y < ABZEICHEN[1] + ABZEICHEN[3]; y += 2) {
            for (int x = ABZEICHEN[0]; x < ABZEICHEN[0] + ABZEICHEN[2]; x += 2) {
                int p = px(f, x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (r > 190 && g < 100 && b < 90) rot++;
            }
        }
        return rot >= 40;   // Abzeichen ~120 Punkte im 2er-Raster, ohne 0
    }

    /**
     * Ist im offenen Chat der Reiter mit dem Verlies-Symbol aktiv (tuerkis hinterlegt, Symbol in der Mitte)? Nur dann
     * darf "Thanks" getippt werden -- sonst landete es im Clan-Chat (Autor 19.11.: "nie in den Gilden-Chat").
     * Gemessen 19.11.: Verlies-Reiter aktiv 16/16 Randpunkte tuerkis, Clan-Reiter aktiv / Burg-Chat 0/16.
     */
    static boolean reiseReiterAktiv(BufferedImage f) {
        if (f == null) return false;
        int rand = 0;
        for (int x : new int[]{406, 414, 482, 490}) {
            for (int y : new int[]{14, 24, 42, 52}) {
                if (tuerkis(px(f, x, y))) rand++;
            }
        }
        int symbol = 0;
        for (int x = 436; x < 462; x += 4) {
            for (int y = 22; y < 46; y += 4) {
                if (!tuerkis(px(f, x, y))) symbol++;
            }
        }
        return rand >= 14 && symbol >= 20;
    }

    /**
     * Ist im Chat auf der Verlieskarte der Reiter "Clan" aktiv (tuerkis hinterlegt, Wappen in der Mitte)? Nur dann darf
     * dort das Clan-Thanks getippt werden. Gemessen 19.11.: Clan-Reiter aktiv (20:51:36, 20:51:56) 16/16 Randpunkte
     * tuerkis und 42/42 Wappenpunkte nicht; Verlies-Reiter aktiv (20:52:11, 20:52:40) 0/16; Wartefenster-Chat 3/16.
     */
    static boolean clanReiterImVerliesAktiv(BufferedImage f) {
        if (f == null) return false;
        int rand = 0;
        for (int x : new int[]{176, 184, 254, 262}) {
            for (int y : new int[]{14, 24, 42, 52}) {
                if (tuerkis(px(f, x, y))) rand++;
            }
        }
        int symbol = 0;
        for (int x = 204; x < 232; x += 4) {
            for (int y = 22; y < 46; y += 4) {
                if (!tuerkis(px(f, x, y))) symbol++;
            }
        }
        return rand >= 14 && symbol >= 20;
    }

    // ---- 3. "Thanks" im Verlies-Chat --------------------------------------

    enum DankErgebnis {
        /** "Thanks" steht im Verlies-Chat. */
        GESENDET,
        /** Der Mitspieler ist schon weg -- im Verlies-Chat nichts geschrieben; das Thanks kommt in den Clan-Chat (21:25). */
        MITSPIELER_WEG,
        /** Chat oder Reiter nicht sicher offen, oder tippen ging nicht -- nichts geschrieben, nicht noch einmal. */
        NICHT_GESENDET
    }

    /** Bild des Chat-Bereichs direkt nach dem eigenen "Thanks" -- am Ende zum Vergleich, ob der Mitspieler schrieb. */
    private BufferedImage nachDank;

    void zuruecksetzen() {
        nachDank = null;
    }

    /**
     * Auf der Verlieskarte: Chat-Pfeil, Reiter mit Verlies-Symbol, pruefen, "Thanks" schreiben, Chat zu. In einem Stueck
     * (ein paar Sekunden) -- spielen hat Vorrang, auf eine Antwort wird hier nicht gewartet.
     */
    DankErgebnis dankSagen(BotContext ctx) {
        if (!reiseChatOeffnen(ctx)) return DankErgebnis.NICHT_GESENDET;
        if (mitspielerWegImChat(ctx)) {
            Log.info(WO, Sprache.t("Dungeon chat: \"The other player left the Dungeon\" -- the thanks goes to the clan chat.", "Verlies-Chat: \"The other player left the Dungeon\" -- das Thanks kommt in den Clan-Chat (Autor 19.11.)."));
            chatZu(ctx);
            return DankErgebnis.MITSPIELER_WEG;
        }
        boolean ok = imOffenenChatSchreiben(ctx, DANKE, Sprache.t("Dungeon chat", "Verlies-Chat"));
        if (ok) {
            ctx.sleep(1500);   // das eigene "Thanks" erst erscheinen lassen
            ctx.refresh();
            nachDank = kopie(ausschnitt(ctx.frame(), new int[]{CHAT_BEREICH.x, CHAT_BEREICH.y, CHAT_BEREICH.width,
                    CHAT_BEREICH.height}));
        }
        chatZu(ctx);
        return ok ? DankErgebnis.GESENDET : DankErgebnis.NICHT_GESENDET;
    }

    /**
     * Der Mitspieler ist schon weg (Autor 19.11. 21:25): "Thanks" stattdessen in den Clan-Chat. Auf der Verlieskarte:
     * Chat-Pfeil, Reiter "Clan" (218,32), pruefen, dass er sicher aktiv ist (und nicht der Verlies-Reiter), tippen, Chat zu.
     */
    DankErgebnis dankImClanChat(BotContext ctx) {
        tippe(ctx, REISECHAT_PFEIL, Sprache.t("Open the chat (arrow left, for the clan thanks)", "Chat oeffnen (Pfeil links, fuer das Clan-Thanks)"));
        ChatAnimation.warteBisRuhig(ctx, 3000);
        Mat bild = ctx.refresh();
        if (bild == null) return DankErgebnis.NICHT_GESENDET;
        if (ctx.matcher().has(T_CHAT_SCHREIBEN) && !ctx.visible(bild, T_CHAT_SCHREIBEN)) {
            Log.warn(WO, Sprache.t("The chat did not open (green speech bubble not seen) -- no clan thanks.", "Chat ging nicht auf (gruene Sprechblase nicht gesehen) -- kein Clan-Thanks."));
            return DankErgebnis.NICHT_GESENDET;
        }
        if (!clanReiterImVerliesAktiv(ctx.frame())) {
            tippe(ctx, REISECHAT_REITER_CLAN, Sprache.t("Chat: Clan tab (crest with dragon)", "Chat: Reiter Clan (Wappen mit Drache)"));
            ctx.sleepUntilChanged(1000);
            ctx.sleep(400);
            ctx.refresh();
        }
        BufferedImage f = ctx.frame();
        if (!clanReiterImVerliesAktiv(f) || reiseReiterAktiv(f)) {
            Log.warn(WO, Sprache.t("Chat: the \"Clan\" tab is not clearly active -- nothing typed.", "Chat: der Reiter \"Clan\" ist nicht sicher aktiv -- nichts getippt."));
            chatZu(ctx);
            return DankErgebnis.NICHT_GESENDET;
        }
        boolean ok = imOffenenChatSchreiben(ctx, DANKE_CLAN, Sprache.t("Clan chat", "Clan-Chat"));
        chatZu(ctx);
        return ok ? DankErgebnis.GESENDET : DankErgebnis.NICHT_GESENDET;
    }

    /**
     * Am Ende (100 %): einmal in den Verlies-Chat schauen. Hat der Mitspieler etwas geschrieben, Autor per Discord
     * Bescheid geben (Autor 19.11.: er antwortet selbst und sagt, wie der Bot kuenftig reagieren soll) und kurz freundlich
     * antworten. Liefert true, wenn der Mitspieler schon weg ist.
     */
    boolean antwortPruefen(BotContext ctx) {
        if (!reiseChatOeffnen(ctx)) return false;
        if (mitspielerWegImChat(ctx)) {
            Log.info(WO, Sprache.t("Dungeon chat: the team mate has already left -- no reply needed.", "Verlies-Chat: der Mitspieler ist schon weg -- keine Antwort noetig."));
            chatZu(ctx);
            return true;
        }
        BufferedImage jetzt = ausschnitt(ctx.frame(), new int[]{CHAT_BEREICH.x, CHAT_BEREICH.y, CHAT_BEREICH.width,
                CHAT_BEREICH.height});
        boolean geaendert = nachDank == null || jetzt == null
                || bereichGeaendert(nachDank, jetzt, null);
        if (!geaendert) {
            Log.info(WO, Sprache.t("Dungeon chat: no reply to the thanks.", "Verlies-Chat: keine Antwort auf das Thanks."));
            chatZu(ctx);
            return false;
        }
        List<String> zeilen = textLesen(ctx.frame(), new int[]{CHAT_BEREICH.x, CHAT_BEREICH.y, CHAT_BEREICH.width,
                CHAT_BEREICH.height});
        if (ClanVerliesPlan.mitspielerWegImText(zeilen)) {
            Log.info(WO, Sprache.t("Dungeon chat: the team mate has already left -- no reply needed.", "Verlies-Chat: der Mitspieler ist schon weg -- keine Antwort noetig."));
            chatZu(ctx);
            return true;
        }
        if (zeilen.isEmpty()) {
            // Ohne Texterkennung nicht raten (das Bild aendert sich auch beim Blaettern): nur Autor Bescheid geben.
            Log.info(WO, Sprache.t("Dungeon chat: something appeared below the thanks, but the text is not readable.", "Verlies-Chat: unter dem Thanks hat sich etwas getan, der Text ist aber nicht lesbar."));
            ctx.melde(Sprache.t("Clan dungeon: dungeon chat", "Clan-Verlies: Verlies-Chat"), Sprache.t("Something appeared in the dungeon chat after the thanks (text not readable) ", "Im Verlies-Chat hat sich nach dem Thanks etwas getan (Text nicht lesbar) ")
                    + Sprache.t("-- the bot did not reply.", "-- der Bot hat nicht geantwortet."));
            chatZu(ctx);
            return false;
        }
        List<String> neu = ClanVerliesPlan.neuNach(zeilen, DANKE);
        if (neu.isEmpty()) {
            Log.info(WO, Sprache.t("Dungeon chat: nothing new below the thanks.", "Verlies-Chat: nichts Neues unter dem Thanks."));
            chatZu(ctx);
            return false;
        }
        String text = String.join(" / ", neu);
        Log.info(WO, Sprache.t("Dungeon chat: the team mate wrote: ", "Verlies-Chat: der Mitspieler schrieb: ") + text);
        ctx.melde(Sprache.t("Clan dungeon: team mate wrote in the dungeon chat", "Clan-Verlies: Mitspieler schrieb im Verlies-Chat"),
                "\"" + text + Sprache.t("\" -- the bot replies: \"", "\" -- der Bot antwortet: \"") + ABSCHIED + Sprache.t("\". How should it react to this in future?", "\". Wie soll er kuenftig auf so etwas reagieren?"));
        imOffenenChatSchreiben(ctx, ABSCHIED, Sprache.t("Dungeon chat", "Verlies-Chat"));
        chatZu(ctx);
        return false;
    }

    /** Chat-Pfeil auf der Verlieskarte, dann Reiter mit Verlies-Symbol. True nur, wenn dieser Reiter sicher aktiv ist. */
    private static boolean reiseChatOeffnen(BotContext ctx) {
        tippe(ctx, REISECHAT_PFEIL, Sprache.t("Open the dungeon chat (arrow left)", "Verlies-Chat oeffnen (Pfeil links)"));
        ChatAnimation.warteBisRuhig(ctx, 3000);
        Mat bild = ctx.refresh();
        if (bild == null) return false;
        if (ctx.matcher().has(T_CHAT_SCHREIBEN) && !ctx.visible(bild, T_CHAT_SCHREIBEN)) {
            // zu ist zu: nichts schliessen (der Pfeil rechts laege sonst auf der Karte)
            Log.warn(WO, Sprache.t("The dungeon chat did not open (green speech bubble not seen) -- nothing written.", "Verlies-Chat ging nicht auf (gruene Sprechblase nicht gesehen) -- nichts geschrieben."));
            return false;
        }
        if (!reiseReiterAktiv(ctx.frame())) {
            tippe(ctx, REISECHAT_REITER, Sprache.t("Dungeon chat: tab with the dungeon symbol", "Verlies-Chat: Reiter mit Verlies-Symbol"));
            ctx.sleepUntilChanged(1000);
            ctx.sleep(400);
            bild = ctx.refresh();
        }
        boolean reiter = reiseReiterAktiv(ctx.frame());
        if (reiter && ctx.matcher().has(T_REISECHAT_REITER) && bild != null) {
            reiter = ctx.matcher().findImBereich(bild, T_REISECHAT_REITER, REITER_BEREICH, Matcher.DEFAULT_THRESHOLD).isPresent();
        }
        if (!reiter) {
            Log.warn(WO, Sprache.t("Dungeon chat: the tab with the dungeon symbol is not clearly active -- nothing typed ", "Verlies-Chat: der Reiter mit dem Verlies-Symbol ist nicht sicher aktiv -- nichts getippt ")
                    + Sprache.t("(otherwise it would end up in the clan chat).", "(sonst landete es im Clan-Chat)."));
            chatZu(ctx);
            return false;
        }
        return true;
    }

    /** Steht im offenen Verlies-Chat die rote Zeile "The other player left the Dungeon"? */
    private static boolean mitspielerWegImChat(BotContext ctx) {
        Mat bild = ctx.refresh();
        return bild != null && ctx.matcher().has(T_REISECHAT_WEG)
                && ctx.matcher().findImBereich(bild, T_REISECHAT_WEG, CHAT_BEREICH, Matcher.DEFAULT_THRESHOLD).isPresent();
    }

    private static BufferedImage kopie(BufferedImage b) {
        if (b == null) return null;
        BufferedImage k = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        k.getGraphics().drawImage(b, 0, 0, null);
        return k;
    }

    /**
     * Hat sich im Bereich (x, y, Breite, Hoehe) sichtbar etwas getan? Raster wie ChatAnimation -- kleine Flackereien
     * zaehlen nicht. Paketoeffentlich fuer den Test.
     */
    static boolean bereichGeaendert(BufferedImage a, BufferedImage b, int[] bereich) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return true;
        int x0 = 0, y0 = 0, x1 = a.getWidth(), y1 = a.getHeight();
        if (bereich != null) {
            x0 = Math.max(0, bereich[0]);
            y0 = Math.max(0, bereich[1]);
            x1 = Math.min(a.getWidth(), bereich[0] + bereich[2]);
            y1 = Math.min(a.getHeight(), bereich[1] + bereich[3]);
        }
        long summe = 0;
        int punkte = 0;
        for (int y = y0; y < y1; y += 6) {
            for (int x = x0; x < x1; x += 6) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                summe += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                summe += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                summe += Math.abs((pa & 0xFF) - (pb & 0xFF));
                punkte += 3;
            }
        }
        return punkte > 0 && (summe / (double) punkte) >= 6.0;
    }

    // ---- Klicks an festen Stellen -----------------------------------------

    private static void tippe(BotContext ctx, int[] p, String was) {
        int x = sx(ctx, p[0]), y = sy(ctx, p[1]);
        Log.action("Klick", Sprache.t("Clan dungeon", WO) + ": " + was + "  [" + x + "," + y + "]");
        ctx.input().click(x, y);
    }

    private static int sx(BotContext ctx, int x) {
        int w = ctx.width();
        return w > 0 ? (int) Math.round(x * (double) w / BREITE) : x;
    }

    private static int sy(BotContext ctx, int y) {
        int h = ctx.height();
        return h > 0 ? (int) Math.round(y * (double) h / HOEHE) : y;
    }

    /** Fuer ClanVerliesPlan: die eigenen Texte (zuerst die Frage) -- das sind nie Antworten. */
    static List<String> eigeneTexte(int n) {
        List<String> l = new ArrayList<>();
        l.add(clanChatText(n));
        l.add(verliesText(n));
        l.add("Event quest - 1 round with a clan mate. Thanks!");   // Autors eigener Text vom 19.11. (Bild 20:36:30)
        l.add(DANKE);
        l.add(DANKE_CLAN);
        l.add(ABSCHIED);
        return l;
    }
}
