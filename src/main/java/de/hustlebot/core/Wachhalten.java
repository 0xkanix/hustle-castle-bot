package de.hustlebot.core;

import com.sun.jna.platform.win32.Kernel32;

import java.util.function.BooleanSupplier;

/**
 * Start-Probleme-Recherche 16.10. (doku/START-PROBLEME-16-10.md, Punkt 6): der Bot hielt den PC nicht wach.
 * Solange er klickt, zaehlt Windows das als Eingabe -- in Wartezeiten (Pause, Spiel-Neustart, Verlies sucht
 * Mitspieler) aber nicht, und dann gehen Bildschirm oder ganzer PC schlafen. Darum: solange ein Modus laeuft
 * oder pausiert, Windows sagen "bitte wach bleiben"; danach wieder freigeben.
 * Die Bitte gilt nur, solange der Faden lebt, der sie gestellt hat -- darum ein eigener Dauerfaden.
 */
public final class Wachhalten {

    private static final int ES_CONTINUOUS = 0x80000000;
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;
    private static final int ES_DISPLAY_REQUIRED = 0x00000002;

    private Wachhalten() {}

    /** Prueft alle 30 s, ob der Bot arbeitet, und stellt die Bitte an Windows danach ein. */
    public static void starten(BooleanSupplier arbeitet) {
        Thread t = new Thread(() -> {
            boolean wach = false;
            while (true) {
                boolean soll = arbeitet.getAsBoolean();
                if (soll != wach) {
                    try {
                        Kernel32.INSTANCE.SetThreadExecutionState(
                                soll ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED : ES_CONTINUOUS);
                        wach = soll;
                        Log.debug("PC", wach ? "Wachhalten an (Bot arbeitet)." : "Wachhalten aus (Bot steht).");
                    } catch (Throwable e) {
                        Log.debug("PC", "Wachhalten nicht moeglich: " + e.getMessage());
                        return;
                    }
                }
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "Wachhalten");
        t.setDaemon(true);
        t.start();
    }
}
