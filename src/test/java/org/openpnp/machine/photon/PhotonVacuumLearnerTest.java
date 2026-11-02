package org.openpnp.machine.photon;

import com.google.common.io.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PhotonVacuumLearnerTest {
    private final List<String> moves = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    private PhotonFeeder feeder(int slot) {
        PhotonFeeder feeder = new PhotonFeeder();
        feeder.setHardwareId(String.format("%024X", slot));
        return feeder;
    }

    // The empty pulse reading wobbles by 0.1 either way.
    private PhotonVacuumLearner.Rig rig(Map<PhotonFeeder, Double> partOn, double emptyHeld, double emptyPulse) {
        return new PhotonVacuumLearner.Rig() {
            private int pulses = 0;

            @Override
            public double pickAndRead(PhotonFeeder feeder) throws Exception {
                moves.add("pick " + feeder.getHardwareId().substring(23));
                Double level = partOn.get(feeder);
                if (level == null) {
                    throw new Exception("no part");
                }
                return level;
            }

            @Override
            public void putBack(PhotonFeeder feeder) {
                moves.add("back " + feeder.getHardwareId().substring(23));
            }

            @Override
            public double readEmptyHeld(PhotonFeeder feeder) {
                return emptyHeld;
            }

            @Override
            public double readEmptyPulse() {
                return emptyPulse + (pulses++ % 2 == 0 ? 0.1 : -0.1);
            }
        };
    }

    @Test
    public void everyPickedPartIsPutBack() throws Exception {
        PhotonFeeder a = feeder(1);
        PhotonFeeder b = feeder(2);
        Map<PhotonFeeder, Double> on = new HashMap<>();
        on.put(a, 60.0);

        PhotonVacuumLearner.Suggestion suggestion = new PhotonVacuumLearner(rig(on, 20, 10))
                .learn(List.of(a, b), null);

        assertEquals(List.of("pick 1", "back 1", "pick 2", "back 2"), moves);
        assertEquals("no part", suggestion.readings.get(1).problem);
        assertTrue(suggestion.isUsable(), "one good pick is enough");
    }

    @Test
    public void higherWithAPart() throws Exception {
        PhotonFeeder a = feeder(1);
        PhotonFeeder b = feeder(2);
        Map<PhotonFeeder, Double> on = new HashMap<>();
        on.put(a, 60.0);
        on.put(b, 70.0);

        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(on, 20, 10)).learn(List.of(a, b), null);

        // Halfway between the empty 20 and the weakest part 60
        assertEquals(40, s.partOnLow, 1e-9);
        assertEquals(70 + 45, s.partOnHigh, 1e-9);
        // Band around 9.9 .. 10.1: a quarter of the 45 difference beats 4 sigma of 0.1
        assertEquals(9.9 - 11.25, s.partOffLow, 1e-9);
        assertEquals(10.1 + 11.25, s.partOffHigh, 1e-9);
    }

    @Test
    public void lowerWithAPart() throws Exception {
        PhotonFeeder a = feeder(1);
        Map<PhotonFeeder, Double> on = new HashMap<>();
        on.put(a, -60.0);

        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(on, -20, -10)).learn(List.of(a), null);

        assertEquals(-40, s.partOnHigh, 1e-9);
        assertEquals(-60 - 40, s.partOnLow, 1e-9);
    }

    @Test
    public void overlappingReadingsGiveNoSuggestion() throws Exception {
        PhotonFeeder a = feeder(1);
        Map<PhotonFeeder, Double> on = new HashMap<>();
        on.put(a, 20.0);

        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(on, 20, 10)).learn(List.of(a), null);

        assertFalse(s.isUsable());
        assertTrue(s.problem.contains("overlap"));
        assertNull(s.partOnLow);
    }

    @Test
    public void noPartsPickedGivesNoSuggestion() throws Exception {
        PhotonFeeder a = feeder(1);

        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(new HashMap<>(), 20, 10)).learn(List.of(a), null);

        assertFalse(s.isUsable());
        assertEquals(List.of("pick 1", "back 1"), moves);
    }

    @Test
    public void atMostFiveFeedersArePickedFrom() throws Exception {
        List<PhotonFeeder> feeders = new ArrayList<>();
        Map<PhotonFeeder, Double> on = new HashMap<>();
        for (int i = 1; i <= 7; i++) {
            PhotonFeeder feeder = feeder(i);
            feeders.add(feeder);
            on.put(feeder, 60.0);
        }

        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(on, 20, 10)).learn(feeders, null);

        assertEquals(PhotonVacuumLearner.MAX_FEEDERS, s.readings.size());
    }

    @Test
    public void applySetsTheAbsoluteRanges() throws Exception {
        PhotonFeeder a = feeder(1);
        Map<PhotonFeeder, Double> on = new HashMap<>();
        on.put(a, 60.0);
        PhotonVacuumLearner.Suggestion s = new PhotonVacuumLearner(rig(on, 20, 10)).learn(List.of(a), null);
        ReferenceNozzleTip tip = new ReferenceNozzleTip();

        PhotonVacuumLearner.apply(tip, s);

        assertEquals(s.partOnLow, tip.getVacuumLevelPartOnLow(), 1e-9);
        assertEquals(s.partOnHigh, tip.getVacuumLevelPartOnHigh(), 1e-9);
        assertEquals(s.partOffLow, tip.getVacuumLevelPartOffLow(), 1e-9);
        assertEquals(s.partOffHigh, tip.getVacuumLevelPartOffHigh(), 1e-9);
    }

    // A feeder in a taught slot holding a part of the given package
    private PhotonFeeder placedFeeder(int slot, Package pkg) {
        PhotonFeeder feeder = feeder(slot);
        feeder.setSlotAddress(slot);
        new PhotonProperties(Configuration.get().getMachine()).getFeederSlots().getSlot(slot)
                .setLocation(new Location(LengthUnit.Millimeters, 10 * slot, 50, 15, 0));
        feeder.setOffset(new Location(LengthUnit.Millimeters));
        Part part = new Part("P" + slot);
        part.setPackage(pkg);
        feeder.setPart(part);
        feeder.setEnabled(true);
        return feeder;
    }

    @Test
    public void choosesFeedersWithoutAnAutoSetupRun() {
        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getNozzleTip()).thenReturn(tip);
        Package fits = new Package("R0402");
        fits.addCompatibleNozzleTip(tip);
        Package other = new Package("SOIC-8");

        PhotonFeeder a = placedFeeder(1, fits);
        PhotonFeeder b = placedFeeder(2, other);
        PhotonFeeder c = placedFeeder(3, fits);
        PhotonFeeder noSlot = feeder(4);
        noSlot.setPart(a.getPart());
        noSlot.setEnabled(true);
        List<Feeder> all = List.of(a, b, c, noSlot);

        assertEquals(List.of(a, c), PhotonVacuumLearner.chooseFeeders(nozzle, all, List.of(), List.of(), null));
        assertEquals(List.of(c, a), PhotonVacuumLearner.chooseFeeders(nozzle, all, List.of(c), List.of(), null),
                "ones Auto Setup saw a part in come first");
        assertEquals(List.of(c), PhotonVacuumLearner.chooseFeeders(nozzle, all, List.of(), List.of(a), null),
                "an empty pocket is left out");
    }

    @Test
    public void saysWhyNoFeederFits() {
        ReferenceNozzleTip tip = new ReferenceNozzleTip();
        tip.setName("N045");
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getNozzleTip()).thenReturn(tip);
        PhotonFeeder b = placedFeeder(2, new Package("SOIC-8"));

        StringBuilder why = new StringBuilder();
        assertTrue(PhotonVacuumLearner.chooseFeeders(nozzle, List.of(b), List.of(), List.of(), why).isEmpty());
        assertTrue(why.toString().contains("doesn't list nozzle tip N045"), why.toString());
    }
}
