package org.openpnp.machine.photon.protocol.commands;

import org.openpnp.machine.photon.protocol.Command;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;

/**
 * A command to a feeder that is running its RS-485 bootloader (0xB0 to 0xB5). The reply is
 * [status] [body], status 0x00 is OK and 0x05 is FAIL.
 */
public class BootloaderCommand extends Command<BootloaderCommand.Response> {
    public static final int INFO = 0xB0;
    public static final int BEGIN = 0xB1;
    public static final int WRITE = 0xB2;
    public static final int COMMIT = 0xB3;
    public static final int RUN = 0xB4;
    public static final int ROM = 0xB5;

    public static final int STATUS_OK = 0x00;
    public static final int STATUS_FAIL = 0x05;

    private final int toAddress;
    private final int op;
    private final int[] data;

    public BootloaderCommand(int toAddress, int op, int... data) {
        this.toAddress = toAddress;
        this.op = op;
        this.data = data;
    }

    public static BootloaderCommand info(int toAddress) {
        return new BootloaderCommand(toAddress, INFO);
    }

    public static BootloaderCommand begin(int toAddress) {
        return new BootloaderCommand(toAddress, BEGIN);
    }

    public static BootloaderCommand write(int toAddress, int offset, byte[] image, int length) {
        int[] data = new int[2 + length];
        data[0] = offset & 0xFF;
        data[1] = (offset >> 8) & 0xFF;
        for (int i = 0; i < length; i++) {
            data[2 + i] = image[offset + i] & 0xFF;
        }
        return new BootloaderCommand(toAddress, WRITE, data);
    }

    public static BootloaderCommand commit(int toAddress, long length, long crc32) {
        int[] data = new int[8];
        for (int i = 0; i < 4; i++) {
            data[i] = (int) ((length >> (8 * i)) & 0xFF);
            data[4 + i] = (int) ((crc32 >> (8 * i)) & 0xFF);
        }
        return new BootloaderCommand(toAddress, COMMIT, data);
    }

    public static BootloaderCommand run(int toAddress) {
        return new BootloaderCommand(toAddress, RUN);
    }

    @Override
    public Packet toPacket() {
        PacketBuilder builder = PacketBuilder.command(op, toAddress);
        for (int b : data) {
            builder.putByte(b);
        }
        return builder.toPacket();
    }

    @Override
    protected Response decodePacket(Packet packet) {
        return new Response(packet);
    }

    public static class Response {
        public final int fromAddress;
        public final boolean ok;
        public final int[] body;

        public Response(Packet packet) {
            fromAddress = packet.fromAddress;
            if (packet.payloadLength < 1) {
                ok = false;
                body = new int[0];
                return;
            }
            ok = packet.payload[0] == STATUS_OK;
            body = new int[packet.payloadLength - 1];
            System.arraycopy(packet.payload, 1, body, 0, body.length);
        }
    }
}
