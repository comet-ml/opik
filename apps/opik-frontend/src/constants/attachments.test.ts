import { describe, expect, it } from "vitest";
import { MINE_TYPE_TO_ATTACHMENT_TYPE_MAP } from "./attachments";
import { ATTACHMENT_TYPE } from "@/types/attachments";

describe("MINE_TYPE_TO_ATTACHMENT_TYPE_MAP", () => {
  it("should map text/csv to CSV attachment type", () => {
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["text/csv"]).toBe(
      ATTACHMENT_TYPE.CSV,
    );
  });

  it("should map application/csv to CSV attachment type", () => {
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["application/csv"]).toBe(
      ATTACHMENT_TYPE.CSV,
    );
  });

  it("should map all standard PDF MIME types", () => {
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["application/pdf"]).toBe(
      ATTACHMENT_TYPE.PDF,
    );
  });

  it("should map all standard image MIME types", () => {
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["image/jpeg"]).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["image/png"]).toBe(
      ATTACHMENT_TYPE.IMAGE,
    );
  });

  it("should map text files to TEXT attachment type", () => {
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["text/plain"]).toBe(
      ATTACHMENT_TYPE.TEXT,
    );
    expect(MINE_TYPE_TO_ATTACHMENT_TYPE_MAP["text/markdown"]).toBe(
      ATTACHMENT_TYPE.TEXT,
    );
  });
});
