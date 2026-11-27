package de.hustlebot.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 15.11. / X3A2: Captcha-Erkennung am echten Spielbild (Ben, 31.10.) und an einem normalen Bild (Verlies-Etage). */
class CaptchaTest {

    @BeforeAll
    static void opencv() {
        nu.pattern.OpenCV.loadLocally();
    }

    private static Mat bild(String name) throws Exception {
        try (InputStream in = CaptchaTest.class.getResourceAsStream("/captcha/" + name)) {
            return Imgcodecs.imdecode(new MatOfByte(in.readAllBytes()), Imgcodecs.IMREAD_COLOR);
        }
    }

    @Test
    void echtesCaptchaWirdErkannt() throws Exception {
        Mat b = bild("echt-x3a2.png");
        double w = Captcha.wert(b);
        System.out.println("Captcha echt: " + w);
        assertTrue(Captcha.erkannt(b), "Wert " + w);
    }

    @Test
    void echtesCaptchaAuchBeiAndererFenstergroesse() throws Exception {
        Mat b = bild("echt-x3a2.png");
        for (double f : new double[]{0.75, 0.87, 1.2}) {
            Mat k = new Mat();
            Imgproc.resize(b, k, new Size(), f, f, Imgproc.INTER_AREA);
            double w = Captcha.wert(k);
            System.out.println("Captcha Faktor " + f + ": " + w);
            assertTrue(Captcha.erkannt(k), "Faktor " + f + " Wert " + w);
        }
    }

    @Test
    void normalesBildIstKeinCaptcha() throws Exception {
        Mat b = bild("ohne-xpvy.png");
        double w = Captcha.wert(b);
        System.out.println("Captcha normal: " + w);
        assertFalse(Captcha.erkannt(b), "Wert " + w);
    }

    @Test
    void leeresBildIstKeinCaptcha() {
        assertFalse(Captcha.erkannt(null));
        assertFalse(Captcha.erkannt(new Mat()));
    }
}
