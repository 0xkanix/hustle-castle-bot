package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.NamensVergleich;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Sprache;
import de.hustlebot.core.Zeitzone;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Titanen-Turm voll -- das schwaechste Team herausnehmen (Autor 26.10.).
 *
 * "Wenn alle Titanen-Tuerme voll sind, soll der Bot auf der Clankriegskarte auf Editing mode druecken, den vollen
 * Turm oeffnen, hoch und runter scrollen, das Team mit der wenigsten Kampfkraft finden, auf die drei Striche neben
 * dem Namen druecken, Withdraw from waypoint, den Turm schliessen und Save changes druecken. Dann ist ein Platz frei
 * und er setzt mein Team ganz normal ein."
 *
 * Nie heraus: die Schutzliste (Bernd, Carl, Anna, Bruno -- Autor 26.10.), der Spieler selbst, und jedes Team
 * ueber der Grenze aus titan-bewertung.txt (Autor: 2.000.000). Ohne Grenze wird niemand herausgenommen. Kein
 * Discord-Bericht -- jeder Rauswurf steht in titan-rauswurf.log (Autor: "nur merken, auf Nachfrage sagen").
 *
 * Schalter titan-rauswurf.txt (an/aus), ohne Datei AUS -- zuerst nur fuer Autor, spaeter fuer Ben.
 */
final class TitanRauswurf {

    static final String T_EDIT_MODE = "cw_edit_mode";
    static final String T_SAVE      = "cw_edit_save";
    static final String T_EXIT      = "cw_edit_exit";
    static final String T_PFEIL     = "cw_edit_pfeil";
    static final String T_MENU      = "cw_edit_menu";
    static final String T_WITHDRAW  = "cw_edit_withdraw";
    static final String T_CLOSE_X   = "cw_close_x";

    /** Autor 26.10.: "Bernd darf nie raus. Carl darf nie raus. Anna darf nie raus. Bruno darf nie raus." */
    static final List<String> SCHUTZ = List.of();   // offene Fassung: keine festen Namen, nur titan-schutz.txt

    private static final Schalterdatei SCHALTER = new Schalterdatei(Ordner.bei("titan-rauswurf.txt"), "Titan-Rauswurf", false);

    /** Abstaende im Turmfenster (Editing mode) vom Knopf "≡" aus, bei 1159 Pixel Fensterbreite gemessen (Fotos 26.10.). */
    private static final int NAME_VON = 140, NAME_BIS = 480, KRAFT_VON = 555, KRAFT_BIS = 725, ZEILE_HALB = 17;
    /** Abstand zweier Teams in der Liste (Fotos 26.10.: 143 -> 370). */
    private static final int ZEILE = 227;
    private static final int MAX_BLAETTERN = 6;
    private static final long SCHRITT_GEDULD_MS = 8000, GESAMT_MS = 150_000;

    enum Schritt { AUS, EDIT_AN, TURM_OEFFNEN, LESEN, HOCH, SUCHEN, MENU, ZU, SPEICHERN, ENDE }

    record Team(String name, long kraft) { }

    private Schritt schritt = Schritt.AUS;
    private long seit, start;
    private int turmX, turmY, blaettern, hochGeblaettert, suchBlaettern;
    private String turmName = "";
    private final Map<String, Team> gelesen = new LinkedHashMap<>();
    private Team opfer;
    private boolean erfolg, panelZu;

    static boolean istAn() {
        return SCHALTER.lesen();
    }

    /** Neuer Lauf des Clan-Kriegs: ein halber Rauswurf von vorher laeuft nicht weiter. */
    void aus() {
        schritt = Schritt.AUS;
        erfolg = false;
    }

    boolean laeuft() {
        return schritt != Schritt.AUS && schritt != Schritt.ENDE;
    }

    boolean erfolg() {
        return erfolg;
    }

    void starten(int x, int y, String name) {
        turmX = x;
        turmY = y;
        turmName = name == null ? "" : name;
        gelesen.clear();
        opfer = null;
        erfolg = false;
        panelZu = false;
        blaettern = hochGeblaettert = suchBlaettern = 0;
        start = System.currentTimeMillis();
        weiter(Schritt.EDIT_AN);
        Log.info("Clan-Krieg", Sprache.t("All titan towers are full -- editing mode: taking the weakest team out of \"" + turmName + "\".",
                "Alle Titanen-Tuerme voll -- Editing mode: das schwaechste Team aus \"" + turmName + "\" herausnehmen."));
    }

    private void weiter(Schritt s) {
        schritt = s;
        seit = System.currentTimeMillis();
    }

    private int px(BotContext ctx, int wert) {
        return (int) Math.round(wert * (ctx.width() > 0 ? ctx.width() / 1159.0 : 1.0));
    }

    GameMode.Outcome schritt(Mat screen, BotContext ctx) {
        long lang = System.currentTimeMillis() - seit;
        if (System.currentTimeMillis() - start > GESAMT_MS) return abbrechen(screen, ctx, "dauert zu lange");
        switch (schritt) {
            case EDIT_AN -> {
                if (ctx.visible(screen, T_SAVE) || ctx.visible(screen, T_EXIT)) {
                    weiter(Schritt.TURM_OEFFNEN);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > 300 && ctx.clickIfVisible(screen, T_EDIT_MODE, Sprache.t("Editing mode", "Editing mode (Bearbeiten)"))) {
                    ctx.sleepUntilChanged(1500);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > SCHRITT_GEDULD_MS) return abbrechen(screen, ctx, "kein Editing mode");
                ctx.sleep(300);
                return GameMode.Outcome.WAITING;
            }
            case TURM_OEFFNEN -> {
                if (ctx.visible(screen, T_MENU)) {
                    weiter(Schritt.LESEN);
                    return GameMode.Outcome.ACTED;
                }
                // Liegt der Turm unter dem Zwischenlager links, das Lager erst mit dem kleinen Pfeil einklappen (Autor 26.10.).
                if (!panelZu && turmX < px(ctx, 380)) {
                    panelZu = true;
                    if (ctx.clickIfVisible(screen, T_PFEIL, Sprache.t("Fold away the squad storage", "Zwischenlager einklappen"))) {
                        ctx.sleepUntilChanged(1200);
                        return GameMode.Outcome.ACTED;
                    }
                }
                if (lang > 400 && (lang < 1500 || lang % 3000 < 400)) {
                    Log.action("Klick", Sprache.t("Editing mode: open \"" + turmName + "\"", "Editing mode: \"" + turmName + "\" oeffnen")
                            + " [" + turmX + "," + turmY + "]");
                    ctx.input().click(turmX, turmY);
                    ctx.sleepUntilChanged(1500);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > SCHRITT_GEDULD_MS) return abbrechen(screen, ctx, "Turm ging im Editing mode nicht auf");
                ctx.sleep(300);
                return GameMode.Outcome.WAITING;
            }
            case LESEN -> {
                // Alle Teams lesen: Name + Kampfkraft, dann eine Zeile weiter -- bis nichts Neues mehr kommt.
                int vorher = gelesen.size();
                for (Team t : zeilenLesen(screen, ctx).values()) gelesen.putIfAbsent(NamensVergleich.form(t.name()), t);
                if (gelesen.size() > vorher && blaettern < MAX_BLAETTERN) {
                    blaettern++;
                    listeSchieben(ctx, true);
                    return GameMode.Outcome.ACTED;
                }
                opfer = waehlen(new ArrayList<>(gelesen.values()), grenze(), MeinTeam.spielername());
                Log.info("Clan-Krieg", Sprache.t("Teams in \"" + turmName + "\": ", "Teams in \"" + turmName + "\": ") + liste());
                if (opfer == null) return abbrechen(screen, ctx, "kein Team darf heraus (Schutzliste, Grenze oder nichts lesbar)");
                weiter(Schritt.HOCH);
                return GameMode.Outcome.ACTED;
            }
            case HOCH -> {
                if (hochGeblaettert <= blaettern) {
                    hochGeblaettert++;
                    listeSchieben(ctx, false);
                    return GameMode.Outcome.ACTED;
                }
                weiter(Schritt.SUCHEN);
                return GameMode.Outcome.ACTED;
            }
            case SUCHEN -> {
                for (Map.Entry<Matcher.Hit, Team> e : zeilenMitKnopf(screen, ctx).entrySet()) {
                    if (gleich(e.getValue().name(), opfer.name())) {
                        ctx.klick(e.getKey(), Sprache.t("Menu of " + opfer.name(), "Menue von " + opfer.name()));
                        ctx.sleepUntilChanged(1200);
                        weiter(Schritt.MENU);
                        return GameMode.Outcome.ACTED;
                    }
                }
                if (suchBlaettern++ < MAX_BLAETTERN + 1) {
                    listeSchieben(ctx, true);
                    return GameMode.Outcome.ACTED;
                }
                return abbrechen(screen, ctx, opfer.name() + " in der Liste nicht wiedergefunden");
            }
            case MENU -> {
                if (ctx.clickIfVisible(screen, T_WITHDRAW, Sprache.t("Withdraw from waypoint: ", "Withdraw from waypoint: ") + opfer.name())) {
                    ctx.sleepUntilChanged(1500);
                    merken();
                    weiter(Schritt.ZU);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > 3000) return abbrechen(screen, ctx, "Withdraw from waypoint nicht zu sehen");
                ctx.sleep(250);
                return GameMode.Outcome.WAITING;
            }
            case ZU -> {
                if (ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tower (editing mode)", "Turm schliessen (Editing mode)"))) {
                    ctx.sleepUntilChanged(1200);
                    weiter(Schritt.SPEICHERN);
                    return GameMode.Outcome.ACTED;
                }
                if (ctx.visible(screen, T_SAVE)) {
                    weiter(Schritt.SPEICHERN);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > 4000) {
                    weiter(Schritt.SPEICHERN);
                    return GameMode.Outcome.ACTED;
                }
                ctx.sleep(250);
                return GameMode.Outcome.WAITING;
            }
            case SPEICHERN -> {
                if (ctx.visible(screen, T_EDIT_MODE) && !ctx.visible(screen, T_SAVE)) {
                    erfolg = true;
                    weiter(Schritt.ENDE);
                    Log.info("Clan-Krieg", Sprache.t("Saved -- a slot in \"" + turmName + "\" is free now, the titan squad goes there.",
                            "Gespeichert -- in \"" + turmName + "\" ist jetzt ein Platz frei, dorthin kommt der Titanen-Trupp."));
                    return GameMode.Outcome.ACTED;
                }
                if (ctx.clickIfVisible(screen, T_SAVE, Sprache.t("Save changes", "Save changes (speichern)"))) {
                    ctx.sleepUntilChanged(2000);
                    return GameMode.Outcome.ACTED;
                }
                // Der Knopf ist erst grau (Fotos 26.10.) -- nach dem Herausnehmen evtl. anders gefaerbt: dann ueber den Text.
                if (lang > 2500 && speichernUeberText(ctx)) return GameMode.Outcome.ACTED;
                if (lang > SCHRITT_GEDULD_MS && ctx.clickIfVisible(screen, T_EXIT, Sprache.t("Exit editing mode", "Editing mode verlassen"))) {
                    ctx.sleepUntilChanged(1500);
                    return GameMode.Outcome.ACTED;
                }
                if (lang > 2 * SCHRITT_GEDULD_MS) return abbrechen(screen, ctx, "Save changes ging nicht");
                ctx.sleep(300);
                return GameMode.Outcome.WAITING;
            }
            default -> {
                return GameMode.Outcome.ACTED;
            }
        }
    }

    /**
     * Das Team, das herausgenommen wird. Autor 26.10.:
     *  1. Normalfall: das schwaechste Team, das heraus DARF -- nicht geschuetzt, nicht ich selbst, UNTER der Grenze
     *     (nie einen Titanen-Trupp ueber 2 Mio Kampfkraft herauswerfen).
     *  2. Notfall: ist JEDES Team im vollen Turm geschuetzt ODER ueber der Grenze (z. B. 5 Plaetze, alle 5 von der
     *     Niemals-Liste), trotzdem das absolut schwaechste herausnehmen -- nur nie mich selbst -- sonst kaeme mein
     *     Titanen-Trupp nie in den Turm.
     * Ohne Grenze (keine titan-bewertung.txt) wird gar niemand herausgenommen.
     */
    static Team waehlen(List<Team> teams, long grenze, String ich) {
        if (grenze <= 0) return null;
        // 1. Normalfall: schwaechstes erlaubtes Team unter der Grenze.
        Team best = null;
        for (Team t : teams) {
            if (t.kraft() <= 0 || t.kraft() >= grenze) continue;
            if (geschuetzt(t.name()) || istIch(t.name(), ich)) continue;
            if (best == null || t.kraft() < best.kraft()) best = t;
        }
        if (best != null) return best;
        // 2. Notfall: alles geschuetzt oder ueber der Grenze -> trotzdem das absolut schwaechste (nur nicht ich).
        //    Ohne lesbaren Namen nie -- sonst wuerde evtl. ein Geschuetzter rausfliegen, den die Texterkennung verfehlt hat.
        for (Team t : teams) {
            if (t.kraft() <= 0 || t.name() == null || t.name().isBlank() || istIch(t.name(), ich)) continue;
            if (best == null || t.kraft() < best.kraft()) best = t;
        }
        if (best != null) {
            Log.info("Clan-Krieg", Sprache.t(
                    "Every team here is protected or above the limit -- taking the weakest anyway so the titan squad gets in.",
                    "Jedes Team hier ist geschuetzt oder ueber der Grenze -- nehme trotzdem das schwaechste heraus, damit der Titanen-Trupp hineinkommt."));
        }
        return best;
    }

    private static boolean istIch(String name, String ich) {
        return ich != null && !ich.isBlank() && gleich(name, ich);
    }

    static boolean geschuetzt(String name) {
        if (name == null || name.isBlank()) return true;   // ohne lesbaren Namen nie
        for (String s : schutzliste()) if (gleich(name, s)) return true;
        return false;
    }

    private static boolean gleich(String a, String b) {
        if (a == null || b == null) return false;
        String fa = NamensVergleich.form(a), fb = NamensVergleich.form(b);
        return !fa.isEmpty() && (fa.equals(fb) || NamensVergleich.gleicherName(a, b));
    }

    /** Schutzliste plus weitere Namen aus titan-schutz.txt (je Zeile einer). */
    static List<String> schutzliste() {
        List<String> l = new ArrayList<>(SCHUTZ);
        try {
            Path f = Ordner.bei("titan-schutz.txt");
            if (Files.exists(f)) {
                for (String z : de.hustlebot.core.Dateien.zeilen(f)) {
                    String t = z.strip();
                    if (!t.isEmpty() && !t.startsWith("#")) l.add(t);
                }
            }
        } catch (Exception e) {
            Log.debug("Clan-Krieg", "titan-schutz.txt nicht lesbar: " + e.getMessage());
        }
        return l;
    }

    /** grenze= aus titan-bewertung.txt (Autors Artefakt), 0 = keine -> niemand heraus. */
    static long grenze() {
        try {
            Path f = Ordner.bei("titan-bewertung.txt");
            if (!Files.exists(f)) return 0;
            for (String z : de.hustlebot.core.Dateien.zeilen(f)) {
                String t = z.strip().replace("﻿", "");
                if (t.startsWith("grenze=")) return Long.parseLong(t.substring(7).replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            Log.debug("Clan-Krieg", "titan-bewertung.txt nicht lesbar: " + e.getMessage());
        }
        return 0;
    }

    private Map<String, Team> zeilenLesen(Mat screen, BotContext ctx) {
        Map<String, Team> m = new LinkedHashMap<>();
        for (Team t : zeilenMitKnopf(screen, ctx).values()) m.put(NamensVergleich.form(t.name()), t);
        return m;
    }

    /** Jede sichtbare Team-Zeile: Knopf "≡" -> Name und Kampfkraft rechts daneben. */
    private Map<Matcher.Hit, Team> zeilenMitKnopf(Mat screen, BotContext ctx) {
        Map<Matcher.Hit, Team> m = new LinkedHashMap<>();
        BufferedImage bild = ctx.frame();
        if (bild == null) return m;
        for (Matcher.Hit h : ctx.matcher().findAll(screen, T_MENU, 0.85, 6)) {
            int y = h.centerY() - px(ctx, ZEILE_HALB), hoehe = px(ctx, 2 * ZEILE_HALB);
            String name = text(bild, h.centerX() + px(ctx, NAME_VON), y, px(ctx, NAME_BIS - NAME_VON), hoehe).strip();
            long kraft = 0;
            int kx = h.centerX() + px(ctx, KRAFT_VON);
            int kb = Math.min(bild.getWidth() - kx, px(ctx, KRAFT_BIS - KRAFT_VON));
            if (kb > 20 && y >= 0 && y + hoehe <= bild.getHeight()) {
                for (long z : ArenaLeser.zahlenIn(bild, kx, y, kb, hoehe)) if (z > kraft && z < 50_000_000L) kraft = z;
            }
            if (!name.isEmpty()) m.put(h, new Team(name, kraft));
        }
        return m;
    }

    private static String text(BufferedImage bild, int x, int y, int b, int h) {
        x = Math.max(0, x);
        y = Math.max(0, y);
        b = Math.min(bild.getWidth() - x, b);
        h = Math.min(bild.getHeight() - y, h);
        if (b < 20 || h < 8) return "";
        try {
            return String.join(" ", de.hustlebot.core.Texterkennung.lies(bild.getSubimage(x, y, b, h)));
        } catch (Exception e) {
            return "";
        }
    }

    /** Eine Team-Zeile weiter (runter = true) oder zurueck, langsam gezogen (sonst rutscht die Liste nach). */
    private void listeSchieben(BotContext ctx, boolean runter) {
        int x = px(ctx, 760), oben = px(ctx, 223), unten = oben + px(ctx, ZEILE);
        if (runter) ctx.input().dragLangsam(x, unten, x, oben);
        else ctx.input().dragLangsam(x, oben, x, unten);
        ctx.sleep(700);
    }

    private boolean speichernUeberText(BotContext ctx) {
        BufferedImage bild = ctx.frame();
        if (bild == null) return false;
        int x = (int) (bild.getWidth() * 0.41), y = (int) (bild.getHeight() * 0.22), b = (int) (bild.getWidth() * 0.18), h = (int) (bild.getHeight() * 0.08);
        if (!text(bild, x, y, b, h).toLowerCase(Locale.ROOT).contains("save")) return false;
        Log.action("Klick", Sprache.t("Save changes (found by its text)", "Save changes (am Text erkannt)"));
        ctx.input().click(x + b / 2, y + h / 2);
        ctx.sleepUntilChanged(2000);
        return true;
    }

    private String liste() {
        StringBuilder sb = new StringBuilder();
        for (Team t : gelesen.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name()).append(" ").append(t.kraft() > 0 ? String.format(Locale.GERMANY, "%,d", t.kraft()) : "?");
        }
        return sb.toString();
    }

    /** Autor: "Du sollst es nur merken" -- jede Herausnahme in titan-rauswurf.log. */
    private void merken() {
        String zeile = Zeitzone.jetzt().withNano(0) + "\t" + turmName + "\t" + opfer.name() + "\t" + opfer.kraft() + "\t" + liste() + System.lineSeparator();
        Log.info("Clan-Krieg", Sprache.t("Taken out of \"" + turmName + "\": " + opfer.name() + " (" + opfer.kraft() + " power).",
                "Aus \"" + turmName + "\" herausgenommen: " + opfer.name() + " (" + opfer.kraft() + " Kampfkraft)."));
        try {
            Files.writeString(Ordner.bei("titan-rauswurf.log"), zeile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            Log.debug("Clan-Krieg", "titan-rauswurf.log nicht geschrieben: " + e.getMessage());
        }
    }

    /** Etwas ging nicht: Turm zu, Editing mode ohne Speichern verlassen -- der Clan-Krieg macht normal weiter. */
    private GameMode.Outcome abbrechen(Mat screen, BotContext ctx, String grund) {
        Log.warn("Clan-Krieg", Sprache.t("Taking a team out did not work (" + grund + ") -- leaving editing mode.",
                "Team herausnehmen ging nicht (" + grund + ") -- Editing mode wird verlassen."));
        if (ctx.clickIfVisible(screen, T_CLOSE_X, Sprache.t("Close the tower", "Turm schliessen"))) ctx.sleepUntilChanged(1200);
        Mat neu = ctx.refresh();
        if (neu != null && ctx.clickIfVisible(neu, T_EXIT, Sprache.t("Exit editing mode", "Editing mode verlassen"))) ctx.sleepUntilChanged(1500);
        erfolg = false;
        weiter(Schritt.ENDE);
        return GameMode.Outcome.ACTED;
    }

}
