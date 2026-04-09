/**
 * COMPATIBILITY V1 TEST — DO NOT MODIFY
 *
 * This is a frozen copy of the original text prompt integration test from before
 * the projectName parameter was added to the SDK API. It ensures backward
 * compatibility: users who never specify projectName should experience zero
 * regressions.
 *
 * If you need to add new prompt tests, add them to the parent directory, not here.
 */
import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { PromptValidationError } from "@/prompt/errors";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "Compatibility V1: Prompt Integration Tests",
  () => {
    let client: Opik;
    const createdPromptIds: string[] = [];

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
    });

    afterAll(async () => {
      if (client) {
        await client.flush();
      }
    });

    it("should handle complete prompt lifecycle: create, format, update, retrieve, delete", async () => {
      const promptName = `compat-v1-lifecycle-${Date.now()}`;

      // CREATE
      const created = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}, welcome to {{place}}!",
        metadata: { author: "compat-test", version: "1.0" },
      });
      createdPromptIds.push(created.id!);

      expect(created.name).toBe(promptName);
      expect(created.prompt).toBe("Hello {{name}}, welcome to {{place}}!");

      // FORMAT
      const formatted = created.format({ name: "Alice", place: "Wonderland" });
      expect(formatted).toBe("Hello Alice, welcome to Wonderland!");

      // UPDATE (create new version)
      const updated = await client.createPrompt({
        name: promptName,
        prompt: "Hi {{name}}, welcome to {{place}}!",
        metadata: { author: "compat-test", version: "2.0" },
      });

      expect(updated.name).toBe(promptName);
      expect(updated.prompt).toBe("Hi {{name}}, welcome to {{place}}!");

      // RETRIEVE latest version
      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved).not.toBeNull();
      expect(retrieved?.prompt).toBe("Hi {{name}}, welcome to {{place}}!");

      // DELETE
      expect(created.id).toBe(updated.id);
      await client.deletePrompts([created.id!]);

      const deleted = await client.getPrompt({ name: promptName });
      expect(deleted).toBeNull();

      createdPromptIds.length = 0;
    }, 30000);

    it("should not create new version with the same template", async () => {
      const promptName = `compat-v1-idempotent-${Date.now()}`;
      const template = "Hello {{name}}!";

      const v1 = await client.createPrompt({
        name: promptName,
        prompt: template,
      });
      createdPromptIds.push(v1.id!);

      const v2 = await client.createPrompt({
        name: promptName,
        prompt: template,
      });

      expect(v2.id).toBe(v1.id);
      expect(v2.commit).toBe(v1.commit);
    }, 30000);

    it("should get prompt by name, return null for non-existent", async () => {
      const promptName = `compat-v1-get-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test {{v}}",
      });
      createdPromptIds.push(prompt.id!);

      const retrieved = await client.getPrompt({ name: promptName });
      expect(retrieved).not.toBeNull();
      expect(retrieved!.name).toBe(promptName);

      const missing = await client.getPrompt({
        name: `non-existent-${Date.now()}`,
      });
      expect(missing).toBeNull();
    }, 30000);

    it("should format with multiple variables", async () => {
      const promptName = `compat-v1-format-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "User: {{user}}\nQuestion: {{question}}\nContext: {{context}}",
      });
      createdPromptIds.push(prompt.id!);

      const formatted = prompt.format({
        user: "Alice",
        question: "What is AI?",
        context: "ML basics",
      });

      expect(formatted).toBe("User: Alice\nQuestion: What is AI?\nContext: ML basics");
    }, 30000);

    it("should throw when required variables are missing", async () => {
      const promptName = `compat-v1-missing-${Date.now()}`;

      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Hello {{name}}, score: {{score}}",
      });
      createdPromptIds.push(prompt.id!);

      expect(() => {
        prompt.format({ name: "Alice" });
      }).toThrow(PromptValidationError);
    }, 30000);
  }
);
