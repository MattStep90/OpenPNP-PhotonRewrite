package org.openpnp.machine.photon.sheets.gui;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonSlotRepair;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gives slots that lost their ID their address back, with all the feeders left in, see
 * {@link PhotonSlotRepair}. Each feeder to fix blinks, and the user says which slot it's in.
 */
public class FixLostSlotsDialog {
    private static final String TITLE = "Fix Lost Slots";

    private FixLostSlotsDialog() {
    }

    public static void start(JButton button, Runnable afterRun) {
        if (!Configuration.get().getMachine().isEnabled()) {
            UiUtils.showError(new Exception("Please connect to the machine first."));
            return;
        }
        int answer = JOptionPane.showConfirmDialog(MainFrame.get(),
                "<html><body style='width: 400px'>Every feeder OpenPnP knows is asked which slot it's in. "
                + "A feeder whose slot lost its ID (blue LED) blinks, and you say which slot it's in. "
                + "Its slot ID is then written again.<br><br>"
                + "After that, one feeder OpenPnP has never seen can be found too. "
                + "Insert new feeders one at a time.</body></html>",
                TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        String text = button.getText();
        button.setEnabled(false);
        UiUtils.submitUiMachineTask(
                () -> PhotonSlotRepair.forMachine().run(FixLostSlotsDialog::askSlot,
                        stage -> SwingUtilities.invokeLater(() -> button.setText(stage))),
                results -> {
                    button.setText(text);
                    button.setEnabled(true);
                    if (afterRun != null) {
                        afterRun.run();
                    }
                    showResults(results);
                },
                throwable -> {
                    button.setText(text);
                    button.setEnabled(true);
                    MessageBoxes.errorBox(MainFrame.get(), TITLE, throwable);
                });
    }

    // Runs on the machine thread, so the question goes to the UI thread and waits for the answer.
    private static Integer askSlot(PhotonFeeder feeder, boolean isNew, Integer suggested, Set<Integer> taken) {
        AtomicReference<Integer> answer = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> answer.set(showAsk(feeder, isNew, suggested, taken)));
        }
        catch (Exception e) {
            return null;
        }
        return answer.get();
    }

    private static Integer showAsk(PhotonFeeder feeder, boolean isNew, Integer suggested, Set<Integer> taken) {
        String part = feeder.getPart() == null ? "" : " holding " + feeder.getPart().getId();
        String message = String.format("<html><body style='width: 360px'>%s %s%s is flashing white, and keeps flashing until you answer.<br><br>"
                + "Which slot is it in?%s</body></html>",
                isNew ? "A new feeder," : "Feeder", feeder.getHardwareId(), isNew ? "," : part,
                suggested == null ? "" : String.format("<br>It was last found in slot %d.", suggested));
        while (true) {
            Object value = JOptionPane.showInputDialog(MainFrame.get(), message, TITLE,
                    JOptionPane.QUESTION_MESSAGE, null, null, suggested == null ? "" : suggested.toString());
            if (value == null) {
                return null;
            }
            try {
                int slot = Integer.parseInt(value.toString().trim());
                if (taken.contains(slot)) {
                    MessageBoxes.errorBox(MainFrame.get(), TITLE, String.format(
                            "Slot %d is in use by another feeder. Check the slot number.", slot));
                    continue;
                }
                if (slot < 1 || slot > PhotonSlotRepair.MAX_SLOT_ADDRESS) {
                    MessageBoxes.errorBox(MainFrame.get(), TITLE,
                            "Enter a slot from 1 to " + PhotonSlotRepair.MAX_SLOT_ADDRESS + ".");
                    continue;
                }
                return slot;
            }
            catch (NumberFormatException e) {
                MessageBoxes.errorBox(MainFrame.get(), TITLE, "Enter the slot number.");
            }
        }
    }

    private static void showResults(List<PhotonSlotRepair.Result> results) {
        if (results.isEmpty()) {
            JOptionPane.showMessageDialog(MainFrame.get(), "Every feeder that answered has its slot. "
                    + "No new feeder answered.", TITLE, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] columns = {"Feeder", "Was", "Now", "Result"};
        Object[][] rows = new Object[results.size()][];
        boolean failed = false;
        for (int i = 0; i < results.size(); i++) {
            PhotonSlotRepair.Result result = results.get(i);
            rows[i] = new Object[]{
                    result.feeder.getHardwareId(),
                    result.before == null ? "no slot" : result.before,
                    result.after == null ? "" : result.after,
                    result.outcome};
            failed |= result.failed;
        }
        JTable table = new JTable(rows, columns);
        table.setEnabled(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(760, Math.min(400, 40 + 20 * results.size())));
        JOptionPane.showMessageDialog(MainFrame.get(), scroll, TITLE,
                failed ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }
}
