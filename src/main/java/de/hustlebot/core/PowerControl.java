package de.hustlebot.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * Faehrt den Rechner herunter, auf dem der Bot laeuft.
 *
 * Dafuer wird der Windows-Befehl "shutdown" benutzt. Der fuehrt sauber
 * herunter: Windows schliesst die Programme, schreibt offene Dateien weg und
 * schaltet dann ab. Ein Geraet, das den Einschaltknopf drueckt, kann das nicht
 * garantieren -- drueckt es zu lange, ist es ein harter Stromschlag.
 *
 * Die Wartezeit ist Absicht: solange sie laeuft, laesst sich der Vorgang mit
 * cancel() zurueckholen. Wer sich vertippt, hat also noch eine Minute Zeit.
 */
public final class PowerControl {

    private PowerControl() {
    }

    /** Ergebnis eines Versuchs, mit einer Erklaerung in Alltagssprache. */
    public record Result(boolean ok, String message) {}

    /**
     * Faehrt den Rechner nach der angegebenen Zahl Sekunden herunter.
     *
     * @param seconds 0 heisst sofort. Mehr als 10 Stunden laesst Windows nicht zu.
     */
    public static Result shutdown(int seconds, String reason) {
        int delay = Math.max(0, Math.min(36_000, seconds));
        Log.warn("Rechner", Sprache.t("Shutdown requested (" + delay + " seconds): " + reason,
                "Herunterfahren angefordert (" + delay + " Sekunden): " + reason));

        return run("Herunterfahren",
                "shutdown", "/s", "/t", String.valueOf(delay), "/c", cut(reason));
    }

    /**
     * Startet den Rechner neu -- nicht den Bot, den ganzen Rechner.
     *
     * Der Unterschied zu {@link #shutdown} ist ein einziger Buchstabe im
     * Windows-Befehl (/r statt /s), fuer Autor aber ein grosser: Nach einem
     * Neustart faehrt der Rechner von selbst wieder hoch, die geplante
     * Aufgabe startet den Bot, und alles laeuft weiter. Nach einem
     * Herunterfahren bleibt er aus, bis jemand den Knopf drueckt -- und
     * wenn Autor nicht zuhause ist, ist das niemand.
     *
     * Genau deshalb hat er den Befehl gewuenscht: "!restart command in
     * discord hinzufügen womit ich meinen PC Restarten kann. Wenn nötig."
     */
    public static Result restart(int seconds, String reason) {
        int delay = Math.max(0, Math.min(36_000, seconds));
        Log.warn("Rechner", Sprache.t("Restart requested (" + delay + " seconds): " + reason,
                "Neustart angefordert (" + delay + " Sekunden): " + reason));

        return run("Neustart",
                "shutdown", "/r", "/t", String.valueOf(delay), "/c", cut(reason));
    }

    /** Nimmt ein angefordertes Herunterfahren zurueck, solange es noch wartet. */
    public static Result cancel() {
        Log.info("Rechner", Sprache.t("Shutdown is cancelled.", "Herunterfahren wird zurueckgenommen."));
        Result result = run("Zuruecknehmen", "shutdown", "/a");

        // Lief gar kein Herunterfahren, meldet Windows einen Fehler. Das ist
        // kein Grund zur Sorge, sondern soll verstaendlich erklaert werden.
        if (!result.ok()) {
            return new Result(false, Sprache.t(
                    "No shutdown was requested at all.",
                    "Es war gar kein Herunterfahren angefordert."));
        }
        return new Result(true, Sprache.t(
                "The shutdown was called off.",
                "Das Herunterfahren wurde zurueckgenommen."));
    }

    // ------------------------------------------------------------------
    //  Bildschirm
    // ------------------------------------------------------------------

    /** Windows-Nachricht "Systembefehl". */
    private static final int WM_SYSCOMMAND = 0x0112;
    /** Systembefehl "Monitor schalten". */
    private static final int SC_MONITORPOWER = 0xF170;
    /** An alle Fenster gleichzeitig. */
    private static final int HWND_BROADCAST = 0xFFFF;

    /** Merkt sich, ob der Bildschirm zuletzt dunkelgeschaltet wurde. */
    private static volatile boolean screenOff = false;

    /**
     * Schaltet den Bildschirm dunkel oder wieder hell -- je nachdem, was
     * zuletzt war. Der Rechner laeuft dabei ganz normal weiter, der Bot also
     * auch.
     *
     * Windows bekommt dafuer dieselbe Nachricht, die es auch nach der
     * eingestellten Leerlaufzeit an den Monitor schickt.
     *
     * NICHT ueber PowerShell: Der erste Versuch rief dort ein kleines
     * C#-Schnipsel auf, und dabei gingen die Anfuehrungszeichen um
     * "user32.dll" auf dem Weg durch die Befehlszeile verloren -- Windows
     * meldete "Der Name 'user32' ist nicht vorhanden". Java spricht dieselbe
     * Schnittstelle ueber JNA direkt an, das ist kuerzer und kann nicht an
     * Anfuehrungszeichen scheitern.
     */
    public static Result toggleMonitor() {
        return screenOff ? monitorOn() : monitorOff();
    }

    /** Schaltet den Bildschirm dunkel. */
    public static Result monitorOff() {
        try {
            sendMonitorCommand(2);
            screenOff = true;
            Log.info("Rechner", Sprache.t("Screen switched dark. The bot keeps running.",
                    "Bildschirm dunkelgeschaltet. Der Bot laeuft weiter."));
            return new Result(true, de.hustlebot.core.Sprache.t("The screen is dark.", "Der Bildschirm ist dunkel."));
        } catch (Throwable t) {
            Log.warn("Rechner", Sprache.t("Screen could not be switched off: " + t.getMessage(),
                    "Bildschirm liess sich nicht ausschalten: " + t.getMessage()));
            return new Result(false, de.hustlebot.core.Sprache.t("The screen could not be switched off: ", "Der Bildschirm liess sich nicht ausschalten: ")
                    + t.getMessage());
        }
    }

    /**
     * Schaltet den Bildschirm wieder an.
     *
     * Die Nachricht allein genuegt vielen Rechnern nicht -- Windows weckt den
     * Monitor erst bei einer echten Eingabe. Deshalb wird zusaetzlich die Maus
     * um einen Punkt bewegt und wieder zurueck. Das ist zu wenig, um im Spiel
     * etwas auszuloesen, reicht Windows aber als Lebenszeichen.
     */
    public static Result monitorOn() {
        try {
            sendMonitorCommand(-1);
            nudgeMouse();
            screenOff = false;
            Log.info("Rechner", Sprache.t("Screen switched on again.", "Bildschirm wieder eingeschaltet."));
            return new Result(true, de.hustlebot.core.Sprache.t("The screen is on again.", "Der Bildschirm ist wieder an."));
        } catch (Throwable t) {
            Log.warn("Rechner", Sprache.t("Screen could not be switched on: " + t.getMessage(),
                    "Bildschirm liess sich nicht einschalten: " + t.getMessage()));
            return new Result(false, de.hustlebot.core.Sprache.t("The screen could not be switched on: ", "Der Bildschirm liess sich nicht einschalten: ")
                    + t.getMessage());
        }
    }

    /** Ist der Bildschirm gerade dunkelgeschaltet? */
    public static boolean isScreenOff() {
        return screenOff;
    }

    /** 2 schaltet aus, -1 schaltet an. */
    private static void sendMonitorCommand(int mode) {
        com.sun.jna.platform.win32.User32.INSTANCE.SendMessage(
                new com.sun.jna.platform.win32.WinDef.HWND(
                        com.sun.jna.Pointer.createConstant(HWND_BROADCAST)),
                WM_SYSCOMMAND,
                new com.sun.jna.platform.win32.WinDef.WPARAM(SC_MONITORPOWER),
                new com.sun.jna.platform.win32.WinDef.LPARAM(mode));
    }

    /** Ein Ruck mit der Maus, damit Windows den Monitor wirklich aufweckt. */
    private static void nudgeMouse() {
        try {
            java.awt.Robot robot = new java.awt.Robot();
            java.awt.Point at = java.awt.MouseInfo.getPointerInfo().getLocation();
            robot.mouseMove(at.x + 1, at.y);
            Thread.sleep(60);
            robot.mouseMove(at.x, at.y);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.debug("Rechner", "Maus liess sich nicht bewegen: " + e.getMessage());
        }
    }

    private static Result run(String what, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output = readAll(process);
            boolean done = process.waitFor(15, TimeUnit.SECONDS);

            if (!done) {
                process.destroyForcibly();
                return new Result(false, what + de.hustlebot.core.Sprache.t(" did not answer.", " hat nicht geantwortet."));
            }
            if (process.exitValue() != 0) {
                String detail = output.isBlank() ? (Sprache.t("error number ", "Fehlernummer ") + process.exitValue())
                                                 : output.trim();
                Log.warn("Rechner", Sprache.t(what + " failed: " + detail, what + " fehlgeschlagen: " + detail));
                return new Result(false, detail);
            }
            return new Result(true, Sprache.t(what + " was triggered.", what + " wurde angestossen."));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, Sprache.t(what + " was interrupted.", what + " wurde unterbrochen."));
        } catch (Exception e) {
            Log.error("Rechner", Sprache.t(what + " not possible", what + " nicht moeglich"), e);
            return new Result(false, e.getMessage());
        }
    }

    private static String readAll(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append(' ');
            return text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Windows nimmt fuer den Hinweistext hoechstens 512 Zeichen. */
    private static String cut(String text) {
        if (text == null || text.isBlank()) return "Hustle Castle Bot";
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
