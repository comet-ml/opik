import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";
import LinkifyText from "@/shared/LinkifyText/LinkifyText";
import { prettifyMessage } from "@/lib/traces";
import useLocalStorageState from "use-local-storage-state";
import { useTruncationEnabled } from "@/contexts/server-sync-provider";
import { hasOpenInferenceHint } from "@/lib/openinference";

type CustomMeta = {
  fieldType: "input" | "output";
  colorIndicator?: boolean;
};

const MAX_DATA_LENGTH_KEY = "pretty-cell-data-length-limit";
const MAX_DATA_LENGTH = 10000;

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const truncationEnabled = useTruncationEnabled();
  const [maxDataLength] = useLocalStorageState(MAX_DATA_LENGTH_KEY, {
    defaultValue: MAX_DATA_LENGTH,
  });
  const { custom } = context.column.columnDef.meta ?? {};
  const { fieldType = "input", colorIndicator = false } = (custom ??
    {}) as CustomMeta;
  const value = context.getValue() as string | object | undefined | null;
  const row = context.row.original as {
    input?: object | string;
    output?: object | string;
    metadata?: object;
  };
  const rowInput = row.input;
  const openInferenceHint = hasOpenInferenceHint(
    row.metadata,
    row.input,
    row.output,
  );

  const displayMessage = useMemo(() => {
    const pretty = prettifyMessage(value ?? undefined, {
      type: fieldType,
      openInferenceInput: fieldType === "output" ? rowInput : undefined,
      openInferenceHint,
    });

    if (!pretty.message) return "-";

    let message: string;
    if (isObject(pretty.message)) {
      message = JSON.stringify(value, null, 2);
    } else {
      message = pretty.message || "";
    }

    if (truncationEnabled && message.length > maxDataLength) {
      return message.slice(0, maxDataLength) + " [truncated]";
    }

    return message;
  }, [
    value,
    fieldType,
    rowInput,
    openInferenceHint,
    truncationEnabled,
    maxDataLength,
  ]);

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isTruncated = rowHeight !== ROW_HEIGHT.large;

  const content = useMemo(() => {
    if (isTruncated) {
      return (
        <CellTooltipWrapper content={displayMessage}>
          <span className="comet-code truncate">
            <LinkifyText>{displayMessage}</LinkifyText>
          </span>
        </CellTooltipWrapper>
      );
    }

    return (
      <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
        <LinkifyText>{displayMessage}</LinkifyText>
      </div>
    );
  }, [isTruncated, displayMessage]);

  const indicatorColor = colorIndicator
    ? fieldType === "input"
      ? "var(--color-green)"
      : "var(--chart-violet)"
    : null;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {indicatorColor && (
        <div
          className="mr-2 shrink-0 self-stretch rounded-full"
          style={{ width: 3, backgroundColor: indicatorColor }}
        />
      )}
      {content}
    </CellWrapper>
  );
};

export default PrettyCell;
