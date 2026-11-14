package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.OptionalLong;
import de.hustlebot.core.Sprache;

/**
 * Food chests im Clan-Laden ("Royal Store") kaufen -- mit CLAN-MUENZEN, nie
 * mit Diamanten (Autor, 27.09., mit Bildern).
 *
 * DER WEG:
 *   Burg -> unten der Clan-Knopf (neben "Map") -> Clan-Karte: Clan-Muenzen
 *   oben links ablesen -> "Store" -> im Royal Store herunterscrollen bis zur
 *   "Food chest" -> Preis darunter ablesen -> so viele kaufen wie moeglich,
 *   hoechstens 7 am Tag -> rotes X -> "Home" -> Burg.
 *
 * Gekauft wird NUR, wenn im Preisfeld unter der Kiste das Clan-Muenzen-Symbol
 * steht (Vorlage cl_preis_muenze). Steht dort etwas anderes (Diamant), passt
 * die Vorlage nicht, und es wird nichts gedrueckt. Nach jedem Kauf muss der
 * Muenzstand wirklich sinken, sonst hoert der Bot auf.
 *
 * Benutzt vom Nahrungskisten-Gang (Tasche leer) und vom Modus "clanladen"
 * (02:00 und 12:00 nach dem Turm, core/Ladenplan). Ein Schritt = eine Handlung;
 * schritt() liefert false, wenn der Gang vorbei ist und der Bot in der Burg steht.
 */
public final class ClanLaden {

    // --- Vorlagen (Gruppe CLAN-LADEN in Capture-Images.exe) -------------------------
    static final String T_KNOPF = "cl_clan_knopf";          // Burg: Clan-Knopf unten neben "Map"
    static final String T_MUENZEN = "cl_muenzen";           // Clan-Muenzen-Symbol oben links (Karte und Laden)
    static final String T_STORE = "cl_store";               // Clan-Karte: das runde "Store"-Symbol
    static final String T_STORE_KNOPF = "cl_store_knopf";   // danach im Menue: runder Knopf "Store" (unten)
    static final String T_TITEL = "cl_store_titel";         // "Royal Store"
    static final String T_KISTE = "cl_food_chest";          // die Worte "Food chest" auf der Karte
    static final String T_AUSVERKAUFT = "cl_ausverkauft";   // "Out of stock"
    static final String T_PREIS = "cl_preis_muenze";        // Clan-Muenze im Preisfeld unter einer Kiste
    static final String T_BESTAETIGEN = "cl_kauf_bestaetigen"; // Kauf bestaetigen (falls das Spiel nachfragt)
    static final String T_HOME = "cl_home";                 // Clan-Karte: "Home" unten links
    private static final String T_X = "close_x";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";

    /** So oft laesst sich die Food chest am Tag kaufen (Autor). */
    static final int MAX_JE_TAG = 7;
    private static final long MAX_DAUER_MS = 4 * 60_000L;
    private static final int MAX_KLICKS = 60;
    private static final int MAX_BLIND = 25;
    private static final int MAX_WISCHER = 6;
    /**
     * So oft wird im Royal Store hoechstens nach unten gescrollt. Bis 28.09.
     * nur 4 -- Autor: die Food chest steht "10-12 mal" weiter unten. Mehr
     * kostet nichts: der Bot hoert auf, sobald er die Kiste sieht.
     */
    // Live-Test 16.10.: langsame, kurze Zuege (ohne Nachrollen) -- dafuer mehr davon.
    private static final int MAX_SCROLLS = 40;
    private static final double X_SCHWELLE = 0.70;

    private enum Phase { HIN, KARTE, LADEN, RAUS }

    private boolean aktiv;
    private Phase phase = Phase.HIN;
    private long begonnen, zuletztGeklickt, kaufGeklicktUm;
    private int klicks, blind, wischer, scrolls, gekauft, kaufOhneWirkung;
    private long muenzen = -1, muenzenVorKauf = -1, preis = -1;
    private String ergebnis = "";
    /** Grund, warum nicht (mehr) gekauft wird -- dann nur noch hinaus. */
    private String schluss;

    public boolean aktiv() { return aktiv; }

    /** Wie viele Food chests in diesem Gang gekauft wurden. */
    public int gekauft() { return gekauft; }

    /** Warum der Gang endete (fuer das Protokoll). */
    public String ergebnis() { return ergebnis; }

    /** Ist der Gang zu Ende gegangen, ohne haengen zu bleiben? */
    public boolean sauberBeendet() { return !ergebnis.startsWith("abgebrochen"); }

    public static boolean einsatzbereit(BotContext ctx) {
        var m = ctx.matcher();
        for (String n : new String[]{T_KNOPF, T_MUENZEN, T_STORE, T_TITEL, T_KISTE, T_PREIS, T_HOME}) {
            if (!m.has(n)) return false;
        }
        return true;
    }

    static java.util.List<String> vorlagen() {
        return java.util.List.of(T_KNOPF, T_MUENZEN, T_STORE, T_TITEL, T_KISTE, T_PREIS, T_HOME);
    }

    public void starten() {
        aktiv = true;
        phase = Phase.HIN;
        begonnen = System.currentTimeMillis();
        zuletztGeklickt = kaufGeklicktUm = 0;
        klicks = blind = wischer = scrolls = gekauft = kaufOhneWirkung = 0;
        muenzen = muenzenVorKauf = preis = -1;
        ergebnis = "";
        schluss = null;
        Log.info("Clan-Laden", Sprache.t("Buying food chests in the clan store (with clan coins, at most " + MAX_JE_TAG + ").",
                "Food chests im Clan-Laden kaufen (mit Clan-Muenzen, hoechstens " + MAX_JE_TAG + ")."));
    }

    private void fertig(String grund) {
        if (schluss == null) {
            schluss = grund;
            Log.info("Clan-Laden", grund);
        }
        phase = Phase.RAUS;
    }

    public boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;
        long jetzt = System.currentTimeMillis();
        if ((klicks > MAX_KLICKS || jetzt - begonnen > MAX_DAUER_MS) && schluss == null) {
            fertig(Sprache.t("aborted: click or time limit reached", "abgebrochen: Klick- oder Zeitgrenze erreicht"));
        }
        if (jetzt - begonnen > MAX_DAUER_MS + 60_000) {
            return ende(Sprache.t("aborted: did not get back to the castle", "abgebrochen: kam nicht zurueck in die Burg"));
        }

        boolean inBurg = ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT);
        boolean imLaden = ctx.visibleIfKnown(screen, T_TITEL);
        boolean aufKarte = !imLaden && ctx.visibleIfKnown(screen, T_HOME);
        // DIE WELTKARTE HAT DENSELBEN "HOME"-KNOPF (Testlauf 28.09., 15:08): Nach
        // dem Titanen-Turm stand das Spiel auf der Weltkarte, der Bot hielt sie
        // fuer die Clan-Karte und suchte dort den Store. Die Clan-Karte erkennt
        // man zusaetzlich an den Clan-Muenzen oben links -- und daran, dass kein
        // Ort der Weltkarte zu sehen ist.
        boolean clanKarte = aufKarte && !Weltkarte.erkannt(screen, ctx)
                && (!ctx.matcher().has(T_MUENZEN) || ctx.visibleIfKnown(screen, T_MUENZEN));

        switch (phase) {
            case HIN -> {
                if (imLaden) { phase = Phase.LADEN; return true; }
                if (clanKarte) { phase = Phase.KARTE; return true; }
                if (inBurg) {
                    if (jetzt - zuletztGeklickt < 2500) return warten(ctx, null);
                    return klicken(screen, ctx, T_KNOPF, Sprache.t("Open the clan menu (clan store)", "Clan-Menue oeffnen (Clan-Laden)"));
                }
                // "Home" da, aber nicht die Clan-Karte: die Weltkarte -- erst in die Burg.
                if (aufKarte || Weltkarte.erkannt(screen, ctx)) {   // nach Leuchtturm/Kolosseum/Turm
                    Weltkarte.heim(screen, ctx, "Clan-Laden");
                    klicks++;
                    return true;
                }
                // Ein anderes Fenster liegt noch offen -- am 29.09. um 12:05 die Kathedrale
                // direkt nach ihrem Run. Das rote X oben rechts fuehrt zurueck in die Burg;
                // frueher gab der Clan-Laden nach 15 s auf, und der Bot stand 20 Minuten.
                if (jetzt - zuletztGeklickt > 1500
                        && (xKlicken(screen, ctx, Sprache.t("Close the window (on the way to the clan store)", "Fenster schliessen (auf dem Weg zum Clan-Laden)"))
                            || ctx.clickIfVisibleIfKnown(screen, "dc_close_x", Sprache.t("Close the cathedral window (clan store)", "Kathedralen-Fenster schliessen (Clan-Laden)")))) {
                    return true;
                }
                return warten(ctx, "Burg");
            }
            case KARTE -> {
                if (imLaden) { phase = Phase.LADEN; wischer = 0; return true; }
                // Das Menue "Store (Level 3)" kann Muenzen und Home verdecken -- sein
                // runder Knopf zuerst, vor der Pruefung auf die Clan-Karte.
                if (ctx.visibleIfKnown(screen, T_STORE_KNOPF)) {
                    return klicken(screen, ctx, T_STORE_KNOPF, Sprache.t("Open the store (round button in the menu)", "Store oeffnen (runder Knopf im Menue)"));
                }
                if (!clanKarte) return warten(ctx, "Clan-Karte");
                blind = 0;
                if (muenzen < 0) {
                    muenzen = muenzenLesen(screen, ctx);
                    if (muenzen >= 0) Log.info("Clan-Laden", Sprache.t("Clan coins: " + zahl(muenzen) + ".",
                            "Clan-Muenzen: " + zahl(muenzen) + "."));
                }
                if (schluss != null) { phase = Phase.RAUS; return true; }
                // Nach dem Klick aufs Store-Symbol kommt ein Menue "Store (Level 3)" mit drei
                // runden Knoepfen -- dort noch einmal auf "Store" (Autor, 28.09., Bild).
                if (ctx.visibleIfKnown(screen, T_STORE_KNOPF)) {
                    return klicken(screen, ctx, T_STORE_KNOPF, Sprache.t("Open the store (round button in the menu)", "Store oeffnen (runder Knopf im Menue)"));
                }
                if (ctx.visibleIfKnown(screen, T_STORE)) {
                    return klicken(screen, ctx, T_STORE, Sprache.t("Open the store on the clan map", "Store auf der Clan-Karte oeffnen"));
                }
                if (wischer >= MAX_WISCHER) {
                    fertig(Sprache.t("aborted: store not found on the clan map (template cl_store?)",
                            "abgebrochen: Store auf der Clan-Karte nicht gefunden (Vorlage cl_store?)"));
                    return true;
                }
                wischer++;
                boolean nachRechts = wischer <= 3;   // der Store liegt links
                int y = ctx.height() / 2;
                int links = (int) (ctx.width() * 0.30), rechts = (int) (ctx.width() * 0.70);
                Log.action("Karte", Sprache.t("Dragging the clan map to the " + (nachRechts ? "right" : "left") + " to find the store (move " + wischer + ").",
                        "Clan-Karte nach " + (nachRechts ? "rechts" : "links")
                        + " ziehen, um den Store zu finden (Zug " + wischer + ")."));
                if (nachRechts) ctx.input().drag(links, y, rechts, y);
                else ctx.input().drag(rechts, y, links, y);
                ctx.sleepUntilChanged(500);
                return true;
            }
            case LADEN -> {
                return laden(screen, ctx, imLaden, aufKarte, jetzt);
            }
            case RAUS -> {
                if (imLaden) {
                    if (xKlicken(screen, ctx, Sprache.t("Close the clan store", "Clan-Laden schliessen"))) return true;
                    return warten(ctx, Sprache.t("red X in the store", "rotes X im Laden"));
                }
                if (aufKarte) {
                    if (jetzt - zuletztGeklickt < 1500) return warten(ctx, null);
                    return klicken(screen, ctx, T_HOME, Sprache.t("Home: back to the castle", "Home: zurueck in die Burg"));
                }
                if (inBurg) {
                    return ende(schluss == null ? "fertig" : schluss);
                }
                // Ein Fenster davor (z. B. Belohnung): nur schliessen, nie bestaetigen.
                if (xKlicken(screen, ctx, Sprache.t("Close the window (way out of the clan store)", "Fenster schliessen (Weg aus dem Clan-Laden)"))) return true;
                return warten(ctx, "Burg");
            }
        }
        return warten(ctx, null);
    }

    /** Im Royal Store: Food chest suchen, pruefen, kaufen. */
    private boolean laden(Mat screen, BotContext ctx, boolean imLaden, boolean aufKarte, long jetzt) {
        if (!imLaden) {
            // Nach dem Kauf-Klick: fragt das Spiel nach? Dann bestaetigen -- nur hier.
            if (kaufGeklicktUm > 0 && ctx.visibleIfKnown(screen, T_BESTAETIGEN)) {
                return klicken(screen, ctx, T_BESTAETIGEN, "Kauf bestaetigen (Food chest)");
            }
            if (aufKarte) {   // Laden ist zu (warum auch immer): zurueck in die Burg
                phase = Phase.RAUS;
                return true;
            }
            if (kaufGeklicktUm > 0 && jetzt - kaufGeklicktUm > 4000
                    && xKlicken(screen, ctx, Sprache.t("Close the window after the purchase", "Fenster nach dem Kauf schliessen"))) {
                return true;
            }
            return warten(ctx, "Royal Store");
        }
        blind = 0;
        if (schluss != null) { phase = Phase.RAUS; return true; }

        long jetztMuenzen = muenzenLesen(screen, ctx);

        // Hat der letzte Kauf gewirkt? Der Muenzstand muss gesunken sein.
        if (kaufGeklicktUm > 0) {
            // Live-Test 16.10.: die Nachfrage des Spiels liegt UEBER dem Laden (imLaden bleibt wahr) -- vorher wurde sie
            // nur bei geschlossenem Laden gesucht, und nach 2,5 s hiess es "Kauf ohne Wirkung".
            if (ctx.visibleIfKnown(screen, T_BESTAETIGEN)) {
                kaufGeklicktUm = jetzt;   // ab der Bestaetigung neu auf den Muenzstand warten
                return klicken(screen, ctx, T_BESTAETIGEN, "Kauf bestaetigen (Food chest)");
            }
            if (jetzt - kaufGeklicktUm < 1500) return warten(ctx, null);
            if (jetztMuenzen >= 0 && muenzenVorKauf >= 0 && jetztMuenzen < muenzenVorKauf) {
                gekauft++;
                kaufOhneWirkung = 0;
                Log.info("Clan-Laden", Sprache.t("Food chest bought (" + gekauft + "). Clan coins: " + zahl(muenzenVorKauf) + " -> " + zahl(jetztMuenzen) + ".",
                        "Food chest gekauft (" + gekauft + "). Clan-Muenzen: "
                        + zahl(muenzenVorKauf) + " -> " + zahl(jetztMuenzen) + "."));
            } else if (++kaufOhneWirkung >= 2) {
                kaufGeklicktUm = 0;
                fertig(Sprache.t("Purchase without effect -- the coin count did not drop. Nothing more is bought. " + "(Does the game ask for confirmation? Then please capture cl_kauf_bestaetigen.)",
                        "Kauf ohne Wirkung -- der Muenzstand sank nicht. Es wird nichts mehr gekauft. "
                        + "(Fragt das Spiel nach? Dann bitte cl_kauf_bestaetigen aufnehmen.)"));
                return true;
            } else {
                return warten(ctx, null);
            }
            kaufGeklicktUm = 0;
        }
        if (jetztMuenzen >= 0) muenzen = jetztMuenzen;

        // Die Food chest finden -- sonst herunterscrollen.
        Optional<Matcher.Hit> kiste = ctx.matcher().find(screen, T_KISTE);
        if (kiste.isEmpty()) {
            if (scrolls >= MAX_SCROLLS) {
                fertig(Sprache.t("The food chest is not visible in the Royal Store (template cl_food_chest?).",
                        "Die Food chest ist im Royal Store nicht zu sehen (Vorlage cl_food_chest?)."));
                return true;
            }
            scrolls++;
            int x = (int) (ctx.width() * 0.45);
            Log.action("Wischen", Sprache.t("Scrolling the Royal Store down to find the food chest (" + scrolls + ").",
                    "Royal Store nach unten scrollen, um die Food chest zu finden (" + scrolls + ")."));
            // Live-Test 16.10.: der schnelle Zug rollte mit Schwung nach und sprang ueber die Reihe der Food chest
            // (Foto: erst "Guardians", dann schon "Mithril Chest"). Langsam und ein Drittel Bild -- dann ist jede
            // Reihe einmal ganz zu sehen.
            ctx.input().dragLangsam(x, (int) (ctx.height() * 0.70), x, (int) (ctx.height() * 0.38));
            ctx.sleepUntilChanged(800);
            return true;
        }
        Matcher.Hit k = kiste.get();
        int spalte = (int) (ctx.width() * 0.11);

        // "Out of stock" ueber dem Namen dieser Kiste?
        if (ctx.matcher().has(T_AUSVERKAUFT)) {
            for (Matcher.Hit a : ctx.matcher().findAll(screen, T_AUSVERKAUFT, Matcher.DEFAULT_THRESHOLD, 4)) {
                if (Math.abs(a.centerX() - k.centerX()) < spalte && a.centerY() <= k.centerY()
                        && k.centerY() - a.centerY() < ctx.height() * 0.10) {
                    fertig(Sprache.t(gekauft > 0 ? "All food chests for today bought (" + gekauft + ")." : "Food chest is sold out today -- nothing to buy.",
                            gekauft > 0 ? "Alle Food chests fuer heute gekauft (" + gekauft + ")."
                                       : "Food chest ist heute ausverkauft -- nichts zu kaufen."));
                    return true;
                }
            }
        }
        if (gekauft >= MAX_JE_TAG) {
            fertig(Sprache.t(MAX_JE_TAG + " food chests bought -- that is all for today.",
                    MAX_JE_TAG + " Food chests gekauft -- mehr gibt es am Tag nicht."));
            return true;
        }

        // Das Preisfeld dieser Kiste: Clan-Muenze unterhalb des Namens, in derselben Spalte.
        Matcher.Hit preisFeld = null;
        for (Matcher.Hit p : ctx.matcher().findAll(screen, T_PREIS, Matcher.DEFAULT_THRESHOLD, 12)) {
            int dy = p.centerY() - k.centerY();
            if (Math.abs(p.centerX() - k.centerX()) < spalte && dy > ctx.height() * 0.05
                    && dy < ctx.height() * 0.30) {
                if (preisFeld == null || dy < preisFeld.centerY() - k.centerY()) preisFeld = p;
            }
        }
        if (preisFeld == null && k.centerY() > ctx.height() * 0.50 && scrolls < MAX_SCROLLS) {
            // Live-Test 16.10.: Kiste unten am Rand, ihr Preis noch abgeschnitten -- ein kleines Stueck nachruecken.
            scrolls++;
            int x = (int) (ctx.width() * 0.45);
            Log.action("Wischen", Sprache.t("Food chest at the bottom edge -- moving it up a little to see the price.",
                    "Food chest am unteren Rand -- ein Stueck hochschieben, um den Preis zu sehen."));
            ctx.input().dragLangsam(x, (int) (ctx.height() * 0.65), x, (int) (ctx.height() * 0.45));
            ctx.sleepUntilChanged(800);
            return true;
        }
        if (preisFeld == null) {
            fertig(Sprache.t("No clan coin price under the food chest -- nothing is bought.",
                    "Unter der Food chest steht kein Preis mit Clan-Muenzen -- es wird nichts gekauft."));
            return true;
        }
        if (preis < 0) {
            BufferedImage bild = ctx.frame();
            OptionalLong p = bild == null ? OptionalLong.empty()
                    : ArenaLeser.zahlRechtsVon(bild, preisFeld, preisFeld.width() * 5);
            if ((p.isEmpty() || p.getAsLong() < 10) && bild != null) {
                p = Nahrungskisten.zahlVerstaerkt(bild, preisFeld.x() + preisFeld.width(), preisFeld.y() - 4,
                        preisFeld.width() * 5, preisFeld.height() + 8, true);
            }
            if (p.isEmpty() || p.getAsLong() < 10 || p.getAsLong() > 10_000_000) {
                fertig(Sprache.t("The price of the food chest is not readable -- to be safe nothing is bought.",
                        "Der Preis der Food chest ist nicht lesbar -- sicherheitshalber wird nichts gekauft."));
                return true;
            }
            preis = p.getAsLong();
            Log.info("Clan-Laden", Sprache.t("Food chest costs " + zahl(preis) + " clan coins.",
                    "Food chest kostet " + zahl(preis) + " Clan-Muenzen."));
        }
        if (muenzen < 0) {
            fertig(Sprache.t("The clan coins are not readable -- to be safe nothing is bought.",
                    "Die Clan-Muenzen sind nicht lesbar -- sicherheitshalber wird nichts gekauft."));
            return true;
        }
        if (muenzen < preis) {
            fertig(Sprache.t(gekauft > 0 ? gekauft + " food chests bought, not enough clan coins for more (" + zahl(muenzen) + ")." : "Too few clan coins (" + zahl(muenzen) + ", a food chest costs " + zahl(preis) + ").",
                    gekauft > 0 ? gekauft + " Food chests gekauft, fuer mehr reichen die Clan-Muenzen nicht ("
                                 + zahl(muenzen) + ")."
                               : "Zu wenig Clan-Muenzen (" + zahl(muenzen) + ", eine Food chest kostet "
                                 + zahl(preis) + ")."));
            return true;
        }

        Log.action("Klick", Sprache.t("Buy food chest (" + (gekauft + 1) + "), " + zahl(preis) + " clan coins  [" + preisFeld.centerX() + "," + preisFeld.centerY() + String.format(", match %.3f]", preisFeld.score()),
                "Food chest kaufen (" + (gekauft + 1) + "), " + zahl(preis) + " Clan-Muenzen  ["
                + preisFeld.centerX() + "," + preisFeld.centerY() + String.format(", Uebereinstimmung %.3f]",
                preisFeld.score())));
        ctx.input().click(preisFeld);
        klicks++;
        muenzenVorKauf = muenzen;
        kaufGeklicktUm = System.currentTimeMillis();
        zuletztGeklickt = kaufGeklicktUm;
        ctx.sleepUntilChanged(1200);
        return true;
    }

    private boolean ende(String grund) {
        aktiv = false;
        ergebnis = grund;
        Log.info("Clan-Laden", Sprache.t("Clan store: " + grund + " (" + gekauft + " bought, " + (System.currentTimeMillis() - begonnen) / 1000 + " s).",
                "Clan-Laden: " + grund + " (" + gekauft + " gekauft, "
                + (System.currentTimeMillis() - begonnen) / 1000 + " s)."));
        return false;
    }

    /** Clan-Muenzen: die Zahl rechts neben dem Symbol oben links; -1 = nicht lesbar. */
    private static long muenzenLesen(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> s = ctx.matcher().find(screen, T_MUENZEN);
        BufferedImage bild = ctx.frame();
        if (s.isEmpty() || bild == null) return -1;
        Matcher.Hit h = s.get();
        OptionalLong n = ArenaLeser.zahlRechtsVon(bild, h, h.width() * 5);
        if (n.isEmpty()) n = Nahrungskisten.zahlVerstaerkt(bild, h.x() + h.width(), h.y() - 4,
                h.width() * 5, h.height() + 8, true);
        return n.isPresent() && n.getAsLong() >= 0 && n.getAsLong() < 100_000_000 ? n.getAsLong() : -1;
    }

    private boolean klicken(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.clickIfVisibleIfKnown(screen, vorlage, grund)) return warten(ctx, vorlage);
        blind = 0;
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1500);
        return true;
    }

    private boolean xKlicken(Mat screen, BotContext ctx, String grund) {
        var x = ctx.matcher().find(screen, T_X, X_SCHWELLE);
        if (x.isEmpty()) return false;
        Log.action("Klick", Sprache.t(grund + "  [close_x at " + x.get().centerX() + "," + x.get().centerY() + "]",
                grund + "  [close_x bei " + x.get().centerX() + "," + x.get().centerY() + "]"));
        ctx.input().click(x.get());
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    private boolean warten(BotContext ctx, String worauf) {
        if (worauf != null && ++blind > MAX_BLIND) {
            if (phase != Phase.RAUS) {
                fertig(Sprache.t("aborted: " + worauf + " not recognized",
                        "abgebrochen: " + worauf + " nicht erkannt"));
                blind = 0;
                return true;
            }
            return ende(Sprache.t("aborted: " + worauf + " not recognized", "abgebrochen: " + worauf + " nicht erkannt"));
        }
        ctx.sleep(250);
        return true;
    }

    private static String zahl(long n) {
        return String.format("%,d", n).replace(',', ' ');
    }
}
