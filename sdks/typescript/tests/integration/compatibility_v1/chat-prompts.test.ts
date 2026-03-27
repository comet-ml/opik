/**
 * COMPATIBILITY V1 TEST — DO NOT MODIFY
 *
 * This is a frozen copy of the original chat prompts integration test from before
 * the projectName parameter was added to the SDK API. It ensures backward
 * compatibility: users who never specify projectName should experience zero
 * regressions.
 *
 * If you need to add new prompt tests, add them to the parent directory
 * (tests/integration/chat-prompts.test.ts), not here.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { PromptType } from "@/prompt/types";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { cleanupPrompts } from "../evaluation/helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "Compatibility V1: ChatPrompt Integration Tests",
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

    afterAll(async () => {
      if (client) {
        await client.flush();
      }

      await cleanupPrompts(client, createdPromptIds);
    });

    it("should create and retrieve a chat prompt", async () => {
      const testPromptName = `compat-v1-chat-prompt-${Date.now()}`;

      const chatPrompt = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant.",
          },
          {
            role: "user",
            content: "Hello {{name}}, how can I help you with {{topic}}?",
          },
        ],
        description: "Compatibility V1 test chat prompt",
        tags: ["test", "compat-v1"],
        type: PromptType.MUSTACHE,
      });

      createdPromptIds.push(chatPrompt.id);

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(chatPrompt.name).toBe(testPromptName);
      expect(chatPrompt.messages).toHaveLength(2);
      expect(chatPrompt.description).toBe("Compatibility V1 test chat prompt");
      expect(chatPrompt.type).toBe(PromptType.MUSTACHE);

      const retrieved = await client.getChatPrompt({ name: testPromptName });
      expect(retrieved).not.toBeNull();
      expect(retrieved!.name).toBe(testPromptName);
      expect(retrieved!.messages).toHaveLength(chatPrompt.messages.length);
    });

    it("should format a chat prompt with variables", async () => {
      const testPromptName = `compat-v1-format-${Date.now()}`;

      const chatPrompt = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "system",
            content: "You are a helpful assistant.",
          },
          {
            role: "user",
            content: "Hello {{name}}, how can I help you with {{topic}}?",
          },
        ],
        type: PromptType.MUSTACHE,
      });
      createdPromptIds.push(chatPrompt.id);

      const formatted = chatPrompt.format({
        name: "Alice",
        topic: "TypeScript",
      });

      expect(formatted).toHaveLength(2);
      expect(formatted[0].role).toBe("system");
      expect(formatted[0].content).toBe("You are a helpful assistant.");
      expect(formatted[1].role).toBe("user");
      expect(formatted[1].content).toBe(
        "Hello Alice, how can I help you with TypeScript?"
      );
    });

    it("should create a new prompt version with different template", async () => {
      const testPromptName = `compat-v1-version-${Date.now()}`;

      const promptV1 = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "user",
            content: "Hello {{name}}",
          },
        ],
        type: PromptType.MUSTACHE,
      });
      createdPromptIds.push(promptV1.id);

      const promptV2 = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "user",
            content: "Hi there {{name}}, welcome!",
          },
        ],
        type: PromptType.MUSTACHE,
      });

      expect(promptV2.id).toBe(promptV1.id);
      expect(promptV2.commit).not.toBe(promptV1.commit);

      const formatted = promptV2.format({ name: "Bob" });
      expect(formatted[0].content).toBe("Hi there Bob, welcome!");
    });

    it("should not create a new version for identical template", async () => {
      const testPromptName = `compat-v1-idempotent-${Date.now()}`;

      const promptV1 = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "user",
            content: "Hello {{name}}",
          },
        ],
        type: PromptType.MUSTACHE,
      });
      createdPromptIds.push(promptV1.id);

      const promptV2 = await client.createChatPrompt({
        name: testPromptName,
        messages: [
          {
            role: "user",
            content: "Hello {{name}}",
          },
        ],
        type: PromptType.MUSTACHE,
      });

      expect(promptV2.id).toBe(promptV1.id);
      expect(promptV2.commit).toBe(promptV1.commit);
    });

    it("should delete a chat prompt", async () => {
      const tempPromptName = `compat-v1-delete-${Date.now()}`;

      const chatPrompt = await client.createChatPrompt({
        name: tempPromptName,
        messages: [
          {
            role: "user",
            content: "Temporary prompt",
          },
        ],
      });

      let retrieved = await client.getChatPrompt({ name: tempPromptName });
      expect(retrieved).not.toBeNull();

      await chatPrompt.delete();

      retrieved = await client.getChatPrompt({ name: tempPromptName });
      expect(retrieved).toBeNull();
    });
  }
);
