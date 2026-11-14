package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;


/**
 * Lesetest Nahrung (Autor 21.10.: "testen, ob der Bot den Nahrungsvorrat ablesen kann, egal wie der Hintergrund
 * aussieht -- in der Burg herumwischen, dass sich der Hintergrund veraendert, und die Zahlen immer ablesen"). Mia hatte
 * oft "Die Nahrung oben in der Burg ist nicht lesbar".
 *
 * "!lesetest 30": nur im Leerlauf und in der Burg. Je Durchgang die Burg zufaellig verschieben (ziehen, kein Tippen),
 * 1,5 s warten, die Nahrung lesen wie vor jeder Bezahlung (Nahrungskisten.nahrungLesen) und den Ausschnitt der Leiste nach
 * logs\lesetest speichern. Am Ende: wie oft lesbar, welche Werte (die Nahrung aendert sich dabei nicht -- es muss immer
 * derselbe Wert sein).
 */
public final class Lesetest {

    private Lesetest() { }

    private static volatile boolean laeuft;

    /** Klickt der Lesetest gerade in der Burg? (Code-Pruefung 22.10.: dann startet der 02:00-Auto-Start nicht.) */
    public static boolean laeuft() {
        return laeuft;
    }

    public static synchronized String starten(BotEngine motor, int anzahl) {
        if (laeuft) return Sprache.t("The read test is already running.", "Der Lesetest laeuft schon.");
        if (motor.state() == BotEngine.State.LAEUFT) {
            return Sprache.t("The bot is playing -- stop it first (!stop), then !readtest.",
                    "Der Bot spielt gerade -- erst anhalten (!stop), dann !lesetest.");
        }
        int n = Math.max(2, Math.min(100, anzahl));
        laeuft = true;
        BotContext ctx = motor.context();
        Thread t = new Thread(() -> {
            try {
                lauf(ctx, n);
            } catch (Exception e) {
                Log.warn("Lesetest", e.getMessage());
            } finally {
                laeuft = false;
            }
        }, "lesetest");
        t.setDaemon(true);
        t.start();
        return Sprache.t("Read test started: " + n + " times move the castle and read the food.",
                "Lesetest gestartet: " + n + " Mal die Burg verschieben und die Nahrung lesen.");
    }

    private static void lauf(BotContext ctx, int n) throws Exception {
        Path ordner = Ordner.bei("logs").resolve("lesetest");
        java.util.Random zufall = new java.util.Random();
        Map<Long, Integer> werte = new TreeMap<>();
        // Autor 21.10.: auch Gold, Holz, Eisen (Stahl), Mana, Mythril, Diamanten, Pokale -- je Rohstoff: Wert -> wie oft
        Map<String, Map<Long, Integer>> rohstoffe = new java.util.LinkedHashMap<>();
        Map<String, Integer> rohstoffFehlt = new java.util.LinkedHashMap<>();
        int unlesbar = 0, gemacht = 0;
        for (int i = 1; i <= n && !BotEngine.stoppAngefordert(); i++) {
            ctx.matcher().naechstesBildGruendlich();   // Code-Pruefung 22.10.: Stoerfenster nicht der Sparregel opfern
            Mat bild = ctx.refresh();
            if (bild == null) break;
            // Erzaehltexte ("My lord, ...") verdecken unten den Kartenknopf, nach langer Pause kommt "are you still here?"
            // (22.10. nachts), nach dem Neuladen Belohnung/Werbung -- erst alles wegdruecken (wie der Motor).
            for (int k = 0; k < 8; k++) {
                boolean weg = Stoerfenster.afkUpdate(bild, ctx, "Lesetest")
                        || Stoerfenster.belohnungWegklicken(bild, ctx, "Lesetest")
                        || Stoerfenster.werbefenster(bild, ctx, "Lesetest")
                        || ctx.clickIfVisibleIfKnown(bild, "dialog_weiter",
                                Sprache.t("Story text -- tap it away", "Erzaehltext -- wegdruecken"));
                if (!weg) break;
                ctx.sleep(1500);
                ctx.matcher().naechstesBildGruendlich();
                bild = ctx.refresh();
                if (bild == null) return;
            }
            boolean burg = ctx.visibleIfKnown(bild, "map_button") || ctx.visibleIfKnown(bild, "map_button_alt");
            if (!burg) {
                Log.warn("Lesetest", Sprache.t("Not in the castle -- stopping.", "Nicht in der Burg -- Abbruch."));
                break;
            }
            int w = ctx.width(), h = ctx.height();
            if (i > 1) {
                // Burg verschieben: von der Mitte aus ziehen (kein Tippen), damit hinter der Leiste ein anderer Hintergrund steht.
                int x0 = w / 2, y0 = (int) (h * 0.55);
                int dx = zufall.nextInt(601) - 300, dy = zufall.nextInt(241) - 120;
                ctx.input().ziehenSofort(x0, y0, x0 + dx, y0 + dy);
                ctx.sleep(1500);
            }
            OptionalLong wert = Nahrungskisten.nahrungLesen(ctx);
            gemacht++;
            BufferedImage b = ctx.frame();
            if (b != null) {
                de.hustlebot.core.Beleg.schreiben(b.getSubimage(0, 0, w, (int) (h * 0.20)), "png",   // 20 %: mit den Pokalen (22.10.)
                        ordner.resolve(String.format("burg_%02d.png", i)).toFile());
            }
            if (wert.isPresent()) werte.merge(wert.getAsLong(), 1, Integer::sum);
            else unlesbar++;
            Mat jetzt = ctx.refresh();
            Map<String, Long> alle = jetzt == null ? Map.of() : Burgstand.zahlen(jetzt, ctx);
            for (String name : Burgstand.ROHSTOFFE.values()) {
                Long v = alle.get(name);
                if (v == null) rohstoffFehlt.merge(name, 1, Integer::sum);
                else rohstoffe.computeIfAbsent(name, k -> new TreeMap<>()).merge(v, 1, Integer::sum);
            }
            Log.info("Lesetest", Sprache.t("Picture " + i + ": resources " + alle, "Bild " + i + ": Rohstoffe " + alle));
            Log.info("Lesetest", Sprache.t("Picture " + i + ": food " + (wert.isPresent() ? wert.getAsLong() : "not readable"),
                    "Bild " + i + ": Nahrung " + (wert.isPresent() ? wert.getAsLong() : "unlesbar")));
        }
        int gelesen = gemacht - unlesbar;
        for (String name : Burgstand.ROHSTOFFE.values()) {
            Map<Long, Integer> w = rohstoffe.getOrDefault(name, Map.of());
            int fehlt = rohstoffFehlt.getOrDefault(name, 0);
            Log.info("Lesetest", Sprache.t(name + ": " + (gemacht - fehlt) + " of " + gemacht + " readable, values " + w
                            + (w.size() <= 1 ? "" : " -- DIFFERENT values"),
                    name + ": " + (gemacht - fehlt) + " von " + gemacht + " lesbar, Werte " + w
                            + (w.size() <= 1 ? "" : " -- VERSCHIEDENE Werte")));
        }
        Log.info("Lesetest", Sprache.t("Read test done: " + gelesen + " of " + gemacht + " readable, values " + werte
                        + (werte.size() <= 1 ? " -- always the same." : " -- DIFFERENT values (misreads)."),
                "Lesetest fertig: " + gelesen + " von " + gemacht + " lesbar, Werte " + werte
                        + (werte.size() <= 1 ? " -- immer derselbe." : " -- VERSCHIEDENE Werte (Lesefehler).")));
    }
}
