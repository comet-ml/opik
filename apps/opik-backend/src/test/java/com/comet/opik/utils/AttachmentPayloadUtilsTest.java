package com.comet.opik.utils;

import java.util.Base64;

/**
 * Utility class for generating valid test attachment payload data for attachment tests.
 *
 * This class provides methods to create valid PNG, GIF, JPEG, and PDF data that:
 * - Can be properly detected by Apache Tika
 * - Are available in both small (below threshold) and large (above threshold) sizes
 * - Have realistic binary patterns instead of repeated characters
 */
public final class AttachmentPayloadUtilsTest {

    /**
     * Default size for large attachments that should trigger attachment processing.
     * This should be well above the minimum threshold used by AttachmentStripperService.
     */
    public static final int DEFAULT_LARGE_SIZE = 5000;

    /**
     * Default size for small attachments that should NOT trigger attachment processing.
     * This should be well below the minimum threshold used by AttachmentStripperService.
     */
    public static final int DEFAULT_SMALL_SIZE = 500;

    private AttachmentPayloadUtilsTest() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a large valid PNG image as base64 string that exceeds the minimum length threshold.
     * The generated PNG will be properly detected by Apache Tika as "image/png".
     *
     * @return a base64 encoded string representing a valid large PNG image.
     */
    public static String createLargePngBase64() {
        return createValidPngBase64(DEFAULT_LARGE_SIZE);
    }

    /**
     * Creates a small valid PNG image as base64 string that is below the minimum length threshold.
     * The generated PNG will be properly detected by Apache Tika as "image/png".
     *
     * @return a base64 encoded string representing a valid small PNG image.
     */
    public static String createSmallPngBase64() {
        return createValidPngBase64(DEFAULT_SMALL_SIZE);
    }

    /**
     * Creates a valid PNG image as base64 string with the specified target size.
     * The generated PNG will be properly detected by Apache Tika as "image/png".
     *
     * @param targetSize the desired size of the binary data in bytes.
     * @return a base64 encoded string representing a valid PNG image.
     */
    public static String createValidPngBase64(int targetSize) {
        byte[] pngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}; // PNG header
        return createMockFileBytes(pngHeader, targetSize, null);
    }

    /**
     * Creates a large valid GIF image as base64 string that exceeds the minimum length threshold.
     * The generated GIF will be properly detected by Apache Tika as "image/gif".
     *
     * @return a base64 encoded string representing a valid large GIF image.
     */
    public static String createLargeGifBase64() {
        return createValidGifBase64(DEFAULT_LARGE_SIZE);
    }

    /**
     * Creates a small valid GIF image as base64 string that is below the minimum length threshold.
     * The generated GIF will be properly detected by Apache Tika as "image/gif".
     *
     * @return a base64 encoded string representing a valid small GIF image.
     */
    public static String createSmallGifBase64() {
        return createValidGifBase64(DEFAULT_SMALL_SIZE);
    }

    /**
     * Creates a valid GIF image as base64 string with the specified target size.
     * The generated GIF will be properly detected by Apache Tika as "image/gif".
     *
     * @param targetSize the desired size of the binary data in bytes.
     * @return a base64 encoded string representing a valid GIF image.
     */
    public static String createValidGifBase64(int targetSize) {
        byte[] gifHeader = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61}; // GIF89a header
        byte[] gifTrailer = {0x00, 0x3B}; // GIF trailer (Image Separator + Trailer)
        return createMockFileBytes(gifHeader, targetSize, gifTrailer);
    }

    /**
     * Creates a large valid JPEG image as base64 string that exceeds the minimum length threshold.
     * The generated JPEG will be properly detected by Apache Tika as "image/jpeg".
     *
     * @return a base64 encoded string representing a valid large JPEG image.
     */
    public static String createLargeJpegBase64() {
        return createValidJpegBase64(DEFAULT_LARGE_SIZE);
    }

    /**
     * Creates a small valid JPEG image as base64 string that is below the minimum length threshold.
     * The generated JPEG will be properly detected by Apache Tika as "image/jpeg".
     *
     * @return a base64 encoded string representing a valid small JPEG image.
     */
    public static String createSmallJpegBase64() {
        return createValidJpegBase64(DEFAULT_SMALL_SIZE);
    }

    /**
     * Creates a valid JPEG image as base64 string with the specified target size.
     * The generated JPEG will be properly detected by Apache Tika as "image/jpeg".
     *
     * @param targetSize the desired size of the binary data in bytes.
     * @return a base64 encoded string representing a valid JPEG image.
     */
    public static String createValidJpegBase64(int targetSize) {
        // Minimal JPEG header (JFIF marker)
        byte[] jpegHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46,
                0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00};
        // JPEG needs special ending bytes
        byte[] jpegFooter = {(byte) 0xFF, (byte) 0xD9}; // End of Image marker
        return createMockFileBytes(jpegHeader, targetSize, jpegFooter);
    }

    /**
     * Creates a large valid PDF document as base64 string that exceeds the minimum length threshold.
     * The generated PDF will be properly detected by Apache Tika as "application/pdf".
     *
     * @return a base64 encoded string representing a valid large PDF document.
     */
    public static String createLargePdfBase64() {
        return createValidPdfBase64(DEFAULT_LARGE_SIZE);
    }

    /**
     * Creates a small valid PDF document as base64 string that is below the minimum length threshold.
     * The generated PDF will be properly detected by Apache Tika as "application/pdf".
     *
     * @return a base64 encoded string representing a valid small PDF document.
     */
    public static String createSmallPdfBase64() {
        return createValidPdfBase64(DEFAULT_SMALL_SIZE);
    }

    /**
     * Creates a valid PDF document as base64 string with the specified target size.
     * The generated PDF will be properly detected by Apache Tika as "application/pdf".
     *
     * @param targetSize the desired size of the binary data in bytes.
     * @return a base64 encoded string representing a valid PDF document.
     */
    public static String createValidPdfBase64(int targetSize) {
        byte[] pdfHeader = "%PDF-1.4\n".getBytes(); // PDF header
        byte[] pdfFooter = "\n%%EOF".getBytes(); // PDF end-of-file marker
        return createMockFileBytes(pdfHeader, targetSize, pdfFooter);
    }

    /**
     * Creates a short base64 string that should NOT trigger attachment processing.
     * This is useful for testing the minimum length threshold.
     *
     * @return a short base64 encoded string (less than 5000 characters)
     */
    public static String createShortBase64() {
        byte[] shortData = "This is a short string that won't trigger attachment processing".getBytes();
        return Base64.getEncoder().encodeToString(shortData);
    }

    /**
     * Creates an invalid base64 string that will be detected as application/octet-stream.
     * This is useful for testing that the attachment stripper ignores non-image data.
     *
     * @return a base64 encoded string that won't be recognized as a valid image
     */
    public static String createInvalidImageBase64() {
        byte[] invalidData = new byte[DEFAULT_LARGE_SIZE];
        // Fill with random pattern that won't match any image format
        for (int i = 0; i < invalidData.length; i++) {
            invalidData[i] = (byte) ((i * 31) % 256);
        }
        return Base64.getEncoder().encodeToString(invalidData);
    }

    /**
     * Foundation method that creates image data with specified header, target size, and optional footer.
     * This method handles the common pattern of:
     * 1. Adding the image header
     * 2. Filling the remaining space with patterned data
     * 3. Adding optional footer bytes (for formats like JPEG)
     * 4. Base64 encoding the result
     *
     * @param header the image format header bytes
     * @param targetSize the desired total size of the binary data in bytes
     * @param footer optional footer bytes (can be null)
     * @return a base64 encoded string representing the image data
     */
    private static String createMockFileBytes(byte[] header, int targetSize, byte[] footer) {
        int footerSize = footer != null ? footer.length : 0;
        int dataLength = Math.max(targetSize, header.length + footerSize + 1);

        byte[] imageData = new byte[dataLength];

        // Copy header
        System.arraycopy(header, 0, imageData, 0, header.length);

        // Fill middle section with patterned data (common filling pattern)
        int fillStart = header.length;
        int fillEnd = dataLength - footerSize;
        for (int i = fillStart; i < fillEnd; i++) {
            imageData[i] = (byte) (i % 256);
        }

        // Copy footer if present
        if (footer != null) {
            System.arraycopy(footer, 0, imageData, fillEnd, footer.length);
        }

        return Base64.getEncoder().encodeToString(imageData);
    }
}
