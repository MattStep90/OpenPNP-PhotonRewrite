package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Machine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonAutoSetupTest {
    private Machine machine;
    private PhotonFeederSlots slots;
    private final List<Location> estimates = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        machine = Configuration.get().getMachine();
        slots = new PhotonProperties(machine).getFeederSlots();
        estimates.clear();
    }

    private PhotonFeeder addFeeder(String hardwareId, Integer address) throws Exception {
        PhotonFeeder feeder = new PhotonFeeder();
        feeder.setHardwareId(hardwareId);
        feeder.setSlotAddress(address);
        machine.addFeeder(feeder);
        // A reel of 2 mm pitch tape is recorded in the feeder
        feeder.setRecord(new PhotonFeederRecord(true, 8, 20, 5000, 0, 5000, false, "R0402-10K"));
        return feeder;
    }

    // Pretends vision found the pick point 0.4 mm right and 0.3 mm up of the estimate.
    private PhotonAutoSetup.PickPointFinder shiftedFinder() {
        return (feeder, estimate) -> {
            estimates.add(estimate);
            return new Location(LengthUnit.Millimeters,
                    estimate.getX() + 0.4, estimate.getY() + 0.3, 99, 45);
        };
    }

    @Test
    public void measuredPickPointBecomesTheSlotLocationAndTheOffsetIsZeroed() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 3);
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 2, 0, 0));

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertEquals(1, results.size());
        PhotonAutoSetup.Result result = results.get(0);
        assertTrue(result.isOk());
        assertEquals(3, result.slotAddress);

        // The estimate includes the old offset, rotated by the slot's 90 degrees.
        Location estimate = estimates.get(0);
        assertEquals(98, estimate.getX(), 1e-9);
        assertEquals(51, estimate.getY(), 1e-9);

        Location slot = slots.getSlot(3).getLocation();
        assertEquals(98.4, slot.getX(), 1e-9);
        assertEquals(51.3, slot.getY(), 1e-9);
        assertEquals(-20, slot.getZ(), 1e-9, "Z stays as taught");
        assertEquals(90, slot.getRotation(), 1e-9, "rotation stays as taught");

        Location offset = feeder.getOffset();
        assertEquals(0, offset.getX(), 1e-9);
        assertEquals(0, offset.getY(), 1e-9);
        assertEquals(0, offset.getRotation(), 1e-9);
        assertEquals(slot.getX(), feeder.getPickLocation().getX(), 1e-9);
        assertEquals(0.5, result.getCorrectionMm(), 1e-9);
    }

    @Test
    public void feederWithoutOffsetStartsAtTheSlotLocation() throws Exception {
        slots.getSlot(4).setLocation(new Location(LengthUnit.Millimeters, 10, 20, -20, 0));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 4);

        new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertEquals(10, estimates.get(0).getX(), 1e-9);
        assertNotNull(feeder.getOffset());
    }

    @Test
    public void slotWithoutLocationIsReportedAndNotMeasured() throws Exception {
        addFeeder("00112233445566778899AABB", 8);

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertFalse(results.get(0).isOk());
        assertTrue(estimates.isEmpty());
        assertNull(slots.getSlot(8).getLocation());
    }

    @Test
    public void visionFailureKeepsTheSlotAndOffset() throws Exception {
        Location taught = new Location(LengthUnit.Millimeters, 10, 20, -20, 0);
        slots.getSlot(4).setLocation(taught);
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 4);
        Location offset = new Location(LengthUnit.Millimeters, 1, 1, 0, 0);
        feeder.setOffset(offset);

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup((f, estimate) -> {
            throw new Exception("No sprocket holes found");
        }).measureAll(null);

        assertFalse(results.get(0).isOk());
        assertEquals("No sprocket holes found", results.get(0).problem);
        assertSame(taught, slots.getSlot(4).getLocation());
        assertSame(offset, feeder.getOffset());
    }

    @Test
    public void failedFeedersAreTriedAgainOnceTheFinderLearnsMore() throws Exception {
        slots.getSlot(1).setLocation(new Location(LengthUnit.Millimeters, 10, 20, -20, 0));
        slots.getSlot(2).setLocation(new Location(LengthUnit.Millimeters, 30, 20, -20, 0));
        addFeeder("00112233445566778899AABB", 1);
        addFeeder("FFEEDDCCBBAA998877665544", 2);

        // Slot 1 can only be found once slot 2 has taught the finder where to look
        int[] learned = {0};
        List<PhotonAutoSetup.Result> reported = new ArrayList<>();
        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(new PhotonAutoSetup.PickPointFinder() {
            @Override
            public Location find(PhotonFeeder feeder, Location estimate) throws Exception {
                estimates.add(estimate);
                if (feeder.getSlotAddress() == 1 && learned[0] == 0) {
                    throw new Exception("No sprocket holes found");
                }
                learned[0]++;
                return estimate.add(new Location(LengthUnit.Millimeters, 0.4, 0.3, 0, 0));
            }

            @Override
            public int learned() {
                return learned[0];
            }
        }).measureAll(reported::add);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isOk());
        assertEquals(1, results.get(0).slotAddress);
        assertTrue(results.get(1).isOk());
        assertEquals(3, estimates.size());
        assertEquals(3, reported.size());
        assertEquals(10.4, slots.getSlot(1).getLocation().getX(), 1e-9);
    }

    @Test
    public void failedFeedersAreNotTriedAgainWhenNothingWasLearned() throws Exception {
        slots.getSlot(1).setLocation(new Location(LengthUnit.Millimeters, 10, 20, -20, 0));
        addFeeder("00112233445566778899AABB", 1);

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup((f, estimate) -> {
            estimates.add(estimate);
            throw new Exception("No sprocket holes found");
        }).measureAll(null);

        assertFalse(results.get(0).isOk());
        assertEquals(1, estimates.size());
    }

    @Test
    public void feedersWithoutASlotAreSkipped() throws Exception {
        addFeeder("00112233445566778899AABB", null);
        slots.getSlot(2).setLocation(new Location(LengthUnit.Millimeters, 10, 20, -20, 0));
        addFeeder("FFEEDDCCBBAA998877665544", 2);

        List<PhotonAutoSetup.Result> reported = new ArrayList<>();
        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(shiftedFinder()).measureAll(reported::add);

        assertEquals(1, results.size());
        assertEquals(2, results.get(0).slotAddress);
        assertEquals(results, reported);
    }

    @Test
    public void feederWithoutARecordedReelIsSkipped() throws Exception {
        Location taught = new Location(LengthUnit.Millimeters, 10, 20, -20, 0);
        slots.getSlot(4).setLocation(taught);
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 4);
        feeder.setRecord(new PhotonFeederRecord(false, 0, 0, 0, 0, 0, false, ""));

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertTrue(results.get(0).isSkipped());
        assertFalse(results.get(0).isOk());
        assertTrue(estimates.isEmpty());
        assertSame(taught, slots.getSlot(4).getLocation());
    }

    @Test
    public void feederWhoseRecordWasNeverReadIsSkipped() throws Exception {
        slots.getSlot(4).setLocation(new Location(LengthUnit.Millimeters, 10, 20, -20, 0));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 4);
        feeder.setRecord(null);

        List<PhotonAutoSetup.Result> results = new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertTrue(results.get(0).isSkipped());
        assertTrue(estimates.isEmpty());
    }

    /**
     * A tape whose first pockets are empty: scores 5 until the given number of feeds, then 40.
     */
    private static class Tape implements PhotonAutoSetup.PocketChecker, PhotonAutoSetup.TapeMover {
        final int emptyPockets;
        int feeds = 0;
        Long partsLeftSet = null;

        Tape(int emptyPockets) {
            this.emptyPockets = emptyPockets;
        }

        @Override
        public double score(PhotonFeeder feeder, Location pick) {
            return feeds < emptyPockets ? 5 : 40;
        }

        @Override
        public void feedOnePart(PhotonFeeder feeder) {
            feeds++;
            feeder.setRecord(feeder.getRecord().afterFeed(feeder.getRecord().pitchTenthsMm));
        }

        @Override
        public void setPartsLeft(PhotonFeeder feeder, long partsLeft) {
            partsLeftSet = partsLeft;
        }
    }

    private PhotonAutoSetup.Result setUpWithTape(Tape tape, boolean feedPastEmpty) throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 0));
        addFeeder("00112233445566778899AABB", 3);
        return new PhotonAutoSetup(shiftedFinder())
                .withPocketCheck(tape, 20, feedPastEmpty)
                .withTapeMover(tape)
                .measureAll(null).get(0);
    }

    @Test
    public void emptyPocketsAreFedPastAndTheCountIsPutBack() throws Exception {
        Tape tape = new Tape(3);

        PhotonAutoSetup.Result result = setUpWithTape(tape, true);

        assertTrue(result.isOk());
        assertTrue(result.partFound);
        assertEquals(3, result.emptyPocketsFed);
        assertEquals(40, result.pocketScore, 1e-9);
        assertEquals(3, tape.feeds);
        assertEquals(5000L, (long) tape.partsLeftSet, "the empty pockets held no parts");
    }

    @Test
    public void aFullPocketIsNotFed() throws Exception {
        Tape tape = new Tape(0);

        PhotonAutoSetup.Result result = setUpWithTape(tape, true);

        assertTrue(result.isOk());
        assertTrue(result.partFound);
        assertEquals(0, tape.feeds);
        assertNull(tape.partsLeftSet);
    }

    @Test
    public void withFeedingOffAnEmptyPocketIsOnlyScored() throws Exception {
        Tape tape = new Tape(2);

        PhotonAutoSetup.Result result = setUpWithTape(tape, false);

        assertTrue(result.isOk());
        assertFalse(result.partFound);
        assertEquals(5, result.pocketScore, 1e-9);
        assertEquals(0, tape.feeds);
    }

    @Test
    public void feedingStopsAfterTheLimitAndReportsIt() throws Exception {
        Tape tape = new Tape(100);

        PhotonAutoSetup.Result result = setUpWithTape(tape, true);

        assertFalse(result.isOk());
        assertEquals(PhotonAutoSetup.MAX_EMPTY_POCKETS, tape.feeds);
        assertTrue(result.problem.startsWith("No part found after feeding 10 empty pockets"));
        assertEquals(5000L, (long) tape.partsLeftSet);
        assertEquals(100.4, slots.getSlot(3).getLocation().getX(), 1e-9, "the measured slot is kept");
    }

    // Pretends the nozzle touched 0.3 mm below the taught height.
    private PhotonAutoSetup.HeightProber prober(List<Location> probed, double dz) {
        return (feeder, pick) -> {
            probed.add(pick);
            return pick.add(new Location(LengthUnit.Millimeters, 0, 0, dz, 0));
        };
    }

    @Test
    public void handAlignedPickHeightIsProbedWhenTheHolesArentFound() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 0));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 3);
        Location offset = new Location(LengthUnit.Millimeters, 1, 2, 0, 0);
        feeder.setOffset(offset);
        List<Location> probed = new ArrayList<>();

        PhotonAutoSetup.Result result = new PhotonAutoSetup((f, estimate) -> {
            throw new Exception("No sprocket holes found");
        }).withHeightProbe(prober(probed, -0.3)).measureAll(null).get(0);

        assertFalse(result.isOk());
        assertTrue(result.problem.startsWith("No sprocket holes found"));
        assertEquals(1, probed.size());
        assertEquals(101, probed.get(0).getX(), 1e-9, "probed where it was aligned");
        assertEquals(52, probed.get(0).getY(), 1e-9);
        assertEquals(-0.3, result.heightChangeMm, 1e-9);
        Location slot = slots.getSlot(3).getLocation();
        assertEquals(100, slot.getX(), 1e-9, "the alignment is kept");
        assertEquals(-20.3, slot.getZ(), 1e-9);
        assertSame(offset, feeder.getOffset());
    }

    @Test
    public void probedHeightBecomesTheSlotZ() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        addFeeder("00112233445566778899AABB", 3);
        List<Location> probed = new ArrayList<>();

        PhotonAutoSetup.Result result = new PhotonAutoSetup(shiftedFinder())
                .withHeightProbe(prober(probed, -0.3)).measureAll(null).get(0);

        assertTrue(result.isOk());
        assertEquals(1, probed.size());
        assertEquals(100.4, probed.get(0).getX(), 1e-9, "probed at the measured pick point");
        assertEquals(-0.3, result.heightChangeMm, 1e-9);
        Location slot = slots.getSlot(3).getLocation();
        assertEquals(-20.3, slot.getZ(), 1e-9);
        assertEquals(100.4, slot.getX(), 1e-9);
        assertEquals(-20.3, result.after.getZ(), 1e-9);
    }

    @Test
    public void aFarOffHeightIsReportedNotUsed() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        addFeeder("00112233445566778899AABB", 3);

        PhotonAutoSetup.Result result = new PhotonAutoSetup(shiftedFinder())
                .withHeightProbe(prober(new ArrayList<>(), -2.0)).measureAll(null).get(0);

        assertFalse(result.isOk());
        assertTrue(result.problem.contains("taught height was kept"));
        assertEquals(-20, slots.getSlot(3).getLocation().getZ(), 1e-9);
    }

    @Test
    public void anEmptyLookingPocketIsNotProbed() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        addFeeder("00112233445566778899AABB", 3);
        List<Location> probed = new ArrayList<>();

        PhotonAutoSetup.Result result = new PhotonAutoSetup(shiftedFinder())
                .withPocketCheck((feeder, pick) -> 5, 20, false)
                .withHeightProbe(prober(probed, -0.3)).measureAll(null).get(0);

        assertTrue(probed.isEmpty());
        assertNull(result.heightChangeMm);
        assertEquals(-20, slots.getSlot(3).getLocation().getZ(), 1e-9);
    }

    @Test
    public void aPlaceholderPartFeederIsNotPocketCheckedOrProbed() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 3);
        feeder.setPart(Configuration.get().getPart(PhotonFeeder.PLACEHOLDER_PART_ID));
        List<Location> probed = new ArrayList<>();
        List<PhotonFeeder> scored = new ArrayList<>();

        PhotonAutoSetup.Result result = new PhotonAutoSetup(shiftedFinder())
                .withPocketCheck((f, pick) -> {
                    scored.add(f);
                    return 40;
                }, 20, false)
                .withHeightProbe(prober(probed, -0.3)).measureAll(null).get(0);

        assertTrue(result.isOk());
        assertTrue(scored.isEmpty());
        assertTrue(probed.isEmpty());
        assertNull(result.pocketScore);
    }

    @Test
    public void aFailedProbeKeepsTheTaughtHeight() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));
        addFeeder("00112233445566778899AABB", 3);

        PhotonAutoSetup.Result result = new PhotonAutoSetup(shiftedFinder())
                .withHeightProbe((feeder, pick) -> {
                    throw new Exception("sniffle-probing made no contact");
                }).measureAll(null).get(0);

        assertEquals("Pick height probe failed: sniffle-probing made no contact", result.problem);
        assertEquals(-20, slots.getSlot(3).getLocation().getZ(), 1e-9);
        assertEquals(100.4, slots.getSlot(3).getLocation().getX(), 1e-9, "the measured X is kept");
    }

    @Test
    public void autoSetupKeepsTheRotationInTape() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 180));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 3);
        feeder.setOffset(new Location(LengthUnit.Millimeters, 0, 0, 0, 90));

        new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertEquals(90, feeder.getOffset().getRotation(), 1e-9);
        assertEquals(270, feeder.getPickLocation().getRotation(), 1e-9);
    }

    @Test
    public void autoSetupTakesTheRotationTheFeederHolds() throws Exception {
        slots.getSlot(3).setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 0));
        PhotonFeeder feeder = addFeeder("00112233445566778899AABB", 3);
        Part part = new Part("R0402-10K");
        Configuration.get().addPart(part);
        feeder.setPart(part);
        feeder.setRecord(new PhotonFeederRecord(true, 8, 20, 5000, 0, 5000, false, "R0402-10K", 1800));
        feeder.setOffset(new Location(LengthUnit.Millimeters, 0, 0, 0, 90));

        new PhotonAutoSetup(shiftedFinder()).measureAll(null);

        assertEquals(180, feeder.getOffset().getRotation(), 1e-9);
    }
}
