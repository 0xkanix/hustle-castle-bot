package de.hustlebot.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Autor 28.10.: sammelt Namensausschnitte mit dem RICHTIGEN Namen -- Futter, um PaddleOCR spaeter auf die Schrift
 * des Spiels nachzutrainieren (Anleitung: doku\OCR-TRAINING.md).
 *
 * Ordner logs\ocr-training\ im Bot-Ordner: je Ausschnitt ein PNG ("000017_1a2b3c4d.png": laufende Nummer und
 * Pruefsumme des Bildes) und eine Zeile in labels.txt im Format, das PaddleOCR zum Trainieren des Erkennungsmodells
 * ("rec") erwartet:  bild.png TAB Name   (UTF-8).
 *
 * Hoechstens 3 Bilder je Spieler (sonst lernt das Modell nur die Stammgegner) und 2000 insgesamt. Dasselbe Bild nur
 * einmal. Namen ueber 25 Zeichen nimmt PaddleOCR beim Training nicht an -- die bleiben weg.
 *
 * Schalterfrei: merken() ruft nur ArenaMode auf, nur mit ArenaSchalter.an() und nur bei SICHERER Erkennung (das
 * entscheidet der Aufrufer). Bei den Freunden entsteht der Ordner nicht. Thread-sicher; Fehler nur ins Protokoll.
 */
public final class OcrTrainingsdaten {

    public static final int JE_SPIELER = 3;
    public static final int GESAMT = 2000;
    public static final String LABELS = "labels.txt";
    /** PaddleOCR: max_text_length (Standard 25) -- laengere Beschriftungen wirft es beim Laden weg. */
    static final int HOECHSTENS_ZEICHEN = 25;

    private static volatile OcrTrainingsdaten standard;

    /** Der Ordner logs\ocr-training im Bot-Ordner. */
    public static OcrTrainingsdaten standard() {
        OcrTrainingsdaten s = standard;
        if (s == null) {
            synchronized (OcrTrainingsdaten.class) {
                if (standard == null) standard = new OcrTrainingsdaten(Ordner.bei("logs").resolve("ocr-training"));
                s = standard;
            }
        }
        return s;
    }

    private final Path ordner;
    private boolean geladen;
    private int gesamt;
    private int naechsteNummer = 1;
    private final Map<String, Integer> jeSpieler = new HashMap<>();
    private final Set<String> pruefsummen = new HashSet<>();

    private final int grenzeJeSpieler;
    private final int grenzeGesamt;

    public OcrTrainingsdaten(Path ordner) {
        this(ordner, JE_SPIELER, GESAMT);
    }

    /** Mit eigenen Grenzen (Tests). */
    OcrTrainingsdaten(Path ordner, int grenzeJeSpieler, int grenzeGesamt) {
        this.ordner = ordner;
        this.grenzeJeSpieler = grenzeJeSpieler;
        this.grenzeGesamt = grenzeGesamt;
    }

    /**
     * Legt den Ausschnitt mit dem richtigen Namen ab. false, wenn er nicht gebraucht wird (Grenze erreicht, schon
     * dasselbe Bild da, Name unbrauchbar) oder das Speichern scheitert.
     */
    public synchronized boolean merken(BufferedImage ausschnitt, String name) {
        String label = name == null ? "" : name.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        if (ausschnitt == null || label.isEmpty()) return false;
        if (label.codePointCount(0, label.length()) > HOECHSTENS_ZEICHEN) return false;
        if (ausschnitt.getWidth() < 8 || ausschnitt.getHeight() < 6) return false;
        laden();
        if (gesamt >= grenzeGesamt) return false;
        String spieler = spieler(label);
        if (jeSpieler.getOrDefault(spieler, 0) >= grenzeJeSpieler) return false;

        // Kopie ohne Durchsichtigkeit -- ein Teilbild teilt sich sonst den Speicher mit dem ganzen Foto.
        BufferedImage rgb = new BufferedImage(ausschnitt.getWidth(), ausschnitt.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(ausschnitt, 0, 0, null);
        String summe = String.format("%08x", java.util.Arrays.hashCode(
                rgb.getRGB(0, 0, rgb.getWidth(), rgb.getHeight(), null, 0, rgb.getWidth())));
        if (pruefsummen.contains(summe)) return false;

        String dateiname = String.format("%06d_%s.png", naechsteNummer, summe);
        Path ziel = ordner.resolve(dateiname);
        Path neu = ordner.resolve(dateiname + ".neu");
        try {
            Files.createDirectories(ordner);
            if (!ImageIO.write(rgb, "png", neu.toFile())) return false;
            Files.move(neu, ziel, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(ordner.resolve(LABELS), dateiname + "\t" + label + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            try { Files.deleteIfExists(neu); } catch (Exception ignored) { /* bleibt liegen */ }
            Log.warn("Arena", Sprache.t("OCR training picture not saved: " + e.getMessage(),
                    "OCR-Trainingsbild nicht gespeichert: " + e.getMessage()));
            return false;
        }
        naechsteNummer++;
        gesamt++;
        jeSpieler.merge(spieler, 1, Integer::sum);
        pruefsummen.add(summe);
        return true;
    }

    /** Wie viele Bilder schon gesammelt sind. */
    public synchronized int anzahl() {
        laden();
        return gesamt;
    }

    /** Wie viele Bilder es von diesem Spieler gibt. */
    public synchronized int anzahl(String name) {
        laden();
        return jeSpieler.getOrDefault(spieler(name == null ? "" : name.strip()), 0);
    }

    /** Derselbe Spieler, auch wenn Gross-/Kleinschreibung oder Zeichen wechseln. */
    private static String spieler(String label) {
        String f = NamensVergleich.form(label);
        return f.isEmpty() ? label : f;
    }

    /** "000017_1a2b3c4d.png": laufende Nummer, Pruefsumme. */
    private static final java.util.regex.Pattern DATEI = java.util.regex.Pattern.compile("^(\\d{1,9})_([0-9a-f]{8})\\.png$");

    /** Einmal je Lauf: was schon im Ordner liegt (labels.txt), damit die Grenzen auch nach einem Neustart gelten. */
    private void laden() {
        if (geladen) return;
        geladen = true;
        Path labels = ordner.resolve(LABELS);
        if (!Files.exists(labels)) return;
        try {
            for (String z : Files.readAllLines(labels, StandardCharsets.UTF_8)) {
                int tab = z.indexOf('\t');
                if (tab <= 0) continue;
                String datei = z.substring(0, tab).strip(), label = z.substring(tab + 1).strip();
                if (label.isEmpty()) continue;
                gesamt++;
                jeSpieler.merge(spieler(label), 1, Integer::sum);
                java.util.regex.Matcher m = DATEI.matcher(datei);
                if (m.matches()) {
                    naechsteNummer = Math.max(naechsteNummer, Integer.parseInt(m.group(1)) + 1);
                    pruefsummen.add(m.group(2));
                }
            }
        } catch (Exception e) {
            Log.warn("Arena", Sprache.t(LABELS + " for OCR training not readable: " + e.getMessage(),
                    LABELS + " fuer das OCR-Training nicht lesbar: " + e.getMessage()));
        }
    }
}
