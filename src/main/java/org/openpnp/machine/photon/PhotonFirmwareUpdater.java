package org.openpnp.machine.photon;

import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.BootloaderCommand;
import org.openpnp.machine.photon.protocol.commands.BusQuiet;
import org.openpnp.machine.photon.protocol.commands.GetFeederId;
import org.openpnp.machine.photon.protocol.commands.VendorOptions;
import org.pmw.tinylog.Logger;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Updates the firmware of one Photon feeder over the bus, through its RS-485 bootloader.
 * See the feeder firmware's docs/BOOTLOADER.md.
 *
 * The other feeders are kept silent with a broadcast bus quiet while it runs. An interrupted
 * update leaves the feeder in its bootloader (LED magenta), from where running the update again
 * finishes it. The feeder must not lose power between "begin" and "commit": until then it has
 * no valid app.
 */
public class PhotonFirmwareUpdater {
    static final int ENTER_BOOTLOADER = 0x13;
    static final int QUIET_SECONDS = 60;
    static final long QUIET_REFRESH_MS = 20_000;
    // A Photon frame through the mainboard's M485 is at most 32 bytes: 5 header, 1 op, 2 offset,
    // so 24 data bytes per write.
    static final int CHUNK = 24;
    static final int INFO_DATA_LENGTH = 26;
    static final int VERSION_CHUNK = 17;

    public interface Progress {
        /**
         * @param stage what is happening, in words for the user
         * @param fraction 0 to 1, or -1 when unknown
         * @param powerCritical true while losing power would leave the feeder without firmware
         */
        void update(String stage, double fraction, boolean powerCritical);
    }

    public interface Clock {
        long millis();

        void sleep(long millis) throws InterruptedException;
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long millis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    public enum Outcome {
        UPDATED,
        ALREADY_UP_TO_DATE
    }

    /**
     * What the bootloader reports about itself and the app it holds (0xB0).
     */
    public static class Info {
        public final int bootloaderVersion;
        public final boolean appValid;
        public final long appLength;
        public final long appCrc32;
        public final long maxAppLength;
        public final String uid;

        Info(int[] data) {
            bootloaderVersion = data[0];
            appValid = data[1] != 0;
            appLength = PhotonFeederRecord.uint32(data, 2);
            appCrc32 = PhotonFeederRecord.uint32(data, 6);
            maxAppLength = PhotonFeederRecord.uint32(data, 10);
            StringBuilder hex = new StringBuilder();
            for (int i = 14; i < 26; i++) {
                hex.append(String.format("%02X", data[i] & 0xFF));
            }
            uid = hex.toString();
        }
    }

    private final PhotonFeeder feeder;
    private final PhotonBusInterface bus;
    private final Clock clock;
    private final Progress progress;
    private long lastQuietMs;

    public PhotonFirmwareUpdater(PhotonFeeder feeder, PhotonBusInterface bus, Clock clock, Progress progress) {
        this.feeder = feeder;
        this.bus = bus;
        this.clock = clock;
        this.progress = progress;
    }

    public static PhotonFirmwareUpdater forFeeder(PhotonFeeder feeder, Progress progress) {
        return new PhotonFirmwareUpdater(feeder, PhotonFeeder.getBus(), SYSTEM_CLOCK, progress);
    }

    /**
     * The image as sent: padded to an even length, as the bootloader writes half-words.
     */
    public static byte[] prepareImage(byte[] file) {
        return file.length % 2 == 0 ? file : Arrays.copyOf(file, file.length + 1);
    }

    public static long crc32(byte[] image) {
        CRC32 crc = new CRC32();
        crc.update(image);
        return crc.getValue();
    }

    /**
     * Reads the firmware version string the feeder's app reports (vendor options 0x00 to 0x0F).
     */
    public static String readVersion(PhotonFeeder feeder) throws Exception {
        StringBuilder version = new StringBuilder();
        for (int chunk = 0; chunk < 3; chunk++) {
            int[] data = feeder.sendVendorOptions(new int[]{chunk}, 3);
            for (int i = 0; i < VERSION_CHUNK; i++) {
                if (data[i] == 0) {
                    return version.toString();
                }
                version.append((char) data[i]);
            }
        }
        return version.toString();
    }

    /**
     * The version, or null if the feeder doesn't say (e.g. it is waiting in its bootloader).
     * Keeps the feeder's slot, which a failed read would otherwise clear.
     */
    public static String readVersionQuietly(PhotonFeeder feeder) {
        Integer address = feeder.getSlotAddress();
        try {
            String version = readVersion(feeder);
            return version.isEmpty() ? null : version;
        }
        catch (Exception e) {
            if (address != null && feeder.getSlotAddress() == null) {
                feeder.setSlotAddress(address);
            }
            return null;
        }
    }

    public Outcome update(byte[] file) throws Exception {
        byte[] image = prepareImage(file);
        long crc = crc32(image);
        if (feeder.getSlotAddress() == null) {
            throw new Exception("Find the feeder's slot first.");
        }
        int address = feeder.getSlotAddress();
        String uid = feeder.getHardwareId();

        try {
            enterBootloader(address);

            quiet(QUIET_SECONDS);
            clock.sleep(300);

            progress(String.format("Talking to the bootloader in slot %d", address), -1, false);
            Info info = readInfo(address);
            if (!uid.equalsIgnoreCase(info.uid)) {
                throw new Exception(String.format(
                        "The bootloader in slot %d belongs to feeder %s, not %s.", address, info.uid, uid));
            }
            if (image.length > info.maxAppLength) {
                throw new Exception(String.format(
                        "The firmware file is %d bytes, but this feeder has room for %d.",
                        image.length, info.maxAppLength));
            }

            if (info.appValid && info.appLength == image.length && info.appCrc32 == crc) {
                Logger.info("Photon feeder {} already runs this firmware", uid);
                runApp(address);
                return Outcome.ALREADY_UP_TO_DATE;
            }

            progress("Erasing the old firmware. Don't remove power.", 0, true);
            expectOk(BootloaderCommand.begin(address), 3, "begin");

            for (int offset = 0; offset < image.length; offset += CHUNK) {
                if (clock.millis() - lastQuietMs > QUIET_REFRESH_MS) {
                    quiet(QUIET_SECONDS);
                }
                int length = Math.min(CHUNK, image.length - offset);
                expectOk(BootloaderCommand.write(address, offset, image, length), 8,
                        String.format("write at %d", offset));
                progress("Writing the new firmware. Don't remove power.",
                        (double) (offset + length) / image.length, true);
            }

            progress("Checking the new firmware. Don't remove power.", 1, true);
            BootloaderCommand.Response commit = send(BootloaderCommand.commit(address, image.length, crc), 3);
            if (commit == null) {
                throw new Exception("The feeder did not answer the final check. Run the update again.");
            }
            if (!commit.ok) {
                throw new Exception("The feeder found the new firmware corrupt and kept it disabled. Run the update again.");
            }

            runApp(address);
            return Outcome.UPDATED;
        }
        finally {
            try {
                new BusQuiet(0).send(bus);
            }
            catch (Exception e) {
                Logger.warn(e, "Could not end the bus quiet");
            }
            feeder.initialized = false;
        }
    }

    private void enterBootloader(int address) throws Exception {
        progress("Restarting the feeder into its bootloader", -1, false);
        int[] request = new int[13];
        request[0] = ENTER_BOOTLOADER;
        String uid = feeder.getHardwareId();
        for (int i = 0; i < 12; i++) {
            request[1 + i] = Integer.parseInt(uid.substring(2 * i, 2 * i + 2), 16);
        }

        int[] reply;
        try {
            reply = feeder.sendVendorOptions(request, 3);
        }
        catch (Exception e) {
            // No app answering: the feeder may already be in its bootloader, e.g. after an
            // interrupted update. Carry on and ask the bootloader.
            Logger.info("Photon feeder {} did not answer as an app, trying its bootloader: {}", uid, e.getMessage());
            // The failed search for it cleared its slot; a bootloader still answers there.
            feeder.setSlotAddress(address);
            return;
        }
        if (reply[0] != 1) {
            throw new Exception("The feeder refused to restart into its bootloader. "
                    + "Its firmware may have been installed without the bootloader (a Standalone build), "
                    + "in which case it can only be updated over FTDI.");
        }
    }

    private Info readInfo(int address) throws Exception {
        for (int i = 0; i < 20; i++) {
            BootloaderCommand.Response response = BootloaderCommand.info(address).send(bus);
            if (response != null && response.ok && response.body.length >= 30) {
                return new Info(VendorOptions.unpack(Arrays.copyOf(response.body, 30), INFO_DATA_LENGTH));
            }
            clock.sleep(100);
        }
        throw new Exception(String.format(
                "No bootloader answered in slot %d. Is the feeder inserted?", address));
    }

    private void runApp(int address) throws Exception {
        progress("Starting the feeder", 1, false);
        expectOk(BootloaderCommand.run(address), 3, "run");
        new BusQuiet(0).send(bus);
        clock.sleep(500);

        for (int i = 0; i < 10; i++) {
            GetFeederId.Response response = new GetFeederId(address).send(bus);
            if (response != null && feeder.getHardwareId().equalsIgnoreCase(response.uuid)) {
                return;
            }
            clock.sleep(200);
        }
        throw new Exception("The feeder did not come back after the update. "
                + "If its LED is magenta, run the update again.");
    }

    private void quiet(int seconds) throws Exception {
        new BusQuiet(seconds).send(bus);
        lastQuietMs = clock.millis();
    }

    private BootloaderCommand.Response send(BootloaderCommand command, int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            BootloaderCommand.Response response = command.send(bus);
            if (response != null) {
                return response;
            }
        }
        return null;
    }

    private void expectOk(BootloaderCommand command, int attempts, String what) throws Exception {
        BootloaderCommand.Response response = send(command, attempts);
        if (response == null) {
            throw new Exception(String.format("The feeder did not answer (%s). Run the update again.", what));
        }
        if (!response.ok) {
            throw new Exception(String.format("The feeder's bootloader refused %s. Run the update again.", what));
        }
    }

    private void progress(String stage, double fraction, boolean powerCritical) {
        if (progress != null) {
            progress.update(stage, fraction, powerCritical);
        }
    }

}
