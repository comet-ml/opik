import { describe, it, expect } from "vitest";
import { mapLangChainMessages, combineLangChainMessages } from "./mapper";

describe("mapLangChainMessages", () => {
  describe("Input mapping", () => {
    it("should map flat messages with human/ai types as roles directly", () => {
      const data = {
        messages: [
          { type: "human", content: "Hello" },
          { type: "ai", content: "Hi there!" },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[0].id).toBe("input-0");
      expect(result.messages[1].role).toBe("ai");
      expect(result.messages[1].id).toBe("input-1");
    });

    it("should map system type messages", () => {
      const data = {
        messages: [
          { type: "system", content: "You are helpful" },
          { type: "human", content: "Hi" },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages[0].role).toBe("system");
    });

    it("should map chat type to user role", () => {
      const data = {
        messages: [{ type: "chat", content: "Hello" }],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages[0].role).toBe("user");
    });

    it("should map batched format (first batch only)", () => {
      const data = {
        messages: [
          [
            { type: "human", content: "Hello" },
            { type: "ai", content: "Response" },
          ],
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[0].id).toBe("input-0");
      expect(result.messages[1].role).toBe("ai");
    });

    it("should map messages with string content", () => {
      const data = {
        messages: [{ type: "human", content: "Hello world" }],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages[0].blocks).toHaveLength(1);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe("Hello world");
      }
    });

    it("should map messages with multimodal array content", () => {
      const data = {
        messages: [
          {
            type: "human",
            content: [
              { type: "text", text: "What is this?" },
              {
                type: "image_url",
                image_url: { url: "https://example.com/image.png" },
              },
            ],
          },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages[0].blocks).toHaveLength(2);
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      expect(result.messages[0].blocks[1].blockType).toBe("image");
    });

    it("should map tool messages with name label", () => {
      const data = {
        messages: [
          {
            type: "tool",
            content: '{"result": "42"}',
            name: "calculator",
            tool_call_id: "call_123",
          },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages[0].role).toBe("tool");
      expect(result.messages[0].label).toBe("calculator");
      expect(result.messages[0].blocks[0].blockType).toBe("code");
    });
  });

  describe("Nested input mapping (Pydantic BaseModel state)", () => {
    it("should map nested flat messages", () => {
      const data = {
        input: {
          messages: [
            { type: "human", content: "Hello" },
            { type: "ai", content: "Hi there!" },
          ],
        },
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[0].id).toBe("input-0");
      expect(result.messages[1].role).toBe("ai");
      expect(result.messages[1].id).toBe("input-1");
    });

    it("should map nested batched messages (first batch only)", () => {
      const data = {
        input: {
          messages: [
            [
              { type: "human", content: "Hello" },
              { type: "ai", content: "Response" },
            ],
          ],
        },
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[1].role).toBe("ai");
    });

    it("should map real Pydantic trace shape with extra fields", () => {
      const data = {
        input: {
          messages: [
            {
              type: "human",
              content: "What is the weather?",
              additional_kwargs: {},
              response_metadata: {},
              id: null,
              name: null,
            },
          ],
        },
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "What is the weather?",
        );
      }
    });

    it("should prefer top-level messages over nested input", () => {
      const data = {
        messages: [{ type: "human", content: "Top level" }],
        input: {
          messages: [{ type: "ai", content: "Nested" }],
        },
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("human");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe("Top level");
      }
    });
  });

  describe("Output mapping", () => {
    it("should map flat output messages", () => {
      const data = {
        messages: [
          {
            type: "ai",
            content: "Here is the response",
            response_metadata: { finish_reason: "stop" },
          },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("ai");
      expect(result.messages[0].id).toBe("output-0");
      expect(result.messages[0].finishReason).toBe("stop");
    });

    it("should map generations format", () => {
      const data = {
        generations: [
          [
            {
              text: "Response text",
              generation_info: { finish_reason: "stop" },
              type: "ChatGeneration",
            },
          ],
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].role).toBe("ai");
      expect(result.messages[0].blocks[0].blockType).toBe("text");
      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "Response text",
        );
      }
      expect(result.messages[0].finishReason).toBe("stop");
    });

    it("should extract usage from generations llm_output", () => {
      const data = {
        generations: [[{ text: "Response" }]],
        llm_output: {
          token_usage: {
            prompt_tokens: 10,
            completion_tokens: 20,
            total_tokens: 30,
          },
        },
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      expect(result.usage).toEqual({
        prompt_tokens: 10,
        completion_tokens: 20,
        total_tokens: 30,
      });
    });

    it("should extract content from generation message kwargs", () => {
      const data = {
        generations: [
          [
            {
              text: "fallback text",
              message: {
                lc: 1,
                type: "constructor",
                id: ["langchain_core", "messages", "ai", "AIMessage"],
                kwargs: { content: "kwargs content", type: "ai" },
              },
            },
          ],
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "kwargs content",
        );
      }
    });

    it("should fall back to generation text when kwargs missing", () => {
      const data = {
        generations: [[{ text: "fallback text" }]],
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      if (result.messages[0].blocks[0].blockType === "text") {
        expect(result.messages[0].blocks[0].props.children).toBe(
          "fallback text",
        );
      }
    });

    it("should extract finish_reason from response_metadata", () => {
      const data = {
        messages: [
          {
            type: "ai",
            content: "Done",
            response_metadata: { finish_reason: "stop" },
          },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "output" });

      expect(result.messages[0].finishReason).toBe("stop");
    });
  });

  describe("Tool calls", () => {
    it("should map LangChain tool_calls with args as object", () => {
      const data = {
        messages: [
          {
            type: "ai",
            content: "",
            tool_calls: [
              {
                name: "search",
                args: { query: "weather" },
                id: "call_1",
                type: "tool_call",
              },
            ],
          },
        ],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });
      const toolBlock = result.messages[0].blocks.find(
        (b) => b.blockType === "code",
      );

      expect(toolBlock).toBeDefined();
      if (toolBlock && toolBlock.blockType === "code") {
        expect(toolBlock.props.label).toBe("search");
        expect(toolBlock.props.code).toBe(
          JSON.stringify({ query: "weather" }, null, 2),
        );
      }
    });
  });

  describe("combineLangChainMessages", () => {
    it("should use only output messages when output has flat messages (LangGraph state)", () => {
      const inputData = {
        messages: [{ type: "human", content: "Hello" }],
      };
      const outputData = {
        messages: [
          { type: "human", content: "Hello" },
          { type: "ai", content: "Hi there!" },
        ],
      };

      const inputMapped = mapLangChainMessages(inputData, {
        fieldType: "input",
      });
      const outputMapped = mapLangChainMessages(outputData, {
        fieldType: "output",
      });

      const result = combineLangChainMessages(
        { raw: inputData, mapped: inputMapped },
        { raw: outputData, mapped: outputMapped },
      );

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[1].role).toBe("ai");
      expect(result.messages[0].id).toBe("output-0");
    });

    it("should concatenate input + output when output has generations", () => {
      const inputData = {
        messages: [{ type: "human", content: "Hello" }],
      };
      const outputData = {
        generations: [[{ text: "Response text" }]],
      };

      const inputMapped = mapLangChainMessages(inputData, {
        fieldType: "input",
      });
      const outputMapped = mapLangChainMessages(outputData, {
        fieldType: "output",
      });

      const result = combineLangChainMessages(
        { raw: inputData, mapped: inputMapped },
        { raw: outputData, mapped: outputMapped },
      );

      expect(result.messages).toHaveLength(2);
      expect(result.messages[0].role).toBe("human");
      expect(result.messages[0].id).toBe("input-0");
      expect(result.messages[1].role).toBe("ai");
      expect(result.messages[1].id).toBe("output-0");
    });

    it("should preserve usage from output", () => {
      const inputData = {
        messages: [{ type: "human", content: "Hello" }],
      };
      const outputData = {
        generations: [[{ text: "Response" }]],
        llm_output: {
          token_usage: {
            prompt_tokens: 10,
            completion_tokens: 20,
            total_tokens: 30,
          },
        },
      };

      const inputMapped = mapLangChainMessages(inputData, {
        fieldType: "input",
      });
      const outputMapped = mapLangChainMessages(outputData, {
        fieldType: "output",
      });

      const result = combineLangChainMessages(
        { raw: inputData, mapped: inputMapped },
        { raw: outputData, mapped: outputMapped },
      );

      expect(result.usage).toEqual({
        prompt_tokens: 10,
        completion_tokens: 20,
        total_tokens: 30,
      });
    });
  });

  describe("Edge cases", () => {
    it("should return empty for null data", () => {
      const result = mapLangChainMessages(null, { fieldType: "input" });
      expect(result.messages).toHaveLength(0);
    });

    it("should return empty for undefined data", () => {
      const result = mapLangChainMessages(undefined, { fieldType: "input" });
      expect(result.messages).toHaveLength(0);
    });

    it("should return empty when fieldType not specified", () => {
      const data = {
        messages: [{ type: "human", content: "Hello" }],
      };
      const result = mapLangChainMessages(data, {});
      expect(result.messages).toHaveLength(0);
    });

    it("should handle messages with empty string content", () => {
      const data = {
        messages: [{ type: "ai", content: "" }],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(0);
    });

    it("should handle messages without content field", () => {
      const data = {
        messages: [{ type: "ai" }],
      };
      const result = mapLangChainMessages(data, { fieldType: "input" });

      expect(result.messages).toHaveLength(1);
      expect(result.messages[0].blocks).toHaveLength(0);
    });
  });
});
