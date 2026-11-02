package org.openpnp.gui.components.reticle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;

import com.google.common.io.Files;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

public class FeederFootprintReticleTest {
    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    private static Pad pad(String name) {
        Pad pad = new Pad();
        pad.setName(name);
        return pad;
    }

    @Test
    public void pin1IsTheMarkedPadElseThePadNamed1() {
        Footprint footprint = new Footprint();
        Pad two = pad("2");
        Pad one = pad("1");
        footprint.addPad(two);
        footprint.addPad(one);
        assertSame(one, FeederFootprintReticle.pin1(footprint));
        two.setMark(true);
        assertSame(two, FeederFootprintReticle.pin1(footprint));
    }

    @Test
    public void aReferenceFeederTurnsTheFootprintToItsLocation() {
        ReferenceAutoFeeder feeder = new ReferenceAutoFeeder();
        feeder.setLocation(new Location(LengthUnit.Millimeters, 10, 20, 0, 270));
        assertEquals(270, FeederFootprintReticle.pickRotation(feeder), 1e-9);
    }

    @Test
    public void aPhotonFeederWithoutASlotDrawsNothing() {
        assertNull(FeederFootprintReticle.pickRotation(new PhotonFeeder()));
    }
}
