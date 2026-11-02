package org.openpnp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class FeederVisionHelperTest {
    @Test
    public void fourMillimetrePitchPocketsLieHalfwayBetweenHoles() {
        // An estimate nearly in line with a hole goes to the pocket beside it, not the hole
        assertEquals(2, FeederVisionHelper.nominalAlongTape(0.9, 4), 1e-9);
        assertEquals(-2, FeederVisionHelper.nominalAlongTape(-0.9, 4), 1e-9);
        assertEquals(6, FeederVisionHelper.nominalAlongTape(4.2, 8), 1e-9);
        assertEquals(2, FeederVisionHelper.nominalAlongTape(2.3, 4), 1e-9);
    }

    @Test
    public void twoMillimetrePitchAlsoHasPocketsInLineWithHoles() {
        assertEquals(0, FeederVisionHelper.nominalAlongTape(0.9, 2), 1e-9);
        assertEquals(2, FeederVisionHelper.nominalAlongTape(1.1, 2), 1e-9);
    }
}
