package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;
import org.openpnp.machine.photon.protocol.commands.IdentifyFeeder;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.MoveFeedForward;
import org.openpnp.machine.photon.protocol.commands.MoveFeedStatus;
import org.openpnp.machine.photon.protocol.commands.VendorOptions;
import org.openpnp.machine.photon.protocol.helpers.ResponsesHelper;
import org.openpnp.machine.photon.protocol.helpers.TestBus;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PhotonFeederRecordFeederTest {
    private final String hardwareId = "00112233445566778899AABB";
    private final int feederAddress = 5;
    private final String partId = "R0402-10K";

    private PhotonFeeder feeder;
    private Nozzle mockedNozzle;
    private PhotonProperties photonProperties;
    private TestBus bus;
    private ResponsesHelper responses;
    private Part part;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        Machine machine = Configuration.get().getMachine();
        feeder = new PhotonFeeder();
        machine.addFeeder(feeder);

        machine.removeActuator(machine.getActuatorByName(PhotonFeeder.ACTUATOR_DATA_NAME));
        Actuator mockedActuator = mock(Actuator.class);
        when(mockedActuator.getName()).thenReturn(PhotonFeeder.ACTUATOR_DATA_NAME);
        machine.addActuator(mockedActuator);

        mockedNozzle = mock(Nozzle.class);
        when(mockedNozzle.getName()).thenReturn("Test Nozzle");

        photonProperties = new PhotonProperties(machine);
        photonProperties.getFeederSlots().getSlot(feederAddress)
                .setLocation(new Location(LengthUnit.Millimeters, 1, 2, 3, 0));

        bus = new TestBus();
        PhotonFeeder.setBus(bus);
        responses = new ResponsesHelper(0);

        part = new Part(partId);
        Configuration.get().addPart(part);

        feeder.setHardwareId(hardwareId);
        feeder.setSlotAddress(feederAddress);
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 1, 0, 0));
        feeder.setMoveWhileFeeding(false);

        bus.when(new InitializeFeeder(feederAddress, hardwareId))
                .reply(responses.initializeFeeder.ok(feederAddress, hardwareId));
    }

    private Packet vendorReply(int... data) {
        PacketBuilder builder = PacketBuilder.response(0, feederAddress).putOk();
        for (int i = 0; i < VendorOptions.DATA_LENGTH; i++) {
            builder.putByte(i < data.length ? data[i] : 0);
        }
        return builder.toPacket();
    }

    private VendorOptions vendor(int[] request) {
        return new VendorOptions(feederAddress, request);
    }

    private int[] idChunk(String id, int offset) {
        int[] chunk = new int[12];
        System.arraycopy(PhotonFeederRecord.stageIdRequest(id, offset), 2, chunk, 0, 12);
        return chunk;
    }

    // Loaded, 8 mm tape, 2 mm pitch, 100 at load, 10 fed, 90 left
    private void mockLoadedRecord(String id) throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 200, 0, 0, 0, 90, 0, 0, 0, 0));
        mockIdReads(id, PhotonFeederRecord.PART_ID_LENGTH);
        mockRotation();
    }

    // The 0x1C read; no data is how firmware without the rotation in tape answers
    private void mockRotation(int... reply) throws Exception {
        bus.when(vendor(PhotonFeederRecord.rotationRequest())).reply(vendorReply(reply));
    }

    // The 0x15 reads OpenPnP makes, from a feeder keeping this many characters of the ID
    private void mockIdReads(String id, int keeps) throws Exception {
        String kept = PhotonFeederRecord.truncatePartId(id, keeps);
        for (int offset : PhotonFeederRecord.readOffsets()) {
            int[] chunk = offset < keeps ? idChunk(kept, offset) : new int[12];
            bus.when(vendor(PhotonFeederRecord.readIdRequest(offset))).reply(vendorReply(chunk));
            if (PhotonFeederRecord.endsPartId(chunk, offset)) {
                break;
            }
        }
    }

    @Test
    public void readRecordShowsPartsLeft() throws Exception {
        feeder.setPart(part);
        mockLoadedRecord(partId);

        PhotonFeederRecord record = feeder.readRecord();

        assertEquals(90, record.partsLeft);
        assertEquals(partId, feeder.getRecordPartId());
        assertEquals("~90", feeder.getRecordPartsLeft());
        assertEquals("100", feeder.getRecordQuantity());
        bus.verifyInMockedOrder();
    }

    @Test
    public void readRecordAssignsTheRecordedPartWhenNoneIsAssigned() throws Exception {
        feeder.setPartPitch(4);
        feeder.setTapeWidth(12);
        mockLoadedRecord(partId);

        feeder.readRecord();

        assertSame(part, feeder.getPart());
        assertEquals(2, feeder.getPartPitch());
        assertEquals(8, feeder.getTapeWidth());
    }

    // Older firmware keeps 24 characters
    private void mockOldFirmwareRecord(String id) throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 200, 0, 0, 0, 90, 0, 0, 0, 0));
        mockIdReads(id, PhotonFeederRecord.OLD_PART_ID_LENGTH);
    }

    @Test
    public void readRecordAssignsAPartWhoseIdIsLongerThanTheFeederKeeps() throws Exception {
        Part longPart = new Part("0402-RC0402FR-0710KL-YAGEO");
        Configuration.get().addPart(longPart);
        mockOldFirmwareRecord(longPart.getId());

        feeder.readRecord();

        assertEquals("0402-RC0402FR-0710KL-YAG", feeder.getRecordPartId());
        assertSame(longPart, feeder.getPart());
    }

    @Test
    public void readRecordDoesNotGuessWhenSeveralLongIdsShareTheFirst24Characters() throws Exception {
        Configuration.get().addPart(new Part("0402-RC0402FR-0710KL-YAGEO"));
        Configuration.get().addPart(new Part("0402-RC0402FR-0710KL-YAGEO-ALT"));
        mockOldFirmwareRecord("0402-RC0402FR-0710KL-YAGEO");

        feeder.readRecord();

        assertNull(feeder.getPart());
    }

    @Test
    public void readRecordKeepsTheAssignedPartWhenTheyDiffer() throws Exception {
        Part other = new Part("C0402-100N");
        Configuration.get().addPart(other);
        feeder.setPart(other);
        mockLoadedRecord(partId);

        feeder.readRecord();

        assertSame(other, feeder.getPart());
        assertEquals(partId, feeder.getRecordPartId());
    }

    @Test
    public void readRecordWithNothingLoadedSkipsThePartId() throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest())).reply(vendorReply());

        PhotonFeederRecord record = feeder.readRecord();

        assertFalse(record.loaded);
        assertEquals("No reel recorded", feeder.getRecordPartsLeft());
        bus.verifyInMockedOrder();
    }

    @Test
    public void loadRecordStagesTheIdLoadsAndReadsBack() throws Exception {
        feeder.setPart(part);
        feeder.setPartPitch(2);
        feeder.setTapeWidth(8);

        bus.when(vendor(PhotonFeederRecord.stageIdRequest(partId, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(partId, 12))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.loadRequest(8, 20, 100, 0))).reply(vendorReply(1));
        mockLoadedRecord(partId);

        feeder.loadRecord(100);

        bus.verifyInMockedOrder();
        assertEquals(90, feeder.getRecord().partsLeft);
    }

    @Test
    public void loadRecordStagesAndReadsBackA64CharacterId() throws Exception {
        String longId = "QFN-32_5x5mm_P0.5mm_EP3.45x3.45mm-STM32G431KBU6-LongerThan64Characters";
        Part longPart = new Part(longId);
        Configuration.get().addPart(longPart);
        feeder.setPart(longPart);
        feeder.setPartPitch(8);
        feeder.setTapeWidth(12);

        for (int offset = 0; offset < 64; offset += 12) {
            bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, offset))).reply(vendorReply(1));
        }
        bus.when(vendor(PhotonFeederRecord.loadRequest(12, 80, 100, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 12, 80, 0, 100, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0, 0));
        mockIdReads(longId, 64);
        mockRotation();

        feeder.loadRecord(100);

        bus.verifyInMockedOrder();
        assertEquals(longId.substring(0, 64), feeder.getRecord().partId);
        assertTrue(feeder.getRecord().matchesPartId(longId));
    }

    @Test
    public void olderFirmwareKeeps24Characters() throws Exception {
        String longId = "R0402-RC0402FR-0710KL-Yageo";
        Part longPart = new Part(longId);
        Configuration.get().addPart(longPart);
        feeder.setPart(longPart);
        feeder.setPartPitch(2);
        feeder.setTapeWidth(8);

        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 12))).reply(vendorReply(1));
        // Older firmware refuses offset 24
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 24))).reply(vendorReply(0));
        bus.when(vendor(PhotonFeederRecord.loadRequest(8, 20, 100, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 0, 0));
        mockIdReads(longId, 24);
        mockRotation();

        feeder.loadRecord(100);

        bus.verifyInMockedOrder();
        assertEquals(longId.substring(0, 24), feeder.getRecord().partId);
        assertTrue(feeder.getRecord().matchesPartId(longId));
    }

    @Test
    public void olderFirmwareRefusesAnIdThatClashesIn24Characters() throws Exception {
        String longId = "R0402-RC0402FR-0710KL-Yageo";
        Part longPart = new Part(longId);
        Configuration.get().addPart(longPart);
        Configuration.get().addPart(new Part("R0402-RC0402FR-0710KL-Yageo-Alt"));
        feeder.setPart(longPart);
        feeder.setPartPitch(2);
        photonProperties.setFeederCommunicationMaxRetry(0);

        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 12))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(longId, 24))).reply(vendorReply(0));

        Exception e = assertThrows(Exception.class, () -> feeder.loadRecord(100));
        assertTrue(e.getMessage().contains("older firmware"), e.getMessage());
    }

    @Test
    public void loadRecordThrowsWhenTheFirmwareRefuses() throws Exception {
        feeder.setPart(part);
        feeder.setPartPitch(2);
        photonProperties.setFeederCommunicationMaxRetry(0);

        // Firmware without the feeder record answers vendor commands with zeros
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(partId, 0))).reply(vendorReply());

        assertThrows(Exception.class, () -> feeder.loadRecord(100));
    }

    @Test
    public void loadRecordNeedsAPart() throws Exception {
        assertThrows(Exception.class, () -> feeder.loadRecord(100));
        bus.verifyNothingSent();
    }

    @Test
    public void setPartsLeftThrowsWhenNothingIsLoaded() throws Exception {
        bus.when(vendor(PhotonFeederRecord.setPartsLeftRequest(50))).reply(vendorReply(0));

        assertThrows(Exception.class, () -> feeder.setRecordPartsLeft(50));
    }

    @Test
    public void clearRecordClearsAndReadsBack() throws Exception {
        bus.when(vendor(PhotonFeederRecord.clearRequest())).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.statusRequest())).reply(vendorReply());

        feeder.clearRecord();

        bus.verifyInMockedOrder();
        assertFalse(feeder.getRecord().loaded);
    }

    @Test
    public void feedCountsDownWithoutAskingTheFeeder() throws Exception {
        feeder.setPart(part);
        feeder.setPartPitch(2);
        mockLoadedRecord(partId);
        feeder.readRecord();

        bus.when(new MoveFeedForward(feederAddress, 20))
                .reply(responses.moveFeedForward.ok(feederAddress, 0));
        bus.when(new MoveFeedStatus(feederAddress))
                .reply(responses.moveFeedStatus.ok(feederAddress));

        feeder.feed(mockedNozzle);

        assertEquals(89, feeder.getRecord().partsLeft);
        bus.verifyInMockedOrder();
    }

    @Test
    public void quietReadGivesUpOnFeedersWithoutTheRecord() throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest())).timeout();

        feeder.readRecordQuietly();
        assertNull(feeder.getRecord());

        // A second quiet read sends nothing more
        feeder.readRecordQuietly();
        bus.verifyInMockedOrder();
    }

    @Test
    public void identifySendsTheBlinkCommandAndLearnsTheSlot() throws Exception {
        bus.when(new IdentifyFeeder(hardwareId)).reply(responses.identifyFeeder.ok(9));

        feeder.identify();

        assertEquals(9, (int) feeder.getSlotAddress());
        bus.verify(new IdentifyFeeder(hardwareId)).nothingElseSent();
    }

    @Test
    public void identifyThrowsWhenTheFeederDoesNotAnswer() throws Exception {
        photonProperties.setFeederCommunicationMaxRetry(1);
        bus.when(new IdentifyFeeder(hardwareId)).timeout();

        assertThrows(Exception.class, () -> feeder.identify());
        bus.verify(new IdentifyFeeder(hardwareId)).then(new IdentifyFeeder(hardwareId)).nothingElseSent();
    }

    @Test
    public void lowPartsWarningIsReadAndShownAgainstTheRecord() throws Exception {
        feeder.setPart(part);
        mockLoadedRecord(partId);
        bus.when(vendor(PhotonFeederRecord.lowPartsRequest())).reply(vendorReply(0xFF, 0xFF, 10, 0, 0, 0, 0));

        feeder.readRecord();
        assertEquals(PhotonFeederRecord.LOW_PARTS_DEFAULT, feeder.readLowParts());

        // 10 % of 100 at load, 90 left
        assertEquals("At 10 left (10 % of the reel)", feeder.getRecordLowParts());
        assertEquals(PhotonFeederRecord.Level.OK, feeder.getLowPartsLevel());
    }

    @Test
    public void feedingDownToTheThresholdRunsLow() throws Exception {
        feeder.setPart(part);
        feeder.setPartPitch(2);
        mockLoadedRecord(partId);
        bus.when(vendor(PhotonFeederRecord.lowPartsRequest())).reply(vendorReply(90, 0));
        feeder.readRecord();
        feeder.readLowParts();
        assertEquals(PhotonFeederRecord.Level.LOW, feeder.getLowPartsLevel());
        assertTrue(feeder.getRecordLowParts().endsWith("Running low!"));
    }

    @Test
    public void settingTheLowPartsWarningWritesItAndReadsItBack() throws Exception {
        bus.when(vendor(PhotonFeederRecord.setLowPartsRequest(25))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.lowPartsRequest())).reply(vendorReply(25, 0));

        feeder.setLowParts(25);

        assertEquals(25, (int) feeder.getLowPartsSetting());
        assertEquals("At 25 parts", feeder.getRecordLowParts());
        bus.verifyInMockedOrder();
    }

    @Test
    public void lowPartsWarningCanBeTurnedOff() throws Exception {
        bus.when(vendor(PhotonFeederRecord.setLowPartsRequest(0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.lowPartsRequest())).reply(vendorReply(0, 0));

        feeder.setLowParts(PhotonFeederRecord.LOW_PARTS_OFF);

        assertEquals("Off", feeder.getRecordLowParts());
    }

    @Test
    public void waitUntilSavedReturnsOnceTheFeederHasWrittenItsFlash() throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 200, 0, 0, 0, 90, 0, 0, 0, 0));

        assertTrue(feeder.waitUntilSaved(2000));
        assertFalse(feeder.getRecord().unsaved);
    }

    @Test
    public void waitUntilSavedGivesUpWhenTheFeederKeepsReportingUnsavedChanges() throws Exception {
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 200, 0, 0, 0, 90, 0, 0, 0, 1));

        assertFalse(feeder.waitUntilSaved(0));
    }

    // The default configuration already has FIDUCIAL-HOME
    private Part addPlaceholderPart() {
        Part placeholder = Configuration.get().getPart(PhotonFeeder.PLACEHOLDER_PART_ID);
        assertNotNull(placeholder);
        return placeholder;
    }

    @Test
    public void placeholderPartIsGivenToAFeederWithNoPart() {
        Part placeholder = addPlaceholderPart();

        feeder.usePlaceholderPartIfNone();

        assertSame(placeholder, feeder.getPart());
        assertTrue(feeder.hasPlaceholderPart());
    }

    @Test
    public void placeholderPartDoesNotReplaceAnAssignedPart() {
        addPlaceholderPart();
        feeder.setPart(part);

        feeder.usePlaceholderPartIfNone();

        assertSame(part, feeder.getPart());
        assertFalse(feeder.hasPlaceholderPart());
    }

    @Test
    public void noPlaceholderPartLeavesTheFeederWithoutAPart() {
        Configuration.get().removePart(Configuration.get().getPart(PhotonFeeder.PLACEHOLDER_PART_ID));

        feeder.usePlaceholderPartIfNone();

        assertNull(feeder.getPart());
    }

    @Test
    public void readRecordReplacesThePlaceholderPart() throws Exception {
        addPlaceholderPart();
        feeder.usePlaceholderPartIfNone();
        mockLoadedRecord(partId);

        feeder.readRecord();

        assertSame(part, feeder.getPart());
    }

    @Test
    public void loadRecordRefusesThePlaceholderPart() {
        addPlaceholderPart();
        feeder.usePlaceholderPartIfNone();

        assertThrows(Exception.class, () -> feeder.loadRecord(100));
    }

    @Test
    public void readRecordTakesTheRotationInTapeTheFeederHolds() throws Exception {
        feeder.setPart(part);
        mockLoadedRecord(partId);
        // 270.0 deg = 2700 = 0x0A8C, and 1 = the firmware keeps it
        mockRotation(0x8C, 0x0A, 1);

        PhotonFeederRecord record = feeder.readRecord();

        assertEquals(2700, record.rotationTenthsDeg);
        assertEquals(270, feeder.getOffset().getRotation(), 1e-9);
        assertEquals(1, feeder.getOffset().getX(), 1e-9, "only the rotation changes");
        assertFalse(feeder.recordRotationDiffers());
    }

    @Test
    public void aRotationForAnotherPartIsLeftAlone() throws Exception {
        Part other = new Part("C0603-100N");
        Configuration.get().addPart(other);
        feeder.setPart(other);
        mockLoadedRecord(partId);
        mockRotation(0x8C, 0x0A, 1);

        feeder.readRecord();

        assertEquals(0, feeder.getOffset().getRotation(), 1e-9);
    }

    @Test
    public void loadRecordStoresTheOffsetRotation() throws Exception {
        feeder.setPart(part);
        feeder.setPartPitch(2);
        feeder.setTapeWidth(8);
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 1, 0, -90));

        bus.when(vendor(PhotonFeederRecord.stageIdRequest(partId, 0))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.stageIdRequest(partId, 12))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.loadRequest(8, 20, 100, 2700))).reply(vendorReply(1));
        mockLoadedRecord(partId);

        feeder.loadRecord(100);

        bus.verifyInMockedOrder();
    }

    @Test
    public void aChangedRotationIsSavedToTheFeeder() throws Exception {
        feeder.setPart(part);
        mockLoadedRecord(partId);
        feeder.readRecord();
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 1, 0, 90));
        assertTrue(feeder.recordRotationDiffers());

        bus.when(vendor(PhotonFeederRecord.setRotationRequest(900))).reply(vendorReply(1));
        bus.when(vendor(PhotonFeederRecord.statusRequest()))
                .reply(vendorReply(1, 8, 20, 0, 100, 0, 0, 0, 200, 0, 0, 0, 90, 0, 0, 0, 0));
        mockIdReads(partId, PhotonFeederRecord.PART_ID_LENGTH);
        mockRotation(0x84, 0x03, 1);

        feeder.saveRotationToRecord();

        assertEquals(900, feeder.getRecord().rotationTenthsDeg);
        assertFalse(feeder.recordRotationDiffers());
    }

    @Test
    public void firmwareThatCantKeepTheRotationIsNotAskedAgain() throws Exception {
        feeder.setPart(part);
        mockLoadedRecord(partId);
        feeder.readRecord();
        feeder.setOffset(new Location(LengthUnit.Millimeters, 1, 1, 0, 90));
        bus.when(vendor(PhotonFeederRecord.setRotationRequest(900))).reply(vendorReply(0));

        feeder.saveRotationToRecord();

        assertFalse(feeder.recordRotationDiffers());
    }
}
