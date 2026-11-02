package org.openpnp.machine.photon.sheets.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;

import org.jdesktop.beansbinding.AutoBinding;
import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.jdesktop.beansbinding.BeanProperty;
import org.jdesktop.beansbinding.Bindings;
import org.openpnp.Translations;
import org.openpnp.gui.components.LocationButtonsPanel;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MutableLocationProxy;
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.machine.photon.PhotonFeedCalibrator;
import org.openpnp.machine.photon.PhotonBlinker;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonProperties;
import org.openpnp.machine.photon.PhotonSprocketFinder;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.machine.photon.PhotonFeederRecord;
import org.openpnp.machine.photon.PhotonFirmwareUpdater;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Part;
import org.openpnp.util.UiUtils;
import org.openpnp.util.UiUtils.Thrunnable;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

public class FeederConfigurationWizard extends AbstractConfigurationWizard {
	private final PhotonFeeder feeder;
	
	private final JLabel hardwareIdValue;
	private final JLabel slotAddressValue;
	private final JComboBox partCb;
	private final JTextField partPitchTf;
	private final JTextField feedRetryCountTf;
	private final JTextField pickRetryCountTf;
	private final JTextField tapeWidthTf;
	private final JCheckBox checkEveryPickCb;
	private final JLabel recordPartIdValue;
	private final JLabel recordPartsLeftValue;
	private final JLabel recordQuantityValue;
	private final JTextField recordCountTf;
	private final JTextField lowPartsTf;
	private final JLabel lowPartsValue;
	private final JLabel saveStatusLabel;
	private final JLabel firmwareVersionValue;
	private final JLabel feedScaleValue;
	private final JTextField xSlotTf;
	private final JTextField ySlotTf;
	private final JTextField zSlotTf;
	private final JTextField rotSlotTf;
	private final JTextField xOffsetTf;
	private final JTextField yOffsetTf;
	private final JTextField zOffsetTf;
	private final JTextField rotOffsetTf;
	private final LocationButtonsPanel offsetLocationPanel;
	private final JLabel moveWhileFeedingLabel;
	private final JCheckBox moveWhileFeedingCheckBox;
	private final LocationButtonsPanel slotLocationPanel;

	/**
	 * Create the panel.
	 */
	public FeederConfigurationWizard(PhotonFeeder feeder) {
		this.feeder = feeder;
		
		JPanel infoPanel = new JPanel();
		contentPanel.add(infoPanel);
		infoPanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.InfoPanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, null)); //$NON-NLS-1$
		infoPanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(50dlu;pref)"), //$NON-NLS-1$
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.PREF_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,}));
		
		JLabel hardwareIdLabel = new JLabel(Translations.getString("FeederConfigurationWizard.InfoPanel.hardwareIdLabel.text")); //$NON-NLS-1$
		infoPanel.add(hardwareIdLabel, "2, 2, left, center"); //$NON-NLS-1$
		
		hardwareIdValue = new JLabel(""); //$NON-NLS-1$
		infoPanel.add(hardwareIdValue, "4, 2, 5, 1, left, center"); //$NON-NLS-1$
		
		JLabel slotAddressLabel = new JLabel(Translations.getString("FeederConfigurationWizard.InfoPanel.slotAddressLabel.text")); //$NON-NLS-1$
		infoPanel.add(slotAddressLabel, "2, 4"); //$NON-NLS-1$

		slotAddressValue = new JLabel(""); //$NON-NLS-1$
		infoPanel.add(slotAddressValue, "4, 4"); //$NON-NLS-1$

		JButton findButton = new JButton(findSlotAddressAction);
		infoPanel.add(findButton, "6, 4"); //$NON-NLS-1$

		JButton identifyButton = new JButton(identifyAction);
		infoPanel.add(identifyButton, "8, 4"); //$NON-NLS-1$
		
		JPanel partPanel = new JPanel();
		partPanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.PartPanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0))); //$NON-NLS-1$
		contentPanel.add(partPanel);
		partPanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(50dlu;default)"), //$NON-NLS-1$
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,}));
		
		JLabel partLabel = new JLabel(Translations.getString("FeederConfigurationWizard.PartPanel.partLabel.text")); //$NON-NLS-1$
		partPanel.add(partLabel, "2, 2, right, default"); //$NON-NLS-1$

		partCb = new JComboBox();
		partPanel.add(partCb, "4, 2, 5, 1, fill, default"); //$NON-NLS-1$
		partCb.setModel(new PartsComboBoxModel());
        partCb.setRenderer(new IdentifiableListCellRenderer<Part>());
		
		JLabel partPitchLabel = new JLabel(Translations.getString("FeederConfigurationWizard.PartPanel.partPitchLabel.text")); //$NON-NLS-1$
		partPanel.add(partPitchLabel, "2, 4, right, default"); //$NON-NLS-1$
		
		partPitchTf = new JTextField();
		partPanel.add(partPitchTf, "4, 4, fill, default"); //$NON-NLS-1$
		partPitchTf.setColumns(10);
		
		JButton feedButton = new JButton(feedAction);
		partPanel.add(feedButton, "6, 4"); //$NON-NLS-1$

		JButton feedOneMmButton = new JButton(feedOneMmAction);
		partPanel.add(feedOneMmButton, "8, 4"); //$NON-NLS-1$
		
		JLabel feedRetryLabel = new JLabel(Translations.getString("FeederConfigurationWizard.PartPanel.feedRetryLabel.text")); //$NON-NLS-1$
		partPanel.add(feedRetryLabel, "2, 6, right, default"); //$NON-NLS-1$
		
		feedRetryCountTf = new JTextField();
		partPanel.add(feedRetryCountTf, "4, 6, fill, default"); //$NON-NLS-1$
		feedRetryCountTf.setColumns(10);
		
		JLabel pickRetryLabel = new JLabel(Translations.getString("FeederConfigurationWizard.PartPanel.pickRetryLabel.text")); //$NON-NLS-1$
		partPanel.add(pickRetryLabel, "2, 8, right, default"); //$NON-NLS-1$
		
		pickRetryCountTf = new JTextField();
		partPanel.add(pickRetryCountTf, "4, 8, fill, default"); //$NON-NLS-1$
		pickRetryCountTf.setColumns(10);

		checkEveryPickCb = new JCheckBox(Translations.getString("FeederConfigurationWizard.PartPanel.checkEveryPick.text")); //$NON-NLS-1$
		checkEveryPickCb.setToolTipText(Translations.getString("FeederConfigurationWizard.PartPanel.checkEveryPick.toolTipText")); //$NON-NLS-1$
		partPanel.add(checkEveryPickCb, "6, 8, 3, 1"); //$NON-NLS-1$
		
		JPanel recordPanel = new JPanel();
		recordPanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.RecordPanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, null)); //$NON-NLS-1$
		contentPanel.add(recordPanel);
		recordPanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(50dlu;default)"), //$NON-NLS-1$
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,}));

		JLabel recordPartIdLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.partIdLabel.text")); //$NON-NLS-1$
		recordPanel.add(recordPartIdLabel, "2, 2, right, default"); //$NON-NLS-1$

		recordPartIdValue = new JLabel(""); //$NON-NLS-1$
		recordPanel.add(recordPartIdValue, "4, 2, left, default"); //$NON-NLS-1$

		JButton readRecordButton = new JButton(readRecordAction);
		recordPanel.add(readRecordButton, "6, 2"); //$NON-NLS-1$

		JButton scanPartButton = new JButton(scanPartAction);
		recordPanel.add(scanPartButton, "8, 2"); //$NON-NLS-1$

		JLabel recordPartsLeftLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.partsLeftLabel.text")); //$NON-NLS-1$
		recordPartsLeftLabel.setToolTipText(Translations.getString("FeederConfigurationWizard.RecordPanel.partsLeftLabel.toolTipText")); //$NON-NLS-1$
		recordPanel.add(recordPartsLeftLabel, "2, 4, right, default"); //$NON-NLS-1$

		recordPartsLeftValue = new JLabel(""); //$NON-NLS-1$
		recordPanel.add(recordPartsLeftValue, "4, 4, left, default"); //$NON-NLS-1$

		JLabel recordQuantityLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.quantityLabel.text")); //$NON-NLS-1$
		recordPanel.add(recordQuantityLabel, "2, 6, right, default"); //$NON-NLS-1$

		recordQuantityValue = new JLabel(""); //$NON-NLS-1$
		recordPanel.add(recordQuantityValue, "4, 6, left, default"); //$NON-NLS-1$

		JLabel tapeWidthLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.tapeWidthLabel.text")); //$NON-NLS-1$
		recordPanel.add(tapeWidthLabel, "2, 8, right, default"); //$NON-NLS-1$

		tapeWidthTf = new JTextField();
		recordPanel.add(tapeWidthTf, "4, 8, fill, default"); //$NON-NLS-1$
		tapeWidthTf.setColumns(10);

		JLabel recordCountLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.countLabel.text")); //$NON-NLS-1$
		recordCountLabel.setToolTipText(Translations.getString("FeederConfigurationWizard.RecordPanel.countLabel.toolTipText")); //$NON-NLS-1$
		recordPanel.add(recordCountLabel, "2, 10, right, default"); //$NON-NLS-1$

		recordCountTf = new JTextField();
		recordPanel.add(recordCountTf, "4, 10, fill, default"); //$NON-NLS-1$
		recordCountTf.setColumns(10);

		JButton loadRecordButton = new JButton(loadRecordAction);
		recordPanel.add(loadRecordButton, "6, 10"); //$NON-NLS-1$

		JButton setPartsLeftButton = new JButton(setPartsLeftAction);
		recordPanel.add(setPartsLeftButton, "8, 10"); //$NON-NLS-1$

		JButton clearRecordButton = new JButton(clearRecordAction);
		recordPanel.add(clearRecordButton, "10, 10"); //$NON-NLS-1$

		JLabel lowPartsLabel = new JLabel(Translations.getString("FeederConfigurationWizard.RecordPanel.lowPartsLabel.text")); //$NON-NLS-1$
		lowPartsLabel.setToolTipText(Translations.getString("FeederConfigurationWizard.RecordPanel.lowPartsLabel.toolTipText")); //$NON-NLS-1$
		recordPanel.add(lowPartsLabel, "2, 12, right, default"); //$NON-NLS-1$

		lowPartsTf = new JTextField();
		lowPartsTf.setToolTipText(Translations.getString("FeederConfigurationWizard.RecordPanel.lowPartsLabel.toolTipText")); //$NON-NLS-1$
		recordPanel.add(lowPartsTf, "4, 12, fill, default"); //$NON-NLS-1$
		lowPartsTf.setColumns(10);

		JButton setLowPartsButton = new JButton(setLowPartsAction);
		recordPanel.add(setLowPartsButton, "6, 12"); //$NON-NLS-1$

		lowPartsValue = new JLabel(""); //$NON-NLS-1$
		recordPanel.add(lowPartsValue, "8, 12, 3, 1, left, default"); //$NON-NLS-1$

		saveStatusLabel = new JLabel(" "); //$NON-NLS-1$
		saveStatusLabel.setFont(saveStatusLabel.getFont().deriveFont(Font.BOLD));
		recordPanel.add(saveStatusLabel, "2, 14, 9, 1, left, default"); //$NON-NLS-1$

		JPanel firmwarePanel = new JPanel();
		firmwarePanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.FirmwarePanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, null)); //$NON-NLS-1$
		contentPanel.add(firmwarePanel);
		firmwarePanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(50dlu;default)"), //$NON-NLS-1$
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,}));

		JLabel firmwareVersionLabel = new JLabel(Translations.getString("FeederConfigurationWizard.FirmwarePanel.versionLabel.text")); //$NON-NLS-1$
		firmwarePanel.add(firmwareVersionLabel, "2, 2, right, default"); //$NON-NLS-1$

		firmwareVersionValue = new JLabel(""); //$NON-NLS-1$
		firmwarePanel.add(firmwareVersionValue, "4, 2, left, default"); //$NON-NLS-1$

		JButton readVersionButton = new JButton(readVersionAction);
		firmwarePanel.add(readVersionButton, "6, 2"); //$NON-NLS-1$

		JButton updateFirmwareButton = new JButton(updateFirmwareAction);
		firmwarePanel.add(updateFirmwareButton, "8, 2"); //$NON-NLS-1$

		JPanel drivePanel = new JPanel();
		drivePanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.DrivePanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, null)); //$NON-NLS-1$
		contentPanel.add(drivePanel);
		drivePanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(50dlu;default)"), //$NON-NLS-1$
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.BUTTON_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,}));

		JLabel feedScaleLabel = new JLabel(Translations.getString("FeederConfigurationWizard.DrivePanel.feedScaleLabel.text")); //$NON-NLS-1$
		feedScaleLabel.setToolTipText(Translations.getString("FeederConfigurationWizard.DrivePanel.feedScaleLabel.toolTipText")); //$NON-NLS-1$
		drivePanel.add(feedScaleLabel, "2, 2, right, default"); //$NON-NLS-1$

		feedScaleValue = new JLabel(""); //$NON-NLS-1$
		drivePanel.add(feedScaleValue, "4, 2, left, default"); //$NON-NLS-1$

		drivePanel.add(new JButton(readFeedScaleAction), "6, 2"); //$NON-NLS-1$
		drivePanel.add(new JButton(calibrateFeedAction), "8, 2"); //$NON-NLS-1$
		drivePanel.add(new JButton(calibrateRatchetAction), "10, 2"); //$NON-NLS-1$

		JPanel locationPanel = new JPanel();
		locationPanel.setBorder(new TitledBorder(null, Translations.getString("FeederConfigurationWizard.LocationPanel.Border.title"), TitledBorder.LEADING, TitledBorder.TOP, null, null)); //$NON-NLS-1$
		contentPanel.add(locationPanel);
		locationPanel.setLayout(new FormLayout(new ColumnSpec[] {
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,
				FormSpecs.DEFAULT_COLSPEC,
				FormSpecs.RELATED_GAP_COLSPEC,},
			new RowSpec[] {
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,
				FormSpecs.DEFAULT_ROWSPEC,
				FormSpecs.RELATED_GAP_ROWSPEC,}));
		
		JLabel xOffsetLabel = new JLabel("X"); //$NON-NLS-1$
		locationPanel.add(xOffsetLabel, "4, 2, center, center"); //$NON-NLS-1$
		
		JLabel yOffsetLabel = new JLabel("Y"); //$NON-NLS-1$
		locationPanel.add(yOffsetLabel, "6, 2, center, center"); //$NON-NLS-1$
		
		JLabel zOffsetLabel = new JLabel("Z"); //$NON-NLS-1$
		locationPanel.add(zOffsetLabel, "8, 2, center, center"); //$NON-NLS-1$
		
		JLabel rotationOffsetLabel = new JLabel(Translations.getString("FeederConfigurationWizard.LocationPanel.rotationOffsetLabel.text")); //$NON-NLS-1$
		locationPanel.add(rotationOffsetLabel, "10, 2, center, center"); //$NON-NLS-1$
		
		JLabel slotLocationLabel = new JLabel(Translations.getString("FeederConfigurationWizard.LocationPanel.slotLocationLabel.text")); //$NON-NLS-1$
		locationPanel.add(slotLocationLabel, "2, 4, right, center"); //$NON-NLS-1$
		
		xSlotTf = new JTextField();
		locationPanel.add(xSlotTf, "4, 4, left, center"); //$NON-NLS-1$
		xSlotTf.setColumns(10);
		
		ySlotTf = new JTextField();
		locationPanel.add(ySlotTf, "6, 4, left, center"); //$NON-NLS-1$
		ySlotTf.setColumns(10);
		
		zSlotTf = new JTextField();
		locationPanel.add(zSlotTf, "8, 4, left, center"); //$NON-NLS-1$
		zSlotTf.setColumns(10);
		
		rotSlotTf = new JTextField();
		locationPanel.add(rotSlotTf, "10, 4, left, center"); //$NON-NLS-1$
		rotSlotTf.setColumns(10);

		slotLocationPanel = new LocationButtonsPanel(xSlotTf, ySlotTf, zSlotTf, rotSlotTf);
		locationPanel.add(slotLocationPanel, "12, 4, left, top"); //$NON-NLS-1$
		
		JLabel offsetLabel = new JLabel(Translations.getString("FeederConfigurationWizard.LocationPanel.offsetLabel.text")); //$NON-NLS-1$
		locationPanel.add(offsetLabel, "2, 6, right, center"); //$NON-NLS-1$
		
		xOffsetTf = new JTextField();
		locationPanel.add(xOffsetTf, "4, 6, left, center"); //$NON-NLS-1$
		xOffsetTf.setColumns(10);
		
		yOffsetTf = new JTextField();
		locationPanel.add(yOffsetTf, "6, 6, left, center"); //$NON-NLS-1$
		yOffsetTf.setColumns(10);
		
		zOffsetTf = new JTextField();
		locationPanel.add(zOffsetTf, "8, 6, left, center"); //$NON-NLS-1$
		zOffsetTf.setColumns(10);
		
		rotOffsetTf = new JTextField();
		locationPanel.add(rotOffsetTf, "10, 6, left, center"); //$NON-NLS-1$
		rotOffsetTf.setColumns(10);

		offsetLocationPanel = new LocationButtonsPanel(xOffsetTf, yOffsetTf, zOffsetTf, rotOffsetTf);
		locationPanel.add(offsetLocationPanel, "12, 6, left, top"); //$NON-NLS-1$

		JButton pin1QuadrantButton = new JButton(pin1QuadrantAction);
		locationPanel.add(pin1QuadrantButton, "10, 8, left, center"); //$NON-NLS-1$

		moveWhileFeedingLabel = new JLabel(Translations.getString("FeederConfigurationWizard.LocationPanel.moveWhileFeedingLabel.text"));
		locationPanel.add(moveWhileFeedingLabel, "2, 8, right, default"); //$NON-NLS-1$
		moveWhileFeedingLabel.setToolTipText(Translations.getString("FeederConfigurationWizard.LocationPanel.moveWhileFeedingLabel.toolTipText"));

		moveWhileFeedingCheckBox = new JCheckBox();
		locationPanel.add(moveWhileFeedingCheckBox, "4, 8, left, default"); //$NON-NLS-1$
		moveWhileFeedingCheckBox.setToolTipText(Translations.getString("FeederConfigurationWizard.LocationPanel.moveWhileFeedingLabel.toolTipText"));
	}

	AutoBinding<PhotonFeeder, Object, SlotProxy, Object> binding;
	
	private static final Color WARNING_COLOR = new Color(0xC62828);
	private static final Color LOW_COLOR = new Color(0xE65100);
	private static final Color SAFE_COLOR = new Color(0x2E7D32);

	private final PropertyChangeListener lowPartsListener = e -> SwingUtilities.invokeLater(this::showLowParts);

	private void showLowParts() {
		switch (feeder.getLowPartsLevel()) {
			case EMPTY:
				lowPartsValue.setForeground(WARNING_COLOR);
				break;
			case LOW:
				lowPartsValue.setForeground(LOW_COLOR);
				break;
			default:
				lowPartsValue.setForeground(UIManager.getColor("Label.foreground")); //$NON-NLS-1$
				break;
		}
	}

	private final PropertyChangeListener feedScaleListener = e -> SwingUtilities.invokeLater(this::showFeedScale);

	private void showFeedScale() {
		Double scale = feeder.getFeedScale();
		feedScaleValue.setText(scale == null ? "" : String.format(java.util.Locale.US, "%+.2f %%", scale)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private final PropertyChangeListener lowPartsSettingListener = e -> SwingUtilities.invokeLater(this::showLowPartsSetting);

	private void showLowPartsSetting() {
		Integer setting = feeder.getLowPartsSetting();
		if (setting == null || setting == PhotonFeederRecord.LOW_PARTS_DEFAULT) {
			lowPartsTf.setText(""); //$NON-NLS-1$
		} else {
			lowPartsTf.setText(String.valueOf(setting));
		}
	}

	private void showSaveStatus(String text, Color color) {
		SwingUtilities.invokeLater(() -> {
			saveStatusLabel.setForeground(color);
			saveStatusLabel.setText(text);
		});
	}

	@Override
	protected void saveToModel() {
		super.saveToModel();
		// The rotation in tape follows the reel, so a changed offset rotation goes to the feeder too
		if (feeder.recordRotationDiffers()) {
			submitSavedTask(feeder::saveRotationToRecord);
		}
	}

	@Override
	public void dispose() {
	    super.dispose();
	    binding.unbind();
	    feeder.removePropertyChangeListener("lowPartsLevel", lowPartsListener); //$NON-NLS-1$
	    feeder.removePropertyChangeListener("lowPartsSetting", lowPartsSettingListener); //$NON-NLS-1$
	    feeder.removePropertyChangeListener("feedScale", feedScaleListener); //$NON-NLS-1$
	}
	
	@Override
	public void createBindings() {
		LengthConverter lengthConverter = new LengthConverter();
		IntegerConverter intConverter = new IntegerConverter();
		DoubleConverter doubleConverter =
				new DoubleConverter(Configuration.get().getLengthDisplayFormat());

		SlotProxy slotProxy = new SlotProxy();
		binding = Bindings.createAutoBinding(UpdateStrategy.READ,
				feeder, BeanProperty.create("slot"), //$NON-NLS-1$
				slotProxy, BeanProperty.create("slot")); //$NON-NLS-1$
		binding.setSourceNullValue(null);
		binding.bind();
//		bind(UpdateStrategy.READ, feeder, "slot", slotProxy, "slot");

		addWrappedBinding(feeder, "hardwareId", hardwareIdValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "slotAddress", slotAddressValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$

		addWrappedBinding(feeder, "part", partCb, "selectedItem"); //$NON-NLS-1$ //$NON-NLS-2$
		addWrappedBinding(feeder, "partPitch", partPitchTf, "text", intConverter); //$NON-NLS-1$ //$NON-NLS-2$
		addWrappedBinding(feeder, "feedRetryCount", feedRetryCountTf, "text", intConverter); //$NON-NLS-1$ //$NON-NLS-2$
		addWrappedBinding(feeder, "pickRetryCount", pickRetryCountTf, "text", intConverter); //$NON-NLS-1$ //$NON-NLS-2$
		addWrappedBinding(feeder, "tapeWidth", tapeWidthTf, "text", intConverter); //$NON-NLS-1$ //$NON-NLS-2$
		addWrappedBinding(feeder, "checkEveryPick", checkEveryPickCb, "selected"); //$NON-NLS-1$ //$NON-NLS-2$

		bind(UpdateStrategy.READ, feeder, "recordPartId", recordPartIdValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, feeder, "recordPartsLeft", recordPartsLeftValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, feeder, "recordQuantity", recordQuantityValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, feeder, "recordLowParts", lowPartsValue, "text"); //$NON-NLS-1$ //$NON-NLS-2$
		feeder.addPropertyChangeListener("lowPartsLevel", lowPartsListener); //$NON-NLS-1$
		feeder.addPropertyChangeListener("lowPartsSetting", lowPartsSettingListener); //$NON-NLS-1$
		showLowParts();
		showLowPartsSetting();
		feeder.addPropertyChangeListener("feedScale", feedScaleListener); //$NON-NLS-1$
		showFeedScale();

		bind(UpdateStrategy.READ, slotProxy, "enabled", readRecordAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", loadRecordAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", scanPartAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", setPartsLeftAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", clearRecordAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", setLowPartsAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", readVersionAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", updateFirmwareAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", readFeedScaleAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", calibrateFeedAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", calibrateRatchetAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$

		bind(UpdateStrategy.READ, slotProxy, "enabled", feedAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", feedOneMmAction, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$

		bind(UpdateStrategy.READ, slotProxy, "enabled", xSlotTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", ySlotTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", zSlotTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", rotSlotTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", slotLocationPanel, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$

		bind(UpdateStrategy.READ, slotProxy, "enabled", xOffsetTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", yOffsetTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", zOffsetTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", rotOffsetTf, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(UpdateStrategy.READ, slotProxy, "enabled", offsetLocationPanel, "enabled"); //$NON-NLS-1$ //$NON-NLS-2$

		MutableLocationProxy pickLocation = new MutableLocationProxy();
		bind(AutoBinding.UpdateStrategy.READ_WRITE, slotProxy, "location", pickLocation, "location"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, pickLocation, "lengthX", xSlotTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, pickLocation, "lengthY", ySlotTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, pickLocation, "lengthZ", zSlotTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, pickLocation, "rotation", rotSlotTf, "text", doubleConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ, pickLocation, "location", offsetLocationPanel, "baseLocation"); //$NON-NLS-1$ //$NON-NLS-2$

		MutableLocationProxy offsets = new MutableLocationProxy();
		addWrappedBinding(feeder, "offset", offsets, "location"); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, offsets, "lengthX", xOffsetTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, offsets, "lengthY", yOffsetTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, offsets, "lengthZ", zOffsetTf, "text", lengthConverter); //$NON-NLS-1$ //$NON-NLS-2$
		bind(AutoBinding.UpdateStrategy.READ_WRITE, offsets, "rotation", rotOffsetTf, "text", doubleConverter); //$NON-NLS-1$ //$NON-NLS-2$

		addWrappedBinding(feeder, "moveWhileFeeding", moveWhileFeedingCheckBox, "selected"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private final Action findSlotAddressAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.FindSlotAddressAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(feeder::findSlotAddress);
		}
	};

	private final Action identifyAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.IdentifyAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.IdentifyAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			// Blink until the user stops it. The machine task waits for that, so nothing else uses the bus meanwhile.
			UiUtils.submitUiMachineTask(() -> {
				PhotonBlinker blinker = PhotonBlinker.start(feeder::identify);
				try {
					SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(MainFrame.get(),
							String.format(Translations.getString("FeederConfigurationWizard.IdentifyAction.Blinking"), feeder.getName()), //$NON-NLS-1$
							Translations.getString("FeederConfigurationWizard.IdentifyAction.Name"), //$NON-NLS-1$
							JOptionPane.INFORMATION_MESSAGE));
				} finally {
					blinker.stop();
				}
			});
		}
	};

	private final Action feedAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.FeedAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(() -> {
				feeder.feed(null); // TODO This probably shouldn't be null
			});
		}
	};

	private long parseRecordCount() throws Exception {
		try {
			long count = Long.parseLong(recordCountTf.getText().trim());
			if (count >= 0) {
				return count;
			}
		} catch (NumberFormatException e) {
			// Fall through to the message below
		}
		throw new Exception(Translations.getString("FeederConfigurationWizard.RecordPanel.countInvalid.text")); //$NON-NLS-1$
	}

	private void submitRecordTask(Thrunnable task) {
		// Apply pending edits (part, pitch, tape width) first, so the feeder gets what is shown
		if (isDirty()) {
			apply();
		}
		submitSavedTask(task);
	}

	/**
	 * Runs a change to the feeder's record, then waits for the feeder to write it to flash and
	 * says when power can be removed again.
	 */
	private void submitSavedTask(Thrunnable task) {
		showSaveStatus(Translations.getString("FeederConfigurationWizard.RecordPanel.saving.text"), WARNING_COLOR); //$NON-NLS-1$
		UiUtils.submitUiMachineTask(() -> {
			try {
				task.thrun();
			} catch (Exception e) {
				showSaveStatus(" ", SAFE_COLOR); //$NON-NLS-1$
				throw e;
			}
			if (feeder.waitUntilSaved(SAVE_TIMEOUT_MS)) {
				showSaveStatus(Translations.getString("FeederConfigurationWizard.RecordPanel.saved.text"), SAFE_COLOR); //$NON-NLS-1$
			} else {
				showSaveStatus(Translations.getString("FeederConfigurationWizard.RecordPanel.notSaved.text"), WARNING_COLOR); //$NON-NLS-1$
			}
		});
	}

	private static final long SAVE_TIMEOUT_MS = 5000;

	private int parseLowParts() throws Exception {
		String text = lowPartsTf.getText().trim();
		if (text.isEmpty()) {
			return PhotonFeederRecord.LOW_PARTS_DEFAULT;
		}
		try {
			int count = Integer.parseInt(text);
			if (count >= 0 && count <= PhotonFeederRecord.LOW_PARTS_MAX_COUNT) {
				return count;
			}
		} catch (NumberFormatException e) {
			// Fall through to the message below
		}
		throw new Exception(Translations.getString("FeederConfigurationWizard.RecordPanel.lowPartsInvalid.text")); //$NON-NLS-1$
	}

	private final Action readRecordAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.ReadRecordAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(() -> {
				feeder.readRecord();
				feeder.readLowParts();
			});
		}
	};

	private final Action loadRecordAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.LoadRecordAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.LoadRecordAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.messageBoxOnException(() -> {
				long count = parseRecordCount();
				submitRecordTask(() -> feeder.loadRecord(count));
			});
		}
	};

	private final Action pin1QuadrantAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.Pin1QuadrantAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.Pin1QuadrantAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			Object selected = partCb.getSelectedItem();
			Part part = selected instanceof Part ? (Part) selected : feeder.getPart();
			Footprint footprint = part == null || part.getPackage() == null ? null : part.getPackage().getFootprint();
			Double current = null;
			try {
				current = Double.parseDouble(rotOffsetTf.getText().trim());
			}
			catch (NumberFormatException ignored) {
			}
			Double rotation = Pin1QuadrantDialog.ask(FeederConfigurationWizard.this, footprint, current);
			if (rotation != null) {
				// Apply then stores it in the feeder too, as for a typed rotation
				rotOffsetTf.setText(new DoubleConverter(Configuration.get().getLengthDisplayFormat()).convertForward(rotation));
			}
		}
	};

	private final Action scanPartAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.ScanPartAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.ScanPartAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			ScanPartDialog.Choice choice = ScanPartDialog.ask(feeder);
			if (choice == null) {
				return;
			}
			// Show what gets loaded in the fields, then load it the same way as Load Reel
			partCb.setSelectedItem(choice.part);
			tapeWidthTf.setText(Integer.toString(choice.tapeWidthMm));
			partPitchTf.setText(Integer.toString(choice.pitchMm));
			recordCountTf.setText(Long.toString(choice.quantity));
			if (choice.rotationInTape != null) {
				rotOffsetTf.setText(new DoubleConverter(Configuration.get().getLengthDisplayFormat())
						.convertForward(choice.rotationInTape));
			}
			apply();
			submitSavedTask(() -> feeder.loadRecord(choice.quantity));
		}
	};

	private final Action setPartsLeftAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.SetPartsLeftAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.SetPartsLeftAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.messageBoxOnException(() -> {
				long count = parseRecordCount();
				submitSavedTask(() -> feeder.setRecordPartsLeft(count));
			});
		}
	};

	private final Action clearRecordAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.ClearRecordAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.ClearRecordAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			submitSavedTask(feeder::clearRecord);
		}
	};

	private final Action setLowPartsAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.SetLowPartsAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.SetLowPartsAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.messageBoxOnException(() -> {
				int setting = parseLowParts();
				submitSavedTask(() -> feeder.setLowParts(setting));
			});
		}
	};

	private final Action readVersionAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.ReadVersionAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(() -> {
				String version = PhotonFirmwareUpdater.readVersionQuietly(feeder);
				SwingUtilities.invokeLater(() -> firmwareVersionValue.setText(version == null
						? Translations.getString("FeederConfigurationWizard.FirmwarePanel.noVersion.text") //$NON-NLS-1$
						: version));
			});
		}
	};

	private final Action updateFirmwareAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.UpdateFirmwareAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.UpdateFirmwareAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			FirmwareUpdateDialog.start(feeder);
		}
	};

	private final Action readFeedScaleAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.ReadFeedScaleAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(() -> {
				if (feeder.readFeedScale() == null) {
					SwingUtilities.invokeLater(() -> feedScaleValue.setText(
							Translations.getString("FeederConfigurationWizard.DrivePanel.noFeedScale.text"))); //$NON-NLS-1$
				}
			});
		}
	};

	private final Action calibrateFeedAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			int pitch = feeder.getPartPitch();
			int parts = pitch > 0 ? PhotonFeedCalibrator.partsToFeed(pitch) + 1 : 0;
			int answer = JOptionPane.showConfirmDialog(MainFrame.get(), String.format(
					Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.confirm.text"), parts), //$NON-NLS-1$
					Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.Name"), //$NON-NLS-1$
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (answer != JOptionPane.OK_OPTION) {
				return;
			}
			UiUtils.submitUiMachineTask(() -> {
				Camera camera = Configuration.get().getMachine().getDefaultHead().getDefaultCamera();
				PhotonProperties photonProperties = new PhotonProperties(Configuration.get().getMachine());
				PhotonSprocketFinder finder = new PhotonSprocketFinder(camera, photonProperties.getAutoSetupPipeline(), 0);
				PhotonFeedCalibrator.TapeTracker tracker = new PhotonFeedCalibrator.TapeTracker() {
					@Override
					public Location[] find(PhotonFeeder f, Location pickEstimate) throws Exception {
						return finder.measure(f, pickEstimate);
					}

					@Override
					public Location[] track(PhotonFeeder f, Location[] previous) throws Exception {
						return finder.track(f, previous);
					}
				};
				return new PhotonFeedCalibrator(tracker, PhotonFeedCalibrator.FEEDER_MOVER).calibrate(feeder, null);
			}, result -> MessageBoxes.infoBox(Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.Name"), //$NON-NLS-1$
					String.format(java.util.Locale.US,
							Translations.getString("FeederConfigurationWizard.CalibrateFeedAction.done.text"), //$NON-NLS-1$
							result.askedMm, result.measuredMm, result.getErrorPercent(),
							result.scaleBefore, result.scaleAfter == null ? Double.NaN : result.scaleAfter)),
					UiUtils::showError);
		}
	};

	private final Action calibrateRatchetAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.CalibrateRatchetAction.Name")) { //$NON-NLS-1$
		{
			putValue(SHORT_DESCRIPTION, Translations.getString("FeederConfigurationWizard.CalibrateRatchetAction.ShortDescription")); //$NON-NLS-1$
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(feeder::calibrateRatchet,
					gap -> MessageBoxes.infoBox(Translations.getString("FeederConfigurationWizard.CalibrateRatchetAction.Name"), //$NON-NLS-1$
							String.format(Translations.getString("FeederConfigurationWizard.CalibrateRatchetAction.done.text"), gap)), //$NON-NLS-1$
					UiUtils::showError);
		}
	};

	private final Action feedOneMmAction = new AbstractAction(Translations.getString("FeederConfigurationWizard.FeedOneMmAction.Name")) { //$NON-NLS-1$
		@Override
		public void actionPerformed(ActionEvent e) {
			UiUtils.submitUiMachineTask(() -> {
				feeder.feedOneMm();
			});
		}
	};
}
