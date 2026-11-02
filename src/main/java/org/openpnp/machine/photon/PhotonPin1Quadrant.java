package org.openpnp.machine.photon;

import org.openpnp.gui.components.reticle.FeederFootprintReticle;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;

/**
 * Works out a Photon feeder's rotation in tape from the pin 1 quadrant printed in datasheets
 * (EIA-481 "Quadrant assignments for pin 1 orientation in tape").
 *
 * The datasheet view is the tape seen from the cover tape side, sprocket holes at the top and
 * the tape fed to the right: Q1 top left, Q2 top right, Q3 bottom left, Q4 bottom right. This is
 * also OpenPnP's EIA tape frame (see FeederVisionHelper).
 *
 * A Photon feeder's rotation in tape is the part's rotation in its slot frame (pick rotation =
 * slot rotation + rotation in tape). The slot frame is the datasheet view turned a quarter turn
 * counter-clockwise: the tape feeds toward slot +Y and the holes lie toward slot -X. The
 * loading fixture uses the same mapping, so both store the same rotation for a quadrant.
 */
public class PhotonPin1Quadrant {
    /** Below this (footprint units, normally mm) pin 1 counts as on an axis, so no corner says which way. */
    static final double ON_AXIS = 0.01;

    /**
     * The corner each quadrant lands in, in the slot frame, as {sign X, sign Y}.
     * Index 0 is Q1.
     */
    private static final int[][] SLOT_CORNER = {
            {-1, -1}, // Q1: hole side, trailing
            {-1, 1},  // Q2: hole side, leading
            {1, -1},  // Q3: far side, trailing
            {1, 1},   // Q4: far side, leading
    };

    private PhotonPin1Quadrant() {
    }

    /**
     * The rotation in tape (0, 90, 180 or 270 degrees) that puts pin 1, at (x, y) from the
     * footprint centre with the part at 0 degrees, in the given quadrant (1 to 4). Null if pin 1
     * sits on an axis, where a quadrant doesn't decide the rotation.
     */
    public static Double rotationFor(int quadrant, double pin1X, double pin1Y) {
        if (quadrant < 1 || quadrant > 4) {
            throw new IllegalArgumentException("Quadrant must be 1 to 4, not " + quadrant);
        }
        if (Math.abs(pin1X) < ON_AXIS || Math.abs(pin1Y) < ON_AXIS) {
            return null;
        }
        int[] corner = SLOT_CORNER[quadrant - 1];
        for (int rotation = 0; rotation < 360; rotation += 90) {
            double[] turned = turn(pin1X, pin1Y, rotation);
            if (Math.signum(turned[0]) == corner[0] && Math.signum(turned[1]) == corner[1]) {
                return (double) rotation;
            }
        }
        return null;
    }

    /**
     * The rotation in tape for a quadrant and a footprint, or null if the footprint has no pin 1
     * or pin 1 sits on an axis.
     */
    public static Double rotationFor(int quadrant, Footprint footprint) {
        Pad pin1 = pin1(footprint);
        return pin1 == null ? null : rotationFor(quadrant, pin1.getX(), pin1.getY());
    }

    /**
     * Which quadrant pin 1 is in at this rotation in tape, or null if pin 1 sits on an axis or
     * the rotation is not close to a quarter turn.
     */
    public static Integer quadrantOf(double rotation, double pin1X, double pin1Y) {
        double quarter = Math.round(rotation / 90.0) * 90.0;
        if (Math.abs(rotation - quarter) > 1
                || Math.abs(pin1X) < ON_AXIS || Math.abs(pin1Y) < ON_AXIS) {
            return null;
        }
        double[] turned = turn(pin1X, pin1Y, quarter);
        for (int q = 0; q < SLOT_CORNER.length; q++) {
            if (Math.signum(turned[0]) == SLOT_CORNER[q][0] && Math.signum(turned[1]) == SLOT_CORNER[q][1]) {
                return q + 1;
            }
        }
        return null;
    }

    public static Integer quadrantOf(double rotation, Footprint footprint) {
        Pad pin1 = pin1(footprint);
        return pin1 == null ? null : quadrantOf(rotation, pin1.getX(), pin1.getY());
    }

    /**
     * The part's rotation in the datasheet view, where pin 1 shows in its quadrant. The slot
     * frame is the datasheet view turned a quarter turn, so it is a quarter turn less.
     */
    public static double datasheetViewRotation(double rotationInTape) {
        return rotationInTape - 90;
    }

    public static Pad pin1(Footprint footprint) {
        return footprint == null ? null : FeederFootprintReticle.pin1(footprint);
    }

    /** (x, y) turned counter-clockwise by whole quarter turns. */
    private static double[] turn(double x, double y, double degrees) {
        int quarters = Math.floorMod((int) Math.round(degrees / 90.0), 4);
        for (int i = 0; i < quarters; i++) {
            double t = x;
            x = -y;
            y = t;
        }
        return new double[]{x, y};
    }
}
