import { logger } from "@/utils/logger";
import { Opik } from "opik";
import path from "path";
import os from "os";
import fs from "fs";
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

  it("should expand tilde in OPIK_CONFIG_PATH", async () => {
    const mockHomedir = "/mock/home/user";
    const mockConfigPath = `${mockHomedir}/custom/.opik.config`;
    const mockConfigContent = `[opik]
api_key = "test-tilde"
url_override = "https://www.comet.com/api"
project_name = "test-tilde"
workspace = "test-tilde"`;

    const existsSyncSpy = vi.spyOn(fs, "existsSync");
    const readFileSyncSpy = vi.spyOn(fs, "readFileSync");
    const homedirSpy = vi.spyOn(os, "homedir");

    homedirSpy.mockReturnValue(mockHomedir);
    existsSyncSpy.mockImplementation((filePath) => {
      return String(filePath) === mockConfigPath;
    });
    readFileSyncSpy.mockImplementation((filePath) => {
      if (String(filePath) === mockConfigPath) {
        return mockConfigContent;
      }
      throw new Error(`File not found: ${filePath}`);
    });

    try {
      process.env.OPIK_CONFIG_PATH = "~/custom/.opik.config";
      delete process.env.OPIK_API_KEY;
      delete process.env.OPIK_URL_OVERRIDE;

      const opik = new Opik();

      expect(homedirSpy).toHaveBeenCalled();
      expect(existsSyncSpy).toHaveBeenCalledWith(mockConfigPath);
      expect(readFileSyncSpy).toHaveBeenCalledWith(mockConfigPath, "utf8");
      expect(opik.config.apiUrl).toBe("https://www.comet.com/api");
      expect(opik.config.apiKey).toBe("test-tilde");
      expect(opik.config.workspaceName).toBe("test-tilde");
      expect(opik.config.projectName).toBe("test-tilde");
    } finally {
      existsSyncSpy.mockRestore();
      readFileSyncSpy.mockRestore();
      homedirSpy.mockRestore();
    }
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

describe("Opik client apiKey propagation", () => {
  const originalFetch = global.fetch;
  let capturedHeaders: Headers | null = null;
  const originalEnvironmentVariables = { ...process.env };

  beforeEach(() => {
    // Mock fetch to capture request headers
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.headers) {
        // Convert headers to Headers object if needed
        if (init.headers instanceof Headers) {
          capturedHeaders = init.headers;
        } else if (typeof init.headers === "object") {
          capturedHeaders = new Headers(init.headers as Record<string, string>);
        }
      }
      return new Response(JSON.stringify({}), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }) as typeof fetch;
  });

  afterEach(() => {
    global.fetch = originalFetch;
    capturedHeaders = null;
    process.env = { ...originalEnvironmentVariables };
  });

  it("should propagate apiKey to Authorization header in HTTP requests", async () => {
    const testApiKey = "test-api-key-12345";
    const client = new Opik({
      apiKey: testApiKey,
      apiUrl: "https://www.comet.com/opik/api",
      workspaceName: "test-workspace",
    });

    // Make an API call that will trigger an HTTP request
    await client.api.isAlive();

    // Verify fetch was called
    expect(global.fetch).toHaveBeenCalled();

    // Verify Authorization header contains the apiKey
    expect(capturedHeaders).not.toBeNull();
    if (capturedHeaders) {
      // The apiKey should be in the Authorization header
      // Note: Fern may format it differently, so we check for the key value
      const authorizationHeader = capturedHeaders.get("authorization");
      expect(authorizationHeader).toBeDefined();
      expect(authorizationHeader).toContain(testApiKey);
    }
  });

  it("should propagate apiKey from environment variable to HTTP requests", async () => {
    const testApiKey = "env-api-key-67890";
    process.env.OPIK_API_KEY = testApiKey;
    process.env.OPIK_URL_OVERRIDE = "https://www.comet.com/opik/api";
    process.env.OPIK_WORKSPACE = "test-workspace";

    const client = new Opik();

    // Make an API call that will trigger an HTTP request
    await client.api.isAlive();

    // Verify fetch was called
    expect(global.fetch).toHaveBeenCalled();

    // Verify Authorization header contains the apiKey
    expect(capturedHeaders).not.toBeNull();
    if (capturedHeaders) {
      const authorizationHeader = capturedHeaders.get("authorization");
      expect(authorizationHeader).toBeDefined();
      expect(authorizationHeader).toContain(testApiKey);
    }
  });
});
