package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonSlotRepairTest {
    // Feeders on the bus: hardware ID -> address it answers from
    private final Map<String, Integer> bus = new LinkedHashMap<>();
    private final List<String> initialized = new ArrayList<>();
    private final List<String> blinked = new ArrayList<>();
    private final Map<String, Integer> answers = new HashMap<>();
    private final List<Integer> suggestions = new ArrayList<>();
    private final List<Set<Integer>> takenSeen = new ArrayList<>();
    private boolean floorBroken = false;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    private static String id(int n) {
        return String.format("%024X", n);
    }

    private PhotonFeeder known(int n) throws Exception {
        PhotonFeeder feeder = new PhotonFeeder();
        feeder.setHardwareId(id(n));
        Configuration.get().getMachine().addFeeder(feeder);
        return feeder;
    }

    private PhotonSlotRepair repair() {
        return new PhotonSlotRepair(new PhotonSlotRepair.Bus() {
            @Override
            public Integer addressOf(String hardwareId) {
                return bus.get(hardwareId);
            }

            @Override
            public void identify(String hardwareId) {
                blinked.add(hardwareId);
            }

            @Override
            public boolean program(String hardwareId, int address) {
                if (floorBroken) {
                    return false;
                }
                bus.put(hardwareId, address);
                return true;
            }

            @Override
            public void initialize(PhotonFeeder feeder) {
                initialized.add(feeder.getHardwareId());
            }

            @Override
            public String findUninitialized() {
                // Answered by every feeder not set up; null when none, or several (a collision)
                List<String> waiting = new ArrayList<>();
                for (String hardwareId : bus.keySet()) {
                    if (!initialized.contains(hardwareId)) {
                        waiting.add(hardwareId);
                    }
                }
                return waiting.size() == 1 ? waiting.get(0) : null;
            }
        });
    }

    private PhotonSlotRepair.SlotAsker asker() {
        return (feeder, isNew, suggested, taken) -> {
            suggestions.add(suggested);
            takenSeen.add(taken);
            return answers.get(feeder.getHardwareId());
        };
    }

    @Test
    public void lostSlotsAreWrittenWithEveryFeederIn() throws Exception {
        PhotonFeeder good = known(1);
        PhotonFeeder lost = known(2);
        PhotonFeeder alsoLost = known(3);
        bus.put(id(1), 5);
        bus.put(id(2), 0xFF);
        bus.put(id(3), 0xFF);
        answers.put(id(2), 6);
        answers.put(id(3), 7);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertEquals(2, results.size());
        assertEquals(List.of(id(2), id(3)), blinked);
        assertEquals(6, (int) lost.getSlotAddress());
        assertEquals(7, (int) alsoLost.getSlotAddress());
        assertEquals(5, (int) good.getSlotAddress());
        assertEquals(Set.of(5), takenSeen.get(0));
        assertEquals("Slot written", results.get(0).outcome);
        assertNull(results.get(0).before);
        assertTrue(initialized.containsAll(List.of(id(1), id(2), id(3))));
    }

    @Test
    public void theLastSlotIsSuggested() throws Exception {
        PhotonFeeder lost = known(2);
        lost.setSlotAddress(9);
        bus.put(id(2), 0xFF);
        answers.put(id(2), 9);

        repair().run(asker(), null);

        assertEquals(9, (int) suggestions.get(0));
        assertEquals(9, (int) lost.getLastSlotAddress());
    }

    @Test
    public void feedersSharingAnAddressAreBothFixed() throws Exception {
        known(1);
        known(2);
        bus.put(id(1), 4);
        bus.put(id(2), 4);
        answers.put(id(1), 4);
        answers.put(id(2), 8);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertEquals(2, results.size());
        assertEquals(4, (int) bus.get(id(1)));
        assertEquals(8, (int) bus.get(id(2)));
    }

    @Test
    public void oneNewFeederIsFoundWithTheKnownOnesIn() throws Exception {
        known(1);
        known(2);
        bus.put(id(1), 1);
        bus.put(id(2), 2);
        bus.put(id(42), 0xFF);
        answers.put(id(42), 3);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertEquals(1, results.size());
        assertEquals("New feeder, slot written", results.get(0).outcome);
        PhotonFeeder added = PhotonFeeder.findByHardwareId(id(42));
        assertNotNull(added);
        assertEquals(3, (int) added.getSlotAddress());
    }

    @Test
    public void twoNewFeedersAnswerAtOnceSoNeitherIsFound() throws Exception {
        bus.put(id(41), 0xFF);
        bus.put(id(42), 0xFF);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertTrue(results.isEmpty());
    }

    @Test
    public void aSkippedFeederIsLeftAlone() throws Exception {
        PhotonFeeder lost = known(2);
        bus.put(id(2), 0xFF);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertEquals(1, results.size(), "asked once");
        assertEquals("Left as it was", results.get(0).outcome);
        assertNull(lost.getSlotAddress());
        assertEquals(0xFF, (int) bus.get(id(2)));
    }

    @Test
    public void aFailedWriteIsReported() throws Exception {
        known(2);
        bus.put(id(2), 0xFF);
        answers.put(id(2), 6);
        floorBroken = true;

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertTrue(results.get(0).failed);
        assertTrue(results.get(0).outcome.contains("couldn't write the slot's ID"));
    }

    @Test
    public void aTakenSlotIsRefused() throws Exception {
        known(1);
        known(2);
        bus.put(id(1), 5);
        bus.put(id(2), 0xFF);
        answers.put(id(2), 5);

        List<PhotonSlotRepair.Result> results = repair().run(asker(), null);

        assertTrue(results.get(0).failed);
        assertEquals(0xFF, (int) bus.get(id(2)));
    }
}
