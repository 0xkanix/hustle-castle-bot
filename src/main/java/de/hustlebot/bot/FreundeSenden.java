package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Matcher;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Freunden Ressourcen senden (Autor, 05.10., mit Bildern): "so lange senden,
 * bis da oben eine 0 steht, einfach die ganze Zeit auf Send druecken ... Wenn
 * keine Knoepfe mehr zu sehen sind, aber der Bot trotzdem noch welche schicken
 * koennte, dann soll er bitte nach unten scrollen."
 *
 * DER WEG:
 *   Burg -> Pokal oben links -> Ranglistenfenster, Reiter "Friends" -> oben
 *   rechts steht "Resources that can be sent today:", darunter Geschenk + Zahl
 *   -> immer den obersten blauen "Send"-Knopf druecken, bis dort 0 steht ->
 *   kein Knopf mehr zu sehen: die Liste nach unten ziehen -> rotes X -> Burg.
 *
 * NIE (Autor, 05.10.): die Kiste am Balken oben ("Send 10 resources to get a
 * reward", fr_kiste_balken), "Add friends" unter der Liste, den Schalter
 * "Incoming friend requests", nichts mit Diamanten. Gedrueckt werden nur der
 * Pokal oben links, der Reiter "Friends", "Send" (nur in der Send-Spalte
 * zwischen Kiste und "Add friends") und das rote X oben rechts.
 *
 * Benutzt vom Modus "freundesenden" (einmal je Spieltag gleich nach dem
 * Titanen-Turm um 02:00, core/Freundeplan). Ein Schritt = eine Handlung, keine
 * innere Schleife -- Stopp und Pause des Motors greifen also zwischen je zwei
 * Klicks. schritt() liefert false, wenn der Gang vorbei ist; zurueckInBurg()
 * sagt, ob der Bot dann wieder in der Burg steht.
 *
 * VORSICHT BEI DER ZAHL (Gegenlese 05.10.): Gesendet wird nur, wenn oben eine
 * Zahl ueber 0 sicher und glaubhaft gelesen ist. Glaubhaft heisst: am Anfang
 * 1 bis 20, danach genau eins weniger als vor dem letzten Send. Eine 0 gilt
 * nur, wenn vorher 1 stand oder in der Send-Spalte kein blauer Knopf mehr zu
 * sehen ist -- und erst beim zweiten Lesen; bis dahin wird nichts gedrueckt.
 */
public final class FreundeSenden {

    private static final String WER = "Freunden senden";

    // --- Vorlagen (templates\gelernt\freunde, ausgeschnitten aus Autors Bildern vom 05.10.) ---
    static final String T_POKAL = "fr_pokal";                       // Burg oben links: goldener Pokal
    static final String T_POKAL_ALT = "rs_pokal";                   // derselbe Knopf (Burg-Stand)
    static final String T_REITER = "fr_reiter_friends";             // Reiter "Friends", noch nicht gewaehlt (beige)
    static final String T_REITER_AKTIV = "fr_reiter_friends_aktiv"; // Reiter "Friends", gewaehlt (tuerkis)
    static final String T_HEUTE = "fr_heute_text";                  // gruen "Resources that can" -- die Zahl steht darunter
    static final String T_KISTE = "fr_kiste_balken";                // Kiste am Balken 1/10 -- NIE anklicken
    static final String T_SEND = "fr_send";                         // blauer "Send"-Knopf
    static final String T_SENT = "fr_sent";                         // "Sent" mit Haken: schon gesendet
    static final String T_ADD = "fr_add_friends";                   // "Add friends" -- NIE anklicken, nur untere Grenze
    // Autor, 08.10.: Nach dem Senden "Get the resources" (gruener Knopf oben rechts im Reiter, nur da,
    // wenn Freunde etwas geschickt haben) -> Fenster "Claim the resources sent to you" -> "Claim all".
    static final String T_HOLEN = "fr_get_resources";               // gruen "Get the resources"
    static final String T_CLAIM_ALL = "fr_claim_all";               // gruen "Claim all" unten rechts
    static final String T_NO_SPACE = "fr_no_space";                 // Meldung "No space" (Lager voll)
    static final String T_LIMIT = "fr_limit_text";                  // orange "You have reached the resource limit"
    static final String T_CLAIM_OK = "fr_claim_ok";                 // gruen "Claim" in dieser Meldung
    /** So lange wird nach dem Senden auf "Get the resources" gewartet, dann gilt: nichts abzuholen. */
    private static final long ABHOLEN_MAX_MS = 8_000L;
    private static final List<String> X_VORLAGEN =
            List.of("close_x", "ar_close_x", "cw_close_x", "dc_close_x", "dg_close_x");
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";

    /** Mehr als 20 gehen am Tag nicht (Autor, 05.10.: 18 offen, nachdem er 2 gesendet hatte). */
    static final int MAX_JE_TAG = 20;
    private static final long MAX_DAUER_MS = 3 * 60_000L;
    /** Nach dem Schluss hat der Weg hinaus (rotes X, zurueck in die Burg) noch so lange Zeit. */
    private static final long RAUS_FRIST_MS = 60_000L;
    /**
     * Liegt zwischen zwei Schritten mehr als das, war der Bot angehalten (!pause) --
     * diese Zeit zaehlt nicht zur Zeitgrenze (Gegenlese 05.10.).
     */
    private static final long LUECKE_MS = 5000;
    /** So oft hintereinander darf die Zahl oben unlesbar oder unglaubhaft sein, dann Schluss. */
    private static final int MAX_LESEFEHLER = 6;
    /** So oft wird nach einer gelesenen 0 noch nachgelesen, bevor ohne weiteren Klick Schluss ist. */
    private static final int NULL_NACHLESEN = 3;
    private static final int MAX_KLICKS = 70;
    private static final int MAX_BLIND = 25;
    private static final int MAX_WISCHER = 30;
    private static final int MAX_POKAL = 3;
    private static final int MAX_REITER = 4;
    private static final double X_SCHWELLE = 0.70;
    /** Gemessen 05.10.: echte Send-Knoepfe 0,91-1,00 (0,84 nur unter Autors roten Pfeilen), "Sent" nie. */
    static final double SEND_SCHWELLE = 0.85;
    /** Graue (ausgegraute) Send-Knoepfe passen schwaecher auf die blaue Vorlage (08.10.). */
    static final double GRAU_SCHWELLE = 0.70;
    /** Nach einem Send-Klick so lange auf "Sent" oder eine kleinere Zahl warten. */
    private static final long WIRKUNG_MS = 4000;
    /** Woerter, hinter die die Zahl zum Lesen gesetzt wird (gemessen 05.10.: alle drei lesen "18"). */
    private static final String[] WOERTER = {"Nummer", "Zahl", "Anzahl"};

    private enum Phase { HIN, LISTE, ABHOLEN, RAUS }

    /** Abholen (08.10.): wann es begann, ob "Get the resources" bzw. "Claim all" gedrueckt wurde. */
    private long abholenAb;
    private int holenKlicks, claimKlicks, noSpaceKlicks;
    private boolean abgeholt, abholenFoto, claimFoto;
    /**
     * Die Zahl oben ist vom Knopf "Get the resources" verdeckt (08.10., Foto 13:25): dann wird ohne
     * Zahl gesendet -- solange ein blauer Send-Knopf da ist, hoechstens MAX_JE_TAG (Autor: "Der
     * Send-Knopf wird dann auch so grau, wenn er nicht mehr schicken kann").
     */
    private boolean ohneZahl;

    private boolean aktiv;
    private Phase phase = Phase.HIN;
    private long begonnen, zuletztGeklickt, sendGeklicktUm;
    /** Arbeitszeit ohne Pausen, der letzte Schritt, die Arbeitszeit beim Schluss (Beginn von RAUS). */
    private long aktivMs, letzterSchritt, rausAb;
    private int klickY, klicks, blind, wischer, ruhig, gesendet, sendKlicks, ohneWirkung;
    private int pokalKlicks, reiterKlicks, leseFehler, nullFolge;
    /** Die Zahl oben (-1 = noch nicht sicher gelesen), vor dem letzten Klick, beim Oeffnen. */
    private long rest = -1, restVorKlick = -1, startZahl = -1;
    /** Welche Zahl oben jetzt stehen muss (-1 = noch keine Erwartung, am Anfang). */
    private long erwartet = -1;
    /**
     * Eine "0" wurde einmal gelesen. Bis sie sich bestaetigt (oder eine Zahl ueber 0
     * sicher dasteht), wird NICHT gesendet (Gegenlese 05.10.: sonst ein Send bei 0).
     */
    private boolean nullGelesen;
    /** Am Listenende angekommen: einmal zurueck nach oben (falls ein Zug Zeilen uebersprungen hat). */
    private boolean hoch;
    private boolean bewegtRunter, knopfGesehen, tagFertig;
    /** Wie oft hintereinander nur graue Send-Knoepfe zu sehen waren (11.10.: erst beim zweiten Blick glauben). */
    private int grauBlicke;
    /** Der Gang endete in der Burg (auch nach einem Abbruch) -- sonst haengt der Bot irgendwo. */
    private boolean zurueckInBurg;
    private Mat listeVorher;
    private String ergebnis = "";
    /** Grund, warum nicht (mehr) gesendet wird -- dann nur noch hinaus. */
    private String schluss;

    public boolean aktiv() { return aktiv; }

    /** Wann zuletzt geklickt wurde (0 = noch nie) -- fuer die Fortschrittsmeldung (Bericht #8GD9). */
    public long zuletztGeklickt() { return zuletztGeklickt; }

    /** Wie viele Ressourcen in diesem Gang gesendet wurden. */
    public int gesendet() { return gesendet; }

    /** Die zuletzt gelesene Zahl oben (-1 = nicht lesbar). */
    public long rest() { return rest; }

    /** Ist fuer heute alles gesendet (oben 0) oder hat kein Freund mehr einen Send-Knopf? */
    public boolean tagFertig() { return tagFertig; }

    /** Warum der Gang endete (fuer das Protokoll). */
    public String ergebnis() { return ergebnis; }

    /** Ist der Gang zu Ende gegangen, ohne haengen zu bleiben? */
    public boolean sauberBeendet() { return !ergebnis.startsWith("abgebrochen"); }

    /**
     * Steht der Bot am Ende wieder in der Burg (Rangliste zu)? Auch nach einem
     * Abbruch -- dann kann die Warteschlange einfach weiterlaufen.
     */
    public boolean zurueckInBurg() { return zurueckInBurg; }

    static List<String> vorlagen() {
        return List.of(T_POKAL, T_REITER, T_HEUTE, T_SEND);
    }

    public void starten() {
        aktiv = true;
        phase = Phase.HIN;
        begonnen = letzterSchritt = System.currentTimeMillis();
        zuletztGeklickt = sendGeklicktUm = 0;
        aktivMs = rausAb = 0;
        klickY = klicks = blind = wischer = ruhig = gesendet = sendKlicks = ohneWirkung = 0;
        pokalKlicks = reiterKlicks = leseFehler = nullFolge = 0;
        rest = restVorKlick = startZahl = erwartet = -1;
        nullGelesen = hoch = bewegtRunter = knopfGesehen = tagFertig = zurueckInBurg = false;
        grauBlicke = 0;
        listeFreigeben();
        ergebnis = "";
        schluss = null;
        abholenAb = 0; holenKlicks = claimKlicks = noSpaceKlicks = 0; abgeholt = abholenFoto = claimFoto = ohneZahl = false;
        Log.info(WER, de.hustlebot.core.Sprache.t("Sending resources to friends: cup -> Friends -> Send, until the top shows 0.",
                "Freunden Ressourcen senden: Pokal -> Friends -> Send, bis oben 0 steht."));
    }

    private void fertig(String grund) {
        if (schluss == null) {
            schluss = grund;
            Log.info(WER, grund);
        }
        // Nach dem Senden erst die Ressourcen der Freunde abholen (Autor, 08.10.) -- aber nicht
        // nach einem Abbruch, und nur, wenn das noch nicht versucht wurde.
        if (phase == Phase.LISTE && !grund.startsWith("abgebrochen") && abholenAb == 0) {
            abholenAb = aktivMs;
            phase = Phase.ABHOLEN;
            return;
        }
        if (phase != Phase.RAUS) rausAb = aktivMs;
        phase = Phase.RAUS;
    }

    /**
     * "Get the resources" -> "Claim all" -> zurueck im Reiter Friends (Autor, 08.10.: "dann soll er
     * noch auf Get the Resources druecken ... dann auf Claim All ... auf das X oben und dann ist er
     * wieder in der Burg"). Ist der gruene Knopf nicht da, hat niemand etwas geschickt -- dann gleich
     * hinaus. Nie die Kiste am Balken, nie "Add friends".
     */
    /** Ein Foto des Spiels nach logs\freunde-&lt;anlass&gt;-HHmmss.png (zum Ausschneiden neuer Vorlagen). */
    private void foto(BotContext ctx, String anlass) {
        java.awt.image.BufferedImage bild = ctx.frame();
        if (bild == null) return;
        try {
            java.nio.file.Path datei = java.nio.file.Path.of("logs", "freunde-" + anlass + "-"
                    + de.hustlebot.core.Zeitzone.uhrzeit().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png");
            if (de.hustlebot.core.Beleg.schreiben(bild, "png", datei.toFile())) Log.info(WER, de.hustlebot.core.Sprache.t("Photo: ", "Foto: ") + datei);
        } catch (Exception e) {
            Log.debug(WER, "Foto nicht gespeichert: " + e.getMessage());
        }
    }

    private boolean abholen(Mat screen, BotContext ctx, long jetzt) {
        if (!abholenFoto) {
            // Ein Foto des Reiters nach dem Senden (08.10.): daraus werden die Vorlagen
            // "Get the resources" und "Claim all" geschnitten, solange sie fehlen.
            abholenFoto = true;
            foto(ctx, "abholen");
        }
        if (jetzt - zuletztGeklickt < 900) return warten(ctx, null);
        // "No space -- Not enough space in storehouses": das Lager ist fuer eine Sorte voll. Autor,
        // 08.10.: "Der Bot soll bei dieser Meldung einfach auf Claim druecken." Das Fenster liegt
        // VOR "Claim all" und dem X -- darum zuerst.
        if (holenKlicks > 0 && !claimFoto) {
            claimFoto = true;   // das Fenster "Claim the resources sent to you" -- fuer die Vorlage fr_claim_all
            foto(ctx, "claim");
        }
        if (ctx.visibleIfKnown(screen, T_CLAIM_ALL)) {
            if (claimKlicks >= 3) { fertig(schluss); return true; }   // haengt: hinaus, X schliesst das Fenster
            claimKlicks++;
            abgeholt = true;
            return klicken(screen, ctx, T_CLAIM_ALL, "Ressourcen der Freunde abholen (Claim all)");
        }
        if (ctx.visibleIfKnown(screen, T_HOLEN)) {
            if (holenKlicks >= 3) { fertig(schluss); return true; }
            holenKlicks++;
            return klicken(screen, ctx, T_HOLEN, "Ressourcen der Freunde ansehen (Get the resources)");
        }
        if (abgeholt) {
            Log.info(WER, de.hustlebot.core.Sprache.t("Collected the friends' resources (Claim all).", "Ressourcen der Freunde abgeholt (Claim all)."));
            abgeholt = false;   // nur einmal melden
            rausAb = aktivMs;
            phase = Phase.RAUS;
            return true;
        }
        if (aktivMs - abholenAb > ABHOLEN_MAX_MS || holenKlicks > 0) {
            if (holenKlicks == 0) Log.info(WER, de.hustlebot.core.Sprache.t("No \"Get the resources\" to be seen -- nobody sent anything.",
                    "Kein \"Get the resources\" zu sehen -- niemand hat etwas geschickt."));
            rausAb = aktivMs;
            phase = Phase.RAUS;
            return true;
        }
        return warten(ctx, "Get the resources");
    }

    public boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;
        long jetzt = System.currentTimeMillis();
        // Nur die Arbeitszeit zaehlt (Gegenlese 05.10.): Nach !pause ... !weiter liegt eine lange
        // Luecke zwischen zwei Schritten -- die kommt nicht dazu, sonst bricht der Gang sofort ab.
        long luecke = jetzt - letzterSchritt;
        if (luecke > 0 && luecke <= LUECKE_MS) aktivMs += luecke;
        letzterSchritt = jetzt;
        if ((klicks > MAX_KLICKS || aktivMs > MAX_DAUER_MS) && schluss == null) {
            fertig("abgebrochen: Klick- oder Zeitgrenze erreicht");
        }
        // Die harte Grenze erst, wenn der Weg hinaus seine eigene Minute hatte -- das rote X
        // wird also immer noch versucht, bevor der Bot aufgibt.
        if (phase == Phase.RAUS && (aktivMs - rausAb > RAUS_FRIST_MS || klicks > MAX_KLICKS + 20)) {
            return ende("abgebrochen: kam nicht zurueck in die Burg", false);
        }

        // "No space" (Lager fuer eine Sorte voll) kann in JEDER Phase vorliegen -- auch schon beim
        // Start, wenn es vom letzten Mal noch offen steht (Testlauf 08.10., 13:37: neun X-Klicks
        // ohne Wirkung). Autor: "dort soll er einfach auf Claim druecken."
        if (jetzt - zuletztGeklickt > 900 && noSpaceKlicks < 4
                && ctx.visibleIfKnown(screen, T_NO_SPACE) && ctx.visibleIfKnown(screen, T_CLAIM_OK)) {
            noSpaceKlicks++;
            abgeholt = true;
            return klicken(screen, ctx, T_CLAIM_OK, "\"No space\": trotzdem annehmen (Claim)");
        }

        boolean freundeOffen = ctx.visibleIfKnown(screen, T_HEUTE) || ctx.visibleIfKnown(screen, T_REITER_AKTIV)
                || ctx.visibleIfKnown(screen, T_KISTE);
        boolean ranglisteOffen = !freundeOffen && ctx.visibleIfKnown(screen, T_REITER);
        // FALLE (gemessen 05.10.): Der Map-Knopf "passt" auch HINTER der Rangliste (0,99).
        // In der Burg ist man also nur, wenn kein Ranglistenfenster offen ist -- und kein rotes
        // X oben rechts zu sehen ist (Gegenlese 05.10.: reine Burg 0,31-0,37, mit offenem
        // Fenster 0,81-0,89). Sonst verdeckt ein Fenster die Merkmale der Rangliste.
        boolean inBurg = !freundeOffen && !ranglisteOffen
                && (ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT))
                && xOben(screen, ctx).isEmpty();

        switch (phase) {
            case HIN -> {
                if (freundeOffen) { phase = Phase.LISTE; blind = 0; return true; }
                if (ranglisteOffen) {
                    blind = 0;
                    if (jetzt - zuletztGeklickt < 2000) return warten(ctx, null);
                    if (reiterKlicks >= MAX_REITER) {
                        fertig("abgebrochen: der Reiter \"Friends\" oeffnet sich nicht");
                        return true;
                    }
                    reiterKlicks++;
                    return klicken(screen, ctx, T_REITER, "Reiter \"Friends\" oeffnen (Freunden senden)");
                }
                if (inBurg) {
                    if (jetzt - zuletztGeklickt < 2500) return warten(ctx, null);
                    if (pokalKlicks >= MAX_POKAL) {
                        fertig("abgebrochen: der Pokal oben links oeffnet die Rangliste nicht");
                        return true;
                    }
                    Optional<Matcher.Hit> pokal = pokalFinden(screen, ctx);
                    if (pokal.isEmpty()) return warten(ctx, "Pokal-Knopf oben links");
                    pokalKlicks++;
                    ctx.klick(pokal.get(), "Pokal oben links: Rangliste oeffnen (Freunden senden)");
                    klicks++;
                    zuletztGeklickt = System.currentTimeMillis();
                    ctx.sleepUntilChanged(1500);
                    return true;
                }
                // Nach dem Titanen-Turm steht das Spiel meist auf der Weltkarte -- erst in die Burg.
                if (Weltkarte.erkannt(screen, ctx)) {
                    if (jetzt - zuletztGeklickt < 2000) return warten(ctx, null);
                    Weltkarte.heim(screen, ctx, WER);
                    klicks++;
                    zuletztGeklickt = System.currentTimeMillis();
                    return true;
                }
                // Ein anderes Fenster liegt noch offen: nur mit dem roten X schliessen. Erst nach
                // 3 s -- sonst traefe es die Rangliste, waehrend sie sich gerade oeffnet.
                if (jetzt - zuletztGeklickt > 3000
                        && xKlicken(screen, ctx, "Fenster schliessen (auf dem Weg zu den Freunden)")) {
                    return true;
                }
                return warten(ctx, "Burg");
            }
            case LISTE -> {
                return liste(screen, ctx, freundeOffen, ranglisteOffen, jetzt);
            }
            case ABHOLEN -> {
                return abholen(screen, ctx, jetzt);
            }
            case RAUS -> {
                if (freundeOffen || ranglisteOffen) {
                    if (jetzt - zuletztGeklickt < 1200) return warten(ctx, null);
                    if (xKlicken(screen, ctx, "Rangliste schliessen (rotes X)")) return true;
                    return warten(ctx, "rotes X der Rangliste");
                }
                if (inBurg) return ende(schluss == null ? "fertig" : schluss, true);
                if (Weltkarte.erkannt(screen, ctx)) {
                    if (jetzt - zuletztGeklickt < 2000) return warten(ctx, null);
                    Weltkarte.heim(screen, ctx, WER);
                    klicks++;
                    zuletztGeklickt = System.currentTimeMillis();
                    return true;
                }
                // Ein Fenster davor (z. B. Belohnung): nur schliessen, nie bestaetigen.
                if (jetzt - zuletztGeklickt > 1500
                        && xKlicken(screen, ctx, "Fenster schliessen (Weg zurueck in die Burg)")) {
                    return true;
                }
                return warten(ctx, "Burg");
            }
        }
        return warten(ctx, null);
    }

    /** Im Reiter "Friends": Zahl lesen, Send druecken, Liste weiterziehen. */
    private boolean liste(Mat screen, BotContext ctx, boolean freundeOffen, boolean ranglisteOffen, long jetzt) {
        if (!freundeOffen) {
            if (ranglisteOffen) { phase = Phase.HIN; return true; }   // wieder bei "Top players"
            // Nach "Send" ein fremdes Fenster? Nur mit dem roten X schliessen, nie bestaetigen.
            if (sendGeklicktUm > 0 && jetzt - sendGeklicktUm > WIRKUNG_MS
                    && xKlicken(screen, ctx, "Fenster nach dem Senden schliessen")) {
                return true;
            }
            return warten(ctx, "Freundesliste");
        }
        blind = 0;
        if (schluss != null) { fertig(schluss); return true; }
        int w = ctx.width(), h = ctx.height();
        // Tageslimit erreicht: oben steht orange "You have reached the resource limit for your
        // friends. Available in: 15h 20m" (Foto 08.10., 13:39) -- dann ist fuer heute alles gesendet.
        if (ctx.visibleIfKnown(screen, T_LIMIT)) {
            tagFertig = true;
            fertig("Oben steht \"You have reached the resource limit\" -- fuer heute ist alles gesendet ("
                    + gesendet + " gesendet).");
            return true;
        }
        Optional<Matcher.Hit> heute = ctx.matcher().has(T_HEUTE) ? ctx.matcher().find(screen, T_HEUTE) : Optional.empty();

        // 1. Hat der letzte Send-Klick gewirkt? Der Knopf wird zu "Sent", oben sinkt die Zahl.
        if (sendGeklicktUm > 0) {
            boolean sentDa = sentAnKlickstelle(screen, ctx);
            long z = heute.isPresent() ? zaehlerLesen(ctx, heute.get(), restVorKlick - 1) : -1;
            // Nur GENAU eins weniger zaehlt als gesunken (Gegenlese 05.10.): eine Fehllesung
            // wie 10 -> 0 ("1" faellt in der Maske weg) darf den Gang nicht beenden.
            boolean gesunken = restVorKlick >= 1 && z == restVorKlick - 1;
            if (sentDa || gesunken) {
                gesendet++;
                ohneWirkung = 0;
                sendGeklicktUm = 0;
                Log.info(WER, de.hustlebot.core.Sprache.t("Sent (", "Gesendet (") + gesendet + ")"
                        + (gesunken ? de.hustlebot.core.Sprache.t(", counter ", ", Zaehler ") + restVorKlick + " -> " + z : "") + ".");
                erwartet = restVorKlick - 1;
                rest = -1;
                leseFehler = 0;
                if (gesunken && z > 0) {
                    rest = z;
                } else if (gesunken) {
                    // Eine "0" nach einer 1: erst im naechsten Bild noch einmal lesen -- bis dahin kein Send.
                    nullGelesen = true;
                    nullFolge = 0;
                    return warten(ctx, null);
                }
            } else if (jetzt - sendGeklicktUm < WIRKUNG_MS) {
                return warten(ctx, null);
            } else {
                sendGeklicktUm = 0;
                if (++ohneWirkung >= 2) {
                    fertig("Send ohne Wirkung -- weder \"Sent\" noch eine kleinere Zahl oben. "
                            + "Es wird nichts mehr gedrueckt (" + gesendet + " gesendet).");
                    return true;
                }
                erwartet = restVorKlick;   // nichts passiert: oben muss noch dieselbe Zahl stehen
                rest = -1;
                leseFehler = 0;
            }
        }

        // 2. Die Zahl oben lesen, solange sie nicht sicher feststeht (oder eine "0" sich bestaetigen
        //    muss). Ohne sichere Zahl ueber 0 wird nie gesendet (Gegenlese 05.10.).
        if (rest < 0 && !ohneZahl && heute.isEmpty() && ctx.matcher().has(T_HOLEN)
                && ctx.visibleIfKnown(screen, T_HOLEN)) {
            ohneZahl = true;
            Log.info(WER, de.hustlebot.core.Sprache.t("The top shows \"Get the resources\" instead of the number -- sending without a number "
                    + "as long as there is a blue Send button (at most " + MAX_JE_TAG + ").",
                    "Oben steht \"Get the resources\" statt der Zahl -- es wird ohne Zahl gesendet, "
                    + "solange ein blauer Send-Knopf da ist (hoechstens " + MAX_JE_TAG + ")."));
        }
        if (rest < 0 && !ohneZahl) {
            long z = heute.isPresent() ? zaehlerLesen(ctx, heute.get(), erwartet) : -1;
            boolean blauDa = z == 0 && !blaueKnoepfe(screen, ctx, heute.orElse(null), w).isEmpty();
            boolean glaubhaft = zahlGlaubhaft(z, erwartet, blauDa);
            if (z >= 0 && !glaubhaft) {
                Log.debug(WER, "Zahl oben " + z + " nicht glaubhaft (erwartet "
                        + (erwartet >= 0 ? erwartet : "1 bis " + MAX_JE_TAG) + (blauDa ? ", blauer Send-Knopf da" : "")
                        + ") -- zaehlt als unlesbar.");
            }
            if (nullGelesen) {
                if (glaubhaft) {
                    nullGelesen = false;
                    rest = z;   // 0 bestaetigt -- oder doch eine sichere Zahl ueber 0
                } else if (++nullFolge >= NULL_NACHLESEN) {
                    fertig("Oben stand einmal 0, das liess sich aber nicht bestaetigen -- es wird nichts mehr "
                            + "gedrueckt (" + gesendet + " gesendet). In 20 Minuten wird noch einmal nachgesehen.");
                    return true;
                } else {
                    return warten(ctx, null);
                }
            } else if (glaubhaft && z == 0) {
                // Eine "0" erst im naechsten Bild noch einmal lesen -- nicht zweimal dasselbe Bild.
                nullGelesen = true;
                nullFolge = 0;
                return warten(ctx, null);
            } else if (glaubhaft) {
                rest = z;
            } else {
                if (++leseFehler >= MAX_LESEFEHLER) {
                    fertig(startZahl < 0
                            ? "Zahl oben nicht lesbar, nichts gesendet. In 20 Minuten wird noch einmal nachgesehen."
                            : "Zahl oben nicht mehr sicher lesbar (erwartet " + erwartet + ") -- Schluss, "
                              + gesendet + " gesendet. In 20 Minuten wird noch einmal nachgesehen.");
                    return true;
                }
                return warten(ctx, null);
            }
            leseFehler = 0;
            if (startZahl < 0) {
                startZahl = rest;
                Log.info(WER, de.hustlebot.core.Sprache.t("The top says: " + rest + " left to send today.", "Oben steht: heute noch " + rest + " zu senden."));
            }
        }

        // 3. Fertig?
        if (rest == 0) {
            tagFertig = true;
            fertig(gesendet > 0 ? gesendet + " Ressourcen gesendet, oben steht jetzt 0 -- fuer heute fertig."
                                : "Oben steht schon 0 -- heute ist alles gesendet.");
            return true;
        }
        if (gesendet >= MAX_JE_TAG) {
            tagFertig = true;
            fertig(gesendet + " Ressourcen gesendet -- mehr geht am Tag nicht.");
            return true;
        }
        // So viele, wie oben am Anfang standen, sind raus -- mehr wird nie gedrueckt (Gegenlese 05.10.).
        if (startZahl >= 0 && gesendet >= startZahl) {
            fertig(gesendet + " Ressourcen gesendet -- so viele standen oben am Anfang. Die 0 oben ist nicht "
                    + "bestaetigt, darum wird der Tag nicht vermerkt.");
            return true;
        }
        int grenze = sendGrenze();
        if (sendKlicks >= grenze) {
            fertig("Grenze von " + grenze + " Send-Klicks erreicht (" + gesendet + " gesendet, oben "
                    + restText() + ") -- sicherheitshalber Schluss.");
            return true;
        }
        if ((rest < 0 && !ohneZahl) || nullGelesen) return warten(ctx, null);   // ohne sichere Zahl ueber 0 nie senden

        // 4. Den obersten blauen Send-Knopf druecken.
        for (Matcher.Hit k : blaueKnoepfe(screen, ctx, heute.orElse(null), w)) {
            knopfGesehen = true;
            grauBlicke = 0;
            // Neue Send-Knoepfe nach einem Wischer: die Liste hat sich bewegt (der Vergleich wurde durch die
            // Geschenke zurueckgesetzt und sah das sonst nicht -- 11.10.).
            if (wischer > 0 && !hoch) bewegtRunter = true;
            restVorKlick = rest;
            klickY = k.centerY();
            sendKlicks++;
            klicks++;
            ctx.klick(k, "Send: Freund " + (gesendet + 1) + " beschenken"
                    + (rest > 0 ? " (oben noch " + rest + ")" : ""));
            sendGeklicktUm = System.currentTimeMillis();
            zuletztGeklickt = sendGeklicktUm;
            ruhig = 0;
            listeFreigeben();   // die Liste hat sich durch "Sent" ohnehin veraendert
            // Nicht auf eine sichtbare Bildaenderung warten (08.10.: lief jedes Mal in die volle
            // Sekunde, der Wechsel zu "Sent" ist zu klein). Der naechste Schritt prueft "Sent" selbst.
            ctx.sleep(120);
            return true;
        }
        // Kein blauer Knopf, aber graue "Send"-Knoepfe: das Tageslimit ist erreicht (Autor, 08.10.:
        // "Der Send-Knopf wird dann auch so grau, wenn er nicht mehr schicken kann"). Dann noch die
        // Ressourcen der Freunde abholen und hinaus -- nicht weiter durch die Liste ziehen.
        if (ctx.matcher().has(T_SEND)) {
            List<Matcher.Hit> graue = sendeKandidaten(ctx.matcher().findAll(screen, T_SEND, GRAU_SCHWELLE, 12),
                    obenGrenze(screen, ctx, heute.orElse(null)), untenGrenze(screen, ctx), w);
            BufferedImage bild = graue.isEmpty() ? null : ctx.frame();
            boolean nurGrau = bild != null;
            for (Matcher.Hit k : graue) { if (knopfIstBlau(bild, k)) { nurGrau = false; break; } }
            // 11.10. 12:57: Nach dem ersten Senden legte die Event-Belohnung ("x16") kurz einen dunklen Schleier
            // ueber die Liste -- alle Knoepfe wirkten grau, der Bot hoerte nach 1 von 10 auf (Foto: noch 5 blaue).
            // Darum erst beim zweiten Blick hintereinander (nach einer Pause) glauben.
            if (nurGrau && ++grauBlicke < 2) {
                Log.debug(WER, "Send-Knoepfe wirken grau -- in einer Sekunde noch einmal hinsehen.");
                ctx.sleep(1000);
                return true;
            }
            if (nurGrau) {
                tagFertig = true;
                fertig("Die Send-Knoepfe sind grau -- fuer heute ist das Limit erreicht (" + gesendet + " gesendet).");
                return true;
            }
        }
        if (!knopfGesehen && ctx.matcher().has(T_SENT)
                && ctx.matcher().find(screen, T_SENT).isPresent()) {
            knopfGesehen = true;
        }

        // 5. Kein Send-Knopf zu sehen, es koennte aber noch etwas gehen: weiter unten suchen.
        return wischen(screen, ctx, w, h);
    }

    /**
     * Die Liste ein Stueck weiterziehen (Autor, Bild 4: Pfeil nach unten = spaetere Zeilen,
     * der Finger geht also von unten nach oben). Ein Zug bewegt knapp vier Zeilen, sichtbar
     * sind fuenfeinhalb -- so wird keine uebersprungen. Bewegt sich die Liste zweimal nicht
     * mehr, ist das Ende erreicht: dann einmal zurueck nach oben, danach Schluss.
     */
    private boolean wischen(Mat screen, BotContext ctx, int w, int h) {
        // 11.10. 13:32: Gewischt wurde 56 ms nach dem 17. Geschenk -- die Event-Einblendung ("x2") kam erst danach
        // und schluckte die beiden letzten Zuege; der Bot hielt die Liste fuer unbeweglich und hoerte bei 17 von 19
        // auf (Autor hat ueber 60 Freunde). Erst wischen, wenn das letzte Geschenk 1,5 s her ist -- bis dahin ist die
        // Einblendung da und vom Modus weggeklickt.
        if (sendGeklicktUm > 0 && System.currentTimeMillis() - sendGeklicktUm < 1500) {
            ctx.sleep(250);
            return true;
        }
        Rect bereich = listenBereich(w, h, screen);
        if (bereich == null) return warten(ctx, "Freundesliste");
        if (listeVorher != null) {
            Mat jetztListe = screen.submat(bereich);
            boolean gleich = bildGleich(listeVorher, jetztListe);
            jetztListe.release();
            listeFreigeben();
            if (gleich) {
                ruhig++;
            } else {
                ruhig = 0;
                if (!hoch) bewegtRunter = true;
            }
        }
        if (ruhig >= 3) {   // 11.10.: drei ruhige Zuege statt zwei, bevor "Ende"/"bewegt sich nicht" gilt
            ruhig = 0;
            // 11.10.: Steht oben noch eine Zahl ueber 0, ist das "Ende" meist keins (Autor: ueber 60 Freunde, ~15 Zuege
            // bis unten). Dann weiter ziehen, bis zu 15 Zuege, statt den Tag als erledigt abzuhaken.
            if (bewegtRunter && knopfGesehen && rest > 0 && wischer < 15) {
                Log.info(WER, de.hustlebot.core.Sprache.t("The list looks finished, but the top still shows " + rest + " -- swiping on (swipe " + wischer + ").",
                        "Liste wirkt zu Ende, oben steht aber noch " + rest + " -- weiter ziehen (Zug " + wischer + ")."));
            } else if (bewegtRunter && knopfGesehen) {
                // Testlauf 08.10.: nach dem Listenende ging es "zur Sicherheit" zurueck nach oben und
                // dann 30 Zuege lang ohne Ende. Ein Zug bewegt knapp 4 von 5,5 sichtbaren Zeilen --
                // uebersprungen wird nichts. Also: Listenende ohne blauen Knopf = fuer heute fertig.
                foto(ctx, "listenende");
                tagFertig = true;
                fertig("Ende der Liste erreicht, kein blauer Send-Knopf mehr -- fuer heute fertig ("
                        + gesendet + " gesendet).");
                return true;
            } else if (!knopfGesehen) {
                fertig("In der ganzen Liste weder \"Send\" noch \"Sent\" erkannt -- es wird nichts gedrueckt "
                        + "(Ausschnitte fr_send/fr_sent pruefen?).");
                return true;
            } else if (!bewegtRunter) {
                // Gegenlese 05.10.: Die Liste hat sich nie bewegt, oben steht aber noch eine Zahl
                // ueber 0. Dann ist nicht bewiesen, dass alle Freunde etwas haben -- der Tag wird
                // NICHT vermerkt, in 20 Minuten wird noch einmal nachgesehen.
                Log.warn(WER, de.hustlebot.core.Sprache.t("The friends list cannot be swiped (top: " + restText() + ").",
                        "Die Freundesliste laesst sich nicht ziehen (oben " + restText() + ")."));
                fertig("Liste laesst sich nicht ziehen -- " + gesendet + " gesendet, oben " + restText()
                        + ". Der Tag wird nicht vermerkt.");
                return true;
            } else {
                tagFertig = true;
                fertig("Keine Send-Knoepfe mehr in der ganzen Liste -- alle Freunde haben heute etwas ("
                        + gesendet + " gesendet, oben " + restText() + ").");
                return true;
            }
        }
        if (wischer >= MAX_WISCHER) {
            fertig("Nach " + MAX_WISCHER + " Wischern kein Ende der Liste gefunden -- sicherheitshalber Schluss ("
                    + gesendet + " gesendet, oben " + restText() + ").");
            return true;
        }
        wischer++;
        Mat teil = screen.submat(bereich);
        listeVorher = teil.clone();
        teil.release();
        // x bei 52 %: zwischen Name/i-Knopf und Online-Punkt ist die Zeile leer -- nie in der Send-Spalte.
        // y zwischen 44 % und 72 %: mitten in der Liste, weit weg von "Add friends" und der Kiste.
        int x = (int) Math.round(w * 0.52);
        int oben = (int) Math.round(h * 0.44), unten = (int) Math.round(h * 0.72);
        if (hoch) {
            Log.action("Wischen", Sprache.t("Drag the friends list back up (", "Freundesliste zurueck nach oben ziehen (") + wischer + ").");
            ctx.input().drag(x, oben, x, unten);
        } else {
            Log.action("Wischen", Sprache.t("Drag the friends list down (", "Freundesliste nach unten ziehen (") + wischer + ").");
            ctx.input().drag(x, unten, x, oben);
        }
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(800);
        return true;
    }

    /**
     * Hoechstens so viele Send-Klicks: Startzahl + 2 (ein Klick ohne Wirkung darf
     * mal sein, zwei hintereinander beenden den Gang ohnehin). Ohne Startzahl 0 --
     * dann wird gar nicht gesendet.
     */
    private int sendGrenze() {
        if (ohneZahl) return MAX_JE_TAG + 2;
        if (startZahl < 0) return 0;
        return (int) Math.min(startZahl + 2, MAX_JE_TAG + 2);
    }

    private String restText() {
        return rest >= 0 ? "noch " + rest : "Zahl nicht lesbar";
    }

    /**
     * Ist eine gelesene Zahl glaubhaft (Gegenlese 05.10.)? Am Anfang (erwartet -1)
     * 1 bis 20; danach nur genau die erwartete Zahl. Eine 0 am Anfang nur, wenn in
     * der Send-Spalte kein blauer Knopf zu sehen ist -- nach einer 1 immer.
     */
    static boolean zahlGlaubhaft(long z, long erwartet, boolean blauerKnopfDa) {
        if (z < 0 || z > MAX_JE_TAG) return false;
        if (erwartet >= 0) return z == erwartet;
        return z > 0 || !blauerKnopfDa;
    }

    private boolean ende(String grund, boolean inBurg) {
        aktiv = false;
        ergebnis = grund;
        zurueckInBurg = inBurg;
        listeFreigeben();
        Log.info(WER, de.hustlebot.core.Sprache.t("Friends: " + gesendet + " resources sent", "Freunde: " + gesendet + " Ressourcen gesendet")
                + (rest >= 0 ? de.hustlebot.core.Sprache.t(", number now ", ", Zahl jetzt ") + rest : "") + " -- " + grund + " ("
                + (System.currentTimeMillis() - begonnen) / 1000 + " s).");
        return false;
    }

    private void listeFreigeben() {
        if (listeVorher != null) {
            listeVorher.release();
            listeVorher = null;
        }
    }

    // ------------------------------------------------------------------ Suchen

    /** Der Pokal oben links -- nur dort, nie ein anderer Pokal im Bild. */
    private static Optional<Matcher.Hit> pokalFinden(Mat screen, BotContext ctx) {
        for (String name : List.of(T_POKAL, T_POKAL_ALT)) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> p = ctx.matcher().find(screen, name);
            if (p.isPresent() && p.get().centerX() < ctx.width() * 0.20 && p.get().centerY() < ctx.height() * 0.35) {
                return p;
            }
        }
        return Optional.empty();
    }

    /** Die blauen Send-Knoepfe in der Send-Spalte, der oberste zuerst (nie "Sent", nie ausserhalb). */
    private static List<Matcher.Hit> blaueKnoepfe(Mat screen, BotContext ctx, Matcher.Hit heute, int w) {
        if (!ctx.matcher().has(T_SEND)) return List.of();
        List<Matcher.Hit> kandidaten = sendeKandidaten(ctx.matcher().findAll(screen, T_SEND, SEND_SCHWELLE, 12),
                obenGrenze(screen, ctx, heute), untenGrenze(screen, ctx), w);
        BufferedImage bild = ctx.frame();
        if (bild == null) return kandidaten;
        List<Matcher.Hit> blau = new ArrayList<>();
        for (Matcher.Hit k : kandidaten) {
            if (knopfIstBlau(bild, k)) blau.add(k);
        }
        return blau;
    }

    /** Steht dort, wo eben "Send" gedrueckt wurde, jetzt "Sent"? */
    private boolean sentAnKlickstelle(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(T_SENT)) return false;
        int w = ctx.width(), h = ctx.height();
        for (Matcher.Hit s : ctx.matcher().findAll(screen, T_SENT, Matcher.DEFAULT_THRESHOLD, 12)) {
            if (Math.abs(s.centerY() - klickY) < h * 0.03 && s.centerX() > w * 0.65 && s.centerX() < w * 0.92) {
                return true;
            }
        }
        return false;
    }

    /** Oberhalb davon wird nie gedrueckt: unter der Kiste und unter dem gruenen Schriftzug. */
    private static int obenGrenze(Mat screen, BotContext ctx, Matcher.Hit heute) {
        int h = ctx.height();
        int grenze = heute != null ? heute.y() + heute.height() : -1;
        if (ctx.matcher().has(T_KISTE)) {
            Optional<Matcher.Hit> kiste = ctx.matcher().find(screen, T_KISTE);
            if (kiste.isPresent()) grenze = Math.max(grenze, kiste.get().y() + kiste.get().height());
        }
        return grenze < 0 ? (int) (h * 0.30) : grenze + (int) (h * 0.04);
    }

    /** Unterhalb davon wird nie gedrueckt: dort liegen "Add friends" und der Schalter fuer Anfragen. */
    private static int untenGrenze(Mat screen, BotContext ctx) {
        int h = ctx.height();
        if (ctx.matcher().has(T_ADD)) {
            Optional<Matcher.Hit> add = ctx.matcher().find(screen, T_ADD);
            if (add.isPresent()) return add.get().y() - (int) Math.round(38 * 0.6 * h / 770.0);
        }
        return (int) (h * 0.79);
    }

    /** Der weisse Listenbereich (ohne Kopf und ohne die Knoepfe darunter) -- fuer den Bildvergleich. */
    private static Rect listenBereich(int w, int h, Mat screen) {
        int x = (int) (w * 0.18), y = (int) (h * 0.36);
        int b = (int) (w * 0.84) - x, hoehe = (int) (h * 0.76) - y;
        if (b <= 10 || hoehe <= 10 || x + b > screen.cols() || y + hoehe > screen.rows()) return null;
        return new Rect(x, y, b, hoehe);
    }

    /** Hat sich die Liste zwischen zwei Bildern bewegt? (Mittlerer Unterschied, wie EventMode.bildGleich.) */
    private static boolean bildGleich(Mat a, Mat b) {
        if (a.empty() || b.empty() || a.rows() != b.rows() || a.cols() != b.cols()) return false;
        Mat d = new Mat();
        Core.absdiff(a, b, d);
        Scalar m = Core.mean(d);
        d.release();
        return (m.val[0] + m.val[1] + m.val[2]) / 3 < 2.0;
    }

    // ------------------------------------------------------------------ Zahl oben lesen

    /**
     * Die Zahl unter "Resources that can be sent today:"; -1 = nicht lesbar.
     *
     * Die Schrift ist gruen auf hellgelb, ohne Weiss -- die Texterkennung des
     * Bots sucht aber weisse Schrift. Darum zuerst eine Maske: gruene Pixel
     * werden weiss, alles andere (Geschenk, Hintergrund) dunkel. Gelesen wird
     * hinter drei Woertern, die Mehrheit zaehlt (gemessen 05.10.: "18" kam als
     * "18" und als "IB"). Passt die erste Lesung zur Erwartung, gilt sie sofort.
     */
    private static long zaehlerLesen(BotContext ctx, Matcher.Hit heute, long erwartet) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return -1;
        double f = ctx.width() / 1159.0;
        BufferedImage maske = gruenMaske(bild, heute.x() + (int) Math.round(120 * f), heute.y() + (int) Math.round(44 * f),
                (int) Math.round(110 * f), (int) Math.round(28 * f));
        if (maske == null) return -1;
        List<String> texte = new ArrayList<>();
        for (String wort : WOERTER) {
            String t = ArenaLeser.textHinterWort(maske, 0, 0, maske.getWidth(), maske.getHeight(), wort);
            if (texte.isEmpty() && erwartet > 0) {
                OptionalLong z = zahlAusText(t);
                if (z.isPresent() && z.getAsLong() == erwartet) return erwartet;
            }
            texte.add(t);
        }
        OptionalLong z = zahlAusStimmen(texte, 2);
        if (z.isEmpty()) Log.debug(WER, "Zahl oben nicht sicher lesbar: " + texte);
        return z.orElse(-1);
    }

    /** Gruene Schrift (die Zahl oben), nicht der hellgelbe Grund und nicht das rot-goldene Geschenk. */
    static boolean istGruen(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return g >= 110 && g - r >= 45 && g - b >= 45;
    }

    /** Ausschnitt mit gruenen Pixeln weiss, alles andere dunkel; null = nichts Gruenes da. */
    static BufferedImage gruenMaske(BufferedImage bild, int x, int y, int w, int h) {
        if (bild == null) return null;
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + w), y2 = Math.min(bild.getHeight(), y + h);
        if (x2 - x1 < 4 || y2 - y1 < 4) return null;
        BufferedImage aus = new BufferedImage(x2 - x1, y2 - y1, BufferedImage.TYPE_INT_RGB);
        int gruen = 0;
        for (int yy = y1; yy < y2; yy++) {
            for (int xx = x1; xx < x2; xx++) {
                boolean ja = istGruen(bild.getRGB(xx, yy));
                if (ja) gruen++;
                aus.setRGB(xx - x1, yy - y1, ja ? 0xFFFFFF : 0x202020);
            }
        }
        return gruen < 10 ? null : aus;
    }

    /**
     * Eine gelesene Zahl (0 bis 50). Die Texterkennung liest Ziffern gern als
     * Buchstaben: O/o/D -> 0, I/i/l/L/|/! -> 1, B -> 8, S -> 5, Z -> 2.
     * Punkte und Leerzeichen fallen weg ("i.B" -> 18).
     */
    static OptionalLong zahlAusText(String text) {
        if (text == null) return OptionalLong.empty();
        String t = text.replaceAll("[\\s.,:;'`\"]", "");
        if (t.isEmpty() || t.length() > 2) return OptionalLong.empty();
        long z = 0;
        for (char c : t.toCharArray()) {
            char d = switch (c) {
                case 'O', 'o', 'D' -> '0';
                case 'I', 'i', 'l', 'L', '|', '!' -> '1';
                case 'B' -> '8';
                case 'S' -> '5';
                case 'Z' -> '2';
                default -> c;
            };
            if (d < '0' || d > '9') return OptionalLong.empty();
            z = z * 10 + (d - '0');
        }
        return z <= 50 ? OptionalLong.of(z) : OptionalLong.empty();
    }

    /** Die Zahl, die mindestens {@code min} Lesungen gleich ergeben haben. */
    static OptionalLong zahlAusStimmen(List<String> texte, int min) {
        Map<Long, Integer> stimmen = new HashMap<>();
        for (String t : texte) {
            OptionalLong z = zahlAusText(t);
            if (z.isPresent()) stimmen.merge(z.getAsLong(), 1, Integer::sum);
        }
        return stimmen.entrySet().stream()
                .filter(e -> e.getValue() >= min)
                .max(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> OptionalLong.of(e.getKey())).orElse(OptionalLong.empty());
    }

    // ------------------------------------------------------------------ Send-Knoepfe

    /**
     * Send-Knoepfe, die gedrueckt werden duerfen: nur in der Send-Spalte rechts
     * (65-92 % der Breite), unter der Kiste/dem Schriftzug und ueber "Add
     * friends". Der oberste zuerst.
     */
    static List<Matcher.Hit> sendeKandidaten(List<Matcher.Hit> treffer, int obenGrenze, int untenGrenze, int breite) {
        List<Matcher.Hit> aus = new ArrayList<>();
        for (Matcher.Hit t : treffer) {
            if (t.centerY() <= obenGrenze || t.centerY() >= untenGrenze) continue;
            if (t.centerX() < breite * 0.65 || t.centerX() > breite * 0.92) continue;
            aus.add(t);
        }
        aus.sort(Comparator.comparingInt(Matcher.Hit::centerY));
        return aus;
    }

    /**
     * Ist der Knopf wirklich blau ("Send", etwa 38/202/224) und nicht hellgrau-blau
     * ("Sent", etwa 148/229/234)? Gemessen links im Knopf, neben dem Geschenk.
     */
    static boolean knopfIstBlau(BufferedImage bild, Matcher.Hit k) {
        int x0 = k.x() + 5, y0 = k.centerY() - 2;
        long r = 0, b = 0;
        int n = 0;
        for (int y = y0; y < y0 + 4; y++) {
            for (int x = x0; x < x0 + 4; x++) {
                if (x < 0 || y < 0 || x >= bild.getWidth() || y >= bild.getHeight()) continue;
                int p = bild.getRGB(x, y);
                r += (p >> 16) & 255;
                b += p & 255;
                n++;
            }
        }
        if (n == 0) return false;
        return r / n < 100 && b / n > 170;
    }

    // ------------------------------------------------------------------ Klicken

    private boolean klicken(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.clickIfVisibleIfKnown(screen, vorlage, grund)) return warten(ctx, vorlage);
        blind = 0;
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1500);
        return true;
    }

    /** Das rote X -- nur oben rechts (dort sitzt es bei der Rangliste), jede bekannte X-Vorlage. */
    private boolean xKlicken(Mat screen, BotContext ctx, String grund) {
        Optional<Matcher.Hit> x = xOben(screen, ctx);
        if (x.isEmpty()) return false;
        ctx.klick(x.get(), grund);
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    /** Ein rotes X oben rechts (ab 0,70) -- dann ist noch ein Fenster offen. */
    private static Optional<Matcher.Hit> xOben(Mat screen, BotContext ctx) {
        int w = ctx.width(), h = ctx.height();
        for (String name : X_VORLAGEN) {
            if (!ctx.matcher().has(name)) continue;
            Optional<Matcher.Hit> x = ctx.matcher().find(screen, name, X_SCHWELLE);
            if (x.isPresent() && x.get().centerX() >= w * 0.70 && x.get().centerY() <= h * 0.30) return x;
        }
        return Optional.empty();
    }

    private boolean warten(BotContext ctx, String worauf) {
        if (worauf != null && ++blind > MAX_BLIND) {
            if (phase != Phase.RAUS) {
                fertig("abgebrochen: " + worauf + " nicht erkannt");
                blind = 0;
                return true;
            }
            return ende("abgebrochen: " + worauf + " nicht erkannt", false);
        }
        ctx.sleep(250);
        return true;
    }
}
