import { describe, it, expect } from "vitest";
import { mapOpenAIMessages } from "./mapper";

describe("mapOpenAIMessages", () => {
  describe("Input formats", () => {
    it("should map standard OpenAI input format", () => {
      const data = {
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi there!" },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("user");
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      expect(result.messages[1].role).toBe("assistant");
    });

    it("should map direct array format", () => {
      const data = [
        { role: "user", content: "Hello" },
        { role: "assistant", content: "Hi there!" },
      ];
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("user");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
    });

    it("should map custom input format with text field", () => {
      const data = {
        input: [
          {
            role: "system",
            text: "You are a helpful assistant",
            files: [],
          },
          {
            role: "user",
            text: "What is 2+2?",
            files: [],
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("system");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "You are a helpful assistant",
        );
      }
      expect(result.messages[1].role).toBe("user");
      if (result.messages[1].blocks[0].blockType === "text") {
        expect(result.messages[1].blocks[0].props.children).toBe(
          "What is 2+2?",
        );
      }
    });

    it("should handle multimodal content in custom input format", () => {
      const data = {
        input: [
          {
            role: "user",
            text: [
              { type: "text", text: "What's in this image?" },
              {
                type: "image_url",
                image_url: { url: "https://example.com/image.jpg" },
              },
            ],
            files: [],
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(2);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      expect(result.messages[0].blocks[1].blockType).toBe("image");
    });
  });

  describe("Output formats", () => {
    it("should map standard OpenAI output format", () => {
      const data = {
        choices: [
          {
            message: { role: "assistant", content: "Hello!" },
            index: 0,
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("assistant");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe("Hello!");
      }
    });

    it("should map standard OpenAI output format with usage and finish_reason", () => {
      const data = {
        choices: [
          {
            message: { role: "assistant", content: "Hello!" },
            index: 0,
            finish_reason: "stop",
          },
        ],
        usage: {
          prompt_tokens: 10,
          completion_tokens: 5,
          total_tokens: 15,
        },
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("assistant");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe("Hello!");
      }
      // Check finish reason and usage
      expect(result.messages[0].finishReason).toBe("stop");
      expect(result.usage).toEqual({
        prompt_tokens: 10,
        completion_tokens: 5,
        total_tokens: 15,
      });
    });

    it("should map custom output format", () => {
      const data = {
        text: "This is the answer",
        usage: {
          prompt_tokens: 180,
          completion_tokens: 219,
          total_tokens: 399,
        },
        finish_reason: "stop",
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("assistant");
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "This is the answer",
        );
      }
      // Check finish reason and usage
      expect(result.messages[0].finishReason).toBe("stop");
      expect(result.usage).toEqual({
        prompt_tokens: 180,
        completion_tokens: 219,
        total_tokens: 399,
      });
    });

    it("should map custom output format with minimal data", () => {
      const data = {
        text: "Simple response",
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("assistant");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "Simple response",
        );
      }
    });
  });

  describe("Edge cases", () => {
    it("should return empty result for null data", () => {
      expect(mapOpenAIMessages(null, { fieldType: "input" })).toEqual({
        messages: [],
      });
    });

    it("should return empty result for undefined data", () => {
      expect(mapOpenAIMessages(undefined, { fieldType: "input" })).toEqual({
        messages: [],
      });
    });

    it("should return empty result when fieldType is not specified", () => {
      const data = [{ role: "user", content: "Hello" }];
      expect(mapOpenAIMessages(data, {})).toEqual({ messages: [] });
    });

    it("should return empty result for invalid format", () => {
      const data = { invalid: "format" };
      expect(mapOpenAIMessages(data, { fieldType: "input" })).toEqual({
        messages: [],
      });
    });
  });

  describe("Legacy function role handling", () => {
    it("should normalize legacy function role to tool in standard format", () => {
      const data = {
        messages: [
          {
            role: "function",
            name: "get_weather",
            content: '{"temperature": 72, "condition": "sunny"}',
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe("get_weather");
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("code");
    });

    it("should normalize legacy function role to tool in direct array format", () => {
      const data = [
        {
          role: "function",
          name: "calculate_sum",
          content: '{"result": 42}',
        },
      ];
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe("calculate_sum");
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("code");
    });

    it("should normalize legacy function role in output format", () => {
      const data = {
        choices: [
          {
            message: {
              role: "function",
              name: "search_database",
              content: '{"records": [1, 2, 3]}',
            },
            index: 0,
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe("search_database");
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("code");
    });

    it("should preserve function name as label when normalizing", () => {
      const data = {
        messages: [
          {
            role: "function",
            name: "my_custom_function",
            content: "Function result",
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe("my_custom_function");
    });

    it("should handle function role without name field", () => {
      const data = {
        messages: [
          {
            role: "function",
            content: "Some result",
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe(undefined);
    });

    it("should format JSON content in function messages", () => {
      const data = {
        messages: [
          {
            role: "function",
            name: "api_call",
            content: '{"status":"success","data":{"id":123}}',
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages[0].blocks[0].blockType).toBe("code");
      if (result.messages[0].blocks[0].blockType === "code") {
        const code = result.messages[0].blocks[0].props.code;
        // Should be pretty-printed JSON
        expect(code).toContain("\n");
        expect(code).toContain("status");
        expect(code).toContain("success");
      }
    });
  });

  describe("Audio messages", () => {
    it("should handle message with audio and transcript", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: null,
              audio: {
                id: "audio_696a22a63a3c8191881b0c0e87a8c59c",
                data: "[output-attachment-1-1768563367373.wav]",
                expires_at: 1768566966,
                transcript:
                  "I received the first audio sample. Please go ahead and send the second one as well so I can confirm.",
              },
            },
            finish_reason: "stop",
            index: 0,
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("assistant");
      expect(result.messages[0].blocks).toHaveLength(2);

      // First block should be audio
      expect(result.messages[0].blocks[0].blockType).toBe("audio");
      if (result.messages[0].blocks[0].blockType === "audio") {
        expect(result.messages[0].blocks[0].props.audios).toEqual([
          {
            url: "[output-attachment-1-1768563367373.wav]",
            name: "audio_696a22a63a3c8191881b0c0e87a8c59c",
          },
        ]);
      }

      // Second block should be text with transcript
      expect(result.messages[0].blocks[1].blockType).toBe("text");
      if (result.messages[0].blocks[1].blockType === "text") {
        expect(result.messages[0].blocks[1].props.children).toBe(
          "I received the first audio sample. Please go ahead and send the second one as well so I can confirm.",
        );
        expect(result.messages[0].blocks[1].props.showMoreButton).toBe(true);
      }
    });

    it("should handle audio without transcript", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: null,
              audio: {
                id: "audio_123",
                data: "[audio-file.wav]",
              },
            },
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("audio");
      if (result.messages[0].blocks[0].blockType === "audio") {
        expect(result.messages[0].blocks[0].props.audios).toEqual([
          {
            url: "[audio-file.wav]",
            name: "audio_123",
          },
        ]);
      }
    });

    it("should handle transcript without audio data", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: null,
              audio: {
                id: "audio_456",
                data: "",
                transcript: "This is just a transcript without audio data.",
              },
            },
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "This is just a transcript without audio data.",
        );
      }
    });

    it("should handle audio with actual URL", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: null,
              audio: {
                id: "audio_789",
                data: "https://example.com/audio.wav",
                expires_at: 1768566966,
                transcript: "Audio with real URL",
              },
            },
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(2);
      expect(result.messages[0].blocks[0].blockType).toBe("audio");
      if (result.messages[0].blocks[0].blockType === "audio") {
        expect(result.messages[0].blocks[0].props.audios).toEqual([
          {
            url: "https://example.com/audio.wav",
            name: "audio_789",
          },
        ]);
      }
    });

    it("should handle message with both content and audio", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: "Here is the audio response:",
              audio: {
                id: "audio_999",
                data: "[audio.wav]",
                transcript: "Transcript of the audio",
              },
            },
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(3);

      // First block should be text content
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "Here is the audio response:",
        );
      }

      // Second block should be audio
      expect(result.messages[0].blocks[1].blockType).toBe("audio");

      // Third block should be transcript
      expect(result.messages[0].blocks[2].blockType).toBe("text");
      if (result.messages[0].blocks[2].blockType === "text") {
        expect(result.messages[0].blocks[2].props.children).toBe(
          "Transcript of the audio",
        );
      }
    });

    it("should not create blocks when audio object is empty", () => {
      const data = {
        choices: [
          {
            message: {
              role: "assistant",
              content: null,
              audio: {
                id: "audio_empty",
                data: "",
              },
            },
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(0);
    });
  });

  describe("Input audio content", () => {
    it("should handle input_audio content type in messages array", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: [
              {
                type: "text",
                text: "I'm sending you two identical audio samples. Please confirm you received them.",
              },
              {
                type: "input_audio",
                input_audio: {
                  data: "[image_0]",
                  format: "wav",
                },
              },
              {
                type: "input_audio",
                input_audio: {
                  data: "[image_1]",
                  format: "wav",
                },
              },
            ],
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("user");
      expect(result.messages[0].blocks).toHaveLength(2);

      // First block should be text
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "I'm sending you two identical audio samples. Please confirm you received them.",
        );
      }

      // Second block should be audio with both files
      expect(result.messages[0].blocks[1].blockType).toBe("audio");
      if (result.messages[0].blocks[1].blockType === "audio") {
        expect(result.messages[0].blocks[1].props.audios).toHaveLength(2);
        expect(result.messages[0].blocks[1].props.audios?.[0].url).toBe(
          "[image_0]",
        );
        expect(result.messages[0].blocks[1].props.audios?.[1].url).toBe(
          "[image_1]",
        );
      }
    });

    it("should handle mixed content with text, audio, and images", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: [
              {
                type: "text",
                text: "Here is some content:",
              },
              {
                type: "input_audio",
                input_audio: {
                  data: "[audio_file.wav]",
                  format: "wav",
                },
              },
              {
                type: "text",
                text: "And an image:",
              },
              {
                type: "image_url",
                image_url: {
                  url: "[image_0]",
                },
              },
            ],
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(4);

      // Verify block order: text, audio, text, image
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      expect(result.messages[0].blocks[1].blockType).toBe("audio");
      expect(result.messages[0].blocks[2].blockType).toBe("text");
      expect(result.messages[0].blocks[3].blockType).toBe("image");
    });

    it("should handle input_audio with empty data", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: [
              {
                type: "input_audio",
                input_audio: {
                  data: "",
                  format: "wav",
                },
              },
            ],
          },
        ],
      };

      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(0);
    });
  });
});
