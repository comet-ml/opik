import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import {
  _resetTrackOpikClientCache,
  getTrackOpikClient,
  track,
} from "@/decorators/track";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";
import { pollUntil } from "./helpers";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Environment Integration Tests", () => {
  let client: Opik;
  const projectName = `ts-e2e-environment-${Date.now()}`;
  const createdEnvNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());
    client = new Opik({ projectName });
  });

  afterAll(async () => {
    for (const name of createdEnvNames) {
      try {
        await client.deleteEnvironment(name);
      } catch {
        // best effort
      }
    }
    await client.flush();
  }, 30000);

  describe("CRUD", () => {
    it("creates, lists, updates, and deletes an environment by name", async () => {
      const name = `env-${Date.now()}`;
      createdEnvNames.push(name);

      const created = await client.createEnvironment(name, {
        description: "created by TS e2e test",
        color: "#abcdef",
      });
      expect(created.name).toBe(name);
      expect(created.description).toBe("created by TS e2e test");
      expect(created.color).toBe("#abcdef");
      expect(created.id).toBeDefined();

      const listed = await client.getEnvironments();
      expect(listed.some((e) => e.name === name)).toBe(true);

      const updated = await client.updateEnvironment(name, {
        description: "updated by TS e2e test",
      });
      expect(updated.name).toBe(name);
      expect(updated.description).toBe("updated by TS e2e test");

      await client.deleteEnvironment(name);
      // Calling delete on a missing name is a no-op.
      await client.deleteEnvironment(name);

      const afterDelete = await client.getEnvironments();
      expect(afterDelete.some((e) => e.name === name)).toBe(false);
    }, 30000);
  });

  describe("Trace and span persistence", () => {
    it("persists per-call environment on the trace", async () => {
      const envName = `env-${Date.now()}`;
      createdEnvNames.push(envName);
      await client.createEnvironment(envName);

      const trace = client.trace({
        name: "env-trace",
        environment: envName,
      });
      trace.end();
      await client.flush();

      const fetched = await pollUntil(async () => {
        try {
          const t = await client.api.traces.getTraceById(trace.data.id);
          return t.environment === envName ? t : undefined;
        } catch {
          return undefined;
        }
      });
      expect(fetched.environment).toBe(envName);
    }, 60000);

    it("span inherits trace environment, ignoring per-call override on @track", async () => {
      const envName = `env-${Date.now()}`;
      createdEnvNames.push(envName);
      await client.createEnvironment(envName);

      // Use a fresh tracked client so the test owns its lifecycle / project.
      _resetTrackOpikClientCache();
      const trackClient = getTrackOpikClient();

      let innerSpanId: string | undefined;
      let innerTraceId: string | undefined;

      const inner = track(
        { name: "inner-fn", environment: "this-should-be-ignored" },
        async () => {
          // grab the active span/trace at call time
          const { getTrackContext } = await import("@/decorators/track");
          const ctx = getTrackContext();
          innerSpanId = ctx?.span.data.id;
          innerTraceId = ctx?.trace.data.id;
          return "ok";
        }
      );

      const outer = track(
        {
          name: "outer-fn",
          environment: envName,
          projectName,
        },
        async () => inner()
      );

      await outer();
      await trackClient.flush();

      expect(innerSpanId).toBeDefined();
      expect(innerTraceId).toBeDefined();

      const fetchedSpan = await pollUntil(async () => {
        try {
          const s = await trackClient.api.spans.getSpanById(innerSpanId!);
          return s.environment === envName ? s : undefined;
        } catch {
          return undefined;
        }
      });
      expect(fetchedSpan.environment).toBe(envName);

      const fetchedTrace = await trackClient.api.traces.getTraceById(
        innerTraceId!
      );
      expect(fetchedTrace.environment).toBe(envName);
    }, 60000);

    it("trace without environment still logs (back-compat)", async () => {
      const trace = client.trace({ name: "no-env-trace" });
      trace.end();
      await client.flush();

      const fetched = await pollUntil(async () => {
        try {
          return await client.api.traces.getTraceById(trace.data.id);
        } catch {
          return undefined;
        }
      });
      expect(fetched.id).toBe(trace.data.id);
    }, 60000);
  });

  describe("OQL in / not_in filtering", () => {
    it("filters traces by environment using in operator", async () => {
      const envName = `env-${Date.now()}`;
      createdEnvNames.push(envName);
      await client.createEnvironment(envName);

      const trace = client.trace({ name: "oql-in-trace", environment: envName });
      trace.end();
      await client.flush();

      const results = await client.searchTraces({
        projectName,
        filterString: `environment in ("${envName}")`,
        waitForAtLeast: 1,
        waitForTimeout: 30,
      });
      expect(results.some((t) => t.id === trace.data.id)).toBe(true);
    }, 60000);

    it("excludes traces by environment using not_in operator", async () => {
      const envName = `env-${Date.now()}`;
      createdEnvNames.push(envName);
      await client.createEnvironment(envName);

      const included = client.trace({
        name: "oql-not-in-included",
        environment: envName,
      });
      included.end();
      await client.flush();

      const results = await client.searchTraces({
        projectName,
        filterString: `environment not_in ("__nonexistent__")`,
        waitForAtLeast: 1,
        waitForTimeout: 30,
      });
      expect(results.some((t) => t.id === included.data.id)).toBe(true);
    }, 60000);
  });
});
