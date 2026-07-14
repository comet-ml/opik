import { describe, it, expect } from "vitest";

import { extractErrorFromLogs } from "./runError";

describe("extractErrorFromLogs", () => {
  it("returns the last error-like line from the log output", () => {
    const logs = [
      "INFO starting optimization",
      "INFO evaluating baseline",
      "ValueError: reference key 'answer' not found in dataset",
    ].join("\n");

    expect(extractErrorFromLogs(logs)).toBe(
      "ValueError: reference key 'answer' not found in dataset",
    );
  });

  it("prefers the deepest error line, ignoring later non-error output", () => {
    const logs = [
      "RuntimeError: metric scoring failed",
      "cleanup complete",
    ].join("\n");

    expect(extractErrorFromLogs(logs)).toBe(
      "RuntimeError: metric scoring failed",
    );
  });

  it("strips ANSI color codes", () => {
    const esc = String.fromCharCode(27); // ANSI ESC
    const line = `${esc}[31mError: boom${esc}[0m`;
    expect(extractErrorFromLogs(line)).toBe("Error: boom");
  });

  it("returns null when there is no error-like line or no content", () => {
    expect(extractErrorFromLogs("all good\ndone")).toBeNull();
    expect(extractErrorFromLogs("")).toBeNull();
  });
});
