import { describe, it, expect } from "vitest";
import { extractInlineAttachments } from "@/client/attachment/attachmentExtraction";
import {
  detectMimeType,
  fileExtensionForMimeType,
} from "@/client/attachment/mimeTypes";

// Small threshold so tests stay fast: minGroups = floor(200/4) = 50 -> >=200 base64 chars.
const MIN = 200;
const PLACEHOLDER =
  /^\[(?:input|output|metadata)-attachment-\d+-\d+-sdk\.\w+\]$/;

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG = [0xff, 0xd8, 0xff];
const GIF89A = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61];
const PDF = [0x25, 0x50, 0x44, 0x46];

// A base64 blob of `bytes` bytes starting with the given magic-byte signature.
const b64 = (signature: number[], bytes: number) => {
  const buf = Buffer.alloc(bytes);
  signature.forEach((byte, i) => (buf[i] = byte));
  return buf.toString("base64");
};
const bigPng = () => b64(PNG, 400); // ~536 base64 chars, well over the threshold
const dataUri = (mime: string, base64: string) =>
  `data:${mime};base64,${base64}`;

describe("extractInlineAttachments", () => {
  it("extracts an oversized data-URI image and replaces it with a placeholder", () => {
    const span = {
      id: "s1",
      input: { image: dataUri("image/png", bigPng()) },
      output: { text: "hi" },
    };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(1);
    expect(attachments[0].mimeType).toBe("image/png");
    expect(attachments[0].data.subarray(0, 8)).toEqual(Buffer.from(PNG));
    expect((result.input as { image: string }).image).toMatch(PLACEHOLDER);
    expect(result.output).toEqual({ text: "hi" }); // untouched sibling
  });

  it("leaves a blob below the size threshold inline", () => {
    const span = { id: "s1", input: dataUri("image/png", b64(PNG, 30)) };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(0);
    expect(result).toBe(span); // same reference, nothing extracted
  });

  it("leaves long non-image base64 inline", () => {
    const text = Buffer.from("x".repeat(400)).toString("base64"); // long but not an image
    const span = { input: { blob: text } };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(0);
    expect(result).toBe(span);
  });

  it("walks nested arrays and objects", () => {
    const span = {
      input: {
        messages: [
          { content: [{ type: "image", url: dataUri("image/png", bigPng()) }] },
        ],
      },
    };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(1);
    const url = (
      result.input as {
        messages: { content: { url: string }[] }[];
      }
    ).messages[0].content[0].url;
    expect(url).toMatch(PLACEHOLDER);
  });

  it("extracts from metadata as well", () => {
    const span = { metadata: { img: dataUri("image/jpeg", b64(JPEG, 400)) } };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(1);
    expect(attachments[0].mimeType).toBe("image/jpeg");
    expect((result.metadata as { img: string }).img).toMatch(PLACEHOLDER);
  });

  it("extracts multiple images from one string", () => {
    const two = `first ${dataUri("image/png", bigPng())} second ${dataUri("image/png", bigPng())}`;
    const span = { output: two };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(2);
    expect(result.output).not.toContain("data:image");
    expect(
      (result.output as string).match(/\[[^\]]+-sdk\.\w+\]/g),
    ).toHaveLength(2);
  });

  it("returns the same reference when nothing matches", () => {
    const span = { input: { text: "nothing here" }, output: null };

    const { result, attachments } = extractInlineAttachments(span, MIN);

    expect(attachments).toHaveLength(0);
    expect(result).toBe(span);
  });

  it("does not mutate the original payload", () => {
    const uri = dataUri("image/png", bigPng());
    const input = { image: uri };
    const span = { input };

    extractInlineAttachments(span, MIN);

    expect(input.image).toBe(uri); // original untouched (non-mutating)
  });
});

describe("detectMimeType", () => {
  const withSig = (signature: number[], bytes = 64) => {
    const buf = Buffer.alloc(bytes);
    signature.forEach((byte, i) => (buf[i] = byte));
    return buf;
  };

  it("recognizes common image and document types", () => {
    expect(detectMimeType(withSig(PNG))).toBe("image/png");
    expect(detectMimeType(withSig(JPEG))).toBe("image/jpeg");
    expect(detectMimeType(withSig(GIF89A))).toBe("image/gif");
    expect(detectMimeType(withSig(PDF))).toBe("application/pdf");
  });

  it("recognizes WEBP via the RIFF container", () => {
    const buf = Buffer.alloc(64);
    Buffer.from("RIFF").copy(buf, 0);
    Buffer.from("WEBP").copy(buf, 8);
    expect(detectMimeType(buf)).toBe("image/webp");
  });

  it("returns null for unrecognized bytes", () => {
    expect(
      detectMimeType(Buffer.from("just some plain text bytes")),
    ).toBeNull();
  });

  it("maps mime types to file extensions", () => {
    expect(fileExtensionForMimeType("image/jpeg")).toBe("jpg");
    expect(fileExtensionForMimeType("application/pdf")).toBe("pdf");
    expect(fileExtensionForMimeType("application/x-unknown")).toBe("bin");
  });
});
