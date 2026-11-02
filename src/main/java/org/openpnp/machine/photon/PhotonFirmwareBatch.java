package org.openpnp.machine.photon;

import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Updates the firmware of many Photon feeders, one at a time. Each feeder gets the image for
 * its drive: the version string the firmware reports ends in "-stock" or "-ratchet", and the
 * image is chosen by file name (PhotonFeeder_F031_Stock.bin, PhotonFeeder_F031_Ratchet.bin).
 * A feeder whose drive can't be told is skipped rather than guessed, since the wrong image
 * would drive the wrong motor.
 *
 * A failure on one feeder doesn't stop the others. Stopping takes effect between feeders,
 * never in the middle of one.
 */
public class PhotonFirmwareBatch {
    public enum Drive {
        STOCK,
        RATCHET;

        public String label() {
            return this == STOCK ? "Stock" : "Ratchet";
        }
    }

    public interface Updater {
        PhotonFirmwareUpdater.Outcome update(PhotonFeeder feeder, byte[] image, PhotonFirmwareUpdater.Progress progress)
                throws Exception;
    }

    public interface VersionReader {
        String read(PhotonFeeder feeder);
    }

    public interface Progress {
        void update(int index, int count, PhotonFeeder feeder, String stage, double fraction, boolean powerCritical);
    }

    public static final Updater FEEDER_UPDATER = (feeder, image, progress) ->
            PhotonFirmwareUpdater.forFeeder(feeder, progress).update(image);

    public static class Result {
        public final PhotonFeeder feeder;
        public final int slotAddress;
        public final String versionBefore;
        public final String versionAfter;
        public final String outcome;
        public final boolean failed;

        Result(PhotonFeeder feeder, int slotAddress, String versionBefore, String versionAfter, String outcome,
               boolean failed) {
            this.feeder = feeder;
            this.slotAddress = slotAddress;
            this.versionBefore = versionBefore;
            this.versionAfter = versionAfter;
            this.outcome = outcome;
            this.failed = failed;
        }
    }

    public static Drive driveOfVersion(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("-ratchet")) {
            return Drive.RATCHET;
        }
        if (v.endsWith("-stock")) {
            return Drive.STOCK;
        }
        return null;
    }

    public static Drive driveOfFileName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("ratchet")) {
            return Drive.RATCHET;
        }
        if (n.contains("stock")) {
            return Drive.STOCK;
        }
        return null;
    }

    private final Updater updater;
    private final VersionReader versionReader;

    public PhotonFirmwareBatch(Updater updater, VersionReader versionReader) {
        this.updater = updater;
        this.versionReader = versionReader;
    }

    public static PhotonFirmwareBatch forMachine() {
        return new PhotonFirmwareBatch(FEEDER_UPDATER, PhotonFirmwareUpdater::readVersionQuietly);
    }

    /**
     * The Photon feeders that have a slot, in slot order.
     */
    public static List<PhotonFeeder> feedersInSlots(List<? extends org.openpnp.spi.Feeder> feeders) {
        List<PhotonFeeder> photonFeeders = new ArrayList<>();
        for (org.openpnp.spi.Feeder feeder : feeders) {
            if (feeder instanceof PhotonFeeder && ((PhotonFeeder) feeder).getSlotAddress() != null) {
                photonFeeders.add((PhotonFeeder) feeder);
            }
        }
        photonFeeders.sort(Comparator.comparingInt(PhotonFeeder::getSlotAddress));
        return photonFeeders;
    }

    public List<Result> run(List<PhotonFeeder> feeders, Map<Drive, byte[]> images, Progress progress,
                            BooleanSupplier stopRequested) {
        List<Result> results = new ArrayList<>();
        int count = feeders.size();
        for (int i = 0; i < count; i++) {
            PhotonFeeder feeder = feeders.get(i);
            Integer slot = feeder.getSlotAddress();
            int slotAddress = slot == null ? 0 : slot;

            if (stopRequested != null && stopRequested.getAsBoolean()) {
                results.add(new Result(feeder, slotAddress, null, null, "Not started: stopped", false));
                continue;
            }

            final int index = i;
            report(progress, index, count, feeder, "Reading its version", -1, false);
            String before = versionReader.read(feeder);
            Drive drive = driveOfVersion(before);
            if (drive == null) {
                results.add(new Result(feeder, slotAddress, before, null,
                        "Skipped: can't tell stock from ratchet. Update it from its own page.", false));
                continue;
            }
            byte[] image = images.get(drive);
            if (image == null) {
                results.add(new Result(feeder, slotAddress, before, null,
                        String.format("Skipped: no %s file chosen", drive.label()), false));
                continue;
            }

            try {
                PhotonFirmwareUpdater.Outcome outcome = updater.update(feeder, image,
                        (stage, fraction, critical) -> report(progress, index, count, feeder, stage, fraction, critical));
                String after = versionReader.read(feeder);
                results.add(new Result(feeder, slotAddress, before, after,
                        outcome == PhotonFirmwareUpdater.Outcome.UPDATED ? "Updated" : "Already up to date", false));
            }
            catch (Exception e) {
                Logger.warn(e, "Firmware update of Photon feeder {} in slot {} failed", feeder.getName(), slotAddress);
                results.add(new Result(feeder, slotAddress, before, null, "Failed: " + e.getMessage(), true));
            }
            report(progress, index, count, feeder, "Done", 1, false);
        }
        return results;
    }

    private static void report(Progress progress, int index, int count, PhotonFeeder feeder, String stage,
                               double fraction, boolean critical) {
        if (progress != null) {
            progress.update(index, count, feeder, stage, fraction, critical);
        }
    }
}
