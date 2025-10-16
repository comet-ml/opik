import { describe, it, expect } from "vitest";
import { hasToolCalls, traceHasToolCalls } from "./toolCallDetection";

describe("toolCallDetection", () => {
  describe("hasToolCalls", () => {
    it("should return false for null or undefined data", () => {
      expect(hasToolCalls(null)).toBe(false);
      expect(hasToolCalls(undefined)).toBe(false);
    });

    it("should return false for non-object data", () => {
      expect(hasToolCalls("string")).toBe(false);
      expect(hasToolCalls(123)).toBe(false);
      expect(hasToolCalls(true)).toBe(false);
    });

    it("should return false for empty object", () => {
      expect(hasToolCalls({})).toBe(false);
    });

    it("should detect tool_calls in root object (OpenAI format)", () => {
      const data = {
        role: "assistant",
        content: null,
        tool_calls: [
          {
            id: "call_abc123",
            type: "function",
            function: {
              name: "get_weather",
              arguments: '{"location": "New York"}',
            },
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should return false if tool_calls array is empty", () => {
      const data = {
        role: "assistant",
        content: null,
        tool_calls: [],
      };

      expect(hasToolCalls(data)).toBe(false);
    });

    it("should detect tool_calls in messages array", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: "What's the weather?",
          },
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_abc123",
                type: "function",
                function: {
                  name: "get_weather",
                  arguments: '{"location": "New York"}',
                },
              },
            ],
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should detect tool role messages", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: "What's the weather?",
          },
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_abc123",
                type: "function",
                function: {
                  name: "get_weather",
                  arguments: '{"location": "New York"}',
                },
              },
            ],
          },
          {
            role: "tool",
            tool_call_id: "call_abc123",
            content: "The weather in New York is sunny, 72°F",
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should detect tool_calls in output array", () => {
      const data = {
        output: [
          {
            role: "assistant",
            tool_calls: [
              {
                id: "call_xyz789",
                type: "function",
                function: {
                  name: "search_database",
                  arguments: '{"query": "users"}',
                },
              },
            ],
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should detect tool_calls in input array", () => {
      const data = {
        input: [
          {
            role: "assistant",
            tool_calls: [
              {
                id: "call_def456",
                type: "function",
                function: {
                  name: "calculate",
                  arguments: '{"expression": "2+2"}',
                },
              },
            ],
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should return false for messages without tool_calls", () => {
      const data = {
        messages: [
          {
            role: "user",
            content: "Hello",
          },
          {
            role: "assistant",
            content: "Hi there!",
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(false);
    });

    it("should handle nested message structures", () => {
      const data = {
        model: "gpt-4",
        messages: [
          {
            role: "user",
            content: "Book a flight",
          },
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_flight123",
                type: "function",
                function: {
                  name: "book_flight",
                  arguments:
                    '{"from": "NYC", "to": "LAX", "date": "2024-01-15"}',
                },
              },
            ],
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });

    it("should handle multiple tool calls", () => {
      const data = {
        role: "assistant",
        tool_calls: [
          {
            id: "call_1",
            type: "function",
            function: { name: "tool_1", arguments: "{}" },
          },
          {
            id: "call_2",
            type: "function",
            function: { name: "tool_2", arguments: "{}" },
          },
          {
            id: "call_3",
            type: "function",
            function: { name: "tool_3", arguments: "{}" },
          },
        ],
      };

      expect(hasToolCalls(data)).toBe(true);
    });
  });

  describe("traceHasToolCalls", () => {
    it("should return false for traces without tool calls", () => {
      const trace = {
        input: { role: "user", content: "Hello" },
        output: { role: "assistant", content: "Hi there!" },
      };

      expect(traceHasToolCalls(trace)).toBe(false);
    });

    it("should detect tool calls in trace input", () => {
      const trace = {
        input: {
          messages: [
            {
              role: "assistant",
              tool_calls: [
                {
                  id: "call_123",
                  type: "function",
                  function: { name: "search", arguments: '{"q":"test"}' },
                },
              ],
            },
          ],
        },
        output: { content: "Result" },
      };

      expect(traceHasToolCalls(trace)).toBe(true);
    });

    it("should detect tool calls in trace output", () => {
      const trace = {
        input: { content: "Search for data" },
        output: {
          messages: [
            {
              role: "assistant",
              tool_calls: [
                {
                  id: "call_456",
                  type: "function",
                  function: { name: "database_query", arguments: "{}" },
                },
              ],
            },
          ],
        },
      };

      expect(traceHasToolCalls(trace)).toBe(true);
    });

    it("should detect tool calls in either input or output", () => {
      const traceWithInputToolCalls = {
        input: {
          tool_calls: [
            {
              id: "1",
              type: "function",
              function: { name: "fn1", arguments: "{}" },
            },
          ],
        },
        output: { content: "Done" },
      };

      const traceWithOutputToolCalls = {
        input: { content: "Start" },
        output: {
          tool_calls: [
            {
              id: "2",
              type: "function",
              function: { name: "fn2", arguments: "{}" },
            },
          ],
        },
      };

      expect(traceHasToolCalls(traceWithInputToolCalls)).toBe(true);
      expect(traceHasToolCalls(traceWithOutputToolCalls)).toBe(true);
    });

    it("should handle traces with missing input or output", () => {
      expect(traceHasToolCalls({})).toBe(false);
      expect(traceHasToolCalls({ input: undefined, output: undefined })).toBe(
        false,
      );
      expect(traceHasToolCalls({ input: null, output: null })).toBe(false);
    });

    it("should handle real-world trace data structure", () => {
      const trace = {
        id: "trace_123",
        name: "agent_call",
        input: {
          messages: [{ role: "user", content: "Get the weather in Boston" }],
        },
        output: {
          messages: [
            {
              role: "assistant",
              content: null,
              tool_calls: [
                {
                  id: "call_weather_123",
                  type: "function",
                  function: {
                    name: "get_current_weather",
                    arguments: '{"location": "Boston, MA"}',
                  },
                },
              ],
            },
            {
              role: "tool",
              tool_call_id: "call_weather_123",
              content: "The weather in Boston is 65°F and partly cloudy",
            },
            {
              role: "assistant",
              content:
                "The current weather in Boston is 65°F and partly cloudy.",
            },
          ],
        },
      };

      expect(traceHasToolCalls(trace)).toBe(true);
    });
  });
});
