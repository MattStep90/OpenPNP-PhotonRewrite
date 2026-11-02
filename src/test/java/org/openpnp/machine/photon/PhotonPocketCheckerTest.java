package org.openpnp.machine.photon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonPocketCheckerTest {
    @BeforeAll
    public static void loadOpenCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @Test
    public void anEvenPocketScoresZero() {
        Mat grey = new Mat(100, 100, CvType.CV_8UC1, new Scalar(30));
        assertEquals(0, PhotonPocketChecker.score(grey, 20, 20), 1e-9);
    }

    @Test
    public void aPartWithBrightEndsScoresHigh() {
        Mat grey = new Mat(100, 100, CvType.CV_8UC1, new Scalar(30));
        // A dark body with bright terminations at both ends, centered
        grey.submat(40, 60, 40, 45).setTo(new Scalar(200));
        grey.submat(40, 60, 55, 60).setTo(new Scalar(200));
        assertTrue(PhotonPocketChecker.score(grey, 20, 20) > 50);
    }

    @Test
    public void onlyTheCenterIsLookedAt() {
        Mat grey = new Mat(100, 100, CvType.CV_8UC1, new Scalar(30));
        grey.submat(0, 10, 0, 100).setTo(new Scalar(250));
        assertEquals(0, PhotonPocketChecker.score(grey, 20, 20), 1e-9);
    }
}
