package org.openpnp.machine.photon;

import org.openpnp.model.Identifiable;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.IdentifiableList;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;

import java.util.ArrayList;
import java.util.List;

public class PhotonFeederSlots {
    @ElementList
    IdentifiableList<Slot> slots = new IdentifiableList<>();

    @ElementList(required = false)
    List<Rail> rails = new ArrayList<>();

    public List<Rail> getRails() {
        return rails;
    }

    /**
     * Sets the location of every slot on the rail, spaced evenly between its first and last slot.
     * Z and rotation are taken from the first slot.
     *
     * @return the number of slots set
     */
    public int fillRail(Rail rail) {
        Location first = rail.getFirstLocation();
        Location last = rail.getLastLocation().convertToUnits(first.getUnits());
        int from = Math.min(rail.getFirstAddress(), rail.getLastAddress());
        int to = Math.max(rail.getFirstAddress(), rail.getLastAddress());
        int span = rail.getLastAddress() - rail.getFirstAddress();

        for (int address = from; address <= to; address++) {
            double t = span == 0 ? 0 : (double) (address - rail.getFirstAddress()) / span;
            Location location = new Location(first.getUnits(),
                    first.getX() + t * (last.getX() - first.getX()),
                    first.getY() + t * (last.getY() - first.getY()),
                    first.getZ(),
                    first.getRotation());
            getSlot(address).setLocation(location);
        }

        return to - from + 1;
    }

    public Slot getSlot(int address) {
        String slotIdentifier = Slot.getIdentifierFromAddress(address);
        Slot slot = slots.get(slotIdentifier);

        if(slot == null) {
            slot = new Slot(address);
            slots.add(slot);
        }

        return slot;
    }

    public static class Slot implements Identifiable {
        @Attribute
        private int address;

        @Element(required = false)
        private Location location;

        private Slot() {}

        public Slot(int address) {
            this.address = address;
        }

        static String getIdentifierFromAddress(int address) {
            return "PHOTON-SLOT-" + address;
        }

        @Override
        public String getId() {
            return getIdentifierFromAddress(address);
        }

        public int getAddress() {
            return address;
        }

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location location) {
            this.location = location;
        }
    }

    /**
     * A straight row of slots, taught by its first and last slot. The slots in between are
     * assumed to be evenly spaced.
     */
    public static class Rail {
        @Attribute
        private int firstAddress = 1;

        @Attribute
        private int lastAddress = 1;

        @Element
        private Location firstLocation = new Location(LengthUnit.Millimeters);

        @Element
        private Location lastLocation = new Location(LengthUnit.Millimeters);

        public Rail() {}

        public Rail(int firstAddress, Location firstLocation, int lastAddress, Location lastLocation) {
            this.firstAddress = firstAddress;
            this.firstLocation = firstLocation;
            this.lastAddress = lastAddress;
            this.lastLocation = lastLocation;
        }

        public int getFirstAddress() {
            return firstAddress;
        }

        public void setFirstAddress(int firstAddress) {
            this.firstAddress = firstAddress;
        }

        public int getLastAddress() {
            return lastAddress;
        }

        public void setLastAddress(int lastAddress) {
            this.lastAddress = lastAddress;
        }

        public Location getFirstLocation() {
            return firstLocation;
        }

        public void setFirstLocation(Location firstLocation) {
            this.firstLocation = firstLocation;
        }

        public Location getLastLocation() {
            return lastLocation;
        }

        public void setLastLocation(Location lastLocation) {
            this.lastLocation = lastLocation;
        }
    }
}
