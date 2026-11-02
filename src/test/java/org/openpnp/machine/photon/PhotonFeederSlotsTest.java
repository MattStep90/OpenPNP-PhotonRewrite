package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Machine;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonFeederSlotsTest {
    private PhotonFeederSlots feederSlots;
    private File workingDirectory;

    @BeforeEach
    public void setUp() throws Exception {
        workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        Machine machine = Configuration.get().getMachine();
        feederSlots = new PhotonProperties(machine).getFeederSlots();
    }
    @Test
    public void byDefaultAnUnknownSlotHasNoLocationConfigured() {
        int address = 5;
        PhotonFeederSlots.Slot slot = feederSlots.getSlot(address);

        assertEquals(address, slot.getAddress());
        assertNull(slot.getLocation());
    }

    @Test
    public void fillRailSpacesSlotsEvenly() {
        PhotonFeederSlots.Rail rail = new PhotonFeederSlots.Rail(
                1, new Location(LengthUnit.Millimeters, 10, 100, -20, 90),
                5, new Location(LengthUnit.Millimeters, 90, 104, -25, 0));

        assertEquals(5, feederSlots.fillRail(rail));

        Location third = feederSlots.getSlot(3).getLocation();
        assertEquals(50, third.getX(), 1e-9);
        assertEquals(102, third.getY(), 1e-9);
        assertEquals(-20, third.getZ(), 1e-9);
        assertEquals(90, third.getRotation(), 1e-9);
        assertEquals(90, feederSlots.getSlot(5).getLocation().getX(), 1e-9);
        assertNull(feederSlots.getSlot(6).getLocation());
    }

    @Test
    public void fillRailWorksWhenTaughtLastToFirst() {
        PhotonFeederSlots.Rail rail = new PhotonFeederSlots.Rail(
                30, new Location(LengthUnit.Millimeters, 0, 0, 0, 0),
                26, new Location(LengthUnit.Millimeters, 0, 40, 0, 0));

        assertEquals(5, feederSlots.fillRail(rail));
        assertEquals(10, feederSlots.getSlot(29).getLocation().getY(), 1e-9);
        assertEquals(40, feederSlots.getSlot(26).getLocation().getY(), 1e-9);
    }

    @Test
    public void fillRailWithOneSlotUsesTheFirstLocation() {
        PhotonFeederSlots.Rail rail = new PhotonFeederSlots.Rail(
                7, new Location(LengthUnit.Millimeters, 3, 4, 5, 6),
                7, new Location(LengthUnit.Millimeters, 9, 9, 9, 9));

        assertEquals(1, feederSlots.fillRail(rail));
        assertEquals(3, feederSlots.getSlot(7).getLocation().getX(), 1e-9);
    }

    @Test
    public void railsAndHoleFinderAreSavedWithTheMachineConfiguration() throws Exception {
        feederSlots.getRails().add(new PhotonFeederSlots.Rail(
                1, new Location(LengthUnit.Millimeters, 10, 100, -20, 90),
                25, new Location(LengthUnit.Millimeters, 490, 100, -20, 90)));
        feederSlots.getRails().add(new PhotonFeederSlots.Rail(
                50, new Location(LengthUnit.Millimeters, 10, 500, -21, -90),
                26, new Location(LengthUnit.Millimeters, 490, 500, -21, -90)));
        new PhotonProperties(Configuration.get().getMachine())
                .setAutoSetupPipeline(org.openpnp.util.FeederVisionHelper.PipelineType.ColorKeyed);
        Configuration.get().save();

        Configuration.initialize(workingDirectory);
        Configuration.get().load();
        PhotonProperties reloaded = new PhotonProperties(Configuration.get().getMachine());

        assertEquals(2, reloaded.getFeederSlots().getRails().size());
        PhotonFeederSlots.Rail back = reloaded.getFeederSlots().getRails().get(1);
        assertEquals(50, back.getFirstAddress());
        assertEquals(26, back.getLastAddress());
        assertEquals(500, back.getFirstLocation().getY(), 1e-9);
        assertEquals(-90, back.getLastLocation().getRotation(), 1e-9);
        assertEquals(org.openpnp.util.FeederVisionHelper.PipelineType.ColorKeyed, reloaded.getAutoSetupPipeline());
    }
}
