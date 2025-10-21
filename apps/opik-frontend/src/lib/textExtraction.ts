import get from "lodash/get";
import isString from "lodash/isString";
import { shouldRenderAsJsonTable, parseJsonForTable } from "./jsonTableUtils";
import { ExtractTextResult } from "./types";

/**
 * Enhanced text extraction logic that dynamically checks the type and structure of the message.
 * This approach replaces complex framework-specific logic with a more general solution that supports multiple message formats.
 * Supported formats include: plain strings, objects with direct string fields, objects containing nested message or text fields,
 * and JSON structures. The function determines the best way to extract and prettify the message for rendering, either as text or a JSON table.
 *
 * Note: For conversation arrays (messages, choices), this function extracts the LAST message/choice by design.
 * This is the expected behavior for playground outputs where we want to show the final response.
 */
// Field priorities based on the old prettifyGenericLogic behavior
const INPUT_FIELD_PRIORITIES = [
  "question",
  "messages",
  "user_input",
  "query",
  "input_prompt",
  "prompt",
  "sys.query",
  "input",
  "output",
];

const OUTPUT_FIELD_PRIORITIES = ["answer", "output", "response", "input"];

export const extractTextFromObject = (
  obj: object,
  context?: "input" | "output",
): ExtractTextResult | string | undefined => {
  // Context-specific fields based on the old prettifyGenericLogic behavior
  const contextSpecificFields =
    context === "input"
      ? INPUT_FIELD_PRIORITIES
      : context === "output"
        ? OUTPUT_FIELD_PRIORITIES
        : INPUT_FIELD_PRIORITIES; // default to input behavior for backward compatibility

  // Common string fields that work for both input and output
  const commonTextFields = ["content", "text", "message"];

  // Combine fields with context-specific ones first
  const directTextFields = [...contextSpecificFields, ...commonTextFields];

  for (const field of directTextFields) {
    const value = (obj as Record<string, unknown>)[field];
    if (isString(value) && value.trim()) {
      return value;
    }
  }

  // Handle OpenAI format: { messages: [{ content: "..." }] }
  if (
    "messages" in obj &&
    Array.isArray((obj as Record<string, unknown>).messages)
  ) {
    const messages = (obj as Record<string, unknown>).messages as unknown[];
    const lastMessage = messages[messages.length - 1];
    if (
      lastMessage &&
      typeof lastMessage === "object" &&
      lastMessage !== null &&
      "content" in lastMessage
    ) {
      const messageContent = (lastMessage as Record<string, unknown>).content;
      if (typeof messageContent === "string" && messageContent.trim()) {
        return messageContent;
      }
      // Handle array content format
      if (Array.isArray(messageContent)) {
        const textContent = messageContent.find(
          (c: unknown) =>
            typeof c === "object" &&
            c !== null &&
            "type" in c &&
            (c as Record<string, unknown>).type === "text",
        );
        if (
          textContent &&
          typeof textContent === "object" &&
          textContent !== null &&
          "text" in textContent &&
          typeof (textContent as Record<string, unknown>).text === "string" &&
          ((textContent as Record<string, unknown>).text as string).trim()
        ) {
          return (textContent as Record<string, unknown>).text as string;
        }
      }
    }
  }

  // Handle assistant messages with tool_calls (skip these as they don't contain displayable content)
  if (
    "role" in obj &&
    (obj as Record<string, unknown>).role === "assistant" &&
    "tool_calls" in obj &&
    "content" in obj &&
    (obj as Record<string, unknown>).content === null
  ) {
    // Skip assistant messages with tool_calls that have null content
    // These are just function call requests and don't contain displayable text
    return undefined;
  }

  // Handle LangGraph format: { messages: [{ type: "ai", content: "..." }] }
  if (
    "messages" in obj &&
    Array.isArray((obj as Record<string, unknown>).messages)
  ) {
    const messages = (obj as Record<string, unknown>).messages as unknown[];

    // Get the last AI message for output (prioritize AI messages)
    const aiMessages: string[] = [];

    for (const m of messages) {
      if (
        typeof m === "object" &&
        m !== null &&
        "type" in (m as Record<string, unknown>) &&
        (m as Record<string, unknown>).type === "ai" &&
        "content" in (m as Record<string, unknown>)
      ) {
        const messageContent = (m as Record<string, unknown>).content;

        // The message can either contain a string attribute named `content`
        if (typeof messageContent === "string" && messageContent.trim()) {
          aiMessages.push(messageContent);
        }
        // Or content can be an array with text content (e.g., OpenAI Responses API)
        else if (Array.isArray(messageContent)) {
          const textItems = messageContent.filter(
            (c: unknown) =>
              typeof c === "object" &&
              c !== null &&
              "type" in (c as Record<string, unknown>) &&
              (c as Record<string, unknown>).type === "text" &&
              "text" in (c as Record<string, unknown>) &&
              typeof (c as Record<string, unknown>).text === "string" &&
              ((c as Record<string, unknown>).text as string).trim() !== "",
          );

          // Check that there is only one text item
          if (textItems.length === 1) {
            aiMessages.push(
              (textItems[0] as Record<string, unknown>).text as string,
            );
          }
        }
      }
    }

    if (aiMessages.length > 0) {
      return aiMessages[aiMessages.length - 1];
    }

    // Fallback: Find the first human message for input if no AI messages
    const humanMessages = messages.filter(
      (m: unknown) =>
        typeof m === "object" &&
        m !== null &&
        "type" in (m as Record<string, unknown>) &&
        (m as Record<string, unknown>).type === "human" &&
        "content" in (m as Record<string, unknown>) &&
        typeof (m as Record<string, unknown>).content === "string" &&
        ((m as Record<string, unknown>).content as string).trim() !== "",
    );

    if (humanMessages.length > 0) {
      return (humanMessages[0] as Record<string, unknown>).content as string;
    }
  }

  // Handle OpenAI choices format: { choices: [{ message: { content: "..." } }] }
  if (
    "choices" in obj &&
    Array.isArray((obj as Record<string, unknown>).choices)
  ) {
    const choices = (obj as Record<string, unknown>).choices as unknown[];
    const lastChoice = choices[choices.length - 1];
    if (
      lastChoice &&
      typeof lastChoice === "object" &&
      lastChoice !== null &&
      "message" in lastChoice
    ) {
      const message = (lastChoice as Record<string, unknown>).message;
      if (
        typeof message === "object" &&
        message !== null &&
        "content" in message &&
        typeof (message as Record<string, unknown>).content === "string" &&
        ((message as Record<string, unknown>).content as string).trim()
      ) {
        const content = (
          (message as Record<string, unknown>).content as string
        ).trim();

        // Check if content is JSON serializable
        if (shouldRenderAsJsonTable(content)) {
          const parsed = parseJsonForTable(content);
          if (parsed !== null) {
            // Return a structured object that indicates this should be rendered as a JSON table
            return {
              renderType: "json-table",
              data: parsed,
            };
          }
        }

        // Check if content is a JSON string that should be parsed
        if (
          content.startsWith("[") &&
          (content.includes("'role'") || content.includes('"role"')) &&
          (content.includes("'content'") || content.includes('"content"'))
        ) {
          // Try to parse the content as JSON first
          try {
            const parsedContent = JSON.parse(content);
            if (Array.isArray(parsedContent)) {
              // If it's a valid JSON array, return it as structured data
              return {
                renderType: "json-table",
                data: parsedContent,
              };
            }
          } catch {
            // If JSON parsing fails, try to parse as JavaScript array literal
            try {
              // Handle multiple arrays separated by commas (JavaScript array literal format)
              const arrayLiteral = `[${content}]`;
              const parsedContent = JSON.parse(arrayLiteral);
              if (Array.isArray(parsedContent)) {
                return {
                  renderType: "json-table",
                  data: parsedContent,
                };
              }
            } catch {
              // If both JSON and array literal parsing fail, fall back to rendering the entire object as a JSON table
              return {
                renderType: "json-table",
                data: obj,
              };
            }
          }
        }

        return content;
      }
    }

    // If we have choices but can't extract meaningful text content,
    // fall back to rendering the entire object as a JSON table
    return {
      renderType: "json-table",
      data: obj,
    };
  }

  // Handle LangChain format: { generations: [[{ text: "..." }]] }
  if (
    "generations" in obj &&
    Array.isArray((obj as Record<string, unknown>).generations)
  ) {
    const generations = (obj as Record<string, unknown>)
      .generations as unknown[];
    if (generations.length === 1 && Array.isArray(generations[0])) {
      const generation = generations[0] as unknown[];
      const lastGen = generation[generation.length - 1];
      if (
        lastGen &&
        typeof lastGen === "object" &&
        lastGen !== null &&
        "text" in lastGen &&
        typeof (lastGen as Record<string, unknown>).text === "string" &&
        ((lastGen as Record<string, unknown>).text as string).trim()
      ) {
        return (lastGen as Record<string, unknown>).text as string;
      }
    }
  }

  // Handle ADK format: { parts: [{ text: "..." }] }
  if ("parts" in obj && Array.isArray((obj as Record<string, unknown>).parts)) {
    const parts = (obj as Record<string, unknown>).parts as unknown[];
    const lastPart = parts[parts.length - 1];
    if (
      lastPart &&
      typeof lastPart === "object" &&
      lastPart !== null &&
      "text" in lastPart &&
      typeof (lastPart as Record<string, unknown>).text === "string" &&
      ((lastPart as Record<string, unknown>).text as string).trim()
    ) {
      return (lastPart as Record<string, unknown>).text as string;
    }
  }

  // Handle ADK spans format: { contents: [{ parts: [{ text: "..." }] }] }
  if (
    "contents" in obj &&
    Array.isArray((obj as Record<string, unknown>).contents)
  ) {
    const contents = (obj as Record<string, unknown>).contents as unknown[];
    const lastContent = contents[contents.length - 1];
    if (
      lastContent &&
      typeof lastContent === "object" &&
      lastContent !== null &&
      "parts" in lastContent &&
      Array.isArray((lastContent as Record<string, unknown>).parts)
    ) {
      const parts = (lastContent as Record<string, unknown>).parts as unknown[];
      const lastPart = parts[parts.length - 1];
      if (
        lastPart &&
        typeof lastPart === "object" &&
        lastPart !== null &&
        "text" in lastPart &&
        typeof (lastPart as Record<string, unknown>).text === "string" &&
        ((lastPart as Record<string, unknown>).text as string).trim()
      ) {
        return (lastPart as Record<string, unknown>).text as string;
      }
    }
  }

  // Handle ADK output format: { content: { parts: [{ text: "..." }] } }
  if (
    "content" in obj &&
    typeof (obj as Record<string, unknown>).content === "object" &&
    (obj as Record<string, unknown>).content !== null
  ) {
    const content = (obj as Record<string, unknown>).content as Record<
      string,
      unknown
    >;
    if ("parts" in content && Array.isArray(content.parts)) {
      const parts = content.parts as unknown[];
      const lastPart = parts[parts.length - 1];
      if (
        lastPart &&
        typeof lastPart === "object" &&
        lastPart !== null &&
        "text" in lastPart &&
        typeof (lastPart as Record<string, unknown>).text === "string" &&
        ((lastPart as Record<string, unknown>).text as string).trim()
      ) {
        return (lastPart as Record<string, unknown>).text as string;
      }
    }
  }

  // Handle Demo project blocks format: { blocks: [{ block_type: "text", text: "..." }] }
  if (
    "blocks" in obj &&
    Array.isArray((obj as Record<string, unknown>).blocks)
  ) {
    const blocks = (obj as Record<string, unknown>).blocks as unknown[];
    const textBlocks = blocks.filter(
      (block: unknown) =>
        typeof block === "object" &&
        block !== null &&
        "block_type" in (block as Record<string, unknown>) &&
        (block as Record<string, unknown>).block_type === "text" &&
        "text" in (block as Record<string, unknown>) &&
        typeof (block as Record<string, unknown>).text === "string" &&
        ((block as Record<string, unknown>).text as string).trim() !== "",
    );

    if (textBlocks.length > 0) {
      return textBlocks
        .map(
          (block: unknown) => (block as Record<string, unknown>).text as string,
        )
        .join("\n\n");
    }
  }

  // Handle Demo project nested blocks: { output: { blocks: [...] } }
  if (
    "output" in obj &&
    typeof (obj as Record<string, unknown>).output === "object" &&
    (obj as Record<string, unknown>).output !== null
  ) {
    const output = (obj as Record<string, unknown>).output as Record<
      string,
      unknown
    >;
    if ("blocks" in output && Array.isArray(output.blocks)) {
      const blocks = output.blocks as unknown[];
      const textBlocks = blocks.filter(
        (block: unknown) =>
          typeof block === "object" &&
          block !== null &&
          "block_type" in (block as Record<string, unknown>) &&
          (block as Record<string, unknown>).block_type === "text" &&
          "text" in (block as Record<string, unknown>) &&
          typeof (block as Record<string, unknown>).text === "string" &&
          ((block as Record<string, unknown>).text as string).trim() !== "",
      );

      if (textBlocks.length > 0) {
        return textBlocks
          .map(
            (block: unknown) =>
              (block as Record<string, unknown>).text as string,
          )
          .join("\n\n");
      }
    }
  }

  // Handle OpenAI Agents input format: { input: [{ role: "user", content: "..." }] }
  if ("input" in obj && Array.isArray((obj as Record<string, unknown>).input)) {
    const input = (obj as Record<string, unknown>).input as unknown[];
    const userMessages = input.filter(
      (m: unknown) =>
        typeof m === "object" &&
        m !== null &&
        "role" in (m as Record<string, unknown>) &&
        (m as Record<string, unknown>).role === "user" &&
        "content" in (m as Record<string, unknown>) &&
        typeof (m as Record<string, unknown>).content === "string" &&
        ((m as Record<string, unknown>).content as string).trim() !== "",
    );

    if (userMessages.length > 0) {
      return userMessages
        .map((m: unknown) => (m as Record<string, unknown>).content as string)
        .join("\n\n  ----------------- \n\n");
    }
  }

  // Handle OpenAI Agents output format: { output: [{ role: "assistant", content: [...] }] }
  if (
    "output" in obj &&
    Array.isArray((obj as Record<string, unknown>).output)
  ) {
    const output = (obj as Record<string, unknown>).output as unknown[];
    const assistantMessages = output.filter(
      (m: unknown) =>
        typeof m === "object" &&
        m !== null &&
        "role" in (m as Record<string, unknown>) &&
        (m as Record<string, unknown>).role === "assistant" &&
        "type" in (m as Record<string, unknown>) &&
        (m as Record<string, unknown>).type === "message" &&
        "content" in (m as Record<string, unknown>) &&
        Array.isArray((m as Record<string, unknown>).content),
    );

    const textMessages = assistantMessages.reduce(
      (acc: string[], m: unknown) => {
        const message = m as Record<string, unknown>;
        const content = message.content as unknown[];
        const textItems = content.filter(
          (c: unknown) =>
            typeof c === "object" &&
            c !== null &&
            "type" in (c as Record<string, unknown>) &&
            (c as Record<string, unknown>).type === "output_text" &&
            "text" in (c as Record<string, unknown>) &&
            typeof (c as Record<string, unknown>).text === "string" &&
            ((c as Record<string, unknown>).text as string).trim() !== "",
        );

        return acc.concat(
          textItems.map(
            (c: unknown) => (c as Record<string, unknown>).text as string,
          ),
        );
      },
      [],
    );

    if (textMessages.length > 0) {
      return textMessages.join("\n\n  ----------------- \n\n");
    }
  }

  // Handle nested objects with common patterns
  const nestedPaths = [
    "data.result.output",
    "data.content",
    "result.output",
    "output.content",
    "response.data",
    "data.text",
    "data.response",
  ];

  for (const path of nestedPaths) {
    const value = get(obj, path);
    if (typeof value === "string" && value.trim()) {
      return value;
    }
  }

  // Check if it's a single-key object with a string value
  const keys = Object.keys(obj);
  if (keys.length === 1) {
    const value = (obj as Record<string, unknown>)[keys[0]];
    if (typeof value === "string" && value.trim()) {
      return value;
    }
  }

  // General fallback: For any object that doesn't match specific patterns,
  // return a structured object that indicates it should be rendered as a JSON table
  // This makes the function more general and enables "Pretty" mode for most objects
  return {
    renderType: "json-table",
    data: obj,
  };
};
