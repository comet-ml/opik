import md5 from "md5";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
import { TAG_VARIANTS } from "@/components/ui/tag";
import { ExperimentItem } from "@/types/datasets";
import { TRACE_VISIBILITY_MODE } from "@/types/traces";

export const generateTagVariant = (label: string) => {
  const hash = md5(label);
  const index = parseInt(hash.slice(-8), 16);
  return TAG_VARIANTS[index % TAG_VARIANTS.length];
};

export const isObjectSpan = (object: object) =>
  Boolean(get(object, "trace_id", false));

export const isObjectThread = (object: object) =>
  Boolean(get(object, "thread_model_id", false)) ||
  Boolean(get(object, "first_message", false)) ||
  Boolean(get(object, "last_message", false));

export const isNumericFeedbackScoreValid = (
  { min, max }: { min: number; max: number },
  value?: number | "",
) => isNumber(value) && value >= min && value <= max;

export const traceExist = (item: ExperimentItem) =>
  item.output || item.input || item.feedback_scores;

export const traceVisible = (item: ExperimentItem) =>
  item.trace_visibility_mode === TRACE_VISIBILITY_MODE.default;

type PrettifyMessageResponse = {
  message: object | string | undefined;
  prettified: boolean;
  renderType?: "text" | "json-table";
};

type ExtractTextResult = {
  text?: string;
  renderType?: "json-table";
  data?: object;
};

/**
 * Enhanced text extraction logic that dynamically checks the type and structure of the message.
 * This approach replaces complex framework-specific logic with a more general solution that supports multiple message formats.
 * Supported formats include: plain strings, objects with direct string fields, objects containing nested message or text fields,
 * and JSON structures. The function determines the best way to extract and prettify the message for rendering, either as text or a JSON table.
 */
export const extractTextFromObject = (
  obj: object,
): ExtractTextResult | string | undefined => {
  // Direct string fields
  const directTextFields = [
    "content",
    "text",
    "message",
    "response",
    "answer",
    "output",
    "input",
    "query",
    "prompt",
    "question",
    "user_input",
  ];

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

        return content;
      }
    }
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

/**
 * Check if content should be rendered as a JSON table
 */
export const shouldRenderAsJsonTable = (content: string): boolean => {
  try {
    const parsed = JSON.parse(content);
    return typeof parsed === "object" && parsed !== null;
  } catch {
    return false;
  }
};

/**
 * Parse JSON content for table rendering
 */
export const parseJsonForTable = (content: string): unknown | null => {
  try {
    const parsed = JSON.parse(content);
    if (typeof parsed === "object" && parsed !== null) {
      return parsed;
    }
  } catch {
    // Ignore parsing errors
  }
  return null;
};

/**
 * Format structured JSON data in a readable way
 */
const formatStructuredData = (data: unknown): string => {
  if (
    typeof data === "object" &&
    data !== null &&
    "message_list" in (data as Record<string, unknown>) &&
    Array.isArray((data as Record<string, unknown>).message_list)
  ) {
    // Handle message_list + examples format
    const parts: string[] = [];
    const dataObj = data as Record<string, unknown>;

    // Format the message_list as a nested conversation
    if ((dataObj.message_list as unknown[]).length > 0) {
      parts.push("**Message Template:**");
      (dataObj.message_list as unknown[]).forEach((msg: unknown) => {
        if (
          typeof msg === "object" &&
          msg !== null &&
          "role" in (msg as Record<string, unknown>) &&
          "content" in (msg as Record<string, unknown>)
        ) {
          const msgObj = msg as Record<string, unknown>;
          const role = msgObj.role as string;
          const content = msgObj.content as string;
          const roleHeader = role.charAt(0).toUpperCase() + role.slice(1);
          parts.push(`  **${roleHeader}**: ${content}`);
        }
      });
    }

    // Format examples if present
    if (
      "examples" in dataObj &&
      Array.isArray(dataObj.examples) &&
      (dataObj.examples as unknown[]).length > 0
    ) {
      parts.push("\n**Examples:**");
      (dataObj.examples as unknown[]).forEach(
        (example: unknown, index: number) => {
          if (
            typeof example === "object" &&
            example !== null &&
            "question" in (example as Record<string, unknown>) &&
            "answer" in (example as Record<string, unknown>)
          ) {
            const exampleObj = example as Record<string, unknown>;
            const question = exampleObj.question as string;
            const answer = exampleObj.answer as string;
            parts.push(`  ${index + 1}. **Q:** ${question}`);
            parts.push(`     **A:** ${answer}`);
          }
        },
      );
    }

    return parts.join("\n");
  }

  // Fallback to JSON string for other structured data
  return JSON.stringify(data, null, 2);
};

/**
 * Extract text from an array of objects by processing each object
 */
const extractTextFromArray = (arr: unknown[]): string | undefined => {
  const textItems: string[] = [];

  // First pass: collect tool names from assistant messages with tool_calls
  const toolNames: { [toolCallId: string]: string } = {};
  for (const item of arr) {
    if (
      isObject(item) &&
      "role" in item &&
      (item as Record<string, unknown>).role === "assistant" &&
      "tool_calls" in item
    ) {
      const toolCalls = (item as Record<string, unknown>).tool_calls;
      if (Array.isArray(toolCalls)) {
        for (const toolCall of toolCalls) {
          if (
            typeof toolCall === "object" &&
            toolCall !== null &&
            "id" in (toolCall as Record<string, unknown>) &&
            "function" in (toolCall as Record<string, unknown>)
          ) {
            const toolCallObj = toolCall as Record<string, unknown>;
            const functionObj = toolCallObj.function as Record<string, unknown>;
            if (
              toolCallObj.id &&
              functionObj &&
              "name" in functionObj &&
              functionObj.name
            ) {
              toolNames[toolCallObj.id as string] = functionObj.name as string;
            }
          }
        }
      }
    }
  }

  for (const item of arr) {
    if (typeof item === "string" && item.trim()) {
      textItems.push(item);
    } else if (isObject(item)) {
      // Check if this is a role-based message (like chat messages)
      if (
        "role" in item &&
        "content" in item &&
        typeof (item as Record<string, unknown>).role === "string" &&
        typeof (item as Record<string, unknown>).content === "string"
      ) {
        const role = (item as Record<string, unknown>).role as string;
        const content = (item as Record<string, unknown>).content as string;
        if (content.trim()) {
          // Check if content is JSON
          if (content.trim().startsWith("{") && content.trim().endsWith("}")) {
            try {
              const parsed = JSON.parse(content);
              const formatted = formatStructuredData(parsed);
              // For JSON content, don't show role header - just show the formatted data
              textItems.push(formatted);
            } catch {
              // If JSON parsing fails, treat as regular content
              const roleHeader = role.charAt(0).toUpperCase() + role.slice(1);
              textItems.push(`**${roleHeader}**:\n${content}`);
            }
          } else {
            // Check if content looks like a JavaScript array representation
            const trimmedContent = content.trim();
            if (
              trimmedContent.startsWith("[") &&
              trimmedContent.endsWith("]")
            ) {
              try {
                // Try to parse as JSON first (handles double quotes)
                const parsedArray = JSON.parse(trimmedContent);
                if (Array.isArray(parsedArray)) {
                  // Convert array to ordered list with new format
                  const arrayContent = parsedArray
                    .map((item, index) => `${index + 1}. ${item}`)
                    .join("\n");

                  // Extract tool name from the message structure
                  let toolName = "unknown_tool";
                  if (role === "tool" && "tool_call_id" in item) {
                    const toolCallId = (item as Record<string, unknown>)
                      .tool_call_id;
                    if (toolCallId && toolNames[toolCallId as string]) {
                      toolName = toolNames[toolCallId as string];
                    }
                  }

                  textItems.push(
                    `**Tool call: ${toolName}**\n*Results*:\n${arrayContent}`,
                  );
                } else {
                  // Not an array, treat as regular content
                  let roleHeader = role.charAt(0).toUpperCase() + role.slice(1);

                  // Special handling for tool role - extract tool name
                  if (role === "tool" && "tool_call_id" in item) {
                    const toolCallId = (item as Record<string, unknown>)
                      .tool_call_id;
                    if (toolCallId && toolNames[toolCallId as string]) {
                      roleHeader = `Tool call: ${
                        toolNames[toolCallId as string]
                      }`;
                    }
                  }

                  textItems.push(`**${roleHeader}**:\n${content}`);
                }
              } catch {
                // If JSON parsing fails, try to safely parse as JavaScript array (handles single quotes)
                try {
                  // Safe parsing: convert single quotes to double quotes and parse as JSON
                  const safeContent = trimmedContent
                    .replace(/'/g, '"') // Replace single quotes with double quotes
                    .replace(/(\w+):/g, '"$1":'); // Add quotes around object keys if any

                  const parsedArray = JSON.parse(safeContent);
                  if (Array.isArray(parsedArray)) {
                    // Convert array to ordered list with new format
                    const arrayContent = parsedArray
                      .map((item, index) => `${index + 1}. ${item}`)
                      .join("\n");

                    // Extract tool name from the message structure
                    let toolName = "unknown_tool";
                    if (role === "tool" && "tool_call_id" in item) {
                      const toolCallId = (item as Record<string, unknown>)
                        .tool_call_id;
                      if (toolCallId && toolNames[toolCallId as string]) {
                        toolName = toolNames[toolCallId as string];
                      }
                    }

                    textItems.push(
                      `**Tool call: ${toolName}**\n*Results*:\n${arrayContent}`,
                    );
                  } else {
                    // Not an array, treat as regular content
                    let roleHeader =
                      role.charAt(0).toUpperCase() + role.slice(1);

                    // Special handling for tool role - extract tool name
                    if (role === "tool" && "tool_call_id" in item) {
                      const toolCallId = (item as Record<string, unknown>)
                        .tool_call_id;
                      if (toolCallId && toolNames[toolCallId as string]) {
                        roleHeader = `Tool call: ${
                          toolNames[toolCallId as string]
                        }`;
                      }
                    }

                    textItems.push(`**${roleHeader}**:\n${content}`);
                  }
                } catch {
                  // If both parsing attempts fail, treat as regular content
                  let roleHeader = role.charAt(0).toUpperCase() + role.slice(1);

                  // Special handling for tool role - extract tool name
                  if (role === "tool" && "tool_call_id" in item) {
                    const toolCallId = (item as Record<string, unknown>)
                      .tool_call_id;
                    if (toolCallId && toolNames[toolCallId as string]) {
                      roleHeader = `Tool call: ${
                        toolNames[toolCallId as string]
                      }`;
                    }
                  }

                  textItems.push(`**${roleHeader}**:\n${content}`);
                }
              }
            } else {
              // Format with role header for non-JSON content
              let roleHeader = role.charAt(0).toUpperCase() + role.slice(1);

              // Special handling for tool role - extract tool name
              if (role === "tool" && "tool_call_id" in item) {
                const toolCallId = (item as Record<string, unknown>)
                  .tool_call_id;
                if (toolCallId && toolNames[toolCallId as string]) {
                  roleHeader = `Tool call: ${toolNames[toolCallId as string]}`;
                }
              }

              textItems.push(`**${roleHeader}**:\n${content}`);
            }
          }
        }
      } else {
        // Use regular object extraction
        const extracted = extractTextFromObject(item);
        if (extracted) {
          // Handle structured result
          if (typeof extracted === "object" && "renderType" in extracted) {
            // For arrays, we'll convert structured results to text representation
            textItems.push(JSON.stringify(extracted.data, null, 2));
          } else if (typeof extracted === "string") {
            // Handle string result
            textItems.push(extracted);
          }
        }
      }
    }
  }

  return textItems.length > 0 ? textItems.join("\n\n") : undefined;
};

export const prettifyMessage = (message: object | string | undefined) => {
  if (isString(message)) {
    return {
      message,
      prettified: false,
    } as PrettifyMessageResponse;
  }

  try {
    let extractedResult: string | ExtractTextResult | undefined;

    // Handle arrays of objects/strings
    if (Array.isArray(message)) {
      extractedResult = extractTextFromArray(message);
    } else if (isObject(message)) {
      extractedResult = extractTextFromObject(message);
    }

    // If we can extract text or have a structured result, use it
    if (extractedResult) {
      // Handle structured result
      if (
        typeof extractedResult === "object" &&
        "renderType" in extractedResult
      ) {
        return {
          message: extractedResult.data,
          prettified: true,
          renderType: extractedResult.renderType,
        } as PrettifyMessageResponse;
      }

      // Handle string result
      return {
        message: extractedResult,
        prettified: true,
        renderType: "text",
      } as PrettifyMessageResponse;
    }

    // If we can't extract text, return the original message as not prettified
    if (isObject(message)) {
      return {
        message: message,
        prettified: false,
      } as PrettifyMessageResponse;
    }

    return {
      message: message,
      prettified: false,
    } as PrettifyMessageResponse;
  } catch (error) {
    return {
      message,
      prettified: false,
    } as PrettifyMessageResponse;
  }
};
