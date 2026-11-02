package org.openpnp.machine.photon.protocol.commands;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;

import static org.junit.jupiter.api.Assertions.*;

public class VendorOptionsTest {
    @Test
    public void createCommandPadsDataTo20Bytes() {
        Packet packet = new VendorOptions(12, new int[]{0x14, 0x02}).toPacket();
        assertEquals(12, packet.toAddress);
        assertEquals(21, packet.payloadLength);

        int[] expected = new int[21];
        expected[0] = 0xBF;
        expected[1] = 0x14;
        expected[2] = 0x02;
        assertArrayEquals(expected, packet.payload);
    }

    @Test
    public void createCommandRejectsTooMuchData() {
        assertThrows(IllegalArgumentException.class, () -> new VendorOptions(1, new int[21]));
    }

    @Test
    public void decodeOk() {
        PacketBuilder builder = PacketBuilder.response(0, 7).putOk();
        for (int i = 0; i < 20; i++) {
            builder.putByte(i + 1);
        }
        VendorOptions.Response response = new VendorOptions.Response(builder.toPacket());

        assertTrue(response.valid);
        assertEquals(7, response.fromAddress);
        assertEquals(ErrorTypes.NONE, response.error);
        assertEquals(20, response.data.length);
        assertEquals(1, response.data[0]);
        assertEquals(20, response.data[19]);
    }

    @Test
    public void decodeUninitializedFeeder() {
        Packet packet = PacketBuilder.response(0, 7)
                .putError(ErrorTypes.UNINITIALIZED_FEEDER)
                .putUuid("00112233445566778899AABB")
                .toPacket();
        VendorOptions.Response response = new VendorOptions.Response(packet);

        assertTrue(response.valid);
        assertEquals(ErrorTypes.UNINITIALIZED_FEEDER, response.error);
        assertNull(response.data);
    }

    @Test
    public void decodeWrongLength() {
        Packet packet = PacketBuilder.response(0, 7).putOk().putByte(1).toPacket();
        VendorOptions.Response response = new VendorOptions.Response(packet);

        assertFalse(response.valid);
        assertNull(response.data);
    }

    @Test
    public void decodeEmpty() {
        Packet packet = PacketBuilder.response(0, 7).toPacket();
        VendorOptions.Response response = new VendorOptions.Response(packet);

        assertFalse(response.valid);
        assertNull(response.error);
    }

    @Test
    public void decodePackedReply() {
        int[] status = {1, 8, 40, 0, 0x88, 0x13, 0, 0, 0x20, 0x4E, 0, 0, 0xF4, 0x0D, 0, 0, 1};
        int[] body = VendorOptions.pack(status);

        PacketBuilder builder = PacketBuilder.response(0, 7).putOk();
        for (int b : body) {
            assertTrue(b >= 0x80);
            builder.putByte(b);
        }
        VendorOptions.Response response = new VendorOptions.Response(builder.toPacket());

        assertTrue(response.valid);
        assertEquals(20, response.data.length);
        for (int i = 0; i < status.length; i++) {
            assertEquals(status[i], response.data[i], "byte " + i);
        }
        for (int i = status.length; i < 20; i++) {
            assertEquals(0, response.data[i]);
        }
    }

    @Test
    public void packedRepliesRoundTripEveryByteValue() {
        for (int value = 0; value < 256; value++) {
            int[] data = new int[17];
            for (int i = 0; i < data.length; i++) {
                data[i] = (value + i * 31) & 0xFF;
            }
            int[] unpacked = VendorOptions.unpack(VendorOptions.pack(data));
            for (int i = 0; i < data.length; i++) {
                assertEquals(data[i], unpacked[i]);
            }
        }
    }

    @Test
    public void packedEmptyRecordHasNoZeroBytes() {
        for (int b : VendorOptions.pack(new int[17])) {
            assertEquals(0x80, b);
        }
    }
}
