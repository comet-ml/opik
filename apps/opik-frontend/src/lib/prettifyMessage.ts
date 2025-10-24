import isString from "lodash/isString";
import isObject from "lodash/isObject";
import { extractTextFromObject } from "./textExtraction";
import { extractTextFromArray } from "./arrayExtraction";
import {
  convertConversationToMarkdown,
  ConversationData,
  convertLLMMessagesToMarkdown,
  LLMMessage,
  ToolCall,
} from "./conversationMarkdown";
import { PrettifyMessageResponse, ExtractTextResult } from "./types";

export const prettifyMessage = (
  message: object | string | undefined,
  config?: { inputType?: string; outputType?: string },
) => {
  // Check if this is a conversation JSON object that should be converted to markdown
  if (isObject(message) && isConversationObject(message)) {
    const markdown = convertConversationToMarkdown(message as ConversationData);
    return {
      message: markdown,
      prettified: true,
      renderType: "text",
    } as PrettifyMessageResponse;
  }

  // Check if this is an object containing an array of LLM messages
  if (isObject(message) && isLLMMessagesObject(message)) {
    const messagesArray = extractLLMMessagesArray(message);
    if (messagesArray && messagesArray.length > 0) {
      const markdown = convertLLMMessagesToMarkdown(messagesArray);
      return {
        message: markdown,
        prettified: true,
        renderType: "text",
      } as PrettifyMessageResponse;
    }
  }

  // If config is provided, use it for type-specific prettification
  if (config && config.inputType) {
    // Handle array input type
    if (config.inputType === "array" && Array.isArray(message)) {
      const extractedResult = extractTextFromArray(message);
      if (extractedResult) {
        const renderType = config.outputType || "text";
        return {
          message: extractedResult,
          prettified: true,
          renderType,
        } as PrettifyMessageResponse;
      }
    }
    // Handle object input type
    else if (config.inputType === "object" && isObject(message)) {
      const extractedResult = extractTextFromObject(message);
      if (extractedResult) {
        if (
          typeof extractedResult === "object" &&
          "renderType" in extractedResult
        ) {
          const renderType = config.outputType || extractedResult.renderType;
          return {
            message: extractedResult.data,
            prettified: true,
            renderType,
          } as PrettifyMessageResponse;
        }
        const renderType = config.outputType || "text";
        return {
          message: extractedResult,
          prettified: true,
          renderType,
        } as PrettifyMessageResponse;
      }
    }
    // Handle other inputType values - treat as explicit type hint
    else if (config.inputType !== "array" && config.inputType !== "object") {
      // For non-standard inputType values, still attempt extraction but with type awareness
      if (Array.isArray(message)) {
        const extractedResult = extractTextFromArray(message);
        if (extractedResult) {
          const renderType = config.outputType || "text";
          return {
            message: extractedResult,
            prettified: true,
            renderType,
          } as PrettifyMessageResponse;
        }
      } else if (isObject(message)) {
        const extractedResult = extractTextFromObject(message);
        if (extractedResult) {
          if (
            typeof extractedResult === "object" &&
            "renderType" in extractedResult
          ) {
            const renderType = config.outputType || extractedResult.renderType;
            return {
              message: extractedResult.data,
              prettified: true,
              renderType,
            } as PrettifyMessageResponse;
          }
          const renderType = config.outputType || "text";
          return {
            message: extractedResult,
            prettified: true,
            renderType,
          } as PrettifyMessageResponse;
        }
      }
    }
    // If inputType doesn't match message type, fall through to default behavior
  }
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
        // Apply outputType override if specified
        const renderType = config?.outputType || extractedResult.renderType;
        return {
          message: extractedResult.data,
          prettified: true,
          renderType,
        } as PrettifyMessageResponse;
      }

      // Handle string result - apply outputType override if specified
      const renderType = config?.outputType || "text";
      return {
        message: extractedResult,
        prettified: true,
        renderType,
      } as PrettifyMessageResponse;
    }

    // If we can't extract text, return the original message as not prettified
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

/**
 * Checks if an object is a conversation JSON (OpenAI format)
 * @param obj - The object to check
 * @returns True if it's a conversation object
 */
const isConversationObject = (obj: object): boolean => {
  if (!isObject(obj)) return false;

  const objRecord = obj as Record<string, unknown>;

  // Check for OpenAI conversation format (with or without model field)
  // This should only match full conversation objects with model, tools, or kwargs
  // to distinguish from simple message arrays
  return (
    "messages" in objRecord &&
    Array.isArray(objRecord.messages) &&
    objRecord.messages.length > 0 &&
    // Check if messages have the expected structure
    objRecord.messages.every(
      (msg: unknown) =>
        typeof msg === "object" &&
        msg !== null &&
        "role" in (msg as Record<string, unknown>),
    ) &&
    // Must have additional conversation-specific fields to distinguish from simple message arrays
    ("model" in objRecord || "tools" in objRecord || "kwargs" in objRecord)
  );
};

/**
 * Checks if an object contains an array of LLM messages that should be displayed in pretty mode
 * @param obj - The object to check
 * @returns True if it contains LLM messages array
 */
const isLLMMessagesObject = (obj: object): boolean => {
  if (!isObject(obj)) return false;

  const objRecord = obj as Record<string, unknown>;

  // Check for messages array with LLM format
  if ("messages" in objRecord && Array.isArray(objRecord.messages)) {
    return objRecord.messages.length > 1 && // Only use pretty mode for multiple messages
      objRecord.messages.every((msg: unknown) =>
        typeof msg === "object" &&
        msg !== null &&
        (("role" in (msg as Record<string, unknown>)) || 
         ("type" in (msg as Record<string, unknown>))) &&
        "content" in (msg as Record<string, unknown>)
      );
  }

  // Check for input array (OpenAI Agents format)
  if ("input" in objRecord && Array.isArray(objRecord.input)) {
    return objRecord.input.length > 1 && // Only use pretty mode for multiple messages
      objRecord.input.every((msg: unknown) =>
        typeof msg === "object" &&
        msg !== null &&
        "role" in (msg as Record<string, unknown>) &&
        "content" in (msg as Record<string, unknown>)
      );
  }

  // Check for output array (OpenAI Agents format)
  if ("output" in objRecord && Array.isArray(objRecord.output)) {
    return objRecord.output.length > 1 && // Only use pretty mode for multiple messages
      objRecord.output.every((msg: unknown) =>
        typeof msg === "object" &&
        msg !== null &&
        "role" in (msg as Record<string, unknown>) &&
        ("content" in (msg as Record<string, unknown>) || 
         "type" in (msg as Record<string, unknown>))
      );
  }

  return false;
};

/**
 * Extracts LLM messages array from various object formats
 * @param obj - The object containing messages
 * @returns Array of LLM messages or null if not found
 */
const extractLLMMessagesArray = (obj: object): LLMMessage[] | null => {
  if (!isObject(obj)) return null;

  const objRecord = obj as Record<string, unknown>;

  // Check for messages array
  if ("messages" in objRecord && Array.isArray(objRecord.messages)) {
    return objRecord.messages.map((msg: unknown) => {
      const msgRecord = msg as Record<string, unknown>;
      return {
        role: msgRecord.role as string,
        type: msgRecord.type as string,
        content: msgRecord.content as string | unknown[],
        tool_calls: isValidToolCallsArray(msgRecord.tool_calls) ? msgRecord.tool_calls : undefined,
        tool_call_id: msgRecord.tool_call_id as string,
      } as LLMMessage;
    });
  }

  // Check for input array (OpenAI Agents format)
  if ("input" in objRecord && Array.isArray(objRecord.input)) {
    return objRecord.input.map((msg: unknown) => {
      const msgRecord = msg as Record<string, unknown>;
      return {
        role: msgRecord.role as string,
        content: msgRecord.content as string | unknown[],
      } as LLMMessage;
    });
  }

  // Check for output array (OpenAI Agents format)
  if ("output" in objRecord && Array.isArray(objRecord.output)) {
    return objRecord.output.map((msg: unknown) => {
      const msgRecord = msg as Record<string, unknown>;
      return {
        role: msgRecord.role as string,
        type: msgRecord.type as string,
        content: msgRecord.content as string | unknown[],
      } as LLMMessage;
    });
  }

  return null;
};

/**
 * Type guard to check if a value is a valid ToolCall array
 * @param value - The value to check
 * @returns True if value is a valid ToolCall array
 */
const isValidToolCallsArray = (value: unknown): value is ToolCall[] => {
  if (!Array.isArray(value)) return false;
  
  return value.every((item: unknown) => {
    if (typeof item !== "object" || item === null) return false;
    
    const toolCall = item as Record<string, unknown>;
    return (
      typeof toolCall.id === "string" &&
      toolCall.type === "function" &&
      typeof toolCall.function === "object" &&
      toolCall.function !== null &&
      typeof (toolCall.function as Record<string, unknown>).name === "string" &&
      typeof (toolCall.function as Record<string, unknown>).arguments === "string"
    );
  });
};
