package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clan-Krieg: unvollstaendigen Trupp auffuellen (Autor 22.10. abends, zehn Bilder "Eye of the Enchantress"):
 *
 *   Deploy squad -> Previous Squad -> Deploy -> Warnung "Are you sure you want to set out with an incomplete squad?"
 *   -> NO -> obere Reihe nach links wischen (die freien Plaetze "Vacant" stehen rechts, Zaehler oben "6/8")
 *   -> untere Reihe nach links wischen, bis Einheiten kommen, die oben noch nicht stehen (meist "ersatz")
 *   -> die staerksten davon nach oben in die freien Plaetze ziehen, bis der Zaehler voll ist (8/8, 7/7, 6/6, 4/4)
 *   -> Deploy. Titanen genauso.
 *
 * Die untere Liste ist nach Kraft sortiert (staerkste links). Welche Karten schon oben stehen, verraet nur der Name:
 * die Namen der oberen Reihe werden an beiden Enden gelesen, jede ganz sichtbare untere Karte einzeln (Name und Kraft).
 * Geht etwas nicht (Namen unlesbar, kein freier Platz, keine passende Karte), gibt das Auffuellen auf; der Bot bestaetigt
 * dann wie bisher und antwortet auf die zweite Warnung mit Yes -- lieber unvollstaendig als gar nicht aufgestellt.
 *
 * Server 01.11. (Autor: "am Anfang waren 6 Einheiten drin, jetzt nur noch 4"), an den Fotos 02:24 und 08:24 nachvollzogen:
 *   - Der Zaehler las sich mit der Windows-Texterkennung als "618": ohne Zaehler merkte der Bot nichts von seinen Fehlern.
 *   - Das Wischen oben begann neben einer Einheit und warf einmal Bogen 1 aus dem Trupp (5/8 -> 4/8).
 *   - Die halb abgeschnittene Karte rechts las sich als "Magie" (449) statt "Magier 3" und galt als nicht im Trupp.
 *   - Der schnelle Zug legte die Karte auf dem Nachbarplatz ab: "Magier 3" warf "Magier 1" hinaus.
 *   - Unten begann jedes Wischen bei x 904; nach dem ersten lag dort die Karte "ersatz", die Reihe rollte nicht weiter.
 * Darum jetzt: Nach JEDEM Zug zaehlt der Zaehler. Waechst er nach einer Karte nicht oder schrumpft er nach dem Wischen,
 * stellt "Previous Squad" den gespeicherten Trupp wieder her, die Karte bleibt fuer diesen Turm tabu. Ohne lesbaren
 * Zaehler wird nichts gezogen. So kann der Bot nie weniger aufstellen als den gespeicherten Trupp.
 *
 * Lagen aus Autors Bildern (Fenster 1159 x 770 ohne Titelleiste, an seinem Bild nachgemessen): obere Reihe Bild bei
 * y 234, Name bei y 322 ("Vacant" bei 296, der Platz ist ohnehin am Bild erkannt), Plaetze
 * alle 128 Punkte (am Anfang ab x 340, am Ende bis x 928); untere Karten alle 193 Punkte: Kraft-Symbol oben links (y 424), Kartenmitte 44 Punkte
 * rechts davon, Bild bei y 494, Name bei y 596.
 */
final class TruppAuffuellen {

    static final String T_NEIN = "cw_nein";
    static final String T_VACANT = "cw_vacant";
    static final String T_HELM = "cw_helm";
    static final String T_KARTENKRAFT = "cw_kartenkraft";

    private static final double B = 1159.0, H = 770.0;
    private static final int OBEN_Y = 234, OBEN_NAME_Y = 322, UNTEN_Y = 494, UNTEN_NAME_Y = 596;
    private static final int[] OBEN_START = {340, 467, 594, 721, 848};
    private static final int[] OBEN_ENDE = {293, 420, 547, 674, 801, 928};
    private static final int KARTE_DX = 44, NAME_BREITE = 118, NAME_HOEHE = 26;
    /** Oben: Luecke zwischen zwei Plaetzen (Anfangslage) und der freie Streifen unter den Namen -- dort liegt keine Einheit. */
    private static final int OBEN_LUECKE_X = 912, OBEN_RAND_Y = 338, OBEN_WISCH_WEG = 603;
    /** Unten: halbe Karte, sichtbarer Bereich, Trennlinie rechts neben einer Karte (vom Kraft-Symbol aus gemessen). */
    private static final int KARTE_HALB = 96, RAND_LINKS = 120, RAND_RECHTS = 1030, TRENNLINIE_DX = 144, TRENNLINIE_MAX_X = 1012, UNTEN_WISCH_WEG = 556;
    private static final int OBEN_LETZTER_PLATZ = 928;
    /**
     * Kraft-Symbol der unteren Karten. An den Server-Fotos 01.11. passen echte Karten nur zu 0,67-0,96: mit der alten Schwelle
     * 0,80 blieb "ersatz" (0,71) unsichtbar ("keine Einheit mehr"). Ab 0,50 gibt es dort keinen falschen Treffer, und alle liegen bei y 424.
     */
    private static final double KARTEN_SCHWELLE = 0.60;
    private static final int KRAFT_Y = 424;
    /** Der Zaehler steht links neben dem roten X: der schmale Ausschnitt liest ihn, der breite las "6/8" als "618". */
    private static final int[] ZAEHLER_BREITEN = {50, 80};
    static final int MAX_SCHRITTE = 40, MAX_UNTEN_WISCHEN = 10, MAX_OBEN_WISCHEN = 3, MAX_RUECKGAENGE = 2, MAX_OHNE_ZAEHLER = 4, UNTEN_ENDE_NACH = 3;
    static final String T_PREVIOUS = "cw_previous_squad";

    private boolean aktiv, versucht, startGelesen, endeGelesen, restFertig;
    private int schritte, untenGewischt, obenGewischt, gezogen, ohneZaehler, rueckgaenge, untenNichtsNeu;
    /** Zaehler vor dem letzten Zug (null = kein Zug offen) und die Kraft der gezogenen Karte (null = nur gewischt). */
    private int[] zaehlerVorZug;
    private String zugKarte;
    private final Map<String, Integer> rest = new HashMap<>();
    private final Set<String> gesehen = new HashSet<>();
    /** Karten, deren Zug den Zaehler nicht wachsen liess: in diesem Turm nicht noch einmal. */
    private final Set<String> tabu = new HashSet<>();
    private final Map<String, Integer> start = new HashMap<>(), ende = new HashMap<>();

    /** Neuer Turm: alles von vorn (auch "schon versucht"). */
    void neuerTurm() {
        aktiv = false;
        versucht = false;
        zuruecksetzen();
    }

    /** Wurde in diesem Turm schon aufgefuellt (dann bei der Warnung Yes)? */
    boolean versucht() {
        return versucht;
    }

    boolean aktiv() {
        return aktiv;
    }

    /** Nach "No" auf die Warnung: ab jetzt auffuellen. */
    void starten() {
        versucht = true;
        aktiv = true;
        zuruecksetzen();
    }

    private void zuruecksetzen() {
        schritte = rueckgaenge = 0;
        tabu.clear();
        neuerVersuch();
    }

    /** Nach "Previous Squad": alles Gelesene neu, gemerkt bleiben nur die tabu-Karten und die Zahl der Schritte. */
    private void neuerVersuch() {
        startGelesen = endeGelesen = restFertig = false;
        untenGewischt = obenGewischt = gezogen = ohneZaehler = untenNichtsNeu = 0;
        zaehlerVorZug = null;
        zugKarte = null;
        rest.clear();
        gesehen.clear();
        start.clear();
        ende.clear();
    }

    /** Ein Schritt im Truppenfenster. null = fertig (voll oder aufgegeben) -- dann bestaetigen. */
    GameMode.Outcome schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return null;
        BufferedImage bild = ctx.frame();
        int[] z = zaehlerLesen(screen, ctx, bild);
        if (z == null) {
            // Ohne Zaehler wird nichts angefasst; steht noch ein Zug aus, zaehlt er als misslungen.
            if (++ohneZaehler <= MAX_OHNE_ZAEHLER) {
                ctx.sleepUntilChanged(700);
                return GameMode.Outcome.ACTED;
            }
            if (zaehlerVorZug != null) {
                rueckgaenge = MAX_RUECKGAENGE;
                return rueckgaengig(screen, ctx, Sprache.t("squad counter not readable after a move", "Zaehler nach einem Zug nicht lesbar"));
            }
            return aufgeben(ctx, Sprache.t("squad counter not readable", "Zaehler nicht lesbar"));
        }
        ohneZaehler = 0;
        if (zaehlerVorZug != null) {
            int vorher = zaehlerVorZug[0];
            String karte = zugKarte;
            zaehlerVorZug = null;
            zugKarte = null;
            if (zugMisslungen(vorher, z[0], karte != null)) {
                if (karte != null) tabu.add(karte);
                return rueckgaengig(screen, ctx, Sprache.t("counter " + vorher + " -> " + z[0] + "/" + z[1], "Zaehler " + vorher + " -> " + z[0] + "/" + z[1]));
            }
        }
        if (z[1] > 0 && z[0] >= z[1]) {
            aktiv = false;
            Log.info("Clan-Krieg", Sprache.t("Squad is full now (" + z[0] + "/" + z[1] + ", " + gezogen + " unit(s) added) -- Deploy.",
                    "Trupp ist jetzt voll (" + z[0] + "/" + z[1] + ", " + gezogen + " Einheit(en) dazu) -- Deploy."));
            ClanWarMode.testFoto(ctx, "voll");
            return null;
        }
        if (++schritte > MAX_SCHRITTE) return aufgeben(ctx, Sprache.t("too many steps", "zu viele Schritte"));
        int w = ctx.width(), h = ctx.height();

        // 1. Namen oben lesen (Anfang).
        if (!startGelesen) {
            startGelesen = true;
            namenLesen(bild, OBEN_START, start, w, h);
            ClanWarMode.testFoto(ctx, "nein-gedrueckt");
        }

        // 2. Freier Platz oben? Ist keiner ganz zu sehen, die obere Reihe ans Ende wischen -- nie auf einer Einheit anfangen.
        if (!ctx.matcher().has(T_VACANT)) return aufgeben(ctx, Sprache.t("picture " + T_VACANT + " missing", "Bild " + T_VACANT + " fehlt"));
        List<Matcher.Hit> frei = new ArrayList<>(ctx.matcher().findAll(screen, T_VACANT, 0.78, 4));
        if (frei.isEmpty()) {
            if (obenGewischt >= MAX_OBEN_WISCHEN) {
                return aufgeben(ctx, Sprache.t("no free slot visible although " + z[0] + "/" + z[1], "kein freier Platz zu sehen, obwohl " + z[0] + "/" + z[1]));
            }
            int y = (int) Math.round((obenGewischt % 2 == 0 ? OBEN_RAND_Y : OBEN_Y) / H * h);
            int x = (int) Math.round(OBEN_LUECKE_X / B * w);
            obenGewischt++;
            zaehlerVorZug = z;
            zugKarte = null;
            ctx.input().dragLangsam(x, y, x - (int) Math.round(OBEN_WISCH_WEG / B * w), y);
            Log.info("Clan-Krieg", Sprache.t("Incomplete squad " + z[0] + "/" + z[1] + ": upper row swiped to the end (free slots are on the right).",
                    "Unvollstaendiger Trupp " + z[0] + "/" + z[1] + ": obere Reihe ans Ende gewischt (die freien Plaetze stehen rechts)."));
            ctx.sleepUntilChanged(900);
            return GameMode.Outcome.ACTED;
        }
        frei.sort(Comparator.comparingInt(Matcher.Hit::centerX));
        Matcher.Hit ziel = frei.get(0);
        if (!endeGelesen && reiheAmEnde(frei.get(frei.size() - 1).centerX(), w)) {
            endeGelesen = true;
            namenLesen(bild, OBEN_ENDE, ende, w, h);
            ClanWarMode.testFoto(ctx, "oben-ende");
        }
        if (!restFertig) {
            restFertig = true;
            for (String n : start.keySet()) rest.merge(n, start.get(n), Math::max);
            for (String n : ende.keySet()) rest.merge(n, ende.get(n), Math::max);
            rest.remove("vacant");
            Log.info("Clan-Krieg", Sprache.t("Units in the squad (read): " + rest, "Einheiten im Trupp (gelesen): " + rest));
            if (rest.values().stream().mapToInt(Integer::intValue).sum() < Math.min(2, z[0])) {
                return aufgeben(ctx, Sprache.t("names in the squad not readable", "Namen im Trupp nicht lesbar"));
            }
        }

        // 3. Untere Karten von links (staerkste) nach rechts: die erste ganz sichtbare, die oben noch nicht steht.
        if (!ctx.matcher().has(T_KARTENKRAFT)) return aufgeben(ctx, Sprache.t("picture " + T_KARTENKRAFT + " missing", "Bild " + T_KARTENKRAFT + " fehlt"));
        List<Matcher.Hit> karten = new ArrayList<>(ctx.matcher().findAll(screen, T_KARTENKRAFT, KARTEN_SCHWELLE, 9));
        karten.removeIf(k -> Math.abs(k.centerY() * H / h - KRAFT_Y) > 12);
        karten.sort(Comparator.comparingInt(Matcher.Hit::centerX));
        Map<String, Integer> gleicheKraft = new HashMap<>();
        boolean neueKarte = false;
        for (Matcher.Hit k : karten) {
            int cx = k.centerX() + (int) Math.round(KARTE_DX / B * w);
            if (!ganzZuSehen(cx, w)) continue;   // Name und Kraft waeren abgeschnitten ("Magie" 449 statt "Magier 3" 4495507)
            String name = lies(bild, cx, (int) Math.round(UNTEN_NAME_Y / H * h), w, h);
            String kraft = ziffern(lies(bild, cx + (int) Math.round(10 / B * w), k.centerY(), w, h));
            String schluessel = kraft.length() >= 3 ? kraft : zeichen(name) + "@" + cx;
            // Zwei gleich starke Einheiten (zweimal "ersatz") sind zwei Karten.
            int nr = gleicheKraft.merge(schluessel, 1, Integer::sum);
            if (schonGesehen(gesehen, schluessel, nr)) continue;
            gesehen.add(schluessel + "#" + nr);
            neueKarte = true;
            String n = zeichen(name);
            if (n.isEmpty()) continue;   // unlesbar -- lieber nicht ziehen
            Integer oben = rest.get(n);
            if (oben != null && oben > 0) {
                rest.put(n, oben - 1);   // steht schon oben
                continue;
            }
            if (tabu.contains(schluessel)) continue;
            Log.info("Clan-Krieg", Sprache.t("Incomplete squad " + z[0] + "/" + z[1] + ": \"" + name + "\" (" + kraft + ") is not in the squad -- dragged up into the free slot.",
                    "Unvollstaendiger Trupp " + z[0] + "/" + z[1] + ": \"" + name + "\" (" + kraft + ") steht nicht im Trupp -- nach oben in den freien Platz gezogen."));
            zaehlerVorZug = z;
            zugKarte = schluessel;
            ctx.input().dragHoch(cx, (int) Math.round(UNTEN_Y / H * h), ziel.centerX(), ziel.centerY());
            gezogen++;
            ctx.sleepUntilChanged(1200);
            ClanWarMode.testFoto(ctx, "gezogen-" + gezogen);
            return GameMode.Outcome.ACTED;
        }
        // 4. Keine passende Karte zu sehen: untere Reihe weiterwischen, bis nichts Neues mehr kommt (dahinter stehen die "ersatz").
        untenNichtsNeu = neueKarte ? 0 : untenNichtsNeu + 1;
        if (untenGewischt < MAX_UNTEN_WISCHEN && untenNichtsNeu < UNTEN_ENDE_NACH) {
            untenGewischt++;
            int y = (int) Math.round(UNTEN_Y / H * h);
            int x = untenWischX(karten.stream().map(Matcher.Hit::centerX).toList(), w);
            zaehlerVorZug = z;
            zugKarte = null;
            ctx.input().dragLangsam(x, y, x - (int) Math.round(UNTEN_WISCH_WEG / B * w), y);
            Log.info("Clan-Krieg", Sprache.t("Incomplete squad: lower row swiped (" + untenGewischt + "), looking for units not in the squad.",
                    "Unvollstaendiger Trupp: untere Reihe gewischt (" + untenGewischt + "), suche Einheiten, die noch nicht oben stehen."));
            ctx.sleepUntilChanged(900);
            return GameMode.Outcome.ACTED;
        }
        return aufgeben(ctx, Sprache.t("no unit left that is not in the squad, " + z[0] + "/" + z[1], "keine Einheit mehr, die nicht schon oben steht, " + z[0] + "/" + z[1]));
    }

    /** Ein Zug ging schief: "Previous Squad" stellt den gespeicherten Trupp wieder her. Nach zu vielen Versuchen bleibt es dabei. */
    private GameMode.Outcome rueckgaengig(Mat screen, BotContext ctx, String grund) {
        ClanWarMode.testFoto(ctx, "rueckgaengig");
        var knopf = ctx.matcher().has(T_PREVIOUS) ? ctx.matcher().find(screen, T_PREVIOUS) : java.util.Optional.<Matcher.Hit>empty();
        if (knopf.isEmpty()) return aufgeben(ctx, Sprache.t("move failed (" + grund + "), Previous Squad not found", "Zug misslungen (" + grund + "), Previous Squad nicht gefunden"));
        Log.warn("Clan-Krieg", Sprache.t("Filling the squad: move failed (" + grund + ") -- Previous Squad restores the saved squad.",
                "Trupp auffuellen: Zug misslungen (" + grund + ") -- Previous Squad stellt den gespeicherten Trupp wieder her."));
        ctx.klick(knopf.get(), Sprache.t("Filling the squad failed -- back to the saved squad (Previous Squad)",
                "Auffuellen misslungen -- zurueck zum gespeicherten Trupp (Previous Squad)"));
        rueckgaenge++;
        neuerVersuch();
        ctx.sleepUntilChanged(1500);
        if (rueckgaenge > MAX_RUECKGAENGE) return aufgeben(ctx, Sprache.t("too many failed moves", "zu viele misslungene Zuege"));
        return GameMode.Outcome.ACTED;
    }

    /** Nach einer Karte muss der Zaehler wachsen, nach dem Wischen darf er nicht schrumpfen. */
    static boolean zugMisslungen(int vorher, int nachher, boolean karteGezogen) {
        return karteGezogen ? nachher <= vorher : nachher < vorher;
    }

    /** Karte ganz zu sehen? Bei der abgeschnittenen sind Name und Kraft Bruchstuecke. */
    static boolean ganzZuSehen(int kartenMitteX, int w) {
        double x = kartenMitteX * B / w;
        return x - KARTE_HALB >= RAND_LINKS && x + KARTE_HALB <= RAND_RECHTS;
    }

    /** Steht ein freier Platz ganz rechts an der letzten Stelle, ist die obere Reihe am Ende (dann stimmen die Stellen aus OBEN_ENDE). */
    static boolean reiheAmEnde(int freierPlatzX, int w) {
        return Math.abs(freierPlatzX * B / w - OBEN_LETZTER_PLATZ) <= 20;
    }

    /** Hier beginnt das Wischen unten: auf der Trennlinie zwischen zwei Karten. Auf einer Karte rollt die Reihe nicht. */
    static int untenWischX(List<Integer> kraftSymbolX, int w) {
        int best = -1;
        for (int k : kraftSymbolX) {
            int sep = (int) Math.round(k * B / w) + TRENNLINIE_DX;
            if (sep <= TRENNLINIE_MAX_X && sep > best) best = sep;
        }
        return (int) Math.round((best > 0 ? best : 904) / B * w);
    }

    /** Dieselbe Karte schon gelesen? Die Texterkennung vertauscht oder verliert hoechstens eine Ziffer. */
    static boolean schonGesehen(Set<String> gesehen, String schluessel, int nr) {
        if (gesehen.contains(schluessel + "#" + nr)) return true;
        for (String g : gesehen) {
            int raute = g.lastIndexOf('#');
            if (g.endsWith("#" + nr) && eineZifferAnders(g.substring(0, raute), schluessel)) return true;
        }
        return false;
    }

    /** Hoechstens eine Ziffer anders, zu viel oder zu wenig -- nur bei Zahlen ab 5 Stellen. */
    static boolean eineZifferAnders(String a, String b) {
        if (a.length() < 5 || b.length() < 5 || Math.abs(a.length() - b.length()) > 1) return false;
        if (!a.chars().allMatch(Character::isDigit) || !b.chars().allMatch(Character::isDigit)) return false;
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        int ka = a.length(), kb = b.length();
        while (ka > i && kb > i && a.charAt(ka - 1) == b.charAt(kb - 1)) {
            ka--;
            kb--;
        }
        return ka - i <= 1 && kb - i <= 1;
    }

    private GameMode.Outcome aufgeben(BotContext ctx, String grund) {
        aktiv = false;
        Log.warn("Clan-Krieg", Sprache.t("Filling the squad stopped (" + grund + ", " + gezogen + " added) -- deploying as it is; "
                        + "on the warning the bot now answers Yes.",
                "Auffuellen abgebrochen (" + grund + ", " + gezogen + " dazu) -- wird so aufgestellt; auf die Warnung "
                        + "antwortet der Bot jetzt mit Yes."));
        ClanWarMode.testFoto(ctx, "aufgegeben");
        return null;
    }

    /** "6/8" rechts neben dem Helm-Symbol oben rechts. null = nicht lesbar. */
    static int[] zaehlerLesen(Mat screen, BotContext ctx, BufferedImage bild) {
        if (bild == null || !ctx.matcher().has(T_HELM)) return null;
        var helm = ctx.matcher().find(screen, T_HELM);
        if (helm.isEmpty()) return null;
        Matcher.Hit hm = helm.get();
        int x = hm.x() + hm.width(), y = hm.y(), bh = hm.height();
        for (int breite : ZAEHLER_BREITEN) {
            int bw = (int) Math.round(breite / B * ctx.width());
            if (x + bw > bild.getWidth() || y + bh > bild.getHeight() || y < 0) continue;
            try {
                int[] z = zaehlerAusText(String.join(" ", de.hustlebot.core.Texterkennung.lies(dreifach(bild.getSubimage(x, y, bw, bh)))));
                if (z != null) return z;
            } catch (Exception e) {
                // der naechste Ausschnitt
            }
        }
        return null;
    }

    /** "6/8", "6 / 8", "6l8" -> {6, 8}. Auch "618": die Windows-Texterkennung las den Schraegstrich als 1 (Server 01.11.). */
    static int[] zaehlerAusText(String t) {
        if (t == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})\\s*[/lI|]\\s*(\\d{1,2})").matcher(t);
        if (!m.find()) {
            m = java.util.regex.Pattern.compile("^\\s*(\\d)1(\\d)\\s*$").matcher(t);
            if (!m.find()) return null;
        }
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
        if (b < 1 || b > 8 || a > b) return null;
        return new int[]{a, b};
    }

    private static void namenLesen(BufferedImage bild, int[] stellen, Map<String, Integer> ziel, int w, int h) {
        if (bild == null) return;
        for (int x : stellen) {
            String n = zeichen(lies(bild, (int) Math.round(x / B * w), (int) Math.round(OBEN_NAME_Y / H * h), w, h));
            if (n.length() >= 2) ziel.merge(n, 1, Integer::sum);
        }
    }

    /** Text in einem Streifen NAME_BREITE x NAME_HOEHE um (cx, cy). */
    private static String lies(BufferedImage bild, int cx, int cy, int w, int h) {
        if (bild == null) return "";
        int bw = (int) Math.round(NAME_BREITE / B * w), bh = (int) Math.round(NAME_HOEHE / H * h);
        int x = Math.max(0, cx - bw / 2), y = Math.max(0, cy - bh / 2);
        if (x + bw > bild.getWidth() || y + bh > bild.getHeight()) return "";
        try {
            // Live 23.10. (21:43, Autor 6/8): in Originalgroesse las die Texterkennung keinen einzigen Namen ("{}") -- dreifach
            // vergroessert alle ("Magier 2", "Magier 3", "Bogen 1", "Tank", am Foto nachgeprueft).
            return String.join(" ", de.hustlebot.core.Texterkennung.lies(dreifach(bild.getSubimage(x, y, bw, bh)))).strip();
        } catch (Exception e) {
            return "";
        }
    }

    /** Dreifach vergroessert (bikubisch) -- die kleine Schrift im Trupp-Fenster liest die Texterkennung sonst nicht. */
    private static BufferedImage dreifach(BufferedImage teil) {
        BufferedImage gross = new BufferedImage(teil.getWidth() * 3, teil.getHeight() * 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = gross.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(teil, 0, 0, gross.getWidth(), gross.getHeight(), null);
        g.dispose();
        return gross;
    }

    static String zeichen(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.toString();
    }

    private static String ziffern(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }
}
