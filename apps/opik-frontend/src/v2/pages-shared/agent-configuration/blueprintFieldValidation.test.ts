import { BlueprintValueType } from "@/types/agent-configs";
import {
  BLUEPRINT_FIELD_NAME_PATTERN,
  validateBlueprintFieldValue,
} from "./blueprintFieldValidation";

describe("BLUEPRINT_FIELD_NAME_PATTERN", () => {
  it.each(["name", "_private", "my_field_2", "A", "_"])(
    "accepts valid name: %s",
    (name) => {
      expect(BLUEPRINT_FIELD_NAME_PATTERN.test(name)).toBe(true);
    },
  );

  it.each(["", "2start", "has space", "has-dash", "special!"])(
    "rejects invalid name: %s",
    (name) => {
      expect(BLUEPRINT_FIELD_NAME_PATTERN.test(name)).toBe(false);
    },
  );
});

describe("validateBlueprintFieldValue", () => {
  describe("STRING", () => {
    it("returns empty string for non-empty value", () => {
      expect(
        validateBlueprintFieldValue(BlueprintValueType.STRING, "hello"),
      ).toBe("");
    });

    it("returns error for empty value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.STRING, "")).toBe(
        "Must not be empty",
      );
    });

    it("returns error for whitespace-only value", () => {
      expect(
        validateBlueprintFieldValue(BlueprintValueType.STRING, "   "),
      ).toBe("Must not be empty");
    });
  });

  describe("INT", () => {
    it("returns empty string for valid integer", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.INT, "42")).toBe(
        "",
      );
    });

    it("returns empty string for negative integer", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.INT, "-5")).toBe(
        "",
      );
    });

    it("returns error for float value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.INT, "3.14")).toBe(
        "Must be an integer",
      );
    });

    it("returns error for non-numeric value", () => {
      expect(
        validateBlueprintFieldValue(BlueprintValueType.INT, "abc"),
      ).toBeTruthy();
    });

    it("returns error for empty value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.INT, "")).toBe(
        "Must not be empty",
      );
    });
  });

  describe("FLOAT", () => {
    it("returns empty string for valid float", () => {
      expect(
        validateBlueprintFieldValue(BlueprintValueType.FLOAT, "3.14"),
      ).toBe("");
    });

    it("returns empty string for integer value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.FLOAT, "42")).toBe(
        "",
      );
    });

    it("returns error for non-numeric value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.FLOAT, "abc")).toBe(
        "Must be a valid number",
      );
    });

    it("returns error for empty value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.FLOAT, "")).toBe(
        "Must not be empty",
      );
    });
  });

  describe("BOOLEAN and PROMPT (no schema)", () => {
    it("returns empty string for BOOLEAN regardless of value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.BOOLEAN, "")).toBe(
        "",
      );
    });

    it("returns empty string for PROMPT regardless of value", () => {
      expect(validateBlueprintFieldValue(BlueprintValueType.PROMPT, "")).toBe(
        "",
      );
    });
  });
});
