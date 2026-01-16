import { Opik } from "opik";
import { MockInstance } from "vitest";
import { Prompt, PromptType } from "@/prompt";
import { OpikApiError } from "@/rest_api";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";
import * as OpikApi from "@/rest_api/api";
import { PromptTemplateStructure } from "@/prompt/types";

describe("Opik prompt operations", () => {
  let client: Opik;
  let retrievePromptVersionSpy: MockInstance<
    typeof client.api.prompts.retrievePromptVersion
  >;
  let getPromptsSpy: MockInstance<typeof client.api.prompts.getPrompts>;
  let getPromptByIdSpy: MockInstance<typeof client.api.prompts.getPromptById>;
  let createPromptVersionSpy: MockInstance<
    typeof client.api.prompts.createPromptVersion
  >;
  let deletePromptsBatchSpy: MockInstance<
    typeof client.api.prompts.deletePromptsBatch
  >;
  let loggerErrorSpy: MockInstance<typeof logger.error>;
  let loggerInfoSpy: MockInstance<typeof logger.info>;
  let loggerDebugSpy: MockInstance<typeof logger.debug>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    // Mock API methods
    retrievePromptVersionSpy = vi
      .spyOn(client.api.prompts, "retrievePromptVersion")
      .mockImplementation(mockAPIFunction);

    getPromptsSpy = vi
      .spyOn(client.api.prompts, "getPrompts")
      .mockImplementation(mockAPIFunction);

    getPromptByIdSpy = vi
      .spyOn(client.api.prompts, "getPromptById")
      .mockImplementation(mockAPIFunction);

    createPromptVersionSpy = vi
      .spyOn(client.api.prompts, "createPromptVersion")
      .mockImplementation(mockAPIFunction);

    deletePromptsBatchSpy = vi
      .spyOn(client.api.prompts, "deletePromptsBatch")
      .mockImplementation(mockAPIFunction);

    // Mock logger methods
    loggerErrorSpy = vi.spyOn(logger, "error");
    loggerInfoSpy = vi.spyOn(logger, "info");
    loggerDebugSpy = vi.spyOn(logger, "debug");
  });

  afterEach(() => {
    retrievePromptVersionSpy.mockRestore();
    getPromptsSpy.mockRestore();
    getPromptByIdSpy.mockRestore();
    createPromptVersionSpy.mockRestore();
    deletePromptsBatchSpy.mockRestore();
    loggerErrorSpy.mockRestore();
    loggerInfoSpy.mockRestore();
    loggerDebugSpy.mockRestore();
  });

  describe("createPrompt", () => {
    it("should create a new prompt successfully", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{name}}!",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "test-prompt",
        description: "Test description",
      };

      // First call: check if prompt exists (returns null for new prompt)
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      // Second call: create new version
      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      // Third call: get prompt data
      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      const result = await client.createPrompt({
        name: "test-prompt",
        prompt: "Hello {{name}}!",
      });

      expect(result).toBeInstanceOf(Prompt);
      expect(result.name).toBe("test-prompt");
      expect(result.prompt).toBe("Hello {{name}}!");
      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        {
          name: "test-prompt",
          version: {
            template: "Hello {{name}}!",
            metadata: undefined,
            type: PromptType.MUSTACHE,
          },
          templateStructure: PromptTemplateStructure.Text,
        },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Creating prompt", {
        name: "test-prompt",
      });
    });

    it("should return existing version if identical (idempotent)", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test {{variable}}",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "existing-prompt",
      };

      // Latest version exists and matches
      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "existing-prompt",
        prompt: "Test {{variable}}",
      });

      expect(createPromptVersionSpy).not.toHaveBeenCalled();
      expect(loggerDebugSpy).toHaveBeenCalledWith(
        "Returning existing prompt version",
        { name: "existing-prompt" }
      );
    });

    it("should create new version if template differs", async () => {
      const mockExistingVersion: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Old template",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockNewVersion: OpikApi.PromptVersionDetail = {
        id: "version-id-2",
        promptId: "prompt-id",
        commit: "def456gh",
        template: "New template",
        type: "mustache",
        createdAt: new Date("2024-01-02"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "test-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockExistingVersion)
      );

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockNewVersion)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "test-prompt",
        prompt: "New template",
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        {
          name: "test-prompt",
          version: {
            template: "New template",
            metadata: undefined,
            type: PromptType.MUSTACHE,
          },
          templateStructure: PromptTemplateStructure.Text,
        },
        client.api.requestOptions
      );
    });

    it("should use default type MUSTACHE when not specified", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "default-type-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "default-type-prompt",
        prompt: "Test",
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            type: PromptType.MUSTACHE,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should create prompt with specified type", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{ name }}!",
        type: "jinja2",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "jinja2-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "jinja2-prompt",
        prompt: "Hello {{ name }}!",
        type: PromptType.JINJA2,
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            type: PromptType.JINJA2,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should create prompt with metadata", async () => {
      const metadata = {
        version: "1.0",
        author: "test-user",
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "prompt-with-metadata",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "prompt-with-metadata",
        prompt: "Test",
        metadata,
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            metadata,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should handle errors gracefully", async () => {
      const apiError = new OpikApiError({
        message: "Server error",
        statusCode: 500,
      });

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(
        client.createPrompt({
          name: "error-prompt",
          prompt: "Test",
        })
      ).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to create prompt",
        expect.objectContaining({
          name: "error-prompt",
          error: apiError,
        })
      );
    });
  });

  describe("getPrompt", () => {
    it("should retrieve prompt by name only (latest version)", async () => {
      const mockSearchResponse = {
        content: [
          {
            id: "prompt-id",
            name: "test-prompt",
            description: "Test description",
            tags: ["test"],
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{name}}!",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
        createdBy: "test-user",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({ name: "test-prompt" });

      expect(getPromptsSpy).toHaveBeenCalledWith(
        {
          filters: JSON.stringify([
            { field: "name", operator: "=", value: "test-prompt" },
          ]),
          size: 1,
        },
        client.api.requestOptions
      );
      expect(retrievePromptVersionSpy).toHaveBeenCalledWith(
        { name: "test-prompt" },
        client.api.requestOptions
      );
      expect(result).toBeInstanceOf(Prompt);
      expect(result?.name).toBe("test-prompt");
      expect(result?.prompt).toBe("Hello {{name}}!");
      expect(loggerDebugSpy).toHaveBeenCalledWith("Getting prompt", {
        name: "test-prompt",
      });
    });

    it("should retrieve prompt by name and commit", async () => {
      const mockSearchResponse = {
        content: [
          {
            id: "prompt-id",
            name: "test-prompt",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "specific-commit",
        template: "Specific version",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({
        name: "test-prompt",
        commit: "specific-commit",
      });

      expect(retrievePromptVersionSpy).toHaveBeenCalledWith(
        { name: "test-prompt", commit: "specific-commit" },
        client.api.requestOptions
      );
      expect(result).toBeInstanceOf(Prompt);
      expect(result?.prompt).toBe("Specific version");
    });

    it("should return null when prompt doesn't exist", async () => {
      const mockSearchResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      const result = await client.getPrompt({ name: "non-existent-prompt" });

      expect(result).toBeNull();
      expect(loggerDebugSpy).toHaveBeenCalledWith("Prompt not found", {
        name: "non-existent-prompt",
      });
    });

    it("should propagate API errors", async () => {
      const apiError = new OpikApiError({
        message: "Server error",
        statusCode: 500,
      });

      getPromptsSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.getPrompt({ name: "test-prompt" })).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to get prompt",
        expect.objectContaining({
          error: apiError,
        })
      );
    });

    it("should handle network errors", async () => {
      const networkError = new Error("Network timeout");

      getPromptsSpy.mockImplementationOnce(() => {
        throw networkError;
      });

      await expect(client.getPrompt({ name: "test-prompt" })).rejects.toThrow(
        "Network timeout"
      );

      expect(loggerErrorSpy).toHaveBeenCalled();
    });
  });

  describe("searchPrompts", () => {
    it("should search all prompts without filters", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "prompt-one",
            description: "First prompt",
          },
          {
            id: "prompt-2",
            name: "prompt-two",
            description: "Second prompt",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      const mockVersionDetail2: OpikApi.PromptVersionDetail = {
        id: "version-2",
        promptId: "prompt-2",
        commit: "commit2",
        template: "Template 2",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail2)
        );

      const results = await client.searchPrompts();

      expect(getPromptsSpy).toHaveBeenCalledWith(
        {
          filters: undefined,
          size: 1000,
        },
        client.api.requestOptions
      );
      expect(results).toHaveLength(2);
      expect(results[0]).toBeInstanceOf(Prompt);
      expect(results[0].name).toBe("prompt-one");
      expect(results[1].name).toBe("prompt-two");
    });

    it("should search prompts with simple OQL filter", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "filtered-prompt",
            description: "Filtered",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Filtered template",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const results = await client.searchPrompts('name = "filtered-prompt"');

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: expect.any(String),
          size: 1000,
        }),
        client.api.requestOptions
      );
      expect(results).toHaveLength(1);
      expect(results[0].name).toBe("filtered-prompt");
    });

    it("should search prompts with complex multi-condition filters", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "complex-prompt",
            description: "Complex filter",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Complex template",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      await client.searchPrompts(
        'tags contains "alpha" AND name contains "summary"'
      );

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: expect.any(String),
          size: 1000,
        }),
        client.api.requestOptions
      );

      // Verify OQL parsing integration
      const callArgs = getPromptsSpy.mock.calls[0]?.[0];
      expect(callArgs).toBeDefined();
      expect(callArgs?.filters).toBeDefined();
      const filters = JSON.parse(callArgs!.filters!);
      expect(filters).toBeInstanceOf(Array);
      expect(filters.length).toBeGreaterThan(0);
    });

    it("should integrate with OQL parsing", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      await client.searchPrompts('created_by = "user@example.com"');

      const callArgs = getPromptsSpy.mock.calls[0]?.[0];
      expect(callArgs).toBeDefined();
      expect(callArgs?.filters).toBeDefined();

      // Verify OQL was parsed and converted to JSON
      const filters = JSON.parse(callArgs!.filters!);
      expect(filters).toBeInstanceOf(Array);
    });

    it("should handle pagination with size 1000", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      await client.searchPrompts();

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          size: 1000,
        }),
        client.api.requestOptions
      );
    });

    it("should return empty array when no results", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      const results = await client.searchPrompts();

      expect(results).toEqual([]);
      expect(results).toHaveLength(0);
    });

    it("should propagate invalid OQL syntax errors", async () => {
      // Mock OQL to throw an error for invalid syntax
      const invalidFilter = "invalid OQL syntax ===";

      await expect(client.searchPrompts(invalidFilter)).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to search prompts",
        expect.objectContaining({
          error: expect.any(Error),
        })
      );
    });

    it("should create prompt with tags and description in options", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test {{var}}",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "prompt-with-tags",
        description: "Test description",
        tags: ["tag1", "tag2"],
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      const updatePropertiesSpy = vi.fn();
      vi.spyOn(Prompt.prototype, "updateProperties").mockImplementation(
        updatePropertiesSpy
      );

      await client.createPrompt({
        name: "prompt-with-tags",
        prompt: "Test {{var}}",
        description: "Test description",
        tags: ["tag1", "tag2"],
      });

      expect(updatePropertiesSpy).toHaveBeenCalledWith({
        description: "Test description",
        tags: ["tag1", "tag2"],
      });
    });

    it("should handle API errors during search", async () => {
      const apiError = new OpikApiError({
        message: "Search failed",
        statusCode: 500,
      });

      getPromptsSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.searchPrompts()).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to search prompts",
        expect.objectContaining({
          error: apiError,
        })
      );
    });

    it("should handle prompts without names gracefully", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "valid-prompt",
          },
          {
            id: "prompt-2",
            name: undefined as unknown as string, // Invalid prompt without name
          },
          {
            id: "prompt-3",
            name: "another-valid",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      const mockVersionDetail3: OpikApi.PromptVersionDetail = {
        id: "version-3",
        promptId: "prompt-3",
        commit: "commit3",
        template: "Template 3",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail3)
        );

      const results = await client.searchPrompts();

      // Should filter out prompts without names
      expect(results).toHaveLength(2);
      expect(results[0].name).toBe("valid-prompt");
      expect(results[1].name).toBe("another-valid");
    });

    it("should handle version retrieval failures gracefully", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "prompt-one",
          },
          {
            id: "prompt-2",
            name: "prompt-two",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      // First prompt succeeds, second fails
      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() => {
          throw new Error("Failed to retrieve version");
        });

      const results = await client.searchPrompts();

      // Should only include successfully retrieved prompts
      expect(results).toHaveLength(1);
      expect(results[0].name).toBe("prompt-one");
      expect(loggerDebugSpy).toHaveBeenCalledWith(
        "Failed to get version for prompt",
        expect.objectContaining({
          name: "prompt-two",
        })
      );
    });
  });

  describe("deletePrompts", () => {
    it("should delete single prompt", async () => {
      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(["prompt-id-1"]);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids: ["prompt-id-1"] },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Deleting prompts in batch", {
        count: 1,
      });
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 1 }
      );
    });

    it("should delete multiple prompts", async () => {
      const ids = ["prompt-id-1", "prompt-id-2", "prompt-id-3"];

      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(ids);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids },
        client.api.requestOptions
      );
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 3 }
      );
    });

    it("should handle empty array", async () => {
      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts([]);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids: [] },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Deleting prompts in batch", {
        count: 0,
      });
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 0 }
      );
    });

    it("should call API with all IDs", async () => {
      const ids = ["id-1", "id-2"];

      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(ids);

      expect(deletePromptsBatchSpy).toHaveBeenCalledTimes(1);
      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids },
        client.api.requestOptions
      );
    });

    it("should handle errors during deletion", async () => {
      const errorMessage = "Failed to delete";
      const apiError = new OpikApiError({
        message: errorMessage,
        statusCode: 500,
      });

      deletePromptsBatchSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.deletePrompts(["error-id"])).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to delete prompts",
        expect.objectContaining({
          count: 1,
          error: apiError,
        })
      );
    });
  });

  describe("flush integration", () => {
    it("should flush all batch queues (prompts are synchronous)", async () => {
      const traceBatchQueueFlushSpy = vi.spyOn(client.traceBatchQueue, "flush");
      const spanBatchQueueFlushSpy = vi.spyOn(client.spanBatchQueue, "flush");

      await client.flush();

      expect(traceBatchQueueFlushSpy).toHaveBeenCalled();
      expect(spanBatchQueueFlushSpy).toHaveBeenCalled();
      // Note: Prompt operations are synchronous and don't use batching
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully flushed all data to Opik"
      );
    });

    it("should handle errors during flush", async () => {
      const traceBatchQueueFlushSpy = vi.spyOn(client.traceBatchQueue, "flush");
      traceBatchQueueFlushSpy.mockImplementationOnce(() => {
        throw new Error("Flush failed");
      });

      await client.flush();

      expect(loggerErrorSpy).toHaveBeenCalled();
    });
  });
});
