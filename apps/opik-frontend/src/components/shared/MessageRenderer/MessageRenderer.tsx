import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";
import isArray from "lodash/isArray";

import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import JsonKeyValueTable from "@/components/shared/JsonKeyValueTable/JsonKeyValueTable";
import { cn, toString } from "@/lib/utils";

export interface MessageRendererProps {
  /**
   * The message data to render. Can be a string, object, null, or undefined.
   */
  message: unknown;

  /**
   * Optional className to apply to the container
   */
  className?: string;

  /**
   * Whether to attempt text extraction from objects before showing JSON view
   * @default true
   */
  attemptTextExtraction?: boolean;

  /**
   * Custom text extraction function. If provided, this will be used instead of the default logic.
   */
  customTextExtractor?: (message: unknown) => string | undefined;

  /**
   * Fallback content to show when message is null/undefined
   * @default "-"
   */
  fallbackContent?: React.ReactNode;
}

/**
 * A general-purpose message renderer that handles different data types:
 * - Arrays: Rendered as ordered lists (OL)
 * - Objects: Rendered as JSON using JsonView
 * - Strings: Rendered as Markdown
 * - Null/undefined: Shows fallback content
 *
 * Optionally attempts to extract text from objects before showing JSON view.
 */
const MessageRenderer: React.FC<MessageRendererProps> = ({
  message,
  className,
  attemptTextExtraction = true,
  customTextExtractor,
  fallbackContent = "-",
}) => {
  const renderedContent = useMemo(() => {
    // Handle null/undefined
    if (isNull(message) || isUndefined(message)) {
      return fallbackContent;
    }

    // Handle strings
    if (isString(message)) {
      // Check if the string looks like a JavaScript array representation
      const trimmedMessage = message.trim();
      if (trimmedMessage.startsWith("[") && trimmedMessage.endsWith("]")) {
        try {
          // Try to parse it as a JavaScript array
          const parsedArray = JSON.parse(trimmedMessage);
          if (isArray(parsedArray)) {
            // Convert to ordered list
            const arrayContent = parsedArray
              .map((item, index) => `${index + 1}. ${toString(item)}`)
              .join("\n");
            return <MarkdownPreview>{arrayContent}</MarkdownPreview>;
          }
        } catch {
          // If parsing fails, treat as regular string
        }
      }

      return <MarkdownPreview>{message}</MarkdownPreview>;
    }

    // Handle arrays - convert to ordered list
    if (isArray(message)) {
      const arrayContent = message
        .map((item, index) => `${index + 1}. ${toString(item)}`)
        .join("\n");
      return <MarkdownPreview>{arrayContent}</MarkdownPreview>;
    }

    // Handle objects
    if (isObject(message)) {
      // Try to extract text if enabled
      if (attemptTextExtraction) {
        const extractedText = customTextExtractor
          ? customTextExtractor(message)
          : extractTextFromObject(message);

        if (extractedText) {
          // Check if the extracted text is a JSON table marker
          if (
            typeof extractedText === "string" &&
            extractedText.startsWith("__JSON_TABLE__:")
          ) {
            try {
              const jsonData = JSON.parse(
                extractedText.substring("__JSON_TABLE__:".length),
              );
              return <JsonKeyValueTable data={jsonData} maxDepth={3} />;
            } catch {
              // If parsing fails, fall back to regular text display
              return <MarkdownPreview>{extractedText}</MarkdownPreview>;
            }
          }

          return <MarkdownPreview>{extractedText}</MarkdownPreview>;
        }
      }

      // Fall back to JSON table for objects that can't be prettified as text
      return <JsonKeyValueTable data={message} maxDepth={3} />;
    }

    // Handle other types (numbers, booleans, etc.)
    return <MarkdownPreview>{toString(String(message))}</MarkdownPreview>;
  }, [message, attemptTextExtraction, customTextExtractor, fallbackContent]);

  return (
    <div className={cn("message-renderer", className)}>{renderedContent}</div>
  );
};

/**
 * Simple text extraction logic that looks for common text fields in objects.
 * This is a simplified version of the complex logic in traces.ts
 */
const extractTextFromObject = (obj: object): string | undefined => {
  // Common text fields to check
  const textFields = [
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

  for (const field of textFields) {
    const value = (obj as Record<string, unknown>)[field];
    if (isString(value) && value.trim()) {
      return value;
    }
  }

  // Check if it's a single-key object with a string value
  const keys = Object.keys(obj);
  if (keys.length === 1) {
    const value = (obj as Record<string, unknown>)[keys[0]];
    if (isString(value) && value.trim()) {
      return value;
    }
  }

  return undefined;
};

export default MessageRenderer;
