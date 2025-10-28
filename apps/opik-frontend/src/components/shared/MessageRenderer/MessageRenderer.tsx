import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
import isUndefined from "lodash/isUndefined";
import isNull from "lodash/isNull";
import isArray from "lodash/isArray";

import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import JsonMarkdown from "@/components/shared/JsonMarkdown/JsonMarkdown";
import { cn, toString } from "@/lib/utils";
import { extractTextFromObject } from "@/lib/traces";
import {
  parseArrayFromString,
  formatArrayAsOrderedList,
  isArrayLikeString,
} from "@/lib/arrayParser";

const DEFAULT_JSON_TABLE_MAX_DEPTH = 10;

export interface MessageRendererProps {
  /**
   * The message data to render. Can be a string, object, null, or undefined.
   */
  message: unknown;

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
   * Context for text extraction - helps prioritize the correct field when extracting text
   */
  extractionContext?: "input" | "output";

  /**
   * Fallback content to show when message is null/undefined
   * @default "-"
   */
  fallbackContent?: React.ReactNode;
}

/**
 * A general-purpose message renderer that handles different data types:
 * - Arrays: Rendered as ordered lists (OL)
 * - Objects: Rendered as JSON using JsonKeyValueTable
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
  extractionContext,
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
      if (isArrayLikeString(message)) {
        const arrayContent = parseArrayFromString(message);
        if (arrayContent) {
          return <MarkdownPreview>{arrayContent}</MarkdownPreview>;
        }
      }

      return <MarkdownPreview>{message}</MarkdownPreview>;
    }

    // Handle arrays - convert to ordered list
    if (isArray(message)) {
      const arrayContent = formatArrayAsOrderedList(message);
      return <MarkdownPreview>{arrayContent}</MarkdownPreview>;
    }

    // Handle objects
    if (isObject(message)) {
      // Try to extract text if enabled
      if (attemptTextExtraction) {
        const extractedText = customTextExtractor
          ? customTextExtractor(message)
          : extractTextFromObject(message, extractionContext);

        if (extractedText) {
          // Handle structured result from extractTextFromObject
          if (
            typeof extractedText === "object" &&
            extractedText !== null &&
            "renderType" in extractedText
          ) {
            const structuredResult = extractedText as {
              renderType: string;
              data: unknown;
            };
            if (structuredResult.renderType === "json-table") {
              return (
                <JsonMarkdown
                  data={structuredResult.data}
                  maxDepth={DEFAULT_JSON_TABLE_MAX_DEPTH}
                />
              );
            }
          }

          const textContent =
            typeof extractedText === "string"
              ? extractedText
              : JSON.stringify(extractedText, null, 2);
          return <MarkdownPreview>{textContent}</MarkdownPreview>;
        }
      }

      // Fall back to JSON table for objects that can't be prettified as text
      return (
        <JsonMarkdown
          data={message}
          maxDepth={DEFAULT_JSON_TABLE_MAX_DEPTH}
        />
      );
    }

    // Handle other types (numbers, booleans, etc.)
    // Type guard to ensure we only call toString with appropriate types
    if (
      typeof message === "string" ||
      typeof message === "number" ||
      typeof message === "boolean" ||
      message === null
    ) {
      return <MarkdownPreview>{toString(message)}</MarkdownPreview>;
    }
    // Fallback for any other unknown types
    return <MarkdownPreview>{String(message)}</MarkdownPreview>;
  }, [
    message,
    attemptTextExtraction,
    customTextExtractor,
    extractionContext,
    fallbackContent,
  ]);

  return (
    <div className={cn("message-renderer", className)}>{renderedContent}</div>
  );
};

export default MessageRenderer;
