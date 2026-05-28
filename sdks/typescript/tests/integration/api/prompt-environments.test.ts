import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { EnvironmentNotFoundError } from "@/prompt/errors";
import { getGlobalCache } from "@/prompt/promptCache";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Prompt Environments Integration", () => {
  let client: Opik;
  const createdPromptIds: string[] = [];
  const createdEnvironmentNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterEach(async () => {
    getGlobalCache().clear();
    if (createdPromptIds.length > 0) {
      try {
        await client.deletePrompts(createdPromptIds);
      } catch (error) {
        console.warn(`Failed to cleanup prompts:`, error);
      }
      createdPromptIds.length = 0;
    }
    while (createdEnvironmentNames.length > 0) {
      const name = createdEnvironmentNames.pop()!;
      try {
        await client.deleteEnvironment(name);
      } catch (error) {
        console.warn(`Failed to cleanup environment '${name}':`, error);
      }
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  const ensureEnvironment = async (name: string): Promise<void> => {
    try {
      await client.createEnvironment(name);
      createdEnvironmentNames.push(name);
    } catch (error) {
      // If the environment already exists in this workspace, reuse it.
      const message = error instanceof Error ? error.message : String(error);
      if (!message.toLowerCase().includes("already exists")) {
        throw error;
      }
    }
  };

  it("lifecycle: create with one env → expand to two → move → clear", async () => {
    const ts = Date.now();
    const envA = `staging-${ts}`;
    const envB = `production-${ts}`;
    const promptName = `env-lifecycle-${ts}`;
    await ensureEnvironment(envA);
    await ensureEnvironment(envB);

    // Step 1: create v1 pinned to envA; a newer v2 exists without any env.
    // Fetching by environment must resolve to v1, not the latest.
    const v1 = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environments: [envA],
    });
    createdPromptIds.push(v1.id!);
    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "v2 {{x}}",
    });
    expect(v2.commit).not.toBe(v1.commit);

    const resolvedByEnvA = await client.getPrompt({
      name: promptName,
      environment: envA,
    });
    expect(resolvedByEnvA).not.toBeNull();
    expect(resolvedByEnvA?.commit).toBe(v1.commit);
    expect(resolvedByEnvA?.environments).toContain(envA);

    // Step 2: expand to both envs on v1's commit.
    await client.setPromptEnvironments({
      promptName,
      environments: [envA, envB],
      commit: v1.commit,
    });
    getGlobalCache().clear();

    const fromA = await client.getPrompt({ name: promptName, environment: envA });
    const fromB = await client.getPrompt({ name: promptName, environment: envB });
    expect(fromA).not.toBeNull();
    expect(fromB).not.toBeNull();
    expect(fromA?.commit).toBe(v1.commit);
    expect(fromB?.commit).toBe(v1.commit);
    expect(fromA?.environments).toContain(envA);
    expect(fromA?.environments).toContain(envB);

    // Step 3: move ownership exclusively to envB.
    await client.setPromptEnvironments({ promptName, environments: [envB] });
    getGlobalCache().clear();

    const afterMove = await client.getPrompt({ name: promptName });
    expect(afterMove).not.toBeNull();
    expect(afterMove?.environments).toContain(envB);
    expect(afterMove?.environments).not.toContain(envA);

    // Step 4: clear all environments.
    await client.setPromptEnvironments({ promptName, environments: [] });
    getGlobalCache().clear();

    const afterClear = await client.getPrompt({ name: promptName });
    expect(afterClear).not.toBeNull();
    expect(afterClear?.environments ?? []).toHaveLength(0);
  }, 30000);

  it("set with explicit commit pins the env to that version", async () => {
    const ts = Date.now();
    const envName = `staging-${ts}`;
    const promptName = `env-commit-${ts}`;
    await ensureEnvironment(envName);

    const v1 = await client.createPrompt({ name: promptName, prompt: "v1 {{x}}" });
    createdPromptIds.push(v1.id!);
    const v1Commit = v1.commit!;
    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "different template {{x}}",
    });
    expect(v2.commit).not.toBe(v1Commit);

    await client.setPromptEnvironments({
      promptName,
      environments: [envName],
      commit: v1Commit,
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({ name: promptName, environment: envName });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(v1Commit);
    expect(retrieved?.prompt).toBe("v1 {{x}}");
    expect(retrieved?.environments).toContain(envName);
  }, 30000);

  it("each environment resolves to its own pinned version", async () => {
    const ts = Date.now();
    const envProd = `production-${ts}`;
    const envStaging = `staging-${ts}`;
    const promptName = `env-get-${ts}`;
    await ensureEnvironment(envProd);
    await ensureEnvironment(envStaging);

    const v1 = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environments: [envProd],
    });
    createdPromptIds.push(v1.id!);
    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "v2 {{x}}",
      environments: [envStaging],
    });

    const fromProd = await client.getPrompt({ name: promptName, environment: envProd });
    expect(fromProd?.commit).toBe(v1.commit);

    const fromStaging = await client.getPrompt({ name: promptName, environment: envStaging });
    expect(fromStaging?.commit).toBe(v2.commit);
  }, 30000);

  it("rejects setPromptEnvironments with an unknown environment", async () => {
    const ts = Date.now();
    const promptName = `env-404-${ts}`;

    const prompt = await client.createPrompt({ name: promptName, prompt: "v1" });
    createdPromptIds.push(prompt.id!);

    await expect(
      client.setPromptEnvironments({
        promptName: prompt.name,
        environments: [`does-not-exist-${ts}`],
      }),
    ).rejects.toBeInstanceOf(EnvironmentNotFoundError);
  }, 30000);
});
