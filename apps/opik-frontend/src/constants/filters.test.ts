import { describe, it, expect } from "vitest";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "./filters";

describe("CUSTOM_FILTER_VALIDATION_REGEXP", () => {
  it.each([
    "input",
    "input.message",
    "output[0].text",
    "input.['a.b']",
    "input.['a..b']",
    "output.['ctx']['trailing.']",
    "input.['items'][0]['sku.id']",
  ])("accepts %s", (key) => {
    expect(CUSTOM_FILTER_VALIDATION_REGEXP.test(key)).toBe(true);
  });

  it.each(["metadata.a", "input..a", "input.", "inputs.a"])(
    "rejects %s",
    (key) => {
      expect(CUSTOM_FILTER_VALIDATION_REGEXP.test(key)).toBe(false);
    },
  );
});
