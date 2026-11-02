package org.openpnp.machine.photon.protocol.commands;

import org.openpnp.machine.photon.protocol.Command;
import org.openpnp.machine.photon.protocol.Packet;
import org.openpnp.machine.photon.protocol.PacketBuilder;

/**
 * Broadcast that silences every feeder running its app for a number of seconds, so one feeder
 * can be updated without traffic from the others. 0 seconds ends the quiet at once. Feeders
 * don't reply, and feeders in the bootloader ignore it.
 */
public class BusQuiet extends Command<Void> {
    public static final int COMMAND_ID = 0xC4;
    public static final int BROADCAST_ID = 0xFF;

    private final int seconds;

    public BusQuiet(int seconds) {
        this.seconds = seconds;
    }

    @Override
    public Packet toPacket() {
        return PacketBuilder.command(COMMAND_ID, BROADCAST_ID)
                .putByte(seconds)
                .toPacket();
    }

    @Override
    protected Void decodePacket(Packet responsePacket) {
        return null;
    }
}
