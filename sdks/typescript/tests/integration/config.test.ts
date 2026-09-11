/**
 * E2E integration tests for the high-level Config API.
 * Requires a running Opik backend. See tests/integration/api/shouldRunIntegrationTests.ts.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik, track, agentConfigContext, Prompt } from "@/index";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { ConfigManager } from "@/agent-config";
import { getTrackContext, getTrackOpikClient } from "@/decorators/track";
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
  "Config high-level API — E2E",
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

    /**
     * Fetches a single trace by ID and returns the agent_configuration block from
     * its metadata, waiting up to 30 s for the trace to be indexed.
     */
    const fetchAgentConfigMeta = async (
      traceId: string,
      projectName: string
    ): Promise<Record<string, unknown>> => {
      const traces = await client.searchTraces({
        projectName,
        filterString: `id = "${traceId}" AND metadata.agent_configuration contains ""`,
        waitForAtLeast: 1,
        waitForTimeout: 30,
      });
      const meta = traces[0]?.metadata as Record<string, unknown> | undefined;
      expect(meta?.agent_configuration).toBeDefined();
      return meta!.agent_configuration as Record<string, unknown>;
    };

    /**
     * Asserts the shape of one field entry inside agent_configuration.values.
     * Pass `closeToValue` for floats that need approximate comparison.
     */
    const verifyAgentConfigField = (
      values: Record<string, { value: unknown; type: string }>,
      key: string,
      expected: { type: string; value?: unknown; closeToValue?: number }
    ): void => {
      const entry = values[key];
      expect(entry).toBeDefined();
      expect(entry.type).toBe(expected.type);
      if (expected.closeToValue !== undefined) {
        expect(entry.value).toBeCloseTo(expected.closeToValue);
      } else if ("value" in expected) {
        expect(entry.value).toBe(expected.value);
      }
    };

    // ─── Test 1: auto-create from fallback, createConfig, getOrCreateConfig by version / env ───

    it(
      "should auto-create from fallback on empty project, publish config, then retrieve by version name and env",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        // Empty project: getOrCreateConfig auto-creates from fallback
        const fallback = { temperature: 0.3, model: "gpt-3.5-mini" };
        const autoCreated = await track({ projectName }, async () =>
          client.getOrCreateConfig({ fallback, projectName })
        )();
        expect(autoCreated.temperature).toBeCloseTo(0.3);
        expect(autoCreated.model).toBe("gpt-3.5-mini");
        expect(autoCreated.isFallback).toBe(false);
        expect(await getBlueprintHistoryLength(client, projectName)).toBe(1);

        // Publish v1 explicitly (becomes v2 in history)
        const v1Name = await client.createConfig(
          { temperature: 0.5, model: "gpt-3.5" },
          { projectName }
        );
        expect(typeof v1Name).toBe("string");
        expect(v1Name.length).toBeGreaterThan(0);
        expect(await getBlueprintHistoryLength(client, projectName)).toBe(2);

        // Publish v2 with different values → new version
        const v2Name = await client.createConfig(
          { temperature: 0.8, model: "gpt-4", hint: "use chain-of-thought" },
          { projectName }
        );
        expect(v2Name).not.toBe(v1Name);
        expect(await getBlueprintHistoryLength(client, projectName)).toBe(3);

        // Retrieve by version name (v1)
        const fetchByName = track(async () => {
          return client.getOrCreateConfig({
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            version: v1Name,
          });
        });
        const byName = await fetchByName();
        expect(byName.temperature).toBeCloseTo(0.5);
        expect(byName.isFallback).toBe(false);
        expect(byName.blueprintId).toBeDefined();
        expect(byName.blueprintVersion).toBe(v1Name);

        // Tag v1 to "staging" then retrieve by env
        await client.setConfigEnv({ version: v1Name, env: "staging", projectName });

        const fetchByEnv = track(async () => {
          return client.getOrCreateConfig({
            fallback: { temperature: 0, model: "fallback" },
            projectName,
            env: "staging",
          });
        });
        const byEnv = await fetchByEnv();
        expect(byEnv.temperature).toBeCloseTo(0.5);
        expect(byEnv.blueprintVersion).toBe(v1Name);
      },
      60_000
    );

    // ─── Test 2: trace metadata injection on field access ───

    it(
      "should inject agent_configuration into trace and span metadata on field access",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        await client.createConfig(
          { temperature: 0.7, model: "gpt-4" },
          { projectName }
        );

        // Tag with "prod" so getOrCreateConfig default lookup works
        const manager = new ConfigManager(projectName, client);
        const latest = await manager.getBlueprint();
        expect(latest).not.toBeNull();
        await client.setConfigEnv({ version: latest!.name ?? latest!.id, env: "prod", projectName });

        let traceId: string | undefined;

        const run = track({ projectName }, async () => {
          const cfg = await client.getOrCreateConfig({
            fallback: { temperature: 0, model: "fallback" },
            projectName,
          });

          // Access fields to trigger metadata injection
          void cfg.temperature;
          void cfg.model;

          const ctx = getTrackContext();
          traceId = ctx?.trace?.data?.id;

          return cfg;
        });

        const cfg = await run();
        expect(cfg.temperature).toBeCloseTo(0.7);
        await getTrackOpikClient().flush();

        expect(traceId).toBeDefined();

        const agentMeta = await fetchAgentConfigMeta(traceId!, projectName);
        expect(agentMeta._blueprint_id).toBeDefined();
        expect(agentMeta.blueprint_version).toBeDefined();

        const agentValues = agentMeta.values as Record<string, { value: unknown; type: string }>;
        verifyAgentConfigField(agentValues, "temperature", { type: "float", closeToValue: 0.7 });
        verifyAgentConfigField(agentValues, "model", { type: "string", value: "gpt-4" });
      },
      60_000
    );

    // ─── Test 3: mask overrides and blueprint version context ───

    it(
      "should overlay mask values on top of the base blueprint and respect blueprint version context",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        // Publish v1 (becomes "prod") and v2
        const v1Name = await client.createConfig(
          { temperature: 0.5, model: "gpt-4" },
          { projectName }
        );
        const v2Name = await client.createConfig(
          { temperature: 0.7, model: "gpt-4o" },
          { projectName }
        );
        expect(v1Name).not.toBe(v2Name);

        const manager = new ConfigManager(projectName, client);

        // Tag v1 as "prod" so the default lookup returns v1
        await client.setConfigEnv({ version: v1Name, env: "prod", projectName });

        // Create a mask that overrides temperature only
        const maskId = await manager.createMask({
          values: [{ key: "temperature", value: "0.9", type: "float" }],
        });

        const fetchConfig = track(async () => {
          return client.getOrCreateConfig({
            fallback: { temperature: 0, model: "fallback" },
            projectName,
          });
        });

        // Baseline: no context → prod (v1) values
        const without = await fetchConfig();
        expect(without.temperature).toBeCloseTo(0.5);
        expect(without.model).toBe("gpt-4");

        // Mask alone: overlays temperature on v1; model stays from v1
        let withMask: Awaited<ReturnType<typeof fetchConfig>> | undefined;
        await agentConfigContext({ maskId }, async () => {
          withMask = await fetchConfig();
        });
        expect(withMask?.temperature).toBeCloseTo(0.9);
        expect(withMask?.model).toBe("gpt-4");

        // Blueprint version context: pin v2 explicitly, no mask → v2 values returned
        let withBlueprintV2: Awaited<ReturnType<typeof fetchConfig>> | undefined;
        await agentConfigContext({ blueprintName: v2Name }, async () => {
          withBlueprintV2 = await fetchConfig();
        });
        expect(withBlueprintV2?.temperature).toBeCloseTo(0.7);
        expect(withBlueprintV2?.model).toBe("gpt-4o");

        // Blueprint version + mask: pin v2 AND apply mask → temperature masked, model from v2
        let withBlueprintAndMask: Awaited<ReturnType<typeof fetchConfig>> | undefined;
        await agentConfigContext({ blueprintName: v2Name, maskId }, async () => {
          withBlueprintAndMask = await fetchConfig();
        });
        expect(withBlueprintAndMask?.temperature).toBeCloseTo(0.9);
        expect(withBlueprintAndMask?.model).toBe("gpt-4o");
      },
      60_000
    );

    // ─── Test 4: Prompt stored in config, retrieved as Prompt instance ───

    it(
      "should store a Prompt in config and retrieve it as a Prompt instance",
      async () => {
        const projectName = uniqueProject();
        projectsToCleanup.push(projectName);

        const storedPrompt = await client.createPrompt({
          name: `${projectName}-system`,
          prompt: "You are a helpful assistant. Think step by step.",
        });
        expect(storedPrompt.commit).toBeDefined();

        const storedChatPrompt = await client.createChatPrompt({
          name: `${projectName}-chat`,
          messages: [
            { role: "system", content: "You are a helpful assistant." },
            { role: "user", content: "Help me with {{task}}" },
          ],
        });
        expect(storedChatPrompt.commit).toBeDefined();

        // Use ConfigManager to publish config with prompt values
        const manager = new ConfigManager(projectName, client);
        const { serializeValuesRecord } = await import("@/typeHelpers");
        const created = await manager.createBlueprint({
          values: serializeValuesRecord({
            model: "gpt-4",
            system_prompt: storedPrompt,
            chat_prompt: storedChatPrompt,
          }),
        });
        await client.setConfigEnv({ version: created.name ?? created.id, env: "prod", projectName });

        let traceId: string | undefined;

        const run = track({ projectName }, async () => {
          const cfg = await client.getOrCreateConfig({
            fallback: { model: "fallback", system_prompt: storedPrompt, chat_prompt: storedChatPrompt },
            projectName,
          });

          void cfg.model;
          void cfg.system_prompt;
          void cfg.chat_prompt;

          const ctx = getTrackContext();
          traceId = ctx?.trace?.data?.id;

          return cfg;
        });
        const cfg = await run();

        expect(cfg.model).toBe("gpt-4");

        // Verify Prompt comes back as Prompt, not ChatPrompt
        expect(cfg.system_prompt).toBeInstanceOf(Prompt);
        expect(cfg.system_prompt).not.toBeInstanceOf(ChatPrompt);
        expect((cfg.system_prompt as Prompt).commit).toBe(storedPrompt.commit);

        // Verify ChatPrompt comes back as ChatPrompt
        expect(cfg.chat_prompt).toBeInstanceOf(ChatPrompt);
        expect((cfg.chat_prompt as ChatPrompt).messages).toEqual([
          { role: "system", content: "You are a helpful assistant." },
          { role: "user", content: "Help me with {{task}}" },
        ]);

        await getTrackOpikClient().flush();
        expect(traceId).toBeDefined();

        const agentMeta = await fetchAgentConfigMeta(traceId!, projectName);
        const agentValues = agentMeta.values as Record<string, { value: unknown; type: string }>;
        verifyAgentConfigField(agentValues, "model", { type: "string", value: "gpt-4" });
        // prompt fields must be serialized to their commit strings
        verifyAgentConfigField(agentValues, "system_prompt", {
          type: "prompt",
          value: storedPrompt.commit,
        });
        verifyAgentConfigField(agentValues, "chat_prompt", {
          type: "prompt",
          value: storedChatPrompt.commit,
        });

        // Without fallback: prompt types must still resolve correctly from backend-declared types
        const { getGlobalBlueprintRegistry } = await import("@/agent-config/blueprintCache");
        getGlobalBlueprintRegistry().clear();

        const noFallbackCfg = await track({ projectName }, async () =>
          client.getOrCreateConfig({ projectName })
        )();

        expect(noFallbackCfg.model).toBe("gpt-4");
        expect(noFallbackCfg.system_prompt).toBeInstanceOf(Prompt);
        expect(noFallbackCfg.system_prompt).not.toBeInstanceOf(ChatPrompt);
        expect((noFallbackCfg.system_prompt as Prompt).commit).toBe(storedPrompt.commit);
        expect(noFallbackCfg.chat_prompt).toBeInstanceOf(ChatPrompt);
        expect((noFallbackCfg.chat_prompt as ChatPrompt).messages).toEqual([
          { role: "system", content: "You are a helpful assistant." },
          { role: "user", content: "Help me with {{task}}" },
        ]);
      },
      60_000
    );
  }
);
