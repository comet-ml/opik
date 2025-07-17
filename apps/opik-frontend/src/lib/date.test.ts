import { describe, expect, it } from "vitest";
import { millisecondsToSeconds } from "./date";

describe("millisecondsToSeconds", () => {
  it("should return seconds with precision 3 when milliseconds <= 5", () => {
    expect(millisecondsToSeconds(5)).toBe(0.005);
    expect(millisecondsToSeconds(1)).toBe(0.001);
  });

  it("should return seconds with precision 2 when 5 < milliseconds <= 50", () => {
    expect(millisecondsToSeconds(50)).toBe(0.05);
    expect(millisecondsToSeconds(25)).toBe(0.03);
  });

  it("should return seconds with precision 1 when milliseconds > 50", () => {
    expect(millisecondsToSeconds(100)).toBe(0.1);
    expect(millisecondsToSeconds(1000)).toBe(1);
  });
});
