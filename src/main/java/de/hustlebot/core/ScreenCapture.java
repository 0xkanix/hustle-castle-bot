package de.hustlebot.core;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Nimmt den Inhaltsbereich des Spielfensters auf und liefert ihn als OpenCV-Bild. */
public final class ScreenCapture {

    private final Robot robot;

    public ScreenCapture() throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoWaitForIdle(false);
    }

    public BufferedImage grab(Rectangle area) {
        return robot.createScreenCapture(area);
    }

    /**
     * Das Zwischenbild fuer die Umrechnung -- eines je Arbeitsfaden.
     *
     * WARUM ES DAS BRAUCHT. Hier wurde frueher bei JEDER Aufnahme ein
     * frisches BufferedImage angelegt: bei der Groesse des Spielfensters
     * knapp drei Megabyte, und das rund zehnmal in der Sekunde.
     *
     * Java raeumt solchen Muell nicht sofort weg, sondern erst, wenn ihm
     * der Platz ausgeht. Ohne Obergrenze darf es sich ein Viertel des
     * Arbeitsspeichers nehmen -- also geht ihm der Platz lange nicht aus.
     * Am 6. September standen deshalb 3,3 Gigabyte im Task-Manager.
     *
     * Zwei Sachen dagegen, beide noetig: dieses wiederverwendete Bild, und
     * die Obergrenze -Xmx512m in run.bat.
     *
     * Je Arbeitsfaden ein eigenes, damit sich der Bot und die
     * Discord-Anbindung nicht dasselbe Bild unter den Haenden wegziehen.
     */
    private static final ThreadLocal<BufferedImage> UMRECHNER = new ThreadLocal<>();

    /**
     * Holt das Zwischenbild -- und legt es nur an, wenn es noch keins in der
     * richtigen Groesse gibt.
     *
     * Das Spielfenster wechselt seine Groesse nicht, also wird dieses Bild
     * genau einmal je Arbeitsfaden angelegt und dann tausendfach
     * wiederbenutzt. Ueberschrieben wird es dabei vollstaendig, es kann also
     * nichts Altes stehenbleiben.
     */
    private static BufferedImage umrechner(int breite, int hoehe) {
        BufferedImage vorhanden = UMRECHNER.get();
        if (vorhanden == null || vorhanden.getWidth() != breite
                || vorhanden.getHeight() != hoehe) {
            vorhanden = new BufferedImage(breite, hoehe, BufferedImage.TYPE_3BYTE_BGR);
            UMRECHNER.set(vorhanden);
        }
        return vorhanden;
    }

    /**
     * Ein kleiner Ausschnitt eines Fotos als Matrix (BGR) -- fuer den
     * Kastenblick. Geht NICHT ueber das grosse Zwischenbild: das bleibt fuer
     * die ganzen Aufnahmen, sonst wuerde es bei jedem Wechsel der Groesse neu
     * angelegt (genau der Muell, den es vermeiden soll).
     */
    public static Mat toMatAusschnitt(BufferedImage image, int x, int y, int breite, int hoehe) {
        int[] rgb = image.getRGB(x, y, breite, hoehe, null, 0, breite);
        byte[] bgr = new byte[breite * hoehe * 3];
        for (int i = 0, j = 0; i < rgb.length; i++) {
            int p = rgb[i];
            bgr[j++] = (byte) p;
            bgr[j++] = (byte) (p >> 8);
            bgr[j++] = (byte) (p >> 16);
        }
        Mat mat = new Mat(hoehe, breite, CvType.CV_8UC3);
        mat.put(0, 0, bgr);
        return mat;
    }

    /** Wandelt ein AWT-Bild in eine OpenCV-Matrix im BGR-Format um. */
    public static Mat toMat(BufferedImage image) {
        int breite = image.getWidth();
        int hoehe = image.getHeight();

        // Liegt es schon als BGR-Bytes vor, kann es direkt uebernommen
        // werden -- ganz ohne Umweg.
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR
                && image.getRaster().getDataBuffer() instanceof DataBufferByte fertig
                && fertig.getData().length == breite * hoehe * 3) {
            Mat mat = new Mat(hoehe, breite, CvType.CV_8UC3);
            mat.put(0, 0, fertig.getData());
            return mat;
        }

        // Sonst umzeichnen -- aber in das Bild von letztem Mal.
        //
        // Am Zeichnen selbst ist nichts auszusetzen, Java macht das schnell.
        // Teuer war nur, dass hier bei JEDER Aufnahme ein frisches Bild
        // angelegt wurde: knapp drei Megabyte, zehnmal in der Sekunde. Java
        // raeumt solchen Muell erst weg, wenn ihm der Platz ausgeht -- und
        // solange stand er im Task-Manager.
        //
        // Von Hand umsortieren waere die andere Moeglichkeit gewesen. Habe
        // ich gemessen: ergibt zwar dasselbe Bild, dauert aber anderthalb
        // mal so lang. Java zeichnet schneller, als man es nachbauen kann.
        BufferedImage ziel = umrechner(breite, hoehe);
        Graphics2D g = ziel.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }

        byte[] pixels = ((DataBufferByte) ziel.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(hoehe, breite, CvType.CV_8UC3);
        mat.put(0, 0, pixels);
        return mat;
    }

    /** Erzeugt eine verkleinerte PNG-Vorschau fuer die Weboberflaeche. */
    public static byte[] toPreviewPng(BufferedImage image, int maxWidth) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaleForPreview(image, maxWidth), "png", out);
        return out.toByteArray();
    }

    /**
     * Erzeugt eine verkleinerte JPEG-Vorschau. Gegenueber PNG deutlich schneller
     * erzeugt und viel kleiner, dadurch laesst sich das Bild oefter je Sekunde
     * neu laden.
     */
    public static byte[] toPreviewJpeg(BufferedImage image, int maxWidth) throws IOException {
        BufferedImage scaled = scaleForPreview(image, maxWidth);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.90f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(scaled, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * Skaliert das Bild weich auf die gewuenschte Breite herunter. Ist die
     * gewuenschte Breite mindestens so gross wie das Spielfenster, wird gar
     * nicht skaliert – das Bild bleibt in voller Schaerfe.
     */
    private static BufferedImage scaleForPreview(BufferedImage image, int maxWidth) {
        int width = Math.min(maxWidth, image.getWidth());
        int height = (int) (image.getHeight() * (width / (double) image.getWidth()));

        // Volle Aufloesung: nur noch in ein JPEG-taugliches Format umkopieren.
        if (width == image.getWidth() && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }
}
