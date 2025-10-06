import { describe, expect, it } from "vitest";
import { formatProviderData, canFormatProviderData } from "./provider-schemas";
import { PROVIDER_TYPE } from "@/types/providers";

describe("provider-schemas", () => {
  describe("formatProviderData", () => {
    describe("OpenAI formatting", () => {
      it("should format OpenAI input correctly", () => {
        const input = {
          messages: [
            { role: "user", content: "Hello, how are you?" }
          ],
          model: "gpt-4",
          temperature: 0.7,
          max_tokens: 1000,
        };

        const result = formatProviderData(PROVIDER_TYPE.OPEN_AI, input, "input");
        
        expect(result).toEqual({
          content: "Hello, how are you?",
          metadata: {
            model: "gpt-4",
            temperature: 0.7,
            max_tokens: 1000,
          },
        });
      });

      it("should format OpenAI output correctly", () => {
        const output = {
          choices: [
            {
              message: { content: "I'm doing well, thank you!" },
              finish_reason: "stop",
            }
          ],
          model: "gpt-4",
          usage: {
            prompt_tokens: 10,
            completion_tokens: 8,
            total_tokens: 18,
          },
        };

        const result = formatProviderData(PROVIDER_TYPE.OPEN_AI, output, "output");
        
        expect(result).toEqual({
          content: "I'm doing well, thank you!",
          metadata: {
            model: "gpt-4",
            usage: {
              prompt_tokens: 10,
              completion_tokens: 8,
              total_tokens: 18,
            },
            finish_reason: "stop",
          },
        });
      });

      it("should handle multimodal content in input", () => {
        const input = {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "What's in this image?" },
                { type: "image_url", image_url: { url: "data:image/jpeg;base64,..." } }
              ]
            }
          ],
          model: "gpt-4-vision",
        };

        const result = formatProviderData(PROVIDER_TYPE.OPEN_AI, input, "input");
        
        expect(result).toEqual({
          content: "What's in this image?",
          metadata: {
            model: "gpt-4-vision",
            temperature: undefined,
            max_tokens: undefined,
          },
        });
      });
    });

    describe("Anthropic formatting", () => {
      it("should format Anthropic input correctly", () => {
        const input = {
          messages: [
            { role: "user", content: "Tell me a joke" }
          ],
          model: "claude-3-sonnet",
          temperature: 0.8,
          max_tokens: 500,
        };

        const result = formatProviderData(PROVIDER_TYPE.ANTHROPIC, input, "input");
        
        expect(result).toEqual({
          content: "Tell me a joke",
          metadata: {
            model: "claude-3-sonnet",
            temperature: 0.8,
            max_tokens: 500,
          },
        });
      });

      it("should format Anthropic output correctly", () => {
        const output = {
          content: "Why don't scientists trust atoms? Because they make up everything!",
          model: "claude-3-sonnet",
          usage: {
            input_tokens: 5,
            output_tokens: 15,
          },
          stop_reason: "end_turn",
        };

        const result = formatProviderData(PROVIDER_TYPE.ANTHROPIC, output, "output");
        
        expect(result).toEqual({
          content: "Why don't scientists trust atoms? Because they make up everything!",
          metadata: {
            model: "claude-3-sonnet",
            usage: {
              input_tokens: 5,
              output_tokens: 15,
            },
            stop_reason: "end_turn",
          },
        });
      });
    });

    describe("Gemini formatting", () => {
      it("should format Gemini input correctly", () => {
        const input = {
          contents: [
            {
              parts: [
                { text: "Explain quantum computing" }
              ]
            }
          ],
          model: "gemini-2.0-flash",
          temperature: 0.6,
          maxOutputTokens: 2000,
        };

        const result = formatProviderData(PROVIDER_TYPE.GEMINI, input, "input");
        
        expect(result).toEqual({
          content: "Explain quantum computing",
          metadata: {
            model: "gemini-2.0-flash",
            temperature: 0.6,
            maxOutputTokens: 2000,
          },
        });
      });

      it("should format Gemini output correctly", () => {
        const output = {
          candidates: [
            {
              content: {
                parts: [
                  { text: "Quantum computing is a type of computation..." }
                ]
              },
              finishReason: "STOP",
            }
          ],
          model: "gemini-2.0-flash",
          usageMetadata: {
            promptTokenCount: 4,
            candidatesTokenCount: 20,
            totalTokenCount: 24,
          },
        };

        const result = formatProviderData(PROVIDER_TYPE.GEMINI, output, "output");
        
        expect(result).toEqual({
          content: "Quantum computing is a type of computation...",
          metadata: {
            model: "gemini-2.0-flash",
            usage: {
              promptTokenCount: 4,
              candidatesTokenCount: 20,
              totalTokenCount: 24,
            },
            finishReason: "STOP",
          },
        });
      });
    });

    it("should return null for invalid data", () => {
      const invalidData = { invalid: "structure" };
      
      expect(formatProviderData(PROVIDER_TYPE.OPEN_AI, invalidData, "input")).toBeNull();
      expect(formatProviderData(PROVIDER_TYPE.ANTHROPIC, invalidData, "output")).toBeNull();
      expect(formatProviderData(PROVIDER_TYPE.GEMINI, invalidData, "input")).toBeNull();
    });

    it("should return null for empty content", () => {
      const emptyInput = { messages: [{ role: "user", content: "" }] };
      
      expect(formatProviderData(PROVIDER_TYPE.OPEN_AI, emptyInput, "input")).toBeNull();
    });
  });

  describe("canFormatProviderData", () => {
    it("should return true for valid OpenAI data", () => {
      const validInput = {
        messages: [{ role: "user", content: "Hello" }]
      };
      
      expect(canFormatProviderData(PROVIDER_TYPE.OPEN_AI, validInput, "input")).toBe(true);
    });

    it("should return false for invalid data", () => {
      const invalidData = { invalid: "structure" };
      
      expect(canFormatProviderData(PROVIDER_TYPE.OPEN_AI, invalidData, "input")).toBe(false);
    });

    it("should return false for empty content", () => {
      const emptyInput = { messages: [{ role: "user", content: "" }] };
      
      expect(canFormatProviderData(PROVIDER_TYPE.OPEN_AI, emptyInput, "input")).toBe(false);
    });
  });
});
