package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PhotonPickRecheckTest {
    private PhotonFeeder feeder;
    private PhotonFeederSlots.Slot slot;
    private PhotonProperties photonProperties;
    private Nozzle nozzle;
    private int finds;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        Machine machine = Configuration.get().getMachine();
        photonProperties = new PhotonProperties(machine);
        slot = photonProperties.getFeederSlots().getSlot(5);
        slot.setLocation(new Location(LengthUnit.Millimeters, 100, 50, -20, 90));

        feeder = new PhotonFeeder();
        feeder.setHardwareId("00112233445566778899AABB");
        feeder.setSlotAddress(5);
        feeder.setOffset(new Location(LengthUnit.Millimeters));
        feeder.setPartPitch(4);
        machine.addFeeder(feeder);

        nozzle = mock(Nozzle.class);
        when(nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)).thenReturn(true);
        finds = 0;
    }

    @AfterEach
    public void tearDown() {
        PhotonFeeder.recheckFinder = null;
    }

    private void visionFindsPickShiftedBy(double dx, double dy) {
        PhotonFeeder.recheckFinder = (f, estimate) -> {
            finds++;
            return new Location(LengthUnit.Millimeters, estimate.getX() + dx, estimate.getY() + dy, 0, 0);
        };
    }

    @Test
    public void aFailedPickMovesTheSlotToTheMeasuredPickPoint() throws Exception {
        when(nozzle.isPartOn()).thenReturn(false);
        visionFindsPickShiftedBy(0.6, -0.3);

        feeder.postPick(nozzle);

        assertEquals(1, finds);
        Location location = slot.getLocation();
        assertEquals(100.6, location.getX(), 1e-9);
        assertEquals(49.7, location.getY(), 1e-9);
        assertEquals(-20, location.getZ(), 1e-9, "Z stays");
        assertEquals(90, location.getRotation(), 1e-9, "rotation stays");
    }

    @Test
    public void aGoodPickIsNotRechecked() throws Exception {
        when(nozzle.isPartOn()).thenReturn(true);
        visionFindsPickShiftedBy(0.6, 0);

        feeder.postPick(nozzle);

        assertEquals(0, finds);
        assertEquals(100, slot.getLocation().getX(), 1e-9);
    }

    @Test
    public void aSmallDifferenceLeavesTheSlotAlone() throws Exception {
        when(nozzle.isPartOn()).thenReturn(false);
        visionFindsPickShiftedBy(0.1, 0.1);

        feeder.postPick(nozzle);

        assertEquals(1, finds);
        assertEquals(100, slot.getLocation().getX(), 1e-9);
    }

    @Test
    public void moreThanHalfAPitchAwayIsDistrusted() throws Exception {
        when(nozzle.isPartOn()).thenReturn(false);
        visionFindsPickShiftedBy(2.5, 0);

        feeder.postPick(nozzle);

        assertEquals(100, slot.getLocation().getX(), 1e-9);
    }

    @Test
    public void aVisionFailureDoesNotStopTheJob() throws Exception {
        when(nozzle.isPartOn()).thenReturn(false);
        PhotonFeeder.recheckFinder = (f, estimate) -> {
            throw new Exception("No sprocket holes found");
        };

        feeder.postPick(nozzle);

        assertEquals(100, slot.getLocation().getX(), 1e-9);
    }

    @Test
    public void recheckCanBeTurnedOff() throws Exception {
        photonProperties.setRecheckAfterFailedPick(false);
        when(nozzle.isPartOn()).thenReturn(false);
        visionFindsPickShiftedBy(0.6, 0);

        feeder.postPick(nozzle);

        assertEquals(0, finds);
    }

    @Test
    public void checkEveryPickMeasuresThePickPointOnEachFeed() throws Exception {
        feeder.setCheckEveryPick(true);
        visionFindsPickShiftedBy(0.6, 0);
        // A part put back: no tape move, so no bus is needed, but the pick point is still checked
        feeder.setFeedOptions(org.openpnp.machine.reference.ReferenceFeeder.FeedOptions.SkipNext);

        feeder.feed(nozzle);

        assertEquals(1, finds);
        assertEquals(100.6, slot.getLocation().getX(), 1e-9);
    }

    @Test
    public void checkEveryPickIsOffByDefault() throws Exception {
        visionFindsPickShiftedBy(0.6, 0);
        feeder.setFeedOptions(org.openpnp.machine.reference.ReferenceFeeder.FeedOptions.SkipNext);

        feeder.feed(nozzle);

        assertFalse(feeder.isCheckEveryPick());
        assertEquals(0, finds);
    }

    @Test
    public void aPlaceholderPartFeederIsNotChecked() throws Exception {
        feeder.setPart(Configuration.get().getPart(PhotonFeeder.PLACEHOLDER_PART_ID));
        feeder.setCheckEveryPick(true);
        when(nozzle.isPartOn()).thenReturn(false);
        visionFindsPickShiftedBy(0.6, 0);
        feeder.setFeedOptions(org.openpnp.machine.reference.ReferenceFeeder.FeedOptions.SkipNext);

        feeder.feed(nozzle);
        feeder.postPick(nozzle);

        assertEquals(0, finds);
    }
}
