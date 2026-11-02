package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.protocol.PacketBuilder;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.MoveFeedStatus;
import org.openpnp.machine.photon.protocol.commands.VendorOptions;
import org.openpnp.machine.photon.protocol.helpers.ResponsesHelper;
import org.openpnp.machine.photon.protocol.helpers.TestBus;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Machine;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonFeedCalibratorTest {
    private static final String HARDWARE_ID = "00112233445566778899AABB";
    private static final int ADDRESS = 5;

    private PhotonFeeder feeder;
    private TestBus bus;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        Machine machine = Configuration.get().getMachine();
        new PhotonProperties(machine).getFeederSlots().getSlot(ADDRESS)
                .setLocation(new Location(LengthUnit.Millimeters, 0, 0, -20, 0));
        feeder = new PhotonFeeder();
        feeder.setHardwareId(HARDWARE_ID);
        feeder.setSlotAddress(ADDRESS);
        feeder.setOffset(new Location(LengthUnit.Millimeters));
        feeder.setPartPitch(2);
        machine.addFeeder(feeder);

        bus = new TestBus();
        PhotonFeeder.setBus(bus);
        bus.when(new InitializeFeeder(ADDRESS, HARDWARE_ID))
                .reply(new ResponsesHelper(0).initializeFeeder.ok(ADDRESS, HARDWARE_ID));
    }

    private Packet vendorReply(int... data) {
        PacketBuilder builder = PacketBuilder.response(0, ADDRESS).putOk();
        for (int i = 0; i < VendorOptions.DATA_LENGTH; i++) {
            builder.putByte(i < data.length ? data[i] : 0);
        }
        return builder.toPacket();
    }

    /**
     * Tape whose holes sit every 4 mm along X, moving in the given direction (+1 or -1) by
     * (1 + error) times what each feed asks for.
     */
    private static class Tape implements PhotonFeedCalibrator.TapeTracker, PhotonFeedCalibrator.Mover {
        final double direction;
        final double error;
        double travel = 0.3;
        int millimetreFeeds = 0;
        int partFeeds = 0;

        Tape(double direction, double error) {
            this.direction = direction;
            this.error = error;
        }

        double nearestHole(double x) {
            double offset = direction * travel;
            return offset + Math.round((x - offset) / 4) * 4;
        }

        Location at(double x) {
            return new Location(LengthUnit.Millimeters, x, 3.5, 0, 0);
        }

        @Override
        public Location[] find(PhotonFeeder feeder, Location pickEstimate) {
            double first = nearestHole(0);
            double second = first + (first <= 0 ? 4 : -4);
            return new Location[]{pickEstimate, at(first), at(second)};
        }

        @Override
        public Location[] track(PhotonFeeder feeder, Location[] previous) {
            return new Location[]{previous[0], at(nearestHole(previous[1].getX())), at(nearestHole(previous[2].getX()))};
        }

        @Override
        public void feedMillimeters(PhotonFeeder feeder, int millimeters) {
            millimetreFeeds += millimeters;
            travel += millimeters * (1 + error);
        }

        @Override
        public void feedPart(PhotonFeeder feeder) {
            partFeeds++;
            travel += feeder.getPartPitch() * (1 + error);
        }
    }

    private void feederScale(int before, int after, long askedUm, long actualUm) throws Exception {
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_FEED_SCALE}))
                .reply(vendorReply(1, before & 0xFF, (before >> 8) & 0xFF));
        int[] correct = new int[9];
        correct[0] = PhotonFeeder.CMD_CORRECT_FEED_SCALE;
        PhotonFeederRecord.putUint32(correct, 1, askedUm);
        PhotonFeederRecord.putUint32(correct, 5, actualUm);
        bus.when(new VendorOptions(ADDRESS, correct)).reply(vendorReply(1));
    }

    @Test
    public void partsToFeedIsAtLeast40MmAndWholeHolePitches() {
        assertEquals(20, PhotonFeedCalibrator.partsToFeed(2));
        assertEquals(10, PhotonFeedCalibrator.partsToFeed(4));
        assertEquals(5, PhotonFeedCalibrator.partsToFeed(8));
        assertEquals(4, PhotonFeedCalibrator.partsToFeed(12));
        assertEquals(40, PhotonFeedCalibrator.partsToFeed(1));
    }

    @Test
    public void aFeederThatFeedsLongIsMeasuredAndCorrected() throws Exception {
        Tape tape = new Tape(1, 0.01);
        feederScale(0, 0, 40_000, 40_400);

        PhotonFeedCalibrator.Result result = new PhotonFeedCalibrator(tape, tape).calibrate(feeder, null);

        assertEquals(40, result.askedMm, 1e-9);
        assertEquals(40.4, result.measuredMm, 1e-6);
        assertEquals(1.0, result.getErrorPercent(), 1e-6);
        assertEquals(20, tape.partFeeds);
        assertEquals(2, tape.millimetreFeeds, "1 mm to find the direction, 1 mm to bring a pocket back");
    }

    @Test
    public void aFeederThatFeedsShortTheOtherWayIsMeasuredToo() throws Exception {
        Tape tape = new Tape(-1, -0.02);
        feederScale(0, 0, 40_000, 39_200);

        PhotonFeedCalibrator.Result result = new PhotonFeedCalibrator(tape, tape).calibrate(feeder, null);

        assertEquals(39.2, result.measuredMm, 1e-6);
    }

    @Test
    public void aRatchetFeederIsRefused() throws Exception {
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_FEED_SCALE})).reply(vendorReply(0));
        Tape tape = new Tape(1, 0);

        Exception e = assertThrows(Exception.class, () -> new PhotonFeedCalibrator(tape, tape).calibrate(feeder, null));
        assertTrue(e.getMessage().contains("no feed scale"));
        assertEquals(0, tape.partFeeds);
    }

    @Test
    public void aTapeThatDoesNotMoveStopsTheCalibration() throws Exception {
        feederScale(0, 0, 0, 0);
        Tape tape = new Tape(1, -1);

        Exception e = assertThrows(Exception.class, () -> new PhotonFeedCalibrator(tape, tape).calibrate(feeder, null));
        assertTrue(e.getMessage().contains("for a 1 mm feed"));
        assertEquals(0, tape.partFeeds);
    }

    @Test
    public void ratchetCalibrationWaitsChecksAndSaves() throws Exception {
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_CALIBRATE_RATCHET})).reply(vendorReply(1));
        bus.when(new MoveFeedStatus(ADDRESS)).reply(new ResponsesHelper(0).moveFeedStatus.ok(ADDRESS));
        // ready, no error, gap 135 ticks
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_DRIVE_STATUS})).reply(vendorReply(1, 135, 0));
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_SAVE_DRIVE_PARAMETERS})).reply(vendorReply(1));

        assertEquals(135, feeder.calibrateRatchet());
        bus.verifyInMockedOrder();
    }

    @Test
    public void aFailedRatchetCalibrationSaysWhyAndSavesNothing() throws Exception {
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_CALIBRATE_RATCHET})).reply(vendorReply(1));
        bus.when(new MoveFeedStatus(ADDRESS)).reply(new ResponsesHelper(0).moveFeedStatus.ok(ADDRESS));
        // fault, no contact
        bus.when(new VendorOptions(ADDRESS, new int[]{PhotonFeeder.CMD_DRIVE_STATUS})).reply(vendorReply(0x13));

        Exception e = assertThrows(Exception.class, () -> feeder.calibrateRatchet());
        assertTrue(e.getMessage().contains("never met a ratchet face"));
        bus.verifyInMockedOrder();
    }
}
