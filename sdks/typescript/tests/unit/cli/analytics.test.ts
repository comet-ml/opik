import { describe, it, expect } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  analyticsEnabled,
  anonymousId,
  eventName,
  summarizeArgs,
} from "@/cli/analytics";

describe("analyticsEnabled", () => {
  it("reports by default", () => {
    expect(analyticsEnabled({})).toBe(true);
  });

  it("honours OPIK_ANALYTICS_ENABLE being switched off", () => {
    expect(analyticsEnabled({ OPIK_ANALYTICS_ENABLE: "false" })).toBe(false);
    expect(analyticsEnabled({ OPIK_ANALYTICS_ENABLE: "0" })).toBe(false);
    expect(analyticsEnabled({ OPIK_ANALYTICS_ENABLE: " No " })).toBe(false);
    expect(analyticsEnabled({ OPIK_ANALYTICS_ENABLE: "true" })).toBe(true);
  });
});

describe("eventName", () => {
  it("names the steps of a run the way the Python SDK names its events", () => {
    expect(eventName("invoked")).toBe(
      "opik_typescript_sdk__configuration__cli__invoked",
    );
    expect(eventName("uv_detected")).toBe(
      "opik_typescript_sdk__configuration__cli__uv_detected",
    );
    expect(eventName("handoff")).toBe(
      "opik_typescript_sdk__configuration__cli__handoff",
    );
    expect(eventName("error_shown")).toBe(
      "opik_typescript_sdk__configuration__cli__error_shown",
    );
  });
});

describe("summarizeArgs", () => {
  it("keeps the subcommand and the flag names", () => {
    expect(summarizeArgs(["mcp", "configure", "--skills"])).toEqual({
      command: "mcp configure",
      flags: "--skills",
    });
  });

  it("never reports a flag's value", () => {
    expect(summarizeArgs(["configure", "--api-key", "secret-value"])).toEqual({
      command: "configure",
      flags: "--api-key",
    });
    expect(summarizeArgs(["configure", "--api-key=secret-value"])).toEqual({
      command: "configure",
      flags: "--api-key",
    });
  });

  it("reads an empty run as an empty summary", () => {
    expect(summarizeArgs([])).toEqual({ command: "", flags: "" });
  });
});

describe("anonymousId", () => {
  it("uses the workspace when there is a real one", () => {
    expect(anonymousId({ OPIK_WORKSPACE: "acme" })).toBe("acme");
  });

  it("reads the workspace out of the config file", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));
    fs.writeFileSync(
      path.join(home, ".opik.config"),
      "[opik]\nworkspace = from-file\n",
    );

    expect(anonymousId({}, home)).toBe("from-file");
  });

  it("falls back to a per-machine hash rather than grouping everyone under 'default'", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));

    const id = anonymousId({ OPIK_WORKSPACE: "default" }, home);

    expect(id).toMatch(/^default_[0-9a-f]{64}$/);
    expect(id).toBe(anonymousId({}, home));
  });
});
