/**
 * Magic-byte MIME sniffing for decoded base64 blobs (parity with the Python SDK's
 * decoder_helpers). Only recognized types become attachments; anything unrecognized
 * returns null and is left inline — matching Python's skip of octet-stream / text.
 */

const startsWith = (
  bytes: Buffer,
  signature: number[],
  offset = 0,
): boolean => {
  if (bytes.length < offset + signature.length) {
    return false;
  }
  for (let i = 0; i < signature.length; i++) {
    if (bytes[offset + i] !== signature[i]) {
      return false;
    }
  }
  return true;
};

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG = [0xff, 0xd8, 0xff];
const GIF87A = [0x47, 0x49, 0x46, 0x38, 0x37, 0x61];
const GIF89A = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61];
const RIFF = [0x52, 0x49, 0x46, 0x46];
const WEBP = [0x57, 0x45, 0x42, 0x50];
const PDF = [0x25, 0x50, 0x44, 0x46];
const FTYP = [0x66, 0x74, 0x79, 0x70]; // "ftyp" box, appears at byte offset 4 in MP4

export const detectMimeType = (bytes: Buffer): string | null => {
  if (startsWith(bytes, PNG)) return "image/png";
  if (startsWith(bytes, JPEG)) return "image/jpeg";
  if (startsWith(bytes, GIF87A) || startsWith(bytes, GIF89A))
    return "image/gif";
  if (startsWith(bytes, RIFF) && startsWith(bytes, WEBP, 8))
    return "image/webp";
  if (startsWith(bytes, PDF)) return "application/pdf";
  if (startsWith(bytes, FTYP, 4)) return "video/mp4";

  const head = bytes.subarray(0, 1024).toString("utf8").trimStart();
  if (
    head.startsWith("<svg") ||
    (head.startsWith("<?xml") && head.includes("<svg"))
  ) {
    return "image/svg+xml";
  }
  return null;
};

const EXTENSIONS: Record<string, string> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/gif": "gif",
  "image/webp": "webp",
  "application/pdf": "pdf",
  "image/svg+xml": "svg",
  "video/mp4": "mp4",
};

export const fileExtensionForMimeType = (mimeType: string): string =>
  EXTENSIONS[mimeType] ?? "bin";
