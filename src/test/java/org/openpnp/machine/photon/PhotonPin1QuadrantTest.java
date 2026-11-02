package org.openpnp.machine.photon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;

public class PhotonPin1QuadrantTest {
    // Pin 1 top left at 0 degrees, as IPC-7351 draws SOIC, TSSOP, QFN and QFP
    private static final double X = -1.9;
    private static final double Y = 1.0;

    @Test
    public void quadrantsForPin1TopLeftMatchTheFixtureTable() {
        // The loading fixture uses the same table, so both store the same rotation
        assertEquals(90.0, PhotonPin1Quadrant.rotationFor(1, X, Y));
        assertEquals(0.0, PhotonPin1Quadrant.rotationFor(2, X, Y));
        assertEquals(180.0, PhotonPin1Quadrant.rotationFor(3, X, Y));
        assertEquals(270.0, PhotonPin1Quadrant.rotationFor(4, X, Y));
    }

    @Test
    public void tps1h200RunsAtNinetyFromQuadrantOne() {
        // Matt's TPS1H200 (TI HTSSOP, Q1) places correctly at rotation in tape 90
        assertEquals(90.0, PhotonPin1Quadrant.rotationFor(1, footprintWithPin1(-2.9, 1.95)));
    }

    @Test
    public void pin1BottomRightTurnsTheOtherWay() {
        assertEquals(270.0, PhotonPin1Quadrant.rotationFor(1, 1.0, -1.0));
        assertEquals(180.0, PhotonPin1Quadrant.rotationFor(2, 1.0, -1.0));
    }

    @Test
    public void pin1OnAnAxisCannotBeDecided() {
        assertNull(PhotonPin1Quadrant.rotationFor(1, -0.5, 0));
        assertNull(PhotonPin1Quadrant.rotationFor(1, 0, 0.8));
        assertNull(PhotonPin1Quadrant.quadrantOf(90, -0.5, 0));
    }

    @Test
    public void quadrantOfUndoesRotationFor() {
        double[][] pins = {{X, Y}, {1, 1}, {1, -1}, {-1, -1}};
        for (double[] pin : pins) {
            for (int q = 1; q <= 4; q++) {
                Double rotation = PhotonPin1Quadrant.rotationFor(q, pin[0], pin[1]);
                assertEquals(q, PhotonPin1Quadrant.quadrantOf(rotation, pin[0], pin[1]));
                assertEquals(q, PhotonPin1Quadrant.quadrantOf(rotation - 360, pin[0], pin[1]));
            }
        }
    }

    @Test
    public void quadrantOfIgnoresRotationsOffAQuarterTurn() {
        assertNull(PhotonPin1Quadrant.quadrantOf(45, X, Y));
        assertEquals(1, PhotonPin1Quadrant.quadrantOf(90.4, X, Y));
    }

    @Test
    public void datasheetViewShowsPin1InItsQuadrant() {
        // Q1 at rotation in tape 90 shows the part upright in the datasheet view, pin 1 top left
        assertEquals(0.0, PhotonPin1Quadrant.datasheetViewRotation(90));
    }

    @Test
    public void footprintWithoutPin1HasNoRotation() {
        Footprint footprint = new Footprint();
        Pad pad = new Pad();
        pad.setName("A");
        pad.setX(1);
        pad.setY(1);
        footprint.addPad(pad);
        assertNull(PhotonPin1Quadrant.rotationFor(1, footprint));
        assertNull(PhotonPin1Quadrant.rotationFor(1, (Footprint) null));
    }

    private static Footprint footprintWithPin1(double x, double y) {
        Footprint footprint = new Footprint();
        Pad pin1 = new Pad();
        pin1.setName("1");
        pin1.setX(x);
        pin1.setY(y);
        footprint.addPad(pin1);
        Pad other = new Pad();
        other.setName("2");
        other.setX(x);
        other.setY(-y);
        footprint.addPad(other);
        return footprint;
    }
}
