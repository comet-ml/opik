import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import {
  PromptType,
  ContentPart,
  TextContentPart,
  MessageContent,
} from "@/prompt/types";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";
import { cleanupPrompts } from "./evaluation/helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

/**
 * Helper function to analyze formatted message content for modality filtering tests
 * @param content - The message content to analyze (can be string or array)
 * @returns Object with text content and presence flags for image/video parts
 */
function analyzeContentParts(content: MessageContent): {
  textContent: string;
  hasImagePart: boolean;
  hasVideoPart: boolean;
} {
  // Handle string content (collapsed when unsupported modalities are present)
  if (typeof content === "string") {
    return {
      textContent: content,
      hasImagePart: false,
      hasVideoPart: false,
    };
  }

  // Handle array content
  expect(Array.isArray(content)).toBe(true);
  const contentArray = content as ContentPart[];

  const textContent = contentArray
    .filter((part) => part.type === "text")
    .map((part) => (part as TextContentPart).text)
    .join(" ");

  const hasImagePart = contentArray.some((part) => part.type === "image_url");
  const hasVideoPart = contentArray.some((part) => part.type === "video_url");

  return { textContent, hasImagePart, hasVideoPart };
}

describe.skipIf(!shouldRunApiTests)("ChatPrompt Integration Tests", () => {
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

    // Cleanup all prompts after all tests are done
    await cleanupPrompts(client, createdPromptIds);
  });

  it("should create and retrieve a chat prompt with text content", async () => {
    const testPromptName = `test-chat-prompt-${Date.now()}`;

    // Create a chat prompt
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
      description: "Test chat prompt",
      tags: ["test", "integration"],
      type: PromptType.MUSTACHE,
    });

    createdPromptIds.push(chatPrompt.id);

    // Verify the created prompt
    expect(chatPrompt).toBeInstanceOf(ChatPrompt);
    expect(chatPrompt.name).toBe(testPromptName);
    expect(chatPrompt.messages).toHaveLength(2);
    expect(chatPrompt.description).toBe("Test chat prompt");
    expect(chatPrompt.tags).toHaveLength(2);
    expect(chatPrompt.tags).toEqual(expect.arrayContaining(["test", "integration"]));
    expect(chatPrompt.type).toBe(PromptType.MUSTACHE);

    // Retrieve the prompt
    const retrieved = await client.getChatPrompt({ name: testPromptName });
    expect(retrieved).not.toBeNull();
    expect(retrieved!.name).toBe(testPromptName);
    expect(retrieved!.messages).toHaveLength(chatPrompt.messages.length);
    expect(retrieved!.messages).toEqual(expect.arrayContaining(chatPrompt.messages));
  });

  it("should format a chat prompt with variables", async () => {
    const testPromptName = `test-chat-prompt-${Date.now()}`;

    // Create a chat prompt
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

    // Format the prompt with variables
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

  it("should create a chat prompt with multimodal content", async () => {
    const multimodalPromptName = `test-multimodal-${Date.now()}`;

    const chatPrompt = await client.createChatPrompt({
      name: multimodalPromptName,
      messages: [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Describe this image:",
            },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/image.jpg",
                detail: "high",
              },
            },
          ],
        },
      ],
      type: PromptType.MUSTACHE,
    });

    createdPromptIds.push(chatPrompt.id);

    expect(chatPrompt).toBeInstanceOf(ChatPrompt);
    expect(chatPrompt.messages).toHaveLength(1);
    expect(Array.isArray(chatPrompt.messages[0].content)).toBe(true);

    const content = chatPrompt.messages[0].content as ContentPart[];
    expect(content).toHaveLength(2);
    expect(content[0].type).toBe("text");
    expect(content[1].type).toBe("image_url");
  });

  it("should update chat prompt properties", async () => {
    const testPromptName = `test-chat-prompt-${Date.now()}`;

    // Create a chat prompt
    const chatPrompt = await client.createChatPrompt({
      name: testPromptName,
      messages: [
        {
          role: "user",
          content: "Hello {{name}}",
        },
      ],
      type: PromptType.MUSTACHE,
    });
    createdPromptIds.push(chatPrompt.id);

    // Update properties
    await chatPrompt.updateProperties({
      description: "Updated description",
      tags: ["updated", "test"],
    });

    // Verify updates
    expect(chatPrompt.description).toBe("Updated description");
    expect(chatPrompt.tags).toHaveLength(2);
    expect(chatPrompt.tags).toEqual(expect.arrayContaining(["updated", "test"]));

    // Retrieve again to verify persistence
    const reRetrieved = await client.getChatPrompt({ name: testPromptName });
    expect(reRetrieved!.description).toBe("Updated description");
    expect(reRetrieved!.tags).toHaveLength(2);
    expect(reRetrieved!.tags).toEqual(expect.arrayContaining(["updated", "test"]));
  });

  it("should handle modality filtering", async () => {
    const modalityPromptName = `test-modality-${Date.now()}`;

    const chatPrompt = await client.createChatPrompt({
      name: modalityPromptName,
      messages: [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Check this:",
            },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/image.jpg",
              },
            },
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/video.mp4",
              },
            },
          ],
        },
      ],
    });

    createdPromptIds.push(chatPrompt.id);

    // Format with vision disabled
    const formattedNoVision = chatPrompt.format(
      {},
      { vision: false, video: true }
    );
    const noVision = analyzeContentParts(formattedNoVision[0].content);
    // Should have text part with image placeholder, video url preserved (but flattened)
    expect(noVision.textContent).toContain("<<<image>>>");
    expect(noVision.textContent).not.toContain("<<<video>>>");
    expect(noVision.hasVideoPart).toBe(false); // Since vision is disabled, flattening should occur
    expect(noVision.hasImagePart).toBe(false);

    // Format with video disabled
    const formattedNoVideo = chatPrompt.format(
      {},
      { vision: true, video: false }
    );
    const noVideo = analyzeContentParts(formattedNoVideo[0].content);
    // Should have text part with video placeholder, image url preserved (but flattened)
    expect(noVideo.textContent).not.toContain("<<<image>>>");
    expect(noVideo.textContent).toContain("<<<video>>>");
    expect(noVideo.hasImagePart).toBe(false); // Since video is disabled, flattening should occur
    expect(noVideo.hasVideoPart).toBe(false);

    // Format with all modalities disabled
    const formattedNoModalities = chatPrompt.format(
      {},
      { vision: false, video: false }
    );
    const noModalities = analyzeContentParts(
      formattedNoModalities[0].content
    );
    // Should have text parts with both placeholders
    expect(noModalities.textContent).toContain("<<<image>>>");
    expect(noModalities.textContent).toContain("<<<video>>>");
    // No structured image or video parts should be present
    expect(noModalities.hasImagePart).toBe(false);
    expect(noModalities.hasVideoPart).toBe(false);
  });

  it("should search and find chat prompts", async () => {
    const testPromptName = `test-chat-prompt-${Date.now()}`;

    // Create a chat prompt
    const chatPrompt = await client.createChatPrompt({
      name: testPromptName,
      messages: [
        {
          role: "user",
          content: "Hello {{name}}",
        },
      ],
      type: PromptType.MUSTACHE,
    });
    createdPromptIds.push(chatPrompt.id);

    const results = await client.searchPrompts();
    const found = results.find((p) => p.name === testPromptName);

    expect(found).toBeDefined();
    expect(found).toBeInstanceOf(ChatPrompt);
    expect(found!.name).toBe(testPromptName);
  });

  it("should get versions of a chat prompt", async () => {
    const testPromptName = `test-chat-prompt-${Date.now()}`;

    // Create a chat prompt
    const chatPrompt = await client.createChatPrompt({
      name: testPromptName,
      messages: [
        {
          role: "user",
          content: "Hello {{name}}",
        },
      ],
      type: PromptType.MUSTACHE,
    });
    createdPromptIds.push(chatPrompt.id);

    const versions = await chatPrompt.getVersions();
    expect(versions.length).toBeGreaterThan(0);

    // Each version should have required properties
    versions.forEach((version) => {
      expect(version.id).toBeDefined();
      expect(version.commit).toBeDefined();
      expect(version.createdAt).toBeDefined();
    });
  });

  it("should delete a chat prompt", async () => {
    const tempPromptName = `test-delete-${Date.now()}`;

    // Create a temporary prompt
    const chatPrompt = await client.createChatPrompt({
      name: tempPromptName,
      messages: [
        {
          role: "user",
          content: "Temporary prompt",
        },
      ],
    });

    // Verify it exists
    let retrieved = await client.getChatPrompt({ name: tempPromptName });
    expect(retrieved).not.toBeNull();

    // Delete it
    await chatPrompt.delete();

    // Verify it's gone
    retrieved = await client.getChatPrompt({ name: tempPromptName });
    expect(retrieved).toBeNull();
  });

  it("should handle Jinja2 template type", async () => {
    const jinja2PromptName = `test-jinja2-${Date.now()}`;

    const chatPrompt = await client.createChatPrompt({
      name: jinja2PromptName,
      messages: [
        {
          role: "user",
          content: "Hello {{ name }}, you have {{ count }} messages.",
        },
      ],
      type: PromptType.JINJA2,
    });

    createdPromptIds.push(chatPrompt.id);

    expect(chatPrompt.type).toBe(PromptType.JINJA2);

    // Format with Jinja2
    const formatted = chatPrompt.format({
      name: "Bob",
      count: 5,
    });

    expect(formatted[0].content).toBe("Hello Bob, you have 5 messages.");
  });

  it("should compare chat prompt versions with multimodal content including unrecognized properties", async () => {
    const comparePromptName = `test-compare-multimodal-${Date.now()}`;

    // Create initial version with multimodal content
    const chatPrompt = await client.createChatPrompt({
      name: comparePromptName,
      messages: [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Analyze this image carefully.",
            },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/image1.jpg",
                detail: "high",
                custom_metadata: "original_image",
              },
            },
          ],
        },
      ],
      type: PromptType.MUSTACHE,
    });

    createdPromptIds.push(chatPrompt.id);

    // Create a new version with modified content
    const updatedPrompt = await client.createChatPrompt({
      name: comparePromptName,
      messages: [
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "Describe this image in detail.",
            },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/image2.jpg",
                detail: "low",
                custom_metadata: "updated_image",
              },
            },
          ],
        },
      ],
      type: PromptType.MUSTACHE,
    });

    // Get versions for comparison
    const versions = await updatedPrompt.getVersions();
    expect(versions.length).toBeGreaterThanOrEqual(2);

    // Compare the two versions
    const latestVersion = versions[0];
    const previousVersion = versions[1];
    const diff = latestVersion.compareTo(previousVersion);

    // Verify diff contains expected structure
    expect(diff).toContain("Message 1 [user]:");
    expect(diff).toContain("Type: text");
    expect(diff).toContain("Type: image_url");

    // Verify text content changes with diff markers (+ for new, - for old)
    expect(diff).toContain("+     Describe this image in detail.");
    expect(diff).toContain("-     Analyze this image carefully.");

    // Verify image URL changes with diff markers
    expect(diff).toContain("+     URL: https://example.com/image2.jpg");
    expect(diff).toContain("-     URL: https://example.com/image1.jpg");

    // Verify standard property changes with diff markers
    expect(diff).toContain("+     detail: low");
    expect(diff).toContain("-     detail: high");

    // Verify unrecognized/custom property changes with diff markers
    expect(diff).toContain("+     custom_metadata: updated_image");
    expect(diff).toContain("-     custom_metadata: original_image");

    // Verify commit hashes are present in headers
    expect(diff).toContain(latestVersion.commit);
    expect(diff).toContain(previousVersion.commit);
  });
});
