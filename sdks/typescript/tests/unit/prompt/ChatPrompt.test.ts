import { describe, it, expect, beforeEach, vi } from "vitest";
import { ChatPrompt, type ChatPromptData } from "@/prompt/ChatPrompt";
import type { ChatMessage } from "@/prompt/types";
import type { OpikClient } from "@/client/Client";
import type * as OpikApi from "@/rest_api/api";
import { PromptValidationError } from "@/prompt/errors";

// Mock OpikClient
const createMockOpikClient = (): OpikClient => {
  return {
    api: {
      prompts: {
        updatePrompt: vi.fn(),
      },
      requestOptions: {},
    },
    deletePrompts: vi.fn(),
  } as unknown as OpikClient;
};

describe("ChatPrompt", () => {
  let mockClient: OpikClient;

  beforeEach(() => {
    mockClient = createMockOpikClient();
  });

  describe("constructor", () => {
    it("should create a ChatPrompt instance with required fields", () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a helpful assistant" },
        { role: "user", content: "Hello {{name}}" },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        commit: "abc123",
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(chatPrompt.id).toBe("prompt-123");
      expect(chatPrompt.versionId).toBe("version-456");
      expect(chatPrompt.name).toBe("test-prompt");
      expect(chatPrompt.messages).toEqual(messages);
      expect(chatPrompt.commit).toBe("abc123");
      expect(chatPrompt.type).toBe("mustache");
    });

    it("should default type to mustache if not provided", () => {
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(chatPrompt.type).toBe("mustache");
    });

    it("should handle optional fields", () => {
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
        description: "Test description",
        tags: ["tag1", "tag2"],
        metadata: { key: "value" },
        changeDescription: "Initial version",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(chatPrompt.description).toBe("Test description");
      expect(chatPrompt.tags).toEqual(["tag1", "tag2"]);
      expect(chatPrompt.metadata).toEqual({ key: "value" });
      expect(chatPrompt.changeDescription).toBe("Initial version");
    });
  });

  describe("format", () => {
    it("should format simple string content with mustache variables", () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a {{role}}" },
        { role: "user", content: "Help me with {{task}}" },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        role: "helpful assistant",
        task: "coding",
      });

      expect(formatted).toEqual([
        { role: "system", content: "You are a helpful assistant" },
        { role: "user", content: "Help me with coding" },
      ]);
    });

    it("should format multimodal content with text and image", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "What is in this image from {{location}}?" },
            {
              type: "image_url",
              image_url: {
                url: "{{image_url}}",
                detail: "high",
              },
            },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        location: "Paris",
        image_url: "https://example.com/photo.jpg",
      });

      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "What is in this image from Paris?" },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/photo.jpg",
                detail: "high",
              },
            },
          ],
        },
      ]);
    });

    it("should replace unsupported modalities with placeholders and collapse to string", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze this image" },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/photo.jpg",
              },
            },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({}, { vision: false });

      expect(formatted).toEqual([
        {
          role: "user",
          content: "Analyze this image\n\n<<<image>>><<</image>>>",
        },
      ]);
      expect(typeof formatted[0].content).toBe("string");
      expect(formatted[0].content).toContain("Analyze this image");
      expect(formatted[0].content).toContain("<<<image>>>");
      expect(formatted[0].content).toContain("<<</image>>>");
    });

    it("should preserve order when replacing unsupported modalities with placeholders and collapse to string", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "First text" },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/photo.jpg",
              },
            },
            { type: "text", text: "Second text" },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({}, { vision: false });

      expect(formatted).toEqual([
        {
          role: "user",
          content: "First text\n\n<<<image>>><<</image>>>\n\nSecond text",
        },
      ]);
      expect(typeof formatted[0].content).toBe("string");
      expect(formatted[0].content).toContain("First text");
      expect(formatted[0].content).toContain("<<<image>>>");
      expect(formatted[0].content).toContain("Second text");
      // Verify order: First text should come before placeholder, placeholder before Second text
      const content = formatted[0].content as string;
      const firstTextIndex = content.indexOf("First text");
      const placeholderIndex = content.indexOf("<<<image>>>");
      const secondTextIndex = content.indexOf("Second text");
      expect(firstTextIndex).toBeLessThan(placeholderIndex);
      expect(placeholderIndex).toBeLessThan(secondTextIndex);
    });

    it("should collapse to string when multimodal content has unsupported image", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze this content" },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/photo.jpg",
              },
            },
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/video.mp4",
                mime_type: "video/mp4",
              },
            },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      // Image not supported, video supported
      const formatted = chatPrompt.format({}, { vision: false, video: true });

      expect(formatted).toEqual([
        {
          role: "user",
          content: expect.any(String),
        },
      ]);
      expect(typeof formatted[0].content).toBe("string");
      const content = formatted[0].content as string;
      
      // Text should be preserved
      expect(content).toContain("Analyze this content");
      
      // Image placeholder should be present
      expect(content).toContain("<<<image>>>");
      expect(content).toContain("<<</image>>>");
      
      // Video should be converted to string representation (since flattening occurred)
      expect(content).toContain("video_url");
      expect(content).toContain("https://example.com/video.mp4");
      
      // Verify order: text comes first, then image placeholder, then video
      const textIndex = content.indexOf("Analyze this content");
      const imagePlaceholderIndex = content.indexOf("<<<image>>>");
      const videoIndex = content.indexOf("video_url");
      expect(textIndex).toBeLessThan(imagePlaceholderIndex);
      expect(imagePlaceholderIndex).toBeLessThan(videoIndex);
    });

    it("should handle video content", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze this video: {{description}}" },
            {
              type: "video_url",
              video_url: {
                url: "{{video_url}}",
                mime_type: "video/mp4",
              },
            },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        description: "traffic analysis",
        video_url: "https://example.com/traffic.mp4",
      });

      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze this video: traffic analysis" },
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/traffic.mp4",
                mime_type: "video/mp4",
              },
            },
          ],
        },
      ]);
    });
  });

  describe("fromApiResponse", () => {
    it("should create ChatPrompt from valid API response", () => {
      const messages: ChatMessage[] = [
        { role: "system", content: "You are a helpful assistant" },
        { role: "user", content: "Hello {{name}}" },
      ];

      const promptData: OpikApi.PromptPublic = {
        name: "test-prompt",
        description: "Test description",
        tags: ["tag1"],
      };

      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-456",
        promptId: "prompt-123",
        template: JSON.stringify(messages),
        commit: "abc123",
        type: "mustache",
        metadata: { key: "value" },
        changeDescription: "Initial version",
      };

      const chatPrompt = ChatPrompt.fromApiResponse(
        promptData,
        apiResponse,
        mockClient
      );

      expect(chatPrompt.id).toBe("prompt-123");
      expect(chatPrompt.versionId).toBe("version-456");
      expect(chatPrompt.name).toBe("test-prompt");
      expect(chatPrompt.messages).toEqual(messages);
      expect(chatPrompt.commit).toBe("abc123");
      expect(chatPrompt.type).toBe("mustache");
      expect(chatPrompt.description).toBe("Test description");
      expect(chatPrompt.tags).toEqual(["tag1"]);
    });

    it("should throw error if template is missing", () => {
      const promptData: OpikApi.PromptPublic = {
        name: "test-prompt",
      };

      const apiResponse = {
        id: "version-456",
        promptId: "prompt-123",
        commit: "abc123",
      } as OpikApi.PromptVersionDetail;

      expect(() =>
        ChatPrompt.fromApiResponse(promptData, apiResponse, mockClient)
      ).toThrow(PromptValidationError);
    });

    it("should throw error if template is not valid JSON", () => {
      const promptData: OpikApi.PromptPublic = {
        name: "test-prompt",
      };

      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-456",
        promptId: "prompt-123",
        template: "invalid json",
        commit: "abc123",
      };

      expect(() =>
        ChatPrompt.fromApiResponse(promptData, apiResponse, mockClient)
      ).toThrow(PromptValidationError);
    });

    it("should throw error if template is not an array", () => {
      const promptData: OpikApi.PromptPublic = {
        name: "test-prompt",
      };

      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-456",
        promptId: "prompt-123",
        template: JSON.stringify({ not: "array" }),
        commit: "abc123",
      };

      expect(() =>
        ChatPrompt.fromApiResponse(promptData, apiResponse, mockClient)
      ).toThrow(PromptValidationError);
    });
  });

  describe("updateProperties", () => {
    it("should update name, description, and tags", async () => {
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
        description: "Old description",
        tags: ["old-tag"],
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      const updatePromptMock = vi.fn().mockResolvedValue(undefined);
      mockClient.api.prompts.updatePrompt = updatePromptMock;

      await chatPrompt.updateProperties({
        name: "new-name",
        description: "New description",
        tags: ["new-tag"],
      });

      expect(updatePromptMock).toHaveBeenCalledWith(
        "prompt-123",
        {
          name: "new-name",
          description: "New description",
          tags: ["new-tag"],
        },
        {}
      );

      expect(chatPrompt.name).toBe("new-name");
      expect(chatPrompt.description).toBe("New description");
      expect(chatPrompt.tags).toEqual(["new-tag"]);
    });
  });

  describe("delete", () => {
    it("should call deletePrompts with prompt id", async () => {
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      const deletePromptsMock = vi.fn().mockResolvedValue(undefined);
      mockClient.deletePrompts = deletePromptsMock;

      await chatPrompt.delete();

      expect(deletePromptsMock).toHaveBeenCalledWith(["prompt-123"]);
    });
  });

  describe("immutability", () => {
    it("should return deep copy of metadata", () => {
      const metadata = { key: "value", nested: { prop: "data" } };
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
        metadata,
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const retrievedMetadata = chatPrompt.metadata;

      // Modify retrieved metadata
      if (retrievedMetadata) {
        (retrievedMetadata as Record<string, unknown>).key = "modified";
      }

      // Original should be unchanged
      expect(chatPrompt.metadata).toEqual(metadata);
    });

    it("should return frozen copy of tags", () => {
      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages: [{ role: "user", content: "Hello" }],
        tags: ["tag1", "tag2"],
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const tags = chatPrompt.tags;

      // Should not be able to modify
      expect(() => {
        (tags as string[]).push("tag3");
      }).toThrow();
    });
  });

  describe("format() error handling", () => {
    it("should throw PromptValidationError for missing variables in mustache template", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: "Hello {{name}}, you have {{count}} messages",
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Missing 'count' variable
      expect(() => {
        chatPrompt.format({ name: "Alice" });
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for missing variables in text content parts", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              {
                type: "text",
                text: "Hello {{name}}, check {{item}}",
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Missing 'item' variable
      expect(() => {
        chatPrompt.format({ name: "Bob" });
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for missing variables in image URLs", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              {
                type: "image_url",
                image_url: {
                  url: "https://example.com/{{imageId}}.jpg",
                },
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Missing 'imageId' variable
      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for missing variables in video URLs", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              {
                type: "video_url",
                video_url: {
                  url: "https://example.com/videos/{{videoId}}.mp4",
                },
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Missing 'videoId' variable
      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for invalid jinja2 syntax", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: "Hello {{ name | invalid_filter }}",
          },
        ],
        type: "jinja2",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(() => {
        chatPrompt.format({ name: "Alice" });
      }).toThrow(PromptValidationError);
    });

    it("should preserve video_url metadata fields as-is without formatting", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            {
              type: "video_url",
              video_url: {
                url: "{{video_url}}",
                mime_type: "video/{{format}}",
                detail: "{{detailLevel}}",
                format: "{{videoFormat}}",
                duration: 120,
              },
            },
          ],
        },
      ];

      const data: ChatPromptData = {
        promptId: "prompt-123",
        versionId: "version-456",
        name: "test-prompt",
        messages,
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        video_url: "https://example.com/video.mp4",
      });

      // Metadata fields should be preserved as-is, even if they contain template variables
      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            {
              type: "video_url",
              video_url: {
                url: "https://example.com/video.mp4",
                mime_type: "video/{{format}}",
                detail: "{{detailLevel}}",
                format: "{{videoFormat}}",
                duration: 120,
              },
            },
          ],
        },
      ]);
    });

    it("should throw PromptValidationError for jinja2 syntax errors with malformed expressions", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: "Result: {{ name |",
          },
        ],
        type: "jinja2",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Jinja2 will throw syntax error for malformed filter expression
      expect(() => {
        chatPrompt.format({ name: "Alice" });
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for invalid mustache syntax", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: "Hello {{#unclosed",
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);

      // Invalid Mustache syntax should throw during parsing
      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
    });

    it("should throw PromptValidationError for invalid content type (object instead of string/array)", () => {
      const data = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: { invalid: "object" }, // Invalid content type
          },
        ],
        type: "mustache",
      } as unknown as ChatPromptData;

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
      expect(() => {
        chatPrompt.format({});
      }).toThrow(/Invalid message content type/);
    });

    it("should throw PromptValidationError for null content", () => {
      const data = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: null, // Invalid null content
          },
        ],
        type: "mustache",
      } as unknown as ChatPromptData;

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
      expect(() => {
        chatPrompt.format({});
      }).toThrow(/Invalid message content type/);
    });

    it("should throw PromptValidationError for undefined content", () => {
      const data = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: undefined, // Invalid undefined content
          },
        ],
        type: "mustache",
      } as unknown as ChatPromptData;

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
      expect(() => {
        chatPrompt.format({});
      }).toThrow(/Invalid message content type/);
    });

    it("should throw PromptValidationError for number content", () => {
      const data = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: 123, // Invalid number content
          },
        ],
        type: "mustache",
      } as unknown as ChatPromptData;

      const chatPrompt = new ChatPrompt(data, mockClient);

      expect(() => {
        chatPrompt.format({});
      }).toThrow(PromptValidationError);
      expect(() => {
        chatPrompt.format({});
      }).toThrow(/Invalid message content type/);
    });
  });

  describe("unrecognized content types", () => {
    it("should preserve unrecognized content type with custom fields", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Analyze this content" },
              {
                type: "image",
                image: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({});

      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze this content" },
            {
              type: "image",
              image: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
            },
          ],
        },
      ]);
    });

    it("should preserve unrecognized content type with template variables", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Process this {{format}}" },
              {
                type: "audio",
                audio: {
                  url: "{{audio_url}}",
                  format: "mp3",
                },
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        format: "audio file",
        audio_url: "https://example.com/audio.mp3",
      });

      // Text part should be formatted, but audio part should be preserved as-is
      // (template variables in unrecognized types are NOT processed)
      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Process this audio file" },
            {
              type: "audio",
              audio: {
                url: "{{audio_url}}",
                format: "mp3",
              },
            },
          ],
        },
      ]);
    });

    it("should preserve multiple unrecognized content types", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Analyze these files" },
              {
                type: "pdf",
                pdf: {
                  url: "https://example.com/doc.pdf",
                  pages: [1, 2, 3],
                },
              },
              {
                type: "spreadsheet",
                spreadsheet: {
                  url: "https://example.com/data.xlsx",
                  sheet: "Sheet1",
                },
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({});

      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Analyze these files" },
            {
              type: "pdf",
              pdf: {
                url: "https://example.com/doc.pdf",
                pages: [1, 2, 3],
              },
            },
            {
              type: "spreadsheet",
              spreadsheet: {
                url: "https://example.com/data.xlsx",
                sheet: "Sheet1",
              },
            },
          ],
        },
      ]);
    });

    it("should preserve unrecognized content type mixed with recognized types", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Compare these: {{description}}" },
              {
                type: "image_url",
                image_url: {
                  url: "{{image_url}}",
                },
              },
              {
                type: "custom_data",
                data: {
                  type: "sensor_reading",
                  value: 42,
                  unit: "celsius",
                },
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({
        description: "image and data",
        image_url: "https://example.com/photo.jpg",
      });

      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Compare these: image and data" },
            {
              type: "image_url",
              image_url: {
                url: "https://example.com/photo.jpg",
              },
            },
            {
              type: "custom_data",
              data: {
                type: "sensor_reading",
                value: 42,
                unit: "celsius",
              },
            },
          ],
        },
      ]);
    });

    it("should preserve unrecognized content type when saving and retrieving from API", () => {
      const messages: ChatMessage[] = [
        {
          role: "user",
          content: [
            { type: "text", text: "Process this" },
            {
              type: "custom_format",
              custom_format: {
                data: "base64encodeddata",
                metadata: { version: "1.0" },
              },
            },
          ],
        },
      ];

      const promptData: OpikApi.PromptPublic = {
        name: "test-prompt",
        description: "Test with custom content",
        tags: ["custom"],
      };

      const apiResponse: OpikApi.PromptVersionDetail = {
        id: "version-456",
        promptId: "prompt-123",
        template: JSON.stringify(messages),
        commit: "abc123",
        type: "mustache",
      };

      const chatPrompt = ChatPrompt.fromApiResponse(
        promptData,
        apiResponse,
        mockClient
      );

      expect(chatPrompt.messages).toEqual(messages);

      // Verify formatting preserves the custom type
      const formatted = chatPrompt.format({});
      expect(formatted).toEqual(messages);
    });

    it("should collapse to single text when unrecognized type is alone in content", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Only text here" },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({});

      // Should collapse to string when only one text part
      expect(formatted).toEqual([
        {
          role: "user",
          content: "Only text here",
        },
      ]);
      expect(typeof formatted[0].content).toBe("string");
    });

    it("should NOT collapse when unrecognized type is present with text", () => {
      const data: ChatPromptData = {
        promptId: "test-id",
        versionId: "version-id",
        name: "test-prompt",
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Text here" },
              {
                type: "unknown",
                data: "something",
              },
            ],
          },
        ],
        type: "mustache",
      };

      const chatPrompt = new ChatPrompt(data, mockClient);
      const formatted = chatPrompt.format({});

      // Should NOT collapse to string when multiple parts present
      expect(formatted).toEqual([
        {
          role: "user",
          content: [
            { type: "text", text: "Text here" },
            {
              type: "unknown",
              data: "something",
            },
          ],
        },
      ]);
      expect(Array.isArray(formatted[0].content)).toBe(true);
    });
  });
});
