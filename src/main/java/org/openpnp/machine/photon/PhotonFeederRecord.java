package org.openpnp.machine.photon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The feeder record kept in the feeder's flash by feeder firmware that supports it: what is
 * loaded in the feeder (part ID, tape width, pitch, quantity at load) and how far it has fed
 * since, so the approximate number of parts left travels with the feeder.
 *
 * It is read and written with Photon vendor options, all values little endian:
 * <pre>
 *  0x14 status  -> [0] loaded [1] tape width mm [2..3] pitch 0.1 mm [4..7] qty at load
 *                  [8..11] fed 0.1 mm [12..15] parts left [16] 1 = not yet saved to flash
 *  0x15 read ID : [1] offset (0, 12 ... 60)          -> [0..11] part ID bytes (4 at offset 60)
 *  0x16 stage ID: [1] offset (0, 12 ... 60) [2..13] bytes -> [0] 1 = ok, 0 = offset past the end;
 *                 offset 0 clears the rest of the staged ID
 *  0x17 load    : [1] tape width [2..3] pitch [4..7] qty, optional [8..9] rotation 0.1 deg
 *                 with [10] = 1                      -> [0] 1 = ok, uses the staged ID
 *  0x18 set parts left: [1..4] count                 -> [0] 1 = ok (0 if nothing is loaded)
 *  0x19 clear (tape removed)                          -> [0] 1
 *  0x1A low-parts warning -> [0..1] setting [2..5] threshold in parts [6] 0 ok, 1 low, 2 empty
 *  0x1B set low-parts warning: [1..2] setting         -> [0] 1
 *  0x1C rotation in tape -> [0..1] 0.1 deg, 0xFFFF = not given [2] 1 = stored (older: all zeros)
 *  0x1D set rotation: [1..2] 0.1 deg 0..3599, 0xFFFF = forget -> [0] 1 = ok
 * </pre>
 * The low-parts setting is 0xFFFF for 10 % of the quantity at load (the default), 0 for off,
 * anything else a part count. It belongs to the feeder and survives reel loads and clears.
 * Only feeds sent over the bus are counted, so the count is approximate.
 *
 * The rotation in tape is the part's rotation in the feeder as OpenPnP uses it (the feeder's
 * offset rotation). It belongs to the reel, so a new load starts without one.
 *
 * The part ID is up to 64 characters. Firmware before 2026-10-20 keeps 24: it refuses to stage
 * at offset 24 or more and reads zeros there.
 */
public class PhotonFeederRecord {
    public static final int CMD_STATUS = 0x14;
    public static final int CMD_READ_ID = 0x15;
    public static final int CMD_STAGE_ID = 0x16;
    public static final int CMD_LOAD = 0x17;
    public static final int CMD_SET_PARTS_LEFT = 0x18;
    public static final int CMD_CLEAR = 0x19;
    public static final int CMD_LOW_PARTS = 0x1A;
    public static final int CMD_SET_LOW_PARTS = 0x1B;
    public static final int CMD_ROTATION = 0x1C;
    public static final int CMD_SET_ROTATION = 0x1D;

    public static final int ROTATION_NONE = 0xFFFF;

    public static final int LOW_PARTS_DEFAULT = 0xFFFF;
    public static final int LOW_PARTS_OFF = 0;
    public static final int LOW_PARTS_MAX_COUNT = 0xFFFE;

    public enum Level {
        OK,
        LOW,
        EMPTY
    }

    public static final int PART_ID_LENGTH = 64;
    // What feeder firmware before 2026-10-20 keeps
    public static final int OLD_PART_ID_LENGTH = 24;
    public static final int PART_ID_CHUNK = 12;

    public final boolean loaded;
    public final int tapeWidthMm;
    public final int pitchTenthsMm;
    public final long quantityAtLoad;
    public final long fedTenthsMm;
    public final long partsLeft;
    public final boolean unsaved;
    public final String partId;
    // The part's rotation in the tape in 0.1 deg, null if not given or the firmware doesn't keep it
    public final Integer rotationTenthsDeg;

    public PhotonFeederRecord(boolean loaded, int tapeWidthMm, int pitchTenthsMm, long quantityAtLoad,
                              long fedTenthsMm, long partsLeft, boolean unsaved, String partId) {
        this(loaded, tapeWidthMm, pitchTenthsMm, quantityAtLoad, fedTenthsMm, partsLeft, unsaved, partId, null);
    }

    public PhotonFeederRecord(boolean loaded, int tapeWidthMm, int pitchTenthsMm, long quantityAtLoad,
                              long fedTenthsMm, long partsLeft, boolean unsaved, String partId,
                              Integer rotationTenthsDeg) {
        this.loaded = loaded;
        this.tapeWidthMm = tapeWidthMm;
        this.pitchTenthsMm = pitchTenthsMm;
        this.quantityAtLoad = quantityAtLoad;
        this.fedTenthsMm = fedTenthsMm;
        this.partsLeft = partsLeft;
        this.unsaved = unsaved;
        this.partId = partId;
        this.rotationTenthsDeg = rotationTenthsDeg;
    }

    /**
     * The same record with the rotation in tape read with 0x1C.
     */
    public PhotonFeederRecord withRotation(Integer rotationTenthsDeg) {
        return new PhotonFeederRecord(loaded, tapeWidthMm, pitchTenthsMm, quantityAtLoad,
                fedTenthsMm, partsLeft, unsaved, partId, rotationTenthsDeg);
    }

    /**
     * The rotation in tape in degrees, or null if not given.
     */
    public Double getRotationDegrees() {
        return rotationTenthsDeg == null ? null : rotationTenthsDeg / 10.0;
    }

    /**
     * Decodes the reply to a status request (0x14). The part ID is read separately.
     */
    public static PhotonFeederRecord fromStatus(int[] data, String partId) {
        return new PhotonFeederRecord(
                data[0] != 0,
                data[1],
                uint16(data, 2),
                uint32(data, 4),
                uint32(data, 8),
                uint32(data, 12),
                data[16] != 0,
                partId);
    }

    /**
     * The same record after the feeder fed forward (or backward, if negative) by the given
     * distance over the bus, worked out the way the firmware counts it. This keeps the count
     * current between reads without extra bus traffic.
     */
    public PhotonFeederRecord afterFeed(long tenthsMm) {
        if (!loaded || pitchTenthsMm == 0) {
            return this;
        }

        long fed = Math.max(0, fedTenthsMm + tenthsMm);
        long left = Math.max(0, quantityAtLoad - fed / pitchTenthsMm);
        return new PhotonFeederRecord(loaded, tapeWidthMm, pitchTenthsMm, quantityAtLoad,
                fed, left, true, partId, rotationTenthsDeg);
    }

    /**
     * True if this record names the given OpenPnP part ID. The feeder stores at most 64
     * characters (24 with older firmware), so a longer ID matches on its first 64 (or 24).
     */
    public boolean matchesPartId(String openPnpPartId) {
        if (partId == null || openPnpPartId == null) {
            return false;
        }

        return truncatePartId(openPnpPartId).equals(partId)
                || (partId.length() == OLD_PART_ID_LENGTH
                        && truncatePartId(openPnpPartId, OLD_PART_ID_LENGTH).equals(partId));
    }

    /**
     * The parts left at which the feeder starts warning, for a low-parts setting, worked out the
     * way the firmware does. 0 when off or when no reel is recorded.
     */
    public long lowPartsThreshold(int setting) {
        if (!loaded || setting == LOW_PARTS_OFF) {
            return 0;
        }
        return setting == LOW_PARTS_DEFAULT ? quantityAtLoad / 10 : setting;
    }

    public Level lowPartsLevel(int setting) {
        if (!loaded || setting == LOW_PARTS_OFF) {
            return Level.OK;
        }
        if (partsLeft == 0) {
            return Level.EMPTY;
        }
        return partsLeft <= lowPartsThreshold(setting) ? Level.LOW : Level.OK;
    }

    public static int[] statusRequest() {
        return new int[]{CMD_STATUS};
    }

    public static int[] readIdRequest(int offset) {
        return new int[]{CMD_READ_ID, offset};
    }

    public static int[] stageIdRequest(String partId, int offset) {
        byte[] id = partIdBytes(partId);
        int[] data = new int[2 + PART_ID_CHUNK];
        data[0] = CMD_STAGE_ID;
        data[1] = offset;
        for (int i = 0; i < PART_ID_CHUNK && offset + i < id.length; i++) {
            data[2 + i] = id[offset + i] & 0xFF;
        }
        return data;
    }

    /**
     * The offsets to stage a part ID at: one 12 byte chunk per 12 characters. New firmware clears
     * the rest at offset 0, but older firmware doesn't, so the first two chunks always go.
     */
    public static List<Integer> stageOffsets(String partId) {
        int length = truncatePartId(partId).length();
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < OLD_PART_ID_LENGTH || offset < length; offset += PART_ID_CHUNK) {
            offsets.add(offset);
        }
        return offsets;
    }

    /**
     * The offsets a part ID can be read at, 0 to 60.
     */
    public static List<Integer> readOffsets() {
        List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < PART_ID_LENGTH; offset += PART_ID_CHUNK) {
            offsets.add(offset);
        }
        return offsets;
    }

    /**
     * True if a 0x15 reply at this offset ends the part ID: it holds a zero byte, or it is the
     * last chunk.
     */
    public static boolean endsPartId(int[] chunk, int offset) {
        int bytes = Math.min(PART_ID_CHUNK, PART_ID_LENGTH - offset);
        for (int i = 0; i < bytes; i++) {
            if (i >= chunk.length || chunk[i] == 0) {
                return true;
            }
        }
        return offset + PART_ID_CHUNK >= PART_ID_LENGTH;
    }

    public static int[] loadRequest(int tapeWidthMm, int pitchTenthsMm, long quantity) {
        int[] data = new int[8];
        data[0] = CMD_LOAD;
        data[1] = tapeWidthMm;
        putUint16(data, 2, pitchTenthsMm);
        putUint32(data, 4, quantity);
        return data;
    }

    /**
     * A load that also stores the rotation in tape, or none if null. Older firmware ignores it.
     */
    public static int[] loadRequest(int tapeWidthMm, int pitchTenthsMm, long quantity, Integer rotationTenthsDeg) {
        if (rotationTenthsDeg == null) {
            return loadRequest(tapeWidthMm, pitchTenthsMm, quantity);
        }
        int[] data = new int[11];
        System.arraycopy(loadRequest(tapeWidthMm, pitchTenthsMm, quantity), 0, data, 0, 8);
        putUint16(data, 8, rotationTenthsDeg);
        data[10] = 1;
        return data;
    }

    public static int[] rotationRequest() {
        return new int[]{CMD_ROTATION};
    }

    /**
     * Sets the rotation in tape, or forgets it if null.
     */
    public static int[] setRotationRequest(Integer rotationTenthsDeg) {
        int[] data = new int[3];
        data[0] = CMD_SET_ROTATION;
        putUint16(data, 1, rotationTenthsDeg == null ? ROTATION_NONE : rotationTenthsDeg);
        return data;
    }

    /**
     * The rotation in a reply to 0x1C, or null if not given or the firmware doesn't keep one.
     */
    public static Integer rotationFromReply(int[] reply) {
        if (reply.length < 3 || reply[2] != 1) {
            return null;
        }
        int rotation = uint16(reply, 0);
        return rotation == ROTATION_NONE || rotation >= 3600 ? null : rotation;
    }

    /**
     * Degrees as the feeder stores them: 0.1 deg steps from 0 to 3599.
     */
    public static int rotationTenths(double degrees) {
        long tenths = Math.round(degrees * 10) % 3600;
        return (int) (tenths < 0 ? tenths + 3600 : tenths);
    }

    public static int[] setPartsLeftRequest(long partsLeft) {
        int[] data = new int[5];
        data[0] = CMD_SET_PARTS_LEFT;
        putUint32(data, 1, partsLeft);
        return data;
    }

    public static int[] clearRequest() {
        return new int[]{CMD_CLEAR};
    }

    public static int[] lowPartsRequest() {
        return new int[]{CMD_LOW_PARTS};
    }

    public static int[] setLowPartsRequest(int setting) {
        int[] data = new int[3];
        data[0] = CMD_SET_LOW_PARTS;
        putUint16(data, 1, setting);
        return data;
    }

    /**
     * The low-parts setting in a reply to 0x1A.
     */
    public static int lowPartsSetting(int[] reply) {
        return uint16(reply, 0);
    }

    /**
     * Joins the 12 byte chunks of a part ID (0x15 replies, from offset 0 on) into a string,
     * dropping the zero padding.
     */
    public static String partIdFromChunks(int[]... chunks) {
        byte[] id = new byte[PART_ID_LENGTH];
        for (int c = 0; c < chunks.length; c++) {
            for (int i = 0; i < PART_ID_CHUNK && c * PART_ID_CHUNK + i < PART_ID_LENGTH
                    && i < chunks[c].length; i++) {
                id[c * PART_ID_CHUNK + i] = (byte) chunks[c][i];
            }
        }

        int length = 0;
        while (length < PART_ID_LENGTH && id[length] != 0) {
            length++;
        }

        return new String(id, 0, length, StandardCharsets.US_ASCII);
    }

    public static String truncatePartId(String partId) {
        return truncatePartId(partId, PART_ID_LENGTH);
    }

    /**
     * The part ID as a feeder keeping this many characters stores it.
     */
    public static String truncatePartId(String partId, int maxLength) {
        String ascii = new String(partIdBytes(partId), StandardCharsets.US_ASCII);
        int end = ascii.indexOf('\0');
        ascii = end < 0 ? ascii : ascii.substring(0, end);
        return ascii.length() > maxLength ? ascii.substring(0, maxLength) : ascii;
    }

    /**
     * The part ID as the feeder stores it: ASCII (anything else becomes '?'), truncated or zero
     * padded to 64 bytes.
     */
    static byte[] partIdBytes(String partId) {
        byte[] id = new byte[PART_ID_LENGTH];
        for (int i = 0; i < PART_ID_LENGTH && i < partId.length(); i++) {
            char c = partId.charAt(i);
            id[i] = (byte) ((c >= 0x20 && c < 0x7F) ? c : '?');
        }
        return id;
    }

    static int uint16(int[] data, int index) {
        return (data[index] & 0xFF) | ((data[index + 1] & 0xFF) << 8);
    }

    static long uint32(int[] data, int index) {
        return (data[index] & 0xFFL)
                | ((data[index + 1] & 0xFFL) << 8)
                | ((data[index + 2] & 0xFFL) << 16)
                | ((data[index + 3] & 0xFFL) << 24);
    }

    static void putUint16(int[] data, int index, int value) {
        data[index] = value & 0xFF;
        data[index + 1] = (value >> 8) & 0xFF;
    }

    static void putUint32(int[] data, int index, long value) {
        for (int i = 0; i < 4; i++) {
            data[index + i] = (int) ((value >> (8 * i)) & 0xFF);
        }
    }
}
