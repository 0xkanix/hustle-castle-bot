package de.hustlebot.core;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Schrift-Erkenner (Autor 29.10.: "erst ein winziges Modell erkennt die Schriftart, dann liest das passende Modell").
 * Ein kleines Netz (690 kB, ~0,5 ms je Name) sagt an einem Namensbild nur, welche von 30 Schriften es ist -- latein,
 * kyrillisch, chinesisch, japanisch, koreanisch, arabisch, Zierschrift, indische Schriften ... Gemessen 29.10. an 9000
 * getrennten Pruefbildern 98,1 %, an 158 echten Arena-Namen 95,6 %. Mit "Erkenner waehlt die Familie" lasen Japanisch
 * 40 -> 90 %, Chinesisch 62 -> 87 %, Koreanisch 66 -> 74 %, Arabisch 0 -> 53 % (Lesen danach wie gehabt).
 *
 * Vorverarbeitung genau wie trainiert: Namensbild (x2) -> Graustufen (0,299 R + 0,587 G + 0,114 B) -> auf 192 x 32
 * gestreckt (verkleinern: Flaechenmittel) -> (g / 255 - 0,5) / 0,5. Ausgabe: Logits je Klasse (Softmax hier).
 */
public final class SchriftErkenner {

    private SchriftErkenner() { }

    /** Wahrscheinlichste Schrift und alle Klassen nach Wahrscheinlichkeit (hoechste zuerst). */
    public record Ergebnis(String klasse, double wahrscheinlich, List<Map.Entry<String, Double>> rangfolge) { }

    private static final int BREITE = 192, HOEHE = 32;
    private static OrtSession sitzung;
    private static String eingang;
    private static List<String> klassen;
    private static Boolean da;

    public static synchronized boolean verfuegbar() {
        if (da != null) return da;
        try {
            Onnxnativ.vorbereiten();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            Path ordner = Ordner.bei("modelle").resolve("paddleocr").resolve("schrift");
            Path modell = ordner.resolve("erkenner.onnx");
            String namen;
            if (Files.exists(modell) && Files.exists(ordner.resolve("klassen.txt"))) {
                namen = Files.readString(ordner.resolve("klassen.txt"), StandardCharsets.UTF_8);
            } else {
                modell = PaddleLeser.ausDemPaket("schrift", "erkenner.onnx");
                try (var in = SchriftErkenner.class.getResourceAsStream("/paddleocr/schrift/klassen.txt")) {
                    namen = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (modell == null || namen == null) {
                da = false;
                return false;
            }
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);
            sitzung = env.createSession(modell.toString(), o);
            eingang = sitzung.getInputNames().iterator().next();
            klassen = new ArrayList<>();
            for (String z : namen.split("\n")) if (!z.strip().isEmpty()) klassen.add(z.strip());
            da = !klassen.isEmpty();
        } catch (Throwable t) {
            Log.info("Text", "Schrift-Erkenner nicht nutzbar: " + t);
            Onnxnativ.laufzeitMelden(t);
            da = false;
        }
        return da;
    }

    /** Welche Schrift steht im Namensbild? Leer, wenn der Erkenner fehlt oder das Bild nicht taugt. */
    public static Optional<Ergebnis> erkennen(BufferedImage bild) {
        if (bild == null || bild.getWidth() < 4 || bild.getHeight() < 4 || !verfuegbar()) return Optional.empty();
        try {
            float[] x = vorbereiten(bild);
            float[] logits;
            synchronized (SchriftErkenner.class) {
                try (OnnxTensor t = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(x),
                        new long[]{1, 1, HOEHE, BREITE});
                     OrtSession.Result r = sitzung.run(Map.of(eingang, t))) {
                    logits = ((float[][]) r.get(0).getValue())[0];
                }
            }
            double max = Double.NEGATIVE_INFINITY;
            for (float v : logits) max = Math.max(max, v);
            double summe = 0;
            double[] p = new double[logits.length];
            for (int i = 0; i < p.length; i++) {
                p[i] = Math.exp(logits[i] - max);
                summe += p[i];
            }
            List<Map.Entry<String, Double>> rang = new ArrayList<>();
            for (int i = 0; i < p.length && i < klassen.size(); i++) rang.add(Map.entry(klassen.get(i), p[i] / summe));
            rang.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            return Optional.of(new Ergebnis(rang.get(0).getKey(), rang.get(0).getValue(), rang));
        } catch (Throwable t) {
            Log.debug("Text", "Schrift-Erkenner: " + t.getMessage());
            return Optional.empty();
        }
    }

    /** Graustufen, auf 192 x 32 gestreckt, (g/255 - 0,5) / 0,5 -- wie im Training. */
    static float[] vorbereiten(BufferedImage bild) {
        int w = bild.getWidth(), h = bild.getHeight();
        Mat grau = new Mat(h, w, CvType.CV_8UC1);
        byte[] g = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int xx = 0; xx < w; xx++) {
                int rgb = bild.getRGB(xx, y);
                double wert = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                g[y * w + xx] = (byte) Math.min(255, (int) Math.round(wert));
            }
        }
        grau.put(0, 0, g);
        Mat klein = new Mat();
        int art = (w > BREITE || h > HOEHE) ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR;
        Imgproc.resize(grau, klein, new Size(BREITE, HOEHE), 0, 0, art);
        byte[] k = new byte[BREITE * HOEHE];
        klein.get(0, 0, k);
        grau.release();
        klein.release();
        float[] x = new float[BREITE * HOEHE];
        for (int i = 0; i < x.length; i++) x[i] = ((k[i] & 0xff) / 255f - 0.5f) / 0.5f;
        return x;
    }
}
