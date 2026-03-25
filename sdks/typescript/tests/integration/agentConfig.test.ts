/**
 * Integration test for AgentConfig CRUD operations in the TypeScript SDK.
 * Requires a running Opik backend. See tests/integration/api/shouldRunIntegrationTests.ts.
 */
import { describe, it, expect, beforeAll } from "vitest";
import { Opik } from "@/index";
import { Blueprint } from "@/agent-config";
import { Prompt } from "@/prompt/Prompt";
import { PromptVersion } from "@/prompt/PromptVersion";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "AgentConfig CRUD Integration Test",
  () => {
    let client: Opik;

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik({});
    });

    it("should create a blueprint and retrieve it by latest", async () => {
      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const created = await agentConfig.createBlueprint({
        values: {
          temperature: 0.8,
          maxTokens: 100,
          stream: true,
          model: "gpt-4",
        },
        description: "Initial config",
      });

      expect(created).toBeInstanceOf(Blueprint);
      expect(created.id).toBeDefined();
      expect(created.type).toBe("blueprint");
      expect(created.get("temperature")).toBe(0.8);
      expect(created.get("maxTokens")).toBe(100);
      expect(created.get("stream")).toBe(true);
      expect(created.get("model")).toBe("gpt-4");

      const latest = await agentConfig.getBlueprint();
      expect(latest).not.toBeNull();
      expect(latest?.id).toBe(created.id);
      expect(latest?.get("temperature")).toBe(0.8);
      expect(latest?.get("maxTokens")).toBe(100);
      expect(latest?.get("stream")).toBe(true);
      expect(latest?.get("model")).toBe("gpt-4");
    });

    it("should retrieve a blueprint by ID", async () => {
      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const created = await agentConfig.createBlueprint({
        values: { temperature: "0.7" },
      });

      const fetched = await agentConfig.getBlueprint({ id: created.id });
      expect(fetched).not.toBeNull();
      expect(fetched?.id).toBe(created.id);
      expect(fetched?.get("temperature")).toBe("0.7");
    });

    it("should create a mask blueprint", async () => {
      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const baseBp = await agentConfig.createBlueprint({
        values: { temperature: "0.6", model: "gpt-3.5" },
        description: "Base config",
      });

      const maskId = await agentConfig.createMask({
        values: { temperature: "0.5" },
        description: "A/B variant",
      });

      expect(typeof maskId).toBe("string");
      expect(maskId).not.toBe(baseBp.id);

      const masked = await agentConfig.getBlueprint({ id: baseBp.id, maskId });
      expect(masked).not.toBeNull();
      expect(masked?.get("temperature")).toBe("0.5");
      expect(masked?.id).toBe(baseBp.id);
    });

    it("should tag a blueprint with an env label and retrieve it by env", async () => {
      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const created = await agentConfig.createBlueprint({
        values: { temperature: "0.9", model: "gpt-4o" },
        description: "Production config",
      });

      const envLabel = `test-env-${Date.now()}`;
      await agentConfig.tagBlueprintWithEnv(created.id, envLabel);

      const fetched = await agentConfig.getBlueprint({ env: envLabel });
      expect(fetched).not.toBeNull();
      expect(fetched?.id).toBe(created.id);
    });

    it("should resolve prompt and prompt_commit values from blueprint", async () => {
      const promptName = `test-prompt-${Date.now()}`;

      const promptV1 = await client.createPrompt({
        name: promptName,
        prompt: "Hello v1",
      });
      const commitV1 = promptV1.commit!;
      const versionIdV1 = promptV1.versionId;

      const versionsV1 = await promptV1.getVersions();
      const promptVersionV1 = versionsV1[0];
      expect(promptVersionV1.commit).toBe(commitV1);

      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const created = await agentConfig.createBlueprint({
        values: {
          systemPrompt: promptV1,
          pinnedVersion: promptVersionV1,
        },
        description: "Prompt resolution test",
      });

      expect(created.get("systemPrompt")).toBeInstanceOf(Prompt);
      expect(created.get("pinnedVersion")).toBeInstanceOf(PromptVersion);

      const resolvedPrompt = created.get("systemPrompt") as Prompt;
      expect(resolvedPrompt.versionId).toBe(versionIdV1);
      expect(resolvedPrompt.commit).toBe(commitV1);

      const resolvedVersion = created.get("pinnedVersion") as PromptVersion;
      expect(resolvedVersion.id).toBe(versionIdV1);
      expect(resolvedVersion.commit).toBe(commitV1);

      // Create v2 of the same prompt
      const promptV2 = await client.createPrompt({
        name: promptName,
        prompt: "Hello v2",
      });
      expect(promptV2.versionId).not.toBe(versionIdV1);
      const commitV2 = promptV2.commit!;

      // Re-fetch the blueprint new latest blueprint
      const fetched = await agentConfig.getBlueprint();
      expect(fetched).not.toBeNull();

      // New prompt is reflected
      const fetchedPrompt = fetched!.get("systemPrompt") as Prompt;
      expect(fetchedPrompt.commit).not.toBe(commitV1);
      expect(fetchedPrompt.commit).toBe(commitV2);

      // Pinned version stays pinned
      const fetchedVersion = fetched!.get("pinnedVersion") as PromptVersion;
      expect(fetchedVersion.id).toBe(versionIdV1);
      expect(fetchedVersion.commit).toBe(commitV1);
    });

    it("should return null for nonexistent blueprint ID", async () => {
      const testProjectName = `test-agent-config-${Date.now()}`;
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const result = await agentConfig.getBlueprint({
        id: "00000000-0000-0000-0000-000000000000",
      });
      expect(result).toBeNull();
    });
  }
);
