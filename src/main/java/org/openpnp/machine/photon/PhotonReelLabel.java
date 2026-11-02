package org.openpnp.machine.photon;

import org.openpnp.model.Part;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a reel label scanned with a USB barcode scanner, which types what it reads like a
 * keyboard, and works out what to load into a Photon feeder: the OpenPnP part, the quantity and
 * the tape width and pitch. The same rules as the loading fixture's tool (labels.py).
 *
 * Distributor labels (Digi-Key, Mouser, LCSC and others) carry a 2D code in the ECIA / ISO 15434
 * format: "[)>" RS "06" GS, then fields separated by GS, each starting with a data identifier:
 * P (customer or distributor part number), 1P (manufacturer part number), Q (quantity). Single
 * field 1D codes ("Q5000", "1PRC0402FR-0710KL") work too.
 */
public class PhotonReelLabel {
    public static final char GS = '\u001D';
    public static final char RS = '\u001E';
    public static final char EOT = '\u0004';

    private static final Pattern SEPARATORS = Pattern.compile("[\\x1d\\x1e\\x04\\r\\n\\t]+");

    public static class Scan {
        public final String mpn;
        public final String pn;
        public final Long quantity;
        // The scanner dropped the field separators, so the split is a best guess to show the user
        public final boolean guessed;

        Scan(String mpn, String pn, Long quantity) {
            this(mpn, pn, quantity, false);
        }

        Scan(String mpn, String pn, Long quantity, boolean guessed) {
            this.mpn = mpn;
            this.pn = pn;
            this.quantity = quantity;
            this.guessed = guessed;
        }

        public boolean isEmpty() {
            return mpn == null && pn == null && quantity == null;
        }
    }

    public static class Tape {
        public final int widthMm;
        public final int pitchMm;
        // Where the sizes came from, to show the user
        public final String source;

        Tape(int widthMm, int pitchMm, String source) {
            this.widthMm = widthMm;
            this.pitchMm = pitchMm;
            this.source = source;
        }
    }

    // Usual tape (width mm, part pitch mm) per package, EIA-481. Some parts differ (thick 0603
    // caps on 4 mm, paper vs embossed tape), so the sizes are always shown before loading.
    // Longer names first, so SOT-23-6 is found before SOT-23.
    private static final Object[][] TAPE_DEFAULTS = {
            {"SOIC-14", 16, 8}, {"SOIC-16", 16, 8}, {"SOIC-8", 12, 8},
            {"TSSOP-8", 12, 8}, {"MSOP-8", 12, 8},
            {"SOT-223", 12, 8}, {"SOT-89", 12, 8}, {"SOT-323", 8, 4}, {"SOT-363", 8, 4},
            {"SOT-23", 8, 4}, {"SC-70", 8, 4},
            {"SOD-123", 8, 4}, {"SOD-323", 8, 4}, {"SOD-523", 8, 4},
            {"SMA", 12, 4}, {"SMB", 12, 8},
            {"2512", 12, 8}, {"2010", 12, 4}, {"1210", 8, 4}, {"1206", 8, 4},
            {"0805", 8, 4}, {"0603", 8, 4}, {"0402", 8, 2}, {"0201", 8, 2},
    };

    // Data identifiers that can follow the quantity on a reel label: date codes, lot, country of
    // origin, sales order, invoice, and Digi-Key's Z fields.
    private static final Pattern QUANTITY = Pattern.compile(
            "Q(\\d+?)(?=10D|9D|1T|4L|1V|1K|10K|11K|14K|11Z|12Z|13Z|20Z|$)");
    // Digi-Key's empty customer PO ("K") followed by its sales order ("1K" and digits)
    private static final Pattern ORDER = Pattern.compile("K1K\\d|1K\\d{6,}");
    // Manufacturer 1D part number labels (Yageo prints a leading "3")
    private static final Pattern MANUFACTURER_MPN = Pattern.compile("3?1P(.+)");

    public static Scan parse(String text) {
        return parse(text, null);
    }

    /**
     * Reads a scan. With the parts list, an MPN from a scan with no field separators is cut to
     * the longest start of it that names a part.
     */
    public static Scan parse(String text, List<Part> parts) {
        String t = text.trim();
        if (t.startsWith("[)>")) {
            t = t.substring(3);
        }
        List<String> fields = new ArrayList<>();
        for (String field : SEPARATORS.split(t)) {
            if (!field.isEmpty()) {
                fields.add(field);
            }
        }
        if (fields.size() == 1 && fields.get(0).length() > 2
                && (fields.get(0).startsWith("06") || fields.get(0).startsWith("05"))) {
            return parseRun(fields.get(0).substring(2), parts);
        }

        String mpn = null;
        String pn = null;
        Long quantity = null;
        for (String field : fields) {
            if (field.equals("06") || field.equals("05")) {
                continue;
            }
            if (field.startsWith("1P") && mpn == null) {
                mpn = field.substring(2);
            }
            else if (field.startsWith("Q") && isDigits(field.substring(1)) && quantity == null) {
                quantity = parseQuantity(field.substring(1));
            }
            else if (field.startsWith("P") && field.length() > 1 && pn == null) {
                pn = field.substring(1);
            }
        }
        if (mpn == null && pn == null && quantity == null && fields.size() == 1) {
            // A 1D code: a manufacturer part number ("31PRC0402FR-071KL"), a run holding the
            // quantity before a date code ("...Q00100009D..."), a bare count or a part number
            String field = fields.get(0);
            java.util.regex.Matcher m = MANUFACTURER_MPN.matcher(field);
            java.util.regex.Matcher q = QUANTITY.matcher(field);
            if (m.matches()) {
                mpn = m.group(1);
            }
            else if (!isDigits(field) && q.find()) {
                quantity = parseQuantity(q.group(1));
            }
            else if (isDigits(field)) {
                quantity = parseQuantity(field);
            }
            else {
                mpn = field;
            }
        }
        return new Scan(mpn, pn, quantity);
    }

    /**
     * Best guess at an ECIA label read by a scanner that drops the separators, so the fields run
     * together: Digi-Key "P311-1.00KLRTR-ND1PRC0402FR-071KLQ1000010D2617...", Mouser
     * "K4058057414K0011PSN74LXC1T14QDCKRQ1Q10011K...". A Digi-Key number ends in -ND, the
     * quantity is Q and digits followed by an identifier that comes after it, and the MPN is what
     * lies between. The same rules as the loading fixture's labels._parse_run.
     */
    private static Scan parseRun(String run, List<Part> parts) {
        String pn = null;
        String mpn = null;
        Long quantity = null;
        String rest = run;
        if (rest.startsWith("P")) {
            int nd = rest.indexOf("-ND");
            int end = nd > 0 ? nd + 3 : rest.indexOf("1P", 1);
            if (end > 1) {
                pn = rest.substring(1, end);
                rest = rest.substring(end);
            }
        }
        // Mouser puts its PO ("K") and line ("14K") first, so the MPN can start further in
        int at = rest.indexOf("1P");
        if (at >= 0) {
            rest = rest.substring(at);
        }
        // The first Q + digits that a known identifier follows. An MPN ending in Q and a digit
        // ("SN74LXC1T14QDCKRQ1Q100...") is skipped because what follows it is the real Q field.
        java.util.regex.Matcher q = QUANTITY.matcher(rest);
        boolean hasQuantity = q.find(at >= 0 ? Math.min(2, rest.length()) : 0);
        if (hasQuantity) {
            quantity = parseQuantity(q.group(1));
        }
        if (at >= 0) {
            mpn = hasQuantity && q.start() > 2 ? rest.substring(2, q.start()) : rest.substring(2);
            java.util.regex.Matcher order = ORDER.matcher(mpn);
            if (order.find()) {
                mpn = mpn.substring(0, order.start());
            }
            if (parts != null) {
                for (int n = mpn.length(); n > 3; n--) {
                    if (matchPart(parts, mpn.substring(0, n)) != null) {
                        mpn = mpn.substring(0, n);
                        break;
                    }
                }
            }
            if (mpn.isEmpty()) {
                mpn = null;
            }
        }
        return new Scan(mpn, pn, quantity, true);
    }

    /**
     * The part that best matches a scanned part number: the same ID or name (ignoring case),
     * else the only part whose ID or name contains it. Numbers are tried in order. Null if
     * nothing matches or several do.
     */
    public static Part matchPart(List<Part> parts, String... numbers) {
        for (String number : numbers) {
            if (number == null || number.isEmpty()) {
                continue;
            }
            String n = number.toLowerCase(Locale.ROOT);
            List<Part> exact = new ArrayList<>();
            List<Part> partial = new ArrayList<>();
            for (Part part : parts) {
                String id = part.getId().toLowerCase(Locale.ROOT);
                String name = part.getName() == null ? "" : part.getName().toLowerCase(Locale.ROOT);
                if (id.equals(n) || name.equals(n)) {
                    exact.add(part);
                }
                if (id.contains(n) || (!name.isEmpty() && name.contains(n))) {
                    partial.add(part);
                }
            }
            if (exact.size() == 1) {
                return exact.get(0);
            }
            if (partial.size() == 1) {
                return partial.get(0);
            }
        }
        return null;
    }

    /**
     * The tape for a part: what another feeder with the same package uses (inUse, may be null),
     * else the EIA-481 size matched against the package ID, then the part ID. Null if unknown.
     */
    public static Tape tapeFor(String packageId, String partId, Tape inUse) {
        if (inUse != null) {
            return inUse;
        }
        for (String text : new String[]{packageId, partId}) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            String t = text.toUpperCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
            for (Object[] entry : TAPE_DEFAULTS) {
                String key = (String) entry[0];
                if (Pattern.compile("(^|[^0-9A-Z])" + Pattern.quote(key) + "($|[^0-9])").matcher(t).find()) {
                    return new Tape((Integer) entry[1], (Integer) entry[2], "standard for " + key);
                }
            }
        }
        return null;
    }

    /**
     * The tape another Photon feeder with this package is set to, or null.
     */
    public static Tape tapeInUse(List<? extends org.openpnp.spi.Feeder> feeders, PhotonFeeder self, String packageId) {
        if (packageId == null) {
            return null;
        }
        for (org.openpnp.spi.Feeder feeder : feeders) {
            if (!(feeder instanceof PhotonFeeder) || feeder == self) {
                continue;
            }
            PhotonFeeder photon = (PhotonFeeder) feeder;
            Part part = photon.getPart();
            if (part != null && part.getPackage() != null && packageId.equals(part.getPackage().getId())
                    && photon.getTapeWidth() > 0 && photon.getPartPitch() > 0) {
                return new Tape(photon.getTapeWidth(), photon.getPartPitch(), "as used for " + packageId);
            }
        }
        return null;
    }

    /**
     * The other parts whose IDs share the first 64 characters with this one. The feeder keeps
     * only those 64, so OpenPnP couldn't tell the parts apart when it reads the feeder back.
     */
    public static List<Part> truncationClash(List<Part> parts, String partId) {
        return truncationClash(parts, partId, PhotonFeederRecord.PART_ID_LENGTH);
    }

    /**
     * The same, for a feeder that keeps this many characters (24 with older firmware).
     */
    public static List<Part> truncationClash(List<Part> parts, String partId, int keeps) {
        List<Part> clashes = new ArrayList<>();
        String head = PhotonFeederRecord.truncatePartId(partId, keeps);
        if (head.equals(partId)) {
            return clashes;
        }
        for (Part part : parts) {
            if (!part.getId().equals(partId) && PhotonFeederRecord.truncatePartId(part.getId(), keeps).equals(head)) {
                clashes.add(part);
            }
        }
        return clashes;
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Long parseQuantity(String digits) {
        try {
            return Long.parseLong(digits);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
