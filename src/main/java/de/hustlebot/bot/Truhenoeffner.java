package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.util.Optional;
import de.hustlebot.core.Sprache;

/**
 * Holt Nachschub, wenn die Schluessel der Kathedrale ausgehen: raus aus der
 * Kathedrale, in die Burg, Tasche auf, Kisten leeren, zurueck.
 *
 * WOZU. Bisher war bei leeren Schluesseln einfach Schluss -- der Bot meldete
 * "fertig" und stand still, obwohl in Autors Tasche hunderte Kisten lagen,
 * die genau das nachliefern, was ihm fehlte. Eine Nacht Kathedrale endete so
 * nach zwei Stunden.
 *
 * DER WEG, abgesehen vom Video, das Autor am 6. September aufgenommen hat:
 *
 *   1. Kathedralen-Fenster mit dem roten X schliessen -> Burg
 *   2. In der Burg auf den runden Truhen-Knopf ueber "Map" -> "Warehouse"
 *   3. In der ersten Reihe eine Kathedralen-Kiste anklicken
 *   4. Im Kistenfenster den gruenen "Open"-Knopf, am liebsten "x10"
 *   5. Beim Oeffnen laeuft unten "Chests remaining: 96" mit. Weiter auf
 *      "Open", bis der Knopf verschwindet -- dann ist diese Sorte leer
 *   6. Oranges "Close" -> zurueck ins Warehouse, naechste Sorte
 *   7. Ist keine bekannte Kiste mehr da: Warehouse zu, zurueck in die Burg
 *
 * Danach uebernimmt wieder der Kathedralen-Modus: Karte, Kathedrale,
 * teilnehmen. Der Schluesselstand wird dabei von selbst neu abgelesen.
 *
 * ZWEI REGELN VON AUTOR, die hier fest eingebaut sind:
 *
 *   - Angefasst werden NUR die Kisten, deren Vorlage er selbst aufgenommen
 *     hat (bag_chest_1, bag_chest_2, ...). Die durchgestrichenen Kisten in
 *     seiner Tasche bleiben unberuehrt. Das ist keine Vorsichtsmassnahme,
 *     die man spaeter lockern koennte -- er hat sie ausdruecklich verboten.
 *
 *   - Nichts wird gekauft. Auf diesem Weg kommt kein Fenster mit
 *     Diamantenpreis vor; taucht doch eins auf, wird es nicht bedient,
 *     sondern der Gang abgebrochen.
 *
 * Fehlen die Vorlagen, tut diese Klasse gar nichts und sagt einmal, welche
 * fehlen. Der Bot laeuft dann wie vorher weiter.
 */
public final class Truhenoeffner {

    // --- Vorlagen ------------------------------------------------------

    /** Der runde Truhen-Knopf in der Burg, gleich ueber "Map". */
    private static final String T_TASCHE = "bag_button";
    /** Die Ueberschrift "Warehouse" im weissen Band. */
    private static final String T_TITEL = "bag_title";
    /** "Chests remaining:" unten im Oeffnen-Bildschirm. */
    private static final String T_RESTLICH = "bag_remaining";
    /** Der gruene Knopf "Open" mit dem Truhensymbol "x10". */
    private static final String T_OPEN_10 = "bag_open_10";
    /** Derselbe Knopf mit "x1" -- kommt, wenn weniger als zehn uebrig sind. */
    private static final String T_OPEN_1 = "bag_open_1";
    /** Das orange "Close" unten links im Oeffnen-Bildschirm. */
    private static final String T_SCHLIESSEN = "bag_close";
    /** Das runde rote X oben rechts. */
    private static final String T_ROTES_X = "close_x";
    /** Der "Map"-Knopf unten links -- daran wird die Burg erkannt. */
    private static final String T_MAP = "map_button";
    /** Derselbe Knopf im neueren Aussehen. */
    private static final String T_MAP_ALT = "map_button_alt";

    // Kistensorten: alle bag_chest_1, _2 ... ohne Obergrenze (bis 28.09. nur
    // bis _6) -- jede neue Aufnahme wirkt sofort, siehe Matcher.serie().

    // --- Grenzen -------------------------------------------------------

    /**
     * Hoechstens so viele Klicks je Gang.
     *
     * Bei "x10" sind das zweitausend Kisten, weit mehr als Autor je hat. Die
     * Zahl ist auch keine Erwartung, sondern eine Reissleine: Sollte der Bot
     * sich irgendwo festfahren und immer dasselbe druecken, ist nach zwei
     * Minuten Schluss statt nach der ganzen Nacht.
     */
    private static final int MAX_KLICKS = 200;

    /**
     * So viele Bilder darf der Bot nichts Bekanntes sehen, bevor er den Gang
     * abbricht.
     *
     * Der Gang fuehrt durch fremde Bildschirme. Verlaeuft er sich, soll er
     * das selbst merken und zurueck in die bekannte Bahn gehen, statt den
     * ganzen Lauf zu blockieren.
     */
    private static final int MAX_BLIND = 25;

    // --- Zustand -------------------------------------------------------

    private boolean aktiv = false;
    private int klicks = 0;
    private int blind = 0;
    private int geoeffnet = 0;
    private long begonnen = 0;
    private boolean hinweisGezeigt = false;
    private boolean oeffnenKnopfWarnung = false;

    /**
     * Wann zuletzt eine Kiste angeklickt wurde. Direkt danach baut sich der
     * Oeffnen-Bildschirm auf; in dieser Zeit darf das rote X NICHT gedrueckt
     * werden, sonst schliesst der Bot den halb aufgebauten Bildschirm wieder
     * (die Endlosschleife vom 17.09.: Kiste -> rotes X -> Kiste -> ...).
     */
    private long kisteAngeklicktUm = 0;

    /** So lange nach einem Kistenklick gilt der Oeffnen-Bildschirm als "im Aufbau". */
    private static final long KISTE_AUFBAU_MS = 2500;

    /** Ebenso nach dem Klick auf den Taschenknopf, bis das Warehouse steht. */
    private static final long TASCHE_AUFBAU_MS = 2000;
    private long tascheGeklicktUm = 0;
    private int tascheVersuche = 0;

    /** x10-Klicks hintereinander, ohne dass ein anderes Bild dazwischen kam. */
    private int zehnerInFolge = 0;
    /** Wie oft der Bot nach dem ersten Oeffnen wieder in der Burg stand. */
    private int burgRueckkehr = 0;
    /** Wie oft am Oeffnen-Bildschirm nur gewartet wurde, ohne etwas zu druecken. */
    private int wartenAmOeffnen = 0;

    /**
     * Wohin der Bot tippt, waehrend die Kisten aufgehen (Autor, 25.09., mit
     * Bild: "irgendwo da in diesen roten Kreisen, dann geht die Animation
     * schneller vorbei"). Die Mitte des linken Kreises, als Anteil der
     * Fensterbreite und -hoehe -- weit weg von "Close" (unten links) und
     * "Next" (unten rechts). Ohne Tippen dauerte ein "x10" etwa 60 s.
     */
    private static final double TIPP_X = 0.24, TIPP_Y = 0.60;
    /** Reissleine: so oft hintereinander tippen, ohne dass Open oder Close kommt. */
    private static final int MAX_TIPPS_IN_FOLGE = 150;
    private int tippsInFolge = 0;
    /**
     * Bericht #A6Y3 (Autor, 28.10.): die Klickgrenze kam direkt nach "Open ten" -- der Gang endete mitten in der
     * Kisten-Animation ("Chests remaining: 2229"), die Kathedrale kannte diesen Bildschirm nicht und stand nach 20 s
     * still. Seitdem wird nach der Grenze erst aufgeraeumt (Animation, Close, Tasche zu), hoechstens so viele Schritte.
     */
    private static final int MAX_AUFRAEUM_SCHRITTE = 40;
    private int aufraeumSchritte = 0;
    private int tipps = 0;
    /** Wie oft im leeren Warehouse gruendlich nach dem roten X gesucht wurde. */
    private int xSuche = 0;
    /** Schwelle fuer das X des Warehouse (close_x passt dort nur zu 0,77). */
    private static final double X_LAGER_SCHWELLE = 0.70;
    /** Wann zuletzt "Open" gedrueckt wurde -- danach laeuft die Animation. */
    private long offenGeklicktUm = 0;
    private static final long NACH_OFFEN_MS = 20000;

    /** Seit wann das Warehouse ohne Kiste dasteht; so lange ruhig warten. */
    private long lagerSeit = 0;
    private static final long LAGER_RUHE_MS = 1200;

    /** Die Weltkarte: erkannt an der Dunklen Kathedrale darauf. */
    private static final String T_WELTKARTE = "dc_node";
    /** "Home" unten links auf der Weltkarte (freiwillige Vorlage, 25.09.). */
    private static final String T_WELT_HOME = "worldmap_home";
    private int weltkarteVersuche = 0;

    /**
     * Wie oft dieselbe Kiste angeklickt wurde, ohne dass der Oeffnen-
     * Bildschirm kam. Schuetzt davor, im Warehouse haengenzubleiben.
     */
    private int kistenVersuche = 0;

    /** True, solange der Gang laeuft. */
    public boolean aktiv() {
        return aktiv;
    }

    /** Wie viele Kisten in diesem Gang geoeffnet wurden -- fuer den Bericht. */
    public int geoeffnet() {
        return geoeffnet;
    }

    /**
     * Endete der letzte Gang ordentlich in einer leeren Tasche (keine
     * bekannte Kiste mehr, X gedrueckt)? Nur dann heisst "0 geoeffnet"
     * wirklich "keine Kisten mehr" -- und nicht "unterwegs verlaufen".
     */
    private boolean tascheLeer = false;

    public boolean tascheLeer() {
        return tascheLeer;
    }

    /**
     * Sind ueberhaupt genug Vorlagen da, um den Gang zu wagen?
     *
     * Verlangt werden die drei, ohne die man sich verlaufen wuerde: der
     * Taschenknopf, die Ueberschrift und mindestens eine Kistensorte. Die
     * Knoepfe kommen erst danach dran; fehlt einer davon, bricht der Gang
     * an einer Stelle ab, an der der Bot noch weiss, wo er ist.
     */
    public boolean einsatzbereit(BotContext ctx) {
        var m = ctx.matcher();
        boolean bereit = m.has(T_TASCHE) && m.has(T_TITEL) && m.has("bag_chest_1");

        if (!bereit && !hinweisGezeigt) {
            hinweisGezeigt = true;
            StringBuilder fehlt = new StringBuilder();
            for (String n : new String[]{T_TASCHE, T_TITEL, "bag_chest_1",
                                         T_OPEN_10, T_OPEN_1, T_SCHLIESSEN, T_RESTLICH}) {
                if (!m.has(n)) fehlt.append(fehlt.length() == 0 ? "" : ", ").append(n);
            }
            Log.info("Tasche", Sprache.t("The chests could supply new keys, but " + "templates are still missing for that: " + fehlt + ". Capture them with Capture-Images.exe, " + "then the bot fetches the supply by itself.",
                    "Die Kisten koennten Schluessel nachliefern, aber dafuer "
                    + "fehlen noch Vorlagen: " + fehlt + ". Mit Capture-Images.exe aufnehmen, "
                    + "dann holt der Bot sich den Nachschub von selbst."));
        }
        return bereit;
    }

    /** Startet den Gang. */
    public void starten(BotContext ctx) {
        starten(ctx, false);
    }

    /**
     * Schwelle (Autor, 29.09.): Liegen hoechstens so viele Kathedralen-Kisten in der
     * Tasche, lohnt der Gang nicht -- der Kreislauf geht dann lieber ins Hammer-Event.
     */
    public static final int WENIGE_KISTEN = 9;
    private boolean wenigeUeberspringen, wenigeKisten, gezaehlt;

    /** Endete der letzte Gang, weil nur noch wenige Kisten da waren (keine geoeffnet)? */
    public boolean wenigeKisten() {
        return wenigeKisten;
    }

    /**
     * Startet den Gang. Mit wenigeUeberspringen: erst die Kisten zaehlen -- sind es
     * hoechstens WENIGE_KISTEN, bleibt die Tasche zu (fuer den Kreislauf).
     */
    public void starten(BotContext ctx, boolean wenigeUeberspringen) {
        if (aktiv) return;
        aktiv = true;
        this.wenigeUeberspringen = wenigeUeberspringen;
        wenigeKisten = false;
        gezaehlt = false;
        klicks = 0;
        blind = 0;
        geoeffnet = 0;
        kistenVersuche = 0;
        oeffnenKnopfWarnung = false;
        kisteAngeklicktUm = 0;
        tascheGeklicktUm = 0;
        tascheVersuche = 0;
        weltkarteVersuche = 0;
        lagerSeit = 0;
        zehnerInFolge = 0;
        tascheLeer = false;
        burgRueckkehr = 0;
        wartenAmOeffnen = 0;
        tippsInFolge = 0;
        aufraeumSchritte = 0;
        tipps = 0;
        xSuche = 0;
        offenGeklicktUm = 0;
        begonnen = System.currentTimeMillis();
        Log.info("Tasche", Sprache.t("Out of keys. The bot goes into the castle and " + "opens the cathedral chests in the bag.",
                "Die Schluessel sind alle. Der Bot geht in die Burg und "
                + "oeffnet die Kathedralen-Kisten in der Tasche."));
    }

    /** Bricht einen angefangenen Gang ab, z. B. nach !stop (neuer Start = neuer Gang). */
    public void abbrechen() {
        beenden(Sprache.t("aborted, the mode was restarted", "abgebrochen, der Modus wurde neu gestartet"));
    }

    /** Beendet den Gang und sagt, was dabei herauskam. */
    private void beenden(String grund) {
        if (!aktiv) return;
        aktiv = false;
        long dauer = (System.currentTimeMillis() - begonnen) / 1000;
        Log.info("Tasche", Sprache.t("Chest round finished (" + grund + "). Opened: " + geoeffnet + " times, tapped: " + tipps + " times, took: " + dauer + " seconds.",
                "Kisten-Gang beendet (" + grund + "). Geoeffnet: "
                + geoeffnet + " Mal, angetippt: " + tipps + " Mal, gedauert: " + dauer + " Sekunden."));
    }

    /**
     * Wie viele Kathedralen-Kisten liegen in der Tasche? Die Stueckzahl steht oben links
     * am Kistenfeld (gleiche Stelle wie bei den Nahrungskisten).
     *
     * @return die Summe, -1 wenn eine Zahl nicht lesbar ist, -2 wenn noch keine Kiste
     *         zu sehen ist
     */
    private static int kistenZaehlen(Mat screen, BotContext ctx) {
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return -1;
        double s = ctx.width() / 1159.0;
        int summe = 0, felder = 0;
        for (String kiste : ctx.matcher().serie("bag_chest")) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, kiste, Matcher.DEFAULT_THRESHOLD, 6)) {
                int x = h.x() - (int) (25 * s), y = h.y() - (int) (32 * s);
                int w = (int) (55 * s), hoehe = (int) (40 * s);
                java.util.OptionalLong n = ArenaLeser.zahl(bild, Math.max(0, x), Math.max(0, y), w, hoehe);
                if (n.isEmpty()) n = Nahrungskisten.zahlVerstaerkt(bild, Math.max(0, x), Math.max(0, y), w, hoehe, false);
                if (n.isEmpty() || n.getAsLong() < 1 || n.getAsLong() > 9999) return -1;
                summe += (int) n.getAsLong();
                felder++;
            }
        }
        return felder == 0 ? -2 : summe;
    }

    /**
     * Tippt einmal in die Oeffnen-Animation, damit die naechste Kiste gleich
     * aufgeht. Zaehlt NICHT zu den Klicks (MAX_KLICKS) -- sonst waere die
     * Reissleine nach wenigen Kistensorten erreicht. Ins Protokoll kommt nur
     * der erste Tipp jeder Serie, nicht jeder einzelne.
     */
    private void tippen(BotContext ctx) {
        int x = (int) Math.round(ctx.width() * TIPP_X), y = (int) Math.round(ctx.height() * TIPP_Y);
        if (tippsInFolge == 0) {
            Log.action("Klick", Sprache.t("Tap the chest animation so it runs faster  [" + x + "," + y + ", further taps without an entry]",
                    "Kisten-Animation antippen, damit sie schneller durchlaeuft  ["
                    + x + "," + y + ", die weiteren Tipps ohne Eintrag]"));
        }
        ctx.input().click(x, y, 8);
        tippsInFolge++;
        tipps++;
        ctx.sleep(250);
    }

    /**
     * Ein Schritt des Gangs. Genau eine Handlung je Aufruf, wie ueberall im
     * Bot -- nie eine Kette.
     *
     * @return true, wenn etwas getan wurde. false heisst: der Gang ist
     *         vorbei, der Modus macht normal weiter.
     */
    public boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;

        if (klicks >= MAX_KLICKS) {
            // Keine neue Kiste mehr -- aber erst raus aus Animation und Tasche, dann beenden (Bericht #A6Y3).
            if (aufraeumSchritte++ < MAX_AUFRAEUM_SCHRITTE && ausDerTascheRaus(screen, ctx)) return true;
            beenden("Klickgrenze erreicht");
            return false;
        }

        // --- 1. Der Oeffnen-Bildschirm ---------------------------------
        //
        // Erkannt am orangen "Close" unten links ODER an "Chests remaining:".
        // BEIDES steht nur hier -- der orange Close reicht also allein.
        //
        // FRUEHER verlangte diese Zeile zusaetzlich einen Open-Knopf. Passte
        // dessen Vorlage nicht mehr (neuer Skin, andere Stueckzahl), galt der
        // Oeffnen-Bildschirm nicht als erkannt, fiel bis Schritt 4 durch und
        // wurde dort mit dem roten X weggeklickt -- immer wieder. Genau die
        // Schleife, die Autor am 17.09. sah.
        //
        // SEIT 25.09. AUCH an den gruenen Open-Knoepfen: Nach dem Klick auf
        // eine Kiste kommt ein Kisten-Fenster ("Manuscript Chest", Contents,
        // "Open x1" / "Open x10") OHNE "Chests remaining" und ohne orangen
        // Close. Der Bot hielt es fuer die Burg (der Map-Knopf schaut
        // dahinter hervor), drueckte die Tasche und schloss es damit wieder
        // -- sechsmal, dann "Kiste liess sich nicht oeffnen" (Test 15:51).
        boolean offenKnopf = ctx.visibleIfKnown(screen, T_OPEN_10)
                || ctx.visibleIfKnown(screen, T_OPEN_1);
        boolean restlich = ctx.visibleIfKnown(screen, T_RESTLICH);
        boolean amOeffnen = offenKnopf || restlich
                || ctx.visibleIfKnown(screen, T_SCHLIESSEN);
        if (!amOeffnen) zehnerInFolge = 0;

        if (amOeffnen) {
            blind = 0;
            lagerSeit = 0;   // zurueck in der Tasche wird neu gewartet

            // Zehn auf einmal, wenn es geht. Das ist der ganze Grund, warum
            // der Gang ertraeglich lange dauert: 226 Kisten einzeln waeren
            // 226 Klicks und ebenso viele Animationen.
            //
            // NUR ein wirklich gedrueckter Open-Knopf setzt den Fehlversuch-
            // Zaehler zurueck. Frueher tat das jeder Blick auf den Oeffnen-
            // Bildschirm -- dann griff die Reissleine (kistenVersuche) nie.
            //
            // Sind weniger als zehn uebrig, tut "x10" womoeglich nichts. Kommt
            // nach drei x10-Klicks in Folge immer wieder dasselbe Fenster, ohne
            // dass dazwischen etwas anderes zu sehen war, wird einzeln geoeffnet.
            if (zehnerInFolge < 3
                    && klickeWennDa(screen, ctx, T_OPEN_10, Sprache.t("Open ten chests at once", "Zehn Kisten auf einmal oeffnen"))) {
                zehnerInFolge++;
                geoeffnet += 10;
                kistenVersuche = 0;
                offenGeklicktUm = System.currentTimeMillis();
                tippsInFolge = 0;
                ctx.sleepUntilChanged(2500);
                return true;
            }
            if (klickeWennDa(screen, ctx, T_OPEN_1, Sprache.t("Open one chest", "Eine Kiste oeffnen"))) {
                geoeffnet += 1;
                kistenVersuche = 0;
                offenGeklicktUm = System.currentTimeMillis();
                tippsInFolge = 0;
                ctx.sleepUntilChanged(2500);
                return true;
            }

            // Kein Open-Knopf zu sehen. Entweder ist die Sorte leer -- oder die
            // Vorlage bag_open_10 / bag_open_1 passt nicht mehr. Einmal je Gang
            // darauf hinweisen; die Reissleine in Schritt 2 (kistenVersuche)
            // beendet den Gang, falls sich gar nichts oeffnen laesst.
            // Nicht waehrend der Animation ("Chests remaining") und nicht auf dem
            // Schlussbild mit "Close" -- dort fehlt der Knopf ja zu Recht (26.09.).
            if (!oeffnenKnopfWarnung && ctx.matcher().has(T_OPEN_10) && !restlich
                    && !ctx.visibleIfKnown(screen, T_SCHLIESSEN)) {
                oeffnenKnopfWarnung = true;
                Log.info("Tasche", Sprache.t("No green \"Open\" button can be seen on the opening screen. " + "If this kind is not empty, the template no longer matches " + "-- then please recapture bag_open_10 and bag_open_1 with Capture-Images.exe " + "(the button looks different since the last game update).",
                        "Auf dem Oeffnen-Bildschirm ist kein gruener \"Open\"-Knopf "
                        + "zu erkennen. Ist die Sorte nicht leer, passt die Vorlage nicht mehr "
                        + "-- dann bitte bag_open_10 und bag_open_1 mit Capture-Images.exe neu "
                        + "aufnehmen (der Knopf sieht seit dem letzten Spiel-Update anders aus)."));
            }

            // Zurueck ins Warehouse und die naechste Sorte nehmen.
            if (klickeWennDa(screen, ctx, T_SCHLIESSEN, Sprache.t("Kind empty / no Open button -- back to the bag", "Sorte leer / kein Open-Knopf -- zurueck zur Tasche"))) {
                tippsInFolge = 0;
                // Mindestens 0,5 s: der Wechsel zurueck ist animiert, und ohne
                // Pause drueckte der Bot "Close" gleich zweimal (Test 20:09:29).
                ctx.sleepUntilChanged(500, 1500);
                return true;
            }

            // Die Kisten gehen gerade auf: "Chests remaining" steht da, aber
            // weder Open noch Close. Kurz antippen, dann springt die Animation
            // zur naechsten Kiste (Autor, 25.09.).
            if (restlich && tippsInFolge < MAX_TIPPS_IN_FOLGE) {
                tippen(ctx);
                return true;
            }

            // Weder oeffnen noch schliessen: vermutlich laeuft gerade die
            // Animation. Kurz warten und noch einmal hinsehen -- aber nicht
            // endlos: nach etwa 5 s das rote X (das Kisten-Fenster hat keinen
            // orangen Close, nur das X oben rechts).
            if (++wartenAmOeffnen > 12
                    && klickeWennDa(screen, ctx, T_ROTES_X, Sprache.t("Close the chest window -- nothing left to open", "Kisten-Fenster schliessen -- nichts mehr zu oeffnen"))) {
                wartenAmOeffnen = 0;
                ctx.sleepUntilChanged(1500);
                return true;
            }
            ctx.sleep(400);
            return true;
        }
        wartenAmOeffnen = 0;

        // --- 2. Das Warehouse ------------------------------------------
        if (ctx.visibleIfKnown(screen, T_TITEL)) {
            tascheVersuche = 0;   // die Tasche ist auf
            blind = 0;

            // Erst zaehlen (Kreislauf): hoechstens 9 Kisten -> Tasche zu, lieber ins Event.
            if (wenigeUeberspringen && !gezaehlt && geoeffnet == 0) {
                int n = kistenZaehlen(screen, ctx);
                if (n != -2) {   // -2: noch keine Kiste zu sehen, gleich noch einmal
                    gezaehlt = true;
                    if (n >= 0 && n <= WENIGE_KISTEN) {
                        wenigeKisten = true;
                        Log.info("Tasche", Sprache.t("Only " + n + " cathedral chests left (threshold " + WENIGE_KISTEN + ") -- they stay put, Hammer Event first.",
                                "Nur noch " + n + " Kathedralen-Kisten (Schwelle " + WENIGE_KISTEN
                                + ") -- die bleiben liegen, erst ins Hammer-Event."));
                        if (klickeWennDa(screen, ctx, T_ROTES_X, Sprache.t("Close the bag -- only a few chests", "Tasche schliessen -- nur wenige Kisten"))) {
                            ctx.sleepUntilChanged(1200);
                        }
                        beenden(Sprache.t("only " + n + " chests, event first",
                                "nur " + n + " Kisten, erst das Event"));
                        return false;
                    }
                    if (n > 0) Log.info("Tasche", Sprache.t(n + " cathedral chests in the bag -- they get opened.",
                            n + " Kathedralen-Kisten in der Tasche -- sie werden geoeffnet."));
                }
            }

            for (String kiste : ctx.matcher().serie("bag_chest")) {
                if (klickeWennDa(screen, ctx, kiste, Sprache.t("Open the cathedral chest " + kiste, "Kathedralen-Kiste " + kiste + " oeffnen"))) {
                    kistenVersuche++;
                    // Ab jetzt baut sich der Oeffnen-Bildschirm auf -- in dieser
                    // Zeit wird das rote X nicht gedrueckt (siehe Schritt 4).
                    kisteAngeklicktUm = System.currentTimeMillis();
                    // Kommt nach mehreren Versuchen kein Oeffnen-Bildschirm,
                    // stimmt etwas mit dieser Vorlage nicht. Dann lieber
                    // aufhoeren als endlos dasselbe Bild anklicken.
                    if (kistenVersuche > 6) {
                        beenden(Sprache.t("chest could not be opened", "Kiste liess sich nicht oeffnen"));
                        return false;
                    }
                    ctx.sleepUntilChanged(1500);
                    return true;
                }
            }

            // Erst ruhig hinsehen (Test 25.09., 15:34): 0,2 s nach dem Oeffnen
            // stand zwar schon "Warehouse" da, die Kisten aber noch nicht --
            // der Bot hielt die Tasche fuer leer und gab auf. Leer ist sie
            // erst, wenn sie eine Weile offen steht und trotzdem nichts kommt.
            if (lagerSeit == 0) lagerSeit = System.currentTimeMillis();
            if (System.currentTimeMillis() - lagerSeit < LAGER_RUHE_MS) {
                ctx.sleep(200);
                return true;
            }

            // Keine bekannte Kiste mehr da: fertig. Tasche zu.
            if (klickeWennDa(screen, ctx, T_ROTES_X, Sprache.t("Close the bag -- all chests empty", "Tasche schliessen -- alle Kisten leer"))) {
                tascheLeer = true;
                beenden(Sprache.t("all chests emptied", "alle Kisten geleert"));
                ctx.sleepUntilChanged(1200);
                return true;
            }
            // Das X nicht gefunden (Test 25.09., 21:47 und 21:50). Zwei Gruende:
            //  - Die Erkennung sieht zuerst dort nach, wo close_x zuletzt war
            //    (oben rechts am Kathedralen-Fenster); das ganze Bild wird nur
            //    bei jedem 25. Fehlschlag abgesucht.
            //  - Und selbst dann passt close_x auf das X des Warehouse nur zu
            //    0,77 (gemessen 21:55), verlangt sind sonst 0,84.
            // Darum hier, wo das Warehouse sicher offen ist: das Bild ganz
            // absuchen und 0,70 genuegen lassen -- aber nur oben rechts, wo
            // das X des Warehouse sitzt.
            if (xSuche > 0) {
                Optional<Matcher.Hit> x = ctx.matcher().find(screen, T_ROTES_X, X_LAGER_SCHWELLE);
                if (x.isPresent() && x.get().centerX() > ctx.width() / 2
                        && x.get().centerY() < ctx.height() / 4) {
                    Matcher.Hit h = x.get();
                    Log.action("Klick", Sprache.t("Close the bag -- all chests empty  [" + T_ROTES_X + " at " + h.centerX() + "," + h.centerY() + ", match " + String.format("%.3f", h.score()) + ", Warehouse-X]",
                            "Tasche schliessen -- alle Kisten leer  [" + T_ROTES_X + " bei "
                            + h.centerX() + "," + h.centerY() + ", Uebereinstimmung "
                            + String.format("%.3f", h.score()) + ", Warehouse-X]"));
                    ctx.input().click(h);
                    klicks++;
                    tascheLeer = true;
                    beenden(Sprache.t("all chests emptied", "alle Kisten geleert"));
                    ctx.sleepUntilChanged(1200);
                    return true;
                }
            }
            if (++xSuche <= 5) {
                ctx.matcher().naechstesBildGruendlich();
                ctx.sleep(200);
                return true;
            }
            beenden(Sprache.t("bag could not be closed", "Tasche liess sich nicht schliessen"));
            return false;
        }

        // --- 3. Die Burg -----------------------------------------------
        //
        // Erkannt am "Map"-Knopf unten links. Von hier fuehrt der Truhen-
        // Knopf in die Tasche.
        // Beide Aussehen des Map-Knopfs (25.09.: bei Autor steht inzwischen
        // map_button_alt da -- der Gang hielt die Burg deshalb fuer fremd und
        // gab nach 9 s mit "Weg nicht gefunden" auf).
        if (ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT)) {
            blind = 0;

            // NICHT gleich wieder druecken (Test 25.09., 15:24): Tasche und
            // Oeffnen-Bildschirm bauen sich mit einer Animation auf, und der
            // Map-Knopf bleibt dahinter sichtbar. Der Bot drueckte den
            // Taschenknopf deshalb alle 0,15 s -- jeder Klick machte die
            // Tasche wieder zu. Jetzt: nach einem Klick erst abwarten.
            long jetzt = System.currentTimeMillis();
            if (jetzt - tascheGeklicktUm < TASCHE_AUFBAU_MS
                    || jetzt - kisteAngeklicktUm < KISTE_AUFBAU_MS) {
                ctx.sleep(200);
                return true;
            }

            // NICHT MEHR "wieder in der Burg = fertig" (Autor, 25.09.: "der bot
            // soll immer alle kisten oeffnen, nicht nur 20"). Im Test 20:09
            // stand der Bot nach 20 Kisten kurz in der Burg und hoerte auf,
            // obwohl noch Kisten da sein konnten. Fertig ist er erst, wenn die
            // Tasche offen steht und keine Kiste mehr darin liegt (Schritt 2).
            // Landet er vorher in der Burg, macht er die Tasche wieder auf --
            // hoechstens fuenfmal, damit er sich nicht im Kreis dreht.
            if (geoeffnet > 0 && ++burgRueckkehr > 5) {
                beenden(Sprache.t("kept landing in the castle", "immer wieder in der Burg gelandet"));
                return false;
            }

            if (tascheVersuche >= 4) {
                beenden(Sprache.t("bag did not open (bag_title not recognized)",
                        "Tasche ging nicht auf (bag_title nicht erkannt)"));
                return false;
            }
            if (klickeWennDa(screen, ctx, T_TASCHE, Sprache.t("Open the bag", "Tasche oeffnen"))) {
                tascheVersuche++;
                tascheGeklicktUm = System.currentTimeMillis();
                ctx.sleepUntilChanged(1500);
                return true;
            }
            beenden(Sprache.t("bag button not found", "Taschenknopf nicht gefunden"));
            return false;
        }

        // --- 4. Irgendein Fenster im Weg -------------------------------
        //
        // Auf dem Weg aus der Kathedrale liegt das Kathedralen-Fenster
        // selbst, und gelegentlich eine Werbung. Beides macht das rote X zu.
        //
        // ABER NICHT direkt nach einem Kistenklick: Dann baut sich der
        // Oeffnen-Bildschirm gerade auf, sein rotes X ist schon da, der Rest
        // aber noch nicht. Wer jetzt das X drueckt, schliesst den Bildschirm,
        // den er gleich brauchen wollte -- genau die Schleife vom 17.09. In
        // dieser kurzen Aufbauzeit wird lieber gewartet und neu hingesehen.
        if (System.currentTimeMillis() - kisteAngeklicktUm < KISTE_AUFBAU_MS) {
            ctx.sleep(300);
            return true;
        }

        // --- 3b. Die Weltkarte -------------------------------------------
        //
        // Das Kathedralen-Fenster wird von der Weltkarte aus geoeffnet --
        // schliesst der Gang es, steht er dort und nicht in der Burg (Test
        // 25.09., 15:22: 9 s gesucht, "Weg nicht gefunden"). Erkannt an der
        // Dunklen Kathedrale auf der Karte. Unten links sitzt dort "Home" mit
        // Burg-Symbol, an derselben Stelle wie in der Burg der Map-Knopf.
        // Mit Vorlage worldmap_home per Bild, sonst an dieser festen Stelle.
        if (weltkarteVersuche < 3 && ctx.visibleIfKnown(screen, T_WELTKARTE)) {
            blind = 0;
            weltkarteVersuche++;
            // Blind nur auf die freie Karte: liegt noch ein Fenster mit rotem X
            // darueber (z. B. das Kathedralen-Fenster), erst das schliessen.
            if (!ctx.visibleIfKnown(screen, T_WELT_HOME)
                    && klickeWennDa(screen, ctx, T_ROTES_X, Sprache.t("Close the window over the world map", "Fenster ueber der Weltkarte schliessen"))) {
                ctx.sleepUntilChanged(1200);
                return true;
            }
            Weltkarte.heim(screen, ctx, "Kisten-Gang");
            klicks++;
            return true;
        }
        if (klickeWennDa(screen, ctx, T_ROTES_X, Sprache.t("Close the window to get into the castle", "Fenster schliessen, um in die Burg zu kommen"))) {
            ctx.sleepUntilChanged(1200);
            return true;
        }

        // --- 5. Nichts erkannt -----------------------------------------
        //
        // Kurz nach einem "Open": vermutlich die Animation, nur ohne "Chests
        // remaining" (Test 25.09., 20:47: nach dem letzten "x1" 10 s nichts
        // erkannt, dann "Weg nicht gefunden" -- die Tasche blieb halb voll).
        // Dann antippen statt blind zu zaehlen.
        if (System.currentTimeMillis() - offenGeklicktUm < NACH_OFFEN_MS
                && tippsInFolge < MAX_TIPPS_IN_FOLGE) {
            tippen(ctx);
            return true;
        }
        if (++blind >= MAX_BLIND) {
            beenden(Sprache.t("way not found", "Weg nicht gefunden"));
            return false;
        }
        ctx.sleep(300);
        return true;
    }

    /** Klickt eine Vorlage, wenn sie da ist -- und zaehlt die Klicks mit. */
    /** Nach der Klickgrenze: aus dem Oeffnen-Bildschirm und der Tasche heraus, ohne eine neue Kiste zu oeffnen. true = etwas getan. */
    private boolean ausDerTascheRaus(Mat screen, BotContext ctx) {
        if (ctx.clickIfVisibleIfKnown(screen, T_SCHLIESSEN, Sprache.t("Click limit reached -- leave the opening screen (Close)",
                "Klickgrenze erreicht -- Oeffnen-Bildschirm verlassen (Close)"))) {
            tippsInFolge = 0;
            ctx.sleepUntilChanged(500, 1500);
            return true;
        }
        if (ctx.visibleIfKnown(screen, T_RESTLICH)) {   // die Animation laeuft noch: antippen, bis "Close" kommt
            tippen(ctx);
            return true;
        }
        if ((ctx.visibleIfKnown(screen, T_TITEL) || ctx.visibleIfKnown(screen, T_OPEN_10) || ctx.visibleIfKnown(screen, T_OPEN_1))
                && ctx.clickIfVisibleIfKnown(screen, T_ROTES_X, Sprache.t("Click limit reached -- close the bag",
                        "Klickgrenze erreicht -- Tasche schliessen"))) {
            ctx.sleepUntilChanged(1200);
            return true;
        }
        return false;   // von der Tasche ist nichts mehr zu sehen
    }

    /**
     * Sicherheitsnetz fuer die Kathedrale (Bericht #A6Y3): steht ohne laufenden Kisten-Gang noch der Oeffnen-Bildschirm
     * oder die Tasche da -- etwa nach einem Neustart mitten in der Animation --, wird er weggeklickt, statt dass die
     * Kathedrale "nichts erkannt" meldet und stehen bleibt. true = etwas getan.
     */
    public static boolean kistenBildschirmWegklicken(Mat screen, BotContext ctx) {
        if (ctx.clickIfVisibleIfKnown(screen, T_SCHLIESSEN, Sprache.t("Leftover chest opening screen -- Close",
                "Uebriger Kisten-Bildschirm -- Close"))) {
            ctx.sleepUntilChanged(500, 1500);
            return true;
        }
        if (ctx.visibleIfKnown(screen, T_RESTLICH)) {
            int x = (int) Math.round(ctx.width() * TIPP_X), y = (int) Math.round(ctx.height() * TIPP_Y);
            Log.action("Klick", Sprache.t("Leftover chest animation -- tap it  [" + x + "," + y + "]",
                    "Uebrige Kisten-Animation -- antippen  [" + x + "," + y + "]"));
            ctx.input().click(x, y, 8);
            ctx.sleep(250);
            return true;
        }
        if (ctx.visibleIfKnown(screen, T_TITEL) && ctx.clickIfVisibleIfKnown(screen, T_ROTES_X,
                Sprache.t("Bag still open -- close it", "Tasche noch offen -- schliessen"))) {
            ctx.sleepUntilChanged(1200);
            return true;
        }
        return false;
    }

    private boolean klickeWennDa(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.clickIfVisibleIfKnown(screen, vorlage, grund)) return false;
        klicks++;
        return true;
    }
}
