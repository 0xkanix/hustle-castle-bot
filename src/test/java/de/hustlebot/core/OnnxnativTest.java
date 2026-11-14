package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Autor 01.11.: Die Texterkennung soll nicht bei jedem Start 12,5 MB im Temp-Ordner liegen lassen. */
class OnnxnativTest {

    @TempDir
    Path ordner;

    private String vorher;

    @BeforeEach
    void merken() {
        vorher = System.clearProperty(Onnxnativ.EIGENSCHAFT);
    }

    @AfterEach
    void zurueck() {
        System.clearProperty(Onnxnativ.EIGENSCHAFT);
        if (vorher != null) System.setProperty(Onnxnativ.EIGENSCHAFT, vorher);
    }

    @Test
    void packtEinmalInEinenFestenOrdnerAus() throws Exception {
        Onnxnativ.vorbereiten(ordner);
        String pfad = System.getProperty(Onnxnativ.EIGENSCHAFT);
        assertNotNull(pfad, "der feste Ordner muss eingetragen sein");
        Path ziel = Path.of(pfad);
        assertTrue(Files.size(ziel.resolve("onnxruntime.dll")) > 1_000_000);
        assertTrue(Files.exists(ziel.resolve("onnxruntime4j_jni.dll")));
        long zeit = Files.getLastModifiedTime(ziel.resolve("onnxruntime.dll")).toMillis();

        System.clearProperty(Onnxnativ.EIGENSCHAFT);
        Thread.sleep(20);
        Onnxnativ.vorbereiten(ordner);
        assertEquals(pfad, System.getProperty(Onnxnativ.EIGENSCHAFT), "derselbe Ordner beim naechsten Start");
        assertEquals(zeit, Files.getLastModifiedTime(ziel.resolve("onnxruntime.dll")).toMillis(), "nicht noch einmal auspacken");
    }

    @Test
    void eineFremdeEinstellungBleibtUnberuehrt() {
        System.setProperty(Onnxnativ.EIGENSCHAFT, "C:/anderswo");
        Onnxnativ.vorbereiten(ordner);
        assertEquals("C:/anderswo", System.getProperty(Onnxnativ.EIGENSCHAFT));
    }
}
