package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.util.Optional;
import de.hustlebot.core.Sprache;

/**
 * Fenster, die das Spiel von sich aus aufmacht und die den Bot aufhalten.
 *
 * Der Anlass war das taegliche Belohnungsfenster ("Daily reward"). Hustle
 * Castle startet jede Nacht um zwei Uhr neu, und beim ersten Betreten der
 * Burg danach liegt dieses Fenster ueber allem. Der Bot erkannte nichts
 * mehr, zaehlte drei Minuten lang "nichts erkannt" und gab dann auf:
 *
 *     02:01:04  Abschlussfenster schliessen
 *     02:01:22  Seit 12 Bildern nichts erkannt
 *     ...
 *     02:04:48  Der Bildschirm wird dauerhaft nicht erkannt. Bot wird gestoppt.
 *
 * Ein ganzer Lauf war damit vorbei -- wegen eines Fensters, das ein Klick
 * schliesst.
 *
 * Das Fenster kommt nicht puenktlich um zwei. Es kommt, wenn der Bot nach
 * zwei Uhr das ERSTE MAL in der Burg steht. Faengt er um 01:58 eine
 * Kathedrale an und kommt um 02:03 zurueck, kommt es eben um 02:03. Nach
 * der Uhrzeit zu gehen waere also falsch -- der Bot muss das Fenster sehen.
 *
 * Zwei Wege, beide gleichzeitig:
 *
 *   1. Die Vorlage "daily_reward". Ist sie da, wird das Fenster sofort
 *      erkannt und weggeklickt.
 *
 *   2. Der Notausgang: Wenn ueberhaupt nichts erkannt wird und irgendwo ein
 *      rotes X sitzt, wird es gedrueckt. Das hilft auch bei Fenstern, die
 *      wir noch gar nicht kennen.
 *
 * Der zweite Weg ist bewusst an "nichts erkannt" gekoppelt. Ein rotes X
 * einfach immer zu druecken waere gefaehrlich -- genau daran ist der Bot
 * schon einmal haengengeblieben: Das rote X des Teilnahmefensters wurde
 * fuer eine Werbung gehalten, und er schloss seinen eigenen Verlies-Eintritt.
 */
public final class Stoerfenster {

    /** Ueberschrift des Belohnungsfensters. */
    private static final String T_DAILY = "daily_reward";

    /** Das runde rote X oben rechts. */
    private static final String T_CLOSE_X = "close_x";

    /** Die Einblendung "You have received a reward" waehrend eines Ereignisses. */
    private static final String T_EVENT_REWARD = "event_reward";

    /**
     * Die Ueberschrift des Werbefensters -- nur die drei Woerter
     * "Running out of", ohne das Wort dahinter.
     *
     * Absicht: Es gibt mehrere dieser Fenster ("Running out of apples?",
     * "Running out of gold?"). Der gemeinsame Anfang passt auf alle, das
     * letzte Wort waere fuer jedes ein eigener Ausschnitt.
     */
    private static final String T_AD_HEADLINE = "ad_running_out";

    /** Das rote runde X dieses Fensters. Weitere Aussehen: ad_close_2, ad_close_3 ... */
    private static final String T_AD_CLOSE = "ad_close";

    /**
     * Wie weit unter der Schrift geklickt wird, in Bruchteilen der
     * Fensterhoehe.
     *
     * Die Schrift sitzt im oberen Drittel, die Knoepfe sitzen ganz unten.
     * Dreissig Prozent darunter landet sicher dazwischen -- genau in der
     * leeren Flaeche, die Autor rot eingekringelt hat.
     */
    private static final double KLICK_UNTER_SCHRIFT = 0.30;

    /** Der Text "you logged in from another device". */
    private static final String T_ANDERES_GERAET = "other_device";

    /** Der gruene Knopf "Try again" in der Mitte. */
    private static final String T_TRY_AGAIN = "try_again";

    /**
     * Wie oft "Try again" hoechstens gedrueckt wird, bevor der Bot wieder
     * anhaelt.
     *
     * Ist Autor mit dem Handy noch drin, kommt nach jedem Versuch dieselbe
     * Meldung. Ohne Grenze wuerde der Bot die ganze Nacht dagegen anrennen.
     */
    private static final int MAX_TRY_AGAIN = 3;

    /**
     * Ab wie vielen Bildern ohne Erkennung der Notausgang greift.
     *
     * Zwoelf Bilder sind rund acht Sekunden. Frueh genug, dass kaum Zeit
     * verlorengeht, spaet genug, dass ein kurzes Laden nicht schon als
     * "haengt" gilt.
     */
    public static final int NOTAUSGANG_AB = 12;

    private Stoerfenster() {
    }

    // ------------------------------------------------------------------
    //  "Du hast dich von einem anderen Geraet angemeldet"
    // ------------------------------------------------------------------
    /**
     * Merker: Der Bot wurde hinausgeworfen und hat deshalb angehalten.
     *
     * Warum ein Merker und keine Uhr: Solange der Bot pausiert, wird step()
     * gar nicht aufgerufen. Sieht der Modus die Meldung also ein zweites
     * Mal, kann das nur heissen, dass Autor inzwischen !weiter getippt hat.
     * Genau dann -- und nur dann -- soll "Try again" gedrueckt werden.
     */
    private static volatile boolean rausgeworfen = false;

    /** Code-Pruefung 22.10.: steht "anderes Geraet" noch offen (Autor am Handy)? Dann nichts von selbst starten. */
    public static boolean istRausgeworfen() {
        return rausgeworfen;
    }

    /** Wie oft seit dem Fortsetzen schon "Try again" gedrueckt wurde. */
    private static volatile int tryAgainKlicks = 0;

    /** Was der Bot mit dieser Meldung tun soll. */
    public enum Antwort {
        /** Die Meldung ist nicht da, alles normal. */
        NICHTS,
        /** Es wurde "Try again" gedrueckt. */
        GEKLICKT,
        /** Der Bot soll anhalten und auf Autor warten. */
        ANHALTEN
    }

    /**
     * Hustle Castle laesst ein Konto nur auf EINEM Geraet zu. Meldet Autor
     * sich mit dem Handy an, um kurz etwas nachzusehen, fliegt der Rechner
     * hinaus und es steht da:
     *
     *     "My lord, you logged in from another device.
     *      You cannot stay in the castle with multiple devices at once."
     *
     * Frueher lief der Bot dann ins Leere: Er erkannte nichts mehr, zaehlte
     * hoch und gab irgendwann auf -- der ganze Lauf war hin, nur weil Autor
     * dreissig Sekunden aufs Handy geschaut hat.
     *
     * Jetzt haelt er an und wartet. Sobald Autor "!weiter" tippt, drueckt der
     * Bot selbst auf "Try again" und macht dort weiter, wo er war.
     *
     * Kommt die Meldung danach gleich wieder -- weil das Handy noch drin ist
     * --, haelt er nach drei Versuchen wieder an, statt die ganze Nacht
     * dagegen anzurennen.
     */
    public static Antwort anderesGeraet(Mat screen, BotContext ctx, String woher) {
        Antwort a = anderesGeraetPruefen(screen, ctx, woher);
        if (a != Antwort.NICHTS) rauswurfUm = System.currentTimeMillis();
        return a;
    }

    /** Wann zuletzt ein Rauswurf / Spielfehler behandelt wurde (Code-Review 23.10.: der Clan-Krieg sah ihn sonst nie). */
    private static volatile long rauswurfUm = 0;

    public static long rauswurfUm() {
        return rauswurfUm;
    }

    private static Antwort anderesGeraetPruefen(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has(T_ANDERES_GERAET)) return spielfehler(screen, ctx, woher);
        if (!ctx.visible(screen, T_ANDERES_GERAET)) {
            // Kein Rauswurf -- vielleicht ein Verbindungsfehler ("Game error").
            Antwort fehler = spielfehler(screen, ctx, woher);
            if (fehler != Antwort.NICHTS) return fehler;
            // Meldung ist weg: Der naechste Rauswurf faengt wieder bei null an.
            rausgeworfen = false;
            tryAgainKlicks = 0;
            return Antwort.NICHTS;
        }

        // Erster Anblick: anhalten und Bescheid geben.
        if (!rausgeworfen) {
            rausgeworfen = true;
            tryAgainKlicks = 0;
            Log.alarm(woher, Sprache.t("The game reports: logged in from another device. " + "The bot stops and touches nothing. When you are done in the game, " + "press \"Resume\" in the control window or type !resume -- then it presses " + "\"Try again\" / \"Update\" itself and carries on where it was.",
                    "Das Spiel meldet: von einem anderen Geraet angemeldet. "
                    + "Der Bot haelt an und ruehrt nichts an. Wenn du im Spiel "
                    + "fertig bist, drueck \"Weiter\" im Steuerprogramm oder tippe !weiter -- dann drueckt er selbst auf "
                    + "\"Try again\" / \"Update\" und macht weiter, wo er war."));
            return Antwort.ANHALTEN;
        }

        // Wir sind wieder dran, also hat Autor fortgesetzt.
        if (tryAgainKlicks >= MAX_TRY_AGAIN) {
            Log.alarm(woher, Sprache.t("\"Try again\" was pressed " + tryAgainKlicks + " times and " + "the message keeps coming back. The phone is probably still in the " + "game. The bot stops again -- close the game on the phone " + "and then type !resume once more.",
                    "\"Try again\" wurde " + tryAgainKlicks + " Mal gedrueckt und "
                    + "die Meldung kommt immer wieder. Vermutlich ist das Handy noch im "
                    + "Spiel. Der Bot haelt wieder an -- schliess das Spiel auf dem Handy "
                    + "und tippe dann noch einmal !weiter."));
            tryAgainKlicks = 0;
            return Antwort.ANHALTEN;
        }

        tryAgainKlicks++;
        // Je nach Spielstand heisst der Knopf "Try again" oder "Update" (13.10.) -- beide gelten.
        if (ctx.clickIfVisible(screen, T_TRY_AGAIN, Sprache.t("Try again after the kick", "Try again nach Rauswurf"))
                || ctx.clickIfVisibleIfKnown(screen, "update_button", Sprache.t("Update after the kick", "Update nach Rauswurf"))) {
            Log.info(woher, Sprache.t("\"Try again\" / \"Update\" pressed (attempt " + tryAgainKlicks + " of " + MAX_TRY_AGAIN + "). The game is logging in again.",
                    "\"Try again\" / \"Update\" gedrueckt (Versuch " + tryAgainKlicks + " von "
                    + MAX_TRY_AGAIN + "). Das Spiel meldet sich neu an."));
            ctx.sleepUntilChanged(4000);
            return Antwort.GEKLICKT;
        }

        Log.warn(woher, Sprache.t("The message is there, but the \"Try again\" button was not " + "found. Please capture it with Capture-Images.exe as try_again.",
                "Die Meldung ist da, aber der Knopf \"Try again\" wurde nicht "
                + "gefunden. Bitte ihn mit Capture-Images.exe als try_again aufnehmen."));
        return Antwort.ANHALTEN;
    }

    /**
     * "Game error (7.4.0) -- Please check your Internet connection" mit dem
     * gruenen Knopf "Try again" (Langlauf 28.09., 16:02): Das Internet war kurz
     * weg. Der Bot drueckte 150 Bilder lang das rote X DAHINTER und gab dann
     * mit "Bildschirm nicht erkannt" auf. Jetzt: "Try again" in der Mitte des
     * Bildes druecken -- hoechstens 5 Mal in 10 Minuten, dann Bescheid geben.
     */
    private static int spielfehlerKlicks = 0;
    private static long spielfehlerSeit = 0;

    static Antwort spielfehler(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has(T_TRY_AGAIN)) return Antwort.NICHTS;
        // Nur in der Bildmitte suchen, wo das Meldefenster steht (Tempo: die
        // Suche uebers ganze Bild kostete ~33 ms, jede Sekunde).
        int w = ctx.width(), hh = ctx.height();
        var mitte = new org.opencv.core.Rect(w / 4, (int) (hh * 0.63) - hh / 7 - 60, w / 2, 2 * hh / 7 + 120);
        var knopf = ctx.matcher().findImBereich(screen, T_TRY_AGAIN, mitte,
                de.hustlebot.core.Matcher.DEFAULT_THRESHOLD);
        if (knopf.isEmpty()) return Antwort.NICHTS;
        var h = knopf.get();
        // Nur der Knopf eines Meldefensters in der Bildmitte.
        if (Math.abs(h.centerX() - ctx.width() / 2) > ctx.width() / 8
                || Math.abs(h.centerY() - ctx.height() * 0.63) > ctx.height() / 7) {
            return Antwort.NICHTS;
        }
        // "Try again" steht auch auf der Meldung "von einem anderen Geraet angemeldet" (13.10., 11:42: erst
        // "Try again" gedrueckt, 18 s spaeter angehalten -- Autor war schon vom Handy geflogen). Dann nicht.
        if (autorIstDrin(screen, ctx)) {
            nichtDruecken(woher, "Try again");
            return Antwort.NICHTS;
        }
        long jetzt = System.currentTimeMillis();
        if (jetzt - spielfehlerSeit > 10 * 60_000L) {
            spielfehlerSeit = jetzt;
            spielfehlerKlicks = 0;
        }
        if (++spielfehlerKlicks > 5) {
            if (spielfehlerKlicks == 6) {
                Log.alarm(woher, Sprache.t("The game keeps reporting an error (internet?). \"Try again\" " + "was pressed 5 times -- please check.",
                        "Das Spiel meldet immer wieder einen Fehler (Internet?). \"Try again\" "
                        + "wurde 5 Mal gedrueckt -- bitte nachsehen."));
            }
            ctx.sleep(5000);
            return Antwort.GEKLICKT;
        }
        Log.warn(woher, Sprache.t("Game error (internet?) -- \"Try again\" pressed (" + spielfehlerKlicks + ". time).",
                "Spielfehler (Internet?) -- \"Try again\" gedrueckt (" + spielfehlerKlicks + ". Mal)."));
        ctx.input().click(h);
        ctx.sleepUntilChanged(5000);
        return Antwort.GEKLICKT;
    }

    /**
     * Die Belohnungseinblendung des laufenden Ereignisses wegtippen.
     *
     * Waehrend eines Ereignisses gibt es fuer fast jede Handlung im Spiel
     * etwas -- Haemmer, Lupen und aehnliches. Die Einblendung "You have
     * received a reward" kann deshalb NACH JEDEM Kampf kommen: nach einem
     * Arena-Kampf genauso wie nach einem Verlies oder einer Kathedrale.
     *
     * Sie hat keinen Knopf zum Schliessen. Man tippt irgendwo hin, und sie
     * geht weg. Autor: "den bitte einfach wegdruecken. einfach irgendwo
     * hindruecken und weiter machen mit dem ausgewaehlten modus."
     *
     * WOHIN GEKLICKT WIRD. Nicht auf eine feste Stelle im Fenster -- was
     * dort liegt, haengt vom Modus dahinter ab, und unten sitzen "Home" und
     * zwei weitere Knoepfe. Geklickt wird stattdessen relativ zur
     * Einblendung selbst: ein gutes Stueck UNTER ihrer Schrift, mitten in
     * die leere Flaeche, die Autor rot eingekringelt hat. Damit stimmt die
     * Stelle auch dann, wenn die Einblendung woanders sitzt.
     *
     * Diese Pruefung steht bewusst hier in Stoerfenster und nicht in den
     * Modi: alle vier rufen die Methode ohnehin schon auf, und die
     * Einblendung geht alle vier gleichermassen an.
     *
     * @return true, wenn geklickt wurde
     */
    public static boolean ereignisBelohnung(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has(T_EVENT_REWARD)) return false;

        var treffer = ctx.find(screen, T_EVENT_REWARD);
        if (treffer.isEmpty()) return false;

        int x = treffer.get().centerX();
        int y = treffer.get().centerY() + (int) (ctx.height() * KLICK_UNTER_SCHRIFT);

        // Nicht aus dem Fenster hinausklicken, falls die Schrift tief sitzt.
        int grenze = (int) (ctx.height() * 0.80);
        if (y > grenze) y = grenze;

        Log.info(woher, Sprache.t("Event reward shown. Tapping it away " + "at " + x + "," + y + ".",
                "Belohnung des Ereignisses eingeblendet. Wird weggetippt "
                + "bei " + x + "," + y + "."));
        ctx.input().click(x, y);
        ctx.sleepUntilChanged(2000);
        return true;
    }

    // ------------------------------------------------------------------
    //  Werbefenster "Running out of apples?"
    // ------------------------------------------------------------------
    /**
     * Wie weit rechts vom Text das rote X sitzt, in Bruchteilen der
     * Fensterbreite -- und wie weit darueber, in Bruchteilen der Hoehe.
     *
     * Gemessen an Autors Bild: Fenster 1157 x 833, die Ueberschrift steht
     * mittig bei etwa 697 / 311, das rote X in der oberen rechten Ecke des
     * Werbefensters bei etwa 879 / 277.
     *
     * Das ist nur der Notnagel, falls die Vorlage des X fehlt oder nicht
     * passt. Ist sie da, wird sie benutzt -- eine gemessene Stelle ist immer
     * besser als eine gerechnete.
     */
    private static final double X_RECHTS_VOM_TEXT = 0.157;
    private static final double X_UEBER_DEM_TEXT  = 0.041;

    /** Der "Map"-Knopf unten links -- daran ist die Burg zu erkennen. */
    private static final String T_MAP_BUTTON = "map_button";

    /**
     * Ist die Burg im Hintergrund zu sehen?
     *
     * Der "Map"-Knopf sieht nicht immer gleich aus, deshalb zaehlt jede
     * Vorlage, deren Name mit map_button anfaengt: map_button_alt,
     * map_button_2 und so weiter.
     */
    private static boolean burgImHintergrund(Mat screen, BotContext ctx) {
        for (String name : ctx.matcher().names()) {
            if (!name.equals(T_MAP_BUTTON) && !name.startsWith(T_MAP_BUTTON + "_")) continue;
            if (ctx.visible(screen, name)) return true;
        }
        return false;
    }

    /** Wann zuletzt auf die gerechnete Stelle geklickt wurde. */
    private static volatile long letzterNotnagel = 0L;

    /** Mindestabstand zwischen zwei Notnagel-Klicks. */
    private static final long NOTNAGEL_PAUSE_MS = 4000;

    /**
     * Das Werbefenster in der Burg schliessen.
     *
     * "Running out of apples? Watch an ad to get some food" legt sich in der
     * Burg ueber alles. Es kommt, wenn wenig Essen da ist -- also gerade
     * dann, wenn der Bot ohnehin viel laeuft.
     *
     * WARUM DAS FRUEHER NICHT REICHTE. Es gab die Pruefung schon, aber nur
     * im Verlies und in der Kathedrale, und sie haing am "Map"-Knopf unten
     * links: Nur wenn der zu sehen war, durfte das rote X gedrueckt werden.
     * Diese Fessel hatte einen guten Grund -- das rote runde X sieht bei
     * JEDEM Fenster gleich aus, auch beim Teilnahmefenster des Verlieses,
     * und der Bot hat sich damit einmal selbst wieder hinausgeworfen.
     *
     * Nur: Ein Knopf im Hintergrund ist ein schwacher Beweis. Verdeckt das
     * Werbefenster ihn, oder laeuft gerade Arena oder Clankrieg -- die die
     * Pruefung gar nicht hatten --, steht der Bot davor und sieht nichts.
     * Genau das ist Autor passiert.
     *
     * Jetzt haengt es an der Ueberschrift des Fensters selbst. Die steht nur
     * in diesem einen Fenster; wer sie sieht, weiss sicher, was da liegt.
     * Ein starker Beweis statt eines schwachen -- und weil die Pruefung in
     * belohnungWegklicken() mitlaeuft, gilt sie in allen vier Modi.
     *
     * @return true, wenn geklickt wurde
     */
    /**
     * Darf das rote runde X der Werbung gedrueckt werden? Nur, wenn die Ueberschrift
     * "Running out of" zu sehen ist -- oder ihr Bild noch gar nicht aufgenommen wurde.
     * Fuer die eigenen Werbe-Klicks von Kathedrale und Verlies (29.09., Diamanten-Shop).
     */
    public static boolean werbungSicher(Mat screen, BotContext ctx) {
        return !ctx.matcher().has(T_AD_HEADLINE) || ctx.find(screen, T_AD_HEADLINE).isPresent();
    }

    public static boolean werbefenster(Mat screen, BotContext ctx, String woher) {
        var kopf = ctx.matcher().has(T_AD_HEADLINE)
                ? ctx.find(screen, T_AD_HEADLINE)
                : java.util.Optional.<de.hustlebot.core.Matcher.Hit>empty();

        // Die Ueberschrift ist noch nicht aufgenommen? Dann gilt der alte,
        // schwaechere Beweis: das rote X nur druecken, wenn im Hintergrund
        // die BURG zu sehen ist.
        //
        // Der Ruecken dahinter: Das rote runde X sieht bei jedem Fenster
        // gleich aus, auch beim Teilnahmefenster des Verlieses -- und das
        // deckt den ganzen Bildschirm, der "Map"-Knopf ist dort weg. Das
        // Werbefenster verdeckt nur die Mitte, der Knopf bleibt sichtbar.
        //
        // Schwaecher als die Ueberschrift, aber besser als nichts: So laeuft
        // der Schutz auch heute Nacht schon, ohne dass Autor erst
        // ad_running_out aufnehmen muss.
        // Ist die Ueberschrift aufgenommen, aber nicht zu sehen, liegt KEIN Werbefenster
        // da (29.09., 19:36: ad_close_2 passte in der Burg mit 0,87 auf einen Knopf oben
        // rechts -- der Klick oeffnete den Diamanten-Shop, der Bot suchte dort nach der
        // Weltkarte und gab auf). Der schwache Beweis gilt nur ohne Ueberschrift-Bild.
        if (kopf.isEmpty() && (ctx.matcher().has(T_AD_HEADLINE) || !burgImHintergrund(screen, ctx))) return false;

        // Jedes bekannte Aussehen des roten X versuchen.
        for (String name : ctx.matcher().names()) {
            if (!name.equals(T_AD_CLOSE) && !name.startsWith(T_AD_CLOSE + "_")) continue;
            if (ctx.clickIfVisible(screen, name, Sprache.t("Close the ad window (" + name + ")", "Werbefenster schliessen (" + name + ")"))) {
                Log.info(woher, Sprache.t("The ad window \"Running out of ...\" was in the way " + "and got closed.",
                        "Das Werbefenster \"Running out of ...\" lag im Weg "
                        + "und wurde geschlossen."));
                ctx.sleepUntilChanged(1500);
                return true;
            }
        }

        // Ohne Ueberschrift wird nicht auf gut Glueck irgendwohin geklickt.
        // Wo das Fenster sitzt, weiss der Bot dann naemlich gar nicht.
        if (kopf.isEmpty()) return false;

        // Notnagel: Die Ueberschrift steht mittig im Fenster, das X in dessen
        // oberer rechter Ecke. Daneben liegt nur Hintergrund -- ein Fehlgriff
        // kostet also nichts. Der "Watch"-Knopf sitzt weit darunter.
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzterNotnagel < NOTNAGEL_PAUSE_MS) return false;
        letzterNotnagel = jetzt;

        int x = kopf.get().centerX() + (int) (ctx.width()  * X_RECHTS_VOM_TEXT);
        int y = kopf.get().centerY() - (int) (ctx.height() * X_UEBER_DEM_TEXT);
        x = Math.min(x, ctx.width()  - 2);
        y = Math.max(y, 2);

        Log.warn(woher, Sprache.t("The ad window is there, but its red X matches no " + "template. Pressing at " + x + "," + y + " -- that is where it " + "usually sits. Please capture ad_close_2 with Capture-Images.exe.",
                "Das Werbefenster ist da, aber sein rotes X passt auf keine "
                + "Vorlage. Es wird bei " + x + "," + y + " gedrueckt -- dort sitzt es "
                + "normalerweise. Bitte ad_close_2 mit Capture-Images.exe aufnehmen."));
        ctx.input().click(x, y);
        ctx.sleepUntilChanged(1500);
        return true;
    }

    /**
     * Ist das taegliche Belohnungsfenster zu sehen? Dann wegklicken.
     *
     * Die Belohnung ist damit nicht verloren -- sie wird beim Anzeigen
     * gutgeschrieben, das Fenster zeigt sie nur an. (Von Autor bestaetigt.)
     *
     * Seit dem Ereignis prueft diese Methode zusaetzlich die Einblendung
     * "You have received a reward" mit, und seit Autors Bild vom
     * 4. September auch das Werbefenster "Running out of apples?". So
     * mussten die vier Modi nicht angefasst werden -- sie rufen
     * belohnungWegklicken() ohnehin alle auf.
     *
     * @return true, wenn geklickt wurde
     */
    /**
     * Der Belohnungs-Text des Events ("You have received a reward").
     *
     * Autor, 19.09.: "dieser text kann auch nach einem dungeon auftreten oder
     * wenn der bot in die burg geht, nach einem clan krieg" -- deshalb steht
     * er hier bei den Stoerfenstern und nicht in der Kathedrale. Jeder Modus
     * ruft belohnungWegklicken(), damit greift es ueberall.
     *
     * Einen Knopf gibt es nicht: Der Text geht weg, wenn man UNTER ihn klickt.
     * Geklickt wird deshalb nicht der Text selbst -- da liegen die
     * Belohnungsbilder -- sondern ein Stueck darunter.
     */
    private static final String T_REWARD_TEXT = "dc_reward_text";

    /**
     * Wann der Belohnungs-Text zuletzt weggetippt wurde. Kurz danach verschluckt
     * das Spiel Klicks (Test 28.09.: die erste Lupe nach dem Neumischen ging in
     * 15 von 15 Stages verloren, weil der Text ueber diesen Weg weg ging).
     */
    private static volatile long textWeggeklicktUm = 0;

    public static long textWeggeklicktUm() {
        return textWeggeklicktUm;
    }

    public static boolean ereignisTextWegklicken(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has(T_REWARD_TEXT)) return false;

        // Strenge Schwelle: hier wird blind unter den Treffer geklickt, ein
        // Fehlgriff waere ein Klick irgendwo ins Spiel.
        java.util.Optional<de.hustlebot.core.Matcher.Hit> treffer =
                ctx.matcher().find(screen, T_REWARD_TEXT, 0.92);
        if (treffer.isEmpty()) {
            // Messung 24.09.: Nach jedem Kathedralen-Run vergingen ~3,5 s bis
            // zum Wegklicken. Steht der Text schon halb da (Einblenden)?
            ctx.matcher().find(screen, T_REWARD_TEXT, 0.60).ifPresent(h ->
                    Log.debug(woher, "Belohnungs-Text fast erkannt: "
                            + String.format("%.3f", h.score())));
            return false;
        }

        de.hustlebot.core.Matcher.Hit b = treffer.get();
        int klickY = Math.max(0, Math.min(ctx.height() - 12, b.centerY() + 120));
        Log.info(woher, de.hustlebot.core.Sprache.t("Event reward text. Clicking below it so it goes away.", "Belohnungs-Text des Events. Es wird darunter geklickt, "
                + "damit er weggeht."));
        ctx.input().click(b.centerX(), klickY, 8);
        textWeggeklicktUm = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    /**
     * Nachfrage des Spiels UEBER DER TASCHE (30.09., 19:45): Der Bot las die Nahrung falsch (0 statt
     * 1,19 Mio) und wollte eine Kiste oeffnen, obwohl das Lager fast voll war. Das Spiel fragte nach
     * (Yes/No), der Bot klickte 170 s auf das X dahinter -- und die Kathedrale drueckte danach "Yes":
     * rund 1 Mio Nahrung ging ueber die Lagergrenze verloren. Erkannt wird die Nachfrage am "Yes"
     * ZUSAMMEN mit dem Titel der Tasche dahinter. Hier wird nicht "Yes" gedrueckt: "No" sitzt gespiegelt
     * rechts daneben (im Spiel noch nicht bestaetigt). Greift das fuenfmal in einer Minute nicht, gibt
     * diese Hilfe auf -- dann gilt wieder, was der Aufrufer sonst tut, damit der Ablauf nicht steht.
     */
    private static long lagerFrageSeit = Long.MIN_VALUE / 2;   // "noch nie" (ohne Ueberlauf)
    private static int lagerFrageVersuche;

    /**
     * Autor 17.10. (Bild "Warning -- The chest contains resources your storage facilities may not have room for. Are you
     * sure you want to open it?"): "Drueck Yes, wenn das kommt." Vorher wurde abgelehnt (lagerVollFrage) bzw. -- ueber dem
     * Kisten-Bild ohne Tasche dahinter -- gar nicht erkannt, und der Bot drueckte 12-mal "Close" hinter dem Fenster.
     * Yes nur, wenn der Text ueber dem Knopf wirklich vom Lager spricht (nie bei einer Kauffrage).
     */
    public static boolean lagerUeberlaufJa(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has("dc_yes")) return false;
        Optional<Matcher.Hit> yes = ctx.matcher().find(screen, "dc_yes", 0.88);
        if (yes.isEmpty()) return false;
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return false;
        int x = Math.max(0, yes.get().centerX() - 160), y = Math.max(0, yes.get().centerY() - 260);
        int w = Math.min(bild.getWidth() - x, 620), h = Math.min(bild.getHeight() - y, 220);
        if (w < 50 || h < 50) return false;
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, w, h)))
                .toLowerCase(java.util.Locale.ROOT);
        boolean lager = text.contains("storage") || text.contains("room for") || text.contains("not have room");
        boolean kauf = text.contains("buy") || text.contains("gem") || text.contains("diamond") || text.contains("purchase");
        if (!lager || kauf) return false;
        ctx.klick(yes.get(), woher + Sprache.t(": storage may overflow -- open anyway (Yes)",
                ": Lager koennte ueberlaufen -- trotzdem oeffnen (Yes, Autor 17.10.)"));
        ctx.sleepUntilChanged(1200);
        return true;
    }

    public static boolean lagerVollFrage(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has("dc_yes") || !ctx.visibleIfKnown(screen, "bag_title")) return false;
        Optional<Matcher.Hit> yes = ctx.matcher().find(screen, "dc_yes", 0.90);
        if (yes.isEmpty()) return false;
        long jetzt = jetztMs();
        if (jetzt - lagerFrageSeit > 60_000) {
            lagerFrageSeit = jetzt;
            lagerFrageVersuche = 0;
        }
        if (++lagerFrageVersuche > 5) {
            if (lagerFrageVersuche == 6) {
                Log.warn(woher, Sprache.t("The prompt over the bag could not be declined five times (is \"No\" " + "somewhere else?). Please capture an image of it -- the bot carries on as before.",
                        "Die Nachfrage ueber der Tasche liess sich fuenfmal nicht ablehnen (sitzt \"No\" "
                        + "woanders?). Bitte ein Bild davon aufnehmen -- der Bot macht weiter wie frueher."));
            }
            return false;
        }
        int x = ctx.width() - yes.get().centerX(), y = yes.get().centerY();
        Log.action("Klick", Sprache.t(woher + ": decline the prompt over the bag (No, mirrored from Yes)  [" + x + "," + y + "]",
                woher + ": Nachfrage ueber der Tasche ablehnen (No, gespiegelt zu Yes)  [" + x + "," + y + "]"));
        Log.warn(woher, Sprache.t("The game asks when opening a food chest (storage too full?). " + "Declined -- nothing gets opened that would be lost.",
                "Das Spiel fragt beim Oeffnen einer Nahrungskiste nach (Lager zu voll?). "
                + "Abgelehnt -- es wird nichts geoeffnet, was verloren ginge."));
        ctx.input().click(x, y, 2);
        return true;
    }

    /**
     * AFK-Rauswurf: "My lord, are you still here? Refresh the game ..." mit dem
     * gruenen Knopf "Update". Er laedt das Spiel neu und bringt in die Burg.
     *
     * Verlies, Kathedrale, Arena, Event und Clan-Krieg hatten das schon je fuer
     * sich; Leuchtturm, Kolosseum, Titanen-Turm und Clan-Laden nicht -- im
     * Testlauf vom 28.09. brachen alle drei mit "Bildschirm nicht erkannt" ab,
     * weil genau dieses Fenster offen war.
     *
     * @return true = gedrueckt (danach 20 s gewartet, das Spiel laedt neu)
     */
    public static boolean afkUpdate(Mat screen, BotContext ctx, String woher) {
        if (!updateDruecken(screen, ctx, woher)) {
            // Auch der Spielfehler ("Game error", Internet weg) -- Try again.
            return spielfehler(screen, ctx, woher) != Antwort.NICHTS;
        }
        ctx.sleep(20000);
        return true;
    }

    // ---- Server-Wartung (Bericht #K4AL, Ben 26.10., 06:00) ------------------------------------------------------
    // "Server maintenance -- Game servers are closed for prevention ..." mit gruenem "Retry". Der Bot kannte das Fenster
    // nicht: nach 150 Bildern "Vorlagen fehlen" und Stopp -- nach der Wartung stand er still. Jetzt: erkennen
    // (Texterkennung, nur wenn der Modus seit 8 s keinen Fortschritt hat), warten, alle 3 Minuten "Retry".
    private static final long WARTUNG_PRUEFEN_MS = 15_000, WARTUNG_RETRY_MS = 180_000;
    private static volatile long wartungGeprueftUm = 0, wartungSeit = 0, wartungRetryUm = 0;

    /** Seit wann die Wartung steht (0 = keine) -- nach 3 h gilt Warten nicht mehr als Fortschritt (Haenger-Meldung). */
    public static long wartungSeitMs() {
        return wartungSeit == 0 ? 0 : System.currentTimeMillis() - wartungSeit;
    }

    /** true = das Wartungsfenster steht im Bild -- der Bot wartet (alle 3 Minuten "Retry"). */
    public static boolean serverWartung(BotContext ctx, String woher, long ohneFortschrittS) {
        long jetzt = System.currentTimeMillis();
        if (wartungSeit == 0) {
            // Code-Review 26.10.: nur alle 20 s lesen (Texterkennung kostet) -- Wartung dauert ohnehin Stunden.
            if (ohneFortschrittS < 8 || jetzt - wartungGeprueftUm < 20_000) return false;
        } else if (jetzt - wartungGeprueftUm < WARTUNG_PRUEFEN_MS) {
            return true;
        }
        wartungGeprueftUm = jetzt;
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return wartungSeit != 0;
        String text = textIn(bild, 0.15, 0.10, 0.70, 0.35);
        // Code-Review 26.10.: "Server maintenance in 30 min" allein ist keine Wartung -- das Fenster sagt auch "closed"/"Retry".
        boolean da = text.contains("maintenance") && (text.contains("closed") || text.contains("retry"));
        if (!da) {
            if (wartungSeit != 0) {
                Log.info(woher, Sprache.t("Server maintenance is over after " + (jetzt - wartungSeit) / 60_000 + " min -- carrying on.",
                        "Server-Wartung nach " + (jetzt - wartungSeit) / 60_000 + " min vorbei -- weiter geht's."));
            }
            wartungSeit = 0;
            return false;
        }
        if (wartungSeit == 0) {
            wartungSeit = jetzt;
            wartungRetryUm = jetzt;
            Log.warn(woher, Sprache.t("Server maintenance: the game servers are closed. The bot waits and presses \"Retry\" every 3 minutes.",
                    "Server-Wartung: die Spielserver sind zu. Der Bot wartet und drueckt alle 3 Minuten \"Retry\"."));
            return true;
        }
        if (jetzt - wartungRetryUm >= WARTUNG_RETRY_MS) {
            wartungRetryUm = jetzt;
            // Nur klicken, wenn an der Stelle wirklich "Retry" steht (darunter liegt "Go to" -> Facebook).
            if (textIn(bild, 0.36, 0.33, 0.28, 0.13).contains("retry")) {
                ctx.input().click((int) (bild.getWidth() * 0.50), (int) (bild.getHeight() * 0.39), 6);
                Log.action("Klick", woher + Sprache.t(": Server maintenance -- Retry", ": Server-Wartung -- Retry"));
            }
        }
        return true;
    }

    private static String textIn(java.awt.image.BufferedImage bild, double x, double y, double w, double h) {
        int px = (int) (bild.getWidth() * x), py = (int) (bild.getHeight() * y);
        int pw = Math.min(bild.getWidth() - px, (int) (bild.getWidth() * w)), ph = Math.min(bild.getHeight() - py, (int) (bild.getHeight() * h));
        if (pw < 20 || ph < 10) return "";
        try {
            return String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(px, py, pw, ph)))
                    .toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** Steht "you logged in from another device" im Bild? Dann hat sich der Spieler selbst angemeldet. */
    public static boolean autorIstDrin(Mat screen, BotContext ctx) {
        return ctx.matcher().has(T_ANDERES_GERAET) && ctx.visible(screen, T_ANDERES_GERAET);
    }

    private static volatile long nichtGedruecktGemeldetUm = 0;

    /** Einmal je Minute ins Protokoll: Der Knopf wird absichtlich NICHT gedrueckt. */
    private static void nichtDruecken(String woher, String knopf) {
        long jetzt = System.currentTimeMillis();
        if (jetzt - nichtGedruecktGemeldetUm < 60_000) return;
        nichtGedruecktGemeldetUm = jetzt;
        Log.info(woher, "\"" + knopf + "\" is on the \"logged in from another device\" message -- NOT pressed (you are in the "
                + "game yourself). The bot waits for Resume / !weiter.");
    }

    /**
     * Den gruenen "Update"-Knopf druecken -- AUSSER dahinter steht "von einem anderen Geraet angemeldet" (Hauptbot
     * 62b1968): sonst wirft der Bot den Spieler vom Handy. Dann haelt anderesGeraet() an, bis der Spieler Weiter drueckt.
     *
     * @return true = gedrueckt (ohne zu warten)
     */
    public static boolean updateDruecken(Mat screen, BotContext ctx, String woher) {
        if (!ctx.visibleIfKnown(screen, "update_button")) return false;
        if (autorIstDrin(screen, ctx)) {
            nichtDruecken(woher, "Update");
            return false;
        }
        boolean gedrueckt = ctx.clickIfVisibleIfKnown(screen, "update_button", woher + ": reconnect (Update)");
        if (gedrueckt) updateGedruecktUm = System.currentTimeMillis();
        return gedrueckt;
    }

    /** Wann zuletzt "Update" gedrueckt wurde -- danach laedt das Spiel ~20 s neu (kein Stillstand, 22.10. nachts). */
    private static volatile long updateGedruecktUm = 0;

    public static long updateGedruecktUm() {
        return updateGedruecktUm;
    }

    /**
     * Bildfehler-Suche 30.10. (Bericht #ETD4, 28.10. 05:02): Die Kathedrale drueckt "Update" ueber ihren eigenen Klick
     * (nicht ueber updateDruecken) und wartet 20 s -- die Zeit wurde nicht gemerkt, der Motor meldete "No progress for
     * 20 s" und schickte Autor einen Haenger-Bericht. Darum merkt sich jetzt JEDER Klick auf "update_button" die Zeit,
     * egal von wo (BotContext ruft das bei jedem Klick auf eine Vorlage).
     */
    static void klickGemacht(String vorlage) {
        if ("update_button".equals(vorlage)) updateGedruecktUm = System.currentTimeMillis();
    }

    /** Bis wann das Hammer-Event selbst unterwegs ist (dann braucht es das Fenster "Active events"). */
    private static volatile long eventUnterwegsBis = 0;

    /** Ruft das Hammer-Event in jedem Bild auf: solange es laeuft, bleibt "Active events" offen. */
    public static void eventUnterwegs() {
        eventUnterwegsBis = System.currentTimeMillis() + 20_000;
    }

    /**
     * Das Fenster "Active events" (Kalender, "Harvest Treasure Hunt!" mit gruenem
     * "View") -- kommt, wenn der Bot sich verklickt hat (Autor, 29.09.): mit dem roten
     * X oben rechts schliessen. Nicht, solange das Hammer-Event selbst dorthin will.
     */
    static boolean aktiveEventsSchliessen(Mat screen, BotContext ctx, String woher) {
        if (System.currentTimeMillis() < eventUnterwegsBis) return false;
        if (!ctx.matcher().has("ev_view") || !ctx.matcher().has("close_x")) return false;
        if (!ctx.visible(screen, "ev_view")) return false;
        var x = ctx.find(screen, "close_x");
        if (x.isEmpty() || x.get().centerX() < ctx.width() * 0.80 || x.get().centerY() > ctx.height() * 0.35) return false;
        Log.info(woher, Sprache.t("Window \"Active events\" (misclick) -- closing it with the red X.",
                "Fenster \"Active events\" (verklickt) -- wird mit dem roten X geschlossen."));
        ctx.input().click(x.get());
        ctx.sleepUntilChanged(1200);
        return true;
    }

    public static boolean belohnungWegklicken(Mat screen, BotContext ctx, String woher) {
        if (ereignisTextWegklicken(screen, ctx, woher)) return true;
        if (aktiveEventsSchliessen(screen, ctx, woher)) return true;
        if (ereignisBelohnung(screen, ctx, woher)) return true;
        if (werbefenster(screen, ctx, woher)) return true;

        if (!ctx.matcher().has(T_DAILY)) return false;
        if (!ctx.visible(screen, T_DAILY)) return false;

        Log.info(woher, Sprache.t("The daily reward window is open. It gets " + "closed -- the reward is not lost by that.",
                "Das taegliche Belohnungsfenster ist offen. Es wird "
                + "geschlossen -- die Belohnung ist dadurch nicht verloren."));

        // Jedes rote X probieren, das der Bot kennt.
        //
        // Autor: "kann auch mehrmals hintereinander kommen." Das ist ohnehin
        // abgedeckt -- die Pruefung laeuft in jedem Bild neu, also wird auch
        // ein zweites und drittes Fenster weggeklickt. Was fehlte, war die
        // Auswahl beim X selbst: Die vier aufgenommenen roten X sehen sich
        // zum Verwechseln aehnlich, aber jedes passt an seiner Stelle etwas
        // besser. Warum nur eines davon versuchen?
        for (String x : java.util.List.of(T_CLOSE_X, "cw_close_x", "ar_close_x",
                                          "dc_close_x", "dg_close_x")) {
            if (ctx.clickIfVisibleIfKnown(screen, x, Sprache.t("Close the reward window (" + x + ")", "Belohnungsfenster schliessen (" + x + ")"))) {
                ctx.sleepUntilChanged(1500);
                return true;
            }
        }

        Log.warn(woher, Sprache.t("The reward window is there, but no red X was " + "found. Please recapture close_x with Capture-Images.exe.",
                "Das Belohnungsfenster ist da, aber kein rotes X wurde "
                + "gefunden. Bitte close_x mit Capture-Images.exe neu aufnehmen."));
        return false;
    }

    /**
     * Zeitstempel fuer die Fristen unten, in ms aus System.nanoTime(): Die Windows-Uhr
     * kann beim Zeitabgleich springen, nanoTime nicht.
     */
    private static long jetztMs() {
        return System.nanoTime() / 1_000_000L;
    }
    /** "Noch nie" -- so lange her, dass jede Frist abgelaufen ist (ohne Ueberlauf). */
    private static final long LANGE_HER = Long.MIN_VALUE / 4;

    /** Wann der Bot zuletzt eine fertige Verlies-Reise mit "End journey" beendet hat. */
    private static volatile long endJourneyGeklicktUm = LANGE_HER;
    /** Wann der Bot zuletzt beim Verlassen "Yes" gedrueckt hat (Nachfrage oder Warnung). */
    private static volatile long nachfrageBestaetigtUm = LANGE_HER;
    /** So lange nach dem eigenen "End journey" darf das gruene "Yes" gedrueckt werden. */
    private static final long NACHFRAGE_FRIST_MS = 10_000;
    /**
     * So lange nach dem "Yes" gehoeren Abschlussfenster und Quit-Warnung noch zum
     * Verlassen -- und so lange wird der (abgedunkelt weiter passende) Knopf dahinter
     * nicht noch einmal gedrueckt.
     */
    private static final long ABSCHLUSS_FRIST_MS = 20_000;
    /** Wie CathedralMode.END_JOURNEY_MIN_SCORE: eine Verwechslung mit dem roten bricht ab. */
    private static final double GRUEN_MIN = 0.88;

    /**
     * Der GRUENE "End journey", wenn er sicher gruen ist -- sonst leer. Gemeinsame
     * Regel fuer Verlies (min 0,96), Kathedrale (0,88) und die Reste vom Verlies
     * (29.09.): Die beste der gruenen Vorlagen muss mindestens {@code min} erreichen
     * UND besser passen als der rote "End journey". Der rote passt auf die gruene
     * Vorlage fast genauso gut (Verlies stieg einmal bei 93 % aus, die Kathedrale
     * waere mittendrin abgebrochen). Beide Vorlagen werden immer in Farbe gesucht.
     */
    public static Optional<Matcher.Hit> gruenerEndJourney(Mat screen, BotContext ctx, double min,
                                                          String... gruenVorlagen) {
        Optional<Matcher.Hit> gruen = Optional.empty();
        for (String name : gruenVorlagen) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> treffer = ctx.matcher().find(screen, name, 0.05);
            if (treffer.isPresent()
                    && (gruen.isEmpty() || treffer.get().score() > gruen.get().score())) {
                gruen = treffer;
            }
        }
        if (gruen.isEmpty() || gruen.get().score() < min) return Optional.empty();
        double rot = ctx.matcher().has("end_journey")
                ? ctx.matcher().find(screen, "end_journey", 0.05).map(Matcher.Hit::score).orElse(0.0)
                : 0;
        return gruen.get().score() > rot ? gruen : Optional.empty();
    }

    /**
     * Fertige Verlies-Reise (100 %, gruener "End journey") beenden -- Rest vom Verlies,
     * wenn ein anderer Modus darin anfaengt (Leuchtturm 29.09., 20:38) oder der Motor
     * vor einem Besuch in die Burg geht. Die Nachfrage danach bestaetigt
     * {@link #verliesNachfrageBestaetigen}, Abschlussfenster und Warnung raeumt
     * {@link #verliesAbschlussWegklicken} ab.
     */
    public static boolean fertigeVerliesReiseBeenden(Mat screen, BotContext ctx, String grund) {
        // Nach dem "Yes" liegt das Abschlussfenster noch ueber dem Knopf.
        if (jetztMs() - nachfrageBestaetigtUm < ABSCHLUSS_FRIST_MS) return false;
        // Nicht haemmern: Kam keine Nachfrage (Klick verschluckt), erst nach 5 s noch einmal.
        if (jetztMs() - endJourneyGeklicktUm < 5_000) return false;
        Optional<Matcher.Hit> gruen = gruenerEndJourney(screen, ctx, GRUEN_MIN, "end_journey_green");
        if (gruen.isEmpty()) return false;
        ctx.klick(gruen.get(), Sprache.t("End the finished dungeon journey (" + grund + ")",
                "Fertige Verlies-Reise beenden (" + grund + ")"));
        endJourneyGeklicktUm = jetztMs();
        ctx.sleepUntilChanged(1500);
        return true;
    }

    /**
     * Nach dem eigenen "End journey" ({@link #fertigeVerliesReiseBeenden}) den Rest
     * abraeumen: Nachfrage, Quit-Warnung, Abschlussfenster -- in dieser Reihenfolge.
     * Tut ausserhalb der Fristen nichts (Leuchtturm Schritt 7a, Notausgang).
     */
    public static boolean verliesRestAbraeumen(Mat screen, BotContext ctx, String woher) {
        return verliesNachfrageBestaetigen(screen, ctx, woher)
                || verliesAbschlussWegklicken(screen, ctx, woher);
    }

    /**
     * Nachfrage "End journey?" mit dem gruenen "Yes" bestaetigen -- NUR kurz nach dem
     * eigenen Klick auf den gruenen "End journey" (Sicherheit: kein fremdes "Yes").
     * Bis 29.09. fehlte das: Der Leuchtturm drueckte "End journey", die Nachfrage
     * blieb offen, und der Knopf dahinter (abgedunkelt, passt trotzdem) wurde wieder
     * und wieder gedrueckt. Zweite Sicherung: Der gruene Knopf muss hinter der
     * Nachfrage noch zu sehen sein (wie im Verlies, DungeonMode Schritt 2b) -- sonst
     * ist es nicht diese Nachfrage. Ein verschluckter Klick wird innerhalb der Frist
     * wiederholt (der Zeitstempel bleibt deshalb stehen).
     */
    private static boolean verliesNachfrageBestaetigen(Mat screen, BotContext ctx, String woher) {
        if (jetztMs() - endJourneyGeklicktUm > NACHFRAGE_FRIST_MS) return false;
        if (!ctx.visibleIfKnown(screen, "end_journey_green")) return false;
        if (ctx.matcher().has("end_journey_yes") && ctx.clickIfVisible(screen, "end_journey_yes",
                Sprache.t("Confirm leaving (rest of the dungeon, " + woher + ")",
                "Verlassen bestaetigen (Rest vom Verlies, " + woher + ")"))) {
            nachfrageBestaetigtUm = jetztMs();
            ctx.sleepUntilChanged(2500);
            return true;
        }
        return false;
    }

    /**
     * Nach dem eigenen "Yes": die Warnung "Are you sure you want to quit?" (Mitspieler
     * noch da; dahinter ein rotes X, das nicht anklickbar ist) mit Ja beantworten und
     * das Abschlussfenster (close_reward) schliessen -- wie DungeonMode. Nur bis 20 s
     * nach dem letzten "Yes".
     */
    private static boolean verliesAbschlussWegklicken(Mat screen, BotContext ctx, String woher) {
        if (jetztMs() - nachfrageBestaetigtUm > ABSCHLUSS_FRIST_MS) return false;
        // Warnung zuerst, und nie dahinter klicken -- die Knoepfe dahinter passen
        // abgedunkelt weiter, sind aber tot (DungeonMode).
        if (ctx.visibleIfKnown(screen, "quit_warning")) {
            if (ctx.clickIfVisibleIfKnown(screen, "quit_yes", Sprache.t("Confirm leaving (warning, " + woher + ")",
                    "Verlassen bestaetigen (Warnung, " + woher + ")"))
                    || ctx.clickIfVisibleIfKnown(screen, "end_journey_yes", Sprache.t("Confirm leaving (warning, " + woher + ")",
                            "Verlassen bestaetigen (Warnung, " + woher + ")"))) {
                nachfrageBestaetigtUm = jetztMs();
                ctx.sleepUntilChanged(2500);
                return true;
            }
            return false;
        }
        // Frist hier NICHT verlaengern: ein Fehltreffer auf close_reward soll nicht
        // endlos geklickt werden.
        if (ctx.clickIfVisibleIfKnown(screen, "close_reward", Sprache.t("Close the completion window (rest of the dungeon, " + woher + ")",
                "Abschlussfenster schliessen (Rest vom Verlies, " + woher + ")"))) {
            ctx.sleepUntilChanged(1200);
            return true;
        }
        return false;
    }

    /**
     * Notausgang: Es wird nichts erkannt, aber ein rotes X ist da.
     *
     * Wird nur aufgerufen, wenn der Bot ohnehin schon feststeckt. Dann ist
     * ein Klick auf das X das Beste, was passieren kann: Entweder das
     * stoerende Fenster geht zu, oder es passiert nichts.
     *
     * @param unbekannteBilder wie viele Bilder lang schon nichts erkannt wurde
     * @return true, wenn geklickt wurde
     */
    public static boolean notausgang(Mat screen, BotContext ctx, String woher,
                                     int unbekannteBilder) {
        return notausgang(screen, ctx, woher, unbekannteBilder, true);
    }

    /**
     * Wie oben. {@code verliesRestBeenden = false} nur aus dem Verlies selbst: Das
     * beendet seine Reise selbst, strenger (0,96) und zaehlt dabei den Run -- ueber
     * den Notausgang ginge der Run verloren.
     */
    /**
     * Offene Lupen-Auswahl vom Hammer-Event abbrechen -- nur wenn ihr rotes Kreuz mitten im Bild liegt (unterhalb des
     * oberen Viertels, links von 85 %), damit kein Fenster-X oben rechts dafuer gehalten wird.
     */
    static boolean lupenAuswahlAbbrechen(Mat screen, BotContext ctx, String woher) {
        if (!ctx.matcher().has("ev_form_x")) return false;
        var x = ctx.find(screen, "ev_form_x");
        if (x.isEmpty() || x.get().centerX() > ctx.width() * 0.85 || x.get().centerY() < ctx.height() * 0.25) return false;
        ctx.klick(x.get(), Sprache.t("Cancel the magnifier selection (rest of the Hammer Event)",
                "Lupen-Auswahl abbrechen (Rest vom Hammer-Event)"));
        Log.warn(woher, Sprache.t("A magnifier selection was still open in the Hammer Event -- cancelled right away.",
                "Im Hammer-Event lag noch eine Lupen-Auswahl offen -- gleich abgebrochen."));
        ctx.sleepUntilChanged(1000);
        return true;
    }

    public static boolean notausgang(Mat screen, BotContext ctx, String woher,
                                     int unbekannteBilder, boolean verliesRestBeenden) {
        // Nachfrage, Warnung und Abschlussfenster nach dem eigenen "End journey" gleich
        // im naechsten Bild abraeumen, nicht erst nach weiteren zwoelf.
        if (verliesRestAbraeumen(screen, ctx, woher)) return true;

        // Kisten-Anzeige der Nahrungskisten ("Chests remaining", gelber "Close" unten links)
        // -- blieb am 30.09. zweimal offen (03:05, 15:02), und die Kathedrale kannte sie nicht.
        if (unbekannteBilder >= 3 && ctx.clickIfVisibleIfKnown(screen, "bag_close",
                Sprache.t("Close the chest display (Close, rest of the food chests)",
                "Kisten-Anzeige schliessen (Close, Rest der Nahrungskisten)"))) {
            Log.warn(woher, Sprache.t("The chest display of the food chests was still open. Closed.",
                    "Die Kisten-Anzeige der Nahrungskisten stand noch offen. Geschlossen."));
            ctx.sleepUntilChanged(1200);
            return true;
        }

        // Berichte #KJ3S, #PQ93, #2TRM, #M7NB, #F6SY, #8YHV (Ben, 24./25.10.): eine offene Lupen-Auswahl im Hammer-Event
        // ("Magnifying glass cannot be used, all tiles are ...") hielt Leuchtturm und Clan-Krieg je 20 s auf, bis zwoelf
        // Bilder voll waren -- und der Clan-Krieg kam nicht zur Burg (rotes X oben rechts wirkt unter der Auswahl nicht).
        // Das rote Kreuz der Auswahl liegt mitten im Feld, nie oben rechts wie ein Fenster-X: gleich abbrechen.
        if (lupenAuswahlAbbrechen(screen, ctx, woher)) return true;

        if (unbekannteBilder < NOTAUSGANG_AB) return false;

        // Nur alle zwoelf Bilder einen Versuch. Sonst haemmert der Bot auf
        // eine Stelle, an der gar kein Knopf ist.
        if (unbekannteBilder % NOTAUSGANG_AB != 0) return false;

        // Reste vom Clan-Krieg: Kriegskarte ("Back") oder Kriegsuebersicht
        // (eigenes X). Am 23.09. um 22:20 blieb nach dem Aufstellen die
        // Uebersicht offen, die Kathedrale erkannte 45 Sekunden lang nichts
        // und fand danach nicht mehr aus der verschobenen Weltkarte heraus.
        for (String rest : new String[]{"cw_back"}) {   // cw_close_x sieht aus wie jedes rote X -- das macht unten der normale Notausgang
            if (ctx.matcher().has(rest) && ctx.clickIfVisibleIfKnown(screen, rest,
                    Sprache.t("Close the rest of the clan war (" + rest + ")",
                    "Rest vom Clan-Krieg schliessen (" + rest + ")"))) {
                Log.warn(woher, Sprache.t("Nothing recognized for " + unbekannteBilder + " frames -- " + "a clan war screen was still open. Closed.",
                        "Seit " + unbekannteBilder + " Bildern nichts erkannt -- "
                        + "es lag noch ein Bildschirm vom Clan-Krieg offen. Geschlossen."));
                ctx.sleepUntilChanged(1500);
                return true;
            }
        }

        // Rest vom Verlies (29.09., 20:38): Die Reise war fertig (100 %, gruener "End
        // journey"), aber noch nicht beendet -- der Leuchtturm kannte den Bildschirm nicht
        // und brach ab. Gruen heisst fertig, der Klick verliert nichts.
        if (verliesRestBeenden && fertigeVerliesReiseBeenden(screen, ctx, Sprache.t("Rest of the dungeon", "Rest vom Verlies"))) {
            Log.warn(woher, Sprache.t("Nothing recognized for " + unbekannteBilder + " frames -- a finished " + "dungeon journey was still open. Ended.",
                    "Seit " + unbekannteBilder + " Bildern nichts erkannt -- eine fertige "
                    + "Verlies-Reise war noch offen. Beendet."));
            return true;
        }

        // Rest vom Hammer-Event (Test 29.09., 14:19): Mitten beim Setzen einer Lupe
        // angehalten, liegt die dunkle Auswahl ueber dem Event -- darunter findet sich
        // das rote X nicht, das Verlies stand 100 s und gab auf. Erst die Auswahl
        // abbrechen (rotes Kreuz im Feld), dann schliesst das normale X das Event.
        // Seit 25.10. schon im ersten Bild (lupenAuswahlAbbrechen oben); hier nur noch ohne Ortspruefung.
        if (ctx.matcher().has("ev_form_x") && ctx.clickIfVisibleIfKnown(screen, "ev_form_x",
                Sprache.t("Cancel the magnifier selection (rest of the Hammer Event)",
                "Lupen-Auswahl abbrechen (Rest vom Hammer-Event)"))) {
            Log.warn(woher, Sprache.t("Nothing recognized for " + unbekannteBilder + " frames -- " + "a magnifier selection was still open in the Hammer Event. Aborted.",
                    "Seit " + unbekannteBilder + " Bildern nichts erkannt -- "
                    + "im Hammer-Event lag noch eine Lupen-Auswahl offen. Abgebrochen."));
            ctx.sleepUntilChanged(1000);
            return true;
        }

        if (!ctx.visible(screen, T_CLOSE_X)) return false;

        Log.warn(woher, Sprache.t("Nothing recognized for " + unbekannteBilder + " frames, " + "but a red X is visible. The bot presses it -- " + "probably a window is in the way.",
                "Seit " + unbekannteBilder + " Bildern wird nichts erkannt, "
                + "aber es ist ein rotes X zu sehen. Der Bot drueckt es -- "
                + "vermutlich liegt ein Fenster im Weg."));

        if (ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the unknown window", "Unbekanntes Fenster schliessen"))) {
            ctx.sleepUntilChanged(1500);
            return true;
        }
        return false;
    }

    /**
     * Soll die Warnung "Nothing recognized for N frames" jetzt ins Protokoll? Alle zwoelf Bilder UND erst, wenn seit dem
     * letzten Fortschritt mindestens {@value #WARNUNG_AB_SEKUNDEN} Sekunden vergangen sind.
     *
     * WARUM (Bugjagd 29.10.): von 166 solcher Warnungen in drei Tagen kamen 156 (94 %) nach weniger als 8 s -- 101 direkt nach
     * "Run completed" (Belohnungs-Einblendung, Burg baut sich auf), 35 nach "Troops confirmed" (Ladebild der Etage). Das ist
     * normaler Uebergang, keine Stoerung; jede Warnung rechnete zusaetzlich die "Best matches" aus. Echte Stillstaende
     * (Tasche 50 s, Partnersuche 55 s) bleiben davon unberuehrt.
     */
    static boolean warnungFaellig(int unbekannteBilder, long stilleSekunden) {
        return unbekannteBilder > 0 && unbekannteBilder % NOTAUSGANG_AB == 0 && stilleSekunden >= WARNUNG_AB_SEKUNDEN;
    }

    static final int WARNUNG_AB_SEKUNDEN = 8;
}
