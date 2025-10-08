import { describe, it, expect, beforeEach } from "vitest";
import { Prompt } from "../../../src/opik/prompt/Prompt";
import { PromptValidationError } from "../../../src/opik/prompt/errors";
import { PromptType } from "../../../src/opik/prompt/types";
import {
  createMockOpikClient,
  createMockPromptData,
  validApiResponse,
  invalidApiResponses,
} from "./fixtures";
import type { OpikClient } from "../../../src/opik/client/Client";
import type * as OpikApi from "../../../src/opik/rest_api/api";

describe("Prompt - fromApiResponse()", () => {
  let mockOpikClient: OpikClient;

  beforeEach(() => {
    mockOpikClient = createMockOpikClient();
  });

  describe("valid API responses", () => {
    it("should create prompt from complete API response", () => {
      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        validApiResponse,
        mockOpikClient
      );

      expect(prompt.name).toBe("test-prompt");
      expect(prompt.id).toBe("prompt-id");
      expect(prompt.prompt).toBe("Hello {{name}}!");
      expect(prompt.commit).toBe("abc123de");
      expect(prompt.type).toBe(PromptType.MUSTACHE);
      expect(prompt.metadata).toEqual({ version: "1.0" });
      expect(prompt.changeDescription).toBe("Initial version");
    });

    it("should handle minimal valid response", () => {
      const minimalResponse: OpikApi.PromptVersionDetail = {
        promptId: "prompt-id",
        id: "version-id",
        commit: "abc123de",
        template: "Hello",
        createdAt: new Date(),
        type: "mustache",
      };

      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        minimalResponse,
        mockOpikClient
      );

      expect(prompt.name).toBe("test-prompt");
      expect(prompt.prompt).toBe("Hello");
      expect(prompt.type).toBe(PromptType.MUSTACHE); // default
      expect(prompt.metadata).toBeUndefined();
      expect(prompt.changeDescription).toBeUndefined();
    });

    it("should handle JINJA2 type from API", () => {
      const jinja2Response = {
        ...validApiResponse,
        type: "jinja2" as const,
        template: "Hello {{ name }}!",
      };

      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        jinja2Response,
        mockOpikClient
      );

      expect(prompt.type).toBe(PromptType.JINJA2);
    });

    it("should handle response with complex metadata", () => {
      const complexMetadata = {
        config: {
          model: "gpt-4",
          settings: { temperature: 0.7 },
        },
        tags: ["production"],
      };

      const response = {
        ...validApiResponse,
        metadata: complexMetadata,
      };

      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        response,
        mockOpikClient
      );

      expect(prompt.metadata).toEqual(complexMetadata);
    });

    it("should preserve name from promptData", () => {
      const promptData = createMockPromptData({ name: "custom-name" });
      const prompt = Prompt.fromApiResponse(
        promptData,
        validApiResponse,
        mockOpikClient
      );

      expect(prompt.name).toBe("custom-name");
    });

    it("should preserve description and tags from promptData", () => {
      const promptData = createMockPromptData({
        description: "Test description",
        tags: ["tag1", "tag2"],
      });
      const prompt = Prompt.fromApiResponse(
        promptData,
        validApiResponse,
        mockOpikClient
      );

      expect(prompt.name).toBe("test-prompt");
      expect(prompt.description).toBe("Test description");
      expect(prompt.tags).toEqual(["tag1", "tag2"]);
    });
  });

  describe("validation errors", () => {
    it("should throw error when template is missing", () => {
      const promptData = createMockPromptData();
      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingTemplate,
          mockOpikClient
        )
      ).toThrow(PromptValidationError);

      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingTemplate,
          mockOpikClient
        )
      ).toThrow(/missing required field 'template'/i);
    });

    it("should throw error when commit is missing", () => {
      const promptData = createMockPromptData();
      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingCommit,
          mockOpikClient
        )
      ).toThrow(PromptValidationError);

      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingCommit,
          mockOpikClient
        )
      ).toThrow(/missing required field 'commit'/i);
    });

    it("should throw error when promptId is missing", () => {
      const promptData = createMockPromptData();
      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingPromptId,
          mockOpikClient
        )
      ).toThrow(PromptValidationError);

      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.missingPromptId,
          mockOpikClient
        )
      ).toThrow(/missing required field 'promptId'/i);
    });

    it("should throw error for invalid prompt type", () => {
      const promptData = createMockPromptData();
      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.invalidType,
          mockOpikClient
        )
      ).toThrow(PromptValidationError);

      expect(() =>
        Prompt.fromApiResponse(
          promptData,
          invalidApiResponses.invalidType,
          mockOpikClient
        )
      ).toThrow(/unknown prompt type/i);
    });
  });

  describe("data integrity", () => {
    it("should create functional prompt that can be formatted", () => {
      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        validApiResponse,
        mockOpikClient
      );

      expect(prompt.format({ name: "World" })).toBe("Hello World!");
    });

    it("should preserve all API response fields", () => {
      const detailedResponse: OpikApi.PromptVersionDetail = {
        promptId: "prompt-123",
        id: "version-456",
        commit: "full-commit-hash",
        template: "Template {{var}}",
        type: "mustache" as const,
        metadata: { key: "value" },
        changeDescription: "Test change",
        createdAt: new Date(),
      };

      const promptData = createMockPromptData();
      const prompt = Prompt.fromApiResponse(
        promptData,
        detailedResponse,
        mockOpikClient
      );

      expect(prompt.id).toBe("prompt-123");
      expect(prompt.commit).toBe("full-commit-hash");
      expect(prompt.prompt).toBe("Template {{var}}");
      expect(prompt.metadata).toEqual({ key: "value" });
      expect(prompt.changeDescription).toBe("Test change");
    });
  });
});
