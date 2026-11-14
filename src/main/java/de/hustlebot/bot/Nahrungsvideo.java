package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Matcher;
import org.opencv.core.Mat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import de.hustlebot.core.Sprache;

/**
 * Nahrung per Werbevideo (Autor, 28.09.): der letzte Weg an Nahrung vor dem
 * Clan-Krieg, wenn Tasche und Clan-Laden nichts mehr haben.
 *
 * In der Burg auf den Apfel oben klicken -> Fenster "Food" -> gelber Knopf
 * "+90 000 Free" -> eine Werbung laeuft. WAEHREND DER WERBUNG WIRD NICHTS
 * GEDRUECKT -- ein Klick aufs X wuerde die Belohnung kosten. Sie endet von
 * selbst, danach steht der Bot wieder in der Burg (das Fenster "Food" mit
 * der Wartezeit darf offen bleiben, sagt Autor).
 *
 * Zwischen zwei Videos 60 Sekunden Pause, hoechstens 2 am Tag; der Zaehler
 * beginnt um 02:00 neu (Autor). Nur fuer den Clan-Krieg.
 */
final class Nahrungsvideo {

    /** Der gelbe Knopf "+90 000 Free" im Fenster "Food". */
    static final String T_KNOPF = "cw_video_knopf";
    /** Derselbe Knopf grau mit Uhr ("50s") -- Wartezeit bis zum naechsten Video. Freiwillig. */
    static final String T_WARTEN = "cw_video_warten";
    /** Unten in der Werbung: "To get a reward, watch the ad all the way through". Freiwillig. */
    static final String T_WERBUNG = "cw_werbung";

    static final int MAX_JE_TAG = 2;
    private static final Path ZAEHLER = Path.of("nahrung-videos.txt");
    private static final long PAUSE_MAX_MS = 80_000;     // 60 s Pause, etwas Luft
    /**
     * Eine Werbung dauert 30 bis 61 Sekunden (Autor). Vor 25 s wird gar nicht
     * erst nachgesehen -- laedt sie langsam, steht kurz noch die Burg da, und
     * das darf nicht als "vorbei" gelten.
     */
    private static final long WERBUNG_MIN_MS = 25_000;
    private static final long WERBUNG_MAX_MS = 180_000;
    private static final double APFEL_SCHWELLE = 0.72;

    private enum Phase { APFEL, KNOPF, WERBUNG }

    private boolean aktiv;
    private Phase phase;
    private long ziel;
    private long seit, knopfSeit;
    private int apfelKlicks, apfelBlind, geschaut;
    private long apfelGeklicktUm;
    /** So lange nach einem Klick auf den Apfel auf das Fenster warten, bevor nochmal geklickt wird. */
    private static final long FENSTER_GEDULD_MS = 4_000;

    /** Sind die Bilder da und heute noch Videos uebrig? */
    static boolean moeglich(BotContext ctx) {
        return ctx.matcher().has(Nahrungskisten.T_NAHRUNG) && ctx.matcher().has(T_KNOPF)
                && heuteGeschaut() < MAX_JE_TAG;
    }

    boolean aktiv() {
        return aktiv;
    }

    int geschaut() {
        return geschaut;
    }

    /** Startet in der Burg. ziel = so viel Nahrung soll danach da sein. */
    void starten(long zielNahrung) {
        aktiv = true;
        ziel = zielNahrung;
        phase = Phase.APFEL;
        seit = System.currentTimeMillis();
        knopfSeit = 0;
        apfelKlicks = apfelBlind = 0;
        apfelGeklicktUm = 0;
        geschaut = 0;
        Log.info("Nahrung", Sprache.t("Food via ad video (today already " + heuteGeschaut() + " of " + MAX_JE_TAG + ").",
                "Nahrung per Werbevideo (heute schon " + heuteGeschaut() + " von "
                + MAX_JE_TAG + ")."));
    }

    /** Ein Schritt. false = fertig, der Bot steht in der Burg. */
    boolean schritt(Mat screen, BotContext ctx) {
        if (!aktiv) return false;
        boolean inBurg = ctx.visibleIfKnown(screen, "map_button") || ctx.visibleIfKnown(screen, "map_button_alt");
        long jetzt = System.currentTimeMillis();

        switch (phase) {
            case APFEL -> {
                if (ctx.visibleIfKnown(screen, T_KNOPF) || ctx.visibleIfKnown(screen, T_WARTEN)) {
                    phase = Phase.KNOPF;
                    knopfSeit = jetzt;
                    return true;
                }
                // Nach einem Klick erst warten -- ein zweiter Klick, waehrend das
                // Fenster noch aufgeht, wuerde es wieder zumachen.
                if (jetzt - apfelGeklicktUm < FENSTER_GEDULD_MS) {
                    ctx.sleep(300);
                    return true;
                }
                if (apfelKlicks >= 3) return fertig(Sprache.t("the \"Food\" window did not open", "das Fenster \"Food\" ging nicht auf"));
                Optional<Matcher.Hit> apfel = ctx.matcher().find(screen, Nahrungskisten.T_NAHRUNG, APFEL_SCHWELLE);
                if (apfel.isEmpty() || apfel.get().centerY() > ctx.height() * 0.20) {
                    if (++apfelBlind > 10) return fertig(Sprache.t("the apple at the top could not be found", "der Apfel oben war nicht zu finden"));
                    ctx.sleep(400);
                    return true;
                }
                apfelKlicks++;
                apfelGeklicktUm = jetzt;
                Log.action("Klick", Sprache.t("Click the food at the top (window \"Food\")  [" + Nahrungskisten.T_NAHRUNG + " at " + apfel.get().centerX() + "," + apfel.get().centerY() + "]",
                        "Nahrung oben anklicken (Fenster \"Food\")  [" + Nahrungskisten.T_NAHRUNG
                        + " bei " + apfel.get().centerX() + "," + apfel.get().centerY() + "]"));
                ctx.input().click(apfel.get());
                ctx.sleepUntilChanged(1200);
                return true;
            }
            case KNOPF -> {
                if (heuteGeschaut() >= MAX_JE_TAG) return fertig(Sprache.t("already " + MAX_JE_TAG + " videos today", "heute schon " + MAX_JE_TAG + " Videos"));
                if (ctx.clickIfVisibleIfKnown(screen, T_KNOPF, Sprache.t("Start the ad video for food (+90 000 Free)", "Werbevideo fuer Nahrung starten (+90 000 Free)"))) {
                    heuteMitzaehlen();
                    phase = Phase.WERBUNG;
                    seit = jetzt;
                    ctx.sleep(2000);
                    return true;
                }
                // Wartezeit (grauer Knopf mit Uhr) -- oder das Fenster ist zu.
                if (knopfSeit == 0) knopfSeit = jetzt;
                if (jetzt - knopfSeit > PAUSE_MAX_MS) return fertig(Sprache.t("no \"Free\" button any more (waiting time or daily limit)",
                        "kein \"Free\"-Knopf mehr (Wartezeit oder Tagesgrenze)"));
                if (!ctx.visibleIfKnown(screen, T_WARTEN) && !ctx.visibleIfKnown(screen, T_KNOPF) && inBurg
                        && jetzt - knopfSeit > 3000) {
                    phase = Phase.APFEL;   // Fenster zu: noch einmal den Apfel
                    return true;
                }
                ctx.sleep(1000);
                return true;
            }
            case WERBUNG -> {
                // NICHTS DRUECKEN. Nur hinsehen, ob die Werbung vorbei ist.
                long dauer = jetzt - seit;
                boolean werbungDa = ctx.visibleIfKnown(screen, T_WERBUNG);
                if (dauer > WERBUNG_MAX_MS) {
                    // Auch jetzt NICHT klicken: ein Klick in eine Werbung kann eine
                    // fremde Webseite oeffnen (Autor). Autor Bescheid geben.
                    Log.alarm("Nahrung", Sprache.t("The ad was not over after " + dauer / 1000 + " s. " + "The bot pressed NOTHING -- please check at the computer.",
                            "Die Werbung war nach " + dauer / 1000 + " s noch nicht vorbei. "
                            + "Der Bot hat NICHTS gedrueckt -- bitte am Rechner nachsehen."));
                    return fertig(Sprache.t("the ad was not over after " + dauer / 1000 + " s",
                            "die Werbung war nach " + dauer / 1000 + " s noch nicht vorbei"));
                }
                if (dauer < WERBUNG_MIN_MS || werbungDa || !inBurg) {
                    ctx.sleep(1000);
                    return true;
                }
                geschaut++;
                OptionalLong n = Nahrungskisten.nahrungLesen(ctx);
                Log.info("Nahrung", Sprache.t("Ad video " + geschaut + " finished (" + dauer / 1000 + " s). Food now " + (n.isPresent() ? n.getAsLong() : "not readable") + ".",
                        "Werbevideo " + geschaut + " zu Ende (" + dauer / 1000 + " s). Nahrung jetzt "
                        + (n.isPresent() ? n.getAsLong() : "nicht lesbar") + "."));
                if (n.isPresent() && n.getAsLong() >= ziel) return fertig(Sprache.t("enough food", "genug Nahrung"));
                if (heuteGeschaut() >= MAX_JE_TAG) return fertig(Sprache.t("already " + MAX_JE_TAG + " videos today", "heute schon " + MAX_JE_TAG + " Videos"));
                // Noch eins: das Fenster ist noch offen, nach der Pause kommt "Free" wieder.
                phase = Phase.KNOPF;
                knopfSeit = jetzt;
                return true;
            }
        }
        return false;
    }

    private boolean fertig(String grund) {
        aktiv = false;
        Log.info("Nahrung", Sprache.t("Ad videos: " + grund + " (" + geschaut + " watched).",
                "Werbevideos: " + grund + " (" + geschaut + " geschaut)."));
        return false;
    }

    // --- Tageszaehler: beginnt um 02:00 neu ---------------------------------

    private static LocalDate spielTag() {
        return de.hustlebot.core.Zeitzone.jetzt().minusHours(2).toLocalDate();
    }

    static int heuteGeschaut() {
        try {
            if (!Files.exists(ZAEHLER)) return 0;
            for (String z : de.hustlebot.core.Dateien.zeilen(ZAEHLER)) {
                String[] t = z.trim().split("\\s+");
                if (t.length == 2 && t[0].equals(spielTag().toString())) return Integer.parseInt(t[1]);
            }
        } catch (Exception e) {
            Log.debug("Nahrung", "Video-Zaehler nicht lesbar: " + e.getMessage());
        }
        return 0;
    }

    private static void heuteMitzaehlen() {
        try {
            Files.writeString(ZAEHLER, spielTag() + " " + (heuteGeschaut() + 1) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.warn("Nahrung", Sprache.t("Video counter could not be saved: " + e.getMessage(),
                    "Video-Zaehler liess sich nicht speichern: " + e.getMessage()));
        }
    }
}
