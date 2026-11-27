package de.hustlebot.bot;

import de.hustlebot.core.Konten;
import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import de.hustlebot.core.Namensbild;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Konto-Pruefung bei JEDEM Modus-Start (Autor 21.11.: "Der Bot soll immer in der Burg gestartet werden, den Namen sehen.
 * Passt er und hat er schon Bilder von diesem Account, nimmt er die. Hat er noch keine ... soll er sie machen und dann erst
 * den Modus spielen." -- Freund Robin spielte auf einem Zweitkonto, der Bot erkannte die Einheiten nicht).
 *
 * ABLAUF. 1. In die Burg (Burgweg: rotes X / Home, hoechstens 25 s). Wo Wegklicken schaden wuerde (Gegnersuche,
 * Verlies-/Clankrieg-Aufstellung, laufende Reise) oder kein Weg zu sehen ist (Kampf), laeuft der Modus normal an und die
 * Pruefung kommt beim naechsten Mal in der Burg ("passiv"). 2. In der Burg Name + Namensbild lesen (ohne Klick):
 * bekanntes Konto = dessen Dateien und Bilder, fertig. 3. Name unbekannt/unlesbar oder dem Konto fehlt noch die ID: das
 * Profil oeffnen (Level-Abzeichen antippen), die Spieler-ID lesen, Profil schliessen. ID bekannt = dieses Konto (auch
 * umbenannt), ID unbekannt = neues Konto anlegen. Danach weiss der Motor ueber {@link #gewechseltAbholen()}, dass er
 * zuerst den Team-Scan einschieben soll. Nichts ist fest eingetragen -- alles wird zur Laufzeit gelesen (core.Konten).
 */
final class KontoPruefung {

    enum Phase { FERTIG, WEG, LESEN, PROFIL_AUF, PROFIL_ZU, PASSIV }

    static final long BURG_FRIST_MS = 25_000, PROFIL_FRIST_MS = 6_000, ZU_FRIST_MS = 8_000, PASSIV_ABSTAND_MS = 2_000;
    /** So viele Bilder hintereinander ohne moeglichen Schritt Richtung Burg -> passiv (z. B. mitten im Kampf). */
    static final int NICHTS_GRENZE = 8;
    /** Passiv hoechstens so oft in der Burg nachsehen (je Modus-Start). */
    static final int PASSIV_BLICKE = 3;

    /**
     * Hier NICHT wegklicken (Autor 21.11.): Gegnersuche (Arena/Verlies), Verlies-Aufstellung mit Mitspieler,
     * Clankrieg-Aufstellung, laufende Verlies-/Kathedralen-Reise. Dann prueft der Bot beim naechsten Mal in der Burg.
     */
    static final List<String> NICHT_WEGKLICKEN = List.of("cancel_search", "ar_searching", "ready", "partner_ready",
            "invite_sent", "start_journey", "cw_deploy_squad", "cw_deploy_best", "cw_deploy_grey", "end_journey",
            "dc_floor_hint");

    /** Konten, deren Profil in diesem Botlauf schon (vergeblich) nach der ID abgesucht wurde -- nicht bei jedem Start neu. */
    private static final Set<String> PROFIL_VERSUCHT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final String WER = "Konten";

    private Phase phase = Phase.FERTIG;
    private long fristBis, naechsterBlick, profilSeit, zuSeit, nochmalAb;
    private int nichts, lesungen, blicke, tipps, idVersuche;
    private String burgName = "", profilName = "", id = "";
    private boolean[][] bild;
    private Konten.Befund befundName;
    private volatile boolean gewechselt;

    /** Beim Modus-Start: mit Schalter an erst in die Burg und pruefen, sonst nichts. */
    void starten(boolean an) {
        phase = an ? Phase.WEG : Phase.FERTIG;
        fristBis = System.currentTimeMillis() + BURG_FRIST_MS;
        naechsterBlick = nochmalAb = 0;
        nichts = lesungen = blicke = tipps = idVersuche = 0;
        burgName = profilName = id = "";
        bild = null;
        befundName = null;
    }

    boolean fertig() {
        return phase == Phase.FERTIG;
    }

    Phase phase() {
        return phase;
    }

    /** Wurde zu einem anderen oder neuen Konto gewechselt? Einmal abholen (fuer den Team-Scan davor). */
    boolean gewechseltAbholen() {
        boolean w = gewechselt;
        gewechselt = false;
        return w;
    }

    /**
     * Ein Takt der Pruefung. true = der Modus wartet in diesem Takt (die Pruefung hat geklickt oder wartet kurz);
     * false = der Modus darf jetzt.
     */
    boolean schritt(Mat screen, BotContext ctx) {
        if (phase == Phase.FERTIG || screen == null) return false;
        long jetzt = System.currentTimeMillis();
        switch (phase) {
            case PASSIV -> {
                if (blicke >= PASSIV_BLICKE) {
                    phase = Phase.FERTIG;
                    return false;
                }
                if (jetzt < naechsterBlick) return false;
                naechsterBlick = jetzt + PASSIV_ABSTAND_MS;
                if (!burg(screen, ctx)) return false;
                blicke++;
                phase = Phase.LESEN;
                return lesen(screen, ctx);
            }
            case WEG -> {
                return weg(screen, ctx, jetzt);
            }
            case LESEN -> {
                if (jetzt < nochmalAb) {
                    ctx.sleep(200);
                    return true;
                }
                if (!burg(screen, ctx)) {
                    phase = jetzt < fristBis ? Phase.WEG : Phase.PASSIV;
                    return phase == Phase.WEG;
                }
                return lesen(screen, ctx);
            }
            case PROFIL_AUF -> {
                return profilAuf(screen, ctx, jetzt);
            }
            case PROFIL_ZU -> {
                return profilZu(screen, ctx, jetzt);
            }
            default -> {
                return false;
            }
        }
    }

    // ---- 1. In die Burg ------------------------------------------------------------------------------------------------

    private boolean weg(Mat screen, BotContext ctx, long jetzt) {
        if (burg(screen, ctx)) {
            phase = Phase.LESEN;
            return lesen(screen, ctx);
        }
        if (jetzt > fristBis) {
            passiv(Sprache.t("castle not reached within " + BURG_FRIST_MS / 1000 + " s",
                    "Burg in " + BURG_FRIST_MS / 1000 + " s nicht erreicht"));
            return false;
        }
        String gefahr = gefahr(screen, ctx);
        if (gefahr != null) {
            passiv(Sprache.t("not clicking away here (" + gefahr + ")", "hier wird nicht weggeklickt (" + gefahr + ")"));
            return false;
        }
        String wo = Sprache.t("Account check", "Konto-Pruefung");
        if (Burgweg.schritt(screen, ctx, wo, false)
                || ctx.clickIfVisibleIfKnown(screen, "close_reward", wo + ": " + Sprache.t("close reward", "Belohnung schliessen"))) {
            nichts = 0;
            return true;
        }
        if (++nichts >= NICHTS_GRENZE) {
            passiv(Sprache.t("no way to the castle in sight (battle?)", "kein Weg zur Burg zu sehen (Kampf?)"));
            return false;
        }
        ctx.sleep(250);
        return true;
    }

    /**
     * In der Burg? Wie Burgweg.inDerBurg, aber NICHT auf der Gebietskarte (Autor 22.11.: dort ist der runde Map-Knopf
     * unten links auch zu sehen -- es gibt aber kein Level-Abzeichen, die Pruefung haette dort Sekunden vertan).
     */
    static boolean burg(Mat screen, BotContext ctx) {
        return Burgweg.inDerBurg(screen, ctx) && !ctx.gebietskarte(screen);
    }

    /** Die erste sichtbare Vorlage, bei der nicht weggeklickt werden darf; null = keine. */
    static String gefahr(Mat screen, BotContext ctx) {
        for (String v : NICHT_WEGKLICKEN) if (ctx.visibleIfKnown(screen, v)) return v;
        return null;
    }

    private void passiv(String grund) {
        phase = Phase.PASSIV;
        naechsterBlick = 0;
        Log.info(WER, Sprache.t("Account check: " + grund + " -- the mode starts normally, the account is checked the next time "
                        + "the castle is in view.",
                "Konto-Pruefung: " + grund + " -- der Modus startet normal, das Konto wird beim naechsten Mal in der Burg geprueft."));
    }

    // ---- 2. Name in der Burg -------------------------------------------------------------------------------------------

    private boolean lesen(Mat screen, BotContext ctx) {
        if (!ctx.matcher().has(Burgstand.T_LEVEL)) {
            // Ohne das Bild des Level-Abzeichens gibt es weder Namen noch Profil -- nicht jedes Mal Sekunden vertun.
            fertig(Sprache.t("the picture of the level badge (" + Burgstand.T_LEVEL + ".png) is missing -- no check",
                    "das Bild des Level-Abzeichens (" + Burgstand.T_LEVEL + ".png) fehlt -- keine Pruefung"));
            return false;
        }
        Optional<Matcher.Hit> abzeichen = ctx.find(screen, Burgstand.T_LEVEL);
        BufferedImage frame = ctx.frame();
        bild = null;
        if (abzeichen.isPresent() && frame != null) {
            Matcher.Hit a = abzeichen.get();
            int breite = (int) (ctx.width() * 0.17);
            bild = Namensbild.ausBereich(frame, a.x() + a.width(), a.y(), breite, a.height());
            // Billig zuerst: gleiches Namensbild wie das aktive Konto -> ohne Texterkennung fertig.
            Konten.Befund schnell = Konten.nachName("", bild);
            if (schnell.urteil() == Konten.Urteil.AKTIV) {
                befundName = schnell;
                return nachNamensbefund(ctx);
            }
            burgName = nameAusBild(bild);
        } else {
            burgName = "";
        }
        lesungen++;
        befundName = Konten.nachName(burgName, bild);
        // Unlesbar (kein Abzeichen, kein Name): einmal 1,5 s spaeter noch einmal, dann ins Profil.
        if (befundName.urteil() == Konten.Urteil.UNLESBAR && lesungen < 2) {
            nochmalAb = System.currentTimeMillis() + 1_500;
            return true;
        }
        return nachNamensbefund(ctx);
    }

    /**
     * Der Spielername aus dem Namensbild (nur die erste Zeile neben dem Level-Abzeichen, schwarz auf weiss, vergroessert).
     * Autor 22.11.: auf dem ganzen Ausschnitt las die Texterkennung "MeinClan" (den Clan unter dem Namen) -- dann haette
     * jedes Konto im selben Clan als dieses Konto gegolten. Lieber "" (dann entscheidet die Spieler-ID) als der Clan.
     */
    static String nameAusBild(boolean[][] namensbild) {
        BufferedImage z = Namensbild.zumLesen(namensbild, 3);
        if (z == null) return "";
        return String.join(" ", ArenaLeser.lies(z, 0, 0, z.getWidth(), z.getHeight())).trim();
    }

    private boolean nachNamensbefund(BotContext ctx) {
        switch (befundName.urteil()) {
            case AKTIV -> {
                Konten.merken("", namen(), bild);
                if (idFehlt()) return profilOeffnen();
                fertig(Sprache.t("account \"" + befundName.konto() + "\" confirmed", "Konto \"" + befundName.konto() + "\" bestaetigt"));
                return false;
            }
            case ANDERES -> {
                // Autor 22.11.: "Name anders als aktives Konto -> Spielerprofil oeffnen" -- die Spieler-ID bestaetigt den
                // Wechsel (er verschiebt alle Dateien und Kaempferbilder; ein Lesefehler soll ihn nie ausloesen). Ist keine
                // ID lesbar, gilt der Name (entscheiden()).
                Log.info(WER, Sprache.t("Account check: the name looks like the account \"" + befundName.konto()
                                + "\" -- checking the player ID in the profile.",
                        "Konto-Pruefung: der Name sieht nach dem Konto \"" + befundName.konto()
                                + "\" aus -- die Spieler-ID im Profil wird geprueft."));
                return profilOeffnen();
            }
            default -> {   // UNBEKANNT, UNLESBAR, ERSTES: die Spieler-ID entscheidet
                if (befundName.urteil() == Konten.Urteil.UNLESBAR
                        && PROFIL_VERSUCHT.contains(Konten.aktiv().toLowerCase(java.util.Locale.ROOT))) {
                    // Name unlesbar UND das Profil gab in diesem Lauf schon keine ID her: nicht bei jedem Modus-Start neu.
                    fertig(Sprache.t("name not readable, the profile was already tried in vain -- the account stays \""
                                    + Konten.aktiv() + "\"",
                            "Name nicht lesbar, das Profil wurde schon vergeblich versucht -- es bleibt das Konto \""
                                    + Konten.aktiv() + "\""));
                    return false;
                }
                Log.info(WER, befundName.urteil() == Konten.Urteil.ERSTES
                        ? Sprache.t("Account check: no account noted yet -- reading the player ID in the profile.",
                                "Konto-Pruefung: noch kein Konto vermerkt -- die Spieler-ID im Profil wird gelesen.")
                        : Sprache.t("Account check: name \"" + burgName + "\" is not the active account \"" + Konten.aktiv()
                                        + "\" (or unreadable) -- reading the player ID in the profile.",
                                "Konto-Pruefung: Name \"" + burgName + "\" ist nicht das aktive Konto \"" + Konten.aktiv()
                                        + "\" (oder unlesbar) -- die Spieler-ID im Profil wird gelesen."));
                return profilOeffnen();
            }
        }
    }

    /** Hat das aktive Konto noch keine ID, und wurde sein Profil in diesem Lauf noch nicht abgesucht? */
    private static boolean idFehlt() {
        return Konten.id().isEmpty() && !PROFIL_VERSUCHT.contains(Konten.aktiv().toLowerCase(java.util.Locale.ROOT));
    }

    /** Ein brauchbarer Name (mindestens 2 Buchstaben/Ziffern, kein Ueberschriften-Wort) -- wie core.Konten.lesbar. */
    static boolean lesbar(String n) {
        return n != null && de.hustlebot.core.NamensVergleich.form(n).length() >= 2
                && !de.hustlebot.core.NamensVergleich.keinName(n);
    }

    private List<String> namen() {
        List<String> l = new ArrayList<>();
        if (!profilName.isBlank()) l.add(profilName);
        if (!burgName.isBlank()) l.add(burgName);
        return l;
    }

    // ---- 3. Profil: Spieler-ID -------------------------------------------------------------------------------------------

    private boolean profilOeffnen() {
        phase = Phase.PROFIL_AUF;
        profilSeit = 0;
        tipps = 0;
        idVersuche = 0;
        id = "";
        profilName = "";
        return true;
    }

    private boolean profilAuf(Mat screen, BotContext ctx, long jetzt) {
        BufferedImage frame = ctx.frame();
        if (SpielerProfil.ziffernreihe(frame) != null) {
            id = SpielerProfil.id(frame);
            if (id.isEmpty() && ++idVersuche < 3) return true;   // die Texterkennung noch zweimal fragen (neues Bild)
            profilName = SpielerProfil.name(frame);
            phase = Phase.PROFIL_ZU;
            zuSeit = jetzt;
            return true;
        }
        boolean inBurg = burg(screen, ctx);
        if (inBurg && (profilSeit == 0 || jetzt - profilSeit > 2_500) && tipps < 2) {
            Optional<Matcher.Hit> a = ctx.matcher().has(Burgstand.T_LEVEL) ? ctx.find(screen, Burgstand.T_LEVEL) : Optional.empty();
            if (a.isPresent()) {
                Log.action("Klick", Sprache.t("Account check: open the player profile (level badge) to read the player ID",
                        "Konto-Pruefung: Spielerprofil oeffnen (Level-Abzeichen), um die Spieler-ID zu lesen"));
                ctx.input().click(a.get());
                tipps++;
                profilSeit = jetzt;
                ctx.sleepUntilChanged(1_500);
                return true;
            }
        }
        if (profilSeit == 0 || jetzt - profilSeit > PROFIL_FRIST_MS || (!inBurg && tipps == 0)) {
            // Kein Profil zu sehen: ohne ID entscheiden (es gilt der Name).
            Log.info(WER, Sprache.t("Account check: the player profile did not open -- deciding by the name.",
                    "Konto-Pruefung: das Spielerprofil ging nicht auf -- es entscheidet der Name."));
            phase = Phase.PROFIL_ZU;
            zuSeit = jetzt;
            return true;
        }
        ctx.sleep(200);
        return true;
    }

    private boolean profilZu(Mat screen, BotContext ctx, long jetzt) {
        if (burg(screen, ctx)) {
            entscheiden(ctx);
            return true;   // ein frisches Bild fuer den Modus
        }
        if (jetzt - zuSeit > ZU_FRIST_MS) {
            entscheiden(ctx);
            return false;
        }
        if (!Burgweg.schritt(screen, ctx, Sprache.t("Account check: close profile", "Konto-Pruefung: Profil schliessen"), true)) {
            ctx.sleep(250);
        }
        return true;
    }

    /** Mit ID (oder ohne, dann nach dem Namen) entscheiden und das Ergebnis beim Konto speichern. */
    private void entscheiden(BotContext ctx) {
        String aktiv = Konten.aktiv();
        if (id.isEmpty() && !aktiv.isEmpty()) PROFIL_VERSUCHT.add(aktiv.toLowerCase(java.util.Locale.ROOT));
        if (aktiv.isEmpty()) {
            // Das allererste Konto: die jetzigen Dateien gehoeren ihm, dazu seine ID.
            String name = namen().stream().filter(KontoPruefung::lesbar).findFirst().orElse(id.isEmpty() ? "" : "ID " + id);
            Konten.ersteVermerken(name);
            if (Konten.aktiv().isEmpty()) {
                fertig(Sprache.t("neither name nor player ID readable -- no account noted, the mode starts normally",
                        "weder Name noch Spieler-ID lesbar -- kein Konto vermerkt, der Modus startet normal"));
                return;
            }
            Konten.merken(id, namen(), bild);
            // Autor 22.11.: auch das erste Konto bekommt zuerst den Team-Scan, wenn es noch keinen hat (der Motor prueft das).
            gewechselt = true;
            fertig(Sprache.t("first account \"" + name + "\" noted" + (id.isEmpty() ? "" : " (ID " + id + ")"),
                    "erstes Konto \"" + name + "\" vermerkt" + (id.isEmpty() ? "" : " (ID " + id + ")")));
            return;
        }
        Konten.Befund b = Konten.nachId(id, namen(), bild);
        switch (b.urteil()) {
            case AKTIV -> {
                Konten.merken(id, namen(), bild);
                fertig(Sprache.t("account \"" + b.konto() + "\" confirmed by its player ID", "Konto \"" + b.konto() + "\" per Spieler-ID bestaetigt"));
            }
            case ANDERES -> {
                wechseln(ctx, b.konto(), false);
                Konten.merken(id, namen(), bild);
                fertig(Sprache.t("player ID " + id + " belongs to \"" + b.konto() + "\" -- switched",
                        "Spieler-ID " + id + " gehoert zu \"" + b.konto() + "\" -- gewechselt"));
            }
            case NEU -> {
                if (wechseln(ctx, b.konto(), true)) {
                    Konten.merken(id, namen(), bild);
                    fertig(Sprache.t("unknown player ID " + id + " -- new account \"" + b.konto() + "\" created",
                            "unbekannte Spieler-ID " + id + " -- neues Konto \"" + b.konto() + "\" angelegt"));
                } else {
                    fertig(Sprache.t("unknown player ID " + id + ", but the new account \"" + b.konto()
                                    + "\" could not be created -- the mode starts with the current account",
                            "unbekannte Spieler-ID " + id + ", das neue Konto \"" + b.konto()
                                    + "\" liess sich aber nicht anlegen -- der Modus startet mit dem bisherigen Konto"));
                }
            }
            default -> {
                // Keine ID lesbar: es gilt der Name (ein neuer Name braucht wie bisher zwei gleiche Lesungen).
                if (befundName != null && befundName.urteil() == Konten.Urteil.AKTIV) {
                    fertig(Sprache.t("no player ID readable, the name fits", "keine Spieler-ID lesbar, der Name passt"));
                    return;
                }
                // Autor 22.11.: Name = ein anderes bekanntes Konto -> dessen Dateien und Bilder holen (Schritt 2).
                if (befundName != null && befundName.urteil() == Konten.Urteil.ANDERES) {
                    if (wechseln(ctx, befundName.konto(), false)) Konten.merken("", namen(), bild);
                    fertig(Sprache.t("no player ID readable -- switched by the name to the known account \"" + befundName.konto() + "\"",
                            "keine Spieler-ID lesbar -- nach dem Namen zum bekannten Konto \"" + befundName.konto() + "\" gewechselt"));
                    return;
                }
                String name = namen().stream().filter(n -> !n.isBlank()).findFirst().orElse("");
                Optional<String> k = Konten.erkennen(name);
                if (k.isPresent()) {
                    wechseln(ctx, k.get(), !de.hustlebot.core.Konten.bekannt().contains(k.get()));
                    Konten.merken("", namen(), bild);
                }
                fertig(Sprache.t("no player ID readable -- decided by the name", "keine Spieler-ID lesbar -- nach dem Namen entschieden"));
            }
        }
    }

    private void fertig(String was) {
        phase = Phase.FERTIG;
        Log.info(WER, Sprache.t("Account check: " + was + ".", "Konto-Pruefung: " + was + "."));
    }

    /** Wechselt das Konto und laedt alles neu, was der Bot von den Konto-Dateien im Speicher hat. */
    private boolean wechseln(BotContext ctx, String konto, boolean neu) {
        if (!Konten.wechseln(konto, ctx.matcher().templateRoot())) return false;
        if (neu) {
            Log.hinweis(WER, Sprache.t("New game account \"" + konto + "\" recognised -- its own folder is created; the team scan "
                            + "comes first, then the mode.",
                    "Neues Spielkonto \"" + konto + "\" erkannt -- es bekommt einen eigenen Ordner; zuerst kommt der Team-Scan, "
                            + "dann der Modus."));
        }
        de.hustlebot.core.Gegnerbuch.gemeinsam().neuLaden();
        de.hustlebot.core.Bezahlung.neuLaden();
        MeinTeam.kontoGewechselt();
        ctx.matcher().loadAll();   // Gegnerlisten und Kaempferbilder des Kontos
        gewechselt = true;
        return true;
    }

    /** Nur fuer Tests: was im Lauf schon im Profil gesucht wurde, vergessen. */
    static void vergessen() {
        PROFIL_VERSUCHT.clear();
    }
}
