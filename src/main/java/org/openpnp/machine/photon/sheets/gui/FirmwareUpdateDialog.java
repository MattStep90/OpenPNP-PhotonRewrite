package org.openpnp.machine.photon.sheets.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonFirmwareUpdater;
import org.openpnp.util.UiUtils;

/**
 * Picks a firmware file, confirms, and updates one Photon feeder while showing what is happening
 * and when power must not be removed.
 */
public class FirmwareUpdateDialog extends JDialog {
    private static final String TITLE = "Update Feeder Firmware";
    private static final String LAST_DIRECTORY = "PhotonFirmwareUpdateDirectory";
    private static final Color DANGER = new Color(0xC62828);
    private static final Color SAFE = new Color(0x2E7D32);

    private final JLabel stageLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JLabel powerLabel = new JLabel(" ");
    private boolean powerCritical = false;

    private FirmwareUpdateDialog(String feederName) {
        super(MainFrame.get(), TITLE + ": " + feederName, false);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        panel.add(stageLabel, BorderLayout.NORTH);
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);
        powerLabel.setFont(powerLabel.getFont().deriveFont(Font.BOLD, powerLabel.getFont().getSize2D() + 2f));
        panel.add(powerLabel, BorderLayout.SOUTH);
        setContentPane(panel);
        setSize(520, 150);
        setLocationRelativeTo(getOwner());
    }

    private void show(String stage, double fraction, boolean critical) {
        SwingUtilities.invokeLater(() -> {
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
            if (critical) {
                powerLabel.setForeground(DANGER);
                powerLabel.setText("Don't remove power from the feeder or close OpenPnP.");
            }
            else if (powerCritical) {
                powerLabel.setForeground(SAFE);
                powerLabel.setText("The new firmware is in place.");
            }
            powerCritical = powerCritical || critical;
        });
    }

    /**
     * Asks for a firmware file and, once confirmed, updates the feeder in a machine task.
     */
    public static void start(PhotonFeeder feeder) {
        if (feeder.getSlotAddress() == null) {
            MessageBoxes.errorBox(MainFrame.get(), TITLE, "Find the feeder's slot first.");
            return;
        }

        Preferences preferences = Preferences.userNodeForPackage(FirmwareUpdateDialog.class);
        JFileChooser chooser = new JFileChooser(preferences.get(LAST_DIRECTORY, null));
        chooser.setDialogTitle(TITLE);
        chooser.setFileFilter(new FileNameExtensionFilter("Feeder firmware (*.bin)", "bin"));
        if (chooser.showOpenDialog(MainFrame.get()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        preferences.put(LAST_DIRECTORY, file.getParent());

        byte[] image;
        try {
            image = Files.readAllBytes(file.toPath());
        }
        catch (Exception e) {
            MessageBoxes.errorBox(MainFrame.get(), TITLE, e);
            return;
        }
        if (image.length == 0) {
            MessageBoxes.errorBox(MainFrame.get(), TITLE, file.getName() + " is empty.");
            return;
        }

        int answer = JOptionPane.showConfirmDialog(MainFrame.get(), String.format(
                "<html><body style='width: 380px'>"
                + "Write <b>%s</b> (%d bytes) to feeder %s in slot %d?<br><br>"
                + "The other feeders stay quiet while it runs, which takes about a minute.<br><br>"
                + "<b>Don't remove power from the feeder, unplug it or close OpenPnP until it finishes.</b> "
                + "If it is interrupted, the feeder's LED turns magenta and it waits in its bootloader; "
                + "run the update again to finish it.</body></html>",
                file.getName(), image.length, feeder.getName(), feeder.getSlotAddress()),
                TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        FirmwareUpdateDialog dialog = new FirmwareUpdateDialog(feeder.getName());
        dialog.show("Starting", -1, false);
        dialog.setVisible(true);

        UiUtils.submitUiMachineTask(() -> {
            String before = PhotonFirmwareUpdater.readVersionQuietly(feeder);
            PhotonFirmwareUpdater.Outcome outcome =
                    PhotonFirmwareUpdater.forFeeder(feeder, dialog::show).update(image);
            String after = PhotonFirmwareUpdater.readVersionQuietly(feeder);
            return new String[]{outcome.name(), before, after};
        }, result -> {
            dialog.dispose();
            String message = PhotonFirmwareUpdater.Outcome.valueOf(result[0]) == PhotonFirmwareUpdater.Outcome.UPDATED
                    ? "The feeder is running the new firmware. It is safe to remove power."
                    : "The feeder already runs this firmware, so nothing was written.";
            if (result[2] != null) {
                message += "\n\nVersion: " + (result[1] != null && !result[1].equals(result[2])
                        ? result[1] + " -> " + result[2] : result[2]);
            }
            MessageBoxes.infoBox(TITLE, message);
        }, throwable -> {
            boolean wasCritical = dialog.powerCritical;
            dialog.dispose();
            String message = throwable.getMessage();
            if (wasCritical) {
                message += "\n\nThe feeder has no working firmware until the update finishes. "
                        + "Leave it powered and run the update again.";
            }
            MessageBoxes.errorBox(MainFrame.get(), TITLE, message);
        });
    }
}
