import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { OpikApiError } from "@/rest_api";
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
      environment: stagingName,
    });
    createdPromptIds.push(prompt.id!);

    expect(prompt.environment).toBe(stagingName);

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: stagingName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(prompt.commit);
    expect(retrieved?.environment).toBe(stagingName);
  }, 30000);

  it("moves environment ownership via setEnvironment", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const productionName = `production-${ts}`;
    const promptName = `env-move-${ts}`;
    await ensureEnvironment(stagingName);
    await ensureEnvironment(productionName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environment: stagingName,
    });
    createdPromptIds.push(prompt.id!);
    expect(prompt.environment).toBe(stagingName);

    await prompt.setEnvironment(productionName);
    expect(prompt.environment).toBe(productionName);

    const retrieved = await client.getPrompt({
      name: promptName,
      environment: productionName,
    });
    expect(retrieved).not.toBeNull();
    expect(retrieved?.commit).toBe(prompt.commit);
    expect(retrieved?.environment).toBe(productionName);
  }, 30000);

  it("clears environment ownership via setEnvironment(null)", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const promptName = `env-clear-${ts}`;
    await ensureEnvironment(stagingName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1 {{x}}",
      environment: stagingName,
    });
    createdPromptIds.push(prompt.id!);
    expect(prompt.environment).toBe(stagingName);

    await prompt.setEnvironment(null);
    expect(prompt.environment).toBeUndefined();
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
      environment: productionName,
    });
    createdPromptIds.push(v1.id!);

    const v2 = await client.createPrompt({
      name: promptName,
      prompt: "v2 {{x}}",
      environment: stagingName,
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

  it("rejects setEnvironment with an unknown environment (404)", async () => {
    const ts = Date.now();
    const stagingName = `staging-${ts}`;
    const promptName = `env-404-${ts}`;
    await ensureEnvironment(stagingName);

    const prompt = await client.createPrompt({
      name: promptName,
      prompt: "v1",
      environment: stagingName,
    });
    createdPromptIds.push(prompt.id!);

    await expect(
      prompt.setEnvironment(`does-not-exist-${ts}`),
    ).rejects.toMatchObject({
      statusCode: 404,
    } satisfies Partial<OpikApiError>);
  }, 30000);
});
