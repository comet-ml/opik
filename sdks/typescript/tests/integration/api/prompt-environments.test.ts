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

  it("creates a prompt with an initial environment", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const promptName = `env-create-${ts}`;
    await ensureEnvironment(stagingName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "Hello {{name}}",
      environments: [stagingName],
    });
    createdPromptIds.push(prompt.id!);

    expect(prompt.environments).toContain(stagingName);

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: stagingName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(prompt.commit);
    expect(retrieved?.environments).toContain(stagingName);
  }, 30000);

  it("creates a prompt with two environments", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const productionName = `production-${ts}`;
    const promptName = `env-multi-${ts}`;
    await ensureEnvironment(stagingName);
    await ensureEnvironment(productionName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "Hello {{name}}",
      environments: [stagingName, productionName],
    });
    createdPromptIds.push(prompt.id!);

    expect(prompt.environments).toContain(stagingName);
    expect(prompt.environments).toContain(productionName);

    const fromStaging = await client.getPrompt({
      name: promptName,
      environment: stagingName,
    });
    expect(fromStaging?.commit).toBe(prompt.commit);

    const fromProd = await client.getPrompt({
      name: promptName,
      environment: productionName,
    });
    expect(fromProd?.commit).toBe(prompt.commit);
  }, 30000);

  it("setPromptEnvironments replaces with the full provided set", async () => {
    const ts = Date.now();
    const envA = `staging-${ts}`;
    const envB = `production-${ts}`;
    const promptName = `env-replace-${ts}`;
    await ensureEnvironment(envA);
    await ensureEnvironment(envB);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "Hello {{name}}",
      environments: [envA],
    });
    createdPromptIds.push(prompt.id!);
    expect(prompt.environments).toContain(envA);

    await client.setPromptEnvironments({
      name: prompt.name,
      environments: [envA, envB],
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({
      name: promptName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.environments).toContain(envA);
    expect(retrieved?.environments).toContain(envB);
  }, 30000);

  it("moves environment ownership via setPromptEnvironments", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const productionName = `production-${ts}`;
    const promptName = `env-move-${ts}`;
    await ensureEnvironment(stagingName);
    await ensureEnvironment(productionName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environments: [stagingName],
    });
    createdPromptIds.push(prompt.id!);
    expect(prompt.environments).toContain(stagingName);

    await client.setPromptEnvironments({
      name: prompt.name,
      environments: [productionName],
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: productionName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(prompt.commit);
    expect(retrieved?.environments).toContain(productionName);
    expect(retrieved?.environments).not.toContain(stagingName);
  }, 30000);

  it("targets a specific version when commit is provided", async () => {
    const ts = Date.now();
    const envName = `staging-${ts}`;
    const promptName = `env-commit-${ts}`;
    await ensureEnvironment(envName);

    const v1 = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
    });
    createdPromptIds.push(v1.id!);
    const v1Commit = v1.commit!;
    expect(v1Commit).toBeTruthy();

    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "different template {{x}}",
    });
    expect(v2.commit).not.toBe(v1Commit);

    await client.setPromptEnvironments({
      name: promptName,
      environments: [envName],
      commit: v1Commit,
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: envName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(v1Commit);
    expect(retrieved?.environments).toContain(envName);
  }, 30000);

  it("clears environment ownership via setPromptEnvironments with an empty list", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const promptName = `env-clear-${ts}`;
    await ensureEnvironment(stagingName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environments: [stagingName],
    });
    createdPromptIds.push(prompt.id!);
    expect(prompt.environments).toContain(stagingName);

    await client.setPromptEnvironments({
      name: prompt.name,
      environments: [],
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({
      name: promptName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.environments ?? []).toHaveLength(0);
  }, 30000);

  it("sets environments by prompt name without a prompt instance", async () => {
    const ts = Date.now();
    const productionName = `production-${ts}`;
    const promptName = `env-by-name-${ts}`;
    await ensureEnvironment(productionName);

    const created = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
    });
    createdPromptIds.push(created.id!);

    await client.setPromptEnvironments({
      name: promptName,
      environments: [productionName],
    });
    getGlobalCache().clear();

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: productionName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(created.commit);
    expect(retrieved?.environments).toContain(productionName);
  }, 30000);

  it("retrieves the correct version by environment", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const productionName = `production-${ts}`;
    const promptName = `env-get-${ts}`;
    await ensureEnvironment(stagingName);
    await ensureEnvironment(productionName);

    const v1 = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environments: [productionName],
    });
    createdPromptIds.push(v1.id!);

    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "v2 {{x}}",
      environments: [stagingName],
    });

    const fromProd = await client.getPrompt({
      name: promptName,
      environment: productionName,
    });
    expect(fromProd?.commit).toBe(v1.commit);

    const fromStaging = await client.getPrompt({
      name: promptName,
      environment: stagingName,
    });
    expect(fromStaging?.commit).toBe(v2.commit);
  }, 30000);

  it("rejects setPromptEnvironments with an unknown environment", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const promptName = `env-404-${ts}`;
    await ensureEnvironment(stagingName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1",
      environments: [stagingName],
    });
    createdPromptIds.push(prompt.id!);

    await expect(
      client.setPromptEnvironments({
        name: prompt.name,
        environments: [`does-not-exist-${ts}`],
      }),
    ).rejects.toBeInstanceOf(EnvironmentNotFoundError);
  }, 30000);
});
