package org.openpnp.machine.photon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonBlinkerTest {
    @AfterEach
    public void tearDown() {
        PhotonBlinker.repeatMs = 1900;
        PhotonBlinker.maxMs = 10 * 60 * 1000;
    }

    @Test
    public void keepsBlinkingUntilStopped() throws Exception {
        PhotonBlinker.repeatMs = 10;
        AtomicInteger blinks = new AtomicInteger();

        PhotonBlinker blinker = PhotonBlinker.start(blinks::incrementAndGet);
        Thread.sleep(200);
        blinker.stop();
        int atStop = blinks.get();
        Thread.sleep(100);

        assertTrue(atStop > 3, "blinked " + atStop + " times");
        assertEquals(atStop, blinks.get(), "no blinks after stop");
    }

    @Test
    public void aFeederThatDoesNotAnswerThrowsAtStart() {
        assertThrows(Exception.class, () -> PhotonBlinker.start(() -> {
            throw new Exception("did not answer");
        }));
    }

    @Test
    public void stopsOnItsOwnAfterTheLimit() throws Exception {
        PhotonBlinker.repeatMs = 10;
        PhotonBlinker.maxMs = 50;
        AtomicInteger blinks = new AtomicInteger();

        PhotonBlinker blinker = PhotonBlinker.start(blinks::incrementAndGet);
        Thread.sleep(300);
        int later = blinks.get();
        Thread.sleep(100);

        assertEquals(later, blinks.get());
        blinker.stop();
    }
}
