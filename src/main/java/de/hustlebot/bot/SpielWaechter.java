package de.hustlebot.bot;

import de.hustlebot.core.GameLauncher;
import de.hustlebot.core.GameWindow;
import de.hustlebot.core.Log;
import de.hustlebot.core.Ordner;
import de.hustlebot.core.Schalterdatei;
import de.hustlebot.core.Sprache;

import java.util.function.Consumer;

/**
 * Der Bot laeuft nur zusammen mit Hustle Castle.
 *
 * Autor, 13.09.: "es soll nur laufen wenn hustle castle laeuft". Gestartet
 * wird der Bot beim Hochfahren (wenn der Autostart an ist, siehe
 * !autostart) oder ueber das Desktop-Symbol "Hustle Castle Bot" -- und er
 * oeffnet das Spiel dann selbst. Ist Hustle Castle danach eine Weile zu,
 * beendet er sich.
 *
 * Von selbst angehen, SOBALD jemand das Spiel oeffnet, kann er nicht: Dafuer
 * muesste etwas im Hintergrund aufpassen, und genau das wollte Autor nicht.
 *
 * WANN ER SICH BEENDET
 *
 *   - Das Spiel war offen und ist seit fuenf Minuten zu.
 *   - Oder es war seit dem Start noch GAR NICHT zu sehen, eine Viertelstunde
 *     lang. Laenger, weil der Launcher nach dem Hochfahren manchmal erst ein
 *     Update laedt.
 *
 * WANN NICHT
 *
 *   - Solange ein Modus laeuft oder pausiert. Stuerzt das Spiel mitten im
 *     Lauf ab, startet der Motor es selbst neu -- dem soll hier niemand den
 *     Stecker ziehen.
 *   - Solange der Bot das Spiel gerade startet (!start, !spiel, Selbststart
 *     nach dem Hochfahren): Dann ist es eine Minute lang noch nicht da, und
 *     das ist Absicht.
 */
public final class SpielWaechter implements Runnable {

    /** So oft wird nachgesehen. */
    private static final long TAKT_MS = 15_000;
    /** Spiel war da und ist jetzt so lange zu: Bot beendet sich. */
    public static final long OHNE_SPIEL_MS = 5 * 60_000L;
    /** Spiel war seit dem Start noch nie zu sehen: so lange wird gewartet. */
    public static final long NIE_GESEHEN_MS = 15 * 60_000L;
    /** Standard aus -- die Freunde-Bots aendern sich nicht. Gelesen einmal beim Start. */
    private static final boolean BLEIBT_OFFEN =
            new Schalterdatei(Ordner.bei("bot-bleibt-offen.txt"), "Bot-bleibt-offen", false).lesen();

    private final GameWindow fenster;
    private final BotEngine motor;
    private final Consumer<String> abschied;

    /** War Hustle Castle seit dem Start schon einmal offen? */
    private volatile boolean gesehen = false;
    /** Stand der letzten Pruefung. */
    private volatile boolean spielDa = false;
    /** Seit wann das Spiel fehlt und das zaehlt. 0 = gerade nicht. */
    private volatile long wegSeit = 0;

    /**
     * @param abschied bekommt den Grund, kurz bevor sich der Bot beendet --
     *                 fuer eine Nachricht in Discord
     */
    public SpielWaechter(GameWindow fenster, BotEngine motor, Consumer<String> abschied) {
        this.fenster = fenster;
        this.motor = motor;
        this.abschied = abschied;
    }

    /** Ist Hustle Castle offen (Stand der letzten Pruefung)? */
    public boolean spielOffen() {
        return spielDa;
    }

    /**
     * In wie vielen Millisekunden sich der Bot beendet -- oder -1, wenn
     * gerade keine Uhr laeuft. Fuer das Bot-Fenster.
     */
    public long beendetInMs() {
        long seit = wegSeit;
        if (seit == 0) return -1;
        return Math.max(0, frist() - (System.currentTimeMillis() - seit));
    }

    private long frist() {
        return gesehen ? OHNE_SPIEL_MS : NIE_GESEHEN_MS;
    }

    @Override
    public void run() {
        while (true) {
            try {
                pruefen();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Ein Fehler beim Nachsehen darf den Waechter nicht still
                // stilllegen -- dann liefe der Bot doch wieder unbemerkt weiter.
                Log.warn("Waechter", Sprache.t("The check failed: " + e.getMessage(),
                        "Nachsehen ging schief: " + e.getMessage()));
            }
            try {
                Thread.sleep(TAKT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pruefen() throws InterruptedException {
        spielDa = fenster.istDa();
        if (spielDa) {
            if (!gesehen) Log.info("Waechter", Sprache.t("Hustle Castle is open.", "Hustle Castle ist offen."));
            else if (wegSeit != 0) Log.info("Waechter", Sprache.t("Hustle Castle is open again. The bot stays.",
                    "Hustle Castle ist wieder offen. Der Bot bleibt."));
            gesehen = true;
            wegSeit = 0;
            return;
        }

        // Laeuft ein Modus oder startet der Bot das Spiel gerade? Dann kuemmert
        // sich der Motor bzw. der Spielstart -- die Uhr laeuft nicht.
        BotEngine.State z = motor.state();
        if (z == BotEngine.State.LAEUFT || z == BotEngine.State.PAUSIERT
                || GameLauncher.startLaeuft()) {
            wegSeit = 0;
            return;
        }

        // Autor 30.10.: "Der PC-Bot soll sich nicht nach 5 Minuten selbst beenden" -- 02:18 Spiel zu fuer den Server, 02:23 Bot
        // weg, und der Rueckfall-Befehl um 02:41 blieb liegen. Datei bot-bleibt-offen.txt = "an": nie selbst beenden.
        if (BLEIBT_OFFEN) {
            wegSeit = 0;
            return;
        }

        long jetzt = System.currentTimeMillis();
        if (wegSeit == 0) {
            wegSeit = jetzt;
            Log.info("Waechter", Sprache.t(
                    "Hustle Castle is " + (gesehen ? "closed" : "not open yet")
                    + ". If it stays that way for " + (frist() / 60_000) + " minutes, the bot closes.",
                    "Hustle Castle ist " + (gesehen ? "zu" : "noch nicht offen")
                    + ". Bleibt das " + (frist() / 60_000) + " Minuten so, beendet sich der Bot."));
            return;
        }
        if (jetzt - wegSeit < frist()) return;

        long minuten = frist() / 60_000;
        String text = gesehen
                ? Sprache.t("Hustle Castle has been closed for " + minuten + " minutes – the bot "
                            + "is closing.",
                            "Hustle Castle ist seit " + minuten + " Minuten zu – der Bot "
                            + "beendet sich.")
                : Sprache.t("Hustle Castle did not open within " + minuten + " minutes – the "
                            + "bot is closing.",
                            "Hustle Castle ist seit dem Start nicht aufgegangen (" + minuten
                            + " Minuten) – der Bot beendet sich.");
        text += Sprache.t(
                "\n\nStart it again with the desktop icon **Hustle Castle Bot**, or at the "
                + "next start-up if autostart is on (`!autostart`).",
                "\n\nWieder starten: am Rechner das Desktop-Symbol **Hustle Castle Bot** – oder "
                + "beim naechsten Hochfahren, wenn der Autostart an ist (`!autostart`).");

        Log.info("Waechter", Sprache.t("Hustle Castle has been gone for " + minuten + " minutes. The bot "
                + "is closing.",
                "Hustle Castle ist " + minuten + " Minuten nicht da. Der Bot "
                + "beendet sich."));
        try {
            abschied.accept(text);
        } catch (Exception e) {
            Log.warn("Waechter", Sprache.t("The message to Discord could not be sent: " + e.getMessage(),
                    "Die Nachricht nach Discord ging nicht: " + e.getMessage()));
        }
        Thread.sleep(2500);   // die Nachricht soll noch hinausgehen
        System.exit(0);
    }
}
