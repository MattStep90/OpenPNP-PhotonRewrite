package org.openpnp.machine.photon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PhotonFeederRecordTest {
    @Test
    public void decodesStatusLittleEndian() {
        int[] data = {
                1,                       // loaded
                8,                       // tape width
                0x28, 0x00,              // pitch 40 = 4 mm
                0x88, 0x13, 0x00, 0x00,  // qty 5000
                0xA0, 0x0F, 0x00, 0x00,  // fed 4000 = 400 mm
                0x6C, 0x12, 0x00, 0x00,  // parts left 4716
                1,                       // unsaved
                0, 0, 0};

        PhotonFeederRecord record = PhotonFeederRecord.fromStatus(data, "R0402-10K");

        assertTrue(record.loaded);
        assertEquals(8, record.tapeWidthMm);
        assertEquals(40, record.pitchTenthsMm);
        assertEquals(5000, record.quantityAtLoad);
        assertEquals(4000, record.fedTenthsMm);
        assertEquals(4716, record.partsLeft);
        assertTrue(record.unsaved);
        assertEquals("R0402-10K", record.partId);
    }

    @Test
    public void decodesLargeUnsignedValues() {
        int[] data = new int[20];
        data[0] = 1;
        for (int i = 12; i < 16; i++) {
            data[i] = 0xFF;
        }

        PhotonFeederRecord record = PhotonFeederRecord.fromStatus(data, "");
        assertEquals(0xFFFFFFFFL, record.partsLeft);
    }

    @Test
    public void loadRequestEncodesWidthPitchAndQuantity() {
        assertArrayEquals(new int[]{0x17, 12, 0x14, 0x00, 0x10, 0x27, 0x00, 0x00},
                PhotonFeederRecord.loadRequest(12, 20, 10000));
    }

    @Test
    public void setPartsLeftRequestEncodesCount() {
        assertArrayEquals(new int[]{0x18, 0x39, 0x30, 0x00, 0x00},
                PhotonFeederRecord.setPartsLeftRequest(12345));
    }

    @Test
    public void stageIdRequestsSplitThePartIdIn12ByteChunks() {
        String id = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";  // 26 characters, stored as 24

        int[] first = PhotonFeederRecord.stageIdRequest(id, 0);
        int[] second = PhotonFeederRecord.stageIdRequest(id, 12);

        assertEquals(14, first.length);
        assertEquals(0x16, first[0]);
        assertEquals(0, first[1]);
        assertEquals('A', first[2]);
        assertEquals('L', first[13]);
        assertEquals(0x16, second[0]);
        assertEquals(12, second[1]);
        assertEquals('M', second[2]);
        assertEquals('X', second[13]);
    }

    @Test
    public void stageIdRequestPadsShortIdsWithZeros() {
        int[] second = PhotonFeederRecord.stageIdRequest("C0402", 12);
        for (int i = 2; i < second.length; i++) {
            assertEquals(0, second[i]);
        }
    }

    @Test
    public void partIdRoundTripsThroughChunks() {
        String id = "C0402-100nF-16V";
        int[] first = new int[20];
        int[] second = new int[20];
        System.arraycopy(PhotonFeederRecord.stageIdRequest(id, 0), 2, first, 0, 12);
        System.arraycopy(PhotonFeederRecord.stageIdRequest(id, 12), 2, second, 0, 12);

        assertEquals(id, PhotonFeederRecord.partIdFromChunks(first, second));
    }

    @Test
    public void nonAsciiCharactersAreReplaced() {
        assertEquals("10?F", PhotonFeederRecord.truncatePartId("10µF"));
    }

    @Test
    public void matchesPartIdOnFirst24Characters() {
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 100, 0, 100, false,
                "ABCDEFGHIJKLMNOPQRSTUVWX");

        assertTrue(record.matchesPartId("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertFalse(record.matchesPartId("ABCDEFGHIJKLMNOPQRSTUVW"));
        assertFalse(record.matchesPartId(null));
    }

    @Test
    public void afterFeedCountsLikeTheFirmware() {
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 100, 0, 100, false, "P");

        record = record.afterFeed(20);
        assertEquals(99, record.partsLeft);
        assertEquals(20, record.fedTenthsMm);

        // Partial feeds only take a part off once a whole pitch is fed
        record = record.afterFeed(10);
        assertEquals(99, record.partsLeft);
        record = record.afterFeed(10);
        assertEquals(98, record.partsLeft);

        // Backward feeds give parts back
        record = record.afterFeed(-40);
        assertEquals(100, record.partsLeft);
        assertEquals(0, record.fedTenthsMm);
    }

    @Test
    public void afterFeedNeverGoesBelowZero() {
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 1, 0, 1, false, "P");
        assertEquals(0, record.afterFeed(1000).partsLeft);
    }

    @Test
    public void afterFeedLeavesAnEmptyRecordAlone() {
        PhotonFeederRecord record = new PhotonFeederRecord(false, 0, 0, 0, 0, 0, false, null);
        assertSame(record, record.afterFeed(20));
    }

    @Test
    public void lowPartsThresholdFollowsTheFirmware() {
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 105, 0, 11, false, "X");
        assertEquals(10, record.lowPartsThreshold(PhotonFeederRecord.LOW_PARTS_DEFAULT));
        assertEquals(0, record.lowPartsThreshold(PhotonFeederRecord.LOW_PARTS_OFF));
        assertEquals(12, record.lowPartsThreshold(12));
        assertEquals(PhotonFeederRecord.Level.OK, record.lowPartsLevel(PhotonFeederRecord.LOW_PARTS_DEFAULT));
        assertEquals(PhotonFeederRecord.Level.LOW, record.lowPartsLevel(11));
        assertEquals(PhotonFeederRecord.Level.OK, record.lowPartsLevel(PhotonFeederRecord.LOW_PARTS_OFF));

        PhotonFeederRecord empty = new PhotonFeederRecord(true, 8, 20, 105, 2100, 0, false, "X");
        assertEquals(PhotonFeederRecord.Level.EMPTY, empty.lowPartsLevel(PhotonFeederRecord.LOW_PARTS_DEFAULT));
        assertEquals(PhotonFeederRecord.Level.OK, empty.lowPartsLevel(PhotonFeederRecord.LOW_PARTS_OFF));
    }

    @Test
    public void lowPartsRequestsEncodeTheSetting() {
        assertArrayEquals(new int[]{0x1A}, PhotonFeederRecord.lowPartsRequest());
        assertArrayEquals(new int[]{0x1B, 0xFF, 0xFF}, PhotonFeederRecord.setLowPartsRequest(PhotonFeederRecord.LOW_PARTS_DEFAULT));
        assertArrayEquals(new int[]{0x1B, 0x2C, 0x01}, PhotonFeederRecord.setLowPartsRequest(300));
        assertEquals(300, PhotonFeederRecord.lowPartsSetting(new int[]{0x2C, 0x01, 0, 0}));
    }

    @Test
    public void partIdsRunTo64Characters() {
        String id = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+=*";
        assertEquals(66, id.length());
        int[][] chunks = new int[6][];
        for (int c = 0; c < 6; c++) {
            chunks[c] = new int[12];
            System.arraycopy(PhotonFeederRecord.stageIdRequest(id, c * 12), 2, chunks[c], 0, 12);
        }

        assertEquals(id.substring(0, 64), PhotonFeederRecord.partIdFromChunks(chunks));
        assertEquals(0, chunks[5][4], "the chunk at 60 carries only 4 characters");
        assertEquals(id.substring(0, 64), PhotonFeederRecord.truncatePartId(id));
        assertEquals(id.substring(0, 24), PhotonFeederRecord.truncatePartId(id, 24));
    }

    @Test
    public void stagesAtLeastTwoChunks() {
        assertEquals(java.util.List.of(0, 12), PhotonFeederRecord.stageOffsets("C0402"));
        assertEquals(java.util.List.of(0, 12), PhotonFeederRecord.stageOffsets("ABCDEFGHIJKLMNOPQRSTUVWX"));
        assertEquals(java.util.List.of(0, 12, 24), PhotonFeederRecord.stageOffsets("ABCDEFGHIJKLMNOPQRSTUVWXY"));
        assertEquals(java.util.List.of(0, 12, 24, 36, 48, 60),
                PhotonFeederRecord.stageOffsets("x".repeat(100)));
    }

    @Test
    public void readingStopsAtTheEndOfTheId() {
        int[] full = new int[12];
        java.util.Arrays.fill(full, 'A');
        assertFalse(PhotonFeederRecord.endsPartId(full, 0));
        assertTrue(PhotonFeederRecord.endsPartId(new int[12], 24), "zeros from older firmware");
        assertTrue(PhotonFeederRecord.endsPartId(full, 60), "the last chunk");
    }

    @Test
    public void matchesA64CharacterRecord() {
        String id = "x".repeat(70);
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 100, 0, 100, false, "x".repeat(64));
        assertTrue(record.matchesPartId(id));
        assertFalse(record.matchesPartId("x".repeat(63)));
    }

    @Test
    public void rotationInTapeRequestsAndReplies() {
        assertArrayEquals(new int[]{0x17, 8, 20, 0, 100, 0, 0, 0, 0x84, 0x03, 1},
                PhotonFeederRecord.loadRequest(8, 20, 100, 900));
        assertArrayEquals(PhotonFeederRecord.loadRequest(8, 20, 100),
                PhotonFeederRecord.loadRequest(8, 20, 100, null));
        assertArrayEquals(new int[]{0x1D, 0xFF, 0xFF}, PhotonFeederRecord.setRotationRequest(null));
        assertEquals(900, PhotonFeederRecord.rotationFromReply(new int[]{0x84, 0x03, 1}));
        assertNull(PhotonFeederRecord.rotationFromReply(new int[]{0xFF, 0xFF, 1}), "not given");
        assertNull(PhotonFeederRecord.rotationFromReply(new int[]{0, 0, 0}), "older firmware, not 0 deg");
        assertEquals(2700, PhotonFeederRecord.rotationTenths(-90));
        assertEquals(0, PhotonFeederRecord.rotationTenths(359.97));
    }

    @Test
    public void theRotationSurvivesAFeed() {
        PhotonFeederRecord record = new PhotonFeederRecord(true, 8, 20, 100, 0, 100, false, "R", 900);
        assertEquals(900, record.afterFeed(20).rotationTenthsDeg);
        assertEquals(90.0, record.getRotationDegrees(), 1e-9);
    }
}
