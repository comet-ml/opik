import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { prettifyMessage } from "@/lib/traces";
import useLocalStorageState from "use-local-storage-state";
import sanitizeHtml from "sanitize-html";

const MAX_DATA_LENGTH_KEY = "pretty-cell-data-length-limit";
const MAX_DATA_LENGTH = 10000;
const MAX_DATA_LENGTH_MESSAGE = "Preview limit exceeded";

/**
 * Strips HTML/Markdown tags from text to show clean plain text
 */
const stripHtmlTags = (text: string): string => {
  // First, sanitize all HTML tags and entities
  let sanitized = sanitizeHtml(text, {
    allowedTags: [],
    allowedAttributes: {},
  });

  // Then, convert HTML-specific newlines/entities (from previous UI) if desired
  sanitized = sanitized
    .replace(/<br\s*\/?>/gi, "\n") // Convert <br> tags (just in case any survived)
    .replace(/<\/p>/gi, "\n\n") // Convert </p> tags to double newlines
    .replace(/<\/div>/gi, "\n") // Convert </div> tags to newlines
    .replace(/&nbsp;/g, " ") // Replace &nbsp; with regular spaces
    .replace(/\n\s*\n\s*\n/g, "\n\n") // Replace multiple newlines with double newlines
    .trim();

  return sanitized;
};

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const [maxDataLength] = useLocalStorageState(MAX_DATA_LENGTH_KEY, {
    defaultValue: MAX_DATA_LENGTH,
  });
  const value = context.getValue() as string | object | undefined | null;

  const rawValue = useMemo(() => {
    let text = "";
    if (isObject(value)) {
      text = JSON.stringify(value, null, 2);
    } else {
      text = value ?? "-";
    }

    return text;
  }, [value]);

  const hasExceededLimit = useMemo(
    () => rawValue.length > maxDataLength,
    [rawValue, maxDataLength],
  );

  const response = useMemo(() => {
    if (!value || hasExceededLimit) {
      return {
        message: "",
        prettified: false,
      };
    }

    return prettifyMessage(value);
  }, [value, hasExceededLimit]);

  const message = useMemo(() => {
    if (isObject(response.message)) {
      return JSON.stringify(value, null, 2);
    }
    return response.message || "";
  }, [response.message, value]);

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  const content = useMemo(() => {
    // Don't strip HTML tags as the message is already plain text from prettifyMessage
    // The prettifyMessage function extracts plain text, so we don't need to sanitize it
    const displayMessage = message;

    if (isSmall) {
      return (
        <CellTooltipWrapper
          content={hasExceededLimit ? MAX_DATA_LENGTH_MESSAGE : displayMessage}
        >
          <span className="comet-code truncate">
            {hasExceededLimit
              ? rawValue.slice(0, maxDataLength) + "..."
              : displayMessage}
          </span>
        </CellTooltipWrapper>
      );
    }

    return (
      <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
        {hasExceededLimit ? MAX_DATA_LENGTH_MESSAGE : displayMessage}
      </div>
    );
  }, [isSmall, hasExceededLimit, message, rawValue, maxDataLength]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {content}
    </CellWrapper>
  );
};

export default PrettyCell;
