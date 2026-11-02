package org.openpnp.machine.photon;

import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.GetFeederAddress;
import org.openpnp.machine.photon.protocol.commands.IdentifyFeeder;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.ProgramFeederFloorAddress;
import org.openpnp.machine.photon.protocol.commands.UninitializedFeedersRespond;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Feeder;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gives slots that lost their ID (the slot's 1-wire memory, which holds its address) their
 * address back, with every feeder left in the machine.
 *
 * Opulo's slot wizard finds the feeder to program with "uninitialized feeders respond", which
 * every feeder not yet set up answers at once, so only one feeder may be on the bus. Asking a
 * feeder its address, blinking it and programming its slot all go to one hardware ID instead, so
 * only that feeder answers. That works for every feeder OpenPnP has seen before:
 *
 * 1. Ask each known feeder for its address. One in a slot with no ID answers from the broadcast
 *    address. Feeders sharing an address are lost too: their slot IDs got copied or corrupted.
 * 2. Blink each lost feeder and ask which slot it is in (suggesting the slot it was last found
 *    in), program that slot and check the feeder now answers from it.
 * 3. Set up every feeder that has an address, so they stop answering "uninitialized feeders
 *    respond", then use it to find a feeder OpenPnP has never seen. Only one such feeder can be
 *    found at a time, since several would answer at once.
 */
public class PhotonSlotRepair {
    public static final int MAX_SLOT_ADDRESS = 254;
    static final int BLANK_ADDRESS = 0xFF;
    static final int MAX_NEW_FEEDERS = 20;

    /**
     * The bus commands, separate so tests need no feeders.
     */
    public interface Bus {
        /** The address the feeder answers from, or null if it doesn't answer. */
        Integer addressOf(String hardwareId) throws Exception;

        void identify(String hardwareId) throws Exception;

        boolean program(String hardwareId, int address) throws Exception;

        void initialize(PhotonFeeder feeder) throws Exception;

        /** The hardware ID of the one feeder not set up yet, or null if none (or several) answer. */
        String findUninitialized() throws Exception;
    }

    /**
     * Asks the user which slot a blinking feeder is in.
     */
    public interface SlotAsker {
        /**
         * @param suggested the slot to fill in, may be null
         * @param taken slots other feeders answer from
         * @return the slot, or null to leave the feeder as it is
         */
        Integer askSlot(PhotonFeeder feeder, boolean isNew, Integer suggested, Set<Integer> taken);
    }

    public interface Progress {
        void update(String stage);
    }

    public static class Result {
        public final PhotonFeeder feeder;
        public final Integer before;
        public final Integer after;
        public final String outcome;
        public final boolean failed;

        Result(PhotonFeeder feeder, Integer before, Integer after, String outcome, boolean failed) {
            this.feeder = feeder;
            this.before = before;
            this.after = after;
            this.outcome = outcome;
            this.failed = failed;
        }
    }

    private final Bus bus;

    public PhotonSlotRepair(Bus bus) {
        this.bus = bus;
    }

    public static PhotonSlotRepair forMachine() {
        return new PhotonSlotRepair(new FeederBus(PhotonFeeder.getBus()));
    }

    public List<Result> run(SlotAsker asker, Progress progress) throws Exception {
        List<PhotonFeeder> known = new ArrayList<>();
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (feeder instanceof PhotonFeeder && ((PhotonFeeder) feeder).getHardwareId() != null) {
                known.add((PhotonFeeder) feeder);
            }
        }

        // 1. Where every known feeder is
        Map<PhotonFeeder, Integer> addresses = new HashMap<>();
        Map<Integer, Integer> feedersAt = new HashMap<>();
        for (int i = 0; i < known.size(); i++) {
            PhotonFeeder feeder = known.get(i);
            report(progress, String.format("Asking feeder %d of %d for its slot", i + 1, known.size()));
            Integer address = bus.addressOf(feeder.getHardwareId());
            if (address != null) {
                addresses.put(feeder, address);
                feedersAt.merge(address, 1, Integer::sum);
            }
        }

        List<PhotonFeeder> lost = new ArrayList<>();
        Set<Integer> taken = new TreeSet<>();
        for (PhotonFeeder feeder : known) {
            Integer address = addresses.get(feeder);
            if (address == null) {
                continue;
            }
            if (!isSlot(address) || feedersAt.get(address) > 1) {
                lost.add(feeder);
            }
            else {
                taken.add(address);
                if (!Integer.valueOf(address).equals(feeder.getSlotAddress())) {
                    feeder.setSlotAddress(address);
                }
            }
        }

        // 2. Lost feeders get their slot back
        List<Result> results = new ArrayList<>();
        Set<String> asked = new TreeSet<>();
        for (PhotonFeeder feeder : lost) {
            asked.add(feeder.getHardwareId());
            results.add(repair(feeder, addresses.get(feeder), false, asker, taken, progress));
        }

        // 3. Feeders OpenPnP has never seen, one at a time
        report(progress, "Setting up the feeders that have a slot");
        for (PhotonFeeder feeder : known) {
            Integer slot = feeder.getSlotAddress();
            if (slot != null && taken.contains(slot)) {
                feeder.markUninitialized();
                bus.initialize(feeder);
            }
        }
        for (int i = 0; i < MAX_NEW_FEEDERS; i++) {
            report(progress, "Looking for a new feeder");
            String hardwareId = bus.findUninitialized();
            if (hardwareId == null || asked.contains(hardwareId)) {
                // None left, or one the user already chose to leave as it was
                break;
            }
            asked.add(hardwareId);
            PhotonFeeder feeder = PhotonFeeder.findByHardwareId(hardwareId);
            boolean isNew = feeder == null;
            if (isNew) {
                feeder = new PhotonFeeder();
                feeder.setHardwareId(hardwareId);
                feeder.usePlaceholderPartIfNone();
                Configuration.get().getMachine().addFeeder(feeder);
            }
            Integer address = bus.addressOf(hardwareId);
            Result result = repair(feeder, address, isNew, asker, taken, progress);
            results.add(result);
            if (result.after == null) {
                // Left alone, so it keeps answering: stop rather than ask about it again
                break;
            }
        }
        return results;
    }

    private Result repair(PhotonFeeder feeder, Integer address, boolean isNew, SlotAsker asker, Set<Integer> taken,
                          Progress progress) {
        Integer before = address != null && isSlot(address) ? address : null;
        try {
            report(progress, String.format("Blinking %s", feeder.getName()));
            Integer suggested = feeder.getLastSlotAddress();
            if (suggested == null || taken.contains(suggested)) {
                suggested = before != null && !taken.contains(before) ? before : null;
            }
            // Keep it blinking until the user answers
            String hardwareId = feeder.getHardwareId();
            PhotonBlinker blinker = PhotonBlinker.start(() -> bus.identify(hardwareId));
            Integer slot;
            try {
                slot = asker.askSlot(feeder, isNew, suggested, new TreeSet<>(taken));
            }
            finally {
                blinker.stop();
            }
            if (slot == null) {
                return new Result(feeder, before, null, "Left as it was", false);
            }
            if (!isSlot(slot)) {
                return new Result(feeder, before, null, String.format("Slot %d isn't between 1 and %d", slot,
                        MAX_SLOT_ADDRESS), true);
            }
            if (taken.contains(slot)) {
                return new Result(feeder, before, null, String.format("Slot %d is in use by another feeder", slot), true);
            }

            report(progress, String.format("Writing slot %d", slot));
            if (!bus.program(feeder.getHardwareId(), slot)) {
                return new Result(feeder, before, null, "The feeder couldn't write the slot's ID. "
                        + "Check the feeder is seated; a red LED means it can't see the slot's memory.", true);
            }
            Integer now = bus.addressOf(feeder.getHardwareId());
            if (now == null || now != slot) {
                return new Result(feeder, before, null, String.format(
                        "Wrote slot %d but the feeder answers from %s", slot, now == null ? "nowhere" : now), true);
            }
            taken.add(slot);
            feeder.setSlotAddress(slot);
            feeder.markUninitialized();
            bus.initialize(feeder);
            Logger.info("Photon feeder {} slot ID written: {}", feeder.getHardwareId(), slot);
            return new Result(feeder, before, slot, isNew ? "New feeder, slot written" : "Slot written", false);
        }
        catch (Exception e) {
            Logger.warn(e, "Repairing the slot of Photon feeder {} failed", feeder.getHardwareId());
            return new Result(feeder, before, null, "Failed: " + e.getMessage(), true);
        }
    }

    static boolean isSlot(int address) {
        return address >= 1 && address <= MAX_SLOT_ADDRESS;
    }

    private static void report(Progress progress, String stage) {
        if (progress != null) {
            progress.update(stage);
        }
    }

    static class FeederBus implements Bus {
        private final PhotonBusInterface photonBus;

        FeederBus(PhotonBusInterface photonBus) {
            this.photonBus = photonBus;
        }

        @Override
        public Integer addressOf(String hardwareId) throws Exception {
            for (int attempt = 0; attempt < 3; attempt++) {
                GetFeederAddress.Response response = new GetFeederAddress(hardwareId).send(photonBus);
                if (response != null) {
                    return response.fromAddress;
                }
            }
            return null;
        }

        @Override
        public void identify(String hardwareId) throws Exception {
            for (int attempt = 0; attempt < 3; attempt++) {
                IdentifyFeeder.Response response = new IdentifyFeeder(hardwareId).send(photonBus);
                if (response != null) {
                    return;
                }
            }
            throw new Exception("The feeder didn't answer when asked to blink.");
        }

        @Override
        public boolean program(String hardwareId, int address) throws Exception {
            ProgramFeederFloorAddress.Response response = new ProgramFeederFloorAddress(hardwareId, address)
                    .send(photonBus);
            return response != null && response.valid && response.error == ErrorTypes.NONE;
        }

        @Override
        public void initialize(PhotonFeeder feeder) throws Exception {
            feeder.initializeIfNeeded();
        }

        @Override
        public String findUninitialized() throws Exception {
            UninitializedFeedersRespond.Response response = new UninitializedFeedersRespond().send(photonBus);
            return response == null || !response.valid ? null : response.uuid;
        }
    }
}
