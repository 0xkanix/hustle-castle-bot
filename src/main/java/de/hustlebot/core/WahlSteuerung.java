package de.hustlebot.core;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

/**
 * Hintergrund-Modus (Autor 20.11.): waehlt bei jedem Schritt zwischen der gewohnten PC-Steuerung (echte Maus,
 * Bildschirmfoto) und der {@link HintergrundSteuerung} -- je nach Schalter hintergrund.txt. Ein begonnener Druck wird
 * immer mit DERSELBEN Steuerung zu Ende gebracht (nie halb Maus, halb Touch). Schalter aus = genau wie bisher.
 */
public final class WahlSteuerung implements Steuerung {

    private final Steuerung vorne;
    private final Steuerung hinten;
    private final BooleanSupplier hintergrund;
    private Steuerung gedrueckt;

    public WahlSteuerung(Steuerung vorne, Steuerung hinten, BooleanSupplier hintergrund) {
        this.vorne = vorne;
        this.hinten = hinten;
        this.hintergrund = hintergrund;
    }

    private Steuerung jetzt() {
        if (gedrueckt != null) return gedrueckt;
        return hintergrund.getAsBoolean() ? hinten : vorne;
    }

    @Override
    public synchronized void bewegen(int x, int y) {
        jetzt().bewegen(x, y);
    }

    @Override
    public synchronized void druecken() {
        Steuerung s = jetzt();
        gedrueckt = s;
        s.druecken();
    }

    @Override
    public synchronized void loslassen() {
        Steuerung s = jetzt();
        gedrueckt = null;
        s.loslassen();
    }

    @Override
    public synchronized Point zeiger() {
        return jetzt().zeiger();
    }

    /** Ohne Sperre: ein Foto (anderer Faden) soll nicht auf einen laufenden Tipp warten. */
    @Override
    public BufferedImage foto(Rectangle bereich) {
        return (hintergrund.getAsBoolean() ? hinten : vorne).foto(bereich);
    }

    @Override
    public synchronized boolean istPcMaus() {
        return jetzt().istPcMaus();
    }
}
