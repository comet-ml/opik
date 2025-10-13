import isString from "lodash/isString";
import isObject from "lodash/isObject";
import { extractTextFromObject } from "./textExtraction";
import { extractTextFromArray } from "./arrayExtraction";
import {
  convertConversationToMarkdown,
  ConversationData,
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

  // Check for OpenAI conversation format
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
    )
  );
};
