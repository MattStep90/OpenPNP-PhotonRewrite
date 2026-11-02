package org.openpnp.machine.photon.sheets.gui;

import org.openpnp.machine.photon.PhotonPin1Quadrant;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

/**
 * Asks for the pin 1 quadrant from the datasheet's tape drawing and turns it into the feeder's
 * rotation in tape. The drawing matches the datasheets: sprocket holes on top, feed to the right.
 * The part is drawn in the second pocket at the resulting rotation, pin 1 filled, to check
 * against the datasheet. A typed rotation is accepted too.
 */
public class Pin1QuadrantDialog extends JDialog {
    private static final String TITLE = "Pin 1 Quadrant";
    private static final Color TAPE = new Color(0xF5F5F5);
    private static final Color OUTLINE = new Color(0x424242);
    private static final Color SELECTED = new Color(0x1E88E5);
    private static final Color PAD = new Color(0x00ACC1);

    private final Footprint footprint;
    private final JRadioButton[] quadrantButtons = new JRadioButton[4];
    private final JTextField rotationTf = new JTextField(6);
    private final JLabel noteLabel = new JLabel(" ");
    private final TapeView tapeView = new TapeView();
    private Double result;

    private Pin1QuadrantDialog(Component parent, Footprint footprint, Double current) {
        super(SwingUtilities.getWindowAncestor(parent), TITLE, ModalityType.APPLICATION_MODAL);
        this.footprint = footprint;

        ButtonGroup group = new ButtonGroup();
        JPanel quadrants = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        quadrants.add(new JLabel("Pin 1 in"));
        for (int i = 0; i < 4; i++) {
            int quadrant = i + 1;
            quadrantButtons[i] = new JRadioButton("Q" + quadrant);
            quadrantButtons[i].addActionListener(e -> pick(quadrant));
            group.add(quadrantButtons[i]);
            quadrants.add(quadrantButtons[i]);
        }
        boolean decides = PhotonPin1Quadrant.rotationFor(1, footprint) != null;
        for (JRadioButton button : quadrantButtons) {
            button.setEnabled(decides);
        }

        JPanel rotation = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rotation.add(new JLabel("Rotation In Tape"));
        rotation.add(rotationTf);
        rotation.add(new JLabel("degrees"));
        rotationTf.addActionListener(e -> typed());
        rotationTf.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                typed();
            }
        });

        // Let the note use the dialog's full width, not the width of its first text
        noteLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, noteLabel.getPreferredSize().height));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        for (JComponent row : new JComponent[]{quadrants, rotation, noteLabel}) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(row);
        }

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> ok());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel south = new JPanel(new BorderLayout());
        south.add(form, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(new JLabel("<html>Find the datasheet's \"pin 1 quadrant\" for this part's reel "
                + "(EIA-481, often under Tape and Reel Information) and pick it here.<br>"
                + "The part is drawn in the second pocket as it will sit in the tape. "
                + "Check pin 1 (filled) against the datasheet.</html>"), BorderLayout.NORTH);
        panel.add(tapeView, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        setContentPane(panel);
        getRootPane().setDefaultButton(okButton);

        if (current != null) {
            rotationTf.setText(format(current));
            Integer q = PhotonPin1Quadrant.quadrantOf(current, footprint);
            if (q != null) {
                quadrantButtons[q - 1].setSelected(true);
            }
        }
        if (!decides) {
            note(footprint == null || PhotonPin1Quadrant.pin1(footprint) == null
                    ? "This part's footprint has no pin 1 pad, so type the rotation."
                    : "Pin 1 is on the footprint's centre line, so a quadrant can't decide the rotation. Type it.", true);
        }
        pack();
        setLocationRelativeTo(getOwner());
    }

    private void pick(int quadrant) {
        Double rotation = PhotonPin1Quadrant.rotationFor(quadrant, footprint);
        if (rotation == null) {
            return;
        }
        rotationTf.setText(format(rotation));
        note(String.format("Q%d gives a rotation in tape of %s degrees.", quadrant, format(rotation)), false);
        tapeView.repaint();
    }

    private void typed() {
        Double rotation = parse();
        if (rotation == null) {
            return;
        }
        Integer q = PhotonPin1Quadrant.quadrantOf(rotation, footprint);
        for (int i = 0; i < 4; i++) {
            quadrantButtons[i].setSelected(q != null && q == i + 1);
        }
        tapeView.repaint();
    }

    private Double parse() {
        String text = rotationTf.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private void ok() {
        Double rotation = parse();
        if (rotation == null) {
            note("Pick a quadrant or type the rotation in degrees.", true);
            return;
        }
        result = rotation;
        dispose();
    }

    private void note(String text, boolean warning) {
        noteLabel.setText(text);
        noteLabel.setForeground(warning ? new Color(0xC62828) : UIManager.getColor("Label.foreground"));
    }

    private static String format(double rotation) {
        return String.format(Locale.US, "%.1f", rotation).replace(".0", "");
    }

    /**
     * Shows the dialog and returns the rotation in tape in degrees, or null if cancelled.
     * The footprint may be null, then only a typed rotation is accepted.
     */
    public static Double ask(Component parent, Footprint footprint, Double current) {
        Pin1QuadrantDialog dialog = new Pin1QuadrantDialog(parent, footprint, current);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * The datasheet's tape drawing: holes on top, two pockets, feed to the right. The first
     * pocket shows the quadrants (click one to pick it), the second the part at the chosen
     * rotation.
     */
    private class TapeView extends JComponent {
        private static final int POCKET = 110;
        private static final int GAP = 40;
        private static final int MARGIN = 20;
        private static final int HOLE = 14;

        TapeView() {
            setPreferredSize(new Dimension(MARGIN * 2 + POCKET * 2 + GAP, 60 + POCKET + 50));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int q = quadrantAt(e.getX(), e.getY());
                    if (q > 0 && quadrantButtons[q - 1].isEnabled()) {
                        quadrantButtons[q - 1].setSelected(true);
                        pick(q);
                    }
                }
            });
        }

        private Rectangle pocket(int index) {
            return new Rectangle(MARGIN + index * (POCKET + GAP), 50, POCKET, POCKET);
        }

        private int quadrantAt(int x, int y) {
            Rectangle p = pocket(0);
            if (!p.contains(x, y)) {
                return 0;
            }
            boolean top = y < p.getCenterY();
            boolean left = x < p.getCenterX();
            return top ? (left ? 1 : 2) : (left ? 3 : 4);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            // Tape
            g.setColor(TAPE);
            g.fillRect(0, 10, width, POCKET + 60);
            g.setColor(OUTLINE);
            g.drawLine(0, 10, width, 10);
            g.drawLine(0, POCKET + 70, width, POCKET + 70);
            // Sprocket holes along the top
            for (int x = MARGIN / 2; x < width; x += (POCKET + GAP) / 2) {
                g.drawOval(x, 22, HOLE, HOLE);
            }
            g.drawString("Sprocket holes", width - 110, 20 + HOLE + 18);
            // Pockets
            for (int i = 0; i < 2; i++) {
                Rectangle p = pocket(i);
                g.setColor(Color.WHITE);
                g.fill(p);
                g.setColor(OUTLINE);
                g.draw(p);
            }
            // Quadrants in the first pocket
            Rectangle p = pocket(0);
            int half = POCKET / 2;
            for (int q = 1; q <= 4; q++) {
                int qx = p.x + ((q == 2 || q == 4) ? half : 0);
                int qy = p.y + (q >= 3 ? half : 0);
                if (quadrantButtons[q - 1].isSelected()) {
                    g.setColor(new Color(SELECTED.getRed(), SELECTED.getGreen(), SELECTED.getBlue(), 60));
                    g.fillRect(qx, qy, half, half);
                }
                g.setColor(OUTLINE);
                g.drawRect(qx, qy, half, half);
                g.drawString("Q" + q, qx + half / 2 - 8, qy + half / 2 + 5);
            }
            // The part in the second pocket
            Double rotation = parse();
            if (footprint != null && rotation != null) {
                drawPart(g, pocket(1), PhotonPin1Quadrant.datasheetViewRotation(rotation));
            }
            // Feed direction
            int ay = POCKET + 95;
            g.setColor(OUTLINE);
            g.drawLine(MARGIN, ay, MARGIN + 120, ay);
            g.fillPolygon(new int[]{MARGIN + 120, MARGIN + 110, MARGIN + 110},
                    new int[]{ay, ay - 5, ay + 5}, 3);
            g.drawString("Feed direction", MARGIN + 130, ay + 5);
            g.dispose();
        }

        private void drawPart(Graphics2D g, Rectangle pocket, double rotation) {
            Rectangle2D extent = null;
            for (Pad pad : footprint.getPads()) {
                Rectangle2D r = padShape(pad).getBounds2D();
                extent = extent == null ? r : extent.createUnion(r);
            }
            Rectangle2D body = new Rectangle2D.Double(-footprint.getBodyWidth() / 2, -footprint.getBodyHeight() / 2,
                    footprint.getBodyWidth(), footprint.getBodyHeight());
            extent = extent == null ? body : extent.createUnion(body);
            double size = Math.max(Math.abs(extent.getMinX()), Math.abs(extent.getMaxX()));
            size = Math.max(size, Math.max(Math.abs(extent.getMinY()), Math.abs(extent.getMaxY())));
            if (size <= 0) {
                return;
            }
            double scale = (pocket.width / 2.0 - 8) / size;
            AffineTransform t = new AffineTransform();
            t.translate(pocket.getCenterX(), pocket.getCenterY());
            t.scale(scale, -scale); // footprint +Y is up
            t.rotate(Math.toRadians(rotation));
            Graphics2D pg = (Graphics2D) g.create();
            pg.setColor(OUTLINE);
            pg.draw(t.createTransformedShape(body));
            Pad pin1 = PhotonPin1Quadrant.pin1(footprint);
            for (Pad pad : footprint.getPads()) {
                Shape shape = t.createTransformedShape(padShape(pad));
                pg.setColor(PAD);
                if (pad == pin1) {
                    pg.fill(shape);
                }
                else {
                    pg.draw(shape);
                }
            }
            pg.dispose();
        }

        private Shape padShape(Pad pad) {
            AffineTransform t = new AffineTransform();
            t.translate(pad.getX(), pad.getY());
            t.rotate(Math.toRadians(pad.getRotation()));
            return t.createTransformedShape(new Rectangle2D.Double(-pad.getWidth() / 2, -pad.getHeight() / 2,
                    pad.getWidth(), pad.getHeight()));
        }
    }
}
