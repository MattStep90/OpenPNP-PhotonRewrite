package org.openpnp.machine.photon;

import org.openpnp.ConfigurationListener;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.photon.exceptions.FeedFailureException;
import org.openpnp.machine.photon.exceptions.FeederHasNoLocationOffsetException;
import org.openpnp.machine.photon.exceptions.NoSlotAddressException;
import org.openpnp.machine.photon.exceptions.UnconfiguredSlotException;
import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.PhotonBus;
import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.*;
import org.openpnp.machine.photon.sheets.FeederPropertySheet;
import org.openpnp.machine.photon.sheets.GlobalConfigPropertySheet;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Solutions;
import org.openpnp.spi.*;
import org.openpnp.util.MovableUtils;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

import javax.swing.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhotonFeeder extends ReferenceFeeder {
    public static final String ACTUATOR_DATA_NAME = "PhotonFeederData";
    // Given to a feeder found with no part, until it gets a real one
    public static final String PLACEHOLDER_PART_ID = "FIDUCIAL-HOME";
    PhotonProperties photonProperties;

    @Attribute(required = false)
    protected String hardwareId;

    protected Integer slotAddress = null;

    // The last slot this feeder was found in, kept in the configuration so a feeder whose slot
    // lost its ID can be put back where it was.
    @Attribute(required = false)
    protected Integer lastSlotAddress = null;

    @Attribute(required = false)
    protected int partPitch = 4;

    protected boolean initialized = false;

    // For costly or hard to get parts: measure the pick point with the camera after every feed.
    @Attribute(required = false)
    protected boolean checkEveryPick = false;

    @Element(required = false)
    private Location offset;

    private static PhotonBusInterface photonBus;

    @Attribute(required = false)
    protected boolean moveWhileFeeding = true;

    @Attribute(required = false)
    protected int tapeWidth = 8;

    // Last feeder record read from the feeder, null until read. Not saved: the feeder holds it.
    protected PhotonFeederRecord record = null;

    // Set when a background record read got no answer, so job starts don't keep waiting on
    // feeders whose firmware has no feeder record. An explicit read clears it.
    protected boolean recordUnsupported = false;

    // The feeder's low-parts warning setting (see PhotonFeederRecord), null until read.
    protected Integer lowPartsSetting = null;

    // How often waitUntilSaved asks. The feeder saves only once the bus has been quiet for
    // 150 ms, so this leaves it room.
    static final long SAVE_POLL_MS = 400;
    // The job processor prepares a feeder once for every placement it serves, so at job start
    // the record is only read again if this long has passed since the last read
    static final long RECORD_FRESH_MS = 60_000;
    private transient long recordReadAtMs = Long.MIN_VALUE / 2;

    // Set while a feed started ahead of its pick has not been used yet. Saved, so a part fed
    // after the last pick before OpenPnP closed is picked next time rather than fed past.
    @Attribute(required = false)
    protected boolean fedAhead = false;
    // When the feed started ahead should be done by; 0 after a restart
    private transient long fedAheadUntilNanos = 0;

    // A failed pick's recheck corrects the slot only when the pick point moved at least this
    // much, and at most half a part pitch: further than that, vision may have found the
    // neighbouring pocket.
    static final double RECHECK_TOLERANCE_MM = 0.2;

    // Drive vendor commands (feeder firmware docs: ratchet 0x20-0x2A, stock feed scale 0x2B-0x2D)
    static final int CMD_DRIVE_STATUS = 0x20;
    static final int CMD_CALIBRATE_RATCHET = 0x24;
    static final int CMD_SAVE_DRIVE_PARAMETERS = 0x2A;
    static final int CMD_FEED_SCALE = 0x2B;
    static final int CMD_CORRECT_FEED_SCALE = 0x2D;
    static final long CALIBRATE_TIMEOUT_MS = 20_000;
    static final long BUSY_POLL_MS = 200;
    // The feeder saves a new feed scale to flash between feeds; give it time before reading back.
    static final long FEED_SCALE_SAVE_MS = 700;

    private static final String[] RATCHET_ERRORS = {
            "no error",
            "the pawl never met a ratchet face",
            "the carrier jammed",
            "it never reached the park stop",
            "it timed out",
            "invalid length",
            "the motor did not turn",
    };

    // Finds the pick point when a pick failed. Null uses the head's default camera.
    static PhotonAutoSetup.PickPointFinder recheckFinder = null;

    public PhotonFeeder() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationLoaded(Configuration configuration) {
                photonProperties = new PhotonProperties(configuration.getMachine());

                // Ensure actuators are added to the machine when it has PhotonFeeders
                getDataActuator();
                populatePhotonBus();
            }
        });
    }

    public static void setBus(PhotonBusInterface bus) {
        photonBus = bus;
    }

    public static PhotonBusInterface getBus() {
        populatePhotonBus();
        return photonBus;
    }

    private static void populatePhotonBus() {
        if (photonBus != null) {
            return;
        }

        photonBus = new PhotonBus(0, getDataActuator());
    }

    /**
     * Called by the job right after each pick, before it checks the vacuum. If the part is not on
     * the nozzle, the pick point is measured again so the job's retry picks in the right place.
     */
    @Override
    public void postPick(Nozzle nozzle) throws Exception {
        super.postPick(nozzle);
        recheckIfThePickFailed(nozzle);
        // After the recheck, which needs the tape standing still
        if (photonProperties != null && photonProperties.getFeedAfterPick()) {
            startFeedAhead();
        }
    }

    private void recheckIfThePickFailed(Nozzle nozzle) throws Exception {
        if (nozzle == null || photonProperties == null || !photonProperties.getRecheckAfterFailedPick()
                || hasPlaceholderPart() || !nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)) {
            return;
        }
        if (nozzle.isPartOn()) {
            return;
        }

        Logger.info("Pick from Photon feeder {} failed, measuring its pick point again.", getName());
        recheckPickLocation();
    }

    /**
     * Measures the pick point with the camera and moves the slot location by the difference, if
     * it is off by more than the tolerance. Never throws: a failed check leaves the slot as it
     * was. Returns the correction applied in mm, or 0 if none.
     */
    public double recheckPickLocation() {
        try {
            Location estimate = getPickLocation();
            PhotonAutoSetup.PickPointFinder finder = recheckFinder;
            if (finder == null) {
                Camera camera = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
                finder = new PhotonSprocketFinder(camera, photonProperties.getAutoSetupPipeline(), 0);
            }
            Location measured = finder.find(this, estimate).convertToUnits(estimate.getUnits());

            double dx = measured.getX() - estimate.getX();
            double dy = measured.getY() - estimate.getY();
            Location shift = new Location(estimate.getUnits(), dx, dy, 0, 0).convertToUnits(LengthUnit.Millimeters);
            double distanceMm = Math.hypot(shift.getX(), shift.getY());

            double pitchMm = record != null && record.pitchTenthsMm > 0 ? record.pitchTenthsMm / 10.0 : partPitch;
            if (distanceMm < RECHECK_TOLERANCE_MM) {
                Logger.info("Photon feeder {} pick point is within {} mm, not changed.", getName(), RECHECK_TOLERANCE_MM);
                return 0;
            }
            if (distanceMm > pitchMm / 2) {
                Logger.warn("Photon feeder {} pick point measured {} mm away, more than half a pitch. Not changed.",
                        getName(), String.format("%.2f", distanceMm));
                return 0;
            }

            PhotonFeederSlots.Slot slot = getSlot();
            Location slotLocation = slot.getLocation();
            Location slotShift = new Location(estimate.getUnits(), dx, dy, 0, 0).convertToUnits(slotLocation.getUnits());
            slot.setLocation(slotLocation.add(slotShift));
            Logger.info("Photon feeder {} pick point corrected by {} mm.", getName(), String.format("%.2f", distanceMm));
            return distanceMm;
        }
        catch (Exception e) {
            Logger.warn(e, "Could not recheck the pick point of Photon feeder {}", getName());
            return 0;
        }
    }

    @Override
    public Location getPickLocation() throws Exception {
        verifyFeederLocationIsFullyConfigured();

        return offset.offsetWithRotationFrom(getSlot().getLocation());
    }

    private void verifyFeederLocationIsFullyConfigured() throws NoSlotAddressException,
            UnconfiguredSlotException, FeederHasNoLocationOffsetException {
        if (slotAddress == null) {
            throw new NoSlotAddressException(
                    String.format("Photon Feeder with address %s has no address. Is it inserted?", hardwareId)
            );
        }

        if (getSlot().getLocation() == null) {
            throw new UnconfiguredSlotException(
                    String.format("The slot at address %s has no location configured.", slotAddress)
            );
        }

        if (offset == null) {
            throw new FeederHasNoLocationOffsetException(
                    String.format("Photon Feeder with address %s has no location offset.", hardwareId)
            );
        }
    }

    public void setOffset(Location offsets) {
        Object oldValue = this.offset;
        this.offset = offsets;
        firePropertyChange("offsets", oldValue, offsets);
    }

    public Location getOffset() {
        return offset;
    }

    public boolean getMoveWhileFeeding() {
        return moveWhileFeeding;
    }

    public void setMoveWhileFeeding(boolean moveWhileFeeding) {
        this.moveWhileFeeding = moveWhileFeeding;
    }

    @Override
    public void findIssues(Solutions solutions) {
        super.findIssues(solutions);

        if (hardwareId == null) {
            return;
        }

        if (slotAddress != null && getSlot().getLocation() == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder slot has no configured location",
                    "Select the feeder in the Feeders tab and make sure the slot has a set location",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }

        if (offset == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder has no configured offset",
                    "Select the feeder in the Feeders tab and make sure the feeder has an offset location from the slot",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }
    }

    @Override
    public void prepareForJob(boolean visit) throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();

            initializeIfNeeded();

            if (initialized) {
                verifyFeederLocationIsFullyConfigured();

                if (System.currentTimeMillis() - recordReadAtMs >= RECORD_FRESH_MS) {
                    readRecordQuietly();
                }
                if (lowPartsSetting == null && record != null && record.loaded) {
                    readLowPartsQuietly();
                }

                super.prepareForJob(visit);
                return;
            }
        }

        throw new Exception("Failed to find and initialize the feeder");
    }

    public void findSlotAddress() throws Exception {
        findSlotAddress(true);
    }

    private void findSlotAddress(boolean force) throws Exception {
        if (slotAddress != null && !force) {
            return;
        }

        GetFeederAddress getFeederAddress = new GetFeederAddress(hardwareId);
        GetFeederAddress.Response response = getFeederAddress.send(photonBus);

        if (response == null) {
            setSlotAddress(null);
            return;
        }
        setSlotAddress(response.fromAddress);
    }

    /**
     * Blinks the feeder's LED so it can be found on the machine. The command goes out by
     * hardware ID, so it works even when the slot is not known, and the reply tells us the slot.
     */
    public void identify() throws Exception {
        if (hardwareId == null) {
            throw new Exception("The feeder has no hardware ID. Search for feeders first.");
        }

        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            IdentifyFeeder.Response response = new IdentifyFeeder(hardwareId).send(photonBus);

            if (response != null && response.valid && response.error == ErrorTypes.NONE) {
                if (slotAddress == null || slotAddress != response.fromAddress) {
                    setSlotAddress(response.fromAddress);
                }
                return;
            }
        }

        throw new Exception(String.format("Photon feeder %s did not answer. Is it inserted?", getName()));
    }

    private void findSlotAddressIfNeeded() throws Exception {
        findSlotAddress(false);
    }

    public void initializeIfNeeded() throws Exception {
        if (initialized || slotAddress == null) {
            return;
        }

        InitializeFeeder initializeFeeder = new InitializeFeeder(slotAddress, hardwareId);
        InitializeFeeder.Response response = initializeFeeder.send(photonBus);

        if (response == null) {
            slotAddress = null;
        } else if (response.error == ErrorTypes.WRONG_FEEDER_UUID) {
            PhotonFeeder otherFeeder = findByHardwareId(response.uuid);

            // If we don't know about that feeder, let's go ahead and create it
            if (otherFeeder == null) {
                otherFeeder = new PhotonFeeder();
                otherFeeder.setHardwareId(response.uuid);
                otherFeeder.usePlaceholderPartIfNone();
                Configuration.get().getMachine().addFeeder(otherFeeder);
            }

            // This other feeder is in the slot we thought we were
            otherFeeder.setSlotAddress(response.fromAddress);
        } else {
            initialized = true;
        }
    }

    static Actuator getDataActuator() {
        Machine machine = Configuration.get().getMachine();

        Actuator actuator = machine.getActuatorByName(ACTUATOR_DATA_NAME);

        if (actuator == null) {
            actuator = createDefaultActuator(machine);
        }

        return actuator;
    }

    private static Actuator createDefaultActuator(Machine machine) {
        Actuator actuator;
        actuator = new ReferenceActuator();
        actuator.setName(ACTUATOR_DATA_NAME);

        for (Driver driver : machine.getDrivers()) {
            if(! (driver instanceof GcodeDriver)) {
                continue;
            }
            GcodeDriver gcodeDriver = (GcodeDriver) driver;
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_COMMAND, "M485 {Value}");
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_REGEX, "rs485-reply: (?<Value>.*)");
            break;  // Only set this on 1 GCodeDriver
        }

        try {
            machine.addActuator(actuator);
        } catch (Exception exception) {
            exception.printStackTrace(); // TODO Probably need to log this, figure out why it can happen first
        }
        return actuator;
    }

    /**
     * Starts each Photon feeder in the list advancing by one part without waiting for it, so a
     * cycle's feeders all move at once instead of one after another. The job calls this with the
     * feeders it is about to pick from; the next feed() of each then only waits for the move to
     * finish. Never throws: a feeder that can't start ahead feeds as usual later.
     */
    public static void feedAhead(Collection<? extends Feeder> feeders) {
        for (Feeder feeder : new LinkedHashSet<>(feeders)) {
            if (feeder instanceof PhotonFeeder) {
                ((PhotonFeeder) feeder).startFeedAhead();
            }
        }
    }

    void startFeedAhead() {
        if (fedAhead || !isEnabled() || getFeedOptions() != FeedOptions.Normal) {
            return;
        }
        try {
            findSlotAddressIfNeeded();
            initializeIfNeeded();
            if (!initialized) {
                return;
            }
            verifyFeederLocationIsFullyConfigured();

            MoveFeedForward.Response response = new MoveFeedForward(slotAddress, partPitch * 10).send(photonBus);
            if (response == null) {
                return;
            }
            if (response.error == ErrorTypes.UNINITIALIZED_FEEDER) {
                slotAddress = null;
                initialized = false;
                return;
            }
            fedAhead = true;
            fedAheadUntilNanos = System.nanoTime() + Duration.ofMillis(response.expectedTimeToFeed).toNanos() * 3;
        }
        catch (Exception e) {
            Logger.debug(e, "Photon feeder {} could not start feeding ahead", getName());
        }
    }

    private void feed(Nozzle nozzle, int distance_mm) throws Exception {
        if (fedAhead) {
            // The tape already moved, or is moving, by one part: use that instead of feeding again
            fedAhead = false;
            if (fedAheadUntilNanos != 0) {
                waitForFeed(nozzle, partPitch, fedAheadUntilNanos);
            }
            // else it was fed before OpenPnP restarted: long done, and in the record read since
            if (distance_mm == partPitch) {
                return;
            }
        }
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();
            initializeIfNeeded();

            if (!initialized) {
                continue;
            }

            verifyFeederLocationIsFullyConfigured();

            MoveFeedForward moveFeedForward = new MoveFeedForward(slotAddress, distance_mm * 10);
            MoveFeedForward.Response moveFeedForwardResponse = moveFeedForward.send(photonBus);

            if (moveFeedForwardResponse == null) {
                slotAddress = null;
                initialized = false;
                throw new FeedFailureException("Feed command timed out");
            } else if (moveFeedForwardResponse.error == ErrorTypes.UNINITIALIZED_FEEDER) {
                slotAddress = null;
                initialized = false;
                continue;  // We'll initialize it on a retry
            }

            // The feeder gives us expectedTimeToFeed, but it is way too conservative.
            // Use expectedTimeToFeed to bound how long we will wait,
            // but use polling to check the status of the feed.
            Duration expectedFeedDuration = Duration.ofMillis(moveFeedForwardResponse.expectedTimeToFeed);
            waitForFeed(nozzle, distance_mm, System.nanoTime() + expectedFeedDuration.toNanos() * 3);
            return;
        }

        throw new FeedFailureException("Failed to feed for an unknown reason. Is the feeder inserted?");
    }

    private void waitForFeed(Nozzle nozzle, int distance_mm, long endTimeNanos) throws Exception {
        for (int j = 0; j <= photonProperties.getFeederCommunicationMaxRetry() || System.nanoTime() <= endTimeNanos; j++) {
            Thread.sleep(50); // MAGIC: this feels like a good number, there is no particular reason it is this way.

            if (j == 0 && nozzle != null && Configuration.get().getMachine().isHomed() && getMoveWhileFeeding()) {
                MovableUtils.moveToLocationAtSafeZ(nozzle, getPickLocation().deriveLengths(null, null, nozzle.getEffectiveSafeZ(), null));
            }

            MoveFeedStatus moveFeedStatus = new MoveFeedStatus(slotAddress);
            MoveFeedStatus.Response moveFeedStatusResponse = moveFeedStatus.send(photonBus);

            if (moveFeedStatusResponse == null) {
                continue; // Timeout. retry after delay.
            }

            if (moveFeedStatusResponse.error == ErrorTypes.NONE) {
                if (record != null) {
                    // Count it here the way the feeder does, rather than asking after every feed
                    setRecord(record.afterFeed(distance_mm * 10L));
                }
                return;
            } else if (moveFeedStatusResponse.error == ErrorTypes.COULD_NOT_REACH) {
                throw new FeedFailureException("Feeder could not reach its destination.");
            }
        }

        throw new FeedFailureException("Feeder timed out when we requested a feed status update.");
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        switch (getFeedOptions()) {
        case Normal:
            break;
        case SkipNext:
            setFeedOptions(FeedOptions.Normal);
            checkPickPointIfAsked();
            return;
        case Disable:
            return;
        }

        feed(nozzle, partPitch);
        checkPickPointIfAsked();
    }

    private void checkPickPointIfAsked() {
        if (checkEveryPick && !hasPlaceholderPart()) {
            recheckPickLocation();
        }
    }

    public boolean isCheckEveryPick() {
        return checkEveryPick;
    }

    public void setCheckEveryPick(boolean checkEveryPick) {
        boolean oldValue = this.checkEveryPick;
        this.checkEveryPick = checkEveryPick;
        firePropertyChange("checkEveryPick", oldValue, checkEveryPick);
    }

    public void feedOneMm() throws Exception {
        feed(null, 1);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        String classSimpleName = getClass().getSimpleName();
        if (hardwareId == null) {
            return String.format("Unconfigured %s", classSimpleName);
        } else {
            return String.format("%s %s", classSimpleName, getName());
        }
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        List<PropertySheet> sheets = new ArrayList<>();

        if (hardwareId != null) {
            sheets.add(new FeederPropertySheet(this));
        }

        sheets.add(new GlobalConfigPropertySheet());

        return sheets.toArray(new PropertySheet[0]);
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return new PropertySheetHolder[0];
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[0];
    }

    @Override
    public Wizard getConfigurationWizard() {
        return null;
    }

    @Override
    public String getName() {
        if (hardwareId == null) {
            return String.format("Unconfigured %s", getClass().getSimpleName());
        }

        StringBuilder result = new StringBuilder();
        result.append(name);
        result.append(" (Slot: ");

        if (slotAddress == null) {
            result.append("None");
        } else {
            result.append(slotAddress);
        }

        result.append(")");

        return result.toString();
    }

    private static final Pattern SLOT_IN_NAME = Pattern.compile("\\(Slot: (\\d+)\\)");

    /**
     * Orders feeder names so Photon feeders come first by slot number ("... (Slot: 7)" before
     * "... (Slot: 12)"), then every other feeder by name, ignoring case.
     */
    public static int compareNames(String a, String b) {
        Integer slotA = slotInName(a);
        Integer slotB = slotInName(b);
        if (slotA != null && slotB != null) {
            int bySlot = Integer.compare(slotA, slotB);
            return bySlot != 0 ? bySlot : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        }
        if (slotA != null || slotB != null) {
            return slotA != null ? -1 : 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a == null ? "" : a, b == null ? "" : b);
    }

    private static Integer slotInName(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = SLOT_IN_NAME.matcher(name);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    @Override
    public void setName(String name) {
        Matcher matcher = Pattern.compile("(\\(Slot: [\\w+]+\\))").matcher(name);
        while (matcher.find()) {
            name = name.replace(matcher.group(), "");
        }

        name = name.trim();

        super.setName(name);
    }

    /**
     * The PhotonFeeder assumes you have a physical slot that is numbered 1 - 254. That
     * value is also used in the protocol as the address of the feeder once the feeder
     * is initialized.
     *
     * @return The slot address of this feeder or null if it doesn't have one.
     */
    public Integer getSlotAddress() {
        return slotAddress;
    }

    public PhotonFeederSlots.Slot getSlot() {
        if (slotAddress == null) {
            return null;
        }

        return photonProperties.getFeederSlots().getSlot(slotAddress);
    }

    public void setSlotAddress(Integer slotAddress) {
        PhotonFeederSlots.Slot oldSlot = getSlot();
        Integer oldValue = this.slotAddress;
        String oldName = this.getName();

        if (slotAddress != null) {
            // Find any other photon feeders and if they have this slot address, set their address to null
            PhotonFeeder otherFeeder = findBySlotAddress(slotAddress);
            if (otherFeeder != null) {
                otherFeeder.slotAddress = null;
                otherFeeder.initialized = false;
            }
        }

        this.slotAddress = slotAddress;
        if (slotAddress != null && slotAddress >= 1 && slotAddress <= PhotonSlotRepair.MAX_SLOT_ADDRESS) {
            lastSlotAddress = slotAddress;
        }

        firePropertyChange("slotAddress", oldValue, slotAddress);
        firePropertyChange("slot", oldSlot, getSlot());
        firePropertyChange("name", oldName, getName());
    }

    /**
     * The last slot this feeder was found in, even across restarts. Null if never found.
     */
    public Integer getLastSlotAddress() {
        return lastSlotAddress;
    }

    void markUninitialized() {
        initialized = false;
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        String oldValue = this.hardwareId;
        this.hardwareId = hardwareId;

        if (getClass().getSimpleName().equals(name)) {
            name = hardwareId;
        }

        firePropertyChange("hardwareId", oldValue, hardwareId);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() &&
                hardwareId != null &&
                partId != null &&
                slotAddress != null &&
                offset != null &&
                getSlot().getLocation() != null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setPartPitch(int partPitch) {
        this.partPitch = partPitch;
    }

    public int getPartPitch() {
        return partPitch;
    }

    public int getTapeWidth() {
        return tapeWidth;
    }

    public void setTapeWidth(int tapeWidth) {
        int oldValue = this.tapeWidth;
        this.tapeWidth = tapeWidth;
        firePropertyChange("tapeWidth", oldValue, tapeWidth);
    }

    /**
     * @return The feeder record as last read from the feeder (or counted since), null if it was
     * never read.
     */
    public PhotonFeederRecord getRecord() {
        return record;
    }

    protected void setRecord(PhotonFeederRecord record) {
        PhotonFeederRecord oldValue = this.record;
        String oldPartsLeft = getRecordPartsLeft();
        String oldPartId = getRecordPartId();
        String oldQuantity = getRecordQuantity();
        String oldLowParts = getRecordLowParts();
        PhotonFeederRecord.Level oldLevel = getLowPartsLevel();

        this.record = record;
        if (fedAhead && oldValue != null && record != null && !Objects.equals(oldValue.partId, record.partId)) {
            // A new reel: the part fed ahead was on the old one
            fedAhead = false;
        }

        firePropertyChange("record", oldValue, record);
        firePropertyChange("recordPartsLeft", oldPartsLeft, getRecordPartsLeft());
        firePropertyChange("recordPartId", oldPartId, getRecordPartId());
        firePropertyChange("recordQuantity", oldQuantity, getRecordQuantity());
        firePropertyChange("recordLowParts", oldLowParts, getRecordLowParts());
        firePropertyChange("lowPartsLevel", oldLevel, getLowPartsLevel());
        warnIfRunningLow(oldValue, oldLevel);
    }

    public String getRecordPartsLeft() {
        if (record == null) {
            return "Not read";
        }
        if (!record.loaded) {
            return "No reel recorded";
        }
        return String.format("~%d", record.partsLeft);
    }

    /**
     * Whether the feeder is running low, by its low-parts warning. OK until the setting and the
     * record have been read.
     */
    public PhotonFeederRecord.Level getLowPartsLevel() {
        if (record == null || lowPartsSetting == null) {
            return PhotonFeederRecord.Level.OK;
        }
        return record.lowPartsLevel(lowPartsSetting);
    }

    public Integer getLowPartsSetting() {
        return lowPartsSetting;
    }

    /**
     * The low-parts warning in words, for the feeder setup.
     */
    public String getRecordLowParts() {
        if (lowPartsSetting == null) {
            return "";
        }
        if (lowPartsSetting == PhotonFeederRecord.LOW_PARTS_OFF) {
            return "Off";
        }

        String setting = lowPartsSetting == PhotonFeederRecord.LOW_PARTS_DEFAULT
                ? "10 % of the reel"
                : String.format("%d parts", lowPartsSetting);
        if (record == null || !record.loaded) {
            return String.format("At %s", setting);
        }

        String text = String.format("At %d left (%s)", record.lowPartsThreshold(lowPartsSetting), setting);
        switch (record.lowPartsLevel(lowPartsSetting)) {
            case LOW:
                return text + ". Running low!";
            case EMPTY:
                return text + ". Empty!";
            default:
                return text;
        }
    }

    protected void setLowPartsSettingValue(Integer setting) {
        String oldLowParts = getRecordLowParts();
        PhotonFeederRecord.Level oldLevel = getLowPartsLevel();
        Integer oldValue = lowPartsSetting;

        lowPartsSetting = setting;

        firePropertyChange("lowPartsSetting", oldValue, setting);
        firePropertyChange("recordLowParts", oldLowParts, getRecordLowParts());
        firePropertyChange("lowPartsLevel", oldLevel, getLowPartsLevel());
    }

    private void warnIfRunningLow(PhotonFeederRecord oldRecord, PhotonFeederRecord.Level oldLevel) {
        PhotonFeederRecord.Level level = getLowPartsLevel();
        if (oldRecord == null || level == oldLevel || level == PhotonFeederRecord.Level.OK) {
            return;
        }
        if (level == PhotonFeederRecord.Level.EMPTY) {
            Logger.warn("Photon feeder {} ({}) is out of parts by its count.", getName(), record.partId);
        }
        else {
            Logger.warn("Photon feeder {} ({}) is running low: about {} parts left.", getName(), record.partId, record.partsLeft);
        }
    }

    public String getRecordPartId() {
        if (record == null || !record.loaded) {
            return "";
        }
        return record.partId;
    }

    public String getRecordQuantity() {
        if (record == null || !record.loaded) {
            return "";
        }
        return String.format("%d", record.quantityAtLoad);
    }

    /**
     * Reads the feeder record (part ID, pitch, parts left) from the feeder. If the feeder has a
     * part recorded and no part is assigned here yet, the recorded part is assigned, along with
     * its pitch and tape width.
     */
    public PhotonFeederRecord readRecord() throws Exception {
        return readRecord(photonProperties.getFeederCommunicationMaxRetry() + 1);
    }

    private PhotonFeederRecord readRecord(int attempts) throws Exception {
        int[] status = sendVendorOptions(PhotonFeederRecord.statusRequest(), attempts);

        String partId = null;
        if (status[0] != 0) {
            // Read 12 byte chunks until one ends the ID. Older firmware answers zeros past 24.
            List<int[]> chunks = new ArrayList<>();
            for (int offset : PhotonFeederRecord.readOffsets()) {
                int[] chunk = sendVendorOptions(PhotonFeederRecord.readIdRequest(offset), attempts);
                chunks.add(chunk);
                if (PhotonFeederRecord.endsPartId(chunk, offset)) {
                    break;
                }
            }
            partId = PhotonFeederRecord.partIdFromChunks(chunks.toArray(new int[0][]));
        }

        PhotonFeederRecord newRecord = PhotonFeederRecord.fromStatus(status, partId);
        if (newRecord.loaded) {
            newRecord = newRecord.withRotation(readRecordRotation());
        }
        recordUnsupported = false;
        recordReadAtMs = System.currentTimeMillis();
        setRecord(newRecord);
        adoptRecordedPart(newRecord);
        adoptRecordedRotation(newRecord);

        return newRecord;
    }

    /**
     * The rotation in tape (0x1C), or null if none is stored or the firmware doesn't keep one.
     * Firmware from before the command may not answer it, so it gets one try.
     */
    private Integer readRecordRotation() {
        try {
            return PhotonFeederRecord.rotationFromReply(sendVendorOptions(PhotonFeederRecord.rotationRequest(), 1));
        } catch (Exception e) {
            Logger.debug("Photon feeder {} did not return a rotation in tape: {}", hardwareId, e.getMessage());
            return null;
        }
    }

    /**
     * The rotation in tape follows the reel, so a rotation the feeder holds for the part assigned
     * here becomes the offset's rotation.
     */
    private void adoptRecordedRotation(PhotonFeederRecord record) {
        Double degrees = record.getRotationDegrees();
        Part part = getPart();
        if (degrees == null || offset == null || part == null || !record.matchesPartId(part.getId())) {
            return;
        }
        if (PhotonFeederRecord.rotationTenths(offset.getRotation()) != record.rotationTenthsDeg) {
            Logger.info("Photon feeder {} holds rotation in tape {}, changing it from {}.",
                    getName(), degrees, offset.getRotation());
            setOffset(offset.derive(null, null, null, degrees));
        }
    }

    /**
     * The rotation in tape to store with the reel: the offset's rotation, or null without an offset.
     */
    private Integer rotationToStore() {
        return offset == null ? null : PhotonFeederRecord.rotationTenths(offset.getRotation());
    }

    /**
     * True if the feeder holds this reel but a different (or no) rotation in tape than the offset.
     */
    public boolean recordRotationDiffers() {
        Part part = getPart();
        Integer rotation = rotationToStore();
        return record != null && record.loaded && !recordRotationUnsupported && rotation != null
                && part != null && record.matchesPartId(part.getId())
                && !rotation.equals(record.rotationTenthsDeg);
    }

    /**
     * Stores the offset's rotation in the feeder as the reel's rotation in tape (0x1D).
     */
    public void saveRotationToRecord() throws Exception {
        Integer rotation = rotationToStore();
        if (rotation == null) {
            return;
        }
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        int[] reply = sendVendorOptions(PhotonFeederRecord.setRotationRequest(rotation), attempts);
        if (reply[0] != 1) {
            // Older firmware answers zeros: it can't keep the rotation, so stop asking it to
            recordRotationUnsupported = true;
            Logger.info("Photon feeder {} did not store the rotation in tape. Its firmware may be too old.", getName());
            return;
        }
        readRecord(attempts);
    }

    // Set when the feeder refused to store a rotation in tape, so it isn't asked again
    private transient boolean recordRotationUnsupported = false;

    /**
     * Reads the record once, without retries, and never throws. Used at job start and after a
     * feeder search, where a feeder without the record must not hold anything up.
     */
    public void readRecordQuietly() {
        if (recordUnsupported) {
            return;
        }

        try {
            readRecord(1);
        } catch (Exception e) {
            recordUnsupported = true;
            Logger.debug("Photon feeder {} did not return a feeder record: {}", hardwareId, e.getMessage());
        }
    }

    /**
     * The part a feeder record names. The feeder keeps only the first 64 characters of a part ID
     * (24 with older firmware), so if no part has exactly that ID, the one part whose ID starts
     * the same way is used. Returns null, with a warning, if there is none or more than one.
     */
    private Part findRecordedPart(String recordedPartId) {
        Part exact = Configuration.get().getPart(recordedPartId);
        if (exact != null) {
            return exact;
        }

        List<Part> matches = new ArrayList<>();
        for (Part part : Configuration.get().getParts()) {
            if (part.getId().length() > recordedPartId.length()
                    && PhotonFeederRecord.truncatePartId(part.getId(), recordedPartId.length()).equals(recordedPartId)) {
                matches.add(part);
            }
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            Logger.warn("Photon feeder {} holds part {}, which is not in the parts list.", getName(), recordedPartId);
        }
        else {
            Logger.warn("Photon feeder {} holds part {}, which matches {} parts once cut to {} characters. Assign it by hand.",
                    getName(), recordedPartId, matches.size(), recordedPartId.length());
        }
        return null;
    }

    private void adoptRecordedPart(PhotonFeederRecord record) {
        if (!record.loaded || record.partId == null || record.partId.isEmpty()) {
            return;
        }

        Part currentPart = getPart();
        if (currentPart == null || hasPlaceholderPart()) {
            Part recordedPart = findRecordedPart(record.partId);
            if (recordedPart == null) {
                return;
            }

            Logger.info("Photon feeder {} holds part {}, assigning it.", getName(), record.partId);
            setPart(recordedPart);
            if (record.pitchTenthsMm > 0 && record.pitchTenthsMm % 10 == 0) {
                setPartPitch(record.pitchTenthsMm / 10);
            }
            if (record.tapeWidthMm > 0) {
                setTapeWidth(record.tapeWidthMm);
            }
        } else if (!record.matchesPartId(currentPart.getId())) {
            Logger.warn("Photon feeder {} is assigned part {} but the feeder holds part {}.",
                    getName(), currentPart.getId(), record.partId);
        }
    }

    /**
     * Writes a newly loaded reel to the feeder: the assigned part, tape width, part pitch and
     * the number of parts on the tape. This restarts the feeder's count.
     */
    public void loadRecord(long quantity) throws Exception {
        Part part = getPart();
        if (part == null || hasPlaceholderPart()) {
            throw new Exception("Assign a part to the feeder before loading a reel.");
        }
        if (partPitch <= 0) {
            throw new Exception("Set the part pitch before loading a reel.");
        }
        if (quantity < 0 || quantity > 0xFFFFFFFFL) {
            throw new Exception("The quantity must be a positive number.");
        }

        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        for (int i = 0; i < attempts; i++) {
            stagePartId(part.getId(), attempts);
            expectOk(sendVendorOptions(PhotonFeederRecord.loadRequest(tapeWidth, partPitch * 10, quantity, rotationToStore()), attempts), "load the reel");

            // The feeder forgets the staged ID if it restarts between staging and loading, so check it
            if (readRecord(attempts).matchesPartId(part.getId())) {
                return;
            }
            Logger.warn("Photon feeder {} did not store part ID {}, retrying.", getName(), part.getId());
        }

        throw new Exception(String.format("Photon feeder %s did not store the part ID.", getName()));
    }

    /**
     * Stages the part ID in 12 byte chunks. Firmware before 2026-10-20 refuses chunks from 24
     * on and keeps 24 characters; that's fine unless another part starts with the same 24.
     */
    private void stagePartId(String partId, int attempts) throws Exception {
        for (int offset : PhotonFeederRecord.stageOffsets(partId)) {
            int[] reply = sendVendorOptions(PhotonFeederRecord.stageIdRequest(partId, offset), attempts);
            if (offset >= PhotonFeederRecord.OLD_PART_ID_LENGTH && reply.length > 0 && reply[0] == 0) {
                List<Part> clashes = PhotonReelLabel.truncationClash(new ArrayList<>(Configuration.get().getParts()),
                        partId, PhotonFeederRecord.OLD_PART_ID_LENGTH);
                if (!clashes.isEmpty()) {
                    throw new Exception(String.format("Photon feeder %s has older firmware that keeps 24 characters "
                            + "of a part ID, and %s starts the same as %s. Update its firmware or shorten an ID.",
                            getName(), partId, clashes.get(0).getId()));
                }
                Logger.info("Photon feeder {} has older firmware and keeps 24 characters of part ID {}.",
                        getName(), partId);
                return;
            }
            expectOk(reply, "stage the part ID");
        }
    }

    /**
     * Corrects the feeder's count, for example after counting the parts left on the tape.
     */
    public void setRecordPartsLeft(long partsLeft) throws Exception {
        if (partsLeft < 0 || partsLeft > 0xFFFFFFFFL) {
            throw new Exception("The count must be a positive number.");
        }

        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        int[] reply = sendVendorOptions(PhotonFeederRecord.setPartsLeftRequest(partsLeft), attempts);
        if (reply[0] != 1) {
            throw new Exception("The feeder has no reel recorded. Load a reel first.");
        }
        readRecord(attempts);
    }

    /**
     * Clears the feeder record, for when the reel is taken out.
     */
    public void clearRecord() throws Exception {
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        expectOk(sendVendorOptions(PhotonFeederRecord.clearRequest(), attempts), "clear the record");
        readRecord(attempts);
    }

    /**
     * Runs a ratchet feeder's own calibration (0x24), waits for it, checks the outcome (0x20) and
     * saves the parameters to flash (0x2A). Returns the measured gap in encoder ticks.
     */
    public int calibrateRatchet() throws Exception {
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        int[] reply = sendVendorOptions(new int[]{CMD_CALIBRATE_RATCHET}, attempts);
        if (reply[0] != 1 && !isBusy()) {
            // 0 is "busy", but a retry after a lost reply also gets 0 while the first one runs
            throw new Exception(String.format(
                    "Feeder %s did not start a calibration. It is busy, or it is not a ratchet feeder.", getName()));
        }
        waitWhileBusy(CALIBRATE_TIMEOUT_MS);

        int[] status = sendVendorOptions(new int[]{CMD_DRIVE_STATUS}, attempts);
        int state = status[0] & 0x0F;
        int error = (status[0] >> 4) & 0x0F;
        if (state != 1 || error != 0) {
            throw new Exception(String.format("Calibration of feeder %s failed: %s.", getName(),
                    error < RATCHET_ERRORS.length ? RATCHET_ERRORS[error] : "error " + error));
        }
        int gap = PhotonFeederRecord.uint16(status, 1);

        if (sendVendorOptions(new int[]{CMD_SAVE_DRIVE_PARAMETERS}, attempts)[0] != 1) {
            throw new Exception(String.format("Feeder %s calibrated but could not save it to flash.", getName()));
        }
        Logger.info("Photon feeder {} ratchet calibrated, gap {} ticks, saved.", getName(), gap);
        return gap;
    }

    private boolean isBusy() throws Exception {
        MoveFeedStatus.Response response = new MoveFeedStatus(slotAddress).send(photonBus);
        return response != null && response.error == ErrorTypes.FEEDING_IN_PROGRESS;
    }

    private void waitWhileBusy(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        do {
            Thread.sleep(BUSY_POLL_MS);
            MoveFeedStatus.Response response = new MoveFeedStatus(slotAddress).send(photonBus);
            if (response != null && response.error != ErrorTypes.FEEDING_IN_PROGRESS) {
                return;
            }
        } while (System.currentTimeMillis() < end);
        throw new Exception(String.format("Feeder %s did not finish within %d s.", getName(), timeoutMs / 1000));
    }

    /**
     * The stock drive's feed scale (0x2B) in percent, or null if the feeder has none (ratchet
     * feeders and older firmware).
     */
    public Double readFeedScale() throws Exception {
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        int[] reply = sendVendorOptions(new int[]{CMD_FEED_SCALE}, attempts);
        Double scale = reply[0] == 1 ? (short) PhotonFeederRecord.uint16(reply, 1) / 1000.0 : null;
        Double oldValue = feedScale;
        feedScale = scale;
        firePropertyChange("feedScale", oldValue, scale);
        return scale;
    }

    // The feed scale as last read, in percent, null if unknown or not supported.
    protected Double feedScale = null;

    public Double getFeedScale() {
        return feedScale;
    }

    /**
     * Tells a stock feeder how far the tape really moved for a feed distance (0x2D), so it can
     * correct its feed scale. Both in micrometres. Returns the new scale in percent.
     */
    public Double correctFeedScale(long askedUm, long actualUm) throws Exception {
        int[] request = new int[9];
        request[0] = CMD_CORRECT_FEED_SCALE;
        PhotonFeederRecord.putUint32(request, 1, askedUm);
        PhotonFeederRecord.putUint32(request, 5, actualUm);
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        if (sendVendorOptions(request, attempts)[0] != 1) {
            throw new Exception(String.format("Feeder %s refused the feed correction. Is it a stock feeder?", getName()));
        }
        Thread.sleep(FEED_SCALE_SAVE_MS);
        return readFeedScale();
    }

    /**
     * Feeds the tape by a whole number of millimetres, e.g. to step it during calibration.
     */
    public void feedMillimeters(int millimeters) throws Exception {
        feed(null, millimeters);
    }

    /**
     * Reads the feeder's low-parts warning setting (0x1A).
     */
    public int readLowParts() throws Exception {
        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        int setting = PhotonFeederRecord.lowPartsSetting(
                sendVendorOptions(PhotonFeederRecord.lowPartsRequest(), attempts));
        setLowPartsSettingValue(setting);
        return setting;
    }

    /**
     * Reads the low-parts setting once, without retries, and never throws.
     */
    public void readLowPartsQuietly() {
        if (recordUnsupported) {
            return;
        }

        try {
            int setting = PhotonFeederRecord.lowPartsSetting(
                    sendVendorOptions(PhotonFeederRecord.lowPartsRequest(), 1));
            setLowPartsSettingValue(setting);
        } catch (Exception e) {
            Logger.debug("Photon feeder {} did not return its low-parts setting: {}", hardwareId, e.getMessage());
        }
    }

    /**
     * Sets when the feeder warns that it is running low: LOW_PARTS_DEFAULT for 10 % of the
     * reel, LOW_PARTS_OFF for never, or a number of parts left.
     */
    public void setLowParts(int setting) throws Exception {
        if (setting < 0 || setting > PhotonFeederRecord.LOW_PARTS_DEFAULT) {
            throw new Exception(String.format("The low-parts warning must be at most %d parts.",
                    PhotonFeederRecord.LOW_PARTS_MAX_COUNT));
        }

        int attempts = photonProperties.getFeederCommunicationMaxRetry() + 1;
        expectOk(sendVendorOptions(PhotonFeederRecord.setLowPartsRequest(setting), attempts), "set the low-parts warning");
        readLowParts();
    }

    /**
     * Waits until the feeder has written its record to flash, so that power can be removed
     * without losing a change just made. Returns false if it has not confirmed by the timeout.
     */
    public boolean waitUntilSaved(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Thread.sleep(SAVE_POLL_MS);
            try {
                int[] status = sendVendorOptions(PhotonFeederRecord.statusRequest(), 1);
                String partId = record == null ? null : record.partId;
                PhotonFeederRecord saved = PhotonFeederRecord.fromStatus(status, status[0] != 0 ? partId : null);
                if (!saved.unsaved) {
                    setRecord(saved);
                    return true;
                }
            } catch (Exception e) {
                // A frame lost while the feeder writes its flash. Ask again.
                Logger.debug("Photon feeder {} save check: {}", getName(), e.getMessage());
            }
            if (System.currentTimeMillis() >= end) {
                return false;
            }
        }
    }

    private void expectOk(int[] reply, String what) throws Exception {
        if (reply[0] != 1) {
            throw new Exception(String.format(
                    "Photon feeder %s could not %s. Does its firmware support the feeder record?", getName(), what));
        }
    }

    int[] sendVendorOptions(int[] request, int attempts) throws Exception {
        for (int i = 0; i < attempts; i++) {
            findSlotAddressIfNeeded();
            initializeIfNeeded();

            if (!initialized) {
                continue;
            }

            VendorOptions.Response response = new VendorOptions(slotAddress, request).send(photonBus);

            if (response == null) {
                continue; // Timeout, e.g. a frame lost while the feeder writes its flash. Retry.
            } else if (response.error == ErrorTypes.UNINITIALIZED_FEEDER) {
                initialized = false;
                continue; // We'll initialize it on a retry
            } else if (response.valid && response.data != null) {
                return response.data;
            }
        }

        throw new Exception(String.format(
                "Photon feeder %s did not answer vendor command 0x%02X. Is it inserted?", getName(), request[0]));
    }

    /**
     * Gives the feeder the placeholder part (FIDUCIAL-HOME) if it has no part. Nothing changes if
     * the machine has no such part. A part named in the feeder's record replaces it when read.
     */
    public void usePlaceholderPartIfNone() {
        if (getPart() != null) {
            return;
        }
        Part placeholder = Configuration.get().getPart(PLACEHOLDER_PART_ID);
        if (placeholder == null) {
            Logger.debug("No {} part to give Photon feeder {}.", PLACEHOLDER_PART_ID, getName());
            return;
        }
        setPart(placeholder);
    }

    /**
     * Whether the feeder only has the placeholder part, not a real one.
     */
    public boolean hasPlaceholderPart() {
        Part part = getPart();
        return part != null && PLACEHOLDER_PART_ID.equals(part.getId());
    }

    public static PhotonFeeder findByHardwareId(String hardwareId) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            // Are we explicitly asking for feeders with null hardware ID?
            if (photonFeeder.hardwareId == null && hardwareId == null) {
                return photonFeeder;
            }

            if (photonFeeder.hardwareId != null && photonFeeder.hardwareId.equals(hardwareId)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public static PhotonFeeder findBySlotAddress(int slotAddress) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            if (photonFeeder.slotAddress != null && photonFeeder.slotAddress.equals(slotAddress)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public enum FeederSearchState {
        UNKNOWN,
        SEARCHING,
        FOUND,
        MISSING
    }

    public interface FeederSearchProgressConsumer {
        void accept(int feederAddress, FeederSearchState feederSearchState);
    }

    public static void findAllFeeders(FeederSearchProgressConsumer progressUpdate) throws Exception {
        Logger.info("Searching for Photon Feeders");
        Machine machine = Configuration.get().getMachine();
        PhotonProperties photonProperties = new PhotonProperties(machine);
        int maxFeederAddress = photonProperties.getMaxFeederAddress();
        Logger.debug("Max Photon feeder address: " + maxFeederAddress);

        List<PhotonFeeder> feedersToAdd = new ArrayList<>();

        for (int address = 1; address <= maxFeederAddress; address++) {
            Logger.debug("Querying Photon feeder address: " + address);

            if (progressUpdate != null) {
                progressUpdate.accept(address, FeederSearchState.SEARCHING);
            }

            GetFeederId getFeederId = new GetFeederId(address);
            GetFeederId.Response response = getFeederId.send(photonBus);

            if (progressUpdate != null) {
                progressUpdate.accept(address, response == null ? FeederSearchState.MISSING : FeederSearchState.FOUND);
            }

            if (response == null) {
                PhotonFeeder otherFeeder = findBySlotAddress(address);
                if (otherFeeder != null) {
                    otherFeeder.slotAddress = null;
                    otherFeeder.initialized = false;
                }
            } else {
                PhotonFeeder otherFeeder = findByHardwareId(response.uuid);
                if (otherFeeder == null) {
                    // Try to find an existing feeder without a hardware id before making a new one
                    otherFeeder = findByHardwareId(null);
                    if (otherFeeder == null) {
                        otherFeeder = new PhotonFeeder();
                        feedersToAdd.add(otherFeeder);
                    }
                }

                otherFeeder.setHardwareId(response.uuid);
                otherFeeder.setSlotAddress(address);
                otherFeeder.usePlaceholderPartIfNone();

                Logger.trace("Found feeder with hardware uuid " + otherFeeder.getHardwareId() + " at address " + otherFeeder.getSlotAddress());
            }
        }

        for (PhotonFeeder feeder : feedersToAdd) {
            Configuration.get().getMachine().addFeeder(feeder);
        }

    }

    /**
     * Reads the feeder record of every Photon feeder that has a slot, e.g. after a search.
     * Feeders that don't answer are skipped.
     */
    public static void readAllRecordsQuietly() {
        // Copy the list: initializing a feeder can add one that turned up in another's slot
        for (Feeder feeder : new ArrayList<>(Configuration.get().getMachine().getFeeders())) {
            if (feeder instanceof PhotonFeeder && ((PhotonFeeder) feeder).slotAddress != null) {
                PhotonFeeder photonFeeder = (PhotonFeeder) feeder;
                photonFeeder.recordUnsupported = false;
                photonFeeder.readRecordQuietly();
                photonFeeder.readLowPartsQuietly();
            }
        }
    }

    @Override
    public boolean canTakeBackPart() {
        return getFeedOptions() == FeedOptions.Normal;
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        setFeedOptions(FeedOptions.SkipNext);
    }

    @Override
    public boolean supportsFeedOptions() {
        return true;
    }
}
