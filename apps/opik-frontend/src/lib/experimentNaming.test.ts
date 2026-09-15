import { describe, expect, it } from "vitest";
import {
  buildExperimentNameBase,
  composeExperimentName,
  getDefaultExperimentLabel,
} from "./experimentNaming";

const DATE = "2026-09-14";

describe("experimentNaming", () => {
  it("falls back to the lowercase column letter when no label is set", () => {
    expect(getDefaultExperimentLabel(0)).toBe("a");
    expect(getDefaultExperimentLabel(1)).toBe("b");
    expect(buildExperimentNameBase("", 0, DATE)).toBe("a_2026-09-14");
    expect(buildExperimentNameBase("", 2, DATE)).toBe("c_2026-09-14");
  });

  it("uses a custom label when provided", () => {
    expect(buildExperimentNameBase("concise", 0, DATE)).toBe(
      "concise_2026-09-14",
    );
  });

  it("treats a whitespace-only label as unset", () => {
    expect(buildExperimentNameBase("   ", 1, DATE)).toBe("b_2026-09-14");
  });

  it("trims surrounding whitespace from the label", () => {
    expect(buildExperimentNameBase("  concise  ", 0, DATE)).toBe(
      "concise_2026-09-14",
    );
  });

  it("zero-pads the run number to two digits", () => {
    expect(composeExperimentName("concise_2026-09-14", 1)).toBe(
      "concise_2026-09-14_01",
    );
    expect(composeExperimentName("concise_2026-09-14", 9)).toBe(
      "concise_2026-09-14_09",
    );
  });

  it("keeps run numbers past 99 intact rather than truncating", () => {
    expect(composeExperimentName("concise_2026-09-14", 100)).toBe(
      "concise_2026-09-14_100",
    );
  });
});
