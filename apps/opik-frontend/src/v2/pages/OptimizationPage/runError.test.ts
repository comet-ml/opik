import { describe, it, expect } from "vitest";

import {
  extractSystemNotice,
  getRunErrorMessage,
  GENERIC_RUN_ERROR,
} from "./runError";

describe("extractSystemNotice", () => {
  it("returns the curated [System] notice with the prefix stripped", () => {
    const logs = [
      "INFO starting optimization",
      "[System] The dataset couldn't be loaded. Make sure it exists and contains items.",
    ].join("\n");

    expect(extractSystemNotice(logs)).toBe(
      "The dataset couldn't be loaded. Make sure it exists and contains items.",
    );
  });

  it("returns the most recent [System] notice when several are present", () => {
    const logs = ["[System] first notice", "[System] second notice"].join("\n");

    expect(extractSystemNotice(logs)).toBe("second notice");
  });

  it("strips ANSI color codes", () => {
    const esc = String.fromCharCode(27);
    const logs = `${esc}[31m[System] boom${esc}[0m`;
    expect(extractSystemNotice(logs)).toBe("boom");
  });

  it("returns null when there is no [System] notice", () => {
    expect(extractSystemNotice("ValueError: raw traceback line")).toBeNull();
    expect(extractSystemNotice("")).toBeNull();
  });
});

describe("getRunErrorMessage", () => {
  it("surfaces the backend's curated high-level message", () => {
    const logs = [
      "Traceback (most recent call last):",
      "  File 'x.py', line 3",
      "openai.RateLimitError: 429 too many requests",
      "[System] The model provider rate-limited this run. Wait a little and try running it again.",
    ].join("\n");

    expect(getRunErrorMessage(logs)).toBe(
      "The model provider rate-limited this run. Wait a little and try running it again.",
    );
  });

  it("never surfaces a raw traceback — falls back to a generic message", () => {
    const logs = [
      "Traceback (most recent call last):",
      "  File 'x.py', line 3",
      "ZeroDivisionError: division by zero",
    ].join("\n");

    const message = getRunErrorMessage(logs);
    expect(message).not.toContain("ZeroDivisionError");
    expect(message).toBe(GENERIC_RUN_ERROR);
  });
});
