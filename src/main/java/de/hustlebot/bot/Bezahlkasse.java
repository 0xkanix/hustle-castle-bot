package de.hustlebot.bot;

import de.hustlebot.core.Bezahlung;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.OptionalLong;
import de.hustlebot.core.Sprache;

/**
 * Bezahlen auf dem Teilnahme-Bildschirm (Teil A, Autor 27.09.): Tickets
 * (Fackeln, Schluessel) oder Nahrung, je nach Einstellung in core.Bezahlung.
 *
 * Jeder der drei Modi (Verlies, Kathedrale, Arena) hat eine eigene Kasse.
 * Er ruft sie an drei Stellen:
 *   - ganz vorn in step(): schritt() -- laeuft ein eigener Gang (zur Burg,
 *     Nahrung ablesen, Nahrungskisten oeffnen), gehoert ihm der Schritt;
 *   - vor "Participate": vorTeilnahme() -- stellt den Reiter um und prueft
 *     Preis, Limit und Vorrat;
 *   - nach "Participate": teilgenommen() -- zaehlt die Nahrung mit.
 * Sind die Tickets alle, fragt der Modus ticketsAlle(): darf mit Nahrung
 * weitergespielt werden?
 *
 * OBERSTE REGEL: NIE Diamanten. Reicht die Nahrung nicht (oder ist das nicht
 * sicher), wird "Participate" nicht gedrueckt -- sonst oeffnet das Spiel das
 * Kauf-Fenster. Kommt es trotzdem (verlesener Vorrat), klickt schritt() es
 * mit "No" weg.
 */
final class Bezahlkasse {

    /** Der Apfel oben im Teilnahme-Fenster -- rechts daneben der Vorrat. */
    private static final String T_VORRAT = "mo_nahrung_oben";
    private static final String T_ROTES_X = "close_x";
    private static final String T_WELT_HOME = "worldmap_home";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";
    /** Das Kauf-Fenster "Buy the missing resources?" (aus dem Clan-Krieg). */
    private static final String T_KAUF = "cw_buy_resources";
    private static final String T_KAUF_NEIN = "cw_buy_no";

    private final String modus, anzeige;
    private final String tFood, tFoodOff, tTicketsOff, tApfel, tKnoten, tFensterX;
    private final Nahrungskisten kisten = new Nahrungskisten();

    private enum Phase { NICHTS, ZUR_BURG_LESEN, ZUR_BURG_KISTEN, KISTEN }
    private Phase phase = Phase.NICHTS;

    /** Wird gerade mit Nahrung bezahlt? (Bei "erst Tickets" erst ab Ticket 0.) */
    private boolean mitNahrung;
    private long preis = -1;
    /** Nahrung in der Burg, seither mit Nahrung ausgegeben. */
    private long vorratBurg = -1, seither = 0;
    /** Seit der letzten Teilnahme schon in der Burg abgelesen / Kisten versucht? */
    private boolean burgFrisch, kistenVersucht;
    /** Preis und Vorrat nur einmal je Teilnahme-Bildschirm lesen (je ~1 s). */
    private boolean preisFrisch, vorratFrisch;
    private long vorratOben = -1;
    private long kistenZiel;
    private int wegVersuche, leseVersuche, preisVersuche, reiterVersuche, ticketNull;
    /** Reiter-Klicks auf diesem Teilnahme-Bildschirm -- nie endlos (Vorlage koennte auch den gewaehlten treffen). */
    private int reiterKlicks;
    /** Wann zuletzt mit Nahrung "Participate" gedrueckt wurde -- nur kurz danach kann das Kauf-Fenster kommen. */
    private long teilgenommenUm;
    private static final long KAUF_FENSTER_MS = 20_000;
    private static final int MAX_REITER_KLICKS = 8;   // 07.10.: Shrum-Kartenfenster schlucken Klicks (wie Hauptbot)
    private boolean ticketsFrisch, imKreislauf, nahrungVorbei;

    /**
     * @param tTicketsOff der Reiter fuer Tickets, wenn er NICHT gewaehlt ist
     *                    (zum Zurueckschalten); null = gibt es nicht
     */
    Bezahlkasse(String modus, String tFood, String tFoodOff, String tTicketsOff,
                String tApfel, String tKnoten, String tFensterX) {
        this.modus = modus;
        this.anzeige = Bezahlung.anzeigeName(modus);
        this.tFood = tFood;
        this.tFoodOff = tFoodOff;
        this.tTicketsOff = tTicketsOff;
        this.tApfel = tApfel;
        this.tKnoten = tKnoten;
        this.tFensterX = tFensterX;
    }

    /** Neuer Auftrag (aus reset() des Modus). */
    /** Warum der Auftrag zuletzt hier endete (fuer "Done" und den Bericht an Autor), sonst null. */
    private String endeGrund;

    String endeGrund() {
        return endeGrund;
    }

    void auftragBeginnt() {
        endeGrund = null;
        imKreislauf = false;
        nahrungVorbei = false;
        mitNahrung = art().startetMitNahrung();   // Mia 21.10.: auch "food+chests" fasst nie einen Ticket-Reiter an
        phase = Phase.NICHTS;
        preis = -1;
        vorratBurg = -1;
        seither = 0;
        burgFrisch = kistenVersucht = preisFrisch = vorratFrisch = preisRot = false;
        wegVersuche = leseVersuche = preisVersuche = reiterVersuche = ticketNull = reiterKlicks = 0;
        teilgenommenUm = 0;
        ticketsFrisch = false;
        if (art().mitNahrung()) {
            long l = Bezahlung.limit(modus);
            Log.info(anzeige, Sprache.t("Paying with: " + art().anzeige + (l > 0 ? " (food limit " + Bezahlung.zahl(l) + ")" : "") + ".",
                    "Bezahlt wird: " + art().anzeige
                    + (l > 0 ? " (Nahrungs-Limit " + Bezahlung.zahl(l) + ")" : "") + "."));
        }
    }

    Bezahlung.Art art() {
        return Bezahlung.art(modus);
    }

    /**
     * Event-Automatik (Autor, 28.09.): Ist die Nahrung vorbei (leer, keine
     * Kisten mehr in der Tasche oder das Nahrungs-Limit erreicht), endet der
     * Auftrag NICHT -- es geht mit Schluesseln und Schluessel-Kisten weiter,
     * danach ins Hammer-Event.
     */
    void imKreislauf() {
        imKreislauf = true;
    }

    /** Nach dem Hammer-Event: Nahrung darf wieder versucht werden (Autor). */
    void nahrungWiederErlauben() {
        nahrungVorbei = false;
        kistenVersucht = false;
    }

    boolean mitNahrung() {
        return mitNahrung;
    }

    /**
     * Tickets (Fackeln, Schluessel) sind alle. true = ab jetzt wird mit
     * Nahrung weitergespielt; false = der Modus macht wie bisher weiter.
     */
    boolean ticketsAlle() {
        Bezahlung.Art a = art();
        if (mitNahrung || nahrungVorbei
                || (a != Bezahlung.Art.ERST_TICKETS && a != Bezahlung.Art.ALLES)) return false;
        mitNahrung = true;
        preisFrisch = vorratFrisch = false;
        Log.info(anzeige, Sprache.t("Out of tickets -- paying with food from now on (" + a.anzeige + ").",
                "Tickets alle -- ab jetzt wird mit Nahrung bezahlt (" + a.anzeige + ")."));
        return true;
    }

    /**
     * Ganz vorn in step(). Laeuft ein eigener Gang, gehoert ihm der Schritt;
     * sonst null, und der Modus macht normal weiter.
     */
    GameMode.Outcome schritt(Mat screen, BotContext ctx) {
        // Laeuft ein eigener Gang, kommt der Modus nicht an seine eigene
        // Update-Behandlung -- deshalb hier (AFK-Rauswurf).
        if (phase != Phase.NICHTS && Stoerfenster.afkUpdate(screen, ctx, anzeige)) return GameMode.Outcome.ACTED;
        if (phase != Phase.NICHTS && Stoerfenster.ereignisTextWegklicken(screen, ctx, anzeige)) return GameMode.Outcome.ACTED;
        GameMode.Outcome kauf = kaufFensterAbwehren(screen, ctx);
        if (kauf != null) return kauf;
        switch (phase) {
            case NICHTS -> {
                return null;
            }
            case KISTEN -> {
                if (kisten.aktiv()) {
                    kisten.schritt(screen, ctx);
                    return GameMode.Outcome.ACTED;
                }
                // Fertig: in der Burg gleich frisch ablesen, was jetzt da ist.
                kistenVersucht = true;
                phase = Phase.ZUR_BURG_LESEN;
                wegVersuche = leseVersuche = 0;
                return GameMode.Outcome.ACTED;
            }
            default -> {
                return zurBurg(screen, ctx);
            }
        }
    }

    /**
     * Das Kauf-Fenster "Buy the missing resources?" darf es nie geben: weg
     * damit ("No"), und die Nahrung gilt als leer. Gesucht wird nur in den
     * ersten Sekunden nach einem Participate mit Nahrung -- nur dann kann es
     * kommen, und so kostet es waehrend der Runs keine Bildsuche.
     *
     * Eigene Methode, damit die Arena sie auch mitten im Turnier-Start rufen
     * kann (dort laeuft schritt() erst, wenn kein Turnier laeuft).
     */
    GameMode.Outcome kaufFensterAbwehren(Mat screen, BotContext ctx) {
        if (teilgenommenUm == 0 || System.currentTimeMillis() - teilgenommenUm > KAUF_FENSTER_MS) return null;
        if (!ctx.visibleIfKnown(screen, T_KAUF)) return null;
        if (ctx.clickIfVisibleIfKnown(screen, T_KAUF_NEIN, Sprache.t("Purchase window: \"No\" (never diamonds)", "Kauf-Fenster: \"No\" (nie Diamanten)"))
                || ctx.clickIfVisibleIfKnown(screen, T_ROTES_X, Sprache.t("Close the purchase window (never diamonds)", "Kauf-Fenster schliessen (nie Diamanten)"))) {
            Log.alarm(anzeige, Sprache.t("The purchase window \"Buy the missing resources?\" came up -- dismissed with \"No\", NO diamonds were spent. Not enough food.",
                    "Das Kauf-Fenster \"Buy the missing resources?\" kam -- mit \"No\" "
                    + "weggeklickt, es wurden KEINE Diamanten ausgegeben. Die Nahrung reicht nicht."));
            teilgenommenUm = 0;
            vorratBurg = 0;
            seither = 0;
            burgFrisch = true;
            vorratOben = -1;
            vorratFrisch = true;
            ctx.sleepUntilChanged(1000);
            return GameMode.Outcome.ACTED;
        }
        return null;
    }

    /**
     * Auf dem Teilnahme-Bildschirm, bevor "Participate" gedrueckt wird.
     * null = alles bereit: der Modus drueckt jetzt Participate und ruft
     * danach teilgenommen(). Sonst das Ergebnis dieses Schritts.
     */
    GameMode.Outcome vorTeilnahme(Mat screen, BotContext ctx) {
        if (!mitNahrung) {
            // Hoechstens ein paar Mal je Bildschirm: passt die Vorlage auch auf den
            // schon gewaehlten Reiter, kaeme der Bot sonst nie zu Participate.
            if (tTicketsOff != null && reiterKlicks < MAX_REITER_KLICKS
                    && ctx.clickIfVisibleIfKnown(screen, tTicketsOff, Sprache.t("Switch to tickets (not food)", "Auf Tickets umstellen (nicht Nahrung)"))) {
                reiterKlicks++;
                ctx.sleepUntilChanged(900);
                return GameMode.Outcome.ACTED;
            }
            return null;
        }

        // Test (!nahrungstest): erst genau eine Food-Kiste aus der Tasche oeffnen.
        if (Bezahlung.kistenTestAbholen()) {
            kistenZiel = 0;   // 0 = so viel wie jetzt da ist + 1 -> eine Kiste
            phase = Phase.ZUR_BURG_KISTEN;
            wegVersuche = leseVersuche = 0;
            reiterKlicks = 0;
            Log.info(anzeige, Sprache.t("Test: open a food chest from the bag before the run.",
                    "Test: vor dem Run eine Food-Kiste aus der Tasche oeffnen."));
            return GameMode.Outcome.ACTED;
        }

        // 1. Der Reiter "For food" muss gewaehlt sein.
        if (!ctx.visibleIfKnown(screen, tFood)) {
            if (reiterKlicks < MAX_REITER_KLICKS
                    && ctx.clickIfVisibleIfKnown(screen, tFoodOff, Sprache.t("Switch to \"For food\"", "Auf \"For food\" (Nahrung) umstellen"))) {
                reiterKlicks++;
                ctx.sleepUntilChanged(900);
                return GameMode.Outcome.ACTED;
            }
            if (!ctx.matcher().has(tFood) || !ctx.matcher().has(tFoodOff)) {
                return ende(screen, ctx, Sprache.t("Food is not possible yet: the templates " + tFood + " and " + tFoodOff + " are missing (Capture-Images.exe). NOT playing with tickets.",
                        "Mit Nahrung geht es noch nicht: es fehlen die Vorlagen "
                        + tFood + " und " + tFoodOff + " (Capture-Images.exe). Es wird NICHT mit Tickets gespielt."));
            }
            if (++reiterVersuche > 20) {
                return ende(screen, ctx, Sprache.t("The \"For food\" tab was not found.",
                        "Der Reiter \"For food\" wurde nicht gefunden."));
            }
            ctx.sleep(300);
            return GameMode.Outcome.WAITING;
        }
        reiterVersuche = 0;

        // 2. Der Preis rechts neben dem Apfel im Participate-Knopf.
        if (!preisFrisch) {
            long p = zahlRechtsVon(screen, ctx, tApfel, Matcher.DEFAULT_THRESHOLD, 6);
            if (p >= 100) preisRot = letzterWertRot;
            if (p >= 100) {
                if (p != preis) Log.info(anzeige, Sprache.t("Price in food: " + Bezahlung.zahl(p) + ".",
                        "Preis mit Nahrung: " + Bezahlung.zahl(p) + "."));
                preis = p;
                preisFrisch = true;
                preisVersuche = 0;
            } else if (preis > 0) {
                preisFrisch = true;     // gerade unlesbar: der zuletzt gelesene Preis gilt
            } else {
                // Laenger hinsehen (Nachtlauf 29.09., 06:36): Das traege Spiel zeigte den
                // Preis nach dem Reiterwechsel erst spaeter -- nach 1 s gab der Bot auf und
                // liess die Nahrung aus. Jetzt bis zu 8 Versuche (~3 s).
                if (++preisVersuche <= 8) {
                    ctx.sleep(400);
                    return GameMode.Outcome.WAITING;
                }
                preisFoto(screen, ctx, tApfel);
                return ende(screen, ctx, Sprache.t("The price in the Participate button is not readable (template " + tApfel + ": ONLY the small apple, tightly cropped). Without a price the bot never " + "participates with food.",
                        "Der Preis im Participate-Knopf ist nicht lesbar (Vorlage "
                        + tApfel + ": NUR der kleine Apfel, eng zugeschnitten). Ohne Preis wird nie mit "
                        + "Nahrung teilgenommen."));
            }
        }

        // 3. Das Nahrungs-Limit dieses Auftrags.
        if (!Bezahlung.limitErlaubt(modus, preis)) {
            return ende(screen, ctx, Sprache.t("Food limit reached: used " + Bezahlung.zahl(Bezahlung.verbraucht(modus)) + ", limit " + Bezahlung.zahl(Bezahlung.limit(modus)) + ", the next round costs " + Bezahlung.zahl(preis) + ".",
                    "Nahrungs-Limit erreicht: verbraucht " + Bezahlung.zahl(Bezahlung.verbraucht(modus))
                    + ", Limit " + Bezahlung.zahl(Bezahlung.limit(modus)) + ", die naechste Runde kostet "
                    + Bezahlung.zahl(preis) + "."));
        }

        // 4. Reicht der Vorrat? Oben im Fenster, sonst der Stand aus der Burg.
        if (!vorratFrisch) {
            vorratOben = ctx.matcher().has(T_VORRAT) ? zahlRechtsVon(screen, ctx, T_VORRAT, 0.72, 6) : -1;
            vorratFrisch = true;
        }
        long vorrat = vorratOben;
        boolean sicher = vorrat >= 0;
        if (vorrat < 0 && vorratBurg >= 0) {
            vorrat = vorratBurg - seither;
            sicher = burgFrisch;
        }
        // Ein ROTER Preis ist die Aussage des Spiels selbst: die Nahrung reicht nicht. Dann
        // stimmt der gemerkte Vorrat nicht (Hauptbot 01.10.) -- er gilt als knapp zu klein, und
        // es geht direkt zu den Nahrungskisten statt achtmal ins Leere.
        if (preisRot && vorrat >= preis) {
            Log.info(anzeige, Sprache.t("The price is red -- so the remembered stock (" + Bezahlung.zahl(vorrat) + ") is wrong. It counts as too small.",
                    "Der Preis ist rot -- der gemerkte Vorrat (" + Bezahlung.zahl(vorrat)
                    + ") stimmt also nicht. Er gilt als zu klein."));
            vorrat = Math.max(0, preis - 1);
            vorratBurg = vorrat + seither;
            vorratOben = -1;
            sicher = true;
        }
        if (vorrat >= preis) return null;

        if (!sicher) {
            phase = Phase.ZUR_BURG_LESEN;
            wegVersuche = leseVersuche = 0;
            reiterKlicks = 0;   // zurueck am Fenster darf der Reiter neu gestellt werden
            Log.info(anzeige, Sprache.t(vorrat < 0 ? "How much food there is isn't shown here -- quick trip to the castle to read it." : "Food might be short (estimated " + Bezahlung.zahl(vorrat) + ") -- reading it fresh in the castle.",
                    vorrat < 0
                    ? "Wie viel Nahrung da ist, steht hier nicht -- kurz in die Burg, dort ablesen."
                    : "Die Nahrung koennte knapp sein (geschaetzt " + Bezahlung.zahl(vorrat)
                      + ") -- in der Burg frisch ablesen."));
            return GameMode.Outcome.ACTED;
        }
        if (art().oeffnetKisten() && !kistenVersucht) {   // Mia 21.10.: "alles" und "food+chests"; "food" bleibt ohne Kisten
            kistenZiel = preis;
            phase = Phase.ZUR_BURG_KISTEN;
            wegVersuche = 0;
            reiterKlicks = 0;
            Log.info(anzeige, Sprache.t("Not enough food (" + Bezahlung.zahl(vorrat) + ", needed " + Bezahlung.zahl(preis) + ") -- opening food chests in the bag first.",
                    "Die Nahrung reicht nicht (" + Bezahlung.zahl(vorrat) + ", gebraucht "
                    + Bezahlung.zahl(preis) + ") -- erst Nahrungskisten in der Tasche oeffnen."));
            return GameMode.Outcome.ACTED;
        }
        return ende(screen, ctx, Sprache.t("Not enough food left (" + Bezahlung.zahl(Math.max(0, vorrat)) + ", the round costs " + Bezahlung.zahl(preis) + ")" + (kistenVersucht ? ", even after the food chests" : "") + ". Diamonds are never spent.",
                "Die Nahrung reicht nicht mehr (" + Bezahlung.zahl(Math.max(0, vorrat))
                + ", die Runde kostet " + Bezahlung.zahl(preis) + ")"
                + (kistenVersucht ? ", auch nach den Nahrungskisten" : "") + ". Diamanten werden nie ausgegeben."));
    }

    /**
     * Nur bei "erst Tickets, dann Nahrung" und "alles": die Tickets rechts neben
     * ihrem Symbol lesen (einmal je Teilnahme-Bildschirm). Zweimal hintereinander
     * 0 -> ab jetzt Nahrung (ACTED). Eine einzelne 0 wird noch einmal gelesen
     * (WAITING) -- die Texterkennung verliest sich gern. null = weiter wie bisher.
     */
    GameMode.Outcome ticketsPruefen(Mat screen, BotContext ctx, String tSymbol) {
        if (mitNahrung || !art().mitNahrung() || ticketsFrisch || !ctx.matcher().has(tSymbol)) return null;
        long n = zahlRechtsVon(screen, ctx, tSymbol, Matcher.DEFAULT_THRESHOLD, 4);
        if (n != 0) {
            ticketNull = 0;
            ticketsFrisch = true;   // gelesen (oder nicht lesbar): weiter wie bisher
            return null;
        }
        if (++ticketNull < 2) {
            ctx.sleep(300);
            return GameMode.Outcome.WAITING;
        }
        ticketNull = 0;
        return ticketsAlle() ? GameMode.Outcome.ACTED : null;
    }

    /**
     * Mit Nahrung steht im Participate-Knopf ein anderer Preis als bei der
     * Aufnahme. Passt die ganze Vorlage nicht, nur das Wort vergleichen --
     * wie in der Arena (Matcher.findOberteil).
     */
    boolean participateOberteil(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!mitNahrung || !ctx.matcher().has(vorlage)) return false;
        Optional<Matcher.Hit> t = ctx.matcher().findOberteil(screen, vorlage, 0.55, Matcher.DEFAULT_THRESHOLD);
        if (t.isEmpty()) return false;
        Log.action("Klick", Sprache.t(grund + " (only the word compared)  [" + vorlage + " at " + t.get().centerX() + "," + t.get().centerY() + "]",
                grund + " (nur das Wort verglichen)  [" + vorlage + " bei "
                + t.get().centerX() + "," + t.get().centerY() + "]"));
        ctx.input().click(t.get());
        return true;
    }

    /**
     * Letzter Weg zum Knopf: der kleine Apfel IM Participate-Knopf (Vorlage zum Preislesen). 22.10. nachts, Verlies mit
     * Nahrung: weder der Fackel-Knopf noch sein Oberteil passten auf "Participate 150 000" -- der Bot stand still.
     * Nur mit Nahrung, nur wenn der Reiter "For food" gewaehlt ist und der Preis gelesen wurde.
     */
    boolean apfelImKnopf(Mat screen, BotContext ctx, String grund) {
        if (!mitNahrung || !preisFrisch || !ctx.visibleIfKnown(screen, tFood)) return false;
        return ctx.clickIfVisibleIfKnown(screen, tApfel, grund + Sprache.t(" (apple in the button)", " (Apfel im Knopf)"));
    }

    /**
     * Teilgenommen, aber nicht mit Nahrung bezahlt (Verlies: Einladung ohne
     * Food-Reiter) -- nichts verbuchen, nur die Merker fuer den naechsten
     * Bildschirm zuruecksetzen.
     */
    void teilgenommenOhneNahrung() {
        ticketsFrisch = false;
        reiterKlicks = 0;
        preisFrisch = vorratFrisch = burgFrisch = kistenVersucht = false;
    }

    /** "Participate" wurde gedrueckt. */
    void teilgenommen() {
        ticketsFrisch = false;
        reiterKlicks = 0;
        if (mitNahrung) teilgenommenUm = System.currentTimeMillis();
        if (mitNahrung && preis > 0) {
            Bezahlung.verbuche(modus, preis);
            seither += preis;
            Log.info(anzeige, Sprache.t("Participated with food (" + Bezahlung.zahl(preis) + "), " + Bezahlung.zahl(Bezahlung.verbraucht(modus)) + " used in this job.",
                    "Mit Nahrung teilgenommen (" + Bezahlung.zahl(preis) + "), in diesem Auftrag "
                    + Bezahlung.zahl(Bezahlung.verbraucht(modus)) + " verbraucht."));
        }
        preisFrisch = vorratFrisch = burgFrisch = kistenVersucht = false;
    }

    // --- Der Weg in die Burg -------------------------------------------------

    private GameMode.Outcome zurBurg(Mat screen, BotContext ctx) {
        if (++wegVersuche > 40) {
            return ende(screen, ctx, Sprache.t("The way into the castle (read food / chests) was not found.",
                    "Der Weg in die Burg (Nahrung ablesen / Kisten) wurde nicht gefunden."));
        }
        boolean inBurg = ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT);
        if (inBurg) {
            if (phase == Phase.ZUR_BURG_KISTEN) {
                if (!kisten.einsatzbereit(ctx)) {
                    return ende(screen, ctx, Sprache.t("Opening food chests is not possible yet -- templates are missing " + "(group CLANKRIEG, food).",
                            "Nahrungskisten oeffnen geht noch nicht -- es fehlen Vorlagen "
                            + "(Gruppe CLANKRIEG, Nahrung)."));
                }
                long ziel = kistenZiel;
                if (ziel <= 0) {   // Test: eine Kiste mehr als jetzt da ist
                    OptionalLong jetzt = Nahrungskisten.nahrungLesen(ctx);
                    if (jetzt.isEmpty()) {
                        if (++leseVersuche >= 3) return ende(screen, ctx, Sprache.t("Test: food in the castle not readable.",
                                "Test: Nahrung in der Burg nicht lesbar."));
                        ctx.sleep(400);
                        return GameMode.Outcome.ACTED;
                    }
                    ziel = jetzt.getAsLong() + 1;
                }
                if (kistenZiel > 0) {
                    // Autor, 29.09.: "so viele Kisten oeffnen, dass er moeglichst genau an
                    // 1,2 Mio Nahrung kommt" -- mindestens aber der Preis des Runs.
                    kisten.startenAuffuellen(Math.max(Bezahlung.lager(), ziel), ziel, false, false);
                } else {
                    kisten.starten(ziel, false, false);   // Test (!nahrungstest): genau eine Kiste
                }
                phase = Phase.KISTEN;
                return GameMode.Outcome.ACTED;
            }
            OptionalLong n = Nahrungskisten.nahrungLesen(ctx);
            if (n.isEmpty()) {
                // Bericht K58J (Mia, 19.10. 14:33): Nach "Home" war die Nahrungsleiste noch nicht da, nach 3 Versuchen
                // (gut 3 s) endete die Arena mit "Done". Um 13:44 klappte es erst nach 5 s. Jetzt bis zu ~10 s Geduld.
                if (++leseVersuche >= 12) {
                    return ende(screen, ctx, Sprache.t("The food at the top of the castle is not readable (template " + "cw_nahrung_burg). Without a stock the bot never participates with food.",
                            "Die Nahrung oben in der Burg ist nicht lesbar (Vorlage "
                            + "cw_nahrung_burg). Ohne Vorrat wird nie mit Nahrung teilgenommen."));
                }
                ctx.sleep(600);
                return GameMode.Outcome.ACTED;
            }
            long mind = kisten.mindestensNachKisten();
            if (mind > n.getAsLong()) {
                Log.warn(anzeige, Sprache.t("The food in the castle reads as " + Bezahlung.zahl(n.getAsLong()) + ", after the chests it should be at least " + Bezahlung.zahl(mind) + " -- misread (large number), the minimum applies.",
                        "Die Nahrung in der Burg liest sich als " + Bezahlung.zahl(n.getAsLong())
                        + ", nach den Kisten muessten es mindestens " + Bezahlung.zahl(mind)
                        + " sein -- Lesefehler (grosse Zahl), es gilt der Mindestwert."));
                n = OptionalLong.of(mind);
            }
            vorratBurg = n.getAsLong();
            seither = 0;
            burgFrisch = true;
            // Der Preis wird am Fenster neu gelesen: nach den Kisten ist er nicht mehr rot
            // (Hauptbot 01.10.: der gemerkte rote Preis galt sonst nach dem Auffuellen weiter).
            preisFrisch = false;
            preisRot = false;
            // Der Vorrat oben im Teilnahme-Fenster war VOR den Kisten gelesen --
            // beim naechsten Mal frisch lesen, sonst gilt der alte (zu kleine) Wert.
            vorratOben = -1;
            vorratFrisch = false;
            phase = Phase.NICHTS;
            Log.info(anzeige, Sprache.t("Food in the castle: " + Bezahlung.zahl(vorratBurg) + ".",
                    "Nahrung in der Burg: " + Bezahlung.zahl(vorratBurg) + "."));
            return GameMode.Outcome.ACTED;
        }
        boolean karte = ctx.visibleIfKnown(screen, T_WELT_HOME) || ctx.visibleIfKnown(screen, tKnoten);
        if (ctx.clickIfVisibleIfKnown(screen, tFensterX, Sprache.t("Close the window (on the way to the castle)", "Fenster schliessen (auf dem Weg in die Burg)"))
                || (!karte && ctx.clickIfVisibleIfKnown(screen, T_ROTES_X, Sprache.t("Close the window (on the way to the castle)", "Fenster schliessen (auf dem Weg in die Burg)")))) {
            ctx.sleepUntilChanged(1200);
            return GameMode.Outcome.ACTED;
        }
        if (karte) {
            Weltkarte.heim(screen, ctx, anzeige + Sprache.t(" (food)", " (Nahrung)"));
            ctx.sleepUntilChanged(1500);
            return GameMode.Outcome.ACTED;
        }
        ctx.sleep(300);
        return GameMode.Outcome.WAITING;
    }

    private GameMode.Outcome ende(Mat screen, BotContext ctx, String grund) {
        phase = Phase.NICHTS;
        // Mia 21.10.: "food+chests" geht auch im Kreislauf nie auf Schluessel zurueck -- der Auftrag endet.
        if (imKreislauf && mitNahrung && !art().nieTickets()) {
            // Kreislauf: zurueck auf Schluessel, der Auftrag laeuft weiter.
            mitNahrung = false;
            nahrungVorbei = true;
            preisFrisch = vorratFrisch = false;
            Log.info(anzeige, Sprache.t(grund + " Carrying on with keys (event automation).",
                    grund + " Weiter mit Schluesseln (Event-Automatik)."));
            return GameMode.Outcome.ACTED;
        }
        Log.warn(anzeige, grund + " Der Auftrag endet hier.");
        endeGrund = grund;
        ctx.clickIfVisibleIfKnown(screen, tFensterX, Sprache.t("Close the participation window", "Teilnahme-Fenster schliessen"));
        return GameMode.Outcome.FINISHED;
    }

    /** Eine Zahl rechts neben einer Vorlage, -1 wenn nicht zu sehen oder nicht lesbar. */
    /**
     * Rote Ziffern auf dem gruenen Knopf lesen: rote Bildpunkte werden schwarz, alles
     * andere weiss (dreifach vergroessert), dann liest die Texterkennung die Zahl.
     */
    static OptionalLong rotZahl(BufferedImage bild, int x, int y, int w, int h) {
        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(w, bild.getWidth() - x);
        h = Math.min(h, bild.getHeight() - y);
        if (w <= 4 || h <= 4) return OptionalLong.empty();
        int f = 3;
        BufferedImage sw = new BufferedImage(w * f, h * f, BufferedImage.TYPE_INT_RGB);
        int rotePunkte = 0;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int rgb = bild.getRGB(x + xx, y + yy);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                boolean rot = r > 140 && r - g > 70 && r - b > 50;
                if (rot) rotePunkte++;
                int farbe = rot ? 0x000000 : 0xFFFFFF;
                for (int dy = 0; dy < f; dy++) for (int dx = 0; dx < f; dx++) sw.setRGB(xx * f + dx, yy * f + dy, farbe);
            }
        }
        if (rotePunkte < 30) return OptionalLong.empty();   // keine roten Ziffern da
        String text = String.join(" ", de.hustlebot.core.Texterkennung.lies(sw));
        OptionalLong n = ArenaLeser.zahlAus(text);
        return n.isPresent() && n.getAsLong() >= 100 ? n : OptionalLong.empty();
    }

    private static long preisFotoUm;

    /**
     * Preis unlesbar (29.09., 16:01, trotz 3 s Hinsehen): Foto nach logs\preis-unlesbar-HHmmss.png
     * und wie gut die Apfel-Vorlage passt -- damit man sieht, ob der Apfel fehlt oder die Zahl.
     * Hoechstens alle 10 Minuten.
     */
    private static void preisFoto(Mat screen, BotContext ctx, String tApfel) {
        if (System.currentTimeMillis() - preisFotoUm < 10 * 60_000L) return;
        preisFotoUm = System.currentTimeMillis();
        try {
            Optional<Matcher.Hit> h = ctx.matcher().has(tApfel) ? ctx.matcher().find(screen, tApfel, 0.30) : Optional.empty();
            String apfel = h.map(x -> String.format("%.2f bei %d,%d", x.score(), x.centerX(), x.centerY())).orElse("nicht gefunden");
            BufferedImage bild = ctx.frame();
            java.nio.file.Path datei = java.nio.file.Path.of("logs", "preis-unlesbar-"
                    + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            boolean foto = bild != null && de.hustlebot.core.Beleg.schreiben(bild, "png", datei.toFile());
            Log.info("Bezahlen", Sprache.t("Price unreadable -- apple template " + tApfel + ": " + apfel + "." + (foto ? " Photo: " + datei : ""),
                    "Preis unlesbar -- Apfel-Vorlage " + tApfel + ": " + apfel + "." + (foto ? " Foto: " + datei : "")));
        } catch (Exception e) {
            Log.debug("Bezahlen", "Preis-Foto nicht gespeichert: " + e.getMessage());
        }
    }

    /** War die zuletzt gelesene Zahl rot geschrieben (Preis, den die Nahrung nicht deckt)? */
    private static boolean letzterWertRot;
    private boolean preisRot;

    private static long zahlRechtsVon(Mat screen, BotContext ctx, String vorlage, double schwelle, int breiten) {
        letzterWertRot = false;
        if (!ctx.matcher().has(vorlage)) return -1;
        BufferedImage bild = ctx.frame();
        Optional<Matcher.Hit> h = ctx.matcher().find(screen, vorlage, schwelle);
        if (bild == null || h.isEmpty()) return -1;
        Matcher.Hit a = h.get();
        OptionalLong n = ArenaLeser.zahlRechtsVon(bild, a, a.width() * breiten);
        if (n.isEmpty()) {
            n = Nahrungskisten.zahlVerstaerkt(bild, a.x() + a.width(), a.y() - 4,
                    a.width() * breiten, a.height() + 8, true);
        }
        if (n.isEmpty()) {
            // ROTER PREIS (29.09., 16:01 und 18:14): Reicht die Nahrung nicht, faerbt das
            // Spiel den Preis rot -- rot auf gruenem Knopf las die Texterkennung nicht,
            // und der Bot liess die Nahrung aus, statt Nahrungskisten zu oeffnen.
            n = rotZahl(bild, a.x() + a.width(), a.y() - a.height() / 3,
                    a.width() * breiten, a.height() + 2 * (a.height() / 3));
            if (n.isPresent()) {
                letzterWertRot = true;
                Log.info("Bezahlen", Sprache.t("Price read in red text: " + n.getAsLong() + " (red = not enough food).",
                        "Preis in roter Schrift gelesen: " + n.getAsLong()
                        + " (rot = die Nahrung reicht nicht)."));
            }
        }
        return n.isPresent() && n.getAsLong() >= 0 && n.getAsLong() < 100_000_000L ? n.getAsLong() : -1;
    }
}
