import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { getGlobalCache } from "@/prompt";
import { promptMaskContext } from "@/prompt/maskContext";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

async function createMaskVersion(
  client: Opik,
  name: string,
  promptId: string,
  template: string,
  templateStructure: "text" | "chat" = "text"
) {
  return client.api.prompts.createPromptVersion(
    {
      name,
      version: {
        template,
        promptId,
        versionType: "mask",
      },
      templateStructure,
    },
    client.api.requestOptions
  );
}

describe.skipIf(!shouldRunApiTests)("Prompt Mask Integration", () => {
  let client: Opik;
  const createdPromptIds: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());
    if (!shouldRunApiTests) return;
    client = new Opik();
  });

  afterEach(async () => {
    getGlobalCache().clear();
    if (createdPromptIds.length > 0) {
      try {
        await client.deletePrompts(createdPromptIds);
      } catch (error) {
        console.warn("Failed to cleanup prompts:", error);
      }
      createdPromptIds.length = 0;
    }
  });

  afterAll(async () => {
    if (client) await client.flush();
  });

  it("getPrompt returns masked template when mask context is active", async () => {
    const name = `mask-text-${Date.now()}`;
    const originalTemplate = `original-${Date.now()}`;
    const maskTemplate = `masked-${Date.now()}`;

    const prompt = await client.createPrompt({
      name,
      prompt: originalTemplate,
    });
    createdPromptIds.push(prompt.id!);

    const maskVersion = await createMaskVersion(
      client,
      name,
      prompt.id!,
      maskTemplate
    );

    getGlobalCache().clear();

    const masked = await promptMaskContext(
      { [prompt.id!]: maskVersion.id! },
      () => client.getPrompt({ name })
    );

    expect(masked).not.toBeNull();
    expect(masked!.prompt).toBe(maskTemplate);
    expect(masked!.versionId).toBe(maskVersion.id);

    getGlobalCache().clear();
    const unmasked = await client.getPrompt({ name });
    expect(unmasked).not.toBeNull();
    expect(unmasked!.prompt).toBe(originalTemplate);

    const versionIds = (await prompt.getVersions()).map((v) => v.id);
    expect(versionIds).not.toContain(maskVersion.id);
  }, 30000);

  it("getChatPrompt returns masked messages when mask context is active", async () => {
    const name = `mask-chat-${Date.now()}`;
    const originalMessages = [
      { role: "user" as const, content: `original-${Date.now()}` },
    ];
    const maskedMessages = [
      { role: "system" as const, content: `masked-${Date.now()}` },
    ];

    const chatPrompt = await client.createChatPrompt({
      name,
      messages: originalMessages,
    });
    createdPromptIds.push(chatPrompt.id!);

    const maskVersion = await createMaskVersion(
      client,
      name,
      chatPrompt.id!,
      JSON.stringify(maskedMessages),
      "chat"
    );

    getGlobalCache().clear();

    const masked = await promptMaskContext(
      { [chatPrompt.id!]: maskVersion.id! },
      () => client.getChatPrompt({ name })
    );

    expect(masked).not.toBeNull();
    expect(masked!.versionId).toBe(maskVersion.id);
    expect(masked!.messages[0].content).toBe(maskedMessages[0].content);
    expect(masked!.messages[0].role).toBe(maskedMessages[0].role);

    getGlobalCache().clear();
    const unmasked = await client.getChatPrompt({ name });
    expect(unmasked).not.toBeNull();
    expect(unmasked!.messages[0].content).toBe(originalMessages[0].content);

    const versionIds = (await chatPrompt.getVersions()).map((v) => v.id);
    expect(versionIds).not.toContain(maskVersion.id);
  }, 30000);

  it("getPrompt without mask context returns original even if a mask exists", async () => {
    const name = `mask-no-context-${Date.now()}`;
    const originalTemplate = `original-${Date.now()}`;
    const prompt = await client.createPrompt({
      name,
      prompt: originalTemplate,
    });
    createdPromptIds.push(prompt.id!);

    await createMaskVersion(
      client,
      name,
      prompt.id!,
      `masked-${Date.now()}`
    );

    getGlobalCache().clear();
    const result = await client.getPrompt({ name });
    expect(result).not.toBeNull();
    expect(result!.prompt).toBe(originalTemplate);
  }, 30000);

});
