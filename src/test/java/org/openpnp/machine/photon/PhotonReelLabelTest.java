package org.openpnp.machine.photon;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Part;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonReelLabelTest {
    private static Part part(String id, String name) {
        Part part = new Part(id);
        part.setName(name);
        return part;
    }

    @Test
    public void readsADigiKey2dLabel() {
        String gs = "\u001D";
        String rs = "\u001E";
        String eot = "\u0004";
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse("[)>" + rs + "06" + gs + "P311-10.0KLRCT-ND" + gs
                + "1PRC0402FR-0710KL" + gs + "K" + gs + "1K12345" + gs + "10K" + gs + "Q10000" + gs + "11ZPICK"
                + rs + eot);

        assertEquals("311-10.0KLRCT-ND", scan.pn);
        assertEquals("RC0402FR-0710KL", scan.mpn);
        assertEquals(10000L, (long) scan.quantity);
    }

    // The same cases as the loading fixture's test_labels, with Matt's real scans from a scanner
    // that sends no separators
    @Test
    public void readsADigiKey2dLabelWithoutSeparators() {
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse(
                "[)>06P311-1.00KLRTR-ND1PRC0402FR-071KLQ1000010D26171T38P01713754LTW");

        assertEquals("311-1.00KLRTR-ND", scan.pn);
        assertEquals("RC0402FR-071KL", scan.mpn);
        assertEquals(10000L, (long) scan.quantity);
        assertTrue(scan.guessed);
    }

    @Test
    public void readsARunTogetherLabelWithOrderFieldsBeforeTheQuantity() {
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse(
                "[)>06P311-1.00KLRTR-ND1PRC0402FR-071KLK1K8812345610K97531Q50011ZPICK");

        assertEquals("RC0402FR-071KL", scan.mpn);
        assertEquals(500L, (long) scan.quantity);
    }

    @Test
    public void cutsAnUnknownFieldOffWithThePartsList() {
        List<Part> parts = List.of(part("0402-RC0402FR-071KL", null), part("0402-RC0402FR-0710KL", null));
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse(
                "[)>06P311-1.00KLRTR-ND1PRC0402FR-071KL30PXYZQ100010D2617", parts);

        assertEquals("RC0402FR-071KL", scan.mpn);
        assertEquals("0402-RC0402FR-071KL", PhotonReelLabel.matchPart(parts, scan.mpn, scan.pn).getId());
    }

    @Test
    public void readsAMouserLabelWithoutSeparators() {
        // The MPN ends in "Q1" and runs straight into "Q100"
        PhotonReelLabel.Scan scan = PhotonReelLabel.parse(
                "[)>06K4058057414K0011PSN74LXC1T14QDCKRQ1Q10011K0927340264LCN1VTI");

        assertNull(scan.pn);
        assertEquals("SN74LXC1T14QDCKRQ1", scan.mpn);
        assertEquals(100L, (long) scan.quantity);
        assertTrue(scan.guessed);
    }

    @Test
    public void readsYageo1dLabels() {
        PhotonReelLabel.Scan mpn = PhotonReelLabel.parse("31PRC0402FR-071KL");
        assertEquals("RC0402FR-071KL", mpn.mpn);
        assertNull(mpn.quantity);

        PhotonReelLabel.Scan quantity = PhotonReelLabel.parse(
                "32P232270671002L1T34O503161238P0171375Q00100009D2550260126 111LFP0068");
        assertNull(quantity.mpn);
        assertNull(quantity.pn);
        assertEquals(10000L, (long) quantity.quantity);
        assertFalse(quantity.guessed);
    }

    @Test
    public void reads1dCodes() {
        assertEquals(5000L, (long) PhotonReelLabel.parse("Q5000").quantity);
        assertEquals("GRM155R71C104KA88D", PhotonReelLabel.parse("1PGRM155R71C104KA88D").mpn);
        assertEquals(250L, (long) PhotonReelLabel.parse("250").quantity);
        assertEquals("RC0402FR-0710KL", PhotonReelLabel.parse("RC0402FR-0710KL").mpn);
        assertTrue(PhotonReelLabel.parse("  ").isEmpty());
    }

    @Test
    public void matchesParts() {
        List<Part> parts = List.of(part("R0402-10K", "RC0402FR-0710KL"), part("GRM155R71C104KA88D", null),
                part("R0402-RC0402FR-0710KL", null), part("C0402-100N", null));

        assertEquals("R0402-10K", PhotonReelLabel.matchPart(parts, "RC0402FR-0710KL").getId(), "by name");
        assertEquals("GRM155R71C104KA88D", PhotonReelLabel.matchPart(parts, "grm155r71c104ka88d").getId(), "any case");
        assertNull(PhotonReelLabel.matchPart(parts, "0402"), "several fit");
        assertEquals("C0402-100N", PhotonReelLabel.matchPart(parts, null, "100N").getId(), "falls back to the next number");
        assertEquals("R0402-RC0402FR-0710KL", PhotonReelLabel.matchPart(
                List.of(part("R0402-RC0402FR-0710KL", null)), "RC0402FR-0710KL").getId(), "package + MPN ID");
    }

    @Test
    public void knowsTheTape() {
        PhotonReelLabel.Tape tape = PhotonReelLabel.tapeFor("R_0402_1005Metric", "x", null);
        assertEquals(8, tape.widthMm);
        assertEquals(2, tape.pitchMm);
        assertEquals(4, PhotonReelLabel.tapeFor("", "SOT-23-6-TPS62177", null).pitchMm);
        assertEquals(12, PhotonReelLabel.tapeFor("SOIC-8", "x", null).widthMm);
        assertNull(PhotonReelLabel.tapeFor("QFN-32", "x", null));
        PhotonReelLabel.Tape inUse = new PhotonReelLabel.Tape(16, 12, "as used for SOIC-8");
        assertSame(inUse, PhotonReelLabel.tapeFor("SOIC-8", "x", inUse), "a tape in use wins");
    }

    @Test
    public void findsIdsTheFeederCouldNotTellApart() {
        List<Part> parts = List.of(part("C_0402_1005Metric-GRM155R71C104KA88D", null),
                part("C_0402_1005Metric-GRM155R71C105KA12D", null));

        assertTrue(PhotonReelLabel.truncationClash(parts, parts.get(0).getId()).isEmpty(), "64 characters tell them apart");
        assertEquals(1, PhotonReelLabel.truncationClash(parts, parts.get(0).getId(), 24).size(), "older firmware's 24 don't");
        assertTrue(PhotonReelLabel.truncationClash(parts.subList(0, 1), parts.get(0).getId(), 24).isEmpty());
        String head = "x".repeat(64);
        assertEquals(1, PhotonReelLabel.truncationClash(List.of(part(head + "A", null), part(head + "B", null)),
                head + "A").size());
        assertTrue(PhotonReelLabel.truncationClash(parts, "R0402-10K").isEmpty());
    }
}
