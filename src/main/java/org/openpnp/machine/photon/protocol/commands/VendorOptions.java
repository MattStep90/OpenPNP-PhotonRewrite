package org.openpnp.machine.photon.protocol.commands;

import org.openpnp.machine.photon.protocol.Command;
import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;

/**
 * Photon vendor options: 20 bytes in, 20 bytes out. The meaning of the bytes is defined by the
 * feeder firmware, the first byte usually selects the vendor command.
 *
 * Feeder firmware can send the reply packed: 17 data bytes as 7 bit groups, least significant
 * bits first, each in a byte with its top bit set. The packed reply never holds a run of zero
 * bytes. The rs485 library in the LumenPnP mainboard firmware (0.0.7) takes five zero bytes inside
 * a longer reply for a complete packet and drops the real one, so plain replies with zero
 * padding don't get through it. A reply whose 20 bytes all have the top bit set is unpacked,
 * anything else is used as is.
 */
public class VendorOptions extends Command<VendorOptions.Response> {
    public static final int COMMAND_ID = 0xBF;
    public static final int DATA_LENGTH = 20;
    public static final int PACKED_DATA_LENGTH = 17;

    private final int toAddress;
    private final int[] data;

    /**
     * @param toAddress address to send this packet to
     * @param data up to 20 bytes of vendor data, the rest is sent as zero
     */
    public VendorOptions(int toAddress, int[] data) {
        if (data.length > DATA_LENGTH) {
            throw new IllegalArgumentException("Vendor options carry at most " + DATA_LENGTH + " bytes");
        }

        this.toAddress = toAddress;
        this.data = new int[DATA_LENGTH];
        System.arraycopy(data, 0, this.data, 0, data.length);
    }

    @Override
    public Packet toPacket() {
        PacketBuilder builder = PacketBuilder.command(COMMAND_ID, toAddress);

        for (int dataByte : data) {
            builder.putByte(dataByte);
        }

        return builder.toPacket();
    }

    @Override
    protected Response decodePacket(Packet responsePacket) {
        return new Response(responsePacket);
    }

    public static class Response {
        public final boolean valid;
        public final int toAddress;
        public final int fromAddress;
        public final ErrorTypes error;
        public final int[] data;

        public Response(Packet packet) {
            toAddress = packet.toAddress;
            fromAddress = packet.fromAddress;

            if (packet.payloadLength < 1) {  // Can't even get the error byte
                valid = false;
                error = null;
                data = null;
                return;
            }

            error = ErrorTypes.fromId(packet.payload[0]);

            if (error != ErrorTypes.NONE) {
                // e.g. an uninitialized feeder answers with its UUID instead of vendor data
                valid = true;
                data = null;
                return;
            }

            if (packet.payloadLength != 1 + DATA_LENGTH) {
                valid = false;
                data = null;
                return;
            }

            int[] body = new int[DATA_LENGTH];
            System.arraycopy(packet.payload, 1, body, 0, DATA_LENGTH);

            valid = true;
            data = isPacked(body) ? unpack(body) : body;
        }
    }

    static boolean isPacked(int[] body) {
        for (int b : body) {
            if ((b & 0x80) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unpacks a packed reply into its 17 data bytes, returned zero padded to 20 bytes so they
     * are read the same way as a plain reply.
     */
    static int[] unpack(int[] body) {
        int[] data = new int[DATA_LENGTH];
        System.arraycopy(unpack(body, PACKED_DATA_LENGTH), 0, data, 0, PACKED_DATA_LENGTH);
        return data;
    }

    /**
     * Unpacks 7 bit groups, least significant bits first, into the given number of data bytes.
     * The feeder packs every reply that could otherwise hold a run of zero bytes this way.
     */
    public static int[] unpack(int[] body, int dataLength) {
        int[] data = new int[dataLength];
        int accumulator = 0;
        int bits = 0;
        int index = 0;
        for (int b : body) {
            accumulator |= (b & 0x7F) << bits;
            bits += 7;
            if (bits >= 8 && index < dataLength) {
                data[index++] = accumulator & 0xFF;
                accumulator >>>= 8;
                bits -= 8;
            }
        }
        return data;
    }

    /**
     * Packs 17 data bytes the way the feeder firmware does. Used by tests.
     */
    static int[] pack(int[] data) {
        return pack(data, PACKED_DATA_LENGTH, DATA_LENGTH);
    }

    /**
     * Packs data bytes the way the feeder firmware does, into the given number of bytes. Used by
     * tests.
     */
    public static int[] pack(int[] data, int dataLength, int bodyLength) {
        int[] body = new int[bodyLength];
        int accumulator = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < dataLength; i++) {
            accumulator |= (i < data.length ? data[i] & 0xFF : 0) << bits;
            bits += 8;
            while (bits >= 7) {
                body[index++] = 0x80 | (accumulator & 0x7F);
                accumulator >>>= 7;
                bits -= 7;
            }
        }
        while (index < bodyLength) {
            body[index++] = 0x80 | (accumulator & 0x7F);
            accumulator >>>= 7;
        }
        return body;
    }
}
