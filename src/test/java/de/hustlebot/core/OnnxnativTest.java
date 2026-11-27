package de.hustlebot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        Onnxnativ.vorbereiten(ordner, false);
        String pfad = System.getProperty(Onnxnativ.EIGENSCHAFT);
        assertNotNull(pfad, "der feste Ordner muss eingetragen sein");
        Path ziel = Path.of(pfad);
        assertTrue(Files.size(ziel.resolve("onnxruntime.dll")) > 1_000_000);
        assertTrue(Files.exists(ziel.resolve("onnxruntime4j_jni.dll")));
        long zeit = Files.getLastModifiedTime(ziel.resolve("onnxruntime.dll")).toMillis();

        System.clearProperty(Onnxnativ.EIGENSCHAFT);
        Thread.sleep(20);
        Onnxnativ.vorbereiten(ordner, false);
        assertEquals(pfad, System.getProperty(Onnxnativ.EIGENSCHAFT), "derselbe Ordner beim naechsten Start");
        assertEquals(zeit, Files.getLastModifiedTime(ziel.resolve("onnxruntime.dll")).toMillis(), "nicht noch einmal auspacken");
    }

    @Test
    void eineFremdeEinstellungBleibtUnberuehrt() {
        System.setProperty(Onnxnativ.EIGENSCHAFT, "C:/anderswo");
        Onnxnativ.vorbereiten(ordner, false);
        assertEquals("C:/anderswo", System.getProperty(Onnxnativ.EIGENSCHAFT));
    }

    // --- 19.11. Konrad: "onnxruntime.dll: Can't find dependent libraries" (VC++-Laufzeit fehlt) ---

    @Test
    void dieVcLaufzeitLiegtAlsRessourceBei() throws Exception {
        assertEquals(List.of("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll"), List.of(Onnxnativ.LAUFZEIT),
                "Ladereihenfolge: jede DLL braucht nur die vor ihr");
        for (String d : Onnxnativ.LAUFZEIT) {
            try (InputStream in = Onnxnativ.class.getResourceAsStream(Onnxnativ.LAUFZEIT_QUELLE + d)) {
                assertNotNull(in, d + " fehlt in der Jar");
                byte[] b = in.readAllBytes();
                assertTrue(b.length > 20_000, d + " zu klein: " + b.length);
                assertEquals('M', b[0], d + " ist keine DLL");
                assertEquals('Z', b[1], d + " ist keine DLL");
            }
        }
    }

    @Test
    void dieVcLaufzeitWirdMitAusgepacktUndGibtEinenNeuenOrdner() throws Exception {
        Path ziel = Onnxnativ.auspacken(ordner);
        assertNotNull(ziel);
        for (String d : Onnxnativ.LAUFZEIT) {
            try (InputStream in = Onnxnativ.class.getResourceAsStream(Onnxnativ.LAUFZEIT_QUELLE + d)) {
                assertEquals(in.readAllBytes().length, Files.size(ziel.resolve(d)), d + " nicht vollstaendig ausgepackt");
            }
        }
        assertTrue(Files.exists(ziel.resolve("onnxruntime.dll")));
        // Bis 3.147 hiess der Ordner onnx-12505200 (nur onnxruntime). Bestehende Installationen muessen einen NEUEN Ordner bekommen,
        // sonst wuerden dort die Laufzeit-DLLs nie nachgelegt -- und eine geladene DLL darf nie ueberschrieben werden.
        assertNotEquals("onnx-12505200", ziel.getFileName().toString());
        try (var liste = Files.list(ziel)) {
            assertTrue(liste.noneMatch(p -> p.getFileName().toString().endsWith(".neu")), "keine halben Dateien liegen lassen");
        }
    }

    @Test
    void geladenWerdenAlleVierJavasEigeneZuerst() throws Exception {
        Path ziel = Onnxnativ.auspacken(ordner);
        Path javaBin = Files.createDirectories(ordner.resolve("java-bin"));
        for (String d : new String[] {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"}) Files.writeString(javaBin.resolve(d), "x");

        // Wie im Paket (runtimein hat drei der vier): die drei von Java, msvcp140_1.dll (fehlte bei Konrad) aus dem festen
        // Ordner -- und zwar NACH msvcp140.dll, die sie braucht (VM 20.11.: allein geladen fand Windows msvcp140.dll nicht).
        assertEquals(List.of(javaBin.resolve("vcruntime140.dll").toAbsolutePath(), javaBin.resolve("vcruntime140_1.dll").toAbsolutePath(),
                javaBin.resolve("msvcp140.dll").toAbsolutePath(), ziel.resolve("msvcp140_1.dll").toAbsolutePath()),
                Onnxnativ.zuLaden(ziel, javaBin));

        // Rueckfall fuer den naechsten Start: nur in ein Java im Bot-Ordner, nur was fehlt
        assertEquals(List.of("msvcp140_1.dll"), Onnxnativ.fuerNaechstenStartKopieren(ziel, javaBin, ordner));
        assertTrue(Files.exists(javaBin.resolve("msvcp140_1.dll")));
        assertEquals("x", Files.readString(javaBin.resolve("msvcp140.dll")), "Vorhandenes bleibt");
        assertTrue(Onnxnativ.fuerNaechstenStartKopieren(ziel, javaBin, ordner).isEmpty(), "zweimal nichts");
        Path fremd = Files.createDirectories(ordner.resolve("anderswo").resolve("bin"));
        assertTrue(Onnxnativ.fuerNaechstenStartKopieren(ziel, fremd, ordner.resolve("bot")).isEmpty(), "fremdes Java bleibt unberuehrt");

        // Java ohne eigene Laufzeit: alle vier, in Ladereihenfolge.
        Path leer = Files.createDirectories(ordner.resolve("leer"));
        List<Path> alle = Onnxnativ.zuLaden(ziel, leer);
        assertEquals(4, alle.size());
        for (int i = 0; i < 4; i++) assertEquals(Onnxnativ.LAUFZEIT[i], alle.get(i).getFileName().toString());
        assertEquals(alle, Onnxnativ.zuLaden(ziel, null));
    }

    @Test
    void fehltDieLaufzeitTrotzdemGibtEsEinenKlarenHinweisMitLink() {
        UnsatisfiedLinkError fehler = new UnsatisfiedLinkError(
                "C:\\Users\\x\\AppData\\Local\\Temp\\onnxruntime-java1\\onnxruntime.dll: Can't find dependent libraries");
        String hinweis = Onnxnativ.laufzeitHinweis(fehler);
        assertNotNull(hinweis);
        assertTrue(hinweis.contains("https://aka.ms/vs/17/release/vc_redist.x64.exe"), hinweis);
        assertNotNull(Onnxnativ.laufzeitHinweis(new IllegalStateException("Laden ging nicht", fehler)), "auch als Ursache erkennen");
        assertNull(Onnxnativ.laufzeitHinweis(new UnsatisfiedLinkError("no onnxruntime in java.library.path")));
        assertNull(Onnxnativ.laufzeitHinweis(new RuntimeException((String) null)));
    }
}
