package org.openpnp.machine.photon;

import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Feeder;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Sets up every Photon feeder on the machine in one go: finds the feeders on the bus, reads the
 * feeder records (part, pitch, tape width), then measures each occupied slot's pick point with
 * the camera and stores it as the slot location.
 *
 * The slot locations must already be roughly right, for example filled in from the rails
 * ({@link PhotonFeederSlots#fillRail}). The measured pick point goes into the slot location and
 * the feeder's offset is set to zero, so a slot's location is its exact pick point.
 *
 * Feeders without a recorded reel are skipped: without the pitch from the record, vision could
 * lock onto the wrong pocket.
 *
 * With a pocket checker, each pick point is also looked at to see whether a part sits in the
 * pocket. If asked to, empty pockets (the leader of a freshly loaded tape) are fed past until a
 * part shows up, and the feeder's count is put back, since those pockets held no parts.
 *
 * With a height prober, the nozzle also touches down on the part at each pick point and the
 * slot's Z becomes the height it touched at. Pockets that look empty aren't probed, since the
 * nozzle would go down into the pocket instead of stopping on a part.
 */
public class PhotonAutoSetup {
    /**
     * Measures the exact pick point of a feeder, starting from an estimate.
     */
    public interface PickPointFinder {
        Location find(PhotonFeeder feeder, Location estimate) throws Exception;

        /**
         * Goes up as the finder learns from feeders it measured, so one that failed before
         * may be worth another try. Zero if it never learns.
         */
        default int learned() {
            return 0;
        }
    }

    /**
     * Scores the pocket at a pick point: higher means more likely a part is in it.
     */
    public interface PocketChecker {
        double score(PhotonFeeder feeder, Location pick) throws Exception;
    }

    /**
     * Touches down at a pick point and returns where contact was made.
     */
    public interface HeightProber {
        Location probe(PhotonFeeder feeder, Location pick) throws Exception;
    }

    /**
     * Moves the tape on by one part and corrects the count. Separate so tests need no bus.
     */
    public interface TapeMover {
        void feedOnePart(PhotonFeeder feeder) throws Exception;

        void setPartsLeft(PhotonFeeder feeder, long partsLeft) throws Exception;
    }

    public static final TapeMover FEEDER_TAPE_MOVER = new TapeMover() {
        @Override
        public void feedOnePart(PhotonFeeder feeder) throws Exception {
            feeder.feed(null);
        }

        @Override
        public void setPartsLeft(PhotonFeeder feeder, long partsLeft) throws Exception {
            feeder.setRecordPartsLeft(partsLeft);
        }
    };

    public static final int MAX_EMPTY_POCKETS = 10;

    // A probed height further than this from the taught one is more likely a wrong touch
    // (an empty pocket, the tape edge) than a real difference, so it isn't used.
    public static final double MAX_HEIGHT_CHANGE_MM = 1.5;

    public static final String NO_REEL_RECORDED = "Skipped: no reel recorded";

    public static class Result {
        public final PhotonFeeder feeder;
        public final int slotAddress;
        public final Location before;
        public final Location after;
        public final String problem;
        // The last pocket score, null if pockets weren't checked
        public final Double pocketScore;
        public final boolean partFound;
        public final int emptyPocketsFed;
        // How far the probed pick height is from the taught one, in mm, null if not probed
        public final Double heightChangeMm;

        Result(PhotonFeeder feeder, int slotAddress, Location before, Location after, String problem) {
            this(feeder, slotAddress, before, after, problem, null, false, 0);
        }

        Result(PhotonFeeder feeder, int slotAddress, Location before, Location after, String problem,
               Double pocketScore, boolean partFound, int emptyPocketsFed) {
            this(feeder, slotAddress, before, after, problem, pocketScore, partFound, emptyPocketsFed, null);
        }

        Result(PhotonFeeder feeder, int slotAddress, Location before, Location after, String problem,
               Double pocketScore, boolean partFound, int emptyPocketsFed, Double heightChangeMm) {
            this.feeder = feeder;
            this.slotAddress = slotAddress;
            this.before = before;
            this.after = after;
            this.problem = problem;
            this.pocketScore = pocketScore;
            this.partFound = partFound;
            this.emptyPocketsFed = emptyPocketsFed;
            this.heightChangeMm = heightChangeMm;
        }

        Result withHeight(Location after, String problem, Double heightChangeMm) {
            return new Result(feeder, slotAddress, before, after, problem, pocketScore, partFound,
                    emptyPocketsFed, heightChangeMm);
        }

        public boolean isOk() {
            return problem == null;
        }

        public boolean isSkipped() {
            return NO_REEL_RECORDED.equals(problem);
        }

        /**
         * How far the measured pick point is from the estimate, in mm, or null if not measured.
         */
        public Double getCorrectionMm() {
            if (before == null || after == null) {
                return null;
            }
            return before.convertToUnits(LengthUnit.Millimeters)
                    .getLinearDistanceTo(after.convertToUnits(LengthUnit.Millimeters));
        }
    }

    private final PickPointFinder finder;
    private PocketChecker pocketChecker;
    private double partScore;
    private boolean feedPastEmptyPockets;
    private TapeMover tapeMover = FEEDER_TAPE_MOVER;
    private HeightProber heightProber;

    public PhotonAutoSetup(PickPointFinder finder) {
        this.finder = finder;
    }

    /**
     * Also scores each pocket, taking a score of at least partScore to mean a part. With
     * feedPastEmptyPockets, empty pockets are fed past, at most MAX_EMPTY_POCKETS per feeder.
     */
    public PhotonAutoSetup withPocketCheck(PocketChecker checker, double partScore, boolean feedPastEmptyPockets) {
        this.pocketChecker = checker;
        this.partScore = partScore;
        this.feedPastEmptyPockets = feedPastEmptyPockets;
        return this;
    }

    /**
     * Also probes each pick height and stores it as the slot's Z.
     */
    public PhotonAutoSetup withHeightProbe(HeightProber prober) {
        this.heightProber = prober;
        return this;
    }

    PhotonAutoSetup withTapeMover(TapeMover tapeMover) {
        this.tapeMover = tapeMover;
        return this;
    }

    /**
     * Searches the bus, reads the records and measures every Photon feeder that has a slot.
     *
     * @param searchProgress progress of the bus search, may be null
     * @param results called with each feeder's result as soon as it is known, may be null
     */
    public List<Result> run(PhotonFeeder.FeederSearchProgressConsumer searchProgress,
                            Consumer<Result> results) throws Exception {
        PhotonFeeder.findAllFeeders(searchProgress);
        PhotonFeeder.readAllRecordsQuietly();
        return measureAll(results);
    }

    /**
     * Measures every Photon feeder that has a slot, without searching the bus first.
     */
    public List<Result> measureAll(Consumer<Result> results) {
        List<Result> all = new ArrayList<>();
        // What the finder knew when each feeder's holes weren't found
        List<Integer> learnedAtFailure = new ArrayList<>();
        for (Feeder feeder : new ArrayList<>(Configuration.get().getMachine().getFeeders())) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }
            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;
            if (photonFeeder.getSlotAddress() == null) {
                continue;
            }

            int learned = finder.learned();
            Result result = measure(photonFeeder);
            all.add(result);
            learnedAtFailure.add(learned);
            if (results != null) {
                results.accept(result);
            }
        }

        // Feeders measured before the finder learned something from later ones get another try
        for (int i = 0; i < all.size(); i++) {
            Result result = all.get(i);
            if (result.isOk() || result.before == null || result.after != null
                    || finder.learned() <= learnedAtFailure.get(i)) {
                continue;
            }
            Logger.info("Trying Photon feeder {} in slot {} again.", result.feeder.getHardwareId(), result.slotAddress);
            Result again = measure(result.feeder);
            all.set(i, again);
            if (results != null) {
                results.accept(again);
            }
        }
        return all;
    }

    public Result measure(PhotonFeeder feeder) {
        int address = feeder.getSlotAddress();
        PhotonFeederSlots.Slot slot = feeder.getSlot();
        Location slotLocation = slot.getLocation();

        PhotonFeederRecord record = feeder.getRecord();
        if (record == null || !record.loaded || record.pitchTenthsMm == 0) {
            return new Result(feeder, address, null, null, NO_REEL_RECORDED);
        }

        if (slotLocation == null) {
            return new Result(feeder, address, null, null,
                    "The slot has no location yet. Teach its rail and fill the slots first.");
        }

        Location estimate = slotLocation;
        Location offset = feeder.getOffset();
        if (offset != null) {
            estimate = offset.offsetWithRotationFrom(slotLocation);
        }

        Location measured;
        try {
            measured = finder.find(feeder, estimate);
        }
        catch (Exception e) {
            Logger.warn(e, "Auto setup of Photon feeder {} in slot {} failed", feeder.getHardwareId(), address);
            Result failed = new Result(feeder, address, estimate, null, e.getMessage());
            if (heightProber == null || feeder.hasPlaceholderPart()) {
                return failed;
            }
            // The pick point stays where it was aligned by hand, but its height can still be probed there
            return probeAlignedHeight(failed, slot, estimate);
        }

        // Vision measures X and Y only. Z (tape height) and rotation stay as taught.
        Location newSlotLocation = measured.convertToUnits(slotLocation.getUnits())
                .derive(null, null, slotLocation.getZ(), slotLocation.getRotation());
        slot.setLocation(newSlotLocation);
        // The pick point is the slot location now, but the part's rotation in the tape stays: the
        // one the feeder holds for this reel, else the one set here before
        PhotonFeederRecord held = feeder.getRecord();
        Double rotationInTape = held != null && held.loaded && feeder.getPart() != null
                && held.matchesPartId(feeder.getPart().getId()) ? held.getRotationDegrees() : null;
        if (rotationInTape == null) {
            rotationInTape = feeder.getOffset() == null ? 0.0 : feeder.getOffset().getRotation();
        }
        feeder.setOffset(new Location(slotLocation.getUnits()).derive(null, null, null, rotationInTape));

        // A feeder with only the placeholder part has no real part to look for or probe
        boolean placeholder = feeder.hasPlaceholderPart();
        Result result = pocketChecker == null || placeholder
                ? new Result(feeder, address, estimate, newSlotLocation, null)
                : checkPocket(feeder, address, estimate, newSlotLocation);
        if (heightProber == null || placeholder || !result.isOk()
                || (result.pocketScore != null && !result.partFound)) {
            return result;
        }
        return probeHeight(result, slot);
    }

    private Result probeHeight(Result result, PhotonFeederSlots.Slot slot) {
        Location pick = result.after;
        Location probed;
        try {
            probed = heightProber.probe(result.feeder, pick);
        }
        catch (Exception e) {
            Logger.warn(e, "Pick height probe of Photon feeder {} in slot {} failed",
                    result.feeder.getHardwareId(), result.slotAddress);
            return result.withHeight(pick, "Pick height probe failed: " + e.getMessage(), null);
        }

        double changeMm = probed.convertToUnits(LengthUnit.Millimeters).getZ()
                - pick.convertToUnits(LengthUnit.Millimeters).getZ();
        if (Math.abs(changeMm) > MAX_HEIGHT_CHANGE_MM) {
            return result.withHeight(pick, String.format(Locale.US,
                    "The nozzle touched %.2f mm from the taught height, so the taught height was kept. "
                    + "Check the pocket and the probe settings.", changeMm), changeMm);
        }
        Location newPick = pick.derive(null, null, probed.convertToUnits(pick.getUnits()).getZ(), null);
        slot.setLocation(newPick);
        return result.withHeight(newPick, null, changeMm);
    }

    /**
     * Probes the pick height at the pick point as it is, when vision couldn't measure it. Only
     * the slot's Z changes. The result still carries the vision problem.
     */
    private Result probeAlignedHeight(Result failed, PhotonFeederSlots.Slot slot, Location pick) {
        Location probed;
        try {
            probed = heightProber.probe(failed.feeder, pick);
        }
        catch (Exception e) {
            Logger.warn(e, "Pick height probe of Photon feeder {} in slot {} failed",
                    failed.feeder.getHardwareId(), failed.slotAddress);
            return failed.withHeight(null, failed.problem + " The pick height probe failed too: " + e.getMessage(), null);
        }
        double changeMm = probed.convertToUnits(LengthUnit.Millimeters).getZ()
                - pick.convertToUnits(LengthUnit.Millimeters).getZ();
        if (Math.abs(changeMm) > MAX_HEIGHT_CHANGE_MM) {
            return failed.withHeight(null, failed.problem + String.format(Locale.US,
                    " The nozzle touched %.2f mm from the taught height, so the taught height was kept.", changeMm),
                    changeMm);
        }
        Location slotLocation = slot.getLocation();
        slot.setLocation(slotLocation.derive(null, null,
                probed.convertToUnits(slotLocation.getUnits()).getZ(), null));
        return failed.withHeight(null, failed.problem
                + " The pick point was kept as aligned and its height was probed.", changeMm);
    }

    private Result checkPocket(PhotonFeeder feeder, int address, Location estimate, Location pick) {
        long partsLeftBefore = feeder.getRecord().partsLeft;
        int fed = 0;
        double score;
        try {
            score = pocketChecker.score(feeder, pick);
            while (feedPastEmptyPockets && score < partScore && fed < MAX_EMPTY_POCKETS) {
                tapeMover.feedOnePart(feeder);
                fed++;
                score = pocketChecker.score(feeder, pick);
            }
        }
        catch (Exception e) {
            Logger.warn(e, "Pocket check of Photon feeder {} in slot {} failed", feeder.getHardwareId(), address);
            restoreCount(feeder, fed, partsLeftBefore);
            return new Result(feeder, address, estimate, pick, "Pocket check failed: " + e.getMessage(),
                    null, false, fed);
        }

        boolean partFound = score >= partScore;
        String problem = restoreCount(feeder, fed, partsLeftBefore);
        if (!partFound && feedPastEmptyPockets) {
            problem = String.format("No part found after feeding %d empty pockets. Is the tape loaded?", fed);
        }
        return new Result(feeder, address, estimate, pick, problem, score, partFound, fed);
    }

    /**
     * Empty pockets fed past held no parts, so the count goes back to what it was.
     */
    private String restoreCount(PhotonFeeder feeder, int fed, long partsLeft) {
        if (fed == 0) {
            return null;
        }
        try {
            tapeMover.setPartsLeft(feeder, partsLeft);
            return null;
        }
        catch (Exception e) {
            Logger.warn(e, "Could not restore the count of Photon feeder {}", feeder.getHardwareId());
            return String.format("Fed %d empty pockets but could not correct the count: %s", fed, e.getMessage());
        }
    }
}
