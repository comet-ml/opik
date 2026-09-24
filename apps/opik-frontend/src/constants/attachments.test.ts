import { describe, expect, it } from "vitest";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import { getAttachmentTypeByMimeType } from "./attachments";

describe("getAttachmentTypeByMimeType", () => {
  it("resolves mime types from the lookup table", () => {
    expect(getAttachmentTypeByMimeType("image/png")).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
    expect(getAttachmentTypeByMimeType("application/pdf")).toBe(
      ATTACHMENT_TYPE.PDF,
    );
  });

  it("classifies well-formed types outside the table by top-level type", () => {
    expect(getAttachmentTypeByMimeType("image/avif")).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
    expect(getAttachmentTypeByMimeType("video/ogg")).toBe(
      ATTACHMENT_TYPE.VIDEO,
    );
    expect(getAttachmentTypeByMimeType("audio/flac")).toBe(
      ATTACHMENT_TYPE.AUDIO,
    );
  });

  it("treats mime types case-insensitively per RFC 2045", () => {
    expect(getAttachmentTypeByMimeType("IMAGE/PNG")).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
    expect(getAttachmentTypeByMimeType("Image/AVIF")).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
  });

  it("tolerates surrounding whitespace", () => {
    expect(getAttachmentTypeByMimeType("  image/png  ")).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
  });

  it("falls back to other for unknown or unusable values", () => {
    expect(getAttachmentTypeByMimeType("application/x-custom")).toBe(
      ATTACHMENT_TYPE.OTHER,
    );
    expect(getAttachmentTypeByMimeType("")).toBe(ATTACHMENT_TYPE.OTHER);
    expect(getAttachmentTypeByMimeType("///")).toBe(ATTACHMENT_TYPE.OTHER);
  });

  it("degrades to other instead of throwing on a missing value", () => {
    expect(getAttachmentTypeByMimeType(null as unknown as string)).toBe(
      ATTACHMENT_TYPE.OTHER,
    );
    expect(getAttachmentTypeByMimeType(undefined as unknown as string)).toBe(
      ATTACHMENT_TYPE.OTHER,
    );
  });
});
