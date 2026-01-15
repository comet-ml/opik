import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { OpikClient } from "@/client/Client";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { PromptTemplateStructureMismatch } from "@/prompt/errors";
import { PromptTemplateStructure, type ChatMessage } from "@/prompt/types";
import type * as OpikApi from "@/rest_api/api";

describe("OpikClient - Chat Prompts", () => {
  let client: OpikClient;

  beforeEach(() => {
    client = new OpikClient({
      apiKey: "test-key",
      holdUntilFlush: true,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("createChatPrompt", () => {
    it("should create a new chat prompt", async () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a helpful assistant" },
        { role: "user", content: "Hello {{name}}" },
      ];

      // Mock API responses
      const mockRetrievePromptVersion = vi
        .spyOn(client.api.prompts, "retrievePromptVersion")
        .mockRejectedValue({ statusCode: 404 });

      const mockCreatePromptVersion = vi
        .spyOn(client.api.prompts, "createPromptVersion")
        .mockResolvedValue({
          id: "version-123",
          promptId: "prompt-456",
          template: JSON.stringify(messages),
          commit: "abc123",
          type: "mustache",
          templateStructure: "chat",
        } as OpikApi.PromptVersionDetail);

      vi.spyOn(client.api.prompts, "getPromptById").mockResolvedValue({
        name: "test-chat-prompt",
        description: "Test description",
        tags: ["test"],
      } as OpikApi.PromptPublic);

      const chatPrompt = await client.createChatPrompt({
        name: "test-chat-prompt",
        messages,
        type: "mustache",
      });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(chatPrompt.name).toBe("test-chat-prompt");
      expect(chatPrompt.messages).toEqual(messages);
      expect(chatPrompt.type).toBe("mustache");

      expect(mockRetrievePromptVersion).toHaveBeenCalledWith(
        { name: "test-chat-prompt" },
        {}
      );

      expect(mockCreatePromptVersion).toHaveBeenCalledWith(
        {
          name: "test-chat-prompt",
          version: {
            template: JSON.stringify(messages),
            metadata: undefined,
            type: "mustache",
          },
          templateStructure: PromptTemplateStructure.Chat,
        },
        {}
      );
    });

    it("should return existing chat prompt if identical", async () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a helpful assistant" },
      ];

      const existingVersion: OpikApi.PromptVersionDetail = {
        id: "version-123",
        promptId: "prompt-456",
        template: JSON.stringify(messages),
        commit: "abc123",
        type: "mustache",
        templateStructure: "chat",
      };

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue(
        existingVersion
      );

      const mockCreatePromptVersion = vi.spyOn(
        client.api.prompts,
        "createPromptVersion"
      );

      vi.spyOn(client.api.prompts, "getPromptById").mockResolvedValue({
        name: "test-chat-prompt",
      } as OpikApi.PromptPublic);

      const chatPrompt = await client.createChatPrompt({
        name: "test-chat-prompt",
        messages,
        type: "mustache",
      });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(mockCreatePromptVersion).not.toHaveBeenCalled();
    });

    it("should throw PromptTemplateStructureMismatch if text prompt exists with same name", async () => {
      const messages: ChatMessage[] = [
        { role: "user", content: "Hello" },
      ];

      const existingTextPrompt: OpikApi.PromptVersionDetail = {
        id: "version-123",
        promptId: "prompt-456",
        template: "Hello {{name}}",
        commit: "abc123",
        type: "mustache",
        templateStructure: "text",
      };

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue(
        existingTextPrompt
      );

      await expect(
        client.createChatPrompt({
          name: "test-prompt",
          messages,
        })
      ).rejects.toThrow(PromptTemplateStructureMismatch);
    });

    it("should update properties if description or tags provided", async () => {
      const messages: ChatMessage[] = [
        { role: "user", content: "Hello" },
      ];

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockRejectedValue({
        statusCode: 404,
      });

      vi.spyOn(client.api.prompts, "createPromptVersion").mockResolvedValue({
        id: "version-123",
        promptId: "prompt-456",
        template: JSON.stringify(messages),
        commit: "abc123",
        type: "mustache",
        templateStructure: "chat",
      } as OpikApi.PromptVersionDetail);

      vi.spyOn(client.api.prompts, "getPromptById").mockResolvedValue({
        name: "test-chat-prompt",
      } as OpikApi.PromptPublic);

      const mockUpdatePrompt = vi
        .spyOn(client.api.prompts, "updatePrompt")
        .mockResolvedValue(undefined);

      await client.createChatPrompt({
        name: "test-chat-prompt",
        messages,
        description: "Test description",
        tags: ["tag1", "tag2"],
      });

      expect(mockUpdatePrompt).toHaveBeenCalledWith(
        "prompt-456",
        {
          name: "test-chat-prompt",
          description: "Test description",
          tags: ["tag1", "tag2"],
        },
        {}
      );
    });
  });

  describe("getChatPrompt", () => {
    it("should retrieve an existing chat prompt", async () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a helpful assistant" },
      ];

      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "test-chat-prompt",
            description: "Test description",
            tags: ["test"],
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue({
        id: "version-123",
        promptId: "prompt-456",
        template: JSON.stringify(messages),
        commit: "abc123",
        type: "mustache",
        templateStructure: "chat",
      } as OpikApi.PromptVersionDetail);

      const chatPrompt = await client.getChatPrompt({
        name: "test-chat-prompt",
      });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(chatPrompt?.name).toBe("test-chat-prompt");
      expect(chatPrompt?.messages).toEqual(messages);
    });

    it("should return null if chat prompt not found", async () => {
      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [],
      } as OpikApi.PromptPagePublic);

      const chatPrompt = await client.getChatPrompt({
        name: "nonexistent-prompt",
      });

      expect(chatPrompt).toBeNull();
    });

    it("should throw PromptTemplateStructureMismatch if text prompt exists", async () => {
      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "test-prompt",
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue({
        id: "version-123",
        promptId: "prompt-456",
        template: "Hello {{name}}",
        commit: "abc123",
        type: "mustache",
        templateStructure: "text",
      } as OpikApi.PromptVersionDetail);

      await expect(
        client.getChatPrompt({ name: "test-prompt" })
      ).rejects.toThrow(PromptTemplateStructureMismatch);
    });

    it("should throw PromptTemplateStructureMismatch if templateStructure is undefined", async () => {
      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "test-prompt",
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue({
        id: "version-123",
        promptId: "prompt-456",
        template: "Hello {{name}}",
        commit: "abc123",
        type: "mustache",
        templateStructure: undefined,
      } as OpikApi.PromptVersionDetail);

      await expect(
        client.getChatPrompt({ name: "test-prompt" })
      ).rejects.toThrow(PromptTemplateStructureMismatch);
    });

    it("should retrieve specific version by commit", async () => {
      const messages: ChatMessage[] = [
        { role: "user", content: "Hello" },
      ];

      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "test-chat-prompt",
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      const mockRetrievePromptVersion = vi
        .spyOn(client.api.prompts, "retrievePromptVersion")
        .mockResolvedValue({
          id: "version-123",
          promptId: "prompt-456",
          template: JSON.stringify(messages),
          commit: "abc123",
          type: "mustache",
          templateStructure: "chat",
        } as OpikApi.PromptVersionDetail);

      const chatPrompt = await client.getChatPrompt({
        name: "test-chat-prompt",
        commit: "abc123",
      });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(mockRetrievePromptVersion).toHaveBeenCalledWith(
        { name: "test-chat-prompt", commit: "abc123" },
        {}
      );
    });
  });

  describe("searchPrompts with chat prompts", () => {
    it("should return both text and chat prompts", async () => {
      const textMessages = "Hello {{name}}";
      const chatMessages: ChatMessage[] = [
        { role: "user", content: "Hello {{name}}" },
      ];

      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "text-prompt",
          } as OpikApi.PromptPublic,
          {
            name: "chat-prompt",
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      vi
        .spyOn(client.api.prompts, "retrievePromptVersion")
        // @ts-expect-error - Mocking with simplified return type
        .mockImplementation((options) => {
          if (options.name === "text-prompt") {
            return Promise.resolve({
              id: "version-1",
              promptId: "prompt-1",
              template: textMessages,
              commit: "abc123",
              type: "mustache",
              templateStructure: "text",
            } as OpikApi.PromptVersionDetail);
          } else {
            return Promise.resolve({
              id: "version-2",
              promptId: "prompt-2",
              template: JSON.stringify(chatMessages),
              commit: "def456",
              type: "mustache",
              templateStructure: "chat",
            } as OpikApi.PromptVersionDetail);
          }
        });

      const prompts = await client.searchPrompts();

      expect(prompts).toHaveLength(2);
      expect(prompts[0].name).toBe("text-prompt");
      expect(prompts[1]).toBeInstanceOf(ChatPrompt);
      expect(prompts[1].name).toBe("chat-prompt");
    });

    it("should filter chat prompts by OQL", async () => {
      const chatMessages: ChatMessage[] = [
        { role: "user", content: "Hello" },
      ];

      vi.spyOn(client.api.prompts, "getPrompts").mockResolvedValue({
        content: [
          {
            name: "chat-prompt-alpha",
            tags: ["alpha"],
          } as OpikApi.PromptPublic,
        ],
      } as OpikApi.PromptPagePublic);

      vi.spyOn(client.api.prompts, "retrievePromptVersion").mockResolvedValue({
        id: "version-1",
        promptId: "prompt-1",
        template: JSON.stringify(chatMessages),
        commit: "abc123",
        type: "mustache",
        templateStructure: "chat",
      } as OpikApi.PromptVersionDetail);

      const prompts = await client.searchPrompts('tags contains "alpha"');

      expect(prompts).toHaveLength(1);
      expect(prompts[0]).toBeInstanceOf(ChatPrompt);
      expect(prompts[0].name).toBe("chat-prompt-alpha");
    });
  });
});
