import { describe, it, expect, beforeAll, afterAll, vi, type MockInstance } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";
import { getProjectUrlByTraceId } from "@/utils/url";
import * as urlUtils from "@/utils/url";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Trace URL Log Integration", () => {
  let client: Opik;
  let getProjectUrlByTraceIdSpy: MockInstance<typeof getProjectUrlByTraceId>;
  const testProjectName = `test-trace-url-${Date.now()}`;

  beforeAll(() => {
    if (shouldRunApiTests) {
      console.log(getIntegrationTestStatus());
      client = new Opik({ projectName: testProjectName });
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  beforeEach(() => {
    if (shouldRunApiTests) {
      getProjectUrlByTraceIdSpy = vi.spyOn(urlUtils, "getProjectUrlByTraceId");
    }
  });

  afterEach(() => {
    if (getProjectUrlByTraceIdSpy) {
      getProjectUrlByTraceIdSpy.mockRestore();
    }
  });

  it("should log a valid trace URL that resolves to a real endpoint", async () => {
    const trace = client.trace({
      name: "test-trace-url-validation",
      input: { test: "data" },
    });

    trace.end();
    await client.flush();

    // Capture the URL from the spy
    const urlCallResult = getProjectUrlByTraceIdSpy.mock.results.find(
      (result) => {
        const url = result.value as string;
        return url.includes(trace.data.id);
      }
    );
    expect(urlCallResult).toBeDefined();
    const traceUrl = urlCallResult!.value as string;

    // Verify trace_id is in the URL
    expect(traceUrl).toContain(trace.data.id);
    expect(traceUrl).toContain("/api/v1/session/redirect/projects/");

    // Fetch the URL to verify it's accessible
    const response = await fetch(traceUrl, {
      method: "GET",
      redirect: "follow",
      signal: AbortSignal.timeout(10000),
    });

    expect(response.status).toBeGreaterThanOrEqual(200);
    expect(response.status).toBeLessThan(500);
  }, 30000);
});
