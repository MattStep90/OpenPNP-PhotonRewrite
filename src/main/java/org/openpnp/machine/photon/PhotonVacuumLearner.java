package org.openpnp.machine.photon;

import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.ReferenceNozzleTip.VacuumMeasurementMethod;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Learns a nozzle tip's vacuum levels from real parts, and suggests the part-on and part-off
 * ranges (Absolute method) for it. Nothing changes on the nozzle tip until the suggestion is
 * applied.
 *
 * For each of up to MAX_FEEDERS feeders, a part is picked and the vacuum read with it held, then
 * the part is put back in its pocket, so no part is used up and the feeder's count and feed state
 * stay as they were. The empty nozzle is read the same two ways: with the vacuum held on (the
 * part-on check), and with the part-off check's vacuum pulse.
 *
 * The part-on range starts halfway between the nearest held readings with and without a part.
 * The part-off range is a band around the empty pulse readings. Some sensors read higher with a
 * part, some lower; both work.
 */
public class PhotonVacuumLearner {
    public static final int MAX_FEEDERS = 5;
    static final int EMPTY_HELD_READINGS = 3;
    // Noise allowance around the empty pulse readings, in standard deviations
    static final double PULSE_SIGMAS = 4;
    // ... and at least this share of the held part/no-part difference
    static final double PULSE_SHARE_OF_DIFFERENCE = 0.25;

    /**
     * The moves and readings, separate so tests need no machine.
     */
    public interface Rig {
        /** Picks the feeder's part, lifts it to safe Z and reads the vacuum with it held. */
        double pickAndRead(PhotonFeeder feeder) throws Exception;

        /** Puts the part just picked back in the feeder's pocket. */
        void putBack(PhotonFeeder feeder) throws Exception;

        /** Reads the empty nozzle with the vacuum held on. */
        double readEmptyHeld(PhotonFeeder feeder) throws Exception;

        /** Reads the empty nozzle the way the part-off check does. */
        double readEmptyPulse() throws Exception;
    }

    public interface Progress {
        void update(String stage);
    }

    public static class Reading {
        public final PhotonFeeder feeder;
        public final Double partOn;
        public final String problem;

        Reading(PhotonFeeder feeder, Double partOn, String problem) {
            this.feeder = feeder;
            this.partOn = partOn;
            this.problem = problem;
        }
    }

    public static class Suggestion {
        public final List<Reading> readings;
        public final List<Double> emptyHeld;
        public final List<Double> emptyPulse;
        // null when the readings don't give a usable suggestion, see problem
        public final Double partOnLow;
        public final Double partOnHigh;
        public final Double partOffLow;
        public final Double partOffHigh;
        public final String problem;

        Suggestion(List<Reading> readings, List<Double> emptyHeld, List<Double> emptyPulse, Double partOnLow,
                   Double partOnHigh, Double partOffLow, Double partOffHigh, String problem) {
            this.readings = readings;
            this.emptyHeld = emptyHeld;
            this.emptyPulse = emptyPulse;
            this.partOnLow = partOnLow;
            this.partOnHigh = partOnHigh;
            this.partOffLow = partOffLow;
            this.partOffHigh = partOffHigh;
            this.problem = problem;
        }

        public boolean isUsable() {
            return problem == null;
        }

        public List<Double> partOnLevels() {
            List<Double> levels = new ArrayList<>();
            for (Reading reading : readings) {
                if (reading.partOn != null) {
                    levels.add(reading.partOn);
                }
            }
            return levels;
        }
    }

    /**
     * Why this nozzle can't learn vacuum levels, or null if it can.
     */
    public static String whyNot(Nozzle nozzle) {
        if (!(nozzle instanceof ReferenceNozzle)) {
            return "The nozzle has no vacuum sensing OpenPnP can read.";
        }
        ReferenceNozzle referenceNozzle = (ReferenceNozzle) nozzle;
        ReferenceNozzleTip tip = referenceNozzle.getNozzleTip();
        if (tip == null) {
            return "Load a nozzle tip on nozzle " + nozzle.getName() + " first.";
        }
        if (referenceNozzle.getVacuumSenseActuator() == null) {
            return "Nozzle " + nozzle.getName() + " has no vacuum sense actuator.";
        }
        if (nozzle.getPart() != null) {
            return "Nozzle " + nozzle.getName() + " is holding a part. Discard it first.";
        }
        if (tip.getMethodPartOn() == VacuumMeasurementMethod.Difference
                || tip.getMethodPartOff() == VacuumMeasurementMethod.Difference) {
            return "Nozzle tip " + tip.getName() + " uses the Difference method. "
                    + "Learning only suggests Absolute levels; switch both checks to Absolute first.";
        }
        return null;
    }

    /**
     * Whether the feeder's part can be picked with the nozzle's current tip.
     */
    public static boolean canPick(Nozzle nozzle, PhotonFeeder feeder) {
        Part part = feeder.getPart();
        if (part == null || feeder.hasPlaceholderPart() || part.getPackage() == null
                || nozzle.getNozzleTip() == null) {
            return false;
        }
        return part.getPackage().getCompatibleNozzleTips().contains(nozzle.getNozzleTip());
    }

    /**
     * The feeders to pick from: enabled Photon feeders with a slot and pick point, holding a part
     * the nozzle tip can pick, leaving out any the last Auto Setup saw an empty pocket in. Ones
     * Auto Setup saw a part in come first. Says in why what ruled the others out when none is left.
     */
    public static List<PhotonFeeder> chooseFeeders(Nozzle nozzle, List<? extends Feeder> all,
                                                   Collection<PhotonFeeder> sawPart, Collection<PhotonFeeder> lookedEmpty,
                                                   StringBuilder why) {
        List<PhotonFeeder> chosen = new ArrayList<>();
        int photon = 0;
        int noPickPoint = 0;
        int wrongTip = 0;
        int empty = 0;
        for (Feeder feeder : all) {
            if (!(feeder instanceof PhotonFeeder) || !feeder.isEnabled()) {
                continue;
            }
            PhotonFeeder f = (PhotonFeeder) feeder;
            if (f.getPart() == null || f.hasPlaceholderPart()) {
                continue;
            }
            photon++;
            try {
                f.getPickLocation();
            }
            catch (Exception e) {
                noPickPoint++;
                continue;
            }
            if (!canPick(nozzle, f)) {
                wrongTip++;
                continue;
            }
            if (lookedEmpty.contains(f)) {
                empty++;
                continue;
            }
            chosen.add(f);
        }
        chosen.sort((a, b) -> Boolean.compare(!sawPart.contains(a), !sawPart.contains(b)));
        if (chosen.size() > MAX_FEEDERS) {
            chosen = new ArrayList<>(chosen.subList(0, MAX_FEEDERS));
        }
        if (chosen.isEmpty() && why != null) {
            if (photon == 0) {
                why.append("No enabled Photon feeder has a part assigned.");
            }
            else {
                why.append(String.format("None of the %d Photon feeders with a part can be used:", photon));
                if (noPickPoint > 0) {
                    why.append(String.format(" %d have no pick point yet (run Auto Setup).", noPickPoint));
                }
                if (wrongTip > 0) {
                    why.append(String.format(" %d hold parts whose package doesn't list nozzle tip %s as compatible.",
                            wrongTip, nozzle.getNozzleTip() == null ? "(none)" : nozzle.getNozzleTip().getName()));
                }
                if (empty > 0) {
                    why.append(String.format(" %d looked empty in the last Auto Setup.", empty));
                }
            }
        }
        return chosen;
    }

    private final Rig rig;

    public PhotonVacuumLearner(Rig rig) {
        this.rig = rig;
    }

    public static PhotonVacuumLearner forNozzle(ReferenceNozzle nozzle) {
        return new PhotonVacuumLearner(new NozzleRig(nozzle));
    }

    public Suggestion learn(List<PhotonFeeder> feeders, Progress progress) throws Exception {
        if (feeders.isEmpty()) {
            throw new Exception("No feeder to pick from.");
        }
        List<PhotonFeeder> used = feeders.subList(0, Math.min(MAX_FEEDERS, feeders.size()));

        List<Double> emptyPulse = new ArrayList<>();
        List<Double> emptyHeld = new ArrayList<>();
        report(progress, "Reading the empty nozzle");
        emptyPulse.add(rig.readEmptyPulse());
        for (int i = 0; i < EMPTY_HELD_READINGS; i++) {
            emptyHeld.add(rig.readEmptyHeld(used.get(0)));
        }

        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            PhotonFeeder feeder = used.get(i);
            report(progress, String.format("Picking from %s (%d of %d)", feeder.getName(), i + 1, used.size()));
            Double level = null;
            String problem = null;
            try {
                level = rig.pickAndRead(feeder);
            }
            catch (Exception e) {
                problem = e.getMessage();
            }
            finally {
                // Even after a failed read the part may be on the nozzle
                report(progress, String.format("Putting the part back in %s", feeder.getName()));
                rig.putBack(feeder);
            }
            readings.add(new Reading(feeder, level, problem));
            emptyPulse.add(rig.readEmptyPulse());
        }

        Suggestion suggestion = suggest(readings, emptyHeld, emptyPulse);
        Logger.info("Vacuum learning: part on {}, empty held {}, empty pulse {}, suggestion on {}..{} off {}..{} {}",
                suggestion.partOnLevels(), emptyHeld, emptyPulse, suggestion.partOnLow, suggestion.partOnHigh,
                suggestion.partOffLow, suggestion.partOffHigh, suggestion.problem == null ? "" : suggestion.problem);
        return suggestion;
    }

    static Suggestion suggest(List<Reading> readings, List<Double> emptyHeld, List<Double> emptyPulse) {
        List<Double> on = new ArrayList<>();
        for (Reading reading : readings) {
            if (reading.partOn != null) {
                on.add(reading.partOn);
            }
        }
        if (on.isEmpty()) {
            return new Suggestion(readings, emptyHeld, emptyPulse, null, null, null, null,
                    "No part could be picked, so there is nothing to learn from.");
        }

        double difference = mean(on) - mean(emptyHeld);
        boolean higherWithPart = difference > 0;
        double gap = higherWithPart ? min(on) - max(emptyHeld) : min(emptyHeld) - max(on);
        if (gap <= 0) {
            return new Suggestion(readings, emptyHeld, emptyPulse, null, null, null, null,
                    "The readings with and without a part overlap, so no level tells them apart. "
                    + "Check the nozzle tip suits these parts and the vacuum doesn't leak.");
        }

        double span = Math.abs(difference);
        double partOnLow;
        double partOnHigh;
        if (higherWithPart) {
            partOnLow = max(emptyHeld) + gap / 2;
            partOnHigh = max(on) + span;
        }
        else {
            partOnHigh = min(emptyHeld) - gap / 2;
            partOnLow = min(on) - span;
        }

        double band = Math.max(PULSE_SIGMAS * stdDev(emptyPulse), PULSE_SHARE_OF_DIFFERENCE * span);
        double partOffLow = min(emptyPulse) - band;
        double partOffHigh = max(emptyPulse) + band;
        return new Suggestion(readings, emptyHeld, emptyPulse, partOnLow, partOnHigh, partOffLow, partOffHigh, null);
    }

    /**
     * Sets the suggested Absolute ranges on the nozzle tip. Methods that are off stay off.
     */
    public static void apply(ReferenceNozzleTip tip, Suggestion suggestion) {
        if (!suggestion.isUsable()) {
            throw new IllegalArgumentException(suggestion.problem);
        }
        tip.setVacuumLevelPartOnLow(suggestion.partOnLow);
        tip.setVacuumLevelPartOnHigh(suggestion.partOnHigh);
        tip.setVacuumLevelPartOffLow(suggestion.partOffLow);
        tip.setVacuumLevelPartOffHigh(suggestion.partOffHigh);
        Logger.info("Nozzle tip {}: part-on {} .. {}, part-off {} .. {} from vacuum learning", tip.getName(),
                suggestion.partOnLow, suggestion.partOnHigh, suggestion.partOffLow, suggestion.partOffHigh);
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double stdDev(List<Double> values) {
        double mean = mean(values);
        double sum = 0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / values.size());
    }

    private static double min(List<Double> values) {
        double min = Double.POSITIVE_INFINITY;
        for (double value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    private static double max(List<Double> values) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static void report(Progress progress, String stage) {
        if (progress != null) {
            progress.update(stage);
        }
    }

    /**
     * The real moves. The part is put back without OpenPnP's take-back, so the feeder doesn't
     * skip its next feed: it was never fed for this pick.
     */
    static class NozzleRig implements Rig {
        private final ReferenceNozzle nozzle;

        NozzleRig(ReferenceNozzle nozzle) {
            this.nozzle = nozzle;
        }

        @Override
        public double pickAndRead(PhotonFeeder feeder) throws Exception {
            nozzle.moveToPickLocation(feeder);
            nozzle.pick(feeder.getPart(), feeder);
            nozzle.moveToSafeZ();
            return nozzle.readVacuumLevel();
        }

        @Override
        public void putBack(PhotonFeeder feeder) throws Exception {
            nozzle.moveToPickLocation(feeder);
            nozzle.place();
            nozzle.moveToSafeZ();
        }

        @Override
        public double readEmptyHeld(PhotonFeeder feeder) throws Exception {
            // Vacuum on with nothing on the nozzle, in the air above the feeder
            nozzle.moveToSafeZ();
            nozzle.pick(feeder.getPart(), feeder);
            try {
                return nozzle.readVacuumLevel();
            }
            finally {
                nozzle.place();
            }
        }

        @Override
        public double readEmptyPulse() throws Exception {
            nozzle.moveToSafeZ();
            // The Absolute part-off check stores the level it read, whatever the outcome
            nozzle.isPartOff();
            Double level = nozzle.getNozzleTip().getVacuumLevelPartOffReading();
            if (level == null) {
                throw new Exception("The part-off check gave no reading.");
            }
            return level;
        }
    }
}
