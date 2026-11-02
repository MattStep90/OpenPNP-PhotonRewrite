package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;
import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.BootloaderCommand;
import org.openpnp.machine.photon.protocol.commands.BusQuiet;
import org.openpnp.machine.photon.protocol.commands.GetFeederAddress;
import org.openpnp.machine.photon.protocol.commands.GetFeederId;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.VendorOptions;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonFirmwareUpdaterTest {
    private static final String UID = "002D801A5046571620333932";
    private static final int ADDRESS = 7;

    private PhotonFeeder feeder;
    private FakeFeeder bus;
    private final List<String> stages = new ArrayList<>();
    private final List<Boolean> powerCritical = new ArrayList<>();

    private final PhotonFirmwareUpdater.Clock clock = new PhotonFirmwareUpdater.Clock() {
        long now = 0;

        @Override
        public long millis() {
            return now += 50;
        }

        @Override
        public void sleep(long millis) {
            now += millis;
        }
    };

    /**
     * One feeder on the bus, with an app and a bootloader that behave as BOOTLOADER.md describes.
     */
    static class FakeFeeder implements PhotonBusInterface {
        boolean inBootloader = false;
        boolean appValid = true;
        boolean appAnswers = true;
        byte[] flash = new byte[21 * 1024 - 16];
        long appLength;
        long appCrc;
        boolean sessionStarted = false;
        boolean[] pageSeen = new boolean[32];
        int quietOn = 0;
        int quietOff = 0;
        int writes = 0;
        int maxFrameBytes = 0;
        String uid = UID;

        @Override
        public Optional<Packet> send(Packet command) {
            maxFrameBytes = Math.max(maxFrameBytes, 5 + command.payloadLength);
            int op = command.payload[0];

            if (op == BusQuiet.COMMAND_ID) {
                assertEquals(0xFF, command.toAddress);
                if (command.payload[1] == 0) {
                    quietOff++;
                }
                else {
                    quietOn++;
                }
                return Optional.empty();
            }
            if (op == GetFeederAddress.COMMAND_ID) {
                // Searching the bus for a feeder by UID: only its app answers.
                assertEquals(0xFF, command.toAddress);
                if (inBootloader || !appAnswers) {
                    return Optional.empty();
                }
                return reply(PacketBuilder.response(0, ADDRESS).putOk());
            }
            assertEquals(ADDRESS, command.toAddress);

            if (!inBootloader) {
                if (!appAnswers) {
                    return Optional.empty();
                }
                if (op == GetFeederId.COMMAND_ID || op == InitializeFeeder.COMMAND_ID) {
                    return reply(PacketBuilder.response(0, ADDRESS).putOk().putUuid(uid));
                }
                if (op == VendorOptions.COMMAND_ID && command.payload[1] == 0x13) {
                    inBootloader = true;
                    sessionStarted = false;
                    int[] data = new int[17];
                    data[0] = 1;
                    return reply(body(PacketBuilder.response(0, ADDRESS).putOk(), VendorOptions.pack(data, 17, 20)));
                }
                fail("unexpected app command " + op);
            }

            switch (op) {
                case BootloaderCommand.INFO: {
                    int[] info = new int[26];
                    info[0] = 1;
                    info[1] = appValid ? 1 : 0;
                    PhotonFeederRecord.putUint32(info, 2, appLength);
                    PhotonFeederRecord.putUint32(info, 6, appCrc);
                    PhotonFeederRecord.putUint32(info, 10, flash.length);
                    for (int i = 0; i < 12; i++) {
                        info[14 + i] = Integer.parseInt(uid.substring(2 * i, 2 * i + 2), 16);
                    }
                    return reply(body(PacketBuilder.response(0, ADDRESS).putOk(), VendorOptions.pack(info, 26, 30)));
                }
                case BootloaderCommand.BEGIN:
                    appValid = false;
                    sessionStarted = true;
                    Arrays.fill(pageSeen, false);
                    return ok();
                case BootloaderCommand.WRITE: {
                    assertTrue(sessionStarted);
                    int offset = command.payload[1] | (command.payload[2] << 8);
                    int length = command.payloadLength - 3;
                    assertEquals(0, offset % 2);
                    for (int i = 0; i < length; i++) {
                        flash[offset + i] = (byte) command.payload[3 + i];
                    }
                    writes++;
                    int page = offset / 1024;
                    if (!pageSeen[page]) {
                        // The page erase makes the reply miss the mainboard's read window.
                        pageSeen[page] = true;
                        return Optional.empty();
                    }
                    return ok();
                }
                case BootloaderCommand.COMMIT: {
                    long length = PhotonFeederRecord.uint32(command.payload, 1);
                    long crc = PhotonFeederRecord.uint32(command.payload, 5);
                    if (PhotonFirmwareUpdater.crc32(Arrays.copyOf(flash, (int) length)) != crc) {
                        return reply(PacketBuilder.response(0, ADDRESS).putByte(BootloaderCommand.STATUS_FAIL));
                    }
                    appValid = true;
                    appLength = length;
                    appCrc = crc;
                    return ok();
                }
                case BootloaderCommand.RUN:
                    if (!appValid) {
                        return reply(PacketBuilder.response(0, ADDRESS).putByte(BootloaderCommand.STATUS_FAIL));
                    }
                    inBootloader = false;
                    appAnswers = true;
                    return ok();
                default:
                    // The bootloader doesn't answer app commands.
                    return Optional.empty();
            }
        }

        private static PacketBuilder body(PacketBuilder builder, int[] bytes) {
            for (int b : bytes) {
                builder.putByte(b);
            }
            return builder;
        }

        private static Optional<Packet> ok() {
            return reply(PacketBuilder.response(0, ADDRESS).putOk());
        }

        private static Optional<Packet> reply(PacketBuilder builder) {
            return Optional.of(builder.toPacket());
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        Machine machine = Configuration.get().getMachine();
        feeder = new PhotonFeeder();
        feeder.setHardwareId(UID);
        feeder.setSlotAddress(ADDRESS);
        machine.addFeeder(feeder);

        bus = new FakeFeeder();
        PhotonFeeder.setBus(bus);
        stages.clear();
        powerCritical.clear();
    }

    private PhotonFirmwareUpdater updater() {
        return new PhotonFirmwareUpdater(feeder, bus, clock, (stage, fraction, critical) -> {
            stages.add(stage);
            powerCritical.add(critical);
        });
    }

    private static byte[] image(int length, int seed) {
        byte[] image = new byte[length];
        for (int i = 0; i < length; i++) {
            image[i] = (byte) (i * 31 + seed);
        }
        return image;
    }

    @Test
    public void updatesTheFeederAndBringsItBack() throws Exception {
        byte[] image = image(15001, 3);

        assertEquals(PhotonFirmwareUpdater.Outcome.UPDATED, updater().update(image));

        byte[] sent = PhotonFirmwareUpdater.prepareImage(image);
        assertEquals(15002, sent.length);
        assertArrayEquals(sent, Arrays.copyOf(bus.flash, sent.length));
        assertTrue(bus.appValid);
        assertFalse(bus.inBootloader);
        assertEquals(PhotonFirmwareUpdater.crc32(sent), bus.appCrc);
        assertTrue(bus.maxFrameBytes <= 32, "M485 sends at most 32 bytes per frame");
        assertTrue(bus.quietOn >= 2, "quiet is refreshed during a long write");
        assertTrue(bus.quietOff >= 1, "quiet is ended");
        assertTrue(powerCritical.contains(true));
        assertFalse(powerCritical.get(powerCritical.size() - 1), "power is safe at the end");
        assertFalse(feeder.isInitialized(), "the restarted feeder must be initialized again");
    }

    @Test
    public void sameFirmwareIsNotWrittenAgain() throws Exception {
        byte[] image = image(1000, 9);
        bus.appLength = image.length;
        bus.appCrc = PhotonFirmwareUpdater.crc32(image);

        assertEquals(PhotonFirmwareUpdater.Outcome.ALREADY_UP_TO_DATE, updater().update(image));
        assertEquals(0, bus.writes);
        assertFalse(bus.inBootloader);
    }

    @Test
    public void finishesAnInterruptedUpdateWhenTheFeederIsStuckInItsBootloader() throws Exception {
        bus.inBootloader = true;
        bus.appValid = false;

        assertEquals(PhotonFirmwareUpdater.Outcome.UPDATED, updater().update(image(3000, 1)));
        assertTrue(bus.appValid);
        assertFalse(bus.inBootloader);
        assertEquals(ADDRESS, (int) feeder.getSlotAddress(), "the slot is kept");
    }

    @Test
    public void refusesABootloaderBelongingToAnotherFeeder() throws Exception {
        bus.uid = "FFEEDDCCBBAA998877665544";
        feeder.setHardwareId(UID);
        bus.inBootloader = true;

        Exception e = assertThrows(Exception.class, () -> updater().update(image(100, 1)));
        assertTrue(e.getMessage().contains("belongs to feeder"));
        assertEquals(0, bus.writes);
        assertTrue(bus.quietOff >= 1, "quiet is ended even after a failure");
    }

    @Test
    public void refusesAnImageTooBigForTheFeeder() throws Exception {
        Exception e = assertThrows(Exception.class, () -> updater().update(image(22 * 1024, 1)));
        assertTrue(e.getMessage().contains("room for"));
        assertEquals(0, bus.writes);
    }

    @Test
    public void crcIsTheStandardZlibOne() {
        assertEquals(0xCBF43926L, PhotonFirmwareUpdater.crc32("123456789".getBytes()));
    }
}
