package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wechselt das Helden-Set (Relikt-Set) ueber den Kleiderbuegel -- fuer den Clan-Krieg (Ben 17.10., siehe HeldenSet).
 *
 * Weg (Fotoserie Autor 17.10., Spiel 1159 x 770): Burg -> oben links Profil (52,48) -> Reiter "Battles" (955,52) ->
 * Held (398,215) -> Kleiderbuegel (hs_kleiderbuegel, 514,150) -> Fenster "Sets" (hs_sets_titel): die Namen stehen
 * untereinander bei 340, 404, 467 ... (Abstand 63,5), gelesen mit der Windows-Texterkennung -> Set antippen ->
 * "Apply" (hs_apply, 575,566) -> mit dem roten X zurueck bis in die Burg.
 *
 * Klappt etwas nicht, wird nicht endlos probiert: nach ein paar Versuchen gilt der Schritt als gescheitert, der Weg
 * geht zurueck in die Burg, und der Clan-Krieg wird trotzdem aufgestellt.
 */
final class HeldenSetWechsel {

    private static final String WER = "Helden-Set";

    private static final double[] P_BURG = {52 / 1159.0, 48 / 770.0};
    private static final double[] P_BATTLES = {955 / 1159.0, 52 / 770.0};
    private static final double[] P_HELD = {398 / 1159.0, 215 / 770.0};
    private static final double[] P_BUEGEL = {514 / 1159.0, 150 / 770.0};
    private static final double[] P_X = {1113 / 1159.0, 35 / 770.0};
    private static final double[] P_REITER = {340 / 1159.0, 33 / 770.0, 750 / 1159.0, 38 / 770.0};
    private static final double[] P_HERO = {600 / 1159.0, 92 / 770.0, 230 / 1159.0, 34 / 770.0};
    /** Die Namenszeilen im Set-Fenster: links, Breite, erste Mitte, Abstand, Hoehe (Pixel bei 1159 x 770). */
    private static final int ZEILE_X = 372, ZEILE_B = 222, ZEILE_Y0 = 340, ZEILE_H = 44;
    private static final double ZEILE_ABSTAND = 63.5;
    private static final int ZEILEN_MAX = 6;

    static final String T_BUEGEL = "hs_kleiderbuegel";
    static final String T_SETS = "hs_sets_titel";
    static final String T_APPLY = "hs_apply";
    private static final String T_HELD_FENSTER = TeamScan.T_HELD_BATTLE;   // Reiter "Battle" im Heldenfenster
    private static final String T_X = TeamScan.T_X;
    private static final String T_BURG_KNOPF = TeamScan.T_BURG_KNOPF;

    private enum Schritt { HEIM, PROFIL, BATTLES, HELD, BUEGEL, WAEHLEN, APPLY, ZU, FERTIG }

    // Pruefer 29.10.: Diese Grenzen galten fest in Echtzeit. Auf dem langsamen Server (Rechnertempo bis 5) brach ein
    // Schritt nach 25 s ab, es wurde schon nach 1,5 s erneut an dieselbe Stelle getippt, und kam "Apply" nach mehr als
    // 4 s, galt das Set als "wohl schon aktiv". Jetzt alle x Rechnertempo (rechner-tempo.txt).
    /** Laengste Dauer eines Schritts, bevor er als gescheitert gilt. */
    static final long SCHRITT_GRENZE_MS = 25_000;
    /** Mindestabstand, bevor an derselben Stelle noch einmal getippt wird (Profil, Battles, Held, Kleiderbuegel). */
    static final long TIPP_ABSTAND_MS = 1500;
    /** Mindestabstand fuer das rote X auf dem Rueckweg. */
    static final long ZU_TIPP_ABSTAND_MS = 1200;
    /** So lange wird nach dem Antippen des Sets auf "Apply" gewartet. */
    static final long APPLY_WARTEN_MS = 4000;

    /** Eine der Grenzen oben auf diesem Rechner (x Rechnertempo). */
    static long grenze(long ms) {
        return de.hustlebot.core.Rechnertempo.mal(ms);
    }

    private final String name;
    /** Doppelte Absicherung (Autor 17.10.): das Set muss an diesem Platz stehen (1 = oben). */
    private final int platz;
    private Schritt schritt = Schritt.HEIM;
    private int versuche = 0;
    /** 0 = noch nicht angefangen: die Uhr laeuft erst ab dem ersten Schritt (Live-Test 17.10.: nach dem Krieg sofort "zu lange"). */
    private long schrittSeit = 0;
    private long letzterTipp = 0;
    private boolean erfolg = false;
    private String grund = "";

    HeldenSetWechsel(String name, int platz) {
        this.name = name;
        this.platz = platz;
        Log.info(WER, Sprache.t("Switching the hero to the set \"" + name + "\" (slot " + platz + ").",
                "Held bekommt das Set \"" + name + "\" (Platz " + platz + ")."));
    }

    boolean fertig() {
        return schritt == Schritt.FERTIG;
    }

    boolean erfolg() {
        return erfolg;
    }

    String grund() {
        return grund;
    }

    /** Ein Schritt. Gibt ACTED oder WAITING zurueck, solange es etwas zu tun gibt. */
    GameMode.Outcome schritt(Mat screen, BotContext ctx) {
        if (schrittSeit == 0) schrittSeit = System.currentTimeMillis();
        long seit = System.currentTimeMillis() - schrittSeit;
        if (seit > grenze(SCHRITT_GRENZE_MS) && schritt != Schritt.ZU && schritt != Schritt.FERTIG) {
            return scheitern(ctx, Sprache.t("step " + schritt + " took too long", "Schritt " + schritt + " dauerte zu lange"));
        }
        // Server 30.10., 08:22: Das Werbefenster "Running out of ..." lag ueber der Burg. Die Burg-Knoepfe bleiben dabei sichtbar,
        // also "in der Burg" -- aber "Profil" ging dreimal ins Leere, und der Clan-Krieg lief ohne das Set "war".
        if ((schritt == Schritt.HEIM || schritt == Schritt.PROFIL)
                && (Stoerfenster.werbefenster(screen, ctx, WER) || Stoerfenster.ereignisBelohnung(screen, ctx, WER))) {
            return GameMode.Outcome.ACTED;
        }
        switch (schritt) {
            case HEIM -> {
                if (inBurg(screen, ctx)) return weiter(Schritt.PROFIL);
                if (++versuche > 10) return scheitern(ctx, Sprache.t("castle not reached", "Burg nicht erreicht"));
                // 22.10. nachts (Test nach dem Leuchtturm): "worldmap_home" passte nur zu 0,73 (anderer Kartenausschnitt
                // dahinter) -- 10 Versuche ohne Klick, "castle not reached". Auf der Weltkarte jetzt wie die anderen Modi:
                // Weltkarte.heim (Vorlage, sonst die feste Stelle unten links).
                if (Weltkarte.erkannt(screen, ctx)) {
                    Weltkarte.heim(screen, ctx, WER);
                    return GameMode.Outcome.ACTED;
                }
                // Ben 24./25.10. (#F6SY, #8YHV): offene Lupen-Auswahl im Hammer-Event -- zuerst abbrechen, sonst greift kein Knopf.
                if (Stoerfenster.lupenAuswahlAbbrechen(screen, ctx, WER)) return GameMode.Outcome.ACTED;
                // Live-Test 17.10., 15:07: nach dem Clan-Krieg stand das Spiel auf der Kriegskarte bzw. der Uebersicht --
                // keiner der drei Knoepfe passte, nach 25 s "Schritt HEIM dauerte zu lange", das Set blieb auf "war".
                // Jetzt auch "Back" der Kriegskarte (cw_back), das X eines Turmfensters (cw_close_x) und das Kartenhaus-Home.
                // Autor 18.10.: nach dem Verlies stand das Spiel auf der Weltkarte -- "castle not reached", der Clan-Krieg
                // lief ohne das Set "war". Jetzt auch der Home-Knopf der Weltkarte.
                if (ctx.clickIfVisibleIfKnown(screen, T_BURG_KNOPF, WER + Sprache.t(": back to the castle", ": zurueck in die Burg"))
                        || Weltkarte.homeUntenLinksKlicken(screen, ctx, WER + Sprache.t(": world map -> Home", ": Weltkarte -> Home"))
                        // Autor 19.10.: aus dem Turnier-Fenster der Arena fand der Bot nicht in die Burg ("castle not reached", 6x).
                        || ctx.clickIfVisibleIfKnown(screen, "ar_home", WER + Sprache.t(": arena -> Home", ": Arena -> Home"))
                        || ctx.clickIfVisibleIfKnown(screen, "ar_close_x", WER + Sprache.t(": close the arena window", ": Arena-Fenster schliessen"))
                        || ctx.clickIfVisibleIfKnown(screen, "ar_home_result", WER + Sprache.t(": arena result -> Home", ": Arena-Ergebnis -> Home"))
                        // 21.10. (Test nach der Arena, 3 Versuche "castle not reached"; Ben bei jedem Krieg): nach dem
                        // Arena-Fenster fehlte der gruene Home-Knopf, der aus fast jedem Bildschirm in die Burg fuehrt.
                        || ctx.clickIfVisibleIfKnown(screen, "home_button", WER + Sprache.t(": green Home", ": gruener Home-Knopf"))
                        || ctx.clickIfVisibleIfKnown(screen, "cw_close_x", WER + Sprache.t(": close the tower window", ": Turmfenster schliessen"))
                        || ctx.clickIfVisibleIfKnown(screen, "cw_back", WER + Sprache.t(": leave the war map (Back)", ": raus aus der Kriegskarte (Back)"))
                        || ctx.clickIfVisibleIfKnown(screen, T_X, WER + Sprache.t(": close window", ": Fenster schliessen"))
                        || ctx.clickIfVisibleIfKnown(screen, "close_x", WER + Sprache.t(": close window", ": Fenster schliessen"))) {
                    ctx.sleepUntilChanged(1200);
                    return GameMode.Outcome.ACTED;
                }
                ctx.sleep(500);
                return GameMode.Outcome.WAITING;
            }
            case PROFIL -> {
                BufferedImage b = ctx.frame();
                if (b != null && text(b, P_REITER).matches(".*(battles|rewards).*")) return weiter(Schritt.BATTLES);
                return tippenOderWarten(ctx, P_BURG, Sprache.t("Profile (castle at the top left)", "Profil (Burg oben links)"));
            }
            case BATTLES -> {
                BufferedImage b = ctx.frame();
                if (b != null && text(b, P_HERO).contains("hero")) return weiter(Schritt.HELD);
                return tippenOderWarten(ctx, P_BATTLES, Sprache.t("Tab \"Battles\" in the profile", "Reiter \"Battles\" im Profil"));
            }
            case HELD -> {
                if (ctx.visibleIfKnown(screen, T_HELD_FENSTER) || ctx.visibleIfKnown(screen, T_BUEGEL)) return weiter(Schritt.BUEGEL);
                return tippenOderWarten(ctx, P_HELD, Sprache.t("Open the hero", "Helden oeffnen"));
            }
            case BUEGEL -> {
                if (ctx.visibleIfKnown(screen, T_SETS)) {
                    ctx.sleep(300);   // Fenster ganz aufgehen lassen, dann lesen
                    return weiter(Schritt.WAEHLEN);
                }
                if (System.currentTimeMillis() - letzterTipp < grenze(TIPP_ABSTAND_MS)) { ctx.sleep(150); return GameMode.Outcome.WAITING; }
                if (++versuche > 3) return scheitern(ctx, Sprache.t("the sets window did not open", "das Set-Fenster ging nicht auf"));
                letzterTipp = System.currentTimeMillis();
                if (ctx.clickIfVisibleIfKnown(screen, T_BUEGEL, WER + Sprache.t(": hanger (sets)", ": Kleiderbuegel (Sets)"))) {
                    ctx.sleepUntilChanged(1000);
                    return GameMode.Outcome.ACTED;
                }
                return tippen(ctx, P_BUEGEL, Sprache.t("Hanger (sets), fixed spot", "Kleiderbuegel (Sets), feste Stelle"));
            }
            case WAEHLEN -> {
                BufferedImage b = ctx.frame();
                if (b == null) { ctx.sleep(200); return GameMode.Outcome.WAITING; }
                List<String> gelesen = new ArrayList<>();
                int gefundenAuf = 0;
                double sx = b.getWidth() / 1159.0, sy = b.getHeight() / 770.0;
                for (int i = 0; i < ZEILEN_MAX; i++) {
                    int mitte = (int) Math.round(ZEILE_Y0 + i * ZEILE_ABSTAND);
                    String t = liesZeile(b, (int) (ZEILE_X * sx), (int) ((mitte - ZEILE_H / 2.0) * sy),
                            (int) (ZEILE_B * sx), (int) (ZEILE_H * sy));
                    String n = normal(t);
                    if (n.contains("unequip")) break;
                    gelesen.add((i + 1) + "=" + (t.isEmpty() ? "?" : t));
                    if (gefundenAuf == 0 && passt(n, normal(name))) gefundenAuf = i + 1;
                }
                // Autor 17.10.: "Wenn das War-Setup schon ausgewaehlt ist, einfach lassen." Das aktive Set hat einen
                // GRUENEN Knopf (die anderen tuerkis) -- dann nichts antippen, nur die Fenster schliessen.
                if (gefundenAuf == platz && istAktiv(b, platz)) {
                    Log.info(WER, Sprache.t("Set \"" + name + "\" (slot " + platz + ") is already active -- nothing to change.",
                            "Set \"" + name + "\" (Platz " + platz + ") ist schon aktiv -- nichts zu wechseln."));
                    erfolg = true;
                    return weiter(Schritt.ZU);
                }
                // Doppelte Absicherung: nur antippen, wenn der Name GENAU am eingestellten Platz steht.
                if (gefundenAuf == platz) {
                    int mitte = (int) Math.round(ZEILE_Y0 + (platz - 1) * ZEILE_ABSTAND);
                    int x = (int) ((ZEILE_X + ZEILE_B / 2.0) * sx), y = (int) (mitte * sy);
                    Log.action("Klick", WER + Sprache.t(": set \"" + name + "\" (slot " + platz + ", name and slot match)  [" + x + "," + y + "]",
                            ": Set \"" + name + "\" (Platz " + platz + ", Name und Platz passen)  [" + x + "," + y + "]"));
                    ctx.input().click(x, y, 4);
                    ctx.sleepUntilChanged(1000);
                    return weiter(Schritt.APPLY);
                }
                if (++versuche < 3) { ctx.sleep(500); return GameMode.Outcome.WAITING; }   // nochmal lesen
                String gelesenText = String.join(" | ", gelesen);
                return scheitern(ctx, gefundenAuf > 0
                        ? Sprache.t("\"" + name + "\" is in slot " + gefundenAuf + ", expected slot " + platz + " (read: " + gelesenText + ")",
                                "\"" + name + "\" steht auf Platz " + gefundenAuf + ", erwartet Platz " + platz + " (gelesen: " + gelesenText + ")")
                        : Sprache.t("set \"" + name + "\" not found (read: " + gelesenText + ")",
                                "Set \"" + name + "\" nicht gefunden (gelesen: " + gelesenText + ")"));
            }
            case APPLY -> {
                if (ctx.clickIfVisibleIfKnown(screen, T_APPLY, WER + ": Apply")) {
                    erfolg = true;
                    Log.info(WER, Sprache.t("Hero set \"" + name + "\" applied.", "Helden-Set \"" + name + "\" angelegt."));
                    ctx.sleepUntilChanged(1500);
                    return weiter(Schritt.ZU);
                }
                if (seit > grenze(APPLY_WARTEN_MS)) {
                    // Kein "Apply" -- vielleicht war das Set schon aktiv oder das Spiel fragt nicht nach.
                    // Pruefer 29.10.: nicht einfach Erfolg annehmen -- am gruenen Knopf nachsehen, ob das Set jetzt aktiv ist.
                    BufferedImage b = ctx.frame();
                    if (b != null && istAktiv(b, platz)) {
                        Log.info(WER, Sprache.t("No \"Apply\" after tapping \"" + name + "\" -- the set is active (green button).",
                                "Kein \"Apply\" nach \"" + name + "\" -- das Set ist aktiv (gruener Knopf)."));
                        erfolg = true;
                        return weiter(Schritt.ZU);
                    }
                    return scheitern(ctx, Sprache.t("no \"Apply\" after tapping the set, and it is not active",
                            "kein \"Apply\" nach dem Antippen, und das Set ist nicht aktiv"));
                }
                ctx.sleep(200);
                return GameMode.Outcome.WAITING;
            }
            case ZU -> {
                if (inBurg(screen, ctx)) {
                    schritt = Schritt.FERTIG;
                    return GameMode.Outcome.ACTED;
                }
                if (System.currentTimeMillis() - letzterTipp < grenze(ZU_TIPP_ABSTAND_MS)) { ctx.sleep(150); return GameMode.Outcome.WAITING; }
                if (++versuche > 8) {
                    Log.warn(WER, Sprache.t("Could not get back to the castle -- the clan war finds its way itself.",
                            "Nicht zurueck in die Burg gekommen -- der Clan-Krieg sucht sich den Weg selbst."));
                    schritt = Schritt.FERTIG;
                    return GameMode.Outcome.ACTED;
                }
                letzterTipp = System.currentTimeMillis();
                if (Weltkarte.erkannt(screen, ctx)) {
                    Weltkarte.heim(screen, ctx, WER);
                    return GameMode.Outcome.ACTED;
                }
                // Ben 24./25.10. (#F6SY, #8YHV): unter einer offenen Lupen-Auswahl wirkt das rote X nicht -- 8x getippt.
                if (Stoerfenster.lupenAuswahlAbbrechen(screen, ctx, WER)) return GameMode.Outcome.ACTED;
                if (ctx.clickIfVisibleIfKnown(screen, T_X, WER + Sprache.t(": close window", ": Fenster schliessen"))
                        || ctx.clickIfVisibleIfKnown(screen, "close_x", WER + Sprache.t(": close window", ": Fenster schliessen"))) {
                    ctx.sleepUntilChanged(1000);
                    return GameMode.Outcome.ACTED;
                }
                return tippen(ctx, P_X, Sprache.t("red X (fixed spot)", "rotes X (feste Stelle)"));
            }
            default -> {
                return GameMode.Outcome.WAITING;
            }
        }
    }

    // --- Hilfen ---------------------------------------------------------------------------------------------------

    private GameMode.Outcome weiter(Schritt neu) {
        schritt = neu;
        versuche = 0;
        letzterTipp = 0;
        schrittSeit = System.currentTimeMillis();
        return GameMode.Outcome.ACTED;
    }

    private GameMode.Outcome scheitern(BotContext ctx, String warum) {
        grund = warum;
        Log.warn(WER, Sprache.t("Hero set \"" + name + "\" NOT set: " + warum + ". Going on without it.",
                "Helden-Set \"" + name + "\" NICHT gesetzt: " + warum + ". Es geht ohne weiter."));
        schritt = Schritt.ZU;
        versuche = 0;
        schrittSeit = System.currentTimeMillis();
        return GameMode.Outcome.ACTED;
    }

    /** Antippen, dann 1,5 s warten, ob es gewirkt hat; nach 3 Versuchen scheitern. */
    private GameMode.Outcome tippenOderWarten(BotContext ctx, double[] p, String was) {
        if (System.currentTimeMillis() - letzterTipp < grenze(TIPP_ABSTAND_MS)) { ctx.sleep(200); return GameMode.Outcome.WAITING; }
        if (++versuche > 3) return scheitern(ctx, Sprache.t(was + " did not work", was + " hat nicht geklappt"));
        letzterTipp = System.currentTimeMillis();
        return tippen(ctx, p, was);
    }

    private GameMode.Outcome tippen(BotContext ctx, double[] p, String was) {
        int x = (int) Math.round(p[0] * ctx.width()), y = (int) Math.round(p[1] * ctx.height());
        Log.action("Klick", WER + ": " + was + "  [" + x + "," + y + "]");
        ctx.input().click(x, y, 3);
        ctx.sleepUntilChanged(1000);
        return GameMode.Outcome.ACTED;
    }

    /**
     * Ist das Set auf diesem Platz das aktive? Foto 17.10.: der Knopf des aktiven Sets ist kraeftig gruen
     * (etwa R 100, G 200, B 20), die anderen hell tuerkis (etwa R 120, G 220, B 230). Gemessen wird ein kleines Feld am
     * linken Rand des Knopfes, wo keine Schrift steht.
     */
    static boolean istAktiv(BufferedImage b, int platz) {
        double sx = b.getWidth() / 1159.0, sy = b.getHeight() / 770.0;
        int mitte = (int) Math.round(ZEILE_Y0 + (platz - 1) * ZEILE_ABSTAND);
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = (int) ((mitte - 6) * sy); y <= (int) ((mitte + 6) * sy); y++) {
            for (int x = (int) ((ZEILE_X + 6) * sx); x <= (int) ((ZEILE_X + 26) * sx); x++) {
                if (x < 0 || y < 0 || x >= b.getWidth() || y >= b.getHeight()) continue;
                int rgb = b.getRGB(x, y);
                r += (rgb >> 16) & 0xff;
                g += (rgb >> 8) & 0xff;
                bl += rgb & 0xff;
                n++;
            }
        }
        if (n == 0) return false;
        r /= n; g /= n; bl /= n;
        return g > 140 && bl < 110 && g > r + 40;   // gruen: viel Gruen, wenig Blau
    }

    /**
     * Eine Namenszeile lesen. Test 17.10. an Autors Foto: "war" und "Dailies" (dunkle Schrift auf hellem Tuerkis) las
     * die Erkennung sofort, "normal" (weisse Schrift auf GRUENEM Knopf = das aktive Set) gar nicht. Bleibt die Zeile
     * leer, wird sie ein zweites Mal gelesen: als Graubild mit vertauschtem Hell und Dunkel.
     */
    static String liesZeile(BufferedImage b, int x, int y, int breite, int hoehe) {
        String t = TeamScan.liesDoppelt(b, x, y, breite, hoehe).strip();
        if (!t.isEmpty()) return t;
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(b.getWidth(), x + breite), y2 = Math.min(b.getHeight(), y + hoehe);
        if (x2 - x1 < 4 || y2 - y1 < 4) return "";
        BufferedImage umgekehrt = new BufferedImage(x2 - x1, y2 - y1, BufferedImage.TYPE_INT_RGB);
        for (int yy = y1; yy < y2; yy++) {
            for (int xx = x1; xx < x2; xx++) {
                int rgb = b.getRGB(xx, yy);
                int grau = (int) (0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff));
                int v = 255 - grau;
                umgekehrt.setRGB(xx - x1, yy - y1, (v << 16) | (v << 8) | v);
            }
        }
        return TeamScan.liesDoppelt(umgekehrt, 0, 0, umgekehrt.getWidth(), umgekehrt.getHeight()).strip();
    }

    private static boolean inBurg(Mat screen, BotContext ctx) {
        return ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
    }

    private static String text(BufferedImage b, double[] s) {
        int bw = b.getWidth(), bh = b.getHeight();
        return TeamScan.liesDoppelt(b, (int) (s[0] * bw), (int) (s[1] * bh), (int) (s[2] * bw), (int) (s[3] * bh))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Kleinbuchstaben, nur Buchstaben und Ziffern ("Tank War" -> "tankwar"). Dazu die haeufigste Verwechslung der
     * Texterkennung: "rn" statt "m" -- auf beiden Seiten gleich ersetzt, so bleiben echte Namen vergleichbar.
     */
    static String normal(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "").replace("rn", "m");
    }

    /**
     * Passt der gelesene Name? Ganz gleich -- nicht "enthaelt" (sonst passt "War" auch auf "Tank War"). Ab 5 Buchstaben
     * darf die Texterkennung einen Buchstaben falsch lesen ("Norrnal").
     */
    static boolean passt(String gelesen, String gesucht) {
        if (gelesen.isEmpty() || gesucht.isEmpty()) return false;
        if (gelesen.equals(gesucht)) return true;
        return gesucht.length() >= 5 && abstand(gelesen, gesucht) <= 1;
    }

    private static int abstand(String a, String b) {
        int[] vor = new int[b.length() + 1], jetzt = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) vor[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            jetzt[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int k = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                jetzt[j] = Math.min(Math.min(jetzt[j - 1] + 1, vor[j] + 1), vor[j - 1] + k);
            }
            int[] t = vor; vor = jetzt; jetzt = t;
        }
        return vor[b.length()];
    }
}
