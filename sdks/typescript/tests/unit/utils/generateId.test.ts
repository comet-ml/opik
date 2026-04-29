import { describe, it, expect } from "vitest";
import { v4 as uuidv4 } from "uuid";
import { generateId, isValidUuidV7 } from "@/utils/generateId";

describe("isValidUuidV7", () => {
  it("returns true for a freshly generated id", () => {
    expect(isValidUuidV7(generateId())).toBe(true);
  });

  it("returns true for the uppercased form of a v7 uuid", () => {
    expect(isValidUuidV7(generateId().toUpperCase())).toBe(true);
  });

  it.each([
    ["v4", uuidv4()],
    ["nil", "00000000-0000-0000-0000-000000000000"],
    ["v1", "6ba7b810-9dad-11d1-80b4-00c04fd430c8"],
    ["v4-style version nibble 4", "550e8400-e29b-41d4-a716-446655440000"],
  ])("returns false for non-v7 uuids (%s)", (_label, value) => {
    expect(isValidUuidV7(value)).toBe(false);
  });

  it.each([
    ["empty", ""],
    ["whitespace", "   "],
    ["garbage", "not-a-uuid"],
    ["truncated", "0193b3a5-1234-7abc-9def"],
    ["too long", "0193b3a5-1234-7abc-9def-0123456789abXX"],
  ])("returns false for malformed strings (%s)", (_label, value) => {
    expect(isValidUuidV7(value)).toBe(false);
  });

  it.each([
    ["null", null],
    ["undefined", undefined],
    ["number", 123],
    ["object", {}],
    ["array", []],
  ])("returns false for non-string inputs (%s)", (_label, value) => {
    expect(isValidUuidV7(value as unknown)).toBe(false);
  });
});