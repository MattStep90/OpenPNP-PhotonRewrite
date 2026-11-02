package org.openpnp.machine.photon;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.pmw.tinylog.Logger;

/**
 * Measures how far a stock Photon feeder really moves the tape and has the feeder correct its
 * feed scale (feeder firmware docs/FEED_SCALE.md), so the pick point stops drifting along the
 * reel.
 *
 * 1. Find two sprocket holes at the pick point.
 * 2. Feed 1 mm and find the same holes again: that gives the direction the tape moves in.
 * 3. Feed at least 40 mm in normal part feeds, a whole number of hole pitches (4 mm), so the
 *    same holes come back to almost the same place. How far they are off, along the tape, is
 *    the feed error.
 * 4. Feed the rest of a part pitch, so a pocket is back at the pick point.
 * 5. Send the distance asked for and the distance measured (0x2D). The feeder works out its new
 *    scale and saves it.
 *
 * The parts fed past the pick point are lost, and the feeder's count goes down by as many.
 */
public class PhotonFeedCalibrator {
    static final double MIN_DISTANCE_MM = 40;
    static final double HOLE_PITCH_MM = 4;

    /**
     * Finds a pick point's sprocket holes, and the same holes again after a short move. Both
     * return {pick, hole 1, hole 2}.
     */
    public interface TapeTracker {
        Location[] find(PhotonFeeder feeder, Location pickEstimate) throws Exception;

        Location[] track(PhotonFeeder feeder, Location[] previous) throws Exception;
    }

    public interface Mover {
        void feedMillimeters(PhotonFeeder feeder, int millimeters) throws Exception;

        void feedPart(PhotonFeeder feeder) throws Exception;
    }

    public static final Mover FEEDER_MOVER = new Mover() {
        @Override
        public void feedMillimeters(PhotonFeeder feeder, int millimeters) throws Exception {
            feeder.feedMillimeters(millimeters);
        }

        @Override
        public void feedPart(PhotonFeeder feeder) throws Exception {
            feeder.feed(null);
        }
    };

    public static class Result {
        public final int partsFed;
        public final double askedMm;
        public final double measuredMm;
        public final Double scaleBefore;
        public final Double scaleAfter;

        Result(int partsFed, double askedMm, double measuredMm, Double scaleBefore, Double scaleAfter) {
            this.partsFed = partsFed;
            this.askedMm = askedMm;
            this.measuredMm = measuredMm;
            this.scaleBefore = scaleBefore;
            this.scaleAfter = scaleAfter;
        }

        /**
         * The feed error measured, in percent of the distance asked for.
         */
        public double getErrorPercent() {
            return (measuredMm - askedMm) / askedMm * 100;
        }
    }

    public interface Progress {
        void update(String stage);
    }

    private final TapeTracker tracker;
    private final Mover mover;

    public PhotonFeedCalibrator(TapeTracker tracker, Mover mover) {
        this.tracker = tracker;
        this.mover = mover;
    }

    /**
     * Parts to feed: at least 40 mm, and a whole number of 4 mm hole pitches.
     */
    public static int partsToFeed(int pitchMm) {
        int parts = (int) Math.ceil(MIN_DISTANCE_MM / pitchMm);
        while ((parts * pitchMm) % (int) HOLE_PITCH_MM != 0) {
            parts++;
        }
        return parts;
    }

    public Result calibrate(PhotonFeeder feeder, Progress progress) throws Exception {
        int pitchMm = feeder.getPartPitch();
        if (pitchMm <= 0) {
            throw new Exception("Set the feeder's part pitch first.");
        }

        Double before = feeder.readFeedScale();
        if (before == null) {
            throw new Exception(String.format("Feeder %s has no feed scale. Ratchet feeders don't need one, "
                    + "and stock feeders need the new firmware.", feeder.getName()));
        }

        report(progress, "Finding the sprocket holes");
        Location[] start = tracker.find(feeder, feeder.getPickLocation());

        report(progress, "Feeding 1 mm to find the feed direction");
        mover.feedMillimeters(feeder, 1);
        Location[] stepped = tracker.track(feeder, start);
        Location step = midpoint(stepped).subtract(midpoint(start));
        double stepMm = Math.hypot(step.getX(), step.getY());
        if (stepMm < 0.5 || stepMm > 1.5) {
            throw new Exception(String.format(
                    "The tape moved %.2f mm for a 1 mm feed. Check that the tape is engaged and the feeder feeds.", stepMm));
        }
        double ux = step.getX() / stepMm;
        double uy = step.getY() / stepMm;

        int parts = partsToFeed(pitchMm);
        double askedMm = parts * pitchMm;
        for (int i = 0; i < parts; i++) {
            report(progress, String.format("Feeding %d mm (%d of %d parts)", (int) askedMm, i + 1, parts));
            mover.feedPart(feeder);
        }

        report(progress, "Finding the same sprocket holes again");
        Location[] end = tracker.track(feeder, stepped);
        Location drift = midpoint(end).subtract(midpoint(stepped));
        double errorMm = drift.getX() * ux + drift.getY() * uy;
        double measuredMm = askedMm + errorMm;

        // Bring a pocket back to the pick point: 1 mm was fed on top of whole part pitches.
        if (pitchMm > 1) {
            mover.feedMillimeters(feeder, pitchMm - 1);
        }

        report(progress, "Correcting the feeder's feed scale");
        Double after = feeder.correctFeedScale(Math.round(askedMm * 1000), Math.round(measuredMm * 1000));
        Logger.info("Photon feeder {}: asked {} mm, tape moved {} mm, feed scale {} % -> {} %",
                feeder.getName(), askedMm, String.format("%.3f", measuredMm), before, after);
        return new Result(parts + 1, askedMm, measuredMm, before, after);
    }

    private static Location midpoint(Location[] holes) {
        return holes[1].convertToUnits(LengthUnit.Millimeters)
                .add(holes[2].convertToUnits(LengthUnit.Millimeters))
                .multiply(0.5, 0.5, 0, 0);
    }

    private static void report(Progress progress, String stage) {
        if (progress != null) {
            progress.update(stage);
        }
    }
}
