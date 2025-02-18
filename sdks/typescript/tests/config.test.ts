import { logger } from "@/utils/logger";
import { Opik } from "opik";
import { MockInstance } from "vitest";

describe("Opik client batching", () => {
  let loggerErrorSpy: MockInstance<typeof logger.error>;
  const originalHost = process.env.OPIK_HOST;
  const originalApiKey = process.env.OPIK_API_KEY;
  const originalWorkspace = process.env.OPIK_WORKSPACE;

  beforeEach(() => {
    loggerErrorSpy = vi.spyOn(logger, "error");
  });

  afterEach(() => {
    if (originalHost) {
      process.env.OPIK_HOST = originalHost;
    }
    if (originalApiKey) {
      process.env.OPIK_API_KEY = originalApiKey;
    }
    if (originalWorkspace) {
      process.env.OPIK_WORKSPACE = originalWorkspace;
    }
    loggerErrorSpy.mockRestore();
  });

  it("should throw an error if the host is cloud and the API key is not set", async () => {
    process.env.OPIK_HOST = "https://www.comet.com/api";

    expect(() => {
      new Opik();
    }).toThrow("OPIK_API_KEY is not set");
  });

  it("should throw an error if the host is cloud and workspace is not set", async () => {
    process.env.OPIK_HOST = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "test";

    expect(() => {
      new Opik();
    }).toThrow("OPIK_WORKSPACE is not set");
  });

  it("should throw an error if the host is cloud and workspace is not set", async () => {
    process.env.OPIK_HOST = "https://www.comet.com/api";
    process.env.OPIK_API_KEY = "test";
    process.env.OPIK_WORKSPACE = "test";

    expect(() => {
      new Opik();
    }).not.toThrow();
  });
});
