package org.openpnp.machine.photon.sheets.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFirmwareBatch;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;

/**
 * Updates the firmware of every Photon feeder in a slot, one after the other, with the Stock or
 * Ratchet image to match each feeder. Shows which feeder is being written and when power must
 * not be removed, and lists the outcome for every feeder at the end.
 */
public class FirmwareUpdateAllDialog extends JDialog {
    private static final String TITLE = "Update All Feeder Firmware";
    private static final String LAST_DIRECTORY = "PhotonFirmwareUpdateDirectory";
    private static final Color DANGER = new Color(0xC62828);

    private final JLabel feederLabel = new JLabel(" ");
    private final JLabel stageLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JLabel powerLabel = new JLabel(" ");
    private final JButton stopButton = new JButton("Stop After This Feeder");
    private volatile boolean stopRequested = false;

    private FirmwareUpdateAllDialog() {
        super(MainFrame.get(), TITLE, false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel labels = new JPanel();
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        feederLabel.setFont(feederLabel.getFont().deriveFont(Font.BOLD));
        labels.add(feederLabel);
        labels.add(stageLabel);

        JPanel bottom = new JPanel(new BorderLayout());
        powerLabel.setFont(powerLabel.getFont().deriveFont(Font.BOLD, powerLabel.getFont().getSize2D() + 2f));
        powerLabel.setForeground(DANGER);
        bottom.add(powerLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        stopButton.addActionListener(e -> {
            stopRequested = true;
            stopButton.setEnabled(false);
            stopButton.setText("Stopping After This Feeder");
        });
        buttons.add(stopButton);
        bottom.add(buttons, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        panel.add(labels, BorderLayout.NORTH);
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        setContentPane(panel);
        setSize(560, 210);
        setLocationRelativeTo(getOwner());
    }

    private void show(int index, int count, PhotonFeeder feeder, String stage, double fraction, boolean critical) {
        SwingUtilities.invokeLater(() -> {
            feederLabel.setText(String.format("Feeder %d of %d: %s", index + 1, count, feeder.getName()));
            stageLabel.setText(stage);
            if (fraction < 0) {
                progressBar.setIndeterminate(true);
                progressBar.setString("");
            }
            else {
                progressBar.setIndeterminate(false);
                progressBar.setValue((int) Math.round(fraction * 1000));
                progressBar.setString(String.format("%.0f %%", fraction * 100));
            }
            powerLabel.setText(critical ? "Don't remove power or close OpenPnP." : " ");
        });
    }

    /**
     * Asks for the firmware files and, once confirmed, updates every feeder in a machine task.
     */
    public static void start() {
        List<PhotonFeeder> feeders = PhotonFirmwareBatch.feedersInSlots(Configuration.get().getMachine().getFeeders());
        if (feeders.isEmpty()) {
            MessageBoxes.errorBox(MainFrame.get(), TITLE, "No Photon feeder has a slot. Run Search first.");
            return;
        }

        Preferences preferences = Preferences.userNodeForPackage(FirmwareUpdateDialog.class);
        JFileChooser chooser = new JFileChooser(preferences.get(LAST_DIRECTORY, null));
        chooser.setDialogTitle(TITLE + ": choose the Stock and/or Ratchet .bin");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Feeder firmware (*.bin)", "bin"));
        if (chooser.showOpenDialog(MainFrame.get()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        if (files.length == 0) {
            return;
        }
        preferences.put(LAST_DIRECTORY, files[0].getParent());

        Map<PhotonFirmwareBatch.Drive, byte[]> images = new EnumMap<>(PhotonFirmwareBatch.Drive.class);
        Map<PhotonFirmwareBatch.Drive, String> names = new EnumMap<>(PhotonFirmwareBatch.Drive.class);
        try {
            for (File file : files) {
                PhotonFirmwareBatch.Drive drive = PhotonFirmwareBatch.driveOfFileName(file.getName());
                if (drive == null) {
                    throw new Exception(file.getName() + " doesn't say Stock or Ratchet in its name, "
                            + "so it can't be matched to feeders.");
                }
                if (images.containsKey(drive)) {
                    throw new Exception("Choose one " + drive.label() + " file, not two.");
                }
                byte[] image = Files.readAllBytes(file.toPath());
                if (image.length == 0) {
                    throw new Exception(file.getName() + " is empty.");
                }
                images.put(drive, image);
                names.put(drive, file.getName());
            }
        }
        catch (Exception e) {
            MessageBoxes.errorBox(MainFrame.get(), TITLE, e.getMessage());
            return;
        }

        StringBuilder chosen = new StringBuilder();
        for (Map.Entry<PhotonFirmwareBatch.Drive, String> entry : names.entrySet()) {
            chosen.append(String.format("%s feeders: <b>%s</b><br>", entry.getKey().label(), entry.getValue()));
        }
        int answer = JOptionPane.showConfirmDialog(MainFrame.get(), String.format(
                "<html><body style='width: 400px'>"
                + "Update the firmware of %d feeders, one at a time?<br><br>%s<br>"
                + "Each feeder gets the file for its drive, and feeders already running it are left alone. "
                + "Each one takes about a minute.<br><br>"
                + "<b>Don't remove power or close OpenPnP until it finishes.</b> "
                + "A feeder that gets interrupted shows a magenta LED; run the update again to finish it.</body></html>",
                feeders.size(), chosen),
                TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        FirmwareUpdateAllDialog dialog = new FirmwareUpdateAllDialog();
        dialog.setVisible(true);

        UiUtils.submitUiMachineTask(
                () -> PhotonFirmwareBatch.forMachine().run(feeders, images, dialog::show, () -> dialog.stopRequested),
                results -> {
                    dialog.dispose();
                    showResults(results);
                },
                throwable -> {
                    dialog.dispose();
                    MessageBoxes.errorBox(MainFrame.get(), TITLE, throwable);
                });
    }

    private static void showResults(List<PhotonFirmwareBatch.Result> results) {
        String[] columns = {"Slot", "Feeder", "Before", "After", "Result"};
        Object[][] rows = new Object[results.size()][];
        int failed = 0;
        int updated = 0;
        for (int i = 0; i < results.size(); i++) {
            PhotonFirmwareBatch.Result result = results.get(i);
            rows[i] = new Object[]{
                    result.slotAddress,
                    result.feeder.getName(),
                    result.versionBefore == null ? "" : result.versionBefore,
                    result.versionAfter == null ? "" : result.versionAfter,
                    result.outcome};
            if (result.failed) {
                failed++;
            }
            else if ("Updated".equals(result.outcome)) {
                updated++;
            }
        }

        JTable table = new JTable(rows, columns);
        table.setEnabled(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(820, Math.min(420, 40 + 20 * results.size())));

        String summary = String.format("%d updated, %d failed, %d other.", updated, failed,
                results.size() - updated - failed);
        if (failed > 0) {
            summary += " Failed feeders may be waiting in their bootloader (magenta LED). Leave them powered and run the update again.";
        }
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(summary), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(MainFrame.get(), panel, TITLE,
                failed == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }
}
