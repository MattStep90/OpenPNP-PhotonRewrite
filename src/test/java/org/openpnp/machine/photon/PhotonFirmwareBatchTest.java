package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonFirmwareBatchTest {
    private final byte[] stockImage = {1, 2};
    private final byte[] ratchetImage = {3, 4};
    private final Map<PhotonFeeder, String> versions = new HashMap<>();
    private final List<String> updated = new ArrayList<>();
    private final Map<Object, byte[]> imageFor = new HashMap<>();

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    private PhotonFeeder feeder(int slot, String version) throws Exception {
        PhotonFeeder feeder = new PhotonFeeder();
        feeder.setHardwareId(String.format("%024X", slot));
        feeder.setSlotAddress(slot);
        Configuration.get().getMachine().addFeeder(feeder);
        versions.put(feeder, version);
        return feeder;
    }

    private PhotonFirmwareBatch batch(String failOn) {
        return new PhotonFirmwareBatch((feeder, image, progress) -> {
            progress.update("Writing", 0.5, true);
            if (feeder.getHardwareId().equals(failOn)) {
                throw new Exception("The feeder did not answer");
            }
            updated.add(feeder.getHardwareId());
            imageFor.put(feeder, image);
            return PhotonFirmwareUpdater.Outcome.UPDATED;
        }, versions::get);
    }

    private Map<PhotonFirmwareBatch.Drive, byte[]> bothImages() {
        Map<PhotonFirmwareBatch.Drive, byte[]> images = new EnumMap<>(PhotonFirmwareBatch.Drive.class);
        images.put(PhotonFirmwareBatch.Drive.STOCK, stockImage);
        images.put(PhotonFirmwareBatch.Drive.RATCHET, ratchetImage);
        return images;
    }

    @Test
    public void eachFeederGetsTheImageForItsDrive() throws Exception {
        PhotonFeeder stock = feeder(3, "v2.0.0-stock");
        PhotonFeeder ratchet = feeder(1, "v2.0.0-ratchet");

        List<PhotonFirmwareBatch.Result> results = batch(null).run(
                PhotonFirmwareBatch.feedersInSlots(Configuration.get().getMachine().getFeeders()), bothImages(), null, null);

        assertEquals(1, results.get(0).slotAddress, "in slot order");
        assertSame(ratchetImage, imageFor.get(ratchet));
        assertSame(stockImage, imageFor.get(stock));
        assertEquals("Updated", results.get(1).outcome);
    }

    @Test
    public void aFeederOfUnknownDriveIsSkipped() throws Exception {
        PhotonFeeder unknown = feeder(2, null);

        List<PhotonFirmwareBatch.Result> results = batch(null).run(List.of(unknown), bothImages(), null, null);

        assertTrue(results.get(0).outcome.startsWith("Skipped: can't tell"));
        assertTrue(updated.isEmpty());
    }

    @Test
    public void onlyTheChosenDriveIsUpdated() throws Exception {
        PhotonFeeder stock = feeder(1, "v2.0.0-stock");
        PhotonFeeder ratchet = feeder(2, "v2.0.0-ratchet");
        Map<PhotonFirmwareBatch.Drive, byte[]> images = new EnumMap<>(PhotonFirmwareBatch.Drive.class);
        images.put(PhotonFirmwareBatch.Drive.STOCK, stockImage);

        List<PhotonFirmwareBatch.Result> results = batch(null).run(List.of(stock, ratchet), images, null, null);

        assertEquals("Updated", results.get(0).outcome);
        assertEquals("Skipped: no Ratchet file chosen", results.get(1).outcome);
    }

    @Test
    public void aFailureDoesNotStopTheOthers() throws Exception {
        PhotonFeeder first = feeder(1, "v2.0.0-stock");
        PhotonFeeder second = feeder(2, "v2.0.0-stock");

        List<PhotonFirmwareBatch.Result> results = batch(first.getHardwareId()).run(List.of(first, second), bothImages(), null, null);

        assertTrue(results.get(0).failed);
        assertEquals("Failed: The feeder did not answer", results.get(0).outcome);
        assertEquals("Updated", results.get(1).outcome);
    }

    @Test
    public void stoppingTakesEffectBetweenFeeders() throws Exception {
        PhotonFeeder first = feeder(1, "v2.0.0-stock");
        PhotonFeeder second = feeder(2, "v2.0.0-stock");

        List<PhotonFirmwareBatch.Result> results = batch(null).run(List.of(first, second), bothImages(), null,
                () -> !updated.isEmpty());

        assertEquals("Updated", results.get(0).outcome);
        assertEquals("Not started: stopped", results.get(1).outcome);
    }

    @Test
    public void drivesAreToldFromVersionsAndFileNames() throws Exception {
        assertEquals(PhotonFirmwareBatch.Drive.STOCK, PhotonFirmwareBatch.driveOfVersion("v2.0.0-dev-stock"));
        assertEquals(PhotonFirmwareBatch.Drive.RATCHET, PhotonFirmwareBatch.driveOfVersion("v2.0.0-Ratchet "));
        assertNull(PhotonFirmwareBatch.driveOfVersion("1.0.7"));
        assertEquals(PhotonFirmwareBatch.Drive.RATCHET, PhotonFirmwareBatch.driveOfFileName("PhotonFeeder_F031_Ratchet.bin"));
        assertEquals(PhotonFirmwareBatch.Drive.STOCK, PhotonFirmwareBatch.driveOfFileName("PhotonFeeder_F031_Stock.bin"));
        assertNull(PhotonFirmwareBatch.driveOfFileName("firmware.bin"));
    }
}
