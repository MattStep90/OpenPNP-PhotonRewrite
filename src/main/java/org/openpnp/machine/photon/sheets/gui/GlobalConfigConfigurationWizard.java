package org.openpnp.machine.photon.sheets.gui;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;
import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.JBindings;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class GlobalConfigConfigurationWizard extends AbstractConfigurationWizard {

    private final PhotonProperties photonProperties;

    private final FeederSearchProgressBar progressBarPanel;
    private final JButton searchButton;
    private final JSpinner maxFeederSpinner;
    private final JButton btnStartFeedSlotsWizard;
    private final JLabel lblNewLabel;

    /**
     * Create the panel.
     */
    public GlobalConfigConfigurationWizard() {
        photonProperties = new PhotonProperties(Configuration.get().getMachine());

        JPanel searchPanel = new JPanel();
        searchPanel.setBorder(new TitledBorder(null, "Search", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(searchPanel);
        searchPanel.setLayout(new FormLayout(new ColumnSpec[]{
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("50dlu"),
                ColumnSpec.decode("4dlu:grow"),
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,},
                new RowSpec[]{
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        RowSpec.decode("10dlu"),
                        FormSpecs.RELATED_GAP_ROWSPEC,}));

        JLabel lblMaxFeeder = new JLabel("Maximum Feeder Address To Scan");
        searchPanel.add(lblMaxFeeder, "2, 2");

        int initialMaxFeederAddress = photonProperties.getMaxFeederAddress();
        SpinnerNumberModel maxFeederSpinnerModel = new SpinnerNumberModel(
                initialMaxFeederAddress, 1, 254, 1
        );
        maxFeederSpinner = new JSpinner(maxFeederSpinnerModel);
        searchPanel.add(maxFeederSpinner, "4, 2");

        searchButton = new JButton("Search");
        searchButton.addActionListener(searchAction);
        searchPanel.add(searchButton, "6, 2");

        progressBarPanel = new FeederSearchProgressBar();
        searchPanel.add(progressBarPanel, "2, 4, 5, 1, fill, fill");
        progressBarPanel.setVisible(false);
        progressBarPanel.setNumberOfElements(initialMaxFeederAddress);

        contentPanel.add(new AutoSetupPanel(photonProperties, GlobalConfigConfigurationWizard::reloadFeeders));

        JPanel firmwarePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        firmwarePanel.setBorder(new TitledBorder(null, "Feeder Firmware", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        JButton updateAllButton = new JButton("Update All Feeders...");
        updateAllButton.setToolTipText("Write new firmware to every feeder in a slot, one at a time, "
                + "with the Stock or Ratchet file to match each feeder.");
        updateAllButton.addActionListener(e -> FirmwareUpdateAllDialog.start());
        firmwarePanel.add(updateAllButton);
        firmwarePanel.add(new JLabel("Choose both PhotonFeeder_F031_Stock.bin and _Ratchet.bin to update a mixed machine."));
        contentPanel.add(firmwarePanel);

        JPanel lostSlotsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        lostSlotsPanel.setBorder(new TitledBorder(null, "Lost Slot IDs", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        JButton fixLostSlotsButton = new JButton("Fix Lost Slots...");
        fixLostSlotsButton.setToolTipText("Write the slot ID again for feeders whose slot lost it (blue LED), "
                + "with all the feeders left in.");
        fixLostSlotsButton.addActionListener(e ->
                FixLostSlotsDialog.start(fixLostSlotsButton, GlobalConfigConfigurationWizard::reloadFeeders));
        lostSlotsPanel.add(fixLostSlotsButton);
        lostSlotsPanel.add(new JLabel("Each feeder to fix blinks, and you say which slot it's in."));
        contentPanel.add(lostSlotsPanel);

        JPanel programFeederSlotsPanel = new JPanel();
        programFeederSlotsPanel.setBorder(new TitledBorder(null, "Program Feeder Slots", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(programFeederSlotsPanel);
        programFeederSlotsPanel.setLayout(new FormLayout(new ColumnSpec[]{
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("4dlu:grow"),
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,},
                new RowSpec[]{
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        RowSpec.decode("6dlu:grow"),
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,}));

        lblNewLabel = new JLabel("If you've built your own slots and need to program them, use this wizard.");
        programFeederSlotsPanel.add(lblNewLabel, "2, 2, 3, 1");

        btnStartFeedSlotsWizard = new JButton("Start Wizard");
        btnStartFeedSlotsWizard.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                if(! Configuration.get().getMachine().isEnabled()) {
                    UiUtils.showError(new Exception("Please connect to the machine before running this wizard."));
                    return;
                }

                ProgramFeederSlotWizard wizard = new ProgramFeederSlotWizard();
                wizard.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                wizard.setVisible(true);
            }
        });
        programFeederSlotsPanel.add(btnStartFeedSlotsWizard, "4, 4");
    }

    @Override
    public void createBindings() {
        bind(UpdateStrategy.READ_WRITE, photonProperties, "maxFeederAddress", maxFeederSpinner, "value");
    }

    private final Action searchAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            progressBarPanel.setVisible(true);
            searchButton.setEnabled(false);
            maxFeederSpinner.setEnabled(false);

            int maxFeederAddress = photonProperties.getMaxFeederAddress();
            progressBarPanel.setNumberOfElements(maxFeederAddress);

            UiUtils.submitUiMachineTask(() -> {
                PhotonFeeder.findAllFeeders(progressBarPanel::updateFeederState);
                PhotonFeeder.readAllRecordsQuietly();
                return null;
            }, (parameter) -> {
                resetState();
                reloadFeeders();
            }, (throwable) -> {
                resetState();
                reloadFeeders();

                MessageBoxes.errorBox(MainFrame.get(), "Error", throwable);
            });
        }

        private void resetState() {
            progressBarPanel.setVisible(false);
            progressBarPanel.clearAllState();
            searchButton.setEnabled(true);
            maxFeederSpinner.setEnabled(true);
        }
    };

    // The search may have filled in the selected feeder or added new ones, so show them as
    // they are now instead of the configuration from before the search.
    private static void reloadFeeders() {
        MainFrame mainFrame = MainFrame.get();
        if (mainFrame != null && mainFrame.getFeedersTab() != null) {
            mainFrame.getFeedersTab().reloadFeeders();
        }
    }
}
