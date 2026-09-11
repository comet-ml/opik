import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import {
  isMultiLineField,
  collectMultiLineKeys,
  hasAnyExpandableField,
} from "./blueprintFieldLayout";

const makeValue = (
  overrides: Partial<BlueprintValue> & { type: BlueprintValueType },
): BlueprintValue => ({
  key: "field",
  value: "",
  ...overrides,
});

describe("isMultiLineField", () => {
  it("returns true for PROMPT type", () => {
    expect(
      isMultiLineField(makeValue({ type: BlueprintValueType.PROMPT })),
    ).toBe(true);
  });

  it("returns false for BOOLEAN type", () => {
    expect(
      isMultiLineField(makeValue({ type: BlueprintValueType.BOOLEAN })),
    ).toBe(false);
  });

  it("returns false for INT type", () => {
    expect(isMultiLineField(makeValue({ type: BlueprintValueType.INT }))).toBe(
      false,
    );
  });

  it("returns false for FLOAT type", () => {
    expect(
      isMultiLineField(makeValue({ type: BlueprintValueType.FLOAT })),
    ).toBe(false);
  });

  it("returns true for STRING with newline", () => {
    expect(
      isMultiLineField(
        makeValue({ type: BlueprintValueType.STRING, value: "line1\nline2" }),
      ),
    ).toBe(true);
  });

  it("returns true for STRING exceeding 80 chars", () => {
    expect(
      isMultiLineField(
        makeValue({ type: BlueprintValueType.STRING, value: "a".repeat(81) }),
      ),
    ).toBe(true);
  });

  it("returns false for short STRING without newlines", () => {
    expect(
      isMultiLineField(
        makeValue({ type: BlueprintValueType.STRING, value: "short" }),
      ),
    ).toBe(false);
  });

  it("returns false for STRING at exactly 80 chars", () => {
    expect(
      isMultiLineField(
        makeValue({ type: BlueprintValueType.STRING, value: "a".repeat(80) }),
      ),
    ).toBe(false);
  });
});

describe("collectMultiLineKeys", () => {
  it("returns empty array for empty input", () => {
    expect(collectMultiLineKeys([])).toEqual([]);
  });

  it("includes PROMPT fields", () => {
    const values = [makeValue({ key: "p", type: BlueprintValueType.PROMPT })];
    expect(collectMultiLineKeys(values)).toEqual(["p"]);
  });

  it("collects STRING fields with long values", () => {
    const values = [
      makeValue({
        key: "long",
        type: BlueprintValueType.STRING,
        value: "a".repeat(81),
      }),
      makeValue({
        key: "short",
        type: BlueprintValueType.STRING,
        value: "small",
      }),
    ];
    expect(collectMultiLineKeys(values)).toEqual(["long"]);
  });
});

describe("hasAnyExpandableField", () => {
  it("returns false for empty array", () => {
    expect(hasAnyExpandableField([])).toBe(false);
  });

  it("returns true when PROMPT field present", () => {
    expect(
      hasAnyExpandableField([makeValue({ type: BlueprintValueType.PROMPT })]),
    ).toBe(true);
  });

  it("returns true when multi-line STRING present", () => {
    expect(
      hasAnyExpandableField([
        makeValue({
          type: BlueprintValueType.STRING,
          value: "line1\nline2",
        }),
      ]),
    ).toBe(true);
  });

  it("returns false when only scalar single-line fields", () => {
    expect(
      hasAnyExpandableField([
        makeValue({ type: BlueprintValueType.INT, value: "1" }),
        makeValue({ type: BlueprintValueType.BOOLEAN, value: "true" }),
      ]),
    ).toBe(false);
  });
});
