import { describe, expect, it } from "vitest";
import { prettifyMessage } from "./traces";

/**
 * `prettifyMessage` takes a message object, string, or undefined, and transforms it
 * into a structured format with a "prettified" flag indicating whether it has been transformed.
 */
describe("prettifyMessage", () => {
  it("returns the content of the last message if config type is 'input'", () => {
    const message = { messages: [{ content: "Hello" }] };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Hello",
      prettified: true,
      renderType: "text",
    });
  });

  it("extracts the last text content when the last message contains an array", () => {
    const message = {
      messages: [
        {
          content: [
            { type: "image", url: "image.png" },
            { type: "text", text: "Hello there" },
          ],
        },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Hello there",
      prettified: true,
      renderType: "text",
    });
  });

  it("returns the content of the last choice if config type is 'output'", () => {
    const message = {
      choices: [
        { message: { content: "How are you?" } },
        { message: { content: "I'm fine" } },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "I'm fine",
      prettified: true,
      renderType: "text",
    });
  });

  it("unwraps a single key object to get its string value", () => {
    const message = { question: "What is your name?" };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "What is your name?",
      prettified: true,
      renderType: "text",
    });
  });

  it("extracts the correct string using a predefined key map when multiple keys exist", () => {
    const message = { query: "Explain recursion.", extra: "unused" };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Explain recursion.",
      prettified: true,
      renderType: "text",
    });
  });

  it("returns the original message if it is already a string and marks it as not prettified", () => {
    const message = "Simple string message";
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Simple string message",
      prettified: false,
    });
  });

  it("returns the original message content when it cannot be prettified", () => {
    const message = { otherKey: "Not relevant" };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Not relevant",
      prettified: true,
      renderType: "text",
    });
  });

  it("gracefully handles an undefined message", () => {
    const result = prettifyMessage(undefined);
    expect(result).toEqual({ message: undefined, prettified: false });
  });

  it("handles ADK input message format", () => {
    const message = { parts: [{ text: "Hello ADK" }] };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Hello ADK",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles ADK spans input message format", () => {
    const message = { contents: [{ parts: [{ text: "Hello ADK" }] }] };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Hello ADK",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles ADK output message format", () => {
    const message = {
      content: {
        parts: [{ text: "ADK Response" }],
      },
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "ADK Response",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles LangGraph input message format", () => {
    const message = {
      messages: [{ type: "human", content: "User message" }],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "User message",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles LangGraph output message format with multiple AI messages", () => {
    const message = {
      messages: [
        { type: "human", content: "User question" },
        { type: "ai", content: "AI response 1" },
        { type: "human", content: "Follow-up question" },
        { type: "ai", content: "AI response 2" },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: expect.stringContaining("<details open>"),
      prettified: true,
      renderType: "text",
    });
    expect(result.message).toContain("User question");
    expect(result.message).toContain("AI response 1");
    expect(result.message).toContain("Follow-up question");
    expect(result.message).toContain("AI response 2");
    expect(result.message).toContain("<summary><strong>Human</strong></summary>");
    expect(result.message).toContain("<summary><strong>Ai</strong></summary>");
  });

  it("uses default input type when config is not provided", () => {
    const message = { question: "Default input type" };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Default input type",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles empty array content in messages", () => {
    const message = { messages: [{ content: [] }] };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message,
      prettified: true,
      renderType: "json-table",
    });
  });

  it("handles malformed choice objects in output", () => {
    const message = {
      choices: [{ incomplete: "data" }],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message,
      prettified: true,
      renderType: "json-table",
    });
  });

  it("renders OpenAI completion object with JsonKeyValueTable when content is not valid JSON", () => {
    const message = {
      id: "chatcmpl-COQWe7LJJW5rzSa1i2G2IKXc2qamO",
      created: 1759937872,
      model: "gpt-4o-mini-2024-07-18",
      object: "chat.completion",
      system_fingerprint: "fp_560af6e559",
      choices: [
        {
          finish_reason: "stop",
          index: 0,
          message: {
            content:
              "[{'role': 'system', 'content': \"Provide a direct and concise answer to the user's question in one to two sentences. If necessary, utilize the `search_wikipedia` tool to confirm factual information before responding. Avoid any conversational tone or pleasantries.\"}, {'role': 'user', 'content': '{question}'}]",
            role: "assistant",
            tool_calls: null,
            function_call: null,
            annotations: [],
          },
          provider_specific_fields: {},
        },
      ],
      usage: {
        completion_tokens: 69,
        prompt_tokens: 374,
        total_tokens: 443,
        completion_tokens_details: {
          accepted_prediction_tokens: 0,
          audio_tokens: 0,
          reasoning_tokens: 0,
          rejected_prediction_tokens: 0,
          text_tokens: null,
        },
        prompt_tokens_details: {
          audio_tokens: 0,
          cached_tokens: 0,
          text_tokens: null,
          image_tokens: null,
        },
      },
      service_tier: "default",
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message,
      prettified: true,
      renderType: "json-table",
    });
  });

  it("renders array of objects with JsonKeyValueTable when objects should be rendered as tables", () => {
    const message = [
      {
        id: "obj1",
        name: "Object 1",
        data: { key: "value1" },
      },
      {
        id: "obj2",
        name: "Object 2",
        data: { key: "value2" },
      },
    ];
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message,
      prettified: true,
      renderType: "json-table",
    });
  });

  it("processes nested objects with predefined keys", () => {
    const message = {
      data: {
        response: "Nested response",
      },
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Nested response",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles OpenAI Agents input message with multiple user messages", () => {
    const message = {
      input: [
        { role: "system", content: "System message" },
        { role: "user", content: "User message 1" },
        { role: "assistant", content: "Assistant message" },
        { role: "user", content: "User message 2" },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: expect.stringContaining("<details open>"),
      prettified: true,
      renderType: "text",
    });
    expect(result.message).toContain("System message");
    expect(result.message).toContain("User message 1");
    expect(result.message).toContain("Assistant message");
    expect(result.message).toContain("User message 2");
    expect(result.message).toContain("<summary><strong>System</strong></summary>");
    expect(result.message).toContain("<summary><strong>User</strong></summary>");
    expect(result.message).toContain("<summary><strong>Assistant</strong></summary>");
  });

  it("handles OpenAI Agents output message with multiple assistant outputs", () => {
    const message = {
      output: [
        {
          role: "assistant",
          type: "message",
          content: [{ type: "output_text", text: "Assistant response 1" }],
        },
        { role: "user", content: "User message" },
        {
          role: "assistant",
          type: "message",
          content: [{ type: "output_text", text: "Assistant response 2" }],
        },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: expect.stringContaining("<details open>"),
      prettified: true,
      renderType: "text",
    });
    expect(result.message).toContain("Assistant response 1");
    expect(result.message).toContain("User message");
    expect(result.message).toContain("Assistant response 2");
    expect(result.message).toContain("<summary><strong>Assistant</strong></summary>");
    expect(result.message).toContain("<summary><strong>User</strong></summary>");
  });

  it("maintains backward compatibility for single message arrays", () => {
    const message = { messages: [{ content: "Single response" }] };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Single response",
      prettified: true,
      renderType: "text",
    });
  });

  it("displays single LangGraph message without collapsible sections", () => {
    const message = {
      messages: [{ type: "ai", content: "Single AI response" }],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "Single AI response",
      prettified: true,
      renderType: "text",
    });
  });

  it("displays tool call messages collapsed by default", () => {
    const message = {
      messages: [
        { role: "user", content: "Use the calculator" },
        { role: "tool", content: "Calculator result: 42" },
      ],
    };
    const result = prettifyMessage(message);
    // Check that user message is expanded
    expect(result.message).toContain("<details open>");
    expect(result.message).toContain("<summary><strong>User</strong></summary>");
    // Check that tool message is collapsed (no 'open' attribute)
    expect(result.message).toMatch(/<details>\s*<summary><strong>Tool<\/strong><\/summary>/);
    expect(result.message).toContain("Calculator result: 42");
  });

  it("displays tool_execution_result messages collapsed by default", () => {
    const message = {
      messages: [
        { role: "user", content: "Use the calculator" },
        { role: "tool_execution_result", content: "Calculation completed" },
      ],
    };
    const result = prettifyMessage(message);
    // Check that user message is expanded
    expect(result.message).toContain("<details open>");
    expect(result.message).toContain("<summary><strong>User</strong></summary>");
    // Check that tool_execution_result message is collapsed (no 'open' attribute)
    expect(result.message).toMatch(/<details>\s*<summary><strong>Tool_execution_result<\/strong><\/summary>/);
    expect(result.message).toContain("Calculation completed");
  });

  it("handles Demo project blocks structure with text content", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "text",
          text: "Opik is a tool that has been specifically designed to support high volumes of traces, making it suitable for monitoring production applications, particularly LLM applications.",
        },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message:
        "Opik is a tool that has been specifically designed to support high volumes of traces, making it suitable for monitoring production applications, particularly LLM applications.",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles Demo project blocks structure with multiple text blocks", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "text",
          text: "First paragraph content.",
        },
        {
          block_type: "text",
          text: "Second paragraph content.",
        },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "First paragraph content.\n\nSecond paragraph content.",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles Demo project blocks structure with mixed block types, extracting only text blocks", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "image",
          url: "https://example.com/image.jpg",
        },
        {
          block_type: "text",
          text: "This is the text content.",
        },
        {
          block_type: "code",
          language: "python",
          code: "print('hello')",
        },
      ],
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: "This is the text content.",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles Demo project nested blocks structure under output property", () => {
    const message = {
      output: {
        role: "assistant",
        blocks: [
          {
            block_type: "text",
            text: "Opik's morning routine before diving into LLM evaluations involves logging, viewing, and evaluating LLM traces using the Opik platform and LLM as a Judge evaluators. This allows for the identification and fixing of issues in the LLM application.",
          },
        ],
      },
    };
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message:
        "Opik's morning routine before diving into LLM evaluations involves logging, viewing, and evaluating LLM traces using the Opik platform and LLM as a Judge evaluators. This allows for the identification and fixing of issues in the LLM application.",
      prettified: true,
      renderType: "text",
    });
  });

  it("handles tool call with array results and extracts correct tool name", () => {
    const message = [
      {
        role: "system",
        content:
          "Answer the question with a direct phrase. Use the tool `search_wikipedia` if you need it.",
      },
      {
        role: "user",
        content:
          "What magazine was established in 1988 by Frank Thomas and Keith White?",
      },
      {
        content: null,
        role: "assistant",
        tool_calls: [
          {
            function: {
              arguments:
                '{"query":"magazine established in 1988 by Frank Thomas and Keith White"}',
              name: "search_wikipedia",
            },
            id: "call_axmw0UjMugEay25lGwIlz1uV",
            type: "function",
          },
        ],
      },
      {
        role: "tool",
        tool_call_id: "call_axmw0UjMugEay25lGwIlz1uV",
        content:
          "['The Baffler | The Baffler is a magazine of cultural, political, and business analysis. Established in 1988 by editors Thomas Frank and Keith White, it was headquartered in Chicago, Illinois until 2010, when it moved to Cambridge, Massachusetts. In 2016, it moved its headquarters to New York City. The first incarnation of \"The Baffler\" had up to 12,000 subscribers.', 'Movmnt | movmnt magazine is an urban-leaning lifestyle magazine which was co-founded in 2006 by David Benaym and Danny Tidwell. The magazine has featured columns by Mario Spinetti, Mia Michaels, Robert Battle, Debbie Allen, Alisan Porter, Rasta Thomas, and Frank Conway. Both Travis Wall and Ivan Koumaev have made guest contributions to the publication, which has published photographs by Gary Land, Dave Hill, James Archibald Houston, and Alison Jackson.', \"Fantasy Advertiser | Fantasy Advertiser, later abbreviated to FA, was a British fanzine which discussed comic books. The magazine was established in 1965. It was initially edited by Frank Dobson, essentially as an advertising service for comic collectors, and when Dobson emigrated to Australia in 1970 he handed it on to two contributors, Dez Skinn and Paul McCartney, to continue. Skinn and McCartney expanded the magazine to include more articles and artwork. Regular contributors included Dave Gibbons, Steve Parkhouse, Paul Neary, Jim Baikie and Kevin O'Neill. Skinn left in 1976.\"]",
      },
    ];
    const result = prettifyMessage(message);
    expect(result).toEqual({
      message: expect.stringContaining("**System**"),
      prettified: true,
      renderType: "text",
    });
  });

  describe("config parameter behavior", () => {
    describe("inputType parameter", () => {
      it("handles inputType 'array' with array message", () => {
        const message = [
          { content: "Array item 1" },
          { content: "Array item 2" },
        ];
        const result = prettifyMessage(message, { inputType: "array" });
        expect(result).toEqual({
          message: "Array item 1\n\nArray item 2",
          prettified: true,
          renderType: "text",
        });
      });

      it("handles inputType 'object' with object message", () => {
        const message = { question: "What is your name?" };
        const result = prettifyMessage(message, { inputType: "object" });
        expect(result).toEqual({
          message: "What is your name?",
          prettified: true,
          renderType: "text",
        });
      });

      it("handles non-standard inputType values with array message", () => {
        const message = [{ content: "Custom array item" }];
        const result = prettifyMessage(message, { inputType: "custom" });
        expect(result).toEqual({
          message: "Custom array item",
          prettified: true,
          renderType: "text",
        });
      });

      it("handles non-standard inputType values with object message", () => {
        const message = { query: "Custom object query" };
        const result = prettifyMessage(message, { inputType: "custom" });
        expect(result).toEqual({
          message: "Custom object query",
          prettified: true,
          renderType: "text",
        });
      });

      it("falls back to default behavior when inputType doesn't match message type", () => {
        const message = { question: "Object message" };
        const result = prettifyMessage(message, { inputType: "array" });
        // Should fall back to default object handling
        expect(result).toEqual({
          message: "Object message",
          prettified: true,
          renderType: "text",
        });
      });

      it("ignores empty string inputType", () => {
        const message = { question: "Test message" };
        const result = prettifyMessage(message, { inputType: "" });
        // Should fall back to default behavior
        expect(result).toEqual({
          message: "Test message",
          prettified: true,
          renderType: "text",
        });
      });

      it("ignores falsy inputType values", () => {
        const message = { question: "Test message" };
        const result = prettifyMessage(message, {
          inputType: null as unknown as string,
        });
        // Should fall back to default behavior
        expect(result).toEqual({
          message: "Test message",
          prettified: true,
          renderType: "text",
        });
      });
    });

    describe("outputType parameter", () => {
      it("overrides renderType with outputType for array input", () => {
        const message = [{ content: "Array item" }];
        const result = prettifyMessage(message, {
          inputType: "array",
          outputType: "json-table",
        });
        expect(result).toEqual({
          message: "Array item",
          prettified: true,
          renderType: "json-table",
        });
      });

      it("overrides renderType with outputType for object input", () => {
        const message = { question: "Object question" };
        const result = prettifyMessage(message, {
          inputType: "object",
          outputType: "json-table",
        });
        expect(result).toEqual({
          message: "Object question",
          prettified: true,
          renderType: "json-table",
        });
      });

      it("overrides renderType with outputType for default behavior", () => {
        const message = { question: "Default question" };
        const result = prettifyMessage(message, { outputType: "json-table" });
        expect(result).toEqual({
          message: "Default question",
          prettified: true,
          renderType: "json-table",
        });
      });

      it("preserves original renderType when outputType is not specified", () => {
        const message = { question: "Test question" };
        const result = prettifyMessage(message, { inputType: "object" });
        expect(result).toEqual({
          message: "Test question",
          prettified: true,
          renderType: "text",
        });
      });
    });

    describe("combined inputType and outputType", () => {
      it("handles both inputType and outputType together", () => {
        const message = [{ content: "Combined test" }];
        const result = prettifyMessage(message, {
          inputType: "array",
          outputType: "json-table",
        });
        expect(result).toEqual({
          message: "Combined test",
          prettified: true,
          renderType: "json-table",
        });
      });

      it("handles non-standard inputType with outputType", () => {
        const message = { query: "Non-standard test" };
        const result = prettifyMessage(message, {
          inputType: "custom",
          outputType: "json-table",
        });
        expect(result).toEqual({
          message: "Non-standard test",
          prettified: true,
          renderType: "json-table",
        });
      });
    });
  });
});
