import { describe, it, expect } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, vi } from "vitest";
import {
  analyticsEnabled,
  createReporter,
  eventName,
  summarizeArgs,
  userIdentifier,
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

describe("userIdentifier", () => {
  it("uses the workspace when there is a real one", () => {
    expect(userIdentifier({ OPIK_WORKSPACE: "acme" })).toBe("acme");
  });

  it("reads the workspace out of the config file", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));
    fs.writeFileSync(
      path.join(home, ".opik.config"),
      "[opik]\nworkspace = from-file\n",
    );

    expect(userIdentifier({}, home)).toBe("from-file");
  });

  it("falls back to a per-machine hash rather than grouping everyone under 'default'", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));

    const id = userIdentifier({ OPIK_WORKSPACE: "default" }, home);

    expect(id).toMatch(/^default_[0-9a-f]{64}$/);
    expect(id).toBe(userIdentifier({}, home));
  });
});

describe("summarizeArgs: what must never be reported", () => {
  it("drops a value that leads with a dash", () => {
    expect(summarizeArgs(["configure", "--api-key", "-s3cret-value"])).toEqual({
      command: "configure",
      flags: "--api-key",
    });
  });

  it("stops at a bare `--`, after which everything is a value", () => {
    expect(
      summarizeArgs(["mcp", "configure", "--", "/home/someone/secret.txt"]),
    ).toEqual({ command: "mcp configure", flags: "" });
  });

  it("does not report a bare word that follows a flag", () => {
    expect(
      summarizeArgs(["mcp", "configure", "--ai-client", "cursor"]),
    ).toEqual({ command: "mcp configure", flags: "--ai-client" });
  });

  it("keeps short flags but not longer dash tokens", () => {
    expect(summarizeArgs(["configure", "-h"])).toEqual({
      command: "configure",
      flags: "-h",
    });
    expect(summarizeArgs(["configure", "-hunter2"])).toEqual({
      command: "configure",
      flags: "",
    });
  });
});

describe("userIdentifier: config file lookup", () => {
  it("expands a `~` in OPIK_CONFIG_PATH, which no shell expanded for us", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));
    fs.writeFileSync(
      path.join(home, "elsewhere.config"),
      "[opik]\nworkspace = tilde-workspace\n",
    );

    expect(
      userIdentifier({ OPIK_CONFIG_PATH: "~/elsewhere.config" }, home),
    ).toBe("tilde-workspace");
  });

  it("treats a blank workspace or config path as unset", () => {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-"));
    fs.writeFileSync(
      path.join(home, ".opik.config"),
      "[opik]\nworkspace = from-file\n",
    );

    expect(
      userIdentifier({ OPIK_WORKSPACE: "  ", OPIK_CONFIG_PATH: " " }, home),
    ).toBe("from-file");
  });
});

describe("createReporter", () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it("sends nothing when analytics are switched off", async () => {
    const fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const reporter = createReporter(["mcp", "configure"], {
      OPIK_ANALYTICS_ENABLE: "false",
    });
    reporter.track("invoked");
    await reporter.flush();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends nothing when the collector URL is empty", async () => {
    const fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const reporter = createReporter(["mcp", "configure"], {
      OPIK_ANALYTICS_URL: "",
    });
    reporter.track("invoked");
    await reporter.flush();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports each step once, to the configured collector", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 200 });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const reporter = createReporter(["mcp", "configure"], {
      OPIK_ANALYTICS_URL: "http://127.0.0.1:9/",
      OPIK_WORKSPACE: "acme",
    });
    reporter.track("invoked");
    reporter.track("uv_detected", { uv_found: true });
    await reporter.flush();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [url, init] = fetchMock.mock.calls[0];
    const event = JSON.parse(init.body);
    expect(url).toBe("http://127.0.0.1:9/");
    expect(event.anonymous_id).toBe("acme");
    expect(event.event_type).toBe(eventName("invoked"));
    expect(event.event_properties.launcher).toBe("npx");

    const second = JSON.parse(fetchMock.mock.calls[1][1].body);
    expect(second.event_properties.run_id).toBe(event.event_properties.run_id);
  });

  it("a failing collector neither throws nor hangs the command", async () => {
    globalThis.fetch = vi
      .fn()
      .mockRejectedValue(
        new Error("connection refused"),
      ) as unknown as typeof fetch;

    const reporter = createReporter([], {
      OPIK_ANALYTICS_URL: "http://127.0.0.1:9/",
    });
    reporter.track("invoked");

    await expect(reporter.flush()).resolves.toBeUndefined();
  });
});
