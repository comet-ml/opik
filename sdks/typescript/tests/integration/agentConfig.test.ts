/**
 * Integration test for AgentConfig CRUD operations in the TypeScript SDK.
 * Requires a running Opik backend. See tests/integration/api/shouldRunIntegrationTests.ts.
 */
import { describe, it, expect, beforeAll } from "vitest";
import { Opik } from "@/index";
import { Blueprint } from "@/agent-config";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "AgentConfig CRUD Integration Test",
  () => {
    let client: Opik;
    const testProjectName = `test-agent-config-${Date.now()}`;

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik({ projectName: testProjectName });
    });

    it("should create a blueprint and retrieve it by latest", async () => {
      const agentConfig = client.getAgentConfig({
        projectName: testProjectName,
      });

      const created = await agentConfig.createBlueprint({
        values: { temperature: "0.8", model: "gpt-4" },
        description: "Initial config",
      });

      expect(created).toBeInstanceOf(Blueprint);
      expect(created.id).toBeDefined();
      expect(created.type).toBe("blueprint");
      expect(created.get("temperature")).toBe("0.8");
      expect(created.get("model")).toBe("gpt-4");

      const latest = await agentConfig.getBlueprint();
      expect(latest).not.toBeNull();
      expect(latest?.id).toBe(created.id);
      expect(latest?.get("temperature")).toBe("0.8");
    });

    it("should retrieve a blueprint by ID", async () => {
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

    it("should return null for nonexistent blueprint ID", async () => {
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
