package org.openpnp.machine.photon;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonSprocketFinderTest {
    private static Location mm(double x, double y) {
        return new Location(LengthUnit.Millimeters, x, y, 15, 0);
    }

    @Test
    public void towardLinePointsStraightAtTheHoleRow() {
        double[] n = PhotonSprocketFinder.towardLine(mm(10, 50), mm(4, 40), mm(4, 44));
        assertEquals(-1, n[0], 1e-9);
        assertEquals(0, n[1], 1e-9);
        assertEquals(6, n[2], 1e-9);
    }

    @Test
    public void towardLineIsNullForCoincidentHolesOrAPointOnTheRow() {
        assertNull(PhotonSprocketFinder.towardLine(mm(10, 50), mm(4, 40), mm(4, 40)));
        assertNull(PhotonSprocketFinder.towardLine(mm(4, 50), mm(4, 40), mm(4, 44)));
    }

    @Test
    public void pickFromHolesUsesTheEiaDistanceForTheTapeWidth() {
        // Holes along X = 4, the estimate well off to the right at X = 12
        Location pick8 = PhotonSprocketFinder.pickFromHoles(mm(12, 50), mm(4, 40), mm(4, 44), 8);
        assertEquals(7.5, pick8.getX(), 1e-9);
        assertEquals(50, pick8.getY(), 1e-9, "stays where the estimate is along the tape");
        assertEquals(15, pick8.getZ(), 1e-9);

        Location pick12 = PhotonSprocketFinder.pickFromHoles(mm(12, 50), mm(4, 40), mm(4, 44), 12);
        assertEquals(9.5, pick12.getX(), 1e-9);

        Location pick16 = PhotonSprocketFinder.pickFromHoles(mm(1, 50), mm(4, 40), mm(4, 44), 16);
        assertEquals(4 - 7.5, pick16.getX(), 1e-9, "on the estimate's side of the holes");
    }

    @Test
    public void pickFromHolesNeedsAKnownWidth() {
        assertNull(PhotonSprocketFinder.pickFromHoles(mm(12, 50), mm(4, 40), mm(4, 44), 0));
    }

    @Test
    public void orderHolesPutsThemCounterClockwiseFromThePick() {
        Location pick = mm(7.5, 50);
        Location low = mm(4, 48);
        Location high = mm(4, 52);
        // The same order as FeederVisionHelper gives, whichever way round they come in
        Location[] ordered = PhotonSprocketFinder.orderHoles(pick, low, high);
        Location[] swapped = PhotonSprocketFinder.orderHoles(pick, high, low);
        assertSame(low, ordered[1]);
        assertSame(high, ordered[2]);
        assertSame(low, swapped[1]);
        assertSame(high, swapped[2]);
        assertEquals(90, ordered[0].getRotation(), 1e-9);
    }

    @Test
    public void holeSideIsLearnedPerRailAndFlippedForTheFacingRail() {
        PhotonSprocketFinder finder = new PhotonSprocketFinder(null, null, 0);
        assertNull(finder.holeSide(mm(100, 45)));
        assertEquals(0, finder.learned());

        // A front rail feeder (rail along X, tape along Y) with its holes 3.5 mm to the left
        finder.learnHoleSide(mm(100, 45), mm(96.5, 43), mm(96.5, 47));
        assertEquals(1, finder.learned());

        Location sameRail = finder.holeSide(mm(250, 46));
        assertEquals(-1, sameRail.getX(), 1e-9);
        assertEquals(0, sameRail.getY(), 1e-9);

        // The back rail feeders are turned round, so their holes are to the right
        Location backRail = finder.holeSide(mm(150, 400));
        assertEquals(1, backRail.getX(), 1e-9);
        assertEquals(0, backRail.getY(), 1e-9);

        // Once a back rail feeder is measured, that wins there, and the front keeps its own
        finder.learnHoleSide(mm(200, 400), mm(196.5, 398), mm(196.5, 402));
        assertEquals(-1, finder.holeSide(mm(150, 400)).getX(), 1e-9);
        assertEquals(-1, finder.holeSide(mm(250, 46)).getX(), 1e-9);
    }

    @Test
    public void onHoleSideTellsTheHolesFromThePockets() {
        Location left = new Location(LengthUnit.Millimeters, -1, 0, 0, 0);
        // Holes 6 mm to the left of the estimate, seen from either side of them
        assertTrue(PhotonSprocketFinder.onHoleSide(mm(100, 45), left, mm(94, 43), mm(94, 47)));
        // A line to the right, e.g. the pockets of the tape, is refused
        assertFalse(PhotonSprocketFinder.onHoleSide(mm(100, 45), left, mm(103, 43), mm(103, 47)));
    }

    @Test
    public void firstLooksStepAlongTheTapeIntoTheWindow() {
        // Front rail: the tape runs along Y
        List<Location> shifts = PhotonSprocketFinder.firstLookShifts(mm(100, 45), null, 8);
        assertEquals(PhotonSprocketFinder.NEAR_LOOKS, shifts.size());
        assertEquals(0, shifts.get(3).getX(), 1e-9);
        assertEquals(4, shifts.get(3).getY(), 1e-9);
        assertEquals(8, shifts.get(4).getY(), 1e-9);
        assertEquals(-4, shifts.get(5).getY(), 1e-9);
        // Back rail, turned round: into the window is the other way
        List<Location> back = PhotonSprocketFinder.firstLookShifts(mm(100, 400).derive(null, null, null, 180.0), null, 8);
        assertEquals(-4, back.get(3).getY(), 1e-9);
    }

    @Test
    public void farLooksStayOnTheFeeder() {
        Location left = new Location(LengthUnit.Millimeters, -1, 0, 0, 0);
        // 8 mm tape: holes 3.5 mm from the pick point, so no further than 5 mm
        List<Location> shifts = PhotonSprocketFinder.firstLookShifts(mm(100, 45), left, 8);
        assertEquals(PhotonSprocketFinder.NEAR_LOOKS + 4, shifts.size());
        double farthest = 0;
        for (Location shift : shifts) {
            farthest = Math.max(farthest, Math.abs(shift.getX()));
        }
        assertEquals(5, farthest, 1e-9);
        // The far looks also look a hole pitch into the window
        assertEquals(-2.5, shifts.get(PhotonSprocketFinder.NEAR_LOOKS + 1).getX(), 1e-9);
        assertEquals(4, shifts.get(PhotonSprocketFinder.NEAR_LOOKS + 1).getY(), 1e-9);
        // 16 mm tape reaches further
        assertEquals(PhotonSprocketFinder.NEAR_LOOKS + 6,
                PhotonSprocketFinder.firstLookShifts(mm(100, 45), left, 16).size());
    }
}
