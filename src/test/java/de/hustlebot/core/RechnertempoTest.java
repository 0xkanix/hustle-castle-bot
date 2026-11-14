package de.hustlebot.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Langsamer Rechner (Autor 29.10.: Server bis 5x langsamer): rechner-tempo.txt -> Faktor fuer alle Wartezeiten. */
class RechnertempoTest {

    @TempDir
    Path ordner;

    @AfterEach
    void zuruecksetzen() {
        Rechnertempo.setzen(1.0);
    }

    private double lade(String inhalt) throws Exception {
        Path d = ordner.resolve("rechner-tempo.txt");
        Files.writeString(d, inhalt, StandardCharsets.UTF_8);
        return Rechnertempo.laden(d);
    }

    @Test
    void zahlAusDerDateiBegrenzt() throws Exception {
        assertEquals(3.0, lade("3"));
        assertEquals(2.5, lade("# Server\n2,5 langsamer"));
        assertEquals(1.0, lade("0.5"), "schneller als normal gibt es nicht");
        assertEquals(Rechnertempo.MAX, lade("20"), "hoechstens 6-mal");
        assertEquals(1.0, lade("schnell"), "unlesbar = normal");
        assertEquals(1.0, lade("﻿# nur Kommentar\n"));
        assertEquals(1.0, Rechnertempo.laden(ordner.resolve("fehlt.txt")));
    }

    @Test
    void malNimmtNurPositiveWartezeitenMal() {
        Rechnertempo.setzen(1.0);
        assertEquals(800, Rechnertempo.mal(800));
        Rechnertempo.setzen(3.0);
        assertEquals(2400, Rechnertempo.mal(800));
        assertEquals(0, Rechnertempo.mal(0));
        assertEquals(-5, Rechnertempo.mal(-5));
    }
}
