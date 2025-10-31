import { logger } from "@/utils/logger";
import { Opik } from "opik";
import path from "path";
import { describe, expect, MockInstance } from "vitest";
import { mockAPIFunction } from "@tests/mockUtils";

describe("Opik client config", () => {
  let loggerErrorSpy: MockInstance<typeof logger.error>;
  const originalEnvironmentVariables = { ...process.env };

  beforeEach(() => {
    loggerErrorSpy = vi.spyOn(logger, "error");
  });

  afterEach(() => {
    process.env = { ...originalEnvironmentVariables };
    loggerErrorSpy.mockRestore();
  });

  it("should throw an error if the host is cloud and the API key is not set", async () => {
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "";

    expect(() => {
      new Opik();
    }).toThrow("OPIK_API_KEY is not set");
  });

  it("should throw an error if the host is cloud and workspace is not set", async () => {
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "test";

    const opik = new Opik();
    expect(opik.config.workspaceName).toBe("default");
  });

  it("should not throw an error if everything is set", async () => {
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "test";
    process.env.OPIK_WORKSPACE = "test";

    expect(() => {
      new Opik();
    }).not.toThrow();
  });

  it("should load the config from the file", async () => {
    process.env.OPIK_CONFIG_PATH = path.resolve(
      __dirname,
      "./examples/valid-opik-config.ini"
    );
    process.env.OPIK_API_KEY = undefined;

    const opik = new Opik();

    expect(opik.config.apiUrl).toBe("https://www.comet.com/api");
    expect(opik.config.apiKey).toBe("test");
    expect(opik.config.workspaceName).toBe("test");
    expect(opik.config.projectName).toBe("test");
  });

  it("should being able to override config values from the environment variables + explicit config", async () => {
    process.env.OPIK_CONFIG_PATH = path.resolve(
      __dirname,
      "./examples/partial-opik-config.ini"
    );
    process.env.OPIK_API_KEY = "api-key-override";

    const opik = new Opik({
      workspaceName: "workspace-override",
    });

    // Configuration from file
    expect(opik.config.apiUrl).toBe("https://www.comet.com/api");
    // Override from environment variables
    expect(opik.config.apiKey).toBe("api-key-override");
    // Override from explicit config
    expect(opik.config.workspaceName).toBe("workspace-override");
    // Default project name
    expect(opik.config.projectName).toBe("Default Project");
  });

  it("should throw an error if the config is not valid from the file (only API url, missing API key)", async () => {
    process.env.OPIK_CONFIG_PATH = path.resolve(
      __dirname,
      "./examples/invalid-opik-config.ini"
    );
    process.env.OPIK_API_KEY = undefined;

    expect(() => {
      new Opik();
    }).toThrow("OPIK_API_KEY is not set");
  });
});

describe("Opik client custom config", () => {
  beforeEach(() => {
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "test";
    process.env.OPIK_WORKSPACE = "test";
  });

  it("Custom headers present - happy path - initial configuration", async () => {
    const headers = {
      h1: "h1",
      h2: "h2",
    };

    const client = new Opik({
      headers,
    });

    expect(client.api.requestOptions?.headers).toEqual(headers);

    const createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    const createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    const trace = client.trace({ name: "test" });

    const span = trace.span({
      name: `test-span`,
      type: "llm",
    });

    span.end();
    trace.end();
    await client.flush();

    const callsToCheck = [
      createTracesSpy.mock.calls[0],
      createSpansSpy.mock.calls[0],
    ];

    for (const call of callsToCheck) {
      const [, configArg] = call;

      expect(configArg).toEqual({ headers });
    }

    createTracesSpy.mockRestore();
  });

  it("Custom headers present - happy path - dynamic configuration", async () => {
    const headers = {
      h1: "h1",
      h2: "h2",
    };

    const client = new Opik();

    client.api.setHeaders(headers);

    expect(client.api.requestOptions?.headers).toEqual(headers);

    const createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    const createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    const trace = client.trace({ name: "test" });

    const span = trace.span({
      name: `test-span`,
      type: "llm",
    });

    span.end();
    trace.end();
    await client.flush();

    const callsToCheck = [
      createTracesSpy.mock.calls[0],
      createSpansSpy.mock.calls[0],
    ];

    for (const call of callsToCheck) {
      const [, configArg] = call;

      expect(configArg).toEqual({ headers });
    }

    createTracesSpy.mockRestore();
  });

  it("Custom headers present - no custom headers", async () => {
    const client = new Opik();

    expect(client.config.requestOptions?.headers).eq(undefined);
  });
});
