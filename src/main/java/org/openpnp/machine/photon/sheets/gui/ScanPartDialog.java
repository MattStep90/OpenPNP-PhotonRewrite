package org.openpnp.machine.photon.sheets.gui;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.PhotonReelLabel;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Part;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scan a reel label with a USB barcode scanner to fill in what to load into a Photon feeder:
 * the part, quantity, tape width and pitch. The values are shown to check before loading. The
 * rotation in tape can be typed or picked from the datasheet's pin 1 quadrant.
 *
 * A keyboard-mode scanner types the GS field separator of a 2D label as Ctrl+], which a text
 * field would drop, so those keys (and Ctrl+^ for RS, Ctrl+D for EOT) are put in by hand.
 */
public class ScanPartDialog extends JDialog {
    public static class Choice {
        public final Part part;
        public final int tapeWidthMm;
        public final int pitchMm;
        public final long quantity;
        /** Degrees, or null to keep the feeder's current rotation in tape. */
        public final Double rotationInTape;

        Choice(Part part, int tapeWidthMm, int pitchMm, long quantity, Double rotationInTape) {
            this.part = part;
            this.tapeWidthMm = tapeWidthMm;
            this.pitchMm = pitchMm;
            this.quantity = quantity;
            this.rotationInTape = rotationInTape;
        }
    }

    private static final String TITLE = "Scan Part";

    private final PhotonFeeder feeder;
    private final List<Part> parts;
    private final JTextField scanField = new JTextField(36);
    private final JComboBox<Part> partCb;
    private final JTextField quantityTf = new JTextField(8);
    private final JTextField tapeWidthTf = new JTextField(4);
    private final JTextField pitchTf = new JTextField(4);
    private final JTextField rotationTf = new JTextField(6);
    private final JLabel noteLabel = new JLabel(" ");
    private Choice choice;

    private ScanPartDialog(PhotonFeeder feeder) {
        super(MainFrame.get(), TITLE, true);
        this.feeder = feeder;
        this.parts = new ArrayList<>(Configuration.get().getParts());
        this.parts.sort(Comparator.comparing(Part::getId, String.CASE_INSENSITIVE_ORDER));

        scanField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                Character control = controlCharacter(e);
                if (control != null) {
                    scanField.replaceSelection(String.valueOf(control));
                    e.consume();
                }
            }
        });
        scanField.addActionListener(e -> readScan());

        partCb = new JComboBox<>(parts.toArray(new Part[0]));
        partCb.setRenderer(new IdentifiableListCellRenderer<>());
        partCb.setSelectedItem(feeder.getPart());
        partCb.addActionListener(e -> {
            suggestTape();
            suggestRotation();
        });

        JButton quadrantButton = new JButton("Pin 1 Quadrant...");
        quadrantButton.addActionListener(e -> askQuadrant());
        JPanel rotationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rotationRow.add(rotationTf);
        rotationRow.add(Box.createHorizontalStrut(6));
        rotationRow.add(quadrantButton);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, c, 0, "Scan", scanField);
        addRow(form, c, 1, "Part", partCb);
        addRow(form, c, 2, "Quantity", quantityTf);
        addRow(form, c, 3, "Tape Width (mm)", tapeWidthTf);
        addRow(form, c, 4, "Part Pitch (mm)", pitchTf);
        addRow(form, c, 5, "Rotation In Tape", rotationRow);
        c.gridx = 1;
        c.gridy = 6;
        form.add(noteLabel, c);

        JButton loadButton = new JButton("Load Reel");
        loadButton.addActionListener(e -> load());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(loadButton);
        buttons.add(cancelButton);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(new JLabel("<html>Scan the reel's label (the 2D code holds the part number and quantity), "
                + "or type a part number and press Enter.<br>Check the values, then press Load Reel.</html>"),
                BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        setContentPane(panel);
        getRootPane().setDefaultButton(null);
        pack();
        setLocationRelativeTo(getOwner());

        suggestTape();
        suggestRotation();
    }

    /**
     * The feeder's current rotation in tape if it already holds this part, else blank: a new
     * part's rotation isn't known until it's picked from the datasheet or typed.
     */
    private void suggestRotation() {
        Part part = (Part) partCb.getSelectedItem();
        if (part != null && part == feeder.getPart() && feeder.getOffset() != null) {
            rotationTf.setText(formatRotation(feeder.getOffset().getRotation()));
        }
        else {
            rotationTf.setText("");
        }
    }

    private void askQuadrant() {
        Part part = (Part) partCb.getSelectedItem();
        Footprint footprint = part == null || part.getPackage() == null ? null : part.getPackage().getFootprint();
        Double rotation = Pin1QuadrantDialog.ask(this, footprint, parseRotation());
        if (rotation != null) {
            rotationTf.setText(formatRotation(rotation));
        }
    }

    private Double parseRotation() {
        String text = rotationTf.getText().trim();
        return text.isEmpty() ? null : Double.parseDouble(text);
    }

    private static String formatRotation(double rotation) {
        return String.format(java.util.Locale.US, "%.1f", rotation).replace(".0", "");
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    static Character controlCharacter(KeyEvent e) {
        if (!e.isControlDown()) {
            return null;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_CLOSE_BRACKET:
                return PhotonReelLabel.GS;
            case KeyEvent.VK_CIRCUMFLEX:
            case KeyEvent.VK_6:
                return PhotonReelLabel.RS;
            case KeyEvent.VK_D:
                return PhotonReelLabel.EOT;
            default:
                return null;
        }
    }

    private void readScan() {
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse(scanField.getText(), parts);
        scanField.setText("");
        if (scan.isEmpty()) {
            note("Nothing read from that scan. Try again.", true);
            return;
        }
        if (scan.quantity != null) {
            quantityTf.setText(Long.toString(scan.quantity));
        }
        if (scan.mpn == null && scan.pn == null) {
            note("Read the quantity. Scan the part number code too.", false);
            return;
        }
        Part part = PhotonReelLabel.matchPart(parts, scan.mpn, scan.pn);
        if (part == null) {
            note(String.format("No single part matches %s. Pick it from the list.",
                    scan.mpn != null ? scan.mpn : scan.pn), true);
            return;
        }
        partCb.setSelectedItem(part);
        if (scan.quantity == null) {
            note("Found the part. Scan the quantity code too, or type it in.", false);
        }
        else if (scan.guessed) {
            note(String.format("The scanner sent no field separators, so this is a best guess: %s, %d parts. "
                    + "Check the part, quantity and tape before loading.", part.getId(), scan.quantity), true);
        }
    }

    private void suggestTape() {
        Part part = (Part) partCb.getSelectedItem();
        if (part == null) {
            return;
        }
        String packageId = part.getPackage() == null ? null : part.getPackage().getId();
        PhotonReelLabel.Tape inUse = PhotonReelLabel.tapeInUse(Configuration.get().getMachine().getFeeders(),
                feeder, packageId);
        PhotonReelLabel.Tape tape = PhotonReelLabel.tapeFor(packageId, part.getId(), inUse);
        if (tape == null) {
            note("Tape size unknown for this package. Fill it in.", true);
            return;
        }
        tapeWidthTf.setText(Integer.toString(tape.widthMm));
        pitchTf.setText(Integer.toString(tape.pitchMm));
        note(String.format("Tape %d mm, pitch %d mm (%s). Check it matches the reel.",
                tape.widthMm, tape.pitchMm, tape.source), false);
    }

    private void note(String text, boolean warning) {
        noteLabel.setText(text);
        noteLabel.setForeground(warning ? new Color(0xC62828) : UIManager.getColor("Label.foreground"));
    }

    private void load() {
        Part part = (Part) partCb.getSelectedItem();
        if (part == null) {
            note("Pick the part.", true);
            return;
        }
        List<Part> clashes = PhotonReelLabel.truncationClash(parts, part.getId());
        if (!clashes.isEmpty()) {
            note(String.format("The feeder keeps 64 characters of the part ID, and %s starts the same as %s. "
                    + "Shorten one of the IDs.", part.getId(), clashes.get(0).getId()), true);
            return;
        }
        try {
            long quantity = Long.parseLong(quantityTf.getText().trim());
            int width = Integer.parseInt(tapeWidthTf.getText().trim());
            int pitch = Integer.parseInt(pitchTf.getText().trim());
            if (quantity < 0 || width <= 0 || pitch <= 0) {
                throw new NumberFormatException();
            }
            Double rotation;
            try {
                rotation = parseRotation();
            }
            catch (NumberFormatException e) {
                note("Rotation in tape must be a number of degrees, or blank to keep the current one.", true);
                return;
            }
            choice = new Choice(part, width, pitch, quantity, rotation);
            dispose();
        }
        catch (NumberFormatException e) {
            note("Quantity, tape width and pitch must be whole numbers, width and pitch above 0.", true);
        }
    }

    /**
     * Shows the dialog and returns what to load, or null if cancelled.
     */
    public static Choice ask(PhotonFeeder feeder) {
        ScanPartDialog dialog = new ScanPartDialog(feeder);
        dialog.setVisible(true);
        return dialog.choice;
    }
}
