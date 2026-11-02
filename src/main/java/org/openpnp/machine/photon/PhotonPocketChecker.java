package org.openpnp.machine.photon;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;

import java.awt.image.BufferedImage;

/**
 * Looks at the pocket under the pick point with the camera and scores how likely it holds a
 * part: the spread of brightness (standard deviation of the grey levels) in a square around the
 * pick point. A part's body and end terminations make a pocket uneven, an empty pocket is mostly
 * one shade.
 *
 * The square is 80 % of the part's longer body side when its footprint is known, otherwise 40 %
 * of the part pitch, so it stays inside the pocket.
 */
public class PhotonPocketChecker implements PhotonAutoSetup.PocketChecker {
    static final double FOOTPRINT_FRACTION = 0.8;
    static final double PITCH_FRACTION = 0.4;
    static final double MIN_SIDE_MM = 0.3;

    private final Camera camera;

    public PhotonPocketChecker(Camera camera) {
        this.camera = camera;
    }

    @Override
    public double score(PhotonFeeder feeder, Location pick) throws Exception {
        MovableUtils.moveToLocationAtSafeZ(camera, pick.derive(null, null, null, 0.0));
        BufferedImage image = camera.settleAndCapture();

        Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        double sideMm = squareSideMm(feeder);
        int widthPx = Math.max(3, (int) Math.round(sideMm / Math.abs(upp.getX())));
        int heightPx = Math.max(3, (int) Math.round(sideMm / Math.abs(upp.getY())));

        Mat mat = OpenCvUtils.toMat(image);
        Mat grey = new Mat();
        try {
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, grey, Imgproc.COLOR_BGR2GRAY);
            }
            else {
                mat.copyTo(grey);
            }
            return score(grey, widthPx, heightPx);
        }
        finally {
            mat.release();
            grey.release();
        }
    }

    /**
     * The standard deviation of the grey levels in a centered rectangle of a grey image.
     */
    static double score(Mat grey, int widthPx, int heightPx) {
        int width = Math.min(widthPx, grey.cols());
        int height = Math.min(heightPx, grey.rows());
        Rect square = new Rect((grey.cols() - width) / 2, (grey.rows() - height) / 2, width, height);
        Mat roi = grey.submat(square);
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stdDev = new MatOfDouble();
        try {
            Core.meanStdDev(roi, mean, stdDev);
            return stdDev.toArray()[0];
        }
        finally {
            roi.release();
            mean.release();
            stdDev.release();
        }
    }

    static double squareSideMm(PhotonFeeder feeder) {
        Part part = feeder.hasPlaceholderPart() ? null : feeder.getPart();
        if (part != null && part.getPackage() != null) {
            Footprint footprint = part.getPackage().getFootprint();
            if (footprint != null) {
                double longer = Math.max(footprint.getBodyWidth(), footprint.getBodyHeight());
                if (longer > 0) {
                    double mm = new Length(longer, footprint.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
                    return Math.max(MIN_SIDE_MM, mm * FOOTPRINT_FRACTION);
                }
            }
        }

        PhotonFeederRecord record = feeder.getRecord();
        double pitchMm = record != null && record.pitchTenthsMm > 0
                ? record.pitchTenthsMm / 10.0
                : feeder.getPartPitch();
        return Math.max(MIN_SIDE_MM, pitchMm * PITCH_FRACTION);
    }
}
