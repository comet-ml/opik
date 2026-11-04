import { describe, it, expect } from "vitest";
import { CustomFieldSchema } from "./schema";

const errors = (field: { key: string; value: string }) => {
  const result = CustomFieldSchema.safeParse(field);
  return result.success
    ? []
    : result.error.issues.map((issue) => issue.message);
};

describe("CustomFieldSchema", () => {
  it("accepts a key and value as entered, without trimming", () => {
    const result = CustomFieldSchema.safeParse({
      key: "impact",
      value: " MAJOR ",
    });

    expect(result.success).toBe(true);
    expect(result.success && result.data.value).toBe(" MAJOR ");
  });

  it.each([
    ["empty key", { key: "", value: "MAJOR" }, "Field key is required"],
    [
      "whitespace-only key",
      { key: "   ", value: "MAJOR" },
      "Field key is required",
    ],
    ["empty value", { key: "impact", value: "" }, "Field value is required"],
    [
      "whitespace-only value",
      { key: "impact", value: " \t " },
      "Field value is required",
    ],
    [
      "key over 64 characters",
      { key: "k".repeat(65), value: "MAJOR" },
      "Field key must be at most 64 characters",
    ],
    [
      "value over 255 characters",
      { key: "impact", value: "v".repeat(256) },
      "Field value must be at most 255 characters",
    ],
  ])("rejects %s", (_, field, message) => {
    expect(errors(field)).toEqual([message]);
  });
});
