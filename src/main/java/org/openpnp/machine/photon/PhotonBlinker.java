package org.openpnp.machine.photon;

import org.pmw.tinylog.Logger;

/**
 * Keeps a feeder's LED blinking until stopped. One identify command blinks the LED three times
 * (about 1.8 s), so the command is sent again as each round ends.
 *
 * The blinking runs on its own thread, so start it only while the machine thread is waiting
 * (for example on a question to the user), never alongside other bus traffic.
 */
public class PhotonBlinker {
    // Three blinks of 300 ms on and 300 ms off, plus a little for the bus
    static long repeatMs = 1900;
    // Stop on its own if nobody does, so a forgotten feeder doesn't blink all night
    static long maxMs = 10 * 60 * 1000;

    public interface Blink {
        void blink() throws Exception;
    }

    private final Thread thread;
    private volatile boolean stopped = false;

    private PhotonBlinker(Blink blink) {
        thread = new Thread(() -> {
            long end = System.currentTimeMillis() + maxMs;
            while (!stopped && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(repeatMs);
                }
                catch (InterruptedException e) {
                    return;
                }
                if (stopped) {
                    return;
                }
                try {
                    blink.blink();
                }
                catch (Exception e) {
                    Logger.debug("Photon feeder blink not answered: {}", e.getMessage());
                }
            }
        }, "PhotonBlinker");
        thread.setDaemon(true);
    }

    /**
     * Blinks once now (so a feeder that doesn't answer throws here), then keeps blinking.
     */
    public static PhotonBlinker start(Blink blink) throws Exception {
        blink.blink();
        PhotonBlinker blinker = new PhotonBlinker(blink);
        blinker.thread.start();
        return blinker;
    }

    /**
     * Stops blinking and waits for a command in flight, so the bus is free when this returns.
     */
    public void stop() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(repeatMs + 2000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
