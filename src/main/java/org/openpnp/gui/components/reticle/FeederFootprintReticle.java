package org.openpnp.gui.components.reticle;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;

/**
 * Draws the footprint of a feeder's part turned to the feeder's pick rotation, so with the
 * camera at the pick location it should lie over the part in the pocket. Pin 1 (the marked pad,
 * else the pad named 1) is filled. Reads the feeder on every frame, so applied changes to the
 * part or rotation show at once.
 */
public class FeederFootprintReticle implements Reticle {
    private final Feeder feeder;
    private final Color color = Color.cyan;

    public FeederFootprintReticle(Feeder feeder) {
        this.feeder = feeder;
    }

    @Override
    public void draw(Graphics2D g2d, LengthUnit cameraUnitsPerPixelUnits,
            double cameraUnitsPerPixelX, double cameraUnitsPerPixelY, double viewPortCenterX,
            double viewPortCenterY, int viewPortWidth, int viewPortHeight, double rotation) {
        // Only while setting up feeders, not over a job's fiducials
        MainFrame mainFrame = MainFrame.get();
        if (mainFrame == null || mainFrame.getTabs().getSelectedComponent() != mainFrame.getFeedersTab()) {
            return;
        }
        Footprint footprint = footprint(feeder);
        Double pickRotation = pickRotation(feeder);
        if (footprint == null || footprint.getPads().isEmpty() || pickRotation == null) {
            return;
        }

        Length l = new Length(1, footprint.getUnits()).convertToUnits(cameraUnitsPerPixelUnits);
        double unitScale = l.getValue();
        AffineTransform tx = new AffineTransform();
        tx.translate(viewPortCenterX, viewPortCenterY);
        // Left-hand coordinate system rotates positive clockwise, so we invert the value.
        tx.rotate(Math.toRadians(-pickRotation));
        // Left-hand coordinate system, Y inverted.
        tx.scale(unitScale / cameraUnitsPerPixelX, -unitScale / cameraUnitsPerPixelY);

        g2d.setStroke(new BasicStroke(1f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(color);
        g2d.draw(tx.createTransformedShape(footprint.getPadsShape()));
        Pad pin1 = pin1(footprint);
        if (pin1 != null) {
            Path2D.Double shape = new Path2D.Double();
            shape.append(pin1.getShape(), false);
            g2d.fill(tx.createTransformedShape(shape));
        }
    }

    static Footprint footprint(Feeder feeder) {
        Part part = feeder.getPart();
        Package pkg = part == null ? null : part.getPackage();
        return pkg == null ? null : pkg.getFootprint();
    }

    /**
     * The rotation of the part in the feeder, or null if the feeder has no pick location yet.
     * Only feeders whose pick location is plain configuration are asked: others may move or
     * use vision to work it out.
     */
    static Double pickRotation(Feeder feeder) {
        try {
            Location location = null;
            if (feeder instanceof PhotonFeeder) {
                location = feeder.getPickLocation();
            }
            else if (feeder instanceof ReferenceFeeder) {
                location = ((ReferenceFeeder) feeder).getLocation();
            }
            return location == null ? null : location.getRotation();
        }
        catch (Exception e) {
            return null;
        }
    }

    public static Pad pin1(Footprint footprint) {
        for (Pad pad : footprint.getPads()) {
            if (pad.getMark()) {
                return pad;
            }
        }
        for (Pad pad : footprint.getPads()) {
            if ("1".equals(pad.getName())) {
                return pad;
            }
        }
        return null;
    }
}
