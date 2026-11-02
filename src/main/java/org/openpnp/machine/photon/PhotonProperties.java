package org.openpnp.machine.photon;

import org.openpnp.spi.Machine;
import org.openpnp.util.FeederVisionHelper.PipelineType;

public class PhotonProperties {
    static final String FEEDER_COMMUNICATION_MAX_RETRY = "PhotonFeeder.FeederCommunicationMaxRetry";
    static final String FEEDER_SLOTS_PROPERTY = "PhotonFeeder.FeederSlots";
    static final String MAX_FEEDER_ADDRESS = "PhotonFeeder.MaxFeederAddress";
    static final String AUTO_SETUP_PIPELINE = "PhotonFeeder.AutoSetupPipeline";
    static final String AUTO_SETUP_FEED_PAST_EMPTY = "PhotonFeeder.AutoSetupFeedPastEmptyPockets";
    static final String AUTO_SETUP_PART_SCORE = "PhotonFeeder.AutoSetupPartScore";
    static final double DEFAULT_PART_SCORE = 20;
    static final String RECHECK_AFTER_FAILED_PICK = "PhotonFeeder.RecheckAfterFailedPick";
    static final String AUTO_SETUP_PROBE_HEIGHT = "PhotonFeeder.AutoSetupProbePickHeight";
    static final String FEED_AFTER_PICK = "PhotonFeeder.FeedAfterPick";

    final Machine machine;

    public PhotonProperties(Machine machine) {
        this.machine = machine;
    }

    public int getFeederCommunicationMaxRetry() {
        Integer maxRetry = (Integer) machine.getProperty(FEEDER_COMMUNICATION_MAX_RETRY);

        if(maxRetry == null) {
            maxRetry = 3;
            setFeederCommunicationMaxRetry(maxRetry);
        }

        return maxRetry;
    }

    public void setFeederCommunicationMaxRetry(int maxRetry) {
        machine.setProperty(FEEDER_COMMUNICATION_MAX_RETRY, maxRetry);
    }

    public int getMaxFeederAddress() {
        Integer maxFeederAddress = (Integer) machine.getProperty(MAX_FEEDER_ADDRESS);

        if(maxFeederAddress == null) {
            maxFeederAddress = 50;
            setMaxFeederAddress(maxFeederAddress);
        }

        return maxFeederAddress;
    }

    public void setMaxFeederAddress(int maxFeederAddress) {
        machine.setProperty(MAX_FEEDER_ADDRESS, maxFeederAddress);
    }

    public synchronized PhotonFeederSlots getFeederSlots() {
        PhotonFeederSlots feederSlots = (PhotonFeederSlots) machine.getProperty(FEEDER_SLOTS_PROPERTY);

        if(feederSlots == null) {
            feederSlots = new PhotonFeederSlots();
            machine.setProperty(FEEDER_SLOTS_PROPERTY, feederSlots);
        }

        return feederSlots;
    }

    /**
     * The sprocket hole pipeline Auto Setup tries first. The other one is tried if it finds nothing.
     */
    public PipelineType getAutoSetupPipeline() {
        Object value = machine.getProperty(AUTO_SETUP_PIPELINE);
        if (value instanceof String) {
            try {
                return PipelineType.valueOf((String) value);
            }
            catch (IllegalArgumentException e) {
                // fall through to the default
            }
        }
        return PipelineType.CircularSymmetry;
    }

    public void setAutoSetupPipeline(PipelineType pipelineType) {
        machine.setProperty(AUTO_SETUP_PIPELINE, pipelineType.name());
    }

    /**
     * Whether Auto Setup feeds the tape on until a part is under the camera.
     */
    public boolean getAutoSetupFeedPastEmptyPockets() {
        return "true".equals(machine.getProperty(AUTO_SETUP_FEED_PAST_EMPTY));
    }

    public void setAutoSetupFeedPastEmptyPockets(boolean feed) {
        machine.setProperty(AUTO_SETUP_FEED_PAST_EMPTY, Boolean.toString(feed));
    }

    /**
     * The pocket score at and above which Auto Setup takes a pocket to hold a part.
     */
    public double getAutoSetupPartScore() {
        Object value = machine.getProperty(AUTO_SETUP_PART_SCORE);
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            }
            catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return DEFAULT_PART_SCORE;
    }

    public void setAutoSetupPartScore(double score) {
        machine.setProperty(AUTO_SETUP_PART_SCORE, Double.toString(score));
    }

    /**
     * Whether a Photon feeder measures its pick point again with the camera when a pick fails.
     * On unless turned off.
     */
    public boolean getRecheckAfterFailedPick() {
        return !"false".equals(machine.getProperty(RECHECK_AFTER_FAILED_PICK));
    }

    public void setRecheckAfterFailedPick(boolean recheck) {
        machine.setProperty(RECHECK_AFTER_FAILED_PICK, Boolean.toString(recheck));
    }

    /**
     * Whether a Photon feeder starts advancing to its next part right after each pick, so the
     * part is waiting when the head comes back. Off unless turned on.
     */
    public boolean getFeedAfterPick() {
        return "true".equals(machine.getProperty(FEED_AFTER_PICK));
    }

    public void setFeedAfterPick(boolean feedAfterPick) {
        machine.setProperty(FEED_AFTER_PICK, Boolean.toString(feedAfterPick));
    }

    /**
     * Whether Auto Setup touches down on each part with the nozzle to set the pick height.
     */
    public boolean getAutoSetupProbePickHeight() {
        return "true".equals(machine.getProperty(AUTO_SETUP_PROBE_HEIGHT));
    }

    public void setAutoSetupProbePickHeight(boolean probe) {
        machine.setProperty(AUTO_SETUP_PROBE_HEIGHT, Boolean.toString(probe));
    }
}
