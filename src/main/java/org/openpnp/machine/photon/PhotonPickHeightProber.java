package org.openpnp.machine.photon;

import org.openpnp.machine.reference.ContactProbeNozzle;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;

/**
 * Finds a feeder's pick height by touching the part with the nozzle, using the nozzle's own
 * contact probing (Machine Setup > Nozzles: Contact Probe Nozzle). With the Vacuum Sense method
 * the nozzle steps down with the vacuum pulsing until the part blocks it, so the height found is
 * the top of the part, which is where OpenPnP picks a tape part from.
 */
public class PhotonPickHeightProber implements PhotonAutoSetup.HeightProber {
    private final ContactProbeNozzle nozzle;

    public PhotonPickHeightProber(ContactProbeNozzle nozzle) {
        this.nozzle = nozzle;
    }

    /**
     * The nozzle to probe with: the default head's default nozzle.
     */
    public static Nozzle defaultNozzle() throws Exception {
        Head head = Configuration.get().getMachine().getDefaultHead();
        return head == null ? null : head.getDefaultNozzle();
    }

    /**
     * Why this nozzle can't probe pick heights, or null if it can.
     */
    public static String whyNot(Nozzle nozzle) {
        if (!(nozzle instanceof ContactProbeNozzle)) {
            return "The nozzle isn't a Contact Probe Nozzle. Change it under Machine Setup > Nozzles "
                    + "and set its contact probe method to Vacuum Sense.";
        }
        ContactProbeNozzle probeNozzle = (ContactProbeNozzle) nozzle;
        if (probeNozzle.getContactProbeMethod() == null
                || probeNozzle.getContactProbeMethod() == ContactProbeNozzle.ContactProbeMethod.None) {
            return "Nozzle " + nozzle.getName() + " has contact probing off. Set its contact probe method "
                    + "to Vacuum Sense under Machine Setup > Nozzles.";
        }
        if (nozzle.getNozzleTip() == null) {
            return "Load a nozzle tip on nozzle " + nozzle.getName() + " first.";
        }
        if (nozzle.getPart() != null) {
            return "Nozzle " + nozzle.getName() + " is holding a part. Discard it first.";
        }
        return null;
    }

    @Override
    public Location probe(PhotonFeeder feeder, Location pick) throws Exception {
        try {
            return nozzle.contactProbeCycle(pick);
        }
        finally {
            nozzle.moveToSafeZ();
        }
    }
}
