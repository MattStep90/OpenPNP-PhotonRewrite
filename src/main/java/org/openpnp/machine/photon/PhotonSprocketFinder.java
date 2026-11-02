package org.openpnp.machine.photon;

import org.opencv.imgcodecs.Imgcodecs;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.FeederVisionHelper;
import org.openpnp.util.FeederVisionHelper.FeederVisionHelperParams;
import org.openpnp.util.FeederVisionHelper.FindFeaturesMode;
import org.openpnp.util.FeederVisionHelper.PipelineType;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.DetectCircularSymmetry;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a Photon feeder's pick point from the sprocket holes of its tape, using the same vision
 * as the push-pull and Pandaplacer feeders. The camera starts at the estimated pick point, finds
 * the holes, and then centers on them for a few passes until the result settles.
 *
 * Both of OpenPnP's stock pipelines are tried, the preferred one first: color keyed (looks for
 * a colored feeder surface showing through the holes) and circular symmetry (looks for round
 * shapes in brightness only).
 */
public class PhotonSprocketFinder implements PhotonAutoSetup.PickPointFinder {
    static final double SPROCKET_HOLE_DIAMETER_MM = 1.5;
    static final double SPROCKET_HOLE_PITCH_MM = 4;
    static final int CALIBRATION_PASSES = 3;
    static final double CALIBRATION_TOLERANCE_MM = 0.05;

    private final Camera camera;
    private final PipelineType preferredPipeline;
    private final long showResultMilliseconds;

    public PhotonSprocketFinder(Camera camera, PipelineType preferredPipeline, long showResultMilliseconds) {
        this.camera = camera;
        this.preferredPipeline = preferredPipeline;
        this.showResultMilliseconds = showResultMilliseconds;
    }

    @Override
    public Location find(PhotonFeeder feeder, Location estimate) throws Exception {
        return measure(feeder, estimate)[0];
    }

    // Which way the sprocket holes lie from the pick point, learned from each feeder measured:
    // {pick X, pick Y, unit X, unit Y} in mm. Feeders on the same rail share it.
    private final List<double[]> holeSides = new ArrayList<>();
    // A feeder within this distance (mm, across the rails) is taken to be on the same rail
    static final double SAME_RAIL_MM = 60;

    @Override
    public int learned() {
        return holeSides.size();
    }

    /**
     * The unit vector from the pick point toward the sprocket holes, as learned from the
     * nearest measured feeder on the same rail. Feeders on the facing rail are turned round, so
     * one learned there counts the other way round. Null if nothing is known yet.
     */
    Location holeSide(Location estimate) {
        Location e = estimate.convertToUnits(LengthUnit.Millimeters);
        double[] sameRail = null;
        double sameDistance = Double.MAX_VALUE;
        double[] otherRail = null;
        double otherDistance = Double.MAX_VALUE;
        for (double[] side : holeSides) {
            double dx = e.getX() - side[0];
            double dy = e.getY() - side[1];
            // The holes lie along the rail from the pick point, so the rail runs along (unit X, Y)
            double acrossRail = Math.abs(dx * side[3] - dy * side[2]);
            double distance = Math.hypot(dx, dy);
            if (acrossRail < SAME_RAIL_MM) {
                if (distance < sameDistance) {
                    sameRail = side;
                    sameDistance = distance;
                }
            }
            else if (distance < otherDistance) {
                otherRail = side;
                otherDistance = distance;
            }
        }
        if (sameRail != null) {
            return new Location(LengthUnit.Millimeters, sameRail[2], sameRail[3], 0, 0);
        }
        if (otherRail != null) {
            return new Location(LengthUnit.Millimeters, -otherRail[2], -otherRail[3], 0, 0);
        }
        return null;
    }

    void learnHoleSide(Location pick, Location hole1, Location hole2) {
        Location p = pick.convertToUnits(LengthUnit.Millimeters);
        Location h1 = hole1.convertToUnits(LengthUnit.Millimeters);
        Location h2 = hole2.convertToUnits(LengthUnit.Millimeters);
        double[] n = towardLine(p, h1, h2);
        if (n != null) {
            holeSides.add(new double[]{p.getX(), p.getY(), n[0], n[1]});
        }
    }

    /**
     * The unit vector from a point straight toward the line through two holes, and the
     * distance, as {x, y, distance}. Null if the holes coincide or the point is on the line.
     */
    static double[] towardLine(Location point, Location hole1, Location hole2) {
        double ux = hole2.getX() - hole1.getX();
        double uy = hole2.getY() - hole1.getY();
        double length = Math.hypot(ux, uy);
        if (length < 1e-9) {
            return null;
        }
        ux /= length;
        uy /= length;
        double px = point.getX() - hole1.getX();
        double py = point.getY() - hole1.getY();
        double along = px * ux + py * uy;
        double nx = along * ux - px;
        double ny = along * uy - py;
        double distance = Math.hypot(nx, ny);
        if (distance < 1e-9) {
            return null;
        }
        return new double[]{nx / distance, ny / distance, distance};
    }

    /**
     * The pick point on a tape of this width with the sprocket holes found, nearest the estimate:
     * EIA-481 puts the pocket centre (W / 2 - 0.5) mm from the hole line (3.5 mm on 8 mm tape,
     * 5.5 mm on 12 mm), on the estimate's side. Along the tape it stays where the estimate is;
     * calibrating snaps it to the pocket grid. Null if it can't be worked out.
     */
    static Location pickFromHoles(Location estimate, Location hole1, Location hole2, int tapeWidthMm) {
        if (tapeWidthMm < 8) {
            return null;
        }
        Location e = estimate.convertToUnits(LengthUnit.Millimeters);
        Location h1 = hole1.convertToUnits(LengthUnit.Millimeters);
        Location h2 = hole2.convertToUnits(LengthUnit.Millimeters);
        double[] n = towardLine(e, h1, h2);
        if (n == null) {
            return null;
        }
        // The foot of the estimate on the hole line, then back out by the EIA distance
        double offset = tapeWidthMm / 2.0 - 0.5;
        double footX = e.getX() + n[0] * n[2];
        double footY = e.getY() + n[1] * n[2];
        return new Location(LengthUnit.Millimeters, footX - n[0] * offset, footY - n[1] * offset, e.getZ(), 0)
                .convertToUnits(estimate.getUnits());
    }

    /**
     * Puts the holes in the order FeederVisionHelper expects from the pick point (hole 1 to
     * hole 2 counter-clockwise) and returns {pick with the tape angle, hole 1, hole 2}.
     */
    static Location[] orderHoles(Location pick, Location hole1, Location hole2) {
        Location p = pick.convertToUnits(LengthUnit.Millimeters);
        Location h1 = hole1.convertToUnits(LengthUnit.Millimeters);
        Location h2 = hole2.convertToUnits(LengthUnit.Millimeters);
        double angle1 = Math.atan2(h1.getY() - p.getY(), h1.getX() - p.getX());
        double angle2 = Math.atan2(h2.getY() - p.getY(), h2.getX() - p.getX());
        if (org.openpnp.util.Utils2D.angleNorm(Math.toDegrees(angle2 - angle1), 180) > 0) {
            Location swap = hole1;
            hole1 = hole2;
            hole2 = swap;
            h1 = hole1.convertToUnits(LengthUnit.Millimeters);
            h2 = hole2.convertToUnits(LengthUnit.Millimeters);
        }
        double angleTape = Math.toDegrees(Math.atan2(h2.getY() - h1.getY(), h2.getX() - h1.getX()));
        return new Location[]{pick.derive(null, null, null, angleTape), hole1, hole2};
    }

    /**
     * Finds the pick point and the two sprocket holes it is measured from, starting at an
     * estimated pick point. Returns {pick, hole 1, hole 2}.
     */
    public Location[] measure(PhotonFeeder feeder, Location estimate) throws Exception {
        estimate = alongTheTape(feeder, estimate);
        if (camera.isUnitsPerPixelAtZCalibrated() && !estimate.getLengthZ().isInitialized()) {
            throw new Exception("The slot has no Z. Set the tape height on its rail so the camera scale is right.");
        }

        Exception firstError = null;
        for (PipelineType type : pipelineOrder()) {
            try {
                FeederVisionHelper feature = null;
                Location shift = null;
                Exception firstLookError = null;
                Location side = holeSide(estimate);
                List<Location> shifts = firstLookShifts(estimate, side, tapeWidth(feeder));
                for (int look = 0; look < shifts.size(); look++) {
                    Location tryShift = shifts.get(look);
                    boolean far = look >= NEAR_LOOKS;
                    try {
                        FeederVisionHelper found = firstLook(feeder, type, estimate, tryShift, far, look == 0);
                        if (far && !onHoleSide(estimate, side, found.getCalibratedHole1Location(),
                                found.getCalibratedHole2Location())) {
                            throw new Exception("The line of holes found is on the wrong side of the pick point");
                        }
                        feature = found;
                        shift = tryShift;
                        break;
                    }
                    catch (Exception e) {
                        Logger.debug("Photon feeder {}: first look with {} shifted by {} failed: {}",
                                feeder.getHardwareId(), type, tryShift, e.getMessage());
                        if (firstLookError == null) {
                            firstLookError = e;
                        }
                    }
                }
                if (feature == null) {
                    throw firstLookError;
                }
                Location pick = feature.getCalibratedPickLocation();
                Location hole1 = feature.getCalibratedHole1Location();
                Location hole2 = feature.getCalibratedHole2Location();
                // The camera may have looked from beside the pick point. With the tape width
                // known, the pick point follows from the holes; else take the shift back off.
                Location fromHoles = pickFromHoles(estimate, hole1, hole2, tapeWidth(feeder));
                if (fromHoles != null) {
                    Location[] ordered = orderHoles(fromHoles, hole1, hole2);
                    pick = ordered[0];
                    hole1 = ordered[1];
                    hole2 = ordered[2];
                }
                else {
                    pick = pick.subtract(shift.convertToUnits(pick.getUnits()));
                }
                Location[] found = calibrate(feeder, type, pick, hole1, hole2, estimate);
                learnHoleSide(found[0], found[1], found[2]);
                lastType = type;

                MovableUtils.moveToLocationAtSafeZ(camera, found[0].derive(null, null, estimate.getZ(), 0.0));
                MovableUtils.fireTargetedUserAction(camera);
                return found;
            }
            catch (Exception e) {
                Logger.debug(e, "Photon feeder {}: no sprocket holes found with the {} pipeline",
                        feeder.getHardwareId(), type);
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        throw new Exception("No sprocket holes found: " + firstError.getMessage(), firstError);
    }

    /**
     * The estimate turned to the slot's rotation, which gives the tape's direction. A pick
     * location also carries the part's rotation in the tape, which says nothing about the tape.
     */
    static Location alongTheTape(PhotonFeeder feeder, Location estimate) {
        try {
            Location slot = feeder.getSlot().getLocation();
            return slot == null ? estimate : estimate.derive(null, null, null, slot.getRotation());
        }
        catch (Exception e) {
            return estimate;
        }
    }

    /**
     * Where to look from for the first look, relative to the estimated pick point. Straight on
     * first, then half a hole pitch along the tape, then across it. A pick point in line with a
     * hole puts the holes either side of it too close to the picture's edge to be found, so then
     * the half pitch shift brings two holes well into view.
     */
    static List<Location> firstLookShifts(Location estimate, Location side, int tapeWidthMm) {
        double half = SPROCKET_HOLE_PITCH_MM / 2;
        Location along = new Location(LengthUnit.Millimeters, 0, 1, 0, 0).rotateXy(estimate.getRotation());
        Location across = new Location(LengthUnit.Millimeters, half, 0, 0, 0).rotateXy(estimate.getRotation());
        List<Location> shifts = new ArrayList<>();
        shifts.add(new Location(LengthUnit.Millimeters));
        shifts.add(along.multiply(half, half, 0, 0));
        shifts.add(across);
        // With the pick point at the end of the window, the cover tape hides half the picture
        // and only one hole shows. Step along the tape into the window, or back if the estimate
        // went past it.
        for (double mm : ALONG_LOOK_MM) {
            shifts.add(along.multiply(mm, mm, 0, 0));
        }
        // With the estimate off the tape, the holes sit at the picture's edge and aren't found.
        // Step toward them, if other feeders on this rail showed which way. Vision only takes a
        // line of holes at least 2.9 mm from the camera, so the steps go on past the holes too,
        // but no further than the tape's far edge would be: beyond that is the next feeder.
        if (side != null) {
            double farthest = (tapeWidthMm >= 8 ? tapeWidthMm : 8) / 2.0 + 1;
            for (double mm : FAR_LOOK_MM) {
                if (mm > farthest) {
                    break;
                }
                shifts.add(side.multiply(mm, mm, 0, 0));
                shifts.add(side.multiply(mm, mm, 0, 0).add(along.multiply(SPROCKET_HOLE_PITCH_MM,
                        SPROCKET_HOLE_PITCH_MM, 0, 0)));
            }
        }
        return shifts;
    }

    // How far along the tape the extra looks step, in mm: into the window first
    static final double[] ALONG_LOOK_MM = {4, 8, -4};
    // How far toward the holes the far looks step, in mm
    static final double[] FAR_LOOK_MM = {2.5, 5, 7.5, 10};
    // Looks before the far ones: straight on, along, across and the steps along the tape
    static final int NEAR_LOOKS = 3 + ALONG_LOOK_MM.length;
    // The far looks also take holes a little less round, e.g. with the feeder's frame over an edge
    static final double FAR_LOOK_MIN_SYMMETRY = 0.8;

    /**
     * Whether the line through two holes lies on the given side of the estimated pick point.
     * Stepping past the holes, the camera can't mistake the pockets for them this way.
     */
    static boolean onHoleSide(Location estimate, Location side, Location hole1, Location hole2) {
        Location e = estimate.convertToUnits(LengthUnit.Millimeters);
        double[] n = towardLine(e, hole1.convertToUnits(LengthUnit.Millimeters),
                hole2.convertToUnits(LengthUnit.Millimeters));
        return n != null && n[0] * side.getX() + n[1] * side.getY() > 0.5;
    }

    /**
     * Looks from the estimated pick point plus a shift and finds the holes anywhere in view.
     * The returned pick location is where the camera was.
     */
    private FeederVisionHelper firstLook(PhotonFeeder feeder, PipelineType type, Location estimate,
                                         Location shift, boolean far, boolean keepPicture) throws Exception {
        Location view = estimate.add(shift.convertToUnits(estimate.getUnits()).derive(null, null, 0.0, 0.0))
                .derive(null, null, null, 0.0);
        MovableUtils.moveToLocationAtSafeZ(camera, view);
        try (CvPipeline pipeline = createPipeline(feeder, type, null)) {
            if (far) {
                for (CvStage stage : pipeline.getStages()) {
                    if (stage instanceof DetectCircularSymmetry) {
                        ((DetectCircularSymmetry) stage).setMinSymmetry(FAR_LOOK_MIN_SYMMETRY);
                    }
                }
            }
            try {
                return new FeederVisionHelper(params(pipeline, type, partPitch(feeder), view, view, view))
                        .findFeatures(FindFeaturesMode.FromPickLocationGetHoles);
            }
            catch (Exception e) {
                saveFailedImage(pipeline, feeder, type, keepPicture);
                throw e;
            }
        }
    }

    /**
     * Finds the same two sprocket holes again after the tape has moved less than half a hole
     * pitch, e.g. after a short feed. Takes and returns {pick, hole 1, hole 2}.
     */
    public Location[] track(PhotonFeeder feeder, Location[] previous) throws Exception {
        PipelineType type = lastType != null ? lastType : preferredPipeline;
        return calibrate(feeder, type, previous[0], previous[1], previous[2], previous[0]);
    }

    private PipelineType lastType;

    /**
     * Keeps the picture the holes weren't found in, with how many hole candidates were seen,
     * so a failure can be looked at afterwards. Never throws.
     */
    private void saveFailedImage(CvPipeline pipeline, PhotonFeeder feeder, PipelineType type, boolean keepPicture) {
        try {
            int candidates = 0;
            CvStage.Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
            if (result != null && result.model instanceof List) {
                candidates = ((List<?>) result.model).size();
            }
            CvStage.Result capture = pipeline.getResult("0");
            if (!keepPicture) {
                // Only the straight-on look's picture is kept, so the later looks don't replace it
                Logger.info("Photon feeder {}: {} pipeline saw {} hole candidates from {}.",
                        feeder.getHardwareId(), type, candidates, camera.getLocation());
                return;
            }
            if (capture == null || capture.image == null) {
                Logger.info("Photon feeder {}: {} pipeline saw {} hole candidates, no picture to save.",
                        feeder.getHardwareId(), type, candidates);
                return;
            }
            File file = new File(Configuration.get().getResourceDirectory(getClass()),
                    String.format("no-holes-%s-%s.png", feeder.getHardwareId(), type));
            Imgcodecs.imwrite(file.getAbsolutePath(), capture.image);
            Location upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
            Logger.info("Photon feeder {}: {} pipeline saw {} hole candidates. Picture saved to {}",
                    feeder.getHardwareId(), type, candidates, file.getAbsolutePath());
            Logger.info("Photon feeder {}: camera at {}, scale {} x {} mm/px, so a hole should be {} px across.",
                    feeder.getHardwareId(), camera.getLocation(), String.format("%.5f", upp.getX()),
                    String.format("%.5f", upp.getY()), String.format("%.0f", SPROCKET_HOLE_DIAMETER_MM / upp.getX()));
        }
        catch (Exception e) {
            Logger.debug(e, "Could not save the sprocket picture");
        }
    }

    private PipelineType[] pipelineOrder() {
        if (preferredPipeline == PipelineType.CircularSymmetry) {
            return new PipelineType[]{PipelineType.CircularSymmetry, PipelineType.ColorKeyed};
        }
        return new PipelineType[]{PipelineType.ColorKeyed, PipelineType.CircularSymmetry};
    }

    private int tapeWidth(PhotonFeeder feeder) {
        PhotonFeederRecord record = feeder.getRecord();
        return record != null && record.loaded && record.tapeWidthMm > 0 ? record.tapeWidthMm : feeder.getTapeWidth();
    }

    private Length partPitch(PhotonFeeder feeder) {
        PhotonFeederRecord record = feeder.getRecord();
        return record != null && record.loaded && record.pitchTenthsMm > 0
                ? new Length(record.pitchTenthsMm / 10.0, LengthUnit.Millimeters)
                : new Length(feeder.getPartPitch(), LengthUnit.Millimeters);
    }

    /**
     * Centers on the holes so lens distortion cancels out, until the pick point settles.
     */
    private Location[] calibrate(PhotonFeeder feeder, PipelineType type, Location pick, Location hole1,
                                 Location hole2, Location estimate) throws Exception {
        Length partPitch = partPitch(feeder);
        for (int pass = 0; pass < CALIBRATION_PASSES; pass++) {
            Location midPoint = hole1.add(hole2).multiply(0.5, 0.5, 0, 0)
                    .derive(null, null, estimate.getZ(), 0.0);
            MovableUtils.moveToLocationAtSafeZ(camera, midPoint);

            FeederVisionHelper feature;
            try (CvPipeline pipeline = createPipeline(feeder, type, hole1.getLinearLengthTo(hole2))) {
                feature = new FeederVisionHelper(params(pipeline, type, partPitch, pick, hole1, hole2))
                        .findFeatures(FindFeaturesMode.CalibrateHoles);
            }

            Location next = feature.getCalibratedPickLocation();
            double moved = next.convertToUnits(LengthUnit.Millimeters)
                    .getLinearDistanceTo(pick.convertToUnits(LengthUnit.Millimeters));
            pick = next;
            hole1 = feature.getCalibratedHole1Location();
            hole2 = feature.getCalibratedHole2Location();
            if (moved < CALIBRATION_TOLERANCE_MM) {
                break;
            }
        }
        return new Location[]{pick, hole1, hole2};
    }

    private FeederVisionHelperParams params(CvPipeline pipeline, PipelineType type, Length partPitch,
                                            Location pick, Location hole1, Location hole2) {
        // A Photon feeder moves the tape by one part pitch per feed.
        return new FeederVisionHelperParams(camera, type, pipeline, showResultMilliseconds,
                true, false, partPitch, partPitch, 1, pick, hole1, hole2,
                1.95, 0.6);
    }

    private CvPipeline createPipeline(PhotonFeeder feeder, PipelineType type, Length holeDistance) {
        CvPipeline pipeline = FeederVisionHelper.createDefaultPipeline(type);
        pipeline.setProperty("camera", camera);
        pipeline.setProperty("feeder", feeder);
        pipeline.setProperty("sprocketHole.diameter",
                new Length(SPROCKET_HOLE_DIAMETER_MM, LengthUnit.Millimeters));

        Length range;
        if (holeDistance == null) {
            // Search the whole view, the holes may be anywhere in it.
            Location upp = camera.getUnitsPerPixelAtZ();
            range = camera.getWidth() > camera.getHeight()
                    ? upp.getLengthX().multiply(camera.getHeight())
                    : upp.getLengthY().multiply(camera.getWidth());
        }
        else {
            range = holeDistance.multiply(0.5).add(new Length(SPROCKET_HOLE_PITCH_MM, LengthUnit.Millimeters));
        }
        pipeline.setProperty("sprocketHole.maxDistance", range);
        return pipeline;
    }
}
