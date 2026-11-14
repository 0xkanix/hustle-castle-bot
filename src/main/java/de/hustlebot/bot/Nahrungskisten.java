package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Schalterdatei;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import de.hustlebot.core.Sprache;

/**
 * Nahrung fuer den Clan-Krieg aus den Nahrungskisten der Tasche holen.
 *
 * Autor, 27.09.: "Einmal seinen Trupp im Clankrieg einzusetzen kostet 280k
 * Nahrung. Der Bot kann auch schon, bevor er in den CK geht, einmal kurz
 * nachschauen, ob ich 280k Nahrung habe. Wenn nicht, oeffnet er die Truhen,
 * geht zurueck zum CK und setzt dann erst meine Einheiten rein." Der
 * Titanen-Turm kostet nichts.
 *
 * DER WEG (Autors Bilder vom 27.09.):
 *
 *   1. In der Burg die Nahrung oben ablesen (Apfel, daneben z. B. 609 760).
 *      Reicht sie fuer 280 000: nichts zu tun.
 *   2. Tasche auf (Truhen-Knopf ueber "Map") -> "Warehouse".
 *   3. JEDE Nahrungskiste einmal anklicken: im Fenster steht "Contents:" und
 *      darunter, wie viel Nahrung EINE solche Kiste bringt (135 000). Mit dem
 *      roten X zurueck in die Tasche. Die Stueckzahl steht oben links am Feld.
 *   4. Ausrechnen, welche Kisten geoeffnet werden: so wenig Nahrung wie
 *      moeglich ueber den Bedarf hinaus -- Autor: "manchmal gibt es Kisten,
 *      die geben nur 40k Nahrung, dann soll er diese Kisten lieber 5 Mal
 *      oeffnen anstatt eine Kiste, die 1 Mio Nahrung gibt". Bei gleicher
 *      Nahrung entscheiden die wenigeren Kisten.
 *   5. Oeffnen: Kiste anklicken, "Open x1", in die leere Flaeche tippen
 *      (die Animation laeuft schneller), dann "Next x1" fuer die naechste
 *      derselben Sorte oder "Close" zurueck in die Tasche.
 *   6. Tasche zu, Nahrung noch einmal ablesen, weiter zum Clan-Krieg.
 *
 * Reichen alle Kisten zusammen nicht, wird NICHTS geoeffnet (Autor) -- dann
 * setzt der Clan-Krieg wie bisher nur den Titanen-Trupp.
 *
 * Nichts wird gekauft. Taucht ein Fenster mit Diamantenpreis auf, wird es
 * nicht bedient. Fehlen Vorlagen, tut die Klasse nichts und sagt einmal,
 * welche fehlen.
 */
public final class Nahrungskisten {

    /**
     * Was das Setzen des normalen Trupps kostet, solange der Bot den Preis
     * noch nie selbst gesehen hat (Autor: 280k; mit Thronsaal 9 weniger).
     */
    public static final long KOSTEN_STANDARD = 280_000;
    /** Hier merkt sich der Bot den zuletzt im Clan-Krieg gelesenen Preis. */
    private static final Path PREIS_DATEI = Path.of("clankrieg-preis.txt");
    private static volatile long preis = preisLaden();

    /** Was der normale Trupp kostet: zuletzt gelesen, sonst 280 000. */
    public static long kosten() {
        return preis;
    }

    private static long preisLaden() {
        try {
            if (!java.nio.file.Files.exists(PREIS_DATEI)) return KOSTEN_STANDARD;
            for (String z : de.hustlebot.core.Dateien.zeilen(PREIS_DATEI)) {
                z = z.trim();
                if (z.isEmpty() || z.startsWith("#")) continue;
                long p = Long.parseLong(z);
                if (p >= 10_000 && p <= 5_000_000) return p;
            }
        } catch (Exception ignored) {
            // unlesbar: Standard
        }
        return KOSTEN_STANDARD;
    }

    /**
     * Liest im Truppenfenster des Clan-Kriegs den Preis unter "Deploy" ab
     * und merkt ihn sich (Autor, 27.09.: "der Preis veraendert sich" -- mit
     * dem Thronsaal). Gilt ab dem naechsten Krieg fuer die Pruefung vorher.
     */
    public static void preisLesen(BotContext ctx, Matcher.Hit knopf) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return;
        int h = (int) (ctx.height() * 0.06);
        OptionalLong p = ArenaLeser.zahl(bild, knopf.x() - knopf.width() / 4, knopf.y() + knopf.height(),
                knopf.width() + knopf.width() / 2, h);
        if (p.isEmpty()) {
            p = zahlVerstaerkt(bild, knopf.x() - knopf.width() / 4, knopf.y() + knopf.height(),
                    knopf.width() + knopf.width() / 2, h, true);
        }
        if (p.isEmpty() || p.getAsLong() < 10_000 || p.getAsLong() > 5_000_000) {
            Log.debug("Nahrung", "Preis unter \"Deploy\" nicht lesbar -- es bleibt bei " + preis + ".");
            return;
        }
        if (p.getAsLong() == preis) return;
        Log.info("Nahrung", Sprache.t("Clan war: the normal squad now costs " + p.getAsLong() + " food (remembered before: " + preis + ").",
                "Clan-Krieg: Der normale Trupp kostet jetzt " + p.getAsLong()
                + " Nahrung (vorher gemerkt: " + preis + ")."));
        preis = p.getAsLong();
        try {
            java.nio.file.Files.writeString(PREIS_DATEI, "# Preis fuer den normalen Trupp im Clan-Krieg "
                    + "(vom Bot unter \"Deploy\" abgelesen)\n" + preis + "\n");
        } catch (Exception e) {
            Log.debug("Nahrung", "Preis nicht gespeichert: " + e.getMessage());
        }
    }

    // --- Schalter (!settings nahrung an|aus) -------------------------------

    private static final Schalterdatei SCHALTER =
            new Schalterdatei(Path.of("nahrungskisten.txt"), "Nahrungskisten", true);   // Autor 17.10.: Standard an -- "das haette der Bot bemerken sollen" (vor dem Clan-Krieg erst Tasche, dann Clan-Laden)
    private static volatile boolean an = SCHALTER.lesen();

    public static boolean istAn() {
        return an;
    }

    public static void setAn(boolean neu) {
        an = neu;
        SCHALTER.setzen(neu, "Nahrungskisten vor dem Clan-Krieg oeffnen (an/aus), gesetzt mit "
                + "!settings nahrung");
    }

    // --- Vorlagen ------------------------------------------------------------

    /** Der Apfel oben in der Burg -- rechts daneben steht die Nahrung. */
    static final String T_NAHRUNG = "cw_nahrung_burg";
    /**
     * Die Nahrungskisten in der Tasche: cw_nahrung_kiste_1, _2 ... ohne
     * Obergrenze (bis 28.09. nur bis _8) -- jedes neue Motiv aus dem
     * Aufnahme-Programm wirkt sofort.
     */
    static final String T_KISTE = "cw_nahrung_kiste_";
    /** "Contents:" im Kistenfenster -- darunter steht die Nahrung je Kiste. */
    static final String T_INHALT = "cw_nahrung_inhalt";
    /** Der gruene Knopf "Open x1" im Kistenfenster. */
    static final String T_OEFFNEN = "cw_nahrung_oeffnen";
    /** Der gruene Knopf "Next x1" nach dem Oeffnen. */
    static final String T_WEITER = "cw_nahrung_weiter";

    private static final String T_TASCHE = "bag_button";
    private static final String T_TITEL = "bag_title";
    private static final String T_SCHLIESSEN = "bag_close";
    private static final String T_RESTLICH = "bag_remaining";
    private static final String T_ROTES_X = "close_x";
    private static final String T_MAP = "map_button";
    private static final String T_MAP_ALT = "map_button_alt";

    // --- Grenzen -------------------------------------------------------------

    private static final int MAX_KLICKS = 120;
    private static final int MAX_BLIND = 25;
    private static final long MAX_DAUER_MS = 13 * 60_000L;  // 28.09.: mit Werbevideos (vorher 9 min)
    /** Das X der Tasche und der Kistenfenster passt nur zu ~0,77 (Truhenoeffner). */
    private static final double X_SCHWELLE = 0.70;
    /** Schwelle fuer den Apfel in der Burg (klein, wechselnder Hintergrund). */
    private static final double APFEL_SCHWELLE = 0.72;
    /** Wohin waehrend der Animation getippt wird -- wie im Kisten-Gang, weit weg von allen Knoepfen. */
    private static final double TIPP_X = 0.24, TIPP_Y = 0.60;

    // --- Zustand -------------------------------------------------------------

    private enum Phase { LESEN, TASCHE, ERKUNDEN, OEFFNEN, ZUMACHEN, NACHLESEN, LADEN, VIDEO }

    /**
     * Zum Schluss (nur Clan-Krieg): Nahrung per Werbevideo, wenn Tasche und
     * Clan-Laden nicht gereicht haben (Autor, 28.09.). Siehe Nahrungsvideo.
     */
    private final Nahrungsvideo video = new Nahrungsvideo();

    /**
     * Keine Kiste in der Tasche: im Clan-Laden Food chests kaufen und dann
     * die Tasche noch einmal oeffnen (Autor, 27.09.). Einmal je Gang.
     */
    private final ClanLaden laden = new ClanLaden();
    private boolean ladenVersucht, nachLaden;
    /** Der Clan-Laden kam VOR der Tasche dran (Autor, 30.09.) -- danach auf jeden Fall noch in die Tasche. */
    private boolean ladenZuerst;

    /** Ein Feld in der Tasche: Motiv, Stelle, Nahrung je Kiste (-1 = noch nicht gesehen), Stueckzahl. */
    private static final class Feld {
        final String motiv;
        int x, y;
        /** Obere linke Ecke des Kistenbilds (fuer die Stueckzahl daneben). */
        int links, oben;
        long inhalt = -1;
        /** Stueckzahl; -1 = nicht lesbar (dann zeigt der Knopf "Next", ob noch eine da ist). */
        int anzahl = -1;
        /** Davon in diesem Gang schon geoeffnet. */
        int geoeffnet = 0;
        Feld(String motiv, int x, int y) {
            this.motiv = motiv;
            this.x = x;
            this.y = y;
        }
        String text() {
            return motiv.replace(T_KISTE, Sprache.t("Chest ", "Kiste ")) + " (" + (anzahl < 0 ? "?" : anzahl) + " x " + inhalt + ")";
        }
        /** Wie viele davon noch eingeplant werden duerfen. */
        int verfuegbar(long bedarf) {
            int noetig = inhalt > 0 ? (int) Math.min(50, (bedarf + inhalt - 1) / inhalt) : 0;
            return anzahl < 0 ? noetig : Math.max(0, Math.min(noetig, anzahl - geoeffnet));
        }
    }

    private boolean aktiv = false;
    /** Wie oft in diesem Gang eine Kiste gleichen Bilds, aber anderen Inhalts erwischt wurde. */
    private int verwechslungen;
    private Phase phase = Phase.LESEN;
    private long begonnen, zuletztGeklickt;
    /** Wann zuletzt "Open x1" / "Next x1" gedrueckt wurde -- die Kisten-Anzeige braucht danach einen Moment. */
    private long oeffnenGeklicktUm;
    /** Das Spiel hat beim Oeffnen nachgefragt (Lager voll): nichts mehr oeffnen. */
    private boolean lagerVollGemeldet;
    private int klicks, blind, leseVersuche, tascheVersuche, scrolls, geoeffnet;
    private long nahrungVorher = -1, bedarf;
    /** Eigenes Ziel (Teil A), sonst -1 = Preis des Kriegstrupps. */
    private long ziel = -1;
    /** Auch Food chests im Clan-Laden kaufen? Nur fuer den Clan-Krieg. */
    private boolean mitLaden = true;
    /** Zum Schluss Werbevideos? Ebenfalls nur fuer den Clan-Krieg -- eigener Schalter. */
    private boolean mitVideos = true;
    /** Nahrung aus den in diesem Gang geoeffneten Kisten. */
    private long nahrungGeoeffnet = 0;
    /** Die Sorte, die gerade geoeffnet wird. */
    private Feld aktuell = null;
    private final List<Feld> felder = new ArrayList<>();
    /** Das Feld, dessen Fenster gerade offen ist (Erkunden) bzw. geoeffnet wird. */
    private Feld offen = null;
    /** Was noch zu oeffnen ist: Feld -> Anzahl. */
    private final List<Feld> planFelder = new ArrayList<>();
    private final List<Integer> planAnzahl = new ArrayList<>();
    /** Von der gerade geoeffneten Sorte noch per "Next" zu oeffnen. */
    private int nochWeiter = 0;
    private long lagerSeit = 0;
    private boolean hinweisGezeigt = false;

    public boolean aktiv() {
        return aktiv;
    }

    /** Laeuft gerade eine Werbung? Dann darf niemand klicken (Autor: fremde Webseiten). */
    public boolean werbungLaeuft() {
        return aktiv && phase == Phase.VIDEO;
    }

    /** Sind die Vorlagen da, ohne die man sich verlaufen wuerde? */
    public boolean einsatzbereit(BotContext ctx) {
        var m = ctx.matcher();
        String[] noetig = {T_NAHRUNG, T_TASCHE, T_TITEL, T_KISTE + "1", T_INHALT, T_OEFFNEN, T_SCHLIESSEN};
        StringBuilder fehlt = new StringBuilder();
        for (String n : noetig) if (!m.has(n)) fehlt.append(fehlt.length() == 0 ? "" : ", ").append(n);
        if (fehlt.length() > 0 && !hinweisGezeigt) {
            hinweisGezeigt = true;
            Log.info("Nahrung", Sprache.t("Food chests before the clan war: templates are still missing (" + fehlt + "). Capture them with Capture-Images.exe (group CLANKRIEG). Until then the " + "clan war only deploys the titans when food runs short, as before.",
                    "Nahrungskisten vor dem Clan-Krieg: es fehlen noch Vorlagen ("
                    + fehlt + "). Mit Capture-Images.exe aufnehmen (Gruppe CLANKRIEG). Bis dahin setzt der "
                    + "Clan-Krieg wie bisher nur die Titanen, wenn die Nahrung nicht reicht."));
        }
        return fehlt.length() == 0;
    }

    /** Startet die Pruefung -- in der Burg, bevor es in den Clan-Krieg geht. */
    public void starten() {
        starten(-1);
    }

    /**
     * Wie starten(), aber mit eigenem Ziel: so viel Nahrung soll danach
     * mindestens da sein (Teil A, 28.09.: der Preis fuer Verlies, Kathedrale
     * oder Arena). -1 = der Preis des normalen Trupps im Clan-Krieg.
     */
    public void starten(long zielNahrung) {
        starten(zielNahrung, true, true);
    }

    /**
     * Auffuellen (Autor, 29.09.): Kisten so oeffnen, dass die Nahrung moeglichst
     * NAH an {@code zielNahrung} kommt (leicht drueber erlaubt), mindestens aber
     * {@code mindestens} erreicht. Gebraucht wird: Kathedrale mit Nahrung -> bis
     * ans Lager (1,2 Mio). Ist schon {@code mindestens} da, wird nichts geoeffnet.
     */
    public void startenAuffuellen(long zielNahrung, long mindestens, boolean mitClanLaden, boolean mitWerbevideos) {
        starten(zielNahrung, mitClanLaden, mitWerbevideos);
        this.mindestens = Math.min(mindestens, zielNahrung);
        nahDran = true;
    }

    /** Beim Auffuellen: so viel muss mindestens da sein (-1 = wie ziel). */
    private long mindestens = -1;
    /** Auffuellen: nah ans Ziel statt "kleinste Summe ueber dem Bedarf". */
    private boolean nahDran = false;
    /** Beim Auffuellen: so viel fehlt mindestens (Preis). */
    private long mindestBedarf = 0;

    /**
     * @param mitClanLaden false = nur die Tasche. Autor, 28.09.: der Clan-Laden
     *                     ist ausschliesslich fuer den Clan-Krieg da.
     * @param mitWerbevideos zum Schluss Nahrung per Werbevideo (nur Clan-Krieg).
     */
    public void starten(long zielNahrung, boolean mitClanLaden, boolean mitWerbevideos) {
        mindestens = -1;
        nahDran = false;
        mindestBedarf = 0;
        mitLaden = mitClanLaden;
        mitVideos = mitWerbevideos;
        ziel = zielNahrung;
        aktiv = true;
        phase = Phase.LESEN;
        begonnen = System.currentTimeMillis();
        zuletztGeklickt = 0;
        klicks = blind = leseVersuche = tascheVersuche = scrolls = geoeffnet = verwechslungen = 0;
        nahrungVorher = -1;
        bedarf = 0;
        nahrungGeoeffnet = 0;
        aktuell = null;
        felder.clear();
        offen = null;
        planFelder.clear();
        planAnzahl.clear();
        nochWeiter = 0;
        lagerSeit = 0;
        ladenVersucht = nachLaden = ladenZuerst = false;
        oeffnenGeklicktUm = 0;
        lagerVollGemeldet = false;
    }

    private void beenden(String grund) {
        if (!aktiv) return;
        aktiv = false;
        Log.info("Nahrung", Sprache.t("Food chests: " + grund + " (" + geoeffnet + " chests opened, " + (System.currentTimeMillis() - begonnen) / 1000 + " s).",
                "Nahrungskisten: " + grund + " (" + geoeffnet + " Kisten geoeffnet, "
                + (System.currentTimeMillis() - begonnen) / 1000 + " s)."));
    }

    /**
     * Ein Schritt, genau eine Handlung. False heisst: fertig, der Clan-Krieg
     * macht weiter (der Bot steht dann in der Burg oder ist dorthin unterwegs).
     */
    public boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;
        // Waehrend einer Werbung NIE auf "zumachen" springen -- das X wuerde die
        // Belohnung kosten. Die Videos haben eigene Grenzen.
        if (phase != Phase.VIDEO
                && (klicks > MAX_KLICKS || System.currentTimeMillis() - begonnen > MAX_DAUER_MS)) {
            phase = Phase.ZUMACHEN;
            if (klicks > MAX_KLICKS + 10 || System.currentTimeMillis() - begonnen > MAX_DAUER_MS + 30_000) {
                beenden(Sprache.t("aborted, click or time limit reached",
                        "abgebrochen, Klick- oder Zeitgrenze erreicht"));
                return false;
            }
        }
        boolean inBurg = ctx.visibleIfKnown(screen, T_MAP) || ctx.visibleIfKnown(screen, T_MAP_ALT);
        boolean lager = ctx.visibleIfKnown(screen, T_TITEL);
        boolean fenster = ctx.visibleIfKnown(screen, T_INHALT);
        boolean oeffnenBild = ctx.visibleIfKnown(screen, T_SCHLIESSEN) || ctx.visibleIfKnown(screen, T_RESTLICH)
                || ctx.visibleIfKnown(screen, T_WEITER);

        // Autor 17.10.: "Lager koennte ueberlaufen -- trotzdem oeffnen?" -> Yes (die Kiste zaehlt dann ganz normal).
        if (phase != Phase.VIDEO && phase != Phase.LADEN && Stoerfenster.lagerUeberlaufJa(screen, ctx, "Nahrung")) {
            blind = 0;
            return true;
        }
        // "Lager voll"-Nachfrage nach "Open" (30.09., 19:45): ablehnen, nichts mehr oeffnen, zumachen.
        if (phase != Phase.VIDEO && phase != Phase.LADEN && Stoerfenster.lagerVollFrage(screen, ctx, "Nahrung")) {
            if (!lagerVollGemeldet) {
                lagerVollGemeldet = true;
                // Die gerade gezaehlte Kiste ging nicht auf -- aber nur, wenn die Nachfrage zu einem
                // eben gedrueckten Open/Next gehoert (Code-Pruefung 30.09.: sonst wuerde eine wirklich
                // geoeffnete Kiste abgezogen).
                if (System.currentTimeMillis() - oeffnenGeklicktUm < 10_000) {
                    if (geoeffnet > 0) geoeffnet--;
                    if (aktuell != null && aktuell.geoeffnet > 0) {
                        aktuell.geoeffnet--;
                        nahrungGeoeffnet -= Math.max(0, aktuell.inhalt);
                    }
                }
            }
            planFelder.clear();
            planAnzahl.clear();
            nochWeiter = 0;
            offen = null;
            neuPlanen = false;
            oeffnenGeklicktUm = 0;
            phase = Phase.ZUMACHEN;
            klicks++;
            blind = 0;
            zuletztGeklickt = System.currentTimeMillis();
            ctx.sleepUntilChanged(1200);
            return true;
        }

        switch (phase) {
            case LESEN -> {
                if (!inBurg || lager || fenster) return warten(ctx, "Burg");
                OptionalLong n = nahrungLesen(ctx);
                if (n.isEmpty()) {
                    if (++leseVersuche >= 3) {
                        beenden(Sprache.t("food at the top of the castle not readable -- staying as it was",
                                "Nahrung oben in der Burg nicht lesbar -- es bleibt beim Alten"));
                        return false;
                    }
                    ctx.sleep(400);
                    return true;
                }
                nahrungVorher = n.getAsLong();
                long kosten = ziel > 0 ? ziel : kosten();
                long reicht = nahDran && mindestens > 0 ? mindestens : kosten;
                if (nahrungVorher >= reicht) {
                    beenden(Sprache.t("enough food there (" + nahrungVorher + ", needed " + kosten + "), nothing to open",
                            "genug Nahrung da (" + nahrungVorher + ", gebraucht werden " + kosten
                            + "), nichts zu oeffnen"));
                    return false;
                }
                bedarf = kosten - nahrungVorher;
                mindestBedarf = nahDran && mindestens > 0 ? mindestens - nahrungVorher : bedarf;
                Log.info("Nahrung", Sprache.t("Food " + nahrungVorher + ", needed " + kosten + " -- short by " + bedarf + ". Searching the bag for food chests.",
                        "Nahrung " + nahrungVorher + ", gebraucht werden " + kosten
                        + " -- es fehlen " + bedarf + ". Die Tasche wird nach Nahrungskisten durchsucht."));
                // Steht an der Tasche eine 0, ist sie leer (Autor, 27.09.) -- gleich in den Clan-Laden.
                OptionalLong inTasche = Burgstand.taschenZahl(screen, ctx);
                if (inTasche.isPresent() && inTasche.getAsLong() == 0) {
                    Log.info("Nahrung", Sprache.t("The bag shows 0 -- it is empty, no need to look inside.",
                            "An der Tasche steht 0 -- sie ist leer, der Blick hinein faellt weg."));
                    if (mitLaden && ClanLaden.einsatzbereit(ctx)) {
                        ladenVersucht = true;
                        laden.starten();
                        phase = Phase.LADEN;
                        return true;
                    }
                    // Kein Clan-Laden: weiter zum Nachlesen -- dort kommen (im Krieg) die Videos.
                    Log.info("Nahrung", Sprache.t(mitLaden ? "Bag empty and no clan store (templates missing)." : "Bag empty.",
                            mitLaden ? "Tasche leer und kein Clan-Laden (Vorlagen fehlen)." : "Tasche leer."));
                    phase = Phase.NACHLESEN;
                    leseVersuche = 0;
                    return true;
                }
                // Autor 17.10.: "Der Bot soll erst in meine Tasche schauen, ob Nahrungskisten da sind, falls keine da
                // sind, soll er erst in den Clan-Laden gehen und welche kaufen!" -- vorher (30.09.) kam der Clan-Laden
                // zuerst. Jetzt: Tasche; keine Kisten oder zu wenig -> Clan-Laden (siehe erkunden()/ZUMACHEN).
                phase = Phase.TASCHE;
                return klicken(screen, ctx, T_TASCHE, Sprache.t("Open the bag (food chests)", "Tasche oeffnen (Nahrungskisten)"));
            }
            case TASCHE -> {
                if (lager) {
                    phase = Phase.ERKUNDEN;
                    lagerSeit = 0;
                    blind = 0;
                    return true;
                }
                if (inBurg && System.currentTimeMillis() - zuletztGeklickt > 2000) {
                    if (++tascheVersuche > 3) {
                        beenden(Sprache.t("bag did not open", "Tasche ging nicht auf"));
                        return false;
                    }
                    return klicken(screen, ctx, T_TASCHE, Sprache.t("Open the bag (food chests)", "Tasche oeffnen (Nahrungskisten)"));
                }
                return warten(ctx, "Tasche");
            }
            case ERKUNDEN -> {
                return erkunden(screen, ctx, lager, fenster);
            }
            case OEFFNEN -> {
                return oeffnen(screen, ctx, lager, fenster, oeffnenBild);
            }
            case ZUMACHEN -> {
                // Den gelben "Close" IMMER zuerst, wenn er da ist (30.09., 03:05: die Kisten-
                // Anzeige lag noch ueber der Tasche, das rote X dahinter war 250 s lang
                // nicht zu treffen -- danach stand alles, bis zum Fehler der Kathedrale).
                // NUR wenn er da ist: klicken() wartet sonst auf ihn, und das rote X der Tasche
                // kam nie dran (30.09., 17:39: "Weg nicht gefunden (wartete auf: bag_close)",
                // die Nahrung wurde nicht nachgelesen, die Kathedrale gab die Nahrung auf).
                if (ctx.visibleIfKnown(screen, T_SCHLIESSEN)
                        && klicken(screen, ctx, T_SCHLIESSEN, Sprache.t("Close the chest screen", "Kisten-Bildschirm schliessen"))) return true;
                if (fenster || lager) {
                    if (xKlicken(screen, ctx, fenster ? Sprache.t("Close the chest window", "Kistenfenster schliessen") : Sprache.t("Close the bag", "Tasche schliessen"))) {
                        return true;
                    }
                    return warten(ctx, "rotes X");
                }
                if (inBurg) {
                    if (nachLaden) {
                        nachLaden = false;
                        laden.starten();
                        phase = Phase.LADEN;
                        return true;
                    }
                    phase = Phase.NACHLESEN;
                    leseVersuche = 0;
                    ctx.sleep(400);
                    return true;
                }
                return warten(ctx, "Burg");
            }
            case LADEN -> {
                blind = 0;
                if (laden.schritt(screen, ctx)) return true;
                if (laden.gekauft() > 0) {
                    // Zurueck in die Tasche -- jetzt liegen die gekauften Kisten dort.
                    Log.info("Nahrung", Sprache.t(laden.gekauft() + " food chests bought -- the bag gets opened " + "once more.",
                            laden.gekauft() + " Food chests gekauft -- die Tasche wird noch "
                            + "einmal geoeffnet."));
                    felder.clear();
                    planFelder.clear();
                    planAnzahl.clear();
                    offen = null;
                    aktuell = null;
                    scrolls = 0;
                    lagerSeit = 0;
                    tascheVersuche = 0;
                    zuletztGeklickt = 0;
                    phase = Phase.TASCHE;
                    ladenZuerst = false;
                    return true;
                }
                if (ladenZuerst) {
                    // Im Laden gab es nichts (heute schon 7 gekauft, keine Muenzen): jetzt die Tasche.
                    ladenZuerst = false;
                    Log.info("Nahrung", Sprache.t("Nothing bought in the clan store -- now the food chests in the bag.",
                            "Im Clan-Laden nichts gekauft -- jetzt die Nahrungskisten in der Tasche."));
                    felder.clear();
                    planFelder.clear();
                    planAnzahl.clear();
                    offen = null;
                    aktuell = null;
                    scrolls = 0;
                    lagerSeit = 0;
                    tascheVersuche = 0;
                    zuletztGeklickt = 0;
                    phase = Phase.TASCHE;
                    return true;
                }
                phase = Phase.NACHLESEN;
                leseVersuche = 0;
                return true;
            }
            case NACHLESEN -> {
                if (!inBurg) return warten(ctx, "Burg");
                OptionalLong n = nahrungLesen(ctx);
                // Nach dem Oeffnen zaehlt die Anzeige erst hoch (30.09., 17:39: 0,2 s nach dem Schliessen
                // stand noch der alte Wert da, die Kathedrale gab die Nahrung auf). Kurz nachlesen.
                long mind = mindestensNachKisten();
                if (geoeffnet > 0 && n.isPresent() && nachlesenNoetig(n.getAsLong(), nahrungVorher, mind) && leseVersuche++ < 6) {
                    ctx.sleep(500);
                    return true;
                }
                if (n.isPresent() && mind > n.getAsLong()) {
                    Log.warn("Nahrung", Sprache.t("The display reads " + de.hustlebot.core.Bezahlung.zahl(n.getAsLong()) + ", after the chests it should be at least " + de.hustlebot.core.Bezahlung.zahl(mind) + " -- misread, the minimum applies.",
                            "Die Anzeige liest " + de.hustlebot.core.Bezahlung.zahl(n.getAsLong())
                            + ", nach den Kisten muessten es mindestens " + de.hustlebot.core.Bezahlung.zahl(mind)
                            + " sein -- Lesefehler, es gilt der Mindestwert."));
                    n = OptionalLong.of(mind);
                }
                long gebraucht = ziel > 0 ? ziel : kosten();
                boolean fehlt = n.isPresent() ? n.getAsLong() < gebraucht : geoeffnet == 0;
                // Reicht es noch nicht: im Clan-Krieg die Werbevideos (Autor, 28.09.).
                if (fehlt && mitVideos && Nahrungsvideo.moeglich(ctx)) {
                    video.starten(gebraucht);
                    phase = Phase.VIDEO;
                    return true;
                }
                beenden(Sprache.t(geoeffnet == 0 ? "nothing opened" : "food now " + (n.isPresent() ? n.getAsLong() : "not readable") + " (before " + nahrungVorher + ")",
                        geoeffnet == 0 ? "nichts geoeffnet"
                        : "Nahrung jetzt " + (n.isPresent() ? n.getAsLong() : "nicht lesbar")
                          + " (vorher " + nahrungVorher + ")"));
                return false;
            }
            case VIDEO -> {
                blind = 0;
                if (video.schritt(screen, ctx)) return true;
                beenden(Sprache.t(video.geschaut() + " ad video(s) watched",
                        video.geschaut() + " Werbevideo(s) geschaut"));
                return false;
            }
        }
        return false;
    }

    /** "Contents:" im offenen Kistenfenster: die Nahrung je Kiste. */
    private OptionalLong inhaltLesen(Mat screen, BotContext ctx) {
        Optional<Matcher.Hit> anker = ctx.matcher().find(screen, T_INHALT);
        BufferedImage bild = ctx.frame();
        OptionalLong wert = OptionalLong.empty();
        if (anker.isPresent() && bild != null) {
            Matcher.Hit a = anker.get();
            int hoehe = (int) (ctx.height() * 0.08);
            wert = ArenaLeser.zahlUnter(bild, a, hoehe);
            if (wert.isEmpty() || wert.getAsLong() < 1000) {
                wert = zahlVerstaerkt(bild, a.x() - a.width() / 4, a.y() + a.height(),
                        a.width() + a.width() / 2, hoehe, true);
            }
        }
        return wert;
    }

    // --- Erkunden: jede Nahrungskiste einmal ansehen ------------------------

    private boolean erkunden(Mat screen, BotContext ctx, boolean lager, boolean fenster) {
        // Kistenfenster offen: Inhalt ablesen, dann rotes X.
        if (fenster) {
            blind = 0;
            if (offen != null && offen.inhalt < 0) {
                OptionalLong wert = inhaltLesen(screen, ctx);
                if (wert.isPresent() && wert.getAsLong() >= 1000) {
                    offen.inhalt = wert.getAsLong();
                    Log.info("Nahrung", "Nahrungskiste angesehen: " + offen.text());
                } else if (++leseVersuche < 3) {
                    ctx.sleep(300);
                    return true;
                } else {
                    offen.inhalt = 0;   // nicht lesbar: diese Kiste wird nicht eingeplant
                    Log.warn("Nahrung", Sprache.t("Content of a food chest not readable -- it stays closed.",
                            "Inhalt einer Nahrungskiste nicht lesbar -- sie bleibt zu."));
                }
            }
            leseVersuche = 0;
            if (xKlicken(screen, ctx, Sprache.t("Close the chest window (content noted)", "Kistenfenster schliessen (Inhalt gemerkt)"))) {
                offen = null;
                return true;
            }
            return warten(ctx, Sprache.t("red X in the chest window", "rotes X im Kistenfenster"));
        }
        if (!lager) return warten(ctx, "Tasche");
        if (System.currentTimeMillis() - zuletztGeklickt < 900) {   // Fenster baut sich noch auf / zu
            ctx.sleep(150);
            return true;
        }
        blind = 0;

        // Alle Nahrungskisten im Bild; neue Felder aufnehmen.
        List<Feld> jetzt = felderFinden(screen, ctx);
        for (Feld f : jetzt) {
            if (feldAn(f.x, f.y) == null) {
                f.anzahl = anzahlLesen(ctx, f);
                felder.add(f);
            }
        }
        for (Feld f : felder) {
            if (f.inhalt < 0 && sichtbar(jetzt, f)) {
                offen = f;
                leseVersuche = 0;
                Log.action("Klick", Sprache.t("Look at the food chest  [" + f.motiv + " at " + f.x + "," + f.y + ", " + f.anzahl + " pieces]",
                        "Nahrungskiste ansehen  [" + f.motiv + " bei " + f.x + "," + f.y
                        + ", " + f.anzahl + " Stueck]"));
                ctx.input().click(f.x, f.y, 4);
                klicks++;
                zuletztGeklickt = System.currentTimeMillis();
                ctx.sleepUntilChanged(1200);
                return true;
            }
        }

        if (felder.isEmpty()) {
            // Erst ruhig hinsehen (die Tasche baut sich auf), dann ein Stueck
            // nach unten wischen -- Autor: "normalerweise stehen sie immer oben".
            if (lagerSeit == 0) lagerSeit = System.currentTimeMillis();
            if (System.currentTimeMillis() - lagerSeit < 1200) {
                ctx.sleep(200);
                return true;
            }
            if (scrolls < 2) {
                scrolls++;
                int x = ctx.width() / 2;
                Log.action("Klick", Sprache.t("Swipe the bag down a bit (no food chest in sight)",
                        "Tasche ein Stueck nach unten wischen (keine Nahrungskiste zu sehen)"));
                ctx.input().drag(x, (int) (ctx.height() * 0.78), x, (int) (ctx.height() * 0.50));
                lagerSeit = System.currentTimeMillis();
                ctx.sleep(800);
                return true;
            }
            Log.info("Nahrung", Sprache.t("No food chest found in the bag.", "Keine Nahrungskiste in der Tasche gefunden."));
            if (mitLaden && !ladenVersucht && ClanLaden.einsatzbereit(ctx)) {
                ladenVersucht = true;
                nachLaden = true;
                Log.info("Nahrung", Sprache.t("Off to the clan store: buying food chests with clan coins.",
                        "Es geht in den Clan-Laden: Food chests mit Clan-Muenzen kaufen."));
            }
            phase = Phase.ZUMACHEN;
            return true;
        }

        // Alle angesehen: planen.
        planen();
        if (planFelder.isEmpty() && mitLaden && !ladenVersucht && ClanLaden.einsatzbereit(ctx)) {
            // Kisten da, reichen aber nicht: wie ohne Kisten in den Clan-Laden (Autor 17.10.: erst Tasche, dann Laden).
            ladenVersucht = true;
            nachLaden = true;
            Log.info("Nahrung", Sprache.t("The food chests in the bag are not enough -- off to the clan store.",
                    "Die Nahrungskisten in der Tasche reichen nicht -- es geht in den Clan-Laden."));
        }
        phase = planFelder.isEmpty() ? Phase.ZUMACHEN : Phase.OEFFNEN;
        return true;
    }

    /** Alle Felder mit Nahrungskisten auf dem Bild (Mitte des Motivs). */
    private List<Feld> felderFinden(Mat screen, BotContext ctx) {
        List<Feld> aus = new ArrayList<>();
        for (String motiv : ctx.matcher().serie(T_KISTE)) {
            for (Matcher.Hit h : ctx.matcher().findAll(screen, motiv, 0.84, 12)) {
                boolean doppelt = false;
                for (Feld f : aus) doppelt |= Math.abs(f.x - h.centerX()) < 30 && Math.abs(f.y - h.centerY()) < 30;
                if (!doppelt) {
                    Feld f = new Feld(motiv, h.centerX(), h.centerY());
                    f.links = h.x();
                    f.oben = h.y();
                    aus.add(f);
                }
            }
        }
        return aus;
    }

    private Feld feldAn(int x, int y) {
        for (Feld f : felder) if (Math.abs(f.x - x) < 30 && Math.abs(f.y - y) < 30) return f;
        return null;
    }

    private static boolean sichtbar(List<Feld> jetzt, Feld f) {
        for (Feld g : jetzt) if (Math.abs(g.x - f.x) < 30 && Math.abs(g.y - f.y) < 30) return true;
        return false;
    }

    /**
     * Die Stueckzahl oben links am Feld ("4", "88"). Nicht lesbar -> 1: Dann
     * wird hoechstens eine Kiste dieser Sorte eingeplant, nie zu viele.
     */
    private int anzahlLesen(BotContext ctx, Feld f) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return -1;
        // Das Kaestchen mit der Zahl sitzt oben links am Feld, ueber der
        // linken Ecke des Kistenbilds (Autors Bild vom 27.09.: "3").
        double s = ctx.width() / 1159.0;
        int x = f.links - (int) (25 * s), y = f.oben - (int) (32 * s);
        int w = (int) (55 * s), h = (int) (40 * s);
        OptionalLong n = ArenaLeser.zahl(bild, Math.max(0, x), Math.max(0, y), w, h);
        if (n.isEmpty()) n = zahlVerstaerkt(bild, Math.max(0, x), Math.max(0, y), w, h, false);
        if (n.isPresent() && n.getAsLong() >= 1 && n.getAsLong() <= 999) return (int) n.getAsLong();
        return -1;   // nicht lesbar -- beim Oeffnen zeigt "Next", ob noch eine da ist
    }

    // --- Planen: so wenig Nahrung wie moeglich ueber den Bedarf -------------

    /**
     * Welche Kisten, wie viele? Kleinste Summe, die den Bedarf deckt; bei
     * gleicher Summe die wenigsten Kisten. Reicht alles zusammen nicht, wird
     * nichts eingeplant (Autor).
     */
    private void planen() {
        planFelder.clear();
        planAnzahl.clear();
        long rest = bedarf - nahrungGeoeffnet;
        long mindestRest = nahDran ? mindestBedarf - nahrungGeoeffnet : rest;
        // Auffuellen: auch wenn der Preis schon gedeckt ist, weiter nah ans Ziel
        // (nach dem Neuplanen hoerte er sonst beim Preis auf -- Code-Pruefung 29.09.).
        // "Nichts mehr oeffnen" ist dann eine erlaubte Wahl (Summe 0).
        if (rest <= 0) return;
        List<Feld> nutzbar = new ArrayList<>();
        long gesamt = 0;
        boolean unbekannt = false;
        for (Feld f : felder) {
            int frei = f.verfuegbar(rest);
            if (f.inhalt > 0 && frei > 0) {
                nutzbar.add(f);
                gesamt += f.inhalt * frei;
                unbekannt |= f.anzahl < 0;
            }
        }
        StringBuilder liste = new StringBuilder();
        for (Feld f : felder) liste.append(liste.length() == 0 ? "" : ", ").append(f.text());
        Log.info("Nahrung", Sprache.t("Food chests in the bag: " + (liste.length() == 0 ? "none" : liste) + ". Still to cover: " + rest + (unbekannt ? " (count not readable everywhere)" : "") + ".",
                "Nahrungskisten in der Tasche: " + (liste.length() == 0 ? "keine" : liste)
                + ". Noch zu decken: " + rest + (unbekannt ? " (Stueckzahl nicht ueberall lesbar)" : "") + "."));
        if (gesamt < Math.max(1, mindestRest)) {
            Log.info("Nahrung", Sprache.t("All food chests together (" + gesamt + ") are not enough for " + rest + " -- nothing (more) gets opened, only the titans are deployed.",
                    "Alle Nahrungskisten zusammen (" + gesamt + ") reichen nicht fuer "
                    + rest + " -- es wird nichts (mehr) geoeffnet, nur die Titanen werden gesetzt."));
            return;
        }
        long[] werte = new long[nutzbar.size()];
        int[] frei = new int[nutzbar.size()];
        for (int i = 0; i < nutzbar.size(); i++) {
            werte[i] = nutzbar.get(i).inhalt;
            frei[i] = nutzbar.get(i).verfuegbar(rest);
        }
        int[] beste = nahDran ? bestePlanungNah(werte, frei, rest, Math.max(0, mindestRest))
                              : bestePlanung(werte, frei, rest);
        // Autor 25.10. (Portal): "Er kann ruhig einfach 2 1-Million-Kisten oeffnen. Er muss nicht erst 7 135.000er
        // Kisten oeffnen und dann eine 1-Million-Kiste." -- lieber wenige grosse, wenn dabei nicht viel zu viel kommt.
        if (!nahDran && beste != null) beste = wenigerKisten(werte, frei, rest, beste);
        if (beste == null) return;
        long summe = 0;
        int kisten = 0;
        StringBuilder plan = new StringBuilder();
        for (int i = 0; i < nutzbar.size(); i++) {
            if (beste[i] <= 0) continue;
            planFelder.add(nutzbar.get(i));
            planAnzahl.add(beste[i]);
            summe += beste[i] * nutzbar.get(i).inhalt;
            kisten += beste[i];
            plan.append(plan.length() == 0 ? "" : " + ").append(beste[i]).append(" x ")
                .append(nutzbar.get(i).inhalt);
        }
        Log.info("Nahrung", Sprache.t("Plan: " + plan + " = " + summe + " food from " + kisten + " chests (to cover " + rest + ", surplus: " + (summe - rest) + ").",
                "Plan: " + plan + " = " + summe + " Nahrung aus " + kisten
                + " Kisten (zu decken " + rest + ", ueber: " + (summe - rest) + ")."));
    }

    /** So viel darf beim Plan "wenige grosse Kisten" hoechstens zu viel kommen (Autor 25.10.: "hoechstens etwa 200.000"). */
    static final long GROSS_UEBER_HOECHSTENS = 200_000;

    /**
     * Die groessten Kisten zuerst, so viele wie noetig (Autor 25.10.). Genommen, wenn es weniger Kisten sind als der Plan
     * mit der kleinsten Summe und hoechstens GROSS_UEBER_HOECHSTENS (200.000) zu viel kommt.
     */
    static int[] wenigerKisten(long[] werte, int[] anzahl, long bedarf, int[] bisher) {
        int n = werte.length;
        Integer[] reihe = new Integer[n];
        for (int i = 0; i < n; i++) reihe[i] = i;
        java.util.Arrays.sort(reihe, (a, b) -> Long.compare(werte[b], werte[a]));
        int[] wahl = new int[n];
        long summe = 0;
        for (int i : reihe) {
            if (werte[i] <= 0 || summe >= bedarf) continue;
            int k = (int) Math.min(anzahl[i], (bedarf - summe + werte[i] - 1) / werte[i]);
            wahl[i] = k;
            summe += k * werte[i];
        }
        if (summe < bedarf) return bisher;
        // Zu viel gross genommen? Die kleinste genommene Sorte zurueckgeben, solange der Bedarf gedeckt bleibt.
        for (int j = n - 1; j >= 0; j--) {
            int i = reihe[j];
            while (wahl[i] > 0 && summe - werte[i] >= bedarf) {
                wahl[i]--;
                summe -= werte[i];
            }
        }
        int kistenNeu = 0, kistenAlt = 0;
        for (int i = 0; i < n; i++) {
            kistenNeu += wahl[i];
            kistenAlt += bisher[i];
        }
        long ueber = summe - bedarf;
        if (kistenNeu < kistenAlt && ueber <= GROSS_UEBER_HOECHSTENS) return wahl;
        return bisher;
    }

    static int[] bestePlanung(long[] werte, int[] anzahl, long bedarf) {
        int n = werte.length;
        // Summe unter dem Bedarf -> (wenigste Kisten, Auswahl je Feld)
        Map<Long, int[]> stand = new HashMap<>();
        stand.put(0L, new int[n]);
        long besteSumme = Long.MAX_VALUE;
        int[] beste = null;
        for (int i = 0; i < n; i++) {
            if (werte[i] <= 0) continue;
            int grenze = (int) Math.min(anzahl[i], (bedarf + werte[i] - 1) / werte[i]);
            Map<Long, int[]> neu = new HashMap<>(stand);
            for (var e : stand.entrySet()) {
                for (int k = 1; k <= grenze; k++) {
                    long s = e.getKey() + k * werte[i];
                    int[] wahl = e.getValue().clone();
                    wahl[i] = k;
                    if (s >= bedarf) {
                        if (s < besteSumme || (s == besteSumme && kisten(wahl) < kisten(beste))) {
                            besteSumme = s;
                            beste = wahl;
                        }
                        break;   // mehr von derselben Sorte wird nur teurer
                    }
                    int[] alt = neu.get(s);
                    if (alt == null || kisten(wahl) < kisten(alt)) neu.put(s, wahl);
                }
            }
            stand = neu;
        }
        return beste;
    }

    /**
     * Auffuellen: die Auswahl, deren Summe am naechsten an {@code ziel} liegt
     * (drueber oder drunter), aber mindestens {@code mindestens}; bei gleichem
     * Abstand die wenigsten Kisten.
     */
    static int[] bestePlanungNah(long[] werte, int[] anzahl, long ziel, long mindestens) {
        int n = werte.length;
        Map<Long, int[]> stand = new HashMap<>();
        stand.put(0L, new int[n]);
        for (int i = 0; i < n; i++) {
            if (werte[i] <= 0) continue;
            int grenze = (int) Math.min(anzahl[i], (ziel + werte[i] - 1) / werte[i] + 1);
            Map<Long, int[]> neu = new HashMap<>(stand);
            for (var e : stand.entrySet()) {
                for (int k = 1; k <= grenze; k++) {
                    long s = e.getKey() + k * werte[i];
                    if (s > 2 * Math.max(ziel, mindestens) + werte[i]) break;   // weit drueber lohnt nie
                    int[] wahl = e.getValue().clone();
                    wahl[i] = k;
                    int[] alt = neu.get(s);
                    if (alt == null || kisten(wahl) < kisten(alt)) neu.put(s, wahl);
                }
            }
            stand = neu;
        }
        int[] beste = null;
        long besterAbstand = Long.MAX_VALUE;
        for (var e : stand.entrySet()) {
            long s = e.getKey();
            if (s < mindestens) continue;
            long abstand = Math.abs(s - ziel);
            if (abstand < besterAbstand || (abstand == besterAbstand && kisten(e.getValue()) < kisten(beste))) {
                besterAbstand = abstand;
                beste = e.getValue();
            }
        }
        return beste;
    }

    private static int kisten(int[] wahl) {
        if (wahl == null) return Integer.MAX_VALUE;
        int k = 0;
        for (int v : wahl) k += v;
        return k;
    }

    // --- Oeffnen ---------------------------------------------------------------

    private boolean oeffnen(Mat screen, BotContext ctx, boolean lager, boolean fenster, boolean oeffnenBild) {
        if (oeffnenBild) {
            blind = 0;
            boolean weiterDa = ctx.visibleIfKnown(screen, T_WEITER);
            boolean closeDa = ctx.visibleIfKnown(screen, T_SCHLIESSEN);
            if (nochWeiter > 0 && weiterDa) {
                nochWeiter--;
                gezaehltOeffnen(aktuell);
                oeffnenGeklicktUm = System.currentTimeMillis();
                return klicken(screen, ctx, T_WEITER, "Naechste Nahrungskiste derselben Sorte (Next x1)");
            }
            if (closeDa) {
                if (nochWeiter > 0) {
                    // Kein "Next" mehr: die Sorte ist leer (Stueckzahl war nicht
                    // lesbar oder falsch). Mit den anderen Kisten neu rechnen.
                    if (aktuell != null) aktuell.anzahl = aktuell.geoeffnet;
                    Log.info("Nahrung", Sprache.t("None of this kind left -- recalculating with the " + "remaining chests.",
                            "Von dieser Sorte ist keine mehr da -- es wird mit den "
                            + "uebrigen Kisten neu gerechnet."));
                    nochWeiter = 0;
                    neuPlanen = true;
                }
                return klicken(screen, ctx, T_SCHLIESSEN, Sprache.t("Back to the bag (Close)", "Zurueck in die Tasche (Close)"));
            }
            // Die Animation laeuft: in die leere Flaeche tippen, damit sie schneller vorbei ist.
            if (!weiterDa && !closeDa) {
                int x = (int) Math.round(ctx.width() * TIPP_X), y = (int) Math.round(ctx.height() * TIPP_Y);
                ctx.input().click(x, y, 8);
                ctx.sleep(250);
                return true;
            }
            return warten(ctx, "Kisten-Bildschirm");
        }
        // Gerade "Open" gedrueckt: die Kisten-Anzeige (oder eine Nachfrage) braucht einen Moment. Nicht
        // gleich die Tasche dahinter fuer "fertig" halten (30.09., 19:45: 120 Klicks auf das X dahinter).
        if (System.currentTimeMillis() - oeffnenGeklicktUm < 4000) {
            ctx.sleep(200);
            return true;
        }
        if (fenster) {
            blind = 0;
            if (offen != null && ctx.visibleIfKnown(screen, T_OEFFNEN)) {
                // GLEICHES BILD, ANDERER INHALT (Autor, 28.09.): Es gibt eine Kiste
                // mit 850 000, die genauso aussieht wie die mit 1 000 000. Nach dem
                // Oeffnen ruecken die Felder nach -- am Bild allein kann der Bot die
                // falsche erwischen (und zu viel oeffnen: das Lager fasst nur ~1,2 Mio).
                // Deshalb vor "Open" den Inhalt lesen; passt er nicht, ist es die
                // andere Sorte: merken, Fenster zu, die richtige nehmen.
                OptionalLong gelesen = offen.inhalt > 0 ? inhaltLesen(screen, ctx) : OptionalLong.empty();
                if (gelesen.isPresent() && gelesen.getAsLong() >= 1000 && gelesen.getAsLong() != offen.inhalt
                        && verwechslungen < 4) {
                    verwechslungen++;
                    long w = gelesen.getAsLong();
                    Feld richtig = null;
                    for (Feld f : felder) {
                        if (f != offen && f.motiv.equals(offen.motiv) && f.inhalt == w) richtig = f;
                    }
                    if (richtig == null) {
                        richtig = new Feld(offen.motiv, offen.x, offen.y);
                        richtig.inhalt = w;
                        felder.add(richtig);
                    }
                    richtig.x = offen.x;
                    richtig.y = offen.y;
                    // Wo die eingeplante Kiste wirklich liegt, ist jetzt unbekannt -- ihre alte
                    // Stelle gehoert der anderen Sorte (sonst sperrt sie dort die richtige).
                    offen.x = -10_000;
                    offen.y = -10_000;
                    Log.info("Nahrung", Sprache.t("Same image, different content: the chest clicked holds " + de.hustlebot.core.Bezahlung.zahl(w) + " instead of " + de.hustlebot.core.Bezahlung.zahl(offen.inhalt) + ". Closing the window, looking for the right one.",
                            "Gleiches Bild, anderer Inhalt: angeklickt wurde eine Kiste mit "
                            + de.hustlebot.core.Bezahlung.zahl(w) + " statt " + de.hustlebot.core.Bezahlung.zahl(offen.inhalt) + ". Fenster zu, die richtige wird gesucht."));
                    neuPlanen = true;
                    offen = null;
                    if (xKlicken(screen, ctx, Sprache.t("Close the chest window (wrong kind)", "Kistenfenster schliessen (falsche Sorte)"))) return true;
                    return warten(ctx, Sprache.t("red X in the chest window", "rotes X im Kistenfenster"));
                }
                int idx = planFelder.indexOf(offen);
                nochWeiter = idx >= 0 ? planAnzahl.get(idx) - 1 : 0;
                if (idx >= 0) {
                    planFelder.remove(idx);
                    planAnzahl.remove(idx);
                }
                aktuell = offen;
                gezaehltOeffnen(aktuell);
                Log.info("Nahrung", "Oeffne " + (nochWeiter + 1) + " x " + offen.text());
                offen = null;
                oeffnenGeklicktUm = System.currentTimeMillis();
                return klicken(screen, ctx, T_OEFFNEN, "Nahrungskiste oeffnen (Open x1)");
            }
            if (offen == null && xKlicken(screen, ctx, Sprache.t("Close the chest window", "Kistenfenster schliessen"))) return true;
            return warten(ctx, Sprache.t("Open x1 button", "Knopf Open x1"));
        }
        if (!lager) return warten(ctx, "Tasche");
        if (System.currentTimeMillis() - zuletztGeklickt < 900) {
            ctx.sleep(150);
            return true;
        }
        blind = 0;
        if (neuPlanen) {
            neuPlanen = false;
            planen();
        }
        if (planFelder.isEmpty()) {
            phase = Phase.ZUMACHEN;
            return true;
        }
        // Die naechste Sorte: am Motiv wiederfinden (nach dem Oeffnen koennen
        // die Felder nachruecken), das naechstgelegene zur gemerkten Stelle.
        Feld ziel = planFelder.get(0);
        Feld treffer = null;
        double beste = Double.MAX_VALUE;
        for (Feld f : felderFinden(screen, ctx)) {
            if (!f.motiv.equals(ziel.motiv)) continue;
            boolean andereSorte = false;
            for (Feld g : felder) {
                if (g != ziel && g.motiv.equals(ziel.motiv) && g.inhalt > 0 && g.inhalt != ziel.inhalt
                        && Math.abs(g.x - f.x) < 20 && Math.abs(g.y - f.y) < 20) andereSorte = true;
            }
            if (andereSorte) continue;   // gleiches Bild, aber die Kiste mit anderem Inhalt
            double d = Math.hypot(f.x - ziel.x, f.y - ziel.y);
            if (d < beste) {
                beste = d;
                treffer = f;
            }
        }
        if (treffer == null) {
            Log.warn("Nahrung", Sprache.t("The planned food chest (" + ziel.motiv + ") is no longer " + "in sight -- it is skipped.",
                    "Die eingeplante Nahrungskiste (" + ziel.motiv + ") ist nicht mehr zu "
                    + "sehen -- sie wird uebersprungen."));
            planFelder.remove(0);
            planAnzahl.remove(0);
            return true;
        }
        ziel.x = treffer.x;
        ziel.y = treffer.y;
        offen = ziel;
        Log.action("Klick", Sprache.t("Choose the food chest  [" + ziel.motiv + " at " + ziel.x + "," + ziel.y + "]",
                "Nahrungskiste waehlen  [" + ziel.motiv + " bei " + ziel.x + "," + ziel.y + "]"));
        ctx.input().click(ziel.x, ziel.y, 4);
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    /** Nach "Open"/"Next": eine Kiste dieser Sorte ist auf. */
    private void gezaehltOeffnen(Feld f) {
        geoeffnet++;
        if (f == null) return;
        f.geoeffnet++;
        nahrungGeoeffnet += Math.max(0, f.inhalt);
    }

    /** Eine Sorte war leer, bevor der Plan erfuellt war: in der Tasche neu rechnen. */
    private boolean neuPlanen = false;

    // --- Hilfen ------------------------------------------------------------------

    /**
     * Eine Zahl aus einem kleinen Ausschnitt, mit Vorstufe fuer die
     * Texterkennung: dreifach vergroessert und in klares Schwarz auf Weiss
     * umgewandelt, mit weissem Rand.
     *
     * Gemessen an Autors Bild vom 27.09.: Ohne Vorstufe las Windows die
     * Stueckzahl "3" am Kistenfeld gar nicht, und die Nahrung "1 034 601"
     * nur bei genau passendem Ausschnitt (zwei Pixel daneben: "aoooa").
     *
     * @param helleSchrift true fuer weisse Ziffern auf dunklem Grund (Nahrung
     *                     in der Burg), false fuer dunkle auf hellem (Stueckzahl)
     */
    static OptionalLong zahlVerstaerkt(BufferedImage bild, int x, int y, int breite, int hoehe,
                                       boolean helleSchrift) {
        String text = textVerstaerkt(bild, x, y, breite, hoehe, helleSchrift);
        return text == null ? OptionalLong.empty() : ArenaLeser.zahlAus(text);
    }

    /**
     * Ein Bruch wie "0/5" oder "90/90" (Energie im Kolosseum, Turm-Balken; 28.09.).
     * @return {vorne, hinten} oder null, wenn nichts in dieser Form lesbar ist
     */
    static int[] bruchVerstaerkt(BufferedImage bild, int x, int y, int breite, int hoehe, boolean helleSchrift) {
        String text = textVerstaerkt(bild, x, y, breite, hoehe, helleSchrift);
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*[/|\\\\]\\s*(\\d{1,3})")
                .matcher(text);
        if (!m.find()) {
            Log.debug("Text", "Kein Bruch erkannt in \"" + text + "\".");
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** Ausschnitt vergroessern, schwarz auf weiss machen, lesen. null = nichts brauchbar. */
    static String textVerstaerkt(BufferedImage bild, int x, int y, int breite, int hoehe, boolean helleSchrift) {
        if (bild == null) return null;
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(bild.getWidth(), x + breite), y2 = Math.min(bild.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return null;
        int f = 3, rand = 12;
        int w = (x2 - x1) * f, h = (y2 - y1) * f;
        BufferedImage aus = new BufferedImage(w + 2 * rand, h + 2 * rand, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = aus.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, aus.getWidth(), aus.getHeight());
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bild.getSubimage(x1, y1, x2 - x1, y2 - y1), rand, rand, w, h, null);
        g.dispose();
        // Schwelle aus dem Ausschnitt selbst: Mitte zwischen hellstem und
        // dunkelstem Punkt -- feste Werte passen nicht zu jedem Hintergrund.
        int min = 255, max = 0;
        int[] hell = new int[w * h];
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int p = aus.getRGB(xx + rand, yy + rand);
                int l = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
                hell[yy * w + xx] = l;
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
        }
        if (max - min < 40) return null;   // kein Kontrast, keine Schrift
        int schwelle = helleSchrift ? min + (max - min) * 2 / 3 : min + (max - min) / 3;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int l = hell[yy * w + xx];
                boolean schrift = helleSchrift ? l > schwelle : l < schwelle;
                aus.setRGB(xx + rand, yy + rand, schrift ? 0x000000 : 0xFFFFFF);
            }
        }
        return String.join(" ", de.hustlebot.core.Texterkennung.lies(aus)).trim();
    }

    /**
     * Die Nahrung oben in der Burg -- erst geglaubt, wenn zweimal hintereinander
     * dieselbe Zahl dasteht.
     *
     * WARUM (Testlauf 28.09., 15:11): Kommt man in die Burg, zaehlt die Anzeige
     * erst hoch. Innerhalb einer Sekunde las der Bot 53 310, 106 109 und
     * 134 293 -- echt waren gut 250 000. Mit dem ersten Wert haette er Kisten
     * geoeffnet, die gar nicht noetig waren (oder im Clan-Krieg zu wenig).
     */
    static OptionalLong nahrungLesen(BotContext ctx) {
        // Autor, 11.10. 15:05: Steht oben der Turnier-Balken der Arena ("Time left to select an
        // opponent", "Wait for the next stage ..."), verdeckt er die Vorratsleiste -- und das letzte
        // Turnier laeuft noch. Dann nichts ablesen; der Motor spielt erst die Arena zu Ende.
        if (Turnierbalken.sichtbar(ctx, 20_000)) {
            Log.info("Nahrung", Sprache.t("Food not read: the arena tournament bar is still at the top.",
                    "Nahrung nicht gelesen: oben steht noch der Turnier-Balken der Arena."));
            return OptionalLong.empty();
        }
        OptionalLong vorher = OptionalLong.empty();
        int unlesbar = 0;
        boolean gestiegen = false;   // die Anzeige zaehlt gerade hoch (zwei steigende Werte gesehen)
        for (int i = 0; i < 6; i++) {
            OptionalLong n = nahrungLesenEinmal(ctx);
            if (n.isEmpty()) {
                if (++unlesbar >= 2) return OptionalLong.empty();
            } else if (gestiegen && vorher.isPresent() && n.getAsLong() < vorher.getAsLong()) {
                // Die Anzeige zaehlt beim Betreten der Burg nur HOCH. Faellt der Wert mitten im
                // Hochzaehlen, ist das ein Lesefehler (30.09., 19:45: 246 924, 572 560, 887 954, dann
                // zweimal 0 -- echt waren 1 194 744; der Bot oeffnete eine Kiste in ein fast volles
                // Lager). Der bisher hoechste Wert bleibt stehen. NUR nach einem beobachteten Anstieg:
                // sonst bliebe ein einmalig zu HOCH gelesener erster Wert stehen (Code-Pruefung 30.09.).
                Log.debug("Nahrung", "Wert " + n.getAsLong() + " verworfen -- kleiner als eben gelesen ("
                        + vorher.getAsLong() + ").");
            } else if (vorher.isPresent() && n.getAsLong() == vorher.getAsLong()) {
                return n;
            } else {
                if (vorher.isPresent() && n.getAsLong() > vorher.getAsLong()) gestiegen = true;
                vorher = n;
            }
            ctx.sleep(350);
        }
        if (vorher.isPresent()) Log.debug("Nahrung", "Nahrung stand nie zweimal gleich da -- es gilt der letzte Wert.");
        return vorher;
    }

    private static OptionalLong nahrungLesenEinmal(BotContext ctx) {
        Mat bild = ctx.refresh();
        BufferedImage roh = ctx.frame();
        if (bild == null || roh == null) return OptionalLong.empty();
        // Lesetest 22.10. (30 Bilder, Burg verschoben): die weissen Ziffern der Leiste lasen die Nahrung immer richtig,
        // die Texterkennung darunter nur 23 Mal. Erst die Ziffern, sonst wie bisher.
        long z = Burgstand.nahrung(bild, roh, ctx.matcher());
        if (z >= 0) {
            Log.debug("Nahrung", "Nahrung in der Burg (Ziffern): " + z);
            return OptionalLong.of(z);
        }
        // Der Apfel ist klein und hat einen wechselnden Hintergrund (Autor,
        // 27.09.) -- darum eine niedrigere Schwelle, dafuer nur oben in der
        // Leiste und in der rechten Haelfte, wo die Nahrung steht.
        Optional<Matcher.Hit> apfel = ctx.matcher().find(bild, T_NAHRUNG, APFEL_SCHWELLE);
        if (apfel.isEmpty() || apfel.get().centerY() > ctx.height() * 0.20
                || apfel.get().centerX() < ctx.width() / 2) {
            return OptionalLong.empty();
        }
        // Eng um die Leiste lesen: an Autors Bild las Windows "1 034 601" nur
        // mit genau diesem Ausschnitt, ein hoeherer ergab gar nichts.
        Matcher.Hit a = apfel.get();
        double s = ctx.width() / 1159.0;
        int x = a.x() + a.width() - (int) (7 * s), y = a.y() - (int) (3 * s);
        int w = (int) (162 * s), h = a.height() + (int) (6 * s);
        String text = ArenaLeser.liesText(roh, x, y, w, h);
        OptionalLong n = ArenaLeser.zahlAus(ziffernGlaetten(text));
        if (n.isEmpty() || n.getAsLong() < 1000) {
            String text2 = textVerstaerkt(roh, x, y, w, h, true);
            OptionalLong n2 = text2 == null ? OptionalLong.empty() : ArenaLeser.zahlAus(ziffernGlaetten(text2));
            if (n2.isPresent() && (n.isEmpty() || n2.getAsLong() > n.getAsLong())) n = n2;
            // Zum Nachsehen, was die Texterkennung wirklich liefert (30.09.: 1 007 884 wurde "0").
            Log.debug("Nahrung", "Nahrung roh gelesen: \"" + text + "\" / verstaerkt: \"" + text2 + "\"");
        }
        if (n.isPresent()) Log.debug("Nahrung", "Nahrung in der Burg: " + n.getAsLong());
        return n;
    }

    /**
     * In der Nahrungs-Leiste stehen nur Ziffern. Die Texterkennung macht aus einer 0 gern ein O
     * (30.09., 17:39: "1 079 548" kam als 79 548 an, 20:05: "1 007 884" als 0) -- Buchstaben, die
     * wie Ziffern aussehen, werden zu Ziffern.
     */
    static String ziffernGlaetten(String text) {
        if (text == null) return null;
        StringBuilder b = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case 'O', 'o', 'Q', 'D' -> b.append('0');
                case 'I', 'l', '|', 'i' -> b.append('1');
                case 'S', 's' -> b.append('5');
                case 'B' -> b.append('8');
                case 'Z', 'z' -> b.append('2');
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Soll die Nahrung nach dem Kisten-Oeffnen noch einmal gelesen werden? Ja, solange die Anzeige noch hochzaehlt: sie
     * zeigt noch den alten Wert (nicht hoeher als vorher) ODER einen Zwischenwert unter dem, was nach den Kisten
     * mindestens da sein muss.
     *
     * WARUM (Bugjagd 29.10.): von 13 Warnungen "reads X, after the chests it should be at least Y -- misread" (27./28.10.)
     * waren 8 Zwischenwerte beim Hochzaehlen (1 780 210 / 1 901 702 / 1 621 012 / 1 540 321 / 655 653 ... gegen 1 98x xxx):
     * nur "nicht hoeher als vorher" wurde nachgelesen, ein Zwischenwert ueber dem alten Stand nicht -- die Warnung kam, und es
     * galt der Mindestwert statt der echten Zahl (der kann unter dem Bedarf liegen, obwohl die Anzeige ihn erreicht hat).
     */
    static boolean nachlesenNoetig(long gelesen, long vorher, long mindestens) {
        if (vorher >= 0 && gelesen <= vorher) return true;
        return mindestens > 0 && gelesen < mindestens;
    }

    /**
     * Was nach den geoeffneten Kisten MINDESTENS im Lager sein muss: der Stand vorher plus der
     * Inhalt der Kisten, hoechstens die Lagergrenze. -1 = nichts geoeffnet oder Stand unbekannt.
     * Liest die Anzeige weniger, ist das ein Lesefehler -- nicht ein leeres Lager.
     */
    long mindestensNachKisten() {
        if (geoeffnet <= 0 || nahrungVorher < 0 || nahrungGeoeffnet <= 0) return -1;
        return Math.min(de.hustlebot.core.Bezahlung.lager(), nahrungVorher + nahrungGeoeffnet);
    }

    private boolean klicken(Mat screen, BotContext ctx, String vorlage, String grund) {
        if (!ctx.clickIfVisibleIfKnown(screen, vorlage, grund)) return warten(ctx, vorlage);
        klicks++;
        blind = 0;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    /** Das rote X oben rechts (Tasche oder Kistenfenster), mit der niedrigeren Schwelle. */
    private boolean xKlicken(Mat screen, BotContext ctx, String grund) {
        Optional<Matcher.Hit> x = ctx.matcher().find(screen, T_ROTES_X, X_SCHWELLE);
        if (x.isEmpty() || x.get().centerX() < ctx.width() / 2 || x.get().centerY() > ctx.height() / 2) {
            ctx.matcher().naechstesBildGruendlich();
            return false;
        }
        Matcher.Hit h = x.get();
        Log.action("Klick", Sprache.t(grund + "  [" + T_ROTES_X + " at " + h.centerX() + "," + h.centerY() + String.format(", match %.3f]", h.score()),
                grund + "  [" + T_ROTES_X + " bei " + h.centerX() + "," + h.centerY()
                + String.format(", Uebereinstimmung %.3f]", h.score())));
        ctx.input().click(h);
        klicks++;
        zuletztGeklickt = System.currentTimeMillis();
        ctx.sleepUntilChanged(1200);
        return true;
    }

    private boolean warten(BotContext ctx, String worauf) {
        if (++blind >= MAX_BLIND) {
            beenden(Sprache.t("way not found (waited for: " + worauf + ")",
                    "Weg nicht gefunden (wartete auf: " + worauf + ")"));
            return false;
        }
        // Die Kisten-Animation laeuft lange -- antippen, wie es der Kisten-Gang der Tasche
        // tut (30.09., 15:01: 24 s auf den "Close"-Knopf gewartet, dann aufgegeben).
        if (T_SCHLIESSEN.equals(worauf) && blind % 5 == 4) {
            ctx.input().click((int) (ctx.width() * 0.24), (int) (ctx.height() * 0.60));
        }
        ctx.sleep(300);
        return true;
    }
}
