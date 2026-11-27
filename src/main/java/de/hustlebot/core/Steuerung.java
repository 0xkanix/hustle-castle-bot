package de.hustlebot.core;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Wie der Bot den Bildschirm sieht und beruehrt (Handy-Umbau, Autor 19.11.). Am PC: echte Maus und Bildschirmfoto
 * (RobotSteuerung, genau wie bisher). Am Handy: Beruehrungen und Fotos ueber ADB (AdbSteuerung). Alle Koordinaten sind
 * Bildschirmpunkte des jeweiligen Geraets.
 */
public interface Steuerung {

    /** Zeiger/Finger an diese Stelle; ist gerade gedrueckt, ist das ein Zug. */
    void bewegen(int x, int y);

    /** An der aktuellen Stelle druecken (Finger auflegen). */
    void druecken();

    /** Loslassen (Finger heben). */
    void loslassen();

    /** Wo der Zeiger/Finger gerade ist. */
    Point zeiger();

    /** Bildschirmfoto dieses Bereichs. */
    BufferedImage foto(Rectangle bereich);

    /** true = echte PC-Maus: dann gilt die Mauswache (ein Mensch bewegt die Maus -> Bot haelt an). */
    default boolean istPcMaus() {
        return false;
    }
}
