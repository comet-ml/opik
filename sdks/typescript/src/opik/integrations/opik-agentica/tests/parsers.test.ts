import { parseInputArgs, parseOutput, parseUsage } from "../src/parsers";

describe("Agentica parsers", () => {
  describe("parseInputArgs", () => {
    it("parses call() inputs with prompt and scope", () => {
      const input = parseInputArgs("call", [
        "Summarize this",
        { text: "hello world" },
      ]);

      expect(input).toEqual({
        prompt: "Summarize this",
        scope: { text: "hello world" },
      });
    });

    it("parses spawn() inputs with config and scope", () => {
      const input = parseInputArgs("spawn", [
        { premise: "You are helpful", model: "openai:gpt-5" },
        { toolA: () => "ok" },
      ]);

      expect(input).toMatchObject({
        config: { premise: "You are helpful", model: "openai:gpt-5" },
        scope: {
          toolA: expect.stringMatching(/^\[Function:/),
        },
      });
    });

    it("sanitizes non-serializable values", () => {
      const circular: Record<string, unknown> = {};
      circular.self = circular;

      const input = parseInputArgs("agentic", [
        "Analyze",
        { fn: () => "ok", circular },
      ]);

      expect(input).toEqual({
        prompt: "Analyze",
        scope: {
          fn: "[Function: fn]",
          circular: {
            self: "[Circular]",
          },
        },
      });
    });
  });

  describe("parseUsage", () => {
    it("normalizes snake_case usage fields", () => {
      const usage = parseUsage({
        input_tokens: 10,
        output_tokens: 4,
        total_tokens: 14,
      });

      expect(usage).toEqual({
        prompt_tokens: 10,
        completion_tokens: 4,
        total_tokens: 14,
        "original_usage.input_tokens": 10,
        "original_usage.output_tokens": 4,
        "original_usage.total_tokens": 14,
      });
    });

    it("normalizes camelCase usage fields and computes total when absent", () => {
      const usage = parseUsage({
        inputTokens: 3,
        outputTokens: 2,
      });

      expect(usage).toEqual({
        prompt_tokens: 3,
        completion_tokens: 2,
        total_tokens: 5,
        "original_usage.inputTokens": 3,
        "original_usage.outputTokens": 2,
      });
    });

    it("flattens nested numeric usage values", () => {
      const usage = parseUsage({
        prompt_tokens: 7,
        completion_tokens: 5,
        total_tokens: 12,
        input_tokens_details: {
          cached_tokens: 2,
        },
      });

      expect(usage).toEqual({
        prompt_tokens: 7,
        completion_tokens: 5,
        total_tokens: 12,
        "original_usage.prompt_tokens": 7,
        "original_usage.completion_tokens": 5,
        "original_usage.total_tokens": 12,
        "original_usage.input_tokens_details.cached_tokens": 2,
      });
    });
  });

  describe("parseOutput", () => {
    it("wraps primitive output values", () => {
      expect(parseOutput("done")).toEqual({ output: "done" });
      expect(parseOutput(123)).toEqual({ output: 123 });
    });

    it("preserves object output values", () => {
      expect(parseOutput({ result: "ok" })).toEqual({ result: "ok" });
    });
  });
});
