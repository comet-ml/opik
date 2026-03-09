import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import LinkifyText from "@/components/shared/LinkifyText/LinkifyText";
import { prettifyMessage } from "@/lib/traces";
import useLocalStorageState from "use-local-storage-state";
import { useTruncationEnabled } from "@/components/server-sync-provider";

type CustomMeta = {
  fieldType: "input" | "output";
};

const MAX_DATA_LENGTH_KEY = "pretty-cell-data-length-limit";
const MAX_DATA_LENGTH = 10000;

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const truncationEnabled = useTruncationEnabled();
  const [maxDataLength] = useLocalStorageState(MAX_DATA_LENGTH_KEY, {
    defaultValue: MAX_DATA_LENGTH,
  });
  const { custom } = context.column.columnDef.meta ?? {};
  const { fieldType = "input" } = (custom ?? {}) as CustomMeta;
  const value = context.getValue() as string | object | undefined | null;

  const displayMessage = useMemo(() => {
    if (!value) return "-";

    const pretty = prettifyMessage(value, { type: fieldType });

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
  }, [value, fieldType, truncationEnabled, maxDataLength]);

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  const content = useMemo(() => {
    if (isSmall) {
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
  }, [isSmall, displayMessage]);

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
