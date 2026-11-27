package de.hustlebot.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ein PowerShell, das offen bleibt und Bilder vorliest.
 *
 * ------------------------------------------------------------------
 *  Warum
 * ------------------------------------------------------------------
 *
 * Gemessen am 4. September 2026: ein Lesevorgang dauert 335
 * Millisekunden, davon 160 blosser PowerShell-Start. Fast die Haelfte
 * geht fuer Hochfahren, WinRT laden und Beenden drauf -- jedes Mal aufs
 * Neue, obwohl sich daran nie etwas aendert.
 *
 * Die Arena liest in einer Runde ein Dutzend Mal. Das sind zwei Sekunden
 * verschenkte Zeit je Runde, und die fehlen genau dann, wenn die
 * Rundenuhr ablaeuft und noch angegriffen werden muss.
 *
 * ------------------------------------------------------------------
 *  Warum es trotzdem den alten Weg noch gibt
 * ------------------------------------------------------------------
 *
 * Ein Dauerlaeufer kann klemmen. Das PowerShell kann abstuerzen, die
 * Leitung kann zugehen, ein Auftrag kann haengenbleiben. Passiert das,
 * gibt diese Klasse null zurueck -- und Texterkennung macht es dann so,
 * wie es immer war: ein eigener Vorgang je Blick.
 *
 * Langsamer als vorher kann es dadurch nie werden. Schlimmstenfalls ist
 * es genauso schnell wie bisher.
 *
 * ------------------------------------------------------------------
 *  Warum das Skript von selbst aufhoert
 * ------------------------------------------------------------------
 *
 * Es liest von der Leitung. Stirbt der Bot -- auch hart abgeschossen,
 * und genau so wird er bei jedem Neustart beendet --, schliesst Windows
 * die Leitung, und das Skript beendet sich. Sonst haette sich mit jedem
 * Neustart ein weiteres PowerShell angesammelt.
 */
final class DauerLeser {

    private static final Path SKRIPT = Path.of("texterkennung-dauer.ps1");

    /**
     * Freunde-Fassung: Das Skript steckt auch in der Jar (Ordner "skripte").
     * Im fertigen Paket wird es vor jedem Start auf Jar-Stand gebracht (fehlt
     * oder weicht ab -> schreiben), beim Entwickeln nur angelegt, wenn es fehlt
     * -- siehe UpdateNachschub.skriptAufJarStand (Sicherheits-Update 16.10.).
     */
    private static void skriptSicherstellen() {
        UpdateNachschub.skriptAufJarStand(SKRIPT);
    }


    /** Diese Zeile schliesst jeden Auftrag ab. */
    private static final String ENDE = "--ENDE--";
    private static final String BEREIT = "--BEREIT--";
    private static final String FEHLER = "--FEHLER--";

    /**
     * So lange darf ein einzelner Auftrag hoechstens dauern.
     *
     * Ein Lesevorgang liegt bei gut 300 Millisekunden. Zehn Sekunden sind
     * also reichlich -- wer so lange braucht, klemmt.
     */
    private static final long AUFTRAG_ZEITGRENZE_MS = 10_000;

    /** Und so lange auf die Bereitmeldung beim Hochfahren. */
    private static final long START_ZEITGRENZE_MS = 20_000;

    private static Process prozess;
    private static BufferedWriter hinein;
    private static BlockingQueue<String> heraus;
    private static Thread leser;

    /** Nach so vielen Fehlschlaegen wird gar nicht mehr versucht. */
    private static final int GENUG_FEHLSCHLAEGE = 3;
    private static int fehlschlaege = 0;

    private DauerLeser() {
    }

    /**
     * Liest ein Bild ueber das offene PowerShell.
     *
     * @return die erkannten Zeilen, oder null wenn der Dauerlaeufer gerade
     *         nicht zu gebrauchen ist -- dann muss der Aufrufer den alten
     *         Weg gehen
     */
    static synchronized List<String> lies(Path bild, String sprache) {
        zuletzt = System.currentTimeMillis();
        Leerlauf.beobachten();
        if (fehlschlaege >= GENUG_FEHLSCHLAEGE) return null;
        if (!Files.exists(SKRIPT)) return null;

        if (!laeuft() && !starten()) return null;

        try {
            // Alte Reste wegwerfen. Ist ein frueherer Auftrag in die
            // Zeitgrenze gelaufen, koennte seine Antwort noch unterwegs
            // sein -- die gehoert nicht zu diesem Auftrag.
            heraus.clear();

            hinein.write((sprache == null ? "" : sprache) + "\t"
                    + bild.toAbsolutePath() + "\n");
            hinein.flush();

            List<String> zeilen = new ArrayList<>();
            long schluss = System.currentTimeMillis() + AUFTRAG_ZEITGRENZE_MS;

            while (true) {
                long rest = schluss - System.currentTimeMillis();
                if (rest <= 0) {
                    Log.warn("Text", Sprache.t("The open text recognition did not answer. " + "It is restarted, this time reading one by one.",
                            "Die offene Texterkennung hat nicht geantwortet. "
                            + "Sie wird neu gestartet, gelesen wird diesmal einzeln."));
                    beenden();
                    fehlschlaege++;
                    return null;
                }
                String zeile = heraus.poll(rest, TimeUnit.MILLISECONDS);
                if (zeile == null) continue;

                if (ENDE.equals(zeile)) break;
                if (zeile.startsWith(FEHLER)) {
                    Log.debug("Text", "Offene Texterkennung meldet: " + zeile);
                    continue;
                }
                // Eine verspaetete Bereitmeldung gehoert nicht zum Ergebnis.
                if (BEREIT.equals(zeile)) continue;

                String sauber = zeile.trim();
                if (!sauber.isEmpty()) zeilen.add(sauber);
            }

            // Ein Auftrag, der durchlief, setzt die Fehlerzaehlung zurueck.
            fehlschlaege = 0;
            return zeilen;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            Log.warn("Text", Sprache.t("The open text recognition failed: " + e.getMessage() + " -- reading one by one again.",
                    "Die offene Texterkennung ist ausgefallen: " + e.getMessage()
                    + " -- es wird wieder einzeln gelesen."));
            beenden();
            fehlschlaege++;
            return null;
        }
    }

    /** Wann zuletzt gelesen wurde (fuer den Leerlauf-Aufraeumer). */
    private static volatile long zuletzt = System.currentTimeMillis();

    /**
     * Sparsam (Autor 20.11.): das offene PowerShell nach {@code nachMs} ohne Auftrag beenden -- es haelt die
     * Windows-Texterkennung im Speicher. Der naechste Auftrag startet es neu (Start ~0,2 s, siehe Texterkennung).
     */
    static synchronized boolean beendenWennUnbenutzt(long jetzt, long nachMs) {
        if (!laeuft() || jetzt - zuletzt < nachMs) return false;
        Log.debug("Text", "Offene Texterkennung " + (jetzt - zuletzt) / 60_000 + " min nicht gebraucht -- beendet (Speicher frei).");
        beenden();
        return true;
    }

    private static boolean laeuft() {
        return prozess != null && prozess.isAlive() && hinein != null && heraus != null;
    }

    private static boolean starten() {
        beenden();
        skriptSicherstellen();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", SKRIPT.toAbsolutePath().toString());
            // Die Fehlerausgabe mit in dieselbe Leitung. Sie separat
            // aufzumachen und nie zu leeren war schon einmal der Grund
            // fuer eine Blockade -- der Puffer laeuft voll, PowerShell
            // wartet beim Schreiben, und Java wartet auf die Antwort.
            pb.redirectErrorStream(true);
            prozess = pb.start();

            hinein = new BufferedWriter(new OutputStreamWriter(
                    prozess.getOutputStream(), StandardCharsets.UTF_8));
            heraus = new ArrayBlockingQueue<>(500);

            // EIGENER FADEN ZUM MITLESEN.
            //
            // Ohne den muesste der Aufrufer selbst lesen -- und ein
            // blockierendes readLine() laesst sich nicht mit einer
            // Zeitgrenze versehen. Klemmt PowerShell, haenge der ganze
            // Bot. So wartet der Aufrufer nur auf die Schlange und kommt
            // nach der Zeitgrenze auf jeden Fall wieder heraus.
            Process p = prozess;
            BlockingQueue<String> ziel = heraus;
            leser = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String z;
                    while ((z = r.readLine()) != null) {
                        // Laeuft die Schlange voll, weil niemand abholt,
                        // wird das Aelteste weggeworfen statt zu blockieren.
                        if (!ziel.offer(z)) {
                            ziel.poll();
                            ziel.offer(z);
                        }
                    }
                } catch (Exception ignored) {
                    // Leitung zu -- das ist beim Beenden der Normalfall.
                }
            }, "Texterkennung-Mitleser");
            leser.setDaemon(true);
            leser.start();

            // Auf die Bereitmeldung warten. Das einmalige Laden der
            // WinRT-Typen dauert; es soll nicht dem ersten Auftrag
            // angelastet werden.
            long schluss = System.currentTimeMillis() + START_ZEITGRENZE_MS;
            while (System.currentTimeMillis() < schluss) {
                String z = heraus.poll(500, TimeUnit.MILLISECONDS);
                if (z == null) continue;
                if (BEREIT.equals(z)) {
                    Log.info("Text", de.hustlebot.core.Sprache.t("Text recognition stays open now. That saves about 160 milliseconds per look.", "Die Texterkennung bleibt jetzt offen. Das spart "
                            + "etwa 160 Millisekunden bei jedem Blick."));
                    return true;
                }
                if (z.startsWith("--STARTFEHLER--")) {
                    Log.warn("Text", Sprache.t("The open text recognition could not be " + "set up: " + z + " -- reading one by one.",
                            "Die offene Texterkennung liess sich nicht "
                            + "einrichten: " + z + " -- es wird einzeln gelesen."));
                    beenden();
                    fehlschlaege = GENUG_FEHLSCHLAEGE;   // gar nicht erst wieder
                    return false;
                }
            }

            Log.warn("Text", Sprache.t("The open text recognition did not report back. " + "Reading one by one.",
                    "Die offene Texterkennung hat sich nicht gemeldet. "
                    + "Es wird einzeln gelesen."));
            beenden();
            fehlschlaege++;
            return false;

        } catch (Exception e) {
            Log.warn("Text", Sprache.t("The open text recognition could not be started: " + e.getMessage() + " -- reading one by one.",
                    "Die offene Texterkennung liess sich nicht starten: "
                    + e.getMessage() + " -- es wird einzeln gelesen."));
            beenden();
            fehlschlaege++;
            return false;
        }
    }

    /** Macht das PowerShell zu. Auch beim Beenden des Bots. */
    static synchronized void beenden() {
        try {
            if (hinein != null) {
                try {
                    hinein.write("--QUIT--\n");
                    hinein.flush();
                } catch (Exception ignored) {
                    // Schon zu -- dann eben hart.
                }
                hinein.close();
            }
        } catch (Exception ignored) {
            // Beim Aufraeumen ist ein Fehler ohne Belang.
        }
        if (prozess != null) {
            try {
                if (!prozess.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    prozess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                prozess.destroyForcibly();
            }
        }
        prozess = null;
        hinein = null;
        heraus = null;
        leser = null;
    }

    static {
        // Beim ordentlichen Beenden mit aufraeumen. Beim harten Abschuss
        // greift das nicht -- dafuer beendet sich das Skript von selbst,
        // sobald die Leitung zugeht.
        Runtime.getRuntime().addShutdownHook(
                new Thread(DauerLeser::beenden, "Texterkennung-Aufraeumer"));
    }
}
