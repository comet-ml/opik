/**
 * Magic-byte MIME sniffing for decoded base64 blobs. Recognizes binary media types
 * (PNG/JPEG/GIF/WebP/PDF/SVG/MP4) plus JSON (leading `{`/`[`), matching the Python SDK's
 * `detect_mime_type`; anything else returns null and is left inline.
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
  // JPEG (parity with the Python SDK): the SOI header alone isn't enough — a complete JPEG
  // ends with the EOI marker (FFD9). A header-only/truncated blob falls through to the other
  // checks so random data that merely starts with FFD8FF isn't misclassified as an image.
  if (
    startsWith(bytes, JPEG) &&
    bytes.length >= 2 &&
    bytes[bytes.length - 2] === 0xff &&
    bytes[bytes.length - 1] === 0xd9
  ) {
    return "image/jpeg";
  }
  if (startsWith(bytes, GIF87A) || startsWith(bytes, GIF89A))
    return "image/gif";
  if (startsWith(bytes, RIFF) && startsWith(bytes, WEBP, 8))
    return "image/webp";
  if (startsWith(bytes, PDF)) return "application/pdf";
  if (startsWith(bytes, FTYP, 4)) return "video/mp4";

  // Case-insensitive, anywhere in the first 1 KB (matches the Python SDK) — catches SVGs
  // that open with a DOCTYPE, an XML comment, or a stylesheet PI before the <svg> tag.
  const head = bytes.subarray(0, 1024).toString("utf8").toLowerCase();
  if (head.includes("<svg")) {
    return "image/svg+xml";
  }

  // JSON (matches the Python SDK): the first ~100 bytes must be valid UTF-8 and, once
  // leading whitespace is trimmed, begin with `{` or `[`. A fatal decoder means a binary
  // blob (or a multibyte char split at the 100-byte window) is treated as not-JSON.
  try {
    const text = new TextDecoder("utf-8", { fatal: true })
      .decode(bytes.subarray(0, 100))
      .trimStart();
    if (text.startsWith("{") || text.startsWith("[")) {
      return "application/json";
    }
  } catch {
    // not valid UTF-8 in the sampled window -> not JSON
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
  "application/json": "json",
};

export const fileExtensionForMimeType = (mimeType: string): string =>
  EXTENSIONS[mimeType] ?? "bin";
