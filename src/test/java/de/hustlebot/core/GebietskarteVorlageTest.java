package de.hustlebot.core;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.CvType;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Gebietskarte (20.11.): die Schwert-Vorlagen werden geladen und in einem Bild wiedergefunden, in einem leeren nicht. */
class GebietskarteVorlageTest {

    @Test
    void schwertWirdGefundenLeeresBildNicht() {
        nu.pattern.OpenCV.loadLocally();
        Matcher m = new Matcher(Path.of("templates"));
        m.loadAll();
        for (String n : new String[]{"rs_gebietskarte_schwert", "rs_gebietskarte_schwertb"}) {
            assertTrue(m.has(n), n);
            Mat t = Imgcodecs.imread("templates/gelernt/dungeon/karte/" + n + ".png");
            Mat bild = new Mat(770, 1159, CvType.CV_8UC3, new Scalar(30, 90, 40));
            t.copyTo(bild.submat(new Rect(300, 200, t.cols(), t.rows())));
            assertTrue(m.find(bild, n).isPresent(), n + " im Bild");
            assertFalse(m.find(new Mat(770, 1159, CvType.CV_8UC3, new Scalar(30, 90, 40)), n).isPresent(), n + " im leeren Bild");
        }
    }
}
