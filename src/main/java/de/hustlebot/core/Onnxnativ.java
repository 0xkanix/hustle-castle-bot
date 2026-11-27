package de.hustlebot.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Damit die Texterkennung nicht bei jedem Start den Temp-Ordner fuellt (Autor 01.11.: "Am besten soll gar nichts wachsen").
 *
 * WARUM. onnxruntime packt seine DLLs bei jedem Programmstart in einen neuen Ordner onnxruntime-java... im Temp-Ordner aus. Unter
 * Windows lassen sie sich nicht loeschen, solange sie geladen sind, also bleiben sie liegen: 12,5 MB je Start, bei Autor 253 Ordner
 * und 2,95 GB in einer Woche. Bekommt onnxruntime einen festen Ordner genannt, packt es gar nichts mehr aus.
 *
 * WIE. Einmalig nach natives\onnx-GROESSE im Bot-Ordner auspacken. Die Groesse steht im Namen, damit eine neue Fassung einen neuen
 * Ordner nimmt und nie eine geladene DLL ueberschreiben muss. Geht etwas schief, bleibt alles wie bisher.
 *
 * VC++-LAUFZEIT (19.11., Konrad: "onnxruntime.dll: Can't find dependent libraries"). onnxruntime.dll braucht die Microsoft Visual C++
 * Laufzeit (x64): vcruntime140, vcruntime140_1, msvcp140 und msvcp140_1. Die ersten drei bringt das eingepackte Java selbst mit
 * (runtime\bin), msvcp140_1.dll aber nicht -- fehlt auf dem PC das "Visual C++ Redistributable", startet die bessere Texterkennung
 * nie. Darum liegen die vier Dateien (aus Microsofts Redistributable, darf weitergegeben werden) in der Jar und kommen mit in den
 * festen Ordner. Windows sucht die Abhaengigkeiten einer DLL NICHT in deren eigenem Ordner (Java laedt mit LoadLibrary ohne
 * LOAD_WITH_ALTERED_SEARCH_PATH). Ist aber eine DLL gleichen Namens schon geladen, nimmt Windows diese. Deshalb werden alle
 * vier vorher mit vollem Pfad geladen, in der Reihenfolge ihrer Abhaengigkeiten -- was Java in seinem bin-Ordner selbst hat,
 * von dort (kein zweites Exemplar), den Rest aus dem festen Ordner. 20.11. (VM): vorher wurde nur msvcp140_1.dll geladen, deren
 * Abhaengigkeit msvcp140.dll Windows dann nicht fand. Scheitert es trotzdem, liegen die DLLs ab dem naechsten Start neben dem
 * Java des Bots (laufzeitMelden).
 */
public final class Onnxnativ {

    private static final String QUELLE = "/ai/onnxruntime/native/win-x64/";
    private static final String[] DATEIEN = {"onnxruntime.dll", "onnxruntime4j_jni.dll"};
    static final String LAUFZEIT_QUELLE = "/vclaufzeit/win-x64/";
    /** In Ladereihenfolge: jede braucht nur die vor ihr. */
    static final String[] LAUFZEIT = {"vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "msvcp140_1.dll"};
    static final String EIGENSCHAFT = "onnxruntime.native.path";
    static final String VC_LINK = "https://aka.ms/vs/17/release/vc_redist.x64.exe";

    private Onnxnativ() {
    }

    /** Vor dem ersten OrtEnvironment aufrufen. */
    public static synchronized void vorbereiten() {
        vorbereiten(Ordner.bei("natives"), true);
    }

    /** @param laden die VC++-Laufzeit gleich laden (die Tests packen nur aus: eine geladene DLL liesse sich nicht mehr loeschen) */
    static synchronized void vorbereiten(Path natives, boolean laden) {
        if (System.getProperty(EIGENSCHAFT) != null) return;
        try {
            Path ziel = auspacken(natives);
            if (ziel == null) return;   // kein Windows-Paket: onnxruntime macht es wie immer selbst
            if (laden) laufzeitLaden(ziel);
            System.setProperty(EIGENSCHAFT, ziel.toAbsolutePath().toString());
        } catch (IOException | RuntimeException e) {
            Log.debug("Texterkennung", "Fester Ordner fuer onnxruntime ging nicht, es bleibt beim Temp-Ordner: " + e.getMessage());
        }
    }

    /** Packt onnxruntime und die VC++-Laufzeit nach natives\onnx-SUMME; null, wenn die onnxruntime-DLLs nicht in der Jar sind. */
    static Path auspacken(Path natives) throws IOException {
        List<String> namen = new ArrayList<>();
        List<byte[]> inhalt = new ArrayList<>();
        long summe = 0;
        for (String d : DATEIEN) {
            byte[] b = ressource(QUELLE + d);
            if (b == null) return null;
            namen.add(d);
            inhalt.add(b);
            summe += b.length;
        }
        for (String d : LAUFZEIT) {
            byte[] b = ressource(LAUFZEIT_QUELLE + d);
            if (b == null) continue;   // fehlt nur die Laufzeit: wie vor dem 19.11.
            namen.add(d);
            inhalt.add(b);
            summe += b.length;
        }
        Path ziel = natives.resolve("onnx-" + summe);
        Files.createDirectories(ziel);
        for (int i = 0; i < namen.size(); i++) {
            Path datei = ziel.resolve(namen.get(i));
            if (Files.exists(datei) && Files.size(datei) == inhalt.get(i).length) continue;
            Path neu = ziel.resolve(namen.get(i) + ".neu");
            Files.write(neu, inhalt.get(i));
            Files.move(neu, datei, StandardCopyOption.REPLACE_EXISTING);
        }
        return ziel;
    }

    private static byte[] ressource(String pfad) throws IOException {
        try (InputStream in = Onnxnativ.class.getResourceAsStream(pfad)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /**
     * ALLE vier Laufzeit-DLLs in Ladereihenfolge, je mit vollem Pfad: die von Java selbst (javaBin), sonst die aus ziel.
     *
     * Live-Befund VM 20.11.: nur msvcp140_1.dll vorzuladen reicht nicht sicher. Sie braucht msvcp140.dll -- und die
     * sucht Windows im Programmordner (bei HustleBot.exe der Bot-Ordner, nicht runtimein) und in System32, nicht im
     * bin-Ordner von Java. Ohne VC++ auf dem Rechner scheiterte es dort. Sind alle vier vorher geladen, nimmt Windows fuer
     * onnxruntime.dll die schon geladenen (gleicher Modulname), egal aus welchem Ordner.
     */
    static List<Path> zuLaden(Path ziel, Path javaBin) {
        List<Path> liste = new ArrayList<>();
        for (String d : LAUFZEIT) {
            Path java = javaBin == null ? null : javaBin.resolve(d);
            Path eigen = ziel.resolve(d);
            if (java != null && Files.exists(java)) liste.add(java.toAbsolutePath());
            else if (Files.exists(eigen)) liste.add(eigen.toAbsolutePath());
        }
        return liste;
    }

    private static void laufzeitLaden(Path ziel) {
        String home = System.getProperty("java.home");
        for (Path p : zuLaden(ziel, home == null ? null : Path.of(home, "bin"))) {
            try {
                System.load(p.toString());
                Log.debug("Texterkennung", "VC++-Laufzeit geladen: " + p.getFileName());
            } catch (Throwable t) {
                Log.debug("Texterkennung", "VC++-Laufzeit " + p.getFileName() + " liess sich nicht laden: " + t.getMessage());
            }
        }
    }

    /**
     * Klare Meldung, wenn onnxruntime trotzdem an fehlenden Windows-Bibliotheken scheitert (die Freunde lesen sonst nur
     * "Can't find dependent libraries"). null, wenn es ein anderer Fehler ist.
     */
    public static String laufzeitHinweis(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = x.getMessage();
            if (m != null && m.contains("dependent libraries")) {
                return Sprache.t("The better text recognition (PaddleOCR) cannot start: Windows is missing the Microsoft Visual C++ Runtime (x64). "
                                + "Please install it once: " + VC_LINK + " (download, double-click, Install), then restart the bot. "
                                + "Until then the bot keeps using the Windows text recognition.",
                        "Die bessere Texterkennung (PaddleOCR) startet nicht: Windows fehlt die Microsoft Visual C++ Laufzeit (x64). "
                                + "Bitte einmal installieren: " + VC_LINK + " (herunterladen, doppelklicken, Installieren), dann den Bot neu starten. "
                                + "Bis dahin liest der Bot weiter mit der Windows-Texterkennung.");
            }
        }
        return null;
    }

    private static boolean gemeldet;

    /** Schreibt den Hinweis einmal je Start ins Protokoll (PaddleOCR und der Schrift-Erkenner scheitern sonst beide daran). */
    public static synchronized void laufzeitMelden(Throwable t) {
        String hinweis = laufzeitHinweis(t);
        if (hinweis == null || gemeldet) return;
        gemeldet = true;
        Log.warn("Text", hinweis);
        String home = System.getProperty("java.home");
        String natives = System.getProperty(EIGENSCHAFT);
        if (home != null && natives != null) {
            List<String> kopiert = fuerNaechstenStartKopieren(Path.of(natives), Path.of(home, "bin"), Ordner.basis());
            if (!kopiert.isEmpty()) {
                Log.warn("Text", Sprache.t("Copied " + kopiert + " next to the bot's Java -- PaddleOCR should start after the next restart of the bot.",
                        kopiert + " neben das Java des Bots gelegt -- nach dem naechsten Neustart des Bots sollte PaddleOCR starten."));
            }
        }
    }

    /**
     * Rueckfall (Live-Befund VM 20.11.: dort half erst, die DLLs in runtimein zu legen): fehlende Laufzeit-DLLs neben das
     * Java des Bots kopieren -- NUR wenn dieses Java im Bot-Ordner liegt (eigenes runtime, nie ein fremdes Java). Wirkt ab
     * dem naechsten Start. Liefert die Namen der kopierten Dateien.
     */
    static List<String> fuerNaechstenStartKopieren(Path ziel, Path javaBin, Path botOrdner) {
        List<String> kopiert = new ArrayList<>();
        try {
            if (botOrdner == null || !javaBin.toAbsolutePath().normalize().startsWith(botOrdner.toAbsolutePath().normalize())) {
                return kopiert;
            }
            for (String d : LAUFZEIT) {
                Path quelle = ziel.resolve(d), dort = javaBin.resolve(d);
                if (Files.exists(dort) || !Files.exists(quelle)) continue;
                Files.copy(quelle, dort);
                kopiert.add(d);
            }
        } catch (Exception e) {
            Log.debug("Texterkennung", "VC++-Laufzeit nicht neben Java gelegt: " + e.getMessage());
        }
        return kopiert;
    }
}
