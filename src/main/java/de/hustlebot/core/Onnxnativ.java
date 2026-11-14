package de.hustlebot.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Damit die Texterkennung nicht bei jedem Start den Temp-Ordner fuellt (Autor 01.11.: "Am besten soll gar nichts wachsen").
 *
 * WARUM. onnxruntime packt seine DLLs bei jedem Programmstart in einen neuen Ordner onnxruntime-java... im Temp-Ordner aus. Unter
 * Windows lassen sie sich nicht loeschen, solange sie geladen sind, also bleiben sie liegen: 12,5 MB je Start, bei Autor 253 Ordner
 * und 2,95 GB in einer Woche. Bekommt onnxruntime einen festen Ordner genannt, packt es gar nichts mehr aus.
 *
 * WIE. Einmalig nach natives\onnx-GROESSE im Bot-Ordner auspacken. Die Groesse steht im Namen, damit eine neue Fassung einen neuen
 * Ordner nimmt und nie eine geladene DLL ueberschreiben muss. Geht etwas schief, bleibt alles wie bisher.
 */
public final class Onnxnativ {

    private static final String QUELLE = "/ai/onnxruntime/native/win-x64/";
    private static final String[] DATEIEN = {"onnxruntime.dll", "onnxruntime4j_jni.dll"};
    static final String EIGENSCHAFT = "onnxruntime.native.path";

    private Onnxnativ() {
    }

    /** Vor dem ersten OrtEnvironment aufrufen. */
    public static synchronized void vorbereiten() {
        vorbereiten(Ordner.bei("natives"));
    }

    static synchronized void vorbereiten(Path natives) {
        if (System.getProperty(EIGENSCHAFT) != null) return;
        try {
            byte[][] inhalt = new byte[DATEIEN.length][];
            long summe = 0;
            for (int i = 0; i < DATEIEN.length; i++) {
                try (InputStream in = Onnxnativ.class.getResourceAsStream(QUELLE + DATEIEN[i])) {
                    if (in == null) return;   // kein Windows-Paket: onnxruntime macht es wie immer selbst
                    inhalt[i] = in.readAllBytes();
                    summe += inhalt[i].length;
                }
            }
            Path ziel = natives.resolve("onnx-" + summe);
            Files.createDirectories(ziel);
            for (int i = 0; i < DATEIEN.length; i++) {
                Path datei = ziel.resolve(DATEIEN[i]);
                if (Files.exists(datei) && Files.size(datei) == inhalt[i].length) continue;
                Path neu = ziel.resolve(DATEIEN[i] + ".neu");
                Files.write(neu, inhalt[i]);
                Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
            }
            System.setProperty(EIGENSCHAFT, ziel.toAbsolutePath().toString());
        } catch (IOException | RuntimeException e) {
            Log.debug("Texterkennung", "Fester Ordner fuer onnxruntime ging nicht, es bleibt beim Temp-Ordner: " + e.getMessage());
        }
    }
}
