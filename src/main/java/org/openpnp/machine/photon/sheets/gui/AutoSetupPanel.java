package org.openpnp.machine.photon.sheets.gui;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.photon.PhotonAutoSetup;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFeederSlots;
import org.openpnp.machine.photon.PhotonPickHeightProber;
import org.openpnp.machine.photon.PhotonPocketChecker;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.machine.photon.PhotonSprocketFinder;
import org.openpnp.machine.photon.PhotonVacuumLearner;
import org.openpnp.machine.reference.ContactProbeNozzle;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.FeederVisionHelper.PipelineType;
import org.openpnp.util.UiUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rails (rows of slots taught by their first and last slot) and the Auto Setup button.
 */
public class AutoSetupPanel extends JPanel {
    private final PhotonProperties photonProperties;
    private final PhotonFeederSlots feederSlots;
    private final RailsTableModel railsModel;
    private final JTable railsTable;
    private final JComboBox<PipelineType> pipelineCombo;
    private final JButton autoSetupButton;
    private final JButton learnVacuumButton;
    private List<PhotonAutoSetup.Result> lastResults = new ArrayList<>();
    private final FeederSearchProgressBar progressBar;
    private final Runnable afterSetup;

    /**
     * @param afterSetup run on the UI thread when Auto Setup finishes, e.g. to reload the feeder list
     */
    public AutoSetupPanel(PhotonProperties photonProperties, Runnable afterSetup) {
        this.photonProperties = photonProperties;
        this.feederSlots = photonProperties.getFeederSlots();
        this.afterSetup = afterSetup;

        setBorder(new TitledBorder(null, "Auto Setup", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        setLayout(new FormLayout(new ColumnSpec[]{
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),
                FormSpecs.RELATED_GAP_COLSPEC,},
                new RowSpec[]{
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        RowSpec.decode("90dlu"),
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        RowSpec.decode("10dlu"),
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,}));

        add(new JLabel("<html>Teach each rail by its first and last slot: jog the camera over the pick point "
                + "and press Capture. Z is the tape height, Rotation the pick rotation for that rail. "
                + "Fill Slots spaces the slots in between evenly. Auto Setup then finds every feeder "
                + "and measures each slot from the sprocket holes. Feeders with no reel recorded are skipped.</html>"), "2, 2");

        railsModel = new RailsTableModel();
        railsTable = new JTable(railsModel);
        railsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(railsTable), "2, 4, fill, fill");

        JPanel railButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        railButtons.add(button("Add Rail", this::addRail));
        railButtons.add(button("Remove Rail", this::removeRail));
        railButtons.add(button("Capture First", () -> capture(true)));
        railButtons.add(button("Capture Last", () -> capture(false)));
        railButtons.add(button("Fill Slots", this::fillSlots));
        add(railButtons, "2, 6");

        JPanel setupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setupRow.add(new JLabel("Hole finder"));
        pipelineCombo = new JComboBox<>(new PipelineType[]{PipelineType.CircularSymmetry, PipelineType.ColorKeyed});
        pipelineCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object text = value == PipelineType.ColorKeyed ? "Color keyed (colored feeder under the holes)"
                        : "Circular symmetry (holes contrast with the tape)";
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        pipelineCombo.setSelectedItem(photonProperties.getAutoSetupPipeline());
        pipelineCombo.addActionListener(e ->
                photonProperties.setAutoSetupPipeline((PipelineType) pipelineCombo.getSelectedItem()));
        setupRow.add(pipelineCombo);

        JCheckBox feedPastEmptyCheckBox = new JCheckBox("Feed past empty pockets",
                photonProperties.getAutoSetupFeedPastEmptyPockets());
        feedPastEmptyCheckBox.setToolTipText("<html>Every pocket under the pick point gets a score (shown in the results). "
                + "When this is on, pockets scoring below the part score are fed past, up to "
                + PhotonAutoSetup.MAX_EMPTY_POCKETS + " per feeder,<br>and the feeder's count is put back. "
                + "Leave it off until the part score has been set from the scores of known full and empty pockets.</html>");
        feedPastEmptyCheckBox.addActionListener(e ->
                photonProperties.setAutoSetupFeedPastEmptyPockets(feedPastEmptyCheckBox.isSelected()));
        setupRow.add(feedPastEmptyCheckBox);

        setupRow.add(new JLabel("Part score"));
        JTextField partScoreField = new JTextField(String.format(Locale.US, "%.1f", photonProperties.getAutoSetupPartScore()), 4);
        partScoreField.setToolTipText("A pocket scoring at least this much holds a part.");
        partScoreField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                applyPartScore(partScoreField);
            }
        });
        partScoreField.addActionListener(e -> applyPartScore(partScoreField));
        setupRow.add(partScoreField);

        autoSetupButton = button("Auto Setup All Feeders", this::autoSetup);
        setupRow.add(autoSetupButton);
        add(setupRow, "2, 8");

        JCheckBox recheckCheckBox = new JCheckBox("Recheck the pick point after a failed pick",
                photonProperties.getRecheckAfterFailedPick());
        recheckCheckBox.setToolTipText("<html>When the vacuum shows no part after a pick, the camera finds the sprocket holes again "
                + "and corrects the slot<br>if it is off by more than 0.2 mm, before the job retries the pick. "
                + "Needs part-on detection and a Pick Retry Count of at least 1.</html>");
        recheckCheckBox.addActionListener(e ->
                photonProperties.setRecheckAfterFailedPick(recheckCheckBox.isSelected()));

        JCheckBox feedAfterPickCheckBox = new JCheckBox("Feed after pick",
                photonProperties.getFeedAfterPick());
        feedAfterPickCheckBox.setToolTipText("<html>Each feeder advances to its next part right after a pick, "
                + "while the head aligns and places,<br>so the part is waiting when the head comes back. "
                + "A part fed after the last pick of a job is used by the next one.</html>");
        feedAfterPickCheckBox.addActionListener(e ->
                photonProperties.setFeedAfterPick(feedAfterPickCheckBox.isSelected()));

        JCheckBox probeHeightCheckBox = new JCheckBox("Probe pick height",
                photonProperties.getAutoSetupProbePickHeight());
        probeHeightCheckBox.setToolTipText("<html>After finding the pick point, the nozzle touches down on the part "
                + "and the slot's Z is set to where it touched.<br>Needs a Contact Probe Nozzle (Machine Setup > Nozzles) "
                + "with the Vacuum Sense method. Pockets that look empty aren't probed,<br>and a height more than "
                + PhotonAutoSetup.MAX_HEIGHT_CHANGE_MM + " mm from the taught one is reported, not used.</html>");
        probeHeightCheckBox.addActionListener(e ->
                photonProperties.setAutoSetupProbePickHeight(probeHeightCheckBox.isSelected()));

        learnVacuumButton = button("Learn Vacuum Levels...", this::learnVacuum);
        learnVacuumButton.setToolTipText("<html>Picks a part from up to " + PhotonVacuumLearner.MAX_FEEDERS
                + " feeders Auto Setup saw a part in, reads the vacuum, and puts each part back.<br>"
                + "Then suggests part-on and part-off levels for the nozzle tip, to apply or not.</html>");

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        optionsRow.add(probeHeightCheckBox);
        optionsRow.add(recheckCheckBox);
        optionsRow.add(feedAfterPickCheckBox);
        optionsRow.add(learnVacuumButton);
        add(optionsRow, "2, 12");

        progressBar = new FeederSearchProgressBar();
        progressBar.setVisible(false);
        add(progressBar, "2, 10, fill, fill");
    }

    private void applyPartScore(JTextField field) {
        try {
            double score = Double.parseDouble(field.getText().trim());
            if (score >= 0) {
                photonProperties.setAutoSetupPartScore(score);
            }
        }
        catch (NumberFormatException e) {
            // keep the stored value
        }
        field.setText(String.format(Locale.US, "%.1f", photonProperties.getAutoSetupPartScore()));
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    private PhotonFeederSlots.Rail selectedRail() {
        int row = railsTable.getSelectedRow();
        if (row < 0) {
            MessageBoxes.errorBox(MainFrame.get(), "Auto Setup", "Select a rail first.");
            return null;
        }
        return feederSlots.getRails().get(railsTable.convertRowIndexToModel(row));
    }

    private void addRail() {
        List<PhotonFeederSlots.Rail> rails = feederSlots.getRails();
        int first = 1;
        for (PhotonFeederSlots.Rail rail : rails) {
            first = Math.max(first, Math.max(rail.getFirstAddress(), rail.getLastAddress()) + 1);
        }
        int last = Math.max(first, Math.min(photonProperties.getMaxFeederAddress(), first + 24));
        rails.add(new PhotonFeederSlots.Rail(first, new Location(LengthUnit.Millimeters),
                last, new Location(LengthUnit.Millimeters)));
        railsModel.fireTableDataChanged();
        railsTable.setRowSelectionInterval(rails.size() - 1, rails.size() - 1);
    }

    private void removeRail() {
        PhotonFeederSlots.Rail rail = selectedRail();
        if (rail != null) {
            feederSlots.getRails().remove(rail);
            railsModel.fireTableDataChanged();
        }
    }

    private void capture(boolean first) {
        PhotonFeederSlots.Rail rail = selectedRail();
        if (rail == null) {
            return;
        }
        UiUtils.messageBoxOnException(() -> {
            Camera camera = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
            Location here = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location old = first ? rail.getFirstLocation() : rail.getLastLocation();
            Location taught = here.derive(null, null, old.convertToUnits(LengthUnit.Millimeters).getZ(),
                    old.getRotation());
            if (first) {
                rail.setFirstLocation(taught);
            }
            else {
                rail.setLastLocation(taught);
            }
            railsModel.fireTableDataChanged();
        });
    }

    private void fillSlots() {
        PhotonFeederSlots.Rail rail = selectedRail();
        if (rail == null) {
            return;
        }
        int count = feederSlots.fillRail(rail);
        MessageBoxes.infoBox("Auto Setup", String.format(
                "Set the locations of %d slots, %d to %d.", count,
                Math.min(rail.getFirstAddress(), rail.getLastAddress()),
                Math.max(rail.getFirstAddress(), rail.getLastAddress())));
    }

    private void autoSetup() {
        if (!Configuration.get().getMachine().isEnabled()) {
            UiUtils.showError(new Exception("Please connect to the machine before running Auto Setup."));
            return;
        }

        PhotonPickHeightProber heightProber = null;
        if (photonProperties.getAutoSetupProbePickHeight()) {
            try {
                Nozzle nozzle = PhotonPickHeightProber.defaultNozzle();
                String whyNot = PhotonPickHeightProber.whyNot(nozzle);
                if (whyNot != null) {
                    MessageBoxes.errorBox(MainFrame.get(), "Auto Setup",
                            "Can't probe pick heights: " + whyNot + " Or turn off Probe pick height.");
                    return;
                }
                heightProber = new PhotonPickHeightProber((ContactProbeNozzle) nozzle);
            }
            catch (Exception e) {
                MessageBoxes.errorBox(MainFrame.get(), "Auto Setup", e);
                return;
            }
        }
        final PhotonPickHeightProber prober = heightProber;

        autoSetupButton.setEnabled(false);
        progressBar.setNumberOfElements(photonProperties.getMaxFeederAddress());
        progressBar.setVisible(true);

        UiUtils.submitUiMachineTask(() -> {
            Camera camera = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
            PhotonAutoSetup setup = new PhotonAutoSetup(
                    new PhotonSprocketFinder(camera, photonProperties.getAutoSetupPipeline(), 500))
                    .withPocketCheck(new PhotonPocketChecker(camera), photonProperties.getAutoSetupPartScore(),
                            photonProperties.getAutoSetupFeedPastEmptyPockets());
            if (prober != null) {
                setup.withHeightProbe(prober);
            }
            return setup.run(progressBar::updateFeederState, null);
        }, (results) -> {
            done();
            lastResults = results;
            showResults(results);
        }, (throwable) -> {
            done();
            MessageBoxes.errorBox(MainFrame.get(), "Auto Setup", throwable);
        });
    }

    private void done() {
        progressBar.setVisible(false);
        progressBar.clearAllState();
        autoSetupButton.setEnabled(true);
        if (afterSetup != null) {
            afterSetup.run();
        }
    }

    private void showResults(List<PhotonAutoSetup.Result> results) {
        String[] columns = {"Slot", "Part", "Parts Left", "Moved (mm)", "Pocket", "Height (mm)", "Result"};
        Object[][] rows = new Object[results.size()][];
        int ok = 0;
        int skipped = 0;
        for (int i = 0; i < results.size(); i++) {
            PhotonAutoSetup.Result result = results.get(i);
            Double moved = result.getCorrectionMm();
            rows[i] = new Object[]{
                    result.slotAddress,
                    result.feeder.getPart() == null ? "" : result.feeder.getPart().getId(),
                    result.feeder.getRecordPartsLeft(),
                    moved == null ? "" : String.format(Locale.US, "%.2f", moved),
                    pocketText(result),
                    result.heightChangeMm == null ? "" : String.format(Locale.US, "%+.2f", result.heightChangeMm),
                    result.isOk() ? "OK" : result.problem};
            if (result.isOk()) {
                ok++;
            }
            else if (result.isSkipped()) {
                skipped++;
            }
        }

        JTable table = new JTable(rows, columns);
        table.setEnabled(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(940, Math.min(400, 40 + 20 * results.size())));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        String summary = String.format("%d of %d feeders set up.", ok, results.size());
        if (skipped > 0) {
            summary += String.format(" %d skipped because no reel is recorded in them.", skipped);
        }
        panel.add(new JLabel(summary), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(MainFrame.get(), panel, "Auto Setup",
                ok + skipped == results.size() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    private void learnVacuum() {
        if (!Configuration.get().getMachine().isEnabled()) {
            UiUtils.showError(new Exception("Please connect to the machine first."));
            return;
        }
        Nozzle nozzle;
        try {
            nozzle = PhotonPickHeightProber.defaultNozzle();
        }
        catch (Exception e) {
            MessageBoxes.errorBox(MainFrame.get(), "Learn Vacuum Levels", e);
            return;
        }
        String whyNot = PhotonVacuumLearner.whyNot(nozzle);
        if (whyNot != null) {
            MessageBoxes.errorBox(MainFrame.get(), "Learn Vacuum Levels", whyNot);
            return;
        }

        // Auto Setup's pocket check, when it ran this session, says which pockets hold a part
        List<PhotonFeeder> sawPart = new ArrayList<>();
        List<PhotonFeeder> lookedEmpty = new ArrayList<>();
        for (PhotonAutoSetup.Result result : lastResults) {
            if (result.isOk() && result.pocketScore != null) {
                (result.partFound ? sawPart : lookedEmpty).add(result.feeder);
            }
        }
        StringBuilder why = new StringBuilder();
        List<PhotonFeeder> feeders = PhotonVacuumLearner.chooseFeeders(nozzle,
                Configuration.get().getMachine().getFeeders(), sawPart, lookedEmpty, why);
        if (feeders.isEmpty()) {
            MessageBoxes.errorBox(MainFrame.get(), "Learn Vacuum Levels", why.toString());
            return;
        }

        StringBuilder names = new StringBuilder();
        for (PhotonFeeder feeder : feeders) {
            names.append(String.format("<br>Slot %d: %s", feeder.getSlotAddress(),
                    feeder.getPart() == null ? "" : feeder.getPart().getId()));
        }
        int answer = JOptionPane.showConfirmDialog(MainFrame.get(), String.format(
                "<html><body style='width: 380px'>Nozzle %s with tip %s will pick a part from each of these "
                + "feeders, read the vacuum, and put the part back in its pocket:%s<br><br>"
                + "The empty nozzle is read too. Nothing on the nozzle tip changes until you apply the result."
                + "</body></html>", nozzle.getName(), nozzle.getNozzleTip().getName(), names),
                "Learn Vacuum Levels", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        ReferenceNozzle referenceNozzle = (ReferenceNozzle) nozzle;
        String buttonText = learnVacuumButton.getText();
        learnVacuumButton.setEnabled(false);
        UiUtils.submitUiMachineTask(
                () -> PhotonVacuumLearner.forNozzle(referenceNozzle).learn(feeders,
                        stage -> SwingUtilities.invokeLater(() -> learnVacuumButton.setText(stage))),
                suggestion -> {
                    learnVacuumButton.setText(buttonText);
                    learnVacuumButton.setEnabled(true);
                    showVacuumSuggestion(referenceNozzle.getNozzleTip(), suggestion);
                },
                throwable -> {
                    learnVacuumButton.setText(buttonText);
                    learnVacuumButton.setEnabled(true);
                    MessageBoxes.errorBox(MainFrame.get(), "Learn Vacuum Levels", throwable);
                });
    }

    private void showVacuumSuggestion(ReferenceNozzleTip tip, PhotonVacuumLearner.Suggestion suggestion) {
        String[] columns = {"Slot", "Part", "Vacuum with part", "Note"};
        Object[][] rows = new Object[suggestion.readings.size()][];
        for (int i = 0; i < rows.length; i++) {
            PhotonVacuumLearner.Reading reading = suggestion.readings.get(i);
            rows[i] = new Object[]{
                    reading.feeder.getSlotAddress(),
                    reading.feeder.getPart() == null ? "" : reading.feeder.getPart().getId(),
                    reading.partOn == null ? "" : String.format(Locale.US, "%.3f", reading.partOn),
                    reading.problem == null ? "" : reading.problem};
        }
        JTable table = new JTable(rows, columns);
        table.setEnabled(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 40 + 20 * rows.length));

        StringBuilder text = new StringBuilder("<html>");
        text.append(String.format(Locale.US, "Empty nozzle, vacuum held: %s<br>Empty nozzle, part-off pulse: %s<br><br>",
                levels(suggestion.emptyHeld), levels(suggestion.emptyPulse)));
        if (!suggestion.isUsable()) {
            text.append("<b>").append(suggestion.problem).append("</b></html>");
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(scroll, BorderLayout.CENTER);
            panel.add(new JLabel(text.toString()), BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(MainFrame.get(), panel, "Learn Vacuum Levels", JOptionPane.WARNING_MESSAGE);
            return;
        }
        text.append(String.format(Locale.US, "<table>"
                        + "<tr><td></td><td><b>Now</b></td><td><b>Suggested</b></td></tr>"
                        + "<tr><td>Part-on (Absolute)</td><td>%.3f .. %.3f</td><td>%.3f .. %.3f</td></tr>"
                        + "<tr><td>Part-off (Absolute)</td><td>%.3f .. %.3f</td><td>%.3f .. %.3f</td></tr></table>",
                tip.getVacuumLevelPartOnLow(), tip.getVacuumLevelPartOnHigh(),
                suggestion.partOnLow, suggestion.partOnHigh,
                tip.getVacuumLevelPartOffLow(), tip.getVacuumLevelPartOffHigh(),
                suggestion.partOffLow, suggestion.partOffHigh));
        if (tip.getMethodPartOn() != ReferenceNozzleTip.VacuumMeasurementMethod.Absolute
                || tip.getMethodPartOff() != ReferenceNozzleTip.VacuumMeasurementMethod.Absolute) {
            text.append("<br>A check on nozzle tip ").append(tip.getName())
                    .append(" is off. Set it to Absolute under its Vacuum settings to use these levels.");
        }
        text.append("</html>");

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(new JLabel(text.toString()), BorderLayout.SOUTH);
        Object[] options = {"Apply to " + tip.getName(), "Cancel"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(), panel, "Learn Vacuum Levels",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            PhotonVacuumLearner.apply(tip, suggestion);
        }
    }

    private static String levels(List<Double> values) {
        StringBuilder text = new StringBuilder();
        for (Double value : values) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(String.format(Locale.US, "%.3f", value));
        }
        return text.toString();
    }

    private static String pocketText(PhotonAutoSetup.Result result) {
        if (result.pocketScore == null) {
            return "";
        }
        String text = String.format(Locale.US, "%s (score %.1f)",
                result.partFound ? "Part" : "Looks empty", result.pocketScore);
        if (result.emptyPocketsFed > 0) {
            text += String.format(", fed %d empty", result.emptyPocketsFed);
        }
        return text;
    }

    private class RailsTableModel extends AbstractTableModel {
        private final String[] columns = {"First Slot", "Last Slot", "First X", "First Y", "Last X", "Last Y",
                "Z", "Rotation"};

        @Override
        public int getRowCount() {
            return feederSlots.getRails().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column < 2 ? Integer.class : Double.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            PhotonFeederSlots.Rail rail = feederSlots.getRails().get(row);
            Location first = rail.getFirstLocation().convertToUnits(LengthUnit.Millimeters);
            Location last = rail.getLastLocation().convertToUnits(LengthUnit.Millimeters);
            switch (column) {
                case 0: return rail.getFirstAddress();
                case 1: return rail.getLastAddress();
                case 2: return first.getX();
                case 3: return first.getY();
                case 4: return last.getX();
                case 5: return last.getY();
                case 6: return first.getZ();
                default: return first.getRotation();
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (value == null) {
                return;
            }
            PhotonFeederSlots.Rail rail = feederSlots.getRails().get(row);
            Location first = rail.getFirstLocation().convertToUnits(LengthUnit.Millimeters);
            Location last = rail.getLastLocation().convertToUnits(LengthUnit.Millimeters);
            double number = ((Number) value).doubleValue();
            switch (column) {
                case 0:
                    rail.setFirstAddress((int) number);
                    break;
                case 1:
                    rail.setLastAddress((int) number);
                    break;
                case 2:
                    rail.setFirstLocation(first.derive(number, null, null, null));
                    break;
                case 3:
                    rail.setFirstLocation(first.derive(null, number, null, null));
                    break;
                case 4:
                    rail.setLastLocation(last.derive(number, null, null, null));
                    break;
                case 5:
                    rail.setLastLocation(last.derive(null, number, null, null));
                    break;
                case 6:
                    rail.setFirstLocation(first.derive(null, null, number, null));
                    rail.setLastLocation(last.derive(null, null, number, null));
                    break;
                default:
                    rail.setFirstLocation(first.derive(null, null, null, number));
                    rail.setLastLocation(last.derive(null, null, null, number));
                    break;
            }
            fireTableRowsUpdated(row, row);
        }
    }
}
