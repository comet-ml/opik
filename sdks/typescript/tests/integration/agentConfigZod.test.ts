/**
 * E2E integration tests for the Zod-based AgentConfig high-level API.
 * Mirrors Python's tests/e2e/test_agent_config.py.
 * Requires a running Opik backend. See tests/integration/api/shouldRunIntegrationTests.ts.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { z } from "zod";
import { Opik, track, agentConfigContext, Prompt } from "@/index";
import { AgentConfigManager } from "@/agent-config";
import { getTrackContext } from "@/decorators/track";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

function uniqueProject(): string {
  return `e2e-agent-config-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

async function deleteProject(client: Opik, name: string): Promise<void> {
  try {
    const project = await client.api.projects.retrieveProject({ name });
    if (project?.id) {
      await client.api.projects.deleteProjectById(project.id);
    }
  } catch {
    // best effort
  }
}

async function getBlueprintHistoryLength(
  client: Opik,
  projectName: string
): Promise<number> {
  const project = await client.api.projects.retrieveProject({ name: projectName });
  if (!project?.id) return 0;
  const history = await client.api.agentConfigs.getBlueprintHistory(project.id);
  return history.content?.length ?? 0;
}

describe.skipIf(!shouldRunApiTests)(
  "AgentConfig Zod high-level API — E2E",
  () => {
    let client: Opik;
    const projectsToCleanup: string[] = [];

    beforeAll(() => {
      console.log(getIntegrationTestStatus());
      client = new Opik({});
    });

    afterAll(async () => {
      for (const name of projectsToCleanup) {
        await deleteProject(client, name);
      }
    });

    // ─── Test 1: publish, dedup, new version, get by latest / version name / env ───

    it(
      "should publish, dedup, and retrieve by latest / version name / env",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const MyConfig = z
          .object({
            temperature: z.number().describe("Sampling temperature"),
            model: z.string(),
            hint: z.string().optional(),
          })
          .describe("MyConfig");

        const verifiedSchema = MyConfig.parse({
          temperature: 0.5,
          model: "gpt-3.5",
        });


        // Publish v1
        const v1Name = await client.createAgentConfig(
          MyConfig,
          verifiedSchema,
          { projectName }
        );
        expect(typeof v1Name).toBe("string");
        expect(v1Name.length).toBeGreaterThan(0);

        // Dedup: same values → same version name, no new history entry
        const v1Again = await client.createAgentConfig(
          MyConfig,
          verifiedSchema,
          { projectName }
        );
        expect(v1Again).toBe(v1Name);
        expect(await getBlueprintHistoryLength(client, projectName)).toBe(1);

        // New values → new version
        const v2Name = await client.createAgentConfig(
          MyConfig,
          { temperature: 0.8, model: "gpt-4", hint: "use chain-of-thought" },
          { projectName }
        );
        expect(v2Name).not.toBe(v1Name);
        expect(await getBlueprintHistoryLength(client, projectName)).toBe(2);

        // Retrieve latest (should be v2)
        const fetchLatest = track(async () => {
          return client.getAgentConfigVersion(MyConfig, {
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            latest: true,
          });
        });
        const latest = await fetchLatest();
        expect(latest.temperature).toBeCloseTo(0.8);
        expect(latest.model).toBe("gpt-4");
        expect(latest.hint).toBe("use chain-of-thought");
        expect(latest.isFallback).toBe(false);
        expect(latest.blueprintId).toBeDefined();
        expect(latest.blueprintVersion).toBe(v2Name);

        // Retrieve by version name (v1)
        const fetchByName = track(async () => {
          return client.getAgentConfigVersion(MyConfig, {
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            version: v1Name,
          });
        });
        const byName = await fetchByName();
        expect(byName.temperature).toBeCloseTo(0.5);
        expect(byName.hint).toBeNull();

        // Deploy v1 to "staging" then retrieve by env
        await byName.deployTo("staging");

        const fetchByEnv = track(async () => {
          return client.getAgentConfigVersion(MyConfig, {
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            env: "staging",
          });
        });
        const byEnv = await fetchByEnv();
        expect(byEnv.temperature).toBeCloseTo(0.5);
        expect(byEnv.hint).toBeNull();
      },
      60_000
    );

    // ─── Test 2: multi-schema dedup (fields from two schemas share one blueprint) ───

    it(
      "should not create duplicate versions when publishing a second schema",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const ConfigA = z
          .object({ temperature: z.number(), model: z.string() })
          .describe("ConfigA");

        const ConfigB = z
          .object({ retries: z.number().int() })
          .describe("ConfigB");

        // First publish of each schema
        await client.createAgentConfig(
          ConfigA,
          { temperature: 0.5, model: "gpt-4" },
          { projectName }
        );
        const vAfterB = await client.createAgentConfig(
          ConfigB,
          { retries: 3 },
          { projectName }
        );

        // Re-publishing ConfigA with same values: blueprint now also has ConfigB keys,
        // but ConfigA values are unchanged → should be a no-op
        const vAAgain = await client.createAgentConfig(
          ConfigA,
          { temperature: 0.5, model: "gpt-4" },
          { projectName }
        );
        expect(vAAgain).toBe(vAfterB);

        expect(await getBlueprintHistoryLength(client, projectName)).toBe(2);
      },
      60_000
    );

    // ─── Test 3: trace metadata injection on field access ───

    it(
      "should inject agent_configuration into trace and span metadata on field access",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const Schema = z
          .object({
            temperature: z.number(),
            model: z.string(),
          })
          .describe("TraceSchema");

        await client.createAgentConfig(
          Schema,
          { temperature: 0.7, model: "gpt-4" },
          { projectName }
        );

        let traceId: string | undefined;
        let spanId: string | undefined;

        const run = track({ projectName }, async () => {
          const cfg = await client.getAgentConfigVersion(Schema, {
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            latest: true,
          });

          // Access fields to trigger metadata injection
          void cfg.temperature;
          void cfg.model;

          const ctx = getTrackContext();
          traceId = ctx?.trace?.data?.id;
          spanId = ctx?.span?.data?.id;

          return cfg;
        });

        const cfg = await run();
        expect(cfg.temperature).toBeCloseTo(0.7);
        await client.flush();

        expect(traceId).toBeDefined();
        expect(spanId).toBeDefined();

        // Poll for the trace to appear and verify metadata
        const traces = await client.searchTraces({
          projectName,
          filterString: `id = "${traceId}"`,
          waitForAtLeast: 1,
          waitForTimeout: 30,
        });

        const trace = traces[0];
        expect(trace).toBeDefined();

        const meta = trace?.metadata as Record<string, unknown> | undefined;
        expect(meta?.agent_configuration).toBeDefined();

        const agentMeta = meta?.agent_configuration as Record<string, unknown>;
        expect(agentMeta._blueprint_id).toBeDefined();
        expect(agentMeta.blueprint_version).toBeDefined();
        expect(agentMeta.values).toEqual({
          "TraceSchema.temperature": { value: 0.7, type: "float" },
          "TraceSchema.model": { value: "gpt-4", type: "string" },
        });
      },
      60_000
    );

    // ─── Test 4: mask overrides selected fields ───

    it(
      "should overlay mask values on top of the base blueprint",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const MyConfig = z
          .object({ temperature: z.number(), model: z.string() })
          .describe("MyConfig");

        await client.createAgentConfig(
          MyConfig,
          { temperature: 0.5, model: "gpt-4" },
          { projectName }
        );

        // Create mask via AgentConfigManager low-level API
        const agentConfigLow = new AgentConfigManager(projectName, client);
        const maskId = await agentConfigLow.createMask({
          values: [{ key: "MyConfig.temperature", value: "0.9", type: "float" }],
        });

        const fetchWithMask = track(async () => {
          return client.getAgentConfigVersion(MyConfig, {
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            latest: true,
          });
        });

        // Without mask
        const without = await fetchWithMask();
        expect(without.temperature).toBeCloseTo(0.5);

        // With mask via agentConfigContext
        let withMask: Awaited<ReturnType<typeof fetchWithMask>> | undefined;
        await agentConfigContext(maskId, async () => {
          withMask = await fetchWithMask();
        });

        expect(withMask?.temperature).toBeCloseTo(0.9);
        expect(withMask?.model).toBe("gpt-4");
      },
      60_000
    );

    // ─── Test 5: getAgentConfigVersion throws outside track() ───

    it("should throw when getAgentConfigVersion is called outside track()", async () => {
      const projectName = uniqueProject();
      projectsToCleanup.push(projectName);

      const Schema = z.object({ x: z.number() }).describe("Cfg");

      await expect(
        client.getAgentConfigVersion(Schema, {
          fallback: { x: 0 },
          projectName,
          latest: true,
        })
      ).rejects.toThrow(/track\(\)/);
    });

    // ─── Test 6: throws when no blueprint exists for the requested env ───

    it("should throw when no blueprint exists for the given env", async () => {
      const projectName = uniqueProject();
      projectsToCleanup.push(projectName);

      const Schema = z.object({ x: z.number() }).describe("FallbackSchema");

      await client.createAgentConfig(Schema, { x: 42 }, { projectName });

      const run = track(async () => {
        return client.getAgentConfigVersion(Schema, {
          fallback: { x: 0 },
          projectName,
          env: "nonexistent-env-xyz",
        });
      });

      await expect(run()).rejects.toThrow();
    });

    // ─── Test 7: Prompt field stored and retrieved ───

    it(
      "should store a Prompt in config and retrieve it as a Prompt instance",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const MyConfig = z
          .object({
            model: z.string(),
            system_prompt: z.instanceof(Prompt).describe("System prompt for the agent"),
          })
          .describe("PromptConfig");

        const storedPrompt = await client.createPrompt({
          name: `${projectName}-system`,
          prompt: "You are a helpful assistant. Think step by step.",
        });
        expect(storedPrompt.commit).toBeDefined();

        const versionName = await client.createAgentConfig(
          MyConfig,
          { model: "gpt-4", system_prompt: storedPrompt },
          { projectName }
        );
        expect(typeof versionName).toBe("string");

        const run = track(async () => {
          return client.getAgentConfigVersion(MyConfig, {
            fallback: { model: "fallback", system_prompt: storedPrompt },
            projectName,
            latest: true,
          });
        });
        const cfg = await run();

        expect(cfg.model).toBe("gpt-4");
        expect(cfg.system_prompt).toBeInstanceOf(Prompt);
        expect((cfg.system_prompt as Prompt).commit).toBe(storedPrompt.commit);
        expect((cfg.system_prompt as Prompt).prompt).toBe(
          "You are a helpful assistant. Think step by step."
        );
      },
      60_000
    );
  }
);
