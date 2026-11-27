package de.hustlebot.bot;

import de.hustlebot.core.Log;
import de.hustlebot.core.Sprache;
import org.opencv.core.Mat;

/**
 * Der Arena-Beobachter als eigener Faden (Autor 21.10.): wenn der Spieler selbst Arena spielt, schaut der Bot zu und
 * schreibt jeden Kampf in den Notizblock -- daraus entstehen die automatischen Listen. Er klickt NIE, holt das Spiel nie
 * nach vorn und schaut nur, solange der Bot selbst nicht spielt. Die Erkennung steht in ArenaMode.beobachtenSchritt.
 * Bewusst im Bot und nicht als eigenes Foto-Skript: der Virenschutz blockt Foto-Schleifen fremder Skripte.
 */
public final class ArenaBeobachter {

    private ArenaBeobachter() { }

    private static volatile Thread faden;

    /** So oft wird hingesehen. */
    static final long TAKT_MS = 800;
    /** So oft und so lange nach einem Klick von Autor. */
    static final long SCHNELL_MS = 60, SCHNELL_NACH_KLICK_MS = 1_500;
    /** So oft mit eingeschalteter Schicht (nur Autors PC). */
    static final long TAKT_SCHICHT_MS = 50;
    private static long blicke, blickeMs, blickZeileUm;

    /** Soll die Arena-Schicht zu? Ja, wenn der Bot gerade selbst einen anderen Modus als Arena spielt (und die Schicht an ist). */
    static boolean schichtBeenden(BotEngine.State zustand, GameMode modus, boolean schichtAn) {
        return schichtAn && zustand == BotEngine.State.LAEUFT && modus != null && !(modus instanceof ArenaMode);
    }

    public static synchronized void starten(BotEngine motor) {
        if (faden != null || motor == null) return;
        if (!(motor.modus("arena") instanceof ArenaMode arena)) return;
        BotContext blick = motor.context().zumZusehen();
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Autor 30.10.: nach einem Klick gleich und kurz sehr oft hinsehen: so ist das Vergleichsfenster sofort erkannt,
                    // ohne dass der PC die ganze Zeit so schnell rechnet.
                    var klick = de.hustlebot.schicht.ArenaStand.letzterKlick();
                    boolean schnell = klick != null && System.currentTimeMillis() - klick.um() < SCHNELL_NACH_KLICK_MS;
                    // Autor 30.10.: "In der ersten Millisekunde, wo er die Pyramide erkennt": mit Schicht 20-mal je Sekunde hinsehen.
                    boolean schicht = de.hustlebot.core.ArenaSchalter.an();
                    de.hustlebot.schicht.ArenaStand.aufKlickWarten(schicht ? TAKT_SCHICHT_MS : schnell ? SCHNELL_MS : TAKT_MS);
                    if (!ArenaMode.beobachterAn()) {
                        Thread.sleep(4_000);
                        continue;
                    }
                    if (motor.state() == BotEngine.State.LAEUFT) {   // der Bot spielt selbst
                        // Spielt er etwas anderes als Arena, ist die Schicht (Seitenfenster, Rahmen) vorbei -- nicht zehn Minuten stehen lassen.
                        if (schichtBeenden(motor.state(), motor.activeMode(), schicht)) de.hustlebot.schicht.ArenaStand.beenden();
                        continue;
                    }
                    Mat bild = blick.beobachten();
                    if (bild == null) {                                      // Spiel nicht vorn: nicht hinsehen
                        Thread.sleep(schicht ? 200 : 2_000);
                        continue;
                    }
                    long t0 = System.currentTimeMillis();
                    arena.beobachtenSchritt(bild, blick);
                    // Fehlerbericht R7RW (Konrad, VM ohne Grafikkarte): ein Blick dauerte bis 0,8 s -- dann hoechstens ein
                    // Viertel der Zeit hinsehen, sonst rechnet ein schwacher PC im Leerlauf ununterbrochen.
                    long dauer = System.currentTimeMillis() - t0;
                    // Autor 30.10. (der Layer flackerte, die Tasten gingen nicht): mit eingeschalteter Schicht nicht schlafen, sonst
                    // laeuft ihr Stand ab. Das schwere Lesen macht dort ein eigener Faden mit niedriger Vorrangstufe.
                    if (schicht) {
                        if (dauer > 2_000) Log.debug("Arena", "Beobachter: ein Blick dauerte " + dauer + " ms.");
                        // Zum Nachmessen: was das schnelle Hinsehen kostet (je Minute eine Zeile).
                        blicke++;
                        blickeMs += dauer;
                        long jetzt = System.currentTimeMillis();
                        if (jetzt - blickZeileUm >= 60_000) {
                            Log.debug("Arena", "Beobachter: " + blicke + " Blicke, je Blick " + (blickeMs / Math.max(1, blicke)) + " ms ohne Foto, Foto "
                                    + Math.round(BotContext.bildMsSchnitt()) + " ms.");
                            blicke = 0;
                            blickeMs = 0;
                            blickZeileUm = jetzt;
                        }
                    } else if (dauer > TAKT_MS / 4) {
                        Thread.sleep(Math.min(10_000, dauer * 3));
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    Log.debug("Arena", "Beobachter: " + e.getMessage());
                }
            }
        }, "arena-beobachter");
        t.setDaemon(true);
        t.start();
        faden = t;
        Log.info("Arena", Sprache.t("Arena watcher " + (ArenaMode.beobachterAn() ? "on" : "off")
                        + ": when you play arena yourself, the bot counts your wins and losses per opponent (never clicks).",
                "Arena-Beobachter " + (ArenaMode.beobachterAn() ? "an" : "aus")
                        + ": spielst du selbst Arena, zaehlt der Bot Siege und Niederlagen je Gegner mit (klickt nie)."));
    }
}
