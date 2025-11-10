import { describe, it, expect, beforeEach } from "vitest";
import { Prompt } from "../../../src/opik/prompt/Prompt";
import { PromptType } from "../../../src/opik/prompt/types";
import {
  createMockOpikClient,
  basicPromptData,
  promptWithMetadata,
  complexMetadata,
} from "./fixtures";
import type { OpikClient } from "../../../src/opik/client/Client";

describe("Prompt - Constructor & Properties", () => {
  let mockOpikClient: OpikClient;

  beforeEach(() => {
    mockOpikClient = createMockOpikClient();
  });

  describe("constructor", () => {
    it("should create prompt with all required fields", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.name).toBe("test-prompt");
      expect(prompt.prompt).toBe("Hello {{name}}!");
      expect(prompt.type).toBe(PromptType.MUSTACHE);
      expect(prompt.commit).toBeUndefined();
      expect(prompt.metadata).toBeUndefined();
    });

    it("should create prompt with optional commit hash", () => {
      const prompt = new Prompt(
        { ...basicPromptData, commit: "abc123de" },
        mockOpikClient
      );

      expect(prompt.commit).toBe("abc123de");
    });

    it("should create prompt with optional metadata", () => {
      const prompt = new Prompt(promptWithMetadata, mockOpikClient);

      expect(prompt.metadata).toEqual(promptWithMetadata.metadata);
      // Verify it's a deep copy (different reference)
      expect(prompt.metadata).not.toBe(promptWithMetadata.metadata);
    });

    it("should default type to MUSTACHE when not provided", () => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { type, ...dataWithoutType } = basicPromptData;
      const prompt = new Prompt(dataWithoutType, mockOpikClient);

      expect(prompt.type).toBe(PromptType.MUSTACHE);
    });

    it("should support JINJA2 type", () => {
      const prompt = new Prompt(
        { ...basicPromptData, type: PromptType.JINJA2 },
        mockOpikClient
      );

      expect(prompt.type).toBe(PromptType.JINJA2);
    });

    it("should handle complex nested metadata structures", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          metadata: complexMetadata,
        },
        mockOpikClient
      );

      expect(prompt.metadata).toEqual(complexMetadata);
    });

    it("should handle description and tags", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          description: "Test description",
          tags: ["tag1", "tag2"],
        },
        mockOpikClient
      );

      expect(prompt.description).toBe("Test description");
      expect(prompt.tags).toEqual(["tag1", "tag2"]);
    });

    it("should handle changeDescription", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          changeDescription: "Initial version",
        },
        mockOpikClient
      );

      expect(prompt.changeDescription).toBe("Initial version");
    });
  });

  describe("edge cases", () => {
    it("should handle unicode and special characters", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          name: "unicode-prompt-日本語",
          prompt: 'Hello "{{name}}"! 你好\n{{greeting}}\t🎉',
          commit: "abc123",
          metadata: {
            description: "Тест с юникодом",
            emoji: "🚀",
          },
        },
        mockOpikClient
      );

      expect(prompt.name).toBe("unicode-prompt-日本語");
      expect(prompt.prompt).toContain("你好");
      expect(prompt.prompt).toContain("\n");
      expect(prompt.prompt).toContain("\t");
      expect(prompt.metadata?.description).toBe("Тест с юникодом");
    });

    it("should handle empty prompt string", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          prompt: "",
        },
        mockOpikClient
      );

      expect(prompt.prompt).toBe("");
    });

    it("should freeze tags array to prevent mutation", () => {
      const tags = ["tag1", "tag2"];
      const prompt = new Prompt(
        {
          ...basicPromptData,
          tags,
        },
        mockOpikClient
      );

      expect(Object.isFrozen(prompt.tags)).toBe(true);
      // Original array should not be frozen
      expect(Object.isFrozen(tags)).toBe(false);
    });
  });

  describe("metadata property", () => {
    it("should return undefined when metadata not set", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.metadata).toBeUndefined();
    });

    it("should return new deep copy on each access preventing mutation", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          metadata: {
            version: "1.0",
            config: { enabled: true },
            array: [1, 2, { nested: "value" }],
          },
        },
        mockOpikClient
      );

      const metadata1 = prompt.metadata;
      const metadata2 = prompt.metadata;

      // Should be deeply equal but different references
      expect(metadata1).toEqual(metadata2);
      expect(metadata1).not.toBe(metadata2);

      // Nested objects should also be different references
      expect(metadata1?.config).not.toBe(metadata2?.config);
      expect(metadata1?.array).not.toBe(metadata2?.array);
    });

    it("should prevent external mutation via returned metadata", () => {
      const originalMetadata = {
        version: "1.0",
        config: { enabled: true },
      };

      const prompt = new Prompt(
        {
          ...basicPromptData,
          metadata: originalMetadata,
        },
        mockOpikClient
      );

      // Get metadata and try to mutate it
      const retrievedMetadata = prompt.metadata as Record<string, unknown>;
      retrievedMetadata.version = "2.0";
      (retrievedMetadata.config as Record<string, unknown>).enabled = false;

      // Original prompt metadata should be unchanged
      const freshMetadata = prompt.metadata as Record<string, unknown>;
      expect(freshMetadata?.version).toBe("1.0");
      expect((freshMetadata?.config as Record<string, unknown>).enabled).toBe(
        true
      );
    });
  });

  describe("public getters", () => {
    it("should expose immutable id property", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.id).toBe("test-prompt-id");
    });

    it("should expose immutable prompt property", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.prompt).toBe("Hello {{name}}!");
    });

    it("should expose immutable type property", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.type).toBe(PromptType.MUSTACHE);
    });

    it("should expose mutable name property", () => {
      const prompt = new Prompt(basicPromptData, mockOpikClient);

      expect(prompt.name).toBe("test-prompt");
    });

    it("should expose optional description property", () => {
      const promptWithDesc = new Prompt(
        {
          ...basicPromptData,
          description: "Test description",
        },
        mockOpikClient
      );

      expect(promptWithDesc.description).toBe("Test description");

      const promptWithoutDesc = new Prompt(basicPromptData, mockOpikClient);
      expect(promptWithoutDesc.description).toBeUndefined();
    });

    it("should expose frozen tags array", () => {
      const prompt = new Prompt(
        {
          ...basicPromptData,
          tags: ["tag1", "tag2"],
        },
        mockOpikClient
      );

      expect(prompt.tags).toEqual(["tag1", "tag2"]);
      expect(Object.isFrozen(prompt.tags)).toBe(true);
    });
  });
});
